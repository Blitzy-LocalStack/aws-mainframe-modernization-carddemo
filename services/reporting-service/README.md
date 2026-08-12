# Reporting Service

> **Purpose.** Document transaction-report, statement, and ad-hoc report
> ownership.
>
> **Source of truth.** `CORPT00C`, `CBTRN03C`, `CBSTM03A`, `CBSTM03B`, and
> `services/reporting-service/**`.

The service reads only masked security-barrier views through a SELECT-only
database role. It omits CVV and source-table access, validates bearer-token
kind/client/scope, and uses verified database TLS.

## Prerequisite: run every command from the repository root

Every command on this page is written with paths relative to the **repository
root** — the directory containing `services/`, `app/` and `infra/`. Assumptions:
this is stated rather than assumed because the commands read naturally as though
they belonged beside this file, and running them from
`services/reporting-service/` makes Maven resolve `services/services/pom.xml` and
fail with a missing-POM error that names a path nobody wrote. Nothing here needs to
be run from the module directory.

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
#       assembled at launch. TEN variables have no fallback anywhere, and §Configuration lists every
#       one; the command below supplies all ten, so an incomplete environment stops at startup rather
#       than serving requests bound to nothing.
# WHY : Trade-offs: the failure names the SYMPTOM rather than the key for the framework-bound values.
#       Measured on the sibling transaction service with its Flyway rows omitted, the first failure was
#       `FATAL: password authentication failed for user "${SPRING_FLYWAY_USER}"` -- Spring Boot's
#       binder leaves an unresolvable placeholder as its own literal text, so the driver was handed
#       those characters as a username. The values read through `@Value` do name their own property,
#       which is why the two report locations below fail differently. An earlier revision of this note
#       claimed the startup failure names the missing key in every case; it does not.
# WHY : Assumptions: this context declares NO Flyway variables at all, unlike every other database
#       workload, because it owns no tables to migrate -- it reads seven `security_barrier` views
#       another context's migration creates. A reader comparing this list against a sibling's should
#       read that absence as deliberate rather than as an omission.
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
# WHY : Refactoring Rationale: two further variables are now REQUIRED for a local run and are
#       supplied below -- CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN and
#       CARDDEMO_REPORTING_S3_OUTPUT_BUCKET. An earlier revision of this command omitted both, on
#       the ground that the classes consuming them had not been authored and that
#       configuration-property binding leaves an unresolvable placeholder as literal text rather
#       than raising. Both halves of that ground are spent: `service/ReportExecutionService.java`
#       and `service/StatementService.java` now read them, and each reads them through `@Value`,
#       which RAISES on an unresolvable placeholder rather than passing the literal through.
#       Omitting them today stops the process at startup naming the missing key.
# WHY : Assumptions: the two values below are deliberately non-resolvable placeholders and not
#       plausible ones. Both are NETWORK LOCATIONS rather than selectors, and neither is contacted on
#       any path a local run exercises: the report submission is the only caller of the state machine
#       and the statement description only DERIVES an object location without reading it. A value
#       that cannot be mistaken for a real state machine or bucket is therefore the right local
#       value, and a deployment sets both from Terraform outputs instead.

mvn -B -f services/pom.xml -pl reporting-service -am package

SERVER_SSL_ENABLED=false \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_reporting \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD=<any value; unused while TLS is off> \
CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY=<from your local secret store; base64, >= 32 bytes> \
CARDDEMO_REPORTING_ARTIFACT_HMAC_KEY=<from your local secret store; >= 32 bytes> \
CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN=<state machine arn, or a local placeholder> \
CARDDEMO_REPORTING_S3_OUTPUT_BUCKET=<dataset bucket name, or a local placeholder> \
java -jar services/reporting-service/target/reporting-service-1.0.0-SNAPSHOT.jar
```

## Configuration

Every value this service reads arrives as an environment variable. The table is
**exhaustive** against `src/main/resources/application.yml` and its two profiles: a
`Fallback` of **none** means the variable has no default anywhere, and every other row
shows the literal the profile falls back to. The `Value` column is `—` throughout, by
design — no value of any of these appears anywhere in this repository.

| Variable | Value | Source in deployment | Fallback |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` | — | Parameter Store (Aurora writer endpoint) | none |
| `SPRING_DATASOURCE_USERNAME` | — | Secrets Manager (`SELECT`-only reporting role) | none |
| `SPRING_DATASOURCE_PASSWORD` | — | Secrets Manager | none |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | — | Terraform output (Cognito user pool issuer) | none |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | — | Parameter Store (Cognito app client) | none |
| `CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN` | — | Parameter Store (Terraform output) | none |
| `CARDDEMO_REPORTING_S3_OUTPUT_BUCKET` | — | Parameter Store (Terraform output) | none |
| `CARDDEMO_REPORTING_ARTIFACT_HMAC_KEY` | — | Secrets Manager | none |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | — | Secrets Manager | none |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | — | minted per task by the image entry point | none |
| `CARDDEMO_VERSION` | — | ECS task definition (image tag or digest) | `unspecified`, which the service module **refuses** |
| `CARDDEMO_ONLINE_WRITES_PARAMETER` | — | ECS task definition (SSM parameter name) | none in effect — see below |
| `AWS_REGION` | — | ECS task definition | none read here; both roots supply it to every workload |
| `CARDDEMO_ENVIRONMENT` | — | ECS task definition | `unspecified` |
| `CARDDEMO_CONFIG_PREFIX` | — | not injected | `/carddemo/reporting` |
| `CARDDEMO_TRUSTED_PROXY_PATTERN` | — | not injected | `10\.0\.\d+\.\d+` |
| `CARDDEMO_LOG_CONSOLE_FORMAT` | — | not injected | `ecs` |
| `SERVER_SSL_ENABLED` | — | not injected; the listener stays encrypted | `true` |
| `CARDDEMO_SERVER_TLS_KEYSTORE` | — | the entry point's own path | `file:/tmp/carddemo-tls/listener.p12` |
| `CARDDEMO_SERVER_TLS_KEY_ALIAS` | — | fixed alias | `carddemo-listener` |
| `CARDDEMO_DB_SSL_ROOT_CERT` | — | the image's own trust bundle | `/etc/ssl/certs/carddemo-rds-ca-bundle.pem` |
| `CARDDEMO_COGNITO_ADMIN_GROUP_NAME` | — | Terraform output | `carddemo-admin` |
| `CARDDEMO_COGNITO_USER_GROUP_NAME` | — | Terraform output | `carddemo-user` |
| `CARDDEMO_PAGINATION_CURSOR_LIFETIME` | — | not injected | `PT15M` |
| `CARDDEMO_ONLINE_WRITES_CACHE_PERIOD` | — | not injected | `PT5S` |

Assumptions: the table lists the variables the service READS — every `${...}` placeholder
in the three profiles plus the two properties bound from an environment name with no
placeholder. It is not a list of every property the profiles set, because the environment
outranks a YAML literal for all of them and enumerating the whole file would bury the
ten rows that actually have to be supplied.

Assumptions: **two required names are not written as `${...}` placeholders in any
profile**, so a reader auditing the YAML for placeholders will not find them and could
reasonably conclude they are optional. They are not. Spring's relaxed binding maps
`CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` onto `carddemo.pagination.cursor.signing-key`
and `CARDDEMO_ONLINE_WRITES_PARAMETER` onto `carddemo.online-writes.parameter`, and both
properties are declared in the shared kernel's auto-configuration rather than here:

- The cursor signing key **stops startup**. Its bean is `@ConditionalOnProperty` on that
  property and `api/ReportController` declares a `private final CursorToken` supplied by
  its constructor, with no `ObjectProvider` or `Optional` wrapper, so an absent value
  fails context refresh with a missing-bean report rather than degrading.
- The online-write gate **removes itself silently**. Both its beans are conditional on the
  same property and nothing outside the auto-configuration injects them.
  `infra/modules/ecs-service` therefore makes the name **biconditional** for the seven web
  workloads: a root that omits it, or that supplies it to the batch task, fails at `plan`.

Assumptions: the service module requires ten of these by name for this workload — five
through Parameter Store, four through Secrets Manager and `CARDDEMO_VERSION` as a plain
value — so a root that drops one fails at `plan` rather than producing a task that starts
and then cannot publish an artifact. The lists are in
[`infra/modules/ecs-service/main.tf`](../../infra/modules/ecs-service/main.tf) under
`required_parameter_environment_names`, `required_secret_environment_names` and
`required_plain_environment_names`.

## HTTP surface

The contract of record is
[`src/main/resources/openapi/reporting-api.yaml`](src/main/resources/openapi/reporting-api.yaml).
Five operations are published and the set is closed; the document generated by the
publishing library at run time is a check on that file rather than a second source
of truth, and
`src/test/java/com/carddemo/reporting/api/ReportingApiContractTest.java` holds the
two to each other in both directions — including
`publishedRoutesAndDeclaredHandlersAgree`, which compares the document's routes with
the mapping annotations the two controllers declare, in both directions.

| Method | Path | Operation | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/reports/transaction-report` | `submitTransactionReport` | Start an on-demand transaction report execution behind the confirmation gate. A declining answer is a SUCCESS carrying an explicit not-submitted outcome, answered 200, where a started run is answered 201. |
| `GET` | `/api/v1/reports/transaction-report/lines` | `listTransactionReportLines` | Read one keyset page of the assembled detail lines for an inclusive business-date range. |
| `GET` | `/api/v1/reports/transaction-report/totals` | `readTransactionReportTotals` | Read the subtotal bands of the same report run. |
| `POST` | `/api/v1/reports/statements` | `generateStatement` | Render the statement pair for one card or account: heading figures, total and the two artifact locations. |
| `POST` | `/api/v1/reports/statements/transactions` | `listStatementTransactions` | Read the transactions behind one statement, which are the rows the two artifacts were built from. |

> Refactoring Rationale: this table formerly listed FOUR operations — `submitReport`,
> `getTransactionDetailReport`, `describeStatement` and `getStatementDocument` — the
> last two rooted at `/api/v1/statements`. It described handlers rather than the
> published contract, and the two had diverged: the contract declares five operations
> and roots the statement pair beneath the reports prefix. The handlers were moved onto
> the contract rather than the contract onto the handlers, for a reason outside either
> file: the browser client, the contract-drift gate and the edge routing are all written
> against the published document, and the edge forwards exactly one prefix to this
> service, so a second root would not have been reachable at all. The detail read also
> split in two, because a page of lines and a set of subtotal bands page differently —
> one is keyset-paged over line ordinals and the other is a whole small set.

Assumptions: the two statement operations are `POST` although both are reads, and the
reason is disclosure rather than semantics. A statement is selected by a primary
account number, and a request line is written into the access log of every intermediary
between the browser and this service, into browser history and into a referrer header —
none of which this service can redact after the fact. A request body appears in none of
them, so **no operation in this contract carries a path template at all**, and the
contract test asserts that as a property rather than leaving it to review.

## Security contract

`config/JwtDecoderConfig` owns the decoder and its token-kind, client and scope
checks; `config/SecurityConfig` owns the filter chain and the group-to-authority
conversion. Declaring a second decoder in the latter would make which one the resource
server used depend on bean ordering.

| Path | Rule |
|---|---|
| `/actuator/health/diagnostics` | administrator authority only — this is the one endpoint that names the database and its reachability |
| `/actuator/health/**` | `permitAll` — the target group and the container probe poll it unauthenticated, and details are never shown |
| `/actuator/info`, `/actuator/prometheus` | loopback only — a **network-position** rule, because neither the scraper nor the build-identity reader presents a token |
| `/actuator/**` | **`denyAll()`** — an operator who exposes a further endpoint and forgets to add a rule gets 403 rather than a working endpoint |
| anything else | either configured business group |

Assumptions: every published operation requires only the ordinary-user group, and
that is the whole of what the chain enforces for them. No operation here is
administrative, because nothing this service returns is wider than what a
cardholder-facing caller may read: an account number is narrowed to its last four
digits and no verification value is returned by any endpoint.

## Data ownership: none, deliberately

**This module owns no table, no index and no relational object, and it has no
`db/migration` directory.** That absence is a designed boundary rather than an
omission, and its POM records that adding a migration directory here would be an
affirmative defect: the relations this service reads are built over `ledger`,
`account`, `card` and `reference` tables that four *other* modules' migrations
create, so a Flyway history inside this module would order that DDL against the
wrong baseline and fail whenever reporting migrated before those four had.

Its entire readable surface is **seven read-only views** in the `reporting` schema,
created by
[`data-migration/sql/V1__reporting_views.sql`](../../data-migration/sql/V1__reporting_views.sql)
as an extract-transform-load step ordered *after* the per-service migrations, and
owned by `carddemo_reporting_owner`:

| View | Read by |
|---|---|
| `reporting.v_report_transactions` | `ReportTransactionView` |
| `reporting.v_statement_transactions` | `StatementTransactionView` |
| `reporting.v_transaction_types` | `TransactionTypeView` |
| `reporting.v_transaction_categories` | `TransactionCategoryView` |
| `reporting.v_accounts` | `AccountView` |
| `reporting.v_customers` | `CustomerView` |
| `reporting.v_card_xref` | `CardXrefView` |

The login role this service connects as, `carddemo_reporting`, holds `USAGE` on the
`reporting` schema and `SELECT` on those views, and **nothing else**:
`data-migration/sql/V0__schemas_and_roles.sql` revokes its `USAGE` and `SELECT` on all
four source schemas explicitly, so a query that reached past a view would fail on
privilege rather than return unmasked data. `CREATE` on the `reporting` schema is
revoked from it as well. That same file creates the one relation in this schema which
is *not* a projection and which this role may **not** select from,
`reporting.card_grouping_key`, and revokes it by name.

Assumptions: the owner role, not the login role, is the one holding `SELECT` on the
four source schemas. That split is what makes the views security barriers rather than
convenience: the view definition runs as its owner, so the login role can read the
masked projection without ever holding a privilege on the table beneath it.

## Configuration

Values are not reproduced here. **Ten** settings have **no fallback**, so an
incomplete environment stops at startup naming what it could not resolve rather than
serving requests bound to nothing:

| Variable | Selects | Secret |
|---|---|---|
| `SPRING_DATASOURCE_URL` | JDBC location of the cluster | |
| `SPRING_DATASOURCE_USERNAME` | The SELECT-only `carddemo_reporting` role | |
| `SPRING_DATASOURCE_PASSWORD` | Credential for that role | **yes** |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Issuer whose keys validate presented tokens | |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | App client a presented token must name | |
| `CARDDEMO_REPORTING_ARTIFACT_HMAC_KEY` | Keys the artifact-identity tokeniser that names every statement object | **yes** |
| `CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN` | The state machine an on-demand report execution starts | |
| `CARDDEMO_REPORTING_S3_OUTPUT_BUCKET` | Where statement and report artifacts are located | |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | Seals keyset cursors. **Not declared in `application.yml`** — supplying it is what publishes the shared `CursorToken` bean, which `api/ReportController` requires | **yes** |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | Opens the listener keystore; **not read when TLS is disabled** | **yes** |

Refactoring Rationale: the artifact key and the cursor key are recent additions to
this inventory and both were missing from it. Neither is optional and neither degrades:
omitting the artifact key stops the context with
`Could not resolve placeholder 'CARDDEMO_REPORTING_ARTIFACT_HMAC_KEY'` while creating
`artifactOpaqueIdentifier`, reached through `service/StatementService`; omitting the
cursor key stops it with `required a bean of type com.carddemo.common.web.CursorToken`
while creating `reportController`. Both were confirmed by running this module's jar
with each value withheld in turn, which is the only way either surfaces — no unit test
loads this application.

Assumptions: the artifact key is not decoration and it is not interchangeable with any
other key in the system. Every statement object is named by a token derived from it,
and an earlier scheme spelled the account identifier out in full followed by the last
four digits of the card — disclosing two protected values to a stream no
application-side control reaches. The token is **keyed** rather than a bare digest for
the reason the shared kernel records: an eleven-digit account identifier and a
sixteen-digit card number are both small enough to enumerate, so an unkeyed digest is
confirmed by guessing and would look like a control while disclosing what it withheld.
Alternatives Considered: a random identifier per artifact held in a mapping relation,
which is unconditionally unlinkable and therefore stronger — rejected because it needs
a writable relation and a row inserted per artifact, and **this context owns neither**;
a keyed token is computed on read, needs no storage, and is stable across runs, so a
rerun of a night overwrites the artifact it replaces instead of accumulating a second
copy under a new name.

Everything else — the group names, the database trust anchor, the Step Functions API
call timeout, the environment and version tags — carries a documented default in
[`application.yml`](src/main/resources/application.yml).

Ad-hoc workflow operation is documented in
[the batch runbook](../../docs/runbooks/batch-operations.md).

## Build and Test

<!-- test-inventory: 27 tests + 1 integration tests -->
**28** test classes across nine subpackages and the module root: **27** matching
`*Test`, run by Surefire, and **1** matching `*IT` — `repository/ReportingQueryBootstrapIT`
— run by Failsafe against Testcontainers-backed PostgreSQL. Every test package carries
a `package-info.java`, because the documentation gate audits test sources. That census
is machine-checked: `ServiceReadmeInventoryTest` in `common-lib` parses the comment
above and re-measures both figures against this module's test tree.

| Package | Classes |
|---|---|
| `mapper` | 8 |
| `config` | 5 |
| `api` | 3 |
| `service` | 2 |
| `dto` | 2 |
| `domain` | 1 |
| `fixtures` | 1 |
| `task` | 1 |
| `repository` | 1 (`ReportingQueryBootstrapIT`) |
| module root | 1 |

Assumptions: one integration class is the right number here, and what it proves — and
does not prove — is worth stating precisely. It provokes creation of all five
repository proxies against a real database, which is the moment every declared query
is handed to the entity manager and **parsed against the real metamodel**: a property
the metamodel does not carry, a join on a path that does not exist, or a projection
accessor with no matching alias is a defect no compiler and no mocked repository can
see, and four of the five roles declare their queries by method name where the fifth
writes them out. Trade-offs: it asserts that the five proxies came into being and not
that any query returned rows — **no relation is created in the container at all**, so
it does not prove the mappings match the seven views. That gap is deliberate and
recorded on the class: reproducing the views here would mean reproducing the base
tables four *other* services' migrations create, which is the same ordering problem
that keeps this module free of a migration directory in the first place.

```bash
# WHAT: run every test in this module, unit and integration alike.
# WHY : Assumptions: `verify` rather than `test`, because Failsafe binds to
#       `integration-test` and `verify`. Refactoring Rationale: this page used to
#       document `test` alone, which exercises 24 of the 25 classes and skips the ONE
#       class that checks the query-to-view binding — the single assertion no unit
#       test can stand in for. A container runtime is required.
mvn -B -f services/pom.xml -pl reporting-service -am verify
```

```bash
# WHAT: run the unit tier only, with no container runtime available.
# WHY : Trade-offs: faster, and it proves strictly less — use it while iterating, and
#       `verify` before pushing. Assumptions: the fixed-width edit masks, the
#       sanitized statement rendering and token validation cross the module boundary
#       into common-lib, which is what `-am` builds alongside it.
mvn -B -f services/pom.xml -pl reporting-service -am test
```

## Run

The entry point is `com.carddemo.reporting.ReportingApplication`. A local run needs a
reachable **database** and a reachable **token issuer** before it will start, in that
order: the datasource is opened while the context refreshes, and the resource server
fetches issuer discovery then too rather than on first request. This module declares
no `@SqsListener`, so no queue has to exist for it to start.

Secrets are prepared **out of band**, in a file that cannot be committed:

```bash
# WHAT: create the environment file with owner-only permissions, before writing
#       any value into it, then fill it in with an editor.
# WHY : Assumptions: the name is `.env.reporting-service.local` specifically because
#       `.gitignore` ignores `.env.*`, which the `git check-ignore` line confirms by
#       printing the rule and its line number. A name such as `reporting-service.env`
#       matches no ignore rule here and would be staged by `git add -A` along with the
#       database password, the artifact key and the cursor key.
# WHY : Assumptions: `umask 077` is applied BEFORE creation rather than corrected
#       afterwards, because a later `chmod` leaves a window in which the file was
#       group- and world-readable.
umask 077
touch .env.reporting-service.local
chmod 600 .env.reporting-service.local
git check-ignore -v .env.reporting-service.local

# Fill in one KEY=value per line, no `export` and no quoting, for the ten settings
# listed under Configuration (the keystore password may be omitted for a local run,
# because TLS is disabled below and the value is then never read).
```

```bash
# WHAT: build the bootable jar for this module, then start it.
# WHY : Assumptions: the jar produced by `spring-boot:repackage` is self-contained, so no class path is
#       assembled at launch.
# WHY : Assumptions: the credentials arrive by SOURCING the prepared file, so no secret is typed on this
#       command line, kept in shell history, or exposed in the process table. Refactoring Rationale: the
#       values used to be written inline as `VAR=<description>`; bash parses the unquoted `<` as an input
#       redirection, so the command failed before Java started with a message naming the word after the
#       angle bracket rather than anything about configuration. `bash -n` accepts that form, so only
#       running it revealed the defect.
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
# WHY : Assumptions: AWS_REGION is required to reach PREPARE-ENVIRONMENT, which is earlier than any bean
#       and earlier than the two AWS clients this module builds. `application.yml` here imports two
#       OPTIONAL configuration locations, a parameter-store prefix and a secrets-manager prefix, and the
#       resolver for each constructs a client -- and therefore resolves a region -- while the environment
#       is still being prepared. "Optional" makes a MISSING SECRET optional, not a missing region:
#       without this variable the process fails in about four seconds with
#       "Unable to load region from any of the providers in the chain", before a single bean exists.
#       Credentials are NOT needed to start, because they resolve on first call.
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
# WHY : Assumptions: the state-machine and bucket values may be well-formed but non-existent for a local
#       run, and that is the right local choice rather than a shortcut. Both are NETWORK LOCATIONS rather
#       than selectors, and neither is contacted on any path a local run exercises: report submission is
#       the only caller of the state machine, and the statement description only DERIVES an object
#       location without reading it. A deployment sets both from Terraform outputs.
mvn -B -f services/pom.xml -pl reporting-service -am package

set -a
. ./.env.reporting-service.local
set +a

export SPRING_PROFILES_ACTIVE=dev
export SERVER_SSL_ENABLED=false
export SERVER_ADDRESS=127.0.0.1
export AWS_REGION=us-east-1

java -jar services/reporting-service/target/reporting-service-1.0.0-SNAPSHOT.jar
```

Confirm the binding rather than trusting it:

```bash
# WHAT: show which address the listener accepted, and prove the port is
#       unreachable from the host's routable address.
# WHY : Alternatives Considered: `ss -lntp` or `netstat -lntp`. Rejected because
#       neither is present in every container this repository is developed in, and
#       their absence yields a "nothing is listening" answer that reads as a pass.
#       Assumptions: the address is hex and byte-reversed, so `0100007F` is
#       127.0.0.1 and `00000000` is every interface; the port is matched as the
#       literal hex `1F90` (8080) rather than converted with `strtonum`, which is a
#       GNU awk extension that mawk rejects. Trade-offs: `--noproxy '*'` is not
#       decoration — where a transparent HTTP proxy is configured, curl otherwise
#       answers from the proxy and never reaches the port under test.
awk 'NR>1 && $4=="0A" && $2 ~ /:1F90$/ {split($2,a,":"); print "listen address:", a[1]}' \
    /proc/net/tcp
curl -sf --noproxy '*' --max-time 5 "http://$(hostname -i | awk '{print $1}'):8080/actuator/health" \
    && echo 'REACHABLE OFF-HOST — server.address did not take effect' \
    || echo 'refused from the routable address, as intended'
```

## The sanctioned statement divergence, D-2

The reference statement generator has **two independent unchecked tables**, so there
are **two independent thresholds and no single combined limit**.
[`app/cbl/CBSTM03A.CBL`](../../app/cbl/CBSTM03A.CBL) declares
`WS-CARD-TBL OCCURS 51 TIMES` at L226 with a nested `WS-TRAN-TBL OCCURS 10 TIMES` at
L228, and separately `WS-TRN-TBL-CTR OCCURS 51 TIMES` at L232 — the only three
`OCCURS` clauses in the program, none bounds-checked. The measured consequences,
recorded in [`tests/README.md`](../../tests/README.md) §1.1, are:

| Axis | Renders | Faults at | Fault |
|---|---|---|---|
| Transactions on **one card** | up to **512** | the **513th** | inner same-card table overrun |
| **Distinct cards** in one run | up to **51** | the **52nd** | outer card table overrun |

`service/StatementService` uses dynamically sized collections with **no fixed arity**,
so neither threshold exists here and no bound has to be checked. The divergence is
therefore the *absence of a limit* rather than a change to any rendered output: within
both baseline bounds the two implementations produce the same statements, and the
existing suite's fixtures stay under both, so the parity comparison is unaffected. The
COBOL stays byte-identical because it is the behavioural oracle; the difference is
registered as
[D-2](../../docs/architecture/cobol-to-service-traceability.md) in the divergence
register.

Assumptions: the two figures must never be collapsed into one. A single
transaction-count figure is in circulation and it conflates them — it takes the outer
table's *card* arity and reports it as a *transaction* count, which understates the
same-card limit by an order of magnitude and mislabels the fault as a
transaction-volume problem when the outer overrun is a distinct-card problem. Anyone
sizing a fixture against the conflated figure would size it against the wrong axis
entirely.

## Behaviour worth knowing before changing anything

- **The report's fixed-width shape is a contract, not a format.** `mapper/CobolEditMask`
  carries seven masks from `app/cpy/CVTRA07Y.cpy` that COEXIST and are never
  interchangeable within the same 133-column line — the detail band uses
  `-ZZZ,ZZZ,ZZZ.ZZ` and the total bands `+ZZZ,ZZZ,ZZZ.ZZ`, each fifteen characters with
  zero blanked.
- **Detail lines page by key, subtotal bands do not.** The two reads are separate
  operations because they page differently: lines are keyset-paged over line ordinals,
  totals are a whole small set. Offset paging is not used anywhere here, because under
  concurrent inserts it skips and repeats rows.
- **Every object is written through the `sink` package, and a statement's key is a
  token.** `S3ArtifactWriter` issues the only `PutObject` calls in the module;
  `S3ReportSink` and `S3StatementSink` compose it, and the `task` classes hand it the
  prefix to write under. A statement's location is built as
  `s3://<bucket>/<statement-prefix><token><suffix>`, where the token is derived through
  the keyed artifact identity — so no account identifier and no card digits appear in
  the key.
- **The three `task` entry points are the batch face of the same code.**
  `GenerateStatementsTask`, `GenerateReportsTask` and `GenerateAdHocReportTask` run the
  same services the HTTP operations call, so a nightly run and an on-demand submission
  cannot diverge in what they render.

## The three batch tasks

The **same jar** runs either as the online service or as one batch task, and the
arguments alone decide which: `ReportingApplication.main` asks
`ReportingTaskRunner.isTaskInvocation(args)` first, and when any argument begins
`--job=` it runs that one task and calls `System.exit` with its status instead of
starting a server. Omitting `--job=` entirely starts the service. A clean run exits
**0** and a hard failure exits **8** — eight rather than one because the batch context
already publishes eight for the same tier, and one repository-wide value for "this run
failed" is what lets an operator dashboard alarm on a single number across both. Any
non-zero value would be refused correctly, since every gate that consumes the status
tests equality with zero.

```bash
# WHAT: the three published job names, with the options each requires.
# WHY : Assumptions: each date is a PARAMETER and never a clock read, so a rerun of
#       the same date reproduces the same artifacts — the property the reference gets
#       from a JCL PARM and the reason a nightly chain can be restarted at all.
set -a
. ./.env.reporting-service.local
set +a
export AWS_REGION=us-east-1
J=services/reporting-service/target/reporting-service-1.0.0-SNAPSHOT.jar

java -jar "$J" --job=generate-statements --business-date=2022-07-18
java -jar "$J" --job=generate-reports    --business-date=2022-07-18
java -jar "$J" --job=generate-report     --start-date=2022-07-01 --end-date=2022-07-18 \
    --report-type=monthly
```

Assumptions: the singular/plural difference between `generate-reports` and
`generate-report` is the orchestrator's own and is preserved deliberately — one is a
whole night's reports and the other is a single requested report, and collapsing them
would make an on-demand request indistinguishable from the nightly one in every log
line and every execution history.
