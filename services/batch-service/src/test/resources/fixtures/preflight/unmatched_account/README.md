# `preflight/unmatched_account` -- the card resolves, and its account is not there

> **Purpose.** Pin the account-not-found path of the pre-posting preflight pass: a daily transaction
> whose card **is** in the cross-reference but whose resolved account is **absent** from the account
> master reaches the second of the program's two diagnostic branches, is **reported** rather than
> rejected, changes nothing, and still ends in the clean return-code tier. It is the second half of a
> pair with [`../unmatched_card`](../unmatched_card), which breaks the first link of the same chain.
>
> **Source of truth.** `app/cbl/CBTRN01C.cbl` for the behaviour, cited below by line;
> `app/cpy/CVTRA06Y.cpy`, `app/cpy/CVACT03Y.cpy` and `app/cpy/CVACT01Y.cpy` for the record layouts; the
> seed datasets under `app/data/ASCII/` for the bytes; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document cites by section
> rather than restating (master section 1.3). **There is no JCL driver to cite**: master section 4.1
> records that no member of `app/jcl` executes this program at all, which is why this folder is
> hand-authored rather than lifted from a job's dataset list.
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup. That form is fixed by
> `docs/CODE_DOCUMENTATION_STANDARD.md` and by master section 1.4. `config/rule1/rule1_gate.py`
> enforces it repository-wide -- its `labels` check fails an emphasis-wrapped label -- but not here:
> that gate excludes every path containing `/src/test/resources/fixtures/`, so the form in this file
> is held by review alone.
>
> **Reference-only sources.** `app/**`, `tests/**` and `scripts/**` are read here and never modified.
> Where the migrated pass behaves differently from the reference, this document says so and names the
> registered divergence; it does not characterise the reference as wrong.

**This README is the mandatory Explainability carrier for the three record files beside it.** Master
section 1.2 records why: a fixed-width record file cannot carry a comment of any kind, because every
byte position is meaningful and a comment on its own line is a physical row of the wrong length. Master
section 10 makes the artifact mandatory rather than courteous, and fixes the order of the five items
this document must state. Nothing in the migration plan asks for a README at this path; user-specified
Rule 1 (Explainability) is the whole reason it exists, and its validation gate means an absent or
unjustified rationale here fails review.

Assumptions: this tree calls the domain **`preflight/`** where the reference-only house tree calls it
**`prepost/`** (`tests/fixtures/prepost/**`), and neither name is wrong. Master section 4.2 rules the
correspondence: the AAP and this module name the job **`preflight-daily-transactions`**, so this tree
matches the job name a reader will find in the module's sources and in the batch state machine, while
the house tree keeps its own older name and is reference-only in any case. The consequence worth naming
is navigational -- a reader looking for the house analogue of this folder must look under `prepost/`,
and a reader who "corrects" either name breaks the cross-tree trail without changing a byte.

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

The four lines the successful first lookup emits are reproduced here character for character, because
two of them space their labels differently and the difference is published text rather than a typo to
tidy:

| Line | Literal, verbatim | Value appended |
|---|---|---|
| `:235` | `SUCCESSFUL READ OF XREF` | -- |
| `:236` | `CARD NUMBER: ` | `XREF-CARD-NUM` |
| `:237` | `ACCOUNT ID : ` | `XREF-ACCT-ID` |
| `:238` | `CUSTOMER ID: ` | `XREF-CUST-ID` |

`:237` carries a **space before its colon** where `:236` and `:238` do not. The three labels are
thereby padded to one width, so the values line up in a console log. An expectation that normalises the
spacing would stop matching the program's output.

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

Assumptions: this is verified control-flow behaviour of the reference program, which is REFERENCE-ONLY
and is not altered. It has no effect on data -- every dataset is opened `INPUT` and the program contains
no write verb of any kind -- so the repeated reads change nothing and produce no output beyond the
duplicated console lines. The consequence for anyone writing an expectation against this folder is
exact: **assert marker presence and absence, never a repetition count.** A test pinned to "twice" would
encode the reference loop shape, and the migrated pass inspects each record once, so that test would
fail against the target while asserting nothing extra about the reference. The migrated pass carries
this as divergence **D-7**, registered as `D-PREFLIGHT-LOOKUP-PAST-END-OF-FILE`; its own class
documentation is the authority on it and this document does not restate it.

### 2.3 Two data-names are declared twice in the FILE SECTION

`CBTRN01C` declares six FDs, and two data-names appear in more than one of them. Both are noted because
a reader mapping the program's record layouts by name alone will find two answers for each and may take
the collision for an error.

Assumptions: `FD-CUST-DATA` is declared **twice** -- at `:69` in the DALYTRAN FD as `PIC X(334)`, and at
`:74` in the CUSTOMER FD as `PIC X(491)`. The two are unrelated: the first is the remainder of a
350-byte feed record after its 16-byte key, and the second the remainder of a 500-byte customer record
after its 9-byte key. Nothing here is ambiguous to the compiler, which resolves each inside its own
record description; the collision exists only in a flat symbol table, so **layouts must be scoped per
copybook** rather than looked up by data-name. A reader who resolves `FD-CUST-DATA` to the wrong one of
the two mis-sizes a record by 157 bytes.

Assumptions: `FD-ACCT-DATA` is likewise declared **twice** -- at `:89` in the ACCOUNT FD as
`PIC X(289)`, and at `:94` in the TRANSACT FD as `PIC X(334)`. This pair matters more than the first
one here, because **this scenario's failing read is the one declared against the ACCOUNT FD at
`:86-89`**: `05 FD-ACCT-ID PIC 9(11)` plus `05 FD-ACCT-DATA PIC X(289)` sums to exactly **300**, which
is the account record length this folder's `acctdata.txt` is authored at. Resolving `FD-ACCT-DATA` to
the TRANSACT FD's `X(334)` instead would make the account record appear to be 345 bytes and would put
every field offset in section 5.3 out by the difference.

---

## 3. Returns -- the expected outcome

The return code is **0**. **`CBTRN01C` declares no `RETURN-CODE` statement anywhere in its 494 lines,
no reject stream and no counters**, so an unresolvable record leaves the code at zero. That is the
distinction this domain turns on: **preflight reports, posting rejects.**

Assumptions: the graded tier contract -- `0` clean, `4` soft warn, `>= 8` hard failure, with only
`PostTransactionsJob` able to emit `4` -- is owned by `com.carddemo.batch.job.package-info` and is
referenced here rather than redefined, so that one statement of it governs every job in the module. What
this folder contributes to it is the *reason* the preflight pass can never reach the warn tier: there is
no assignment in the reference for the migrated pass to carry across. Expressing anything about this
scenario as a warn tier or a reject would contradict both the reference and that contract.

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
| `SUCCESSFUL READ OF ACCOUNT FILE` | `:249` | the read at `:243` takes `INVALID KEY`, so the `NOT INVALID KEY` arm at `:248` is never entered. The account read did not succeed, and this is the line that would claim it did |
| `INVALID CARD NUMBER FOR XREF` | `:232` | the card **was** verified: the cross-reference read at `:229` took `NOT INVALID KEY`. This marker belongs to [`../unmatched_card`](../unmatched_card), whose cross-reference holds a different card |
| `CARD NUMBER ... COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-...` | `:181-183` | the `ELSE` arm at `:180` is reached only when `WS-XREF-READ-STATUS` is not 0, and here it is 0. Again the card was verified, so the transaction is not skipped -- it is carried through to the account read |

**The account identifier reported is the one the cross-reference resolved, not the one the master
holds.** `:178` prints `ACCT-ID`, which `:175` loaded from `XREF-ACCT-ID`, so the line names
`00000000007` -- the account that is **missing** -- and never `00000000020`, the account that happens to
be present. That is the whole diagnostic value of the line, and it is the assertion most easily got
backwards.

**No data changes.** All six datasets are opened `INPUT` -- `:254` `DALYTRAN`, `:273` `CUSTFILE`,
`:291` `XREFFILE`, `:309` `CARDFILE`, `:327` `ACCTFILE`, `:345` `TRANFILE` -- and the program contains
**no** `WRITE`, `REWRITE` or `DELETE` statement of any kind. In particular the missing account is **not
created**: a preflight pass has no mechanism to create anything.

**Three of the six datasets are opened and never read.** The program's only three `READ` statements are
the feed at `:203`, the cross-reference at `:229` and the account at `:243`; `CUSTFILE`, `CARDFILE` and
`TRANFILE` are opened at `:273`, `:309` and `:345` and closed again without being read.

Trade-offs: those three datasets therefore need only be **openable**, and the paired test supplies
**empty valid indexed files carrying the correct key geometry** rather than three more byte images in
this folder. The compromise accepted is that this folder does not document a complete picture of every
dataset the program touches -- a reader counting files here finds three where the program opens six, and
section 5.4 names that departure. What is bought is worth more: three additional record files would be
dead weight that no `READ` ever reaches, and each would still owe the full provenance attestation of
section 9, so the tree would carry three unfalsifiable governance claims to explain bytes nothing
consumes. An empty file with the right key geometry proves exactly the property the program depends on,
which is that the `OPEN INPUT` succeeds.

Assumptions: this domain has **no golden corpus in this tree's shape**, and none is asserted here. The
program's output is console diagnostics rather than records, so the expectation is stated as marker
presence and absence above rather than as a byte comparison against an `*.expected` file.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

**The missing account must not abend the run.** This is the scenario's central negative. An unmatched
account is a normal not-found report, not an I/O error: `3000-READ-ACCOUNT` (`:241-250`) has **no abend
site at all**, its `INVALID KEY` arm being a DISPLAY at `:246` and a status move at `:247` and nothing
else. An implementation treating a missing account as an I/O error would abend here while handling the
matched path correctly, which is why the negative is stated rather than left implied.

**The program's only failure exit is `Z-ABEND-PROGRAM` at `:469-473`**, which DISPLAYs
`ABENDING PROGRAM`, moves 999 into the abend code and calls `CEE3ABD`. Every path to it is guarded by a
file status, and none of those guards can trip on this scenario's data:

| Guarded site | Lines | Reached only when |
|---|---|---|
| the six `OPEN INPUT` paragraphs | begin at `:252`, `:271`, `:289`, `:307`, `:325`, `:343` | an open returns a status other than `'00'` |
| the feed read | `:222` | the read status is neither `'00'` nor `'10'` |
| the six close paragraphs | begin at `:361` | a close returns a status other than `'00'` |

A run whose six opens and six closes all return `'00'`, and whose feed read returns `'00'` for the one
record and then `'10'` at end of file, reaches none of them. **A missing account key is not a file
status**, so the account read's `INVALID KEY` arm is a normal branch and not an error path.

**The pass must not fail or warn the chain.** Section 3 states the tier; the reason it matters is that a
failure here would stop the nightly chain on a condition the reference treats as informational, and
posting would then never run to reject the record properly.

**The card diagnostic must not appear.** An implementation that inverted the guard at `:173` -- testing
`NOT = 0` where the reference tests `= 0` -- would skip the account read on this resolvable card and emit
[`../unmatched_card`](../unmatched_card)'s skip message instead. The two folders together are what
distinguish a correct guard from an inverted one; neither does it alone.

**Neither of the two diagnostics may be emitted without the other.** `:246` reports that a read failed
and `:178` reports which account; an implementation emitting the paragraph's line but not the loop's, or
the reverse, would still look like a not-found report to a loose assertion. Section 3 requires both.

Assumptions: the `INVALID KEY` arm at `:245` depends on the account file being a genuine indexed file
opened successfully. Master section 3.12 requires indexed inputs to be pre-sorted by key, which one row
satisfies trivially, but the consequence worth naming is the failure mode this scenario cannot
distinguish on its own: if `acctdata.txt` failed to load at all, the read would still miss and the same
two diagnostics would appear. That is precisely why section 1 rejects an empty account master, and why
the verification script in section 5.2 asserts the account master's key rather than only its geometry.

---

## 5. Parameters -- the files in this folder and their byte geometry

### 5.1 Inventory

| File | Copybook | RECLN | DDNAME / `ASSIGN` | Organization | Key | Role in this scenario |
|---|---|---:|---|---|---|---|
| `dailytran.txt` | `app/cpy/CVTRA06Y.cpy` | 350 | `DALYTRAN` | SEQUENTIAL | -- | its card resolves through the cross-reference to account `00000000007` |
| `cardxref.txt` | `app/cpy/CVACT03Y.cpy` | 50 | `XREFFILE` | INDEXED | `XREF-CARD-NUM`, offset 0, length 16 | the indexed lookup that **HITS**, yielding `XREF-ACCT-ID` = `00000000007` |
| `acctdata.txt` | `app/cpy/CVACT01Y.cpy` | 300 | `ACCTFILE` | INDEXED | `ACCT-ID`, offset 0, length 11 | the indexed lookup that **MISSES** -- it holds unrelated account `00000000020` |
| `README.md` | -- | -- | -- | -- | -- | this file |

The `ASSIGN` names are declared at `app/cbl/CBTRN01C.cbl:29-58`; the record lengths are the summed
widths of master section 5.1, never a `RECLN` banner. Indexed inputs are pre-sorted by key per master
section 3.12, which one row per file satisfies trivially.

**Two distinct keys, two distinct outcomes.** The cross-reference read uses the **primary** xref key --
card number, offset 0, length 16 -- and must hit. The account read uses `ACCT-ID` at offset 0, length 11,
and must miss. Master section 4.3 records that the account-keyed alternate cross-reference path belongs
to the interest domain; **nothing here reads the cross-reference by account.** `XREF-ACCT-ID` at offset
25 appears in this scenario only as the field `:175` reads the resolved account id *out of*, never as a
key anything is looked up *by*.

Assumptions: `DALYTRAN-RECORD` is field-for-field identical to `CVTRA05Y` at the same copybook line
numbers, 5 through 18, with only the `DALYTRAN-` prefix distinguishing the names -- which is why master
section 5.2 tabulates one field list under both copybook names. The offsets this folder depends on are
therefore the shared ones: `CARD-NUM` at **262**, `ORIG-TS` at **278**, `PROC-TS` at **304** and the
trailing `FILLER` at **330**, the widths summing to exactly 350 as
`16+2+4+10+100+11+9+50+50+10+16+26+26+20`. The consequence is that a reader may verify this feed record
against either copybook and must not treat the two as separate layouts needing separate verification.

### 5.2 On-disk sizes and line endings

| File | Size | Records | Bytes per record | `CR` bytes | `NUL` bytes | Trailing newline |
|---|---:|---:|---:|---:|---:|---|
| `dailytran.txt` | **351** | 1 | 350 | 0 | 0 | exactly one `LF` |
| `cardxref.txt` | **51** | 1 | 50 | 0 | 0 | exactly one `LF` |
| `acctdata.txt` | **301** | 1 | 300 | 0 | 0 | exactly one `LF` |

One record plus a single trailing `LF` per file, per master sections 3.8 and 3.9, so `wc -l` returns 1
for each file and equals its record count. There are **zero `CR` bytes and zero `NUL` bytes** in the
folder.

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
          "| CR", raw.count(b"\r"), "| NUL", raw.count(b"\x00"),
          "| OK" if len(raw) == size and raw.count(b"\r") == 0
          and raw.count(b"\x00") == 0 and widths == [width]
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
the decimal point, per master sections 3.3 and 3.7. The overpunch alphabet is the master's and is not
reproduced here. **This program reads no monetary field at all.**

`dailytran.txt`, one `DALYTRAN-RECORD`: `DALYTRAN-ID` `0000000000683580` at 0, type `01` at 16,
category `0001` at 18, source `POS TERM` plus two spaces at 22, description
`Purchase at Abshire-Lowe` plus 76 spaces at 32, `DALYTRAN-AMT` `0000005047G` at 132 decoding to
`+504.77`, merchant identifier `800000000` at 143, merchant name `Abshire-Lowe` plus 38 spaces at 152,
merchant city `North Enoshaven` plus 35 spaces at 202, merchant ZIP `72112` plus five spaces at 252,
**`DALYTRAN-CARD-NUM` `4859452612877065` at 262** -- the key `:171` moves and `:229` **finds** --
`DALYTRAN-ORIG-TS` `2022-06-10 19:27:53.000000` at 278, `DALYTRAN-PROC-TS` 26 spaces at 304 whose
meaning section 6 states, and 20 spaces of `FILLER` at 330.

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
that this folder's account row was read when it should not have been, whereas the `+193.00` and
`+2065.00` of account 7 would be indistinguishable from the sibling folders' values at a glance.

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` spells it, preserved verbatim because
this is copybook-side naming; master section 9.3 confines the three spelling corrections to target
column names, where this field becomes `expiration_date`.

Assumptions: every padding byte in this folder is **SPACE (`0x20`), never `NUL`** -- the `FILLER X(20)`
of the feed record at 330, the `FILLER X(178)` of the account record at 122 together with its blank
`ACCT-GROUP-ID` at 112, and the widened `FILLER X(14)` of the cross-reference record at 36. That is what
master section 6.1 **measures** for the ACCOUNT, input-DALYTRAN and CARD-XREF records, and master
section 6 is explicit that the measured table **overrides** the general padding rule of section 3.2
wherever the two disagree. The consequence of getting it wrong is silent: a `NUL`-padded row is the
right length and the wrong bytes, so it loads and then mismatches on comparison rather than failing at
load. No file here carries the ASCII-`'0'` padding that master section 6.2 measures for the
category-balance and disclosure-group records, because neither record type appears in this domain.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed's 36 | master section 3.10 | The copybook sums to 50; the shipped seed measures 36 because the trailing `FILLER X(14)` is absent from the seed text. Authored at full copybook width with that `FILLER` space-padded |
| The account master deliberately omits the resolved account | master section 8 | Master section 8 permits a key to be **deliberately** omitted to trigger a reject or a fallback and requires the scenario README to name it. Account `00000000007` is absent by design; section 9 states it in full |
| The folder holds three record files where the program opens six datasets | master section 2 | `CUSTFILE`, `CARDFILE` and `TRANFILE` are opened and never read, per the `Trade-offs:` note in section 3 |
| The domain is named `preflight` where the house tree names it `prepost` | master section 4.2 | Recorded at the head of this document; the two names denote the same `CBTRN01C` behaviour |

Assumptions: the 50-byte width above is a deliberate choice between two forms the master permits, not an
inherited property of the seed. Master section 3.10 rules that either the full copybook width or the
shipped 36-byte seed geometry may be authored, provided the scenario names which it uses; **this folder
uses the full 50-byte copybook width and no 36-byte variant appears here.** The consequence of the
choice is that a byte-for-byte diff of this file against its seed row shows 14 trailing bytes of
difference that carry no value, so section 9 states the widening explicitly rather than leaving a reader
to conclude the row was edited. Had a 36-byte variant been wanted -- to exercise a reader against the
seed's own geometry -- that would have been a different departure requiring its own statement here.

There is **no** line-ending normalization to declare: neither seed source ships `CRLF`, so master
section 3.8 is satisfied without a conversion step.

---

## 6. Determinism -- and what the blank timestamp means here

**`DALYTRAN-PROC-TS` is 26 spaces at offset 304, and in this input file those spaces are genuine input
data.** The feed record has not been processed yet, so the field is legitimately blank rather than
missing or erroneous.

Assumptions: master section 8.1 requires that meaning to be stated every time a blank timestamp is
shown, because the byte pattern is identical to the one timestamp normalisation produces and the meaning
cannot be recovered from the bytes alone. This document therefore fixes it once, here, for every place
26 spaces appear in it: **the 26 spaces in this folder are unprocessed-input blanks, never normalised
output.** The migrated schema encodes the same distinction structurally -- `ledger.daily_transactions`
declares `proc_ts` **nullable** while `ledger.transactions` declares it `NOT NULL` -- in the sibling
harness
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql),
whose own rationale records that a posted row has been processed by definition whereas a pre-posting
feed row has not. `DALYTRAN-ORIG-TS` at 278, by contrast, is deterministic author-supplied input data
carrying a real value.

**Neither timestamp field is read, written or normalised anywhere in this domain**, because `CBTRN01C`
writes no record at all. Master section 8.1's posting and interest normalisation rules therefore do not
engage; the field appears here only because the feed record carries it.

The doubled diagnostics of section 2.2 are deterministic too: exactly two lookups for one record on
every run, not a race. Everything else holds by construction -- every byte is literal, there is no clock
value, no random identifier and no environment-derived string, and each test provisions and tears down
its own workspace, per master section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated pass is `job/PreflightDailyTransactionsJob`, the bean `preflight-daily-transactions` and
state 3 of the nightly chain. It is read-only and validation-only, emits a diagnostic per missing account
and writes no record. Four of its contracts constrain what this folder may expect; each is named here and
specified there, not restated:

- **An unresolvable record is a diagnostic, never a rejected row.** The pass reports it and completes,
  and it has **no warn tier to report at all** -- the target-side expression of section 3's observation
  that `CBTRN01C` contains no `RETURN-CODE` statement.
- **The reported account identifier is redacted.** The line is emitted at warning level and so reaches
  durable log storage, so the target renders `ACCOUNT *********** NOT FOUND` -- **eleven** redaction
  characters, the width `ACCT-ID` is declared at as `PIC 9(11)` -- so a positional reader still finds the
  field at its declared width. The two surrounding literals are verbatim, including the trailing space of
  `ACCOUNT ` and the leading space of ` NOT FOUND`. This is a documented, deliberate qualification of the
  verbatim-text rule, taken because the alternative is writing an account identifier into a durable log.
- **The account lookup is performed, and that is asserted.** Unlike [`../unmatched_card`](../unmatched_card),
  where the absence of the read is the assertion, here the read must **happen** and must be issued for
  the resolved identifier.
- **One inspection per record read.** The target implements the single-pass loop, so the doubled markers
  of section 2.2 are a reference characteristic the target does not reproduce and no test asserts a count
  for. The divergence is registered as `D-PREFLIGHT-LOOKUP-PAST-END-OF-FILE`.

The bytes decode through the shared codecs in
`services/common-lib/src/main/java/com/carddemo/common/codec/` -- `CopybookLayout`, `FixedWidthCodec` and
`ZonedDecimalCodec` -- and through the module's `DailyTransactionMapper`, `CardXrefRecordMapper` and
`AccountRecordMapper`. Two of their decisions matter to a reader of these bytes: the unsigned `PIC 9(n)`
identifiers here are decoded as `CopybookLayout.Kind.UINT` and never through sign overpunch, so the final
byte of `XREF-ACCT-ID` is the digit it looks like; and the account record's five 12-byte zoned money
fields decode to `NUMERIC(12,2)`, which is why section 5.3 can state their values exactly.

The rows land in the objects the sibling harness declares -- `ledger.daily_transactions` for the feed,
`account.card_xref` for the cross-reference with `card_num CHAR(16)` as its primary key, and
`account.accounts` for the master with `account_id BIGINT` as its primary key. Two consequences of that
last type are worth naming: `00000000020` lands as the integer `20`, and the deliberately absent
`00000000007` simply has **no row** rather than a row with a null or sentinel key. The harness is created
under the `test` profile configured in `../../application-test.yml`. A scenario owns only its own rows and
never seeds another service's schema, per master section 11.3.

Assumptions: the module's own test reaches this scenario's outcome **the other way round** from this
fixture, and the difference is recorded so neither reads as a mistake. This folder resolves the card to
account `00000000007` and commits an account master holding `00000000020`; the test commits a
cross-reference resolving to an identifier **no** account row carries. Both produce the same finding from
the same branch -- the account read misses -- and the second form needs no second committed image. The
fixture keeps the reference construction because it is the form the house tree uses, so one description
covers both trees.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs. Unlike the
posting and interest domains, this one has **no golden corpus at all** in either tree -- the program
produces console diagnostics rather than records, so its expectation is stated as marker presence and
absence in section 3. `tests/**` remains reference-only and no golden is ever regenerated.

### 8.2 The posting reject this is the lineage of, and does not produce

The same broken chain link, driven through the posting program instead, is reject reason **101**.
`app/cbl/CBTRN02C.cbl:397-398` moves `101` into the reason field and the message
`ACCOUNT RECORD NOT FOUND` into its description, from the `INVALID KEY` arm of the account read in
`1500-B-LOOKUP-ACCT` at `:393-395`. Master section 7.1.1 carries that reason with the other three, and
[`../../posting/reject_101_acct_missing`](../../posting/reject_101_acct_missing) is the folder whose
expectation is that reject. Its fixture even uses the same two seed rows this one does, so **the two
scenarios differ only in which program reads them** -- and that difference changes the outcome from a
console diagnostic to a persisted reject.

The lineage is recorded so a reader can connect the two domains through one condition, and for no other
purpose. **Preflight emits no reject record, no reason code and no return code.** The reject stream
belongs entirely to the posting domain: nothing in this folder produces a `0101`, a 430-byte reject
record or a tier-4 outcome, and an expectation that looked for one here would be looking for posting's
output in a pass that writes nothing. Master section 7.1.7's oracle constants belong to that folder's
program, not to this one -- this pass posts nothing, rejects nothing and touches no category balance, so
none of them applies here.

The two programs word the condition **differently**, and each wording is carried verbatim from its own
program:

| Program | Text, verbatim | Line |
|---|---|---|
| `CBTRN01C` | `INVALID ACCOUNT NUMBER FOUND` | `:246` |
| `CBTRN01C` | `ACCOUNT ` + `ACCT-ID` + ` NOT FOUND` | `:178` |
| `CBTRN02C` | `ACCOUNT RECORD NOT FOUND` | `:398` |

Assumptions: the three strings are never merged or paraphrased into one another. They are published
text, each read by expectations written against its own program, so normalising them to a single
sentence would break whichever expectation lost its wording -- and would misattribute a posting message
to a pass that never emits it.

### 8.3 The sibling folders, and the two files this folder shares with one of them

[`../unmatched_card`](../unmatched_card) breaks the **first** link of the chain -- its cross-reference
holds a different card -- and [`../happy_path`](../happy_path) breaks neither. Read the three together:
they cover the two ways the chain can fail and the case where it does not, and only the set of three
shows that each diagnostic is reported for its own condition and no other.

One relationship inside that set is a byte-level fact rather than a family resemblance, and it is stated
here because a contract test asserts it: **this folder's `dailytran.txt` and `cardxref.txt` are
byte-identical to `../happy_path`'s, and `acctdata.txt` is the only file that differs between the two
scenarios.** Both folders carry the same feed record and the same cross-reference row resolving to
account `00000000007`; `../happy_path` then supplies account `00000000007` in its account master, where
this folder supplies account `00000000020` instead.

Assumptions: that duplication is deliberate and is the whole construction of the pair, so it must not be
tidied. Holding the feed and the cross-reference **equal** across the two folders is what isolates the
account master as the single variable, which is the only way the pair proves that the account read is
what decides the outcome. A future author who removed the duplication -- by editing one folder's feed or
cross-reference, or by pointing one scenario at the other's files -- would leave both scenarios still
passing their own expectations while this documented relationship became false, which is precisely why
`BatchFixtureContractTest` asserts the sameness rather than trusting the prose.

---

## 9. Data governance and synthetic provenance

**The data in this folder is synthetic and seed-derived, and no business-rule field is reshaped anywhere
in it.** Every record is a published AWS CardDemo sample seed row under `app/data/ASCII/`, selected
rather than edited:

| File | Seed | Seed row | Relationship to the seed row |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | **1** | **byte-identical** |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | **21** | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | **20** | **byte-identical** |

**Every business-rule field reshaped away from its seed value: none.** Master section 10's fifth item
requires that clause to be answered explicitly rather than left blank, and for this folder the answer is
that no field was reshaped at all -- not the account identifier, not a balance, not a limit, not a date,
and not `ACCT-GROUP-ID`, which master section 7.2.3 names as the canonical reshaped field in the interest
domain and which is left at its seed blank here because nothing in this domain reads it. **The scenario
is created by selection, not by reshaping.**

**The only byte-level difference from any seed anywhere in this folder** is the `cardxref.txt` widening
from the seed's 36 bytes to the copybook's 50, which appends 14 spaces of `FILLER X(14)` and alters no
value. Section 5.4 names it as a departure and this section names it as the sole byte-level edit, so the
two statements agree: every other byte in the folder is a seed byte in its seed position.

The cross-reference row is row **21** of its seed rather than row 7 because that file is ordered by card
number, and account `00000000007` appears in it under card `4859452612877065`.

**It represents no real person and no real account.** The seeds ship with the upstream open-source
project as fabricated demonstration data, and master section 11.1 carries the tree-level attestation this
scenario inherits. Identity and primary-account-number bytes -- the card number, the customer identifier
and both account identifiers named in this document -- are taken unchanged from the seed.

### 9.1 The deliberate omission, and why it must not be repaired

**Account `00000000007` -- the identifier the cross-reference resolves -- is deliberately not present as
an `ACCT-ID` key in `acctdata.txt`.** That absence is not an oversight, an incomplete extract or a
missing row awaiting completion: **it is the mechanism of the scenario.** The account read at `:243`
misses because there is nothing under that key to find, and every expectation in section 3 follows from
the miss.

Assumptions: master section 8's self-containment rule -- every key cross-referenced inside a scenario
resolves inside its own files -- carries an **explicit carve-out** for exactly this case, permitting a
key to be deliberately omitted to trigger a reject or a fallback provided the scenario's README names
the omission. This section is that naming, and it is the reason this folder does not violate
self-containment despite holding a cross-reference row whose account resolves to nothing. **No future
reader should "repair" this fixture by adding the missing account.** Adding a row for `00000000007`
would make both lookups hit, both diagnostics vanish, and this folder become a duplicate of
`../happy_path` while still claiming in its own documentation to pin the account-not-found path -- and
because this domain has no return code and no reject stream, nothing in a run's exit status would reveal
the change.

The omission is an authorial decision rather than an artifact of what the seed happened to offer, and the
trap is worth naming: **account `00000000007` genuinely does exist, as row 7 of the 50-record
`app/data/ASCII/acctdata.txt` seed.** The matching row was available and was not used; row 20 was chosen
instead. A reader who notices that row 7 exists and infers the fixture is simply incomplete would be
reading the one fact that most looks like evidence of an accident.

Trade-offs: selecting seed row 20 rather than reshaping row 7's account identifier to something absent
costs one thing worth naming. Row 20 differs from row 7 in every monetary field and every date, so this
folder's account row shares almost no bytes with its siblings' -- a reader diffing two preflight fixtures
sees a large difference where the scenario's actual delta is the key alone. The compromise is accepted
because the alternative is worse in kind: overwriting an account identifier would produce a row whose key
names an account no seed row describes, so its provenance could no longer be stated as a seed row and the
eleven bytes would have to be justified as invented -- and an invented identifier could in principle
collide with a real value, which is the risk the seed-only discipline of master section 11.2 exists to
remove. Even the deliberately wrong record is therefore attributable to a published synthetic row.
Section 5.3 turns the cost into a benefit by naming the unfamiliar values as a diagnostic signal.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string or
endpoint appears in any file here, money never leaves fixed point, and nothing here modifies the COBOL
baseline or the parity oracle.

---

## 10. What drives this corpus, and what reads it

Master section 1.5 records, file by file, which record files this module opens as job input and which it
only holds to geometry, and it names this one as driven from its `dailytran.txt` by
`PreflightDailyTransactionsJobTest`, which seeds the card to an account it deliberately does not create.
The other two files here, `acctdata.txt` and `cardxref.txt`, are among the nine in the whole tree that no
job opens -- so within this one folder both cases apply, which is why the distinction is drawn per file
rather than per directory. Every file here additionally has a contract consumer in
`BatchFixtureContractTest`, which enumerates this scenario among all sixteen, holds each file to its
declared geometry, its line endings and its trailing newline, decodes every record under its declared
layout, and asserts the relationships this document states -- including the byte-sameness of section 8.3.
So an edit to any file here is detected in this module even where no job reads the bytes.

Assumptions: this document is self-contained for every contract fact it states, and its links to
`../happy_path`, `../unmatched_card` and `../../posting/reject_101_acct_missing` are for orientation
only. No fact above is deferred to a sibling README, and no claim is made about what a sibling document
says, because a scenario folder can be read, moved or authored independently of its siblings -- a
statement of the form "as documented in the sibling README" would be unverifiable from this folder alone.
Byte-encoding rules are the single exception: those belong to the master contract by master section 1.3
and are cited there by section rather than copied here, because sixteen copies of one ledger would become
sixteen things to keep in step.

---

*This README is the mandatory Explainability carrier for the three record files in this directory,
required by master section 10 and by user-specified Rule 1. **No gate reads this prose.**
`config/checkstyle/checkstyle.xml` limits its audit set to `java`, and `config/rule1/rule1_gate.py`
excludes every path containing `/src/test/resources/fixtures/` in its `_is_governed` check, which is
this path -- so neither its `labels` check nor its `what` check ever opens this file. Refactoring
Rationale: this paragraph previously said the gate "decides the form of the rationale labels above,
repository-wide and including Markdown". The gate does run repository-wide, which is what made the
claim plausible, but this path is explicitly outside its remit -- so the sentence credited a
build-failing gate with cover it does not provide, and a green build could be read as evidence about
this document. The canonical label form is used regardless, because
`docs/CODE_DOCUMENTATION_STANDARD.md` fixes one written form repository-wide and a Rule 1 audit finds
a rationale by literal string search; compliance in this path is therefore **review-based** rather than
mechanical. What IS machine-checked here is the neighbouring files' geometry, by
`BatchFixtureContractTest`. Whether each rationale names a real consequence, and whether every number
and line citation is true, are review obligations no lexical gate can decide.*
