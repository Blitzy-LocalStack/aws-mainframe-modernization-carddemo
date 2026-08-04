# =============================================================================
# infra/modules/eventbridge-scheduler/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The public contract of the `eventbridge-scheduler` module -- the four values
#   an environment root can see, and the whole of what it can wire onward. A
#   caller cannot reach inside a module to read a resource, so this file is not
#   a convenience layer over main.tf: it IS the module's visible surface, and an
#   attribute not named below does not exist as far as infra/envs/dev and
#   infra/envs/prod are concerned. What it names is the nightly trigger for the
#   migrated batch chain -- the schedule that starts the `carddemo-daily-batch`
#   state machine, the group that contains it, and the role it assumes.
#
# Parameters:
#   None. This file declares no `variable`; the module's sixteen inputs live in
#   variables.tf. Values flow strictly one way here -- outward to the calling
#   root, never back into a sibling module -- because this module calls no
#   sibling and the two ARNs it consumes arrive as inputs rather than as
#   references.
#
# Return values:
#   Four, each a string, each read from a resource declared in main.tf:
#     schedule_name        name of the schedule.
#     schedule_arn         ARN of the same schedule.
#     schedule_group_name  name of the group that schedule belongs to.
#     scheduler_role_arn   ARN of the role the schedule assumes.
#   Every one carries a `description`, which under this language's documentation
#   mapping is the only place a return value's meaning can be stated at all --
#   HCL has no docstring, and an `output` is a return value consumed across a
#   directory boundary by a caller that cannot see the resource behind it. Two
#   gates depend on that being more than a formality: infra/.tflint.hcl enables
#   terraform_documented_outputs and does not tolerate a finding, and
#   infra/.terraform-docs.yml generates this module's README Outputs table from
#   these descriptions with `read-comments: false`, so the description attribute
#   is the sole source for that table and a comment above an output would not be
#   harvested in its place.
#
# Errors:
#   None, and the absence is structural rather than an omission. Every value
#   below is a direct attribute read on a resource this module declares -- no
#   expression, no type conversion, no lookup into a value a caller supplied --
#   so there is nothing here that can fail at plan time and consequently no
#   `precondition` to write. All four attributes were verified to exist on the
#   pinned provider (`hashicorp/aws ~> 6.56`, exercised at 6.57.1) by reading
#   its resource schemas rather than by assuming the names, because a
#   mistyped attribute path in this file is invisible until `terraform validate`
#   runs against a root that calls the module.
# =============================================================================

# -----------------------------------------------------------------------------
# The four published values.
#
# The three decisions governing this set -- which values to publish, whether to
# mark them sensitive, and where to read each one from -- apply to all four
# outputs rather than to any one of them, so all three are recorded here,
# immediately above the set they govern rather than repeated four times inside it.
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: publishing more than these four, in either of
#       the two shapes that suggest themselves, and both are rejected. Exporting
#       a whole resource object would hand a caller every attribute the provider
#       happens to compute, which makes this module's contract identical to
#       whatever the provider's schema is on the day it is read -- so a provider
#       upgrade that renames or drops an attribute silently becomes a breaking
#       change to a contract this module never chose to offer. Echoing an input
#       back is the second shape and is worse for being the more tempting:
#       re-emitting `state_machine_arn` or `dead_letter_arn` as an output would
#       let a root read either from here instead of from the module that owns it,
#       and those two values are owned by infra/modules/step-functions-batch and
#       infra/modules/sqs, which already publish them. One value with two
#       sources of truth is not redundancy, because nothing holds the copies in
#       agreement and a root wiring one of them cannot tell which it got. The
#       rule applied instead is that a module publishes what it CREATES and
#       nothing it merely consumes, which fixes the set at exactly the schedule's
#       name and ARN, its group's name and its role's ARN. The asymmetry in what
#       a fifth output would cost is the point: it is a promise two environment
#       roots must keep honouring for as long as they exist, and withdrawing one
#       later is a breaking change, so the set is kept at what a caller
#       demonstrably needs rather than at what could plausibly be useful.
#
# WHY : Trade-offs: not one of the four is marked `sensitive`, and leaving the
#       flag off is a decision here rather than a default nobody revisited. All
#       four are identifiers -- two names and two ARNs -- and an identifier
#       confers nothing on whoever reads it: knowing a role's ARN does not
#       permit assuming that role, and knowing a schedule's name does not permit
#       disabling it. Against that, the cost of the flag is concrete and lands
#       on the operator. It redacts the value from `terraform output`, which is
#       exactly where docs/runbooks/batch-operations.md sends someone who has to
#       locate, pause or resume the trigger; and the marking propagates, because
#       a root that re-exports a sensitive value must mark its own output
#       sensitive too or have the plan rejected -- so one flag set here would
#       impose redaction and extra handling on both environment roots in
#       exchange for concealing nothing worth concealing. The module's inputs are
#       worth contrasting deliberately: `dead_letter_kms_key_arn` and
#       `kms_key_arn` name encryption keys and are likewise not secrets, since a
#       key ARN is a reference and not key material. So no credential crosses
#       this module boundary in either direction, and that is by construction
#       rather than by luck -- every generated credential in this target is
#       written to Secrets Manager at apply time precisely because a Terraform
#       output is recorded in state, and state is plaintext.
#
# WHY : Alternatives Considered: each value is read from the resource that owns
#       it rather than from the `local` main.tf composed the name in. Those
#       locals are in scope here and would render the same two names, so the
#       choice is not forced -- what separates them is the dependency edge. A
#       local built only from input variables resolves without reference to any
#       resource, so an output carrying one could be answered before, or
#       without, the schedule and its group actually existing, and a root
#       wiring that value into an alarm or a resource policy would be ordered
#       against nothing. Reading the attribute makes each output depend on its
#       resource, so every consumer is ordered after creation, and it reports
#       what the service accepted rather than what this module asked for should
#       those ever diverge. Note that main.tf makes the opposite call for one
#       specific value -- it composes the schedule-group ARN instead of reading
#       it back -- and records its own reason there: that value feeds the role's
#       trust policy, where being fully known in the reviewed plan outweighs the
#       edge. The two decisions are consistent rather than contradictory, each
#       taking whichever property matters at its own point of use.

output "schedule_name" {
  description = "Name of the nightly EventBridge Scheduler schedule that starts the `carddemo-daily-batch` Step Functions state machine on the cron expression supplied to this module. This is the handle an operator uses to locate that trigger, and the one to name when disabling or re-enabling it."
  value       = aws_scheduler_schedule.this.name
}

output "schedule_arn" {
  description = "ARN of that same schedule, for referring to it from outside this module: a dashboard or alarm identifies a schedule by ARN rather than by name, and a runbook step quotes it to establish which environment's trigger an execution came from."
  value       = aws_scheduler_schedule.this.arn
}

output "schedule_group_name" {
  description = "Name of the schedule group the schedule belongs to. The group is functional and not organisational: its ARN is the value the role's trust policy matches on `aws:SourceArn`, so the group is what bounds which schedules may assume that role at all. It is also where this module's tags land, because the schedule resource itself accepts none."
  value       = aws_scheduler_schedule_group.this.name
}

output "scheduler_role_arn" {
  description = "ARN of the execution role EventBridge Scheduler assumes to act for this schedule. It is permitted to call `states:StartExecution` on the one state machine supplied and `sqs:SendMessage` on the one dead-letter queue supplied, plus the two data-key operations a CMK-encrypted queue needs when one is named, and nothing further. Published so a root can reference the trigger's identity and audit its effective privilege without reading the module."
  value       = aws_iam_role.this.arn
}
