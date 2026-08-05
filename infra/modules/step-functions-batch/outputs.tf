# =============================================================================
# infra/modules/step-functions-batch/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Publishes both workflow entry points, their shared execution role and the
#   two execution-log groups so scheduler, reporting, observability and
#   environment-root IAM wiring can consume exact resource identities.
#
# Parameters:
#   None. Every value is derived from resources in main.tf.
#
# Return values:
#   Ten non-sensitive outputs: ARN and name for each state machine, ARN and name
#   for the execution role, and ARN and name for each log group.
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
