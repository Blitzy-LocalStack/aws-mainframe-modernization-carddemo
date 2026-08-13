# CardDemo Transaction Service — Ledger Bounded Context

> The COBOL under `app/**` is the behavioural specification for this module, not a
> historical artifact: it is reference-only, is never modified, and every rule below
> is transcribed from it rather than reinterpreted. Where this module's structure
> departs from the baseline the departure is structural only, and any point at which
> observable behaviour differs is recorded as a named divergence rather than left for
> a reader to discover.

Every command on this page is written from the **repository root** — the directory
holding `services/`, `app/` and `infra/`. Assumptions: this is stated once rather
than repeated, because the commands read as though they belonged beside this file
and running them one directory down makes Maven resolve a `services/` path nested
inside itself and fail naming a file nobody wrote. Nothing here is run from the
module directory.

```bash
# WHAT: confirm the working directory before running anything else on this page.
# WHY : Assumptions: the probe tests for two directories that coexist only at the
#       repository root, which is cheaper and less brittle than comparing the
#       shell's path against a hard-coded directory name that differs per clone.
test -d services -a -d app || echo 'not at the repository root; cd there first'
```

---

## 1. Purpose and bounded context

This module is the **ledger** bounded context. It owns the PostgreSQL schema
`ledger` and its four tables — `transactions`, `daily_transactions`,
`transaction_rejects` and `transaction_category_balances` — and it publishes the
online surface of the four CICS transactions that read and write them.

| Property | Value |
|---|---|
| Java package root | `com.carddemo.transaction` |
| Maven artifactId | `transaction-service` |
| Maven parent | `com.carddemo:carddemo-services:1.0.0-SNAPSHOT` at `../pom.xml` |
| Sibling dependency | exactly one, `common-lib` (plus its `test-jar` at test scope) |
| Owned schema | `ledger` |
| Migrations | `db/migration/V1__ledger.sql`, `db/migration/V2__ledger_transaction_id_allocator.sql` |
| API contract | `src/main/resources/openapi/transaction-api.yaml`, OpenAPI 3.1.1 |

### 1.1 The service is stateless

There are no sticky sessions and no server-side session store. The baseline is
strictly pseudo-conversational: a CICS task ends at every screen turn, so all
continuity between turns travels in one passed structure, the `DFHCOMMAREA` carrying
the shared `CDEMO-*` fields. **That structure does not travel here.** It decomposes
into four separate mechanisms, and the decomposition is the most consequential
structural change in this module:

| Baseline concern | Where it goes | Consequence |
|---|---|---|
| Navigation fields (`CDEMO-FROM-PROGRAM`, `CDEMO-TO-PROGRAM`, `CDEMO-LAST-MAP`) | client-side routing | no server-side "next program" field exists at all |
| Identity (`CDEMO-USER-ID`, `CDEMO-USER-TYPE`) | validated JWT claims | the client can no longer assert its own authority, because the group claim is signed rather than echoed back from storage the client held |
| Selection context (card number, account id) | REST path and query parameters | every request is self-describing and therefore independently authorizable |
| Browse cursor (last key, first key, page number) | the `PageResponse` envelope from `common-lib` | paging state is carried in the response, not in server memory |
| Re-entry discriminator `CDEMO-PGM-CONTEXT` | **nothing — it disappears entirely** | a stateless handler that answers with a per-field error array has no first-entry-versus-re-entry distinction left to make |

That last row is why horizontally scaled tasks behind a load balancer are viable
for this context at all: with no turn counter to remember, any task can answer any
request.

---

## 2. What this module migrates

The four transaction identifiers, mapsets, programs and official function names are
taken verbatim from the CICS inventory in the root [`README.md`](../../README.md) at
lines L298 to L302. The baseline names are used as that table spells them — in
particular `COTRN01C` is **Transaction View**, not "transaction detail".

| CICS txn | Mapset | Program | Baseline name | Target |
|---|---|---|---|---|
| `CT00` | `COTRN00` | [`app/cbl/COTRN00C.cbl`](../../app/cbl/COTRN00C.cbl) (699 lines) | Transaction List | `TransactionListService` / `TransactionController` |
| `CT01` | `COTRN01` | [`app/cbl/COTRN01C.cbl`](../../app/cbl/COTRN01C.cbl) (330 lines) | Transaction View | `TransactionViewService` / `TransactionController` |
| `CT02` | `COTRN02` | [`app/cbl/COTRN02C.cbl`](../../app/cbl/COTRN02C.cbl) (783 lines) | Transaction Add | `TransactionAddService` / `TransactionController` |
| `CB00` | `COBIL00` | [`app/cbl/COBIL00C.cbl`](../../app/cbl/COBIL00C.cbl) (572 lines) | Bill Payment | `BillPaymentService` / `BillPaymentController` |

`app/cbl/CBTRN02C.cbl` is read here **for the schema contract only**. It is
`batch-service`'s program, not this module's: it is the posting job, it writes these
tables, and the two modules agree through the physical schema rather than through
code. Section 6 states exactly what that agreement is and what it is not.

---

## 3. Module layout

```text
services/transaction-service/
├── pom.xml                      parent-managed; one sibling dependency, common-lib
├── Dockerfile                   two stages, non-root, HEALTHCHECK on /actuator/health
├── README.md                    this file
└── src/
    ├── main/java/com/carddemo/transaction/
    │   ├── TransactionApplication.java        @SpringBootApplication entry point
    │   ├── api/                               TransactionController, BillPaymentController
    │   ├── service/                           TransactionListService, TransactionViewService,
    │   │                                      TransactionAddService, BillPaymentService,
    │   │                                      AccountContextClient, RestAccountContextClient
    │   ├── repository/                        Transaction, DailyTransaction, Reject,
    │   │                                      CategoryBalance and AccountBalance repositories
    │   ├── domain/                            Transaction, DailyTransaction, TransactionReject,
    │   │                                      TransactionCategoryBalance
    │   ├── dto/                               request, response, preview and outcome types
    │   ├── mapper/                            TransactionMapper, BillPaymentMapper
    │   └── config/                            SecurityConfig, DataSourceConfig,
    │                                          OpenApiConfig, InternalIdentityConfig
    ├── main/resources/
    │   ├── application.yml, application-dev.yml, application-prod.yml
    │   ├── db/migration/V1__ledger.sql
    │   ├── db/migration/V2__ledger_transaction_id_allocator.sql
    │   └── openapi/transaction-api.yaml
    └── test/
        ├── java/com/carddemo/transaction/     nine subpackages; see section 9
        └── resources/
            ├── application-test.yml
            ├── db/testharness/test-harness-account-schema.sql
            └── fixtures/<nine scenarios>/{transact,dailytran,tcatbal}.txt
```

Every package under `src/main/java` and `src/test/java` carries a
`package-info.java`. That is not decoration: section 12 explains that the
documentation gate audits packages and test sources too, so a package without one
fails the build.

---

## 4. Record contracts and the `ledger` schema

### 4.1 The three record layouts

| Copybook | Record | Length | Target table |
|---|---|---|---|
| [`app/cpy/CVTRA05Y.cpy`](../../app/cpy/CVTRA05Y.cpy) (21 lines) | `TRAN-RECORD` | **350** bytes | `ledger.transactions` |
| [`app/cpy/CVTRA06Y.cpy`](../../app/cpy/CVTRA06Y.cpy) (21 lines) | `DALYTRAN-RECORD` | **350** bytes | `ledger.daily_transactions` |
| [`app/cpy/CVTRA01Y.cpy`](../../app/cpy/CVTRA01Y.cpy) (13 lines) | `TRAN-CAT-BAL-RECORD` | **50** bytes | `ledger.transaction_category_balances` |

`DALYTRAN-RECORD` is structurally **identical** to `TRAN-RECORD`, field for field
and width for width; only the field-name prefix differs, `DALYTRAN-` against
`TRAN-`. The two tables are nevertheless separate, because the feed and the ledger
have different identity and different nullability — see 4.4 and 4.5.

`FILLER` is dropped from every record, and each drop is recorded at the entity that
drops it: `TRAN-RECORD` and `DALYTRAN-RECORD` each end in `FILLER PIC X(20)` padding
to 350, and `TRAN-CAT-BAL-RECORD` ends in `FILLER PIC X(22)` padding to 50. **No
field is renamed in this module.** The three documented misspelling corrections in
the migration belong to `account-service`, `card-service` and
`authorization-service`; none of the fields in these three records is misspelled.

### 4.2 The offset corroboration, from two unrelated sources

Two independent sources fix where the card number and the processing timestamp sit
inside the 350-byte transaction record, and they agree exactly.

Summing the declared PICTURE widths of `CVTRA05Y.cpy` puts `TRAN-CARD-NUM` at
zero-based offset **262** and `TRAN-PROC-TS` at **304**, with the trailing `FILLER`
closing the record at exactly 350:

| Field | PICTURE | Width | Zero-based offset |
|---|---|---|---|
| `TRAN-ID` | `X(16)` | 16 | 0 |
| `TRAN-TYPE-CD` | `X(02)` | 2 | 16 |
| `TRAN-CAT-CD` | `9(04)` | 4 | 18 |
| `TRAN-SOURCE` | `X(10)` | 10 | 22 |
| `TRAN-DESC` | `X(100)` | 100 | 32 |
| `TRAN-AMT` | `S9(09)V99` | 11 | 132 |
| `TRAN-MERCHANT-ID` | `9(09)` | 9 | 143 |
| `TRAN-MERCHANT-NAME` | `X(50)` | 50 | 152 |
| `TRAN-MERCHANT-CITY` | `X(50)` | 50 | 202 |
| `TRAN-MERCHANT-ZIP` | `X(10)` | 10 | 252 |
| `TRAN-CARD-NUM` | `X(16)` | 16 | **262** |
| `TRAN-ORIG-TS` | `X(26)` | 26 | 278 |
| `TRAN-PROC-TS` | `X(26)` | 26 | **304** |
| `FILLER` | `X(20)` | 20 | 330, closing at 350 |

Independently, the report job's sort control at
[`app/jcl/TRANREPT.jcl`](../../app/jcl/TRANREPT.jcl) L41 to L42 declares
`TRAN-CARD-NUM,263,16,ZD` and `TRAN-PROC-DT,305,10,CH` in **one-based** positions.
One-based 263 and 305 are zero-based 262 and 304, so the sort control and the
copybook arithmetic land on the same two bytes.

Assumptions: `S` and `V` occupy no byte in a display field, which is why
`S9(09)V99` is 11 and not 13 — the sign is overpunched onto the low-order digit and
the decimal point is implied rather than stored. Getting that wrong shifts every
subsequent offset and is the single easiest way to produce a table of byte positions
that looks authoritative and is uniformly 14 bytes wrong.

That agreement is what confirms the alternate-index key. `app/jcl/TRANIDX.jcl`
L27 declares `KEYS(26 304)` — written space-separated in the source, and left that
way here — with `NONUNIQUEKEY` on L28 and `UPGRADE` on L29. Twenty-six bytes at
offset 304 is exactly `TRAN-PROC-TS`.

### 4.3 The three query paths and their three indexes

| Access path | Object | Replaces |
|---|---|---|
| by transaction identifier | `pk_transactions PRIMARY KEY (transaction_id)` on `CHAR(16) COLLATE "C"` | the KSDS primary key |
| by card number | `idx_transactions_card_num` | the online list path of `COTRN00C` and the bill-pay browse of `COBIL00C` |
| by processing timestamp | `idx_transactions_proc_ts`, **non-unique** | the batch alternate index `TRANSACT.VSAM.AIX` |

`transaction_id` is declared `COLLATE "C"`, so every comparison and every
`ORDER BY` over it -- and the primary-key index built on it -- sorts by byte
value rather than by whatever collation the cluster was initialised with. That
matters because the ordered reads over this column, here and in `batch-service`
and `reporting-service`, are derived or JPQL queries that name no `COLLATE`
clause, and because the column stores identifiers that are not all digits: the
interest accrual mints keys of the form `2022-07-18000001`, which a collation
giving punctuation no primary weight orders after every all-digit key where a
byte comparison orders it before. The reference sorts the field `CH`, byte-wise,
at `app/jcl/COMBTRAN.jcl` line 28. The full reasoning, the alternatives weighed
and the two sibling columns deliberately left unpinned are recorded in the
collation note at the foot of `db/migration/V1__ledger.sql`, and
`TransactionRepositoryIT` asserts both the declared collation and the resulting
order.

`idx_transactions_proc_ts` is created with a plain `CREATE INDEX`, which is
non-unique in PostgreSQL, matching the `NONUNIQUEKEY` the baseline declares. A
unique index here would refuse two transactions that share a processing instant,
which the baseline accepts.

The **`BLDINDEX` step retires.** `app/jcl/TRANIDX.jcl` L52 runs
`IDCAMS BLDINDEX` as its own job step because VSAM builds an alternate index as a
separate pass over the base cluster. PostgreSQL maintains an index transactionally
as part of the write that changes it, so there is no build pass to schedule and no
step to run. What survives the retirement is the **key**, as
`idx_transactions_proc_ts`; what does not survive is the job step, and nothing in
the batch chain needs to replace it.

### 4.4 The 430-byte reject contract

[`app/jcl/POSTTRAN.jcl`](../../app/jcl/POSTTRAN.jcl) L36 defines the reject stream
as `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)`, and
[`app/jcl/DALYREJS.jcl`](../../app/jcl/DALYREJS.jcl) L24 to L27 defines its
generation base with `LIMIT(5)` and `SCRATCH`. Those 430 bytes are preserved as
exactly **three** columns, which sum to 430:

| Column | Type | Bytes |
|---|---|---|
| `raw_record` | `CHAR(350) NOT NULL` | 350, the rejected record verbatim |
| `reason_code` | `SMALLINT NOT NULL`, `CHECK (reason_code BETWEEN 0 AND 9999)` | 4 |
| `reason_desc` | `VARCHAR(76) NOT NULL` | 76 |

**The raw record is deliberately not decomposed into fields.** A rejected record
has to be retained byte for byte so it can be re-driven after the condition that
rejected it is corrected, and decomposition would lose the very bytes that caused
the reject: a record rejected because its card number is not in the cross-reference
is a record whose card number cannot be resolved, and a column-per-field table
either refuses the row or silently normalises the offending value away. Keeping it
as one fixed-width column costs the ability to query inside a rejected record and
buys the guarantee that what is stored is what arrived.

The table adds a surrogate `reject_seq` identity primary key. Assumptions: a reject
stream is append-only and has no natural key — the same record may be rejected on
two runs, and two different records may be rejected for the same reason — so the
only honest key is the arrival order the sequence records.

### 4.5 Identity and nullability, table by table

| Table | Primary key | Note |
|---|---|---|
| `transactions` | `transaction_id CHAR(16)` | the record's own key; `proc_ts TIMESTAMP(6) NOT NULL` |
| `daily_transactions` | surrogate `ingest_seq` identity | `proc_ts TIMESTAMP(6)` **nullable** |
| `transaction_rejects` | surrogate `reject_seq` identity | see 4.4 |
| `transaction_category_balances` | **composite** `(account_id, type_cd, category_cd)` | matches the `TRAN-CAT-KEY` group of `CVTRA01Y.cpy` |

`daily_transactions.proc_ts` is nullable while `transactions.proc_ts` is `NOT NULL`,
and the asymmetry is a data fact rather than a preference: the pre-posting feed
carries the processing timestamp as 26 spaces because nothing has processed the row
yet, and the ETL maps that to `NULL`. Asserting `NOT NULL` on the feed would refuse
every row the baseline actually produces.

The composite key on `transaction_category_balances` is the group-level
`TRAN-CAT-KEY` of `CVTRA01Y.cpy` written out as three columns — `TRANCAT-ACCT-ID`
`9(11)`, `TRANCAT-TYPE-CD` `X(02)` and `TRANCAT-CD` `9(04)`, seventeen bytes
together. A surrogate key here would admit two rows for one account, type and
category, which the VSAM key structurally cannot.

Money columns are `NUMERIC(11,2)`, because `TRAN-AMT` and `TRAN-CAT-BAL` are both
`S9(09)V99`: nine integral digits plus two fractional digits is a precision of 11
at a scale of 2. Widening to `NUMERIC(12,2)` would admit a ten-digit amount the
fixed-width record cannot represent, so a value that round-trips through this table
would not round-trip through the baseline.

---

## 5. Behaviour preserved exactly

### 5.1 Money is exact fixed point end to end

`NUMERIC(p,2)` in SQL, `BigDecimal` at scale 2 with `RoundingMode.HALF_UP` in Java
through `Money` from `common-lib`, and a **JSON string** on the wire through
`MoneyModule`. `float`, `double` and JSON numbers are forbidden in the money path,
and the prohibition is enforced by an ArchUnit test rather than left to review.

The string on the wire is the part that looks like fussiness and is not: a JSON
number is parsed into an IEEE-754 double by most clients, a double cannot represent
every two-decimal value, and the loss therefore lands at the boundary the user
actually reads. The contract is published as two schemas whose widths differ
because the two reference fields differ:

| Schema | Pattern | Reference field |
|---|---|---|
| `TransactionAmount` | `^-?[0-9]{1,9}\.[0-9]{2}$` | `TRAN-AMT`, `PIC S9(09)V99` |
| `AccountBalance` | `^-?[0-9]{1,10}\.[0-9]{2}$` | `ACCT-CURR-BAL`, `PIC S9(10)V99` |

The leading sign is part of both patterns because a credit balance is an ordinary
state, and a client validating digits only would reject every negative amount it
was sent.

### 5.2 Keyset pagination, never offset

Both browse surfaces page by key: the transaction list of `COTRN00C` **and** the
bill-pay browse of `COBIL00C`.

| Direction | Predicate | Ordering | Limit |
|---|---|---|---|
| forward | key **strictly greater than** `lastKey` | ascending | page size **+ 1** |
| backward | key **strictly less than** `firstKey` | descending | page size **+ 1** |

Offset pagination was rejected, and the reason is behavioural rather than about
speed: under a concurrent insert an offset window skips rows and repeats rows,
because the rows before the offset shift while the reader is paging. Browse-by-key
does not, because the key it resumes from names a row rather than a position. The
repository interface takes a `Limit` rather than a `Pageable` for exactly this
reason — `Pageable` emits a SQL `OFFSET` clause, so accepting one would reintroduce
the semantics the design exists to avoid.

`hasNext` is discovered by the **fetch-one-extra** technique: the query asks for one
row beyond the page and the presence of that extra row is the answer. Assumptions:
this is precisely how the COBOL discovers it. `COTRN00C` reads forward with
`STARTBR` at L281 and `READNEXT` at L286, and it learns that a further page exists
by finding one more record than fits the screen; the backward path at `STARTBR`
L335 with `READPREV` L340 is the mirror image, which is why the backward query is
ordered descending rather than reversed after the fact.

### 5.3 Timestamps

`PIC X(26)` becomes `TIMESTAMP(6)` in SQL and `LocalDateTime` in Java, formatted by
`TimestampFormatter` from `common-lib` to exactly the 26-character
`'YYYY-MM-DD HH:MM:SS.mmmmmm'` form. Assumptions: microsecond precision matches the
declared width exactly — six fractional digits is what the 26 characters leave room
for, so `TIMESTAMP(6)` neither truncates a value the baseline can express nor admits
one it cannot.

### 5.4 Bill payment is one unit of work

Bill payment pays the outstanding balance **in full** and is the only
balance-affecting write this context performs. `EXEC CICS SYNCPOINT` becomes a
single `@Transactional` boundary and `EXEC CICS SYNCPOINT ROLLBACK` becomes
exception propagation, so the transaction row and the account balance either both
land or neither does. Section 6 explains why that stays one commit rather than
becoming two.

### 5.5 Field errors and message text

The `FLG-*-NOT-OK` and `FLG-*-BLANK` validation-flag pattern becomes a structured
per-field error array carried by `ApiError` from `common-lib`, including the literal
`'*'` marker the baseline moves into a field that was left blank. Every
user-visible message is carried verbatim, character for character, keyed by the
copybook it came from.

The message-line contract for this module is the **75**-character form declared in
[`app/cpy/CVCRD01Y.cpy`](../../app/cpy/CVCRD01Y.cpy): `CCARD-ERROR-MSG PIC X(75)`
at L28 and `CCARD-RETURN-MSG PIC X(75)` at L29. That is a different contract from
the 50-character messages in `CSMSG01Y`, and the two are not interchangeable — a
message authored against the shorter width and rendered into the longer band is not
the string the baseline displayed.

L30 declares `88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES`. That sentinel maps to
null or absent, and it is **not** interchangeable with `SPACES`: the baseline
distinguishes "no message" from "a message of 75 blanks", so collapsing the two
would make an absent message render as an empty band that the client cannot tell
from a suppressed one.

### 5.6 Card numbers and exposure

Primary account numbers are masked to the **last four digits in every response** this
module publishes. No card verification value is returned by any endpoint here;
this context does not store one.

The card number is `CHAR(16)` in the database and a digits-only **string** in every
DTO. Assumptions: fixed width is part of the contract rather than incidental
padding, and a numeric type is wrong twice over — sixteen digits exceed what a
double carries exactly, and the live seed data contains a card number with a
leading zero, which any numeric type silently drops. The baseline itself treats
these fields as characters and as numbers only for arithmetic: `CVCRD01Y.cpy` L37
to L39 declares `CC-CARD-NUM PIC X(16)` with a separate
`CC-CARD-NUM-N REDEFINES CC-CARD-NUM PIC 9(16)`, and the same X-over-9 pairing
appears for the account identifier at L34 to L36.

---

## 6. The cross-schema grant, and why it is not a database-per-service violation

`batch-service` writes `ledger.*`. It does so under a **narrowly scoped
cross-schema grant** rather than through this module, so that the posting unit of
work — the transaction, the category balance and the account, together — remains a
**single ACID commit**. The grants are named, not blanket:
`data-migration/sql/V0__schemas_and_roles.sql` L1140 gives the batch role `USAGE` on
`ledger`, and L1162 gives it `SELECT`, `INSERT`, `UPDATE` on that schema's tables.
There is no `DELETE` and no `CREATE`.

*A transactional-outbox-plus-compensating-reversal design was considered and
rejected: it would introduce observable partial-posting states that do not exist in
the baseline, which would break golden-master parity outright.* A posted
transaction whose category balance has not yet moved is a state the baseline cannot
produce, and the golden masters would correctly report it as a parity failure rather
than as an implementation detail.

The two modules therefore agree on **column names, types and scale — never through
code**:

- there is **no** Maven dependency between `transaction-service` and
  `batch-service`, in either direction;
- there is **no** cross-service `domain` import, and ArchUnit forbids one;
- consequently **any change to `V1__ledger.sql` is a change to the posting unit of
  work**, and has to be reasoned about as one even though no Java file in
  `batch-service` mentions this module.

The same reasoning runs the other way for bill payment. This module's own runtime
role reaches into the account schema by name — `V0__schemas_and_roles.sql` L1348
grants `USAGE ON SCHEMA account` and L1357 grants `SELECT, UPDATE ON
account.accounts`, and nothing wider — so the balance write stays inside the one
commit that writes the transaction. It does not become a saga, for the same reason
posting does not: a saga would make a paid bill with an unmoved balance observable.

The `ledger` schema, its roles and its grants are **bootstrapped** by
`data-migration/sql/V0__schemas_and_roles.sql`, which also makes the migration role
the owner of the schema so the runtime role can be reduced to named DML. This
module's `V1__ledger.sql` defines only the four tables and their indexes; it creates
no schema, no role and no grant.

---

## 7. API surface

Five operations across four paths, as published by
[`openapi/transaction-api.yaml`](src/main/resources/openapi/transaction-api.yaml)
(`openapi: 3.1.1`). That contract is what
[`ui/src/api/transactions.ts`](../../ui/src/api/transactions.ts) is written
against, so it is the agreement between this module and the browser client rather
than a description of it.

| Method | Path | Replaces | Status codes |
|---|---|---|---|
| `GET` | `/api/v1/transactions` | `COTRN00C` | 200, 400, 401, 403, 500 |
| `POST` | `/api/v1/transactions` | `COTRN02C` | 200, 201, 400, 401, 403, 404, **409**, 500, 503 |
| `POST` | `/api/v1/transactions/copy-last` | the backward read of `COTRN02C` that pre-fills the form from the last stored transaction | 200, 201, 400, 401, 403, 404, **409**, 500, 503 |
| `GET` | `/api/v1/transactions/{transactionId}` | `COTRN01C` | 200, 400, 401, 403, 404, 500 |
| `POST` | `/api/v1/billpay` | `COBIL00C` | 200, 201, 400, 401, 403, 404, **409**, 500, 503 |

**409 Conflict** is the duplicate-transaction-identifier answer on every write path,
including bill payment, which writes a transaction of its own and can therefore
collide the same way. A `409` also carries the stale-state conflict a category-balance
update raises when the row it expected has moved.

The list operation is keyset-paged and answers with the `PageResponse` envelope
described in 5.2; it publishes no page number and no total count, because neither is
derivable from a keyset cursor without the full-table enumeration the design exists
to avoid.

Authentication is a **Cognito JWT resource server**. Group claims become Spring
Security authorities through `JwtRoleConverter` from `common-lib`, which is what
replaces the client-echoed user-type field of the baseline COMMAREA.

The filter chain in
[`src/main/java/com/carddemo/transaction/config/SecurityConfig.java`](src/main/java/com/carddemo/transaction/config/SecurityConfig.java)
is worth stating exactly rather than in summary, because these are the operations
whose misuse moves money:

| Surface | Rule | Token required |
|---|---|---|
| `/actuator/health/**` | permitted to all | **no** — it is the container and target-group probe |
| `/actuator/prometheus` | loopback only | **no**, but reachable only from `127.0.0.1/32` or `::1/128` |
| `/actuator/**` | loopback only | **no**, same restriction; matched after the more specific health pattern |
| everything else, including all five business operations | business authority | **yes** |

Assumptions: what protects the two metric and management surfaces is **network
position rather than authentication**, and stating that plainly is the point. The
collector scrapes the metric endpoint from inside the task over loopback, so nothing
off the box can reach either path — but a reader auditing exposure who was told "a
token is required everywhere except health" would not go on to check that the
loopback restriction is actually in place. Understating an exposed surface is the
dangerous direction to be wrong in.

---

## 8. Configuration

**No endpoint, credential or secret is hard-coded anywhere in this module.** Values
arrive from Terraform outputs by way of **Parameter Store** and **Secrets Manager**,
injected as environment variables by the ECS task definition. The table below lists
variable **names** only; every value column would be empty by design, so none is
shown.

Eleven variables have **no fallback** in `application.yml` or either profile, so an
incomplete environment stops the context at startup rather than serving requests
bound to nothing:

| Variable | Selects | Deployment source |
|---|---|---|
| `SPRING_DATASOURCE_URL` | JDBC location of the cluster | Parameter Store, the Aurora writer endpoint |
| `SPRING_DATASOURCE_USERNAME` | the `ledger` runtime role | Secrets Manager |
| `SPRING_DATASOURCE_PASSWORD` | credential for that role | Secrets Manager |
| `SPRING_FLYWAY_USER` | the **migration** role, distinct from the runtime role | Secrets Manager |
| `SPRING_FLYWAY_PASSWORD` | credential for the migration role | Secrets Manager |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | issuer whose keys validate presented tokens | Terraform output |
| `CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID` | app client a presented token must name | Parameter Store |
| `CARDDEMO_ACCOUNT_CONTEXT_BASE_URL` | where the account context answers | Parameter Store, the internal load balancer |
| `CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY` | seals the internal token this context presents to the account context | Secrets Manager |
| `CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY` | seals the keyset cursor | Secrets Manager |
| `CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD` | opens the listener keystore | minted per task by the image entry point |

Four of those deserve emphasis, because a deployment provisioned from an older
inventory would omit them and fail at startup:

- The **Flyway credential pair** is separate from the runtime pair on purpose.
  Migration runs as the migration role, which makes that role the owner of the
  schema and every table in it, so the runtime role can be reduced to named DML.
- The **internal signing key** is load-bearing rather than decorative: bill payment
  crosses into the account context, and the token authorising that hop is signed
  with it.
- The **cursor signing key** stops startup when absent, by design. Its bean is
  conditional on the property and the controller declares the cursor type as a
  required constructor parameter, so an absent value fails context refresh with a
  missing-bean report instead of degrading. A default was rejected in the shared
  kernel because an unkeyed cursor is forgeable, and a forged cursor is a read of
  somebody else's page.

Everything else — group names, the database trust anchor, the environment and
version tags, TLS paths and the cursor lifetime — carries a documented default in
[`application.yml`](src/main/resources/application.yml).

### 8.1 Profiles, datasource and health

`application.yml` carries the shared configuration; `application-dev.yml` and
`application-prod.yml` differ only in connection-pool sizing, JPA settings,
management exposure and logging levels, with the production profile additionally
disabling the interactive API browser.

The datasource pins the schema with a connection-initialisation statement,
`SET search_path TO ledger`. Assumptions: this was chosen over a `currentSchema`
parameter on the JDBC URL so that the schema is set by configuration this module
owns rather than by a fragment of a connection string assembled elsewhere; the URL
arrives from Parameter Store, and a schema smuggled into it would be invisible here.
The pool is sized at a maximum of 10 with a minimum of 2 idle and a 10-second
connection timeout.

Actuator health is exposed for **both** readers: the load balancer target group and
the container `HEALTHCHECK` in this module's Dockerfile probe the same
`/actuator/health` path, which makes it a deployment contract rather than a
convenience. Health detail is suppressed on that path, so the unauthenticated probe
surface carries no diagnostics.

### 8.2 Flyway

Flyway runs `db/migration/V1__ledger.sql` and then
`db/migration/V2__ledger_transaction_id_allocator.sql` from
`classpath:db/migration`, against schema `ledger`, as the migration role.

Two Flyway artifacts are required, not one: the `spring-boot-starter-flyway` starter
**and** `org.flywaydb:flyway-database-postgresql`. Assumptions: Flyway 10 and later
moved PostgreSQL support out of the core artifact into a per-database module, so the
core alone resolves and compiles and then **fails at run time** when it cannot find
a database implementation for the URL it was given. Both versions come from the
parent POM at `flyway.version` 13.0.0; neither is pinned here.

The second migration exists because identifier allocation could not stay as
read-the-maximum-then-add-one. Refactoring Rationale: that shape was correct while a
single CICS region serialised the two transactions that used it, and it is not
correct behind a load balancer — two tasks read the same maximum, derived the same
key, and one caller received a constraint violation surfaced as an internal error.
`V2` adds `ledger.transaction_id_seq` so allocation is a single atomic `nextval`.
The maximum-key read survives for the copy path only, which genuinely needs the last
**stored** record rather than the next free key.


---

## 9. Build, run and test

### 9.1 Build and test

```bash
# WHAT: build and test this module through the reactor, unit tier and integration
#       tier together.
# WHY : Assumptions: (1) `-am` is required because common-lib is an unpublished
#       reactor sibling, so without it Maven looks for com.carddemo:common-lib in a
#       repository that has never held it; (2) `verify` rather than `test`, because
#       Failsafe binds to integration-test and verify, so `test` alone runs the 26
#       *Test classes and silently skips all SEVEN *IT classes -- including the
#       keyset paging and reject-stream assertions, which are the ones a reader is
#       most likely to assume are covered. A container runtime is required.
mvn -B -f services/pom.xml -pl transaction-service -am verify

# WHAT: run the whole reactor, all nine modules in declared order.
# WHY : Assumptions: common-lib is built before this module because the aggregator
#       declares it first, and its test-jar carries the shared architecture rules
#       that the parent's `architecture-rules` Surefire execution re-runs inside
#       every module through dependenciesToScan. Running this module alone still
#       executes those rules; running the reactor also proves no sibling broke them.
mvn -B -f services/pom.xml clean verify
```

```bash
# WHAT: run the unit tier only, on a machine with no container runtime.
# WHY : Trade-offs: faster, and it proves strictly less -- the seven Testcontainers
#       backed classes do not run. Use it while iterating and `verify` before
#       pushing, so the three keyset access paths are exercised before review.
mvn -B -f services/pom.xml -pl transaction-service -am test
```

```bash
# WHAT: fire the inherited Checkstyle documentation gate on its own, without
#       compiling or testing anything.
# WHY : Assumptions: services/pom.xml binds its checkstyle-documentation-gate
#       execution to the `validate` phase, and validate is the first phase of the
#       default lifecycle, so naming it runs the gate and nothing after it. This is
#       the fastest way to answer "is my Javadoc complete" -- seconds rather than a
#       full compile -- and it is the same gate that will fail the reactor build and
#       the image build, not a lighter approximation of it.
mvn -B -f services/transaction-service/pom.xml validate
```

### 9.2 Container image

```bash
# WHAT: build this module's image. The trailing dot is the REPOSITORY ROOT.
# WHY : Assumptions: the build context must be the repository root, for three
#       independent reasons, and narrowing it does not produce a smaller image --
#       it produces no image at all. (1) The build stage compiles the unpublished
#       common-lib sibling from source, and no registry carries it. (2) This module
#       declares its parent at <relativePath>../pom.xml</relativePath>, which a
#       module-scoped context does not contain, so Maven fails to resolve the parent
#       and reports a missing POM naming a path nobody wrote. (3) The inherited
#       documentation gate reads config/checkstyle, which sits outside the services
#       reactor. Running `docker build .` from inside this directory fails on the
#       first COPY with a message that names none of those three causes.
docker build -f services/transaction-service/Dockerfile \
  -t carddemo/transaction-service:local .
```

| Stage | Image | Role |
|---|---|---|
| `build` | `maven:3.9.16-amazoncorretto-21-al2023` | resolves dependencies, then compiles `common-lib` and this module in one invocation |
| `runtime` | `public.ecr.aws/amazoncorretto/amazoncorretto:21.0.12-al2023-headless` | carries one bootable jar at `/app/app.jar`, run as a fixed non-root identity, listening on 8080 |

Both references are additionally digest-pinned in the Dockerfile.

Assumptions: **no Alpine variant of the Corretto image exists.** The repository
publishes only `-al2` and `-al2023` tags with `headful`, `headless`, `generic` and
`jdk` suffixes, and 21.0.12 is the highest published 21.x; the intuitive
`21-alpine` *"does not exist and would have failed every image build."* The headless
suffix is selected because an HTTP service needs no AWT or graphics stack. The build
and runtime stages are matched on JDK vendor and major version so neither the
class-file major version nor the trust-store contents can diverge between compile
time and run time.

The image build runs with `-DskipTests`, and section 12.5 explains why that does not
weaken the documentation gate.

### 9.3 Test topology

<!-- test-inventory: 27 tests + 7 integration tests -->
**34** test classes across nine subpackages: **27** matching `*Test`, run by
Surefire, and **7** matching `*IT`, run by Failsafe. The `*RepositoryIT` naming
already matches Failsafe's default include pattern, so neither plugin needs an
include list. That census is machine-checked — `ServiceReadmeInventoryTest` in
`common-lib` parses the marker comment above and re-measures both figures against
this module's test tree, so a stale number here fails a build rather than misleading
a reader.

| Package | Classes |
|---|---|
| `api` | `TransactionControllerTest`, `BillPaymentControllerTest`, `TransactionApiRoutingContractTest`, `TransactionCaptureWireContractTest` |
| `service` | `TransactionListServiceTest`, `TransactionListServiceCursorBindingTest`, `TransactionAddServiceTest`, `TransactionViewServiceTest`, `BillPaymentServiceTest`, `BillPaymentEvaluationOrderTest`, `BillPaymentUnitOfWorkIT` |
| `repository` | `TransactionRepositoryIT`, `DailyTransactionRepositoryIT`, `TransactionCategoryBalanceRepositoryIT`, `TransactionRejectRepositoryIT`, `AccountBalanceRepositoryIT`, `BillPaymentAtomicityIT`, `AccountBalanceSchemaAgreementTest` |
| `mapper` | `TransactionMapperTest`, `BillPaymentMapperTest`, `BillPaymentMappingTest` |
| `domain` | `MoneyColumnInvariantTest`, `FixedWidthMappingTest`, `FeedRowIdentityTest`, `OccurrenceIdentityTest` |
| `architecture` | `TransactionLayeringRulesTest`, `MoneyPathGateProofTest`, `KeysetPaginationGateProofTest` |
| `dto` | `TransactionApiContractTest`, `TransactionAddRequestTest` |
| `config` | `SecurityConfigTest`, `SecurityChainDispatchTest`, `OpenApiConfigTest` |
| `fixtures` | `TransactionFixtureContractTest` |

What the tiers assert:

- **Controller tests** stand the web layer up in isolation and assert routing, the
  status codes of section 7 and the shape of the error body.
- **`TransactionListServiceTest`** asserts forward and backward paging, `hasNext`
  discovery through the size-plus-one fetch, and that a page boundary stays stable
  when a row is inserted concurrently — which is the property offset paging cannot
  hold.
- **`TransactionAddServiceTest`** covers every transcribed validation branch and
  identifier generation.
- **`BillPaymentServiceTest`** covers the single-transaction balance write, with
  `BillPaymentUnitOfWorkIT` and `BillPaymentAtomicityIT` proving the boundary is one
  commit rather than two.
- **`*RepositoryIT`** run under Testcontainers, whose BOM comes from the parent at
  `testcontainers.version` 2.0.5, and exercise all **three** query paths of 4.3
  including the processing-timestamp index.
- **`SecurityChainDispatchTest`** stands the deployed filter chain up in a web slice and
  asserts what it does with the container's own ERROR dispatch. That rule matches a
  dispatcher type rather than a path or an authority, so nothing addressable from the rule
  table can witness it; the case that matters most is the one issuing a MUTATING method on
  an error dispatch, because this chain authorises every business route through one
  catch-all and a forward keeps the original method.
- **Money tests** assert scale-2 `BigDecimal` and JSON-**string** serialisation, and
  the two architecture proof tests assert that the money-path and keyset gates
  actually fire by presenting them with a deliberate violation.

Fixtures live at `src/test/resources/fixtures/<scenario>/` and are byte-exact at the
verified record lengths — `transact.txt` 350, `dailytran.txt` 350, `tcatbal.txt` 50.

Assumptions: a **real PostgreSQL** is required for the integration tier rather than
an in-memory database. The three keyset access paths and the deliberately
**non-unique** `idx_transactions_proc_ts` cannot be exercised on H2: it does not
reproduce PostgreSQL's index semantics or its `CHAR` padding, so a green in-memory
run would prove nothing about the access paths this schema exists to provide.

Both tiers write to their **default** report directories,
`services/*/target/surefire-reports/` and `services/*/target/failsafe-reports/`, and
those must never be relocated. The continuous-integration workflow collects from
exactly those paths, so moving either one makes the build green while the workflow
publishes nothing.

### 9.4 Running it locally

A local run needs a reachable **database** and a reachable **token issuer** before
it will start, in that order: the migration credential is used before anything else,
and the resource server fetches issuer discovery while the context refreshes rather
than on first request. This module declares no queue listener, so no queue has to
exist for it to start.

```bash
# WHAT: create the local environment file with owner-only permissions BEFORE any
#       value is written into it, then confirm it is ignored by git.
# WHY : Assumptions: the name is `.env.transaction-service.local` specifically
#       because the root .gitignore ignores `.env.*` at line 223, which the
#       check-ignore call proves by printing the matching rule and its line number.
#       A name such as `transaction-service.env` matches no ignore rule and would be
#       staged by `git add -A` along with the database password and the signing keys.
# WHY : Assumptions: `umask 077` is applied before creation rather than corrected
#       afterwards, because a later chmod leaves a window in which the file was
#       group- and world-readable.
umask 077
touch .env.transaction-service.local
git check-ignore -v .env.transaction-service.local
```

Fill that file with one `KEY=value` line per variable for the eleven names in
section 8, then start the module:

```bash
# WHAT: build the bootable jar and start it on the loopback interface only.
# WHY : Assumptions: SERVER_ADDRESS binds the listener to loopback and is a
#       configured property rather than a description -- no profile here sets
#       server.address, and the variable reaches Spring Boot through relaxed
#       binding. Verified by reading the kernel's listening socket both ways: with
#       it set, /proc/net/tcp shows local address 0100007F and a request to the
#       host's routable address is refused; without it the row is 00000000 and the
#       same request is answered from off-host.
# WHY : Assumptions: SERVER_SSL_ENABLED is turned off for a LOCAL run only.
#       application.yml enables TLS and reads listener material from a PKCS#12
#       keystore that the image entry point mints per task, so no keystore exists on
#       a developer machine and the process would fail while trying to open one.
#       Disabling the transport is only defensible once nothing outside the machine
#       can reach the port, which is what the line above establishes.
# WHY : Alternatives Considered: running the image entry point locally so a local
#       run also speaks TLS. Rejected because the self-signed certificate it mints
#       would not match `localhost` for any caller that verified it, and turning TLS
#       off for a loopback hop states plainly that a local run does not exercise the
#       deployed transport rather than appearing to.
mvn -B -f services/pom.xml -pl transaction-service -am package

set -a && . ./.env.transaction-service.local && set +a
SERVER_ADDRESS=127.0.0.1 SERVER_SSL_ENABLED=false \
  java -jar services/transaction-service/target/transaction-service-1.0.0-SNAPSHOT.jar
```

---

## 10. Parity caveat: there is no golden master for these paths

`tests/README.md` records the limitation verbatim at L83:

> - **Online `CO*` CICS programs** cannot run end-to-end without a CICS runtime

All four programs this module migrates are `CO*` online programs. The consequence
has to be stated plainly rather than implied: the batch golden-master oracle
**covers batch flows only**, so for the transaction list, view, add and bill-payment
paths there is **no byte-comparable golden output to diff against**. Any claim that
these paths are golden-master verified would be false.

Parity for this module therefore rests on two narrower foundations, and they are
narrower on purpose:

1. **Validation logic transcribed paragraph by paragraph** from the four COBOL
   programs, with each transcription citing the lines it came from, so a reviewer can
   check the transcription against the source rather than against a summary of it.
2. **The copybook contracts** of section 4, which fix field widths, offsets, scale
   and sign for every column and every DTO field.

New Java tests are **strictly additive**. `tests/**` and `scripts/**` are
reference-only: they are never modified, never re-pinned, and nothing in this module
depends on them. They remain the parity oracle for the batch chain that writes these
same tables, which is the reason their pins matter enough to leave alone.

---

## 11. What must not be added here

Each entry carries the specific reason it is excluded, because an unexplained
prohibition is indistinguishable from an oversight and gets reversed by the next
contributor.

- **No Maven dependency on any sibling service module.** `common-lib` only. Cross-service
  `domain` imports are forbidden by ArchUnit, so a violation fails the build rather
  than a review. In particular this module must not import `batch-service` types and
  `batch-service` must not import these — section 6 explains that the two agree
  through the schema instead.
- **No Lombok.** Generated accessors cannot carry the Javadoc the explainability rule
  requires, so adding Lombok would itself breach the documentation gate described in
  section 12. Java 21 `record` types with explicit constructors give the same brevity
  with members that can be documented.
- **No MapStruct.** Its newest published release is a beta, and copybook-to-DTO
  mapping here is not mechanical: it drops `FILLER`, masks the card number to its
  last four digits, and carries money as strings. *"Every one of those decisions needs
  an inline justification at the mapping site, which a generated mapper cannot
  hold."*
- **No resilience library and no circuit breaker.** Retry lives in Spring Framework 7
  core, which arrives with the Boot parent. The attribute is **`maxRetries`** — total
  attempts are one plus its value — and the enabler is **`@EnableResilientMethods`**,
  **not** `@EnableRetry`; both are easy to get wrong from memory, so both must be
  commented at any use site. A circuit breaker is omitted because the only
  synchronous hop this module makes is in-VPC to the account context behind an
  internal load balancer with bounded connect and read timeouts, so a breaker would
  add a failure mode without removing one.
- **No Redis, ElastiCache, Kafka, Kinesis, caching starter or read replica.** The
  baseline has no cache tier and no replica, so none is needed for parity, and a
  replica would add replica-lag semantics to reads that currently have none.
- **No `spring-boot-starter-batch`, no Spring Cloud AWS and no AWS SDK**, and therefore
  **no `SqsConfig` and no `BatchConfig`**. This bounded context has no queue and no
  job repository; the posting job lives in `batch-service`.
- **No offset pagination on any browse endpoint**, and no `Pageable` parameter on a
  repository method, because `Pageable` emits a SQL `OFFSET` clause. Section 5.2
  gives the behavioural reason.
- **No saga and no two-phase commit** for anything touching these tables. Section 6
  names the rejected design and the observable state it would introduce.
- **No `float`, no `double` and no JSON number in the money path.** Section 5.1 gives
  the reason and the ArchUnit test that enforces it.
- **No secrets, credentials or endpoint values**, in any file in this module. No card
  verification value in any response, and card numbers masked to the last four digits.
- **No `.gitignore` in this module.** The root `.gitignore` adds `target/` at line 141
  deliberately un-anchored, so it already matches `services/*/target/`; a second
  ignore file here would duplicate a rule that is already in force and invite the two
  to drift.
- **No Maven wrapper** (`mvnw`, `mvnw.cmd`, `.mvn/`), because the reactor is driven by
  the toolchain the workflow installs. **No `settings.xml`**, because this project has
  no private registry and every artifact resolves from public repositories. **No
  module-local `checkstyle.xml` or `suppressions.xml`** — section 12.4 explains that
  re-declaring the ruleset is a rule violation rather than a configuration choice.
  **No `lombok.config`**, since Lombok is absent. **No `.dockerignore`**, because
  build-context scope is a repository-wide concern and the root file already excludes
  `.git`, every `target/`, `node_modules/`, `dist/`, `.terraform/` and the virtual
  environments.


---

## 12. Rule 1: Explainability, as it applies to this module

This project has exactly **one** user-specified rule, Rule 1 "Explainability". It
binds every file in this module. What follows states what it requires here and how
it is enforced; it does not restate the rule in place of the rule.

### 12.1 The convention

**Docstrings.** Every new or modified function, class and module entry point carries
a docstring, in the language's standard format — for this module that means
**Javadoc for Java**. Each docstring specifies four things:

| Section | Content |
|---|---|
| Purpose | what the function or class does |
| Parameters | name, type and description, for **each** parameter |
| Return values | the type, and a description of what is returned |
| Exceptions or errors | any that may be raised, where applicable |

The single concession is that *"Trivial accessors (getters/setters with no logic)
may use a single-line docstring."* Nothing else in this module is exempt.

**Inline comments** sit adjacent to the code they explain and must say **why** a
decision was made, not what the code does — the code already shows the what. Each
non-obvious decision documents at least one of four categories, written in exactly
these forms:

| Label | Records |
|---|---|
| `Alternatives Considered:` | what other approaches were evaluated, and why this one was chosen |
| `Refactoring Rationale:` | when replacing existing code, what was wrong with the old approach |
| `Assumptions:` | what external contract, data format or behaviour this code depends on |
| `Trade-offs:` | what compromise was accepted |

The plural and hyphenated spellings above are the **only** permitted written forms,
and 12.6 explains that a lexical gate enforces that mechanically across every
language at once. The singular spellings, a parenthesised form, and an
emphasis-wrapped form are all rejected. The two forms are never mixed inside one
file.

**Forbidden**, from the rule itself: a comment that restates what the code does; a
docstring that omits parameters, return values or purpose; a non-obvious choice left
undocumented where a reasonable alternative existed; and a vague rationale offered
without specific justification. Every why-comment in this module therefore names a
mechanism, a version, a byte offset, a failure mode or a rejected alternative. The
house standard for that bar is set by two in-repo exemplars: the compiler dialect in
`.github/workflows/tests.yml` at L23 to L27, chosen because the stricter IBM dialect
accepts the packed-decimal money fields the 1985 standard rejects; and the sign
convention in `tests/README.md` §5.2, required because the default misreads the
zoned-decimal sign overpunch and silently corrupts negative balances. Both name the
consequence of the alternative rather than asserting a preference.

**Validation Gate**, quoted, and note that it is conjunctive — a docstring is not a
substitute for a rationale, nor a rationale for a docstring: *"Every new or modified
function must have a docstring with purpose, parameters, and return values. Every
non-obvious implementation decision must have an inline comment explaining why that
approach was chosen using at least one of the categories above.
Code missing either fails review."*

Assumptions: that closing sentence is kept whole on one line rather than reflowed
into the paragraph above it, so `grep -F 'Code missing either fails review'` finds
it. A hard wrap through the middle of the clause renders identically and is
invisible to a reader, but it silently defeats the search that a reviewer or a
future gate would use to confirm the gate is stated — the same failure mode
`config/rule1/rule1_gate.py` guards against by tolerating a wrap inside a two-word
label.

### 12.2 The written convention, and what outranks what

The convention is written once, for every language in the new tree, at
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../docs/CODE_DOCUMENTATION_STANDARD.md).
Read it there rather than inferring it from this module.

Its precedence clause matters more than it first appears, and it is easy to get
backwards. The standard states at L22 to L28 that **the AAP and Rule 1 are
authoritative**: the standard explains that contract, the language configurations
encode only its machine-checkable subset, and where prose and configuration
disagree **both** must be corrected to match the AAP and Rule 1. A linter setting
may never narrow or override the governing requirement. The configuration is
authoritative for one narrow question only — what the build currently enforces.
The standard is explicit that collapsing this into "the configuration wins" would
invert its own precedence clause and make a linter's limitation a licence. So a gate
that enforces **more** than the prose describes means the prose was understating a
real gate and must be corrected upward; a gate that enforces **less** than Rule 1
requires means the gap stays a review failure until the setting is widened.

### 12.3 The in-repo precedent

This is not a foreign convention being imported. `tests/README.md` **§12** already
imposes the **same** four docstring sections — purpose, parameters, returns and
exceptions — and the **same** four justification categories on every test, fixture
builder, helper, mock and runner routine in the existing suite, and calls it
*"a hard review gate."* Rule 1 and existing house style therefore **agree
completely**: there is no conflict to resolve and no trade to make. This module
extends an established in-repo convention to Java rather than introducing a new one.

### 12.4 The Checkstyle gate

| Property | Value |
|---|---|
| Engine | Checkstyle **13.8.0**, pinned as a plugin dependency so the plugin's own bundled version is not used |
| Configuration | [`config/checkstyle/checkstyle.xml`](../../config/checkstyle/checkstyle.xml) |
| Binding | execution `checkstyle-documentation-gate`, phase **`validate`**, declared in [`services/pom.xml`](../pom.xml) |
| `failOnViolation` | `true` |
| `violationSeverity` | `warning` |
| `consoleOutput` | `true` |
| `includeTestSourceDirectory` | `true` |

Because the gate is bound to `validate` rather than to a reporting phase, it fires
on **every local build**, not only in the pipeline — and `validate` precedes
`package`, which is why it also runs inside `docker build`.

The consequences a contributor meets in practice:

- **`package-info.java` is required in every package.** Both `JavadocPackage` and
  `MissingJavadocPackage` are active, and `includeTestSourceDirectory` is `true`, so
  the requirement reaches every package under `src/main/java` **and** every package
  under `src/test/java`. No total is quoted here on purpose: a hand-kept count is a
  second source of truth that drifts the first time a package is added — this
  sentence read "seventeen" while the tree held eighteen — and the count carries no
  information the gate does not already guarantee. `mvn -f services/pom.xml -pl
  transaction-service validate` is what answers the question, and it answers it for
  the tree as it stands rather than as it stood when this line was written.
- **Every DTO `record` component needs an `@param`.** `JavadocType` runs with
  `allowMissingParamTags="false"`, and a record's components are its type
  parameters for this purpose.
- **`MissingJavadocType` includes `RECORD_DEF`**, alongside `INTERFACE_DEF`,
  `CLASS_DEF`, `ENUM_DEF` and `ANNOTATION_DEF`, at `scope="private"`. A record is
  not exempt for being short.
- **`JavadocMethod` runs `validateThrows="true"` and `allowMissingReturnTag="false"`**,
  across `public, protected, package, private`. A declared or thrown exception must
  be documented and a non-void method must document its return.
- **No annotation exempts a method.** `MissingJavadocMethod` sets `allowedAnnotations`
  to the **empty string**, so the usual escape hatch is closed:
  `TransactionApplication.java` and all four `config/*Config.java` classes need full
  Javadoc despite being almost entirely annotation.
- `NonEmptyAtclauseDescription`, `SummaryJavadoc`, `AtclauseOrder` and
  `CommentsIndentation` are active, so a tag with no description, a summary sentence
  that is missing or is a boilerplate accessor stub, tags out of order, and a comment
  indented away from the code it explains are each a failure. `SummaryJavadoc` also
  rejects deferred-work markers and, by literal match, two of the vague rationales
  Rule 1 itself names — so the phrases the rule calls insufficient cannot be written
  into a Java summary at all.
- The `SuppressionFilter` is declared `optional="false"`, so it **fails closed**: a
  missing or unreadable suppressions file stops the build instead of quietly
  disabling every suppression.

### 12.5 The gate is not to be relaxed, skipped, re-declared or weakened

Doing any of those is a **Rule 1 violation, not a build-configuration preference**.
The rule's own validation gate is what the Checkstyle binding mechanises, so
loosening the binding loosens the rule. The following must not appear anywhere in
this module, in a POM, a Dockerfile, a workflow or a script:

| Forbidden | Why it is forbidden |
|---|---|
| `-Dcheckstyle.skip` | turns the gate off for the invocation that most needs it |
| `<skip>true</skip>` on the plugin | the same, permanently |
| `-Dcheckstyle.failOnViolation=false` or `<failOnViolation>false</failOnViolation>` | reports findings and passes anyway, which is worse than not running: the log reads as evidence of a gate |
| `\|\| true` after the goal | discards the exit status the gate exists to produce |
| `continue-on-error: true` on a gate step | the workflow-level form of the same discard |
| any return-code tolerance of the form `[ "$rc" -le N ]` | see 12.6 |
| setting `suppressionsLocation` on the plugin | the ruleset already loads suppressions through its own filter; a second path would let one file name a suppressions file while the POM named another |
| adding a module-local `checkstyle.xml` or `suppressions.xml` | a second ruleset drifts from the first, and the drift is invisible because both resolve |

`-DskipTests` in the Dockerfile is a **different thing entirely** and is not on this
list. It skips Surefire and Failsafe. It does not skip Checkstyle, which is bound at
`validate` and therefore still runs during the image build — so a missing Javadoc
fails `docker build` itself.

### 12.6 The graded return-code rubric is quarantined to the COBOL oracle

The COBOL parity suite uses a **graded** rubric — `0` pass, `2` usage, `4` warn,
`8` fail, `16` fatal — documented at `tests/README.md` §8. Its current green state
is **`RC=4`**, and that warn tier is caused solely by the pre-existing, out-of-scope
`CBEXPORT` and `CBIMPORT` FD `RECORD KEY` defect in the immutable baseline. Reading
that 4 as a regression introduced by this migration is a misreading.

That rubric belongs **exclusively** to the COBOL oracle under `tests/**`. **A Maven
or JUnit gate is binary.** No graded tolerance, no warn tier and no return-code
arithmetic may ever reach one: a Checkstyle run either has zero findings or fails the
build, and a test either passes or fails. Importing the graded rubric into this
module's build would create a tier in which the build is green while a documented
requirement is unmet, which is precisely the outcome the rubric is safe for in the
oracle and unsafe for here.

The lexical half of the rule is enforced repository-wide by
[`config/rule1/rule1_gate.py`](../../config/rule1/rule1_gate.py), which is
fail-closed and governs Markdown as well as code. It decides the two things no
per-language linter reads: that every rationale label is written in its one
permitted form, and that a `WHAT:` comment appears only in a file's leading header
block. Assumptions: Markdown is deliberately governed for label form but **not** for
the second check, because the aligned WHAT-and-WHY pair *is* correct here inside a
fenced command block — a shell pipeline has no docstring construct to carry its
purpose, which is why every command on this page has one.

```bash
# WHAT: run the repository-wide Rule 1 lexical gate, then the Java documentation
#       gate for this module.
# WHY : Assumptions: the lexical gate resolves its own repository root from its own
#       location and takes no path argument, so it cannot be pointed at a subtree by
#       accident -- a partial scan reporting a pass is the one failure mode worth
#       preventing in a gate. It imports nothing outside the standard library, so it
#       needs no virtual environment.
python3 config/rule1/rule1_gate.py
mvn -B -f services/transaction-service/pom.xml validate
```

### 12.7 The suppressions charter

[`config/checkstyle/suppressions.xml`](../../config/checkstyle/suppressions.xml) is
the only place an exemption from the documentation gate may be recorded, and it is
chartered for *"generated-source and test-fixture suppressions."* Those six words are
the whole scope. It holds exactly **two** entries, one per sanctioned surface:

| Permitted path | Entry |
|---|---|
| `services/*/target/generated-sources/**` | there is no author to ask for a docstring |
| `services/*/src/test/resources/fixtures/**` | fixture material is data at a fixed record length, not authored logic |

**Suppressing anything under `services/*/src/main/java/**` is absolutely
prohibited**, and `mapper/**` above all: the mapper package is exactly where the
non-obvious decisions concentrate — dropping `FILLER`, masking the card number to
its last four digits, carrying money as a string — so an exemption there would
remove the documentation from the code that most needs it.

Two mechanical facts a contributor will otherwise get wrong:

- **The `suppress` element has no `reason` attribute.** Its DTD admits `files`,
  `checks`, `message`, `id`, `lines` and `columns`, and nothing else. Writing a
  `reason` onto the element makes the document invalid against the DTD, and because
  the filter fails closed that stops the reactor before a single class is compiled.
  Worse than the breakage is the near miss: an entry that looks documented while the
  attribute carrying its reason is silently unusable. The justification therefore
  goes in an XML comment immediately above the element.
- **`files` is a regex over the path, not a glob.** Write `[\\/]` for the separator
  rather than `/`, and a bare `*` will not mean what a shell reader expects.

Each entry must also narrow itself with a `checks` attribute naming only checks the
ruleset actually configures — there are exactly ten — because a suppression for an
unconfigured check never fires, so nothing reveals that it is dead while the entry
still reads to the next maintainer as evidence that the check is active and had to be
relaxed.

---

## 13. Decision ledger

Every non-obvious decision in this module, each tagged with the Rule 1 category that
justifies it. The labels are quoted in their one permitted written form.

| Decision | Category | Substance |
|---|---|---|
| Money transported as a JSON string | `Alternatives Considered:` | A JSON number is parsed into an IEEE-754 double by most clients, which cannot represent every two-decimal value, destroying exactness at the client boundary the user reads. A JSON number was the alternative and is rejected for that reason. |
| Keyset pagination on the list **and** the bill-pay browse | `Alternatives Considered:` | Offset pagination skips and repeats rows under concurrent inserts, because the rows before the offset shift while the reader pages. That changes observable behaviour, which browse-by-key does not. `Pageable` is refused at the repository for the same reason: it emits a SQL `OFFSET`. |
| The `size + 1` fetch to discover `hasNext` | `Assumptions:` | This is precisely how the COBOL discovers it. `COTRN00C` reads forward from `STARTBR` at L281 with `READNEXT` at L286 and learns a further page exists by finding one more record than fits the screen. |
| The backward query ordered descending rather than reversed after the fact | `Assumptions:` | `READPREV` at `COTRN00C` L340 is a genuine backward read, not a forward read reversed, so descending ordering with a strictly-less-than predicate is the faithful shape. |
| `idx_transactions_proc_ts` replacing the batch alternate index while `BLDINDEX` retires | `Refactoring Rationale:` | PostgreSQL maintains indexes transactionally, so the separate build pass that `app/jcl/TRANIDX.jcl` L52 runs as `IDCAMS BLDINDEX` is not a job step in the target. The **key** survives, corroborated by `app/jcl/TRANREPT.jcl` L41 to L42 at one-based 263 and 305; the step does not. |
| `idx_transactions_proc_ts` left non-unique | `Assumptions:` | `app/jcl/TRANIDX.jcl` L28 declares `NONUNIQUEKEY`, so two transactions may share a processing instant. A unique index would refuse rows the baseline accepts. |
| `transaction_rejects` retaining `raw_record CHAR(350)` undecomposed | `Trade-offs:` | A rejected record must be retained verbatim for re-drive, and decomposition would lose the very bytes that caused the reject — a record rejected for an unresolvable card number is one whose card number a column-per-field table cannot store. The cost accepted is that queries cannot reach inside a rejected record. |
| The category-balance write keeping both branches distinguishable | `Assumptions:` | `app/cbl/CBTRN02C.cbl` L467 to L542 branches on whether the row exists: `2700-A-CREATE-TCATBAL-REC` at L503 or `2700-B-UPDATE-TCATBAL-REC` at L526, selected at L496 and L498. Which path ran is observable behaviour and is separately tested, so an opaque single-statement merge that hides the distinction is not acceptable here. |
| The cross-schema `GRANT` rather than a saga | `Alternatives Considered:` | A transactional-outbox-plus-compensating-reversal design was evaluated and rejected: it would introduce observable partial-posting states that do not exist in the baseline and would break golden-master parity outright. The named grant keeps the posting unit of work one ACID commit. |
| `TIMESTAMP(6)` and the fixed 26-character format | `Assumptions:` | Microsecond precision matches `PIC X(26)` exactly — six fractional digits is what those 26 characters leave room for — so the column neither truncates a value the baseline expresses nor admits one it cannot. |
| `CHAR(16)` in the database with digits-only string DTOs for the card number | `Assumptions:` | Fixed width is part of the contract, a numeric type loses digits through a double at sixteen digits, and the live seed data contains a card number with a leading zero that any numeric type drops. The baseline agrees: `app/cpy/CVCRD01Y.cpy` L37 to L39 declares `CC-CARD-NUM PIC X(16)` with a separate numeric `REDEFINES`. |
| Card-number masking applied at the mapper site | `Trade-offs:` | Narrowing data exposure is chosen over unrestricted detail access, and it is placed in the mapper so no response path can bypass it. The cost is that an operator needing a full number must use the administrative surface of the card context rather than this one. |
| `NUMERIC(11,2)` rather than a wider money column | `Assumptions:` | `TRAN-AMT` and `TRAN-CAT-BAL` are both `PIC S9(09)V99`, so precision 11 at scale 2 is exact. Widening would admit a ten-digit amount the 350-byte record cannot represent, so a value stored here would not round-trip through the baseline. |
| `daily_transactions.proc_ts` nullable while `transactions.proc_ts` is `NOT NULL` | `Assumptions:` | The pre-posting feed carries the processing timestamp as 26 spaces because nothing has processed the row yet, and the ETL maps that to null. Asserting `NOT NULL` on the feed would refuse every row the baseline produces. |
| A surrogate identity key on the feed and reject tables, a composite key on category balances | `Assumptions:` | The feed and the reject stream are append-only with no natural key, while `CVTRA01Y.cpy` L5 to L8 gives the category balance a real three-part `TRAN-CAT-KEY`. A surrogate there would admit two rows the VSAM key structurally cannot. |
| `SET search_path TO ledger` as a connection-initialisation statement | `Alternatives Considered:` | A `currentSchema` parameter on the JDBC URL was rejected because the URL arrives from Parameter Store, so a schema smuggled into it would be invisible in this module's own configuration. |
| A sequence for identifier allocation, added by `V2` | `Refactoring Rationale:` | Read-the-maximum-then-add-one was correct while one CICS region serialised the two transactions using it. Behind a load balancer two tasks read the same maximum, derived the same key, and one caller received a constraint violation surfaced as an internal error. The maximum-key read survives for the copy path only, which needs the last stored record rather than the next free key. |
| Both Flyway artifacts declared | `Assumptions:` | Flyway 10 and later moved PostgreSQL support out of the core artifact, so core alone resolves and compiles and then fails at run time with no database implementation for the URL. `flyway-database-postgresql` is the required companion. |
| Absence of Lombok and MapStruct | `Alternatives Considered:` | Lombok's generated accessors cannot carry the required Javadoc, so it would breach the very gate that enforces the rule; MapStruct's newest release is a beta and the copybook-to-DTO mapping is not mechanical, so each mapping decision needs an inline justification a generated mapper cannot hold. Java 21 records with explicit constructors give the brevity without either cost. |
| No resilience library and no circuit breaker | `Alternatives Considered:` | Spring Framework 7 core already carries retry, with `maxRetries` and `@EnableResilientMethods`, so an external library would be a second mechanism for one need. A breaker is omitted because the only synchronous hop is in-VPC behind an internal load balancer with bounded timeouts, so it would add a failure mode without removing one. |
| The Corretto runtime tag `21.0.12-al2023-headless` | `Assumptions:` | No Alpine variant of that image exists — the publisher ships only `-al2` and `-al2023` tags — so `21-alpine` is not a smaller alternative but a tag that does not resolve and fails every build. 21.0.12 is the highest published 21.x, and headless is chosen because an HTTP service needs no graphics stack. |
| The repository root as the container build context | `Alternatives Considered:` | A module-scoped context was rejected three times over: it cannot reach the unpublished `common-lib` sibling, it does not contain the parent POM this module declares at `../pom.xml`, and it cannot reach `config/checkstyle`. It produces no image rather than a smaller one. |
| A real PostgreSQL for the integration tier | `Trade-offs:` | A container runtime is required and the tier is slower, which is accepted because an in-memory database reproduces neither PostgreSQL's index semantics nor its `CHAR` padding, so a green in-memory run would prove nothing about the three access paths this schema exists to provide. |
| Report directories left at their defaults | `Assumptions:` | The continuous-integration workflow collects from `services/*/target/surefire-reports/` and `services/*/target/failsafe-reports/`. Relocating either makes the build green while the workflow publishes nothing. |
| Plain ASCII hyphens throughout this file | `Assumptions:` | `tests/README.md` mixes U+2011 non-breaking hyphens with plain ASCII. Plain ASCII is greppable and diff-stable, whereas a U+2011 silently defeats a `grep -F` on a hyphenated term, so the house mixture is deliberately not reproduced here. |
| Rationale labels written unemphasised or in backticks, never bold | `Assumptions:` | `config/rule1/rule1_gate.py` governs `.md` for label form and rejects an emphasis-wrapped label; only the two files that quote Rule 1's own typography are allowlisted, and this one is not. Backtick spans are masked by the gate, which is why the tables above quote the labels that way. |

---

<sub>Apache-2.0. The COBOL baseline under `app/**`, the parity suite under `tests/**`
and its runners under `scripts/**` are reference-only and are never modified by this
module. See [`MIGRATION_README.md`](../../MIGRATION_README.md) for the migration as a
whole, [`docs/architecture/service-catalog.md`](../../docs/architecture/service-catalog.md)
for this module's place in it, and the root [`README.md`](../../README.md) for the
mainframe application overview.</sub>
