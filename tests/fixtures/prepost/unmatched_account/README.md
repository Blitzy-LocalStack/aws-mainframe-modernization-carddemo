# CBTRN01C pre-post — unmatched account (card in XREF, account absent → not found)

A single daily transaction whose card number **is** present in the card
cross-reference but whose resolved account is **absent** from the account master.
Driving [`app/cbl/CBTRN01C.cbl`](../../../../app/cbl/CBTRN01C.cbl) against this
fixture exercises the **account-not-found** path: `2000-LOOKUP-XREF` succeeds
(DISPLAYing `SUCCESSFUL READ OF XREF`), but `3000-READ-ACCOUNT` takes its
`INVALID KEY` branch, so the program DISPLAYs `INVALID ACCOUNT NUMBER FOUND`
followed by `ACCOUNT 00000000007 NOT FOUND`, and exits with `RETURN-CODE = 0`.

> **Why this README exists (Explainability, AAP §0.10.1).** The three fixtures in
> this folder are static, fixed-width `.txt` records that cannot carry
> docstrings, so this document is their mandated *why*. The **authoritative**
> byte-encoding contract lives in the master
> **[`tests/fixtures/README.md`](../../README.md)**; this scenario README
> **references** it and **must not restate or contradict it**.

## 1. Program behaviour exercised — `app/cbl/CBTRN01C.cbl`

1. **XREF lookup (`2000-LOOKUP-XREF`) HITS.** `DALYTRAN-CARD-NUM =
   4859452612877065` is **present** in `cardxref.txt` and resolves `XREF-ACCT-ID =
   00000000007`; the program DISPLAYs `SUCCESSFUL READ OF XREF` and leaves
   `WS-XREF-READ-STATUS = 0`.
2. **ACCOUNT lookup (`3000-READ-ACCOUNT`) MISSES.** The resolved account
   `00000000007` is **not** a key in `acctdata.txt` (which instead carries the
   unrelated account `00000000020`), so the indexed read takes `INVALID KEY`, the
   program DISPLAYs `INVALID ACCOUNT NUMBER FOUND`, sets `WS-ACCT-READ-STATUS = 4`,
   and the main loop then DISPLAYs `ACCOUNT 00000000007 NOT FOUND`.

No abend occurs (an unmatched account is a normal not-found report, not an I/O
error), so the run ends with `RETURN-CODE = 0`.

> **Assumption (loop control-flow characteristic).** As documented in the
> `happy_path` sibling README, `CBTRN01C` performs the lookup once more on the
> end-of-file pass, so both diagnostics are DISPLAYed twice for one input record.
> The paired test asserts on **marker presence**, never on an exact repetition
> count.

## 2. Files in this folder

| File | Layout (copybook, reclen) | Role for `CBTRN01C` |
|---|---|---|
| `dailytran.txt` | `DALYTRAN-RECORD` (`CVTRA06Y`, 350) | sequential input; its card resolves through XREF to account `00000000007` |
| `cardxref.txt` | `CARD-XREF-RECORD` (`CVACT03Y`, 50) | indexed lookup that **hits** and resolves account `00000000007` |
| `acctdata.txt` | `ACCOUNT-RECORD` (`CVACT01Y`, 300) | indexed lookup that **misses** (holds account `00000000020`, not `00000000007`) |

> **Trade-off (opened-not-read masters).** As in the other prepost scenarios, the
> customer/card/transaction masters that `CBTRN01C` opens but never reads are
> supplied by the paired test as **empty** indexed files.

These three records are **byte-identical** to the proven
`tests/fixtures/posting/reject_101_acct_missing/` seeds (whose posting-reject-101
scenario is precisely a card-in-XREF/account-absent condition), reused here for
the distinct `CBTRN01C` not-found/display behaviour.

## 3. Data governance / synthetic provenance

The account ids (`00000000007`, `00000000020`) and card number
(`4859452612877065`) are **synthetic, seed-derived** values from the published
AWS CardDemo sample datasets and represent **no real person or account**. The
full attestation lives in the master
[`tests/fixtures/README.md`](../../README.md) §10.
