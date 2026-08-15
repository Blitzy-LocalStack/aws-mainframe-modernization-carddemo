# `posting/happy_path` -- one clean posting through the update arm

> **Purpose.** Pin the reference posting case: a single daily transaction whose card resolves,
> whose account exists, which passes both inclusive boundary tests, and whose
> transaction-category-balance row **already exists**, so the posting unit of work runs in full
> and moves all three of the job's mutable outputs. This is the scenario every other posting
> scenario is a deviation from, so its bytes and its arithmetic are the domain's baseline.
>
> **Source of truth.** `app/cbl/CBTRN02C.cbl` for the behaviour and `app/jcl/POSTTRAN.jcl` for
> the dataset contract, both reference-only and both cited below by line;
> `app/cpy/CVTRA06Y.cpy`, `app/cpy/CVACT03Y.cpy`, `app/cpy/CVACT01Y.cpy` and
> `app/cpy/CVTRA01Y.cpy` for the record layouts; the seed datasets under `app/data/ASCII/` for
> the bytes; `tests/golden/posting/happy_path/` for the expected outputs; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document cites by
> section rather than restating (master section 1.3).
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup. That is the one permitted
> written form fixed by `docs/CODE_DOCUMENTATION_STANDARD.md` and required of this tree by
> master section 1.4, and this whole file is pure ASCII for the reason that section gives.

**This README is the mandatory Explainability carrier for the four record files beside it.**
Master section 1.2 records why one is needed: a fixed-width record file cannot carry a comment
of any kind, not even a header line, because every byte position is meaningful and a comment
occupying its own line is a physical row of the wrong length. The whole folder's Rule 1
obligation therefore lands here, and master section 10 makes the artifact mandatory rather
than courteous.

The section order is the one master section 10 mandates -- intent, then the rule cited by line,
then the outcome, then the bytes and their governance, then the provenance attestation.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction of **`+504.77`** on card `4859452612877065`, against account
`00000000007`, whose credit limit is `+2065.00` and whose expiration date is `2024-12-13`, with
a **populated** category-balance row for the composed key `00000000007 / 01 / 0001` carrying
`+100.00`.

Four things must hold at once, and each one is a distinct part of the posting contract:

- the record **passes validation**, so no reject is produced and the return code stays clean;
- the category balance takes the **update** arm rather than the create arm, because the key
  already exists;
- the account's **current balance and its current-cycle credit total both move**, by the same
  amount, while the cycle debit total does not;
- one **350-byte transaction record** is written, carrying the input's originating timestamp
  unchanged and a processing timestamp read from the clock.

Alternatives Considered: opening the category balance at `+0.00`, which is what the seed row
carries. Rejected because the update arm would then produce `0.00 + 504.77 = 504.77`, which is
**byte-identical** to what the create arm produces for the same transaction -- so a job that
took the wrong arm would still match the golden, and the arm under test would not be
observable at all. Opening at `+100.00` makes the addend visible: `604.77` can only be reached
by adding to an existing row. The complementary case is `posting/zero_balance`, which ships an
empty category-balance file so that the **create** arm is the only reachable one; between the
two folders the branch of master section 7.1.5 is exercised in both directions with each arm
distinguishable from the other.

---

## 2. The business rule, cited by program and line

The walk below was checked line by line against `app/cbl/CBTRN02C.cbl`.

| Step | Line | What happens for this record |
|---|---|---|
| Read the feed | `:204` | `1000-DALYTRAN-GET-NEXT` returns status `'00'`, so the loop body runs |
| Count it | `:206` | `ADD 1 TO WS-TRANSACTION-COUNT`, giving a processed count of 1 |
| Clear the verdict | `:208-209` | reason set to 0 and its description to spaces before validation |
| Validate | `:210` | `1500-VALIDATE-TRAN` (`:370-378`) |
| -- cross-reference | `:380-392` | `1500-A-LOOKUP-XREF` reads by `FD-XREF-CARD-NUM` (`:382-383`) and takes `NOT INVALID KEY` (`:388`), so no reason is set and `XREF-ACCT-ID` is now `00000000007` |
| -- account | `:393-422` | the reason is still 0, so `:372-373` performs `1500-B-LOOKUP-ACCT`; `:394-395` reads by the resolved account id and takes `NOT INVALID KEY` (`:400`) |
| -- projected balance | `:403-405` | `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT` |
| -- credit-limit test | `:407` | `IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` is **true**, so `:408` continues and 102 is not set |
| -- expiration test | `:414` | `IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)` is **true**, so `:415` continues and 103 is not set |
| Take the posting path | `:211-212` | the reason is still 0, so `2000-POST-TRANSACTION` (`:424-444`) runs |
| -- build the record | `:425-438` | fourteen field moves; `:436` copies `DALYTRAN-ORIG-TS`; `:437-438` reads the clock into `TRAN-PROC-TS` |
| -- category balance | `:440` | `2700-UPDATE-TCATBAL` (`:467-501`) keys on `XREF-ACCT-ID` (`:469`), the feed type (`:470`) and the feed category (`:471`) |
| -- which arm | `:474-479`, `:495-499` | the `READ` **hits**, so `WS-CREATE-TRANCAT-REC` stays `'N'` (its declared value at `:190`) and `:498` performs `2700-B-UPDATE-TCATBAL-REC` (`:526-542`) |
| -- add and rewrite | `:527-528` | `ADD DALYTRAN-AMT TO TRAN-CAT-BAL` then `REWRITE` |
| -- account | `:441` | `2800-UPDATE-ACCOUNT-REC` (`:545-560`): `:547` adds the amount to `ACCT-CURR-BAL`, and `:548-549` add it to `ACCT-CURR-CYC-CREDIT` because the amount is not negative |
| -- transaction | `:442` | `2900-WRITE-TRANSACTION-FILE` writes the one 350-byte record |
| Report and grade | `:227-230` | both counter lines are emitted; `IF WS-REJECT-COUNT > 0` is false, so the return code stays 0 |

Two details of this walk are worth naming because they are the ones a reader is most likely to
supply from intuition instead. The category-balance key's account id comes from the
**cross-reference read**, not from the daily transaction record -- `:469` moves `XREF-ACCT-ID`,
per master section 7.1.5 -- and the three writes at `:440`, `:441` and `:442` are **one unit of
work**, so no fixture may expect a state in which some of them landed and others did not.

---

## 3. Returns -- the expected outcome

The return code is **0**, the clean tier of master section 7.1.6. Every figure below is
arithmetic over this folder's bytes, and every one was confirmed against the reference-only
golden corpus in `tests/golden/posting/happy_path/`.

**Validation.** `WS-TEMP-BAL = 0.00 - 0.00 + 504.77 = 504.77`, and `2065.00 >= 504.77`, so the
credit-limit test at `:407` passes. `2024-12-13 >= 2022-06-10`, so the expiration test at
`:414` passes. Both cycle fields are `+0.00` in this fixture, which is the identity master
section 7.1.3 relies on: the projected balance equals the transaction amount, so the outcome
can be read off the amount alone.

**`ACCT-CURR-BAL` takes no part in that test, and the misreading is worth pre-empting.** The
account's `+193.00` current balance is the most balance-shaped number in the folder and it is
**not** an operand of `:403-405`, which composes the projection from `ACCT-CURR-CYC-CREDIT`,
`ACCT-CURR-CYC-DEBIT` and the transaction amount only. A reader who assumes otherwise computes
`193.00 + 504.77 = 697.77` against a `2065.00` limit, still concludes "passes", and is right by
luck rather than by rule -- so the error survives this scenario and surfaces on
`boundary_exact_limit`, where the two readings disagree about which side of the boundary the
record falls. `ACCT-CURR-BAL` is a **posting** field here: `:547` adds to it after validation has
already finished.

**The three mutations.**

| Output | Before | Arithmetic | After | Encoded |
|---|---|---|---|---|
| `TRAN-CAT-BAL` for `00000000007 / 01 / 0001` | `+100.00` | `100.00 + 504.77` | **`+604.77`** | `0000006047G` |
| `ACCT-CURR-BAL` for `00000000007` | `+193.00` | `193.00 + 504.77` | **`+697.77`** | `00000006977G` |
| `ACCT-CURR-CYC-CREDIT` | `+0.00` | `0.00 + 504.77` | **`+504.77`** | `00000005047G` |
| `ACCT-CURR-CYC-DEBIT` | `+0.00` | untouched -- `:551` runs only for a negative amount | **`+0.00`** | `00000000000{` |

Read the `Encoded` column against master sections 3.3 and 3.7 and it is self-checking: the
overpunch folds the sign onto the **last** digit, so `G` *is* a `7` carrying a plus, and the
column holds one digit fewer than the field's digit count rather than a full digit string with a
sign character appended to it. `ACCT-CURR-BAL` is `S9(10)V99`, twelve digits, so `+697.77`
encodes as eleven literal digits plus `G`; `TRAN-CAT-BAL` is `S9(09)V99`, eleven digits, so
`+604.77` encodes as ten plus `G`. Writing `00000069777G` instead would be thirteen digits'
worth of information in a twelve-digit field and would decode to `+6977.77`, a factor of ten
out, which is precisely the class of error a reader cannot see by eye.

Assumptions: the four encodings above are the bytes at the same offsets in
`tests/golden/posting/happy_path/`, so the arithmetic and the golden corpus are cross-checks on
each other rather than one being copied from the other. `ACCT-CURR-BAL` sits at `[12:24]` and
`ACCT-CURR-CYC-CREDIT` at `[78:90]` of `acctdat.expected`, and `TRAN-CAT-BAL` at `[17:28]` of
`tcatbal.expected`. Stating the encoded form as well as the decimal is what makes the check
possible at all: two documents can agree on "`+604.77`" while disagreeing on the bytes, and it
is the bytes a comparison fails on.

**The written transaction record**, one row of 350 bytes: identifier `0000000000683580`, type
`01`, category `0001`, source `POS TERM`, amount `+504.77`, card `4859452612877065`, all four
merchant fields copied from the feed, `TRAN-ORIG-TS` = `2022-06-10 19:27:53.000000` copied
unchanged by `:436`, `TRAN-PROC-TS` from the clock and therefore masked, and the trailing
`FILLER` at offset 330 carrying **20 NUL bytes** rather than blanks -- the output-TRAN row of
master section 6.1, because no `MOVE` in the program ever touches that field. `TRAN-DESC` is
**space**-padded here, which master section 6.3 fixes as the posting job's padding: `:429` is a
plain `MOVE` of an already space-padded input field.

**The reject stream is empty but the dataset still exists.** `app/jcl/POSTTRAN.jcl:34-38`
allocates `DALYREJS(+1)` unconditionally with `LRECL=430`, and the program opens it OUTPUT at
`:293` before it can know whether anything will be rejected, so a clean run produces the
dataset with zero records in it. The golden corpus records that as a **zero-byte**
`dalyrejs.expected` rather than as a missing file, and an expectation phrased as "no reject
dataset exists" would fail against correct behaviour.

**Counters.** The processed count is **1** and the rejected count is **0**; both lines are
emitted at `:227-228` regardless.

Assumptions: the golden corpus is cited as corroboration and never as the derivation. Its
`acctdat.expected` is 301 bytes, its `tcatbal.expected` 51, its `tranfile.expected` 351, its
`dalyrejs.expected` 0 and its `return_code.expected` the single byte `0` -- all consistent with
the arithmetic above, which is derived from the bytes in this folder and the lines cited in
section 2. Reading the expectation out of the golden instead would make this document a
transcription of an output rather than a statement of a rule, and it would be silent about
which line produced which byte.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**No reject reason is reachable.** All four of master section 7.1.1 are excluded by this
fixture's own bytes: the card is in the cross-reference, so 100 cannot be set at `:385`; the
account exists, so 101 cannot be set at `:397`; the projected balance is well inside the limit,
so 102 cannot be set at `:410`; and the originating date is two and a half years before
expiration, so 103 cannot be set at `:417`. Master section 7.1.4's overwrite of 102 by 103 has
nothing to overwrite here.

**The create arm must not be taken.** `2700-A-CREATE-TCATBAL-REC` (`:503-524`) runs only when
`WS-CREATE-TRANCAT-REC` is `'Y'`, which only the `INVALID KEY` branch at `:475-478` sets. The
category-balance row for this scenario's exact composed key is present, so the read at `:474`
returns `'00'` and the flag keeps its declared `'N'`. A run that created a row here would have
produced a **second** row for a key that already had one.

**Code 109 is not reachable and must not be expected.** `:556` moves 109 into the reason field
inside the `INVALID KEY` branch of the account `REWRITE` at `:554-559`. That branch is on the
posting path, after validation has already passed, and no reject record is written from it --
master section 7.1.1 fixes the persisted domain as exactly `{100, 101, 102, 103}`. This
scenario's account row is the one just read at `:395`, so the rewrite cannot miss it.

**No abend occurs.** Every `9999-ABEND-PROGRAM` site on this control path is guarded by an I/O
status: the six opens (`:250`, `:268`, `:287`, `:305`, `:323`, `:341`), the feed read
(`:363-366`), the category-balance read (`:492`) and rewrite (`:541`), the transaction write
(`:577`) and the six closes (`:596`, `:614`, `:633`, `:651`, `:669`, `:688`). A run in which
every status is `'00'` -- which is what a well-formed fixture of the right widths produces --
reaches none of them. Note that `:481` accepts `'23'` as well as `'00'` from the
category-balance read, which is how the create arm coexists with the abend guard; this scenario
returns `'00'`.

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

The `ASSIGN` names and organizations are the ones declared at `app/cbl/CBTRN02C.cbl:29-61`; the
record lengths are the summed widths of master section 5.1, never a `RECLN` banner. The posting
job reads the cross-reference **by card number** at offset 0 -- master section 4.3, because
`app/jcl/POSTTRAN.jcl:32-33` mounts the base cluster only and never the account-keyed alternate
path -- so this row's account id is payload rather than a second key.

### 5.2 On-disk sizes and line endings

| File | Size | Records | Bytes per record | `CR` bytes | Trailing newline |
|---|---:|---:|---:|---:|---|
| `dailytran.txt` | **351** | 1 | 350 | 0 | exactly one `LF` |
| `cardxref.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |
| `acctdata.txt` | **301** | 1 | 300 | 0 | exactly one `LF` |
| `tcatbal.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |

Each file is one record plus a single trailing `LF`, per master sections 3.8 and 3.9, and the
whole folder contains **zero `CR` bytes**.

```bash
# WHAT: assert this scenario's whole byte geometry at once -- one record of the declared width
#       per file, a single trailing LF, and no CR anywhere. Run from this directory.
# WHY : Assumptions: the loaders reject a wrong-length row outright rather than padding or
#       truncating it (master section 1.2), so this turns a load-time failure into an
#       authoring-time one. Printing the CR count beside the size is what separates the two
#       most common mistakes -- a stray CR absorbed into the last field, which reports only as
#       "352", and a second blank line that parses as a zero-length record.
python3 - <<'PY'
EXPECTED = {"dailytran.txt": (351, 350), "cardxref.txt": (51, 50),
            "acctdata.txt": (301, 300), "tcatbal.txt": (51, 50)}
for name, (size, width) in EXPECTED.items():
    raw = open(name, "rb").read()
    rows = [r for r in raw.split(b"\n") if r]
    widths = sorted({len(r) for r in rows})
    carriage_returns = raw.count(b"\r")
    print(name, "size", len(raw), "expected", size, "| rows", len(rows), widths,
          "| CR", carriage_returns,
          "| OK" if len(raw) == size and carriage_returns == 0 and widths == [width]
          else "| MISMATCH")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based. Money is a signed zoned field whose sign is folded into its last byte
and whose decimal point occupies no byte, per master sections 3.3 and 3.7; the decoded column
is what those bytes mean, not a second encoding.

`dailytran.txt`, one `DALYTRAN-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DALYTRAN-ID` | 0 | 16 | `0000000000683580` | -- |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `01` | -- |
| `DALYTRAN-CAT-CD` | 18 | 4 | `0001` | unsigned, plain digits |
| `DALYTRAN-SOURCE` | 22 | 10 | `POS TERM` + 2 spaces | -- |
| `DALYTRAN-DESC` | 32 | 100 | `Purchase at Abshire-Lowe` + 76 spaces | -- |
| `DALYTRAN-AMT` | 132 | 11 | `0000005047G` | **`+504.77`** |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | -- |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | -- |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | -- |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | -- |
| `DALYTRAN-CARD-NUM` | 262 | 16 | `4859452612877065` | -- |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `2022-06-10 19:27:53.000000` | ISO-shaped, from the seed |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

`cardxref.txt`, one `CARD-XREF-RECORD`:

| Field | Offset | Width | Bytes |
|---|---:|---:|---|
| `XREF-CARD-NUM` | 0 | 16 | `4859452612877065` |
| `XREF-CUST-ID` | 16 | 9 | `000000007` |
| `XREF-ACCT-ID` | 25 | 11 | `00000000007` |
| `FILLER` | 36 | 14 | 14 spaces |

`acctdata.txt`, one `ACCOUNT-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | `00000000007` | account 7 |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | active |
| `ACCT-CURR-BAL` | 12 | 12 | `00000001930{` | `+193.00` |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000020650{` | `+2065.00` |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000002640{` | `+264.00` |
| `ACCT-OPEN-DATE` | 48 | 10 | `2012-10-12` | -- |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `2024-12-13` | the date `:414` compares |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2024-12-13` | -- |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` | `+0.00` |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` | `+0.00` |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | -- |
| `ACCT-GROUP-ID` | 112 | 10 | 10 spaces | blank -- unread by this job |
| `FILLER` | 122 | 178 | 178 spaces | -- |

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` line 11 spells it. The
misspelling is **preserved verbatim** here because this is copybook-side naming; master section
9.3 records that the three spelling corrections apply to target column names only.

Do not read `ACCT-GROUP-ID`'s blank as a defect. This job never touches the field -- it is the
disclosure-group key the **interest** program composes, and master section 7.2.3 records that
leaving it blank is what selects the `DEFAULT` fallback there. In this folder it is inert.

`tcatbal.txt`, one `TRAN-CAT-BAL-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `TRANCAT-ACCT-ID` | 0 | 11 | `00000000007` | account 7 |
| `TRANCAT-TYPE-CD` | 11 | 2 | `01` | matches the feed type |
| `TRANCAT-CD` | 13 | 4 | `0001` | matches the feed category |
| `TRAN-CAT-BAL` | 17 | 11 | `0000001000{` | **`+100.00`** -- reshaped, section 9 |
| `FILLER` | 28 | 22 | 22 ASCII `'0'` | -- |

**The `FILLER` bytes differ between these files by measurement, not by inconsistency.**
`dailytran.txt`, `cardxref.txt` and `acctdata.txt` pad with `0x20` SPACE while `tcatbal.txt`
pads with ASCII `'0'`, `0x30`. Both are the bytes master section 6.1 measures, and master
section 6.2 records that the category-balance row is one of the two contradicting the general
rule of section 3.2, with the measured byte winning. Applying the general rule to `tcatbal.txt`
would produce a row differing from its seed **and** from the golden in 22 bytes that carry no
data at all.

**Which arm wrote the category row is what decides its pad, and this scenario's arm is the
update one.** Master section 6.2.1 requires a scenario document to name that arm rather than
name the record, because TCATBAL is one record type with two padding bytes: a row the job
**rewrites** starts from the image the read returned and carries whatever that image held, so
this folder's `0x30` survives from the seed into the expectation untouched, while a row the job
**creates** is built in a record area no `MOVE` reaches past the fields it sets and keeps that
area's low values. Measured across the nine committed posting trees, eight expectations carry
byte-set `{48}` and only `zero_balance` -- the one scenario whose `tcatbal.txt` is a zero-byte
file, so the only one reaching `2700-A-CREATE-TCATBAL-REC` -- carries `{0}`. The consequence is
directional and worth stating in full: an author who reasons "TCATBAL pads with ASCII zero"
onto a **created** expectation is wrong by 22 bytes with the key and the balance both correct,
which is the same failure master section 6.2 describes arriving from the opposite side.

Assumptions: this folder's `0x30` pad is inherited, not chosen. It is the byte the seed row
already carried, and the update arm's `REWRITE` at `:528` preserves it because it writes back a
record area that the read at `:474` populated; the pad is never assigned by any `MOVE` in the
program. Deriving the pad from the `PICTURE` clause instead -- blanks, on the reasoning that
`X(22)` is text -- is the documented hazard, and it is worth naming that the shared codec does
exactly that on encode by design, because a codec has no record-specific padding knowledge and
must pick one filler. That makes the codec right and the fixture-authoring rule different, which
is why master section 6.1 is the authority here and overrides the general rule of section 3.2.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50 and the seed simply omits the trailing `FILLER X(14)`. Authored at the full copybook width with that `FILLER` space-padded, which is the ruling and the house shape |
| `tcatbal.txt` is `LF`-terminated where its seed is `CRLF` | master sections 3.8, 3.9 | A stray `0x0D` absorbed into the 22-byte `'0'` `FILLER` would push the record to 51 bytes and fail the load with every field value correct |
| `tcatbal.txt` carries `+100.00` where its seed row carries `+0.00` | master section 11.1 | A business-rule field reshaped deliberately; see section 1 for why, and section 9 for the attestation |

Assumptions: the first two departures are conformance to a declared width and a declared line
ending, and neither touches a value. The card-xref seed row stops after `XREF-ACCT-ID` at 36
bytes because the seed omits the trailing `FILLER X(14)` outright, so authoring at the copybook's
full 50 is what makes the row loadable at all rather than a decoration -- and the 14 bytes added
are blanks in a field no program reads, so no identity byte moves. The category-balance seed
ships `CRLF`, and a surviving `0x0D` would be absorbed into the 22-byte pad and push the physical
row to 51 bytes while every field still decoded correctly, which is the failure that looks like a
loader bug and is not one. Both are therefore recorded here as departures from a seed rather than
in section 9 as reshaped values, and section 9 says so explicitly so the two statements cannot be
read as contradicting each other.

Trade-offs: each file here holds exactly **one** record, which buys unambiguous attribution --
every byte of every expectation traces to the single input row that produced it, so a failure
names a field rather than a row -- and gives up any coverage of multi-record iteration, ordering
across keys, or the pre-sorted-input requirement of master section 3.12. That cost is accepted
because it is already paid elsewhere: the full 300-record seed cycle in the reference-only
`tests/e2e/test_posting_cycle.py` exercises iteration at scale, and section 8.2 records why its
constants are not this scenario's expectations. A fixture that tried to do both would localise
neither.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input
data.** Master section 8.1 requires every scenario README to say which of the two meanings a
blank timestamp carries, every time it shows one, because the byte pattern is identical in both
cases and the meaning cannot be recovered from the bytes. Here the record **has not been
processed yet** -- that is the whole point of a daily-transaction feed -- which is also why the
migrated `ledger.daily_transactions.proc_ts` column is nullable while
`ledger.transactions.proc_ts` is `NOT NULL`. It is **not** the product of normalisation.

The other 26-byte field is the opposite case. `DALYTRAN-ORIG-TS` = `2022-06-10 19:27:53.000000`
is real, deterministic input data, `:436` copies it into the output record unchanged, and
master section 8.1 therefore says to **assert** it rather than mask it. The processing timestamp
of the **output** record is a clock reading and is masked; when a golden shows that field as 26
spaces, those spaces are the product of normalisation, which is the other of the two meanings.

Everything else about determinism holds by construction: every byte here is literal, there is
no random identifier and no environment-derived string, and each test provisions a fresh
workspace and tears it down, per master section 8.

Assumptions: the input `DALYTRAN-PROC-TS` is left as 26 blanks because it is the **one** field on
this path the program overwrites from the wall clock -- `:437-438` performs
`Z-GET-DB2-FORMAT-TIMESTAMP`, which reads `FUNCTION CURRENT-DATE` at `:693` -- so any literal
value written here would be discarded on the way to the output record while making this file
falsely appear to describe an already-processed transaction. The blanks also decode to SQL `NULL`
against the nullable `ledger.daily_transactions.proc_ts` column, which is the migrated shape of
the same fact, and the harness's own comment records that the nullability asymmetry between the
daily feed and the posted ledger is deliberate rather than incidental. Choosing a fixed literal
instead would have made the field self-documenting at the cost of making it a lie.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and three of its collaborators constrain what
this folder may expect:

- **`service/PostingValidationService.validate`** returns a `PostingDecision` carrying
  `dto/PostingValidationResult.accepted(504.77)` -- the projected cycle balance is part of the
  accepted outcome, held at `PROJECTED_BALANCE_SCALE` decimal places, so the number `:403-405`
  computes survives into the target rather than being recomputed later.
- **`service/CategoryBalanceService`** reports its arm explicitly, returning an `Outcome` whose
  `Arm` is `UPDATED` for this scenario and whose balance is `604.77`. It reaches that arm the
  way the COBOL does -- a keyed read, then a write down one of two branches -- rather than by
  collapsing the two into a single database-side upsert, and both branches accumulate additively
  exactly as `:508` and `:527` do. That shape is deliberate and the service's own header records
  the reason: an upsert resolves the branch inside the database, so the caller cannot observe
  which arm ran, and `tests/README.md` section 13 requires the branch exercised both ways with
  the arms distinguishable. This folder is the update half of that requirement, so a design that
  hid the arm would make the folder unable to prove the thing it exists to prove.
- **`dto/BatchRunSummary`** enforces the warn-tier biconditional in both directions: the
  soft-warn tier holds **exactly when** the rejected count exceeds zero. A summary claiming
  warn with nothing rejected is refused, and so is one claiming clean with records rejected.
  This scenario must therefore report tier 0 with one processed, zero rejected, and no negative
  counter.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql)
-- `ledger.daily_transactions`, `account.card_xref`, `account.accounts` and
`ledger.transaction_category_balances` as inputs, `ledger.transactions` as the one output that
gains a row and `ledger.transaction_rejects` as the one that must stay empty. A scenario owns
only its own rows and never seeds another service's schema, per master section 11.3.

**One distinction to keep straight.** The graded return-code rubric -- 0, 4, 8 -- belongs to
the COBOL parity suite alone, per master section 7.1.6. The tier-0 expectation above is the
**job's** return code, asserted like any other fixture value; it is not a build status, and a
Java build reporting anything other than success is a failure.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs. The
expected-output oracle is the reference-only `tests/golden/posting/happy_path/` tree, which is
read as the authority and never written, and no golden is ever regenerated. These fixtures are
an **additive mirror** of the house tree's shape, never a move of it.

### 8.2 Oracle constants that are not expectations for this scenario

Master section 7.1.7 lists the constants `tests/e2e/test_posting_cycle.py` fixes for the **full
300-record seed cycle** -- 300 daily records, 262 posted, 38 rejected all reason `0102`, a
conservation total of `77954.70`, and 50 category keys becoming 100. **None of them is an
expectation for this scenario.** This is a sample of one record, so the counts here are one
processed and zero rejected. Nothing in this folder contradicts those constants, because the
same program and the same layouts produce both; they simply describe a different input.

The two loader-geometry constants **do** agree, and the agreement is a cross-check rather than
a coincidence: `_ACCT = (300, 11)` matches this folder's 300-byte account record and 11-byte
key, and `_TCAT = (50, 17)` matches its 50-byte category record and 17-byte composite key.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived.** Every record comes from the
published AWS CardDemo sample seed datasets under `app/data/ASCII/`, and each row can be
pointed back to a specific seed row:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | **byte-identical** |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 7 | **byte-identical** |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | 21 | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |
| `tcatbal.txt` | `app/data/ASCII/tcatbal.txt` | 7 | one business-rule field reshaped, below |

The cross-reference row is row **21** of its seed rather than row 7 because that file is
ordered by card number, and account `00000000007` appears there under card
`4859452612877065`.

**It represents no real person and no real account.** Those seeds ship with the upstream
open-source project as fabricated demonstration data, and master section 11.1 carries the
tree-level attestation this scenario inherits. Identity and primary-account-number bytes are
taken unchanged from the seed, and the whole folder turns on one identity triple: card
`4859452612877065`, the customer `000000007` it belongs to, and the account `00000000007` the
cross-reference resolves it to.

The attestation is written out rather than assumed, and the reason is specific to
financial data. A sixteen-digit primary account number that satisfies a Luhn check is
**indistinguishable by inspection** from a live one: nothing about the digits themselves tells a
reviewer whether the number was fabricated for a sample dataset or captured from a real card, and
no amount of care reading the bytes can settle it. The only thing that can settle it is a stated,
checkable provenance -- this file, this seed, this row -- which is why every scenario in this tree
carries the statement even where the number is obviously synthetic, and why the per-file seed-row
table above cites rows rather than merely asserting that the data is fake. Leaving it implicit
would shift the burden onto each future reader to re-derive what the author already knew.

**Exactly one business-rule field is reshaped away from its seed value.** In `tcatbal.txt`,
`TRAN-CAT-BAL` at `[17:28]` is `0000001000{` = `+100.00`, where seed row 7 carries
`0000000000{` = `+0.00`. Measured, that is a **one-byte** difference at offset 23, `'0'`
becoming `'1'`. No other field in any file here departs from its seed value: the account's
balance, both credit limits, all three dates, both cycle amounts, the ZIP and the blank group
id are the seed's own, and so is every field of the daily transaction. The two normalizations
named in section 5.4 -- the card-xref width and the category-balance line ending -- are width
and line-ending conformance, **not** business-rule field changes, so the two statements do not
conflict.

Trade-offs: reshaping the opening category balance costs this folder byte-identity with its
seed row and buys the only thing that makes the update arm observable, as section 1 explains.
The compromise is accepted because a fixture that cannot distinguish the arm it claims to
exercise is worth less than a fixture that differs from its seed in one documented byte. The
alternative of adding a second transaction to the feed to force an update on the second pass
was rejected: it would change the processed count, the conservation arithmetic and the written
record count all at once, so a failure anywhere would no longer localise to the arm.

Master section 11.3 governs the rest and is not restated here: no secret, credential,
connection string or endpoint appears in any file in this folder, money never leaves fixed
point, and nothing here modifies the COBOL baseline or the parity oracle.

---

*This README is the mandatory Explainability carrier for the four record files in this
directory, required by master section 10 and by user-specified Rule 1. Exactly one gate reads
it: `config/rule1/rule1_gate.py` decides the form of the rationale labels above,
repository-wide and including Markdown, which is why they are written plain rather than
emphasised. `config/checkstyle/checkstyle.xml` limits its audit set to `java`, so no linter
reads a byte of this prose. Whether each rationale names a real consequence, and whether every
number and line citation here is true, are review obligations no lexical gate can decide.*

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**. `PostTransactionsJobParityIT` resolves each scenario under
`/fixtures/posting/`, seeds the masters from it, launches the posting job and compares the resulting
transaction master, category balances, account master and reject stream against
`tests/golden/posting/happy_path` -- so an edit to these bytes changes what the parity run asserts.
`BatchFixtureContractTest` additionally holds every file here to its declared geometry and to the
values that make the scenario discriminating, so a layout mistake is caught in this module rather
than surfacing later as a comparison failure.

Assumptions: this section exists because the two halves of this tree behave differently and a
reader cannot tell which half a directory belongs to by looking at it. `PostTransactionsJobParityIT`
names `/fixtures/posting/` as its seed root and reads all four files here -- `acctdata.txt`,
`cardxref.txt` and `tcatbal.txt` to seed the masters, `dailytran.txt` to seed the feed -- so an edit
to these bytes changes what a parity run asserts. The sibling `preflight/**` and `interest/**`
families are mirrors: `CalculateInterestJobTest` resolves its inputs under the repository-root
`tests/fixtures/interest/` tree instead, so editing those directories changes nothing a job reads.
Stating which case applies is the point of the section, because the two look identical on disk and
the expensive mistake is editing a driven byte while believing it is inert.
