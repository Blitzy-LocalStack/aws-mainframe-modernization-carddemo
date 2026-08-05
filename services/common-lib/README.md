# Common Library

> **Purpose.** Document the shared security, money, codec, validation,
> observability, and web contracts consumed by every CardDemo service.
>
> **Source of truth.** `services/common-lib/src/main/java/**`, the normative
> copybooks under `app/cpy/**`, and Rule T1 through T10 in the AAP.

`common-lib` is a library, not a deployable process. It intentionally has no
Spring Boot application entry point or container image.

## Build and Test

```bash
# WHAT: compile the library, run codec/security tests, and publish its test-jar architecture rules.
# WHY : Assumptions: service modules consume both the main jar and the test
#       artifact, so the module must be built before a dependent service test.
mvn -B -f services/pom.xml -pl common-lib -am test
```

Money is `BigDecimal` scale two and serializes as a JSON string. Cursor and
opaque-identifier tokens require secret-keyed HMAC material and never expose
raw keys or PANs.
