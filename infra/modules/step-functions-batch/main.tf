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
#   it is on-demand rather than nightly because that is the execution model the
#   baseline gives the pair it replaces. app/jcl/CBEXPORT.jcl and
#   app/jcl/CBIMPORT.jcl are standalone job cards an operator submits, named in
#   neither app/scheduler/CardDemo.ca7 nor app/scheduler/CardDemo.controlm, which
#   between them drive POSTTRAN, INTCALC, TRANBKP, CREASTMT and the rest of the
#   scheduled work. batch-service registers both as runnable jobs, under the
#   tokens `export` and `import`, so a machine an operator starts exposes that
#   execution model as it is, whereas two more nightly states would export a
#   five-master extract every night whether or not one was asked for. The
#   placement argument, with its three rejected alternatives, is at
#   dataset_roundtrip_definition.
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
#   Every TIMED state -- the twelve named in local.timed_state_names -- has an
#   explicit timeout, and every machine ALSO carries a top-level TimeoutSeconds:
#   var.state_machine_timeout_seconds for the daily chain and one ceiling each for
#   the three on-demand machines. Those twelve names are the ELEVEN top-level work
#   states AAP section 0.4.1.7 fixes for the nightly chain plus VerifyMigration,
#   which is not a twelfth top-level state: it runs inside the StageSeedDatasets
#   branch, and why it lives there rather than beside the eleven is recorded at that
#   state. Four asymmetries are deliberate and are stated here rather than left for a
#   reader to discover as an apparent omission. State 2, StageSeedDatasets, is the
#   one top-level work state that is not a Task -- it is a Parallel wrapping one
#   branch -- and it carries an explicit Catch but NO TimeoutSeconds and NO Retry:
#   the States Language does not define TimeoutSeconds for a Parallel, so the ceiling
#   is applied inside the branch at the Map and at each of its two tasks, and a Retry
#   is withheld deliberately because re-entering the Parallel would replay a
#   completed refresh of every dataset to redo one read-only pass. Inside that branch
#   the Map likewise carries a timeout but NO Retry, and the retry that covers the
#   work sits one level lower still, on the per-dataset task, so a transient fault
#   replays the one dataset that faulted instead of all eleven. That placement is not
#   a gap: for an INLINE Map every Map-level error the language defines is either
#   unraisable without an ItemReader or ResultWriter, or documented as non-retriable,
#   which is recorded in full at the state together with the two non-existent
#   "States." names a Map-level Retry had previously introduced. The five
#   Notify* states -- NotifyInvalidExecutionInput, NotifyFailure, NotifyAdHocFailure,
#   NotifyDatasetFailure and NotifyAuthorizationExtractFailure -- are single SNS
#   publishes rather than work states: each carries a
#   retry and a catch and no TimeoutSeconds, because the SNS integration is not a
#   long-running task and the state that would time out is the one whose job is to
#   report that something else already failed. The finalizer's StopResidualBatchTask
#   carries a catch and no retry, because the confirmation loop around it re-issues
#   the stop on every pass -- there the loop IS the retry, and the reasoning for
#   repeating rather than retrying sits at the state.
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
#   - Refactoring Rationale: a JCL COND is a SKIP predicate and a Choice is a RUN
#     predicate, so a condition code INVERTS on the way across rather than being
#     copied -- and one keyword carries three unrelated forms, so conflating them
#     produces a chain that plans and applies cleanly and is wrong only at run
#     time. Each form is mapped where it occurs; the site census and the long-form
#     argument stay in docs/architecture/batch-orchestration.md and the decision in
#     docs/adr/ADR-005-batch-orchestration.md, not in a second copy here:
#       (a) COND=(0,NE), eight step gates -> the DEFAULT SUCCESS EDGE, which two
#           different routes leave and the graph keeps apart: an integration fault
#           raises a States error the state's Catch takes, while a task whose
#           CONTAINER exits non-zero raises nothing and is read by the
#           Check*ExitCode Choice after it, whose Default routes to NotifyFailure.
#           Both end at Fail, but treating Catch as the handler for a non-zero exit
#           would describe a chain that continues past a failed step.
#       (b) COND=(4,LT), one step gate at app/jcl/TRANBKP.jcl:51 -> no state. It
#           gates that job's OWN STEP10 re-DEFINE of the transaction cluster, after
#           its own STEP05R unload and STEP05 DELETE, and cannot observe CBTRN02C,
#           because a JCL COND reads earlier steps of the SAME job while posting
#           runs in app/jcl/POSTTRAN.jcl. It therefore retires WITH the
#           delete-and-redefine mechanism, state 6 being an export rather than a
#           drop and rebuild, so no state gates on it. The posting warn tier has
#           its own source -- app/cbl/CBTRN02C.cbl:229-230 moves 4 to RETURN-CODE
#           on a positive reject count, which the return-code rubric in
#           tests/README.md classifies as warn -- and that, not this gate, is what
#           CheckPostingExitCode admits; argued at local.posting_warn_return_code.
#       (c) INCLUDE COND=(...), TWO occurrences of one predicate, at
#           app/jcl/TRANREPT.jcl:47-48 and app/proc/TRANREPT.prc:45-46 -> one SQL
#           WHERE clause in reporting-service, never a state: both sit inside a
#           DFSORT step and select RECORDS by an inclusive processing-date range.
#           See the GenerateReports state.
#   - Assumptions: state 2 is a ten-item Map using data-migration, states 3-7
#     use batch-service, and states 8-9 plus ad-hoc reporting use
#     reporting-service. BatchApplication's verified seven-name list contains
#     the five daily batch jobs used here and contains no reporting job.
#   - Refactoring Rationale: restart is an IMPROVEMENT here, not a preserved
#     contract, and the direction matters. The baseline has no batch-restart
#     mechanism to port -- its only RESTART= is COMMENTED OUT at
#     app/jcl/DEFGDGD.jcl:2 and CHKPT= appears ZERO times in the JCL tier -- so a
#     STANDARD execution's redrive resumes from the failed state and
#     batch-service's durable batch.batch_run ledger, unique on
#     (run_id, step_name), makes a resumed step that already completed a no-op.
#     Each half is recorded where it acts: redrive at aws_sfn_state_machine.daily
#     below, the ledger in batch-service.
#   - Assumptions: one baseline job retires WITH an analogue rather than without
#     one. app/jcl/WAITSTEP.jcl:22 drives PGM=COBSWAIT, a step whose only purpose
#     is to make a job wait for the step before it. That is precisely what an edge
#     between two states already is here, so the wait is expressed by the graph's
#     own ordering and no state corresponds to it. Recorded because its absence
#     from the state list would otherwise look like an omission.
#   - Assumptions: what this module does NOT own, recorded once so nothing is
#     bolted on here that belongs to a sibling. The dataset bucket, its TEN
#     generation-dataset prefix families and the noncurrent-version lifecycle rule
#     that stands in for their LIMIT belong to infra/modules/s3-datasets. Ten
#     counts DISTINCT bases and not declarations: the baseline holds ELEVEN DEFINE
#     GENERATIONDATAGROUP statements -- six naming families at
#     app/jcl/DEFGDGB.jcl:25, :31, :37, :43, :49 and :55, three at
#     app/jcl/DEFGDGD.jcl:28, :51 and :74 and one at app/jcl/DALYREJS.jcl:24-26,
#     those ten at LIMIT(5) SCRATCH, plus app/jcl/REPTFILE.jcl:25-28 declaring the
#     SAME TRANREPT base again at LIMIT(10) with no SCRATCH. That conflict is
#     resolved in the sibling rather than here: its noncurrent_version_retention
#     applies five uniformly and its per-family noncurrent_versions override is
#     left UNSET on `tranrept`, so the LIMIT(5) SCRATCH declaration is the one
#     applied while the other stays expressible from a caller without a topology
#     change. docs/adr/ADR-005-batch-orchestration.md records the same
#     eleven-over-ten finding. This module only consumes var.dataset_bucket_name
#     and creates nothing inside it.
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
#       composed rather than written. Three ARNs cannot come from a variable: the
#       EventBridge managed rule the synchronous run-task integration relies on has
#       a name AWS fixes, the state-machine ARNs the trust policies match on describe
#       machines this module is itself creating, and the task ARN pattern that scopes
#       ecs:StopTask names tasks that do not exist until RunTask creates them.
#       Hard-coding any of them would embed one account, one region and one partition
#       in a module that both environment roots share, which defeats having two roots
#       that differ only in sizing. Composing from the caller's own partition, region
#       and identity makes the module correct in whichever of the three it is applied
#       to, including a non-commercial partition where the `aws` literal is wrong.
# WHY : Trade-offs: an account identifier is an IDENTIFIER, not a credential, so
#       committing one would not breach the no-secrets-in-source constraint -- that
#       is not the reason these data sources exist. The reason is portability: a
#       literal account, region or partition in a shared module is a value that is
#       right in exactly one place and silently wrong everywhere else, and the
#       failure it produces is an access-denied at run time rather than an error at
#       plan time. The cost accepted is three provider round trips per plan.
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
  execution_role_name_stem = "${local.name_stem}-sfn-batch"
  ecs_events_rule_arn      = "arn:${data.aws_partition.current.partition}:events:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:rule/StepFunctionsGetEventsForECSTaskRule"
  state_machine_arn_root   = "arn:${data.aws_partition.current.partition}:states:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:stateMachine:"
  ecs_cluster_name         = element(split("/", var.ecs_cluster_arn), 1)
  cluster_task_arn_pattern = "arn:${data.aws_partition.current.partition}:ecs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:task/${local.ecs_cluster_name}/*"

  # WHY : Assumptions: this list is ELEVEN names and the number is a contract, not a
  #       tally. Specification section 0.4.1.7 enumerates the nightly chain state by
  #       state -- QuiesceOnlineWrites, StageSeedDatasets, PreflightDailyTransactions,
  #       PostTransactions, CalculateInterest, BackupTransactions,
  #       CombineTransactions, GenerateStatements, GenerateReports, AnalyzeTables,
  #       ResumeOnlineWrites -- so a twelfth work state is a change to the specified
  #       topology rather than an addition to an open list.
  # WHY : Refactoring Rationale: three names have been authored into this list as
  #       TOP-LEVEL work states and none of the three is one now. LoadSeedDatasets and
  #       ReconcileTransactionSequence are withdrawn outright, because the refresh
  #       branch already loads each dataset and already advances the identifier
  #       allocator, so neither added work. VerifyMigration is a different case: it too
  #       stood as a twelfth top-level state against a specified eleven, and it is
  #       RELOCATED rather than withdrawn -- it now runs inside the StageSeedDatasets
  #       Parallel, after the seed-refresh Map, so the published topology is the eleven
  #       the specification enumerates and the gate still runs.
  # WHY : ⚠️ Alternatives Considered: withdrawing the gate outright, folding its
  #       substance into the per-dataset refresh -- each StageSeedDatasets branch runs
  #       `refresh-dataset`, which fetches the extract, stages the generation, loads the
  #       owning schema and then runs row count, per-record digest and exact money
  #       totals for that dataset -- and leaving `verify-all` to the cutover runbook.
  #       Rejected, and the reason is what the two arrangements do NOT share. The
  #       per-dataset passes verify each dataset against its own source; the gate runs
  #       the two committed WHOLE-MIGRATION queries, a cross-relation row-count report
  #       and an exact money-total and negative-row report, which no per-dataset pass
  #       reaches at all -- and it runs them on the SELECT-only verification login
  #       rather than the loader's own identity. Specification sections 0.9.2 and 0.7.7
  #       make that verification a first-class deliverable on the stated ground that a
  #       load which "succeeded" without a money-total check is not evidence of
  #       anything, so withdrawing it would have bought the eleven by giving up a
  #       mandated check. Relocating it buys the same eleven and gives up nothing.
  # WHY : Assumptions: the ordering barrier does not rest on the gate alone, and that is
  #       what makes the relocation safe rather than merely tidy. A branch that fails any
  #       of its steps exits non-zero, the branch's own exit-code Choice turns that into
  #       a Fail, a failed branch fails the Map, and the Map's Catch routes to failure
  #       notification -- so PreflightDailyTransactions is already unreachable over a
  #       corpus whose per-dataset verification did not pass. The gate adds the
  #       whole-migration barrier on top of that, through its own exit-code Choice, and
  #       both barriers sit inside the one state the specification does contain.
  # WHY : Assumptions: `verify-all` remains the CUTOVER verb as well, invoked by
  #       docs/runbooks/data-migration.md over the whole registry with no dataset
  #       selector. The nightly gate and the cutover step run the same verb for the same
  #       reason at two different moments, which is why the verb accepts no selector.
  # WHY : Assumptions: the order is load-then-reconcile-then-verify and it is still
  #       enforced, inside the refresh branch rather than across three states. The
  #       ordering matters for the reason the withdrawn states recorded: reconciling
  #       before the load would advance the sequence past rows that are not there yet,
  #       and verifying before the reconciliation would pass over a sequence still
  #       pointing inside the loaded range, so the first posted transaction would
  #       collide on a primary key the verification had just certified as clean.
  # WHY : Refactoring Rationale: this list is named timed_state_names and it was named
  #       working_state_names. The rename is the point, not cosmetic. It holds TWELVE
  #       entries while the nightly chain publishes ELEVEN top-level work states, because
  #       VerifyMigration needs a ceiling of its own and runs inside the StageSeedDatasets
  #       branch rather than beside the eleven. Under the old name a reader counting this
  #       list arrived at twelve work states and the module's own eleven-state contract
  #       contradicted them -- exactly the drift this list exists to prevent. What the
  #       list actually enumerates is every state that HAS a configured ceiling, so that
  #       is what it is called.
  # WHY : Assumptions: "has a ceiling" is deliberately not "carries a TimeoutSeconds
  #       field", because for one of the twelve those are different places. Eleven names
  #       resolve to a Task that carries the field itself; the StageSeedDatasets entry
  #       names a budget that is applied INSIDE that state, on the Map and on each of the
  #       two tasks in its branch, because the States Language does not define
  #       TimeoutSeconds for a Parallel. The distinction is recorded here as well as at
  #       the state itself so that this sentence and that one cannot be read as
  #       disagreeing about whether the Parallel carries a field it cannot carry.
  timed_state_names = [
    "QuiesceOnlineWrites",
    "StageSeedDatasets",
    # WHY : Assumptions: VerifyMigration is listed here although it is NOT a top-level
    #       work state. It runs inside the StageSeedDatasets Parallel, so it never
    #       appears in the eleven-state topology AAP section 0.4.1.7 fixes, but it is a
    #       Task with a TimeoutSeconds like any other and a Task with no ceiling waits
    #       indefinitely. This list is what var.state_timeout_seconds is indexed by, so
    #       omitting the name here would silently drop the ceiling rather than fail.
    "VerifyMigration",
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
  #       timed state -- so a missing key is a plan-time error naming the variable
  #       rather than a state silently inheriting a ceiling nobody chose for it.
  #       The iteration still runs over local.timed_state_names so that this map
  #       and the state list cannot come to hold different sets.
  state_timeouts = {
    for state_name in local.timed_state_names :
    state_name => var.state_timeout_seconds[state_name]
  }

  # WHY : Refactoring Rationale: a second `data_migration_environment` local and a
  #       `dataset_inbox_uri` local stood here, composing the same container
  #       environment and the same extract location a second way. Both are withdrawn
  #       in favour of the single pair below, because two locals of the same name are
  #       not a duplication Terraform tolerates and two spellings of one location are
  #       not a duplication a reader can keep straight. What the withdrawn block
  #       argued and this one keeps: the URI is COMPOSED from an input this module
  #       already holds rather than accepted whole, so a root cannot name a bucket the
  #       task role's statements are not scoped to -- which a free-form URI input would
  #       permit and which would surface as an authorization denial against a bucket
  #       nobody configured.
  # WHY : Refactoring Rationale: the withdrawn block split the environment in two --
  #       one list for staging, another for the states after it -- on the grounds that
  #       only staging reads the extract location. That premise does not hold here: the
  #       verification gate resolves every registered extract from the SAME location and
  #       refuses to run when it is unset, so the location is shared rather than
  #       staging-specific, and one block is what keeps the two from disagreeing about
  #       where the bytes are.

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

  # WHY : Refactoring Rationale: the extract location defaults to a prefix in the
  #       dataset bucket, where it used to default to an absolute FILESYSTEM path
  #       (/mnt/carddemo-extracts). That default could not run: the data-migration
  #       task definition mounts nothing there, the image ships no extract (its
  #       Dockerfile copies only src/ and sql/), and no resource in this repository
  #       provisions a file system for it -- so every Map branch resolved a source
  #       that existed nowhere. The bucket needs no new grant, because the
  #       data-migration task role already holds s3:GetObject across it for the
  #       generations the same step writes.
  # WHY : Alternatives Considered: an encrypted read-only EFS access point mounted on
  #       the task, which would have kept the filesystem contract. Rejected on cost
  #       and on operator burden together: it adds a file system, mount targets, a
  #       security group and a second place to hold the same bytes, and the operator
  #       still has to get the extracts into it -- which they do today with one
  #       `aws s3 cp`. Object storage is also where the step's OUTPUT already goes, so
  #       reading the input from the same bucket keeps one storage dependency.
  # WHY : Trade-offs: var.dataset_staging_root remains as an override rather than
  #       being removed, because the same command is run by hand in a checkout where a
  #       local directory is the natural source. Null means "use the bucket", which is
  #       what both environment roots leave it as.
  # WHY : Refactoring Rationale: the composed default is built from
  #       var.dataset_source_extract_prefix and not from a prefix input of this
  #       module's own. Two such inputs were authored -- one defaulting to
  #       "source-extracts" with a trailing slash refused, one to "inbox" -- and both
  #       are withdrawn at variables.tf. The measurement that decided it: the
  #       s3-datasets module owns this prefix, provisions its lifecycle rule, publishes
  #       it, and both environment roots wire it in as
  #       dataset_source_extract_prefix, whose default is "migration/source/EBCDIC/".
  #       Composing from anything else pointed the verification gate at a prefix
  #       nothing is ever synced to while the refresh command read the real one -- a
  #       divergence that was latent only because this value had lost its consumer.
  # WHY : Assumptions: no separator is inserted. dataset_source_extract_prefix's own
  #       validation REQUIRES a trailing slash, so the join is already well formed and
  #       adding one here would produce a key with an empty path segment.
  dataset_staging_root = coalesce(
    var.dataset_staging_root,
    "s3://${var.dataset_bucket_name}/${var.dataset_source_extract_prefix}",
  )

  # WHY : Assumptions: this is the whole environment the data-migration container
  #       reads, and it is shared by the seed-refresh branch and the verification gate
  #       so the two cannot come to disagree about where the extract is. CARDDEMO_BATCH_RUN_ID is
  #       read by the CLI as its execution token, and CARDDEMO_DATASET_STAGING_ROOT is
  #       where every command resolves the registered extract from. Everything else
  #       the commands need -- the environment name, the parameter prefix and the TLS
  #       settings -- is on the task definition itself rather than repeated per state.
  # WHY : Refactoring Rationale: CARDDEMO_DATASET_BUCKET is deliberately NOT here,
  #       and the staging branch used to pass it. Nothing in the data-migration
  #       distribution reads that variable: config.resolve_dataset_staging_settings
  #       reads the bucket name from Parameter Store instead, precisely so that a
  #       deployment which overrides the name has one authority for it. The readers
  #       are batch-service's Java jobs, and their own task states still pass it. An
  #       unread variable copied into a second state would have read as a contract.
  data_migration_environment = [
    {
      # WHY : Assumptions: this is not only a logging correlator. The staging command
      #       keys its GENERATION RESERVATION on this value, so it is what makes a
      #       retried Map branch reuse the generation its first attempt reserved
      #       instead of consuming a second one for a byte-identical copy. The
      #       execution name is the right identity for that because Step Functions
      #       holds it constant across a redrive, which is precisely the case that
      #       must replay rather than reallocate.
      Name      = "CARDDEMO_BATCH_RUN_ID"
      "Value.$" = "$$.Execution.Name"
    },
    {
      Name  = "CARDDEMO_DATASET_STAGING_ROOT"
      Value = local.dataset_staging_root
    },
  ]

  # WHY : Refactoring Rationale: every RunTask integration below stamps this marker so
  #       that a task these machines started can be FOUND after the state that started
  #       it has given up on it. Without it, a `.sync` state that timed out or was
  #       aborted left a task that may still be running and no way to enumerate it:
  #       ListTasks can filter by startedBy, family or service, and family would also
  #       match the tasks an ECS service runs from the same definition.
  # WHY : Assumptions: an ECS SERVICE sets its own startedBy of the form
  #       `ecs-svc/<deployment id>`, so a service-managed task can never carry this
  #       marker and the cancellation path cannot reach one. That is what makes
  #       stopping every task bearing the marker safe: the set is exactly the tasks
  #       this module's state machines launched.
  # WHY : Refactoring Rationale: the marker is SUPPLIED by the caller rather than
  #       composed here, and the reason is a dependency cycle rather than a
  #       preference. The resume function needs the same literal in its environment so
  #       the reconciling release can list the tasks it matches -- and this module
  #       already takes that function's ARN as an input, so a module OUTPUT carrying
  #       the marker would make the function depend on the module and the module
  #       depend on the function. Terraform refuses that outright. Making the value an
  #       input moves it to the one place that wires both, so there is exactly one
  #       spelling; the length bound it used to enforce by truncation is enforced by
  #       var.task_started_by's own validation instead, where a too-long value names
  #       the input at plan time rather than being silently shortened.
  task_started_by = var.task_started_by

  # WHY : Refactoring Rationale: these three RunTask parameters are single-sourced and
  #       merged into every RunTask state's Parameters below, and none of them was set
  #       anywhere. PropagateTags and EnableECSManagedTags occurred zero times, and
  #       task-definition tags do NOT reach a task launched directly by RunTask unless
  #       propagation is asked for -- so every transient task these machines start was
  #       arriving in Cost Explorer and in the tag-based views with none of the
  #       project, environment or owner tags the rest of the stack carries, which is
  #       precisely the attribution the nightly chain's Fargate spend needs.
  # WHY : Assumptions: TASK_DEFINITION rather than SERVICE, because these tasks have no
  #       service: RunTask launches them standalone, and SERVICE is only meaningful for
  #       a service-managed task. The tags therefore come from the task definition the
  #       ecs-service module tagged, which is the one place the stack's tag set is
  #       already applied to each workload.
  # WHY : Trade-offs: merging one local into nine Parameters blocks rather than writing
  #       three lines nine times. The merge is what makes the attribution structurally
  #       impossible to omit from a state -- a new RunTask state that forgets it is a
  #       visibly different shape from its neighbours -- and it is why the assertion
  #       these findings asked for can be a count of merge sites rather than a scan.
  run_task_attribution = {
    EnableECSManagedTags = true
    PropagateTags        = "TASK_DEFINITION"
    StartedBy            = local.task_started_by
  }

  ecs_retry = [{
    # WHY : Assumptions: a non-zero container exit does not throw an ECS API
    #       error in the synchronous integration; it is returned and handled by
    #       the Choice states below. This retry therefore covers launch and
    #       observation faults rather than replaying a deterministic application
    #       outcome.
    # WHY : Refactoring Rationale: States.Timeout was REMOVED from this list, and it
    #       was the more dangerous of the two entries. TimeoutSeconds expiring on a
    #       `.sync` ECS state does not stop the container -- Step Functions abandons
    #       the wait, and the task keeps running until it finishes on its own -- so
    #       retrying that error started a SECOND task of the same job while the first
    #       was still writing. For PostTransactions, CalculateInterest and the import
    #       job that is two concurrent writers against the same rows and the same
    #       ledger key, and the retry made it up to var.retry_max_attempts of them.
    #       Every state that retried on this error also carries a Catch, so removing
    #       the entry does not lose the failure: an expiry now goes straight to the
    #       failure path, which is where the cancellation sub-chain stops the
    #       abandoned task before the run is declared failed.
    # WHY : Alternatives Considered: keeping States.Timeout and relying on the
    #       BatchStepLedger idempotency key to make the duplicate harmless. Rejected
    #       because the ledger recognises a REPEAT of a completed step, not a
    #       CONCURRENT one -- the first attempt has not recorded its outcome yet
    #       while it is still running, so the second attempt reads no record and
    #       proceeds. The ledger is what makes a redrive safe, not what makes
    #       overlapping tasks safe.
    # WHY : Trade-offs: States.TaskFailed is KEPT. It is raised for a launch or
    #       observation fault -- a capacity failure, a pull failure, a task that
    #       stopped without the container reporting -- in each of which the task is
    #       already terminal, so a replacement attempt cannot overlap with anything.
    ErrorEquals     = ["States.TaskFailed"]
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

  # WHY : Refactoring Rationale: these bounds and the two rule sets below replace a
  #       "????-??-??" StringMatches pattern that was repeated at six Choice sites
  #       across all four machines, and every one of those six REFUSED the input it
  #       was written to admit. Amazon States Language treats exactly one character
  #       as special in a StringMatches pattern -- "*", escaped as "\\*" -- and no
  #       other character has any special meaning during matching, so each "?" was
  #       a literal question mark and "????-??-??" matched only the ten-character
  #       string "????-??-??" itself. A real businessDate therefore fell through to
  #       the Default edge: the daily chain, the ad-hoc report machine, the dataset
  #       round trip and the authorization unload all refused every well-formed
  #       request. The rules are single-sourced here rather than restated per site
  #       because six copies of one predicate is how a single wrong pattern became
  #       six wrong graphs; one definition can only ever be wrong once.
  # WHY : Assumptions: the bounds are LEXICAL string comparisons, which is why they
  #       are written as whole dates rather than as a year range. Amazon States
  #       Language compares strings the way Java's compareTo does, so for any input
  #       that begins with a digit the comparison is decided on the leading
  #       characters alone: "2022-07-18" sits inside the pair, while "not-a-date"
  #       exceeds the upper bound on its first character ("n" > "9") and "--"
  #       falls below the lower bound on its first character ("-" < "1"). That is
  #       what makes the pair reject a wrongly-shaped value whose dashes happen to
  #       satisfy the glob, and why a fractional-second or offset suffix on a
  #       timestamp is immaterial to it.
  iso_date_lower_bound      = "1000-01-01"
  iso_date_upper_bound      = "9999-12-31"
  iso_timestamp_lower_bound = "1000-01-01T00:00:00Z"
  iso_timestamp_upper_bound = "9999-12-31T23:59:59Z"

  # WHY : Alternatives Considered: expressing the real calendar shape with JSONata,
  #       by setting QueryLanguage on these Choice states and testing
  #       $contains($states.input.businessDate, /^\d{4}-\d{2}-\d{2}$/). It is the
  #       only form that would reject 2022-13-45 in the graph, and it was rejected
  #       here because it cannot be verified without a live account: nothing in this
  #       repository or its CI evaluates Amazon States Language, so adopting a
  #       second query language inside an otherwise JSONPath machine would replace a
  #       check known to be wrong with one nobody can demonstrate to be right. The
  #       operators used instead are the ones this module already relies on
  #       everywhere else, and every one of them is exercised against valid and
  #       invalid values by data-migration/tests/test_step_functions_asl_contract.py.
  #       Also considered and rejected: IsTimestamp for the scheduler's fire time,
  #       which would re-create exactly the defect being fixed the first time the
  #       service emits a fractional second or a numeric offset; and a validating
  #       Lambda, which is a function, a role and a log group for one string test.
  # WHY : Trade-offs: these four rules per field check PRESENCE, TYPE and SHAPE and
  #       deliberately not the calendar, so a well-shaped impossible date still
  #       reaches the container. That is the same division of labour the module
  #       documented before, and it is the right one: the graph check exists to stop
  #       an UNSET or transposed input from starting a Fargate task, and
  #       BusinessDate's width check followed by LocalDate.parse refuses the rest
  #       with the offending value in the message. The rules are ordered
  #       presence-then-type-then-value because a comparison against an absent path
  #       has nothing to compare, so the guard has to be evaluated first.
  iso_date_rules = {
    for field in ["businessDate", "startDate", "endDate"] : field => [
      {
        Variable  = "$.${field}"
        IsPresent = true
      },
      {
        Variable = "$.${field}"
        IsString = true
      },
      {
        # A date carries two separators, so the glob admits "2022-07-18" and
        # rejects an undelimited "20220718"; the bounds below reject the
        # wrongly-shaped values this pattern alone would still admit.
        Variable      = "$.${field}"
        StringMatches = "*-*-*"
      },
      {
        Variable                = "$.${field}"
        StringGreaterThanEquals = local.iso_date_lower_bound
      },
      {
        Variable             = "$.${field}"
        StringLessThanEquals = local.iso_date_upper_bound
      },
    ]
  }

  # WHY : Assumptions: the scheduler's payload carries a fire TIME rather than a
  #       business date, so its shape test requires the uppercase "T" that
  #       infra/modules/eventbridge-scheduler's <aws.scheduler.scheduled-time>
  #       substitution produces and that RFC3339 requires, and the date part is taken
  #       from it one state later with States.StringSplit. The bounds are the
  #       timestamp forms of the pair above for the same lexical reason, and a
  #       fractional second or a numeric offset is immaterial to them because the
  #       comparison is decided on the leading year digit.
  iso_timestamp_rules = [
    {
      Variable  = "$.scheduledTime"
      IsPresent = true
    },
    {
      Variable = "$.scheduledTime"
      IsString = true
    },
    {
      Variable      = "$.scheduledTime"
      StringMatches = "*-*-*T*"
    },
    {
      Variable                = "$.scheduledTime"
      StringGreaterThanEquals = local.iso_timestamp_lower_bound
    },
    {
      Variable             = "$.scheduledTime"
      StringLessThanEquals = local.iso_timestamp_upper_bound
    },
  ]

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
  # WHY : Refactoring Rationale: this pair is grounded in the PROGRAM's exit contract
  #       and no longer in app/jcl/TRANBKP.jcl:51, and the correction matters because
  #       the earlier reading was not available to that JCL. It described
  #       CBTRN02C.cbl:229-230 as the producer of the 4 and TRANBKP.jcl:51's
  #       COND=(4,LT) as its consumer, "at opposite ends of the baseline". A JCL COND
  #       parameter can only test the return codes of steps in ITS OWN JOB, and the two
  #       sit in different jobs: CBTRN02C runs as POSTTRAN.jcl's single STEP15, while
  #       TRANBKP.jcl:51 gates STEP10 on its own STEP05R (the REPROC unload, :23) and
  #       STEP05 (the IDCAMS DELETE, :37). So that COND cannot observe the posting
  #       return code at all, and citing it as the warn tier's authority pointed a
  #       reader at a gate that means something else -- specifically, at a safety
  #       interlock about whether the master was backed up before being deleted.
  # WHY : Assumptions: NOTHING in the baseline JCL gates on the posting return code.
  #       app/jcl/POSTTRAN.jcl is a single-step job carrying no COND= parameter, so the
  #       code CBTRN02C sets reaches JES and the operator rather than another step, and
  #       the scheduler definitions in app/scheduler/ sequence the jobs by completion
  #       events (Control-M IN/OUT conditions) rather than by return code. The warn
  #       tier is therefore read off the two places that DO state it: the program,
  #       which sets 4 at :230 only when WS-REJECT-COUNT is positive at :229 and
  #       otherwise leaves 0; and the suite's own condition-code rubric in
  #       tests/README.md section 8, which assigns 4 to "warn / soft reject -- e.g. a
  #       business-rule reject was correctly written". Both say the same thing: a
  #       reject night is a successful night with rejects in it.
  # WHY : Trade-offs: continuing on 4 rather than failing on it is the choice, and it is
  #       the baseline's behaviour rather than a relaxation. A reject is a normal
  #       operating condition -- app/jcl/POSTTRAN.jcl:34-38 allocates DALYREJS(+1) as a
  #       NEW generation on every run precisely because rejects are expected -- so
  #       treating 4 as a failure would report every night that rejected one transaction
  #       as a failed batch run and skip the interest, backup, combine, statement and
  #       report work the baseline unconditionally performs. The cost accepted is that
  #       the chain does not stop to have the reject stream reviewed; that review is an
  #       operator action on the DALYREJS generation, and the reject count is what the
  #       Choice below branches on to raise the warning.
  # WHY : Assumptions: the backup-before-delete interlock TRANBKP.jcl:51 really does
  #       express is preserved elsewhere and is recorded here so the two are not
  #       confused again. Its STEP05R unloads the master to TRANSACT.BKUP(+1) (:33,
  #       LRECL=350 RECFM=FB), STEP05 DELETEs the cluster and its alternate index (:40,
  #       :43), and the gated STEP10 re-DEFINEs the master (:54) only if both went
  #       acceptably -- so an empty master is never created over a failed backup. In the
  #       target there is no delete-and-redefine at all: the BackupTransactions state
  #       exports a new S3 generation and the relation it read stays in place, so the
  #       interlock has nothing to guard. The two IF MAXCC LE 08 THEN SET MAXCC = 0
  #       lines at :42 and :45, which normalise a not-found DELETE down to zero, are
  #       still the reason the target's own drop-if-exists behaviour must be non-fatal
  #       and idempotent.
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

  # WHY : ⚠️ Refactoring Rationale: the two release states had NO result selector and no gate
  #       after them, so the flag's resulting state was returned by the function and read by
  #       nothing. That was the gap behind the write-strand: the release function deleted its
  #       lease before writing the flag, so a failed Parameter Store write left a retry unable to
  #       tell a completed release from an abandoned one -- it reported success with the flag still
  #       reading false, and both graph paths went straight to a terminal state. Lifting these
  #       three members is what lets the two Choice states below assert the outcome instead of
  #       assuming it, and onlineWritesEnabled is the member that carries it: the function reports
  #       the flag's ACTUAL state, not the state it was configured to impose.
  # WHY : Trade-offs: three members are lifted rather than the whole payload, matching the lease
  #       selector above. onlineWritesEnabled is what the gates test; the other two are what an
  #       operator reading a stranded execution needs, and they answer opposite questions --
  #       releaseRefusedReason means the flag was deliberately left alone, while
  #       leaseRetainedReason means the flag WAS restored and only the lease outlived it. Folding
  #       them into one field would leave onlineWritesEnabled as the only way to tell the two
  #       apart, which is the ambiguity the strand hid behind.
  lambda_release_result_selector = {
    "onlineWritesEnabled.$"  = "$.Payload.onlineWritesEnabled"
    "releaseRefusedReason.$" = "$.Payload.releaseRefusedReason"
    "leaseRetainedReason.$"  = "$.Payload.leaseRetainedReason"
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
  #           DCB=(RECFM=F,LRECL=350,BLKSIZE=0). That separation is what the
  #           REFERENCE's state 7 depends on, and the migrated chain does not inherit
  #           it: the accrual pass commits its generated rows to ledger.transactions
  #           as well, so combine reads one relation rather than two datasets. The
  #           difference is registered as D-COMBINE-BACKUP-SUPERSET and
  #           D-COMBINE-GENERATION-BYPASS in
  #           docs/architecture/cobol-to-service-traceability.md.
  #         BackupTransactions <- app/jcl/TRANBKP.jcl:23-33, unloading the master
  #           to TRANSACT.BKUP(+1) at LRECL=350 RECFM=FB.
  #         CombineTransactions <- app/jcl/COMBTRAN.jcl, whose STEP05R at :22 sorts
  #           a CONCATENATED SORTIN of TRANSACT.BKUP(0) at :24 and SYSTRAN(0) at
  #           :26 on TRAN-ID (:30) into TRANSACT.COMBINED(+1) at :37, then REPROs
  #           it back into the master at :48. Assumptions: those two (0) references
  #           read the generations the reference's own predecessors have just created,
  #           which is why this state is ordered after BOTH state 5 and state 6 rather
  #           than beside either. The migrated ordering requirement is the same and
  #           its reason is not: state 7 requires a current generation of each family
  #           as a PRECONDITION and reads neither payload, so what it actually needs
  #           from its predecessors is their COMMITS -- the rows they wrote to
  #           ledger.transactions. Trade-offs: the precondition is kept rather than
  #           dropped because it preserves the reference's failure mode, a night whose
  #           posting or accrual step produced no generation stopping here by name
  #           instead of quietly combining a stale relation. Neither COMBTRAN step
  #           carries a COND=, so both take the default success edge per form (a) in
  #           the header.
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
      Parameters = merge(local.run_task_attribution, {
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
      })
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
  # WHY : Refactoring Rationale: this state writes NO generation prefix, and the note
  #       here said it did. The correction matters because the two mechanisms have
  #       different retention: reporting-service publishes its artifacts at FIXED
  #       object keys under its own configured statement prefix, so a rerun replaces the
  #       previous night's artifact as a new object VERSION over the same key.
  # WHY : Assumptions: the run composes FOUR keys under that prefix, not two, and the
  #       fourth is why bucket versioning on this prefix is load-bearing rather than
  #       merely convenient. GenerateStatementsTask composes prefix + statements.txt,
  #       prefix + statements.html and prefix + statements-index.txt -- the per-card
  #       record ranges a single-card read bisects -- and then prefix +
  #       statements-current.txt, the generation pointer, published LAST. S3ArtifactWriter
  #       completes one upload per key and publishes only on an explicit complete(), so a
  #       failed run aborts and leaves every one of them at its previous version.
  # WHY : Trade-offs: the pointer stores the three artifacts' object VERSION IDENTIFIERS
  #       rather than copies of their bytes, which is what makes a generation switch one
  #       write instead of three. The cost is a hard dependency on versioning staying
  #       enabled for this prefix: with versioning suspended the stored identifiers stop
  #       resolving and every statement read fails closed. infra/modules/s3-datasets
  #       enables it bucket-wide, so the dependency is satisfied by construction and is
  #       recorded here because nothing in this file would otherwise show that suspending
  #       it breaks a reader rather than merely losing history. Neither statement dataset has a DEFINE GENERATIONDATAGROUP base
  #       anywhere in the baseline -- an exhaustive search matches only
  #       app/jcl/DEFGDGB.jcl, DEFGDGD.jcl, DALYREJS.jcl and REPTFILE.jcl, none of which
  #       defines a statement base -- and infra/modules/s3-datasets holds their prefixes
  #       in a separate input for exactly that reason. Describing this state as writing a
  #       generation would make the family count twelve where the AAP, that module's own
  #       validation and two sibling documents all publish ten.
  # WHY : Assumptions: what the baseline actually expresses, and what versioning
  #       reproduces, is REPLACE-NEVER-APPEND. STEP030 at :66 runs IEFBR14 with
  #       DISP=(MOD,DELETE,DELETE) purely to delete the previous run's two outputs, and
  #       STEP040 at :79 then creates them fresh with DISP=(NEW,CATLG,DELETE). Completing
  #       an upload over the same key is that pair in one operation, and it is strictly
  #       more recoverable than the baseline, which retained nothing of the deleted copy.
  #       Recorded as observations of an immutable baseline rather than asserted defects,
  #       two artifacts of that job are visible while reading it and neither is
  #       reproduced here: overlapping corrupted text at :90, and the HTML dataset
  #       declared at LRECL=80 in STEP030 against LRECL=100 at :94.
  # WHY : Refactoring Rationale: GenerateReports replaces app/jcl/TRANREPT.jcl,
  #       whose STEP10R at :59 runs PGM=CBTRN03C and writes TRANREPT(+1) at :80
  #       with DCB=(LRECL=133,RECFM=FB,BLKSIZE=0) -- the 133-column report --
  #       together with app/jcl/PRTCATBL.jcl, a DFSORT-only job with no COBOL
  #       program that builds the category-balance report from TCATBALF.BKUP(+1).
  #       Assumptions: BOTH reports are now produced, and this note used to claim two
  #       while only one existed. GenerateReportsTask drives two publishers in the state's
  #       one run: ReportArtifactPublisher for the 133-column transaction report, and
  #       CategoryBalanceArtifactPublisher for the 40-byte category-balance report over
  #       reporting.v_transaction_category_balances. The second takes NO date, because
  #       PRTCATBL.jcl:44-45 feeds its sort the whole unloaded file with no INCLUDE
  #       condition, unlike TRANREPT.jcl:47-48 which does carry one -- so it is a full
  #       print of current balances rather than a period report.
  # WHY : Assumptions: the transaction report is published to TWO keys from ONE generation
  #       pass, and only one of them is a generation. The reference writes TRANREPT(+1)
  #       against a base defined LIMIT(5) SCRATCH at app/jcl/DEFGDGB.jcl:37-39, so the
  #       generation key carries that retention contract; the request-scoped key is what a
  #       range-addressed request and the runbooks resolve. Until both existed the TRANREPT
  #       family had no production writer at all, so the prefix and lifecycle rule
  #       infra/modules/s3-datasets provisions for it governed nothing. The
  #       category-balance report is keyed FIXED with no date partition, because no
  #       GENERATIONDATAGROUP base for TCATBALF.REPT exists anywhere in the baseline and
  #       PRTCATBL.jcl:21-25 deletes the prior copy before rewriting it -- which object
  #       versioning expresses as a new current version over one key.
  #       The ordering gain is real and worth naming, and it is not the one this note
  #       used to claim. TRANREPT.jcl RE-DOES the very same REPROC unload that
  #       TRANBKP.jcl performs, both writing TRANSACT.BKUP(+1), because each JCL job is
  #       written to be self-contained -- so the baseline unloads the transaction master
  #       TWICE per night. This chain unloads ONCE, in state 6.
  # WHY : Refactoring Rationale: the withdrawn half of that claim was that "states 7 and
  #       9 read that one generation". Neither does. State 6's BackupTransactionsJob
  #       exports the transact-bkup generation and nothing in the chain reads it back:
  #       CombineTransactionsJob walks ledger.transactions through TransactionRepository
  #       and writes the transact-combined generation, and reporting-service's report and
  #       statement tasks read the reporting VIEWS through their own repositories. So the
  #       duplicated unload is removed because the later states never needed a flat file
  #       at all -- the relation is still there to be read -- rather than because they
  #       share one file's output. Stating it the old way would send an operator
  #       diagnosing a combine or report failure to an S3 generation that step does not
  #       open, and would imply a dependency that would make state 6 a precondition of
  #       states 7 and 9 when it is not.
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
  #       steps both named STEP05R, at :23 and :37. That single sort key is why the
  #       TRANSACT.DALY generation state 6 stages carries a transaction-identifier
  #       tie-break the reference does not, registered as D-DALY-CARD-TIE-BREAK: one
  #       control field leaves the order within a card decided by an installation
  #       option rather than by the job, and a staged generation whose bytes differ
  #       between two runs over the same rows cannot serve as a comparison baseline.
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
  #       because a serving task publishes /actuator/prometheus for a scraper to read,
  #       and enabling both paths on one task definition would export every meter twice
  #       the moment a scraper is introduced. A reporting run started here has no listener to
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
  # WHY : Refactoring Rationale: these three overrides were spelled
  #       MANAGEMENT_OTLP_METRICS_EXPORT_{ENABLED,STEP,URL}, and in that form they did
  #       nothing. spring-boot-starter-opentelemetry 4.1.0 installs
  #       OpenTelemetryEnvironmentVariableEnvironmentPostProcessor, which maps OTEL_*
  #       variables onto Spring properties and adds the result with
  #       MutablePropertySources.addFirst -- above the system environment. The
  #       reporting task definition carries OTEL_METRICS_EXPORTER=none, because
  #       ecs-service creates a service for it and its serving mode is scraped, and
  #       that mapped value beat the MANAGEMENT_* override for the same property. The
  #       statement and report steps therefore exported no counter and no timer while
  #       appearing configured. Overriding the OTEL_* name instead lands in the same
  #       channel: an ECS container override replaces a task-definition variable of the
  #       same name, so "otlp" wins for the lifetime of the run and reverts for the
  #       serving task.
  # WHY : Assumptions: the interval is expressed in MILLISECONDS rather than as a
  #       Spring duration, because the framework reads this variable through
  #       Duration.ofMillis as the OpenTelemetry specification defines it -- "15s"
  #       here is rejected as a non-numeric duration and leaves the registry default.
  # WHY : Trade-offs: the fifteen-second step matches what ecs-service gives the
  #       other task-mode workloads and for the same reason -- the registry's own
  #       default publishes once a minute, and a report that finishes inside that
  #       window would exit having exported nothing, because the shutdown flush is
  #       best effort. Four times the export volume for a nightly job is negligible
  #       against losing its metrics entirely.
  reporting_metrics_push_environment = [
    {
      Name  = "OTEL_METRICS_EXPORTER"
      Value = "otlp"
    },
    {
      Name  = "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT"
      Value = "http://127.0.0.1:4318/v1/metrics"
    },
    {
      Name  = "OTEL_METRIC_EXPORT_INTERVAL"
      Value = "15000"
    },
  ]

  reporting_task_states = {
    for state_name, job_config in local.reporting_jobs :
    state_name => {
      Type           = "Task"
      Resource       = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
      TimeoutSeconds = local.state_timeouts[state_name]
      Parameters = merge(local.run_task_attribution, {
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
      })
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
        # WHY : Trade-offs: what this state checks is that the input is PRESENT, is a
        #       string, and carries the separators of a date -- not that the date
        #       exists in the calendar, because Amazon States Language has no date
        #       type and a Choice cannot call out to one. A well-shaped impossible
        #       date is therefore admitted here and refused one state later by
        #       BusinessDate's width check and LocalDate.parse, with the offending
        #       value in the message. Accepted because the check exists to stop an
        #       UNSET or transposed input from quiescing online writes and starting a
        #       Fargate task, which is the mistake that actually happens. Both rule
        #       sets come from local.iso_date_rules and local.iso_timestamp_rules so
        #       that this entry gate and the three on-demand machines' gates cannot
        #       drift apart; see the rationale at those locals for why the previous
        #       "????-??-??" pattern admitted nothing at all.
        ValidateExecutionInput = {
          Type = "Choice"
          Choices = [
            {
              And  = local.iso_date_rules["businessDate"]
              Next = "PrepareProvidedBusinessDate"
            },
            {
              And  = local.iso_timestamp_rules
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
        # WHY : Assumptions: both preparation states also seed the cancellation counter
        #       at zero, and it has to be seeded HERE rather than where it is read. The
        #       cancellation loop on the failure path increments it with States.MathAdd
        #       against $.cancellation.attempts, and that function raises
        #       States.Runtime on an absent path rather than treating it as zero -- so a
        #       counter introduced only inside the loop would fail the first increment
        #       on every failing execution, which is precisely the moment the loop has
        #       to work. Both entry states set it because either can be the one that
        #       runs.
        PrepareProvidedBusinessDate = {
          Type = "Pass"
          Parameters = {
            "businessDate.$"  = "$.businessDate"
            "executionName.$" = "$$.Execution.Name"
            seedDatasets      = var.seed_datasets
            cancellation      = { attempts = 0 }
          }
          Next = "QuiesceOnlineWrites"
        }

        PrepareScheduledBusinessDate = {
          Type = "Pass"
          Parameters = {
            "businessDate.$"  = "States.ArrayGetItem(States.StringSplit($.scheduledTime, 'T'), 0)"
            "executionName.$" = "$$.Execution.Name"
            seedDatasets      = var.seed_datasets
            cancellation      = { attempts = 0 }
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
              #       it, so on its own a reader cannot distinguish a live
              #       quiesce from one abandoned by an execution that stopped
              #       without resuming. leaseSeconds is what bounds that: the
              #       quiesce handler CONSUMES it as the expiry it records on the
              #       lease item, so the bracket becomes an ownership record that
              #       lapses on its own rather than a flag someone must remember
              #       to clear. leaseStartedAt is consumed by nothing and is sent
              #       for the execution history, where it is the one place the
              #       window's start is visible beside the transition that opened
              #       it.
              #       Alternatives Considered: a separate lease-length input.
              #       Rejected because it could be set below
              #       state_machine_timeout_seconds and would then record an
              #       expiry that lapses while the chain is still legitimately
              #       running -- the one way a lease can do harm, because a
              #       lapsed lease is one the next execution may take over.
              #       Deriving it from the execution ceiling makes that
              #       unrepresentable, because the ceiling IS the longest the
              #       bracket can be held by a running execution.
              #       Assumptions: ASL has no date arithmetic, so the two parts
              #       travel separately and a reader adds them. The resume
              #       handler consumes neither -- it reads expectedLeaseOwner and
              #       proves ownership against the stored lease instead -- and it
              #       ignores payload keys it does not consume, so sending them
              #       on this edge cannot change the call the other edge makes.
              # WHY : Assumptions: the execution ARN travels ALONGSIDE the name
              #       rather than instead of it, because the two are read by
              #       different consumers. Every release names the OWNER, and the
              #       owner is a name -- it is what an operator reads off the lease
              #       and what the graph passes back from $.quiesce.leaseOwner. The
              #       ARN is the only form DescribeExecution accepts, so it is what
              #       the scheduled reconciler needs in order to establish that a
              #       lease outliving its owner outlived a TERMINAL owner rather
              #       than one still posting transactions.
              #       Alternatives Considered: composing the ARN in the reconciler
              #       from the stored name and this machine's ARN. Rejected because
              #       it would put a state-machine ARN and an execution-ARN
              #       formatting rule inside the function, where a change to either
              #       would silently stop the watchdog verifying anything -- and the
              #       failure mode of a watchdog that verifies nothing is a release
              #       granted while the chain is still running.
              #       Trade-offs: $$.Execution.Id is the ARN despite its name; ASL
              #       exposes no $$.Execution.Arn, so the misleading spelling is the
              #       correct reference and is named here so a reader does not
              #       "correct" it.
              "leaseOwnerArn.$" = "$$.Execution.Id"

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
        #       an alternate index. A Map over one list expresses eleven instances of a
        #       single operation more honestly than eleven near-identical states, and it
        #       keeps the dataset list an input (var.seed_datasets) instead of eleven
        #       pieces of graph. The count rises from the baseline's ten because
        #       var.seed_datasets also carries `transactions`, which the baseline
        #       refreshed through TRANFILE.jcl but which this chain stages and
        #       reconciles WITHOUT loading, for the reason recorded below.
        # WHY : Refactoring Rationale: each branch now invokes `refresh-dataset`, and it
        #       used to invoke `stage-dataset`. That was the defect this state carried:
        #       `stage-dataset` copies an extract's bytes into a versioned generation
        #       prefix and stops there, so a chain that ran end to end left eleven objects
        #       in S3 and TEN EMPTY TABLES -- eleven branches stage, and ten of them own a
        #       table to fill -- with a green execution history. None of the
        #       three things an IDCAMS DELETE/DEFINE/REPRO actually accomplishes -- the
        #       master exists, it holds the records, the records are the ones that were
        #       shipped -- was performed or checked anywhere in the chain. The single
        #       `refresh-dataset` command performs the whole per-dataset sequence: fetch
        #       the extract from the provisioned source prefix, stage its bytes as the
        #       new generation, decode it per field and bulk-load it into the schema
        #       that owns it, verify the load by row count, by per-record checksum and
        #       by exact money totals, and -- for the one dataset that feeds
        #       ledger.transactions -- advance the identifier allocator past the rows it
        #       loaded. It stops at the first step that does not succeed and exits with
        #       that step's own status, so the branch's exit-code Choice below still
        #       distinguishes success from failure without knowing which step ran.
        # WHY : Assumptions: the load and the three passes are composed only for a dataset
        #       whose layout SHIPS a committed extract, so one branch of the eleven --
        #       `transactions` -- stages its generation, reconciles the allocator and
        #       reports success without loading anything. The exemption belongs to the ETL
        #       and is stated in that branch's log: no TRANSACT extract is committed, the
        #       registry points the token at the single 350-byte initializer record
        #       app/jcl/TRANFILE.jcl REPROs, and the verification query at state 3
        #       REQUIRES ledger.transactions to hold zero rows because posting is what
        #       fills it. A branch that loaded it would turn a green Map into a failed
        #       gate two states later, which is the failure mode hardest to attribute.
        # WHY : Alternatives Considered: expressing those steps as five further states
        #       inside this Map's ItemProcessor. Rejected on three counts. AAP section
        #       0.4.1.7 fixes the chain at ELEVEN states and describes this one as "a
        #       Map state, one branch per dataset, each a runTask.sync", so five states
        #       per branch would publish a different machine from the documented one.
        #       Each state is a separate Fargate task with its own cold start and its own
        #       Aurora connection, so eleven datasets would open fifty-five task lifecycles
        #       to do what eleven can. And the sequence is inseparable in practice: a load whose
        #       verification lives in a different state can be left committed and
        #       unverified by an orchestration failure between the two, whereas one task
        #       per dataset makes the branch's own Retry replay the whole refresh of that
        #       one dataset -- which is exactly the unit that has to be idempotent.
        # WHY : Assumptions: the refresh is idempotent per dataset, which is what makes
        #       the branch Retry below safe. Staging replays its reserved generation
        #       rather than consuming a second one (the execution name below is the
        #       reservation key), the load stages and merges rather than inserting
        #       blindly, the three verification passes write nothing, and the allocator
        #       step only ever advances. A retried branch therefore converges on the same
        #       state instead of accumulating a second copy of the same bytes.
        # WHY : Assumptions: the Map branches take the DEFAULT SUCCESS EDGE, per form
        #       (a) in the header block, because NONE of those ten jobs carries a
        #       COND= parameter at all -- there is no baseline gate on any of them to
        #       invert. A non-zero branch is therefore an infrastructure or data
        #       fault, which the branch's Catch surfaces rather than a condition code
        #       the chain was meant to interpret.
        # WHY : Trade-offs: MaxConcurrency is an input rather than unbounded. Eleven
        #       branches at once would each open its own Aurora connection and its own
        #       bulk-copy stream against the cluster this same chain is about to post
        #       through, so the ceiling exists to bound the concurrent load on one
        #       writer rather than to bound Fargate. Setting it to 1 is legal and
        #       makes the refresh strictly sequential, which is what the baseline's
        #       ten separate jobs actually did.
        # WHY : Refactoring Rationale: state 2 is a Parallel wrapping ONE branch, and it
        #       used to be the Map that is now the first state of that branch. The wrapper
        #       exists so that the verification gate below runs INSIDE state 2 rather than
        #       beside it. AAP section 0.4.1.7 fixes the nightly chain at ELEVEN top-level
        #       work states and names every one of them; the gate is mandated separately --
        #       AAP section 0.9.2 makes combined post-load verification a first-class
        #       deliverable and section 0.7.7 specifies its three passes -- but 0.4.1.7
        #       gives it no state of its own. Authored as a TWELFTH top-level state it
        #       published a chain length the frozen plan does not carry, and the module's
        #       own README then had to claim both twelve states and an eleven-state
        #       contract "this module must not change". Authored here it is reached on
        #       exactly the same edge, in exactly the same order, and the published
        #       top-level chain is the eleven the plan names.
        # WHY : Assumptions: nesting a real workload inside state 2 is the precedent state
        #       2 ALREADY sets, not a relabelling invented to reach a number.
        #       RefreshSeedDataset is a Fargate task that fetches, stages, loads and
        #       verifies one dataset, and nobody counts it among the eleven, because it
        #       runs inside this state's sub-graph. VerifyMigration sits at the same
        #       nesting level for the same reason, so "eleven" keeps meaning here what it
        #       means everywhere else in this file: eleven TOP-LEVEL work states. The
        #       daily graph holds far more ASL states than eleven once the Choice, Pass
        #       and Fail states are counted, so a count of top-level WORK states was
        #       always the published quantity.
        # WHY : Assumptions: the three properties that made the gate worth adding are
        #       untouched by the move. It still runs on the SELECT-only verification login
        #       rather than the loader's identity, it still executes the two committed
        #       WHOLE-MIGRATION queries whose digests the per-dataset passes pin, and it is
        #       still the only edge into PreflightDailyTransactions -- now because this
        #       state's own Next is that edge and no state in the branch can leave the
        #       branch by any other route.
        # WHY : Alternatives Considered: deleting the gate to reach eleven and relying on
        #       the three passes the per-dataset refresh already runs. Rejected. Those
        #       passes run per dataset, under the loader's own login, so dropping the gate
        #       gives up the SELECT-only session AND both whole-migration queries -- a real
        #       reduction in a control AAP section 0.9.2 mandates, to fix a count that
        #       nesting fixes at no cost.
        # WHY : Alternatives Considered: a nested child state machine holding the two
        #       states. Rejected because it adds a fifth machine with its own execution
        #       role, log group and alarm surface, and because section 0.4.1.7 describes
        #       state 2 as "a Map state, one branch per dataset, each a runTask.sync" -- a
        #       Parallel whose branch STARTS at that Map keeps that description true of
        #       what runs, where a child machine would replace it.
        # WHY : Trade-offs: a Parallel with one branch buys nothing concurrent, and that is
        #       the point -- it is used here purely as the scoping construct that lets one
        #       top-level state hold a sequence. The cost is one extra state transition per
        #       run and a sub-graph a reader must open to see the gate; against that, the
        #       published chain length is a measured property of the top-level state list
        #       again instead of a number the plan and the module disagree about.
        StageSeedDatasets = {
          Type = "Parallel"

          # WHY : Assumptions: this state carries NO TimeoutSeconds. The States Language
          #       defines that field for Task and Activity states and for the machine top
          #       level; it is absent from the Parallel field list. The ceiling is therefore
          #       applied where the language accepts it -- on the Map below and on each of
          #       the two tasks inside the branch, from the same local.state_timeouts
          #       entries as before -- so nothing became unbounded, and the machine's own
          #       var.state_machine_timeout_seconds still bounds the whole run.
          # WHY : Assumptions: this state carries NO Retry either, and unlike the timeout
          #       that is a SEMANTIC choice rather than a field-support one -- the language
          #       does allow Retry on a Parallel. Re-entering it would re-run the refresh of
          #       every dataset AND the gate, so a transient fault in the gate would replay
          #       eleven completed loads to redo one read-only pass. The retries that matter
          #       already sit at the states that do the work; the Catch below is what a
          #       failure needs from this level.
          Branches = [{
            StartAt = "RefreshEachSeedDataset"
            States = {
              # WHY : Refactoring Rationale: this Map is named RefreshEachSeedDataset and it
              #       used to BE state 2 under the name StageSeedDatasets. The name moved to
              #       the Parallel that now wraps it, because that is the state the chain's
              #       published eleven names, and a Map and its wrapper cannot share one
              #       name. "Each" rather than "All" so that the plural Map and the singular
              #       RefreshSeedDataset task inside it do not read as the same state.
              RefreshEachSeedDataset = {
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
                  StartAt = "RefreshSeedDataset"
                  States = {
                    RefreshSeedDataset = {
                      Type           = "Task"
                      Resource       = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
                      TimeoutSeconds = local.state_timeouts.StageSeedDatasets
                      Parameters = merge(local.run_task_attribution, {
                        Cluster              = var.ecs_cluster_arn
                        TaskDefinition       = var.data_migration_task_definition_arn
                        LaunchType           = "FARGATE"
                        NetworkConfiguration = local.network_configuration
                        Overrides = {
                          ContainerOverrides = [{
                            Name = var.data_migration_container_name
                            # WHY : Assumptions: the extract prefix travels as a COMMAND
                            #       ARGUMENT while the bucket travels as an environment
                            #       variable, and the asymmetry follows the baseline's own
                            #       split that AAP rule T6 carries over: a JCL `PARM=` becomes
                            #       a job parameter and a `DD DSN=` becomes configuration. The
                            #       prefix is the per-run instruction "read the extracts from
                            #       here", so it belongs in the definition where a reviewer
                            #       reading the state machine can see which prefix a nightly
                            #       execution read; the bucket is deployment configuration that
                            #       every command in the image shares.
                            "Command.$" = "States.Array('refresh-dataset', States.Format('--dataset={}', $.dataset), States.Format('--business-date={}', $.businessDate), '--extract-prefix=${var.dataset_source_extract_prefix}')"
                            # WHY : Refactoring Rationale: this branch reads the SHARED environment
                            #       block rather than an inline list of its own, restoring the shape
                            #       the block was introduced for. An inline list stood here and it had
                            #       drifted in two ways worth recording. It passed
                            #       CARDDEMO_DATASET_BUCKET, which nothing in the data-migration
                            #       distribution reads -- config.resolve_dataset_staging_settings takes
                            #       the bucket name from Parameter Store, precisely so a deployment
                            #       that overrides the name has one authority for it -- and the shared
                            #       block's own comment already recorded that omission as deliberate,
                            #       so the two disagreed. And it omitted
                            #       CARDDEMO_DATASET_STAGING_ROOT, which the verification gate below
                            #       refuses to run without, so the two states that resolve the same
                            #       extracts were told about them differently.
                            # WHY : Assumptions: the generation-reservation property this branch
                            #       depends on is unaffected. CARDDEMO_BATCH_RUN_ID is the first entry
                            #       of the shared block and carries the same execution name, so a
                            #       retried branch still reuses the generation its first attempt
                            #       reserved instead of consuming a second one for a byte-identical
                            #       copy.
                            Environment = local.data_migration_environment
                          }]
                        }
                      })
                      ResultSelector = local.ecs_result_selector
                      ResultPath     = "$.stageTask"
                      Retry          = local.ecs_retry
                      Catch = [{
                        ErrorEquals = ["States.ALL"]
                        ResultPath  = "$.failure"
                        Next        = "DatasetRefreshFailed"
                      }]
                      Next = "CheckDatasetRefreshExitCode"
                    }

                    CheckDatasetRefreshExitCode = {
                      Type = "Choice"
                      Choices = [{
                        Variable      = "$.stageTask.exitCode"
                        NumericEquals = 0
                        Next          = "DatasetRefreshSucceeded"
                      }]
                      Default = "DatasetRefreshFailed"
                    }

                    DatasetRefreshSucceeded = {
                      Type = "Succeed"
                    }

                    # WHY : Assumptions: the Cause names the whole sequence rather than the
                    #       staging step, because the task performs up to six operations -- how
                    #       many depends on the dataset -- and any of them can be the one that
                    #       failed. The container stops at the first that did not succeed and
                    #       logs which step it was together with that step's own status, so the
                    #       log line -- not this Cause -- is where the diagnosis lives; naming
                    #       one step here would point most readers at the wrong one.
                    DatasetRefreshFailed = {
                      Type  = "Fail"
                      Error = "DatasetRefreshFailed"
                      Cause = "The data-migration refresh task did not complete with exit code zero; its log names which of the fetch, stage, load, verification or allocator steps stopped the sequence"
                    }
                  }
                }

                # WHY : Assumptions: ResultPath is null so the Map DISCARDS its per-branch
                #       output instead of writing it into the execution state. Each branch
                #       returns a task envelope, and eleven of those replacing or nesting
                #       under the state object would push businessDate out of the path every
                #       following state reads it from -- including the verification gate that
                #       now follows this Map inside the same branch. Nothing downstream
                #       consumes which datasets were staged -- a failed branch has already
                #       failed the Map -- so the eleven envelopes are cost without a reader.
                ResultPath = null

                # WHY : Refactoring Rationale: this Map carries NO Retry, and a Retry that was
                #       added here is WITHDRAWN rather than corrected, because every one of the
                #       five error names it listed was unusable and two of them made the whole
                #       machine unprovisionable. It named States.ServiceQuotaExceeded and
                #       States.ThrottledException, and neither is a States Language built-in.
                #       The language reserves the "States." prefix for its own catalogue and
                #       forbids any other name from using it, so those two were read as illegal
                #       custom names and CreateStateMachine refused the entire definition:
                #       "Custom Error Names MUST NOT begin with the prefix 'States.', got
                #       'States.ServiceQuotaExceeded'." That is not a latent risk. Until this
                #       withdrawal the daily machine could not be created at all, so no timeout,
                #       catch or ordering guarantee anywhere in this file was reachable.
                # WHY : Assumptions: the remaining three names were legal but inert HERE, which
                #       is why correcting the list was not the fix. States.ItemReaderFailed is
                #       raised when a Map cannot read the source named in its ItemReader field
                #       and States.ResultWriterFailed when it cannot write the destination named
                #       in its ResultWriter field; this Map declares neither, taking its items
                #       from ItemsPath and discarding its results through ResultPath = null, so
                #       both are unraisable. States.Runtime is documented as not retriable and
                #       as always failing the execution, so listing it in a Retry changes
                #       nothing. The prose that justified the list also claimed coverage of
                #       States.ExceedToleratedFailureCount, which is not a built-in either --
                #       the real name is States.ExceedToleratedFailureThreshold -- was never
                #       actually in the list, and belongs to the Distributed Map failure
                #       thresholds that an INLINE processor with no ToleratedFailure field
                #       cannot reach.
                # WHY : Trade-offs: the absence is deliberate and the original reasoning for it
                #       was correct. A transient fault in the WORK is a fault in one branch,
                #       and it is retried where it happens -- RefreshSeedDataset below carries
                #       local.ecs_retry -- because re-entering the Map would replay ten
                #       successful refreshes to redo the one that failed. A fault in the
                #       ORCHESTRATION of an INLINE Map is, per the paragraph above, either
                #       unraisable or non-retriable, so there is no residual class left for a
                #       Map-level Retry to cover. The header records this as a deliberate
                #       asymmetry rather than an omission, and the branch's own Fail state
                #       raises the custom name DatasetRefreshFailed, which is legal precisely
                #       because it does NOT begin with "States.".
                # WHY : Refactoring Rationale: this Map carried a Catch of its own -- the shared
                #       local.common_catch, routing to the top-level NotifyFailure -- and it is
                #       WITHDRAWN rather than rewritten. A state inside a Parallel branch cannot
                #       transition to a state outside that branch, so that target is no longer
                #       reachable from here. An in-branch catcher plus an in-branch Fail state
                #       would only rename the error before it propagated, because a failed branch
                #       fails the Parallel either way; the Parallel's own Catch is the same
                #       local.common_catch and writes the failure to the same $.failure path, so
                #       the failure edge is unchanged from the outside.
                Next = "VerifyMigration"
              }

              # WHY : Refactoring Rationale: two further states were authored between the
              #       seed-refresh Map and the gate below -- a LoadSeedDatasets Map running
              #       `load-dataset` once per dataset, and a ReconcileTransactionSequence task
              #       running `reconcile-sequences` -- and BOTH are withdrawn. The work each
              #       one was written to add is already performed, per dataset, inside the
              #       refresh branch above: `refresh-dataset` stages the generation, LOADS the
              #       target table for the ten datasets that ship a committed extract, runs all
              #       three verification passes on each of those, stages the backup generation
              #       for the three families that have one, and ADVANCES
              #       ledger.transaction_id_seq for the one dataset whose rows occupy the
              #       allocator's range. A second Map would re-fetch and re-decode every
              #       extract to perform an upsert that by construction changes nothing, and a
              #       second reconciliation would advance a sequence already advanced.
              # WHY : Assumptions: the reasoning the withdrawn reconciliation state gave is
              #       kept and is not lost with it -- a load leaves the sequence pointing
              #       inside the range it just inserted, so the first identifier the online
              #       service allocates collides on the primary key, hours later, in a service
              #       that did nothing wrong. The baseline has no analogue because VSAM has no
              #       sequence. That hazard is closed at the refresh step, whose own comment
              #       records why it is closed there rather than as a state of its own: the
              #       allocator is advanced inside the refresh of the ONE dataset that feeds
              #       its table, so the ordering it depends on -- after those rows exist,
              #       before anything allocates -- cannot be broken by a graph edit.
              # WHY : Assumptions: what is NOT withdrawn is the gate below, and the three
              #       things it does that no per-dataset pass reaches: it runs on the
              #       SELECT-only verification login rather than the loader's own identity, it
              #       executes the two committed WHOLE-MIGRATION queries whose digests the
              #       passes pin, and it is the only edge into PreflightDailyTransactions, so
              #       posting cannot be reached over a corpus that does not match its source.

              # WHY : Refactoring Rationale: this is the gate the chain had no state for.
              #       The distribution delivers three verification passes -- a server-side
              #       row-count report over every declared relation, a per-record digest
              #       comparison between each extract and its loaded rows, and an exact
              #       money-total and negative-row report -- and before this state nothing
              #       in the infrastructure invoked any of them. Correct verification logic
              #       with no caller certifies nothing.
              # WHY : Assumptions: the single `verify-all` verb is invoked rather than the
              #       three per-dataset verbs in sequence, and the difference is not
              #       brevity. That verb runs the passes in a MANDATED order over the whole
              #       registry and stops at the first failure, and it accepts no dataset
              #       selector at all -- so no caller can narrow the gate to a subset and
              #       still receive a verdict. Composing it here from per-dataset states
              #       would put the coverage decision in HCL, where a state removed from the
              #       graph silently reduces what was certified.
              # WHY : Assumptions: the order the passes run in is load-bearing and belongs
              #       to that verb, not to this graph. A row-count mismatch means the wrong
              #       NUMBER of rows arrived, at which point the digest comparison and the
              #       totals are guaranteed to differ too -- so running them anyway reports
              #       three failures for one cause.
              # WHY : Assumptions: --sql-root is passed because the container cannot derive
              #       it. The two committed queries whose digests the passes pin are copied
              #       to a directory beneath the image's WORKDIR, while the package itself
              #       is installed into a virtual environment, so the package-relative
              #       default resolves to neither.
              VerifyMigration = {
                Type           = "Task"
                Resource       = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"
                TimeoutSeconds = local.state_timeouts.VerifyMigration
                Parameters = {
                  Cluster              = var.ecs_cluster_arn
                  TaskDefinition       = var.data_migration_task_definition_arn
                  LaunchType           = "FARGATE"
                  NetworkConfiguration = local.network_configuration
                  Overrides = {
                    ContainerOverrides = [{
                      Name        = var.data_migration_container_name
                      "Command.$" = "States.Array('verify-all', '--sql-root=${var.data_migration_sql_root}')"
                      Environment = local.data_migration_environment
                    }]
                  }
                }
                ResultSelector = local.ecs_result_selector
                ResultPath     = "$.verification"
                Retry          = local.ecs_retry
                # WHY : Refactoring Rationale: this state's Catch is WITHDRAWN for the same
                #       reason the Map's above it is -- local.common_catch names the top-level
                #       NotifyFailure, and no state inside a Parallel branch may transition out
                #       of the branch. An unhandled error here fails the branch, which fails the
                #       enclosing StageSeedDatasets Parallel, whose Catch IS local.common_catch;
                #       the retry tier that this state does own is unchanged.
                Next = "CheckVerificationExitCode"
              }

              # WHY : Assumptions: the predicate is exit code ZERO and nothing else, with no
              #       warn tier. Every other exit-code Choice in this chain admits a soft
              #       path because the baseline job it replaces carried one -- a reject count
              #       that sets RC=4, a COND=(4,LT) that lets a warning through. This gate
              #       replaces no baseline job at all: it is a binary statement about whether
              #       the migrated data matches its source, and there is no reading of
              #       "partly matches" that business processing may proceed on.
              # WHY : Refactoring Rationale: the clean verdict now transitions to
              #       MigrationVerified rather than straight to PreflightDailyTransactions,
              #       because this Choice lives inside the StageSeedDatasets branch and a branch
              #       state cannot name a state outside its branch. The edge itself is NOT
              #       redirected: MigrationVerified ends the branch, the branch ends the
              #       Parallel, and the Parallel's Next is PreflightDailyTransactions -- so a
              #       clean verdict remains the ONE and ONLY route into business processing,
              #       which is the property this gate exists to hold.
              CheckVerificationExitCode = {
                Type = "Choice"
                Choices = [{
                  Variable      = "$.verification.exitCode"
                  NumericEquals = 0
                  Next          = "MigrationVerified"
                }]
                Default = "VerificationFailed"
              }

              # WHY : Assumptions: a Succeed state is required here rather than optional. Every
              #       path through a Parallel branch has to reach a terminal state, and the only
              #       non-failing terminal available inside a branch is Succeed -- End: true and
              #       a Next out of the branch are both unavailable. It performs no work and
              #       returns the branch's input as the branch result, which the Parallel then
              #       discards; its whole function is to say "this branch finished cleanly" so
              #       the enclosing state can take its own Next.
              MigrationVerified = {
                Type = "Succeed"
              }

              # WHY : Refactoring Rationale: this Fail state is unchanged in error name and cause
              #       but has moved inside the branch with the Choice that selects it, and its
              #       effect is now BETTER than it was. As a top-level state it terminated the
              #       execution the instant it ran, which left the online read-only bracket
              #       engaged and the residual-task sweep unrun -- a failed verification was the
              #       one failure in this chain that stranded online writes. Failing the branch
              #       instead raises the error to the Parallel's Catch, which is the same
              #       local.common_catch every other work state uses, so a refused verdict now
              #       runs NotifyFailure, the cancellation sub-chain and the bracket release like
              #       any other failure.
              VerificationFailed = {
                Type  = "Fail"
                Error = "VerificationFailed"
                Cause = "The combined migration verification did not report a clean verdict; the chain stopped before posting against data that does not match its source"
              }
            }
          }]

          # WHY : Assumptions: ResultPath is null so the Parallel DISCARDS its result. A
          #       Parallel returns an ARRAY with one element per branch, and letting that
          #       array replace the state object would drop businessDate, seedDatasets and
          #       executionName, which every state after this one reads. Discarding it also
          #       discards $.verification, and nothing downstream reads that: the only
          #       consumer was CheckVerificationExitCode, which is inside the branch.
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
              #       unconditional one, and on this edge the owner is always this
              #       execution: CheckQuiesceLeaseAcquired routes a refused
              #       acquisition to OnlineWriteLeaseUnavailable, so nothing that
              #       failed to take the bracket can reach this state at all. Passing
              #       the owner is therefore a second, independent check rather than
              #       the only one -- the gate reads the lease record captured at the
              #       head of the chain, while the function re-tests ownership in the
              #       lease store at the moment of the release.
              # WHY : ⚠️ Refactoring Rationale: this comment asserted the opposite --
              #       that "this edge is NOT gated on ownership the way the failure
              #       edge is" because "QuiesceOnlineWrites continues to
              #       StageSeedDatasets whether or not it acquired the bracket, so a
              #       chain that reached here may be one that was refused". That
              #       described the graph as it was before CheckQuiesceLeaseAcquired
              #       existed. It is now false in a way that matters to a reader
              #       reasoning about the release: it argues that an ungated success
              #       edge is safe because the FUNCTION refuses, which invites removing
              #       the gate that in fact makes the owner on this edge knowable.
              # WHY : Alternatives Considered: a CheckQuiesceLeaseOwnership twin in
              #       front of this state, mirroring the failure edge. Still rejected,
              #       for a reason the earlier comment reached by the wrong route:
              #       CheckQuiesceLeaseAcquired already stopped every execution that
              #       does not own the bracket, so a twin here would re-ask a question
              #       the graph has answered. What this edge needed was not a gate on
              #       the way IN but one on the way OUT, which is CheckOnlineWritesResumed
              #       below.
              "expectedLeaseOwner.$" = "$.quiesce.leaseOwner"

              "businessDate.$"  = "$.businessDate"
              "executionName.$" = "$$.Execution.Name"

            }
          }
          ResultSelector = local.lambda_release_result_selector
          ResultPath     = "$.resume"
          Retry          = local.lambda_retry
          Catch          = local.common_catch
          Next           = "CheckOnlineWritesResumed"
        }

        # WHY : ⚠️ Refactoring Rationale: this state did not exist and ResumeOnlineWrites went
        #       straight to BatchSucceeded, so a chain reported success on the strength of the
        #       release call having RETURNED rather than of the flag having been restored. The
        #       release function's own ordering made that reachable rather than theoretical: it
        #       deleted its lease before writing the flag, so one failed Parameter Store write
        #       produced a retry that found no lease, declined to write, and returned success with
        #       every online service still read-only. Nothing in the graph disagreed, and the
        #       outage lasted until the NEXT night's chain completed. The function's ordering is
        #       fixed too, and this gate is what makes the fix checkable from the graph: an
        #       execution cannot reach Succeed unless the flag it is responsible for reads enabled.
        # WHY : Assumptions: the test is the flag's RESULTING STATE and not the absence of a
        #       refusal reason, because the two differ on exactly the case that must pass. A retry
        #       of an already-completed release is refused with "no lease is held" AND finds the
        #       flag already enabled -- writes are on, the bracket is gone, and there is nothing
        #       left to do. Gating on the refusal would fail that execution; gating on the state
        #       passes it, and still fails the one where the flag was never restored.
        # WHY : Assumptions: IsPresent precedes BooleanEquals, matching the two lease gates above.
        #       An absent path in a Choice comparison is a States.Runtime error rather than a
        #       false, so the guard is what keeps a malformed function result a routed failure.
        # WHY : Alternatives Considered: routing the failure straight to a Fail state. Rejected
        #       because NotifyFailure is the path that already does the three things this outcome
        #       needs: it publishes to the notification topic, and then reaches
        #       CheckQuiesceLeaseOwnership, which -- this execution still owning the lease --
        #       attempts the release a SECOND time through ResumeOnlineWritesOnFailure. Ending the
        #       execution FAILED additionally makes the bracket-finalizer rule fire, which is a
        #       third attempt from outside the execution. A bare Fail would have taken all three.
        CheckOnlineWritesResumed = {
          Type = "Choice"
          Choices = [{
            And = [
              {
                Variable  = "$.resume.onlineWritesEnabled"
                IsPresent = true
              },
              {
                Variable      = "$.resume.onlineWritesEnabled"
                BooleanEquals = true
              },
            ]
            Next = "BatchSucceeded"
          }]
          Default = "NotifyFailure"
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
            Next        = "ListResidualBatchTasks"
          }]
          Next = "ListResidualBatchTasks"
        }

        # WHY : Refactoring Rationale: this sub-chain is new, and it closes the window
        #       between a failing chain and the release of the write bracket. A `.sync`
        #       ECS state that expires or is aborted does NOT stop its container -- Step
        #       Functions stops waiting and the task carries on -- so every failure edge
        #       used to reach the release with an unknown number of this execution's own
        #       tasks still writing. Releasing then re-enables online writes underneath a
        #       posting or interest task mid-transaction, which is the same corruption the
        #       quiesce bracket exists to prevent, arriving through the failure path
        #       instead of the success path.
        # WHY : Assumptions: discovery is a ListTasks filtered by the startedBy marker
        #       rather than a read of the task ARNs the states recorded. That is not a
        #       preference -- it is the only method that works for the case this exists
        #       for. local.ecs_result_selector lifts taskArn out of a task's RESULT, and a
        #       state that expired produces no result at all, so the ARN of the abandoned
        #       task is exactly the one never written to the execution state. The marker
        #       is stamped at launch, before any outcome, so ListTasks finds it either
        #       way; local.task_started_by records why a service's tasks cannot be caught
        #       by the same filter.
        # WHY : Alternatives Considered: stopping tasks from the finalizer Lambda instead,
        #       which already runs on every terminal execution. Rejected as the primary
        #       mechanism because the finalizer is best-effort EventBridge delivery on a
        #       path that can be dropped, whereas an in-graph sub-chain is part of the
        #       execution that owns the bracket and can therefore REFUSE to release --
        #       something an out-of-band consumer cannot do, since by the time it runs the
        #       execution is already terminal. The finalizer keeps its own terminal-task
        #       confirmation for the case where the execution died without running this.
        ListResidualBatchTasks = {
          Type           = "Task"
          Resource       = "arn:${data.aws_partition.current.partition}:states:::aws-sdk:ecs:listTasks"
          TimeoutSeconds = var.cancellation_state_timeout_seconds
          Parameters = {
            Cluster = var.ecs_cluster_arn

            # WHY : Assumptions: DesiredStatus is RUNNING, which is the set of tasks ECS
            #       has not been asked to stop. A task already asked to stop leaves this
            #       set immediately, which is why the confirmation state below reads
            #       LastStatus instead -- being absent from this list proves a stop was
            #       requested, not that the container has exited.
            StartedBy     = local.task_started_by
            DesiredStatus = "RUNNING"
          }
          ResultSelector = {
            "taskArns.$" = "$.TaskArns"
          }
          ResultPath = "$.residual"
          Retry = [{
            ErrorEquals     = ["Ecs.EcsException", "Ecs.SdkClientException", "States.TaskFailed"]
            IntervalSeconds = var.retry_interval_seconds
            MaxAttempts     = var.retry_max_attempts
            BackoffRate     = var.retry_backoff_rate
          }]

          # WHY : Assumptions: a listing that cannot be completed goes to the unconfirmed
          #       terminal state rather than to the release. Not knowing whether a task
          #       is running is operationally the same as knowing one is: the only safe
          #       action is to leave the bracket engaged and let an operator look.
          Catch = [{
            ErrorEquals = ["States.ALL"]
            ResultPath  = "$.cancellationFailure"
            Next        = "ResidualBatchTasksUnconfirmed"
          }]
          Next = "CheckResidualBatchTasks"
        }

        # WHY : Assumptions: the emptiness test is IsPresent on the FIRST element rather
        #       than a length comparison, because ASL's Choice has no array-length
        #       operator and an absent index evaluates as not present rather than
        #       erroring. An empty list therefore takes the default edge straight to the
        #       ownership gate, which is the normal case: a chain that failed on a
        #       validation or notification step never started a task at all.
        CheckResidualBatchTasks = {
          Type = "Choice"
          Choices = [{
            Variable  = "$.residual.taskArns[0]"
            IsPresent = true
            Next      = "StopResidualBatchTasks"
          }]
          Default = "CheckQuiesceLeaseOwnership"
        }

        # WHY : Assumptions: the stop is re-issued on every pass of the loop rather than
        #       once, and that is deliberate. StopTask on a task that has already stopped
        #       is accepted and changes nothing, while a task that was still PROVISIONING
        #       when the first stop arrived can reach RUNNING afterwards -- so the repeat
        #       is what makes the loop converge rather than merely re-checking.
        # WHY : Trade-offs: each branch CATCHES its own stop failure and succeeds anyway,
        #       so one un-stoppable task cannot abandon the confirmation for the others.
        #       That is safe because nothing here decides anything: the confirmation state
        #       below is the only judge of whether the bracket may be released, and a task
        #       whose stop kept failing stays in its LastStatus and holds the release shut.
        StopResidualBatchTasks = {
          Type           = "Map"
          ItemsPath      = "$.residual.taskArns"
          MaxConcurrency = var.cancellation_max_concurrency
          TimeoutSeconds = var.cancellation_state_timeout_seconds
          ItemSelector = {
            "taskArn.$" = "$$.Map.Item.Value"
          }
          ItemProcessor = {
            ProcessorConfig = {
              Mode = "INLINE"
            }
            StartAt = "StopResidualBatchTask"
            States = {
              StopResidualBatchTask = {
                Type     = "Task"
                Resource = "arn:${data.aws_partition.current.partition}:states:::aws-sdk:ecs:stopTask"
                Parameters = {
                  Cluster  = var.ecs_cluster_arn
                  "Task.$" = "$.taskArn"

                  # WHY : Assumptions: the reason is recorded on the task itself because
                  #       it is the only place an operator reading `describe-tasks`
                  #       months later can learn that the stop was the orchestrator's
                  #       decision rather than a capacity eviction or an OOM kill.
                  Reason = "CardDemo daily batch failed; the orchestrator is stopping its own residual tasks before releasing the online-write bracket"
                }
                ResultPath = null
                Catch = [{
                  ErrorEquals = ["States.ALL"]
                  ResultPath  = "$.stopFailure"
                  Next        = "ResidualBatchTaskStopRequested"
                }]
                Next = "ResidualBatchTaskStopRequested"
              }

              ResidualBatchTaskStopRequested = {
                Type = "Succeed"
              }
            }
          }
          ResultPath = null
          Catch = [{
            ErrorEquals = ["States.ALL"]
            ResultPath  = "$.cancellationFailure"
            Next        = "CountCancellationAttempt"
          }]
          Next = "ConfirmResidualBatchTasksStopped"
        }

        # WHY : Refactoring Rationale: confirmation reads LastStatus through DescribeTasks
        #       rather than trusting the stop call's success, because StopTask only
        #       RECORDS the intent: ECS then sends SIGTERM, waits out the container stop
        #       timeout and only then SIGKILLs, so a task whose stop returned 200 can
        #       still be writing for that whole interval. LastStatus reaching STOPPED is
        #       the first moment the container is known not to be.
        # WHY : Assumptions: a per-branch Fail is used as the "not yet" signal, and the
        #       Map's Catch loops back on it. ASL has no universal quantifier over an
        #       array, so "every task is STOPPED" is expressed as "no branch reported
        #       otherwise" -- a shape that needs no filter expression and no counting, and
        #       that reports which task is still running in the failed branch's own
        #       history entry.
        ConfirmResidualBatchTasksStopped = {
          Type           = "Map"
          ItemsPath      = "$.residual.taskArns"
          MaxConcurrency = var.cancellation_max_concurrency
          TimeoutSeconds = var.cancellation_state_timeout_seconds
          ItemSelector = {
            "taskArn.$" = "$$.Map.Item.Value"
          }
          ItemProcessor = {
            ProcessorConfig = {
              Mode = "INLINE"
            }
            StartAt = "DescribeResidualBatchTask"
            States = {
              DescribeResidualBatchTask = {
                Type     = "Task"
                Resource = "arn:${data.aws_partition.current.partition}:states:::aws-sdk:ecs:describeTasks"
                Parameters = {
                  Cluster   = var.ecs_cluster_arn
                  "Tasks.$" = "States.Array($.taskArn)"
                }

                # WHY : Assumptions: an ARN whose task has aged out of the ECS API
                #       returns no Tasks entry, so LastStatus is selected defensively
                #       and the Choice below treats an absent status as unconfirmed. A
                #       stopped task is retained for a bounded period and this loop is
                #       bounded well inside it, so the absent case is a fault rather
                #       than an expected outcome.
                ResultSelector = {
                  "lastStatus.$" = "$.Tasks[0].LastStatus"
                }
                ResultPath = "$.taskState"
                Retry = [{
                  ErrorEquals     = ["Ecs.EcsException", "Ecs.SdkClientException", "States.TaskFailed"]
                  IntervalSeconds = var.retry_interval_seconds
                  MaxAttempts     = var.retry_max_attempts
                  BackoffRate     = var.retry_backoff_rate
                }]
                Catch = [{
                  ErrorEquals = ["States.ALL"]
                  ResultPath  = "$.describeFailure"
                  Next        = "ResidualBatchTaskStillRunning"
                }]
                Next = "CheckResidualBatchTaskStopped"
              }

              CheckResidualBatchTaskStopped = {
                Type = "Choice"
                Choices = [{
                  And = [
                    {
                      Variable  = "$.taskState.lastStatus"
                      IsPresent = true
                    },
                    {
                      Variable     = "$.taskState.lastStatus"
                      StringEquals = "STOPPED"
                    },
                  ]
                  Next = "ResidualBatchTaskStopped"
                }]
                Default = "ResidualBatchTaskStillRunning"
              }

              ResidualBatchTaskStopped = {
                Type = "Succeed"
              }

              ResidualBatchTaskStillRunning = {
                Type  = "Fail"
                Error = "ResidualBatchTaskStillRunning"
                Cause = "A task this execution started has not reached LastStatus STOPPED, so the online-write bracket must stay engaged"
              }
            }
          }
          ResultPath = null
          Catch = [{
            ErrorEquals = ["States.ALL"]
            ResultPath  = "$.cancellationFailure"
            Next        = "CountCancellationAttempt"
          }]
          Next = "CheckQuiesceLeaseOwnership"
        }

        # WHY : Assumptions: the counter is incremented in its own state rather than
        #       inside the Choice, because a Choice cannot transform its input. The Pass
        #       writes a whole object to $.cancellation because a payload template's
        #       result is an object, so a scalar counter is not expressible; keeping the
        #       count one level down also leaves room to record anything else the loop
        #       needs later without moving what already reads it.
        CountCancellationAttempt = {
          Type = "Pass"
          Parameters = {
            "attempts.$" = "States.MathAdd($.cancellation.attempts, 1)"
          }
          ResultPath = "$.cancellation"
          Next       = "CheckCancellationBudget"
        }

        # WHY : Trade-offs: the budget is bounded rather than open-ended, and exhausting
        #       it FAILS WITHOUT RELEASING. Waiting forever would hold the execution --
        #       and therefore the bracket -- until the machine-level ceiling aborted it,
        #       at which point nothing in the graph runs at all and the bracket is left
        #       engaged with no notification. Failing closed keeps the same safe outcome
        #       for the data while naming it, notifying it and pointing the operator at
        #       the task that would not stop.
        CheckCancellationBudget = {
          Type = "Choice"
          Choices = [{
            Variable        = "$.cancellation.attempts"
            NumericLessThan = var.cancellation_max_attempts
            Next            = "WaitForResidualBatchTasks"
          }]
          Default = "ResidualBatchTasksUnconfirmed"
        }

        # WHY : Assumptions: the interval defaults to the ECS container stop timeout, so
        #       one pass of the loop is the shortest wait that can plausibly change the
        #       answer -- polling faster would spend DescribeTasks calls to observe a
        #       task that cannot have exited yet.
        WaitForResidualBatchTasks = {
          Type    = "Wait"
          Seconds = var.cancellation_poll_seconds
          Next    = "StopResidualBatchTasks"
        }

        # WHY : Assumptions: this terminal state deliberately does NOT pass through the
        #       release, exactly like OnlineWriteLeaseUnavailable above and for the
        #       mirror-image reason. There the bracket belongs to somebody else; here it
        #       belongs to this execution but one of its own tasks may still be writing,
        #       so clearing the flag would re-admit online writes alongside it. The lease
        #       record's own expiry is what eventually frees the bracket if no operator
        #       acts, and the finalizer's reconciliation confirms terminality before
        #       using it.
        ResidualBatchTasksUnconfirmed = {
          Type  = "Fail"
          Error = "ResidualBatchTasksUnconfirmed"
          Cause = "A daily batch state failed and at least one task this execution started could not be confirmed stopped; the online-write bracket was left engaged on purpose. Stop the task named in the execution history, then clear the read-only flag once no batch task is running."
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
              # WHY : ⚠️ Refactoring Rationale: this Trade-off said the function's half
              #       of the pair "is bounded by what the flag can attest -- it stores
              #       the boolean every online service reads and no owner, so 'someone
              #       else re-took the bracket in between' is indistinguishable from
              #       'this execution still holds it'". That was written while the
              #       bracket WAS the flag. Ownership now lives in a durable lease item
              #       with a compare-and-set primitive, and the release is conditional
              #       on it, so the case the comment described as indistinguishable is
              #       exactly the case the condition refuses. Leaving the claim would
              #       understate the check a reader is relying on.
              # WHY : Trade-offs: what remains bounded is the window BETWEEN the
              #       release's ownership check and its flag write. Parameter Store
              #       offers no compare-and-set, so a lease admitted by EXPIRY can be
              #       re-acquired by another execution across that gap and the flag
              #       would be restored inside the new owner's window. The residual is
              #       accepted rather than closed here: it requires the previous
              #       execution to have run past the state machine's own timeout, and
              #       closing it would mean moving the flag every online service reads
              #       into the lease store.
              "expectedLeaseOwner.$" = "$.quiesce.leaseOwner"

              failedExecution   = true
              "executionName.$" = "$$.Execution.Name"
            }
          }
          ResultSelector = local.lambda_release_result_selector
          ResultPath     = "$.failureResume"
          Retry          = local.lambda_retry
          Catch = [{
            ErrorEquals = ["States.ALL"]
            ResultPath  = "$.resumeFailure"
            Next        = "OnlineWritesStranded"
          }]
          Next = "CheckOnlineWritesResumedOnFailure"
        }

        # WHY : ⚠️ Refactoring Rationale: this state did not exist and the failure release went
        #       straight to BatchFailed, so the one invocation the comment above calls "the
        #       invocation that matters most" was the one whose outcome nothing checked. A failed
        #       chain and a failed chain that also left every online service read-only ended
        #       identically, under one error name, and the difference between them is a day-long
        #       write outage.
        # WHY : Assumptions: a proved release still ends the execution FAILED, because the batch
        #       did fail and the release succeeding does not change that. What the gate changes is
        #       which failure is reported when the bracket was NOT restored, and that distinction
        #       is the point: BatchFailed asks an operator to read the execution history,
        #       OnlineWritesStranded tells them the online services are still refusing writes.
        # WHY : Assumptions: the Catch on the release above now routes to OnlineWritesStranded
        #       rather than to BatchFailed for the same reason. An exhausted retry on the release
        #       call is the case where the flag is LEAST likely to have been restored, so
        #       reporting it as an ordinary batch failure was the least useful of the two names.
        CheckOnlineWritesResumedOnFailure = {
          Type = "Choice"
          Choices = [{
            And = [
              {
                Variable  = "$.failureResume.onlineWritesEnabled"
                IsPresent = true
              },
              {
                Variable      = "$.failureResume.onlineWritesEnabled"
                BooleanEquals = true
              },
            ]
            Next = "BatchFailed"
          }]
          Default = "OnlineWritesStranded"
        }

        BatchFailed = {
          Type  = "Fail"
          Error = "CardDemoBatchFailed"
          Cause = "A daily batch state failed; inspect the execution history and notification"
        }

        # WHY : Assumptions: a distinct error name rather than a Cause on the shared failure
        #       state, because this is the outcome an alarm should match on. The two are not the
        #       same operational event: CardDemoBatchFailed means tonight's work did not complete,
        #       while this one means the online write path is still closed and will stay closed
        #       until the bracket is released -- by the bracket-finalizer rule this failure fires,
        #       by the lease expiring and a later execution taking it, or by an operator.
        OnlineWritesStranded = {
          Type  = "Fail"
          Error = "CardDemoOnlineWritesStranded"
          Cause = "The daily batch failed AND the online-write bracket was not proved released; online services may still be refusing writes. Check the read-only flag parameter, then the batch-finalizer rule's delivery and its dead-letter queue"
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
      # WHY : Assumptions: the two dates and the report type are checked as ONE rule
      #       set, so a request naming a type with no range, or a range with no type,
      #       is refused without starting a Fargate task. The date halves are
      #       local.iso_date_rules entries rather than restated patterns: this state
      #       is one of the four that carried the "????-??-??" pattern which matched
      #       only the literal string "????-??-??", so every well-formed report
      #       request reached InvalidReportRequest instead of the report.
      # WHY : Trade-offs: the range is not checked for ORDER here -- a startDate after
      #       its endDate satisfies these rules and is refused by the reporting job,
      #       which is where the range is turned into a query predicate. Expressing
      #       the ordering in the graph is possible with StringLessThanEqualsPath, and
      #       it is deliberately not done, because the job must validate the pair
      #       anyway for the operator who invokes the image directly and two
      #       implementations of one rule is how the two drift apart.
      ValidateReportRequest = {
        Type = "Choice"
        Choices = [{
          And = concat(
            local.iso_date_rules["startDate"],
            local.iso_date_rules["endDate"],
            [{
              Variable  = "$.reportType"
              IsPresent = true
            }],
          )
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
        Parameters = merge(local.run_task_attribution, {
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
        })
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
      #       the job, and the rule set is literally the same object the daily
      #       chain's entry gate uses -- local.iso_date_rules["businessDate"] -- so
      #       the two cannot diverge. The job does validate it again, through the
      #       shared job-parameter validator and then through BusinessDate's own
      #       width check, and that second check is not redundant: a graph check
      #       refuses a malformed request WITHOUT starting a Fargate task, so the
      #       operator gets an immediate InvalidDatasetRequest instead of paying a
      #       task start-up to be told the same thing by a stack trace.
      # WHY : Trade-offs: the rules check presence, type and shape and not the
      #       calendar, so 2022-13-45 passes here and is refused by BusinessDate.
      #       Expressing a real date check in Amazon States Language would need
      #       either a validating Lambda -- a function, a role and a log group for
      #       one string test -- or a second query language in this machine. The
      #       shape and range tests catch the mistakes an operator actually makes at
      #       the command line, a transposed, truncated or unset token, and the job
      #       catches the rest.
      ValidateDatasetRequest = {
        Type = "Choice"
        Choices = [{
          And  = local.iso_date_rules["businessDate"]
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
        Parameters = merge(local.run_task_attribution, {
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
        })
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
        Parameters = merge(local.run_task_attribution, {
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
        })
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

  # WHY : Refactoring Rationale: the unload state is GENERATED in two variants, and it
  #       used to be one state that passed neither the business date nor the extract
  #       form. Both omissions were real defects rather than tidying. The date appeared
  #       only inside the two S3 keys the graph composed, so nothing ever parsed it and
  #       `abcd-ef-gh` produced a successful unload under a false dt= partition; and the
  #       machine's published request accepts extractForm="sequential" while the task
  #       was invoked with no form at all, so a sequential request completed and wrote
  #       prefixed bytes.
  # WHY : Assumptions: the ONLY difference between the two variants is whether the
  #       --extract-form option is present, and the absent case omits it rather than
  #       passing the default. The exporter publishes UnloadService.DEFAULT_FORM and
  #       MaintenanceTaskRunner deliberately does not copy it -- its own comment says a
  #       copy would be a second place to change it -- so writing "prefixed" here would
  #       make this the third. Omitting the option is what leaves one authority for the
  #       default.
  # WHY : Alternatives Considered: one state whose command array is assembled
  #       conditionally, which is what a single state would need. Rejected because ASL
  #       has no array-concatenation intrinsic -- States.Array nests rather than
  #       flattens -- so a conditionally-present argument is not expressible inside one
  #       payload template. Generating the pair from one map is the closest available
  #       shape: the Task body exists once, and the variant is one string.
  # WHY : Trade-offs: the destination keys are built in their own local rather than
  #       written twice. They are the longest expression in the file and the two
  #       variants must compose byte-identical keys, so a second copy is the one edit a
  #       reviewer would not catch.
  # WHY : Assumptions: the two destinations are COMPUTED rather than accepted from the
  #       request, so an export cannot be told to overwrite an unrelated key and two
  #       exports of the same business date on different executions cannot collide. The
  #       execution name is in the prefix for the second reason and the business date
  #       for the first, which is why both appear.
  # WHY : Trade-offs: the operator therefore cannot choose where an export lands. That
  #       is accepted because the runbook reads the destinations back out of the
  #       execution's own input, so the location is discoverable after the fact without
  #       being specifiable before it.
  authorization_unload_destination_arguments = "States.Format('--root-extract=s3://{}/authorization/extract/dt={}/run={}/roots.dat', '${var.dataset_bucket_name}', $.businessDate, $$.Execution.Name), States.Format('--child-extract=s3://{}/authorization/extract/dt={}/run={}/children.dat', '${var.dataset_bucket_name}', $.businessDate, $$.Execution.Name)"

  authorization_unload_form_arguments = {
    # Assumptions: this variant is reached only when the request omitted extractForm,
    #   so the exporter applies its own published default.
    UnloadAuthorizations = ""

    # Assumptions: this variant is reached only after the Choice has matched
    #   extractForm against the published domain, so the value interpolated here
    #   cannot be an unknown form.
    UnloadAuthorizationsInRequestedForm = ", States.Format('--extract-form={}', $.extractForm)"
  }

  authorization_unload_task_states = {
    for state_name, form_arguments in local.authorization_unload_form_arguments :
    state_name => {
      Type     = "Task"
      Resource = "arn:${data.aws_partition.current.partition}:states:::ecs:runTask.sync"

      # WHY : Assumptions: both variants share the ONE ceiling declared for the unload
      #       direction, because they are the same export doing the same work in a
      #       different output form. authorization_state_timeout_seconds is validated to
      #       hold exactly the two direction names, so neither variant introduces a
      #       third key.
      TimeoutSeconds = var.authorization_state_timeout_seconds["UnloadAuthorizations"]
      Parameters = merge(local.run_task_attribution, {
        Cluster              = var.ecs_cluster_arn
        TaskDefinition       = var.authorization_task_definition_arn
        LaunchType           = "FARGATE"
        NetworkConfiguration = local.network_configuration
        Overrides = {
          ContainerOverrides = [{
            Name = var.authorization_container_name

            # WHY : Assumptions: --business-date is passed on BOTH variants and is not
            #       optional to the task. MaintenanceTaskRunner reads it with
            #       requiredDate for this job, so it parses a real calendar date before
            #       the application context starts and before either destination is
            #       opened -- which is the check the graph's own ten-character
            #       StringMatches cannot make, since that shape admits 2022-02-30.
            "Command.$" = "States.Array('--job=unload-authorizations', ${local.authorization_unload_destination_arguments}, States.Format('--business-date={}', $.businessDate)${form_arguments})"
          }]
        }
      })
      ResultSelector = local.ecs_result_selector
      ResultPath     = "$.unload"
      Retry          = local.ecs_retry
      Catch = [{
        ErrorEquals = ["States.ALL"]
        ResultPath  = "$.failure"
        Next        = "NotifyAuthorizationExtractFailure"
      }]

      # Assumptions: both variants converge on the SAME exit-code Choice, which reads
      #   $.unload.exitCode -- the ResultPath both write -- so the form cannot change
      #   how a non-zero exit is handled.
      Next = "CheckUnloadExitCode"
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
  # WHY : Assumptions: one pattern, referenced by both location checks, and it is built from the
  #       dataset bucket input rather than written out. The unload mode COMPUTES its destinations
  #       as s3://<bucket>/authorization/extract/dt=<date>/run=<execution>/, so deriving the
  #       load's admissible space from the same input is what keeps the two directions addressing
  #       one location space; a literal here could drift from the destinations above with nothing
  #       to notice.
  # WHY : Assumptions: `StringMatches` is the only pattern comparison ASL offers and its one
  #       wildcard is `*`, so the pattern pins the scheme, the bucket and the prefix and leaves
  #       the object path open. That is the whole of what needs pinning: everything a leak or a
  #       cross-account read requires -- another scheme, another bucket, a container-local path --
  #       is in the part that is fixed.
  # WHY : Trade-offs: the trailing wildcard admits any depth beneath the prefix, including keys
  #       this machine never wrote. Tightening it to the dt=/run= shape was considered and
  #       rejected: the mode exists to load extracts the reference programs produced, which carry
  #       no run identifier, so a stricter pattern would refuse the very input the mode is for.
  #       The bucket and prefix are what bound the exposure; the depth does not.
  authorization_extract_location_pattern = "s3://${var.dataset_bucket_name}/authorization/extract/*"

  authorization_extract_definition = {
    Comment = "CardDemo operator-invoked pending-authorization segment export and extract load"

    TimeoutSeconds = var.authorization_extract_timeout_seconds

    StartAt = "ValidateAuthorizationExtractRequest"
    States = merge(
      local.authorization_unload_task_states,
      {
        # WHY : Assumptions: the mode is checked in the GRAPH and each mode's own
        #       arguments are checked with it, so a request naming no mode, or a mode
        #       whose arguments are absent, is refused without starting a Fargate task.
        #       The service validates every argument again before its context starts;
        #       that second check is not redundant, it is the one that runs when an
        #       operator invokes the image directly rather than through this machine.
        # WHY : Refactoring Rationale: the unload mode now has TWO arms rather than one,
        #       split on whether the request named an extract form, and an unload naming
        #       an UNPUBLISHED form now falls through to the refusal. Previously a single
        #       arm matched mode and date shape only, so extractForm was neither checked
        #       nor forwarded: "sequential" was accepted by the graph and then silently
        #       ignored by the task. Matching the value against the published domain here
        #       is what makes the refusal happen before a Fargate task starts, and
        #       routing the two cases to two variants is what carries the operator's
        #       choice through to the argv.
        # WHY : Assumptions: the published domain is UnloadService.UnloadForm.WIRE_VALUES
        #       -- "prefixed" and "sequential" -- and it is spelled here as two
        #       StringEquals rather than a pattern, so a value the exporter does not
        #       accept cannot pass this gate. The task's own requiredForm check remains
        #       the authority for a direct invocation.
        ValidateAuthorizationExtractRequest = {
          Type = "Choice"
          Choices = [
            {
              # WHY : Assumptions: the mode test is prepended to the SHARED date rule
              #       set and the extractForm tests are appended to it, rather than
              #       either sitting beside a restated pattern. The "????-??-??"
              #       pattern this replaces matched only the literal ten-character
              #       string "????-??-??", because ASL treats `?` as an ordinary
              #       character, so an unload naming a real business date was refused
              #       as though it had named no date at all. Single-sourcing the rule
              #       set is what stops that defect from being fixed at five gates and
              #       left standing at a sixth.
              And = concat(
                [{
                  Variable     = "$.mode"
                  StringEquals = "unload"
                }],
                local.iso_date_rules["businessDate"],
                [{
                  Variable  = "$.extractForm"
                  IsPresent = true
                  },
                  {
                    Or = [
                      {
                        Variable     = "$.extractForm"
                        StringEquals = "prefixed"
                      },
                      {
                        Variable     = "$.extractForm"
                        StringEquals = "sequential"
                      },
                    ]
                }],
              )
              Next = "UnloadAuthorizationsInRequestedForm"
            },
            {
              # Assumptions: this arm requires extractForm to be ABSENT rather than
              #   merely unmatched by the arm above, so a present-but-unpublished form
              #   matches neither arm and reaches the refusal. An arm that omitted this
              #   test would silently downgrade a misspelled form to the default.
              And = concat(
                [{
                  Variable     = "$.mode"
                  StringEquals = "unload"
                }],
                local.iso_date_rules["businessDate"],
                [{
                  Variable  = "$.extractForm"
                  IsPresent = false
                }],
              )
              Next = "UnloadAuthorizations"
            },
            {
              # WHY : Assumptions: the load takes its two sources from the REQUEST
              #       rather than deriving them from a business date, because the
              #       extract being loaded was not necessarily produced by this
              #       machine -- the reference programs' own output is a legitimate
              #       input, and it carries no run identifier this graph could
              #       reconstruct a key from.
              # WHY : ⚠️ Refactoring Rationale: each location was checked for PRESENCE
              #       only and then forwarded verbatim into a container argument, so the
              #       machine's public API accepted any string at all -- another
              #       account's bucket, a container-local path, a file:// URI -- and
              #       recorded it in the execution log and in the failure notification.
              #       Presence is not a contract: it admits every value except an absent
              #       one. The pattern pins the scheme, the bucket and the prefix to the
              #       same three things the unload mode COMPUTES for its own
              #       destinations, so the two directions now address one location space
              #       and a request naming anything else is refused before a Fargate task
              #       starts.
              # WHY : Assumptions: this narrows the API deliberately, and the use case the
              #       comment above survives it. An extract produced by the reference
              #       programs is still loadable -- an operator stages it under the same
              #       authorization/extract/ prefix first, which is one object-store copy
              #       and is documented in the runbook. What is no longer expressible is a
              #       load from a location this deployment does not own, which is the case
              #       that made the location a leak rather than a parameter.
              # WHY : Alternatives Considered: validating the two locations only inside
              #       the container, which already re-checks every argument. Rejected
              #       because the value has to be refused BEFORE it is logged, and by the
              #       time the container reads it the state machine has already recorded
              #       the transition that carried it. A graph-level refusal is also the
              #       only one that costs no task launch.
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
                  Variable      = "$.rootExtract"
                  StringMatches = local.authorization_extract_location_pattern
                },
                {
                  Variable  = "$.childExtract"
                  IsPresent = true
                },
                {
                  Variable      = "$.childExtract"
                  StringMatches = local.authorization_extract_location_pattern
                },
              ]
              Next = "LoadAuthorizations"
            },
          ]
          Default = "InvalidAuthorizationExtractRequest"
        }

        # WHY : Assumptions: the message names extractForm's published values, because a
        #       refusal an operator cannot act on is worse than no message. The two arms
        #       above accept the form only when it is one of these, so a request refused
        #       for a misspelled form reads its own correction here.
        InvalidAuthorizationExtractRequest = {
          Type = "Pass"
          Result = {
            error   = "InvalidAuthorizationExtractRequest"
            message = "Supply mode=unload with businessDate as YYYY-MM-DD and optionally extractForm as prefixed or sequential, or mode=load with rootExtract and childExtract locations"
          }
          ResultPath = "$.failure"
          Next       = "NotifyAuthorizationExtractFailure"
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
          Parameters = merge(local.run_task_attribution, {
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
          })
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

        # WHY : ⚠️ Refactoring Rationale: the message was
        #       States.Format('... {} failed: {}', $$.Execution.Name, States.JsonToString($)),
        #       which serialises the ENTIRE state into a notification. For this machine that
        #       state includes the operator-supplied rootExtract and childExtract, so a failed
        #       load published two operator locations to every subscriber of the topic -- and an
        #       SNS subscription leaves the account boundary, to an address or an endpoint this
        #       module does not know. The three sibling machines still serialise their state
        #       because theirs holds only business dates, dataset names and job names; this one
        #       acquired an operand that claim does not cover, so this is the one envelope that
        #       is curated.
        # WHY : Assumptions: the curated fields are drawn from the CONTEXT OBJECT rather than
        #       from the state, and that is what makes the envelope safe for every path into
        #       this state -- the two task catches, the two exit-code defaults and the
        #       validation refusal reach it with different shapes, and only the context object
        #       is present in all of them. A Format over $.failure.Error would raise
        #       States.Runtime on the exit-code paths, which carry no $.failure at all: the
        #       notification would then fail on exactly the executions it exists to report.
        # WHY : Trade-offs: the notification therefore names WHICH execution failed and not
        #       WHY. The why is in the execution history, which stays inside the account under
        #       a customer-managed key, and the message says so and gives the execution ARN to
        #       open. Carrying the cause as well would mean either re-introducing the payload
        #       or adding a normalising Pass state on each of the paths that reach here.
        NotifyAuthorizationExtractFailure = {
          Type     = "Task"
          Resource = "arn:${data.aws_partition.current.partition}:states:::sns:publish"
          Parameters = {
            TopicArn    = var.notification_topic_arn
            Subject     = "CardDemo authorization extract failed"
            "Message.$" = "States.Format('CardDemo authorization extract execution {} failed. Open the execution history at {} for the failing state and its cause. This notification deliberately carries no execution payload, because a load request names operator-supplied extract locations.', $$.Execution.Name, $$.Execution.Id)"
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
      },
    )
  }

  # WHY : Refactoring Rationale: this map replaces a single union execution role that
  #       all four state machines shared. Under the union role the ad-hoc report
  #       machine -- which runs one reporting task and invokes nothing -- could start
  #       the batch, data-migration and authorization task definitions, pass all eight
  #       task and execution roles, and invoke the quiesce, analyze-tables and resume
  #       functions, none of which appear anywhere in its own definition. Those are
  #       precisely the privileges that turn an operator-invoked report into a way of
  #       reopening the online write bracket or running a posting container. Keying the
  #       role, its inline policy and its trust document by machine makes each grant
  #       the CONSEQUENCE of what that machine's definition actually references, so a
  #       machine cannot inherit a privilege from a sibling.
  # WHY : Assumptions: every entry is derived from the rendered definition, not from
  #       intent. daily runs the batch, data-migration and reporting task definitions
  #       (it never runs the authorization one), invokes all three operational
  #       functions, and is the only machine that cancels residual tasks itself. adhoc
  #       runs only reporting, dataset only batch, authz only authorization, and none
  #       of the three invokes a function. All four publish to the one notification
  #       topic, deliver vended execution logs and emit traces, so those three grants
  #       are unconditional below.
  # WHY : Trade-offs: four roles and four inline policies instead of one is four times
  #       the IAM objects for the same topology, and a reader now has to look up which
  #       role a machine holds rather than knowing there is only one. Accepted because
  #       the alternative is a role whose blast radius is the union of four unrelated
  #       capabilities, and because the map is the single place a new machine's
  #       privileges are declared -- adding one is an entry here rather than an edit
  #       spread across a trust document, a policy document, a role and an output.
  machines = {
    daily = {
      machine_name = local.daily_machine_name
      task_definition_arns = [
        var.batch_task_definition_arn,
        var.data_migration_task_definition_arn,
        var.reporting_task_definition_arn,
      ]
      function_arns = [
        var.quiesce_function_arn,
        var.analyze_tables_function_arn,
        var.resume_function_arn,
      ]
      cancels_residual_tasks = true
    }

    adhoc = {
      machine_name           = local.adhoc_machine_name
      task_definition_arns   = [var.reporting_task_definition_arn]
      function_arns          = []
      cancels_residual_tasks = false
    }

    dataset = {
      machine_name           = local.dataset_machine_name
      task_definition_arns   = [var.batch_task_definition_arn]
      function_arns          = []
      cancels_residual_tasks = false
    }

    authz = {
      machine_name           = local.authz_machine_name
      task_definition_arns   = [var.authorization_task_definition_arn]
      function_arns          = []
      cancels_residual_tasks = false
    }
  }

  # WHY : Assumptions: an IAM role name is limited to 64 characters. The longest name
  #       this can produce is var.name_prefix at its 32-character ceiling, plus the
  #       four-character `prod`, plus `-sfn-batch-`, plus the longest key `dataset`,
  #       which is 55 -- so the suffix cannot push a valid prefix over the limit. The
  #       keys are deliberately the short internal ones rather than the machine names,
  #       which would add up to 21 characters and could.
  execution_role_names = {
    for key, machine in local.machines :
    key => "${local.execution_role_name_stem}-${key}"
  }

  # WHY : Assumptions: the payload an input transformer sends is a TEMPLATE, and
  #       EventBridge substitutes a declared input path by finding its
  #       angle-bracketed name LITERALLY in that template. The brackets are part of
  #       the token, not punctuation around it, which is what the restoration below
  #       exists to protect.
  finalizer_input_payload = {
    action                = "resume"
    readOnlyFlagParameter = var.read_only_flag_parameter_name
    finalizer             = true
    releasedBy            = "batch-finalizer"
    executionName         = "<executionName>"
    expectedLeaseOwner    = "<executionName>"
    terminalStatus        = "<status>"

    # WHY : Assumptions: this rule asks for the task check where the two in-graph
    #       release states do not, and the difference is what it can vouch for. A
    #       release state runs after the graph has confirmed its own tasks stopped;
    #       this rule fires the INSTANT the execution ended, so a task a timed-out
    #       synchronous state abandoned may still be writing. Without the flag the
    #       fast recovery path would re-enable online writes underneath exactly the
    #       container the finding calls out.
    # WHY : Trade-offs: the consequence is that this rule often REFUSES on an aborted
    #       run, because the abandoned task is still draining when it fires. That is
    #       the wanted outcome rather than a gap: the scheduled reconciler picks the
    #       bracket up once the tasks are terminal, so the release is late instead of
    #       unsafe.
    confirmTasksStopped = true
  }

  # WHY : Trade-offs: jsonencode alone is not usable for an input template, and this
  #       is not cosmetic. Terraform's jsonencode HTML-escapes `<` to \u003c and `>`
  #       to \u003e -- confirmed against the pinned toolchain, whose encoded form of
  #       "<executionName>" contains no literal `<` at all. Left escaped, the
  #       template reaching EventBridge reads \u003cexecutionName\u003e, the literal
  #       token is absent, NO substitution happens, and the resume function receives
  #       that placeholder text as the lease owner it must match -- so the release is
  #       refused on every out-of-graph terminal outcome and the bracket stays
  #       engaged until the lease expires. The failure is silent: the rule fires, the
  #       function is invoked, and only the release is missing. Restoring the two
  #       delimiters costs a string rewrite and is safe in both directions, because an
  #       unescaped `<` is valid JSON and the rewritten document still parses.
  # WHY : Alternatives Considered: assembling the template by hand as a heredoc,
  #       which needs no restoration because nothing escapes it. Rejected because it
  #       would give up jsonencode's quoting of the parameter name and the boolean
  #       and string typing of every other member, so a parameter name containing a
  #       quote or a slash would produce a malformed template that only fails at
  #       delivery. `&` is escaped by the same mechanism and is deliberately left
  #       alone: no token here contains one.
  # WHY : Refactoring Rationale: the identical rewrite is applied by
  #       infra/modules/eventbridge-scheduler/main.tf to its own target payload, and
  #       that module's comment records the same measurement. This module's finalizer
  #       template was written with a bare jsonencode and therefore carried the bug
  #       that module had already found and fixed.
  finalizer_input_template = replace(
    replace(jsonencode(local.finalizer_input_payload), "\\u003c", "<"),
    "\\u003e",
    ">",
  )

  # WHY : Assumptions: iam:RunTask evaluates the revision-qualified task
  #       definition ARN. A caller may supply either a family ARN or a
  #       revision ARN, so an unqualified family is widened only to its own
  #       revisions rather than to another task-definition family.
  machine_task_definition_policy_arns = {
    for key, machine in local.machines :
    key => [
      for task_definition_arn in machine.task_definition_arns :
      can(regex(":[0-9]+$", task_definition_arn))
      ? task_definition_arn
      : "${task_definition_arn}:*"
    ]
  }
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
# Per-machine execution roles
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "assume_role" {
  for_each = local.machines

  statement {
    sid     = "StepFunctionsAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }

    # WHY : Refactoring Rationale: this condition used to be ArnLike over
    #       "<prefix>*", because one role served all four machines and no single
    #       exact ARN could be named. With a role per machine the exact ARN IS
    #       knowable, and it is composed here rather than referenced from
    #       aws_sfn_state_machine because the machine takes this role as an input --
    #       referencing the machine would close a dependency cycle. The same-prefix
    #       wildcard also matched any FUTURE machine sharing the stem, so the ad-hoc
    #       machine could have assumed the daily machine's role once both existed.
    # WHY : Assumptions: SourceArn and SourceAccount together are the confused-deputy
    #       guard. Without them the trust policy would let the Step Functions service
    #       principal assume this role on behalf of ANY state machine that service
    #       runs, including one in another account; SourceArn alone leaves the ARN
    #       matchable in a partition where the account field is not asserted.
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = ["${local.state_machine_arn_root}${each.value.machine_name}"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "this" {
  for_each = local.machines

  name               = local.execution_role_names[each.key]
  assume_role_policy = data.aws_iam_policy_document.assume_role[each.key].json

  # WHY : ⚠️ Refactoring Rationale: all FOUR roles this resource creates -- one per
  #       machine in local.machines -- carried no boundary, while both environment
  #       roots described their `permissions_boundary_arn` as applying to "every role
  #       this deployment creates". These are the widest roles in the deployment: each
  #       holds ecs:RunTask, iam:PassRole over the task roles, and lambda:InvokeFunction.
  #       iam:PassRole in particular is a privilege-escalation primitive -- a role that
  #       can pass any role can act as any role -- so a ceiling above the inline
  #       documents is worth more here than anywhere else in the module.
  # WHY : Assumptions: one boundary covers all four rather than one input per machine.
  #       A boundary is an account-level ceiling, not a per-machine grant; the
  #       narrowing BETWEEN machines is already done by the per-machine assume-role and
  #       inline documents keyed by each.key, and a second per-machine axis here would
  #       let one machine be given a wider ceiling than its siblings by accident.
  permissions_boundary = var.permissions_boundary_arn

  tags = merge(var.tags, {
    Name = local.execution_role_names[each.key]
  })

  lifecycle {
    precondition {
      # WHY : Assumptions: a boundary ARN naming another account is accepted by IAM and
      #       then bounds nothing, because the policy does not resolve here. Comparing
      #       the ARN's account field against the caller's makes that a plan failure.
      condition     = split(":", var.permissions_boundary_arn)[4] == data.aws_caller_identity.current.account_id
      error_message = "permissions_boundary_arn must belong to the same AWS account as the state-machine execution roles."
    }
  }
}

data "aws_iam_policy_document" "permissions" {
  for_each = local.machines

  statement {
    sid       = "RunApprovedTaskDefinitions"
    effect    = "Allow"
    actions   = ["ecs:RunTask"]
    resources = local.machine_task_definition_policy_arns[each.key]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [var.ecs_cluster_arn]
    }
  }

  # WHY : Refactoring Rationale: RunTask asks ECS to APPLY the task definition's tags
  #       to the task it creates, which is a tagging call on a resource that does not
  #       exist yet, and it is authorised separately from RunTask itself. Without this
  #       statement every RunTask integration that sets PropagateTags fails outright
  #       with an access-denied on ecs:TagResource -- so the attribution added on the
  #       nine run-task integrations would have made the chain refuse to start rather
  #       than merely arrive untagged.
  # WHY : Assumptions: ecs:CreateAction is the condition key that distinguishes a tag
  #       applied AS PART OF a create call from an arbitrary later re-tag. Pinning it
  #       to RunTask means this grant cannot be used to relabel an existing task,
  #       which is what would let a caller move a task between cost centres or defeat
  #       a tag-based access rule. The resource is the cluster's task ARN pattern
  #       rather than a wildcard, so it cannot tag a task in another cluster.
  statement {
    sid       = "TagTasksCreatedByRunTask"
    effect    = "Allow"
    actions   = ["ecs:TagResource"]
    resources = [local.cluster_task_arn_pattern]

    condition {
      test     = "StringEquals"
      variable = "ecs:CreateAction"
      values   = ["RunTask"]
    }
  }

  # WHY : Refactoring Rationale: DescribeTasks and StopTask used to share one
  #       statement whose only bound was an `ecs:cluster` condition. AWS's service
  #       authorization reference lists the task resource type for both, so the
  #       narrowest supported bound for StopTask is a task ARN and not a condition --
  #       and the task ARN embeds the cluster name, so scoping by resource is the
  #       same restriction expressed where the service enforces it. Splitting the
  #       statements lets each carry the bound its own action supports instead of the
  #       intersection of the two.
  # WHY : Assumptions: task ARNs do not exist until RunTask creates them, so neither
  #       action can name an individual task. `task/<cluster>/*` is therefore the
  #       narrowest static scope available, and it is exactly the set of tasks this
  #       module's own RunTask grant can create.
  statement {
    sid       = "StopTasksInApprovedCluster"
    effect    = "Allow"
    actions   = ["ecs:StopTask"]
    resources = [local.cluster_task_arn_pattern]
  }

  # WHY : Assumptions: DescribeTasks keeps the cluster condition rather than the task
  #       ARN scope because a describe is issued against a cluster and a task list,
  #       and the condition is the form AWS's own identity-based policy example uses
  #       for it. Both machines and this module's cancellation states call it: the
  #       synchronous run-task integration observes the task it started, and the
  #       daily chain's confirmation states read LastStatus after a stop.
  statement {
    sid       = "DescribeTasksInApprovedCluster"
    effect    = "Allow"
    actions   = ["ecs:DescribeTasks"]
    resources = ["*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [var.ecs_cluster_arn]
    }
  }

  # WHY : Assumptions: only the daily chain LISTS tasks. Its cancellation sub-chain
  #       discovers residual tasks by asking ECS which tasks in the cluster carry this
  #       module's startedBy marker, because a task whose state timed out produced no
  #       result and therefore left no ARN to read back. The other three machines never
  #       enumerate tasks, so the action is withheld from their roles entirely.
  # WHY : Trade-offs: ListTasks takes a wildcard resource with the cluster condition,
  #       following AWS's documented identity-based policy example, because its
  #       reference resource type is the container instance -- a resource a Fargate
  #       cluster does not have. The cluster condition is therefore the only bound
  #       available, and it is the one that matters: the call cannot enumerate another
  #       cluster's tasks.
  dynamic "statement" {
    for_each = each.value.cancels_residual_tasks ? [each.key] : []

    content {
      sid       = "ListTasksInApprovedCluster"
      effect    = "Allow"
      actions   = ["ecs:ListTasks"]
      resources = ["*"]

      condition {
        test     = "ArnEquals"
        variable = "ecs:cluster"
        values   = [var.ecs_cluster_arn]
      }
    }
  }

  # WHY : Assumptions: Step Functions observes a synchronous run-task through an
  #       EventBridge MANAGED RULE it creates and maintains itself, named
  #       StepFunctionsGetEventsForECSTaskRule, which is why a state machine that only
  #       starts tasks still needs three events permissions. Without them the service
  #       cannot register that listener, and the documented consequence is that the
  #       state machine cannot monitor the job -- a task state that runs its task and
  #       then waits far longer than the work took, which reads like a slow batch job
  #       and is not one. The rule name is fixed by AWS, so the ARN is composed from
  #       the data sources above rather than named by an input.
  # WHY : Trade-offs: this grant is not a substitute for the describe permission above
  #       and the two are not alternatives -- the service may also observe task status
  #       directly, and the daily chain's own confirmation states certainly do. Both
  #       are granted because both are used; withholding either produces a failure
  #       that surfaces only at run time.
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
    resources = var.pass_role_arns[each.key]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  # WHY : Assumptions: only the daily chain invokes a function. The quiesce and resume
  #       functions bracket its write window and the analyze-tables function runs
  #       between the reports and the resume; no other machine references any of the
  #       three. Withholding the action from the other three roles is what stops an
  #       operator-invoked report or extract from being able to REOPEN the online write
  #       bracket, which under the previous shared role it could.
  dynamic "statement" {
    for_each = length(each.value.function_arns) > 0 ? [each.key] : []

    content {
      sid       = "InvokeOperationalFunctions"
      effect    = "Allow"
      actions   = ["lambda:InvokeFunction"]
      resources = each.value.function_arns
    }
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
  for_each = local.machines

  name   = local.execution_role_names[each.key]
  role   = aws_iam_role.this[each.key].id
  policy = data.aws_iam_policy_document.permissions[each.key].json
}

# -----------------------------------------------------------------------------
# State machines
# -----------------------------------------------------------------------------

resource "aws_sfn_state_machine" "daily" {
  name     = local.daily_machine_name
  role_arn = aws_iam_role.this["daily"].arn

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
# WHY : Alternatives Considered: making a scheduled watchdog the ONLY recovery, in
#       place of this rule. Rejected on latency and on what a schedule can prove:
#       an event fires within seconds of the execution ending, where a cadence waits
#       out its interval. A watchdog does exist -- aws_cloudwatch_event_rule.bracket_reconciler
#       below -- and it is deliberately a backstop rather than the primary path.
#       Assumptions: the watchdog shape this comment originally rejected was a
#       different one: inspecting the FLAG's AGE and clearing a stale one. That is
#       still rejected, and for the reason recorded then -- any staleness threshold is
#       either shorter than a legitimate long night, in which case it re-enables
#       writes mid-window, or longer than an operator is willing to wait. The
#       reconciler carries no threshold at all: it releases on evidence, requiring the
#       owning execution to have stopped running and the chain's tasks to be terminal,
#       so its cadence decides only how quickly a stranded bracket is noticed.
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

    # WHY : Assumptions: the template is built in a local so the ESCAPING fix it needs
    #       is applied in one place and can be asserted on its own. Every placeholder
    #       it contains is declared above -- EventBridge refuses a template naming an
    #       input path that does not exist -- and nothing is declared that the template
    #       does not use.
    input_template = local.finalizer_input_template
  }

  # WHY : ⚠️ Refactoring Rationale: this target had NO dead-letter queue, and the
  #       reasoning recorded here for that was wrong on its central claim. It said
  #       "a lease whose owner terminated WITHOUT this rule delivering is still
  #       recoverable, because the function's release condition also admits an
  #       expired lease ... losing an event delays the bracket's release to its
  #       expiry instead of stranding it". Expiry does no such thing. It changes
  #       what a FUTURE caller is permitted to do -- nothing observes an expiry and
  #       nothing writes the flag on it -- so a lost event leaves the read-only flag
  #       set until some later invocation releases it. The next such invocation is
  #       the NEXT night's chain, whose own resume runs after a full batch window,
  #       so the measured consequence of one dropped event is online writes refused
  #       for about a day. That is the outage this module exists to bracket, reached
  #       by the one path that had no recovery.
  # WHY : Assumptions: a dead-letter queue plus an alarm is the resolution rather
  #       than an expiry reconciler. The reconciler was the alternative and is
  #       strictly larger -- a schedule, a function, its role and its own idempotency
  #       argument -- to recover an event this queue simply keeps. Keeping the event
  #       also preserves the terminating execution's NAME, which is what the release
  #       condition compares against, so a redrive is a faithful retry of the release
  #       that was lost rather than a reconstruction of it.
  # WHY : Trade-offs: the queue makes recovery possible and does not make it
  #       automatic. A redrive is an operator action, which is accepted because the
  #       alternative -- an automatic consumer -- would be a second releaser running
  #       on its own clock, exactly the unbounded-staleness design the scheduled
  #       watchdog was rejected for above. What the alarm buys is that the operator
  #       learns within a metric period instead of when a user reports a refused
  #       write. The stranded terminal state in the graph is the other half of the
  #       same signal, for the executions that do transition. The rule exists precisely
  #       for the failures that do NOT transition, so when its own delivery fails there is
  #       no other path at all.

  # WHY : Assumptions: the event age is bounded well below the nightly cadence so a
  #       discarded event cannot be delivered against the NEXT night's lease. With a
  #       dead-letter queue attached, exceeding this age is no longer a discard: the
  #       event lands on the queue with its original payload, so the bound now
  #       decides how long EventBridge keeps trying rather than how much evidence
  #       survives.
  retry_policy {
    maximum_retry_attempts       = var.retry_max_attempts
    maximum_event_age_in_seconds = 3600
  }

  # WHY : Assumptions: present unconditionally, never optional -- the same reasoning
  #       infra/modules/eventbridge-scheduler applies to the schedule that starts
  #       the chain. An invocation that exhausts the retries above has nowhere else
  #       to go, so capturing it is what makes a lost release recoverable by hand
  #       instead of invisible.
  dead_letter_config {
    arn = aws_sqs_queue.bracket_release_dlq.arn
  }
}

# -----------------------------------------------------------------------------
# Bracket-release dead-letter queue
# -----------------------------------------------------------------------------
# Purpose:
#   Retains a release invocation that EventBridge could not deliver, from EITHER
#   release rule, so a bracket left engaged by a failed release is recorded rather
#   than lost.
#
# WHY : Alternatives Considered: reusing the environment root's shared error queue,
#       which is what the scheduler module is given for its own dead-letter target.
#       Rejected on a permission boundary: an EventBridge RULE delivers to a
#       dead-letter queue under the queue's own RESOURCE policy, not under a role,
#       so this module would have to add a statement to a queue another module owns
#       -- either by taking a second policy resource on a foreign queue, which
#       conflicts with that module's own policy, or by asking the root to thread a
#       rule ARN back into it, which makes two modules depend on each other's
#       creation order. Owning one small queue keeps the policy beside the rule it
#       admits.
# WHY : Trade-offs: a dedicated queue costs one more resource and a second place to
#       look for failures. Accepted because what lands here is unambiguous -- every
#       message is one undeliverable bracket release -- so the alarm on its depth
#       needs no filtering, whereas a shared queue's depth cannot distinguish this
#       failure from any other producer's.
# WHY : Assumptions: ONE dead-letter queue serves both release rules -- the in-graph
#       finalizer and the reconciler -- rather than one each. Its policy authorises exactly
#       those two rule ARNs by SourceArn, so the grant is no wider than two dedicated queues
#       would give, and an operator has one place to look and one alarm to answer instead of
#       two that mean the same thing. The events are distinguishable inside the queue by
#       their own payloads, which carry the rule's input transformer output.
resource "aws_sqs_queue" "bracket_release_dlq" {
  name = "${local.name_stem}-batch-bracket-release-dlq"

  # WHY : Assumptions: fourteen days, the service maximum. A message here is read by
  #       a human during an investigation that begins with an alarm, and the retention
  #       has to outlast a long weekend plus a holiday; there is no consumer to drain
  #       it, so nothing is gained by expiring it sooner.
  message_retention_seconds = 1209600

  # WHY : Trade-offs: encryption is a customer-managed key when the caller supplies
  #       one and SQS-managed otherwise. The nullable input keeps the module
  #       applicable without a key while letting both environment roots pass the
  #       project key they already create, and the fallback is never "unencrypted" --
  #       sqs_managed_sse_enabled is the service default and is asserted here rather
  #       than left implicit.
  kms_master_key_id = var.dead_letter_kms_key_arn
  sqs_managed_sse_enabled = (
    var.dead_letter_kms_key_arn == null ? true : null
  )

  tags = merge(var.tags, {
    Name = "${local.name_stem}-batch-bracket-release-dlq"
  })
}

# WHY : Assumptions: EventBridge writes to a dead-letter queue as the events service
#       principal under the QUEUE's policy, so without this statement the rule's
#       dead_letter_config above is accepted at apply and silently drops every
#       undeliverable event. The SourceArn condition narrows the grant to this one
#       rule, so no other rule in the account can use this queue as its sink.
resource "aws_sqs_queue_policy" "bracket_release_dlq" {
  queue_url = aws_sqs_queue.bracket_release_dlq.url

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # WHY : Assumptions: ONE statement admits BOTH rules through a SourceArn list,
      #       rather than one statement per rule. SQS holds a single policy document
      #       per queue, so two aws_sqs_queue_policy resources would each overwrite
      #       the other on every apply -- a failure that shows up as a rule whose
      #       dead-letter deliveries start disappearing after an unrelated apply. The
      #       list keeps the grant to exactly the two rules this module creates.
      {
        Sid       = "AllowBracketReleaseRulesToDeadLetter"
        Effect    = "Allow"
        Principal = { Service = "events.amazonaws.com" }
        Action    = "sqs:SendMessage"
        Resource  = aws_sqs_queue.bracket_release_dlq.arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = [
              aws_cloudwatch_event_rule.daily_finalizer.arn,
              aws_cloudwatch_event_rule.bracket_reconciler.arn,
            ]
          }
        }
      },
      # WHY : Assumptions: an identity policy can restrict which principal may call
      #       SQS but cannot require that the signed request travelled over TLS, so
      #       the transport control is a resource-policy deny -- the same statement
      #       infra/modules/sqs attaches to every queue it owns, kept identical here
      #       so this queue is not the one exception in the deployment.
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "sqs:*"
        Resource  = aws_sqs_queue.bracket_release_dlq.arn
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      },
    ]
  })
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

# -----------------------------------------------------------------------------
# Quiesce-bracket reconciler
# -----------------------------------------------------------------------------
# Purpose:
#   Releases a quiesce bracket that no event ever released, once the owning
#   execution and every task it started are provably terminal.
#
# Parameters:
#   None of its own beyond the reconcile cadence. It reuses the resume function
#   and the read-only flag parameter already declared for the graph.
#
# Return values:
#   None. Its effect is the resume function's own, and only when that function's
#   two terminality checks both pass.
#
# Exceptions or errors:
#   A refused reconciliation is the normal outcome on every cycle where nothing
#   is stranded; it writes nothing and reports the reason. Delivery failures land
#   in the same dead-letter queue as the finalizer's and raise the same alarm.
#
# WHY : Refactoring Rationale: the finalizer rule's own comment argued that a
#       scheduled watchdog was the WRONG shape because it "needs a staleness
#       threshold, and any threshold is either shorter than a legitimate long night
#       or longer than an operator is willing to wait". That objection is answered
#       rather than ignored: this rule carries NO threshold. It asks the function to
#       reconcile on a cadence, and the function releases only on evidence -- the
#       lease's own owner recorded on it, that owner's execution no longer RUNNING,
#       and no task carrying this module's startedBy marker short of STOPPED. A
#       cadence with no threshold cannot re-enable writes mid-window, because a
#       legitimate long night is an execution that is still running.
# WHY : Assumptions: this is a BACKSTOP and not a replacement for the finalizer.
#       The finalizer fires within seconds of a terminal execution and is the fast
#       path; this rule covers the cases the finalizer cannot -- its own delivery
#       failing after every retry, and a bracket whose release was refused because
#       the chain's tasks were still draining at the moment the execution ended,
#       which is the ordinary outcome of an aborted run.
# WHY : Trade-offs: the payload is a STATIC input rather than an input transformer.
#       That is deliberate and it is what makes this rule immune to the escaping
#       defect the finalizer's template has to work around: with no placeholders
#       there is nothing for jsonencode to mangle, so the document EventBridge
#       delivers is the document written here. The cost is that the payload cannot
#       carry anything about the invocation -- which is exactly right, because the
#       whole point of reconciliation is that no caller knows what to name.
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_event_rule" "bracket_reconciler" {
  name                = "${local.name_stem}-batch-bracket-reconciler"
  description         = "Asks the resume function to release a CardDemo online-write bracket that no event released, once its owning execution and tasks are terminal."
  schedule_expression = "rate(${var.reconcile_interval_minutes} minutes)"

  tags = merge(var.tags, {
    Name = "${local.name_stem}-batch-bracket-reconciler"
  })
}

resource "aws_cloudwatch_event_target" "bracket_reconciler" {
  rule      = aws_cloudwatch_event_rule.bracket_reconciler.name
  target_id = "reconcile-online-writes"
  arn       = var.resume_function_arn

  # WHY : Assumptions: `reconcile` is what distinguishes this payload from the two
  #       in-graph releases and the finalizer's. It names no expectedLeaseOwner
  #       BECAUSE there is nobody left to name one -- the function derives the owner
  #       from the stored lease and then makes the ordinary conditional claim against
  #       it, so this path is not a second release implementation.
  # WHY : Assumptions: confirmTasksStopped is set as well, even though the reconcile
  #       path checks tasks regardless. It is sent so the payload states the whole
  #       contract it relies on in one place: a reader comparing the four release
  #       payloads can see that this one and the finalizer's both require task
  #       terminality while the two in-graph releases do not, without having to read
  #       the function to discover it.
  input = jsonencode({
    action                = "resume"
    readOnlyFlagParameter = var.read_only_flag_parameter_name
    reconcile             = true
    confirmTasksStopped   = true
    releasedBy            = "batch-bracket-reconciler"
  })

  retry_policy {
    maximum_retry_attempts = var.retry_max_attempts

    # WHY : Assumptions: the event age is bounded to one reconcile interval. A
    #       delivery that has been pending longer than that has already been
    #       superseded by the next cycle, so retrying it further would act on a
    #       lease state the next invocation is about to read for itself.
    maximum_event_age_in_seconds = var.reconcile_interval_minutes * 60
  }

  dead_letter_config {
    arn = aws_sqs_queue.bracket_release_dlq.arn
  }
}

resource "aws_lambda_permission" "bracket_reconciler" {
  statement_id  = "AllowExecutionFromBracketReconcilerRule"
  action        = "lambda:InvokeFunction"
  function_name = var.resume_function_arn
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.bracket_reconciler.arn
}

# -----------------------------------------------------------------------------
# Bracket-recovery alarms
# -----------------------------------------------------------------------------
# Purpose:
#   Make every way the out-of-execution release can fail visible on the topic the
#   environment root already routes batch notifications to.
#
# WHY : Refactoring Rationale: these did not exist, and their absence was the
#       reason a failed release was silent. The three signals are independent and
#       none substitutes for another: EventBridge reports a delivery it could not
#       make, Lambda reports an invocation that raised, and the queue reports a
#       delivery abandoned after every retry. A release can fail through any one of
#       the three without the other two seeing anything, so alarming on one would
#       leave the other two silent.
# WHY : Assumptions: alarm_actions and ok_actions both go to the notification topic
#       the graph already publishes failures to, so an operator learns about a
#       stranded bracket the same way they learn about a failed step, and learns
#       when it clears.
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "release_delivery_failed" {
  for_each = {
    finalizer  = aws_cloudwatch_event_rule.daily_finalizer.name
    reconciler = aws_cloudwatch_event_rule.bracket_reconciler.name
  }

  alarm_name          = "${local.name_stem}-batch-${each.key}-delivery-failed"
  alarm_description   = "Condition: EventBridge could not invoke the resume function from the ${each.key} rule. Question: is the online-write bracket still engaged after its execution ended? Action: read the flag, read the lease item, and release by hand if both show a stranded bracket."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = 300
  namespace           = "AWS/Events"
  metric_name         = "FailedInvocations"
  statistic           = "Sum"

  # WHY : Assumptions: missing data is NOT breaching. EventBridge publishes
  #       FailedInvocations only when there is a failure, so a healthy rule reports
  #       nothing at all and treating absence as breaching would alarm permanently.
  treat_missing_data = "notBreaching"
  actions_enabled    = true
  alarm_actions      = [var.notification_topic_arn]
  ok_actions         = [var.notification_topic_arn]

  dimensions = {
    RuleName = each.value
  }

  tags = merge(var.tags, {
    Name   = "${local.name_stem}-batch-${each.key}-delivery-failed"
    Signal = "bracket-release-delivery"
  })
}

resource "aws_cloudwatch_metric_alarm" "release_function_errors" {
  alarm_name          = "${local.name_stem}-batch-release-function-errors"
  alarm_description   = "Condition: the resume function raised. Question: can the online-write bracket be released at all? Action: read the function's log for the failing edge -- an SSM write, a lease claim, or a refused terminality check -- before releasing by hand."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = 300
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [var.notification_topic_arn]
  ok_actions          = [var.notification_topic_arn]

  # WHY : Assumptions: the dimension is the function NAME, which is derived from the
  #       ARN this module is given rather than taken as a second input -- an input
  #       could name a different function from the one the graph invokes, and the
  #       alarm would then watch something the bracket does not depend on.
  dimensions = {
    FunctionName = element(split(":", var.resume_function_arn), 6)
  }

  tags = merge(var.tags, {
    Name   = "${local.name_stem}-batch-release-function-errors"
    Signal = "bracket-release-function"
  })
}

resource "aws_cloudwatch_metric_alarm" "release_dead_letters" {
  alarm_name          = "${local.name_stem}-batch-release-dead-letters"
  alarm_description   = "Condition: an undeliverable bracket-release invocation is waiting in the dead-letter queue. Question: which execution's bracket was never released? Action: read the message body for the execution name, confirm its tasks have stopped, then release and purge."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = 300
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"

  # WHY : Trade-offs: this queue has no consumer, so a message stays visible until an
  #       operator purges it and the alarm stays in ALARM until they do. That is the
  #       wanted behaviour for a bracket that may still be engaged -- an alarm that
  #       cleared itself while the message remained would report the problem gone.
  treat_missing_data = "notBreaching"
  actions_enabled    = true
  alarm_actions      = [var.notification_topic_arn]
  ok_actions         = [var.notification_topic_arn]

  dimensions = {
    QueueName = aws_sqs_queue.bracket_release_dlq.name
  }

  tags = merge(var.tags, {
    Name   = "${local.name_stem}-batch-release-dead-letters"
    Signal = "bracket-release-dead-letter"
  })
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
  role_arn = aws_iam_role.this["adhoc"].arn

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
# WHY : Refactoring Rationale: this machine holds its OWN execution role, keyed
#       `dataset` in local.machines. It previously shared one union role with the other
#       three, and the comment here claimed that role was "exactly what this machine
#       needs and nothing more" -- which was false: the shared role could run the
#       data-migration, reporting and authorization task definitions, pass all eight
#       task and execution roles, and invoke the quiesce, analyze-tables and resume
#       functions, none of which this two-state round trip references. Its own role
#       grants ecs:RunTask on the batch task definition only, iam:PassRole over that
#       image's two roles only, and no lambda:InvokeFunction at all, alongside the
#       task tagging, stopping, describing, managed-rule, notification, log-delivery
#       and tracing grants every machine here needs.
resource "aws_sfn_state_machine" "dataset_roundtrip" {
  name     = local.dataset_machine_name
  role_arn = aws_iam_role.this["dataset"].arn
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
# WHY : Refactoring Rationale: this machine holds its OWN execution role, keyed `authz`
#       in local.machines, for the reason recorded on the dataset round trip above. The
#       narrowing matters most here: the authorization schema is the only place pending
#       authorizations and fraud markings live, and under the shared role an
#       operator-invoked extract could equally have started the posting container and
#       passed the batch task role. Its own role grants ecs:RunTask on the
#       authorization task definition only and iam:PassRole over that image's two roles
#       only, with no lambda:InvokeFunction and no ecs:ListTasks.
# WHY : Assumptions: nothing here grants the object-store access the export needs. That
#       privilege belongs to the authorization TASK role, which is what the container
#       authenticates as, and it is granted in the environment root beside the rest of
#       that role's policy. Granting it to the execution role instead would be granting
#       it to the wrong identity and would not work.
resource "aws_sfn_state_machine" "authorization_extract" {
  name     = local.authz_machine_name
  role_arn = aws_iam_role.this["authz"].arn
  type     = "STANDARD"

  # WHY : Alternatives Considered: a templatefile()-rendered ASL, rejected for the
  #       reason recorded on the daily machine's definition above.
  definition = jsonencode(local.authorization_extract_definition)

  logging_configuration {
    # WHY : ⚠️ Refactoring Rationale: this read var.log_include_execution_data, the input the
    #       other three machines share, whose own rationale rests on no payload carrying anything
    #       but business dates, dataset names, job names and execution identities. This machine's
    #       load mode breaks that claim: it takes two extract LOCATIONS from the operator's
    #       request. Splitting the input is what makes the shared claim true again for the
    #       machines it describes, instead of true-with-an-exception nobody reading it would find.
    #       The narrowing that actually removes the exposure is in the graph rather than here --
    #       ValidateAuthorizationExtractRequest refuses any location outside this deployment's own
    #       bucket and prefix -- and that is why this input's default matches the shared one; see
    #       its own Trade-offs for why turning it off costs a policy gate.
    include_execution_data = var.log_include_authorization_execution_data
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
