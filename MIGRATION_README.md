# CardDemo Migration Guide

> **Purpose.** Provide the top-level operator and developer path for the
> additive Linux/AWS migration: build, deploy, run, migrate data, validate, and
> roll back.
>
> **Source of truth.** The AAP, the package READMEs, `docs/architecture/**`,
> `docs/runbooks/**`, and the immutable baseline under `app/**`.

The baseline remains available and unchanged. The target trees are additive:
`services/`, `ui/`, `data-migration/`, and `infra/`.

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
#       built under "Migrate Data" below and is invoked from it by path, so it is deliberately not
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

Operate the nightly and ad-hoc workflows through
[the batch runbook](docs/runbooks/batch-operations.md).

For local UI development:

```bash
# WHAT: start the Vite development server from the authored UI package.
# WHY : Assumptions: VITE_API_BASE_URL points at an approved API endpoint and
#       contains no secret.
cd ui
npm run dev
```

## Migrate Data

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

## Validate

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

Run the immutable parity oracle separately:

```bash
# WHAT: build and run all three COBOL test layers with the optional S3 emulator.
# WHY : Assumptions: aggregate return code 4 is the documented green state; it
#       records known unsupported baseline programs and expected soft warnings.
source scripts/test_env.sh
bash scripts/run_tests.sh --with-localstack
```

## Roll Back

Application rollback means redeploying the previously reviewed image digests
and restoring the corresponding database/object-store recovery point. Data
rollback must preserve exact money totals and never mix application and schema
versions.

Infrastructure removal follows [the teardown runbook](docs/runbooks/teardown.md):
destroy the selected environment first, preserve/empty protected versioned
buckets deliberately, and remove `infra/bootstrap` only after every dependent
environment and retained state object is gone.

The untouched `app/**` baseline remains available as the original execution
path; no "un-migration" edit to baseline source is required.

## Reference Map

- [Architecture diagrams](docs/architecture/context-and-container-diagrams.md)
- [Service catalogue](docs/architecture/service-catalog.md)
- [Data mapping](docs/architecture/data-model-and-schema-mapping.md)
- [Traceability and divergences](docs/architecture/cobol-to-service-traceability.md)
- [ADRs](docs/adr/README.md)
- [Documentation standard](docs/CODE_DOCUMENTATION_STANDARD.md)
