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

Environment configuration is in `src/main/resources/application*.yml`. The entry
point is `com.carddemo.card.CardApplication` and the filter chain is
`com.carddemo.card.config.SecurityConfig`, which restricts the `/api/v1/admin/**`
prefix -- the only place the unnarrowed primary account number is served -- to the
`carddemo-admin` authority.

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
#       variable by hand: the task definition injects both from Secrets Manager, and TLS stays enabled --
#       which matters more on this module than most, since the rows behind it are primary account numbers
#       and card verification values.
mvn -B -f services/pom.xml -pl card-service -am package

SERVER_SSL_ENABLED=false \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_card \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
java -jar services/card-service/target/card-service.jar
```
