# Teardown Runbook

> **Purpose.** Remove a CardDemo environment in the exact reverse dependency
> order of deployment while preserving required evidence and state until last.
>
> **Source of truth.** `docs/runbooks/deploy.md`, the environment Terraform
> roots, and `infra/bootstrap/**`.

Trade-offs: destructive operations are intentionally not automated into one
command. Versioned buckets, deletion protection, and retained state are
independent safeguards; bypassing all of them through a wrapper would make an
operator typo account-wide.

## 1. Select and Quiesce the Environment

```bash
# WHAT: bind every later command to one reviewed environment root.
# WHY : Assumptions: dev and prod have identical topology but different
#       protection/retention values; an implicit default is unsafe for teardown.
ENVIRONMENT=dev
ENV_ROOT="infra/envs/${ENVIRONMENT}"
terraform -chdir="$ENV_ROOT" init -input=false -lockfile=readonly
terraform -chdir="$ENV_ROOT" plan -destroy -out=destroy.tfplan
terraform -chdir="$ENV_ROOT" show destroy.tfplan
```

Disable the scheduler and wait for any active daily or ad-hoc execution to
finish or fail cleanly. Confirm online-write quiesce has been cleared.

## 2. Preserve Required Evidence

Export audit logs, statement/report artifacts, database snapshots, and the
reviewed destroy plan according to the environment's retention policy. This
step is mandatory before emptying a bucket whose module deliberately sets
`force_destroy = false`.

## 3. Empty Protected Application Buckets

Resolve the SPA and dataset bucket names from Terraform outputs. Delete current
objects and all retained versions/delete markers only after evidence approval.

```bash
# WHAT: identify the two application buckets Terraform will refuse to destroy while populated.
# WHY : Assumptions: names are outputs rather than constants and must be read
#       from the state being removed.
# WHY : Refactoring Rationale: both names are read from the module output OBJECT the root
#       publishes -- `spa` and `datasets` -- rather than from root outputs named
#       `spa_bucket_name` and `dataset_bucket_name`. Neither root output exists, so both
#       reads failed with "Output ... not found" and the two variables were empty; the
#       printf below then invited a reviewer to approve emptying two unnamed buckets.
SPA_BUCKET="$(terraform -chdir="$ENV_ROOT" output -json spa | jq -r '.spa_bucket_name')"
DATASET_BUCKET="$(terraform -chdir="$ENV_ROOT" output -json datasets | jq -r '.bucket_name')"
printf 'Review before emptying: %s %s\n' "$SPA_BUCKET" "$DATASET_BUCKET"
```

Use the organization's approved version-aware S3 purge procedure. A plain
`aws s3 rm --recursive` removes only current objects and is insufficient for a
versioned bucket.

## 4. Destroy the Environment Root

```bash
# WHAT: apply the reviewed destroy plan for the selected environment.
# WHY : Assumptions: the saved plan is invalidated by any intervening
#       configuration or state change; regenerate it after a bucket purge.
terraform -chdir="$ENV_ROOT" plan -destroy -out=destroy.tfplan
terraform -chdir="$ENV_ROOT" apply destroy.tfplan
```

Verify that ECS services/tasks, Step Functions executions, certificates,
database resources, queues, logs, and edge resources are gone before
continuing.

## 5. Retain or Remove the Shared Bootstrap Last

If another CardDemo environment remains, stop: the state bucket, lock table,
KMS key, and audit trail are shared prerequisites.

When no environment remains, export and disposition all state versions and
audit evidence. The bootstrap root uses local state and must be destroyed only
after its remote-state objects are no longer needed.

```bash
# WHAT: review and remove the state backend after every dependent environment.
# WHY : Trade-offs: retaining bootstrap costs storage, KMS, audit, and lock-table
#       requests; removing it forfeits online state recovery. The retention
#       decision must therefore be explicit and last.
terraform -chdir=infra/bootstrap init -input=false -lockfile=readonly
terraform -chdir=infra/bootstrap plan -destroy -out=destroy.tfplan
terraform -chdir=infra/bootstrap show destroy.tfplan
terraform -chdir=infra/bootstrap apply destroy.tfplan
```

## 6. Verify Teardown

Run `terraform state list` in the environment and bootstrap roots; both should
be empty after their respective destroys. Confirm no local plan, state,
credential, certificate-key, or temporary test artifact remains in the working
tree.
