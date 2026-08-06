# Transaction Service

> **Purpose.** Document transaction list/detail/add and bill-payment ownership.
>
> **Source of truth.** `COTRN00C`, `COTRN01C`, `COTRN02C`, `COBIL00C`, and
> `services/transaction-service/**`.

Transactions use exact fixed-point amounts and authenticated, context-bound
keyset cursors. Database TLS uses peer verification; detailed health diagnostics
are isolated from the unauthenticated probe surface.

## Build and Test

```bash
# WHAT: compile and test the transaction module and architecture rules.
# WHY : Assumptions: the module-level test run checks service packaging plus the
#       common no-float and no-cross-domain-import constraints.
mvn -B -f services/pom.xml -pl transaction-service -am test
```

Runtime profiles are under `src/main/resources/application*.yml`. The entry point
is `com.carddemo.transaction.TransactionApplication` and the filter chain is
`com.carddemo.transaction.config.SecurityConfig`. These are the operations whose
misuse moves money, so the chain admits nothing unauthenticated except the health
probe and the decoder refuses any token that is not an access token minted for the
configured app client.

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
mvn -B -f services/pom.xml -pl transaction-service -am package

SERVER_SSL_ENABLED=false \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_ledger \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
java -jar services/transaction-service/target/transaction-service-1.0.0-SNAPSHOT.jar
```
