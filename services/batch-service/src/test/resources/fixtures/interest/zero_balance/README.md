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

### 2.3 The disclosure-group read is a direct hit

`1200-GET-INTEREST-RATE` (`:415-440`) reads with the key composed at `:210-212` from
`ACCT-GROUP-ID`, `TRANCAT-CD` and `TRANCAT-TYPE-CD`. This folder's accounts carry
`ACCT-GROUP-ID = A000000000` and its `discgrp.txt` holds exactly that key, so the read returns status
`'00'`, `:422` accepts it, and the `IF DISCGRP-STATUS = '23'` test at **`:436`** is false -- so
`:437`'s `MOVE 'DEFAULT'` and the retry at `:438` never run. Master section 7.2.2 fixes the three
outcomes; this is the first.

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

**No fee record must appear.** `1400-COMPUTE-FEES` (`:518-520`) is an empty stub. Master section 7.2.4
forbids fabricating fee output for any scenario.

**The generated category must not be reconciled to the input.** `:483` writes the literal `'05'`, so
the output records carry `0005` while the input rows carry `0001`. An implementation that copied the
input category would produce `0001` in a field whose expected bytes are `0005`, and the diff would
point at a category rather than at a literal.

**Money must not pass through a floating-point value.** A zero balance is the one vector where a
`double` conversion is harmless, so this folder cannot detect such a violation -- stated so the
absence of a failure here is not read as evidence of compliance. Master section 5.5 forbids `float`,
`double` and JSON numbers in the money path and records that the prohibition is asserted by an
ArchUnit rule.

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

**The business date is an input parameter of the scenario, not a fixture byte.** No file in this
folder carries it; it arrives as the job's `PARM-DATE`, and `:476-480` copies its ten characters into
the transaction identifier with **no formatting whatsoever**. This folder's golden carries the **ISO**
token `2024-01-15`, producing `2024-01-15000001` and `2024-01-15000002`. Master section 8.2 measures
that all three interest **scenario** goldens use the ISO form while both interest **end-to-end**
goldens use the compact `2022071800` of `app/jcl/INTCALC.jcl:22`, and it requires the domain to
exercise both shapes so the passthrough is genuinely tested; the module discharges that by
parameterising one launch over both tokens rather than by splitting the two across scenario folders.
**Any expectation that hard-codes one format is wrong**, and the ten-character prefix is treated
throughout as an input parameter rather than as a derived value.

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

*This README is the mandatory Explainability carrier for the four record files in this directory,
required by master section 10 and by user-specified Rule 1. `config/rule1/rule1_gate.py` decides the
form of the rationale labels above, repository-wide and including Markdown, which is why they are
written plain rather than emphasised. `config/checkstyle/checkstyle.xml` limits its audit set to
`java`, so no linter reads this prose. Whether each rationale names a real consequence, and whether
every number and line citation is true, are review obligations no lexical gate can decide.*

---

## 10. What drives this corpus, and what reads it

This corpus is a **reference mirror**, not a driven input: no test in this module seeds a run from
`/fixtures/interest/`. What reads it is `BatchFixtureContractTest`, which enumerates this scenario
among all sixteen fixture trees and holds every file here to its declared geometry and to the values
that make the scenario discriminating -- so an edit is detected in this module even though no job
consumes the bytes. The end-to-end run for this rule belongs to the reference suite.

Assumptions: the distinction from `posting/**` is deliberate and is stated rather than left to
inference. That family IS driven -- `PostTransactionsJobParityIT` declares
`/fixtures/posting/` as its seed root and compares against `tests/golden/posting/<scenario>` -- so
one tree serves two purposes, and only there does an edit change what a run asserts.
