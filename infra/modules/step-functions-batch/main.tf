# =============================================================================
# infra/modules/step-functions-batch/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions four STANDARD Step Functions workflows: the eleven-work-state
#   nightly CardDemo batch chain, the ad-hoc report workflow started by
#   reporting-service, the operator-invoked dataset export/import round trip, and
#   the operator-invoked authorization unload/load extract.
#   All four use one least-privilege execution role, encrypted CloudWatch
#   execution logs and X-Ray tracing. It also provisions the out-of-graph
#   finalizer that releases the online-write quiesce bracket for a daily execution
#   that was terminated rather than failed.
#
#   Collectively these four replace the mainframe's job stream: the nightly chain
#   replaces the JCL/JES2 batch cycle under app/jcl/, and the three on-demand
#   machines replace work an operator or a CICS user submitted by hand. The
#   baseline is not removed by any of this -- app/** continues to exist and run,
#   and every citation below is a reference to it, never a change to it.
#
#   Refactoring Rationale: the round-trip workflow is the third of the four, and
#   it exists because two batch jobs were unreachable. BatchApplication accepts
#   `--job=export` and `--job=import` and ExportJob and ImportJob register beans
#   under exactly those tokens, yet neither token appeared in any state machine,
#   schedule or API in this repository -- so both jobs could be built, tested and
#   deployed while remaining impossible to run. The pair is operator-submitted in
#   the baseline as well: app/jcl/CBEXPORT.jcl and app/jcl/CBIMPORT.jcl appear in
#   neither app/scheduler/CardDemo.ca7 nor app/scheduler/CardDemo.controlm, so an
#   on-demand machine is the faithful target rather than two more nightly states.
#
# Parameters:
#   variables.tf declares the ECS cluster and four task definitions -- batch,
#   data-migration, reporting and authorization -- private task placement,
#   passable roles, three Lambda functions, dataset bucket, notification topic,
#   per-state timeout and retry controls and observability settings.
#
#   The one parameter that is NOT a Terraform input is the business date. It is
#   execution input, named `businessDate` and formatted YYYY-MM-DD, supplied by
#   the eventbridge-scheduler payload; a `scheduledTime` ISO timestamp is accepted
#   as a fallback and reduced to its date part. Assumptions: this preserves
#   app/jcl/INTCALC.jcl:22, which runs PGM=CBACT04C with PARM='2022071800' rather
#   than letting the program read a clock -- an injected date is what makes a
#   rerun reproducible and a golden-master comparison meaningful. A Terraform
#   variable would freeze one date into the infrastructure, so the input contract
#   is asserted at run time instead and an execution that satisfies neither form
#   is refused rather than defaulted.
#
# Return values:
#   None here. outputs.tf publishes all four state-machine ARNs and names, the
#   execution role, all four log-group identities and the finalizer rule.
#   infra/modules/eventbridge-scheduler starts an execution of the daily machine,
#   services/reporting-service starts an execution of the ad-hoc machine, and
#   infra/modules/observability builds its dashboards and alarms from the ARNs.
#
# Exceptions or errors:
#   Every work state has an explicit timeout, retry and catch, and every machine
#   ALSO carries a top-level TimeoutSeconds -- var.state_machine_timeout_seconds
#   for the daily chain and one ceiling each for the three on-demand machines.
#   Assumptions: a top-level ceiling is a CEILING, not a prediction of how long a
#   run takes, and it is there because a per-state timeout cannot bound an
#   execution that stalls BETWEEN states. Trade-offs: the accepted cost is that
#   the ceiling is not catchable -- an execution it terminates is ended by the
#   service as TIMED_OUT without entering any further state, so no Catch and no
#   in-graph cleanup runs. That is precisely why the quiesce bracket is released
#   from OUTSIDE the graph as well; see the finalizer at the foot of this file.
#   Infrastructure faults reach the common notification path; container exit codes are
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
#     run predicate, so every condition code INVERTS on the way across rather
#     than being copied. Stated once here because it governs three distinct
#     baseline forms, each mapped where it occurs:
#       (a) COND=(0,NE) -- eight sites, at app/jcl/DEFGDGD.jcl:36, :47, :59 and
#           :82, app/jcl/CREASTMT.JCL:56, :66 and :79, and app/jcl/TXT2PDF1.JCL:26
#           (a job that retires with no target). It skips the step when zero does
#           NOT equal a prior code, so it RUNS only when every predecessor ended
#           cleanly. Target: the DEFAULT SUCCESS EDGE, with any non-zero code
#           taken by that state's Catch to NotifyFailure and then Fail. Modelling
#           it as a Choice would add a predicate for a condition the edge already
#           expresses.
#       (b) COND=(4,LT) -- exactly ONE site, app/jcl/TRANBKP.jcl:51. It skips
#           when four is LESS THAN the code, so it RUNS when the code is 4 or
#           lower. Target: the explicit CheckPostingExitCode Choice below, which
#           admits 4 as a warning rather than a failure. The full argument, and
#           why the predicate is exactly-0-or-exactly-4 rather than a "4 or
#           lower" ceiling, is at local.posting_warn_return_code.
#       (c) INCLUDE COND=(...) -- exactly ONE site, app/jcl/TRANREPT.jcl:47-48,
#           and it is NOT a step gate at all. It sits inside a DFSORT step and
#           selects RECORDS by an inclusive processing-date range. Target: a SQL
#           WHERE clause inside reporting-service. It is deliberately absent from
#           every state graph in this file; see the GenerateReports state.
#     The hazard the three share is the keyword: conflating a record predicate
#     with a step gate, or copying either sense unaltered, produces a chain that
#     plans and applies cleanly and is wrong only at run time.
#   - Assumptions: state 2 is a ten-item Map using data-migration, states 3-7
#     use batch-service, and states 8-9 plus ad-hoc reporting use
#     reporting-service. BatchApplication's verified seven-name list contains
#     the five daily batch jobs used here and contains no reporting job.
#   - Refactoring Rationale: restart is an IMPROVEMENT here, not a preserved
#     contract, and the direction matters. The baseline has no batch-restart
#     mechanism to port: the only RESTART= anywhere under app/ is COMMENTED OUT,
#     at app/jcl/DEFGDGD.jcl:2 (`//*  RESTART=STEP30`, carrying a leftover
#     JOB05067 sequence field), and CHKPT= -- the JCL checkpoint keyword --
#     appears ZERO times across the whole tree. What replaces it is a STANDARD
#     execution's redrive, which resumes from the failed state, plus the durable
#     batch.batch_run step ledger owned by batch-service, unique on
#     (run_id, step_name), so a redriven step that already completed is a no-op.
#     As an observation of an immutable baseline rather than an asserted defect:
#     had that RESTART=STEP30 been active it would have re-entered a job whose
#     earlier step had already defined a generation-data group, which is exactly
#     the idempotency problem the step ledger removes.
#   - Assumptions: one baseline job retires WITH an analogue rather than without
#     one. app/jcl/WAITSTEP.jcl:22 drives PGM=COBSWAIT, a step whose only purpose
#     is to make a job wait for the step before it. That is precisely what an edge
#     between two states already is here, so the wait is expressed by the graph's
#     own ordering and no state corresponds to it. Recorded because its absence
#     from the state list would otherwise look like an omission.
#   - Assumptions: what this module does NOT own, recorded once so nothing is
#     bolted on here that belongs to a sibling. The dataset bucket, its TEN
#     generation-dataset prefix families and the five-noncurrent-version
#     lifecycle rule that reproduces their LIMIT(5) belong to
#     infra/modules/s3-datasets -- six families defined at app/jcl/DEFGDGB.jcl:25,
#     :31, :37, :43, :49 and :55, three at app/jcl/DEFGDGD.jcl:28, :51 and :74,
#     and one at app/jcl/DALYREJS.jcl:24-26, every one at LIMIT(5) SCRATCH. This
#     module only consumes var.dataset_bucket_name and creates nothing inside it.
#     The nightly cron schedule belongs to infra/modules/eventbridge-scheduler,
#     which targets the daily ARN exported here. Dashboards, alarms and the
#     notification topic belong to infra/modules/observability; this module
#     creates only its own four log groups. The three Lambda functions arrive as
#     ARNs. Aurora, its schemas and the batch.batch_run table are elsewhere
#     entirely. The IAM grant that lets reporting-service call StartExecution on
#     the ad-hoc machine is wired by the environment root, not here.
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

# -----------------------------------------------------------------------------
# Data sources
# -----------------------------------------------------------------------------

# WHY : Assumptions: these three exist so that every ARN this module must BUILD is
#       composed rather than written. Two ARNs cannot come from a variable: the
#       EventBridge managed rule the synchronous run-task integration relies on has
#       a name AWS fixes, and the state-machine ARN prefix the trust policy matches
#       on describes machines this module is itself creating. Hard-coding either
#       would embed one account, one region and one partition in a module that both
#       environment roots share, which defeats having two roots that differ only in
#       sizing -- and an account identifier in a committed ARN is exactly what the
#       no-secrets-in-source constraint forbids. Composing from the caller's own
#       partition, region and identity makes the module correct in whichever of the
#       three it is applied to, including a non-commercial partition where the
#       `aws` literal is wrong.
# WHY : Alternatives Considered: accepting the account identifier and region as
#       input variables instead. Rejected because the values are already knowable
#       from the provider the calling root configured, so an input would let a
#       caller pass a region that disagrees with the provider's and produce a policy
#       that references resources in one region while the machines are created in
#       another -- a mismatch that plans and applies cleanly and denies at run time.
data "aws_partition" "current" {}
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

# -----------------------------------------------------------------------------
# Locals
# -----------------------------------------------------------------------------

locals {
  name_stem                = "${var.name_prefix}-${var.environment}"
  daily_machine_name       = "${local.name_stem}-daily-batch"
  adhoc_machine_name       = "${local.name_stem}-adhoc-report"
  dataset_machine_name     = "${local.name_stem}-dataset-roundtrip"
  authz_machine_name       = "${local.name_stem}-authorization-extract"
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

  # WHY : Assumptions: AssignPublicIp is DISABLED as a literal rather than an input.
  #       Every task these machines start reaches Aurora, the queues, Secrets
  #       Manager, KMS, ECR, CloudWatch Logs and Step Functions itself through the
  #       interface VPC endpoints infra/modules/network provisions, and reaches
  #       anything genuinely off-VPC through that module's NAT gateway, so a public
  #       address on the task ENI is never the path any of its traffic takes. Making
  #       it configurable would offer one setting whose only other value widens the
  #       task's exposure while enabling nothing, and both environment roots are
  #       required to differ in sizing rather than in topology.
  # WHY : Assumptions: one shared network block for every task state in all four
  #       machines. The alternative -- per-state subnets or security groups -- would
  #       let one step in the chain reach something its neighbours cannot, and there
  #       is no step here whose data access differs in kind: they all read and write
  #       the same Aurora cluster and the same dataset bucket.
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
  #       admitted 1, 2 and 3 as warnings, and app/cbl/CBTRN02C.cbl:229-230 assigns
  #       RETURN-CODE in exactly one place -- IF WS-REJECT-COUNT > 0 at :229
  #       guarding MOVE 4 TO RETURN-CODE at :230
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
  #       The producer and the consumer sit at opposite ends of the baseline:
  #       app/cbl/CBTRN02C.cbl:229-230 PRODUCES the 4 when the reject count is
  #       positive, and app/jcl/TRANBKP.jcl:51 CONSUMES it by still running its
  #       step. Treating 4 as a failure here is therefore not a conservative
  #       choice, it is a wrong one: a reject is a normal operating condition, so
  #       every night that posted correctly but rejected at least one transaction
  #       would be reported as a failed batch run, and the interest, backup,
  #       combine, statement and report states that the baseline does run would
  #       all be skipped. That is the specific false alarm this pair prevents.
  # WHY : Assumptions: what TRANBKP.jcl:51 gates is a safety interlock, which is
  #       why the warn tier has to reach it rather than merely be tolerated. Its
  #       STEP05R at :23 unloads the transaction master to TRANSACT.BKUP(+1)
  #       (:33, LRECL=350 RECFM=FB), its STEP05 at :37 DELETEs the cluster and its
  #       alternate index, and the gated STEP10 at :51 DEFINEs the master again --
  #       so an empty master is never re-created unless the backup and the delete
  #       both went acceptably. The two IF MAXCC LE 08 THEN SET MAXCC = 0 lines at
  #       :42 and :45 are what normalise a not-found DELETE down to zero so the
  #       "4 or lower" test can pass at all, which is why the target's own
  #       drop-if-exists behaviour must likewise be non-fatal and idempotent.
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

  # WHY : Assumptions: these five tokens are VERIFIED against batch-service's own
  #       BatchJobName enum rather than inferred from the state names --
  #       preflight-daily-transactions, post-transactions, calculate-interest,
  #       backup-transactions and combine-transactions are five of the seven
  #       constants it declares. The other two, export and import, are reachable
  #       only through the dataset round-trip machine further down this file. A
  #       token that does not match that enum is not a plan-time error: the
  #       container starts, fails to resolve the job and exits non-zero, so the
  #       chain reports a failed step for what is really a spelling mistake.
  # WHY : Assumptions: each entry's `next` names an exit-code Choice rather than
  #       the following work state, because the synchronous run-task integration
  #       does NOT throw on a non-zero container exit -- it returns the exit code
  #       in the task envelope. Wiring one work state straight to the next would
  #       run the whole chain over a failed step's output.
  # WHY : Refactoring Rationale: one JCL job per state, and the state ORDER carries
  #       what each job's DD statements and generation references used to. The
  #       lineage, job by job:
  #         PreflightDailyTransactions <- CBTRN01C, which has NO JCL driver
  #           anywhere in the baseline: it appears in no app/jcl file and in no
  #           app/scheduler definition, and the EXEC PGM= census across app/jcl
  #           names CBTRN03C, CBTRN02C, CBSTM03A, CBIMPORT, CBEXPORT, CBCUS01C,
  #           CBACT04C, CBACT03C, CBACT02C and CBACT01C but never CBTRN01C. Its
  #           only exercised contract is tests/integration/test_cbtrn01c_prepost.py.
  #           Assumptions: this state exists because the PROGRAM exists and that
  #           test defines its behaviour, not because a job card was ported.
  #         PostTransactions <- app/jcl/POSTTRAN.jcl:23, //STEP15 EXEC PGM=CBTRN02C,
  #           with nine DD statements (STEPLIB :24, SYSPRINT :26, SYSOUT :27,
  #           TRANFILE :28, DALYTRAN :30, XREFFILE :32, DALYREJS :34, ACCTFILE :39,
  #           TCATBALF :41). The reject stream at :34 is DISP=(NEW,CATLG,DELETE)
  #           with DCB=(RECFM=F,LRECL=430,BLKSIZE=0) writing DALYREJS(+1) at :38 --
  #           fixed UNBLOCKED at 430 bytes, and a NEW generation every run, which
  #           is why a rerun never appends to a previous night's rejects.
  #         CalculateInterest <- app/jcl/INTCALC.jcl:22,
  #           //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'. It reads the
  #           cross-reference through TWO paths, XREFFILE :29 and XREFFIL1 :31, the
  #           second being the alternate-index PATH, and writes its generated
  #           interest transactions to a SEPARATE generation: the DD is named
  #           TRANSACT at :37 but the dataset is SYSTRAN(+1) at :41,
  #           DCB=(RECFM=F,LRECL=350,BLKSIZE=0). That separation is what state 7
  #           later depends on.
  #         BackupTransactions <- app/jcl/TRANBKP.jcl:23-33, unloading the master
  #           to TRANSACT.BKUP(+1) at LRECL=350 RECFM=FB.
  #         CombineTransactions <- app/jcl/COMBTRAN.jcl, whose STEP05R at :22 sorts
  #           a CONCATENATED SORTIN of TRANSACT.BKUP(0) at :24 and SYSTRAN(0) at
  #           :26 on TRAN-ID (:30) into TRANSACT.COMBINED(+1) at :37, then REPROs
  #           it back into the master at :48. Assumptions: those two (0) references
  #           read the generations states 5 and 6 have just created, which is the
  #           whole reason this state is ordered after BOTH of them rather than
  #           beside either. Neither COMBTRAN step carries a COND=, so both take
  #           the default success edge per form (a) in the header.
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

  # WHY : Assumptions: every `Command.$` in this file is built with States.Array so it
  #       resolves to a JSON ARRAY OF DISCRETE ARGUMENT STRINGS, and that is a
  #       requirement of the container contract rather than a formatting preference.
  #       services/batch-service/Dockerfile uses an EXEC-FORM ENTRYPOINT, and an ECS
  #       command override replaces CMD and is appended after that entrypoint, so each
  #       element arrives as one argv entry. A single space-joined string
  #       ("--job=x --business-date=y") would arrive as ONE argument, which the
  #       argument parser rejects because no such option exists. States.Format is
  #       nested inside the array rather than wrapped around it for the same reason:
  #       it interpolates the date INTO one token and does not join tokens together.
  # WHY : Alternatives Considered: wrapping the command in `sh -c "<joined string>"`,
  #       which would make the joined form work. Rejected because it inserts a shell
  #       as PID 1: the shell, not the JVM, then receives SIGTERM when ECS drains the
  #       task, and a non-exec shell does not forward it, so the job loses its
  #       shutdown hook and its chance to finish the chunk in flight. It would also
  #       put job arguments through shell word-splitting for no gain, since the array
  #       form already passes them exactly.
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

  # WHY : Assumptions: these two tokens are VERIFIED against reporting-service, not
  #       derived from the state names. GenerateStatementsTask and
  #       GenerateReportsTask declare generate-statements and generate-reports, and
  #       ReportingTaskRunner dispatches on exactly those two plus generate-report,
  #       which the ad-hoc machine below uses. They live in reporting-service and
  #       NOT in batch-service because batch-service's BatchJobName enum has no
  #       statement job and no report job at all -- which is why states 8 and 9
  #       run a different task definition from states 3 through 7. If a token ever
  #       stops matching, the correction belongs on this side, against that
  #       service's actual argument surface, rather than in a new convention.
  # WHY : Refactoring Rationale: GenerateStatements replaces app/jcl/CREASTMT.JCL,
  #       whose five steps include THREE carrying COND=(0,NE) -- STEP020 at :56,
  #       STEP030 at :66 and STEP040 at :79 -- all three of which invert to the
  #       default success edge per form (a) in the header block. The step produces
  #       TWO artifacts, not one: STMTFILE at :87-91,
  #       DCB=(LRECL=80,BLKSIZE=8000,RECFM=FB), the plain-text statement, and
  #       HTMLFILE at :92-96, DCB=(LRECL=100,BLKSIZE=800,RECFM=FB), the HTML one.
  # WHY : Assumptions: a statement rerun REPLACES and never appends, which is why
  #       this state writes a new generation prefix instead of opening an existing
  #       object for append. The baseline expresses it in two steps: STEP030 at :66
  #       runs IEFBR14 with DISP=(MOD,DELETE,DELETE) purely to delete the previous
  #       run's two outputs, and STEP040 at :79 then creates them fresh with
  #       DISP=(NEW,CATLG,DELETE). Recorded as observations of an immutable
  #       baseline rather than asserted defects, two artifacts of that job are
  #       visible while reading it and neither is reproduced here: overlapping
  #       corrupted text at :90, and the HTML dataset declared at LRECL=80 in
  #       STEP030 against LRECL=100 at :94.
  # WHY : Refactoring Rationale: GenerateReports replaces app/jcl/TRANREPT.jcl,
  #       whose STEP10R at :59 runs PGM=CBTRN03C and writes TRANREPT(+1) at :80
  #       with DCB=(LRECL=133,RECFM=FB,BLKSIZE=0) -- the 133-column report --
  #       together with app/jcl/PRTCATBL.jcl, a DFSORT-only job with no COBOL
  #       program that builds the category-balance report from TCATBALF.BKUP(+1).
  #       The ordering gain is real and worth naming: TRANREPT.jcl RE-DOES the very
  #       same REPROC unload that TRANBKP.jcl performs, both writing
  #       TRANSACT.BKUP(+1), because each JCL job is written to be self-contained.
  #       This chain unloads ONCE, in state 6, and states 7 and 9 read that one
  #       generation -- less work for byte-identical output.
  # WHY : Assumptions: the inclusive date range that app/jcl/TRANREPT.jcl:47-48
  #       expresses as INCLUDE COND=(TRAN-PROC-DT,GE,PARM-START-DATE,AND,
  #       TRAN-PROC-DT,LE,PARM-END-DATE) is deliberately ABSENT from this state
  #       graph. It is a RECORD-selection predicate inside a DFSORT step, not a
  #       step gate, so it belongs in reporting-service's SQL WHERE clause and is
  #       carried there. Modelling it as a Choice state is the specific mistake to
  #       avoid: the two forms share the COND keyword, and a Choice can only admit
  #       or reject a whole step, so it would either run the report over every
  #       transaction ever recorded or skip it entirely. Its bounds are job
  #       parameters in the baseline too -- DFSORT SYMNAMES inject
  #       PARM-START-DATE at :43 and PARM-END-DATE at :44 -- which is why the
  #       ad-hoc machine below passes them as --start-date and --end-date rather
  #       than embedding a range. As an observation of an immutable baseline, that
  #       job also sorts on the single key TRAN-CARD-NUM at :46 and declares two
  #       steps both named STEP05R, at :23 and :37.
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

  # WHY : Refactoring Rationale: reporting-service is the ONE workload whose task
  #       definition is both a long-running ECS service and a task this machine
  #       starts directly, and that duality costs it its metrics unless they are
  #       pushed per run. ecs-service supplies Micrometer's OTLP registry settings
  #       only to workloads it creates no service for -- batch and data-migration --
  #       because a serving task publishes /actuator/prometheus and the collector
  #       sidecar scrapes it, and enabling both paths on one task definition would
  #       export every meter twice. A reporting run started here has no listener to
  #       scrape: the command switches the container into a one-shot job, so the
  #       scrape half of that arrangement is absent and, without these three
  #       overrides, the statement and report steps would export spans and not one
  #       counter or timer. Supplying them as a container override rather than in the
  #       task definition keeps the double-export impossible, because they exist only
  #       for the lifetime of a run this machine starts.
  # WHY : Assumptions: the collector sidecar listens on loopback inside the task's
  #       own network namespace, so 127.0.0.1:4318 needs no service discovery and
  #       leaves the task on no port. ecs-service adds that sidecar to every
  #       workload by default and neither environment root disables it; were a root
  #       ever to disable it for reporting, these pushes would be refused on loopback
  #       and logged by Micrometer, which is a bounded warning rather than a failed
  #       step -- the alternative, gating the overrides on a new module input, would
  #       add a second place for the two settings to disagree.
  # WHY : Trade-offs: the fifteen-second step matches what ecs-service gives the
  #       other task-mode workloads and for the same reason -- the registry's own
  #       default publishes once a minute, and a report that finishes inside that
  #       window would exit having exported nothing, because the shutdown flush is
  #       best effort. Four times the export volume for a nightly job is negligible
  #       against losing its metrics entirely.
  reporting_metrics_push_environment = [
    {
      Name  = "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED"
      Value = "true"
    },
    {
      Name  = "MANAGEMENT_OTLP_METRICS_EXPORT_STEP"
      Value = "15s"
    },
    {
      Name  = "MANAGEMENT_OTLP_METRICS_EXPORT_URL"
      Value = "http://127.0.0.1:4318/v1/metrics"
    },
  ]

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
            Environment = concat([
              {
                Name      = "CARDDEMO_BATCH_RUN_ID"
                "Value.$" = "$$.Execution.Name"
              },
              {
                Name  = "CARDDEMO_DATASET_BUCKET"
                Value = var.dataset_bucket_name
              },
            ], local.reporting_metrics_push_environment)
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
    #       reachable, and the flag this chain set would stay set. The same holds
    #       for an operator ABORT, which no in-execution Catch can observe either.
    #       Relying on the ceiling meant that the one failure mode the bracket
    #       exists to survive -- a chain that
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
        # WHY : Assumptions: the business date is a PARAMETER of the run, supplied by
        #       the eventbridge-scheduler payload, and this state asserts that contract
        #       before anything else happens. It preserves app/jcl/INTCALC.jcl:22,
        #       which runs PGM=CBACT04C with PARM='2022071800' rather than letting the
        #       program read a clock -- an injected date is what makes a rerun produce
        #       the same output and so what makes a golden-master comparison mean
        #       anything. Two forms are accepted because two callers exist: an explicit
        #       businessDate for an operator or a replay, and a scheduledTime ISO
        #       timestamp reduced to its date part for the scheduler, whose payload
        #       carries the fire time rather than a business date.
        # WHY : Alternatives Considered: defaulting a missing input to the current date.
        #       Rejected outright: it is the one failure mode that produces a COMPLETE
        #       AND PLAUSIBLE result that is silently wrong. A replay of last night's
        #       chain would post, accrue interest over and report on today instead, and
        #       nothing in the output would say so. Failing fast to
        #       InvalidExecutionInput makes the misconfiguration visible at the only
        #       point where it is still cheap to correct.
        # WHY : Trade-offs: the shape checks are StringMatches wildcards rather than a
        #       real date parse, because ASL has no date type and a Choice cannot call
        #       out to one. They therefore catch a missing, empty or wrongly shaped
        #       value but would admit a well-shaped impossible date; the container's own
        #       parser rejects that, one state later, with the offending value in its
        #       message. Accepted because the check exists to stop an UNSET input from
        #       reaching the chain, which is the mistake that actually happens.
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

        # WHY : Refactoring Rationale: this replaces app/jcl/CLOSEFIL.jcl, whose step
        #       CLCIFIL at :22 runs EXEC PGM=SDSF and whose ISFIN DD at :25 issues
        #       FIVE operator commands -- /F CICSAWSA,'CEMT SET FIL(x ) CLO' at :26
        #       through :30 over TRANSACT, CCXREF, ACCTDAT, CXACAIX and USRSEC. The
        #       SDSF MECHANISM retires: there is no operator console to drive and no
        #       CICS region to address. What is preserved is the BEHAVIOUR, as a
        #       read-only flag the online services honour.
        # WHY : Assumptions: a flag rather than a hard lock is the faithful analogue,
        #       and the baseline's own scope is the evidence. That bracket closes five
        #       files, not the eight app/csd/CARDDEMO.CSD defines, because it is
        #       scoped to the WRITE path -- the read-only unload jobs that run inside
        #       the window open their inputs shared and would break under a real
        #       exclusive lock. A flag reproduces exactly that: writes are refused,
        #       reads continue.
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
          Next           = "CheckQuiesceLeaseAcquired"
        }

        # WHY : Refactoring Rationale: the quiesce state used to continue STRAIGHT to
        #       StageSeedDatasets, whether or not it had acquired the bracket. The lease
        #       was therefore computed, reported and then ignored on the success edge --
        #       the only place the graph consulted it was the FAILURE edge, at
        #       CheckQuiesceLeaseOwnership below, which decided whether to release it.
        #       So an execution starting while a previous night still held the window went
        #       on to post transactions and accrue interest with online writes enabled,
        #       which is the exact condition the bracket exists to prevent. This state is
        #       what makes the lease decide whether the chain runs at all.
        # WHY : Alternatives Considered: a Wait-and-retry loop instead of failing. Rejected
        #       because the schedule starts one execution per night, so a refused
        #       acquisition means the PREVIOUS night is still running -- waiting would
        #       stack a second full chain behind a run that is already overdue and hide
        #       that fact, where failing surfaces it while the first execution keeps the
        #       bracket it legitimately holds.
        CheckQuiesceLeaseAcquired = {
          Type = "Choice"
          Choices = [{
            # Assumptions: IsPresent is tested before BooleanEquals, matching
            #   CheckQuiesceLeaseOwnership below. An absent path in a Choice comparison is a
            #   runtime error rather than a false, so the guard is what keeps a malformed
            #   lambda result a routed failure instead of an unhandled States.Runtime error.
            And = [
              {
                Variable  = "$.quiesce.leaseAcquired"
                IsPresent = true
              },
              {
                Variable      = "$.quiesce.leaseAcquired"
                BooleanEquals = true
              },
            ]
            Next = "StageSeedDatasets"
          }]
          Default = "OnlineWriteLeaseUnavailable"
        }

        # WHY : Assumptions: this failure path does NOT pass through any resume state, and
        #       that is the whole point of it. Reaching here means another execution owns
        #       the bracket, so clearing the flag would re-enable online writes underneath
        #       a run that is still inside its window -- the very outcome the conditional
        #       release was added to refuse. Ending the execution leaves the owner's
        #       bracket exactly as it found it.
        OnlineWriteLeaseUnavailable = {
          Type  = "Fail"
          Error = "OnlineWriteLeaseUnavailable"
          Cause = "Another execution holds the online-write bracket; this execution stopped without staging or posting anything, and without touching the other execution's lease"
        }

        # WHY : Refactoring Rationale: this replaces the baseline's master-refresh
        #       block, which is TEN separate load jobs rather than one step --
        #       app/jcl/ACCTFILE.jcl, CARDFILE.jcl, CUSTFILE.jcl, XREFFILE.jcl,
        #       TRANFILE.jcl, DISCGRP.jcl, TCATBALF.jcl, TRANTYPE.jcl, TRANCATG.jcl
        #       and DUSRSECJ.jcl. Each one runs the same IDCAMS shape, DELETE then
        #       DEFINE CLUSTER then REPRO, adding BLDINDEX where the cluster carries
        #       an alternate index. A Map over one list expresses ten instances of a
        #       single operation more honestly than ten near-identical states, and it
        #       keeps the dataset list an input (var.seed_datasets) instead of ten
        #       pieces of graph.
        # WHY : Assumptions: the Map branches take the DEFAULT SUCCESS EDGE, per form
        #       (a) in the header block, because NONE of those ten jobs carries a
        #       COND= parameter at all -- there is no baseline gate on any of them to
        #       invert. A non-zero branch is therefore an infrastructure or data
        #       fault, which the branch's Catch surfaces rather than a condition code
        #       the chain was meant to interpret.
        # WHY : Trade-offs: MaxConcurrency is an input rather than unbounded. Ten
        #       branches at once would each open its own Aurora connection and its own
        #       bulk-copy stream against the cluster this same chain is about to post
        #       through, so the ceiling exists to bound the concurrent load on one
        #       writer rather than to bound Fargate. Setting it to 1 is legal and
        #       makes the refresh strictly sequential, which is what the baseline's
        #       ten separate jobs actually did.
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
                          # WHY : Assumptions: this is not only a logging correlator here. The
                          #       staging command keys its GENERATION RESERVATION on this value,
                          #       so it is what makes a retried Map branch reuse the generation
                          #       its first attempt reserved instead of consuming a second one
                          #       for a byte-identical copy. The execution name is the right
                          #       identity for that because Step Functions holds it constant
                          #       across a redrive, which is precisely the case that must
                          #       replay rather than reallocate.
                          Name      = "CARDDEMO_BATCH_RUN_ID"
                          "Value.$" = "$$.Execution.Name"
                        },
                        {
                          Name  = "CARDDEMO_DATASET_BUCKET"
                          Value = var.dataset_bucket_name
                        },
                        {
                          # WHY : Refactoring Rationale: this variable completes the staging
                          #       contract, and without it the branch could not run. The command
                          #       receives only a dataset token and a business date -- it resolves
                          #       the domain, the prefix segment, the source file name, the record
                          #       geometry and the retention limit from its own seed-dataset
                          #       registry -- but a file NAME still has to be found somewhere.
                          #       The image deliberately ships no extract (its Dockerfile copies
                          #       only src/ and sql/), so the directory has to come from the
                          #       deployment, and this is where it is stated.
                          # WHY : Alternatives Considered: baking the extracts into the image was
                          #       rejected because it would put a copy of the baseline data in
                          #       every published image layer and make refreshing an extract an
                          #       image rebuild. Passing an S3 URI and having the command fetch
                          #       was rejected as the wider change: the staging step already
                          #       writes to S3, and giving it a second, read side would duplicate
                          #       the transfer discipline -- the single held descriptor, the
                          #       digest, the geometry check -- against a different source kind.
                          #       A filesystem path keeps one code path for the read.
                          Name  = "CARDDEMO_DATASET_STAGING_ROOT"
                          Value = var.dataset_staging_root
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

          # WHY : Assumptions: ResultPath is null so the Map DISCARDS its per-branch
          #       output instead of writing it into the execution state. Each branch
          #       returns a task envelope, and ten of those replacing or nesting under
          #       the state object would push businessDate out of the path every
          #       following state reads it from. Nothing downstream consumes which
          #       datasets were staged -- a failed branch has already failed the Map --
          #       so the ten envelopes are cost without a reader.
          ResultPath = null

          # WHY : Trade-offs: the Map carries a Catch and a TimeoutSeconds but
          #       deliberately NO Retry of its own, while the branch state inside it
          #       does. A Retry here would re-enter the Map as a whole and re-run all
          #       ten datasets because one of them failed transiently, redoing nine
          #       successful DELETE/DEFINE/REPRO refreshes to recover one; retrying
          #       inside the branch retries only the dataset that faulted. The cost
          #       accepted is that a fault in the Map's own orchestration -- as opposed
          #       to in a branch -- is not retried at all, and goes straight to the
          #       Catch. That is the right way round: a branch fault is the transient
          #       one, and this state's own timeout still bounds the whole fan-out.
          Catch = local.common_catch
          Next  = "PreflightDailyTransactions"
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

        # WHY : Refactoring Rationale: this replaces app/jcl/TRANIDX.jcl, whose three
        #       IDCAMS steps are STEP20 at :22, DEFINE ALTERNATEINDEX at :25 with
        #       KEYS(26 304), NONUNIQUEKEY, UPGRADE and RECORDSIZE(350,350) at :27-30;
        #       STEP25 at :39, DEFINE PATH at :42; and STEP30 at :49, BLDINDEX at :52.
        #       All three retire, because PostgreSQL maintains an index inside the same
        #       transaction as the write that affects it, so there is no build to
        #       schedule and no window in which the index is stale.
        # WHY : Assumptions: the distinction a reader could easily get wrong is that
        #       the INDEX is not dropped -- only its imperative REBUILD retires. The
        #       equivalent of that alternate index is declared permanently in
        #       transaction-service's own Flyway migration, over the same columns the
        #       KEYS(26 304) offsets address. What survives as this state is the
        #       remaining half of TRANIDX.jcl's intent: refreshing the planner
        #       statistics that a night of bulk posting has just invalidated, which is
        #       real work with no baseline analogue in the index build itself.
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

        # WHY : Refactoring Rationale: this replaces app/jcl/OPENFIL.jcl, whose step
        #       OPCIFIL at :22 issues the mirror image of the close bracket -- the same
        #       five /F CICSAWSA,'CEMT SET FIL(x ) OPE' commands at :26 through :30
        #       over TRANSACT, CCXREF, ACCTDAT, CXACAIX and USRSEC. As with the
        #       quiesce, the SDSF mechanism retires and the behaviour is preserved by
        #       clearing the read-only flag.
        # WHY : Trade-offs: releasing the bracket has to be reachable on the FAILURE
        #       path too, or one bad night leaves online writes refused until an
        #       operator intervenes. That is accepted as needing THREE release points
        #       rather than one, because no single one covers every way an execution
        #       can end: this state on success, ResumeOnlineWritesOnFailure on a
        #       caught failure, and aws_cloudwatch_event_rule.daily_finalizer from
        #       outside the graph for an execution that was TERMINATED -- by its own
        #       ceiling or by an operator -- and so entered no further state at all.
        #       The cost is that the release logic is expressed three times over and
        #       must stay consistent; the alternative, a single in-graph release, is
        #       provably unreachable in the terminated case.
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
              Environment = concat([
                {
                  Name      = "CARDDEMO_BATCH_RUN_ID"
                  "Value.$" = "$$.Execution.Name"
                },
                {
                  Name  = "CARDDEMO_DATASET_BUCKET"
                  Value = var.dataset_bucket_name
                },
              ], local.reporting_metrics_push_environment)
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

  # ---------------------------------------------------------------------------
  # Operator-invoked dataset round trip: export then import
  # ---------------------------------------------------------------------------
  # WHY : Refactoring Rationale: this definition is NEW, and it closes a gap in
  #       which two jobs existed with no way to run them. BatchApplication accepts
  #       `--job=export` and `--job=import`, ExportJob and ImportJob register beans
  #       under exactly those tokens, and yet neither token appeared in
  #       local.batch_jobs, in local.reporting_jobs or in the ad-hoc definition --
  #       so nothing in this repository could start either job in a provisioned
  #       environment. That is not a scheduling decision: app/jcl/CBEXPORT.jcl and
  #       app/jcl/CBIMPORT.jcl are both operator-submitted rather than driven by the
  #       nightly scheduler definitions in app/scheduler, so the correct target is an
  #       on-demand state machine, exactly as the ad-hoc report above is.
  # WHY : Alternatives Considered: three other placements, all rejected.
  #       (a) Two more states inside the daily chain. Rejected because it changes
  #           WHEN the pair runs: the reference submits both by hand, neither appears
  #           in app/scheduler/CardDemo.ca7 or app/scheduler/CardDemo.controlm, and
  #           adding them to the nightly graph would export a full five-master
  #           extract every night whether or not anyone asked for one.
  #       (b) Two more states in the ad-hoc report machine. Rejected because that
  #           machine's input contract is a report request -- startDate, endDate and
  #           reportType -- and the round trip needs only a business date, so the two
  #           would have to share a validator that accepted the union of both shapes
  #           and therefore validated neither.
  #       (c) A bare `runTask` from an operator's shell, with no state machine.
  #           Rejected because the export must succeed before the import runs, and a
  #           shell sequence has no retry, no per-step ceiling, no exit-code gate and
  #           no execution history -- so an import over a half-written dataset would
  #           be indistinguishable from a clean round trip.
  # WHY : Assumptions: the two states run SEQUENTIALLY and the import is gated on the
  #       export's exit code, because the import reads exactly the object the export
  #       writes -- export/<yyyymmdd00>/export.dat, composed identically by
  #       ExportJob and ImportJob from the same business date. Running them in
  #       parallel, or letting the import follow a non-zero export, would read a
  #       partial or absent dataset and report the six artefacts it produced as
  #       complete.
  dataset_roundtrip_definition = {
    Comment = "CardDemo operator-invoked dataset export/import round trip"

    # WHY : Assumptions: the machine-level ceiling is separate from the two state
    #       ceilings for the reason the ad-hoc definition above records: a per-state
    #       TimeoutSeconds does not bound an execution that stalls BETWEEN states or
    #       inside the service's own bookkeeping. Its validation holds it at or above
    #       the SUM of the two work-state ceilings, because the two run in sequence,
    #       so the execution can never expire while either state is still inside its
    #       own allowance.
    TimeoutSeconds = var.dataset_roundtrip_timeout_seconds

    StartAt = "ValidateDatasetRequest"
    States = {
      # WHY : Assumptions: the input is validated in the GRAPH rather than left to
      #       the job, and the shape checked is the same one the daily chain's
      #       entry receives -- a present businessDate matching ????-??-??. The job
      #       does validate it again, through the shared job-parameter validator
      #       and then through BusinessDate's own width check, and that second
      #       check is not redundant: a graph check refuses a malformed request
      #       WITHOUT starting a Fargate task, so the operator gets an immediate
      #       InvalidDatasetRequest instead of paying a task start-up to be told
      #       the same thing by a stack trace.
      # WHY : Trade-offs: StringMatches checks the SHAPE and not the calendar, so
      #       2022-13-45 passes here and is refused by BusinessDate. Expressing a
      #       real date check in Amazon States Language would need either a
      #       validating Lambda -- a function, a role and a log group for one
      #       string test -- or a wall of Choice rules per field. The shape test
      #       catches the mistakes an operator actually makes at the command line,
      #       a transposed or truncated token, and the job catches the rest.
      ValidateDatasetRequest = {
        Type = "Choice"
        Choices = [{
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
          Next = "ExportDataset"
        }]
        Default = "InvalidDatasetRequest"
      }

      InvalidDatasetRequest = {
        Type = "Pass"
        Result = {
          error   = "InvalidDatasetRequest"
          message = "Supply businessDate as YYYY-MM-DD"
        }
        ResultPath = "$.failure"
        Next       = "NotifyDatasetFailure"
      }

      ExportDataset = {
        Type           = "Task"
        Resource       = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
        TimeoutSeconds = var.dataset_state_timeout_seconds["ExportDataset"]
        Parameters = {
          Cluster              = var.ecs_cluster_arn
          TaskDefinition       = var.batch_task_definition_arn
          LaunchType           = "FARGATE"
          NetworkConfiguration = local.network_configuration
          Overrides = {
            ContainerOverrides = [{
              Name        = var.batch_container_name
              "Command.$" = "States.Array('--job=export', States.Format('--business-date={}', $.businessDate))"
              Environment = [
                # WHY : Assumptions: the run identifier is the EXECUTION NAME, the
                #       same binding every state of the daily chain uses, and here
                #       it is the whole of the idempotency story. BatchStepLedger
                #       keys on (runId, stepName) over batch.batch_run, so a
                #       re-invocation carrying the same execution name finds the
                #       step already recorded and replays its outcome instead of
                #       exporting a second time -- and for the import that matters
                #       more than for any other job in the module, because a second
                #       body run would append a second copy of every record to all
                #       six artefacts. The operator convention that makes the name
                #       repeatable is documented in
                #       docs/runbooks/batch-operations.md; Step Functions
                #       independently refuses a duplicate execution name on a
                #       STANDARD machine, so a repeat is refused before it starts
                #       and recognised by the ledger if it ever does.
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
        ResultPath     = "$.export"
        Retry          = local.ecs_retry
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.failure"
          Next        = "NotifyDatasetFailure"
        }]
        Next = "CheckExportExitCode"
      }

      # WHY : Assumptions: the exit code is checked in a SEPARATE Choice state
      #       rather than folded into the task's Catch, because a non-zero container
      #       exit does not raise an ECS API error -- runTask.sync completes
      #       successfully and reports the code in its result. This is the same
      #       two-state shape every work state of the daily chain uses, and the
      #       reason the result selector exists.
      CheckExportExitCode = {
        Type = "Choice"
        Choices = [{
          Variable      = "$.export.exitCode"
          NumericEquals = 0
          Next          = "ImportDataset"
        }]
        Default = "NotifyDatasetFailure"
      }

      ImportDataset = {
        Type           = "Task"
        Resource       = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
        TimeoutSeconds = var.dataset_state_timeout_seconds["ImportDataset"]
        Parameters = {
          Cluster              = var.ecs_cluster_arn
          TaskDefinition       = var.batch_task_definition_arn
          LaunchType           = "FARGATE"
          NetworkConfiguration = local.network_configuration
          Overrides = {
            ContainerOverrides = [{
              Name        = var.batch_container_name
              "Command.$" = "States.Array('--job=import', States.Format('--business-date={}', $.businessDate))"
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
        ResultPath     = "$.import"
        Retry          = local.ecs_retry
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.failure"
          Next        = "NotifyDatasetFailure"
        }]
        Next = "CheckImportExitCode"
      }

      CheckImportExitCode = {
        Type = "Choice"
        Choices = [{
          Variable      = "$.import.exitCode"
          NumericEquals = 0
          Next          = "DatasetRoundTripSucceeded"
        }]
        Default = "NotifyDatasetFailure"
      }

      NotifyDatasetFailure = {
        Type     = "Task"
        Resource = "arn:${data.aws_partition.current.partition}:states:::sns:publish"
        Parameters = {
          TopicArn    = var.notification_topic_arn
          Subject     = "CardDemo dataset round trip failed"
          "Message.$" = "States.Format('CardDemo dataset round trip execution {} failed: {}', $$.Execution.Name, States.JsonToString($))"
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
          Next        = "DatasetRoundTripFailed"
        }]
        Next = "DatasetRoundTripFailed"
      }

      DatasetRoundTripSucceeded = {
        Type = "Succeed"
      }

      DatasetRoundTripFailed = {
        Type  = "Fail"
        Error = "CardDemoDatasetRoundTripFailed"
        Cause = "The dataset export or import task failed; inspect the execution history and notification"
      }
    }
  }

  # WHY : Refactoring Rationale: the authorization definition is the FOURTH entry and
  #       was added with the authorization-extract machine below. Its absence was not a
  #       narrowing -- it was the reason that machine could not exist: ecs:RunTask is
  #       scoped to exactly this list, so a state naming a task definition outside it is
  #       refused at run time with an access-denied error naming the definition rather
  #       than this input.
  # WHY : Refactoring Rationale: this machine exists because
  #       com.carddemo.authorization.service.UnloadService -- the transcription of
  #       cbl/PAUDBUNL.CBL and cbl/DBUNLDGS.CBL -- had no accepted invocation of any
  #       kind. It had no task, no controller, no schedule and no state here, so the
  #       segment export was reachable only from its own unit tests. The load and the
  #       purge were in the same state and were given task entry points inside the
  #       service; what neither of them gained, and what this machine supplies, is the
  #       operator-facing half: an invocation that names destinations, bounds its own
  #       duration, retries a launch fault, reports a non-zero exit and notifies.
  # WHY : Assumptions: ONE machine with a mode rather than one machine per direction.
  #       The two directions share their validation, their notification, their failure
  #       state and their timeouts, and differ only in which job the container override
  #       selects; a second machine would duplicate seven states to vary one string.
  # WHY : Assumptions: the two directions are alternatives and are NEVER chained, which
  #       is the one way this definition deliberately departs from the dataset round
  #       trip above. That machine exports and then imports, and verifying an export by
  #       importing it is safe there because the import writes to dataset artefacts. The
  #       equivalent here would load an extract back into the live authorization schema,
  #       and because the purge deletes expired rows a load run after one would
  #       RESURRECT exactly the rows the purge had removed. A verification that can undo
  #       a retention decision is worse than no verification, so the load is offered as
  #       its own explicitly-requested mode against operator-named sources.
  authorization_extract_definition = {
    Comment = "CardDemo operator-invoked pending-authorization segment export and extract load"

    TimeoutSeconds = var.authorization_extract_timeout_seconds

    StartAt = "ValidateAuthorizationExtractRequest"
    States = {
      # WHY : Assumptions: the mode is checked in the GRAPH and each mode's own
      #       arguments are checked with it, so a request naming no mode, or a mode
      #       whose arguments are absent, is refused without starting a Fargate task.
      #       The service validates every argument again before its context starts;
      #       that second check is not redundant, it is the one that runs when an
      #       operator invokes the image directly rather than through this machine.
      ValidateAuthorizationExtractRequest = {
        Type = "Choice"
        Choices = [
          {
            And = [
              {
                Variable     = "$.mode"
                StringEquals = "unload"
              },
              {
                Variable  = "$.businessDate"
                IsPresent = true
              },
              {
                Variable      = "$.businessDate"
                StringMatches = "????-??-??"
              },
            ]
            Next = "UnloadAuthorizations"
          },
          {
            # WHY : Assumptions: the load takes its two sources from the REQUEST
            #       rather than deriving them from a business date, because the
            #       extract being loaded was not necessarily produced by this
            #       machine -- the reference programs' own output is a legitimate
            #       input, and it carries no run identifier this graph could
            #       reconstruct a key from.
            And = [
              {
                Variable     = "$.mode"
                StringEquals = "load"
              },
              {
                Variable  = "$.rootExtract"
                IsPresent = true
              },
              {
                Variable  = "$.childExtract"
                IsPresent = true
              },
            ]
            Next = "LoadAuthorizations"
          },
        ]
        Default = "InvalidAuthorizationExtractRequest"
      }

      InvalidAuthorizationExtractRequest = {
        Type = "Pass"
        Result = {
          error   = "InvalidAuthorizationExtractRequest"
          message = "Supply mode=unload with businessDate as YYYY-MM-DD, or mode=load with rootExtract and childExtract locations"
        }
        ResultPath = "$.failure"
        Next       = "NotifyAuthorizationExtractFailure"
      }

      # WHY : Assumptions: the two destinations are COMPUTED here rather than accepted
      #       from the request, so an export cannot be told to overwrite an unrelated
      #       key and two exports of the same business date on different executions
      #       cannot collide. The execution name is in the prefix for the second
      #       reason and the business date for the first, which is why both appear.
      # WHY : Trade-offs: the operator therefore cannot choose where an export lands.
      #       That is accepted because the runbook reads the destinations back out of
      #       the execution's own input, so the location is discoverable after the
      #       fact without being specifiable before it.
      UnloadAuthorizations = {
        Type           = "Task"
        Resource       = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
        TimeoutSeconds = var.authorization_state_timeout_seconds["UnloadAuthorizations"]
        Parameters = {
          Cluster              = var.ecs_cluster_arn
          TaskDefinition       = var.authorization_task_definition_arn
          LaunchType           = "FARGATE"
          NetworkConfiguration = local.network_configuration
          Overrides = {
            ContainerOverrides = [{
              Name        = var.authorization_container_name
              "Command.$" = "States.Array('--job=unload-authorizations', States.Format('--root-extract=s3://{}/authorization/extract/dt={}/run={}/roots.dat', '${var.dataset_bucket_name}', $.businessDate, $$.Execution.Name), States.Format('--child-extract=s3://{}/authorization/extract/dt={}/run={}/children.dat', '${var.dataset_bucket_name}', $.businessDate, $$.Execution.Name))"
            }]
          }
        }
        ResultSelector = local.ecs_result_selector
        ResultPath     = "$.unload"
        Retry          = local.ecs_retry
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.failure"
          Next        = "NotifyAuthorizationExtractFailure"
        }]
        Next = "CheckUnloadExitCode"
      }

      # WHY : Assumptions: a separate Choice, for the reason the dataset machine
      #       records against its own pair -- a non-zero container exit completes the
      #       synchronous integration successfully and reports the code in its result,
      #       so the task's Catch never sees it.
      CheckUnloadExitCode = {
        Type = "Choice"
        Choices = [{
          Variable      = "$.unload.exitCode"
          NumericEquals = 0
          Next          = "AuthorizationExtractSucceeded"
        }]
        Default = "NotifyAuthorizationExtractFailure"
      }

      LoadAuthorizations = {
        Type           = "Task"
        Resource       = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
        TimeoutSeconds = var.authorization_state_timeout_seconds["LoadAuthorizations"]
        Parameters = {
          Cluster              = var.ecs_cluster_arn
          TaskDefinition       = var.authorization_task_definition_arn
          LaunchType           = "FARGATE"
          NetworkConfiguration = local.network_configuration
          Overrides = {
            ContainerOverrides = [{
              Name        = var.authorization_container_name
              "Command.$" = "States.Array('--job=load-authorizations', States.Format('--root-extract={}', $.rootExtract), States.Format('--child-extract={}', $.childExtract))"
            }]
          }
        }
        ResultSelector = local.ecs_result_selector
        ResultPath     = "$.load"
        Retry          = local.ecs_retry
        Catch = [{
          ErrorEquals = ["States.ALL"]
          ResultPath  = "$.failure"
          Next        = "NotifyAuthorizationExtractFailure"
        }]
        Next = "CheckLoadExitCode"
      }

      CheckLoadExitCode = {
        Type = "Choice"
        Choices = [{
          Variable      = "$.load.exitCode"
          NumericEquals = 0
          Next          = "AuthorizationExtractSucceeded"
        }]
        Default = "NotifyAuthorizationExtractFailure"
      }

      NotifyAuthorizationExtractFailure = {
        Type     = "Task"
        Resource = "arn:${data.aws_partition.current.partition}:states:::sns:publish"
        Parameters = {
          TopicArn    = var.notification_topic_arn
          Subject     = "CardDemo authorization extract failed"
          "Message.$" = "States.Format('CardDemo authorization extract execution {} failed: {}', $$.Execution.Name, States.JsonToString($))"
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
          Next        = "AuthorizationExtractFailed"
        }]
        Next = "AuthorizationExtractFailed"
      }

      AuthorizationExtractSucceeded = {
        Type = "Succeed"
      }

      AuthorizationExtractFailed = {
        Type  = "Fail"
        Error = "CardDemoAuthorizationExtractFailed"
        Cause = "The authorization export or extract load task failed; inspect the execution history and notification"
      }
    }
  }

  task_definition_arns = [
    var.batch_task_definition_arn,
    var.data_migration_task_definition_arn,
    var.reporting_task_definition_arn,
    var.authorization_task_definition_arn,
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

# WHY : Assumptions: the /aws/vendedlogs/states/ prefix is not decoration and is not
#       interchangeable with a name of our own choosing. Step Functions delivers
#       execution history as a VENDED log, and CloudWatch Logs grants that delivery
#       through a resource policy on the destination. Outside this reserved prefix
#       each destination is enumerated individually in that policy, which has a hard
#       size limit -- so once enough state machines log to bespoke paths in one
#       account, a further destination cannot be attached at all, and the failure
#       appears as a state machine that applies cleanly and then logs nothing.
#       Inside the
#       prefix the service covers the whole path at once and the limit is never
#       approached. This module alone adds four destinations, which is why it matters
#       here rather than being a general preference.
# WHY : Refactoring Rationale: these four log groups are declared here rather than
#       left to the service to create implicitly. A group Step Functions creates for
#       itself carries never-expire retention and no customer-managed key, so the
#       execution history of a financial batch chain would accumulate indefinitely
#       and unencrypted; declaring them makes retention and encryption reviewable and
#       lets Terraform delete them with the machines they belong to.
resource "aws_cloudwatch_log_group" "daily" {
  name              = "/aws/vendedlogs/states/${local.daily_machine_name}"
  retention_in_days = var.log_retention_days

  # WHY : Trade-offs: this input is nullable, and a null leaves the group on the
  #       CloudWatch-managed key rather than failing the plan. Customer-managed
  #       encryption at rest is a property this migration ADDS -- every CICS file in
  #       app/csd/CARDDEMO.CSD:1-89 is defined RECOVERY(NONE) JOURNAL(NO) -- so a
  #       caller that has not yet provisioned a key still gets a usable,
  #       service-encrypted module rather than a blocked one. Both environment roots
  #       do pass the key, so the null case is a bootstrapping affordance and not the
  #       operating posture.
  kms_key_id = var.log_group_kms_key_arn

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

# WHY : Assumptions: a THIRD log group rather than a shared one, matching the two
#       above. Step Functions writes one log destination per state machine, so a
#       shared group would interleave a nightly chain, an on-demand report and an
#       operator round trip into one stream and make a per-workload retention or
#       subscription impossible to express.
resource "aws_cloudwatch_log_group" "dataset_roundtrip" {
  name              = "/aws/vendedlogs/states/${local.dataset_machine_name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_group_kms_key_arn

  tags = merge(var.tags, {
    Name = "${local.dataset_machine_name}-logs"
  })
}

# WHY : Assumptions: a FOURTH log group, on the same reasoning as the third. Step
#       Functions writes one destination per state machine, and the authorization
#       extract's stream is the one an operator reads when an export produced nothing;
#       interleaved with the nightly chain it would be unreadable, and it is also the
#       only one of the four whose retention a data-handling review might want set
#       differently from a batch job's.
resource "aws_cloudwatch_log_group" "authorization_extract" {
  name              = "/aws/vendedlogs/states/${local.authz_machine_name}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_group_kms_key_arn

  tags = merge(var.tags, {
    Name = "${local.authz_machine_name}-logs"
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

    # WHY : Assumptions: all four machines share one role, so the source ARN is
    #       scoped to this module's name prefix rather than one exact machine.
    #       SourceAccount separately prevents a state machine in another
    #       account from satisfying the wildcard. Together the two conditions are
    #       the confused-deputy guard: without them the trust policy would let the
    #       Step Functions service principal assume this role on behalf of ANY
    #       state machine that service runs, including one in another account.
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

  # WHY : Assumptions: the `.sync` in ecs:runTask.sync is not implemented by polling.
  #       Step Functions learns that a task has stopped through an EventBridge MANAGED
  #       RULE it creates and maintains itself, named StepFunctionsGetEventsForECSTaskRule,
  #       which is why a state machine that only starts tasks still needs three events
  #       permissions. Without them the service cannot register its listener, so every
  #       task state runs the task and then HANGS until its own TimeoutSeconds expires
  #       rather than failing usefully -- a symptom that looks like a slow batch job and
  #       is not one. The rule name is fixed by AWS, so the ARN is composed from the
  #       data sources above rather than named by an input.
  statement {
    sid       = "ObserveSynchronousTaskCompletion"
    effect    = "Allow"
    actions   = ["events:DescribeRule", "events:PutRule", "events:PutTargets"]
    resources = [local.ecs_events_rule_arn]
  }

  # WHY : Assumptions: RunTask cannot start a task without also PASSING that task's
  #       role and execution role to ECS, so this grant is a consequence of running
  #       tasks at all rather than a separate privilege. It is scoped by resource to
  #       the enumerated roles, which is why var.pass_role_arns is required and exact.
  # WHY : Trade-offs: the iam:PassedToService condition is what stops the grant being
  #       usable to hand those same roles to any OTHER service -- without it, this
  #       execution role could pass a task role to a service that would assume it for
  #       something the role's own policy permits but this chain never intended. The
  #       cost accepted is one extra condition block per statement and the coupling to
  #       a service principal string; the alternative, resource scoping alone, leaves
  #       the passing DESTINATION unconstrained, and it is the destination that decides
  #       what the passed credentials end up doing.
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

  # WHY : Alternatives Considered: EXPRESS, rejected on a hard capability limit and
  #       not on cost or preference. An EXPRESS workflow does not support the
  #       synchronous service integrations at all, and every work state in this chain
  #       is an ecs:runTask.sync or a lambda:invoke, so the definition below simply
  #       could not be deployed as EXPRESS. Its five-minute execution ceiling rules it
  #       out independently, since a single posting or statement step processes a whole
  #       day's volume in one pass. STANDARD also refuses a duplicate execution NAME,
  #       which the graph relies on as the outer half of its run-id idempotency.
  # WHY : Assumptions: redrive -- the closest analogue to a JCL RESTART= -- needs
  #       nothing configured here. It is a runtime capability of a STANDARD execution,
  #       invoked against a failed execution rather than declared on the machine, so
  #       there is deliberately no Terraform attribute for it in this resource. The
  #       durable half that makes a redriven step safe is batch-service's
  #       batch.batch_run ledger, as recorded in the header block.
  type = "STANDARD"

  # WHY : Alternatives Considered: holding the ASL in a templates/*.json.tftpl file
  #       rendered by templatefile(). Rejected because it would turn every cluster,
  #       task-definition, function, topic and log-group reference into a
  #       stringly-typed template variable that neither terraform validate nor tflint
  #       can check, and a typo in one would surface as a malformed state machine at
  #       apply or a broken reference at run time. jsonencode over an HCL object keeps
  #       the whole definition inside the validator's view, lets every ARN stay a
  #       native reference that Terraform orders the graph on, and keeps each state's
  #       rationale adjacent to the state instead of a file away.
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
  # WHY : Refactoring Rationale: this release now NAMES the owner it means to clear, where
  #       it was deliberately unconditional and carried no expectedLeaseOwner at all. The
  #       old reasoning was that a watchdog must be able to clear a bracket whose owner
  #       stopped without releasing it, and that requiring an owner would refuse exactly
  #       the invocation the rule exists for. That was true only while the function had no
  #       durable owner to compare against -- it stored the bracket as a bare boolean, so
  #       "unconditional" was the only release it could implement. The consequence was that
  #       this rule could clear a bracket a HEALTHY execution still held: the rule fires per
  #       terminating execution, so a chain that lost the lease and failed fast triggered a
  #       release that re-enabled online writes underneath the execution which legitimately
  #       owned the window.
  # WHY : Assumptions: the owner is available here and always was -- the transformer already
  #       extracts $.detail.name, the terminating execution's NAME, which is exactly the
  #       value the function records as the lease owner. So naming it costs nothing and
  #       makes this rule checkable like every other caller: it can clear the lease of the
  #       execution that just terminated, and no other. An earlier revision passed the
  #       execution ARN under expectedLeaseOwnerArn and it was removed because nothing
  #       compared it; the difference now is that this key IS the one the condition reads
  #       and the value IS a name rather than an ARN.
  # WHY : Trade-offs: a lease whose owner terminated WITHOUT this rule delivering is still
  #       recoverable, because the function's release condition also admits an expired
  #       lease and the graph records an expiry equal to the state machine's own timeout.
  #       So the watchdog is now the fast path rather than the only path, and losing an
  #       event delays the bracket's release to its expiry instead of stranding it.
  #       terminalStatus and releasedBy remain because they are read where this rule is
  #       read -- in the rule definition and in the invocation record -- and claim no
  #       comparison.
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
      "expectedLeaseOwner"  = "<executionName>"
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

# WHY : Refactoring Rationale: this machine replaces a CICS-side job-submission
#       tunnel, and the defect it retires is specific. app/csd/CARDDEMO.CSD:499-505
#       defines TDQUEUE(JOBS) -- DEFINE TDQUEUE(JOBS) GROUP(CARDDEMO) at :499,
#       DESCRIPTION(SUBMIT JOBS FROM CICS) at :500, then
#       TYPE(EXTRA) DATABUFFERS(1) DDNAME(INREADER) ERROROPTION(IGNORE) at :501,
#       OPENTIME(INITIAL) TYPEFILE(OUTPUT) RECORDSIZE(80) at :502 and
#       RECORDFORMAT(FIXED) BLOCKFORMAT(UNBLOCKED) DISPOSITION(MOD) at :503. An
#       on-demand report was requested by writing 80-byte JCL card images to that
#       extrapartition queue, which DDNAME(INREADER) at :501 routed to the internal
#       reader for submission. ERROROPTION(IGNORE) on that same line means a FAILED
#       WRITE WAS SWALLOWED SILENTLY: a user could believe a report had been
#       requested when nothing was ever queued, and there was no return path to tell
#       them otherwise. DISPOSITION(MOD) at :503 additionally means submissions
#       accumulated in the queue rather than replacing one another.
#       states:StartExecution replaces both properties: it returns an execution ARN
#       the caller can poll and it FAILS LOUDLY, so a refused request is refused
#       where it was made.
# WHY : Assumptions: this module does NOT create the permission that lets
#       reporting-service call StartExecution on this machine, and does not publish
#       the Parameter Store entry that carries the ARN to it. It exports the ARN from
#       outputs.tf and the environment root wires both, because the grant belongs on
#       that service's TASK role -- the identity that actually makes the call -- and a
#       cross-service grant bolted in here would attach it to the wrong principal and
#       make this module depend on a consumer it is meant to be independent of.
resource "aws_sfn_state_machine" "adhoc" {
  name     = local.adhoc_machine_name
  role_arn = aws_iam_role.this.arn

  # WHY : Alternatives Considered: EXPRESS, rejected for the same capability reason
  #       the daily machine records -- its single work state is an ecs:runTask.sync,
  #       which an EXPRESS workflow cannot express at all, and a report over a
  #       user-chosen date range can outlast the five-minute ceiling.
  type = "STANDARD"

  # WHY : Alternatives Considered: a templatefile()-rendered ASL, rejected for the
  #       reason recorded on the daily machine's definition above.
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

# WHY : Assumptions: STANDARD rather than EXPRESS, for two reasons that both
#       matter here. The round trip runs two Fargate tasks in sequence, so its
#       duration is minutes and can exceed the five-minute ceiling an EXPRESS
#       execution has; and only a STANDARD execution refuses a duplicate execution
#       NAME, which is the outer half of the idempotency the definition's run-id
#       binding describes -- an EXPRESS machine would accept a repeated name and
#       leave the batch step ledger as the only defence.
# WHY : Assumptions: the SHARED execution role is reused rather than a third one
#       created. Its privileges are already exactly what this machine needs and
#       nothing more: ecs:RunTask is scoped to the four task definitions this
#       module was given -- of which this machine uses only the batch one --
#       alongside ecs:StopTask, ecs:DescribeTasks, the managed-rule permissions
#       Step Functions needs to observe task completion, iam:PassRole over the
#       enumerated task roles, and sns:Publish on the one notification topic. A
#       third role would duplicate all of that with no narrowing, because the batch
#       task definition is already in the set the daily chain runs.
resource "aws_sfn_state_machine" "dataset_roundtrip" {
  name     = local.dataset_machine_name
  role_arn = aws_iam_role.this.arn
  type     = "STANDARD"

  # WHY : Alternatives Considered: a templatefile()-rendered ASL, rejected for the
  #       reason recorded on the daily machine's definition above.
  definition = jsonencode(local.dataset_roundtrip_definition)

  logging_configuration {
    include_execution_data = var.log_include_execution_data
    level                  = var.log_level
    log_destination        = "${aws_cloudwatch_log_group.dataset_roundtrip.arn}:*"
  }

  tracing_configuration {
    # WHY : Assumptions: asserted rather than parameterised, for the reason
    #       recorded on the X-Ray statement of the execution-role policy above.
    enabled = true
  }

  tags = merge(var.tags, {
    Name = local.dataset_machine_name
  })

  depends_on = [aws_iam_role_policy.this]
}


# WHY : Assumptions: STANDARD rather than EXPRESS, on the same two grounds the dataset
#       round trip records. An export walks every pending authorization in the schema, so
#       its duration is minutes and can exceed an EXPRESS execution's five-minute
#       ceiling; and only a STANDARD execution refuses a duplicate execution NAME, which
#       matters more here than anywhere else in this module because the export's
#       destination keys are DERIVED from that name -- two executions sharing a name
#       would write the same two objects.
# WHY : Assumptions: the SHARED execution role is reused rather than a fourth created.
#       Its privileges are already exactly what this machine needs: ecs:RunTask over the
#       four task definitions this module was given, of which this machine uses only the
#       authorization one, alongside ecs:StopTask, ecs:DescribeTasks, the managed-rule
#       permissions Step Functions needs to observe task completion, iam:PassRole over the
#       enumerated task roles, and sns:Publish on the one notification topic.
# WHY : Assumptions: nothing here grants the object-store access the export needs. That
#       privilege belongs to the authorization TASK role, which is what the container
#       authenticates as, and it is granted in the environment root beside the rest of
#       that role's policy. Granting it to the execution role instead would be granting
#       it to the wrong identity and would not work.
resource "aws_sfn_state_machine" "authorization_extract" {
  name     = local.authz_machine_name
  role_arn = aws_iam_role.this.arn
  type     = "STANDARD"

  # WHY : Alternatives Considered: a templatefile()-rendered ASL, rejected for the
  #       reason recorded on the daily machine's definition above.
  definition = jsonencode(local.authorization_extract_definition)

  logging_configuration {
    include_execution_data = var.log_include_execution_data
    level                  = var.log_level
    log_destination        = "${aws_cloudwatch_log_group.authorization_extract.arn}:*"
  }

  tracing_configuration {
    # WHY : Assumptions: asserted rather than parameterised, for the reason
    #       recorded on the X-Ray statement of the execution-role policy above.
    enabled = true
  }

  tags = merge(var.tags, {
    Name = local.authz_machine_name
  })

  depends_on = [aws_iam_role_policy.this]
}
