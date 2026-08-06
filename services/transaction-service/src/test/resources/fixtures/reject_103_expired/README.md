# reject_103_expired -- a transaction one day past the account expiration date

Scenario README for `fixtures/reject_103_expired/`, and the Explainability carrier
for the three fixed-width record files beside it. Master section 9.1 states the
obligation in `MUST` wording at
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) line
698: a positional `.txt` record has nowhere to put a docstring, so this document
discharges the whole documentation obligation of the folder rather than
supplementing it.

Two documents carry the reasoning this one deliberately does not repeat. The
[folder index](../README.md) holds everything common to all ten scenarios, and
[`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md) holds
the byte-encoding contract. Both are cited by section number throughout. A second
copy of an encoding rule is free to drift from the first, and a reader who found
two copies disagreeing would have no way to tell which one the loader implements.

## Read this as the folder's docstring

Rule 1 asks for purpose, inputs, outputs and error conditions. Those four map onto
a fixture folder as follows, and the rest of this document expands each one.

- **Purpose.** Prove that the account-expiration comparison in `CBTRN02C` rejects
  with reason **103** once the transaction date passes the account expiration
  date, at the smallest step the field can express: one calendar day.
- **Inputs.** Three fixed-width record files -- `dailytran.txt` (`CVTRA06Y`, 350
  bytes), `transact.txt` (`CVTRA05Y`, 350 bytes) and `tcatbal.txt` (`CVTRA01Y`, 50
  bytes) -- each holding exactly one record. Section 4.1 gives the full table.
- **Outputs.** The three-column reject contract of `ledger.transaction_rejects`:
  reason code 103, the verbatim message, and the driver record's 350 bytes kept
  undecomposed. Section 3 states it precisely.
- **Error conditions.** Section 6 records what makes this fixture stop proving
  what it claims: a second field moving alongside the date, an amount drifting
  close enough to the credit limit for the earlier gate to fire first, or the
  originating timestamp being treated as a runtime value and blanked.

---

## 1. Intent

These fixtures drive the **reject reason 103** path, whose message is

```text
TRANSACTION RECEIVED AFTER ACCT EXPIRATION
```

for a transaction whose originating date falls **one calendar day past** the
expiration date of the account it resolves to.

Everything upstream of the date succeeds, and that is the point of the scenario.
The card resolves in the cross-reference, so reason 100 cannot fire. The account
is found, so reason 101 cannot fire. The amount is comfortably **within** the
credit limit, so reason 102 cannot fire. The date is the only thing that fails,
which is what makes 103 attributable to the date alone.

### 1.1 This folder is one half of a pair, and proves nothing alone

Folder section 1.1 is the authority for the pairing; the part that matters here is
which half this folder is. The comparison at the heart of the scenario is
**inclusive**, and an inclusive comparison needs two fixtures to pin down:

- `boundary_expiry_equal` supplies the **inclusive** side: a transaction dated
  exactly on the expiration date **posts**.
- This folder supplies the **exclusive** side: one day later **rejects**.

Neither half establishes inclusiveness by itself. A fixture that only posts on the
equal date is equally consistent with a rule that accepts every date, and a
fixture that only rejects one day later is equally consistent with a rule that
rejects the equal date too. The two together are the discriminator, and they
discriminate only because they agree byte for byte everywhere except in the date
-- measured, in section 5, as a single byte.

---

## 2. The exact rule it exercises

The gate lives in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl) inside the
paragraph `1500-B-LOOKUP-ACCT.`, which opens at **line 393**. The gate itself
spans **lines 414 to 420**:

```text
414                IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
415                  CONTINUE
416                ELSE
417                  MOVE 103 TO WS-VALIDATION-FAIL-REASON
418                  MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
419                    TO WS-VALIDATION-FAIL-REASON-DESC
420                END-IF
```

The comparison is **inclusive**, because the operator on line 414 is `>=` rather
than `>`. Line 415 is the whole of the true arm, so an expiration date greater
than *or equal to* the transaction date passes and sets nothing.

**The concrete instance this folder builds.** The account resolves with expiration
date `2024-12-13`; the driver record's date is `2024-12-14`. So line 414 evaluates
`"2024-12-13" >= "2024-12-14"`, which is **false**, control takes the `ELSE` on
line 416, reason 103 is set on line 417 and the message on lines 418 to 419.

The house statement of the same rule is in
[`tests/README.md`](../../../../../../../tests/README.md): line **566** carries
the reason-103 row of the posting reject-reason table with the message verbatim,
and lines **572 to 573** carry the rule in prose, line **573** holding the
operative clause -- that a transaction dated equal to the expiration date must
post, and one day past must reject.

### 2.1 Reason 103 is the last gate, and cannot be overwritten

Gate order matters here in a way it does not for the other three reasons, so it is
stated explicitly rather than left to be re-derived.

| Order | Gate | Paragraph or line | Reason it can set |
|---|---|---|---|
| 1 | cross-reference lookup | `1500-A-LOOKUP-XREF.` at line 380 | 100 |
| 2 | account lookup | `1500-B-LOOKUP-ACCT.` at line 393 | 101 |
| 3 | credit-limit comparison | line 407 | 102 |
| 4 | expiration comparison | line 414 | **103** |

Reason 103 is reachable only once gates 1 and 2 have resolved successfully, and
**nothing follows it** -- line 420 closes the gate, line 421 closes the enclosing
`READ` and line 422 exits the paragraph.

The four gates are not, however, all exposed to being overwritten, and the
difference is worth being exact about because all four write the same field.

- **Reasons 100 and 101 cannot be overwritten**, because the flow short-circuits
  around the later gates. `1500-VALIDATE-TRAN`, at line 370, performs the
  cross-reference gate and then performs the account gate **only if the reason is
  still zero** -- the test is at line 372 and its `ELSE` at lines 374 to 375 does
  nothing at all. Within the account gate, the credit-limit and expiration
  comparisons sit in the `NOT INVALID KEY` leg opening at line 400, so a failed
  account read reaches neither.
- **Reason 102 is the one that can be overwritten, and 103 is what overwrites it**,
  because those two alone are sequential independent `IF` statements inside the same
  leg.

So 103 is the only reason that no later gate can supersede, and the only reason
capable of superseding another. Section 6 records what that costs this scenario and
how the fixture is shaped so it never happens here.

### 2.2 The `(1:10)` reference modifier, which is the crux

Line 414 does not compare the whole timestamp. `DALYTRAN-ORIG-TS (1:10)` is a
reference modifier selecting the **first ten bytes** of a 26-character field, and
those ten bytes are exactly the calendar date.

That is not an assumption about the format; it is a consequence of the declared
layout in
[`app/cpy/CSDAT01Y.cpy`](../../../../../../../app/cpy/CSDAT01Y.cpy). The
`WS-TIMESTAMP` group is declared at **line 42** and its **thirteen** members span
**lines 43 to 55**, summing to exactly 26 bytes. Two of those members are literal
punctuation that determines the shape: the `FILLER` at **line 48** carries a **space**
and the one at **line 54** carries a **period**, giving the form
`YYYY-MM-DD HH:MM:SS.mmmmmm`.

Laying the member widths end to end shows where the modifier cuts:

```text
member          L43   L44  L45  L46  L47  | L48  L49 ... L55
picture         9(04) X(01) 9(02) X(01) 9(02) | X(01) ...
value                 '-'        '-'         | ' '
characters      1-4    5    6-7   8    9-10  | 11   12-26
                +-------- (1:10) --------+     +-- excluded --+
```

The first ten characters therefore span the year, a hyphen, the month, a hyphen
and the day and nothing else, and the **space at character 11 is the first byte
the modifier excludes**. A comparison of ten bytes is a comparison of the date.

**The consequence for this fixture.** The originating timestamp at one-based
positions **279 to 304** of the driver record is **author-supplied and
load-bearing**, not incidental padding, because its first ten bytes *are* the
value line 414 consumes. Master section 6.3 records the asymmetry this creates
between the two timestamp fields on an input record -- which one is deterministic
and must never be masked or scrubbed, and which one is the only range a
determinism mask may touch. That section is the authority and its byte contract is
not restated here.

Finally, any date the program itself **consumes** is injected as a fixed literal
rather than read from a wall clock, so a rerun produces identical output. A
scenario built on a date that moves cannot assert a boundary at all, because the
side of the boundary it lands on would change between runs.

### 2.3 Two baseline spellings inside one gate, both reproduced as declared

Lines 414 and 418 each contain a spelling a careful reader will want to tidy, and
they are unusual in being two different kinds of spelling inside a single gate.
Both are reproduced exactly as the baseline declares them.

**One: the field name is `ACCT-EXPIRAION-DATE`.** It is missing the `T` of
`EXPIRATION`. The name is quoted verbatim above because the declared name is what
the program compiles against, and it is declared that way at its source too -- in
[`app/cpy/CVACT01Y.cpy`](../../../../../../../app/cpy/CVACT01Y.cpy) at **line
11**, as `PIC X(10)`. The baseline declares `ACCT-EXPIRAION-DATE`; the target
column is `expiration_date`; the divergence is recorded in
[`docs/architecture/data-model-and-schema-mapping.md`](../../../../../../../docs/architecture/data-model-and-schema-mapping.md).
Those three clauses are the whole of the permitted framing, and section 6 gives
the rationale for why the rename lives only on the target side.

**Two: `ACCT` is abbreviated inside the message, while `EXPIRATION` is not.** The
literal on line 418 reads `AFTER ACCT EXPIRATION` -- the abbreviated form of one
word immediately beside the full form of the other. It is carried
character-for-character: `ACCT` is not expanded to `ACCOUNT`, the case is not
changed, and no trailing period is added. Note that the row in
[`tests/README.md`](../../../../../../../tests/README.md) line 566 describes the
condition as "after account expiration" in full words while quoting the message
with `ACCT` abbreviated. The message is the contract; the description is not.

Two measured properties of the literal:

| Property | Value |
|---|---|
| Length | **42** characters |
| Rank among the four validation messages | **longest** -- reason 100 is 25, 101 is 24, 102 is 21 |
| Target column | `reason_desc VARCHAR(76)`, so it fits with 34 characters to spare |

---

## 3. Expected outcome

What this folder supports is an assertion about one reject row and about two things
that must not change. Stated as the target contract, then grounded in the baseline
that defines it.

### 3.1 One reject row, three columns

Folder section 2.3 is the authority for the reject contract; the values this
scenario fills it with are:

| Column in `ledger.transaction_rejects` | Expected value |
|---|---|
| `reason_code SMALLINT` | `103` |
| `reason_desc VARCHAR(76)` | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`, verbatim |
| `raw_record CHAR(350)` | the `dailytran.txt` record's **350 bytes, undecomposed** -- including the 26 spaces at positions 305 to 330 |

The third row is the one worth reading twice. `raw_record` holds the driver record
whole and unparsed, so the 26 blank bytes of the processing timestamp travel into
the reject row as blanks rather than being dropped, defaulted or rendered as a
zero timestamp. The reject row is a faithful image of what arrived.

That shape comes straight from the baseline. In
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), the paragraph
`2500-WRITE-REJECT-REC.` opens at **line 446**, moves the **entire** daily record
into the data area at **line 447** -- `MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA`,
one move of the whole record rather than a field-by-field copy -- attaches the
trailer at line 448, and writes at **line 451**. The record it writes is declared
at **lines 176 to 178** as a 350-byte data area plus an 80-byte trailer, totalling
**430** bytes; the trailer group opens at **line 180** and its two members at
**lines 181 to 182** are a `9(04)` reason and an `X(76)` description.
[`app/jcl/POSTTRAN.jcl`](../../../../../../../app/jcl/POSTTRAN.jcl) confirms the
total independently at **line 36** with `LRECL=430` on the reject stream's
definition.

### 3.2 Two things that must not change

- **`ledger.transactions` gains no row for the rejected daily record.** The table
  is pre-loaded from `transact.txt` with one row, and a reject must leave it
  holding exactly that one row, byte-unchanged. Section 4.1 records that the
  pre-loaded row and the driver record share a transaction identifier, so the
  assertion to write is a count-and-content assertion on the table -- one row,
  unchanged -- and not an argument from the identifier being distinct.
- **The `tcatbal.txt` row is untouched.** Its balance stays at `+100.00`.

The second follows from control flow rather than from a separate rule, which is
why it needs no assertion of its own in the program. Reason 103 is set during
validation, so the reject branch is taken and `2000-POST-TRANSACTION.` at **line
424** never runs. `2700-UPDATE-TCATBAL` is invoked from **line 440 only**, which
sits inside that paragraph, so the category balance is never reached on this path.
The same is true of the account update and the transaction write on the two lines
after it.

---

## 4. Fixture bytes and governance

### 4.1 The three files

Every number in this table was measured on the branch rather than recalled; section
8 gives the commands that reproduce it.

| File | Copybook | Table it loads | Record width | Records | Bytes on disk | Line ending | Role |
|---|---|---|---|---|---|---|---|
| `dailytran.txt` | `CVTRA06Y` | `ledger.daily_transactions` | 350 | 1 | 351 | LF | the driver; its `ORIG-TS` date is the expiration date plus one day, `2024-12-14`, and its amount is `+504.77`, inside the limit |
| `transact.txt` | `CVTRA05Y` | `ledger.transactions` | 350 | 1 | 351 | LF | pre-existing ledger state that a reject must leave exactly as it found it |
| `tcatbal.txt` | `CVTRA01Y` | `ledger.transaction_category_balances` | 50 | 1 | 51 | LF | pre-existing category balance of `+100.00` that must remain unchanged on this path |

Each file holds **one record followed by a single trailing LF**, which is why each
one occupies exactly one byte more on disk than its record width and why a line
count equals a record count. There is **no carriage return anywhere in this
folder**: `tr -cd '\r' | wc -c` returns `0` for all three files. Folder section 3.3
is the authority for LF being the only line ending here, `tcatbal.txt` included,
and master section 3.3 for the trailing-newline rule.

The three files are three **independent table images**, not a before-and-after pair
of one transaction. Folder section 3.1 is the authority for that, and it is the
reason `dailytran.txt` and `transact.txt` may carry the same transaction
identifier -- both carry `0000000000683580` -- without that coincidence asserting
anything about posting order. It also means nothing here should be read as
"the same transaction, before and after".

Byte-level conventions are cited, never copied. Master section **3.4** holds the
zoned-decimal sign-overpunch table, which is what makes `0000005047G` read as
`+504.77` and `0000001000{` read as `+100.00`; master sections **5.1** and **5.5**
hold the two 350-byte layouts; master section **5.6** holds the 50-byte layout and
the seed's convention of filling its trailing `FILLER` with ASCII zeros rather than
spaces. None of those contracts is restated here, and the overpunch table is not
reproduced anywhere in this folder.

### 4.2 What this folder deliberately does not ship

`CBTRN02C` opens the account master and the card cross-reference during validation,
so a reader who knows the program will expect an `acctdata.txt` and a
`cardxref.txt` here. Neither is present, and the absence is a decision rather than
an omission.

`CBTRN02C` is batch-service's program. This module migrates the four online
programs and owns exactly one schema, `ledger`. The account record belongs to the
`account` schema and the card cross-reference to `account` as well, both owned by
another module; the card record belongs to `card`. Placing either image in this
module's test resources would put another bounded context's records inside this
one's test data and leave two modules free to disagree about the same bytes.
Folder section 3.1 is the authority, and it also records the ceiling this sets: a
scenario in this folder never acquires a fourth or fifth record file.

**The expiration date the gate reads lives in `account.accounts`, not here.** That
is exactly why this folder cannot assert the comparison end to end on its own, and
why what it does assert is the reject contract of section 3 -- the one contract
this module owns.

### 4.3 Account context, in prose only

The values below are **not staged as a file in this folder**. They are recorded
because the scenario is unreadable without them: the driver record's date means
nothing until you know the date it is being compared against. They are stated as
prose, and a test that needs them supplies them itself.

Account `00000000007`, as it stands in the published seed:

| Field | Value | Why it matters here |
|---|---|---|
| expiration date | `2024-12-13` | the value on the left of the comparison -- it is what makes `2024-12-13` the equal case and `2024-12-14` the one-day-past case |
| credit limit | `+2065.00` | four times the transaction amount, so the earlier gate cannot fire |
| current cycle credit | `+0.00` | with the debit also zero, `WS-TEMP-BAL` reduces to the amount alone |
| current cycle debit | `+0.00` | as above |

Those last two are what make the earlier gate predictable. `WS-TEMP-BAL` is
computed at **lines 403 to 405** as the cycle credit minus the cycle debit plus the
transaction amount, so with both cycle amounts at zero it equals the transaction
amount exactly: `0.00 - 0.00 + 504.77 = 504.77`. Line 407 then compares
`2065.00 >= 504.77`, which is true, so reason 102 cannot fire and control reaches
line 414 with the reason field still clear.

### 4.4 `transact.txt` has no seed row, and says so

`app/data/ASCII/` holds exactly **nine** files -- `acctdata`, `carddata`,
`cardxref`, `custdata`, `dailytran`, `discgrp`, `tcatbal`, `trancatg` and
`trantype` -- and **none of them is `transact.txt`**. That is not an oversight in
the baseline: in the posting flow the transaction master is an **output** of the
program, never an input to it, so there was never a seed row to ship. Item 7 of
the table in folder section 4.2 records the same fact.

Two consequences follow, and both are load-bearing:

1. The bytes of `transact.txt` are authored from the `CVTRA05Y` layout rather than
   copied from a seed row, so section 4.5 attests them in its own words rather
   than pointing at a seed line.
2. Its processing timestamp is a **real 26-character value**,
   `2022-07-18 00:00:00.000000`, where the driver's is 26 spaces. The asymmetry is
   not stylistic: `ledger.transactions.proc_ts` is declared `NOT NULL` while
   `ledger.daily_transactions.proc_ts` is **nullable**, so a blank there would not
   load at all. Folder section 3.2 is the authority for that pair, and folder
   section 6 requires this exact literal in every `transact.txt` in the folder.

### 4.5 Provenance attestation

Master section 10.3 requires three statements from every scenario README whose
folder carries account-number or identity data, and folder section 7 records that
the obligation applies to every scenario here without exception. All three follow.

**One: the values are synthetic and seed-derived.** The primary account number
`4859452612877065` and the account identifier `00000000007` come from the published
CardDemo demonstration datasets, which ship with the upstream open-source project as
fabricated demonstration data.

| File here | Derived from | Seed row |
|---|---|---|
| `dailytran.txt` | [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt) | record 1, which is byte-identical to this file apart from the one field in item three |
| `tcatbal.txt` | [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt) | row 7, seed key `00000000007` / `01` / `0001` -- the key is carried across unchanged, so no key move was needed |
| `transact.txt` | no seed row exists; authored from the `CVTRA05Y` layout | see section 4.4 |

**Two: they represent no real person and no real account.** Every account number,
card number, merchant name and address byte in these three files comes from that
published demonstration seed. They are demonstration values, not credentials, and
no value here corresponds to a real payment instrument or a real individual.

**Three: the business-rule fields reshaped away from the seed value.** There are
two, plus one field on the file that has no seed row. Each is a deliberate scenario
choice, and none of them is a correction of the seed.

1. **`dailytran.txt` -- the date inside the originating timestamp.** The seed
   carries `2022-06-10`; this file carries `2024-12-14`, the account's expiration
   date plus one day. Every other field of the record carries over from the seed
   row unchanged, the amount `+504.77` included. Measured against the seed the
   change is four bytes -- positions 282, 284, 285 and 288 -- and measured against
   `boundary_expiry_equal` it is the single byte of section 5. The time-of-day and
   microsecond tail at positions 289 to 304 is the seed's, untouched.
2. **`tcatbal.txt` -- the balance.** Seed row 7 carries `+0.00` at positions 18 to
   28; this file carries `+100.00`. The reason is diagnostic and is given in full in
   section 6: a non-zero starting balance is what makes an accidental update to a
   row that should not change visible at all. Measured against the seed row the
   change is a single byte, position 24. The record's key and its 22-byte
   ASCII-zero `FILLER` are the seed's, unchanged.
3. **`transact.txt` -- the processing timestamp only.** It carries the literal
   `2022-07-18 00:00:00.000000` where a daily-transaction record carries 26 spaces.
   This is not a scenario choice so much as a schema requirement:
   `ledger.transactions.proc_ts` is `NOT NULL`, so the row could not load with the
   field blank. Section 4.4 gives the full reasoning.

Assumptions: a primary account number that satisfies a checksum is
indistinguishable, by inspection, from a live card number -- the digits carry no
marker that says which they are. A reviewer therefore cannot confirm the provenance
of these bytes by looking at them, however carefully. That is the whole reason a
financial fixture must *attest* its provenance in writing beside the bytes rather
than leaving a reader to assume it from context, and it is why this section names
the specific seed file and seed row for each value instead of asserting
"synthetic test data" and stopping. An attestation a reader can check against a
named source is evidence; an unsourced assurance is not.

---

## 5. The one-day discriminator, measured

The pair turns on the date carried in the first ten bytes of the originating
timestamp, at one-based positions 279 to 288.

| Folder | `ORIG` date, bytes 279-288 | Relation to expiration `2024-12-13` | Outcome at line 414 |
|---|---|---|---|
| `boundary_expiry_equal` | `2024-12-13` | equal | expiration `>=` date is true -> **passes** |
| this folder | `2024-12-14` | one day past | expiration `>=` date is false -> **rejects 103** |

Between the two `dailytran.txt` files the delta is **byte 288 alone** -- a `3`
becoming a `4`. That is a measurement, not an intention: `cmp -l` on the two files
reports exactly one differing position, `288`, with octal `63` against octal `64`.
Section 8 gives the command.

The other sixteen bytes of the timestamp are identical in both members.
Concatenating positions 279 to 287 with positions 289 to 304 yields the same string
from each file, so the time-of-day and microsecond portions -- ` 19:27:53.000000`
-- are provably carried across unchanged and **cannot** confound the outcome. A
reader who suspects the result came from a shifted clock rather than a shifted date
can settle it with one comparison.

Two things this table does **not** claim, both worth stating because both would be
easy to assume:

- **The one-byte agreement is a property of the two `dailytran.txt` files only.**
  The `transact.txt` files of the two folders are not byte-identical to each other,
  and they are not required to be. Only the driver record is the discriminator,
  because only the driver record is the input the comparison reads.
- **Neither folder's outcome depends on the amount.** Both carry the same
  `+504.77`, well inside the same limit, which is what leaves the date as the only
  moving part across the pair.

---

## 6. Decisions about this fixture

Each paragraph below is tagged with one of the four labels the documentation
standard permits at
[`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md)
lines 209 to 212 -- plural, unparenthesised, colon retained, no emphasis markup.
Folder section 8 records why the single spelling matters: the labels are read by a
literal search before they are read by a person, so a second accepted spelling of
the same category makes an audit silently partial.

Assumptions: the pair differs in exactly one byte, and every claim this folder
makes rests on that. If the driver record here differed from
`boundary_expiry_equal`'s in any second field, the difference in outcome would be
attributable to more than one cause, and the pair would stop being a discriminator
even though both members still behaved as documented. That is the failure mode
worth naming precisely, because it is silent: both fixtures would still pass, and
the suite would still be green, while proving something weaker than it claims. The
assumption also has a shelf life -- it holds when the two members are authored
together and stops holding the moment one is edited alone -- which is why byte 288
is recorded as a measurement with a reproducing command in section 8 rather than as
an intention.

Assumptions: the amount is held far from the credit limit deliberately, and the
mechanism is worth spelling out because the gates are not mutually exclusive. Both
sit in the same `NOT INVALID KEY` leg of the account read, which opens at line 400,
and they execute in sequence as two independent `IF` statements -- the credit-limit
comparison at line 407, then the expiration comparison at line 414. Both `MOVE`
into the **same** field, `WS-VALIDATION-FAIL-REASON`, declared at line 181 as
`PIC 9(04)`. A record that was both over limit and expired would therefore have 102
written first and then overwritten by 103, so only one reason ever survives and
which one survives is decided by statement order rather than by severity. Holding
the amount at `+504.77` against a `+2065.00` limit, with both cycle amounts at
`+0.00` so `WS-TEMP-BAL` reduces to the amount alone, keeps the earlier gate
silent. Reason 103 is then the sole reason on merit and not merely the last writer.

Assumptions: the ISO ordering of `YYYY-MM-DD` makes the baseline's character
comparison behave as a date comparison. Line 414 compares ten bytes of text, not
two dates -- there is no date type and no date arithmetic anywhere in the
comparison. It gives chronological answers only because a fixed-width,
most-significant-first, zero-padded encoding sorts lexicographically in the same
order it sorts chronologically. Two things follow. It is why the target can store
the column as a `DATE` without changing any outcome, and it is why shifting the
date by one calendar day flips this test reliably: the fixture needs no date
arithmetic of its own, only a different digit in the day position.

Trade-offs: a one-day margin was chosen over a comfortable one, accepting extreme
sensitivity in exchange for the only evidence that actually distinguishes the two
candidate operators. A date a month past the expiration date would also reject, and
would be far more robust to careless editing, but it would say nothing about
whether the comparison is `>=` or `>` -- both operators reject a date a month past.
Only the adjacent day separates them, so only the adjacent day is worth testing.
The cost accepted is that the fixture's entire meaning hangs on one byte, and the
compensation is that the byte is named, measured, and given a reproducing command
rather than left implicit for a future editor to discover by breaking it.

Refactoring Rationale: the rename from the baseline's `ACCT-EXPIRAION-DATE` to the
target's `expiration_date` lives entirely on the target side of a documented
mapping, and it is not a repair of the copybook. A reader meeting the baseline name
for the first time will naturally read it as a typing slip and expect it to have
been put right at some point; that expectation is wrong here, and acting on it
would break things. The declared name is what the program compiles against, and
`app/**` is reference-only, so the copybook keeps its spelling permanently. What
changes is only the name of the target column, and the correspondence between the
two is registered in
[`docs/architecture/data-model-and-schema-mapping.md`](../../../../../../../docs/architecture/data-model-and-schema-mapping.md)
so that neither name has to be guessed from the other. The practical consequence
for anyone working in this folder is that a search for the correct spelling finds
nothing in the baseline, and a search for the target column name finds nothing in
the copybook -- which is precisely why both spellings appear in section 2.3.

Alternatives Considered: the same boundary could have been expressed by moving the
account's expiration date one day earlier instead of moving the transaction date
one day later, and that was rejected on ownership grounds. The expiration date is a
column of `account.accounts`, owned by another module, and section 4.2 records that
this folder ships no account image at all. Shifting a value this folder does not
own would put the discriminator outside the folder that documents it, so the
fixture would no longer be self-describing. Moving the driver record's date keeps
both halves of the pair inside `ledger`, which is the one schema this module owns.

Alternatives Considered: `tcatbal.txt` could have carried the seed's `+0.00`
balance rather than `+100.00`, and a non-zero balance was chosen for a specific
diagnostic reason. Section 3.2 expects that row to be untouched, and a zero balance
makes the difference between "correctly untouched" and "incorrectly updated by an
amount that happened to be zero" invisible. Starting from `+100.00` makes an
accidental mutation self-evident -- posting this transaction against it would leave
`+604.77`, a value that could not arise any other way. It also keeps this folder
directly comparable with `happy_path` and `reject_101_acct_missing`, which start
from the same balance, so a diff between two scenarios shows only what the scenarios
actually differ about.

Trade-offs: this document cites the immutable baseline by line number but cites the
migration by column name and type instead. That asymmetry is deliberate. Files
under `app/**` are reference-only and never change, so a line number into
`CBTRN02C.cbl` stays correct indefinitely and is the most precise citation
available. `V1__ledger.sql` is a live target file that grows as it is authored, and
line numbers into it have already drifted once on this branch, so citing
`reason_code SMALLINT` by name is both stable and sufficient to locate. The cost is
that a reader must search the migration rather than jump to a line; the benefit is
that no citation here can rot into pointing at the wrong statement, which folder
section 8 identifies as worse than carrying no citation at all.

---

## 7. What consumes these fixtures

Availability below uses the `[present]` and `[planned]` convention of master
section 9.3, whose purpose is to stop a document describing a future artifact as
though it already exists. Every entry was measured on the branch, and a path that
does not yet exist is written as a plain code span rather than a link so that no
link in this folder resolves to nothing.

- [`TransactionFixtureContractTest`](../../../java/com/carddemo/transaction/fixtures/TransactionFixtureContractTest.java)
  -- **[present]**, and it reads this folder. It is the executable guard on the byte
  claims made above, and it holds three of them directly:
  - that all three files here are whole records at their declared widths and carry
    no carriage return, which is section 4.1;
  - that this folder's originating timestamp begins `2024-12-14` while
    `boundary_expiry_equal`'s begins `2024-12-13`, and that the remaining tail is
    ` 19:27:53.000000` in **both** -- which is exactly the pair claim of section 5,
    asserted rather than asserted-about;
  - that this scenario carries the seed card and that its category-balance row
    carries the composed key and the template balance, which is section 4.5.

  So the parts of this document that are byte facts are checked by a test rather
  than trusted. A boundary value moving by one day here fails that test.
- [`TransactionRepositoryIT`](../../../java/com/carddemo/transaction/repository/TransactionRepositoryIT.java)
  -- **[present]**, and it does **not** read this folder. It builds every row it
  needs in code through `save(...)` calls on literal values, and runs against a real
  PostgreSQL container rather than an in-memory substitute.
- [`../../application-test.yml`](../../application-test.yml) -- **[present]**. It
  pins schema resolution to `ledger` for both the connection and the migration tool,
  and pins the clock to a single instant. That last one is what makes any assertion
  on a 26-character timestamp reproducible at all, because otherwise the value would
  change between runs.
- [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) --
  **[present]**. It creates the four `ledger` tables these files load into,
  including `ledger.transaction_rejects` with the three columns section 3 asserts
  against.
- A test that **loads these three records into PostgreSQL and asserts the reject
  contract of section 3** -- **[planned]**. Nothing yet drives a record from this
  folder through a repository and checks that a reject row appears with reason 103
  and that `ledger.transactions` is unchanged. That is the assertion this folder
  exists to support and the one still to be written; a plausible home for it is
  `src/test/java/com/carddemo/transaction/fixtures/PostingRejectContractIT.java`.

Assumptions: availability is recorded as a measurement rather than as a plan,
because it has already moved. These files were authored before any consumer existed,
and the fixture-contract test above arrived afterwards; a document that had written
the consumer off as permanently absent would now be wrong, and one that had promised
a specific class name would have been wrong on arrival. Naming what is measured, and
naming the one assertion that is genuinely still missing, is what leaves the next
author something to falsify rather than something to trust.

---

## 8. Verifying every measurement in this document

Every number claimed above is reproducible. Each block is self-contained and is run
from the repository root; none of them changes directory, so they can be run in any
order or pasted together.

Assumptions: each block sets `F` to the fixture tree and addresses files through it
rather than using `cd`. Written with `cd`, a block would leave the shell somewhere
other than the root and the next block's relative paths would silently resolve
against the wrong directory -- and a bare `README.md` at the repository root is the
project's own README, not this one, so that particular slip would check the wrong
file and still report success.

```bash
# WHAT: Report the record width, record count and on-disk size of each file here,
#       then prove there is no carriage return anywhere in the folder.
# WHY : Assumptions: the loader reads one physical line as one fixed-length record,
#       so a width other than 350 for the two 350-byte layouts or 50 for the
#       balance row is a corrupt record and not a formatting preference. A width of
#       51 on a balance line is the specific signature of a stray carriage return,
#       which is why the last command reports a count rather than a pass or fail.
F=services/transaction-service/src/test/resources/fixtures/reject_103_expired
awk '{ printf "%s: width=%d\n", FILENAME, length($0) }' \
    $F/dailytran.txt $F/transact.txt $F/tcatbal.txt
wc -l -c $F/dailytran.txt $F/transact.txt $F/tcatbal.txt
cat $F/dailytran.txt $F/transact.txt $F/tcatbal.txt | tr -cd '\r' | wc -c   # must print 0
```

```bash
# WHAT: Prove the one-day pair differs at exactly one byte, and that the byte is 288.
# WHY : Assumptions: this is the single measurement the whole scenario rests on, and
#       cmp reports it positionally, so the output is the claim itself rather than a
#       summary of it. Expect exactly one line reading "288  64  63" -- octal 64 is
#       the ASCII '4' in this folder and octal 63 the '3' in its counterpart.
F=services/transaction-service/src/test/resources/fixtures
cmp -l $F/reject_103_expired/dailytran.txt $F/boundary_expiry_equal/dailytran.txt
```

```bash
# WHAT: Show the fields the scenario turns on.
# WHY : Assumptions: three of these are invisible or misleading in a terminal -- 26
#       blank bytes look like nothing, a sign lives in a letter-shaped byte, and the
#       leading character of the timestamp tail is a space. Cutting the exact ranges,
#       and piping the blank range through a byte dump, is what makes them checkable
#       rather than merely present.
F=services/transaction-service/src/test/resources/fixtures/reject_103_expired
cut -c279-288 $F/dailytran.txt      # 2024-12-14  -- the compared date
cut -c133-143 $F/dailytran.txt      # 0000005047G -- +504.77 by master section 3.4
cut -c305-330 $F/dailytran.txt | od -c | head -2   # 26 blanks, unposted
cut -c18-28   $F/tcatbal.txt        # 0000001000{ -- +100.00
```

```bash
# WHAT: Re-derive both reshaped fields against the published seeds.
# WHY : Assumptions: the provenance claims of section 4.5 are only as good as the
#       comparison behind them, so they are stated as diffs against a named seed row
#       rather than as assurances. Expect four differing positions for the daily
#       record -- 282, 284, 285 and 288 -- and exactly one, position 24, for the
#       balance row. The seed rows are stripped of their carriage return first
#       because the balance seed ships CRLF, per master section 3.2.
F=services/transaction-service/src/test/resources/fixtures/reject_103_expired
awk 'NR==1' app/data/ASCII/dailytran.txt | tr -d '\r' > /tmp/seed_daily.txt
cmp -l /tmp/seed_daily.txt $F/dailytran.txt
awk 'NR==7' app/data/ASCII/tcatbal.txt   | tr -d '\r' > /tmp/seed_tcat.txt
cmp -l /tmp/seed_tcat.txt $F/tcatbal.txt
```

```bash
# WHAT: Assert this document contains no byte outside 7-bit ASCII, then run the test
#       that guards the byte claims it makes.
# WHY : Assumptions: the visual check is unreliable by construction, because the
#       codepoints that matter are the ones that look like ASCII -- a non-breaking
#       hyphen inside ACCT-EXPIRAION-DATE would silently defeat a search for the
#       field name, which is the main way a reader navigates between this folder and
#       the copybooks. The first command must print nothing at all.
F=services/transaction-service/src/test/resources/fixtures/reject_103_expired
LC_ALL=C grep -n '[^ -~]' $F/README.md
mvn -B -f services/pom.xml -pl transaction-service \
    -Dtest=TransactionFixtureContractTest -DfailIfNoSpecifiedTests=false test
```

---
