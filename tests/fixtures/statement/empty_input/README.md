# `empty_input` — CBSTM03A / CBSTM03B zero-activity statement fixtures

These are deterministic, fixed-width **INPUT** fixtures that drive CardDemo
statement generation (`app/cbl/CBSTM03A.CBL` with its I/O subprogram
`app/cbl/CBSTM03B.CBL`) for **one valid, fully linked customer / account / card
that has *zero matching transactions***. The program still produces a complete
statement for the account holder, but the transaction body is empty: the emitted
statement shows a `TRANSACTION SUMMARY` with **no detail lines** and
`Total EXP` = `0.00`.

> ⚠️ **Read [`tests/fixtures/README.md`](../../README.md) FIRST.** That master
> document is the authoritative, byte-level contract for **fixed-width layout,
> zoned-decimal sign overpunch, implied-decimal (`V99`), and LF-only line
> endings**. Every fixture in this folder conforms to it, and this README does
> **not** restate it — it only records the *WHY* behind this scenario's
> non-obvious choices (per the Explainability rule, AAP §0.10.1). The
> `empty_input` semantics in master §7 describe the *generic* "present but empty
> (0-record)" form; **this statement scenario deliberately deviates from that
> generic guidance — see §2 below for the load-bearing reason.**

---

## 1. Fixture inventory

Four data fixtures plus this README. Widths, keys, and key offsets are contracts
(master §5); every key sits at **offset 0** and every fixture holds a **single**
record (so each key is unique).

| File (= `ASSIGN`/DD name) | Copybook layout | `RECLN` | Key (offset 0) | Content |
|---|---|---:|---|---|
| `trnxfile.txt` | `COSTM01.CPY` `TRNX-RECORD` | 350 | `TRNX-CARD-NUM` `X(16)` + `TRNX-ID` `X(16)` = **32** | **ONE synthetic row**, card = `9999999999999999`, id = `0000000000000001`, middle (business) fields taken from `dailytran` row 1 |
| `xreffile.txt` | `CVACT03Y` | 50 | `XREF-CARD-NUM` `X(16)` = **16** | card `0500024453765740` → cust `000000050` → acct `00000000050` (36-byte seed line + 14-space `FILLER` pad) |
| `custfile.txt` | `CVCUS01Y` | 500 | `CUST-ID` `9(09)` = **9** | customer `000000050` (Aniya Alba Von, FICO `623`) — verbatim seed record |
| `acctfile.txt` | `CVACT01Y` | 300 | `ACCT-ID` `9(11)` = **11** | account `00000000050` (current balance **+492.00**) — verbatim seed record |
| `README.md` | — | — | — | this file |

**The statemented customer / account / card triple is identical to the sibling
`happy_path` scenario** — card `0500024453765740` → cust `000000050` → acct
`00000000050`. The master data is **copied, not shared**: each scenario folder
owns its own byte-identical copies of `xreffile.txt` / `custfile.txt` /
`acctfile.txt`.

> **WHY copy rather than share the triple (Trade-off / test isolation).** Sharing
> one physical file across scenarios would couple them: a change made for one
> scenario could silently alter another, and parallel `pytest-xdist` runs could
> race on the same inode. Per-scenario copies keep every scenario **independent
> and self-contained** (master §7), at the cost of a few duplicated bytes — an
> acceptable trade for deterministic, parallel-safe fixtures.

The distinguishing fact of this scenario is that the statemented card
`0500024453765740` (the card in `xreffile.txt`) has **no matching row** in
`trnxfile.txt`. The single synthetic transaction present in `trnxfile.txt`
carries the card `9999999999999999`, which is **not** the statemented card, so
the account is statemented with an empty transaction body.

---

## 2. ⚠️ CRITICAL: why `trnxfile.txt` is **not** a zero-record file (the central WHY)

The generic `empty_input` guidance in master §7 says an empty scenario uses a
**present but empty (0-record) primary input file**. The sibling posting scenario
`tests/fixtures/posting/empty_input/` follows that guidance literally — its
`dailytran.txt` is a genuine **0-byte** file, because `CBTRN02C` opens `DALYTRAN`
and reads straight to end-of-file, completing cleanly with `RETURN-CODE = 0`.

**The statement domain cannot do that, and this fixture deliberately deviates.**

- **Trade-off / Assumption (the abend hazard).** `CBSTM03A` paragraph
  **`8100-TRNXFILE-OPEN`** opens `TRNXFILE` (through `CBSTM03B`) and then
  **immediately issues a READ** on it, tolerating **only** file-status `00`/`04`.
  A truly empty indexed `TRNXFILE` returns file-status **`10`** (end-of-file) on
  that very first read. Because `10` is neither `00` nor `04`, the program falls
  into **`9999-ABEND-PROGRAM`**, which calls Language Environment
  **`CEE3ABD`** (the CardDemo batch abend convention is **abend code 999**) and
  terminates. In other words, a genuinely empty statement input **abends the
  program** rather than producing an empty statement. The fixture must therefore
  contain **≥ 1 record** so that the first read returns `00` and the program
  proceeds normally.

- **The all-nines mechanism (how one record still yields zero activity).** The
  single row's card number is `9999999999999999`, which is **lexically greater**
  than the statemented card `0500024453765740`. When `CBSTM03A` paragraph
  **`4000-TRNXFILE-GET`** walks the in-memory transaction table, its loop
  early-exits on the test **`WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM`**. Because
  `9999999999999999 > 0500024453765740`, that test is true on the **first**
  iteration, the loop stops immediately, and **zero** transactions are matched to
  the account. The result is a clean **zero-activity statement** — a satisfied
  OPEN-then-READ (no abend) with an empty transaction body.

- **Alternatives Considered.** A card number lexically **less** than the
  statemented card would *also* yield zero matches (the `= ` branch simply never
  fires). All-nines (`9999999999999999`) was chosen instead because it is the
  **unambiguous, self-documenting sentinel** for "impossible / sort-high" data:
  an auditor reading the fixture sees at a glance that the row exists only to
  satisfy the open-read and can never match a real card, with no arithmetic on
  the reader's part.

- **Verification caveat (Assumption to confirm empirically).** This abend-safe
  design was derived by **reading** `app/cbl/CBSTM03A.CBL` and
  `app/cbl/CBSTM03B.CBL`; the `cobc` compiler was not available in the sandbox
  where this fixture was authored. The consuming test agent **must verify
  empirically on the CI runner** that (1) the program does **not** abend on this
  input and (2) it emits an empty-activity statement (`Total EXP` = `0.00`, no
  detail lines). Should a future refactor of `8100-TRNXFILE-OPEN` make a
  truly-empty `TRNXFILE` safe (e.g. by tolerating status `10` on the first read),
  this note — and the need for the all-nines row — should be revisited, and the
  scenario could then adopt the generic 0-record form.

---

## 3. Expected output (golden shape)

The expected outputs live in the mirrored golden folder
`tests/golden/statement/empty_input/` (a **matched pair** with this fixture
folder — the `domain/scenario` path is byte-identical across the two trees per
master §2.1). That golden tree is **owned by a different agent** and is
**[planned]** — referenced here as the authoritative *intent* for this scenario,
not claimed to exist on the branch today.

The golden should contain **a single statement** for **customer Aniya Alba Von /
account `00000000050`**:

- the same statement header, with **current balance `492.00`** and **FICO
  `623`** (drawn from `acctfile.txt` / `custfile.txt`);
- a `TRANSACTION SUMMARY` section with **zero transaction detail lines**;
- **`Total EXP` = `0.00`** (nothing was matched, so nothing was summed);
- the closing `END OF STATEMENT` marker.

The **HTML** statement the program also emits mirrors this: the fixed
`Bank of XYZ` / `410 Terry Ave N` / `Seattle WA 99999` header, the same
account/customer details, and **no transaction rows** in the table body.

> **Determinism (Trade-off — golden-master over field-by-field).** Statement
> output is compared as a **byte-deterministic golden master** rather than
> field-by-field, because the layout (headers, spacing, HTML markup) is easier to
> pin as a whole. Any run date/processing timestamp that appears in the program's
> **output** is normalized by `tests/helpers/golden_compare.assert_matches_golden`
> before comparison (it masks only the runtime `PROC-TS`, master §6.3), so reruns
> diff byte-for-byte. The **input** fixtures here contain no run-varying values
> (see §4).

---

## 4. Encoding & determinism (summary — full contract in the master README)

This is a summary only; the authoritative rules are in
[`tests/fixtures/README.md`](../../README.md) §3.

- **Fixed width, no delimiters.** Each record is exactly its copybook `RECLN`
  (350 / 50 / 500 / 300) with no separators; meaning is positional only.
- **LF-only endings.** Every fixture uses **LF (`\n`)**, never CRLF, and ends
  with a **single trailing newline** after its one and only record.
- **Padding.** Text (`X`) fields are padded **right with spaces**; unsigned
  numeric (`9`) fields are padded **left with `0`**.
- **Zoned-decimal sign overpunch.** Signed numerics carry the sign in the **last
  byte** together with that digit — positive `{ABCDEFGHI` = +0..+9, negative
  `}JKLMNOPQR` = −0..−9 — with **no** `+`/`-` character.
- **Implied decimal (`V99`).** The decimal point occupies **no byte**; the last
  two digits of a `V99` field are cents, with **no literal `.`** stored.

Worked examples that appear in these fixtures:

- **`00000004920{` = +492.00** — the `ACCT-CURR-BAL` (`S9(10)V99`, 12 bytes) of
  account `00000000050` in `acctfile.txt`: last byte `{` = positive digit `0`.
- **`0000005047G` = +504.77** — the synthetic transaction amount (`TRNX-AMT`,
  `S9(09)V99`, 11 bytes) carried in `trnxfile.txt`: last byte `G` = positive
  digit `7`. **This amount is never summed in this scenario**, because the
  synthetic row's all-nines card never matches the statemented card (§2).

The synthetic `TRNX-RECORD` is fully fixed and deterministic: its runtime
processing timestamp `TRNX-PROC-TS` is **26 spaces** (blank, exactly as the
`dailytran` seed leaves `PROC-TS`), and its `TRNX-ORIG-TS` is the fixed literal
`2022-06-10 19:27:53.000000`. No field in any input fixture varies between runs.

---

## 5. Loader / runtime notes

Every fixture is loaded into a GnuCOBOL **indexed** file (the test-suite analog of
`IDCAMS REPRO`) **before** the program runs, via:

```
tests/helpers/load_indexed.sh <flat> <indexed> <reclen> <key_length>
```

All keys are at **offset 0** and every fixture holds a **unique** key (one record
per file), so the ascending load is trivially in-order. The per-fixture arguments
are:

| Fixture | `<reclen>` | `<key_length>` |
|---|---:|---:|
| `trnxfile.txt` | 350 | 32 |
| `xreffile.txt` | 50 | 16 |
| `custfile.txt` | 500 | 9 |
| `acctfile.txt` | 300 | 11 |

> **Note (availability).** `load_indexed.sh` is the planned thin wrapper around
> the present `tests/helpers/vsam_loader.py` (master §1, §9.3); until it lands,
> invoke `vsam_loader.py` directly with the same width/key arguments.

---

## 6. Sources & dependencies

- **Derived from the published synthetic seeds (never edited — AAP §0.8.2):**
  `app/data/ASCII/cardxref.txt`, `custdata.txt`, `acctdata.txt`, and
  `dailytran.txt` (the last supplies the synthetic transaction's business
  fields).
- **Record layouts:** `app/cpy/COSTM01.CPY` (`TRNX-RECORD`), `CVACT03Y` (XREF),
  `CVCUS01Y` (CUSTOMER), and `CVACT01Y` (ACCOUNT).
- **Programs whose behavior the abend-safe design is sourced from:**
  `app/cbl/CBSTM03A.CBL` (the `8100-TRNXFILE-OPEN` immediate-read and the
  `4000-TRNXFILE-GET` early-exit, §2) and its I/O subprogram
  `app/cbl/CBSTM03B.CBL`.
- **No sibling-folder dependencies.** These four fixtures are **self-contained
  static files**; nothing here reads from another scenario's folder.

**Minimal-change principle (mandatory).** The seeds under `app/data/ASCII/` and
all production sources under `app/` are **REFERENCE ONLY and are never modified**
(AAP §0.8.2). Fixtures are derived copies, reshaped only in this folder.

---

## 7. Data governance / synthetic provenance

The customer, account, and card values here are **synthetic, seed-derived** test
data representing **no real person or account** (master §10):

- `custfile.txt` (customer `000000050` — name, SSN, DOB, address, phone, FICO) is
  copied **byte-for-byte** from the published synthetic seed
  `app/data/ASCII/custdata.txt`.
- `acctfile.txt` and `xreffile.txt` derive from the published seeds
  `acctdata.txt` / `cardxref.txt` for account/card `…050` / `0500024453765740`;
  no business-rule field was reshaped away from the seed value in this scenario.
- The synthetic transaction's card `9999999999999999` is **not** a seed value and
  **not** a real PAN — it is a fabricated **all-nines sentinel** whose sole
  purpose is to be lexically sort-high so it never matches the statemented card
  (§2). It represents no cardholder.

These seeds ship with the upstream open-source AWS CardDemo project as fabricated
demonstration data. See master §10 for the full attestation.

---

*This README is the mandatory Explainability artifact (AAP §0.10.1) for the
static fixtures in this folder. Its load-bearing WHY (§2) is the **abend-safe
deviation**: because `CBSTM03A`'s `8100-TRNXFILE-OPEN` issues an immediate read
that abends on an empty file, this scenario uses a single all-nines sentinel
transaction — not a 0-record file — to obtain a clean zero-activity statement
(`Total EXP` = `0.00`).*
