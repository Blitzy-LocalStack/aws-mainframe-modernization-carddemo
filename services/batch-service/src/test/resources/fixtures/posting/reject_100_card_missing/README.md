# `posting/reject_100_card_missing` -- the card is not in the cross-reference

> **Purpose.** Pin reject reason **100**: a daily transaction whose card number is absent from the
> card cross-reference is refused with the message `INVALID CARD NUMBER FOUND`, the run reports the
> soft-warn tier, and -- the part most easily missed -- the account lookup **never happens at all**,
> because reason 100 short-circuits validation.
>
> **Source of truth.** `app/cbl/CBTRN02C.cbl` for the behaviour and `app/jcl/POSTTRAN.jcl` for the
> dataset contract, both reference-only and both cited below by line; `app/cpy/CVTRA06Y.cpy`,
> `app/cpy/CVACT03Y.cpy`, `app/cpy/CVACT01Y.cpy` and `app/cpy/CVTRA01Y.cpy` for the record layouts;
> the seed datasets under `app/data/ASCII/` for the bytes;
> `tests/golden/posting/reject_100_card_missing/` for the expected outputs; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document cites by section
> rather than restating (master section 1.3).
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. This whole file is pure ASCII for
> the reason that section gives.

**This README is the mandatory Explainability carrier for the four record files beside it.**
Master section 1.2 records why: a fixed-width record file cannot carry a comment of any kind,
because every byte position is meaningful and a comment on its own line is a physical row of the
wrong length. Master section 10 makes the artifact mandatory rather than courteous, and the section
order below is the one it fixes.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction on card **`4859452612877065`**, against a cross-reference holding a single
row for a **different** card, `0927987108636232`. The lookup misses, and the rest of the folder is
arranged so that the miss is the only thing that can produce a reject.

Two properties make this the first of the four reject scenarios rather than one of a set of four
equivalent ones:

- it is the **only** reject reason set outside the account lookup, so it is the only one that can
  prevent later validation from running at all;
- the resulting reject record carries the whole 350-byte input image, so the rejected card number
  is recoverable from the reject stream at offsets 262 to 277 -- which is how the parity oracle
  reads it.

Alternatives Considered: producing the miss by shipping an **empty** `cardxref.txt` rather than a
populated one naming a different card. Rejected because an empty cross-reference cannot distinguish
"the card was looked up and not found" from "the dataset was never loaded", and the second is a
harness failure that would present as a passing test. A populated file with one non-matching row
proves the dataset loaded, the read executed, and the key genuinely did not match. `posting/
zero_balance` uses the empty-file construction deliberately for the category-balance file, where
the ambiguity does not arise because the create arm's `DISPLAY` names the key it failed to find.

---

## 2. The business rule, cited by program and line

**`app/cbl/CBTRN02C.cbl:380-392`, paragraph `1500-A-LOOKUP-XREF`.**

```cobol
 1500-A-LOOKUP-XREF.
     MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM
     READ XREF-FILE INTO CARD-XREF-RECORD
        INVALID KEY
          MOVE 100 TO WS-VALIDATION-FAIL-REASON
          MOVE 'INVALID CARD NUMBER FOUND'
             TO WS-VALIDATION-FAIL-REASON-DESC
```

That is `:380-387`, reproduced faithfully. The reason is assigned at **`:385`** and the message
literal is at **`:386`** -- master section 7.1.1 gives both columns because a citation may
reasonably point at either, and a reader following one number should not land in the wrong
construct.

**The short-circuit, `:370-378`.** `1500-VALIDATE-TRAN` performs the cross-reference lookup, then
performs the account lookup **only if** the reason is still zero:

```cobol
 1500-VALIDATE-TRAN.
     PERFORM 1500-A-LOOKUP-XREF.
     IF WS-VALIDATION-FAIL-REASON = 0
         PERFORM 1500-B-LOOKUP-ACCT
     ELSE
         CONTINUE
     END-IF
```

Reason 100 is therefore **terminal for this record**: `1500-B-LOOKUP-ACCT` (`:393-422`) is never
entered, so `:395` never reads the account master, `:403-405` never computes a projected balance,
and neither boundary test at `:407` or `:414` is evaluated. Master section 7.1.3 fixes this and
draws the consequence for a fixture: a `reject_100_card_missing` scenario may legitimately omit the
account, and its README should say the omission is **untested rather than satisfied**. Section 5.3
below says exactly that about the account row this folder does ship.

The rest of the walk, all in `app/cbl/CBTRN02C.cbl`:

| Step | Line | What happens for this record |
|---|---|---|
| Read the feed | `:204` | status `'00'`, so the loop body runs |
| Count it | `:206` | processed count becomes 1 |
| Clear the verdict | `:208-209` | reason 0, description spaces |
| Validate | `:210`, `:370-378` | the cross-reference lookup misses; the account lookup is skipped |
| Take the reject path | `:211`, `:213-215` | the reason is not 0, so `ADD 1 TO WS-REJECT-COUNT` and `2500-WRITE-REJECT-REC` |
| Build the reject | `:447-448` | `:447` moves the entire 350-byte `DALYTRAN-RECORD` verbatim; `:448` appends the 80-byte trailer |
| Write it | `:451` | one 430-byte record; `:452` requires status `'00'` |
| Grade the run | `:229-230` | the rejected count exceeds zero, so `MOVE 4 TO RETURN-CODE` |

**Nothing is posted.** `2000-POST-TRANSACTION` (`:424-444`) is reached only from `:212`, on the
branch this record does not take, so the category balance, the account and the transaction file are
all untouched.

---

## 3. Returns -- the expected outcome

The return code is **4**, the soft-warn tier -- `:229-230` moves 4 because the rejected count is
one. Master section 7.1.6 fixes that tier as a **fixture expectation value** belonging to the COBOL
parity suite's graded rubric, never to a Java build gate.

**The reject record**, one row of 430 bytes:

| Slice | Width | Contents |
|---|---:|---|
| `[0:350]` | 350 | the input `DALYTRAN-RECORD` **verbatim**, including card `4859452612877065` at `[262:278]` |
| `[350:354]` | 4 | **`0100`** |
| `[354:430]` | 76 | **`INVALID CARD NUMBER FOUND`** followed by 51 spaces |

**The code is `0100` on the wire and the integer `100` in storage.** `WS-VALIDATION-FAIL-REASON` is
`PIC 9(04)` at `app/cbl/CBTRN02C.cbl:181`, so the trailer carries four characters zero-padded --
not `100`. The migrated `ledger.transaction_rejects.reason_code` column is `SMALLINT` and holds the
integer. Master section 7.1.2 fixes both representations and warns that an expectation must name
which one it asserts: a comparison of the column against the string `0100` fails, and a byte
comparison against `100` fails, and both failures look like a wrong reason code rather than a wrong
representation.

**The message is space-padded to 76 characters**, because `:386` moves an alphanumeric literal into
an alphanumeric receiver and `MOVE` pads with spaces -- master section 6.3 records the same
mechanism for the trailer description. `INVALID CARD NUMBER FOUND` is 25 characters, so 51 spaces
follow.

**Nothing else changes.**

| Output | Expected | Authority |
|---|---|---|
| Posted transaction records | **0** | `2900-WRITE-TRANSACTION-FILE` at `:562` is reached only from `2000-POST-TRANSACTION` (`:442`) |
| `tcatbal.txt` after the run | **unchanged, byte for byte** | `2700-UPDATE-TCATBAL` at `:467` is reached only from `:440` |
| `acctdata.txt` after the run | **unchanged, byte for byte** | `2800-UPDATE-ACCOUNT-REC` at `:545` is reached only from `:441` |
| `cardxref.txt` after the run | **unchanged, byte for byte** | opened INPUT at `:275`; the job has no statement that writes it |
| Counters | 1 processed, 1 rejected | `:227-228` |

Assumptions: the golden corpus is corroboration, never the derivation. In
`tests/golden/posting/reject_100_card_missing/`, `return_code.expected` is the single byte `4`,
`dalyrejs.expected` is **431 bytes** -- 430 plus the single trailing `LF` of master section 3.9 --
`tranfile.expected` is **0 bytes**, and `acctdat.expected` and `tcatbal.expected` are
byte-identical to this folder's `acctdata.txt` and `tcatbal.txt` at 301 and 51 bytes. The
unchanged-input assertion is available **only** because those two files have content; against empty
files it would assert nothing.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**101 must not be reported, and this is the scenario's sharpest negative.** Both reasons name a
lookup that failed, and an implementation that validated the account **before** or **regardless of**
the cross-reference would report 101 here -- the resolved account id would be missing or blank, the
account read would miss, and the reject stream would carry `0101` with `ACCOUNT RECORD NOT FOUND`
instead of `0100` with `INVALID CARD NUMBER FOUND`. The return code would still be 4 and the reject
count would still be one, so **only the trailer bytes distinguish the two behaviours.** The
short-circuit at `:372-373` is what makes 100 the answer.

**102 and 103 must not be reported either, for the same reason.** Both are assigned inside
`1500-B-LOOKUP-ACCT`, at `:410` and `:417`, in the `NOT INVALID KEY` branch that this record never
reaches. Master section 7.1.4's overwrite of 102 by 103 is unreachable here.

**The account lookup must not be observed at all.** This is a **negative** assertion about
control flow rather than about data, and it is the one this folder is uniquely able to make.

**Code 109 must not be expected.** `:556` assigns it inside the `INVALID KEY` branch of the account
`REWRITE` at `:554-559`, which is on the posting path -- not taken here -- and writes no reject
record. Master section 7.1.1 fixes the persisted domain as exactly `{100, 101, 102, 103}` and
records 109 as a dead write.

**Nothing may be posted.** A run that wrote a transaction record here would have posted a
transaction for a card that resolves to no account, so its category-balance key would have had no
account id to compose from -- `:469` moves `XREF-ACCT-ID`, which the failed read never populated.

**No abend occurs.** The reachable `9999-ABEND-PROGRAM` sites on this path are all status-guarded:
the six opens (`:250`, `:268`, `:287`, `:305`, `:323`, `:341`), the feed read (`:363-366`), the
**reject write** (`:463`) and the six closes (`:596`, `:614`, `:633`, `:651`, `:669`, `:688`).
Note which one is new relative to a posting scenario: `:463` is reached only when the reject write
returns a status other than `'00'`, and it is on this path precisely because this path writes a
reject. A failed `READ` with `INVALID KEY` is **not** an error -- it is the branch the rule lives
in.

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

`ASSIGN` names and organizations are declared at `app/cbl/CBTRN02C.cbl:29-61`; the record lengths
are the summed widths of master section 5.1, never a `RECLN` banner. The cross-reference is read
**by card number** at offset 0 -- master section 4.3, since `app/jcl/POSTTRAN.jcl:32-33` mounts the
base cluster only -- which is the access path this scenario makes fail.

The reject dataset is an **output** and has no fixture file here. Its 430-byte geometry is proved
six ways in master section 5.4, and `app/jcl/POSTTRAN.jcl:34-38` allocates it as `DALYREJS(+1)`
with `DISP=(NEW,CATLG,DELETE)` and `LRECL=430`.

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
# WHAT: assert the byte geometry, and additionally assert the one relation this scenario turns on
#       -- that the feed's card number and the cross-reference's key are DIFFERENT. Run from this
#       directory.
# WHY : Assumptions: a not-found fixture has an inverted failure mode compared with a matching
#       one. If the two card numbers ever agree the geometry stays perfect and the scenario
#       silently becomes a posting scenario -- return code 0, one transaction record, no reject --
#       which reads as a defect in the program rather than in the fixture. Asserting the
#       inequality at authoring time is what keeps that diagnosis pointed at the right file.
python3 - <<'PY'
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
feed_card = open("dailytran.txt", "rb").read()[262:278].decode("ascii")
xref_key = open("cardxref.txt", "rb").read()[0:16].decode("ascii")
print("feed card", feed_card, "cross-reference key", xref_key,
      "| OK the lookup misses" if feed_card != xref_key
      else "| MISMATCH the lookup would hit")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based; money is signed zoned with the sign folded into the last byte and no byte
for the decimal point, per master sections 3.3 and 3.7.

`dailytran.txt`, one `DALYTRAN-RECORD` -- this whole record is what the reject image reproduces
verbatim at `:447`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DALYTRAN-ID` | 0 | 16 | `0000000000683580` | -- |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `01` | -- |
| `DALYTRAN-CAT-CD` | 18 | 4 | `0001` | unsigned, plain digits |
| `DALYTRAN-SOURCE` | 22 | 10 | `POS TERM` + 2 spaces | -- |
| `DALYTRAN-DESC` | 32 | 100 | `Purchase at Abshire-Lowe` + 76 spaces | -- |
| `DALYTRAN-AMT` | 132 | 11 | `0000005047G` | `+504.77` -- never tested against a limit here |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | -- |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | -- |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | -- |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | -- |
| `DALYTRAN-CARD-NUM` | 262 | 16 | **`4859452612877065`** | the key `:382` moves and `:383` fails to find |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `2022-06-10 19:27:53.000000` | never compared against an expiration date here |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

`cardxref.txt`, one `CARD-XREF-RECORD` -- the row that does **not** match:

| Field | Offset | Width | Bytes |
|---|---:|---:|---|
| `XREF-CARD-NUM` | 0 | 16 | **`0927987108636232`** |
| `XREF-CUST-ID` | 16 | 9 | `000000020` |
| `XREF-ACCT-ID` | 25 | 11 | `00000000020` |
| `FILLER` | 36 | 14 | 14 spaces |

`acctdata.txt`, one `ACCOUNT-RECORD` for account `00000000007`: status `Y`, `ACCT-CURR-BAL`
`00000001930{` = `+193.00`, `ACCT-CREDIT-LIMIT` `00000020650{` = `+2065.00`,
`ACCT-CASH-CREDIT-LIMIT` `00000002640{` = `+264.00`, open `2012-10-12`, `ACCT-EXPIRAION-DATE`
`2024-12-13`, reissue `2024-12-13`, both cycle fields `+0.00`, ZIP `A000000000`, `ACCT-GROUP-ID`
ten spaces, `FILLER` 178 spaces.

**This account row is present and is never read, so this folder tests nothing about it.** Master
section 7.1.3 requires the point to be made this way round: the omission of the account lookup is
**untested rather than satisfied**. The row exists for two reasons, neither of which is an
expectation. It lets section 3 assert that the account master is **unchanged byte for byte** after
the run, which a job that reached the posting path would violate; and it keeps the folder's shape
uniform with the other eight posting scenarios, so a reader comparing them is not left wondering
whether a file went missing. Note in particular that the row is account `00000000007`, while the
cross-reference's non-matching row names account `00000000020` -- so even if the short-circuit were
broken and the account lookup ran, it would look for `00000000020` and miss, which is the shape
`posting/reject_101_acct_missing` pins deliberately.

`tcatbal.txt`, one `TRAN-CAT-BAL-RECORD`: `TRANCAT-ACCT-ID` `00000000007` at 0, `TRANCAT-TYPE-CD`
`01` at 11, `TRANCAT-CD` `0001` at 13, `TRAN-CAT-BAL` `0000000000{` = `+0.00` at 17, and 22 ASCII
`'0'` of `FILLER` at 28. It is likewise present, unread and asserted unchanged.

**The `FILLER` bytes differ by measurement, not by inconsistency.** Three files pad with `0x20`
SPACE; `tcatbal.txt` pads with ASCII `'0'`, `0x30`. Both are the bytes master section 6.1 measures,
and master section 6.2 records the category-balance row as one of the two contradicting the general
rule of section 3.2, with the measured byte winning.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| `tcatbal.txt` is `LF`-terminated where its seed is `CRLF` | master sections 3.8, 3.9 | A stray `0x0D` absorbed into the 22-byte `'0'` `FILLER` would push the record to 51 bytes and fail the load with every field value correct |
| The cross-reference deliberately omits the feed record's card | master section 8 | Master section 8 permits a key to be deliberately omitted to trigger a reject, and requires the scenario's README to name it. This is that naming: card `4859452612877065` is absent by design |

**No business-rule field is reshaped in this folder.** The scenario is created entirely by
**selecting** a non-matching seed row, which section 9 attests row by row.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input data** --
the feed record has not been processed yet. Master section 8.1 requires this to be stated every
time a blank timestamp is shown, because the byte pattern is identical to the one normalisation
produces and the meaning cannot be recovered from the bytes. It is the same reason the migrated
`ledger.daily_transactions.proc_ts` column is nullable while `ledger.transactions.proc_ts` is
`NOT NULL`.

**Both 26-byte fields travel into the reject record unchanged, and neither is masked there.**
`:447` copies the entire 350-byte input image, so the reject's `[278:304]` carries
`2022-06-10 19:27:53.000000` and its `[304:330]` carries the same 26 spaces the input carries --
still meaning "not processed". This is a real difference from the posting path, where `:437-438`
reads the clock into the output record's processing stamp: **a reject record contains no clock
reading at all**, which is why `dalyrejs.expected` can be compared byte for byte with no
normalisation of any field.

Everything else holds by construction: every byte here is literal, there is no random identifier
and no environment-derived string, and each test provisions and tears down its own workspace, per
master section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and four target-side contracts constrain what this
folder may expect:

- **`dto/RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE`** carries the code `100` and the
  description `INVALID CARD NUMBER FOUND` **verbatim**, and exposes both representations
  separately: `codeField()` renders the four-character `0100` of master section 7.1.2 and `code()`
  returns the integer the `SMALLINT` column stores. `trailerField()` composes the 80-byte trailer
  from a 4-character code and a 76-character description.
- **`dto/RejectReason.terminatesValidation`** is the target's expression of the short-circuit at
  `:372-373`, so the "the account lookup never ran" property is a modelled contract rather than an
  incidental ordering.
- **`dto/PostingValidationResult.resolve`** returns on `crossReferenceMissing` before the boundary
  comparison, and its projected cycle balance is `null` for this outcome -- there is no projection,
  because no account was read. The record's constructor refuses a result whose findings and
  projection disagree, so a decision claiming reason 100 **and** a projected balance is rejected
  outright.
- **`dto/BatchRunSummary`** enforces the warn-tier biconditional in both directions: the soft-warn
  tier holds **exactly when** the rejected count exceeds zero. This scenario must therefore report
  the warn tier with one processed, one rejected, zero written -- and a summary claiming the clean
  tier with a record rejected is refused.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql),
where lines 492 to 494 declare the reject contract by composition -- `raw_record CHAR(350)`,
`reason_code SMALLINT`, `reason_desc VARCHAR(76)`. That file is the authority for the schema and it
is deliberately not re-derived here; note only that the three columns reproduce the 430-byte
record's three parts exactly, so `350 + 4 + 76` survives the migration as a composition rather than
as a single opaque string.

The graded rubric 0, 4, 8 belongs to the COBOL parity suite alone (master section 7.1.6). The
warn-tier expectation here is the **job's** return code, asserted like any other fixture value; it
is not a build status, and a Java build reporting anything other than success is a failure.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs; the oracle
is the reference-only `tests/golden/posting/reject_100_card_missing/` tree, read as the authority
and never written, and no golden is ever regenerated.

### 8.2 Oracle constants that are not expectations for this scenario

Master section 7.1.7's constants describe the **full 300-record seed cycle** -- 300 daily records,
262 posted, 38 rejected **all reason `0102`**, a conservation total of `77954.70`, 50 category keys
becoming 100. **None is an expectation here**, and the `0102` figure is the one that matters most:
across the entire unmodified seed feed, **no** record produces reason 100, because every seed card
resolves in the seed cross-reference. That is precisely why this scenario exists as a fixture --
the reason is unreachable from the seeds as shipped and can only be reached by selecting a
non-matching pair. Nothing here contradicts the oracle; this folder reaches a branch its input
never visits.

The reject-slice constants **do** agree with this folder's expectation and are worth restating as a
cross-check rather than as a derivation: the oracle reads the card at `[262:278]`, the code at
`[350:354]` and the message at `[354:430]`, and `_REJ_RECLEN` is 430 -- the same three slices
section 3 asserts.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived, and no business-rule field is reshaped
anywhere in it.** Every record is a published AWS CardDemo sample seed row under
`app/data/ASCII/`, selected rather than edited:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | **byte-identical** |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 7 | **byte-identical** |
| `tcatbal.txt` | `app/data/ASCII/tcatbal.txt` | 7 | **byte-identical** once the `CR` is removed |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | **4** | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |

**The scenario is created by selection, not by reshaping.** The cross-reference row is row **4** of
its seed -- card `0927987108636232`, customer `000000020`, account `00000000020` -- chosen because
it is a real seed row naming a card the feed record does not carry. Every other posting scenario in
this tree uses row 21, which is where card `4859452612877065` appears. Swapping which seed row is
copied is the entire construction, and it is why the phrase "every business-rule field reshaped
away from its seed value" resolves to **none** for this folder.

**It represents no real person and no real account.** The seeds ship with the upstream open-source
project as fabricated demonstration data, and master section 11.1 carries the tree-level
attestation this scenario inherits. Identity and primary-account-number bytes -- including both card
numbers named in this document -- are taken unchanged from the seed.

The two normalizations named in section 5.4 are width and line-ending conformance, **not**
business-rule field changes, so they do not qualify the "nothing reshaped" statement above.

Trade-offs: constructing a reject by selection rather than by reshaping is strictly preferable
where it is available, because it leaves every byte attributable to a published seed row and
removes the need to justify a value at all. It is available for exactly two of the four reject
reasons -- this one and `reject_101_acct_missing` -- because a missing key can be arranged by
choosing rows, while the two boundary reasons need a value moved past a threshold no seed row
crosses. The cost accepted is that the two card numbers in this folder differ, which looks at first
glance like an inconsistency; section 5.3 and this section both name it as the design.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string
or endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the
COBOL baseline or the parity oracle.

---

*This README is the mandatory Explainability carrier for the four record files in this directory,
required by master section 10 and by user-specified Rule 1. `config/rule1/rule1_gate.py` decides
the form of the rationale labels above, repository-wide and including Markdown, which is why they
are written plain rather than emphasised. `config/checkstyle/checkstyle.xml` limits its audit set to
`java`, so no linter reads this prose. Whether each rationale names a real consequence, and whether
every number and line citation is true, are review obligations no lexical gate can decide.*

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**. `PostTransactionsJobParityIT` resolves each scenario under
`/fixtures/posting/`, seeds the masters from it, launches the posting job and compares the resulting
transaction master, category balances, account master and reject stream against
`tests/golden/posting/reject_100_card_missing` -- so an edit to these bytes changes what the parity run asserts.
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
