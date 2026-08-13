# =============================================================================
# infra/modules/step-functions-batch/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Publishes all four workflow entry points, their per-machine execution roles,
#   the four execution-log groups and the out-of-execution bracket watchdog rule
#   so scheduler, reporting, observability and environment-root IAM wiring can
#   consume exact resource identities.
#
# Parameters:
#   None. Every value is derived from resources in main.tf.
#
# Return values:
#   Non-sensitive outputs only: ARN and name for each state machine, a map of
#   execution-role ARNs and a map of execution-role names keyed by machine, ARN
#   and name for each log group, and ARN and name for the bracket watchdog rule.
#
# Exceptions or errors:
#   No output is available until its referenced resource is created. Terraform
#   therefore withholds the corresponding value if a state machine, role or log
#   group fails rather than publishing a guessed identifier.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: four outputs were added for the operator-invoked
#     dataset round-trip machine, which is new. Two batch jobs -- export and
#     import -- had beans registered under tokens no state machine, schedule or
#     API could pass, so nothing in this repository could run either one in a
#     provisioned environment; main.tf now carries a third machine for them and
#     its identity is published here on the same terms as the other two.
#   - Assumptions: both ARN and name are published because consumers need
#     different shapes. IAM and Scheduler require ARNs; dashboards, log queries
#     and operator output use stable names.
#   - Trade-offs: the encoded ASL definitions and inline role policy are not
#     outputs. Publishing either would duplicate large implementation detail in
#     root plans without giving a consumer a supported integration contract.
# =============================================================================

output "daily_state_machine_arn" {
  description = "ARN of the twelve-work-state daily batch machine. The EventBridge Scheduler module targets this value and observability scopes the batch-failure alarm to it."
  value       = aws_sfn_state_machine.daily.arn
}

output "daily_state_machine_name" {
  description = "Name of the daily batch machine, used in operator commands, execution-history queries and dashboard dimensions."
  value       = aws_sfn_state_machine.daily.name
}

output "adhoc_report_state_machine_arn" {
  description = "ARN of the ad-hoc report machine. The environment root publishes it to reporting-service and grants that service states:StartExecution on this exact resource."
  value       = aws_sfn_state_machine.adhoc.arn
}

output "adhoc_report_state_machine_name" {
  description = "Name of the ad-hoc report machine, used in execution-history queries and report-operations diagnostics."
  value       = aws_sfn_state_machine.adhoc.name
}

# WHY : Refactoring Rationale: these two were scalars naming one shared execution
#       role. They are maps keyed by machine -- daily, adhoc, dataset, authz --
#       because each machine now holds its own role scoped to the task definitions,
#       pass-roles and functions its own definition references. A scalar would have
#       had to pick one of the four and would have made the other three invisible to
#       the IAM inventory these outputs exist for.
output "execution_role_arns" {
  description = "ARN of each state machine's execution role, keyed by machine (daily, adhoc, dataset, authz), for IAM inventory and policy auditing by the environment root."
  value       = { for key, role in aws_iam_role.this : key => role.arn }
}

output "execution_role_names" {
  description = "Name of each state machine's execution role, keyed by machine (daily, adhoc, dataset, authz), used by operator and compliance queries that address IAM roles by name."
  value       = { for key, role in aws_iam_role.this : key => role.name }
}

output "daily_log_group_arn" {
  description = "ARN of the encrypted CloudWatch log group receiving daily-machine execution events."
  value       = aws_cloudwatch_log_group.daily.arn
}

output "daily_log_group_name" {
  description = "Name of the CloudWatch log group receiving daily-machine execution events, for observability dashboards and log queries."
  value       = aws_cloudwatch_log_group.daily.name
}

output "adhoc_report_log_group_arn" {
  description = "ARN of the encrypted CloudWatch log group receiving ad-hoc report execution events."
  value       = aws_cloudwatch_log_group.adhoc.arn
}

output "adhoc_report_log_group_name" {
  description = "Name of the CloudWatch log group receiving ad-hoc report execution events, for report-operations dashboards and log queries."
  value       = aws_cloudwatch_log_group.adhoc.name
}

# WHY : Assumptions: the finalizer rule is published because it is the only part
#       of the quiesce bracket that lives outside the execution, so nothing in an
#       execution history reveals it. An operator asking why the read-only flag
#       cleared without a resume state having run, and an alarm on the rule's
#       failed-invocation metric, both need to address it by identity rather
#       than by reading this module. A FailedInvocations count on this rule is the
#       one signal that the bracket was left set -- the condition the rule exists to
#       prevent -- so exporting the name and the ARN lets the observability root
#       attach that alarm without reaching into this module's internals.
output "bracket_finalizer_rule_arn" {
  description = "ARN of the EventBridge rule that releases the online write quiesce bracket when a daily execution terminates without having released it in-graph. Consumers scope failed-invocation alarms to this exact rule."
  value       = aws_cloudwatch_event_rule.daily_finalizer.arn
}

output "bracket_finalizer_rule_name" {
  description = "Name of the bracket finalizer rule, used in operator diagnostics and CloudWatch metric dimensions when explaining a resume that no state in the execution history performed."
  value       = aws_cloudwatch_event_rule.daily_finalizer.name
}

# WHY : Refactoring Rationale: the paragraph above said an alarm on the finalizer's
#       FailedInvocations metric was left to "the observability root" to attach. No
#       root attached one, and a signal nobody owns is a signal nobody receives, so
#       this module now declares that alarm and the two beside it. These three
#       outputs exist so the root can still see what was created -- an alarm
#       inventory is a compliance artifact -- without being responsible for it.
# WHY : Assumptions: the reconciler rule is published on the same terms as the
#       finalizer rule. It is the other half of the out-of-execution release, and an
#       operator reading a flag that cleared with no resume state in the history
#       needs to be able to tell which of the two cleared it.
output "bracket_reconciler_rule_arn" {
  description = "ARN of the scheduled EventBridge rule that asks the resume function to release a quiesce bracket no event released, once its owning execution and tasks are terminal."
  value       = aws_cloudwatch_event_rule.bracket_reconciler.arn
}

output "bracket_reconciler_rule_name" {
  description = "Name of the bracket reconciler rule, used in operator diagnostics and CloudWatch metric dimensions when explaining a resume that no state in the execution history performed."
  value       = aws_cloudwatch_event_rule.bracket_reconciler.name
}

output "bracket_release_dead_letter_queue_arn" {
  description = "ARN of the queue retaining bracket-release invocations that EventBridge could not deliver. Operators read it to find which execution's bracket was never released; nothing consumes it automatically."
  value       = aws_sqs_queue.bracket_release_dlq.arn
}

output "bracket_release_dead_letter_queue_url" {
  description = "URL of the bracket-release dead-letter queue, for the receive-message and purge commands the batch-operations runbook publishes."
  value       = aws_sqs_queue.bracket_release_dlq.url
}

# WHY : Assumptions: the staging root is published because an operator has to PUT the
#       seed extracts where the nightly staging branch will look for them, and the only
#       authority on that location is the composition this module performs. Publishing
#       the prefix as well as the whole URI is what lets the data-migration runbook's
#       upload command build a key without parsing a URI in shell.
# WHY : Trade-offs: publishing the resolved root rather than only the inputs means an
#       operator reading it sees the effect of the optional override, which is the value
#       the tasks actually receive. Reading the two inputs back instead would report the
#       composed default even when an override was in force.
output "dataset_source_extract_prefix" {
  description = "Key prefix within the dataset bucket that the seed extracts must be uploaded to, flat and under the names the seed-dataset registry records."
  value       = var.dataset_source_extract_prefix
}

output "dataset_staging_root" {
  description = "Resolved location the data-migration container reads seed extracts from, as passed to every staging and load task in CARDDEMO_DATASET_STAGING_ROOT. Either an s3://bucket/prefix URI or, when overridden, an absolute filesystem path."
  value       = local.dataset_staging_root
}

output "bracket_release_alarm_names" {
  description = "Names of every alarm guarding the out-of-execution bracket release: one per release rule's failed invocations, one for the resume function's errors and one for the dead-letter queue's depth."
  value = concat(
    [for alarm in aws_cloudwatch_metric_alarm.release_delivery_failed : alarm.alarm_name],
    [
      aws_cloudwatch_metric_alarm.release_function_errors.alarm_name,
      aws_cloudwatch_metric_alarm.release_dead_letters.alarm_name,
    ],
  )
}

# WHY : Assumptions: the round-trip machine's identity is published for the same
#       two consumers the ad-hoc machine's is -- an operator or automation calling
#       states:StartExecution, and a root granting that call on exactly this
#       resource. Without an output the only route to the ARN is to compose it from
#       the name prefix and environment, which is the deterministic composition the
#       reporting cycle already forces elsewhere and which every other consumer
#       should be spared.
output "dataset_roundtrip_state_machine_arn" {
  description = "ARN of the operator-invoked dataset export/import round-trip machine. An operator or automation starts it with a single `businessDate` input; the environment root grants states:StartExecution on exactly this resource to any principal that needs it."
  value       = aws_sfn_state_machine.dataset_roundtrip.arn
}

output "dataset_roundtrip_state_machine_name" {
  description = "Name of the dataset round-trip state machine, for a console link or a CLI invocation that addresses it by name."
  value       = aws_sfn_state_machine.dataset_roundtrip.name
}

output "dataset_roundtrip_log_group_arn" {
  description = "ARN of the CloudWatch log group the dataset round-trip machine writes its execution history to. Published so a root can attach a subscription or a metric filter without reaching into the module."
  value       = aws_cloudwatch_log_group.dataset_roundtrip.arn
}

output "dataset_roundtrip_log_group_name" {
  description = "Name of the CloudWatch log group the dataset round-trip machine writes to, for a console link or a logs query."
  value       = aws_cloudwatch_log_group.dataset_roundtrip.name
}

# Assumptions: published so the environment root can print the machine an operator
#   invokes and so the runbook can name it without an operator having to construct it
#   from a naming convention.
output "authorization_extract_state_machine_arn" {
  description = "ARN of the operator-invoked authorization-extract state machine, which runs the pending-authorization segment export and the extract load."
  value       = aws_sfn_state_machine.authorization_extract.arn
}

output "authorization_extract_state_machine_name" {
  description = "Name of the operator-invoked authorization-extract state machine, which is what an operator passes to start-execution."
  value       = aws_sfn_state_machine.authorization_extract.name
}

output "authorization_extract_log_group_arn" {
  description = "ARN of the log group the authorization-extract state machine writes its execution history to."
  value       = aws_cloudwatch_log_group.authorization_extract.arn
}

output "authorization_extract_log_group_name" {
  description = "Name of the log group the authorization-extract state machine writes its execution history to."
  value       = aws_cloudwatch_log_group.authorization_extract.name
}
