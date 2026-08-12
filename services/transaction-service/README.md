# Transaction Service

> **Purpose.** Document transaction list/detail/add and bill-payment ownership.
>
> **Source of truth.** `COTRN00C`, `COTRN01C`, `COTRN02C`, `COBIL00C`, and
> `services/transaction-service/**`.

Transactions use exact fixed-point amounts and authenticated, context-bound
keyset cursors. Database TLS uses peer verification; detailed health diagnostics
are isolated from the unauthenticated probe surface.

## Prerequisite: run every command from the repository root

Every command on this page is written with paths relative to the **repository
root** — the directory containing `services/`, `app/` and `infra/`. Assumptions:
this is stated rather than assumed because the commands read naturally as though
they belonged beside this file, and running them from
`services/transaction-service/` makes Maven resolve `services/services/pom.xml`
and fail with a missing-POM error that names a path nobody wrote. Nothing here
needs to be run from the module directory.

```bash
# WHAT: confirm the working directory before running anything else on this page.
# WHY : Assumptions: the check tests for a directory that exists only at the
#       repository root, which is cheaper and less brittle than comparing the
#       shell's path against a hard-coded name that differs per clone.
test -d services -a -d app || echo 'not at the repository root — cd there first'
```

## API surface

Four operations across three paths, as published by
[`openapi/transaction-api.yaml`](src/main/resources/openapi/transaction-api.yaml):

| Operation | Path | Replaces | Notes |
|---|---|---|---|
| `GET` | `/api/v1/transactions` | `COTRN00C` | Keyset-paged list; the cursor is signed and context-bound, never a raw composite key |
| `POST` | `/api/v1/transactions` | `COTRN02C` | Adds a transaction; every transcribed validation gate runs so a submission reports all of its faults rather than the first |
| `GET` | `/api/v1/transactions/{transactionId}` | `COTRN01C` | Single transaction by identifier |
| `POST` | `/api/v1/billpay` | `COBIL00C` | Pays the outstanding balance **in full**; the only balance-affecting write in this context |

## Data ownership

This module owns the **`ledger`** schema and nothing outside it. The objects are
created by [`V1__ledger.sql`](src/main/resources/db/migration) and a sequence is
added by the second migration:

| Object | Kind | Replaces / why |
|---|---|---|
| `ledger.transactions` | table | `CVTRA05Y`, the 350-byte transaction record |
| `idx_transactions_card_num` | index | the by-card access path |
| `idx_transactions_proc_ts` | index | the `TRANSACT.VSAM.AIX` batch alternate index; non-unique, because the baseline path is not unique either |
| `ledger.daily_transactions` | table | `CVTRA06Y`, the 350-byte daily feed |
| `ledger.transaction_rejects` | table | the reject stream, preserving the 430-byte composition as raw record plus reason code and description |
| `ledger.transaction_category_balances` | table | `CVTRA01Y`, the 50-byte category balance |
| `ledger.transaction_id_seq` | sequence | identifier generation for added transactions |

`batch-service` also writes `ledger.*` under the one sanctioned cross-schema
grant, which is recorded in that module's README and in `ADR-007`; nothing else
does.

## Money crosses the API as a signed decimal string

Assumptions: every amount is a **JSON string**, signed, with the decimal point
**required** and exactly two fractional digits — never a JSON number. The contract
is published as two schemas, and their widths differ because the reference layouts
differ:

| Schema | Pattern | Reference field |
|---|---|---|
| `TransactionAmount` | `^-?[0-9]{1,9}\.[0-9]{2}$` | `TRAN-AMT`, `PIC S9(09)V99` |
| `AccountBalance` | `^-?[0-9]{1,10}\.[0-9]{2}$` | `ACCT-CURR-BAL`, `PIC S9(10)V99` |

Trade-offs: a string costs a parse step at the edge and buys exactness that cannot
be lost in transit — a JSON number is parsed into an IEEE-754 double by most
clients, which cannot represent every two-decimal value, and the corruption lands
where the user sees it. The leading `-` is part of both patterns because a credit
balance is a normal state: a client validating digits only would reject every
negative amount it was sent. `MoneyModule` in `common-lib` owns the serialisation
so no service can opt out, and an architecture test refuses `double` anywhere in
the money path.

## Security contract

The filter chain is
[`config/SecurityConfig`](src/main/java/com/carddemo/transaction/config/SecurityConfig.java).
These are the operations whose misuse moves money, so the chain is worth stating
exactly rather than in summary:

| Surface | Rule | Authenticated? |
|---|---|---|
| `/actuator/health/**` | `permitAll()` | **No** — it is the container and target-group probe |
| `/actuator/prometheus` | `loopbackOnly()` | **No**, but reachable only from `127.0.0.1/32` or `::1/128` |
| `/actuator/**` | `loopbackOnly()` | **No**, same network restriction; matched after the health pattern, which is more specific |
| everything else, including all four business operations | `businessAccess()` — either business authority | **Yes** |

Refactoring Rationale: this document previously said the chain "admits nothing
unauthenticated except the health probe". That was wrong in a way that mattered:
two further actuator surfaces are admitted without a token, and what protects them
is **network position rather than authentication**. Understating the surface is the
dangerous direction — a reader auditing exposure would have concluded a token was
required where none is, and so would not have checked that the loopback restriction
is actually in place. Assumptions: the loopback grant is what makes the metric
endpoint acceptable, because the collector sidecar scrapes
`https://127.0.0.1:<container-port>/actuator/prometheus` from **inside the task**;
nothing off the box can reach either path. Alternatives Considered: refusing the
management namespace with `denyAll()`, as the auth and card contexts do. Rejected
here for a specific structural reason recorded in the class: this chain has **no
per-path rule table for its business routes**, so every one of the four operations
is authorized *by* the catch-all — ending it in `denyAll()` would refuse all four,
including bill payment.

## Configuration

Values are not reproduced here. Ten variables have **no fallback** in
`application.yml`, so an incomplete environment stops at startup naming the key it
could not resolve rather than serving requests bound to nothing:

| Variable | Selects |
|---|---|
| `SPRING_DATASOURCE_URL` | JDBC location of the cluster |
| `SPRING_DATASOURCE_USERNAME` | Runtime `ledger` role |
| `SPRING_DATASOURCE_PASSWORD` | Credential for that role |
| `SPRING_FLYWAY_USER` | **Migration** role, distinct from the runtime role, which owns the schema and its tables |
| `SPRING_FLYWAY_PASSWORD` | Credential for the migration role |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Issuer whose keys validate presented tokens |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | App client a presented token must name |
| `CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY` | Seals the internal token this context presents to the account context |
| `CARDDEMO_ACCOUNT_CONTEXT_BASE_URL` | Where the account context answers |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | Opens the listener keystore; **not read when TLS is disabled** |

Assumptions: two of those deserve emphasis because a deployment provisioned from
an older inventory would omit them and fail at startup. The **Flyway credential
pair** is separate from the runtime pair on purpose — migration runs as the
migration role, which makes that role the owner of the schema and every table in
it, so the runtime role can be reduced to named DML. And the **internal signing
key** is not decoration: bill payment crosses into the account context, and the
token that authorises that hop is signed with this key.

Everything else — the group names, the database trust anchor, the environment and
version tags — carries a documented default in
[`application.yml`](src/main/resources/application.yml).

## Build and Test

<!-- test-inventory: 26 tests + 7 integration tests -->
**33** test classes across nine subpackages: **26** matching `*Test`, run by
Surefire, and **7** matching `*IT`, run by Failsafe. Every test package carries a
`package-info.java`, because the documentation gate audits test sources. That
census is machine-checked — `ServiceReadmeInventoryTest` in `common-lib` parses the
comment above and re-measures both figures against this module's test tree.

| Package | Classes |
|---|---|
| `api` | `TransactionControllerTest`, `BillPaymentControllerTest`, `TransactionApiRoutingContractTest` |
| `service` | `TransactionListServiceTest`, `TransactionListServiceCursorBindingTest`, `TransactionAddServiceTest`, `TransactionViewServiceTest`, `BillPaymentServiceTest`, `BillPaymentEvaluationOrderTest` |
| `repository` | `TransactionRepositoryIT`, `DailyTransactionRepositoryIT`, `TransactionCategoryBalanceRepositoryIT`, `TransactionRejectRepositoryIT` |
| `mapper` | `TransactionMapperTest`, `BillPaymentMapperTest`, `BillPaymentMappingTest` |
| `domain` | `MoneyColumnInvariantTest`, `FixedWidthMappingTest`, `FeedRowIdentityTest`, `OccurrenceIdentityTest` |
| `architecture` | `TransactionLayeringRulesTest`, `MoneyPathGateProofTest`, `KeysetPaginationGateProofTest` |
| `dto` | `TransactionApiContractTest`, `TransactionAddRequestTest` |
| `config` | `SecurityConfigTest`, `OpenApiConfigTest` |
| `fixtures` | `TransactionFixtureContractTest` |

```bash
# WHAT: run every test in this module, unit and integration alike.
# WHY : Assumptions: `verify` rather than `test`, because Failsafe binds to
#       `integration-test` and `verify`. Refactoring Rationale: this page used to
#       document `test` alone, which exercises 24 of the 28 classes and silently
#       skips all FOUR Testcontainers-backed classes — including the keyset paging
#       and reject-stream assertions, which are the ones a reader is most likely to
#       assume are covered. A container runtime is required.
mvn -B -f services/pom.xml -pl transaction-service -am verify
```

```bash
# WHAT: run the unit tier only, with no container runtime available.
# WHY : Trade-offs: faster, and it proves strictly less — use it while iterating,
#       and `verify` before pushing.
mvn -B -f services/pom.xml -pl transaction-service -am test
```

## Run

A local run needs a reachable **database** and a reachable **token issuer** before
it will start, in that order: the migration credential is used before anything
else, and the resource server fetches issuer discovery while the context refreshes
rather than on first request. This module declares no `@SqsListener`, so no queue
has to exist for it to start.

Secrets are prepared **out of band**, in a file that cannot be committed:

```bash
# WHAT: create the environment file with owner-only permissions, before writing
#       any value into it, then fill it in with an editor.
# WHY : Assumptions: the name is `.env.transaction-service.local` specifically
#       because `.gitignore` ignores `.env.*`, which the `git check-ignore` line
#       confirms by printing the rule and its line number. A name such as
#       `transaction-service.env` matches no ignore rule here and would be staged
#       by `git add -A` along with the database password and the signing key.
# WHY : Assumptions: `umask 077` is applied BEFORE creation rather than corrected
#       afterwards, because a later `chmod` leaves a window in which the file was
#       group- and world-readable.
umask 077
touch .env.transaction-service.local
chmod 600 .env.transaction-service.local
git check-ignore -v .env.transaction-service.local

# Fill in one KEY=value per line, no `export` and no quoting, for the ten
# variables listed under Configuration (the keystore password may be omitted for a
# local run, because TLS is disabled below and the value is then never read).
```

```bash
# WHAT: build the bootable jar for this module, then start it.
# WHY : Assumptions: the jar produced by `spring-boot:repackage` is self-contained, so no class path is
#       assembled at launch. ELEVEN variables have no fallback anywhere, and §Configuration lists every
#       one; the command below supplies all eleven, so an incomplete environment stops at startup
#       rather than serving requests bound to nothing.
# WHY : Trade-offs: the failure names the SYMPTOM rather than the key for the framework-bound values.
#       Measured with this command's earlier form, which omitted the two Flyway rows, the first failure
#       was `FATAL: password authentication failed for user "${SPRING_FLYWAY_USER}"` -- Spring Boot's
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
#       running this module both ways and reading the kernel's listening socket: with the variable set,
#       /proc/net/tcp shows local address `0100007F` (127.0.0.1) and a request to the host's routable
#       address is refused; without it the row is `00000000` and that same request returns HTTP 200 from
#       off-host. Disabling TLS is only defensible once nothing outside the machine can reach the port.
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
mvn -B -f services/pom.xml -pl transaction-service -am package

SERVER_SSL_ENABLED=false \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_ledger \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_FLYWAY_USER=carddemo_ledger_migrator \
SPRING_FLYWAY_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD=<any value; unused while TLS is off> \
CARDDEMO_ACCOUNT_CONTEXT_BASE_URL=<account-service origin> \
CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY=<from your local secret store; >= 32 bytes> \
CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY=<from your local secret store; base64, >= 32 bytes> \
java -jar services/transaction-service/target/transaction-service-1.0.0-SNAPSHOT.jar
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
| `SPRING_DATASOURCE_USERNAME` | — | Secrets Manager (`ledger` schema runtime role) | none |
| `SPRING_DATASOURCE_PASSWORD` | — | Secrets Manager | none |
| `SPRING_FLYWAY_USER` | — | Secrets Manager (migration role, not the runtime one) | none |
| `SPRING_FLYWAY_PASSWORD` | — | Secrets Manager | none |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | — | Terraform output (Cognito user pool issuer) | none |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | — | Parameter Store (Cognito app client) | none |
| `CARDDEMO_ACCOUNT_CONTEXT_BASE_URL` | — | Parameter Store (internal load balancer) | none |
| `CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY` | — | Secrets Manager | none |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | — | Secrets Manager | none |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | — | minted per task by the image entry point | none |
| `CARDDEMO_VERSION` | — | ECS task definition (image tag or digest) | `unspecified`, which the service module **refuses** |
| `CARDDEMO_ONLINE_WRITES_PARAMETER` | — | ECS task definition (SSM parameter name) | none in effect — see below |
| `AWS_REGION` | — | ECS task definition | none read here; both roots supply it to every workload |
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

Assumptions: **two required names are not written as `${...}` placeholders in any
profile**, so a reader auditing the YAML for placeholders will not find them and could
reasonably conclude they are optional. They are not. Spring's relaxed binding maps
`CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` onto `carddemo.pagination.cursor.signing-key`
and `CARDDEMO_ONLINE_WRITES_PARAMETER` onto `carddemo.online-writes.parameter`, and
both properties are declared in the shared kernel's auto-configuration rather than
here. The two behave differently when absent, and the difference is the reason both are
called out:

- The cursor signing key **stops startup**. Its bean is `@ConditionalOnProperty` on that
  property, and `api/TransactionController` declares a `private final CursorToken`
  supplied by its constructor — with no `ObjectProvider` or `Optional` wrapper — so an
  absent value fails context refresh with a missing-bean report rather than degrading.
  The list service receives the same instance as a method parameter from there, so one
  missing bean closes every paged read this context publishes. Trade-offs: a default was rejected in the shared kernel because an
  unkeyed cursor is forgeable, and a forged cursor is a read of somebody else's page.
- The online-write gate **removes itself silently**. Both its beans are conditional on
  the same property and nothing outside the auto-configuration injects them, so an
  absent value leaves this service accepting writes during the nightly batch window with
  nothing in the log to say the gate is gone. `infra/modules/ecs-service` therefore
  makes the name **biconditional** for the seven web workloads: a root that omits it, or
  that supplies it to the batch task, fails at `plan` rather than at 02:00.

Assumptions: the service module requires eleven of these by name for this workload —
four through Parameter Store, six through Secrets Manager and `CARDDEMO_VERSION` as a
plain value — so a root that drops one fails at `plan` rather than producing a task that
starts and then cannot serve. The lists are in
[`infra/modules/ecs-service/main.tf`](../../infra/modules/ecs-service/main.tf) under
`required_parameter_environment_names`, `required_secret_environment_names` and
`required_plain_environment_names`.
