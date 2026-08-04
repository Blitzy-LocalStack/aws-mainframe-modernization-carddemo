# CardDemo Infrastructure as Code

> Operator / CI entry point for validating, deploying and tearing down the AWS
> infrastructure that runs the migrated CardDemo services. The migration delivers
> two co-equal artifacts — the migrated service code, and **the complete
> infrastructure that runs it expressed as infrastructure-as-code** — and this
> package is the second of the two. Every command below is copy-paste accurate to
> the Terraform in this tree; **if the HCL and this README ever disagree, the
> Terraform code is authoritative** — please fix this README rather than
> diverging.
>
> **Scope boundary, stated up front so nothing here is mistaken for a running
> system.** This package is authored and **statically validated** — `fmt`,
> `validate`, `plan`, `tflint`, policy scan — all of which run without an AWS
> account. Running `terraform apply` against a live account, and the cost it
> incurs, is an **operator action outside the scope of this migration**. No live
> apply has been performed, no provisioned environment exists, and nothing in
> this document is output captured from a real deployment. An operator who runs
> the deploy commands in [§6](#6-deploy) creates billable AWS resources.

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

This package provisions, in a single region across three availability zones, the
whole target platform: a three-tier VPC, an Aurora PostgreSQL Serverless v2
cluster, an ECS Fargate cluster running the eight bounded-context services, an
internal Application Load Balancer behind an API Gateway HTTP API, a Cognito user
pool, five SQS queues with dead-letter queues, a Step Functions state machine that
replaces the nightly JCL chain, a versioned S3 bucket for dataset generations, a
CloudFront-fronted bucket for the single-page application, ten ECR repositories,
four KMS customer-managed keys, Secrets Manager entries, and the log groups,
dashboards and alarms that replace the job log.

It is organised as **three Terraform roots** and **sixteen reusable modules**:

| Kind | Path | Applied directly? | Purpose |
|---|---|---|---|
| Root | `infra/bootstrap` | Yes — once per AWS account | The remote-state backend: a versioned, encrypted state bucket and a DynamoDB lock table |
| Root | `infra/envs/dev` | Yes | The `dev` environment, composed entirely from the modules |
| Root | `infra/envs/prod` | Yes | The `prod` environment, composed from the same modules |
| Module | `infra/modules/*` | **No** | Sixteen reusable building blocks, each called by an environment root |

**Modules are never applied directly.** They are validated *transitively*, through
a calling root.

> **Assumption:** a module validated or linted in isolation does not behave the
> same as the same module reached through its caller. On its own, every
> caller-supplied variable is unset, so a root's `init -backend=false` +
> `validate` is what actually exercises a module's inputs against real values.
> That is why [§5](#5-static-validation) iterates the three roots and not the
> nineteen directories.

Nineteen directories carry Terraform configuration, and every one of them
declares its own Terraform CLI floor and provider constraints in a `versions.tf`
— the sixteen modules, the two environment roots and the bootstrap root — so no
directory silently inherits a version contract from a caller.

---

## 2. Directory layout

```text
infra/
├── README.md, .tflint.hcl, .terraform-docs.yml
├── bootstrap/{versions.tf, main.tf, variables.tf, outputs.tf, README.md}
├── modules/   (16 modules; each has versions.tf, main.tf, variables.tf, outputs.tf, README.md)
└── envs/
    ├── dev/{versions.tf, backend.tf, main.tf, variables.tf, terraform.tfvars, outputs.tf, README.md}
    └── prod/{versions.tf, backend.tf, main.tf, variables.tf, terraform.tfvars, outputs.tf, README.md}
```

Two shapes recur, and the difference between them is the whole distinction
between a module and a root:

- **A module** carries five files. It has no `backend.tf` because it holds no
  state of its own, and no `terraform.tfvars` because its inputs arrive from its
  caller.
- **An environment root** carries seven — the module five plus `backend.tf`, which
  points at the state bucket `infra/bootstrap` creates, and
  `terraform.tfvars`, which supplies that environment's parameter values.

`infra/` itself holds no `.tf` files. It carries this README and the two lint and
documentation configurations, which are read by tooling rather than by Terraform.

---

## 3. Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| Terraform CLI | **1.15.8** (validated) | Everything in this package |
| `tflint` | with the AWS ruleset **0.48.0**, pinned in [`.tflint.hcl`](.tflint.hcl) | The HCL lint gate |
| `terraform-docs` | **0.20.0**, pinned in [`.terraform-docs.yml`](.terraform-docs.yml) | The per-directory documentation drift check |
| AWS credentials | — | **Only** for a real `plan`/`apply`/`destroy`. Everything in [§5](#5-static-validation) runs without them |

### 3.1 Version constraints, and why each one is where it is

Three constraints are pinned. None of them is arbitrary, and the reasoning for
each is recorded here because each is exactly the kind of pin a later reader is
tempted to relax.

| Constraint | Value | Declared in | Reasoning |
|---|---|---|---|
| `required_version` | `>= 1.15.0` | the sixteen modules | **Trade-off:** an open-ended floor rather than an exact pin. A module is consumed by a caller whose own CLI version it cannot control, so a floor lets Terraform intersect every constraint in the graph and select one satisfying CLI, where an exact pin in sixteen places would have to be edited in sixteen places. |
| `required_version` | `~> 1.15.0` | the three roots | **Assumption:** a root is the directory an operator actually runs, so it is the right place to bound the minor line as well as the floor. Validated on **1.15.8**. |
| `hashicorp/aws` | `~> 6.56` | all nineteen directories | **Assumption:** the provider only accepts an Aurora Serverless **minimum capacity of zero** from **5.81.0** onward, and `dev` is the environment permitted to use it, so 5.81.0 is a hard floor rather than a preference. 6.56 clears it with room to spare. **Alternatives Considered:** a bare `>= 5.81` was rejected because it has no upper bound and would admit a 7.x major whose resource-schema changes would land unreviewed across every module at once; an exact `= 6.56.0` was rejected because it blocks provider patch releases while buying nothing this stack needs. Verified against the Terraform Registry at 6.56.0. |
| `hashicorp/random` | `~> 3.9` | `envs/dev`, `envs/prod`, `modules/secrets`, `modules/cognito` — and **deliberately nowhere else** | **Assumption:** this provider generates the database and Cognito seed-user passwords at apply time and writes them straight into Secrets Manager, which is the mechanism that keeps generated credentials out of the repository structurally rather than by reviewer vigilance. It is declared at the two roots so Terraform resolves **one** release for the whole module graph, and in the two modules that actually generate values. It is absent from `infra/bootstrap` on purpose: nothing there generates a random value, and suffixing the state bucket name with a random identifier would make that name unreproducible for an operator who had lost the local state file. |

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
  for every operator. **Assumption:** it is *generated* by `terraform init` and
  then committed — it is never hand-authored. One lives in each of the three
  roots, and none in the modules, because a lock file belongs to the root that
  resolves the provider graph.

Only generated bytes are ignored: `.terraform/` (the provider and module cache),
`*.tfstate` and `*.tfstate.*`, `*.tfplan` and `*.tfplan.*`, and `.env` files. See
[§9](#9-secrets-and-configuration-flow) for why state and saved plans in
particular must never be committed.

---

## 4. Module index

Sixteen modules. The third column cites the baseline artifact each module was
derived from, so a reader can go back to the specification; every one of those
paths is REFERENCE-only and is never edited by this migration.

| Module | Provisions | REFERENCE source in the untouched baseline |
|---|---|---|
| `network` | Three-AZ VPC; public, private-app and **isolated-data** subnets; NAT; interface endpoints for `ecr.api`, `ecr.dkr`, `logs`, `secretsmanager`, `kms`, `sqs`, `states` and `ssm`; an S3 gateway endpoint; three security groups | *(net-new — the baseline expresses no network topology)* |
| `kms` | Four customer-managed keys **with rotation** — for Aurora, S3, Secrets Manager and SQS | `app/csd/CARDDEMO.CSD` — all eight file resources are defined `RECOVERY(NONE)` and `JOURNAL(NO)` (8 occurrences each, L1–L89), so this is encryption at rest where the baseline had none |
| `secrets` | Secrets Manager entries, and the random initial passwords generated at apply time | `app/cpy/CSUSR01Y.cpy:L21` — `05 SEC-USR-PWD PIC X(08).`, the plaintext password field this designs out |
| `ecr` | **Ten** repositories, with scan-on-push and a lifecycle policy | `app/csd/CARDDEMO.CSD:L489-L496` — two `DEFINE LIBRARY` stanzas, both naming the single `AWS.M2.CARDDEMO.LOADLIB` load library |
| `aurora-postgresql` | A Serverless v2 cluster in the isolated data subnets, encrypted, with automated backups | the ten VSAM cluster definitions across `app/jcl/*.jcl` |
| `ecs-cluster` | A Fargate cluster with Container Insights | `app/csd/CARDDEMO.CSD:L308-L480` — the 18 transaction-to-program pairs that constituted the CICS region |
| `ecs-service` | **Reusable** per-service unit: task definition, task role, log group, target group, autoscaling | the same CICS region definitions — decomposed into one deployable per bounded context |
| `alb` | Internal Application Load Balancer, HTTPS listener, per-service routing rules | *(net-new — CICS needed no load balancer)* |
| `api-gateway-http` | HTTP API, Cognito JWT authorizer, VPC Link to the internal ALB | *(net-new)* |
| `cognito` | User pool, app client, the groups `carddemo-admin` and `carddemo-user`, seed users | `app/cpy/CSUSR01Y.cpy:L22` — `SEC-USR-TYPE PIC X(01)` with its `'A'`/`'U'` values — and `app/cbl/COSGN00C.cbl` |
| `sqs` | Two FIFO and three standard queues, each with a dead-letter queue at `maxReceiveCount` 5, all SSE-KMS | the five message-queue names in the extension trees: `AWS.M2.CARDDEMO.PAUTH.REQUEST`, `AWS.M2.CARDDEMO.PAUTH.REPLY`, `CARDDEMO.REQUEST.QUEUE`, `CARDDEMO.RESPONSE.QUEUE`, `CARD.DEMO.ERROR` |
| `step-functions-batch` | The **eleven-state** `carddemo-daily-batch` state machine, its IAM role and task-definition wiring, plus a **second, smaller** state machine for ad-hoc reports | all 38 files in `app/jcl/`; the ad-hoc path from `app/csd/CARDDEMO.CSD:L499-L505`, where `DEFINE TDQUEUE(JOBS)` maps to `DDNAME(INREADER)` |
| `eventbridge-scheduler` | The nightly cron schedule, with a dead-letter target | `app/scheduler/CardDemo.ca7` and `app/scheduler/CardDemo.controlm` — their **intent**, not their syntax |
| `s3-datasets` | A versioned bucket, with prefixes and lifecycle configuration for **ten** generation-dataset families | `app/jcl/DEFGDGB.jcl:L25-L56` (six), `app/jcl/DEFGDGD.jcl:L28-L75` (three), `app/jcl/DALYREJS.jcl:L25-L26` (one) |
| `cloudfront-spa` | S3 origin with an origin access control, the distribution, and single-page-application error routing | `app/bms/*.bms` — the delivery path that replaces the 3270 terminal |
| `observability` | Log groups, dashboards, alarms and an SNS topic | the `SYSOUT` and `SYSPRINT` DD statements across the 38 jobs — 116 occurrences of `SYSOUT=*` and 80 `SYSPRINT DD`, which is what the job log actually was |

### 4.1 Why `ecr` provisions exactly ten repositories

**Assumption:** an ECR repository is needed per **container image**, not per Maven
module, and those two counts differ by one. There are **nine** Maven modules under
`services/` but only **eight** service images: `services/common-lib` is the shared
kernel library that the eight services compile against, so it has no Dockerfile
and produces no image. Adding the SPA image and the ETL image to the eight service
images gives **ten**: the eight bounded-context services plus `ui` plus
`data-migration`. Counting Maven modules instead would invent an eleventh
repository that nothing ever pushes to.

---

## 5. Static validation

Everything in this section runs **without AWS credentials and without an AWS
account**, and this is the whole of what `.github/workflows/infra-ci.yml` runs
against this package — that workflow contains no `apply` and no `destroy`. Run
these from the repository root.

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

```bash
# WHAT: initialise and validate each of the three ROOTS in turn, skipping
#       backend configuration entirely.
# WHY : `validate` refuses to run in an uninitialised directory, but a plain
#       `init` reads infra/envs/<env>/backend.tf, which points at the S3 state
#       bucket that infra/bootstrap creates — so a plain `init` needs AWS
#       credentials and an already-bootstrapped account. `-backend=false`
#       installs providers and resolves modules, which is everything `validate`
#       needs, while touching no remote state. That is what lets a contributor
#       with no AWS access review this package.
#       Trade-off: `-backend=false` does not verify the backend configuration
#       itself, so a malformed backend block survives this gate. Accepted,
#       because a backend error surfaces immediately and unmistakably on the
#       first real `init` in section 6, and the alternative — requiring
#       credentials to lint HCL — would put review out of reach for most
#       contributors.
for root in bootstrap envs/dev envs/prod; do
  terraform -chdir="infra/${root}" init -backend=false
  terraform -chdir="infra/${root}" validate
done
```

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
tflint --chdir=infra --config="$(pwd)/infra/.tflint.hcl" --recursive
```

```bash
# WHAT: assert that each directory's README still matches its variables and
#       outputs — a CHECK, which changes nothing on disk.
# WHY : infra/.terraform-docs.yml runs in `inject` mode, writing a generated
#       table between the BEGIN_TF_DOCS and END_TF_DOCS markers in each
#       directory's README.md. `--output-check` compares what WOULD be injected
#       against what is committed and fails on a mismatch.
#       Alternatives Considered: running the generator in write mode in CI and
#       committing the result was rejected, because it converts a review gate
#       into a silent mutation — the pipeline would repair the drift and the
#       author would never learn that adding a variable left the README stale.
#       Failing instead puts the correction in the same change as the cause.
terraform-docs --config=infra/.terraform-docs.yml markdown table \
  --output-check infra/modules/network
```

Finally, `.github/workflows/infra-ci.yml` runs a policy scan over the package with
an explicit severity threshold, and that scan is **gating** rather than advisory:
a finding at or above the configured severity fails the workflow instead of being
recorded as an annotation someone might read.

> **Assumption:** this README is hand-authored prose and is deliberately **not** a
> `terraform-docs` target. The generator emits a table of a directory's
> requirements, providers, resources, inputs and outputs, and `infra/` holds no
> `.tf` files of its own — so there is nothing here for it to describe. It must
> therefore never gain `BEGIN_TF_DOCS` markers. The nineteen directories that
> *do* hold `.tf` files are the ones whose READMEs carry the injected table.

---

## 6. Deploy

Two stages, in this order, and the order is not interchangeable.

### 6.1 Stage one: `bootstrap`, once per AWS account

```bash
# WHAT: create the remote-state backend: a versioned, encrypted S3 bucket for
#       state and a DynamoDB table for state locking.
# WHY : every environment's backend.tf points AT this bucket and table, so they
#       must exist before any environment can even run `init` — which is why
#       this root is the one place that necessarily begins with local state.
#       Run it once per AWS account, out of band from any environment. It is
#       deliberately EXCLUDED from .github/workflows/deploy.yml: that workflow
#       reaches the backend in order to `init`, so having it create the backend
#       would be circular.
terraform -chdir=infra/bootstrap init
terraform -chdir=infra/bootstrap plan -out=bootstrap.tfplan
terraform -chdir=infra/bootstrap apply bootstrap.tfplan
```

### 6.2 Stage two: one environment at a time

Substitute `dev` or `prod` for `<env>`.

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
terraform -chdir=infra/envs/<env> init
terraform -chdir=infra/envs/<env> plan -out=tfplan -var-file=terraform.tfvars
terraform -chdir=infra/envs/<env> apply tfplan
```

> **Trade-off:** name the saved plan so the ignore rules actually catch it. A
> saved plan is not a summary of a diff: it embeds the resolved value of every
> attribute the apply will set, which for this stack includes the Aurora master
> password and the Cognito seed-user passwords the `random` provider generates.
> One committed plan therefore discloses the same credential set as one committed
> state file. [`.gitignore`](../.gitignore) ignores `*.tfplan` and `*.tfplan.*`,
> but the extensionless `tfplan` written by the command above **is not matched by
> that pattern** — verify with `git check-ignore -v infra/envs/dev/tfplan`. So
> either write the plan with a `.tfplan` suffix, as [§6.1](#61-stage-one-bootstrap-once-per-aws-account)
> does, or delete it as soon as the apply completes. The command above is spelled
> the way the deploy workflow spells it; this note is the reason it needs the
> extra care rather than an argument for spelling it differently.

`.github/workflows/deploy.yml` performs this same `init` → `plan -out` → `apply`
sequence in CI behind an environment protection gate. It authenticates **only**
by short-lived OIDC federated role assumption against a deployment role
(`<role-arn>`); no long-lived AWS access key exists anywhere in this repository.
It also builds and pushes the ten container images to ECR ahead of the apply.

**Roll-forward and rollback.** **Alternatives Considered:** blue-green and canary
deployment are out of scope for this migration, so ECS services use a rolling
deployment only. Rolling forward is therefore a rolling service deployment with an
updated image tag (`<tag>`) — no infrastructure change is involved. Rolling back
the infrastructure itself is the teardown in [§7](#7-teardown), which is why the
ordering there matters as much as the ordering here.

---

## 7. Teardown

**The exact reverse of [§6](#6-deploy): every environment first, `bootstrap`
last.** This is the highest-consequence instruction in this document.

```bash
# WHAT: destroy one environment's resources, then — and only after every
#       environment is gone — destroy the state backend itself.
# WHY : the ORDER is the entire point. bootstrap owns the S3 bucket holding the
#       state files that describe every environment resource. Destroying it
#       first orphans those state files, which leaves the environment's real
#       resources running with nothing left that knows they exist — no
#       subsequent `destroy` can reach them, and they have to be hunted down and
#       deleted by hand while continuing to bill. Reversing these two steps is
#       not a slower teardown, it is an unrecoverable one.
terraform -chdir=infra/envs/<env> destroy -var-file=terraform.tfvars

# ... repeat for every remaining environment, and only then:
terraform -chdir=infra/bootstrap destroy
```

Two environment-level settings interact with this, and both are
[§8](#8-environment-parameterization) parameters rather than topology:

- **`prod` resists destruction on purpose.** It sets deletion protection and
  requires a final snapshot, so a `prod` destroy fails until those are changed
  deliberately in `terraform.tfvars` and re-applied. **Trade-off:** teardown of
  `prod` therefore takes two deliberate steps instead of one, which is accepted
  because the failure it prevents — an accidental single-command destruction of
  the production datastore — is unrecoverable, while the cost is one extra
  reviewed change.
- **`dev` is disposable by design.** It sets the same two flags the other way, so
  it tears down in the single command above.

The acceptance criterion this package is measured against is that a `terraform
apply` provisions the full stack cleanly and a `destroy` tears it down cleanly.
Clean teardown depends entirely on the ordering above. The same ordering is
carried in `docs/runbooks/teardown.md`.

---

## 8. Environment parameterization

`dev` and `prod` differ **only in sizing and retention, never in topology**. The
list below is **closed** — nothing outside it may differ between environments.

| Parameter | Variables |
|---|---|
| Aurora capacity and pause behaviour | `aurora_min_capacity`, `aurora_max_capacity`, `aurora_seconds_until_auto_pause` |
| ECS task count and size | `ecs_desired_count`, `ecs_task_cpu`, `ecs_task_memory` |
| Log retention | `log_retention_days` |
| CloudFront price class | `cloudfront_price_class` |
| Deletion protection and final snapshot | `deletion_protection`, `skip_final_snapshot` |

`infra/envs/dev/variables.tf` and `infra/envs/prod/variables.tf` declare an
**identical** variable set — the ten above plus the shared `aws_region` and `tags`
— so the two environments are structurally the same configuration supplied with
different values. Diffing those two files is the cheapest way to confirm the
closed list is still closed.

> **Trade-off:** `dev` is deliberately **under-sized rather than differently
> shaped**. Giving `dev` a cheaper topology — a single availability zone, a
> public database subnet, no VPC endpoints — would cut its cost further, and it
> was rejected: the moment the two environments differ in shape, `dev` stops
> being a valid rehearsal for `prod`, and every failure mode that depends on
> topology becomes discoverable only in production. Paying for three availability
> zones and an isolated data tier in `dev` buys a `dev` that can actually falsify
> a `prod` change.

### 8.1 Aurora capacity has a coupled constraint

**Assumption:** these constraints belong to the provider and the engine, not to
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
[§3.1](#31-version-constraints-and-why-each-one-is-where-it-is) — the zero
minimum is the specific reason that constraint cannot be relaxed below 5.81.0.

---

## 9. Secrets and configuration flow

The constraint is that **no secret is committed to this repository**, and it is met
structurally rather than by review vigilance. Five mechanisms carry it, and they
are listed together because each one closes a route the others leave open:

1. **Credentials are generated at apply time.** The `hashicorp/random` provider
   generates the database password and the Cognito seed-user passwords during
   `apply` and writes them directly into Secrets Manager. No credential is ever
   authored, so there is none to leak.
2. **Environment parameter files carry only sizing and retention values.** No
   secret value appears in any `.tfvars` file — see the closed list in
   [§8](#8-environment-parameterization).
3. **Deployment authenticates by short-lived federated role assumption.**
   `.github/workflows/deploy.yml` assumes a deployment role through OIDC. No
   long-lived AWS access key exists anywhere in this repository.
4. **The example environment file lists variable names with no values.** It
   documents what has to be set without ever demonstrating a real value.
5. **[`.gitignore`](../.gitignore) makes the secret-bearing generated files
   uncommittable.** It ignores `.terraform/`, `*.tfstate` and `*.tfstate.*`,
   `*.tfplan` and `*.tfplan.*`, and `.env` files. Terraform records generated
   passwords in state as **plaintext**, and a saved plan embeds the same resolved
   values, so either one committed would disclose the whole credential set at
   once. **It does not ignore `*.tfvars` or `.terraform.lock.hcl`** — both are
   required tracked deliverables, as [§3.2](#32-which-files-in-this-tree-are-tracked-in-git)
   explains.

> **Assumption:** limb 5 carries less than it appears to, and this is stated so it
> is not mistaken for a control it is not. An ignore rule keeps a path out of
> `git status` and out
> of an unqualified `git add`, which removes the most likely route to a committed
> state file: staging a directory without reading it. It does not make committing
> one impossible — `git add -f` overrides it, and an already-tracked path is
> unaffected by a rule added afterwards. The hard guarantee comes from limbs 1
> through 3, which leave no long-lived secret in the repository for a rule to have
> to catch.

### 9.1 Configuration flows outward, never inward

Terraform module outputs are the **only** source of runtime endpoints and
identifiers. The Aurora writer endpoint, each queue URL, the Cognito issuer URI
and pool identifiers, the dataset bucket name and each KMS key ARN are written to
Parameter Store and Secrets Manager, and each service reads them at startup
through its Spring profile.

**No service hard-codes an endpoint, and no endpoint appears in a `tfvars` file.**
This is also why no literal account identifier, ARN, region, bucket name or pool
identifier appears anywhere in this document — every such value is an output of an
apply, not an input to it, so the placeholders `<aws-account-id>`, `<region>`,
`<env>`, `<tag>` and `<role-arn>` are used throughout.

---

## 10. Network, security and identity

Summarised here; `docs/architecture/security-and-identity.md` carries the depth.

- **Three tiers across three availability zones, in a single region.** Public
  subnets carry **only** the load balancer and the NAT gateways. Private
  application subnets carry the ECS tasks. **Isolated data subnets carry Aurora
  and have no internet route at all** — isolating the data tier with no route out
  is the strongest blast-radius control available here.
- **AWS API traffic never leaves the VPC.** Interface endpoints cover the ECR API
  and Docker registry, CloudWatch Logs, Secrets Manager, KMS, SQS, Step Functions
  and SSM; a gateway endpoint covers S3.
- **Security groups permit three flows and nothing else:** load balancer to
  application on **8080**, application to Aurora on **5432**, and application to
  the interface endpoints on **443**.
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

> **Assumption:** the mainframe's external security manager (RACF) has **no cloud
> analogue and is not ported**. Its role is filled by least-privilege IAM task
> roles plus Cognito groups, and that substitution is documented as a *mapping* in
> `docs/architecture/security-and-identity.md` rather than emulated in HCL.
> Attempting to reproduce a RACF profile hierarchy in IAM would produce a
> structure that resembles the original and enforces something subtly different,
> which is worse than an explicit mapping a reader can check.

> **Assumption:** single region, three availability zones. Multi-region and
> disaster-recovery topology are out of scope for this migration, so no module
> provisions a second region, a global database or cross-region replication.

---

## 11. Batch orchestration and datasets

`module.step-functions-batch` provisions the **eleven-state**
`carddemo-daily-batch` state machine, invoked by the
`module.eventbridge-scheduler` nightly cron schedule. Each state that does real
work runs a Fargate task and waits for it; each carries an explicit timeout, a
retry with exponential backoff, and a catch that routes to notification. States 1
and 11 bracket the run by setting and clearing a read-only flag, reproducing the
operator quiesce and resume that the baseline performed around its batch window.
`docs/architecture/batch-orchestration.md` maps every state to the JCL job it
replaces.

> **Assumption:** the retry count of five is the baseline's own number, not an
> arbitrary choice. Every job in `app/scheduler/CardDemo.controlm` carries
> `MAXRERUN="5"` (15 occurrences), and each of its folders wraps its work in the
> same `CLOSEFIL` → work → `OPENFIL` bracket. The same five is why the SQS
> dead-letter queues in [§12](#12-messaging) use `maxReceiveCount` 5.

> **Refactoring Rationale:** restart capability is an improvement here, not a
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

**Assumption: there are ten generation-dataset bases in the baseline, not six.**
The count is easy to get wrong because they are declared in three different jobs,
and provisioning only the obvious six would silently lose four families:

| Declared in | Count | Bases |
|---|---|---|
| `app/jcl/DEFGDGB.jcl:L25-L56` | six | `TRANSACT.BKUP`, `TRANSACT.DALY`, `TRANREPT`, `TCATBALF.BKUP`, `SYSTRAN`, `TRANSACT.COMBINED` |
| `app/jcl/DEFGDGD.jcl:L28-L75` | three | `TRANTYPE.BKUP`, `TRANCATG.PS.BKUP`, `DISCGRP.BKUP` |
| `app/jcl/DALYREJS.jcl:L25-L26` | one | `DALYREJS` |

Every one of the ten is declared `LIMIT(5)` with `SCRATCH`, which means the oldest
generation is **physically deleted** once a sixth arrives. `module.s3-datasets`
therefore enables bucket versioning and applies a lifecycle rule retaining **five
noncurrent versions** per family — five retained plus the current one is the exact
analogue of that limit, and `SCRATCH` is why the rule expires the surplus rather
than transitioning it to colder storage.

---

## 12. Messaging

`module.sqs` provisions five queues, each with its own dead-letter queue at
`maxReceiveCount` 5 and SSE-KMS encryption. `docs/architecture/messaging-contracts.md`
carries the payload contracts.

| Queue | Type | Replaces |
|---|---|---|
| Authorization request | FIFO | `AWS.M2.CARDDEMO.PAUTH.REQUEST` |
| Authorization reply | FIFO | `AWS.M2.CARDDEMO.PAUTH.REPLY` |
| Inquiry request | Standard | `CARDDEMO.REQUEST.QUEUE` |
| Inquiry reply | Standard | `CARDDEMO.RESPONSE.QUEUE` |
| Error sink | Standard | `CARD.DEMO.ERROR` |

**Assumption:** the FIFO/standard split is not uniform because the baseline's two
messaging disciplines are not the same. The authorization pair is FIFO because
per-card ordering is observable behaviour in the baseline, and a FIFO message group
per card number preserves it while still allowing parallel throughput across cards.
The inquiry pair has no ordering requirement, and a standard queue costs less and
scales without group-level serialisation.

> **Alternatives Considered:** no streaming platform, no cache tier, no read
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
  of the four category labels — **`Assumption:`**, **`Trade-off:`**,
  **`Alternatives Considered:`** or **`Refactoring Rationale:`** — and explaining
  the reasoning rather than restating the argument.

Two gates check the mechanical half, and neither one can check the whole:

| Gate | Configuration | What it checks |
|---|---|---|
| `tflint` | [`.tflint.hcl`](.tflint.hcl) | `terraform_documented_variables` and `terraform_documented_outputs` fail any variable or output with no `description`; the `all` preset plus the explicitly enabled structural rules also enforce typed variables, the version constraints, comment syntax and the standard module shape |
| `terraform-docs` | [`.terraform-docs.yml`](.terraform-docs.yml) | That each directory's README still matches its actual inputs and outputs — a **check**, never a rewrite |

The prose half is carried by a README in **each of the sixteen modules and both
environment roots** — eighteen READMEs — plus `infra/bootstrap/README.md` and this
file.

> **Trade-off:** the two gates verify *presence*, not *quality*. A `description`
> that reads `"the region"` satisfies `terraform_documented_variables` completely
> while explaining nothing, and no linter can tell a real rationale from a
> plausible-sounding one. Three of the four forbidden patterns in the governing
> rule — restating the code, omitting a rationale where a reasonable alternative
> existed, and vague justification — are therefore reviewer obligations that these
> gates cannot discharge. Saying so is deliberate: a gate described as stronger
> than it is quietly costs exactly the review attention it depends on.

One convention in this package is easy to mistake for an oversight, so it is
recorded rather than left to be discovered: **a path to a document that does not
exist yet is written as a plain code span, never as a link.** Several paths in
this README are spelled `` `docs/adr/ADR-009-iac-tool.md` `` rather than linked
for that reason. **Alternatives Considered:** linking them anyway was rejected
because a link that resolves to nothing is a defect a reader finds by clicking,
and a document authored alongside its siblings would otherwise publish a page of
dead links. Each code span becomes a link once its target lands.

---

## 14. Scope boundaries

**What this package does not do.** Restating
[the boundary from the opening](#carddemo-infrastructure-as-code) because it is the
single most important thing not to misread: the IaC here is **authored and
statically validated** — `fmt`, `validate`, `plan`, `tflint` and the policy scan,
every one of which runs with no AWS account. **A `terraform apply` against a live
account is an operator action outside the scope of this migration, and none has
been performed.** No provisioned environment exists, and no output in this document
is evidence of one. An operator who runs [§6](#6-deploy) creates real, billable
resources.

**Deliberately out of scope**, each with the reason it was excluded rather than
merely a note that it was:

| Excluded | Reason |
|---|---|
| Multi-region and disaster-recovery topology | Single region, three availability zones. No module provisions a second region or a global database |
| Blue-green and canary deployment | Rolling ECS service deployment only — see the roll-forward note in [§6](#6-deploy) |
| Kafka, Kinesis, Redis, ElastiCache, read replicas | See the rejection reasoning in [§12](#12-messaging) |
| Mainframe-side artifacts with no cloud analogue | The CSD deployment deck, the operator quiesce mechanism, the job-submission tunnel and the scheduler syntax are documented as retired. Their **behaviour** is preserved — the quiesce bracket as states 1 and 11, the schedule as an EventBridge cron — but the mechanisms themselves are not reproduced |

**What this package never touches.** The migration is purely **additive**: it adds
a deployment path beside the existing one and removes nothing.
`app/**` — the COBOL programs, copybooks, BMS maps, JCL, CICS resource definitions
and seed data — is **REFERENCE-only** and is read as the specification, never
edited. So are `tests/**`, which is the functional-parity oracle,
`scripts/**`, and `samples/**`, whose mainframe build and deployment archives
remain exactly as they are. The mainframe path still works; this is a second one.

---

## 15. Related documents

| Document | Covers |
|---|---|
| `docs/adr/ADR-009-iac-tool.md` | **Why Terraform**, rather than CloudFormation or the CDK — module reuse across `dev` and `prod` from a single source of truth, a first-class `plan` as a reviewable artifact, and a `destroy` that satisfies the teardown acceptance criterion cleanly. The ADR is the authority for that decision and records its options, cost implications and risks |
| `docs/runbooks/deploy.md` | The deploy procedure with exact commands |
| `docs/runbooks/teardown.md` | The teardown procedure, carrying the same environment-then-bootstrap ordering as [§7](#7-teardown) |
| `docs/runbooks/data-migration.md` | Staging, loading and verifying the seed data |
| `docs/runbooks/batch-operations.md` | Operating the nightly chain, including redrive |
| `docs/architecture/batch-orchestration.md` | Every state mapped to the JCL job it replaces |
| `docs/architecture/messaging-contracts.md` | Queue mapping, payload field order, correlation |
| `docs/architecture/security-and-identity.md` | The Cognito mapping, IAM boundaries, encryption, and the RACF mapping rationale |
| [`docs/architecture/service-catalog.md`](../docs/architecture/service-catalog.md) | The eight services, their responsibilities and their dependencies |
| [`docs/architecture/data-model-and-schema-mapping.md`](../docs/architecture/data-model-and-schema-mapping.md) | Field-by-field record-to-column mapping |
| [`docs/CODE_DOCUMENTATION_STANDARD.md`](../docs/CODE_DOCUMENTATION_STANDARD.md) | The documentation convention summarised in [§13](#13-documentation-convention) |
| `MIGRATION_README.md` | The top-level guide: build, deploy, run, migrate data, validate, roll back |
| [`tests/README.md`](../tests/README.md) | The COBOL parity suite that establishes the behaviour this platform has to preserve |
| [`README.md`](../README.md) | The CardDemo application overview |
| [`CONTRIBUTING.md`](../CONTRIBUTING.md) | Contribution conventions, including the explainability convention this package follows |

---

<sub>Apache-2.0 · This package is additive: it adds a deployment path and removes
none. Production `app/**` is REFERENCE-only and is never modified. See the root
[`README.md`](../README.md) for the application overview and
[`CONTRIBUTING.md`](../CONTRIBUTING.md) for contribution conventions.</sub>
