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
#       TLS and reads its listener material from a PKCS#12 keystore whose password placeholder,
#       CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD, has no fallback, because the deployed target group
#       speaks HTTPS to the task. No keystore exists on a developer machine, so the process would
#       fail while trying to open one.
# WHY : Refactoring Rationale: this note previously said the certificate and private key arrive as
#       PEM CONTENT in CARDDEMO_SERVER_TLS_CERTIFICATE and CARDDEMO_SERVER_TLS_PRIVATE_KEY,
#       injected by the task definition from Secrets Manager. That is no longer how the material
#       exists, and the arrangement it described carried a defect: the pair was produced by a
#       managed `tls_private_key` resource in each environment root, so ONE private key was written
#       into Terraform state and then injected into every online task. Deployment now injects
#       neither variable, because there is nothing to inject -- the image entry point,
#       `config/docker/generate-listener-material.sh`, mints THIS task's own key pair and
#       self-signed certificate with keytool before the JVM starts, onto the task's encrypted
#       ephemeral volume. TLS stays enabled in deployment, and no listener private key reaches
#       Terraform state, Secrets Manager or an image layer.
# WHY : Alternatives Considered: running that entry point locally so a local run also speaks TLS.
#       Rejected for the reason the previous note gave for rejecting a hand-made pair, plus one
#       more: the certificate would not match `localhost` for any caller that verified it, and
#       disabling TLS for the loopback hop states plainly that a local run does not exercise the
#       deployed transport rather than appearing to.
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
