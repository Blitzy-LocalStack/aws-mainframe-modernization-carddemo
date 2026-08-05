# =============================================================================
# infra/modules/eventbridge-scheduler/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The nightly trigger for the migrated batch chain, and nothing else. This
#   file declares four resources: a schedule group, an EventBridge Scheduler
#   schedule inside it, the least-privilege IAM role that schedule assumes, and
#   that role's one permissions policy. The schedule's only target is
#   `states:StartExecution` on the `carddemo-daily-batch` state machine, whose
#   ARN arrives as an input because this module never names another resource.
#
#   The retired mainframe scheduler definitions are the source of intent, not
#   of syntax. app/scheduler/CardDemo.ca7 and app/scheduler/CardDemo.controlm
#   describe when the chain starts and how deeply a failed start is retried;
#   both files are reference-only and are never modified. What they also encode
#   -- which job runs after which -- is deliberately absent here; see the scope
#   boundary below.
#
# Non-obvious design decisions:
#   - Assumptions: this module STARTS the chain; it does not model the chain.
#     The operator quiesce bracket is the temptation worth naming explicitly,
#     because it looks like scheduling and is not. Three of the four containers
#     in app/scheduler/CardDemo.controlm open and close with it -- CLOSEFIL at
#     lines 4, 33 and 65 and OPENFIL at lines 20, 50 and 87 -- and each of those
#     JCL members is an SDSF step issuing five `CEMT SET FIL(...) CLO'` or
#     `OPE'` operator commands against TRANSACT, CCXREF, ACCTDAT, CXACAIX and
#     USRSEC. None of that belongs in a scheduler: the bracket has to sit
#     INSIDE the unit of work it protects, so that a chain which fails midway
#     still re-opens the files. It is therefore state 1 (QuiesceOnlineWrites)
#     and state 11 (ResumeOnlineWrites) of the state machine in
#     infra/modules/step-functions-batch, which flips a read-only flag rather
#     than driving a terminal. Putting either end of the bracket here would
#     split one atomic protection across two Terraform modules and leave the
#     files closed whenever the schedule fired but the chain did not complete.
#     The same reasoning retires WAITSTEP / PGM=COBSWAIT: a wait between two
#     steps is expressed by the transition between two states, not by a
#     scheduled invocation.
#   - Assumptions: the INCOND/OUTCOND token hand-off that sequences those jobs
#     is a linear DAG of prerequisites, which is what a state machine is for.
#     No argument below relates to step ordering or to condition codes.
#   - Alternatives Considered: every argument set below is governed by an input
#     declared in variables.tf. Arguments the pinned provider offers but no
#     input governs are left unset rather than given a value invented here,
#     because a reusable module that hard-codes a schedule's description, end
#     instant or post-run disposition imposes one caller's circumstances on all
#     of them. The specific omissions are enumerated at the foot of this file.
# =============================================================================

# WHY : Alternatives Considered: the partition, region and account are read
#       from the provider's own context rather than accepted as inputs or
#       written as literals. An input would let a caller describe an account
#       that is not the one being applied to, which would silently produce a
#       trust policy that trusts nothing reachable; a literal would put an
#       account identifier and a region into this repository, which the
#       migration forbids outright. These three are the only lookups this
#       module performs, and all three are consumed when composing the
#       schedule-group ARN that narrows the role's trust policy below.
data "aws_partition" "current" {}

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

locals {
  # WHY : Assumptions: the `-<environment>` suffix is the naming convention the
  #       surrounding target already uses for every environment-scoped resource,
  #       so a schedule, its group and its role sort beside the queues and
  #       buckets of the same environment rather than forming a separate
  #       vocabulary. The middle token deliberately echoes the logical name of
  #       the state machine this schedule starts, `carddemo-daily-batch`, so the
  #       relationship is legible from a console listing; it is composed from
  #       the inputs rather than hard-coded, because the ARN of that machine is
  #       an input and a module that also spelled its name would have two
  #       sources of truth for one resource that could disagree.
  schedule_name       = "${var.name_prefix}-daily-batch-${var.environment}"
  schedule_group_name = "${var.name_prefix}-batch-${var.environment}"
  role_name           = "${var.name_prefix}-daily-batch-scheduler-${var.environment}"

  # WHY : Assumptions: the ARN of the group is composed here, from the three
  #       lookups above and the group name below, rather than read back from
  #       aws_scheduler_schedule_group.this.arn. Both forms denote the same
  #       string, but only the composed one is fully known at plan time; the
  #       attribute form renders as "(known after apply)" and would hide the
  #       exact scope of the trust policy in the artifact the change is actually
  #       reviewed from. It also keeps the role independent of the group in the
  #       dependency graph, so the two can be created in either order. The
  #       residual risk of composing a string by hand is that it could drift
  #       from the resource it describes, and it is closed by deriving both from
  #       the single local above.
  schedule_group_arn = format(
    "arn:%s:scheduler:%s:%s:schedule-group/%s",
    data.aws_partition.current.partition,
    data.aws_region.current.region,
    data.aws_caller_identity.current.account_id,
    local.schedule_group_name,
  )

  # WHY : Assumptions: this is EventBridge Scheduler's scheduled-time context
  #       attribute, spelled exactly as the service matches it. The service
  #       substitutes the keyword by finding it literally in the target payload,
  #       so the angle brackets are part of the token and not punctuation around
  #       it -- which is what the escaping fix below exists to protect.
  scheduled_time_attribute = "<aws.scheduler.scheduled-time>"

  # WHY : Assumptions: the module's own key is merged LAST so that it wins. A
  #       caller supplying the same key is overridden rather than honoured,
  #       which is the whole point of the payload being additive: the state
  #       machine's business date has to come from the schedule that fired, and
  #       a caller able to replace it could produce a chain that still runs,
  #       still reports success, and dates its work wrongly. Caller keys that do
  #       not collide are preserved untouched.
  target_payload = merge(
    var.target_input,
    { scheduledTime = local.scheduled_time_attribute },
  )

  # WHY : Trade-offs: jsonencode alone is not usable here, and this is not
  #       cosmetic. Terraform's jsonencode HTML-escapes `<` to \u003c and `>`
  #       to \u003e, which was confirmed against the pinned toolchain by
  #       inspecting the encoded bytes -- the encoded form contains no literal
  #       `<` at all. Left escaped, the payload reaching the service reads
  #       \u003caws.scheduler.scheduled-time\u003e, the literal keyword is
  #       absent, no substitution happens, and the state machine is handed the
  #       placeholder text instead of an instant. That failure is silent: the
  #       schedule fires, the target starts, and only the dated output is wrong.
  #       Restoring the two delimiters costs a string rewrite and is safe in
  #       both directions, because an unescaped `<` is valid JSON and the
  #       rewritten document still parses. The alternative -- assembling the
  #       JSON by hand with a template -- would give up jsonencode's quoting and
  #       escaping of the caller's own values, which is a far worse trade. `&`
  #       is escaped by the same mechanism and is deliberately left alone: no
  #       context attribute contains one, and a caller's value legitimately may.
  target_input_json = replace(
    replace(jsonencode(local.target_payload), "\\u003c", "<"),
    "\\u003e",
    ">",
  )
}

# WHY : Alternatives Considered: a dedicated group, not the account's `default`
#       group. Omitting a group is legal and places the schedule in `default`,
#       and two consequences follow that cannot be recovered afterwards. The
#       `default` group is not a resource this module would manage, so nothing
#       would carry the caller's tags and the schedule would be unattributable
#       in cost reporting; and, decisively, the group is the granularity at
#       which a schedule's ARN can be bounded, so without a distinct group the
#       confused-deputy condition on the role below could not be narrowed past
#       "any schedule in this account".
resource "aws_scheduler_schedule_group" "this" {
  name = local.schedule_group_name
  tags = var.tags
}

# WHY : Assumptions: the trust policy is scoped by both aws:SourceAccount and
#       aws:SourceArn because the role is assumed by a service principal, not by
#       a caller this account controls. Without the pair, any EventBridge
#       Scheduler schedule -- including one in another account that named this
#       role -- could induce the service to assume it and start the batch chain.
data "aws_iam_policy_document" "assume_role" {
  statement {
    sid     = "AllowEventBridgeSchedulerToAssumeThisRole"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    # WHY : Alternatives Considered: the source is bounded to the schedule
    #       GROUP, not to the schedule itself, and the group ARN is matched
    #       exactly rather than by pattern. Scoping to the individual schedule
    #       is the tighter-looking option and is wrong twice over. It is
    #       unsatisfiable in this graph -- the schedule takes this role's ARN,
    #       so a trust policy naming the schedule would close a cycle Terraform
    #       cannot resolve -- and EventBridge Scheduler specifies the group ARN
    #       as the value this condition takes, the older per-schedule ARN form
    #       having been superseded. StringEquals rather than ArnLike because the
    #       composed value is a complete ARN carrying no wildcard, and a pattern
    #       test on an exact string would only invite a wildcard to be widened
    #       into it later. The group is thus the tightest bound available, and
    #       it still excludes every other group in the account.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [local.schedule_group_arn]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = local.role_name
  assume_role_policy = data.aws_iam_policy_document.assume_role.json
  tags               = var.tags
}

data "aws_iam_policy_document" "permissions" {
  # WHY : Assumptions: the only thing this principal ever legitimately does is
  #       begin an execution -- it never needs to stop one, describe one or read
  #       its history -- so naming the action singly costs nothing that is ever
  #       exercised, whereas states:* would also confer cancellation over a
  #       running batch chain. The resource is likewise the exact state machine
  #       ARN the caller supplied rather than a prefix, so the role cannot start
  #       a different machine that happens to share a naming stem. Both
  #       narrowings are load-bearing because a schedule is an unattended
  #       principal: whatever this policy permits is permitted with no operator
  #       present to notice it being used.
  statement {
    sid       = "AllowStartDailyBatchExecution"
    actions   = ["states:StartExecution"]
    resources = [var.state_machine_arn]
  }

  # WHY : Assumptions: configuring a dead-letter target does not by itself make
  #       one work. The scheduler writes the undeliverable invocation using this
  #       role, so without sqs:SendMessage on that exact queue the write is
  #       refused -- and because the write IS the failure path, the refusal has
  #       nowhere to be reported and the failed invocation is simply gone. That
  #       is the outcome the dead-letter target exists to prevent, so the grant
  #       is not an add-on to the configuration below; it is half of it.
  statement {
    sid       = "AllowDeadLetterDelivery"
    actions   = ["sqs:SendMessage"]
    resources = [var.dead_letter_arn]
  }

  # WHY : Assumptions: when the schedule payload is encrypted with a
  #       customer-managed key, Scheduler reads that payload under this execution
  #       role immediately before invoking the target. The role therefore needs
  #       kms:Decrypt on that exact key; configuring kms_key_arn on the schedule
  #       without this grant creates a schedule whose trigger is authorized to
  #       start the state machine but cannot read the input it must send.
  #       Alternatives Considered: granting GenerateDataKey here too, matching
  #       the dead-letter statement below. Rejected because the execution role
  #       only decrypts the already-stored schedule payload; the principal that
  #       creates or updates the schedule owns the write-time key permission.
  dynamic "statement" {
    for_each = var.kms_key_arn == null ? [] : [var.kms_key_arn]

    content {
      sid       = "AllowSchedulePayloadDecryption"
      actions   = ["kms:Decrypt"]
      resources = [statement.value]
    }
  }

  # WHY : Alternatives Considered: emitted through a dynamic block over a
  #       one-or-zero element list, so that a queue relying on SQS-managed
  #       encryption mints no key grant at all. Granting the two actions
  #       unconditionally would have been one line shorter and would have left
  #       the role holding KMS permissions against a null resource, which is
  #       both wider than necessary and unrepresentable as a valid policy. The
  #       two actions are the pair a queue write against a customer-managed key
  #       needs -- the data key to encrypt with, and decrypt for the envelope --
  #       and they are scoped to that one key rather than to the account's keys.
  dynamic "statement" {
    for_each = var.dead_letter_kms_key_arn == null ? [] : [var.dead_letter_kms_key_arn]

    content {
      sid       = "AllowDeadLetterQueueEncryption"
      actions   = ["kms:GenerateDataKey", "kms:Decrypt"]
      resources = [statement.value]
    }
  }
}

# WHY : Alternatives Considered: a separate aws_iam_role_policy rather than an
#       inline_policy block on the role. The pinned provider marks that block
#       deprecated, and the two forms also fight each other -- inline_policy
#       manages the complete set of inline policies on the role, so it removes
#       any policy attached by another means. A standalone resource keeps this
#       one policy independently addressable in state.
resource "aws_iam_role_policy" "this" {
  name   = local.role_name
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.permissions.json
}

# WHY : Assumptions: no tags argument appears below because the schedule has
#       none to set -- the pinned provider's schema exposes no tags attribute on
#       this resource, tagging being a group-level concept in this service. The
#       omission is recorded so it is not read as one, and attributability is
#       preserved by tagging the group, to which every schedule belongs exactly
#       once.
resource "aws_scheduler_schedule" "this" {
  name       = local.schedule_name
  group_name = aws_scheduler_schedule_group.this.name
  state      = var.schedule_state

  schedule_expression          = var.schedule_expression
  schedule_expression_timezone = var.schedule_expression_timezone
  kms_key_arn                  = var.kms_key_arn

  # WHY : Assumptions: forwarded exactly as given, including null. The baseline
  #       states the same kind of floor -- app/scheduler/CardDemo.ca7 line 39
  #       reads `. DONT SCHEDULE BEFORE 03237 AT 0000`, a bound on the earliest
  #       pickup that is separate from the recurring expression -- so the concept
  #       is carried across while the instant stays the caller's, because that
  #       Julian instant belongs to one historical cutover and baking any instant
  #       into a reusable module would impose it on every environment.
  start_date = var.start_date

  flexible_time_window {
    mode = var.flexible_time_window_mode

    # WHY : Assumptions: variables.tf validates the two inputs as one contract:
    #       OFF requires null, while FLEXIBLE requires a whole number from 1 to
    #       1440. This conditional is the resource-side translation of that
    #       contract, omitting the attribute entirely for OFF rather than sending
    #       a zero or a stale value and emitting it only for FLEXIBLE.
    maximum_window_in_minutes = var.flexible_time_window_mode == "FLEXIBLE" ? var.flexible_time_window_minutes : null
  }

  target {
    # WHY : Assumptions: the state machine's ARN is the target ARN directly.
    #       EventBridge Scheduler reaches Step Functions as a templated target,
    #       which was confirmed against the pinned provider's schema -- the
    #       target block offers parameter sub-blocks for ECS, EventBridge,
    #       Kinesis, SageMaker and SQS and none for Step Functions, because none
    #       is needed. The universal-target form, which would instead put an
    #       aws-sdk ARN here and move the machine into the payload, is therefore
    #       the wrong shape for this target and is not used.
    arn      = var.state_machine_arn
    role_arn = aws_iam_role.this.arn
    input    = local.target_input_json

    retry_policy {
      # WHY : Assumptions: five is preserved baseline behaviour rather than a
      #       round number -- app/scheduler/CardDemo.controlm carries
      #       MAXRERUN="5" on every one of its fifteen real job definitions --
      #       and the same depth is used for the dead-letter maxReceiveCount and
      #       for per-state retries, so a failure is retried to one consistent
      #       depth across the target instead of three unrelated ones. Both
      #       bounds are set explicitly because the platform's own defaults are
      #       far wider, and accepting them would keep re-delivering a failing
      #       trigger long past the point the baseline would have stopped and
      #       reported.
      maximum_retry_attempts       = var.maximum_retry_attempts
      maximum_event_age_in_seconds = var.maximum_event_age_in_seconds
    }

    # WHY : Assumptions: present unconditionally, never optional. An invocation
    #       that exhausts the retries above has nowhere else to go: EventBridge
    #       Scheduler does not retain it, so with no dead-letter target the
    #       night's chain silently never starts and no artifact records that it
    #       should have. Capturing the failed invocation is what makes the
    #       absence detectable at all, which is why it is not exposed as
    #       something a caller can switch off -- only as a queue they must name.
    dead_letter_config {
      arn = var.dead_letter_arn
    }
  }

  # WHY : Assumptions: the schedule references the role's ARN but not its
  #       policy, so nothing would otherwise order the two. Terraform would be
  #       free to create the schedule while the policy is still in flight, and a
  #       schedule whose expression came due inside that gap would fire without
  #       permission to start anything -- landing a failed invocation in the
  #       dead-letter queue for a deployment-ordering reason rather than a real
  #       one. Declaring the edge costs nothing and removes the window.
  depends_on = [aws_iam_role_policy.this]
}

# =============================================================================
# Deliberately not declared here
# -----------------------------------------------------------------------------
# Each omission below is a decision, not an oversight, and is recorded so that a
# reader looking for the resource finds the reason instead of a gap. The operator
# quiesce bracket and the retired wait step are covered in the header above.
#
#   - No aws_cloudwatch_event_rule. Alternatives Considered: a scheduled
#     EventBridge Rule would also start a state machine on a cron expression,
#     and it was rejected because it does not carry the fields this module
#     depends on. EventBridge Scheduler gives a schedule group to scope
#     permissions against, a per-schedule execution role, an explicit
#     evaluation timezone, a start instant, a flexible-window setting and a
#     per-target dead-letter configuration as first-class arguments; reaching
#     the same posture with a Rule means bolting several of them on elsewhere,
#     and the dead-letter target is required rather than nice to have.
#
#   - No self-managed scheduler: no cron container, no host crond, no hosted
#     equivalent of the retired CA-7 or Control-M definitions. Trade-offs: a
#     self-managed scheduler is the only option that could reproduce those
#     definitions' syntax, and what it costs is a component to patch, monitor
#     and make highly available in order to fire one nightly trigger. The intent
#     is preserved; the machinery is not.
#
#   - No Lambda function between the schedule and the state machine.
#     Alternatives Considered: a shim is the habitual shape and buys nothing
#     here, because Scheduler calls StartExecution itself. It would add a second
#     execution role, a second failure mode and a second place for the payload
#     to be rewritten, while removing none of the above.
#
#   - No second schedule for the weekly or monthly cadences. Assumptions: only
#     the nightly chain is specified, and the baseline does not contain what a
#     monthly schedule would need. Its five MONTHLY-InterestCalculation job
#     nodes carry no day-of-month attribute at all, and the file declares no
#     calendar of any kind, so any specific day chosen here would be invented
#     rather than migrated. A caller needing another cadence instantiates this
#     module again with its own expression.
#
#   - No provider, backend or terraform block, and no call to a sibling module.
#     Assumptions: this is a reusable module rather than a root. Provider
#     configuration and state belong to the calling environment root, and
#     declaring a provider here would break both the ability to pass an aliased
#     provider in and the ability to instantiate the module more than once. The
#     version constraints live in versions.tf.
#
#   - No log group, metric alarm or dashboard. Assumptions: observability is
#     owned by infra/modules/observability, so that alarm thresholds and
#     retention are set once for the whole environment rather than differently
#     inside each module that happens to emit something.
#
#   - No end_date, description or action_after_completion on the schedule, and
#     no additional context attributes in the payload. Assumptions: no input
#     governs them, and the last of these would be actively harmful --
#     action_after_completion set to DELETE removes the schedule once it has
#     run, which for a recurring nightly trigger means it fires once and then
#     does not exist. The remaining context attributes the service offers stay
#     available to a caller through target_input, and the escaping fix in the
#     locals above applies to those the same way it applies to this module's own.
# =============================================================================
