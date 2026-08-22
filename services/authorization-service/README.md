# Authorization Service

> **Purpose.** The pending-authorization bounded context. One deployable consumes the
> ordered authorization request queue, decides each request, serves the
> pending-authorization summary and detail endpoints, toggles the fraud mark, and
> carries the load, unload and purge maintenance jobs.
>
> **Parameters — source of truth.** Every behavioural claim on this page is cited to a
> path **and a line**. The behavioural oracle is the eight COBOL programs under
> [`app/app-authorization-ims-db2-mq/cbl/`](../../app/app-authorization-ims-db2-mq/cbl)
> with their copybooks in `../cpy/`, their IMS definitions in `../ims/`, their Db2
> definitions in `../ddl/` and their CICS resource definitions in `../csd/`. That tree
> is **REFERENCE-ONLY and is never modified** — see [§12](#12-what-must-not-be-added-here).
> The implementation of record is this module's own tree, and where the two differ the
> difference is a registered divergence in [§6](#6-divergences-registered-against-this-context).
>
> **Return values.** A REST surface published as OpenAPI 3.1 at
> [`src/main/resources/openapi/authorization-api.yaml`](src/main/resources/openapi/authorization-api.yaml),
> consumed by the typed client [`ui/src/api/authorization.ts`](../../ui/src/api/authorization.ts);
> a reply message on the ordered reply queue; and the `authorization` PostgreSQL schema
> this context alone owns. The screen routes this context is assigned are
> `/authorizations` and `/authorizations/:key`, both authored as
> `ui/src/screens/authSummary` and `ui/src/screens/authDetail` and mounted by
> `ui/src/router.tsx` — see [§13](#13-honest-boundaries--what-this-modules-tests-do-not-prove)
> for what a declared route does and does not assert.
>
> **Exceptions or errors.** The honest limits are stated rather than implied. **No
> golden master exists for any path in this context** — the caveat is set out in full in
> [§13](#13-honest-boundaries--what-this-modules-tests-do-not-prove), and it is the most
> important thing on this page to read before trusting a parity claim.

Assumptions: this page documents a module whose `pom.xml` and `Dockerfile` already
exist beside it, and it describes what those two files and this module's source
actually do rather than what a plan intended. Where a specification figure and the
code disagreed, the code and the reference COBOL were re-read and the figure corrected
here; each such correction is called out at the point it matters, because a README that
silently restates a wrong number is worse than one that has no number at all.

## 1. What this module is

Migrated **wholly** from the `app/app-authorization-ims-db2-mq` extension tree. That
tree is one of the three optional extensions the root
[`README.md`](../../README.md) describes, and the root README's own framing explains
why its style differs from the base application: CardDemo *"intentionally incorporates
various coding styles and patterns to exercise analysis, transformation, and migration
tooling"* (root `README.md` L39).

### 1.1 Eight programs, not seven

⚠ `COPAUS2C` is a real program that a casual count misses, because it is reached by
`EXEC CICS LINK` from the detail screen rather than from a menu. **The filename casing
is mixed in the reference tree and is reproduced exactly here**; the three utilities
are upper-case and the five transaction programs are lower-case.

| Reference program | Migrated to | Role |
|---|---|---|
| `COPAUS0C.cbl` | `service/PendingAuthSummaryService` | Summary list, keyset-paged |
| `COPAUS1C.cbl` | `service/PendingAuthDetailService` | Detail view and the fraud action's entry |
| `COPAUS2C.cbl` | `service/FraudMarkingService` | The relational fraud write |
| `COPAUA0C.cbl` | `service/AuthorizationRequestListener`, `service/AuthorizationDecisionService`, `service/OutboxPublisher` | Request consumption, decision, reply publication |
| `CBPAUP0C.cbl` | `service/PurgeJob` | Expiry and purge |
| `PAUDBLOD.CBL` | `service/LoadService` | Segment load |
| `PAUDBUNL.CBL` | `service/UnloadService` | Segment unload, prefixed shape |
| `DBUNLDGS.CBL` | `service/UnloadService` | Segment unload, GSAM shape |

Assumptions: the casing is a live hazard on a case-sensitive filesystem, and it splits
by directory rather than by convention. Verified by listing the tree: `cbl/` holds
lower-case `.cbl` for `COPAUS0C.cbl`, `COPAUS1C.cbl`, `COPAUS2C.cbl`, `COPAUA0C.cbl`
and `CBPAUP0C.cbl`, and upper-case `.CBL` for `PAUDBLOD.CBL`, `PAUDBUNL.CBL` and
`DBUNLDGS.CBL`. The same split runs through the copybooks: the extension symbolic maps
under `cpy-bms/` are lower-case (`COPAU00.cpy`, `COPAU01.cpy`), while the three
program-communication-block copybooks under `cpy/` are upper-case (`PADFLPCB.CPY`,
`PASFLPCB.CPY`, `PAUTBPCB.CPY`) even though their five siblings in that same directory
are lower-case. A path written with the wrong case resolves on a developer's
case-insensitive machine and fails in the container.

### 1.2 Where to find this feature under its house names

The reference system names this feature four different ways, none of them
"authorization service". Searching the root README for any of these finds it:

| House name | What it is | Cited at |
|---|---|---|
| `CPVS` | CICS transaction — summary screen | root `README.md` L303; `csd/CRDDEMO2.csd` L49 |
| `CPVD` | CICS transaction — detail screen and the fraud write | root `README.md` L304; `csd/CRDDEMO2.csd` L39 |
| `CP00` | CICS transaction — the queue-triggered consumer | root `README.md` L305; `csd/CRDDEMO2.csd` L59 |
| `CBPAUP0J` | Batch job — purge expired authorizations | root `README.md` L253 and L344 |
| "Option 11 (Pending Authorizations)" | The main-menu entry point | root `README.md` L358 |

Assumptions: two gaps in the house inventory are recorded here rather than left for a
reader to trip over, because this page is otherwise the only place the full set is
listed. First, **`COPAUS2C` appears in no root README table** — the online components
table lists `COPAUS0C`, `COPAUS1C` and `COPAUA0C` at L303–L305 and stops, even though
`COPAUS2C` is a real program reached from the `CPVD` screen and is the one that writes
the relational fraud row. Second, **the three load and unload utilities appear in no
root README table either** — the batch components table names only `CBPAUP0J`/`CBPAUP0C`
at L344. This module covers all eight regardless, and [§1.1](#11-eight-programs-not-seven)
is the complete list.

## 2. Prerequisite: run every command from the repository root

Every command on this page uses paths relative to the **repository root** — the
directory containing `services/`, `app/` and `infra/`. Assumptions: this is stated
rather than assumed because the commands read naturally as though they belonged beside
this file, and running them from `services/authorization-service/` makes Maven resolve
`services/services/pom.xml` and fail with a missing-POM error that names a path nobody
wrote. Nothing here needs to be run from the module directory.

```bash
# WHAT: confirm the working directory before running anything else on this page.
# WHY : Assumptions: the check tests for directories that exist only at the
#       repository root, which is cheaper and less brittle than comparing the
#       shell's path against a hard-coded name that differs per clone.
test -d services -a -d app || echo 'not at the repository root — cd there first'
```

## 3. API surface

The contract of record is
[`src/main/resources/openapi/authorization-api.yaml`](src/main/resources/openapi/authorization-api.yaml).
Five operations are published across four paths, and
`src/test/java/com/carddemo/authorization/config/AuthorizationApiContractTest.java`
holds the document and the two controllers to each other in both directions.

| Method | Path | Operation | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/authorizations/search` | `listPendingAuthorizations` | Read the account-level summary plus one keyset page of that account's pending authorizations, newest first, with the cursors for the pages after and before it. Collapses the browse loop of `COPAUS0C` into one request. |
| `GET` | `/api/v1/authorizations/{key}` | `getPendingAuthorization` | Read one authorization's full **stored** state. Display compositions the reference performs on the way to the terminal — the solidus in the card expiry, the fraud mark's separator and report date — are described beside each property and left to the client. |
| `GET` | `/api/v1/authorizations/{key}/screen` | `getPendingAuthorizationScreen` | Read the same authorization projected onto the 27-component shape `COPAUS1C` renders, plus the six components of screen chrome no segment holds. Every chrome value is derived here and none is accepted from the caller. |
| `GET` | `/api/v1/authorizations/{key}/next` | `getNextPendingAuthorization` | Read the authorization immediately older than the one named, which is the detail screen's forward paging move. At the oldest row the response carries the end-of-data indicator and no authorization. |
| `PUT` | `/api/v1/authorizations/{key}/fraud` | `setAuthorizationFraudState` | Set the fraud state to the state the body names and report what the write did. |

Assumptions: the search is a `POST` although it is a read, and the reason is
disclosure rather than semantics. The selector names an account, and a request line is
written into the access log of every intermediary, into browser history and into a
referrer header, none of which this service can redact afterwards. A request body
appears in none of them.

Assumptions: the fraud operation is **idempotent** and takes the target state, where
`COPAUS1C` L230–L243 takes no action argument, re-reads the segment at L234 and inverts
what it found at L236–L241 — pressing its one key twice returns the row to where it
began. The difference is registered as divergence `D-AUTH-FRAUD-TARGET-STATE`; a
retried request that lands twice is the failure a toggle turns into a silent state flip.

### 3.1 Fraud marking admits either business group, and that is the reference behaviour

`config/SecurityConfig.java` guards `/api/v1/authorizations/*/fraud` with
`fraudAccess()` (L310–L312), which is
`AuthorityAuthorizationManager.hasAnyAuthority(BUSINESS_AUTHORITIES)` over the constant
at L221 — the administrator authority **and** the ordinary-user authority. The published
contract agrees: all five operations carry `x-required-authority: carddemo-user`, and
the document says in as many words that this admits either group.

Refactoring Rationale: an admin-only rule is the intuitive reading and it is wrong, so
the evidence is recorded here rather than left to be rediscovered. The two menu tables
were read directly. `app/cpy/COMEN02Y.cpy` is the **main** menu table, whose eleventh
entry is option `11`, named `'Pending Authorization View         '`, dispatching to
`'COPAUS0C'` and carrying the access byte `'U'`. The administrative table
`app/cpy/COADM02Y.cpy` declares six options and contains **zero** occurrences of
`COPAUS` anywhere in the file. From that ordinary-user screen the write is two steps
away: `cbl/COPAUS1C.cbl` takes `WHEN DFHPF5` at L187 into the paragraph performed at
L188, which links at L248–L252 to `COPAUS2C`, and that program issues
`INSERT INTO CARDDEMO.AUTHFRDS` at L141–L142 and `UPDATE` at L222–L223. So the
capability an ordinary user reaches is a durable write, not a view.

Trade-offs: what the reference-faithful rule costs is stated plainly rather than argued
away, because it is why the narrower rule is attractive. The only state-changing route
this context publishes is reachable by every signed-in holder of an ordinary-user token,
and the confirmation the migrated screen shows first is a client-side affordance with no
server-side rule behind it. That is also the reference system's posture: the extension's
three transaction definitions carry `RESSEC(NO) CMDSEC(NO)` at L46, L56 and L66 of
`csd/CRDDEMO2.csd`, so neither a per-resource nor a per-command check ran there either.
A deployment that wants the narrower rule can have it by changing that one method — but
doing so is a behavioural change against the oracle, so it must be implemented,
registered in
[`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md)
and published in the contract before it ships.

### 3.2 No session state, and no offset pagination

Every handler in this context is **stateless**: no sticky sessions and no server-side session
store. The reference's pseudo-conversational state decomposes into four different mechanisms,
and one of them disappears entirely:

| Reference mechanism | Target |
|---|---|
| `EXEC CICS XCTL` between programs | A client-side route change in the single-page application |
| Identity carried in the communication area | Claims on a validated token, converted by `JwtRoleConverter` |
| The selected authorization key | A REST path parameter — `/authorizations/:key` |
| The re-entry discriminator `CDEMO-PGM-CONTEXT` | **Gone entirely** |

Refactoring Rationale: the re-entry discriminator has no target counterpart because it existed
only to tell a program whether it was being entered for the first time or re-entered after a
screen turn. A stateless handler that returns a structured per-field error array has no such
distinction to make, so error presentation is driven purely by the response body. What the
old approach got wrong is that identity and navigation both travelled in storage the *client*
echoed back — so a client could in principle assert its own user type. A signed claim cannot
be asserted by its holder.

**Paging is keyset, never offset**, through `PageResponse` from `common-lib`, and it
reproduces the reference's own browse arithmetic:

| Property | Value | Reference basis |
|---|---|---|
| Default page size | **5** | The rows the summary screen renders per turn |
| `hasNext` | Computed by a **look-ahead read of size + 1** | The reference discovers one row more than it can display |
| Backward paging | A previous-key history bounded at **20 pages** | The communication area's own bounded key stack |

Alternatives Considered: offset pagination. Rejected because under concurrent inserts an
offset **skips and repeats rows** — a row inserted before the cursor shifts every later row by
one, so the next page omits one row and repeats another. Browsing by key does not, because the
cursor names a row rather than a position. Preserving keyset paging preserves observable
behaviour that offset paging would change, which is why the built-in offset pagination of the
user-interface table component is deliberately disabled on this screen.

## 4. Data ownership and the copybook contracts

The `authorization` schema is created and owned by this module's Flyway migrations and
read or written by nothing else; this context reads no other context's schema.

| Object | Kind | Carries |
|---|---|---|
| `pending_auth_summary` | table | The account-level summary segment, including the five discrete `account_status_1..5` columns that stand for `PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES` |
| `pending_auth_detail` | table | One row per pending authorization, keyed by the composite the packed IMS key expresses, with the reply-code and match-status domains as check constraints |
| `auth_fraud` | table | What the reference writes to its Db2 fraud table `CARDDEMO.AUTHFRDS` |
| `idx_auth_fraud_card_recent` | index | Card ascending, authorization instant descending — the order `XAUTHFRD` provides |
| `auth_reply_outbox` | table | The transactional outbox. **No reference counterpart**; it exists for the reason set out under [D-5](#61-d-5--the-reply-is-published-from-an-outbox) |
| `idx_auth_reply_outbox_unpublished`, `_group`, `_published` | indexes | The drain's claim scan, per-group ordering, and the retention sweep |

Assumptions: four tables is the count every authority states —
[`docs/architecture/service-catalog.md`](../../docs/architecture/service-catalog.md)
lists the same four as this context's owned tables and
[`docs/architecture/data-model-and-schema-mapping.md`](../../docs/architecture/data-model-and-schema-mapping.md)
specifies the outbox column by column. The authoritative column lists are the
migrations under
[`src/main/resources/db/migration`](src/main/resources/db/migration), not this page:
`V1__authorization.sql` creates all four, `V2` adds the drain's claim version and `V3`
adds the two ordered-queue identity columns.

Assumptions: publication state is a nullable `published_at` rather than a status
enumeration, and retention is bounded by the purge job migrated from `CBPAUP0C` rather
than by a database job. The outbox holds a primary account number in every row, so an
unbounded table is a growing disclosure surface, not merely a large one.

### 4.1 Packed decimal is the persisted numeric regime here, and getting it wrong fails silently

⭐ **This is the only bounded context in the migration whose *persisted* data originates
in packed decimal (`COMP-3`).** Everywhere else packed decimal appears only at an export
edge. That makes the decode path this module's highest-risk surface.

Assumptions: the risk is specifically that an error here is **silent — it produces
plausible numbers that are wrong.** A mis-decoded packed field does not throw; it yields
a balance that looks like money and is not, and no test that only checks for an
exception will notice.

The rule, applied without exception:

- `PackedDecimalCodec` **from `common-lib`** decodes `COMP-3` in `mapper/**`. The
  database stores `NUMERIC(p,2)` for money and an integer type for counts.
  **Packed bytes are never written to a column.**
- Money is exact fixed point end to end: `BigDecimal` at scale 2 with
  `RoundingMode.HALF_UP` through `Money`, and a **JSON string** on the wire through
  `MoneyModule`.

Alternatives Considered: transporting money as a JSON *number*. Rejected because most
clients parse a JSON number into an IEEE-754 double, which destroys exactness at
precisely the boundary the user reads. `float`, `double` and JSON numbers are therefore
forbidden in the money path, and the prohibition is asserted by
`LayeringRulesTest` rather than left to review.

Assumptions: the summary segment carries **three** numeric regimes, not one, and a
decoder that assumes packed throughout will corrupt two of them. Read from
`cpy/CIPAUSMY.cpy`: `PA-ACCT-ID` is `PIC S9(11) COMP-3` at L19 and the four money
fields are `PIC S9(09)V99 COMP-3`, but `PA-APPROVED-AUTH-CNT` and
`PA-DECLINED-AUTH-CNT` at L27–L28 are `PIC S9(04) COMP` — **binary**, not packed — and
`PA-CUST-ID` at L20 is `PIC 9(09)` zoned display. `mapper/PendingAuthSummaryMapper`
dispatches per field on the declared regime.

### 4.2 The two IMS segments, corroborated four ways

| Segment | Copybook | Fields | Length | Trailing `FILLER` |
|---|---|---|---|---|
| `PAUTSUM0` (root) | `cpy/CIPAUSMY.cpy` | L19–L31 | **100 bytes** | `PIC X(34)` at L31 |
| `PAUTDTL1` (child) | `cpy/CIPAUDTY.cpy` | L19–L54 | **200 bytes** | `PIC X(17)` at L54 |

Assumptions: both lengths are load-bearing for every fixture and every decode offset, so
neither is taken on trust. Each is corroborated four independent ways: the database
definition (`ims/DBPAUTP0.dbd` L28 `SEGM NAME=PAUTSUM0,PARENT=0,BYTES=100` and L36
`SEGM NAME=PAUTDTL1,PARENT=((PAUTSUM0,)),BYTES=200`); the sequential-access definitions
used by the unload path (`ims/PASFLDBD.DBD` L27 `RECORD=(100)` and `ims/PADFLDBD.DBD`
L27 `RECORD=(200)`); explicit `PICTURE` arithmetic over the declared fields; and the
committed fixtures `pautsum0-canonical.bin` and `pautdtl1-canonical.bin`, which are
exactly 100 and 200 bytes on disk.

`FILLER` is dropped and each drop is recorded, so a reader reconciling a decoded row
against a 100- or 200-byte buffer knows where the unaccounted bytes went.

**The five-slot status array becomes five discrete columns.**
`PA-ACCOUNT-STATUS PIC X(02) OCCURS 5 TIMES` at `cpy/CIPAUSMY.cpy` L22 maps to
`account_status_1` through `account_status_5`, each `CHAR(2)`.

Alternatives Considered: a PostgreSQL array column. Rejected *"because the fixed arity of
5 is then enforced by the schema itself and the JPA mapping stays portable"* — an array
would move an invariant the reference expresses in its record layout out of the schema
and into application code, where nothing checks it.

**The composite packed key becomes plain typed key columns.** The primary key is
`(account_id, auth_date, auth_time)`, derived from `PA-AUTHORIZATION-KEY` at
`cpy/CIPAUDTY.cpy` L19–L21 — `PA-AUTH-DATE-9C PIC S9(05) COMP-3` and
`PA-AUTH-TIME-9C PIC S9(09) COMP-3` — together with `PA-ACCT-ID PIC S9(11) COMP-3` at
`cpy/CIPAUSMY.cpy` L19. Decoding happens in the mapper; the key columns are ordinary
typed columns.

Assumptions: that this triple *is* the whole key is confirmed independently of the
copybooks by the program-communication block. `ims/PSBPAUTB.psb` L17 declares
`PAUTBPCB PCB TYPE=DB,DBDNAME=DBPAUTP0,PROCOPT=AP,KEYLEN=14`, and the three packed
fields occupy exactly 6 + 3 + 5 = **14** bytes. A fourth key field, or a different
arity, would not sum to the declared key length.

**The `88`-level value domains become CHECK constraints**, because they are the value
sets the reference condition names assert — a row outside them is a row the reference
could not have produced.

| Constraint | Preserves | Declared at |
|---|---|---|
| `CHECK (match_status IN ('P','D','E','M'))` | `PA-MATCH-PENDING` `'P'`, `PA-MATCH-AUTH-DECLINED` `'D'`, `PA-MATCH-PENDING-EXPIRED` `'E'`, `PA-MATCHED-WITH-TRAN` `'M'` | `cpy/CIPAUDTY.cpy` L46–L49; enforced at `V1__authorization.sql` L622 |
| `CHECK (auth_fraud IN ('F','R') OR auth_fraud IS NULL OR auth_fraud = ' ')` | `PA-FRAUD-CONFIRMED` `'F'`, `PA-FRAUD-REMOVED` `'R'` | `cpy/CIPAUDTY.cpy` L51–L52; enforced at `V1__authorization.sql` L703 |

Assumptions: ⚠ the fraud constraint must tolerate a **blank**, and this is the detail
most easily lost. The reference writes `SPACE` rather than a null to `PA-AUTH-FRAUD` and
`PA-FRAUD-RPT-DATE` on the approve path, so a faithfully loaded row carries `' '` and a
constraint admitting only `'F'`, `'R'` and null would reject data the reference produced.
The third disjunct at L703 exists for exactly that reason.

**The one rename this module owns.** `PA-MERCHANT-CATAGORY-CODE` at `cpy/CIPAUDTY.cpy`
L36 becomes `merchant_category_code` in SQL and `merchantCategoryCode` in Java.

Refactoring Rationale: the misspelling propagated out of the copybook and into the
persisted Db2 column `MERCHANT_CATAGORY_CODE`, so correcting it is a deliberate breaking
change rather than a cosmetic one, and it is recorded in
[`docs/architecture/data-model-and-schema-mapping.md`](../../docs/architecture/data-model-and-schema-mapping.md)
so the lineage stays traceable. The justification belongs at the mapping site in
`mapper/**`, which is where the old name last appears. The migration's other two
renames — `ACCT-EXPIRAION-DATE` and `CARD-EXPIRAION-DATE` — belong to the account and
card contexts and are **not** this module's to make.

### 4.3 The fraud table and the direction of its index

The reference relational side is `ddl/AUTHFRDS.ddl` — 26 columns with
`PRIMARY KEY(CARD_NUM, AUTH_TS)` — and its index is `ddl/XAUTHFRD.ddl`, which reads in
full:

```sql
-- WHAT: the reference index this module's own index must reproduce.
-- WHY : Assumptions: quoted here in full because the SECOND column's direction is the
--       whole point, and an abbreviated citation loses it.
CREATE UNIQUE INDEX CARDDEMO.XAUTHFRD
    ON CARDDEMO.AUTHFRDS
    (CARD_NUM ASC, AUTH_TS DESC)
    COPY YES;
```

The target index matches both the column order and the direction:
`V1__authorization.sql` L901–L902 creates
`idx_auth_fraud_card_recent ON auth_fraud (card_num ASC, auth_ts DESC)`.

Refactoring Rationale: **direction is part of the access path, not decoration.** An index
on the same two columns with both ascending is a different access path from the one the
reference had: the query this index exists for reads a card's authorizations
newest-first, which a `(card_num ASC, auth_ts DESC)` index satisfies by an ordered scan
and an all-ascending index satisfies only by scanning that card's rows and sorting them.
Preserving the direction preserves the plan shape, not merely the column set.

⚠ Two PostgreSQL consequences are recorded honestly:

1. The Db2 `UNIQUE INDEX` sits on the same columns as the primary key, so in PostgreSQL
   it becomes **two objects, not one** — the primary-key constraint with its own
   implicit index, plus the separate directional index above. Collapsing them would
   lose either the uniqueness or the direction.
2. **`COPY YES` is a Db2 image-copy attribute and has no PostgreSQL analogue.** Its
   equivalent is the cluster's automated backups and point-in-time recovery, configured
   in the infrastructure — **not** an index clause. No index option is invented for it.

## 5. Messaging contract

| Target | Replaces | Direction | Notes |
|---|---|---|---|
| `carddemo-pauth-request-<env>.fifo` | `AWS.M2.CARDDEMO.PAUTH.REQUEST` | **consumed** | One `@SqsListener`, registered under `ContainerCyclingWindowBoundary.REQUEST_CONTAINER_ID` |
| `carddemo-pauth-reply-<env>.fifo` | `AWS.M2.CARDDEMO.PAUTH.REPLY` | **published** | Never consumed here. `service/OutboxPublisher` drains `auth_reply_outbox` and sends to the destination recorded on the row |
| each queue's dead-letter queue | — | quarantine | `maxReceiveCount` **5**. Recovery is per-message replay after reconciliation |

Refactoring Rationale: the count above is **one** consumed queue. An earlier revision of
this page and of the module's package charter both said two were consumed, which
described a reply consumer that does not exist and hid the mechanism that does — the
reply row is committed with the decision and published from the outbox afterwards.

**Ordered delivery is required, not optional.** `MessageGroupId = card_num` gives
per-card ordering with parallel throughput across cards, and
`MessageDeduplicationId = transaction_id` gives exactly-once acceptance inside the
five-minute deduplication window.

Alternatives Considered: a standard queue, and content-based deduplication. A standard
queue cannot express per-card ordering at all, which is the one ordering guarantee an
authorization stream needs. Content-based deduplication would key on the payload rather
than on business identity, so two genuinely distinct requests that happened to serialise
identically would collide while a retried request with any incidental difference would
not.

Trade-offs: both identities become message **metadata**, and a queue's server-side
encryption of a message *body* does not cover metadata — so the card number reaches
queue telemetry and the trace of every send. The exposure is bounded by the deployment
(customer-managed-key encryption, an interface endpoint inside the private network, and
read access scoped to task roles) and is registered as divergence
`D-AUTHORIZATION-FIFO-IDENTITY-METADATA` rather than described away.

### 5.1 The wire shape is the contract

Because both payloads are declared as delimited strings, **field order and the delimiter
*are* the interface**. `CsvAuthCodec` from `common-lib` owns both directions and
publishes the numbers as constants rather than leaving them to a reader. The reference
parses inbound with `UNSTRING ... DELIMITED BY ','` at `cbl/COPAUA0C.cbl` L354–L374,
which is why the delimiter is load-bearing rather than cosmetic.

| | Fields | Declared field-width sum | Comma-delimited wire length |
|---|---|---|---|
| Request — `cpy/CCPAURQY.cpy` L19–L36 | **18** | **153 bytes** | **170 bytes** |
| Reply — `cpy/CCPAURLY.cpy` L19–L24 | **6** | **57 bytes** | **63 bytes** emitted; **63 or 64** accepted |

⚠ Assumptions: **the two columns measure different things and neither may be presented
as the other.** 153 and 57 are the *field-width sums* — what the copybook fields add up
to, with no delimiters counted. 170 and 63 are the *comma-delimited wire lengths* — the
bytes actually on the queue. Quoting a field-width sum as a wire length, or the reverse,
produces a codec that is wrong by exactly the number of separators.

Assumptions: the two wire lengths are **not** derived by the same rule, and this is the
trap. The request adds 17 separators to 153 for 170, because 18 fields need 17
separators between them. The reply adds **six**, not five, for 63 — because the
reference assembles it with a `STRING` that writes a separator after **all six fields
including the last**:

```text
STRING PA-RL-CARD-NUM         ','          <- cbl/COPAUA0C.cbl L722
       PA-RL-TRANSACTION-ID   ','
       PA-RL-AUTH-ID-CODE     ','
       PA-RL-AUTH-RESP-CODE   ','
       PA-RL-AUTH-RESP-REASON ','
       WS-APPROVED-AMT-DIS    ','          <- L727: a SIXTH, TRAILING separator
       DELIMITED BY SIZE
```

Assumptions: any figure of five reply delimiters, and the 62-byte length it implies, is
superseded by the line above and by the committed encode oracle
`auth-reply-encode-oracle-63.bin`, which is exactly 63 bytes on disk. The request's
oracle `auth-request-encode-oracle-170.bin` is exactly 170.

Assumptions: the reference **transmits 64 bytes for the 63 characters it builds**, and
this service accepts both. `WS-RESP-LENGTH` is declared `PIC S9(4) VALUE 1` at
`cbl/COPAUA0C.cbl` L46 and used as the `WITH POINTER` of that assembly at L730, so it is
a **one-based** cursor standing at 64 once 63 characters are written; L756 then reuses
that same cursor as the put length, appending one trailing byte of the put buffer. This
service encodes exactly 63 and decodes either, so a message from the reference producer
still parses. Registered as divergence `D-REPLY-PUT-LENGTH`.

The eighteen request fields, in exact declaration order, are `PA-RQ-AUTH-DATE X(06)`,
`PA-RQ-AUTH-TIME X(06)`, `PA-RQ-CARD-NUM X(16)`, `PA-RQ-AUTH-TYPE X(04)`,
`PA-RQ-CARD-EXPIRY-DATE X(04)`, `PA-RQ-MESSAGE-TYPE X(06)`,
`PA-RQ-MESSAGE-SOURCE X(06)`, `PA-RQ-PROCESSING-CODE 9(06)`,
`PA-RQ-TRANSACTION-AMT +9(10).99`, `PA-RQ-MERCHANT-CATAGORY-CODE X(04)`,
`PA-RQ-ACQR-COUNTRY-CODE X(03)`, `PA-RQ-POS-ENTRY-MODE 9(02)`,
`PA-RQ-MERCHANT-ID X(15)`, `PA-RQ-MERCHANT-NAME X(22)`, `PA-RQ-MERCHANT-CITY X(13)`,
`PA-RQ-MERCHANT-STATE X(02)`, `PA-RQ-MERCHANT-ZIP X(09)` and
`PA-RQ-TRANSACTION-ID X(15)`. The six reply fields are `PA-RL-CARD-NUM X(16)`,
`PA-RL-TRANSACTION-ID X(15)`, `PA-RL-AUTH-ID-CODE X(06)`,
`PA-RL-AUTH-RESP-CODE X(02)`, `PA-RL-AUTH-RESP-REASON X(04)` and
`PA-RL-APPROVED-AMT +9(10).99`.

Trade-offs: a JSON envelope is offered **additively** for new consumers and never as a
replacement. The delimited form stays the contract because an existing producer cannot
be asked to change, and carrying two encodings is the cost of not breaking it.

### 5.2 Descriptor fields, ordering, and the one genuine gap

The correlation identifier becomes `correlationId`; the message identifier becomes
`messageId`; the reply-to queue becomes `replyToQueueUrl`; the string-format indicator
set at `cbl/COPAUA0C.cbl` L751 outbound and L397 inbound becomes a `contentType` of
`text/csv`. `CorrelationIdFilter` from `common-lib` carries the same identity across the
HTTP surface.

**Reply routing is dynamic and baseline-faithful.** `cbl/COPAUA0C.cbl` L413 copies the
inbound `MQMD-REPLYTOQ` into `WS-REPLY-QNAME`, which L741–L742 then uses as the reply
destination, and L745 echoes the inbound correlation identifier onto the reply.

Assumptions: the listener therefore **must** copy the inbound `correlationId` onto the
reply and **must** send to the inbound `replyToQueueUrl`, never to a statically
configured reply queue. A static destination would silently break every requester whose
reply queue is not the configured one, and it would break the correlation the requester
uses to match a reply to its request.

**The five-second reply deadline has no target equivalent.** The reference sets
`MOVE 50 TO MQMD-EXPIRY` at L750, and that unit is **tenths of a second**, so the
deadline is **5.0 seconds**.

Trade-offs: SQS has **no per-message time-to-live**, so there is nothing to configure
that reproduces this. The deadline travels instead as an `expiresAt` message attribute
which the consumer honours by **dropping and logging** a stale message, and the reply
queues carry **short retention** behind it. Consumer-side staleness checking plus short
retention is the closest faithful substitute available: it preserves the observable
outcome (a late reply is not acted on) while accepting that the message occupies the
queue until retention expires rather than vanishing at the deadline. Recorded in
[`ADR-004`](../../docs/adr/ADR-004-messaging.md) and
[`docs/architecture/messaging-contracts.md`](../../docs/architecture/messaging-contracts.md).

Assumptions: short reply retention is defensible precisely because the reference marks
the reply `MQPER-NOT-PERSISTENT` at L749 — it was never a durable message, so bounding
its lifetime does not discard a guarantee the reference offered.

**Batch discipline is preserved so throughput does not shift silently.**

| Reference | Declared at | Target |
|---|---|---|
| Processing limit `VALUE 500` | `cbl/COPAUA0C.cbl` L40 | A bounded long-poll loop |
| Wait interval `MOVE 5000` | `cbl/COPAUA0C.cbl` L242 | `WaitTimeSeconds=5` |

⚠ Assumptions: **the reference loop stops after 501 messages, not 500.** L339 tests
`IF WS-MSG-PROCESSED > WS-REQSTS-PROCESS-LIMIT` — strictly greater — and the counter is
incremented at L332 *before* the test, so the 501st message is processed and only then
does the loop end. Reproducing "500" would quietly change the batch size by one.

⚠ Assumptions: **the two durations above are expressed in different units and mean the
same five seconds.** The expiry at L750 is in tenths of a second (50 → 5.0s); the wait
interval at L242 is in milliseconds (5000 → 5s). Reading either in the other's unit is
wrong by a factor of a hundred.

**Per-message transaction.** A per-message `@Transactional` replaces the per-message
`EXEC CICS SYNCPOINT` at L334–L336. No `EntityManager` or connection state may leak
across messages.

An **empty receive** after the five-second wait terminates the poll loop **normally**
and is not an error — it is the reference's `MQRC-NO-MSG-AVAILABLE` drain condition,
which L326 tests as a loop-exit flag rather than a failure.

Assumptions: `MQGMO-CONVERT` at L389–L391 means the transport performed codepage
conversion for the reference. SQS does not, so that responsibility moves to the
**producer**, which must send UTF-8. Assumptions: `MQGMO-FAIL-IF-QUIESCING` in the same
option set becomes **graceful shutdown of the listener on `SIGTERM`** — the equivalent
of declining to start new work while the platform is draining.

## 6. Divergences registered against this context

Every divergence below is registered in
[`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md);
none is described only here, and the COBOL each diverges from stays byte-identical
because it is the behavioural oracle.

Assumptions: the framing for all four is the specification's own finding that
*"The three extensions do **not** share one messaging discipline, and treating them
uniformly would break one of them."* This context is the one that breaks if treated
uniformly, which is why [§6.1](#61-d-5--the-reply-is-published-from-an-outbox) reads the
way it does.

### 6.1 D-5 — the reply is published from an outbox

**The reference sends the reply before the data lands.** The ordering is not inferred; it
is read from the call sequence inside `5000-PROCESS-AUTH` at `cbl/COPAUA0C.cbl` L438:

| Step | Line | What happens |
|---|---|---|
| 1 | L459 | `PERFORM 6000-MAKE-DECISION` — the decision is made and the reply buffer assembled |
| 2 | **L461** | `PERFORM 7100-SEND-RESPONSE` — **the reply is put**, by the `CALL 'MQPUT1'` at L758 |
| 3 | **L464** | `PERFORM 8000-WRITE-AUTH-TO-DB` — **only now is the authorization written** |
| 4 | **L334–L336** | `EXEC CICS SYNCPOINT` — the database work commits, back in the loop |

Neither message operation joins that commit: the get computes
`MQGMO-NO-SYNCPOINT + MQGMO-WAIT + MQGMO-CONVERT + MQGMO-FAIL-IF-QUIESCING` at
L389–L391, and the put computes `MQPMO-NO-SYNCPOINT + MQPMO-DEFAULT-CONTEXT` at
L753–L754. So delivery is at-least-once with the reply published **outside** the commit,
and the seam between the put and the commit is unguarded in **both** directions:

- **Phantom reply — the direction the line ordering actually produces.** Because the put
  at L461 precedes the commit at L334–L336, a failure in between leaves the requester
  holding an approval or decline for a decision the database never recorded. This is the
  primary failure mode, and it is the one a reader guessing from intuition tends to get
  backwards.
- **Lost reply — the other half of the same seam.** At L767–L779 a put that fails is
  merely logged (`PERFORM 9500-LOG-ERROR` at L778) and control **falls through**, so the
  write at L464 still happens and still commits. A committed decision with no reply is
  therefore reachable too.

Refactoring Rationale: what was wrong with the old approach is that the reply and the
data it reports were committed by two different mechanisms with no relationship between
them, so the system could assert either one without the other. **This service therefore
requires a transactional outbox.** The reply is written as an `auth_reply_outbox` row
**inside the decision's own transaction**, and `service/OutboxPublisher` drains it
afterwards. Exactly the committed decisions are the ones a reply exists for: no reply can
exist for an uncommitted decision, and a publisher that crashes before publishing loses
nothing, because the row survives to be drained on the next pass.

#### ⭐ The deliberate asymmetry with `account-service`

**`account-service` has no outbox, and that is correct rather than an oversight.** The
distinction is the single easiest one in this migration to get backwards, so the evidence
is recorded here.

Refactoring Rationale: the inquiry extension uses the **opposite** syncpoint discipline.
Read first-hand from `app/app-vsam-mq/cbl/COACCT01.cbl`: the get computes
`MQGMO-OPTIONS = MQGMO-SYNCPOINT` at L347 before its `CALL 'MQGET'` at L352, and the puts
compute `MQPMO-OPTIONS = MQPMO-SYNCPOINT` at L475 and L512 before their `CALL 'MQPUT'` at
L479 and L516, with a single `SYNCPOINT` at L327 covering the lot. Get, logic and put are
therefore **one atomic unit** there. Neither a phantom reply nor a lost reply is reachable,
so an outbox in that context would add machinery that removes no failure mode — cost with
no corresponding guarantee.

The asymmetry between the two contexts is **deliberate**. Neither ruling may be copied
into the other module: omitting the outbox here reintroduces both failure modes above,
and adding one to `account-service` adds a table, a drain and a retention sweep to close a
window that does not exist.

### 6.2 D-6 — the distributed transaction collapses to a local one

**The reference splits this feature's data across two resource managers and joins them
with two-phase commit.** One IMS hierarchic database — root segment `PAUTSUM0` and child
`PAUTDTL1`, reached through the program-communication block `PAUTBPCB` declared at
`ims/PSBPAUTB.psb` L17 and generated as `PSBNAME=PSBPAUTB` at L20 — and one Db2 table,
`CARDDEMO.AUTHFRDS`, reached through the plan bound to the transaction at
`csd/CRDDEMO2.csd` L69 `DEFINE DB2ENTRY(AWS01PLN)` with `PLAN(AWS01PLN)` at L71 and its
transaction association `DEFINE DB2TRAN(CPVDTRAN)` at L75.

**The sharpest proof is a single user action.** Marking or un-marking fraud writes to
**both** managers inside one unit of work:

- `cbl/COPAUS1C.cbl` moves the amended record at L522 and replaces the IMS segment at
  L525–L528 with
  `EXEC DLI REPL USING PCB(PAUT-PCB-NUM) SEGMENT (PAUTDTL1) FROM (PENDING-AUTH-DETAILS)`;
- the same transaction reaches `cbl/COPAUS2C.cbl` by `EXEC CICS LINK` at L248–L252, and
  that program writes the relational fraud row — `INSERT INTO CARDDEMO.AUTHFRDS` at
  L141–L142, `UPDATE` at L222–L223.

Two managers enlisted in one commit is a two-phase commit, and that is exactly why the
reference needs a distributed transaction.

Refactoring Rationale: in the target **all four tables live in the one `authorization`
schema**, so there is one resource manager and the distributed transaction is
**eliminated rather than emulated** — no coordinator, no prepare phase, no heuristic
outcome to reconcile. `service/FraudMarkingService` performs both writes inside a single
`@Transactional`, and the outbox row joins that same local transaction, which extends the
guarantee to the message the reference published outside its commit.

Assumptions: the rollback semantics the reference expressed as transaction attributes
carry over as ordinary exception propagation. `csd/CRDDEMO2.csd` sets `ACTION(BACKOUT)`
on all three transactions (L45, L55, L65) and `DROLLBACK(YES)` on the Db2 entry (L71);
under Rule T5 both map onto `@Transactional` rollback driven by a propagating exception,
which is what the migrated commit and rollback paths do — the reference's own
`TAKE-SYNCPOINT` at `cbl/COPAUS1C.cbl` L557 with its `EXEC CICS SYNCPOINT` at L558, and
its `ROLL-BACK` at L565 with `EXEC CICS SYNCPOINT ROLLBACK` at L566–L568, performed from
L540.

**One reference constraint is deliberately not inherited.** `csd/CRDDEMO2.csd` L72
declares `THREADLIMIT(1)` on the Db2 entry, so the reference serialises every fraud write
in the region through a single thread. The target does not reproduce that: the schema is
one resource manager reached over a connection pool, and the service scales horizontally
across tasks. This is a genuine improvement rather than a divergence in behaviour —
concurrency rises, while the per-row outcome is unchanged because the composite primary
key and the fraud upsert make concurrent writes to the same authorization resolve
deterministically.

### 6.3 D-C — a silent fall-through in the loader now fails loudly

`PAUDBLOD.CBL` L305–L314 contains a genuine defect, and reproducing it would mean
reproducing data loss:

```text
L305   IF PAUT-PCB-STATUS = SPACES
L306      DISPLAY 'GU CALL TO ROOT SEG SUCCESS'
L309      PERFORM 3200-INSERT-IMS-CALL  THRU 3200-EXIT
L310   IF PAUT-PCB-STATUS NOT EQUAL TO  SPACES AND 'II'
L311      DISPLAY 'ROOT GU CALL FAIL:' PAUT-PCB-STATUS
L313        PERFORM 9999-ABEND
L314   END-IF.
```

Refactoring Rationale: there is **one `END-IF.` for two `IF`s**. The error test at L310
is therefore nested inside the *success* branch of L305, which makes it unreachable in
the only case it was written for: when the root positioning call genuinely fails — status
`'GE'`, say — the L305 test is false, the whole nested block including L310's check is
skipped, and the program continues. The child segment is never inserted and the failure is
never reported. That is silent data loss, not a cosmetic slip.

`service/LoadService` diverges: a root positioning failure is **reported and fails the
run**. It is also **two-pass** (every parent before any child) and **idempotent on both
root and child**, tolerating the reference's `'II'` duplicate status and continuing —
`'II'` means the segment is already present, which on a re-run is the expected outcome
rather than an error.

### 6.4 D-D — a receive failure no longer reprocesses a stale buffer

In `cbl/COPAUA0C.cbl` a get failure that is *not* the no-message-available drain is
logged and then falls through **without setting either loop-exit flag**. The loop at L326
therefore iterates again, and L328 re-runs `2100-EXTRACT-REQUEST-MSG` over the buffer
contents left by the **previous** message — re-parsing and re-deciding an authorization
that has already been committed and replied to.

Refactoring Rationale: the old approach treated an unreadable queue as a condition to log
and continue from, but the thing it continued with was stale state, so the failure mode
was duplicate processing rather than a missed message. **The Java listener diverges: a
receive failure is terminal for that poll cycle.** The container ends the cycle, the
message stays invisible until its visibility timeout expires, and redelivery — bounded by
the dead-letter queue at five receives — is what retries it. Nothing is re-decided from a
buffer that was never refilled.

### 6.5 Further registered divergences — a selection, not the whole set

⚠ **This subsection is not exhaustive, and it no longer claims to be.** The complete and only
authority is
[`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md)
§7. The rows below are the ones a reader of *this* document is most likely to need, drawn
from the register; the four narrated at length in §6.1–§6.4 are not repeated here.

| Identifier | What differs |
|---|---|
| `D-AUTH-FRAUD-TARGET-STATE` | The fraud operation takes a target state and is idempotent, where `cbl/COPAUS1C.cbl` L230–L243 inverts whatever it re-reads |
| `D-AUTH-FRAUD-ONE-CLOCK` | The two fraud report dates come from one clock rather than two, so they cannot straddle midnight |
| `D-REPLY-PUT-LENGTH` | This service emits exactly 63 reply bytes and accepts 63 or 64, where the reference transmits 64 for 63 built characters ([§5.1](#51-the-wire-shape-is-the-contract)) |
| `D-AUTHORIZATION-FIFO-IDENTITY-METADATA` | The card number reaches queue metadata as the ordering identity ([§5](#5-messaging-contract)) |
| `D-AUTH-AMOUNT-TOLERANT-READ` | The declared-width amount token is emitted and read whole rather than at the narrower width the reference writes |
| `D-NEGATIVE-AUTH-AMOUNT` | An authorization amount outside the declared domain is refused rather than decided on |
| `D-AUTH-REASON-WIDTH` | The composed authorization reason is capped at the screen's twenty characters rather than overflowing |
| `D-AUTH-SUMMARY-MONEY-DOMAIN` | The summary's money columns **saturate** at the column's greatest magnitude where the baseline's packed fields truncate silently, and the saturation is reported |
| `D-SUMMARY-LIMIT-REFRESH` | The stored limits are refreshed only on a request whose account was actually read, not on every request |
| `D-DECLINED-AMT-CURRENT` | The declined total accumulates the current request's amount, which the reference leaves out of it |
| `D-LOAD-PREFIX-REFUSED` | An undecodable parent key is reported, where the loader's guard has no else branch |
| `D-LOAD-READ-BOUNDED` | The load walk cannot fail to terminate, where two of the reference's read branches suspend it |
| `D-UNLOAD-SKIP-REPORTED` | A root the unload cannot attribute is counted and reported, where the reference passes it over in silence |
| `D-PURGE-EXPIRY-FLOOR` | A zero expiry threshold is refused rather than honoured ([§11](#11-the-three-maintenance-jobs)) |
| `D-E`, also `D-PURGE-YEAR-BOUNDARY` | The reference subtracts two `YYDDD` ordinals as plain integers, so 31 December and 1 January read as 636 days apart; `PurgeJob` differences calendar dates instead, and its business date is a required parameter with no clock fallback |
| `D-F`, also `D-PURGE-DELETE-GUARD` | One counter is tested twice in the reference, making its second condition unreachable |
| `D-G`, also `D-PURGE-COUNTER-PERSISTENCE` | Four decrements the reference computes and never persists are persisted here |

⚠ Refactoring Rationale: this subsection was headed *"The remaining registered divergences"*
and carried FOUR rows, which read as the complete residue after §6.1–§6.4. It was not:
`D-AUTH-SUMMARY-MONEY-DOMAIN` was live, shipped, asserted by
`domain/PendingAuthSummaryMoneyDomainTest` and cited by identifier in
`domain/PendingAuthSummary` and `service/AuthorizationRequestListener`, and it was absent
here — and it was not the only one. A sweep of this module's own main sources for
register-defined identifiers reports **25**, against the eight this section claimed between
§6.1–§6.4 and its table:
`grep -rhoE '\bD-([A-Z0-9][A-Z0-9-]{2,}|[1-9]|[A-Z])\b' src/main | sort -u`.

Assumptions: the honest fix is BOTH halves — the named omission is added, and the claim of
completeness is withdrawn. A hand-maintained exhaustive list of twenty-five entries in a
module README is a second register that must agree with the first, and the drift this finding
caught is what that costs; a selection that says it is a selection cannot be wrong in the one
direction that matters, which is a reader concluding a divergence is unregistered because it
is not written here. Two identifiers a reader will find in this module's source deserve a note
because neither is in the register's §7 by design: `D-AUTH-REQUEST-WINDOW` was **withdrawn**
once the migrated consumer reproduced the reference's own 501-request run, and
`D-PURGE-BALANCE` names a **preserved** asymmetry rather than a difference — the reference
never releases a reserved balance on expiry and neither does this service. Both are explained
in the register at the point a searcher would look.

## 7. Security contract

`config/SecurityConfig.java` owns the filter chain, the group-to-authority conversion and
the decoder, and that decoder adds the token-kind, client and scope checks the issuer
location alone does not make. `JwtRoleConverter` from `common-lib` maps the
`cognito:groups` claim onto authorities.

| Path | Rule |
|---|---|
| `/actuator/health/**` | `permitAll` — the target group and the container probe poll it unauthenticated, and health details are never shown, in any profile |
| `/actuator/prometheus` | `loopbackOnly()` — a **network-position** rule over `127.0.0.1/32` and `::1/128`, because the scraper presents no token |
| `/api/v1/authorizations/*/fraud` | `fraudAccess()` — either business group, for the reasons and with the evidence in [§3.1](#31-fraud-marking-admits-either-business-group-and-that-is-the-reference-behaviour) |
| `/api/v1/authorizations/**` | the read decision — either business group |
| anything else | **`denyAll()`**, not `authenticated()` |

Assumptions: the catch-all denies rather than merely requiring authentication, and the
difference is not academic here. An earlier revision of the fraud path pattern omitted the
`/api/v1` prefix, so it matched no request this service can receive and fraud marking fell
through to a catch-all that asked only for a token — which any authenticated caller
satisfies. `management.endpoints.web.exposure.include` names `health,prometheus` and not
the wildcard, so no other actuator endpoint is reachable even before the chain refuses it.

**Card data exposure.** No card verification value is returned by any endpoint in this
context — the segments do not carry one, and none is synthesised. Primary account numbers
are masked to the last four digits on every projection; `dto/MaskedCardNumberContractTest`
and `mapper/MapperRenderingExposureTest` assert it rather than leaving it to review.

Assumptions: `docs/architecture/security-and-identity.md` records that the mainframe
external security manager has **no cloud analogue** and is not ported. Its role is filled
by least-privilege task roles plus the managed user pool's groups, and that substitution
is documented as a mapping rather than presented as a port.

## 8. Configuration

Every value this service reads arrives as an environment variable. **No value of any of
them appears anywhere in this repository.** The table is exhaustive against
[`application.yml`](src/main/resources/application.yml) and its two profiles: a
`Fallback` of **none** means the variable has no default anywhere, and every other row
shows the literal the profile falls back to.

**Thirteen** settings have **no fallback**, so an incomplete environment stops at startup
rather than running a consumer bound to nothing. Refactoring Rationale: this read **fourteen**
and the figure is one lower because `CARDDEMO_MESSAGING_HMAC_KEY` is withdrawn — see the record
under the secret table below. It is a measurement of the rows marked `none` in the table that
follows, so it is checkable rather than asserted.

Assumptions: that failure mode matters more in this context than in its siblings, because
a consumer pointed at the wrong queue is **silent rather than broken** — it starts,
reports healthy, and processes nothing. A hard startup failure is the correct behaviour.

| Variable | Selects | Source in deployment | Fallback |
|---|---|---|---|
| `SPRING_DATASOURCE_URL` | JDBC location of the cluster | Parameter Store (Aurora writer endpoint) | none |
| `SPRING_DATASOURCE_USERNAME` | Runtime `authorization` role | Secrets Manager | none |
| `SPRING_DATASOURCE_PASSWORD` | Credential for that role — **secret** | Secrets Manager | none |
| `SPRING_FLYWAY_USER` | **Migration** role, distinct from the runtime role, which owns the schema and its four tables | Secrets Manager | none |
| `SPRING_FLYWAY_PASSWORD` | Credential for the migration role — **secret** | Secrets Manager | none |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Issuer whose keys validate presented tokens | Terraform output (user pool issuer) | none |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | App client a presented token must name | Parameter Store | none |
| `CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE` | The one queue this context consumes | Parameter Store | none |
| `CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST` | Comma-separated **queue addresses** a reply may be sent to | Parameter Store | none |
| `CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY` | Seals the internal token presented on the calls into the account context — **secret** | Secrets Manager | none |
| `CARDDEMO_ACCOUNT_CONTEXT_BASE_URL` | Where the account context answers; must be an absolute **HTTPS** origin with no path, query or user information | Parameter Store (internal load balancer) | none |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | Seals keyset cursors — **secret**. Not declared in `application.yml`; see below | Secrets Manager | none |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | Opens the listener keystore — **secret**; not read when TLS is disabled | minted per task by the image entry point | none |
| `CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN` | Origin a redirect is checked against | Parameter Store | the base URL above — but the module requires it anyway |
| `CARDDEMO_VERSION` | Build identity on metrics and logs | ECS task definition (image tag or digest) | `unspecified`, which the service module **refuses** |
| `CARDDEMO_ONLINE_WRITES_PARAMETER` | Name of the read-only flag parameter | ECS task definition | none in effect — see below |
| `AWS_REGION` | Region for the queue client | ECS task definition | none read here — see below |
| `CARDDEMO_ENVIRONMENT` | Environment tag | ECS task definition | `unspecified` |
| `CARDDEMO_LOG_CONSOLE_FORMAT` | Console encoder | not injected | `ecs` |
| `SERVER_SSL_ENABLED` | Listener encryption | not injected; the listener stays encrypted | `true` |
| `CARDDEMO_SERVER_TLS_KEYSTORE` | Keystore location | the entry point's own path | `file:/tmp/carddemo-tls/listener.p12` |
| `CARDDEMO_SERVER_TLS_KEY_ALIAS` | Key alias | fixed alias | `carddemo-listener` |
| `CARDDEMO_DB_SSL_ROOT_CERT` | Database trust anchor | the image's own trust bundle | `/etc/ssl/certs/carddemo-rds-ca-bundle.pem` |
| `CARDDEMO_COGNITO_ADMIN_GROUP_NAME` | Administrator group name | Terraform output | `carddemo-admin` |
| `CARDDEMO_COGNITO_USER_GROUP_NAME` | Ordinary-user group name | Terraform output | `carddemo-user` |
| `CARDDEMO_PAGINATION_CURSOR_LIFETIME` | Cursor validity window | not injected | `PT15M` |
| `CARDDEMO_ONLINE_WRITES_CACHE_PERIOD` | Flag cache period | not injected | `PT5S` |

Assumptions: **no endpoint, credential or secret is hard-coded** — no service hard-codes
an endpoint. Queue addresses, the datasource URL, the issuer location and key material all
arrive from Terraform outputs by way of Parameter Store and Secrets Manager, and
`DataSourceConfig` pins the connection's `search_path` to `authorization`. Only variable
**names** appear on this page.

Assumptions: the allowlist entries are **addresses**, not names. Each is passed unchanged
as the destination of a send, so `config/SqsConfig` refuses at startup any entry that is
not a well-formed ordered-queue address — naming the property rather than failing later on
the reply path. A local run may therefore use a well-formed address that is never
contacted, because nothing is sent until a request has been consumed and decided.

Assumptions: the allowlist is applied where the destination **arrives**, in the listener,
and not where the send happens. The reply-to address is chosen by whoever can put a message
on the request queue, so a request naming an unlisted destination is refused before any
decision is recorded and no outbox row is written for it — which is why the publisher can
send to the address on the row without re-checking it. Dynamic routing decides *where* a
reply goes; the allowlist decides which destinations are legitimate at all, and both are
needed.

Assumptions: `AWS_REGION` carries no fallback and is nonetheless not in this workload's
required-plain set, which looks like an inconsistency and is not. Unlike its peers,
`application.yml` here declares **no** static region at all — so the region is not a
property this service reads, it is the first entry in the SDK's own resolution chain.
Naming it locally reaches the same setting without adding a key, and both environment
roots supply it to every workload regardless. Without it the chain falls through to
instance-metadata discovery and waits before failing.

Assumptions: `CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN` is the one row whose fallback and
whose requirement disagree, deliberately. It defaults to the base URL, so a local run needs
only the base URL; but `infra/modules/ecs-service` requires the approved origin **by name**
for this workload, because in a deployment the two are allowed to differ — the origin a
redirect is checked against is a security decision that must not silently inherit whatever
address the client happens to be configured with.

Assumptions: **two required names are not written as `${...}` placeholders in any
profile**, so a reader auditing the YAML for placeholders will not find them and could
reasonably conclude they are optional. They are not. Relaxed binding maps
`CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` onto `carddemo.pagination.cursor.signing-key` and
`CARDDEMO_ONLINE_WRITES_PARAMETER` onto `carddemo.online-writes.parameter`, and both
properties are declared in the shared kernel's auto-configuration rather than here:

- The cursor signing key is **required of this workload by name**, and its absence removes
  the `CursorToken` bean that `mapper/PendingAuthViewMapper` takes as its only constructor
  argument.
- The online-write gate **removes itself silently**. Both its beans are conditional on the
  same property and nothing outside the auto-configuration injects them, so an absent value
  leaves the fraud-marking write accepting traffic during the nightly batch window with
  nothing in the log to say the gate is gone. `infra/modules/ecs-service` therefore makes
  the name **biconditional** for the seven web workloads: a root that omits it, or that
  supplies it to the batch task, fails at `plan`.

### 8.1 Where the five secrets come from, and who else holds them

Every value is generated by the selected Terraform environment root into Secrets Manager
and injected as a container secret; **none has a default and none appears in a `tfvars`
file.** `infra/modules/ecs-service` asserts the injection in **both** directions — the
services that must receive a name, and only those — so a deployment that forgets one or
hands one to the wrong service fails at `plan` rather than at container start.

| Secret | Held by | Rotation |
|---|---|---|
| `SPRING_DATASOURCE_PASSWORD` | this service only | composed by `infra/modules/secrets` with the cluster credential |
| `SPRING_FLYWAY_PASSWORD` | this service only, and used **before** the runtime credential | as above |
| `CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY` | **two tasks** — this service signs with it and `account-service` verifies against it | attended and **not** a rolling change, in [`docs/runbooks/deploy.md`](../../docs/runbooks/deploy.md) |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | **seven tasks**, sharing ONE value per environment | attended, in [`docs/runbooks/deploy.md`](../../docs/runbooks/deploy.md); rotating it refuses every cursor a client currently holds |

Assumptions: the holder counts are the part to read carefully, because each implies a
different rotation. The internal-identity key has two holders by necessity and the cursor key
seven sharing a single value — so rotating the cursor
key has a blast radius of every browsing client in the deployment, while rotating the
internal-identity key affects one caller and its verifier. Conflating them would make the
first look rolling. The internal-identity key is symmetric, so signer and verifier must hold the same
bytes; each task reads its value once at start-up and the verifier holds exactly one key per
subject with no predecessor, which is why replacing it has an unavoidable refusal window
confined to that one caller. It is also **per caller** rather than shared with
`transaction-service`: with shared bytes the subject a token asserts would be a value its
holder writes rather than a property the verifier can check, so either caller could mint as
the other.

⚠️ Refactoring Rationale: this table carried a fourth row, `CARDDEMO_MESSAGING_HMAC_KEY`,
held by "this service alone" and rotated by an attended procedure in
[`docs/runbooks/batch-operations.md`](../../docs/runbooks/batch-operations.md). Both the row and
that procedure are **withdrawn**. The key fed exactly one Spring bean in this service, and
**nothing injected that bean** once specification §0.4.1.8 fixed the pending-authorization
queue's `MessageGroupId` as `card_num` and its `MessageDeduplicationId` as `transaction_id`:
with the identities literal there was nothing left to derive. The variable, the property, the
generated secret in both environment roots, the task-role read grant and the
`infra/modules/ecs-service` condition that required this task to receive it are all gone. It is
recorded here rather than deleted silently because a reader comparing this table against a task
definition applied earlier will find the variable there, and because the reason it persisted is
instructive: the bean was kept because the deployment provisioned the key, and the key was
provisioned because the module required it for the bean. Neither half could be the one to go
until both went together. The runbook section that rotated it now carries the same record.

Assumptions: no task role holds `secretsmanager:PutSecretValue` for any of these. A task
reads its entries and never writes them, so a compromised task cannot make its own key the
accepted one.


## 9. Build, test, and the documentation gate

<!-- test-inventory: 72 tests + 11 integration tests -->
**83** test classes across ten subpackages: **72** matching `*Test`, run by Surefire, and
**11** matching `*IT`, run by Failsafe against Testcontainers-backed PostgreSQL. Every test
package carries a `package-info.java`, because the documentation gate audits test sources
too.

Assumptions: that census is machine-checked rather than asserted —
`ServiceReadmeInventoryTest` in `common-lib` parses the marker comment above and
re-measures both figures against this module's test tree, and it also checks that the bold
total equals their sum. Editing either number without editing the tree fails the build,
which is the point: a census a reader trusts has to be one a test maintains.

| Package | `*Test` | `*IT` | What the integration tier proves here |
|---|---|---|---|
| `service` | 15 | 5 | The decision unit of work, the fraud-marking boundary, the outbox drain's publication lifecycle, that an extract load leaves no row behind when it is refused, and that a purge window rolls back as one |
| `fixtures` | 11 | 1 | The fraud-domain fixtures against real columns |
| `mapper` | 10 | — | |
| `config` | 12 | — | |
| `dto` | 9 | — | |
| `domain` | 8 | — | |
| `repository` | — | 5 | Composite keys, key and reply-code domains, parentage, keyset paging, the fraud index order, and the outbox claim |
| `api` | 2 | — | |
| `contract` | 1 | — | |
| `task` | 4 | — | |

```bash
# WHAT: run every test in this module, unit and integration alike.
# WHY : Assumptions: `verify` rather than `test`, because Failsafe binds to
#       `integration-test` and `verify`. Refactoring Rationale: this page used to
#       document `test` alone, which exercises 72 of the 83 classes and silently
#       skips all ELEVEN Testcontainers-backed classes — every assertion about the
#       single-transaction decision, the outbox drain, the atomic extract load and the
#       purge rollback, which are precisely the properties D-5 and D-6 exist for. A
#       container runtime is required.
mvn -B -f services/pom.xml -pl authorization-service -am verify
```

```bash
# WHAT: run the unit tier only, with no container runtime available.
# WHY : Trade-offs: faster to iterate with, and it proves strictly less — use it while
#       editing and `verify` before pushing. Assumptions: the codec bounds, the
#       control-character refusal and the error redaction this service relies on are
#       common-lib behaviours, which is what `-am` builds alongside it.
mvn -B -f services/pom.xml -pl authorization-service -am test
```

```bash
# WHAT: fire the Javadoc documentation gate on its own, without compiling or testing.
# WHY : Assumptions: the gate is bound to `validate`, which is the first phase of the
#       lifecycle, so naming `validate` runs it and nothing after it. Trade-offs: this is
#       the fastest way to find a missing docstring, and it proves nothing else at all.
mvn -B -f services/pom.xml -pl authorization-service validate
```

```bash
# WHAT: build the container image, as a validation of the Dockerfile only.
# WHY : Assumptions: both stages are pinned by digest as well as tag — the build stage to
#       maven:3.9.16-amazoncorretto-21-al2023 and the runtime stage to
#       public.ecr.aws/amazoncorretto/amazoncorretto:21.0.12-al2023-headless — so this
#       either resolves those exact images or fails, and never silently drifts onto a
#       rebuilt tag. Trade-offs: nothing is pushed and nothing is run; a green build here
#       says the image assembles, not that the service works.
docker build -f services/authorization-service/Dockerfile -t carddemo/authorization-service:local .
```

### 9.1 The documentation gate, stated plainly

The gate is Checkstyle **13.8.0**, configured by
[`config/checkstyle/checkstyle.xml`](../../config/checkstyle/checkstyle.xml) and bound by
[`services/pom.xml`](../pom.xml) to the Maven **`validate`** phase (L832) under the
execution id `checkstyle-documentation-gate`, with `failOnViolation` **true** (L930) and
`violationSeverity` **warning** (L931).

Assumptions: because it is bound to `validate` rather than run only in continuous
integration, **it runs on every local build** — there is no version of `mvn` past
`validate` that skips it. What that costs a contributor is worth stating precisely:

| Setting | Declared at | Consequence for code in this module |
|---|---|---|
| `JavadocPackage` at `Checker` level | `checkstyle.xml` L262 | A `package-info.java` is **required in every package**, main and test alike |
| `allowMissingParamTags="false"` | `checkstyle.xml` L429 | **Every DTO record component needs an `@param`** — a record with five components needs five |
| `allowMissingReturnTag="false"` | `checkstyle.xml` L444–L445 | Every non-void method needs `@return` |
| `validateThrows="true"` | `checkstyle.xml` L444–L445 | Every declared or documented throw needs `@throws` |
| `skipAnnotations` deliberately **not** set | `checkstyle.xml` L316 | Annotated types are **not** exempt, so `AuthorizationApplication` and every `config/*Config` class needs full Javadoc |
| `includeTestSourceDirectory=true` | `services/pom.xml` L949 | **Test classes are not exempt** |
| suppressions `optional="false"` | `checkstyle.xml` L245 | The gate is **fail-closed**: a missing suppressions file is an error, not a silent skip |

Assumptions: the suppressions file is wired from **inside** `checkstyle.xml` via
`${config_loc}/suppressions.xml` rather than by the plugin, and `config_loc` is supplied by
the plugin's `propertyExpansion` (`services/pom.xml` L897). That indirection is why a
reader looking only at the plugin configuration will not find the suppressions path.

⛔ **Suppressing anything under `src/main/java/**` in this module is prohibited.**
[`config/checkstyle/suppressions.xml`](../../config/checkstyle/suppressions.xml) is
chartered for generated-source and test-fixture suppressions only, and it holds exactly two
entries — `target/generated-sources/` at L160 and `src/test/resources/fixtures/` at L188.

Assumptions: `mapper/**` is the **highest-value documentation target in this module**, which
is exactly why exempting it would be the most damaging suppression anyone could add. Every
decision that needs a justification at its site lives there: the packed-decimal decode
([§4.1](#41-packed-decimal-is-the-persisted-numeric-regime-here-and-getting-it-wrong-fails-silently)),
the `OCCURS` array split into five columns, the `merchant_category_code` correction, and the
masking of the primary account number
([§4.2](#42-the-two-ims-segments-corroborated-four-ways), [§7](#7-security-contract)).

⛔ Never add `-Dcheckstyle.skip`, `<skip>true</skip>`, `failOnViolation=false`, a trailing
`|| true`, `continue-on-error: true`, or any other return-code tolerance to make the gate
pass.

### 9.2 Why Lombok and MapStruct are absent

Alternatives Considered: **Lombok**, for the accessors. Rejected because generated
accessors cannot carry the Javadoc the explainability rule requires, so adopting it would
trade the gate in [§9.1](#91-the-documentation-gate-stated-plainly) for brevity. Java
records with explicit components give the same brevity with documentable members.

Alternatives Considered: **MapStruct**, for the mapping layer. Rejected for two independent
reasons. Its most recent published release is a beta, and — decisively — the mapping here is
**not mechanical**: it decodes packed decimal, splits an `OCCURS` array into five columns,
corrects a misspelling and masks the primary account number. *"Every one of those decisions
needs an inline justification at the mapping site, which a generated mapper cannot hold."*

Alternatives Considered: a **resilience library**. None is added. Retry lives in Spring
Framework core: the annotation attribute is **`maxRetries`** (total attempts are one plus
its value) and the enabler is **`@EnableResilientMethods`** — **not** `@EnableRetry`, which
belongs to the superseded external project. No circuit breaker is used: the only synchronous
hop out of this context is in-network behind an internal load balancer with bounded connect
and read timeouts, so a breaker would add a failure mode without removing one. The durable
retry tier is queue redelivery with a dead-letter queue at **five** receives.

## 10. Run

The entry point is `com.carddemo.authorization.AuthorizationApplication`. A local run needs
four things reachable, in this order, and each failure names itself:

1. **the database**, whose *migration* credential is used before anything else;
2. **the token issuer**, whose discovery document the resource server fetches while the
   context refreshes rather than on first request;
3. **a free port**;
4. **the request queue, existing** — `service/AuthorizationRequestListener` resolves its
   `@SqsListener` queue placeholder when the endpoint is *registered*, so an absent or
   unknown queue aborts context refresh rather than producing an idle consumer. That is the
   correct failure, for the reason in [§8](#8-configuration).

Assumptions: the database also needs
`data-migration/sql/V2__runtime_delete_grants.sql` applied **after** this module's Flyway
migrations have created its tables. `V0` runs before any table exists, so the only privilege
forms available to it are schema-wide, and it withholds `DELETE` entirely; `V2` grants it one
table at a time. Without it the service starts and looks healthy while the outbox retention
sweep fails on every pass with `permission denied for table auth_reply_outbox` — and that
table holds a primary account number in every row.

Assumptions: **Flyway needs its PostgreSQL companion artifact.** Flyway 13 requires
`flyway-database-postgresql` alongside `flyway-core`, because Flyway 10 and later moved
PostgreSQL support out of core; core alone fails at run time rather than at build time, which
is the more expensive way to discover it. Both are declared in this module's `pom.xml`
(L448 and L497) at versions managed by the parent. The schema, its roles and its grants are
bootstrapped by `data-migration/sql/V0__schemas_and_roles.sql`.

Secrets are prepared **out of band**, in a file that cannot be committed:

```bash
# WHAT: create the environment file with owner-only permissions, before writing any value
#       into it, then fill it in with an editor.
# WHY : Assumptions: the name is `.env.authorization-service.local` specifically because
#       the repository's `.gitignore` ignores `.env.*`, which the `git check-ignore` line
#       confirms by printing the matching rule and its line number. A name such as
#       `authorization-service.env` matches no ignore rule and would be staged by
#       `git add -A` along with every secret in it.
# WHY : Assumptions: `umask 077` is applied BEFORE creation rather than corrected
#       afterwards, because a later `chmod` leaves a window in which the file was group-
#       and world-readable.
( umask 077 && : > .env.authorization-service.local )
git check-ignore -v .env.authorization-service.local
```

Fill in one `KEY=value` per line — no `export`, no quoting — for the fourteen settings that
[§8](#8-configuration) marks with a fallback of `none`. The keystore password may be omitted
for a local run, because TLS is disabled below and the value is then never read.

```bash
# WHAT: build the bootable jar for this module, then start it bound to loopback only.
# WHY : Assumptions: the jar is `authorization-service.jar` with NO version in the name,
#       because this module's pom.xml sets <finalName>authorization-service</finalName>.
#       Refactoring Rationale: this command previously named
#       `authorization-service-1.0.0-SNAPSHOT.jar`, which no longer exists after a clean
#       build and fails with "Unable to access jarfile" — a stale jar of that name can
#       linger in target/ from before the rename, which makes the mistake intermittent
#       and therefore worse.
# WHY : Assumptions: SERVER_SSL_ENABLED=false is for a LOCAL run only. `application.yml`
#       enables TLS and reads its listener material from a keystore whose password
#       placeholder has no fallback, because the deployed target group speaks HTTPS to the
#       task. No keystore exists on a developer machine, so the process would otherwise
#       fail while trying to open one.
# WHY : Assumptions: SERVER_ADDRESS=127.0.0.1 is what MAKES this run loopback-only, and it
#       is a configured property rather than a description — no `application.yml` here sets
#       `server.address`, so the variable reaches Spring Boot by relaxed binding.
#       Disabling TLS is only defensible once nothing outside the machine can reach the
#       port, which is why the two appear together and are verified below.
# WHY : Assumptions: AWS_REGION is needed for the queue client this context cannot run
#       without; see the note in §8 on why it is not a property this service reads.
mvn -B -f services/pom.xml -pl authorization-service -am package

set -a
. ./.env.authorization-service.local
set +a

SERVER_SSL_ENABLED=false \
SERVER_ADDRESS=127.0.0.1 \
java -jar services/authorization-service/target/authorization-service.jar
```

Confirm the binding rather than trusting it:

```bash
# WHAT: show which address the listener accepted, and prove the port is unreachable from
#       the host's routable address.
# WHY : Alternatives Considered: `ss -lntp` or `netstat -lntp`. Rejected because neither is
#       present in every container this repository is developed in, and their absence
#       yields a "nothing is listening" answer that reads as a pass.
# WHY : Assumptions: the address in /proc/net/tcp is hex and byte-reversed, so `0100007F`
#       is 127.0.0.1 and `00000000` is every interface; the port is matched as the literal
#       hex `1F90` (8080) rather than converted with `strtonum`, which is a GNU awk
#       extension that mawk rejects. Trade-offs: `--noproxy '*'` is not decoration — where
#       a transparent HTTP proxy is configured, curl otherwise answers from the proxy and
#       never reaches the port under test.
awk 'NR>1 && $4=="0A" && $2 ~ /:1F90$/ {split($2,a,":"); print "listen address:", a[1]}' \
    /proc/net/tcp
curl -sf --noproxy '*' --max-time 5 "http://$(hostname -i | awk '{print $1}'):8080/actuator/health" \
    && echo 'REACHABLE OFF-HOST — server.address did not take effect' \
    || echo 'refused from the routable address, as intended'
```

Assumptions: integration tests need no part of the above. Testcontainers supplies PostgreSQL
and the queue emulation for the `*IT` tier, so `verify` is self-contained given a container
runtime.

## 11. The three maintenance jobs

⚠ There are **three**, not two. `task/MaintenanceTaskRunner.JOB_NAMES` (L123) publishes
`load-authorizations` (L114), `unload-authorizations` (L117) and `purge-authorizations`
(L120); an unknown name is refused with that list and the usage text.

The **same jar** runs either as the online service or as one maintenance job, and which one
it does is decided by the arguments alone: `AuthorizationApplication.main` asks
`task/MaintenanceTaskRunner.isTaskInvocation(args)` first, and when any argument begins
`--job=` it runs that one task and calls `System.exit` with its status instead of starting a
server.

| Job | Migrated from | Required arguments | Optional arguments |
|---|---|---|---|
| `load-authorizations` | `PAUDBLOD.CBL` | `--root-extract=<location>`, `--child-extract=<location>` | — |
| `unload-authorizations` | `PAUDBUNL.CBL`, `DBUNLDGS.CBL` | `--root-extract=<location>`, `--child-extract=<location>`, `--business-date=<YYYY-MM-DD>` | `--extract-form=prefixed\|sequential` |
| `purge-authorizations` | `CBPAUP0C.cbl` | `--business-date=<YYYY-MM-DD>` | `--expiry-days=<1..MAX>`, `--checkpoint-frequency=<1..MAX>`, `--progress-log-frequency=<1..MAX>` |

A `<location>` is either `s3://bucket/key` or a filesystem path, which is the sentence
`MaintenanceTaskRunner.usage()` closes with. Both extract arguments are **mandatory for both**
the load and the unload job: `task/MaintenanceTaskRunner` reads each through `requiredValue`
at L371–L372 for the load and L375–L376 for the unload, and `requiredValue` raises rather
than defaulting, so an invocation without them exits non-zero having done nothing. The unload
additionally requires `--business-date`, read through `requiredDate`, because the date selects
the generation prefix the extract is written under.

Assumptions: the two option names are the same for both jobs but their **direction is
opposite**, and knowing which is which is the difference between reading an extract and
overwriting it. `LoadAuthorizationsTask` opens both locations with `ExtractStore.openForRead`,
so for the load they are **sources that must already exist**; `UnloadAuthorizationsTask` opens
both with `ExtractStore.openForWrite`, so for the unload they are **destinations that get
written**. Pointing an unload at the locations a load reads from is therefore a way to destroy
the input, which is why the examples below use distinct names for the two.

⚠ Refactoring Rationale: this table showed **—** in the required column for load and unload,
and the examples below omitted both arguments, so every command a reader could copy from this
section failed on the first thing it did. The two jobs are the ones an operator reaches for
least often and therefore the ones most likely to be run straight from a README, and the
failure is not self-explanatory from the table — it is only self-explanatory from the usage
text the failure prints. Both required arguments are stated here and in every example, and
the optional column is added rather than folding options into a prose aside, because the
purge row already carried its option inside the required cell and that is what made the
distinction unreadable in the first place.

⚠ Assumptions: the optional column is exhaustive per row and is transcribed from
`MaintenanceTaskRunner.usage()` rather than from the option constants, because a constant
exists for every option but the usage text is where required and optional are distinguished.
`--extract-form` is optional and defaults to `UnloadService.DEFAULT_FORM` (`prefixed`), which
the exporter owns and publishes; the three purge options are read through `optionalCount` and
are absent from the parameter map when omitted rather than present with a default written in.

⚠ The export requires `--business-date` and it is not decoration. The orchestrator keys **both**
destinations by that date — `authorization/extract/dt=<businessDate>/run=<execution>/` — and the
state machine's own guard checks only the ten-character shape `????-??-??`, which admits
`abcd-ef-gh` and the impossible `2022-02-30` alike. `MaintenanceTaskRunner.requiredDate` (L469)
parses it before the application context starts and `UnloadAuthorizationsTask` parses it again
before either destination is opened, so an unparseable date refuses the run instead of landing an
extract under a prefix no later run can find by date. The two locations are still the caller's,
because the export a load reads back is not always the one this machine produced.

Alternatives Considered: two exit paths in one class is the accepted cost, and both
alternatives were worse. A second bootable artifact would double the images to build, scan
and deploy for one shared context; a scheduled bean inside the running service would fire on
every replica at once and leave the run no exit status for an orchestrator to branch on.

```bash
# WHAT: run each maintenance job. All three need the same environment the service does, so
#       source the prepared file first.
# WHY : Assumptions: the business date is a PARAMETER and never a clock read, so a rerun of
#       the same date produces the same result — the property the reference gets from a JCL
#       PARM and the reason a nightly chain can be restarted at all.
# WHY : Trade-offs: `--expiry-days` is optional and defaults to 5; see below for why zero is
#       refused rather than honoured.
# WHY : Assumptions: the two extract locations are MANDATORY for load and unload and are shown
#       on every invocation. `MaintenanceTaskRunner` reads both through `requiredValue`, which
#       raises rather than defaulting, so omitting either exits non-zero having done nothing —
#       and a reader copying a command from a README has no reason to expect that. A
#       `<location>` is `s3://bucket/key` or a filesystem path; the filesystem forms below are
#       written under `target/` so a copied command cannot write outside the build directory.
set -a
. ./.env.authorization-service.local
set +a

java -jar services/authorization-service/target/authorization-service.jar \
    --job=load-authorizations \
    --root-extract=s3://carddemo-datasets-dev/authorization/extract/roots.dat \
    --child-extract=s3://carddemo-datasets-dev/authorization/extract/children.dat

java -jar services/authorization-service/target/authorization-service.jar \
    --job=unload-authorizations --business-date=2022-07-18 \
    --root-extract=s3://carddemo-datasets-dev/authorization/extract/dt=2022-07-18/roots.dat \
    --child-extract=s3://carddemo-datasets-dev/authorization/extract/dt=2022-07-18/children.dat

# WHY : Trade-offs: `--extract-form` is omitted here and therefore takes the exporter's own
#       published default rather than a value copied into this file, which is the same reason
#       the runner leaves it out of its parameter map when the operator omits it. Pass
#       `--extract-form=prefixed` or `--extract-form=sequential` to select it explicitly.
java -jar services/authorization-service/target/authorization-service.jar \
    --job=purge-authorizations --business-date=2022-07-18 --expiry-days=5
```

### 11.1 Purge selects by age alone, and the reference's shipped threshold purges everything

Two verified facts about `CBPAUP0C` are disclosed here because both are surprising and both
change what the migrated job must do.

⚠ **The shipped control card sets the threshold to zero.** `cbl/CBPAUP0C.cbl` L196 tests
`IF P-EXPIRY-DAYS IS NUMERIC` — *not* whether it is greater than zero — and takes the value
when it is numeric, falling back to `MOVE 5` at L199 only when it is not. The card shipped at
`jcl/CBPAUP0J.jcl` L37 is `00,00001,00001,Y`, whose first field `00` **is** numeric, so
`WS-EXPIRY-DAYS` becomes 0 and the age test at L284,
`IF WS-DAY-DIFF >= WS-EXPIRY-DAYS`, reduces to `>= 0` — true for every authorization dated on
or before the run date. **As shipped, the reference job purges everything.**

Refactoring Rationale: `service/PurgeJob` therefore **refuses** a zero threshold rather than
honouring it, registered as `D-PURGE-EXPIRY-FLOOR`, and defaults to
`DEFAULT_EXPIRY_DAYS = 5` (L213) — the same fallback the reference reaches when its field is
non-numeric. Honouring zero would faithfully reproduce a job that deletes the entire table,
which is not a behaviour worth preserving; refusing it and naming the parameter is.

⚠ **There is no match-status filter anywhere in the program.** `MATCH-STATUS` occurs zero
times in all 386 lines of `cbl/CBPAUP0C.cbl`, and no spelling of the word appears at all, so
selection is **by age alone**.

Assumptions: this contradicts the root README's description of the job as purging expired
*unmatched* authorizations, and the code is authoritative. The migrated job selects by age and
does not filter on match status, so a matched authorization older than the threshold is purged
exactly as an unmatched one is.

### 11.2 The unload job has two record shapes, and both are supported

Alternatives Considered: emitting one shape and converting. Rejected because the reference
emits two genuinely different layouts from two programs, and a consumer of either would break
if handed the other.

| Shape | Root record | Child record | Declared at |
|---|---|---|---|
| Prefixed (flat file) | 100 bytes | **206 bytes** — a `PIC S9(11) COMP-3` key prefix of 6 bytes followed by `PIC X(200)` | `PAUDBUNL.CBL` L44, L47–L48 |
| Bare (sequential access) | 100 bytes | **200 bytes**, no prefix | `DBUNLDGS.CBL` L56; the prefix is commented out at L48 |

Assumptions: the 206 is 6 + 200 and not a different segment — the child segment is 200 bytes
in both shapes ([§4.2](#42-the-two-ims-segments-corroborated-four-ways)), and the prefixed
shape adds the packed parent key so a flat file can be reassembled into a hierarchy without a
second pass. `service/UnloadService` supports both, and the committed fixtures
`unload-prefixed-detail-206.bin` and `unload-gsam-detail-200.bin` hold whole multiples of each
record length so a reader can verify the framing by dividing.

### 11.3 Validation messages are carried verbatim

Rule T7: the reference's `FLG-*-NOT-OK` and `FLG-*-BLANK` condition pattern becomes a
structured per-field error array on `ApiError`, with the `'*'` marker preserved for the blank
case; the reference analogue is `MOVE 'Y' TO WS-ERR-FLG` at `cbl/COPAUS1C.cbl` L542.

Rule T8: every user-visible string is carried **character for character**.

| Message | Source | Path |
|---|---|---|
| `AUTH FRAUD REMOVED...` | `cbl/COPAUS1C.cbl` L535 | the un-mark path |
| `AUTH MARKED FRAUD...` | `cbl/COPAUS1C.cbl` L537 | the mark path |
| ` System error while FRAUD Tagging, ROLLBACK\|\|` | `cbl/COPAUS1C.cbl` **L545** | the rollback path |

⚠ Assumptions: the third message has **one leading space** and a **trailing double pipe**,
and neither is a typographical accident — the `||` is a literal separator the reference
appends the IMS return code after, so trimming either changes what the operator sees. Cite
**L545** for it; L562–L564 is a comment banner, not the message.

Assumptions: `service/FraudMarkingService` is a **toggle, not a one-way mark**.
`cbl/COPAUS1C.cbl` L534–L538 selects between the two messages above on `IF PA-FRAUD-REMOVED`,
so the one flow both marks `'F'` and un-marks `'R'`. It performs two writes in one
`@Transactional`: an update of `pending_auth_detail` setting `auth_fraud` and
`fraud_rpt_date` by composite key, and an **upsert** on `auth_fraud` keyed
`(card_num, auth_ts)`.

### 11.4 Error logging

`cpy/CCPAUERY.cpy` defines `ERROR-LOG-RECORD` with a severity code set (`ERR-LEVEL`: `'L'`
log, `'I'` info, `'W'` warning, `'C'` critical) and a subsystem code set (`ERR-SUBSYSTEM`:
`'A'` application, `'C'` CICS, `'I'` IMS, `'D'` Db2, `'M'` message queue, `'F'` file). These
become structured logging levels plus `AbendDetail` from `common-lib`.

Assumptions: the reference had already centralised error emission, so the target's
`GlobalExceptionHandler` plus one structured logger is a like-for-like replacement rather than
a new idea. `9500-LOG-ERROR` is invoked from **fourteen** call sites in `cbl/COPAUA0C.cbl` —
L282, L316, L429, L500, L512, L547, L560, L595, L608, L639, L778, L846, L931 and L975. A
count of ten covers only the first ten and misses the four in the put, summary and detail
paths.


## 12. What must NOT be added here

Each prohibition below exists because violating it would either break the oracle, reopen a
failure mode a divergence closed, or disclose data. They are stated as rules because a
reviewer needs to be able to point at one.

| # | Prohibition | Why |
|---|---|---|
| 1 | **Never modify anything under `app/**`.** | It is the behavioural oracle. Every citation on this page is only meaningful while the cited line is byte-identical. The three reference defects this context touches are **documented, not fixed** ([§6.3](#63-d-c--a-silent-fall-through-in-the-loader-now-fails-loudly), [§6.4](#64-d-d--a-receive-failure-no-longer-reprocesses-a-stale-buffer), [§11.1](#111-purge-selects-by-age-alone-and-the-references-shipped-threshold-purges-everything)). |
| 2 | **Never modify or re-pin `tests/**` or `scripts/**`.** | They are the parity oracle's own harness. New tests here are strictly additive ([§13](#13-honest-boundaries--what-this-modules-tests-do-not-prove)). |
| 3 | **Never omit the transactional outbox**, and never copy `account-service`'s no-outbox ruling into this module. | Both the phantom-reply and lost-reply windows return immediately ([§6.1](#61-d-5--the-reply-is-published-from-an-outbox)). |
| 4 | **Never reintroduce two-phase commit** or any distributed-transaction coordinator. | One schema means one resource manager; D-6 eliminated the second rather than emulating it ([§6.2](#62-d-6--the-distributed-transaction-collapses-to-a-local-one)). |
| 5 | **Never write packed-decimal bytes into a column.** | `COMP-3` is decoded in `mapper/**` to `NUMERIC(p,2)` or an integer type ([§4.1](#41-packed-decimal-is-the-persisted-numeric-regime-here-and-getting-it-wrong-fails-silently)). |
| 6 | **Never use `float`, `double`, or a JSON number in the money path.** | Exactness is lost at the boundary the user reads; `LayeringRulesTest` asserts it. |
| 7 | **Never re-declare a shared codec or contract type locally.** | `Money`, `MoneyModule`, `PackedDecimalCodec`, `ZonedDecimalCodec`, `FixedWidthCodec`, `CopybookLayout`, `CsvAuthCodec`, `ApiError`, `GlobalExceptionHandler`, `AbendDetail`, `PageResponse`, `CorrelationIdFilter`, `JwtRoleConverter`, `MetricsConfig`, `TimestampFormatter`, `DateEditValidator` and `FieldValidationFlag` come from `com.carddemo.common.*` only. A second copy is a second behaviour. |
| 8 | **Never change the delimited field order, field count, or delimiter.** | They *are* the interface; a JSON envelope is additive only ([§5.1](#51-the-wire-shape-is-the-contract)). |
| 9 | **Never move the request or reply queue to a standard queue.** | Per-card ordering and business-identity deduplication both depend on the ordered queue ([§5](#5-messaging-contract)). |
| 10 | **Never use offset pagination.** | It skips and repeats rows under concurrent inserts ([§3.2](#32-no-session-state-and-no-offset-pagination)). |
| 11 | **Never add Lombok, MapStruct, or a resilience library.** | Each was evaluated and rejected with a stated reason ([§9.2](#92-why-lombok-and-mapstruct-are-absent)). |
| 12 | **Never hard-code an endpoint, credential or secret; never return a card verification value; always mask the primary account number to its last four digits.** | [§7](#7-security-contract), [§8](#8-configuration). |
| 13 | **Never add temporal or schedule language to this page.** | A README states what is true of the code, not when something is expected. |
| 14 | **Never add a `.gitignore` to this module.** | Ignore rules are the repository root's; a module-level file fragments them and is the reason a local secrets file silently became committable in the past ([§10](#10-run)). |

Assumptions: `SqsConfig` is required in this module and there is **no `BatchConfig`**. The
purge and the load and unload utilities are argument-dispatched entry points
([§11](#11-the-three-maintenance-jobs)), not owners of a batch job repository, so adding one
would create a second scheduling mechanism with no job to schedule.

Assumptions: `LayeringRulesTest` lives at
`services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java`
and this module **inherits** it: no AWS software-development-kit type and no web type inside
`..domain..`, no cross-service `domain` imports, and no `double` in the money path. It is a
**test**, not a convention, so it cannot rot — a violation fails the build rather than a
review.

## 13. Honest boundaries — what this module's tests do NOT prove

⚠ **No golden master exists for any path in this context.** This is the single most
important caveat on this page, and every parity claim above must be read against it.

`tests/README.md` §1.1 states both halves of the reason. The online `CO*` programs
**cannot run end-to-end without a CICS runtime**, which the runner does not have — so
`COPAUS0C`, `COPAUS1C` and `COPAUS2C` have no recorded reference output to compare against.
And the authorization **request producer is not supplied by the baseline at all**; the only
producer in the repository is the stub at `tests/mocks/mq_request_stub.py`, so there is no
reference-generated request stream either.

Assumptions: parity for this context therefore rests on **the copybook and data-definition
contracts, and on logic transcribed line by line with its source cited** — not on byte
comparison of outputs. That is a genuinely weaker guarantee than the batch contexts enjoy,
and it is why this page cites a line number for every behavioural claim: the citation is the
evidence, because no golden file can be.

Assumptions: the reference suite's aggregate return code of **4** is its documented **green**
state and is **not** a regression introduced by this work. It is caused solely by the
out-of-scope `CBEXPORT`/`CBIMPORT` record-key defect described in `tests/README.md` §1.1,
which belongs to neither this context nor this module.

⛔ **The reference return-code rubric must never reach a Maven or JUnit gate.** The
0 / 2 / 4 / 8 / 16 scale belongs exclusively to the COBOL oracle (`tests/README.md` §8,
restated independently at `.github/workflows/tests.yml` L28–L38), where a soft warning is a
meaningful outcome. A Java gate is **binary**: a test passes or it fails. Teaching Surefire or
Failsafe to tolerate a return code of 4 would convert a real failure into a silent one.

⚠️ Refactoring Rationale: **this page previously recorded the two screen routes for
this context as not yet declared, and that limitation is withdrawn — both are authored and
mounted.** `ui/src/screens/authSummary/index.tsx` and `ui/src/screens/authDetail/index.tsx`
exist, and `ui/src/router.tsx` declares `AUTH_SUMMARY_PATH = '/authorizations'` and
`AUTH_DETAIL_PATH = '/authorizations/:key'` and mounts them inside the guarded subtree as
`AuthSummaryScreen` and `AuthDetailScreen`. Between them the two screens call every one of
the five published operations: the summary calls `listPendingAuthorizations`, and the detail
calls `getPendingAuthorization`, `getPendingAuthorizationScreen`, `getNextPendingAuthorization` and
`setAuthorizationFraudState`. The withdrawal is stated rather than the paragraph simply
deleted, because a reader who met the old limitation needs to know it no longer holds.

Assumptions: the browser surface is covered by
`ui/src/screens/authSummary/authSummary.test.tsx`, `ui/src/screens/authDetailScreen.test.tsx`,
`ui/src/screens/authSummaryEntry.test.tsx` and `ui/src/screens/referenceAndAuthScreens.test.tsx`,
and the typed client [`ui/src/api/authorization.ts`](../../ui/src/api/authorization.ts)
remains exercised by `ui/src/api/contracts.test.ts` against all five operations. Those suites
belong to the user-interface tree and are named here only so a reader can see where the
route-level evidence lives; nothing in this module asserts them. `ui/src/api/authorization.test.ts` covers
that client directly, and `ui/src/routerRoutes.test.tsx` asserts the registration itself —
it fails if either path constant resolves to no mounted route. Trade-offs: those files are
cited by NAME and by constant rather than by line. `ui/src/router.tsx` is edited far more
often than this page, so a line citation into it goes stale in exactly the way the
withdrawn paragraph above did; the cost is that a reader searches rather than jumps.

Assumptions: what remains true is the narrower point the old paragraph was reaching for —
the route existing is **not** a claim that this extension is deployed. `ui/src/router.tsx`
declares `/authorizations` unconditionally and records why: `app/cbl/COMEN01C.cbl`
L147–L168 probes for the extension with `EXEC CICS INQUIRE PROGRAM ... NOHANDLE` and paints
`'This option '` + the option name + `' is not installed...'` when it is absent, and no
client-side equivalent of that probe was invented. In the migrated system this service's
availability surfaces where every other service outage does — as an ordinary API error the
screen reports.

Assumptions: one reference behaviour is **intentionally dropped** rather than migrated.
`cbl/COPAUS1C.cbl` L523 contains `DISPLAY 'RPT DT: ' PA-FRAUD-RPT-DATE`, a leftover debugging
statement inside an online CICS program. It produces no terminal output a user sees, so it is
not user-visible output under Rule T8 and has no target equivalent.

Assumptions: for completeness of the resource inventory, `app/csd/CARDDEMO.CSD` L390 defines
`PROGRAM(COCRDSEC)` for which no matching `.cbl` exists anywhere in the repository. It is
documented as having **no target** rather than quietly omitted, and it belongs to the base
application rather than to this extension.

## 14. Acceptance criteria

These four assertions are this module's definition of done, and each is covered by a named
test rather than by inspection.

1. **The wire contract encodes and decodes byte-exactly.** The 18-field request and the
   6-field reply round-trip at the copybook field-width sums of **153** and **57** bytes, with
   the exact field counts and declaration order, and on the wire at **170** and **63**
   ([§5.1](#51-the-wire-shape-is-the-contract)).
2. **A committed authorization decision always leaves a publishable outbox row**, and a
   publisher that crashes before publication loses nothing
   ([§6.1](#61-d-5--the-reply-is-published-from-an-outbox)).
3. **A stale `expiresAt` message is dropped and logged**
   ([§5.2](#52-descriptor-fields-ordering-and-the-one-genuine-gap)).
4. **The fraud index exists with `card_num` ascending and `auth_ts` descending**
   ([§4.3](#43-the-fraud-table-and-the-direction-of-its-index)).

Required coverage behind them:

| Assertion | Test shape |
|---|---|
| Controller surface | `@WebMvcTest` controller tests holding each path to the published contract |
| Segment decode | `PackedDecimalCodec` round-trip fixtures at the verified segment lengths of **100** and **200** bytes |
| Wire codec | Fixtures at **153** and **57** bytes, plus a `CsvAuthCodec` test asserting **field order and delimiter** — not merely round-trip equality, which passes even when two fields are transposed in both directions |
| Ordered delivery | A test asserting `MessageGroupId = card_num` and `MessageDeduplicationId = transaction_id` |
| Schema | `*RepositoryIT` under Testcontainers asserting the composite primary key, **every** CHECK constraint, and the fraud index **direction** |

Assumptions: asserting index *direction* rather than mere existence is deliberate, because an
index on the same two columns with both ascending satisfies an existence check and silently
changes the access path ([§4.3](#43-the-fraud-table-and-the-direction-of-its-index)).

## 15. Rule 1 (Explainability) as it applies to this page

There is exactly **one** user-specified rule, Rule 1 "Explainability". Its full text is
available through the project's rules document; it is summarised rather than reproduced here.

What it requires of **code** in this module: a docstring on every new or modified function,
class and module entry point, specifying **Purpose**, **Parameters**, **Return values** and
**Exceptions or errors**; comments placed adjacent to what they explain, saying **why** rather
than what; and every non-obvious decision carrying at least one of **Alternatives
Considered**, **Refactoring Rationale**, **Assumptions** or **Trade-offs**. It forbids
comments that restate the code, docstrings that omit parameters or return values,
undocumented non-obvious choices where a reasonable alternative existed, and vague rationales
such as "this is better" or "for performance" with no specific justification. Missing either
the docstring or the reasoning fails review. The mechanical half of that gate is
[§9.1](#91-the-documentation-gate-stated-plainly).

⚠ **Attribution, stated precisely.** Rule 1's format clause names JSDoc, Javadoc, Python
docstrings and XML comments; it says **nothing about Markdown**, and the `# WHAT:` / `# WHY :`
comment idiom used in every fenced block on this page **does not appear in Rule 1 at all**.
Those obligations come from
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md) — its
"Markdown and documentation" section at L919 and its "The `# WHAT:` / `# WHY :` idiom —
prose command blocks only" section at L954 — from the technical specification's own §0.8.1,
and from the precedent the reference suite set in `tests/README.md`. Rule 1's
inline-reasoning clauses and forbidden patterns do apply to this page in full; its
docstring-format clause does not, and claiming otherwise would misattribute a requirement.

Assumptions: that standard is cited by **section name and line** rather than by a section
number because it uses named headings throughout and defines no numbered scheme — there is no
"§3.4" in it to cite. A numbered citation would send a reader looking for something that does
not exist, which is the documentation equivalent of the vague sourcing the rule forbids.

Four obligations follow from the "Markdown and documentation" section, and this page meets
each: it opens with a header stating its purpose and its source of truth; it carries reasoning
for every non-obvious assertion under one of the four category names; it uses the idiom in
every fenced command block; and — the one most easily missed — **a path to a document that
does not exist yet is written as a plain code span, never as a link**, because a link that
resolves to nothing is a defect a reader discovers by clicking. Every link on this page was
checked to resolve before it was published.

How this page satisfies the Markdown analogue: it opens with a header carrying all four Rule 1
elements — purpose, the source of truth that stands in for parameters, what the module returns
and who consumes it, and the honest limits that stand in for exceptions. Every non-obvious
assertion carries one of the four category labels. Every fenced command block carries
`# WHAT:` and `# WHY :`. And every citation names a **path and a line**, because vague
sourcing — "the COBOL does X" — is the documentation equivalent of the vague rationale Rule 1
forbids.

## 16. References

| Document | What it settles |
|---|---|
| [`docs/adr/ADR-004-messaging.md`](../../docs/adr/ADR-004-messaging.md) | The ordered-queue choice and the message-expiry gap resolution |
| [`docs/architecture/messaging-contracts.md`](../../docs/architecture/messaging-contracts.md) | Queue mapping, delimited field order, correlation, the expiry gap |
| [`docs/architecture/data-model-and-schema-mapping.md`](../../docs/architecture/data-model-and-schema-mapping.md) | Field-by-field mapping, the `merchant_category_code` rename, the `FILLER` drops |
| [`docs/architecture/cobol-to-service-traceability.md`](../../docs/architecture/cobol-to-service-traceability.md) | The register of every divergence in [§6](#6-divergences-registered-against-this-context) |
| [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md) | The polyglot documentation convention; its "Markdown and documentation" section (L919) governs this page |
| [`config/checkstyle/checkstyle.xml`](../../config/checkstyle/checkstyle.xml) · [`suppressions.xml`](../../config/checkstyle/suppressions.xml) | The gate in [§9.1](#91-the-documentation-gate-stated-plainly) |
| [`services/common-lib/README.md`](../common-lib/README.md) | The shared kernel every type in prohibition 7 comes from |
| [`services/pom.xml`](../pom.xml) | The Java release, managed versions, and the gate binding |
| [`app/app-authorization-ims-db2-mq/README.md`](../../app/app-authorization-ims-db2-mq/README.md) | The reference extension's own description of the feature |
| [`README.md`](../../README.md) · [`tests/README.md`](../../tests/README.md) | House inventory and house conventions; the parity oracle and its caveats |

<sub>Apache-2.0 · This module is additive. The COBOL baseline under <code>app/**</code>
and the reference suite under <code>tests/**</code> are read, cited and never
modified.</sub>
