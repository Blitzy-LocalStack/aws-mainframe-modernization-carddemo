# `preflight/unmatched_card` -- the card is not in the cross-reference, so the account is never read

> **Purpose.** Pin the card-not-verified path of the pre-posting preflight pass: a daily transaction
> whose card is **absent** from the cross-reference is reported as unverifiable, the account read is
> **skipped entirely** by the guard the reference places between the two lookups, nothing is changed, and
> the pass still ends in the clean return-code tier. It is the first half of a pair with
> `../unmatched_account`, which breaks the second link of the same chain.
>
> **Source of truth.** `app/cbl/CBTRN01C.cbl` for the behaviour, cited below by line;
> `app/cpy/CVTRA06Y.cpy`, `app/cpy/CVACT03Y.cpy` and `app/cpy/CVACT01Y.cpy` for the record layouts; the
> seed datasets under `app/data/ASCII/` for the bytes; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document cites by section rather
> than restating (master section 1.3). **There is no JCL driver to cite**: master section 4.1 records
> that no member of `app/jcl` executes this program at all.
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per
> `docs/CODE_DOCUMENTATION_STANDARD.md` and master section 1.4. This whole file is pure ASCII for the
> reason that section gives.

**This README is the mandatory Explainability carrier for the three record files beside it.** Master
section 1.2 records why: a fixed-width record file cannot carry a comment of any kind, because every byte
position is meaningful and a comment on its own line is a physical row of the wrong length. Master
section 10 makes the artifact mandatory rather than courteous, and the section order below is the one it
fixes.

This tree calls the domain **`preflight/`** where the reference-only house tree calls it **`prepost/`**.
Master section 4.2 records that the two names denote the same `CBTRN01C` behaviour and that neither is
wrong; nobody should correct one to the other.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction on card **`4859452612877065`**, against a cross-reference holding a single row for
a **different** card, **`0927987108636232`**. The lookup misses.

Three things must hold at once:

- the cross-reference lookup **misses**, so the card-not-verified line is emitted;
- the account read is **not performed at all**, because the guard at `:173` admits it only when the
  cross-reference read succeeded -- and the account master in this folder **does** hold a resolvable row,
  so the skip is genuine rather than vacuous;
- the pass still ends **clean** and changes nothing -- an unverifiable card is a **report**, not a
  rejection and not a failure.

**The absence of the account read is this folder's principal assertion**, and it is the reason the
account master here is populated rather than empty. Against an empty master a test could not tell "the
read was skipped" from "the read happened and found nothing", and those are different behaviours with
different diagnostics.

Alternatives Considered: producing the miss by shipping an **empty** `cardxref.txt` rather than a
populated one naming a different card. Rejected because an empty cross-reference cannot distinguish "the
card was looked up and not found" from "the dataset was never loaded", and in this domain the second
failure is especially easy to miss: there is no return code and no reject stream to change, so a harness
that never loaded the file would produce **exactly** this scenario's expected diagnostics and pass. A
populated file holding one non-matching row proves the dataset loaded, the read executed, and the key
genuinely did not match.

---

## 2. The business rule, cited by program and line

### 2.1 The failing lookup and the guard that skips the second

`app/cbl/CBTRN01C.cbl:164-186` is the loop. For this record:

| Step | Line | What happens |
|---|---|---|
| Read the feed | `:166`, `:202-225` | the `READ` at `:203` returns `'00'`, so the terminating flag stays `'N'` |
| Display the record | `:167-169` | the inner guard is true, so `:168` DISPLAYs the whole `DALYTRAN-RECORD` |
| Compose the card key | `:170-171` | `WS-XREF-READ-STATUS` is cleared and `DALYTRAN-CARD-NUM` is moved into `XREF-CARD-NUM` |
| **Cross-reference lookup MISSES** | `:172`, `:227-239` | `2000-LOOKUP-XREF` reads by `FD-XREF-CARD-NUM` (`:228-230`) and takes **`INVALID KEY`** (`:231`), so `:232` DISPLAYs `INVALID CARD NUMBER FOR XREF` and `:233` moves **4** into `WS-XREF-READ-STATUS` |
| **Guard fails** | `:173`, `:180` | `IF WS-XREF-READ-STATUS = 0` is **false**, so the block at `:174-179` -- including the account read at `:176` -- is **not entered**; control takes the `ELSE` at `:180` |
| **The skip diagnostic** | `:181-183` | three pieces: `'CARD NUMBER '`, then `DALYTRAN-CARD-NUM`, then `' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'`, then `DALYTRAN-ID` |
| Leave the loop | `:186` | the next iteration reaches end of file; see section 2.2 |
| Close and finish | `:188-197` | six closes, the end banner at `:195`, `GOBACK` at `:197` |

**Two things about the skip message must be reproduced exactly.** Its middle fragment ends
`ID-` -- a hyphen with **no trailing space** -- so the transaction identifier abuts it directly:
`... SKIPPING TRANSACTION ID-0000000000683580`. And `'CARD NUMBER '` carries a **trailing** space while
`' COULD NOT BE VERIFIED...'` carries a **leading** one, because `:181-183` concatenates the pieces with
no separator of its own. Inserting the space a reader expects after `ID-` would change published text.

**The identifier in the message is the transaction's, not the account's.** `DALYTRAN-ID` is the only
field of the feed record this branch names, and no account identifier is available on it at all -- the
cross-reference read that would have supplied one failed.

### 2.2 The loop performs the failing lookup twice for one record

The inner `IF END-OF-DAILY-TRANS-FILE = 'N'` opened at `:167` closes at **`:169`**, so it guards **only**
the record display at `:168`. The lookup block at `:170-184` sits **outside** it and inside the outer `IF`
at `:165`. On the second iteration the read at `:166` reaches end of file and sets the flag, the display
is skipped -- and the lookup block runs **again**, against the card number still sitting in
`DALYTRAN-RECORD`. So for one input record **both** of this scenario's diagnostics appear **twice**.

Assumptions: this is a control-flow characteristic of the immutable baseline, not a defect to work
around. It has no effect on data -- every dataset is opened `INPUT` -- so the repeated read changes
nothing and produces no output beyond the duplicated console lines. **An expectation must assert on
marker presence, never on a repetition count**, which is the rule the reference-only
`tests/fixtures/prepost/unmatched_card/README.md` records for the house tree as well. Note the
interaction with this scenario in particular: because the guard at `:173` fails on both passes, the
account read is skipped **twice**, so "the account was never read" holds for the whole run and not merely
for the first iteration. The migrated pass implements the clean loop instead and its class documentation
records that divergence.

---

## 3. Returns -- the expected outcome

The return code is **0**. **`CBTRN01C` never assigns `RETURN-CODE` anywhere**, so an unverifiable card
leaves it at zero. That is the distinction this domain turns on: **preflight reports, posting rejects.**
The same missing-cross-reference condition becomes reject reason **100** with the message
`INVALID CARD NUMBER FOUND` and a return code of **4** in `CBTRN02C` -- master section 7.1.1 -- and
`../../posting/reject_100_card_missing` is the folder for that. Nothing about this scenario may be
expressed as a warn tier or a reject.

**What must appear.**

| Marker | Line | Times, for one input record |
|---|---|---|
| `START OF EXECUTION OF PROGRAM CBTRN01C` | `:156` | once |
| the whole `DALYTRAN-RECORD`, displayed | `:168` | once -- inside the guard that closes at `:169` |
| **`INVALID CARD NUMBER FOR XREF`** | `:232` | twice, per section 2.2 |
| **`CARD NUMBER 4859452612877065 COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-0000000000683580`** | `:181-183` | twice |
| `END OF EXECUTION OF PROGRAM CBTRN01C` | `:195` | once |

**What must not appear.**

| Absent marker | Line | Why it cannot occur |
|---|---|---|
| `SUCCESSFUL READ OF XREF` | `:235` | the read at `:229` takes `INVALID KEY`, so the `NOT INVALID KEY` arm is not entered |
| `CARD NUMBER: ...`, `ACCOUNT ID : ...`, `CUSTOMER ID: ...` | `:236-238` | the same arm -- these three lines are the successful read's detail and share its branch |
| `SUCCESSFUL READ OF ACCOUNT FILE` | `:249` | `3000-READ-ACCOUNT` is never performed |
| `INVALID ACCOUNT NUMBER FOUND` | `:246` | the same paragraph, never performed |
| `ACCOUNT ... NOT FOUND` | `:178` | inside the block the guard at `:173` does not enter |

**The account master is present and is never read, and both halves of that are assertions.** The row for
account `00000000007` sits in `acctdata.txt` and would resolve if anything asked for it; nothing does.
Asserting only the message would pass even for an implementation that read the master first and discarded
the answer, so the assertion is the **absence of the read** -- and it is meaningful only because the row
exists.

**No data changes.** All six datasets are opened `INPUT` -- `:254` `DALYTRAN`, `:273` `CUSTFILE`, `:291`
`XREFFILE`, `:309` `CARDFILE`, `:327` `ACCTFILE`, `:345` `TRANFILE` -- and the program contains **no**
`WRITE`, `REWRITE` or `DELETE` statement of any kind. In particular the missing cross-reference row is
**not created**.

**Three of the six datasets are opened and never read**, and in this scenario a **fourth** joins them:
`CUSTFILE`, `CARDFILE` and `TRANFILE` are never read by the program at all -- opened at `:158`, `:160`,
`:162` and closed at `:189`, `:191`, `:193` -- and here `ACCTFILE` is opened at `:161`, closed at `:192`
and also never read, because the guard skips its only reader. That is why the account row's contents are
entirely inert in this folder while the row's **existence** still matters.

Assumptions: this domain has **no golden corpus in this tree's shape**, and none is asserted here. The
program's output is console diagnostics rather than records, so the expectation is stated as marker
presence and absence above rather than as a byte comparison against an `*.expected` file. The
reference-only house tree states the same expectation for the same program in the same terms.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**The missing cross-reference row must not abend the run.** This is the scenario's central negative.
`2000-LOOKUP-XREF` (`:227-239`) has **no abend site at all**: its `INVALID KEY` arm DISPLAYs and sets a
status field, and that is the whole of it. An implementation treating a missing cross-reference row as an
I/O error would abend here while handling the matched path correctly.

**The account read must not happen.** An implementation that read the account master regardless -- for
example one that resolved card to account with a **join** and inspected both results afterwards -- would
still report an unverifiable card and would still be wrong about this, and the difference would appear in
no console line. It is checkable only as the absence of the read, which is why the target-side contract in
section 7 states it as a verified interaction rather than as a message.

**The account diagnostics must not appear.** Both `:246` and `:178` are inside code the guard skips.
Emitting either would mean the account path ran.

**The pass must not fail or warn the chain.** Section 3 states the return code; a failure here would stop
the nightly chain on a condition the baseline treats as informational, and posting would then never run to
reject the record properly as reason 100.

**No abend occurs.** `CBTRN01C` uses `Z-ABEND-PROGRAM` and `Z-DISPLAY-IO-STATUS` rather than the numbered
paragraphs `CBTRN02C` uses, and every site is status-guarded: the six open paragraphs beginning at `:252`,
`:271`, `:289`, `:307`, `:325` and `:343`, each abending at `:266`, `:285`, `:303`, `:321`, `:339` and
`:357`; the feed read at `:222`, reached only when the status is neither `'00'` nor `'10'`; and the six
close paragraphs beginning at `:361`, abending at `:375`, `:393`, `:411`, `:429`, `:447` and `:465`. A run
whose opens and closes all return `'00'` and whose feed read returns `'00'` then `'10'` reaches none of
them.

---

## 5. Parameters -- the files in this folder and their byte geometry

### 5.1 Inventory

| File | Copybook | RECLN | DDNAME / `ASSIGN` | Organization | Key |
|---|---|---:|---|---|---|
| `dailytran.txt` | `app/cpy/CVTRA06Y.cpy` | 350 | `DALYTRAN` | SEQUENTIAL | -- |
| `cardxref.txt` | `app/cpy/CVACT03Y.cpy` | 50 | `XREFFILE` | INDEXED | `XREF-CARD-NUM`, offset 0, length 16 |
| `acctdata.txt` | `app/cpy/CVACT01Y.cpy` | 300 | `ACCTFILE` | INDEXED | `ACCT-ID`, offset 0, length 11 |
| `README.md` | -- | -- | -- | -- | this file |

The `ASSIGN` names are declared at `app/cbl/CBTRN01C.cbl:29-58`; the record lengths are the summed widths
of master section 5.1, never a `RECLN` banner. This program reads the cross-reference **by card number**
at offset 0 -- master section 4.3 -- and that is precisely the access path this scenario makes fail.

### 5.2 On-disk sizes and line endings

| File | Size | Records | Bytes per record | `CR` bytes | Trailing newline |
|---|---:|---:|---:|---:|---|
| `dailytran.txt` | **351** | 1 | 350 | 0 | exactly one `LF` |
| `cardxref.txt` | **51** | 1 | 50 | 0 | exactly one `LF` |
| `acctdata.txt` | **301** | 1 | 300 | 0 | exactly one `LF` |

One record plus a single trailing `LF` per file, per master sections 3.8 and 3.9, and **zero `CR` bytes**
in the folder.

```bash
# WHAT: assert the byte geometry, and additionally assert the two relations this scenario turns on -- that
#       the feed's card does NOT match the cross-reference key, and that the account master nevertheless
#       holds a row, so the skipped read is genuine. Run from this directory.
# WHY : Assumptions: this scenario has two independent ways to stop testing what it claims, and in this
#       domain neither changes a return code or a record count -- only which console line appears. If the
#       card starts matching, the folder silently becomes ../happy_path; if the account master were empty
#       or absent, the "the read was skipped" assertion would hold vacuously and would pass equally for an
#       implementation that read the master and found nothing. Asserting both at authoring time is what
#       keeps the claim in section 3 substantive.
python3 - <<'PY'
EXPECTED = {"dailytran.txt": (351, 350), "cardxref.txt": (51, 50),
            "acctdata.txt": (301, 300)}
rows_of = {}
for name, (size, width) in EXPECTED.items():
    raw = open(name, "rb").read()
    rows = [r for r in raw.split(b"\n") if r]
    rows_of[name] = rows
    widths = sorted({len(r) for r in rows})
    print(name, "size", len(raw), "expected", size, "| rows", len(rows), widths,
          "| CR", raw.count(b"\r"),
          "| OK" if len(raw) == size and raw.count(b"\r") == 0 and widths == [width]
          else "| MISMATCH")
feed_card = rows_of["dailytran.txt"][0][262:278].decode("ascii")
xref_key = rows_of["cardxref.txt"][0][0:16].decode("ascii")
print("feed card", feed_card, "cross-reference key", xref_key,
      "| OK the lookup misses" if feed_card != xref_key
      else "| MISMATCH nothing would be reported")
print("account master rows", len(rows_of["acctdata.txt"]),
      "holding", rows_of["acctdata.txt"][0][0:11].decode("ascii"),
      "| OK the skipped read is genuine" if rows_of["acctdata.txt"]
      else "| MISMATCH the skip assertion would be vacuous")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based; money is signed zoned with the sign folded into the last byte and no byte for the
decimal point, per master sections 3.3 and 3.7. **This program reads no monetary field at all.**

`dailytran.txt`, one `DALYTRAN-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DALYTRAN-ID` | 0 | 16 | **`0000000000683580`** | the identifier the skip message carries, from `:183` |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `01` | unread |
| `DALYTRAN-CAT-CD` | 18 | 4 | `0001` | unread; unsigned, plain digits |
| `DALYTRAN-SOURCE` | 22 | 10 | `POS TERM` + 2 spaces | unread |
| `DALYTRAN-DESC` | 32 | 100 | `Purchase at Abshire-Lowe` + 76 spaces | unread |
| `DALYTRAN-AMT` | 132 | 11 | `0000005047G` | `+504.77` -- unread |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | unread |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | unread |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | unread |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | unread |
| `DALYTRAN-CARD-NUM` | 262 | 16 | **`4859452612877065`** | the key `:171` moves, `:229` fails to find, and `:182` reports |
| `DALYTRAN-ORIG-TS` | 278 | 26 | `2022-06-10 19:27:53.000000` | unread |
| `DALYTRAN-PROC-TS` | 304 | 26 | 26 spaces | see section 6 |
| `FILLER` | 330 | 20 | 20 spaces | input-DALYTRAN padding |

**This is the only preflight scenario in which two fields of the feed record reach the output.** The card
number and the transaction identifier both appear in the skip message, so both are asserted values rather
than inert bytes -- everywhere else in this domain the feed record's fields are merely displayed as part
of the whole-record dump at `:168`.

`cardxref.txt`, one `CARD-XREF-RECORD` -- the row that does **not** match:

| Field | Offset | Width | Bytes |
|---|---:|---:|---|
| `XREF-CARD-NUM` | 0 | 16 | **`0927987108636232`** |
| `XREF-CUST-ID` | 16 | 9 | `000000020` |
| `XREF-ACCT-ID` | 25 | 11 | `00000000020` |
| `FILLER` | 36 | 14 | 14 spaces |

**None of this row's three identifiers is ever read**, because the read that would have loaded them
failed. The row exists only so that the failing read has a populated dataset to fail against, per section
1.

`acctdata.txt`, one `ACCOUNT-RECORD` for account `00000000007`: status `Y`, `ACCT-CURR-BAL`
`00000001930{` = `+193.00`, `ACCT-CREDIT-LIMIT` `00000020650{` = `+2065.00`, `ACCT-CASH-CREDIT-LIMIT`
`00000002640{` = `+264.00`, open `2012-10-12`, `ACCT-EXPIRAION-DATE` `2024-12-13`, reissue `2024-12-13`,
both cycle fields `+0.00`, `ACCT-ADDR-ZIP` `A000000000`, `ACCT-GROUP-ID` ten spaces, `FILLER` 178 spaces.

**This row is never read either, and its purpose is precisely that.** It is the same account row
`../happy_path` uses, so the two folders differ in exactly one file -- and it makes the skipped read
genuine rather than vacuous, as sections 1 and 3 explain. Note the asymmetry with the cross-reference row
above: that row is unread because the read **failed**, while this one is unread because the read **never
happened**, and the second is the assertion.

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` line 11 spells it, preserved verbatim
because this is copybook-side naming; master section 9.3 confines the three spelling corrections to
target column names. The blank `ACCT-GROUP-ID` is the interest domain's disclosure-group key (master
section 7.2.3) and is inert here.

**`FILLER` bytes: both files here pad with `0x20` SPACE**, which is what master section 6.1 measures for
the ACCOUNT, input-DALYTRAN and CARD-XREF records. No file in this folder carries the ASCII-`'0'` padding
of the category-balance or disclosure-group records, because neither record type appears in this domain --
so master section 6.2's two exceptions do not arise here.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| The cross-reference deliberately omits the feed record's card | master section 8 | Master section 8 permits a key to be deliberately omitted to trigger a diagnostic and requires the README to name it. Card `4859452612877065` is absent by design |
| The account master holds a row no lookup asks for | master section 8 | The deliberate counterpart of the omission: it is what makes the skipped read genuine rather than vacuous (sections 1 and 3) |
| The folder holds three record files where the program opens six datasets | master section 2 | `CUSTFILE`, `CARDFILE` and `TRANFILE` are opened and never read (section 3), so a fixture for them would assert nothing |
| The domain is named `preflight` where the house tree names it `prepost` | master section 4.2 | Recorded at the head of this document; the two denote the same program |

There is **no** line-ending normalization to declare: neither seed source ships `CRLF`. **No
business-rule field is reshaped either** -- section 9 attests every row.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces, and in this input file those spaces are genuine input data** -- the
feed record has not been processed yet. Master section 8.1 requires this to be stated every time a blank
timestamp is shown, because the byte pattern is identical to the one normalisation produces and the
meaning cannot be recovered from the bytes. It is the same reason the migrated
`ledger.daily_transactions.proc_ts` column is nullable while `ledger.transactions.proc_ts` is `NOT NULL`.

**Neither timestamp field is read, written or normalised anywhere in this domain**, because `CBTRN01C`
writes no record at all. Master section 8.1's posting and interest rules therefore do not engage; the field
appears here only because the feed record carries it and master section 8.1 requires its meaning to be
stated whenever it is shown.

The doubled diagnostics of section 2.2 are deterministic too: exactly two failing lookups for one record
on every run, not a race. Everything else holds by construction -- every byte is literal, there is no
clock value, no random identifier and no environment-derived string, and each test provisions and tears
down its own workspace, per master section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated pass is `job/PreflightDailyTransactionsJob`, and four of its contracts constrain what this
folder may expect:

- **An unresolvable card is a diagnostic, never a rejected row**, and the pass has **no warn tier to
  report at all** -- the target-side expression of section 3's observation that `CBTRN01C` never assigns
  `RETURN-CODE`.
- **The reported card number is masked to its last four digits.** The line is emitted at warning level
  and so reaches durable log storage, and rendering all sixteen digits would put a primary account number
  there, which the migration plan's data-exposure rule forbids. The template around it is verbatim from
  `:181-183`, including the middle fragment's terminal `ID-` with no trailing space, so the transaction
  identifier abuts it exactly as the baseline renders it. This is a **documented, deliberate
  qualification** of the verbatim-text rule and the one place in this pass where it is knowingly
  qualified.
- **The account lookup is asserted never to be issued.** This is the target-side form of section 3's
  claim: the assertion is on the **absence** of the interaction, not on the message, because a message
  assertion alone would pass for an implementation that read the master first and discarded the answer.
  The account identifier is not even available on this branch.
- **The account master row is asserted to be present**, so the skip is genuine. A row count of one on
  `account.accounts` alongside zero lookups is the pair of facts that makes the claim substantive.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql)
-- `ledger.daily_transactions` for the feed, `account.card_xref` for the failing lookup and
`account.accounts` for the row that is never read. A scenario owns only its own rows and never seeds
another service's schema, per master section 11.3.

The graded rubric 0, 4, 8 belongs to the COBOL parity suite alone (master section 7.1.6). The tier-0
expectation here is the pass's own outcome, not a build status.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs. Unlike the
posting and interest domains, this one has **no golden corpus at all** in either tree -- the program
produces console diagnostics rather than records, so its expectation is stated as marker presence and
absence in section 3. `tests/**` remains reference-only and no golden is ever regenerated.

### 8.2 The posting scenario this must not be confused with

`../../posting/reject_100_card_missing` is the **same broken chain link** driven through `CBTRN02C`
instead, and it is the folder whose expectation is a reject: code `0100`, the message
`INVALID CARD NUMBER FOUND`, a 430-byte reject record and a return code of **4**. Its fixture uses the
same two seed rows this one does -- cross-reference row 4 and account row 7 -- and adds a category-balance
file the preflight program has no dataset for. **The two scenarios differ in which program reads them**,
and that difference changes the outcome from a console diagnostic to a persisted reject. Master section
7.1.7's oracle constants belong to that folder's program, not to this one: this pass posts nothing,
rejects nothing and touches no category balance, so none of them applies here.

### 8.3 The sibling folders

`../unmatched_account` breaks the **second** link of the chain -- its cross-reference resolves correctly
and its account master holds a different account -- and `../happy_path` breaks neither. Each ships the
same three files at the same widths; only which seed row is copied differs, and this folder differs from
`../happy_path` in **one file** and from `../unmatched_account` in **two**. Read the three together: they
cover the two ways the chain can fail and the case where it does not, and only the set of three shows
that each diagnostic is reported for its own condition and no other.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived, and no business-rule field is reshaped anywhere
in it.** Every record is a published AWS CardDemo sample seed row under `app/data/ASCII/`, selected rather
than edited:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | **byte-identical** |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | 7 | **byte-identical** |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | **4** | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |

**The scenario is created by selection, not by reshaping.** The cross-reference row is row **4** of its
seed -- card `0927987108636232`, customer `000000020`, account `00000000020` -- chosen because it is a
real seed row naming a card the feed record does not carry. `../happy_path` and `../unmatched_account`
both use row 21, which is where card `4859452612877065` appears. Swapping which seed row is copied is the
entire construction, and it is why the phrase "every business-rule field reshaped away from its seed
value" resolves to **none** for this folder.

**It represents no real person and no real account.** The seeds ship with the upstream open-source project
as fabricated demonstration data, and master section 11.1 carries the tree-level attestation this scenario
inherits. Identity and primary-account-number bytes -- **both** card numbers, both customer identifiers
and both account identifiers named in this document -- are taken unchanged from the seed.

The single normalization named in section 5.4 is width conformance, **not** a business-rule field change,
so it does not qualify the "nothing reshaped" statement above.

Trade-offs: this folder's card numbers differ between its feed record and its cross-reference row, which
looks at first glance like an inconsistency in the fixture. It is the design, and stating it in three
places -- section 1, section 5.3 and here -- is the accepted cost of a construction whose whole point is a
key that does not resolve. The alternative of reshaping the feed record's card number to a value no seed
row carries was rejected for the reason master section 11.2 gives for the tree as a whole: an invented
sixteen-digit value is indistinguishable from a live one by inspection, so its provenance would have to
be attested rather than pointed at, whereas both numbers here are published seed values.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string or
endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the COBOL
baseline or the parity oracle.

---

*This README is the mandatory Explainability carrier for the three record files in this directory,
required by master section 10 and by user-specified Rule 1. `config/rule1/rule1_gate.py` decides the form
of the rationale labels above, repository-wide and including Markdown, which is why they are written
plain rather than emphasised. `config/checkstyle/checkstyle.xml` limits its audit set to `java`, so no
linter reads this prose. Whether each rationale names a real consequence, and whether every number and
line citation is true, are review obligations no lexical gate can decide.*

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
