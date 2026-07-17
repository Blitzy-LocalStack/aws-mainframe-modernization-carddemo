# `tests/mocks/` — Mock & Stub Carriers

**Purpose.** This directory holds the deterministic test doubles that stand in for
CardDemo dependencies which are either *not shipped in this repository* (the
external MQ authorization producer) or are cheaper to feed from a small, curated
file than from the full seed data (the disclosure-group VSAM file). Every mock is
pure, offline, and reproducible so the suite runs in parallel with no shared
mutable state, exactly as the financial-enterprise test standard requires.

This README is the mandatory Explainability carrier for the mocks (finding
**MA-21**): it documents, for each artifact, its **bytes**, **provenance**, **line
endings**, **expected behavior**, and **limitations**.

> **Provenance & privacy (applies to every file here).** All values are
> **synthetic**. Numeric identifiers and rates derive from the *shape* of AWS
> CardDemo's published synthetic demo seeds (`app/data/ASCII/**`); no real
> cardholder, account, or payment-network data is present. The card numbers used
> by the MQ stub are reserved test-only PANs (ISO/IEC 7812 test ranges), never
> live PANs.

---

## `mock_discgrp.txt` — disclosure-group (DISCGRP) fixture

A fixed-width flat file that is loaded into a GnuCOBOL indexed file and read by
`CBACT04C` (interest calculation) through its `DISCGRP-FILE`.

### Bytes & line endings
- **50 records**, each **exactly 50 bytes** of data, terminated by a single
  **`LF` (`\n`, 0x0A)** — Unix endings, **no `CR`/CRLF**.
- File size = **2550 bytes** = `50 × (50 + 1)`. There is no extra trailing blank
  line beyond the final record's `LF`.
- Encoding is ASCII human-readable zoned decimal (the same convention as
  `app/data/ASCII/**`), so the file is diff-friendly and reviewable.

### Record layout — `DIS-GROUP-RECORD` (`app/cpy/CVTRA02Y.cpy`)
| Offset (0-based) | Len | Field | PIC | Notes |
|---|---|---|---|---|
| 0  | 10 | `DIS-ACCT-GROUP-ID` | `X(10)` | Left-justified, space-padded group id |
| 10 | 2  | `DIS-TRAN-TYPE-CD`  | `X(02)` | Transaction type code |
| 12 | 4  | `DIS-TRAN-CAT-CD`   | `9(04)` | Transaction category code |
| 16 | 6  | `DIS-INT-RATE`      | `S9(04)V99` | Zoned decimal, **trailing sign overpunch** |
| 22 | 28 | `FILLER`            | `X(28)` | Reserved, all `0` in this fixture |

**Zoned-decimal / overpunch (Assumption).** `DIS-INT-RATE` is a 6-byte zoned
`S9(4)V99` whose final byte carries both the last digit and the sign via IBM
overpunch (`{`=+0, `A`..`I`=+1..+9, `}`=-0, `J`..`R`=-1..-9). This matches the
`app/cpy/` copybook contract and the decoder in `tests/helpers/record_codec.py`.
Worked examples straight from the file (overpunch `{`=+0):
- `DIS-INT-RATE` bytes **`00150{`** decode as **`+0015.00`** (a 15.00 rate) and
  **`00250{`** as **`+0025.00`** (25.00). Both the `A000000000` and `DEFAULT`
  groups carry a **mix** of `00000{` / `00150{` / `00250{` rows across their
  transaction type/category combinations (i.e. rates of 0.00, 15.00, and 25.00).
- The `ZEROAPR` group carries only **`00000{`** → **`+0000.00`** (a uniform
  zero-APR group).

### Group ids present (and why)
| `DIS-ACCT-GROUP-ID` | Rows | Role |
|---|---|---|
| `A000000000` | 16 | A normal, populated disclosure group (the "happy path" lookup). |
| `DEFAULT`    | 17 | **Mandatory fallback group** (see below). |
| `ZEROAPR`    | 17 | A zero-rate group, for asserting a `0.00` interest outcome. |

### Expected behavior (how `CBACT04C` uses this file)
`1200-GET-INTEREST-RATE` reads `DISCGRP-FILE` on the key built from the account's
`ACCT-GROUP-ID` + tran type + tran category:
1. **Hit (status `00`)** → the row's `DIS-INT-RATE` feeds the interest formula
   `COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200`.
2. **Miss (VSAM status `23`, record not found)** → the program moves `'DEFAULT'`
   into the key and re-reads via `1200-A-GET-DEFAULT-INT-RATE`.

**Trade-off / why the `DEFAULT` rows exist.** This fixture deliberately includes a
complete `DEFAULT` group so that when a test account references a disclosure group
that is *absent* from this file (deliberately provoking status `23`), the fallback
read succeeds and the `DEFAULT`-group branch is exercised end to end. Without the
`DEFAULT` rows the fallback read would itself fail and abend (`9999-ABEND-PROGRAM`),
so their presence is load-bearing, not decorative.

### Limitations
- This is a curated fixture, **not** the full production disclosure catalog; only
  the three group ids above resolve. Any other group id relies on the `DEFAULT`
  fallback.
- The file must be loaded into an **indexed** file before `CBACT04C` runs (the
  program declares `ORGANIZATION IS INDEXED`); the flat file is not read directly.
- `FILLER` is all `0` here; the program does not interpret it, so its content is
  not asserted.

---

## `mq_request_stub.py` — external MQ authorization producer stub

A pure-Python, fully deterministic stand-in for the **external** authorization
producer used by CardDemo's optional *Credit Card Authorizations with IMS, DB2 and
MQ* extension (`app/app-authorization-ims-db2-mq`). That producer is **not shipped
in this repository**, so the optional-module tests have nothing real to call; this
module supplies deterministic authorization decisions offline.

### Provenance & contract
- Mirrors the COBOL copybooks **`CCPAURQY.cpy`** (request, `PA-RQ-*`) and
  **`CCPAURLY.cpy`** (response, `PA-RL-*`); field names, widths, and the
  `PA-RQ-TRANSACTION-AMT PIC +9(10).99` money domain are transcribed from those
  frozen copybooks.
- The canned card numbers are reserved **test-only** PANs (e.g. `4111…1111`), not
  live card numbers.

### Behavior — deterministic decision order (first match wins)
| Step | Condition | Outcome |
|---|---|---|
| 0 | **Copybook width/domain validation** (`validate_request`) | **raise** `TypeError`/`ValueError` on any violation |
| 1 | `card_num` not exactly 16 digits (but ≤ 16 wide) | decline `14` / `1400` (invalid card) |
| 2 | `card_num` in `CANNED_RESPONSES` | the mapped code (approve or decline) |
| 3 | `card_expiry_date == "0000"` | decline `54` / `5400` (expired) |
| 4 | `amount > STUB_APPROVAL_CEILING` | decline `51` / `5100` (over limit) |
| 5 | otherwise | approve `00` / `0000` |

### Decision boundaries (money — finding **MA-14**)
- **Exact `Decimal` cents only.** `transaction_amt` accepts `Decimal` / `int` /
  `str`; a binary **`float` is rejected** (`TypeError`) because it cannot
  represent cents exactly.
- The amount must be **finite** (no `NaN`/`Infinity`), **non-negative**, carry **at
  most two decimal places** (over-precision is rejected, never silently rounded),
  and be **≤ `9999999999.99`** (`+9(10).99`). Any violation **raises** before a
  decision is made — a malformed amount can *never* be approved.
- `STUB_APPROVAL_CEILING = Decimal("5000.00")`. The comparison is exact: a value
  **exactly at** the ceiling approves; **one cent over** declines `51`.
- Other copybook fields are width-checked: `transaction_id` ≤ 15, `merchant_id`
  ≤ 15, `card_expiry_date` ≤ 4, `card_num` ≤ 16, `processing_code` numeric ≤ 6.

### Limitations
- This is a **test double of the producer's business behavior**, not a real
  authorization engine and not an MQ transport — there is no queue, network, or
  `pymqi` dependency. It models decisions, not messaging.
- The approval rule-set is intentionally small and transparent (a fixed ceiling
  plus an explicit canned-card table); determinism and auditability are valued
  over realism.
- A "declined" result is a **normal business outcome** (a returned response), not
  an exception; the module raises only for programmer/contract misuse.

---

## Line endings & determinism (all files)
Fixtures use `LF` endings and fixed-width records so golden comparison is
byte-deterministic. The Python stub is a pure function of its input (no wall
clock, no RNG, no salted `hash()`), so repeated runs and parallel workers produce
identical results.
