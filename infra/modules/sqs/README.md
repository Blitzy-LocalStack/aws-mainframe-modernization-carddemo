# `infra/modules/sqs/` — Request/reply queues replacing IBM MQ

**Purpose.** This module provisions the ten Amazon SQS queues that carry every
asynchronous message exchange in the migrated CardDemo stack — five primary
queues, each paired with a dead-letter queue of its own — together with the
resource policies that constrain their transport and their dead-letter
admission. Between them they replace the five IBM MQ queues the baseline used
for the pending-authorization exchange, for the account and date inquiry
exchanges, and as the terminal error sink.

**Source of truth.** Two sources, and neither of them is this document. Every
statement about baseline behaviour derives from the COBOL programs, the
extension READMEs and the scheduler definition under `app/**`, which is
reference-only: it is read here and never modified, and every line number cited
below was verified against the file it names. Every statement about the target
derives from decision D4 of the technical specification, whose full comparison
is owned by [ADR-004](../../../docs/adr/ADR-004-messaging.md). Where the two
disagree about what a queue is able to express, the difference is recorded
rather than closed quietly — see
[the message-expiry semantic gap](#the-message-expiry-semantic-gap).

This README is the mandatory Explainability carrier for the module's HCL.
Terraform has no docstring construct, so the file-header blocks, the
`description` on every variable and output, and the why-comment beside each
non-obvious argument in the four `.tf` files supply the mechanical half of the
project's documentation rule, while this document supplies the prose half. See
the [documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md).

## What this module provisions

Ten `aws_sqs_queue` resources. Five are primary queues and five are
dead-letter queues, one dedicated to each primary queue.

| Logical key | Composed name | Type | Role |
|---|---|---|---|
| `pauth_request` | `<name_prefix>-pauth-request-<environment>.fifo` | FIFO | Inbound pending-authorization requests |
| `pauth_request_dlq` | `<name_prefix>-pauth-request-<environment>-dlq.fifo` | FIFO | Quarantine for the above |
| `pauth_reply` | `<name_prefix>-pauth-reply-<environment>.fifo` | FIFO | Outbound authorization decisions |
| `pauth_reply_dlq` | `<name_prefix>-pauth-reply-<environment>-dlq.fifo` | FIFO | Quarantine for the above |
| `inquiry_request` | `<name_prefix>-inquiry-request-<environment>` | standard | Both inquiry flows — account details and date conversion — consumed by `account-service` as the single owning dispatcher |
| `inquiry_request_dlq` | `<name_prefix>-inquiry-request-<environment>-dlq` | standard | Quarantine for the above |
| `inquiry_reply` | `<name_prefix>-inquiry-reply-<environment>` | standard | Replies for both inquiry flows |
| `inquiry_reply_dlq` | `<name_prefix>-inquiry-reply-<environment>-dlq` | standard | Quarantine for the above |
| `error` | `<name_prefix>-error-<environment>` | standard | Terminal error sink for every producer |
| `error_dlq` | `<name_prefix>-error-<environment>-dlq` | standard | Quarantine for the above |

Four of the ten set `fifo_queue`, not two. Assumptions: AWS refuses a
standard dead-letter queue for a FIFO source, so each authorization
dead-letter queue has to be FIFO itself. The same constraint dictates the name
shape: `.fifo` must be the last characters of a FIFO queue's name, which is why
those names read `<base>-dlq.fifo` and never `<base>.fifo-dlq`.

Every one of the ten is encrypted with the customer-managed key the caller
supplies as `kms_key_arn`, produced by [the `kms` module](../kms/README.md).
Every one of the five primary queues redrives to its own dead-letter queue once
a message has been received `max_receive_count` times.

Alongside the queues the module creates two policy resources, both declared once
with `for_each` rather than copied per queue:

- One `aws_sqs_queue_policy`, instantiated **ten** times — once per queue.
  Each instance denies any request whose `aws:SecureTransport` condition key is
  false, on that queue's own exact ARN. Assumptions: an identity policy can
  restrict which principal may call SQS but cannot require that the signed
  request travelled over TLS, so this is an independent transport control rather
  than a duplicate of one. On the two FIFO authorization dead-letter queues, and
  only there, a second statement additionally denies
  `sqs:StartMessageMoveTask`; the reason is given under
  [ordering and idempotency](#ordering-and-idempotency-why-only-the-authorization-pair-is-fifo).
- One `aws_sqs_queue_redrive_allow_policy`, instantiated **five** times — once per
  dead-letter queue — each admitting exactly one source queue ARN through
  `redrivePermission = "byQueue"`. Assumptions: it is a standalone resource
  rather than an inline argument because inline is not merely untidy but
  impossible: the dead-letter queue would reference its source's ARN while that
  source already references the dead-letter queue's ARN in its
  `redrive_policy`, and Terraform builds its graph per resource, so the mutual
  reference fails at `validate` with a cycle error. Alternatives Considered:
  relying on identity policies alone, since only a principal holding
  `sqs:StartMessageMoveTask` can redrive at all. That was rejected because the
  identity model bounds who may redrive but not which source a dead-letter queue
  will accept, so a misconfigured `redrive_policy` on an unrelated queue could
  still land foreign messages in an authorization dead-letter queue and have
  them replayed as though they belonged there.

Note the module boundary: this module **calls no sibling module and declares no
data source**. Assumptions: the encryption key arrives as a variable from the
calling root rather than being read here, because a module that names
`module.kms` can only be called from a configuration that happens to contain
that sibling under that name — and this module is called from two roots. Keeping
the key an input also leaves the directory free of any account identifier or
region literal.

## Baseline MQ to target SQS mapping

Five MQ queues become five primary queues, one for one. The next section
reconciles that against the seven distinct queue-name literals the baseline
contains.

| Target primary queue | Type | Replaces | Verified at |
|---|---|---|---|
| `<name_prefix>-pauth-request-<environment>.fifo` | FIFO | `AWS.M2.CARDDEMO.PAUTH.REQUEST` | `app/app-authorization-ims-db2-mq/README.md:278` — "Input queue for authorization requests" |
| `<name_prefix>-pauth-reply-<environment>.fifo` | FIFO | `AWS.M2.CARDDEMO.PAUTH.REPLY` | same README `:279` — "Output queue for authorization responses" |
| `<name_prefix>-inquiry-request-<environment>` | standard | `CARDDEMO.REQUEST.QUEUE`, both legs | `app/app-vsam-mq/README.md:53` (`DEFINE QLOCAL`), aliased at `:71` (`MQQUEUE(CARDREQ)`) |
| `<name_prefix>-inquiry-reply-<environment>` | standard | `CARDDEMO.RESPONSE.QUEUE` | same README `:54` and `:72` (`MQQUEUE(CARDRES)`) |
| `<name_prefix>-error-<environment>` | standard | `CARD.DEMO.ERROR` | `app/app-vsam-mq/cbl/CODATE01.cbl:243` and `app/app-vsam-mq/cbl/COACCT01.cbl:294` |

One shared error queue serves every producer rather than one per flow.
Assumptions: both inquiry programs move the identical `CARD.DEMO.ERROR` literal
into `ERROR-QUEUE-NAME`, at the two lines cited in the final row above, so a
single sink is what the baseline already had. Splitting it per flow would invent
a distinction the source does not make, and would leave an operator watching
several queues for a condition that was previously visible in one place.

### Seven baseline literals, five primary queues

A reader who greps the baseline for queue names finds **seven** distinct
literals, not five. The inventory is complete: those seven literals
occur in exactly four files — the two extension READMEs and the two inquiry
programs. Recording the reconciliation here is what stops a later reader
concluding that queues are missing and provisioning extras.

| # | Baseline literal | Target disposition |
|---|---|---|
| 1 | `AWS.M2.CARDDEMO.PAUTH.REQUEST` | FIFO authorization request queue |
| 2 | `AWS.M2.CARDDEMO.PAUTH.REPLY` | FIFO authorization reply queue |
| 3 | `CARDDEMO.REQUEST.QUEUE` | Standard shared inquiry request queue, carrying both flows |
| 4 | `CARDDEMO.RESPONSE.QUEUE` | Standard inquiry reply queue |
| 5 | `CARD.DEMO.ERROR` | Standard error queue |
| 6 | `CARD.DEMO.REPLY.DATE` | Absorbed by the `replyToQueueUrl` contract |
| 7 | `CARD.DEMO.REPLY.ACCT` | Absorbed by the `replyToQueueUrl` contract |

**Why literal 3 stays one queue.** Refactoring Rationale: an earlier revision
split it into an account request queue and a date request queue, because one SQS
queue cannot have two *owning consumers* — SQS delivers no copy to each consumer,
inspects no message type before choosing one, and each receive hides the message
from the other, so discriminating after receipt lets the wrong service hide or
remove a sibling's message. That hazard is real, but the split answered it by
changing the queue inventory to six primaries, and the inventory is frozen
topology: AAP §0.4.1.8 maps five MQ queues to five target queues. The hazard is a
consumer-count hazard, so it is closed by consumer count instead — exactly ONE
service (`account-service`) binds a consumer to this queue, and that consumer
dispatches on the four-character function code carried in the request's first
field: `INQA` for the `COACCT01` account inquiry, `DATE` for the `CODATE01`
date-and-time inquiry, and the baseline's own invalid-parameters reply for
anything else. `com.carddemo.common.codec.InquiryRequestCodec` single-sources
that layout, and `COACCT01` itself branches on the same field at
`app/app-vsam-mq/cbl/COACCT01.cbl:393`.

Trade-offs: dispatch moves from the producer to the consumer, so the owning
service renders both answers. The account answer is data-bound and stays where
the data is; the date answer is a function of the clock and a fixed layout alone,
so its renderer is shared through `common-lib` rather than reached over the
network — no cross-context call, no machine-identity key and no new failure mode
on the message path. One behaviour changes and is registered in
[`cobol-to-service-traceability.md`](../../../docs/architecture/cobol-to-service-traceability.md):
`CODATE01` reads no field of its request and answers *any* function code with the
date, so an unrecognised code that reached its trigger queue got a date reply;
on the merged queue an unrecognised code gets `COACCT01`'s invalid-parameters
reply.

**Why literals 6 and 7 add no queues.** Refactoring Rationale: both are reply
queue *names*, not queues, and each program hard-codes its own — `MOVE
'CARD.DEMO.REPLY.DATE' TO REPLY-QUEUE-NAME` at
`app/app-vsam-mq/cbl/CODATE01.cbl:147` and `MOVE 'CARD.DEMO.REPLY.ACCT' TO
REPLY-QUEUE-NAME` at `app/app-vsam-mq/cbl/COACCT01.cbl:198`. The existence of a
per-flow reply-name literal in each program is precisely *why* the target uses
one shared reply queue plus an attribute instead: a request nominates its own
destination in a `replyToQueueUrl` message attribute and the responder sends
only to that nominated, IAM-authorized queue, so the per-flow indirection
survives as data on the message while the per-flow resource does not.
Assumptions: this rests on a correlation pattern the baseline documents itself,
listed at `app/app-vsam-mq/README.md:135` as "Message Correlation: Demonstrates
how to correlate request and response messages". No second reply queue is
missing.

## Ordering and idempotency: why only the authorization pair is FIFO

Two of the five primary queues are FIFO and three are standard. That split is the
module's central claim: message order is observable behaviour for authorization
and is required *per card*, whereas the inquiry exchange has no ordering
requirement at all.

**Per-card ordering.** Producers set `MessageGroupId` to the **card number
itself** and `MessageDeduplicationId` to the **transaction identifier itself**,
which §0.4.1.8 of the technical specification states literally and §0.7.6 repeats
for the grouping rule. Equal cards therefore produce one group by construction,
with no derivation for two producers to disagree about.

Refactoring Rationale: this paragraph described both identities as
purpose-scoped HMACs derived through `OpaqueIdentifier` from a key supplied via
`CARDDEMO_MESSAGING_HMAC_KEY` — a variable that no longer exists, since the bean it
keyed had no consumer once the identities became literal and the property, the
container secret and the provisioned Secrets Manager entry were all withdrawn with
it — so that the account number never entered queue
metadata. That derivation is withdrawn, because it removed the guarantees it was
layered on: a group identity orders one card's messages only while **every**
producer computes the same value for that card, and a deduplication identity
suppresses a resend only while the **requester** can predict it, so a value keyed
from one consumer's secret split a card across groups and let an honest resend
through as new. The consequence — a primary account number in queue metadata,
which server-side encryption of the body does not cover — is registered as
divergence `D-AUTHORIZATION-FIFO-IDENTITY-METADATA` in
`docs/architecture/cobol-to-service-traceability.md`, and this module supplies two
of the three controls that bound it: `sse_kms` encryption under the
customer-managed key this module is handed, and receive/send capability scoped by
the task-role policies built from its outputs. The third, private-network-only
reachability, comes from the interface endpoint in `infra/modules/network`.
Assumptions: a per-card group is implementable at all because the card number is
an explicit field of the baseline's comma-separated request — the field order is
listed from `app/app-authorization-ims-db2-mq/README.md:285` onward, with
`CARD-NUM` as its third field. Alternatives Considered: a single constant
message group would give total ordering across the whole stream, and was
rejected because it serialises every card behind every other and collapses
throughput to one consumer, in exchange for a guarantee nothing in the baseline
asks for — authorizations for two different cards have no ordering relationship
to preserve.

**Deduplication.** `MessageDeduplicationId` is the producer-supplied transaction
identifier, which is content-independent. Alternatives Considered:
`content_based_deduplication` is therefore set to `false` explicitly rather than
left to its default, because the difference between the two is a correctness
question and not a tuning one. With it true, SQS derives a SHA-256 identifier
from the body *only when* the producer omits an explicit
`MessageDeduplicationId`, so enabling that fallback would make an accidentally
omitted identifier look successful while changing semantics: two distinct
authorizations with identical bodies could collapse into one, and a resend whose
body had changed could be accepted as new. Stating `false` makes an omission
fail at `SendMessage` and keeps the producer-supplied identifier mandatory and
reviewable.

Assumptions: the guarantee this buys is **bounded**. The deduplication interval
is five minutes, so a duplicate arriving after it lapses is accepted as a new
message. The queue is a first line of defence and not the system of record for
idempotency — the durable backstop is the database.

**What the durable backstop actually enforces**, stated exactly, because the
difference from a looser reading of it decides whether a replay is rejected or
silently posted twice. The consumer's schema declares
`transaction_id` **NOT NULL** and carries the unique constraint
`uq_pending_auth_detail_card_transaction` over the **composite**
`(card_num, transaction_id)`, in
[`V1__authorization.sql`](../../../services/authorization-service/src/main/resources/db/migration/V1__authorization.sql),
and the consumer's replay lookup queries by that same composite. So:

* a redelivery of the same transaction identifier **for the same card** is
  rejected durably, past the five-minute window and across consumer restarts;
* the same transaction identifier **for a different card** is accepted, and that
  is correct rather than a gap — the identifier is only unique within its
  originating terminal's stream, and the message group that preserves ordering is
  the card number for the same reason.

Refactoring Rationale: this paragraph previously said only that a replayed
transaction identifier is "ultimately rejected", naming no constraint. At the time
it was written the column was neither `NOT NULL` nor unique nor indexed, so the
guarantee it described did not exist at all — a queue-tuning document was carrying
the load-bearing claim for a correctness property that nothing enforced. It now
names the constraint and the exact key, so the claim can be checked against the
migration rather than believed, and it names the composite rather than the
identifier alone, because a reader who assumed a single-column key would conclude
that a cross-card replay was a defect.

**The interdependent pair.** `deduplication_scope = "messageGroup"` and
`fifo_throughput_limit = "perMessageGroupId"` are set together on both FIFO
queues, and neither may be removed on its own: per-message-group throughput
requires the deduplication scope to be the message group, and AWS rejects the
combination that pairs per-message-group throughput with queue-wide
deduplication. Together they are also what actually *delivers* the
parallel-throughput half of the FIFO choice. Without them per-card ordering
would still hold, but throughput would be serialised queue-wide, so the FIFO
decision would cost throughput it had no need to cost. Neither argument is
repeated on the two FIFO dead-letter queues: Trade-offs: the only producer to a
dead-letter queue is SQS itself performing a redrive, so there is no throughput
to parallelise across groups, and setting them there would imply a concern those
queues do not have.

**Why the inquiry queues are standard.** Trade-offs: the inquiry exchange has no
ordering requirement, and FIFO requests are priced above standard requests, so
making those four queues FIFO would pay more for a guarantee the workload does
not need. The accepted cost is that two inquiry replies can be delivered out of
the order they were sent, which no baseline behaviour depends on.

**Dead-lettering a FIFO message is a quarantine boundary, not a claim of
uninterrupted order.** Trade-offs: SQS documents that moving a failed FIFO
message to a dead-letter queue can let later messages in that group proceed. The
target therefore guarantees per-card order while messages remain processable on
the source queue, preserves the failed message on an exact-source FIFO
dead-letter queue, denies `sqs:StartMessageMoveTask` on those two queues so that
an unfiltered bulk move cannot release a later message ahead of the reconciled
failed one, and requires reconciliation before a controlled per-message replay.
Availability for later authorizations is chosen over blocking one card
indefinitely.
[Messaging contracts](../../../docs/architecture/messaging-contracts.md) records
that bounded guarantee and its operator controls without claiming exact order
across the quarantine boundary.

**Two broker options were considered and rejected.** Alternatives Considered:
**a self-managed IBM MQ queue manager on AWS** — the closest alternative on
capability, because it speaks the MQ wire protocol itself, carries the same
message-descriptor concepts and honours `MQMD-EXPIRY` natively — and **Amazon MQ
for ActiveMQ or RabbitMQ**, the managed broker, which needs the same payload
translation this module's consumers need but does offer a per-message expiry
primitive that the chosen option lacks. Assumptions: those are two separate
options because Amazon MQ has no IBM MQ engine — its `engineType` admits
`ACTIVEMQ` and `RABBITMQ` only — so protocol fidelity and a managed broker cannot
be had together. The deciding factor against both was that each keeps a broker to
size, patch and fail over, and an idle cost floor, for a workload that needs no
broker capability. [ADR-004](../../../docs/adr/ADR-004-messaging.md) owns that
comparison in full and it is not restated here.

## Two messaging disciplines, not one

This is the single most consequential thing to know before changing anything in
this module, because a change that treats the five primary queues uniformly
breaks one of the two flows. The baseline's own MQ option words differ between
them.

**Authorization** receives with `MQGMO-NO-SYNCPOINT + MQGMO-WAIT` at
`app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:389` and replies with
`MQPMO-NO-SYNCPOINT` at `:753` via `MQPUT1` at `:758`, while committing its own
database work separately with `EXEC CICS SYNCPOINT` at `:335`. The reply is
therefore published outside the unit of work that committed the decision, which
leaves a narrow window in which the data records a decision whose reply was
never sent. That is the behaviour being migrated, stated as fact.

Assumptions: the queue set expresses only what a queue can express, and that
window is not one of them. It is closed by a transactional outbox in
`authorization-service`, which writes the reply in the same transaction as the
decision and publishes it afterwards. **Nothing in this module implements that
outbox, and nobody should look for it here.**

**Inquiry** both receives and replies under syncpoint — `MQGMO-SYNCPOINT` at
`app/app-vsam-mq/cbl/CODATE01.cbl:296` and `app/app-vsam-mq/cbl/COACCT01.cbl:347`,
with `MQPMO-SYNCPOINT` on the replies at `CODATE01.cbl:379` and `:416` and at
`COACCT01.cbl:475` and `:512` — so a message is not removed until the unit of
work commits. Refactoring Rationale: that discipline *is* expressible as queue
configuration, and it maps onto `visibility_timeout_seconds` together with
delete-on-success. The value has to bound the consumer's processing time: set it
below that time and a message is redelivered while the first attempt is still
running, which both duplicates the work and spends receives against
`max_receive_count`, so a message can reach a dead-letter queue having in fact
succeeded every time. That failure mode is silent, which is why it is recorded
rather than left to be inferred.

### Two preserved constants, both consumer-side

Assumptions: the baseline's receive discipline carries two numbers, and only one
of them is a queue attribute. Both are recorded so that nobody searches the
provider for an argument that does not exist.

- **The five-second wait is queue configuration.** `COPAUA0C.cbl:242` sets `MOVE
  5000 TO WS-WAIT-INTERVAL` and passes it to `MQGMO-WAITINTERVAL` at `:393`. MQ
  expresses that field in **milliseconds**, so 5000 is **five seconds** — the
  literal misleads by three orders of magnitude read on its own. The same
  five-second wait appears independently in both inquiry flows, at
  `CODATE01.cbl:286` and `COACCT01.cbl:337`. All three flows already waited the
  same interval, so `receive_wait_time_seconds` defaulting to `5` is a measured
  baseline constant rather than a chosen one. Trade-offs: the same value
  independently suppresses empty receives, since at a wait of zero an idle
  consumer bills one request per loop iteration; what is given up is up to five
  seconds of added latency for a message arriving just after a receive returned
  empty.
- **The five-hundred-message limit is not.** `COPAUA0C.cbl:40` declares
  `WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500` and tests it at `:339`,
  ending the loop after five hundred messages. SQS has no
  messages-per-consumer-loop attribute, so that limit becomes a bounded
  long-poll loop in `authorization-service`. It is **consumer-side** and appears
  nowhere in this module.

### Why `max_receive_count` defaults to five

Assumptions: the figure is corroborated twice over rather than picked. It is the
value the messaging design fixes, and the baseline was already operated to the
same retry budget — `app/scheduler/CardDemo.controlm` carries `MAXRERUN="5"` on
all fifteen of its `<JOB>` elements, including lines 4, 8, 14, 20 and 27. The
only two elements that differ are the two `<SMART_FOLDER>` elements at `:32` and
`:57`, which carry `MAXRERUN="0"` and schedule rather than run work.

## The message-expiry semantic gap

This gap is recorded rather than papered over, because claiming parity here
would be wrong.

**The baseline behaviour.** The authorization reply carries a five-second
expiry: `MOVE 50 TO MQMD-EXPIRY OF MQM-MD-REPLY` at
`app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:750`. Assumptions: the MQ
expiry field is expressed in **tenths of a second**, so `50` is **five
seconds** — the literal is misleading by an order of magnitude read on its own,
which is why the conversion is stated rather than assumed. The line immediately
above it, `:749`, corroborates the intent by marking the same reply
`MQPER-NOT-PERSISTENT`: a short-lived, non-durable message.

**The gap.** SQS has **no per-message time-to-live**. Queue-level retention
exists; per-message expiry does not. There is no argument in this module that
corresponds to `MQMD-EXPIRY`, and none is invented.

**The resolution has three parts, and only the third is this module's.**

1. The producer stamps an `expiresAt` message attribute. Consumer-side.
2. The consumer honours it by dropping **and logging** stale messages. Also
   consumer-side. Assumptions: the logging is not incidental — a silently
   dropped reply is indistinguishable from a lost one, so an unlogged drop would
   remove the only evidence that the mechanism worked as designed.
3. Short retention on the reply queues, through
   `reply_message_retention_seconds`. **This is the part this module
   implements**, and it is the only part of the three that appears in the HCL.

That third part is deliberately not tuned down to five seconds. Assumptions: the
value is validated to exceed `visibility_timeout_seconds × (max_receive_count +
1) + receive_wait_time_seconds`, so a failing reply survives every receive cycle
and the final move to its dead-letter queue instead of expiring mid-retry.
Business staleness belongs to the `expiresAt` attribute; queue retention
implements transport recovery.

**What is not equivalent.** Trade-offs: the baseline's expiry was
**broker-enforced** — MQ itself discarded the message once the interval lapsed.
The target's is **consumer-enforced**, so a message can sit on a queue past its
nominal expiry until a consumer reads it and discards it. The observable outcome
that no stale reply is acted upon is preserved; the mechanism that produces it
is not the same one, and the retained-but-expired interval has no baseline
counterpart. A further consequence follows in the same direction: the resulting
transport lifetime exceeds that of the non-persistent reply at
`COPAUA0C.cbl:749-750`, and that extra durability is restricted to diagnosis and
recovery — the dead-letter queue now records a failure that a sixty-second
source retention could previously erase.

The decision record is [ADR-004](../../../docs/adr/ADR-004-messaging.md).

## Retired with no target analogue

Retired, not missing. The distinction matters: an omission is an oversight to be
corrected, whereas a retirement is a resource type the target has no need of.

- **`DEFINE MQCONN(MQ01) GROUP(CARDDEMO)`**, at `app/app-vsam-mq/README.md:70`.
  Refactoring Rationale: SQS is reached over the AWS API with IAM credentials and
  needs no broker connection object at all, so there is no target analogue and
  none is invented.
- **`DEFINE MQQUEUE(CARDREQ)` and `DEFINE MQQUEUE(CARDRES)`**, at the same
  README `:71` and `:72`, which alias the two inquiry queue names for CICS.
  Refactoring Rationale: the alias indirection is retired on the same grounds,
  but its *role* survives — letting a program name a logical queue and resolve
  it to a physical one at run time is filled by a Parameter Store lookup of the
  queue URL. The indirection continues; the resource type does not.

Assumptions: there is no CSD artifact to map, which is what makes this a
retirement rather than a dropped resource. A search for
`MQCONN|MQQUEUE|MQMONITOR|MQINI` across **all four** CSD files in the
repository — `app/csd/CARDDEMO.CSD`, `app/app-vsam-mq/csd/CRDDEMOM.csd`,
`app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd` and
`app/app-transaction-type-db2/csd/CRDDEMOD.csd` — returns no hits at all. Those
definitions exist only as installation prose in that one README and were never
deployed CICS resources, so nothing was lost in translation.

One related contract is worth naming because it cannot be configured on a queue
and a reader may go looking. The MQ message descriptor becomes message
**attributes**: the correlation identifier becomes `correlationId`, the message
identifier `messageId`, the reply-to queue `replyToQueueUrl`, and `MQFMT-STRING`
a `contentType` of `text/csv`. Assumptions: the correlation identifier is the
load-bearing one, and the baseline saves and echoes it in all three flows. None
of it is a queue argument, and its absence here is not a missing provider
feature.

## Module boundary and usage

This directory is a called module, not a Terraform root. It is consumed from the
two environment roots:

```hcl
module "sqs" {
  source = "../../modules/sqs"

  environment = var.environment
  kms_key_arn = module.kms.sqs_key_arn

  tags = local.common_tags
}
```

The `kms_key_arn` value comes from the `kms` module's SQS key output **in the
calling root**, which is what keeps this module free of a sibling reference. The
argument is validated against the shape
`arn:aws:kms:<region>:<aws-account-id>:key/<key-id>`; a bare key id or an alias
reference is rejected at `plan`. Every identifier in this document is a
placeholder — the module contains no account identifier, no region literal, no
queue URL and no key material.

The calling `dev` and `prod` roots own provider configuration, default tags and
the state backend. This module therefore has no `backend` block, no provider
configuration body and no sibling `module` block. Environment differences are
confined to caller-supplied parameters such as retention and receive counts;
both environments keep the same ten-queue topology.

**There is deliberately no `terraform apply` or `terraform destroy` command in
this section.** Assumptions: a module cannot be applied on its own — those
commands belong to the environment roots, and running them here would be
impossible rather than merely unusual. The real deploy and teardown sequences
live in the [infrastructure guide](../../README.md), the
[deployment runbook](../../../docs/runbooks/deploy.md) and the
[teardown runbook](../../../docs/runbooks/teardown.md).

### How the outputs are consumed

The module exposes a URL, an ARN and a bare name for each of the ten queues,
plus four maps keyed by the logical keys in the table above. A calling root
writes the queue URLs to Parameter Store, and each service resolves the URL it
needs at startup, so **no service hard-codes a queue endpoint**. The ARNs go
into the IAM task-role policies owned by
[`ecs-service`](../ecs-service/README.md) and
[`step-functions-batch`](../step-functions-batch/README.md); the bare names are
the `QueueName` dimension CloudWatch requires, consumed by
[`observability`](../observability/README.md).

The `service_queue_permissions` output publishes the exact per-service boundary
so a root does not have to reconstruct it:

| Service | Receives from | Sends to |
|---|---|---|
| `authorization-service` | `pauth_request` | `pauth_reply` |
| `account-service` | `inquiry_request` (sole receiving principal) | `inquiry_reply`, `error` |
| `batch-service` | *(nothing)* | `error` |

Assumptions: callers pass only the exact per-service subset into `ecs-service`.
Granting `values(queue_arns)` instead would hand every service every queue and
defeat the boundary these lists exist to create. There is no `reference-service`
row: that context answers date conversion synchronously and consumes no queue, so
granting it a receive action would license a second consumer on the shared
inquiry request queue — the one thing the single-owner rule forbids.

Refactoring Rationale: the `batch-service` row did not exist, and its absence was
the IAM half of a producer that could not publish. `batch-service` carries a queue
configuration whose only purpose is to notify this terminal sink that a nightly run
failed — the migration plan scopes such a configuration to exactly two bounded
contexts, batch and authorization — and the task role it ran under held no
`sqs:SendMessage` statement for any queue at all, so the first send the producer
ever attempted would have been refused on the failure path. The receive column is
empty and stays empty: `batch-service` declares no listener of any kind, selecting
its work from a command argument the orchestrator supplies and from its own tables,
so a receive grant it has no consumer for could only be used to drain a queue
another context is the sole consumer of. `ecs-service` derives the cardinality of
its queue policy from the length of each list, so a send-only entry produces a
send-only statement rather than an empty receive statement.

## Validation

```bash
# WHAT: run the gating infrastructure checks that cover this module, from the
#       repository root.
# WHY : Assumptions: this directory is a called module, so validating it in
#       isolation is a convenience rather than the authoritative check — the
#       authoritative one runs transitively when an environment root is
#       initialised. Running both catches a fault here before a root inherits
#       it.
. /etc/profile.d/00-carddemo-toolchain.sh
terraform fmt -check -recursive infra/modules/sqs
terraform -chdir=infra/modules/sqs init -backend=false -input=false
terraform -chdir=infra/modules/sqs validate
tflint --chdir=infra/modules/sqs --config="$(pwd)/infra/.tflint.hcl"
terraform-docs --config infra/.terraform-docs.yml --output-check infra/modules/sqs
```

Formatting, validation, linting and generated-document drift are all **gating**
checks in `.github/workflows/infra-ci.yml`, with no tolerance and no ignored
exit code. Trade-offs: the drift check is deliberately check-only and never
rewrites this file. An arrangement that regenerated the document in CI and
committed the result would convert a review gate into a silent mutation — the
pipeline would repair the drift, the build would go green, and the author would
never learn that the contract they published was wrong. The accepted cost is one
extra push when somebody forgets to regenerate.

The module is authored and statically validated. Applying a root against a live
AWS account remains an operator action outside this scope, so no claim is made
here that any queue has been created, encrypted in flight or assessed against a
compliance standard.

## What this module deliberately does not do

Each absence is recorded with its reason, so that none of them is mistaken for
an oversight.

- **No resource-based ALLOW statement.** Assumptions: every producer and consumer
  is a same-account IAM principal, so positive access stays in the
  identity-based task-role policies owned by `ecs-service` and
  `step-functions-batch`. The queue policies this module does create contain
  only denials, so they harden transport and dead-letter admission without
  duplicating a grant or widening a principal. Adding an allow here would make
  effective access the union of two policy surfaces. A cross-account producer
  would change that analysis; there is none.
- **No sixth primary queue.** The seven baseline literals reconcile to five
  primary queues, as tabulated
  [above](#seven-baseline-literals-five-primary-queues). Two of the seven are
  reply-queue names absorbed by the `replyToQueueUrl` attribute, and the shared
  request literal stays one queue with one owning consumer.
- **No broker resource.** Decision D4 rejected both broker options — a
  self-managed IBM MQ queue manager and an Amazon MQ ActiveMQ or RabbitMQ broker —
  so there is no broker, no broker subnet group and no broker credential anywhere
  in this design.
- **No Kafka and no Kinesis.** Alternatives Considered: streaming platforms are
  out of scope because the requirement these queues serve is request/reply,
  which SQS satisfies directly; a partitioned log would add retention and
  consumer-group semantics to a workload that needs neither.
- **No large-payload offload, and no offload bucket.** Assumptions: every
  payload sits far below the 256 KiB maximum, measured rather than assumed. The
  inquiry layouts at `app/app-vsam-mq/README.md:99-128` sum to 12 bytes for the
  date request, 22 for the date reply, 23 for the account request and 312 for
  the account reply — the largest being a single `X(300)` account-data field.
  The authorization payloads are an eighteen-field CSV request and a six-field
  CSV reply. `max_message_size` is therefore left at the service default:
  lowering it could only reject a message the design expects to succeed, and the
  maximum needs no raising.
- **No `delay_seconds`.** Assumptions: the baseline defers no delivery, and both
  request/reply exchanges are latency-sensitive — the authorization reply
  carrying the five-second expiry discussed above. A non-zero delay would
  postpone every message on the queue and could push a reply past that expiry
  before any consumer saw it, so the service default of no delay is what
  preserves the baseline's behaviour.
- **No `sqs_managed_sse_enabled`.** Assumptions: it and `kms_master_key_id` are
  mutually exclusive in the provider, so exactly one may appear. Only
  `kms_master_key_id` is set, on every queue; adding the other beside it would
  make the configuration self-contradicting rather than doubly encrypted.
- **No `provider` block and no `hashicorp/random` provider.** Assumptions: a
  module carrying its own provider configuration cannot be called with `count`,
  `for_each` or `depends_on`, and it takes the choice of region away from its
  caller. Nothing here generates a random value either — a queue needs neither a
  password nor a globally unique name — and an unused provider declaration would
  fail the gating `terraform_unused_required_providers` rule outright rather
  than merely read as untidy.
- **No `prevent_destroy` lifecycle.** Trade-offs: the acceptance criteria require
  an environment root to be destroyable, so pinning a queue against destruction
  would break a documented requirement in order to protect a queue whose
  contents are already retained by its dead-letter queue.
- **No direct `apply` or `destroy` from this directory.** Assumptions: this is a
  called module rather than a Terraform root, so lifecycle commands belong to
  the environment roots. The [module boundary and usage](#module-boundary-and-usage)
  section identifies the owning roots and links to their operator runbooks.

## Related documentation

- [Infrastructure guide](../../README.md) — the `infra/` package overview and the
  module catalogue this module belongs to.
- [ADR-004: messaging](../../../docs/adr/ADR-004-messaging.md) — the decision
  record for SQS over Amazon MQ, and for the expiry-gap resolution.
- [Messaging contracts](../../../docs/architecture/messaging-contracts.md) — the
  full queue mapping, CSV field order, correlation and ordering guarantees.
- [Deployment runbook](../../../docs/runbooks/deploy.md) and
  [teardown runbook](../../../docs/runbooks/teardown.md) — the exact commands,
  which belong to the environment roots rather than to this directory.
- [Documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md) — the
  convention this README is written to.
- [`kms` module](../kms/README.md) — the origin of the `kms_key_arn` input.

## Generated Terraform reference

The block below is generated by terraform-docs v0.20.0 from this module's HCL.
Everything inside the markers is regenerated from `versions.tf`,
`variables.tf`, `main.tf` and `outputs.tf`; edit those files rather than the
table, and regenerate with the command in
[Validation](#validation) above minus its `--output-check` flag.

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_sqs_queue.error](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue.error_dlq](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue.inquiry_reply](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue.inquiry_reply_dlq](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue.inquiry_request](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue.inquiry_request_dlq](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue.pauth_reply](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue.pauth_reply_dlq](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue.pauth_request](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue.pauth_request_dlq](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue) | resource |
| [aws_sqs_queue_policy.tls_only](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue_policy) | resource |
| [aws_sqs_queue_redrive_allow_policy.exact_source](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/sqs_queue_redrive_allow_policy) | resource |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_environment"></a> [environment](#input\_environment) | Environment token appended to every queue name, keeping the dev and prod queue sets distinct within a single account and region. Consumed by the naming locals in main.tf that compose each aws\_sqs\_queue name. | `string` | n/a | yes |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | ARN of the customer-managed KMS key used for server-side encryption of every queue and dead-letter queue, of the form arn:aws:kms:<region>:<aws-account-id>:key/<key-id>. Passed in by the calling root from the kms module's SQS key output and applied as kms\_master\_key\_id on each aws\_sqs\_queue. | `string` | n/a | yes |
| <a name="input_dlq_message_retention_seconds"></a> [dlq\_message\_retention\_seconds](#input\_dlq\_message\_retention\_seconds) | Seconds a message is retained on each of the five dead-letter queues, applied as message\_retention\_seconds to those queues. Defaults longer than either source retention, and is validated never to be shorter than the request retention, because a message only arrives here already aged. | `number` | `1209600` | no |
| <a name="input_kms_data_key_reuse_period_seconds"></a> [kms\_data\_key\_reuse\_period\_seconds](#input\_kms\_data\_key\_reuse\_period\_seconds) | Seconds SQS may reuse a KMS data key before calling KMS again, applied as kms\_data\_key\_reuse\_period\_seconds on every queue and dead-letter queue. | `number` | `300` | no |
| <a name="input_max_receive_count"></a> [max\_receive\_count](#input\_max\_receive\_count) | Receives a message may accumulate on a source queue before SQS moves it to that queue's dead-letter queue. Applied as maxReceiveCount in the redrive\_policy of all five source queues. | `number` | `5` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading token of every composed queue name; "carddemo" yields names such as carddemo-pauth-request-<environment>.fifo. Consumed by the naming locals in main.tf. | `string` | `"carddemo"` | no |
| <a name="input_receive_wait_time_seconds"></a> [receive\_wait\_time\_seconds](#input\_receive\_wait\_time\_seconds) | Seconds a receive call waits for a message before returning empty, applied as receive\_wait\_time\_seconds on every queue and dead-letter queue. Any non-zero value enables long polling. | `number` | `5` | no |
| <a name="input_reply_message_retention_seconds"></a> [reply\_message\_retention\_seconds](#input\_reply\_message\_retention\_seconds) | Seconds a message is retained on the two reply queues, applied as message\_retention\_seconds to those queues. Must exceed the complete visibility and receive-wait retry budget; consumer-side expiresAt remains the business-staleness authority. | `number` | `900` | no |
| <a name="input_request_message_retention_seconds"></a> [request\_message\_retention\_seconds](#input\_request\_message\_retention\_seconds) | Seconds a message is retained on the two request queues and on the error queue, applied as message\_retention\_seconds to those three queues. Sized to outlast an interruption and still permit a redrive from the dead-letter queue. | `number` | `345600` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Tags applied to every queue and dead-letter queue created by this module. Supplied as an input because the module declares no provider and so inherits no provider-level default\_tags. | `map(string)` | `{}` | no |
| <a name="input_visibility_timeout_seconds"></a> [visibility\_timeout\_seconds](#input\_visibility\_timeout\_seconds) | Seconds a received message stays invisible to other consumers before becoming available again, applied as visibility\_timeout\_seconds on every queue and dead-letter queue. Must exceed the consumer's processing time. | `number` | `60` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_error_dlq_arn"></a> [error\_dlq\_arn](#output\_error\_dlq\_arn) | ARN of the dead-letter queue serving the terminal error queue. Named as the Resource of the IAM statement that lets an operator drain it or issue the sqs:StartMessageMoveTask call that returns its messages to the error queue. |
| <a name="output_error_dlq_name"></a> [error\_dlq\_name](#output\_error\_dlq\_name) | Bare name of the dead-letter queue serving the terminal error queue. Consumed by the observability module as the QueueName dimension of its dead-letter depth alarm; a non-zero depth here means a failure report itself failed to be processed, which is the deepest failure this module can surface. |
| <a name="output_error_dlq_url"></a> [error\_dlq\_url](#output\_error\_dlq\_url) | Queue URL of the dead-letter queue serving the terminal error queue. An error sink with a dead-letter queue of its own reads oddly and is deliberate: the error queue is consumed like any other, so a message that defeats even the error handler would otherwise be redelivered indefinitely or lost, and this URL is where an operator can still read it. |
| <a name="output_error_queue_arn"></a> [error\_queue\_arn](#output\_error\_queue\_arn) | ARN of the standard terminal error queue (baseline CARD.DEMO.ERROR). This is the Resource a calling root names in the send-permission statement of every producing service's task-role policy, and in the receive-permission statement of whatever drains the sink to raise an alert or record the failure. |
| <a name="output_error_queue_name"></a> [error\_queue\_name](#output\_error\_queue\_name) | Bare name of the standard terminal error queue. Consumed by the observability module as the QueueName metric dimension. This queue's depth is the one queue metric that is a direct failure signal rather than a throughput signal, because nothing writes to it in normal operation. |
| <a name="output_error_queue_url"></a> [error\_queue\_url](#output\_error\_queue\_url) | Queue URL of the standard terminal error queue, which replaces the baseline's CARD.DEMO.ERROR -- the single error sink both inquiry programs write to, at MOVE 'CARD.DEMO.ERROR' TO ERROR-QUEUE-NAME in app/app-vsam-mq/cbl/CODATE01.cbl:243 and app/app-vsam-mq/cbl/COACCT01.cbl:294. Written to Parameter Store for every producer in the messaging design, so any service that cannot complete a message exchange reports it to one place rather than to a sink of its own. |
| <a name="output_inquiry_reply_dlq_arn"></a> [inquiry\_reply\_dlq\_arn](#output\_inquiry\_reply\_dlq\_arn) | ARN of the dead-letter queue serving the standard account-inquiry reply queue. Named as the Resource of the IAM statement that lets an operator drain it or issue the sqs:StartMessageMoveTask call that returns its messages to the inquiry reply queue. |
| <a name="output_inquiry_reply_dlq_name"></a> [inquiry\_reply\_dlq\_name](#output\_inquiry\_reply\_dlq\_name) | Bare name of the dead-letter queue serving the standard account-inquiry reply queue. Consumed by the observability module as the QueueName dimension of its dead-letter depth alarm. |
| <a name="output_inquiry_reply_dlq_url"></a> [inquiry\_reply\_dlq\_url](#output\_inquiry\_reply\_dlq\_url) | Queue URL of the dead-letter queue that receives an account-inquiry reply after max\_receive\_count failed receives. The reply source retention is validated to survive the complete retry budget, and an operator reads the quarantined message here using its opaque correlation identifier to tie it back to the request. |
| <a name="output_inquiry_reply_queue_arn"></a> [inquiry\_reply\_queue\_arn](#output\_inquiry\_reply\_queue\_arn) | ARN of the standard account-inquiry reply queue (baseline CARDDEMO.RESPONSE.QUEUE, plus the two per-flow reply names at CODATE01.cbl:147 and COACCT01.cbl:198 that this single queue absorbs). This is the Resource a calling root names to let account-service and reference-service send replies, and to let the requester receive them. |
| <a name="output_inquiry_reply_queue_name"></a> [inquiry\_reply\_queue\_name](#output\_inquiry\_reply\_queue\_name) | Bare name of the standard account-inquiry reply queue. Consumed by the observability module as the QueueName metric dimension. Because one queue carries both flows' replies, its metrics are shared: a backlog here is not attributable to the date flow or the account flow from queue metrics alone, and attribution comes from the correlation identifier on each message and from consumer-side metrics instead. |
| <a name="output_inquiry_reply_queue_url"></a> [inquiry\_reply\_queue\_url](#output\_inquiry\_reply\_queue\_url) | Queue URL of the standard account-inquiry reply queue, which replaces the baseline's CARDDEMO.RESPONSE.QUEUE, defined as DEFINE QLOCAL('CARDDEMO.RESPONSE.QUEUE') at app/app-vsam-mq/README.md:54. This ONE queue serves both inquiry flows even though the baseline hard-codes a reply-queue name per flow -- MOVE 'CARD.DEMO.REPLY.DATE' TO REPLY-QUEUE-NAME at app/app-vsam-mq/cbl/CODATE01.cbl:147 and MOVE 'CARD.DEMO.REPLY.ACCT' TO REPLY-QUEUE-NAME at app/app-vsam-mq/cbl/COACCT01.cbl:198 -- because the target routes a reply by the replyToQueueUrl message attribute the request carries rather than by a per-flow queue. No second reply queue is missing. Written to Parameter Store for account-service and reference-service to publish replies to. |
| <a name="output_inquiry_request_dlq_arn"></a> [inquiry\_request\_dlq\_arn](#output\_inquiry\_request\_dlq\_arn) | ARN of the dead-letter queue serving the inquiry request queue, for exact operator receive and StartMessageMoveTask permissions. |
| <a name="output_inquiry_request_dlq_name"></a> [inquiry\_request\_dlq\_name](#output\_inquiry\_request\_dlq\_name) | Bare name of the dead-letter queue serving inquiry requests, used as the QueueName dimension of its dead-letter alarm. |
| <a name="output_inquiry_request_dlq_url"></a> [inquiry\_request\_dlq\_url](#output\_inquiry\_request\_dlq\_url) | Queue URL of the dead-letter queue receiving inquiry requests after max\_receive\_count failed receives, whatever function code they carried. |
| <a name="output_inquiry_request_queue_arn"></a> [inquiry\_request\_queue\_arn](#output\_inquiry\_request\_queue\_arn) | ARN of the inquiry request queue. Exactly one task role -- the owning consumer's -- receives and deletes on this ARN; a second receiving principal would reintroduce the competing-consumer loss the single-owner rule exists to prevent. |
| <a name="output_inquiry_request_queue_name"></a> [inquiry\_request\_queue\_name](#output\_inquiry\_request\_queue\_name) | Bare name of the inquiry request queue. It is the CloudWatch QueueName dimension for inquiry depth and age, and it is also the identifier form the consuming service is configured with, because that service validates a queue NAME and resolves the address itself. |
| <a name="output_inquiry_request_queue_url"></a> [inquiry\_request\_queue\_url](#output\_inquiry\_request\_queue\_url) | Queue URL of the standard inquiry request queue replacing CARDDEMO.REQUEST.QUEUE. It carries BOTH inquiry flows -- the COACCT01 account inquiry and the CODATE01 date-and-time inquiry -- and exactly one service binds a consumer to it, dispatching on the four-character function code in the request's first field. |
| <a name="output_pauth_reply_dlq_arn"></a> [pauth\_reply\_dlq\_arn](#output\_pauth\_reply\_dlq\_arn) | ARN of the dead-letter queue serving the FIFO pending-authorization reply queue. Named by reviewed receive/delete permissions for reconciliation. Native sqs:StartMessageMoveTask is denied because unfiltered bulk redrive cannot preserve the per-card ordering boundary across quarantine. |
| <a name="output_pauth_reply_dlq_name"></a> [pauth\_reply\_dlq\_name](#output\_pauth\_reply\_dlq\_name) | Bare name of the dead-letter queue serving the FIFO pending-authorization reply queue. Consumed by the observability module as the QueueName dimension of its dead-letter depth alarm. |
| <a name="output_pauth_reply_dlq_url"></a> [pauth\_reply\_dlq\_url](#output\_pauth\_reply\_dlq\_url) | Queue URL of the dead-letter queue that receives a pending-authorization reply after max\_receive\_count failed receives. An operator investigating an authorization whose reply never arrived reads it here; the source retention is validated to survive every receive cycle, and this fourteen-day quarantine keeps the failed delivery available for reconciliation. |
| <a name="output_pauth_reply_queue_arn"></a> [pauth\_reply\_queue\_arn](#output\_pauth\_reply\_queue\_arn) | ARN of the FIFO pending-authorization reply queue (baseline AWS.M2.CARDDEMO.PAUTH.REPLY). This is the Resource a calling root names in the task-role policy statement granting authorization-service permission to send replies, and the form a consumer of those replies is granted receive permission against. |
| <a name="output_pauth_reply_queue_name"></a> [pauth\_reply\_queue\_name](#output\_pauth\_reply\_queue\_name) | Bare name of the FIFO pending-authorization reply queue. Consumed by the observability module as the QueueName metric dimension; its retention exceeds the complete configured retry budget, while consumer-side expiresAt remains the authority that decides whether a reply is still actionable. |
| <a name="output_pauth_reply_queue_url"></a> [pauth\_reply\_queue\_url](#output\_pauth\_reply\_queue\_url) | Queue URL of the FIFO pending-authorization reply queue, which replaces the baseline's AWS.M2.CARDDEMO.PAUTH.REPLY, listed as the "Output queue for authorization responses" at app/app-authorization-ims-db2-mq/README.md:279. Written to Parameter Store for authorization-service, whose transactional outbox publishes a reply here with a send call once the decision it reports has committed. |
| <a name="output_pauth_request_dlq_arn"></a> [pauth\_request\_dlq\_arn](#output\_pauth\_request\_dlq\_arn) | ARN of the dead-letter queue serving the FIFO pending-authorization request queue. It is the Resource form an IAM statement names for reviewed receive/delete access during reconciliation. Native sqs:StartMessageMoveTask is explicitly denied because it cannot filter one message group and can interleave quarantined messages with new traffic; replay is controlled per message after the failed group is reconciled. |
| <a name="output_pauth_request_dlq_name"></a> [pauth\_request\_dlq\_name](#output\_pauth\_request\_dlq\_name) | Bare name of the dead-letter queue serving the FIFO pending-authorization request queue. This is the QueueName dimension the observability module's dead-letter depth alarm is built on -- the alarm whose threshold defaults to the smallest breachable value, because this queue is empty in normal operation and any depth above zero is a real failure. |
| <a name="output_pauth_request_dlq_url"></a> [pauth\_request\_dlq\_url](#output\_pauth\_request\_dlq\_url) | Queue URL of the dead-letter queue that receives a pending-authorization request after max\_receive\_count failed receives. An operator establishing what defeated the consumer reads the message body with a receive call against this URL, which is the only way to see a payload SQS has moved off the request queue. |
| <a name="output_pauth_request_queue_arn"></a> [pauth\_request\_queue\_arn](#output\_pauth\_request\_queue\_arn) | ARN of the FIFO pending-authorization request queue (baseline AWS.M2.CARDDEMO.PAUTH.REQUEST). An ARN is the form an IAM policy Resource list and a redrive target accept, so a calling root places this value in the task-role policy statement -- owned by the ecs-service module -- that permits authorization-service to receive and delete from this queue. A queue URL is not accepted there. |
| <a name="output_pauth_request_queue_name"></a> [pauth\_request\_queue\_name](#output\_pauth\_request\_queue\_name) | Bare name of the FIFO pending-authorization request queue, carrying neither the account and region parts of an ARN nor the endpoint host of a URL. CloudWatch dimensions every SQS metric on QueueName, so this is the value the observability module needs to alarm on this queue's depth or age or to place it on a dashboard. |
| <a name="output_pauth_request_queue_url"></a> [pauth\_request\_queue\_url](#output\_pauth\_request\_queue\_url) | Queue URL of the FIFO pending-authorization request queue, which replaces the baseline's AWS.M2.CARDDEMO.PAUTH.REQUEST, listed as the "Input queue for authorization requests" at app/app-authorization-ims-db2-mq/README.md:278. A URL is the address the SQS send, receive and delete calls take, so a calling root writes this value to Parameter Store for authorization-service to resolve at startup and receive authorization requests from. |
| <a name="output_queue_arns"></a> [queue\_arns](#output\_queue\_arns) | Map of logical queue name to queue ARN for all ten queues, keyed identically to queue\_urls. Callers pass only the exact per-service subset into ecs-service's sqs\_send\_queue\_arns and sqs\_receive\_queue\_arns; using values(...) for all queues would defeat the confused-deputy boundary. |
| <a name="output_queue_names"></a> [queue\_names](#output\_queue\_names) | Map of logical queue name to bare queue name for all ten queues, keyed identically to queue\_urls. CloudWatch dimensions SQS metrics on QueueName, so inquiry depth, age and dead-letter signals are reported for the one shared request queue rather than per flow. |
| <a name="output_queue_urls"></a> [queue\_urls](#output\_queue\_urls) | Map of logical queue name to queue URL for all ten queues, keyed by the resource labels main.tf uses. There is ONE inquiry\_request key because the baseline defines one shared request destination and exactly one service consumes it. |
| <a name="output_service_queue_permissions"></a> [service\_queue\_permissions](#output\_service\_queue\_permissions) | Exact per-service SQS IAM boundaries. authorization-service receives pauth\_request and sends only pauth\_reply; account-service receives inquiry\_request -- the ONLY receiving principal on it -- and sends only inquiry\_reply/error; batch-service receives NOTHING and sends only error. There is no reference-service entry: that context answers date conversion synchronously and consumes no queue. Pass these lists to ecs-service rather than granting values(queue\_arns). |
<!-- END_TF_DOCS -->
