# Posting golden — `reject_101_acct_missing`

Expected-output ("golden master") mirror for the posting scenario
`reject_101_acct_missing`. It is compared against a real run of
`app/cbl/CBTRN02C.cbl` by `tests/helpers/golden_compare.py`
(`assert_matches_golden`) and the `tests/integration/test_cbtrn02c_posting.py`
harness.

> **Path-mirroring contract:** this directory mirrors
> [`../../../fixtures/posting/reject_101_acct_missing/`](../../../fixtures/posting/reject_101_acct_missing/)
> one-for-one (same domain/scenario spelling and case), because
> `tests/helpers/golden_compare.py` pairs a scenario's **input** fixtures with
> its **expected** output purely by path. See
> [`../../../fixtures/README.md`](../../../fixtures/README.md) §2.1 (authoritative).
> Per the Explainability mandate (AAP §0.10.1), this README documents *why* each
> static `.expected` artifact holds the value it does, since a data file cannot
> carry a docstring.

## Golden files in this folder

| File | Meaning | Value |
|---|---|---|
| `return_code.expected` | The process return code emitted by `CBTRN02C` for this scenario. | `4` |

## Why `return_code.expected` is `4`

The single transaction in this scenario is **rejected with reason 101**, which
drives `CBTRN02C` to a soft-warning exit. The chain (rule source
`app/cbl/CBTRN02C.cbl`, REFERENCE-only — never modified, AAP §0.8.2):

1. `1500-A-LOOKUP-XREF` **succeeds** — card `4859452612877065` is present in
   `XREFFILE`, resolving `XREF-ACCT-ID = 00000000007` (so this is **not** a
   reject 100).
2. `1500-B-LOOKUP-ACCT` **fails** — `MOVE XREF-ACCT-ID TO FD-ACCT-ID` then
   `READ ACCOUNT-FILE ...`. Key `00000000007` is **absent** from `ACCTFILE`
   (the fixture holds only the decoy account `00000000020`), so `INVALID KEY`
   fires → `MOVE 101 TO WS-VALIDATION-FAIL-REASON`.
3. Because validation failed, the main loop takes its `ELSE` branch:
   `ADD 1 TO WS-REJECT-COUNT` and `PERFORM 2500-WRITE-REJECT-REC`.
4. At end-of-job `IF WS-REJECT-COUNT > 0 MOVE 4 TO RETURN-CODE`.

Therefore the observed process return code is **`4`**, which in the repository's
condition-code rubric means **warn / soft-reject** (RC=0 pass, **RC=4
warn/reject**, RC≥8 fail). This is a *soft* reject: no abend, no `TRANSACT`
record written, and no balance changes.

> **Why not 0 or ≥8 (Assumption / Trade-off):** RC=0 would require zero rejects,
> but the missing account guarantees exactly one; RC≥8 is reserved for hard
> failures (bad OPEN / I-O → `9999-ABEND-PROGRAM`, LE abend 999), which this
> path never reaches because the account *read* returns `INVALID KEY` cleanly
> rather than a file-status error.

## On-disk form (byte-exact)

`return_code.expected` contains exactly the ASCII digit `4` followed by a single
trailing newline (`4\n`, 2 bytes) — no label, quotes, padding, or `RC=` prefix.

> **Why a trailing newline (Trade-off):** it matches the suite-wide convention
> that fixtures and goldens are LF-terminated text
> ([`../../../fixtures/README.md`](../../../fixtures/README.md) §3), and
> `tests/helpers/golden_compare.py` normalizes line endings and trailing
> whitespace on both sides before diffing, so the comparison is stable.

## How this value is produced (not hand-invented)

The value is captured from a real run, then blessed through the same comparator
that later reads it:

1. Build `CBTRN02C` — `scripts/build_test_programs.sh`
   (`cobc -x -fixed -I app/cpy --std=ibm-strict`).
2. Load the paired fixtures into indexed files (`XREFFILE`, `ACCTFILE`,
   `TCATBALF`) and bind `DALYTRAN` to `dailytran.txt`.
3. Run the program and capture the process return code.
4. Bless the golden via the comparator's update path
   (`assert_matches_golden(..., update=True)` or `CARDDEMO_UPDATE_GOLDENS=1`),
   which writes the normalized captured value here.

If a fresh run yields anything other than `4`, the fixtures or the load step are
wrong — **not** this golden.

## Sources & scope

- Rule source: `app/cbl/CBTRN02C.cbl` (`1500-VALIDATE-TRAN`).
- Paired inputs: `tests/fixtures/posting/reject_101_acct_missing/` (see its
  README §4, authoritative for the expected outcome).
- Production sources and seed data are **REFERENCE-only and never edited**
  (AAP §0.8.2).
