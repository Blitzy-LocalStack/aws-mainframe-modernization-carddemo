# =============================================================================
# infra/modules/eventbridge-scheduler/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `eventbridge-scheduler` module -- every
#   value a caller may supply, and nothing else. The module provisions one
#   EventBridge Scheduler schedule, inside its own schedule group, whose target
#   is `states:StartExecution` on the `carddemo-daily-batch` state machine;
#   the least-privilege IAM role that schedule assumes; and the dead-letter
#   target that captures an invocation the scheduler could not deliver.
#
#   This module calls no sibling module and reads no data source, so this file
#   IS the whole coupling surface between it and the rest of the tree. The two
#   ARNs it cannot work without -- the state machine to start, and the queue
#   that catches a failed invocation -- arrive here as plain inputs from the
#   environment root. That is deliberate and it is what keeps every account
#   identifier, region and resource name out of this repository: there is no
#   default anywhere below that contains one.
#
#   The retired mainframe scheduler definitions are the source of intent rather
#   than of syntax. Several defaults below carry a value measured directly out
#   of app/scheduler/CardDemo.controlm and app/scheduler/CardDemo.ca7, and each
#   such default names the file and the count it came from; those files are
#   reference-only and are never modified. The job-to-job sequencing they also
#   encode is NOT this module's concern -- it belongs to the state machine in
#   infra/modules/step-functions-batch -- so no input here relates to step
#   ordering, to condition codes, or to the operator quiesce bracket that
#   brackets the chain.
#
# Parameters:
#   Sixteen `variable` blocks under the seven banners below: Naming, Consumed
#   ARNs, Schedule, Delivery and retry, Encryption, Target payload, Tagging.
#   Every one declares an explicit `type` and a `description` stating what the
#   value means and controls, which together are this language's stand-in for
#   a documented parameter list. `infra/.tflint.hcl` enables both
#   terraform_typed_variables and terraform_documented_variables and treats a
#   finding as a build failure, so neither is optional.
#
# Return values:
#   None. This file declares no `output` -- the module's exported values live
#   in outputs.tf -- and no resource, which live in main.tf. Those two files
#   plus versions.tf and README.md are the rest of the module.
#
# Errors:
#   Nine `validation` blocks reject a malformed or out-of-domain value at plan
#   time with a message naming the accepted domain. Three of them -- the two
#   ARN shapes and `schedule_expression` -- exist because the AWS provider
#   checks nothing about those values whatsoever, which was measured against
#   the pinned provider rather than assumed; the rest restate a bound or a
#   domain the provider does enforce, so that a rejection names the input the
#   caller supplied instead of a line inside main.tf. Every bound and every
#   enumerated domain quoted below was read off the pinned provider
#   (`hashicorp/aws ~> 6.56`, exercised at 6.57.1) by driving it with valid and
#   invalid values, and each numeric range was independently confirmed against
#   the EventBridge Scheduler API reference. None was assumed.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming
#
# The module composes every resource name from these two inputs rather than
# accepting one name per resource. A caller therefore cannot produce a schedule
# whose group or role belongs to a different environment, which is a class of
# mistake that is invisible in a plan diff.
# -----------------------------------------------------------------------------

variable "name_prefix" {
  description = "Leading token shared by the schedule, its schedule group and the IAM role the schedule assumes, so the three resources that make up one nightly trigger are recognisable as a set in the console and in cost reporting."
  type        = string
  default     = "carddemo"
}

variable "environment" {
  description = "Environment this schedule belongs to. Supplies the `-<env>` suffix that keeps the dev and prod copies of every resource this module creates distinct. Accepted values: dev, prod."
  type        = string

  # WHY : (Trade-offs) this is the one input in the file with no default, and
  #       the omission is the point. Defaulting it to "dev" would make the
  #       wrong environment the silent outcome of a forgotten argument: a prod
  #       root that failed to pass it would still plan cleanly and still
  #       apply, having quietly named its schedule, group and role after dev
  #       and so collided with -- or adopted -- resources the dev root
  #       manages. The cost accepted is one extra line in each of the two
  #       environment roots, paid against a failure whose blast radius is a
  #       production trigger pointing at development state.
  validation {
    # WHY : (Assumptions) the domain is closed at exactly two values because
    #       the tree defines exactly two environment roots, infra/envs/dev and
    #       infra/envs/prod, and every name in the target carries one of the
    #       two as its suffix. A third value would name resources that no root
    #       manages and no README documents, so rejecting it at plan time is
    #       cheaper than discovering the orphaned schedule afterwards.
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "The environment value must be one of: dev, prod."
  }
}

# -----------------------------------------------------------------------------
# Consumed ARNs -- the cross-module coupling surface
#
# These are the only channel through which this module learns about anything
# outside itself. The first two are required and have no default, because an
# ARN embeds an account identifier and a region: supplying a default would
# write one environment's account number into the repository, which is exactly
# what the project's no-secrets-in-source constraint forbids. Each value is the
# published output of the module that owns the resource, wired through by the
# environment root.
# -----------------------------------------------------------------------------

variable "state_machine_arn" {
  description = "ARN of the `carddemo-daily-batch` Step Functions state machine this schedule starts. Published as an output by infra/modules/step-functions-batch and passed in by the environment root; the schedule's IAM role is granted states:StartExecution on exactly this value."
  type        = string

  validation {
    # WHY : (Assumptions) the shape is checked because a wrong-but-well-formed
    #       ARN here fails silently rather than loudly. main.tf scopes the
    #       role's states:StartExecution grant to precisely this string, so an
    #       ARN naming some other service or resource type -- an activity, a
    #       function, a task definition -- still yields a syntactically valid
    #       IAM policy and a schedule that applies without complaint, and then
    #       authorises nothing the target needs. The symptom appears at the
    #       first invocation, in the dead-letter queue, detached from the
    #       change that caused it. Asserting the `states:` service field and
    #       the `:stateMachine:` resource type converts that into a plan-time
    #       error naming the offending input.
    #       Alternatives Considered: anchoring the pattern at the end, which
    #       would be the tighter check and is rejected. A version-qualified or
    #       alias-qualified machine ARN carries a further colon-delimited
    #       segment, so anchoring would refuse ARNs the service issues and
    #       accepts.
    condition     = can(regex("^arn:[a-z0-9-]+:states:[a-z0-9-]+:[0-9]{12}:stateMachine:", var.state_machine_arn))
    error_message = "The state_machine_arn value must be a Step Functions state machine ARN of the form arn:<partition>:states:<region>:<account-id>:stateMachine:<name>."
  }
}

variable "dead_letter_arn" {
  description = "ARN of the SQS queue that receives an invocation EventBridge Scheduler could not deliver to the state machine. This is what makes a failed nightly trigger captured and inspectable rather than silently lost."
  type        = string

  validation {
    # WHY : (Assumptions) EventBridge Scheduler delivers an undeliverable
    #       invocation to an SQS queue and to nothing else, so an ARN of any
    #       other service cannot work -- but it is accepted into the plan and
    #       rejected only by the service at apply. Checking the `sqs:` service
    #       field here moves that rejection to the point where the caller can
    #       see which input was wrong.
    #       Alternatives Considered: anchoring at the end, rejected for the
    #       same reason as above -- a FIFO queue ARN ends in `.fifo` and the
    #       queue-name segment is otherwise unconstrained, so the pattern stops
    #       after the account field rather than guessing at the name.
    condition     = can(regex("^arn:[a-z0-9-]+:sqs:[a-z0-9-]+:[0-9]{12}:", var.dead_letter_arn))
    error_message = "The dead_letter_arn value must be an SQS queue ARN of the form arn:<partition>:sqs:<region>:<account-id>:<queue-name>."
  }
}

variable "dead_letter_kms_key_arn" {
  description = "Customer-managed KMS key encrypting the dead-letter queue, when that queue is CMK-encrypted; null when it relies on SQS-managed encryption. Controls whether the schedule's role is additionally granted kms:GenerateDataKey and kms:Decrypt on that key."
  type        = string
  default     = null
  nullable    = true

  # WHY : (Assumptions) null is a meaningful sentinel here rather than an
  #       absent value, and it selects between two different IAM outcomes that
  #       main.tf resolves. Left null, the queue is assumed to use SQS-managed
  #       encryption and no key grant is minted, which keeps the role at least
  #       privilege. Given a key ARN, the queue is assumed CMK-encrypted -- the
  #       posture the sqs module actually provisions, since every queue in this
  #       target is SSE-KMS under one of the four customer-managed keys -- and
  #       the role must also hold kms:GenerateDataKey and kms:Decrypt on that
  #       key. Without those two actions the dead-letter write is refused by
  #       KMS, and because the write being refused IS the failure path, nothing
  #       is left to report it: the invocation that failed is simply gone. That
  #       is the specific outcome this input exists to prevent, and it is why
  #       omitting the variable entirely would have been the more dangerous
  #       simplification.
}

# -----------------------------------------------------------------------------
# Schedule
#
# When the chain starts, in which zone that is read, and whether it starts at
# all. The baseline fixes two of these three and leaves the third open, and the
# comments below are careful to distinguish them. In
# app/scheduler/CardDemo.controlm the cadence is stated -- DAYS="ALL", on the
# four jobs of the daily folder -- and so is a must-end-by deadline,
# TIMETO="23:00", on all fifteen of its job definitions. A start time of day is
# stated nowhere.
# -----------------------------------------------------------------------------

variable "schedule_expression" {
  description = "Cron expression in the six-field EventBridge Scheduler form `cron(minutes hours day-of-month month day-of-week year)` fixing when the batch chain is started. Only the cron(...) form is accepted; rate(...) and at(...) are rejected."
  type        = string

  # WHY : (Assumptions) the default is a daily expression because the baseline
  #       daily folder in app/scheduler/CardDemo.controlm carries DAYS="ALL" on
  #       each of its four jobs -- an every-day cadence, which `* *` in the
  #       day-of-month and month fields reproduces exactly. Note that the
  #       day-of-week field must be `?` and not `*`: the service rejects an
  #       expression that constrains both day-of-month and day-of-week, so the
  #       two cannot both be wildcards.
  #       The time of day is the one element the baseline does NOT state, so it
  #       is a decision rather than a transcription and is recorded as such.
  #       00:00 is chosen because the only related value the baseline does fix
  #       is TIMETO="23:00", a must-end-by deadline; starting at the first
  #       minute of the day puts the start and that deadline unambiguously on
  #       the same calendar date, whereas a late-evening start would place them
  #       on either side of midnight depending on the day. It is also the time
  #       component named by the CA-7 scheduling floor at
  #       app/scheduler/CardDemo.ca7 line 39. This is a placement decision
  #       about which date the run belongs to; it asserts nothing about how
  #       long anything takes.
  default = "cron(0 0 * * ? *)"

  validation {
    # WHY : (Assumptions) this guard is load-bearing rather than defensive,
    #       because the AWS provider validates this argument not at all. That
    #       was measured against the pinned provider, not assumed: `rate(1
    #       day)`, `at(2026-01-01T00:00:00)` and even the literal string
    #       `nonsense` are each accepted by `terraform validate` without a
    #       warning. `rate(...)` is the dangerous one -- it is well formed, it
    #       applies cleanly, and it silently replaces a calendar schedule with
    #       a fixed interval that no longer lands at a fixed time of day, so
    #       the run drifts away from the deadline the baseline states with
    #       nothing anywhere reporting a problem. This condition is the only
    #       check in the whole toolchain that rejects it.
    #       Alternatives Considered: validating the six cron fields properly,
    #       rather than only the prefix. Rejected because re-implementing the
    #       service's own parser here would create a second source of truth for
    #       what a valid expression is, and every disagreement between the two
    #       would surface as this module refusing an expression the service
    #       accepts -- a worse failure than the one being prevented, because it
    #       blocks a correct change instead of catching a wrong one.
    condition     = startswith(var.schedule_expression, "cron(")
    error_message = "The schedule_expression value must be a cron(...) expression; the rate(...) and at(...) forms are not accepted by this module."
  }
}

variable "schedule_expression_timezone" {
  description = "IANA time zone name, such as UTC or America/New_York, that the cron expression above is interpreted in."
  type        = string

  # WHY : (Assumptions) stated explicitly rather than left to the service
  #       default, because the baseline's TIMETO="23:00" is an operator
  #       wall-clock deadline and a wall clock is exactly what a zone decides.
  #       Naming the zone makes the daylight-saving behaviour a recorded
  #       decision instead of an accident: in a zone that observes a summer
  #       shift the same cron expression fires an hour earlier or later twice a
  #       year, moving the run relative to that deadline, and an implicit zone
  #       gives nobody a place to notice. UTC is chosen because it observes no
  #       shift, so the expression means one instant all year; an operator who
  #       needs the run pinned to a local wall clock instead overrides this
  #       with the zone name and accepts the shift knowingly.
  default = "UTC"
}

variable "schedule_state" {
  description = "Whether the schedule fires. ENABLED starts the batch chain on the expression above; DISABLED provisions the schedule, its group and its role but never triggers a run, which lets a dev root stand the trigger up and verify it without running the nightly chain. Accepted values: ENABLED, DISABLED."
  type        = string
  default     = "ENABLED"

  validation {
    # WHY : (Assumptions) the two accepted values and their exact casing were
    #       read off the pinned provider, which reports `expected state to be
    #       one of ["ENABLED" "DISABLED"]` and rejects both a third value and
    #       a lowercase spelling of an accepted one. Restating the domain here
    #       rather than relying on that check attributes the failure to the
    #       caller's input instead of to a line inside main.tf.
    condition     = contains(["ENABLED", "DISABLED"], var.schedule_state)
    error_message = "The schedule_state value must be one of: ENABLED, DISABLED."
  }
}

variable "start_date" {
  description = "RFC3339 instant before which the schedule must not fire, or null to let it fire from the moment it is created."
  type        = string
  default     = null
  nullable    = true

  # WHY : (Assumptions) this input is not speculative -- the baseline states
  #       the same constraint. app/scheduler/CardDemo.ca7 line 39 reads
  #       `. DONT SCHEDULE BEFORE 03237 AT 0000`, a floor on the earliest
  #       instant the job may be picked up at all, distinct from the recurring
  #       expression that governs it thereafter. The concept is therefore
  #       carried across, but its value is not: that Julian instant belongs to
  #       one historical cutover, and hard-coding any specific instant into a
  #       reusable module would bake one environment's cutover date into every
  #       caller. null leaves the floor unset, which is the correct behaviour
  #       for a schedule with no cutover to wait for, and an environment
  #       performing one supplies its own.
}

variable "flexible_time_window_mode" {
  description = "Whether EventBridge Scheduler may shift an invocation within a window rather than firing at the exact expression time. OFF fires at the expression time; FLEXIBLE spreads it across the window given by flexible_time_window_minutes. Accepted values: OFF, FLEXIBLE."
  type        = string

  # WHY : (Trade-offs) OFF, because a flexible window spends the one budget the
  #       baseline actually states. Every job definition in
  #       app/scheduler/CardDemo.controlm carries TIMETO="23:00" -- fifteen
  #       occurrences, a single value on every real job -- and that is a hard
  #       must-end-by deadline, not a preference. A flexible window moves the
  #       invocation later inside the window, which consumes part of the margin
  #       ahead of that deadline while returning nothing to a chain that runs
  #       once a night: the load-spreading and thundering-herd jitter the
  #       feature exists to provide only pay off across many schedules
  #       contending for one target, and there is exactly one schedule here.
  #       What is given up by choosing OFF is precisely that jitter, and it is
  #       given up knowingly; FLEXIBLE stays available as an opt-in for a
  #       caller whose circumstances differ.
  default = "OFF"

  validation {
    # WHY : (Assumptions) the two accepted values and their casing were read
    #       off the pinned provider, which reports `expected mode to be one of
    #       ["OFF" "FLEXIBLE"]`. As with schedule_state, restating the domain
    #       names the offending input rather than a line in main.tf.
    condition     = contains(["OFF", "FLEXIBLE"], var.flexible_time_window_mode)
    error_message = "The flexible_time_window_mode value must be one of: OFF, FLEXIBLE."
  }
}

variable "flexible_time_window_minutes" {
  description = "Width in minutes of the window an invocation may be shifted within, from 1 to 1440. Meaningful only when flexible_time_window_mode is FLEXIBLE; leave null when the mode is OFF."
  type        = number
  default     = null
  nullable    = true

  # WHY : (Assumptions) null rather than a number, because the value is
  #       meaningless unless the mode above is FLEXIBLE, and the mode defaults
  #       to OFF. A numeric default would therefore ship a width that describes
  #       a window the module does not open, and main.tf gates the argument on
  #       the mode rather than on this being set. The pinned provider does not
  #       object to a width supplied alongside mode OFF -- that combination was
  #       measured and accepted -- so the gating has to be the module's job.
  validation {
    # WHY : (Assumptions) the 1-1440 bound is the pinned provider's, obtained
    #       by driving it until it complained: it reports `expected
    #       maximum_window_in_minutes to be in the range (1 - 1440)` and
    #       rejects both 0 and 1441. The null branch is explicit because a
    #       comparison against null is not a false condition but an evaluation
    #       error, which would surface as an internal expression failure rather
    #       than as the clear message below.
    condition = var.flexible_time_window_minutes == null ? true : (
      var.flexible_time_window_minutes >= 1 &&
      var.flexible_time_window_minutes <= 1440
    )
    error_message = "The flexible_time_window_minutes value must be between 1 and 1440, or null when flexible_time_window_mode is OFF."
  }
}

# -----------------------------------------------------------------------------
# Delivery and retry
#
# How hard the scheduler tries before the invocation is handed to the
# dead-letter queue. The two settings are a conjunction, not alternatives: the
# EventBridge Scheduler retry policy continues until either the attempt count
# is exhausted or the event age is reached, whichever comes first. That is what
# makes the pairing below coherent, and it is stated here once so neither
# comment has to repeat it.
# -----------------------------------------------------------------------------

variable "maximum_retry_attempts" {
  description = "Retries attempted, with exponential backoff, before the invocation is sent to the dead-letter queue. Accepts 0 to 185, the range the pinned AWS provider enforces."
  type        = number

  # WHY : (Assumptions) five is preserved baseline behaviour, not a round
  #       number. app/scheduler/CardDemo.controlm carries MAXRERUN="5" on
  #       fifteen of its seventeen job definitions -- every real job; the only
  #       two exceptions are the SMART_FOLDER container nodes at lines 32 and
  #       57, which carry MAXRERUN="0" because a folder is a grouping and not a
  #       unit of work. Five is therefore what an operator of the existing
  #       system already expects, and the same figure is used independently for
  #       the SQS dead-letter maxReceiveCount and for the per-state Step
  #       Functions retries, so the whole target retries to one consistent
  #       depth rather than three unrelated ones.
  #       It is deliberately a narrowing: the platform's own default for this
  #       field is 185, so accepting the default would have replaced a stated
  #       baseline value with a platform one roughly thirty-seven times larger,
  #       and a failing nightly trigger would keep being re-delivered long past
  #       the point where the baseline would have stopped and reported.
  default = 5

  validation {
    # WHY : (Assumptions) the 0-185 bound is the pinned provider's, established
    #       by driving it with out-of-range values rather than by reading a
    #       guide: it reports `expected maximum_retry_attempts to be in the
    #       range (0 - 185)` and rejects -1 and 186 while accepting 0 and 185.
    #       The EventBridge Scheduler API reference states the same minimum and
    #       maximum, so two independent sources agree on these numbers.
    condition     = var.maximum_retry_attempts >= 0 && var.maximum_retry_attempts <= 185
    error_message = "The maximum_retry_attempts value must be between 0 and 185."
  }
}

variable "maximum_event_age_in_seconds" {
  description = "Outer bound, in seconds, on how long retry attempts may continue before the invocation is sent to the dead-letter queue. Accepts 60 to 86400, the range the pinned AWS provider enforces."
  type        = number

  # WHY : (Trade-offs) the default is the top of the accepted range so that the
  #       attempt count above, and not this bound, is what actually governs.
  #       Because the two are a conjunction, a smaller value here would cut the
  #       retrying short before the five attempts carried over from the
  #       baseline had been made -- silently overriding the one figure the
  #       baseline does state with one it does not. Holding this at the ceiling
  #       narrows exactly one setting, the retry count, and leaves the other
  #       where the platform puts it, which is also 86400.
  #       (Trade-offs, and an honest divergence.) The baseline's nearest
  #       analogue is Control-M MAXWAIT="7" -- fifteen occurrences in
  #       app/scheduler/CardDemo.controlm -- seven days of willingness to keep
  #       waiting for a job's conditions to be met. 86400 is the largest value
  #       this field accepts, far below that, so the baseline figure cannot be
  #       represented on this platform at all and is deliberately NOT carried
  #       over. What replaces it is the dead-letter queue: instead of waiting
  #       for days, a trigger that cannot be delivered is captured and
  #       surfaced. Recording that plainly is the point -- quietly writing
  #       86400 as though it were the baseline value would misrepresent a
  #       divergence as a transcription.
  default = 86400

  validation {
    # WHY : (Assumptions) the 60-86400 bound is the pinned provider's, obtained
    #       the same way as the bound above: it reports `expected
    #       maximum_event_age_in_seconds to be in the range (60 - 86400)` and
    #       rejects 59 and 86401 while accepting both endpoints. The
    #       EventBridge Scheduler API reference states the same range. Note the
    #       floor is 60 and not 0, so this field cannot be used to disable
    #       retrying -- setting maximum_retry_attempts to 0 is how that is
    #       expressed.
    condition = (
      var.maximum_event_age_in_seconds >= 60 &&
      var.maximum_event_age_in_seconds <= 86400
    )
    error_message = "The maximum_event_age_in_seconds value must be between 60 and 86400."
  }
}

# -----------------------------------------------------------------------------
# Encryption
#
# Neither key input is shape-validated, and that is a decision rather than an
# omission: a KMS key may legitimately be referenced by a key ARN, by an alias
# ARN or in a multi-region form, and a pattern tight enough to be worth having
# would reject at least one of those. The two required ARNs above are validated
# because each has exactly one correct service and resource type; these do not.
# -----------------------------------------------------------------------------

variable "kms_key_arn" {
  description = "Customer-managed KMS key used to encrypt the schedule's own stored payload, or null to use the service-owned key."
  type        = string
  default     = null
  nullable    = true

  # WHY : (Trade-offs) null selects the service-owned key, which is what makes
  #       this module usable by a caller that has not provisioned a key for it,
  #       while leaving the encrypted-with-a-customer-managed-key posture
  #       available to one that has. The distinction is real but narrow: the
  #       payload stored here is the small JSON document handed to
  #       StartExecution, which carries no cardholder data, so the four
  #       customer-managed keys in this target are aimed at the database,
  #       object storage, secrets and queues rather than at this. Defaulting to
  #       null keeps the module independent of the kms module instead of
  #       requiring a key it has little to protect.
}

# -----------------------------------------------------------------------------
# Target payload
# -----------------------------------------------------------------------------

variable "target_input" {
  description = "Additional key/value pairs merged into the JSON document handed to StartExecution. Keys are added to the payload the module already builds; they cannot remove or replace it."
  type        = map(string)
  default     = {}

  # WHY : (Assumptions) additive by contract, and empty by default, because one
  #       key in that payload is not the caller's to remove. main.tf always
  #       injects the scheduler's own scheduled-time context attribute, which
  #       is how the state machine receives the business date it is running
  #       for. That matters because the migrated batch takes its business date
  #       as a parameter and never reads it from a container clock -- the same
  #       contract the baseline JCL states as PARM='2022071800' and the target
  #       carries as a --business-date argument -- and it is what makes a rerun
  #       reproduce its original output instead of quietly processing whatever
  #       day the retry happens to land on. A caller able to overwrite the
  #       payload wholesale could drop that key and get a chain that still
  #       runs, still succeeds, and dates its work wrongly. Merging on top of
  #       the module's own keys removes that possibility while still allowing a
  #       root to pass anything else the state machine accepts.
}

# -----------------------------------------------------------------------------
# Tagging
# -----------------------------------------------------------------------------

variable "tags" {
  description = "Tags applied to the schedule group and to the schedule's IAM role. They are not applied to the schedule itself, because aws_scheduler_schedule exposes no tags argument."
  type        = map(string)
  default     = {}

  # WHY : (Assumptions) the gap in coverage is the service's, not this
  #       module's, and is recorded so a reader does not file it as a bug. The
  #       schedule resource carries no tags argument in the pinned provider's
  #       schema, so the group and the role are the only taggable surfaces this
  #       module creates; tagging the group is what keeps the schedule
  #       attributable, since a schedule belongs to exactly one group. An empty
  #       default is correct rather than merely convenient: the environment
  #       roots set provider-level default_tags, so tags common to every
  #       resource in an environment already arrive without being restated
  #       here, and this input is for the ones specific to this module.
}
