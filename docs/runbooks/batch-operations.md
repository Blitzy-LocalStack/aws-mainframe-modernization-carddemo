# Batch Operations Runbook

> **Purpose.** Operate, inspect, and redrive the CardDemo nightly and ad-hoc
> Step Functions workflows.
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
ENVIRONMENT=dev
DAILY_ARN="$(terraform -chdir="infra/envs/${ENVIRONMENT}" output -raw daily_batch_state_machine_arn)"
ADHOC_ARN="$(terraform -chdir="infra/envs/${ENVIRONMENT}" output -raw adhoc_report_state_machine_arn)"
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
finalizer rule releases the bracket. If it is in the **past**, no action is needed
at all — the next acquisition will take the lease over, because the acquisition
condition admits an expired lease. Trade-offs: that is why a crashed execution does
not require an operator to clear anything by hand, and it is also why the item
should not be deleted manually while `expiresAt` is still in the future — doing so
would hand the bracket to a second execution while the first is still writing.

## Start an Ad-Hoc Report

```bash
# WHAT: run the report workflow for an inclusive date range.
# WHY : Assumptions: report selection is a record predicate, not a batch-step
#       gate, so both dates travel as workflow input to reporting-service.
START_DATE=2022-07-01
END_DATE=2022-07-31
aws stepfunctions start-execution \
  --state-machine-arn "$ADHOC_ARN" \
  --name "report-${START_DATE}-${END_DATE}-$(date -u +%H%M%S)" \
  --input "{\"startDate\":\"${START_DATE}\",\"endDate\":\"${END_DATE}\"}"
```

## Poison-Message Handling

Authorization FIFO DLQ entries are quarantined. Do not bulk-redrive them:
review one message, preserve its opaque group identifier, correct the cause, and
replay one message at a time. This maintains the per-group order contract and
prevents a later authorization from overtaking the failed one.
