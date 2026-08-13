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
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. This whole file is pure ASCII for
> the reason that section gives.

**This README is the mandatory Explainability carrier for the four record files beside it.** Master
section 1.2 records why: a fixed-width record file cannot carry a comment of any kind, because every
byte position is meaningful and a comment on its own line is a physical row of the wrong length.
Master section 10 makes the artifact mandatory rather than courteous, and the section order below is
the one it fixes.

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

`ACCOUNT RECORD NOT FOUND` is 24 characters, so 52 spaces follow -- the padding `MOVE` supplies at
`:398`, per the mechanism master section 6.3 records for the trailer description.

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
`'0'` of `FILLER` at 28. **It is keyed on account 7 -- the account the master does not hold.** That
inconsistency between the two files is the scenario, not an authoring slip: the category-balance row
is never reached, because `2700-UPDATE-TCATBAL` is performed only from the posting path, and it is
present so that section 3 can assert the file is unchanged.

**The `FILLER` bytes differ by measurement, not by inconsistency.** Three files pad with `0x20`
SPACE; `tcatbal.txt` pads with ASCII `'0'`, `0x30`. Both are the bytes master section 6.1 measures,
and master section 6.2 records the category-balance row as one of the two contradicting the general
rule of section 3.2, with the measured byte winning.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| `tcatbal.txt` is `LF`-terminated where its seed is `CRLF` | master sections 3.8, 3.9 | A stray `0x0D` absorbed into the 22-byte `'0'` `FILLER` would push the record to 51 bytes and fail the load with every field value correct |
| The account master deliberately omits the resolved account | master sections 8, 9.1 | Master section 8 permits a key to be deliberately omitted to trigger a reject and requires the README to name it; master section 9.1 names this scenario as exactly what that omission is for. Account `00000000007` is absent by design |
| The category-balance row is keyed on an account the master does not hold | master section 8 | The same omission seen from the other file. The row is unreachable and exists only for the unchanged-file assertion of section 3 |

**No business-rule field is reshaped in this folder.** The scenario is created entirely by
**selecting** a non-matching seed row, which section 9 attests row by row.

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
where lines 492 to 494 declare the reject contract by composition -- `raw_record CHAR(350)`,
`reason_code SMALLINT`, `reason_desc VARCHAR(76)`. That file is the authority for the schema and it
is deliberately not re-derived here.

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

**It represents no real person and no real account.** The seeds ship with the upstream open-source
project as fabricated demonstration data, and master section 11.1 carries the tree-level attestation
this scenario inherits. Identity and primary-account-number bytes -- including both account numbers
named in this document -- are taken unchanged from the seed.

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
`tests/golden/posting/reject_101_acct_missing` -- so an edit to these bytes changes what the parity run asserts.
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
