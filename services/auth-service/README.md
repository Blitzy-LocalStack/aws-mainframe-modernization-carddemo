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
resolved through ECS Secrets Manager selectors. The entry point is
`com.carddemo.auth.AuthApplication` and the filter chain is
`com.carddemo.auth.config.SecurityConfig`, which permits the sign-on operation
without a token -- it is the operation that issues one -- restricts every
user-administration path to the `carddemo-admin` authority, and installs the
token-kind, client and scope checks the issuer location alone does not make.

## Run

```bash
# WHAT: build the bootable jar for this module, then start it.
# WHY : Assumptions: the jar produced by `spring-boot:repackage` is self-contained, so no class path is
#       assembled at launch. The two datasource variables and the issuer location have NO defaults in
#       `application.yml`, so an incomplete environment stops at startup naming the missing key rather
#       than serving requests bound to nothing.
# WHY : Assumptions: CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID has no fallback either. It is the app
#       client a presented token must name, and `config/SecurityConfig.java` refuses to start without it
#       -- a blank value would make the shared validator skip that check silently.
# WHY : Assumptions: SERVER_SSL_ENABLED=false is set for a LOCAL run only. `application.yml` enables
#       TLS and takes the certificate and private key from CARDDEMO_SERVER_TLS_CERTIFICATE and
#       CARDDEMO_SERVER_TLS_PRIVATE_KEY as PEM CONTENT with no fallback, because the deployed target
#       group speaks HTTPS to the task. Neither value exists on a developer machine, and the process
#       would fail while trying to read the unresolved placeholder as key material. Alternatives
#       Considered: generating a self-signed pair for the loopback hop -- rejected because it puts
#       private-key material into a shell history for a hop that leaves no host, and the certificate
#       name would not match localhost anyway. Deployment sets neither of these two variables by hand:
#       the task definition injects both from Secrets Manager, and TLS stays enabled there.
# WHY : Assumptions: three further variables have no fallback in `application.yml` and are deliberately
#       absent from this command -- CARDDEMO_AUTH_COGNITO_USER_POOL_ID, CARDDEMO_AUTH_COGNITO_CLIENT_ID
#       and CARDDEMO_AUTH_COGNITO_CLIENT_SECRET. They are read by configuration-property binding, which
#       leaves an unresolvable placeholder as literal text rather than raising, so the process starts
#       without them and fails only on a sign-on attempt. The last of the three is a SECRET and must
#       never be typed on a command line: the task definition resolves it from Secrets Manager, and a
#       local sign-on test supplies it through an environment file that is not committed.
mvn -B -f services/pom.xml -pl auth-service -am package

SERVER_SSL_ENABLED=false \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_auth \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
java -jar services/auth-service/target/auth-service-1.0.0-SNAPSHOT.jar
```
