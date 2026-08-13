# `preflight/happy_path` -- the card resolves, the account is found, nothing is reported

> **Purpose.** Pin the fully matched path of the pre-posting preflight pass: a daily transaction whose
> card **is** in the cross-reference and whose resolved account **is** in the account master reaches
> neither of the program's two diagnostic branches, writes nothing anywhere, and ends in the clean
> return-code tier. It is the reference case the two unmatched siblings deviate from, and it is the
> only one of the three whose expected observable output is **silence**.
>
> **Source of truth.** `app/cbl/CBTRN01C.cbl` for the behaviour, cited below by line;
> `app/cpy/CVTRA06Y.cpy`, `app/cpy/CVACT03Y.cpy` and `app/cpy/CVACT01Y.cpy` for the record layouts; the
> seed datasets under `app/data/ASCII/` for the bytes; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document cites by section
> rather than restating (master section 1.3). **There is no JCL driver to cite**: master section 4.1
> records that no member of `app/jcl` executes this program at all.
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. This whole file is pure ASCII for the
> reason that section gives.

**This README is the mandatory Explainability carrier for the three record files beside it.** Master
section 1.2 records why: a fixed-width record file cannot carry a comment of any kind, because every
byte position is meaningful and a comment on its own line is a physical row of the wrong length. Master
section 10 makes the artifact mandatory rather than courteous, and the section order below is the one it
fixes.

**A naming fact first, because it is the one thing most likely to be "corrected".** This tree calls the
domain **`preflight/`** while the reference-only house tree calls it **`prepost/`**
(`tests/fixtures/prepost/**`). Master section 4.2 records that the two names denote the same
`CBTRN01C` behaviour, that this tree matches the job name a reader finds in the module's own sources
and in the batch state machine, and that the house tree is reference-only in any case. Neither name is
wrong and neither should be changed to match the other.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction on card `4859452612877065`, a cross-reference holding **that** card and
resolving it to account **`00000000007`**, and an account master holding **that** account. Both lookups
succeed.

Three things must hold at once:

- the cross-reference lookup **succeeds**, so the card-not-verified branch is not taken;
- the account lookup **succeeds**, so the account-not-found branch is not taken;
- **nothing is written and nothing is changed** -- every one of the program's six datasets is opened
  `INPUT`, so there is no output for a preflight pass to produce.

The scenario's whole expectation is therefore a **negative**: no diagnostic, no row, no mutation, clean
tier. Master section 4.1 fixes the domain at three scenarios, and this is the one that establishes what
"nothing wrong" looks like so that the other two have something to differ from.

Alternatives Considered: omitting a matched-path scenario altogether, on the grounds that the two
unmatched scenarios carry the interesting behaviour. Rejected because a pass that reported a diagnostic
for **every** record -- one that had, say, inverted a status test -- would satisfy both unmatched
scenarios and fail only this one. Without it the suite cannot distinguish "reports the right records"
from "reports every record".

---

## 2. The business rule, cited by program and line

### 2.1 The two lookups, and the guard between them

`app/cbl/CBTRN01C.cbl:164-186` is the loop. For this record:

| Step | Line | What happens |
|---|---|---|
| Read the feed | `:166`, `:202-225` | `1000-DALYTRAN-GET-NEXT`; the `READ` at `:203` returns `'00'`, so `:204-205` set `APPL-RESULT` to 0 and the terminating flag stays `'N'` |
| Display the record | `:167-169` | the inner guard is true, so `:168` DISPLAYs the whole `DALYTRAN-RECORD` |
| Compose the card key | `:170-171` | the read status is cleared and `DALYTRAN-CARD-NUM` is moved into `XREF-CARD-NUM` |
| **Cross-reference lookup** | `:172`, `:227-239` | `2000-LOOKUP-XREF` reads by `FD-XREF-CARD-NUM` (`:228-230`) and takes **`NOT INVALID KEY`** (`:234`), so `:235-238` DISPLAY `SUCCESSFUL READ OF XREF` and then the card, account and customer identifiers, and `WS-XREF-READ-STATUS` stays 0 |
| Guard | `:173` | `IF WS-XREF-READ-STATUS = 0` is **true**, so the account read is performed rather than the skip message |
| Compose the account key | `:174-175` | the account status is cleared and `XREF-ACCT-ID` is moved into `ACCT-ID` |
| **Account lookup** | `:176`, `:241-250` | `3000-READ-ACCOUNT` reads by `FD-ACCT-ID` (`:242-244`) and takes **`NOT INVALID KEY`** (`:248`), so `:249` DISPLAYs `SUCCESSFUL READ OF ACCOUNT FILE` and `WS-ACCT-READ-STATUS` stays 0 |
| No diagnostic | `:177-179` | `IF WS-ACCT-READ-STATUS NOT = 0` is **false**, so the `ACCOUNT ... NOT FOUND` line at `:178` does not run |
| Leave the loop | `:186` | the next iteration reaches end of file; see section 2.2 |
| Close and finish | `:188-197` | six closes, the end banner at `:195`, `GOBACK` at `:197` |

**The `SUCCESSFUL READ OF ...` lines are the program's own confirmation output, not error output.**
`:235` and `:249` are on the `NOT INVALID KEY` arms. They are the markers a test asserts on; the two
diagnostics this scenario must **not** produce are the ones at `:232`, `:246`, `:178` and `:181-183`.

### 2.2 The loop performs the lookup twice for one record, and this is a real characteristic

The inner `IF END-OF-DAILY-TRANS-FILE = 'N'` opened at `:167` closes at **`:169`**, so it guards
**only** the record display at `:168`. The lookup block at `:170-184` sits **outside** it and inside the
outer `IF` at `:165`. On the second iteration the read at `:166` reaches end of file and sets the flag,
the display is skipped -- and the lookup block runs **again**, against the card number still sitting in
`DALYTRAN-RECORD` from the first pass.

So for one input record the program performs **two** cross-reference reads and **two** account reads,
and DISPLAYs each success marker **twice**.

Assumptions: this is a control-flow characteristic of the immutable baseline, not a defect to be
worked around in a fixture. It has no effect on data -- every dataset is opened `INPUT`, so a repeated
read changes nothing and produces no output beyond the duplicated console lines. **An expectation must
therefore assert on marker presence, never on a repetition count**, which is the same rule the
reference-only `tests/fixtures/prepost/happy_path/README.md` records for the house tree. The migrated
pass implements the clean loop instead -- one inspection per record read, and none against a record it
did not read -- and its own class documentation records that divergence and why the extra pair is
unobservable in output data.

---

## 3. Returns -- the expected outcome

The return code is **0**. **`CBTRN01C` never assigns `RETURN-CODE` anywhere**, so a completed pass
leaves it at zero -- including the two unmatched scenarios, where an unresolvable record is reported as
a diagnostic rather than graded as a failure. Master section 7.1.6's graded rubric belongs to the COBOL
parity suite and never to a Java build gate; there is no reject stream in this program and no
counter-driven warn tier.

**What must appear.**

| Marker | Line | Times, for one input record |
|---|---|---|
| `START OF EXECUTION OF PROGRAM CBTRN01C` | `:156` | once |
| the whole `DALYTRAN-RECORD`, displayed | `:168` | once -- it is inside the guard that closes at `:169` |
| `SUCCESSFUL READ OF XREF` | `:235` | twice, per section 2.2 |
| `CARD NUMBER: 4859452612877065` | `:236` | twice |
| `ACCOUNT ID : 00000000007` | `:237` | twice |
| `CUSTOMER ID: 000000007` | `:238` | twice |
| `SUCCESSFUL READ OF ACCOUNT FILE` | `:249` | twice |
| `END OF EXECUTION OF PROGRAM CBTRN01C` | `:195` | once |

**What must not appear.**

| Absent marker | Line | Why it cannot occur |
|---|---|---|
| `INVALID CARD NUMBER FOR XREF` | `:232` | the read at `:229` takes `NOT INVALID KEY` |
| `CARD NUMBER ... COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-...` | `:181-183` | reached only when `WS-XREF-READ-STATUS` is not 0 |
| `INVALID ACCOUNT NUMBER FOUND` | `:246` | the read at `:243` takes `NOT INVALID KEY` |
| `ACCOUNT 00000000007 NOT FOUND` | `:178` | reached only when `WS-ACCT-READ-STATUS` is not 0 |

**No data changes, and the reason is structural rather than incidental.** All six datasets are opened
`INPUT` -- `:254` `DALYTRAN`, `:273` `CUSTFILE`, `:291` `XREFFILE`, `:309` `CARDFILE`, `:327`
`ACCTFILE`, `:345` `TRANFILE` -- and the program contains **no** `WRITE`, `REWRITE` or `DELETE`
statement of any kind. There is nothing for a preflight pass to produce, so every input file is
byte-identical after the run.

**Three of the six datasets are opened and never read.** `CUSTFILE`, `CARDFILE` and `TRANFILE` are
opened at `:158`, `:160` and `:162` and closed at `:189`, `:191` and `:193`, and the program's only
three `READ` statements are the feed at `:203`, the cross-reference at `:229` and the account at
`:243`. That is why this folder ships **three** record files rather than six: a fixture for a dataset
the program never reads would assert nothing. Their absence from the folder is deliberate and is not an
omission.

Assumptions: this domain has **no golden corpus in this tree's shape**, and none is asserted here. The
program's output is console diagnostics rather than records, so the expectation is stated as marker
presence and absence above rather than as a byte comparison against an `*.expected` file. The
house tree's `tests/fixtures/prepost/happy_path/README.md` states the same expectation for the same
program in the same terms, and it is reference-only.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**Neither diagnostic branch may be taken.** Both are listed in section 3 with the line that guards
them. The failure mode worth naming is the **inverted status test**: an implementation testing
`WS-XREF-READ-STATUS NOT = 0` where the baseline tests `= 0` at `:173` would skip the account read on a
matched card and emit the skip message, which is exactly the output the `unmatched_card` sibling
expects -- so the two scenarios together are what distinguish a correct guard from an inverted one.

**No abend occurs.** `CBTRN01C` uses `Z-ABEND-PROGRAM` and `Z-DISPLAY-IO-STATUS` rather than the
numbered paragraphs `CBTRN02C` uses, and every site is status-guarded:

| Site | Paragraph | Fires only when |
|---|---|---|
| `:266`, `:285`, `:303`, `:321`, `:339`, `:357` and their neighbours | the six open paragraphs beginning at `:252`, `:271`, `:289`, `:307`, `:325`, `:343` | an `OPEN` returns a status other than `'00'` |
| `:222` | `1000-DALYTRAN-GET-NEXT` | the feed `READ` status is neither `'00'` nor `'10'` |
| the six close paragraphs | from `:188-193` | a `CLOSE` returns a status other than `'00'` |

A run whose opens and closes all return `'00'` and whose feed read returns `'00'` then `'10'` reaches
none of them. **An `INVALID KEY` on either lookup is not an error** -- `2000-LOOKUP-XREF` and
`3000-READ-ACCOUNT` have **no abend site at all**; they set a status field and DISPLAY, which is the
whole point of a preflight pass.

**A missing cross-reference or account row must not fail the batch chain.** That is the two siblings'
expectation and it is stated here as well because the distinction is easy to lose: preflight
**reports**, posting **rejects**. The same two conditions become reject reasons 100 and 101 in
`CBTRN02C` with a return code of 4; here they are diagnostics with a return code of 0.

---

## 5. Parameters -- the files in this folder and their byte geometry

### 5.1 Inventory

| File | Copybook | RECLN | DDNAME / `ASSIGN` | Organization | Key |
|---|---|---:|---|---|---|
| `dailytran.txt` | `app/cpy/CVTRA06Y.cpy` | 350 | `DALYTRAN` | SEQUENTIAL | -- |
| `cardxref.txt` | `app/cpy/CVACT03Y.cpy` | 50 | `XREFFILE` | INDEXED | `XREF-CARD-NUM`, offset 0, length 16 |
| `acctdata.txt` | `app/cpy/CVACT01Y.cpy` | 300 | `ACCTFILE` | INDEXED | `ACCT-ID`, offset 0, length 11 |
| `README.md` | -- | -- | -- | -- | this file |

The `ASSIGN` names are declared at `app/cbl/CBTRN01C.cbl:29-58`; the record lengths are the summed
widths of master section 5.1, never a `RECLN` banner. Three further `ASSIGN` names -- `CUSTFILE`,
`CARDFILE` and `TRANFILE` -- are declared and opened but never read, for the reason section 3 gives.

**This program reads the cross-reference by card number**, at offset 0, exactly as posting does.
Master section 4.3 fixes the two access paths and records that the account-keyed alternate path belongs
to the interest domain; nothing in this folder uses it.

### 5.2 On-disk sizes and line endings

| File | Size | Records | Bytes per record | `CR` bytes | Trailing newline |
|---|---:|---:|---:|---:|---|
| `dailytran.txt` | **351** | 1 | 350 | 0 | exactly one `LF` |
| `cardxref.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |
| `acctdata.txt` | **301** | 1 | 300 | 0 | exactly one `LF` |

One record plus a single trailing `LF` per file, per master sections 3.8 and 3.9, and **zero `CR`
bytes** in the folder.

```bash
# WHAT: assert the byte geometry, and additionally assert the two relations this scenario turns on --
#       that the feed's card matches the cross-reference key, and that the account it resolves to
#       matches the account master's key. Run from this directory.
# WHY : Assumptions: this scenario's entire expectation is the ABSENCE of output, so a fixture that
#       quietly stopped matching would produce a diagnostic that looks like a program defect rather
#       than a fixture defect -- and, unlike the posting domain, there is no return code or reject
#       stream to make the change loud. Asserting both chain links at authoring time is the only cheap
#       way to tell the two apart.
python3 - <<'PY'
EXPECTED = {"dailytran.txt": (351, 350), "cardxref.txt": (51, 50),
            "acctdata.txt": (301, 300)}
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
      else "| MISMATCH the card would not be verified")
print("resolved account", resolved, "account master holds", held,
      "| OK the account is found" if resolved == held
      else "| MISMATCH the account would be reported missing")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based; money is signed zoned with the sign folded into the last byte and no byte for
the decimal point, per master sections 3.3 and 3.7. **This program reads no monetary field at all** --
the decoded amounts below are recorded for completeness, not as expectations.

`dailytran.txt`, one `DALYTRAN-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DALYTRAN-ID` | 0 | 16 | `0000000000683580` | the identifier the skip message would carry -- not reached here |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `01` | unread |
| `DALYTRAN-CAT-CD` | 18 | 4 | `0001` | unread; unsigned, plain digits |
| `DALYTRAN-SOURCE` | 22 | 10 | `POS TERM` + 2 spaces | unread |
| `DALYTRAN-DESC` | 32 | 100 | `Purchase at Abshire-Lowe` + 76 spaces | unread |
| `DALYTRAN-AMT` | 132 | 11 | `0000005047G` | `+504.77` -- unread |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | unread |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | unread |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | unread |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | unread |
| `DALYTRAN-CARD-NUM` | 262 | 16 | **`4859452612877065`** | the key `:171` moves and `:229` **finds** |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `2022-06-10 19:27:53.000000` | unread |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

Only two fields of this record are read by the program: the card number at `:171`, and the identifier
at `:183` on the branch this scenario does not take. Everything else is displayed as part of the whole
record at `:168` and otherwise ignored.

`cardxref.txt`, one `CARD-XREF-RECORD` -- all three of its identifiers are DISPLAYed at `:236-238`:

| Field | Offset | Width | Bytes |
|---|---:|---:|---|
| `XREF-CARD-NUM` | 0 | 16 | `4859452612877065` |
| `XREF-CUST-ID` | 16 | 9 | `000000007` |
| `XREF-ACCT-ID` | 25 | 11 | `00000000007` |
| `FILLER` | 36 | 14 | 14 spaces |

`acctdata.txt`, one `ACCOUNT-RECORD` for account `00000000007`: status `Y`, `ACCT-CURR-BAL`
`00000001930{` = `+193.00`, `ACCT-CREDIT-LIMIT` `00000020650{` = `+2065.00`, `ACCT-CASH-CREDIT-LIMIT`
`00000002640{` = `+264.00`, open `2012-10-12`, `ACCT-EXPIRAION-DATE` `2024-12-13`, reissue
`2024-12-13`, both cycle fields `+0.00`, `ACCT-ADDR-ZIP` `A000000000`, `ACCT-GROUP-ID` ten spaces,
`FILLER` 178 spaces.

**Not one field of this record is read.** `3000-READ-ACCOUNT` reads the row by key and DISPLAYs a fixed
marker; it inspects no field of what it read. The row's only job is to **exist** under key
`00000000007`, so every value in it is inert -- including the blank `ACCT-GROUP-ID`, which is the
interest domain's disclosure-group key (master section 7.2.3) and has no meaning here.

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` line 11 spells it, preserved
verbatim because this is copybook-side naming; master section 9.3 confines the three spelling
corrections to target column names.

**`FILLER` bytes: both files here pad with `0x20` SPACE**, which is what master section 6.1 measures
for the ACCOUNT, input-DALYTRAN and CARD-XREF records. **No file in this folder carries the ASCII-`'0'`
padding** that the category-balance and disclosure-group records use, because neither record type
appears in this domain -- so master section 6.2's two exceptions do not arise here.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| The folder holds three record files where the program opens six datasets | master section 2 | `CUSTFILE`, `CARDFILE` and `TRANFILE` are opened and never read (section 3), so a fixture for them would assert nothing |
| The domain is named `preflight` where the house tree names it `prepost` | master section 4.2 | Recorded at the head of this document; the two denote the same program and neither is wrong |

There is **no** line-ending normalization to declare in this folder: neither of its two seed sources
ships `CRLF`, so the `CR`-stripping the posting scenarios perform on their category-balance row has no
counterpart here. **No business-rule field is reshaped either** -- section 9 attests every row.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input data** -- the
feed record has not been processed yet. Master section 8.1 requires this to be stated every time a
blank timestamp is shown, because the byte pattern is identical to the one normalisation produces and
the meaning cannot be recovered from the bytes. It is the same reason the migrated
`ledger.daily_transactions.proc_ts` column is nullable while `ledger.transactions.proc_ts` is
`NOT NULL`.

**Neither timestamp field is read, written or normalised anywhere in this domain.** `CBTRN01C` writes
no record at all, so master section 8.1's posting and interest rules -- assert the originating stamp,
mask the processing stamp -- do not engage. The field appears in this document only because the
scenario's feed record carries it and master section 8.1 requires its meaning to be stated whenever it
is shown.

The one non-deterministic-looking property of this domain is the **doubled** console output of section
2.2, and it is deterministic too: it is exactly two lookups for one record on every run, not a race.
Everything else holds by construction -- every byte here is literal, there is no clock value, no random
identifier and no environment-derived string, and each test provisions and tears down its own
workspace, per master section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated pass is `job/PreflightDailyTransactionsJob`, and four of its contracts constrain what this
folder may expect:

- **`START_BANNER` and `END_BANNER`** are `START OF EXECUTION OF PROGRAM CBTRN01C` and
  `END OF EXECUTION OF PROGRAM CBTRN01C`, verbatim from `:156` and `:195`.
- **The clean tier is unconditional.** An unresolvable record is a diagnostic, not a rejection, so the
  pass has no warn tier to report -- which is the target-side expression of section 3's observation that
  `CBTRN01C` never assigns `RETURN-CODE`.
- **The pass writes no row and mutates no row**, because every dataset is read-only. This scenario's
  negative expectation is therefore checkable as a row count rather than only as an absence of
  diagnostics.
- **One inspection per record read.** The target implements the clean loop rather than the baseline's
  repeated final pass (section 2.2), so the doubled markers are a baseline characteristic that the
  target does not reproduce and that no test asserts a count for.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql)
-- `ledger.daily_transactions` for the feed, `account.card_xref` and `account.accounts` for the two
lookups. A scenario owns only its own rows and never seeds another service's schema, per master section
11.3.

The graded rubric 0, 4, 8 belongs to the COBOL parity suite alone (master section 7.1.6). The tier-0
expectation here is the pass's own outcome, not a build status.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs. Unlike the
posting and interest domains, this one has **no golden corpus at all** in either tree -- the program
produces console diagnostics rather than records, so its expectation is stated as marker presence and
absence in section 3. `tests/**` remains reference-only and no golden is ever regenerated.

### 8.2 The oracle constants do not apply here

Master section 7.1.7's constants -- 300 daily records, 262 posted, 38 rejected, a conservation total,
50 category keys becoming 100 -- are `CBTRN02C` figures for the full seed cycle. **None applies to this
domain**: this program posts nothing, rejects nothing and touches no category balance. The only figures
that transfer are the record geometries, and they agree: the 350-byte feed record and the 300-byte
account record are the same layouts.

### 8.3 The sibling folders

`../unmatched_card` breaks the **first** link -- its cross-reference holds a different card -- and
`../unmatched_account` breaks the **second** -- its cross-reference resolves correctly but its account
master holds a different account. Each ships the same three files at the same widths; only which row is
copied differs. Read the three together: this folder shows what a clean record looks like, and the two
siblings show the two ways the chain can fail and which diagnostic each produces.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived, and no business-rule field is reshaped
anywhere in it.** Every record is a published AWS CardDemo sample seed row under `app/data/ASCII/`,
selected rather than edited:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | **byte-identical** |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 7 | **byte-identical** |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | 21 | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |

The cross-reference row is row **21** of its seed rather than row 7 because that file is ordered by card
number, and account `00000000007` appears there under card `4859452612877065`.

**It represents no real person and no real account.** The seeds ship with the upstream open-source
project as fabricated demonstration data, and master section 11.1 carries the tree-level attestation
this scenario inherits. Identity and primary-account-number bytes -- the card number, the customer
identifier and the account identifier named throughout this document -- are taken unchanged from the
seed.

**Every business-rule field reshaped away from its seed value: none.** The matched path needs no
reshaping at all, because the seeds are already internally consistent -- card `4859452612877065`
resolves to account `00000000007` in the seed cross-reference, and the seed account master holds that
account. The scenario is the seeds' own default state, which is the strongest provenance position a
fixture in this tree can occupy. The single normalization named in section 5.4 is width conformance,
**not** a business-rule field change, so it does not qualify this statement.

Trade-offs: this folder's three files are byte-identical to their equivalents in the reference-only
`tests/fixtures/prepost/happy_path/`, and that duplication is deliberate. Master section 4.2 records
that the house tree is reference-only and that this module's tests must read fixtures from this tree, so
the alternative would be for the module's tests to reach across into `tests/**` -- coupling a Java test
to a path the COBOL suite owns and could reorganise, and blurring which tree a failure belongs to. The
cost accepted is duplicated bytes; what is bought is one owner per file.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string or
endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the COBOL
baseline or the parity oracle.

---

*This README is the mandatory Explainability carrier for the three record files in this directory,
required by master section 10 and by user-specified Rule 1. `config/rule1/rule1_gate.py` decides the
form of the rationale labels above, repository-wide and including Markdown, which is why they are
written plain rather than emphasised. `config/checkstyle/checkstyle.xml` limits its audit set to
`java`, so no linter reads this prose. Whether each rationale names a real consequence, and whether
every number and line citation is true, are review obligations no lexical gate can decide.*

---

## 10. What drives this corpus, and what reads it

This corpus is a **reference mirror**, not a driven input: no test in this module seeds a run from
`/fixtures/preflight/`. What reads it is `BatchFixtureContractTest`, which enumerates this scenario
among all sixteen fixture trees and holds every file here to its declared geometry and to the values
that make the scenario discriminating -- so an edit is detected in this module even though no job
consumes the bytes. The end-to-end run for this rule belongs to the reference suite.

Assumptions: the distinction from `posting/**` is deliberate and is stated rather than left to
inference. That family IS driven -- `PostTransactionsJobParityIT` declares
`/fixtures/posting/` as its seed root and compares against `tests/golden/posting/<scenario>` -- so
one tree serves two purposes, and only there does an edit change what a run asserts.
