# Card Service

> **Purpose.** Document card list, detail, update, masking, and by-account access.
>
> **Source of truth.** `COCRDLIC`, `COCRDSLC`, `COCRDUPC`, `CBACT02C`, and
> `services/card-service/**`.

Browser and API routes use opaque card identifiers rather than PANs. CVV data is
never serialized, and PANs are masked outside the narrowly authorized
administrative detail contract.

## Build and Test

```bash
# WHAT: compile and test the card module with its shared dependencies.
# WHY : Assumptions: cursor sealing, opaque identifiers, and money serialization
#       are common-lib contracts exercised with this module.
mvn -B -f services/pom.xml -pl card-service -am test
```

Environment configuration is in `src/main/resources/application*.yml`. The
current module does not contain a Spring Boot application entry point, so no
standalone server launch is documented.
