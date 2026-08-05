# Transaction Service

> **Purpose.** Document transaction list/detail/add and bill-payment ownership.
>
> **Source of truth.** `COTRN00C`, `COTRN01C`, `COTRN02C`, `COBIL00C`, and
> `services/transaction-service/**`.

Transactions use exact fixed-point amounts and authenticated, context-bound
keyset cursors. Database TLS uses peer verification; detailed health diagnostics
are isolated from the unauthenticated probe surface.

## Build and Test

```bash
# WHAT: compile and test the transaction module and architecture rules.
# WHY : Assumptions: the module-level test run checks service packaging plus the
#       common no-float and no-cross-domain-import constraints.
mvn -B -f services/pom.xml -pl transaction-service -am test
```

Runtime profiles are under `src/main/resources/application*.yml`. The current
module does not contain a Spring Boot application entry point, so no standalone
server launch is documented.
