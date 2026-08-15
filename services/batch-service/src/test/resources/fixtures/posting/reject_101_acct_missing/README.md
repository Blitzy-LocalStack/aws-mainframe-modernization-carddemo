# `posting/reject_101_acct_missing` -- the card resolves, and its account does not exist

> **Purpose.** Pin reject reason **101**: a daily transaction whose card **is** in the card
> cross-reference but whose resolved account is **absent** from the account master is refused with
> the message `ACCOUNT RECORD NOT FOUND`, and the run reports the soft-warn tier. The scenario is
> the second half of a pair with `posting/reject_100_card_missing`: together they separate the two
> lookups of `1500-VALIDATE-TRAN`, which both fail with a not-found and which are otherwise easy to
> conflate.
>
> **Source of truth.** `app/cbl/CBTRN02C.cbl` for the behaviour and `app/jcl/POSTTRAN.jcl` for the
> dataset contract, both reference-only and both cited below by line; `app/cpy/CVTRA06Y.cpy`,
> `app/cpy/CVACT03Y.cpy`, `app/cpy/CVACT01Y.cpy` and `app/cpy/CVTRA01Y.cpy` for the record layouts;
> the seed datasets under `app/data/ASCII/` for the bytes;
> `tests/golden/posting/reject_101_acct_missing/` for the expected outputs; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document cites by section
> rather than restating (master section 1.3).
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. Rule 1's fourth category is
> **factually unavailable here** and is not used; section 11 records why, and records which automated
> gates do and do not read this file. This whole file is pure ASCII for the reason master section 1.4
> gives.

**This README is the mandatory Explainability carrier for the four record files beside it.** Master
section 1.2 records why: a fixed-width record file cannot carry a comment of any kind, because every
byte position is meaningful and a comment on its own line is a physical row of the wrong length.
Master section 10 makes the artifact mandatory rather than courteous, and the section order below is
the one it fixes. The four elements Rule 1 requires of a docstring map onto sections **1** (purpose),
**5** (parameters -- the four files and their byte geometry), **3** (returns -- the expected outcome)
and **4** (errors), so a reader auditing this document against the rule can find each one directly.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction on card `4859452612877065`. The cross-reference **does** hold that card and
resolves it to account **`00000000007`**. The account master holds a single row for a **different**
account, **`00000000020`**. The first lookup succeeds, the second fails, and reason 101 is the
result.

Three things must hold at once:

- the cross-reference lookup **succeeds**, so reason 100 is not set and validation continues;
- the account lookup **misses**, so reason 101 is set and the record is refused;
- neither boundary test is evaluated, because both live in the `NOT INVALID KEY` branch the failed
  read does not take -- so this scenario says nothing about the credit limit or the expiration date
  even though the account row it does ship carries both.

Alternatives Considered: shipping an **empty** `acctdata.txt` rather than a populated one naming a
different account. Rejected for the same reason `reject_100_card_missing` rejects an empty
cross-reference: an empty master cannot distinguish "the account was looked up and not found" from
"the dataset was never loaded", and the second is a harness failure that presents as a passing test.
A populated file holding one non-matching row proves the master loaded, the read executed, and the
key genuinely did not match.

Assumptions: the ordering of the two failures is what makes this scenario distinct rather than
redundant, and it is a property of the program rather than of the data. `:372-373` performs the
account lookup **only** when the reason is still zero, so reaching reason 101 requires the
cross-reference lookup to have succeeded first. A fixture in which **both** lookups failed would
report 100, not 101 -- the two reasons are not independent conditions that could both be reported,
and no reject record ever carries two codes.

---

## 2. The business rule, cited by program and line

**`app/cbl/CBTRN02C.cbl:393-399`, the `INVALID KEY` branch of `1500-B-LOOKUP-ACCT`.**

```cobol
 1500-B-LOOKUP-ACCT.
     MOVE XREF-ACCT-ID TO FD-ACCT-ID
     READ ACCOUNT-FILE INTO ACCOUNT-RECORD
        INVALID KEY
          MOVE 101 TO WS-VALIDATION-FAIL-REASON
          MOVE 'ACCOUNT RECORD NOT FOUND'
             TO WS-VALIDATION-FAIL-REASON-DESC
```

That is `:393-399`, reproduced faithfully. The reason is assigned at **`:397`** and the message
literal is at **`:398`** -- master section 7.1.1 gives both columns because a citation may
reasonably point at either.

**The key comes from the cross-reference, not from the feed.** `:394` moves `XREF-ACCT-ID` -- the
value the successful read at `:383` placed in `CARD-XREF-RECORD` -- into the account file key. The
daily transaction record carries **no account number at all**; its only link to an account is
through the card. That indirection is the whole shape of this scenario: the feed names a card, the
cross-reference turns it into `00000000007`, and the master does not hold that account.

**Two consequences of taking the `INVALID KEY` branch**, both of which are expectations rather than
observations:

- `:403-405` never runs, so **no projected cycle balance is computed**;
- `:407` and `:414` are inside the `NOT INVALID KEY` branch at `:400-420`, so neither the
  credit-limit test nor the expiration test is evaluated, and reasons 102 and 103 are unreachable.

The rest of the walk, all in `app/cbl/CBTRN02C.cbl`:

| Step | Line | What happens for this record |
|---|---|---|
| Read the feed | `:204` | status `'00'`, so the loop body runs |
| Count it | `:206` | processed count becomes 1 |
| Clear the verdict | `:208-209` | reason 0, description spaces |
| Cross-reference **hits** | `:382-383`, `:388` | card `4859452612877065` is found; `XREF-ACCT-ID` becomes `00000000007`; no reason is set |
| Continue validation | `:372-373` | the reason is still 0, so `1500-B-LOOKUP-ACCT` is performed |
| Account **misses** | `:394-395`, `:396-399` | the read for `00000000007` takes `INVALID KEY`; reason 101 and its message are assigned |
| Take the reject path | `:211`, `:213-215` | the reason is not 0, so the reject counter is incremented and `2500-WRITE-REJECT-REC` runs |
| Build the reject | `:447-448` | `:447` moves the entire 350-byte `DALYTRAN-RECORD` verbatim; `:448` appends the 80-byte trailer |
| Write it | `:451` | one 430-byte record; `:452` requires status `'00'` |
| Grade the run | `:229-230` | the rejected count exceeds zero, so `MOVE 4 TO RETURN-CODE` |

**Nothing is posted.** `2000-POST-TRANSACTION` (`:424-444`) is reached only from `:212`, on the
branch this record does not take.

---

## 3. Returns -- the expected outcome

The return code is **4**, the soft-warn tier -- `:229-230` moves 4 because the rejected count is
one. Master section 7.1.6 fixes that tier as a **fixture expectation value** belonging to the COBOL
parity suite's graded rubric, never to a Java build gate.

**The reject record**, one row of 430 bytes:

| Slice | Width | Contents |
|---|---:|---|
| `[0:350]` | 350 | the input `DALYTRAN-RECORD` **verbatim**, including card `4859452612877065` at `[262:278]` |
| `[350:354]` | 4 | **`0101`** |
| `[354:430]` | 76 | **`ACCOUNT RECORD NOT FOUND`** followed by 52 spaces |

**The 430 is arithmetic on declared widths, not a constant to be looked up.** `REJECT-RECORD` is
declared at `app/cbl/CBTRN02C.cbl:176-178` as `REJECT-TRAN-DATA PIC X(350)` followed by
`VALIDATION-TRAILER PIC X(80)`; the trailer's own two fields are declared separately, with
`WS-VALIDATION-FAIL-REASON PIC 9(04)` at `:181` and `WS-VALIDATION-FAIL-REASON-DESC PIC X(76)` at
`:182`. `2500-WRITE-REJECT-REC`
fills the two halves in that order -- `:447` moves the whole 350-byte `DALYTRAN-RECORD` into the first
and `:448` moves the trailer into the second -- and `:451` writes the group. So the record is
**350 + 4 + 76 = 430**, which is also the `LRECL=430` the job allocates at `app/jcl/POSTTRAN.jcl:36`
and the width master section 5.4 proves six independent ways.

`ACCOUNT RECORD NOT FOUND` is 24 characters, so 52 spaces follow -- the padding `MOVE` supplies at
`:398`, per the mechanism master section 6.3 records for the trailer description.

Assumptions: the 350-byte prefix is a **group move**, not a field-by-field re-encode, and that single
fact decides most of what this section can assert. `:447` moves one group item to another of the same
declared width, so every byte of the feed record arrives at the same offset it occupied on input --
which is why the card is still readable at `[262:278]` in the reject image, why the blank
`DALYTRAN-PROC-TS` at `[304:330]` and the 20-space `FILLER` at `[330:350]` survive unchanged, and why
no field in the prefix needs its own expectation. The alternative reading -- that the reject writer
re-serialises the transaction -- would make each of those a separate claim to verify and would leave
the offsets open to drift; the `MOVE` at `:447` is the evidence that they cannot.

**The reject stream does not carry the account number.** The 350-byte image is the *feed* record,
and the feed record has no account field; the resolved `00000000007` exists only in working storage
at the moment of the failure. So a reader trying to learn *which* account was missing must resolve
the card through the cross-reference themselves. This is worth stating because it is a real limit on
what the reject stream can be asserted against, and because it is the one respect in which reasons
100 and 101 produce indistinguishable payloads apart from their four code bytes and their message.

**The code is `0101` on the wire and the integer `101` in storage.** `WS-VALIDATION-FAIL-REASON` is
`PIC 9(04)` at `app/cbl/CBTRN02C.cbl:181`, so the trailer carries four characters zero-padded. The
migrated `ledger.transaction_rejects.reason_code` column is `SMALLINT` and holds the integer. Master
section 7.1.2 fixes both representations and warns that an expectation must name which one it
asserts.

Assumptions: the two representations are not interchangeable and the difference is a `PICTURE`
clause, not a formatting preference. `PIC 9(04)` is an unsigned four-position display field, so the
byte image of 101 is necessarily `0101` -- the leading zero is a character the field must contain,
not padding a writer chose to add. `SMALLINT` stores a number and has no width at all, so the same
value round-trips as `101`. An expectation that greps the reject stream for `101` therefore matches
at the wrong offset, and one that queries the column for `'0101'` compares an integer against a
string; both fail for a reason that has nothing to do with the business rule under test. Naming
which representation is being asserted is the only reliable defence, which is why every slice in the
table above is given as an offset pair rather than as a search string.

The 430-byte composition is corroborated independently by the reference-only parity oracle, which
is worth citing because it was written from the same COBOL without reference to this document:
`tests/e2e/test_posting_cycle.py:110-111` declares `_REJ_CODE = slice(350, 354)` and
`_REJ_MSG = slice(354, 430)`, its `:109` declares `_REJ_CARD = slice(262, 278)` and its `:108`
declares `_REJ_RECLEN = 430`; `tests/integration/test_cbtrn02c_posting.py:53` restates the trailer
as `WS-VALIDATION-FAIL-REASON 9(04) @ [350:354]` plus `DESC X(76) @ [354:430]`. Two independent
derivations agreeing on all three slices is the check; neither is the source, which remains the
copybook and the `MOVE` statements at `:447-448`.

**Nothing else changes.**

| Output | Expected | Authority |
|---|---|---|
| Posted transaction records | **0** | `2900-WRITE-TRANSACTION-FILE` at `:562` is reached only from `:442` |
| `tcatbal.txt` after the run | **unchanged, byte for byte** | `2700-UPDATE-TCATBAL` at `:467` is reached only from `:440` |
| `acctdata.txt` after the run | **unchanged, byte for byte** | opened I-O at `:311`, but `2800-UPDATE-ACCOUNT-REC` at `:545` is reached only from `:441` |
| `cardxref.txt` after the run | **unchanged, byte for byte** | opened INPUT at `:275`; the job has no statement that writes it |
| Counters | 1 processed, 1 rejected | `:227-228` |

Assumptions: the golden corpus is corroboration, never the derivation. In
`tests/golden/posting/reject_101_acct_missing/`, `return_code.expected` is the single byte `4`,
`dalyrejs.expected` is **431 bytes** -- 430 plus the single trailing `LF` of master section 3.9 --
`tranfile.expected` is **0 bytes**, and `acctdat.expected` and `tcatbal.expected` are byte-identical
to this folder's `acctdata.txt` and `tcatbal.txt` at 301 and 51 bytes. Note what the first of those
two proves: the account master still holds account `00000000020` with its original balance after the
run, so the job did not create the missing account, did not post to the one that was there, and did
not touch a dataset it opened I-O.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**The error path this record takes is a soft reject, and it is not an error condition in the program's
own terms.** The whole decision is the `IF`/`ELSE` at `:211-216`: validation returned a non-zero
reason, so the `ELSE` arm at `:213-215` is taken, `:214` increments `WS-REJECT-COUNT` and `:215`
performs `2500-WRITE-REJECT-REC`, which writes exactly one 430-byte record at `:451` and requires
status `'00'` at `:452`. The run then grades itself at `:229-230`. **No abend, no exception and no
non-zero file status is involved anywhere in that path**, and **no posting side effect occurs at
all** -- not a transaction row, not a category-balance row, not an account update -- because
`2000-POST-TRANSACTION` is performed only from `:212`, the other arm of the same decision. A reject is
a business outcome the program is designed to produce, which is why the return code is the warn tier
rather than a failure tier.

**100 must not be reported.** The card **is** in the cross-reference, so `:385` cannot fire. An
implementation that reported 100 here would be failing the first lookup for a card that resolves --
and, because both reasons produce a 430-byte reject with the same 350-byte image and the same return
code, the difference would appear **only** in the four code bytes at `[350:354]` and the message at
`[354:430]`. This is the mirror of the negative `reject_100_card_missing` makes, and the two folders
are only meaningful as a pair.

**102 and 103 must not be reported.** Both are assigned inside the `NOT INVALID KEY` branch at
`:400-420`, which a failed read does not take. This matters more here than in any other reject
scenario, because this folder's account row **does** carry a credit limit of `+3767.00` and an
expiration date of `2024-03-13` -- values an implementation that read the wrong row, or that fell
back to some default row, could plausibly compare against. The feed amount `+504.77` is inside that
limit and the feed date `2022-06-10` is before that expiration, so such an implementation would
report **no reject at all** rather than the wrong one: return code 0, one posted transaction, an
empty reject stream. That is the failure shape to expect if the account lookup silently resolves to
the wrong account.

**Code 109 must not be expected.** `:556` assigns it inside the `INVALID KEY` branch of the account
`REWRITE` at `:554-559`, on the posting path -- not taken here -- and writes no reject record. Master
section 7.1.1 fixes the persisted domain as exactly `{100, 101, 102, 103}` and records 109 as a dead
write. The distinction is worth naming in this folder specifically: 109 carries the **same message
text** as 101, `ACCOUNT RECORD NOT FOUND`, moved at `:557`. The two are different codes for
different conditions -- a read that missed before posting, and a rewrite that missed during posting
-- and only the first ever reaches the reject stream.

**No abend occurs.** The reachable `9999-ABEND-PROGRAM` sites are all status-guarded: the six opens
(`:250`, `:268`, `:287`, `:305`, `:323`, `:341`), the feed read (`:363-366`), the reject write
(`:463`) and the six closes (`:596`, `:614`, `:633`, `:651`, `:669`, `:688`). A `READ` returning
`INVALID KEY` is **not** an error -- it is the branch the rule lives in, and `1500-B-LOOKUP-ACCT`
has no abend site of its own at all.

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
base cluster only -- and it is the **account** file's key at offset 0 that this scenario makes fail.

The reject dataset is an output and has no fixture file here. Its 430-byte geometry is proved six
ways in master section 5.4, and `app/jcl/POSTTRAN.jcl:34-38` allocates it as `DALYREJS(+1)` with
`DISP=(NEW,CATLG,DELETE)` and `LRECL=430`.

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
# WHAT: assert the byte geometry, and additionally assert the two relations this scenario turns on
#       -- that the feed's card DOES match the cross-reference key, and that the account the
#       cross-reference resolves to does NOT match the account master's key. Run from this
#       directory.
# WHY : Assumptions: this scenario needs one lookup to hit and the next to miss, so it has two
#       independent ways to degrade and each produces a different wrong outcome. If the card stops
#       matching, the expectation becomes reason 100 with the same return code and the same
#       350-byte image, so the failure is four bytes wide and easy to misread as a program defect.
#       If the account starts matching, the record posts and the reject disappears entirely.
#       Asserting both relations at authoring time is what tells the two apart before a comparison
#       runs.
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
xref = open("cardxref.txt", "rb").read()
resolved = xref[25:36].decode("ascii")
held = open("acctdata.txt", "rb").read()[0:11].decode("ascii")
print("feed card", feed_card, "xref key", xref[0:16].decode("ascii"),
      "| OK the card resolves" if feed_card == xref[0:16].decode("ascii")
      else "| MISMATCH reason 100 would fire instead")
print("resolved account", resolved, "account master holds", held,
      "| OK the account lookup misses" if resolved != held
      else "| MISMATCH the record would post")
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
| `DALYTRAN-CARD-NUM` | 262 | 16 | **`4859452612877065`** | the key `:382` moves and `:383` **finds** |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `2022-06-10 19:27:53.000000` | never compared against an expiration date here |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

`cardxref.txt`, one `CARD-XREF-RECORD` -- the row that **matches** and resolves the account:

| Field | Offset | Width | Bytes |
|---|---:|---:|---|
| `XREF-CARD-NUM` | 0 | 16 | `4859452612877065` |
| `XREF-CUST-ID` | 16 | 9 | `000000007` |
| `XREF-ACCT-ID` | 25 | 11 | **`00000000007`** -- the key `:394` moves and `:395` fails to find |
| `FILLER` | 36 | 14 | 14 spaces |

`acctdata.txt`, one `ACCOUNT-RECORD` -- the row that does **not** match:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | **`00000000020`** | account 20, not the resolved 7 |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | active -- and unread by this job |
| `ACCT-CURR-BAL` | 12 | 12 | `00000003690{` | `+369.00` |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000037670{` | `+3767.00` -- never compared, see section 4 |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000010400{` | `+1040.00` |
| `ACCT-OPEN-DATE` | 48 | 10 | `2014-02-27` | -- |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `2024-03-13` | never compared, see section 4 |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2024-03-13` | -- |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` | `+0.00` |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` | `+0.00` |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | -- |
| `ACCT-GROUP-ID` | 112 | 10 | 10 spaces | blank -- unread by this job |
| `FILLER` | 122 | 178 | 178 spaces | -- |

**This row's balance, limits and dates all differ from those of account `00000000007`, which every
other posting scenario in this tree carries.** That is deliberate and it is a diagnostic aid: a
`+369.00` balance or a `+3767.00` limit appearing anywhere in a comparison is immediate evidence
that this folder's account row was read when it should not have been, whereas a row carrying the
familiar `+193.00` and `+2065.00` would be indistinguishable from the sibling folders' at a glance.

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` line 11 spells it, preserved
verbatim because this is copybook-side naming; master section 9.3 confines the three spelling
corrections to target column names.

`tcatbal.txt`, one `TRAN-CAT-BAL-RECORD`: `TRANCAT-ACCT-ID` `00000000007` at 0, `TRANCAT-TYPE-CD`
`01` at 11, `TRANCAT-CD` `0001` at 13, `TRAN-CAT-BAL` `0000000000{` = `+0.00` at 17, and 22 ASCII
`'0'` of `FILLER` at 28. **It is keyed on account 7 -- the account the master does not hold.**

Assumptions: that disagreement with `acctdata.txt`'s account `00000000020` is the scenario itself and
**not** an authoring slip, so it must not be "corrected". The 17-byte composite key here is the key
the transaction *would* have used had the account existed -- account, type `01`, category `0001` all
taken from the feed record's own fields -- so it is the row that would have been updated on the path
this record never takes. `2700-UPDATE-TCATBAL` is performed only from `:440`, inside
`2000-POST-TRANSACTION`, which is reached only from `:212`; validation stopped at `:397` and the run
went to `:215` instead. The row is therefore unreachable by construction, and it is shipped for one
purpose only: so that section 3 has something to assert is byte-for-byte unchanged. Making the two
files agree would destroy the scenario, because an account master holding account 7 is the
`happy_path` precondition, not this one.

**The `FILLER` bytes differ by measurement, not by inconsistency.** Three files pad with `0x20`
SPACE; `tcatbal.txt` pads with ASCII `'0'`, `0x30`.

Assumptions: the padding byte is a measured property of the seed corpus and is not derivable from the
`PICTURE` clause, so it is copied from the seed rather than reasoned out. Master section 6.1 records
the measured byte per record and master section 6.2 records the category-balance row as one of
exactly two that contradict the general padding rule of section 3.2, with the measured byte winning.
Both tables are cited rather than reproduced, per master section 1.3 -- a second copy of a measured
table is a second thing to keep true. The practical consequence is that a fixture author who applies
the general rule uniformly, and space-pads this record's `FILLER`, produces 50 correct-length bytes
that differ from the golden in 22 positions with every field value right.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| `tcatbal.txt` is `LF`-terminated where its seed is `CRLF` | master sections 3.8, 3.9 | A stray `0x0D` absorbed into the 22-byte `'0'` `FILLER` would push the record to 51 bytes and fail the load with every field value correct |
| The account master deliberately omits the resolved account | master sections 8, 9.1 | Master section 8 permits a key to be deliberately omitted to trigger a reject and requires the README to name it; master section 9.1 names this scenario as exactly what that omission is for. Account `00000000007` is absent by design |
| The category-balance row is keyed on an account the master does not hold | master section 8 | The same omission seen from the other file. The row is unreachable and exists only for the unchanged-file assertion of section 3 |

Alternatives Considered: authoring `cardxref.txt` at the seed's 36 bytes instead of the copybook's
50. The seed row genuinely is 36 bytes -- it stops after `XREF-ACCT-ID` and omits the trailing
`FILLER X(14)` that `app/cpy/CVACT03Y.cpy` declares -- so both physical widths exist in the corpus
and either could be defended. Master section 3.10 measured both forms and **ruled for 50** with the
`FILLER` space-padded, so that the record loaded into the indexed file matches the copybook the
program was compiled against; that ruling is the tree's, not this folder's, and is cited rather than
re-argued. The 36-byte alternative is rejected here for the reason the ruling gives, with one
consequence specific to this scenario worth naming: the key this file must resolve sits at offset 0
and is 16 bytes wide, so **either** width would resolve the card correctly and the reject reason
would be 101 in both cases. The choice is therefore invisible in this scenario's outcome, which is
precisely why it has to be decided by the tree-level contract rather than by whichever width happened
to make the test pass.

Trade-offs: stripping the `CR` that `tcatbal.txt`'s seed row carries, rather than preserving it as
master section 3.8 permits a scenario to do when it says so. The asymmetry that forces a choice is
measured, not assumed: the `tcatbal` seed carries **49 `CR` bytes** across its 50 rows, while the
`dailytran`, `cardxref` and `acctdata` seeds carry **zero**. Preserving the seed's own line ending
per file would therefore make this one folder mix two conventions across four files, and the cost of
that is concrete rather than stylistic -- a `0x0D` at the end of this record is absorbed into the
22-byte `'0'` `FILLER`, pushing the row to 51 bytes and failing the load with every field value
correct, which is the failure mode master section 3.9 exists to prevent. What is given up is
byte-identity with the seed row on that one file; what is bought is a single line-ending convention
across the folder, so that the geometry check in section 5.2 has one expected answer instead of a
per-file exception. Section 9 records the strip as a line-ending normalisation so the provenance
claim stays exact.

**No business-rule field is reshaped in this folder**, measured against each file's own seed row. The
scenario is created entirely by **selecting** a non-matching seed row rather than by editing a
matching one, which section 9 attests row by row -- and which section 9 also states the other way
round, tabulating the seven fields in which the substituted account row differs from the account every
sibling scenario carries, so that the "none reshaped" claim above cannot be mistaken for a claim that
this folder's account row looks like theirs.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input data** --
the feed record has not been processed yet. Master section 8.1 requires this to be stated every time
a blank timestamp is shown, because the byte pattern is identical to the one normalisation produces
and the meaning cannot be recovered from the bytes. It is the same reason the migrated
`ledger.daily_transactions.proc_ts` column is nullable while `ledger.transactions.proc_ts` is
`NOT NULL`.

**Both 26-byte fields travel into the reject record unchanged, and neither is masked there.** `:447`
copies the entire 350-byte input image, so the reject's `[278:304]` carries
`2022-06-10 19:27:53.000000` and its `[304:330]` carries the same 26 spaces -- still meaning "not
processed". A reject record contains **no clock reading at all**, which is why `dalyrejs.expected`
can be compared byte for byte with no field normalised.

Assumptions: those 26 bytes are blank because an input fixture may not contain a wall-clock reading,
and the group `MOVE` at `:447` is what makes the constraint bite rather than merely tidy. Two
properties compound. First, determinism: a fixture carrying a timestamp would make the file's meaning
depend on when it was authored, and the field's purpose is to be empty until the job fills it --
`Z-GET-DB2-FORMAT-TIMESTAMP` at `:437` writes the processed value into `TRAN-PROC-TS` at `:438`, on
the posting path only. Second, propagation: `:447` moves the whole 350-byte group rather than
re-encoding it field by field, so **whatever occupies these 26 bytes reaches the reject image
verbatim**. Any value here -- even a plausible-looking constant -- would land in `dalyrejs.expected`
at `[304:330]` and would have to be normalised before comparison, which would forfeit the
byte-for-byte comparison the golden currently permits. The 20-space `FILLER` at `[330:350]` survives
the same way and for the same reason, which is why the input record's padding is worth getting right
even though nothing reads it.

Everything else holds by construction: every byte here is literal, there is no random identifier and
no environment-derived string, and each test provisions and tears down its own workspace, per master
section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and four target-side contracts constrain what this
folder may expect:

- **`dto/RejectReason.ACCOUNT_NOT_FOUND_ON_READ`** carries the code `101` and the description
  `ACCOUNT RECORD NOT FOUND` **verbatim**. It is a distinct constant from
  `ACCOUNT_NOT_FOUND_ON_REWRITE`, which carries code `109` and the **same description**, so the
  target keeps the two conditions section 4 distinguishes apart by code rather than by text -- and
  `isPersistedToRejectStream()` is what records that only the first reaches the stream.
- **`dto/RejectReason.terminatesValidation`** models the short-circuit of `:372-373`, which is what
  makes "reason 100 was not reported because the card resolved" a checkable property rather than an
  ordering accident.
- **`dto/PostingValidationResult.resolve`** returns on `accountMissing` before either boundary
  comparison, with a `null` projected cycle balance -- there is no projection, because `:403-405`
  never ran. The record's constructor refuses a result whose findings and projection disagree, so a
  decision claiming reason 101 **and** a projected balance is rejected outright. That refusal is the
  target-side equivalent of section 4's claim that the limit and the expiration date were never
  compared.
- **`dto/BatchRunSummary`** enforces the warn-tier biconditional in both directions, so this scenario
  must report the warn tier with one processed, one rejected and zero written.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql),
where `ledger.transaction_rejects` is created at `:554` and declares the reject contract by
composition -- `raw_record CHAR(350)` at `:556`, `reason_code SMALLINT` at `:557` and
`reason_desc VARCHAR(76)` at `:558`, with `CHECK (reason_code BETWEEN 0 AND 9999)` at `:559-560`.
That file is the authority for the schema and it is deliberately not re-derived here.

Assumptions: those four line numbers were re-measured against the file rather than carried forward.
An earlier revision of this paragraph cited lines 492 to 494, which now hold a collation rationale
and not the table at all -- the declaration moved as the harness grew. A citation is only worth its
line number if the number is checked at the revision that ships it, and a stale one is worse than a
bare table name because it sends a reviewer to real text that plausibly discusses the wrong thing.

One target-side note specific to this scenario: the harness declares the cross-reference and the
account master as separate tables with `idx_card_xref_account_id` available on the first, so a target
implementation could in principle resolve card to account with a **join** and discover the missing
account and the missing card in one query. The two reject reasons would then be one outcome. They are
not: the target keeps two lookups precisely so the two reasons stay distinguishable, and this folder
paired with `reject_100_card_missing` is what holds that separation in place.

The graded rubric 0, 4, 8 belongs to the COBOL parity suite alone (master section 7.1.6). The
warn-tier expectation here is the job's return code, not a build status.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs; the oracle
is the reference-only `tests/golden/posting/reject_101_acct_missing/` tree, read as the authority and
never written, and no golden is ever regenerated.

### 8.2 Oracle constants that are not expectations for this scenario

Master section 7.1.7's constants describe the **full 300-record seed cycle** -- 300 daily records,
262 posted, 38 rejected **all reason `0102`**, a conservation total of `77954.70`, 50 category keys
becoming 100. **None is an expectation here**, and as with `reject_100_card_missing` the `0102`
figure carries the informative part: across the unmodified seed feed, **no** record produces reason
101, because every card the seed cross-reference resolves names an account the seed master holds.
The reason is unreachable from the seeds as shipped and can only be reached by selecting a
non-matching pair, which is why this folder exists. The reject-slice constants -- card `[262:278]`,
code `[350:354]`, message `[354:430]`, `_REJ_RECLEN` 430 -- do agree with section 3's assertion and
are a cross-check rather than a derivation.

### 8.3 The paired folder

`posting/reject_100_card_missing` is the other half: the same feed record, an account master holding
the **same** account `00000000007`, and a cross-reference holding a **different card**. Read the two
together -- one makes the first lookup fail and the second unreachable, the other makes the first
succeed and the second fail. Neither on its own shows that the two reasons are distinct, because
both produce a 430-byte reject, a 350-byte verbatim image and return code 4.

Assumptions: **the feed record is the constant and the masters are the variable**, across this whole
scenario family. `../happy_path/dailytran.txt` is byte-identical to this folder's, so the single input
transaction is held fixed while only the two lookup files change -- which is what makes the reject
reason attributable to the master state rather than to anything about the transaction. That is the
property to preserve when adding a posting scenario: change a master to move the outcome, and change
the feed only when the rule under test is a property of the transaction itself, as it is for the
amount in `../boundary_exact_limit` and the date in `../boundary_expiry_equal`. Editing the feed here
would make this folder's result incomparable with the rest of the family for no gain.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived, and no business-rule field is reshaped
anywhere in it.** Every record is a published AWS CardDemo sample seed row under `app/data/ASCII/`,
selected rather than edited:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | **byte-identical** |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | 21 | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | **20** | **byte-identical** |
| `tcatbal.txt` | `app/data/ASCII/tcatbal.txt` | 7 | **byte-identical** once the `CR` is removed |

**The scenario is created by selection, not by reshaping.** The account row is row **20** of its
seed -- account `00000000020`, balance `+369.00`, limit `+3767.00` -- chosen because it is a real
seed row naming an account the cross-reference does not resolve to. Every other posting scenario in
this tree uses row 7, account `00000000007`. Swapping which seed row is copied is the entire
construction, and it is why the phrase "every business-rule field reshaped away from its seed value"
resolves to **none** for this folder.

The cross-reference row is row **21** of its seed rather than row 7 because that file is ordered by
card number, and account `00000000007` appears there under card `4859452612877065`.

**The substitution delta, stated field by field.** "Nothing reshaped" is the claim against each
file's **own** seed row, and it is the claim master section 10 item 5 asks for. It is not the whole
picture for a reviewer, because the account row was substituted **wholesale** rather than edited, so
measured against account `00000000007` -- the row every other posting scenario in this tree carries
-- seven fields differ. All seven are named here so that none has to be inferred from a diff:

| Field | Account `00000000007` (seed row 7) | This folder (seed row 20) | Business-rule relevant here |
|---|---|---|---|
| `ACCT-ID` | `00000000007` | `00000000020` | **Yes -- this is the entire scenario** |
| `ACCT-CURR-BAL` | `00000001930{` | `00000003690{` | No |
| `ACCT-CREDIT-LIMIT` | `00000020650{` | `00000037670{` | No -- never compared, see section 4 |
| `ACCT-CASH-CREDIT-LIMIT` | `00000002640{` | `00000010400{` | No |
| `ACCT-OPEN-DATE` | `2012-10-12` | `2014-02-27` | No |
| `ACCT-EXPIRAION-DATE` | `2024-12-13` | `2024-03-13` | No -- never compared, see section 4 |
| `ACCT-REISSUE-DATE` | `2024-12-13` | `2024-03-13` | No |

Assumptions: only `ACCT-ID` carries the scenario; the other six differ **as a consequence** of
substituting a whole seed row rather than as six independent authoring decisions, and none of them is
read by this job on the path this record takes. They are listed anyway because master section 10 item
5 warns that the reshaped-field clause is the one most easily skipped and the one that matters most
for audit -- a reviewer diffing this folder's account row against a sibling's sees seven differences
and, without this table, cannot tell which one is the scenario and which six are carried freight.
Stating the same thing two ways deliberately: measured against its own seed row 20 the file is
byte-identical and nothing is reshaped; measured against the sibling folders' account it differs in
seven fields, exactly one of which is load-bearing.

**For the other three files, no business-rule field is reshaped at all**, and the two differences
that do exist are conformance rather than data. `dailytran.txt` is byte-identical to its seed row.
`cardxref.txt` differs from seed row 21 only by the 14 appended `FILLER` spaces that bring it to the
copybook's declared width -- a width-conformance change, per section 5.4. `tcatbal.txt` differs from
seed row 7 only by the removed `CR` -- a line-ending normalisation, per the same section. Neither
touches a field any rule reads.

**It represents no real person and no real account.** The seeds ship with the upstream open-source
project as fabricated demonstration data, and master section 11.1 carries the tree-level attestation
this scenario inherits. Identity and primary-account-number bytes -- including both account numbers
named in this document -- are taken unchanged from the seed.

Assumptions: provenance is **attested** rather than left to be inferred, because inspection cannot
establish it. Card `4859452612877065` is sixteen digits and its Luhn check digit is **valid** -- the
weighted sum is 80, so the number is exactly what a live primary account number looks like. That is a
property of the whole corpus rather than of this one row: **all 50** primary account numbers in
`app/data/ASCII/cardxref.txt` pass the Luhn check, measured rather than assumed. So the check digit
is useless as a provenance signal in either direction, and the two nine- and eleven-digit identifiers
named above carry no check digit at all. A reviewer therefore has no way to reach a verdict from the
bytes, and silence would leave a card-shaped string in a repository with nothing recording where it
came from. The attestation supplies what inspection cannot: a named seed file and a named row, so the
claim is checkable by comparison rather than by judgement. That is also why the tables above give the
seed row for every file rather than asserting synthetic origin in the aggregate -- per-row citation is
what makes the attestation falsifiable, and an attestation that cannot be falsified is not evidence
of anything.

The two normalizations named in section 5.4 are width and line-ending conformance, **not**
business-rule field changes, so they do not qualify the "nothing reshaped" statement above.

Trade-offs: selecting seed row 20 rather than reshaping row 7's account id to something absent was
the choice, and it costs one thing worth naming. Row 20 differs from row 7 in every monetary field
and every date, so this folder's account row shares almost no bytes with its siblings' -- a reader
diffing two posting fixtures sees a large difference where the scenario's actual delta is one field,
the key. The compromise is accepted because the alternative is worse in kind rather than in degree:
overwriting an account id produces a row whose key names an account no seed row describes, so its
provenance could no longer be stated as a seed row at all, and the eleven bytes would have to be
justified as invented. Section 5.3 turns the cost into a benefit by naming the unfamiliar values as
a diagnostic signal.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string
or endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the
COBOL baseline or the parity oracle.

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**. `PostTransactionsJobParityIT` resolves each scenario under
`/fixtures/posting/`, seeds the masters from it, launches the posting job and compares the resulting
transaction master, category balances, account master and reject stream against
`tests/golden/posting/reject_101_acct_missing` -- so an edit to these bytes changes what the parity
run asserts. `BatchFixtureContractTest` additionally holds every file here to its declared geometry
and to the values that make the scenario discriminating, so a layout mistake is caught in this module
rather than surfacing later as a comparison failure.

Assumptions: the seed root is declared in the test, not inferred from the directory layout, and that
declaration is what makes these bytes load-bearing. `PostTransactionsJobParityIT` line 223 declares
`FIXTURE_ROOT = "/fixtures/posting/"`, so this folder is read by a comparison rather than kept as a
reference copy of one. The distinction is worth stating explicitly and in the strong direction: a
reader who believed these bytes drove nothing would edit them expecting no consequence, and a silent
change to a driven input is the most expensive mistake this folder admits. Verify the declaration
before trusting a claim either way -- a folder's purpose is a property of the classes that name it
and can change without any byte here changing.

Assumptions: the sibling `preflight/**` and `interest/**` families are still mirrors -- no test
declares either as a seed root -- so the tree serves two different purposes and only this one changes
what a run asserts.

Assumptions: Rule 1's fourth category name, the one its line 32 scopes to *replacing existing code*,
is deliberately absent from this document. Nothing here replaces anything: the four record files are
net-new derived copies and the COBOL baseline they derive from is untouched and still runs. Master
section 1.4 rules that category unavailable throughout this tree for exactly that reason and directs
such reasoning to `Alternatives Considered:` or `Assumptions:` instead, which is why corrections to
this document's own earlier claims -- the paragraph above is one -- are recorded under `Assumptions:`
with the contract that decides the matter cited, rather than under a category that would assert a
code replacement that never happened.

---

## 11. Why this document exists, and what actually checks it

This README is the mandatory Explainability carrier for the four record files in this directory,
required by master section 10 and by user-specified Rule 1. Section 1.2 of the master contract gives
the mechanism: a fixed-width record file cannot carry a comment of any kind, because every byte
position is meaningful and a comment on its own line is a physical row of the wrong length. Rule 1's
docstring duty for `dailytran.txt`, `cardxref.txt`, `acctdata.txt` and `tcatbal.txt` therefore
transfers here in full, which is why the four required elements -- purpose, parameters, returns and
errors -- appear as sections 1, 5, 3 and 4 rather than as a header comment in each file.

Assumptions: **no automated gate reads this file, and the two that might are both verified not to.**
`config/checkstyle/checkstyle.xml` line 215 sets `fileExtensions` to `java`, so Checkstyle never
opens Markdown. `config/rule1/rule1_gate.py` does govern Markdown for its `labels` check, but its
`_is_governed` function at line 528 returns false for any path containing
`/src/test/resources/fixtures/` -- the segment declared at its line 142 -- so every file in this
directory is outside its remit by design, the record fixtures because their `.txt` extension is
incidental and this document because it shares their path. The label form used above is nevertheless
the one that gate defines and master section 1.4 fixes: plain, plural, unemphasised, colon retained.
It is adopted here **voluntarily and for a reason** -- a reviewer auditing this repository against
Rule 1's validation gate finds every rationale by literal string search across seven languages, and a
label written in an emphasised or parenthesised variant is a rationale that search does not return.
Consistency with the ninety-odd governed siblings is what keeps one search sufficient.

Trade-offs: the consequence of being ungoverned is that **correctness here rests on construction and
review, not on a check that fails**. A lexical gate could not decide the things that actually matter
about this document in any case: whether each rationale names a real consequence rather than a
plausible-sounding one, whether every byte count matches the file it describes, and whether every
line citation still points at the statement it claims. Those were established by measurement while
this revision was written -- the four record files were read and their bytes counted, every cited
line of `app/cbl/CBTRN02C.cbl` was opened and compared against the claim made about it, the seed rows
were matched programmatically against their fixtures, and the harness table's column declarations
were located rather than assumed. A citation that has not been checked at the revision that ships it
is worse than no citation, because it sends a reviewer to real text that plausibly discusses
something else.
