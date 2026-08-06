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

Runtime configuration is in `src/main/resources/application.yml`. The entry point
is `com.carddemo.reporting.ReportingApplication`. Authorization is split across two
classes in `config/`: `JwtDecoderConfig` owns the decoder and its token-kind,
client and scope checks, and `SecurityConfig` owns the filter chain and the
group-to-authority conversion. Declaring a second decoder in the latter would make
which one the resource server used depend on bean ordering. Ad-hoc workflow
operation is documented in
[the batch runbook](../../docs/runbooks/batch-operations.md).

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
#       private-key material into a shell history for a hop that leaves no host. Deployment sets neither
#       variable by hand: the task definition injects both from Secrets Manager, and TLS stays enabled.
# WHY : Assumptions: two further variables have no fallback in `application.yml` and are deliberately
#       absent from this command -- CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN and
#       CARDDEMO_REPORTING_S3_OUTPUT_BUCKET. Both are read by configuration-property binding, which
#       leaves an unresolvable placeholder as literal text rather than raising, and the service classes
#       that will consume them are authored at a later index of the same plan, so the process starts
#       without them today. Both are also NETWORK LOCATIONS rather than selectors, so a fabricated value
#       is worse than an absent one: an unresolved placeholder cannot be mistaken for a real state
#       machine or bucket. A deployment sets both from Terraform outputs.
mvn -B -f services/pom.xml -pl reporting-service -am package

SERVER_SSL_ENABLED=false \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_reporting \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
java -jar services/reporting-service/target/reporting-service-1.0.0-SNAPSHOT.jar
```
