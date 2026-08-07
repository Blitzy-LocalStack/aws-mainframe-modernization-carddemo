# reject_101_acct_missing -- the card resolves, the account does not exist

The ledger-side fixture for the second reject in the posting validation order: a
transaction whose card number **does** resolve in the card cross-reference, but whose
resolved account is **absent** from the account master, so the record is rejected as
`ACCOUNT RECORD NOT FOUND` under reason code **101**.

This document is also the folder's mandatory Explainability carrier. The three sibling
records are positional data files with no comment construct, so it carries the
documentation obligation for all four files here. Rule 1 (Explainability) is what forces
it into scope; the rule's full text is available through `review_rules`.

> **The byte contract is not restated here.** The encoding rules -- positional fields,
> line endings, the trailing newline, sign overpunch and the implied decimal -- live in
> [`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) sections
> 3.1 through 3.5, and the decisions that apply to all ten scenarios live in the folder
> index at [`../README.md`](../README.md). Both are cited by section and neither is
> reproduced. Re-narrating a contract another document owns is the pattern Rule 1 forbids
> as restating what the code already shows.

---

## 1. Business rule exercised

`CBTRN02C` reject 101 from `1500-B-LOOKUP-ACCT`, in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl). Every line number
below was read in that file before being written down.

### 1.1 Two gates, short-circuited

`1500-VALIDATE-TRAN` at line 370 runs the cross-reference lookup at line 371, then runs
the account lookup at line 373 **only if** the reason is still zero, tested at line 372.
The gates short-circuit, and that ordering is this scenario's design constraint rather
than an incidental detail of the program.

Gate A is `1500-A-LOOKUP-XREF` at line 380. It moves the record's card number to the file
key at line 382 and reads the cross-reference at line 383. On `INVALID KEY` it moves
**100** at line 385 and the literal `INVALID CARD NUMBER FOUND` at line 386, after which
`1500-VALIDATE-TRAN` takes its `ELSE` and gate B never runs.

Gate B is `1500-B-LOOKUP-ACCT` at line 393, and it is the gate this scenario exercises:

| Line | What it does |
|---|---|
| 394 | moves `XREF-ACCT-ID` -- the account identifier gate A's read populated -- into the account file key |
| 395 | reads the account file |
| 396 | opens the `INVALID KEY` arm |
| 397 | moves **101** into the reason |
| 398 | carries the literal `ACCOUNT RECORD NOT FOUND` |
| 399 | moves that literal into the description field |

The consequence has to be stated plainly, because it is what keeps this scenario
distinct: **reason 101 is reachable only when gate A has already succeeded.** The card
must be one that **does** resolve to a cross-reference row whose account identifier then
has **no** account record. A card with no cross-reference row at all surfaces reason
**100** instead, and this scenario silently becomes a duplicate of
`reject_100_card_missing`. Section 10 records how that failure mode governed the card
this folder actually carries.

Reason 101 also **pre-empts** 102 and 103. The credit-limit gate at line 407 and the
expiration gate at line 414 sit on the `NOT INVALID KEY` leg of the same line-395 read,
so neither is evaluated on this path. Nothing in this folder can express an over-limit or
an expiry outcome, and nothing here should be read as attempting to.

### 1.2 What the driver does with a non-zero reason

The driver resets the reason at line 208 and the description at line 209, validates at
line 210, and posts only when the reason is still zero at line 211. Otherwise it
increments the reject count at line 214 and writes the reject at line 215. The reject arm
and the posting arm are exclusive, so the three writes inside `2000-POST-TRANSACTION` --
the category balance at line 440, the account at line 441 and the transaction at line 442
-- do not occur on this path. Section 6 turns that into assertions.

---

## 2. The message collision with reason 109

This is the most important thing this document records.

Line 398 carries `ACCOUNT RECORD NOT FOUND` for reason **101**. Line 557 carries **the
same literal, byte for byte**, for reason **109**. A literal search of the program finds
that string at exactly those two lines and nowhere else. The two differ only in the
numeric code and in where they fire:

| | Reason 101 | Reason 109 |
|---|---|---|
| Code moved at | line 397 | line 556 |
| Message moved at | line 398 | line 557 |
| Triggered by | a `READ` at line 395 | a `REWRITE ... INVALID KEY` at lines 554-555 |
| Paragraph | `1500-B-LOOKUP-ACCT`, line 393 | `2800-UPDATE-ACCOUNT-REC`, line 545 |
| Position in the run | during validation | after validation has already returned zero |

**A test asserting only on `reason_desc` cannot distinguish 101 from 109. Only
`reason_code` separates them.** An assertion for this scenario must check the code; one
that checks the description alone passes against the wrong scenario and reports success.

The other half of the pair is the sibling folder `reject_109_rewrite_invalid_key`.
**Neither folder is redundant** -- they exercise different code paths that happen to
share a message. A reader who assumes the message identifies the reason will conclude
that one of the two is duplicated, and that is precisely the misreading this section
exists to prevent.

The duplicated literal is the baseline's own text and is cited as it stands. It is carried
across character for character under the verbatim-message contract, so disambiguating by
altering the wording is not available: the message **is** the contract.

---

## 3. What this module owns: the reject output contract

The account read at line 395 touches `account.accounts`, which belongs to a **different
bounded context**, owned by account-service. **This module does not re-run that read.**
What it owns is the reject **output**.

### 3.1 The three columns

`ledger.transaction_rejects`, created by
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql):

| Column | Declaration | Expected value |
|---|---|---|
| `reason_code` | `SMALLINT`, line 619 | `101` |
| `reason_desc` | `VARCHAR(76)`, line 631 | `ACCOUNT RECORD NOT FOUND` -- verbatim, no case change, no trailing period |
| `raw_record` | `CHAR(350)`, line 605 | the sibling `dailytran.txt` record's 350 bytes, undecomposed and byte for byte, including the 26 spaces at 305-330 |

### 3.2 The 430-byte lineage

`2500-WRITE-REJECT-REC` at line 446 moves the whole daily record into the reject area at
line 447, moves the trailer at line 448, and writes at line 451. The reject record is 430
bytes: `REJECT-TRAN-DATA PIC X(350)` followed by `VALIDATION-TRAILER PIC X(80)` at lines
176-178. The trailer's first two members are a `9(04)` reason and an `X(76)` description
at lines 180-182, which is where the `SMALLINT` and the `VARCHAR(76)` above come from.
[`app/jcl/POSTTRAN.jcl`](../../../../../../../app/jcl/POSTTRAN.jcl) line 36 corroborates
the total independently, declaring the reject dataset `LRECL=430`.

Because the target keeps `raw_record` undecomposed, the fixture bytes and the expected
column value are the same 350 bytes. No decode step sits between them, so an assertion
compares them directly and a field-level disagreement cannot hide inside a conversion.

### 3.3 What this folder deliberately does not ship

`acctdata.txt` and `cardxref.txt` are **absent by design**. `accounts` and `card_xref`
both live in the `account` schema, so staging either here would put one table's ownership
in two modules. The absent-account condition is therefore **described** in this document
and never staged as data; section 7 records who establishes it instead.

---

## 4. Fixture files in this folder

| File | Copybook | RECLN | Records | Line ending | Role |
|---|---|---|---|---|---|
| `dailytran.txt` | `CVTRA06Y` | 350 | 1 | LF | the transaction to reject |
| `transact.txt` | `CVTRA05Y` | 350 | 1 | LF | prior ledger state; no row for the rejected transaction |
| `tcatbal.txt` | `CVTRA01Y` | 50 | 1 | LF | prior balance that must remain unchanged |

On disk the three occupy **351, 351 and 51 bytes** -- the record width plus one trailing
newline in each case. The layout tables are master sections 5.1, 5.5 and 5.6
respectively, and the copybooks themselves are
[`CVTRA06Y.cpy`](../../../../../../../app/cpy/CVTRA06Y.cpy),
[`CVTRA05Y.cpy`](../../../../../../../app/cpy/CVTRA05Y.cpy) and
[`CVTRA01Y.cpy`](../../../../../../../app/cpy/CVTRA01Y.cpy).

Folder section 3.1 is the authority for this folder shipping neither an account image
nor a cross-reference image even though the rule under test reads both: they belong to
the `account` schema, owned by another module.

### 4.2 Provenance attestation

Folder section 7 mandates the three items below.

1. **Synthetic and seed-derived.** `dailytran.txt` is byte-identical to record 1 of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt),
   measured rather than asserted, with no field reshaped. `transact.txt` derives from
   **record 2** of that same seed file -- positions 1-304 are that row verbatim,
   measured -- and section 4.3 records why it derives from a different seed row than
   `dailytran.txt` does. `tcatbal.txt` is byte-identical to its `happy_path`
   counterpart -- also measured -- whose provenance is recorded in
   [`../happy_path/README.md`](../happy_path/README.md) section 4.2.
2. **No real person and no real account.** Every account number, card number, name and
   address byte here comes from the published CardDemo demonstration seed. They are
   demonstration values, not credentials, and they identify no real person and no real
   account. Every identity byte of all three files is a published seed byte; the only
   bytes that are not are the generated processing timestamp and the two `tcatbal.txt`
   fields item 3 names, none of which carries identity.
3. **Which bytes were reshaped away from the seed value.** In `dailytran.txt`, none --
   the record is the seed row unchanged, and `cmp` against `../happy_path/dailytran.txt`
   reports the two as identical, measured. In `transact.txt`, the processing timestamp
   at positions 305-330 only: seed record 2 carries 26 spaces there and this file
   carries the literal `2022-07-18 00:00:00.000000`, the same instant `happy_path`
   pins. In `tcatbal.txt`, the account identifier and balance, exactly as `happy_path`
   item 3 records for that file. This scenario's discriminator is not a byte of any of
   the three, so nothing here is attested as reshaped for its sake; section 5 records
   where the discriminator lives instead.

### 4.3 Why `transact.txt` holds prior ledger state rather than a posted outcome

Assumptions: `transact.txt` loads `ledger.transactions`, the **posted**-transaction
table, and section 3 states this scenario's expected outcome as **no posted
transaction**. Those two facts together decide what the row may contain. In
`happy_path` the row is the posted image of that scenario's daily record, because there
the record posts. Here the record is rejected, so a posted image of it would assert the
opposite of the scenario -- and the objection is structural rather than rhetorical:
`transaction_id` is the primary key of that table at
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) line 255, so
a pre-loaded row keyed `0000000000683580` would be indistinguishable from one an insert
had added, and an insert that did occur would fail on the key rather than on the rule
under test. The row is therefore a **different** transaction, one already on the ledger
before the run: seed record 2, identifier `0000000001774260`. Measured, no byte of this
file carries `0000000000683580`.

What that buys is an assertable statement, which an empty table cannot give. A table
asserted to be unchanged has to have contents to be unchanged, so the reject is
checkable twice over: the row count is one before and one after, and the rejected
identifier is absent at both points.

Alternatives Considered: shipping no `transact.txt`, or an empty one. Both were
rejected. An absent file breaks the three-file composition folder section 3.1 fixes as
this folder's shape, and an empty table makes "unchanged" vacuous -- a query returning
nothing proves nothing about whether a write was suppressed, because it returns nothing
either way.

Trade-offs: this `transact.txt` is not byte-identical to `happy_path`'s, and it is not
the only one. Measured, four of the ten scenarios ship a divergent `transact.txt`:
`boundary_expiry_equal`, which moves the two timestamp fields; this folder, which
withholds the account being posted to; `reject_100_card_missing`, which withholds the
transaction its own card lookup rejects and therefore carries this same seed row 2; and
`reject_109_rewrite_invalid_key`, which carries the identity bytes of seed line 114. An
author comparing the ten with `cmp` therefore meets a difference in four places rather
than one, and each is accounted for in that scenario's own section rather than here. The cost is accepted because the
alternative is a fixture whose bytes contradict the outcome its own section 3 states,
and this section is where this folder's difference is accounted for.

Assumptions: the card at positions 263-278 is `0927987108636232` because it arrives
with seed record 2, not because it was selected, and it is **not** a reintroduction of
the card section 5 records as withdrawn. That withdrawal governs `dailytran.txt`, whose
card `CBTRN02C` resolves through the cross-reference to choose a reason code; nothing
resolves the card on a row that is already posted, so the concern does not reach this
file. `dailytran.txt` here carries `4859452612877065`, measured, and section 5 stands
unqualified.

The processing timestamp is populated here and blank in `dailytran.txt` for a reason
that is a schema constraint rather than a house convention: `proc_ts` is `NOT NULL` on
this table at line 245 of the same migration and nullable on
`ledger.daily_transactions`, so 26 spaces are the correct bytes there and would be
rejected at insert here. Folder section 3.2 carries that reasoning in full and is the
authority for it; item 3 above records the positions it moves.

---

## 5. Determinism and encoding notes

- **The two processing timestamps are treated differently, and the asymmetry is a schema
  constraint rather than a house preference.** `dailytran.txt` carries 26 spaces at
  305-330; `transact.txt` carries the literal `2022-07-18 00:00:00.000000`.
  `ledger.transactions.proc_ts` is `NOT NULL` at line 247 of the migration, while
  `ledger.daily_transactions.proc_ts` is nullable at line 463 -- so blank bytes are
  correct on the daily record and would be refused on the posted one. The baseline does
  not copy the value across: it **generates** it at posting time, performing
  `Z-GET-DB2-FORMAT-TIMESTAMP` at line 437 and moving the result at line 438.
- **That date is the repository's own injected business date**, `2022-07-18`, taken from
  [`app/jcl/INTCALC.jcl`](../../../../../../../app/jcl/INTCALC.jcl) line 22, which passes
  `PARM='2022071800'`. It is never a clock read, and that is what keeps an assertion on a
  26-character value reproducible from one run to the next.
- **`ORIG-TS` is the seed literal `2022-06-10 19:27:53.000000`** in both records,
  unchanged from the published seed.
- **`tcatbal.txt` bytes 29-50 are 22 ASCII zeros, not spaces**, per master section 5.6.
  That section is the authority for the convention, and its table is not reproduced here.
- **The `tcatbal` seed ships CRLF-terminated.** This folder's copy is authored LF-only
  with the carriage return stripped, which keeps the record at its declared width of 50
  rather than 51. Master section 3.2 governs line endings and folder Decision C governs
  why LF is the only ending in this tree.
- **Money is exact fixed-point decimal.** These amounts are zoned-decimal character fields
  carrying their sign as an overpunch in the low-order byte, per master section 3.4:
  `0000005047G` on the daily record decodes to `+504.77`, and `0000009190}` on the posted
  record to `-919.00`. Decoding either with the default sign convention yields a positive
  value with a letter-shaped byte instead of a signed amount, which is a silent error
  rather than a loud one.

---

## 6. Expected outcome

- **Exactly one row in `ledger.transaction_rejects`**, carrying `reason_code` `101`,
  `reason_desc` `ACCOUNT RECORD NOT FOUND`, and `raw_record` equal to the `dailytran.txt`
  record's 350 bytes.
- **Zero new rows in `ledger.transactions`.** The file's single prior row remains the only
  row and still carries `TRAN-ID` `0000000001774260`. Measured, no byte of `transact.txt`
  carries the rejected identifier `0000000000683580`, so a suppressed insert and an
  applied one are distinguishable at the key rather than by inference.
- **The `tcatbal.txt` row unchanged at `+100.00`.** That is assertable because reason 101
  fires during validation, so control never reaches `2700-UPDATE-TCATBAL`: neither the
  create arm at line 503 nor the update arm at line 526 executes. An accidental mutation
  would land at **604.77** -- the prior `+100.00` plus the record's `+504.77` -- which is
  exactly why a non-zero starting balance was chosen. Against a zero start, a suppressed
  write and an applied write of a matching amount are not distinguishable by the resulting
  value alone.

As **baseline** behaviour, a non-zero reject count moves 4 into `RETURN-CODE` at lines
229-230. That is recorded as a property of the program being migrated and is not this
scenario's expectation. The target expresses the reject as a **row** in
`ledger.transaction_rejects`, so every assertion above is on rows, and this tree carries
no condition-code rubric of its own.

---

## 7. What this fixture does not establish, and why

Assumptions: **account `00000000007` exists in the published seed.** It is row 7 of
[`app/data/ASCII/acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt),
measured, and it is the account the seed cross-reference resolves this record's card to.
The precondition this scenario is named for is therefore established neither by these
three files nor by the published seed. It is established by the account state the
consuming test seeds: a tier that **omits** that account.

The consequence is concrete, and after section 2 it is the most useful thing here: **a
test that seeds all fifty published accounts verbatim will see this record post, and the
101 assertion will fail.** That failure looks like a fixture defect and is not one.
Whoever authors that test has to seed the account tier **without** account `00000000007`,
and has to seed the cross-reference tier **with** the row for this card -- otherwise the
record expresses reason 100, for the gate-ordering reason in section 1.1.

The reference tree reaches the same reason from the other direction, and its shape is
worth reading. Its scenario directory `tests/fixtures/posting/reject_101_acct_missing/`
ships five files, and measured, its `acctdata.txt` holds a single row for account
`00000000020` while its `cardxref.txt` holds a single row mapping this card to account
`00000000007` -- the arrangement its own
[`README.md`](../../../../../../../tests/fixtures/posting/reject_101_acct_missing/README.md)
documents. The card resolves; the account it names is not in that tier; reason 101
follows. That construction is unavailable in this module because both images belong to
another bounded context, per section 3.3.

---

## 8. Sources and scope

Every path below is read as specification and **never modified**. The baseline is the
behavioural oracle for this migration, so it is cited, not edited.

| Source | Read for |
|---|---|
| [`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl) | the validation order, both reason literals, the reject record layout, the write paragraph |
| [`app/cpy/CVTRA06Y.cpy`](../../../../../../../app/cpy/CVTRA06Y.cpy), [`CVTRA05Y.cpy`](../../../../../../../app/cpy/CVTRA05Y.cpy), [`CVTRA01Y.cpy`](../../../../../../../app/cpy/CVTRA01Y.cpy) | the three record layouts |
| [`app/jcl/POSTTRAN.jcl`](../../../../../../../app/jcl/POSTTRAN.jcl) | the 430-byte reject dataset declaration, line 36 |
| [`app/jcl/INTCALC.jcl`](../../../../../../../app/jcl/INTCALC.jcl) | the injected business date, line 22 |
| [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt), [`cardxref.txt`](../../../../../../../app/data/ASCII/cardxref.txt), [`tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt), [`acctdata.txt`](../../../../../../../app/data/ASCII/acctdata.txt) | the seed rows these records derive from |
| [`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) | the byte contract and the mandate for this document, cited by section |

Availability, using the `[present]` and `[planned]` convention of master section 9.3:

- [`TransactionFixtureContractTest`](../../../java/com/carddemo/transaction/fixtures/TransactionFixtureContractTest.java)
  -- **[present]**, and it **does** read this folder. It asserts that all three files are
  whole records carrying no carriage return, that this scenario's `dailytran.txt` holds the
  one seed card the tree uses, that the category-balance row composes the shared key, and
  -- reading this folder's `transact.txt` specifically -- that a negative amount decodes
  through its sign overpunch to `-919.00` at scale 2. This folder is the tree's only
  carrier of a negative money value, which is why that assertion reads from here.
- A **fixture-loading** integration test -- one that loads these three records into the
  `ledger` tables and asserts the reject row of section 6 -- is **[planned]**. Assumptions:
  the reject row, the `NOT NULL` processing stamp and the key-ordered read paths are
  properties of the real engine, and this module's integration tests run against a
  PostgreSQL container, so an in-memory substitute would prove nothing about the behaviour
  claimed here. Where these records feed a list path, that path pages by **key** and not
  by ordinal position, so record order matters to any page boundary such a test asserts.
- [`../../application-test.yml`](../../application-test.yml) -- **[present]** sibling. It pins
  schema resolution to `ledger`. It does **not** pin the clock: its own exclusion list rules
  out any clock or current-time property, and the pinned instant is
  `TransactionRepositoryIT.FIXED_CLOCK` at that class's lines 259 and 260,
  `Clock.fixed(Instant.parse("2022-07-18T00:00:00Z"), ZoneOffset.UTC)`. Without a pinned
  instant no assertion on a 26-character timestamp is reproducible.
- [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) --
  **[present]**. It creates the four `ledger` tables these records load into, including
  `ledger.transaction_rejects` at line 542.

Refactoring Rationale: the first bullet is phrased around **whether anything reads these
bytes**, not around a class count, and it names a present consumer where the folder index
still describes the tree as having none. The index's bullet is accurate for the state it
was measured in and its own rationale records that it has been re-derived more than once;
the statement above is a fresh measurement against this branch. Phrasing it around the
consumer relationship is what stops another test class being added from falsifying the
sentence, because only a change in what reads this folder can.

---

## 9. Data governance and synthetic provenance

Master section 10.3 mandates three items for any scenario folder carrying PAN or
identity-shaped data. This folder carries both, so all three apply.

1. **The PAN and identity bytes are synthetic and seed-derived.** `dailytran.txt` is
   byte-identical to record 1 of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt),
   measured, and carries card `4859452612877065` -- row 21 of
   [`app/data/ASCII/cardxref.txt`](../../../../../../../app/data/ASCII/cardxref.txt),
   which maps it to customer `000000007` and account `00000000007`. `transact.txt`
   positions 1-304 are record 2 of that same seed file verbatim, measured, carrying card
   `0927987108636232` -- row 4 of the cross-reference, whose bytes are
   `092798710863623200000002000000000020`. `tcatbal.txt` carries account `00000000007`,
   the key of row 7 of
   [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt). All of
   it comes from the published AWS CardDemo sample datasets, which ship as fabricated
   demonstration data.
2. **They represent no real person, no real account and no real payment instrument.**
   Every identity byte in all three files is a published demonstration byte, and none of
   them is a credential.
3. **Fields reshaped away from the seed value**, so the provenance of the changed bytes is
   explicit too:
   - `dailytran.txt` -- **none.** The record is the seed row unchanged, and a byte
     comparison against [`../happy_path/dailytran.txt`](../happy_path/dailytran.txt)
     reports the two identical, measured. Section 10 records why no card substitution was
     made, and this scenario's discriminator is therefore not a byte of this file.
   - `transact.txt` -- the processing timestamp at 305-330 only. Seed record 2 carries 26
     spaces there; this file carries the injected business date, for the `NOT NULL` reason
     in section 5.
   - `tcatbal.txt` -- the balance at 18-28, `+100.00` here against the seed row's `+0.00`,
     and the stripped carriage return. The composed key is the seed row's, unchanged.

---

## 10. Design rationale

Rule 1 (Explainability) requires every non-obvious choice to name a reasonable alternative
and a concrete consequence. The four category labels below are written in the single form
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
permits at its lines 209-212 -- plural, unparenthesised, colon retained and carrying no
emphasis markup -- because, as its lines 228-234 record, the label is read by a literal
search before it is read by a person, and a second spelling makes that search silently
partial.

Assumptions: **the card must resolve in the cross-reference for gate B to be reached at
all.** Reason 101 sits behind gate A, so without that precondition the scenario does not
merely lose coverage -- it degenerates into reason 100 and duplicates
`reject_100_card_missing` while still being named for 101. In this module neither the
cross-reference nor the account master is staged, per section 3.3, so **both** halves of
the precondition -- the card resolving and the account being absent -- are declared
preconditions on the consuming test rather than staged data. Section 7 states what a test
has to seed on each side, and the failure mode if it seeds the account tier verbatim.

Trade-offs: **keeping the description literal byte-identical to reason 109's costs
discriminating power on `reason_desc`.** The cost is concrete: a test asserting only on
the description cannot tell 101 from 109, so it can pass against
`reject_109_rewrite_invalid_key` while reporting that it verified this scenario. The cost
is **accepted**, because the verbatim-message contract outranks test convenience -- the
message text is a user-visible string carried across unchanged, and rewording it to make
assertions easier would substitute a fixture's convenience for the behaviour under test.
Section 2 states the mitigation, which is to assert the code.

Alternatives Considered: **describing the absent account row rather than staging an
`acctdata.txt` alongside these three files.** Staging one would make the precondition
self-contained and would remove the trap section 7 describes, which is a real benefit. It
was rejected because `accounts` belongs to the `account` schema, owned by another bounded
context: a fixture here holding account records would put one table's ownership in two
modules, and the next change to that table would have two places to land instead of one.
The same objection applies to a cross-reference image, which is why the resolvable half of
the precondition is expressed through a seed card rather than through a staged row.

Alternatives Considered: **the card number carried at positions 263-278.** The alternative
was `0927987108636232`, which reads like the natural choice -- it is a published seed value
that resolves at row 4 of the cross-reference, and it carries a leading zero, which is
independently useful evidence that a card number has to round-trip as a digits-only string
in a `CHAR(16)` column rather than through a numeric type that would drop that digit. It
was carried here and has been withdrawn, and the measured reason is the gate ordering:
`0927987108636232` resolves only if a consuming test seeds row 4 of the cross-reference
specifically, whereas the minimal cross-reference tier the resolvable-card scenarios ship
maps `4859452612877065` alone and holds `0927987108636232` only as the unresolvable decoy
of `reject_100_card_missing`. Under that tier the card resolves to nothing, and because
reason 100 is checked at lines 385-387 **before** reason 101 at lines 397-399, the record
would express its predecessor while still being named for 101. The template card
`4859452612877065` avoids the dependency outright, since it is the card every
resolvable-card scenario maps, leaving the account-tier omission as the single condition a
test must establish. Trade-offs: byte identity with `happy_path` costs this scenario any
visible marker of its own, so its identity rests on its folder name and on the account
state a test supplies, and it costs this folder the leading-zero round-trip the withdrawn
card happened to carry. Both are accepted -- a fixture whose bytes contradict its name is
worse than one with no marker, and the round-trip property is better served by a fixture
authored and named for that purpose. The folder index carries this same adjudication, and
this entry is deliberately consistent with it rather than a second opinion on it.

Assumptions: **the two processing-timestamp treatments are two shapes, not one.** The
alternative was a single shared record shape, populated identically in both files, which
would make the pair diffable in one step. It fails on a schema constraint rather than on
taste: `ledger.transactions.proc_ts` is `NOT NULL` at line 247 of the migration while
`ledger.daily_transactions.proc_ts` is nullable at line 463, so 26 spaces are the correct
bytes on the daily record and would be refused on the posted one. The baseline itself
draws the same distinction, generating the stamp at lines 437-438 rather than copying it,
so a shared shape would also have asserted a lineage the program does not have.
