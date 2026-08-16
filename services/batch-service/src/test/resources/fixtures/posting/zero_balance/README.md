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
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:`,
> `Trade-offs:` and, once in section 10, `Refactoring Rationale:` -- plain, plural, colon retained,
> no emphasis markup, per `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. All four are
> permitted forms. `config/rule1/rule1_gate.py` is the mechanism that decides the spelling
> repository-wide, but it excludes every path containing `/src/test/resources/fixtures/`, so it does
> not decide it for this file -- the same spelling is used so that one literal search finds every
> rationale in the repository in a single pass. This
> whole file is pure ASCII for the reason that section gives, and the constraint binds the labels
> most of all: a non-ASCII byte inside one is invisible on screen and defeats the literal search the
> standard relies on.

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

Alternatives Considered: giving this folder a category-balance row that is **present but carries a
zero balance**, which is the reading the folder's name most invites and the change a maintainer is
most likely to propose. Rejected because a present row is **found**: the read at `:474` succeeds,
`:478` never runs, the flag stays `'N'` from `:473`, and `:498` takes the update arm at `:526`. The
scenario would then assert nothing whatever about `:503`, while still producing `+504.77` and still
looking correct, because both arms are additive and `0.00 + 504.77` is the same sum either way. That
shape is not hypothetical and is not missing from the domain -- `../boundary_exact_limit` already
ships exactly it, a row under this same composed key `00000000007 / 01 / 0001` opening at `+0.00`
with ASCII-zero padding -- so authoring it here a second time would delete the domain's only
create-arm coverage and leave a duplicate in its place. The distinction is therefore between an
absent **row** and a zero **value**, which is the same distinction section 4 draws for the account.

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

**The two arms differ in exactly three things, and share the statement that does the arithmetic.**
Stating it precisely matters, because the shared statement is why the arms are hard to tell apart
from a balance alone. The create arm (`:503-524`) has `INITIALIZE` at `:504`, the three key moves
at `:505-507` and `WRITE` at `:510`; the update arm (`:526-542`) has none of those five statements
and reaches the file with `REWRITE` at `:528` instead. What the two share is the addition: `:508`
and `:527` are the same statement against the same field, so **both arms are additive**. Master
section 7.1.5 fixes this.

It is also why `posting/happy_path` opens its category balance at `+100.00` rather than at zero:
with a `+0.00` opening row the update arm would compute `0.00 + 504.77` and land on precisely the
number the create arm lands on, so the two arms would be indistinguishable by balance and the
domain would have no evidence which one ran. The arms are separated here by the presence of the
row, and in the golden by the padding byte of section 3 -- never by the balance.

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
| `TRAN-CAT-BAL` for `00000000007 / 01 / 0001` | **absent** | created, then `0.00 + 504.77` | **`+504.77`** in a new row | `0000005047G` |
| `ACCT-CURR-BAL` | `+0.00` | `0.00 + 504.77` | **`+504.77`** | `00000005047G` |
| `ACCT-CURR-CYC-CREDIT` | `+0.00` | `0.00 + 504.77` | **`+504.77`** | `00000005047G` |
| `ACCT-CURR-CYC-DEBIT` | `+0.00` | untouched -- `:551` runs only for a negative amount | **`+0.00`** | `00000000000{` |

**The same amount appears above in two widths, and the pair is the cheapest available check on
this table.** `TRAN-CAT-BAL` is `S9(09)V99`, so eleven bytes; the three account money fields are
`S9(10)V99`, so twelve. `+504.77` is therefore `0000005047G` in the category row and
`00000005047G` in the account row -- the same digits behind one further leading zero, with the
identical `G` overpunch carrying `+7` in the final position, per master sections 3.3 and 3.7.
Because a zero opening balance makes every result equal the transaction amount, the eleven-byte
form above must be **byte-identical to `DALYTRAN-AMT` in section 5.3**, which is the one
comparison that catches a mistranscribed digit here without opening the golden.

Assumptions: the encodings above are quoted from the golden records rather than composed by hand
-- `tests/golden/posting/zero_balance/tcatbal.expected` slice `[17:28]` and
`acctdat.expected` slices `[12:24]`, `[78:90]` and `[90:102]`. A hand-composed zoned literal is
the most error-prone thing this document contains, because a transposed digit still reads as a
plausible balance and still carries the correct sign byte, so it survives inspection and fails
only at comparison. The snippet in section 5.2 prints these four slices for exactly that reason.

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

Assumptions: this is **not** a local finding and must not be read as one. Master section 6.1
carries TCATBAL as **two** rows rather than one -- "input and rewritten output" at `0x30`, and
"created output" at `0x00` -- and cites this folder's own
`tests/golden/posting/zero_balance/tcatbal.expected` as the evidence for the second, naming it the
one tree whose input holds no row for the key. Master section 6.2.1 then fixes the general rule the
two rows express: the byte belongs to the **arm** that wrote the record, not to the record type,
because only one of the two arms builds the image from scratch. That section requires a scenario
README to say which arm produced the category row it expects, which is what this section 3 does,
and it warns that applying the `0x30` row to a created expectation differs from the golden in
twenty-two bytes while the key and the balance both read correctly. This document therefore cites
that ruling rather than restating it (master section 1.3), and the snippet in section 5.2 below
re-measures both arms so the claim is checkable in one command.

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
# WHAT: assert the byte geometry, print the four money slices section 3 quotes, and re-measure the
#       category-balance FILLER byte on both arms -- this scenario's create-arm golden against a
#       sibling update-arm golden. Run from this directory.
# WHY : Assumptions: the padding byte is a measured property, not a consequence of the PICTURE
#       clause (master section 6.2), and this folder is the one place in the domain where the two
#       arms of one branch produce two different bytes in the same slice of the same record type.
#       Printing both byte-sets side by side is what turns section 3's claim into something a
#       reader can check in one command instead of taking on trust; a single-element set is the
#       proof that the whole slice is uniform.
# WHY : Trade-offs: the money slices are printed as raw bytes and compared to the input amount
#       rather than decoded to a number. Decoding would need the overpunch alphabet inlined here,
#       which master section 1.3 forbids duplicating; comparing the created balance against
#       DALYTRAN-AMT byte for byte needs no alphabet and still catches a transposed digit, which is
#       the failure this scenario's zero opening balance makes detectable at all.
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
amount = open("dailytran.txt", "rb").read().rstrip(b"\n")[132:143]
balance = open(f"{ROOT}/tests/golden/posting/zero_balance/tcatbal.expected", "rb").read()
account = open(f"{ROOT}/tests/golden/posting/zero_balance/acctdat.expected", "rb").read()
print("DALYTRAN-AMT[132:143]      ", amount)
print("TRAN-CAT-BAL[17:28]        ", balance[17:28],
      "| equals the amount:", balance[17:28] == amount)
for label, lo, hi in (("ACCT-CURR-BAL       [12:24]", 12, 24),
                      ("ACCT-CURR-CYC-CREDIT[78:90]", 78, 90),
                      ("ACCT-CURR-CYC-DEBIT [90:102]", 90, 102)):
    print(label, account[lo:hi])
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

Assumptions: this one 50-byte row carries **two** independent responsibilities, and a reader who
sees only the first will not understand why the file is required at all. It resolves the card, so
reject 100 cannot fire; and its `XREF-ACCT-ID` at offset 25 is the account id the composed
category-balance key is built from, because `:469` moves `XREF-ACCT-ID` -- **not** a field of the
feed record. The daily transaction layout of `app/cpy/CVTRA06Y.cpy` contains no account id at any
offset, so the cross-reference is the only source for that key part, and deleting or altering this
row would change the created row's key rather than merely provoking a reject. The same value
therefore appears twice by necessity: at offset 25 here, and at offset 0 of `acctdata.txt`.

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

Assumptions: all three are written `00000000000{` and **not** as twelve blanks, and the difference
is not cosmetic. A signed zoned field folds its sign into the final byte, so the twelfth position
must hold a signed digit rather than a digit or a space; `{` is the `+0` overpunch, which makes
these fields **positive zero** -- a real, decodable value. Twelve spaces would not be a valid zoned
number at all: the decoder that reads every record under its declared layout would reject the row,
and a decoder lenient enough to accept it would have to invent a value the bytes do not carry.
This is the one place where "the field is empty" and "the field is zero" must not be conflated,
because `:403-405` performs arithmetic on two of these three fields and arithmetic on a blank is
undefined. The overpunch alphabet itself is master section 3.3 and is not reproduced here.

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
| The created row's `FILLER` is NUL where the seed's and the other eight goldens' is ASCII `'0'` | master sections 6.1, 6.2.1 | Not a departure at all once the right row is read: section 6.1 carries a separate "created output" row at `0x00` and section 6.2.1 makes the byte a property of the writing arm. Listed here because a reader checking only the `0x30` row would score it as one, and traced to `INITIALIZE` at `:504` in section 3 |
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

Assumptions: the input `DALYTRAN-PROC-TS` is left blank rather than carrying a plausible stamp
because the program **overwrites** it unconditionally -- `:437-438` calls
`Z-GET-DB2-FORMAT-TIMESTAMP` and moves the result into `TRAN-PROC-TS`, so the field is generated at
run time and any value authored here would be discarded on the way through while still varying
between runs if it were ever compared. Blank is therefore the only value that cannot mislead: it
decodes to SQL `NULL` in the nullable `ledger.daily_transactions.proc_ts` column, which is the
target-side statement that this feed row has not been processed yet. `TRAN-ORIG-TS` takes the
opposite treatment for the opposite reason -- `:436` is a plain copy, so it is a function of the
input alone and masking it would discard a real assertion.

The empty file introduces no non-determinism of its own. A zero-byte dataset is the same zero
bytes on every run, and the created row's contents are a function of the key and the amount alone.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and this folder is the one that exercises the
create side of its category-balance collaborator:

- **`service/CategoryBalanceService`** reports its arm explicitly through
  `Outcome(Arm, balance)`, and for this scenario the arm is **create** (`Arm.CREATED`) with a
  resulting balance of `504.77`. The service resolves the branch **in Java and not in the database**:
  it reads the three-part key with `findByIdIs`, and on an empty result takes the create arm -- the
  target form of `2700-A-CREATE-TCATBAL-REC` at `app/cbl/CBTRN02C.cbl:503` -- while on a present row
  it takes the update arm, `2700-B-UPDATE-TCATBAL-REC` at `:526`. Both arms are additive exactly as
  `:508` and `:527` are, and each ends in its own `save`, so the reported arm is what makes them
  distinguishable to an assertion rather than only to a byte comparison.

  Assumptions: this bullet previously described the branch as an upsert, `INSERT ... ON CONFLICT DO
  UPDATE SET balance = balance + :amt`, and that is the one implementation the class deliberately does
  **not** use -- its own Trade-offs note records why, and the reason bears directly on this folder. An
  upsert resolves the branch inside the database, where neither a caller nor a test can observe which
  way it went, so this scenario -- whose whole purpose is to exercise the CREATE side -- would have
  nothing to assert beyond a byte comparison that the update arm would satisfy identically. The
  description mattered because it would have sent a reader looking for a statement the class does not
  contain, and would have suggested that the two arms are the same code path when the module carries
  two write paths and one extra read precisely so that they are not.
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

### 8.3 The complementary folders

Two siblings share this folder's composed key `00000000007 / 01 / 0001`, and reading the three
together is what makes the branch legible:

| Folder | Category-balance input | Arm taken | What it settles |
|---|---|---|---|
| **this folder** | zero-byte file, no row | `2700-A-CREATE`, `:503` | that a missing row is created rather than fatal |
| [`../happy_path`](../happy_path) | one row opening at `+100.00` | `2700-B-UPDATE`, `:526` | that an existing row is added to, not replaced |
| [`../boundary_exact_limit`](../boundary_exact_limit) | one row opening at `+0.00` | `2700-B-UPDATE`, `:526` | that a zero **value** is still a found **row** |

Master section 7.1.5 requires both partitions of the branch to be non-empty across the domain and
separately tested, and the first two rows are how that requirement is met. The third row is the
one that keeps this folder honest: it is the reason a zero balance alone does not reach the create
arm, and the reason this folder's `tcatbal.txt` has to be empty rather than merely zeroed.

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
compliance in this path is therefore **review-based** rather than mechanical. Whether each rationale names a real consequence, and
whether every number and line citation is true, are review obligations no lexical gate can
decide.*

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**, and **three** classes read it. `PostTransactionsJobParityIT`
resolves each scenario under `/fixtures/posting/`, seeds the masters from it, launches the posting
job and compares the resulting transaction master, category balances, account master and reject
stream against `tests/golden/posting/zero_balance`. `PostTransactionsJobTest` resolves the same
scenario under `fixtures/posting/` at the unit tier and seeds all four relations from the same
bytes, so both tiers read this folder -- an edit to these bytes changes what BOTH runs assert.
`BatchFixtureContractTest` additionally holds every file here to its declared geometry, to the
values that make the scenario discriminating and to its committed SHA-256 per master section 11.5,
so a layout mistake or an unintended byte change is caught in this module rather than surfacing
later as a comparison failure.

Refactoring Rationale: this section stated that no test in this module opened the folder and that
the corpus was a reference mirror. That was accurate when it was written and is no longer -- the
parity class now seeds from here. It is corrected rather than deleted, because a reader who had been
told these bytes drive nothing would edit them expecting no consequence, which is the most expensive
mistake this folder admits. The correction is recorded under this category rather than dropped
because Rule 1 scopes it to replacing an existing approach and saying what was wrong with it, and
what was wrong here was a claim about consequence, not a wording. Master section 1.5 listed this
domain among the scenarios no job in this module opens, so for a period the two documents disagreed
and the measured consumer was the tie-breaker: `PostTransactionsJobParityIT` names
`/fixtures/posting/` as its fixture root and this folder among its committed scenarios. That section
has since been remeasured against the consuming classes and now agrees, so the disagreement is
closed and the tie-breaker is no longer needed.

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
