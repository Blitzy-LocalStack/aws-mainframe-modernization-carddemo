# zero_balance -- the create arm of the category-balance fork

One well-formed, in-limit daily transaction posted against a category-balance key
that carries **no row at all**. The missing row is the whole scenario: it is what
sends the category-balance decision in `CBTRN02C` down its create arm instead of
its update arm, and it is expressed as a `tcatbal.txt` that is present and
**exactly zero bytes long**.

## Why this README exists

Rule 1, Explainability, is the only user-specified rule on this project. Its
binding text is available through `review_rules`; the specification records the
rule at sections 0.8 and 0.8.1 and the artifacts it forces into scope at section
0.2.1.6. The rule requires that a non-obvious implementation decision carry its
reasoning, and the three sibling files here are fixed-width positional records
with no comment construct of any kind. This document is therefore the only place
in the folder where reasoning can live, and it carries it for all four files.

The obligation is also explicit rather than inferred. `tests/fixtures/README.md`
section 9.1, at its lines 695 to 719, states in deliberate `MUST` wording that
every scenario subfolder carries a README, and its lines 714 to 719 record that
the wording was strengthened from "should" precisely because scenario
directories had shipped with no Explainability carrier at all. The four items
that section requires are the four numbered sections below.

Two documents are cited here by section number and are never restated. The
byte-encoding contract -- field widths and offsets, the zoned-decimal sign
overpunch table, the implied decimal, the line-ending and trailing-newline rules
-- lives in `tests/fixtures/README.md` sections 3.1 through 3.5 and 5.1, 5.5 and
5.6. The conventions shared by all ten scenarios in this folder -- three record
files per scenario, the deliberate absence of any account or card record here,
LF-only endings, 7-bit ASCII, the label spellings, the availability annotation --
live in [`../README.md`](../README.md). Reproducing either contract would create
a second copy to drift out of step with the first.

---

## 1. Scenario intent

This scenario reaches the create arm of the transaction-category-balance
decision. That arm is reachable on exactly one condition: no row exists for the
key the posting program composes. The fixture expresses that condition as an
absent row.

**Read the folder name carefully. `zero_balance` means that no accumulated
balance exists yet for this key -- the absence of a row. It does not mean a row
whose balance is zero.** The distinction is the entire point of the directory,
and the name invites the opposite reading. A reader who takes it the wrong way
will helpfully add a row with a zero balance, the scenario will still pass
whatever it is pointed at, and it will silently be testing the update arm
instead. Section 5 records why a zero-valued row takes the other branch.

Its counterpart is [`../happy_path`](../happy_path), which seeds a pre-existing
row of `+100.00` and so exercises the **update** arm. The two scenarios are one
matched pair covering the two halves of a two-way branch, and they are built so
that the branch is the only variable: measured with a byte comparison, this
scenario's `dailytran.txt` and `transact.txt` are byte-identical to
`happy_path`'s, and the folder index records the same measurement at its section
1.3. Everything about the input record is held constant; only the presence of a
category-balance row moves.

Keeping the pair separate matters for the target and not only for the baseline.
The specification carries the create-versus-update branch as a business rule
whose parity is to be preserved, and it expresses the branch in the target as an
**upsert**. An upsert collapses two statements into one, so a test that asserts
only the balance an upsert leaves behind cannot report which arm ran. Both paths
have to stay separately distinguishable and separately asserted, which is why
this directory exists as a second scenario rather than as a variation of the
first.

---

## 2. The exact business rule it exercises

The rule is the category-balance decision in
[`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl), reached from
`2000-POST-TRANSACTION` at line 424, which performs it at line 440. Every line
number below was read in the file before being written here.

| Step | Line | What the program does |
|---|---|---|
| the paragraph opens | 467 | `2700-UPDATE-TCATBAL.` |
| the key is composed | 469-471 | `XREF-ACCT-ID`, then `DALYTRAN-TYPE-CD`, then `DALYTRAN-CAT-CD`, each moved into its part of the read key |
| the flag is preset to not-create | 473 | `MOVE 'N' TO WS-CREATE-TRANCAT-REC` |
| the row is read | 474 | `READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD` |
| the absent row is detected | 475-478 | the `INVALID KEY` arm displays the key it could not find and the word `Creating`, then line 478 sets the flag to `'Y'` |
| an absent row is explicitly not a failure | 481 | `IF TCATBALF-STATUS = '00' OR '23'` treats found and not-found alike as acceptable, before line 484 selects the abend path for anything else |
| the fork | 495-499 | `IF WS-CREATE-TRANCAT-REC = 'Y'` performs the create paragraph at line 496, otherwise the update paragraph at line 498 |
| the create arm, taken here | 503-510 | `2700-A-CREATE-TCATBAL-REC.` initialises the record at 504, moves the three key parts at 505-507, adds the transaction amount to a freshly-zeroed balance at 508, and `WRITE`s a new row at 510 |
| the update arm, not taken here | 526-528 | `2700-B-UPDATE-TCATBAL-REC.` adds the amount to the balance already in the record at 527 and `REWRITE`s it at 528 |

Line 473 and line 478 are the only two writes to the flag, and line 495 reads
nothing else, so the branch is decided entirely by whether the read at line 474
found a row.

Line 481 is the load-bearing detail. Status `23` is the not-found status, and the
program names it beside `00` as an acceptable outcome. A missing row is therefore
ordinary control flow in this program rather than an error condition, which is
what makes "no row" a legitimate scenario to build a fixture around instead of a
fault to be avoided.

### 2.1 The key that is probed, and why it is predictable

The key this scenario leaves unpopulated is account `00000000007`, transaction
type `01`, category `0001`.

Two of its three parts come from the transaction record itself. Lines 470 and 471
take the type code and the category code straight out of the record being posted,
and `dailytran.txt` carries `01` at positions 17-18 and `0001` at positions
19-22. The remaining part, the account identifier at line 469, is the account the
transaction's card resolves to through the cross-reference lookup, which is
`00000000007` for the card in this record.

Assumptions: this is what makes an absent row a reliable trigger rather than a
coincidence. Because the program derives two thirds of the probe key from bytes
this fixture supplies, the key is fixed by the fixture and known before the
program runs -- so "no row on that key" is a property this directory can
guarantee by shipping no rows at all, rather than a hope that some unrelated row
fails to collide. Had the key been derived from state outside these files, an
empty `tcatbal.txt` would prove nothing, because a row could arrive on the probed
key from elsewhere.

The same three values are corroborated outside this folder. The reference tree's
committed expected output for this scenario,
`tests/golden/posting/zero_balance/tcatbal.expected`, carries `00000000007`,
`01` and `0001` in the three key fields the `CVTRA01Y` layout places at positions
1-11, 12-13 and 14-17. That file is a measurement of the baseline's own output,
not a comparison this module performs.

---

## 3. The expected outcome

- One category-balance row on the key (`00000000007`, `01`, `0001`) is
  **created**. It did not exist, so nothing is updated.
- Its resulting balance is the transaction amount alone: **`504.77`**. The create
  arm adds the amount at line 508 onto a balance that line 504 has just
  initialised, so there is no prior value for it to accumulate onto.
- No reject row is produced: the record clears validation, so nothing lands in
  `ledger.transaction_rejects`. That table is a target of this scenario and not a
  file in it -- the folder index's section 2.3 records why the reject contract
  adds no fourth file here.
- The update arm at line 498 does not run.

### 3.1 Why the result is separately assertable from the sibling's

The contrast with the matched scenario is what makes each arm provable:

| Scenario | Prior row | Arm taken | Resulting balance |
|---|---|---|---|
| `zero_balance` | none | create, line 496 | `504.77` |
| `happy_path` | `+100.00` | update, line 498 | `604.77` |

Both figures are measured. `happy_path/tcatbal.txt` carries `+100.00` on this
same key, and `100.00` plus the `+504.77` this scenario's `dailytran.txt` carries
is `604.77`. The two outcomes differ, so an assertion on the resulting balance
distinguishes the arms on its own -- which is the property an upsert
implementation would otherwise take away.

A second, independent discriminator is available and is worth preferring where a
test can express it: a created row and a seeded row differ in their non-key bytes
as well as in their balance. `tests/fixtures/README.md` section 5.6 states the
convention the seed follows in the 22 bytes after the balance, and the create arm
does not populate that trailing area from a fixture at all -- line 504 builds the
record in storage. Row presence before the run is therefore also assertable:
before, no row exists on the key; after, exactly one does. That check cannot be
satisfied by an update no matter what balance results.

### 3.2 Money is exact fixed point

`504.77` is an exact decimal value at every hop and is never an approximate
binary one. `V1__ledger.sql` declares
`ledger.transaction_category_balances.balance` as `NUMERIC(11,2)` at its line
726, and the transaction amount that feeds it as `NUMERIC(11,2)` on both
`ledger.transactions` and `ledger.daily_transactions`. That migration's own
reasoning at its lines 720-725 records why the two operands must carry the same
precision and scale: the baseline adds one to the other at line 508 on the create
path and at line 527 on the update path, so no approximate type may appear
between them. On the wire the value travels as a string, so that no client parses
it into a binary floating-point number and loses the cent.

### 3.3 What consumes this scenario

Annotated `[present]` or `[planned]` per `tests/fixtures/README.md` section 9.3,
so that nothing not yet authored is described as though it existed.

- [`TransactionFixtureContractTest`](../../../java/com/carddemo/transaction/fixtures/TransactionFixtureContractTest.java)
  -- **[present]**, and it reads bytes from this directory. It asserts that this
  scenario's `tcatbal.txt` is empty at its line 221, that this scenario's
  `dailytran.txt` occupies 351 bytes at its line 228, and that this scenario uses
  the same seed card as the rest of the folder at its line 198. Its
  category-balance key test at lines 239-241 deliberately omits this scenario,
  which is correct: there is no row here to carry a key.

  Refactoring Rationale: this entry was authored as `[planned]` on the premise
  that the module held no consumer of these bytes, and that premise was measured
  false before it was written down. The annotation exists to stop a document
  claiming a future artifact already exists; claiming a present one is merely
  planned is the same error pointed the other way, and it is worse here because
  it would tell a maintainer that the zero-byte file has no executable guard when
  in fact one asserts it directly. The folder index's own section 5 anticipated
  this, recording that a test loading a record file is the single change that
  would need to update it.
- A behavioural test that runs the category-balance fork and asserts that the row
  was created rather than updated -- **[planned]**. The present consumer holds the
  fixture's bytes to what this document states about them; it does not execute the
  posting logic, so the arm itself is not yet asserted by anything.
- [`application-test.yml`](../../application-test.yml) -- **[present]**, the test
  profile two directories up. It supplies the schema resolution and the migration
  pointer that make an assertion on these rows reproducible. Note that it sets no
  clock property, deliberately: its lines 175-178 record that determinism for a
  processing timestamp is supplied as a bean through `Clock.fixed`, because the
  shared timestamp formatter takes a clock as a parameter and refuses an ambient
  one.
- [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql) --
  **[present]**. It creates the four `ledger` tables these records load into.

No golden-output tree covers this module, no comparison against one is claimed
here, and this document states no return code.

---

## 4. Fixture bytes and governance

### 4.1 Per file: record width, record count, line ending

| File | Copybook | Record width | Records | Line ending | Bytes on disk |
|---|---|---|---|---|---|
| `dailytran.txt` | `CVTRA06Y` | 350 | 1 | LF, one trailing newline | 351 |
| `transact.txt` | `CVTRA05Y` | 350 | 1 | LF, one trailing newline | 351 |
| `tcatbal.txt` | `CVTRA01Y` when populated | not applicable | **0** | none | **0** |

Every figure above was measured on this branch. Across the two non-empty files
the carriage-return count is zero and the tab count is zero, and neither carries
a byte outside 7-bit ASCII.

**`dailytran.txt` -- the input driver.** The record the run posts, at the offsets
`tests/fixtures/README.md` section 5.1 fixes for the 350-byte
`DALYTRAN-RECORD`:

| Field | Positions | Value |
|---|---|---|
| `DALYTRAN-ID` | 1-16 | `0000000000683580` |
| `DALYTRAN-TYPE-CD` | 17-18 | `01` |
| `DALYTRAN-CAT-CD` | 19-22 | `0001` |
| `DALYTRAN-AMT` | 133-143 | `0000005047G`, which is `+504.77` |
| `DALYTRAN-CARD-NUM` | 263-278 | `4859452612877065` |
| `DALYTRAN-ORIG-TS` | 279-304 | `2022-06-10 19:27:53.000000` |
| `DALYTRAN-PROC-TS` | 305-330 | 26 spaces |
| `FILLER` | 331-350 | 20 spaces |

The type and category values in rows two and three are the two thirds of the
probe key that section 2.1 describes; the amount in row four is the value the
created balance ends up carrying.

Assumptions: the blank processing timestamp is correct here and must not be
filled in. The baseline does not receive that field, it mints it -- line 437
obtains a timestamp and line 438 moves it into the posted record, while line 436
copies the originating timestamp straight across from this feed. Matching that,
`ledger.daily_transactions.proc_ts` is declared nullable, at line 463 of
`V1__ledger.sql`, and a blank fixed-width field decodes to a null. The
originating timestamp at positions 279-304 is deliberately not blank by the same
logic: it is supplied input rather than derived output, and it is the value the
expiration comparison reads.

**`transact.txt` -- the posted projection.** Positions 1-304 mirror
`dailytran.txt` field for field. That equality is exact rather than approximate,
and it is exact for a stated reason: lines 425-436 of `CBTRN02C` are twelve
consecutive one-to-one `MOVE DALYTRAN-x TO TRAN-x` statements -- identifier, type
code, category code, source, description, amount, merchant identifier, merchant
name, merchant city, merchant zip, card number, originating timestamp -- with
nothing reformatted, rounded or recomputed in transit. Measured on this branch,
every position at which the two files differ falls inside positions 305-330, and
no position outside that range differs at all.

That one differing field is `TRAN-PROC-TS`, carrying the 26-character literal
`2022-07-18 00:00:00.000000`.

Within the field, 25 of its 26 bytes differ rather than all 26. Position 315 holds
a space in both files, because the separator between the date and the time in that
literal is itself a space and the daily record's field is entirely spaces. The
distinction is recorded because "the files differ across 305-330" and "the files
differ at every byte of 305-330" are not the same claim, and only the first is
true. An assertion written as a byte-range comparison over the field is therefore
correct, while one counting 26 changed bytes is not.

Assumptions: it has to carry a real value, because `ledger.transactions.proc_ts`
is declared `NOT NULL` at line 247 of `V1__ledger.sql` while
`ledger.daily_transactions.proc_ts` is nullable at line 463. That asymmetry
between two records whose copybooks declare the field at the identical `PIC
X(26)` width is the reason these are two files rather than one, and the folder
index's section 3.2 is where the decision is recorded. The instant itself is
traceable rather than invented: `app/jcl/INTCALC.jcl` line 22 reads
`EXEC PGM=CBACT04C,PARM='2022071800'`, and it is the only business-date
injection in the baseline batch chain -- `app/jcl/POSTTRAN.jcl` line 23 invokes
`CBTRN02C` with no `PARM` at all, so posting supplies no date of its own to
borrow.

Assumptions: this record is assembled at a layout's offsets rather than copied
from a seed row, because no seed row for it exists. `app/data/ASCII/` holds
exactly nine files -- and none of them is named `transact.txt`. The reason is
structural: the posted-transaction master is an **output** of this program,
written at line 564 of `CBTRN02C` inside the `2900-WRITE-TRANSACTION-FILE`
paragraph that opens at line 562, and it is never an input to it. So the bytes
are laid out per `CVTRA05Y`, which the folder index's section 2 measures as
field-for-field identical to `CVTRA06Y` in picture and order -- that measurement
is what licenses using one record's offsets for the other, and it is why a
projection of the daily record lands correctly in the posted record's fields.

**`tcatbal.txt`** -- see section 4.3, which is the paragraph that matters most in
this document.

### 4.2 Provenance attestation

The three items `tests/fixtures/README.md` section 10.3 requires, and that the
folder index restates as a folder-level obligation at its section 7.

1. **The account-number and identity bytes are synthetic and seed-derived.** The
   card number `4859452612877065`, the account identifier `00000000007` and the
   merchant identifier `800000000` come from the published AWS CardDemo
   demonstration data. `dailytran.txt` is byte-identical to record 1 of
   [`app/data/ASCII/dailytran.txt`](../../../../../../../app/data/ASCII/dailytran.txt),
   measured on this branch rather than asserted. This directory ships no
   category-balance row at all, so no identity byte originates from
   [`app/data/ASCII/tcatbal.txt`](../../../../../../../app/data/ASCII/tcatbal.txt);
   that seed is cited here only for the layout and the byte value section 5
   discusses, and its record 1 sits on seed key `00000000001` / `01` / `0001`.
2. **They represent no real person and no real account.** Every account number,
   card number, merchant name and address byte in this directory is a
   demonstration value from that published seed. None is a credential, and none
   identifies a real person or a real account.
3. **Which business-rule fields were reshaped away from the seed value.** In
   `dailytran.txt`, none: the record is the seed row unchanged. In
   `transact.txt`, one field -- the processing timestamp at positions 305-330,
   which the daily seed record leaves blank and this file fills with the literal
   named in section 4.1. Its other 324 bytes are not reshaped either, being the
   projection of that same seed record described above; what section 4.1 records
   about them is that the *file* has no seed of its own to copy, so they are
   positioned by the `CVTRA05Y` layout rather than lifted from a
   posted-transaction extract. In `tcatbal.txt` the reshaping is an **omission
   rather than an edit**: the seed's record 1 was not copied, no substitute row
   was authored, and no byte of it was changed into something else. That omission
   is the scenario.

### 4.3 `tcatbal.txt` is a zero-byte file, not a file holding a blank line

**`tcatbal.txt` in this directory is a genuine zero-length file. It contains no
bytes whatsoever -- not one newline, not one space. It is emphatically not a file
containing one blank line.**

The distinction is mechanical, not pedantic. `tests/fixtures/README.md` section
3.3, at its lines 176 to 184, establishes both halves of it: a single trailing
newline per record makes a file's line count equal its record count, and an empty
line is a zero-length "record" that will fail fixed-width parsing. A file holding
one newline therefore presents **one malformed record** to a reader expecting 50
bytes, where a file holding nothing presents **no records**. Those are two
different inputs, and they take two different paths -- one is a parse failure, the
other is the not-found condition this scenario is built on. A single stray byte
converts this directory from a test of the create arm into a test of how a loader
reacts to a corrupt record.

The form is an established convention rather than an oversight, and the
precedent was measured: in the reference tree,
`tests/fixtures/posting/zero_balance/tcatbal.txt` and
`tests/fixtures/posting/empty_input/dailytran.txt` are both genuinely 0 bytes.
The folder index records the folder-level consequence at its section 3.3 -- every
file here ends with a single line feed except the two that hold no bytes at all
to terminate.

---

## 5. Why an empty file rather than a zero-valued row

Alternatives Considered: three forms of `tcatbal.txt` could satisfy a reading of
this directory's name, and they exercise three different things. Only the first
reaches the create arm.

1. **A true zero-byte file. Chosen.** No row exists on the composed key, so the
   read at line 474 takes its `INVALID KEY` arm, line 478 sets the flag to `'Y'`,
   and the fork at line 495 takes its true branch -- the `PERFORM` at line 496,
   entering the create paragraph that opens at line 503. This is the only one of
   the three forms under which line 503 executes at all.
2. **A file containing one blank line. Rejected.** This is not an empty file. Per
   section 3.3 of the master document it presents a zero-length record that fails
   fixed-width parsing, so what it exercises is a loader's tolerance of a
   malformed record. That is a legitimate question and it is not this one; the
   branch under test would never be reached, because the run would not get as far
   as a lookup.
3. **A 50-byte row whose balance is `0000000000{`. Rejected.** This is the trap
   the directory's name sets, and it inverts the scenario. Such a row **exists**,
   so the read at line 474 succeeds, the flag keeps the `'N'` set at line 473, and
   the fork at line 495 takes its false branch -- the `PERFORM` at line 498,
   entering the **update** paragraph that opens at line 526 -- proving nothing
   whatever about line 503 and duplicating what `happy_path` already covers. A row
   whose balance is zero and an absent row are two different scenarios; this
   directory is the second one.

   The trap is closer than it looks, which is why it is written down rather than
   left as obvious. `0000000000{` is not an invented value: it is exactly what
   record 1 of the seed `app/data/ASCII/tcatbal.txt` carries in the balance field
   the `CVTRA01Y` layout places at positions 18-28. So the default move
   everywhere else in this folder -- copy the seed row -- is precisely the move
   that would destroy this scenario. The folder index's section 4.2 records that
   the trailing `{` is a positive-zero overpunch and therefore a different byte
   from an ASCII `0`; a positive zero is a signed value and not an unsigned one.
   Neither of those facts rescues a present row from taking the wrong branch,
   because line 495 tests only whether the read found something.

Assumptions: this directory depends on five properties of the program, and it
stops testing what it claims if any one of them changes.

- `WS-CREATE-TRANCAT-REC` is preset to `'N'` at line 473 and set to `'Y'` at line
  478 on the not-found path only. Those are its only two writes before the fork.
- The fork at line 495 dispatches on that flag alone, issuing the create `PERFORM`
  at line 496 and the update `PERFORM` at line 498 and testing nothing else.
- The create arm at line 503 initialises the record at 504, moves the three key
  parts at 505-507, adds the transaction amount to the freshly-zeroed balance at
  508 and writes a new row at 510 -- so a created row's balance is the amount and
  nothing more.
- The update arm at lines 526-528 adds the amount to the balance already present
  and rewrites the row in place -- so its result depends on a prior value that
  this directory supplies none of.
- A not-found read is normal control flow rather than an error, because line 481
  accepts status `'00'` and `'23'` alike.

Trade-offs: a zero-byte file is indistinguishable, by inspection, from a file an
author forgot to fill in. No tool flags it, and a directory listing shows the
same `0` either way. That cost is accepted because the two alternative file
contents are worse -- one tests a parse failure and the other tests the opposite
branch -- and it is mitigated the only two ways it can be. This document is the
record that the emptiness is deliberate, and the emptiness is asserted executably
by the present consumer named in section 3.3, so restoring bytes to the file fails
a test rather than quietly changing what the directory proves.

Alternatives Considered: deleting the file instead of emptying it was weighed and
rejected on three independent grounds. The folder index's section 1 promises three
record files in every scenario, so an absent file would make that inventory false
-- and an inventory that cannot be trusted is the class of defect this tree of
documents exists to prevent. An absent file and an empty file are also different
inputs to whatever reads them: one is a missing resource, the other is an
immediate end of data, and only the second is the condition under test. The third
ground is measurable rather than argued. The present consumer resolves each
fixture as a classpath resource and, at its lines 56-58, throws
`IllegalStateException` with the message `fixture absent from the test classpath`
when the resource is null; a zero-byte file instead resolves normally and yields
an empty byte array, which is what its assertion at line 221 expects. Deleting
this file would therefore convert a passing assertion about emptiness into an
error about a missing resource -- two different failures reported for two
different reasons, only one of which is about the scenario.

Assumptions: `dailytran.txt` and `transact.txt` are held byte-identical to their
`happy_path` counterparts on purpose, so that the pair differs in exactly one
file. Deriving the input independently would have left two variables in play
between the two halves of the branch, and a difference in outcome could then be
attributed to either. Holding the input constant makes the presence or absence of
a category-balance row the sole cause of the difference, which is what the pair
is for. Reusing the sibling's fixed instant at positions 305-330 rather than
minting a per-scenario one follows from the same reasoning, and from the
traceability recorded in section 4.1.

---

## 6. Sources and scope

Every line and section number in this document was read in the file named before
being written here.

- **Business rule:**
  [`app/cbl/CBTRN02C.cbl`](../../../../../../../app/cbl/CBTRN02C.cbl) --
  `2000-POST-TRANSACTION` at line 424 with its twelve field moves at 425-436 and
  the derived timestamp at 437-438; `2700-UPDATE-TCATBAL` at 467 with the key at
  469-471, the flag at 473 and 478, the read at 474, the accepted statuses at 481
  and the fork at 495-499; `2700-A-CREATE-TCATBAL-REC` at 503-510;
  `2700-B-UPDATE-TCATBAL-REC` at 526-528; `2900-WRITE-TRANSACTION-FILE` at 562
  with its write at 564.
- **Record layouts:**
  [`app/cpy/CVTRA06Y.cpy`](../../../../../../../app/cpy/CVTRA06Y.cpy) lines 4-18,
  [`app/cpy/CVTRA05Y.cpy`](../../../../../../../app/cpy/CVTRA05Y.cpy) lines 4-18
  and
  [`app/cpy/CVTRA01Y.cpy`](../../../../../../../app/cpy/CVTRA01Y.cpy) lines 4-10.
- **Byte contract, by reference:**
  [`tests/fixtures/README.md`](../../../../../../../tests/fixtures/README.md)
  sections 3.1 through 3.5, 5.1, 5.5, 5.6, 9.1 at lines 695-719, 9.3 and 10.3.
- **Folder conventions, by reference:** [`../README.md`](../README.md) sections
  1, 1.3, 2, 3.2, 3.3, 4.2, 5, 7 and 8.
- **Documentation convention:**
  [`docs/CODE_DOCUMENTATION_STANDARD.md`](../../../../../../../docs/CODE_DOCUMENTATION_STANDARD.md),
  whose lines 209-226 fix the four rationale labels used above in the one
  permitted form -- plural, unparenthesised, colon retained and without emphasis
  markup, in Markdown as much as in code.
- **Seeds and reference measurements:** `app/data/ASCII/dailytran.txt` record 1,
  `app/data/ASCII/tcatbal.txt` record 1, `app/jcl/INTCALC.jcl` line 22,
  `app/jcl/POSTTRAN.jcl` line 23, and the reference tree's
  `tests/golden/posting/zero_balance/tcatbal.expected` and
  `tests/fixtures/posting/**` byte measurements.
- **Target schema:**
  [`V1__ledger.sql`](../../../../main/resources/db/migration/V1__ledger.sql)
  lines 247, 463, 720-726.

**Scope.** Everything under `app/**` and `tests/**` is reference-only and is
never modified, per specification sections 0.2.2 and 0.9.2. This document reads
those trees; it changes nothing in them. The baseline is the specification
against which the target is built, so where this document notes that the target
expresses the fork as an upsert, that is a recorded property of the target and
not a criticism of the program. The fixtures in this directory are derived
copies, reshaped only as section 4.2 records.
