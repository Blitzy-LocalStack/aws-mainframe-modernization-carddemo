# `posting/reject_102_overlimit` -- one cent past the credit limit

> **Purpose.** Pin reject reason **102**: a transaction whose projected cycle balance exceeds the
> account's credit limit by **one cent** is refused with the message `OVERLIMIT TRANSACTION`, and the
> run reports the soft-warn tier. This is the exclusive half of the pair whose inclusive half is
> `posting/boundary_exact_limit`: the two folders differ in six bytes of one field, and between them
> they fix the comparison at `app/cbl/CBTRN02C.cbl:407` as `>=` rather than `>`.
>
> **Source of truth.** `app/cbl/CBTRN02C.cbl` for the behaviour and `app/jcl/POSTTRAN.jcl` for the
> dataset contract, both reference-only and both cited below by line; `app/cpy/CVTRA06Y.cpy`,
> `app/cpy/CVACT03Y.cpy`, `app/cpy/CVACT01Y.cpy` and `app/cpy/CVTRA01Y.cpy` for the record layouts;
> the seed datasets under `app/data/ASCII/` for the bytes;
> `tests/golden/posting/reject_102_overlimit/` for the expected outputs; and the tree-level
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

One daily transaction of **`+2065.01`** against account `00000000007`, whose credit limit is
**`+2065.00`**. Both current-cycle fields are `+0.00`, so the projected balance is the amount, and
the comparison is `2065.00 >= 2065.01` -- false by a cent.

Two properties make this folder more than the negation of its pair:

- **the margin is the smallest representable one.** Money in this record is `S9(09)V99`, scale 2, so
  one cent is the least significant unit the field can express. A fixture that exceeded the limit by
  a dollar would pass under a comparison that had been implemented with a tolerance, a rounding step,
  or a floating-point conversion; a one-cent excess does not.
- **the date is deliberately inside the expiration date**, so that reason 102 is the reason the
  reject stream carries. Master section 7.1.4 records that 103 overwrites 102 when a record trips
  both, and a scenario meaning to isolate 102 has to avoid that.

Alternatives Considered: reaching the excess by lowering the credit limit instead of raising the
amount. Rejected because `posting/boundary_exact_limit` is this folder's pair and the pair only works
if the two differ in one field: reshaping the limit here and the amount there would leave two fields
different between the folders, and a divergence would no longer localise. Keeping the account row
byte-identical to its seed in both folders is what makes the one-cent delta the only variable.

---

## 2. The business rule, cited by program and line

**`app/cbl/CBTRN02C.cbl:403-413`, inside the `NOT INVALID KEY` branch of `1500-B-LOOKUP-ACCT`.**

```cobol
     COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                         - ACCT-CURR-CYC-DEBIT
                         + DALYTRAN-AMT

     IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
         CONTINUE
     ELSE
         MOVE 102 TO WS-VALIDATION-FAIL-REASON
         MOVE 'OVERLIMIT TRANSACTION'
           TO WS-VALIDATION-FAIL-REASON-DESC
     END-IF
```

That is `:403-413`, reproduced faithfully. The projected balance is formed at `:403-405`, the guard
is at **`:407`**, the reason is assigned at **`:410`** and the message literal is at **`:411`** --
master section 7.1.1 gives both of the last two because a citation may reasonably point at either.

**The reject is produced by the `ELSE` of a passing guard, not by a failing test.** `:407` asks
whether the limit is at least the projected balance; the reject lives on the arm where it is not.
That phrasing matters for the target, where the same finding is expressed as the strict complement of
this guard rather than as a re-derived predicate.

**Reaching this line requires two successes first.** `1500-A-LOOKUP-XREF` must find the card (`:383`,
`:388`) and `1500-B-LOOKUP-ACCT` must find the account (`:395`, `:400`), because `:372-373` performs
the account lookup only while the reason is zero and `:403-405` sits inside the account read's
`NOT INVALID KEY` arm. A single fixture therefore cannot reach 102 without also demonstrating that
neither 100 nor 101 fired.

The rest of the walk, all in `app/cbl/CBTRN02C.cbl`:

| Step | Line | What happens for this record |
|---|---|---|
| Read the feed | `:204` | status `'00'`, so the loop body runs |
| Count it | `:206` | processed count becomes 1 |
| Cross-reference hits | `:382-383`, `:388` | card `4859452612877065` resolves to account `00000000007` |
| Account hits | `:394-395`, `:400` | account `00000000007` is read, so both boundary tests are evaluated |
| **Credit-limit test fails** | `:403-405`, **`:407`**, `:409-412` | `2065.00 >= 2065.01` is **false**, so reason 102 and its message are assigned |
| Expiration test **passes** | `:414-415` | `2024-12-13 >= 2022-06-10` is true, so `:417` does **not** run and 102 survives -- master section 7.1.4 |
| Take the reject path | `:211`, `:213-215` | the reason is not 0, so the reject counter is incremented and `2500-WRITE-REJECT-REC` runs |
| Build the reject | `:447-448` | `:447` moves the entire 350-byte `DALYTRAN-RECORD` verbatim; `:448` appends the 80-byte trailer |
| Write it | `:451` | one 430-byte record; `:452` requires status `'00'` |
| Grade the run | `:229-230` | the rejected count exceeds zero, so `MOVE 4 TO RETURN-CODE` |

**Nothing is posted.** `2000-POST-TRANSACTION` (`:424-444`) is reached only from `:212`, on the branch
this record does not take, so the category balance, the account and the transaction file are all
untouched -- including `ACCT-CURR-CYC-CREDIT`, which the projection read but which `:549` never adds
to.

---

## 3. Returns -- the expected outcome

The return code is **4**, the soft-warn tier -- `:229-230` moves 4 because the rejected count is one.
Master section 7.1.6 fixes that tier as a **fixture expectation value** belonging to the COBOL parity
suite's graded rubric, never to a Java build gate.

**The arithmetic, to the cent.**

| Quantity | Value | Source |
|---|---|---|
| `ACCT-CURR-CYC-CREDIT` | `+0.00` | `acctdata.txt` `[78:90]` |
| `ACCT-CURR-CYC-DEBIT` | `+0.00` | `acctdata.txt` `[90:102]` |
| `DALYTRAN-AMT` | `+2065.01` | `dailytran.txt` `[132:143]` |
| `WS-TEMP-BAL` = `0.00 - 0.00 + 2065.01` | **`+2065.01`** | `:403-405` |
| `ACCT-CREDIT-LIMIT` | `+2065.00` | `acctdata.txt` `[24:36]` |
| `2065.00 >= 2065.01` | **false**, by `0.01` | `:407` |

**The reject record**, one row of 430 bytes:

| Slice | Width | Contents |
|---|---:|---|
| `[0:350]` | 350 | the input `DALYTRAN-RECORD` **verbatim**, including card `4859452612877065` at `[262:278]` and the amount `0000020650A` at `[132:143]` |
| `[350:354]` | 4 | **`0102`** |
| `[354:430]` | 76 | **`OVERLIMIT TRANSACTION`** followed by 55 spaces |

`OVERLIMIT TRANSACTION` is 21 characters, so 55 spaces follow -- the padding `MOVE` supplies at
`:411`, per the mechanism master section 6.3 records for the trailer description.

**The rejected amount is recoverable from the reject stream**, at `[132:143]` of the verbatim image,
which is what lets a comparison confirm that the record refused was the one-cent-over record and not
some other. Master section 7.1.7 records that the oracle reads the **card** out of the same image at
`[262:278]`; the amount is at the offset master section 5.2 fixes.

**The code is `0102` on the wire and the integer `102` in storage.** `WS-VALIDATION-FAIL-REASON` is
`PIC 9(04)` at `app/cbl/CBTRN02C.cbl:181`, so the trailer carries four characters zero-padded. The
migrated `ledger.transaction_rejects.reason_code` column is `SMALLINT` and holds the integer. Master
section 7.1.2 fixes both representations and warns that an expectation must name which one it
asserts.

**Nothing else changes.**

| Output | Expected | Authority |
|---|---|---|
| Posted transaction records | **0** | `2900-WRITE-TRANSACTION-FILE` at `:562` is reached only from `:442` |
| `tcatbal.txt` after the run | **unchanged, byte for byte** at `+0.00` | `2700-UPDATE-TCATBAL` at `:467` is reached only from `:440` |
| `acctdata.txt` after the run | **unchanged, byte for byte** at `+193.00` | opened I-O at `:311`, but `2800-UPDATE-ACCOUNT-REC` at `:545` is reached only from `:441` |
| `cardxref.txt` after the run | **unchanged, byte for byte** | opened INPUT at `:275`; the job has no statement that writes it |
| Counters | 1 processed, 1 rejected | `:227-228` |

Assumptions: the golden corpus is corroboration, never the derivation. In
`tests/golden/posting/reject_102_overlimit/`, `return_code.expected` is the single byte `4`,
`dalyrejs.expected` is **431 bytes** -- 430 plus the single trailing `LF` of master section 3.9 --
`tranfile.expected` is **0 bytes**, and `acctdat.expected` and `tcatbal.expected` are byte-identical
to this folder's `acctdata.txt` and `tcatbal.txt` at 301 and 51 bytes. The unchanged-account
assertion is the one that catches a partial unit of work: a job that updated the account and then
declined to write the transaction would leave `+2258.01` there.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**The record must not post, and a cent is the whole margin.** An implementation reading the guard as
`>` posts this record: return code 0, one transaction record, an empty reject stream, an account
balance of `+2258.01` and a category balance of `+2065.01`. Every one of the four outputs changes, so
the failure is loud -- but its **cause** is a single character, and only the pairing with
`posting/boundary_exact_limit` shows which of the two folders was mis-specified.

**103 must not be reported instead of 102.** Master section 7.1.4 records that `:407-413` and
`:414-420` are two sequential unguarded `IF` blocks with no reason-code test between them, so a
record tripping both ends with **103** overwriting 102 and the trailer reads `0103` with the
expiration message. This fixture's originating date is `2022-06-10` against an expiration date of
`2024-12-13`, so the expiration test passes and nothing overwrites the reason. **A scenario isolating
102 must keep its date on or before expiration**, and this is that arrangement, stated because the
overwrite is the least obvious expectation in the domain.

**100 and 101 must not be reported.** The card is in the cross-reference, so `:385` cannot fire; the
resolved account is present in the master, so `:397` cannot fire. Reaching `:407` at all presupposes
both.

**Money must not pass through a floating-point value anywhere on this path.** This is the negative
this folder is uniquely placed to catch. `2065.01` is not representable exactly in binary floating
point, so a comparison performed in `double` can decide `2065.00 >= 2065.01` on the wrong side of a
rounding, and a conversion that goes through a `float` can lose the cent altogether and post the
record. Master section 5.5 forbids `float`, `double` and JSON numbers in the money path and records
that the prohibition is asserted by an ArchUnit rule rather than left to review; this fixture is the
data that would expose a violation of it.

**Code 109 must not be expected.** `:556` assigns it inside the `INVALID KEY` branch of the account
`REWRITE` at `:554-559`, on the posting path -- not taken here -- and writes no reject record. Master
section 7.1.1 fixes the persisted domain as exactly `{100, 101, 102, 103}` and records 109 as a dead
write.

**No abend occurs.** The reachable `9999-ABEND-PROGRAM` sites are all status-guarded: the six opens
(`:250`, `:268`, `:287`, `:305`, `:323`, `:341`), the feed read (`:363-366`), the reject write
(`:463`) and the six closes (`:596`, `:614`, `:633`, `:651`, `:669`, `:688`). An over-limit
transaction is a business reject, not an I/O condition, and `1500-B-LOOKUP-ACCT` has no abend site of
its own.

---

## 5. Parameters -- the files in this folder and their byte geometry

### 5.1 Inventory

| File | Copybook | RECLN | DDNAME / `ASSIGN` | Organization | Key |
|---|---|---:|---|---|---|
| `dailytran.txt` | `app/cpy/CVTRA06Y.cpy` | 350 | `DALYTRAN` | SEQUENTIAL | -- |
| `cardxref.txt` | `app/cpy/CVACT03Y.cpy` | 50 | `XREFFILE` | INDEXED | `XREF-CARD-NUM`, offset 0, length 16 |
| `acctdata.txt` | `app/cpy/CVACT01Y.cpy` | 300 | `ACCTFILE` | INDEXED | `ACCT-ID`, offset 0, length 11 |
| `tcatbal.txt` | `app/cpy/CVTRA01Y.cpy` | 50 | `TCATBALF` | INDEXED | `TRAN-CAT-KEY`, offset 0, length 17 |
| `README.md` | -- | -- | -- | -- | this file |

`ASSIGN` names and organizations are declared at `app/cbl/CBTRN02C.cbl:29-61`; the record lengths are
the summed widths of master section 5.1, never a `RECLN` banner. The cross-reference is read **by card
number** at offset 0 -- master section 4.3, since `app/jcl/POSTTRAN.jcl:32-33` mounts the base cluster
only. The reject dataset is an output with no fixture file here; its 430-byte geometry is proved six
ways in master section 5.4.

### 5.2 On-disk sizes and line endings

| File | Size | Records | Bytes per record | `CR` bytes | Trailing newline |
|---|---:|---:|---:|---:|---|
| `dailytran.txt` | **351** | 1 | 350 | 0 | exactly one `LF` |
| `cardxref.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |
| `acctdata.txt` | **301** | 1 | 300 | 0 | exactly one `LF` |
| `tcatbal.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |

One record plus a single trailing `LF` per file, per master sections 3.8 and 3.9, and **zero `CR`
bytes** in the folder.

```bash
# WHAT: assert the byte geometry, and additionally assert the one relation this scenario turns on --
#       that the amount exceeds the credit limit by exactly one cent. Run from this directory.
# WHY : Assumptions: an exclusive-boundary fixture fails silently in both directions. A cent low and
#       the record posts, a dollar high and the fixture no longer discriminates between a correct
#       comparison and a tolerant one, and in neither case does the geometry change. Asserting the
#       exact difference at authoring time is the only check that catches both. The arithmetic is
#       done in integer cents on purpose -- master section 5.5 forbids float in the money path, and
#       a checker that used one could not prove a one-cent difference it might itself have lost.
python3 - <<'PY'
OVERPUNCH = {"{": 0, "A": 1, "B": 2, "C": 3, "D": 4, "E": 5,
             "F": 6, "G": 7, "H": 8, "I": 9}
def cents(raw):
    text = raw.decode("ascii")
    return int(text[:-1] + str(OVERPUNCH[text[-1]]))
EXPECTED = {"dailytran.txt": (351, 350), "cardxref.txt": (51, 50),
            "acctdata.txt": (301, 300), "tcatbal.txt": (51, 50)}
for name, (size, width) in EXPECTED.items():
    raw = open(name, "rb").read()
    rows = [r for r in raw.split(b"\n") if r]
    widths = sorted({len(r) for r in rows})
    print(name, "size", len(raw), "expected", size, "| rows", len(rows), widths,
          "| CR", raw.count(b"\r"),
          "| OK" if len(raw) == size and raw.count(b"\r") == 0 and widths == [width]
          else "| MISMATCH")
amount = cents(open("dailytran.txt", "rb").read()[132:143])
limit = cents(open("acctdata.txt", "rb").read()[24:36])
print("amount cents", amount, "limit cents", limit, "excess cents", amount - limit,
      "| OK one cent over" if amount - limit == 1 else "| MISMATCH not a one-cent excess")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based; money is signed zoned with the sign folded into the last byte and no byte for
the decimal point, per master sections 3.3 and 3.7.

`dailytran.txt`, one `DALYTRAN-RECORD` -- this whole record is what the reject image reproduces
verbatim at `:447`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DALYTRAN-ID` | 0 | 16 | `0000000000683580` | -- |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `01` | -- |
| `DALYTRAN-CAT-CD` | 18 | 4 | `0001` | unsigned, plain digits |
| `DALYTRAN-SOURCE` | 22 | 10 | `POS TERM` + 2 spaces | -- |
| `DALYTRAN-DESC` | 32 | 100 | `Purchase at Abshire-Lowe` + 76 spaces | -- |
| `DALYTRAN-AMT` | 132 | 11 | **`0000020650A`** | **`+2065.01`** -- reshaped, section 9 |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | -- |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | -- |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | -- |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | -- |
| `DALYTRAN-CARD-NUM` | 262 | 16 | `4859452612877065` | resolves, so 100 cannot fire |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `2022-06-10 19:27:53.000000` | before expiration, so 103 cannot overwrite |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

**The overpunch byte is where the cent lives.** `0000020650A` differs from `boundary_exact_limit`'s
`0000020650{` in its **final byte only**: `{` encodes a trailing digit `0` with a positive sign and
`A` encodes a trailing digit `1` with a positive sign, per master section 3.3. The ten leading
characters are identical. That is worth stating because a reader scanning the two files by eye sees
the same digits and can conclude the fixtures are duplicates; the difference is one byte and it is
the whole scenario. It is also why the check in section 5.2 decodes rather than comparing text.

`cardxref.txt`: `XREF-CARD-NUM` `4859452612877065` at 0, `XREF-CUST-ID` `000000007` at 16,
`XREF-ACCT-ID` `00000000007` at 25, `FILLER` 14 spaces at 36.

`acctdata.txt`, one `ACCOUNT-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | `00000000007` | account 7 |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | active -- and unread by this job |
| `ACCT-CURR-BAL` | 12 | 12 | `00000001930{` | `+193.00` -- takes no part in `:407` |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000020650{` | **`+2065.00`** -- the limit `:407` tests |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000002640{` | `+264.00` -- unread by this job |
| `ACCT-OPEN-DATE` | 48 | 10 | `2012-10-12` | -- |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `2024-12-13` | after the feed date, so 103 cannot fire |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2024-12-13` | -- |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` | `+0.00` -- read by `:403` |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` | `+0.00` -- read by `:404` |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | -- |
| `ACCT-GROUP-ID` | 112 | 10 | 10 spaces | blank -- unread by this job |
| `FILLER` | 122 | 178 | 178 spaces | -- |

**`ACCT-CURR-BAL` is not the quantity the limit is compared against.** `:403-405` reads the two
current-cycle fields and the amount, and nothing else. An implementation comparing the limit against
the running balance would compute `193.00 + 2065.01 = 2258.01` -- also over the limit, so this
particular fixture would still reject, for the wrong reason and by a different margin. Master section
7.1.3's identity is what keeps the scenario readable, and it holds only because the two cycle fields
are zero.

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` line 11 spells it, preserved
verbatim because this is copybook-side naming; master section 9.3 confines the three spelling
corrections to target column names.

`tcatbal.txt`, one `TRAN-CAT-BAL-RECORD`: `TRANCAT-ACCT-ID` `00000000007` at 0, `TRANCAT-TYPE-CD`
`01` at 11, `TRANCAT-CD` `0001` at 13, `TRAN-CAT-BAL` `0000000000{` = `+0.00` at 17, and 22 ASCII
`'0'` of `FILLER` at 28. The row is present, is never reached, and is asserted unchanged.

**The `FILLER` bytes differ by measurement, not by inconsistency.** Three files pad with `0x20`
SPACE; `tcatbal.txt` pads with ASCII `'0'`, `0x30`. Both are the bytes master section 6.1 measures,
and master section 6.2 records the category-balance row as one of the two contradicting the general
rule of section 3.2, with the measured byte winning.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| `tcatbal.txt` is `LF`-terminated where its seed is `CRLF` | master sections 3.8, 3.9 | A stray `0x0D` absorbed into the 22-byte `'0'` `FILLER` would push the record to 51 bytes and fail the load with every field value correct |
| `DALYTRAN-AMT` carries `+2065.01` where the seed row carries `+504.77` | master section 11.1 | A business-rule field reshaped deliberately -- it is the scenario. Attested in section 9 |
| The date is held inside the expiration date so that 102 is not overwritten by 103 | master section 7.1.4 | Named here because section 7.1.4 requires a scenario isolating 102 to say so. No date was reshaped; the seed value already satisfies it |

No non-zero cycle values are used, so the disclosure master section 7.1.3 requires of a fixture that
changes the projected-balance identity does not apply here.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input data** -- the
feed record has not been processed yet. Master section 8.1 requires this to be stated every time a
blank timestamp is shown, because the byte pattern is identical to the one normalisation produces and
the meaning cannot be recovered from the bytes. It is the same reason the migrated
`ledger.daily_transactions.proc_ts` column is nullable while `ledger.transactions.proc_ts` is
`NOT NULL`.

**Both 26-byte fields travel into the reject record unchanged, and neither is masked there.** `:447`
copies the entire 350-byte input image, so the reject's `[278:304]` carries
`2022-06-10 19:27:53.000000` and its `[304:330]` carries the same 26 spaces -- still meaning "not
processed". A reject record contains **no clock reading at all**, which is why `dalyrejs.expected` can
be compared byte for byte with no field normalised.

Everything else holds by construction: every byte here is literal, there is no random identifier and
no environment-derived string, and each test provisions and tears down its own workspace, per master
section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and this scenario is one of the four the module's
posting-validation assertions read directly:

- **`dto/RejectReason.OVER_CREDIT_LIMIT`** carries the code `102` and the description `OVERLIMIT
  TRANSACTION` **verbatim**, and renders both representations separately -- `codeField()` for the
  four-character `0102` and `code()` for the integer the `SMALLINT` column stores.
- **`dto/PostingValidationResult.resolve`** takes `overCreditLimit` as the **strict complement** of
  the inclusive guard at `:407`, so this folder is the fixture on the side where that argument is
  `true`. Unlike the two lookup failures, this outcome **carries a projected cycle balance**: the
  record's constructor requires a projection to be present exactly when one could have been computed,
  so a decision claiming reason 102 with a `null` projection is refused, and the number `:403-405`
  computed is asserted alongside the reason rather than inferred from it.
- **`dto/RejectReason.lastWriterWins` and `baselineAssignmentOrder`** are how the target preserves
  master section 7.1.4's overwrite. `resolve` selects the surviving boundary reason as a **maximum**
  under the recorded assignment order rather than by an either-or, so a record tripping both
  conditions reports 103 no matter which candidate is tested first. This folder is the single-condition
  case that must keep reporting **102** under that same selection.
- **`dto/BatchRunSummary`** enforces the warn-tier biconditional in both directions, so this scenario
  must report the warn tier with one processed, one rejected and zero written.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql),
where lines 492 to 494 declare the reject contract by composition -- `raw_record CHAR(350)`,
`reason_code SMALLINT`, `reason_desc VARCHAR(76)`. That file is the authority for the schema and it is
deliberately not re-derived here.

The graded rubric 0, 4, 8 belongs to the COBOL parity suite alone (master section 7.1.6). The
warn-tier expectation here is the job's return code, not a build status.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs; the oracle is
the reference-only `tests/golden/posting/reject_102_overlimit/` tree, read as the authority and never
written, and no golden is ever regenerated.

### 8.2 Oracle constants that agree with this scenario, and the one that does not

This is the one reject folder whose reason the seed cycle also produces, so master section 7.1.7's
constants need reading rather than dismissing. `_EXPECTED_REJECTED = 38, all reason 0102` means that
across the full 300-record seed feed **every** rejection is an over-limit rejection -- the same reason
this folder isolates, reached by 38 unmodified seed records against unmodified seed accounts. The
oracle's reject-slice constants agree with section 3 exactly: card `[262:278]`, code `[350:354]`,
message `[354:430]`, `_REJ_RECLEN` 430.

**The counts themselves are not expectations here.** This is one record, so the expectation is one
processed and one rejected, not 300 and 38. And `_EXPECTED_CONSERVATION = Decimal("77954.70")` is a
whole-cycle sum with no counterpart in a one-record fixture. The two loader-geometry constants do
agree -- `_ACCT = (300, 11)` and `_TCAT = (50, 17)` match this folder's records and keys.

### 8.3 The paired folder

`posting/boundary_exact_limit` carries the same card, the same account, the same category row and the
same dates, with `DALYTRAN-AMT` one cent lower at `+2065.00` -- a **single byte** different, the
overpunch character at offset 142. Read the two together: that folder pins that the boundary is
inclusive, and this one pins that one cent past it rejects. Neither claim survives on its own.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived.** Every record traces to a published AWS
CardDemo sample seed row under `app/data/ASCII/`:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 7 | **byte-identical** |
| `tcatbal.txt` | `app/data/ASCII/tcatbal.txt` | 7 | **byte-identical** once the `CR` is removed |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | 21 | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | one business-rule field reshaped, below |

The cross-reference row is row **21** of its seed rather than row 7 because that file is ordered by
card number, and account `00000000007` appears there under card `4859452612877065`.

**It represents no real person and no real account.** The seeds ship with the upstream open-source
project as fabricated demonstration data, and master section 11.1 carries the tree-level attestation
this scenario inherits. Identity and primary-account-number bytes are taken unchanged from the seed.

**Exactly one business-rule field is reshaped away from its seed value.** In `dailytran.txt`,
`DALYTRAN-AMT` at `[132:143]` is `0000020650A` = `+2065.01`, where seed row 1 carries `0000005047G` =
`+504.77`. Measured, the difference is the **six bytes** at offsets 137 to 142: `05047G` becoming
`20650A`. The final byte carries both a digit and the sign, so the change is a change of value rather
than of digits alone -- `G` is a positive trailing 7 and `A` a positive trailing 1.

No other field in any file here departs from its seed value: the account row, the category row and
the cross-reference row are the seed's own, and in the daily transaction the identifier, type,
category, source, description, all four merchant fields, the card number and both timestamps are
unchanged. The two normalizations named in section 5.4 -- the card-xref width and the category-balance
line ending -- are width and line-ending conformance, **not** business-rule field changes, so the two
statements do not conflict.

Trade-offs: this folder and `posting/boundary_exact_limit` reshape the **same** field of the **same**
seed row to two values one cent apart, which means neither is byte-identical to its seed and both
carry an amount no seed record contains. The alternative -- finding two real seed records that
straddle their own account's limit by exactly one cent -- does not exist in the corpus: the oracle
records that all 38 seed rejections are over-limit, but nothing in the seeds sits *exactly* on a
limit, which is the value the inclusive half of the pair requires. The compromise accepted is a
documented six-byte delta in each of two folders, in exchange for a pair that fixes the operator; the
account row that the limit comes from stays seed-exact in both, so the threshold itself is never an
invented number.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string or
endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the
COBOL baseline or the parity oracle.

---

*This README is the mandatory Explainability carrier for the four record files in this directory,
required by master section 10 and by user-specified Rule 1. `config/rule1/rule1_gate.py` decides the
form of the rationale labels above, repository-wide and including Markdown, which is why they are
written plain rather than emphasised. `config/checkstyle/checkstyle.xml` limits its audit set to
`java`, so no linter reads this prose. Whether each rationale names a real consequence, and whether
every number and line citation is true, are review obligations no lexical gate can decide.*

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**. `PostTransactionsJobParityIT` resolves each scenario under
`/fixtures/posting/`, seeds the masters from it, launches the posting job and compares the resulting
transaction master, category balances, account master and reject stream against
`tests/golden/posting/reject_102_overlimit` -- so an edit to these bytes changes what the parity run asserts.
`BatchFixtureContractTest` additionally holds every file here to its declared geometry and to the
values that make the scenario discriminating, so a layout mistake is caught in this module rather
than surfacing later as a comparison failure.

⚠️ Refactoring Rationale: this section stated that no test in this module opened the folder and that
the corpus was a reference mirror. That was accurate when it was written and is no longer -- the
parity class now seeds from here. It is corrected rather than deleted, because a reader who had been
told these bytes drive nothing would edit them expecting no consequence, which is the most expensive
mistake this folder admits.

Assumptions: the sibling `preflight/**` and `interest/**` families are still mirrors -- no test
declares either as a seed root -- so the tree serves two different purposes and only this one changes
what a run asserts.
