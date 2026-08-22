# Batch Operations Runbook

> **Purpose.** Operate, inspect, and redrive the CardDemo nightly, ad-hoc and
> dataset round-trip Step Functions workflows.
>
> **Source of truth.** `infra/modules/step-functions-batch/main.tf`,
> `docs/architecture/batch-orchestration.md`, and the immutable JCL under
> `app/jcl/**`.

Assumptions: the selected environment has been deployed, the caller uses a
short-lived AWS identity allowed to read/start/redrive the state machines, and
all commands run from the repository root.

## Resolve Workflow Identifiers

```bash
# WHAT: read the deployed workflow ARNs from Terraform outputs.
# WHY : Assumptions: names are environment-specific and must not be copied from
#       another account or region.
# WHY : Refactoring Rationale: these three read the COMPOSITE `batch_orchestration`
#       output and select a member from it, where an earlier revision of this block
#       used `output -raw daily_batch_state_machine_arn` and
#       `output -raw adhoc_report_state_machine_arn`. Neither of those root outputs
#       exists: `infra/envs/<env>/outputs.tf` publishes one output per module, and
#       for this module it is `batch_orchestration`, whose value is the whole module
#       output object. Both commands therefore failed with "Output
#       \"daily_batch_state_machine_arn\" not found", which is a runbook step that
#       cannot be followed rather than one that is merely imprecise.
ENVIRONMENT=dev
ORCHESTRATION="$(terraform -chdir="infra/envs/${ENVIRONMENT}" output -json batch_orchestration)"
DAILY_ARN="$(printf '%s' "$ORCHESTRATION" | jq -r '.daily_state_machine_arn')"
ADHOC_ARN="$(printf '%s' "$ORCHESTRATION" | jq -r '.adhoc_report_state_machine_arn')"
DATASET_ARN="$(printf '%s' "$ORCHESTRATION" | jq -r '.dataset_roundtrip_state_machine_arn')"
AUTHZ_EXTRACT_ARN="$(printf '%s' "$ORCHESTRATION" | jq -r '.authorization_extract_state_machine_arn')"
```

## Start the Nightly Chain Manually

Use an injected business date so reruns are deterministic.

```bash
# WHAT: start one named daily execution with an explicit ISO business date.
# WHY : Assumptions: the date is business input, not wall-clock state; preserving
#       it across retries keeps postings and generated object keys reproducible.
BUSINESS_DATE=2022-07-18
EXECUTION_NAME="manual-${BUSINESS_DATE}-$(date -u +%H%M%S)"
aws stepfunctions start-execution \
  --state-machine-arn "$DAILY_ARN" \
  --name "$EXECUTION_NAME" \
  --input "{\"businessDate\":\"${BUSINESS_DATE}\"}"
```

Return code 4 from posting is the documented warning tier and follows the
continue branch. A terminal state failure is not converted to success.

## Inspect an Execution

```bash
# WHAT: read the terminal status and event history without exposing task secrets.
# WHY : Assumptions: the failed state name and sanitized cause are sufficient to
#       choose retry, redrive, or data repair; container environment values are
#       not requested or printed.
EXECUTION_ARN="<execution ARN returned by start-execution>"
aws stepfunctions describe-execution --execution-arn "$EXECUTION_ARN"
aws stepfunctions get-execution-history \
  --execution-arn "$EXECUTION_ARN" \
  --reverse-order \
  --max-results 50
```

Use the observability dashboard and the workflow log group to correlate the
execution ARN with ECS task, Lambda, queue, and database alarms.

## Redrive a Failed Execution

Redrive only after the original failure cause is resolved. The durable
`batch.batch_run` ledger makes completed steps idempotent; it is not permission
to ignore a data-integrity failure.

```bash
# WHAT: resume a redrive-eligible failed execution from its failed state.
# WHY : Trade-offs: redrive preserves the original input and execution history,
#       avoiding a second run with a different date, but it also preserves a bad
#       input; validate the business date and failed-state evidence first.
aws stepfunctions redrive-execution --execution-arn "$EXECUTION_ARN"
```

If the failure occurred after online writes were quiesced, confirm the cleanup
path cleared the read-only flag before admitting interactive writes.

### An execution that stopped at `OnlineWriteLeaseUnavailable`

This is not a data failure and needs no redrive. It means the execution asked for
the online-write bracket and another execution already held it, so it stopped
before staging or posting anything — and, deliberately, without touching the other
execution's lease. Assumptions: the schedule starts one execution per night, so the
normal cause is that the **previous** night is still running; the action is to find
that execution rather than to rerun this one.

```bash
# WHAT: read the lease item to find which execution holds the bracket and until when.
# WHY : Assumptions: the lease is a DynamoDB item, not the Parameter Store flag. The
#       flag is a derived boolean every online service reads; ownership and expiry live
#       only here, which is why a stuck bracket is diagnosed against this table and not
#       against the parameter. LEASE_KEY is derived from the flag parameter path, so it
#       is spelled the same way the handler spells it.
LEASE_TABLE="carddemo-<env>-online-write-lease"
FLAG_PARAMETER="/carddemo/<env>/batch/online-writes-enabled"
aws dynamodb get-item --table-name "$LEASE_TABLE" --consistent-read \
  --key "{\"LeaseName\":{\"S\":\"online-write-gate:${FLAG_PARAMETER}\"}}"
```

Compare `expiresAt` with the current epoch second. If it is in the **future**, an
execution legitimately holds the window: let it finish, or abort it so the
finalizer rule releases the bracket. The lease itself needs no operator action once
it expires: the acquisition condition admits an expired lease, so the next chain takes
it over, and the scheduled reconciler below re-enables online writes without waiting
for that acquisition. Trade-offs: the item should not be deleted manually while
`expiresAt` is still in the future — doing so would hand the bracket to a second
execution while the first is still writing.

If `expiresAt` is in the **past**, read the **flag** as well, because the two
answer different questions and only one of them decides whether online writes are
working:

```bash
# WHAT: read the boolean every online service actually reads.
# WHY : ⚠️ Refactoring Rationale: this runbook said an expired lease needed "no action
#       at all", because the next acquisition takes it over. That is true of the LEASE
#       and says nothing about the FLAG. Nothing observes an expiry and nothing writes
#       the flag on it, so an expired lease beside a flag still reading false means
#       online writes are refused right now and will stay refused until the next
#       night's chain completes its own release. Reading only the lease is what turns a
#       lost release into a day-long outage nobody is looking for.
aws ssm get-parameter --name "$FLAG_PARAMETER" --query 'Parameter.Value' --output text
```

`true` means writes are enabled and the expired lease is harmless — the next
acquisition takes it over. `false` with an expired lease means a release was lost.
Check the finalizer rule's dead-letter queue, which the
`carddemo-<env>-batch-release-dead-letters` alarm also fires on:

```bash
# WHAT: see whether a bracket-release event was never delivered, then redrive it.
# WHY : Assumptions: redriving is preferred to writing the flag by hand, because the
#       held message carries the terminating execution's NAME and the release condition
#       is checked against it. A hand-written flag would open the window without the
#       ownership check the release performs, which is the check that stops a release
#       re-enabling writes inside another execution's live window.
DLQ_URL="$(aws sqs get-queue-url --queue-name carddemo-<env>-batch-bracket-release-dlq \
  --query QueueUrl --output text)"
aws sqs get-queue-attributes --queue-url "$DLQ_URL" \
  --attribute-names ApproximateNumberOfMessagesVisible
aws sqs start-message-move-task --source-arn "$(aws sqs get-queue-attributes \
  --queue-url "$DLQ_URL" --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text)"
```

A daily execution that failed **and** could not prove the bracket released ends in
`CardDemoOnlineWritesStranded` rather than `CardDemoBatchFailed`. The two error
names are two different jobs for an operator: the first says the online write path
is still closed and should be acted on now, the second says tonight's work did not
complete.

A `releaseClaimedAt` attribute on the item distinguishes the two ways a lease can be
live. Without it, the owner is still running the chain and `expiresAt` is the state
machine's ceiling away. With it, the owner has already reached a release state and is
between proving its ownership and writing the flag, so `expiresAt` is only the release
window — five minutes — away. Assumptions: a lease still carrying `releaseClaimedAt`
several minutes after `expiresAt` has passed means a release that never completed, and
the diagnosis moves to that Lambda's log group rather than to the state machine: look
for `event=online_write_lease_release_incomplete`, and read `enabled=` on it to see
whether online writes were re-enabled before the lease was orphaned.

### The three release paths, and what to do when none of them ran

The bracket is released by whichever of three paths gets there first, and they differ
in how much they can prove. Reading them in order is the fastest route to a diagnosis.

| Path | Fires when | Proves before releasing |
|---|---|---|
| The two in-graph resume states | The execution reaches them | It owns the lease. Its tasks were already confirmed stopped by the graph's own cancellation states |
| `<name>-<env>-batch-finalizer` rule | The daily execution ends `TIMED_OUT`, `ABORTED` or `FAILED` | It names the terminating execution as the owner, **and** confirms no task carrying the chain's `startedBy` marker is short of `STOPPED` |
| `<name>-<env>-batch-bracket-reconciler` rule | Every `reconcile_interval_minutes`, 15 by default | It derives the owner from the lease, **and** confirms the owning execution is no longer `RUNNING`, **and** confirms the tasks are terminal |

Assumptions: the finalizer REFUSING is a normal outcome after an aborted run, not a
fault. An ECS task started by a synchronous run-task state outlives the state that
started it, so at the instant an execution is aborted its container is usually still
draining — and a release granted then would re-enable online writes underneath a
posting container that is still writing. The reconciler picks the bracket up on its
next cycle once the tasks have stopped, so the release is late rather than unsafe.

```bash
# WHAT: read why the last reconcile cycle did or did not release the bracket.
# WHY : Assumptions: every cycle logs one line whether or not it releases, so the
#       reason is a fact rather than an inference. `reconciled=false` with
#       refused=<reason> is the normal idle answer -- "no lease is held" -- and the
#       same field names the exact fact that was missing when a bracket is stuck.
ENVIRONMENT=dev
aws logs filter-log-events \
  --log-group-name "/aws/lambda/carddemo-${ENVIRONMENT}-resume-online-writes" \
  --filter-pattern "event=online_write_gate_updated" \
  --max-items 20 \
  --query 'events[].message' --output text
```

The refusal reasons a stuck bracket produces, and what each one means:

| `refused=` | Meaning | Action |
|---|---|---|
| `no lease is held` | Nothing is stranded | None. This is the idle answer |
| `lease has not expired and records no owning execution to verify` | A hand-run acquisition, still inside its window | Wait for expiry, or release deliberately by invoking the resume function with the owner named |
| `owning execution is still RUNNING` | A legitimate long night | None. Let it finish |
| `N batch task(s) are still desired-RUNNING` | Tasks abandoned by a terminated execution are still writing | Confirm they are the chain's, then let them finish or stop them. The next cycle releases |
| `N batch task(s) have not reached STOPPED` | A stop was issued and the container has not exited | Wait out the container stop timeout |
| `batch task terminality cannot be confirmed without ...` | The resume function is missing `BATCH_TASK_CLUSTER_ARN` or `BATCH_TASK_STARTED_BY` | Re-apply the environment root; the release is withheld until it can be justified |
| `owning execution status could not be read (...)` | `states:DescribeExecution` was denied or failed | Check `<name>-<env>-online-write-reconcile` is attached to the online-write Lambda role |

```bash
# WHAT: read the undeliverable release invocations EventBridge could not hand over.
# WHY : Assumptions: this queue has NO consumer by design, so a message stays until an
#       operator purges it and the depth alarm stays in ALARM until they do -- an alarm
#       that cleared itself while the message remained would report the problem gone.
#       Each body is one bracket release that never happened, and it names the execution.
ENVIRONMENT=dev
QUEUE_URL="$(terraform -chdir="infra/envs/${ENVIRONMENT}" output -json batch_orchestration \
  | jq -r '.bracket_release_dead_letter_queue_url')"
aws sqs receive-message --queue-url "$QUEUE_URL" --max-number-of-messages 10 \
  --visibility-timeout 0 --query 'Messages[].Body' --output text
```

Once the bracket is released and the cause understood, purge the queue so the depth
alarm clears: `aws sqs purge-queue --queue-url "$QUEUE_URL"`.

Three alarms publish to the same notification topic the chain's failures use, and
between them they cover every way the out-of-execution release can fail: the two
rules' `FailedInvocations`, the resume function's `Errors`, and this queue's depth.
Their names are published as `bracket_release_alarm_names` in the
`batch_orchestration` output.

## Start an Ad-Hoc Report

The input carries **three** members, not two: `startDate`, `endDate` and `reportType`.
`ValidateReportRequest` checks all three as one rule set, so a range with no type is refused exactly
as a type with no range is.

`reportType` selects one of the three on-demand report kinds — `monthly`, `yearly` or `custom`. The
value is compared trimmed and lower-cased by `ReportArtifactLocator`, and it decides both the report
the job generates and the object key it writes, so an unrecognised value is not a cosmetic mistake.
`daily` is deliberately **not** in that set: it is the nightly chain's own report and is not
requestable here.

```bash
# WHAT: run the report workflow for an inclusive date range and one on-demand report kind.
# WHY : Assumptions: report selection is a record predicate, not a batch-step
#       gate, so all three values travel as workflow input to reporting-service.
# WHY : Refactoring Rationale: this command omitted `reportType`, and the graph's FIRST state refuses
#       the request without it -- taking the `Default` edge to `InvalidReportRequest`, which sets
#       `{"error":"InvalidReportRequest","message":"Supply startDate, endDate and reportType"}` under
#       `$.failure`, notifies, and ends at `AdHocReportFailed`. Every well-formed-looking request
#       therefore failed with `CardDemoAdHocReportFailed` before any task started, so there is no
#       task and no task log to read -- which is why the cause has to be read from the execution
#       history rather than from a container.
# WHY : Assumptions: the type is validated in the shell before the call, against the same three
#       tokens the job accepts. The graph only checks that `reportType` is PRESENT, so an
#       unrecognised value passes validation, starts a Fargate task, and is refused inside it by
#       `ReportArtifactLocator` -- which costs a task start-up to learn what this line answers for
#       nothing.
# WHY : Trade-offs: the range is not checked for ORDER here or in the graph. A startDate after its
#       endDate satisfies both and is refused by the job, deliberately: the job must validate the
#       pair anyway for an operator who invokes the image directly, and two implementations of one
#       rule is how the two drift apart.
# WHY : Trade-offs: the guard WRAPS the call rather than preceding it with `|| exit 1`. This block is
#       pasted into an interactive shell, where `exit` closes the terminal along with the `ADHOC_ARN`
#       resolved at the top of this document; wrapping refuses the start without costing the session.
START_DATE=2022-07-01
END_DATE=2022-07-31
REPORT_TYPE=monthly
case "$REPORT_TYPE" in
  monthly|yearly|custom)
    aws stepfunctions start-execution \
      --state-machine-arn "$ADHOC_ARN" \
      --name "report-${REPORT_TYPE}-${START_DATE}-${END_DATE}-$(date -u +%H%M%S)" \
      --input "{\"startDate\":\"${START_DATE}\",\"endDate\":\"${END_DATE}\",\"reportType\":\"${REPORT_TYPE}\"}"
    ;;
  *)
    printf 'FAIL reportType must be monthly, yearly or custom; got "%s" -- nothing started\n' \
      "$REPORT_TYPE" >&2
    ;;
esac
```

The graph turns those three into the task's argument vector — `--job=generate-report`,
`--start-date=`, `--end-date=` and `--report-type=` — so what the shell validated above is exactly
what the job receives.

Both a refused request and a failed report task end at `AdHocReportFailed`, so `describe-execution`
reports the same `FAILED` status and the same `CardDemoAdHocReportFailed` error for two problems with
different remedies. The history is what separates them.

```bash
# WHAT: name the state that ended the execution, so a refused input is distinguished from a report
#       task that ran and failed.
# WHY : Assumptions: `InvalidReportRequest` appearing in the history means the INPUT was refused --
#       fix the input and start a new execution. Its absence, with `GenerateAdHocReport` present,
#       means the task ran: read that task's log group instead. Both present is impossible, because
#       the two are on opposite edges of the first Choice.
# WHY : Assumptions: `--reverse-order` is used so the terminal states arrive first and the command can
#       be read without paging a long history.
aws stepfunctions get-execution-history \
  --execution-arn "<execution-arn>" --reverse-order --max-items 25 \
  --query 'events[?stateEnteredEventDetails!=null].stateEnteredEventDetails.name' --output text
```

## Run the Dataset Export/Import Round Trip

The export/import pair is operator-invoked, not scheduled — exactly as
`app/jcl/CBEXPORT.jcl` and `app/jcl/CBIMPORT.jcl` are, neither of which appears
in `app/scheduler/CardDemo.ca7` or `app/scheduler/CardDemo.controlm`. One
execution runs `--job=export` and then, only if that task exited zero,
`--job=import` over the object the export wrote.

```bash
# WHAT: run one export/import round trip for an injected business date.
# WHY : Assumptions: the execution NAME is derived from the business date and
#       carries NO timestamp, which is the opposite of the nightly and report
#       commands above and is deliberate. The name becomes CARDDEMO_BATCH_RUN_ID
#       inside both tasks, and BatchStepLedger keys on (runId, stepName) over
#       batch.batch_run -- so a deterministic name is what makes a re-invocation
#       idempotent: the ledger finds the step already recorded and replays its
#       outcome instead of running the body again. Step Functions also refuses a
#       duplicate execution name on a STANDARD machine, so a repeat is normally
#       refused before it starts.
# WHY : Trade-offs: because the name is deterministic, a genuine RERUN of the same
#       business date -- after correcting a cause -- has to be asked for
#       explicitly, by appending a suffix such as -r2. That is the intended
#       friction: without it, a re-run of the import would append a second copy of
#       every record to all six artefacts, and the artefacts carry no marker that
#       would let a consumer notice.
BUSINESS_DATE=2022-07-18
aws stepfunctions start-execution \
  --state-machine-arn "$DATASET_ARN" \
  --name "dataset-roundtrip-${BUSINESS_DATE}" \
  --input "{\"businessDate\":\"${BUSINESS_DATE}\"}"
```

A malformed or absent `businessDate` is refused by the graph's
`ValidateDatasetRequest` state before any task starts, so the operator sees
`InvalidDatasetRequest` immediately rather than paying a task start-up to be
told the same thing. The shape check is `YYYY-MM-DD`; the calendar check is the
job's, so `2022-13-45` passes the graph and is refused by the task.

The artefacts one execution produces, all under the dataset bucket:

| Object | Written by | Contents |
|---|---|---|
| `export/<yyyymmdd00>/export.dat` | `--job=export` | All five record types — customer `C`, account `A`, cross-reference `X`, transaction `T`, card `D` — at 500 bytes each |
| `import/<yyyymmdd00>/customer.dat` | `--job=import` | Customer records separated out of the export |
| `import/<yyyymmdd00>/account.dat` | `--job=import` | Account records |
| `import/<yyyymmdd00>/card_xref.dat` | `--job=import` | Cross-reference records |
| `import/<yyyymmdd00>/transaction.dat` | `--job=import` | Transaction records |
| `import/<yyyymmdd00>/card.dat` | `--job=import` | Card records |
| `import/<yyyymmdd00>/error.dat` | `--job=import` | One 132-byte diagnostic record per unrecognised or truncated image |

Every one of the six import artefacts is written even when it is empty, because
the reference allocates its outputs `DISP=(NEW,CATLG,DELETE)` and a consumer
distinguishing "no records of this type" from "the import did not run" needs the
empty artefact to exist.

**Three fields in the export are deliberately redacted** and this is not a
defect to be reported: the national identifier at `app/cpy/CVEXPORT.cpy:36` is
written as zero, the government-issued identifier at `:37` as blanks, and the
card verification value at `:96` as an encoded zero. All three are stored
enciphered under keys the batch task role holds no decrypt right for, and
acquiring that right in order to write them in clear into an object-store extract
is refused rather than unimplemented. The divergence is registered in
`docs/architecture/cobol-to-service-traceability.md`.

## Export or Load the Pending-Authorization Segments

The segment export and the extract load are operator-invoked, not scheduled, for
the same reason the export/import pair above is:
`app/app-authorization-ims-db2-mq/jcl/DBPAUTP0.jcl` runs the
reference unload on request and appears in neither
`app/scheduler/CardDemo.ca7` nor `app/scheduler/CardDemo.controlm`. One state
machine serves both directions and a `mode` field in the input selects which.

**The two directions are alternatives and are never chained.** That is the one
way this machine differs from the dataset round trip, and it is deliberate:
verifying an export by loading it back would write into the live `authorization`
schema, and because `--job=purge-authorizations` deletes expired rows a load run
after a purge would **resurrect exactly the rows the purge removed**. A
verification that can undo a retention decision is worse than none, so the load
is a separate, explicitly-requested mode.

### Export the segments

```bash
# WHAT: export every pending-authorization summary and its authorizations to two
#       flat extracts in the dataset bucket.
# WHY : Assumptions: the execution NAME carries a timestamp, which is the opposite
#       of the dataset round trip above and is deliberate. That machine derives a
#       deterministic name because its jobs are ledger-idempotent and a repeat must
#       replay rather than re-run. This machine's export has no ledger and writes to
#       keys that CONTAIN the execution name, so two exports of the same business
#       date are two different sets of objects rather than one overwritten set --
#       and a unique name is what keeps an earlier export readable after a later one
#       has run.
# WHY : Assumptions: no destination is passed. The graph composes both keys from the
#       bucket, the business date and the execution name, so an export cannot be
#       aimed at an unrelated key and two concurrent exports cannot collide. Where
#       the objects landed is read back out of the execution's own input below.
ENVIRONMENT=dev
BUSINESS_DATE=2022-07-18
RUN_NAME="authz-unload-${BUSINESS_DATE}-$(date -u +%Y%m%dT%H%M%SZ)"
aws stepfunctions start-execution \
  --state-machine-arn "$AUTHZ_EXTRACT_ARN" \
  --name "$RUN_NAME" \
  --input "{\"mode\":\"unload\",\"businessDate\":\"${BUSINESS_DATE}\"}"
```

The two objects one export writes, under the dataset bucket:

| Object | Contents |
|---|---|
| `authorization/extract/dt=<BUSINESS_DATE>/run=<RUN_NAME>/roots.dat` | One 100-byte summary image per `pending_auth_summary` row |
| `authorization/extract/dt=<BUSINESS_DATE>/run=<RUN_NAME>/children.dat` | One authorization record per `pending_auth_detail` row, 206 bytes in the default `prefixed` form and 200 in `sequential` |

```bash
# WHAT: read back where the export put its two objects, and what it wrote.
# WHY : Assumptions: the destinations are recovered from the execution rather than
#       reconstructed by hand, because the run name is part of the key and a
#       transcription error would name an object that does not exist. The counts
#       come from the task's own log line, which reports rootsWritten,
#       childrenWritten and rootsSkipped and names no account or customer.
DATASET_BUCKET="$(terraform -chdir="infra/envs/${ENVIRONMENT}" output -json datasets | jq -r '.bucket_name')"
aws s3 ls --recursive \
  "s3://${DATASET_BUCKET}/authorization/extract/dt=${BUSINESS_DATE}/run=${RUN_NAME}/"
```

**Neither object appears unless the export completed.** Both are staged and
published only after the walk returns, so a run that failed part-way leaves no
object at either key rather than a complete `roots.dat` beside a truncated
`children.dat` — a pair a consumer could not tell from a correct one, because a
child record is attributed to its parent by a key only the root file explains.

`rootsSkipped` above is not necessarily an error: a summary row carrying no
account identifier cannot be exported, and the export reports the count rather
than failing. The diagnostic names no subject of a skipped row by design; the
count is the whole of what the log can say about them.

### Choose the record form

The default is the `prefixed` form — the transcription of `cbl/PAUDBUNL.CBL`,
whose child record carries its packed parent key ahead of the segment. It is the
form the load reads back, which is why it is the default. The `sequential` form
is `cbl/DBUNLDGS.CBL`: a bare 200-byte segment with no prefix, attributable to an
account only by the interleaved order of the two files.

```bash
# WHAT: export in the sequential form instead of the default prefixed one.
# WHY : Trade-offs: an extract in this form cannot be loaded back by
#       --job=load-authorizations, because a child record in it carries nothing
#       that attributes it to a parent. Ask for it only when the consumer is one
#       that reads the two files in step, which is what the reference program's own
#       consumer does.
aws stepfunctions start-execution \
  --state-machine-arn "$AUTHZ_EXTRACT_ARN" \
  --name "authz-unload-seq-${BUSINESS_DATE}-$(date -u +%Y%m%dT%H%M%SZ)" \
  --input "{\"mode\":\"unload\",\"businessDate\":\"${BUSINESS_DATE}\",\"extractForm\":\"sequential\"}"
```

The graph passes `extractForm` through to the task only for the export; an
unpublished value is refused by the service before its context starts, and the
refusal names both published forms.

### Load an extract back

```bash
# WHAT: load two prefixed-form extracts into the authorization schema.
# WHY : Assumptions: BOTH sources are named by the operator rather than derived,
#       because the extract being loaded was not necessarily produced by this
#       machine -- the reference programs' own output is a legitimate input and
#       carries no run identifier a graph could reconstruct.
# WHY : ⚠️ Refactoring Rationale: this note said "either source may be an s3://
#       location or a filesystem path inside the container". Both are now refused
#       unless they match s3://<dataset bucket>/authorization/extract/*, the same
#       space the unload mode computes its own destinations in. The locations were
#       checked for presence only and then forwarded verbatim into a container
#       argument, written into the execution log at every transition and copied into
#       the failure notification, so the machine's public API accepted -- and
#       republished -- any string at all, including another account's bucket and a
#       container-local path. To load an extract the reference programs produced,
#       copy it under that prefix first; that is one object-store copy and it keeps
#       both directions addressing one location space.
# WHY : Assumptions: the load is IDEMPOTENT on the rows it inserts -- it reports
#       alreadyPresent for a row it finds -- but it is NOT a no-op against a schema
#       a purge has run on, because a row the purge deleted is absent and will be
#       inserted again. Confirm the extract's date against the retention window
#       before running this.
aws stepfunctions start-execution \
  --state-machine-arn "$AUTHZ_EXTRACT_ARN" \
  --name "authz-load-$(date -u +%Y%m%dT%H%M%SZ)" \
  --input "$(jq -nc \
      --arg roots "s3://${DATASET_BUCKET}/authorization/extract/dt=${BUSINESS_DATE}/run=${RUN_NAME}/roots.dat" \
      --arg children "s3://${DATASET_BUCKET}/authorization/extract/dt=${BUSINESS_DATE}/run=${RUN_NAME}/children.dat" \
      '{mode:"load", rootExtract:$roots, childExtract:$children}')"
```

An input naming no `mode`, a `mode` whose own arguments are absent, or a load whose
`rootExtract` or `childExtract` falls outside the deployment's own
`authorization/extract/` prefix is refused by
`ValidateAuthorizationExtractRequest` before any task starts, and the operator sees
`InvalidAuthorizationExtractRequest` with the accepted shapes named.
Inspect and redrive an execution of this machine exactly as for the others above.

## Poison-Message Handling

Authorization FIFO DLQ entries are quarantined. Do not bulk-redrive them:
review one message, preserve its group identifier, correct the cause, and
replay one message at a time. This maintains the per-group order contract and
prevents a later authorization from overtaking the failed one.

> Refactoring Rationale: that identifier was described here as **opaque**, and it is not. Sections
> 0.4.1.8 and 0.7.6 of the technical specification freeze `MessageGroupId` as `card_num`, so the
> value to preserve is the card number itself, and the exposure that follows is registered as
> divergence `D-AUTHORIZATION-FIFO-IDENTITY-METADATA`. The practical difference for an operator is
> the whole reason to correct it: a replay must reuse the value the message already carries rather
> than recompute anything, and the value is a **primary account number**, so a transcript of this
> procedure is a transcript containing cardholder data and must be handled as one.

## Rotate the Messaging HMAC Key — WITHDRAWN

⚠️ Refactoring Rationale: this section documented an attended rotation of
`<name-prefix>/<env>/messaging/hmac-key`, and **there is no such secret any more**. The
procedure is removed rather than left standing, because a runbook procedure that names a
secret nobody provisions sends an operator to a console page that shows nothing, at the one
moment they are least able to tell an error from a gap. The record stays because the secret
existed in any environment applied before this change, and because the reason it is gone is
the reason this section had become so hard to write.

**What was withdrawn.** The Secrets Manager entry, the `ephemeral` generator behind it in both
environment roots, the `CARDDEMO_MESSAGING_HMAC_KEY` container secret, the task-role read
grant, the `infra/modules/ecs-service` condition that required the `authorization` task to
receive it, the `carddemo.messaging.hmac-key` property, and the single Spring bean the property
keyed. All of it. The bean had **no injection point**: once specification sections 0.4.1.8 and
0.7.6 fixed `MessageGroupId` as `card_num` and `MessageDeduplicationId` as `transaction_id` —
because a group identity orders one card's messages only while every producer computes it
identically, and a deduplication identity suppresses a resend only while the requester can
predict it — nothing in the authorization context derived anything through that key.

**Why it survived as long as it did, which is the part worth keeping.** This section had already
been rewritten twice to stay true. Its original hazard — that rotating mid-flight would split one
card's messages across two group identifiers and forfeit per-card ordering — stopped existing when
the group identity became the literal card number. Its queue-depth check was then retained and
explicitly marked "no longer load-bearing". Its own text ended up conceding that "no component
injects that bean today, so no run-time value is currently derived from it" while still requiring
the key. Each revision was locally reasonable and the result was a procedure whose stated purpose
had been withdrawn twice over. The `checkov` `CKV2_AWS_57` suppression on the secret pointed here
for its justification, this section pointed at a required key, and the key was required because
the module demanded it — a loop with no participant able to be the one that goes. Withdrawing the
whole chain at once is what breaks it.

**What an operator does instead.** Nothing: there is no rotation to perform. If a future component
genuinely needs a keyed derivation in this context, it arrives with its own provisioned secret and
its own rotation procedure in the same change, and that procedure is written here then. The
rotations that remain live are documented in
[`deploy.md`](deploy.md): the two per-caller internal-identity signing keys, the pagination cursor
signing key and the card selector signing key. Assumptions: the reporting artifact-identity key is
deliberately NOT claimed here — it is a fifth generated key with its own attended-rotation
reasoning recorded on its resource, and no procedure for it exists in `deploy.md`, so naming it
would repeat the pointing-at-a-missing-document defect this record was written to stop.

**If an environment was applied before this change.** Its Secrets Manager entry is removed by the
next `terraform apply` of that root, subject to the root's `recovery_window_in_days`, and the next
task definition revision stops injecting the variable. No service reads it in either state, so the
order the two happen in does not matter and no quiesce bracket is needed.
