# =============================================================================
# infra/modules/step-functions-batch/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Publishes both workflow entry points, their shared execution role, the two
#   execution-log groups and the out-of-execution bracket watchdog rule so
#   scheduler, reporting, observability and environment-root IAM wiring can
#   consume exact resource identities.
#
# Parameters:
#   None. Every value is derived from resources in main.tf.
#
# Return values:
#   Twelve non-sensitive outputs: ARN and name for each state machine, ARN and
#   name for the execution role, ARN and name for each log group, and ARN and
#   name for the bracket watchdog rule.
#
# Exceptions or errors:
#   No output is available until its referenced resource is created. Terraform
#   therefore withholds the corresponding value if a state machine, role or log
#   group fails rather than publishing a guessed identifier.
#
# WHY (non-obvious design decisions):
#   - Assumptions: both ARN and name are published because consumers need
#     different shapes. IAM and Scheduler require ARNs; dashboards, log queries
#     and operator output use stable names.
#   - Trade-offs: the encoded ASL definitions and inline role policy are not
#     outputs. Publishing either would duplicate large implementation detail in
#     root plans without giving a consumer a supported integration contract.
# =============================================================================

output "daily_state_machine_arn" {
  description = "ARN of the eleven-work-state daily batch machine. The EventBridge Scheduler module targets this value and observability scopes the batch-failure alarm to it."
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

output "execution_role_arn" {
  description = "ARN of the shared Step Functions execution role, for IAM inventory and policy auditing by the environment root."
  value       = aws_iam_role.this.arn
}

output "execution_role_name" {
  description = "Name of the shared Step Functions execution role, used by operator and compliance queries that address IAM roles by name."
  value       = aws_iam_role.this.name
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
