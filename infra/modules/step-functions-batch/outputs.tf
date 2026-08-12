# =============================================================================
# infra/modules/step-functions-batch/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Publishes all three workflow entry points, their shared execution role, the
#   three execution-log groups and the out-of-execution bracket watchdog rule so
#   scheduler, reporting, observability and environment-root IAM wiring can
#   consume exact resource identities.
#
# Parameters:
#   None. Every value is derived from resources in main.tf.
#
# Return values:
#   Sixteen non-sensitive outputs: ARN and name for each of the three state
#   machines, ARN and name for the execution role, ARN and name for each of the
#   three log groups, and ARN and name for the bracket watchdog rule.
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
