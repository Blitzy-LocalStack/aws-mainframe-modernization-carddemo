# CardDemo Migration Guide

> **Purpose.** Provide the top-level operator and developer path for the
> additive Linux/AWS migration: build, deploy, run, migrate data, validate, and
> roll back.
>
> **Source of truth.** The AAP, the package READMEs, `docs/architecture/**`,
> `docs/runbooks/**`, and the immutable baseline under `app/**`.

The baseline remains available and unchanged. The target trees are additive:
`services/`, `ui/`, `data-migration/`, and `infra/`.

Assumptions: commands run from the repository root after
`. /etc/profile.d/00-carddemo-toolchain.sh`; Python commands also activate the
repository `.venv`.

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

```bash
# WHAT: validate and package the Python migration support code.
# WHY : Assumptions: the hash-locked development manifest reproduces the CI
#       versions of runtime, lint, and test dependencies.
source .venv/bin/activate
python -m pip install --require-hashes -r data-migration/requirements-dev.txt
ruff check data-migration
python -m compileall -q data-migration/src
python -m pytest -v --tb=short data-migration/tests
```

## Deploy

Follow [the deployment runbook](docs/runbooks/deploy.md). The order is:

1. validate the repository and build immutable artifacts;
2. apply `infra/bootstrap`;
3. configure the selected environment backend;
4. review and apply the environment plan;
5. publish the SPA and verify service health.

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
ENVIRONMENT=dev
terraform -chdir="infra/envs/${ENVIRONMENT}" output -raw cloudfront_domain_name
terraform -chdir="infra/envs/${ENVIRONMENT}" output -raw api_endpoint_url
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

The fixed-width readers, the Aurora bulk loader and the three verification passes
are implemented and are reachable as the `load-dataset`, `verify-row-counts`,
`verify-checksum` and `verify-money-parity` subcommands. `load-dataset` serves all
**ten** loadable records, covering every seeded table across the eight schemas; the
three columns that hold ciphertext are sealed by the loader under the same key and
in the same envelope framing the owning service reads, so nothing is written in the
clear and nothing is left for a service to backfill.

Two conditions still gate a cutover, and the runbook's cutover-gate section states
both: the load must have resolved the keys of the environment the application will
run in, which no verification pass can confirm; and the checksum pass serves three
of the ten records today, so the evidence for the other seven is the row-count and
money-parity passes plus the two whole-schema queries. Schema and security
validation success must not be reported as a complete data migration, and neither
must a partial load.

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
mvn -B -f services/pom.xml clean verify
(cd ui && npm run typecheck && npm run lint && npm test && npm run build)
source .venv/bin/activate
ruff check data-migration
python -m pytest -v --tb=short data-migration/tests
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
