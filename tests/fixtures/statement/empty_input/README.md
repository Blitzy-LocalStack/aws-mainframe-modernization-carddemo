# `empty_input` — CBSTM03A / CBSTM03B empty-transaction (abend) fixtures

These are deterministic, fixed-width **INPUT** fixtures that drive CardDemo
statement generation (`app/cbl/CBSTM03A.CBL` with its I/O subprogram
`app/cbl/CBSTM03B.CBL`) with a **genuinely empty transaction file** against an
otherwise valid, fully-linked customer / account / card. On this input the
program does **not** produce a statement: it takes its documented **fail-fast
abend** path (see §2). This scenario therefore *characterises the abend* — it is
the honest, auditable record of what `CBSTM03A` actually does on empty input, not
an aspiration that it degrade gracefully.

> ⚠️ **Read [`tests/fixtures/README.md`](../../README.md) FIRST.** That master
> document is the authoritative, byte-level contract for **fixed-width layout,
> zoned-decimal sign overpunch, implied-decimal (`V99`), and LF-only line
> endings**. Every non-empty fixture in this folder conforms to it, and this
> README does **not** restate it — it only records the *WHY* behind this
> scenario's non-obvious choices (per the Explainability rule, AAP §0.10.1). The
> `empty_input` semantics in master §7 describe the generic "present but empty
> (0-record)" form; **this statement scenario follows that generic form literally
> — `trnxfile.txt` is a genuine 0-byte file — and §2 explains why that drives an
> abend rather than an empty statement.**

---

## 1. Fixture inventory

Four data fixtures plus this README. Widths, keys, and key offsets are contracts
(master §5); every key sits at **offset 0**. The three master fixtures each hold a
**single** record (so each key is unique); the transaction fixture is empty.

| File (= `ASSIGN`/DD name) | Copybook layout | `RECLN` | Key (offset 0) | Content |
|---|---|---:|---|---|
| `trnxfile.txt` | `COSTM01.CPY` `TRNX-RECORD` | 350 | `TRNX-CARD-NUM` `X(16)` + `TRNX-ID` `X(16)` = **32** | **Empty — a genuine 0-byte file (zero records).** This is the load-bearing input of the scenario (see §2). |
| `xreffile.txt` | `CVACT03Y` | 50 | `XREF-CARD-NUM` `X(16)` = **16** | card `0500024453765740` → cust `000000050` → acct `00000000050` (36-byte seed line + 14-space `FILLER` pad) |
| `custfile.txt` | `CVCUS01Y` | 500 | `CUST-ID` `9(09)` = **9** | customer `000000050` (Aniya Alba Von, FICO `623`) — verbatim seed record |
| `acctfile.txt` | `CVACT01Y` | 300 | `ACCT-ID` `9(11)` = **11** | account `00000000050` (current balance **+492.00**) — verbatim seed record |
| `README.md` | — | — | — | this file |

**The customer / account / card triple is identical to the sibling `happy_path`
scenario** — card `0500024453765740` → cust `000000050` → acct `00000000050`. The
master data is **copied, not shared**: each scenario folder owns its own
byte-identical copies of `xreffile.txt` / `custfile.txt` / `acctfile.txt`.

> **WHY copy rather than share the triple (Trade-off / test isolation).** Sharing
> one physical file across scenarios would couple them: a change made for one
> scenario could silently alter another, and parallel `pytest-xdist` runs could
> race on the same inode. Per-scenario copies keep every scenario **independent
> and self-contained** (master §7), at the cost of a few duplicated bytes — an
> acceptable trade for deterministic, parallel-safe fixtures.

The distinguishing fact of this scenario is simply that **`trnxfile.txt` holds no
records at all** while the master chain is fully valid — so the program opens the
masters successfully and then fails on the very first transaction read (§2).

---

## 2. ⚠️ CRITICAL: why an empty `TRNXFILE` **abends** the program (the central WHY)

The generic `empty_input` guidance in master §7 says an empty scenario uses a
**present but empty (0-record) primary input file**, and the sibling posting
scenario `tests/fixtures/posting/empty_input/` follows it cleanly because
`CBTRN02C` opens `DALYTRAN` and reads straight to end-of-file, completing with
`RETURN-CODE = 0`. **The statement domain does not tolerate empty input, and this
fixture deliberately exercises that fact.**

- **The abend mechanism (Assumption — verified empirically on this runner).**
  `CBSTM03A` paragraph **`8100-TRNXFILE-OPEN`** opens `TRNXFILE` (through
  `CBSTM03B`) and then **immediately issues a READ** on it, tolerating **only**
  file-status `00`/`04`. A genuinely empty `TRNXFILE` returns file-status **`10`**
  (end-of-file) on that first read. Because `10` is neither `00` nor `04`, the
  program DISPLAYs `ERROR READING TRNXFILE` / `RETURN CODE: 10` and falls into
  **`9999-ABEND-PROGRAM`**, which DISPLAYs `ABENDING PROGRAM` and calls Language
  Environment **`CEE3ABD`** (the CardDemo batch abend convention is **abend code
  999**). The process therefore **exits non-zero and produces no statement** —
  both the plain-text and HTML output files are left empty.

- **Why this is the correct, asserted behaviour (Refactoring Rationale).** An
  earlier iteration of this scenario tried to *avoid* the abend by seeding one
  sort-high sentinel transaction so the first read returned `00`; the consuming
  test then expected a graceful zero-activity statement. Empirical execution on
  the CI runner disproved that assumption: the sort-high-sentinel path is brittle
  and the program's genuine, documented contract for empty input is the abend
  above. The scenario was therefore simplified to the honest 0-byte form, and the
  consuming test (`tests/integration/test_cbstm03a_statement.py::test_statement_empty_input`,
  QA finding *F-STMT-EMPTY-ABEND*) now asserts the abend it actually takes.

- **The abend is out of scope to change (AAP §0.8.2).** `app/cbl/CBSTM03A.CBL`
  and `CBSTM03B.CBL` are **REFERENCE-only**; this suite characterises their
  behaviour, it does not modify it. Should a future refactor of
  `8100-TRNXFILE-OPEN` make a truly-empty `TRNXFILE` safe (e.g. by tolerating
  status `10` on the first read), this scenario and its consuming test should be
  revisited to expect a graceful zero-activity statement instead.

---

## 3. Expected output (no golden pair — the abend is asserted directly)

Because empty input drives the program into its abend **before any statement is
written**, this scenario produces **no statement output to compare** — there is
therefore **no golden pair** under `tests/golden/statement/empty_input/`, and none
is expected. (Contrast the sibling `happy_path` scenario, which *does* produce a
statement and *is* pinned byte-for-byte by
`tests/golden/statement/happy_path/statement.{txt,html}.expected`.)

> **WHY no golden here (Refactoring Rationale — resolves QA finding F-1).** A
> pair of `statement.{txt,html}.expected` files previously lived here that encoded
> a *graceful* single statement for Aniya Alba Von. They were **never consumed by
> any test** and **directly contradicted** the abend the program actually takes on
> empty input, so they were removed. The correct oracle for an abend is not a
> statement golden but the **observable abend contract itself**, which the
> consuming test asserts directly:
>
> - a **non-zero** process exit code, and
> - the DISPLAY diagnostics **`ERROR READING TRNXFILE`** and **`ABENDING
>   PROGRAM`** (emitted *before* the `CEE3ABD` call, so they are present
>   regardless of how the abend is realised on a given runtime).
>
> **WHY assert the markers, not a specific exit integer (Assumption / Trade-off).**
> The exact process exit code depends on whether an LE `CEE3ABD` module is
> resolvable at run time (absent on this runner, so `libcob` reports the abend and
> the process exits `1`). Pinning a single integer would make the test brittle to
> the presence/absence of the LE runtime; "exited non-zero **and** printed its
> abend diagnostics" is the stable, environment-independent contract.
