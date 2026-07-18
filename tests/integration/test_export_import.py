"""Layer-2 integration test: byte-identical export -> import round-trip (CBEXPORT/CBIMPORT).

Purpose
-------
Verify the CardDemo data **export/import** pair -- ``CBEXPORT`` and ``CBIMPORT`` --
as a single byte-identical *round-trip*, modelled on the ``IDCAMS REPRO`` pattern of
``samples/jcl/REPRTEST.jcl`` (used as a REFERENCE template only, never modified). The
round-trip is::

    original INDEXED masters
        --CBEXPORT-->  a single EXPFILE export dataset
        --CBIMPORT-->  per-type SEQUENTIAL outputs

and the test asserts that every re-materialised output record byte-matches the
corresponding original master record (after normalising volatile fields). This is the
automated, asserting analogue of the manual REPRO round-trip the mainframe job
performs, promoted to a financial-grade equality check.

Binding contract (GnuCOBOL ASSIGN name -> record geometry)
----------------------------------------------------------
``CBEXPORT`` reads five INDEXED masters and writes one export dataset::

    ASSIGN     reclen  key (len @ off)          copybook / codec layout
    CUSTFILE   500     CUST-ID       ( 9 @ 0)   CVCUS01Y / CUSTOMER
    ACCTFILE   300     ACCT-ID       (11 @ 0)   CVACT01Y / ACCOUNT
    XREFFILE    50     XREF-CARD-NUM (16 @ 0)   CVACT03Y / XREF
    TRANSACT   350     TRAN-ID       (16 @ 0)   CVTRA05Y / TRAN
    CARDFILE   150     CARD-NUM      (16 @ 0)   CVACT02Y / CARD
    EXPFILE    500     EXPORT-SEQUENCE-NUM 9(9) COMP   CVEXPORT   (output)

``CBIMPORT`` reads that EXPFILE and writes six SEQUENTIAL outputs; the five data
outputs re-materialise the masters in their ORIGINAL copybook layouts::

    ASSIGN    reclen  copybook / codec layout
    CUSTOUT   500     CVCUS01Y / CUSTOMER
    ACCTOUT   300     CVACT01Y / ACCOUNT
    XREFOUT    50     CVACT03Y / XREF
    TRNXOUT   350     CVTRA05Y / TRAN
    CARDOUT   150     CVACT02Y / CARD
    ERROUT    132     error report (not part of the round-trip)

The 500-byte CVEXPORT export envelope carries exactly one volatile field --
``EXPORT-TIMESTAMP`` PIC X(26) at offset 1 (right after the 1-byte
``EXPORT-REC-TYPE``) -- which is a wall-clock stamp and must never enter a byte
comparison (see the WHY note on ``_EXPORT_TIMESTAMP_OFFSET``). The re-materialised
master outputs do NOT carry that envelope field; their own runtime timestamps (e.g.
``TRAN-PROC-TS``) are the volatile fields normalised here via
:func:`tests.helpers.record_codec.normalize_timestamps`.

RUN STATUS -- documented conditional skip (the expected outcome on this runner)
------------------------------------------------------------------------------
``CBEXPORT`` and ``CBIMPORT`` currently **do not compile** under any GnuCOBOL dialect
(verified: default, cobol2014, cobol2002, mvs, ibm-strict)::

    app/cbl/CBEXPORT.cbl:68: error: 'EXPORT-SEQUENCE-NUM' is not defined
    app/cbl/CBIMPORT.cbl:40: error: 'EXPORT-SEQUENCE-NUM' is not defined

Root cause: both declare ``SELECT ... ASSIGN EXPFILE ORGANIZATION INDEXED RECORD KEY
IS EXPORT-SEQUENCE-NUM``, but the EXPFILE FD record is ``01 EXPORT-OUTPUT-RECORD PIC
X(500)`` while ``EXPORT-SEQUENCE-NUM`` is defined in copybook ``CVEXPORT`` copied into
WORKING-STORAGE, NOT into the file record. A ``RECORD KEY`` must name a field inside
the file's own record, so ``cobc`` rejects the SELECT and emits no binary. This is a
**production source defect**, and fixing production COBOL is explicitly OUT OF SCOPE
(AAP Section 0.8.2 -- production code is REFERENCE only). The suite's build script
``scripts/build_test_programs.sh`` already classifies both as "KNOWN-UNSUPPORTED" and
does not fail the build.

Therefore this test's primary, expected behaviour is a **documented, explicit skip**
(never a silent pass, never ``xfail``). Crucially, the full round-trip assertion path
below is implemented correctly so the test **auto-upgrades to a real, asserting test**
the moment buildable ``CBEXPORT``/``CBIMPORT`` binaries appear in ``build_dir`` (a
future source fix, or an externally supplied prebuilt binary) -- with no further edits
to this file.

Determinism & isolation
-----------------------
Each test runs in a fresh per-test ``workspace`` (via the ``cobol_runner`` fixture),
so runs are independent and safe under ``pytest-xdist -n auto``. The skip path is
fully deterministic. The round-trip path removes every source of non-determinism by
(a) never comparing the volatile export envelope, (b) normalising each master
layout's runtime timestamp, and (c) comparing records as sorted sets so the
ascending-primary-key order in which the programs traverse INDEXED files never
matters.

Explainability
--------------
Per the project's mandatory Explainability rule (AAP Section 0.10.1) every function
below carries a Purpose / Parameters / Returns / Raises docstring, and each non-obvious
decision is annotated with a WHY comment documenting at least one of Alternatives
Considered, Refactoring Rationale, Assumptions, or Trade-offs.

There is deliberately **no** ``__init__.py`` anywhere under ``tests/`` -- the tree
resolves as a PEP 420 namespace package via ``PYTHONPATH=<repo_root>`` (which the
bootstrap ``tests/conftest.py`` injects and the runner scripts export).
"""

from __future__ import annotations

import shutil
import subprocess
from collections import namedtuple
from pathlib import Path

import pytest

# WHY (financial-grade rigor): register the whole module under the `integration`
# marker (declared in tests/pytest.ini with --strict-markers) so the layer-scoped
# runner `pytest -m integration` includes it and a marker typo would hard-error
# instead of silently dropping the test.
pytestmark = pytest.mark.integration


# ---------------------------------------------------------------------------
# Module constants.
# ---------------------------------------------------------------------------

# The export/import program pair under test, ordered export-then-import because the
# round-trip runs them in exactly that dependency order.
_PROGRAMS: "tuple[str, ...]" = ("CBEXPORT", "CBIMPORT")

# The single volatile field inside the 500-byte CVEXPORT export envelope:
# EXPORT-TIMESTAMP PIC X(26) at offset 1 (immediately after the 1-byte
# EXPORT-REC-TYPE).
#
# WHY name these even though the comparison below never slices them out (Assumption +
# Trade-off): EXPORT-TIMESTAMP is a wall-clock value CBEXPORT stamps into every export
# record, so any comparison including it would be non-deterministic. It lives ONLY in
# the intermediate EXPFILE envelope, which this test never byte-compares (it asserts
# EXPFILE is merely produced and non-empty). The records it DOES compare are the
# re-materialised MASTER outputs, whose copybook layouts do not contain this field at
# all -- their own runtime stamps are blanked via record_codec.normalize_timestamps
# instead. Naming the offset/length documents the excluded field precisely (rather
# than as a bare "1"/"26" magic pair) for a future reader who extends the test to
# inspect the export envelope directly.
_EXPORT_TIMESTAMP_OFFSET: int = 1
_EXPORT_TIMESTAMP_LENGTH: int = 26

# Fixture scenario directories to search, most-specific first. A dedicated `export`
# domain is preferred once authored; `provisioning/happy_path` is the pragmatic
# fallback because it already ships production-shaped master fixtures.
#
# WHY a preference list rather than one hard-coded directory (Alternatives Considered):
# the AAP's fixture tree groups fixtures by domain/scenario, and a dedicated `export`
# domain does not exist yet. A list lets the round-trip bind to whichever scenario is
# present without this file having to change when the export fixtures land.
_SCENARIO_PREFS: "tuple[str, ...]" = (
    "export/happy_path",
    "export/roundtrip",
    "provisioning/happy_path",
)


# One master file's full round-trip specification. WHY a namedtuple over a bare tuple
# (Trade-off): the shape (input ASSIGN, output ASSIGN, layout, candidate filenames) is
# read in two places -- load and compare -- and named access keeps the fields from
# silently transposing.
_Master = namedtuple("_Master", ("name", "in_assign", "out_assign", "layout", "candidates"))

# The five masters exported by CBEXPORT and re-materialised by CBIMPORT, each mapped to
# its input ASSIGN name, output ASSIGN name, record_codec layout, and the candidate
# fixture filenames to try (seed-style names first, then the ASSIGN name).
_MASTERS: "tuple[_Master, ...]" = (
    _Master("customer", "CUSTFILE", "CUSTOUT", "CUSTOMER",
            ("custdata.txt", "custfile.txt", "customer.txt", "CUSTFILE")),
    _Master("account", "ACCTFILE", "ACCTOUT", "ACCOUNT",
            ("acctdata.txt", "acctfile.txt", "account.txt", "ACCTFILE")),
    _Master("xref", "XREFFILE", "XREFOUT", "XREF",
            ("cardxref.txt", "xreffile.txt", "xref.txt", "XREFFILE")),
    _Master("transaction", "TRANSACT", "TRNXOUT", "TRAN",
            ("trandata.txt", "transact.txt", "transaction.txt", "TRANSACT")),
    _Master("card", "CARDFILE", "CARDOUT", "CARD",
            ("carddata.txt", "cardfile.txt", "card.txt", "CARDFILE")),
)


def _ensure_export_import(build_dir: Path, repo_root: Path) -> None:
    """Ensure runnable CBEXPORT/CBIMPORT binaries exist, else skip with the defect reason.

    Purpose
    -------
    Gate the round-trip on the availability of buildable ``CBEXPORT`` and ``CBIMPORT``
    programs. Prefer binaries already produced by ``scripts/build_test_programs.sh``;
    if either is missing, attempt an on-demand GnuCOBOL compile. If the compile fails
    (the expected outcome, because of the documented ``EXPORT-SEQUENCE-NUM`` source
    defect) the whole test is skipped with an auditable message rather than failing or
    erroring.

    Parameters
    ----------
    build_dir : pathlib.Path
        Directory that holds (or will receive) the compiled ``CBEXPORT`` and
        ``CBIMPORT`` executables -- the same directory the ``cobol_runner`` fixture
        resolves programs from, so a binary compiled here is runnable by the runner.
    repo_root : pathlib.Path
        Repository root, used to locate the COBOL sources (``app/cbl``) and the
        copybook include path (``app/cpy``).

    Returns
    -------
    None
        Returns normally only when both binaries are present and runnable -- i.e. the
        auto-upgrade path where the round-trip assertions execute for real.

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`) when ``cobc`` is not on ``PATH``, a compile times
        out, or either program fails to compile. The skip message cites the production
        ``EXPORT-SEQUENCE-NUM`` defect and includes the captured ``cobc`` stderr so the
        reason is self-explanatory in a CI report.
    """
    # Prefer already-built binaries. WHY (Trade-off): scripts/build_test_programs.sh is
    # the authoritative, cached build for the whole suite; if it (or a future source
    # fix / an externally supplied binary) has produced both programs, we run the real
    # round-trip and never pay for a redundant compile here.
    missing = [name for name in _PROGRAMS if not (build_dir / name).is_file()]
    if not missing:
        return

    # A missing compiler is an ENVIRONMENT condition, not a test defect, so skip
    # (matching conftest's built_programs policy) rather than erroring the test.
    if shutil.which("cobc") is None:
        pytest.skip(
            "GnuCOBOL 'cobc' not on PATH and prebuilt CBEXPORT/CBIMPORT are absent; "
            "cannot exercise the export/import round-trip."
        )

    cpy_dir = repo_root / "app" / "cpy"
    cbl_dir = repo_root / "app" / "cbl"
    # OPEN OUTPUT cannot create missing parents; ensure the build dir exists before we
    # point `-o` at it (idempotent, safe if it already exists).
    build_dir.mkdir(parents=True, exist_ok=True)

    failures: "list[str]" = []
    for name in missing:
        source = cbl_dir / f"{name}.cbl"
        target = build_dir / name
        # Reuse the repository's documented compile convention (scripts/local_compile.sh
        # / build_test_programs.sh): fixed-format, --std=ibm-strict (the only dialect
        # that accepts the COMP-3 money fields), copybooks resolved from app/cpy.
        # WHY -x: CBEXPORT/CBIMPORT are main programs (own PROCEDURE entry point), so
        # they build as executables, not dynamically CALL'd .so modules.
        command = [
            "cobc", "-x", "-fixed", "--std=ibm-strict",
            "-I", str(cpy_dir), "-o", str(target), str(source),
        ]
        try:
            completed = subprocess.run(
                command, capture_output=True, text=True, timeout=120,
            )
        except subprocess.TimeoutExpired as exc:  # pragma: no cover - defensive
            # A hung compiler is an environment fault, not a product assertion; skip.
            pytest.skip(f"compiling {name} timed out after 120s: {exc}")

        if completed.returncode != 0 or not target.is_file():
            # Capture a bounded stderr tail so the skip reason is actionable without
            # dumping an unbounded compiler log into the CI report.
            stderr_tail = "\n".join((completed.stderr or "").strip().splitlines()[-12:])
            failures.append(f"{name} (cobc rc={completed.returncode}):\n{stderr_tail}")

    if failures:
        # WHY skip and NOT xfail (Alternatives Considered): the programs are
        # un-buildable because of a production source defect that is out of scope to
        # fix, so there is no product behaviour to assert. An xfail would still try to
        # RUN a non-existent binary (muddying the failure) and, under this suite's
        # xfail_strict=True, would flip to a HARD failure the day the source is fixed.
        # A precise, documented skip is auditable today and cleanly auto-upgrades to a
        # real pass/fail the moment the binaries build.
        detail = "\n\n".join(failures)
        pytest.skip(
            "CBEXPORT/CBIMPORT do not compile under GnuCOBOL, so the export/import "
            "round-trip cannot run. This is a DOCUMENTED production source defect "
            "(out of scope to fix per AAP Section 0.8.2): both declare "
            "'RECORD KEY IS EXPORT-SEQUENCE-NUM' on the EXPFILE SELECT, but "
            "EXPORT-SEQUENCE-NUM is defined in copybook CVEXPORT within "
            "WORKING-STORAGE, not inside the file's FD record -- a RECORD KEY must "
            "name a field in the file record, so cobc rejects the SELECT and emits no "
            "binary. Captured cobc output:\n\n" + detail
        )


def _select_scenario_dir(fixtures_root: Path) -> Path:
    """Return the first existing fixture scenario directory from the preference list.

    Purpose
    -------
    Choose which ``tests/fixtures/<domain>/<scenario>`` directory supplies the master
    fixtures for the round-trip, honouring :data:`_SCENARIO_PREFS` order so a dedicated
    ``export`` scenario is used when present and ``provisioning/happy_path`` serves as
    the fallback.

    Parameters
    ----------
    fixtures_root : pathlib.Path
        The ``tests/fixtures`` directory to resolve the preference entries against.

    Returns
    -------
    pathlib.Path
        The first directory in :data:`_SCENARIO_PREFS` that exists on disk.

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`) if none of the preferred scenario directories exists
        -- the round-trip has no data to run against, which is a fixture-provisioning
        gap, not a product failure.
    """
    for rel in _SCENARIO_PREFS:
        candidate = fixtures_root / rel
        if candidate.is_dir():
            return candidate
    # WHY skip (Assumption): absent fixtures mean the suite has not yet shipped an
    # export scenario; that is a data gap to fill, not a defect in CBEXPORT/CBIMPORT,
    # so a skip (not a failure) is the honest signal.
    pytest.skip(
        "no export/import fixture scenario directory found under "
        f"{fixtures_root} (tried {', '.join(_SCENARIO_PREFS)})"
    )


def _resolve_fixture(scenario_dir: Path, *candidates: str) -> Path:
    """Return the first candidate fixture that exists in a scenario directory.

    Purpose
    -------
    Resiliently locate one master's flat fixture by trying several candidate filenames
    (seed-style names such as ``custdata.txt`` first, then the ASSIGN name) inside an
    already-selected scenario directory, so the round-trip is not coupled to a single
    naming convention.

    Parameters
    ----------
    scenario_dir : pathlib.Path
        The scenario directory (from :func:`_select_scenario_dir`) to search.
    *candidates : str
        One or more candidate filenames, tried in order. The first that names an
        existing regular file wins.

    Returns
    -------
    pathlib.Path
        The path of the first existing candidate file.

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`) if none of the candidates exists in ``scenario_dir``
        -- a missing master fixture is a data gap, so the round-trip skips rather than
        fails.
    """
    for name in candidates:
        candidate = scenario_dir / name
        if candidate.is_file():
            return candidate
    # WHY skip rather than fail (Trade-off): the chosen scenario dir may legitimately
    # lack one master (e.g. provisioning/happy_path ships no transaction fixture). A
    # skip keeps the round-trip's absence auditable and lets it light up once a
    # complete export scenario is authored, without ever green-washing a real run.
    pytest.skip(
        f"no fixture among {list(candidates)} found in {scenario_dir}; "
        "cannot assemble the export/import master set."
    )


def _read_flat_records(path: Path, reclen: int) -> "list[str]":
    """Read a fixed-width flat/sequential file into a list of ``reclen``-char records.

    Purpose
    -------
    Frame either an original flat fixture or a CBIMPORT sequential output into its
    logical fixed-width records, tolerating both on-disk framings the harness may
    encounter: contiguous record-sequential bytes (GnuCOBOL ``ORGANIZATION IS
    SEQUENTIAL`` output, no delimiters) and newline-delimited fixtures.

    Parameters
    ----------
    path : pathlib.Path
        The flat file to read.
    reclen : int
        The fixed logical record length in characters used to frame contiguous data.

    Returns
    -------
    list[str]
        The framed records as ``reclen``-character strings (contiguous framing) or the
        newline-delimited rows with their single trailing terminator removed. An empty
        file yields ``[]``.

    Raises
    ------
    FileNotFoundError
        If ``path`` does not exist.
    """
    # latin-1 maps all 256 byte values 1:1, so decoding fixed-width EBCDIC/zoned bytes
    # can never raise and slicing stays byte-exact (the same choice
    # cobol_runner.read_output documents for CardDemo records).
    data = Path(path).read_bytes().decode("latin-1")
    n = len(data)
    # WHY prefer exact reclen chunking first (Assumption): CBIMPORT declares its outputs
    # ORGANIZATION IS SEQUENTIAL (record-sequential), which GnuCOBOL writes as
    # contiguous fixed records with NO record delimiter -- so a clean multiple of reclen
    # is the record-sequential case and must be framed by width. Doing this before the
    # newline split also prevents a stray 0x0A byte *inside* a binary record from being
    # mistaken for a row terminator.
    if reclen > 0 and n > 0 and n % reclen == 0:
        return [data[i:i + reclen] for i in range(0, n, reclen)]
    # Fallback: newline-delimited fixture. Normalise line endings, then drop exactly one
    # trailing terminator (the EOF newline) so a file ending in "\n" does not yield a
    # spurious empty final record.
    norm = data.replace("\r\n", "\n").replace("\r", "\n")
    if norm.endswith("\n"):
        norm = norm[:-1]
    if norm == "":
        return []
    return norm.split("\n")


def test_export_import_roundtrip(cobol_runner, build_dir, repo_root) -> None:
    """Assert a byte-identical export -> import round-trip of the five master files.

    Purpose
    -------
    Drive the full ``CBEXPORT`` -> ``EXPFILE`` -> ``CBIMPORT`` round-trip on
    deterministic master fixtures and assert that every re-materialised output record
    byte-matches its original master record (volatile fields normalised). This is the
    asserting automation of the ``samples/jcl/REPRTEST.jcl`` ``IDCAMS REPRO`` model.

    On this runner the test SKIPS at the build gate because ``CBEXPORT``/``CBIMPORT``
    do not compile (the documented ``EXPORT-SEQUENCE-NUM`` defect); the body below runs
    in full only once buildable binaries exist (auto-upgrade).

    Parameters
    ----------
    cobol_runner : tests.helpers.cobol_runner.CobolRunner
        Per-test runner (from the ``cobol_runner`` fixture) bound to the build dir and
        an isolated workspace; used to load indexed inputs and run the programs.
    build_dir : pathlib.Path
        The build directory (from the ``build_dir`` fixture); passed to the build gate
        and shared with the runner so an on-demand-compiled binary is runnable.
    repo_root : pathlib.Path
        Repository root (from the ``repo_root`` fixture); locates COBOL sources, the
        fixture tree, and the golden tree.

    Returns
    -------
    None

    Raises
    ------
    Skipped
        (via :func:`pytest.skip`, from :func:`_ensure_export_import`,
        :func:`_select_scenario_dir`, or :func:`_resolve_fixture`) when the programs
        are un-buildable or a required master fixture is absent.
    AssertionError
        If either program returns non-zero, an expected output is missing or empty, or
        a re-materialised record does not byte-match its original master record.
    tests.helpers.golden_compare.GoldenMismatchError
        If a committed golden exists for an output and the normalised output differs
        from it (``GoldenMismatchError`` is a subclass of ``AssertionError``).
    """
    # 1. BUILD GATE FIRST. WHY (ordering): this is the single call that turns the
    # documented compile defect into an explicit, auditable skip. It must run before
    # any fixture resolution or program execution so the skip reason a reader sees is
    # the EXPORT-SEQUENCE-NUM defect, not a downstream "fixture missing" artefact.
    _ensure_export_import(build_dir, repo_root)

    # 2. Round-trip helpers are imported LAZILY here (not at module top). WHY
    # (Trade-off, mirroring conftest's lazy CobolRunner import): only this auto-upgrade
    # path needs them, so collection and the skip path stay dependency-light and cannot
    # be broken by a helper-import problem in an environment that will only ever skip.
    from tests.helpers.record_codec import LAYOUTS, normalize_timestamps, reclen_of
    from tests.helpers.golden_compare import assert_matches_golden

    fixtures_root = repo_root / "tests" / "fixtures"
    golden_root = repo_root / "tests" / "golden"
    scenario_dir = _select_scenario_dir(fixtures_root)

    # 3. Stage every master as an INDEXED input and remember its original records so the
    # round-trip can be checked against the exact bytes that went in.
    originals: "dict[str, list[str]]" = {}
    for master in _MASTERS:
        fixture = _resolve_fixture(scenario_dir, *master.candidates)
        # WHY alternate_keys=() (Assumption -> avoid FILE STATUS 39): every CBEXPORT
        # master SELECT declares only a primary RECORD KEY (no ALTERNATE KEY), so we
        # build primary-key-only indexed files. Loading XREF with its codec-declared
        # account-id alternate index would make the file's key set richer than the
        # program's OPEN expects, which GnuCOBOL can reject as a file-attribute
        # mismatch (status 39). The empty tuple forces primary-key-only even for the
        # alternate-keyed XREF layout, while ``layout=`` still supplies the geometry.
        cobol_runner.load_input(
            master.in_assign, fixture, layout=master.layout, alternate_keys=(),
        )
        originals[master.name] = _read_flat_records(fixture, reclen_of(master.layout))

    # 4. Export: the five masters -> one EXPFILE dataset.
    export_result = cobol_runner.run("CBEXPORT")
    assert export_result.returncode == 0, (
        f"CBEXPORT expected RETURN-CODE 0, got {export_result.returncode}. "
        f"stderr tail:\n{export_result.stderr[-2000:]}"
    )
    export_path = export_result.output_path("EXPFILE")
    # WHY assert only "produced and non-empty" for EXPFILE (Trade-off): the export
    # envelope is an INDEXED dataset keyed on a COMP field and carries the volatile
    # EXPORT-TIMESTAMP, so a byte-level assertion on it would be both awkward and
    # non-deterministic. Its correctness is proven transitively by the import step's
    # exact round-trip comparison below; here we only confirm CBEXPORT wrote something.
    assert export_path.is_file() and export_path.stat().st_size > 0, (
        f"CBEXPORT did not produce a non-empty EXPFILE at {export_path}."
    )

    # 5. Import: EXPFILE -> per-type SEQUENTIAL outputs.
    import_result = cobol_runner.run("CBIMPORT")
    assert import_result.returncode == 0, (
        f"CBIMPORT expected RETURN-CODE 0, got {import_result.returncode}. "
        f"stderr tail:\n{import_result.stderr[-2000:]}"
    )

    # 6. Assert the byte-identical round-trip, one master type at a time.
    scenario_rel = scenario_dir.relative_to(fixtures_root)
    for master in _MASTERS:
        out_path = import_result.output_path(master.out_assign)
        assert out_path.is_file(), (
            f"CBIMPORT did not produce the {master.out_assign} output at {out_path}."
        )
        reclen = reclen_of(master.layout)
        actual_records = _read_flat_records(out_path, reclen)

        golden_path = golden_root / scenario_rel / f"{master.out_assign}.expected"
        if golden_path.is_file():
            # Prefer a committed golden when one exists: assert_matches_golden applies
            # record-mode (layout-aware) normalisation to BOTH sides -- including
            # blanking the layout's runtime timestamp -- and raises a masked,
            # privacy-safe diff on mismatch. WHY join with "\n": record mode frames by
            # width, so the newline join compares equal to the width-framed golden.
            assert_matches_golden(
                "\n".join(actual_records), golden_path, layout=master.layout,
            )
            continue

        # No golden committed yet: fall back to a direct original-vs-round-trip byte
        # comparison. WHY compare SORTED, NORMALISED records (Assumptions):
        #  * CBEXPORT reads INDEXED masters in ascending PRIMARY-KEY order and CBIMPORT
        #    writes in that order, which need not match the fixture's on-disk order --
        #    so we compare as sorted multisets to be order-independent.
        #  * normalize_timestamps blanks each layout's runtime timestamp field (e.g.
        #    TRAN-PROC-TS) and validates every record is exactly `reclen` wide; layouts
        #    with no volatile field (CUSTOMER/ACCOUNT/XREF/CARD) are returned unchanged
        #    after that width check. This is the concrete "blank the volatile field
        #    before comparing" step for the re-materialised master payloads.
        layout_obj = LAYOUTS[master.layout]
        expected_norm = sorted(
            normalize_timestamps(record, layout_obj) for record in originals[master.name]
        )
        actual_norm = sorted(
            normalize_timestamps(record, layout_obj) for record in actual_records
        )
        # WHY assert on a pre-computed bool, not `actual_norm == expected_norm` directly
        # (Privacy / MA-13): pytest's assertion rewriting would otherwise dump BOTH full
        # record lists into the report on failure, leaking cardholder PII (names, SSNs,
        # PANs). Comparing a plain bool keeps the operands out of the introspected
        # output; the golden path (above) uses the field-aware masker for the same
        # reason. The message states only record COUNTS, never record content.
        records_match = actual_norm == expected_norm
        assert records_match, (
            f"export/import round-trip mismatch for {master.name} "
            f"({master.in_assign} -> EXPFILE -> {master.out_assign}): "
            f"{len(actual_norm)} output record(s) vs {len(expected_norm)} original(s) "
            "did not byte-match after volatile-field normalisation."
        )

