# CBTRN02C posting — happy path (in-limit, non-expired → POST via `2700-B-UPDATE`)

A single well-formed daily transaction passes **both** `1500-VALIDATE-TRAN`
lookups and **both** boundary checks, so `CBTRN02C` **posts** it: it updates the
transaction-category balance through the *update-existing* branch
(`2700-B-UPDATE`), updates the account, writes one record to `TRANSACT`, emits
**no** reject, and exits with `RETURN-CODE = 0`.

> **Why this README exists (Explainability, AAP §0.10.1).** The four fixtures in
> this folder are static, fixed-width `.txt` records that cannot carry
> docstrings, so this document is their mandated *why*. The **authoritative**
> byte-encoding contract — field widths, offsets, the zoned-decimal sign-
> overpunch table, and the LF / trailing-newline determinism rules — lives in the
> master **[`tests/fixtures/README.md`](../../README.md)**. This scenario README
> **references** that contract by section and **must not restate or contradict
> it**; it documents only the *scenario semantics* (the rule exercised and the
> expected outcome). It is also the per-scenario documentation mandated by master
> §9.1 and the synthetic-provenance attestation mandated by master §10.3.

## 1. Rule exercised — `app/cbl/CBTRN02C.cbl` `1500-VALIDATE-TRAN`

Validation runs two lookups then two boundary checks; the happy path passes all
four and falls through to posting (master §6.1 transcribes the rule verbatim):

1. **XREF lookup (`1500-A-LOOKUP-XREF`).** `DALYTRAN-CARD-NUM = 4859452612877065`
   is **present** in `cardxref.txt`; the indexed read succeeds and resolves
   `XREF-ACCT-ID = 00000000007` (account 7). → no reject 100.
2. **ACCOUNT lookup (`1500-B-LOOKUP-ACCT`).** Account `00000000007` is **present**
   in `acctdata.txt`; the indexed read succeeds. → no reject 101.
3. **Over-limit boundary (`>=`).**
   `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT (0.00) − ACCT-CURR-CYC-DEBIT (0.00) +
   DALYTRAN-AMT (504.77) = 504.77`. Because `ACCT-CREDIT-LIMIT (2065.00) >=
   WS-TEMP-BAL (504.77)` is TRUE, the transaction is **within limit**. → no
   reject 102.
4. **Expiration boundary (`>=`).** Because `ACCT-EXPIRAION-DATE ("2024-12-13") >=
   DALYTRAN-ORIG-TS(1:10) ("2022-06-10")` is TRUE, the transaction is **not
   expired**. → no reject 103.
   > **Assumption.** The copybook field name is the preserved misspelling
   > `ACCT-EXPIRAION-DATE` (master §5.2) — reproduced exactly, not "corrected",
   > because the misspelled name *is* the contract the program compiles against.

Because `WS-VALIDATION-FAIL-REASON = 0`, the main loop calls
`2000-POST-TRANSACTION`, which:

- **`2700-UPDATE-TCATBAL` → `2700-B-UPDATE-TCATBAL-REC`:** the category-balance
  key `00000000007 / 01 / 0001` **already exists** (seeded — see §2), so the read
  succeeds and the *update-existing* branch runs `ADD DALYTRAN-AMT TO
  TRAN-CAT-BAL` then `REWRITE` — **not** the create branch `2700-A-CREATE`.
- **`2800-UPDATE-ACCOUNT-REC`:** `ADD DALYTRAN-AMT TO ACCT-CURR-BAL`; because
  `DALYTRAN-AMT (504.77) >= 0` it also `ADD`s to `ACCT-CURR-CYC-CREDIT` (the debit
  accumulator is left untouched), then `REWRITE`s the account.
- **`2900-WRITE-TRANSACTION-FILE`:** `WRITE`s exactly **one** posted `TRAN-RECORD`
  to `TRANSACT`.

No reject record is written, so `WS-REJECT-COUNT = 0` and the run ends with
`RETURN-CODE = 0`.

## 2. Why `TCATBAL` is seeded non-zero (key design choice)

`tcatbal.txt` deliberately carries a **pre-existing** category-balance row whose
key (`00000000007 / 01 / 0001`) matches the daily transaction's account, type,
and category, seeded to **`+100.00`**.

> **Assumption / Trade-off.** Seeding a non-zero, key-matching row forces
> `2700-UPDATE-TCATBAL` down the **update-existing** arm (`2700-B-UPDATE`) rather
> than the create arm (`2700-A-CREATE`). A *non-zero* starting balance also makes
> the update arithmetic **observable**: `100.00 + 504.77 = 604.77` is provably
> distinct from a create-from-zero result (`0.00 + 504.77 = 504.77`), so a test
> cannot pass by silently taking the wrong branch. The sibling
> `posting/zero_balance` scenario omits this row to cover the complementary
> `2700-A-CREATE` branch, so the two scenarios together exercise both arms of the
> create-vs-update decision (master §6.1).

## 3. Input fixtures

Each row below is one flat, fixed-width record at exactly its copybook `RECLEN`.
The widths, byte offsets, and sign-overpunch encoding are defined by the master
contract (§3, §5) and are **not** restated here.

| File | ASSIGN | Copybook | RECLEN | Organization | Key |
|---|---|---|---:|---|---|
| `dailytran.txt` | `DALYTRAN` | `CVTRA06Y` | 350 | SEQUENTIAL | (flat; no indexed load) |
| `cardxref.txt` | `XREFFILE` | `CVACT03Y` | 50 | INDEXED | `XREF-CARD-NUM` len 16 off 0 |
| `acctdata.txt` | `ACCTFILE` | `CVACT01Y` | 300 | INDEXED | `ACCT-ID` len 11 off 0 |
| `tcatbal.txt` | `TCATBALF` | `CVTRA01Y` | 50 | INDEXED | `TRAN-CAT-KEY` len 17 off 0 |

> **Loading (master §4.2).** GnuCOBOL binds each `SELECT … ASSIGN TO <NAME>` to a
> same-named environment variable at run time (`scripts/test_env.sh` sets them).
> The three `INDEXED` fixtures are loaded flat→indexed by
> `tests/helpers/load_indexed.sh` / `vsam_loader.load_indexed(...)` **before**
> `CBTRN02C` runs; `dailytran.txt` is read sequentially and needs no load.
> Indexed rows are supplied **pre-sorted by key** — each fixture here has a single
> row, so ordering is trivial.

Salient field values (shown as decimals; see master §3.4 / §3.5 for how they are
byte-encoded):

- `dailytran.txt` — `DALYTRAN-ID 0000000000683580`, `TYPE-CD 01`, `CAT-CD 0001`,
  `AMT +504.77`, `CARD-NUM 4859452612877065`,
  `ORIG-TS "2022-06-10 19:27:53.000000"`, `PROC-TS` blank (26 spaces).
- `cardxref.txt` — `XREF-CARD-NUM 4859452612877065` → `XREF-CUST-ID 000000007` →
  `XREF-ACCT-ID 00000000007`.
- `acctdata.txt` — account `00000000007`: `ACCT-CURR-BAL 193.00`,
  `ACCT-CREDIT-LIMIT 2065.00`, `ACCT-EXPIRAION-DATE 2024-12-13`,
  `ACCT-CURR-CYC-CREDIT 0.00`, `ACCT-CURR-CYC-DEBIT 0.00`.
- `tcatbal.txt` — category row `00000000007 / 01 / 0001` with `TRAN-CAT-BAL
  100.00`.

## 4. Expected outcome (authoritative for the golden mirror + integration asserts)

These values are the single source of truth that the **[planned]** golden mirror
`tests/golden/posting/happy_path/` and `tests/integration/test_cbtrn02c_posting.py`
both encode; they are byte-exact and consistent with the four fixtures in §3.

- **`RETURN-CODE = 0`.**
- **`TRANSACT` (`TRANFILE`): exactly `1` record written** — the posted
  transaction (`TRAN-AMT +504.77`).
- **`DALYREJS`: empty — `0` reject records.**
- **`TCATBAL` row `00000000007 / 01 / 0001`:** `TRAN-CAT-BAL` `100.00 → 604.77`,
  encoded `0000006047G`.
- **ACCOUNT `00000000007`:**
  - `ACCT-CURR-BAL` `193.00 → 697.77`, encoded `00000006977G`.
  - `ACCT-CURR-CYC-CREDIT` `0.00 → 504.77`, encoded `00000005047G`.
  - `ACCT-CURR-CYC-DEBIT` **unchanged at `0.00`** (the posted amount is a credit,
    i.e. `>= 0`, so `2800` adds it to cycle-credit, not cycle-debit).

## 5. Determinism caveat — mask `TRAN-PROC-TS` before comparison

`2000-POST-TRANSACTION` sets the emitted `TRAN-PROC-TS` from
`Z-GET-DB2-FORMAT-TIMESTAMP` — a **runtime** DB2-format processing timestamp that
changes on every run.

> **Trade-off.** A byte-for-byte golden comparison of the emitted `TRANSACT`
> record would otherwise fail purely because of this clock value. The comparator
> therefore **normalizes / masks `TRAN-PROC-TS`** in the produced record before
> diffing (master §6.3). Conversely, the *input* `DALYTRAN-PROC-TS` is
> intentionally left **blank (26 spaces)** so the fixture itself carries no
> run-varying value; `DALYTRAN-ORIG-TS` is **not** blanked, because its first 10
> bytes are the deterministic transaction date that the expiration check
> (§1, step 4) consumes.

## 6. Line endings

All four fixtures use **LF (`\n`)** with a single **trailing newline** after the
one record (master §3.2, §3.3), so `wc -l` equals the record count (`1`).

> **Assumption.** `tcatbal.txt` is derived from a seed
> (`app/data/ASCII/tcatbal.txt`) that ships as **CRLF (`\r\n`)**, but this fixture
> is stored **LF-only**. It mirrors the seed's *content* while normalizing its
> *line ending* to LF, honoring the master rule that a stray `\r` must never be
> absorbed into the trailing field and push the record one byte over `RECLEN`
> (master §3.2). The other three seeds are already LF, so no normalization is
> needed for them.

## 7. Provenance & data governance (master §8, §10)

**Derivation — seeds are REFERENCE ONLY and are never edited (AAP §0.8.2):**

- `dailytran.txt` ← `app/data/ASCII/dailytran.txt` (line 1), copied byte-for-byte.
- `cardxref.txt` ← the card `4859452612877065` row of
  `app/data/ASCII/cardxref.txt`, padded with the `FILLER X(14)` trailing spaces to
  the full 50-byte `RECLEN`.
- `acctdata.txt` ← account `00000000007` (line 7) of
  `app/data/ASCII/acctdata.txt`, copied byte-for-byte.
- `tcatbal.txt` ← the `00000000007 / 01 / 0001` row of
  `app/data/ASCII/tcatbal.txt`.
- Record layouts: `app/cpy/{CVTRA06Y,CVACT03Y,CVACT01Y,CVTRA01Y}.cpy`. Rule
  source: `app/cbl/CBTRN02C.cbl`.

**Synthetic-data attestation (master §10.3).** The card number
(`4859452612877065`), account id (`00000000007`), and customer id (`000000007`)
are **synthetic, seed-derived** values copied verbatim from the published AWS
CardDemo sample datasets; they represent **no real person or account**. Only
**non-identity, business-rule fields** are reshaped away from the seed value for
this scenario, and each reshaped field is called out so the provenance of the
*changed* bytes is explicit:

- `tcatbal.txt` `TRAN-CAT-BAL` is reshaped from the seed value **`0.00` →
  `100.00`** — the deliberate non-zero seed that forces the `2700-B-UPDATE` branch
  (see §2). The key and `FILLER` bytes are unchanged.
- `cardxref.txt` is **width-normalized** (the 36-byte seed row is padded to the
  50-byte `RECLEN`); no identity byte is altered.
- `dailytran.txt` and `acctdata.txt` carry **no** reshaped bytes — they are the
  seed rows verbatim.

For the full encoding contract and the attestation rationale, see the master
**[`tests/fixtures/README.md`](../../README.md)** (§3, §5, §10).

## 8. Companion artifacts

- **Golden mirror — [planned]:** `tests/golden/posting/happy_path/` (master §1,
  §2.1) holds the `.expected` outputs — the posted `TRANSACT` record (with
  `TRAN-PROC-TS` masked, §5), the updated `ACCOUNT` and `TCATBAL` records, and an
  **empty** `DALYREJS`. Its numbers must match §4 exactly.
- **Consumers — [planned]:** `tests/integration/test_cbtrn02c_posting.py` and the
  end-to-end posting cycle load these fixtures into an isolated workspace, run the
  compiled `CBTRN02C`, and assert against the golden mirror and `RETURN-CODE`.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder, and the per-scenario documentation required
by master `tests/fixtures/README.md` §9.1 / §10.3.*
