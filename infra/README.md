# CardDemo Infrastructure as Code

> Operator / CI entry point for validating, deploying and tearing down the AWS
> infrastructure that runs the migrated CardDemo services. The migration delivers
> two co-equal artifacts — the migrated service code, and **the complete
> infrastructure that runs it expressed as infrastructure-as-code** — and this
> package is the second of the two. Every command below is either a local
> validation command that runs without an AWS account or an operator procedure
> that requires one, and each is labelled as such; **if the HCL and this README
> ever disagree, the Terraform code is authoritative** — please fix this README
> rather than diverging.
>
> **Scope boundary and measured delivery state.** This tree is **complete and
> statically validated**. Three Terraform roots — `infra/bootstrap`,
> `infra/envs/dev` and `infra/envs/prod` — and **sixteen** modules span
> **nineteen** Terraform directories and 78 `.tf` files. Every module carries
> `versions.tf`, `main.tf`, `variables.tf`, `outputs.tf` and a `README.md`;
> both environment roots add `backend.tf` and `terraform.tfvars` to that set. All
> five local gates pass on this checkout:
> `terraform fmt -check -recursive infra/` is clean, `validate` returns
> `Success! The configuration is valid.` for all three roots, the exact recursive
> TFLint command in [§5](#5-static-validation) returns **exit 0 with zero
> findings**, the `terraform-docs --output-check` loop reports **zero drift across
> all nineteen directories**, and the Checkov material-security gate that
> [`.github/workflows/infra-ci.yml`](../.github/workflows/infra-ci.yml) runs reports
> **828 passed, 0 failed, 22 reviewed skips and 0 parsing errors** over 307
> resources. The four workflows this migration authors — `services-ci.yml`,
> `ui-ci.yml`, `infra-ci.yml` and `deploy.yml` — and the four runbooks under
> `docs/runbooks/` are in place, as is the top-level
> [`MIGRATION_README.md`](../MIGRATION_README.md).
>
> **What is authored and validated is not what is deployed, and the difference is
> the whole of the remaining boundary.** Assumptions: **no `terraform apply` has
> been performed against a live AWS account by this repository**, so no output in
> this document is evidence of a provisioned environment, and the live-only proofs
> — a real plan against provider-resolved data, remote-state locking under
> contention, and a full apply-then-destroy cycle — remain operator actions. Every
> command in [§6](#6-deploy) and [§7](#7-teardown) creates or destroys billable
> AWS resources when it is run.

CardDemo is a mainframe **credit-card management** application written in
procedural **COBOL** running under **CICS** (online) and **JCL/JES2** (batch)
against **VSAM** data, licensed **Apache-2.0**. The baseline had no
infrastructure expressed as code at all, so every file in this tree is new rather
than converted. The baseline itself is the specification: the CICS resource
definitions, the 38 JCL jobs and the scheduler definitions under `app/` are read
as the source of truth for what each resource has to reproduce, and they are
**REFERENCE-only and never modified**.

---

## 1. Overview

Applying an environment root provisions, in a single region across three
availability zones, the whole platform: a three-tier VPC, an Aurora
PostgreSQL Serverless v2 cluster, an ECS Fargate cluster running the eight
bounded-context services, an internal Application Load Balancer behind an API
Gateway HTTP API, a Cognito user pool, five primary SQS queues each with a dead-letter
queue, a Step Functions state machine replacing the nightly JCL chain, a
versioned S3 bucket for dataset generations, a CloudFront-fronted bucket for the
single-page application, eleven ECR repositories, four KMS customer-managed keys,
Secrets Manager entries, and the log groups, dashboards and alarms replacing the
job log.

Assumptions: eleven repositories rather than the ten deployables this repository
builds. The eleventh mirrors a pinned third-party telemetry image that this
repository caches rather than builds, and it is a ratification-pending divergence
from the specification's fixed ten — the approval, the refused alternatives and the
recurring-cost reasoning are recorded in
[`docs/adr/ADR-002-compute-platform.md`](../docs/adr/ADR-002-compute-platform.md)
§4 and are not restated here. [§4.1](#41-why-ecr-provisions-eleven-repositories-for-ten-deployables)
carries the arithmetic an operator needs.

The package is organised as **three Terraform roots** and **sixteen reusable
modules**:

| Kind | Path | Applied directly? | Purpose |
|---|---|---|---|
| Root | `infra/bootstrap` | Yes — once per AWS account | The remote-state backend: a versioned, encrypted state bucket and a DynamoDB lock table |
| Root | `infra/envs/dev` | Yes | The `dev` environment, composed entirely from the modules |
| Root | `infra/envs/prod` | Yes | The `prod` environment, composed from the same modules |
| Module | `infra/modules/*` | **No** | Sixteen reusable building blocks, each called by an environment root |

**Modules are never applied directly.** A module holds no backend and no tfvars,
so it has no state of its own and no values to plan against; it is reached only
through the root that calls it.

> Assumptions: a module validated or linted in isolation does not behave the
> same as the same module reached through its caller. On its own, every
> caller-supplied variable is unset, so a root's `init -backend=false` +
> `validate` is what actually exercises a module's inputs against real values.
> That is why the root validation is the integration gate, and it passes on this
> checkout for all three roots. The [§5](#5-static-validation) loop nonetheless
> visits all nineteen directories rather than the three roots alone, so a module
> whose own contract has drifted is reported in its own name instead of surfacing
> as a puzzling error inside a caller — and so that a module's `README.md`,
> `versions.tf` and lock file are checked even when no root exercises the argument
> that broke.

Nineteen directories carry Terraform configuration, and every one of them
declares its own Terraform CLI floor and provider constraints in a `versions.tf`
— the sixteen modules, the two environment roots and the bootstrap root — so no
directory silently inherits a version contract from a caller.

---

## 2. Directory layout

```text
infra/
├── README.md, .tflint.hcl, .terraform-docs.yml
├── lambda/{build_packages.py, database_admin.py,
│           dataset_generation_retention.py, online_write_flag.py}
├── bootstrap/{versions.tf, main.tf, variables.tf, outputs.tf, README.md}
├── modules/
│   └── {alb,api-gateway-http,aurora-postgresql,cloudfront-spa,cognito,ecr,
│        ecs-cluster,ecs-service,eventbridge-scheduler,kms,network,
│        observability,s3-datasets,secrets,sqs,step-functions-batch}/
│          {versions.tf,main.tf,variables.tf,outputs.tf,README.md}
└── envs/
    ├── dev/{versions.tf,backend.tf,main.tf,variables.tf,terraform.tfvars,
    │         outputs.tf,README.md}
    └── prod/{versions.tf,backend.tf,main.tf,variables.tf,terraform.tfvars,
               outputs.tf,README.md}
```

Every one of the nineteen Terraform directories also carries a committed
`.terraform.lock.hcl`, generated by `terraform init` — see
[§3.2](#32-which-files-in-this-tree-are-tracked-in-git) for why those are tracked
rather than ignored.

Two shapes recur, and the difference between them is the whole distinction
between a module and a root:

- **A module** carries `versions.tf`, `main.tf`, `variables.tf`, `outputs.tf` and
  a README holding its generated input/output contract. It has no `backend.tf`
  because it holds no state of its own, and no `terraform.tfvars` because its
  inputs arrive from its caller. All sixteen have that shape, which is also what
  `terraform_standard_module_structure` in [`.tflint.hcl`](.tflint.hcl) checks
  mechanically rather than leaving to review.
- **An environment root** adds `backend.tf`, `terraform.tfvars` and its module
  composition to that contract. Both roots carry all seven files, and both
  `validate` cleanly — see [§5](#5-static-validation).

`infra/` itself holds no `.tf` files. It carries this README, the two lint and
documentation configurations, which are read by tooling rather than by Terraform,
and the `lambda/` sources below.

> Assumptions: `infra/lambda/` holds Python rather than HCL and is deliberately
> **not** one of the nineteen Terraform directories — it declares no `versions.tf`,
> so the `find`-driven loops in [§5](#5-static-validation) never visit it, and
> `terraform-docs` has nothing there to describe. `build_packages.py` produces the
> three deployment archives under `infra/lambda/dist/`, and **it must run before
> any `validate` or `plan`**: both environment roots evaluate `filebase64sha256`
> over those archives, and that function reads a file on disk rather than a
> resource attribute, so it is evaluated during plan and fails outright on an
> absent archive. [§5](#5-static-validation) therefore builds them first.

---

## 3. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Terraform CLI | **1.15.8** (validated) | Everything in this package |
| `tflint` | with the AWS ruleset **0.48.0**, pinned in [`.tflint.hcl`](.tflint.hcl) | The HCL lint gate |
| `terraform-docs` | **0.20.0**, pinned in [`.terraform-docs.yml`](.terraform-docs.yml) | The per-directory documentation drift check across all nineteen directories |
| Checkov | **3.3.8** (validated) | The material-security policy gate `.github/workflows/infra-ci.yml` runs; see [§5](#5-static-validation) |
| Python 3 | **3.13** (validated) | `infra/lambda/build_packages.py`, which must run before any `validate` or `plan` |
| AWS credentials | — | **Only** for a real `plan`/`apply`/`destroy`. Everything in [§5](#5-static-validation) runs without them |

### 3.1 Version constraints, and why each one is where it is

Three constraints are pinned. None of them is arbitrary, and the reasoning for
each is recorded here because each is exactly the kind of pin a later reader is
tempted to relax.

| Constraint | Value | Declared in | Reasoning |
|---|---|---|---|
| `required_version` | `>= 1.15.0` | the sixteen modules | Trade-offs: an open-ended floor rather than an exact pin. A module is consumed by a caller whose own CLI version it cannot control, so a floor lets Terraform intersect every constraint in the graph and select one satisfying CLI, where an exact pin in sixteen places would have to be edited in sixteen places. |
| `required_version` | `>= 1.15.0` | the three roots | Assumptions: the SAME open floor the sixteen modules carry, so the whole graph declares one form and no file can veto another. Refactoring Rationale: this row read `~> 1.15.0` on the reasoning that a root is where a version is selected. The roots declare `>= 1.15.0` -- `infra/bootstrap/versions.tf`, `infra/envs/dev/versions.tf` and `infra/envs/prod/versions.tf` -- and the premise was false anyway: the version is selected by the installer step that fetches the CLI, which pins **1.15.8** in `infra-ci.yml` and `deploy.yml` against a digest. 1.15.8 is therefore the reviewed installer version, not a constraint. [`docs/adr/ADR-009-iac-tool.md`](../docs/adr/ADR-009-iac-tool.md) records the decision. |
| `hashicorp/aws` | `~> 6.56` | all nineteen directories | Assumptions: the provider only accepts an Aurora Serverless **minimum capacity of zero** from **5.80.0** onward, and the auto-pause-seconds argument a zero minimum makes mandatory only from **5.81.0**, so the effective floor for the pair is **5.81.0**, and `dev` is the environment permitted to use it, so 5.81.0 is a hard floor rather than a preference. 6.56 clears it with room to spare. Alternatives Considered: a bare `>= 5.81` was rejected because it has no upper bound and would admit a 7.x major whose resource-schema changes would land unreviewed across every module at once; an exact `= 6.56.0` was rejected because it blocks provider patch releases while buying nothing this stack needs. Verified against the Terraform Registry at 6.56.0. |
| `hashicorp/random` | `~> 3.9` | all seventeen directories that declare a provider set | Assumptions: the constraint is declared uniformly so Terraform resolves **one** release for the whole module graph, rather than only in the directories that currently use it. Four directories use it today, and each uses it as an **`ephemeral`** resource or for a non-secret handle: `modules/secrets` generates each service database credential with `ephemeral "random_password"`, `envs/dev` and `envs/prod` generate the messaging-HMAC, internal-identity and pagination-cursor keys the same way, and `modules/cognito` uses `random_id` only for the opaque 128-bit handle in a secret's NAME. An ephemeral value is never written to state, which is what keeps a generated credential out of both the repository and the state file structurally rather than by reviewer vigilance. Assumptions: this provider does NOT generate the Cognito seed-user credentials — `terraform_data.seed_user_credential` in `modules/cognito` runs `seed_user_bootstrap.py`, which mints each one and writes it straight to Secrets Manager — and it does not generate the Aurora master password either, which `manage_master_user_password = true` delegates to the database service. Refactoring Rationale: this row previously named four directories and asserted the provider was declared "deliberately nowhere else", singling out `infra/bootstrap` as an intentional omission. It is in fact declared in seventeen directories including `infra/bootstrap`, and it generated neither of the two credential kinds the row credited it with. |

### 3.2 Which files in this tree are tracked in git

Two of these routinely surprise people, so both are stated explicitly.

- **`terraform.tfvars` is tracked, deliberately.** It is a required deliverable
  carrying each environment's sizing, capacity and retention values — and nothing
  else. **Do not add `*.tfvars` to [`.gitignore`](../.gitignore).** The usual
  Terraform convention is to ignore tfvars because they usually hold secrets;
  here they hold none, so applying that convention would silently drop two files
  this package has to ship.
- **`.terraform.lock.hcl` is tracked, deliberately.** It is the provider
  dependency lock that records the exact provider versions and their checksums,
  and it is what makes `terraform init` reproduce the same provider set for CI and
  for every operator. Assumptions: it is *generated* by `terraform init` and
  then committed — it is never hand-authored. **Nineteen are tracked**: one in each
  of the three roots, and one in each of the sixteen module directories. Two
  different init invocations are easy to conflate and only the first is a root's:
  a root's init resolves the provider graph for itself and its children and writes
  a lock beside itself only; `terraform init -backend=false` run *inside* a module
  directory — the documented way to make `terraform validate` work there — writes
  that directory's own lock, pinning the package used for isolated module
  validation. Both are legitimate and both are committed. Refactoring Rationale:
  this bullet previously said one lock lives in each of the three roots "and none
  in the modules". That was inaccurate, and it disagreed with the module
  `versions.tf` files, which already describe the module-lock convention, and with
  [`.terraform-docs.yml`](.terraform-docs.yml), whose `lockfile: true` setting reads
  each directory's lock — which is why a module README's Providers row reports a
  resolved release such as `6.57.1` while its Requirements row reports the `~> 6.56`
  constraint.

Only generated bytes are ignored: `.terraform/` (the provider and module cache),
`*.tfstate` and `*.tfstate.*`, `*.tfplan` and `*.tfplan.*`, and `.env` files. See
[§9](#9-secrets-and-configuration-flow) for why state and saved plans in
particular must never be committed.

---

## 4. Module index

Sixteen modules. The third column cites the baseline artifact each module was
derived from, so a reader can go back to the specification; every one of those
paths is REFERENCE-only and is never edited by this migration.

All sixteen modules carry a resource graph, so the responsibility column below is
what each module provisions rather than a contract it has yet to satisfy.

| Module | Responsibility | REFERENCE source in the untouched baseline |
|---|---|---|
| `network` | Three-AZ VPC; public, private-app and **isolated-data** subnets; NAT; **ten** interface endpoints — the eight AAP §0.4.1.9 names `ecr.api`, `ecr.dkr`, `logs`, `secretsmanager`, `kms`, `sqs`, `states` and `ssm`, plus the two approved additions `cognito-idp` and `xray` held in a separate input and ratified in [`docs/adr/ADR-008-security-and-identity.md`](../docs/adr/ADR-008-security-and-identity.md); an S3 gateway endpoint reached by a **prefix-list** egress rule from both the app and data tiers; **three** security groups (edge load balancer, application, data — the endpoint ENIs carry the application group rather than a fourth one, and the VPC Link's group is owned by `api-gateway-http`) | *(net-new — the baseline expresses no network topology)* |
| `kms` | Four customer-managed keys **with rotation** — for Aurora, S3, Secrets Manager and SQS | `app/csd/CARDDEMO.CSD` — all eight file resources are defined `RECOVERY(NONE)` and `JOURNAL(NO)` (8 occurrences each, L1–L89), so this is encryption at rest where the baseline had none |
| `secrets` | Secrets Manager entries, and the random initial passwords generated at apply time | `app/cpy/CSUSR01Y.cpy:L21` — `05 SEC-USR-PWD PIC X(08).`, the plaintext password field this designs out |
| `ecr` | **Ten** repositories, with scan-on-push and a lifecycle policy | `app/csd/CARDDEMO.CSD:L489-L496` — two `DEFINE LIBRARY` stanzas, both naming the single `AWS.M2.CARDDEMO.LOADLIB` load library |
| `aurora-postgresql` | A Serverless v2 cluster in the isolated data subnets, encrypted, with automated backups | the ten VSAM cluster definitions across `app/jcl/*.jcl` |
| `ecs-cluster` | A Fargate cluster with Container Insights | `app/csd/CARDDEMO.CSD:L308-L480` — the 18 transaction-to-program pairs that constituted the CICS region |
| `ecs-service` | **Reusable** per-service unit: task definition, task role, log group, target group, autoscaling | the same CICS region definitions — decomposed into one deployable per bounded context |
| `alb` | Internal Application Load Balancer, HTTPS listener, per-service routing rules | *(net-new — CICS needed no load balancer)* |
| `api-gateway-http` | HTTP API, Cognito JWT authorizer, VPC Link to the internal ALB | *(net-new)* |
| `cognito` | User pool, app client, the groups `carddemo-admin` and `carddemo-user`, seed users | `app/cpy/CSUSR01Y.cpy:L22` — `SEC-USR-TYPE PIC X(01)` with its `'A'`/`'U'` values — and `app/cbl/COSGN00C.cbl` |
| `sqs` | Two FIFO and four standard queues, each with a dead-letter queue at `maxReceiveCount` 5, all SSE-KMS | the five message-queue names in the extension trees: `AWS.M2.CARDDEMO.PAUTH.REQUEST`, `AWS.M2.CARDDEMO.PAUTH.REPLY`, `CARDDEMO.REQUEST.QUEUE`, `CARDDEMO.RESPONSE.QUEUE`, `CARD.DEMO.ERROR`; the shared request queue becomes separate account/date request queues so competing consumers cannot steal each other's messages |
| `step-functions-batch` | The **eleven-state** `carddemo-daily-batch` state machine, its IAM role and task-definition wiring, plus a **second, smaller** state machine for ad-hoc reports | all 38 files in `app/jcl/`; the ad-hoc path from `app/csd/CARDDEMO.CSD:L499-L505`, where `DEFINE TDQUEUE(JOBS)` maps to `DDNAME(INREADER)` |
| `eventbridge-scheduler` | The nightly cron schedule, with a dead-letter target | `app/scheduler/CardDemo.ca7` and `app/scheduler/CardDemo.controlm` — their **intent**, not their syntax |
| `s3-datasets` | A versioned bucket, with prefixes and lifecycle configuration for **ten** generation-dataset families | `app/jcl/DEFGDGB.jcl:L25-L56` (six), `app/jcl/DEFGDGD.jcl:L28-L75` (three), `app/jcl/DALYREJS.jcl:L25-L26` (one) |
| `cloudfront-spa` | S3 origin with an origin access control, the distribution, and single-page-application error routing | `app/bms/*.bms` — the delivery path that replaces the 3270 terminal |
| `observability` | Log groups, dashboards, alarms and an SNS topic | the `SYSOUT` and `SYSPRINT` DD statements across the 38 jobs — 116 occurrences of `SYSOUT=*` and 80 `SYSPRINT DD`, which is what the job log actually was |

### 4.1 Why `ecr` provisions eleven repositories for ten deployables

Assumptions: an ECR repository is needed per **container image**, not per Maven
module, and those two counts differ by one. There are **nine** Maven modules under
`services/` but only **eight** service images: `services/common-lib` is the shared
kernel library that the eight services compile against, so it has no Dockerfile
and produces no image. Adding the SPA image and the ETL image to the eight service
images gives **ten** deployables built from this repository: the eight
bounded-context services plus `ui` plus `data-migration`. Counting Maven modules
instead would invent a repository that nothing ever pushes to.

Assumptions: the **eleventh** repository holds `aws-otel-collector`, a mirror of a
pinned third-party telemetry image that this repository does not build, and it is
declared through a separate input for exactly that reason.
`third_party_mirror_repository_names` is held apart from `repository_names` so the
ten-deployable count stays assertable — `.github/workflows/infra-ci.yml` asserts the
ten as an exact set — while `main.tf` unions the two and gives the mirror the same
scan-on-push, encryption and lifecycle treatment as everything else. So ten is what
this repository *builds*, eleven is what the registry *holds*, and the two numbers
are different on purpose rather than by drift.

Assumptions: the eleventh repository is a **recorded, ratification-pending
divergence** from the specification's fixed ten rather than a local decision taken
here. The approval, the alternatives that were refused and the recurring-cost
arithmetic are in
[`docs/adr/ADR-002-compute-platform.md`](../docs/adr/ADR-002-compute-platform.md)
§4, which is the authority for the count; that reasoning is cited rather than
repeated, so the two documents cannot come to disagree about it.

Three derived counts follow from the eleven, and they are stated together because
each is easy to reach for in place of another:

| Count | Value | Where it is fixed |
|---|---|---|
| Repositories the registry holds | **eleven** | `infra/modules/ecr/main.tf` — `setunion(repository_names, third_party_mirror_repository_names)`, namespaced `<name_prefix>-<environment>/<name>`. This is the number the [§7](#7-teardown) purge asserts |
| Repositories this repository builds and pushes | **ten** | `infra/modules/ecr/variables.tf` — `repository_names`, asserted as an exact set in `infra-ci.yml` |
| Repositories that run as an ECS task | **nine** | each root's `local.workloads` — the eight services plus `data-migration`. `ui` is published to the SPA bucket and runs no task; the collector runs as a sidecar inside the eight service tasks |
| Keys `image_digests` admits | **ten** | each root's `variables.tf` validation — the nine task artifacts plus `aws-otel-collector`, whose digest pins which collector bytes ran. Nine of the ten are mandatory in `prod`, where `ecs-service` refuses a mutable `image_uri`; the collector's is optional because its reference may be an immutable private tag |

Assumptions: the mirror exists because a private task cannot reach the public
registry. `ecs-service` attaches an AWS Distro for OpenTelemetry collector sidecar to
every workload, the application tier holds no egress rule to any public destination,
and Amazon ECR Public is not served by the `ecr.api` and `ecr.dkr` interface
endpoints — so a task pulling its sidecar from `public.ecr.aws` would fail to start
with no route to fix it. Mirroring the image into this registry is what makes the
pull an in-VPC call like every other pull. It also pins what runs: the mirror is
immutable-tagged and its digest is recorded in `image_digests`.

Refactoring Rationale: this section read **ten**, with the mirror and the sidecar
both **withdrawn** on the grounds that neither appears in the frozen plan's
repository count. The withdrawal is reversed, because it discharged the count by
deleting a deliverable: AAP §0.2.1.4 and §0.9.3 require centralised metrics and
tracing, and with no collector the estate published meters nothing collected and
spans nothing exported. The count objection is answered instead of ignored — the ten
deployables §0.4.1.6 enumerates are provisioned from `repository_names` and gated in
CI as an exact set, and the mirror is a separate input holding a cached third-party
image that is not one of them.

Trade-offs: an operator has one more step before the first deployment, and it cannot
be skipped — the sidecar is `essential`, so a task whose collector cannot be pulled
never reaches `RUNNING`. [`docs/runbooks/deploy.md`](../docs/runbooks/deploy.md) §2b
is that step, and it runs after `terraform apply` has created the repository and
before any task is rolled onto the new image.

---

## 5. Static validation

Everything in this section runs **without AWS credentials and without an AWS
account**. Each command below has been run on this checkout and its observed
result is stated beneath it. Run them from the repository root.

> Assumptions: these are the same gates
> [`.github/workflows/infra-ci.yml`](../.github/workflows/infra-ci.yml) runs, with
> one deliberate difference in scope. The archive build, `fmt -check`, the
> recursive TFLint pass, the `terraform-docs` drift loop and the Checkov gate are
> the identical commands. The `validate` loop here visits all **nineteen**
> Terraform directories where the workflow's step visits the **three roots**, so
> this loop is a strict superset: a root exercises a module's inputs against real
> values and is the integration gate, while visiting a module on its own is what
> reports a broken module in its own name rather than as a puzzling error inside a
> caller. Neither is redundant, and running the superset locally means this
> workflow's static job cannot fail on HCL a local pass declared clean.

```bash
# WHAT: build the three Lambda deployment archives the roots hash at plan time.
# WHY : Assumptions: this is a PREREQUISITE of every command below it, not an
#       optional extra. Both environment roots pass `filebase64sha256` over
#       infra/lambda/dist/*.zip, and that function reads a file on disk rather than
#       a resource attribute, so Terraform evaluates it while validating and
#       planning and fails on an absent archive with a message about the file
#       rather than about the module that asked for it.
#       Alternatives Considered: committing the archives so no build step is
#       needed. Rejected because a zip is generated output — .gitignore excludes
#       it, and a committed archive would drift silently from the Python beside it,
#       which is the one form of drift no gate in this package can see.
python3 infra/lambda/build_packages.py
```

Observed: exit 0, printing `built infra/lambda/dist/database-admin.zip`,
`dataset-generation-retention.zip` and `online-write-flag.zip`.

```bash
# WHAT: check HCL canonical formatting across the whole package without
#       rewriting a single file.
# WHY : `-check` reports drift and exits non-zero, where a bare `terraform fmt`
#       silently rewrites the tree — which in CI would let a formatting
#       regression pass as green because the command "fixed" it and then
#       succeeded. `-recursive` is required because the package is nineteen
#       Terraform directories, and without it only the one directory named on
#       the command line is examined.
terraform fmt -check -recursive infra/
```

Observed: exit 0 with no output, which is what this command prints when no file
is misformatted.

```bash
# WHAT: initialise and validate every Terraform directory, skipping backend
#       configuration entirely.
# WHY : `validate` refuses to run in an uninitialised directory, but a plain
#       `init` reads infra/envs/<env>/backend.tf, which points at the S3 state
#       bucket that infra/bootstrap creates — so a plain `init` needs AWS
#       credentials and an already-bootstrapped account. `-backend=false`
#       installs providers and resolves modules, which is everything `validate`
#       needs, while touching no remote state. That is what lets a contributor
#       with no AWS access review this package.
#       Trade-offs: `-backend=false` does not verify the backend configuration
#       itself, so a malformed backend block survives this gate. Accepted,
#       because a backend error surfaces immediately and unmistakably on the
#       first real `init` in section 6, and the alternative — requiring
#       credentials to lint HCL — would put review out of reach for most
#       contributors.
#       Assumptions: `-lockfile=readonly` is not decoration. Every one of the
#       nineteen directories carries a TRACKED .terraform.lock.hcl (section 3.2),
#       and a plain `init` may rewrite one as a side effect of validating — which
#       turns a read-only review command into a nineteen-file diff an author then
#       has to explain. Readonly makes init fail loudly instead if the committed
#       lock cannot satisfy the constraints, which is the outcome worth having.
mapfile -t terraform_dirs < <(
  find infra -type f -name versions.tf -printf '%h\n' | sort
)
test "${#terraform_dirs[@]}" -eq 19
for dir in "${terraform_dirs[@]}"; do
  terraform -chdir="$dir" init -backend=false -lockfile=readonly -input=false
  terraform -chdir="$dir" validate
done
```

Observed: the directory count assertion passes at 19, and every directory reports
`Success! The configuration is valid.` — nineteen of nineteen, with no lock file
modified.

```bash
# WHAT: lint every Terraform directory against the shared rule set.
# WHY : infra/.tflint.hcl is the MECHANICAL half of this package's
#       documentation obligation: `terraform_documented_variables` and
#       `terraform_documented_outputs` fail any variable or output declared
#       without a `description`, which is the only part of the HCL convention in
#       section 13 a machine can check. It also enables the `all` preset plus
#       `terraform_typed_variables`, `terraform_required_version`,
#       `terraform_required_providers`, `terraform_unused_declarations`,
#       `terraform_naming_convention` and `terraform_standard_module_structure`,
#       so a module that drifts from the five-file shape is reported rather than
#       merely noticed in review.
#       Assumptions: `--config` stays ABSOLUTE. Under `--recursive` a relative
#       path is resolved against each visited directory, so a relative one would
#       be found in infra/ and nowhere else — and TFLint treats an absent
#       configuration as a DEFAULT configuration, which makes the run pass while
#       checking almost nothing. An absolute path is what stops this gate from
#       reporting green over eighteen unchecked directories.
tflint --chdir=infra --config="$(pwd)/infra/.tflint.hcl" --recursive
```

Observed: **exit 0 with zero findings and zero tool errors** across all nineteen
directories.

> Refactoring Rationale: this passage recorded exit code 2 with **86 findings** —
> 73 unused declarations, 10 standard-module-structure and 3 unused required
> providers — attributed to five directories that had no resource graph, and it
> instructed the reader that the gate could be called passing only once the
> missing HCL landed. That HCL landed: all sixteen modules and both roots are
> complete, every declaration those findings named is now consumed, and the exact
> command above returns zero. The historical breakdown is recorded here rather
> than deleted silently, because a reader who saw the old number needs to know it
> was superseded by the tree being finished rather than by the rule set being
> relaxed — `.tflint.hcl` still enables the `all` preset and every rule listed
> above.

```bash
# WHAT: assert that all nineteen Terraform directories have a README and that
#       every README matches its variables and outputs. This is a CHECK and
#       changes nothing on disk.
# WHY : infra/.terraform-docs.yml runs in `inject` mode, writing a generated
#       table between the BEGIN_TF_DOCS and END_TF_DOCS markers in each
#       directory's README.md. `--output-check` compares what WOULD be injected
#       against what is committed and fails on a mismatch.
#       Alternatives Considered: running the generator in write mode in CI and
#       committing the result was rejected, because it converts a review gate
#       into a silent mutation — the pipeline would repair the drift and the
#       author would never learn that adding a variable left the README stale.
#       Failing instead puts the correction in the same change as the cause.
(
  status=0
  mapfile -t terraform_dirs < <(
    find infra -type f -name versions.tf -printf '%h\n' | sort
  )
  test "${#terraform_dirs[@]}" -eq 19 || exit 1

  for dir in "${terraform_dirs[@]}"; do
    if test ! -f "$dir/README.md"; then
      printf 'missing Terraform README: %s/README.md\n' "$dir" >&2
      status=1
      continue
    fi
    terraform-docs --config=infra/.terraform-docs.yml markdown table \
      --output-check "$dir" || status=1
  done
  exit "$status"
)
```

Observed: the whole block exits 0 — all nineteen directories have a `README.md`
and all nineteen match their committed inputs and outputs, so drift is zero.

> Assumptions: this README is hand-authored prose and is deliberately **not** a
> `terraform-docs` target. The generator emits a table of a directory's
> requirements, providers, resources, inputs and outputs, and `infra/` holds no
> `.tf` files of its own — so there is nothing here for it to describe. It must
> therefore never gain `BEGIN_TF_DOCS` markers. The nineteen directories that
> *do* hold `.tf` files are the whole of the checked set.

```bash
# WHAT: run the material-security policy gate over the package.
# WHY : Assumptions: the check list is EXPLICIT rather than a severity threshold.
#       The offline Checkov distribution carries no policy severities, so
#       `--check HIGH,CRITICAL` selects zero checks and reports success over an
#       unexamined tree — a false green. The reviewable baseline is the 83 check
#       identifiers spanning IAM, encryption, audit, network, edge, messaging and
#       workload isolation that .github/workflows/infra-ci.yml enumerates in its
#       `material_checks` array; read them from there rather than retyping them, so
#       the local gate and the CI gate cannot drift apart.
#       Assumptions: `--skip-path '\.terraform'` keeps the scan off the provider
#       cache, which holds vendored HCL this package does not author.
#       Trade-offs: the workflow also runs the COMPLETE check set first under
#       `--soft-fail`, purely so the full result stays visible in the run log. That
#       first scan's failure count is NOT a gate result and must not be quoted as
#       one; the gate is the second, explicit-check scan below.
#       Assumptions: the report directory is created here because .gitignore
#       excludes `/infra-reports/`, so it is absent on a fresh checkout and the
#       redirection would fail before the scanner ever starts.
mkdir -p infra-reports
checkov -d infra --framework terraform --skip-path '\.terraform' \
  --check "$(
    sed -n '/material_checks=(/,/^          )/p' .github/workflows/infra-ci.yml |
      grep -oE 'CKV[0-9]*_AWS_[0-9]+' | paste -sd,
  )" --compact --output json > infra-reports/checkov.json
```

Observed on Checkov 3.3.8: the extracted list is **83** checks, and the scan
reports **828 passed, 0 failed, 22 skipped and 0 parsing errors** over **307
resources**, exiting 0. Every one of the 22 skips is an in-file
`checkov:skip=<id>:<reason>` annotation, and the workflow's "Verify bounded
policy-scan exceptions" step asserts each one individually so a skip cannot be
widened without that assertion being edited in the same change.

---

## 6. Deploy

Two stages, in this order, and the order is not interchangeable.

> **Live-account guard.** Both stages below are complete and runnable, and every
> command in them **creates billable AWS resources against whatever account the
> ambient credentials name**. Assumptions: no `terraform apply` has been performed
> against a live account by this repository, so nothing here has been exercised
> end to end against real provider responses — a plan that resolves cleanly is not
> the same evidence as an apply that converged. Run these only once a reviewed plan
> for the intended environment has been read and the credentials in the shell are
> confirmed to point at the intended account. The exact operator sequence, with
> every input, gate and ordering constraint spelled out, is
> [`docs/runbooks/deploy.md`](../docs/runbooks/deploy.md); this section is the
> shape of it, and the runbook is the procedure.

### 6.1 Stage one: `bootstrap`, once per AWS account

```bash
# WHAT: create the remote-state backend: a versioned, encrypted S3 bucket for
#       state and a DynamoDB table for state locking.
# WHY : every environment's backend.tf points AT this bucket and table, so they
#       must exist before any environment can even run `init` — which is why
#       this root is the one place that necessarily begins with local state.
#       Run it once per AWS account, out of band from any environment.
#       Assumptions: .github/workflows/deploy.yml deliberately EXCLUDES this
#       stage. That workflow reaches the backend in order to `init`, so having it
#       also create the backend would be circular; bootstrap stays an out-of-band
#       operator action for exactly that reason.
(
  set -euo pipefail
  plan_path="infra/bootstrap/bootstrap.tfplan"
  cleanup_plan() {
    rm -f "$plan_path"
  }
  trap cleanup_plan EXIT

  terraform -chdir=infra/bootstrap init
  terraform -chdir=infra/bootstrap plan -out=bootstrap.tfplan
  terraform -chdir=infra/bootstrap apply bootstrap.tfplan
)
```

### 6.2 Stage two: one environment at a time

Set `CARDDEMO_ENV` to `dev` or `prod`.

```bash
# WHAT: initialise the environment root against the REAL S3 backend, review a
#       saved plan, then apply exactly that saved plan.
# WHY : `init` here carries no `-backend=false`, unlike section 5, precisely
#       because this is the path that has to reach remote state — it is what
#       acquires the DynamoDB lock and reads the existing state, so an operator
#       running it needs credentials and a bootstrapped account, and section 5's
#       reviewer does not.
#       Refactoring Rationale: the two-step plan-then-apply replaces a one-step
#       apply because `apply` on its own re-plans at apply time, so what
#       executes is not what any human or gate reviewed, and `-auto-approve`
#       removes the review entirely. A saved plan is an immutable artifact — the
#       reviewed plan and the applied plan are byte-identical by construction.
#       The baseline shows exactly what an unreviewed imperative deck costs:
#       app/jcl/CBADMCDJ.jcl installs the CICS resources by hand and has drifted
#       from the code it describes, carrying a duplicate
#       `DEFINE MAPSET(COSGN00M)` stanza at both L50 and L53 and defining five
#       programs that do not exist in app/cbl at all (COADM00C at L130 and
#       COTSTP1C-COTSTP4C at L134-L143, each then wired to a transaction at
#       L148-L157). A reviewed plan surfaces that class of drift before it is
#       applied rather than after.
(
  set -euo pipefail
  : "${CARDDEMO_ENV:?Set CARDDEMO_ENV to dev or prod}"
  case "$CARDDEMO_ENV" in
    dev|prod) ;;
    *) printf 'CARDDEMO_ENV must be dev or prod\n' >&2; exit 2 ;;
  esac

  plan_file="${CARDDEMO_ENV}.tfplan"
  plan_path="infra/envs/${CARDDEMO_ENV}/${plan_file}"
  cleanup_plan() {
    rm -f "$plan_path"
  }
  trap cleanup_plan EXIT

  terraform -chdir="infra/envs/${CARDDEMO_ENV}" init
  terraform -chdir="infra/envs/${CARDDEMO_ENV}" plan \
    -out="$plan_file" -var-file=terraform.tfvars
  terraform -chdir="infra/envs/${CARDDEMO_ENV}" show "$plan_file"
  terraform -chdir="infra/envs/${CARDDEMO_ENV}" apply "$plan_file"
)
```

> Trade-offs: name the saved plan so the ignore rules actually catch it. A
> saved plan is not a summary of a diff: it embeds the resolved value of every
> attribute the apply will set. This stack is deliberately arranged so that the
> credential values are NOT among them — the service credentials and the three
> platform keys are `ephemeral` resources, the Aurora master password is delegated
> to the database service by `manage_master_user_password`, and the Cognito
> seed-user credentials are minted by the module's bootstrap script directly into
> Secrets Manager — but a plan still embeds every non-ephemeral attribute,
> including secret ARNs, account identifiers and the whole resolved topology. One
> committed plan therefore discloses the shape and the addressing of the
> deployment even where it discloses no credential. [`.gitignore`](../.gitignore) ignores `*.tfplan` and `*.tfplan.*`,
> and `git check-ignore -v infra/envs/dev/dev.tfplan` confirms the path above is
> matched. That rule is a convenience, not an access-control boundary:
> `git add -f` overrides it and a previously tracked path remains tracked. The
> `EXIT` trap is therefore the primary local safeguard and removes the plan on
> success, failure or interruption.
> [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) applies the
> same three properties on the CI path: the ignored suffix, deterministic cleanup
> of both the plan and the untracked variable file it composes, and short-lived
> OIDC authentication with no key to leak.

[`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml) performs this
same `init` → `plan -out` → `apply` sequence in CI behind an environment
protection gate. It authenticates **only** by short-lived OIDC federated role
assumption against a deployment role (`<role-arn>`); no long-lived AWS access key
exists anywhere in this repository. It also builds and pushes the **ten** images
this repository builds to ECR ahead of the apply, and mirrors the **eleventh**
repository's pinned third-party collector image in a separate step — see
[§4.1](#41-why-ecr-provisions-eleven-repositories-for-ten-deployables) for why the
two counts differ.

**Roll-forward and rollback.** Alternatives Considered: blue-green and
canary deployment are out of scope, so the ECS services use rolling
deployments. A roll-forward changes the immutable image tag or HCL value in a
reviewed commit and applies the resulting saved plan. A rollback is **not a
destroy**: restore the last known-good image tag and configuration values in a
new reviewed commit, run the same `plan -out=<env>.tfplan` command, inspect the
resource replacements and then apply that saved plan. This produces a new task
definition revision and reapplies prior desired infrastructure while preserving
stateful resources. Destroy is reserved for decommissioning and is documented
separately in [§7](#7-teardown).

---

## 7. Teardown

**The exact reverse of [§6](#6-deploy): every environment first, `bootstrap`
last.** This is decommissioning, not rollback, and it is the
highest-consequence instruction in this document.

```bash
# WHAT: define guarded, version-aware purge routines. Run this block once in the
#       shell that will perform the decommission.
# WHY : all three S3 buckets are versioned. `aws s3 rm --recursive` removes only
#       current objects and leaves noncurrent versions and delete markers, so
#       Terraform still receives BucketNotEmpty while force_destroy is false.
#       ECR likewise refuses to remove a repository containing images while
#       force_delete is false. Both defaults are deliberate data-loss stops.
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

purge_ecr_repository() {
  local repository="$1" image_ids response count failures

  while :; do
    image_ids="$(
      aws ecr list-images \
        --repository-name "$repository" \
        --max-items 100 \
        --output json |
        jq -c '.imageIds // []'
    )"
    count="$(jq -r 'length' <<<"$image_ids")"
    test "$count" -gt 0 || break

    response="$(
      aws ecr batch-delete-image \
        --repository-name "$repository" \
        --image-ids "$image_ids" \
        --output json
    )"
    test -n "$response" || response='{}'
    failures="$(jq -r '.failures // [] | length' <<<"$response")"
    test "$failures" -eq 0 || {
      jq -r '.failures' <<<"$response" >&2
      return 1
    }
  done
}
```

```bash
# WHAT: verify the target account and environment, explicitly acknowledge the
#       irreversible operation, purge the two environment buckets and exactly
#       eleven environment repositories, then destroy the environment.
# WHY : deriving names from the same composition used by the modules avoids a
#       wildcard account-wide purge. Requiring the exact account/environment
#       confirmation prevents ambient credentials from silently retargeting the
#       operation.
# WHY : Refactoring Rationale: the count assertion below read `-eq 10` and would
#       have refused to purge a correctly applied environment. infra/modules/ecr
#       keys its repositories over setunion(repository_names,
#       third_party_mirror_repository_names) and namespaces EVERY entry as
#       "<name_prefix>-<environment>/<name>", so the prefix filter matches the ten
#       built repositories AND the aws-otel-collector mirror -- eleven. An exact
#       count is kept rather than loosened to a floor, because its purpose is to
#       stop a purge that has resolved the wrong prefix and found some other
#       account's repositories; a floor would let that through. See section 4.1.
(
  set -euo pipefail
  : "${CARDDEMO_ENV:?Set CARDDEMO_ENV to dev or prod}"
  : "${CARDDEMO_NAME_PREFIX:?Set the applied Terraform name_prefix}"
  : "${CARDDEMO_DESTROY_ACCOUNT:?Set the expected 12-digit AWS account id}"
  : "${AWS_REGION:?Set the deployed AWS region}"
  : "${CARDDEMO_DESTROY_CONFIRM:?Set to DESTROY:<env>:<account>}"

  case "$CARDDEMO_ENV" in
    dev|prod) ;;
    *) printf 'CARDDEMO_ENV must be dev or prod\n' >&2; exit 2 ;;
  esac
  actual_account="$(
    aws sts get-caller-identity --query Account --output text
  )"
  test "$actual_account" = "$CARDDEMO_DESTROY_ACCOUNT"
  test "$CARDDEMO_DESTROY_CONFIRM" = \
    "DESTROY:${CARDDEMO_ENV}:${CARDDEMO_DESTROY_ACCOUNT}"

  dataset_bucket="${CARDDEMO_NAME_PREFIX}-datasets-${CARDDEMO_ENV}-${actual_account}-${AWS_REGION}"
  spa_bucket="${CARDDEMO_NAME_PREFIX}-${CARDDEMO_ENV}-spa-${actual_account}-${AWS_REGION}"
  repository_prefix="${CARDDEMO_NAME_PREFIX}-${CARDDEMO_ENV}/"

  aws s3api head-bucket --bucket "$dataset_bucket"
  aws s3api head-bucket --bucket "$spa_bucket"
  mapfile -t repositories < <(
    aws ecr describe-repositories --output json |
      jq -r --arg prefix "$repository_prefix" '
        .repositories[]?.repositoryName |
        select(startswith($prefix))
      '
  )
  test "${#repositories[@]}" -eq 11

  purge_versioned_bucket "$dataset_bucket"
  purge_versioned_bucket "$spa_bucket"
  for repository in "${repositories[@]}"; do
    purge_ecr_repository "$repository"
  done

  terraform -chdir="infra/envs/${CARDDEMO_ENV}" destroy \
    -var-file=terraform.tfvars
)
```

Before the `prod` block is run, make and apply a separately reviewed change that
sets deletion protection to false while keeping `skip_final_snapshot = false`.
Review the planned final-snapshot identifier and confirm that no snapshot with
that identifier already exists; RDS rejects a collision. Preserve the resulting
final snapshot according to the data-retention policy rather than deleting it as
part of routine teardown.

After repeating the guarded environment procedure for **every** environment,
leave bootstrap in place if the account may be used again. For a genuine account
decommission, keep the same shell (and therefore `purge_versioned_bucket`)
open and run:

```bash
# WHAT: remove the remote-state backend only after all environment resources
#       and states are no longer needed.
# WHY : deleting this bucket first orphans the state required to destroy the
#       environments. The explicit acknowledgement is intentionally different
#       from the environment token because state loss affects every environment.
(
  set -euo pipefail
  : "${CARDDEMO_DESTROY_ACCOUNT:?Set the expected 12-digit AWS account id}"
  : "${CARDDEMO_DESTROY_STATE_CONFIRM:?Set to DESTROY-STATE:<account>}"
  actual_account="$(
    aws sts get-caller-identity --query Account --output text
  )"
  test "$actual_account" = "$CARDDEMO_DESTROY_ACCOUNT"
  test "$CARDDEMO_DESTROY_STATE_CONFIRM" = \
    "DESTROY-STATE:${CARDDEMO_DESTROY_ACCOUNT}"

  state_bucket="$(
    terraform -chdir=infra/bootstrap output -raw state_bucket_name
  )"
  purge_versioned_bucket "$state_bucket"
  terraform -chdir=infra/bootstrap destroy
)
```

Two environment-level settings interact with this, and both are
[§8](#8-environment-parameterization) parameters rather than topology:

- **`prod` resists destruction on purpose.** It sets deletion protection and
  requires a final snapshot, so a `prod` destroy fails until those are changed
  deliberately in `terraform.tfvars` and re-applied. Trade-offs: teardown of
  `prod` therefore takes two deliberate steps instead of one, which is accepted
  because the failure it prevents — an accidental single-command destruction of
  the production datastore — is unrecoverable, while the cost is one extra
  reviewed change.
- **`dev` is disposable by design.** It sets the same two flags the other way, so
  it tears down in the single command above.

The acceptance criterion is that an environment `apply` provisions the stack and
the guarded procedure above tears it down cleanly. Assumptions: that criterion is
**not** demonstrated by this repository, because it requires a live account and
no apply has been run against one — the configuration is complete and statically
validated, which is a different and weaker claim, and it is the one this document
makes.

[`docs/runbooks/teardown.md`](../docs/runbooks/teardown.md) is the fuller
procedure: it adds the artifacts a `destroy` deliberately leaves behind — retained
snapshots, log groups in retention, secrets inside their recovery window — the
abandoned-lock recovery path, and the roll-back routes. Use it for a real
decommission; this section is the shape of the operation and the two purge helpers
it depends on.

---

## 8. Environment parameterization

The contract requires `dev` and `prod` to differ **only in sizing and retention,
never in topology**. The list below is the closed set, and both roots carry a
`terraform.tfvars` supplying it.

| Parameter | Variables |
|---|---|
| Aurora capacity and pause behaviour | `aurora_min_capacity`, `aurora_max_capacity`, `aurora_seconds_until_auto_pause` |
| ECS task count and size | `ecs_desired_count`, `ecs_task_cpu`, `ecs_task_memory` |
| Log retention | `log_retention_days` |
| CloudFront price class | `cloudfront_price_class` |
| Deletion protection and final snapshot | `deletion_protection`, `skip_final_snapshot` |

The two roots expose one symmetric input surface and call the same sixteen
modules, and that symmetry is asserted mechanically rather than reviewed: the
"Verify workflow input and output closure against the roots" step in
[`.github/workflows/infra-ci.yml`](../.github/workflows/infra-ci.yml) parses both
roots' `variables.tf` and `outputs.tf` and fails if they declare different
variables, disagree on which are required, or publish different outputs.

> Assumptions: that gate proves the input and output *surfaces* are identical,
> which is the part a static check can reach. Proving the provisioned *topology* is
> identical needs a plan against provider-resolved data in a live account, and no
> such plan has been produced by this repository — so symmetry is established here
> and equivalence remains an operator-verified property.

> Trade-offs: `dev` is deliberately **under-sized rather than differently
> shaped**. Giving `dev` a cheaper topology — a single availability zone, a
> public database subnet, no VPC endpoints — would cut its cost further, and it
> was rejected: the moment the two environments differ in shape, `dev` stops
> being a valid rehearsal for `prod`, and every failure mode that depends on
> topology becomes discoverable only in production. Paying for three availability
> zones and an isolated data tier in `dev` buys a `dev` that can actually falsify
> a `prod` change.

### 8.1 Aurora capacity has a coupled constraint

Assumptions: these constraints belong to the provider and the engine, not to
this package's own preferences, and getting any of them wrong produces an apply
error rather than a silently degraded cluster.

- When `aurora_min_capacity` is **0**, configuring the auto-pause threshold
  becomes **mandatory**, and `aurora_max_capacity` must be **at least 1**.
- Scaling to zero requires a sufficiently recent PostgreSQL minor version.
- The auto-pause threshold is configurable between **300** and **86,400** seconds.
- Resuming a paused cluster takes on the order of fifteen seconds.

That resume latency is immaterial to a batch chain, which is why `dev` is
permitted a minimum of zero, and it is a real hazard for interactive use, which is
why **`prod` holds its minimum above zero**. See also the `hashicorp/aws` floor in
[§3.1](#31-version-constraints-and-why-each-one-is-where-it-is): provider 5.80.0
introduced the zero minimum, while 5.81.0 introduced the auto-pause-seconds
argument this configuration also needs. The combination is why 5.81.0 remains
the effective full-feature floor.

---

## 9. Secrets and configuration flow

The constraint is that **no secret is committed to this repository**, and it is met
structurally rather than by review vigilance. Five mechanisms carry it, and they
are listed together because each one closes a route the others leave open:

1. **The authored credential modules generate values at apply time.** Each
   service database credential and each of the three platform keys is produced by
   an `ephemeral "random_password"` and written to Secrets Manager without
   entering state; each Cognito seed-user credential is minted by
   `modules/cognito`'s bootstrap script and written there directly; the Aurora
   master password is generated and rotated by the database service itself. Both
   environment roots wire every one of those modules, so the arrangement is
   complete in the authored tree. It is not verified against a live account, which
   is a different claim and is the one the deployment boundary above makes.
2. **Both environment parameter files carry only sizing and retention values.**
   `infra/envs/dev/terraform.tfvars` and `infra/envs/prod/terraform.tfvars` exist,
   are tracked, and hold nothing outside the closed list in
   [§8](#8-environment-parameterization) — no credential, no endpoint, no account
   identifier. Deployment-specific ARNs and hostnames are supplied as `TF_VAR_`
   exports at the point of use instead, which is why they are absent from these
   files rather than merely blank in them.
3. **The deployment workflow authenticates by short-lived federated role
   assumption.** [`.github/workflows/deploy.yml`](../.github/workflows/deploy.yml)
   requests an OIDC token with `id-token: write` and exchanges it for temporary
   credentials; no long-lived AWS access key exists anywhere in this repository,
   and there is no secret for one to be stored in.
4. **The example environment file lists variable names with no values.** It
   documents what has to be set without ever demonstrating a real value.
5. **[`.gitignore`](../.gitignore) keeps secret-bearing generated files out of
   ordinary staging.** It ignores `.terraform/`, `*.tfstate` and `*.tfstate.*`,
   `*.tfplan` and `*.tfplan.*`, and `.env` files. Terraform records generated
   passwords in state as **plaintext**, and a saved plan embeds the same resolved
   values, so either one committed would disclose the whole credential set at
   once. **It does not ignore `*.tfvars` or `.terraform.lock.hcl`** — both are
   required tracked deliverables, as [§3.2](#32-which-files-in-this-tree-are-tracked-in-git)
   explains.

> Assumptions: limb 5 carries less than it appears to, and this is stated so it
> is not mistaken for a control it is not. An ignore rule keeps a path out of
> `git status` and out
> of an unqualified `git add`, which removes the most likely route to a committed
> state file: staging a directory without reading it. It does not make committing
> one impossible — `git add -f` overrides it, and an already-tracked path is
> unaffected by a rule added afterwards. The hard guarantee comes from limbs 1
> through 3, which leave no long-lived secret in the repository for a rule to have
> to catch.

### 9.1 Configuration flows outward, never inward

The wiring makes Terraform module outputs the **only** source of runtime
endpoints and identifiers. Each root publishes **eighteen** outputs — one aggregate
per module plus `service_credentials` and `runtime_configuration` — carrying the
Aurora writer endpoint, the queue URLs, the Cognito issuer and pool identifiers,
the dataset bucket name and the KMS key ARNs, and it publishes them into the
runtime configuration stores the services read at startup.

> Assumptions: one aggregate output per module rather than a flat list of scalars.
> A caller then reads `registry.repository_urls` or `database.writer_endpoint`,
> which is why a step in a runbook resolves an address from the module's own output
> instead of composing one from an account identifier and a Region — a composed
> address can be plausible and wrong, and a published one cannot.

No service configuration hard-codes a deployed endpoint, and the
tfvars contract forbids endpoints and secrets. This is also why no literal
account identifier, ARN, region, bucket name or pool identifier appears in this
document: operator examples derive values or use explicit `<...>` metavariables
rather than pretending an apply has already resolved them.

---

## 10. Network, security and identity

The topology the `network`, `cognito`, `kms` and `api-gateway-http` modules
provision is summarised here.
[`docs/architecture/security-and-identity.md`](../docs/architecture/security-and-identity.md)
carries the deeper contract.

- **Three tiers across three availability zones, in a single region.** Public
  subnets carry **only** the load balancer and the NAT gateways. Private
  application subnets carry the ECS tasks. **Isolated data subnets carry Aurora
  and have no internet route at all** — isolating the data tier with no route out
  is the strongest blast-radius control available here.
- **AWS API traffic stays inside the VPC, without exception.**
  Ten interface endpoints cover the ECR API and Docker registry, CloudWatch
  Logs, Secrets Manager, KMS, SQS, Step Functions, SSM, X-Ray and the Cognito
  identity provider; a gateway endpoint
  covers S3 — eleven endpointed services in total, which is every managed service
  a task calls. Refactoring Rationale: this bullet said two dependencies were
  "deliberately reached over NAT instead" because Cognito and X-Ray "have no
  endpoint in the frozen eight-service set that AAP §0.4.1.6 fixes", and pointed at
  ADR-008 for why adding endpoints for them was rejected. Both endpoints exist —
  the set is a documented superset of §0.4.1.6's enumeration, adopted because
  §0.4.1.9's security-group contract permits the application tier no internet
  destination at all, and ADR-008's Rationale now records that reasoning in place
  of the rejection.
- **Security groups permit five declared flows and nothing else** — three security
  groups (`alb`, `app`, `data`) carry them, and every flow is declared
  as a paired egress and ingress rule except those that have no security group
  on the far side: load balancer to application on the container port,
  application to Aurora on **5432**, application to the interface endpoints on
  **443**, application back to the load balancer on **443** (this is how one
  service resolves an account context from another), application to the S3
  gateway endpoint on **443** by prefix list, and the isolated data tier to that
  same prefix list on **443**. The application-to-endpoint flow is a SELF reference
  on the `app` group, because the endpoint ENIs carry that group rather than a
  fourth one. No rule on any group names an internet destination: the
  identity-provider egress rule keyed over `identity_provider_egress_cidrs` is
  withdrawn along with its input, and the identity provider is reached through the
  `cognito-idp` interface endpoint instead. Assumptions: this list is the complete
  set the network module declares. A flow present here but absent from the module
  — or absent from both — is dropped by the application security group at run
  time, with no build failure and a hung request as the first symptom, so the
  list is maintained against `infra/modules/network/main.tf` rather than summarised.
- **At the edge:** an API Gateway HTTP API with a Cognito JWT authorizer fronts the
  internal ALB through a VPC Link; CloudFront with an origin access control fronts
  the single-page-application bucket.
- **Identity.** Cognito replaces the `USRSEC` file. The `SEC-USR-TYPE` values
  `'A'` and `'U'` map to the groups `carddemo-admin` and `carddemo-user`, and seed
  users receive random initial passwords written to Secrets Manager. This removes
  the plaintext-password field at `app/cpy/CSUSR01Y.cpy:L21` entirely rather than
  porting it.
- **Encryption.** Four KMS customer-managed keys with rotation, covering Aurora,
  S3, Secrets Manager and SQS.

> Assumptions: the mainframe's external security manager (RACF) has **no cloud
> analogue and is not ported**. Its role is filled by least-privilege IAM task
> roles plus Cognito groups, and that substitution is documented as a *mapping* in
> [`docs/architecture/security-and-identity.md`](../docs/architecture/security-and-identity.md)
> rather than emulated in HCL.
> Attempting to reproduce a RACF profile hierarchy in IAM would produce a
> structure that resembles the original and enforces something subtly different,
> which is worse than an explicit mapping a reader can check.

> Assumptions: single region, three availability zones. Multi-region and
> disaster-recovery topology are out of scope for this migration, so no module
> provisions a second region, a global database or cross-region replication.

---

## 11. Batch orchestration and datasets

The `step-functions-batch` module provisions the **eleven-state**
`carddemo-daily-batch` state machine, invoked by the `eventbridge-scheduler`
module, and both environment roots instantiate it. Each work state runs a Fargate
task synchronously with timeout, retry and catch handling, while states 1 and 11
bracket the run with a read-only flag.
[`docs/architecture/batch-orchestration.md`](../docs/architecture/batch-orchestration.md)
maps the states to the JCL jobs they replace.

Assumptions: **eleven** is the topology count and **twelve** is the timed count,
and the two are easy to conflate. Specification section 0.4.1.7 enumerates eleven
top-level states by name; the module's `var.state_timeout_seconds` validation
asserts **twelve** keys, because every state carrying a ceiling is those eleven
plus `VerifyMigration`, which runs INSIDE state 2's `Parallel` rather than beside
them. Refactoring Rationale: this passage once gave the topology count as twelve by
counting the nested gate among the top-level states, which put it in direct
contradiction with the module's own validation message; both numbers are stated
here together so a reader who meets one of them elsewhere can tell which is which.

> Assumptions: the retry count of five is the baseline's own number, not an
> arbitrary choice. Every job in `app/scheduler/CardDemo.controlm` carries
> `MAXRERUN="5"` (15 occurrences), and each of its folders wraps its work in the
> same `CLOSEFIL` → work → `OPENFIL` bracket. The same five is why the SQS
> dead-letter queues in [§12](#12-messaging) use `maxReceiveCount` 5.

> Refactoring Rationale: restart capability is an improvement here, not a
> port. It would be wrong to present the state machine's redrive plus the
> `batch.batch_run` idempotency ledger as a translation of an existing checkpoint
> contract, because the baseline has none: the only `RESTART=` anywhere in the
> tree is **commented out** (`app/jcl/DEFGDGD.jcl:L2`, `//*  RESTART=STEP30`) and
> there is **no `CHKPT=` in the tree at all**. A failed baseline run was
> re-submitted by hand. Redrive plus a durable per-step ledger is therefore new
> capability, and describing it as parity would misrepresent both systems.

The baseline's condition-code gating inverts on the way across, and the two forms
are easy to conflate because they share a keyword. A JCL `COND` is a **skip**
predicate; a state machine `Choice` is a **run** predicate. `COND=(0,NE)` — eight
occurrences across the jobs — means "run only if every predecessor ended cleanly",
which becomes the default success edge with any non-zero code caught. The single
`COND=(4,LT)` in the whole tree (`app/jcl/TRANBKP.jcl:L51`) means "run if the code
is 4 or lower", which becomes an explicit `Choice` preserving the soft-warn tier.

### 11.1 Ten generation-dataset families

Assumptions: there are **ten** generation-dataset bases in the baseline, not six.
The count is easy to get wrong because they are declared in three different jobs,
and provisioning only the obvious six would silently lose four families:

| Declared in | Count | Bases |
|---|---|---|
| `app/jcl/DEFGDGB.jcl:L25-L56` | six | `TRANSACT.BKUP`, `TRANSACT.DALY`, `TRANREPT`, `TCATBALF.BKUP`, `SYSTRAN`, `TRANSACT.COMBINED` |
| `app/jcl/DEFGDGD.jcl:L28-L75` | three | `TRANTYPE.BKUP`, `TRANCATG.PS.BKUP`, `DISCGRP.BKUP` |
| `app/jcl/DALYREJS.jcl:L25-L26` | one | `DALYREJS` |

Every one of the ten is declared `LIMIT(5)` with `SCRATCH`, which means the
oldest logical generation is **physically deleted** once a sixth arrives. The
authored `data-migration/src/carddemo_migration/loaders/s3_stage.py` writer
preserves the required
`<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/` key convention and prunes complete
generation prefixes beyond the newest five, including every object version and
delete marker below each pruned prefix. Bucket versioning protects revisions of
a stable object key; it does not count those logical `gen=` prefixes. The
module's noncurrent-version lifecycle rules are therefore same-key revision
hygiene, while the writer's prefix cleanup is the `LIMIT(5) SCRATCH` analogue.

---

## 12. Messaging

The authored `sqs` module defines **five** primary queues — two FIFO and three
standard, one per baseline queue — each with its own
dead-letter queue at `maxReceiveCount` 5 and SSE-KMS encryption, so the module
creates **ten** queues.
[`docs/architecture/messaging-contracts.md`](../docs/architecture/messaging-contracts.md)
carries the payload contracts.

| Queue | Type | Replaces |
|---|---|---|
| Authorization request | FIFO | `AWS.M2.CARDDEMO.PAUTH.REQUEST` |
| Authorization reply | FIFO | `AWS.M2.CARDDEMO.PAUTH.REPLY` |
| Inquiry request | Standard | `CARDDEMO.REQUEST.QUEUE` — **shared**, as the baseline shares it |
| Inquiry reply | Standard | `CARDDEMO.RESPONSE.QUEUE` |
| Error sink | Standard | `CARD.DEMO.ERROR` |

⚠️ Refactoring Rationale: this said **six** primaries and split the shared request
queue into an account-inquiry queue and a date-inquiry queue, on the argument that
competing consumers would otherwise steal each other's messages. That split is
withdrawn. The baseline runs **one** request queue, and specification section
0.4.1.8 specifies four inquiry/error primaries and not five; the stealing hazard is
real but is answered where the baseline answers it — `COACCT01` guards on the
four-character function code in the request record, so the target's single consumer
dispatches on that same field rather than relying on the transport to separate the
two traffics. Splitting the queue would have been a divergence from both the
baseline and the specification, adopted to avoid a problem neither has.

Assumptions: the FIFO/standard split is not uniform because the baseline's two
messaging disciplines are not the same. The authorization pair is FIFO because
per-card ordering is observable behaviour in the baseline, and specification
sections 0.4.1.8 and 0.7.6 freeze the identities that deliver it as **literal**
values — `MessageGroupId = card_num` and `MessageDeduplicationId = transaction_id`.
⚠️ Refactoring Rationale: this paragraph previously credited a purpose-scoped
opaque HMAC token with supplying the message group "without placing the card number
in queue metadata." No such derivation is in effect, and it would not have been free:
a group identity orders only while it is equal for equal cards across **every**
producer on the queue, and a deduplication identity suppresses a duplicate only
while the requester that may resend can predict it — so keying either from one
service's own secret removes both guarantees for anyone else on the queue. The
metadata exposure that the literal values entail is registered instead, as
divergence `D-AUTHORIZATION-FIFO-IDENTITY-METADATA`, bounded by the queue's
customer-managed-key encryption, its private-network-only reachability and
task-role-scoped read access. The HMAC key this root still provisions keys the
service's own correlation and diagnostic identities, and nothing on the queue.
The guarantee holds on the source queue; dead-letter
transfer is an explicit quarantine boundary with exact source admission and no
native bulk redrive. The inquiry pair has no ordering requirement, and a standard
queue costs less and scales without group-level serialisation.

> Alternatives Considered: no streaming platform, no cache tier, no read
> replica. Kafka and Kinesis were rejected because the requirement is
> request/reply with correlation, which SQS satisfies directly while a stream
> would add partition and offset management for a workload that has neither.
> Redis and ElastiCache were rejected because the baseline has no cache tier, so
> adding one would introduce cache-coherency behaviour that has no counterpart to
> be faithful to. An Aurora read replica was rejected because reporting reads go
> to the writer through read-only cross-schema views; a replica would add cost and
> replica-lag semantics for no parity benefit.

---

## 13. Documentation convention

[`docs/CODE_DOCUMENTATION_STANDARD.md`](../docs/CODE_DOCUMENTATION_STANDARD.md)
is the normative, polyglot source for this convention; it is summarised here
rather than restated, so that every directory in this package inherits the same
obligation from one place.

HCL has no docstring construct, so the analogous obligation applies to every
`.tf` file in this tree:

- a **file-header comment block** stating the file's purpose;
- a **`description` on every `variable` and every `output`**;
- a **why-comment on each non-obvious resource argument**, carrying at least one
  of the four category labels — **`Assumptions:`**, **`Trade-offs:`**,
  **`Alternatives Considered:`** or **`Refactoring Rationale:`** — and explaining
  the reasoning rather than restating the argument.

Two gates cover the mechanical half, and neither one can check the whole:

| Gate | Configuration | What it checks |
|---|---|---|
| `tflint` | [`.tflint.hcl`](.tflint.hcl) | `terraform_documented_variables` and `terraform_documented_outputs` fail any variable or output with no `description`; the `all` preset plus the explicitly enabled structural rules also enforce typed variables, the version constraints, comment syntax and the standard module shape |
| `terraform-docs` | [`.terraform-docs.yml`](.terraform-docs.yml) | Each directory's README must match its actual inputs and outputs — a **check**, never a rewrite. It passes for all nineteen directories |

The prose half is delivered: all sixteen module READMEs, both environment READMEs
and `infra/bootstrap/README.md` exist, and each carries the generated
inputs-and-outputs contract that `terraform-docs --output-check` compares against
its directory. [§5](#5-static-validation) checks the full nineteen-file set rather
than sampling one module and calling the gate complete.

> Refactoring Rationale: this paragraph and the table row above it reported that
> every one of those nineteen READMEs was absent and that the gate consequently
> failed. Both statements were measured before the files landed and neither held
> when they were read. They are corrected rather than deleted because the
> nineteen-file scope is the part worth keeping: a gate that checked one module
> would pass while eighteen directories drifted.

> Trade-offs: the two gates verify *presence*, not *quality*. A `description`
> that reads `"the region"` satisfies `terraform_documented_variables` completely
> while explaining nothing, and no linter can tell a real rationale from a
> plausible-sounding one. Three of the four forbidden patterns in the governing
> rule — restating the code, omitting a rationale where a reasonable alternative
> existed, and vague justification — are therefore reviewer obligations that these
> gates cannot discharge. Saying so is deliberate: a gate described as stronger
> than it is quietly costs exactly the review attention it depends on.

One convention in this package is easy to mistake for an oversight, so it is
recorded rather than left to be discovered: **a path to a document that does not
exist yet is written as a plain code span, never as a link**, and each code span
becomes a link once its target lands. Alternatives Considered: linking a path
ahead of its target was rejected because a link that resolves to nothing is a
defect a reader finds by clicking, where an unlinked path is read as a reference
and checked.

Refactoring Rationale: every document this README referenced by code span now
exists, so the convention has no live exceptions left in this file — the ADR, the
four runbooks, the architecture set and
[`MIGRATION_README.md`](../MIGRATION_README.md) are all linked. The convention
is retained rather than deleted because it governs the next path added, not
because a path is currently waiting on it.

---

## 14. Scope boundaries

**Current delivery boundary.** Restating
[the boundary from the opening](#carddemo-infrastructure-as-code) because it is the
single most important thing not to misread — and it is a boundary about
*deployment*, not about completeness. The HCL is **complete**: sixteen modules and
three roots across nineteen directories, with `fmt`, `validate`, TFLint, the
`terraform-docs` drift check and the Checkov material gate all returning zero on
this checkout, as [§5](#5-static-validation) records command by command. What has
**not** happened is a `terraform apply`: **no live AWS account has been
provisioned from this repository, and no output in this document is evidence of a
running environment.** Three proofs are therefore unavailable here and remain
operator work — a real `plan` resolved against provider data and a live account, a
remote-state lock demonstrated under contention, and a full apply-then-destroy
cycle. Assumptions: this is the AAP's own division of labour rather than an
omission, because a live apply incurs cost and touches an account that only an
operator can authorise.

The stage-two commands in [§6](#6-deploy) are runnable as written; what they need
is credentials, a bootstrapped backend and a reviewed plan, not further authoring.

**Deliberately out of scope**, each with the reason it was excluded rather than
merely a note that it was:

| Excluded | Reason |
|---|---|
| Multi-region and disaster-recovery topology | Single region, three availability zones. No module provisions a second region or a global database |
| Blue-green and canary deployment | Rolling ECS service deployment only — see the roll-forward note in [§6](#6-deploy) |
| Kafka, Kinesis, Redis, ElastiCache, read replicas | See the rejection reasoning in [§12](#12-messaging) |
| Mainframe-side artifacts with no cloud analogue | The CSD deployment deck, the operator quiesce mechanism, the job-submission tunnel and the scheduler syntax are documented as retired. Their **behaviour** is preserved — the quiesce bracket as states 1 and 11 of the eleven-state machine ([§11](#11-batch-orchestration-and-datasets)), the schedule as an EventBridge cron — but the mechanisms themselves are not reproduced |

**What this package never touches.** The migration is purely **additive**: it adds
a deployment path beside the existing one and removes nothing.
`app/**` — the COBOL programs, copybooks, BMS maps, JCL, CICS resource definitions
and seed data — is **REFERENCE-only** and is read as the specification, never
edited. So are `tests/**`, which is the functional-parity oracle,
`scripts/**`, and `samples/**`, whose mainframe build and deployment archives
remain exactly as they are. The mainframe path still works; this is a second one.

---

## 15. Related documents

Every document below exists in this checkout. Where one is the authority for a
procedure this README only sketches, the "Covers" column says so, because reading
the sketch instead of the procedure is the mistake this table exists to prevent.

| Document | Status | Covers |
|---|---|---|
| [`docs/adr/ADR-002-compute-platform.md`](../docs/adr/ADR-002-compute-platform.md) | Authored | The compute decision, and in §4 the ratification of the eleventh ECR repository holding the mirrored collector image — the authority for the count in [§4.1](#41-why-ecr-provisions-eleven-repositories-for-ten-deployables) |
| [`docs/adr/ADR-008-security-and-identity.md`](../docs/adr/ADR-008-security-and-identity.md) | Authored | The security and identity decision, and the formal approval of the two interface endpoints beyond the specification's eight |
| [`docs/adr/ADR-009-iac-tool.md`](../docs/adr/ADR-009-iac-tool.md) | Authored | The Terraform decision, with options, cost implications and risks |
| [`docs/runbooks/deploy.md`](../docs/runbooks/deploy.md) | Authored | **The** deploy procedure: every required input, the registry and image ordering, the schema and data steps, and the smoke flows. [§6](#6-deploy) is the shape of it |
| [`docs/runbooks/teardown.md`](../docs/runbooks/teardown.md) | Authored | **The** teardown procedure: the guarded destroy, the residue a destroy leaves, lock recovery and the roll-back routes. [§7](#7-teardown) is the shape of it |
| [`docs/runbooks/data-migration.md`](../docs/runbooks/data-migration.md) | Authored | Seed-data staging, loading and the three verification passes |
| [`docs/runbooks/batch-operations.md`](../docs/runbooks/batch-operations.md) | Authored | The nightly chain, its states and the redrive procedure |
| [`docs/architecture/batch-orchestration.md`](../docs/architecture/batch-orchestration.md) | Authored | States mapped to the JCL jobs they replace |
| [`docs/architecture/messaging-contracts.md`](../docs/architecture/messaging-contracts.md) | Authored | Queue mapping, payload field order and correlation |
| [`docs/architecture/security-and-identity.md`](../docs/architecture/security-and-identity.md) | Authored | Cognito mapping, IAM boundaries, encryption and the RACF mapping rationale |
| [`docs/architecture/service-catalog.md`](../docs/architecture/service-catalog.md) | Authored | The eight services, their responsibilities and dependencies |
| [`docs/architecture/data-model-and-schema-mapping.md`](../docs/architecture/data-model-and-schema-mapping.md) | Authored | Field-by-field record-to-column mapping |
| [`docs/CODE_DOCUMENTATION_STANDARD.md`](../docs/CODE_DOCUMENTATION_STANDARD.md) | Authored | The documentation convention summarised in [§13](#13-documentation-convention) |
| [`MIGRATION_README.md`](../MIGRATION_README.md) | Authored | The top-level guide for build, deploy, run, migration, validation and rollback |
| [`tests/README.md`](../tests/README.md) | Authored, reference-only | The COBOL parity suite establishing the behaviour this platform must preserve |
| [`README.md`](../README.md) | Authored | The CardDemo application overview |
| [`CONTRIBUTING.md`](../CONTRIBUTING.md) | Authored | Contribution conventions, including the explainability convention this package follows |

---

<sub>Apache-2.0 · This package is additive: it adds a deployment path and removes
none. Production `app/**` is REFERENCE-only and is never modified. See the root
[`README.md`](../README.md) for the application overview and
[`CONTRIBUTING.md`](../CONTRIBUTING.md) for contribution conventions.</sub>
