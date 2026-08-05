# Reporting Service

> **Purpose.** Document transaction-report, statement, and ad-hoc report
> ownership.
>
> **Source of truth.** `CORPT00C`, `CBTRN03C`, `CBSTM03A`, `CBSTM03B`, and
> `services/reporting-service/**`.

The service reads only masked security-barrier views through a SELECT-only
database role. It omits CVV and source-table access, validates bearer-token
kind/client/scope, and uses verified database TLS.

## Build and Test

```bash
# WHAT: compile and test the reporting module with common formatting and security contracts.
# WHY : Assumptions: fixed-width edit masks, sanitized statement DTO rendering,
#       and token validation cross module boundaries.
mvn -B -f services/pom.xml -pl reporting-service -am test
```

Runtime configuration is in `src/main/resources/application.yml`. The current
module does not contain a Spring Boot application entry point, so no standalone
HTTP launch command is claimed. Ad-hoc workflow operation is documented in
[the batch runbook](../../docs/runbooks/batch-operations.md).
