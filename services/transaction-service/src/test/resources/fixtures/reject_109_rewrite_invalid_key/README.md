# reject_109_rewrite_invalid_key -- a reject raised after validation has already passed

Scenario fixtures for posting reason **109**, the reject `CBTRN02C` raises when the
account rewrite fails with an invalid key. Reasons 100 through 103 are refusals decided
during validation. Reason 109 is not: it is a write outcome, reachable only once
validation has returned a reason of zero and posting has begun. That single difference in
*when* the reason is set is the whole content of this scenario, and it is what makes this
folder the one place in the tree that can witness the posting unit of work being
all-or-nothing.

> **This document is the mandated Explainability carrier for the three record files
> beside it.** `dailytran.txt`, `transact.txt` and `tcatbal.txt` are fixed-width
> positional records in which every byte is data; they have no comment construct, and
> master section 3.1 records that the loaders reject any physical row whose length is not
> exactly `RECLN` rather than padding it, so a comment line in one of them is a hard load
> failure rather than an annotation. The Explainability obligation for all three is
> therefore discharged here. Rule 1 is cited by name and not reproduced -- read it through
> `review_rules`, with the specification anchors at sections 0.8 and 0.8.1 for the rule
> itself and 0.2.1.6 for the artifacts it forces into scope.
>
> Two documents govern this one and neither is restated here. The authoritative byte
> contract is
> [`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md), cited by
> section. The folder-level conventions -- the record layouts, the three folder decisions,
> the label form and the ASCII rule -- are the folder index at
> [`../README.md`](../README.md), also cited by section. Where this document and either of
> those disagree, they are right and this is a defect.

---

## 1. Scenario intent

One daily transaction that passes every validation gate, is taken down the posting path,
and fails on the account rewrite. The reject that results carries reason code **109** and
the message `ACCOUNT RECORD NOT FOUND`.

This scenario has **no counterpart in the reference tree, no mirrored fixture and no
expected-output master**, and that is stated plainly rather than glossed. Measured:
`tests/fixtures/posting/` holds scenarios for 100, 101, 102 and 103 and stops; the reject
table in `tests/README.md` begins at its line 561 and its four rows at lines 563 to 566
list those same four reasons; and a word-boundary search for `109` across both
`tests/README.md` and the master fixture contract returns nothing. Folder index section
1.2 is the authority for the same finding. The fixture here is therefore reasoned from the
record layout and the baseline source rather than adapted from an existing scenario.

There is a second reason no expected-output master exists for this module at all, and it
is worth naming so that its absence is not mistaken for an omission. The four programs
this module migrates -- `COTRN00C`, `COTRN01C`, `COTRN02C` and `COBIL00C` -- are online
CICS programs, which the reference suite documents as not runnable end to end without a
CICS runtime. The reference suite's expected-output comparison covers batch flows only, so
there is nothing for an online-path scenario to be compared against even in principle.

---

## 2. The rule it exercises

The account rewrite in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl). Every line number
below was read out of the file rather than carried over from a description of it.

| Step | Line | Statement |
|---|---|---|
| validation returned no reason | 211 | `IF WS-VALIDATION-FAIL-REASON = 0` is true |
| posting is entered | 212 | `PERFORM 2000-POST-TRANSACTION` |
| the posting paragraph | 424 | `2000-POST-TRANSACTION.` |
| the account paragraph is performed | 441 | `PERFORM 2800-UPDATE-ACCOUNT-REC` |
| the account paragraph header | 545 | `2800-UPDATE-ACCOUNT-REC.` |
| the running balance moves | 547 | `ADD DALYTRAN-AMT TO ACCT-CURR-BAL` |
| the sign is tested | 548 | `IF DALYTRAN-AMT >= 0` |
| a non-negative amount joins the cycle credit | 549 | `ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT` |
| a negative amount joins the cycle debit | 551 | `ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT` |
| **the rewrite** | **554** | `REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD` |
| the failure arm opens | 555 | `INVALID KEY` |
| the reason is set | 556 | `MOVE 109 TO WS-VALIDATION-FAIL-REASON` |
| the message is set | 557 | `MOVE 'ACCOUNT RECORD NOT FOUND'` |
| into the description field | 558 | `TO WS-VALIDATION-FAIL-REASON-DESC` |
| the arm closes | 559 | `END-REWRITE.` |

Two things about that table are load-bearing.

**The rewrite is line 554.** Line 545 is the paragraph header, nine lines earlier, and
carries no statement. The two are easy to conflate because the paragraph is short, and a
description of this folder did conflate them; the distinction is recorded here so the
conflation is not carried any further.

**Reason 109 lives in the validation reason field without being a validation reason.**
`WS-VALIDATION-FAIL-REASON` is declared `PIC 9(04)` at line 181, with
`WS-VALIDATION-FAIL-REASON-DESC` as `PIC X(76)` at line 182. Four digits is what makes a
three-digit `109` sit legally in the same field that holds `100`. The field is shared, the
control path is not: 109 is reached only from `2000-POST-TRANSACTION` by way of the
`PERFORM` at line 441, which line 211 reaches only when validation found nothing to
refuse. The folder name records exactly that -- a rewrite with an invalid key, not a
validation refusal.

### 2.1 Why the daily record has to pass all four gates

Control reaches line 554 only if `1500-VALIDATE-TRAN` at line 370 leaves the reason at
zero, which means all four gates below were passed:

| Gate | Paragraph or test | Reason on failure |
|---|---|---|
| the cross-reference lookup | `1500-A-LOOKUP-XREF.` at line 380, reading the cross-reference at line 383 | 100 at line 385 |
| the account lookup | `1500-B-LOOKUP-ACCT.` at line 393, reading the account at line 395 | 101 at line 397 |
| the inclusive over-limit test | `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` at line 407, over the balance composed at lines 403 to 405 | 102 at line 410 |
| the inclusive expiry test | `IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)` at line 414 | 103 at line 417 |

Both surviving tests are inclusive: a balance exactly at the limit passes, and a
transaction dated equal to the expiration date passes. The field name in the expiry test is
reproduced above as `ACCT-EXPIRAION-DATE`, which is the spelling the copybook carries and
therefore the spelling a search has to match.

`dailytran.txt` is consequently byte-identical to `happy_path/dailytran.txt`, measured.
That is the design of the fixture and not an oversight -- section 7 sets out the reasoning
-- and folder index section 1.3 records the same measurement in its table of how each
scenario relates to the template.

### 2.2 What the baseline does with a 109, stated exactly

This needs care, because the obvious reading is wrong in two places.

The branch at line 211 was already taken toward posting before 109 could be set. The
`ELSE` arm at lines 213 to 215 -- `ADD 1 TO WS-REJECT-COUNT` at line 214 and
`PERFORM 2500-WRITE-REJECT-REC` at line 215 -- is therefore **not reached** for a 109. The
reject counter is not incremented and `2500-WRITE-REJECT-REC` does not run.

Nor does the paragraph stop the posting sequence. `PERFORM 2900-WRITE-TRANSACTION-FILE` at
line 442 follows the account `PERFORM` at line 441 unconditionally, so the transaction
write at line 564 still executes with the reason field holding 109.

The durable reject row and the discarded unit of work that sections 3, 4 and 6 describe are
therefore **migrated behaviour**, and they are attributed to the migration throughout this
document. They are not a reading of the baseline flow at lines 213 to 215, and no statement
here should be taken as one. The baseline's own arrangement is cited as it stands.

---

## 3. The collision trap: 109 and 101 carry the same message

Read this before writing any assertion that touches either scenario.

**The message text for reason 109 is byte-identical to the message text for reason 101.**

| Reason | Set at | Message literal at | Set from |
|---|---|---|---|
| 101 | line 397 | line 398 | the `READ` of the account file at line 395, during validation |
| 109 | line 556 | line 557 | the `REWRITE` of the account file at line 554, after validation |

Both literals are `ACCOUNT RECORD NOT FOUND` -- 24 characters, no trailing punctuation, no
distinguishing whitespace, nothing to tell them apart.

The consequences are specific:

- **An assertion on `reason_desc` alone cannot distinguish 101 from 109.** It will pass
  against the wrong scenario and report success. **Only `reason_code` separates them**, so
  any assertion covering either scenario must include the code. Folder index section 1.2
  records the same collision at folder level.
- **Neither folder is redundant.** [`../reject_101_acct_missing/`](../reject_101_acct_missing/README.md)
  is the other half of the pair. The two differ in the numeric code and in *when* the
  reason fires -- one during validation, one after it -- not in their text. Deleting either
  because its message duplicates the other's would remove a distinct control path from
  coverage while leaving the message covered, which is the least useful half to keep.
- **The literal is not altered to make testing easier.** Every user-visible string crosses
  into the migration character for character, and that contract outranks the convenience of
  a description-only assertion. The duplication is the baseline's own arrangement and is
  carried across as it stands.
- The shared four-digit reason field described in section 2 is what permits the reuse in
  the first place. It is one field serving two distinct classes of outcome.

---

## 4. The atomicity witness: the property only this folder can prove

Reason 109 is the only reject reason in the program that fires **inside** the posting unit
of work, after that unit of work has already done part of its job. Reasons 100, 101, 102
and 103 all decide before any of it starts.

By the time control reaches the rewrite at line 554, three things have happened:

| Already done | Where |
|---|---|
| the processing timestamp has been minted and moved into the transaction record | `PERFORM Z-GET-DB2-FORMAT-TIMESTAMP` at line 437, then the move at line 438 |
| the category balance has been read and taken through its fork | `PERFORM 2700-UPDATE-TCATBAL` at line 440, into the paragraph at line 467 |
| the in-memory account totals have been adjusted | lines 547 to 551 |

The category-balance fork matters, and this folder pins which arm it takes. Because
`tcatbal.txt` ships a row **on the composed key**, the read at line 474 succeeds and the
fork takes the **update** arm, `2700-B-UPDATE-TCATBAL-REC.` at line 526, which adds the
amount at line 527 and rewrites at line 528. It does **not** take the create arm,
`2700-A-CREATE-TCATBAL-REC.` at line 503. So the work standing behind the failure is a
modification of an existing row, which is precisely the work a rollback has to discard.

### 4.1 What the migration guarantees, and what a test must assert

The migration keeps the three posting writes -- the transaction, the category balance and
the account -- inside **one ACID commit**. A saga with compensating reversals was
considered and rejected for this exact unit of work, on the grounds that reversals would
make partial-posting states observable that the baseline never exposes; specification
section 0.4.1.3 records that decision and the scoped grant that makes the single commit
possible. **This folder is the fixture that witnesses the guarantee**, because it is the
only one whose failure arrives after work has begun.

The assertion has three parts and all three are required:

1. **`ledger.transactions` gains no row for the failed transaction.** No row bearing
   `TRAN-ID 0000000000683580` appears. The table still holds exactly the one pre-existing
   row that `transact.txt` supplies, and no more.
2. **`ledger.transaction_category_balances` is unchanged.** The row on
   `00000000007 / 01 / 0001` still carries `+100.00`. It must never be found holding
   `604.77`, which is what `100.00 + 504.77` would produce had the update arm at line 526
   been allowed to stand.
3. **The reject row is the only durable output.** One row in
   `ledger.transaction_rejects`, shaped as section 6 sets out, and nothing else.

**A test that checks the reject row but not the absence of the two partial writes leaves
this folder's main purpose unproven.** It would confirm that a 109 is reported, which the
sibling reject scenarios largely establish already, while saying nothing about the property
that justifies the folder existing. Both halves are the assertion.

Money in all three parts is exact fixed point end to end: `NUMERIC(11,2)` in the schema,
`BigDecimal` at scale 2 in Java, and a string on the wire. A comparison that routes
`604.77` or `100.00` through a binary floating-point type is not a valid check of any of
the three parts.

---

## 5. Input fixtures: widths, record counts, line endings

Three files, one record each. The master byte contract governs the encoding and is cited by
section rather than restated: fixed-width positional fields with no delimiters and an exact
`RECLN` per record at master section 3.1, line endings at master section 3.2, the trailing
newline at master section 3.3, the sign overpunch at master section 3.4 and the implied
decimal at master section 3.5. The two 350-byte layouts are master sections 5.1 and 5.5;
the 50-byte layout is master section 5.6.

| File | Copybook | Table it seeds | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---|---|---|---|---|
| `dailytran.txt` | `CVTRA06Y` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `CVTRA05Y` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `CVTRA01Y` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

Carriage-return count across all three files is zero, measured. The extra byte on disk in
each row is the single trailing line feed and nothing else.

No account image ships here. Folder index section 3.1 is the authority for this folder
carrying three record layouts rather than five: the account record belongs to the `account`
schema, owned by another module. That absence is what section 7 has to account for, because
the discriminating condition of this scenario is a property of the account file.

---

## 6. Expected outcome and the reject output contract

**Outcome.** The transaction is not posted. One reject is produced, and the two partial
writes named in section 4.1 are discarded rather than left standing.

**What this folder supplies to `ledger.transaction_rejects`:**

| Column | Value |
|---|---|
| `reason_code SMALLINT` | `109` |
| `reason_desc VARCHAR(76)` | `ACCOUNT RECORD NOT FOUND`, verbatim, character for character |
| `raw_record CHAR(350)` | the 350 bytes of the `dailytran.txt` record, undecomposed, including the 26 spaces at positions 305 to 330 |

Measured in
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql): `raw_record` is
declared at line 605, `reason_code` at line 619 and `reason_desc` at line 631.

**Where that shape comes from.** `2500-WRITE-REJECT-REC.` at line 446 moves the whole daily
record into the reject data area at line 447, moves the trailer at line 448, and writes the
composed record at line 451. The record it writes is declared at lines 176 to 178 as
`X(350)` of transaction data plus an `X(80)` trailer, so **430 bytes**, and lines 180 to 182
decompose that trailer into the four-digit reason and the 76-byte description. The total is
corroborated independently by
[`app/jcl/POSTTRAN.jcl`](../../../../../../../app/jcl/POSTTRAN.jcl) line 36, whose
`DALYREJS` allocation at line 34 declares `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)`. Two
unrelated sources agreeing on 430 is what makes the width a fact rather than an arithmetic
guess. The `9(04)` reason width is the same declaration that lets a three-digit `109` share
the field with `100`.

The data area stays undecomposed on purpose, and folder index section 2.3 is the authority:
the reject is an **output** of posting rather than an input to it, which is also why no
scenario in this folder ships a reject fixture and why this document states the expected
code and message in prose instead.

**No graded outcome applies here.** The Maven and JUnit gates this module runs under are
pass or fail. The reference suite's condition-code rubric belongs to that suite and its own
workflow, and folder index section 9 says so directly; nothing in this scenario passes at a
reduced or warning grade.

---

## 7. Why the fixture is shaped this way

Four rationales, tagged with the four labels the documentation standard fixes at its lines
209 to 226 -- plural, unparenthesised, colon retained, no emphasis markup. All four
categories are present rather than the one the rule requires as a minimum, because this
folder has no precedent to point at: every choice in it is one a reader could reasonably
have made differently, so leaving any category unaddressed would leave a real alternative
undocumented.

Alternatives Considered: giving `dailytran.txt` its own distinct field values, so that this
scenario's primary input would not be byte-identical to another folder's. Rejected, and the
identity is deliberate. Only a record that already passes every gate reaches line 554, and
every field a variation could touch is a field one of the four gates reads -- the card
number feeds the lookup at line 383, the amount feeds the balance composed at lines 403 to
405 and tested at line 407, and the originating date feeds the test at line 414. Changing
any of them moves the record onto a **validation** reject path, at which point line 211 is
false, line 212 never runs, and the post-validation path this scenario exists to cover is
not exercised at all -- the reject surfaced would be 100, 101, 102 or 103 and the scenario
would silently become a duplicate of a folder that already exists. Holding the record
identical is also what proves the two scenarios differ only in the injected account-rewrite
failure and not in their input.

Assumptions: the discriminating condition is injected at the repository boundary and is not
encoded in these bytes, because **no field of the 350-byte record can express "the account
row was readable during validation and not writable at the rewrite"**. The record has no
field for the state of another file. The precondition is that the account is found by the
`READ` at line 395 and rejected by the `REWRITE` at line 554, and it is established by the
consuming test's own account state -- which is consistent with this folder shipping no
account image at all, per folder index section 3.1. A second assumption concerns why
`transact.txt` exists separately: `ledger.transactions.proc_ts` is declared `NOT NULL` at
`V1__ledger.sql` line 247 while `ledger.daily_transactions.proc_ts` is nullable at line
463, and that single asymmetry is what folder index section 3.2 records as the reason these
are two files rather than one shared record. It is why `dailytran.txt` leaves positions 305
to 330 blank, correct for a feed record nothing has posted, while `transact.txt` carries a
real 26-character value there. Master section 6.3 pins that span blank on **input**
fixtures; `transact.txt` is seeded prior state rather than input, so the two are a
divergence in the file's **role** and not a contradiction of the rule -- both insist a
fixture hold a fixed literal rather than a wall-clock read, which is the property that
keeps a comparison on a 26-character timestamp reproducible. A third assumption covers
`tcatbal.txt`: its upstream seed is one of the three master section 3.2 names as shipping
CRLF, and this file is normalised to LF, following folder index section 3.3. Carrying the
seed's line endings across verbatim is what would be wrong here, because master section 3.1
makes a 51-byte physical row a corrupt 50-byte record rather than a formatting variant.

Trade-offs: carrying the message literal at line 557 byte-identical to the one at line 398
costs this scenario all discriminating power on `reason_desc`, and that cost is accepted.
The alternative -- altering one of the two literals so a description could tell them apart
-- was available and is declined, because the verbatim-message contract outranks the
convenience of a description-only assertion. The whole distinction therefore rests on
`reason_code`, the `SMALLINT` at `V1__ledger.sql` line 619, and every assertion covering
either scenario has to read that column. A second cost falls on this document rather than
on the bytes: because `dailytran.txt` matches `happy_path/dailytran.txt` exactly, a diff
between the two directories reports no difference in that file at all, so to a reader
skimming the tree the folder reads as an accidental duplicate. This README and folder index
sections 1.2 and 1.3 are the only artifacts that distinguish them, which is a real
maintenance cost accepted in exchange for the control path of section 2.2 being covered;
should all three drift, the scenario looks like a copy-paste error and invites removal by
someone acting in good faith.

Refactoring Rationale: the scenario is added even though the reference tree omits it, and
the alternative -- mirroring that tree exactly and shipping nine scenarios rather than ten
-- is declined, because two properties this module owns would then be uncovered. First,
`ledger.transaction_rejects.reason_code` has to accept `109` and not merely the four
reasons the reference table lists at `tests/README.md` lines 563 to 566, and the collision
in section 3 means a description-only assertion elsewhere would actively hide a confusion
between 109 and 101 rather than fail on it. Second, the posting unit of work has to be
all-or-nothing, and section 4 establishes that no other reject reason fires late enough to
test it: reasons 100 and 101 are set at lines 385 and 397 before any account or balance
write is attempted, and 102 and 103 at lines 410 and 417 likewise, so not one of the four
mirrored scenarios can distinguish a single atomic commit from a sequence of committed
steps. Dropping this scenario for having no precedent would leave both properties resting
on inspection alone.

---

## 8. Provenance and synthetic-data attestation

Every record file here carries a primary account number, so the attestation mandated by
master sections 10.1 through 10.3 applies. Folder index section 7 restates the three
required items at folder level. All three are recorded below; the reasoning for deriving
from the published seeds rather than minting fresh values lives in master section 10.2 and
is not repeated.

**1. The account-number and identity bytes are synthetic and seed-derived.**

| File | Seed and row key |
|---|---|
| `dailytran.txt` | [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt) line 1 -- card `4859452612877065`, `TRAN-ID 0000000000683580`. Byte-identical to that line, measured. |
| `tcatbal.txt` | the `00000000007 / 01 / 0001` row of [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt), which is its line 7. |
| `transact.txt` | identity bytes from `app/data/ASCII/dailytran.txt` **line 114** -- the same card `4859452612877065`, with `TRAN-ID 0000000380632461`, amount `0000004283C` and merchant `Beahan, Little and Sanford`. |

`transact.txt` has **no seed of its own**, and this is a genuine provenance fact rather than
an oversight: `app/data/ASCII/` holds exactly nine files -- `acctdata`, `carddata`,
`cardxref`, `custdata`, `dailytran`, `discgrp`, `tcatbal`, `trancatg` and `trantype` -- and
none of them is `transact.txt`. There is no seeded posted-transaction master to copy,
because `TRANSACT` is an **output** of `CBTRN02C`, written at line 564, and never an input
to it. Its bytes are therefore authored over the `CVTRA05Y` layout from a daily seed row,
which folder index section 4.2 item 7 establishes as the accepted derivation for this file.

`transact.txt` deliberately does **not** reuse the identifier the daily record carries, and
the reason is a schema fact rather than a preference. `V1__ledger.sql` declares
`pk_transactions PRIMARY KEY (transaction_id)` at line 257, so seeding
`ledger.transactions` with the identifier the feed is about to post would make the posting
insert raise a unique violation -- a different failure from the account rewrite this
scenario exists to exercise, and one that would mask it. It would also make the first
assertion in section 4.1 unfalsifiable, because a row bearing that identifier would be
present before the run and "no row was added for this transaction" could not fail. Holding a
**different** identifier on the **same** card keeps that assertion falsifiable while leaving
the table populated, so it is a genuine no-new-row check rather than an empty-table check.

**2. They represent no real person and no real account.** Every account number, card
number, merchant name and address byte here comes from the published CardDemo demonstration
seed. They are demonstration values, not credentials, and they identify no real person and
no real account. No secret, key or credential of any kind appears in this folder.

**3. Business-rule fields reshaped away from the seed value.** Byte-diffed against each
file's seed row, so the changed bytes are accounted for exactly:

| File | Reshaped | From | To |
|---|---|---|---|
| `dailytran.txt` | **nothing at all** -- zero bytes differ from the seed row | -- | -- |
| `tcatbal.txt` | `TRAN-CAT-BAL` at positions 18 to 28, a single byte within the field | `0000000000{`, `+0.00` | `0000001000{`, `+100.00` |
| `transact.txt` | `TRAN-PROC-TS` at positions 305 to 330, and nowhere else | 26 blanks | `2022-07-18 00:00:00.000000` |

Both reshaped values are chosen rather than arbitrary. The category balance is seeded
non-zero so that the read at line 474 finds a row on the composed key and the fork takes the
update arm at line 526 -- a zero-balance or absent row would take the create arm at line 503
and this scenario would assert the opposite of what section 4 claims. The balance is also
held identical across the populated scenarios in this folder, so a consumer asserting
balance arithmetic has one prior value to reason from. The processing timestamp is a fixed
literal because the baseline mints that value at run time, at lines 437 and 438, and a
fixture that read a clock could not support a reproducible comparison; the literal chosen is
the one the sibling scenarios carry, so the folder stays internally consistent.

The `{` and `G` bytes in the tables above are sign overpunches, not stray characters:
`{` is a positive zero and `G` a positive seven, per master section 3.4. `dailytran.txt`
carries `0000005047G`, which is `+504.77` once master section 3.5's implied decimal is
applied -- the amount behind the `604.77` that section 4.1 forbids.

---

## 9. Companion artifacts

Annotated `[present]` or `[planned]` per master section 9.3, whose stated purpose is that a
future artifact is never described as though it already exists. A path that does not resolve
is written as a plain code span rather than a link.

- [`TransactionFixtureContractTest`](../../../java/com/carddemo/transaction/fixtures/TransactionFixtureContractTest.java)
  -- **[present]**, and it does read bytes from this folder. It names
  `reject_109_rewrite_invalid_key` in three of its scenario lists and asserts this folder's
  category-balance row: the composed key `7 / 01 / 1` and the balance `100.00`, at its lines
  250 to 254. It also asserts that this folder's `dailytran.txt` uses the seed card. It is a
  byte-contract test over the fixtures themselves, so it holds these records to the values
  this document states.
- A **fixture-loading integration test for the 109 reject path** -- **[planned]**. Nothing
  loads these records into `ledger` and asserts the three parts of section 4.1. That test is
  the consumer this folder was authored for, and in its absence the atomicity guarantee
  rests on inspection. Its home is
  `services/transaction-service/src/test/java/com/carddemo/transaction/service`, whose
  package is [present] and holds no such class.
- [`TransactionRepositoryIT`](../../../java/com/carddemo/transaction/repository/TransactionRepositoryIT.java)
  -- **[present]**, and it reads no byte from this folder; it builds every row it needs in
  code. It establishes the tier a fixture-loading test would join: integration against a
  real PostgreSQL instance through Testcontainers, applying this module's own migration. An
  in-memory substitute is not used, because the non-unique index on the processing timestamp
  and the key-ordered read paths these records feed are properties of the real engine. Read
  paths page by **key**, never by ordinal position, so fixture record order is significant
  wherever a page boundary is under test.
- [`application-test.yml`](../../application-test.yml) -- **[present]**. It pins schema
  resolution to `ledger` at its line 283 and pins the clock to one instant, which is what
  makes any assertion on a 26-character timestamp reproducible.
- [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) --
  **[present]**. It creates the four `ledger` tables these records seed and the two indexes
  the read paths use.
- [`../README.md`](../README.md) -- **[present]**, the folder index and the authority for
  every folder-level convention cited above.
- [`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) --
  **[present]**, the authoritative byte contract.

Nothing under `app/`, `tests/`, `scripts/` or `samples/` is modified by this scenario, and
no row for reason 109 is added to the reference tree. Those trees are read-only: they are
the specification this document reasons from and the parity oracle for the batch flows, and
the migration reads them without writing to them. Everything this scenario needed was
authored inside this folder.

---

This document is the Rule 1 (Explainability) carrier for
`reject_109_rewrite_invalid_key/` and for the three record files beside it, which can carry
no documentation of their own.
