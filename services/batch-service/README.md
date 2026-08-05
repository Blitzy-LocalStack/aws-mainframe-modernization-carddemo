# Batch Service

> **Purpose.** Build and run the one-shot Spring Batch jobs invoked by the
> CardDemo Step Functions workflows.
>
> **Source of truth.** `CBTRN01C`, `CBTRN02C`, `CBACT04C`, `CBEXPORT`,
> `CBIMPORT`, the corresponding JCL, and `services/batch-service/**`.

This module is a task, not an HTTP server. It intentionally has no web or
Actuator starter; the process exit state is the orchestrator contract.

## Build and Test

```bash
# WHAT: build the executable batch archive and run its tests.
# WHY : Assumptions: common-lib and the reactor architecture rules are required
#       dependencies, so the module is built with -am.
mvn -B -f services/pom.xml -pl batch-service -am package
```

## Run Locally

```bash
# WHAT: run one deterministic posting job against the TLS-enabled local database.
# WHY : Assumptions: the business date is injected and the CA path verifies the
#       local server identity; neither is read from the wall clock or disabled.
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo \
SPRING_DATASOURCE_PASSWORD=carddemo \
AWS_REGION=us-east-1 \
CARDDEMO_DB_SSL_ROOT_CERT=/opt/carddemo-pg-certs/ca.pem \
java -jar services/batch-service/target/batch-service-1.0.0-SNAPSHOT.jar \
  --job=post-transactions --business-date=2022-07-18
```

Use [the batch operations runbook](../../docs/runbooks/batch-operations.md) for
deployed execution, inspection, and redrive.
