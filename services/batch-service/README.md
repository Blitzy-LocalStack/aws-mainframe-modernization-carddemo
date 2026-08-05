# Batch Service

> **Purpose.** Build and run the one-shot Spring Batch jobs invoked by the
> CardDemo Step Functions workflows.
>
> **Source of truth.** `CBTRN01C`, `CBTRN02C`, `CBACT04C`, `CBEXPORT`,
> `CBIMPORT`, the corresponding JCL, and `services/batch-service/**`.

This module is a task, not a business HTTP service. Its only HTTP surface is
`/actuator/health` on port `8080`, used by the container health check while a job
is running. There is no controller, administrative trigger, OpenAPI contract or
security configuration; Step Functions is the only invocation path, and the
process exit status is the orchestration contract.

## Invocation Contract

`--job=<name>` selects one of:

- `preflight-daily-transactions`
- `post-transactions`
- `calculate-interest`
- `backup-transactions`
- `combine-transactions`
- `export`
- `import`

`--business-date=<token>` supplies the required ten-character business-date
token. The orchestrated API uses the ISO `yyyy-MM-dd` shape, while the compact
`yyyyMMdd00` form remains accepted and is forwarded verbatim because both forms
occur in the committed parity fixtures. Neither argument has an image default.

| Exit status | Meaning |
|---|---|
| `0` | Clean completion. |
| `4` | Completed with rejects; this is the soft-warn tier. |
| `>= 8` | Argument, infrastructure, I/O or job failure. |

`app/cbl/CBTRN02C.cbl:228` fixes the observable counter spelling as
`TRANSACTIONS REJECTED  :` with two spaces before the colon. Lines 229-230
produce status `4` when rejects exist. `app/jcl/TRANBKP.jcl:51` uses
`COND=(4,LT)`, a JCL skip predicate, so the equivalent Step Functions run
predicate is `rc <= 4`.

## Build and Test

```bash
# WHAT: build the common library and batch module, then run their unit and
#       integration gates.
# WHY : Assumptions: common-lib and the reactor architecture rules are required
#       dependencies, so the module is selected with -am and the binary Maven
#       gate remains separate from the container's numeric exit-status rubric.
mvn -B -f services/pom.xml -pl common-lib,batch-service -am clean verify
```

The Docker build must run from the repository root because the image compiles
`common-lib` from source and the inherited Checkstyle gate reads the root
`config/checkstyle` directory:

```bash
# WHAT: build the non-root batch image from the repository-root context.
# WHY : Assumptions: the Dockerfile's COPY paths and the unpublished sibling
#       dependency require this exact context and file path.
docker build --file services/batch-service/Dockerfile \
  --tag carddemo-batch-service:validation .
```

## Run Locally

```bash
# WHAT: run one deterministic posting job after the required datasource, region
#       and trust-anchor environment variables have been supplied externally.
# WHY : Assumptions: the business date is injected rather than read from the
#       wall clock, and no endpoint or credential is committed in this document.
java -jar services/batch-service/target/batch-service-1.0.0-SNAPSHOT.jar \
  --job=post-transactions --business-date=2022-07-18
```

Use [the batch operations runbook](../../docs/runbooks/batch-operations.md) for
deployed execution, inspection, and redrive.
