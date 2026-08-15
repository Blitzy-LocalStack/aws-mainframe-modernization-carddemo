# `posting/boundary_expiry_equal` -- dated exactly on the expiration date, and it posts

> **Purpose.** Pin the **inclusive** side of the expiration boundary: a transaction whose
> originating date equals the account's expiration date to the day **posts**, because the guard
> is `>=` rather than `>`. This is one half of a two-fixture pair -- the other,
> [`posting/reject_103_expired`](../reject_103_expired/), is the same account and the same card
> one day later -- and the pair is the only construction that pins an inclusive comparison,
> since either fixture alone passes under both readings of the operator.
>
> **Source of truth.** `app/cbl/CBTRN02C.cbl` for the behaviour and `app/jcl/POSTTRAN.jcl` for
> the dataset contract, both reference-only and both cited below by line;
> `app/cpy/CVTRA06Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVACT01Y.cpy` and
> `app/cpy/CVTRA01Y.cpy` for the record layouts; the seed datasets under `app/data/ASCII/` for
> the bytes; `tests/golden/posting/boundary_expiry_equal/` for the expected outputs; and the
> tree-level [master contract](../../README.md) for every encoding rule, which this document
> cites by section rather than restating (master section 1.3).
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4, and this whole file is pure
> ASCII for the reason that section gives.

**This README is the mandatory Explainability carrier for the four record files beside it.**
Master section 1.2 records why: a fixed-width record file cannot carry a comment of any kind,
because every byte position is meaningful and a comment on its own line is a physical row of the
wrong length. Master section 10 makes the artifact mandatory rather than courteous, and the
section order below is the one it fixes.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction whose `DALYTRAN-ORIG-TS` begins **`2024-12-13`**, against account
`00000000007` whose `ACCT-EXPIRAION-DATE` is **`2024-12-13`** -- the same day. The amount,
`+504.77`, is far inside the `+2065.00` credit limit, so the *other* boundary test cannot
interfere.

The expectation is that the record **posts**. Two properties of the comparison make this
scenario worth its own folder rather than a variation of [`happy_path`](../happy_path/):

- the guard compares a **ten-character prefix of a twenty-six-character field**, so the
  remaining sixteen characters of the timestamp -- here `19:27:53.000000` and its separating
  space -- take **no part** in the decision;
- the comparison is a **character** comparison between two `X(10)` fields, not a date
  comparison, and it is only equivalent to one because both sides are ISO-ordered.

`tests/README.md` section 13 states the paired requirement directly and in the reference tree's
own words: a transaction dated equal to the expiration date must post, and one day past it must
reject. This folder is the first half; `reject_103_expired` is the second.

Alternatives Considered: pinning the boundary with one fixture rather than two -- either this one
alone, or `reject_103_expired` alone. Rejected because neither discriminates. An equal-date
transaction passes under `>=` and would also be expected to pass by a reader who believed the
guard was `>` and mis-stated the date by a day; a one-day-late transaction rejects under both.
Only the **pair** -- identical in every byte except one inside `DALYTRAN-ORIG-TS` -- fixes the
operator, because exactly one of the two outcomes flips if the comparison changes. That is why
both folders carry the same account, the same card, the same amount and the same category row.

Assumptions: the amount being well inside the limit is load-bearing and is stated rather than
assumed. Master section 7.1.4 records that reason 102 and reason 103 are assigned by two
sequential unguarded `IF` blocks, so a transaction tripping both reports 103. A date-boundary
fixture that also sat over the limit would still report 103 and would appear to pass -- while
actually proving nothing about the date, because the amount alone would have produced the same
reject. Keeping the amount at `+504.77` means the only reachable reason in this folder is the
one under test.

---

## 2. The business rule, cited by program and line

**`app/cbl/CBTRN02C.cbl:414`** is the subject, and it is quoted here exactly as the baseline
writes it:

```cobol
      IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
        CONTINUE
      ELSE
        MOVE 103 TO WS-VALIDATION-FAIL-REASON
        MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
          TO WS-VALIDATION-FAIL-REASON-DESC
      END-IF
```

The `CONTINUE` arm at `:415` is the posting path; the `ELSE` at `:416-419` sets reason 103 and
its message. Because the guard is written as the **pass** condition, an equal-date transaction
falls through `CONTINUE` and the reject arm is never entered.

Three properties of that line, each checked against the source:

- **`(1:10)` is a reference modifier** taking the first ten characters of the 26-character
  originating timestamp -- one-based in COBOL, so bytes `[0:10]` zero-based, which is the date
  portion `2024-12-13` and nothing else. The remaining sixteen characters are not compared.
- **Both operands are alphanumeric.** `ACCT-EXPIRAION-DATE` is `PIC X(10)` at
  `app/cpy/CVACT01Y.cpy:11` and the modified reference is a character substring, so the
  comparison is left-to-right byte-wise rather than arithmetic on a date type.
- **The field name carries the baseline's misspelling.** `EXPIRAION` is what line 11 says, and a
  citation of `:414` must quote it that way. The migrated column is named `expiration_date`, but
  that correction belongs to master section 9.3 and to
  `docs/architecture/data-model-and-schema-mapping.md`, which confine the three spelling fixes
  to **target column names only**. It is not this fixture's business, and no byte in this folder
  changes because of it.

Assumptions: the byte-wise comparison is equivalent to a date comparison **only** because both
sides are zero-padded `YYYY-MM-DD`, where lexical order and chronological order coincide. That
equivalence is what lets a static byte fixture exercise a date rule with no date arithmetic
anywhere in the folder, and it is worth naming because it is a property of the format rather
than of the program -- the same line against a `DD/MM/YYYY` field would compare days before
years and would order dates wrongly while still comparing successfully.

The surrounding walk, all in `app/cbl/CBTRN02C.cbl`:

| Step | Line | What happens for this record |
|---|---|---|
| Validate | `:370-378` | `1500-VALIDATE-TRAN` performs the cross-reference lookup, then the account lookup because the reason is still 0 |
| Cross-reference hits | `:382-383`, `:388` | card `4859452612877065` resolves; `XREF-ACCT-ID` becomes `00000000007` |
| Account hits | `:394-395`, `:400` | account `00000000007` is read, so both boundary tests are evaluated |
| Credit-limit test | `:403-405`, `:407` | `2065.00 >= 504.77` is true; `:408` continues and 102 is not set |
| **Expiration test** | **`:414`** | `2024-12-13 >= 2024-12-13` is **true**; `:415` continues and 103 is **not** set |
| Post | `:424`, `:440-442` | reason 0, so the unit of work runs: `:440` category balance, `:441` account, `:442` transaction |
| Category arm | `:474`, `:495-499`, `:526-528` | the read hits, so `:498` performs the **update** arm |
| Account arithmetic | `:547`, `:548-549` | the amount is added to the current balance and, being non-negative, to the current-cycle **credit** total |
| Grade | `:229-230` | the rejected count is zero, so the return code stays 0 |

The originating timestamp is used twice on this path and the two uses are independent: `:414`
compares its first ten characters, and `:436` copies all twenty-six into the output record
unchanged. A fixture that reshapes the date therefore changes both the verdict **and** an
asserted output field, which section 3 states explicitly.

---

## 3. Returns -- the expected outcome

The return code is **0**, the clean tier of master section 7.1.6. Every figure below is
arithmetic over this folder's bytes, corroborated against the reference-only golden corpus in
`tests/golden/posting/boundary_expiry_equal/`.

**The comparison.** `DALYTRAN-ORIG-TS (1:10)` is `2024-12-13`. `ACCT-EXPIRAION-DATE` is
`2024-12-13`. `2024-12-13 >= 2024-12-13` is **true**, so the transaction posts and reject reason
103 is never assigned. The credit-limit test passes independently:
`WS-TEMP-BAL = 0.00 - 0.00 + 504.77 = 504.77`, and `2065.00 >= 504.77`. Master section 7.1.3
supplies the identity that makes the second line readable off the amount alone -- both cycle
accumulators are `0` in the seed, so the projected balance reduces to `DALYTRAN-AMT` -- and this
folder inherits it unchanged rather than re-deriving it.

**One posted row, zero reject rows.** The unit of work runs once, in the order `:440` category
balance, `:441` account, `:442` transaction, which on the target side is a single
`@Transactional` commit.

**The three mutations.**

| Output | Before | Arithmetic | After | Encoded |
|---|---|---|---|---|
| `TRAN-CAT-BAL` for `00000000007 / 01 / 0001` | `+0.00` | `0.00 + 504.77` | **`+504.77`** | `0000005047G` |
| `ACCT-CURR-BAL` | `+193.00` | `193.00 + 504.77` | **`+697.77`** | `00000006977G` |
| `ACCT-CURR-CYC-CREDIT` | `+0.00` | `0.00 + 504.77` | **`+504.77`** | `00000005047G` |
| `ACCT-CURR-CYC-DEBIT` | `+0.00` | untouched -- `:551` runs only for a negative amount | **`+0.00`** | `00000000000{` |

Assumptions: the `Encoded` column shows the byte string as the golden actually holds it, which
for a signed zoned field means the **final digit lives inside the sign character** and the field
therefore shows one fewer plain digit than the decimal value has. `00000006977G` is eleven
digits followed by `G`, and `G` carries both the positive sign and the digit `7`, so the field
reads `000000069777` and scales to `+697.77`. Writing the value's digits out in full and then
appending an overpunch -- `00000069777G` -- is the natural-looking mistake and is wrong by a
factor of ten, yielding `000000697777` and `+6977.77`. What makes it worth naming is that the
mistaken form is **also exactly twelve bytes**, so it passes every width and record-length check
in this tree and fails only against the golden, as a money diff rather than as a layout error.
The overpunch alphabet itself is master section 3.3 and the implied decimal is section 3.7;
neither is restated here.

**The written transaction record carries the reshaped timestamp, and that is an assertion rather
than a side effect.** One row of 350 bytes: identifier `0000000000683580`, type `01`, category
`0001`, source `POS TERM`, amount `+504.77` as `0000005047G`, card `4859452612877065`, and
`TRAN-ORIG-TS` = **`2024-12-13 19:27:53.000000`** -- the same twenty-six characters this folder's
feed record carries, copied by `:436`. Master section 8.1 requires the posting domain to assert
the originating stamp rather than mask it, so this scenario's reshaped date is visible in the
comparison twice over: once as the verdict, once as an output field asserted byte for byte.
`TRAN-PROC-TS` is a clock reading from `:437-438` and is masked; the trailing `FILLER` at offset
330 carries **20 NUL bytes** per master section 6.1; `TRAN-DESC` is **space**-padded because
`:429` is a plain `MOVE` of an already-padded input field, per master section 6.3.

**The reject stream is empty but the dataset exists.** `app/jcl/POSTTRAN.jcl:34-38` allocates
`DALYREJS(+1)` unconditionally at `LRECL=430`, and `:293` opens it `OUTPUT` before the program
can know whether anything will be rejected, so the golden records a zero-byte `dalyrejs.expected`
rather than a missing file.

**Counters.** One processed, zero rejected, both displayed at `:227-228` and graded at
`:229-230`.

Assumptions: the golden corpus is corroboration, never the derivation -- `acctdat.expected` 301
bytes, `tcatbal.expected` 51, `tranfile.expected` 351, `dalyrejs.expected` 0,
`return_code.expected` the single byte `0`. The arithmetic comes from this folder's bytes and the
lines cited in section 2, so the two agree by independent construction rather than because one
was copied from the other; a golden regenerated from a defective run would otherwise validate
itself.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**103 must not be set, and one day is the whole margin.** The arm that must stay unreached is
`:417-419`, which moves `103` into `WS-VALIDATION-FAIL-REASON` and the verbatim text
`TRANSACTION RECEIVED AFTER ACCT EXPIRATION` into its description. An implementation reading the
guard as `>` produces exactly that reject here with every other byte identical, and the failure
surfaces as a reject where a posted record was expected -- return code 4 instead of 0, one
430-byte reject record instead of one 350-byte transaction record. No reject stream content is
produced by this scenario at all.

**A subtler defect this fixture also catches: comparing the whole field instead of its first ten
characters.** `ACCT-EXPIRAION-DATE` is ten bytes and `DALYTRAN-ORIG-TS` is twenty-six. An
implementation that compares them without the `(1:10)` modifier compares `2024-12-13` against
`2024-12-13 19:27:53.000000`, and the longer operand is greater once the shorter one is
blank-extended -- so the equal-date case would **reject**. This folder is the only fixture in
the domain where that mistake is visible, because it is the only one where the two dates are
equal and the trailing time is therefore the sole difference between the operands.

**102 must not be set, and the fixture is arranged so it cannot.** The amount `+504.77` projects
to `504.77` against a `2065.00` limit, so `:407` continues at `:408`. Master section 7.1.4's
overwrite of 102 by 103 has nothing to overwrite, and -- more to the point -- nothing could mask
the date verdict behind an amount verdict.

**100 and 101 are excluded by the fixture's own bytes.** The card is present in the
cross-reference, so `:385` cannot fire; the resolved account is present in the master, so `:397`
cannot fire. With 100, 101 and 102 all unreachable, the scenario has exactly **one live
decision** -- the one at `:414`.

**The create arm must not be taken.** `2700-A-CREATE-TCATBAL-REC` (`:503-524`) runs only when
`WS-CREATE-TRANCAT-REC` is `'Y'`, set only by the `INVALID KEY` branch at `:475-478`. The row for
`00000000007 / 01 / 0001` is present, so the read at `:474` succeeds and the flag keeps the
`'N'` declared at `:190` and re-asserted at `:473`, sending `:495-499` down the update arm at
`:498`.

**Code 109 is unreachable and must not be expected.** `:556` assigns it inside the `INVALID KEY`
branch of the account `REWRITE` at `:554-559`, on the posting path and after validation, writing
no reject record; master section 7.1.1 fixes the persisted reject domain as exactly
`{100, 101, 102, 103}`.

**No abend occurs.** Every reachable `9999-ABEND-PROGRAM` site is status-guarded, including the
category-balance read at `:492` and its rewrite at `:541`, so a clean run of these four files
reaches `GOBACK` with `RETURN-CODE` still 0.

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

`ASSIGN` names and organizations are declared at `app/cbl/CBTRN02C.cbl:29-61`; record lengths are
the summed widths of master section 5.1, never a `RECLN` banner. The cross-reference is read **by
card number** at offset 0 -- master section 4.3, since `app/jcl/POSTTRAN.jcl:32-33` mounts the
base cluster only.

### 5.2 On-disk sizes and line endings

| File | Size | Records | Bytes per record | `CR` bytes | Trailing newline |
|---|---:|---:|---:|---:|---|
| `dailytran.txt` | **351** | 1 | 350 | 0 | exactly one `LF` |
| `cardxref.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |
| `acctdata.txt` | **301** | 1 | 300 | 0 | exactly one `LF` |
| `tcatbal.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |

Every populated file is exactly **RECLN + 1** bytes -- one record plus a single trailing `LF`, per
master sections 3.8 and 3.9 -- and there are **zero `CR` bytes** anywhere in the folder.

```bash
# WHAT: assert the byte geometry, and additionally assert the one relation this scenario turns
#       on -- that the feed's date prefix and the account's expiration date are the same ten
#       characters. Run from this directory.
# WHY : Assumptions: a date-boundary fixture has a failure mode a width check cannot see. A
#       one-day drift in either field leaves the geometry perfect and silently inverts the
#       expected outcome, so the equality is worth asserting at authoring time rather than
#       discovering it as an unexpected reject. The check slices ten characters from a
#       twenty-six-byte field on purpose, because that slice IS the comparison the program makes
#       at :414, and comparing the whole field is the defect section 4 names.
python3 - <<'PY'
feed_date = open("dailytran.txt", "rb").read()[278:288].decode("ascii")
expiration = open("acctdata.txt", "rb").read()[58:68].decode("ascii")
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
print("feed date", feed_date, "expiration", expiration,
      "| OK exactly on the expiration date" if feed_date == expiration
      else "| MISMATCH not a boundary case")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based; money is signed zoned with the sign folded into the last byte and no byte
for the decimal point, per master sections 3.3 and 3.7.

`dailytran.txt`, one `DALYTRAN-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DALYTRAN-ID` | 0 | 16 | `0000000000683580` | -- |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `01` | -- |
| `DALYTRAN-CAT-CD` | 18 | 4 | `0001` | unsigned, plain digits |
| `DALYTRAN-SOURCE` | 22 | 10 | `POS TERM` + 2 spaces | -- |
| `DALYTRAN-DESC` | 32 | 100 | `Purchase at Abshire-Lowe` + 76 spaces | -- |
| `DALYTRAN-AMT` | 132 | 11 | `0000005047G` | `+504.77` -- inside the limit by design |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | -- |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | -- |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | -- |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | -- |
| `DALYTRAN-CARD-NUM` | 262 | 16 | `4859452612877065` | -- |
| `DALYTRAN-ORIG-TS` | 278 | 26 | **`2024-12-13 19:27:53.000000`** | reshaped, section 9; `[278:288]` is what `:414` compares |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | genuine input blanks -- see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

Every field except `DALYTRAN-ORIG-TS` is carried unchanged from `happy_path`, so a diff of the
two feed records is one field wide.

Assumptions: only the **date** part of the originating timestamp differs from the baseline; the
time part ` 19:27:53.000000` is the seed's own and is held constant deliberately. Holding it
constant is itself evidence of the `(1:10)` slice -- if the time mattered to the guard, two
fixtures with different verdicts could not share it -- and it keeps this folder diffable against
both `happy_path` and `reject_103_expired` down to a single field, so a reviewer comparing the
three sees the discriminator immediately instead of hunting it among incidental differences.

`cardxref.txt`: `XREF-CARD-NUM` `4859452612877065` at 0, `XREF-CUST-ID` `000000007` at 16,
`XREF-ACCT-ID` `00000000007` at 25, `FILLER` 14 spaces at 36.

`acctdata.txt`, one `ACCOUNT-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | `00000000007` | account 7 |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | active -- and unread by this job |
| `ACCT-CURR-BAL` | 12 | 12 | `00000001930{` | `+193.00` |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000020650{` | `+2065.00` |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000002640{` | `+264.00` -- unread by this job |
| `ACCT-OPEN-DATE` | 48 | 10 | `2012-10-12` | -- |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | **`2024-12-13`** | the date `:414` compares |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2024-12-13` | equal to expiration in the seed -- see below |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` | `+0.00` |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` | `+0.00` |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | -- |
| `ACCT-GROUP-ID` | 112 | 10 | 10 spaces | blank -- unread by this job |
| `FILLER` | 122 | 178 | 178 spaces | -- |

**`ACCT-REISSUE-DATE` carries the same ten characters as `ACCT-EXPIRAION-DATE`, and it is not the
field under test.** That coincidence is the seed's, not this scenario's, and it is named because
the two fields are ten bytes apart and a reader checking the boundary against offset 68 rather
than 58 would find the same value and conclude the fixture was correct for the wrong reason.
`:414` names `ACCT-EXPIRAION-DATE`, at offset 58.

`ACCT-ACTIVE-STATUS` is `Y` and this job never reads it: `CBTRN02C` has no test on the field
anywhere, so an inactive account would post exactly as this one does. The value is the seed's and
carries no expectation.

`tcatbal.txt`, one `TRAN-CAT-BAL-RECORD`: `TRANCAT-ACCT-ID` `00000000007` at 0,
`TRANCAT-TYPE-CD` `01` at 11, `TRANCAT-CD` `0001` at 13, `TRAN-CAT-BAL` `0000000000{` = `+0.00`
at 17, and 22 ASCII `'0'` of `FILLER` at 28.

Assumptions: `tcatbal.txt` pads its `FILLER` with ASCII `'0'` (`0x30`) while the other three files
pad with SPACE (`0x20`), and the `'0'` is correct rather than a typing slip. Master section 6.1
measures the padding byte per record from the corpus instead of deriving it from the `PICTURE`
clause, and section 6.2.1 records this record as one of the two that contradict the general rule
of section 3.2 -- with the measured byte winning. Substituting spaces here would leave the width
at 50 and every named field decoding correctly, so nothing would fail until the rewritten row was
compared against a golden that carries `'0'`. Section 6.2.1 also names the arm as the reason:
this row is **rewritten** at `:526-528` from the row that was read, so it keeps the seed's padding,
whereas the one posting scenario that reaches the create arm carries NUL instead.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| `tcatbal.txt` is `LF`-terminated where its seed row is `CRLF` | master sections 3.8, 3.9 | A stray `0x0D` absorbed into the 22-byte `'0'` `FILLER` would push the record to 51 bytes and fail the load with every field value correct |
| `DALYTRAN-ORIG-TS` begins `2024-12-13` where the seed row begins `2022-06-10` | master section 11.1 | A business-rule field reshaped deliberately -- it is the scenario. Attested in section 9 |

Assumptions: the seed's `CRLF` is stripped rather than preserved because the record is
fixed-width and the terminator is not part of the record. Preserving it would be defensible for a
byte-for-byte archival copy, but here the `CR` would land inside the padding slice a loader reads
as data, producing a 51-byte row -- the failure mode master section 3.9 exists to prevent, and one
that reports as a width error rather than as a line-ending error, which is why the normalization is
recorded as a departure instead of being left silent.

No non-zero cycle values are used, so the disclosure master section 7.1.3 requires of a fixture
that changes the projected-balance identity does not apply here.

---


## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input data**
-- the feed record has not been processed yet. Master section 8.1 requires this to be stated every
time a blank timestamp is shown, because the byte pattern is identical to the one normalisation
produces and the meaning cannot be recovered from the bytes. It is the same reason the migrated
`ledger.daily_transactions.proc_ts` column is nullable while `ledger.transactions.proc_ts` is
`NOT NULL`, and it is why these blanks decode to SQL `NULL` when the harness loads the row.

Assumptions: the processing stamp is left blank rather than populated because a populated input
`PROC-TS` would have to be either a literal -- which would then be compared against an output
field the comparison deliberately masks -- or a value that varies per run. The posting comparison
masks only the **output** `PROC-TS`, which `:437-438` reads from the wall clock, and asserts
`ORIG-TS`, which `:436` copies verbatim. Blank input therefore keeps the input side fully
deterministic without weakening a single assertion.

`DALYTRAN-ORIG-TS` is the opposite case, and in this folder it is doubly so: it is real,
deterministic input data, it decides the verdict at `:414`, and `:436` copies it unchanged into
the output record, where master section 8.1 says to **assert** it. In the golden's output record
the *processing* stamp appears as 26 spaces, and there those spaces are the product of
normalisation -- the other of the two meanings.

The reshaped date is a literal, not a clock reading. Nothing here derives a value from the
current date, which matters for a boundary fixture more than for any other: a scenario whose
expiration date were computed relative to today would pass until the day it silently began
testing the other side of the boundary.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and the boundary this folder pins is expressed on
the target side in one place rather than per caller:

- **`service/PostingValidationService`** must accept this record. Its expiration reject predicate
  is the **strict complement** of the COBOL pass guard, not a transcription of it, and this
  fixture is the evidence for that ruling.
- **`dto/PostingValidationResult.resolve`** takes `pastAccountExpiration`, documented on the
  parameter itself as the account expiration date being *strictly earlier* than the transaction
  date. A transaction dated exactly on the expiration date therefore arrives with that argument
  `false` and resolves through `accepted(...)` carrying a projected cycle balance of `504.77`.
- **`dto/RejectReason`** holds `RECEIVED_AFTER_ACCOUNT_EXPIRATION` as code 103 with the verbatim
  text `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`. This scenario must not produce it.
- **`service/CategoryBalanceService`** must report the **update** arm and a resulting balance of
  `504.77`.
- **`dto/BatchReturnCode`** supplies the tiers `CLEAN(0)`, `SOFT_WARN(4)` and `HARD_FAILURE(8)`;
  this scenario is `CLEAN`.
- **`dto/BatchRunSummary`** enforces the warn-tier biconditional in both directions, so with zero
  rejects the tier can only be 0 -- a run reporting tier 4 here would fail on the summary alone,
  independently of any byte comparison.

On the target side the date arrives as a typed value rather than as ten characters, so the
`(1:10)` truncation of `:414` becomes a comparison against the date part of a timestamp. The
defect section 4 names -- comparing a ten-character date against a twenty-six-character timestamp
-- is therefore not reachable in the same shape there, but the **inclusiveness** is, which is
what this folder pins.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql):
`ledger.daily_transactions`, `account.card_xref`, `account.accounts` and
`ledger.transaction_category_balances` as inputs, `ledger.transactions` gaining one row, and
`ledger.transaction_rejects` staying empty. A scenario owns only its own rows, per master section
11.3.

The graded rubric 0, 4, 8 belongs to the COBOL parity suite and to the job's own return code
alone (master section 7.1.6). The tier-0 expectation here is **the job's return code, never a
build status**: Maven, Surefire, Failsafe and JUnit are binary pass or fail, and there is no
warn-level green in a Java build to import the vocabulary into.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs; the
oracle is the reference-only `tests/golden/posting/boundary_expiry_equal/` tree, read as the
authority and never written, and no golden is ever regenerated.

### 8.2 Oracle constants that are not expectations for this scenario

Master section 7.1.7's constants describe the **full 300-record seed cycle** -- `_EXPECTED_DAILY`
300, `_EXPECTED_POSTED` 262, `_EXPECTED_REJECTED` 38 all reason `0102`, `_EXPECTED_CONSERVATION`
`77954.70`, and `_EXPECTED_TCAT_INIT_KEYS` 50 becoming `_EXPECTED_TCAT_FINAL_KEYS` 100.
**None is an expectation here**, because this folder is a deliberate one-record sample: its
figures are one processed and zero rejected, and those are scenario figures, not seed aggregates.
In particular, that every rejection in the seed cycle is reason `0102` says nothing about this
folder -- no seed record is dated past its account's expiration, which is exactly why an
expiration scenario has to reshape a date to exist at all. The two loader-geometry constants do
agree, because they describe layouts rather than volumes: `_ACCT = (300, 11)` and
`_TCAT = (50, 17)` match this folder's record lengths and key lengths exactly. The reject slices
`[262:278]`, `[350:354]` and `[354:430]` describe a record this scenario never writes.

### 8.3 The paired folder

[`posting/reject_103_expired`](../reject_103_expired/) carries the same card, the same account,
the same amount and the same category row, with `DALYTRAN-ORIG-TS` one day later at
`2024-12-14`. Read the two together: this folder pins that the boundary is inclusive, and that
folder pins that one day past it rejects. Neither claim survives on its own.

---


## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived.** Every record traces to a published
AWS CardDemo sample seed row under `app/data/ASCII/`:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 7 | **byte-identical** |
| `tcatbal.txt` | `app/data/ASCII/tcatbal.txt` | 7 | **byte-identical** once the `CR` is removed |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | 21 | identical on `[0:36]`; the seed line is **unpadded at 36 bytes**, so 14 spaces are appended to reach the copybook's 50, per master section 3.10 |
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | one business-rule field reshaped, below |

The cross-reference row is row **21** of its seed rather than row 7 because that file is ordered
by card number, and account `00000000007` appears there under card `4859452612877065`.

**It represents no real person and no real account.** The seeds ship with the upstream
open-source project as fabricated demonstration data, and master section 11.1 carries the
tree-level attestation this scenario inherits. Identity and primary-account-number bytes are
taken unchanged from the seed.

Assumptions: the attestation is written out here rather than treated as self-evident because a
card number that satisfies a checksum is indistinguishable by inspection from a live one. Nothing
about the digit string `4859452612877065` tells a reader whether it is safe, so provenance has to
be **attested and traceable to a named seed row** rather than assumed from the fact that the file
sits in a test tree. Colocating that attestation with the bytes it describes is what makes the
folder answerable on its own during an audit, instead of requiring a reviewer to reconstruct the
lineage from a diff against a 300-row seed.

**Exactly one business-rule field is reshaped away from its seed value.** In `dailytran.txt`,
`DALYTRAN-ORIG-TS` at `[278:304]` begins `2024-12-13` where seed row 1 begins `2022-06-10`.
Measured, the difference is **four bytes** in three runs -- offset 281 (`2` becoming `4`), offsets
283 to 284 (`06` becoming `12`) and offset 287 (`0` becoming `3`) -- because the year's first
three characters, the separators and the whole time portion are unchanged. The sixteen characters
after the date, ` 19:27:53.000000`, are the seed's own and take no part in the comparison.

No other field in any file here departs from its seed value: the account row, the category row and
the cross-reference row are the seed's own, and in the daily transaction the identifier, type,
category, source, description, amount, all four merchant fields, the card number and the
processing timestamp are unchanged. The two normalizations named in section 5.4 -- the card-xref
width and the category-balance line ending -- are width and line-ending conformance, **not**
business-rule field changes, so the two statements do not conflict.

Trade-offs: the transaction date is set **exactly** on the expiration date rather than safely
before it, which is what makes this fixture worth having and also what couples it to its sibling.
A date a few days inside the expiry would post under `>` and under `>=` alike and would prove
nothing about the operator; only the equal-date and plus-one-day pair discriminates. The
compromise accepted is that neither half carries its meaning alone: this folder's expected outcome
is a **post**, which is also what `happy_path` expects, so nothing in this directory read on its
own reveals that an operator is being pinned at all. That coupling is the price of pinning the
boundary, and it is why the relationship is not left to prose -- section 8.3 names the sibling,
and `BatchFixtureContractTest` asserts the pair jointly, holding both halves to the same expiry
and to their two specific dates so that re-dating either one fails in this module rather than
quietly dissolving the pair.

Trade-offs: reshaping the transaction date rather than the account's expiration date was a real
choice. Moving `ACCT-EXPIRAION-DATE` back to `2022-06-10` would produce the same equality, and it
would cost more: the account row would stop being byte-identical to its seed in both this folder
and `reject_103_expired`, the reissue date ten bytes away would then differ from the expiration
date in a way the seed never does, and the paired folder would have to reshape the account rather
than the feed -- so the two folders would differ from each other in a field neither scenario is
about. Reshaping the feed's date keeps the account row seed-exact across the pair and confines
the difference between the two folders to one byte of one field -- and that confinement is not
merely claimed here, it is asserted: `BatchFixtureContractTest` requires the two feed records to
differ at **exactly one-based position 288**, the day digit, and nowhere else in 350 bytes. The
cost accepted is that the feed record is not byte-identical to its seed row, which is why the
delta is stated to the byte above.

`app/**` and `tests/**` are REFERENCE-ONLY and are never modified: this folder derives from the
seeds and is compared against the golden masters, and it writes to neither. Master section 11.3
governs the rest and is not restated -- no secret, credential, connection string or endpoint
appears in any file here, money never leaves fixed point, and nothing here modifies the COBOL
baseline or the parity oracle.

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**. `PostTransactionsJobParityIT` resolves each scenario under
`/fixtures/posting/`, seeds the masters from it, launches the posting job and compares the
resulting transaction master, category balances, account master and reject stream against
`tests/golden/posting/boundary_expiry_equal` -- so an edit to these bytes changes what the parity
run asserts. `BatchFixtureContractTest` additionally holds every file here to its declared
geometry and to the values that make the scenario discriminating, so a layout mistake is caught in
this module rather than surfacing later as a comparison failure.

**What is pinned here, and what is only pinned downstream.** The two guards catch different
edits, and knowing which is which is the difference between a one-line fix and a hunt:

| Edit | Caught by | How it reports |
|---|---|---|
| any byte of `dailytran.txt` | `BatchFixtureContractTest` | the pair must differ at **exactly one-based position 288** and nowhere else in 350 bytes, so the assertion names the offset |
| `ACCT-EXPIRAION-DATE`, `ACCT-ID` or either cross-reference key | `BatchFixtureContractTest` | asserted equal across both halves of the pair |
| any other account field -- `ACCT-CURR-BAL`, `ACCT-CREDIT-LIMIT`, the cycle accumulators | `PostTransactionsJobParityIT` only | a money diff against the golden, pointing at the expectation rather than at the edit |

Assumptions: that this folder is read by a run at all is worth stating, because the two sibling
families in this tree behave differently and the difference is invisible from the directory
layout. `preflight/**` and `interest/**` are mirrors -- no test in this module declares either as
a seed root -- so a reader who generalises from them would edit these four files expecting no
consequence. The table above is the reason that mistake is survivable for the feed record and
expensive for the account record: the contract test pins the feed to a single differing byte, but
it does not pin the balances, so a changed credit limit passes every check in this module and
surfaces later as a golden mismatch. Section 1.5 of the master records the same mirror-versus-driven
split from the consuming classes.

---

*This README is the mandatory Explainability carrier for the four record files in this
directory, required by master section 10 and by user-specified Rule 1. Two gates touch it and
neither can decide whether it is true: `config/rule1/rule1_gate.py` checks the **form** of the
rationale labels above, repository-wide and including Markdown, which is why they are written
plain rather than emphasised; `config/checkstyle/checkstyle.xml` limits its audit set to `java`,
so it reads none of this prose. Whether each rationale names a real consequence, and whether every
byte value and line citation here is true, are review obligations no lexical gate can decide --
which is why the numbers above are stated to the offset and the citations to the line, so a
reviewer can check them.*
