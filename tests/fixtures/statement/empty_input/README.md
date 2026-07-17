# Statement — Empty-Input Scenario (`CBSTM03A` / `CBSTM03B`)

This scenario drives the statement generator over a **genuinely empty (0-record)
transaction input** while a **valid** account, customer, and cross-reference are
present. It pins the program's behavior when there are **no transactions to
statement**: a statement is produced for the account holder with **no transaction
detail lines**.

> **Read [`tests/fixtures/README.md`](../../README.md) first** for the byte-level
> encoding contract, the `empty_input` semantics (§7), and the availability status
> of the `tests/golden/**` / `tests/integration/**` trees. This README does not
> restate that contract.

---

## 1. Intent & the MA-23 correction (KEY WHY)

**This is a _true_ empty-input scenario.** `trnxfile.txt` is a **0-byte file with
zero transaction records**; the other three inputs describe one valid, fully linked
customer/account/card so the program has something to statement — it simply finds
no transactions for them.

> **Why `trnxfile.txt` was made genuinely empty (MA-23 — Scenario Truthfulness).**
> A prior revision of this scenario shipped a `trnxfile.txt` containing **one**
> transaction whose card number was `9999999999999999` — a card **absent** from
> `xreffile.txt` (card `0500024453765740`). That is a **no-matching-transactions**
> case, **not** empty input: the file was not empty, it held an unmatchable record.
> The scenario name `empty_input` therefore contradicted its content. To make the
> name truthful — and to align with the master contract's rule that the *only* input
> treated as empty is a **genuinely zero-byte dataset** (§3.1, §7) — the
> unmatchable record was **removed** and `trnxfile.txt` is now **0 bytes**.
>
> **Alternatives Considered / Trade-off.** The alternative was to *keep* the
> no-match record and **rename** the directory to `no_matching_transactions`. That
> was rejected because `empty_input` is the AAP-sanctioned scenario name for the
> statement domain (AAP §0.4.4 / §0.5.1) and renaming would diverge from the AAP
> taxonomy and the golden-mirror path. Converting the content to a real empty input
> keeps the AAP name **and** makes it honest. If a dedicated no-match scenario is
> wanted later, it belongs in its own directory (e.g. `no_matching_transactions`) —
> it is out of scope here ("add a true zero-record case if required").

---

## 2. Files in this folder

| File | Copybook / layout | `RECLN` | Line ending | Records | Role |
|---|---|---:|---|---:|---|
| `trnxfile.txt` | `COSTM01` (TRNX) | 350 | — (0-byte) | **0** | **Genuinely empty** transaction input — the defining condition of this scenario. |
| `acctfile.txt` | `CVACT01Y` (ACCOUNT) | 300 | LF | 1 | Valid account `00000000050` (`ACCT-CURR-BAL 492.00`) to statement. |
| `custfile.txt` | `CVCUS01Y` (CUSTOMER) | 500 | LF | 1 | Valid customer `000000050` (the statement recipient). |
| `xreffile.txt` | `CVACT03Y` (XREF) | 50 | LF | 1 | card `0500024453765740` → cust `000000050` → acct `00000000050`. |

> **Empty-input form (documented per master §7).** `trnxfile.txt` is a **0-byte**
> file (no bytes, no records), **not** a file "containing no lines." This is the
> unambiguous form the loader/codec accept as empty: the program opens a valid but
> empty transaction dataset and reads straight to end-of-file, rather than failing
> on a missing dataset. **Why 0-byte rather than a blank line (Assumption):** the
> master contract rejects blank lines as zero-length "records" (§3.3), so the
> correct representation of "no records" is an empty file, not a file with an empty
> line.

---

## 3. Programs under test & expected outcome

- **Programs:** `app/cbl/CBSTM03A.CBL` (driver) with the I/O subprogram
  `app/cbl/CBSTM03B.CBL`. (Note the UPPERCASE `.CBL` file names — master §4.1.)
- **Expected outcome** (authoritative intent for the **[planned]** golden mirror
  `tests/golden/statement/empty_input/`, captured from actual output when authored
  — master §1): a statement is generated for customer `000000050` / account
  `00000000050` with the header/account/customer information populated but **zero
  transaction detail lines**, because the transaction input is empty. No transaction
  is matched, summed, or listed; the program completes normally (no abend) on the
  empty read.

---

## 4. Data governance / synthetic provenance (MA-24)

The customer, account, and card values here are **synthetic, seed-derived** test
data representing **no real person or account**:

- `custfile.txt` (customer `000000050` — name/SSN/DOB/address/etc.) is copied
  **byte-for-byte** from the published synthetic seed
  `app/data/ASCII/custdata.txt` (verified: the fixture row equals the seed row for
  cust `000000050`).
- `acctfile.txt`, `xreffile.txt` likewise derive from the published seeds
  (`acctdata.txt`, `cardxref.txt`) for account/card `…050` / `0500024453765740`.

These seeds ship with the upstream open-source AWS CardDemo project as fabricated
demonstration data. See master §10 for the full attestation.

---

## 5. Sources & scope

- **Consumed by (when present):** the statement integration test and the end-to-end
  statement cycle — both **[planned]**, not on the branch today (master §1).
- **Derived from (never edited):** `app/data/ASCII/{custdata,acctdata,cardxref}.txt`.
- **Record layouts:** `app/cpy/CVCUS01Y.cpy`, `CVACT01Y.cpy`, `CVACT03Y.cpy`, and
  the statement transaction layout `COSTM01`.
- **Programs:** `app/cbl/CBSTM03A.CBL`, `app/cbl/CBSTM03B.CBL`.

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2).

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the static
fixtures in this folder. Its load-bearing WHY is the MA-23 correction — this is a
**true** empty (0-record) transaction input, not the no-matching-transactions case
it previously (mis)labelled (§1).*
