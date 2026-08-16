# =============================================================================
# infra/modules/sqs/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The ten queues of the `sqs` module: five primary queues, each paired with
#   a dead-letter queue of its own. They stand one-for-one against the five IBM
#   MQ queues
#   the CardDemo baseline used -- AWS.M2.CARDDEMO.PAUTH.REQUEST and
#   AWS.M2.CARDDEMO.PAUTH.REPLY carrying the pending-authorization exchange
#   (app/app-authorization-ims-db2-mq/README.md:278-279),
#   CARDDEMO.REQUEST.QUEUE and CARDDEMO.RESPONSE.QUEUE carrying account and date
#   inquiries (app/app-vsam-mq/README.md:53-54), and CARD.DEMO.ERROR as the
#   terminal error sink (app/app-vsam-mq/cbl/CODATE01.cbl:243 and
#   app/app-vsam-mq/cbl/COACCT01.cbl:294).
#
#   The authorization pair is FIFO and the other three are standard, and that
#   split is the module's central claim: message order is observable behaviour
#   for authorization and is required per card, whereas the inquiry exchange
#   has no ordering requirement at all. Every one of the ten queues is
#   encrypted with the customer-managed key the caller supplies, and every one
#   of the five primary queues redrives to its own dead-letter queue once a
#   message has been received max_receive_count times. Every queue also receives
#   an exact-ARN resource policy that denies requests made without TLS.
#
#   Two properties of the baseline shape this file and are easy to misread from
#   the source, so both are set out in full below rather than left to be
#   rediscovered: the two extensions do NOT share one messaging discipline, and
#   the baseline's ONE shared inquiry request queue stays one queue here -- so a
#   single owning consumer dispatches both inquiry flows on the request's own
#   function code -- while replies follow one dynamic replyToQueueUrl contract.
#
#   Parameters: none are declared in this file. Every input is declared in
#   variables.tf, which carries the description and the domain validation for
#   each one; the obligation discharged here is the why-comment on each
#   argument at the point that argument is consumed.
#
#   Return values: none are declared in this file. The queue URLs, ARNs and
#   names that the container services and the batch state machine consume are
#   declared in outputs.tf.
#
#   Errors:
#     * A message that exhausts max_receive_count receives on a primary queue
#       is moved by SQS to that queue's dead-letter queue. That is the designed
#       path rather than a fault, which is why the dead-letter retention is the
#       longest in the module -- the evidence has to outlive the failure.
#     * A kms_key_arn naming a key that does not exist, or one whose key policy
#       does not permit the SQS service principal, passes `plan` and fails
#       during `apply` against the first queue the provider reaches. The queue
#       set is then partially created, and the run has to be applied again once
#       the key is corrected.
#     * A FIFO queue whose redrive target is a standard queue is rejected by
#       AWS outright. Both authorization dead-letter queues are therefore FIFO
#       themselves, which is why four resources below set fifo_queue and not
#       two.
#     * An over-long name_prefix composes a queue name past the SQS limit of 80
#       characters, which counts the .fifo suffix. variables.tf refuses that at
#       `plan`; the arithmetic it guards is the naming block below.
#     * A signed request made over plaintext transport is denied by the queue's
#       own policy even when the caller's identity policy would otherwise allow
#       the action; transport enforcement is therefore independent of who calls.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the baseline is the specification for the constants this
#     module carries, and two of them cannot be read correctly without a unit
#     conversion -- MQ states a message expiry in tenths of a second and a wait
#     interval in milliseconds. Each conversion is stated at the point of use,
#     because the bare literal is misleading by an order of magnitude on its
#     own.
#   - Trade-offs: ten explicit resources rather than one `for_each` over a map
#     of five queue definitions. The loop would be materially shorter, and it
#     would also push every difference between the queues into a conditional
#     expression -- two queues are FIFO and carry four further arguments, while
#     replies take a different retention from requests -- when those differences
#     are the entire point of the module. Ten blocks also let each queue keep
#     its rationale beside the argument that needs it, which one templated
#     block cannot do.
#   - Alternatives Considered: no resource-based ALLOW statement is created.
#     Access is granted by the identity-based task-role policies owned by the
#     ecs-service and step-functions-batch modules. Resource policies contribute
#     only the mandatory insecure-transport deny and the authorization FIFO
#     dead-letter recovery guard recorded near the end of this file.
#   - Refactoring Rationale: the MQ queue-manager connection and the two CICS
#     queue aliases have no analogue here and are retired rather than ported.
#     SQS needs no broker resource at all, and the alias indirection is
#     replaced by a Parameter Store lookup of the queue URL.
# =============================================================================

# -----------------------------------------------------------------------------
# Messaging boundary.
#
# This is the single most consequential thing to know before changing anything
# below, because a change that treats the five queues uniformly breaks one of
# the two flows. The baseline's own MQ option words differ between them:
#
#   Authorization. app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl receives
#   with MQGMO-NO-SYNCPOINT + MQGMO-WAIT at :389 and replies with
#   MQPMO-NO-SYNCPOINT at :753 via MQPUT1 at :758, while committing its own
#   database work separately with EXEC CICS SYNCPOINT at :335. The reply is
#   therefore published OUTSIDE the unit of work that committed the decision,
#   which leaves a narrow window in which the data records a decision whose
#   reply was never sent.
#
#   Inquiry. app/app-vsam-mq/cbl/CODATE01.cbl and
#   app/app-vsam-mq/cbl/COACCT01.cbl both receive under MQGMO-SYNCPOINT
#   (:296 and :347) and reply under MQPMO-SYNCPOINT (:379 and :416; :475 and
#   :512), so a message is not removed until the unit of work commits.
#
# Assumptions: the queue set expresses only what a queue can express. The
# inquiry discipline maps onto visibility timeout plus delete-on-success,
# which is configuration and appears below. The authorization
# lost-reply window does NOT map onto any queue attribute -- it is closed
# by a transactional outbox in authorization-service, which writes the
# reply in the same transaction as the decision and publishes it
# afterwards. Nothing in this module implements that outbox, and nobody
# should look for it here.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Seven baseline queue-name literals, five target queues.
#
# A reader who greps the baseline for queue names finds SEVEN. The target
# provisions FIVE primaries -- the same five AAP section 0.4.1.8 fixes -- because
# the two hard-coded reply names are absorbed by the reply-routing contract and
# the one shared request name stays one queue.
#
#   1. AWS.M2.CARDDEMO.PAUTH.REQUEST  -> the FIFO authorization request queue
#   2. AWS.M2.CARDDEMO.PAUTH.REPLY    -> the FIFO authorization reply queue
#   3. CARDDEMO.REQUEST.QUEUE         -> the standard shared inquiry request queue
#   4. CARDDEMO.RESPONSE.QUEUE        -> the standard inquiry reply queue
#   5. CARD.DEMO.ERROR                -> the standard error queue
#   6. CARD.DEMO.REPLY.DATE           -> absorbed by replyToQueueUrl contract
#   7. CARD.DEMO.REPLY.ACCT           -> absorbed by replyToQueueUrl contract
#
# WHY : Refactoring Rationale: name three is NOT split per consumer, and it was.
#       The split provisioned an account request queue and a date request queue,
#       which made SIX primaries where the frozen inventory has five. What made
#       the split look necessary is real -- SQS delivers no copy to each consumer
#       and inspects no type before choosing one, so two competing consumers on
#       one queue let either hide or remove the other's message -- but the hazard
#       is a CONSUMER-COUNT hazard, not a queue-count one. It is closed by having
#       exactly one service bind a consumer to this queue and dispatch on the
#       four-character function code the request already carries in its first
#       field, which is the field COACCT01 itself branches on at
#       app/app-vsam-mq/cbl/COACCT01.cbl:393.
#       Trade-offs: dispatch moves from the producer to the consumer, so the
#       owning service now needs a renderer for both answers. The account answer
#       is data-bound and stays where the data is; the date answer is a function
#       of the clock and a fixed layout alone, so its renderer is shared through
#       common-lib rather than reached over the network.
# WHY : Refactoring Rationale: names six and seven are hard-coded reply-queue
#       NAMES, one per inquiry flow -- MOVE 'CARD.DEMO.REPLY.DATE' TO
#       REPLY-QUEUE-NAME at app/app-vsam-mq/cbl/CODATE01.cbl:147 and
#       MOVE 'CARD.DEMO.REPLY.ACCT' TO REPLY-QUEUE-NAME at
#       app/app-vsam-mq/cbl/COACCT01.cbl:198. Each program names its own reply
#       destination. The target provisions ONE reply queue and distinguishes
#       destinations through the request's replyToQueueUrl attribute, honoured
#       only when it matches the configured destination exactly. The baseline
#       documents the
#       correlation pattern this relies on at app/app-vsam-mq/README.md:135,
#       "Message Correlation: Demonstrates how to correlate request and
#       response messages".
# WHY : Assumptions: one shared error queue serves every producer, rather than
#       one per flow. Both inquiry programs write the SAME literal --
#       MOVE 'CARD.DEMO.ERROR' TO ERROR-QUEUE-NAME at CODATE01.cbl:243 and at
#       COACCT01.cbl:294 -- so a single error sink is what the baseline already
#       had, and splitting it per flow would invent a distinction the source
#       does not make.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# The MQ queue-manager connection is RETIRED, not missing.
#
# app/app-vsam-mq/README.md:70-72 documents DEFINE MQCONN(MQ01) GROUP(CARDDEMO)
# together with DEFINE MQQUEUE(CARDREQ) and DEFINE MQQUEUE(CARDRES), which
# alias the two inquiry queue names for CICS.
#
# Assumptions: SQS is accessed over the AWS API with IAM
# credentials and needs no broker connection object, so MQCONN has no
# target analogue and none is invented. The MQQUEUE alias indirection is
# retired on the same grounds: its role -- letting a program name a
# logical queue and resolve it to a physical one at run time -- is filled
# by a Parameter Store lookup of the queue URL, so the indirection
# survives while the resource type does not.
# Assumptions: there is no CSD artifact to map, which is what makes this
# a retirement rather than an omission. A search for
# MQCONN|MQQUEUE|MQMONITOR|MQINI across all four CSD files in the
# repository -- app/csd/CARDDEMO.CSD, app/app-vsam-mq/csd/CRDDEMOM.csd,
# app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd and
# app/app-transaction-type-db2/csd/CRDDEMOD.csd -- returns no hits at
# all. Those definitions exist only as installation prose in that README
# and were never deployed CICS resources, so nothing was dropped in
# translation.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# The MQ message descriptor becomes message ATTRIBUTES, not queue arguments.
#
# Assumptions: the contract below is honoured by the producers and
# consumers in authorization-service, account-service and
# reference-service, and none of it can be expressed as an argument on a
# queue. It is recorded here because it is what makes the single shared
# inquiry reply queue above workable, and so that nobody tries to
# configure it as a queue attribute and concludes the provider is
# missing a feature:
#   correlation identifier -> correlationId
#   message identifier     -> messageId
#   reply-to queue         -> replyToQueueUrl
#   MQFMT-STRING           -> a contentType of text/csv
# The correlation identifier is the load-bearing one, and the baseline
# saves and echoes it in all three flows: COPAUA0C.cbl clears it at :396,
# saves it at :411 and echoes it onto the reply at :745; CODATE01.cbl
# does the same at :293, :314 and :374; COACCT01.cbl at :344, :365 and
# :470. MQFMT-STRING is set at COPAUA0C.cbl:751, and because the payload
# is a string format the field order and the delimiter ARE the interface
# -- an eighteen-field CSV request and a six-field CSV reply, encoded and
# decoded by CsvAuthCodec in common-lib rather than by anything here.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Naming. Every name is composed once here so that the two FIFO suffixes and
# the five dead-letter suffixes cannot drift apart across ten resources.
# -----------------------------------------------------------------------------
locals {
  # WHY : Assumptions: the .fifo suffix has to be the LAST characters of a FIFO
  #       queue's name, and that single fact dictates the shape of both FIFO
  #       dead-letter names below. Composing a dead-letter name by appending
  #       "-dlq" to an already-suffixed name yields "<base>.fifo-dlq", which is
  #       not a valid FIFO name; the suffix must be appended last, giving
  #       "<base>-dlq.fifo". The trap is that the invalid form looks entirely
  #       reasonable and is accepted by `plan` -- the composition happens in
  #       Terraform, so nothing checks it until the service refuses the name
  #       partway through `apply`.
  # WHY : Assumptions: a queue name has to be unique only within one account and
  #       one region, unlike an S3 bucket name, which is globally unique. The
  #       environment token is therefore sufficient to keep the dev and prod
  #       queue sets apart even when both roots target the same account, and no
  #       account identifier is needed in any name. That is why this module
  #       declares no data source: the sibling infra/bootstrap module needs
  #       `data "aws_caller_identity"` because a bucket name must embed the
  #       account id, and nothing here does.
  # WHY : Trade-offs: the fixed part of each name is spelled out literally
  #       rather than derived from the resource labels. The literals repeat the
  #       flow names that already appear in the labels, but they are also the
  #       exact strings the length arithmetic in variables.tf is computed
  #       against -- "-pauth-request-" plus environment plus "-dlq.fifo" is the
  #       28-character binding case that fixes the 52-character ceiling on
  #       name_prefix. Deriving them would hide that arithmetic from the reader
  #       who has to check it.
  # WHY : Assumptions: the binding case named above is the pauth-request
  #       dead-letter name and no longer an inquiry name. While the request side
  #       carried a per-consumer split, "-account-inquiry-request-" plus
  #       environment plus "-dlq" composed 33 characters and was the longest
  #       fixed part in the module; the merged "-inquiry-request-" pair composes
  #       25, so the FIFO dead-letter name at 28 is now the longest and
  #       variables.tf validates name_prefix against that number.
  pauth_request_name     = "${var.name_prefix}-pauth-request-${var.environment}.fifo"
  pauth_request_dlq_name = "${var.name_prefix}-pauth-request-${var.environment}-dlq.fifo"

  pauth_reply_name     = "${var.name_prefix}-pauth-reply-${var.environment}.fifo"
  pauth_reply_dlq_name = "${var.name_prefix}-pauth-reply-${var.environment}-dlq.fifo"

  # WHY : Refactoring Rationale: this is ONE name pair where two stood -- an
  #       `-account-inquiry-request-` pair and a `-date-inquiry-request-` pair.
  #       The baseline defines a SINGLE request destination for both inquiry
  #       flows, DEFINE QLOCAL('CARDDEMO.REQUEST.QUEUE') at
  #       app/app-vsam-mq/README.md:53 aliased to CICS as MQQUEUE(CARDREQ) at
  #       :71, and AAP section 0.4.1.8 maps that one name to one target queue.
  #       Splitting it per consumer produced SIX primary queues where the
  #       migration plan fixes five, which is a topology change rather than an
  #       implementation choice, so the split is withdrawn here.
  #       Assumptions: one queue is answerable by exactly one consumer, and that
  #       consumer dispatches. The four-character function code is the first
  #       field of every request -- com.carddemo.common.codec.InquiryRequestCodec
  #       single-sources the layout -- so the owning consumer routes on content
  #       it already decodes rather than on which queue delivered the message.
  inquiry_request_name     = "${var.name_prefix}-inquiry-request-${var.environment}"
  inquiry_request_dlq_name = "${var.name_prefix}-inquiry-request-${var.environment}-dlq"

  inquiry_reply_name     = "${var.name_prefix}-inquiry-reply-${var.environment}"
  inquiry_reply_dlq_name = "${var.name_prefix}-inquiry-reply-${var.environment}-dlq"

  error_name     = "${var.name_prefix}-error-${var.environment}"
  error_dlq_name = "${var.name_prefix}-error-${var.environment}-dlq"
}

# -----------------------------------------------------------------------------
# Ordering of the ten queue resources below.
#
# Trade-offs: each dead-letter queue is declared immediately BEFORE the
# primary queue that redrives to it, because the primary's redrive_policy
# reads the dead-letter queue's ARN. Terraform derives the dependency
# graph from that reference and would order the pair correctly whatever
# the textual order, so this buys nothing at apply time; what it buys is
# that the file reads in the same order it executes, which is worth the
# mild oddity of a dead-letter queue appearing before the queue it
# serves.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Five arguments are carried by all ten queues for the same reasons each time.
# The full rationale is recorded once here; each resource below then carries a
# short pointer rather than five repeated paragraphs, because a rationale
# restated ten times is read zero times.
#
#   Argument kms_master_key_id, taken from var.kms_key_arn:
# Alternatives Considered: sqs_managed_sse_enabled, the service-managed
# SSE-SQS option, would encrypt these queues with a key AWS owns and would
# need no key wiring at all. It is rejected because the security design
# calls for four customer-managed keys with rotation, one per data domain,
# so that a key-policy mistake is bounded to one domain and key use is
# auditable per domain. A service-owned key gives up both properties.
# Assumptions: kms_master_key_id and sqs_managed_sse_enabled are mutually
# exclusive in the provider, so exactly one of the two may appear. Only
# kms_master_key_id is set, on every queue; sqs_managed_sse_enabled is set
# nowhere, and adding it beside the key would make the configuration
# self-contradicting rather than doubly encrypted.
# Assumptions: encryption at rest is something the target ADDS, not
# something it preserves. Every one of the eight file resources in
# app/csd/CARDDEMO.CSD is defined RECOVERY(NONE) with JOURNAL(NO), and the
# baseline's queues had no encryption attribute either -- it is a teaching
# sample, deliberately simple, and the migration adds a path rather than
# removing one. Recording the contrast keeps a later reader from hunting
# for a baseline setting that this argument corresponds to; there is none.
#
#   kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
# WHY : Trade-offs: a direct exchange, stated in both directions because neither
#       end is obviously right. A longer period means fewer GenerateDataKey and
#       Decrypt calls, which matters because KMS bills per request and enforces
#       a per-account request-rate quota that ten queues sharing one key can
#       contend for; it also means a data key stays cached in the service for
#       longer, so revoking access only takes effect once the period lapses. A
#       shorter period inverts both halves.
#
#   Argument receive_wait_time_seconds, taken from var.receive_wait_time_seconds:
# Assumptions: the default of 5 is a measured baseline constant rather than
# a chosen one, and reading it requires a unit conversion. The
# authorization consumer sets MOVE 5000 TO WS-WAIT-INTERVAL at
# app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:242 and passes it to
# MQGMO-WAITINTERVAL at :393; MQ expresses that field in MILLISECONDS, so
# 5000 is five seconds. The same five-second wait appears independently in
# both inquiry flows -- MOVE 5000 TO MQGMO-WAITINTERVAL at
# app/app-vsam-mq/cbl/CODATE01.cbl:286 and
# app/app-vsam-mq/cbl/COACCT01.cbl:337 -- so all three flows already
# waited the same interval and the polling rhythm is preserved, not picked.
# Trade-offs: the same value independently suppresses empty receives, which
# is a cost control and not only a fidelity choice: at a wait of 0 an idle
# consumer bills one request per loop iteration, and SQS charges per
# request, whereas any non-zero wait collapses an idle interval into a
# single request. What is given up is up to five seconds of added latency
# for a message arriving just after a receive call returned empty.
# Assumptions: the other half of the baseline's receive discipline is NOT
# expressible here and is not attempted. COPAUA0C.cbl declares
# WS-REQSTS-PROCESS-LIMIT PIC S9(4) COMP VALUE 500 at :40 and tests it at
# :339, ending its loop after five hundred messages. SQS has no
# messages-per-consumer-loop attribute, so that limit becomes a bounded
# long-poll loop in authorization-service. It is noted here so that nobody
# searches the provider for a queue argument that does not exist.
#
#   Argument visibility_timeout_seconds, taken from var.visibility_timeout_seconds:
# Assumptions: this is the target's stand-in for the inquiry flows'
# syncpoint discipline -- MQGMO-SYNCPOINT at CODATE01.cbl:296 and
# COACCT01.cbl:347, with MQPMO-SYNCPOINT on the replies at CODATE01.cbl:379
# and :416 and at COACCT01.cbl:475 and :512. Under syncpoint a message is
# not removed until the unit of work commits and returns if it never does;
# the SQS equivalent is this timeout together with delete-on-success, so
# the value has to bound the consumer's processing time. Set it below that
# time and a message is redelivered while the first attempt is still
# running, which both duplicates the work and spends receives against
# max_receive_count -- so a message can reach a dead-letter queue having
# in fact succeeded every time. That failure mode is silent, which is why
# it is recorded rather than left to be inferred.
#
#   Argument tags, taken from var.tags:
# Assumptions: tags are attached per resource because versions.tf declares
# no `provider` block and must not declare one -- a module carrying its own
# provider configuration cannot be called with count, for_each or
# depends_on, and it takes the choice of region away from its caller. With
# no provider there is no provider-level `default_tags` to inherit, so
# there is no implicit tagging to fall back on and omitting this argument
# from any single queue would silently leave that queue untagged. The
# sibling infra/bootstrap module does use provider `default_tags`, and
# correctly so, because bootstrap is a root rather than a module; the
# asymmetry between the two is deliberate.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Two arguments are deliberately left at the service default on all ten queues.
# An unexplained absence and an unexplained presence are equally unreadable, so
# both absences are recorded.
#
#   delay_seconds -- not set.
# Assumptions: the baseline delays no delivery. Neither MQPUT path sets a
# deferred or scheduled delivery option -- COPAUA0C.cbl:753 combines only
# MQPMO-NO-SYNCPOINT with MQPMO-DEFAULT-CONTEXT -- and both request/reply
# exchanges are latency-sensitive, the authorization reply carrying a
# five-second expiry of its own. A non-zero delay would postpone every
# message on the queue and could push a reply past that expiry before any
# consumer saw it, so the service default of no delay is what preserves
# the baseline's behaviour.
#
#   max_message_size -- not set.
# Assumptions: every payload these queues carry sits far below the 256 KiB
# maximum, so lowering the limit could only reject a message the design
# expects to succeed, and the maximum needs no raising. The inquiry
# layouts at app/app-vsam-mq/README.md:99-128 sum to 12 bytes for the date
# request, 22 for the date reply, 23 for the account request and 312 for
# the account reply -- the largest being a single X(300) account-data
# field. The authorization payloads are an eighteen-field CSV request and a
# six-field CSV reply. Nothing approaches the limit.
# Alternatives Considered: the extended client library with an S3
# large-payload offload is therefore not used, and no offload bucket is
# provisioned anywhere in this design. Recording that here prevents an
# unnecessary bucket appearing later on the assumption that one was
# forgotten.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Pending authorization: request. Replaces AWS.M2.CARDDEMO.PAUTH.REQUEST,
# described as the "Input queue for authorization requests" at
# app/app-authorization-ims-db2-mq/README.md:278.
# -----------------------------------------------------------------------------

resource "aws_sqs_queue" "pauth_request_dlq" {
  name = local.pauth_request_dlq_name

  # Assumptions: a FIFO queue's redrive target must ITSELF be a FIFO queue
  # -- AWS refuses a standard dead-letter queue for a FIFO source. That is
  # why FOUR resources in this file set fifo_queue rather than the two that
  # "the FIFO pair" would suggest. It is also the second half of the naming
  # trap noted in the locals block: because this queue is FIFO its name has
  # to end in .fifo, which is why it is "<base>-dlq.fifo" and never
  # "<base>.fifo-dlq".
  fifo_queue = true

  # Trade-offs: the high-throughput pair that the source queue sets --
  # deduplication_scope and fifo_throughput_limit -- is deliberately not
  # repeated here. The only producer to a dead-letter queue is SQS itself
  # performing a redrive, so there is no throughput to parallelise across
  # message groups, and redriven messages retain their original group and
  # deduplication identifiers regardless. Setting them would imply a
  # throughput concern this queue does not have.
  # Trade-offs: the retention here is the longest in the module and
  # intentionally exceeds the source queue's. A message reaches this queue
  # only after being received max_receive_count times on the source, so it
  # is already old on arrival, having spent its entire failing life on the
  # other queue. Matching the source's retention would let the evidence
  # expire while an operator was still establishing that anything had
  # failed; giving it less would let the evidence go before the alarm was
  # read.
  message_retention_seconds = var.dlq_message_retention_seconds

  # Alternatives Considered: restating the reasoning for the five arguments below on
  #       each of the ten queues was rejected. They take the same value on every
  #       queue and nothing about them is queue-specific, so the reasoning is
  #       recorded once in the shared block above; ten copies would be ten
  #       places for one decision to drift.
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  tags                              = var.tags
}

resource "aws_sqs_queue" "pauth_request" {
  name = local.pauth_request_name

  # WHY : Assumptions: the ordering requirement is PER CARD, not global, and a
  #       FIFO queue is the only queue type that can express it. Producers set
  #       MessageGroupId to the card number itself and MessageDeduplicationId to the
  #       transaction identifier itself, which sections 0.4.1.8 and 0.7.6 of the
  #       technical specification state literally, so equal cards produce one group
  #       by construction rather than by agreement on a derivation.
  # WHY : Refactoring Rationale: this comment described both identities as
  #       purpose-scoped HMACs derived through OpaqueIdentifier from a key delivered
  #       as CARDDEMO_MESSAGING_HMAC_KEY -- a variable that no longer exists anywhere,
  #       because the bean it keyed lost its last purpose here and the whole chain,
  #       property to provisioned secret, was withdrawn with it -- so that no account
  #       number entered queue
  #       metadata. The derivation is withdrawn because it removed the guarantees it
  #       was layered on: a group identity orders one card's messages only while
  #       EVERY producer computes the same value for that card, and a deduplication
  #       identity suppresses a resend only while the REQUESTER can predict it. The
  #       resulting exposure -- an account number in queue metadata -- is registered
  #       as divergence D-AUTHORIZATION-FIFO-IDENTITY-METADATA in
  #       docs/architecture/cobol-to-service-traceability.md, and this module
  #       supplies two of the three controls bounding it: SSE under the
  #       customer-managed key it is handed, and send/receive capability scoped by
  #       the task-role policies built from its outputs.
  # WHY : Alternatives Considered: a single constant message group would give
  #       total ordering across the whole stream. It is rejected because it
  #       serialises every card behind every other and collapses throughput to
  #       one consumer, in exchange for a guarantee nothing in the baseline asks
  #       for -- authorizations for two different cards have no ordering
  #       relationship to preserve.
  fifo_queue = true

  # Alternatives Considered: content-based deduplication is the alternative
  # to this, and it is set to false EXPLICITLY rather than left to default
  # because the difference between the two is a correctness question and
  # not a tuning one. With content_based_deduplication true, SQS derives a
  # SHA-256 identifier from the body ONLY WHEN the producer omits an
  # explicit MessageDeduplicationId; an explicit identifier overrides the
  # generated body hash. This design requires every producer to supply the
  # transaction identifier, which is content-INDEPENDENT, so enabling the
  # fallback would make an accidentally omitted identifier look successful
  # while changing semantics: two distinct authorizations with identical
  # bodies could collapse, and a resend whose body changed could be
  # accepted as new. Stating false makes omission fail at SendMessage and
  # keeps the producer-supplied transaction id mandatory and reviewable.
  # Assumptions: the guarantee this buys is BOUNDED. The deduplication
  # interval is five minutes, so a duplicate arriving after it lapses is
  # accepted as a new message. The queue is therefore a first line of
  # defence and not the system of record for idempotency -- the durable
  # backstop is the database, which is where a replayed transaction
  # identifier is ultimately rejected.
  content_based_deduplication = false

  # Assumptions: these two arguments are INTERDEPENDENT and neither may be
  # removed on its own -- per-message-group throughput requires the
  # deduplication scope to be the message group, and AWS rejects the
  # combination that pairs per-message-group throughput with queue-wide
  # deduplication. Together they are also what actually DELIVERS the
  # parallel-throughput half of the FIFO choice above. Without them
  # per-card ordering would still hold, but throughput would be serialised
  # queue-wide, so the FIFO decision would cost throughput it had no need
  # to cost. Removing one and keeping the other is the specific mistake
  # this note exists to prevent.
  deduplication_scope   = "messageGroup"
  fifo_throughput_limit = "perMessageGroupId"

  # Trade-offs: a request is retained for the long window rather than the
  # short one because a request IS the unit of work -- discarding one
  # discards an authorization that was genuinely asked for. The window has
  # to outlast the interruption that produced a backlog and then still
  # accept a redrive from the dead-letter queue, which returns messages to
  # this queue.
  message_retention_seconds = var.request_message_retention_seconds

  # WHY : Alternatives Considered: the policy is built with jsonencode rather
  #       than written as a heredoc string. jsonencode cannot emit malformed
  #       JSON, and referencing the dead-letter queue's ARN through the resource
  #       makes this a real dependency edge, so Terraform derives the creation
  #       order instead of the author asserting it with depends_on. A hand-written
  #       string embedding a literal ARN would lose both properties at once.
  # WHY : Assumptions: this queue has its OWN dead-letter queue rather than
  #       sharing one across the five sources, so a poison message is
  #       attributable to the flow that produced it without inspecting its body.
  #       A receive count of five is corroborated twice over: it is the figure
  #       the messaging design fixes, and the baseline was already operated to
  #       the same budget -- app/scheduler/CardDemo.controlm sets MAXRERUN="5" on
  #       all fifteen of its job elements, including lines 4, 8, 14, 20 and 27.
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.pauth_request_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })

  # Alternatives Considered: restating the reasoning for the five arguments below on
  #       each of the ten queues was rejected. They take the same value on every
  #       queue and nothing about them is queue-specific, so the reasoning is
  #       recorded once in the shared block above; ten copies would be ten
  #       places for one decision to drift.
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  tags                              = var.tags
}

# -----------------------------------------------------------------------------
# Pending authorization: reply. Replaces AWS.M2.CARDDEMO.PAUTH.REPLY, described
# as the "Output queue for authorization responses" at
# app/app-authorization-ims-db2-mq/README.md:279.
# -----------------------------------------------------------------------------

resource "aws_sqs_queue" "pauth_reply_dlq" {
  name = local.pauth_reply_dlq_name

  # Assumptions: FIFO for the same structural reason as the request
  # dead-letter queue -- its source is FIFO, and AWS refuses a standard
  # dead-letter queue for a FIFO source. The .fifo suffix is consequently
  # last in the name.
  fifo_queue = true

  # Trade-offs: this remains the widest retention gap in the module. The
  # source queue retains long enough to finish every retry cycle, while
  # this dead-letter queue keeps the failed reply for fourteen days so the
  # evidence survives the incident that produced it.
  message_retention_seconds = var.dlq_message_retention_seconds

  # Alternatives Considered: restating the reasoning for the five arguments below on
  #       each of the ten queues was rejected. They take the same value on every
  #       queue and nothing about them is queue-specific, so the reasoning is
  #       recorded once in the shared block above; ten copies would be ten
  #       places for one decision to drift.
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  tags                              = var.tags
}

resource "aws_sqs_queue" "pauth_reply" {
  name = local.pauth_reply_name

  # WHY : Assumptions: the reply is FIFO because it is the return leg of a FIFO
  #       exchange, and it carries the same two LITERAL identities as the request
  #       queue above -- MessageGroupId is the card number itself and
  #       MessageDeduplicationId is the transaction identifier itself, stated that
  #       way by sections 0.4.1.8 and 0.7.6 of the technical specification. A
  #       consumer therefore observes one card's decisions in send order because
  #       every producer built to that specification computes the same group value,
  #       rather than because publishers agreed on a derivation.
  # WHY : ⚠️ Refactoring Rationale: this comment said replies "use the same
  #       purpose-scoped opaque per-card group identity as requests, so a consumer
  #       observes decisions for one card in send order without the primary account
  #       number becoming message metadata". The derivation it described was
  #       withdrawn from the publisher -- the request queue above records why, and
  #       services/authorization-service AuthReplyOutbox.orderGroupId now stores
  #       the card number itself -- so this paragraph survived as the module's only
  #       remaining claim that no account number reaches queue metadata. That is
  #       the most damaging shape a stale comment can take here: an operator
  #       assessing this queue's exposure reads a reassurance where a registered
  #       divergence exists. The exposure IS real and is registered as
  #       D-AUTHORIZATION-FIFO-IDENTITY-METADATA in
  #       docs/architecture/cobol-to-service-traceability.md.
  # WHY : Trade-offs: server-side encryption covers a message BODY and not its
  #       metadata, so the card number in this queue's group identity is visible to
  #       queue telemetry, to send traces and to anything permitted to observe the
  #       queue. This module supplies two of the three provisioned controls that
  #       bound it -- SSE under the customer-managed key it is handed, and send and
  #       receive capability scoped by the task-role policies built from its
  #       outputs -- and infra/modules/network supplies the third by making the
  #       queue reachable only through an interface endpoint inside the private
  #       network. What NO infrastructure control reaches is a log line: the
  #       exposure is contained to this one metadata field only while nothing
  #       copies the group identity into a durable diagnostic, which is a
  #       requirement on the publisher and its consumers rather than on this file,
  #       and which OutboxMetadataConfidentialityTest asserts by refusing the whole
  #       number, its leading six digits and its trailing four in every other
  #       metadata field.
  fifo_queue = true

  # Alternatives Considered: false for the same reason as the request
  # queue. Deduplication is keyed on the producer-supplied transaction
  # identifier, which is content-independent; body hashing would collapse
  # two distinct replies that serialised identically -- entirely possible
  # here, since a reply carries a response code and an approved amount and
  # little else, so two different authorizations can easily share a body.
  content_based_deduplication = false

  # Assumptions: interdependent, exactly as on the request queue -- neither
  # may be removed alone.
  deduplication_scope   = "messageGroup"
  fifo_throughput_limit = "perMessageGroupId"

  # Assumptions: queue retention implements transport recovery, not the
  # baseline's five-second business-expiry rule. The producer stamps the
  # `expiresAt` attribute and the consumer rejects stale replies; this value
  # is instead validated to outlast every visibility interval, every receive
  # wait and the final dead-letter transition. That separation prevents a
  # poison reply from disappearing before quarantine while preserving the
  # baseline outcome that no stale reply is acted upon.
  # Trade-offs: the resulting transport lifetime exceeds the non-persistent
  # reply at COPAUA0C.cbl:749-750. The extra durability is restricted to
  # diagnosis and recovery: `expiresAt` remains authoritative for business
  # use, and a retention shorter than the dead-letter transition would erase
  # the record of the failure before anyone could read it.
  message_retention_seconds = var.reply_message_retention_seconds

  # Assumptions: a reply queue needs a dead-letter queue as much as a
  # request queue does. Its source retention is validated against the
  # complete visibility and receive-wait retry budget, so a reply cannot
  # expire before the configured receive count moves it to the dead-letter
  # queue, where it remains available for investigation.
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.pauth_reply_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })

  # Alternatives Considered: restating the reasoning for the five arguments below on
  #       each of the ten queues was rejected. They take the same value on every
  #       queue and nothing about them is queue-specific, so the reasoning is
  #       recorded once in the shared block above; ten copies would be ten
  #       places for one decision to drift.
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  tags                              = var.tags
}

# -----------------------------------------------------------------------------
# Inquiry request. Replaces CARDDEMO.REQUEST.QUEUE, defined as
# DEFINE QLOCAL('CARDDEMO.REQUEST.QUEUE') at app/app-vsam-mq/README.md:53 and
# aliased to CICS as MQQUEUE(CARDREQ) at :71. It carries BOTH inquiry flows --
# the COACCT01 account inquiry and the CODATE01 date-and-time inquiry -- exactly
# as the one baseline name did, and account-service owns it as the single
# consumer that dispatches on the request's own function code.
# -----------------------------------------------------------------------------

resource "aws_sqs_queue" "inquiry_request_dlq" {
  name = local.inquiry_request_dlq_name

  # Trade-offs: standard rather than FIFO, matching its source queue. A
  # dead-letter queue has to be the same type as the source it serves, so
  # this is determined by the choice made on the source rather than
  # decided here; no fifo_queue argument appears, and the name carries no
  # .fifo suffix.
  message_retention_seconds = var.dlq_message_retention_seconds

  # Alternatives Considered: restating the reasoning for the five arguments below on
  #       each of the ten queues was rejected. They take the same value on every
  #       queue and nothing about them is queue-specific, so the reasoning is
  #       recorded once in the shared block above; ten copies would be ten
  #       places for one decision to drift.
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  tags                              = var.tags
}

resource "aws_sqs_queue" "inquiry_request" {
  name = local.inquiry_request_name

  # WHY : Refactoring Rationale: this ONE queue replaced a pair of per-consumer
  #       request queues. Two independent consumers on one standard queue do not
  #       receive a copy each; they COMPETE, and whichever receives first hides
  #       the message for the visibility timeout -- which is why the pair was
  #       authored. The pair was withdrawn because it provisioned a sixth primary
  #       queue against the five AAP section 0.4.1.8 fixes, and the competing-
  #       consumer hazard is closed a different way: exactly ONE service binds a
  #       consumer to this queue (account-service) and that consumer dispatches
  #       on the four-character function code carried in the request's first
  #       field -- 'INQA' for the COACCT01 account inquiry, 'DATE' for the
  #       CODATE01 date-and-time inquiry, and the baseline's own
  #       invalid-parameters reply for anything else.
  #       Alternatives Considered: keeping a consumer in each service and
  #       accepting the sixth queue. Rejected: the queue inventory is frozen
  #       topology, and a comment cannot widen it.
  #       Alternatives Considered: having the owning consumer call the other
  #       context synchronously for the date answer. Rejected on cost and blast
  #       radius -- it would add a pairwise machine-identity signing key, its IAM
  #       grants and a cross-context network hop to a reply that is a function of
  #       the clock alone, so the renderer is shared through common-lib instead.
  # WHY : Trade-offs: this queue is standard, and the absence of fifo_queue is a
  #       decision rather than an oversight. Inquiries are independent of one
  #       another, so ordering buys no business guarantee and would require a
  #       synthetic message group while costing more per request.
  # WHY : Trade-offs: merging the two request destinations changes one observable
  #       behaviour, and it is registered in
  #       docs/architecture/cobol-to-service-traceability.md rather than left to
  #       be discovered. CODATE01 reads no field of its request and answers ANY
  #       function code with the date, so on the baseline's separate trigger
  #       queues an unrecognised code received a date reply there and the
  #       invalid-parameters reply on the account queue. With one queue an
  #       unrecognised code receives the invalid-parameters reply, which is
  #       COACCT01's documented text; both recognised codes are unaffected.
  # WHY : Assumptions: standard delivery is at-least-once and may reorder, and
  #       that is acceptable specifically because the consumer reads under the
  #       visibility-timeout-plus-delete-on-success discipline described in the
  #       shared block above, which is the faithful translation of this flow's
  #       MQGMO-SYNCPOINT receive.
  message_retention_seconds = var.request_message_retention_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.inquiry_request_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })

  # Alternatives Considered: restating the reasoning for the five arguments below on
  #       each of the ten queues was rejected. They take the same value on every
  #       queue and nothing about them is queue-specific, so the reasoning is
  #       recorded once in the shared block above; ten copies would be ten
  #       places for one decision to drift.
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  tags                              = var.tags
}


# -----------------------------------------------------------------------------
# Inquiry reply. Replaces CARDDEMO.RESPONSE.QUEUE, defined as
# DEFINE QLOCAL('CARDDEMO.RESPONSE.QUEUE') at app/app-vsam-mq/README.md:54 and
# aliased to CICS as MQQUEUE(CARDRES) at :72.
# -----------------------------------------------------------------------------

resource "aws_sqs_queue" "inquiry_reply_dlq" {
  name = local.inquiry_reply_dlq_name

  # Trade-offs: as with the inquiry request dead-letter queue, the long
  # retention is what keeps a repeatedly-failing reply examinable after
  # its source queue's sixty-second window has closed.
  message_retention_seconds = var.dlq_message_retention_seconds

  # Alternatives Considered: restating the reasoning for the five arguments below on
  #       each of the ten queues was rejected. They take the same value on every
  #       queue and nothing about them is queue-specific, so the reasoning is
  #       recorded once in the shared block above; ten copies would be ten
  #       places for one decision to drift.
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  tags                              = var.tags
}

resource "aws_sqs_queue" "inquiry_reply" {
  name = local.inquiry_reply_name

  # Assumptions: request queues split by owning service, while
  # replies follow one explicit request/reply contract: each request names
  # its destination in `replyToQueueUrl`, and the responder sends only to
  # that nominated, IAM-authorized queue. This provisioned queue is the
  # CardDemo requester's shared destination; responders do not choose a
  # hard-coded flow-specific queue.
  # Trade-offs: the consolidation means the two flows' replies share a
  # queue depth and a set of metrics, so a backlog cannot be attributed to
  # the date flow or the account flow from queue metrics alone. That is
  # accepted because the correlation identifier and the request type are
  # both present on every message, so attribution remains available from
  # the messages and from consumer-side metrics.
  message_retention_seconds = var.reply_message_retention_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.inquiry_reply_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })

  # Alternatives Considered: restating the reasoning for the five arguments below on
  #       each of the ten queues was rejected. They take the same value on every
  #       queue and nothing about them is queue-specific, so the reasoning is
  #       recorded once in the shared block above; ten copies would be ten
  #       places for one decision to drift.
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  tags                              = var.tags
}

# -----------------------------------------------------------------------------
# Terminal error sink. Replaces CARD.DEMO.ERROR, written by BOTH inquiry
# programs -- MOVE 'CARD.DEMO.ERROR' TO ERROR-QUEUE-NAME at
# app/app-vsam-mq/cbl/CODATE01.cbl:243 and app/app-vsam-mq/cbl/COACCT01.cbl:294.
# -----------------------------------------------------------------------------

resource "aws_sqs_queue" "error_dlq" {
  name = local.error_dlq_name

  # Assumptions: an error sink having a dead-letter queue of its own reads
  # oddly and is deliberate. The error queue is a source queue like any
  # other -- something consumes it, to raise an alert or to record the
  # failure -- and a message that consumer cannot process would otherwise
  # be redelivered indefinitely or lost. This queue is where a message that
  # defeated even the error handler ends up, which is the last place an
  # operator can still find it.
  message_retention_seconds = var.dlq_message_retention_seconds

  # Alternatives Considered: restating the reasoning for the five arguments below on
  #       each of the ten queues was rejected. They take the same value on every
  #       queue and nothing about them is queue-specific, so the reasoning is
  #       recorded once in the shared block above; ten copies would be ten
  #       places for one decision to drift.
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  tags                              = var.tags
}

resource "aws_sqs_queue" "error" {
  name = local.error_name

  # Assumptions: ONE error queue serves every producer rather than one per
  # flow, because that is what the baseline does -- both inquiry programs
  # move the identical CARD.DEMO.ERROR literal into ERROR-QUEUE-NAME, at
  # CODATE01.cbl:243 and COACCT01.cbl:294 respectively. Splitting the sink
  # per flow would introduce a distinction the source does not make, and
  # would leave an operator watching several queues for a condition the source
  # surfaces in one place.
  # Trade-offs: this queue takes the long request retention rather than the
  # short reply retention, even though the messages on it are failures
  # rather than units of work. Its entire purpose is to still be readable
  # when somebody eventually comes looking, which is the request case in
  # the extreme; a short retention would make the sink useless precisely in
  # the scenario it exists for. The cost is paying to store error messages
  # that may never be read.
  message_retention_seconds = var.request_message_retention_seconds

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.error_dlq.arn
    maxReceiveCount     = var.max_receive_count
  })

  # Alternatives Considered: restating the reasoning for the five arguments below on
  #       each of the ten queues was rejected. They take the same value on every
  #       queue and nothing about them is queue-specific, so the reasoning is
  #       recorded once in the shared block above; ten copies would be ten
  #       places for one decision to drift.
  kms_master_key_id                 = var.kms_key_arn
  kms_data_key_reuse_period_seconds = var.kms_data_key_reuse_period_seconds
  receive_wait_time_seconds         = var.receive_wait_time_seconds
  visibility_timeout_seconds        = var.visibility_timeout_seconds
  tags                              = var.tags
}

# -----------------------------------------------------------------------------
# Queue-level transport enforcement.
#
# Each object pairs the URL the provider attaches the policy to with the exact
# ARN the policy statement protects. The inventory is read from the ten queue
# resources themselves so it cannot name an eleventh queue or omit an ARN while
# still pointing at a URL.
# -----------------------------------------------------------------------------
locals {
  tls_policy_targets = {
    pauth_request = {
      arn = aws_sqs_queue.pauth_request.arn
      url = aws_sqs_queue.pauth_request.url
    }
    pauth_request_dlq = {
      arn = aws_sqs_queue.pauth_request_dlq.arn
      url = aws_sqs_queue.pauth_request_dlq.url
    }
    pauth_reply = {
      arn = aws_sqs_queue.pauth_reply.arn
      url = aws_sqs_queue.pauth_reply.url
    }
    pauth_reply_dlq = {
      arn = aws_sqs_queue.pauth_reply_dlq.arn
      url = aws_sqs_queue.pauth_reply_dlq.url
    }
    inquiry_request = {
      arn = aws_sqs_queue.inquiry_request.arn
      url = aws_sqs_queue.inquiry_request.url
    }
    inquiry_request_dlq = {
      arn = aws_sqs_queue.inquiry_request_dlq.arn
      url = aws_sqs_queue.inquiry_request_dlq.url
    }
    inquiry_reply = {
      arn = aws_sqs_queue.inquiry_reply.arn
      url = aws_sqs_queue.inquiry_reply.url
    }
    inquiry_reply_dlq = {
      arn = aws_sqs_queue.inquiry_reply_dlq.arn
      url = aws_sqs_queue.inquiry_reply_dlq.url
    }
    error = {
      arn = aws_sqs_queue.error.arn
      url = aws_sqs_queue.error.url
    }
    error_dlq = {
      arn = aws_sqs_queue.error_dlq.arn
      url = aws_sqs_queue.error_dlq.url
    }
  }

  # Exact dead-letter admission.
  #
  # Each entry pairs ONE dead-letter queue URL with the single source queue ARN
  # permitted to move messages into it. The five pairs are the five source queues
  # in the inventory above; a dead-letter queue that appears here can therefore
  # receive from exactly one source and nothing else.
  #
  # Assumptions: this is expressed through the standalone
  # aws_sqs_queue_redrive_allow_policy resource rather than inline on the
  # queue. Inline is not merely inelegant, it is impossible: the dead-letter
  # queue would reference its source's ARN while that source already
  # references the dead-letter queue's ARN in its redrive_policy, and
  # Terraform builds its graph per resource, so the mutual reference fails at
  # `validate` with "Cycle: aws_sqs_queue.<dlq>, aws_sqs_queue.<source>". The
  # standalone resource depends on both queues and breaks the cycle.
  #       Alternatives Considered: relying on identity policies alone, since only a
  #       principal holding sqs:StartMessageMoveTask can redrive at all. Rejected
  #       because the identity model bounds WHO may redrive but not WHICH source a
  #       dead-letter queue will accept, so a misconfigured redrive_policy on an
  #       unrelated queue could still land foreign messages in an authorization
  #       dead-letter queue and be replayed as if it belonged there.
  dlq_source_pairs = {
    pauth_request = {
      dlq_url    = aws_sqs_queue.pauth_request_dlq.url
      source_arn = aws_sqs_queue.pauth_request.arn
    }
    pauth_reply = {
      dlq_url    = aws_sqs_queue.pauth_reply_dlq.url
      source_arn = aws_sqs_queue.pauth_reply.arn
    }
    inquiry_request = {
      dlq_url    = aws_sqs_queue.inquiry_request_dlq.url
      source_arn = aws_sqs_queue.inquiry_request.arn
    }
    inquiry_reply = {
      dlq_url    = aws_sqs_queue.inquiry_reply_dlq.url
      source_arn = aws_sqs_queue.inquiry_reply.arn
    }
    error = {
      dlq_url    = aws_sqs_queue.error_dlq.url
      source_arn = aws_sqs_queue.error.arn
    }
  }

  # The two dead-letter queues that hold FIFO authorization traffic. Native bulk
  # redrive is denied on exactly these, for the reason recorded at the deny
  # statement: a bulk move replays a group's messages without the reconciliation
  # the ordering contract depends on.
  authorization_fifo_dlq_keys = toset([
    "pauth_request_dlq",
    "pauth_reply_dlq",
  ])
}

# WHY : Assumptions: one policy instance is attached to EACH queue, including
#       every dead-letter queue. An identity policy can restrict which principal
#       may call SQS but cannot require that the signed request travelled over
#       TLS; the `aws:SecureTransport` condition is the independent transport
#       control. The Resource is the same queue's exact ARN, so a policy cannot
#       accidentally govern a sibling queue.
#       Alternatives Considered: ten copied policy blocks. Rejected because the
#       policy is intentionally identical across all queues; one for_each makes
#       the ten-instance inventory auditable while keeping the queue-specific
#       URL and ARN paired in one object.
resource "aws_sqs_queue_policy" "tls_only" {
  for_each = local.tls_policy_targets

  queue_url = each.value.url
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [
        {
          Sid       = "DenyInsecureTransport"
          Effect    = "Deny"
          Principal = "*"
          Action    = "sqs:*"
          Resource  = each.value.arn
          Condition = {
            Bool = {
              "aws:SecureTransport" = "false"
            }
          }
        }
      ],
      contains(local.authorization_fifo_dlq_keys, each.key) ? [
        {
          Sid       = "DenyUnorderedNativeBulkRedrive"
          Effect    = "Deny"
          Principal = "*"
          Action    = "sqs:StartMessageMoveTask"
          Resource  = each.value.arn
        }
      ] : []
    )
  })
}

# Assumptions: every dead-letter queue belongs to exactly one source.
# `byQueue` turns that topology into an admission rule, so a mistaken or
# compromised source cannot attach the queue as a failure sink. A separate
# resource depends on both queues after creation and avoids the dependency
# cycle that an inline DLQ-to-source reference would create.
resource "aws_sqs_queue_redrive_allow_policy" "exact_source" {
  for_each = local.dlq_source_pairs

  queue_url = each.value.dlq_url
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [each.value.source_arn]
  })
}

# -----------------------------------------------------------------------------
# What this file deliberately does NOT declare.
# -----------------------------------------------------------------------------
#
# Assumptions: resource-based ALLOW statements remain absent. Every
# producer and consumer is a same-account IAM principal, so positive
# access stays in the identity-based task-role policies owned by
# ecs-service and step-functions-batch. The queue policies above contain
# only denials and therefore harden transport and recovery without
# duplicating a grant or widening a principal. Adding an allow here would
# duplicate the identity grants in a second place and make access the union
# of two policy surfaces. A cross-account producer would change that
# analysis; there is none.
#
# Trade-offs: FIFO dead-lettering is an explicit QUARANTINE boundary, not
# a claim of uninterrupted order through poison handling. SQS documents
# that moving a failed FIFO message to a dead-letter queue can let later
# messages in that group proceed. The target therefore guarantees
# per-card order while messages remain processable on the source queue,
# preserves the failed message on an exact-source FIFO dead-letter queue,
# denies unsafe native bulk redrive, and requires reconciliation before
# controlled per-message replay. Availability for later authorizations is
# chosen over blocking one card forever; docs/architecture/
# messaging-contracts.md records that bounded guarantee and its operator
# controls without claiming exact order across the quarantine boundary.
#
# Alternatives Considered: NO Amazon MQ broker, and no Kafka or Kinesis
# stream. The requirement these queues serve is request/reply, which SQS
# satisfies directly, so a streaming platform would add a partitioned log
# and its retention and consumer-group semantics to a workload that needs
# none of them. The two broker options were a SELF-MANAGED IBM MQ queue
# manager, which would have preserved the MQ wire protocol itself, and an
# Amazon MQ ActiveMQ or RabbitMQ broker, which would not -- Amazon MQ has no
# IBM MQ engine, so protocol fidelity and a managed broker are not the same
# option. Both were rejected at the cost of keeping a broker to size, patch
# and fail over. docs/adr/ADR-004-messaging.md owns that comparison in full
# and it is not restated here.
#
# Assumptions: NO data source and NO reference to a sibling module. The
# encryption key arrives as var.kms_key_arn from the calling root rather
# than being read here, because a module that names module.kms can only be
# called from a configuration that happens to contain that module under
# that name -- and this module is called from two roots. An unused data
# source would additionally be reported by tflint's
# terraform_unused_declarations rule, which is a gating step, and omitting
# one keeps this file free of any account identifier or region literal.
# -----------------------------------------------------------------------------
