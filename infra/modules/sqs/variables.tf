# =============================================================================
# infra/modules/sqs/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `sqs` module -- every value a calling
#   root may supply, and nothing beyond that. The module provisions twelve
#   queues: six primary queues plus one dead-letter queue for each. The two
#   inquiry request flows are split by owning service, while one shared reply
#   queue follows the request's replyToQueueUrl contract.
#
#   Every variable below is explicitly typed, carries a `description`, and is
#   consumed by main.tf or outputs.tf. Nothing is declared speculatively.
#
#   Several of the defaults are not free choices. They are constants recovered
#   by measurement from the baseline's own MQ calls and scheduler definition,
#   and each carries its citation at the point of declaration rather than in a
#   separate document that could drift away from the value it explains.
#
#   Parameters: the eleven `variable` blocks below. Two are mandatory and have
#   no default -- `environment` and `kms_key_arn` -- so a call is valid with
#   only those two supplied and every other input left to its default.
#
#   Return values: none -- this file declares no `output` blocks. The module's
#   outputs, the queue URLs, ARNs and names consumed by the container services
#   and by the batch state machine, are declared in outputs.tf.
#
#   Errors:
#     * Each `validation` block rejects an out-of-domain value at `plan` time
#       and names the accepted range or set in its message. Without them the
#       same value is rejected by the service partway through `apply`, as a
#       provider error against one queue that a caller cannot readily trace
#       back to the input that produced it.
#     * Omitting `environment` or `kms_key_arn` stops the calling root at
#       `plan` with a missing-required-argument error, which for the key is the
#       mechanism that makes encryption unskippable rather than merely
#       expected.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the variable NAMES in this file are the module's call
#     contract. infra/envs/dev and infra/envs/prod reference them by name in
#     their `module "sqs"` blocks, so renaming one is a breaking change to both
#     roots rather than a local edit, and every name is chosen on that basis.
#   - Trade-offs: the tuning inputs are exposed one scalar at a time rather than
#     bundled into a single object variable. The object would be one entry in
#     the generated module README instead of nine, but its sub-attributes would
#     then have nowhere to carry a `description` of their own, and a caller
#     wanting to change one value would have to restate the rest. Nine
#     documented scalars are more to read and less to get wrong.
#   - Trade-offs: three separate retention inputs rather than one. This is the
#     most surprising shape in the file and it is deliberate -- requests, of
#     which the error sink is one, are retained for days; replies outlast their
#     complete retry budget; dead-letter queues retain failed evidence longer
#     than either source. The reasoning for each sits on the variable itself,
#     because the three values are only defensible in relation to one another.
#   - Alternatives Considered: no per-queue name override and no `create_*`
#     feature flag is offered. The queue set is fixed at six plus six
#     dead-letter queues by the messaging design, so a toggle would advertise
#     an optionality that does not exist, and a `count`-gated queue would turn
#     every output in outputs.tf into a possibly-empty list that each caller
#     would have to index defensively.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming. These two inputs compose every queue name, so the SQS name limit is
# enforced against them jointly rather than against either one alone.
# -----------------------------------------------------------------------------

# WHY : Assumptions: a queue name has to be unique only within one account and
#       region, and nothing prevents both environment roots from targeting the
#       same account. This token is then the only thing keeping the two queue
#       sets apart, which is why it deliberately has no default: a defaulted
#       value would let a root that forgot to pass it apply cleanly and either
#       create a second set under the wrong name or, worse, adopt and mutate
#       the other environment's queues. A missing-argument error at `plan` is
#       the cheaper of the two failures by a wide margin.
variable "environment" {
  description = "Environment token appended to every queue name, keeping the dev and prod queue sets distinct within a single account and region. Consumed by the naming locals in main.tf that compose each aws_sqs_queue name."
  type        = string

  # WHY : Assumptions: the domain is genuinely two values rather than an open
  #       string. infra/envs/ holds exactly two roots, and multi-region and
  #       disaster-recovery topology are out of scope, so there is no third
  #       environment for a third token to name. Closing the set here also
  #       underwrites the length arithmetic on `name_prefix` below, which is
  #       computed against the longest permitted token being four characters;
  #       admitting an arbitrary string would invalidate that ceiling silently.
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly one of: dev, prod."
  }
}

# WHY : Trade-offs: an input rather than a literal in main.tf, even though both
#       environments are expected to pass the same value. The cost is one more
#       parameter to document; what it buys is the ability to stand a second,
#       independently named queue set up alongside the first in one account --
#       for a migration rehearsal, for instance -- without editing shared
#       module code.
variable "name_prefix" {
  description = "Leading token of every composed queue name; \"carddemo\" yields names such as carddemo-pauth-request-<environment>.fifo. Consumed by the naming locals in main.tf."
  type        = string
  default     = "carddemo"

  # WHY : Assumptions: this is the most load-bearing validation in the file,
  #       because the limit it guards belongs to the service and not to
  #       Terraform. An SQS queue name may be at most 80 characters, and on a
  #       FIFO queue the mandatory `.fifo` suffix counts toward that 80. The
  #       arithmetic, which a reader can check against the naming locals in
  #       main.tf: the longest name this module composes is the account-inquiry
  #       dead-letter queue, whose fixed part is
  #       "-account-inquiry-request-" (25 characters) + <environment> (4 at
  #       most, being "prod") + "-dlq" (4) = 33. 80 - 33 = 47, so a
  #       47-character prefix composes a name of exactly 80 and anything longer
  #       cannot fit. The default spends 8 of those 47.
  #       Left unchecked, an over-long prefix passes `plan` untouched and fails
  #       during `apply` against whichever queue the provider reached first,
  #       reported as an invalid parameter on a name the caller never typed.
  validation {
    condition     = length(var.name_prefix) >= 1 && length(var.name_prefix) <= 47
    error_message = "name_prefix must be 1 to 47 characters. The module appends up to 33 more characters (\"-account-inquiry-request-\" + environment + \"-dlq\") and SQS limits queue names to 80 characters."
  }

  # WHY : Assumptions: SQS accepts only letters, digits, hyphens and
  #       underscores in a queue name. A dot is refused here even though every
  #       FIFO name this module creates contains one, because that dot belongs
  #       to the `.fifo` suffix main.tf appends; a prefix carrying its own dot
  #       would compose a name with two, which the service rejects. Refusing
  #       the character the caller can see in the finished name is the
  #       counter-intuitive part, so it is stated rather than left to be
  #       rediscovered from the error message.
  validation {
    condition     = can(regex("^[a-zA-Z0-9_-]+$", var.name_prefix))
    error_message = "name_prefix may contain only letters, digits, hyphens and underscores. A dot is not permitted: the .fifo suffix on the two FIFO queues is appended by the module."
  }
}

# -----------------------------------------------------------------------------
# Encryption at rest. Every queue and every dead-letter queue is encrypted with
# a customer-managed key; the baseline's queues had no equivalent protection.
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: the module could obtain the key itself, either
#       by referring to `module.kms` directly or with a `data "aws_kms_key"`
#       lookup against a known alias. Both were rejected. A module that names a
#       sibling module can only ever be called from a configuration that
#       happens to contain that sibling under that exact name, which makes it a
#       fragment of one particular root rather than a reusable module -- and
#       this one is called twice, from two roots. An alias lookup trades an
#       explicit, plan-visible dependency for an implicit one that resolves
#       against whatever the account currently holds, so a renamed or recreated
#       alias changes behaviour with no change to this configuration. Taking
#       the ARN as an input leaves the wiring in the caller, where each
#       environment root passes its own `kms` module's SQS key output, and
#       keeps this module callable from any root.
# WHY : Assumptions: there is deliberately no default, not even `null`. Every
#       queue here is required to be encrypted with a customer-managed key, and
#       the policy scan in .github/workflows/infra-ci.yml checks exactly that.
#       A default would make the requirement skippable by omission: a caller
#       who simply forgot the argument would still get twelve working queues, just
#       unencrypted ones, and the omission would surface only in the scan. With
#       no default the same mistake is a `plan` error, so the requirement is
#       carried by the shape of the contract instead of by a downstream check.
variable "kms_key_arn" {
  description = "ARN of the customer-managed KMS key used for server-side encryption of every queue and dead-letter queue, of the form arn:aws:kms:<region>:<aws-account-id>:key/<key-id>. Passed in by the calling root from the kms module's SQS key output and applied as kms_master_key_id on each aws_sqs_queue."
  type        = string

  # WHY : Assumptions: a full key ARN is required, although the queue attribute
  #       would also accept a bare key id or an alias. Only the ARN is
  #       unambiguous about which account and region the key belongs to, and
  #       checking the shape here catches the likeliest wiring mistake -- the
  #       kms module exposes both an id and an ARN output, so passing the wrong
  #       one is a single-token slip that otherwise surfaces during `apply`.
  #       The partition segment is matched loosely so a non-commercial
  #       partition is not rejected, while `key/` is required specifically:
  #       an alias ARN would resolve, but it reintroduces the indirection the
  #       preceding note rejected.
  validation {
    condition     = can(regex("^arn:aws[a-zA-Z-]*:kms:[a-z0-9-]+:[0-9]{12}:key/", var.kms_key_arn))
    error_message = "kms_key_arn must be a full KMS key ARN of the form arn:aws:kms:<region>:<aws-account-id>:key/<key-id>. A bare key id, or an alias/ reference, is not accepted."
  }
}

# WHY : Trade-offs: a direct exchange between KMS request volume and the
#       lifetime of a cached data key, stated in both directions because
#       neither end is obviously correct. Raising it means fewer GenerateDataKey
#       and Decrypt calls -- KMS bills per request and enforces a per-account
#       request-rate quota that twelve queues sharing one key can contend for --
#       but a data key then stays resident in the service for longer, so
#       revoking access takes effect only once the period lapses. Lowering it
#       inverts both halves: tighter key turnover, more requests, more contention
#       against that quota. The service default is carried because the
#       queues-per-key capacity arithmetic published for SQS is written against
#       it, so anyone reasoning about request volume for this queue set can use
#       that arithmetic unaltered instead of re-deriving it from a local choice.
variable "kms_data_key_reuse_period_seconds" {
  description = "Seconds SQS may reuse a KMS data key before calling KMS again, applied as kms_data_key_reuse_period_seconds on every queue and dead-letter queue."
  type        = number
  default     = 300

  validation {
    condition     = var.kms_data_key_reuse_period_seconds >= 60 && var.kms_data_key_reuse_period_seconds <= 86400
    error_message = "kms_data_key_reuse_period_seconds must be between 60 (one minute) and 86400 (twenty-four hours) inclusive."
  }
}

# -----------------------------------------------------------------------------
# Delivery and redrive. These three inputs together reconstruct the baseline's
# receive discipline, which MQ expressed with a wait interval and a syncpoint
# and which the scheduler backed with a rerun budget.
# -----------------------------------------------------------------------------

# WHY : Assumptions: 5 is constrained at both ends rather than chosen. The
#       messaging design fixes a dead-letter queue at a receive count of five
#       for each of the six source queues, and the baseline corroborates that
#       figure independently: app/scheduler/CardDemo.controlm sets
#       MAXRERUN="5" on every job it defines -- lines 4, 8, 14, 20 and 27, and
#       on all fifteen job elements in the file -- so a budget of five attempts
#       before a work item is treated as failed is the posture the system was
#       already operated under. Carrying the same number means an operator who
#       knew the Control-M configuration does not have to relearn the
#       threshold, which is why this value is a citation and not a preference.
# WHY : Trade-offs: exposed as an input even though both environments pass the
#       same number, because diagnosing a poison message sometimes needs the
#       threshold lowered so the message reaches the dead-letter queue on the
#       next attempt instead of the fifth. What is given up is that dev and
#       prod can drift apart on a value the redrive behaviour depends on; the
#       drift is at least visible, since it can only be introduced through
#       infra/envs/dev/terraform.tfvars or infra/envs/prod/terraform.tfvars.
variable "max_receive_count" {
  description = "Receives a message may accumulate on a source queue before SQS moves it to that queue's dead-letter queue. Applied as maxReceiveCount in the redrive_policy of all six source queues."
  type        = number
  default     = 5

  validation {
    condition     = var.max_receive_count >= 1 && var.max_receive_count <= 1000
    error_message = "max_receive_count must be between 1 and 1000 inclusive, the range SQS accepts for maxReceiveCount in a redrive policy."
  }
}

# WHY : Assumptions: this is the target's stand-in for a unit of work the
#       baseline expressed with syncpoint. Both inquiry programs receive under
#       syncpoint -- MQGMO-SYNCPOINT at app/app-vsam-mq/cbl/CODATE01.cbl:296
#       and app/app-vsam-mq/cbl/COACCT01.cbl:347 -- and reply under syncpoint
#       as well, with MQPMO-SYNCPOINT at CODATE01.cbl:379 and :416 and at
#       COACCT01.cbl:475 and :512.
#       Under that discipline a message is not removed from the queue until the
#       unit of work commits, and if it never commits the message returns. SQS
#       has no syncpoint; the equivalent is this timeout together with
#       delete-on-success, so the value has to bound the consumer's processing
#       time. Set it below that time and a message is redelivered while the
#       first attempt is still running, which duplicates the work AND spends
#       receives against max_receive_count -- so a message can land in the
#       dead-letter queue having in fact been processed successfully every time.
#       That failure mode is the reason this input exists at all.
# WHY : Trade-offs: 60 rather than the service default of 30. These consumers
#       run on Fargate against an Aurora Serverless cluster whose dev capacity
#       floor is zero, and a paused cluster takes on the order of fifteen
#       seconds to resume, which the first message after an idle period pays
#       before its transaction even begins. Thirty would leave that one message
#       very little headroom, and the failure it causes is the silent kind
#       described above rather than an error. The cost of the larger value is
#       that a genuinely dead consumer -- one that crashed rather than one that
#       is slow -- withholds its message for a minute before anything can retry
#       it, which is accepted because it delays a retry instead of losing one.
variable "visibility_timeout_seconds" {
  description = "Seconds a received message stays invisible to other consumers before becoming available again, applied as visibility_timeout_seconds on every queue and dead-letter queue. Must exceed the consumer's processing time."
  type        = number
  default     = 60

  # WHY : Refactoring Rationale: the floor was 0, which admitted the exact failure
  #       the rationale above says this input exists to prevent. At 0 a received
  #       message is visible to another consumer immediately, so redelivery does
  #       not merely become possible while the first attempt runs -- it becomes
  #       certain, and every redelivery spends a receive against
  #       max_receive_count. A queue configured that way sends messages to the
  #       dead-letter queue having processed each one successfully every time,
  #       which is the silent variant of the failure and the one no alarm sees.
  #       A validation that accepts the value its own justification forbids is
  #       not a bound, so the range now starts at the lowest value the consumer's
  #       measured behaviour can survive rather than at the lowest value the SQS
  #       API accepts.
  # WHY : Assumptions: the floor is 30 because it is derived from this consumer's
  #       processing time rather than chosen for roundness. The dominant
  #       component is stated in the Trade-offs above: these consumers run
  #       against an Aurora Serverless cluster whose dev capacity floor is zero,
  #       and a paused cluster takes on the order of fifteen seconds to resume,
  #       which the first message after an idle period pays BEFORE its
  #       transaction begins. Below 30 that documented resume alone consumes at
  #       least half the window and leaves the transaction itself less than the
  #       resume it already waited through, so redelivery-during-processing stops
  #       being an edge case. 30 is therefore the point below which the module
  #       would be handing a caller a configuration that cannot work, and the
  #       default of 60 remains the value both environment roots take.
  # WHY : Assumptions: this single bound also closes a second, distinct
  #       redelivery mode, and that is why no cross-variable condition
  #       accompanies it. A consumer polls, then processes; if the visibility
  #       window were shorter than its own long poll, a message could become
  #       visible again while that same consumer sat blocked in its next receive
  #       call, so it would receive its own in-flight message. The ceiling on
  #       receive_wait_time_seconds below is 20 -- the longest wait SQS accepts --
  #       and 30 exceeds it for every admissible pairing, so the invariant holds
  #       by arithmetic and a `> var.receive_wait_time_seconds` condition would be
  #       dead on every input this module can be given. It is recorded here
  #       because the implication is the reason the floor may not be lowered
  #       beneath 20: doing so would reopen a mode this bound currently closes
  #       silently.
  # WHY : Alternatives Considered: requiring merely > 0. Rejected because it
  #       would satisfy the letter of "reject zero" while leaving 1 second
  #       admissible, and a one-second window fails in precisely the way zero
  #       does -- the difference between them is a rounding error against a
  #       fifteen-second resume, not a difference in outcome.
  validation {
    condition     = var.visibility_timeout_seconds >= 30 && var.visibility_timeout_seconds <= 43200
    error_message = "visibility_timeout_seconds must be between 30 and 43200 (twelve hours) inclusive. It has to exceed the consumer's processing time: a paused Aurora Serverless cluster takes about fifteen seconds to resume before a transaction begins, so a window under 30 lets a message be redelivered while the first attempt is still running, which duplicates the work and spends receives against max_receive_count until the message reaches the dead-letter queue despite having succeeded every time. 0 is refused for the same reason in its most extreme form."
  }
}

# WHY : Assumptions: 5 is a measured baseline constant, and recognising it
#       requires a unit conversion. The authorization consumer sets
#       MOVE 5000 TO WS-WAIT-INTERVAL at
#       app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:242 and passes it to
#       MQGMO-WAITINTERVAL at :393. MQ expresses that field in MILLISECONDS, so
#       5000 is five seconds -- read as anything else the literal is badly
#       misleading. The same five-second wait appears independently in both
#       inquiry flows, MOVE 5000 TO MQGMO-WAITINTERVAL at
#       app/app-vsam-mq/cbl/CODATE01.cbl:286 and
#       app/app-vsam-mq/cbl/COACCT01.cbl:337, so all three flows already waited
#       the same interval. The value is therefore preserved rather than picked,
#       and the consumers keep the polling rhythm the system was run with.
# WHY : Trade-offs: the same value independently suppresses empty receives, and
#       that deserves naming because it is what makes long polling differ in
#       kind from short polling rather than merely in latency. At 0 a receive
#       returns immediately whether a message exists or not, so an idle consumer
#       bills one request per loop iteration and SQS charges per request; any
#       non-zero wait collapses an idle interval into a single request instead
#       of many. What is given up is up to five seconds of added latency for a
#       message that arrives just after a receive call has returned.
variable "receive_wait_time_seconds" {
  description = "Seconds a receive call waits for a message before returning empty, applied as receive_wait_time_seconds on every queue and dead-letter queue. Any non-zero value enables long polling."
  type        = number
  default     = 5

  # WHY : Assumptions: the ceiling is 20, the longest long-poll wait SQS
  #       accepts, and it is worth validating because it is the one bound in
  #       this file a caller is likely to overshoot by analogy -- a value
  #       chosen to match a visibility timeout or a retention period would sail
  #       straight past it. Checked here, the caller is told the limit at
  #       `plan`; unchecked, the provider refuses it during `apply`.
  validation {
    condition     = var.receive_wait_time_seconds >= 0 && var.receive_wait_time_seconds <= 20
    error_message = "receive_wait_time_seconds must be between 0 and 20 inclusive. 20 is the longest long-poll wait SQS accepts, and 0 disables long polling."
  }
}

# -----------------------------------------------------------------------------
# Retention. Three values, deliberately unequal, and only defensible against
# one another: requests are kept for days, replies long enough to complete the
# configured retry budget, and dead-letter queues for longer than either.
# -----------------------------------------------------------------------------

# WHY : Trade-offs: four days, and pointedly not the same value the reply queues
#       get below -- the asymmetry is the whole design. A request IS the unit of
#       work, so discarding one discards an authorization or an inquiry that was
#       genuinely asked for, and the error sink is the same case in the extreme:
#       its only purpose is to still be readable when somebody finally comes
#       looking. The window therefore has to outlast the interruption that
#       produced the backlog and then leave room for an operator to redrive from
#       the dead-letter queue, which returns messages to THIS queue and so needs
#       this retention still to accept them. Four days spans a multi-day
#       interruption, including one that begins at the end of a working week.
#       What is accepted in exchange is paying to store messages that, in the
#       worst case, nobody ever processes.
# WHY : Assumptions: reducing this on a live queue is not a neutral edit, which
#       is why it is called out on the input rather than left to be discovered.
#       SQS applies a lowered retention to messages ALREADY enqueued, so a value
#       below the age of the current backlog deletes that backlog, and the
#       change can take up to fifteen minutes to propagate. Lowering this is a
#       data-affecting change; raising it is not.
variable "request_message_retention_seconds" {
  description = "Seconds a message is retained on the two request queues and on the error queue, applied as message_retention_seconds to those three queues. Sized to outlast an interruption and still permit a redrive from the dead-letter queue."
  type        = number
  default     = 345600

  validation {
    condition     = var.request_message_retention_seconds >= 60 && var.request_message_retention_seconds <= 1209600
    error_message = "request_message_retention_seconds must be between 60 (one minute) and 1209600 (fourteen days) inclusive."
  }
}

# WHY : Assumptions: the baseline put a hard expiry on its reply and SQS has no
#       equivalent, so queue retention cannot implement business staleness.
#       COPAUA0C sets MOVE 50 TO MQMD-EXPIRY at
#       app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl:750, and MQ expresses
#       expiry in TENTHS OF A SECOND, so 50 is five seconds -- read as seconds
#       the literal is wrong by an order of magnitude. A reply older than five
#       seconds was discarded by the queue manager and never delivered at all.
#       The producer therefore stamps an `expiresAt` message attribute and the
#       consumer rejects and logs a stale message; that is the only layer that
#       decides whether a reply is still actionable. This queue-level value has
#       a different responsibility: retaining a failed reply through every
#       configured receive and visibility interval so SQS can move it to the
#       dead-letter queue instead of deleting it first.
# WHY : Refactoring Rationale: the former sixty-second default was shorter than
#       five complete sixty-second visibility cycles, so a repeatedly failing
#       reply could expire on the source queue before reaching its dead-letter
#       queue. The validation below reserves max_receive_count plus one complete
#       visibility interval and one receive-wait interval. The extra visibility
#       interval covers the transition after the final permitted receive rather
#       than assuming the move occurs at the start of that receive.
# WHY : Trade-offs: fifteen minutes is intentionally longer than the baseline's
#       non-persistent five-second reply lifetime. That added transport
#       durability is accepted because `expiresAt` still prevents stale business
#       use, while losing an unprocessable reply before it reaches quarantine
#       would remove the only evidence needed to diagnose the failure.
variable "reply_message_retention_seconds" {
  description = "Seconds a message is retained on the two reply queues, applied as message_retention_seconds to those queues. Must exceed the complete visibility and receive-wait retry budget; consumer-side expiresAt remains the business-staleness authority."
  type        = number
  default     = 900

  validation {
    condition     = var.reply_message_retention_seconds >= 60 && var.reply_message_retention_seconds <= 1209600
    error_message = "reply_message_retention_seconds must be between 60 (one minute) and 1209600 (fourteen days) inclusive."
  }

  validation {
    condition = var.reply_message_retention_seconds > (
      var.visibility_timeout_seconds * (var.max_receive_count + 1)
      + var.receive_wait_time_seconds
    )
    error_message = "reply_message_retention_seconds must be greater than visibility_timeout_seconds * (max_receive_count + 1) + receive_wait_time_seconds so a failed reply survives every receive cycle and the final move to its dead-letter queue. Business staleness belongs to the expiresAt message attribute, not queue retention."
  }
}

# WHY : Trade-offs: the inequality looks backwards and is not. A message only
#       arrives on a dead-letter queue after being received max_receive_count
#       times on its source queue, so by definition it is already old when it
#       lands -- it spent its entire failing life on the other queue. Give the
#       dead-letter queue the same retention as its source and the evidence can
#       expire while an operator is still working out that anything failed at
#       all; give it less and the evidence can be gone before the alarm has been
#       read. Retention here counts from arrival, so it has to be generous in
#       absolute terms to be useful at all. Fourteen days is the service
#       maximum, and the storage that buys is small: a dead-letter queue holds
#       only messages that already failed max_receive_count times, so its depth
#       is bounded by the failure rate rather than by throughput. That is the
#       compromise -- paying to store failed messages in exchange for the
#       failure still being diagnosable when somebody looks.
# WHY : Assumptions: one value covers all six dead-letter queues, including the
#       two whose source queues use the bounded retry-retention window above.
#       Widening those two to the service maximum is intentional: a reply that
#       failed repeatedly is exactly the case where the source retention would
#       otherwise destroy the only surviving record before investigation.
variable "dlq_message_retention_seconds" {
  description = "Seconds a message is retained on each of the six dead-letter queues, applied as message_retention_seconds to those queues. Defaults longer than either source retention, and is validated never to be shorter than the request retention, because a message only arrives here already aged."
  type        = number
  default     = 1209600

  validation {
    condition     = var.dlq_message_retention_seconds >= 60 && var.dlq_message_retention_seconds <= 1209600
    error_message = "dlq_message_retention_seconds must be between 60 (one minute) and 1209600 (fourteen days) inclusive."
  }

  # WHY : Alternatives Considered: leaving the longer-than-source relationship
  #       as prose in the comment above, which is where an invariant between two
  #       inputs usually ends up. Rejected, because the whole reasoning for this
  #       value is relative -- a dead-letter retention shorter than its source's
  #       is not merely unusual, it defeats the purpose of the queue -- and a
  #       relationship that matters that much should fail the `plan` rather than
  #       depend on the next person reading the comment. Terraform evaluates a
  #       validation condition that references the two source retentions and
  #       reports the conflict before apply, so the check cannot silently become
  #       false when either source window changes.
  # WHY : Trade-offs: the comparison is `>=` and not `>`, so an equal value is
  #       allowed even though the default is strictly longer. A strict
  #       inequality would make the service maximum of fourteen days
  #       unreachable here whenever a caller had already chosen it for the
  #       request queues, since nothing can exceed it -- the check would then
  #       block a legitimate configuration rather than a mistaken one. Refusing
  #       "shorter" catches the error this is guarding against; demanding
  #       "strictly longer" would also catch a correct configuration.
  validation {
    condition = var.dlq_message_retention_seconds >= max(
      var.request_message_retention_seconds,
      var.reply_message_retention_seconds,
    )
    error_message = "dlq_message_retention_seconds must be greater than or equal to both request_message_retention_seconds and reply_message_retention_seconds. A message reaches a dead-letter queue only after ageing on its source queue, so a shorter retention here can expire the evidence of a failure before it is examined."
  }
}

# -----------------------------------------------------------------------------
# Tagging.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this input exists because versions.tf declares no `provider`
#       block, and it must not declare one -- a module carrying its own provider
#       configuration cannot be called with count, for_each or depends_on, and
#       it takes the choice of region away from its caller. The consequence
#       lands here: with no provider there is no provider-level `default_tags`
#       to inherit, so tagging cannot happen implicitly the way it does in
#       infra/bootstrap, which IS a root and therefore does use `default_tags`.
#       Tags instead arrive as an input and main.tf attaches them to each of the
#       twelve queues individually. The asymmetry between this module and bootstrap
#       is deliberate, and it is recorded here so that it is not later
#       "simplified" into a provider block that would break every call site
#       using for_each.
# WHY : Trade-offs: an empty map is the default, which makes tagging optional. A
#       module that required tags would be the stricter contract, but an
#       untagged queue is plainly visible in the console and in cost reporting
#       rather than silently wrong, and both environment roots pass a common tag
#       set, so the stricter contract would prevent nothing that is not already
#       obvious at the call site.
variable "tags" {
  description = "Tags applied to every queue and dead-letter queue created by this module. Supplied as an input because the module declares no provider and so inherits no provider-level default_tags."
  type        = map(string)
  default     = {}
}
