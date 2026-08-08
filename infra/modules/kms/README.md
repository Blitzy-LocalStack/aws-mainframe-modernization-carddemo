# `infra/modules/kms/` — Customer-managed encryption keys

**Purpose.** This module provisions the five customer-managed KMS keys the
CardDemo target stack encrypts itself with — one for the Aurora PostgreSQL
cluster, one for the versioned S3 dataset bucket and the object, log and alert
data carried alongside it, one for Secrets Manager, one for the SQS queue
set, and one for the values the migrated code enciphers **itself** rather than
through an integrated service. Each key is created with automatic rotation of its
key material enabled, an
alias of the form `alias/<name-prefix>-<data-class>-<environment>`, and a key
policy that reserves administration to the account root while granting
cryptographic use only to the principals its caller names.

**Source of truth.** Every claim below about the module's behaviour is taken from
the four `.tf` files in this directory; every claim about the mainframe baseline is
cited to a path and a line under `app/**`, which this migration reads as reference
material and never modifies. The input and output tables are not written by hand:
they are generated from those same `.tf` files into
[the generated reference](#generated-terraform-reference) below, so this prose
and that table cannot disagree without the drift gate reporting it.

**Why this README exists.** HCL has no docstring construct, so the project's
Explainability obligation for Terraform is met in two halves. Inside the `.tf`
files, a file-header block, a `description` on every variable and output, and a
why-comment on each non-obvious argument carry the mechanical half. A `README.md`
in every module directory carries the prose half, and this file is that half —
which is the honest answer to why a module of five repeated resource patterns is
documented at this length. See the
[code documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md).

## Why a key per data class rather than one

Alternatives Considered: One shared customer-managed key for the whole stack
is the obvious simplification, and on price it wins outright: KMS bills per key
per month plus per request, so one key is one monthly key charge instead of five.
It is rejected on a mechanism, not on a preference. One key has exactly one key
policy, so a single mistaken principal, a missing condition or a compromise of
that one key reaches all five data classes at once — the relational records, the
dataset objects and logs, the stored credentials, the queue payloads, and the
values the application enciphers itself. Five
keys give each data class its own policy and its own independent rotation, so the
same mistake is confined to one class: an over-broad grant added to the queue
key cannot decrypt database ciphertext, and a grant added to the Secrets Manager
key cannot read a dataset generation.

Refactoring Rationale: This section, and the counts throughout this README,
previously said **four**. The fifth key — application data — was added when the
card verification value acquired a real writer, and the reason it could not reuse
one of the four is mechanical rather than stylistic: each of the four carries a
`kms:ViaService` condition confining it to the one AWS service that reaches it, so
none of them can be called directly by a workload that needs one data key for one
column. The target architecture's own enumeration of four keys is read as naming
what the four managed-service data domains need, not as a prohibition on the key
the application layer needs; the alternative readings were widening one of the
four by removing its service condition, and leaving the column unenciphered, both
of which are larger departures. The full reasoning, including the rejected
seventeenth-module option, is recorded at the key itself in `main.tf`.

Trade-offs: The cost accepted for that boundary is five monthly key charges
instead of one, five key policies to review instead of one, and five key ARNs for a
calling root to wire instead of one. Naming the cost matters: a rationale that
reports only the benefit is not a rationale. The per-data-domain reasoning and
its full cost analysis — including why service-managed keys were rejected — are
owned by
[ADR-008 — security and identity](../../../docs/adr/ADR-008-security-and-identity.md)
and are not re-derived here.

## The measured source context

Every VSAM `FILE` resource the baseline defines in `app/csd/CARDDEMO.CSD:1-99`
declares `JOURNAL(NO)`, `RECOVERY(NONE)` and `FWDRECOVLOG(NO)`, with journal
activity switched off on each stanza. These are factual configuration properties
of a deliberately simple demonstration application, cited here because they are
what this module's existence answers.

Refactoring Rationale: The eight VSAM file resources express no recovery and
no journalling of file activity, and the tier has no encryption-at-rest
construct at all, so there is no baseline rotation behaviour for this module to
reproduce. That is precisely why rotation is an invariant of the module rather
than a caller preference: the target supplies both properties the replacement
data path needs — encryption at rest under a key this repository declares, and
automatic rotation of that key's material — where the tier being replaced
expressed neither. Without this citation, five rotating keys would read as an
unexplained addition; with it, they are a documented answer to a measured
starting point.

**Scope of that claim.** The statement above is scoped to the **VSAM tier** and
must not be read more widely, because the baseline is not uniform. The
authorization extension's Db2 tier does enable image copy: the index definition
`app/app-authorization-ims-db2-mq/ddl/XAUTHFRD.ddl` is four lines and line 4
reads `COPY YES;`. The wider identity and data-handling treatment is owned by
[security and identity](../../../docs/architecture/security-and-identity.md),
which carries the same reconciliation.

Nothing under `app/**` is changed by this module or by anything else in this
migration. The z/OS and AWS Mainframe Modernization deployment paths remain
exactly as they are — the migration adds a path, it does not remove one.

## What each key protects, and which module consumes it

| Key | Data it protects | Consuming sibling module(s) |
|---|---|---|
| Aurora | The relational record tier: account, customer, card, ledger, reference and authorization rows, the cluster's automated backups and its managed master-credential secret. The source contracts include exact money at `app/cpy/CVACT01Y.cpy:7` (`ACCT-CURR-BAL PIC S9(10)V99`), the card verification value at `app/cpy/CVACT02Y.cpy:7` (`CARD-CVV-CD PIC 9(03)`), which no endpoint returns, and the national and government identifiers at `app/cpy/CVCUS01Y.cpy:17-18` (`CUST-SSN PIC 9(09)` and `CUST-GOVT-ISSUED-ID PIC X(20)`), which are stored encrypted and returned masked. | `aurora-postgresql`, via its `storage_encrypted` cluster and `kms_key_id` |
| S3 | The ten dataset-generation families that replace the baseline generation data groups, the single-page application origin, the explicitly named CloudWatch log groups and the encrypted alert topic. **Not** the CloudFront access-log destination: that bucket's default encryption is SSE-S3 (`AES256`), because CloudFront standard log delivery cannot write to a bucket defaulted to SSE-KMS. This key's grant to the log-delivery service principal is still installed and still scoped to that exact delivery source, so the key policy stays narrow and a destination that can carry a key later needs no policy change — but it encrypts no CloudFront access-log object today. | `s3-datasets`, `cloudfront-spa`, and the log-group consumers `network`, `ecs-service`, `api-gateway-http`, `step-functions-batch`, `observability` |
| Secrets Manager | The generated database credential and the seed-user bootstrap values, all created at provisioning time rather than committed. This is the key that answers `app/cpy/CSUSR01Y.cpy:21`, where the baseline declares `SEC-USR-PWD PIC X(08)`, an eight-character password held in plain text: the target carries no password field forward at all. | `secrets`, `cognito` |
| SQS | Queue message payloads at rest, on every queue the `sqs` module creates and on every one of their dead-letter queues. The target messaging design names **five** queues -- authorization request and reply, inquiry request and reply, and the error sink -- each with a DLQ. The implemented module creates **six** pairs, because the one inquiry request queue is split at the ownership boundary; see the note below. | `sqs` |
| Application data | The values the migrated code enciphers itself, one data key per value, under an authenticated cipher applied in the workload. Today that is the card verification value at `app/cpy/CVACT02Y.cpy:7` (`CARD-CVV-CD PIC 9(03)`), which the baseline holds as three display digits in the clear and which `com.carddemo.card.service.CardVerificationValueCipher` stores as a self-describing envelope in `card.cards.cvv_encrypted`. This is the one key here a task role calls **directly**, so its policy carries an encryption-context condition where the other four carry `kms:ViaService`. | None — consumed by `infra/envs/dev` and `infra/envs/prod` directly, as the card task-role policy's resource and as the `CARDDEMO_SECURITY_CVV_KEY_ID` runtime parameter |

Assumptions: this module encrypts whatever the `sqs` module creates, which is one
queue pair more than the target messaging design names. That sixth pair is a
registered divergence rather than an oversight: both inquiry programs are driven
from one request queue in the baseline, but they answer different questions and
belong to different owners, so `COACCT01`'s account inquiry and `CODATE01`'s date
conversion are routed to separate request queues before either consumer receives a
message — two competing consumers on one queue cannot safely peek at a sibling's
message and put it back. The divergence and its rejected alternatives are owned by
[`docs/architecture/messaging-contracts.md`](../../../docs/architecture/messaging-contracts.md);
the split itself is owned by the `sqs` module.

Assumptions: Each consuming module receives the key ARN it needs from an
environment root, never by calling this module itself. That is what allows key
creation and key-policy application to be separate resources here: a consumer can
be created with the key ARN first, and its resulting exact resource identity can
then narrow the final key policy through an encryption-context input, without the
two modules forming a dependency cycle Terraform would refuse to graph.

## The policy model

Every one of the five key policies is composed from two kinds of statement.
Administration is reserved to the account-root principal. Cryptographic use is
granted separately and narrowly, qualified by exact role ARNs, by service
principal, by service path, and by resource-specific encryption context:

- Aurora use is bound to the cluster resource identifiers the environment root
  supplies.
- S3, CloudFront, CloudWatch Logs and SNS use is bound to exact bucket,
  distribution, log-delivery-source, log-group or topic ARNs.
- Secrets Manager use is bound to exact secret ARNs.
- SQS use is bound to exact same-account role ARNs and the regional queue
  service path.
- Application-data use is bound to exact same-account role ARNs and to the
  `carddemo:purpose` encryption-context values the caller declares. There is
  deliberately **no** `kms:ViaService` condition on this one: the key is called
  by a workload rather than through a service, so such a condition would deny
  every legitimate request, and the encryption context narrows the grant in its
  place.

Assumptions: Naming the account root as the administrative principal is a
deliberate dependency on two documented KMS behaviours rather than a default.
First, a key policy must leave an administrative path in place, or the key
becomes unmanageable by anyone; the account-root statement is also what permits
an operator running `terraform destroy` to schedule each key for deletion, so
clean teardown depends on it. Second, `resources = ["*"]` inside a key policy
scopes to *that key alone* and is not the account-wide wildcard the same
expression would mean in an identity policy — which is why the statement is not
an over-broad grant despite how it reads.

Assumptions: The five trusted-principal lists each default to an empty list
so the keys can be created before the task roles that use them exist. Those roles
come from the `ecs-service` module, which itself consumes these key ARNs, so
requiring a non-empty list would make the grant a precondition of the key the
grant depends on. An empty default installs no wildcard grant — it installs no
use grant at all — and no default in this module holds an ARN. The empty defaults
are load-bearing, not placeholders left unfinished.

Trade-offs: The key ARNs and key identifiers are published as ordinary
outputs and are not marked `sensitive`. They are resource identifiers, not key
material, and leaving them legible lets a reviewer read a plan and see which data
class is wired to which key — the single most useful thing a reviewer can check
here. The compromise accepted is that these identifiers appear in plan output and
in state; authorization is enforced by the key and IAM policies, never by
redacting an identifier.

## Calling the module

This directory is a called module, never a Terraform root. Both environment roots
consume it from source:

```hcl
module "kms" {
  source = "../../modules/kms"

  environment                 = var.environment
  cloudfront_distribution_arn = var.cloudfront_distribution_arn
}
```

Only `environment` and `cloudfront_distribution_arn` are required; every other
input has a default. The call sites are `infra/envs/dev/main.tf` and
`infra/envs/prod/main.tf`.

Three things are absent from this module by design, and each is the constraint a
contributor is most likely to breach:

- **No `backend` block.** A called module has no state of its own, and Terraform
  honours a backend only in a root module — one declared here would be reported
  as ignored configuration. State is configured per environment in
  `infra/envs/dev/backend.tf` and `infra/envs/prod/backend.tf`.
- **No `provider` block body.** The calling root owns provider configuration,
  including the region and the `default_tags` these keys inherit. A second
  configuration for the same provider inside a called module would stop the
  root's region and tags reaching these resources and would make Terraform reject
  `count`, `for_each` and `depends_on` on the module block itself. `versions.tf`
  therefore *requires* the AWS provider without *configuring* it.
- **No `module` call to a sibling.** This module calls nothing. It publishes key
  ARNs outward and receives narrowing context inward, which is what keeps the
  graph acyclic.

**This module is never applied directly.** `terraform validate` reaches it
transitively: CI initialises and validates `infra/bootstrap`, `infra/envs/dev`
and `infra/envs/prod` with `-backend=false`, and validating a root parses every
module it calls. Lint and the documentation drift check reach this directory
directly, because both run across `infra/modules/*`.

## What differs between `dev` and `prod`

The two environment roots are required to be identical in shape and to differ
only in sizing and retention values, so **both environments get the same five
keys with rotation enabled**. The one lever this module exposes to that
difference is `deletion_window_in_days`, set per environment in
`infra/envs/dev/terraform.tfvars` and `infra/envs/prod/terraform.tfvars`.

Trade-offs: The two ends of that window buy different things. A short window
lets `terraform destroy` release the keys sooner and stops a torn-down
environment leaving keys behind in a pending-deletion state that still bills; a
long window preserves more time in which a key deleted by mistake can be
recovered, because past its window a key is gone and every ciphertext under it is
permanently unreadable. A differing window does **not** make the two
environments topologically different — it is a retention value, which is exactly
the category the two roots are permitted to disagree on.

No `multi_region` argument is set on any key, so every key is single-Region.
Multi-Region and disaster-recovery topology are out of scope for this migration:
the target is one region with three availability zones, and a Multi-Region key
would provision a replica capability nothing consumes.

## Zero secrets, as a structural property

This module contains no key material, no secret value, no credential, no AWS
account identifier and no literal resource ARN — and that is a property of how it
is built, not a habit to be maintained:

- Account and partition identity are resolved at plan time from caller-identity,
  region and partition data sources, so the administrative principal is composed
  rather than written.
- Trusted principals and narrowing contexts arrive as typed, validated caller
  inputs.
- The key ARNs travel **outward as outputs**, never inward as source-committed
  inputs.

Assumptions: The reason no default carries a specimen ARN — not even as a
worked example of the expected shape — is that an ARN embeds an AWS account
identifier, and committing no secret to the repository is a non-negotiable
constraint of this project that admits no exception. The expected shape is
conveyed by each input's `type`, `description` and `validation` regex instead.
Alias *names* such as `alias/carddemo-aurora-dev` do appear, because an alias
name is architecture rather than a secret. See
[security and identity](../../../docs/architecture/security-and-identity.md).

## Validating this module

Run from the repository root:

```bash
# WHAT: check this module's HCL against canonical formatting without rewriting
#       a single byte of it.
# WHY : Trade-offs: `-check` reports drift and exits non-zero, whereas a bare
#       `terraform fmt` rewrites files in place -- which in CI would let a
#       formatting regression pass as green, because the command repaired the
#       tree and then succeeded.
terraform fmt -check -recursive infra/modules/kms

# WHAT: parse and type-check the module's configuration with no state backend
#       and no credentials.
# WHY : Assumptions: `validate` refuses to run in an uninitialised directory, so `init` must
#       precede it, and `-backend=false` is what lets it run offline -- this
#       directory has no backend of its own to configure. The GATING path is
#       the transitive one: CI validates the three roots, and validating a root
#       parses every module it calls.
terraform -chdir=infra/modules/kms init -backend=false -input=false
terraform -chdir=infra/modules/kms validate

# WHAT: run the HCL lint gate over this directory.
# WHY : Assumptions: the config is given as an absolute path because tflint resolves a
#       relative --config against the directory named by --chdir rather than
#       against the shell's working directory, so a relative path silently
#       finds no configuration and lints with default rules.
tflint --chdir=infra/modules/kms --config="$(pwd)/infra/.tflint.hcl"

# WHAT: compare the generated region of this README against the module's HCL,
#       writing nothing at all.
# WHY : Trade-offs: this is the drift gate in check-only mode. A stale README exits
#       non-zero and stays stale until a human resolves it deliberately; an
#       auto-fix step that regenerated the file and committed it back would
#       turn a review gate into a silent mutation, leaving the author unaware
#       that the contract they published was wrong.
terraform-docs --config infra/.terraform-docs.yml --output-check infra/modules/kms
```

### The gates that govern this module

All four run in
[`.github/workflows/infra-ci.yml`](../../../.github/workflows/infra-ci.yml) with
no `continue-on-error` and no tolerated return code — each one is **gating**:

| Gate | Command | Reaches this module |
|---|---|---|
| Formatting | `terraform fmt -check -recursive infra/` | Directly |
| Parse and type check | `terraform init -backend=false` then `terraform validate`, per root | Transitively, through both environment roots |
| HCL lint | `tflint --recursive --config infra/.tflint.hcl` | Directly |
| Documentation drift | `terraform-docs --config infra/.terraform-docs.yml --output-check` | Directly |

A severity-thresholded policy scan runs alongside them. The two findings it
raises against a KMS module are key rotation and over-broad key policy, and both
are **satisfied by construction rather than by suppression**: rotation is wired
to `enable_key_rotation` on all five keys and the input refuses any value but
`true`, and each policy grants use only to named principals under an encryption
context. This module carries no scanner suppression of any kind — no
`checkov:skip`, no `tflint-ignore`.

## Teardown

Trade-offs: No `prevent_destroy` lifecycle guard is set on any key. The
accepted risk is a key destroyed by an unintended `terraform destroy`. It is
accepted because a guard would make the project's own acceptance criterion —
that an environment tears down cleanly — unsatisfiable: the run would halt on the
guarded key and leave the rest of the environment half-removed, to be finished by
hand. Deletion is still not immediate, because the configured deletion window
schedules removal rather than erasing key material at once. The teardown
procedure is in
[the teardown runbook](../../../docs/runbooks/teardown.md).

## Generated Terraform reference

The block below is generated from this module's `.tf` files by terraform-docs
0.20.0 under [`infra/.terraform-docs.yml`](../../.terraform-docs.yml), and its
freshness is gated. It is the only inputs and outputs listing in this document;
no second table competes with it.

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_kms_alias.application](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_alias.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_alias.s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_alias.secrets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_alias.sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_alias) | resource |
| [aws_kms_key.application](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_kms_key.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_kms_key.s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_kms_key.secrets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_kms_key.sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key) | resource |
| [aws_kms_key_policy.application](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key_policy) | resource |
| [aws_kms_key_policy.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key_policy) | resource |
| [aws_kms_key_policy.s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key_policy) | resource |
| [aws_kms_key_policy.secrets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key_policy) | resource |
| [aws_kms_key_policy.sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/kms_key_policy) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.application](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.aurora](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.secrets](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.sqs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_cloudfront_distribution_arn"></a> [cloudfront\_distribution\_arn](#input\_cloudfront\_distribution\_arn) | ARN of the CloudFront distribution allowed to decrypt the SPA origin under the S3 data-domain key. Required so the service-principal grant is always scoped to the exact distribution rather than omitted or widened. | `string` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment name interpolated into all five KMS alias names, so one environment's keys are distinguishable from the other's in the console and in any alias-based key reference; must be `dev` or `prod`, the two environments that have a Terraform root under infra/envs/. | `string` | n/a | yes |
| <a name="input_application_encryption_context_purposes"></a> [application\_encryption\_context\_purposes](#input\_application\_encryption\_context\_purposes) | The kms:EncryptionContext:carddemo:purpose values the application-data key grant admits. This is the narrowing condition a directly-called key has in place of kms:ViaService, so a role holding the grant can work only with ciphertext produced for one of these purposes. The default names the two purposes the migration enciphers today: card-cvv, the card verification value produced by com.carddemo.card.service.CardVerificationValueCipher, and customer-identifier, the national and government-issued identifiers produced by com.carddemo.account.service.CustomerIdentifierCipher. | `list(string)` | <pre>[<br/>  "card-cvv",<br/>  "customer-identifier"<br/>]</pre> | no |
| <a name="input_application_key_user_role_arns"></a> [application\_key\_user\_role\_arns](#input\_application\_key\_user\_role\_arns) | Exact IAM role ARNs the application-data key policy permits to generate envelope data keys and decrypt them, confined to the encryption-context purposes declared below. Unlike the other four inputs of this shape there is no kms:ViaService condition on the resulting statement, because this key is called directly by a workload rather than through an integrated service. Wildcards, assumed-role session ARNs, users, roots and service principals are refused. | `list(string)` | `[]` | no |
| <a name="input_aurora_encryption_context_ids"></a> [aurora\_encryption\_context\_ids](#input\_aurora\_encryption\_context\_ids) | Aurora cluster resource identifiers accepted in the `aws:rds:db-id` KMS encryption context. A non-empty Aurora role trust list requires at least one exact identifier. | `list(string)` | `[]` | no |
| <a name="input_aurora_key_user_role_arns"></a> [aurora\_key\_user\_role\_arns](#input\_aurora\_key\_user\_role\_arns) | Exact IAM role ARNs the Aurora key policy permits to use the key through Amazon RDS for the named Aurora encryption contexts. Wildcards, assumed-role session ARNs, users, roots and service principals are refused. | `list(string)` | `[]` | no |
| <a name="input_cloudfront_distribution_arns"></a> [cloudfront\_distribution\_arns](#input\_cloudfront\_distribution\_arns) | Exact same-account CloudFront distribution ARNs allowed to decrypt the SSE-KMS SPA origin through an origin access control. Empty means no CloudFront service-principal grant is installed. | `list(string)` | `[]` | no |
| <a name="input_cloudwatch_log_delivery_source_arns"></a> [cloudwatch\_log\_delivery\_source\_arns](#input\_cloudwatch\_log\_delivery\_source\_arns) | Exact same-account CloudWatch Logs delivery-source ARNs allowed to generate data keys under this key for a CloudFront standard logging v2 destination. Scoping rather than exercise: the destination cloudfront-spa creates defaults to SSE-S3 because CloudFront delivery cannot write to an SSE-KMS bucket, so the grant is currently unexercised and exists so the policy stays narrow and a key-encrypted destination needs no policy change. Empty means no log-delivery service-principal grant is installed. | `list(string)` | `[]` | no |
| <a name="input_cloudwatch_log_group_arns"></a> [cloudwatch\_log\_group\_arns](#input\_cloudwatch\_log\_group\_arns) | Exact same-account CloudWatch log-group ARNs the regional Logs service may encrypt with the S3/data key. Empty installs no CloudWatch Logs service-principal grant. | `list(string)` | `[]` | no |
| <a name="input_deletion_window_in_days"></a> [deletion\_window\_in\_days](#input\_deletion\_window\_in\_days) | Days a destroyed key spends pending deletion before the service removes it and every ciphertext under it becomes permanently unreadable; the service accepts 7 through 30, and this is one of the retention values the dev and prod roots are permitted to set differently without changing the stack's shape. | `number` | `7` | no |
| <a name="input_enable_key_rotation"></a> [enable\_key\_rotation](#input\_enable\_key\_rotation) | Whether all five customer-managed keys rotate their key material automatically on the service's own interval. The module accepts only true because rotation is an architecture invariant rather than an environment preference. | `bool` | `true` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Prefix concatenated into each KMS alias name ahead of the key's purpose and the environment, giving the five keys one greppable identity shared with the rest of the stack's resource names; lowercase letters, digits and hyphens only, at most 32 characters, matching the characters an alias name accepts. | `string` | `"carddemo"` | no |
| <a name="input_s3_cloudfront_distribution_arns"></a> [s3\_cloudfront\_distribution\_arns](#input\_s3\_cloudfront\_distribution\_arns) | CloudFront distribution ARNs the S3 key's mandatory `cloudfront.amazonaws.com` decrypt grant is confined to; empty narrows the grant to every distribution in THIS account and partition, which is the tightest scope expressible without a dependency cycle. | `list(string)` | `[]` | no |
| <a name="input_s3_encryption_context_bucket_arns"></a> [s3\_encryption\_context\_bucket\_arns](#input\_s3\_encryption\_context\_bucket\_arns) | Exact S3 bucket ARNs accepted by the S3 key policy. The policy derives both bucket and object encryption-context forms so S3 Bucket Keys and direct object keys remain scoped to these buckets. | `list(string)` | `[]` | no |
| <a name="input_s3_key_user_role_arns"></a> [s3\_key\_user\_role\_arns](#input\_s3\_key\_user\_role\_arns) | Exact IAM role ARNs the S3 key policy permits to use the key through Amazon S3 for the named bucket encryption contexts. Wildcards, assumed-role session ARNs, users, roots and service principals are refused. | `list(string)` | `[]` | no |
| <a name="input_secrets_encryption_context_arns"></a> [secrets\_encryption\_context\_arns](#input\_secrets\_encryption\_context\_arns) | Exact Secrets Manager secret ARNs accepted in the `SecretARN` KMS encryption context. A non-empty Secrets Manager role trust list requires at least one exact secret ARN. | `list(string)` | `[]` | no |
| <a name="input_secrets_key_user_role_arns"></a> [secrets\_key\_user\_role\_arns](#input\_secrets\_key\_user\_role\_arns) | Exact IAM role ARNs the Secrets Manager key policy permits to use the key through Secrets Manager for the named secret encryption contexts. Wildcards, assumed-role session ARNs, users, roots and service principals are refused. | `list(string)` | `[]` | no |
| <a name="input_sns_topic_arns"></a> [sns\_topic\_arns](#input\_sns\_topic\_arns) | Exact same-account SNS topic ARNs that CloudWatch alarms and SNS may encrypt with the S3/data key. Empty installs no alert-topic service-principal grant. | `list(string)` | `[]` | no |
| <a name="input_sqs_key_user_role_arns"></a> [sqs\_key\_user\_role\_arns](#input\_sqs\_key\_user\_role\_arns) | Exact IAM role ARNs the SQS key policy permits to use the key through Amazon SQS. Wildcards, assumed-role session ARNs, users, roots and service principals are refused. | `list(string)` | `[]` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Key-specific tags merged onto each of the five keys, layered on top of the common tag set the calling root already applies through its provider's `default_tags`; defaults to none, because the baseline tags arrive from the root rather than from this module. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_application_key_alias_name"></a> [application\_key\_alias\_name](#output\_application\_key\_alias\_name) | Alias name this module assigns to the application-data key, carrying the module's name prefix and the environment. This is the value a task definition publishes as CARDDEMO\_SECURITY\_CVV\_KEY\_ID: an alias survives replacement of the key behind it, so a rotation does not require every task definition holding an identifier to be revised. |
| <a name="output_application_key_arn"></a> [application\_key\_arn](#output\_application\_key\_arn) | ARN of the customer-managed key the application generates envelope data keys from. A calling environment root names this in the resource element of the card workload's task-role policy, granting kms:GenerateDataKey* and kms:Decrypt on this key alone. |
| <a name="output_application_key_id"></a> [application\_key\_id](#output\_application\_key\_id) | Bare identifier -- not the ARN -- of the application-data key, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN. |
| <a name="output_aurora_key_alias_name"></a> [aurora\_key\_alias\_name](#output\_aurora\_key\_alias\_name) | Alias name this module assigns to the Aurora PostgreSQL key, carrying the module's name prefix and the environment so one environment's key is distinguishable from the other's. It remains valid if the key behind it is replaced, so a runbook step or a stored parameter that identifies the database's key should reference this rather than the identifier. |
| <a name="output_aurora_key_arn"></a> [aurora\_key\_arn](#output\_aurora\_key\_arn) | ARN of the customer-managed key that encrypts the Aurora PostgreSQL cluster at rest -- the account, customer, card, ledger, reference and authorization records, together with the cluster's automated backups and its managed master-credential secret. A calling environment root passes this into the aurora-postgresql module's `kms_key_arn` input: it is the key that module's `storage_encrypted` cluster is encrypted with, and the value its `kms_key_id`, master-credential-secret and Performance Insights arguments each take. |
| <a name="output_aurora_key_id"></a> [aurora\_key\_id](#output\_aurora\_key\_id) | Bare identifier -- not the ARN -- of the key that encrypts the Aurora PostgreSQL cluster, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN, and for naming this key unambiguously in an operator procedure. |
| <a name="output_s3_key_alias_name"></a> [s3\_key\_alias\_name](#output\_s3\_key\_alias\_name) | Alias name this module assigns to the S3 key, carrying the module's name prefix and the environment. It survives replacement of the key behind it, so it is the reference an operator procedure should use when recording which key a stored dataset generation was encrypted under. |
| <a name="output_s3_key_arn"></a> [s3\_key\_arn](#output\_s3\_key\_arn) | ARN of the customer-managed key for stored objects, CloudWatch log groups and the encrypted alert topic. A calling root passes it to s3-datasets and cloudfront-spa for bucket SSE-KMS, to network/ecs-service/api-gateway-http/step-functions-batch for log-group encryption, and to observability for its managed groups and SNS topic; the key policy admits only the regional logging and notification service paths in this account. |
| <a name="output_s3_key_id"></a> [s3\_key\_id](#output\_s3\_key\_id) | Bare identifier -- not the ARN -- of the key that encrypts the object, log and alert data class, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN. |
| <a name="output_s3_key_policy_id"></a> [s3\_key\_policy\_id](#output\_s3\_key\_policy\_id) | Provider identifier of the fully applied S3 key policy. The CloudFront logging v2 delivery consumes this as an ordering token ONLY -- it is not a key reference and it does not encrypt a log object, the delivery destination being SSE-S3 -- so that delivery is not enabled before the exact distribution and delivery-source grants on this key exist. |
| <a name="output_secrets_key_alias_name"></a> [secrets\_key\_alias\_name](#output\_secrets\_key\_alias\_name) | Alias name this module assigns to the Secrets Manager key, carrying the module's name prefix and the environment. It remains valid across replacement of the key behind it, so a credential-rotation or recovery procedure should identify the key by this name rather than by its identifier. |
| <a name="output_secrets_key_arn"></a> [secrets\_key\_arn](#output\_secrets\_key\_arn) | ARN of the customer-managed key that encrypts the Secrets Manager entries holding the generated database credential and the seed-user passwords -- values the stack generates at provisioning time rather than committing, which is the mechanism that lets no password field be carried into any target schema. A calling environment root passes this into the secrets module's `kms_key_arn` input and into the cognito module's `secrets_kms_key_arn` input, each of which sets it as the `kms_key_id` of the entries that module creates. |
| <a name="output_secrets_key_id"></a> [secrets\_key\_id](#output\_secrets\_key\_id) | Bare identifier -- not the ARN -- of the key that encrypts the stored credentials, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN. |
| <a name="output_sqs_key_alias_name"></a> [sqs\_key\_alias\_name](#output\_sqs\_key\_alias\_name) | Alias name this module assigns to the SQS key, carrying the module's name prefix and the environment. It survives replacement of the key behind it, so a procedure that inspects or redrives a queue should identify the key by this name rather than by its identifier. |
| <a name="output_sqs_key_arn"></a> [sqs\_key\_arn](#output\_sqs\_key\_arn) | ARN of the customer-managed key that encrypts queue message payloads at rest -- the authorization request and reply, the split account/date inquiry requests, the shared inquiry reply and the error sink. A calling environment root passes this into the sqs module's `kms_key_arn` input, which sets it on every queue that module creates. That is five request, reply and error queues per the target messaging design, plus a sixth because the single inquiry request queue is split at the ownership boundary into an account-inquiry and a date-conversion request queue -- a divergence registered in docs/architecture/messaging-contracts.md, not an extra key. Each of the six has its own dead-letter queue, and the key covers those too. |
| <a name="output_sqs_key_id"></a> [sqs\_key\_id](#output\_sqs\_key\_id) | Bare identifier -- not the ARN -- of the key that encrypts the queue payloads, for a consumer whose resource argument or IAM policy condition key is written against a key identifier rather than a full ARN. |
<!-- END_TF_DOCS -->

## Related documents

| Document | What it owns that this file does not |
|---|---|
| [`infra/` package guide](../../README.md) | Prerequisites, the directory layout, the full module index and the tree-wide validation commands |
| [ADR-008 — security and identity](../../../docs/adr/ADR-008-security-and-identity.md) | Decision D8: the per-data-domain key split, the rejection of service-managed keys, and the cost analysis behind both |
| [ADR-009 — IaC tool](../../../docs/adr/ADR-009-iac-tool.md) | Decision D9: why Terraform, and why `plan` and `destroy` are the reviewable artifacts this module is written against |
| [Security and identity](../../../docs/architecture/security-and-identity.md) | Encryption in transit and at rest across the stack, the identity treatment, the IAM boundaries, and the VSAM-versus-Db2 baseline reconciliation cited above |
| [Deploy runbook](../../../docs/runbooks/deploy.md) | The authoritative deploy command sequence, which this file does not duplicate |
| [Teardown runbook](../../../docs/runbooks/teardown.md) | The authoritative teardown sequence and the deletion-window consequences of removing these keys |
| [Code documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md) | The convention this document is written to, including the HCL split and the paired what-and-why comment idiom |
