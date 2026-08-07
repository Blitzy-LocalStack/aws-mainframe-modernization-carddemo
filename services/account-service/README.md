# Account Service

> **Purpose.** Document account, customer, card cross-reference, update, and
> inquiry ownership.
>
> **Source of truth.** `COACTVWC`, `COACTUPC`, `CBACT01C`, `CBACT03C`,
> `CBCUS01C`, `COACCT01`, and `services/account-service/**`.

The account schema owns accounts, customers, and card cross-reference access.
Updates preserve the baseline's before-image concurrency intent through the
target version contract.

## Build and Test

```bash
# WHAT: compile and test the account module with common-lib.
# WHY : Assumptions: account DTOs and services depend on shared exact-money,
#       validation, and error contracts.
mvn -B -f services/pom.xml -pl account-service -am test
```

The entry point is `com.carddemo.account.AccountApplication` and the filter chain
is `com.carddemo.account.config.SecurityConfig`. No route of this context is
administrator-only: the baseline reaches account view and account update from the
main menu, which both user types reach, so restricting either would remove a
capability an ordinary user has today.

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
#       SQS starter for the account-inquiry consumer, and the SQS client bean is built as the context is
#       constructed, so a context with no region enters the SDK resolution chain and waits on instance
#       metadata discovery before failing. Credentials are NOT needed to start, because they resolve on
#       first call.
# WHY : Assumptions: three further variables have no fallback in `application.yml` and are deliberately
#       absent from this command -- CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE, its reply counterpart and its
#       error counterpart. They are read by configuration-property binding, which leaves an unresolvable
#       placeholder as literal text rather than raising, and the consumer that will use them is authored
#       at a later index of the same plan, so the process starts without them today. A deployment sets
#       all three from Terraform outputs.
# WHY : Assumptions: at THIS checkpoint the command below does not reach a listening state, and saying so
#       is the point of this note. `service/AddressValidationService` is annotated `@Service` and takes
#       its nested `ReferenceAddressLookup` port through its constructor; that port is the seam to the
#       three allow-list tables `reference-service` owns, and the adapter that implements it is authored
#       at a later index of the same plan. Context refresh therefore ends in
#       `UnsatisfiedDependencyException` naming `ReferenceAddressLookup`, and it does so AFTER every
#       setting in `application.yml` has been applied -- verified by running it: the pool opens its
#       connections with verified TLS and Flyway reports zero migrations before the failure arrives at
#       bean wiring. The command is documented now because it is the command that will work unchanged
#       once that adapter lands, and because a reader who meets the failure should not spend time on the
#       configuration, which is not implicated.
mvn -B -f services/pom.xml -pl account-service -am package

SERVER_SSL_ENABLED=false \
AWS_REGION=us-east-1 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_account \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
java -jar services/account-service/target/account-service-1.0.0-SNAPSHOT.jar
```
