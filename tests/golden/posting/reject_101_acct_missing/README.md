# Posting golden — `reject_101_acct_missing`

Byte-deterministic **golden-master EXPECTED OUTPUTS** for `app/cbl/CBTRN02C.cbl`
(batch transaction posting) under the **`reject_101_acct_missing`** scenario. The
`tests/integration/test_cbtrn02c_posting.py` harness runs the compiled program
against the paired input fixtures and then
[`../../../helpers/golden_compare.py`](../../../helpers/golden_compare.py)
(`assert_matches_golden`) diffs the program's **captured outputs** against the
`*.expected` files committed here.

> **Path-mirroring contract (1:1 / lockstep).** This directory is paired
> **one-for-one** with the INPUT fixtures at
> [`../../../fixtures/posting/reject_101_acct_missing/`](../../../fixtures/posting/reject_101_acct_missing/).
> The `<domain>/<scenario>/` names are **byte-identical** across the two trees
> (`posting` / `reject_101_acct_missing`, same spelling, same case, same
> underscores) because `tests/helpers/golden_compare.py` locates a scenario's
> expected output purely by path; if the trees drift, the comparator cannot find
> the golden and the test cannot assert. See
> [`../../../fixtures/README.md`](../../../fixtures/README.md) §2.1 (authoritative).
>
> **Why a README instead of code comments (Explainability, AAP §0.10.1).** The
> `.expected` files are static data and cannot carry docstrings, so this document
> is the mandated Explainability artifact for the folder: it records *why* each
> golden holds the value it does, so a reviewer can audit the scenario **without
> re-reading the COBOL**.

---

## 1. Scenario intent & business rule (reject reason **101**)

This scenario exercises exactly one path of `CBTRN02C`: a transaction whose card
**resolves** in the cross-reference but whose **account is absent** from the
account master, producing reject reason **101 — `ACCOUNT RECORD NOT FOUND`**.
The rule is quoted from `app/cbl/CBTRN02C.cbl`, which is **REFERENCE-ONLY and is
never modified** (AAP §0.8.2):

1. **`1500-A-LOOKUP-XREF` succeeds.** The card `4859452612877065` **is** present
   in the card cross-reference (`XREFFILE`), so **reject 100 does _not_ fire**;
   the read resolves `XREF-ACCT-ID = 00000000007`.
2. **`1500-B-LOOKUP-ACCT` fails.** The program does
   `MOVE XREF-ACCT-ID TO FD-ACCT-ID` and `READ ACCOUNT-FILE ... INVALID KEY`.
   Because account `00000000007` is **absent** from `ACCTFILE`, the `INVALID KEY`
   path sets `WS-VALIDATION-FAIL-REASON = 101` with the message
   **`ACCOUNT RECORD NOT FOUND`**.
3. **Validation stops here.** The credit-limit (102) and expiration (103) checks
   live **only inside** the `NOT INVALID KEY` branch of `1500-B-LOOKUP-ACCT`,
   which is never entered on this path — so **no further validation runs and
   nothing is posted**. The main loop takes its `ELSE` arm:
   `ADD 1 TO WS-REJECT-COUNT` then `PERFORM 2500-WRITE-REJECT-REC`.
4. **Return code.** At end-of-job, `IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE`
   — one reject means the process exits with `RETURN-CODE = 4`.

> **WHY the card resolves but the account does not (the entire point of the
> scenario).** The paired fixture's `cardxref.txt` maps card `4859452612877065`
> → account `00000000007`, but the paired `acctdata.txt` deliberately contains
> only a **decoy** account `00000000020` and omits `00000000007`. The *missing
> key* is engineered on purpose so the failure is precisely the missing-account
> path (reason 101) and nothing else — not a bad card (100), not over-limit
> (102), not expired (103).

---

## 2. Expected files in this folder (all six)

Each file below is a **data-out** expectation for one dataset the program touches
(or, in the reject case, deliberately leaves untouched). "Why" ties the value to
the responsible `CBTRN02C` paragraph and the business reason.

| File | Expected content | Why it holds that |
|---|---|---|
| `tranfile.expected` | **empty (0 bytes)** | Rejects are never posted: `2000-POST-TRANSACTION` (which performs `2900-WRITE-TRANSACTION-FILE`) is **not** called on the reject arm, so `TRANSACT`/`TRANFILE` gets no record. |
| `acctdat.expected` | `ACCTFILE` **unchanged** — the single decoy 300-byte account `00000000020` | The reject path skips `2800-UPDATE-ACCOUNT-REC`, so no balance/cycle fields are rewritten; the master is byte-identical to the input fixture. |
| `tcatbal.expected` | `TCATBALF` **unchanged** — the 50-byte account-`00000000007` category-balance row from the fixture | The reject path skips `2700-UPDATE-TCATBAL` (and its `2700-A-CREATE` / `2700-B-UPDATE` arms), so the category balance is untouched. |
| `dalyrejs.expected` | **one 430-byte reject record** | The observable assertion that reject **101** fired — written by `2500-WRITE-REJECT-REC` (layout in §3). |
| `return_code.expected` | `4` | `WS-REJECT-COUNT > 0 ⇒ MOVE 4 TO RETURN-CODE`; in the repo condition-code rubric **RC=4 = warn / soft-reject** (RC=0 pass, RC=4 warn/reject, RC≥8 fail). This is a *soft* reject — no abend. |
| `README.md` | this document | Explainability artifact (AAP §0.10.1). |

> **Why RC=4 and not 0 or ≥8.** `RC=0` would require zero rejects, but the
> missing account guarantees exactly one; `RC≥8` is reserved for hard failures
> (a bad `OPEN` / I-O status routes through `9999-ABEND-PROGRAM` → Language
> Environment abend `999`), which this path never reaches because the account
> **read** returns `INVALID KEY` cleanly rather than a file-status error.

---

## 3. The 430-byte reject record (`dalyrejs.expected`) — byte-exact layout

`2500-WRITE-REJECT-REC` builds the reject record as
`MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA` followed by
`MOVE WS-VALIDATION-TRAILER TO VALIDATION-TRAILER`, then
`WRITE FD-REJS-RECORD FROM REJECT-RECORD`. The `01 REJECT-RECORD` group in
`CBTRN02C` is therefore laid out exactly as:

| Offset (0-based) | Field (COBOL `PIC`) | Bytes | Content in this golden |
|---:|---|---:|---|
| `0 – 349`   | `REJECT-TRAN-DATA PIC X(350)` | 350 | The full `DALYTRAN-RECORD` **as read** (card `4859452612877065`, `DALYTRAN-AMT` `+504.77`, `DALYTRAN-ORIG-TS` = `2022-06-10 19:27:53.000000`, and a **blank 26-space `DALYTRAN-PROC-TS`**). |
| `350 – 353` | `WS-VALIDATION-FAIL-REASON PIC 9(04)` | 4 | **`0101`** (reason 101, zero-filled to 4 digits). |
| `354 – 429` | `WS-VALIDATION-FAIL-REASON-DESC PIC X(76)` | 76 | **`ACCOUNT RECORD NOT FOUND`** — 24 characters, right-space-padded with 52 spaces to fill `X(76)`. |
| | **Total** | **430** | `350 + 4 + 76 = 430` bytes (the file adds a single trailing LF ⇒ 431 bytes on disk). |

Reading the last 80 bytes of the record makes the trailer self-evident (reason
immediately followed by the space-padded description):

```
…2022-06-10 19:27:53.000000<26 spaces = blank PROC-TS>0101ACCOUNT RECORD NOT FOUND<52 spaces>
```

> **Why the description occupies a fixed 76 bytes.** `WS-VALIDATION-FAIL-REASON-DESC`
> is `PIC X(76)`, so COBOL right-pads the 24-character literal with spaces; the
> golden must carry those trailing spaces to preserve the copybook width. The
> comparator normalizes trailing whitespace (see §4), so the padding is a layout
> fact, not a comparison hazard.

---

## 4. Determinism & normalization

`dalyrejs.expected` is **fully deterministic** — no wall-clock value is embedded.
The processing timestamp `DALYTRAN-PROC-TS` is stamped **only** on the posting
path (`2000-POST-TRANSACTION` → `Z-GET-DB2-FORMAT-TIMESTAMP` → `MOVE DB2-FORMAT-TS
TO TRAN-PROC-TS`), which a reject **never reaches**; the copy that lands in the
reject record therefore keeps the fixture's **blank 26-space** `PROC-TS`. Only
**trailing-whitespace normalization** is needed before diffing (the sole variable
region — the description padding and record tail — is whitespace).

The other goldens carry no timestamp concern:

- `acctdat.expected` and `tcatbal.expected` are **deterministic masters** — they
  equal their input fixtures byte-for-byte because the reject path performs no
  update.
- `tranfile.expected` has **no record at all** (0 bytes), so there is nothing to
  normalize.

> **Assumption.** Fixed-width record offsets and the zoned-decimal sign
> overpunch in these goldens match the `app/cpy/` copybook contract exactly, as
> defined authoritatively in
> [`../../../fixtures/README.md`](../../../fixtures/README.md) §3 (this document
> does not restate those rules — see §6).
>
> **Trade-off.** Golden-master **byte comparison** was chosen over field-by-field
> assertions for these outputs: it yields a deterministic, audit-grade diff of
> every observable byte (records, reject stream, masters, return code) in one
> shot. The cost is coarser failure locality than per-field checks; that is
> acceptable here because the record layouts are single-sourced from `app/cpy/`
> and the only non-deterministic field (`PROC-TS`) is provably blank on this path.

---

## 5. Provenance — how these goldens are generated (run-and-capture)

These files are **produced by running the program, not hand-assembled** (hard
rule). To (re)generate them:

1. **Build** `CBTRN02C` via `scripts/build_test_programs.sh`
   (repo convention: `cobc -x -fixed -I app/cpy --std=ibm-strict`).
2. **Load** the paired inputs
   `tests/fixtures/posting/reject_101_acct_missing/{acctdata,cardxref,tcatbal}.txt`
   into GnuCOBOL indexed files via `tests/helpers/load_indexed.sh` (the
   `IDCAMS REPRO` analog), and bind `DALYTRAN` to `dailytran.txt`.
3. **Run** through `tests/helpers/cobol_runner.py` with the `ASSIGN`-name
   environment bindings exported by `scripts/test_env.sh`
   (`DALYTRAN`, `TRANFILE`, `XREFFILE`, `DALYREJS`, `ACCTFILE`, `TCATBALF`).
4. **Capture** the outputs: `TRANFILE` (empty), `ACCTFILE` → `acctdat`
   (unchanged), `TCATBALF` → `tcatbal` (unchanged), `DALYREJS` → the one reject
   record, and the process `RETURN-CODE` (= `4`).
5. **Normalize & commit** by blessing through the comparator's update path —
   `assert_matches_golden(..., update=True)` (or `CARDDEMO_UPDATE_GOLDENS=1`) —
   which writes the normalized captured values back into this folder.
6. **Regenerate** (re-run the update path) whenever the paired fixtures change.

> **Why capture-then-bless rather than author by hand.** A financial golden must
> reflect what the program *actually* emits, byte-for-byte; hand-typing 430-byte
> records invites transcription errors in the overpunch/width fields. If a fresh
> run yields anything other than the values above, the **fixtures or the load
> step** are wrong — not this golden.

---

## 6. Encoding contract & cross-links (reference — not restated here)

- **Byte-encoding rules are authoritative elsewhere.** Fixed-width offsets, the
  zoned-decimal sign-overpunch table, implied decimals, and the LF line-ending /
  trailing-newline convention all live in
  [`../../../fixtures/README.md`](../../../fixtures/README.md) §3. This document
  **links** that contract and does **not** repeat it (avoids drift; single source
  of truth).
- **Paired input fixtures** (the "data-in" side, with per-file `ASSIGN`/copybook
  table and outcome):
  [`../../../fixtures/posting/reject_101_acct_missing/README.md`](../../../fixtures/posting/reject_101_acct_missing/README.md).
- **Comparator** used to diff captured output against these goldens:
  [`../../../helpers/golden_compare.py`](../../../helpers/golden_compare.py).
- **Copybooks that define the record layouts** (under `app/cpy/`, REFERENCE-only):
  `CVTRA06Y` (DALYTRAN, 350 bytes), `CVACT03Y` (XREF, 50), `CVACT01Y` (ACCOUNT,
  300), `CVTRA01Y` (TCATBAL, 50). The reject record reuses the 350-byte DALYTRAN
  layout for its data portion plus the 80-byte in-program `VALIDATION-TRAILER`.

## Sources & scope

- **Rule source:** `app/cbl/CBTRN02C.cbl` — paragraphs `1500-VALIDATE-TRAN`,
  `1500-A-LOOKUP-XREF`, `1500-B-LOOKUP-ACCT`, `2500-WRITE-REJECT-REC` (and the
  skipped `2000-POST-TRANSACTION` / `2700-UPDATE-TCATBAL` / `2800-UPDATE-ACCOUNT-REC`).
- **Seed provenance:** the decoy account `00000000020` is copied from the
  published sample seed `app/data/ASCII/acctdata.txt`; all identifiers are
  synthetic demonstration data (no real person or payment instrument).
- Production COBOL, copybooks, and seed datasets are **REFERENCE-only and are
  never edited** (AAP §0.8.2). The goldens are derived by running the unmodified
  program against derived fixtures.
