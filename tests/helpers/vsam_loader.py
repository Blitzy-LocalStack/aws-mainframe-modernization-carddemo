"""Flat-fixture -> GnuCOBOL native indexed (ISAM) loader for the CardDemo test harness.

Purpose
-------
This module is the automated analog of the mainframe ``IDCAMS DELETE -> DEFINE ->
REPRO`` file-load pattern (see ``app/jcl/ACCTFILE.jcl``). Several CardDemo batch
programs declare their inputs with ``ORGANIZATION IS INDEXED`` (VSAM KSDS on the
mainframe). Before such a program can run under GnuCOBOL, the flat fixed-width
fixture that a test provides must first be materialised as a *native GnuCOBOL
indexed file*. :func:`load_indexed` performs exactly that step: it takes a flat
fixture and a target path and produces an indexed file the program under test can
open and read.

It is imported as ``from tests.helpers.vsam_loader import load_indexed`` and can also
be driven from the shell as ``python3 -m tests.helpers.vsam_loader`` so the thin
``tests/helpers/load_indexed.sh`` wrapper can delegate to a single source of truth.
There is **no** ``__init__.py`` anywhere under ``tests/`` -- the package resolves as a
PEP 420 namespace package via ``PYTHONPATH=<repo_root>``.

The central design constraint (the primary WHY)
------------------------------------------------
**Only GnuCOBOL can write its own native indexed-file format, and that format depends
on how ``cobc`` was built** (on this runner the indexed backend is Berkeley DB). A
hand-rolled Python writer targeting VBISAM/BDB byte layouts would be brittle and
tightly coupled to a specific backend build, and -- worse -- would not be guaranteed
readable by the very programs under test. Therefore this loader generates a tiny
COBOL "loader" program, compiles it **with the same ``cobc`` that compiles the
programs under test**, and runs it to WRITE the indexed file. That is the only
approach that guarantees the produced file is byte-compatible with what the programs
under test expect.

*Alternatives Considered:* emulating the VBISAM / Berkeley-DB on-disk structures
directly in Python was rejected as brittle and backend-specific; the same file
produced on a differently-built ``cobc`` would silently fail to open. Round-tripping
through the project ``cobc`` sidesteps the entire compatibility question.

How a load proceeds (mirrors the JCL, step for step)
-----------------------------------------------------
1. **DELETE analog** -- any pre-existing target indexed file (and its GnuCOBOL
   companion/index sidecar files) is removed first, exactly as JCL ``STEP05`` issues
   ``DELETE ... CLUSTER`` and tolerates an absent target.
2. **Normalise the fixture** -- the flat fixture is read in Python, each line is
   stripped of a trailing ``\\r``/``\\n`` and forced to exactly ``reclen`` characters
   (short records are space-padded; the codebase's omitted trailing FILLER is spaces),
   then concatenated into a *headerless fixed-width blob* (``reclen * N`` bytes, no
   record separators). See :func:`_normalize_to_blob` for the WHY.
3. **DEFINE + REPRO analog** -- a generated COBOL program (see
   :func:`_generate_loader_source`) reads the blob as a fixed-length record-sequential
   file and WRITEs each record into an ``ORGANIZATION IS INDEXED`` file whose
   ``RECORD KEY`` is the leading ``key_length`` bytes. This is JCL ``STEP10``'s
   ``DEFINE CLUSTER ... KEYS(<len> 0) RECORDSIZE(<reclen> <reclen>) INDEXED`` and
   ``STEP15``'s ``REPRO`` collapsed into one deterministic pass.

Key geometry invariant (WHY key_offset must be 0)
-------------------------------------------------
Every primary ``RECORD KEY`` in the CardDemo VSAM files is at **offset 0** (the key is
the leading field of the record). The generated loader hard-codes that assumption by
splitting each record into ``IDX-KEY`` (the first ``key_length`` bytes) followed by
``IDX-REST``. Passing a non-zero ``key_offset`` would therefore silently mis-key the
file, so :func:`load_indexed` rejects it loudly rather than producing a subtly wrong
file.

Explainability
--------------
Per the project's mandatory Explainability rule, every public and private symbol below
carries a docstring stating Purpose / Parameters / Returns / Raises, and each
non-obvious decision is annotated with a WHY comment documenting at least one of
Alternatives Considered, Assumptions, or Trade-offs.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# NOTE (Import whitelist): the ONLY internal dependency permitted for this file is
# ``tests/helpers/record_codec.py``, and it is imported *lazily* inside
# :func:`geometry_for` (not at module top level). WHY (Trade-off / Assumptions):
#   * keeping the top level import-free means this module can be imported and its
#     COBOL source generated even in a bare checkout before the rest of the harness
#     is present, and it removes any risk of an import cycle through the helpers
#     package;
#   * record_codec is only needed for the *by-name* convenience (resolving a layout's
#     reclen/key_length), which not every caller uses, so paying its import cost lazily
#     is strictly cheaper.
# Everything else this module needs is in the Python standard library.

__all__ = [
    "VsamLoadError",
    "load_indexed",
    "geometry_for",
]

# ---------------------------------------------------------------------------
# Environment-variable "DD name" bindings for the generated loader.
#
# WHY (Assumptions): GnuCOBOL resolves a non-literal ``ASSIGN TO name`` by looking up
# an environment variable of that exact name at run time and using its value as the
# physical file path. The generated loader uses these two fixed assignment names, and
# :func:`load_indexed` sets the matching variables in the child process environment.
# They are module constants (not magic strings scattered across the code) so the
# COBOL generator and the runner cannot drift apart.
# ---------------------------------------------------------------------------
_DD_FLAT_IN = "FLATIN"    # generated ``SELECT FLAT-FILE ASSIGN TO FLATIN``
_DD_INDEX_OUT = "IDXOUT"  # generated ``SELECT IDX-FILE  ASSIGN TO IDXOUT``

# Program-id of the generated loader. A fixed name is safe because each distinct
# (reclen, key_length, std) combination is compiled to its own standalone executable;
# the program-id is irrelevant to a ``-x`` built binary and never collides on disk.
_LOADER_PROGRAM_ID = "VSAMLDR"

# Return codes the generated loader emits, surfaced verbatim in error messages.
# WHY (Refactoring Rationale): naming them once keeps the COBOL generator, the runner's
# diagnostics, and the tests describing them in agreement.
_RC_OPEN_FAILURE = 12   # OPEN INPUT / OPEN OUTPUT failed (e.g. missing input, bad path)
_RC_WRITE_FAILURE = 8   # a WRITE to the indexed file failed (e.g. duplicate key)

# GnuCOBOL indexed-file companion suffixes to remove during the DELETE analog. BDB
# builds create ``<name>.1``, ``<name>.2`` ... for *alternate* keys; VBISAM builds use
# ``<name>.idx``. The primary-key-only files this loader writes normally have no
# companion at all, but the DELETE step scrubs any that a previous (differently keyed)
# load may have left behind so a stale sidecar can never shadow a fresh load.
_INDEX_COMPANION_LITERAL_SUFFIXES = ("idx",)


class VsamLoadError(RuntimeError):
    """Raised when a flat fixture cannot be loaded into a GnuCOBOL indexed file.

    Purpose
    -------
    Provide a single, specific exception type for every failure mode of the loader so
    callers (and tests) can catch exactly this class: invalid arguments (non-zero
    ``key_offset``, out-of-range ``key_length``), a missing/unusable ``cobc``
    compiler, a failed compile of the generated loader program, or a non-zero exit
    from the loader run itself (bad OPEN, failed WRITE such as a duplicate key).

    It subclasses :class:`RuntimeError` (WHY: Assumption) because a failed fixture load
    is an operational/runtime failure of the harness rather than a programming-type
    error such as :class:`TypeError`; callers that guard broadly with
    ``except RuntimeError`` still catch it, while callers wanting the precise signal
    can catch :class:`VsamLoadError` directly.

    Parameters
    ----------
    args : tuple
        Standard :class:`RuntimeError` positional arguments (typically a single
        human-readable diagnostic string that includes the offending path, file
        status, and any captured compiler/loader output).

    Returns
    -------
    VsamLoadError
        A new exception instance.

    Raises
    ------
    None
    """


def _resolve_cobc(cobc: str) -> str:
    """Locate the GnuCOBOL ``cobc`` compiler and return an invocable path to it.

    Purpose
    -------
    Turn the ``cobc`` argument (a bare command name such as ``"cobc"`` or an explicit
    path) into a concrete, existing, executable path, failing loudly with a
    :class:`VsamLoadError` when no usable compiler is found. Resolving up front lets
    the loader give one clear error ("cobc not found") instead of an opaque
    :class:`FileNotFoundError` from deep inside :func:`subprocess.run`.

    Parameters
    ----------
    cobc : str
        Either a command name to look up on ``PATH`` (e.g. ``"cobc"``) or a filesystem
        path to a specific compiler binary.

    Returns
    -------
    str
        An absolute or directly-invocable path to the ``cobc`` executable.

    Raises
    ------
    VsamLoadError
        If ``cobc`` contains a path separator but does not point at an executable
        file, or if a bare name cannot be found on ``PATH``.
    """
    # WHY (Trade-off): honour an explicit path first. If the caller passed something
    # that looks like a path (contains a separator), we do NOT consult PATH -- an
    # explicit request should fail on its own terms rather than silently resolving a
    # different compiler from PATH.
    if os.sep in cobc or (os.altsep and os.altsep in cobc):
        candidate = Path(cobc)
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
        raise VsamLoadError(
            f"cobc compiler path {cobc!r} is not an executable file; "
            "cannot build the GnuCOBOL indexed-file loader"
        )

    # Bare command name: defer to shutil.which so we respect the caller's PATH exactly
    # as the shell would (this is also what the repo's own compile scripts rely on).
    found = shutil.which(cobc)
    if found is None:
        raise VsamLoadError(
            f"GnuCOBOL compiler {cobc!r} was not found on PATH; the indexed-file "
            "loader requires cobc to build and run its loader program "
            "(install gnucobol, or pass cobc=<path>)"
        )
    return found


def _generate_loader_source(reclen: int, key_length: int) -> str:
    """Return the COBOL source text of a loader for one ``(reclen, key_length)`` pair.

    Purpose
    -------
    Produce a minimal, dialect-agnostic, free-format COBOL program that copies a
    headerless fixed-width record-sequential blob into a native GnuCOBOL
    ``ORGANIZATION IS INDEXED`` file, keyed on the leading ``key_length`` bytes. This
    is the compiled engine behind :func:`load_indexed` -- the automated equivalent of
    JCL ``DEFINE CLUSTER ... INDEXED`` + ``IDCAMS REPRO``.

    The generated program:

    * reads ``FLAT-FILE`` (``ASSIGN TO FLATIN``) as ``ORGANIZATION IS SEQUENTIAL`` with
      fixed ``reclen``-character records -- i.e. it consumes exactly ``reclen`` bytes
      per record from the blob with no delimiter handling;
    * writes ``IDX-FILE`` (``ASSIGN TO IDXOUT``) as ``ORGANIZATION IS INDEXED``,
      ``ACCESS MODE IS DYNAMIC``, ``RECORD KEY IS IDX-KEY``, opened ``OUTPUT``;
    * checks ``FILE STATUS`` after every OPEN and WRITE, emitting a
      ``FILE STATUS IS: NNNN`` diagnostic and a non-zero ``RETURN-CODE`` on any error
      (mirroring the fail-fast convention of the production batch programs).

    Parameters
    ----------
    reclen : int
        Fixed record length in characters. Must be a positive integer.
    key_length : int
        Length in characters of the leading primary key. Must satisfy
        ``0 < key_length <= reclen``.

    Returns
    -------
    str
        The complete COBOL source (free format), ready to be written to a ``.cbl`` file
        and compiled with ``cobc -x -free``.

    Raises
    ------
    VsamLoadError
        If ``reclen`` or ``key_length`` violate the ``0 < key_length <= reclen`` /
        positive-``reclen`` contract. (Validated here as well as in
        :func:`load_indexed` so the generator is safe to call directly from tests.)
    """
    if reclen <= 0:
        raise VsamLoadError(f"reclen must be a positive integer, got {reclen!r}")
    if not (0 < key_length <= reclen):
        raise VsamLoadError(
            f"key_length must satisfy 0 < key_length <= reclen "
            f"(reclen={reclen}, key_length={key_length})"
        )

    rest_len = reclen - key_length

    # WHY (Assumptions): PIC X(0) is illegal in COBOL, so when the key spans the whole
    # record we must NOT emit an IDX-REST subfield. Building the ``01 IDX-REC`` layout
    # conditionally keeps the generator correct at the reclen == key_length boundary
    # (a degenerate but valid layout where the entire record is the key).
    if rest_len > 0:
        idx_rec_layout = (
            "       01 IDX-REC.\n"
            f"          05 IDX-KEY   PIC X({key_length}).\n"
            f"          05 IDX-REST  PIC X({rest_len}).\n"
        )
    else:
        idx_rec_layout = (
            "       01 IDX-REC.\n"
            f"          05 IDX-KEY   PIC X({key_length}).\n"
        )

    # WHY (Trade-off): the source is assembled from an f-string template rather than a
    # COBOL COPY/REPLACING scheme because there is exactly one small, self-contained
    # program shape here; a template is far easier to read, review, and keep faithful
    # to the JCL semantics than an indirection through copybooks. The leading spaces
    # keep the listing tidy; ``-free`` (used by load_indexed) ignores column rules, so
    # indentation is purely cosmetic.
    source = f"""\
      *> ==================================================================
      *> {_LOADER_PROGRAM_ID} -- generated flat -> INDEXED loader (do not edit).
      *>
      *> Purpose : copy a headerless fixed-width record-sequential blob into a
      *>           native GnuCOBOL ORGANIZATION IS INDEXED file, keyed on the
      *>           leading {key_length} byte(s). Automated analog of the mainframe
      *>           IDCAMS DEFINE CLUSTER ... INDEXED + REPRO steps.
      *>
      *> WHY the input is ORGANIZATION IS SEQUENTIAL and the output is INDEXED:
      *>   the caller (tests/helpers/vsam_loader.py) has already normalised the
      *>   fixture into a fixed-width blob of exactly {reclen}-byte records with no
      *>   separators, so reading it as fixed record-sequential yields one record
      *>   per {reclen} bytes deterministically -- no LINE SEQUENTIAL padding or
      *>   stray-CR ambiguity. The output must be INDEXED because that is what the
      *>   programs under test declare for these files.
      *>
      *> WHY ACCESS MODE IS DYNAMIC (not SEQUENTIAL) on the output: OPEN OUTPUT with
      *>   SEQUENTIAL access requires records be presented in ascending key order and
      *>   fails FILE STATUS 21 otherwise. Fixtures are not guaranteed sorted, so
      *>   DYNAMIC access is used to let the ISAM layer place each record by key
      *>   regardless of input order (Trade-off: marginally more work per WRITE in
      *>   exchange for robustness against unsorted fixtures).
      *> ==================================================================
       IDENTIFICATION DIVISION.
       PROGRAM-ID. {_LOADER_PROGRAM_ID}.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
      *> ASSIGN names are resolved from same-named environment variables at run
      *> time (set by load_indexed): {_DD_FLAT_IN} -> input blob, {_DD_INDEX_OUT} -> output file.
           SELECT FLAT-FILE ASSIGN TO {_DD_FLAT_IN}
               ORGANIZATION IS SEQUENTIAL
               ACCESS MODE IS SEQUENTIAL
               FILE STATUS IS WS-FLAT-STATUS.
           SELECT IDX-FILE ASSIGN TO {_DD_INDEX_OUT}
               ORGANIZATION IS INDEXED
               ACCESS MODE IS DYNAMIC
               RECORD KEY IS IDX-KEY
               FILE STATUS IS WS-IDX-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD FLAT-FILE
           RECORD CONTAINS {reclen} CHARACTERS.
       01 FLAT-REC PIC X({reclen}).
       FD IDX-FILE
           RECORD CONTAINS {reclen} CHARACTERS.
{idx_rec_layout}       WORKING-STORAGE SECTION.
      *> Two-byte FILE STATUS receivers; "00" means the last I/O succeeded.
       01 WS-FLAT-STATUS PIC XX VALUE "00".
       01 WS-IDX-STATUS  PIC XX VALUE "00".
       01 WS-EOF         PIC X  VALUE "N".
      *> Loaded-record counter, echoed on success for auditability.
       01 WS-COUNT       PIC 9(9) VALUE 0.
       PROCEDURE DIVISION.
       0000-MAIN SECTION.
       0000-MAIN-PARA.
           OPEN INPUT FLAT-FILE
           IF WS-FLAT-STATUS NOT = "00"
               DISPLAY "{_LOADER_PROGRAM_ID}: OPEN INPUT FAILED FILE STATUS IS: "
                   WS-FLAT-STATUS
               MOVE {_RC_OPEN_FAILURE} TO RETURN-CODE
               STOP RUN
           END-IF
           OPEN OUTPUT IDX-FILE
           IF WS-IDX-STATUS NOT = "00"
               DISPLAY "{_LOADER_PROGRAM_ID}: OPEN OUTPUT FAILED FILE STATUS IS: "
                   WS-IDX-STATUS
               MOVE {_RC_OPEN_FAILURE} TO RETURN-CODE
               STOP RUN
           END-IF
           PERFORM UNTIL WS-EOF = "Y"
               READ FLAT-FILE
                   AT END
                       MOVE "Y" TO WS-EOF
                   NOT AT END
                       MOVE FLAT-REC TO IDX-REC
                       WRITE IDX-REC
                       IF WS-IDX-STATUS NOT = "00"
                           DISPLAY "{_LOADER_PROGRAM_ID}: WRITE FAILED "
                               "FILE STATUS IS: " WS-IDX-STATUS
                           MOVE {_RC_WRITE_FAILURE} TO RETURN-CODE
                           CLOSE FLAT-FILE
                           CLOSE IDX-FILE
                           STOP RUN
                       END-IF
                       ADD 1 TO WS-COUNT
               END-READ
           END-PERFORM
           CLOSE FLAT-FILE
           CLOSE IDX-FILE
           DISPLAY "{_LOADER_PROGRAM_ID}: LOADED " WS-COUNT " RECORD(S)"
           MOVE 0 TO RETURN-CODE
           STOP RUN.
"""
    return source



def _std_token(std: str) -> str:
    """Return a filesystem-safe token derived from a compiler dialect name.

    Purpose
    -------
    Turn a ``--std`` value such as ``"ibm-strict"`` into a token usable inside a cache
    filename, so the compiled loader binary can be keyed by dialect without worrying
    about characters that are awkward in filenames.

    Parameters
    ----------
    std : str
        The compiler dialect name (the value passed to ``cobc --std=``).

    Returns
    -------
    str
        ``std`` with every non-alphanumeric character replaced by ``_``.

    Raises
    ------
    None
    """
    # WHY (Trade-off): a character-class replacement is simpler and more predictable
    # than hashing -- the resulting cache filename stays human-readable (e.g.
    # ``vsamldr_r300_k11_ibm_strict``) which aids debugging of the loaders cache.
    return "".join(ch if ch.isalnum() else "_" for ch in std)


def _default_cache_dir() -> Path:
    """Return the directory where compiled loader binaries are cached.

    Purpose
    -------
    Provide a stable, reusable location for the compiled loader executables so that
    repeated loads within a test session do not recompile the same loader.

    Parameters
    ----------
    None

    Returns
    -------
    pathlib.Path
        ``$CARDDEMO_BUILD_DIR/loaders`` when the ``CARDDEMO_BUILD_DIR`` environment
        variable is set (so the suite's build artifacts stay together), otherwise
        ``<system-temp>/carddemo_vsam_loaders``.

    Raises
    ------
    None
    """
    # WHY (Trade-off): a *stable* directory (not a fresh mkdtemp per call) is essential
    # -- the compile-once cache only pays off if the binary persists across load_indexed
    # calls within the same session. Honouring CARDDEMO_BUILD_DIR first lets the runner
    # scripts co-locate loaders with the rest of the build output; the temp fallback
    # keeps the helper usable standalone with zero configuration.
    build_dir = os.environ.get("CARDDEMO_BUILD_DIR")
    if build_dir:
        return Path(build_dir) / "loaders"
    return Path(tempfile.gettempdir()) / "carddemo_vsam_loaders"


def _compiled_loader(
    reclen: int,
    key_length: int,
    *,
    std: str,
    cobc: str,
    cache_dir: "str | os.PathLike[str] | None",
) -> str:
    """Compile (or reuse a cached) loader binary for one ``(reclen, key_length, std)``.

    Purpose
    -------
    Return the path to an executable loader built for exactly this record geometry and
    dialect, compiling it with the project ``cobc`` on the first request and reusing the
    cached binary on every subsequent request. Caching keyed on the full tuple
    guarantees a loader built for one layout can never be reused for a different one.

    Parameters
    ----------
    reclen : int
        Fixed record length in characters.
    key_length : int
        Leading primary-key length in characters (``0 < key_length <= reclen``).
    std : str
        Compiler dialect passed to ``cobc --std=`` (keyword-only).
    cobc : str
        ``cobc`` command name or explicit path (keyword-only), resolved via
        :func:`_resolve_cobc`.
    cache_dir : str | os.PathLike | None
        Directory for cached binaries (keyword-only). ``None`` selects
        :func:`_default_cache_dir`.

    Returns
    -------
    str
        Filesystem path to the compiled, executable loader binary.

    Raises
    ------
    VsamLoadError
        If ``cobc`` cannot be resolved, or if compilation of the generated loader fails.
    """
    cobc_path = _resolve_cobc(cobc)
    cache = Path(os.fspath(cache_dir)) if cache_dir is not None else _default_cache_dir()
    cache.mkdir(parents=True, exist_ok=True)

    exe_ext = ".exe" if os.name == "nt" else ""
    final_bin = cache / f"vsamldr_r{reclen}_k{key_length}_{_std_token(std)}{exe_ext}"

    # Cache hit: reuse the already-built binary. WHY: recompiling the same tiny program
    # on every fixture load would dominate the runtime of a large suite; the geometry is
    # fully captured by the filename so a hit is guaranteed correct.
    if final_bin.is_file() and os.access(final_bin, os.X_OK):
        return str(final_bin)

    source = _generate_loader_source(reclen, key_length)

    # Compile inside a private temp directory *within the cache dir*, then atomically
    # publish the finished binary with os.replace.
    # WHY (parallel-safety Trade-off): pytest-xdist runs tests in parallel workers that
    # may request the same loader simultaneously. Building into a per-attempt temp dir
    # and os.replace()-ing the result (an atomic rename on the same filesystem) means a
    # concurrent worker either sees no binary yet or the fully-built one -- never a
    # half-written file. Multiple winners simply overwrite an identical binary.
    with tempfile.TemporaryDirectory(dir=str(cache), prefix=".build-") as tmpd:
        tmp = Path(tmpd)
        src_path = tmp / f"{final_bin.stem}.cbl"
        # The generated source is pure ASCII by construction; encode strictly so any
        # accidental non-ASCII would surface immediately rather than reach the compiler.
        src_path.write_text(source, encoding="ascii")
        tmp_bin = tmp / final_bin.name

        cmd = [cobc_path, "-x", f"--std={std}", "-free", "-o", str(tmp_bin), str(src_path)]
        proc = subprocess.run(cmd, capture_output=True, text=True)

        # WHY (Assumptions): success is judged by the process return code AND the binary
        # existing -- NOT by empty stderr. The gcc backend emits a benign
        # "_FORTIFY_SOURCE redefined" warning on every compile on this runner, so a
        # non-empty stderr is normal and must not be treated as failure.
        if proc.returncode != 0 or not tmp_bin.is_file():
            raise VsamLoadError(
                "failed to compile the GnuCOBOL indexed-file loader "
                f"(reclen={reclen}, key_length={key_length}, std={std!r}); "
                f"cobc exit code {proc.returncode}.\n"
                f"--- cobc stdout ---\n{proc.stdout}\n"
                f"--- cobc stderr ---\n{proc.stderr}\n"
                f"--- generated source ---\n{source}"
            )

        os.replace(str(tmp_bin), str(final_bin))

    return str(final_bin)


def _normalize_to_blob(flat_path: "str | os.PathLike[str]", reclen: int) -> bytes:
    """Read a flat fixture and return a headerless fixed-width blob of its records.

    Purpose
    -------
    Convert a human-authored flat fixture -- whose lines may carry stray ``\\r``, may be
    shorter than ``reclen`` (omitted trailing FILLER), or may be terminated by any line
    ending -- into a deterministic byte blob of exactly ``reclen`` bytes per record with
    no separators, which the generated loader consumes as a fixed record-sequential
    file.

    Parameters
    ----------
    flat_path : str | os.PathLike
        Path to the flat fixture to read.
    reclen : int
        The fixed record length every output record is forced to.

    Returns
    -------
    bytes
        ``reclen * N`` bytes where ``N`` is the number of non-empty input lines, encoded
        Latin-1.

    Raises
    ------
    VsamLoadError
        If the fixture cannot be opened or read.
    """
    parts: list[str] = []
    try:
        # WHY (Trade-off / Alternatives Considered): normalising in Python -- read with
        # universal newlines, strip a trailing CR/LF, then ljust/truncate to reclen --
        # is simpler and more portable than relying on GnuCOBOL LINE SEQUENTIAL
        # auto-padding plus stray-CR handling (the documented alternative). It also lets
        # the generated COBOL read a dead-simple fixed record-sequential file with zero
        # delimiter logic, which removes the single biggest source of load ambiguity.
        # WHY encoding="latin-1": it is the identity byte<->codepoint map for 0..255, so
        # every byte in the fixture round-trips exactly; the space pad byte is 0x20.
        with open(os.fspath(flat_path), "r", encoding="latin-1", newline=None) as fh:
            for raw_line in fh:
                line = raw_line.rstrip("\r\n")
                # WHY (Assumptions): a completely blank line is not a record. Including
                # it would inject a spurious all-spaces key into the indexed file (and a
                # trailing newline at EOF must not create a phantom record). CardDemo
                # keys are account ids / card numbers / group keys and are never blank,
                # so skipping empties is safe and keeps the load deterministic.
                if line == "":
                    continue
                parts.append(line.ljust(reclen)[:reclen])
    except OSError as exc:
        raise VsamLoadError(f"cannot read flat fixture {os.fspath(flat_path)!r}: {exc}") from exc

    return "".join(parts).encode("latin-1")


def _remove_indexed(indexed_path: "str | os.PathLike[str]") -> None:
    """Delete a pre-existing indexed file and its GnuCOBOL companion index files.

    Purpose
    -------
    Implement the ``IDCAMS DELETE ... CLUSTER`` analog (JCL ``STEP05``): before a fresh
    load, remove any target file left by a previous run so ``OPEN OUTPUT`` starts clean,
    and remove any sidecar/alternate-index companion files so a stale companion can
    never shadow the new data.

    Parameters
    ----------
    indexed_path : str | os.PathLike
        Path of the primary indexed file to remove.

    Returns
    -------
    None

    Raises
    ------
    None
        Absence of the target (and of any companion) is tolerated silently -- exactly as
        the JCL uses ``IF MAXCC LE 08 THEN SET MAXCC = 0`` to ignore a delete of a file
        that does not yet exist.
    """
    primary = Path(os.fspath(indexed_path))
    try:
        primary.unlink()
    except FileNotFoundError:
        pass  # DELETE analog: tolerate an absent target.

    parent = primary.parent
    prefix = primary.name + "."
    if not parent.exists():
        return
    # WHY (Alternatives Considered): iterdir + explicit suffix test is used instead of a
    # glob so that (a) glob metacharacters in the file name cannot cause surprises and
    # (b) only true companion files are removed -- ``<name>.idx`` (VBISAM builds) and
    # ``<name>.<digits>`` (BDB alternate-key indexes) -- never an unrelated neighbour.
    for sibling in parent.iterdir():
        if not sibling.name.startswith(prefix):
            continue
        suffix = sibling.name[len(prefix):]
        if suffix in _INDEX_COMPANION_LITERAL_SUFFIXES or suffix.isdigit():
            try:
                sibling.unlink()
            except FileNotFoundError:
                pass


def load_indexed(
    flat_path: "str | os.PathLike[str]",
    indexed_path: "str | os.PathLike[str]",
    reclen: int,
    key_length: int,
    key_offset: int = 0,
    *,
    cobc: str = "cobc",
    std: str = "ibm-strict",
    cache_dir: "str | os.PathLike[str] | None" = None,
) -> str:
    """Load a flat fixed-width fixture into a GnuCOBOL native indexed (ISAM) file.

    Purpose
    -------
    The single public entry point of this module and the automated analog of the
    mainframe ``IDCAMS DELETE -> DEFINE -> REPRO`` file-load pattern. Given a flat
    fixture and a target path, it produces a native GnuCOBOL ``ORGANIZATION IS INDEXED``
    file -- keyed on the leading ``key_length`` bytes -- that a program under test can
    open and read.

    Parameters
    ----------
    flat_path : str | os.PathLike
        Path to the flat fixed-width fixture (one record per line; short lines are
        space-padded, long lines truncated, stray CR/LF stripped).
    indexed_path : str | os.PathLike
        Destination path for the indexed file. Any pre-existing file at this path (and
        its companion index files) is deleted first.
    reclen : int
        Fixed record length in characters. Must be a positive integer.
    key_length : int
        Length in characters of the leading primary key. Must satisfy
        ``0 < key_length <= reclen``.
    key_offset : int, optional
        Zero-based offset of the key within the record. Only ``0`` is supported -- see
        Raises. Defaults to ``0``.
    cobc : str, optional
        ``cobc`` command name or explicit path used to build the loader (keyword-only).
        Defaults to ``"cobc"``. **Must be the same compiler that builds the programs
        under test**, because only that compiler writes an indexed file those programs
        can read.
    std : str, optional
        Compiler dialect passed to ``cobc --std=`` (keyword-only). Defaults to
        ``"ibm-strict"`` to match the repository's compile convention.
    cache_dir : str | os.PathLike | None, optional
        Directory in which the compiled loader binary is cached (keyword-only).
        Defaults to :func:`_default_cache_dir`.

    Returns
    -------
    str
        ``os.fspath(indexed_path)`` -- the path of the freshly written indexed file.

    Raises
    ------
    VsamLoadError
        If ``key_offset`` is non-zero (this codebase's keys are all at offset 0, and the
        generated loader hard-codes a leading key -- so a non-zero offset is refused
        loudly rather than silently mis-keying the file); if ``reclen`` is not positive
        or ``key_length`` is outside ``0 < key_length <= reclen``; if the fixture cannot
        be read; if the loader fails to compile; or if the loader run exits non-zero
        (e.g. a duplicate key or an I/O failure), in which case the loader's
        ``FILE STATUS IS: NNNN`` diagnostic is included in the message.
    """
    # --- Argument validation (fail loud, fail early) -----------------------------
    if key_offset != 0:
        # WHY: the generated FD assumes IDX-KEY is the leading field. Honouring a
        # non-zero offset would require a different record layout; rather than emit a
        # file whose key silently points at the wrong bytes, refuse the request.
        raise VsamLoadError(
            f"key_offset={key_offset} is not supported: every CardDemo indexed file "
            "keys on its leading bytes (offset 0), and the generated loader assumes a "
            "leading key. A non-zero key_offset would produce a mis-keyed file."
        )
    if not isinstance(reclen, int) or reclen <= 0:
        raise VsamLoadError(f"reclen must be a positive integer, got {reclen!r}")
    if not isinstance(key_length, int) or not (0 < key_length <= reclen):
        raise VsamLoadError(
            f"key_length must satisfy 0 < key_length <= reclen "
            f"(reclen={reclen}, key_length={key_length!r})"
        )

    flat = os.fspath(flat_path)
    indexed = os.fspath(indexed_path)

    # --- DELETE analog: scrub any pre-existing target + companions ---------------
    _remove_indexed(indexed)

    # The loader's OPEN OUTPUT cannot create missing parent directories, so ensure the
    # destination directory exists before we run it.
    Path(indexed).parent.mkdir(parents=True, exist_ok=True)

    # --- Normalise the fixture into a headerless fixed-width blob ----------------
    blob = _normalize_to_blob(flat, reclen)

    # --- DEFINE + REPRO analog: build (or reuse) the loader and run it -----------
    loader_bin = _compiled_loader(reclen, key_length, std=std, cobc=cobc, cache_dir=cache_dir)

    blob_path: "str | None" = None
    try:
        # Materialise the blob in a temp file that the child reads via the FLATIN env
        # var. WHY a temp file (not a pipe/stdin): GnuCOBOL binds a SELECT to a named
        # file, and a real file keeps the loader trivially simple and re-runnable.
        fd, blob_path = tempfile.mkstemp(prefix="vsamldr-blob-", suffix=".dat")
        with os.fdopen(fd, "wb") as blob_file:
            blob_file.write(blob)

        # Bind the generated program's ASSIGN names to the physical paths via the child
        # environment (GnuCOBOL's env-var filename mapping). Copy the current
        # environment so the child keeps its normal runtime configuration.
        child_env = os.environ.copy()
        child_env[_DD_FLAT_IN] = blob_path
        child_env[_DD_INDEX_OUT] = indexed

        proc = subprocess.run(
            [loader_bin],
            env=child_env,
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            raise VsamLoadError(
                f"indexed-file loader failed for {flat!r} -> {indexed!r} "
                f"(reclen={reclen}, key_length={key_length}); exit code "
                f"{proc.returncode}.\n"
                f"--- loader stdout ---\n{proc.stdout}\n"
                f"--- loader stderr ---\n{proc.stderr}"
            )
    finally:
        # Always remove the transient blob; it has served its purpose once the loader
        # has run (success or failure). Absence is tolerated.
        if blob_path is not None:
            try:
                os.unlink(blob_path)
            except FileNotFoundError:
                pass

    return indexed


def geometry_for(layout_name: str) -> "tuple[int, int]":
    """Resolve ``(reclen, key_length)`` for a named record layout via ``record_codec``.

    Purpose
    -------
    Convenience for callers (and the CLI) that would rather name a known CardDemo record
    layout than restate its geometry. It defers to the single source of truth for record
    structure, :mod:`tests.helpers.record_codec`, so the loader never duplicates the
    reclen/key numbers that copybooks define.

    Parameters
    ----------
    layout_name : str
        A logical record name registered in :data:`tests.helpers.record_codec.LAYOUTS`
        (e.g. ``"ACCOUNT"``, ``"DALYTRAN"``, ``"DISGROUP"``).

    Returns
    -------
    tuple[int, int]
        ``(reclen, key_length)`` for the named layout.

    Raises
    ------
    KeyError
        If ``layout_name`` is not a registered layout (propagated from
        :func:`record_codec.reclen_of`).
    ImportError
        If :mod:`tests.helpers.record_codec` cannot be imported (e.g. the repository
        root is not on ``PYTHONPATH``).
    """
    # WHY (lazy import): record_codec is only needed for this by-name convenience, and
    # importing it lazily keeps vsam_loader's module import free of internal deps (see
    # the top-of-file import-whitelist note) and avoids any import-cycle risk within the
    # helpers package.
    from tests.helpers.record_codec import keylen_of, reclen_of

    return reclen_of(layout_name), keylen_of(layout_name)



# ---------------------------------------------------------------------------
# Command-line interface.
#
# WHY (single source of truth): the shell wrapper ``tests/helpers/load_indexed.sh``
# delegates to ``python3 -m tests.helpers.vsam_loader`` rather than reimplementing the
# load logic in bash. Keeping the CLI here means there is exactly one implementation of
# the DELETE -> DEFINE -> REPRO flow, and the bash wrapper stays thin and hard to get
# wrong.
# ---------------------------------------------------------------------------


def _build_arg_parser() -> argparse.ArgumentParser:
    """Construct the :class:`argparse.ArgumentParser` for the module CLI.

    Purpose
    -------
    Define the command-line contract ``<flat> <indexed> <reclen> <key_length>
    [key_offset]`` (plus the ``--layout`` convenience and ``--cobc``/``--std``/
    ``--cache-dir`` options) in one place so both :func:`main` and its tests share the
    same parser.

    Parameters
    ----------
    None

    Returns
    -------
    argparse.ArgumentParser
        A fully configured parser. ``reclen``/``key_length`` are optional at the
        argparse level so that ``--layout`` may supply them; :func:`main` enforces that
        exactly one of the two ways of specifying geometry is used.

    Raises
    ------
    None
    """
    parser = argparse.ArgumentParser(
        prog="python3 -m tests.helpers.vsam_loader",
        description=(
            "Load a flat fixed-width fixture into a GnuCOBOL native indexed (ISAM) "
            "file -- the automated analog of IDCAMS DELETE -> DEFINE -> REPRO. The "
            "indexed file is written by a loader compiled with the SAME cobc that "
            "builds the programs under test, which is the only way to guarantee those "
            "programs can read it."
        ),
        epilog=(
            "geometry may be given explicitly (reclen + key_length) or resolved from a "
            "record_codec layout name via --layout (e.g. --layout DISGROUP). "
            "Only key_offset 0 is supported (CardDemo keys are all leading)."
        ),
    )
    parser.add_argument("flat", help="path to the flat fixed-width fixture to load")
    parser.add_argument("indexed", help="destination path for the indexed file")
    # reclen/key_length are optional at parse time (type=int) so that --layout can
    # supply them; main() validates that exactly one mechanism was used.
    parser.add_argument(
        "reclen", nargs="?", type=int, default=None,
        help="fixed record length in characters (omit when using --layout)",
    )
    parser.add_argument(
        "key_length", nargs="?", type=int, default=None,
        help="leading primary-key length in characters (omit when using --layout)",
    )
    parser.add_argument(
        "key_offset", nargs="?", type=int, default=0,
        help="key offset within the record; only 0 is supported (default: 0)",
    )
    parser.add_argument(
        "--layout", default=None, metavar="NAME",
        help="record_codec layout name to derive reclen/key_length (e.g. ACCOUNT, "
             "DALYTRAN, DISGROUP); mutually exclusive with positional reclen/key_length",
    )
    parser.add_argument(
        "--cobc", default="cobc",
        help="cobc command name or path used to build the loader (default: cobc)",
    )
    parser.add_argument(
        "--std", default="ibm-strict",
        help="cobc dialect passed to --std (default: ibm-strict)",
    )
    parser.add_argument(
        "--cache-dir", default=None, dest="cache_dir", metavar="DIR",
        help="directory for the cached loader binary "
             "(default: $CARDDEMO_BUILD_DIR/loaders or a system temp dir)",
    )
    return parser


def main(argv: "list[str] | None" = None) -> int:
    """Run the loader as a command-line program and return a process exit code.

    Purpose
    -------
    Parse command-line arguments, invoke :func:`load_indexed`, print the resulting
    indexed-file path to stdout on success, and translate any failure into a clear
    stderr message and a non-zero exit code -- so ``python3 -m tests.helpers.vsam_loader``
    (and the ``load_indexed.sh`` wrapper that calls it) behave like a well-mannered CLI.

    Parameters
    ----------
    argv : list[str] | None, optional
        Argument vector to parse (excluding the program name). ``None`` (the default)
        uses :data:`sys.argv`, matching normal command-line invocation while letting
        tests pass an explicit vector.

    Returns
    -------
    int
        ``0`` on success; ``1`` on a :class:`VsamLoadError` (bad input, compile failure,
        or loader run failure); ``2`` on a usage/geometry-resolution error such as an
        unknown ``--layout`` name or a missing ``record_codec`` import.

    Raises
    ------
    SystemExit
        Propagated from :meth:`argparse.ArgumentParser.parse_args` for ``-h``/``--help``
        (exit 0) and for argument-syntax errors (exit 2), per argparse convention.
    """
    parser = _build_arg_parser()
    args = parser.parse_args(argv)

    # Resolve record geometry from exactly one source: --layout OR positional numbers.
    if args.layout is not None:
        # WHY: combining --layout with positional reclen/key_length is ambiguous, so we
        # reject it rather than silently preferring one over the other.
        if args.reclen is not None or args.key_length is not None:
            parser.error("--layout cannot be combined with positional reclen/key_length")
        try:
            reclen, key_length = geometry_for(args.layout)
        except KeyError as exc:
            print(f"error: unknown --layout {args.layout!r}: {exc}", file=sys.stderr)
            return 2
        except ImportError as exc:
            print(
                f"error: cannot import tests.helpers.record_codec to resolve --layout "
                f"{args.layout!r}: {exc} (is the repo root on PYTHONPATH?)",
                file=sys.stderr,
            )
            return 2
        key_offset = args.key_offset
    else:
        if args.reclen is None or args.key_length is None:
            parser.error("reclen and key_length are required unless --layout is given")
        reclen, key_length = args.reclen, args.key_length
        key_offset = args.key_offset

    try:
        result = load_indexed(
            args.flat,
            args.indexed,
            reclen,
            key_length,
            key_offset,
            cobc=args.cobc,
            std=args.std,
            cache_dir=args.cache_dir,
        )
    except VsamLoadError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    # On success, echo the indexed-file path so a caller (e.g. load_indexed.sh) can
    # capture it from stdout.
    print(result)
    return 0


if __name__ == "__main__":
    # WHY: delegate to main() and forward its return value to sys.exit so the process
    # exit code reflects success/failure -- essential for the CI-consumable runner
    # scripts that key off the exit status.
    sys.exit(main())

