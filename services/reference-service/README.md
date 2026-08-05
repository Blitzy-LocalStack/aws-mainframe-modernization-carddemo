# Reference Service

> **Purpose.** Document transaction-type/category, disclosure-group, lookup,
> and date-conversion ownership.
>
> **Source of truth.** `COTRTLIC`, `COTRTUPC`, `COBTUPDT`, `CODATE01`,
> `CSUTLDTC`, and `services/reference-service/**`.

The reference schema preserves the transaction-type/category restrict
constraint and the mandatory `DEFAULT` disclosure-group fallback.

## Build and Test

```bash
# WHAT: compile and test the reference module with common validation code.
# WHY : Assumptions: date editing and exact reference-data contracts cross the
#       module/common-lib boundary and must be built together.
mvn -B -f services/pom.xml -pl reference-service -am test
```

The current module does not contain a Spring Boot application entry point. The
schema migrations and package contracts remain build-validated.
