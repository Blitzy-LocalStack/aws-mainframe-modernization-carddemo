# CBTRN01C pre-post — unmatched card (card absent from XREF → skip)

A single daily transaction whose card number is **absent** from the card
cross-reference. Driving [`app/cbl/CBTRN01C.cbl`](../../../../app/cbl/CBTRN01C.cbl)
against this fixture exercises the **card-not-verified** path: `2000-LOOKUP-XREF`
takes its `INVALID KEY` branch, so the program DISPLAYs `INVALID CARD NUMBER FOR
XREF`, then (because `WS-XREF-READ-STATUS ≠ 0`) reports `CARD NUMBER … COULD NOT
BE VERIFIED. SKIPPING TRANSACTION ID-…`, never reads the account master, and
exits with `RETURN-CODE = 0`.

> **Why this README exists (Explainability, AAP §0.10.1).** The three fixtures in
> this folder are static, fixed-width `.txt` records that cannot carry
> docstrings, so this document is their mandated *why*. The **authoritative**
> byte-encoding contract lives in the master
> **[`tests/fixtures/README.md`](../../README.md)**; this scenario README
> **references** it and **must not restate or contradict it**.

## 1. Program behaviour exercised — `app/cbl/CBTRN01C.cbl`

1. **XREF lookup (`2000-LOOKUP-XREF`) MISSES.** `DALYTRAN-CARD-NUM =
   4859452612877065` is **not** a key in `cardxref.txt` (which instead carries the
   unrelated card `0927987108636232`), so the indexed read takes `INVALID KEY`,
   the program DISPLAYs `INVALID CARD NUMBER FOR XREF`, and sets
   `WS-XREF-READ-STATUS = 4`.
2. **Account lookup is skipped.** Because `WS-XREF-READ-STATUS ≠ 0`, the main loop
   takes its `ELSE` branch and DISPLAYs `CARD NUMBER 4859452612877065 COULD NOT BE
   VERIFIED. SKIPPING TRANSACTION ID-…` — `3000-READ-ACCOUNT` is **never**
   performed, so `SUCCESSFUL READ OF ACCOUNT FILE` is **absent**.

No abend occurs (a missing cross-reference is a normal skip, not an I/O error),
so the run ends with `RETURN-CODE = 0`.

> **Assumption (loop control-flow characteristic).** As documented in the
> `happy_path` sibling README, `CBTRN01C` performs the lookup once more on the
> end-of-file pass, so the skip diagnostics are DISPLAYed twice for one input
> record. The paired test asserts on **marker presence** plus the **absence** of
> `SUCCESSFUL READ OF ACCOUNT FILE`, never on an exact repetition count.

## 2. Files in this folder

| File | Layout (copybook, reclen) | Role for `CBTRN01C` |
|---|---|---|
| `dailytran.txt` | `DALYTRAN-RECORD` (`CVTRA06Y`, 350) | sequential input; its `DALYTRAN-CARD-NUM` is deliberately **absent** from `cardxref.txt` |
| `cardxref.txt` | `CARD-XREF-RECORD` (`CVACT03Y`, 50) | indexed lookup that **misses** the transaction's card |
| `acctdata.txt` | `ACCOUNT-RECORD` (`CVACT01Y`, 300) | present but **never reached** on this path (documents that the skip short-circuits before the account read) |

> **Trade-off (opened-not-read masters).** As in the other prepost scenarios, the
> customer/card/transaction masters that `CBTRN01C` opens but never reads are
> supplied by the paired test as **empty** indexed files, so this folder ships
> only the three records relevant to the cross-reference decision.

These three records are **byte-identical** to the proven
`tests/fixtures/posting/reject_100_card_missing/` seeds (whose posting-reject-100
scenario is precisely a card-absent-from-XREF condition), reused here for the
distinct `CBTRN01C` skip/display behaviour.

## 3. Data governance / synthetic provenance

The card numbers (`4859452612877065`, `0927987108636232`) and account ids are
**synthetic, seed-derived** values from the published AWS CardDemo sample
datasets and represent **no real person or account**. The full attestation lives
in the master [`tests/fixtures/README.md`](../../README.md) §10.
