"""Compile-aware run wrapper for the CardDemo GnuCOBOL batch programs under test.

Purpose
-------
This module is the load-bearing bridge between the Python test layers
(``tests/integration`` and ``tests/e2e``) and the compiled GnuCOBOL batch
programs in ``build/``. It executes a compiled batch program against an
*isolated per-test workspace* and captures everything a financial-grade
assertion needs: the process ``RETURN-CODE``, the captured ``stdout``/``stderr``,
and the concrete on-disk paths of every output file the program may write.

A CardDemo batch program does not take its input/output file names as arguments.
Instead each ``SELECT ... ASSIGN TO <NAME>`` clause is bound by the GnuCOBOL
runtime to a **same-named environment variable** at run time (this is GnuCOBOL's
documented external-filename mechanism, and the convention that
``scripts/test_env.sh`` codifies for the whole suite). This wrapper therefore:

1. Points each of the 28 distinct ASSIGN external names at a file inside a
   per-test workspace (``<workspace>/data/<NAME>``), guaranteeing isolation.
2. Sets ``COB_LIBRARY_PATH`` to include the build directory so the dynamically
   ``CALL``'d shared modules (``CSUTLDTC``, ``CBSTM03B``, ``CBACT04C`` -- built
   as ``build/<NAME>.so`` by ``scripts/build_test_programs.sh``) resolve by
   ``PROGRAM-ID`` at run time.
3. Runs ``build/<PROG>`` to completion and wraps the outcome in a
   :class:`RunResult`.

It is imported two ways, both of which are hard contracts of the suite:

* ``from tests.helpers.cobol_runner import run_program`` -- a one-liner for tests
  (AAP Section 0.6.2 import contract).
* ``from tests.helpers.cobol_runner import CobolRunner`` -- the class the pending
  ``tests/conftest.py`` ``cobol_runner`` fixture instantiates as
  ``CobolRunner(build_dir, workspace, reports_dir)``.

There is **no** ``__init__.py`` anywhere under ``tests/`` -- the package resolves
as a PEP 420 namespace package via ``PYTHONPATH=<repo_root>``.

Design decisions (WHY)
----------------------
* **Copy the environment, never replace it.** :meth:`CobolRunner.build_env`
  starts from ``os.environ.copy()`` so the child keeps ``PATH``, locale, and the
  LocalStack/AWS variables the wider suite relies on; only the ASSIGN bindings
  and ``COB_LIBRARY_PATH`` are layered on top. Replacing the environment
  wholesale would break the very ``cobc`` runtime it is trying to launch.
* **Per-test workspace binding = isolation + parallel safety.** Every ASSIGN
  name is redirected into the injected ``workspace`` rather than the shared
  ``$CARDDEMO_DATA_DIR`` default from ``test_env.sh``. This is what lets the
  suite run under ``pytest-xdist -n auto`` without tests clobbering one
  another's VSAM files.
* **All 28 ASSIGN names are bound, and the near-synonyms are kept DISTINCT.**
  The same logical file is ASSIGNed under different external names by different
  programs -- the card cross-reference is ``XREFFILE`` in most programs but
  ``CARDXREF`` in ``CBTRN03C``; the transaction file is ``TRANFILE`` in the
  posting/daily programs but ``TRANSACT`` in ``CBACT04C``/``CBEXPORT``. Binding
  both members of each pair is required so every program resolves its files
  regardless of which external name it uses. (Assumption: GnuCOBOL resolves
  ASSIGN names case-sensitively via env vars, so a merged name would leave one
  program's file unbound.)
* **Standard library only; the indexed-file loader is imported lazily.** The
  module imports nothing outside the standard library at import time, and only
  reaches for :mod:`tests.helpers.vsam_loader` inside :meth:`CobolRunner.load_input`.
  (Trade-off: a test that exercises only sequential-file programs then needs
  neither the loader nor ``cobc``, keeping such tests fast and dependency-light.)
* **No floating point anywhere.** Return codes are integers and file contents
  are bytes/text; this module never introduces a ``float`` (a hard suite rule,
  because CardDemo is money-handling software where binary floats are forbidden).

Explainability
--------------
Per the project's mandatory Explainability rule, every public and private symbol
below carries a docstring stating Purpose / Parameters / Returns / Raises, and
each non-obvious decision is annotated with a WHY comment documenting at least
one of Alternatives Considered, Refactoring Rationale, Assumptions, or Trade-offs.
"""

from __future__ import annotations

import os
import re
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

# ---------------------------------------------------------------------------
# The canonical set of GnuCOBOL ASSIGN external names.
#
# Each entry is a `SELECT ... ASSIGN TO <NAME>` target verified across the batch
# programs in app/cbl/. The order and membership deliberately mirror
# scripts/test_env.sh (the single source of truth for the suite's env bindings)
# so the two never drift.
#
# WHY (Assumption): the four "near-synonym" names below are intentionally
# separate because different programs ASSIGN the same logical file under
# different external names:
#   * XREFFILE (CBACT03C/04C, CBTRN01C/02C, CBEXPORT, CBSTM03B) vs
#     CARDXREF (CBTRN03C)  -- both name the card cross-reference file.
#   * TRANFILE (CBTRN01C/02C/03C) vs TRANSACT (CBACT04C, CBEXPORT) -- both name
#     the transaction file.
# Binding every distinct name guarantees each program resolves its files, so
# merging any pair is explicitly forbidden.
# ---------------------------------------------------------------------------
ASSIGN_NAMES: "tuple[str, ...]" = (
    # Master / account domain (CBACT01-04C, CBCUS01C, CBACT04C lookups).
    "ACCTFILE",
    "CARDFILE",
    "XREFFILE",
    "CUSTFILE",
    "DISCGRP",
    "TCATBALF",
    "TRANSACT",
    # CBACT01C print / extra sequential outputs.
    "OUTFILE",
    "ARRYFILE",
    "VBRCFILE",
    # Daily posting domain (CBTRN01C / CBTRN02C).
    "DALYTRAN",
    "TRANFILE",
    "DALYREJS",
    # Transaction reporting domain (CBTRN03C).
    "CARDXREF",
    "TRANTYPE",
    "TRANCATG",
    "TRANREPT",
    "DATEPARM",
    # Statement domain (CBSTM03A / CBSTM03B).
    "STMTFILE",
    "HTMLFILE",
    "TRNXFILE",
    # Export / import domain (CBEXPORT / CBIMPORT).
    "EXPFILE",
    "CUSTOUT",
    "ACCTOUT",
    "XREFOUT",
    "TRNXOUT",
    "CARDOUT",
    "ERROUT",
)

# The name of the GnuCOBOL runtime variable used to locate dynamically CALL'd
# shared modules. Named once here so the intent is documented in a single place.
_COB_LIBRARY_PATH = "COB_LIBRARY_PATH"

# The workspace sub-directory that holds the ASSIGN-name data files, mirroring
# test_env.sh's `CARDDEMO_DATA_DIR=<workspace>/data`.
_DATA_SUBDIR = "data"

# Whitelist of characters permitted in an ASSIGN name bound by :meth:`CobolRunner.assign_path`.
# WHY (QA finding i1 -- Security/hardening): assign_path builds ``<workspace>/data/<name>``,
# so a name that is a path component ('..'), an absolute path, or contains a separator could
# bind a file OUTSIDE the isolated per-test workspace and defeat isolation. A conservative
# character whitelist plus an explicit reject of the traversal components ('.' / '..') closes
# that hole as defense-in-depth. WHY allow '.' / '-' (Trade-off vs. the stricter [A-Za-z0-9_]
# a reviewer might reach for): the loader/unloader publish alternate-key sidecars named
# ``<file>.1`` and the module's documented contract accepts auxiliary bare filenames, so dots
# and hyphens WITHIN a name are legitimate and safe (a single path component can never escape
# ``data/``); only the *whole-name* tokens '.' and '..' are dangerous and are rejected below.
_ASSIGN_NAME_RE = re.compile(r"^[A-Za-z0-9._-]+$")


class CobolRunError(RuntimeError):
    """Raised when a COBOL program run fails a caller-requested expectation.

    Purpose
    -------
    Signals two distinct run-time failures that a test almost always wants to
    treat as an error rather than inspect field-by-field:

    * a non-zero ``RETURN-CODE`` when the caller passed ``check=True`` to
      :meth:`CobolRunner.run`; and
    * a run that exceeded its ``timeout`` (a stuck program is a defect, not a
      soft reject).

    It subclasses :class:`RuntimeError` so a broad ``except RuntimeError`` in a
    test still catches it, while a targeted ``except CobolRunError`` can
    distinguish a program failure from an unrelated bug in the harness.

    Parameters
    ----------
    Standard :class:`RuntimeError` arguments (a single human-readable message is
    the norm in this module).

    Returns
    -------
    None

    Raises
    ------
    None
    """


@dataclass
class RunResult:
    """Immutable-ish record of a single COBOL program execution.

    Purpose
    -------
    Bundles everything a financial-grade assertion needs after running a batch
    program: the process return code, the captured console streams, the
    workspace the program ran in, and a mapping of every ASSIGN external name to
    the concrete file path it was bound to (so a test can read the produced
    output without re-deriving paths).

    Parameters
    ----------
    program : str
        The program name that was run (e.g. ``"CBTRN02C"``), as passed to
        :meth:`CobolRunner.run`.
    returncode : int
        The process exit status. See the return-code caveat below.
    stdout : str
        Captured standard output (decoded text; see the decoding note on
        :meth:`CobolRunner.run`).
    stderr : str
        Captured standard error (decoded text).
    workspace : Path
        The working directory the program ran in.
    assigns : dict[str, Path]
        Mapping of ASSIGN external name -> bound file path, reflecting the paths
        actually presented to the program (including any per-run overrides).

    Returns
    -------
    None

    Raises
    ------
    None

    Notes
    -----
    RETURN-CODE > 255 caveat: COBOL programs can set ``RETURN-CODE`` to values
    larger than 255 (e.g. an LE abend code such as 999), but a POSIX process exit
    status is only 8 bits, so the value observed here is the COBOL code modulo
    256 (``999 & 0xFF == 231``). Tests should therefore assert primarily on the
    produced output artifacts and on ``returncode`` relative to a small known
    value (0 pass, 4 soft-reject), and treat the raw ``returncode`` as a
    truncated signal rather than an exact abend code. The raw value is exposed
    verbatim (never reinterpreted) so callers keep full control.
    """

    program: str
    returncode: int
    stdout: str
    stderr: str
    workspace: Path
    assigns: "dict[str, Path]"

    def ok(self, expected: int = 0) -> bool:
        """Return whether the run's return code equals an expected value.

        Purpose
        -------
        A readable shorthand for the most common assertion -- "did this program
        exit with the code I expected?" -- so tests can write
        ``assert result.ok()`` (pass) or ``result.ok(4)`` (a soft reject) rather
        than repeating the comparison.

        Parameters
        ----------
        expected : int, optional
            The return code considered success for this scenario. Defaults to
            ``0`` (the CardDemo RC rubric's PASS). Pass ``4`` to accept the
            soft-reject/warn code.

        Returns
        -------
        bool
            ``True`` if :attr:`returncode` equals ``expected``, else ``False``.

        Raises
        ------
        None
        """
        # WHY (Trade-off): an exact equality check -- not ``<= expected`` -- keeps
        # the assertion honest. A program that unexpectedly returns 0 when a
        # reject (4) was expected is a real test failure, and a range check would
        # hide it.
        return self.returncode == expected

    def output_path(self, assign_name: str) -> Path:
        """Return the bound workspace path for an ASSIGN external name.

        Purpose
        -------
        Lets a test locate the concrete file a program wrote (or read) without
        re-deriving the workspace layout, e.g.
        ``result.output_path("DALYREJS")``.

        Parameters
        ----------
        assign_name : str
            One of the ASSIGN external names in :data:`ASSIGN_NAMES` (or any name
            that was bound for this run via ``env_overrides``).

        Returns
        -------
        Path
            The file path bound to ``assign_name`` for this run.

        Raises
        ------
        KeyError
            If ``assign_name`` was not bound for this run. The message lists the
            known names to make a typo obvious.
        """
        try:
            return self.assigns[assign_name]
        except KeyError:
            # WHY (Assumption): a lookup miss is almost always a typo'd ASSIGN
            # name; surfacing the known keys turns a bare KeyError into an
            # actionable diagnostic.
            known = ", ".join(sorted(self.assigns))
            raise KeyError(
                f"ASSIGN name {assign_name!r} was not bound for this run; "
                f"known names are: {known}"
            ) from None

    def read_output(self, assign_name: str, encoding: str = "latin-1") -> str:
        """Read and decode a produced output file bound to an ASSIGN name.

        Purpose
        -------
        Convenience for reading the raw content of a file a program wrote, ready
        for golden-master comparison or field extraction.

        Parameters
        ----------
        assign_name : str
            The ASSIGN external name whose bound file should be read.
        encoding : str, optional
            Text encoding used to decode the file. Defaults to ``"latin-1"``.

        Returns
        -------
        str
            The decoded file content.

        Raises
        ------
        KeyError
            If ``assign_name`` was not bound for this run (from
            :meth:`output_path`).
        FileNotFoundError
            If the program did not create the file (with a message that says so,
            to distinguish "empty output" from "no output").
        """
        # WHY (Assumption/Trade-off): latin-1 is the default because CardDemo
        # records are fixed-width bytes derived from EBCDIC/zoned-decimal data
        # that are frequently NOT valid UTF-8. latin-1 maps every one of the 256
        # byte values to a code point 1:1, so decoding can never raise -- the
        # caller gets the exact bytes back as characters and can slice by offset.
        path = self.output_path(assign_name)
        if not path.exists():
            raise FileNotFoundError(
                f"output file for ASSIGN {assign_name!r} was not produced at "
                f"{path} (the program may not have written it)"
            )
        return path.read_text(encoding=encoding)


def _resolve_geometry(
    layout: "str | None",
    reclen: "int | None",
    key_length: "int | None",
    alternate_keys: "object | None",
) -> "tuple[int, int, object]":
    """Resolve ``(reclen, key_length, alternate_keys)`` from an optional layout name.

    Purpose
    -------
    Shared geometry resolution for both indexed-file directions:
    :meth:`CobolRunner.load_input` (staging an indexed INPUT) and
    :meth:`CobolRunner.unload_output` (reading an indexed OUTPUT). When ``layout`` is given,
    its record length, primary-key length, and alternate-key descriptors come from the
    single-source record codec (via :mod:`vsam_loader`), and any explicitly supplied
    ``reclen``/``key_length`` must *agree* with it. WHY reject a conflict rather than pick a
    winner (Assumption): a caller who passes both a layout and a disagreeing explicit value
    has a bug that would otherwise silently mis-handle the file; failing loudly surfaces it.
    WHY factor this out (Refactoring Rationale): load and unload must agree byte-for-byte on
    geometry; a single resolver makes them provably identical and prevents the two sides
    from drifting (e.g. a fix applied to staging but not to read-back).

    Parameters
    ----------
    layout : str or None
        Logical record-layout name (e.g. ``"XREF"``, ``"ACCOUNT"``) or ``None``.
    reclen : int or None
        Explicit record length, or ``None`` to derive it from ``layout``.
    key_length : int or None
        Explicit primary-key length, or ``None`` to derive it from ``layout``.
    alternate_keys : object or None
        Explicit alternate-key descriptors; ``None`` derives them from ``layout`` (or an
        empty tuple when no layout is given); an explicit ``()`` forces a primary-key-only
        file even for an alternate-keyed layout.

    Returns
    -------
    tuple[int, int, object]
        ``(reclen, key_length, alternate_keys)`` fully resolved for a :mod:`vsam_loader`
        call.

    Raises
    ------
    ValueError
        If neither ``layout`` nor both ``reclen`` and ``key_length`` are supplied, or if an
        explicit ``reclen``/``key_length`` conflicts with ``layout``'s geometry.
    """
    if layout is not None:
        # WHY (QA finding C1/C2): deriving geometry AND alternate keys from the layout means
        # a caller can never forget to stage/declare the alternate-key sidecars that a
        # program like CBACT04C (which reads XREF by its account-id ALTERNATE key) requires.
        from tests.helpers.vsam_loader import geometry_for, alternate_keys_for

        geo_reclen, geo_keylen = geometry_for(layout)
        if reclen is None:
            reclen = geo_reclen
        elif reclen != geo_reclen:
            raise ValueError(
                f"reclen={reclen} conflicts with layout {layout!r} geometry "
                f"(reclen={geo_reclen})"
            )
        if key_length is None:
            key_length = geo_keylen
        elif key_length != geo_keylen:
            raise ValueError(
                f"key_length={key_length} conflicts with layout {layout!r} geometry "
                f"(key_length={geo_keylen})"
            )
        if alternate_keys is None:
            alternate_keys = alternate_keys_for(layout)

    if reclen is None or key_length is None:
        raise ValueError(
            "either layout= or both reclen= and key_length= must be supplied"
        )
    if alternate_keys is None:
        alternate_keys = ()
    return reclen, key_length, alternate_keys


class CobolRunner:
    """Execute compiled CardDemo batch programs against an isolated workspace.

    Purpose
    -------
    Owns the workspace + environment wiring needed to run a compiled GnuCOBOL
    batch program deterministically and in isolation. This is the object the
    pending ``tests/conftest.py`` ``cobol_runner`` fixture constructs, bound to
    the build directory, a per-test workspace, and (optionally) a reports
    directory.

    Parameters
    ----------
    build_dir : str | os.PathLike[str]
        Directory containing the compiled programs (``build/<PROG>`` executables
        and ``build/<NAME>.so`` shared modules), as produced by
        ``scripts/build_test_programs.sh``.
    workspace : str | os.PathLike[str]
        Per-test working directory. It (and a ``data/`` sub-directory) is created
        if missing. Every ASSIGN name is bound beneath this directory so runs are
        isolated from one another.
    reports_dir : str | os.PathLike[str] | None, optional
        Optional directory for run artifacts a caller may wish to persist
        alongside the suite's JUnit reports. Created if provided; otherwise
        ``None``. The runner itself writes no report file (pytest owns JUnit
        emission); the attribute exists so the conftest fixture can hand the path
        through to tests that want it.

    Returns
    -------
    None

    Raises
    ------
    OSError
        If the workspace / data / reports directories cannot be created.
    """

    def __init__(
        self,
        build_dir: "str | os.PathLike[str]",
        workspace: "str | os.PathLike[str]",
        reports_dir: "str | os.PathLike[str] | None" = None,
    ) -> None:
        """Bind this runner to a build dir, per-test workspace, and optional reports dir.

        Purpose
        -------
        Resolve the three injected locations to absolute paths and eagerly create
        the per-test workspace, its ``data/`` sub-directory, and (when supplied)
        the reports directory, so every subsequent program launch is isolated and
        path-stable regardless of the process's later working directory.

        Parameters, Returns, and Raises are documented once on the class docstring
        (see :class:`CobolRunner`) and are deliberately not repeated here to keep
        the constructor's contract single-sourced and free of documentation drift
        (Trade-off: one authoritative description over a duplicated one that could
        silently diverge from the signature).
        """
        # Resolve to absolute paths up front so later subprocess launches are
        # immune to any later `chdir`, and so RunResult carries unambiguous paths.
        # WHY (Assumption): the injected paths may be relative to the repo root;
        # resolving once here keeps every downstream path stable and comparable.
        self.build_dir: Path = Path(build_dir).resolve()
        self.workspace: Path = Path(workspace).resolve()
        self.data_dir: Path = self.workspace / _DATA_SUBDIR
        self.reports_dir: "Path | None" = (
            Path(reports_dir).resolve() if reports_dir is not None else None
        )

        # Create the workspace and its data sub-directory eagerly. WHY: GnuCOBOL
        # opens output files with OPEN OUTPUT, which cannot create missing parent
        # directories; ensuring they exist here means callers never have to.
        self.data_dir.mkdir(parents=True, exist_ok=True)
        if self.reports_dir is not None:
            self.reports_dir.mkdir(parents=True, exist_ok=True)

    def assign_path(self, name: str) -> Path:
        """Return the per-workspace file path for an ASSIGN external name.

        Purpose
        -------
        Central definition of *where* a given ASSIGN name's file lives for this
        runner, keeping the layout (``<workspace>/data/<NAME>``) consistent with
        ``test_env.sh``'s ``CARDDEMO_DATA_DIR=<workspace>/data`` and defined in
        exactly one place.

        Parameters
        ----------
        name : str
            An ASSIGN external name (typically one of :data:`ASSIGN_NAMES`, but
            any bare filename is accepted so callers may bind auxiliary names).

        Returns
        -------
        Path
            ``<workspace>/data/<name>``.

        Raises
        ------
        ValueError
            If ``name`` is empty, is a path-traversal component (``"."`` or ``".."``),
            or contains any character outside ``[A-Za-z0-9._-]`` (e.g. a path separator,
            NUL, whitespace, or a control/shell metacharacter) -- any of which could let a
            binding escape the isolated per-test workspace (QA finding i1).
        """
        # WHY (QA finding i1 -- hardening): assign_path builds ``<workspace>/data/<name>``,
        # so ``name`` MUST be a single, benign path component. The previous guard rejected
        # only names containing an OS path separator, which let the bare traversal token
        # ``".."`` through -- ``data_dir / ".."`` resolves to the workspace root, binding a
        # file OUTSIDE the isolated data directory and defeating per-test isolation. We now
        # (1) reject the ``"."``/``".."`` traversal components explicitly and (2) require the
        # remaining name to match a conservative character whitelist, which also rejects
        # absolute paths, separators, NUL bytes, and control/whitespace characters. A name
        # that passes both checks is a single component that can only ever resolve inside
        # ``data_dir`` (Assumption: ``Path(base) / "<bare component>"`` never escapes base).
        if not name or name in (".", "..") or not _ASSIGN_NAME_RE.match(name):
            raise ValueError(
                f"ASSIGN name {name!r} must be a bare filename matching "
                f"[A-Za-z0-9._-]+ and not '.' or '..' (a path separator or traversal "
                f"component could bind a file outside the isolated workspace)."
            )
        return self.data_dir / name

    def build_env(self, overrides: "dict[str, object] | None" = None) -> "dict[str, str]":
        """Build the child-process environment for a run.

        Purpose
        -------
        Produces the environment dict that binds every ASSIGN external name to a
        workspace file and points ``COB_LIBRARY_PATH`` at the build directory, so
        a launched program resolves both its data files and its dynamically
        CALL'd shared modules.

        Parameters
        ----------
        overrides : dict[str, object] | None, optional
            Extra environment entries applied last, so a caller can repoint a
            specific ASSIGN name (e.g. share one fixture between two runs) or set
            an unrelated variable. Values are stringified. Defaults to ``None``.

        Returns
        -------
        dict[str, str]
            A fresh environment mapping suitable for ``subprocess.run(env=...)``.

        Raises
        ------
        None
        """
        # WHY (Trade-off): copy os.environ rather than starting from an empty
        # dict, so the child keeps PATH, locale, and any LocalStack/AWS variables
        # the wider suite depends on. Only the bindings we own are layered on top.
        env: "dict[str, str]" = os.environ.copy()

        # Bind each of the 28 distinct ASSIGN names into the per-test workspace.
        # WHY (per-test isolation + parallel safety): redirecting the names here
        # -- rather than relying on test_env.sh's shared $CARDDEMO_DATA_DIR --
        # gives every run its own files, which is what makes `pytest-xdist -n
        # auto` safe.
        for name in ASSIGN_NAMES:
            env[name] = str(self.assign_path(name))

        # Ensure COB_LIBRARY_PATH includes the build dir so CSUTLDTC / CBSTM03B /
        # CBACT04C (.so shared modules) resolve by PROGRAM-ID at run time.
        # WHY (Refactoring rationale): prepend-and-dedupe rather than overwrite,
        # so any path the ambient environment already provides is preserved while
        # the build dir still takes precedence.
        build_dir_str = str(self.build_dir)
        prior = env.get(_COB_LIBRARY_PATH, "")
        if prior:
            parts = prior.split(os.pathsep)
            if build_dir_str in parts:
                env[_COB_LIBRARY_PATH] = prior
            else:
                env[_COB_LIBRARY_PATH] = build_dir_str + os.pathsep + prior
        else:
            env[_COB_LIBRARY_PATH] = build_dir_str

        # Apply caller overrides last so an explicit override always wins over the
        # defaults set above (including an override of an ASSIGN name or of
        # COB_LIBRARY_PATH itself).
        if overrides:
            for key, value in overrides.items():
                env[str(key)] = str(value)

        return env

    def load_input(
        self,
        assign_name: str,
        flat_path: "str | os.PathLike[str]",
        reclen: "int | None" = None,
        key_length: "int | None" = None,
        key_offset: int = 0,
        *,
        alternate_keys: "tuple[tuple[int, int, bool], ...] | None" = None,
        layout: "str | None" = None,
    ) -> Path:
        """Load a flat fixture into the indexed file bound to an ASSIGN name.

        Purpose
        -------
        Convenience for programs that declare ``ORGANIZATION IS INDEXED`` inputs:
        it materialises a flat fixed-width fixture as a native GnuCOBOL indexed
        file at the workspace path bound to ``assign_name`` (the automated analog
        of ``IDCAMS REPRO``), so the program can OPEN and read it -- **including any
        alternate-key sidecar files** the program relies on.

        Passing ``layout`` (e.g. ``layout="XREF"``) resolves the record geometry AND
        the alternate-key descriptors from the single-source record codec, so a caller
        stages an alternate-keyed file correctly without transcribing offsets:
        ``runner.load_input("XREFFILE", fixture, layout="XREF")``.

        Parameters
        ----------
        assign_name : str
            ASSIGN external name whose bound path will receive the indexed file.
        flat_path : str | os.PathLike[str]
            Path to the flat fixed-width fixture to load.
        reclen : int or None, optional
            Fixed record length in characters. Required unless ``layout`` is given
            (from which it is derived). Defaults to ``None``.
        key_length : int or None, optional
            Leading primary-key length in characters. Required unless ``layout`` is
            given (from which it is derived). Defaults to ``None``.
        key_offset : int, optional
            Key offset within the record. Only ``0`` is supported by the loader;
            a non-zero value is rejected there. Defaults to ``0``.
        alternate_keys : tuple[tuple[int, int, bool], ...] or None, optional
            Secondary-index descriptors as ``(offset, length, allow_duplicates)``
            tuples (or :class:`record_codec.AlternateKey` objects), forwarded to
            :func:`load_indexed` so it emits the matching ``ALTERNATE RECORD KEY``
            clauses and produces the ``<file>.N`` sidecars. ``None`` (default) means
            "derive from ``layout`` if one is given, otherwise none". Pass an explicit
            ``()`` to force a primary-key-only file even for an alternate-keyed layout.
        layout : str or None, optional
            Logical record-layout name (e.g. ``"XREF"``). When given, its geometry and
            alternate keys are resolved from :mod:`record_codec` and fill any of
            ``reclen``/``key_length``/``alternate_keys`` left unset. Defaults to ``None``.

        Returns
        -------
        Path
            The path of the produced indexed file (== ``assign_path(assign_name)``).

        Raises
        ------
        ValueError
            If neither ``layout`` nor both ``reclen`` and ``key_length`` are supplied,
            or if an explicit ``reclen``/``key_length`` conflicts with ``layout``'s
            geometry (a silent geometry mismatch would corrupt the loaded file).
        tests.helpers.vsam_loader.VsamLoadError
            If the load fails (bad geometry, unreadable fixture, compile/run
            failure); propagated unchanged from :func:`load_indexed`.
        """
        # WHY (Trade-off): import the loader lazily, inside this method, so a test
        # that only runs sequential-file programs pays neither the import cost nor
        # the implicit `cobc` requirement of the indexed-file loader. This keeps
        # the module import free of any internal dependency and avoids import
        # cycles within the helpers namespace package.
        from tests.helpers.vsam_loader import load_indexed

        # Resolve geometry + alternate keys via the shared resolver (single source of
        # truth, shared with unload_output). WHY (QA finding C1): deriving the alternate-key
        # descriptors from the layout means a caller can never forget to stage the ``.1``
        # sidecar that CBACT04C needs to read XREF by its account-id ALTERNATE key.
        reclen, key_length, alternate_keys = _resolve_geometry(
            layout, reclen, key_length, alternate_keys
        )

        indexed_path = self.assign_path(assign_name)
        # load_indexed writes to `indexed_path` and returns it; wrap to Path so the
        # return type is consistent with the rest of this module. alternate_keys is
        # forwarded so the ALTERNATE RECORD KEY sidecars are produced (C1).
        return Path(
            load_indexed(
                flat_path,
                indexed_path,
                reclen,
                key_length,
                key_offset,
                alternate_keys=alternate_keys,
            )
        )

    def load_sequential(
        self,
        assign_name: str,
        flat_path: "str | os.PathLike[str]",
        reclen: "int | None" = None,
    ) -> Path:
        """Stage a flat fixture as a headerless sequential input for an ASSIGN name.

        Purpose
        -------
        Materialise a flat fixed-width fixture at the workspace path bound to
        ``assign_name`` as a byte-exact ``N * reclen`` blob with **no** trailing
        record terminator, for programs that declare ``ORGANIZATION IS SEQUENTIAL``
        line-sequential inputs (e.g. ``CBTRN02C``'s ``DALYTRAN`` daily-transaction
        file). Without this helper a caller would copy the fixture verbatim, and a
        shipped fixture that carries a trailing newline (351 bytes for one 350-byte
        record) is read by GnuCOBOL as a spurious extra partial record -- for
        ``CBTRN02C`` that surfaced as a phantom second rejected transaction in
        ``DALYREJS`` (QA finding M2, a silent wrong-result defect).

        Parameters
        ----------
        assign_name : str
            ASSIGN external name whose bound workspace path will receive the blob.
        flat_path : str | os.PathLike[str]
            Path to the flat fixed-width fixture to stage.
        reclen : int or None, optional
            Fixed record length in characters. When given (recommended), every row
            is validated to be exactly ``reclen`` wide and the result is a strict
            ``N * reclen`` blob -- a malformed fixture fails loudly. When ``None``,
            each line's single trailing terminator is stripped and the rows are
            concatenated without width validation (convenience for callers that do
            not know the geometry). Defaults to ``None``.

        Returns
        -------
        Path
            The path of the staged sequential file (== ``assign_path(assign_name)``).

        Raises
        ------
        tests.helpers.vsam_loader.VsamLoadError
            If ``reclen`` is given and a row is not exactly ``reclen`` wide, or the
            fixture cannot be read; propagated unchanged from ``_validated_blob``.
        FileNotFoundError
            If ``flat_path`` does not exist (raised while reading the fixture).
        """
        dest = self.assign_path(assign_name)
        if reclen is not None:
            # WHY (single source of truth): delegate the strip-and-validate to
            # vsam_loader._validated_blob -- the exact routine the indexed loader
            # uses for its flat inputs -- so a sequential fixture is subject to the
            # identical CR-03 width contract (one trailing \r/\n stripped per row,
            # every row exactly `reclen`, headerless N*reclen result). Reusing it
            # (rather than re-implementing the strip here) guarantees the two staging
            # paths can never drift apart, which is why reaching for the sibling
            # module's leading-underscore helper is justified between these two
            # tightly-coupled helpers in the same package.
            from tests.helpers.vsam_loader import _validated_blob

            blob = _validated_blob(flat_path, reclen)
            dest.write_bytes(blob)
            return dest

        # reclen is None: convenience path with no width validation. Normalise line
        # endings, then strip a single trailing terminator per row and concatenate.
        # WHY (Trade-off): mirroring _validated_blob's newline handling keeps the
        # "empty means empty" and "one terminator per row" semantics consistent even
        # on the unvalidated path, so the only difference from the strict path is the
        # absence of the per-row width assertion -- not a different stripping rule.
        raw = Path(flat_path).read_text(encoding="latin-1")
        raw = raw.replace("\r\n", "\n").replace("\r", "\n")
        if raw in ("", "\n"):
            dest.write_text("", encoding="latin-1")
            return dest
        lines = raw.split("\n")
        if lines and lines[-1] == "":
            lines.pop()  # drop the single empty element from a trailing EOF newline
        dest.write_text("".join(lines), encoding="latin-1")
        return dest

    def unload_output(
        self,
        assign_name: str,
        reclen: "int | None" = None,
        key_length: "int | None" = None,
        key_offset: int = 0,
        *,
        alternate_keys: "tuple[tuple[int, int, bool], ...] | None" = None,
        layout: "str | None" = None,
    ) -> "list[str]":
        """Read the indexed OUTPUT bound to an ASSIGN name into flat logical records.

        Purpose
        -------
        Convenience for verifying programs that write ``ORGANIZATION IS INDEXED`` outputs
        (e.g. ``CBTRN02C``'s posted ``TRANSACT``, ``CBACT04C``'s updated ``ACCTFILE`` and
        ``TCATBAL``): it reads the native GnuCOBOL indexed file at the workspace path bound
        to ``assign_name`` back into its logical records -- each a fixed
        ``reclen``-character string, in ascending **primary-key** order -- so a
        golden-master comparison can assert on record content without ever touching the
        opaque, unstable ISAM on-disk byte layout (QA finding C2). It is the read-back
        mirror of :meth:`load_input`, and the two share :func:`_resolve_geometry` so a
        ``layout=`` resolves identically on both sides.

        Typical use::

            runner.run("CBACT04C")
            records = runner.unload_output("ACCTFILE", layout="ACCOUNT")
            assert_matches_golden("\\n".join(records), golden_path, layout="ACCOUNT")

        Parameters
        ----------
        assign_name : str
            ASSIGN external name whose bound workspace path holds the indexed output.
        reclen : int or None, optional
            Fixed record length in characters. Required unless ``layout`` is given (from
            which it is derived). Defaults to ``None``.
        key_length : int or None, optional
            Primary-key length in characters. Required unless ``layout`` is given (from
            which it is derived). Defaults to ``None``.
        key_offset : int, optional
            Zero-based offset of the primary key within the record. Defaults to ``0``.
        alternate_keys : tuple[tuple[int, int, bool], ...] or None, optional
            Alternate-key descriptors the file was built with, forwarded so the reader's
            OPEN INPUT key set matches the file (a mismatch can fail FILE STATUS 39).
            ``None`` (default) derives them from ``layout`` if given, else none; an explicit
            ``()`` forces primary-key-only. CardDemo's indexed outputs are primary-key only.
        layout : str or None, optional
            Logical record-layout name (e.g. ``"ACCOUNT"``). When given, its geometry and
            alternate keys fill any of ``reclen``/``key_length``/``alternate_keys`` left
            unset. Defaults to ``None``.

        Returns
        -------
        list[str]
            The logical records as fixed ``reclen``-character strings in ascending
            primary-key order; an empty indexed file yields ``[]``. Join with ``"\\n"`` (or
            concatenate) for :func:`golden_compare.assert_matches_golden`, whose record mode
            frames fixed-width records by width so either joiner compares equal to the
            committed golden.

        Raises
        ------
        ValueError
            If neither ``layout`` nor both ``reclen`` and ``key_length`` are supplied, or if
            an explicit ``reclen``/``key_length`` conflicts with ``layout``'s geometry.
        tests.helpers.vsam_loader.VsamLoadError
            If the unload fails (bad geometry, missing/symlinked file, compile/run failure,
            or a blob length that is not a whole multiple of ``reclen``); propagated
            unchanged from :func:`unload_indexed`.
        """
        # WHY (Trade-off): import the unloader lazily, like load_input, so a test that never
        # reads an indexed output pays neither the import cost nor the implicit `cobc`
        # requirement of the indexed-file machinery.
        from tests.helpers.vsam_loader import unload_indexed

        reclen, key_length, alternate_keys = _resolve_geometry(
            layout, reclen, key_length, alternate_keys
        )
        indexed_path = self.assign_path(assign_name)
        return unload_indexed(
            indexed_path,
            reclen,
            key_length,
            key_offset,
            alternate_keys=alternate_keys,
        )

    def run(
        self,
        program: str,
        *,
        args: "list[str] | tuple[str, ...] | None" = None,
        env_overrides: "dict[str, object] | None" = None,
        stdin: "str | None" = None,
        timeout: int = 120,
        check: bool = False,
    ) -> RunResult:
        """Run a compiled batch program and capture its outcome.

        Purpose
        -------
        Resolve ``build_dir/<program>``, execute it to completion in the isolated
        workspace with the bound environment, and return a :class:`RunResult`
        carrying the return code, captured streams, and ASSIGN path map.

        Parameters
        ----------
        program : str
            Program name to run, resolved as ``build_dir/<program>`` (e.g.
            ``"CBTRN02C"``). Dynamically CALL'd modules (``build/<NAME>.so``) are
            not run directly -- they are resolved by a main program at run time.
        args : list[str] | tuple[str, ...] | None, optional
            Extra command-line arguments passed to the program. Defaults to
            ``None`` (no arguments), matching the batch programs' usual contract.
        env_overrides : dict[str, object] | None, optional
            Environment overrides forwarded to :meth:`build_env`; an override of
            an ASSIGN name wins over the workspace default. Defaults to ``None``.
        stdin : str | None, optional
            Text piped to the program's standard input. Defaults to ``None``.
        timeout : int, optional
            Seconds before the run is abandoned. Defaults to ``120``.
        check : bool, optional
            If ``True``, a non-zero return code raises :class:`CobolRunError`.
            Defaults to ``False`` (so soft rejects, RC=4, can be asserted on).

        Returns
        -------
        RunResult
            The captured result of the run.

        Raises
        ------
        FileNotFoundError
            If ``build_dir/<program>`` does not exist (with a message pointing at
            ``scripts/build_test_programs.sh``).
        CobolRunError
            If the run exceeds ``timeout``, or if ``check`` is ``True`` and the
            return code is non-zero.
        """
        executable = self.build_dir / program
        if not executable.exists():
            # WHY (Assumption): this runner intentionally does NOT compile; the
            # build is scripts/build_test_programs.sh's job (invoked by conftest's
            # `built_programs` fixture). A clear, actionable error beats a cryptic
            # OSError from subprocess.
            raise FileNotFoundError(
                f"compiled program not found: {executable}. Build it first with "
                f"scripts/build_test_programs.sh (it writes build/<PROG> for the "
                f"11 main executables). Note: dynamically CALL'd subprograms "
                f"(CSUTLDTC, CBSTM03B, CBACT04C) are built as build/<NAME>.so and "
                f"are resolved at run time via COB_LIBRARY_PATH, not run directly."
            )

        # Assemble the command. Stringify args defensively so a caller may pass
        # ints/Paths without surprises.
        command: "list[str]" = [str(executable)]
        if args:
            command.extend(str(a) for a in args)

        env = self.build_env(env_overrides)

        try:
            # WHY (completion-aware execution): subprocess.run blocks until the
            # child exits and hands back its exit status -- the deterministic
            # replacement for the fixed `sleep` pacing in the reference
            # scripts/run_*.sh demonstration jobs. text=True + errors="replace"
            # so odd (non-UTF-8) bytes a COBOL DISPLAY may emit never crash the
            # harness; real assertions are made on output files (read via latin-1)
            # and the return code, not on a pristine console decode.
            completed = subprocess.run(
                command,
                cwd=str(self.workspace),
                env=env,
                input=stdin,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=timeout,
            )
        except subprocess.TimeoutExpired as exc:
            # A run that never terminates is a defect (e.g. an unexpected prompt
            # awaiting input), so surface it as a hard error rather than a result.
            raise CobolRunError(
                f"program {program!r} did not finish within {timeout}s "
                f"(workspace={self.workspace})"
            ) from exc

        # Capture the ACTUAL bound paths from the environment so the map reflects
        # any per-run override (not merely the workspace defaults).
        assigns: "dict[str, Path]" = {name: Path(env[name]) for name in ASSIGN_NAMES}

        result = RunResult(
            program=program,
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
            workspace=self.workspace,
            assigns=assigns,
        )

        if check and completed.returncode != 0:
            # Include a bounded tail of stderr so the exception is self-explanatory
            # without dumping an unbounded log into the test output.
            stderr_tail = "\n".join((completed.stderr or "").splitlines()[-20:])
            raise CobolRunError(
                f"program {program!r} exited with return code "
                f"{completed.returncode} (expected 0). stderr tail:\n{stderr_tail}"
            )

        return result


def run_program(
    program: str,
    build_dir: "str | os.PathLike[str] | None" = None,
    workspace: "str | os.PathLike[str] | None" = None,
    reports_dir: "str | os.PathLike[str] | None" = None,
    **kwargs: object,
) -> RunResult:
    """Run a compiled batch program with a one-line call (Section 0.6.2 import).

    Purpose
    -------
    A thin convenience wrapper that resolves a build dir and workspace, builds a
    :class:`CobolRunner`, and delegates to :meth:`CobolRunner.run`. It gives
    integration/E2E tests a single-call entry point while the class serves the
    conftest fixture.

    Parameters
    ----------
    program : str
        Program name to run (see :meth:`CobolRunner.run`).
    build_dir : str | os.PathLike[str] | None, optional
        Build directory. Resolution order: this argument, then the
        ``CARDDEMO_BUILD_DIR`` environment variable, then ``<cwd>/build``.
    workspace : str | os.PathLike[str] | None, optional
        Per-run workspace. Resolution order: this argument, then the
        ``CARDDEMO_TEST_WORKSPACE`` environment variable, then a fresh temporary
        directory.
    reports_dir : str | os.PathLike[str] | None, optional
        Optional reports directory forwarded to :class:`CobolRunner`.
    **kwargs : object
        Keyword arguments forwarded verbatim to :meth:`CobolRunner.run`
        (``args``, ``env_overrides``, ``stdin``, ``timeout``, ``check``).

    Returns
    -------
    RunResult
        The captured result of the run.

    Raises
    ------
    FileNotFoundError
        If the resolved ``build_dir/<program>`` does not exist.
    CobolRunError
        If the run times out or ``check=True`` and the return code is non-zero.
    """
    # WHY (Refactoring rationale): resolve defaults from the same environment
    # variables test_env.sh exports, so a one-liner call behaves identically to
    # the fixture-driven path when the suite environment is sourced.
    if build_dir is None:
        build_dir = os.environ.get("CARDDEMO_BUILD_DIR") or (Path.cwd() / "build")
    if workspace is None:
        # WHY (Trade-off): fall back to a fresh mkdtemp so an ad-hoc call is still
        # isolated. Such a directory is intentionally NOT auto-deleted here --
        # callers using this fallback typically want to inspect the outputs, and
        # the fixture-driven path (which owns cleanup) is preferred for managed
        # runs.
        workspace = os.environ.get("CARDDEMO_TEST_WORKSPACE") or tempfile.mkdtemp(
            prefix="carddemo-run-"
        )
    runner = CobolRunner(build_dir, workspace, reports_dir=reports_dir)
    return runner.run(program, **kwargs)
