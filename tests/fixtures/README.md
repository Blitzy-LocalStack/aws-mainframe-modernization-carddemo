# AWS CardDemo — Test Fixtures

> **Authoritative, byte-level contract** for the deterministic input fixtures that
> drive the AWS CardDemo automated test suite. Every domain fixture author
> (`posting`, `interest`, `statement`, `provisioning`) and every downstream
> test/helper author **must** build and consume fixtures exactly as specified
> here. Widths, offsets, the sign-overpunch encoding, and the business-rule
> semantics below are **contracts** — do not paraphrase or "round" them.

---

## 1. Overview

This tree holds **deterministic, fixed-width, flat-record _input_ fixtures** for
the three-layer CardDemo suite (COBOL unit → pytest integration → end-to-end
batch cycle). Per the AAP, the fixtures are consumed by the `tests/integration/**`
and `tests/e2e/**` layers through the shared helpers in `tests/helpers/*`.

Fixtures are the **"data-in"** side of the golden-master verification model: a
test loads a scenario's fixtures into an isolated workspace, runs the compiled
program under test, and compares the produced datasets against the **"data-out"**
expectations in the parallel `tests/golden/**` tree.

> **Availability status (verified against the branch — not aspirational).** This
> README documents the *target* architecture from the AAP; **not every referenced
> artifact exists at this checkpoint**, and every reference below is a contract for
> when the artifact lands, never a claim that it exists today. As currently
> committed on this branch:
>
> - **Present** in `tests/helpers/`: `record_codec.py` (encode/decode fixed-width +
>   zoned-decimal), `vsam_loader.py` (the flat→indexed loader), and
>   `golden_compare.py` (deterministic comparator).
> - **Not yet present** — authored by later test-suite agents per the AAP plan and
>   named here only as the eventual consumers/producers of these fixtures:
>   `tests/helpers/load_indexed.sh`, `tests/helpers/cobol_runner.py`, and the
>   `tests/integration/**`, `tests/e2e/**`, and `tests/golden/**` trees.
>
> **Why name not-yet-present artifacts at all (Trade-off).** Fixture authoring must
> encode the width/key/endianness contract those consumers will rely on, so the
> contract has to be written *before* the consumers exist. The alternative —
> deferring this README until every consumer lands — would let fixtures drift from
> the layouts the programs compile against. The load-bearing loader that exists
> **today** is `tests/helpers/vsam_loader.py`; `load_indexed.sh` is the planned thin
> wrapper around it, and each mention of `load_indexed.sh` below should be read as
> "`vsam_loader.py` now, `load_indexed.sh` once it wraps it."

```
tests/fixtures/<domain>/<scenario>/   ← input records  (this tree)
tests/golden/<domain>/<scenario>/     ← expected output (mirror tree)
```

**Minimal-change principle (mandatory).** Production sources under `app/**`
(COBOL programs `app/cbl/*.cbl`, copybooks `app/cpy/*.cpy`, JCL `app/jcl/*.jcl`,
BMS maps, CICS CSD) **and** the ASCII seed datasets `app/data/ASCII/*.txt` are
**REFERENCE ONLY and are never modified**. Fixtures are *derived* copies/subsets,
reshaped per scenario. The seeds are inputs to derivation, never edited in place
(see §8).

> **Why a README instead of code comments?** The fixtures are static `.txt`
> files that cannot carry docstrings. Per the project Explainability rule
> (AAP §0.10.1), this document is the mandated Explainability artifact for the
> fixture tree: it records the *WHY* behind every non-obvious rule (why
> `PROC-TS` is blank, why `>=` boundaries POST rather than reject, why some
> seeds are LF and others CRLF). It must therefore be complete and precise.

---

## 2. Directory organization

The mandated layout is a two-level `domain / scenario` partition, with one flat
record file per record type used by that scenario:

```
tests/fixtures/<domain>/<scenario>/<record-file>.txt
```

- **`domain`** ∈ { `posting`, `interest`, `statement`, `provisioning` }
- **`scenario`** ∈ { `happy_path`, `reject_100_card_missing`,
  `reject_101_acct_missing`, `reject_102_overlimit`, `reject_103_expired`,
  `boundary_exact_limit`, `boundary_expiry_equal`, `empty_input`,
  `zero_balance`, `default_fallback` }
  > **`default_fallback` is interest-only.** It is the `CBACT04C` DEFAULT
  > disclosure-group fallback case (VSAM status 23 → `DEFAULT` group; see §6.2),
  > and it exists as a committed scenario directory today:
  > `tests/fixtures/interest/default_fallback/`. It was omitted from earlier
  > revisions of this list even though the directory shipped — the enumeration is
  > corrected here so the inventory matches the tree on disk.
- **`<record-file>.txt`** — one file per input record type, named for the logical
  dataset it stands in for (e.g. `dailytran.txt`, `acctdata.txt`, `cardxref.txt`,
  `tcatbal.txt`, `discgrp.txt`). Contents follow the byte-exact layouts in §5.

### 2.1 Fixtures ↔ golden mirroring (critical)

The `<domain>/<scenario>/` path **MUST be mirrored one-for-one** under
`tests/golden/<domain>/<scenario>/`. (This is a firm rule — **MUST**, not
"should" — that binds every scenario the moment its golden mirror is authored;
see the availability note below for the current state of `tests/golden/**`.)

> **Why:** `tests/helpers/golden_compare.py` pairs each input scenario with its
> expected output purely by path. If the two trees drift, the comparator cannot
> locate the golden master for a scenario and the test cannot assert. Keep the
> domain and scenario directory names **byte-identical** across the two trees —
> same spelling, same case, same underscores.

> **Availability.** The `tests/golden/**` mirror tree is **not yet present on this
> branch** (see §1) — it is authored by the golden/integration agents per the AAP.
> The one-for-one mirroring rule above is therefore the **contract those agents
> must honor when the tree lands**, stated here so fixtures and goldens cannot
> drift; it is not a claim that any golden file exists today.

Example of a correctly mirrored pair:

```
tests/fixtures/posting/reject_102_overlimit/dailytran.txt
tests/fixtures/posting/reject_102_overlimit/acctdata.txt
tests/fixtures/posting/reject_102_overlimit/cardxref.txt
tests/golden/posting/reject_102_overlimit/dalyrejs.expected
tests/golden/posting/reject_102_overlimit/acctdata.expected
```

### 2.2 Which scenarios apply to which domain

Not every scenario is meaningful for every program under test. The applicable
set per domain is given in the §4 table; the reject/boundary scenarios are
posting-specific because reason codes 100–103 and the credit-limit/expiration
edges are enforced by `CBTRN02C` (see §6).

---

## 3. Encoding rules (apply to EVERY fixture)

This section is the **most error-prone area** of the suite. Read it in full
before authoring any fixture.

### 3.1 Fixed width, positional fields — no delimiters

- Records are **fixed width with no field delimiters**. A field's meaning is
  determined **only** by its byte position within the record (see the §5 layout
  tables). There are no commas, tabs, or separators between fields.
- **Every record line is EXACTLY the copybook `RECLN`** for its record type
  (350, 300, 150, 50, 500, or 80 bytes — see §5). A line that is one byte short
  or long shifts every subsequent field and silently corrupts the record.

  > **Verified enforcement (not just guidance).** `tests/helpers/record_codec.py`
  > and `tests/helpers/vsam_loader.py` **reject** any physical row whose length is
  > not exactly `RECLN`; they do **not** pad short rows or truncate long ones, and
  > they do **not** silently drop blank lines. The *only* input treated as "empty"
  > is a **genuinely zero-byte dataset** (0 bytes, no records) — see §7. An author
  > who miscounts a width gets a hard failure at load, not a corrupted record. This
  > is a deliberate financial-integrity stance: a malformed monetary record must
  > never be silently coerced into a well-formed-looking one.
- **Padding:**
  - Text (`X`) fields are padded on the **RIGHT with spaces**.
  - Unsigned numeric (`9`) fields are padded on the **LEFT with `0`**.
  - Signed numeric (`S9`) fields are left-padded with `0` and carry their sign in
    the last byte via overpunch (see §3.4).

### 3.2 Line endings

- Use **LF (`\n`) only** for the money/master fixtures that mirror the **LF
  seeds**: `acctdata`, `dailytran`, `cardxref`, `carddata`, `custdata`,
  `discgrp`.

  > **Why / Assumption:** the loader treats each physical line as exactly one
  > fixed-length record. LF matches the primary seeds, and it avoids a stray
  > carriage return (`\r`) being absorbed into the trailing field or `FILLER`,
  > which would push the record one byte over `RECLN` and corrupt the last
  > field. LF is therefore the default for all new fixtures.

- The seeds `tcatbal`, `trancatg`, and `trantype` ship as **CRLF (`\r\n`)** in
  the repository. If a fixture is derived from one of these and a scenario
  *intentionally* preserves CRLF, that choice **must be documented explicitly**
  in that scenario's own `README` (see §9). Prefer LF unless a scenario has a
  specific reason to keep CRLF.

### 3.3 Trailing newline

- End every fixture file with a **single trailing newline after the last
  record**, and keep this consistent across the tree.

  > **Why:** with one trailing newline per record, `wc -l <file>` equals the
  > record count, giving authors and CI a cheap integrity check. Do not add
  > blank lines between records or a second trailing blank line — an empty line
  > is a zero-length "record" that will fail fixed-width parsing.

### 3.4 Signed numeric fields — ZONED-DECIMAL SIGN OVERPUNCH

Signed numeric (`S9…`) fields do **not** contain a `+`/`-` character or a
literal decimal point. Instead, the **sign is encoded into the last byte**
together with that byte's digit, using the zoned-decimal overpunch table below.
This exactly matches the ASCII seeds.

| Last digit | Positive sign | Negative sign |
|:----------:|:-------------:|:-------------:|
| 0          | `{`           | `}`           |
| 1          | `A`           | `J`           |
| 2          | `B`           | `K`           |
| 3          | `C`           | `L`           |
| 4          | `D`           | `M`           |
| 5          | `E`           | `N`           |
| 6          | `F`           | `O`           |
| 7          | `G`           | `P`           |
| 8          | `H`           | `Q`           |
| 9          | `I`           | `R`           |

Read as two rows:

- **Positive:** `{`=0, `A`=1, `B`=2, `C`=3, `D`=4, `E`=5, `F`=6, `G`=7, `H`=8, `I`=9
- **Negative:** `}`=0, `J`=1, `K`=2, `L`=3, `M`=4, `N`=5, `O`=6, `P`=7, `Q`=8, `R`=9

#### Worked examples (verified in the seeds — always self-check against these)

1. **`S9(10)V99` value `+194.00`** → `00000001940{`
   - 12 digit positions. `194.00` → digits `000000019400`.
   - The first 11 characters carry `00000001940`; the 12th digit is `0` with a
     **positive** sign → overpunch `{`. Result: `00000001940{`.
   - (This is the `ACCT-CURR-BAL` of account `00000000001` in `acctdata`.)

2. **`S9(09)V99` value `+504.77`** → `0000005047G`
   - 11 digit positions. `504.77` → digits `00000050477`.
   - The first 10 characters carry `0000005047`; the 11th (last) digit is `7`
     with a **positive** sign → overpunch `G` (`G`=+7). Result: `0000005047G`.
   - (This is the `DALYTRAN-AMT` of the first record in `dailytran`.)

3. **`S9(09)V99` value `-919.00`** → `0000009190}`
   - 11 digit positions. `919.00` → digits `00000091900`.
   - The first 10 characters carry `0000009190`; the 11th (last) digit is `0`
     with a **negative** sign → overpunch `}` (`}`=−0). Result: `0000009190}`.

4. **Interest rate `S9(04)V99` value `15.00` (15%)** → `00150{`
   - 6 digit positions. `15.00` → digits `001500`.
   - The first 5 characters carry `00150`; the 6th (last) digit is `0` with a
     **positive** sign → overpunch `{`. Result: `00150{`.
   - (This is the `DIS-INT-RATE` of the first row in `discgrp`.)

### 3.5 Implied decimal

`V` marks an **implied** decimal point that occupies **no byte**. `V99` means the
**last two digits of the field are cents**; there is **no literal `.`** stored.
For example, in a `S9(09)V99` field the stored 11 digits `00000050477` represent
`504.77` — the decimal sits between the 9th and 10th digit positions by
definition, not because any character marks it.

---

## 4. Domains → programs under test & applicable scenarios

| Domain | Program(s) under test | Input record types (copybook) | Applicable scenarios |
|---|---|---|---|
| `posting` | `CBTRN02C` | DALYTRAN (`CVTRA06Y`, sequential); XREF (`CVACT03Y`, indexed by card #); ACCOUNT (`CVACT01Y`, indexed by acct-id); TCATBAL (`CVTRA01Y`, indexed by acct+type+cat) | **all 9** |
| `interest` | `CBACT04C` | TCATBAL (`CVTRA01Y`); ACCOUNT (`CVACT01Y`); DISCGRP (`CVTRA02Y`); XREF (`CVACT03Y`, read by its **alternate acct-id key**, §4.2) | `happy_path`, `zero_balance`, `default_fallback` |
| `statement` | `CBSTM03A` / `CBSTM03B` | TRNX (`COSTM01`); XREF (`CVACT03Y`); CUSTOMER (`CVCUS01Y`); ACCOUNT (`CVACT01Y`) | `happy_path`, `empty_input` |
| `provisioning` | `CBACT01C` / `CBACT02C` / `CBACT03C`, `CBCUS01C` | ACCOUNT (`CVACT01Y`); CARD (`CVACT02Y`); XREF (`CVACT03Y`); CUSTOMER (`CVCUS01Y`) | `happy_path`, `empty_input` |

> **Interest fixtures carry ≥ 2 accounts, and their `tcatbal` is LF (scenario
> exception).** Each `interest/*` scenario intentionally ships **two** accounts
> (`00000000001` and `00000000002`) rather than one. `CBACT04C` flushes an account
> to disk (`1050-UPDATE-ACCOUNT`: add interest, zero cycle credit/debit, `REWRITE`)
> **only when the sequentially-keyed account id changes** — so with a single
> account the sole/final account is **never** rewritten. The second account makes
> the first a **non-final** account whose `REWRITE` path is genuinely exercised,
> while the final account documents the production **final-flush defect** (the
> trailing `ELSE PERFORM 1050-UPDATE-ACCOUNT` is dead code under `PERFORM UNTIL
> END-OF-FILE = 'Y'`). Because `TCATBAL` is `ORGANIZATION IS INDEXED`, records are
> read in **key order**, so `…001` always precedes `…002` regardless of flat-file
> order. Each interest scenario's `tcatbal.txt` is normalized to **LF** (not the
> seed's CRLF) and documented in that scenario's own `README` — see §3.2 and the
> `interest/*` scenario READMEs. Full detail (including a known out-of-scope
> arithmetic divergence in the *integrated* multi-account run) lives in
> `tests/fixtures/interest/happy_path/README.md`.

### 4.1 File-name casing quirk of the units under test

The statement programs ship as **UPPERCASE** `.CBL` files — `CBSTM03A.CBL` and
`CBSTM03B.CBL` — whereas **all other** batch programs are lowercase `.cbl`
(e.g. `CBTRN02C.cbl`, `CBACT04C.cbl`).

> **Why it matters:** on a case-sensitive Linux filesystem the build/runner
> steps must match **both** `*.cbl` and `*.CBL`. A glob that only matches
> lowercase will silently skip the statement programs.

### 4.2 Loader: sequential vs. indexed inputs

GnuCOBOL binds each COBOL `SELECT … ASSIGN TO <NAME>` to a same-named runtime
environment variable (wired by `scripts/test_env.sh`). The **organization** of
the underlying file dictates how the fixture is loaded:

- **SEQUENTIAL (flat):** `DALYTRAN` (input) and `DALYREJS` (reject output) are
  ordinary line-sequential files. The flat fixture is used **as-is**.
- **`ORGANIZATION IS INDEXED`:** `XREF`, `ACCOUNT`, `TCATBAL`, `DISCGRP`, and
  `TRANSACT` are keyed (VSAM KSDS analogs). The flat fixture must first be
  **loaded into a GnuCOBOL indexed file** by `tests/helpers/load_indexed.sh`
  (the test-suite analog of `IDCAMS REPRO`) **before** the program runs.

  > **Recommendation / Assumption:** author indexed fixtures **pre-sorted by
  > their key** (XREF by card number; ACCOUNT by acct-id; TCATBAL by
  > acct+type+cat; DISCGRP by group+type+cat). A clean ascending load avoids
  > out-of-sequence write errors and makes the fixture human-diffable against
  > the golden output.

  > **Primary AND alternate keys (verified — keys are NOT all at offset 0).** A
  > VSAM KSDS analog can carry more than a single leading key. `XREF`
  > (`CVACT03Y`) has a **primary** key `XREF-CARD-NUM` at **offset 1** (bytes
  > 1–16) **and an ALTERNATE key `XREF-ACCT-ID` at offset 26** (bytes 26–36,
  > §5.3). `CBACT04C` reads the cross-reference **by account id** — its
  > `1110-GET-XREF-DATA` issues `READ … KEY IS FD-XREF-ACCT-ID` — so the loader
  > **must model the alternate index**, not just a leading primary key. The
  > current `tests/helpers/vsam_loader.py` emits both the primary and the
  > `ALTERNATE RECORD KEY` (accepting a non-zero `key_offset`, e.g. 25 for the
  > 0-based acct-id column), and its cache identity includes the full key schema
  > so a primary-only build is never reused for an alternate-key read.

---

## 5. Record-layout reference tables

One table per record type. Columns are **Field | PIC | Bytes | Offset (1-based)**.
These layouts were verified byte-for-byte against `app/cpy/*.cpy`; the
per-record byte counts sum **exactly** to the stated `RECLN`. Reproduce them
exactly — they are contracts.

### 5.1 DALYTRAN-RECORD — `CVTRA06Y` — RECLN 350 (sequential input to `CBTRN02C`)

| Field | PIC | Bytes | Offset (1-based) |
|---|---|---:|---|
| `DALYTRAN-ID` | `X(16)` | 16 | 1–16 |
| `DALYTRAN-TYPE-CD` | `X(02)` | 2 | 17–18 |
| `DALYTRAN-CAT-CD` | `9(04)` | 4 | 19–22 |
| `DALYTRAN-SOURCE` | `X(10)` | 10 | 23–32 |
| `DALYTRAN-DESC` | `X(100)` | 100 | 33–132 |
| `DALYTRAN-AMT` | `S9(09)V99` | 11 | 133–143 |
| `DALYTRAN-MERCHANT-ID` | `9(09)` | 9 | 144–152 |
| `DALYTRAN-MERCHANT-NAME` | `X(50)` | 50 | 153–202 |
| `DALYTRAN-MERCHANT-CITY` | `X(50)` | 50 | 203–252 |
| `DALYTRAN-MERCHANT-ZIP` | `X(10)` | 10 | 253–262 |
| `DALYTRAN-CARD-NUM` | `X(16)` | 16 | 263–278 |
| `DALYTRAN-ORIG-TS` | `X(26)` | 26 | 279–304 |
| `DALYTRAN-PROC-TS` | `X(26)` | 26 | 305–330 |
| `FILLER` | `X(20)` | 20 | 331–350 |

**Byte-count check:** 16+2+4+10+100+11+9+50+50+10+16+26+26+20 = **350** ✓

> **Determinism note:** `DALYTRAN-PROC-TS` (bytes 305–330) is a **runtime**
> processing timestamp. In every input fixture it **must be 26 spaces** (blank),
> exactly as in the `dailytran` seed. See §6.3.

### 5.2 ACCOUNT-RECORD — `CVACT01Y` — RECLN 300 (indexed by acct-id)

| Field | PIC | Bytes | Offset (1-based) |
|---|---|---:|---|
| `ACCT-ID` | `9(11)` | 11 | 1–11 |
| `ACCT-ACTIVE-STATUS` | `X(01)` | 1 | 12 |
| `ACCT-CURR-BAL` | `S9(10)V99` | 12 | 13–24 |
| `ACCT-CREDIT-LIMIT` | `S9(10)V99` | 12 | 25–36 |
| `ACCT-CASH-CREDIT-LIMIT` | `S9(10)V99` | 12 | 37–48 |
| `ACCT-OPEN-DATE` | `X(10)` | 10 | 49–58 |
| `ACCT-EXPIRAION-DATE` | `X(10)` | 10 | 59–68 |
| `ACCT-REISSUE-DATE` | `X(10)` | 10 | 69–78 |
| `ACCT-CURR-CYC-CREDIT` | `S9(10)V99` | 12 | 79–90 |
| `ACCT-CURR-CYC-DEBIT` | `S9(10)V99` | 12 | 91–102 |
| `ACCT-ADDR-ZIP` | `X(10)` | 10 | 103–112 |
| `ACCT-GROUP-ID` | `X(10)` | 10 | 113–122 |
| `FILLER` | `X(178)` | 178 | 123–300 |

**Byte-count check:** 11+1+12+12+12+10+10+10+12+12+10+10+178 = **300** ✓

> **⚠ Preserved source misspelling:** the field at bytes 59–68 is spelled
> **`ACCT-EXPIRAION-DATE`** (missing the second `T` of "EXPIRATION") in the
> copybook. This misspelling is **part of the contract** — the program under
> test (`CBTRN02C`) references `ACCT-EXPIRAION-DATE` for the expiration
> boundary (§6.1, reject 103). Do **not** "correct" it in fixtures, tests, or
> golden files. The card copybook `CVACT02Y` carries the same misspelling as
> `CARD-EXPIRAION-DATE` (§5.4).

### 5.3 CARD-XREF-RECORD — `CVACT03Y` — copybook RECLN 50 (indexed by card #)

| Field | PIC | Bytes | Offset (1-based) |
|---|---|---:|---|
| `XREF-CARD-NUM` | `X(16)` | 16 | 1–16 |
| `XREF-CUST-ID` | `9(09)` | 9 | 17–25 |
| `XREF-ACCT-ID` | `9(11)` | 11 | 26–36 |
| `FILLER` | `X(14)` | 14 | 37–50 |

**Byte-count check:** 16+9+11+14 = **50** ✓

> **Two physical forms — read carefully.** The `cardxref` **seed** rows are only
> **36 bytes** (they stop after `XREF-ACCT-ID` and omit the trailing `FILLER`).
> The **copybook** the program `COPY`s is **50 bytes**. **Recommendation:**
> author XREF fixtures at the full **50-byte** width with `FILLER` space-padded,
> so the loaded record matches the copybook the program compiles against. If a
> scenario deliberately uses the **36-byte** seed shape, state that explicitly
> in the scenario's `README` so the loader/codec width is unambiguous.

### 5.4 CARD-RECORD — `CVACT02Y` — RECLN 150

| Field | PIC | Bytes | Offset (1-based) |
|---|---|---:|---|
| `CARD-NUM` | `X(16)` | 16 | 1–16 |
| `CARD-ACCT-ID` | `9(11)` | 11 | 17–27 |
| `CARD-CVV-CD` | `9(03)` | 3 | 28–30 |
| `CARD-EMBOSSED-NAME` | `X(50)` | 50 | 31–80 |
| `CARD-EXPIRAION-DATE` | `X(10)` | 10 | 81–90 |
| `CARD-ACTIVE-STATUS` | `X(01)` | 1 | 91 |
| `FILLER` | `X(59)` | 59 | 92–150 |

**Byte-count check:** 16+11+3+50+10+1+59 = **150** ✓
(Note the same `EXPIRAION` misspelling in `CARD-EXPIRAION-DATE`.)

### 5.5 TRAN-RECORD — `CVTRA05Y` — RECLN 350

Physically **identical** to DALYTRAN (§5.1) but with `TRAN-*` field names; it is
the posted-transaction record `CBTRN02C` writes to `TRANSACT`. Key field:
`TRAN-AMT` `S9(09)V99` (11 bytes) at offset 133–143. All other offsets match §5.1
one-for-one (`TRAN-ID` 1–16, …, `TRAN-PROC-TS` 305–330, `FILLER` 331–350).

**Byte-count check:** 16+2+4+10+100+11+9+50+50+10+16+26+26+20 = **350** ✓

### 5.6 TRAN-CAT-BAL-RECORD — `CVTRA01Y` — RECLN 50 (indexed by acct+type+cat)

| Field | PIC | Bytes | Offset (1-based) |
|---|---|---:|---|
| `TRANCAT-ACCT-ID` | `9(11)` | 11 | 1–11 |
| `TRANCAT-TYPE-CD` | `X(02)` | 2 | 12–13 |
| `TRANCAT-CD` | `9(04)` | 4 | 14–17 |
| `TRAN-CAT-BAL` | `S9(09)V99` | 11 | 18–28 |
| `FILLER` | `X(22)` | 22 | 29–50 |

**Byte-count check:** 11+2+4+11+22 = **50** ✓
The first three fields form the record key `TRAN-CAT-KEY`
(`TRANCAT-ACCT-ID + TRANCAT-TYPE-CD + TRANCAT-CD`).

> **Seed FILLER convention:** the `tcatbal` seed fills bytes 29–50 with **`0`s**
> (not spaces). Preserve this convention in fixtures for a clean byte-diff
> against the seed-shaped golden. (The `tcatbal` seed is also **CRLF** — see
> §3.2.)

### 5.7 DIS-GROUP-RECORD — `CVTRA02Y` — RECLN 50 (DISCGRP; indexed by group+type+cat)

| Field | PIC | Bytes | Offset (1-based) |
|---|---|---:|---|
| `DIS-ACCT-GROUP-ID` | `X(10)` | 10 | 1–10 |
| `DIS-TRAN-TYPE-CD` | `X(02)` | 2 | 11–12 |
| `DIS-TRAN-CAT-CD` | `9(04)` | 4 | 13–16 |
| `DIS-INT-RATE` | `S9(04)V99` | 6 | 17–22 |
| `FILLER` | `X(28)` | 28 | 23–50 |

**Byte-count check:** 10+2+4+6+28 = **50** ✓
The first three fields form the read key `DIS-GROUP-KEY`
(`DIS-ACCT-GROUP-ID + DIS-TRAN-TYPE-CD + DIS-TRAN-CAT-CD`). See §6.2 for how the
interest program falls back to the `DEFAULT` group.

### 5.8 CUSTOMER-RECORD — `CVCUS01Y` — RECLN 500

Used by the `statement` and `provisioning` domains. Summarized layout (all
offsets 1-based):

| Field | PIC | Bytes | Offset (1-based) |
|---|---|---:|---|
| `CUST-ID` | `9(09)` | 9 | 1–9 |
| `CUST-FIRST-NAME` | `X(25)` | 25 | 10–34 |
| `CUST-MIDDLE-NAME` | `X(25)` | 25 | 35–59 |
| `CUST-LAST-NAME` | `X(25)` | 25 | 60–84 |
| `CUST-ADDR-LINE-1` | `X(50)` | 50 | 85–134 |
| `CUST-ADDR-LINE-2` | `X(50)` | 50 | 135–184 |
| `CUST-ADDR-LINE-3` | `X(50)` | 50 | 185–234 |
| `CUST-ADDR-STATE-CD` | `X(02)` | 2 | 235–236 |
| `CUST-ADDR-COUNTRY-CD` | `X(03)` | 3 | 237–239 |
| `CUST-ADDR-ZIP` | `X(10)` | 10 | 240–249 |
| `CUST-PHONE-NUM-1` | `X(15)` | 15 | 250–264 |
| `CUST-PHONE-NUM-2` | `X(15)` | 15 | 265–279 |
| `CUST-SSN` | `9(09)` | 9 | 280–288 |
| `CUST-GOVT-ISSUED-ID` | `X(20)` | 20 | 289–308 |
| `CUST-DOB-YYYY-MM-DD` | `X(10)` | 10 | 309–318 |
| `CUST-EFT-ACCOUNT-ID` | `X(10)` | 10 | 319–328 |
| `CUST-PRI-CARD-HOLDER-IND` | `X(01)` | 1 | 329 |
| `CUST-FICO-CREDIT-SCORE` | `9(03)` | 3 | 330–332 |
| `FILLER` | `X(168)` | 168 | 333–500 |

**Byte-count check:** 9+25+25+25+50+50+50+2+3+10+15+15+9+20+10+10+1+3+168 = **500** ✓

### 5.9 SEC-USER-DATA — `CSUSR01Y` — RECLN 80 (optional auth module)

| Field | PIC | Bytes | Offset (1-based) |
|---|---|---:|---|
| `SEC-USR-ID` | `X(08)` | 8 | 1–8 |
| `SEC-USR-FNAME` | `X(20)` | 20 | 9–28 |
| `SEC-USR-LNAME` | `X(20)` | 20 | 29–48 |
| `SEC-USR-PWD` | `X(08)` | 8 | 49–56 |
| `SEC-USR-TYPE` | `X(01)` | 1 | 57 |
| `SEC-USR-FILLER` | `X(23)` | 23 | 58–80 |

**Byte-count check:** 8+20+20+8+1+23 = **80** ✓


---

## 6. Business-rule semantics each scenario must encode

The rules below are transcribed **verbatim from the source** (`app/cbl/CBTRN02C.cbl`
paragraph `1500-VALIDATE-TRAN`; `app/cbl/CBACT04C.cbl` paragraph
`1200-GET-INTEREST-RATE` / `1300-COMPUTE-INTEREST`). Fixtures **encode** these
rules; they do not redefine them. Reason codes, message text, and comparison
operators must be reproduced exactly.

### 6.1 Posting validation — `CBTRN02C` `1500-VALIDATE-TRAN`

Validation runs two lookups then two boundary checks:

- **Reject 100 — `"INVALID CARD NUMBER FOUND"`**
  (`1500-A-LOOKUP-XREF`): `DALYTRAN-CARD-NUM` is **absent from XREF** (indexed
  read returns `INVALID KEY`). → scenario `reject_100_card_missing`.
- **Reject 101 — `"ACCOUNT RECORD NOT FOUND"`**
  (`1500-B-LOOKUP-ACCT`): the card is present in XREF, but the resolved
  `XREF-ACCT-ID` is **absent from ACCOUNT** (`INVALID KEY`). → scenario
  `reject_101_acct_missing`.
- **Reject 102 — `"OVERLIMIT TRANSACTION"`** — computed as:

  ```
  WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT
  IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL
      (POST)
  ELSE
      reject 102 "OVERLIMIT TRANSACTION"
  ```

  Because the comparison is **`>=`**, a balance **exactly at the credit limit
  POSTS** (scenario `boundary_exact_limit`), while **one cent over the limit
  REJECTS** (scenario `reject_102_overlimit`).

  > **Why the boundary matters:** the `>=` vs `>` distinction is a
  > financial-edge correctness requirement. A test that only checks "well over"
  > and "well under" would pass even if the operator were wrong. The two
  > boundary fixtures pin the exact `>=` behavior.

- **Reject 103 — `"TRANSACTION RECEIVED AFTER ACCT EXPIRATION"`** — computed as:

  ```
  IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)
      (POST)
  ELSE
      reject 103 "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"
  ```

  The transaction date is the first 10 characters of `DALYTRAN-ORIG-TS`
  (`YYYY-MM-DD`). Because the comparison is **`>=`**, a transaction dated
  **equal to** the expiration date **POSTS** (scenario `boundary_expiry_equal`),
  while a transaction **one day past** expiration **REJECTS** (scenario
  `reject_103_expired`). Note the field name uses the preserved misspelling
  `ACCT-EXPIRAION-DATE` (§5.2).

**Effects of each outcome:**

- **Reject:** the program writes the record to the `DALYREJS` reject stream
  (reason code + message) and sets **`RETURN-CODE = 4`**.
- **Successful post:** the program **creates or updates** the matching `TCATBAL`
  category-balance row (`ADD DALYTRAN-AMT TO TRAN-CAT-BAL` — the create-vs-update
  branch), **updates** the ACCOUNT record, and **writes** the posted transaction
  to `TRANSACT`.

> **Fixture-tuning assumption:** in the seeds, `ACCT-CURR-CYC-CREDIT` and
> `ACCT-CURR-CYC-DEBIT` are both `0`, so `WS-TEMP-BAL == DALYTRAN-AMT`. The
> posting boundary fixtures therefore set `DALYTRAN-AMT` **relative to the chosen
> account's `ACCT-CREDIT-LIMIT`**: equal to the limit for `boundary_exact_limit`,
> and limit + `0.01` for `reject_102_overlimit`. If a fixture uses non-zero cycle
> credit/debit, document the intended `WS-TEMP-BAL` arithmetic in that
> scenario's `README`.

### 6.2 Interest & fee calculation — `CBACT04C`

- **Interest formula (exact):**

  ```
  COMPUTE WS-MONTHLY-INT = (TRAN-CAT-BAL * DIS-INT-RATE) / 1200
  ```

  The divisor is **1200** (12 months × 100 for the percentage), applied in
  fixed-point. `DIS-INT-RATE` is `S9(04)V99`, so `00150{` encodes `15.00`
  (i.e. 15%). **Worked example:** `TRAN-CAT-BAL = 1000.00`, rate `15.00` →
  `(1000 × 15) / 1200 = 12.50`. Assert this **exactly** (no floating-point
  tolerance).

- **`DEFAULT`-group fallback (VSAM status 23):** the DISCGRP read key is
  `ACCT-GROUP-ID + TRAN-TYPE + TRAN-CAT`. On `INVALID KEY` (VSAM file status
  **23** — record not found) the program **moves `'DEFAULT'` into the group id**
  and re-reads (`1200-A-GET-DEFAULT-INT-RATE`).

  > **Fixture design:** all seed ACCOUNT rows have a **blank** `ACCT-GROUP-ID`,
  > so the seed path *naturally* triggers the `DEFAULT` fallback. To exercise a
  > **direct** DISCGRP hit in the `interest` `happy_path` fixture, set
  > `ACCT-GROUP-ID = A000000000` (a group present in `discgrp`). Provide a
  > separate **fallback fixture** (blank/absent group) to exercise the
  > status-23 → `DEFAULT` path. Both paths must be covered.

- **Fee stub (`1400-COMPUTE-FEES`):** paragraph `1400-COMPUTE-FEES` is an
  **empty stub** — its body is the single comment `* To be implemented`.
  Therefore the current behavior is **no fee is produced**. The fee-stub edge
  case asserts the **absence** of a fee transaction; it also flags the
  documented feature gap for future work. Do not fabricate fee output.

### 6.3 Determinism note tied to the rules

Not every timestamp is non-deterministic, and the two must not be confused. The
verified rule that `tests/helpers/record_codec.py` / `golden_compare.py` implement
is: **mask ONLY the runtime processing timestamp (`PROC-TS`) in place; preserve
`ORIG-TS` and every other byte exactly.**

- **`ORIG-TS` on an _input_ record is DETERMINISTIC and is preserved.**
  `DALYTRAN-ORIG-TS` (bytes 279–304) is author-supplied fixture data — indeed its
  first 10 bytes are the transaction date the posting expiration check consumes
  (§6.1, reject 103). It is **not** a runtime value, it is **not** masked, and it
  **must** be a fixed literal in the fixture. Treating input `ORIG-TS` as
  "non-deterministic" (an earlier, incorrect claim) would wrongly scrub a
  load-bearing field.
- **`PROC-TS` is the only runtime-varying byte range in these input fixtures, and
  it must be blank.** Input fixtures **must leave `PROC-TS` (bytes 305–330) blank**
  (26 spaces), exactly as in the seed (verified: bytes 305–330 of the first
  `dailytran` record are 26 blanks). The comparator masks exactly this range and
  nothing else.
- Any date the program **consumes** (e.g. the run/processing date) must be a
  **fixed literal** injected via the program's `PARM-DATE`, **not** the wall
  clock, so reruns produce byte-identical output.

  > **Why the asymmetry (mask `PROC-TS`, keep `ORIG-TS`):** an un-normalized
  > processing timestamp is the single most common source of flaky golden-master
  > diffs for mainframe batch, so `PROC-TS` is pinned blank on input and masked on
  > output. `ORIG-TS`, by contrast, carries meaning the program branches on, so
  > scrubbing it would both destroy determinism-of-meaning and hide real diffs.

  > **Documented limitation — `CBACT04C`-_written_ transactions.** The interest
  > program's emitted transaction (`1300-B-WRITE-TX`) fills **both** its `ORIG-TS`
  > and `PROC-TS` from the runtime clock (`DB2-FORMAT-TS`), unlike the deterministic
  > *input* `ORIG-TS` above. Because the comparator masks only `PROC-TS`, a byte-
  > exact golden comparison of that written record's `ORIG-TS` is **not** currently
  > deterministic. This is a known limitation to resolve where such output is
  > compared (e.g. by injecting a fixed clock or masking the written `ORIG-TS`
  > specifically for `CBACT04C` output); it is recorded here rather than hidden. The
  > deterministic, always-safe field on that written record is its **`TRAN-ID`**,
  > which is `PARM-DATE` + an ascending suffix (see the interest scenario READMEs).

---

## 7. Determinism & isolation principles

- **Fixed content only.** A fixture contains no timestamps that vary between
  runs, no random identifiers, and no environment-derived values. Everything is
  literal and reproducible.
- **Self-contained per scenario.** All keys cross-referenced within a scenario
  (card → xref → account → category balance) must be **resolvable inside that
  scenario's own files** — *except* where a scenario **deliberately omits** a key
  to trigger a reject or a fallback (e.g. `reject_100_card_missing` intentionally
  omits the card from XREF; the interest fallback fixture intentionally leaves
  `ACCT-GROUP-ID` blank).
- **Isolated workspace.** Each test provisions a **fresh** temporary workspace,
  loads its fixtures (flat → indexed where required, §4.2), runs the program, and
  tears the workspace down. There is **no shared mutable state**, so tests run
  independently and in parallel (`pytest-xdist -n auto`).
- **`empty_input` semantics.** An `empty_input` scenario uses a **present but
  empty (0-record) primary input file**. Document per scenario whether that is a
  **0-byte** file or a file **containing no lines** — both are "empty" but the
  loader/codec must be told which, so the program opens a valid (if empty) file
  rather than failing on a missing dataset.

---

## 8. Derivation from seeds

Fixtures are **cut down / adjusted** from the ASCII seed datasets in
`app/data/ASCII/*.txt`:

| Seed file | Record type (copybook) | Line ending |
|---|---|---|
| `acctdata.txt` | ACCOUNT (`CVACT01Y`, 300) | LF |
| `dailytran.txt` | DALYTRAN (`CVTRA06Y`, 350) | LF |
| `cardxref.txt` | CARD-XREF (`CVACT03Y`, 36-byte seed / 50-byte copybook) | LF |
| `carddata.txt` | CARD (`CVACT02Y`, 150) | LF |
| `custdata.txt` | CUSTOMER (`CVCUS01Y`, 500) | LF |
| `discgrp.txt` | DIS-GROUP (`CVTRA02Y`, 50) | LF |
| `tcatbal.txt` | TRAN-CAT-BAL (`CVTRA01Y`, 50) | **CRLF** |
| `trancatg.txt` | transaction category reference | **CRLF** |
| `trantype.txt` | transaction type reference | **CRLF** |

- **Card → customer → account linkage** is established through `cardxref`:
  `card[1–16] → cust[17–25] → acct[26–36]`. When building a scenario, pick a
  coherent chain — a card whose `XREF-ACCT-ID` points at an account that exists
  in `acctdata` (or is *deliberately* missing, for `reject_101_acct_missing`).
- **Seeds are inputs to derivation only and are NEVER modified.** Copy the rows
  you need into the scenario's fixture files under `tests/fixtures/…` and reshape
  there (adjust amounts, dates, group ids). Never edit `app/data/ASCII/*` in
  place (AAP §0.8.2).

---

## 9. Explainability & extension

### 9.1 Per-scenario documentation (mandated)

Because static `.txt` fixtures cannot carry docstrings, **every scenario
subfolder MUST carry a short `README`** (this is a mandatory Explainability
carrier, not a suggestion) stating:

1. **Scenario intent** — what condition this scenario represents.
2. **The exact business rule it exercises** — e.g. "`CBTRN02C` reject 102, `>=`
   credit-limit boundary" or "`CBACT04C` DEFAULT-group fallback on VSAM status
   23".
3. **The expected outcome** — POST vs. specific reject code/message, or the
   exact computed interest value.
4. **Fixture bytes & governance** — the per-file record width, record count, and
   line ending, plus the synthetic-data provenance/attestation required by §10
   whenever the scenario carries PAN or identity-shaped data.

This satisfies the project Explainability review gate (AAP §0.10.1) for static
fixtures.

> **Wording is deliberately `MUST`, not `should` (Refactoring Rationale).** An
> earlier revision weakened this to "should," which let scenario directories ship
> with **no** Explainability carrier at all — a direct review-gate violation. The
> obligation is restored to **`MUST`**: a scenario directory without a `README` (or
> an emitting helper whose docstring carries the same content) is non-conforming.
> Every scenario directory currently on the branch carries this README.

### 9.2 How to add a new scenario

1. Create the fixture folder: `tests/fixtures/<domain>/<new-scenario>/` and add
   one flat file per input record type, at the **exact `RECLN` width** and with
   the **overpunch contract** (§3, §5) honored.
2. **Mirror** it under `tests/golden/<domain>/<new-scenario>/` with the expected
   output(s), keeping the directory names **byte-identical** (§2.1).
3. Keep `PROC-TS` blank and inject any run date via `PARM-DATE` for determinism
   (§6.3).
4. Add the scenario `README` describing intent, rule, and expected outcome
   (§9.1).
5. For indexed inputs, ensure the fixture is **pre-sorted by key** so
   `tests/helpers/load_indexed.sh` loads it cleanly (§4.2).

### 9.3 Referenced helpers and trees (with verified availability)

Each entry is annotated **[present]** (committed on this branch today) or
**[planned]** (authored by later test-suite agents per the AAP; referenced here
only as an eventual consumer/producer of these fixtures — never claimed to exist
now). See the availability note in §1.

- `tests/helpers/record_codec.py` — **[present]** encode/decode fixed-width +
  zoned-decimal.
- `tests/helpers/vsam_loader.py` — **[present]** flat → indexed loader
  (`IDCAMS REPRO` analog); models primary **and** alternate keys (§4.2).
- `tests/helpers/golden_compare.py` — **[present]** deterministic golden-master
  comparator (masks only `PROC-TS`, §6.3).
- `tests/mocks/**` — **[present]** disclosure-group edge rows, MQ stub, LocalStack
  manifest (see `tests/mocks/README.md`).
- `tests/helpers/load_indexed.sh` — **[planned]** thin `bash` wrapper around
  `vsam_loader.py`; until it lands, invoke `vsam_loader.py` directly.
- `tests/helpers/cobol_runner.py` — **[planned]** compile + run wrapper,
  `ASSIGN`-name binding, return-code capture.
- `tests/golden/**` — **[planned]** the parallel expected-output tree (§2.1).
- `tests/integration/**`, `tests/e2e/**` — **[planned]** the pytest layers that
  consume these fixtures.

> **Why annotate availability (Assumption made explicit).** The planned artifacts
> are defined elsewhere in the suite per the project plan; this README references
> them only as the consumers/producers of these fixtures and introduces no new tool
> or command. Marking each **[present]/[planned]** keeps the document from
> describing a future artifact as if it already exists — the exact defect this
> section previously had.


---

## 10. Data governance & synthetic-provenance attestation

> **Scope.** This section is the mandated attestation for every fixture that
> carries a **Primary Account Number (PAN / card number)** or **identity-shaped
> data** (customer name, address, SSN, government-issued id, date of birth, phone,
> FICO). It exists to satisfy the data-governance review gate: test data that
> *looks* like real cardholder data must have a demonstrable non-person origin.

### 10.1 Attestation

**No real cardholder, account, or personal data appears anywhere in this fixture
tree.** Every PAN, account id, customer identity, SSN, government id, date of
birth, address, and phone number in `tests/fixtures/**` is **synthetic test data
derived byte-for-byte from the AWS CardDemo published sample seed datasets** in
`app/data/ASCII/*.txt` (`acctdata`, `carddata`, `cardxref`, `custdata`,
`dailytran`, `discgrp`, `tcatbal`). Those seeds ship with the upstream
open-source AWS CardDemo project as **fabricated demonstration data**; they
describe **no real person or account**.

### 10.2 Generator, seed, and derivation of record

- **Generator / source of record.** The identity and PAN values are **not
  independently generated here** — they are **copied verbatim** from the CardDemo
  ASCII seeds and then, where a scenario requires, reshaped **only in
  non-identity, business-rule fields** (monetary amounts, dates, group ids,
  balances). The seed files are the single source of record; §8 lists them.
- **Seed.** The upstream CardDemo sample dataset (the `app/data/ASCII/*.txt` files
  committed in this repository) is the fixed "seed" for all derivation. It is
  **REFERENCE-only and never modified** (AAP §0.8.2), so the provenance chain is
  reproducible: every fixture identity/PAN value can be traced back to a specific
  seed row by its key.
- **Reshaping discipline.** When a scenario needs a second or altered record
  (e.g. the interest scenarios' second account `00000000002`, sourced
  byte-for-byte from `acctdata.txt` line 2 and `cardxref.txt`), the **identity and
  PAN bytes are taken unchanged from the seed**; only business-rule fields are
  adjusted. This keeps every card number and identity attributable to a published
  synthetic seed rather than to an invented (and possibly real-collision) value.

> **Why derive from the seeds rather than mint fresh PANs (Alternatives
> Considered / Trade-off).** Two alternatives were weighed: (a) generating fresh
> reserved-range test PANs (e.g. ISO/IEC 7812 test ranges) with a documented
> Luhn/identity generator, and (b) reusing the published CardDemo synthetic seeds.
> Option (b) was chosen because the units under test already validate against the
> seed-shaped cross-reference/account/customer records, so seed-derived fixtures
> stay internally consistent (card → xref → account → customer chains resolve) **and**
> inherit the seeds' documented synthetic, non-person status — giving demonstrable
> provenance without introducing a second, independently-attested generator. A PAN
> that happens to be Luhn-valid here is Luhn-valid **because the upstream synthetic
> seed made it so**, not because it was matched to any issuer.

### 10.3 What each scenario README must record

Every scenario `README` whose folder contains PAN or identity data (all `posting`,
`statement`, and `provisioning` scenarios, and the `interest` cross-reference
files) **MUST** state, briefly:

1. that the PAN/identity bytes are synthetic and seed-derived (citing the seed
   file and, where useful, the seed row key);
2. that they represent **no real person or account**;
3. any business-rule fields that were reshaped away from the seed value (so the
   provenance of the *changed* bytes is also explicit).

This attestation, colocated with the bytes it describes, is what makes the
fixture tree audit-defensible for financial-enterprise review.
