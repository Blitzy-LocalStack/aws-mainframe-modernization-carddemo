# AWS CardDemo — Automated Test Suite

> Developer / CI entry point for building, running, and extending the CardDemo
> test suite. Every command below is copy‑paste accurate to the sibling
> `scripts/` runners; **if a script and this README ever disagree, the script
> is authoritative** — please open a fix rather than diverging.

CardDemo is a mainframe **credit‑card management** application written in
procedural **COBOL** running under **CICS** (online) and **JCL/JES** (batch)
against **VSAM** data, licensed **Apache‑2.0**. Its financial batch programs
process money (transaction posting, interest accrual, statement generation,
bill payment) using advanced fixed‑point data formats — **`COMP-3` packed
decimal** and **zoned‑decimal sign overpunch** — so the tests treat monetary
results as exact fixed‑point values, never floating‑point approximations.

---

## 1. Overview

This is a **greenfield, financial‑enterprise‑grade** test suite: the repository
shipped **no programmatic tests** before it, so every artifact here is new. It
is organised into **three complementary layers** so that failures localise
precisely and the full daily batch cycle is exercised deterministically:

| Layer | What it exercises | Technology |
|-------|-------------------|------------|
| **Unit** | Isolated COBOL program / paragraph logic (validation branches, interest formula, date edits). | COBOL test programs under **GCBLUnit** |
| **Integration** | A *single* compiled GnuCOBOL program run against **real** indexed/sequential files: seed fixture → run → assert on output records, reject stream, updated masters, and `RETURN-CODE`. | **pytest** |
| **End‑to‑end (E2E)** | The whole daily batch chain (provision → post → interest → statement/report → export/import) compared against **golden masters**. | **pytest** + golden‑master comparator |

**Test‑only / minimal‑change principle.** Production code under `app/**`
(COBOL programs, copybooks, JCL, BMS maps, CICS CSD) and the seed data under
`app/data/**` are **REFERENCE ONLY and are never modified** by the suite. The
twelve batch programs already compile under GnuCOBOL with `--std=ibm-strict`,
so no source change is required for testability — the tests *encode* the
documented business rules, they do not redefine them.

**Scope note.** The twelve batch programs (`app/cbl/CB*.cbl`) contain zero
`EXEC CICS` verbs and run standalone under GnuCOBOL, so they are fully
automatable. The eighteen online `CO*` programs use the CICS command‑level API;
their fully‑automated end‑to‑end testing is environment‑bounded (no CICS
runtime on the runner), so only their extractable field‑validation logic is
unit‑tested.

---

## 2. Directory layout

```text
tests/
├── README.md                 # this file — how to build, run, and extend the suite
├── pytest.ini                # pytest config: markers, --strict-markers, junit_family
├── conftest.py               # shared fixtures: workspace provisioning, build cache, env wiring
├── .coveragerc               # coverage.py config for the PYTHON harness (run with --rcfile)
├── requirements-test.txt      # pinned, test-only Python deps (pytest, xdist, coverage, aws shims)
├── cobolget.json             # cobolget manifest pinning the GCBLUnit framework version
├── import.json               # cobolget lock: exact resolved GCBLUnit version (reproducibility)
│
├── cobol-unit/               # Layer 1 — GCBLUnit COBOL unit tests
│   ├── *_test.cbl            # one test program per unit-under-test (CBTRN02C_test.cbl, ...)
│   └── gcblunit.cbl          # vendored GCBLUnit runner framework (network-free)
│
├── integration/              # Layer 2 — pytest single-program tests
│   ├── test_cbtrn02c_posting.py
│   ├── test_cbact04c_interest.py
│   ├── test_cbstm03a_statement.py
│   ├── test_provisioning.py
│   ├── test_csutldtc_date.py
│   └── test_export_import.py
│
├── e2e/                      # Layer 3 — full-pipeline golden-master tests
│   ├── test_full_batch_cycle.py
│   ├── test_posting_cycle.py
│   ├── test_interest_cycle.py
│   └── test_localstack_dataset_staging.py
│
├── fixtures/<domain>/<scenario>/   # fixed-width flat inputs (see scenarios below)
├── golden/<domain>/<scenario>/     # *.expected golden-master outputs
│
├── mocks/                    # test doubles for absent/external dependencies
│   ├── mock_discgrp.txt          # disclosure-group rows incl. a MISSING key (DEFAULT fallback)
│   ├── mq_request_stub.py        # stub for the not-supplied MQ authorization producer
│   └── localstack_s3_manifest.json  # buckets/objects for the S3 dataset-staging layer
│
└── helpers/                  # reusable Python harness + shell loaders
    ├── cobol_runner.py           # compile+run wrapper, env-var ASSIGN binding, RC capture
    ├── vsam_loader.py            # flat → GnuCOBOL indexed loader (IDCAMS REPRO analog)
    ├── load_indexed.sh           # shell entry point for the indexed load step
    ├── record_codec.py           # encode/decode fixed-width + zoned-decimal records
    ├── golden_compare.py         # deterministic golden-master comparator
    └── localstack_setup.py       # headless LocalStack lifecycle + resource seeding
```

- **`fixtures/<domain>/<scenario>/`** — `domain ∈ {posting, interest, statement,
  provisioning}`; `scenario ∈ {happy_path, reject_100_card_missing,
  reject_101_acct_missing, reject_102_overlimit, reject_103_expired,
  boundary_exact_limit, boundary_expiry_equal, empty_input, zero_balance}`.
- **`golden/<domain>/<scenario>/*.expected`** — the expected outputs for each
  fixture, compared byte‑deterministically after timestamp normalisation.

> **Note.** Some children (e.g. `integration/`, `e2e/`, `golden/`, `conftest.py`,
> and the `scripts/run_*.sh` runners) are produced across the test‑suite build.
> The layout above is the **target** structure the runners expect; author new
> files into exactly these paths so the documented commands keep working.

---

## 3. Prerequisites

| Tool | Version (validated) | Needed for |
|------|---------------------|------------|
| GnuCOBOL `cobc` | **3.1.2.0** (gcc 13.3.0 backend) | Compiling programs‑under‑test and COBOL unit tests |
| Python | **3.12.3** | The pytest integration/E2E harness |
| pytest | **9.1.1** | Integration + E2E orchestration, JUnit‑XML reporting |
| pytest‑xdist | **3.8.0** | Parallel execution (`-n auto`) to prove test isolation |
| coverage | **7.15.2** | Python‑harness line/branch coverage |
| pytest‑golden | **1.0.1** *(optional)* | Convenience golden‑file plugin |
| awscli‑local | **0.22.2** | `awslocal` wrapper targeting LocalStack |
| awscli | **1.45.49** | S3 dataset staging against LocalStack |
| LocalStack CLI | **2026.6.1** *(optional)* | Headless AWS (S3) emulation |
| cobolget | **3.13.26** | COBOL package manager used to pin/vendor GCBLUnit |
| GCBLUnit | **1.22.6** | COBOL unit‑test framework (JUnit‑XML output) |

Install the Python test dependencies once:

```bash
# WHAT: installs the pinned pytest/coverage/aws-shim stack into your environment.
# WHY : exact '==' pins guarantee reproducible CI runs — a hard financial-grade
#       requirement, because a floating dependency could silently break the
#       byte-deterministic golden comparisons.
python3 -m pip install -r tests/requirements-test.txt
```

> **Dialect matters.** COBOL is compiled with **`--std=ibm-strict`**, *not*
> `-std=cobol85`. `cobol85` rejects the `COMP-3` packed‑decimal money fields
> these financial programs use; `ibm-strict` accepts them. This is the single
> most important dialect decision in the whole suite.

Optional for the AWS layer only: the **LocalStack CLI** and **`jq`** (used by
`scripts/setup_localstack.sh` to parse the ephemeral‑instance token response).

---

## 4. Quick start (single command)

```bash
# WHAT: builds everything and runs all three layers end to end.
# WHY : this is the one command an operator or CI job needs; it is the master
#       runner that wires the layers together in the correct order.
bash scripts/run_tests.sh
```

`run_tests.sh` performs, in order:

1. `source scripts/test_env.sh` — wire the runtime environment (ASSIGN bindings).
2. `bash scripts/build_test_programs.sh --with-tests` — compile the programs
   under test **and** the COBOL unit tests.
3. `bash scripts/run_unit_tests.sh` — Layer 1 (COBOL/GCBLUnit).
4. `bash scripts/run_integration_tests.sh` — Layer 2 (pytest integration).
5. `bash scripts/run_e2e_tests.sh` — Layer 3 (pytest E2E).

It **aggregates a worst‑case return code** across all layers (see
[§8 Return‑code rubric](#8-return-code-rubric)) and returns that single status
to the caller, so CI gets one deterministic pass/fail signal.

Useful flags:

| Flag | Effect |
|------|--------|
| `--fail-fast`, `-x` | Stop at the first failing layer instead of running them all. |
| `--with-localstack` | Bring up the headless LocalStack S3 layer before the E2E run. |
| `-h`, `--help` | Print usage and exit. |

---

## 5. Layer‑by‑layer commands

These are the **binding contracts** with the sibling `scripts/`. Reproduced
verbatim; run them from the **repository root**.

### 5.1 Environment (source, don't execute)

```bash
# WHAT: exports the 28 GnuCOBOL ASSIGN-name -> file bindings plus the shared
#       CARDDEMO_* workspace/build/reports paths and the RC-rubric helpers.
# WHY : it MUST be *sourced*, not executed — GnuCOBOL resolves each
#       `SELECT ... ASSIGN TO <NAME>` via a same-named environment variable, so
#       the exports must land in *your* shell. The file deliberately never calls
#       `exit` (only `return`) so a bad source cannot kill an interactive shell.
#       Sourcing is idempotent, so re-sourcing between runs is safe.
source scripts/test_env.sh
```

### 5.2 Build

```bash
# WHAT: compiles the batch programs under test from app/cbl/ into build/ using
#       the repository's `cobc -fixed --std=ibm-strict -I app/cpy` convention.
# WHY : ten main programs build as executables (`cobc -x`); the three
#       dynamically-CALL'd subprograms (CBACT04C, CSUTLDTC, CBSTM03B) build as
#       shared modules (`cobc -m`) resolved at run time via COB_LIBRARY_PATH.
bash scripts/build_test_programs.sh              # programs under test only
bash scripts/build_test_programs.sh --with-tests # also compile tests/cobol-unit/*_test.cbl
bash scripts/build_test_programs.sh --coverage   # add gcov instrumentation for COBOL line coverage
```

### 5.3 Unit layer (COBOL / GCBLUnit)

```bash
# WHAT: runs every compiled tests/cobol-unit/*_test.cbl program and writes a
#       JUnit-XML report to reports/unit.xml for CI ingestion.
bash scripts/run_unit_tests.sh
```

### 5.4 Integration layer (pytest)

```bash
# WHAT: runs the single-program integration tests and writes JUnit XML.
# WHY : the runner selects ONLY the `integration` marker so a stray unit/e2e
#       test cannot leak into this layer.
bash scripts/run_integration_tests.sh
#   └── internally runs:
#       pytest tests/integration -m integration --junitxml=reports/integration.xml
```

### 5.5 End‑to‑end layer (pytest)

```bash
# WHAT: runs the full batch-chain golden-master tests and writes JUnit XML.
# WHY : `--with-localstack` is optional because the AWS layer is best-effort;
#       without it the S3 staging test is skipped/warned, never hard-failed.
bash scripts/run_e2e_tests.sh                    # core E2E only
bash scripts/run_e2e_tests.sh --with-localstack  # also run the S3 dataset-staging test
#   └── internally runs:
#       pytest tests/e2e -m e2e --junitxml=reports/e2e.xml
```

### 5.6 Direct pytest (advanced / debugging)

```bash
# WHAT: invoke pytest directly, bypassing the runner scripts.
# WHY : you MUST set PYTHONPATH to the repo root so the `tests.*` package
#       imports (from tests.helpers.cobol_runner import ...) resolve; the runner
#       scripts set this for you, so this is only needed for ad-hoc invocations.
PYTHONPATH=$(pwd) pytest tests/integration -m integration
PYTHONPATH=$(pwd) pytest tests/integration/test_cbtrn02c_posting.py::test_reject_102_overlimit -v
PYTHONPATH=$(pwd) pytest -vv --tb=long -x tests/integration/test_cbtrn02c_posting.py   # debug mode
```

---

## 6. Markers

`pytest.ini` registers the markers below and runs with **`--strict-markers`**,
so any *unregistered* marker is a **hard error** (financial‑grade rigor) rather
than a silent no‑op that could let a whole layer go unrun.

| Marker | Selects |
|--------|---------|
| `unit` | COBOL/GCBLUnit‑level unit tests (fast, isolated program/paragraph logic). |
| `integration` | Single‑program pytest tests against compiled GnuCOBOL programs. |
| `e2e` | Full batch‑chain end‑to‑end golden‑master tests. |
| `localstack` | Tests requiring the headless LocalStack S3 emulator (opt‑in). |
| `slow` | Long‑running tests (may be deselected for fast local iteration). |

```bash
# WHAT: run only the LocalStack-backed tests, or exclude the slow ones.
pytest tests -m localstack
pytest tests -m "not slow"
```

---

## 7. Coverage

**Python harness** (the reusable `tests/helpers` + `tests/mocks` packages):

```bash
# WHAT: measures Python-harness line/branch coverage across integration + e2e.
# WHY : the `--rcfile=tests/.coveragerc` flag is REQUIRED — the rcfile lives
#       under tests/ (not the repo root), so coverage.py will not auto-discover
#       it. Omitting the flag silently measures nothing useful.
coverage run --rcfile=tests/.coveragerc -m pytest tests/integration tests/e2e
coverage report --rcfile=tests/.coveragerc
# (equivalently: export COVERAGE_RCFILE=tests/.coveragerc)
```

**COBOL** coverage is produced **separately** via `gcov` on the
GnuCOBOL‑transpiled C — GnuCOBOL compiles COBOL to C and then to a native
executable, so instrumenting that intermediate C yields line coverage:

```bash
# WHAT: build with instrumentation, run the suite, then collect gcov data.
# WHY : COBOL line coverage cannot come from coverage.py (that only sees
#       Python); it comes from the C the compiler emits under --coverage.
bash scripts/build_test_programs.sh --with-tests --coverage
```

**Targets** (grounded recommendations from critical‑path analysis — **not**
contractual SLAs; the repository defines none):

| Component | Target line | Target branch | Focus |
|-----------|-------------|---------------|-------|
| `CBTRN02C` (posting) | **≥ 90%** | **≥ 85%** | Reject branches, posting, balance updates |
| `CBACT04C` (interest) | **≥ 90%** | **≥ 85%** | Interest formula, DEFAULT fallback, fee stub |
| Other 10 batch programs | ≥ 1 asserting test each | critical path | Round‑trips, report/statement output |

**Mandatory branch coverage (100%),** regardless of aggregate percentages:
every documented posting reject reason (100–103), the over‑limit and expiration
boundaries, the `CBACT04C` DEFAULT disclosure‑group fallback, the `TCATBAL`
create‑vs‑update branch, and the interest formula must each be exercised by at
least one test.

---

## 8. Return‑code rubric

The suite follows the mainframe condition‑code convention so CI receives one
deterministic status. Runners **aggregate the worst (highest) code** seen.

| RC | Meaning |
|----|---------|
| **0** | Pass — everything succeeded. |
| **4** | Warn / soft reject — e.g. a business‑rule reject was correctly written, a layer collected no tests, or the optional AWS layer was unavailable. |
| **8** | Fail — a test or a required build step failed. |
| **16** | Fatal — an abend / unrecoverable error (e.g. a bad file OPEN triggering the program's `9999-ABEND-PROGRAM` path). |
| **2** | Usage — a runner was invoked incorrectly (bad arguments); aborts immediately and does not enter aggregation. |

---

## 9. LocalStack (AWS emulation)

The AWS Mainframe Modernization dataset‑staging path is virtualised with
**LocalStack** — no real AWS account is ever used. Because the runner has **no
Docker**, the **headless, Docker‑less `localstack ephemeral create`** path is
used (see the reference guide at
<https://blog.localstack.cloud/ai/agents.md>).

```bash
# WHAT: brings LocalStack up headless and seeds S3 from the manifest.
# WHY : every failure mode here degrades to WARN(4), never a hard fail — the
#       AWS layer is OPTIONAL, so the core COBOL suite still passes on a runner
#       with no LocalStack credentials.
bash scripts/setup_localstack.sh
```

`setup_localstack.sh`:

- brings up the ephemeral instance with an agent token (skipped if
  `LOCALSTACK_AUTH_TOKEN` is already set as a CI secret);
- seeds the S3 buckets/objects declared in
  `tests/mocks/localstack_s3_manifest.json`;
- exports **`AWS_ENDPOINT_URL`** (and writes `<workspace>/localstack.env`) so
  the pytest E2E layer — and `awslocal s3 ...` — target the emulator.

---

## 10. cobolget / GCBLUnit provenance

The COBOL unit framework is pinned for reproducibility in two places:

- **`tests/cobolget.json`** — the manifest declaring **GCBLUnit `^1.22.6`** as a
  debug/test dependency.
- **`tests/import.json`** — the lock pinning the **exact** resolved version so
  every checkout vendors byte‑identical framework code.

> **Why the non‑native names?** cobolget's native filenames are `modules.json`
> and `modules-lock.json`; they are named `cobolget.json` / `import.json` here
> per the project's test plan, while still following the cobolget schema.

> **Why vendored too?** GCBLUnit is **also** vendored directly as
> `tests/cobol-unit/gcblunit.cbl`, so the unit tests compile and run **without
> network access** (the cobolget registry may be unreachable in CI). The
> manifest/lock therefore serve primarily as a provenance record of the exact
> framework version.

---

## 11. Determinism & isolation

Financial‑grade tests must be reproducible and independently runnable:

- **Fresh workspace per test.** Each test provisions its own temporary
  workspace and tears it down afterward — no shared mutable state.
- **Flat → indexed load.** The batch programs declare
  `ORGANIZATION IS INDEXED`, so flat fixtures are first loaded into GnuCOBOL
  indexed files (an **`IDCAMS REPRO` analog**, via `tests/helpers/load_indexed.sh`
  / `vsam_loader.py`) before the program runs.
- **Timestamp normalisation.** Non‑deterministic processing timestamps
  (`DALYTRAN-PROC-TS` and originating timestamps) are normalised **before**
  golden comparison so byte‑diffs stay stable across runs.
- **Injected dates.** Business dates are injected via `PARM-DATE` rather than
  read from the wall clock, so reruns produce identical output.
- **Parallel‑safe.** Because tests share nothing, they run under
  `pytest -n auto` (`pytest-xdist`); a green parallel run *proves* isolation.

```bash
# WHAT: run the whole suite in parallel to validate isolation.
PYTHONPATH=$(pwd) pytest tests -n auto
```

---

## 12. Extending the suite

To add a new scenario (illustrated for the `posting` domain):

1. **Fixture** — drop fixed‑width flat inputs under
   `tests/fixtures/posting/<new_scenario>/` (records must match the `app/cpy/`
   copybook layouts exactly — e.g. 350‑byte daily‑tran, 300‑byte account — with
   correct zoned‑decimal sign overpunch).
2. **Golden** — add the expected output(s) under
   `tests/golden/posting/<new_scenario>/*.expected`.
3. **Test case** — add a parametrised case to the relevant
   `tests/integration/` (or `tests/e2e/`) module; reuse the shared helpers:

   ```python
   from tests.helpers.cobol_runner import run_program
   from tests.helpers.golden_compare import assert_matches_golden
   ```

4. **COBOL unit tests** resolve record layouts through the compiler copybook
   path (`cobc -I app/cpy`) via `COPY CVTRA06Y.` / `COPY CVACT01Y.` — never
   duplicate a layout; keep it single‑sourced from `app/cpy/`.

> **Explainability rule (mandatory).** Every new test, fixture builder, helper,
> mock, and runner routine must carry a docstring stating **Purpose,
> Parameters, Returns, and Exceptions**, and its inline comments must explain
> **why** (documenting at least one of Alternatives Considered, Refactoring
> Rationale, Assumptions, or Trade‑offs) — never restate what the code does.
> This is a hard review gate.

---

## 13. Business rules under test (verbatim)

Tests **encode** the specification exactly as documented in the production
source — they do not redefine it. The rules below are asserted verbatim.

**Posting reject reasons (`CBTRN02C`)** — each writes the `DALYREJS` stream and
sets `RETURN-CODE = 4`:

| Reason | Condition | Message text (verbatim) |
|--------|-----------|-------------------------|
| **100** | Card number not found in the cross‑reference | `INVALID CARD NUMBER FOUND` |
| **101** | Account record not found | `ACCOUNT RECORD NOT FOUND` |
| **102** | Transaction would exceed the credit limit | `OVERLIMIT TRANSACTION` |
| **103** | Transaction received after account expiration | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` |

**Monetary & date boundaries:**

- **Over‑limit boundary** — a balance *exactly at* the credit limit must
  **post**; one cent over must **reject** (reason 102).
- **Expiration boundary** — a transaction dated *equal to* the account
  expiration date must **post**; one day past must **reject** (reason 103).

**Interest calculation (`CBACT04C`):**

- Formula (fixed‑point, exact): **`(TRAN-CAT-BAL × rate) / 1200`**.
- **DEFAULT disclosure‑group fallback** — when an account's disclosure‑group
  key is not found (VSAM status **23**), the `DEFAULT` group's rate is used.

**Transaction‑category balance (`TCATBAL`):** the **create‑vs‑update** branch
(`2700-A-CREATE` when the category row is new, `2700-B-UPDATE` when it exists)
is exercised both ways.

---

<sub>Apache‑2.0 · This suite is additive and test‑only; production `app/**` is
never modified. See the root `README.md` for the application overview and
`CONTRIBUTING.md` for contribution conventions.</sub>

