# `preflight/unmatched_account` -- the card resolves, and its account is not there

> **Purpose.** Pin the account-not-found path of the pre-posting preflight pass: a daily transaction
> whose card **is** in the cross-reference but whose resolved account is **absent** from the account
> master reaches the second of the program's two diagnostic branches, is **reported** rather than
> rejected, changes nothing, and still ends in the clean return-code tier. It is the second half of a
> pair with `../unmatched_card`, which breaks the first link of the same chain.
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

This tree calls the domain **`preflight/`** where the reference-only house tree calls it **`prepost/`**.
Master section 4.2 records that the two names denote the same `CBTRN01C` behaviour and that neither is
wrong; nobody should correct one to the other.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction on card `4859452612877065`. The cross-reference **does** hold that card and
resolves it to account **`00000000007`**. The account master holds a single row for a **different**
account, **`00000000020`**. The first lookup succeeds, the second fails.

Three things must hold at once:

- the cross-reference lookup **succeeds**, so the guard at `:173` admits the account read rather than
  taking the skip branch;
- the account lookup **misses**, so both of the account-not-found lines are emitted;
- the pass still ends **clean** and still changes nothing -- an unresolvable account is a **report**,
  not a rejection and not a failure.

Alternatives Considered: shipping an **empty** `acctdata.txt` rather than a populated one naming a
different account. Rejected because an empty master cannot distinguish "the account was looked up and
not found" from "the dataset was never loaded", and in this domain the second failure is especially
easy to miss: there is no return code and no reject stream to change, so a harness that never loaded
the file would produce **exactly** this scenario's expected diagnostics and pass. A populated file
holding one non-matching row proves the master loaded, the read executed, and the key genuinely did not
match.

Assumptions: the ordering of the two outcomes is a property of the program rather than of the data.
`:173` performs the account read **only** when the cross-reference read succeeded, so reaching the
account-not-found branch at all is itself evidence that the card resolved. A fixture in which both
links were broken would report only the card diagnostic, never this one -- the two are not independent
conditions that could both be reported for one record.

---

## 2. The business rule, cited by program and line

### 2.1 The successful first lookup and the failing second

`app/cbl/CBTRN01C.cbl:164-186` is the loop. For this record:

| Step | Line | What happens |
|---|---|---|
| Read the feed | `:166`, `:202-225` | the `READ` at `:203` returns `'00'`, so the terminating flag stays `'N'` |
| Display the record | `:167-169` | the inner guard is true, so `:168` DISPLAYs the whole `DALYTRAN-RECORD` |
| Compose the card key | `:170-171` | `WS-XREF-READ-STATUS` is cleared and `DALYTRAN-CARD-NUM` is moved into `XREF-CARD-NUM` |
| **Cross-reference lookup HITS** | `:172`, `:227-239` | `2000-LOOKUP-XREF` reads by `FD-XREF-CARD-NUM` (`:228-230`) and takes `NOT INVALID KEY` (`:234`), so `:235-238` DISPLAY `SUCCESSFUL READ OF XREF` and the three identifiers, and the status stays 0 |
| Guard passes | `:173` | `IF WS-XREF-READ-STATUS = 0` is true, so the account read is performed |
| Compose the account key | `:174-175` | `WS-ACCT-READ-STATUS` is cleared and `XREF-ACCT-ID` -- `00000000007` -- is moved into `ACCT-ID` |
| **Account lookup MISSES** | `:176`, `:241-250` | `3000-READ-ACCOUNT` reads by `FD-ACCT-ID` (`:242-244`) and takes **`INVALID KEY`** (`:245`), so `:246` DISPLAYs `INVALID ACCOUNT NUMBER FOUND` and `:247` moves **4** into `WS-ACCT-READ-STATUS` |
| **The second diagnostic** | `:177-179` | `IF WS-ACCT-READ-STATUS NOT = 0` is now true, so `:178` DISPLAYs `ACCOUNT ` then `ACCT-ID` then ` NOT FOUND` |
| Leave the loop | `:186` | the next iteration reaches end of file; see section 2.2 |
| Close and finish | `:188-197` | six closes, the end banner at `:195`, `GOBACK` at `:197` |

**Two diagnostics are emitted, not one, and they come from different places.** `:246` is inside
`3000-READ-ACCOUNT` and reports that a read failed; `:178` is in the main loop and reports **which**
account was not found, because the paragraph that discovered the failure does not have the identifier
in a form it prints. An expectation naming only one of the two is incomplete.

**The `:178` line is three pieces with no separator of its own**, so the spaces belong to the literals:
`'ACCOUNT '` carries a **trailing** space and `' NOT FOUND'` carries a **leading** one, with the
eleven-character `ACCT-ID` between them. The rendered line for this scenario is
`ACCOUNT 00000000007 NOT FOUND`. Adding or removing a space would change published text.

### 2.2 The loop performs both lookups twice for one record

The inner `IF END-OF-DAILY-TRANS-FILE = 'N'` opened at `:167` closes at **`:169`**, so it guards **only**
the record display at `:168`. The lookup block at `:170-184` sits **outside** it and inside the outer
`IF` at `:165`. On the second iteration the read at `:166` reaches end of file and sets the flag, the
display is skipped -- and the lookup block runs **again**, against the card number still sitting in
`DALYTRAN-RECORD`. So for one input record **both** diagnostics of this scenario appear **twice**.

Assumptions: this is a control-flow characteristic of the immutable baseline, not a defect to work
around. It has no effect on data -- every dataset is opened `INPUT` -- so the repeated reads change
nothing and produce no output beyond the duplicated console lines. **An expectation must assert on
marker presence, never on a repetition count**, which is the rule the reference-only
`tests/fixtures/prepost/unmatched_account/README.md` records for the house tree as well. The migrated
pass implements the clean loop instead and its class documentation records that divergence.

---

## 3. Returns -- the expected outcome

The return code is **0**. **`CBTRN01C` never assigns `RETURN-CODE` anywhere**, so an unresolvable record
leaves it at zero. That is the distinction this domain turns on: **preflight reports, posting rejects.**
The same missing-account condition becomes reject reason **101** with the message
`ACCOUNT RECORD NOT FOUND` and a return code of **4** in `CBTRN02C` -- master section 7.1.1 -- and
`../../posting/reject_101_acct_missing` is the folder for that. Nothing about this scenario may be
expressed as a warn tier or a reject.

**What must appear.**

| Marker | Line | Times, for one input record |
|---|---|---|
| `START OF EXECUTION OF PROGRAM CBTRN01C` | `:156` | once |
| the whole `DALYTRAN-RECORD`, displayed | `:168` | once -- inside the guard that closes at `:169` |
| `SUCCESSFUL READ OF XREF` | `:235` | twice, per section 2.2 |
| `CARD NUMBER: 4859452612877065` | `:236` | twice |
| `ACCOUNT ID : 00000000007` | `:237` | twice |
| `CUSTOMER ID: 000000007` | `:238` | twice |
| **`INVALID ACCOUNT NUMBER FOUND`** | `:246` | twice |
| **`ACCOUNT 00000000007 NOT FOUND`** | `:178` | twice |
| `END OF EXECUTION OF PROGRAM CBTRN01C` | `:195` | once |

**What must not appear.**

| Absent marker | Line | Why it cannot occur |
|---|---|---|
| `SUCCESSFUL READ OF ACCOUNT FILE` | `:249` | the read at `:243` takes `INVALID KEY`, so the `NOT INVALID KEY` arm is not entered |
| `INVALID CARD NUMBER FOR XREF` | `:232` | the cross-reference read succeeds |
| `CARD NUMBER ... COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-...` | `:181-183` | reached only when `WS-XREF-READ-STATUS` is not 0, and here it is 0 |

**The account identifier reported is the one the cross-reference resolved, not the one the master
holds.** `:178` prints `ACCT-ID`, which `:175` loaded from `XREF-ACCT-ID`, so the line names
`00000000007` -- the account that is **missing** -- and never `00000000020`, the account that happens to
be present. That is the whole diagnostic value of the line, and it is the assertion most easily got
backwards.

**No data changes.** All six datasets are opened `INPUT` -- `:254` `DALYTRAN`, `:273` `CUSTFILE`,
`:291` `XREFFILE`, `:309` `CARDFILE`, `:327` `ACCTFILE`, `:345` `TRANFILE` -- and the program contains
**no** `WRITE`, `REWRITE` or `DELETE` statement of any kind. In particular the missing account is **not
created**: a preflight pass has no mechanism to create anything.

**Three of the six datasets are opened and never read.** `CUSTFILE`, `CARDFILE` and `TRANFILE` are
opened at `:158`, `:160` and `:162` and closed at `:189`, `:191` and `:193`; the program's only three
`READ` statements are the feed at `:203`, the cross-reference at `:229` and the account at `:243`. That
is why this folder ships **three** record files rather than six.

Assumptions: this domain has **no golden corpus in this tree's shape**, and none is asserted here. The
program's output is console diagnostics rather than records, so the expectation is stated as marker
presence and absence above rather than as a byte comparison against an `*.expected` file. The
reference-only house tree states the same expectation for the same program in the same terms.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**The missing account must not abend the run.** This is the scenario's central negative.
`3000-READ-ACCOUNT` (`:241-250`) has **no abend site at all**: its `INVALID KEY` arm DISPLAYs and sets a
status field, and that is the whole of it. An implementation treating a missing account as an I/O error
would abend here while handling the matched path correctly.

**The pass must not fail or warn the chain.** Section 3 states the return code; the reason it matters is
that a failure here would stop the nightly chain on a condition the baseline treats as informational,
and posting would then never run to reject the record properly.

**The card diagnostic must not appear.** An implementation that inverted the guard at `:173` -- testing
`NOT = 0` where the baseline tests `= 0` -- would skip the account read on this resolvable card and emit
`../unmatched_card`'s skip message instead. The two folders together are what distinguish a correct
guard from an inverted one; neither does it alone.

**Only the first of the two diagnostics must not be emitted alone.** `:246` reports that a read failed
and `:178` reports which account; an implementation that emitted the paragraph's line but not the
loop's, or vice versa, would still look like a not-found report to a loose assertion. Section 3 lists
both.

**No abend occurs.** `CBTRN01C` uses `Z-ABEND-PROGRAM` and `Z-DISPLAY-IO-STATUS` rather than the
numbered paragraphs `CBTRN02C` uses, and every site is status-guarded: the six open paragraphs
beginning at `:252`, `:271`, `:289`, `:307`, `:325` and `:343`, each abending at `:266`, `:285`, `:303`,
`:321`, `:339` and `:357`; the feed read at `:222`, reached only when the status is neither `'00'` nor
`'10'`; and the six close paragraphs beginning at `:361`, abending at `:375`, `:393`, `:411`, `:429`,
`:447` and `:465`. A run whose opens and closes all return `'00'` and whose feed read returns `'00'`
then `'10'` reaches none of them.

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
widths of master section 5.1, never a `RECLN` banner. This program reads the cross-reference **by card
number** at offset 0, exactly as posting does; master section 4.3 records that the account-keyed
alternate path belongs to the interest domain and nothing here uses it.

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
#       that the feed's card DOES match the cross-reference key, and that the account it resolves to
#       does NOT match the account master's key. Run from this directory.
# WHY : Assumptions: this scenario needs one lookup to hit and the next to miss, so it has two
#       independent ways to degrade, and in this domain neither one changes a return code or a record
#       count -- only which console line appears. If the card stops matching, the expectation becomes
#       the skip message of ../unmatched_card; if the account starts matching, both diagnostics vanish
#       and the folder silently becomes ../happy_path. Asserting both relations at authoring time is
#       the only cheap way to tell three scenarios apart that differ in no byte count at all.
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
      else "| MISMATCH the skip message would be reported instead")
print("resolved account", resolved, "account master holds", held,
      "| OK the account lookup misses" if resolved != held
      else "| MISMATCH nothing would be reported")
PY
```

### 5.3 Field values, as observed

Offsets are zero-based; money is signed zoned with the sign folded into the last byte and no byte for
the decimal point, per master sections 3.3 and 3.7. **This program reads no monetary field at all.**

`dailytran.txt`, one `DALYTRAN-RECORD`: `DALYTRAN-ID` `0000000000683580` at 0, type `01` at 16,
category `0001` at 18, source `POS TERM` plus two spaces at 22, description
`Purchase at Abshire-Lowe` plus 76 spaces at 32, `DALYTRAN-AMT` `0000005047G` = `+504.77` at 132,
merchant identifier `800000000` at 143, merchant name `Abshire-Lowe` plus 38 spaces at 152, merchant
city `North Enoshaven` plus 35 spaces at 202, merchant ZIP `72112` plus five spaces at 252,
**`DALYTRAN-CARD-NUM` `4859452612877065` at 262** -- the key `:171` moves and `:229` **finds** --
`DALYTRAN-ORIG-TS` `2022-06-10 19:27:53.000000` at 278, `DALYTRAN-PROC-TS` 26 spaces at 304 (see
section 6), and 20 spaces of `FILLER` at 330.

`cardxref.txt`, one `CARD-XREF-RECORD` -- the row that **matches**, and whose three identifiers are all
DISPLAYed at `:236-238`:

| Field | Offset | Width | Bytes |
|---|---:|---:|---|
| `XREF-CARD-NUM` | 0 | 16 | `4859452612877065` |
| `XREF-CUST-ID` | 16 | 9 | `000000007` |
| `XREF-ACCT-ID` | 25 | 11 | **`00000000007`** -- the key `:175` moves, `:243` fails to find, and `:178` reports |
| `FILLER` | 36 | 14 | 14 spaces |

`acctdata.txt`, one `ACCOUNT-RECORD` -- the row that does **not** match:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | **`00000000020`** | account 20, not the resolved 7 |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | unread |
| `ACCT-CURR-BAL` | 12 | 12 | `00000003690{` | `+369.00` -- unread |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000037670{` | `+3767.00` -- unread |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000010400{` | `+1040.00` -- unread |
| `ACCT-OPEN-DATE` | 48 | 10 | `2014-02-27` | unread |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `2024-03-13` | unread |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2024-03-13` | unread |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` | `+0.00` -- unread |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` | `+0.00` -- unread |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | unread |
| `ACCT-GROUP-ID` | 112 | 10 | 10 spaces | unread -- the interest domain's key, inert here |
| `FILLER` | 122 | 178 | 178 spaces | -- |

**Not one field of this record is read, and its only job is to exist under a key the lookup does not
ask for.** `3000-READ-ACCOUNT` reads by key and DISPLAYs a fixed marker; it inspects nothing. The row's
values are therefore entirely inert -- but they are not interchangeable with account 7's, and the
difference is useful: `+369.00` and `+3767.00` appearing anywhere in a diagnostic is immediate evidence
that this folder's account row was read when it should not have been, whereas the familiar `+193.00`
and `+2065.00` of account 7 would be indistinguishable from the sibling folders' at a glance.

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` line 11 spells it, preserved verbatim
because this is copybook-side naming; master section 9.3 confines the three spelling corrections to
target column names.

**`FILLER` bytes: both files here pad with `0x20` SPACE**, which is what master section 6.1 measures for
the ACCOUNT, input-DALYTRAN and CARD-XREF records. No file in this folder carries the ASCII-`'0'`
padding of the category-balance or disclosure-group records, because neither record type appears in this
domain -- so master section 6.2's two exceptions do not arise here.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the seed omits the trailing `FILLER X(14)`. Authored at full copybook width with that `FILLER` space-padded |
| The account master deliberately omits the resolved account | master sections 8, 9.1 | Master section 8 permits a key to be deliberately omitted to trigger a fallback or a reject and requires the README to name it. Account `00000000007` is absent by design |
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
`ledger.daily_transactions.proc_ts` column is nullable while `ledger.transactions.proc_ts` is
`NOT NULL`.

**Neither timestamp field is read, written or normalised anywhere in this domain**, because `CBTRN01C`
writes no record at all. Master section 8.1's posting and interest rules therefore do not engage; the
field appears here only because the feed record carries it and master section 8.1 requires its meaning
to be stated whenever it is shown.

The doubled diagnostics of section 2.2 are deterministic too: exactly two lookups for one record on
every run, not a race. Everything else holds by construction -- every byte is literal, there is no clock
value, no random identifier and no environment-derived string, and each test provisions and tears down
its own workspace, per master section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated pass is `job/PreflightDailyTransactionsJob`, and four of its contracts constrain what this
folder may expect:

- **An unresolvable record is a diagnostic, never a rejected row.** The pass reports it and completes,
  and it has **no warn tier to report at all** -- the target-side expression of section 3's observation
  that `CBTRN01C` never assigns `RETURN-CODE`.
- **The reported account identifier is redacted.** The line is emitted at warning level and so reaches
  durable log storage, so the target renders `ACCOUNT *********** NOT FOUND` -- **eleven** redaction
  characters, the width `ACCT-ID` is declared at, `PIC 9(11)` at `app/cpy/CVACT01Y.cpy:5` -- so a
  positional reader still finds the field at its declared width. The two surrounding literals are
  verbatim, including the trailing space of `ACCOUNT ` and the leading space of ` NOT FOUND`. This is a
  **documented, deliberate qualification** of the verbatim-text rule, taken because the alternative is
  writing an account identifier into a durable log.
- **The account lookup is performed, and that is asserted.** Unlike `../unmatched_card`, where the
  absence of the read is the assertion, here the read must **happen** and must be issued for the
  resolved identifier.
- **One inspection per record read.** The target implements the clean loop rather than the baseline's
  repeated final pass (section 2.2), so the doubled markers are a baseline characteristic the target
  does not reproduce and no test asserts a count for.

Assumptions: the module's own test reaches this scenario's outcome **the other way round** from this
fixture, and the difference is worth recording so neither reads as a mistake. This folder resolves the
card to account `00000000007` and commits an account master holding `00000000020`; the test commits a
cross-reference resolving to an identifier **no** account row carries. Both produce the same finding
from the same branch -- the account read misses -- and the second form needs no second committed image.
The fixture keeps the house tree's construction because it is the form the reference-only
`tests/fixtures/prepost/unmatched_account/` uses, so one description covers both trees.

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

### 8.2 The posting scenario this must not be confused with

`../../posting/reject_101_acct_missing` is the **same broken chain link** driven through `CBTRN02C`
instead, and it is the folder whose expectation is a reject: code `0101`, the message
`ACCOUNT RECORD NOT FOUND`, a 430-byte reject record and a return code of **4**. Its fixture even uses
the same two seed rows this one does. **The two scenarios differ only in which program reads them**, and
that difference changes the outcome from a console diagnostic to a persisted reject. Master section
7.1.7's oracle constants belong to that folder's program, not to this one: this pass posts nothing,
rejects nothing and touches no category balance, so none of them applies here.

### 8.3 The sibling folders

`../unmatched_card` breaks the **first** link of the chain -- its cross-reference holds a different card
-- and `../happy_path` breaks neither. Each ships the same three files at the same widths; only which
seed row is copied differs. Read the three together: they cover the two ways the chain can fail and the
case where it does not, and only the set of three shows that each diagnostic is reported for its own
condition and no other.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived, and no business-rule field is reshaped anywhere
in it.** Every record is a published AWS CardDemo sample seed row under `app/data/ASCII/`, selected
rather than edited:

| File | Seed | Seed row | Relationship |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | 1 | **byte-identical** |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | 21 | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | **20** | **byte-identical** |

**The scenario is created by selection, not by reshaping.** The account row is row **20** of its seed --
account `00000000020` -- chosen because it is a real seed row naming an account the cross-reference does
not resolve to. `../happy_path` and `../unmatched_card` both use row 7, account `00000000007`. Swapping
which seed row is copied is the entire construction, and it is why the phrase "every business-rule field
reshaped away from its seed value" resolves to **none** for this folder.

The cross-reference row is row **21** of its seed rather than row 7 because that file is ordered by card
number, and account `00000000007` appears there under card `4859452612877065`.

**It represents no real person and no real account.** The seeds ship with the upstream open-source
project as fabricated demonstration data, and master section 11.1 carries the tree-level attestation
this scenario inherits. Identity and primary-account-number bytes -- the card number, the customer
identifier and both account identifiers named in this document -- are taken unchanged from the seed.

The single normalization named in section 5.4 is width conformance, **not** a business-rule field
change, so it does not qualify the "nothing reshaped" statement above.

Trade-offs: selecting seed row 20 rather than reshaping row 7's account identifier to something absent
costs one thing worth naming. Row 20 differs from row 7 in every monetary field and every date, so this
folder's account row shares almost no bytes with its siblings' -- a reader diffing two preflight
fixtures sees a large difference where the scenario's actual delta is the key alone. The compromise is
accepted because the alternative is worse in kind: overwriting an account identifier produces a row
whose key names an account no seed row describes, so its provenance could no longer be stated as a seed
row and the eleven bytes would have to be justified as invented. Section 5.3 turns the cost into a
benefit by naming the unfamiliar values as a diagnostic signal.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string or
endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the COBOL
baseline or the parity oracle.

---

*This README is the mandatory Explainability carrier for the three record files in this directory,
required by master section 10 and by user-specified Rule 1. `config/rule1/rule1_gate.py` decides the
form of the rationale labels above, repository-wide and including Markdown, which is why they are
written plain rather than emphasised. `config/checkstyle/checkstyle.xml` limits its audit set to `java`,
so no linter reads this prose. Whether each rationale names a real consequence, and whether every number
and line citation is true, are review obligations no lexical gate can decide.*

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
