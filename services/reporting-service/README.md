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
CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN=<state machine arn, or a local placeholder> \
CARDDEMO_REPORTING_S3_OUTPUT_BUCKET=<dataset bucket name, or a local placeholder> \
java -jar services/reporting-service/target/reporting-service-1.0.0-SNAPSHOT.jar
```

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

<!--
WHY : Refactoring Rationale: the two statement operations are POST even though both
are reads, and the reason is disclosure rather than semantics. A statement is
selected by a primary account number, and a request line is written into the access
log of every intermediary between the browser and this service, into browser history
and into a referrer header -- none of which this service can redact after the fact. A
request body appears in none of them, so no operation in this contract carries a path
template at all, and the contract test asserts that as a property rather than leaving
it to review.

WHY : Assumptions: every operation requires the ordinary-user group authority, which
is the whole of what the filter chain enforces -- it admits a principal holding either
configured group and refuses everything else. No operation here is administrative,
because nothing this service returns is wider than what a cardholder-facing caller may
read: an account number is narrowed to its last four digits and no verification value
is returned by any endpoint.
-->
