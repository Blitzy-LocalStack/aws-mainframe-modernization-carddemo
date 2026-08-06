# =============================================================================
# infra/modules/step-functions-batch/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions two STANDARD Step Functions workflows: the eleven-work-state
#   nightly CardDemo batch chain and the ad-hoc report workflow started by
#   reporting-service. Both use one least-privilege execution role, encrypted
#   CloudWatch execution logs and optional X-Ray tracing. It also provisions the
#   out-of-graph finalizer that releases the online-write quiesce bracket for a
#   daily execution that was terminated rather than failed.
#
# Parameters:
#   variables.tf declares the ECS cluster and three task definitions, private
#   task placement, passable roles, three Lambda functions, dataset bucket,
#   notification topic, per-state timeout and retry controls and observability
#   settings. The business date is deliberately execution input, never Terraform
#   state.
#
# Return values:
#   None here. outputs.tf publishes both state-machine ARNs/names, the execution
#   role and both log-group identities.
#
# Exceptions or errors:
#   Every work state has an explicit timeout, retry and catch, and NO execution
#   carries a top-level timeout, because a top-level timeout ends an execution
#   outside the state graph and so skips the cleanup states. Infrastructure
#   faults reach the common notification path; container exit codes are
#   evaluated explicitly so posting codes 0 and 4 rejoin the success path while
#   every other code notifies and fails. The daily failure path releases the
#   online-write quiesce bracket before terminating, but only when the ownership
#   gate confirms THIS execution acquired it -- a refusal raised before the
#   bracket was taken notifies and fails without touching the flag. An execution
#   that is terminated rather than failed, by its own ceiling or by an operator,
#   enters no further state at all; the EventBridge finalizer rule at the foot of
#   this file releases the bracket for those, from outside the graph.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: a JCL COND is a skip predicate and a Choice is a
#     run predicate. PostTransactions therefore continues on EXACTLY 0 and
#     EXACTLY 4 and fails on every other code; copying the JCL sense would turn
#     a normal reject night into a failed chain, and copying its "4 or lower"
#     shape would tolerate exit codes the posting program cannot produce.
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
#   - Alternatives Considered: keeping a whole-execution ceiling by nesting the
#     chain in a child state machine that a parent owning the quiesce bracket
#     starts synchronously and catches. Rejected as the heavier of two ways to
#     reach the same guarantee: it doubles the state machines, the roles and the
#     log groups, and it still cannot catch an aborted parent. The
#     aws_cloudwatch_event_rule.daily_finalizer rule below covers abort as well
#     as timeout and adds one rule.
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

  # WHY : Refactoring Rationale: this used to `lookup` each state in an overrides
  #       map and fall back to a single default. It now indexes var.state_timeout_seconds
  #       directly, because that variable's own validation asserts one entry per
  #       work state -- so a missing key is a plan-time error naming the variable
  #       rather than a state silently inheriting a ceiling nobody chose for it.
  #       The iteration still runs over local.working_state_names so that this map
  #       and the state list cannot come to hold different sets.
  state_timeouts = {
    for state_name in local.working_state_names :
    state_name => var.state_timeout_seconds[state_name]
  }

  network_configuration = {
    AwsvpcConfiguration = {
      Subnets        = var.private_app_subnet_ids
      SecurityGroups = [var.task_security_group_id]
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

  # WHY : Refactoring Rationale: these two locals are the only process exit statuses
  #       the transaction-posting program can produce, and therefore the only two the
  #       chain continues on. They were one CONFIGURABLE ceiling compared with
  #       NumericLessThanEquals, and both halves of that were wrong. The ceiling
  #       admitted 1, 2 and 3 as warnings, and app/cbl/CBTRN02C.cbl assigns
  #       RETURN-CODE in exactly one place -- MOVE 4 TO RETURN-CODE at its line 230
  #       -- so it produces 0 or 4 and nothing else. An exit status of 1, 2 or 3
  #       therefore did not come from the program's own exit path at all: it came
  #       from the runtime failing before or around it, which is a hard failure
  #       being tolerated as a reject night. And because the ceiling was an input,
  #       it could be set to 255, at which point every failure the state can report
  #       satisfies the predicate and the chain runs interest accrual over
  #       transactions that were never posted.
  # WHY : Alternatives Considered: keeping the input and validating it to equal 4.
  #       Rejected because a variable whose only permitted value is a constant is a
  #       constant with an extra failure mode -- it still appears in every module
  #       call site and in the generated documentation as though it were a choice.
  #       The value is not a policy: it is the baseline's contract, cited above.
  # WHY : Assumptions: the JCL gate at app/jcl/TRANBKP.jcl:51 is COND=(4,LT), which
  #       as a SKIP predicate runs the step when the code is 4 or lower. Reading
  #       that as "tolerate 1 through 4" would be reading a bound the baseline never
  #       had to express, because the only producible codes below 4 are 0.
  posting_clean_return_code = 0
  posting_warn_return_code  = 4

  ecs_result_selector = {
    "exitCode.$" = "$.Tasks[0].Containers[0].ExitCode"
    "taskArn.$"  = "$.Tasks[0].TaskArn"
  }

  # WHY : Assumptions: the Lambda states use the SDK integration
  #       (arn:aws:states:::lambda:invoke), whose raw result is the INVOKE ENVELOPE --
  #       StatusCode, ExecutedVersion and the function's own return under Payload --
  #       and not the function's return itself. Without a selector the lease record
  #       would land at $.quiesce.Payload.leaseOwner, so every reference path in the
  #       ownership gate and in both release edges would address nothing. Extracting
  #       here rather than lengthening each reference keeps the envelope's shape a
  #       detail of this one line, exactly as ecs_result_selector above does for the
  #       task envelope.
  # WHY : Trade-offs: only the two members the graph actually addresses are lifted.
  #       Lifting the whole payload would also work and would survive the graph
  #       reading a third member later, but it would put the function's entire return
  #       -- including the parameter name and the SSM version -- into the execution
  #       history at a state whose purpose is to record who holds the bracket.
  #       Naming the two members makes an unread addition a plan-visible edit here.
  # WHY : Alternatives Considered: the optimized integration
  #       (arn:aws:lambda:::function:<name>), which returns the payload directly and
  #       needs no selector. Rejected because the module receives function ARNs as
  #       inputs rather than names, and the optimized form is expressed as a resource
  #       ARN this module would have to reconstruct from an input it was given whole.
  lambda_lease_result_selector = {
    "leaseAcquired.$" = "$.Payload.leaseAcquired"
    "leaseOwner.$"    = "$.Payload.leaseOwner"
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

    # WHY : Assumptions: a per-state timeout does not bound the execution as a
    #       whole. An execution can stall between states, or inside a Map's own
    #       bookkeeping, where no single state's TimeoutSeconds applies, and an
    #       unbounded execution there holds the online write path quiesced because
    #       ResumeOnlineWrites runs only once the chain finishes or fails. This
    #       ceiling exists to TERMINATE such an execution and so to make the stall
    #       DETECTABLE in bounded time; variables.tf derives its floor from the
    #       longest per-state ceiling so it can never be set below one.
    # WHY : Refactoring Rationale: this ceiling does NOT release the quiesce
    #       bracket, and an earlier revision said it did. An execution that hits
    #       its top-level timeout is terminated by the service as TIMED_OUT WITHOUT
    #       running any state's Catch: no further state is entered, so
    #       ResumeOnlineWritesOnFailure cannot run, no in-graph cleanup path is
    #       reachable, and the flag this chain set would stay set. The same holds for an operator ABORT, which
    #       no in-execution Catch can observe either. Relying on the ceiling meant
    #       that the one failure mode the bracket exists to survive -- a chain that
    #       hangs rather than fails -- was the one mode that left the online
    #       services read-only until an operator noticed. The release is therefore
    #       performed OUTSIDE the graph, by aws_cloudwatch_event_rule.daily_finalizer
    #       at the foot of this file, which reads the execution's terminal status
    #       from outside the execution and invokes the same idempotent resume
    #       function. The in-graph path remains for the failures that DO transition,
    #       because it releases the bracket seconds after the failure rather than
    #       after the event has propagated, and QuiesceOnlineWrites publishes this
    #       same execution as the bracket's lease owner so a reader of the flag can
    #       tell when it has outlived the execution that set it.
    TimeoutSeconds = var.state_machine_timeout_seconds

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

        # WHY : Assumptions: both preparation states copy the execution name into
        #       the state input as well as leaving it available on the context
        #       object. The ownership gate below compares the lease owner recorded
        #       by the quiesce call against this value with StringEqualsPath, and a
        #       comparison operator's reference path resolves against the state
        #       INPUT -- so the identity has to be in the input for the gate to be
        #       expressible at all. It is set here, before the bracket is taken,
        #       rather than beside the gate, because a value the gate itself
        #       introduced could not attest to who acquired the lease.
        PrepareProvidedBusinessDate = {
          Type = "Pass"
          Parameters = {
            "businessDate.$"  = "$.businessDate"
            "executionName.$" = "$$.Execution.Name"
            seedDatasets      = var.seed_datasets
          }
          Next = "QuiesceOnlineWrites"
        }

        PrepareScheduledBusinessDate = {
          Type = "Pass"
          Parameters = {
            "businessDate.$"  = "States.ArrayGetItem(States.StringSplit($.scheduledTime, 'T'), 0)"
            "executionName.$" = "$$.Execution.Name"
            seedDatasets      = var.seed_datasets
          }
          Next = "QuiesceOnlineWrites"
        }

        # WHY : Refactoring Rationale: a refused input now notifies and FAILS
        #       without touching the quiesce flag, where it used to route to the
        #       shared failure path and clear it. This state is reached before
        #       QuiesceOnlineWrites has run, so such an execution never acquired
        #       the bracket -- and clearing a flag it does not own re-enables
        #       online writes in the middle of whatever DOES own it: a concurrent
        #       execution's write window, or an operator's maintenance window set
        #       by hand. The damage is silent and is exactly the shape a scheduler
        #       produces, because a malformed input is most likely on a manual or
        #       retried start that overlaps a scheduled run.
        InvalidExecutionInput = {
          Type = "Pass"
          Result = {
            error   = "InvalidExecutionInput"
            message = "Supply businessDate as YYYY-MM-DD or scheduledTime as an ISO timestamp"
          }
          ResultPath = "$.failure"
          Next       = "NotifyInvalidExecutionInput"
        }

        # WHY : Alternatives Considered: reusing NotifyFailure and making the resume
        #       step conditional on the flag alone. Rejected because NotifyFailure's
        #       own catch handler falls through to the resume path, so a refusal
        #       whose notification also failed would still have reached it. A
        #       separate notification state whose every edge ends at BatchFailed is
        #       what makes "this execution never touches the bracket" a property of
        #       the graph rather than of one predicate inside it.
        NotifyInvalidExecutionInput = {
          Type     = "Task"
          Resource = "arn:${data.aws_partition.current.partition}:states:::sns:publish"
          Parameters = {
            TopicArn    = var.notification_topic_arn
            Subject     = "CardDemo daily batch refused its execution input"
            "Message.$" = "States.Format('CardDemo daily batch execution {} was refused before quiescing online writes: {}', $$.Execution.Name, States.JsonToString($.failure))"
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
            Next        = "BatchFailed"
          }]
          Next = "BatchFailed"
        }

        QuiesceOnlineWrites = {
          Type           = "Task"
          Resource       = "arn:${data.aws_partition.current.partition}:states:::lambda:invoke"
          TimeoutSeconds = local.state_timeouts.QuiesceOnlineWrites
          Parameters = {
            FunctionName = var.quiesce_function_arn
            Payload = {
              action = "quiesce"

              # WHY : Alternatives Considered: relying only on the parameter name
              #       the environment root bakes into the function's own
              #       environment variables, which would make this field
              #       unnecessary. Rejected because it leaves the definition
              #       showing a quiesce call with no subject: which flag the
              #       bracket toggles would be discoverable only by opening a
              #       different resource in a different file. Naming it in the
              #       payload puts it in the execution history beside the
              #       transition that set it.
              readOnlyFlagParameter = var.read_only_flag_parameter_name

              "businessDate.$"  = "$.businessDate"
              "executionName.$" = "$$.Execution.Name"

              # WHY : Trade-offs: the flag this call sets is a bare
              #       "true"/"false" string that outlives the execution that set
              #       it, so a reader cannot distinguish a live quiesce from one
              #       abandoned by an execution that stopped without resuming.
              #       Publishing the window's start and its ceiling turns the
              #       flag into a lease: leaseStartedAt plus leaseSeconds is the
              #       instant after which a still-quiesced flag is stale, which
              #       is exactly the point by which the watchdog rule at the
              #       foot of this file has been asked to clear it. The lease is
              #       therefore an expiry a reader can fail safe on, and the
              #       watchdog is what actually releases the bracket.
              #       Alternatives Considered: a separate lease-length input.
              #       Rejected because it could be set below
              #       state_machine_timeout_seconds and would then advertise an
              #       expiry that lapses while the chain is still legitimately
              #       running -- the one way a lease can do harm. Deriving it
              #       from the execution ceiling makes that unrepresentable,
              #       because the ceiling IS the longest the bracket can be held
              #       by a running execution.
              #       Assumptions: ASL has no date arithmetic, so the two parts
              #       travel separately and the reader adds them; the resume
              #       handler reads neither, and ignores payload keys it does not
              #       consume, so adding them cannot change the call it makes.
              leaseSeconds       = var.state_machine_timeout_seconds
              "leaseStartedAt.$" = "$$.Execution.StartTime"
            }
          }

          # WHY : Assumptions: the result of this call is the LEASE RECORD, and the
          #       contract with the function is that it returns leaseAcquired as a
          #       boolean and leaseOwner as the execution name that now holds the
          #       flag. Recording ownership is what lets every release below be
          #       conditional: without it, "was the flag set?" is the only question
          #       the graph can ask, and that question cannot distinguish a flag
          #       this execution set from one another execution or an operator set.
          #       A function that finds the flag already held by another owner
          #       reports leaseAcquired false, and the states below then leave it
          #       alone rather than clearing it on the way out.
          ResultSelector = local.lambda_lease_result_selector
          ResultPath     = "$.quiesce"
          Retry          = local.lambda_retry
          Catch          = local.common_catch
          Next           = "StageSeedDatasets"
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
              NumericEquals = local.posting_clean_return_code
              Next          = "CalculateInterest"
            },
            {
              Variable      = "$.posting.exitCode"
              NumericEquals = local.posting_warn_return_code
              Next          = "RecordPostingWarning"
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
              action = "resume"

              # WHY : Assumptions: the resume call names the same parameter the
              #       quiesce call set, so the bracket is symmetric in the
              #       execution history as well as in effect. Passing the name on
              #       both ends is what makes a mismatched pair visible in one
              #       place instead of requiring the two functions' environments
              #       to be compared.
              readOnlyFlagParameter = var.read_only_flag_parameter_name

              # WHY : Assumptions: the release names the owner it EXPECTS, so the
              #       function performs a conditional clear rather than an
              #       unconditional one. This edge is NOT gated on ownership the way
              #       the failure edge is -- QuiesceOnlineWrites continues to
              #       StageSeedDatasets whether or not it acquired the bracket, so a
              #       chain that reached here may be one that was refused. Passing the
              #       owner is what makes that safe: a refused acquisition reports an
              #       EMPTY owner, and the function declines to clear on behalf of a
              #       caller that names none.
              # WHY : Refactoring Rationale: this comment used to assert that the
              #       expected owner and the running execution "are always equal on
              #       this edge, because the chain reached here". That was read from
              #       the failure edge's gate rather than from this edge, which has no
              #       gate; an execution refused at the bracket ran the whole chain
              #       and then cleared a window it never owned. The refusal now lives
              #       in the function, so the guarantee holds on both edges without a
              #       second Choice state in front of a successful run.
              # WHY : Alternatives Considered: a CheckQuiesceLeaseOwnership twin in
              #       front of this state, mirroring the failure edge. Rejected because
              #       the two edges want different outcomes from the same answer -- the
              #       failure edge skips the release and fails, while this edge must
              #       still SUCCEED, since a batch that completed did complete -- so
              #       the twin would need its own success join and would put a branch
              #       in the graph to express what one empty string already expresses.
              "expectedLeaseOwner.$" = "$.quiesce.leaseOwner"

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
            Next        = "CheckQuiesceLeaseOwnership"
          }]
          Next = "CheckQuiesceLeaseOwnership"
        }

        # WHY : Refactoring Rationale: the failure path releases the bracket only
        #       when THIS execution acquired it. Every failure used to arrive at the
        #       release directly, including failures that occurred before the
        #       bracket was taken and failures of the quiesce call itself -- so an
        #       execution that never held the flag, or that was refused BECAUSE
        #       another owner held it, cleared it on the way out. That re-enables
        #       online writes inside another execution's write window, and does so
        #       silently, because clearing an already-clear flag and clearing
        #       someone else's are indistinguishable from the graph's point of view.
        # WHY : Assumptions: the presence tests come FIRST in the And and are not
        #       redundant. When the quiesce call itself failed, its catch handler
        #       wrote to $.failure and $.quiesce was never created, so a comparison
        #       reaching for $.quiesce.leaseOwner would raise a runtime error rather
        #       than evaluate false -- and a runtime error here would abandon the
        #       release decision altogether. Testing IsPresent ahead of the value is
        #       the documented way to make a Choice tolerate an absent path.
        # WHY : Assumptions: the owner is compared against $.executionName, which the
        #       preparation states copied out of the context object, rather than
        #       against $$.Execution.Name directly. A comparison operator's reference
        #       path resolves against the state input, so the context object is not
        #       addressable from one; putting the identity in the input at the top of
        #       the chain is what makes the comparison expressible.
        CheckQuiesceLeaseOwnership = {
          Type = "Choice"
          Choices = [{
            And = [
              {
                Variable  = "$.quiesce.leaseAcquired"
                IsPresent = true
              },
              {
                Variable      = "$.quiesce.leaseAcquired"
                BooleanEquals = true
              },
              {
                Variable  = "$.quiesce.leaseOwner"
                IsPresent = true
              },
              {
                Variable  = "$.executionName"
                IsPresent = true
              },
              {
                Variable         = "$.quiesce.leaseOwner"
                StringEqualsPath = "$.executionName"
              },
            ]
            Next = "ResumeOnlineWritesOnFailure"
          }]
          Default = "BatchFailed"
        }

        ResumeOnlineWritesOnFailure = {
          Type           = "Task"
          Resource       = "arn:${data.aws_partition.current.partition}:states:::lambda:invoke"
          TimeoutSeconds = local.state_timeouts.ResumeOnlineWrites
          Parameters = {
            FunctionName = var.resume_function_arn
            Payload = {
              action = "resume"

              # WHY : Assumptions: the failure path clears the SAME parameter as
              #       the success path, and naming it here rather than inheriting
              #       it is what keeps that true if the two ever diverge. This is
              #       the invocation that matters most: a chain that failed
              #       without clearing the flag it set would leave the online
              #       services read-only after the window ended.
              readOnlyFlagParameter = var.read_only_flag_parameter_name

              # WHY : Assumptions: the conditional-clear argument is mandatory here
              #       too, and this is the edge where it matters. The ownership gate
              #       above has already established that this execution holds the
              #       lease, so the two checks are belt and braces -- but they guard
              #       different windows: the gate reads state captured earlier in the
              #       execution, while the function reads the flag as it is at the
              #       moment of the write and declines to write when the bracket is
              #       no longer engaged.
              # WHY : Trade-offs: the function's half of that pair is bounded by what
              #       the flag can attest. It stores the boolean every online service
              #       reads and no owner, so "someone else re-took the bracket in
              #       between" is indistinguishable from "this execution still holds
              #       it". Recording the owner in the value was rejected for the
              #       readers it would break, and a companion owner parameter was
              #       rejected as a resource, a grant and an input to narrow a window
              #       this gate already covers.
              "expectedLeaseOwner.$" = "$.quiesce.leaseOwner"

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

    # WHY : Assumptions: a per-state timeout does not bound the execution as a
    #       whole, here for the same reason it does not bound the daily chain: an
    #       execution can stall between states or inside the service's own
    #       bookkeeping, where no single state's TimeoutSeconds applies. This
    #       ceiling exists to TERMINATE such an execution, and variables.tf
    #       derives its floor from the GenerateReports entry of the same override
    #       map the single work state below draws from, so it can never be set
    #       below the allowance that state already has.
    # WHY : Trade-offs: this ceiling is NOT catchable -- an execution terminated
    #       by it enters no further state, so NotifyAdHocFailure does not run and
    #       the caller learns the outcome from the execution's TIMED_OUT status
    #       rather than from the notification topic. That cost is accepted because
    #       the alternative is worse in kind: with no machine-level ceiling an
    #       on-demand report that hangs outside its work state runs until an
    #       operator notices, holding a Fargate task and a reporting query open
    #       indefinitely. A bounded execution that reports its own status beats an
    #       unbounded one that would have notified.
    # WHY : Refactoring Rationale: an intermediate revision REMOVED this ceiling,
    #       reasoning that a top-level timeout suppresses the notification state.
    #       It is restored because the premise it rested on -- that the daily
    #       chain's ceiling had been removed for that reason -- is not what the
    #       daily definition above does: that ceiling is kept, and the
    #       notification gap it leaves is answered outside the graph by
    #       aws_cloudwatch_event_rule.daily_finalizer rather than by deleting the
    #       bound. Removing this one instead left var.adhoc_report_timeout_seconds
    #       declared and consumed nowhere, which the HCL lint reports as an unused
    #       declaration and which left three statements in this module disagreeing
    #       about where the ad-hoc ceiling lives.
    TimeoutSeconds = var.adhoc_report_timeout_seconds

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
        # WHY : Assumptions: the ad-hoc report state borrows the GenerateReports
        #       ceiling rather than declaring one of its own, because it runs the
        #       same reporting image over the same query shape -- one report
        #       instead of the nightly set. The machine-level ceiling that bounds
        #       a whole on-demand execution is separate, is declared at the top of
        #       this definition, and is var.adhoc_report_timeout_seconds, whose
        #       validation holds it at or above the value indexed here so the
        #       execution can never expire while its only work state is still
        #       inside its own allowance.
        TimeoutSeconds = var.state_timeout_seconds["GenerateReports"]
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
  kms_key_id        = var.log_group_kms_key_arn

  tags = merge(var.tags, {
    Name = "${local.daily_machine_name}-logs"
  })
}

resource "aws_cloudwatch_log_group" "adhoc" {
  name              = "/aws/vendedlogs/states/${local.adhoc_machine_name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_group_kms_key_arn

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
    resources = var.pass_role_arns

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

  # WHY : Refactoring Rationale: this statement used to be a `dynamic` block gated
  #       on a `tracing_enabled` input. The input is gone and tracing is asserted
  #       instead: the cross-cutting observability requirement is that one trace
  #       spans the task and function invocations of a single execution, and dev
  #       and prod are permitted to differ in sizing and retention, not in whether
  #       the topology emits traces. The variable's own validation had in fact
  #       refused any value but true, so it was a switch that could only be left
  #       on -- and a switch that cannot be flipped is better expressed as a
  #       constant than as an input a reader has to look up to discover is fixed.
  #       Assumptions: the four X-Ray actions have no resource-level permission,
  #       which is why the Resource is a wildcard here while every other statement
  #       in this policy is ARN-scoped.
  statement {
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
    include_execution_data = var.log_include_execution_data
    level                  = var.log_level
    log_destination        = "${aws_cloudwatch_log_group.daily.arn}:*"
  }

  tracing_configuration {
    # WHY : Assumptions: asserted rather than parameterised, for the reason
    #       recorded on the X-Ray statement of the execution-role policy above.
    enabled = true
  }

  tags = merge(var.tags, {
    Name = local.daily_machine_name
  })

  depends_on = [aws_iam_role_policy.this]
}

# -----------------------------------------------------------------------------
# Quiesce-bracket finalizer
# -----------------------------------------------------------------------------
# Purpose:
#   Releases the online-write quiesce bracket for a daily execution that ended
#   WITHOUT reaching an in-graph release state.
#
# Parameters:
#   None of its own. It reuses the resume function, the read-only flag parameter
#   name and the retry knobs already declared for the graph.
#
# Return values:
#   None. Its effect is the resume function's own: the read-only flag is cleared
#   when this execution owns it.
#
# Exceptions or errors:
#   Delivery is retried by EventBridge to the limits set below, after which the
#   event is discarded. The in-graph release remains the primary path, so a
#   discarded event costs the difference between the two rather than the bracket.
#
# WHY : Refactoring Rationale: this exists because a top-level TimeoutSeconds
#       cannot release the bracket. An execution that hits its ceiling is
#       TERMINATED -- Step Functions enters no further state -- so
#       ResumeOnlineWritesOnFailure is unreachable on exactly the failure the
#       ceiling exists to catch, and the graph's own comment used to claim the
#       opposite. The same is true of an execution an operator STOPS, which
#       terminates it just as abruptly. Both are observable only from outside the
#       execution, which is why the release for them is wired to the execution's
#       own terminal status-change event.
# WHY : Alternatives Considered: a scheduled watchdog that periodically inspects
#       the flag's age and clears a stale one. Rejected because it needs a
#       staleness threshold, and any threshold is either shorter than a legitimate
#       long night -- in which case it re-enables writes mid-window -- or longer
#       than an operator is willing to wait. An event carries no threshold: it
#       fires when, and only when, the execution that took the bracket has
#       demonstrably ended.
# WHY : Alternatives Considered: a .sync callback or a second state machine
#       wrapping the first, so the wrapper's own catch could perform the release.
#       Rejected because the wrapper inherits the identical problem one level up:
#       its own ceiling terminates it the same way, so the release would still be
#       unreachable for the same class of failure and the graph would be twice as
#       large.
# WHY : Assumptions: the pattern matches FAILED as well as TIMED_OUT and ABORTED,
#       even though a FAILED execution normally reaches the in-graph release. The
#       in-graph release is itself a state, so it can fail: a chain whose
#       ResumeOnlineWritesOnFailure state errors ends FAILED with the bracket still
#       engaged, which is precisely the outage this rule exists to prevent. The
#       cost of covering it is that the ordinary failure path releases twice; the
#       release is idempotent -- it writes one SSM value -- so a second invocation
#       is a duplicate log line and nothing more, which is a far smaller cost than
#       the case it closes.
# WHY : Trade-offs: the event carries both the execution ARN and the execution
#       NAME, and the transformer passes both because they answer different
#       questions: the name is what the function logs and echoes, and the ARN is
#       what it compares against the lease the quiesce state recorded. An input
#       transformer maps whole values rather than deriving substrings, so passing
#       one and deriving the other in the function would put a parsing rule where
#       the event already supplies the parsed value.
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_event_rule" "daily_finalizer" {
  name        = "${local.name_stem}-batch-finalizer"
  description = "Releases the CardDemo online-write quiesce bracket from outside the execution, on every terminal status a daily batch execution can end in without having released it."

  # WHY : Assumptions: the pattern is scoped to THIS state machine's ARN and to
  #       the three non-succeeded terminal statuses. Omitting the ARN would fire
  #       the resume for every state machine in the account, and including
  #       SUCCEEDED would fire it for the one outcome whose own final state has
  #       already cleared the flag.
  event_pattern = jsonencode({
    source      = ["aws.states"]
    detail-type = ["Step Functions Execution Status Change"]
    detail = {
      status          = ["TIMED_OUT", "ABORTED", "FAILED"]
      stateMachineArn = [aws_sfn_state_machine.daily.arn]
    }
  })

  tags = merge(var.tags, {
    Name = "${local.name_stem}-batch-finalizer"
  })
}

resource "aws_cloudwatch_event_target" "daily_finalizer" {
  rule      = aws_cloudwatch_event_rule.daily_finalizer.name
  target_id = "resume-online-writes"
  arn       = var.resume_function_arn

  # WHY : Assumptions: the payload is built by an input transformer rather than
  #       forwarded whole, so the function receives the same shape on this path as
  #       on the two in-graph paths -- an action and the parameter to clear.
  #       Forwarding the raw event would give the function a second,
  #       differently-shaped input to parse for one caller.
  # WHY : Assumptions: this release is deliberately UNCONDITIONAL and carries no
  #       expectedLeaseOwner. The two in-graph edges name the owner so that a chain
  #       which never acquired the bracket cannot clear it; this rule exists for the
  #       opposite case -- an execution that DID acquire it and then stopped without
  #       releasing it -- so requiring an owner here would refuse exactly the
  #       invocation the rule was added for. The function reads an absent
  #       expectedLeaseOwner as "clear it", which is what a watchdog must mean.
  # WHY : Refactoring Rationale: an earlier revision passed the terminating
  #       execution's ARN as expectedLeaseOwnerArn. It was removed because nothing
  #       compared it: the function's conditional release reads expectedLeaseOwner
  #       and matches it against an execution NAME, so an ARN under a similar key
  #       advertised a check that could not run. terminalStatus and releasedBy remain
  #       because they are read where this rule is read -- in the rule definition and
  #       in the invocation record -- and claim no comparison.
  input_transformer {
    input_paths = {
      executionName = "$.detail.name"
      status        = "$.detail.status"
    }

    input_template = jsonencode({
      action                = "resume"
      readOnlyFlagParameter = var.read_only_flag_parameter_name
      finalizer             = true
      releasedBy            = "batch-finalizer"
      "executionName"       = "<executionName>"
      "terminalStatus"      = "<status>"
    })
  }

  # WHY : Assumptions: delivery is retried and then discarded rather than sent to a
  #       dead-letter queue. A queue would need a queue, a policy and an alarm in
  #       this module for a message whose only consumer is the very function that
  #       failed to accept it; the in-graph path already covers every failure that
  #       transitions, so what is at risk here is the difference between the two
  #       paths and not the bracket itself. The event age is bounded well below the
  #       nightly cadence so a discarded event cannot be delivered against the
  #       NEXT night's lease.
  retry_policy {
    maximum_retry_attempts       = var.retry_max_attempts
    maximum_event_age_in_seconds = 3600
  }
}

# WHY : Assumptions: EventBridge invokes a Lambda function through a resource-based
#       policy on the function and not through an assumed role, which is why this is
#       a permission and not another IAM role. The source ARN narrows it to this one
#       rule, so the grant does not admit every rule in the account.
resource "aws_lambda_permission" "daily_finalizer" {
  statement_id  = "AllowExecutionFromBatchFinalizerRule"
  action        = "lambda:InvokeFunction"
  function_name = var.resume_function_arn
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.daily_finalizer.arn
}

resource "aws_sfn_state_machine" "adhoc" {
  name     = local.adhoc_machine_name
  role_arn = aws_iam_role.this.arn
  type     = "STANDARD"

  definition = jsonencode(local.adhoc_definition)

  logging_configuration {
    include_execution_data = var.log_include_execution_data
    level                  = var.log_level
    log_destination        = "${aws_cloudwatch_log_group.adhoc.arn}:*"
  }

  tracing_configuration {
    # WHY : Assumptions: asserted rather than parameterised, for the reason
    #       recorded on the X-Ray statement of the execution-role policy above.
    enabled = true
  }

  tags = merge(var.tags, {
    Name = local.adhoc_machine_name
  })

  depends_on = [aws_iam_role_policy.this]
}

