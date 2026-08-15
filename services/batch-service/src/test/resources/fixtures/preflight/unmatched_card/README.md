# `preflight/unmatched_card` -- the card is not in the cross-reference, so the account is never read

> **Purpose.** Pin the card-not-verified path of the pre-posting preflight pass: a single daily
> transaction whose card number is **absent** from the card cross-reference is reported as
> unverifiable, the account read is **skipped entirely** by the guard the reference places between the
> two lookups, nothing is changed, and the run ends cleanly without an abend. It is the first half of a
> pair with `../unmatched_account`, which breaks the second link of the same chain.
>
> **Source of truth.** `app/cbl/CBTRN01C.cbl` for the behaviour, cited below by line;
> `app/cpy/CVTRA06Y.cpy`, `app/cpy/CVACT03Y.cpy` and `app/cpy/CVACT01Y.cpy` for the record layouts; the
> seed datasets under `app/data/ASCII/` for the bytes; and the tree-level
> [master contract](../../README.md) for every encoding rule, which this document **cites by section
> and neither restates nor contradicts**, per master section 1.3. **There is no JCL driver to cite**:
> master section 4.1 records that no member of `app/jcl` executes this program at all.
>
> **Label form.** Rationales below are tagged `Alternatives Considered:`, `Assumptions:` and
> `Trade-offs:` -- plain, plural, colon retained, no emphasis markup, per master section 1.4 and
> `docs/CODE_DOCUMENTATION_STANDARD.md`. The rules document typesets those names in bold; that bold is
> its own typography and is not part of a label's written form. This whole file is pure ASCII for the
> reason master section 1.4 gives.

**This README is the mandatory Explainability carrier for the three record files beside it.** Master
section 1.2 records why: a fixed-width record file cannot carry a comment of any kind, because every
byte position is meaningful and a comment on its own line is a physical row of the wrong length. Any
header, ruler or `#` here would move these files off **351 / 51 / 301** bytes and off their declared
**350 / 50 / 300** widths, and the loaders reject a wrong-length row outright rather than padding it.
Master section 10 makes the artifact mandatory rather than courteous, its five items fix the section
order below, and under user-specified Rule 1 the absence of this file is a review-gate failure -- that
gate is conjunctive, failing a contribution that lacks **either** the documentation **or** the labelled
rationale.

Assumptions: this tree calls the domain **`preflight/`** where the reference-only house tree calls it
**`prepost/`** (`tests/fixtures/prepost/**`). Master section 4.2 records the correspondence: the two
names denote the same `CBTRN01C` behaviour, this tree matches the job name a reader finds in the
module's own sources and in the batch state machine (`preflight-daily-transactions`), and the house
tree is reference-only in any case. It is recorded here as well as there so that a reader can find the
house analogue at `tests/fixtures/prepost/unmatched_card/`, and so that nobody renames one tree to
match the other -- a rename would break the job-name correspondence in one direction or the oracle
correspondence in the other, and neither name is wrong.

---

## 1. Purpose -- the condition this scenario pins

One daily transaction on card **`4859452612877065`**, against a cross-reference holding a single row
for a **different** card, **`0927987108636232`**. The lookup misses.

Three things must hold at once:

- the cross-reference lookup **misses**, so the card-not-verified line is emitted;
- the account read is **not performed at all**, because the guard at `:173` admits it only when the
  cross-reference read succeeded -- and the account master in this folder **does** hold a resolvable
  row, so the skip is genuine rather than vacuous;
- the run still ends **cleanly** and changes nothing -- an unverifiable card is a **report**, not a
  rejection and not a failure.

**The absence of the account read is this folder's principal assertion**, and it is the reason the
account master here is populated rather than empty. Against an empty master a test could not tell "the
read was skipped" from "the read happened and found nothing", and those are different behaviours with
different diagnostics.

Assumptions: the omission is **deliberate**. Card `4859452612877065` is intentionally not present as
an `XREF-CARD-NUM` key in `cardxref.txt`, in order to reach the `INVALID KEY` branch at `:231`. Master
section 8 states that each scenario is self-contained -- every key cross-referenced inside it resolves
inside its own files -- **except** where a key is deliberately omitted to trigger a reject or a
fallback, which the scenario's README must name; this paragraph is that naming. It is stated
explicitly so that no future reader "repairs" the fixture by adding the missing key: adding it would
make the lookup succeed, silently turn this folder into a duplicate of `../happy_path`, and leave a
scenario that asserts nothing while still passing every geometry check.

Alternatives Considered: producing the miss by shipping an **empty** `cardxref.txt` rather than a
populated one naming a different card. Rejected because an empty cross-reference cannot distinguish
"the card was looked up and not found" from "the dataset was never loaded", and in this domain the
second failure is especially easy to miss: there is no reject stream and no graded exit to change, so a
harness that never loaded the file would produce **exactly** this scenario's expected diagnostics and
pass. A populated file holding one non-matching row proves the dataset loaded, the read executed, and
the key genuinely did not match.

---

## 2. The business rule, cited by program and line

### 2.1 The failing lookup and the guard that skips the second

`app/cbl/CBTRN01C.cbl:164-186` is the loop. For this record, in two steps:

**Step 1 -- `2000-LOOKUP-XREF` (`:227-239`) MISSES.**

| Line | Statement | Effect |
|---|---|---|
| `:171` | `MOVE DALYTRAN-CARD-NUM TO XREF-CARD-NUM` | the feed record's card becomes the lookup key |
| `:228` | `MOVE XREF-CARD-NUM TO FD-XREF-CARD-NUM` | the key is moved into the file's own record area |
| `:229-230` | `READ XREF-FILE RECORD INTO CARD-XREF-RECORD` / `KEY IS FD-XREF-CARD-NUM` | the keyed read |
| `:231` | `INVALID KEY` | the branch this scenario reaches |
| `:232` | `DISPLAY 'INVALID CARD NUMBER FOR XREF'` | the first expected marker |
| `:233` | `MOVE 4 TO WS-XREF-READ-STATUS` | the status the guard below tests |

**Step 2 -- the account read is skipped.** Because `WS-XREF-READ-STATUS` is now non-zero, the
main-loop test at `:173` (`IF WS-XREF-READ-STATUS = 0`) is **false**, so the block at `:174-179` --
which contains the `PERFORM 3000-READ-ACCOUNT` at `:176` -- is **not entered**. Control takes the
**`ELSE` at `:180`** and DISPLAYs at `:181-183`: `'CARD NUMBER '`, then `DALYTRAN-CARD-NUM`, then
`' COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-'`, then `DALYTRAN-ID`. **`3000-READ-ACCOUNT`
(`:241-250`) is never performed.**

The rest of the run is bookkeeping: `:166` and `:202-225` read the feed, `:167-169` display the whole
record, `:186` closes the loop, `:188-193` issue the six closes, `:195` DISPLAYs the end banner and
`:197` reaches `GOBACK`.

**The lookup is by the PRIMARY cross-reference key** -- `XREF-CARD-NUM`, `PIC X(16)` at zero-based
offset **0**. Master section 4.3 separates the two access paths: this program and posting read the base
cluster by card number at offset 0, while the **interest** domain reads the same record through the
alternate index on `XREF-ACCT-ID`, `PIC 9(11)` at offset **25**. That alternate path plays **no part
here** -- `CBTRN01C` declares no `ALTERNATE RECORD KEY`, so offset 25 is inert in this folder and a
fixture that satisfied only the account-keyed path would not make this lookup miss.

**Two things about the skip message must be reproduced exactly.** Its middle fragment ends `ID-` -- a
hyphen with **no trailing space** -- so the identifier position abuts it directly. And `'CARD NUMBER '`
carries a **trailing** space while `' COULD NOT BE VERIFIED...'` carries a **leading** one, because
`:181-183` concatenates the pieces with no separator of its own. Inserting the space a reader expects
after `ID-` would change published text.

**The identifier in the message is the transaction's, not the account's.** `DALYTRAN-ID` is the only
field of the feed record this branch names, and no account identifier is available on it at all -- the
cross-reference read that would have supplied one failed.

### 2.2 The loop performs the failing lookup twice for one record

The inner `IF END-OF-DAILY-TRANS-FILE = 'N'` opened at `:167` closes at **`:169`**, so it guards
**only** the record display at `:168`. The lookup block at `:170-184` sits **outside** it and inside
the outer `IF` at `:165`. On the second iteration the read at `:166` reaches end of file --
`1000-DALYTRAN-GET-NEXT` (`:202-225`) sets `MOVE 'Y' TO END-OF-DAILY-TRANS-FILE` at `:217` and does
**not** clear `DALYTRAN-RECORD` -- so the display is skipped and the lookup block runs **again**,
against the card number still sitting in the stale record area. For one input record **both** of this
scenario's diagnostics therefore appear **twice**.

Assumptions: this is a control-flow characteristic of the baseline, and `app/**` is REFERENCE-ONLY, so
it is documented rather than worked around here. It has no effect on data -- every dataset is opened
`INPUT` -- so the repeated read changes nothing and produces no output beyond the duplicated console
lines. **The consequence for any expectation is that it must assert marker PRESENCE and ABSENCE, never
a repetition count.** Note the interaction with this scenario in particular: because the guard at
`:173` fails on both passes, the account read is skipped **twice**, so "the account was never read"
holds for the whole run and not merely for the first iteration. The baseline performs one lookup past
end of file; the migrated pass implements the clean loop instead, performing exactly N lookups for N
records, and carries that as its documented divergence D-7.

### 2.3 Two duplicated data-names in the FILE SECTION are not defects

Both duplicates are visible in the program's own FD declarations, and a reader mapping bytes from
those declarations will meet them.

Assumptions: `FD-CUST-DATA` is declared **twice** -- at `:69` as `PIC X(334)` in the DALYTRAN FD, and
at `:74` as `PIC X(491)` in the CUSTOMER FD. The two names collide only in a flat symbol table; in
COBOL each is qualified by its own record, so **a layout must be scoped per copybook** rather than
resolved by name alone. An offset table that keyed on the bare name would silently take one width for
the other, and 334 against 491 is a 157-byte error that produces a record of plausible length.

Assumptions: `FD-ACCT-DATA` is likewise declared **twice** -- at `:89` as `PIC X(289)` in the ACCOUNT
FD, and at `:94` as `PIC X(334)` in the TRANSACT FD. The same per-copybook scoping requirement
applies, and the same failure mode follows from ignoring it. The two duplicates are recorded as
separate notes because they are separate declarations in separate records, and a reader who finds one
should not have to infer the other.

---

## 3. Returns -- the expected outcome

**What must appear.**

| Marker | Line | Times, for one input record |
|---|---|---|
| `START OF EXECUTION OF PROGRAM CBTRN01C` | `:156` | once |
| the whole `DALYTRAN-RECORD`, displayed | `:168` | once -- inside the guard that closes at `:169` |
| **`INVALID CARD NUMBER FOR XREF`** | `:232` | twice, per section 2.2 |
| **`CARD NUMBER 4859452612877065 COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-0000000000683580`** | `:181-183` | twice |
| `END OF EXECUTION OF PROGRAM CBTRN01C` | `:195` | once |

**What must not appear.** These three are the load-bearing negative assertions -- each names a line the
run cannot reach, and each would pass unnoticed if only the positive markers were checked.

| Forbidden marker | Line | Why it cannot occur |
|---|---|---|
| **`SUCCESSFUL READ OF XREF`** | `:235` | the read at `:229` takes `INVALID KEY` at `:231`, so the `NOT INVALID KEY` arm at `:234` is never entered |
| **`SUCCESSFUL READ OF ACCOUNT FILE`** | `:249` | `3000-READ-ACCOUNT` is never performed, because the guard at `:173` is false |
| **`INVALID ACCOUNT NUMBER FOUND`** | `:246` | the same paragraph, never performed -- so **neither** arm of the account read can speak |

Two further lines are absent for the same reasons and are listed so their absence is not mistaken for
an oversight: the successful read's three detail lines `CARD NUMBER: `, `ACCOUNT ID : ` and
`CUSTOMER ID: ` at `:236-238` share the arm at `:234`, and `ACCOUNT ... NOT FOUND` at `:178` sits
inside the block the guard at `:173` does not enter.

**No abend occurs.** A missing cross-reference row is a **normal skip, not an I/O error**.
`2000-LOOKUP-XREF` (`:227-239`) has no abend site at all: its `INVALID KEY` arm DISPLAYs and sets a
status field, and that is the whole of it. Every abend site in the program is elsewhere and is
status-guarded -- the six open paragraphs, the six close paragraphs, and the feed read at `:222`, which
is reached only when the read status is neither `'00'` nor `'10'`. A run whose opens and closes all
return `'00'` and whose feed read returns `'00'` then `'10'` reaches none of them.

**The program declares no return code, and the run simply ends cleanly.** `CBTRN01C` contains **no
`RETURN-CODE` statement anywhere in its 494 lines, no reject stream and no counters** -- a search for
each of those across the program returns zero hits -- and its only failure exit is `Z-ABEND-PROGRAM` at
`:469-473`, which DISPLAYs `ABENDING PROGRAM` and calls `CEE3ABD` with code 999. So the run either
falls through `GOBACK` at `:197` having reported the unverifiable card, or it abends. There is no third
outcome and no numeric grade to assert. An expectation for this scenario must therefore be stated as
the marker presence and absence above, together with the completion of the run.

**The consequence for the target is that the declared `PreflightDailyTransactionsJob` can never emit
exit-status tier 4.** Because the reference has no return code, no reject stream and no counter to
grade, the migrated pass reports the clean tier on every completing run and otherwise fails hard; the
warn tier is unreachable here by construction rather than by choice. Nothing about this scenario may be
expressed as a warn tier or as a reject. The graded rubric 0, 4, 8 belongs to the COBOL parity suite
alone (master section 7.1.6), and the only warn-tier gate in the tree belongs to posting.

**No data changes.** All six datasets are opened `INPUT` -- `:254` DALYTRAN, `:273` CUSTOMER, `:291`
XREF, `:309` CARD, `:327` ACCOUNT, `:345` TRANSACT -- and the program contains **no** `WRITE`,
`REWRITE` or `DELETE` statement of any kind. In particular the missing cross-reference row is **not
created**.

**The account master is present and is never read, and both halves of that are assertions.** The row
for account `00000000007` sits in `acctdata.txt` and would resolve if anything asked for it; nothing
does. Asserting only the message would pass even for an implementation that read the master first and
discarded the answer, so the assertion is the **absence of the read** -- and it is meaningful only
because the row exists.

Assumptions: this domain has **no golden corpus in this tree's shape**, and none is asserted here,
because the program's output is console diagnostics rather than records -- so the expectation is stated
as marker presence and absence above rather than as a byte comparison against an `*.expected` file. The
consequence of assuming otherwise is concrete: an author who added an `*.expected` file here would be
committing an expectation nothing compares, and the two tables above would stop being the authority
while still reading as though they were. The reference-only house tree states the same expectation for
the same program in the same terms.

---

## 4. Exceptions and errors -- what must not happen, and why it cannot

### 4.1 Reject lineage -- the posting scenario this must not be confused with

This is the **same broken chain link** that the posting program surfaces as a reject, reached here
through the preflight path instead. `app/cbl/CBTRN02C.cbl:385-386`, inside paragraph
`1500-A-LOOKUP-XREF`, takes the same `INVALID KEY` on the same keyed read and instead performs
`MOVE 100 TO WS-VALIDATION-FAIL-REASON` followed by
`MOVE 'INVALID CARD NUMBER FOUND' TO WS-VALIDATION-FAIL-REASON-DESC`. That is reject reason **100** of
the four master section 7.1.1 enumerates, and `../../posting/reject_100_card_missing` is the folder
for it.

**The two message strings are different and must never be conflated:**

| Domain | Program | String | Fate |
|---|---|---|---|
| preflight | `CBTRN01C:232` | `INVALID CARD NUMBER FOR XREF` | DISPLAYed to the console |
| posting | `CBTRN02C:386` | `INVALID CARD NUMBER FOUND` | stored in the reject record's trailer |

`FOR XREF` against `FOUND`: neither may be quoted in place of the other, and a test that asserted the
posting string against this program would fail against text the program never emits.

**Preflight emits no reject record, no reason code and no return code.** The reject stream belongs
wholly to the **posting** domain -- `CBTRN01C` has no `DALYREJS` dataset, writes nothing, and grades
nothing. This subsection exists so a reader can connect the two domains, and for nothing more: it is
lineage, not a contract this folder owns.

The corroboration is measurable in this tree. This folder's three record files are **byte-identical**
to `../../posting/reject_100_card_missing/`'s `dailytran.txt`, `cardxref.txt` and `acctdata.txt` --
which is exactly what one expects of two scenarios built on the same condition -- and that folder
additionally ships a `tcatbal.txt` that this one correctly does **not**, because `CBTRN01C` never opens
`TCATBALF` at all; the token does not occur in the program. **The two scenarios differ in which
program reads them**, and that difference changes the outcome from a console diagnostic into a
persisted 430-byte reject. Master section 7.1.7's oracle constants belong to that folder's program, not
to this one: this pass posts nothing, rejects nothing and touches no category balance.

### 4.2 The three things an implementation must not do

**The missing cross-reference row must not abend the run.** This is the scenario's central negative,
and section 3 records why the reference cannot: there is no abend site on that branch. An
implementation treating a missing cross-reference row as an I/O error would abend here while handling
the matched path correctly.

**The account read must not happen.** An implementation that read the account master regardless -- for
example one that resolved card to account with a **join** and inspected both results afterwards --
would still report an unverifiable card and would still be wrong about this, and the difference would
appear in no console line. It is checkable only as the absence of the read, which is why the
target-side contract in section 7 states it as a verified interaction rather than as a message.

**The run must not fail or warn the chain.** Section 3 states the position: there is no return code to
set. A failure here would stop the nightly chain on a condition the reference lets run to completion,
and posting would then never run to reject the record properly as reason 100.

---

## 5. Parameters -- the files in this folder and their byte geometry

### 5.1 Inventory

| File | Copybook | RECLN | Bytes on disk | Organization / key | Role in this scenario |
|---|---|---:|---:|---|---|
| `dailytran.txt` | `app/cpy/CVTRA06Y.cpy` | 350 | **351** | SEQUENTIAL, no key | the feed; its `DALYTRAN-CARD-NUM` is **deliberately absent** from `cardxref.txt` |
| `cardxref.txt` | `app/cpy/CVACT03Y.cpy` | **50** | **51** | INDEXED, `XREF-CARD-NUM` offset 0 length 16 | the indexed lookup that **misses** -- carries an unrelated card only |
| `acctdata.txt` | `app/cpy/CVACT01Y.cpy` | 300 | **301** | INDEXED, `ACCT-ID` offset 0 length 11 | present but **never reached** |
| `README.md` | -- | -- | -- | -- | this file |

The `ASSIGN` names the program binds -- `DALYTRAN`, `XREFFILE`, `ACCTFILE` -- are declared at
`app/cbl/CBTRN01C.cbl:29-58`. Every record length above is the **summed width of the declared fields**,
never a `RECLN` banner comment, per master section 5.1.

### 5.2 On-disk sizes, line endings and governance

**One record per file; LF only; exactly one trailing newline, so `wc -l` equals 1 equals the record
count; zero `CR` bytes; zero `NUL` bytes.** Per master sections 3.8 and 3.9, and measured rather than
assumed:

| File | Bytes | Records | Bytes per record | `CR` | `NUL` | `wc -l` | Trailing newline |
|---|---:|---:|---:|---:|---:|---:|---|
| `dailytran.txt` | **351** | 1 | 350 | 0 | 0 | 1 | exactly one `LF` |
| `cardxref.txt` | **51** | 1 | 50 | 0 | 0 | 1 | exactly one `LF` |
| `acctdata.txt` | **301** | 1 | 300 | 0 | 0 | 1 | exactly one `LF` |

There is **no** line-ending normalization to declare: none of the three seed sources ships `CRLF`. The
folder is pure ASCII throughout.

```bash
# WHAT: assert the byte geometry, and additionally assert the two relations this scenario turns on -- that
#       the feed's card does NOT match the cross-reference key, and that the account master nevertheless
#       holds a row, so the skipped read is genuine. Run from this directory.
# WHY : Assumptions: this scenario has two independent ways to stop testing what it claims, and in this
#       domain neither changes a record count nor any graded outcome -- only which console line appears. If
#       the card starts matching, the folder silently becomes ../happy_path; if the account master were
#       empty or absent, the "the read was skipped" assertion would hold vacuously and would pass equally
#       for an implementation that read the master and found nothing. Asserting both at authoring time is
#       what keeps the claim in section 3 substantive.
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
          "| CR", raw.count(b"\r"), "| NUL", raw.count(b"\x00"),
          "| OK" if len(raw) == size and raw.count(b"\r") == 0
          and raw.count(b"\x00") == 0 and widths == [width]
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

Offsets are zero-based; money is signed zoned decimal with the sign folded into the last byte and no
byte for the decimal point, per master sections 3.3 and 3.7. **This program reads no monetary field at
all**, so every amount below is documented for provenance rather than for assertion.

The offsets used here are licensed by an identity master section 5.1 records: **`DALYTRAN-RECORD` is
field-for-field identical to `TRAN-RECORD`** -- `CVTRA06Y` lines 5-18 against `CVTRA05Y` lines 5-18,
verified by side-by-side comparison, with only the `DALYTRAN-` name prefix differing and line 18
declaring the same `FILLER PIC X(20)` in both. That is why master section 5.2's offset table for
`CVTRA05Y` applies unchanged to this feed record, and why the two-source cross-check of master
section 5.3 -- the copybook sum and the DFSORT symbols of `app/jcl/TRANREPT.jcl` agreeing on 262 and 304
-- covers these bytes too.

`dailytran.txt`, one `DALYTRAN-RECORD`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `DALYTRAN-ID` | 0 | 16 | **`0000000000683580`** | the identifier the skip message carries, from `:183` |
| `DALYTRAN-TYPE-CD` | 16 | 2 | `01` | unread |
| `DALYTRAN-CAT-CD` | 18 | 4 | `0001` | unread; unsigned `PIC 9(04)`, plain digits, no overpunch |
| `DALYTRAN-SOURCE` | 22 | 10 | `POS TERM` + 2 spaces | unread |
| `DALYTRAN-DESC` | 32 | 100 | `Purchase at Abshire-Lowe` + 76 spaces | unread |
| `DALYTRAN-AMT` | **132** | 11 | **`0000005047G`** | **`+504.77`** -- unread |
| `DALYTRAN-MERCHANT-ID` | 143 | 9 | `800000000` | unread |
| `DALYTRAN-MERCHANT-NAME` | 152 | 50 | `Abshire-Lowe` + 38 spaces | unread |
| `DALYTRAN-MERCHANT-CITY` | 202 | 50 | `North Enoshaven` + 35 spaces | unread |
| `DALYTRAN-MERCHANT-ZIP` | 252 | 10 | `72112` + 5 spaces | unread |
| `DALYTRAN-CARD-NUM` | **262** | 16 | **`4859452612877065`** | the key `:171` moves, `:229` fails to find, and `:182` reports |
| `DALYTRAN-ORIG-TS` | **278** | 26 | `2022-06-10 19:27:53.000000` | unread |
| `DALYTRAN-PROC-TS` | **304** | 26 | 26 spaces | a legitimate absent value -- see section 6 |
| `FILLER` | **330** | 20 | 20 spaces | padding -- see the note below |

The money decode, worked rather than asserted: `DALYTRAN-AMT` is `PIC S9(09)V99`, so eleven stored
digits with the sign overpunched into the last byte. The overpunch `G` carries digit **7** and a
**positive** sign, so `0000005047` + `G` reads as the eleven digits `00000050477`, and `V99` places the
implied point two from the right: **`+504.77`**.

**This is the only preflight scenario in which two fields of the feed record reach the output.** The
card number and the transaction identifier both appear in the skip message, so both are asserted values
rather than inert bytes -- everywhere else in this domain the feed record's fields are merely displayed
as part of the whole-record dump at `:168`.

`cardxref.txt`, one `CARD-XREF-RECORD` -- the row that does **not** match:

| Field | Offset | Width | Bytes |
|---|---:|---:|---|
| `XREF-CARD-NUM` | 0 | 16 | **`0927987108636232`** |
| `XREF-CUST-ID` | 16 | 9 | `000000020` |
| `XREF-ACCT-ID` | 25 | 11 | `00000000020` |
| `FILLER` | 36 | 14 | 14 spaces |

**`cardxref.txt[0:16]` differs from `dailytran.txt[262:278]`** -- `0927987108636232` against
`4859452612877065` -- and that inequality **is** the scenario. **None of this row's three identifiers is
ever read**, because the read that would have loaded them failed; the row exists only so that the
failing read has a populated dataset to fail against, per section 1.

`acctdata.txt`, one `ACCOUNT-RECORD` for account `00000000007`:

| Field | Offset | Width | Bytes | Decoded |
|---|---:|---:|---|---|
| `ACCT-ID` | 0 | 11 | `00000000007` | the key nothing asks for |
| `ACCT-ACTIVE-STATUS` | 11 | 1 | `Y` | unread |
| `ACCT-CURR-BAL` | 12 | 12 | `00000001930{` | **`+193.00`** |
| `ACCT-CREDIT-LIMIT` | 24 | 12 | `00000020650{` | **`+2065.00`** |
| `ACCT-CASH-CREDIT-LIMIT` | 36 | 12 | `00000002640{` | **`+264.00`** |
| `ACCT-OPEN-DATE` | 48 | 10 | `2012-10-12` | unread |
| `ACCT-EXPIRAION-DATE` | 58 | 10 | `2024-12-13` | unread |
| `ACCT-REISSUE-DATE` | 68 | 10 | `2024-12-13` | unread |
| `ACCT-CURR-CYC-CREDIT` | 78 | 12 | `00000000000{` | **`+0.00`** |
| `ACCT-CURR-CYC-DEBIT` | 90 | 12 | `00000000000{` | **`+0.00`** |
| `ACCT-ADDR-ZIP` | 102 | 10 | `A000000000` | unread |
| `ACCT-GROUP-ID` | 112 | 10 | 10 spaces | a legitimate absent value -- see section 6 |
| `FILLER` | 122 | 178 | 178 spaces | padding -- see the note below |

All five money fields are `PIC S9(10)V99`, twelve stored digits with the overpunch in the last byte.
The overpunch `{` carries digit **0** and a **positive** sign, so `00000001930` + `{` reads as
`000000019300` and `V99` gives **`+193.00`**; by the same derivation `00000020650{` is **`+2065.00`**,
`00000002640{` is **`+264.00`**, and both `00000000000{` fields are **`+0.00`**.

**This row is never read either, and its purpose is precisely that.** It is the same account row
`../happy_path` uses, so the two folders differ in exactly one file -- and it makes the skipped read
genuine rather than vacuous, as sections 1 and 3 explain. Note the asymmetry with the cross-reference
row above: that row is unread because the read **failed**, while this one is unread because the read
**never happened**, and the second is the assertion.

`ACCT-EXPIRAION-DATE` is spelled exactly as `app/cpy/CVACT01Y.cpy` line 11 spells it, quoted verbatim
because master section 9.3 confines the three misspelling corrections to **target column names only**
and requires a baseline citation to quote the baseline spelling. The baseline declares
`ACCT-EXPIRAION-DATE`; the migrated column is `expiration_date`; the divergence is documented, and it
is a naming decision on the target side rather than any change to `app/**`.

Assumptions: **`FILLER` padding here is SPACE (`0x20`), never NUL.** That holds for all three
`FILLER` slices in this folder -- the DALYTRAN `X(20)` at 330, the ACCOUNT `X(178)` at 122 and the
widened CARD-XREF `X(14)` at 36 -- and it is taken from master section 6.1's **measured** per-record
table, which master section 3.2 declares **overriding** any general rule derived from the `PICTURE`
clause. The distinction matters because that same table records two records whose `FILLER` is *not*
space: the **output** `TRAN` record pads with `0x00` and the TCATBAL and DISCGRP records with ASCII
`'0'`. Neither of those record types appears in this domain, so master section 6.2's exceptions do not
arise here -- but an author who reasoned from the general rule instead of the table would be right by
accident in this folder and wrong in the next one.

Trade-offs: the three masters `CBTRN01C` opens but never reads are **supplied empty by the paired test
rather than shipped here as byte images**. The program issues `OPEN INPUT` on the customer master at
`:273`, the card master at `:309` and the transaction master at `:345`, and never issues a `READ`
against any of them, so they need only be **openable**; the test therefore creates empty but valid
indexed files with the correct key geometry. The compromise accepted is a dependency on the test to
create them -- a reader of this folder alone cannot see that those three datasets exist at all, which is
why they are named here. What is bought is three fewer record images to keep in step with three
copybooks whose bytes no assertion in this scenario can ever observe.

Trade-offs: `acctdata.txt` **ships even though it is unreachable on this path**. Dropping it was
considered and rejected for a specific consequence: `OPEN INPUT ACCOUNT-FILE` at `:327` would then have
nothing to open, which changes the outcome of that statement, and an empty or absent master would make
"the account read was skipped" hold **vacuously** -- the expectation would pass equally for an
implementation that performed the read and found nothing, which is the very short-circuit this scenario
exists to prove. So the file ships, and this README documents its unreachability rather than the folder
hiding a file with no visible purpose.

### 5.4 Departures from a tree rule, named

| Departure | Rule | Reason |
|---|---|---|
| `cardxref.txt` is 50 bytes, not the seed row's 36 | master section 3.10 | The copybook sums to 50 and the seed file omits the trailing `FILLER X(14)`; authored at full copybook width with that `FILLER` space-padded. See the note below |
| The cross-reference deliberately omits the feed record's card | master section 8 | That section's carve-out permits a key to be deliberately omitted to trigger a diagnostic and requires the README to name it; card `4859452612877065` is absent by design (section 1) |
| The account master holds a row no lookup asks for | master section 8 | The deliberate counterpart of the omission: it is what makes the skipped read genuine rather than vacuous (sections 1 and 3) |
| The folder holds three record files where the program opens six datasets | master section 2 | `CUSTFILE`, `CARDFILE` and `TRANFILE` are opened and never read, so a fixture for them would assert nothing (the note in section 5.3) |
| The domain is named `preflight` where the house tree names it `prepost` | master section 4.2 | Recorded at the head of this document; the two denote the same program |

Assumptions: the cardxref fixture is authored at **50 bytes although the shipped seed row measures
36**, and the reason is that the seed **file omits the trailing padding**, not that the record is 36
bytes wide. The 50-byte width has **two independent proofs**: `app/cpy/CVACT03Y.cpy` declares
`FILLER PIC X(14)` at line **8**, giving `16 + 9 + 11 + 14 = 50`, and its own banner at line 2 reads
`RECLN 50`; and `app/cbl/CBTRN01C.cbl:77-79` independently declares `FD-XREFFILE-REC` as
`FD-XREF-CARD-NUM PIC X(16)` plus `FD-XREF-DATA PIC X(34)`, which is **50 bytes** in the program's own
file record. Master section 3.10 rules that card-xref fixtures are authored at the full 50-byte width
with that `FILLER` space-padded, and adds that a scenario deliberately wanting the 36-byte seed shape
must say so explicitly. **This scenario does not use a 36-byte variant**, and no statement here should
be read as licensing one; a folder that wanted it would have to declare the choice in its own README.

---

## 6. Determinism -- and what each run of blanks means here

Assumptions: **a 26-blank timestamp is a legitimate value, not an error**, and master section 8.1
requires every scenario README to say **which** of its two meanings applies every time it shows one,
because the byte pattern normalisation produces is identical to the byte pattern real absent data
produces and the meaning cannot be recovered from the bytes. In this folder the answer is the first
meaning: `DALYTRAN-PROC-TS` at offset **304** is 26 **genuine input blanks** -- the feed record has not
been processed yet -- and that is exactly why the sibling harness declares
**`ledger.daily_transactions.proc_ts` as nullable** while `ledger.transactions.proc_ts` is `NOT NULL`.
Neither timestamp is read, written or normalised anywhere in this domain, because `CBTRN01C` writes no
record at all, so master section 8.1's posting and interest masking rules do not engage; the field
appears here only because the feed record carries it.

**The same disambiguation applies to every other run of spaces in this folder, and the two meanings are
not interchangeable:**

| Bytes | Offset | File | Meaning |
|---|---:|---|---|
| 26 spaces, `DALYTRAN-PROC-TS` | 304 | `dailytran.txt` | **a legitimate absent value** -- "not yet processed" |
| 20 spaces, `FILLER X(20)` | 330 | `dailytran.txt` | **padding** -- no field, no meaning |
| 10 spaces, `ACCT-GROUP-ID` | 112 | `acctdata.txt` | **a legitimate absent value** -- the interest domain's disclosure-group key, inert here |
| 178 spaces, `FILLER X(178)` | 122 | `acctdata.txt` | **padding** |
| 14 spaces, `FILLER X(14)` | 36 | `cardxref.txt` | **padding** -- the widening of section 5.4 |

The blank `ACCT-GROUP-ID` is the field master section 7.2.3 calls the canonical reshaped field for the
**interest** domain; in this folder it is left exactly as the seed row has it and is never read.

The doubled diagnostics of section 2.2 are deterministic too: exactly two failing lookups for one
record on every run, not a race. Everything else holds by construction -- every byte is literal, there
is no clock value, no random identifier and no environment-derived string, and each test provisions and
tears down its own workspace, per master section 8.

---

## 7. Target-side contracts this scenario agrees with

The migrated pass is
[`PreflightDailyTransactionsJob`](../../../../../main/java/com/carddemo/batch/job/PreflightDailyTransactionsJob.java),
the job bean **`preflight-daily-transactions`** and **state 3** of the eleven-state
`carddemo-daily-batch` machine. It is read-only and validation-only, emits a diagnostic per unresolved
card and writes no record, and it is the only job in the chain whose COBOL original has **no JCL driver
anywhere among the baseline's thirty-eight jobs**. Five of its contracts constrain what this folder may
expect; each is declared there and named here rather than restated:

- **An unresolvable card is a diagnostic, never a rejected row**, and the pass has **no warn tier to
  report at all** -- the target-side expression of section 3's finding that `CBTRN01C` declares no
  return code, no reject stream and no counters.
- **The reported card number is masked to its last four digits**, and **the transaction identifier is
  redacted to its declared width**. Both lines are emitted at warning level and so reach durable log
  storage; rendering the sixteen digits of a primary account number, or the ledger's own primary key,
  into that storage is what the migration plan's data-exposure rule forbids. The template around both is
  verbatim from `:181-183`, including the middle fragment's terminal `ID-` with no trailing space. This
  is a **documented, deliberate qualification** of the verbatim-text rule and the one place in this pass
  where it is knowingly qualified -- the reference renders both values in full, which is what the marker
  table in section 3 describes.
- **The account lookup is asserted never to be issued.** This is the target-side form of section 3's
  claim: the assertion is on the **absence** of the interaction, not on the message, because a message
  assertion alone would pass for an implementation that read the master first and discarded the answer.
  The account identifier is not even available on that branch.
- **The account master row is asserted to be present**, so the skip is genuine. A row count of one on
  `account.accounts` alongside zero lookups is the pair of facts that makes the claim substantive.
- **The clean loop replaces the reference's post-end-of-file lookup**, carried as documented divergence
  D-7 (section 2.2), so a run over N records performs exactly N cross-reference lookups.

The bytes are decoded by the mappers
[`DailyTransactionMapper`](../../../../../main/java/com/carddemo/batch/mapper/DailyTransactionMapper.java),
[`CardXrefRecordMapper`](../../../../../main/java/com/carddemo/batch/mapper/CardXrefRecordMapper.java)
and
[`AccountRecordMapper`](../../../../../main/java/com/carddemo/batch/mapper/AccountRecordMapper.java),
over the shared codecs `CopybookLayout`, `FixedWidthCodec` and `ZonedDecimalCodec` in
`services/common-lib/src/main/java/com/carddemo/common/codec/`. One decoding fact is worth naming
because getting it wrong is silent: the unsigned `PIC 9(n)` identifiers in these records --
`DALYTRAN-CAT-CD`, `DALYTRAN-MERCHANT-ID`, `XREF-CUST-ID`, `XREF-ACCT-ID`, `ACCT-ID` -- decode as
**`CopybookLayout.Kind.UINT`**, plain ASCII digits, and are **never** routed through sign-overpunch
interpretation. Applying the signed rule to `0001` would read its last byte as a sign and turn a
four-digit category into three digits plus a sign.

The rows load into the objects the sibling harness declares in
[`test-harness-schemas-and-foreign-tables.sql`](../../../db/testharness/test-harness-schemas-and-foreign-tables.sql)
under the `test` profile of
[`application-test.yml`](../../../application-test.yml): `ledger.daily_transactions` for the feed,
`account.card_xref` -- keyed `card_num CHAR(16)` -- for the failing lookup, and `account.accounts` --
keyed `account_id BIGINT` -- for the row that is never read. A scenario owns only its own rows and
never seeds another service's schema, per master section 11.3.

---

## 8. Boundaries

### 8.1 No golden file lives in this folder

There is **no `*.expected` file here and none is to be added.** This folder holds inputs. Unlike the
posting and interest domains, this one has **no golden corpus at all** in either tree -- the program
produces console diagnostics rather than records, so its expectation is stated as marker presence and
absence in section 3. `tests/**` remains reference-only and no golden is ever regenerated.

### 8.2 The sibling folders

`../unmatched_account` breaks the **second** link of the chain -- its cross-reference resolves correctly
and its account master holds a different account -- and `../happy_path` breaks neither. Each ships the
same three files at the same widths; only which seed row is copied differs. Measured: this folder
differs from `../happy_path` in **one file** (`cardxref.txt`) and from `../unmatched_account` in
**two** (`cardxref.txt` and `acctdata.txt`), and all three share a byte-identical `dailytran.txt`. The
cross-links are for orientation only -- every contract fact of **this** scenario is stated in this
document and none is deferred to a sibling. Read the three together and they cover the two ways the
chain can fail plus the case where it does not, which is what shows each diagnostic is reported for its
own condition and no other.

---

## 9. Data governance and synthetic provenance

**Every value in this folder is synthetic and seed-derived**, taken from the published AWS CardDemo
sample seed datasets under `app/data/ASCII/`, which ship with the upstream open-source project as
fabricated demonstration data. **The bytes here represent no real person and no real account.** Master
section 11.1 carries the tree-level attestation this scenario inherits; the per-file provenance is
below.

| File | Seed file | Seed row | Relationship to that row |
|---|---|---:|---|
| `dailytran.txt` | `app/data/ASCII/dailytran.txt` | **1** | **byte-identical** -- an exact, verbatim copy |
| `cardxref.txt` | `app/data/ASCII/cardxref.txt` | **4** | identical on `[0:36]`; 14 spaces appended, per master section 3.10 |
| `acctdata.txt` | `app/data/ASCII/acctdata.txt` | **7** | **byte-identical** -- an exact, verbatim copy |

**No business-rule field is reshaped away from its seed value anywhere in this folder -- that set is
empty.** Master section 10's fifth item requires every reshaped business-rule field to be named, and
for this scenario there is nothing to name: the statement is made explicitly so a reader knows the
absence is a **finding** rather than an omission from this document. The scenario is created by
**selection, not by reshaping**. The cross-reference row is row **4** of its seed -- card
`0927987108636232`, customer `000000020`, account `00000000020` -- chosen because it is a real seed row
naming a card the feed record does not carry, where `../happy_path` and `../unmatched_account` both
take row **21**, which is where card `4859452612877065` appears. Swapping which seed row is copied is
the entire construction.

**The only byte-level difference from any seed row in this folder is the `cardxref.txt` 36-to-50
`FILLER X(14)` widening** described in section 5.4. It is width conformance to the copybook, not a
business-rule field change, so it does not qualify the empty-set statement above.

**The key deliberately omitted, stated plainly: card `4859452612877065` is deliberately NOT present as
an `XREF-CARD-NUM` key in `cardxref.txt`.** That single absence is the whole mechanism of this
scenario, it is the omission master section 8's carve-out requires a README to name, and it is the one
sentence in this attestation a reader must not miss. Restoring the key would leave every byte count,
every width and every governance check passing while the scenario silently stopped asserting anything.

Identity and primary-account-number bytes are taken **unchanged** from the seed -- both card numbers,
both customer identifiers and both account identifiers named in this document. Master section 11.2
records why the published seeds are used rather than freshly minted values: a card number invented here
would be indistinguishable from a live one by inspection, so its provenance would have to be attested
rather than pointed at, whereas each value here can be pointed back to a specific seed row by its key.

Trade-offs: this folder's card numbers differ between its feed record and its cross-reference row,
which reads at first glance like an inconsistency in the fixture. It is the design, and stating it in
three places -- section 1, section 5.3 and here -- is the accepted cost of a construction whose whole
point is a key that does not resolve. The alternative of reshaping the feed record's card number to a
value no seed row carries was rejected for the reason master section 11.2 gives for the tree as a
whole, and it would also have made the reshaped-field set non-empty for no gain.

Master section 11.3 governs the rest and is not restated: no secret, credential, connection string or
endpoint appears in any file here, money never leaves fixed point, and nothing in this folder modifies
the COBOL baseline or the parity oracle. The permitted framing throughout is the one that section
fixes -- the baseline does X, the migrated Java implements Y, and the divergence is documented.

---

## 10. What drives this corpus, and what reads it

This corpus is a **driven input**, and two classes read it.

`PreflightDailyTransactionsJobTest` seeds a run from this scenario: it opens **`dailytran.txt`** as the
feed and **`acctdata.txt`** as the account master -- decoding the latter through the production
`AccountRecordMapper` -- and deliberately seeds **no** cross-reference row, which is how the committed
`cardxref.txt` miss is reproduced against a database. Master section 1.5 records the same mapping,
measured from the consuming classes. An edit to either of those two files therefore changes what a run
asserts.

`BatchFixtureContractTest` reads all three. It enumerates this scenario among the sixteen fixture trees
as a closed set in both directions, holds every file here to its declared geometry and the governance
rules of section 5.2, and asserts the two values that make the scenario discriminating: that
`cardxref.txt`'s `XREF-CARD-NUM` is **not equal to** `dailytran.txt`'s `DALYTRAN-CARD-NUM`, and that
`acctdata.txt` still carries its seed expiry -- so `cardxref.txt` is protected even though the job test
does not open it.

Assumptions: the distinction from `interest/**` is deliberate and is stated rather than left to
inference. Those three scenario directories are **mirrors** -- `CalculateInterestJobTest` resolves its
fixtures under the repository root at `tests/fixtures/interest/<scenario>/` and so reads the
**reference** tree, never this one -- whereas this folder is opened directly. One tree therefore serves
two purposes, and a reader deciding whether an edit here changes a run's outcome has to know which case
a folder is in. The consequence of guessing wrong runs both ways: reading this folder as a mirror would
license an edit to `dailytran.txt` that silently changes what a job assertion proves, while reading an
`interest/**` folder as driven would send an author looking for a failure in this module that only the
reference suite can produce.

---

*This README is the mandatory Explainability carrier for the three record files in this directory,
required by master section 10 and by user-specified Rule 1. `config/checkstyle/checkstyle.xml` limits
its audit set to `java` (line 215), and `config/rule1/rule1_gate.py` -- which does govern `.md` for
label form -- exempts every path under `src/test/resources/fixtures/`, so **no linter reads this
prose**. Whether each rationale names a real consequence, and whether every number and line citation is
true, are review obligations no lexical gate can decide. That is why the labels above are written in
the one permitted plain form and why every citation names the line it rests on.*
