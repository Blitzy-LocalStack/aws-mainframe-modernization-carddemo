# Authorization Service

> **Purpose.** Document pending-authorization summary/detail, fraud marking,
> request consumption, outbox reply publication, purge, load, and unload.
>
> **Source of truth.** The eight programs under
> `app/app-authorization-ims-db2-mq/cbl/**` and
> `services/authorization-service/**`.

Authorization requests preserve the CSV field contract while queue metadata
uses opaque HMAC tokens instead of PANs. Database changes and the outbox row
commit atomically.

## Build and Test

```bash
# WHAT: compile and test the authorization module with shared codec/security contracts.
# WHY : Assumptions: CSV bounds, control-character rejection, opaque group ids,
#       and error redaction are common-lib behaviors required by this service.
mvn -B -f services/pom.xml -pl authorization-service -am test
```

The current module does not contain a Spring Boot application entry point or
runtime YAML. No standalone listener/consumer command is claimed.
