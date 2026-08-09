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

## Rotate the Messaging HMAC Key (operator-managed)

Each environment root holds `<name-prefix>/<env>/messaging/hmac-key`, injected into the
`authorization` task alone and read by `com.carddemo.common.messaging` to derive the FIFO
**message-group identity** and the correlation identity of every pending-authorization message. Its
resource carries a recorded `checkov` suppression for `CKV2_AWS_57` stating that rotation is an
**attended** procedure documented in this runbook. This section is that procedure.

> Refactoring Rationale: the suppression cited this file while no such procedure existed in it. A
> suppression whose justification points at a missing document is indistinguishable from an
> unjustified one — the reviewer accepts a promise and the operator finds nothing. The procedure is
> written here rather than in `deploy.md` because the constraint that makes it attended is a
> **messaging-ordering** constraint, and the window it must run in is the batch quiesce bracket this
> runbook already defines.

**Why this one cannot be rotated while the queue is non-empty.** The group identity is derived from
the key, so equal cards must derive equal groups across every producer *at the same instant*. Change
the key while messages are in flight and one card's messages split across two group identifiers —
which silently forfeits the per-card ordering guarantee the key exists to provide. That failure does
not surface as an error: both groups are processed, just not in one order. It is therefore the one
rotation here whose damage is invisible at the time it occurs.

Run it **inside the batch quiesce bracket**, and only after confirming the request queue and its
dead-letter queue are both empty.

```bash
# WHAT: confirms there is nothing in flight before the key changes.
# WHY : Assumptions: both queues are checked, and the dead-letter queue is not optional -- a
#       quarantined message replayed after rotation would be re-grouped under the new key while its
#       siblings were grouped under the old one, which is the split this procedure exists to avoid.
#       ApproximateNumberOfMessagesNotVisible is included because an in-flight message held under a
#       visibility timeout is exactly the case a depth-only check misses.
aws sqs get-queue-attributes --region "<aws-region>" \
  --queue-url "<pauth-request-queue-url>" \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
aws sqs get-queue-attributes --region "<aws-region>" \
  --queue-url "<pauth-request-dlq-url>" \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible
```

```bash
# WHAT: replaces the stored key with freshly generated bytes, without the value ever appearing in a
#       command line.
# WHY : Trade-offs: the value travels on STDIN via --secret-string fileb:///dev/stdin. An argv value
#       is readable from the process table by any local process for as long as the call runs, is
#       retained by the shell's history file, and is echoed by `set -x`. `set +o xtrace` is issued
#       explicitly for that last reason.
# WHY : Assumptions: --exclude-punctuation matches the generator both roots use (special = false),
#       and 32 characters matches the byte floor the deriving component enforces at start-up.
#       --query null keeps the new version identifier out of the transcript.
set +o xtrace
aws secretsmanager get-random-password --region "<aws-region>" \
  --exclude-punctuation --password-length 32 --query RandomPassword --output text \
  | tr -d '\n' \
  | aws secretsmanager put-secret-value --region "<aws-region>" \
  --secret-id "<name-prefix>/<env>/messaging/hmac-key" \
  --secret-string fileb:///dev/stdin \
  --query "null" --output text
```

```bash
# WHAT: rolls the single consuming service and waits for it to settle before the bracket is released.
# WHY : Assumptions: exactly ONE service binds this key, which is what makes this rotation simpler
#       than the internal-identity one -- there is no second holder to keep in step, so the only
#       requirement is that no message is processed while two tasks hold different keys. Waiting for
#       PRIMARY to reach COMPLETED before resuming online writes is what guarantees that.
aws ecs update-service --region "<aws-region>" --cluster "<cluster-name>" \
  --service "<authorization-service-name>" --force-new-deployment
aws ecs describe-services --region "<aws-region>" --cluster "<cluster-name>" \
  --services "<authorization-service-name>" \
  --query "services[].deployments[?status=='PRIMARY'].[serviceName:@.id,rolloutState]" --output table
```

Advance the secret's `secret_string_wo_version` in a reviewed diff instead if the value should be
regenerated by Terraform. The identity performing the manual form needs
`secretsmanager:PutSecretValue` on that one entry, `kms:GenerateDataKey` and `kms:Decrypt` through
Secrets Manager on the secrets CMK, `sqs:GetQueueAttributes` on the two queues above, and
`ecs:UpdateService` and `ecs:DescribeServices` on the authorization service. No task role should ever
hold `PutSecretValue`: the task reads this entry and never writes it.
