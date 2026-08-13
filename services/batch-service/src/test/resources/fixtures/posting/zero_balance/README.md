# `posting/zero_balance` -- a zero opening balance, and the only create arm in the domain

> **Purpose.** Pin two things at once that only compose in this shape. First, an account whose
> current balance is exactly `+0.00` posts normally -- a zero balance is an ordinary value, not an
> uninitialised one. Second, and the reason this folder exists at all, the
> transaction-category-balance file is **empty**, so the composed key is absent and the posting
> path takes the **create** arm of the branch at `app/cbl/CBTRN02C.cbl:495-499`. It is the only
> posting scenario in this tree that does, and its golden category-balance record is the only one
> of the nine whose padding bytes are NUL rather than ASCII zeros -- a byte-level consequence of
> the create arm that section 3 traces to the statement that causes it.
>
> **Source of truth.** `app/cbl/CBTRN02C.cbl` for the behaviour and `app/jcl/POSTTRAN.jcl` for the
> dataset contract, both reference-only and both cited below by line; `app/cpy/CVTRA06Y.cpy`,
> `app/cpy/CVACT03Y.cpy`, `app/cpy/CVACT01Y.cpy` and `app/cpy/CVTRA01Y.cpy` for the record
> layouts; the seed datasets under `app/data/ASCII/` for the bytes;
> `tests/golden/posting/zero_balance/` for the expected outputs; and the tree-level
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
wrong length. `tcatbal.txt` is the extreme case in this folder -- it is zero bytes, so a single
added byte would stop it being an empty dataset at all. Master section 10 makes the artifact
mandatory rather than courteous, and the section order below is the one it fixes.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction of `+504.77` on card `4859452612877065`, against account `00000000007`
whose `ACCT-CURR-BAL` is **`+0.00`** and whose credit limit is `+2065.00`, with a
**zero-byte** `tcatbal.txt`.

Three things must hold at once:

- the record **posts**, because a zero opening balance fails no test the program makes;
- the category balance takes the **create** arm, because the read for
  `00000000007 / 01 / 0001` finds nothing;
- the account's balance moves from `+0.00` to the full transaction amount, so the arithmetic is
  visible in its simplest form -- `0.00 + 504.77` -- with no prior balance obscuring it.

Alternatives Considered: reaching the create arm by keeping a populated `tcatbal.txt` and giving
the feed record a category code no row carries -- `0002` instead of `0001`, say. Rejected because
the composed key has three parts and a mismatch in any one of them produces the same miss, so
such a fixture would not say **which** part of the key the program composed. An empty file makes
the miss unambiguous, and it additionally proves that an empty indexed dataset opens I-O
successfully rather than abending on the open -- which is a second thing that could go wrong and
which no populated fixture tests.

Assumptions: this folder's emptiness is **not** the `empty_input` semantic of master section
3.11. That semantic is about the **primary** input: `posting/empty_input` ships a zero-byte
`dailytran.txt` alongside three populated files, and it asserts that a job with no work posts
nothing. Here the primary input is fully populated and a **reference** input is empty, so the job
has work to do and does it -- the two scenarios are near-opposites and the shared word "empty"
is the only thing they have in common. Recorded so that neither folder is read as a variant of
the other.

---

## 2. The business rule, cited by program and line

Two rules meet here. The first is the ordinary posting path; the second is the branch this folder
exists for.

**The category-balance branch, `app/cbl/CBTRN02C.cbl:467-542`.**

| Step | Line | What happens for this record |
|---|---|---|
| Compose the key | `:469-471` | `XREF-ACCT-ID` from the cross-reference read, then the feed's type code and category code. **The account id comes from the cross-reference, not from the daily transaction** -- master section 7.1.5 |
| Arm the flag | `:473` | `MOVE 'N' TO WS-CREATE-TRANCAT-REC`, resetting the field declared `'N'` at `:190` |
| Read | `:474-479` | the file is empty, so the read takes `INVALID KEY`: `:476-477` DISPLAY `TCATBAL record not found for key : ... Creating.` and `:478` moves `'Y'` into the flag |
| Tolerate status 23 | `:481` | `IF TCATBALF-STATUS = '00' OR '23'` accepts the miss, so the abend guard at `:492` is not reached. **A miss is a normal condition here, not an error** |
| Select the arm | `:495-499` | the flag is `'Y'`, so `:496` performs `2700-A-CREATE-TCATBAL-REC` (`:503-524`) |
| Initialise | `:504` | `INITIALIZE TRAN-CAT-BAL-RECORD` |
| Fill the key | `:505-507` | the same three key parts, now into the record rather than the file key |
| Add | `:508` | `ADD DALYTRAN-AMT TO TRAN-CAT-BAL`, so the created row's balance is the amount |
| Write | `:510` | `WRITE`, and `:512` requires status `'00'` |

**The posting path around it**, in the same program: validation passes at `:407` (`2065.00 >=
504.77`) and `:414` (`2024-12-13 >= 2022-06-10`), so `:211-212` performs `2000-POST-TRANSACTION`,
whose three writes are `:440` the category balance, `:441` the account and `:442` the transaction
-- one unit of work, per master section 7.1.5. The account arithmetic is `:547`, adding the amount
to `ACCT-CURR-BAL`, and `:548-549`, adding it to `ACCT-CURR-CYC-CREDIT` because the amount is not
negative. `:229-230` leaves the return code at 0 because nothing was rejected.

**The two arms differ in exactly two statements**, which is worth stating precisely because it is
the reason the arms are hard to tell apart: the create arm has `INITIALIZE` and `WRITE` where the
update arm (`:526-542`) has neither an initialise nor a rewrite of an existing image -- it has
`ADD` and `REWRITE`. Both arms are **additive**; `:508` and `:527` are the same statement. Master
section 7.1.5 fixes this, and it is why `posting/happy_path` opens its category balance at
`+100.00`: with a `+0.00` opening balance the update arm would produce the same number the create
arm does, and the arms would be indistinguishable.

---

## 3. Returns -- the expected outcome

The return code is **0**, the clean tier of master section 7.1.6. Every figure is arithmetic over
this folder's bytes, confirmed against the reference-only golden corpus in
`tests/golden/posting/zero_balance/`.

**Validation.** `WS-TEMP-BAL = 0.00 - 0.00 + 504.77 = 504.77` at `:403-405`, and `2065.00 >=
504.77` at `:407`. `2024-12-13 >= 2022-06-10` at `:414`. Note that `ACCT-CURR-BAL` takes **no
part** in either test -- `:403-405` reads the two current-cycle fields and the amount, and neither
boundary test names the running balance. A zero opening balance therefore cannot affect the
verdict, which is precisely what this half of the scenario asserts.

**The two mutations, and the one creation.**

| Output | Before | Arithmetic | After | Encoded |
|---|---|---|---|---|
| `TRAN-CAT-BAL` for `00000000007 / 01 / 0001` | **absent** | created, then `0.00 + 504.77` | **`+504.77`** in a new row | `0000050477G` |
| `ACCT-CURR-BAL` | `+0.00` | `0.00 + 504.77` | **`+504.77`** | `00000050477G` |
| `ACCT-CURR-CYC-CREDIT` | `+0.00` | `0.00 + 504.77` | **`+504.77`** | `00000050477G` |
| `ACCT-CURR-CYC-DEBIT` | `+0.00` | untouched -- `:551` runs only for a negative amount | **`+0.00`** | `00000000000{` |

**The created category-balance row's padding bytes are NUL, and this is the folder's most
distinctive byte-level expectation.** `tests/golden/posting/zero_balance/tcatbal.expected` is 51
bytes -- one 50-byte record plus the trailing `LF` -- and the byte-set of its `FILLER` slice
`[28:50]` is `{0x00}`. In the other **eight** posting goldens the same slice is `{0x30}`, ASCII
`'0'`. Measured across all nine, `zero_balance` is the only one that differs, and it is the only
one that takes the create arm.

The mechanism is `INITIALIZE` at `:504`. The COBOL `INITIALIZE` statement does **not** affect
`FILLER` items, so the 22-byte `FILLER` at offset 28 keeps whatever the working-storage record
area already held -- low values -- while every named field is set. The eight update-arm goldens
carry `0x30` because their record image was **read from the file** at `:474`, so its padding is
the seed's own ASCII zeros, which `:527` and `:528` never touch.

Assumptions: this is an **additional measurement** under master section 6.1 rather than a
correction of it, and the distinction matters. Master section 6.1 records the TCATBAL `FILLER`
byte as `0x30` on the evidence of the seed's fifty rows and of the posting golden it measured, and
master section 6 opens by stating that the byte a `FILLER` carries depends on the record and, for
output records, on the job that wrote it. This folder extends that same principle one level
finer: for the category-balance record it depends on the **arm** that wrote it, because only one
of the two arms builds the record from scratch. Master section 6.2's ruling that the measured byte
wins is what makes the extension admissible, and master section 6.2 explicitly invites
re-measuring any row of the table -- the snippet in section 5.2 below does exactly that for both
arms.

**The written transaction record**, one row of 350 bytes: identifier `0000000000683580`, type
`01`, category `0001`, source `POS TERM`, amount `+504.77`, card `4859452612877065`,
`TRAN-ORIG-TS` = `2022-06-10 19:27:53.000000` copied unchanged by `:436`, `TRAN-PROC-TS` from the
clock and therefore masked, the trailing `FILLER` at offset 330 carrying **20 NUL bytes** per
master section 6.1, and `TRAN-DESC` **space**-padded per master section 6.3.

**The reject stream is empty but the dataset exists.** `app/jcl/POSTTRAN.jcl:34-38` allocates
`DALYREJS(+1)` unconditionally at `LRECL=430` and `:293` opens it OUTPUT before the program can
know whether anything will be rejected, so the golden records a zero-byte `dalyrejs.expected`
rather than a missing file.

**Counters.** One processed, zero rejected, both emitted at `:227-228`.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**The empty category-balance file must not abend the run.** This is the negative that the folder's
shape makes reachable, and the guard that prevents it is specific: `:481` accepts `'23'` alongside
`'00'` from the read at `:474`, so the miss falls through to the arm selection instead of reaching
`9999-ABEND-PROGRAM` at `:492`. An implementation treating a missing category row as an I/O error
would abend here while posting every other scenario correctly.

**The update arm must not be taken.** `2700-B-UPDATE-TCATBAL-REC` (`:526-542`) would `REWRITE` a
record that was never read, and there is no record image to rewrite. The discriminator is the flag
at `:473`, `:478` and `:495`, and nothing else.

**No reject reason is reachable.** The card is in the cross-reference, so 100 cannot be set at
`:385`; the account exists, so 101 cannot be set at `:397`; `504.77` is far inside the `2065.00`
limit, so 102 cannot be set at `:410`; the originating date is two and a half years before
expiration, so 103 cannot be set at `:417`. Master section 7.1.4's overwrite of 102 by 103 has
nothing to overwrite.

**A zero balance must not be read as a missing account.** The account row is present, the read at
`:395` succeeds and takes `NOT INVALID KEY` at `:400`, and reason 101 is therefore unreachable. A
zero **value** and an absent **row** are different conditions with different reject outcomes, and
`posting/reject_101_acct_missing` is the folder for the second.

**Code 109 is unreachable and must not be expected.** `:556` assigns it inside the `INVALID KEY`
branch of the account `REWRITE` at `:554-559`, on the posting path and after validation, writing
no reject record; master section 7.1.1 fixes the persisted domain as exactly
`{100, 101, 102, 103}`.

**No abend occurs.** Every reachable `9999-ABEND-PROGRAM` site is status-guarded: the six opens
(`:250`, `:268`, `:287`, `:305`, `:323`, `:341`), the feed read (`:363-366`), the category-balance
read (`:492`) and **write** (`:523`, the create arm's site rather than the update arm's `:541`),
the transaction write (`:577`) and the six closes (`:596`, `:614`, `:633`, `:651`, `:669`,
`:688`).

---

## 5. Parameters -- the files in this folder and their byte geometry

### 5.1 Inventory

| File | Copybook | RECLN | DDNAME / `ASSIGN` | Organization | Key |
|---|---|---:|---|---|---|
| `dailytran.txt` | `app/cpy/CVTRA06Y.cpy` | 350 | `DALYTRAN` | SEQUENTIAL | -- |
| `cardxref.txt` | `app/cpy/CVACT03Y.cpy` | 50 | `XREFFILE` | INDEXED | `XREF-CARD-NUM`, offset 0, length 16 |
| `acctdata.txt` | `app/cpy/CVACT01Y.cpy` | 300 | `ACCTFILE` | INDEXED | `ACCT-ID`, offset 0, length 11 |
| `tcatbal.txt` | `app/cpy/CVTRA01Y.cpy` | **0 bytes -- no record** | `TCATBALF` | INDEXED | `TRAN-CAT-KEY`, offset 0, length 17 |
| `README.md` | -- | -- | -- | -- | this file |

`ASSIGN` names and organizations are declared at `app/cbl/CBTRN02C.cbl:29-61`; the record lengths
are the summed widths of master section 5.1, never a `RECLN` banner. The category-balance row
above states the width and the emptiness separately, because the declared width still governs any
row the job **writes** into that dataset. The cross-reference is read **by card number** at offset
0 -- master section 4.3, since `app/jcl/POSTTRAN.jcl:32-33` mounts the base cluster only.

### 5.2 On-disk sizes, line endings, and the zero-byte reference file

| File | Size | Records | Bytes per record | `CR` bytes | Trailing newline |
|---|---:|---:|---:|---:|---|
| `dailytran.txt` | **351** | 1 | 350 | 0 | exactly one `LF` |
| `cardxref.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |
| `acctdata.txt` | **301** | 1 | 300 | 0 | exactly one `LF` |
| `tcatbal.txt` | **0** | 0 | -- | 0 | none, and none is possible |

Alternatives Considered: writing `tcatbal.txt` as a single `LF` rather than as a zero-byte file.
Rejected because a lone newline is a **one-byte record**, not an empty dataset. Master section 1.2
records that the loaders reject any physical row whose length is not exactly the record length, do
not pad a short row and do not silently drop a blank line. A more permissive reader would be worse
rather than better: it would admit the newline as a malformed 1-byte category-balance row, whose
key would then be eleven blanks, which either fails the load or -- worse -- loads and makes the
read at `:474` **hit nothing while the file is non-empty**, so the create arm would still be taken
and the fixture would appear to pass while testing something else.

```bash
# WHAT: assert the byte geometry, and re-measure the category-balance FILLER byte on both arms --
#       this scenario's create-arm golden against a sibling update-arm golden. Run from this
#       directory.
# WHY : Assumptions: the padding byte is a measured property, not a consequence of the PICTURE
#       clause (master section 6.2), and this folder is the one place in the domain where the two
#       arms of one branch produce two different bytes in the same slice of the same record type.
#       Printing both byte-sets side by side is what turns section 3's claim into something a
#       reader can check in one command instead of taking on trust; a single-element set is the
#       proof that the whole slice is uniform.
python3 - <<'PY'
EXPECTED = {"dailytran.txt": (351, 350), "cardxref.txt": (51, 50),
            "acctdata.txt": (301, 300), "tcatbal.txt": (0, None)}
for name, (size, width) in EXPECTED.items():
    raw = open(name, "rb").read()
    rows = [r for r in raw.split(b"\n") if r]
    widths = sorted({len(r) for r in rows})
    print(name, "size", len(raw), "expected", size, "| rows", len(rows), widths,
          "| CR", raw.count(b"\r"),
          "| OK" if len(raw) == size and raw.count(b"\r") == 0
          and widths == ([width] if width else []) else "| MISMATCH")
ROOT = "../../../../../../../.."
for arm, golden in (("create", "zero_balance"), ("update", "happy_path")):
    raw = open(f"{ROOT}/tests/golden/posting/{golden}/tcatbal.expected", "rb").read()
    record = raw.rstrip(b"\n")
    print(arm, "arm golden", golden, "| record", len(record),
          "| FILLER[28:50] byte-set", sorted(set(record[28:50])))
PY
```

### 5.3 Field values, as observed

Offsets are zero-based; money is signed zoned with the sign folded into the last byte and no byte
for the decimal point, per master sections 3.3 and 3.7.

`dailytran.txt`, one `DALYTRAN-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DALYTRAN-ID` | 0 | 16 | `0000000000683580` | -- |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `01` | the key part `:470` moves |
| `DALYTRAN-CAT-CD` | 18 | 4 | `0001` | the key part `:471` moves; unsigned, plain digits |
| `DALYTRAN-SOURCE` | 22 | 10 | `POS TERM` + 2 spaces | -- |
| `DALYTRAN-DESC` | 32 | 100 | `Purchase at Abshire-Lowe` + 76 spaces | -- |
| `DALYTRAN-AMT` | 132 | 11 | `0000005047G` | `+504.77` |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | -- |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | -- |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | -- |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | -- |
| `DALYTRAN-CARD-NUM` | 262 | 16 | `4859452612877065` | -- |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `2022-06-10 19:27:53.000000` | inside expiration by design |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

`cardxref.txt`: `XREF-CARD-NUM` `4859452612877065` at 0, `XREF-CUST-ID` `000000007` at 16,
`XREF-ACCT-ID` `00000000007` at 25 -- the value `:469` moves into the category key -- and `FILLER`
14 spaces at 36.

`acctdata.txt`, one `ACCOUNT-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | `00000000007` | account 7 |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | active -- and unread by this job |
| `ACCT-CURR-BAL` | 12 | 12 | `00000000000{` | **`+0.00`** -- reshaped, section 9 |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000020650{` | `+2065.00` |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000002640{` | `+264.00` -- unread by this job |
| `ACCT-OPEN-DATE` | 48 | 10 | `2012-10-12` | -- |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `2024-12-13` | the date `:414` compares |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2024-12-13` | -- |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` | `+0.00` |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` | `+0.00` |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | -- |
| `ACCT-GROUP-ID` | 112 | 10 | 10 spaces | blank -- unread by this job |
| `FILLER` | 122 | 178 | 178 spaces | -- |

**Three of these fields now read `+0.00` and only one of them was reshaped.** `ACCT-CURR-BAL` at
offset 12 is this scenario's own change; the two current-cycle fields at 78 and 90 are the seed's
own zeros, as they are in every other posting scenario. The distinction matters because the
boundary test at `:403-405` reads the two that were **not** reshaped, so the projected-balance
identity of master section 7.1.3 still holds here unchanged.

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` line 11 spells it, preserved
verbatim because this is copybook-side naming; master section 9.3 confines the three spelling
corrections to target column names.

`tcatbal.txt` holds **no record**, so it has no field table. Its declared layout still applies to
the row the job writes: `TRANCAT-ACCT-ID` at 0, `TRANCAT-TYPE-CD` at 11, `TRANCAT-CD` at 13,
`TRAN-CAT-BAL` at 17 and the 22-byte `FILLER` at 28.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `tcatbal.txt` is zero bytes while the primary input is populated | master section 3.11 | Not the `empty_input` semantic, which concerns the primary input. Emptying the category-balance file is the only unambiguous way to reach the create arm, per section 1 |
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| The created row's `FILLER` is NUL where the seed's and the other eight goldens' is ASCII `'0'` | master sections 6.1, 6.2 | An additional measurement under section 6's own per-writer principle, traced to `INITIALIZE` at `:504` in section 3. Not a contradiction: the measured byte wins |
| `ACCT-CURR-BAL` carries `+0.00` where the seed row carries `+193.00` | master section 11.1 | A business-rule field reshaped deliberately -- it is half the scenario. Attested in section 9 |

There is **no** category-balance line-ending normalization to declare in this folder, because
there is no category-balance row: the `CRLF`-to-`LF` conversion the other posting scenarios
perform on their seed row has nothing to act on here.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input data** --
the feed record has not been processed yet. Master section 8.1 requires this to be stated every
time a blank timestamp is shown, because the byte pattern is identical to the one normalisation
produces and the meaning cannot be recovered from the bytes. It is the same reason the migrated
`ledger.daily_transactions.proc_ts` column is nullable while `ledger.transactions.proc_ts` is
`NOT NULL`.

`DALYTRAN-ORIG-TS` is the opposite case: real, deterministic input data, copied unchanged into the
output by `:436`, and therefore **asserted** rather than masked. In the golden's output record the
*processing* stamp appears as 26 spaces, and there those spaces are the product of normalisation
-- the other of the two meanings.

The empty file introduces no non-determinism of its own. A zero-byte dataset is the same zero
bytes on every run, and the created row's contents are a function of the key and the amount alone.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and this folder is the one that exercises the
create side of its category-balance collaborator:

- **`service/CategoryBalanceService`** reports its arm explicitly through
  `Outcome(Arm, balance)`, and for this scenario the arm is **create** with a resulting balance of
  `504.77`. The service expresses the branch as an upsert -- `INSERT ... ON CONFLICT DO UPDATE SET
  balance = balance + :amt` with the insert value `:amt` -- so both arms are additive exactly as
  `:508` and `:527` are, and the reported arm is what makes them distinguishable to an assertion
  rather than only to a byte comparison.
- **`service/PostingValidationService.validate`** returns
  `dto/PostingValidationResult.accepted(504.77)`; the zero opening balance does not enter the
  projection, for the reason section 3 gives.
- **`dto/BatchRunSummary`** enforces the warn-tier biconditional in both directions, so this
  scenario must report tier 0 with one processed and zero rejected.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql):
`ledger.daily_transactions`, `account.card_xref` and `account.accounts` as inputs,
`ledger.transaction_category_balances` starting **empty** and gaining one row,
`ledger.transactions` gaining one row, and `ledger.transaction_rejects` staying empty. A scenario
owns only its own rows and never seeds another service's schema, per master section 11.3 -- which
is what makes an empty starting table expressible at all: this folder simply contributes no row to
it.

The graded rubric 0, 4, 8 belongs to the COBOL parity suite alone (master section 7.1.6). The
tier-0 expectation here is the job's return code, not a build status.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs; the
oracle is the reference-only `tests/golden/posting/zero_balance/` tree, read as the authority and
never written, and no golden is ever regenerated. The snippet in section 5.2 reads that tree and
`tests/golden/posting/happy_path/`, both read-only.

### 8.2 Oracle constants that are not expectations for this scenario

Master section 7.1.7's constants describe the **full 300-record seed cycle**. One of them is worth
naming here rather than dismissing wholesale: `_EXPECTED_TCAT_INIT_KEYS = 50` becoming
`_EXPECTED_TCAT_FINAL_KEYS = 100` means that over the seed cycle the create and update partitions
are each exactly fifty, so **both arms are genuinely exercised at scale** and this folder is the
small, isolated case of the arm that creates. The counts themselves are not expectations here: one
key exists at the end of this scenario, not a hundred. The two loader-geometry constants do agree
-- `_ACCT = (300, 11)` and `_TCAT = (50, 17)` match this folder's account record and the category
key the job writes.

### 8.3 The complementary folder

`posting/happy_path` is the update arm: the same card, the same account and the same composed key,
with a **populated** category-balance row opening at `+100.00`. Read the two together -- master
section 7.1.5 requires both partitions of the branch to be non-empty across the domain and
separately tested, and these two folders are how that requirement is met.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived.** Every record traces to a published AWS
CardDemo sample seed row under `app/data/ASCII/`:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | **byte-identical** |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | 21 | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 7 | one business-rule field reshaped, below |
| `tcatbal.txt` | -- | -- | no seed row: the file is deliberately empty |

The cross-reference row is row **21** of its seed rather than row 7 because that file is ordered by
card number, and account `00000000007` appears there under card `4859452612877065`.

**It represents no real person and no real account.** The seeds ship with the upstream open-source
project as fabricated demonstration data, and master section 11.1 carries the tree-level
attestation this scenario inherits. Identity and primary-account-number bytes are taken unchanged
from the seed.

**Exactly one business-rule field is reshaped away from its seed value.** In `acctdata.txt`,
`ACCT-CURR-BAL` at `[12:24]` is `00000000000{` = `+0.00`, where seed row 7 carries `00000001930{`
= `+193.00`. Measured, the difference is the **three bytes** at offsets 19 to 21, `193` becoming
`000`; the overpunch byte `{` is unchanged, because both values are positive. No other field in
any file here departs from its seed value: both credit limits, all three dates, both cycle
amounts, the ZIP, the blank group id and the active status are the seed's own, and the daily
transaction and cross-reference rows are the seed's own throughout.

**The empty `tcatbal.txt` is deliberate rather than a missing file, and it is not a reshaping.**
It contributes no seed row because it contains no row. A reviewer finding a zero-length file in a
fixture folder is right to suspect an authoring omission, so its emptiness is recorded here and in
sections 1, 5.1, 5.2 and 5.4: the file is meant to be exactly zero bytes, and that emptiness is
half of the condition under test.

Trade-offs: reshaping the account balance to zero costs this folder byte-identity with its seed row
and buys an arithmetic that is checkable by eye -- `0.00 + 504.77 = 504.77` appears in three
places in section 3's table, and any drift in the amount shows up in all three at once. Leaving the
balance at `+193.00` would have made the folder a create-arm test only, with the zero-balance half
of its name unearned. The alternative of a separate fifth posting scenario for the zero balance
was rejected: the two conditions are independent, they compose without interacting -- the balance
takes no part in validation and the empty file takes no part in the account arithmetic -- and a
tenth scenario would add a folder whose only new information was one field value.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string
or endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the
COBOL baseline or the parity oracle.

---

*This README is the mandatory Explainability carrier for the four record files in this directory,
required by master section 10 and by user-specified Rule 1. `config/rule1/rule1_gate.py` decides
the form of the rationale labels above, repository-wide and including Markdown, which is why they
are written plain rather than emphasised. `config/checkstyle/checkstyle.xml` limits its audit set
to `java`, so no linter reads this prose. Whether each rationale names a real consequence, and
whether every number and line citation is true, are review obligations no lexical gate can
decide.*

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**. `PostTransactionsJobParityIT` resolves each scenario under
`/fixtures/posting/`, seeds the masters from it, launches the posting job and compares the resulting
transaction master, category balances, account master and reject stream against
`tests/golden/posting/zero_balance` -- so an edit to these bytes changes what the parity run asserts.
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
