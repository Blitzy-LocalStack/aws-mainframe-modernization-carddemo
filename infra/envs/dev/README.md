# Development Terraform environment

This root composes all sixteen infrastructure modules, the API Gateway VPC Link
security-group edge and the non-secret Parameter Store discovery inventory for
development. The source of truth is the sibling HCL plus the AAP's identical
topology rule; [`../../README.md`](../../README.md) and the
[deploy runbook](../../../docs/runbooks/deploy.md) own package-wide procedures.

No live environment is claimed here. The configuration is authored and
statically validated; a backend-enabled plan or apply requires an operator's
short-lived federated AWS session.

**How this root fails.** Five failure modes are reachable from the commands
below, and every one of them is a stop rather than a partial deployment: the
bootstrap backend is absent, the state lock is held, a saved plan proposes a
destroy nobody asked for, a module rejects a value against its own invariant, or
the generated reference has drifted from the HCL. Each is paired with its
response in [Failure handling](#failure-handling) at the end of this document.

Assumptions: the failure surface belongs in the opening rather than only at the
end, because an operator reads a header before running anything and the table
after something has already gone wrong. Naming the five here is what makes this
document's contract complete in one screen -- what it composes, what it is
derived from, what it takes, what an apply hands back, and what it refuses to
do.

## Parameters and expected result

| Parameter | Kind | Purpose |
|:---|:---|:---|
| `bucket` | backend config | Bootstrap state-bucket name |
| `region` | backend config | Bootstrap backend Region |
| `dynamodb_table` | backend config | Bootstrap lock-table name |
| `kms_key_id` | backend config | Bootstrap state CMK ARN |
| `terraform.tfvars` | tracked non-secret file | Development sizing, retention and protection values |
| deployment variables | runtime inputs | The twelve inputs `variables.tf` declares with no default, none of which appears in the tracked tfvars: `alarm_email_endpoints`, `alb_certificate_arn`, `cloudfront_acm_certificate_arn`, `cloudfront_aliases`, `cloudfront_api_connect_src_origins`, `github_oidc_provider_arn`, `github_repository`, `image_tag`, `internal_service_domain_name`, `mask_hmac_secret_arn`, `mask_hmac_secret_kms_key_arn` and `permissions_boundary_arn`. `image_digests` is the thirteenth: it defaults to `{}` here and is required in production, where the service module refuses a mutable tag |

Refactoring Rationale: the deployment-variables row read "Image digests, IAM
boundary, public DNS zone/domain names, the HMAC secret reference and glue Lambda
ARNs". Two of those five are inputs this root does not own, and both errors point
an operator at a value nobody is holding. There is no public-DNS-zone input at
all: `aws_route53_zone.internal_service` in `main.tf` is created here and is
**private**, scoped to this environment's VPC, and the only DNS names supplied
from outside are `internal_service_domain_name` and `cloudfront_aliases`, which
are names covered by the two certificate ARNs rather than a zone. Nor are the
operational Lambda ARNs supplied: this root **creates** all four -- quiesce,
resume, dataset retention and database admin -- and publishes their ARNs through
the `runtime_configuration` output, so a row listing them as runtime inputs
inverts the direction they travel. The row is therefore restated as a measurement
of `variables.tf`: the inputs with no default, enumerated rather than summarised,
so the next reader can compare the list against the file instead of trusting a
paraphrase.

Every input's type, default and full description is in the generated
[Inputs](#inputs) table below, rendered from [`variables.tf`](variables.tf); the
row above records only which of them an operator must supply, because that is the
part a table sorted by name does not make visible.

A successful apply publishes the API/SPA entry points, service and queue
identifiers, database connection facts and the canonical Parameter Store paths
listed in the generated [Outputs](#outputs) table. It publishes **no** certificate
identifier of any kind.

Refactoring Rationale: this said the apply publishes "automatically issued
edge/service certificates, the service-TLS secret identifier". Every part of that
was wrong, in two different ways. Nothing here issues a certificate: the two the
stack uses are operator-supplied INPUTS — `alb_certificate_arn` for the load
balancer listener and `cloudfront_acm_certificate_arn` for the distribution — so a
caller already holds both ARNs before the apply begins, and echoing an input back
as an output would only invite a reader to treat it as something this root minted.
Internal listener material is not issued here either: each image's
`config/docker/generate-listener-material.sh` mints that task's own key pair and
self-signed certificate at container start-up, which is why no service certificate
and no service-TLS secret exist to publish. Measured against `outputs.tf`, none of
the eighteen outputs carries a certificate in its value, and `runtime_configuration`
records the same withdrawal in its own description. An output summary is the first
thing a reader consults to learn what an apply hands back, so naming artifacts that
were never there sends them hunting the Outputs table for rows that do not exist.

## What this root provisions

This root is a composition, not a resource library. The sixteen modules under
[`../../modules`](../../modules) declare the stack; `main.tf` wires them together
and supplies the values `terraform.tfvars` and the twelve non-defaulted inputs
carry. The generated [Modules](#modules) and [Resources](#resources) tables below
are the authoritative inventory. What follows is what this root owns **because no
module owns it**, which is the part a reader cannot find by opening a module.

- **The Parameter Store discovery inventory.** `aws_ssm_parameter.runtime`,
  `aws_ssm_parameter.platform` and `aws_ssm_parameter.online_writes_enabled` are
  declared here and in no module. Assumptions: each publishes a composition whose
  parts cross module boundaries -- a database endpoint beside a queue URL beside a
  bucket name -- so no single module holds the whole of any of them. A module
  publishing its own fragment would leave every service reading several parameters
  and reassembling the composition itself, and would put the assembly rule in as
  many places as there are readers.
- **The four operational Lambda functions** -- quiesce, resume, dataset-generation
  retention and database admin -- with the shared execution role, its policies and
  the `aws_lambda_invocation.database_bootstrap` that applies the schema and role
  inventory. They span modules by nature: quiesce and resume act on a parameter
  this root publishes on behalf of a state machine the batch module declares.
- **The online-write bracket lease**, `aws_dynamodb_table.online_write_lease`.
  Assumptions: the lease makes the batch bracket an ownership record rather than a
  bare boolean -- the parameter reports whether writes are permitted, the lease
  item records which execution is entitled to change that.
- **The private service zone and record.** `aws_route53_zone.internal_service` is
  created here and is **private**, scoped to this environment's VPC. The only DNS
  names supplied from outside are `internal_service_domain_name` and
  `cloudfront_aliases`, which are names covered by the two operator-supplied
  certificate ARNs rather than a zone.
- **The five purpose secrets** enumerated under
  [No secrets by construction](#no-secrets-by-construction), each generated here
  through an `ephemeral` generator written with `secret_string_wo`.
- **The ECS execute-command log group**, the dataset-generation bucket
  notification, and the SPA publication role whose non-secret values `deploy.yml`
  reads back.
- **Three cross-module assertions** -- `terraform_data.batch_window_disjoint`,
  `terraform_data.cross_module_contracts` and
  `terraform_data.dataset_retention_path_ready` -- which fail a plan rather than an
  apply when two modules' values contradict each other.

The API Gateway VPC-link security-group edge is the one item in the opening
paragraph that is **not** a resource declared here: the root passes the network
module's load-balancer security group into the API Gateway module, and the edge is
the result of that wiring. Assumptions: it is named at the top because a reader
tracing how edge traffic reaches a private integration has to know which file
joined the two modules, and grepping either module for the other's name finds
nothing.

Not provisioned by this root or the other, and named here because an operator may
look for them: multi-region topology and disaster-recovery failover; blue-green
and canary deployment, since the ECS services are rolled instead; stream
platforms; an application cache tier; and database read replicas. All five are out
of scope for this migration, and reporting reads instead go to the writer through
read-only cross-schema views.

## Prerequisites

| Tool or artifact | Constraint | Why this value |
|:---|:---|:---|
| Terraform CLI | `required_version >= 1.15.0`, validated on 1.15.8 | The floor `versions.tf` declares; 1.15.8 is the release the static gates are run against |
| `hashicorp/aws` | `~> 6.56` | A 5.81.0-or-later provider is required to accept an Aurora capacity floor of **zero**, which this environment sets; `~> 6.56` clears that comfortably |
| `hashicorp/random` | `~> 3.9` | Generates the values the `secrets` and `cognito` modules write into Secrets Manager at apply time, which is the mechanism that keeps credentials out of source |
| `terraform-docs`, `tflint`, `checkov` | Versions pinned by CI | Only needed to reproduce the static gates locally; `.github/workflows/infra-ci.yml` owns them |
| `infra/lambda/dist/*.zip` | Built, not committed | Three archives, read by five `filebase64sha256` call sites in `main.tf`, so a plan or a validate resolves them before it resolves anything else |

```bash
# WHAT: build the three Lambda archives this root's function resources hash.
# WHY : Assumptions: this runs BEFORE any validate or plan, not as part of one.
#       `filebase64sha256` is evaluated during expression resolution, so an absent
#       archive fails the whole run with a path error rather than with a missing
#       resource -- and the archives are deliberately not committed, because a
#       committed zip hides reviewed source behind an opaque binary.
python3 infra/lambda/build_packages.py
```

**Note**: `infra/bootstrap` must be applied **once per AWS account, out of band**,
before this root can initialize against remote state at all.

Assumptions: `backend.tf` names the S3 bucket and the DynamoDB lock table that
bootstrap creates, so an `init` attempted before they exist has nothing to reach
and fails outright -- there is no fallback to local state and none should be
introduced. [`../../bootstrap/README.md`](../../bootstrap/README.md) owns that
root, and
[deploy.md Step 1](../../../docs/runbooks/deploy.md#step-1---bootstrap-the-remote-state-backend)
owns the sequence.

Assumptions: `.github/workflows/deploy.yml` **never** applies bootstrap, and the
omission is structural rather than an oversight. That workflow stores its own
state in the backend bootstrap creates, so a job that created the backend would
have to exist before its own state did.

Credentials are obtained by short-lived federated role assumption -- `<role-arn>`
in every documented invocation. No long-lived credential exists anywhere in this
repository and none may be created; the deployment path authenticates by OIDC and
holds no stored key.

## Files in this root

Seven files are authored here. There are no subdirectories.

| File | Owns |
|:---|:---|
| [`versions.tf`](versions.tf) | The Terraform and provider constraints, and this root's single `provider "aws"` with its `default_tags` |
| [`backend.tf`](backend.tf) | The partial S3 backend: the committed state key and encryption flag, and the record of the four values supplied at init |
| [`variables.tf`](variables.tf) | Every input, its type, its validation and its description -- the source the generated [Inputs](#inputs) table is rendered from |
| [`main.tf`](main.tf) | The sixteen module calls and the root-owned resources listed above |
| [`outputs.tf`](outputs.tf) | The eighteen outputs, rendered below as [Outputs](#outputs) |
| [`terraform.tfvars`](terraform.tfvars) | The development values on the five parameterization axes; tracked, and non-secret by construction |
| `README.md` | This document: the operator procedure, and the prose half of the HCL documentation analogue |

`.terraform.lock.hcl` is also tracked, and is the one file here that is **not
authored**. Assumptions: `terraform init` generates it from real registry
checksums, so it is only ever regenerated and never edited -- an invented checksum
fails every subsequent `init -lockfile=readonly`, and it is tracked precisely so
that provider resolution is reproducible rather than resolved afresh per machine.

## Bootstrap and initialization

`infra/bootstrap` must exist before backend-enabled initialization. The backend
is partial because its bucket and key contain account-resolved identifiers and
Terraform evaluates backends before input variables.

```bash
# WHAT: print the bootstrap root's outputs, which carry the four literal values
#       this root's partial backend needs.
# WHY : Assumptions: these are identifiers rather than credentials -- a bucket
#       name, a table name, a Region and a key ARN -- so reading them needs no
#       secret handling, and access to the resources they name is controlled by
#       IAM and the bucket and key policies instead.
terraform -chdir=infra/bootstrap output

# WHAT: initialize this root against the bootstrapped remote state, supplying the
#       four backend values on the command line.
# WHY : Assumptions: all four are supplied together and re-supplied on any fresh
#       checkout, because the resolved backend configuration is cached under
#       `.terraform/`, which the repository ignores and therefore never carries
#       between machines. The note below this block records why none of the four
#       is committed instead.
terraform -chdir=infra/envs/dev init \
  -backend-config="bucket=<state-bucket>" \
  -backend-config="region=<region>" \
  -backend-config="dynamodb_table=<lock-table>" \
  -backend-config="kms_key_id=<state-kms-key-arn>"
```

Alternatives Considered: committing a literal backend configuration or a
tfbackend file would store account-identifying values in source, while a
variable cannot be used because backend evaluation happens first.

## Plan and apply

```bash
# WHAT: create the review artifact from the tracked non-secret values.
# WHY : Assumptions: the artifact is named `dev.tfplan`, matching
#       [`infra/README.md`](../../README.md), because the repository ignore rules
#       match on the `.tfplan` SUFFIX. A bare `tfplan` has no suffix to match and
#       stays trackable: `git check-ignore -v infra/envs/dev/dev.tfplan` resolves,
#       while the same command on `infra/envs/dev/tfplan` returns nothing.
# WHY : Assumptions: a saved plan embeds the resolved value of every NON-EPHEMERAL
#       attribute the apply will set, and no generated credential is among them.
#       The five purpose secrets below are written through `secret_string_wo` from
#       `ephemeral` generators; `infra/modules/secrets` generates each service
#       database credential the same way; the Aurora master password is delegated
#       to the database service by `manage_master_user_password`, so no password
#       attribute exists in the configuration at all; and the Cognito seed-user
#       and app-client credentials are minted by that module's apply-time
#       bootstrap processes straight into Secrets Manager. What the plan DOES
#       carry is secret ARNs and names, the account identifier, every endpoint and
#       the whole resolved topology -- enough that a committed plan discloses the
#       shape and addressing of the deployment, which is why it is still removed
#       below and never staged.
# WHY : Refactoring Rationale: this said the plan embeds "the generated Aurora
#       master password and the Cognito seed-user passwords". It embeds neither,
#       and the error was not harmless in either direction: it tells an operator
#       that a reviewable artifact is a live credential store, which invites
#       either handling the plan as a secret and skipping the review this root
#       depends on, or discovering the claim is false and discounting the
#       genuine exposure the plan does carry.
terraform -chdir=infra/envs/dev plan -out=dev.tfplan -var-file=terraform.tfvars

# WHAT: apply exactly the reviewed artifact rather than replanning.
# WHY : Alternatives Considered: `terraform apply -var-file=terraform.tfvars` with
#       no saved plan. Rejected because it computes a second plan at apply time,
#       so the graph that was reviewed and the graph that executes are two
#       different objects; applying the artifact makes them one, and Terraform
#       refuses it outright if state or configuration moved underneath it.
terraform -chdir=infra/envs/dev apply dev.tfplan

# WHAT: remove the artifact once the apply completes.
# WHY : Trade-offs: an ignore rule reduces accidental staging and cannot defeat
#       `git add -f`, so deleting the plan is the primary local safeguard and the
#       naming convention above is the cheap backstop.
rm -f infra/envs/dev/dev.tfplan
```

`apply -auto-approve` is not the normal path because it replans instead of
executing the artifact that was reviewed.

## Teardown

[`docs/runbooks/teardown.md`](../../../docs/runbooks/teardown.md) is the authority
for decommissioning and carries the verification checks, the recovery procedures
and the residual inventory. This section is the environment-specific view of the
same sequence: what an operator runs against **this** root, and the one ordering
rule a run cannot recover from getting wrong.

```bash
# WHAT: produce a reviewable destroy plan for this root, then execute exactly that
#       plan.
# WHY : Assumptions: a destroy resolves root variables exactly as a create does, so
#       the twelve non-defaulted inputs in the Parameters table must be exported
#       first. Without them `plan -destroy` produces no plan at all and prompts
#       instead -- which an unattended run stalls on and an attended one answers
#       from memory.
# WHY : Alternatives Considered: `terraform destroy`, which plans and prompts in one
#       command. Rejected for the same reason `apply -auto-approve` is rejected
#       above, and the consequence is larger here: the graph that gets a yes at the
#       prompt is not an artifact anyone can re-read, and the one command that
#       cannot be partially undone is the one whose review most needs to be
#       inspectable. Terraform refuses a saved plan that has gone stale, so the
#       artifact is also the staleness check.
terraform -chdir=infra/envs/dev plan -destroy -out="<planfile>"
terraform -chdir=infra/envs/dev apply "<planfile>"
```

A clean run prints a `Destroy complete!` summary and exits 0. That says the run
finished, not that the root manages nothing --
[teardown.md Step 3](../../../docs/runbooks/teardown.md#step-3---destroy-the-environment-root)
carries the two checks that establish the latter, and notes that a fresh **ordinary**
plan is not one of them.

**Destroy this environment root first; destroy `infra/bootstrap` last, and only on
account decommission.**

Assumptions: a destroy reads the existing state to discover what there is to
destroy. Bootstrap owns the bucket that state lives in and the table that locks
it, so destroying bootstrap first removes the record every other destroy depends
on -- and the resources this root created then keep running in AWS with nothing
left that knows they exist. The order is not a preference; the reverse leaves no
route back.
[teardown.md Step 5](../../../docs/runbooks/teardown.md#step-5---destroy-the-bootstrap-last-and-only-on-account-decommission)
also establishes that bootstrap outlives an ordinary teardown entirely, because
both environment roots share it.

In `dev` the destroy completes in one pass: `deletion_protection` is `false` and
`skip_final_snapshot` is `true`, so nothing refuses deletion and no snapshot is
left behind to keep billing. Running the same command against `prod` while its
flags are set **fails**, and that failure is the flags working -- clearing them
there is a deliberate, separately reviewed step rather than something a teardown
does on the way past.

**Note**: one part of that production-only step is **not** skipped here.
Assumptions: if anybody is relying on a manual snapshot of this environment's
cluster, it depends on a key this destroy is about to remove, and that dependency
turns on whether a snapshot is being relied on rather than on which environment
this is. See
[teardown.md Step 2b](../../../docs/runbooks/teardown.md#step-2b---preserve-a-decryptable-recovery-point)
before destroying, not after.

**Note**: `force-unlock` and the inventory of what a destroy leaves behind are
owned by [teardown.md](../../../docs/runbooks/teardown.md) --
[Concurrency, interrupted operations and the state lock](../../../docs/runbooks/teardown.md#concurrency-interrupted-operations-and-the-state-lock)
and
[What destroy does not remove](../../../docs/runbooks/teardown.md#what-destroy-does-not-remove).
Trade-offs: pointing costs a reader one hop, and restating would cost every later
reader the risk of following whichever copy drifted. One recovery procedure
written down twice is how the two copies come to disagree, and a wrong
`force-unlock` corrupts state.

## Development posture

The module graph is identical to production. Only Aurora capacity/auto-pause,
ECS size/count, retention, CloudFront price class, and deletion/final-snapshot
protection differ. Development can scale Aurora to zero, runs one task per
online service, retains logs for seven days, uses the narrow edge class and
supports one-step teardown.

Those five axes are the closed set. The values the two roots pass on them are:

| Axis | Input | `dev` | `prod` |
|:---|:---|:---|:---|
| Aurora capacity and auto-pause | `aurora_min_capacity`, `aurora_max_capacity`, `aurora_seconds_until_auto_pause` | `0`, `4`, `300` | `2`, `32`, `300` |
| ECS task count and size | `ecs_desired_count`, `ecs_task_cpu`, `ecs_task_memory` | `1`, `512`, `1024` | `2`, `1024`, `2048` |
| Log retention | `log_retention_days` | `7` | `365` |
| Edge distribution reach | `cloudfront_price_class` | `PriceClass_100` | `PriceClass_All` |
| Deletion protection and final snapshot | `deletion_protection`, `skip_final_snapshot` | `false`, `true` | `true`, `false` |

Assumptions: the constraint exists so that this environment stays a valid
rehearsal for the other one. A difference in sizing changes what a deployment
costs and how much load it absorbs; a difference in **topology** changes what a
deployment *is*, and a `dev` apply then stops being evidence about `prod` at
exactly the moment that evidence is wanted. `vpc_cidr` is the clearest case: both
roots pass `10.1.0.0/16`, because an address space is topology rather than a size.

Trade-offs: because the two differ only in sizing, a validated `dev` apply is real
evidence about `prod` -- the same modules, the same graph, the same wiring. It is
not evidence about `prod`'s capacity behaviour, and it cannot be: the scale-to-zero
resume delay below is a `dev`-only characteristic, and no `dev` run exercises a
32-unit ceiling or a 365-day retention.

Trade-offs: a task replacement can briefly remove the only development
target, and a destroy has no final database snapshot. Those costs keep the
environment disposable while production retains redundancy and protection.

The narrowness is enforced from the module side as well, not merely observed here.
[`../../modules/network/variables.tf`](../../modules/network/variables.tf)
deliberately exposes no `single_nat_gateway` toggle, no per-endpoint enable flag
and no `create_*` or `enabled` module-level switch, so neither root has a lever
that would let it build a different shape.

### What makes this the development root

Aurora's capacity floor here is **zero**, so the cluster pauses when idle and the
first connection after a pause waits roughly fifteen seconds while it resumes. An
interactive developer notices that; a batch execution does not.

Assumptions: the rules governing a zero floor are owned and enforced by
[`../../modules/aurora-postgresql/variables.tf`](../../modules/aurora-postgresql/variables.tf),
not by this root. That module accepts capacity between 0 and 256 Aurora Capacity
Units in half-unit increments, requires the ceiling to be at least the floor, and
adds two rules that bite only while the floor is zero: the ceiling must be at
least 1, and an auto-pause interval must be set, within 300 to 86,400 seconds. A
value this root passes that breaks any of them is refused at plan time by the
module's own `validation`, which is why the tfvars above carry `4` and `300`
rather than a ceiling of zero or an absent interval.

The remaining development-only facts are the other four axes at their smallest
settings -- one task per service at the lowest CPU and memory pair, seven-day log
retention, the narrowest edge class -- plus deletion protection **off** and the
final snapshot **skipped**, which together are what make the one-pass teardown
above possible.

`.github/workflows/deploy.yml` applies this environment with no GitHub environment
approval gate, while `prod` sits behind one. Assumptions: the asymmetry is coherent
because it follows the same disposability the flags encode -- an environment that
scales to zero, keeps logs for a week and destroys in one pass is one whose
re-creation is cheap, so requiring a human decision per apply would buy protection
for something nothing is protecting.

## Static validation

```bash
# WHAT: resolve modules and providers without contacting the remote backend.
# WHY : Assumptions: `-backend=false` is what makes this runnable with no AWS
#       credentials and no bootstrapped state, which is the same form
#       .github/workflows/infra-ci.yml runs. Add `-lockfile=readonly` when the
#       intent is to verify the tracked `.terraform.lock.hcl` rather than to
#       update it.
terraform -chdir=infra/envs/dev init -backend=false

# WHAT: validate module interfaces and graph expressions.
# WHY : Trade-offs: validation resolves types, references and module contracts
#       without evaluating a data source or contacting AWS, so it catches a
#       mistyped input or a broken module call and cannot catch a value AWS
#       itself would refuse. Only a credentialed plan does that.
terraform -chdir=infra/envs/dev validate

# WHAT: run the shared infrastructure linter over this root.
# WHY : Assumptions: the config path is absolute -- `--chdir` moves TFLint's
#       working directory, so a relative `infra/.tflint.hcl` would resolve
#       against this root and not be found. One shared configuration is used so
#       both environment roots and all sixteen modules are linted by the same
#       rule set.
tflint --chdir=infra/envs/dev --config="$(pwd)/infra/.tflint.hcl"
```

`-backend=false` validates configuration only; it cannot produce a deployable
remote-state plan.

## No secrets by construction

The tracked tfvars file contains no credential. Database and seed-user
credentials are generated into Secrets Manager; the card-selector signing key, the
two internal-identity signing keys, the pagination cursor signing key and the
reporting artifact-identity key -- five in all -- are generated
by this root itself through `ephemeral` resources written with `secret_string_wo`,
so none of
those values is recorded in state or in a plan artifact; the
extract-transform-load masking key arrives only as a secret ARN; and deployment
uses short-lived role assumption. Parameter Store contains endpoints and
identifiers only.

Refactoring Rationale: this paragraph previously said "TLS and HMAC values arrive
only as secret ARNs", which was true of one HMAC key and not of the other. The
masking key does arrive as an ARN because its derived tags must stay stable across
extract loads that may predate this root. Every other key **is** generated here,
and deliberately: nothing outside this stack produces or consumes any of them, so
an operator-supplied value would have a tfvars file to be committed in for no
benefit.

Refactoring Rationale: this paragraph singled out a **messaging** HMAC key as the
generated counterpart to the supplied masking key, and explained that the two were
separate so that a job reading cardholder extracts could not compute production
queue group identities. That key is **withdrawn** — see the record in `main.tf`
above the card-selector resource — because nothing injected the one Spring
bean it keyed once specification §0.4.1.8 fixed the queue group and deduplication
identities as the literal card number and transaction identifier. The
supplied-versus-generated distinction the paragraph was drawing still holds and is
restated without it.

Refactoring Rationale: the withdrawal was recorded and the COUNT was not. This
section went on saying **six** and listing `messaging/hmac-key` as a live secret
with a holder, while the withdrawal note in `main.tf` records that the `ephemeral`
generator, the secret and its write-only version went together with the injection
— so five secrets were provisioned against six described. That is the most
expensive shape this drift takes rather than a cosmetic one: a rotation review
plans for a key that does not exist and reports one it cannot find, and a reader
sizing the blast radius of a disclosure counts a holder there is no way to reach.
Both counts and the table row are corrected below, and the count is now asserted
by the "Verify hand-written Terraform prose counts against the declarations" gate
in `.github/workflows/infra-ci.yml`, which reads the `aws_secretsmanager_secret`
declarations in this root's `main.tf` — the measurement the prose claims to be.

### The five generated secrets are five, not one

Each of the generated values above exists for a different holder, and the
separation is what bounds the damage a single disclosure does. The Held-by column
is the set `infra/modules/ecs-service` asserts **biconditionally**, so a root that
hands a key to a workload outside its set — or withholds it from one inside — fails
at `terraform plan` rather than at run time:

| Secret | Held by | What holding it permits |
| --- | --- | --- |
| `card/selector-signing-key` | card only | minting or opening the opaque selector every single-card route addresses its row by, so a holder can address a card row it was never listed |
| `internal-identity/authorization-signing-key` | authorization **and** account | minting or verifying the bearer token the AUTHORIZATION service presents to every internal account-context read the chain in `InternalApiSecurityConfig.internalPaths()` claims. Authorization signs with it, account verifies it. A cell describing what a leaked key unlocks is the one place a count must not be short, so it names the enumerating method rather than a number |
| `internal-identity/transaction-signing-key` | transaction **and** account | the same capability for the TRANSACTION service's account-context client. Transaction signs with it, account verifies it. Splitting the two callers is what lets account attribute a token to a caller it can actually verify: under one shared key either caller could mint a token carrying the other's subject |
| `pagination/cursor-signing-key` | auth, account, card, transaction, reference, reporting **and** authorization | sealing and opening a keyset page boundary, so a holder can forge a cursor and page into rows no query scoped to it |
| `reporting/artifact-hmac-key` | reporting only | recomputing the token a stored statement's object key is named under, so a holder can locate a named cardholder's statement objects by listing a prefix — with no read on the object itself, because the disclosure is carried by the key rather than the content |

Refactoring Rationale: this section has now been short three times, and the reason
it drifted every time is that the count was written as prose. It first named
**three** secrets and listed an "Internal TLS pair" held by every online service;
the count was short by two (`pagination/cursor-signing-key` and
`card/selector-signing-key` were both generated here and neither appeared) and the
TLS row named a secret this root does not create at all, because each image's
`config/docker/generate-listener-material.sh` mints that task's own key pair and
self-signed certificate at start-up. It was then corrected to **five**, which went
stale in turn when the single `internal-identity/signing-key` was split into the two
pairwise keys above. Two rows in the table had drifted with it: the
internal-identity row still described one key shared by three services, and the
pagination row named five holders where the gate admits seven — it had been widened
to include auth and card, both of which construct a `CursorToken` unconditionally
and crash-loop without the key. The correction to **six** was then overtaken by the
withdrawal of `messaging/hmac-key`, and an ADDED entry and a REMOVED one do not
drift alike: an addition leaves a resource with no row, which a reader of `main.tf`
notices, while a removal leaves a row with no resource, which reads as
corroboration of a secret nobody can find. The count is therefore no longer only
stated as a measurement — five `aws_secretsmanager_secret` resources in this root's
`main.tf`, each with an `ephemeral` generator, and each table row's holder set
copied from the gate in `local.secret_sources_by_workload` rather than described —
it is ASSERTED as one: the prose-count gate in
`.github/workflows/infra-ci.yml` reads those declarations and fails the build when
this number disagrees with them, and the holder sets are the ones
`infra/modules/ecs-service` asserts biconditionally, so a drifted set fails at
`terraform plan`.

Three of the five are deliberately held by more than one workload, and each is
shared for its own reason. The two internal-identity keys are **pairwise**: they are
symmetric, so a signer and its verifier must hold the same bytes, and the pairing is
the narrowest sharing that can work — authorization signs with one and transaction
with the other, while account, as the verifier of both, is the only workload holding
two. `pagination/cursor-signing-key` is symmetric in the same way but genuinely
service-wide, because any of the seven services that seals a page boundary may be
asked to open one.

The pairwise split is what makes rotation independent, and that is the point of it:
re-issuing one caller's key stalls that caller alone, where re-issuing the former
shared key stalled both signers at once. Rotating either internal-identity key is
still **coordinated across its own pair** — its signer and account must be redeployed
together, because account rejects a token signed under a key it has not yet received
and the signer cannot sign under a key it has already lost. Rotating one pair does
not require touching the other.

Rotating any of the five is an **attended** procedure that redeploys its holders
together, documented in [`docs/runbooks/deploy.md`](../../../docs/runbooks/deploy.md).
Automatic rotation is deliberately not configured for any of them, and each
resource carries its own recorded reason: changing either internal-identity value
while its signer still holds the previous one refuses that caller's internal
account-context reads with a 401, which stalls the authorization consumer rather
than degrading it; a rotated cursor key refuses every page boundary a client
currently holds; a rotated selector key makes every single-card route reachable
from a list already on screen stop resolving; and a rotated artifact key orphans
every statement object already published, because nothing recomputes the name it
was written under.

## Generated Terraform reference

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |
| <a name="requirement_random"></a> [random](#requirement\_random) | ~> 3.9 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |
| <a name="provider_terraform"></a> [terraform](#provider\_terraform) | n/a |

### Modules

| Name | Source | Version |
|------|--------|---------|
| <a name="module_alb"></a> [alb](#module\_alb) | ../../modules/alb | n/a |
| <a name="module_api_gateway"></a> [api\_gateway](#module\_api\_gateway) | ../../modules/api-gateway-http | n/a |
| <a name="module_aurora"></a> [aurora](#module\_aurora) | ../../modules/aurora-postgresql | n/a |
| <a name="module_cloudfront_spa"></a> [cloudfront\_spa](#module\_cloudfront\_spa) | ../../modules/cloudfront-spa | n/a |
| <a name="module_cognito"></a> [cognito](#module\_cognito) | ../../modules/cognito | n/a |
| <a name="module_ecr"></a> [ecr](#module\_ecr) | ../../modules/ecr | n/a |
| <a name="module_ecs_cluster"></a> [ecs\_cluster](#module\_ecs\_cluster) | ../../modules/ecs-cluster | n/a |
| <a name="module_ecs_service"></a> [ecs\_service](#module\_ecs\_service) | ../../modules/ecs-service | n/a |
| <a name="module_eventbridge_scheduler"></a> [eventbridge\_scheduler](#module\_eventbridge\_scheduler) | ../../modules/eventbridge-scheduler | n/a |
| <a name="module_kms"></a> [kms](#module\_kms) | ../../modules/kms | n/a |
| <a name="module_network"></a> [network](#module\_network) | ../../modules/network | n/a |
| <a name="module_observability"></a> [observability](#module\_observability) | ../../modules/observability | n/a |
| <a name="module_s3_datasets"></a> [s3\_datasets](#module\_s3\_datasets) | ../../modules/s3-datasets | n/a |
| <a name="module_secrets"></a> [secrets](#module\_secrets) | ../../modules/secrets | n/a |
| <a name="module_sqs"></a> [sqs](#module\_sqs) | ../../modules/sqs | n/a |
| <a name="module_step_functions"></a> [step\_functions](#module\_step\_functions) | ../../modules/step-functions-batch | n/a |

### Resources

| Name | Type |
|------|------|
| [aws_cloudwatch_log_group.ecs_execute_command](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_group) | resource |
| [aws_dynamodb_table.online_write_lease](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/dynamodb_table) | resource |
| [aws_iam_role.lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role.spa_publication](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role_policy.dataset_retention_s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.lambda_xray](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.online_write_reconcile](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_iam_role_policy.spa_publication](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_lambda_function.database_admin](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function.dataset_retention](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function.quiesce](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function.resume](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function) | resource |
| [aws_lambda_function_event_invoke_config.dataset_retention](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_function_event_invoke_config) | resource |
| [aws_lambda_invocation.database_bootstrap](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_invocation) | resource |
| [aws_lambda_permission.dataset_retention_from_s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lambda_permission) | resource |
| [aws_route53_record.internal_service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/route53_record) | resource |
| [aws_route53_zone.internal_service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/route53_zone) | resource |
| [aws_s3_bucket_notification.dataset_generations](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_notification) | resource |
| [aws_secretsmanager_secret.card_selector](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.internal_identity_authorization](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.internal_identity_transaction](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.pagination_cursor](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.reporting_artifact](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret_version.card_selector](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.internal_identity_authorization](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.internal_identity_transaction](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.pagination_cursor](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_secretsmanager_secret_version.reporting_artifact](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret_version) | resource |
| [aws_ssm_parameter.online_writes_enabled](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [aws_ssm_parameter.platform](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [aws_ssm_parameter.runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/ssm_parameter) | resource |
| [terraform_data.batch_window_disjoint](https://registry.terraform.io/providers/hashicorp/terraform/latest/docs/resources/data) | resource |
| [terraform_data.cross_module_contracts](https://registry.terraform.io/providers/hashicorp/terraform/latest/docs/resources/data) | resource |
| [terraform_data.dataset_retention_path_ready](https://registry.terraform.io/providers/hashicorp/terraform/latest/docs/resources/data) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.account_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.auth_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.authorization_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.batch_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.card_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.data_migration_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.database_admin_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.dataset_retention_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.dataset_retention_s3](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.lambda_assume_role](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.lambda_logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.lambda_xray](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.online_write_lambda](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.online_write_reconcile](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.reporting_runtime](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.spa_publication](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.spa_publication_assume_role](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_alarm_email_endpoints"></a> [alarm\_email\_endpoints](#input\_alarm\_email\_endpoints) | Email addresses subscribed to the environment observability topic. REQUIRED and deliberately absent from terraform.tfvars: supply it out of band, because the repository carries no operator identity. At least one address must be given, so no environment creates alarms that notify nobody. | `list(string)` | n/a | yes |
| <a name="input_alb_certificate_arn"></a> [alb\_certificate\_arn](#input\_alb\_certificate\_arn) | Regional ACM certificate ARN presented by the internal ALB HTTPS listener. Forwarded to the alb module; the certificate must be issued in this environment's region and cover internal\_service\_domain\_name. | `string` | n/a | yes |
| <a name="input_cloudfront_acm_certificate_arn"></a> [cloudfront\_acm\_certificate\_arn](#input\_cloudfront\_acm\_certificate\_arn) | ACM certificate ARN issued in us-east-1 for the SPA distribution. Forwarded to cloudfront-spa and required to cover every entry in cloudfront\_aliases. | `string` | n/a | yes |
| <a name="input_cloudfront_aliases"></a> [cloudfront\_aliases](#input\_cloudfront\_aliases) | Non-empty list of bare DNS names the SPA distribution serves. Every entry must be covered by cloudfront\_acm\_certificate\_arn and is forwarded unchanged to cloudfront-spa. | `list(string)` | n/a | yes |
| <a name="input_cloudfront_api_connect_src_origins"></a> [cloudfront\_api\_connect\_src\_origins](#input\_cloudfront\_api\_connect\_src\_origins) | Origins the SPA is permitted to reach with fetch or XHR, forwarded unchanged to cloudfront-spa as api\_connect\_src\_origins. Scheme and host only, no path and no trailing slash; normally the single API Gateway origin the SPA was built against. Supply it as TF\_VAR\_cloudfront\_api\_connect\_src\_origins, never in terraform.tfvars, so it tracks the deployed endpoint. | `list(string)` | n/a | yes |
| <a name="input_github_oidc_provider_arn"></a> [github\_oidc\_provider\_arn](#input\_github\_oidc\_provider\_arn) | ARN of the account-scoped GitHub Actions OIDC provider created by infra/bootstrap. | `string` | n/a | yes |
| <a name="input_github_repository"></a> [github\_repository](#input\_github\_repository) | GitHub repository in owner/name form whose protected dev environment may assume the SPA publication role. | `string` | n/a | yes |
| <a name="input_image_tag"></a> [image\_tag](#input\_image\_tag) | Immutable image tag applied to the ten deployable ECR repositories this deployment builds, normally the source commit SHA supplied by the OIDC deployment workflow. The mirrored telemetry collector is not one of them: it is a cached third-party image and carries its own upstream version tag. | `string` | n/a | yes |
| <a name="input_internal_service_domain_name"></a> [internal\_service\_domain\_name](#input\_internal\_service\_domain\_name) | Bare DNS name covered by alb\_certificate\_arn. Forwarded to the alb module as its certificate identity and to api-gateway-http as the private integration server name to verify. | `string` | n/a | yes |
| <a name="input_mask_hmac_secret_arn"></a> [mask\_hmac\_secret\_arn](#input\_mask\_hmac\_secret\_arn) | Secrets Manager ARN of the environment-separated HMAC key the data-migration image uses for protected-field fingerprints. Supplied by the operator; never created or rotated by this configuration. The secret VALUE must be canonical standard base64 decoding to at least 32 bytes, which the image enforces by refusing to run on weaker material; docs/runbooks/deploy.md gives the creation command. | `string` | n/a | yes |
| <a name="input_mask_hmac_secret_kms_key_arn"></a> [mask\_hmac\_secret\_kms\_key\_arn](#input\_mask\_hmac\_secret\_kms\_key\_arn) | ARN of the customer-managed KMS key that encrypts mask\_hmac\_secret\_arn. Supplied by the operator alongside the secret; never created here. The data-migration task role is granted kms:Decrypt on exactly this key, through Secrets Manager, so a secret protected by a different key fails to decrypt rather than succeeding through a wider key policy. | `string` | n/a | yes |
| <a name="input_permissions_boundary_arn"></a> [permissions\_boundary\_arn](#input\_permissions\_boundary\_arn) | ARN of the same-account customer-managed IAM policy used as the permissions boundary on every role this deployment creates -- all nine aws\_iam\_role resources under infra/, which is ten effective role instances per environment plus the two per ECS service. Passed into every module that creates a role, each of which asserts the ARN belongs to this account. Supplied by the operator or the deploy workflow; never created here. | `string` | n/a | yes |
| <a name="input_aurora_backup_retention_period"></a> [aurora\_backup\_retention\_period](#input\_aurora\_backup\_retention\_period) | Days of automated backups the cluster retains, forwarded to the database module. Aurora does not permit automated backups to be switched off, so the module accepts 1 to 35 and there is no value here meaning `none`. Set to the same value production uses: specification section 0.4.1.6's only retention axis is log retention days, so backup retention is not an axis the two roots may differ on. | `number` | `35` | no |
| <a name="input_aurora_engine_version"></a> [aurora\_engine\_version](#input\_aurora\_engine\_version) | Aurora PostgreSQL engine version for this environment's cluster, forwarded to the database module, which requires the value and supplies no default of its own. Must be a numeric version such as "16.8", not an engine name or a parameter-group family. Defaults to the reviewed long-term-support pin this root's terraform.tfvars sets, so an omitted tfvars cannot select an unsupported release. While aurora\_min\_capacity is 0, the release named here must be one that supports scaling to zero capacity. | `string` | `"16.8"` | no |
| <a name="input_aurora_max_capacity"></a> [aurora\_max\_capacity](#input\_aurora\_max\_capacity) | Ceiling of the cluster's capacity range, in Aurora Capacity Units, forwarded to the database module which requires it. It bounds what a runaway query or an unexpectedly large batch run can cost, which is why a development environment sets it low rather than leaving headroom it will never use. The module owns the range, the granularity and the rule relating this to the floor. | `number` | `4` | no |
| <a name="input_aurora_min_capacity"></a> [aurora\_min\_capacity](#input\_aurora\_min\_capacity) | Floor of the cluster's capacity range, in Aurora Capacity Units, forwarded to the database module which requires it. A floor of 0 lets the cluster pause when idle and is the single largest cost difference between this environment and production; the price is a resume delay on the first connection after a pause. The module owns the full range, granularity and zero-floor rules. | `number` | `0` | no |
| <a name="input_aurora_parameter_group_family"></a> [aurora\_parameter\_group\_family](#input\_aurora\_parameter\_group\_family) | Aurora PostgreSQL cluster parameter-group family matching aurora\_engine\_version. | `string` | `"aurora-postgresql16"` | no |
| <a name="input_aurora_preferred_backup_window"></a> [aurora\_preferred\_backup\_window](#input\_aurora\_preferred\_backup\_window) | Daily UTC window in which automated backups are taken, forwarded to the database module. Must be of the form `hh:mm-hh:mm` with no day-of-week prefix, and must not overlap the window batch\_schedule\_expression starts the nightly chain in: a backup running against the cluster while the posting and interest jobs are writing to it competes with them for the same capacity, and this environment's capacity ceiling is deliberately low. | `string` | `"07:00-08:00"` | no |
| <a name="input_aurora_preferred_maintenance_window"></a> [aurora\_preferred\_maintenance\_window](#input\_aurora\_preferred\_maintenance\_window) | Weekly UTC maintenance window for Aurora, kept outside the nightly batch and backup windows. | `string` | `"sun:09:00-sun:10:00"` | no |
| <a name="input_aurora_seconds_until_auto_pause"></a> [aurora\_seconds\_until\_auto\_pause](#input\_aurora\_seconds\_until\_auto\_pause) | Idle interval, in seconds, before a cluster whose capacity floor is zero pauses. It has an effect only while that floor is zero, and the database module requires it to be set in exactly that case, so it is declared in both environment roots for interface symmetry and is inert in the root whose floor is above zero. The module owns the accepted range. | `number` | `300` | no |
| <a name="input_aws_region"></a> [aws\_region](#input\_aws\_region) | AWS region this environment is provisioned into. Read by `provider "aws"` in versions.tf, so all sixteen modules main.tf calls inherit it instead of configuring a region of their own. Accepts a standard region identifier such as us-east-1 or ap-southeast-4. | `string` | `"us-east-1"` | no |
| <a name="input_batch_schedule_expression"></a> [batch\_schedule\_expression](#input\_batch\_schedule\_expression) | Schedule on which the nightly batch chain is started, forwarded to the scheduler module as the trigger for the batch state machine. Must be a `cron(...)` expression; the `rate(...)` and `at(...)` forms are not accepted by that module. Must not overlap aurora\_preferred\_backup\_window, because both act on the same database cluster. | `string` | `"cron(0 2 * * ? *)"` | no |
| <a name="input_batch_schedule_maximum_event_age_seconds"></a> [batch\_schedule\_maximum\_event\_age\_seconds](#input\_batch\_schedule\_maximum\_event\_age\_seconds) | Outer bound, in seconds, on how long a failed delivery of the nightly batch trigger may keep being retried before it is sent to the dead-letter queue. Narrows the scheduler module's 86400 default so that a retried delivery cannot start the chain outside its intended window; must keep the start window clear of aurora\_preferred\_backup\_window and aurora\_preferred\_maintenance\_window, which terraform\_data.batch\_window\_disjoint asserts. | `number` | `3600` | no |
| <a name="input_cloudfront_price_class"></a> [cloudfront\_price\_class](#input\_cloudfront\_price\_class) | Edge locations the browser application's distribution is served from, forwarded to the content distribution module. Must be one of PriceClass\_100, PriceClass\_200 or PriceClass\_All. A narrower class serves fewer regions at lower cost; it changes latency for distant users and nothing else about how the distribution behaves. | `string` | `"PriceClass_100"` | no |
| <a name="input_deletion_protection"></a> [deletion\_protection](#input\_deletion\_protection) | Whether the stateful resources in this environment refuse deletion until the flag is cleared. Forwarded to both resources that offer the protection -- the database cluster and the user pool -- so one value governs the environment's whole teardown posture. | `bool` | `false` | no |
| <a name="input_ecs_desired_count"></a> [ecs\_desired\_count](#input\_ecs\_desired\_count) | Tasks each service runs. This is the input that decides whether a service survives losing one task, and it is the clearest single sizing difference between this environment and production. Forwarded to all eight services, so the value multiplies by eight across the stack. The service module cross-checks it against its autoscaling floor, so main.tf must keep that floor no higher than this value -- see the note below. | `number` | `1` | no |
| <a name="input_ecs_task_cpu"></a> [ecs\_task\_cpu](#input\_ecs\_task\_cpu) | CPU units allocated to each service task, where 1024 units is one virtual CPU. Forwarded to every service this root creates, so all eight are sized alike within an environment. Fargate accepts only certain memory values for a given CPU size, so this value and ecs\_task\_memory are not independently free -- see ecs\_task\_memory. | `number` | `512` | no |
| <a name="input_ecs_task_memory"></a> [ecs\_task\_memory](#input\_ecs\_task\_memory) | Memory in mebibytes allocated to each service task, forwarded to every service this root creates alongside ecs\_task\_cpu. Fargate accepts only certain memory values for a given CPU size, so this input is validated against ecs\_task\_cpu rather than on its own; the two must be changed together. | `number` | `1024` | no |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment identity forwarded to all sixteen modules, where it is interpolated into resource names, name tags, log group names and composed secret names so that this environment's resources are distinguishable from the other's. Pinned to `dev`, the environment whose state this Terraform root owns; infra/envs/prod declares the same input pinned to `prod`. | `string` | `"dev"` | no |
| <a name="input_image_digests"></a> [image\_digests](#input\_image\_digests) | Immutable sha256 digests keyed by ECR artifact name, for example { "auth-service" = "sha256:<64 hex>" }. Any artifact named here is deployed by digest instead of by image\_tag. Required in production, where the ECS service module refuses a mutable tag. | `map(string)` | `{}` | no |
| <a name="input_log_retention_days"></a> [log\_retention\_days](#input\_log\_retention\_days) | Days that log groups and log-derived object lifecycles in this environment retain data. Forwarded to the service, API gateway, batch, observability and content distribution modules, so one value governs the whole environment's retention rather than each module carrying its own. Must be one of the periods the log service accepts, and must be greater than zero because one consumer reads it as an object lifecycle expiry. | `number` | `7` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Prefix concatenated into the name of every resource this root creates, ahead of the component and the environment, giving the whole stack one greppable identity. Lowercase letters, digits and hyphens only, beginning and ending with a letter or digit, at most 32 characters. | `string` | `"carddemo"` | no |
| <a name="input_secret_recovery_window_in_days"></a> [secret\_recovery\_window\_in\_days](#input\_secret\_recovery\_window\_in\_days) | Secrets Manager recovery window, in days, applied to every secret this deployment generates: the five purpose secrets this root creates -- card-selector, the two pairwise internal-identity keys, pagination-cursor and reporting-artifact -- plus the per-service database credentials from the secrets module and the Cognito seed-user secrets. Set to the same value production uses, because a recovery window is not one of the axes specification section 0.4.1.6 permits the two roots to differ on. | `number` | `30` | no |
| <a name="input_skip_final_snapshot"></a> [skip\_final\_snapshot](#input\_skip\_final\_snapshot) | Whether destroying the database cluster skips taking a final snapshot first. Skipping makes the destroy fast and complete; taking one leaves a snapshot that survives the cluster and continues to bill until it is deleted by hand. | `bool` | `true` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Common tag set merged into every taggable resource in this root, and in every module it calls, through the `default_tags` block in versions.tf. Values must be non-secret: tags are visible to any principal that can describe the resource and appear in cost-allocation exports. | `map(string)` | <pre>{<br/>  "Environment": "dev",<br/>  "ManagedBy": "terraform",<br/>  "Project": "carddemo"<br/>}</pre> | no |
| <a name="input_vpc_cidr"></a> [vpc\_cidr](#input\_vpc\_cidr) | IPv4 address space this environment's VPC is created with, and the block the network module carves all nine subnets from -- three public, three private-application and three isolated-data. It is deliberately the SAME value production uses: an address space is topology, and specification section 0.4.1.6 permits the two roots to differ only on a closed set of sizing and retention axes that does not include it. The network module additionally requires a prefix length between /16 and /20 inclusive, the range in which nine usably sized subnets fit; this root narrows that to exactly /16 for the reason its second validation records. | `string` | `"10.1.0.0/16"` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_api_gateway"></a> [api\_gateway](#output\_api\_gateway) | Complete HTTP API endpoint, identifier, stage, authorizer and VPC-link contract. |
| <a name="output_batch_orchestration"></a> [batch\_orchestration](#output\_batch\_orchestration) | Complete daily, ad-hoc report, dataset round-trip and authorization-extract state-machine contract: the four machines, their per-machine execution roles, their log groups, the two bracket-release rules with their dead-letter queue and alarms, the resolved dataset staging root, and one deprecated dataset\_source\_extract\_prefix alias the module retains for a compatibility window. |
| <a name="output_batch_schedule"></a> [batch\_schedule](#output\_batch\_schedule) | Complete EventBridge Scheduler schedule, group and execution-role contract. |
| <a name="output_database"></a> [database](#output\_database) | Complete Aurora endpoint, identifier, subnet, security-group and RDS-managed master-secret handle contract. |
| <a name="output_datasets"></a> [datasets](#output\_datasets) | Complete versioned dataset-bucket, prefix and generation-retention contract. |
| <a name="output_ecs_cluster"></a> [ecs\_cluster](#output\_ecs\_cluster) | Complete ECS cluster and capacity-provider contract. |
| <a name="output_ecs_workloads"></a> [ecs\_workloads](#output\_ecs\_workloads) | Complete ecs-service output object per online service, batch task and data-migration task. |
| <a name="output_encryption"></a> [encryption](#output\_encryption) | Complete KMS module output object for the Aurora, S3/log, Secrets Manager and SQS keys. |
| <a name="output_identity"></a> [identity](#output\_identity) | Complete Cognito pool, client, scope, group, domain and generated-secret-handle contract. |
| <a name="output_load_balancer"></a> [load\_balancer](#output\_load\_balancer) | Complete internal ALB, HTTPS listener, rule and health-path contract. |
| <a name="output_messaging"></a> [messaging](#output\_messaging) | Complete SQS URL, ARN and name contract for all primary and dead-letter queues. |
| <a name="output_network"></a> [network](#output\_network) | Complete network module output object: VPC, subnet, route, endpoint, security-group, port and flow-log handles. |
| <a name="output_observability"></a> [observability](#output\_observability) | Complete notification, access-log bucket, managed log-group, dashboard and alarm contract. |
| <a name="output_registry"></a> [registry](#output\_registry) | Complete ECR repository URL, name, ARN and registry-id contract. |
| <a name="output_runtime_configuration"></a> [runtime\_configuration](#output\_runtime\_configuration) | Root-owned SSM parameter ARNs (runtime and platform), the online-write flag parameter ARN, the four operational Lambda ARNs, and the database bootstrap result. Publishes NO certificate or listener-secret handle: each task mints its own listener material, and the certificate the load balancer presents is the operator-supplied alb\_certificate\_arn the caller already holds. |
| <a name="output_service_credentials"></a> [service\_credentials](#output\_service\_credentials) | Role-keyed service database secret handles; values remain in Secrets Manager. |
| <a name="output_spa"></a> [spa](#output\_spa) | Complete CloudFront distribution, origin bucket and origin-access-control contract. |
| <a name="output_spa_publication"></a> [spa\_publication](#output\_spa\_publication) | Non-secret values to copy into the protected GitHub dev environment variables consumed by deploy.yml. |
<!-- END_TF_DOCS -->

## Failure handling

| Failure | Response |
|:---|:---|
| Backend bucket/table/key missing | Apply bootstrap first and repeat initialization. See [Prerequisites](#prerequisites). |
| `init` reports an incomplete backend configuration | One of the four `-backend-config` values was not passed. The configuration is partial by design; see [Bootstrap and initialization](#bootstrap-and-initialization). |
| `filebase64sha256` reports a path that does not exist | The Lambda archives were not built. Run the builder in [Prerequisites](#prerequisites) before planning again. |
| `plan` or `plan -destroy` prompts with `Enter a value:` | One of the twelve non-defaulted inputs is unset. Both directions resolve variables alike; export them and re-run rather than answering the prompt. |
| State lock held | Confirm another operation is not active before any unlock action. Queue the operation rather than cancelling the running one; [teardown.md](../../../docs/runbooks/teardown.md#concurrency-interrupted-operations-and-the-state-lock) owns `force-unlock` and the judgement of when a lock is genuinely orphaned. |
| Plan contains unexpected destroys | Stop; do not apply the plan. |
| `apply` interrupted part-way | The environment may be partially provisioned and the lock may be abandoned. Do not re-run blind: take it to [teardown.md](../../../docs/runbooks/teardown.md#concurrency-interrupted-operations-and-the-state-lock). |
| Module validation rejects a value | Correct the owning variable rather than bypassing its invariant. A capacity value is refused by the [database module](../../modules/aurora-postgresql/variables.tf), which documents the rule it enforces. |
| terraform-docs reports drift | Update the generated reference to match HCL before review. The CI check is check-only and does not rewrite this file. |
| `tflint` reports a missing `description` | Every `variable` and `output` requires one; that is the mechanical half of the documentation gate, and the fix is the description rather than a lint exclusion. |

## Related documents

| Document | Covers |
|:---|:---|
| [`../prod/README.md`](../prod/README.md) | The production root: the same procedure on the other side of the five axes above |
| [`../../README.md`](../../README.md) | Package-wide procedure, the module index and the ignore policy this root relies on |
| [`../../bootstrap/README.md`](../../bootstrap/README.md) | The remote-state backend this root initializes against |
| [`../../../docs/runbooks/deploy.md`](../../../docs/runbooks/deploy.md) | The full deployment procedure, of which this root is one step |
| [`../../../docs/runbooks/teardown.md`](../../../docs/runbooks/teardown.md) | The authority for decommissioning, `force-unlock` and residual resources |
| [`../../../docs/CODE_DOCUMENTATION_STANDARD.md`](../../../docs/CODE_DOCUMENTATION_STANDARD.md) | The documentation convention this file is the prose half of |
| [`../../../MIGRATION_README.md`](../../../MIGRATION_README.md) | Build, deploy, run, migrate, validate and roll back, end to end |
| [`../../../CONTRIBUTING.md`](../../../CONTRIBUTING.md) | The explainability convention every file in this package follows |
| [`../../../README.md`](../../../README.md) | The CardDemo application overview. The mainframe path it documents remains available and unchanged: this package adds a deployment path and removes none |
