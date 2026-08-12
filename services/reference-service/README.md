# Reference Service

> **Purpose.** Document transaction-type/category, disclosure-group, lookup,
> and date-conversion ownership.
>
> **Source of truth.** `COTRTLIC`, `COTRTUPC`, `COBTUPDT`, `CODATE01`,
> `CSUTLDTC`, and `services/reference-service/**`.

The reference schema preserves the transaction-type/category restrict
constraint and the mandatory `DEFAULT` disclosure-group fallback.

## Prerequisite: run every command from the repository root

Every command on this page uses paths relative to the **repository root** — the
directory containing `services/`, `app/` and `infra/`. Assumptions: this is stated
rather than assumed because the commands read as though they belonged beside this
file, and running them from `services/reference-service/` makes Maven resolve
`services/services/pom.xml` and fail with a missing-POM error naming a path nobody
wrote.

```bash
# WHAT: confirm the working directory before running anything else on this page.
# WHY : Assumptions: the check tests for directories that exist only at the
#       repository root, which is less brittle than comparing the shell's path
#       against a clone-specific name.
test -d services -a -d app || echo 'not at the repository root — cd there first'
```

## API surface

**Nineteen** operations across fourteen paths, as published by
[`openapi/reference-api.yaml`](src/main/resources/openapi/reference-api.yaml):

| Path | Operations | Replaces |
|---|---|---|
| `/api/v1/reference/transaction-types` | `GET`, `POST` | `COTRTLIC` list, add |
| `/api/v1/reference/transaction-types/{typeCd}` | `GET`, `PUT`, `DELETE` | `COTRTUPC` view, edit, delete |
| `/api/v1/reference/transaction-categories` | `GET`, `POST` | category list, add |
| `/api/v1/reference/transaction-categories/{typeCd}/{catCd}` | `GET`, `PUT`, `DELETE` | category view, edit, delete |
| `/api/v1/reference/disclosure-groups/{acctGroupId}/{tranTypeCd}/{tranCatCd}` | `GET` | the interest-rate lookup `CBACT04C` performs |
| `/api/v1/reference/us-phone-area-codes` and `/{areaCd}` | `GET`, `GET` | `CSLKPCDY` area-code allow-list |
| `/api/v1/reference/us-states` and `/{stateCd}` | `GET`, `GET` | `CSLKPCDY` state allow-list |
| `/api/v1/reference/us-state-zip-prefixes` and `/{stateZipCd}` | `GET`, `GET` | `CSLKPCDY` state/ZIP-prefix allow-list |
| `/api/v1/reference/date-evaluations` | `GET` | `CSUTLDTC` / `CODATE01` date edit |
| `/api/v1/reference/maintenance-actions` | `POST` | `COBTUPDT` batch reference update |

Authorization splits **by HTTP method**, not by path: reads are available to any
authenticated caller, because every other context reads the seeded lookup rows to
validate an address, while `POST`, `PUT`, `PATCH` and `DELETE` each require the
`carddemo-admin` authority. `/actuator/health/**` is unauthenticated and the rest
of the actuator namespace is reachable only from loopback.

## Data ownership

This module owns the **`reference`** schema: six tables created by
[`V1__reference.sql`](src/main/resources/db/migration), seeded by
`V2__seed_reference.sql`.

| Table | Holds | Source |
|---|---|---|
| `reference.transaction_types` | transaction type codes | `CVTRA03Y`, `trantype.txt` |
| `reference.transaction_categories` | type/category pairs | `CVTRA04Y`, `trancatg.txt` |
| `reference.disclosure_groups` | interest rate by group, type and category | `CVTRA02Y`, `discgrp.txt` |
| `reference.us_phone_area_codes` | area-code allow-list | `CSLKPCDY` |
| `reference.us_states` | state allow-list | `CSLKPCDY` |
| `reference.us_state_zip_prefixes` | state/ZIP-prefix allow-list | `CSLKPCDY` |

Two properties of that schema are load-bearing and must not be "simplified":

- **The restrict constraint is preserved as a real foreign key.**
  `transaction_categories.type_cd` references `transaction_types (type_cd)`
  **`ON DELETE RESTRICT`**, which is the same semantic the baseline's Db2 definition
  declares. Assumptions: deleting a type that still has categories is refused by
  the database, and the API surfaces that refusal as **409 Conflict** rather than
  letting a driver error escape — the refusal is the documented behaviour, not an
  incident.
- **The `DEFAULT` disclosure group is seeded, and its key is space-padded.**
  `V2__seed_reference.sql` inserts exactly **17** rows whose account group is the
  ten-character literal `'DEFAULT   '` — `DEFAULT` followed by three spaces,
  because the column is fixed width and the baseline compares the whole field.
  Assumptions: this seed is not optional. `CBACT04C` falls back to the `DEFAULT`
  group when an account's own disclosure key is not found, so a database missing
  these rows makes the interest job **abend** rather than degrade — a non-local
  defect whose symptom appears in `batch-service`. Trade-offs: writing the padding
  explicitly in the seed is uglier than trimming the column, and it is the reason
  the fallback resolves at all; an unpadded `'DEFAULT'` would not match.

## Configuration

Twelve variables have **no fallback** in `application.yml`, and one further
setting is required by a bean condition rather than by a placeholder. All thirteen
must be present for the context to start:

| Variable | Selects |
|---|---|
| `SPRING_DATASOURCE_URL` | JDBC location of the cluster |
| `SPRING_DATASOURCE_USERNAME` | Runtime `reference` role |
| `SPRING_DATASOURCE_PASSWORD` | Credential for that role |
| `SPRING_FLYWAY_USER` | **Migration** role, distinct from the runtime role, which owns the schema and its tables |
| `SPRING_FLYWAY_PASSWORD` | Credential for the migration role |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Issuer whose keys validate presented tokens |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | App client a presented token must name |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | Opens the listener keystore; **not read when TLS is disabled** |
| `AWS_REGION` | Region for the SQS client |
| `CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE` | Queue the date-conversion listener consumes |
| `CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE` | Queue replies are published to |
| `CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE` | Terminal error sink |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | Seals keyset cursors; supplied as `carddemo.pagination.cursor.signing-key` |

**The three queue variables are required to START. They are not safely
omittable.** Refactoring Rationale: this page previously stated that they were
"deliberately absent from this command", that configuration-property binding
"leaves an unresolvable placeholder as literal text rather than raising", and that
"the process starts without them and a listener would fail only when it resolved
its queue". That is false, and it was verified false by running the jar with every
other variable supplied and these three omitted: the context fails during refresh
with

```text
Error creating bean with name 'dateInquiryMessageListener' …
Caused by: org.springframework.util.PlaceholderResolutionException:
  Could not resolve placeholder 'CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE'
  in value "${CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE}"
  <-- "${carddemo.reference.inquiry.reply-queue}"
```

Assumptions: the `@SqsListener` on `DateInquiryMessageListener` resolves its queue
names through annotation placeholder resolution while the bean is being created, so
an unresolved name raises at that point rather than becoming literal text. And even
with the names resolved, `application.yml` pins
`queue-not-found-strategy: fail`, so the **queues themselves must already exist**;
a local run needs an SQS-compatible endpoint (`--spring.cloud.aws.sqs.endpoint=…`)
with all three queues created first. Trade-offs: `fail` is chosen over the
framework's `CREATE` default deliberately — an auto-created queue has neither the
dead-letter queue nor the encryption the provisioned one has, so the service would
start healthy against a queue nothing else publishes to and look idle rather than
misconfigured.

Assumptions: `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` is required even though it
appears in no `application.yml` placeholder here. The shared kernel publishes the
`CursorToken` bean **only** when that property is set, and this module's
`AddressLookupController` takes one as a constructor parameter, so omitting it
fails the context with a missing-bean error naming `CursorToken` rather than naming
the setting — which is how it is most often missed.

Everything else carries a documented default in
[`application.yml`](src/main/resources/application.yml).

## Build and Test

<!-- test-inventory: 27 tests + 10 integration tests -->
**37** test classes across eight subpackages: **27** matching `*Test`, run by
Surefire, and **10** matching `*IT`, run by Failsafe. The census is machine-checked
— `ServiceReadmeInventoryTest` in `common-lib` parses the comment above and
re-measures both figures against this module's test tree.

| Package | Classes | Package | Classes |
|---|---|---|---|
| `service` | 10 | `api` | 4 |
| `repository` | 9 (all `*IT`) | `config` | 3 |
| `mapper` | 3 | `dto` | 2 |
| `fixtures` | 2 | `domain` | 1 |

```bash
# WHAT: run every test in this module, unit and integration alike.
# WHY : Assumptions: `verify` rather than `test`, because Failsafe binds to
#       `integration-test` and `verify`. Refactoring Rationale: this page used to
#       document `test` alone, which exercises 26 of the 35 classes and skips ALL
#       NINE Testcontainers-backed classes — every repository assertion in the
#       module, including the restrict constraint and the padded `DEFAULT` seed,
#       which are the two properties this schema exists to preserve. A container
#       runtime is required.
mvn -B -f services/pom.xml -pl reference-service -am verify
```

```bash
# WHAT: compile and test the unit tier only, with no container runtime.
# WHY : Assumptions: date editing and exact reference-data contracts cross the
#       module/common-lib boundary and must be built together, which is what `-am`
#       provides. Trade-offs: faster, and it proves strictly less — use `verify`
#       before pushing.
mvn -B -f services/pom.xml -pl reference-service -am test
```

## Run

A local run needs, in the order they are required: a reachable **database** (the
migration credential is used first), a reachable **token issuer** (discovery is
fetched during context refresh, not on first request), a free port, and an **SQS
endpoint whose three queues already exist**.

Secrets are prepared **out of band**, in a file that cannot be committed:

```bash
# WHAT: create the environment file with owner-only permissions, before writing
#       any value into it, then fill it in with an editor.
# WHY : Assumptions: the name is `.env.reference-service.local` specifically
#       because `.gitignore` ignores `.env.*`, which the `git check-ignore` line
#       confirms by printing the rule and its line number. A name such as
#       `reference-service.env` matches no ignore rule here and would be staged by
#       `git add -A` along with the database password and the cursor signing key.
# WHY : Assumptions: `umask 077` is applied BEFORE creation rather than corrected
#       afterwards, because a later `chmod` leaves a window in which the file was
#       group- and world-readable.
umask 077
touch .env.reference-service.local
chmod 600 .env.reference-service.local
git check-ignore -v .env.reference-service.local

# Fill in one KEY=value per line, no `export` and no quoting, for the thirteen
# settings listed under Configuration (the keystore password may be omitted for a
# local run, because TLS is disabled below and the value is then never read).
```

```bash
# WHAT: build the bootable jar for this module, then start it.
# WHY : Assumptions: the jar produced by `spring-boot:repackage` is self-contained, so no class path is
#       assembled at launch. THIRTEEN variables have no fallback anywhere, and §Configuration lists
#       every one; the command below supplies all thirteen, so an incomplete environment stops at
#       startup rather than serving requests bound to nothing.
# WHY : Trade-offs: the failure names the SYMPTOM rather than the key for the framework-bound values.
#       Measured on the sibling transaction service with the Flyway rows omitted, the first failure was
#       `FATAL: password authentication failed for user "${SPRING_FLYWAY_USER}"` -- Spring Boot's
#       binder leaves an unresolvable placeholder as its own literal text, so the driver was handed
#       those characters as a username. The values read through `@Value` do name their own property.
#       An earlier revision of this note claimed the startup failure names the missing key in every
#       case; it does not.
# WHY : Assumptions: CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID has no fallback either. It is the app
#       client a presented token must name, and `config/SecurityConfig.java` refuses to start without it
#       -- a blank value would make the shared validator skip that check silently.
# WHY : Assumptions: SERVER_SSL_ENABLED=false is set for a LOCAL run only. `application.yml` enables
#       TLS and reads its listener material from a PKCS#12 keystore whose password placeholder,
#       CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD, has no fallback, because the deployed target group
#       speaks HTTPS to the task. No keystore exists on a developer machine, so the process would
#       fail while trying to open one.
# WHY : Assumptions: SERVER_ADDRESS=127.0.0.1 is what MAKES this run loopback-only, and it is a
#       configured property rather than a description. No `application.yml` here sets `server.address`;
#       the variable reaches Spring Boot's `ServerProperties.address` by relaxed binding. Verified by
#       running a service both ways and reading the kernel's listening socket: with the variable set,
#       /proc/net/tcp shows local address `0100007F` (127.0.0.1) and a request to the host's routable
#       address is refused; without it the row is `00000000` and that same request returns HTTP 200 from
#       off-host. Disabling TLS is only defensible once nothing outside the machine can reach the port.
# WHY : Assumptions: AWS_REGION is required to START, not merely to reach AWS. This module declares the
#       SQS starter for the date-conversion inquiry consumer, and the SQS client bean is built as the
#       context is constructed, so a context with no region enters the SDK resolution chain and waits on
#       instance metadata discovery before failing. Credentials are NOT needed to start, because they
#       resolve on first call.
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
# WHY : Refactoring Rationale: the three inquiry queue variables are now SUPPLIED below, and an earlier
#       revision of this note deliberately omitted them on the ground that they are read by
#       configuration-property binding, "which leaves an unresolvable placeholder as literal text
#       rather than raising, so the process starts without them". Measured against
#       `service/DateInquiryMessageListener.java`, that ground is wrong in both halves: the reply and
#       error queues arrive as `@Value` constructor arguments at `:188-189` -- which RAISES on an
#       unresolvable placeholder -- and each is additionally passed through `requireQueueName`, while
#       the request queue is the `@SqsListener(queueNames = ...)` placeholder at `:229`, resolved when
#       the endpoint is REGISTERED rather than when a message arrives. Omitting any of the three
#       therefore aborts context refresh; it does not produce a process that starts and fails later.
#       A deployment sets all three from Terraform outputs, and this command sets local stand-ins.
mvn -B -f services/pom.xml -pl reference-service -am package

SERVER_SSL_ENABLED=false \
AWS_REGION=us-east-1 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_reference \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_FLYWAY_USER=carddemo_reference_migrator \
SPRING_FLYWAY_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD=<any value; unused while TLS is off> \
CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY=<from your local secret store; base64, >= 32 bytes> \
CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE=<request queue name or url> \
CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE=<reply queue name or url> \
CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE=<error queue name or url> \
java -jar services/reference-service/target/reference-service-1.0.0-SNAPSHOT.jar
```

---

## Configuration

Every value this service reads arrives as an environment variable. The table is
**exhaustive** against `src/main/resources/application.yml` and its two profiles: a
`Fallback` of **none** means the variable has no default anywhere, and every other row
shows the literal the profile falls back to. The `Value` column is `—` throughout, by
design — no value of any of these appears anywhere in this repository.

| Variable | Value | Source in deployment | Fallback |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` | — | Parameter Store (Aurora writer endpoint) | none |
| `SPRING_DATASOURCE_USERNAME` | — | Secrets Manager (`reference` schema runtime role) | none |
| `SPRING_DATASOURCE_PASSWORD` | — | Secrets Manager | none |
| `SPRING_FLYWAY_USER` | — | Secrets Manager (migration role, not the runtime one) | none |
| `SPRING_FLYWAY_PASSWORD` | — | Secrets Manager | none |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | — | Terraform output (Cognito user pool issuer) | none |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | — | Parameter Store (Cognito app client) | none |
| `CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE` | — | Parameter Store (SQS) | none |
| `CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE` | — | Parameter Store (SQS) | none |
| `CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE` | — | Parameter Store (SQS) | none |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | — | Secrets Manager | none |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | — | minted per task by the image entry point | none |
| `AWS_REGION` | — | ECS task definition | none — see below |
| `CARDDEMO_VERSION` | — | ECS task definition (image tag or digest) | `unspecified`, which the service module **refuses** |
| `CARDDEMO_ONLINE_WRITES_PARAMETER` | — | ECS task definition (SSM parameter name) | none in effect — see below |
| `CARDDEMO_ENVIRONMENT` | — | ECS task definition | `unspecified` |
| `CARDDEMO_LOG_CONSOLE_FORMAT` | — | not injected | `ecs` |
| `SERVER_SSL_ENABLED` | — | not injected; the listener stays encrypted | `true` |
| `CARDDEMO_SERVER_TLS_KEYSTORE` | — | the entry point's own path | `file:/tmp/carddemo-tls/listener.p12` |
| `CARDDEMO_SERVER_TLS_KEY_ALIAS` | — | fixed alias | `carddemo-listener` |
| `CARDDEMO_DB_SSL_ROOT_CERT` | — | the image's own trust bundle | `/etc/ssl/certs/carddemo-rds-ca-bundle.pem` |
| `CARDDEMO_COGNITO_ADMIN_GROUP_NAME` | — | Terraform output | `carddemo-admin` |
| `CARDDEMO_COGNITO_USER_GROUP_NAME` | — | Terraform output | `carddemo-user` |
| `CARDDEMO_PAGINATION_CURSOR_LIFETIME` | — | not injected | `PT15M` |
| `CARDDEMO_ONLINE_WRITES_CACHE_PERIOD` | — | not injected | `PT5S` |
| `CARDDEMO_REFERENCE_INQUIRY_MAX_CONCURRENT_MESSAGES` | — | not injected | `10` |
| `CARDDEMO_REFERENCE_INQUIRY_MAX_MESSAGES_PER_POLL` | — | not injected | `10` |
| `CARDDEMO_REFERENCE_INQUIRY_POLL_TIMEOUT_SECONDS` | — | not injected | `5` |

Assumptions: `AWS_REGION` is required to **start**, not merely to reach AWS, which is why
it carries `none` rather than a fallback. `application.yml` binds it to
`spring.cloud.aws.region.static`, and the SQS client bean is built while the context is
constructed, so a context with no region enters the SDK's resolution chain and waits on
instance-metadata discovery before failing. `infra/modules/ecs-service` requires the name
of this workload for that reason. Credentials are **not** needed to start, because they
resolve on the first call.

Assumptions: **two required names are not written as `${...}` placeholders in any
profile**, so a reader auditing the YAML for placeholders will not find them and could
reasonably conclude they are optional. They are not. Spring's relaxed binding maps
`CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` onto `carddemo.pagination.cursor.signing-key`
and `CARDDEMO_ONLINE_WRITES_PARAMETER` onto `carddemo.online-writes.parameter`, and both
properties are declared in the shared kernel's auto-configuration rather than here. The
two behave differently when absent:

- The cursor signing key **stops startup**. Its bean is `@ConditionalOnProperty` on that
  property, and three controllers here — `api/TransactionTypeController`,
  `api/TransactionCategoryController` and `api/AddressLookupController` — each declare a
  `private final CursorToken` supplied by their constructor, with no `ObjectProvider` or
  `Optional` wrapper, so an absent value fails context refresh with a missing-bean report.
  This context has more paged readers than any other, because every other context reads
  these lookup rows to validate an address.
- The online-write gate **removes itself silently**. Both its beans are conditional on the
  same property and nothing outside the auto-configuration injects them, so an absent
  value leaves the admin-only writes here accepting traffic during the nightly batch
  window with nothing in the log to say the gate is gone. `infra/modules/ecs-service`
  therefore makes the name **biconditional** for the seven web workloads: a root that
  omits it, or that supplies it to the batch task, fails at `plan`.

Assumptions: the service module requires thirteen of these by name for this workload —
six through Parameter Store, five through Secrets Manager, and `AWS_REGION` and
`CARDDEMO_VERSION` as plain values — so a root that drops one fails at `plan` rather than
producing a task that starts and then cannot consume. The lists are in
[`infra/modules/ecs-service/main.tf`](../../infra/modules/ecs-service/main.tf) under
`required_parameter_environment_names`, `required_secret_environment_names` and
`required_plain_environment_names`.
