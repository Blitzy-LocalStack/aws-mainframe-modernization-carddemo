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

Additional environment variables this conftest recognises (test-harness policy,
NOT persisted by ``test_env.sh``):

* ``CARDDEMO_REQUIRE_COBOL``  -> when truthy (``1``/``true``/``yes``/``on``), a
  genuinely-absent COBOL toolchain / build script / helper module is a HARD
  FAILURE instead of a clean skip. This mirrors the existing
  ``CARDDEMO_REQUIRE_LOCALSTACK`` convention in ``scripts/setup_localstack.sh``.
  It is an OPTIONAL, opt-in strict gate: an operator (or a stricter CI variant)
  may set it so a required layer can never silently vanish behind a green-looking
  (all-skipped) report (QA finding M1). The shipped default CI workflow
  (``.github/workflows/tests.yml``) deliberately does NOT arm it -- see the WHY
  note on the M1 gate below for the reason -- and does not need to, because the
  catastrophic all-skipped case is already prevented by build-first gating:
  ``scripts/build_test_programs.sh`` exits 8 when ``cobc`` is absent, failing the
  build (and therefore CI) before any test could even be collected to skip.
* ``CARDDEMO_REQUIRE_LOCALSTACK`` -> the LocalStack peer of the flag above: when
  truthy, an absent/unreachable S3 emulator turns the dataset-staging E2E tests
  from a clean skip into a HARD FAILURE. It mirrors the identical bash flag that
  ``scripts/setup_localstack.sh`` already honours, so a single CI switch makes
  BOTH the bring-up script and the pytest layer treat the AWS layer as required
  (QA finding F4). Consumed via the :func:`localstack_gate` session fixture.
* ``CARDDEMO_BUILD_TIMEOUT``  -> wall-clock seconds allowed for the one-time
  compile subprocess (default ``600``); bounds the build stage so a hung
  compiler cannot stall a run indefinitely (QA finding M3).

Security & concurrency contract (QA findings M3, M6)
----------------------------------------------------
* The repository root is anchored to THIS file's own location and any
  ``CARDDEMO_REPO_ROOT`` override is validated against it -- an override that
  points at a different tree is rejected, never followed (no arbitrary roots).
* ``CARDDEMO_BUILD_DIR`` / ``CARDDEMO_REPORTS_DIR`` overrides must resolve to a
  location CONTAINED under the repository root; an escaping path is rejected.
* The one-time build runs under a cross-process file lock and a per-run stamp,
  so under ``pytest-xdist -n auto`` the programs are compiled EXACTLY ONCE for
  the whole run (never once-per-worker, never racing) and the compile subprocess
  inherits only a MINIMAL, curated environment (no ambient cloud credentials or
  tokens leak into the child).

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

import contextlib
import errno
import fcntl
import os
import shutil
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import TYPE_CHECKING

import pytest

# WHY (Trade-off): ``from __future__ import annotations`` makes every annotation
# a lazy string, so these names are needed ONLY by a static type checker, never
# at runtime. Importing them under ``TYPE_CHECKING`` (False at run time) keeps
# the string annotations ``"Iterator[...]"`` / ``"NoReturn"`` resolvable for
# mypy/pyright while adding zero import cost to the actual test run.
if TYPE_CHECKING:
    from collections.abc import Iterator
    from typing import NoReturn

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

# ---------------------------------------------------------------------------
# M1 -- "required layer" gate (mirrors scripts/setup_localstack.sh's
# CARDDEMO_REQUIRE_LOCALSTACK convention EXACTLY, for one uniform policy).
# ---------------------------------------------------------------------------
# WHY (Refactoring rationale + Convention): setup_localstack.sh already grades a
# genuinely-absent OPTIONAL layer as a HARD FAILURE when its
# ``CARDDEMO_REQUIRE_<LAYER>`` flag is set, and a clean skip otherwise. Reusing
# the identical ``CARDDEMO_REQUIRE_COBOL`` name/semantics here gives operators one
# uniform "absent-required-layer == FAIL, never a green-looking skip" rule they can
# opt into across every layer (LocalStack and COBOL) by turning on one family of
# flags. Without this gate the Python layer could skip all COBOL-dependent tests
# and still exit 0 with an all-skipped (superficially green) report (M1).
#
# WHY the SHIPPED default CI does NOT arm this flag (Assumption + Trade-off): arming
# ``CARDDEMO_REQUIRE_COBOL=1`` by default would promote the export/import module's
# own strict gate to a HARD FAILURE, but CBEXPORT/CBIMPORT cannot compile under
# GnuCOBOL (a documented ``EXPORT-SEQUENCE-NUM`` production-source defect). Fixing
# production COBOL is OUT OF SCOPE -- ``app/cbl`` is REFERENCE-only per AAP 0.8.2 --
# so a default-armed flag would leave CI PERMANENTLY RED on a defect this test-only
# suite is forbidden to fix. The shipped ``.github/workflows/tests.yml`` therefore
# leaves the flag UNSET (COBOL tests still run; only the unfixable pair is skipped
# with an explicit, documented reason) and relies on build-first gating for the
# catastrophic case: ``scripts/build_test_programs.sh`` exits 8 when ``cobc`` is
# wholly absent, so a missing toolchain fails the build -- hence CI -- regardless of
# this flag. The flag stays available for a stricter opt-in CI/dev run that has no
# such unfixable programs, where it correctly fails an all-skipped COBOL layer
# instead of letting it exit 0.
_STRICT_ENV = "CARDDEMO_REQUIRE_COBOL"

# WHY (finding F4 -- symmetric LocalStack gate): the dataset-staging E2E layer is
# OPTIONAL by default (a developer without LocalStack still gets a green core
# suite via a clean skip), but a CI job that OPTED IN with
# ``run_e2e_tests.sh --with-localstack`` must NOT let an absent/unreachable
# emulator masquerade as a green all-skipped report. This flag is the pytest peer
# of the identically-named switch ``scripts/setup_localstack.sh`` already honours,
# so one CI variable makes BOTH sides treat the AWS layer as required. It is kept
# SEPARATE from ``CARDDEMO_REQUIRE_COBOL`` so the two layers can be required
# independently (Trade-off: two flags vs. one blunt "require everything").
_STRICT_LOCALSTACK_ENV = "CARDDEMO_REQUIRE_LOCALSTACK"

# ---------------------------------------------------------------------------
# QA Issue 2 -- "no hidden / unexpected skips" enforcement gate.
# ---------------------------------------------------------------------------
# WHY (Refactoring rationale -- closes the QA finding "an unexpected pytest.skip()
# in a required test passes CI green"): a genuine ``pytest.skip()`` is VISIBLE in
# the JUnit report and the ``-ra`` summary, but on its own it does NOT change any
# layer/master/CI exit status. That let a financial reject-case or monetary test be
# silently disabled while CI stayed green, relying on a human noticing a skip-count
# delta. The hooks below turn any UNEXPECTED skip into a hard, report-visible
# session failure while still permitting the small, DOCUMENTED set of skips that are
# legitimately expected.
#
# WHY an explicit (module, reason) allowlist rather than a bare "at most N skips"
# count (Alternatives Considered): a numeric cap would let a forced skip slip in
# whenever an expected one happened to be absent, and would need re-tuning every
# time the documented set changed. Matching each skip against a self-documenting
# allowlist is precise (a forced skip in a required test never matches) and auditable
# (the allowed set is spelled out here in code).
#
# WHY exactly these two entries (Assumptions, verified empirically on this runner):
#   * ``test_export_import.py`` skipping with the "CBEXPORT/CBIMPORT do not compile"
#     reason is the ONE documented, AAP-out-of-scope (0.8.2) production-source defect
#     that cannot be fixed here. It is an explicit, reasoned skip in the default
#     developer mode -- and that module's OWN local gate still HARD-FAILS it under an
#     opt-in ``CARDDEMO_REQUIRE_COBOL=1`` run, so it is never SILENTLY skipped when
#     required.
#   * ``test_localstack_dataset_staging.py`` is the OPT-IN AWS layer; skipping is its
#     normal default outcome (it runs only with ``--with-localstack``), so any skip
#     reason there is expected -- hence a module-level allow (reason ``None``).
# The known ``CBACT04C`` ``xfail(strict=True)`` is intentionally NOT listed: an xfail
# is a distinct outcome governed by ``xfail_strict=True`` (pytest.ini), not a skip,
# and is excluded from this gate by the ``wasxfail`` check in the report hook below.
#
# Each entry is ``(nodeid_substring, reason_substring_or_None)``; a skip is allowed
# iff its nodeid contains the module substring AND (the reason substring is ``None``
# OR appears in the skip's reason text).
_SKIP_ALLOWLIST = (
    ("test_export_import.py", "CBEXPORT/CBIMPORT do not compile"),
    ("test_localstack_dataset_staging.py", None),
)

# Accumulator of UNEXPECTED genuine skips observed during the session, keyed by test
# nodeid so a skip reported once per test is never double-counted. WHY a module-global
# rather than a fixture (Trade-off): pytest's report hook ``pytest_runtest_logreport``
# receives only the report object (no session/config), and each pytest run is a fresh
# process, so a module-level dict reset in ``pytest_configure`` is the simplest correct
# store. Under ``pytest-xdist`` the CONTROLLER process receives every worker's forwarded
# report here, so the controller accumulates the complete picture for the whole run.
_UNEXPECTED_SKIPS: "dict[str, str]" = {}

# ---------------------------------------------------------------------------
# F-DOC-LOCALSTACK-COMMAND-GREEN-SKIP -- zero-executed LocalStack layer gate.
# ---------------------------------------------------------------------------
# WHY (Refactoring rationale -- closes "a bare `pytest -m localstack` exits 0 with
# every AWS test merely SKIPPED"): selecting a layer explicitly (``-m localstack``)
# is an INTENT to verify it, so a run in which the emulator is absent and all three
# AWS tests skip must NOT read as green -- "nothing ran" is not "everything passed".
# The per-test F4 gate (_require_localstack_or_skip) already turns an absent-but-
# REQUIRED emulator into hard failures; this SESSION-level backstop additionally
# fails the DEFAULT (non-required) localstack-targeted run when zero AWS test bodies
# actually executed, so the documented command can never present an all-skipped run
# as success.
#
# WHY key on the EXACT ``-m`` token "localstack" (Alternatives Considered): matching
# only the precise documented selection keeps the gate surgical. Compound or negated
# expressions (``-m "e2e or localstack"``, ``-m "not localstack"``) and the layer
# runners (``-m e2e`` / ``-m integration``, where a skipped optional AWS test is a
# normal, allowlisted outcome) are deliberately NOT matched, so this gate can never
# turn those supported runs red. A substring/regex match was rejected as too broad.
#
# WHY a call-phase execution counter rather than "were 3 skipped?" (Assumption): the
# module's endpoint precondition is enforced in a FIXTURE (setup phase), so a
# skipped OR failed AWS test produces no call-phase report at all. Counting only
# call-phase, non-skipped reports therefore cleanly distinguishes "green because the
# bodies ran and passed" (counter > 0 -> no gate) from "green because every body was
# skipped" (counter == 0 -> gate). Under pytest-xdist the controller receives every
# worker's report in pytest_runtest_logreport, so the controller's counter reflects
# the whole run.
_LOCALSTACK_MARKEXPR_TARGETED = False
_LOCALSTACK_EXECUTED = 0

# ---------------------------------------------------------------------------
# M3 -- bounded build stage.
# ---------------------------------------------------------------------------
# WHY (Trade-off): 600 s is generous for the ~13-program GnuCOBOL compile (which
# finishes in seconds on the reference runner) yet still finite, so a wedged
# compiler surfaces as an actionable timeout failure instead of hanging CI. The
# value is overridable via ``CARDDEMO_BUILD_TIMEOUT`` for slow/instrumented
# (``--coverage``) builds.
_BUILD_TIMEOUT_ENV = "CARDDEMO_BUILD_TIMEOUT"
_DEFAULT_BUILD_TIMEOUT = 600.0  # seconds
# Poll cadence while waiting on the build lock. WHY (Trade-off): 0.25 s is small
# enough that a waiting xdist worker starts almost immediately once the single
# builder releases the lock, but large enough not to busy-spin a core.
_LOCK_POLL_INTERVAL = 0.25  # seconds

# Names of the lock + completion-stamp files placed inside the build directory.
# WHY (Assumption): build/ is git-ignored (see .gitignore ``/build/``), so these
# coordination files are never committed; keeping them beside the artifacts they
# guard means every worker derives the same path from the same build_dir.
_BUILD_LOCK_NAME = ".build.lock"
_BUILD_STAMP_NAME = ".build.complete"

# ---------------------------------------------------------------------------
# M6 -- minimal build-subprocess environment (allowlist).
# ---------------------------------------------------------------------------
# WHY (Security -- least privilege): the compile child only needs a working
# toolchain (PATH/locale/tmp) plus the suite's own contract variables. Passing
# the FULL ambient environment would leak unrelated secrets -- AWS credentials,
# the LocalStack auth token, CI tokens -- into a process that has no use for
# them (M6). We therefore ALLOWLIST exactly the keys/prefixes the build needs
# and drop everything else. An allowlist (not a denylist) is chosen so a
# newly-introduced secret variable is excluded by DEFAULT rather than only if
# someone remembers to add it to a blocklist (Alternatives Considered).
_ENV_ALLOW_KEYS = frozenset(
    {
        "PATH",          # locate bash / cobc / cc -- without it the build cannot run
        "HOME",          # gcc/cobc read HOME for temp + config
        "PWD",
        "SHELL",
        "TERM",
        "USER",
        "LOGNAME",
        "TZ",
        "TMPDIR",        # gcc/cobc scratch space
        "TEMP",
        "TMP",
        "LANG",          # locale affects cobc diagnostics / text handling
        "LANGUAGE",
        "LC_ALL",
        "LC_CTYPE",
        "LC_MESSAGES",
        "LC_NUMERIC",
    }
)
# Prefix allowlist: the suite's own CARDDEMO_* contract, GnuCOBOL's COB_* runtime
# knobs, GCOV_* coverage controls, and the xdist worker/run identifiers (harmless
# identifiers, part of the parallel-run contract -- never secrets).
_ENV_ALLOW_PREFIXES = ("CARDDEMO_", "COB_", "GCOV_", "PYTEST_XDIST_")


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


def _is_truthy(value: "str | None") -> bool:
    """Interpret an environment-variable string as a boolean flag.

    Purpose
    -------
    Provide one shared, case-insensitive reading of the on/off flags this
    conftest honours (currently ``CARDDEMO_REQUIRE_COBOL``), so every call site
    agrees on exactly which spellings mean "enabled".

    Parameters
    ----------
    value : str | None
        The raw environment value (``os.environ.get(...)`` result), which may be
        ``None`` when the variable is unset.

    Returns
    -------
    bool
        ``True`` iff ``value`` -- lower-cased and stripped -- is one of
        ``{"1", "true", "yes", "on"}``; ``False`` otherwise (including ``None``).

    Raises
    ------
    None
    """
    # WHY (Convention + Trade-off): "1" is the canonical trigger that matches the
    # bash side (scripts/setup_localstack.sh tests `= "1"`); we ALSO accept
    # true/yes/on so a CI author who writes ``CARDDEMO_REQUIRE_COBOL=true`` is
    # not silently ignored. Accepting a small fixed set (rather than "any
    # non-empty string") avoids surprising activation from an accidental value.
    if value is None:
        return False
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _strict_cobol_required() -> bool:
    """Return whether an absent COBOL layer must HARD-FAIL rather than skip.

    Purpose
    -------
    Centralise the read of ``CARDDEMO_REQUIRE_COBOL`` so the "required layer"
    policy (QA finding M1) is evaluated identically everywhere.

    Parameters
    ----------
    None

    Returns
    -------
    bool
        ``True`` when the operator/CI demands the COBOL toolchain be present
        (missing -> failure); ``False`` for the default developer-friendly mode
        (missing -> clean skip).

    Raises
    ------
    None
    """
    return _is_truthy(os.environ.get(_STRICT_ENV))


def _require_or_skip(reason: str) -> "NoReturn":
    """Fail (strict mode) or skip (default) when a required dependency is absent.

    Purpose
    -------
    Implement the M1 contract in one place: a genuinely-absent COBOL dependency
    (compiler, build script, or helper module) becomes a HARD, report-visible
    FAILURE when ``CARDDEMO_REQUIRE_COBOL`` is set, and a clean skip otherwise.
    This is what stops a CI run from exiting green with an all-skipped report
    that hides a missing required layer.

    Parameters
    ----------
    reason : str
        Human-readable explanation of what is missing; surfaced verbatim in the
        pytest failure/skip message.

    Returns
    -------
    NoReturn
        Never returns normally -- always raises ``Failed`` (strict) or
        ``Skipped`` (default) via pytest.

    Raises
    ------
    Failed
        (via :func:`pytest.fail`) when strict mode is active.
    Skipped
        (via :func:`pytest.skip`) when strict mode is inactive.
    """
    if _strict_cobol_required():
        # ``pytrace=False``: the actionable signal is the missing-dependency
        # message, not a traceback into this helper. Failing (not skipping)
        # makes the absence show up as an ERROR-bearing JUnit case that the
        # runner scripts map to RC>=8 -- i.e. it can never masquerade as green.
        pytest.fail(
            f"{reason} (required because {_STRICT_ENV} is set).",
            pytrace=False,
        )
    pytest.skip(f"{reason}; skipping (set {_STRICT_ENV}=1 to require it).")


def _strict_localstack_required() -> bool:
    """Return whether an absent LocalStack layer must HARD-FAIL rather than skip.

    Purpose
    -------
    LocalStack peer of :func:`_strict_cobol_required`: centralise the read of
    ``CARDDEMO_REQUIRE_LOCALSTACK`` so the "required AWS layer" policy (QA finding
    F4) is evaluated identically wherever it is consulted.

    Parameters
    ----------
    None

    Returns
    -------
    bool
        ``True`` when the operator/CI demands a reachable LocalStack emulator
        (absent -> failure); ``False`` for the default developer-friendly mode
        (absent -> clean skip).

    Raises
    ------
    None
    """
    return _is_truthy(os.environ.get(_STRICT_LOCALSTACK_ENV))


def _require_localstack_or_skip(reason: str) -> "NoReturn":
    """Fail (strict) or skip (default) when the LocalStack layer is unavailable.

    Purpose
    -------
    LocalStack peer of :func:`_require_or_skip`, implementing the F4 contract in
    one place: an absent/unreachable S3 emulator becomes a HARD, report-visible
    FAILURE when ``CARDDEMO_REQUIRE_LOCALSTACK`` is set, and a clean skip
    otherwise. This is what stops a ``--with-localstack`` CI run from exiting
    green with an all-skipped report that hides a missing required layer.

    Parameters
    ----------
    reason : str
        Human-readable explanation of what is missing/unreachable; surfaced
        verbatim in the pytest failure/skip message.

    Returns
    -------
    NoReturn
        Never returns normally -- always raises ``Failed`` (strict) or
        ``Skipped`` (default) via pytest.

    Raises
    ------
    Failed
        (via :func:`pytest.fail`) when strict mode is active.
    Skipped
        (via :func:`pytest.skip`) when strict mode is inactive.
    """
    # WHY (finding F4): identical fail-vs-skip mechanics to the COBOL gate, but
    # keyed on the LocalStack flag. Failing (not skipping) under REQUIRE makes the
    # absence an ERROR-bearing JUnit case the runner scripts map to RC>=8, so it
    # can never masquerade as green. pytrace=False keeps the actionable
    # missing-emulator message front-and-centre instead of a helper traceback.
    if _strict_localstack_required():
        pytest.fail(
            f"{reason} (required because {_STRICT_LOCALSTACK_ENV} is set).",
            pytrace=False,
        )
    pytest.skip(
        f"{reason}; skipping (set {_STRICT_LOCALSTACK_ENV}=1 to require it)."
    )


def _validate_repo_root() -> Path:
    """Resolve the repository root from a TRUSTED anchor, validating any override.

    Purpose
    -------
    Anchor the suite to the repository that physically contains THIS file, and
    reject any ``CARDDEMO_REPO_ROOT`` environment override that would redirect
    execution to a different tree (QA finding M6 -- "arbitrary roots accepted").

    Parameters
    ----------
    None

    Returns
    -------
    pathlib.Path
        The resolved (absolute, symlink-free) repository root.

    Raises
    ------
    Failed
        (via :func:`pytest.fail`) if ``CARDDEMO_REPO_ROOT`` is set but does not
        resolve to the same directory as the trusted ``__file__``-derived anchor,
        or does not look like the CardDemo repository (missing marker dirs).
    """
    # The trusted anchor cannot be spoofed by the environment: it is derived from
    # the on-disk location of this very module (``<repo>/tests/conftest.py`` ->
    # grandparent == repo root). WHY (Assumption): conftest.py's position in the
    # tree is fixed by the repository layout, so its grandparent is authoritative.
    trusted = _REPO_ROOT
    override = os.environ.get("CARDDEMO_REPO_ROOT")
    if override:
        candidate = Path(override).resolve()
        # WHY (Security -- enforce a CANONICAL root, not merely a plausible one):
        # we require the override to resolve to the SAME directory as the trusted
        # anchor. Honouring a divergent value would let an attacker (or a
        # misconfiguration) point the whole harness -- builds, ASSIGN bindings,
        # golden roots -- at an arbitrary tree. Because scripts/test_env.sh
        # derives CARDDEMO_REPO_ROOT as the parent of scripts/ (identical to this
        # anchor after ``.resolve()``), the legitimate runner flow always passes;
        # only a redirecting value is rejected.
        if candidate != trusted:
            pytest.fail(
                "CARDDEMO_REPO_ROOT="
                f"{override!r} resolves to {candidate}, which is not this test "
                f"tree's repository root ({trusted}). Refusing to run against a "
                "different tree (security containment, QA finding M6).",
                pytrace=False,
            )
        # A resolved-equal override is redundant but harmless; validate markers
        # below against the trusted anchor regardless of which value we started
        # from, so a corrupted/partial checkout is caught early.
    # Validate the repository "shape" so we fail fast with a clear message rather
    # than deep inside a build/run against a tree that is not CardDemo at all.
    # WHY (Assumption): these three directories are invariant landmarks of the
    # repository (the units under test, their copybooks/scripts, and this test
    # tree); their joint presence is a cheap, reliable identity check.
    for marker in ("scripts", "tests", os.path.join("app", "cbl")):
        if not (trusted / marker).is_dir():
            pytest.fail(
                f"repository root {trusted} is missing expected directory "
                f"{marker!r}; refusing to run against an unrecognised tree "
                "(QA finding M6).",
                pytrace=False,
            )
    return trusted


def _contained_under(child: "str | os.PathLike[str]", parent: Path, *, label: str) -> Path:
    """Resolve ``child`` and require it to live inside ``parent``; else fail.

    Purpose
    -------
    Enforce the M6 containment allowlist for operator-supplied output locations
    (``CARDDEMO_BUILD_DIR`` / ``CARDDEMO_REPORTS_DIR``): a path override is only
    honoured when it resolves to somewhere under the (already validated)
    repository root, so a crafted ``..``/absolute value cannot make the harness
    write outside the repo.

    Parameters
    ----------
    child : str | os.PathLike[str]
        The candidate path (typically an environment override or a default built
        from ``parent``).
    parent : pathlib.Path
        The containing directory the child must resolve under (the repo root).
    label : str
        Short name of the setting being validated (e.g. ``"CARDDEMO_BUILD_DIR"``),
        used only to make the failure message actionable.

    Returns
    -------
    pathlib.Path
        The resolved, contained child path.

    Raises
    ------
    Failed
        (via :func:`pytest.fail`) if the resolved child escapes ``parent``.
    """
    resolved = Path(child).resolve()
    # WHY (Refactoring rationale): compare on the RESOLVED paths so that symlinks
    # and ``..`` segments are collapsed BEFORE the containment test -- a lexical
    # (string-prefix) check on the raw value could be fooled by ``<repo>/../evil``
    # or a symlink, whereas ``.resolve()`` + ``is_relative_to`` reasons about the
    # real target. ``is_relative_to`` (Python 3.9+) is the intent-revealing form
    # of "is parent an ancestor of resolved?".
    if resolved != parent and not resolved.is_relative_to(parent):
        pytest.fail(
            f"{label}={str(child)!r} resolves to {resolved}, which is outside "
            f"the repository root {parent}. Refusing an out-of-tree location "
            "(security containment, QA finding M6).",
            pytrace=False,
        )
    return resolved


def _run_token() -> str:
    """Return a token that is STABLE across the workers of one pytest run.

    Purpose
    -------
    Provide the identity written into the build-completion stamp so the one-time
    compile can be shared across ``pytest-xdist`` workers (build ONCE per run,
    not once per worker) yet still be rebuilt on a subsequent, independent
    invocation (QA finding M3).

    Parameters
    ----------
    None

    Returns
    -------
    str
        ``PYTEST_XDIST_TESTRUNUID`` when running under xdist (identical for every
        worker of the same run), else a process-local token. The non-xdist
        fallback is sufficient because, without xdist, the session-scoped build
        fixture executes exactly once in a single process anyway.

    Raises
    ------
    None
    """
    # WHY (Assumption -- verified empirically): xdist exports
    # PYTEST_XDIST_TESTRUNUID with the SAME value to the controller and every
    # worker of a run, so keying the stamp on it makes all workers agree "this
    # run already built". Absent xdist the variable is unset; a per-process token
    # (pid) is a correct fallback because the single process builds once and a
    # later invocation gets a new pid -> a fresh rebuild (no stale reuse).
    return os.environ.get("PYTEST_XDIST_TESTRUNUID") or f"pid-{os.getpid()}"


def _minimal_build_env(repo_root: Path, build_dir: Path) -> "dict[str, str]":
    """Build a MINIMAL, curated environment for the compile subprocess.

    Purpose
    -------
    Hand ``scripts/build_test_programs.sh`` only the variables a COBOL compile
    legitimately needs, dropping unrelated ambient secrets (AWS credentials,
    LocalStack token, CI tokens) so they never leak into the child (QA finding
    M6). The suite's own ``CARDDEMO_REPO_ROOT`` / ``CARDDEMO_BUILD_DIR`` are then
    pinned to the VALIDATED values so the child cannot be steered by a hostile
    ambient copy of them.

    Parameters
    ----------
    repo_root : pathlib.Path
        The validated repository root, pinned into ``CARDDEMO_REPO_ROOT``.
    build_dir : pathlib.Path
        The validated, contained build directory, pinned into
        ``CARDDEMO_BUILD_DIR``.

    Returns
    -------
    dict[str, str]
        A fresh environment mapping suitable for ``subprocess.run(env=...)``.

    Raises
    ------
    None
    """
    env: "dict[str, str]" = {}
    for key, value in os.environ.items():
        # WHY (Security -- allowlist, not denylist): admit a variable only if it
        # is an explicitly-approved key OR carries an approved prefix. Anything
        # else (an unknown, possibly-secret variable) is excluded by default.
        if key in _ENV_ALLOW_KEYS or key.startswith(_ENV_ALLOW_PREFIXES):
            env[key] = value
    # Pin the two contract variables to the validated paths so the sourced
    # test_env.sh honours OUR root/build dir rather than any ambient override
    # that survived the allowlist (CARDDEMO_* is allow-prefixed, so a hostile
    # value could otherwise ride along -- we overwrite it here to be safe).
    env["CARDDEMO_REPO_ROOT"] = str(repo_root)
    env["CARDDEMO_BUILD_DIR"] = str(build_dir)
    return env


@contextlib.contextmanager
def _build_lock(lock_path: Path, timeout: float) -> "Iterator[None]":
    """Hold an exclusive, cross-process advisory lock for the duration of a block.

    Purpose
    -------
    Serialise the one-time compile so that, under ``pytest-xdist``, at most one
    worker builds at a time (QA finding M3 -- "repeated unlocked builds"). While
    the single builder holds the lock, every other worker BLOCKS here, so no
    consumer ever observes a half-written build directory -- the build is atomic
    from a reader's point of view.

    Parameters
    ----------
    lock_path : pathlib.Path
        Filesystem path of the lock file. Its parent directory must already
        exist; the file itself is created if absent.
    timeout : float
        Maximum seconds to wait to acquire the lock before giving up. Bounds the
        wait so a wedged builder cannot hang the waiters forever (M3 -- "bounded
        stages").

    Yields
    ------
    None
        Control is yielded to the ``with`` body only once the lock is held.

    Raises
    ------
    TimeoutError
        If the lock cannot be acquired within ``timeout`` seconds.
    OSError
        If the lock file cannot be opened for a reason other than contention.
    """
    # WHY (Alternatives Considered): the ``filelock`` package is the usual choice
    # but is NOT installed in this environment, so we use stdlib ``fcntl.flock``
    # directly. flock gives a robust cross-process advisory lock on Linux (the
    # documented runner OS) and is released automatically if the holder dies,
    # which prevents a crashed builder from deadlocking the rest of the run.
    fd = os.open(str(lock_path), os.O_RDWR | os.O_CREAT, 0o644)
    deadline = time.monotonic() + timeout
    try:
        while True:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break  # lock acquired
            except OSError as exc:
                # EAGAIN/EWOULDBLOCK/EACCES mean "held by someone else" -> wait
                # and retry until the deadline. Any other errno is a real fault.
                if exc.errno not in (errno.EAGAIN, errno.EWOULDBLOCK, errno.EACCES):
                    raise
                if time.monotonic() >= deadline:
                    raise TimeoutError(
                        f"could not acquire build lock {lock_path} within "
                        f"{timeout:.0f}s"
                    ) from exc
                time.sleep(_LOCK_POLL_INTERVAL)
        yield
    finally:
        # WHY (Refactoring rationale): unlock explicitly BEFORE closing so the
        # release is deterministic even if a later close were to be delayed, then
        # always close the descriptor to avoid an fd leak across the session.
        try:
            fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)


def compile_helper_program(
    build_dir: "str | os.PathLike[str]",
    name: str,
    source_text: str,
    *,
    compile_timeout: float = 120.0,
    lock_timeout: float = _DEFAULT_BUILD_TIMEOUT,
) -> Path:
    """Compile an inline COBOL helper program ONCE per run, race-free under xdist.

    Purpose
    -------
    Build a small, test-owned COBOL helper (e.g. the ``LDXREFA`` alternate-key
    XREF loader the e2e cycle tests need) into the SHARED session build directory
    without the ``pytest-xdist`` workers racing on it. Each e2e cycle test used to
    run an unlocked ``if not exe.exists(): write <name>.cbl; cobc -o exe`` against
    the ONE shared ``build_dir/<name>`` path; under ``-n`` that check-then-build
    (TOCTOU) let two workers write the same source and compile onto the same binary
    at once, yielding ``ETXTBSY`` ("text file busy"), a ``PermissionError``, or a
    half-written executable a third worker then tried to run (QA finding
    F-XDIST-LDXREFA-RACE). This helper serialises the build behind the same kind of
    cross-process lock the app-program build uses and publishes the result
    atomically, so the program is compiled exactly once and never observed partial.

    Parameters
    ----------
    build_dir : str | os.PathLike[str]
        The shared session build directory. The published binary lands at
        ``build_dir/<name>``; a dedicated lock file lives at
        ``build_dir/.<name>.build.lock``.
    name : str
        Program name, also used as the published executable's filename.
    source_text : str
        Complete free-format (``-free``) COBOL source for the helper program.
    compile_timeout : float, optional
        Maximum seconds for the ``cobc`` compile (default ``120.0``), so a wedged
        compiler surfaces as a bounded failure instead of hanging CI.
    lock_timeout : float, optional
        Maximum seconds to wait for the shared build lock (default
        ``_DEFAULT_BUILD_TIMEOUT`` == 600.0).

    Returns
    -------
    pathlib.Path
        Path of the compiled, ready-to-run executable (``build_dir/<name>``).

    Raises
    ------
    subprocess.CalledProcessError
        If ``cobc`` fails; its captured ``stdout``/``stderr`` is attached, exactly
        as the previous inline ``check=True`` compile did.
    subprocess.TimeoutExpired
        If the compile exceeds ``compile_timeout`` seconds.
    TimeoutError
        If the build lock cannot be acquired within ``lock_timeout`` seconds.
    """
    # WHY reuse the same flock discipline the app-program build uses (Refactoring
    # rationale): serialising through a dedicated lock file makes the
    # check-then-build atomic across workers, so ``<name>`` is compiled exactly ONCE
    # per run and every other worker simply reuses the finished binary. A SEPARATE
    # lock file (not the app-build lock) is used deliberately so this never contends
    # with ``built_programs`` -- which has already released its lock before these
    # e2e tests run -- keeping the two build stages independent.
    bdir = Path(build_dir)
    exe = bdir / name
    lock_path = bdir / f".{name}.build.lock"
    with _build_lock(lock_path, lock_timeout):
        if exe.exists():
            # Built earlier this run (by us, or by another worker that won the lock
            # first); reuse it rather than recompiling.
            return exe
        # WHY compile to a UNIQUE temp then ``os.replace`` (atomic publish,
        # Trade-off): even with the lock held, publishing via an atomic rename means
        # no reader/executor can EVER observe a partially written binary, and because
        # we never write directly onto ``exe`` a concurrently-executing stale copy
        # from a crashed prior run cannot trigger ``ETXTBSY``. The unique token keeps
        # a previous aborted attempt's leftovers from colliding with this one.
        #
        # WHY the SOURCE keeps a STABLE, clean base name while only the OUTPUT is
        # uniquified (Assumption, verified empirically): ``cobc`` derives the module
        # name from the SOURCE file's base name and rejects names that are over-long
        # or dot-prefixed ("invalid file base name ... length exceeds maximum"), so a
        # ``uuid``-laden ``.cbl`` name will not compile. Writing the source as the
        # plain ``<name>.cbl`` is safe here precisely because we hold the exclusive
        # build lock -- no other worker can be writing the same source concurrently --
        # so a unique source name is neither needed nor accepted by the compiler. The
        # output path carries no such constraint, so uniqueness lives there.
        token = f"{os.getpid()}-{uuid.uuid4().hex}"
        src_path = bdir / f"{name}.cbl"
        tmp_exe = bdir / f".{name}.{token}.tmp"
        src_path.write_text(source_text)
        try:
            subprocess.run(
                ["cobc", "-x", "-free", "-o", str(tmp_exe), str(src_path)],
                check=True,
                capture_output=True,
                timeout=compile_timeout,
            )
            os.replace(tmp_exe, exe)
        finally:
            # Best-effort temp cleanup that never masks a compile error: the ``.cbl``
            # is always removable, and ``tmp_exe`` no longer exists after a successful
            # ``os.replace`` (hence FileNotFoundError, an OSError, is tolerated).
            for leftover in (src_path, tmp_exe):
                try:
                    leftover.unlink()
                except OSError:
                    pass
    return exe


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

    # WHY (Assumption -- defensive reset): each pytest run is normally a fresh
    # process, so the module-global "no hidden skips" accumulator starts empty. But
    # a caller that invokes ``pytest.main()`` more than once in the same interpreter
    # would otherwise carry a prior run's unexpected-skip records into the next
    # session. Clearing at configure time makes the gate correct even in that reuse
    # case, at zero cost to the common one-process-per-run path.
    _UNEXPECTED_SKIPS.clear()

    # WHY (F-DOC-LOCALSTACK-COMMAND-GREEN-SKIP): capture, once per session, whether
    # THIS run explicitly targets ONLY the LocalStack layer (the documented
    # ``pytest -m localstack``), and reset the per-session execution counter. Reading
    # the ``-m`` expression here (configure time) makes the later sessionfinish gate a
    # pure check of already-captured state, and the reset keeps the module-globals
    # correct even if a caller drives ``pytest.main()`` more than once in one
    # interpreter. The empty-string default is belt-and-suspenders: ``markexpr`` is a
    # core pytest option that always exists, defaulting to "" when no ``-m`` is given.
    global _LOCALSTACK_MARKEXPR_TARGETED, _LOCALSTACK_EXECUTED
    _LOCALSTACK_MARKEXPR_TARGETED = (config.getoption("markexpr", "") or "").strip() == "localstack"
    _LOCALSTACK_EXECUTED = 0


def _skip_reason_text(report: "pytest.TestReport") -> str:
    """Extract a human-readable skip reason from a pytest ``TestReport``.

    Purpose
    -------
    Normalise the several shapes ``report.longrepr`` can take for a skipped test
    into one reason string the "no hidden skips" gate can pattern-match against its
    allowlist (see :data:`_SKIP_ALLOWLIST`).

    Parameters
    ----------
    report : pytest.TestReport
        A test report whose ``skipped`` flag is set. For a ``pytest.skip(msg)`` the
        ``longrepr`` is typically a ``(path, lineno, "Skipped: <msg>")`` tuple; other
        outcomes may carry a plain string or an object.

    Returns
    -------
    str
        The skip message with any single leading ``"Skipped: "`` marker removed; an
        empty string when no reason can be recovered.

    Raises
    ------
    None
        Extraction is best-effort and never raises -- an unrecognised ``longrepr``
        shape falls back to ``str(longrepr)`` so the caller still sees the skip.
    """
    longrepr = getattr(report, "longrepr", None)
    if longrepr is None:
        return ""
    # WHY (Assumption): pytest packs a skip as a 3-tuple (path, lineno, message) and
    # only the message is meaningful to the allowlist. Falling back to ``str()`` for
    # any other shape keeps this robust to future/edge longrepr forms rather than
    # raising and masking the real signal (the skip itself).
    if isinstance(longrepr, tuple) and len(longrepr) == 3:
        text = str(longrepr[2])
    else:
        text = str(longrepr)
    # A leading "Skipped: " is noise pytest prepends when reporting; strip exactly
    # one occurrence so an allowlist reason-fragment matches the message a test
    # author actually wrote.
    marker = "Skipped: "
    if text.startswith(marker):
        text = text[len(marker):]
    return text


def _skip_is_allowlisted(nodeid: str, reason: str) -> bool:
    """Return whether a genuine skip is one of the documented, expected skips.

    Purpose
    -------
    Decide if a skipped test matches an entry in :data:`_SKIP_ALLOWLIST`, so the
    "no hidden skips" gate permits the small documented set (the export/import
    compile defect; the opt-in LocalStack layer) while flagging every other skip as
    unexpected.

    Parameters
    ----------
    nodeid : str
        The skipped test's node id (e.g.
        ``tests/integration/test_export_import.py::test_export_import_roundtrip``).
    reason : str
        The extracted skip reason (see :func:`_skip_reason_text`).

    Returns
    -------
    bool
        ``True`` iff some allowlist entry's module fragment appears in ``nodeid`` AND
        (that entry's reason fragment is ``None`` OR appears in ``reason``); else
        ``False``.

    Raises
    ------
    None
    """
    for module_fragment, reason_fragment in _SKIP_ALLOWLIST:
        if module_fragment in nodeid and (reason_fragment is None or reason_fragment in reason):
            return True
    return False


def pytest_runtest_logreport(report: "pytest.TestReport") -> None:
    """Record any UNEXPECTED genuine skip for the end-of-session "no hidden skips" gate.

    Purpose
    -------
    Observe every test outcome and remember the ones that are genuine
    ``pytest.skip()`` skips OUTSIDE the documented allowlist, so
    :func:`pytest_sessionfinish` can fail the run. Implements the QA "no hidden
    skips" contract (an unexpected skip in a required test must not pass CI green).

    Parameters
    ----------
    report : pytest.TestReport
        The per-phase report pytest emits for setup/call/teardown of each test.
        Under ``pytest-xdist`` this fires on the CONTROLLER for every worker's
        forwarded report, so the controller observes the whole run.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    # WHY count BEFORE the cobc guard below (Assumption): the LocalStack layer is
    # pure-Python and does not need the COBOL toolchain, so its zero-executed gate
    # must work even on a box without ``cobc``. We tally executed AWS test bodies
    # here, ahead of the compiler-gated no-hidden-skips logic. Only call-phase,
    # non-skipped reports count as an executed body (setup-phase skips/failures from
    # the endpoint fixture never reach the call phase), and only when this run
    # explicitly targets the localstack layer -- so the counter is zero-cost otherwise.
    global _LOCALSTACK_EXECUTED
    if _LOCALSTACK_MARKEXPR_TARGETED and report.when == "call" and not report.skipped:
        _LOCALSTACK_EXECUTED += 1

    # WHY enforce only when the toolchain is PRESENT (Assumption + Trade-off): when
    # ``cobc`` is absent this suite is in its documented developer-friendly degraded
    # mode, where COBOL-dependent tests skip and a green run is intentional (the M1
    # gate's "skip by default"). Enforcing "no unexpected skips" there would turn that
    # SUPPORTED mode red. When ``cobc`` IS present (CI installs it; a proper dev box
    # has it) every COBOL test is expected to run, so a skip outside the allowlist is
    # genuinely unexpected and must fail. This keeps the gate strict exactly where it
    # matters without regressing the no-compiler mode.
    if shutil.which("cobc") is None:
        return
    # Only GENUINE skips count. An xfail is ALSO reported with ``skipped`` True but
    # carries a ``wasxfail`` attribute; it is a distinct expected-failure outcome
    # governed by ``xfail_strict`` (pytest.ini) and must NOT be treated as a hidden
    # skip here.
    if not report.skipped or hasattr(report, "wasxfail"):
        return
    reason = _skip_reason_text(report)
    if _skip_is_allowlisted(report.nodeid, reason):
        return
    # Keyed by nodeid so a test that skips (one skipped report) is recorded once; a
    # later phase's report for the same test cannot inflate the count.
    _UNEXPECTED_SKIPS[report.nodeid] = reason


def _augment_junit_with_skip_failures(
    xml_path: "str | os.PathLike[str]", skips: "dict[str, str]"
) -> bool:
    """Inject a synthetic failing ``<testcase>`` into a JUnit report for hidden skips.

    Purpose
    -------
    Make the machine-readable JUnit XML itself agree with the no-hidden-skips
    verdict. :func:`pytest_sessionfinish` escalates the *process* exit code when an
    unexpected skip is found, but pytest's own JUnit writer records those tests as
    ``<skipped>`` -- leaving ``failures=0 errors=0`` in the file. A CI publisher that
    keys ONLY on the XML (ignoring the exit code) would therefore read an aborted
    run as green. This helper rewrites the report so it carries a real
    ``<failure>``, closing that "skipped-only green" gap for every pure-XML consumer.

    Parameters
    ----------
    xml_path : str | os.PathLike[str]
        Filesystem path of the JUnit report pytest already wrote (the value of
        ``--junitxml``). A missing/unparseable file is a no-op.
    skips : dict[str, str]
        Mapping of ``nodeid -> skip-reason`` for every unexpected skip; used to
        build a deterministic, auditable failure message naming each offender.

    Returns
    -------
    bool
        ``True`` if the report was augmented and rewritten; ``False`` on any no-op
        (no path, file absent, unparseable, or no ``<testsuite>`` present).

    Raises
    ------
    None
        All I/O and parse errors are swallowed and reported as ``False`` so this
        never turns a real test verdict into a harness crash at unconfigure time.
    """
    if not xml_path or not skips:
        return False
    path = Path(xml_path)
    if not path.is_file():
        return False
    # WHY parse-and-rewrite with the stdlib ElementTree rather than a text/regex
    # splice (Alternatives Considered): the counts on <testsuite> must stay
    # internally consistent with the node we add, and a structural edit guarantees
    # that far more safely than string surgery on attribute lists. ElementTree is
    # stdlib (no new dependency) and pytest's JUnit is small, so the parse cost is
    # negligible. A malformed report (should never happen for a pytest-written
    # file) degrades to a no-op rather than raising.
    try:
        tree = ET.parse(path)
    except ET.ParseError:
        return False
    root = tree.getroot()
    # pytest emits <testsuites><testsuite .../></testsuites>; tolerate a bare
    # <testsuite> root defensively so the helper is robust to writer variations.
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    if not suites:
        return False
    suite = suites[0]
    # WHY a single fixed testcase carrying ALL offenders (Trade-off): one node with
    # a sorted, newline-joined body keeps the message deterministic and the count
    # bookkeeping trivial, while still naming every hidden skip for the audit trail.
    detail = "\n".join(
        f"{nodeid}: {(reason or '').strip() or '(no reason given)'}"
        for nodeid, reason in sorted(skips.items())
    )
    message = (
        f"{len(skips)} unexpected skip(s) outside the documented allowlist; "
        "a genuine pytest.skip() in a required test must not pass CI green"
    )
    # ``time="0"`` (not a wall-clock value) so the injected node is itself
    # deterministic and survives normalization unchanged.
    testcase = ET.SubElement(
        suite,
        "testcase",
        {
            "classname": "carddemo.no_hidden_skips_gate",
            "name": "no_unexpected_skips",
            "time": "0",
        },
    )
    failure = ET.SubElement(testcase, "failure", {"message": message})
    failure.text = detail
    # Keep the suite header counts truthful: one more test, one more failure. A
    # non-numeric attribute (never produced by pytest) falls back to a safe value
    # rather than raising.
    for attr, base in (("tests", suite.get("tests")), ("failures", suite.get("failures"))):
        try:
            suite.set(attr, str(int(base) + 1))
        except (TypeError, ValueError):
            suite.set(attr, "1")
    # If the <testsuites> wrapper carries aggregate counts (some writers do), keep
    # them consistent too; pytest's wrapper usually omits them, hence the guard.
    if root is not suite and root.tag == "testsuites":
        for attr in ("tests", "failures"):
            if root.get(attr) is not None:
                try:
                    root.set(attr, str(int(root.get(attr)) + 1))
                except (TypeError, ValueError):
                    pass
    # WHY re-emit the declaration by hand (Trade-off): ElementTree.write's
    # xml_declaration uses single quotes; matching pytest's double-quoted UTF-8
    # declaration keeps the file visually consistent with the untouched-report case.
    try:
        body = ET.tostring(root, encoding="unicode")
        path.write_text('<?xml version="1.0" encoding="utf-8"?>\n' + body, encoding="utf-8")
    except OSError:
        return False
    return True


def pytest_sessionfinish(session: "pytest.Session", exitstatus: int) -> None:
    """Fail the session if any UNEXPECTED skip was observed (no-hidden-skips gate).

    Purpose
    -------
    Turn the accumulated set of unexpected genuine skips into a hard, auditable
    session failure so a silently-disabled required test can never leave the
    layer/master/CI status green. Only ESCALATES an otherwise-passing status; a run
    that is already failing keeps its (at-least-as-severe) status.

    Parameters
    ----------
    session : pytest.Session
        The finishing session; its ``exitstatus`` is the authoritative value the
        runner scripts map onto the CardDemo RC rubric.
    exitstatus : int
        The exit status pytest computed from test outcomes. Read for context; the
        gate mutates ``session.exitstatus`` (which pytest honours as the final code)
        rather than this parameter.

    Returns
    -------
    None

    Raises
    ------
    None
        The gate reports via ``session.exitstatus`` + a stderr diagnostic, never by
        raising (an exception here is swallowed and would NOT set the exit code).
    """
    # WHY evaluate only on the CONTROLLER (Assumption): under ``pytest-xdist`` the
    # controller receives every worker's report in ``pytest_runtest_logreport`` and
    # its exit status is the one the runner observes; a worker (identified by the
    # ``workerinput`` config attribute) sees only its own subset and its exit code is
    # not the run's, so letting a worker escalate would be both incomplete and
    # ineffective. Without xdist there is no ``workerinput`` and this simply runs
    # once, in-process.
    if hasattr(session.config, "workerinput"):
        return

    # --- Zero-executed LocalStack gate (F-DOC-LOCALSTACK-COMMAND-GREEN-SKIP) ------
    # WHY fire ONLY from an otherwise-green status (Trade-off): the gate's sole job is
    # to stop a localstack-targeted run presenting "nothing ran" as success. If the
    # status is already non-OK (e.g. CARDDEMO_REQUIRE_LOCALSTACK=1 turned the absent
    # emulator into hard setup failures) the run is red for a stronger, correctly-named
    # reason -- we must neither mask nor duplicate it. The executed-body counter then
    # distinguishes a genuine green (bodies ran and passed -> counter > 0) from the
    # trap (every body skipped -> counter == 0). NO_TESTS_COLLECTED (a marker typo that
    # collected zero AWS tests) is green-equivalent here and is likewise escalated.
    # WHY exclude --collect-only (Assumption): a collection-only invocation runs no
    # bodies by design, so a zero counter there is expected, not a hidden skip.
    if (
        _LOCALSTACK_MARKEXPR_TARGETED
        and _LOCALSTACK_EXECUTED == 0
        and not session.config.getoption("collectonly", False)
        and session.exitstatus in (pytest.ExitCode.OK, pytest.ExitCode.NO_TESTS_COLLECTED)
    ):
        print(
            "\n".join(
                [
                    "",
                    "=============== ZERO-EXECUTED LOCALSTACK GATE FAILED ===============",
                    "A localstack-targeted run (`-m localstack`) executed ZERO AWS test",
                    "bodies -- every LocalStack test was skipped because the emulator was",
                    "absent/unreachable. 'Nothing ran' must not read as green.",
                    "To actually exercise the layer, either:",
                    "  * scripts/run_e2e_tests.sh --with-localstack   (brings the emulator up), or",
                    "  * CARDDEMO_REQUIRE_LOCALSTACK=1 pytest tests -m localstack   (needs an endpoint).",
                    "===================================================================",
                ]
            ),
            file=sys.stderr,
        )
        # TESTS_FAILED (1) maps to the runner FAIL(8) rubric via
        # scripts/test_env.sh's carddemo_rc_from_pytest, so the process is RED.
        session.exitstatus = pytest.ExitCode.TESTS_FAILED

    if not _UNEXPECTED_SKIPS:
        return
    # Emit a prominent, reproducible diagnostic to stderr so the failure is
    # actionable in the run log the runner scripts capture -- naming the offending
    # nodeid and reason for every unexpected skip.
    lines = [
        "",
        "=================== NO-HIDDEN-SKIPS GATE FAILED ===================",
        f"{len(_UNEXPECTED_SKIPS)} unexpected skip(s) detected outside the documented allowlist.",
        "A genuine pytest.skip() in a required test must not pass CI green.",
        "Allowlisted skips: export/import compile-defect; opt-in LocalStack layer.",
        "Offending skip(s):",
    ]
    for nodeid, reason in sorted(_UNEXPECTED_SKIPS.items()):
        lines.append(f"  - {nodeid}\n      reason: {reason.strip() or '(no reason given)'}")
    lines.append("===================================================================")
    print("\n".join(lines), file=sys.stderr)
    # WHY escalate ONLY from a passing status (Trade-off): if tests already failed
    # (``exitstatus`` != OK) the run is red for a stronger reason and we must neither
    # mask nor downgrade it; we only PROMOTE an otherwise-green run to failed so an
    # unexpected skip cannot hide behind a ``tests=N failures=0`` report.
    # ``TESTS_FAILED`` (1) maps to the runner's FAIL(8) rubric via
    # ``scripts/test_env.sh``'s ``carddemo_rc_from_pytest``.
    if session.exitstatus == pytest.ExitCode.OK:
        session.exitstatus = pytest.ExitCode.TESTS_FAILED
    # NB: the *process* exit code is now correct, but the JUnit file pytest already
    # wrote still records the offenders as <skipped>. The companion
    # ``pytest_unconfigure`` hook below rewrites that file so the XML is red too --
    # it runs LATER than this hook, after pytest's own JUnit writer has flushed.


def pytest_unconfigure(config: "pytest.Config") -> None:
    """Rewrite the JUnit report so hidden skips also show as a failure IN the XML.

    Purpose
    -------
    Second half of the no-hidden-skips gate. :func:`pytest_sessionfinish` fixes the
    exit *code*; this hook fixes the exit *report*. It fires during config teardown
    -- strictly AFTER pytest's ``LogXML.pytest_sessionfinish`` has written the
    ``--junitxml`` file -- which is the earliest point the finished report exists on
    disk and can be safely augmented with a synthetic failing ``<testcase>``.

    Parameters
    ----------
    config : pytest.Config
        The finishing session's config. Supplies ``option.xmlpath`` (the
        ``--junitxml`` destination, or ``None`` when unset) and, under
        ``pytest-xdist``, the ``workerinput`` marker used to run only on the
        controller.

    Returns
    -------
    None

    Raises
    ------
    None
        Delegates to :func:`_augment_junit_with_skip_failures`, which never raises.
    """
    # WHY unconfigure rather than converting pytest_sessionfinish into a
    # hookwrapper (Alternatives Considered): the JUnit file is written by pytest's
    # own sessionfinish hook, whose ordering relative to a plain sibling hook is not
    # guaranteed. ``pytest_unconfigure`` is guaranteed to run after the whole
    # session (including the JUnit flush) has finished, so it is the simplest place
    # that is reliably "after the file exists" -- and it leaves the existing,
    # audited exit-code escalation in pytest_sessionfinish completely untouched.
    # WHY controller-only (Assumption): mirrors pytest_sessionfinish -- the
    # controller both owns the single real JUnit file and accumulates every
    # worker's skips in ``_UNEXPECTED_SKIPS`` (workers write no JUnit and see only
    # their own subset), so augmenting anywhere else would be wrong or a no-op.
    if hasattr(config, "workerinput"):
        return
    if not _UNEXPECTED_SKIPS:
        return
    xml_path = getattr(config.option, "xmlpath", None)
    if not xml_path:
        # No --junitxml was requested (e.g. an ad-hoc `pytest` run): the process
        # exit code already carries the failure and there is no report to correct.
        return
    if _augment_junit_with_skip_failures(xml_path, _UNEXPECTED_SKIPS):
        print(
            f"[carddemo] injected a synthetic failing <testcase> into {xml_path} "
            f"for {len(_UNEXPECTED_SKIPS)} unexpected skip(s) so the JUnit report "
            "is not misread as green.",
            file=sys.stderr,
        )


@pytest.fixture(scope="session")
def localstack_gate():
    """Return the LocalStack "require-or-skip" gate as a callable.

    Purpose
    -------
    Expose the module-private :func:`_require_localstack_or_skip` policy to test
    modules OUTSIDE this conftest (notably
    ``tests/e2e/test_localstack_dataset_staging.py``) WITHOUT them importing a
    private helper. A test whose LocalStack precondition is unmet calls
    ``gate(reason)``; the call HARD-FAILS under ``CARDDEMO_REQUIRE_LOCALSTACK``
    and cleanly skips otherwise (QA finding F4).

    Parameters
    ----------
    None

    Returns
    -------
    Callable[[str], NoReturn]
        A one-argument callable ``gate(reason)`` that never returns normally --
        it raises pytest ``Failed`` (strict) or ``Skipped`` (default).

    Raises
    ------
    None
        Returning the callable cannot fail; the fail/skip happens only when a
        test invokes it.
    """
    # WHY (Alternatives Considered): returning the function OBJECT (rather than
    # having the fixture itself fail/skip) lets the CONSUMING test decide WHEN and
    # with WHAT reason to trip the gate -- e.g. "endpoint unset" vs. "endpoint
    # unreachable" produce distinct, actionable messages a bare fixture could not
    # carry. Session scope: the policy is process-wide and stateless, so one
    # shared callable suffices for the whole run.
    return _require_localstack_or_skip


@pytest.fixture(scope="session")
def repo_root() -> Path:
    """Absolute path to the CardDemo repository root.

    Purpose
    -------
    Provide the single anchor from which every other path fixture is derived.
    The root is taken from this file's own on-disk location (the trusted
    anchor); an operator-supplied ``CARDDEMO_REPO_ROOT`` is validated against
    that anchor rather than blindly trusted, so the environment cannot redirect
    the suite to an arbitrary tree (QA finding M6).

    Parameters
    ----------
    None

    Returns
    -------
    pathlib.Path
        The resolved (absolute, symlink-free) repository-root directory.

    Raises
    ------
    Failed
        (via :func:`pytest.fail`, from :func:`_validate_repo_root`) if a
        ``CARDDEMO_REPO_ROOT`` override diverges from the trusted anchor or the
        tree is missing its expected marker directories.
    """
    # WHY (Refactoring rationale): the previous body honoured CARDDEMO_REPO_ROOT
    # verbatim (``os.environ.get(..., _REPO_ROOT)``), which "accepted an
    # arbitrary root" -- exactly the M6 defect. All validation now lives in the
    # single testable helper so the security contract is exercised by unit tests
    # rather than hidden in a fixture body.
    return _validate_repo_root()


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
        The resolved build-directory path, guaranteed to be CONTAINED under
        ``repo_root``. Not necessarily created yet -- the build script and/or the
        ``built_programs`` fixture create it.

    Raises
    ------
    Failed
        (via :func:`pytest.fail`, from :func:`_contained_under`) if a
        ``CARDDEMO_BUILD_DIR`` override resolves outside the repository root.
    """
    # WHY (Assumption): mirror test_env.sh's ``CARDDEMO_BUILD_DIR:-<root>/build``
    # exactly so this fixture names the same directory the build script writes
    # to. Not created here on purpose: directory creation is the build step's
    # responsibility, and an empty build dir must not read as "already built".
    # WHY (Security, M6): an override is honoured only if it resolves UNDER the
    # repo root; a ``..``/absolute escape is rejected so the harness cannot be
    # induced to write build artifacts outside the tree.
    raw = os.environ.get("CARDDEMO_BUILD_DIR", repo_root / "build")
    return _contained_under(raw, repo_root, label="CARDDEMO_BUILD_DIR")


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
        The reports directory (CONTAINED under ``repo_root``), guaranteed to
        exist on return.

    Raises
    ------
    Failed
        (via :func:`pytest.fail`, from :func:`_contained_under`) if a
        ``CARDDEMO_REPORTS_DIR`` override resolves outside the repository root.
    OSError
        If the directory cannot be created (e.g. permission denied).
    """
    # WHY (Security, M6): validate/contain the (possibly overridden) location
    # BEFORE creating it, so a hostile ``CARDDEMO_REPORTS_DIR`` cannot cause an
    # mkdir outside the repository tree.
    raw = os.environ.get("CARDDEMO_REPORTS_DIR", repo_root / "reports")
    path = _contained_under(raw, repo_root, label="CARDDEMO_REPORTS_DIR")
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
    EXACTLY ONCE PER RUN -- even under ``pytest-xdist``, where each worker has its
    own session. A cross-process lock serialises the compile and a per-run stamp
    lets the first worker build while the rest reuse the artifacts, so the build
    never races or repeats once-per-worker (QA finding M3). The compile subprocess
    is bounded by a timeout and receives only a minimal, curated environment (QA
    finding M6). When the COBOL toolchain is genuinely absent the fixture skips by
    default but HARD-FAILS under ``CARDDEMO_REQUIRE_COBOL`` (QA finding M1).

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root; used as the subprocess working directory and to locate
        the build script.
    build_dir : pathlib.Path
        The (validated, contained) build directory returned to callers once the
        compile succeeds; also hosts the lock and completion-stamp files.

    Returns
    -------
    pathlib.Path
        The ``build_dir`` containing the freshly-compiled programs.

    Raises
    ------
    Skipped
        (via :func:`_require_or_skip`) if ``cobc`` is absent or the build script
        is missing AND ``CARDDEMO_REQUIRE_COBOL`` is not set -- COBOL-dependent
        tests are skipped, not failed.
    Failed
        (via :func:`pytest.fail`) if the toolchain is absent while
        ``CARDDEMO_REQUIRE_COBOL`` is set, if the build exceeds its timeout, or if
        the build script exits fatally (RC >= 8) / with an otherwise-unexpected
        code -- all real, actionable breakages.
    """
    # WHY (M1): a missing compiler is an *environment* condition. By DEFAULT it is
    # a clean skip so the Python suite still collects and any non-COBOL portions
    # run on machines without GnuCOBOL. But when CARDDEMO_REQUIRE_COBOL is set
    # (CI), the same absence is a HARD FAILURE -- a required layer must never hide
    # behind a green-looking all-skipped report. Detection via shutil.which
    # matches the check the build script itself performs before it exits 8.
    if shutil.which("cobc") is None:
        _require_or_skip("GnuCOBOL 'cobc' not found on PATH")

    build_script = repo_root / "scripts" / "build_test_programs.sh"
    # WHY (M1): during a partial checkout the script may be absent; treat that
    # exactly like a missing compiler -- skip by default, fail when required --
    # instead of erroring on a FileNotFoundError from the subprocess.
    if not build_script.is_file():
        _require_or_skip(f"build script not found: {build_script}")

    # Ensure the build directory exists BEFORE we place the lock/stamp inside it.
    # WHY (Assumption): directory creation is normally the build step's job, but
    # the lock and completion stamp must live at a stable, agreed path that every
    # xdist worker can open, so we create the (already-validated, contained) dir
    # here. It stays empty of artifacts until the single builder populates it.
    build_dir.mkdir(parents=True, exist_ok=True)
    lock_path = build_dir / _BUILD_LOCK_NAME
    stamp_path = build_dir / _BUILD_STAMP_NAME
    token = _run_token()

    # Resolve the (overridable) build-stage timeout defensively. WHY (Trade-off):
    # a malformed CARDDEMO_BUILD_TIMEOUT should not crash collection with a
    # ValueError -- we fall back to the safe default so the suite still runs.
    try:
        build_timeout = float(os.environ.get(_BUILD_TIMEOUT_ENV, _DEFAULT_BUILD_TIMEOUT))
        if build_timeout <= 0:
            raise ValueError
    except (TypeError, ValueError):
        build_timeout = _DEFAULT_BUILD_TIMEOUT

    # A waiting worker must be willing to wait for the single builder to finish a
    # full compile, so the lock-acquisition budget is the build budget plus a
    # margin for stamp I/O. WHY (bounded stage, M3): still finite, so a wedged
    # builder surfaces as a TimeoutError rather than hanging every waiter forever.
    lock_timeout = build_timeout + 60.0

    try:
        with _build_lock(lock_path, lock_timeout):
            # Fast path: another worker in THIS run already built successfully.
            # WHY (build ONCE per run, M3): the stamp records the shared run token
            # (PYTEST_XDIST_TESTRUNUID); if it already carries our token the
            # artifacts are current for this run and we must NOT recompile. A
            # stamp from an earlier run (different token) is ignored so a fresh
            # invocation always rebuilds -- no stale reuse across runs.
            if stamp_path.is_file() and stamp_path.read_text(encoding="utf-8").strip() == token:
                return build_dir

            # Slow path: we are the elected builder for this run. Run the build
            # ONCE, under the lock, with a MINIMAL environment and a bounded
            # timeout. capture_output keeps compile noise out of pytest's streams
            # unless we surface the tail on failure; text=True yields str so
            # ``_tail`` can splitlines() directly.
            try:
                completed = subprocess.run(
                    ["bash", str(build_script)],
                    cwd=str(repo_root),
                    capture_output=True,
                    text=True,
                    timeout=build_timeout,
                    # WHY (M6 -- least privilege): a curated allowlist env, so no
                    # ambient AWS/LocalStack/CI secret leaks into the compiler
                    # child; CARDDEMO_REPO_ROOT/BUILD_DIR are pinned to the
                    # validated paths so the child cannot be redirected.
                    env=_minimal_build_env(repo_root, build_dir),
                )
            except subprocess.TimeoutExpired as exc:
                tail = _tail(exc.stderr) if isinstance(exc.stderr, str) else ""
                pytest.fail(
                    "scripts/build_test_programs.sh exceeded the "
                    f"{build_timeout:.0f}s build timeout ({_BUILD_TIMEOUT_ENV} to "
                    f"adjust). Last output:\n{tail or '(no output captured)'}",
                    pytrace=False,
                )

            rc = completed.returncode
            # WHY (Trade-off): treat RC >= 8 (fatal) OR any code that is not 0/4 as
            # a failure. RC == 4 (warn) is allowed through because it means an
            # optional / known-unsupported program (e.g. CBEXPORT/CBIMPORT) did not
            # compile while the core programs did -- the suite should still run. An
            # unexpected code such as 2 (usage) signals we invoked the script
            # wrongly, a real bug, hence excluded from (0, 4).
            if rc >= _RC_FAIL or rc not in (0, _RC_WARN):
                tail = _tail(completed.stderr) or _tail(completed.stdout) or "(no output captured)"
                # ``pytrace=False``: the traceback would point at this fixture,
                # not the COBOL compile error; the captured tail is the signal a
                # developer needs. We do NOT write the stamp on failure, so the
                # build is retried on the next attempt rather than falsely cached.
                pytest.fail(
                    f"scripts/build_test_programs.sh failed (exit {rc}). Last output:\n{tail}",
                    pytrace=False,
                )

            # Publish the completion stamp ATOMICALLY (write-temp-then-rename) so a
            # crash mid-write can never leave a half-written token that a later
            # reader would mistake for a valid one. WHY (M3 -- atomic publication):
            # os.replace is atomic on the same filesystem, so the stamp appears
            # complete-or-not-at-all to every subsequent worker.
            tmp_stamp = stamp_path.with_suffix(stamp_path.suffix + ".tmp")
            tmp_stamp.write_text(token, encoding="utf-8")
            os.replace(tmp_stamp, stamp_path)
            return build_dir
    except TimeoutError as exc:
        # Lock could not be acquired within the budget -- the elected builder is
        # wedged. Surface it as an actionable failure (bounded stage, M3).
        pytest.fail(str(exc), pytrace=False)


@pytest.fixture(scope="function")
def workspace(tmp_path: Path) -> "Iterator[Path]":
    """Fresh, isolated per-test working directory (with a ``data/`` subdir).

    Purpose
    -------
    Give each test its own scratch workspace so runs never share mutable state,
    and reclaim it on teardown so long parallel runs do not accumulate stale
    indexed/VSAM files (QA finding M3 -- "workspace leakage"). A ``data/``
    sub-directory is pre-created to match the layout the sibling scripts describe
    as ``CARDDEMO_DATA_DIR=<CARDDEMO_TEST_WORKSPACE>/data``, into which the
    harness binds every GnuCOBOL ASSIGN name.

    Parameters
    ----------
    tmp_path : pathlib.Path
        pytest's built-in per-test temporary-directory fixture; the source of
        isolation. Each test gets a distinct ``tmp_path`` (and, under xdist, each
        worker has its own base), so cleaning it here is always safe.

    Yields
    ------
    pathlib.Path
        The per-test workspace root (``tmp_path``); ``<workspace>/data`` exists.

    Raises
    ------
    OSError
        If the ``data/`` sub-directory cannot be created (during setup only;
        teardown never raises -- see below).
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
    yield tmp_path
    # WHY (M3 -- deterministic cleanup): pytest retains a few recent ``tmp_path``
    # trees for post-mortem debugging, which is fine for a handful of tests but
    # lets indexed-file fixtures pile up during a large parallel suite. We remove
    # this test's tree eagerly. ``ignore_errors=True`` (Trade-off) guarantees a
    # teardown hiccup -- e.g. a file still held on an exotic FS -- can never turn
    # a passing test red; reclaiming space is best-effort, correctness is not.
    shutil.rmtree(tmp_path, ignore_errors=True)


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
        (via :func:`_require_or_skip`) if ``tests.helpers.cobol_runner`` cannot
        be imported (e.g. a partial checkout without the helpers) AND
        ``CARDDEMO_REQUIRE_COBOL`` is not set -- so collection and the non-runner
        tests stay green.
    Failed
        (via :func:`_require_or_skip`) if the helper import fails while
        ``CARDDEMO_REQUIRE_COBOL`` is set -- the runner layer is required, so its
        absence must not hide behind a skip (QA finding M1).
    """
    # WHY (Trade-off): import LAZILY inside the fixture body, not at module top.
    # If the helpers module were imported at collection time, a partial checkout
    # missing tests/helpers/ would make the WHOLE conftest fail to load and every
    # test error out. Importing here degrades that failure mode to a clean
    # per-test skip (or, under CARDDEMO_REQUIRE_COBOL, a targeted failure) that
    # affects only tests actually requesting a runner.
    try:
        from tests.helpers.cobol_runner import CobolRunner
    except ImportError as exc:  # pragma: no cover - only hit on partial checkouts
        # WHY (M1): route through the same required-layer gate as the compiler so
        # an absent runner helper is graded consistently -- skip by default, fail
        # when the operator/CI demands the COBOL layer be present.
        _require_or_skip(f"tests.helpers.cobol_runner not available ({exc})")

    # WHY (Assumption): the constructor signature
    # ``CobolRunner(build_dir, workspace, reports_dir)`` is a hard contract
    # documented verbatim in that module's docstring; we pass positionally in
    # that exact order so the two files stay in lock-step and a future signature
    # change surfaces immediately here.
    return CobolRunner(built_programs, workspace, reports_dir)
