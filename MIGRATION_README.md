# CardDemo Migration Guide

> **Purpose.** Provide the top-level operator and developer path for the
> additive Linux/AWS migration: build, deploy, run, migrate data, validate, and
> roll back.
>
> **Source of truth.** The AAP, the package READMEs, `docs/architecture/**`,
> `docs/runbooks/**`, and the immutable baseline under `app/**`.

The baseline remains available and unchanged. The target trees are additive:
`services/`, `ui/`, `data-migration/`, and `infra/`.

## Table of Contents
- [What this is, and what it is not](#what-this-is-and-what-it-is-not)
- [Repository layout](#repository-layout)
- [Prerequisites](#prerequisites)
- [Build](#build)
- [Deploy](#deploy)
- [Run](#run)
- [Migrate data](#migrate-data)
- [Validate](#validate)
- [Batch operations](#batch-operations)
- [Roll back and roll forward](#roll-back-and-roll-forward)
- [Architecture decisions](#architecture-decisions)
- [Documented divergences from the baseline](#documented-divergences-from-the-baseline)
- [Code documentation convention](#code-documentation-convention)
- [Known limitations and troubleshooting](#known-limitations-and-troubleshooting)
- [Further reading](#further-reading)

## What this is, and what it is not

This guide covers the **migrated** system: eight Java and Spring Boot
microservices sharing one common library, a React and TypeScript single-page
application, a Python extract-transform-load package, and a Terraform
infrastructure package. All four are **added beside** the COBOL application
rather than replacing it.

The migration is **purely additive**. `app/`, `tests/`, `scripts/` and
`samples/` are unchanged, and the mainframe application remains runnable exactly
as [`README.md`](README.md) documents. This guide never asks a reader to edit
anything in those trees.

The COBOL baseline is the **behavioural specification and the parity oracle**,
not legacy to be discarded. Where the two systems disagree, the COBOL is right
by definition and the difference is either a defect in the migration or a
divergence recorded in
[the traceability register](docs/architecture/cobol-to-service-traceability.md).

What this guide is **not** is evidence that a live environment exists. The
infrastructure code is authored and statically validated — formatting, graph
validation, a plan, HCL lint and a policy scan all run in continuous integration
— but no `terraform apply` has been performed against an AWS account, and this
document contains no account identifier, endpoint or cost figure because there
is none to report.

Trade-offs: static validation is the deliberate stopping point. It catches
configuration and policy errors without incurring cloud spend and without
placing credentials in a pipeline, whereas a live apply requires both. Live
provisioning is therefore left to an operator who owns the account and its
billing, and every deployment instruction below is written for that operator to
execute rather than as a record of something already run.

## Repository layout

The trees this migration authors:

| Tree | Purpose |
|:-------------------------|:--------------------------------------------------------------|
| `services/` | Nine Maven modules — `common-lib` plus the eight bounded-context services |
| `ui/` | The React and TypeScript single-page application replacing the 3270 screens |
| `data-migration/` | The Python package that stages, loads and verifies the baseline datasets |
| `infra/` | Terraform: a state-backend bootstrap, reusable modules, and the `dev` and `prod` roots |
| `docs/adr/` | One architecture decision record per critical decision, plus an index |
| `docs/architecture/` | Diagrams, the service catalogue, data mapping, batch, messaging, security and traceability |
| `docs/runbooks/` | Step-by-step deploy, teardown, data-migration and batch-operations procedures |
| `config/checkstyle/` | The Java documentation gate configuration |
| `config/rule1/` | The repository-wide rationale-label and documentation gate |
| `.github/workflows/` | Build, test, lint, documentation-gate and deployment pipelines |

The trees that are **read, never modified**:

| Tree | Why it is reference-only |
|:-------------|:----------------------------------------------------------------------|
| `app/` | The COBOL programs, copybooks, BMS maps, JCL, CICS definitions and seed data — the specification being migrated |
| `tests/` | The three-layer COBOL suite that serves as the functional-parity oracle |
| `scripts/` | That suite's runners, which are the binding contract for invoking it |
| `samples/` | The mainframe build, security and deployment samples |
| `diagrams/` | The baseline's published diagrams |

## Prerequisites

Assumptions: every command below runs **from the repository root**. Check the toolchain first rather
than assuming a profile script put it on `PATH`.

```bash
# WHAT: prove each tool this guide invokes resolves, and name any that does not.
# WHY : Refactoring Rationale: this used to read `. /etc/profile.d/00-carddemo-toolchain.sh`, and no
#       such file exists -- not under that name and not under `carddemo-toolchain.sh` either. A
#       `.`-source of a missing file fails the shell immediately under `set -e` and, worse, is
#       commonly written with a `|| true` that hides it, leaving a session that looks prepared and
#       has nothing on PATH. Checking the tools directly tests the property that actually matters,
#       and it holds however they were installed.
# WHY : Assumptions: each name is resolved with `command -v` rather than by running it with
#       `--version`. Resolution is the question -- a tool that resolves but is the wrong major
#       version is reported by the version block in
#       docs/runbooks/deploy.md, which is where that belongs.
# WHY : Assumptions: `aws` is listed but `ruff` is not. `ruff` is a dependency of the ETL environment
#       built under "Build" below and is invoked from it by path, so it is deliberately not
#       expected on PATH; `aws` may come from a system package or from an environment-specific
#       install, so it is checked here and its absence is a real prerequisite failure.
# WHY : Assumptions: `psql` is required, not optional. It is the only client that can send the three
#       multi-statement operator SQL files in docs/runbooks/deploy.md Step 4d -- two of them carry
#       dollar-quoted blocks, and the Data API used everywhere else takes one statement per call.
# WHY : Trade-offs: the check is a FUNCTION returning non-zero rather than a bare `exit 1`. It has to
#       satisfy two callers with opposite needs: pasted into an interactive shell, `exit` would close
#       the terminal; run as a script, printing FAIL and exiting 0 would let a pipeline continue with
#       a missing tool. A function's return status becomes the script's exit status when it is the
#       last command, and merely sets `$?` interactively -- so both callers get what they need.
carddemo_check_toolchain() {
  missing=""
  for tool in java mvn node npm python3 terraform docker jq aws psql; do
    command -v "$tool" >/dev/null 2>&1 || missing="$missing $tool"
  done
  if [ -z "$missing" ]; then
    echo "OK   toolchain resolved"
    return 0
  fi
  printf 'FAIL not on PATH:%s -- install or activate these before continuing\n' "$missing" >&2
  return 1
}
carddemo_check_toolchain
```

Every version below is stated by the manifest that owns it, and this table cites
the manifest rather than repeating the number.

Assumptions: a version copied into prose drifts the moment the manifest moves,
and a reader who then trusts the prose installs the wrong toolchain. Naming the
owning file keeps exactly one authority per version, which is the same
single-sourcing discipline the COBOL suite applies by resolving every record
layout through `app/cpy` rather than restating it per program.

| Tool | Required version | Declared by | Needed for |
|:-----------------|:-----------------------------|:------------------------------------------|:------------------------------|
| JDK | 21 | `services/pom.xml` (`java.version`, `maven.compiler.release`) | Compiling and running the nine Maven modules |
| Maven | 3.9.0 or later | `services/pom.xml` (enforcer `requireMavenVersion`) | Driving the service reactor build |
| Node.js and npm | See the `engines` field | `ui/package.json` | Building and testing the single-page application |
| Python | 3.13 or later | `data-migration/pyproject.toml` (`requires-python`) | Running the extract-transform-load package |
| Terraform CLI | 1.15.0 or later | every `infra/**/versions.tf` (`required_version`) | Validating and applying the infrastructure |
| AWS CLI | No repository pin | — | Registry authentication, secret retrieval, batch invocation |
| Docker or an OCI builder | No repository pin | — | Building the service, UI and ETL images |
| `jq` | No repository pin | — | Reading grouped Terraform outputs, as the `Run` section does |
| `psql` | No repository pin | — | Sending the multi-statement operator SQL in the deployment runbook |
| GnuCOBOL and the pinned Python test dependencies | As recorded by the suite | [`tests/README.md`](tests/README.md) section 3 and `tests/requirements-test.txt` | The functional-parity oracle only |

**Note**: the parity oracle's dependency pins are hash-locked and are part of the
reference-only `tests/` tree. Install them as that suite documents; nothing in
this migration re-pins them, because the suite's byte-deterministic golden
comparisons are only trustworthy while its closure is frozen.

## Build

```bash
# WHAT: compile, test and PACKAGE the Java reactor with its documentation and
#       architecture gates.
# WHY : Assumptions: common-lib supplies shared security, money, codec, and
#       architecture contracts, so a reactor build is the minimum coherent unit.
# WHY : Refactoring Rationale: this was `clean test`, which stopped short of two
#       things the deployment depends on. `verify` additionally runs the Spring Boot
#       repackage goal, which produces the executable jar each service Dockerfile
#       copies, and it reaches the integration-test phase Failsafe binds to. Building
#       with `test` therefore left the jars unbuilt and the integration suite unrun
#       while still reporting success, and it is not what CI gates on --
#       .github/workflows/services-ci.yml runs `clean verify`.
mvn -B -f services/pom.xml clean verify
```

```bash
# WHAT: type-check, lint, test, and build the React SPA from the lock file.
# WHY : Assumptions: npm ci refuses manifest/lock drift before any UI artifact is produced.
# WHY : Alternatives Considered: `npm install`, which is the reflex and is wrong here.
#       `install` treats the lock file as a starting point and may resolve a different
#       tree -- writing an updated lock as a side effect -- so two machines can build
#       two different applications from one commit. `ci` installs the locked tree
#       exactly or fails, which makes the lock file the single authority and the build
#       reproducible.
cd ui
npm ci
npm run typecheck
npm run lint
npm test
npm run build
cd ..
```

The ETL gets an environment **of its own**, and the reason is a hard conflict rather than a
preference: `tests/requirements-test.txt` pins `cryptography==49.0.0` for the COBOL parity suite while
`data-migration/requirements.txt` pins `cryptography==50.0.0`, and both are installed with
`--require-hashes`. One interpreter cannot satisfy both, so installing either closure into the other's
environment silently replaces a hash-locked pin the other depends on.

```bash
# WHAT: create the ETL's own environment, install its hash-locked closures, install the package, and
#       run its gates.
# WHY : Assumptions: `--without-pip` is required, not preferred. The interpreter on the reviewed image
#       ships without the `ensurepip` payload, so a plain `python3 -m venv` aborts with
#       "Command '... -m ensurepip ...' returned non-zero exit status 1" and leaves an unusable
#       directory. Creating the environment without pip and bootstrapping it explicitly works with or
#       without `ensurepip`.
# WHY : Assumptions: the package install is LAST and carries `--no-build-isolation --no-deps`.
#       `data-migration/pyproject.toml` puts no source directory on pytest's import path on purpose --
#       the suite imports the INSTALLED distribution -- and the package is src-layout, so without this
#       step there is no importable `carddemo_migration` and no `carddemo-migrate` script at all.
#       `--no-build-isolation` makes the build use the backend just pinned by digest instead of
#       resolving one from the network; `--no-deps` stops the install re-resolving a closure the two
#       hash-locked manifests already fixed.
# WHY : Trade-offs: `.venv` is gitignored at any depth, so this directory cannot be committed. It sits
#       inside `data-migration/` rather than in a temporary directory so that this guide,
#       docs/runbooks/deploy.md and docs/runbooks/data-migration.md can all name one path.
python3 -m venv data-migration/.venv --without-pip
curl -sSf https://bootstrap.pypa.io/get-pip.py | data-migration/.venv/bin/python -
data-migration/.venv/bin/python -m pip install --require-hashes -r data-migration/requirements-dev.txt
data-migration/.venv/bin/python -m pip install --require-hashes -r data-migration/requirements-build.txt
data-migration/.venv/bin/python -m pip install --no-build-isolation --no-deps ./data-migration

data-migration/.venv/bin/ruff check data-migration
data-migration/.venv/bin/python -m compileall -q data-migration/src
data-migration/.venv/bin/python -m pytest -v --tb=short data-migration/tests
data-migration/.venv/bin/carddemo-migrate --help
```

## Deploy

Follow [the deployment runbook](docs/runbooks/deploy.md). The order is:

1. apply `infra/bootstrap`, then resolve the four partial-backend values it publishes;
2. run the language gates, and export the twelve root inputs the environment declares without defaults;
3. initialise the environment backend and apply **only** `module.ecr`, because the ten repositories
   the images are pushed to are Terraform-managed and do not exist on a clean account;
4. build, push and capture the digest of each of the ten images, then export the nine-entry digest map
   the environment consumes;
5. review and apply the full environment plan;
6. bring the database to its cutover state, then load and verify the data;
7. narrow the content-security policy to the resolved API origin, publish the SPA with its runtime
   `config.json`, and verify service health.

Assumptions: steps 3 and 4 are in that order and not the other way round. The registry has to exist
before an image can be pushed to it, and it is created by Terraform rather than by hand so the full
plan in step 5 does not discover unmanaged resources.

Condensed to three stages, with the runbook owning every precondition and
failure path, the sequence is as follows.

### Stage 1 — bootstrap the remote state backend, once per account

```bash
# WHAT: create the versioned, encrypted state bucket and the lock table, then read
#       back the partial-backend values every environment root needs.
# WHY : Assumptions: this runs ONCE per AWS account and strictly BEFORE any
#       environment root, because each environment's `backend.tf` names this bucket
#       and this lock table as its state store. `terraform -chdir=infra/envs/<env>
#       init` resolves that backend before it evaluates a single resource, so
#       running it first fails on a bucket that does not exist yet -- and it fails
#       while reporting a backend error, which reads as a misconfigured backend
#       rather than as a stage performed out of order.
# WHY : Trade-offs: the bootstrap keeps its own state as a local file beside itself
#       rather than in the bucket it creates. A backend cannot store the state of
#       its own creation, so the alternative is a migrate-state step immediately
#       after the first apply, which buys nothing and adds a step that can half-fail.
terraform -chdir=infra/bootstrap init
terraform -chdir=infra/bootstrap plan -out=bootstrap.tfplan
terraform -chdir=infra/bootstrap apply bootstrap.tfplan
```

### Stage 2 — publish the container images

```bash
# WHAT: authenticate to the account's registry, then build, tag and push one image
#       per deployable unit.
# WHY : Assumptions: the ten repositories are Terraform-managed and are created by
#       the `module.ecr`-only apply the runbook performs first, so a push before
#       that apply fails on a repository that does not exist.
# WHY : Assumptions: no long-lived credential participates. This reads an ambient
#       short-lived identity; the pipeline in .github/workflows/deploy.yml assumes a
#       role by OIDC instead, so no access key is stored in the repository, in a
#       workflow secret consumed as a key pair, or in any command in this guide.
AWS_ACCOUNT_ID="<aws-account-id>"
AWS_REGION="<region>"
IMAGE_TAG="<tag>"
REGISTRY="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${REGISTRY}"

docker build -f services/auth-service/Dockerfile -t "${REGISTRY}/carddemo-auth-service:${IMAGE_TAG}" .
docker push "${REGISTRY}/carddemo-auth-service:${IMAGE_TAG}"
```

The remaining service images follow the same two lines with their own module
directory and repository name; the UI image builds from the `ui` directory as its
own context, and the ETL image from `data-migration`. The runbook enumerates all
ten together with the digest map the environment consumes. Assumptions: it is the
digest rather than the tag that the deployment pins, so the push is only half the
step — the digest it produces has to reach the environment, and keeping both
halves in one place is what stops an image being pushed and never deployed.

### Stage 3 — apply an environment root

```bash
# WHAT: initialise the environment against the bootstrap backend, capture a plan as
#       a reviewable artifact, and apply exactly that plan.
# WHY : Assumptions: `apply` is given the saved plan file rather than being run bare.
#       A bare apply re-plans against whatever the account looks like at that moment,
#       so the thing reviewed and the thing applied are two different plans; passing
#       the file makes them the same one and makes the review meaningful.
ENVIRONMENT=dev
terraform -chdir="infra/envs/${ENVIRONMENT}" init
terraform -chdir="infra/envs/${ENVIRONMENT}" plan -out=tfplan
terraform -chdir="infra/envs/${ENVIRONMENT}" apply tfplan
```

The same three commands serve `prod` with `ENVIRONMENT=prod`. The two
environments differ **only** in sizing and retention — database capacity bounds
and the auto-pause setting, task count and task CPU and memory, log retention,
the content-delivery price class, and the deletion-protection and
final-snapshot flags — and **never** in topology. Assumptions: both roots call
the same modules with the same wiring, so a change proven in `dev` exercises the
same graph it will exercise in `prod`; a topology difference would make `dev` a
different system that happens to be smaller, and its evidence would not transfer.

### Secrets and runtime configuration

**No secret value appears in any `terraform.tfvars` file.** Those files carry
capacity, sizing and retention parameters only. Database credentials and
seed-user passwords are generated at apply time and written to AWS Secrets
Manager, so the value never exists in a file a commit could capture.

The baseline's sign-on credentials are **not** carried forward. Its user record
stores an eight-character password in plain text and its sign-on program compares
it directly; the migrated system does not carry that field at all — the user table
keeps only a subject reference, and identity moves to a managed user pool. This is
the one place the migration deliberately declines parity, and it is recorded as a
divergence in
[`docs/architecture/security-and-identity.md`](docs/architecture/security-and-identity.md)
rather than left as an undocumented difference. Assumptions: porting a plaintext
password column would have reproduced the defect faithfully and then required a
second migration to remove it, so the field is designed out at the point identity
moves rather than carried and cleaned up later.

```bash
# WHAT: retrieve a generated credential from Secrets Manager when an operator needs
#       one, without ever writing it into a tracked file.
# WHY : Assumptions: generation-at-apply is what makes "no secrets committed"
#       structurally true rather than merely observed. Nothing generates the value
#       until Terraform runs, so there is no window in which a developer holds it and
#       could paste it into a manifest, a tfvars file or a runbook.
aws secretsmanager get-secret-value \
  --secret-id "<secret-id>" \
  --query SecretString \
  --output text
```

Service runtime configuration — the database endpoint, the queue URLs, the
identity-provider issuer URI, the dataset bucket name and the encryption key
identifiers — comes from Terraform outputs written to Parameter Store and
Secrets Manager, and each service reads them at startup through its Spring
profile. **No service hard-codes an endpoint**, and no endpoint appears in this
guide either. Assumptions: the authoritative value does not exist until an
account has been provisioned, so any endpoint written here would be a guess that
reads as a fact — and a service carrying a hard-coded endpoint could not be moved
between `dev` and `prod` without a code change.

No long-lived AWS credential belongs in a file or command. CI deployment uses
OIDC; interactive deployment uses an approved short-lived ambient identity.

Trade-offs: a real `terraform apply` creates billable AWS resources and remains
an operator action. Static validation proves graph and policy correctness but
is not represented as a live-account deployment.

## Run

After deployment, read endpoints from the selected environment:

```bash
# WHAT: display the browser and API entry points without copying account-specific values into docs.
# WHY : Assumptions: outputs are the reviewed contract between Terraform and operators.
# WHY : Refactoring Rationale: this read `output -raw cloudfront_domain_name` and
#       `output -raw api_endpoint_url`, and neither output exists. `infra/envs/<env>/outputs.tf`
#       publishes eighteen GROUPED outputs -- one aggregate per module -- and records that choice
#       explicitly, so a scalar read fails with `Output "cloudfront_domain_name" not found` after a
#       successful deployment, which reads as a broken deployment rather than as a wrong command.
#       The members are `spa`.`distribution_domain_name` and `api_gateway`.`api_endpoint_url`.
# WHY : Assumptions: read with `output -json <group>` piped through `jq -er`. `-e` makes a missing or
#       null member exit non-zero, so a renamed member is reported here rather than printing `null`
#       into whatever consumes it.
# WHY : Assumptions: the API value is the stage invoke URL and is NOT what the browser client is
#       given. Every published route key carries an `/api/v1` prefix, so the SPA's `apiBaseUrl` is
#       this value with that prefix appended -- which docs/runbooks/deploy.md Step 6 derives and
#       validates. Handing the bare endpoint to the SPA produces a 404 on every call.
ENVIRONMENT=dev
terraform -chdir="infra/envs/${ENVIRONMENT}" output -json spa | jq -er '.distribution_domain_name'
terraform -chdir="infra/envs/${ENVIRONMENT}" output -json api_gateway | jq -er '.api_endpoint_url'
```

```bash
# WHAT: read the two values the SPA publication step needs, from the aggregate that carries both.
# WHY : Assumptions: `spa_publication` exists precisely so a publication step reads one output rather
#       than three, and it spells the distribution member `spa_distribution_id` -- not
#       `cloudfront_distribution_id`. docs/runbooks/deploy.md Step 6 owns the publication itself; this
#       is here so the values can be inspected without opening it.
terraform -chdir="infra/envs/${ENVIRONMENT}" output -json spa_publication \
  | jq -er '{bucket: .spa_bucket_name, distribution: .spa_distribution_id}'
```

Both values are reached through the account's own edge: the browser loads the
single-page application from the content-delivery distribution, and the
application calls the HTTP API, which authorises every request against a
Cognito-issued JSON Web Token. The token's group claim decides whether the
administrative routes are reachable, so authorisation is carried by a signed
claim rather than by anything the client asserts.

Operate the nightly and ad-hoc workflows through
[the batch runbook](docs/runbooks/batch-operations.md).

### One service locally

Each service runs as an ordinary executable jar against a reachable PostgreSQL
database, with the `dev` Spring profile active:

```bash
# WHAT: start one service against a local or port-forwarded database.
# WHY : Assumptions: every value a service needs is supplied by the environment
#       rather than by a file in the repository, and the service fails at startup
#       when one is missing instead of falling back to a default. That is deliberate:
#       a silent fallback would let a service come up pointed at the wrong database
#       or the wrong issuer and report itself healthy.
# WHY : Assumptions: the per-service variable list is NOT tabulated here. It differs
#       per service and it changes with the service, so the authority is the module's
#       own README -- services/<service>/README.md -- and duplicating it here would
#       produce a second list that silently goes stale.
export SPRING_PROFILES_ACTIVE=dev
java -jar services/auth-service/target/auth-service-*.jar
```

Read `services/<service>/README.md` for that service's required environment
variables, its endpoints and its local database expectations. The service
catalogue in
[`docs/architecture/service-catalog.md`](docs/architecture/service-catalog.md)
names all eight and what each owns.

### The single-page application locally

```bash
# WHAT: start the Vite development server from the authored UI package.
# WHY : Assumptions: VITE_API_BASE_URL points at an approved API endpoint and
#       contains no secret.
cd ui
npm run dev
```

The application's configuration comes from environment variables, every one of
which is documented in `ui/.env.example`. That template carries **variable names
and no values**, and a real `.env` is git-ignored. Assumptions: the asymmetry is
the point of the file — the template can be tracked precisely because it holds
nothing worth protecting, so a contributor can discover what to set without any
value ever entering a commit. [`ui/README.md`](ui/README.md) documents the
variables and the local workflow in full.

### Health

Every service exposes `/actuator/health`. The same endpoint is polled by two
consumers with different consequences: the load balancer's target group uses it
to decide whether a task receives traffic, and the container health check uses it
to decide whether the task is replaced.

## Migrate data

Cutover is **read, then verify, then switch** — never a single swap. The three
movements are:

1. **Read and stage.** The exported flat files under `app/data/` are published,
   byte-preserved, to the dataset bucket's source-extract prefix. The files are
   read and copied; nothing under `app/` is altered.
2. **Load.** Each dataset is decoded from its fixed-width layout and bulk-loaded
   into the relational tables of the schema that owns it.
3. **Verify.** Three passes run over what was loaded, and only then is the system
   considered switchable.

Verification is a first-class step rather than a closing formality, and **all
three passes are required**: row counts per dataset, record checksums, and
money-total parity against the source files.

Trade-offs: row counts alone prove only that some rows arrived — they cannot
detect a field decoded at the wrong offset, a sign overpunch read as a digit, or
a packed nibble transcoded into a replacement character, every one of which
produces the expected row count and the wrong money. The checksum pass compares
records field by field and the money-total pass compares the sums the source
files themselves carry, so a load that "succeeded" without them is not evidence
of anything. The accepted cost is one extra pass over the loaded data, which is
cheap set against discovering a decode defect after the system has been switched
and has begun writing on top of it.

Use [the data-migration runbook](docs/runbooks/data-migration.md) to validate
the package, stage byte-preserved source extracts, create schemas/roles and
masked reporting views, verify database trust boundaries, bulk-load each dataset,
and run the verification passes.

The fixed-width readers, the Aurora bulk loader, the three verification passes and the
combined verification gate are implemented and are reachable as the `stage-dataset`,
`refresh-dataset`, `load-dataset`, `verify-row-counts`, `verify-checksum`,
`verify-money-parity`, `verify-row-count-report`, `verify-money-total-report` and
`verify-all` subcommands. The two report commands run the whole-migration row-count and
money-total reports on a session each proves is the read-only reporting role, and reduce
them to a process exit status a batch step can branch on. `verify-all` is the gate: it runs
all three passes in the fixed order 1, 2, 3, stops at the first pass that fails, offers no
option that could skip a pass, and reduces the result to one exit status -- which is what
the **cutover** verification step branches on, per
[`docs/runbooks/data-migration.md`](docs/runbooks/data-migration.md). The nightly chain
gates business processing differently and one state earlier: each `StageSeedDatasets`
branch runs the same three passes over the dataset it just loaded, and a failed branch
fails the `Map` and with it the chain, so no business state runs over data that does not
match its source. Its coverage is a `--manifest` when an operator supplies one and every dataset
that ships a committed extract when none is. `load-dataset` serves all **eleven** loadable
records, covering every seeded table across the eight schemas; the three columns that hold
ciphertext are sealed by the loader under the same key and in the same envelope framing the
owning service reads, so nothing is written in the clear and nothing is left for a service
to backfill.

The nightly chain does not invoke those commands one at a time. Its seed-refresh
state runs `refresh-dataset` once per dataset, which composes the fetch, the staging,
the load, all three verification passes and then one dataset-specific step — for the
transaction master, the sequence reconciliation below; for each of the three
reference datasets, the backup generation `app/jcl/DEFGDGD.jcl` creates from the same
sequential file the load reads — into one exit status a batch step can branch on. The
extracts it reads are the ones the runbook's `aws s3 sync` publishes under the
dataset bucket's source-extract prefix; nothing is mounted and no filesystem is
provisioned for them.

**Two steps sit between the last load and using the system: `reconcile-sequences` and
`refresh-card-identity`.** Both close the same class of hazard — a derived value
positioned by a migration that ran before the data existed — and each is a runbook step
of its own.

`ledger.transaction_id_seq` — the allocator the interactive transaction-add and
bill-payment paths draw from — has its starting position derived by its own Flyway
migration from the rows `ledger.transactions` held when that migration ran, which on
a cutover is none. Once the extract is loaded the allocator points into an occupied
range, so the first interactive write would fail on the primary key. `reconcile-sequences`
advances it past every loaded identifier, only ever forward, and is a no-op on a
deployment whose ledger was never loaded.

`reporting.card_identity` — the per-card identity relation every card-bearing reporting
projection joins, and what makes a per-card statement read an indexed one — is created
and backfilled by `data-migration/sql/V1__reporting_views.sql`, which on a cutover also
runs before the cross-reference is loaded. `refresh-card-identity` reconciles it with
`account.card_xref`, and it has to run before any statement or report run. Its omission
is the one failure in the cutover that reports nothing at all: a card absent from the
relation is absent from `reporting.v_card_xref`, so the statement run simply produces no
document for that cardholder and completes normally. The command is a delta insert plus a
delta delete, so a repeat run writes nothing.

Three conditions still gate a cutover, and the runbook's cutover-gate section numbers
each. **One:** the load must have resolved the keys of the environment the application
will run in, which no verification pass can confirm, because a row sealed under another
environment's key stores and verifies cleanly and fails to decrypt days later -- and, on
the same question of whether a load was performed against the right shape, an
`account.customers` loaded before the customer envelope framing was aligned must be
discarded rather than topped up, which a re-run of the load cannot do because no role this
package uses holds `DELETE` or `TRUNCATE`. **Two:** the gate reads eleven datasets and
compares ten of them; when a manifest is supplied the manifest fixes that population, so
confirm it declared every record the cutover loaded. Confirm as well that each pass
actually emitted its verdicts, which is a different question from whether it can: the
checksum pass over `customers` and `cards` needs the same key-management grant their loads
needed, and a session without it fails those two rather than skipping them. **Three:** `ledger.transactions`
loading zero rows is the correct result on a corpus-only run and the wrong one on a
cutover, which the pass output alone cannot distinguish. Schema and security validation
success must not be reported as a complete data migration, and neither must a partial
load.

Refactoring Rationale: condition two above used to read differently -- that the checksum
pass served three of the eleven records, so the evidence for the other eight was the
row-count and money-parity passes plus the two whole-schema queries. That reason is gone
rather than accepted: the pass could not digest the `BIGINT`, `DATE`, `SMALLINT`,
`TIMESTAMP` and `UUID` values a driver returns, and it now canonicalises by value class,
which lifted its reach from three records to every record that ships a committed
extract -- ten of the eleven, compared record by record. The eleventh, `ledger.transactions`,
ships no extract at all, so no per-dataset pass can be pointed at it and condition three is
how its result is read. The three sealed columns, which no digest can compare because
ciphertext differs on every write, are audited for well-formed envelopes instead, so
nothing in a loaded row is looked at by no pass at all. The condition is kept rather than
withdrawn, because the failure it guards against survived the fix in another form: a
manifest silently short of a dataset produces a green run over the datasets it does declare,
which reads as "the migration was verified". Only the obsolete reason is removed -- a gate
citing a resolved obstacle is read as a gate that can be ignored, and a gate deleted while
the runbook still numbers it is worse.

Refactoring Rationale: this section recorded cutover as closed because `CUSTOMER`
and `CARD` could not be loaded at all. That reason no longer exists, and leaving it
would have understated the delivery while hiding the two conditions that do still
apply — a gate citing a resolved obstacle is read as a gate that can be ignored.

### Invocation

The documented entry point is the module, invoked from the environment built
under `Build`:

```bash
# WHAT: list the datasets the package knows, and run the combined verification gate.
# WHY : Assumptions: `python -m carddemo_migration.cli` is THE documented form, which
#       data-migration/pyproject.toml states in the block declaring the console
#       script. The `carddemo-migrate` script exists so a container image can name one
#       entry point without hard-coding an interpreter, not as a second documented
#       interface -- naming both as equals is how two invocations drift apart.
data-migration/.venv/bin/python -m carddemo_migration.cli list-datasets
data-migration/.venv/bin/python -m carddemo_migration.cli verify-all --help
```

[`data-migration/README.md`](data-migration/README.md) is the authority for the
full subcommand list and each command's arguments.

### Two encoding invariants that decide whether a load is correct

**EBCDIC datasets are decoded per fixed-width field, never per record.** The
extracts are opened in binary mode and each field is decoded within its own
offset and length.

Assumptions: a record is not text. It interleaves character fields with zoned
decimals carrying a sign overpunch, packed-decimal nibbles and embedded low
values, so passing a whole record through a text decoder replaces exactly the
bytes that carry sign and magnitude — and it does so without raising, producing a
file that reads as almost right. Decoding per field is what keeps those bytes on
the numeric path. This is the single most likely silent defect in the whole data
migration, which is why the money-total pass above exists to catch it rather than
trusting the decode.

**Money is exact fixed point at every hop:** `NUMERIC(p,2)` in SQL,
`BigDecimal` at scale 2 in Java, `Decimal` in Python — and it is transported as a
**JSON string** on the wire.

Alternatives Considered: emitting money as a JSON number, which is the obvious
choice and is wrong here. Most clients parse a JSON number into an IEEE-754
double, which cannot represent every two-decimal value exactly, so exactness
would be destroyed at the one boundary the user actually sees — after having been
preserved carefully through the database, the service and the codec. A string
crosses that boundary intact and costs a parse the client was doing anyway.

## Validate

Two kinds of validation apply, and they answer different questions. The
target-stack gates ask whether the migrated code is correct on its own terms. The
parity oracle asks whether it behaves as the COBOL does. Passing the first without
the second proves only that a well-formed program was written.

### Target-stack gates

The same commands the `Build` section gives, gathered as one checklist:

- [ ] `services/` — the Maven reactor compiles, its unit and integration tests
      pass, and the Checkstyle documentation gate and the ArchUnit layering rules
      hold
- [ ] `ui/` — type-check, lint, component tests and a production build all pass
      from the lock file
- [ ] `data-migration/` — the `ruff` lint and docstring gate passes and the test
      suite passes from the package's own environment
- [ ] `infra/` — formatting, per-root validation, HCL lint and the generated-docs
      drift check all pass, provisioning nothing

Run the target-stack gates:

```bash
# WHAT: execute the Java, UI, Python, and Terraform static validation suites.
# WHY : Assumptions: each package has a language-specific gate, while the
#       combined run detects cross-package drift before review.
# WHY : Assumptions: the two Python gates run from `data-migration/.venv`, built under "Build"
#       above, and NOT from the repository `.venv`. The repository environment belongs to the COBOL
#       parity suite invoked further down this section; it holds a different `cryptography` pin and
#       ships no `ruff`, so `ruff check` from it fails as "No such file or directory" -- which reads
#       as a missing tool rather than as the wrong environment.
# WHY : Assumptions: the Lambda archives are built before the Terraform loop. Both `validate` and
#       `plan` evaluate `filebase64sha256` over the three archives, which are build output rather
#       than tracked files, so a fresh checkout fails `validate` on a path it cannot read.
mvn -B -f services/pom.xml clean verify
(cd ui && npm run typecheck && npm run lint && npm test && npm run build)
data-migration/.venv/bin/ruff check data-migration
data-migration/.venv/bin/python -m pytest -v --tb=short data-migration/tests
python3 infra/lambda/build_packages.py
terraform fmt -check -recursive infra/
for root in infra/bootstrap infra/envs/dev infra/envs/prod; do
  terraform -chdir="$root" init -backend=false -lockfile=readonly -input=false
  terraform -chdir="$root" validate
done
tflint --recursive --config="$(pwd)/infra/.tflint.hcl"
for dir in infra/bootstrap infra/modules/* infra/envs/dev infra/envs/prod; do
  terraform-docs --config infra/.terraform-docs.yml --output-check "$dir"
done
```

### Functional-parity verification against the COBOL oracle

The repository already contains the oracle this migration needs, so the method is
to use it rather than to invent one. Run it from the **repository root**:

```bash
# WHAT: install the oracle's hash-locked dependencies, then build and run all three
#       COBOL test layers with the optional S3 emulator.
# WHY : Assumptions: aggregate return code 4 is the documented green state; it
#       records known unsupported baseline programs and expected soft warnings.
# WHY : Assumptions: the install uses `--require-hashes` against the suite's own
#       pinned manifest, and this guide neither relaxes nor re-pins it. The suite's
#       golden comparisons are byte-deterministic, so a floating dependency could
#       change an output and turn the parity oracle into a source of false failures.
# WHY : Assumptions: this is the repository-root `.venv`, and NOT
#       `data-migration/.venv`. The two hold conflicting hash-locked `cryptography`
#       pins, as the "Build" section records, so the environments cannot be merged.
source .venv/bin/activate
pip install --require-hashes -r tests/requirements-test.txt
source scripts/test_env.sh
bash scripts/run_tests.sh --with-localstack
```

`bash scripts/run_tests.sh` is the one command an operator or a pipeline needs. It
performs five steps in order:

1. `source scripts/test_env.sh` — wire the GnuCOBOL `ASSIGN`-name bindings;
2. `bash scripts/build_test_programs.sh --with-tests` — compile the programs under
   test and the COBOL unit tests;
3. `bash scripts/run_unit_tests.sh` — layer 1, the COBOL unit tests;
4. `bash scripts/run_integration_tests.sh` — layer 2, single-program integration
   tests;
5. `bash scripts/run_e2e_tests.sh` — layer 3, the full-chain golden-master tests.

`--fail-fast` (or `-x`) stops at the first failing layer, `--with-localstack`
brings up the object-store emulator before the end-to-end layer, and `-h` prints
usage.

**Note**: the runners are the binding contract for invoking this suite. Where a
script and any documentation disagree — including this guide — **the script is
authoritative**.

#### The return-code rubric

The suite follows the mainframe condition-code convention so a pipeline receives
one deterministic status, and the runners **aggregate the worst (highest) code**
seen across all layers.

| RC | Meaning |
|:-----|:-------------------------------------------------------------------------|
| `0` | Pass — everything succeeded |
| `2` | Usage — a runner was invoked incorrectly; aborts immediately and does not enter aggregation |
| `4` | Warn or soft reject — a business-rule reject was correctly written, a layer collected no tests, or the optional emulator layer was unavailable |
| `8` | Fail — a test or a required build step failed |
| `16` | Fatal — an abend or unrecoverable error |

**An aggregate return code of 4 is the current green state. It is not a
regression introduced by this migration.**

Assumptions: the 4 originates in a pre-existing compile defect in the baseline
export and import programs, described under
[the documented divergences](#documented-divergences-from-the-baseline). That
defect is in reference-only source that must stay byte-identical, so no change in
this repository can clear it. Treating the 4 as a failure would leave the parity
oracle permanently red, and an oracle that is always red reports nothing — a real
regression arriving later would be indistinguishable from the standing warning.
The runners therefore classify that pair as known-unsupported and aggregate a soft
warning rather than poisoning the result.

#### The parity method

Run the COBOL pipeline to produce the golden outputs, run the equivalent Java job
over the migrated data, and compare after applying the same timestamp
normalisation. Four things are compared: the reject stream, the posted
transaction records, the updated master records, and the return code.
Business dates are injected as parameters in both systems rather than read from
the clock, so a rerun compares like with like.

Where the target **intentionally** differs, the difference is registered in
[`docs/architecture/cobol-to-service-traceability.md`](docs/architecture/cobol-to-service-traceability.md)
rather than absorbed silently. A difference that is not in that register is a
defect, not a decision.

The online programs are the exception, and not by choice: they use the CICS
command-level API and cannot be executed end to end without a CICS runtime, which
the runner does not have. Online parity is therefore verified per screen —
against field constraints taken from the copybook widths, function-key behaviour,
and message text reproduced character for character — rather than by golden
master.

### Acceptance criteria

- [ ] Each service builds and passes its tests
- [ ] `terraform apply` provisions the full stack cleanly, and `destroy` tears it
      down cleanly
- [ ] The candidate business flows work end to end: sign-on, account view and
      update, card list and update, transaction add and list, bill pay, the
      transaction-posting batch, and the three messaging extensions
- [ ] Every critical decision is documented in an architecture decision record

## Batch operations

Nightly execution is an EventBridge Scheduler cron trigger invoking the
`carddemo-daily-batch` Step Functions state machine, whose working states each run
a Fargate task. The chain replaces the JCL job stream one state at a time:

| State | Replaces |
|:-------------------------------|:-------------------------------------------------|
| `QuiesceOnlineWrites` | The operator command that closed the files to online updates |
| `StageSeedDatasets` | The dataset refresh block |
| `PreflightDailyTransactions` | The pre-posting program |
| `PostTransactions` | The posting job, with a reject-count branch that warns rather than fails |
| `CalculateInterest` | The interest-accrual job |
| `BackupTransactions` | The transaction backup job, exporting to a new generation |
| `CombineTransactions` | The sort-merge combine job, as ordered SQL |
| `GenerateStatements` | The statement generation jobs |
| `GenerateReports` | The transaction and category report jobs |
| `AnalyzeTables` | The index rebuild step, now a statistics refresh |
| `ResumeOnlineWrites` | The operator command that reopened the files |

[`docs/architecture/batch-orchestration.md`](docs/architecture/batch-orchestration.md)
carries the full job-to-state mapping, including the condition-code inversion and
the generation-dataset conventions.

### Invoking a run

```bash
# WHAT: submit one execution of the nightly chain with an explicit business date.
# WHY : Assumptions: the business date is a JOB PARAMETER and is never read from the
#       wall clock. A rerun exists precisely to reproduce a previous run's output, and
#       a clock read would make the rerun process a different date from the run it is
#       meant to reproduce -- so the one thing the operator is trying to verify is the
#       one thing that would change. The baseline injects the same date the same way
#       through its own job parameter, so this preserves the contract rather than
#       inventing one.
# WHY : Assumptions: the execution is NAMED. A generated name is an identifier that
#       appears only in this command's response, so an operator who loses the
#       response has to find their run among the schedule's by start time -- exactly
#       the identification problem a failure investigation needs already solved.
aws stepfunctions start-execution \
  --state-machine-arn "<state-machine-arn>" \
  --name "<execution-name>" \
  --input '{"businessDate":"<yyyy-mm-dd>"}'
```

### Restart

Two mechanisms combine. Step Functions redrive resumes a failed execution from
the state that failed rather than from the beginning. The durable per-step run
ledger gives each step an idempotency key, so a step that had already completed
before the failure is a no-op when the resumed execution reaches it.

Assumptions: both are needed, not either. Redrive alone would re-enter a state
whose work was already committed, and the ledger alone would still require the
whole chain to be resubmitted. The baseline had neither — its only restart
facility is a commented-out hint — so this is recorded as an improvement rather
than as a ported behaviour.

The chain opens and closes with a read-only quiesce and resume bracket,
implemented as flag steps in Parameter Store that the services honour. It stands
in for the operator commands that closed and reopened the files around the batch
window on the mainframe.

[`docs/runbooks/batch-operations.md`](docs/runbooks/batch-operations.md) is the
operating authority: scheduling, following an execution, redrive, and the
per-state failure procedures.

## Roll back and roll forward

Application rollback means redeploying the previously reviewed image digests
and restoring the corresponding database/object-store recovery point. Data
rollback must preserve exact money totals and never mix application and schema
versions.

### Roll forward

Rolling forward is a rolling ECS service deployment: publish the new image, then
re-apply the environment with the updated image digest, and the service replaces
its tasks in place. Blue-green and canary deployment are out of scope and no
traffic-shifting resources are provisioned, so there is no second environment or
weighted listener to reason about during a rollout.

### Teardown

Infrastructure removal follows [the teardown runbook](docs/runbooks/teardown.md):
destroy the selected environment first, preserve/empty protected versioned
buckets deliberately, and remove `infra/bootstrap` only after every dependent
environment and retained state object is gone.

```bash
# WHAT: destroy one environment, then the state backend, in that order.
# WHY : Assumptions: teardown order is the exact REVERSE of deploy order, and the
#       reason is where the state lives. Each environment root keeps its state in the
#       bucket and lock table that `infra/bootstrap` owns, so destroying the bootstrap
#       first deletes the only record of what the environment created. The live
#       resources would remain, unmanaged and no longer discoverable by Terraform,
#       and the only remaining route to removing them is finding each one by hand in
#       the console -- which is precisely the failure this ordering prevents.
ENVIRONMENT=dev
terraform -chdir="infra/envs/${ENVIRONMENT}" destroy
terraform -chdir=infra/bootstrap destroy
```

For `prod`, the deletion-protection and final-snapshot flags must be cleared
before a destroy will complete. Assumptions: those flags exist to make an
accidental production deletion fail rather than succeed, so a destroy that ran
through them without an explicit change would mean they were never protecting
anything. Clearing them is a deliberate, separately reviewed act, and the teardown
runbook treats it as its own step.

### Rolling back to the mainframe path

**Rolling back to the mainframe path requires no un-migration.**

Assumptions: the baseline is untouched and still runnable. The original programs,
copybooks, JCL, CICS definitions and seed data are exactly where they were, and
this migration added trees beside them rather than editing them — so there is no
change to reverse, no compatibility shim to remove, and no branch to unwind. The
mainframe path is reached by following [`README.md`](README.md) as it stands,
which is the same instruction it was before this work began.

Reversing a **data** cutover is a different question, and it is not symmetrical
with the code rollback above. Assumptions: by the time a reversal is considered,
the target database has accepted writes the baseline never saw, so there is no
state both systems agree on to return to — which is why this is a procedure with
its own preconditions rather than a command.
[`docs/runbooks/data-migration.md`](docs/runbooks/data-migration.md) owns that
procedure, including the money-total preservation requirement and the rule against
mixing application and schema versions.

## Architecture decisions

Each critical decision has its own record capturing the options considered, the
rationale, the **cost implications** and the risks and trade-offs.

Trade-offs: only the outcome is named here and the reasoning stays in the record.
A reader wanting to know *why* has to open one more file, which is the accepted
cost; the alternative is two copies of an argument that diverge the first time one
of them is revised, after which a reader cannot tell which copy is current.

| Decision | Outcome | Record |
|:-------------------------------|:-----------------------------------------------------|:----------|
| Language and runtime | Idiomatic rewrite in Java 21 with Spring Boot; no transpilation | [ADR-001](docs/adr/ADR-001-language-and-runtime.md) |
| Compute platform | ECS Fargate for the services; Step-Functions-invoked Fargate tasks for batch; Lambda for glue only | [ADR-002](docs/adr/ADR-002-compute-platform.md) |
| Datastore targets | Aurora PostgreSQL Serverless for record data; versioned S3 for dataset generations | [ADR-003](docs/adr/ADR-003-datastore-targets.md) |
| Messaging | Amazon SQS — FIFO for authorization request and reply, standard for inquiry | [ADR-004](docs/adr/ADR-004-messaging.md) |
| Batch orchestration | EventBridge Scheduler, Step Functions, and Spring Batch on Fargate | [ADR-005](docs/adr/ADR-005-batch-orchestration.md) |
| API and user interface | REST and JSON with OpenAPI behind an HTTP API; the BMS screens become a React SPA | [ADR-006](docs/adr/ADR-006-api-and-ui.md) |
| Service boundaries | Eight bounded contexts, schema per service | [ADR-007](docs/adr/ADR-007-service-boundaries.md) |
| Networking, security, identity | Three-zone VPC with an isolated data tier; Cognito; KMS and Secrets Manager; least-privilege IAM | [ADR-008](docs/adr/ADR-008-security-and-identity.md) |
| Infrastructure-as-code tool | Terraform with a pinned AWS provider | [ADR-009](docs/adr/ADR-009-iac-tool.md) |

[`docs/adr/README.md`](docs/adr/README.md) is the index.

## Documented divergences from the baseline

The migration targets functional parity, and **every intentional behavioural
difference is documented rather than silently introduced**. The COBOL is never
edited — not even to fix a defect it demonstrably has. The Java implements correct
behaviour, the baseline keeps its behaviour, and the gap between them is
registered in
[`docs/architecture/cobol-to-service-traceability.md`](docs/architecture/cobol-to-service-traceability.md).

Assumptions: this is the only arrangement that keeps the oracle usable. Fixing a
defect in the COBOL would change the golden outputs, and the golden outputs are
the standard the migration is measured against — so the fix would move the
standard and no comparison afterwards would mean anything.

**One — the export and import record key.** `app/cbl/CBEXPORT.cbl` line 68 and
`app/cbl/CBIMPORT.cbl` line 40 each declare a file `RECORD KEY` naming a field
that exists only in `WORKING-STORAGE`, by way of the export copybook, and not in
the file record. No compiler flag can reconcile that, so the pair does not compile
under the open-source compiler and only **ten of the twelve** batch programs
build. This is the origin of the permanent soft warn return code of 4, and the
corresponding integration test is skipped with that reason recorded. The Java
implements the correct keying.

**Two — the statement-generator table limits.** The statement program has **two
independent** unchecked tables. A single card renders up to **512** transactions
and the **513th** overruns the inner same-card table. Up to **51 distinct cards**
render and the **52nd** overruns the outer card table. **There is no single "~51
transactions" limit — that figure conflates the two thresholds** and mislabels a
same-card overrun as a transaction-count problem. The Java implementation has no
fixed arity, so neither boundary exists in the target.

**Three — the interest final-account flush.** The interest program omits the
flush for the final account it processes. The Java flushes correctly, so the last
account is treated exactly like every other one.

A fourth item is a limitation rather than a defect and is worth stating alongside
them: the online programs use the CICS command-level API and cannot be run end to
end without a CICS runtime, which is why online parity is verified per screen as
described under [`Validate`](#validate).

## Code documentation convention

All newly authored code must document both what it does **and why particular
implementation decisions were made**. The convention is defined in
[`docs/CODE_DOCUMENTATION_STANDARD.md`](docs/CODE_DOCUMENTATION_STANDARD.md) and
required by [`CONTRIBUTING.md`](CONTRIBUTING.md).

It is enforced mechanically rather than by review habit: Checkstyle for Java,
bound to the Maven `validate` phase so it runs on every local build; an ESLint
JSDoc plugin for TypeScript; the pydocstyle rule family for Python; and TFLint
together with generated module documentation for HCL, which has no docstring
construct of its own. A repository-wide gate additionally holds the four
rationale labels to one canonical written form. All of these run as required
continuous-integration steps.

`app/**` is reference-only and is **not** retrofitted with any of this — the
baseline predates the convention and must stay byte-identical. `tests/**` already
satisfies the equivalent convention, which this migration extends to the new
polyglot trees rather than inventing from nothing.

## Known limitations and troubleshooting

**The `dev` database can scale to zero, and the first query after it does pays for
that.** A paused cluster must resume before it serves anything, so the first
request following an idle period waits for the resume. Assumptions: that is
acceptable in `dev`, where the cluster is idle far more than it is busy and the
cost of holding capacity is paid continuously for a benefit nobody is waiting on.
It is not acceptable in `prod`, where the minimum capacity is held above zero so
no user-facing request ever pays a resume.

**The topology is single-region across three availability zones.** Multi-region
and disaster-recovery topology are out of scope, so there is no standby region,
no cross-region replication and no failover procedure to invoke.

**There is no caching tier and no read replica**, and both absences are
deliberate. The baseline has no cache, so adding one would introduce an
invalidation problem that parity does not require solving. Reporting reads go to
the writer through read-only cross-schema views, which avoids reasoning about
replica lag in a comparison that has to be exact. See
[ADR-003](docs/adr/ADR-003-datastore-targets.md).

**Reply-message expiry has no direct equivalent.** The baseline sets a short
expiry on a reply message, and the target queue service has no per-message
time-to-live. The resolution is an `expiresAt` attribute the consumer honours by
dropping and logging a stale message, backed by short retention on the reply
queues. Recorded in [ADR-004](docs/adr/ADR-004-messaging.md).

**If the oracle's `pip install` is blocked**, the interpreter is very likely a
PEP 668 externally-managed system Python, which refuses a direct install.
[`tests/README.md`](tests/README.md) section 3 documents the supported route,
including recreating the virtual environment when its pip seed is absent. Follow
that document rather than installing into the system interpreter, and do not
alter the suite's pinned manifest.

**If a Terraform `validate` fails on a path it cannot read**, the Lambda archives
have not been built. They are build output rather than tracked files, and both
`validate` and `plan` hash them, so a fresh checkout must build them first — the
`Validate` section's command sequence does this before the Terraform loop for
exactly that reason.

## Further reading

The baseline and its oracle:

- [`README.md`](README.md) — the mainframe application, its transaction and job
  inventories, and how to run it unchanged
- [`tests/README.md`](tests/README.md) — the three-layer COBOL suite that is the
  functional-parity oracle
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — contribution conventions, including the
  explainability requirement

Architecture and decisions:

- [Architecture diagrams](docs/architecture/context-and-container-diagrams.md)
- [Service catalogue](docs/architecture/service-catalog.md)
- [Data mapping](docs/architecture/data-model-and-schema-mapping.md)
- [Batch orchestration](docs/architecture/batch-orchestration.md)
- [Traceability and divergences](docs/architecture/cobol-to-service-traceability.md)
- [ADRs](docs/adr/README.md)
- [Documentation standard](docs/CODE_DOCUMENTATION_STANDARD.md)

Operating procedures:

- [Deploy](docs/runbooks/deploy.md)
- [Teardown](docs/runbooks/teardown.md)
- [Data migration](docs/runbooks/data-migration.md)
- [Batch operations](docs/runbooks/batch-operations.md)

Per-package guides:

- [`infra/README.md`](infra/README.md) — the modules, the environment roots and
  the state backend
- [`ui/README.md`](ui/README.md) — the single-page application's screens, scripts
  and environment variables
- [`data-migration/README.md`](data-migration/README.md) — the extract readers,
  the loaders and the verification passes
- `services/<service>/README.md` — each service's build, run, variables and
  endpoints
