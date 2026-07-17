"""Shared pytest fixtures for the AWS CardDemo automated test suite.

Purpose
-------
This is the **root** ``conftest.py`` for the CardDemo Python test layers
(``tests/integration`` and ``tests/e2e``). Because ``tests/pytest.ini`` anchors
pytest's ``rootdir`` to ``tests/``, this file is auto-discovered and loaded for
every Python test run, making its fixtures available suite-wide without any
explicit import.

It is the single load-bearing bridge between two worlds:

* the **bash runner world** -- the ``scripts/*.sh`` contracts that export the
  ``CARDDEMO_*`` environment variables (``scripts/test_env.sh``) and compile the
  programs under test (``scripts/build_test_programs.sh``); and
* the **Python test world** -- the integration/e2e modules that need a compiled
  build directory, a reports directory, an isolated per-test workspace, and a
  ready-to-use ``CobolRunner``.

The fixtures here deliberately stay *thin*: all heavy lifting (binding the 28
GnuCOBOL ``ASSIGN`` external names, loading flat fixtures into indexed files,
capturing ``RETURN-CODE``) lives in ``tests/helpers/`` and is imported -- never
re-implemented -- from this conftest.

Rootdir / namespace-package note
--------------------------------
There is intentionally **no** ``__init__.py`` anywhere under ``tests/``; the test
tree resolves as a PEP 420 namespace package via ``PYTHONPATH=<repo_root>`` (set
by the runner scripts). Under a bare ``pytest`` invocation, however, pytest adds
only ``tests/`` (the conftest's rootpath, since it has no adjacent
``__init__.py``) to ``sys.path`` -- not the repository root -- so
``import tests.helpers...`` would fail. The module-level ``sys.path`` bootstrap
below repairs that, making the suite runnable both via the runner scripts and
via a plain ``pytest`` call from the repository root.

Contract summary (kept in lock-step with the sibling scripts)
-------------------------------------------------------------
Environment variables (names/defaults mirror ``scripts/test_env.sh`` EXACTLY):

* ``CARDDEMO_REPO_ROOT``      -> repository root (parent of ``scripts/``)
* ``CARDDEMO_BUILD_DIR``      -> ``<repo_root>/build``
* ``CARDDEMO_REPORTS_DIR``    -> ``<repo_root>/reports``
* ``CARDDEMO_TEST_WORKSPACE`` -> ``<workspace base>/run-<id>`` (bash default)
* ``CARDDEMO_DATA_DIR``       -> ``<CARDDEMO_TEST_WORKSPACE>/data``

Return-code rubric (from ``scripts/test_env.sh`` / ``build_test_programs.sh``):
``0`` pass, ``4`` warn/reject, ``8`` fail, ``16`` fatal, ``2`` usage.

Fixtures exposed
----------------
* ``repo_root``      (session) -> :class:`pathlib.Path`
* ``build_dir``      (session) -> :class:`pathlib.Path`
* ``reports_dir``    (session) -> :class:`pathlib.Path`
* ``built_programs`` (session) -> :class:`pathlib.Path` (the build dir, after a
  one-time compile of the programs under test)
* ``workspace``      (function) -> :class:`pathlib.Path` (fresh, isolated)
* ``cobol_runner``   (function) -> ``tests.helpers.cobol_runner.CobolRunner``
"""

# ``from __future__ import annotations`` keeps every annotation a lazy string.
# WHY (Trade-off): the AAP targets Python 3.12 but pins a 3.9+ floor; stringised
# annotations let us reference types like ``pytest.Config`` without paying an
# import-time cost or risking a version-specific attribute lookup at load time.
from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

# ---------------------------------------------------------------------------
# sys.path bootstrap -- MUST run at import time, before any ``tests.*`` import.
# ---------------------------------------------------------------------------
# ``tests/conftest.py`` lives at ``<repo_root>/tests/conftest.py``; its
# grandparent (``parent.parent``) is therefore the repository root.
_REPO_ROOT = Path(__file__).resolve().parent.parent

# WHY (Alternatives Considered): the test tree could have been made importable by
# adding ``__init__.py`` files, but that was rejected suite-wide in favour of
# PEP 420 namespace packages resolved through ``PYTHONPATH=<repo_root>`` (the
# approach the runner scripts already use, and the one documented in
# tests/helpers/cobol_runner.py). Under a *bare* ``pytest`` run the runner does
# not set PYTHONPATH, and pytest -- finding no ``__init__.py`` beside this
# conftest -- prepends only ``tests/`` to ``sys.path``. Injecting the repository
# root here (idempotently, guarded by the membership test) is what lets
# ``from tests.helpers... import ...`` resolve identically in both invocation
# styles without ever adding a duplicate ``sys.path`` entry.
if str(_REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(_REPO_ROOT))


# ---------------------------------------------------------------------------
# Return-code thresholds mirrored from scripts/test_env.sh (CARDDEMO_RC_*).
# ---------------------------------------------------------------------------
# WHY (Assumption + Trade-off): scripts/test_env.sh is the authoritative source
# of the rubric and exports CARDDEMO_RC_WARN=4 / CARDDEMO_RC_FAIL=8, but a Python
# fixture cannot see a bash script's shell variables. We mirror ONLY the two
# thresholds this conftest actually branches on, as named constants, so the
# coupling is explicit and reviewable rather than hidden behind magic numbers.
_RC_WARN = 4  # soft warning / reject / "nothing to do" -- non-fatal, proceed
_RC_FAIL = 8  # a required compile step failed -- fatal, abort the session

# The five markers this suite recognises. Kept byte-for-byte identical to
# tests/pytest.ini so the belt-and-suspenders registration in pytest_configure
# never drifts from the canonical ini declaration.
_MARKERS = (
    ("unit", "COBOL/GCBLUnit-level unit tests (fast, isolated program/paragraph logic)."),
    ("integration", "single-program pytest tests against compiled GnuCOBOL programs."),
    ("e2e", "full batch-chain end-to-end golden-master tests."),
    ("localstack", "tests requiring the headless LocalStack S3 emulator (opt-in)."),
    ("slow", "long-running tests (may be deselected for fast local iteration)."),
)


def _tail(text: str, max_lines: int = 40) -> str:
    """Return the last ``max_lines`` lines of ``text`` for concise diagnostics.

    Purpose
    -------
    Trim a potentially large captured subprocess transcript down to the tail
    that actually matters for a failure message, keeping pytest reports readable.

    Parameters
    ----------
    text : str
        The full captured text (may be empty or otherwise falsy).
    max_lines : int, optional
        Maximum number of trailing lines to keep (default ``40``).

    Returns
    -------
    str
        The last ``max_lines`` lines joined by newlines, or an empty string if
        ``text`` is falsy.

    Raises
    ------
    None
    """
    # WHY (Trade-off): 40 lines is a pragmatic cap -- long enough to show a
    # GnuCOBOL diagnostic and its surrounding context, short enough that it does
    # not bury the actionable line at the bottom of a huge pytest failure block.
    if not text:
        return ""
    return "\n".join(text.splitlines()[-max_lines:])


def pytest_configure(config: pytest.Config) -> None:
    """Register the suite's markers as a defensive backup to ``tests/pytest.ini``.

    Purpose
    -------
    Re-declare the five CardDemo markers programmatically so that
    ``--strict-markers`` never rejects a legitimately-marked test even if this
    conftest is loaded in a context where ``tests/pytest.ini`` was not picked up
    (for example, a caller pointing pytest at a different config file).

    Parameters
    ----------
    config : pytest.Config
        The active pytest configuration object, supplied by pytest. Used to
        append ``markers`` ini-lines via ``config.addinivalue_line``.

    Returns
    -------
    None

    Raises
    ------
    None
        Registration is additive and cannot fail for well-formed marker strings.
    """
    # WHY (Trade-off): duplicating the marker list here and in pytest.ini is a
    # small, deliberately-accepted maintenance cost -- it guarantees marker
    # validity is a property of the *code* and not only of an ini file that a
    # non-standard invocation might bypass. addinivalue_line is additive, so
    # re-declaring markers already present in pytest.ini is harmless.
    for name, description in _MARKERS:
        config.addinivalue_line("markers", f"{name}: {description}")


@pytest.fixture(scope="session")
def repo_root() -> Path:
    """Absolute path to the CardDemo repository root.

    Purpose
    -------
    Provide the single anchor from which every other path fixture is derived,
    honouring an operator-supplied ``CARDDEMO_REPO_ROOT`` override when present
    and otherwise falling back to this file's grandparent directory.

    Parameters
    ----------
    None

    Returns
    -------
    pathlib.Path
        The resolved (absolute, symlink-free) repository-root directory.

    Raises
    ------
    None
    """
    # WHY (Assumption): honour CARDDEMO_REPO_ROOT first so a run driven by the
    # sibling scripts (which export it) and a bare pytest run agree on the same
    # root; the computed grandparent is only the fallback. ``.resolve()`` makes
    # downstream comparisons stable against the resolved paths CobolRunner emits.
    return Path(os.environ.get("CARDDEMO_REPO_ROOT", _REPO_ROOT)).resolve()


@pytest.fixture(scope="session")
def build_dir(repo_root: Path) -> Path:
    """Directory holding the compiled programs under test (``<repo_root>/build``).

    Purpose
    -------
    Resolve the location into which ``scripts/build_test_programs.sh`` compiles
    the batch executables (``build/<PROG>``) and CALL'd shared modules
    (``build/<NAME>.so``), honouring a ``CARDDEMO_BUILD_DIR`` override.

    Parameters
    ----------
    repo_root : pathlib.Path
        The repository-root fixture; supplies the default parent directory.

    Returns
    -------
    pathlib.Path
        The build-directory path. Not necessarily created yet -- the build
        script and/or the ``built_programs`` fixture create it.

    Raises
    ------
    None
    """
    # WHY (Assumption): mirror test_env.sh's ``CARDDEMO_BUILD_DIR:-<root>/build``
    # exactly so this fixture names the same directory the build script writes
    # to. Not created here on purpose: directory creation is the build step's
    # responsibility, and an empty build dir must not read as "already built".
    return Path(os.environ.get("CARDDEMO_BUILD_DIR", repo_root / "build"))


@pytest.fixture(scope="session")
def reports_dir(repo_root: Path) -> Path:
    """Directory for run artifacts / JUnit reports (``<repo_root>/reports``).

    Purpose
    -------
    Resolve and create the directory where the runner scripts write
    ``reports/*.xml`` and where tests may persist auxiliary artifacts, honouring
    a ``CARDDEMO_REPORTS_DIR`` override.

    Parameters
    ----------
    repo_root : pathlib.Path
        The repository-root fixture; supplies the default parent directory.

    Returns
    -------
    pathlib.Path
        The reports directory, guaranteed to exist on return.

    Raises
    ------
    OSError
        If the directory cannot be created (e.g. permission denied).
    """
    path = Path(os.environ.get("CARDDEMO_REPORTS_DIR", repo_root / "reports"))
    # WHY (Trade-off): create eagerly (``parents=True, exist_ok=True``) rather
    # than lazily, so a test that merely wants somewhere to drop an artifact
    # never has to guard for a missing directory; being idempotent, doing it in
    # a session-scoped fixture is safe under repeated resolution.
    path.mkdir(parents=True, exist_ok=True)
    return path


@pytest.fixture(scope="session")
def built_programs(repo_root: Path, build_dir: Path) -> Path:
    """Compile the programs under test once per session; return the build dir.

    Purpose
    -------
    Guarantee the compiled GnuCOBOL programs exist before any integration/e2e
    test runs, by invoking the authoritative ``scripts/build_test_programs.sh``
    exactly once per session (session scope caches the result). Environments
    without a COBOL compiler skip cleanly rather than erroring the session.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root; used as the subprocess working directory and to locate
        the build script.
    build_dir : pathlib.Path
        The build directory returned to callers once the compile succeeds.

    Returns
    -------
    pathlib.Path
        The ``build_dir`` containing the freshly-compiled programs.

    Raises
    ------
    Skipped
        (via ``pytest.skip``) if ``cobc`` is not on ``PATH`` or the build script
        is absent -- COBOL-dependent tests cannot run and are skipped, not
        failed.
    Failed
        (via ``pytest.fail``) if the build script exits fatally (RC >= 8) or with
        an otherwise-unexpected code -- a real, actionable build breakage.
    """
    # WHY (Assumption): a missing compiler is an *environment* condition, not a
    # test defect, so we skip (not fail). This lets the Python suite be collected
    # and any non-COBOL portions be exercised on machines without GnuCOBOL,
    # exactly as the agent_prompt requires. Detection via shutil.which matches
    # the same check the build script performs before it exits 8.
    if shutil.which("cobc") is None:
        pytest.skip("GnuCOBOL 'cobc' not found on PATH; skipping COBOL-dependent tests")

    build_script = repo_root / "scripts" / "build_test_programs.sh"
    # WHY (Assumption): during a partial checkout the script may be absent; treat
    # that like a missing compiler (skip) so collection and non-COBOL tests stay
    # green instead of erroring on a FileNotFoundError from the subprocess.
    if not build_script.is_file():
        pytest.skip(
            f"build script not found: {build_script}; skipping COBOL-dependent tests"
        )

    # Run the build ONCE. WHY (Trade-off): session scope plus an idempotent build
    # script means we pay the ~13-program compile cost a single time and every
    # test reuses the artifacts -- the dominant runtime saving for the suite.
    # capture_output keeps the child's compile noise out of pytest's streams
    # unless we deliberately surface the tail on failure below; text=True yields
    # str (not bytes) so ``_tail`` can splitlines() directly.
    completed = subprocess.run(
        ["bash", str(build_script)],
        cwd=str(repo_root),
        capture_output=True,
        text=True,
    )

    rc = completed.returncode
    # WHY (Trade-off): treat RC >= 8 (fatal) OR any code that is not 0/4 as a
    # failure. RC == 4 (warn) is intentionally allowed through because it means
    # an optional / known-unsupported program (e.g. CBEXPORT/CBIMPORT) did not
    # compile while the core programs did -- the suite should still run. An
    # unexpected code such as 2 (usage) signals we invoked the script wrongly,
    # which is a real bug and must surface, hence it is excluded from (0, 4).
    if rc >= _RC_FAIL or rc not in (0, _RC_WARN):
        tail = _tail(completed.stderr) or _tail(completed.stdout) or "(no output captured)"
        # ``pytrace=False``: the traceback would point at this fixture, not at
        # the actual COBOL compile error, so it adds noise; the captured tail is
        # the signal a developer needs.
        pytest.fail(
            f"scripts/build_test_programs.sh failed (exit {rc}). Last output:\n{tail}",
            pytrace=False,
        )

    return build_dir


@pytest.fixture(scope="function")
def workspace(tmp_path: Path) -> Path:
    """Fresh, isolated per-test working directory (with a ``data/`` subdir).

    Purpose
    -------
    Give each test its own scratch workspace so runs never share mutable state.
    A ``data/`` sub-directory is pre-created to match the layout the sibling
    scripts describe as ``CARDDEMO_DATA_DIR=<CARDDEMO_TEST_WORKSPACE>/data``, into
    which the harness binds every GnuCOBOL ASSIGN name.

    Parameters
    ----------
    tmp_path : pathlib.Path
        pytest's built-in per-test temporary-directory fixture; the source of
        isolation and of automatic post-test cleanup.

    Returns
    -------
    pathlib.Path
        The per-test workspace root (``tmp_path``); ``<workspace>/data`` exists.

    Raises
    ------
    OSError
        If the ``data/`` sub-directory cannot be created.
    """
    # WHY (Trade-off): function scope (a fresh ``tmp_path`` per test) is the
    # deliberate opposite of the session-scoped build -- compiling is expensive
    # and shareable, but *data* must be pristine and unshared so tests run
    # independently and in parallel under ``pytest-xdist -n auto`` without
    # clobbering one another's indexed/VSAM files. This is precisely why we do
    # NOT reuse the single shared ``CARDDEMO_TEST_WORKSPACE`` /
    # ``CARDDEMO_DATA_DIR`` that test_env.sh defaults to for the bash runners:
    # those name one directory for the whole run, which would defeat the
    # per-test isolation the Python layer depends on.
    (tmp_path / "data").mkdir(parents=True, exist_ok=True)
    return tmp_path


@pytest.fixture(scope="function")
def cobol_runner(built_programs: Path, workspace: Path, reports_dir: Path):
    """A ``CobolRunner`` bound to the build dir, this test's workspace, and reports.

    Purpose
    -------
    Hand each test a ready-to-use runner for executing a compiled batch program
    against its own isolated workspace, capturing ``RETURN-CODE`` and output
    files. The heavy lifting lives in ``tests/helpers/cobol_runner.py``; this
    fixture only wires the three collaborators together.

    Parameters
    ----------
    built_programs : pathlib.Path
        The build directory (compiled once per session); passed as the runner's
        ``build_dir`` argument.
    workspace : pathlib.Path
        This test's fresh, isolated workspace; passed as the runner's
        ``workspace`` argument.
    reports_dir : pathlib.Path
        The suite reports directory; passed through so a test may persist run
        artifacts alongside the JUnit reports.

    Returns
    -------
    tests.helpers.cobol_runner.CobolRunner
        A runner instance constructed as
        ``CobolRunner(built_programs, workspace, reports_dir)``.

    Raises
    ------
    Skipped
        (via ``pytest.skip``) if ``tests.helpers.cobol_runner`` cannot be
        imported (e.g. a partial checkout without the helpers) -- so collection
        and the non-runner tests stay green.
    """
    # WHY (Trade-off): import LAZILY inside the fixture body, not at module top.
    # If the helpers module were imported at collection time, a partial checkout
    # missing tests/helpers/ would make the WHOLE conftest fail to load and every
    # test error out. Importing here degrades that failure mode to a clean
    # per-test skip that affects only tests actually requesting a runner.
    try:
        from tests.helpers.cobol_runner import CobolRunner
    except ImportError as exc:  # pragma: no cover - only hit on partial checkouts
        pytest.skip(
            f"tests.helpers.cobol_runner not available ({exc}); "
            "skipping runner-dependent test"
        )

    # WHY (Assumption): the constructor signature
    # ``CobolRunner(build_dir, workspace, reports_dir)`` is a hard contract
    # documented verbatim in that module's docstring; we pass positionally in
    # that exact order so the two files stay in lock-step and a future signature
    # change surfaces immediately here.
    return CobolRunner(built_programs, workspace, reports_dir)
