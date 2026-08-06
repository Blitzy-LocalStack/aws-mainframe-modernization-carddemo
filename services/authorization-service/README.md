# Authorization Service

> **Purpose.** Document pending-authorization summary/detail, fraud marking,
> request consumption, outbox reply publication, purge, load, and unload.
>
> **Source of truth.** The eight programs under
> `app/app-authorization-ims-db2-mq/cbl/**` and
> `services/authorization-service/**`.

Authorization requests preserve the CSV field contract while queue metadata
uses opaque HMAC tokens instead of PANs. Database changes and the outbox row
commit atomically.

## Build and Test

```bash
# WHAT: compile and test the authorization module with shared codec/security contracts.
# WHY : Assumptions: CSV bounds, control-character rejection, opaque group ids,
#       and error redaction are common-lib behaviors required by this service.
mvn -B -f services/pom.xml -pl authorization-service -am test
```

The entry point is `com.carddemo.authorization.AuthorizationApplication`, and one
deployable both consumes the FIFO request queue and serves the pending-authorization
endpoints -- they contend over the same tables, so splitting them would put a bounded
context's data behind two independent writers. `config/SecurityConfig.java` restricts
fraud marking to the `carddemo-admin` authority, because the baseline reaches its
fraud-marking program from the administrative menu only, and its decoder installs the
token-kind, client and scope checks the issuer location alone does not make.

## Run

```bash
# WHAT: build the bootable jar for this module, then start it.
# WHY : Assumptions: the datasource values, the issuer location, the three queue names and the app
#       client id have NO defaults in `application.yml`, so an incomplete environment stops at startup
#       naming the missing key rather than starting a consumer bound to nothing. That matters more here
#       than elsewhere: a consumer pointed at the wrong queue is silent rather than broken.
# WHY : Assumptions: SERVER_SSL_ENABLED=false is set for a LOCAL run only. `application.yml` enables
#       TLS and takes the certificate and private key from CARDDEMO_SERVER_TLS_CERTIFICATE and
#       CARDDEMO_SERVER_TLS_PRIVATE_KEY as PEM CONTENT with no fallback, because the deployed target
#       group speaks HTTPS to the task. Neither value exists on a developer machine, and the process
#       would fail while trying to read the unresolved placeholder as key material. Alternatives
#       Considered: generating a self-signed pair for the loopback hop -- rejected because it puts
#       private-key material into a shell history for a hop that leaves no host. Deployment sets neither
#       variable by hand: the task definition injects both from Secrets Manager, and TLS stays enabled.
# WHY : Assumptions: AWS_REGION is needed for the SQS client this context cannot run without. Unlike its
#       peers `application.yml` here declares no static region at all, deliberately, so that a deployed
#       task resolves it from its own environment; this variable is the first entry in the SDK's own
#       resolution chain, so naming it locally reaches the same setting without adding a key. Without it
#       the chain falls through to instance metadata discovery and waits before failing.
# WHY : Assumptions: the queue name below is REQUIRED even for a process that will consume nothing,
#       because `service/AuthorizationRequestListener` resolves its @SqsListener queueNames placeholder
#       when the endpoint is REGISTERED rather than when a message arrives. An absent value therefore
#       aborts context refresh rather than producing an idle consumer -- which is the correct failure, and
#       the reason recorded above still holds: a consumer pointed at the wrong queue is silent rather
#       than broken.
mvn -B -f services/pom.xml -pl authorization-service -am package

SERVER_SSL_ENABLED=false \
AWS_REGION=us-east-1 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_authorization \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE=<request queue name> \
java -jar services/authorization-service/target/authorization-service-1.0.0-SNAPSHOT.jar
```
