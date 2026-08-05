# =============================================================================
# infra/modules/step-functions-batch/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions two STANDARD Step Functions workflows: the eleven-work-state
#   nightly CardDemo batch chain and the ad-hoc report workflow started by
#   reporting-service. Both use one least-privilege execution role, encrypted
#   CloudWatch execution logs and optional X-Ray tracing.
#
# Parameters:
#   variables.tf declares the ECS cluster and three task definitions, private
#   task placement, passable roles, three Lambda functions, dataset bucket,
#   notification topic, timeout/retry controls and observability settings. The
#   business date is deliberately execution input, never Terraform state.
#
# Return values:
#   None here. outputs.tf publishes both state-machine ARNs/names, the execution
#   role and both log-group identities.
#
# Exceptions or errors:
#   Every work state has an explicit timeout, retry and catch. Infrastructure
#   faults reach the common notification path; container exit codes are
#   evaluated explicitly so posting code 4 rejoins the success path while hard
#   failures notify and fail. The daily failure path invokes the idempotent
#   resume function before terminating so online writes are not left quiesced.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: a JCL COND is a skip predicate and a Choice is a
#     run predicate. PostTransactions therefore continues for exit codes at or
#     below the configured warn code and fails only above it; copying the JCL
#     sense would turn a normal reject night into a failed chain.
#   - Assumptions: state 2 is a ten-item Map using data-migration, states 3-7
#     use batch-service, and states 8-9 plus ad-hoc reporting use
#     reporting-service. BatchApplication's verified seven-name list contains
#     the five daily batch jobs used here and contains no reporting job.
#   - Trade-offs: the execution payload carries only dates, dataset names,
#     execution identities and task metadata. Record data and credentials never
#     enter Step Functions, which is why execution-data logging can remain on.
#   - Alternatives Considered: optional Lambda ARNs replaced by Pass states.
#     Rejected because silently skipping the quiesce/resume bracket would run
#     the whole write window against live online writes; a missing function must
#     prevent planning rather than degrade behaviour.
# =============================================================================

data "aws_partition" "current" {}
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  name_stem                = "${var.name_prefix}-${var.environment}"
  daily_machine_name       = "${local.name_stem}-daily-batch"
  adhoc_machine_name       = "${local.name_stem}-adhoc-report"
  execution_role_name      = "${local.name_stem}-sfn-batch"
  ecs_events_rule_arn      = "arn:${data.aws_partition.current.partition}:events:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:rule/StepFunctionsGetEventsForECSTaskRule"
  state_machine_arn_prefix = "arn:${data.aws_partition.current.partition}:states:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:stateMachine:${local.name_stem}-"

  working_state_names = [
    "QuiesceOnlineWrites",
    "StageSeedDatasets",
    "PreflightDailyTransactions",
    "PostTransactions",
    "CalculateInterest",
    "BackupTransactions",
    "CombineTransactions",
    "GenerateStatements",
    "GenerateReports",
    "AnalyzeTables",
    "ResumeOnlineWrites",
  ]

  state_timeouts = {
    for state_name in local.working_state_names :
    state_name => lookup(
      var.state_timeout_seconds_overrides,
      state_name,
      var.default_state_timeout_seconds,
    )
  }

  network_configuration = {
    AwsvpcConfiguration = {
      Subnets        = var.private_app_subnet_ids
      SecurityGroups = var.security_group_ids
      AssignPublicIp = "DISABLED"
    }
  }

  ecs_retry = [{
    # WHY : Assumptions: a non-zero container exit does not throw an ECS API
    #       error in the synchronous integration; it is returned and handled by
    #       the Choice states below. This retry therefore covers launch,
    #       observation and timeout faults rather than replaying a deterministic
    #       application outcome.
    ErrorEquals     = ["States.TaskFailed", "States.Timeout"]
    IntervalSeconds = var.retry_interval_seconds
    MaxAttempts     = var.retry_max_attempts
    BackoffRate     = var.retry_backoff_rate
  }]

  lambda_retry = [{
    ErrorEquals = [
      "Lambda.ServiceException",
      "Lambda.AWSLambdaException",
      "Lambda.SdkClientException",
      "Lambda.TooManyRequestsException",
    ]
    IntervalSeconds = var.retry_interval_seconds
    MaxAttempts     = var.retry_max_attempts
    BackoffRate     = var.retry_backoff_rate
  }]

  common_catch = [{
    ErrorEquals = ["States.ALL"]
    ResultPath  = "$.failure"
    Next        = "NotifyFailure"
  }]

  ecs_result_selector = {
    "exitCode.$" = "$.Tasks[0].Containers[0].ExitCode"
    "taskArn.$"  = "$.Tasks[0].TaskArn"
  }

  batch_jobs = {
    PreflightDailyTransactions = {
      job         = "preflight-daily-transactions"
      result_path = "$.preflight"
      next        = "CheckPreflightExitCode"
    }
    PostTransactions = {
      job         = "post-transactions"
      result_path = "$.posting"
      next        = "CheckPostingExitCode"
    }
    CalculateInterest = {
      job         = "calculate-interest"
      result_path = "$.interest"
      next        = "CheckInterestExitCode"
    }
    BackupTransactions = {
      job         = "backup-transactions"
      result_path = "$.backup"
      next        = "CheckBackupExitCode"
    }
    CombineTransactions = {
      job         = "combine-transactions"
      result_path = "$.combine"
      next        = "CheckCombineExitCode"
    }
  }

  batch_task_states = {
    for state_name, job_config in local.batch_jobs :
    state_name => {
      Type           = "Task"
      Resource       = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
      TimeoutSeconds = local.state_timeouts[state_name]
      Parameters = {
        Cluster              = var.ecs_cluster_arn
        TaskDefinition       = var.batch_task_definition_arn
        LaunchType           = "FARGATE"
        NetworkConfiguration = local.network_configuration
        Overrides = {
          ContainerOverrides = [{
            Name        = var.batch_container_name
            "Command.$" = "States.Array('--job=${job_config.job}', States.Format('--business-date={}', $.businessDate))"
            Environment = [
              {
                Name      = "CARDDEMO_BATCH_RUN_ID"
                "Value.$" = "$$.Execution.Name"
              },
              {
                Name  = "CARDDEMO_DATASET_BUCKET"
                Value = var.dataset_bucket_name
              },
            ]
          }]
        }
      }
      ResultSelector = local.ecs_result_selector
      ResultPath     = job_config.result_path
      Retry          = local.ecs_retry
      Catch          = local.common_catch
      Next           = job_config.next
    }
  }

  reporting_jobs = {
    GenerateStatements = {
      job         = "generate-statements"
      result_path = "$.statements"
      next        = "CheckStatementsExitCode"
    }
    GenerateReports = {
      job         = "generate-reports"
      result_path = "$.reports"
      next        = "CheckReportsExitCode"
    }
  }

  reporting_task_states = {
    for state_name, job_config in local.reporting_jobs :
    state_name => {
      Type           = "Task"
      Resource       = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
      TimeoutSeconds = local.state_timeouts[state_name]
      Parameters = {
        Cluster              = var.ecs_cluster_arn
        TaskDefinition       = var.reporting_task_definition_arn
        LaunchType           = "FARGATE"
        NetworkConfiguration = local.network_configuration
        Overrides = {
          ContainerOverrides = [{
            Name        = var.reporting_container_name
            "Command.$" = "States.Array('--job=${job_config.job}', States.Format('--business-date={}', $.businessDate))"
            Environment = [
              {
                Name      = "CARDDEMO_BATCH_RUN_ID"
                "Value.$" = "$$.Execution.Name"
              },
              {
                Name  = "CARDDEMO_DATASET_BUCKET"
                Value = var.dataset_bucket_name
              },
            ]
          }]
        }
      }
      ResultSelector = local.ecs_result_selector
      ResultPath     = job_config.result_path
      Retry          = local.ecs_retry
      Catch          = local.common_catch
      Next           = job_config.next
    }
  }

  daily_definition = {
    Comment = "CardDemo nightly batch chain migrated from JCL/JES2"
    StartAt = "ValidateExecutionInput"
    States = merge(
      local.batch_task_states,
      local.reporting_task_states,
      {
        ValidateExecutionInput = {
          Type = "Choice"
          Choices = [
            {
              And = [
                {
                  Variable  = "$.businessDate"
                  IsPresent = true
                },
                {
                  Variable      = "$.businessDate"
                  StringMatches = "????-??-??"
                },
              ]
              Next = "PrepareProvidedBusinessDate"
            },
            {
              And = [
                {
                  Variable  = "$.scheduledTime"
                  IsPresent = true
                },
                {
                  Variable      = "$.scheduledTime"
                  StringMatches = "????-??-??T*"
                },
              ]
              Next = "PrepareScheduledBusinessDate"
            },
          ]
          Default = "InvalidExecutionInput"
        }

        PrepareProvidedBusinessDate = {
          Type = "Pass"
          Parameters = {
            "businessDate.$" = "$.businessDate"
            seedDatasets     = var.seed_dataset_names
          }
          Next = "QuiesceOnlineWrites"
        }

        PrepareScheduledBusinessDate = {
          Type = "Pass"
          Parameters = {
            "businessDate.$" = "States.ArrayGetItem(States.StringSplit($.scheduledTime, 'T'), 0)"
            seedDatasets     = var.seed_dataset_names
          }
          Next = "QuiesceOnlineWrites"
        }

        InvalidExecutionInput = {
          Type = "Pass"
          Result = {
            error   = "InvalidExecutionInput"
            message = "Supply businessDate as YYYY-MM-DD or scheduledTime as an ISO timestamp"
          }
          ResultPath = "$.failure"
          Next       = "NotifyFailure"
        }

        QuiesceOnlineWrites = {
          Type           = "Task"
          Resource       = "arn:${data.aws_partition.current.partition}:states:::lambda:invoke"
          TimeoutSeconds = local.state_timeouts.QuiesceOnlineWrites
          Parameters = {
            FunctionName = var.quiesce_function_arn
            Payload = {
              action            = "quiesce"
              "businessDate.$"  = "$.businessDate"
              "executionName.$" = "$$.Execution.Name"
            }
          }
          ResultPath = "$.quiesce"
          Retry      = local.lambda_retry
          Catch      = local.common_catch
          Next       = "StageSeedDatasets"
        }

        StageSeedDatasets = {
          Type           = "Map"
          ItemsPath      = "$.seedDatasets"
          MaxConcurrency = var.stage_datasets_max_concurrency
          TimeoutSeconds = local.state_timeouts.StageSeedDatasets
          ItemSelector = {
            "dataset.$"      = "$$.Map.Item.Value"
            "businessDate.$" = "$.businessDate"
          }
          ItemProcessor = {
            ProcessorConfig = {
              Mode = "INLINE"
            }
            StartAt = "StageSeedDataset"
            States = {
              StageSeedDataset = {
                Type           = "Task"
                Resource       = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
                TimeoutSeconds = local.state_timeouts.StageSeedDatasets
                Parameters = {
                  Cluster              = var.ecs_cluster_arn
                  TaskDefinition       = var.data_migration_task_definition_arn
                  LaunchType           = "FARGATE"
                  NetworkConfiguration = local.network_configuration
                  Overrides = {
                    ContainerOverrides = [{
                      Name        = var.data_migration_container_name
                      "Command.$" = "States.Array('stage-dataset', States.Format('--dataset={}', $.dataset), States.Format('--business-date={}', $.businessDate))"
                      Environment = [
                        {
                          Name      = "CARDDEMO_BATCH_RUN_ID"
                          "Value.$" = "$$.Execution.Name"
                        },
                        {
                          Name  = "CARDDEMO_DATASET_BUCKET"
                          Value = var.dataset_bucket_name
                        },
                      ]
                    }]
                  }
                }
                ResultSelector = local.ecs_result_selector
                ResultPath     = "$.task"
                Retry          = local.ecs_retry
                Catch = [{
                  ErrorEquals = ["States.ALL"]
                  ResultPath  = "$.failure"
                  Next        = "DatasetStageFailed"
                }]
                Next = "CheckDatasetStageExitCode"
              }

              CheckDatasetStageExitCode = {
                Type = "Choice"
                Choices = [{
                  Variable      = "$.task.exitCode"
                  NumericEquals = 0
                  Next          = "DatasetStageSucceeded"
                }]
                Default = "DatasetStageFailed"
              }

              DatasetStageSucceeded = {
                Type = "Succeed"
              }

              DatasetStageFailed = {
                Type  = "Fail"
                Error = "DatasetStageFailed"
                Cause = "The data-migration task did not complete with exit code zero"
              }
            }
          }
          ResultPath = null
          Catch      = local.common_catch
          Next       = "PreflightDailyTransactions"
        }

        CheckPreflightExitCode = {
          Type = "Choice"
          Choices = [{
            Variable      = "$.preflight.exitCode"
            NumericEquals = 0
            Next          = "PostTransactions"
          }]
          Default = "NotifyFailure"
        }

        CheckPostingExitCode = {
          Type = "Choice"
          Choices = [
            {
              Variable      = "$.posting.exitCode"
              NumericEquals = 0
              Next          = "CalculateInterest"
            },
            {
              Variable              = "$.posting.exitCode"
              NumericLessThanEquals = var.posting_warn_return_code
              Next                  = "RecordPostingWarning"
            },
          ]
          Default = "NotifyFailure"
        }

        RecordPostingWarning = {
          Type = "Pass"
          Parameters = {
            code         = "POSTING_REJECTS_PRESENT"
            "exitCode.$" = "$.posting.exitCode"
          }
          ResultPath = "$.warning"
          Next       = "CalculateInterest"
        }

        CheckInterestExitCode = {
          Type = "Choice"
          Choices = [{
            Variable      = "$.interest.exitCode"
            NumericEquals = 0
            Next          = "BackupTransactions"
          }]
          Default = "NotifyFailure"
        }

        CheckBackupExitCode = {
          Type = "Choice"
          Choices = [{
            Variable      = "$.backup.exitCode"
            NumericEquals = 0
            Next          = "CombineTransactions"
          }]
          Default = "NotifyFailure"
        }

        CheckCombineExitCode = {
          Type = "Choice"
          Choices = [{
            Variable      = "$.combine.exitCode"
            NumericEquals = 0
            Next          = "GenerateStatements"
          }]
          Default = "NotifyFailure"
        }

        CheckStatementsExitCode = {
          Type = "Choice"
          Choices = [{
            Variable      = "$.statements.exitCode"
            NumericEquals = 0
            Next          = "GenerateReports"
          }]
          Default = "NotifyFailure"
        }

        CheckReportsExitCode = {
          Type = "Choice"
          Choices = [{
            Variable      = "$.reports.exitCode"
            NumericEquals = 0
            Next          = "AnalyzeTables"
          }]
          Default = "NotifyFailure"
        }

        AnalyzeTables = {
          Type           = "Task"
          Resource       = "arn:${data.aws_partition.current.partition}:states:::lambda:invoke"
          TimeoutSeconds = local.state_timeouts.AnalyzeTables
          Parameters = {
            FunctionName = var.analyze_tables_function_arn
            Payload = {
              action            = "analyze"
              "businessDate.$"  = "$.businessDate"
              "executionName.$" = "$$.Execution.Name"
            }
          }
          ResultPath = "$.analyze"
          Retry      = local.lambda_retry
          Catch      = local.common_catch
          Next       = "ResumeOnlineWrites"
        }

        ResumeOnlineWrites = {
          Type           = "Task"
          Resource       = "arn:${data.aws_partition.current.partition}:states:::lambda:invoke"
          TimeoutSeconds = local.state_timeouts.ResumeOnlineWrites
          Parameters = {
            FunctionName = var.resume_function_arn
            Payload = {
              action            = "resume"
              "businessDate.$"  = "$.businessDate"
              "executionName.$" = "$$.Execution.Name"
            }
          }
          ResultPath = "$.resume"
          Retry      = local.lambda_retry
          Catch      = local.common_catch
          Next       = "BatchSucceeded"
        }

        BatchSucceeded = {
          Type = "Succeed"
        }

        NotifyFailure = {
          Type     = "Task"
          Resource = "arn:${data.aws_partition.current.partition}:states:::sns:publish"
          Parameters = {
            TopicArn    = var.notification_topic_arn
            Subject     = "CardDemo daily batch failed"
            "Message.$" = "States.Format('CardDemo daily batch execution {} failed: {}', $$.Execution.Name, States.JsonToString($))"
          }
          ResultPath = "$.notification"
          Retry = [{
            ErrorEquals     = ["States.TaskFailed", "States.Timeout"]
            IntervalSeconds = var.retry_interval_seconds
            MaxAttempts     = var.retry_max_attempts
            BackoffRate     = var.retry_backoff_rate
          }]
          Catch = [{
            ErrorEquals = ["States.ALL"]
            ResultPath  = "$.notificationFailure"
            Next        = "ResumeOnlineWritesOnFailure"
          }]
          Next = "ResumeOnlineWritesOnFailure"
        }

        ResumeOnlineWritesOnFailure = {
          Type           = "Task"
          Resource       = "arn:${data.aws_partition.current.partition}:states:::lambda:invoke"
          TimeoutSeconds = local.state_timeouts.ResumeOnlineWrites
          Parameters = {
            FunctionName = var.resume_function_arn
            Payload = {
              action            = "resume"
              failedExecution   = true
              "executionName.$" = "$$.Execution.Name"
            }
          }
          ResultPath = "$.failureResume"
          Retry      = local.lambda_retry
          Catch = [{
            ErrorEquals = ["States.ALL"]
            ResultPath  = "$.resumeFailure"
            Next        = "BatchFailed"
          }]
          Next = "BatchFailed"
        }

        BatchFailed = {
          Type  = "Fail"
          Error = "CardDemoBatchFailed"
          Cause = "A daily batch state failed; inspect the execution history and notification"
        }
      },
    )
  }

  adhoc_definition = {
    Comment = "CardDemo ad-hoc report workflow"
    StartAt = "ValidateReportRequest"
    States = {
      ValidateReportRequest = {
        Type = "Choice"
        Choices = [{
          And = [
            {
              Variable  = "$.startDate"
              IsPresent = true
            },
            {
              Variable      = "$.startDate"
              StringMatches = "????-??-??"
            },
            {
              Variable  = "$.endDate"
              IsPresent = true
            },
            {
              Variable      = "$.endDate"
              StringMatches = "????-??-??"
            },
            {
              Variable  = "$.reportType"
              IsPresent = true
            },
          ]
          Next = "GenerateAdHocReport"
        }]
        Default = "InvalidReportRequest"
      }

      InvalidReportRequest = {
        Type = "Pass"
        Result = {
          error   = "InvalidReportRequest"
          message = "Supply startDate, endDate and reportType"
        }
        ResultPath = "$.failure"
        Next       = "NotifyAdHocFailure"
      }

      GenerateAdHocReport = {
        Type     = "Task"
        Resource = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
        TimeoutSeconds = lookup(
          var.state_timeout_seconds_overrides,
          "GenerateReports",
          var.default_state_timeout_seconds,
        )
        Parameters = {
          Cluster              = var.ecs_cluster_arn
          TaskDefinition       = var.reporting_task_definition_arn
          LaunchType           = "FARGATE"
          NetworkConfiguration = local.network_configuration
          Overrides = {
            ContainerOverrides = [{
              Name        = var.reporting_container_name
              "Command.$" = "States.Array('--job=generate-report', States.Format('--start-date={}', $.startDate), States.Format('--end-date={}', $.endDate), States.Format('--report-type={}', $.reportType))"
              Environment = [
                {
                  Name      = "CARDDEMO_BATCH_RUN_ID"
                  "Value.$" = "$$.Execution.Name"
                },
                {
                  Name  = "CARDDEMO_DATASET_BUCKET"
                  Value = var.dataset_bucket_name
                },
              ]
            }]
          }
        }
        ResultSelector = local.ecs_result_selector
        ResultPath     = "$.report"
        Retry          = local.ecs_retry
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.failure"
          Next        = "NotifyAdHocFailure"
        }]
        Next = "CheckAdHocReportExitCode"
      }

      CheckAdHocReportExitCode = {
        Type = "Choice"
        Choices = [{
          Variable      = "$.report.exitCode"
          NumericEquals = 0
          Next          = "AdHocReportSucceeded"
        }]
        Default = "NotifyAdHocFailure"
      }

      NotifyAdHocFailure = {
        Type     = "Task"
        Resource = "arn:${data.aws_partition.current.partition}:states:::sns:publish"
        Parameters = {
          TopicArn    = var.notification_topic_arn
          Subject     = "CardDemo ad-hoc report failed"
          "Message.$" = "States.Format('CardDemo ad-hoc report execution {} failed: {}', $$.Execution.Name, States.JsonToString($))"
        }
        ResultPath = "$.notification"
        Retry = [{
          ErrorEquals     = ["States.TaskFailed", "States.Timeout"]
          IntervalSeconds = var.retry_interval_seconds
          MaxAttempts     = var.retry_max_attempts
          BackoffRate     = var.retry_backoff_rate
        }]
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.notificationFailure"
          Next        = "AdHocReportFailed"
        }]
        Next = "AdHocReportFailed"
      }

      AdHocReportSucceeded = {
        Type = "Succeed"
      }

      AdHocReportFailed = {
        Type  = "Fail"
        Error = "CardDemoAdHocReportFailed"
        Cause = "The ad-hoc report task failed; inspect the execution history and notification"
      }
    }
  }

  task_definition_arns = [
    var.batch_task_definition_arn,
    var.data_migration_task_definition_arn,
    var.reporting_task_definition_arn,
  ]

  # WHY : Assumptions: iam:RunTask evaluates the revision-qualified task
  #       definition ARN. A caller may supply either a family ARN or a
  #       revision ARN, so an unqualified family is widened only to its own
  #       revisions rather than to another task-definition family.
  task_definition_policy_arns = [
    for task_definition_arn in local.task_definition_arns :
    can(regex(":[0-9]+$", task_definition_arn))
    ? task_definition_arn
    : "${task_definition_arn}:*"
  ]
}

# -----------------------------------------------------------------------------
# Encrypted execution logs
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "daily" {
  name              = "/aws/vendedlogs/states/${local.daily_machine_name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn

  tags = merge(var.tags, {
    Name = "${local.daily_machine_name}-logs"
  })
}

resource "aws_cloudwatch_log_group" "adhoc" {
  name              = "/aws/vendedlogs/states/${local.adhoc_machine_name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn

  tags = merge(var.tags, {
    Name = "${local.adhoc_machine_name}-logs"
  })
}

# -----------------------------------------------------------------------------
# Shared execution role
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "assume_role" {
  statement {
    sid     = "StepFunctionsAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }

    # WHY : Assumptions: both machines share one role, so the source ARN is
    #       scoped to this module's name prefix rather than one exact machine.
    #       SourceAccount separately prevents a state machine in another
    #       account from satisfying the wildcard.
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["${local.state_machine_arn_prefix}*"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = local.execution_role_name
  assume_role_policy = data.aws_iam_policy_document.assume_role.json

  tags = merge(var.tags, {
    Name = local.execution_role_name
  })
}

data "aws_iam_policy_document" "permissions" {
  statement {
    sid       = "RunApprovedTaskDefinitions"
    effect    = "Allow"
    actions   = ["ecs:RunTask"]
    resources = local.task_definition_policy_arns

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [var.ecs_cluster_arn]
    }
  }

  # WHY : Assumptions: task ARNs do not exist until RunTask creates them, so
  #       StopTask and DescribeTasks cannot be scoped to a static Resource ARN.
  #       The cluster condition is the service-supported least-privilege bound.
  statement {
    sid       = "ObserveAndStopTasksInApprovedCluster"
    effect    = "Allow"
    actions   = ["ecs:DescribeTasks", "ecs:StopTask"]
    resources = ["*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [var.ecs_cluster_arn]
    }
  }

  statement {
    sid       = "ObserveSynchronousTaskCompletion"
    effect    = "Allow"
    actions   = ["events:DescribeRule", "events:PutRule", "events:PutTargets"]
    resources = [local.ecs_events_rule_arn]
  }

  statement {
    sid       = "PassApprovedECSTaskRoles"
    effect    = "Allow"
    actions   = ["iam:PassRole"]
    resources = var.task_role_arns

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  statement {
    sid     = "InvokeOperationalFunctions"
    effect  = "Allow"
    actions = ["lambda:InvokeFunction"]
    resources = [
      var.quiesce_function_arn,
      var.analyze_tables_function_arn,
      var.resume_function_arn,
    ]
  }

  statement {
    sid       = "PublishFailureNotification"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = [var.notification_topic_arn]
  }

  # WHY : Assumptions: Step Functions vended-log delivery actions do not
  #       support resource-level permissions. They are isolated in one
  #       statement with enumerated actions so the wildcard resource cannot
  #       widen any ECS, Lambda, role-passing or notification permission.
  statement {
    sid    = "DeliverExecutionLogs"
    effect = "Allow"
    actions = [
      "logs:CreateLogDelivery",
      "logs:GetLogDelivery",
      "logs:UpdateLogDelivery",
      "logs:DeleteLogDelivery",
      "logs:ListLogDeliveries",
      "logs:PutResourcePolicy",
      "logs:DescribeResourcePolicies",
      "logs:DescribeLogGroups",
    ]
    resources = ["*"]
  }

  dynamic "statement" {
    for_each = var.tracing_enabled ? [1] : []

    content {
      sid    = "PublishExecutionTraces"
      effect = "Allow"
      actions = [
        "xray:GetSamplingRules",
        "xray:GetSamplingTargets",
        "xray:PutTelemetryRecords",
        "xray:PutTraceSegments",
      ]
      resources = ["*"]
    }
  }
}

resource "aws_iam_role_policy" "this" {
  name   = local.execution_role_name
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.permissions.json
}

# -----------------------------------------------------------------------------
# State machines
# -----------------------------------------------------------------------------

resource "aws_sfn_state_machine" "daily" {
  name     = local.daily_machine_name
  role_arn = aws_iam_role.this.arn
  type     = "STANDARD"

  definition = jsonencode(local.daily_definition)

  logging_configuration {
    include_execution_data = var.include_execution_data
    level                  = var.log_level
    log_destination        = "${aws_cloudwatch_log_group.daily.arn}:*"
  }

  tracing_configuration {
    enabled = var.tracing_enabled
  }

  tags = merge(var.tags, {
    Name = local.daily_machine_name
  })

  depends_on = [aws_iam_role_policy.this]
}

resource "aws_sfn_state_machine" "adhoc" {
  name     = local.adhoc_machine_name
  role_arn = aws_iam_role.this.arn
  type     = "STANDARD"

  definition = jsonencode(local.adhoc_definition)

  logging_configuration {
    include_execution_data = var.include_execution_data
    level                  = var.log_level
    log_destination        = "${aws_cloudwatch_log_group.adhoc.arn}:*"
  }

  tracing_configuration {
    enabled = var.tracing_enabled
  }

  tags = merge(var.tags, {
    Name = local.adhoc_machine_name
  })

  depends_on = [aws_iam_role_policy.this]
}
