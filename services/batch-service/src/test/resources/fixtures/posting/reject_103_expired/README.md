# `posting/reject_103_expired` -- one day past the expiration date

> **Purpose.** Pin reject reason **103**: a transaction dated **one day** after the account's
> expiration date is refused with the message `TRANSACTION RECEIVED AFTER ACCT EXPIRATION`, and the
> run reports the soft-warn tier. This is the exclusive half of the pair whose inclusive half is
> `posting/boundary_expiry_equal`: the two folders differ in one byte of one field, and between them
> they fix the comparison at `app/cbl/CBTRN02C.cbl:414` as `>=` rather than `>`.
>
> **Source of truth.** `app/cbl/CBTRN02C.cbl` for the behaviour and `app/jcl/POSTTRAN.jcl` for the
> dataset contract, both reference-only and both cited below by line; `app/cpy/CVTRA06Y.cpy`,
> `app/cpy/CVACT03Y.cpy`, `app/cpy/CVACT01Y.cpy` and `app/cpy/CVTRA01Y.cpy` for the record layouts;
> the seed datasets under `app/data/ASCII/` for the bytes;
> `tests/golden/posting/reject_103_expired/` for the expected outputs; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document cites by section
> rather than restating (master section 1.3).
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. This whole file is pure ASCII for
> the reason that section gives.

**This README is the mandatory Explainability carrier for the four record files beside it.** Master
section 1.2 records why: a fixed-width record file cannot carry a comment of any kind, because every
byte position is meaningful and a comment on its own line is a physical row of the wrong length.
Master section 10 makes the artifact mandatory rather than courteous, and the section order below is
the one it fixes.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction whose `DALYTRAN-ORIG-TS` begins **`2024-12-14`**, against account
`00000000007` whose `ACCT-EXPIRAION-DATE` is **`2024-12-13`** -- one day earlier. The amount,
`+504.77`, is far inside the `+2065.00` credit limit, so the credit-limit test passes and 103 is the
only reason that can be assigned.

Three properties make this folder worth its own document:

- **the margin is one day**, the least significant unit the `X(10)` date can express, so a
  comparison implemented with any slack at all admits the record;
- **the comparison is on a ten-character prefix** of a twenty-six-character field, so the sixteen
  characters of time after the date take no part in the decision even though they differ from
  nothing;
- **this is the reason that overwrites another.** Master section 7.1.4 records that 103 is assigned
  after 102 with no guard between them, so this reason is the one a record tripping both ends up
  with -- and this folder is deliberately **not** such a record, so that the reason it reports is
  earned by the date alone.

Alternatives Considered: dating the record far past expiration -- a year, say -- rather than one day.
Rejected because a large margin proves only that the program compares dates at all, while a one-day
margin additionally proves the comparison is exclusive at its boundary and that it reads the day
field rather than truncating to a month or a year. A fixture dated `2025-12-14` would pass under a
comparison that ignored the day entirely, and so would its inclusive pair.

Assumptions: keeping the amount inside the credit limit is load-bearing rather than incidental. If
the amount also exceeded the limit, `:410` would assign 102 and `:417` would then overwrite it with
103, and the reject stream would read exactly as it does now -- so the scenario would appear to pass
while proving nothing about the date, because the reason would have been reachable through the
amount. The only way to earn 103 is to make it the sole assignment.

Trade-offs: the one-day margin buys the sharpest possible statement about the operator and concedes
that **neither folder is self-sufficient**. This one shows that a date past expiration is refused; it
cannot show where the boundary falls, because a refusal is equally consistent with `>=` and with `>`.
Only [`../boundary_expiry_equal`](../boundary_expiry_equal/README.md), where the dates are equal and
the record posts, excludes `>`. The compromise accepted is therefore a maintenance coupling between
two directories: a one-byte edit to the date in either half silently converts the pair into two
copies of the same claim, with both halves still passing their own byte checks. What limits the cost
is that the coupling is asserted rather than trusted -- `BatchFixtureContractTest` pins both dates,
holds the account and cross-reference rows equal across the pair, and requires the two feeds to
differ in exactly one byte position, so the pair cannot quietly stop discriminating.

---

## 2. The business rule, cited by program and line

**`app/cbl/CBTRN02C.cbl:414-420`, inside the `NOT INVALID KEY` branch of `1500-B-LOOKUP-ACCT`.**

```cobol
     IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
         CONTINUE
     ELSE
         MOVE 103 TO WS-VALIDATION-FAIL-REASON
         MOVE 'TRANSACTION RECEIVED AFTER ACCT EXPIRATION'
           TO WS-VALIDATION-FAIL-REASON-DESC
     END-IF
```

That is `:414-420`, reproduced faithfully. The guard is at **`:414`**, the reason is assigned at
**`:417`** and the message literal is at **`:418`** -- master section 7.1.1 gives both of the last
two because a citation may reasonably point at either.

Three properties of the guard, each checked against the source:

- **`(1:10)` is a reference modifier** taking the first ten characters of the 26-character
  originating timestamp -- one-based in COBOL, so bytes `[0:10]` zero-based, which is the date
  portion `2024-12-14` and nothing else.
- **Both operands are alphanumeric.** `ACCT-EXPIRAION-DATE` is `PIC X(10)` at
  `app/cpy/CVACT01Y.cpy:11` and the modified reference is a character substring, so the comparison is
  a left-to-right byte comparison. It is equivalent to a date comparison **only** because both sides
  are zero-padded `YYYY-MM-DD`, where lexical and chronological order coincide.
- **The field name carries the baseline's misspelling.** `EXPIRAION` is what line 11 says, and a
  citation of `:414` must quote it that way; master section 9.3 confines the three spelling
  corrections to target column names.

Assumptions: the whole scenario rests on an external property of the data format rather than on
anything the program does -- that `YYYY-MM-DD` is zero-padded and big-endian, so byte order and
chronological order coincide. Nothing at `:414` enforces it. Both dates here are ten-character fields
compared left to right, and the fixture is only a valid test of an expiration rule because those two
orderings agree; a fixture written with a `DD-MM-YYYY` or unpadded date would compare `2024-12-14`
against something whose byte order said the opposite of its calendar order, and the reject this folder
expects would then be produced by the wrong reason or not at all. It also means the equivalence is a
property of the **baseline only**: the migrated job compares typed dates, so it inherits the outcome
but not the mechanism, and no target-side code may lean on the string comparison.

**The reject is produced by the `ELSE` of a passing guard**, not by a failing test: `:414` asks
whether expiration is at least the transaction date, and the reject lives on the arm where it is not.

**Reaching this line requires two successes and one pass.** The card lookup must find the card
(`:383`, `:388`), the account lookup must find the account (`:395`, `:400`) because `:372-373`
performs it only while the reason is zero, and the credit-limit test at `:407` must pass -- not for
control-flow reasons, since nothing guards `:414`, but because this scenario means the reason to be
earned by the date.

The rest of the walk, all in `app/cbl/CBTRN02C.cbl`:

| Step | Line | What happens for this record |
|---|---|---|
| Read the feed | `:204` | status `'00'`, so the loop body runs |
| Count it | `:206` | processed count becomes 1 |
| Cross-reference hits | `:382-383`, `:388` | card `4859452612877065` resolves to account `00000000007` |
| Account hits | `:394-395`, `:400` | account `00000000007` is read, so both boundary tests are evaluated |
| Credit-limit test **passes** | `:403-405`, `:407-408` | `WS-TEMP-BAL` is `504.77`; `2065.00 >= 504.77` is true, so 102 is **not** set |
| **Expiration test fails** | **`:414`**, `:416-419` | `2024-12-13 >= 2024-12-14` is **false**, so reason 103 and its message are assigned |
| Take the reject path | `:211`, `:213-215` | the reason is not 0, so the reject counter is incremented and `2500-WRITE-REJECT-REC` runs |
| Build the reject | `:447-448` | `:447` moves the entire 350-byte `DALYTRAN-RECORD` verbatim; `:448` appends the 80-byte trailer |
| Write it | `:451` | one 430-byte record; `:452` requires status `'00'` |
| Grade the run | `:229-230` | the rejected count exceeds zero, so `MOVE 4 TO RETURN-CODE` |

**Nothing is posted.** `2000-POST-TRANSACTION` (`:424-444`) is reached only from `:212`, on the branch
this record does not take.

---

## 3. Returns -- the expected outcome

The return code is **4**, the soft-warn tier -- `:229-230` moves 4 because the rejected count is one.
Master section 7.1.6 fixes that tier as a **fixture expectation value** belonging to the COBOL parity
suite's graded rubric, never to a Java build gate.

**The comparison, character by character.**

| Operand | Value | Source |
|---|---|---|
| `ACCT-EXPIRAION-DATE` | `2024-12-13` | `acctdata.txt` `[58:68]` |
| `DALYTRAN-ORIG-TS (1:10)` | `2024-12-14` | `dailytran.txt` `[278:288]` |
| First differing character | position **10**, `3` against `4` | -- |
| `2024-12-13 >= 2024-12-14` | **false**, by one day | `:414` |

The two strings agree on their first nine characters and differ only in the tenth, which is the units
digit of the day. That is the whole margin.

**The reject record**, one row of 430 bytes:

| Slice | Width | Contents |
|---|---:|---|
| `[0:350]` | 350 | the input `DALYTRAN-RECORD` **verbatim**, including card `4859452612877065` at `[262:278]` and the originating timestamp `2024-12-14 19:27:53.000000` at `[278:304]` |
| `[350:354]` | 4 | **`0103`** |
| `[354:430]` | 76 | **`TRANSACTION RECEIVED AFTER ACCT EXPIRATION`** followed by 34 spaces |

`TRANSACTION RECEIVED AFTER ACCT EXPIRATION` is 42 characters -- the longest of the four messages, so
it has the shortest padding at 34 spaces. The padding is what `MOVE` supplies at `:418`, per the
mechanism master section 6.3 records for the trailer description. **The message says `ACCT` and not
`ACCOUNT`**, and it is carried verbatim; abbreviating or expanding it would change published text.

**The refused date is recoverable from the reject stream**, at `[278:304]` of the verbatim image,
which is what lets a comparison confirm that the record refused was the one dated a day late. Note
that the expiration date it was compared **against** is not in the image -- the reject carries the
feed record, and the feed record has no expiration field.

**The code is `0103` on the wire and the integer `103` in storage.** `WS-VALIDATION-FAIL-REASON` is
`PIC 9(04)` at `app/cbl/CBTRN02C.cbl:181`, so the trailer carries four characters zero-padded. The
migrated `ledger.transaction_rejects.reason_code` column is `SMALLINT` and holds the integer. Master
section 7.1.2 fixes both representations and warns that an expectation must name which one it asserts.

Assumptions: the two forms are one value in two representations, and which one an assertion is written
against is decided by where it reads. The four characters exist because the field is declared `9(04)`,
not because anything formats it, so the zero is data at that offset; the integer exists because
`SMALLINT` has no width. The reason to state this at all is that **getting it wrong fails in a way that
looks like the wrong reject reason**: a byte comparison against `103` finds `010` at `[350:353]` and
misaligns everything after it, and a column comparison against the string `0103` fails a type it never
mentions. Neither failure message says "representation", so a reader spends the debugging cycle on the
business rule instead.

**Nothing else changes.**

| Output | Expected | Authority |
|---|---|---|
| Posted transaction records | **0** | `2900-WRITE-TRANSACTION-FILE` at `:562` is reached only from `:442` |
| `tcatbal.txt` after the run | **unchanged, byte for byte** at `+0.00` | `2700-UPDATE-TCATBAL` at `:467` is reached only from `:440` |
| `acctdata.txt` after the run | **unchanged, byte for byte** at `+193.00` | opened I-O at `:311`, but `2800-UPDATE-ACCOUNT-REC` at `:545` is reached only from `:441` |
| `cardxref.txt` after the run | **unchanged, byte for byte** | opened INPUT at `:275`; the job has no statement that writes it |
| Counters | 1 processed, 1 rejected | `:227-228` |

Assumptions: the golden corpus is corroboration, never the derivation. In
`tests/golden/posting/reject_103_expired/`, `return_code.expected` is the single byte `4`,
`dalyrejs.expected` is **431 bytes** -- 430 plus the single trailing `LF` of master section 3.9 --
`tranfile.expected` is **0 bytes**, and `acctdat.expected` and `tcatbal.expected` are byte-identical
to this folder's `acctdata.txt` and `tcatbal.txt` at 301 and 51 bytes.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**The record must not post, and a day is the whole margin.** An implementation reading the guard as
`>` posts this record: return code 0, one transaction record, an empty reject stream, an account
balance of `+697.77` and a category balance of `+504.77`. All four outputs change, and the cause is a
single character -- which is why the pairing with `posting/boundary_expiry_equal` is what identifies
which of the two folders a divergence belongs to.

**102 must not be reported alongside or instead of 103.** The amount projects to `504.77` against a
`2065.00` limit, so `:410` is unreachable here. That is deliberate: master section 7.1.4 records that
103 overwrites 102 when both fire, so a fixture that tripped both would produce **this folder's exact
reject bytes** -- code `0103`, the expiration message -- while proving nothing about the date. The
scenario is only informative because the date is the sole cause.

**For a future author, the corollary is worth stating in advance.** No scenario in this tree currently
trips both boundaries, and if one is added its expected reject code is **`0103`**, not `0102`, with
`TRANSACTION RECEIVED AFTER ACCT EXPIRATION` as its expected message -- and its own README must say so
explicitly, naming `:407-413` and `:414-420` as the two unguarded blocks and 103 as the surviving
assignment, exactly as master section 7.1.4 requires of any scenario touching the overwrite. **This
folder cannot stand in for that one.** Its output is byte-identical to what such a record would
produce, which is precisely why the two cases have to be told apart by their inputs rather than by
their reject bytes: a reader who found `0103` in a reject stream cannot tell from the stream alone
whether one guard fired or two.

**100 and 101 must not be reported.** The card is in the cross-reference, so `:385` cannot fire; the
resolved account is present in the master, so `:397` cannot fire. Reaching `:414` at all presupposes
both.

**The comparison must not include the time.** This is the negative that distinguishes a correct
implementation from one that dropped the `(1:10)` modifier, and its effect here is the opposite of its
effect in the paired folder. Comparing the whole fields blank-extends the ten-byte expiration date and
compares it against `2024-12-14 19:27:53.000000`; the result is still that the account date is
smaller, so **this scenario still rejects** and the defect is invisible. Only
`posting/boundary_expiry_equal`, where the dates are equal and the trailing time is the sole
difference, exposes it. Recorded here so that a reader does not conclude this folder covers the case.

**The comparison must not be reduced to a month or a year.** `2024-12` equals `2024-12` and `2024`
equals `2024`, so an implementation truncating either way posts this record. The one-day margin is
what makes that reachable; a fixture dated a year late would not detect it.

**Code 109 must not be expected.** `:556` assigns it inside the `INVALID KEY` branch of the account
`REWRITE` at `:554-559`, on the posting path -- not taken here -- and writes no reject record. Master
section 7.1.1 fixes the persisted domain as exactly `{100, 101, 102, 103}` and records 109 as a dead
write.

**No abend occurs.** The reachable `9999-ABEND-PROGRAM` sites are all status-guarded: the six opens
(`:250`, `:268`, `:287`, `:305`, `:323`, `:341`), the feed read (`:363-366`), the reject write
(`:463`) and the six closes (`:596`, `:614`, `:633`, `:651`, `:669`, `:688`). An expired transaction
is a business reject, not an I/O condition, and `1500-B-LOOKUP-ACCT` has no abend site of its own.

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
# WHAT: assert the byte geometry, and additionally assert the two relations this scenario turns on --
#       that the feed's date prefix is exactly one day after the expiration date, and that the amount
#       stays inside the credit limit. Run from this directory.
# WHY : Assumptions: this fixture has two independent ways to stop being informative and neither
#       changes a single byte count. A date drift of one day makes it the inclusive case and inverts
#       the outcome; an amount over the limit makes reason 102 fire and be overwritten by 103, which
#       produces byte-identical output while proving nothing about the date (master section 7.1.4).
#       Checking both at authoring time is the only way to tell a scenario that earns its reason from
#       one that inherits it. The date arithmetic uses the standard library rather than string
#       comparison, because "one day later" across a month or year boundary is not a lexical
#       property.
python3 - <<'PY'
from datetime import date, timedelta
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
feed = open("dailytran.txt", "rb").read()
account = open("acctdata.txt", "rb").read()
feed_date = date.fromisoformat(feed[278:288].decode("ascii"))
expiration = date.fromisoformat(account[58:68].decode("ascii"))
print("feed date", feed_date, "expiration", expiration, "days late",
      (feed_date - expiration).days,
      "| OK one day past" if feed_date - expiration == timedelta(days=1)
      else "| MISMATCH not a one-day excess")
print("amount cents", cents(feed[132:143]), "limit cents", cents(account[24:36]),
      "| OK inside the limit, so 102 cannot fire"
      if cents(feed[132:143]) <= cents(account[24:36])
      else "| MISMATCH 102 would fire and be overwritten by 103")
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
| `DALYTRAN-AMT` | 132 | 11 | `0000005047G` | `+504.77` -- inside the limit, so 102 cannot fire |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | -- |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | -- |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | -- |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | -- |
| `DALYTRAN-CARD-NUM` | 262 | 16 | `4859452612877065` | resolves, so 100 cannot fire |
| `DALYTRAN-ORIG-TS` | 278 | 26 | **`2024-12-14 19:27:53.000000`** | reshaped, section 9; `[278:288]` is what `:414` compares |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

`cardxref.txt`: `XREF-CARD-NUM` `4859452612877065` at 0, `XREF-CUST-ID` `000000007` at 16,
`XREF-ACCT-ID` `00000000007` at 25, `FILLER` 14 spaces at 36.

`acctdata.txt`, one `ACCOUNT-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | `00000000007` | account 7 |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | active -- and unread by this job |
| `ACCT-CURR-BAL` | 12 | 12 | `00000001930{` | `+193.00` |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000020650{` | `+2065.00` -- the amount stays inside it |
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
field under test.** The coincidence is the seed's, not this scenario's, and it is named because the
two fields are ten bytes apart: a reader checking the boundary at offset 68 rather than 58 finds the
same value and concludes the fixture is right for the wrong reason. `:414` names
`ACCT-EXPIRAION-DATE`, at offset 58. **`ACCT-OPEN-DATE` at offset 48 is a third `X(10)` date in the
same record**, and it is likewise unread by this program.

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

Assumptions: the padding byte is a measured property of the corpus rather than something derivable from
the `PICTURE` clause, and `TRAN-CAT-BAL-RECORD` is precisely a record where the general rule and the
measurement disagree -- `FILLER X(22)` reads as text and would pad with SPACE under section 3.2 alone.
Deriving it that way here would produce twenty-two wrong bytes in a row that is asserted **unchanged**,
so the mismatch would surface as a comparison failure on the one file this scenario never touches, and
the reader would look for a write that does not exist. The measured value is used because it is
measured; all twenty-two bytes at `[28:50]` were confirmed to be `'0'` and nothing else.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| `tcatbal.txt` is `LF`-terminated where its seed is `CRLF` | master sections 3.8, 3.9 | A stray `0x0D` absorbed into the 22-byte `'0'` `FILLER` would push the record to 51 bytes and fail the load with every field value correct |
| `DALYTRAN-ORIG-TS` begins `2024-12-14` where the seed row begins `2022-06-10` | master section 11.1 | A business-rule field reshaped deliberately -- it is the scenario. Attested in section 9 |
| The amount is held inside the credit limit so that 103 is earned rather than inherited | master section 7.1.4 | Named here because section 7.1.4 requires a scenario touching the overwrite to say so. No amount was reshaped; the seed value already satisfies it |

No non-zero cycle values are used, so the disclosure master section 7.1.3 requires of a fixture that
changes the projected-balance identity does not apply here.

Assumptions: the row above states the consequence of a surviving `CR`; what it does not state is why the
normalisation has to happen **per row** rather than once per file. The seed is mixed, not uniformly
`CRLF` -- forty-nine terminators across fifty rows, its last row ending in a bare `LF` (master section
3.8) -- so a reader who inspected the seed's final line would conclude no conversion was needed and
would be wrong for the other forty-nine. Two further points a reader may otherwise get backwards: the
resulting failure names the record length, not the byte that caused it, so the diagnosis is slower than
the defect is deep; and master section 3.8 requires only a deliberately **preserved** `CRLF` to be
declared, silence meaning `LF`, so this note is recorded for the reader rather than to satisfy the
contract. The folder is `CR`-free by measurement, not by intent.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input data** -- the
feed record has not been processed yet. Master section 8.1 requires this to be stated every time a
blank timestamp is shown, because the byte pattern is identical to the one normalisation produces and
the meaning cannot be recovered from the bytes. It is the same reason the migrated
`ledger.daily_transactions.proc_ts` column is nullable while `ledger.transactions.proc_ts` is
`NOT NULL`.

Assumptions: twenty-six blanks are load-bearing input data here, and the reason to say so is that the
byte pattern is **ambiguous on its own**. A normaliser that masks a processing timestamp before a golden
comparison writes exactly the same twenty-six blanks, so the same bytes mean "not yet processed" in an
input and "deliberately not compared" in an output, and nothing in the bytes distinguishes them. Filling
the field with a plausible timestamp instead would be worse than useless: `:447` copies the record
verbatim, so the invented value would travel into the reject image and be compared byte for byte against
a golden that has blanks there, and the run would fail on a field the scenario is not about. The width is
twenty-six because that is the declared field width, and it is asserted rather than assumed -- `[304:330]`
was confirmed to be blank in both the fixture and the committed reject golden.

**Both 26-byte fields travel into the reject record unchanged, and neither is masked there.** `:447`
copies the entire 350-byte input image, so the reject's `[278:304]` carries the reshaped
`2024-12-14 19:27:53.000000` and its `[304:330]` carries the same 26 spaces -- still meaning "not
processed". A reject record contains **no clock reading at all**, which is why `dalyrejs.expected` can
be compared byte for byte with no field normalised, and why the reshaped date is directly assertable
in the output rather than only inferable from the reason code.

**The reshaped date is a literal, not a clock reading.** Nothing here derives a value from the current
date, which matters more for this fixture than for most: a scenario whose expiration date were
computed relative to today would pass until the day it silently began testing the other side of the
boundary, and an expired-transaction scenario is exactly the kind that invites such a construction.

Everything else holds by construction: every byte here is literal, there is no random identifier and
no environment-derived string, and each test provisions and tears down its own workspace, per master
section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and this scenario is one of the four the module's
posting-validation assertions read directly:

- **`dto/RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION`** carries the code `103` and the description
  `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` **verbatim**, including the abbreviation, and renders
  both representations separately -- `codeField()` for the four-character `0103` and `code()` for the
  integer the `SMALLINT` column stores. Its 76-character `descriptionField()` is the tightest fit of
  the four descriptions, with 34 characters of padding.
- **`service/PostingValidationService`** decides only **which** conditions a transaction failed, and
  its expiration predicate is the **strict complement** of the inclusive pass guard at `:414`: it holds
  when the expiration date is strictly earlier than the ten-character date prefix, which is why an
  equal date is not a finding and this folder's one-day-later date is. It deliberately **does not
  resolve precedence**; it reports both boundary findings independently and lets the result type pick
  the survivor. That split is the reason this folder can assert a single-reason path at all -- if the
  service short-circuited on the first failing condition, the reported reason would depend on the order
  the two tests happen to be written in, which is the one thing `:407-413` and `:414-420` do not fix.
- **`dto/PostingValidationResult.resolve`** takes `pastAccountExpiration` as the **strict complement**
  of the inclusive guard at `:414`, documented on the parameter itself, so this folder is the fixture
  on the side where that argument is `true`. Like reason 102 and unlike the two lookup failures, this
  outcome **carries a projected cycle balance**, because `:403-405` did run -- so the `504.77`
  projection is asserted alongside the reason even though it played no part in it.
- **`dto/RejectReason.baselineAssignmentOrder` and `lastWriterWins`** are how the target preserves
  master section 7.1.4's overwrite: `resolve` selects the surviving boundary reason as a **maximum**
  under the recorded assignment order rather than by an either-or, so a record tripping both
  conditions reports 103 regardless of the order the two candidates are tested. This folder is the
  single-condition case, and its value is that it must report 103 **without** relying on that
  selection at all.
- **`dto/BatchRunSummary`** enforces the warn-tier biconditional in both directions, so this scenario
  must report the warn tier with one processed, one rejected and zero written.

On the target side the two dates arrive as typed values rather than as characters, so the byte-wise
comparison and the `(1:10)` truncation both disappear; what survives is the exclusiveness at the
boundary, which is what this folder pins.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql),
whose `CREATE TABLE ledger.transaction_rejects` declares the reject contract by composition --
`raw_record CHAR(350)`, `reason_code SMALLINT`, `reason_desc VARCHAR(76)`, currently at lines 554 to
558. That file is the authority for the schema and it is deliberately not re-derived here.

Assumptions: the table is cited **by name** and the line span only as a convenience, because a line
number in a 632-line file that is still being extended is the least durable part of a citation. Master
section 7.1.2 gives this same contract at lines 492 to 494, which is where it sat earlier; those lines
now carry an interest-accrual comment, and the composition is unchanged. The name resolves under a
search whatever moves above it, which the number does not.

The graded rubric 0, 4, 8 belongs to the COBOL parity suite alone (master section 7.1.6). The
warn-tier expectation here is the job's return code, not a build status.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs; the oracle is
the reference-only `tests/golden/posting/reject_103_expired/` tree, read as the authority and never
written, and no golden is ever regenerated.

### 8.2 Oracle constants that are not expectations for this scenario

Master section 7.1.7's constants describe the **full 300-record seed cycle** -- 300 daily records, 262
posted, 38 rejected **all reason `0102`**, a conservation total of `77954.70`, 50 category keys
becoming 100. **None is an expectation here**, and the `0102` figure carries the informative part:
across the unmodified seed feed, **no** record produces reason 103, because no seed transaction is
dated past its account's expiration. The reason is unreachable from the seeds as shipped, which is why
this scenario has to reshape a date to exist at all -- and, read together with master section 7.1.4,
it also means the overwrite of 102 by 103 is unreachable from the seeds too, so this pair of folders
is the only place in the corpus where the interaction can be reasoned about. The reject-slice
constants -- card `[262:278]`, code `[350:354]`, message `[354:430]`, `_REJ_RECLEN` 430 -- do agree
with section 3 and are a cross-check rather than a derivation.

### 8.3 The paired folder

`posting/boundary_expiry_equal` carries the same card, the same account, the same amount and the same
category row, with `DALYTRAN-ORIG-TS` one day earlier at `2024-12-13` -- a **single byte** different,
at offset 287. Read the two together: that folder pins that the boundary is inclusive, and this one
pins that one day past it rejects. Neither claim survives on its own, and only that folder catches the
whole-field comparison defect section 4 names.

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

Assumptions: the attestation is written down because it **cannot be recovered by looking at the bytes**.
`4859452612877065` is sixteen digits, opens on the digit that denotes a real card scheme, and **satisfies
the Luhn check** -- so it is indistinguishable by inspection from a live primary account number, and
validating it only confirms it is well formed, which is exactly what a generator produces. No test a
reader can run on the number itself will establish that it is synthetic. The provenance is therefore
stated on the authority of the seed file the row came from, and the seed row is cited per file above so
that the claim is checkable against something. That is also why the reshaping inventory below is
exhaustive: the only way to confirm no identity byte was invented is to be told which bytes were changed
and to find every other one unchanged in the seed.

**Exactly one business-rule field is reshaped away from its seed value.** In `dailytran.txt`,
`DALYTRAN-ORIG-TS` at `[278:304]` begins `2024-12-14` where seed row 1 begins `2022-06-10`. Measured,
the difference is **four bytes** in three runs -- offset 281 (`2` becoming `4`), offsets 283 to 284
(`06` becoming `12`) and offset 287 (`0` becoming `4`) -- because the year's first three characters,
the separators and the whole time portion are unchanged. The sixteen characters after the date,
` 19:27:53.000000`, are the seed's own and take no part in the comparison.

**The delta against the paired folder is smaller still: one byte.**
`posting/boundary_expiry_equal` carries `2024-12-13` where this folder carries `2024-12-14`, differing
only at offset 287. Both folders reshape the same field of the same seed row, to two values one day
apart.

Assumptions: the sixteen characters of time were left at the seed's own ` 19:27:53.000000` in both halves
deliberately, and their being **identical** is itself part of what the pair demonstrates. `:414` slices
ten characters, so a differing time could not change either verdict -- but a reader has no way to know
that from one folder, whereas a pair whose feeds differ in one byte of the date and agree in every byte of
the time makes the irrelevance of the time visible rather than asserted. Advancing the clock as well would
have cost that: two feeds differing in the date *and* the time would still produce these outcomes, so a
divergence could no longer be attributed to the date, and the folder would prove less while looking like
it proved more. It is also what keeps the delta at one byte and lets `BatchFixtureContractTest` assert a
single differing position instead of a set.

No other field in any file here departs from its seed value: the account row, the category row and the
cross-reference row are the seed's own, and in the daily transaction the identifier, type, category,
source, description, amount, all four merchant fields, the card number and the processing timestamp
are unchanged. The two normalizations named in section 5.4 -- the card-xref width and the
category-balance line ending -- are width and line-ending conformance, **not** business-rule field
changes, so the two statements do not conflict.

Trade-offs: reshaping the transaction date rather than the account's expiration date was the choice,
and it is the same one `posting/boundary_expiry_equal` makes for the same reason. Moving
`ACCT-EXPIRAION-DATE` earlier would produce the same inequality and cost more: the account row would
stop being byte-identical to its seed in both folders of the pair, the reissue date ten bytes away
would then differ from the expiration date in a way no seed row does -- removing the very coincidence
section 5.3 warns about, and with it the warning's usefulness -- and the two folders would differ from
each other in a field neither scenario is about. Reshaping the feed's date keeps the account row
seed-exact across the pair and confines the difference between the two folders to one byte.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string or
endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the COBOL
baseline or the parity oracle.

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**, and **three** classes read it. `PostTransactionsJobParityIT`
resolves each scenario under `/fixtures/posting/`, seeds the masters from it, launches the posting
job and compares the resulting transaction master, category balances, account master and reject
stream against `tests/golden/posting/reject_103_expired`. `PostTransactionsJobTest` resolves the same
scenario under `fixtures/posting/` at the unit tier and seeds all four relations from the same
bytes, so both tiers read this folder -- an edit to these bytes changes what BOTH runs assert.
`BatchFixtureContractTest` additionally holds every file here to its declared geometry, to the
values that make the scenario discriminating and to its committed SHA-256 per master section 11.5,
so a layout mistake or an unintended byte change is caught in this module rather than surfacing
later as a comparison failure.

**The distinction above is the most expensive thing in this document to get wrong.** A reader who
believes these bytes drive nothing will edit them expecting no consequence, and the consequence is a
parity comparison against a golden that no longer matches. An earlier draft of this section did say
that -- that no test in this module opened the folder and that the corpus was a reference mirror --
which was true of the tree at the time and stopped being true when the parity class took
`/fixtures/posting/` as its seed root. It is stated positively here, and the sentence a reader should
carry away is the first one: **driven input, not mirror.**

Assumptions: a correction like the one above looks as though it belongs under Rule 1's fourth category
name -- the one its line 32 scopes to *replacing existing code* -- and that category is **factually
unavailable in this file**, for the reason master section 1.4 gives. Nothing in this folder replaces
anything: all five files are net-new, the COBOL baseline they derive from is untouched, and the house
fixture tree they parallel still runs unchanged. Section 1.4 names the two categories that carry such a
difference instead, and this note uses one of them. The category name itself is left unwritten here as
well as unused, because a reviewer auditing this tree finds each rationale by literal string search, and
a mention inside a sentence saying the category does not apply is indistinguishable from a use of it.
The header of this file enumerates exactly the three labels it uses, and that enumeration is meant to be
verifiable by the same search.

Assumptions: this section exists because two record files sitting in the same tree can differ in
whether a job opens them, and the difference is invisible from the layout. Master section 1.5 holds the
measurement, taken from the resource root each consuming class declares rather than from prose: all four
files in every one of the NINE `posting/**` scenarios are opened -- by `PostTransactionsJobTest` under
the classpath prefix `fixtures/posting/` and by `PostTransactionsJobParityIT` under `/fixtures/posting/`
-- all four files in every one of the THREE `interest/**` scenarios are opened by
`CalculateInterestJobTest` under `fixtures/interest/`, and `PreflightDailyTransactionsJobTest` opens the
three `preflight/**` feed files plus `preflight/unmatched_card/acctdata.txt` under
`fixtures/preflight/`. **53 of the 62 record files in this tree are live job input**; master section 1.5
names the nine that are not.

Assumptions: this paragraph previously said the sibling `preflight/**` and `interest/**` families were
mirrors that no test in this module read, and that `CalculateInterestJobTest` resolved its inputs from
the repository-root `tests/fixtures/interest/` tree. Both claims were false. That class declares
`FIXTURE_INTEREST_ROOT` as the classpath prefix `fixtures/interest/` and loads through
`getClassLoader().getResourceAsStream(...)`, so it reads this tree; it DISCUSSES the reference oracle
tree in its prose, and the two were conflated -- a path named in a docstring is not a path being opened.
The correction matters in exactly the direction the paragraph was warning about: a reader told that a
sibling directory was inert would carry that belief into it and edit a live job input believing the
change was free.

---

*This README is the mandatory Explainability carrier for the four record files in this directory,
required by master section 10 and by user-specified Rule 1. **No gate reads this prose.**
`config/checkstyle/checkstyle.xml` limits its audit set to `java`, and `config/rule1/rule1_gate.py`
excludes every path containing `/src/test/resources/fixtures/` in its `_is_governed` check, which is
this path -- so neither its `labels` check nor its `what` check ever opens this file. Refactoring
Rationale: this paragraph previously credited `config/rule1/rule1_gate.py` with deciding the form of
the rationale labels above "repository-wide and including Markdown". The gate does run
repository-wide, which is what made the claim plausible, but this path is explicitly outside its
remit, so the sentence let a green build be read as evidence about a document the gate never opens.
The canonical label form is used here regardless, because `docs/CODE_DOCUMENTATION_STANDARD.md` fixes
one written form repository-wide and a Rule 1 audit finds a rationale by literal string search;
compliance in this path is therefore **review-based** rather than mechanical. Whether each rationale
names a real consequence, and whether every number and line citation is true, are review obligations no
lexical gate can decide.*
