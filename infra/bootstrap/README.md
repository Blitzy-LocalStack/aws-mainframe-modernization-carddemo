# Terraform State Bootstrap

> **Purpose.** This Terraform root creates the account-scoped remote-state
> foundation used by every CardDemo environment: protected state storage,
> serialized state locking, encryption, object-access auditing, and the workload
> identity provider used by deployment automation.
>
> **Source of truth.** The HCL files in this directory define the resources,
> inputs, and outputs. The generated reference in
> [§8](#8-inputs-and-outputs) is synchronized from those files by
> [`infra/.terraform-docs.yml`](../.terraform-docs.yml).

Trade-offs: this root is self-contained and is applied once per AWS account,
outside either environment. It calls no module: sharing bucket hardening through
a module would reduce repeated HCL, but that module's state would belong in the
bucket being created and would invert the dependency this root exists to
establish.


---


## 1. What this root provisions

The backend contract consumed by the environment roots has two primary
resources:

* A versioned S3 bucket containing Terraform state.
* A DynamoDB table serializing concurrent writes through its `LockID` String
  partition key.

The same root owns the controls needed to operate and audit that contract:

* A customer-managed KMS key and alias encrypt the state bucket, lock table, and
  audit logs.
* S3 versioning, default KMS encryption, bucket-owner-enforced ownership, all
  four public-access blocks, lifecycle rules, and TLS-only bucket policies
  protect the state and audit buckets.
* A dedicated audit bucket receives validated CloudTrail data-event logs for
  reads and writes to state objects.
* A single-region CloudTrail trail records those object-level events.
* An account-scoped GitHub Actions OIDC provider enables short-lived workload
  identity without a stored cloud access key.

Assumptions: these controls are account-scoped shared prerequisites rather than
application-environment resources. Creating them in `infra/envs/dev` or
`infra/envs/prod` would give one environment ownership of infrastructure the
other environment also needs and would create a state-ownership conflict.


---


## 2. Prerequisites

An operator needs:

* Terraform CLI satisfying `~> 1.15.0`; this root is validated on Terraform
  1.15.8.
* The `hashicorp/aws` provider satisfying `~> 6.56`. `terraform init` resolves
  the provider using the tracked `.terraform.lock.hcl`.
* Ambient AWS credentials for the intended account and region. The provider has
  no `access_key`, `secret_key`, `profile`, or `assume_role` argument.
* Permission to create and configure:
  * A KMS key, alias, and key policy.
  * Two S3 buckets and their versioning, encryption, public-access, ownership,
    lifecycle, and bucket-policy resources.
  * A CloudTrail trail with S3 object data-event selectors.
  * A DynamoDB table with point-in-time recovery and KMS encryption.
  * An IAM OIDC provider for GitHub Actions.
* AWS CLI and `jq` only when using the manual version-purge procedure in
  [§10](#10-teardown).

Alternatives Considered: a pasted broad IAM policy was rejected because it
would either grant more than this root needs or become stale as the HCL changes.
The reviewed plan is the authoritative list of concrete API operations.


---


## 3. Deploy

Run this sequence from the repository root. Run it once in each AWS account
that hosts a CardDemo environment.

```bash
# WHAT: initialize the local-state root, save a reviewable plan, apply exactly
#       that plan, and print the values consumed by the environment backends.
# WHY : Alternatives Considered: a bare `terraform apply` was rejected because
#       it creates a second implicit plan at apply time. Saving `tfplan` makes
#       the reviewed graph and the applied graph identical.
terraform -chdir=infra/bootstrap init
terraform -chdir=infra/bootstrap plan -out=tfplan
terraform -chdir=infra/bootstrap apply tfplan
terraform -chdir=infra/bootstrap output
```

`init` takes no `-backend-config` arguments because this root has no remote
backend. The `tfplan` file is a local binary review artifact; remove
`infra/bootstrap/tfplan` when the apply is complete and never commit it.

Override non-secret configuration on the plan command without editing a tracked
file. For example, append `-var 'name_prefix=<prefix>'`,
`-var 'aws_region=<region>'`, or
`-var 'state_bucket_name=<state-bucket-name>'`. If dev and prod use different
AWS accounts, apply this root once in each account and record each account's
separate outputs.


---


## 4. Read the outputs and wire environments

The root exposes six non-sensitive string outputs:

| Output | Type | Consumer |
|---|---|---|
| `state_bucket_name` | `string` | The `bucket` argument of each environment's S3 backend. |
| `state_lock_table_name` | `string` | The `dynamodb_table` argument of each environment's S3 backend. |
| `aws_region` | `string` | The `region` argument of each environment's S3 backend. |
| `state_kms_key_arn` | `string` | The `kms_key_id` value used when an environment initializes the KMS-encrypted S3 backend. |
| `state_audit_bucket_name` | `string` | Operators and audit tooling identifying the bucket that receives state-object access logs. |
| `state_object_access_trail_arn` | `string` | Operators and audit tooling identifying the CloudTrail trail that records state-object reads and writes. |

Assumptions: a Terraform backend block is evaluated before normal variables,
locals, data sources, and resources. It therefore cannot consume `var.*`,
`local.*`, or another root's outputs directly; an operator or deployment system
must read these outputs and supply literal backend values.

The following is illustrative only. The real backend configuration belongs to
the environment root, and every value shown here remains a placeholder:

```hcl
terraform {
  backend "s3" {
    bucket         = "<state-bucket-name>"
    key            = "<env>/terraform.tfstate"
    region         = "<region>"
    dynamodb_table = "<lock-table-name>"
    kms_key_id     = "<state-kms-key-arn>"
    encrypt        = true
  }
}
```

Trade-offs: none of the six outputs is marked sensitive. Bucket and table names,
a region, and resource ARNs are identifiers rather than credentials; marking
them sensitive would hide them from the ordinary `terraform output` table and
add friction to the required handoff without controlling access. IAM, KMS
policy, the bucket policies, and the public-access blocks control who can use
the resources.

The dependency direction is one-way: `infra/envs/dev` and `infra/envs/prod`
depend on this root, while this root depends on nothing under `infra/modules` or
`infra/envs`.


---


## 5. Ordering: first on deploy, last on teardown

> **Note**
>
> Apply `infra/bootstrap` **first** and destroy it **last**. Reversing either
> order breaks the state dependency shared by every environment.

Deployment order:

1. Apply `infra/bootstrap` once for the target AWS account.
1. Read its backend outputs and supply them to the selected environment.
1. Initialize and apply `infra/envs/dev` and/or `infra/envs/prod`.

Assumptions: an environment's backend-enabled `terraform init` cannot succeed
until the state bucket, lock table, region, and KMS key are available.

Teardown order is the exact reverse:

1. Destroy every environment root while its remote state is still reachable.
1. Preserve or disposition the state versions and audit evidence.
1. Destroy `infra/bootstrap` only for account decommission.

Destroying the backend first orphans the state files that describe every
remaining resource. The resources keep running, but Terraform no longer has the
state needed to address and remove them; recovery becomes manual inventory,
import, and deletion work.

The same ordering is owned at three broader levels by the
[infrastructure overview](../README.md), the
[deployment runbook](../../docs/runbooks/deploy.md), and the
[teardown runbook](../../docs/runbooks/teardown.md). This README owns the
bootstrap commands; those documents own the package-wide operator sequence.


---


## 6. Local state and the absent backend file

Assumptions: this root creates the S3 bucket and DynamoDB table required by an
S3 backend, so using that backend for its own first apply would be circular. The
repository therefore ships no `backend.tf` in this directory. Terraform writes
this root's state locally as `infra/bootstrap/terraform.tfstate`.

The repository-root [`.gitignore`](../../.gitignore), not a file in this folder,
excludes `.terraform/`, `*.tfstate`, `*.tfstate.*`, `*.tfplan`, and
`*.tfplan.*`. Local state can still contain infrastructure identifiers and
provider-returned sensitive values, so keep it on encrypted, access-controlled
storage even though version control ignores it.

Alternatives Considered: a random bucket suffix was rejected because it would
make loss of local state harder to recover. The deterministic state-bucket name
combines the prefix, caller account identifier, and region; the lock table
derives from the prefix. An operator can discover those resources and re-adopt
them with `terraform import`. [§11](#11-troubleshooting) shows the recovery
shape.

Trade-offs: Terraform can migrate this root's state into an S3 backend by using
an operator-local backend configuration and `terraform init -migrate-state`.
That is awareness, not the repository procedure: this repository deliberately
ships no backend file for this root and does not recommend committing one.
Keeping the bootstrap state local avoids making backend recovery and teardown
depend on the backend being recovered or removed.


---


## 7. CI validates but never applies

`.github/workflows/infra-ci.yml` validates this root without credentials or a
configured backend:

```bash
# WHAT: install the locked provider schema without configuring remote state,
#       then validate the bootstrap root.
# WHY : Assumptions: `validate` does not evaluate `aws_caller_identity`, while a
#       real plan does. `-backend=false` therefore checks the configuration
#       without requiring the backend or AWS credentials.
terraform -chdir=infra/bootstrap init -backend=false -lockfile=readonly -input=false
terraform -chdir=infra/bootstrap validate
```

Alternatives Considered: regenerating documentation in CI was rejected because
it would hide drift by mutating the checkout. The workflow instead checks
Terraform formatting, runs recursive TFLint, runs terraform-docs in check-only
mode, and performs policy and secret scans. Stale generated content fails and
must be regenerated in the authoring change.

Assumptions: `.github/workflows/deploy.yml` deliberately excludes
`infra/bootstrap`. The deployment job needs the real backend in order to
initialize an environment, so asking the same job to create that backend would
be the same circular dependency this root avoids locally.

Assumptions: `.terraform.lock.hcl` is tracked because it records the selected
provider and checksums. CI uses `-lockfile=readonly`, which verifies that
contract without changing it; an operator commits a reviewed lock update rather
than deleting or silently rewriting it.

> **Note**
>
> A human applies this root deliberately and out of band. No pipeline creates
> or destroys it.


---


## 8. Inputs and outputs

Assumptions: the generated region below is the machine-owned contract for all
nine inputs, all six outputs, version requirements, providers, resources, and
data sources. Human-owned guidance stays outside the markers because
terraform-docs replaces everything between them.

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | ~> 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_cloudtrail.state_object_access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudtrail) | resource |
| [aws_dynamodb_table.state_lock](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/dynamodb_table) | resource |
| [aws_iam_openid_connect_provider.github_actions](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_openid_connect_provider) | resource |
| [aws_kms_alias.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_key.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_s3_bucket.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket_lifecycle_configuration.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_lifecycle_configuration.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_ownership_controls.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_ownership_controls.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_policy.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_policy.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_public_access_block.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_public_access_block.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_versioning.state](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_s3_bucket_versioning.state_audit](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.state_audit_bucket](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.state_bucket](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.state_key](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_audit_log_retention_days"></a> [audit\_log\_retention\_days](#input\_audit\_log\_retention\_days) | Finite lifecycle horizon for validated CloudTrail state-object data-event logs. | `number` | `2557` | no |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | AWS region the state bucket and lock table are created in. Read by the aws provider in versions.tf, composed into the default bucket name in main.tf, and echoed by outputs.tf so it can be transcribed into each environment root's backend configuration. Accepts a standard region identifier, for example us-east-1 or ap-southeast-4. | `string` | `"us-east-1"` | no |
| <a name="input_lock_table_name"></a> [lock\_table\_name](#input\_lock\_table\_name) | Explicit name for the DynamoDB state-lock table, overriding the name main.tf composes from name\_prefix. Resolved by the matching coalesce in main.tf, so leaving it null selects the composed name. Set it to adopt an existing table or to satisfy an account naming standard. | `string` | `null` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading component of the composed names for both halves of the backend. Read by the locals block in main.tf, which appends the fixed -tfstate- segment, the caller's account identifier and aws\_region to build the bucket name, and derives the lock table name from the same prefix. | `string` | `"carddemo"` | no |
| <a name="input_state_bucket_force_destroy"></a> [state\_bucket\_force\_destroy](#input\_state\_bucket\_force\_destroy) | Whether terraform destroy may delete the state bucket while it still holds objects and non-current versions. Read by force\_destroy on the bucket resource in main.tf. Keep false so destroying a populated bucket fails; set true only for a deliberate account decommission, which permanently discards all state history. | `bool` | `false` | no |
| <a name="input_state_bucket_name"></a> [state\_bucket\_name](#input\_state\_bucket\_name) | Explicit name for the Terraform state bucket, overriding the name main.tf composes from name\_prefix, the caller's account identifier and aws\_region. Resolved by coalesce in main.tf, so leaving it null selects the composed name. Set it to adopt an existing bucket or to satisfy an account naming standard. | `string` | `null` | no |
| <a name="input_state_noncurrent_versions_to_retain"></a> [state\_noncurrent\_versions\_to\_retain](#input\_state\_noncurrent\_versions\_to\_retain) | Number of newest noncurrent Terraform state versions retained regardless of age. | `number` | `20` | no |
| <a name="input_state_version_retention_days"></a> [state\_version\_retention\_days](#input\_state\_version\_retention\_days) | Minimum age in days before a state object version beyond the retained recent-version count may expire. | `number` | `365` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Common tag set merged into every taggable resource in this root through the provider default\_tags block in versions.tf. Values must be non-secret: tags are visible to any principal that can describe the resource and appear in cost-allocation exports. | `map(string)` | <pre>{<br/>  "Component": "tfstate-backend",<br/>  "ManagedBy": "terraform",<br/>  "Project": "carddemo"<br/>}</pre> | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_aws_region"></a> [aws\_region](#output\_aws\_region) | AWS region containing both the state bucket and the lock table. Supply it as the `region` argument of each environment root's S3 backend block. |
| <a name="output_state_audit_bucket_name"></a> [state\_audit\_bucket\_name](#output\_state\_audit\_bucket\_name) | Name of the versioned S3 bucket receiving validated CloudTrail data-event logs for every Terraform state object read and write. |
| <a name="output_state_bucket_name"></a> [state\_bucket\_name](#output\_state\_bucket\_name) | Name of the versioned, encrypted S3 bucket that holds Terraform state for every other root in this repository. Supply it as the `bucket` argument of each environment root's S3 backend block. |
| <a name="output_state_kms_key_arn"></a> [state\_kms\_key\_arn](#output\_state\_kms\_key\_arn) | ARN of the bootstrap-owned customer-managed KMS key encrypting Terraform state, the lock table and immutable access-audit logs. |
| <a name="output_state_lock_table_name"></a> [state\_lock\_table\_name](#output\_state\_lock\_table\_name) | Name of the DynamoDB table Terraform uses to serialise concurrent writes to the state object. Supply it as the `dynamodb_table` argument of each environment root's S3 backend block. Its partition key is `LockID` of type String, which is the schema the S3 backend requires and writes the lock item under. |
| <a name="output_state_object_access_trail_arn"></a> [state\_object\_access\_trail\_arn](#output\_state\_object\_access\_trail\_arn) | ARN of the CloudTrail trail whose advanced selector records object-level access to the Terraform state bucket. |
<!-- END_TF_DOCS -->


---


## 9. Idempotency and safe re-runs

With unchanged configuration, account, and region, another `terraform apply`
reconciles the declared graph with the resources already recorded in state and
converges to a no-op. An interrupted apply can be planned again; Terraform
refreshes the known resources and proposes only the work still required.

The closest baseline analogue is
[`app/jcl/DEFGDGB.jcl`](../../app/jcl/DEFGDGB.jcl), the out-of-band IDCAMS job
that defines durable generation-data-group bases before batch jobs use them.
IDCAMS reports an already-defined base as condition code 12, so the job guards
each definition with `IF LASTCC=12 THEN SET MAXCC=0` at lines 29, 35, 41, 47,
53, and 59. Terraform expresses the same safe re-run outcome through state
reconciliation, without a per-resource condition-code guard.

Refactoring Rationale: this is a mechanical simplification, not a criticism or
retirement of the mainframe path. The baseline remains the behavioral
reference. Its generation-retention contract is also a different concern from
Terraform state history; the state bucket uses its own age and retained-count
inputs rather than copying the batch-data policy.

A re-run is not automatically a no-op when configuration, variables, provider
behavior, or actual AWS resources have changed. Always review the saved plan;
idempotency means convergence to the declared state, not suppression of a real
drift correction.


---


## 10. Teardown

Trade-offs: destroy `infra/envs/prod` and `infra/envs/dev` first. Destroy this
root last and only when the AWS account no longer needs any CardDemo
environment. If the account may host CardDemo again, leave the bootstrap in
place; retaining the backend costs less than reconstructing lost state history
and audit evidence.

Both the state bucket and the audit bucket are versioned. A plain
`aws s3 rm --recursive` removes current objects but leaves noncurrent versions
and delete markers, so it is insufficient.

### Path A: Terraform purges the state bucket

`state_bucket_force_destroy` exists for deliberate account decommission and
defaults to `false`. Set it explicitly, apply the reviewed change, stop the
audit trail, purge the audit bucket, and destroy with the same override:

```bash
# WHAT: authorize Terraform to purge the versioned state bucket during destroy.
# WHY : Trade-offs: this permanently discards every retained state version, so
#       the destructive behavior requires an explicit variable rather than a
#       permissive default.
terraform -chdir=infra/bootstrap plan -out=tfplan \
  -var 'state_bucket_force_destroy=true'
terraform -chdir=infra/bootstrap apply tfplan
```

Assumptions: the variable controls only the state bucket. The audit bucket is
also versioned and has `force_destroy = false`, so every complete teardown
purges that bucket explicitly using the function under Path B.

### Path B: version-aware AWS CLI purge

Use this for either tracked bucket when retaining its contents is no longer
required, or for a bucket that local Terraform state no longer tracks. The loop
deletes at most 1,000 versions and delete markers per request and repeats until
the bucket is empty:

```bash
# WHAT: list and delete every object version and delete marker in one bucket.
# WHY : Assumptions: S3 refuses to delete a versioned bucket while any version
#       or delete marker remains; deleting current keys alone does not empty it.
purge_versioned_bucket() {
  local bucket="$1" batch response count failures

  while :; do
    batch="$(
      aws s3api list-object-versions \
        --bucket "$bucket" \
        --max-items 1000 \
        --output json |
        jq -c '{
          Objects: (
            [.Versions[]?, .DeleteMarkers[]?] |
            map({Key: .Key, VersionId: .VersionId})
          ),
          Quiet: true
        }'
    )"
    count="$(jq -r '.Objects | length' <<<"$batch")"
    test "$count" -gt 0 || break

    response="$(
      aws s3api delete-objects \
        --bucket "$bucket" \
        --delete "$batch" \
        --output json
    )"
    test -n "$response" || response='{}'
    failures="$(jq -r '.Errors // [] | length' <<<"$response")"
    test "$failures" -eq 0 || {
      jq -r '.Errors' <<<"$response" >&2
      return 1
    }
  done
}
```

For Path A, stop new audit delivery, purge only the audit bucket, and let
Terraform purge the state bucket:

```bash
# WHAT: stop audit delivery, empty the audit bucket, and destroy the root with
#       state-bucket force deletion still explicit.
# WHY : Assumptions: stopping the trail ends active audit delivery before the
#       bucket purge. If AWS delivers an already-buffered object, run the purge
#       again before destroy; destroy then removes the stopped trail and every
#       remaining bootstrap resource.
AUDIT_BUCKET="$(
  terraform -chdir=infra/bootstrap output -raw state_audit_bucket_name
)"
BOOTSTRAP_REGION="$(
  terraform -chdir=infra/bootstrap output -raw aws_region
)"
TRAIL_ARN="$(
  terraform -chdir=infra/bootstrap output -raw state_object_access_trail_arn
)"
aws cloudtrail stop-logging \
  --region "$BOOTSTRAP_REGION" \
  --name "$TRAIL_ARN"
purge_versioned_bucket "$AUDIT_BUCKET"
terraform -chdir=infra/bootstrap destroy \
  -var 'state_bucket_force_destroy=true'
```

For Path B, purge both buckets before the normal destroy:

```bash
# WHAT: stop audit delivery, purge both versioned buckets, and destroy with the
#       protective default still in force.
# WHY : Alternatives Considered: `aws s3 rm --recursive` was rejected because
#       it leaves noncurrent versions and delete markers behind.
STATE_BUCKET="$(
  terraform -chdir=infra/bootstrap output -raw state_bucket_name
)"
AUDIT_BUCKET="$(
  terraform -chdir=infra/bootstrap output -raw state_audit_bucket_name
)"
BOOTSTRAP_REGION="$(
  terraform -chdir=infra/bootstrap output -raw aws_region
)"
TRAIL_ARN="$(
  terraform -chdir=infra/bootstrap output -raw state_object_access_trail_arn
)"
aws cloudtrail stop-logging \
  --region "$BOOTSTRAP_REGION" \
  --name "$TRAIL_ARN"
purge_versioned_bucket "$STATE_BUCKET"
purge_versioned_bucket "$AUDIT_BUCKET"
terraform -chdir=infra/bootstrap destroy
```

There is no `lifecycle { prevent_destroy = true }` on the buckets or table and
no DynamoDB deletion protection. Alternatives Considered: either control would
make the checked-in [teardown runbook](../../docs/runbooks/teardown.md) stop at
an undocumented refusal. The versioned-bucket purge is already the deliberate,
data-loss-specific barrier, and `state_bucket_force_destroy` makes its waiver
explicit.


---


## 11. Troubleshooting

### `BucketAlreadyExists` or `BucketAlreadyOwnedByYou`

* **Symptom:** the first apply cannot create `aws_s3_bucket.state` or
  `aws_s3_bucket.state_audit`.
* **Cause:** both S3 bucket names are global across AWS accounts. Their composed
  names include the caller account identifier and region to reduce collisions,
  but global uniqueness cannot be validated before the API call. DynamoDB table
  names need no account identifier because they are scoped by account and
  region.
* **Fix:** for a state-bucket collision, plan with
  `-var 'state_bucket_name=<state-bucket-name>'`; for an audit-bucket collision,
  use a different `name_prefix`, which composes both names. If the current
  account owns the intended pre-existing bucket, import it instead of attempting
  a duplicate create.

### `Error acquiring the state lock`

* **Symptom:** an environment plan or apply prints a `Lock Info` block and stops.
* **Cause:** another Terraform operation holds the DynamoDB lock, or an
  interrupted operation left its item behind.
* **Fix:** read the lock ID, path, operation, owner, and creation value in
  `Lock Info`. Wait when the named operation is active. Only after confirming
  that no operation still owns the lock, run:

```bash
# WHAT: remove one confirmed-orphaned environment state lock.
# WHY : Assumptions: the operator has identified the lock owner and verified
#       that no plan or apply still writes the protected state.
terraform -chdir="infra/envs/<env>" force-unlock "<LOCK_ID>"
```

Alternatives Considered: a lock-table TTL was rejected because it cannot
distinguish an orphaned item from a legitimately long operation; expiring a live
lock would turn a visible stall into an invisible concurrent-write hazard.

### `NoSuchBucket` during environment initialization

* **Symptom:** backend-enabled `terraform init` in an environment reports that
  the configured state bucket does not exist.
* **Cause:** bootstrap was not applied in that account, or the supplied backend
  values belong to a different account or region.
* **Fix:** apply this root first, run
  `terraform -chdir=infra/bootstrap output`, and supply the outputs for the
  intended account to the environment initialization.

### Local bootstrap state was lost

* **Symptom:** a new plan proposes resources that already exist, or apply reports
  name collisions after `infra/bootstrap/terraform.tfstate` was lost.
* **Cause:** Terraform no longer has the local ownership records for this root.
* **Fix:** initialize locally, import the known resources, and inspect a plan
  before any apply. The principal recovery shape is:

```bash
# WHAT: re-adopt the deterministic state bucket and lock table, then inspect the
#       remaining graph.
# WHY : Assumptions: the placeholders identify the resources created by this
#       root in the intended account and region.
terraform -chdir=infra/bootstrap import \
  aws_s3_bucket.state "<state-bucket-name>"
terraform -chdir=infra/bootstrap import \
  aws_dynamodb_table.state_lock "<lock-table-name>"
terraform -chdir=infra/bootstrap plan
```

Assumptions: those two imports are the starting point, not permission to apply a
plan that still proposes duplicate infrastructure. Import the existing KMS,
audit, CloudTrail, OIDC, and S3 sub-resources under their declared addresses
until the plan describes the intended configuration rather than replacement
resources. The deterministic names and KMS alias make those resources
discoverable; a random bucket suffix would not.

### terraform-docs drift fails CI

* **Symptom:** the check-only terraform-docs step reports this README as stale.
* **Cause:** `versions.tf`, `variables.tf`, `outputs.tf`, or the resource graph
  changed without regenerating the machine-owned block.
* **Fix:** run the generator locally and commit the resulting README:

```bash
# WHAT: regenerate this root's machine-owned Terraform reference.
# WHY : Assumptions: CI uses the same pinned configuration in check-only mode
#       and never commits a repair for the author.
terraform-docs --config infra/.terraform-docs.yml infra/bootstrap
```

### Validation succeeds but planning reports credentials errors

* **Symptom:** `terraform validate` passes, while `terraform plan` cannot resolve
  the AWS identity.
* **Cause:** validation checks configuration and provider schemas without
  evaluating `data.aws_caller_identity.current`; planning evaluates it.
* **Fix:** use ambient credentials for the intended account before planning.
  Credential-free CI intentionally stops at initialization and validation.


---


## 12. Design decisions

These rationales are the prose half of the repository's
[code documentation standard](../../docs/CODE_DOCUMENTATION_STANDARD.md) and
the contribution contract in [`CONTRIBUTING.md`](../../CONTRIBUTING.md).

1. **Local state and no backend file.** Assumptions: an S3 backend cannot be
   initialized until the bucket and lock table exist, and this root creates
   them. The same choice enables credential-free CI validation with
   `init -backend=false`. A committed backend for this root would make both
   creation and recovery depend on infrastructure that is absent in the failure
   case.

1. **Human-applied, out-of-band lifecycle.** Alternatives Considered: adding
   bootstrap to `deploy.yml` was rejected because that workflow initializes an
   environment against the backend before it can apply anything. A pipeline
   cannot create its own prerequisite without a separate, independently
   credentialed state path, which would duplicate the bootstrap problem rather
   than solve it.

1. **Versioned state storage.** Refactoring Rationale:
   [`app/csd/CARDDEMO.CSD`](../../app/csd/CARDDEMO.CSD) declares each baseline
   file with `RECOVERY(NONE)`, `JOURNAL(NO)`, and `FWDRECOVLOG(NO)`, including
   the line pairs 7/9, 19/21, 31/33, 44/46, 57/59, and 70/72. State versioning
   provides a prior object image when a state write is truncated or overwritten.
   Trade-offs: retained versions and delete markers make bucket teardown an
   explicit purge operation.

1. **Finite state-history policy.** Trade-offs: the state bucket always retains
   the newest configured noncurrent-version count and expires older versions
   only after the configured minimum age. The defaults retain 20 recent
   noncurrent versions and apply a 365-day minimum age to older ones, bounding
   storage and stale sensitive attributes without making one mistaken write
   unrecoverable. The dataset module's retention policy governs business-data
   generations and is not copied into this state-specific policy.

1. **A bootstrap-owned customer-managed KMS key.** Refactoring Rationale:
   SSE-S3 was rejected because it would encrypt bytes without a project-owned
   key policy, rotation setting, or KMS decrypt audit. The key cannot come from
   `infra/modules/kms`, whose state belongs in this backend; this root therefore
   owns the key directly and uses it for state, locking, and access-audit logs.
   Trade-offs: the backend depends on KMS availability and KMS request
   billing, reduced by S3 Bucket Keys.

1. **Public access blocked and TLS enforced at the bucket.** Assumptions: a
   Terraform state object enumerates resource identifiers and may retain
   provider-returned sensitive values. Public read access would disclose the
   account's infrastructure map, not merely one filename. All four S3
   public-access settings close ACL and bucket-policy paths, while a bucket
   policy denies insecure transport for both bucket-level and object-level ARNs.

1. **DynamoDB locking with `LockID` String.** Assumptions: `LockID` and its
   String type are an external Terraform S3-backend contract, not a local naming
   preference. HashiCorp marks `dynamodb_table` deprecated and names
   `use_lockfile` as its replacement, while allowing both mechanisms together.
   The table remains because the project contract mandates it; an environment
   can also use S3 lock files without changing this root.

1. **On-demand billing and point-in-time recovery.** Trade-offs: lock traffic is
   a burst of small reads and writes around Terraform operations, with no stable
   utilization level from which to size provisioned capacity. Per-request
   billing avoids paying for idle units. PITR is inexpensive recovery insurance
   if the table is deleted or emptied, but it does not restore S3 state and does
   not replace a reviewed `force-unlock` for an orphaned item.

1. **Deterministic names and no random provider.** Alternatives Considered: a
   random bucket suffix would reduce collision probability but make the name
   unrecoverable from prefix, account, and region after local state loss. The
   deterministic composition supports discovery and `terraform import`, and it
   avoids declaring `hashicorp/random` where no runtime value needs randomness.

1. **Deliberate absence of generic deletion and timeout controls.**
   Alternatives Considered: `prevent_destroy`, DynamoDB deletion protection,
   and lock TTL were each rejected for specific operational conflicts.
   The first two would contradict the checked-in teardown procedure; TTL can
   release a lock still held by a long operation. S3 server access logging is
   also absent because this root already provisions a dedicated, validated
   CloudTrail data-event trail scoped to state-object reads and writes, with its
   own encrypted audit bucket. Duplicating it would add another log stream
   without improving state-object attribution.

1. **Account-scoped GitHub OIDC provider.** Assumptions: the issuer is shared by
   dev and prod, so declaring it in either environment would give one root
   ownership of an account-wide identity resource and make the other root
   conflict. The provider omits a pinned certificate thumbprint so IAM resolves
   the issuer chain instead of binding federation to one certificate value.


---


## 13. Secrets and committed-state controls

This root has no credential input. Its nine inputs are non-secret configuration:
a region, a prefix, two optional name overrides, two state-history controls, an
audit-retention control, a force-destroy boolean, and a tag map. Tags must remain
non-secret because principals that can describe resources and cost-allocation
systems can read them.

Assumptions: credentials come from the operator's ambient AWS configuration.
Deployment automation uses short-lived OIDC role assumption; no long-lived cloud
access key is stored in this directory or passed as a Terraform variable. The
OIDC provider resource establishes trust metadata, not a credential.

Assumptions: no `terraform.tfvars` belongs to this root. Apply-time overrides
use `-var` with non-secret values. Local `.terraform/`, state files, and plan
files are excluded by the repository-root `.gitignore`;
`.terraform.lock.hcl` remains tracked because it contains provider versions and
checksums, not credentials.

Trade-offs: outputs are intentionally visible identifiers, but state files can
contain sensitive provider values. The controls are therefore structural: local
state stays out of version control; remote state is KMS-encrypted, versioned,
non-public, TLS-only, and access-audited; provider credentials stay outside HCL.

Every example in this document uses placeholders such as `<prefix>`, `<region>`,
`<env>`, `<state-bucket-name>`, `<lock-table-name>`,
`<state-kms-key-arn>`, and `<LOCK_ID>`. Substitute real values only in the
operator's shell or approved deployment system, never in a commit.