# Auth Service

> **Purpose.** Document the bounded context replacing sign-on and user
> administration.
>
> **Source of truth.** `COSGN00C` and `COUSR00C` through `COUSR03C`, together
> with `services/auth-service/**`.

The service delegates credential storage and comparison to Cognito. It receives
the sign-on credential transiently over TLS, does not persist or log it, and
maps the invariant `carddemo-admin` / `carddemo-user` group contract to
authorities.

## Build and Test

```bash
# WHAT: compile this module and every required shared dependency.
# WHY : Assumptions: the shared JWT, error, and architecture contracts live in
#       common-lib and must be tested in the same reactor.
mvn -B -f services/pom.xml -pl auth-service -am test
```

Configuration lives under `src/main/resources/application*.yml`; secrets are
resolved through ECS Secrets Manager selectors. The current module source does
not contain a Spring Boot application entry point, so this README does not
claim a standalone local HTTP launch command.
