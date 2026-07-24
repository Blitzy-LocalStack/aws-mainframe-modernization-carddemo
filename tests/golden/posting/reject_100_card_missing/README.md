# Golden master — posting / reject_100_card_missing

> **Consumer test.** These goldens are read and asserted by the pytest integration test [`tests/integration/test_cbtrn02c_posting.py`](../../../integration/test_cbtrn02c_posting.py), which runs the compiled `CBTRN02C` against the paired fixtures and diffs its output against these `.expected` files via `assert_matches_golden`.

## 1. What this folder is (scenario intent)

This folder holds the **EXPECTED outputs** (the "golden master") for one posting
scenario of the batch transaction-posting program. The comparator
[`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py) diffs the
actual output produced by the compiled `app/cbl/CBTRN02C.cbl` against the
`.expected` files stored here and fails the test on any material difference.

These EXPECTED outputs are paired **1:1** with the INPUT fixtures at
[`tests/fixtures/posting/reject_100_card_missing/`](../../../fixtures/posting/reject_100_card_missing/).
The two folder paths are byte-identical below their `fixtures/` vs `golden/`
roots (`posting/reject_100_card_missing/`) — this lockstep naming is intentional
so that a reader (or a test-discovery loop) can move between an input scenario
and its expected result without a lookup table.
<!-- WHY (Trade-off): lockstep folder naming trades a tiny bit of path
     redundancy for zero-ambiguity input↔output pairing, which an auditor can
     verify by eye. A central manifest mapping inputs to outputs was considered
     and rejected because it adds a second source of truth that can drift. -->

`app/cbl/CBTRN02C.cbl` is the **program under test and is REFERENCE ONLY — it is
never modified by this test suite** (AAP §0.8.2, §0.10.2). The suite encodes the
program's documented behavior; it does not change it.

---

## 2. Business rule under test — reject reason 100 (card missing from XREF)

This scenario exercises the card cross-reference (XREF) lookup in
`CBTRN02C` paragraph `1500-A-LOOKUP-XREF` (reached from `1500-VALIDATE-TRAN`).

- The daily transaction's `DALYTRAN-CARD-NUM` (`4859452612877065`) is
  **absent from the XREF fixture**. The paired `cardxref.txt` contains only a
  single **decoy** row for card `0927987108636232` and *deliberately omits* the
  transaction's card number.
  <!-- WHY (Assumption): a decoy row (rather than an empty XREF) proves the
       failure is a genuine key miss on a populated, indexed file, not an
       empty-file artifact — the INVALID KEY branch is what we mean to cover. -->
- The program performs `MOVE DALYTRAN-CARD-NUM TO FD-XREF-CARD-NUM` and then
  `READ XREF-FILE ... INVALID KEY`. Because the key is not present, the
  `INVALID KEY` branch fires and sets `WS-VALIDATION-FAIL-REASON = 100` with the
  fail-reason description text:

  > `INVALID CARD NUMBER FOUND`

  This message is quoted **verbatim** because `dalyrejs.expected` asserts it
  **byte-for-byte** (see §4).
- Validation **stops at the card lookup**: since the reason is now non-zero,
  `1500-VALIDATE-TRAN` does **not** call `1500-B-LOOKUP-ACCT`, so the account,
  credit-limit, and expiration checks never run for this record.
- Back in the main read loop, a non-zero reason drives
  `ADD 1 TO WS-REJECT-COUNT` followed by `PERFORM 2500-WRITE-REJECT-REC`. At
  end-of-job, `WS-REJECT-COUNT > 0` sets `RETURN-CODE = 4`.

---

## 3. Expected-outputs inventory

Every sibling `.expected` file in this folder and what it asserts. Sizes are the
raw on-disk bytes (fixed-width record **plus** one trailing `LF`, except the
empty `tranfile`).

| File | Size | Meaning |
|---|---|---|
| `tranfile.expected` | 0 bytes (empty) | Rejected transactions are **not** posted. `2900-WRITE-TRANSACTION-FILE` runs only under `2000-POST-TRANSACTION`, which is skipped on a reject, so `TRANFILE` receives no writes. |
| `acctdat.expected` | 301 bytes — 300-byte `ACCOUNT-RECORD` (+ `LF`) | `ACCTFILE` is **unchanged** versus the paired fixture `acctdata.txt`. No `2800-UPDATE-ACCOUNT-REC` REWRITE occurs on a reject, so this is byte-identical to the input master. |
| `tcatbal.expected` | 51 bytes — 50-byte `TRAN-CAT-BAL-RECORD` (+ `LF`) | `TCATBALF` is **unchanged** versus the fixture `tcatbal.txt`. Paragraph `2700-UPDATE-TCATBAL` is never reached, so this is byte-identical to the input. |
| `dalyrejs.expected` | 431 bytes — one 430-byte reject record (+ `LF`) | The reject stream — the single record written by `2500-WRITE-REJECT-REC` (layout in §4). |
| `return_code.expected` | 2 bytes — `4` (+ `LF`) | `WS-REJECT-COUNT > 0` ⇒ `RETURN-CODE = 4` (warn/reject on the repo rubric: `RC=0` pass / `RC=4` warn / `RC≥8` fail). |

**Naming note (intentional, not a typo).** The golden master for the account
master is `acctdat.expected` — **no trailing `a`** — while its paired input
fixture is `acctdata.txt` — **with the `a`**. The transaction-category-balance
pair keeps the same stem on both sides: `tcatbal.expected` ↔ `tcatbal.txt`.
<!-- WHY (Assumption): the golden stems track the runtime ASSIGN/DDNAME the
     program writes (ACCTFILE→acctdat), whereas the fixture stems track the
     human-facing seed name (acctdata). Documenting the asymmetry here prevents
     a maintainer "correcting" one side and breaking the 1:1 pairing. -->

---

## 4. The 430-byte reject-record layout (`2500-WRITE-REJECT-REC`)

`dalyrejs.expected` contains exactly one physical reject record. Paragraph
`2500-WRITE-REJECT-REC` builds it by `MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA`
and appending the validation trailer, then `WRITE FD-REJS-RECORD`. The byte
positions below were verified against the committed file.

| Bytes | Field | Value in this golden |
|---|---|---|
| **1–350** | `REJECT-TRAN-DATA PIC X(350)` | The full 350-byte `DALYTRAN-RECORD` exactly as read (verbatim record 1 of `dailytran.txt`). Its `PROC-TS` sub-field at bytes **305–330** is **26 blanks** (deterministic — see §5). |
| **351–354** | `WS-VALIDATION-FAIL-REASON PIC 9(04)` | `0100` (the reject reason, four zero-padded digits). |
| **355–430** | `WS-VALIDATION-FAIL-REASON-DESC PIC X(76)` | `INVALID CARD NUMBER FOUND` — the 25-character message, space-padded to 76 (51 trailing spaces). |

In short: **`350` (transaction image) + `0100` (reason) + `76`-char message = `430` bytes**, plus the one trailing `LF` that makes the file 431 bytes on disk.

---

## 5. Determinism & normalization

Golden-master comparison only works if the EXPECTED bytes are reproducible on
every run. This scenario is fully deterministic:

- **`dalyrejs.expected` carries no live timestamp.** The transaction's embedded
  original timestamp `DALYTRAN-ORIG-TS` (`2022-06-10 19:27:53.000000`) is a
  fixed seed literal baked into the fixture, and the processing timestamp
  `DALYTRAN-PROC-TS` is **blank (26 spaces)** on this path. `PROC-TS` is stamped
  only inside `2000-POST-TRANSACTION`, and that paragraph never runs for a
  reject. Because there is no live clock value in the record, comparison needs
  **no `<TS>` sentinel / timestamp-scrubbing at all** for this golden.
  <!-- WHY (Refactoring rationale): scenarios that DO post a transaction must
       blank a live PROC-TS before comparison; this reject scenario is simpler
       precisely because the reject path leaves PROC-TS blank, so we document
       the absence of a sentinel rather than silently omitting it. -->
- **`acctdat.expected` and `tcatbal.expected` are deterministic masters** —
  byte-for-byte echoes of their input fixtures, since no update paragraph runs
  (see §2). **`tranfile.expected` is empty**, so it has no timestamp concern.

The comparator `golden_compare.py::normalize()` canonicalizes line endings to
`\n` and treats a single trailing end-of-file newline as equivalent to none, so
a raw `ORGANIZATION SEQUENTIAL` program output (concatenated fixed-width records
with no delimiters) compares equal to the committed golden (which stores the
record with one trailing `LF`). In its byte-exact **record mode** the normalizer
preserves each record's full fixed-width width and blanks only a
processing-timestamp field *in place* — which is a no-op here because `PROC-TS`
is already blank. The stored `.expected` files therefore retain the **full
fixed-width records with their raw trailing padding preserved**, for audit
fidelity, while the comparison stays stable across platforms.
<!-- WHY (Trade-off): we store full-width, padding-intact records (not
     whitespace-trimmed) so an auditor can measure field offsets directly on the
     committed artifact; run-time normalization, not the stored file, absorbs
     the harmless line-ending/EOF differences. -->

---

## 6. How these goldens were generated (provenance)

These files were produced by **running the compiled program and capturing its
output** — they are **not** hand-assembled. The primary run-and-capture path:

1. **Build** `CBTRN02C` via `scripts/build_test_programs.sh`
   (`cobc -x -fixed -I app/cpy --std=ibm-strict`).
2. **Load** the paired inputs
   `tests/fixtures/posting/reject_100_card_missing/{acctdata,cardxref,tcatbal}.txt`
   into GnuCOBOL indexed files via `tests/helpers/load_indexed.sh` (the
   `IDCAMS REPRO` analog), and bind the sequential `DALYTRAN` input.
3. **Run** the program through `tests/helpers/cobol_runner.py`, with the
   `ASSIGN`-name → file environment bindings exported by `scripts/test_env.sh`
   (`DALYTRAN`, `TRANFILE`, `XREFFILE`, `DALYREJS`, `ACCTFILE`, `TCATBALF`).
4. **Capture** the five observable outputs: `TRANFILE` (empty),
   `ACCTFILE` → `acctdat` (unchanged), `TCATBALF` → `tcatbal` (unchanged),
   `DALYREJS` → `dalyrejs` (the one reject record), and `RETURN-CODE` (`4`).
5. **Normalize and commit** the captured bytes through
   `golden_compare.py::assert_matches_golden(..., update=True)`. Regeneration is
   deliberately guarded: a golden is rewritten only under a **two-step opt-in** —
   the caller must pass `update=True` **and** the environment must set
   `CARDDEMO_UPDATE_GOLDENS=1`, and the write is refused when running in CI.
   <!-- WHY (Assumption): requiring BOTH signals, and blocking CI writes,
        prevents an accidental `--update` run from silently "blessing" a
        regression as the new expected result. -->
6. **Validate** the captured golden: the `dalyrejs` trailer reason is `0100`,
   its message is `INVALID CARD NUMBER FOUND`, `RETURN-CODE` is `4`, `tranfile`
   is empty, and both masters are unchanged versus their fixtures.

---

## 7. See also (encoding contract & cross-references)

This README documents **what** these outputs are and **why** they look the way
they do. It intentionally does **not** restate the byte-encoding rules — those
live in one authoritative place:

- **Authoritative byte-encoding contract** (field widths, zoned-decimal sign
  overpunch, implied-decimal money, date formats, line endings / trailing
  newline): [`../../../fixtures/README.md`](../../../fixtures/README.md).
  Consult it for the encoding rules — they are **not duplicated here**.
- **Paired INPUT fixture scenario** (the inputs that drive this golden):
  [`../../../fixtures/posting/reject_100_card_missing/README.md`](../../../fixtures/posting/reject_100_card_missing/README.md).
- **Program under test (REFERENCE only):** `app/cbl/CBTRN02C.cbl`.
- **Comparator:** [`tests/helpers/golden_compare.py`](../../../helpers/golden_compare.py).

---

## Explainability notes (AAP §0.10.1)

The rationales below are stated once so the "WHY" behind the golden is explicit
for an audit reader; they are not repeated inline where they would merely restate
the file contents.

- **Assumptions.** The byte offsets in §4 assume the record layouts match the
  `app/cpy/` copybook contract: `CVTRA06Y` (`DALYTRAN-RECORD`, 350 bytes),
  `CVACT01Y` (`ACCOUNT-RECORD`, 300 bytes), `CVTRA01Y` (`TRAN-CAT-BAL-RECORD`,
  50 bytes), and `CVACT03Y` (`CARD-XREF-RECORD`, 50 bytes). The zoned-decimal /
  sign-overpunch and fixed-width conventions those copybooks imply are defined in
  the linked encoding contract (§7), not here.
- **Trade-off.** These outputs are checked with **golden-master byte
  comparison** (a deterministic whole-record diff) rather than field-by-field
  assertions. Byte comparison catches unexpected drift anywhere in the record
  (including padding and offsets) with one stable check; the cost is that a
  legitimate layout change requires a reviewed golden regeneration (§6).
- **Why the masters are unchanged.** `acctdat` and `tcatbal` echo their input
  fixtures because validation stops at the card lookup (§2): the posting and
  balance-update paragraphs (`2000`, `2700`, `2800`) never execute on a reject,
  so nothing rewrites `ACCTFILE` or `TCATBALF`.
