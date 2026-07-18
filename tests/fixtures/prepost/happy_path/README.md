# CBTRN01C pre-post — happy path (card resolves through XREF → account found)

A single well-formed daily transaction whose card number is **present** in the
card cross-reference and whose resolved account is **present** in the account
master. Driving [`app/cbl/CBTRN01C.cbl`](../../../../app/cbl/CBTRN01C.cbl)
(the daily-transaction pre-post cross-reference validation program) against this
fixture exercises the **fully-matched** path: `2000-LOOKUP-XREF` succeeds and
`3000-READ-ACCOUNT` succeeds, so the program DISPLAYs `SUCCESSFUL READ OF XREF`
(with the card, account, and customer ids) followed by `SUCCESSFUL READ OF
ACCOUNT FILE`, and exits with `RETURN-CODE = 0`.

> **Why this README exists (Explainability, AAP §0.10.1).** The three fixtures in
> this folder are static, fixed-width `.txt` records that cannot carry
> docstrings, so this document is their mandated *why*. The **authoritative**
> byte-encoding contract — field widths, offsets, the zoned-decimal sign-
> overpunch table, and the LF / trailing-newline determinism rules — lives in the
> master **[`tests/fixtures/README.md`](../../README.md)**. This scenario README
> **references** that contract and **must not restate or contradict it**; it
> documents only the *scenario semantics* (the rule exercised and the expected
> observable behaviour).

## 1. Program behaviour exercised — `app/cbl/CBTRN01C.cbl`

`CBTRN01C` reads the daily-transaction file sequentially and, for each record,
performs two indexed cross-reference reads (master §CBTRN01C transcribes the
paragraphs verbatim):

1. **XREF lookup (`2000-LOOKUP-XREF`).** `DALYTRAN-CARD-NUM = 4859452612877065`
   (bytes 263–278 of the 350-byte `CVTRA06Y` daily-transaction record) is
   **present** in `cardxref.txt`; the indexed read on the primary card-number key
   succeeds and resolves `XREF-ACCT-ID = 00000000007` and `XREF-CUST-ID =
   000000007`. → the program DISPLAYs `SUCCESSFUL READ OF XREF`.
2. **ACCOUNT lookup (`3000-READ-ACCOUNT`).** Account `00000000007` is **present**
   in `acctdata.txt`; the indexed read on the 11-byte account-id key succeeds. →
   the program DISPLAYs `SUCCESSFUL READ OF ACCOUNT FILE`.

Because both reads succeed, no `INVALID …` diagnostic is emitted and the run ends
with `RETURN-CODE = 0`.

> **Assumption (loop control-flow characteristic).** `CBTRN01C`'s main loop
> performs the cross-reference lookup once **more** on the end-of-file pass (the
> lookup is outside the `IF END-OF-DAILY-TRANS-FILE = 'N'` guard), so for one
> input record the `SUCCESSFUL READ OF …` block is DISPLAYed twice. This is
> **production behaviour** (`app/cbl` is REFERENCE-only, AAP §0.8.2) — the paired
> test therefore asserts on **marker presence** (and the absence of any `INVALID`
> marker), never on an exact repetition count.

## 2. Files in this folder

| File | Layout (copybook, reclen) | Role for `CBTRN01C` |
|---|---|---|
| `dailytran.txt` | `DALYTRAN-RECORD` (`CVTRA06Y`, 350) | sequential input read by `1000-DALYTRAN-GET-NEXT`; supplies `DALYTRAN-CARD-NUM` |
| `cardxref.txt` | `CARD-XREF-RECORD` (`CVACT03Y`, 50) | indexed card→(customer, account) lookup read by `2000-LOOKUP-XREF` |
| `acctdata.txt` | `ACCOUNT-RECORD` (`CVACT01Y`, 300) | indexed account lookup read by `3000-READ-ACCOUNT` |

> **Trade-off / Assumption (opened-not-read masters).** `CBTRN01C` also `OPEN
> INPUT`s the customer, card, and transaction masters, but its main loop never
> `READ`s them, so they need only be **openable** valid indexed files. Rather
> than ship three more static fixtures whose bytes would never be read, the paired
> test loads **empty** indexed files for `CUSTFILE`/`CARDFILE`/`TRANFILE` (with the
> correct key geometry so `OPEN INPUT` returns file-status `00`). This keeps this
> fixture folder to exactly the three records the program actually consumes.

These three records are **byte-identical** to the proven, deterministic
`tests/fixtures/posting/happy_path/` seeds — `CBTRN01C` validates the same
card→XREF→account chain that `CBTRN02C` posts against, so reusing the same
seed values guarantees the relationship holds while keeping this domain
self-contained.

## 3. Data governance / synthetic provenance

The account id `00000000007`, card number `4859452612877065`, and customer id
`000000007` are **synthetic, seed-derived** values from the published AWS
CardDemo sample datasets and represent **no real person or account**. The full
attestation lives in the master
[`tests/fixtures/README.md`](../../README.md) §10.
