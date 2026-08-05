# =============================================================================
# infra/modules/observability/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions the cross-cutting operational surface for CardDemo: explicitly
#   retained and customer-key-encrypted log groups, a shared access-log
#   destination, one encrypted notification topic, a CloudWatch dashboard, and
#   metric alarms over the exact identifiers supplied by the environment root.
#
# Parameters:
#   variables.tf owns the complete input contract. Naming, retention and alarm
#   policy are module settings; cluster, load-balancer, queue, database, state
#   machine and log-group identifiers are producer outputs passed by the root.
#
# Return values:
#   outputs.tf publishes the topic, access-log bucket, managed log groups,
#   dashboard, alarms and trace configuration so roots can wire producers and
#   operator runbooks without reconstructing an ARN or resource name.
#
# Exceptions or errors:
#   Input validation rejects malformed metric dimensions before any resource is
#   evaluated. A duplicate log-group name or access-log bucket name is an
#   apply-time ownership conflict, which is why this module creates only groups
#   explicitly listed by the root and never recreates groups owned by ECS, API
#   Gateway, Step Functions or the network module.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the baseline already centralises structured error
#     emission and routes job output to operator-readable spool destinations.
#     The target preserves those concepts while making records queryable across
#     services and making a failure publish instead of waiting to be read.
#   - Assumptions: every monitored identifier arrives through the environment
#     root. Deriving sibling names here would duplicate their naming rules and
#     a rename would produce a quiet alarm rather than a Terraform error.
#   - Trade-offs: one topic serves the whole environment. Subscription and mute
#     policy are therefore changed once, while alarm names and descriptions
#     retain the service and signal that produced each notification.
#   - Alternatives Considered: ECS, API Gateway, Step Functions and VPC flow-log
#     groups stay with the modules that own those producer lifecycles. Creating
#     duplicates here would either collide during apply or leave an empty group
#     that appears to show a silent producer.
#   - Alternatives Considered: no self-managed Prometheus or Grafana service is
#     created. ECS Container Insights and native AWS service metrics feed this
#     dashboard without adding two stateful services to patch and scale.
# =============================================================================

data "aws_partition" "current" {}
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  name_stem = "${var.name_prefix}-${var.environment}"

  # WHY : Assumptions: the account identifier makes the bucket globally unique
  #       without committing one to source, while omitting the region keeps the
  #       name inside S3's 63-character ceiling at the maximum permitted prefix.
  access_log_bucket_name = "${local.name_stem}-logs-${data.aws_caller_identity.current.account_id}"

  # WHY : Assumptions: logical keys, rather than queue-name string matching,
  #       identify queue roles. Queue names may change without silently moving
  #       an alarm from a dead-letter queue to a live queue or vice versa.
  dlq_queue_names = {
    for key, name in var.queue_names : key => name
    if endswith(key, "_dlq")
  }

  reply_queue_names = {
    for key, name in var.queue_names : key => name
    if endswith(key, "_reply")
  }

  batch_failure_metrics = {
    failed    = "ExecutionsFailed"
    timed_out = "ExecutionsTimedOut"
  }

  tags = merge(var.tags, {
    Module      = "observability"
    Environment = var.environment
  })

  account_root_arn = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"
  alarm_arn_prefix = "arn:${data.aws_partition.current.partition}:cloudwatch:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:alarm:${local.name_stem}-"
}

# -----------------------------------------------------------------------------
# Log groups owned by this module
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "managed" {
  for_each = var.log_group_names

  name              = each.value
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn

  # WHY : Refactoring Rationale: retention is explicit rather than inheriting
  #       the unlimited service default. In the baseline 29 of 38 jobs use
  #       MSGCLASS=0, so log survival was a per-job spool-class side effect;
  #       here the environment chooses one reviewable retention period.
  # WHY : Assumptions: the KMS policy grants the regional CloudWatch Logs
  #       service principal use of this key with an account-and-region-scoped
  #       encryption context. Without that grant the resource can be created
  #       but the producer cannot write an encrypted event.
  tags = merge(local.tags, {
    Name     = each.value
    Producer = each.key
  })
}

# -----------------------------------------------------------------------------
# One encrypted notification topic per environment
# -----------------------------------------------------------------------------

resource "aws_sns_topic" "alerts" {
  name              = "${local.name_stem}-alerts"
  kms_master_key_id = var.kms_key_arn

  # WHY : Assumptions: active tracing lets a Step Functions failure publication
  #       remain connected to the execution that produced it. It records no
  #       message body in this module and changes no delivery semantics.
  tracing_config = "Active"

  tags = merge(local.tags, {
    Name = "${local.name_stem}-alerts"
  })
}

data "aws_iam_policy_document" "alerts" {
  statement {
    sid    = "AllowAccountAdministration"
    effect = "Allow"
    actions = [
      "sns:AddPermission",
      "sns:DeleteTopic",
      "sns:GetTopicAttributes",
      "sns:ListSubscriptionsByTopic",
      "sns:Publish",
      "sns:Receive",
      "sns:RemovePermission",
      "sns:SetTopicAttributes",
      "sns:Subscribe",
    ]
    resources = [aws_sns_topic.alerts.arn]

    principals {
      type        = "AWS"
      identifiers = [local.account_root_arn]
    }
  }

  statement {
    sid       = "AllowCloudWatchAlarmPublication"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alerts.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.${data.aws_partition.current.dns_suffix}"]
    }

    # WHY : Assumptions: a service principal represents every account the
    #       service acts for. The account and alarm-prefix conditions confine
    #       publication to alarms this environment creates instead of allowing
    #       another account or an unrelated alarm to inject a false alert.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["${local.alarm_arn_prefix}*"]
    }
  }
}

resource "aws_sns_topic_policy" "alerts" {
  arn    = aws_sns_topic.alerts.arn
  policy = data.aws_iam_policy_document.alerts.json
}

resource "aws_sns_topic_subscription" "email" {
  for_each = toset(var.alarm_email_endpoints)

  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = each.value
}

# -----------------------------------------------------------------------------
# Shared ALB and S3 server-access-log destination
# -----------------------------------------------------------------------------

#checkov:skip=CKV_AWS_18:This bucket is the terminal access-log destination; sending its own server-access logs back to itself creates a recursive write stream.
resource "aws_s3_bucket" "access_logs" {
  bucket        = local.access_log_bucket_name
  force_destroy = var.access_log_bucket_force_destroy

  tags = merge(local.tags, {
    Name = local.access_log_bucket_name
  })
}

resource "aws_s3_bucket_public_access_block" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    # WHY : Assumptions: both delivery services use bucket-owner-full-control
    #       semantics. Enforcing ownership keeps every delivered object under
    #       this account even though a service principal performed the write.
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  #checkov:skip=CKV_AWS_145:Application Load Balancer access-log delivery supports SSE-S3 on its destination; forcing SSE-KMS prevents delivery and produces an empty audit bucket.
  bucket = aws_s3_bucket.access_logs.id

  rule {
    apply_server_side_encryption_by_default {
      # WHY : Trade-offs: this terminal access-log bucket uses SSE-S3 rather
      #       than the module's KMS key because ALB log delivery must be able to
      #       write it. The application datasets, service logs and alert topic
      #       remain customer-key encrypted; the exception is confined to this
      #       delivery destination and recorded beside the argument.
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    id     = "expire-access-logs"
    status = "Enabled"

    filter {}

    # WHY : Assumptions: one retention input governs both CloudWatch events and
    #       access-log objects so an environment has one audit-retention choice
    #       rather than two values that can drift without a policy distinction.
    expiration {
      days = var.log_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.access_logs]
}

data "aws_iam_policy_document" "access_logs" {
  statement {
    sid    = "DenyNonTlsRequests"
    effect = "Deny"

    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.access_logs.arn,
      "${aws_s3_bucket.access_logs.arn}/*",
    ]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  statement {
    sid       = "AllowAlbLogDelivery"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs.arn}/*/AWSLogs/${data.aws_caller_identity.current.account_id}/*"]

    principals {
      type        = "Service"
      identifiers = ["logdelivery.elasticloadbalancing.${data.aws_partition.current.dns_suffix}"]
    }

    # WHY : Assumptions: the resource path confines objects to this account's
    #       AWSLogs segment, and SourceAccount prevents the regional delivery
    #       principal from writing another account's records into this bucket.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }

  statement {
    sid       = "AllowAlbBucketAclCheck"
    effect    = "Allow"
    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.access_logs.arn]

    principals {
      type        = "Service"
      identifiers = ["logdelivery.elasticloadbalancing.${data.aws_partition.current.dns_suffix}"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }

  statement {
    sid       = "AllowS3ServerAccessLogDelivery"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs.arn}/s3-access-logs/*"]

    principals {
      type        = "Service"
      identifiers = ["logging.s3.${data.aws_partition.current.dns_suffix}"]
    }

    # WHY : Assumptions: the source pattern admits only the CardDemo dataset
    #       bucket for this environment. It closes the destination without
    #       depending on that bucket's ARN, which would form a module cycle:
    #       datasets needs the destination name before its own ARN exists.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:s3:::${var.name_prefix}-datasets-${var.environment}-*"]
    }
  }
}

resource "aws_s3_bucket_policy" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  policy = data.aws_iam_policy_document.access_logs.json

  depends_on = [
    aws_s3_bucket_ownership_controls.access_logs,
    aws_s3_bucket_public_access_block.access_logs,
  ]
}

# -----------------------------------------------------------------------------
# Dashboard
# -----------------------------------------------------------------------------

locals {
  ecs_service_widgets = [
    for index, service in var.dashboard_service_names : {
      type   = "metric"
      x      = (index % 2) * 12
      y      = floor(index / 2) * 6
      width  = 12
      height = 6
      properties = {
        title  = "${service}: ECS tasks and utilisation"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Average"
        period = var.alarm_period_seconds
        metrics = [
          ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", var.ecs_cluster_name, "ServiceName", service],
          ["ECS/ContainerInsights", "DesiredTaskCount", "ClusterName", var.ecs_cluster_name, "ServiceName", service],
          ["ECS/ContainerInsights", "CpuUtilized", "ClusterName", var.ecs_cluster_name, "ServiceName", service],
          ["ECS/ContainerInsights", "MemoryUtilized", "ClusterName", var.ecs_cluster_name, "ServiceName", service],
        ]
      }
    }
  ]

  shared_widget_y = ceil(length(var.dashboard_service_names) / 2) * 6

  queue_depth_widget = length(var.queue_names) == 0 ? [] : [{
    type   = "metric"
    x      = 0
    y      = local.shared_widget_y
    width  = 12
    height = 6
    properties = {
      title  = "SQS visible messages"
      region = data.aws_region.current.region
      view   = "timeSeries"
      stat   = "Maximum"
      period = var.alarm_period_seconds
      metrics = [
        for key, name in var.queue_names :
        ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", name, { label = key }]
      ]
    }
  }]

  cloudfront_widget = var.cloudfront_distribution_id == null ? [] : [{
    type   = "metric"
    x      = 12
    y      = local.shared_widget_y + 12
    width  = 12
    height = 6
    properties = {
      title = "CloudFront requests and edge errors"

      # WHY : Assumptions: CloudFront publishes this metric family in the
      #       service's fixed metrics region. A dashboard widget can select that
      #       region independently; creating an alarm with the default provider
      #       would instead leave it permanently without datapoints.
      region = "us-east-1"
      view   = "timeSeries"
      stat   = "Sum"
      period = var.alarm_period_seconds
      metrics = [
        ["AWS/CloudFront", "Requests", "DistributionId", var.cloudfront_distribution_id, "Region", "Global"],
        ["AWS/CloudFront", "5xxErrorRate", "DistributionId", var.cloudfront_distribution_id, "Region", "Global", { stat = "Average" }],
      ]
    }
  }]

  dashboard_widgets = concat(
    local.ecs_service_widgets,
    local.queue_depth_widget,
    [
      {
        type   = "metric"
        x      = 12
        y      = local.shared_widget_y
        width  = 12
        height = 6
        properties = {
          title  = "HTTP edge and load-balancer failures"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Sum"
          period = var.alarm_period_seconds
          metrics = [
            ["AWS/ApiGateway", "5xx", "ApiId", var.api_gateway_id, "Stage", var.api_gateway_stage_name],
            ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", var.alb_arn_suffix],
            ["AWS/ApplicationELB", "RejectedConnectionCount", "LoadBalancer", var.alb_arn_suffix],
          ]
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = local.shared_widget_y + 6
        width  = 12
        height = 6
        properties = {
          title  = "Aurora capacity and connections"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Average"
          period = var.alarm_period_seconds
          metrics = [
            ["AWS/RDS", "CPUUtilization", "DBClusterIdentifier", var.aurora_cluster_identifier],
            ["AWS/RDS", "DatabaseConnections", "DBClusterIdentifier", var.aurora_cluster_identifier],
            ["AWS/RDS", "ServerlessDatabaseCapacity", "DBClusterIdentifier", var.aurora_cluster_identifier],
            ["AWS/RDS", "ACUUtilization", "DBClusterIdentifier", var.aurora_cluster_identifier],
          ]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = local.shared_widget_y + 6
        width  = 12
        height = 6
        properties = {
          title  = "Daily batch executions"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Sum"
          period = var.alarm_period_seconds
          metrics = [
            ["AWS/States", "ExecutionsSucceeded", "StateMachineArn", var.daily_state_machine_arn],
            ["AWS/States", "ExecutionsFailed", "StateMachineArn", var.daily_state_machine_arn],
            ["AWS/States", "ExecutionsTimedOut", "StateMachineArn", var.daily_state_machine_arn],
          ]
        }
      },
      {
        type   = "log"
        x      = 0
        y      = local.shared_widget_y + 12
        width  = 12
        height = 6
        properties = {
          title  = "Rejected VPC flows"
          region = data.aws_region.current.region
          view   = "table"
          query  = "SOURCE '${var.vpc_flow_log_group_name}' | fields @timestamp, srcAddr, dstAddr, srcPort, dstPort, protocol, action | filter action = 'REJECT' | sort @timestamp desc | limit 50"
        }
      },
    ],
    local.cloudfront_widget,
  )
}

resource "aws_cloudwatch_dashboard" "operations" {
  dashboard_name = "${local.name_stem}-operations"

  # WHY : Alternatives Considered: a JSON heredoc was rejected because
  #       Terraform treats it as an opaque string and malformed JSON reaches the
  #       service only during apply. jsonencode validates the object structure
  #       while planning and lets the variable-driven widget lists stay typed.
  dashboard_body = jsonencode({
    start          = "-PT6H"
    periodOverride = "inherit"
    widgets        = local.dashboard_widgets
  })
}

# -----------------------------------------------------------------------------
# Structurally grounded alarms
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "service_unhealthy" {
  for_each = var.service_target_group_arn_suffixes

  alarm_name          = "${local.name_stem}-${each.key}-unhealthy-targets"
  alarm_description   = "Condition: the load balancer reports an unhealthy target. Question: is ${each.key} serving traffic? Action: replace the task or roll back the image revision."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  statistic           = "Maximum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = each.value
  }

  # WHY : Assumptions: zero is derived from the health-check contract rather
  #       than chosen as a service objective. A target is unhealthy only after
  #       the load balancer's configured checks have classified it that way.
  tags = merge(local.tags, {
    Service = each.key
    Signal  = "unhealthy-target"
  })
}

resource "aws_cloudwatch_metric_alarm" "service_5xx" {
  for_each = var.service_target_group_arn_suffixes

  alarm_name          = "${local.name_stem}-${each.key}-target-5xx"
  alarm_description   = "Condition: target-generated 5xx responses reach the configured count. Question: is ${each.key} failing requests? Action: inspect that service's correlated logs and roll back or replace the failing task."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.service_error_rate_threshold
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = each.value
  }

  tags = merge(local.tags, {
    Service = each.key
    Signal  = "target-5xx"
  })
}

resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  alarm_name          = "${local.name_stem}-api-5xx"
  alarm_description   = "Condition: the HTTP API returns 5xx responses at the configured count. Question: is failure occurring at the edge or integration boundary? Action: compare API access logs with the load-balancer and service alarms."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.service_error_rate_threshold
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/ApiGateway"
  metric_name         = "5xx"
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    ApiId = var.api_gateway_id
    Stage = var.api_gateway_stage_name
  }

  tags = merge(local.tags, {
    Signal = "api-5xx"
  })
}

resource "aws_cloudwatch_metric_alarm" "dead_letter_depth" {
  for_each = local.dlq_queue_names

  alarm_name          = "${local.name_stem}-${each.key}-depth"
  alarm_description   = "Condition: a dead-letter queue contains a visible message. Question: has a message exhausted every receive attempt? Action: inspect and redrive the stranded message only after its failure cause is corrected."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.dead_letter_depth_threshold
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = var.alarm_period_seconds
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    QueueName = each.value
  }

  # WHY : Assumptions: one datapoint is sufficient because a message reaches
  #       this queue only after the source redrive policy has exhausted its
  #       receive limit. Waiting for a second period hides a repeated failure;
  #       it does not filter a transient condition.
  tags = merge(local.tags, {
    Queue  = each.key
    Signal = "dead-letter-depth"
  })
}

resource "aws_cloudwatch_metric_alarm" "reply_queue_age" {
  for_each = local.reply_queue_names

  alarm_name          = "${local.name_stem}-${each.key}-oldest-message"
  alarm_description   = "Condition: the oldest reply exceeds the baseline request/reply expiry interval. Question: is the requester consuming replies? Action: inspect the waiting consumer and correlate the stale message before its expiresAt is enforced."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.reply_queue_age_threshold_seconds
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = var.alarm_period_seconds
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  statistic           = "Maximum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    QueueName = each.value
  }

  tags = merge(local.tags, {
    Queue  = each.key
    Signal = "stale-reply"
  })
}

# WHY : Assumptions: a rotation failure is silent by construction. Secrets Manager
#       invokes the function on its own schedule with no operator present, and a
#       failed rotation leaves the secret on its previous version -- so the system
#       keeps working while the control that was supposed to be running has
#       stopped. The Errors metric is therefore the only signal that distinguishes
#       "rotated successfully" from "never ran again", and it is watched at a
#       threshold of one because a single failed rotation is already the whole
#       finding.
#       Alternatives Considered: alarming on the absence of successful invocations
#       instead. Rejected because the rotation interval is measured in days, so an
#       absence alarm would have to span a window long enough to be useless for the
#       common case of a function that fails on every attempt.
#       Trade-offs: the input is a set of names rather than a single name, and an
#       empty set creates no alarm. That keeps the module usable by a root that has
#       not provisioned rotation, at the cost of the alarm silently not existing
#       there -- which is why the variable's description says so explicitly.
resource "aws_cloudwatch_metric_alarm" "rotation_failure" {
  for_each = var.rotation_lambda_function_names

  alarm_name          = "${local.name_stem}-${each.value}-rotation-errors"
  alarm_description   = "Condition: rotation function ${each.value} reported an invocation error. Question: did a scheduled credential rotation fail and leave the secret on its previous version? Action: inspect the function's encrypted log group and reconcile the affected secret version before the next interval."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = var.alarm_period_seconds
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    FunctionName = each.value
  }

  tags = merge(local.tags, {
    Signal = "rotation-errors"
  })
}

resource "aws_cloudwatch_metric_alarm" "batch_failure" {
  for_each = local.batch_failure_metrics

  alarm_name          = "${local.name_stem}-batch-${replace(each.key, "_", "-")}"
  alarm_description   = "Condition: the daily state machine reports ${each.value}. Question: did the nightly chain fail rather than take the accepted RC 4 warning edge? Action: inspect the failed state and redrive after the cause is corrected."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.batch_failure_threshold
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = var.alarm_period_seconds
  namespace           = "AWS/States"
  metric_name         = each.value
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    StateMachineArn = var.daily_state_machine_arn
  }

  # WHY : Assumptions: the state machine classifies posting RC 4 as a warning
  #       and still succeeds, so this metric sees only the fail/fatal tier. It
  #       deliberately does not turn business-rule rejects into red alarms.
  tags = merge(local.tags, {
    Signal = "batch-${each.key}"
  })
}

resource "aws_cloudwatch_metric_alarm" "aurora_cpu" {
  alarm_name          = "${local.name_stem}-aurora-cpu"
  alarm_description   = "Condition: cluster processor utilisation reaches the configured threshold. Question: is compute pressure sustained on the Aurora cluster? Action: compare capacity and connections before changing the environment's maximum ACUs."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.database_cpu_threshold_percent
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  treat_missing_data  = "missing"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    DBClusterIdentifier = var.aurora_cluster_identifier
  }

  tags = merge(local.tags, {
    Signal = "aurora-cpu"
  })
}

resource "aws_cloudwatch_metric_alarm" "aurora_capacity" {
  alarm_name          = "${local.name_stem}-aurora-capacity-ceiling"
  alarm_description   = "Condition: serverless database capacity reaches the maximum configured by the environment root. Question: has the cluster exhausted the capacity it is permitted to add? Action: review workload and raise the declared maximum only when the pressure is expected."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.aurora_max_capacity
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/RDS"
  metric_name         = "ServerlessDatabaseCapacity"
  statistic           = "Maximum"
  treat_missing_data  = "missing"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    DBClusterIdentifier = var.aurora_cluster_identifier
  }

  # WHY : Assumptions: the threshold is not independently chosen. It is the
  #       exact max_capacity value the root supplied to Aurora, so crossing it
  #       means the configured ceiling was reached by definition.
  tags = merge(local.tags, {
    Signal = "aurora-capacity"
  })
}
