# CBTRN02C posting -- reject 100, a card number that resolves to no cross-reference row

**Purpose.** This document is the scenario record for
`fixtures/reject_100_card_missing/`. It states what the three fixed-width record
files beside it represent, which business rule they exercise, what a consumer
should expect of them, and why each non-obvious byte in them is the byte it is.

**Why it is mandatory rather than courteous.** The three files here --
`dailytran.txt`, `transact.txt` and `tcatbal.txt` -- are fixed-width positional
records, and a fixed-width record has no comment construct at all. A single added
byte of prose would change the record width, break the check that a file's line
count equals its record count, and shift every field after the insertion point.
Those files therefore carry no explanation of their own, and this document
discharges the whole of their Explainability obligation. That obligation is
Rule 1 of this project's user-specified rules, whose full text is available
through `review_rules`; the requirement that a scenario directory carry this
document is stated as a `MUST` in
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md)
section 9.1.

**What is not repeated here.** The reasoning that is identical across all ten
scenarios in this folder lives in the folder index at
[`../README.md`](../README.md) and is cited by section number rather than copied.
The byte-encoding contract -- field offsets, the sign-overpunch table, the
implied decimal, the line-ending and trailing-newline rules -- lives in the
master document above and is likewise cited by section number and never
restated. Where this document gives a number, that number was read from the file
before it was written down.

---

## 1. Scenario intent

This scenario represents a daily transaction whose card number resolves to **no
cross-reference row**. The observable result is a reject carrying reason code
**100** and the message `INVALID CARD NUMBER FOUND`.

It is the **first** gate in the validation order, and that position is itself
part of what the scenario asserts. The account lookup, the credit-limit
comparison and the expiration comparison all sit behind it and are never
reached, so this record's amount and date are left at values that would have
passed had the chain ever got that far.

---

## 2. Rule exercised -- `CBTRN02C` `1500-VALIDATE-TRAN` (L370)

The paragraph at line 370 of
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl) runs its
gates in a fixed order. It performs `1500-A-LOOKUP-XREF` first, on line 371, and
reaches `1500-B-LOOKUP-ACCT` on line 373 only under the guard on line 372 that
the reason is still zero.

`1500-A-LOOKUP-XREF` is declared on line 380:

```text
MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
READ XREF-FILE INTO CARD-XREF-RECORD
   INVALID KEY
     MOVE 100 TO WS-VALIDATION-FAIL-REASON
     MOVE 'INVALID CARD NUMBER FOUND'
       TO WS-VALIDATION-FAIL-REASON-DESC
   NOT INVALID KEY
     CONTINUE
END-READ
```

That is lines 382-391: the key is moved on 382, the read is issued on 383, and
the `INVALID KEY` arm opening on 384 sets reason code 100 on 385 and moves the
literal message on 386 into the description field on 387.

The driver decides per record. Line 208 resets the reason to zero and line 209
blanks the description, line 210 performs the validation, and line 211 admits
posting on line 212 only while the reason is still zero. With the reason
non-zero, line 211 takes its `ELSE` on 213, the reject count is incremented on
214 and `2500-WRITE-REJECT-REC` runs on 215.

Assumptions: reason 100 **pre-empts** reasons 101, 102 and 103, and this masking
is the behaviour under test rather than an accident of the fixture. Because line
372 guards the rest of the chain on the reason still being zero, a record whose
card does not resolve is never measured against anything else -- so a record that
would *also* have been over limit, or *also* have been past expiration, still
reports 100. Naming what is never reached is the only way to say that precisely:
the account lookup at line 393; the over-limit computation at lines 403-405,
`COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT +
DALYTRAN-AMT`, together with the inclusive gate at line 407,
`IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`; and the expiration gate at line 414,
`IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)`. None of the four executes
for this record. A consumer that asserts only "some reject occurred" would pass
while the ordering silently inverted, which is why the reason code and the
ordering are asserted rather than the fact of a reject.

---

## 3. What this folder serves in this module

The lookup on line 383 reads the card cross-reference, which in the target is
`account.card_xref` -- a table in the `account` schema, owned by another bounded
context. `CBTRN02C` itself is batch-service's program. **This module therefore
does not re-run that lookup**, and this folder ships no image of that table;
folder section 3.1 is the authority for that composition and folder section 2.3
for why a reject is not shipped as an input either.

What this module does own is the reject **output** contract. The reject is
written into `ledger.transaction_rejects`, and this scenario supplies the one row
expected there:

| Column | Expected value |
|---|---|
| `reason_code SMALLINT` | `100` |
| `reason_desc VARCHAR(76)` | `INVALID CARD NUMBER FOUND` |
| `raw_record CHAR(350)` | the 350 bytes of the `dailytran.txt` record, undecomposed and byte for byte |

The description is carried **verbatim, character for character**, as the literal
declared on line 386. It is 25 characters, in upper case, with no trailing
period. Any case change, added punctuation or rewording is a defect rather than a
formatting preference.

The undecomposed form of `raw_record` is what makes it the strongest available
assertion, and the shape is corroborated three ways. In the baseline,
`2500-WRITE-REJECT-REC` at line 446 moves the **whole** daily record into the
reject data area on line 447 and writes the composed record on line 451. That
composed record is 430 bytes: lines 176-178 declare `REJECT-RECORD` as a
`PIC X(350)` data area followed by a `PIC X(80)` trailer, and lines 180-182
decompose the trailer into a `PIC 9(04)` reason and a `PIC X(76)` description.
Independently,
[`app/jcl/POSTTRAN.jcl`](../../../../../../../app/jcl/POSTTRAN.jcl) line 36
declares the same total for that stream as `DCB=(RECFM=F,LRECL=430,BLKSIZE=0)`.
The target keeps the data area whole:
[`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql)
declares `raw_record CHAR(350)` on line 605 beside `reason_code SMALLINT` on 619
and `reason_desc VARCHAR(76)` on 631, the three contract columns of the table
created on line 542, which also carries a generated surrogate key on line 579 as
its primary key on line 653.

Assumptions: because `raw_record` is stored undecomposed, **the fixture file is
the expected value**. The 350 bytes of `dailytran.txt` and the 350 characters of
the expected column value are the same bytes, including the 26 spaces at
positions 305-330 that the input carries in its processing-timestamp field. That
equality is what a consumer can assert directly, and it is also what any
per-field re-encoding on the way in would break: decoding the record into fields
and re-emitting it would silently normalise the blank timestamp, the sign
overpunch or the trailing padding, and the comparison would then be against a
re-rendered record rather than against the bytes the program received.

Alternatives Considered: staging the missing cross-reference row as a
`cardxref.txt` in this folder, so that the absence were expressed as data rather
than described in prose. It was rejected on ownership grounds, and the temptation
is strongest in exactly this folder, because here the missing row **is** the
trigger for the behaviour under test. The cross-reference table belongs to
account-service; `ledger` is the only schema this module owns. Shipping that
file here would put one table's ownership in two deployables, which is the
failure that bounded contexts exist to prevent and which no later addition
repairs. The absence is therefore asserted by the consuming test's stubbed
cross-reference lookup rather than by a file on disk. The account master is
likewise absent from this folder for the same reason.

---

## 4. Fixture bytes and governance

### 4.1 Files, widths, record counts, line endings

| File | Table it loads | Records | Bytes per record | Bytes on disk | Line ending |
|---|---|---:|---:|---:|---|
| `dailytran.txt` | `ledger.daily_transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `transact.txt` | `ledger.transactions` | 1 | 350 | 351 | LF, one trailing newline |
| `tcatbal.txt` | `ledger.transaction_category_balances` | 1 | 50 | 51 | LF, one trailing newline |

Every figure above is measured. Each file's carriage-return count is zero, and
each holds one line, so the record count and the line count agree as master
section 3.3 requires. The one byte between the record width and the size on disk
is the single trailing line feed.

The layouts are `CVTRA06Y` for `dailytran.txt` at master section 5.1, `CVTRA05Y`
for `transact.txt` at section 5.5, and `CVTRA01Y` for `tcatbal.txt` at section
5.6. The field offsets are stated there and are not repeated here; the sign
overpunch is master section 3.4 and the implied decimal master section 3.5.

### 4.2 The values that decide this scenario

`dailytran.txt` -- the feed record the rule rejects:

- card number `9999999999999999` at positions 263-278, the value that resolves to
  nothing;
- amount `0000005047G`, decoding to **+504.77**;
- originating timestamp `2022-06-10 19:27:53.000000` at positions 279-304, whose
  first ten characters are the date the expiration gate would have consumed had
  it been reached;
- processing timestamp **26 spaces** at positions 305-330;
- trailing `FILLER` 20 spaces at positions 331-350.

`transact.txt` -- one transaction already on the posted ledger before the run:

- identifier `0000000001774260`;
- amount `0000009190}`, decoding to **-919.00**;
- card number `0927987108636232`;
- processing timestamp `2022-07-18 00:00:00.000000` at positions 305-330.

`tcatbal.txt` -- the category-balance row the reject must leave alone:

- key at positions 1-17, composed as account `00000000007`, type `01`, category
  `0001`;
- balance `0000001000{` at positions 18-28, decoding to **+100.00**;
- trailing `FILLER` at positions 29-50 as **22 ASCII zeros, not spaces**, the
  seed convention recorded at master section 5.6 lines 432-435.

**The balance is +100.00 and not +10.00.** The trailing `{` is not punctuation
and not a placeholder: it is a positive overpunch that **supplies the low-order
digit** of the field. The eleven digit positions of this `S9(09)V99` field are
therefore `00000010000`, and because the implied decimal occupies no byte the
last two of those digits are the cents. Reading the visible `0000001000` as the
whole number and ignoring the overpunched digit is the specific misreading this
note exists to prevent.

**Both card numbers are 16 characters of digits, and leading zeros are
significant.** `0927987108636232` begins with a zero that carries meaning, which
is why `card_num` is declared `CHAR(16)` and held as a string -- on
`ledger.transactions` at `V1__ledger.sql` line 222 and on
`ledger.daily_transactions` at line 436. A numeric type would drop that leading
digit and turn a 16-character card into a 15-digit number, and the comparison
against the cross-reference would then fail for a reason that has nothing to do
with the rule under test.

Trade-offs: the category balance is a non-zero **+100.00**, where both the seed
row for account `00000000007` / type `01` / category `0001` in
[`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt)
and the reference tree's counterpart for this scenario carry `0000000000{`, which
is +0.00. The reason is that this scenario's expected outcome for this row is
that it is **untouched**, and +0.00 makes that unfalsifiable: a row holding zero
is indistinguishable from a row that was zeroed, and from no row at all once a
consumer reads a balance rather than a row count. A non-zero prior balance makes
the claim provable -- the value read after the run either is +100.00 or it is
not. The cost accepted is that this field departs from its seed value, so it is
disclosed as such in section 6. Note that the sibling `happy_path` carries the
same +100.00 for a different reason: there the balance makes the update arm
observable in the result, whereas here neither arm runs at all and the balance
exists to make "unchanged" a statement that can fail.

### 4.3 Line endings

Trade-offs: this fixture is stored **LF-only** although the seed it derives from
ships CRLF. `tcatbal` is one of the three seeds master section 3.2 names as
CRLF-terminated, so carrying the seed's line endings across verbatim is the thing
that would be wrong here rather than the thing that would be faithful. That
section is the authority for why a stray carriage return is fatal rather than
cosmetic, and its reasoning is not restated: the consequence for this record is
that the extra byte lands inside the 22-byte `FILLER` at positions 29-50 and
pushes the record past its declared width, which a fixed-width reader absorbs
silently instead of rejecting. The cost accepted is that this file is not a
byte-for-byte copy of its seed line; folder section 3.3 records that every record
file in this folder resolves it the same way, and master section 3.2 requires the
opposite choice to be documented explicitly, which is the choice not taken here.

### 4.4 Why the two processing timestamps differ

One file carries 26 blanks in its processing-timestamp field and the other
carries a real value, and the asymmetry is a schema constraint rather than a
house preference.

Assumptions: master sections 5.1 and 6.3 require a **blank** processing timestamp
on an input fixture, which governs `dailytran.txt`, and the target agrees with
it -- `proc_ts` is nullable on `ledger.daily_transactions` at `V1__ledger.sql`
line 463. On `ledger.transactions` the same column is `NOT NULL` at line 247, so
26 spaces are the correct bytes in the feed record and are refused at insert in
the posted one. What master section 6.3 actually requires of that value is
determinism, not blankness, and a **fixed literal** satisfies it exactly: the
value never comes from a clock, so reruns are byte-identical.

Alternatives Considered: emitting the shape the baseline itself produces. Its
`Z-GET-DB2-FORMAT-TIMESTAMP` at lines 692-705 fills the layout that
`FILLER REDEFINES DB2-FORMAT-TS` declares at lines 160-174, and lines 702 and 703
move a hyphen into the three separator positions and a dot into the three time
separators -- so the baseline's rendering puts a hyphen at position 11, where a
date and a time meet, and dots between the time components. That form was not
used. The space-and-colon form `2022-07-18 00:00:00.000000` is the target's
normative shape, being what `common-lib`'s `TimestampFormatter` emits, it is
already what the seed's own originating timestamp carries at positions 279-304,
and it parses as a `TIMESTAMP(6)` with no conversion step in between.

---

## 5. Expected outcome

The outcome is **binary** in both directions: something is present with exact
values, and something else is absent.

- **Exactly one row in `ledger.transaction_rejects`**, carrying the three column
  values tabulated in section 3 -- reason code `100`, the description
  `INVALID CARD NUMBER FOUND` verbatim, and the feed record's 350 bytes
  undecomposed.
- **No new row in `ledger.transactions`.** The single pre-existing row loaded from
  `transact.txt`, identifier `0000000001774260`, is unchanged, and **no row exists
  for the rejected identifier `0000000000683580`**. The reject path never reaches
  `2900-WRITE-TRANSACTION-FILE` at line 562, because that paragraph is performed
  from within `2000-POST-TRANSACTION` -- at line 442, alongside the category-balance
  update on 440 and the account update on 441 -- and `2000-POST-TRANSACTION` is
  itself performed only at line 212, under the zero-reason guard on line 211.
- **The `tcatbal.txt` row is untouched.** `2700-UPDATE-TCATBAL` at line 467 is
  unreached, and with it both of its arms: the create arm
  `2700-A-CREATE-TCATBAL-REC` at line 503 and the update arm
  `2700-B-UPDATE-TCATBAL-REC` at line 526. The account updates at lines 547 and
  551 are unreached for the same reason. The row therefore still reads +100.00
  after the run.

The prior ledger row is what makes the second bullet assertable at all. A table
asserted to be unchanged has to have contents in order to be unchanged, because a
query over an empty table returns nothing whether or not a write was suppressed.
With one unrelated row present, the reject is checkable twice over: the row count
is one before and one after, and the rejected identifier is absent at both points.
Asserting on the identifier rather than on the count is what keeps the check
binary -- `transaction_id` is the primary key of that table, declared `CHAR(16)`
at `V1__ledger.sql` line 128 and constrained on line 257, so a pre-loaded row
carrying the rejected identifier would be indistinguishable from one an insert had
added, and an insert that did occur would fail on the key rather than on the rule
under test. Measured, no byte of `transact.txt` carries `0000000000683580`.

Nothing here is compared against an expected-output file. Every program this
service migrates is an online program, and the reject row above is asserted by
value rather than by comparison against a stored expected tree, so a reader
should not look for such a tree or add one on the assumption that it was
overlooked.

---

## 6. Provenance and data governance

This folder carries a primary account number in two of its three files, so the
attestation mandated by master section 10.3 applies to it. It matters unusually
here because **the discriminating field of the scenario is itself a card number**,
so the one byte range a reader is most likely to scrutinise is also the one most
likely to be mistaken for real data. Its three required items follow.

**1. The account-number and identity bytes are synthetic and seed-derived.**

- `dailytran.txt` derives from **record 1** of
  [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt).
  Positions 1-262 and 279-350 are that row verbatim -- identifier
  `0000000000683580`, the amount, the merchant name and city, the originating
  timestamp and the blank processing timestamp -- with one field reshaped, per
  item 3.
- `transact.txt` derives from **record 2** of that same seed file. Positions 1-304
  and 331-350 are that row verbatim, including its card number
  `0927987108636232`, which arrives with the row rather than being selected.
  `app/data/ASCII/` holds no `transact.txt` of its own, so a posted-transaction
  record has no seed file to derive from directly; deriving it from the
  parallel `CVTRA06Y` row is derivation at one remove, and folder section 4.2
  records the same absence as a folder-level fact about the seed set.
- `tcatbal.txt` derives from the `00000000007` / `01` / `0001` row of
  [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt).
  That account is not an arbitrary choice: line 21 of
  [`app/data/ASCII/cardxref.txt`](../../../../../../../app/data/ASCII/cardxref.txt)
  maps the card that seed record 1 carries to customer `000000007` and account
  `00000000007`, and master section 8 states the card-to-customer-to-account
  linkage that reading establishes. The key of this row is therefore the key that
  the unreshaped feed record would have resolved to.

**2. They represent no real person and no real account.** Every account number,
card number, name, merchant and address byte in this folder originates in the
upstream AWS CardDemo sample datasets, which are published as fabricated
demonstration data, with the single exception named in item 3, which originates
nowhere and matches nothing. None of it is a credential, and none of it
identifies a real person, a real account or a real payment instrument.

**3. The business-rule fields reshaped away from their seed values.** Three
fields depart from the seed, and each is disclosed so that the provenance of the
*changed* bytes is as explicit as that of the unchanged ones.

- `dailytran.txt` `DALYTRAN-CARD-NUM` at positions 263-278, from the seed row's
  `4859452612877065` to `9999999999999999`. Assumptions: the absence is a
  property of **this scenario's declared world** and is also visible in the seed
  set -- measured, no row among the 50 in `app/data/ASCII/cardxref.txt` begins
  with sixteen nines, whereas the seed row's own card is present there on line 21.
  Stating it either way round without that measurement would be a provenance
  claim rather than a fact, which is why both halves are recorded. This is the one
  field in the folder where a PAN-shaped value departs from the seed, and master
  section 10.2's reshaping discipline -- identity bytes taken unchanged, only
  business-rule fields adjusted -- is what makes that departure worth stating
  plainly. Alternatives Considered: keeping the seed card and declaring the
  cross-reference empty at the scenario level, which is the mechanism the
  reference tree uses, where the condition is produced by staging a decoy
  cross-reference row for a different card. That mechanism is unavailable here,
  because this folder ships no cross-reference image at all, for the ownership
  reason in section 3. Trade-offs: an all-nines sentinel is not a plausible card
  number, and that is precisely its merit -- it cannot collide with a seed row now
  or after any change to the seed, it reads as deliberate to anyone opening the
  file, and it carries no risk of resembling an issuable number. A
  plausible-looking sixteen-digit value would have needed its absence re-measured
  whenever the seed set changed. The replacement keeps the field exactly sixteen
  bytes and digits-only, so the reject arises from the lookup finding nothing
  rather than from a width or character-domain failure.
- `tcatbal.txt` `TRAN-CAT-BAL` at positions 18-28, from the seed row's
  `0000000000{` to `0000001000{`, that is from +0.00 to +100.00, for the reason
  given in section 4.2. The key bytes and the 22-byte `FILLER` are unchanged, so
  the balance is the only field in that record that differs from its seed row.
- `transact.txt` `TRAN-PROC-TS` at positions 305-330, from the seed row's 26
  spaces to the literal `2022-07-18 00:00:00.000000`, for the reason given in
  section 4.4.

A balance and a timestamp are non-identity business-rule fields, which is the
category master section 10.2 permits reshaping. The card number is not, which is
why it carries the fuller justification above rather than a bare mention.

`tcatbal.txt` is additionally stored LF-only although its seed row ships CRLF;
that is a storage convention rather than a change of value, and section 4.3
records it.

---

## 7. Consumers and companion artifacts

This section uses the `[present]` and `[planned]` convention of master section
9.3, whose purpose is to keep a document from describing a future artifact as
though it already existed. Each marker below is a measurement taken on this
branch, not an expectation.

- [`TransactionFixtureContractTest`](../../../java/com/carddemo/transaction/fixtures/TransactionFixtureContractTest.java)
  -- **[present]**, and it reads the bytes of this folder. Measured, it asserts
  that this scenario's card number is sixteen nines and is not the seed card;
  that the category-balance row composes the account, type and category key above
  and holds a balance of 100.00; that `transact.txt` carries -919.00 as a
  `BigDecimal` of scale 2, negative, decoded through the sign overpunch rather
  than read as text; and that the rejected identifier `0000000000683580` appears
  nowhere in `transact.txt` while the prior identifier `0000000001774260` does,
  with the file still 351 bytes so that "unchanged" has contents to be asserted
  against.
- The repository integration tests
  [`TransactionRepositoryIT`](../../../java/com/carddemo/transaction/repository/TransactionRepositoryIT.java),
  [`DailyTransactionRepositoryIT`](../../../java/com/carddemo/transaction/repository/DailyTransactionRepositoryIT.java)
  and
  [`TransactionCategoryBalanceRepositoryIT`](../../../java/com/carddemo/transaction/repository/TransactionCategoryBalanceRepositoryIT.java)
  -- **[present]**. They exercise the three tables these records load, against a
  real PostgreSQL instance through Testcontainers.
- A consumer that loads these three records and asserts the
  `ledger.transaction_rejects` row of section 5 -- **[planned]**. No test class in
  this module reads a fixture into that table, and the module holds no repository
  integration test for it, so the expected row above is stated here as the
  contract such a consumer is to assert rather than as something already
  asserted. Assumptions: the reject row's shape is a property of the real engine
  -- a `CHAR(350)` column blank-pads to its declared width, which is what makes the
  undecomposed comparison in section 3 exact -- so this belongs in an integration
  test against PostgreSQL rather than against an in-memory substitute.
- [`../application-test.yml`](../../application-test.yml) -- **[present]** sibling.
  It is what makes an assertion on these bytes reproducible: it pins schema
  resolution to `ledger` for both the connection and the migration tool, allows
  the migration to create that schema in the empty database a container starts
  with, and validates the migration's output against the entity mapping rather
  than generating a schema from it.
- [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) --
  **[present]**. It creates the four `ledger` tables these fixtures load into,
  including the `transaction_rejects` table of section 3.
- [`../README.md`](../README.md) -- **[present]**, the folder index, authoritative
  for the three-file composition and the folder-level decisions cited throughout.
- [`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) --
  **[present]**, the authoritative byte contract cited by section number
  throughout.
- [`../happy_path/README.md`](../happy_path/README.md) -- **[present]**, the
  sibling scenario in which this same chain runs to completion.

Refactoring Rationale: the first marker above reads **[present]** where folder
section 5 records that no consumer of these fixtures is present, and the
difference is deliberate rather than an oversight. That bullet was written when
nothing in the module read a byte from this tree, and it names the single change
that would falsify it -- one of the test classes loading a record file. A
fixture-reading consumer has since been authored, and the enumeration above is a
measurement of what it asserts rather than a restatement of an expectation. The
reasonable alternative was to mark it **[planned]** so that this document agreed
with the folder index; that was rejected because **[planned]** carries the
specific meaning "never claimed to exist now" under master section 9.3, so
applying it to a class that does exist and does read these bytes would be exactly
the false-availability claim the convention exists to prevent. Agreeing with a
stale sentence is a worse defect than differing from one, because a reader
checking the marker against the filesystem can resolve the difference in one
command and cannot resolve a false claim at all.

---

This README is the mandatory Explainability carrier for the three static `.txt`
fixtures in this folder, as required by
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md)
section 9.1.
