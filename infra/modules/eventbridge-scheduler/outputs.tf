# =============================================================================
# infra/modules/eventbridge-scheduler/outputs.tf
# -----------------------------------------------------------------------------
# Purpose: publishes the nightly schedule's name and ARN, its group name, and
#   the execution-role ARN as this module's complete outward contract.
# Alternatives Considered: exporting consumed inputs or whole resource objects
#   would duplicate another module's authority or couple callers to provider
#   schema details. Each output therefore names only an object created here.
# Trade-offs: these identifiers are intentionally not sensitive. They confer no
#   permission, and keeping them visible lets operators locate and audit the
#   trigger through Terraform output without exposing credential material.
# Assumptions: values are read from their owning resources rather than composed
#   locals so downstream references retain an explicit creation dependency.
# =============================================================================

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
