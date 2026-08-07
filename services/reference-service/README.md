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

The entry point is `com.carddemo.reference.ReferenceApplication` and the filter
chain is `com.carddemo.reference.config.SecurityConfig`, which splits
authorization by HTTP method: reads are available to any authenticated caller
because every other context reads the seeded lookup rows to validate an address,
while `POST`, `PUT`, `PATCH` and `DELETE` require the `carddemo-admin` authority.

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
# WHY : Assumptions: AWS_REGION is required to START, not merely to reach AWS. This module declares the
#       SQS starter for the date-conversion inquiry consumer, and the SQS client bean is built as the
#       context is constructed, so a context with no region enters the SDK resolution chain and waits on
#       instance metadata discovery before failing. Credentials are NOT needed to start, because they
#       resolve on first call.
# WHY : Assumptions: three further variables have no fallback in `application.yml` and are deliberately
#       absent from this command -- CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE, its reply counterpart and
#       its error counterpart. They are read by configuration-property binding, which leaves an
#       unresolvable placeholder as literal text rather than raising, so the process starts without them
#       and a listener would fail only when it resolved its queue. A deployment sets all three from
#       Terraform outputs.
mvn -B -f services/pom.xml -pl reference-service -am package

SERVER_SSL_ENABLED=false \
AWS_REGION=us-east-1 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_reference \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
java -jar services/reference-service/target/reference-service-1.0.0-SNAPSHOT.jar
```
