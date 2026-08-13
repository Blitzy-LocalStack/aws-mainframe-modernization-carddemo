# `posting/boundary_exact_limit` -- exactly on the credit limit, and it posts

> **Purpose.** Pin the **inclusive** side of the credit-limit boundary: a transaction whose
> projected cycle balance lands *exactly* on the account's credit limit **posts**, because the
> guard is `>=` rather than `>`. This is one half of a two-fixture pair -- the other,
> `posting/reject_102_overlimit`, is the same account and the same card one cent higher -- and
> the pair is the only construction that pins an inclusive comparison, since either fixture
> alone passes under both readings of the operator.
>
> **Source of truth.** `app/cbl/CBTRN02C.cbl` for the behaviour and `app/jcl/POSTTRAN.jcl` for
> the dataset contract, both reference-only and both cited below by line;
> `app/cpy/CVTRA06Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVACT01Y.cpy` and
> `app/cpy/CVTRA01Y.cpy` for the record layouts; the seed datasets under `app/data/ASCII/` for
> the bytes; `tests/golden/posting/boundary_exact_limit/` for the expected outputs; and the
> tree-level [master contract](../../README.md) for every encoding rule, which this document
> cites by section rather than restating (master section 1.3).
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. This whole file is pure ASCII
> for the reason that section gives.

**This README is the mandatory Explainability carrier for the four record files beside it.**
Master section 1.2 records why: a fixed-width record file cannot carry a comment of any kind,
because every byte position is meaningful and a comment occupying its own line is a physical row
of the wrong length. Master section 10 makes the artifact mandatory rather than courteous, and
the section order below is the one it fixes.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction of **`+2065.00`** against account `00000000007`, whose credit limit is
**`+2065.00`** -- the same number to the cent. Both current-cycle fields are `+0.00`, so the
projected balance the program computes *is* the transaction amount, and the comparison at the
heart of the scenario is `2065.00 >= 2065.00`.

The expectation is that the record **posts**. Everything else in the folder exists to make that
outcome legible: the category-balance row is present so the balance arithmetic has a visible
addend, and the originating date is two and a half years inside the expiration date so that the
*other* boundary test cannot interfere.

Alternatives Considered: pinning the boundary with one fixture rather than two -- either this
one alone, or `reject_102_overlimit` alone. Rejected because neither discriminates. A single
transaction landing exactly on the limit passes under `>=` and, read the other way, would also
be expected to pass by anyone who believed the guard was `>` and mis-stated the amount by a
cent; and a single one-cent-over transaction rejects under both `>` and `>=`. Only the **pair**
-- identical in every byte except six inside `DALYTRAN-AMT` -- fixes the operator, because
exactly one of the two outcomes flips if the comparison is changed. That is why the two folders
carry the same account, the same card, the same category row and the same dates.

Assumptions: the cycle fields being zero is load-bearing and is stated rather than assumed.
Master section 7.1.3 records that in the seeds both are `0`, so the projected balance equals the
amount and a boundary fixture can be reasoned about from the amount alone. This fixture keeps
them at `+0.00`, so no arithmetic beyond the identity is needed; a fixture with non-zero cycle
values would have to show its own arithmetic instead.

---

## 2. The business rule, cited by program and line

**`app/cbl/CBTRN02C.cbl:407`, `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL`.** That single line is the
subject. Its `CONTINUE` arm at `:408` is the posting path; its `ELSE` at `:409-412` sets reason
102 and the message `OVERLIMIT TRANSACTION`.

The projected balance it tests is formed three lines earlier:

```cobol
COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                    - ACCT-CURR-CYC-DEBIT
                    + DALYTRAN-AMT
```

That is `:403-405`. The order of the operands matters for nothing here, because both cycle
fields are zero, but the identity it produces -- projected balance equals amount -- is what
makes this fixture readable.

The surrounding walk, all in `app/cbl/CBTRN02C.cbl`:

| Step | Line | What happens for this record |
|---|---|---|
| Validate | `:210`, `:370-378` | `1500-VALIDATE-TRAN` performs the cross-reference lookup, then the account lookup because the reason is still 0 |
| Cross-reference hits | `:382-383`, `:388` | card `4859452612877065` resolves; `XREF-ACCT-ID` becomes `00000000007` |
| Account hits | `:394-395`, `:400` | account `00000000007` is read, so the two boundary tests are evaluated |
| **Credit-limit test** | `:403-405`, **`:407`** | `2065.00 >= 2065.00` is **true**; `:408` continues and 102 is **not** set |
| Expiration test | `:414` | `2024-12-13 >= 2022-06-10` is **true**; `:415` continues and 103 is not set |
| Post | `:211-212`, `:424-444` | reason 0, so the unit of work runs: `:440` category balance, `:441` account, `:442` transaction |
| Category arm | `:474`, `:495-499`, `:526-542` | the read hits, so `:498` performs the **update** arm |
| Account arithmetic | `:547`, `:548-549` | the amount is added to the current balance and, being non-negative, to the current-cycle **credit** total |
| Grade | `:229-230` | the rejected count is zero, so the return code stays 0 |

Assumptions: `WS-TEMP-BAL` is declared `PIC S9(10)V99` in working storage, the same scale as the
credit limit it is compared against, so the comparison is exact fixed point on both sides and
there is no rounding step for a boundary case to fall through. Master section 5.5 fixes that
contract for the whole tree; it is named here because an inclusive boundary is exactly where a
scale mismatch would show up as an off-by-one-cent verdict.

---

## 3. Returns -- the expected outcome

The return code is **0**, the clean tier of master section 7.1.6. Every figure is arithmetic
over this folder's bytes, confirmed against the reference-only golden corpus in
`tests/golden/posting/boundary_exact_limit/`.

**The comparison.** `WS-TEMP-BAL = 0.00 - 0.00 + 2065.00 = 2065.00`. `ACCT-CREDIT-LIMIT` is
`2065.00`. `2065.00 >= 2065.00` is **true**, so the transaction posts and reject reason 102 is
never assigned.

**The three mutations.**

| Output | Before | Arithmetic | After | Encoded |
|---|---|---|---|---|
| `TRAN-CAT-BAL` for `00000000007 / 01 / 0001` | `+0.00` | `0.00 + 2065.00` | **`+2065.00`** | `0000206500{` |
| `ACCT-CURR-BAL` | `+193.00` | `193.00 + 2065.00` | **`+2258.00`** | `00000225800{` |
| `ACCT-CURR-CYC-CREDIT` | `+0.00` | `0.00 + 2065.00` | **`+2065.00`** | `00000206500{` |
| `ACCT-CURR-CYC-DEBIT` | `+0.00` | untouched -- `:551` runs only for a negative amount | **`+0.00`** | `00000000000{` |

**The posted account balance exceeds the credit limit, and that is correct.** `2258.00` is above
the `2065.00` limit, because `:407` tests the **projected cycle** balance and not the account's
running balance -- the two are different quantities, and only the first is bounded by the limit.
A reader who expects the account balance to stay under the limit will read this row as a defect;
it is the rule.

**One transaction record** of 350 bytes is written: identifier `0000000000683580`, type `01`,
category `0001`, source `POS TERM`, amount `+2065.00`, card `4859452612877065`,
`TRAN-ORIG-TS` = `2022-06-10 19:27:53.000000` copied unchanged by `:436`, `TRAN-PROC-TS` from
the clock and therefore masked, the trailing `FILLER` at offset 330 carrying **20 NUL bytes**
per master section 6.1, and `TRAN-DESC` **space**-padded per master section 6.3.

**The reject stream is empty but the dataset exists.** `app/jcl/POSTTRAN.jcl:34-38` allocates
`DALYREJS(+1)` unconditionally at `LRECL=430` and the program opens it OUTPUT at `:293` before
it can know whether anything will be rejected. The golden records that as a zero-byte
`dalyrejs.expected`, so an expectation phrased as "no reject dataset exists" would fail against
correct behaviour.

**Counters.** One processed, zero rejected, both emitted at `:227-228`.

Assumptions: the golden corpus is corroboration, never the derivation -- its `acctdat.expected`
is 301 bytes, `tcatbal.expected` 51, `tranfile.expected` 351, `dalyrejs.expected` 0 and
`return_code.expected` the single byte `0`. The arithmetic above comes from this folder's bytes
and the lines cited in section 2, so this document states a rule rather than transcribing an
output.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**102 must not be set, and one cent is the whole margin.** This is the scenario's single most
important negative: an implementation that reads the guard as `>` produces reason 102 here with
every other byte identical, and the failure surfaces as a reject where a posted record was
expected -- return code 4 instead of 0, one reject record instead of one transaction record. It
is a one-line defect with a four-file consequence.

**103 must not be set either, and the fixture is arranged so it cannot.** Master section 7.1.4
records that `:407-413` and `:414-420` are two sequential unguarded `IF` blocks with no
reason-code test between them, so a transaction that trips both ends with **103** overwriting
102. This fixture's originating date, `2022-06-10`, is well before the expiration date
`2024-12-13`, so the expiration test passes and nothing can overwrite anything. **A scenario
isolating the credit-limit boundary must keep its date on or before expiration**, or its
expectation silently becomes 103 -- and it would then be pinning the wrong operator entirely.

**100 and 101 are excluded by the fixture's own bytes.** The card is present in the
cross-reference, so `:385` cannot fire; the resolved account is present in the master, so `:397`
cannot fire.

**The create arm must not be taken.** `2700-A-CREATE-TCATBAL-REC` (`:503-524`) runs only when
`WS-CREATE-TRANCAT-REC` is `'Y'`, set only by the `INVALID KEY` branch at `:475-478`. The row
for `00000000007 / 01 / 0001` is present, so the read at `:474` returns `'00'` and the flag
keeps the declared `'N'` of `:190`.

**Code 109 is unreachable and must not be expected.** `:556` assigns it inside the `INVALID KEY`
branch of the account `REWRITE` at `:554-559`, on the posting path and after validation, and it
writes no reject record. Master section 7.1.1 fixes the persisted domain as exactly
`{100, 101, 102, 103}`.

**No abend occurs.** Every reachable `9999-ABEND-PROGRAM` site is status-guarded: the six opens
(`:250`, `:268`, `:287`, `:305`, `:323`, `:341`), the feed read (`:363-366`), the
category-balance read (`:492`) and rewrite (`:541`), the transaction write (`:577`) and the six
closes (`:596`, `:614`, `:633`, `:651`, `:669`, `:688`).

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

`ASSIGN` names and organizations are declared at `app/cbl/CBTRN02C.cbl:29-61`; the record
lengths are the summed widths of master section 5.1, never a `RECLN` banner. The cross-reference
is read **by card number** at offset 0 -- master section 4.3, since `app/jcl/POSTTRAN.jcl:32-33`
mounts the base cluster only.

### 5.2 On-disk sizes and line endings

| File | Size | Records | Bytes per record | `CR` bytes | Trailing newline |
|---|---:|---:|---:|---:|---|
| `dailytran.txt` | **351** | 1 | 350 | 0 | exactly one `LF` |
| `cardxref.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |
| `acctdata.txt` | **301** | 1 | 300 | 0 | exactly one `LF` |
| `tcatbal.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |

One record plus a single trailing `LF` per file, per master sections 3.8 and 3.9, and **zero
`CR` bytes** in the folder.

```bash
# WHAT: assert the whole byte geometry, and additionally assert the one field this scenario
#       turns on -- that the amount and the credit limit decode to the same value. Run from
#       this directory.
# WHY : Assumptions: a boundary fixture has a second failure mode a width check cannot see. If
#       the amount drifts by a cent in either direction the geometry stays perfect and the
#       expected outcome silently inverts, so the equality is worth asserting at authoring time
#       rather than discovering it as a reject in a comparison. The two values are read from
#       different files and different widths -- 11 bytes at offset 132 against 12 at offset 24 --
#       which is why the check decodes both rather than comparing bytes.
python3 - <<'PY'
OVERPUNCH = {"{": 0, "A": 1, "B": 2, "C": 3, "D": 4, "E": 5,
             "F": 6, "G": 7, "H": 8, "I": 9}
def zoned(raw):
    text = raw.decode("ascii")
    return int(text[:-1] + str(OVERPUNCH[text[-1]])) / 100
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
amount = zoned(open("dailytran.txt", "rb").read()[132:143])
limit = zoned(open("acctdata.txt", "rb").read()[24:36])
print("amount", amount, "limit", limit,
      "| OK exactly on the limit" if amount == limit else "| MISMATCH not a boundary case")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based; money is signed zoned with the sign folded into the last byte and no
byte for the decimal point, per master sections 3.3 and 3.7.

`dailytran.txt`, one `DALYTRAN-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DALYTRAN-ID` | 0 | 16 | `0000000000683580` | -- |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `01` | -- |
| `DALYTRAN-CAT-CD` | 18 | 4 | `0001` | unsigned, plain digits |
| `DALYTRAN-SOURCE` | 22 | 10 | `POS TERM` + 2 spaces | -- |
| `DALYTRAN-DESC` | 32 | 100 | `Purchase at Abshire-Lowe` + 76 spaces | -- |
| `DALYTRAN-AMT` | 132 | 11 | `0000020650{` | **`+2065.00`** -- reshaped, section 9 |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | -- |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | -- |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | -- |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | -- |
| `DALYTRAN-CARD-NUM` | 262 | 16 | `4859452612877065` | -- |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `2022-06-10 19:27:53.000000` | inside expiration by design |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

`cardxref.txt`: `XREF-CARD-NUM` `4859452612877065` at 0, `XREF-CUST-ID` `000000007` at 16,
`XREF-ACCT-ID` `00000000007` at 25, `FILLER` 14 spaces at 36.

`acctdata.txt`, one `ACCOUNT-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | `00000000007` | account 7 |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | active |
| `ACCT-CURR-BAL` | 12 | 12 | `00000001930{` | `+193.00` |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000020650{` | **`+2065.00`** -- the limit `:407` tests |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000002640{` | `+264.00` -- unread by this job |
| `ACCT-OPEN-DATE` | 48 | 10 | `2012-10-12` | -- |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `2024-12-13` | -- |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2024-12-13` | -- |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` | `+0.00` -- the identity of section 1 |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` | `+0.00` |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | -- |
| `ACCT-GROUP-ID` | 112 | 10 | 10 spaces | blank -- unread by this job |
| `FILLER` | 122 | 178 | 178 spaces | -- |

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` line 11 spells it, preserved
verbatim because this is copybook-side naming; master section 9.3 confines the three spelling
corrections to target column names. The blank `ACCT-GROUP-ID` is inert here -- it is the
disclosure-group key the interest program composes (master section 7.2.3), and this job never
reads it. **`ACCT-CASH-CREDIT-LIMIT` is not the field `:407` tests**; the guard names
`ACCT-CREDIT-LIMIT`, and confusing the two in a boundary scenario would move the boundary by
`1801.00`.

`tcatbal.txt`, one `TRAN-CAT-BAL-RECORD`: `TRANCAT-ACCT-ID` `00000000007` at 0,
`TRANCAT-TYPE-CD` `01` at 11, `TRANCAT-CD` `0001` at 13, `TRAN-CAT-BAL` `0000000000{` = `+0.00`
at 17, and 22 ASCII `'0'` of `FILLER` at 28.

**The `FILLER` bytes differ by measurement, not by inconsistency.** Three files pad with `0x20`
SPACE; `tcatbal.txt` pads with ASCII `'0'`, `0x30`. Both are the bytes master section 6.1
measures, and master section 6.2 records the category-balance row as one of the two that
contradict the general rule of section 3.2, with the measured byte winning -- applying the
general rule there would produce a row differing from seed and golden in 22 bytes carrying no
data.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| `tcatbal.txt` is `LF`-terminated where its seed is `CRLF` | master sections 3.8, 3.9 | A stray `0x0D` absorbed into the 22-byte `'0'` `FILLER` would push the record to 51 bytes and fail the load with every field value correct |
| `DALYTRAN-AMT` carries `+2065.00` where the seed row carries `+504.77` | master section 11.1 | A business-rule field reshaped deliberately -- it is the scenario. Attested in section 9 |

No non-zero cycle values are used, so the disclosure master section 7.1.3 requires of a fixture
that changes the projected-balance identity does not apply here.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input
data** -- the feed record has not been processed yet. Master section 8.1 requires this to be
stated every time a blank timestamp is shown, because the byte pattern is identical to the one
normalisation produces and the meaning cannot be recovered from the bytes. It is the same reason
the migrated `ledger.daily_transactions.proc_ts` column is nullable while
`ledger.transactions.proc_ts` is `NOT NULL`.

`DALYTRAN-ORIG-TS` is the opposite case: real, deterministic input data, copied unchanged into
the output by `:436`, and therefore **asserted** rather than masked. In the golden's output
record the *processing* stamp appears as 26 spaces, and there those spaces are the product of
normalisation -- the other of the two meanings.

Everything else holds by construction: every byte is literal, there is no clock value, no random
identifier and no environment-derived string, and each test provisions and tears down its own
workspace, per master section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and this scenario is one of the two the
module's boundary assertions read directly:

- **`service/PostingValidationService.validate`** returns a decision carrying
  `dto/PostingValidationResult.accepted(2065.00)`. The projected cycle balance is part of the
  **accepted** outcome, not only of a rejection, which is what lets an assertion check the
  number `:403-405` computed even on the passing side of the boundary.
- **`dto/PostingValidationResult.resolve`** takes `overCreditLimit` as the **strict complement**
  of the inclusive guard, so a transaction landing exactly on the limit arrives with that
  argument `false`. The inclusiveness lives in one place rather than being re-derived per
  caller, and this folder is the fixture that proves the complement was taken correctly.
- **`service/CategoryBalanceService`** reports the **update** arm and a resulting balance of
  `2065.00`.
- **`dto/BatchRunSummary`** enforces the warn-tier biconditional in both directions, so this
  scenario must report tier 0 with one processed and zero rejected; a summary claiming warn with
  nothing rejected is refused outright.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql):
`ledger.daily_transactions`, `account.card_xref`, `account.accounts` and
`ledger.transaction_category_balances` as inputs, `ledger.transactions` gaining one row, and
`ledger.transaction_rejects` staying empty. A scenario owns only its own rows, per master
section 11.3.

The graded rubric 0, 4, 8 belongs to the COBOL parity suite alone (master section 7.1.6). The
tier-0 expectation here is the job's return code, not a build status.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs; the
oracle is the reference-only `tests/golden/posting/boundary_exact_limit/` tree, read as the
authority and never written, and no golden is ever regenerated.

### 8.2 Oracle constants that are not expectations for this scenario

Master section 7.1.7's constants describe the **full 300-record seed cycle** -- 300 daily
records, 262 posted, 38 rejected all reason `0102`, a conservation total of `77954.70`, 50
category keys becoming 100. **None is an expectation here**, and the `0102` figure is the one
most likely to be misread: those 38 rejections come from the unmodified seed feed against the
unmodified seed accounts, and they say nothing about a fixture that was reshaped precisely to
land on the limit instead of over it. The two loader-geometry constants do agree --
`_ACCT = (300, 11)` and `_TCAT = (50, 17)` match this folder's records and keys.

### 8.3 The paired folder

`posting/reject_102_overlimit` carries the same card, the same account, the same category row
and the same dates, with `DALYTRAN-AMT` one cent higher at `+2065.01`. Read the two together:
this folder pins that the boundary is inclusive, and that folder pins that one cent past it
rejects. Neither claim survives on its own.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived.** Every record traces to a published
AWS CardDemo sample seed row under `app/data/ASCII/`:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 7 | **byte-identical** |
| `tcatbal.txt` | `app/data/ASCII/tcatbal.txt` | 7 | **byte-identical** once the `CR` is removed |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | 21 | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | one business-rule field reshaped, below |

The cross-reference row is row **21** of its seed rather than row 7 because that file is ordered
by card number, and account `00000000007` appears there under card `4859452612877065`.

**It represents no real person and no real account.** The seeds ship with the upstream
open-source project as fabricated demonstration data, and master section 11.1 carries the
tree-level attestation this scenario inherits. Identity and primary-account-number bytes are
taken unchanged from the seed.

**Exactly one business-rule field is reshaped away from its seed value.** In `dailytran.txt`,
`DALYTRAN-AMT` at `[132:143]` is `0000020650{` = `+2065.00`, where seed row 1 carries
`0000005047G` = `+504.77`. Measured, the difference is the **six bytes** at offsets 137 to 142:
`05047G` becoming `20650{`. Note that the overpunch character changes too -- `G` is `+7` and
`{` is `+0` -- so this is a change of value and not merely of digits, which is why the check in
section 5.2 decodes rather than compares text.

No other field in any file here departs from its seed value: the account row, the category row
and the cross-reference row are the seed's own, and in the daily transaction the identifier,
type, category, source, description, all four merchant fields, the card number and both
timestamps are unchanged. The two normalizations named in section 5.4 -- the card-xref width and
the category-balance line ending -- are width and line-ending conformance, **not** business-rule
field changes, so the two statements do not conflict.

Trade-offs: reshaping the amount rather than the credit limit was a real choice. Lowering the
limit to `+504.77` to meet the seed amount would produce the same comparison, and it would break
the pairing: `reject_102_overlimit` would then have to raise the amount by a cent anyway, so the
two folders would differ in two fields rather than one, and a failure would no longer localise
to the amount. Reshaping the amount keeps the account row byte-identical to its seed across
both folders and confines the difference between them to a single field. The cost accepted is
that this folder's feed record is not byte-identical to its seed row, which is why the delta is
stated to the byte above.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection
string or endpoint appears in any file here, money never leaves fixed point, and nothing here
modifies the COBOL baseline or the parity oracle.

---

*This README is the mandatory Explainability carrier for the four record files in this
directory, required by master section 10 and by user-specified Rule 1.
`config/rule1/rule1_gate.py` decides the form of the rationale labels above, repository-wide and
including Markdown, which is why they are written plain rather than emphasised.
`config/checkstyle/checkstyle.xml` limits its audit set to `java`, so no linter reads this
prose. Whether each rationale names a real consequence, and whether every number and line
citation is true, are review obligations no lexical gate can decide.*

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**. `PostTransactionsJobParityIT` resolves each scenario under
`/fixtures/posting/`, seeds the masters from it, launches the posting job and compares the resulting
transaction master, category balances, account master and reject stream against
`tests/golden/posting/boundary_exact_limit` -- so an edit to these bytes changes what the parity run asserts.
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
