# `interest/zero_balance` -- a zero balance still writes a `0.00` interest transaction

> **Purpose.** Pin the distinction between a zero **balance** and a zero **rate**, which master
> section 7.2.2 names as two of the three disclosure-group outcomes and which are one byte apart in a
> fixture and a whole record apart in the output. Here the rate is `15.00` and both category balances
> are `+0.00`, so `1300-COMPUTE-INTEREST` runs, computes `0.00`, and **writes a transaction record
> anyway**. A scenario expecting *no record at all* would be driving a zero rate, which this folder
> deliberately does not.
>
> **Source of truth.** `app/cbl/CBACT04C.cbl` for the behaviour and `app/jcl/INTCALC.jcl` for the
> dataset and parameter contract, both reference-only and both cited below by line;
> `app/cpy/CVTRA01Y.cpy`, `app/cpy/CVACT01Y.cpy`, `app/cpy/CVTRA02Y.cpy` and `app/cpy/CVACT03Y.cpy`
> for the record layouts; the seed datasets under `app/data/ASCII/` for the bytes;
> `tests/golden/interest/zero_balance/` for the expected outputs; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document cites by section
> rather than restating (master section 1.3).
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. This whole file is pure ASCII for the
> reason that section gives.

**This README is the mandatory Explainability carrier for the four record files beside it.** Master
section 1.2 records why: a fixed-width record file cannot carry a comment of any kind, because every
byte position is meaningful and a comment on its own line is a physical row of the wrong length.
Master section 10 makes the artifact mandatory rather than courteous, and the section order below is
the one it fixes.

---

## 1. Purpose -- the condition this scenario pins

Two accounts, `00000000001` and `00000000002`, each with **one** category-balance row for type `01`
and category `0001` carrying **`+0.00`**, both accounts carrying the group id `A000000000`, and a
single disclosure-group row `A000000000 / 01 / 0001` at a rate of **`15.00`**.

Four things must hold at once:

- the disclosure-group read is a **direct hit**, so the `DEFAULT` fallback of `:436-439` is never
  entered -- that outcome belongs to `../default_fallback`;
- the rate is **non-zero**, so `:214` admits the calculation and the fee stub is reached;
- the computed interest is **`0.00`** and a transaction record is **written for each account
  anyway**, because `:214` gates on the rate and not on the product;
- both accounts' stored balances end **unchanged**, which section 3 shows is a consequence of three
  simultaneous no-ops rather than of the rewrite not happening.

Alternatives Considered: driving the same "no interest accrued" condition with a zero **rate**
instead of a zero balance. Rejected because it is a different outcome, not a different route to this
one: `:214` `IF DIS-INT-RATE NOT = 0` skips `1300-COMPUTE-INTEREST` **and** `1400-COMPUTE-FEES`
entirely, so no record is written at all and the transaction file stays empty. Master section 7.2.2
records that as its third outcome and this folder as the contrast to it. Both are worth having; this
one is the harder of the two to get right, because the expected output is a record whose amount is
zero rather than the absence of a record, and the two are indistinguishable if a test only asserts
that no interest was accrued.

Assumptions: the two-account shape is inherited from the domain and is not this scenario's own
choice. Master section 7.3 records that `:188-206` performs `1050-UPDATE-ACCOUNT` only on an account
change and that the trailing `ELSE PERFORM 1050-UPDATE-ACCOUNT` at `:220` is unreachable, so the last
account is never written back -- and that a single-account interest fixture can observe neither
behaviour. This folder mirrors `../happy_path`'s two-account shape for that reason, with account
`...001` deterministically the non-final one because `TCATBAL` is indexed and consumed in key order
(master section 3.12).

---

## 2. The business rule, cited by program and line

### 2.1 The formula, and why this vector is exact

**`app/cbl/CBACT04C.cbl:464-465`:**

```text
COMPUTE WS-MONTHLY-INT
 = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200
```

There is **no `ROUNDED` phrase**, so the baseline truncates. AAP Rule T4 requires the migrated Java
to multiply at full precision first and only then divide, with an explicit scale and rounding mode.

**This vector is neutral on both axes, and more completely so than the canonical one.**
`(0.00 x 15.00) / 1200` is `0` under either order of operations, and `0` needs no rounding at all, so
multiply-first and divide-first agree and truncation and half-up agree. Master section 7.2.1 makes
the same point about the canonical `1000.00` vector, which yields `12.5000` exactly; here the
degeneracy is total. **This folder therefore proves nothing about the ordering rule and does not
claim to.** What it does prove is that a zero product does not suppress the record, which no
non-degenerate vector can show.

### 2.2 The gate is on the rate, not on the product

`:213-217`:

```cobol
                  PERFORM 1200-GET-INTEREST-RATE
                  IF DIS-INT-RATE NOT = 0
                    PERFORM 1300-COMPUTE-INTEREST
                    PERFORM 1400-COMPUTE-FEES
                  END-IF
```

`DIS-INT-RATE` is `15.00` here, so the condition at **`:214`** is true and both paragraphs run.
`1300-COMPUTE-INTEREST` (`:462-470`) computes the monthly interest at `:464-465`, adds it to
`WS-TOTAL-INT` at `:467` -- adding zero -- and unconditionally performs `1300-B-WRITE-TX` at `:468`.
**There is no test on `WS-MONTHLY-INT` anywhere between the computation and the write.** That is the
whole rule this folder pins.

The two cases the gate separates, side by side, so that "no interest accrued" is never read as one
condition with two spellings:

| Case | `DIS-INT-RATE` | `:214` `NOT = 0` | `1300-COMPUTE-INTEREST` | `1400-COMPUTE-FEES` | Asserted output |
|---|---|---|---|---|---|
| **this folder** -- zero balance | `15.00` | **true** | **runs**, yields `0.00` | reached, empty stub | one record per category row, `TRAN-AMT` = `0000000000{` |
| zero rate -- contrast only, **not shipped as data** | `0.00` | **false** | **skipped** | skipped | **no record at all**, transaction file empty |

Because `:214` encloses `:215` and `:216` together, a zero rate skips the fee paragraph as well as the
interest one. The two rows are therefore different outcomes of the program, not two routes to one
outcome, and no single fixture can occupy both.

Assumptions: `:214` is a **numeric** comparison, so the migrated predicate is
`rate.compareTo(BigDecimal.ZERO) != 0` and **not** `!rate.equals(BigDecimal.ZERO)`. `BigDecimal.equals`
compares scale as well as unscaled value, so it reports `0.00` and `0` as different objects while
`compareTo` reports them equal -- and a rate arriving as `0.00` from a `NUMERIC(6,2)` column is exactly
the shape that reaches this predicate. Choosing `equals` would admit the calculation for a genuinely
zero rate and write the records the second row above forbids, which is the zero-rate outcome produced
under the zero-rate condition's own name and therefore invisible to any assertion phrased as "interest
was not accrued".

### 2.3 The disclosure-group read is a direct hit

`1200-GET-INTEREST-RATE` (`:415-440`) reads with the key composed at `:210-212` from
`ACCT-GROUP-ID`, `TRANCAT-CD` and `TRANCAT-TYPE-CD`. This folder's accounts carry
`ACCT-GROUP-ID = A000000000` and its `discgrp.txt` holds exactly that key, so the read returns status
`'00'`, `:422` accepts it, and the `IF DISCGRP-STATUS = '23'` test at **`:436`** is false -- so
`:437`'s `MOVE 'DEFAULT'` and the retry at `:438` never run. Master section 7.2.2 fixes the three
outcomes; this is the first.

**The order the key is assembled in is not the order it occupies on disk, and the difference is a real
authoring hazard.** The physical key is declared at `:79-81` as group id `X(10)`, then transaction type
`X(02)`, then transaction category `9(04)` -- which is the byte order `discgrp.txt` must be written in
and the order section 5.3 tabulates. The three `MOVE` statements that populate it run in a **different**
order, `:210` group id, then `:211` **category**, then `:212` **type**:

```cobol
MOVE ACCT-GROUP-ID TO FD-DIS-ACCT-GROUP-ID
MOVE TRANCAT-CD TO FD-DIS-TRAN-CAT-CD
MOVE TRANCAT-TYPE-CD TO FD-DIS-TRAN-TYPE-CD
```

Each `MOVE` names its own target field, so the assembled key is correct regardless of the order the
statements appear in; sequence matters to a reader, not to the program. It matters here because reading
`:210-212` top to bottom and transcribing that sequence into a record layout writes the group id, then
the category, then the type -- which for this folder's codes yields `A000000000000101` where the key is
`A000000000010001`. Both are sixteen bytes, both keep the group id intact, and they differ in exactly
**two** byte positions, the twelfth and the fourteenth. That row misses the key and takes the `DEFAULT`
fallback, and at a zero balance the fallback rate still produces `0.00`, so nothing in the output marks
the mistake. Author the row from `:79-81`, never from `:210-212`.

Assumptions: `discgrp.txt`'s byte order is taken from the **declaration** at `:79-81` rather than from the
statement sequence at `:210-212`, because a `MOVE` names its destination while a record layout is
positional. The two orders disagree, so which one an author follows is load-bearing even though the
program itself is indifferent to it.

### 2.4 The control break, and the rewrite that changes nothing

`:188-206` is the loop. For each category-balance row it compares `TRANCAT-ACCT-ID` against
`WS-LAST-ACCT-NUM` at `:194` and, when they differ and this is not the first row (`:195`), performs
`1050-UPDATE-ACCOUNT` at `:196` for the **previous** account. It then resets `WS-TOTAL-INT` at
`:200`, remembers the new account at `:201`, and reads the account and cross-reference rows at
`:202-205`.

`1050-UPDATE-ACCOUNT` (`:350-356`) does **three** things, and all three are no-ops on this data:

| Statement | Line | Effect here |
|---|---|---|
| `ADD WS-TOTAL-INT TO ACCT-CURR-BAL` | `:352` | adds `0.00`, so the balance is unchanged |
| `MOVE 0 TO ACCT-CURR-CYC-CREDIT` | `:353` | the field is already `+0.00` |
| `MOVE 0 TO ACCT-CURR-CYC-DEBIT` | `:354` | the field is already `+0.00` |
| `REWRITE FD-ACCTFILE-REC FROM ACCOUNT-RECORD` | `:356` | writes bytes identical to those read |

So the rewrite for account `...001` **does happen** and is **unobservable**. Section 3 draws the
consequence: this scenario cannot distinguish the baseline's missing final-account flush from a
target that flushes it, because both accounts' expected bytes are the same either way. That is a real
limitation of this folder and is stated rather than left for a reader to discover.

Assumptions: **the rewrite firing and the rewrite changing something are two different facts, and this
folder holds only the first.** The single most likely misreading of this scenario's output is that
account `...001` was never written back, because its persisted bytes equal its input bytes. It was
written back: the control break at `:194` and `:196` reaches `1050-UPDATE-ACCOUNT` for it, and what that
paragraph demonstrably does here is the **cycle reset** at `:353` and `:354` rather than the balance
addition at `:352`. Both cycle buckets already hold `00000000000{` in this folder's input, so the reset
writes the value they already carry and `:356` persists a record identical to the one it read. Any
assertion phrased as "the account row changed" therefore fails on correct behaviour, and any assertion
phrased as "the rewrite did not happen" passes on it -- which is why the property this folder can carry
is the accrual arithmetic and the record generation, and `../happy_path` is where the rewrite becomes
observable.

### 2.5 The generated transaction

`1300-B-WRITE-TX` (`:473-515`) builds the record:

| Field | Line | Value here |
|---|---|---|
| `TRAN-ID` | `:474-480` | `ADD 1 TO WS-TRANID-SUFFIX` **before** use, then `STRING PARM-DATE, WS-TRANID-SUFFIX` -- `10 + 6 = 16` exactly, so the first id ends `000001` |
| `TRAN-TYPE-CD` | `:482` | `01` |
| `TRAN-CAT-CD` | `:483` | the literal `'05'` into a `PIC 9(04)` field, so the bytes are **`0005`** -- **regardless of the input category**, which is `0001` here. Master section 7.2.4: do not reconcile it to the input |
| `TRAN-SOURCE` | `:484` | `System` in `X(10)`, so `System` plus four spaces |
| `TRAN-DESC` | `:485-489` | `STRING 'Int. for a/c ' ACCT-ID`, 24 characters, leaving the remaining 76 bytes of the `X(100)` field **untouched** |
| `TRAN-AMT` | `:490` | `WS-MONTHLY-INT`, which is `0.00` |
| `TRAN-MERCHANT-ID` | `:491` | `MOVE 0`, so `000000000` |
| merchant name, city, zip | `:492-494` | `MOVE SPACES` -- explicitly space-filled |
| `TRAN-CARD-NUM` | `:495` | `XREF-CARD-NUM` from the cross-reference read at `:205` |
| `TRAN-ORIG-TS`, `TRAN-PROC-TS` | `:496-498` | one clock read moved into **both** fields, so they are necessarily equal as well as volatile |

`1400-COMPUTE-FEES` (`:518-520`) is an **empty stub** -- its body is the single comment
`* To be implemented`, followed by `EXIT`. **No fee record is produced**, and master section 7.2.4
forbids fabricating one.

Four of those rows encode decisions that look like mistakes until their mechanism is known, so each is
recorded here rather than left to the table.

Assumptions: **`TRAN-CAT-CD` is `0005` and must not be reconciled to the input.** `:483` moves the
**literal** `'05'` into a `PIC 9(04)` field, so the generated record carries `0005` whatever the driving
category was -- `0001` in this folder. The field is therefore a constant of the program, not a copy of
the input, and an implementation that propagated the input category would write `0001` where `0005` is
expected. The resulting diff points at a category code and reads as a data problem, which is the wrong
place to look: the cause is a literal four lines above.

Assumptions: **`TRAN-DESC` is NUL-padded while the three merchant fields are space-padded, and the two
mechanisms are different statements.** `:485-489` `STRING`s 24 characters into an `X(100)` field, and
`STRING` writes only what it is given -- it leaves the remaining 76 bytes of the receiving field exactly
as they were, which over this program's storage is `0x00`. `:492-494` by contrast `MOVE SPACES`, which
fills its targets to their declared width with `0x20`. Master section 6.3 holds the job-dependent split
for the whole tree. The consequence is specific and expensive: space-padding `TRAN-DESC` shifts no field
and changes no value, so the record still parses, every decoded field still reads correctly, and the
comparison fails on 76 padding bytes of a field whose **value is right** -- the hardest class of failure
to localise, because nothing that is wrong is visible.

Assumptions: **both timestamps are volatile, they are necessarily equal, and the comparison layout is
`INTTRAN` rather than `TRAN`.** `:496` performs the clock read **once** and `:497` and `:498` move that
one result into `TRAN-ORIG-TS` and `TRAN-PROC-TS`, so the two fields cannot differ. That is the opposite
of the posting domain, where the originating stamp arrives from the input and is asserted rather than
masked, and it is why master section 8.1 gives the interest domain its own layout with **both** stamps
flagged. Comparing this folder's records under `TRAN` leaves a live clock reading in the originating
field and fails on every run, at a different byte each time.

Assumptions: **the absence of a fee record is an expectation to assert, not an omission to fill.**
`1400-COMPUTE-FEES` is reached on this path -- `:214` admits it because the rate is non-zero -- and it
returns having done nothing, because its body is one comment. A fee record must therefore be asserted
**absent**; fabricating one to make the scenario look complete would invent output the program does not
produce.

---

## 3. Returns -- the expected outcome

The return code is **0**. `CBACT04C` has no reject stream and no counter-driven grading: nothing in
the program assigns `RETURN-CODE` at all, so a completed run leaves it at zero. Master section 7.1.6's
graded rubric belongs to the COBOL parity suite and never to a Java build gate.

**The arithmetic, per category-balance row.**

| Account | `TRAN-CAT-BAL` | `DIS-INT-RATE` | `(balance x rate) / 1200` | `WS-MONTHLY-INT` | Encoded |
|---|---|---|---|---|---|
| `00000000001` | `+0.00` | `15.00` | `(0.00 x 15.00) / 1200` = `0` | **`+0.00`** | `0000000000{` |
| `00000000002` | `+0.00` | `15.00` | `(0.00 x 15.00) / 1200` = `0` | **`+0.00`** | `0000000000{` |

`WS-TOTAL-INT` accumulates `0.00` for each account, which is what makes `:352` a no-op.

**Two transaction records are written**, one per category-balance row, each 350 bytes:

| Field | Offset | Account `...001` | Account `...002` |
|---|---:|---|---|
| `TRAN-ID` | 0 | `2024-01-15000001` | `2024-01-15000002` |
| `TRAN-TYPE-CD` | 16 | `01` | `01` |
| `TRAN-CAT-CD` | 18 | `0005` | `0005` |
| `TRAN-SOURCE` | 22 | `System` + 4 spaces | `System` + 4 spaces |
| `TRAN-DESC` | 32 | `Int. for a/c 00000000001` + **76 NUL** | `Int. for a/c 00000000002` + **76 NUL** |
| `TRAN-AMT` | 132 | `0000000000{` = **`+0.00`** | `0000000000{` = **`+0.00`** |
| `TRAN-MERCHANT-ID` | 143 | `000000000` | `000000000` |
| merchant name, city, zip | 152, 202, 252 | all spaces | all spaces |
| `TRAN-CARD-NUM` | 262 | `9680294154603697` | `0923877193247330` |
| `TRAN-ORIG-TS` | 278 | 26 spaces -- **normalised**, see section 6 | same |
| `TRAN-PROC-TS` | 304 | 26 spaces -- **normalised**, see section 6 | same |
| `FILLER` | 330 | **20 NUL** | **20 NUL** |

**Two padding bytes here are NUL rather than blank, and both have mechanisms.** The 76 bytes after
`Int. for a/c 00000000001` are `0x00` because `STRING` at `:485-489` writes only the 24 characters it
was given and leaves the tail of the receiving field untouched over storage that is low values --
master section 6.3, which also records that space-padding them would change 76 bytes on a field whose
value is correct. The trailing `FILLER` at offset 330 is `0x00` because no `MOVE` in the program ever
touches it -- master section 6.1's output-TRAN row.

**Both account balances end unchanged.**

| Account | Before | After | Encoded | Why |
|---|---|---|---|---|
| `00000000001` | `+194.00` | **`+194.00`** | `00000001940{` | the control break fired and rewrote the row, adding `0.00` and zeroing two already-zero fields -- section 2.4 |
| `00000000002` | `+158.00` | **`+158.00`** | `00000001580{` | no further control break followed it, so it was never rewritten at all -- master section 7.3 |

**The two rows are unchanged for two different reasons, and this folder cannot tell them apart.**
That is the honest limitation: in `../happy_path` the same pair reads `+206.50` and `+158.00`, so the
rewrite is visible on the first account and its absence on the second. Here both are `0.00` movements,
so the observable output is identical whether the final account is flushed or not. **A test using this
folder must not claim to exercise the final-account flush**, and the divergence registered in
`docs/architecture/cobol-to-service-traceability.md` is exercised by `../happy_path` instead.

Assumptions: the golden corpus is corroboration, never the derivation. In
`tests/golden/interest/zero_balance/`, `return_code.expected` is the single byte `0`,
`acctdat.expected` is **602 bytes** -- two 300-byte rows plus two `LF` -- holding `+194.00` and
`+158.00` with both group ids `A000000000`, and `transact.expected` is **702 bytes**, two 350-byte
records plus two `LF`, with the two identifiers and the two `0.00` amounts above. The arithmetic comes
from this folder's bytes and the lines cited in section 2.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**No record must be omitted because its amount is zero.** This is the folder's central negative. An
implementation that suppressed a zero-amount interest transaction -- an easy and superficially
sensible optimisation -- writes an empty transaction file here, which is the expected output of a
zero-**rate** scenario rather than of this one. Nothing between `:465` and `:500` tests
`WS-MONTHLY-INT`.

**The `DEFAULT` fallback must not run.** `:436` tests `DISCGRP-STATUS = '23'`, and this folder's
disclosure-group row matches the composed key exactly, so the status is `'00'`. An implementation that
fell back regardless would still find a rate -- the harness seeds seventeen `'DEFAULT   '` rows per
master section 7.2.2 -- so the interest would still be some number and, at a zero balance, still
`0.00`. **The wrong outcome would therefore be invisible in this folder's output**, which is why
`../default_fallback` exists as a separate scenario with a blank group id and why this document states
the direct hit as an expectation rather than assuming it.

**A missing `'DEFAULT'` row must not be reachable, and it is not.** `1200-A-GET-DEFAULT-INT-RATE`
(`:443-460`) treats anything other than status `'00'` as fatal at `:452-459`, so a missing `DEFAULT`
row is an abend rather than a zero rate. This folder never enters that path.

Assumptions: the rate lookup has **three** outcomes, this folder owns the first, and the three are not
interchangeable. The direct hit returns `'00'` and is what this folder's data produces. The miss returns
`'23'`, which `:422` admits as success before `:436` re-reads under the literal `'DEFAULT'` written by
`:437` into the group-id component alone -- that is `../default_fallback`. The third is the abend above,
and it is reachable only from the second: the retry's `READ` at `:444` carries **no `INVALID KEY` clause**
at all, unlike the first read at `:416-420`, and `:446` accepts **only** `'00'`, so a fallback whose
`'DEFAULT'` row is absent terminates the run rather than yielding a zero rate. That asymmetry between the
two reads is why the harness seeds the `'DEFAULT'` rows unconditionally even for scenarios like this one
that never read them, and why this folder states the direct hit as an expectation instead of assuming it:
at a zero balance every one of the three outcomes that does not abend still reports `0.00`.

**No fee record must appear.** `1400-COMPUTE-FEES` (`:518-520`) is an empty stub. Master section 7.2.4
forbids fabricating fee output for any scenario.

**The generated category must not be reconciled to the input.** `:483` writes the literal `'05'`, so
the output records carry `0005` while the input rows carry `0001`. An implementation that copied the
input category would produce `0001` in a field whose expected bytes are `0005`, and the diff would
point at a category rather than at a literal.

**Money must never leave exact fixed point.** A zero balance is also the one vector on which a lapse
would be undetectable: zero is representable exactly in IEEE-754 binary, so a round trip through a
binary type returns the same value and every expected byte in this folder still matches. **This folder
therefore cannot detect that class of violation at all**, and the absence of a failure here is not
evidence of compliance. Master section 5.5 fixes the single money contract for the whole tree, names
the binary types and JSON numbers it excludes from the money path, and records that the exclusion is
asserted by an ArchUnit rule rather than left to review.

**No abend occurs.** Every `9999-ABEND-PROGRAM` site on this path is status-guarded: the five opens
(`0000-TCATBALF-OPEN` through `0400-TRANFILE-OPEN` at `:182-186`), the category-balance read
(`:342-345`), the account read, the cross-reference read (`:408-411`), the disclosure-group read
(`:431-434`), the default retry (`:455-458`), the account rewrite, the transaction write (`:510-513`)
and the five closes at `:224-228`. Note that `:422` accepts `'23'` alongside `'00'` from the
disclosure-group read, which is how the fallback coexists with the abend guard.

---

## 5. Parameters -- the files in this folder and their byte geometry

### 5.1 Inventory

| File | Copybook | RECLN | DDNAME / `ASSIGN` | Organization | Key |
|---|---|---:|---|---|---|
| `tcatbal.txt` | `app/cpy/CVTRA01Y.cpy` | 50 | `TCATBALF` | INDEXED | `TRAN-CAT-KEY`, offset 0, length 17 |
| `acctdata.txt` | `app/cpy/CVACT01Y.cpy` | 300 | `ACCTFILE` | INDEXED | `ACCT-ID`, offset 0, length 11 |
| `discgrp.txt` | `app/cpy/CVTRA02Y.cpy` | 50 | `DISCGRP` | INDEXED | `DIS-GROUP-KEY`, offset 0, length 16 |
| `cardxref.txt` | `app/cpy/CVACT03Y.cpy` | 50 | `XREFFILE` | INDEXED | `XREF-ACCT-ID`, offset **25**, length 11 -- the **alternate** path |
| `README.md` | -- | -- | -- | -- | this file |

The record lengths are the summed widths of master section 5.1, never a `RECLN` banner.

**The cross-reference is read by account id here, not by card number.** Master section 4.3 fixes the
two access paths and why a fixture satisfying one does not automatically satisfy the other:
`app/jcl/INTCALC.jcl` lines 29 to 30 mount the base cluster and lines 31 to 32 mount a second DD,
`XREFFIL1`, pointing at the alternate-index PATH; `app/cbl/CBACT04C.cbl` line 38 declares
`ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID`, defined `WITH DUPLICATES`. `:204-205` moves
`TRANCAT-ACCT-ID` into that key and reads. So this folder's `cardxref.txt` must make **every
category-balance row's account** resolvable at offset 25, which it does -- one row per account, in the
same order. The migrated target of that path is the secondary index `idx_card_xref_account_id`.

Assumptions: `WITH DUPLICATES` means the account-keyed path may legitimately return more than one row,
so this fixture's one-row-per-account shape is a property of its own data rather than of the access
path. Master section 4.3 requires that to be said: a test relying on exactly one row coming back is
relying on this file, not on the index.

### 5.2 On-disk sizes and line endings

| File | Size | Records | Bytes per record | `CR` bytes | Trailing newline |
|---|---:|---:|---:|---:|---|
| `tcatbal.txt` | **102** | 2 | 50 | 0 | exactly one `LF` |
| `acctdata.txt` | **602** | 2 | 300 | 0 | exactly one `LF` |
| `discgrp.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |
| `cardxref.txt` | **102** | 2 | 50 | 0 | exactly one `LF` |

Every row is exactly its declared width and every file ends in a single `LF`, per master sections 3.8
and 3.9, with **zero `CR` bytes** in the folder.

```bash
# WHAT: assert the byte geometry, and additionally assert the three relations this scenario turns on
#       -- that the disclosure-group key matches both accounts' group ids, that every category
#       balance is zero, and that the rate is not. Run from this directory.
# WHY : Assumptions: this scenario has three independent ways to stop testing what it claims, none of
#       which changes a byte count. A group id that stops matching converts it silently into the
#       DEFAULT-fallback scenario (master section 7.2.3), a non-zero balance turns the expected
#       amounts into numbers this document does not state, and a zero rate suppresses both records
#       entirely (master section 7.2.2 outcome 3) -- and that last one is the very outcome this folder
#       is the contrast to. Checking all three at authoring time is what keeps the three apart.
python3 - <<'PY'
OVERPUNCH = {"{": 0, "A": 1, "B": 2, "C": 3, "D": 4, "E": 5,
             "F": 6, "G": 7, "H": 8, "I": 9}
def cents(raw):
    text = raw.decode("ascii")
    return int(text[:-1] + str(OVERPUNCH[text[-1]]))
EXPECTED = {"tcatbal.txt": (102, 50, 2), "acctdata.txt": (602, 300, 2),
            "discgrp.txt": (51, 50, 1), "cardxref.txt": (102, 50, 2)}
rows_of = {}
for name, (size, width, count) in EXPECTED.items():
    raw = open(name, "rb").read()
    rows = [r for r in raw.split(b"\n") if r]
    rows_of[name] = rows
    widths = sorted({len(r) for r in rows})
    print(name, "size", len(raw), "expected", size, "| rows", len(rows), "expected", count,
          widths, "| CR", raw.count(b"\r"),
          "| OK" if len(raw) == size and raw.count(b"\r") == 0
          and widths == [width] and len(rows) == count else "| MISMATCH")
group = rows_of["discgrp.txt"][0][0:10].decode("ascii")
groups = {r[112:122].decode("ascii") for r in rows_of["acctdata.txt"]}
print("disclosure group", repr(group), "account group ids", groups,
      "| OK direct hit" if groups == {group} else "| MISMATCH the DEFAULT fallback would run")
balances = [cents(r[17:28]) for r in rows_of["tcatbal.txt"]]
rate = cents(rows_of["discgrp.txt"][0][16:22])
print("category balances in cents", balances, "rate in hundredths", rate,
      "| OK zero balance, non-zero rate" if set(balances) == {0} and rate != 0
      else "| MISMATCH")
accounts = [r[0:11].decode("ascii") for r in rows_of["acctdata.txt"]]
resolvable = [r[25:36].decode("ascii") for r in rows_of["cardxref.txt"]]
keyed = [r[0:11].decode("ascii") for r in rows_of["tcatbal.txt"]]
print("accounts", accounts, "resolvable at offset 25", resolvable, "category keys", keyed,
      "| OK every balance resolves" if set(keyed) <= set(resolvable) & set(accounts)
      else "| MISMATCH a balance row cannot resolve")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based; money is signed zoned with the sign folded into the last byte and no byte for
the decimal point, per master sections 3.3 and 3.7.

Assumptions: **`0000000000{` is a positive zero, and it is the single most misread byte string in this
folder.** It is ten `0` characters followed by one `{` -- **eleven bytes**, filling an `S9(09)V99` field
whose eleven digit positions carry no byte for the sign and no byte for the point -- and it decodes to
**`+0.00`**, not to eleven plain zeros and not to a malformed value. The trailing `{` is the sign folded
onto the low-order digit, so it simultaneously encodes "positive" and the digit `0`; master section 3.3
holds the full overpunch table and is not reproduced here. Two consequences follow and both bear on
this scenario specifically. A reader who sees `{` where a digit was expected may take the row for
corrupt and reshape it, which would change the one value the scenario exists to hold. And a decoder that
strips non-digits instead of decoding the overpunch reads `0000000000` as ten digits, arrives at the
same `0` by a route that is wrong, and then reports a **negative** balance as positive on the very next
fixture it meets -- which is why the check in section 5.2 decodes the sign byte rather than casting the
field.

`tcatbal.txt`, two `TRAN-CAT-BAL-RECORD` rows, in key order:

| Row | `TRANCAT-ACCT-ID` 0 | `TRANCAT-TYPE-CD` 11 | `TRANCAT-CD` 13 | `TRAN-CAT-BAL` 17 | Decoded | `FILLER` 28 |
|---|---|---|---|---|---|---|
| 1 | `00000000001` | `01` | `0001` | `0000000000{` | **`+0.00`** | 22 ASCII `'0'` |
| 2 | `00000000002` | `01` | `0001` | `0000000000{` | **`+0.00`** | 22 ASCII `'0'` |

`acctdata.txt`, two `ACCOUNT-RECORD` rows:

| Field | Offset | Width | Account `...001` | Account `...002` |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | `00000000001` | `00000000002` |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | `Y` |
| `ACCT-CURR-BAL` | 12 | 12 | `00000001940{` = `+194.00` | `00000001580{` = `+158.00` |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000020200{` = `+2020.00` | `00000061300{` = `+6130.00` |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000010200{` = `+1020.00` | `00000054480{` = `+5448.00` |
| `ACCT-OPEN-DATE` | 48 | 10 | `2014-11-20` | `2013-06-19` |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `2025-05-20` | `2024-08-11` |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2025-05-20` | `2024-08-11` |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` = `+0.00` | `00000000000{` = `+0.00` |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` = `+0.00` | `00000000000{` = `+0.00` |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | `A000000000` |
| `ACCT-GROUP-ID` | 112 | 10 | **`A000000000`** -- reshaped, section 9 | **`A000000000`** -- reshaped, section 9 |
| `FILLER` | 122 | 178 | 178 spaces | 178 spaces |

**`ACCT-ADDR-ZIP` and `ACCT-GROUP-ID` both read `A000000000`, ten bytes apart, and only the second was
reshaped.** Master section 7.2.3 records that coincidence as the easiest confusion in this tree and
its consequence as silent: writing the group id into the zip slice leaves the record the right length,
leaves the group id blank, and produces a run that takes the `DEFAULT` fallback and still yields a
plausible figure -- which at a zero balance is `0.00` either way, so in **this** folder the mistake
would be entirely invisible in the output. That is why the check in section 5.2 reads offset 112
explicitly.

Assumptions: **this scenario depends on the keyed disclosure-group read returning status `'00'`, and that
depends entirely on the ten bytes at `[112:122]`.** `:210` moves `ACCT-GROUP-ID` into the key's group-id
component, so those ten bytes are what the read resolves on; measured, the raw seed rows carry **ten
spaces** there while carrying `A000000000` at `[102:112]`. **Verify this field by offset, never by
searching for the literal**, because in the reshaped fixture the literal occurs twice, ten bytes apart,
and matching the first occurrence finds the ZIP.

Alternatives Considered: copying the two account rows from the seed verbatim, which is the default and
the more faithful-looking option, and is what the provenance discipline of section 9 otherwise argues
for. Rejected because it does not produce this scenario: a blank group id makes the composed key miss,
`:422` admits the resulting status `23`, `:436` fires, and the run resolves its rate through the
`'DEFAULT'` retry instead of the direct hit -- which is `../default_fallback`, a scenario that already
exists and is deliberately kept distinct. The reshape is confined to that one field precisely so the
rejection costs as little provenance as possible, and section 9 attests to it as the folder's only
business-rule change.

Neither credit limit, neither cash limit and none of the six dates is read by `CBACT04C`; they are
seed values carrying no expectation. The credit limits differ markedly between the two accounts, which
is the seed's own doing.

`discgrp.txt`, one `DIS-GROUP-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DIS-ACCT-GROUP-ID` | 0 | 10 | `A000000000` | matches both accounts |
| `DIS-TRAN-TYPE-CD` | 10 | 2 | `01` | matches both category rows |
| `DIS-TRAN-CAT-CD` | 12 | 4 | `0001` | matches both category rows |
| `DIS-INT-RATE` | 16 | 6 | `00150{` | **`+15.00`** |
| `FILLER` | 22 | 28 | 28 ASCII `'0'` | -- |

`cardxref.txt`, two `CARD-XREF-RECORD` rows:

| Row | `XREF-CARD-NUM` 0 | `XREF-CUST-ID` 16 | `XREF-ACCT-ID` 25 | `FILLER` 36 |
|---|---|---|---|---|
| 1 | `9680294154603697` | `000000001` | `00000000001` | 14 spaces |
| 2 | `0923877193247330` | `000000002` | `00000000002` | 14 spaces |

These two card numbers are the ones the generated transaction records carry at offset 262, moved by
`:495`, so they are asserted output as well as input.

**Three different `FILLER` bytes appear in this folder, all by measurement.** `acctdata.txt` and
`cardxref.txt` pad with `0x20` SPACE, while `tcatbal.txt` and `discgrp.txt` pad with ASCII `'0'`,
`0x30`. Master section 6.1 measures all four, and master section 6.2 records the category-balance and
disclosure-group rows as **the two** that contradict the general rule of section 3.2, with the
measured byte winning -- applying the general rule would produce rows differing from seed and golden
in 22 and 28 bytes respectively, every one of them padding. The output records add a third byte,
`0x00`, for the two reasons section 3 gives.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` rows are 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| `tcatbal.txt` is `LF`-terminated where its seed is `CRLF` | master sections 3.8, 3.9 | A stray `0x0D` absorbed into the 22-byte `'0'` `FILLER` would push a record to 51 bytes and fail the load with every field value correct |
| `ACCT-GROUP-ID` carries `A000000000` where both seed rows carry ten spaces | master sections 7.2.3, 11.1 | The reshaping master section 7.2.3 requires of any direct-hit interest fixture. Attested in section 9 |

No departure applies to `discgrp.txt`, whose single row is the seed's own including its `'0'` padding.

Trade-offs: the line-ending normalisation of `tcatbal.txt` accepts a byte-level difference from its seed
in exchange for a file the loader can read. Measured, `app/data/ASCII/tcatbal.txt` is 2599 bytes with
**49 `CR` and 50 `LF`** -- the only `CRLF` seed of the four this folder derives from, and not even
uniformly so, since one row lacks the `CR`. The loader treats one physical line as one fixed-length
record, so a surviving `0x0D` is absorbed into the trailing 22-byte `FILLER` and presents a **51-byte**
row against a `RECLN` of 50: one byte past the declared width, on a record whose every content field is
correct, failing the load rather than the comparison and pointing at a length rather than at a line
ending. The compromise is that this file is not byte-identical to its seed. It is accepted because the
difference is confined to the terminator -- **no content byte moves and no field value changes**, as
section 9 records -- and because the alternative, carrying the `CR` through, produces a corpus that
cannot be loaded at all. Master sections 3.8 and 3.9 impose the rule tree-wide; this row records what it
costs here.

Assumptions: `cardxref.txt` is authored at the copybook's **full 50-byte width** while its seed rows are
**36 bytes**, and that difference is a completion rather than an edit. `CVACT03Y` declares a trailing
`FILLER X(14)` after the eleven-byte account id that ends at offset 36, so the copybook sums to 50 and
the seed simply stops early; master section 3.10 rules for the copybook width and gives the reasoning the
whole tree follows. This scenario depends on the ruling twice over. `:204-205` reads this file through the
alternate key at offset **25**, which sits inside the first 36 bytes and so would resolve at either
width -- meaning a 36-byte row would load, resolve, and yield correct interest, leaving the width mistake
invisible in the output. The fourteen appended bytes are **spaces**, matching the measured padding of the
account record rather than the ASCII `'0'` of the category-balance and disclosure-group rows, per master
section 6.1.

---

## 6. Determinism -- and what the blank timestamps mean here

**Both 26-byte timestamp fields in the expected output are 26 spaces, and in a golden those spaces are
the product of normalisation** -- not input data. Master section 8.1 requires this to be stated every
time a blank timestamp appears, because the byte pattern is identical to a genuinely unprocessed
input stamp and the meaning cannot be recovered from the bytes.

For the interest domain **both** fields are volatile and both are masked: `:496` performs
`Z-GET-DB2-FORMAT-TIMESTAMP` **once** and `:497-498` move that one value into `TRAN-ORIG-TS` and
`TRAN-PROC-TS`, so the two are necessarily **equal to each other** as well as clock-derived. In the
goldens they appear as 52 contiguous spaces. This is the opposite of the posting domain, where the
originating stamp is copied from the input and is therefore asserted rather than masked. Master
section 8.1 records that the Java side expresses the distinction with two layouts -- `TRAN`, which
normalises only the processing stamp, and `INTTRAN`, a derivation of it with the originating stamp
additionally flagged. **A comparison of this folder's expected records names the `INTTRAN` layout**;
comparing under `TRAN` would leave a clock reading in the originating field and fail on every run.

**No input file in this folder contains a timestamp field at all.** The account, category-balance,
disclosure-group and cross-reference layouts carry none -- the account's three `X(10)` values are
dates, not timestamps -- so the "genuine input data" reading of a blank stamp does not arise here in
either direction.

**The business date is an input parameter of the scenario, not a fixture byte, and never a clock
reading.** No file in this folder carries it. It reaches the program as `PARM-DATE`, a `PIC X(10)` in the
LINKAGE SECTION at `:178` received through `PROCEDURE DIVISION USING EXTERNAL-PARMS` at `:180`, and
`:476-480` `STRING`s those ten characters beside the `PIC 9(06)` counter of `:173` `DELIMITED BY SIZE`
into a `PIC X(16)` identifier -- `10 + 6 = 16`, filled exactly, with **no formatting whatsoever** and no
date-parsing code anywhere in the program. `:474` increments the counter **before** the `STRING`, so the
first generated identifier always ends `000001`. That injection is what makes a rerun reproducible: the
**only** legitimate clock read on this path is `:496` `Z-GET-DB2-FORMAT-TIMESTAMP`, which stamps the two
record timestamps described above and never the business date.

This folder's expectation carries the **ISO** token `2024-01-15`, producing `2024-01-15000001` and
`2024-01-15000002`. Master section 8.2 measures that all three interest **scenario** expectations use the
ISO form while both interest **end-to-end** expectations use the compact `2022071800` that
`app/jcl/INTCALC.jcl:22` passes as `PARM='2022071800'`, and it requires the domain to exercise both
shapes so that the passthrough is proven rather than assumed. **Any expectation that hard-codes one
format is wrong**, and the ten-character prefix is treated throughout as an input parameter rather than
as a derived value.

Assumptions: the prefix above is ISO because that is the token the run this folder drives actually
supplies, not because ISO is canonical -- there is no canonical form. `CalculateInterestJobTest`
reproduces each committed scenario, this one among them, under its separated ten-character token and
compares the result against `tests/golden/interest/zero_balance/`, so an expectation written here in the
compact form would describe an output no run produces. The compact form is exercised in the same class by
a case that supplies **both** committed layouts to one launch and asserts each reaches every collaborator
unaltered, which is how the module discharges master section 8.2's both-shapes requirement -- by
parameterising a launch rather than by splitting the two shapes across scenario folders, so that neither
shape becomes a property of a directory. **Do not "align" this prefix to the compact form, and do not
align a compact expectation to this one.** Either edit silently converts a passthrough assertion into a
format assertion.

Assumptions: `services/batch-service/README.md` describes the compact token as `yyyyMMdd` followed by the
literal `00` and "not an ISO date". **Read as a description of what `app/jcl/INTCALC.jcl:22` happens to
pass, that is accurate; read as a required format for the migrated Java, it is contradicted and must
neither be followed nor propagated.** Master section 8.2 records the same guard. The stronger reading
fails against the evidence on both sides: it contradicts the opaque-token contract `dto/BusinessDate`
implements -- ten characters preserved exactly, no default instance, no clock-reading factory, a raw
accessor kept separate from any calendar parsing -- and it would invalidate every ISO-prefixed scenario
expectation in the domain, this folder's included. Rendering a parsed date under that reading is the
specific defect both committed layouts exist to catch: applied to the compact token it emits sixteen
characters of the wrong shape, which is the failure mode hardest to see because the length still checks
out.

Everything else holds by construction: every byte here is literal, there is no random identifier and
no environment-derived string, and each test provisions and tears down its own workspace, per master
section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/CalculateInterestJob` with `service/InterestCalculationService`, and five
target-side contracts constrain what this folder may expect:

- **`ACCRUAL_ROUNDING` is `RoundingMode.DOWN`**, which is the migrated expression of the baseline's
  missing `ROUNDED` phrase at `:464-465`. This vector cannot distinguish it from any other mode, for
  the reason section 2.1 gives.
- **`INTEREST_TYPE_CODE` is `01` and `INTEREST_CATEGORY_CODE` is `05`**, with a separate
  four-digit rendering for the `PIC 9(04)` field, so the generated `0005` is a declared constant
  rather than a formatting accident -- and `TRANSACTION_CATEGORY_CODE_DIGITS` is why it is four
  characters wide.
- **`INTEREST_SOURCE` is `System` and `INTEREST_DESCRIPTION_PREFIX` is `Int. for a/c `**, both
  verbatim including the prefix's trailing space, with the padding to each field's declared width
  belonging to the record mapper rather than to the constant.
- **`DEFAULT_ACCOUNT_GROUP` is `DEFAULT`**, reached through `defaultRateFor` rather than `rateFor`.
  This folder exercises `rateFor` only; `../default_fallback` is the folder for the other.
- **`dto/BusinessDate`** models an opaque ten-character token, preserved character for character,
  with no default instance and no clock-reading factory -- which is what makes the ISO prefix above a
  parameter rather than a format.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql),
which also seeds the seventeen `'DEFAULT   '` disclosure-group rows master section 7.2.2 names, space-
padded to `CHAR(10)`, idempotently. **This folder's disclosure-group row is additive on top of those**
and neither re-seeds nor alters them, per master section 11.3: a scenario owns only its own rows.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs; the oracle is
the reference-only `tests/golden/interest/zero_balance/` tree, read as the authority and never
written, and no golden is ever regenerated.

### 8.2 What this folder does not cover

Three properties of the domain are **outside** this scenario, and each is named because a reader could
otherwise assume the folder covers it:

- **the ordering rule of AAP Rule T4**, which needs a vector where multiply-first and divide-first
  disagree; master section 7.2.1 gives `0.43` at `15.00` as such a vector and records that neither the
  canonical `1000.00` nor -- a fortiori -- this `0.00` distinguishes them;
- **the final-account flush**, for the reason section 3 states: both accounts' expected bytes are the
  same whether or not the last one is written back;
- **the `DEFAULT` fallback**, which requires a blank group id and belongs to `../default_fallback`.

Trade-offs: the first of those three is a coverage gap this folder accepts rather than narrows, and the
reason is worth stating precisely because a zero vector looks like the safest possible test. Section 2.1
already records that `(0.00 x 15.00) / 1200` is neutral on both the ordering axis and the rounding axis;
what that buys is total insensitivity to how the migrated arithmetic is written, and what it costs is that
**no defect in that arithmetic can fail here.** A vector of the shape master section 7.2.1 specifies is
required instead. The compromise is accepted because the two properties trade against each other: the
same degeneracy that makes this folder unable to discriminate operand order is what makes it able to
prove that a zero product still produces a record, and no non-degenerate vector can prove that.

Assumptions: section 7 of the reference-only `tests/fixtures/interest/happy_path/README.md` is the
authority for a further, separately recorded divergence -- an observed end-to-end run of the compiled
baseline over the two-account interest shape whose integrated multi-account arithmetic departs from the
isolated per-row figures. It is referenced here rather than restated, and the reference is deliberately
figureless: **those amounts belong to the non-zero scenarios and must never be carried into this folder**,
where every expected amount is `0.00`. This folder is not evidence about that divergence in either
direction, because a zero accrual moves no balance and so cannot show a departure in one.

### 8.3 The sibling folders

`../happy_path` is the same two accounts, the same cross-reference, the same disclosure-group row and
the same group ids, with category balances of `+1000.00` instead of `+0.00` -- **the only difference
between the two folders' four files**. It yields `12.50` per account and a first-account balance of
`+206.50`, so it is where the rewrite becomes observable. `../default_fallback` is the same shape again
with the group ids left blank. Read together, the three folders cover master section 7.2.2's first two
outcomes and this folder's zero-product case, with the third outcome -- a zero **rate** -- documented
as the contrast rather than shipped as a fourth scenario.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived.** Every record traces to a published AWS
CardDemo sample seed row under `app/data/ASCII/`:

| File | Seed | Seed rows | Relationship |
|---|---|---|---|
| `tcatbal.txt` | `app/data/ASCII/tcatbal.txt` | 1, 2 | **byte-identical** once the `CR` is removed |
| `discgrp.txt` | `app/data/ASCII/discgrp.txt` | 1 | **byte-identical** |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | **49**, **3** | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 1, 2 | one business-rule field reshaped per row, below |

The cross-reference rows are rows **49** and **3** of their seed rather than rows 1 and 2, because
that file is ordered by card number: account `00000000001` appears there under card
`9680294154603697` and account `00000000002` under card `0923877193247330`.

**It represents no real person and no real account.** The seeds ship with the upstream open-source
project as fabricated demonstration data, and master section 11.1 carries the tree-level attestation
this scenario inherits. Identity and primary-account-number bytes are taken unchanged from the seed.

**Exactly one business-rule field is reshaped, in each of the two account rows.** `ACCT-GROUP-ID` at
`[112:122]` is `A000000000` where both seed rows carry **ten spaces**. Measured, that is a ten-byte
difference at one offset in each row, and it is the only difference between either row and its seed.
Master section 7.2.3 records why the reshaping is unavoidable for a direct-hit interest fixture: a
verbatim seed account row has a **blank** group id, so the composed key misses, status 23 fires, and
the `DEFAULT` fallback runs -- which is `../default_fallback`, not this scenario.

**The category balances are NOT reshaped, and that is worth stating explicitly.** `+0.00` is the seed's
own value in rows 1 and 2 of `app/data/ASCII/tcatbal.txt`, so this folder's defining condition is
inherited rather than authored. It is `../happy_path` that reshapes those two rows, to `+1000.00`, in
order to obtain a non-zero product. A reader who assumes the zero-balance scenario must be the
reshaped one has it exactly backwards.

No other field in any file here departs from its seed value: both account balances, both credit
limits, both cash limits, all six dates, both cycle amounts, both ZIPs, the disclosure-group row
including its rate, and both cross-reference rows are the seed's own. The two normalizations named in
section 5.4 -- the card-xref width and the category-balance line ending -- are width and line-ending
conformance, **not** business-rule field changes, so the two statements do not conflict.

Trade-offs: this folder reaches its condition with the smallest possible delta from the seeds -- one
field, twice, and that field forced by master section 7.2.3 -- which is the best provenance position
any interest scenario in this tree can occupy. The cost is the coverage gap section 8.2 names: a
scenario whose every expected amount is zero cannot distinguish rounding modes, operand order, or a
rewrite from its absence. The compromise is accepted because those three properties are covered by
`../happy_path` and by the discriminating vector master section 7.2.1 specifies, while the property
this folder covers -- that a zero product still produces a record -- is covered by nothing else.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string or
endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the COBOL
baseline or the parity oracle.

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**. `CalculateInterestJobTest` declares `FIXTURE_INTEREST_ROOT` as the
classpath prefix `fixtures/interest/`, names `acctdata.txt`, `cardxref.txt`, `discgrp.txt` and
`tcatbal.txt` as its `DRIVING_FIXTURES`, and loads each through
`getClassLoader().getResourceAsStream(...)` -- which resolves to these four files. It seeds the masters
from them, runs the interest job and asserts the outcome, so **an edit to these bytes changes what that
run asserts.** `BatchFixtureContractTest` additionally enumerates this scenario among all sixteen
fixture trees and holds every file here to its declared geometry and to the values that make the
scenario discriminating, so a layout mistake is caught before the job run reports a value difference.

Assumptions: this section said the exact opposite -- "a reference mirror, not a driven input: no test in
this module seeds a run from `/fixtures/interest/`" -- and the correction is recorded rather than merely
applied, because the withdrawn claim licensed precisely the edit it warned against everywhere else in
this tree. What produced it is worth naming: `CalculateInterestJobTest` cites the repository-root
`tests/fixtures/interest/` oracle tree repeatedly in its own prose, and additionally compares this tree
against it byte for byte to detect drift, so a reader auditing that class finds the oracle path in it
and can conclude that the oracle path is what it opens. It is not. A path named in a docstring is not a
path being opened, and this folder is opened.

Assumptions: the distinction from `posting/**` no longer exists in the direction this paragraph drew it.
Both families are driven -- `PostTransactionsJobParityIT` declares `/fixtures/posting/` as its seed root
and compares against `tests/golden/posting/<scenario>`, and `PostTransactionsJobTest` reads the same
four files under `fixtures/posting/` -- so an edit in either family changes what a run asserts. Master
section 1.5 holds the measured inventory for the whole tree and names the nine files, in `preflight/**`
and `export/**`, that no job opens.

---

*This README is the mandatory Explainability carrier for the four record files in this directory,
required by master section 10 and by user-specified Rule 1. Those four files can satisfy that rule
neither in themselves nor by inspection, for two independent reasons, and this document exists because
of both. **They admit no comment syntax:** every byte position is meaningful, a comment character inside
a record shifts every field after it, and a comment on its own line is a physical row of the wrong length
that the loader rejects -- master section 1.2. **And no mechanical gate reaches them:**
`config/checkstyle/checkstyle.xml` narrows its audit set to `java`, and `config/checkstyle/suppressions.xml`
suppresses `src/test/resources/fixtures/` outright as data rather than behaviour, while
`config/rule1/rule1_gate.py` governs the written form of rationale labels repository-wide and does read
Markdown, but excludes every path containing `/src/test/resources/fixtures/`. **So no gate reads this
prose either.** The plain, unemphasised label form is used regardless, because
`docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4 fix one written form repository-wide and a
Rule 1 audit finds a rationale by literal string search, which an emphasised label defeats. Whether each
rationale names a real consequence, and whether every byte value and line citation here is true, are
review obligations no lexical gate can decide -- which is why every number in this document was
re-derived from the four files and from the cited baseline lines rather than carried over from prose.*
