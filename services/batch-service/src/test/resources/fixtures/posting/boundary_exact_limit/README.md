# `posting/boundary_exact_limit` -- exactly on the credit limit, and it posts

> **Purpose.** Pin the **inclusive** side of the credit-limit guard: a transaction whose
> projected cycle balance lands *exactly* on the account's credit limit **posts**, because the
> comparison is `>=` and not `>`. This folder is one half of a two-fixture pair -- the other,
> [`../reject_102_overlimit`](../reject_102_overlimit/), is the same account and the same card
> one cent higher -- and the pair is the only construction that pins an inclusive comparison,
> because either fixture on its own is consistent with both readings of the operator.
>
> **Source of truth.** `app/cbl/CBTRN02C.cbl` for the behaviour and `app/jcl/POSTTRAN.jcl` for
> the dataset contract, both reference-only and both cited below by line; `app/cpy/CVTRA06Y.cpy`,
> `app/cpy/CVACT03Y.cpy`, `app/cpy/CVACT01Y.cpy` and `app/cpy/CVTRA01Y.cpy` for the record
> layouts; the seed datasets under `app/data/ASCII/` for the bytes; `tests/golden/posting/`
> `boundary_exact_limit/` for the expected outputs; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document cites **by
> section** rather than restating (master section 1.3, the two-tier rule). Each master section is
> cited with its title on first use, so a citation can be checked without counting headings.
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. This file is **pure ASCII** for
> the reason that section gives: the label is found by literal string search before it is read by
> a person, and a lookalike character defeats the search while looking correct on screen.

**This README is the mandatory Explainability carrier for the four record files beside it.**
Master section 1.2 (why this contract is a README) records why there is no alternative: a
fixed-width record file cannot carry a comment of any kind, not even a header line, because every
byte position is data and a comment occupying its own line is a physical row of the wrong length.
Master section 10 (the per-scenario README mandate) makes the artifact **mandatory** rather than
courteous and fixes the order of the five sections it requires; sections 4, 6, 7, 8 and 10 below
are additions the mandate does not ask for.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction of **`+2065.00`** against account `00000000007`, whose credit limit is
**`+2065.00`** -- the same number to the cent. Both current-cycle fields are `+0.00`, so the
projected balance the program forms *is* the transaction amount, and the comparison at the heart
of the scenario is `2065.00 >= 2065.00`.

The expectation is that the record **posts**. Everything else in the folder exists to make that
outcome legible: the category-balance row is present so the balance arithmetic has a visible
addend, and the originating date sits two and a half years inside the expiration date so that the
*other* inclusive boundary cannot interfere.

Trade-offs: the amount is placed *exactly* on the limit rather than comfortably inside it, and
that choice is what makes this fixture worth having and also what makes it useless alone. It is a
choice made **against** the sibling [`../reject_102_overlimit`](../reject_102_overlimit/), because
neither folder discriminates without the other. An amount well inside the limit posts under `>`
and under `>=` alike, so it proves nothing about the edge; an amount well over the limit rejects
under both, so it proves nothing either. Only the equal-and-plus-one-cent pair moves exactly one
of the two outcomes when the operator is changed. The compromise accepted is that **this folder
proves nothing in isolation** -- it is load-bearing only as half of that pair, which section 8.3
sets out -- and the cost of that is one extra directory whose feed record differs from this one in
a single byte.

Assumptions: both cycle accumulators are left at `+0.00` deliberately, and the reason is
legibility rather than convenience. Master section 7.1.3 (validation order and the two inclusive
boundaries) records that in the seeds both fields are `0`, so the projected balance equals the
amount and a boundary fixture can be reasoned about from **one field** instead of from a
three-term sum a reader has to recompute before knowing whether the fixture is on the boundary at
all. A variant using non-zero cycle values would change that identity and would therefore have to
**show its own arithmetic** in its own README, which master section 7.1.3 requires; this scenario
deliberately does not, because it has no arithmetic beyond the identity to show.

---

## 2. The business rule, cited by program and line

The subject is a single line, `app/cbl/CBTRN02C.cbl:407`, and it is quoted here **in its passing
sense** together with the projection it tests and the arm it does not take:

```cobol
COMPUTE WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT
                    - ACCT-CURR-CYC-DEBIT
                    + DALYTRAN-AMT

IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
  CONTINUE
ELSE
  MOVE 102 TO WS-VALIDATION-FAIL-REASON
  MOVE 'OVERLIMIT TRANSACTION'
    TO WS-VALIDATION-FAIL-REASON-DESC
END-IF
```

The `COMPUTE` is `:403-405`, the guard is **`:407`**, its posting arm is `CONTINUE` at `:408`,
and the reject arm opens at `ELSE` on `:409` and sets reason 102 and the verbatim message
`OVERLIMIT TRANSACTION` across `:410-412`. Master section 7.1.1 (the four reject reasons) records
both the reason `MOVE` and the message `MOVE` for each code and is the authority for the message
text.

Assumptions: the guard is quoted as a **pass** condition because that is the sense the reference
tests, and quoting it the other way round is the specific mistake that inverts the boundary. The
COBOL asks whether the limit is greater than **or equal to** the projection and rejects in the
`ELSE`; a transcription that reads the reject sense out of this block and writes `>=` into a
reject test would reject the equal case -- exactly the value this folder exists to post. The
migrated predicate is therefore the **strict complement**, strictly greater, and section 7 names
where it lives.

**The projection is formed from the cycle accumulators, never from the running balance.**
`WS-TEMP-BAL` is `ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`. `ACCT-CURR-BAL`
appears nowhere in `:403-405`, and section 3 records the consequence that most surprises a first
reader: the posted account balance ends up **above** the credit limit while the guard still
passed, because the two are different quantities.

For this fixture the arithmetic is the identity:

```text
WS-TEMP-BAL       = 0.00 - 0.00 + 2065.00 = 2065.00
ACCT-CREDIT-LIMIT =                         2065.00
2065.00 >= 2065.00                        -> TRUE  -> CONTINUE at :408, reason stays 0
```

The surrounding walk, all in `app/cbl/CBTRN02C.cbl`:

| Step | Line | What happens for this record |
|---|---|---|
| Validate | `:210`, `:370-378` | `1500-VALIDATE-TRAN` performs the cross-reference lookup, then the account lookup because the reason is still 0 |
| Cross-reference hits | `:382-383`, `:388` | card `4859452612877065` resolves; `XREF-ACCT-ID` becomes `00000000007` |
| Account hits | `:394-395`, `:400` | account `00000000007` is read, so both boundary tests are evaluated |
| **Credit-limit test** | `:403-405`, **`:407`** | `2065.00 >= 2065.00` is **true**; `:408` continues and 102 is **not** set |
| Expiration test | `:414` | `2024-12-13 >= 2022-06-10` is **true**; `:415` continues and 103 is not set |
| Post | `:211-212`, `:424-444` | reason 0, so the unit of work runs: `:440` category balance, `:441` account, `:442` transaction |
| Category arm | `:474`, `:495-499`, `:526-542` | the keyed read hits, so `:498` performs the **update** arm |
| Account arithmetic | `:547`, `:548-549` | the amount is added to the running balance and, being non-negative, to the current-cycle **credit** total |
| Grade | `:229-230` | the rejected count is zero, so the return code stays 0 |

Assumptions: `WS-TEMP-BAL` is declared `PIC S9(09)V99` at `app/cbl/CBTRN02C.cbl:187` -- **eleven
digit positions, scale 2** -- while `ACCT-CREDIT-LIMIT` is the wider `PIC S9(10)V99`, twelve
positions, scale 2. The two agree on **scale**, which is the property the comparison depends on,
so `:407` is an exact fixed-point test on both sides with no rounding step for an equal case to
fall through. Master section 5.5 (money is one uniform contract) fixes that split for the whole
tree -- amounts and category balances at `S9(09)V99`, account balances and credit limits at
`S9(10)V99`, one scale throughout. It is named here rather than assumed because an inclusive
boundary is precisely where a scale mismatch would surface, and it would surface as a
one-cent-wrong verdict rather than as an error.

**`tests/README.md` section 13 states the requirement this folder discharges** in its own words:
a balance exactly at the credit limit must post, and one cent over must reject with reason 102.
That document is the reference parity suite's own specification, is reference-only, and is cited
here as the requirement rather than quoted.

---

## 3. Returns -- the expected outcome

The expected return code is **0**, the clean tier. Master section 7.1.6 (the warn tier belongs to
the COBOL suite alone) is the authority: `:229-230` raises the code to 4 **only** when the reject
count is greater than zero, and this scenario rejects nothing, so that branch never engages.

**The comparison.** `WS-TEMP-BAL = 0.00 - 0.00 + 2065.00 = 2065.00` against a credit limit of
`2065.00`. `2065.00 >= 2065.00` is **true**, so the transaction posts and reason 102 is never
assigned.

**One posted transaction row and no reject row.** The posted row carries transaction identifier
`0000000000683580`, type `01`, category `0001`, source `POS TERM`, amount `+2065.00` and card
`4859452612877065`. `ledger.transaction_rejects` gains nothing.

**The three mutations, in the order the reference performs them.** `2000-POST-TRANSACTION`
performs `:440` the category balance, then `:441` the account, then `:442` the transaction write,
and the record loop at `:200-226` performs that paragraph once per record:

| Output | Before | Arithmetic | After | Encoded |
|---|---|---|---|---|
| `TRAN-CAT-BAL` for `00000000007 / 01 / 0001` | `+0.00` | `0.00 + 2065.00` | **`+2065.00`** | `0000206500{` |
| `ACCT-CURR-BAL` | `+193.00` | `193.00 + 2065.00` | **`+2258.00`** | `00000225800{` |
| `ACCT-CURR-CYC-CREDIT` | `+0.00` | `0.00 + 2065.00` | **`+2065.00`** | `00000206500{` |
| `ACCT-CURR-CYC-DEBIT` | `+0.00` | untouched -- `:551` runs only for a negative amount | **`+0.00`** | `00000000000{` |
| `ACCT-CREDIT-LIMIT` | `+2065.00` | never written by this job | **`+2065.00`** | `00000020650{` |

**Those three writes are one commit.** Master section 7.1.5 (the reject payload, the unit of work
and the category-balance branch) fixes the migrated shape: **one commit per record**, opened by a
`TransactionTemplate` over the step's transaction manager, with the tasklet itself declared
`PROPAGATION_NOT_SUPPORTED` so the pass body holds no transaction of its own -- and
`job/PostTransactionsJob` annotates no method `@Transactional` precisely so that the commit
boundary has a single owner. **No saga, no two-phase commit, no compensating reversal.** A
partial-posting state within one record does not exist in the reference, so no expectation here
may describe one: either all three mutations in the table above are present or none is.

**The category row is reached through the UPDATE arm.** `:495-499` selects the arm, and because
this folder's `tcatbal.txt` already holds the row for `00000000007 / 01 / 0001`, the keyed read at
`:474` succeeds and `:498` performs `2700-B-UPDATE-TCATBAL-REC` at `:526-527`. The create arm at
`:503` is **not** taken. Master section 6.2.1 (TCATBAL's `FILLER` depends on the arm that wrote
the row) requires a scenario to say which arm produced its category row, because the arm -- not
the record type -- decides 22 bytes of padding; this is one of the eight trees on the update arm.

**Mask the processing timestamp; assert the originating one.** Master section 8.1 (timestamp
handling is domain-dependent) is the authority, and for the posting domain it splits the two
26-byte fields:

- `TRAN-ORIG-TS` is **copied** from the input record by `:436`, so it is deterministic and is
  **asserted**, not masked. Its value is `2022-06-10 19:27:53.000000`.
- `TRAN-PROC-TS` comes from the wall clock: `:437` performs the timestamp routine and `:438`
  moves the result in. It is **masked**, and it is the only field in the posted record that is.

Normalising both would discard a real assertion; normalising neither would compare a clock
reading. The per-domain set that decides which is which lives in the master, not in the reference
suite's own comparator, which is reference-only and is not the policy this tree follows.

**Two padding facts about the posted row, and they point in opposite directions.**

- `TRAN-DESC` is **space**-padded, because `:429` is a plain `MOVE DALYTRAN-DESC TO TRAN-DESC` of
  an input field that is already space-padded, and `MOVE` pads an alphanumeric receiver with
  spaces. Master section 6.3 (`TRAN-DESC` padding is job-dependent) records that the interest job
  reaches the same field with `STRING` and leaves a `0x00` tail instead, so "a 350-byte
  transaction record" is not enough information to author those 100 bytes -- the writing job has
  to be named, and here it is the posting job.
- The posted record's trailing `FILLER` at offset 330 carries **20 `0x00` NUL bytes**, whereas the
  **input** `DALYTRAN` record's `FILLER` at the same offset in this folder carries **20 `0x20`
  SPACE bytes**. Both are the bytes master section 6.1 (the measured `FILLER` table) measures.
  **This contrast is a genuine trap**: the input and the output are the same width at the same
  offset under two copybooks that are field-for-field identical (master section 5.1), so it is
  natural to assume the pad byte carries across, and it does not. No `MOVE` in the posting
  paragraph touches `TRAN-RECORD`'s `FILLER`, so it keeps the low-values state of the record
  area; the input file's pad is the seed's own space padding.

**The reject stream is empty, and the dataset still exists.** `app/jcl/POSTTRAN.jcl:34-38`
allocates `DALYREJS(+1)` unconditionally with `LRECL=430` at `:36`, and `:293` opens it `OUTPUT`
before the program can know whether anything will be rejected. The reference golden records that
as a **zero-byte** `dalyrejs.expected`, so an expectation phrased as "no reject dataset exists"
would fail against correct behaviour.

**Counters.** One processed, zero rejected, both emitted at `:227-228`.

Assumptions: the golden corpus is corroboration, never the derivation. Its `acctdat.expected`
measures 301 bytes, `tcatbal.expected` 51, `tranfile.expected` 351, `dalyrejs.expected` 0, and
`return_code.expected` the single byte `0`. Every figure above is arithmetic over **this folder's**
bytes and the lines cited in section 2, so this document states the rule and the golden agrees
with it; transcribing an output would make the document a copy of the oracle and would hide the
rule it is supposed to explain.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**Reason 102 must not be set. That is this folder's assertion, and it is an absence.** The reject
arm at `:410-412` is reachable, correct and deliberately not taken. An implementation that reads
the guard as strictly greater-or-equal on the *reject* side produces 102 here with every other
byte identical, and the failure surfaces as a reject where a posted record was expected: return
code 4 instead of 0, one reject row instead of one transaction row, and three absent mutations.
It is a one-line defect with a four-file consequence.

**Reason 103 must not be set either, and the fixture is arranged so that it cannot.** Master
section 7.1.4 (reason 102 is overwritten by 103) records that `:407-413` and `:414-420` are two
sequential unguarded `IF` blocks with no reason-code test between them, so a transaction tripping
both ends with **103** overwriting 102. Here the inclusive expiration guard at `:414` compares
`2024-12-13 >= 2022-06-10`, which is **true**, so nothing can overwrite anything. A scenario
isolating the credit-limit boundary **must** keep its originating date on or before the expiration
date, or its expectation silently becomes 103 and it is pinning the wrong operator entirely.

**Reasons 100 and 101 are excluded by this folder's own bytes.** `cardxref.txt` resolves card
`4859452612877065`, so the reason `MOVE` at `:385` cannot fire; `acctdata.txt` supplies the
resolved account `00000000007`, so `:397` cannot fire. Master section 7.1.3 also records that
reason 100 short-circuits the account lookup entirely, which is why a card that resolves is a
precondition for this scenario reaching `:407` at all.

**No reject record is produced, so the reject layout is not exercised here.** The 430-byte image
-- the 350-byte verbatim input plus the 80-byte trailer of a `9(04)` code and an `X(76)`
description, established six independent ways in master section 5.4 (why the reject record is 430
bytes) -- is untouched by this scenario. Nothing here asserts the trailer, the four-character
`0102` wire form or the `SMALLINT` stored form of master section 7.1.2; the four `reject_10x_*`
folders own those.

**The create arm must not be taken.** `2700-A-CREATE-TCATBAL-REC` at `:503-524` runs only when
`WS-CREATE-TRANCAT-REC` is `'Y'`, which only the `INVALID KEY` branch at `:475-478` sets. The row
for `00000000007 / 01 / 0001` is present, so the read at `:474` succeeds and the flag keeps the
`'N'` declared at `:190`. Taking the create arm here would also change 22 padding bytes, for the
reason master section 6.2.1 gives.

**Code 109 is unreachable and must not be expected.** `:556` assigns it inside the `INVALID KEY`
branch of the account `REWRITE` at `:554-559` -- on the posting path, after validation has passed,
and where no reject record is written. Master section 7.1.1 fixes the persisted domain as exactly
`{100, 101, 102, 103}`.

**No abend occurs.** Every reachable `9999-ABEND-PROGRAM` site is status-guarded: the six opens
(`:250`, `:268`, `:287`, `:305`, `:323`, `:341`), the feed read (`:363-366`), the category-balance
read (`:492`) and rewrite (`:541`), the transaction write (`:577`) and the six closes (`:596`,
`:614`, `:633`, `:651`, `:669`, `:688`).

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

The `ASSIGN` names, organizations and record keys are declared at `app/cbl/CBTRN02C.cbl:29-61`,
and the datasets they bind at `app/jcl/POSTTRAN.jcl:30-42`. The record lengths are the **summed
field widths** of master section 5.1 (the ledger, and why lengths are summed rather than read),
never a `RECLN` banner comment -- the banners are inconsistent across the baseline and that
section records why summing is the only admissible method. The 17-byte `TRAN-CAT-KEY` is the
three-part key that section 5.1 composes, and the cross-reference is read **by card number** at
offset 0 because `app/jcl/POSTTRAN.jcl:32-33` mounts the base cluster only.

### 5.2 On-disk geometry, line endings and pad bytes

| File | Size | Records | Bytes per record | `CR` bytes | Trailing newline | `FILLER` fill byte |
|---|---:|---:|---:|---:|---|---|
| `dailytran.txt` | **351** | 1 | 350 | 0 | exactly one `LF` | `0x20` SPACE, 20 at offset 330 |
| `cardxref.txt` | **51** | 1 | 50 | 0 | exactly one `LF` | `0x20` SPACE, 14 at offset 36 |
| `acctdata.txt` | **301** | 1 | 300 | 0 | exactly one `LF` | `0x20` SPACE, 178 at offset 122 |
| `tcatbal.txt` | **51** | 1 | 50 | 0 | exactly one `LF` | **`0x30` ASCII `'0'`, 22 at offset 28** |

Every file is exactly **RECLN + 1** bytes: one record plus one trailing `LF`, per master sections
3.8 (line endings) and 3.9 (trailing newline). There are **zero `CR` bytes** anywhere in this
folder.

Assumptions: the `FILLER` fill byte differs between the files because it was **measured from the
seeds, not chosen**. Master section 6.1 (the measured `FILLER` table) takes the byte-set of the
`FILLER` slice of every row of each seed, and master section 3.2 (padding -- the general rule, and
the one that overrides it) states explicitly that the measured table **overrides** the general
`PICTURE`-derived rule wherever the two differ. The category-balance row is one of the two rows
master section 6.2 (the two rows that contradict the general rule) identifies, and applying the
general rule to it would produce a record
differing from both its seed and the golden in 22 bytes that carry no data at all -- a diff with
the key and the balance both correct, pointing at the field a reviewer is least likely to suspect.

Assumptions: `tcatbal.txt` is `LF`-terminated although its seed ships `CRLF`, and the
normalisation is required rather than stylistic. Master section 3.8 measures
`app/data/ASCII/tcatbal.txt` as **mixed** -- 49 `CRLF` rows and a final bare `LF` -- and rules that
a `CRLF` seed is normalised on the way in, with silence meaning `LF`. A retained `0x0D` would be
absorbed as a data byte at the tail of the 22-byte `'0'` `FILLER`, making the physical row 51 bytes
against a declared 50, and master section 1.2 records that the loaders **reject** a wrong-length
row rather than padding or truncating it. The failure would therefore arrive at load time with
every field value correct.

```bash
# WHAT: assert the whole byte geometry of this folder, and additionally assert the one
#       relationship the scenario turns on -- that the amount and the credit limit decode to
#       the same value. Run from this directory.
# WHY : Assumptions: a boundary fixture has a second failure mode a width check cannot see. If
#       the amount drifts by one cent in either direction the geometry stays perfect and the
#       expected outcome silently inverts, so the equality is worth asserting at authoring time
#       rather than discovering it as an unexplained reject in a comparison. The two values are
#       read from different files at different widths -- 11 bytes at offset 132 against 12 at
#       offset 24 -- which is why the check DECODES both instead of comparing their bytes.
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

### 5.3 The discriminating field, decoded

`DALYTRAN-AMT` occupies **offsets 132 to 142**, zero-based, and holds `0000020650{`. It decodes
like this, and every step is a rule from the master rather than a convention of this folder:

| Step | Rule | Result |
|---|---|---|
| The field is `PIC S9(09)V99`, so it is **eleven digit positions** in one byte each | master section 5.6 (physical width comes from `USAGE`) -- display usage is one byte per digit | 11 bytes, not 12 |
| The last byte carries the sign as an **overpunch**, and `{` is the positive `0` | master section 3.3 (zoned-decimal sign overpunch) | digits `00000206500`, sign positive |
| `V99` marks an implied decimal occupying **no byte**, so the last two digits are cents | master section 3.7 (implied decimal) | **`+2065.00`** |

`ACCT-CREDIT-LIMIT` at offsets 24 to 35 holds `00000020650{` and decodes to the same value,
`+2065.00`, by the same three rules.

**The two fields are one byte different in width, and that is correct rather than a
discrepancy.** The amount is `S9(09)V99` and the limit is the wider `S9(10)V99`; both carry scale
2, and master section 5.5 fixes exactly that split for the whole tree. The extra byte is an extra
**integer** digit, so the limit can express a value the amount cannot, and neither the comparison
at `:407` nor the equality asserted above is affected. Reading the width difference as an error
and "correcting" one field to match the other would change a record length.

Assumptions: the decode is driven from the field's `PICTURE` and never from the look of its last
byte. Master section 3.5 (the signed-versus-unsigned false-positive class) records that several
**unsigned** `PIC 9(n)` fields in these seeds end in a letter that is simply a data character, and
sniffing the trailing byte turns such a letter into a sign. Two fields in this folder are unsigned
and sit next to signed ones -- `DALYTRAN-CAT-CD` is `PIC 9(04)` holding `0001`, and
`TRANCAT-ACCT-ID` is `PIC 9(11)` -- so the distinction is live here, not hypothetical. Master
section 3.4 (worked vectors) additionally publishes the exact vector this folder's seed row uses,
`0000005047G` for `+504.77`, and warns that reading it as `+50.47` scales every amount by a
hundred while leaving the field the right width.

### 5.4 Field values, as observed

Offsets are zero-based. Money is signed zoned with the sign folded into the last byte and no byte
for the decimal point, per master sections 3.3 and 3.7. The offsets themselves come from master
section 5.2 (the `CVTRA05Y` / `CVTRA06Y` field table), and master section 5.3 (the two-source
offset cross-check) independently corroborates two of them -- the card number at 262 and the
processing timestamp at 304 -- from `app/jcl/TRANREPT.jcl`, which is what removes doubt from every
offset below.

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
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | genuine input data -- section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input padding; the posted row differs, section 3 |

`cardxref.txt`, one `CARD-XREF-RECORD`: `XREF-CARD-NUM` `4859452612877065` at 0, `XREF-CUST-ID`
`000000007` at 16, `XREF-ACCT-ID` `00000000007` at 25, `FILLER` 14 spaces at 36. That is the
card-to-customer-to-account chain master section 9.1 (derivation discipline) requires a scenario to
keep coherent, and it resolves to an account this folder's own `acctdata.txt` supplies.

`acctdata.txt`, one `ACCOUNT-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | `00000000007` | account 7 |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | active |
| `ACCT-CURR-BAL` | 12 | 12 | `00000001930{` | `+193.00` |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000020650{` | **`+2065.00`** -- the limit `:407` tests |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000002640{` | `+264.00` -- unread by this job |
| `ACCT-OPEN-DATE` | 48 | 10 | `2012-10-12` | -- |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `2024-12-13` | the date `:414` tests |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2024-12-13` | -- |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` | `+0.00` -- the identity of section 1 |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` | `+0.00` |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | -- |
| `ACCT-GROUP-ID` | 112 | 10 | 10 spaces | blank -- unread by this job |
| `FILLER` | 122 | 178 | 178 spaces | -- |

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` line 11 spells it. The
misspelling is preserved because this is copybook-side naming; master section 9.3 confines the
three spelling corrections to **target column names** only. The blank `ACCT-GROUP-ID` is inert
here -- it is the disclosure-group key the interest program composes, and the posting job never
reads it. **`ACCT-CASH-CREDIT-LIMIT` is not the field `:407` tests**: the guard names
`ACCT-CREDIT-LIMIT`, and confusing the two would move the boundary by `1801.00` while leaving a
fixture that still looks like a boundary case.

`tcatbal.txt`, one `TRAN-CAT-BAL-RECORD`: `TRANCAT-ACCT-ID` `00000000007` at 0, `TRANCAT-TYPE-CD`
`01` at 11, `TRANCAT-CD` `0001` at 13 -- together the 17-byte key -- then `TRAN-CAT-BAL`
`0000000000{` = `+0.00` at 17, and 22 ASCII `'0'` of `FILLER` at 28. The key matches the type and
category of the daily transaction, which is what puts the read at `:474` on the update arm.

### 5.5 Departures from a tree rule, named

Master section 10 requires a scenario to name every departure from a rule in the master and give
its reason. There are three, and none is a business-rule change:

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes where its seed row is 36 | master section 3.10 (measured seed geometry, and the card-xref ruling) | The copybook sums to 50; the seed omits the trailing `FILLER X(14)` entirely. Authored at full copybook width with that `FILLER` space-padded, which is the shape that section rules for |
| `tcatbal.txt` is `LF`-terminated where its seed is `CRLF` | master sections 3.8 and 3.9 | A retained `0x0D` would land in the 22-byte `'0'` `FILLER` and make the row 51 bytes against a declared 50; the rationale is above in section 5.2 |
| `DALYTRAN-AMT` carries `+2065.00` where the seed row carries `+504.77` | master section 11.1 (attestation) | A business-rule field reshaped deliberately -- it **is** the scenario. Attested to the byte in section 9 |

No non-zero cycle values are used, so the additional arithmetic disclosure master section 7.1.3
requires of a fixture that breaks the projected-balance identity does not apply here.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input data.**
The feed record has not been processed yet.

Assumptions: master section 8.1 requires this to be stated **every time** a blank timestamp is
shown, because 26 spaces in an input file and 26 spaces in a golden are the identical byte pattern
arrived at for opposite reasons -- real unset data in the first case, the product of normalisation
in the second -- and the meaning cannot be recovered from the bytes. A reader who assumes
"normalised" for an input file concludes the fixture is missing data; a reader who assumes "real
data" for a golden concludes the comparison is not masking. The same distinction is why the
migrated `ledger.daily_transactions.proc_ts` column is **nullable** while
`ledger.transactions.proc_ts` is `NOT NULL`, in the sibling harness cited in section 7: **these 26
blanks load as SQL `NULL`**, and they are the reason the input column can be null at all. Leaving
the field blank rather than stamping it is also what keeps reruns byte-identical, since any value
written here would either be a clock reading or a constant a reader would mistake for one.

`DALYTRAN-ORIG-TS` is the opposite case: real, deterministic input data, copied unchanged into the
posted record by `:436`, and therefore **asserted** rather than masked.

Everything else holds by construction. Every byte in this folder is literal; there is no clock
value, no random identifier and no environment-derived string; and the business date the reference
takes as a parameter never appears here, because the posting job derives nothing from the wall
clock except the processing stamp that section 3 masks.

---

## 7. Target-side contracts this scenario agrees with

The migrated job is `job/PostTransactionsJob`, and the boundary this folder pins is asserted
against these contracts:

- **`service/PostingValidationService`** -- its over-limit predicate is **strictly greater**, not
  `>=`. `isOverCreditLimit` reports the breach as `projectedBalance.exceeds(creditLimit)`, which
  is the strict complement of the inclusive COBOL guard, so a transaction landing exactly on the
  limit arrives at the reject decision with that argument `false`. **This folder is the evidence
  that the complement was taken in the right direction**; describing the Java predicate as `>=`
  would describe an implementation that rejects this fixture.
- **`dto/PostingValidationResult`** -- `accepted(...)` carries the projected cycle balance, held at
  the same scale as `WS-TEMP-BAL PIC S9(09)V99` at `:187`. The projection is part of the
  **accepted** outcome and not only of a rejection, which is what lets an assertion check the
  number `:403-405` computed on the *passing* side of the boundary rather than inferring it.
- **`service/CategoryBalanceService`** -- reports the **update** arm and a resulting balance of
  `2065.00`. Both arms are additive and neither assigns, so the arm is visible in the reported
  outcome rather than in the balance alone.
- **`dto/BatchReturnCode`** -- supplies the tiers `0`, `4` and `8`; this scenario expects the
  clean tier.
- **`dto/BatchRunSummary`** -- enforces the warn-tier biconditional in both directions for the
  posting job, so this scenario must report the clean tier with one record processed and zero
  rejected. A summary claiming the warn tier with nothing rejected is refused outright, which is
  exactly the shape a mis-transcribed guard would produce.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql):
`ledger.daily_transactions`, `account.card_xref`, `account.accounts` and
`ledger.transaction_category_balances` as inputs -- the last keyed on the three-part primary key
that the 17-byte `TRAN-CAT-KEY` maps to -- with `ledger.transactions` gaining one row and
`ledger.transaction_rejects` staying empty. A scenario owns only its own rows and seeds no other
service's schema, per master section 11.3 (prohibitions that govern every byte in this tree).

**Tier 0 here is a batch step return code, never a build status.** Master section 7.1.6 is
explicit that the graded rubric belongs exclusively to the reference COBOL suite under `tests/**`.
Maven, Surefire, Failsafe and JUnit are binary: a Java build either passes or fails, there is no
intermediate tier for them to express, and a Java build reporting anything other than success is a
failure. The `0` in this document is a fixture expectation value, asserted like any other.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs. The
oracle is the reference-only `tests/golden/posting/boundary_exact_limit/` tree, read as the
authority and never written, and no golden is ever regenerated from a run.

### 8.2 Oracle constants that are not expectations for this scenario

Master section 7.1.7 (oracle constants a fixture may not contradict) records the constants of
`tests/e2e/test_posting_cycle.py`, and they describe the **full 300-record seed cycle**: 300 daily
records, 262 posted, 38 rejected, a conservation total of `77954.70`, and 50 category keys becoming
100. **Not one of them is an expectation here.** This folder holds a single record and expects one
posted row and zero rejects.

The 38-reject figure is the one most likely to be misread, because for that seed those rejections
all carry reason `0102` -- the same reason this folder exists to *avoid*. They arise from the
unmodified seed feed against the unmodified seed accounts, and they say nothing about a fixture
whose amount was reshaped precisely to land **on** the limit instead of over it. The two
loader-geometry constants do agree: `(300, 11)` for the account record and `(50, 17)` for the
category balance match this folder's record lengths and key lengths exactly.

### 8.3 The paired folder

[`../reject_102_overlimit`](../reject_102_overlimit/) carries the same card, the same account, the
same type and category, the same category row and the same dates. It differs in **exactly one
byte**: the overpunch at offset 142 of `dailytran.txt`.

| Scenario | `DALYTRAN-AMT` | `WS-TEMP-BAL` | `ACCT-CREDIT-LIMIT >= WS-TEMP-BAL` | Result |
|---|---|---|---|---|
| `boundary_exact_limit` (here) | `0000020650{` = `+2065.00` | `2065.00` | **TRUE** | **posts**, return code 0, no reject row |
| [`../reject_102_overlimit`](../reject_102_overlimit/) | `0000020650A` = `+2065.01` | `2065.01` | FALSE | rejects **102**, return code 4 |

`{` is the positive `0` overpunch and `A` is the positive `1`, so one byte carries the whole
one-cent difference. **The pair, and only the pair, proves the operator is `>=` and not `>`.**
Read either folder alone and both readings of the operator survive: an amount exactly on the limit
is expected to post by anyone who believes the guard is inclusive, and would also be *assumed* to
post by someone who believed it was strict and mis-stated the amount by a cent; an amount one cent
over rejects under `>` and under `>=` alike. Changing the operator flips exactly one of the two
outcomes, and that is the whole mechanism. It is also why the two folders are byte-identical
everywhere else: a failure localises to the amount rather than to any of the other 349 bytes.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived.** Every record traces to a published AWS
CardDemo sample seed row under `app/data/ASCII/`:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 7 | **byte-identical** |
| `tcatbal.txt` | `app/data/ASCII/tcatbal.txt` | 7 | **byte-identical** once the seed's `CR` is removed |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | 21 | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | one business-rule field reshaped, below |

The cross-reference row is row **21** of its seed rather than row 7 because that file is ordered by
card number, and account `00000000007` appears there under card `4859452612877065`.

**It represents no real person and no real account.** The seeds ship with the upstream open-source
AWS CardDemo project as fabricated demonstration data, and master section 11.1 carries the
tree-level attestation this scenario inherits. Identity and primary-account-number bytes --
the card number, the customer id and the account id -- are taken **unchanged** from the seed.

Alternatives Considered: provenance is **attested from a published synthetic source rather than
inferred**, and the reason is that a card number satisfying a check digit is indistinguishable by
inspection from a live one, so "it looks like test data" is not evidence of anything. Two options
were weighed, and master section 11.2 (why the seeds rather than freshly minted card numbers)
records the tree-level decision this folder follows. The first was minting fresh reserved-range
test primary account numbers with a documented check-digit generator, which would give an
independent provenance story but would require standing up and attesting a second generator. The
second, chosen here, was reusing the published CardDemo synthetic seeds: they carry documented
non-person status, and the card-to-cross-reference-to-account chain stays internally consistent
because the programs under test already validate against seed-shaped records. A card number here
satisfies its check digit because the upstream synthetic seed made it so, not because it was
matched to any issuer.

**Exactly one business-rule field is reshaped away from its seed value.** In `dailytran.txt`,
`DALYTRAN-AMT` at `[132:143]` holds `0000020650{` = `+2065.00`, where seed row 1 holds
`0000005047G` = `+504.77`. Measured, the difference is the **six bytes** at offsets 137 to 142:
`05047G` becoming `20650{`. The overpunch changes too -- `G` is the positive `7` and `{` the
positive `0` -- so this is a change of value and not merely of digits, which is why the check in
section 5.2 decodes rather than comparing text.

**The amount was matched to the limit, not the limit to the amount**, and the direction is the
whole point of the choice.

Trade-offs: lowering `ACCT-CREDIT-LIMIT` to `+504.77` to meet the seed amount would produce the
same comparison and the same verdict, and it was rejected for a reason that only shows up in the
pair. The sibling would then still have to raise its amount by a cent, so the two folders would
differ from each other in **two** fields rather than one, and a failure would no longer localise to
the amount. Reshaping the amount instead keeps the account row byte-identical to its seed in both
folders and confines the difference between them to a single byte. The cost accepted is that this
folder's feed record is *not* byte-identical to its seed row, which is exactly why the delta is
stated to the byte above.

**No other field in any file here departs from its seed value.** The account row, the category row
and the cross-reference row are the seed's own; in the daily transaction the identifier, type,
category, source, description, all four merchant fields, the card number and both timestamps are
unchanged. The two normalisations named in section 5.5 -- the card-xref width and the
category-balance line ending -- are width and line-ending conformance, **not** business-rule field
changes, so the two statements do not conflict.

The baseline is read and never written. `app/**`, `tests/**`, `scripts/**` and `samples/**` are
**REFERENCE-ONLY** per AAP section 0.2.2 and per master section 9.1: the seeds are inputs to
derivation, rows are copied into this folder and reshaped **here**, and nothing in this tree edits
a baseline file or regenerates a golden. Master section 11.3 governs the rest and is not restated:
no secret, credential, connection string or endpoint appears in any file here, and money never
leaves fixed point.

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**, not a mirror. `PostTransactionsJobParityIT` resolves each
scenario under `/fixtures/posting/`, seeds the masters from these bytes, launches the posting job
and compares the resulting transaction master, category balances, account master and reject stream
against `tests/golden/posting/boundary_exact_limit` -- so an edit here changes what the parity run
asserts. `BatchFixtureContractTest` additionally holds every file in this folder to its declared
geometry and to the values that make the scenario discriminating, so a layout mistake is caught in
this module rather than surfacing later as an unexplained comparison failure.

Assumptions: this is stated explicitly because the three families in this tree behave differently
and the difference is invisible from the files themselves. Master section 10 is the authority for
the `posting/**` family: it records that `PostTransactionsJobParityIT` declares `/fixtures/posting/`
as its seed root and compares against `tests/golden/posting/<scenario>`, and that each posting
README says so. Master section 1.5 (which scenarios drive a job in this module) is the authority for
the other two: `preflight/**` is opened directly by `PreflightDailyTransactionsJobTest`, while
`interest/**` is a mirror whose consuming test resolves its inputs under the repository root and so
never reads this tree at all. A reader who assumed these bytes drive nothing would edit them
expecting no consequence, which is the most expensive mistake this folder admits.

---

*This README is the mandatory Explainability carrier for the four record files in this directory,
required by master section 10 and by user-specified Rule 1 (Explainability). Nothing
machine-reads it: `config/checkstyle/checkstyle.xml` narrows its audit set to `java` at `Checker`
level, ESLint governs `ui/**` and Ruff `data-migration/**`, and although the repository-wide
lexical gate `config/rule1/rule1_gate.py` does govern label form in Markdown, it excludes every
path containing `/src/test/resources/fixtures/`, which includes this one. The canonical label form
is used here regardless, because `docs/CODE_DOCUMENTATION_STANDARD.md` fixes one written form
repository-wide and a Rule 1 audit finds a rationale by literal string search across seven
languages. Whether each rationale names a real consequence, and whether every byte value and line
citation above is true, are review obligations no lexical gate can decide.*
