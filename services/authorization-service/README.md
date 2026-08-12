# Authorization Service

> **Purpose.** Document pending-authorization summary/detail, fraud marking,
> request consumption, outbox reply publication, purge, load, and unload.
>
> **Source of truth.** The eight programs under
> `app/app-authorization-ims-db2-mq/cbl/**` and
> `services/authorization-service/**`.

One deployable consumes the ordered authorization request queue and serves the
pending-authorization endpoints. The request and reply payloads keep the reference
system's delimited-string field contract exactly. Each request is decided and its
reply row written inside **one** local transaction, and the reply is published from
that row afterwards.

Refactoring Rationale: this paragraph said queue metadata used "opaque HMAC tokens
instead of PANs", and it no longer does. Sections 0.4.1.8 and 0.7.6 of the technical
specification freeze the reply queue's ordering and duplicate-suppression identities
as the literal values `MessageGroupId = card_num` and
`MessageDeduplicationId = transaction_id`; the derivation that predated them held
each guarantee inside this one producer and would have broken both for any other
party on the queue. The card number therefore does reach queue metadata, and that
exposure is registered as divergence
`D-AUTHORIZATION-FIFO-IDENTITY-METADATA` rather than described away. The
[messaging section](#messaging-contract) states the whole contract in one place.

## Prerequisite: run every command from the repository root

Every command on this page is written with paths relative to the **repository
root** — the directory containing `services/`, `app/` and `infra/`. Assumptions:
this is stated rather than assumed because the commands read naturally as though
they belonged beside this file, and running them from
`services/authorization-service/` makes Maven resolve `services/services/pom.xml`
and fail with a missing-POM error that names a path nobody wrote. Nothing here needs
to be run from the module directory.

```bash
# WHAT: confirm the working directory before running anything else on this page.
# WHY : Assumptions: the check tests for directories that exist only at the
#       repository root, which is cheaper and less brittle than comparing the
#       shell's path against a hard-coded name that differs per clone.
test -d services -a -d app || echo 'not at the repository root — cd there first'
```

## API surface

The contract of record is
[`src/main/resources/openapi/authorization-api.yaml`](src/main/resources/openapi/authorization-api.yaml).
Five operations are published across four paths, and
`src/test/java/com/carddemo/authorization/config/AuthorizationApiContractTest.java`
holds the document and the two controllers to each other in both directions.

| Method | Path | Operation | Authority | Purpose |
|---|---|---|---|---|
| `POST` | `/api/v1/authorizations/search` | `listPendingAuthorizations` | either business group | Read the account-level summary plus one keyset page of that account's pending authorizations, newest first, with the cursors for the pages after and before it. Collapses the browse loop of `COPAUS0C` into one request. |
| `GET` | `/api/v1/authorizations/{key}` | `getPendingAuthorization` | either business group | Read one authorization's full **stored** state. Display compositions the reference performs on the way to the terminal — the solidus in the card expiry, the fraud mark's separator and report date — are described beside each property and left to the client. |
| `GET` | `/api/v1/authorizations/{key}/screen` | `getPendingAuthorizationScreen` | either business group | Read the same authorization projected onto the 27-component shape `COPAUS1C` renders, plus the six components of screen chrome no segment holds. Every chrome value is derived here and none is accepted from the caller. |
| `GET` | `/api/v1/authorizations/{key}/next` | `getNextPendingAuthorization` | either business group | Read the authorization immediately older than the one named, which is the detail screen's forward paging move. At the oldest row the response carries the end-of-data indicator and no authorization. |
| `PUT` | `/api/v1/authorizations/{key}/fraud` | `setAuthorizationFraudState` | either business group | Set the fraud state to the state the body names and report what the write did. |

Assumptions: the search is a `POST` although it is a read, and the reason is
disclosure rather than semantics. The selector names an account, and a request line
is written into the access log of every intermediary, into browser history and into
a referrer header, none of which this service can redact afterwards. A request body
appears in none of them.

Assumptions: the fraud operation is **idempotent** and takes the target state,
where `COPAUS1C` L230–L243 takes no action argument, re-reads the segment at L234
and inverts what it found at L236–L241 — pressing its one key twice returns the row
to where it began. The difference is registered as divergence
`D-AUTH-FRAUD-TARGET-STATE`; a retried request that lands twice is the failure a
toggle turns into a silent state flip.

## Fraud marking admits either business group, and that is the reference behaviour

`config/SecurityConfig.java` guards `/api/v1/authorizations/*/fraud` with
`fraudAccess()`, which is
`AuthorityAuthorizationManager.hasAnyAuthority(BUSINESS_AUTHORITIES)` — the
administrator authority **and** the ordinary-user authority. The published contract
agrees: all five operations carry `x-required-authority: carddemo-user`, and the
document says in as many words that this admits either group.

Refactoring Rationale: this page said the rule restricted fraud marking to
`carddemo-admin` "because the baseline reaches its fraud-marking program from the
administrative menu only". Both halves were wrong, and the second is the one that
decides the rule. The two menu tables were read directly: `app/cpy/COMEN02Y.cpy` is
the **main** menu table, whose eleventh entry is option `11` at L86, named
`'Pending Authorization View         '` at L88, dispatching to `'COPAUS0C'` at L89
and carrying the access byte `'U'` at L90; the administrative table
`app/cpy/COADM02Y.cpy` declares six options and names `COPAUS` nowhere in the file.
From that ordinary-user screen the write is two steps away —
`app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl` takes `WHEN DFHPF5` at L187 into
the paragraph performed at L188, which links at L248–L252 to `COPAUS2C`, and that
program issues `INSERT INTO CARDDEMO.AUTHFRDS` at L142 and `UPDATE` at L223. So the
capability an ordinary user reaches is a durable write, not a view.

Trade-offs: what the restored rule costs is stated plainly rather than argued away,
because it is why the narrower rule was attractive. The only state-changing route
this context publishes is reachable by every signed-in holder of an ordinary-user
token, and the confirmation the migrated screen shows first is a client-side
affordance with no server-side rule behind it. That is also the reference system's
posture: the extension's three transaction definitions carry
`RESSEC(NO) CMDSEC(NO)` at L46, L56 and L66 of
`app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd`, so no per-resource and no
per-command check ran there either. A deployment that wants the narrower rule can
have it by changing that one method — but doing so is a behavioural change against
the oracle, so it must be implemented, registered in
[`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md)
and published in the contract before it ships. Documenting it while the code admits
either group is the state this page was in.

## Data ownership

The `authorization` schema is created and owned by this module's Flyway migrations
and read or written by nothing else; this context reads no other context's schema.

| Object | Kind | Carries |
|---|---|---|
| `pending_auth_summary` | table | The account-level summary segment, including the five discrete `account_status_1..5` columns that stand for `PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES` |
| `pending_auth_detail` | table | One row per pending authorization, keyed by the composite the packed IMS key expresses, with the reply-code and match-status domains as check constraints |
| `auth_fraud` | table | What the baseline writes to its Db2 fraud table |
| `idx_auth_fraud_card_recent` | index | Card ascending, authorization instant descending — the order `XAUTHFRD` provides |
| `auth_reply_outbox` | table | The transactional outbox. **No baseline counterpart**; it exists for the reason set out under [D-5](#divergences-registered-against-this-context) |
| `idx_auth_reply_outbox_unpublished`, `_group`, `_published` | indexes | The drain's claim scan, per-group ordering, and the retention sweep |

Assumptions: four tables is the count every authority states —
[`docs/architecture/service-catalog.md`](../../docs/architecture/service-catalog.md)
lists the same four as this context's owned tables and
[`docs/architecture/data-model-and-schema-mapping.md`](../../docs/architecture/data-model-and-schema-mapping.md)
specifies the outbox column by column. The authoritative column lists are the
migrations under
[`src/main/resources/db/migration`](src/main/resources/db/migration), not this page:
`V1__authorization.sql` creates all four, `V2` adds the drain's claim version and
`V3` adds the two FIFO identity columns.

Assumptions: publication state is a nullable `published_at` rather than a status
enumeration, and retention is bounded by the purge job migrated from `CBPAUP0C`
rather than by a database job. The outbox holds a primary account number in every
row, so an unbounded table is a growing disclosure surface, not merely a large one.

## Messaging contract

| Target | Replaces | Direction | Notes |
|---|---|---|---|
| `carddemo-pauth-request-<env>.fifo` | `AWS.M2.CARDDEMO.PAUTH.REQUEST` | **consumed** | One `@SqsListener`, registered under the identifier `ContainerCyclingWindowBoundary.REQUEST_CONTAINER_ID` |
| `carddemo-pauth-reply-<env>.fifo` | `AWS.M2.CARDDEMO.PAUTH.REPLY` | **published** | Never consumed here. `service/OutboxPublisher` drains `auth_reply_outbox` and sends to the destination recorded on the row |
| each queue's dead-letter queue | — | quarantine | `maxReceiveCount` 5. Later messages may proceed; native bulk redrive is denied and recovery is reviewed per-message replay after reconciliation |

Refactoring Rationale: the count above is **one** consumed queue. This page and the
module's own package charter both said two were consumed, which described a reply
consumer that does not exist and hid the mechanism that does: the reply row is
committed with the decision and published from the outbox afterwards.

### The wire shape is the contract

Because both payloads are declared as delimited strings, field order and the
delimiter *are* the interface. `common-lib`'s `CsvAuthCodec` owns both directions and
publishes the numbers as constants rather than leaving them to a reader:

| | Fields | Declared widths sum | On the wire |
|---|---|---|---|
| Request | **18** | 153 | **170** |
| Reply | **6** | 57 | **63** emitted, **63 or 64** accepted |

Assumptions: the wire length exceeds the declared sum because the reference
assembles with a `STRING ... DELIMITED BY SIZE` that writes a separator after **all**
fields including the last. Any count of five reply delimiters, and the shorter length
it implies, is superseded.

Assumptions: the reference **transmits 64 bytes** for the 63 characters it builds.
`WS-RESP-LENGTH` is declared `PIC S9(4) VALUE 1` at `cbl/COPAUA0C.cbl` L46 and used
as the `WITH POINTER` of the assembly at L730, so it is a one-based cursor standing
at 64 once 63 characters are written; L756 then reuses that same cursor as the put
length, appending one trailing byte of the 200-byte put buffer. This service encodes
exactly 63 and decodes either, so a message from the reference producer still parses.
The difference is registered as divergence `D-REPLY-PUT-LENGTH`.

### Descriptor fields, ordering, and the one gap

The correlation identifier becomes `correlationId`; the message identifier becomes
`messageId`; the reply-to queue becomes `replyToQueueUrl`; the string-format
indicator set at L751 outbound and L397 inbound becomes a `contentType` of
`text/csv`; the non-persistent delivery at L749 becomes short reply-queue retention.

- **Ordering and duplicate suppression** use the literal `card_num` and
  `transaction_id`, for the reason given at the top of this page. Trade-offs: both
  become message metadata, which a queue's server-side encryption of a *body* does
  not cover, so the card number reaches queue telemetry and the trace of every send.
  The exposure is bounded by the deployment — customer-managed-key encryption, an
  interface endpoint inside the private network, and read access scoped to task
  roles — and the judgement belongs to the specification rather than to this module.
- **The five-second reply deadline at L750 has no target equivalent**, because SQS has
  no per-message time to live. It travels instead as the `expiresAt` message
  attribute, which the consumer honours by dropping and logging a stale message, and
  the reply queues carry short retention behind it. Recorded in
  [`ADR-004`](../../docs/adr/ADR-004-messaging.md).
- **The 500-message processing limit becomes a bounded long-poll loop** and the
  five-second get-with-wait becomes an equivalent receive wait, so throughput
  characteristics do not shift silently.

## Divergences registered against this context

Both are registered in
[`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md);
neither is described only here, and the COBOL they diverge from stays byte-identical
because it is the behavioural oracle.

- **D-5 — the reply is published from an outbox.** The baseline sends the response
  before the data lands: `cbl/COPAUA0C.cbl` sends at L461 through the paragraph
  beginning at L738 whose `MQPUT1` is at L758, writes the authorization afterwards at
  L463–L465, and commits once at L335. Neither message operation joins that commit —
  the get computes no-syncpoint options at L389–L391 and the put does the same at
  L753–L754 — so there is a window in which a reply was sent for data never
  committed, or data was committed and the reply lost. Here the reply is an outbox row
  written inside the decision's own transaction and drained afterwards, so exactly the
  committed decisions are the ones a reply exists for.
- **D-6 — the distributed transaction collapses to a local one.** In the baseline one
  CICS transaction spans two programs and two resource managers: `CPVD` enters
  `cbl/COPAUS1C.cbl`, which reaches `cbl/COPAUS2C.cbl` by `EXEC CICS LINK` at
  L248–L252, so both run under one unit of work; `COPAUS1C` replaces an IMS segment
  with `EXEC DLI REPL` at L525–L528 and commits at L557–L558 with a rollback path at
  L567, while `COPAUS2C` writes the relational fraud table at L141–L142 and L222–L223
  under the Db2 plan bound to that transaction at `csd/CRDDEMO2.csd` L69 and L75–L77.
  Two managers in one commit is a two-phase commit. Here all four tables live in the
  one `authorization` schema, so there is one resource manager and the two-phase
  commit is **eliminated rather than emulated** — and the outbox joins that same local
  transaction, which extends the guarantee to the message the baseline published
  outside its commit.

## Security contract

`config/SecurityConfig.java` owns the filter chain, the group-to-authority conversion
and the decoder, and that decoder adds the token-kind, client and scope checks the
issuer location alone does not make.

| Path | Rule |
|---|---|
| `/actuator/health/**` | `permitAll` — the target group and the container probe poll it unauthenticated, and health details are never shown, in any profile |
| `/actuator/prometheus` | `loopbackOnly()` — a **network-position** rule over `127.0.0.1/32` and `::1/128`, because the scraper presents no token |
| `/api/v1/authorizations/*/fraud` | `fraudAccess()` — either business group, as argued above |
| `/api/v1/authorizations/**` | the read decision — either business group |
| anything else | **`denyAll()`**, not `authenticated()` |

Assumptions: the catch-all denies rather than merely requiring authentication, and
the difference is not academic here. An earlier revision of the fraud path pattern
omitted the `/api/v1` prefix, so it matched no request this service can receive and
fraud marking fell through to a catch-all that asked only for a token — which any
authenticated caller satisfied. `management.endpoints.web.exposure.include` names
`health,prometheus` and not the wildcard, so no other actuator endpoint is reachable
even before the chain refuses it.

## Configuration

Values are not reproduced here. **Fourteen** settings have **no fallback**, so an
incomplete environment stops at startup naming what it could not resolve rather than
running a consumer bound to nothing — which matters more here than elsewhere,
because a consumer pointed at the wrong queue is silent rather than broken.

| Variable | Selects | Secret |
|---|---|---|
| `SPRING_DATASOURCE_URL` | JDBC location of the cluster | |
| `SPRING_DATASOURCE_USERNAME` | Runtime `authorization` role | |
| `SPRING_DATASOURCE_PASSWORD` | Credential for that role | **yes** |
| `SPRING_FLYWAY_USER` | **Migration** role, distinct from the runtime role, which owns the schema and its four tables | |
| `SPRING_FLYWAY_PASSWORD` | Credential for the migration role | **yes** |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Issuer whose keys validate presented tokens | |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | App client a presented token must name | |
| `CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE` | The one queue this context consumes | |
| `CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST` | Comma-separated **queue addresses** a reply may be sent to | |
| `CARDDEMO_MESSAGING_HMAC_KEY` | Keys the one tokeniser this context holds | **yes** |
| `CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY` | Seals the internal token presented on the three calls into the account context | **yes** |
| `CARDDEMO_ACCOUNT_CONTEXT_BASE_URL` | Where the account context answers; must be an absolute **HTTPS** origin with no path, query or user information | |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | Seals keyset cursors. **Not declared in `application.yml`** — supplying it is what publishes the shared `CursorToken` bean, which `mapper/PendingAuthViewMapper` requires, so a run without it fails naming that type | **yes** |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | Opens the listener keystore; **not read when TLS is disabled** | **yes** |

Refactoring Rationale: this page previously described the queue configuration as
"the three queue names", and named only the messaging key and the datasource password
as secret. There are **two** queue-shaped settings, not three — one consumed queue and
one allowlist of send destinations — and the secret inventory omitted three entries:
the internal-service signing key, the Flyway credential, and the cursor signing key.
A deployment provisioned from that inventory would have failed at startup on the
first and third and silently migrated as the runtime role for the second.

Assumptions: the allowlist entries are **addresses**, not names. Each is passed
unchanged as the destination of a send, so `config/SqsConfig` refuses at startup any
entry that does not begin `https://` or does not end with the ordered-queue suffix —
naming the property rather than failing later on the reply path. A local run may
therefore use a well-formed address that is never contacted, because nothing is sent
until a request has been consumed and decided.

Assumptions: the allowlist is applied where the destination ARRIVES, in the listener,
and not where the send happens. The reply-to address is chosen by whoever can put a
message on the request queue, so a request naming an unlisted destination is refused
before any decision is recorded and no outbox row is written for it — which is why the
publisher can send to the address on the row without re-checking it. Dynamic routing
decides *where* a reply goes from the message; the allowlist decides which destinations
are legitimate at all, and both are needed.

Everything else — the group names, the database trust anchor, the outbox batch size,
the container window bounds, the environment and version tags — carries a documented
default in [`application.yml`](src/main/resources/application.yml).

### Where the five secrets come from, and who else holds them

Every value is generated by the selected Terraform environment root into Secrets
Manager and injected as a container secret; none has a default and none appears in a
`tfvars` file. `infra/modules/ecs-service` asserts the injection in **both**
directions — the services that must receive a name, and only those — so a deployment
that forgets one or hands one to the wrong service fails at `plan` rather than at
container start. The number of holders differs per secret and is the part most easily
assumed wrong:

| Secret | Held by | Rotation |
|---|---|---|
| `SPRING_DATASOURCE_PASSWORD` | this service only | composed by `infra/modules/secrets` with the cluster credential |
| `SPRING_FLYWAY_PASSWORD` | this service only, and used **before** the runtime credential | as above |
| `CARDDEMO_MESSAGING_HMAC_KEY` | **this service alone** | attended, in [`docs/runbooks/batch-operations.md`](../../docs/runbooks/batch-operations.md) |
| `CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY` | **two tasks** — this service signs with it and `account-service` verifies against it | attended and **not** a rolling change, in [`docs/runbooks/deploy.md`](../../docs/runbooks/deploy.md) |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | **seven tasks**, sharing ONE value per environment — auth, account, card, transaction, reference, authorization and reporting | attended, in [`docs/runbooks/deploy.md`](../../docs/runbooks/deploy.md); rotating it refuses every cursor a client currently holds |

Assumptions: the holder counts are the part to read carefully, because each implies a
different rotation. The internal-identity key has two holders by necessity, the
messaging key one by design, and the cursor key seven sharing a single value — so
rotating the cursor key is an action whose blast radius is every browsing client in
the deployment, not just this service, while rotating the messaging key touches
nothing else at all. Conflating them would make the first two look rolling. The signing key is symmetric, so signer and verifier must hold the same
bytes; each consuming task reads its value once at start-up and the verifier holds
exactly one key per subject with no predecessor, which is why replacing it has an
unavoidable refusal window confined to this one caller. It is also **per caller**
rather than shared with `transaction-service`: with shared bytes the subject a token
asserts would be a value its holder writes rather than a property the verifier can
check, so either caller could mint as the other.

Assumptions: `CARDDEMO_MESSAGING_HMAC_KEY` is a different secret again from the
extract-transform-load masking key documented in
[`data-migration/README.md`](../../data-migration/README.md), and from the cursor key
above. Different holders, different trust purposes: sharing one value would let a
one-off migration job that reads cardholder extracts compute values a production
consumer derives, and would make rotating either purpose require stopping an
interactive consumer and a batch workload together.

Assumptions: no task role holds `secretsmanager:PutSecretValue` for any of these. A
task reads its entries and never writes them, so a compromised task cannot make its
own key the accepted one.

## Build and Test

<!-- test-inventory: 68 tests + 10 integration tests -->
**78** test classes across ten subpackages: **68** matching `*Test`, run by
Surefire, and **10** matching `*IT`, run by Failsafe against Testcontainers-backed
PostgreSQL. Every test package carries a `package-info.java`, because the
documentation gate audits test sources. That census is machine-checked —
`ServiceReadmeInventoryTest` in `common-lib` parses the comment above and
re-measures both figures against this module's test tree.

| Package | `*Test` | `*IT` | What the integration tier proves here |
|---|---|---|---|
| `service` | 14 | 4 | The decision unit of work, the fraud-marking boundary, the outbox drain's publication lifecycle, and that a purge window rolls back as one |
| `fixtures` | 11 | 1 | The fraud-domain fixtures against real columns |
| `mapper` | 10 | — | |
| `config` | 10 | — | |
| `dto` | 8 | — | |
| `domain` | 7 | — | |
| `repository` | — | 5 | Composite keys, key and reply-code domains, parentage, keyset paging, the fraud index order, and the outbox claim |
| `api` | 2 | — | |
| `contract` | 1 | — | |
| `task` | 1 | — | |

```bash
# WHAT: run every test in this module, unit and integration alike.
# WHY : Assumptions: `verify` rather than `test`, because Failsafe binds to
#       `integration-test` and `verify`. Refactoring Rationale: this page used to
#       document `test` alone, which exercises 64 of the 74 classes and silently
#       skips all TEN Testcontainers-backed classes — every assertion about the
#       single-transaction decision, the outbox drain and the purge rollback, which
#       are precisely the properties D-5 and D-6 exist for. A container runtime is
#       required.
mvn -B -f services/pom.xml -pl authorization-service -am verify
```

```bash
# WHAT: run the unit tier only, with no container runtime available.
# WHY : Trade-offs: faster, and it proves strictly less — use it while iterating,
#       and `verify` before pushing. Assumptions: the codec bounds, the
#       control-character refusal and the error redaction this service relies on are
#       common-lib behaviours, which is what `-am` builds alongside it.
mvn -B -f services/pom.xml -pl authorization-service -am test
```

## Run

The entry point is `com.carddemo.authorization.AuthorizationApplication`. A local
run needs four things reachable, in this order, and each failure names itself:

1. **the database**, whose *migration* credential is used before anything else;
2. **the token issuer**, whose discovery document the resource server fetches while
   the context refreshes rather than on first request;
3. **a free port**;
4. **the request queue, existing** — `service/AuthorizationRequestListener` resolves
   its `@SqsListener` queue placeholder when the endpoint is *registered*, so an
   absent or unknown queue aborts context refresh rather than producing an idle
   consumer. That is the correct failure for the reason above.

Assumptions: the database also needs
`data-migration/sql/V2__runtime_delete_grants.sql` applied **after** this module's
Flyway migrations have created its tables. `V0` runs before any table exists, so the
only privilege forms available to it are schema-wide, and it withholds `DELETE`
entirely; `V2` grants it one table at a time. Without it the service starts and looks
healthy while the outbox retention sweep fails on every pass with
`permission denied for table auth_reply_outbox` — and that table holds a primary
account number in every row.

Secrets are prepared **out of band**, in a file that cannot be committed:

```bash
# WHAT: create the environment file with owner-only permissions, before writing
#       any value into it, then fill it in with an editor.
# WHY : Assumptions: the name is `.env.authorization-service.local` specifically
#       because `.gitignore` ignores `.env.*`, which the `git check-ignore` line
#       confirms by printing the rule and its line number. A name such as
#       `authorization-service.env` matches no ignore rule here and would be staged
#       by `git add -A` along with four secrets.
# WHY : Assumptions: `umask 077` is applied BEFORE creation rather than corrected
#       afterwards, because a later `chmod` leaves a window in which the file was
#       group- and world-readable.
umask 077
touch .env.authorization-service.local
chmod 600 .env.authorization-service.local
git check-ignore -v .env.authorization-service.local

# Fill in one KEY=value per line, no `export` and no quoting, for the fourteen
# settings listed under Configuration (the keystore password may be omitted for a
# local run, because TLS is disabled below and the value is then never read).
```

```bash
# WHAT: build the bootable jar for this module, then start it.
# WHY : Assumptions: FOURTEEN variables have no fallback anywhere -- more than any sibling except the
#       account context -- and §Configuration lists every one. The command below supplies all fourteen,
#       so an incomplete environment stops at startup rather than starting a consumer bound to nothing.
#       That matters more here than elsewhere: a consumer pointed at the wrong queue is silent rather
#       than broken.
# WHY : Refactoring Rationale: this note said "the three queue names". There are TWO messaging
#       destination variables -- CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE and
#       CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST -- and no third; the reply destination is an
#       allowlist rather than a single address precisely because the reply queue arrives on the
#       message. Counting a third invited a reader to look for a variable that does not exist.
# WHY : Trade-offs: the failure names the SYMPTOM rather than the key for the framework-bound values.
#       Measured on the sibling transaction service with its Flyway rows omitted, the first failure was
#       `FATAL: password authentication failed for user "${SPRING_FLYWAY_USER}"` -- Spring Boot's
#       binder leaves an unresolvable placeholder as its own literal text. The values read through
#       `@Value` do name their own property. An earlier revision of this note claimed the startup
#       failure names the missing key in every case; it does not.
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
# WHY : Assumptions: AWS_REGION is needed for the queue client this context cannot run without. Unlike
#       some of its peers `application.yml` here declares no static region at all, deliberately, so that a
#       deployed task resolves it from its own environment; this variable is the first entry in the SDK's
#       own resolution chain, so naming it locally reaches the same setting without adding a key. Without
#       it the chain falls through to instance metadata discovery and waits before failing.
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
mvn -B -f services/pom.xml -pl authorization-service -am package

SERVER_SSL_ENABLED=false \
AWS_REGION=us-east-1 \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/carddemo \
SPRING_DATASOURCE_USERNAME=carddemo_authorization \
SPRING_DATASOURCE_PASSWORD=<from your local secret store> \
SPRING_FLYWAY_USER=carddemo_authorization_migrator \
SPRING_FLYWAY_PASSWORD=<from your local secret store> \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=<user pool issuer> \
CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID=<app client id> \
CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD=<any value; unused while TLS is off> \
CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE=<request queue name> \
CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST=<comma-separated reply queue URLs> \
CARDDEMO_MESSAGING_HMAC_KEY=<from your local secret store; >= 32 bytes> \
CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY=<from your local secret store; >= 32 bytes> \
CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY=<from your local secret store; base64, >= 32 bytes> \
CARDDEMO_ACCOUNT_CONTEXT_BASE_URL=<account-service origin> \
java -jar services/authorization-service/target/authorization-service-1.0.0-SNAPSHOT.jar
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

## The two maintenance jobs

The **same jar** runs either as the online service or as one maintenance job, and
which one it does is decided by the arguments alone: `AuthorizationApplication.main`
asks `task/MaintenanceTaskRunner.isTaskInvocation(args)` first, and when any argument
begins `--job=` it runs that one task and calls `System.exit` with its status instead
of starting a server. `JOB_NAMES` publishes exactly two, migrated from `PAUDBLOD`,
`PAUDBUNL`/`DBUNLDGS` and `CBPAUP0C`; an unknown name is refused with that list and
the usage text.

Assumptions: two exit paths in one class is the accepted cost, and the alternative was
worse. A second bootable artifact would double the images to build, scan and deploy
for one shared context; a scheduled bean inside the running service would fire on
every replica at once and leave the run no exit status for an orchestrator to branch
on.

```bash
# WHAT: load the two authorization extracts, then purge as at a business date. Both
#       need the same environment the service does, so source the prepared file first.
# WHY : Assumptions: the business date is a PARAMETER and never a clock read, so a
#       rerun of the same date produces the same result — the property the reference
#       gets from a JCL PARM and the reason a nightly chain can be restarted at all.
set -a
. ./.env.authorization-service.local
set +a
export AWS_REGION=us-east-1

Assumptions: this is a **different** secret from `CARDDEMO_MASK_HMAC_KEY`, the
extract-transform-load redaction key documented in
[`data-migration/README.md`](../../data-migration/README.md). The two have
different holders and different trust purposes, and sharing one value would let a
one-off migration job that reads cardholder extracts compute production queue
group identities — as well as making a rotation of either purpose require stopping
an interactive consumer and a batch workload together. The key is accepted in
base64 or as raw text; either way it must decode to at least
`OpaqueIdentifier.MIN_KEY_LENGTH` bytes.

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
| `SPRING_DATASOURCE_USERNAME` | — | Secrets Manager (`authorization` schema runtime role) | none |
| `SPRING_DATASOURCE_PASSWORD` | — | Secrets Manager | none |
| `SPRING_FLYWAY_USER` | — | Secrets Manager (migration role, not the runtime one) | none |
| `SPRING_FLYWAY_PASSWORD` | — | Secrets Manager | none |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | — | Terraform output (Cognito user pool issuer) | none |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | — | Parameter Store (Cognito app client) | none |
| `CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE` | — | Parameter Store (SQS FIFO request queue) | none |
| `CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST` | — | Parameter Store (comma-separated reply queue URLs) | none |
| `CARDDEMO_MESSAGING_HMAC_KEY` | — | Secrets Manager | none |
| `CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY` | — | Secrets Manager | none |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | — | Secrets Manager | none |
| `CARDDEMO_ACCOUNT_CONTEXT_BASE_URL` | — | Parameter Store (internal load balancer) | none |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | — | minted per task by the image entry point | none |
| `CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN` | — | Parameter Store | the base URL above — but the module requires it anyway |
| `CARDDEMO_VERSION` | — | ECS task definition (image tag or digest) | `unspecified`, which the service module **refuses** |
| `CARDDEMO_ONLINE_WRITES_PARAMETER` | — | ECS task definition (SSM parameter name) | none in effect — see below |
| `AWS_REGION` | — | ECS task definition | none read here — see below |
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

Assumptions: `AWS_REGION` carries no fallback and is nonetheless not in this workload's
required-plain set, which looks like an inconsistency and is not. Unlike its peers,
`application.yml` here declares **no** `spring.cloud.aws.region.static` at all — so the
region is not a property this service reads, it is the first entry in the SDK's own
resolution chain. Naming it locally reaches the same setting without adding a key, and
both environment roots supply it to every workload regardless. Without it the chain falls
through to instance-metadata discovery and waits before failing, which is why the launch
command above sets it.

Assumptions: `CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN` is the one row whose fallback and
whose requirement disagree, deliberately. It defaults to
`CARDDEMO_ACCOUNT_CONTEXT_BASE_URL`, so a local run needs only the base URL; but
`infra/modules/ecs-service` requires the approved origin **by name** for this workload,
because in a deployment the two are allowed to differ — the origin a redirect is checked
against is a security decision that must not silently inherit whatever address the client
happens to be configured with.

Assumptions: **two required names are not written as `${...}` placeholders in any
profile**, so a reader auditing the YAML for placeholders will not find them and could
reasonably conclude they are optional. They are not. Spring's relaxed binding maps
`CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` onto `carddemo.pagination.cursor.signing-key`
and `CARDDEMO_ONLINE_WRITES_PARAMETER` onto `carddemo.online-writes.parameter`, and both
properties are declared in the shared kernel's auto-configuration rather than here:

- The cursor signing key is **required of this workload by name**, and its absence removes
  the `CursorToken` bean that `mapper/PendingAuthViewMapper` takes as its only constructor
  argument. Assumptions: unlike its six siblings, this context does not reach that bean
  from an annotated component — `PendingAuthViewMapper` is deliberately **not** annotated,
  and its own Javadoc records why, on the premise that no application publishes a
  `CursorToken`. That premise is now out of date: the shared kernel's auto-configuration
  publishes one whenever this property is set. **The gap that leaves is recorded here
  rather than papered over: three `@Service` classes in `.service` require a
  `PendingAuthViewMapper` by constructor, and no configuration in this module's main tree
  publishes one — only the integration tests construct it, each in its own bespoke test
  application.** So supplying this variable is necessary and, on its own, not sufficient
  for this context to start; publishing the mapper (annotating it, or adding a `@Bean` in
  `.config` beside the other key-derived beans) is the outstanding change, and it is
  deliberately not made here because it is a wiring decision rather than a documentation
  correction.
- The online-write gate **removes itself silently**. Both its beans are conditional on the
  same property and nothing outside the auto-configuration injects them, so an absent
  value leaves the fraud-marking write accepting traffic during the nightly batch window
  with nothing in the log to say the gate is gone. `infra/modules/ecs-service` therefore
  makes the name **biconditional** for the seven web workloads: a root that omits it, or
  that supplies it to the batch task, fails at `plan`.

Assumptions: the service module requires fifteen of these by name for this workload —
seven through Parameter Store, seven through Secrets Manager and `CARDDEMO_VERSION` as a
plain value — so a root that drops one fails at `plan` rather than producing a task that
starts and then consumes nothing. The lists are in
[`infra/modules/ecs-service/main.tf`](../../infra/modules/ecs-service/main.tf) under
`required_parameter_environment_names`, `required_secret_environment_names` and
`required_plain_environment_names`.
