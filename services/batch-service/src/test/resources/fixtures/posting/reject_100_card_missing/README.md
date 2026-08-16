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
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. The path is written as a
> repository-root-relative code span rather than a link because eight levels of `../` from this
> directory is a path a later move silently breaks. This whole file is pure ASCII for the reason
> master section 1.4 gives.

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

### 1.1 Which side of the mismatch was changed, and why that side

The mismatch needs two values that disagree. **This folder changes the cross-reference and leaves
the daily transaction untouched**: `dailytran.txt` is byte-identical to `../happy_path/dailytran.txt`
and still carries seed card `4859452612877065`, while `cardxref.txt` names an unrelated card.

Alternatives Considered: producing the same reject by editing `DALYTRAN-CARD-NUM` instead -- moving
the feed record's card to a value no cross-reference row holds. It reaches reason 100 just as
reliably, and it is **not hypothetical**: the sibling corpus at
`services/transaction-service/src/test/resources/fixtures/reject_100_card_missing/` is built that
way, with `DALYTRAN-CARD-NUM` set to `9999999999999999`. It was rejected here because this tree's
nine posting scenarios share one driver transaction, and eight of them share one cross-reference
row -- seed row 21, the row that maps `4859452612877065` to account `00000000007`. Editing the feed
would give this folder a driver no other scenario has, so a diff between two scenarios would show
two changes and a reader could not tell which one caused the different outcome. Changing the
cross-reference keeps the driver constant across the family, so the diff that explains the outcome
is exactly one row in one file. `BatchFixtureContractTest` records the same split from the other
direction, warning that a card-number literal carried between the two trees would be wrong in one
of them.

Alternatives Considered: producing the miss by shipping an **empty** `cardxref.txt` rather than a
populated one naming a different card. Rejected because an empty cross-reference cannot distinguish
"the card was looked up and not found" from "the dataset was never loaded", and the second is a
harness failure that would present as a passing test. A populated file with one non-matching row
proves the dataset loaded, the read executed, and the key genuinely did not match.
`posting/zero_balance` uses the empty-file construction deliberately for the category-balance file,
where the ambiguity does not arise because the create arm's `DISPLAY` names the key it failed to
find.

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

The key is moved at `:382` and the read is issued at `:383`. The verdict is assigned across
**`:385-387`** -- `:385` moves the reason `100`, and the message literal at `:386` reaches its
receiver `WS-VALIDATION-FAIL-REASON-DESC` on the continuation line `:387`. The range is the correct
citation for the assignment because the code and its message occupy different lines; master section
7.1.1 gives both columns, because a citation may reasonably point at either and a reader following
one number should not land in the wrong construct.

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

The guard is `:372` and the guarded call is `:373`. Reason 100 is therefore **terminal for this
record**: `1500-B-LOOKUP-ACCT` (`:393-422`) is never entered, so `:395` never reads the account
master, `:403-405` never computes a projected balance, and neither boundary test at `:407` or `:414`
is evaluated. Master section 7.1.3 fixes this and draws the consequence for a fixture: a
`reject_100_card_missing` scenario may legitimately omit the account, and its README should say the
omission is **untested rather than satisfied**. Section 5.3 below says exactly that about the
account row this folder does ship.

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

The two halves are assembled by two statements: `:447` moves the whole 350-byte `DALYTRAN-RECORD`
into `REJECT-TRAN-DATA PIC X(350)` and `:448` moves `WS-VALIDATION-TRAILER` into
`VALIDATION-TRAILER PIC X(80)`. The receiving group is declared at `:176-178` and the trailer it
receives at `:180-182`, as `WS-VALIDATION-FAIL-REASON PIC 9(04)` followed by
`WS-VALIDATION-FAIL-REASON-DESC PIC X(76)`. Master section 5.4 proves the 430 six ways and is not
re-derived here.

`:447` is a **group move, not a re-encode**. Every byte of the input record survives it, including
the record's own trailing `FILLER` and its own blank processing timestamp -- which is why section 6
can say a reject record contains no clock reading at all.

**The code is `0100` on the wire and the integer `100` in storage.**

Assumptions: the two renderings are the same value in two contracts, and an expectation has to name
which one it asserts. `WS-VALIDATION-FAIL-REASON` is `PIC 9(04)` at `app/cbl/CBTRN02C.cbl:181`, so a
numeric-edited move left-pads with zeros and the trailer carries four characters -- `0100`, never
`100`, and never ` 100`. The migrated `ledger.transaction_rejects.reason_code` column is `SMALLINT`
and holds the integer `100`, because a fixed-width string column would make every arithmetic or
range predicate a cast. Master section 7.1.2 fixes both representations and warns what the confusion
costs: comparing the column against the string `0100` fails, and comparing the 430-byte record's
`[350:354]` against `100` fails, and **both failures look like a wrong reason code rather than a
wrong representation** -- which sends a reader to the validation logic instead of to the assertion.

**The message is space-padded to 76 characters**, because `:386-387` moves an alphanumeric literal
into an alphanumeric receiver and `MOVE` pads with spaces -- master section 6.3 records the same
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

**No balance moves on a reject.** The three writes that a posted record performs are the contiguous
block `:440-442` -- `:440` the category balance, `:441` the account, `:442` the transaction file --
all inside the branch `:212` selects; this record takes `:213-215` instead, so none of the three
executes and no money moves anywhere.

Assumptions: the golden corpus is corroboration, never the derivation. In
`tests/golden/posting/reject_100_card_missing/`, `return_code.expected` is the single byte `4`,
`dalyrejs.expected` is **431 bytes** -- 430 plus the single trailing `LF` of master section 3.9 --
`tranfile.expected` is **0 bytes**, and `acctdat.expected` and `tcatbal.expected` are
byte-identical to this folder's `acctdata.txt` and `tcatbal.txt` at 301 and 51 bytes. The
unchanged-input assertion is available **only** because those two files have content; against empty
files it would assert nothing, which is the first of the two reasons section 5.3 gives for shipping
a record neither file's job ever reads.

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

**Code 109 must not be expected.** `:556-558` assigns it inside the `INVALID KEY` branch of the
account `REWRITE` at `:554-559`, which sits in `2800-UPDATE-ACCOUNT-REC` on the posting path --
reached from `:441`, long after the reject decision was taken at `:211-216` -- and writes no reject
record. The persisted reject-code domain is therefore exactly `{100, 101, 102, 103}`, and 109 is a
dead write with respect to the reject stream; `dto/RejectReason` models it with a predicate that
answers false for exactly that reason, and this statement agrees with it rather than restating it.

**Nothing may be posted.** A run that wrote a transaction record here would have posted a
transaction for a card that resolves to no account, so its category-balance key would have had no
account id to compose from -- `:469` moves `XREF-ACCT-ID`, which the failed read never populated.

**No abend occurs.** Reject 100 is a soft, expected outcome and shares no path with
`9999-ABEND-PROGRAM` (`:707`). The reachable abend sites on this path are all status-guarded: the
six opens (`:250`, `:268`, `:287`, `:305`, `:323`, `:341`), the feed read (`:363-366`), the
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

All four files are **populated**; nothing in this scenario is empty, and the `empty_input` semantic
of master section 3.11 is not in play here.

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

Every file is exactly **RECLN + 1** bytes -- one record plus a single trailing `LF`, per master
sections 3.8 and 3.9 -- and there are **zero `CR` bytes** anywhere in the folder.

Trade-offs: `tcatbal.txt` is stored `LF`-only even though its seed row ships `CRLF`, so this folder
is not a byte-for-byte copy of that seed line. The alternative -- preserving the seed's `CRLF` --
was rejected because the `0x0D` would land inside the record rather than after it: the row would
measure **51 bytes instead of 50**, the extra byte would be absorbed into the 22-byte run of ASCII
`'0'` that ends the record, and the load would fail or the last field would decode wrongly **with
every visible field value still correct**. That is the most expensive failure shape available here,
because the bytes look right in a text editor. The cost accepted is the small provenance asymmetry,
which section 9 names explicitly rather than leaving a reader to discover it by comparing sizes.
Master section 3.8 requires the choice to be recorded per scenario, and this is that record.

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

Assumptions: the tables below tabulate **this scenario's observed byte values**, which no tree-level
document can hold, and carry the offset and width columns only as the addressing a reader needs to
locate each value in the record. Master section 5.2 remains the authority for the layout itself: if
an offset here ever disagreed with it, the master is right and this file is stale. Reproducing the
values without their addressing was the alternative, and it was rejected because a byte string with
no offset cannot be checked against the file without counting characters by hand.

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

`cardxref.txt`, one `CARD-XREF-RECORD` -- the row that does **not** match, and the only file in this
folder whose contents decide the outcome:

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
ten spaces, `FILLER` 178 spaces. `ACCT-EXPIRAION-DATE` is the baseline's own misspelling, declared
that way at `app/cpy/CVACT01Y.cpy:11` and preserved here; master section 9.3 records that the
correction applies to target column names only.

**This account row is present and is never read, so this folder tests nothing about it.** Master
section 7.1.3 requires the point to be made this way round: the omission of the account lookup is
**untested rather than satisfied**.

Assumptions: a fully valid account row is kept precisely **because** nothing reads it, and that is
positive evidence rather than dead weight. An empty or absent account master would make reason 100
and reason 101 indistinguishable as fixtures -- both would then present as "no resolvable account",
and a run that had validated the account first would still reject, so the scenario would no longer
prove the `:372` short-circuit at all. Two consequences follow from shipping it. It lets section 3
assert the account master is **unchanged byte for byte** after the run, an assertion that is vacuous
against an empty file and that a job reaching the posting path would violate. And it keeps the
folder's shape uniform with the other eight posting scenarios, so a reader comparing them is not
left wondering whether a file went missing. Note also that this row is account `00000000007` while
the non-matching cross-reference row names account `00000000020`, so even if the short-circuit were
broken the account lookup would search for `00000000020` and miss -- which is the shape
[`../reject_101_acct_missing`](../reject_101_acct_missing/README.md) pins deliberately, by keeping
the driver's own cross-reference mapping and holding account `00000000020` in its master instead.

`tcatbal.txt`, one `TRAN-CAT-BAL-RECORD`: `TRANCAT-ACCT-ID` `00000000007` at 0, `TRANCAT-TYPE-CD`
`01` at 11, `TRANCAT-CD` `0001` at 13, `TRAN-CAT-BAL` `0000000000{` = `+0.00` at 17, and 22 ASCII
`'0'` of `FILLER` at 28. It is likewise present, unread and asserted unchanged.

Assumptions: the category-balance file is populated because the job **opens** it unconditionally.
`0500-TCATBALF-OPEN` runs at `:200`, before the read loop begins at `:204`, so a missing or
unloadable dataset fails at `OPEN` -- reaching the status guard and `9999-ABEND-PROGRAM` at `:341`
-- and the run would abend without ever evaluating the reject-100 logic this folder exists to
exercise. The failure would be reported as an abend on a file the scenario has no opinion about,
which is the least informative place it could surface. A loadable single row costs one 50-byte
record and removes that failure mode entirely.

Assumptions: this row also differs from `../happy_path/tcatbal.txt` in exactly one byte, at offset
23 -- an opening `TRAN-CAT-BAL` of `0000000000{` = `+0.00` here against `0000001000{` = `+100.00`
there -- and that difference **cannot** affect this scenario's outcome, because the category-balance
row is not consulted anywhere in validation.

Assumptions: this sentence said `+10.00` rather than `+100.00`, and the correction is recorded because
the decode is the exact place a reader is most likely to make the same slip. The field is
`PIC S9(09)V99` and therefore **eleven** byte positions, of which the last carries both the eleventh
digit and the sign: `0000001000{` decodes to the digits `00000010000` and the overpunch `{` is a
positive trailing `0`, so the value is `+100.00` and not `+10.00`. Reading the ten leading characters
as the whole number and the overpunch as a sign carrying no digit is what produces the answer that was
written here, and it is off by a factor of ten in every field of this shape. `../happy_path`'s own
section 4 states `+100.00` for the same bytes, so the two documents disagreed until now -- and a
disagreement between two fixture READMEs about one byte is the kind of thing a reader resolves by
guessing, which is why the decode is shown here rather than only the result. It is read and rewritten only by `2700-UPDATE-TCATBAL` at `:467`, reached from `:440` on
the posting path. The precise statement about what makes this folder reject is therefore narrower
than "one file differs": `dailytran.txt` and `acctdata.txt` are byte-identical to `../happy_path`,
`tcatbal.txt` differs by one byte that no validation path reads, and `cardxref.txt` is the only
difference that can change the verdict.

**The `FILLER` bytes differ by measurement, not by inconsistency.** Three files pad with `0x20`
SPACE; `tcatbal.txt` pads with ASCII `'0'`, `0x30`. Both are the bytes master section 6.1 measures,
and master sections 6.2 and 6.2.1 record the category-balance row as one of the two contradicting
the general rule of section 3.2, with the measured byte winning.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50 and declares a trailing `FILLER X(14)` the seed rows omit. Authored at full copybook width with that `FILLER` space-padded |
| `tcatbal.txt` is `LF`-terminated where its seed is `CRLF` | master sections 3.8, 3.9 | A stray `0x0D` absorbed into the 22-byte `'0'` `FILLER` would push the record to 51 bytes and fail the load with every field value correct -- see the `Trade-offs:` note in section 5.2 |
| The cross-reference deliberately omits the feed record's card | master section 8 | Master section 8 permits a key to be deliberately omitted to trigger a reject, and requires the scenario's README to name it. This is that naming: card `4859452612877065` is absent from the cross-reference by design |

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input data** --
the feed record has not been processed yet.

Assumptions: the input processing timestamp is blank **by contract**, not by omission and not by
masking. Master section 8.1 requires the distinction to be stated every time a blank timestamp is
shown, because the byte pattern a not-yet-processed record carries is identical to the one
normalisation produces, and the meaning cannot be recovered from the bytes. Only the **output**
stamp is runtime-generated: `:437-438` performs `Z-GET-DB2-FORMAT-TIMESTAMP` (`:692`) and moves the
result into the posted record's `TRAN-PROC-TS`, and that is the only clock reading in the program's
posting path. The master's posting normalisation set masks `PROC-TS` and deliberately **asserts**
`ORIG-TS`, so a fixture that pre-filled the processing stamp would be asserting a value the job is
supposed to write. It is the same reason the migrated `ledger.daily_transactions.proc_ts` column is
nullable while `ledger.transactions.proc_ts` is `NOT NULL`.

**Both 26-byte fields travel into the reject record unchanged, and neither is masked there.**
`:447` copies the entire 350-byte input image, so the reject's `[278:304]` carries
`2022-06-10 19:27:53.000000` and its `[304:330]` carries the same 26 spaces the input carries --
still meaning "not processed". This is a real difference from the posting path: **a reject record
contains no clock reading at all**, which is why `dalyrejs.expected` can be compared byte for byte
with no normalisation of any field.

Everything else holds by construction: every byte here is literal, there is no random identifier
and no environment-derived string, and each test provisions and tears down its own workspace, per
master section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and four target-side contracts constrain what this
folder may expect. Each owns its own contract; they are named here, not re-specified.

- **`service/PostingValidationService`** performs the cross-reference lookup first and **stops**
  before the account lookup once the reference is missing, mirroring `:372`. The "the account lookup
  never ran" property is a modelled contract rather than an incidental ordering.
- **`dto/RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE`** carries the code `100` and the
  description `INVALID CARD NUMBER FOUND` **verbatim**, and exposes both representations
  separately: `codeField()` renders the four-character `0100` of master section 7.1.2 and `code()`
  returns the integer the `SMALLINT` column stores. `trailerField()` composes the 80-byte trailer
  from a 4-character code and a 76-character description, and `terminatesValidation()` is the
  target's expression of the short-circuit at `:372-373`.
- **`dto/PostingValidationResult.resolve`** returns on `crossReferenceMissing` before the boundary
  comparison, and its projected cycle balance is `null` for this outcome -- there is no projection,
  because no account was read. The record's constructor refuses a result whose findings and
  projection disagree, so a decision claiming reason 100 **and** a projected balance is rejected
  outright.
- **`mapper/TransactionRejectRecordMapper`** renders the 430 bytes, and **`dto/BatchReturnCode`**
  supplies the warn tier. **`dto/BatchRunSummary`** enforces the warn-tier biconditional in both
  directions: the soft-warn tier holds **exactly when** the rejected count exceeds zero. This
  scenario must therefore report the warn tier with one processed, one rejected, zero written -- and
  a summary claiming the clean tier with a record rejected is refused.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql),
where `ledger.transaction_rejects` is created at line 554 and declares the reject contract by
composition across lines **556 to 558** -- `raw_record CHAR(350)`, `reason_code SMALLINT`,
`reason_desc VARCHAR(76)`. That file is the authority for the schema and it is deliberately not
re-derived here; note only that the three columns reproduce the 430-byte record's three parts
exactly, so `350 + 4 + 76` survives the migration as a composition rather than as a single opaque
string.

The graded rubric 0, 4, 8 belongs to the COBOL parity suite alone (master section 7.1.6). The
warn-tier expectation here is the **job's** return code, asserted like any other fixture value; it
is not a build status. Maven, Surefire, Failsafe and JUnit are binary pass or fail, so a Java build
reporting anything other than success is a failure -- there is no warn-level green.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs; the oracle
is the reference-only `tests/golden/posting/reject_100_card_missing/` tree, read as the authority
and never written, and no golden is ever regenerated.

### 8.2 Oracle constants that are not expectations for this scenario

Master section 7.1.7's constants describe the **full 300-record seed cycle** -- 300 daily records,
262 posted, 38 rejected **all reason `0102`**, a conservation total of `77954.70`, 50 category keys
becoming 100, with loader geometry `(300, 11)` for the account master and `(50, 17)` for the
category-balance file. **None is an expectation here**, and the `0102` figure is the one that matters
most: across the entire unmodified seed feed, **no** record produces reason 100, because every seed
card resolves in the seed cross-reference. That is precisely why this scenario exists as a fixture --
the reason is unreachable from the seeds as shipped and can only be reached by selecting a
non-matching pair. Nothing here contradicts the oracle; this folder reaches a branch the full seed
never visits.

The reject-slice constants **do** agree with this folder's expectation and are worth restating as a
cross-check rather than as a derivation: the oracle reads the card at `[262:278]`, the code at
`[350:354]` and the message at `[354:430]`, and `_REJ_RECLEN` is 430 -- the same three slices
section 3 asserts.

### 8.3 What this folder may not touch

`app/**`, `tests/**` and `scripts/**` are **reference-only** (AAP section 0.2.2). This document reads
and cites them by line and never modifies them: the COBOL is the specification, the golden tree is
the oracle, and both keep their authority only by staying byte-identical.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived.** Every record traces to a published AWS
CardDemo sample seed row under `app/data/ASCII/`:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | **byte-identical** |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 7 | **byte-identical** |
| `tcatbal.txt` | `app/data/ASCII/tcatbal.txt` | 7 | **byte-identical** once the `CR` is removed |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | **4** | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |

**It represents no real person and no real account.** The seeds ship with the upstream open-source
project as fabricated demonstration data, and master section 11.1 carries the tree-level attestation
this scenario inherits.

Assumptions: the attestation is stated rather than assumed because **two checksum-valid primary
account numbers appear in this folder** -- `4859452612877065` in the feed record and
`0927987108636232` in the cross-reference -- and neither can be distinguished from a live card
number by inspection. A reader who cannot rule out live data by looking at it needs the provenance
recorded, so both values are named here with the seed row each comes from.

### 9.1 Business-rule fields reshaped away from their seed value

Master section 10 requires every scenario README to name **every business-rule field reshaped away
from its seed value**, because a reader comparing a fixture row against a seed row byte for byte
would otherwise conclude the row was invented. This folder reshapes **three**, all in the
cross-reference record:

| Field | Value here | Value in the row this scenario displaced |
|---|---|---|
| `XREF-CARD-NUM` | `0927987108636232` | `4859452612877065` |
| `XREF-CUST-ID` | `000000020` | `000000007` |
| `XREF-ACCT-ID` | `00000000020` | `00000000007` |

They are reshaped **wholesale, by row substitution rather than by field editing**: seed row 4 is
copied in place of seed row 21, which is the row that maps the driver transaction's card to account
`00000000007` and which the other eight posting scenarios in this tree all use. Swapping which seed
row is copied is the entire construction of the scenario, and it is what makes the lookup at `:383`
miss.

Two properties of that mechanism are worth stating together, because either alone misleads. Because
the substitution is wholesale, **every byte in the file is still a verbatim seed byte** and no value
was invented -- which is why the table above compares this folder against the displaced row rather
than against nothing. And because the three fields nevertheless do not carry the values the driver's
own cross-reference row carries, they **are** reshaped in the sense master section 10 means, and
naming them is the audit obligation that clause exists to create.

Two further changes are **mechanical conformance, not business-rule reshaping**, and are recorded
here so the list above is not read as exhaustive of all differences: `cardxref.txt` has the 14-space
`FILLER X(14)` appended because the seed rows stop at 36 bytes (master section 3.10), and
`tcatbal.txt` has its seed `CR` stripped (master section 3.8). Neither changes a field a business
rule reads.

Trade-offs: constructing a reject by substituting a seed row rather than by editing a field is
strictly preferable where it is available, because it leaves every byte attributable to a published
seed row and removes the need to justify an invented value at all. It is available for exactly two
of the four reject reasons -- this one and `reject_101_acct_missing` -- because a missing key can be
arranged by choosing rows, while the two boundary reasons need a value moved past a threshold no
seed row crosses. The cost accepted is that the two card numbers in this folder differ, which looks
at first glance like an inconsistency; sections 5.3 and 9.1 both name it as the design.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string
or endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the
COBOL baseline or the parity oracle.

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**, and **three** classes read it. `PostTransactionsJobParityIT`
resolves each scenario under the classpath root `/fixtures/posting/`, seeds the account master, the
cross-reference, the category balances and the feed from these four files, launches the posting job,
and compares the resulting transaction master, category balances, account master and reject stream
against `tests/golden/posting/reject_100_card_missing`. `PostTransactionsJobTest` resolves the same
scenario under `fixtures/posting/` at the unit tier and seeds the same four relations from the same
bytes. **An edit to these bytes changes what both runs assert.**

`BatchFixtureContractTest` additionally holds every file here to its declared geometry, to
`LF`-only line endings and the one-trailing-newline rule, to a successful decode under its declared
layout, to its committed SHA-256, and to the one relation that makes this scenario discriminating --
that `XREF-CARD-NUM` and `DALYTRAN-CARD-NUM` differ here, where `../happy_path` requires them equal. A
layout mistake or an unintended byte change is therefore caught in this module rather than surfacing
later as a comparison failure.

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

Assumptions: the inventory is cited to master section 1.5 rather than restated per scenario here,
because a per-folder copy of it is the claim that goes stale the moment a class starts or stops
reading a directory -- which is precisely how the withdrawn paragraph above came to be wrong.

---

*This README is the mandatory Explainability carrier for the four record files in this directory,
required by master section 10 and by user-specified Rule 1 (AAP section 0.8.1). No linter reads this
prose: `config/checkstyle/checkstyle.xml` limits its audit set to `java`, and
`config/rule1/rule1_gate.py` excludes the whole `/src/test/resources/fixtures/` path segment, so
this document is outside the remit of both. The labels above are nonetheless written in the one form
that gate enforces everywhere it does read, because a single repository-wide spelling is what makes
a rationale findable by search. What IS machine-checked is this file's presence and its neighbours'
geometry, by `BatchFixtureContractTest`. Whether each rationale names a real consequence, and whether
every number and line citation is true, are review obligations no lexical gate can decide.*
