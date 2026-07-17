# Posting fixture — zero_balance (zero starting balance, TCATBAL CREATE branch)

These fixtures drive `app/cbl/CBTRN02C.cbl` with one well-formed, in-limit daily
transaction against an account whose current balance is **exactly `+0.00`** and
whose category-balance row **does not yet exist**. The post therefore exercises
zero-start balance arithmetic and the **create** arm of the category-balance
update — `2700-A-CREATE-TCATBAL-REC` — the direct counterpart to sibling
`posting/happy_path`, which seeds an existing row to exercise the **update** arm
(`2700-B-UPDATE-TCATBAL-REC`).

> **Why this README exists.** The fixtures here are static, fixed-width `.txt`
> files that cannot carry docstrings, so — per the project Explainability rule
> (AAP §0.10.1) — this document is the mandated *why* for their bytes. The
> authoritative byte-encoding contract (field widths, offsets, the zoned-decimal
> sign-overpunch table, line endings) lives in the master
> **[`tests/fixtures/README.md`](../../README.md)**; this scenario README only
> summarizes what is specific to `zero_balance` and never restates or contradicts
> it.

## 1. Business rule exercised — `CBTRN02C`

### 1.1 Validation — `1500-VALIDATE-TRAN` (all four checks pass → POST)

- **XREF lookup (`1500-A-LOOKUP-XREF`):** `DALYTRAN-CARD-NUM = 4859452612877065`
  **is present** in `cardxref.txt` and resolves to `XREF-ACCT-ID = 00000000007`
  → **no reject 100** (`"INVALID CARD NUMBER FOUND"`).
- **ACCOUNT lookup (`1500-B-LOOKUP-ACCT`):** account `00000000007` **is present**
  in `acctdata.txt` → **no reject 101** (`"ACCOUNT RECORD NOT FOUND"`).
- **Over-limit check (`>=`):**
  `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`
  `= 0.00 - 0.00 + 504.77 = 504.77`; `ACCT-CREDIT-LIMIT (2065.00) >= 504.77` is
  **TRUE** → within limit, **no reject 102** (`"OVERLIMIT TRANSACTION"`).
- **Expiration check (`>=`):** `ACCT-EXPIRAION-DATE (2024-12-13) >=
  DALYTRAN-ORIG-TS(1:10) (2022-06-10)` is **TRUE** → not expired, **no reject
  103** (`"TRANSACTION RECEIVED AFTER ACCT EXPIRATION"`). The field name uses the
  copybook's preserved misspelling `ACCT-EXPIRAION-DATE` (master §5.2); the full
  reject-code semantics live in master §6.1.

### 1.2 Posting effects (this scenario takes the CREATE branch)

- **`2700-UPDATE-TCATBAL` → `2700-A-CREATE-TCATBAL-REC`:** the key
  `XREF-ACCT-ID + DALYTRAN-TYPE-CD + DALYTRAN-CAT-CD = 00000000007 + 01 + 0001` is
  built and `READ TCATBALF` returns **`INVALID KEY`** (the file is empty), so the
  program `INITIALIZE`s a fresh record, moves the three key parts,
  `ADD DALYTRAN-AMT TO TRAN-CAT-BAL`, and **`WRITE`s a new row** (rather than
  `REWRITE`-ing an existing one).
- **`2800-UPDATE-ACCOUNT-REC`:** `ADD DALYTRAN-AMT TO ACCT-CURR-BAL`
  (`0.00 → 504.77`); because `DALYTRAN-AMT (504.77) >= 0`, the amount is added to
  `ACCT-CURR-CYC-CREDIT` (`0.00 → 504.77`) and `ACCT-CURR-CYC-DEBIT` is left
  unchanged.
- **`2900-WRITE-TRANSACTION-FILE`:** the posted transaction is written as one
  record to `TRANSACT`.

## 2. Fixture files in this folder

| File | `ASSIGN` | Copybook | `RECLN` | Org | Line ending | Records | Role / key values |
|---|---|---|---:|---|---|---:|---|
| `dailytran.txt` | `DALYTRAN` | `CVTRA06Y` | 350 | SEQUENTIAL | LF | 1 | Transaction to post: `DALYTRAN-ID 0000000000683580`, `TYPE 01`, `CAT 0001`, `AMT +504.77`, `CARD 4859452612877065`, `ORIG-TS` date `2022-06-10`, `PROC-TS` blank. |
| `cardxref.txt` | `XREFFILE` | `CVACT03Y` | 50 | INDEXED (key 16@0) | LF | 1 | card `4859452612877065` → cust `000000007` → acct `00000000007` (+14-space `FILLER`). |
| `acctdata.txt` | `ACCTFILE` | `CVACT01Y` | 300 | INDEXED (key 11@0) | LF | 1 | account `00000000007`: **`ACCT-CURR-BAL 0.00`** (the zero starting balance), `CREDIT-LIMIT 2065.00`, `EXPIRAION-DATE 2024-12-13`, cycle credit/debit `0.00`. |
| `tcatbal.txt` | `TCATBALF` | `CVTRA01Y` | 50 | INDEXED (key 17@0) | — (0-byte) | **0** | **Empty** — no category-balance row exists, so the absent key forces the **create** branch (`2700-A-CREATE`). |

> **WHY `PROC-TS` is blank.** `DALYTRAN-PROC-TS` (bytes 305–330) is a *runtime*
> processing timestamp. Leaving it blank (26 spaces, as in the seed) keeps the
> fixture free of any run-varying value so golden-master comparison stays
> byte-reproducible (master §6.3). `DALYTRAN-ORIG-TS` (bytes 279–304) is **not**
> blanked — its first 10 bytes are the transaction date the expiration check
> consumes, so it is deterministic, author-supplied data.

## 3. Expected outcome (authoritative intent; golden is [planned])

For the **[planned]** mirror `tests/golden/posting/zero_balance/` (master §1),
captured from actual output:

- **`RETURN-CODE = 0`** — the transaction POSTs, no reject.
- **`DALYREJS` reject stream is empty**; **1** record is written to `TRANSACT`
  (the `TRAN-*` record, `TRAN-AMT +504.77`).
- **`TCATBAL` — CREATE (`2700-A-CREATE`):** a **new** row
  `00000000007 / 01 / 0001` is written with `TRAN-CAT-BAL = 504.77` (encoded
  **`0000005047G`** in the 11-byte `S9(09)V99` field). Because the record is built
  with `INITIALIZE`, its trailing `FILLER X(22)` is **spaces** — unlike a *seeded
  input* row, whose `FILLER` is `0`-filled (master §5.6).
- **ACCOUNT `00000000007` updated** from its zero starting balance:
  - `ACCT-CURR-BAL` `0.00 → 504.77` (encoded **`00000005047G`**),
  - `ACCT-CURR-CYC-CREDIT` `0.00 → 504.77` (encoded **`00000005047G`**),
  - `ACCT-CURR-CYC-DEBIT` **unchanged** at `0.00` (encoded **`00000000000{`**).

> **WHY the same value has two encodings (field-width distinction).** `504.77`
> encodes as **`0000005047G`** in the **11-byte** `TCATBAL.TRAN-CAT-BAL`
> (`S9(09)V99`) field but as **`00000005047G`** in the **12-byte** `ACCOUNT`
> money fields (`S9(10)V99`) — the same amount, one extra leading zero for the
> wider `PIC`. The overpunch `G` (= `+7`) is identical; only the digit count
> differs. `+0.00` in a 12-byte field is `00000000000{` (`{` = `+0`). The full
> overpunch table is in master §3.4 — not restated here.
>
> **Determinism caveat.** The emitted `TRANSACT` record's `TRAN-PROC-TS` (bytes
> 305–330) is a runtime processing timestamp, so it **must be masked/normalized
> before golden comparison** — performed by `tests/helpers/golden_compare.py`
> **[present]**, which masks exactly that byte range and nothing else (master
> §6.3). Every other byte, including the deterministic `TRAN-ORIG-TS`, is compared
> exactly.

> **WHY an empty `tcatbal` (Trade-off vs. `happy_path`).** This scenario alone
> ships a **genuinely empty (0-byte)** `tcatbal.txt` so that the absent key drives
> the **create** branch (`2700-A-CREATE`). Every other posting scenario — including
> `happy_path`, which seeds a `100.00` category row — keeps a populated index and
> exercises the **update** branch (`2700-B-UPDATE`). Splitting the two gives clean,
> single-branch coverage of the create-vs-update decision (master §6.1).
> **Assumption:** the flat→indexed loader (`tests/helpers/load_indexed.sh` /
> `vsam_loader.py`, master §4.2) and the program's random `READ` on an `OPEN I-O`
> indexed file tolerate an **empty** index — the `READ` returns `INVALID KEY`
> (file status `23`), which `CBTRN02C` accepts as a normal "not found" and routes
> to the create path.

## 4. Data governance / synthetic provenance (MA-24)

The card number (`4859452612877065`), account id (`00000000007`), and customer id
(`000000007`) are **synthetic, seed-derived** values copied verbatim from the
published AWS CardDemo sample datasets in `app/data/ASCII/`, and represent **no
real person or account**. Only **non-identity, business-rule fields** are reshaped
for this scenario: `ACCT-CURR-BAL` is zeroed to `0.00` (the seed row carries a
non-zero balance) and the category-balance row is intentionally omitted
(`tcatbal.txt` left empty). See master §10.

## 5. Sources & scope

- **Derived from (never edited):**
  `app/data/ASCII/{dailytran,cardxref,acctdata}.txt` — with `ACCT-CURR-BAL` zeroed;
  `tcatbal.txt` is intentionally empty (no seed row copied).
- **Record layouts:** `app/cpy/{CVTRA06Y,CVACT03Y,CVACT01Y,CVTRA01Y}.cpy`.
- **Business rule:** `app/cbl/CBTRN02C.cbl` — `1500-VALIDATE-TRAN`,
  `2700-UPDATE-TCATBAL` (create branch `2700-A-CREATE-TCATBAL-REC`),
  `2800-UPDATE-ACCOUNT-REC`, `2900-WRITE-TRANSACTION-FILE`.
- **Consumed by (when present):** `tests/integration/test_cbtrn02c_posting.py` and
  the end-to-end posting cycle — **[planned]** (master §1).

**Minimal-change principle (mandatory).** Seeds under `app/data/ASCII/` and all
production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2). Fixtures are derived copies reshaped only in non-identity fields for
this scenario.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the four
static `.txt` fixtures in this folder.*
