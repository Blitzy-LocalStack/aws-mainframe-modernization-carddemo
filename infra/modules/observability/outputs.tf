# =============================================================================
# infra/modules/observability/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Publishes the observability resources that environment roots wire into
#   producers and runbooks: the alert topic, shared access-log bucket, managed
#   log groups, dashboard and alarm identities.
#
# Parameters:
#   None. variables.tf is the input surface; every value below is read from a
#   resource or a stable collection created in main.tf.
#
# Return values:
#   Identifiers are published in the form their consumers require. Bucket and
#   log-group names configure producers, while ARNs scope IAM or compose further
#   notification policy. The alarm map is keyed by signal and producer so adding
#   an alarm does not re-index existing callers.
#
# Exceptions or errors:
#   A resource-attribute typo fails terraform validate in this module. A changed
#   map key is a breaking contract for callers even when the underlying resource
#   is unchanged, so the logical keys below are treated as stable API names.
#
# WHY (non-obvious design decisions):
#   - Assumptions: none of these identifiers is sensitive. Possessing an ARN or
#     resource name grants no access; redaction would hide root wiring in plans
#     without protecting key material or credentials.
#   - Trade-offs: names and ARNs are both published where consumers need both.
#     Reconstructing an ARN from a name would force account and region string
#     assembly back into the environment roots.
#   - Alternatives Considered: input values such as the KMS key and retention
#     period are not echoed. The caller already owns them and republishing them
#     would misstate this module as their authority.
# =============================================================================

output "notification_topic_arn" {
  description = "ARN of the single per-environment alert topic. Pass it to step-functions-batch as its failure-publication target and use it as the action target for any alarm authored outside this module."
  value       = aws_sns_topic.alerts.arn
}

output "notification_topic_name" {
  description = "Name of the alert topic for operator lookup and CloudWatch/SNS diagnostics where a name, rather than an IAM resource ARN, is required."
  value       = aws_sns_topic.alerts.name
}

output "access_log_bucket_name" {
  description = "Name of the shared terminal access-log bucket. Pass it to alb.access_logs_bucket and s3-datasets.access_log_bucket_name so both producers write into a destination whose policies this module owns."
  value       = aws_s3_bucket.access_logs.bucket
}

output "access_log_bucket_arn" {
  description = "ARN of the shared access-log bucket for IAM policy composition and audit inventory. Producers take the bucket name output instead; IAM Resource elements take this ARN."
  value       = aws_s3_bucket.access_logs.arn
}

output "managed_log_group_names" {
  description = "Map of logical producer key to exact CloudWatch log-group name for groups this module creates. Use the name in a Lambda or collector logging configuration; keys match the input map and remain stable when a path changes."
  value = {
    for key, group in aws_cloudwatch_log_group.managed : key => group.name
  }
}

output "managed_log_group_arns" {
  description = "Map of the same logical producer keys to CloudWatch log-group ARNs. Use these values to scope logs:CreateLogStream and logs:PutLogEvents without granting access to every group in the account."
  value = {
    for key, group in aws_cloudwatch_log_group.managed : key => trimsuffix(group.arn, ":*")
  }
}

output "dashboard_name" {
  description = "Name of the operations dashboard for runbook deep-links and console lookup after deployment."
  value       = aws_cloudwatch_dashboard.operations.dashboard_name
}

output "dashboard_arn" {
  description = "ARN of the operations dashboard for IAM policies that grant a read-only operator access to this dashboard without granting access to every dashboard."
  value       = aws_cloudwatch_dashboard.operations.dashboard_arn
}

output "alarm_arns" {
  description = "Map of stable signal keys to alarm ARNs. A root may use these values to compose a composite alarm or attach an additional action without discovering alarms by name."
  value = merge(
    {
      for service, alarm in aws_cloudwatch_metric_alarm.service_unhealthy :
      "service_unhealthy/${service}" => alarm.arn
    },
    {
      for service, alarm in aws_cloudwatch_metric_alarm.service_5xx :
      "service_5xx/${service}" => alarm.arn
    },
    {
      api_5xx = aws_cloudwatch_metric_alarm.api_5xx.arn
    },
    {
      for queue, alarm in aws_cloudwatch_metric_alarm.dead_letter_depth :
      "dead_letter_depth/${queue}" => alarm.arn
    },
    {
      for queue, alarm in aws_cloudwatch_metric_alarm.reply_queue_age :
      "reply_queue_age/${queue}" => alarm.arn
    },
    {
      for outcome, alarm in aws_cloudwatch_metric_alarm.batch_failure :
      "batch_${outcome}" => alarm.arn
    },
    {
      aurora_cpu      = aws_cloudwatch_metric_alarm.aurora_cpu.arn
      aurora_capacity = aws_cloudwatch_metric_alarm.aurora_capacity.arn
    },
  )
}
