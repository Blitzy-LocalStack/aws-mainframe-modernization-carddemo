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
CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST=<comma-separated reply queue URLs> \
CARDDEMO_MESSAGING_HMAC_KEY=<from your local secret store; >= 32 bytes> \
CARDDEMO_ACCOUNT_CONTEXT_BASE_URL=<account-service origin> \
java -jar services/authorization-service/target/authorization-service-1.0.0-SNAPSHOT.jar
```

### `CARDDEMO_MESSAGING_HMAC_KEY` is key material, not a name

This variable carries a **secret value**, and it is the only one in the list above
that does besides the datasource password. It is the purpose-scoped key that
`CsvAuthCodec.AuthRequest.orderGroup` and `CsvAuthCodec.AuthReply.orderGroup`
derive the queue's FIFO **group identity** under, and that the codec's
`correlationKey` accessors derive the internal correlation identity under.

Assumptions: it carries **no default**, and a missing value stops startup in
`config/MessagingIdentityConfig.java` with a message naming the property. That is
deliberate rather than strict: a group identifier is message *metadata* — it sits
outside the encrypted body, it is reported in queue telemetry, and it is carried
into every log and metric that observes the queue — and the only other per-card
stable value the service holds is the card number itself. A default would
therefore produce a running service that published a primary account number on
every reply, which is worse than one that refuses to start.

Alternatives Considered: an unkeyed digest, or a keyed digest with a published
salt. Both rejected for the same reason: the protected value is a sixteen-digit
number with a check digit, so the candidate space is small enough to enumerate and
anyone holding a token could confirm which card produced it. Only a secret key
makes the token unconfirmable, which is what makes publishing it acceptable.

Assumptions: the **same** key must reach every producer on the pending-authorization
queue, and it must not be per instance or per restart. The group identity has to be
equal for equal cards across producers — that equality *is* the per-card ordering
guarantee — so a per-instance key would scatter one card's messages across as many
groups as there are running tasks and remove the ordering silently. Each Terraform
environment root generates one key per environment into Secrets Manager and injects
it to this service alone; `infra/modules/ecs-service` asserts both directions of
that, so a deployment that forgets it or that hands it to another service fails at
`plan`.

Assumptions: this is a **different** secret from `CARDDEMO_MASK_HMAC_KEY`, the
extract-transform-load redaction key documented in
[`data-migration/README.md`](../../data-migration/README.md). The two have
different holders and different trust purposes, and sharing one value would let a
one-off migration job that reads cardholder extracts compute production queue
group identities — as well as making a rotation of either purpose require stopping
an interactive consumer and a batch workload together. The key is accepted in
base64 or as raw text; either way it must decode to at least
`OpaqueIdentifier.MIN_KEY_LENGTH` bytes.
