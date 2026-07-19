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

How a load proceeds (mirrors the JCL, but validate-first)
---------------------------------------------------------
1. **Validate the fixture (no silent repair)** -- the flat fixture is read in Python and
   each physical line is stripped of a single trailing ``\\r``/``\\n`` and then required
   to be *exactly* ``reclen`` characters. A short, long, or blank row is **rejected**
   (never padded, truncated, or dropped); a genuinely zero-byte file legitimately yields
   zero records (an empty index). The validated rows are concatenated into a *headerless
   fixed-width blob* (``reclen * N`` bytes, no record separators). This mirrors the
   strict contract of :mod:`tests.helpers.record_codec`. See :func:`_validated_blob`.
2. **DEFINE + REPRO analog (into a private workspace)** -- a generated COBOL program (see
   :func:`_generate_loader_source`) reads the blob as a fixed-length record-sequential
   file and WRITEs each record into an ``ORGANIZATION IS INDEXED`` file -- with the
   appropriate ``RECORD KEY`` and any ``ALTERNATE RECORD KEY`` clauses -- built inside a
   fresh per-run workspace directory, not directly at the target path. This is JCL
   ``STEP10``'s ``DEFINE CLUSTER ... INDEXED`` and ``STEP15``'s ``REPRO`` collapsed into
   one deterministic pass.
3. **DELETE + publish analog (validate-first)** -- only after the loader has succeeded
   and produced a file is any pre-existing target (and its GnuCOBOL companion/index
   sidecar files) removed -- exactly as JCL ``STEP05`` issues ``DELETE ... CLUSTER`` and
   tolerates an absent target -- and the freshly built primary + companions are then
   atomically moved into place. WHY the DELETE is deferred to the end (not run first): a
   failed or timed-out load must never destroy a previously good indexed file or leave a
   half-written one at the target path (MA-10). See :func:`_publish_indexed`.

Key geometry (primary AND alternate indexes)
--------------------------------------------
Most CardDemo primary ``RECORD KEY``s are at offset 0, but **this is no longer assumed**:
the loader models the primary key by ``(key_offset, key_length)`` and accepts zero or
more :class:`~tests.helpers.record_codec.AlternateKey` descriptors. The generated record
is carved into named subfields at the exact key positions, and one ``ALTERNATE RECORD
KEY ... WITH DUPLICATES`` clause is emitted per alternate key. This is required because
``CBACT04C`` reads the cross-reference file by its **alternate** account-id key
(offset 25), which the earlier offset-0-only loader could not build.

Explainability
--------------
Per the project's mandatory Explainability rule, every public and private symbol below
carries a docstring stating Purpose / Parameters / Returns / Raises, and each
non-obvious decision is annotated with a WHY comment documenting at least one of
Alternatives Considered, Assumptions, or Trade-offs.
"""

from __future__ import annotations

import argparse
import hashlib
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
    "unload_indexed",
    "unload_indexed_bytes",
    "geometry_for",
    "alternate_keys_for",
]

# Default wall-clock ceilings (seconds) for the two child subprocesses this module
# spawns. WHY (MA-10 / Reliability): an un-bounded ``cobc`` compile or loader run could
# hang a CI job indefinitely (e.g. a wedged compiler, a pathological fixture). Bounding
# both with a generous-but-finite timeout turns a hang into a clear, catchable failure.
# The values are deliberately generous (a normal compile/run is well under a second) so
# they never trip on a slow shared runner, yet still cap a genuine hang.
_COMPILE_TIMEOUT_S = 120
_RUN_TIMEOUT_S = 120

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
_DD_FLAT_IN = "FLATIN"    # generated loader:   ``SELECT FLAT-FILE ASSIGN TO FLATIN``
_DD_INDEX_OUT = "IDXOUT"  # generated loader:   ``SELECT IDX-FILE  ASSIGN TO IDXOUT``
_DD_INDEX_IN = "IDXIN"    # generated unloader: ``SELECT IDX-FILE  ASSIGN TO IDXIN``
_DD_FLAT_OUT = "FLATOUT"  # generated unloader: ``SELECT FLAT-FILE ASSIGN TO FLATOUT``

# Program-id of the generated loader. A fixed name is safe because each distinct
# (reclen, key_length, std) combination is compiled to its own standalone executable;
# the program-id is irrelevant to a ``-x`` built binary and never collides on disk.
_LOADER_PROGRAM_ID = "VSAMLDR"

# Program-id of the generated *unloader* (indexed -> flat). Same rationale as the loader:
# each geometry is a distinct content-addressed binary, so a fixed program-id is safe.
_UNLOADER_PROGRAM_ID = "VSAMULD"

# Version marker for the generated loader source. WHY (MA-10 cache identity): this is
# folded into the compiler fingerprint so that whenever the generator's emitted source
# format changes (e.g. the segmented alternate-key layout added for CR-02), previously
# cached binaries are invalidated even if the cobc banner and --std are unchanged. Bump
# this string on any change to _generate_loader_source's output.
_LOADER_SOURCE_VERSION = "2-segmented-altkeys"

# Version marker embedded in the generated *unloader* source. WHY: unlike the loader
# (whose version rides in the compiler fingerprint), the unloader stamps its version as a
# source comment so it is captured directly by the content-addressed source digest -- a
# bump changes the emitted bytes and therefore the cache filename, invalidating any stale
# unloader binary without disturbing the independently keyed loader cache. The unloader's
# and loader's generated sources are always textually distinct, so even sharing a cache
# directory and compiler fingerprint they can never collide (their source digests differ).
_UNLOADER_SOURCE_VERSION = "1-primary-key-order"

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


def _record_segments(
    reclen: int,
    key_offset: int,
    key_length: int,
    alternate_keys: "tuple[tuple[int, int, bool], ...]",
) -> "tuple[list[tuple[str | None, int]], str, list[str]]":
    """Carve a record into contiguous named subfields for the primary and alternate keys.

    Purpose
    -------
    Produce the ordered field breakdown of the generated ``01 IDX-REC`` so that every
    key (primary and each alternate) is a distinct, named ``PIC X(n)`` subfield that a
    ``RECORD KEY`` / ``ALTERNATE RECORD KEY`` clause can reference, with the gaps between
    keys represented as unnamed ``FILLER``. COBOL keys must name a data item, so a
    contiguous named layout is the cleanest way to place a key at an arbitrary offset
    without reference modification (which is not allowed in a KEY clause).

    Parameters
    ----------
    reclen : int
        Total record length in characters.
    key_offset : int
        Zero-based offset of the primary key.
    key_length : int
        Length of the primary key.
    alternate_keys : tuple[tuple[int, int, bool], ...]
        Each alternate key as ``(offset, length, with_duplicates)``.

    Returns
    -------
    tuple[list[tuple[str | None, int]], str, list[str]]
        ``(segments, primary_name, alt_names)`` where ``segments`` is the ordered list
        of ``(field_name_or_None_for_filler, length)`` covering ``[0, reclen)``,
        ``primary_name`` is the primary-key field name, and ``alt_names`` are the
        alternate-key field names in declaration order.

    Raises
    ------
    VsamLoadError
        If any key span is out of range or two key spans overlap (which would make a
        clean contiguous named layout impossible; CardDemo's keys never overlap).
    """
    primary_name = "IDX-KEY"
    # (name, offset, length) for every key; alternate names are IDX-ALT-1, IDX-ALT-2 ...
    spans: list[tuple[str, int, int]] = [(primary_name, key_offset, key_length)]
    alt_names: list[str] = []
    for i, (off, ln, _dup) in enumerate(alternate_keys, start=1):
        if ln <= 0 or off < 0 or off + ln > reclen:
            raise VsamLoadError(
                f"alternate key #{i} (offset={off}, length={ln}) is out of range for "
                f"reclen={reclen}"
            )
        name = f"IDX-ALT-{i}"
        spans.append((name, off, ln))
        alt_names.append(name)

    # Sort by offset and verify the key spans do not overlap. WHY (Assumption): the
    # CardDemo indexed files never overlap their keys, so a clean contiguous group is
    # sufficient; overlapping keys would require a REDEFINES scheme we deliberately do
    # not add until a real layout needs it (YAGNI trade-off, surfaced loudly if violated).
    spans_sorted = sorted(spans, key=lambda s: s[1])
    for a, b in zip(spans_sorted, spans_sorted[1:]):
        if a[1] + a[2] > b[1]:
            raise VsamLoadError(
                f"key fields {a[0]} and {b[0]} overlap in the record "
                f"({a[0]} @ {a[1]}+{a[2]}, {b[0]} @ {b[1]}+{b[2]}); overlapping keys "
                "are not supported by the generated contiguous layout"
            )

    segments: list[tuple[str | None, int]] = []
    cursor = 0
    for name, off, ln in spans_sorted:
        if off > cursor:
            segments.append((None, off - cursor))  # FILLER gap before this key
        segments.append((name, ln))
        cursor = off + ln
    if cursor < reclen:
        segments.append((None, reclen - cursor))  # trailing FILLER
    return segments, primary_name, alt_names


def _generate_loader_source(
    reclen: int,
    key_length: int,
    key_offset: int = 0,
    alternate_keys: "tuple[tuple[int, int, bool], ...]" = (),
) -> str:
    """Return the COBOL source text of a loader for one record geometry.

    Purpose
    -------
    Produce a minimal, dialect-agnostic, free-format COBOL program that copies a
    headerless fixed-width record-sequential blob into a native GnuCOBOL
    ``ORGANIZATION IS INDEXED`` file with the given primary key and any alternate keys.
    This is the compiled engine behind :func:`load_indexed` -- the automated equivalent
    of JCL ``DEFINE CLUSTER ... INDEXED`` + ``IDCAMS REPRO``.

    The generated program:

    * reads ``FLAT-FILE`` (``ASSIGN TO FLATIN``) as ``ORGANIZATION IS SEQUENTIAL`` with
      fixed ``reclen``-character records -- i.e. it consumes exactly ``reclen`` bytes
      per record from the blob with no delimiter handling;
    * writes ``IDX-FILE`` (``ASSIGN TO IDXOUT``) as ``ORGANIZATION IS INDEXED``,
      ``ACCESS MODE IS DYNAMIC``, ``RECORD KEY IS IDX-KEY`` plus one
      ``ALTERNATE RECORD KEY`` clause per alternate key, opened ``OUTPUT``;
    * checks ``FILE STATUS`` after every OPEN and WRITE, emitting a
      ``FILE STATUS IS: NNNN`` diagnostic and a non-zero ``RETURN-CODE`` on any error
      (mirroring the fail-fast convention of the production batch programs).

    Parameters
    ----------
    reclen : int
        Fixed record length in characters. Must be a positive integer.
    key_length : int
        Length in characters of the primary key. Must satisfy ``0 < key_length``.
    key_offset : int
        Zero-based offset of the primary key within the record. Defaults to 0.
    alternate_keys : tuple[tuple[int, int, bool], ...]
        Zero or more ``(offset, length, with_duplicates)`` alternate-key descriptors.

    Returns
    -------
    str
        The complete COBOL source (free format), ready to be written to a ``.cbl`` file
        and compiled with ``cobc -x -free``.

    Raises
    ------
    VsamLoadError
        If ``reclen``/``key_length``/``key_offset`` violate the positive-``reclen`` /
        ``0 < key_length`` / ``0 <= key_offset`` / ``key_offset+key_length <= reclen``
        contract, or if any key span is out of range or overlaps another. (Validated
        here as well as in :func:`load_indexed` so the generator is safe to call
        directly from tests.)
    """
    if reclen <= 0:
        raise VsamLoadError(f"reclen must be a positive integer, got {reclen!r}")
    if key_length <= 0 or key_offset < 0 or key_offset + key_length > reclen:
        raise VsamLoadError(
            f"primary key must satisfy 0 < key_length and 0 <= key_offset and "
            f"key_offset+key_length <= reclen "
            f"(reclen={reclen}, key_offset={key_offset}, key_length={key_length})"
        )

    segments, _primary_name, alt_names = _record_segments(
        reclen, key_offset, key_length, alternate_keys
    )

    # Build the ``01 IDX-REC`` field lines from the carved segments. Named key fields get
    # their name; gaps become FILLER. WHY: a group move (MOVE FLAT-REC TO IDX-REC) copies
    # the bytes straight through regardless of the subfield breakdown, so the named keys
    # are purely to satisfy the RECORD KEY / ALTERNATE RECORD KEY clauses.
    field_lines = ["       01 IDX-REC."]
    for name, ln in segments:
        field_name = name if name is not None else "FILLER"
        field_lines.append(f"          05 {field_name:<10} PIC X({ln}).")
    idx_rec_layout = "\n".join(field_lines) + "\n"

    # Build the ALTERNATE RECORD KEY clauses (one per alternate key), preserving the
    # WITH DUPLICATES flag. WHY (CR-02): CBACT04C reads XREF by its account-id alternate
    # key; without these clauses the file has no secondary index and the read fails.
    alt_clause_lines = []
    for name, (_off, _ln, dup) in zip(alt_names, alternate_keys):
        dup_clause = " WITH DUPLICATES" if dup else ""
        alt_clause_lines.append(
            f"               ALTERNATE RECORD KEY IS {name}{dup_clause}"
        )
    alt_key_clauses = ("\n".join(alt_clause_lines) + "\n") if alt_clause_lines else ""

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
{alt_key_clauses}               FILE STATUS IS WS-IDX-STATUS.
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


def _generate_unloader_source(
    reclen: int,
    key_length: int,
    key_offset: int = 0,
    alternate_keys: "tuple[tuple[int, int, bool], ...]" = (),
) -> str:
    """Return the COBOL source text of an *unloader* for one record geometry.

    Purpose
    -------
    Produce a minimal, dialect-agnostic, free-format COBOL program that reads a native
    GnuCOBOL ``ORGANIZATION IS INDEXED`` file **sequentially in ascending primary-key
    order** and copies every logical record -- unchanged and unpadded -- to a headerless
    fixed-width record-sequential blob. This is the compiled engine behind
    :func:`unload_indexed`: the read-back counterpart of :func:`_generate_loader_source`,
    and the automated analog of an ``IDCAMS REPRO`` that dumps an indexed cluster to a
    flat sequential dataset. It lets the golden-master comparator verify the *logical*
    content of an indexed output (ACCTFILE, TCATBAL, TRANFILE) without depending on the
    opaque, unstable on-disk byte layout of the ISAM container.

    The generated program:

    * reads ``IDX-FILE`` (``ASSIGN TO IDXIN``) as ``ORGANIZATION IS INDEXED``,
      ``ACCESS MODE IS SEQUENTIAL``, ``RECORD KEY IS IDX-KEY`` plus one
      ``ALTERNATE RECORD KEY`` clause per alternate key, opened ``INPUT``. Sequential
      ``READ ... NEXT`` on an indexed file returns records in ascending primary-key
      order, which is what makes the unload deterministic;
    * writes ``FLAT-FILE`` (``ASSIGN TO FLATOUT``) as ``ORGANIZATION IS SEQUENTIAL`` with
      fixed ``reclen``-character records and no separators -- exactly the framing the
      loader consumes, so a load->unload round-trip is byte-faithful;
    * checks ``FILE STATUS`` after every OPEN and WRITE, emitting a
      ``FILE STATUS IS: NNNN`` diagnostic and a non-zero ``RETURN-CODE`` on any error
      (mirroring the fail-fast convention of the production batch programs).

    WHY declare the same alternate keys as the loader (Assumption): a GnuCOBOL indexed
    file records how many keys it was built with; opening it INPUT with a mismatched key
    set can fail FILE STATUS 39 (conflicting fixed file attributes). The unloader is
    therefore given the *same* geometry (primary + alternates) that built the file, even
    though it only ever navigates by the primary key. CardDemo's three indexed *outputs*
    (ACCTFILE, TCATBAL, TRANFILE) are primary-key only, so in practice no alternate clause
    is emitted; the parameter exists so an alt-keyed file can still be unloaded.

    Parameters
    ----------
    reclen : int
        Fixed record length in characters. Must be a positive integer.
    key_length : int
        Length in characters of the primary key. Must satisfy ``0 < key_length``.
    key_offset : int
        Zero-based offset of the primary key within the record. Defaults to 0.
    alternate_keys : tuple[tuple[int, int, bool], ...]
        Zero or more ``(offset, length, with_duplicates)`` alternate-key descriptors that
        the file was built with (declared so the OPEN INPUT key set matches the file).

    Returns
    -------
    str
        The complete COBOL source (free format), ready to be written to a ``.cbl`` file
        and compiled with ``cobc -x -free``.

    Raises
    ------
    VsamLoadError
        If ``reclen``/``key_length``/``key_offset`` violate the positive-``reclen`` /
        ``0 < key_length`` / ``0 <= key_offset`` / ``key_offset+key_length <= reclen``
        contract, or if any key span is out of range or overlaps another.
    """
    if reclen <= 0:
        raise VsamLoadError(f"reclen must be a positive integer, got {reclen!r}")
    if key_length <= 0 or key_offset < 0 or key_offset + key_length > reclen:
        raise VsamLoadError(
            f"primary key must satisfy 0 < key_length and 0 <= key_offset and "
            f"key_offset+key_length <= reclen "
            f"(reclen={reclen}, key_offset={key_offset}, key_length={key_length})"
        )

    # Reuse the exact same record-carving used by the loader so the IDX-REC layout (named
    # primary/alternate key fields + FILLER gaps) is identical on both sides -- a single
    # source of truth for the record geometry (Refactoring Rationale).
    segments, _primary_name, alt_names = _record_segments(
        reclen, key_offset, key_length, alternate_keys
    )

    field_lines = ["       01 IDX-REC."]
    for name, ln in segments:
        field_name = name if name is not None else "FILLER"
        field_lines.append(f"          05 {field_name:<10} PIC X({ln}).")
    idx_rec_layout = "\n".join(field_lines) + "\n"

    alt_clause_lines = []
    for name, (_off, _ln, dup) in zip(alt_names, alternate_keys):
        dup_clause = " WITH DUPLICATES" if dup else ""
        alt_clause_lines.append(
            f"               ALTERNATE RECORD KEY IS {name}{dup_clause}"
        )
    alt_key_clauses = ("\n".join(alt_clause_lines) + "\n") if alt_clause_lines else ""

    # WHY (Trade-off): as with the loader, an f-string template is used rather than a
    # COPY/REPLACING indirection -- there is one tiny, self-contained program shape and a
    # template keeps it readable and faithful to the REPRO semantics. The ``source-version``
    # comment embeds _UNLOADER_SOURCE_VERSION into the emitted bytes so the content-address
    # digest self-invalidates when this generator changes.
    source = f"""\
      *> ==================================================================
      *> {_UNLOADER_PROGRAM_ID} -- generated INDEXED -> flat unloader (do not edit).
      *> source-version: {_UNLOADER_SOURCE_VERSION}
      *>
      *> Purpose : read a native GnuCOBOL ORGANIZATION IS INDEXED file in ascending
      *>           PRIMARY-key order and copy every logical record, unchanged and
      *>           unpadded, to a headerless fixed-width record-sequential blob of
      *>           {reclen}-byte records. Automated analog of an IDCAMS REPRO that
      *>           dumps an indexed cluster to a flat sequential dataset, used to
      *>           verify the LOGICAL content of an indexed output independent of the
      *>           opaque ISAM on-disk byte layout.
      *>
      *> WHY ACCESS MODE IS SEQUENTIAL + READ NEXT: on an indexed file this walks the
      *>   records in ascending primary-key order, giving a deterministic, sorted dump
      *>   regardless of the order in which the program under test wrote them -- exactly
      *>   what a byte-stable golden comparison needs.
      *> ==================================================================
       IDENTIFICATION DIVISION.
       PROGRAM-ID. {_UNLOADER_PROGRAM_ID}.
       ENVIRONMENT DIVISION.
       INPUT-OUTPUT SECTION.
       FILE-CONTROL.
      *> ASSIGN names are resolved from same-named environment variables at run
      *> time (set by unload_indexed): {_DD_INDEX_IN} -> input indexed file,
      *> {_DD_FLAT_OUT} -> output flat blob.
           SELECT IDX-FILE ASSIGN TO {_DD_INDEX_IN}
               ORGANIZATION IS INDEXED
               ACCESS MODE IS SEQUENTIAL
               RECORD KEY IS IDX-KEY
{alt_key_clauses}               FILE STATUS IS WS-IDX-STATUS.
           SELECT FLAT-FILE ASSIGN TO {_DD_FLAT_OUT}
               ORGANIZATION IS SEQUENTIAL
               ACCESS MODE IS SEQUENTIAL
               FILE STATUS IS WS-FLAT-STATUS.
       DATA DIVISION.
       FILE SECTION.
       FD IDX-FILE
           RECORD CONTAINS {reclen} CHARACTERS.
{idx_rec_layout}       FD FLAT-FILE
           RECORD CONTAINS {reclen} CHARACTERS.
       01 FLAT-REC PIC X({reclen}).
       WORKING-STORAGE SECTION.
      *> Two-byte FILE STATUS receivers; "00"/"02" mean the last I/O succeeded ("02" is
      *> a duplicate-alternate-key info status on READ and is treated as success).
       01 WS-IDX-STATUS  PIC XX VALUE "00".
       01 WS-FLAT-STATUS PIC XX VALUE "00".
       01 WS-EOF         PIC X  VALUE "N".
      *> Unloaded-record counter, echoed on success for auditability.
       01 WS-COUNT       PIC 9(9) VALUE 0.
       PROCEDURE DIVISION.
       0000-MAIN SECTION.
       0000-MAIN-PARA.
           OPEN INPUT IDX-FILE
           IF WS-IDX-STATUS NOT = "00"
               DISPLAY "{_UNLOADER_PROGRAM_ID}: OPEN INPUT FAILED FILE STATUS IS: "
                   WS-IDX-STATUS
               MOVE {_RC_OPEN_FAILURE} TO RETURN-CODE
               STOP RUN
           END-IF
           OPEN OUTPUT FLAT-FILE
           IF WS-FLAT-STATUS NOT = "00"
               DISPLAY "{_UNLOADER_PROGRAM_ID}: OPEN OUTPUT FAILED FILE STATUS IS: "
                   WS-FLAT-STATUS
               MOVE {_RC_OPEN_FAILURE} TO RETURN-CODE
               STOP RUN
           END-IF
           PERFORM UNTIL WS-EOF = "Y"
               READ IDX-FILE NEXT RECORD
                   AT END
                       MOVE "Y" TO WS-EOF
                   NOT AT END
                       MOVE IDX-REC TO FLAT-REC
                       WRITE FLAT-REC
                       IF WS-FLAT-STATUS NOT = "00"
                           DISPLAY "{_UNLOADER_PROGRAM_ID}: WRITE FAILED "
                               "FILE STATUS IS: " WS-FLAT-STATUS
                           MOVE {_RC_WRITE_FAILURE} TO RETURN-CODE
                           CLOSE IDX-FILE
                           CLOSE FLAT-FILE
                           STOP RUN
                       END-IF
                       ADD 1 TO WS-COUNT
               END-READ
           END-PERFORM
           CLOSE IDX-FILE
           CLOSE FLAT-FILE
           DISPLAY "{_UNLOADER_PROGRAM_ID}: UNLOADED " WS-COUNT " RECORD(S)"
           MOVE 0 TO RETURN-CODE
           STOP RUN.
"""
    return source


def _default_cache_dir() -> Path:
    """Return the directory where compiled loader binaries are cached.

    Purpose
    -------
    Provide a stable, reusable, *per-user private* location for the compiled loader
    executables so that repeated loads within a test session do not recompile the same
    loader, while never sharing a world-writable path with other users.

    Parameters
    ----------
    None

    Returns
    -------
    pathlib.Path
        ``$CARDDEMO_BUILD_DIR/loaders`` when the ``CARDDEMO_BUILD_DIR`` environment
        variable is set (so the suite's build artifacts stay together), otherwise a
        per-uid directory ``<system-temp>/carddemo_vsam_loaders-<uid>``.

    Raises
    ------
    None
    """
    # WHY (Trade-off): a *stable* directory (not a fresh mkdtemp per call) is essential
    # -- the compile-once cache only pays off if the binary persists across load_indexed
    # calls within the same session. Honouring CARDDEMO_BUILD_DIR first lets the runner
    # scripts co-locate loaders with the rest of the build output.
    build_dir = os.environ.get("CARDDEMO_BUILD_DIR")
    if build_dir:
        return Path(build_dir) / "loaders"
    # WHY (MA-10 security): the temp fallback is namespaced by uid so two users on the
    # same host never share (and cannot hijack) each other's cache directory in a
    # world-writable /tmp. _secure_dir additionally enforces 0700 + ownership.
    uid = getattr(os, "getuid", lambda: "nouid")()
    return Path(tempfile.gettempdir()) / f"carddemo_vsam_loaders-{uid}"


def _secure_dir(path: Path) -> Path:
    """Create (if needed) and validate a private directory owned by the current user.

    Purpose
    -------
    Guarantee that a directory the loader will write executables or workspaces into is a
    real directory (not a symlink), is owned by the current user, and is not group/other
    accessible (mode ``0700``). This closes the MA-10 class of attacks where a predictable
    path in a shared temp area is pre-created as a symlink or with loose permissions so a
    second user can read/replace a compiled binary before it is executed.

    Parameters
    ----------
    path : pathlib.Path
        The directory to create and secure.

    Returns
    -------
    pathlib.Path
        The same ``path``, now guaranteed to exist as a private directory.

    Raises
    ------
    VsamLoadError
        If ``path`` exists as a symlink or non-directory, is owned by another user, or
        cannot be created/secured.
    """
    try:
        # mode=0o700 on mkdir is subject to umask; we re-assert with chmod below so the
        # final mode is exactly 0700 regardless of the caller's umask.
        path.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        raise VsamLoadError(f"cannot create cache/workspace directory {path!r}: {exc}") from exc

    # WHY (Assumption): os.lstat (not stat) so a symlink is detected rather than followed
    # -- a symlinked cache dir is exactly the hijack vector we must refuse.
    try:
        st = os.lstat(path)
    except OSError as exc:
        raise VsamLoadError(f"cannot stat directory {path!r}: {exc}") from exc
    import stat as _stat

    if _stat.S_ISLNK(st.st_mode):
        raise VsamLoadError(
            f"refusing to use {path!r}: it is a symbolic link (possible hijack vector)"
        )
    if not _stat.S_ISDIR(st.st_mode):
        raise VsamLoadError(f"refusing to use {path!r}: not a directory")
    # Ownership check only where getuid exists (POSIX). On such systems a directory owned
    # by another user is refused outright.
    getuid = getattr(os, "getuid", None)
    if getuid is not None and st.st_uid != getuid():
        raise VsamLoadError(
            f"refusing to use {path!r}: owned by uid {st.st_uid}, not the current user"
        )
    # Re-assert exact private permissions (POSIX only; chmod is a no-op-ish on Windows).
    if os.name == "posix":
        try:
            os.chmod(path, 0o700)
        except OSError as exc:
            raise VsamLoadError(f"cannot secure permissions on {path!r}: {exc}") from exc
    return path


# WHY (MA-10 minimal child environment): the child loader is a tiny COBOL program that
# needs only (a) a way to find shared libraries and the GnuCOBOL runtime, (b) the DD
# environment variables that bind its ASSIGN names, and (c) locale so byte<->char
# handling is stable. Passing the *entire* parent environment to a spawned process is an
# unnecessary exposure (secrets, tokens) and a determinism risk; we forward only a
# vetted allow-list plus every COB_* runtime-config variable GnuCOBOL honours.
_CHILD_ENV_ALLOW = (
    "PATH",
    "HOME",
    "TMPDIR",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "TERM",
)


def _minimal_child_env(extra: "dict[str, str]") -> "dict[str, str]":
    """Build a minimal, vetted environment for a spawned loader/compiler process.

    Purpose
    -------
    Return a fresh environment mapping containing only the variables a GnuCOBOL child
    genuinely needs, plus the caller-supplied ``extra`` bindings (the DD ``ASSIGN``-name
    variables). This bounds what a subprocess can observe and keeps runs deterministic.

    Parameters
    ----------
    extra : dict[str, str]
        Additional bindings to inject (e.g. ``{FLATIN: ..., IDXOUT: ...}``). These take
        precedence over inherited values of the same name.

    Returns
    -------
    dict[str, str]
        The child environment: allow-listed inherited variables + all ``COB_*`` runtime
        variables + ``extra``.

    Raises
    ------
    None
    """
    env: "dict[str, str]" = {}
    for name in _CHILD_ENV_ALLOW:
        val = os.environ.get(name)
        if val is not None:
            env[name] = val
    # Forward GnuCOBOL runtime configuration (COB_LIBRARY_PATH, COB_FILE_PATH, etc.) so
    # the child resolves the same runtime/config as the parent without inheriting
    # unrelated variables.
    for name, val in os.environ.items():
        if name.startswith("COB_"):
            env[name] = val
    env.update(extra)
    return env


def _compiler_fingerprint(cobc_path: str, std: str) -> str:
    """Return a short digest identifying the compiler build + dialect + generator.

    Purpose
    -------
    Produce a stable fingerprint that changes whenever anything affecting the compiled
    loader's bytes changes -- the ``cobc`` version banner, the ``--std`` dialect, and this
    module's own source version marker. Folding it into the cache filename (MA-10)
    guarantees a binary compiled by one toolchain is never silently reused after the
    compiler is upgraded, which could otherwise emit an incompatible indexed-file format.

    Parameters
    ----------
    cobc_path : str
        Resolved path/command of the ``cobc`` compiler.
    std : str
        The ``--std`` dialect value.

    Returns
    -------
    str
        A 16-hex-character SHA-256 prefix over ``(cobc --version banner, std, marker)``.

    Raises
    ------
    None
        A failure to obtain the version banner degrades to a fixed placeholder rather
        than raising; the compile step will still fail loudly later if cobc is unusable.
    """
    try:
        # bounded: never let a hung `cobc --version` stall the suite.
        proc = subprocess.run(
            [cobc_path, "--version"],
            capture_output=True,
            text=True,
            timeout=30,
            env=_minimal_child_env({}),
        )
        banner = proc.stdout or proc.stderr or ""
    except (OSError, subprocess.SubprocessError):
        banner = "unknown-cobc"
    h = hashlib.sha256()
    # _LOADER_SOURCE_VERSION bumps whenever the generator's output format changes, so an
    # old cached binary is invalidated even if the compiler banner is unchanged.
    h.update(_LOADER_SOURCE_VERSION.encode("utf-8"))
    h.update(b"\x00")
    h.update(banner.encode("utf-8", "replace"))
    h.update(b"\x00")
    h.update(std.encode("utf-8"))
    return h.hexdigest()[:16]


def _compile_source(
    source: str,
    *,
    describe: str,
    exe_prefix: str,
    std: str,
    cobc: str,
    cache_dir: "str | os.PathLike[str] | None",
    compile_timeout: float = _COMPILE_TIMEOUT_S,
) -> str:
    """Compile (or reuse a cached) standalone ``-x`` binary for a generated COBOL source.

    Purpose
    -------
    The shared compile-and-cache engine behind both :func:`_compiled_loader` and
    :func:`_compiled_unloader`. Given the full generated COBOL source text, return the path
    to an executable built from it -- compiling with the project ``cobc`` on the first
    request and reusing the cached binary on every subsequent request. The cache filename
    embeds a content-addressed digest of the *exact* source AND a compiler fingerprint, so a
    binary built for one program shape, geometry, or toolchain can never be silently reused
    for a different one (MA-10).

    WHY factor this out (Refactoring Rationale): the loader and the unloader need
    byte-for-byte the same secure-cache, content-address, parallel-safe atomic-publish, and
    bounded-compile behaviour. Duplicating ~70 lines of it would invite the two copies to
    drift (a fix or hardening applied to one but not the other); a single shared engine
    keeps them provably identical and is the correct home for that logic.

    Parameters
    ----------
    source : str
        Complete free-format COBOL source (pure ASCII by construction).
    describe : str
        Human-readable description of the artifact (e.g. ``"the GnuCOBOL indexed-file
        loader (reclen=300, key_length=11, ...)"``), folded verbatim into the timeout and
        failure messages so a diagnostic still identifies the geometry after this
        generalisation (keyword-only).
    exe_prefix : str
        Short (<= 31 char) base name for the compiled binary and its temporary ``.cbl``
        (e.g. ``"vsamldr"`` / ``"vsamuld"``), keyword-only. The content-addressed cache
        name is ``<exe_prefix>_<src_digest>_<fingerprint>``; because the loader and unloader
        emit textually distinct sources their digests already differ, so a shared prefix
        would still not collide -- distinct prefixes are used purely for on-disk legibility.
    std : str
        Compiler dialect passed to ``cobc --std=`` (keyword-only).
    cobc : str
        ``cobc`` command name or explicit path (keyword-only), resolved via
        :func:`_resolve_cobc`.
    cache_dir : str | os.PathLike | None
        Directory for cached binaries (keyword-only). ``None`` selects
        :func:`_default_cache_dir`.
    compile_timeout : float
        Wall-clock ceiling (seconds) for the ``cobc`` invocation (keyword-only).

    Returns
    -------
    str
        Filesystem path to the compiled, executable binary.

    Raises
    ------
    VsamLoadError
        If ``cobc`` cannot be resolved, the cache directory cannot be secured, the compile
        exceeds ``compile_timeout``, or compilation fails.
    """
    cobc_path = _resolve_cobc(cobc)
    cache = Path(os.fspath(cache_dir)) if cache_dir is not None else _default_cache_dir()
    # WHY (MA-10): the cache dir holds executables we will run, so it MUST be private and
    # owned by us -- _secure_dir enforces 0700 + ownership and refuses a symlinked path.
    _secure_dir(cache)

    # Content-address the binary: the digest covers the exact generated source, so any
    # change in program shape or geometry (reclen, key_length, key_offset, alternate keys)
    # yields a different source and therefore a different cache file. The compiler
    # fingerprint is appended so a toolchain/dialect change also invalidates the cache.
    src_digest = hashlib.sha256(source.encode("ascii")).hexdigest()[:16]
    fp = _compiler_fingerprint(cobc_path, std)

    exe_ext = ".exe" if os.name == "nt" else ""
    final_bin = cache / f"{exe_prefix}_{src_digest}_{fp}{exe_ext}"

    # Cache hit: reuse the already-built binary. WHY: recompiling the same tiny program on
    # every fixture load/unload would dominate the runtime of a large suite; the digest
    # fully captures the source+toolchain so a hit is guaranteed correct.
    if final_bin.is_file() and os.access(final_bin, os.X_OK):
        return str(final_bin)

    # Compile inside a private temp directory *within the cache dir*, then atomically
    # publish the finished binary with os.replace.
    # WHY (parallel-safety Trade-off): pytest-xdist runs tests in parallel workers that may
    # request the same binary simultaneously. Building into a per-attempt temp dir and
    # os.replace()-ing the result (an atomic rename on the same filesystem) means a
    # concurrent worker either sees no binary yet or the fully-built one -- never a
    # half-written file. Multiple winners simply overwrite an identical binary.
    with tempfile.TemporaryDirectory(dir=str(cache), prefix=".build-") as tmpd:
        tmp = Path(tmpd)
        # WHY (short source base name): GnuCOBOL validates the SOURCE file's base name as a
        # candidate program word and rejects names longer than a COBOL word (31 chars). The
        # content-addressed cache name is far longer, so we compile from a short fixed name
        # inside this already-unique temp dir and only the *cached* binary carries the long
        # digest name (a plain filename, length-safe).
        src_path = tmp / f"{exe_prefix}.cbl"
        # The generated source is pure ASCII by construction; encode strictly so any
        # accidental non-ASCII would surface immediately rather than reach the compiler.
        src_path.write_text(source, encoding="ascii")
        tmp_bin = tmp / (f"{exe_prefix}.exe" if os.name == "nt" else exe_prefix)

        cmd = [cobc_path, "-x", f"--std={std}", "-free", "-o", str(tmp_bin), str(src_path)]
        try:
            # Bounded + minimal-env compile (MA-10): a wedged compiler becomes a clean
            # TimeoutExpired rather than an indefinite hang, and the child sees only a
            # vetted environment.
            proc = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=compile_timeout,
                env=_minimal_child_env({}),
            )
        except subprocess.TimeoutExpired as exc:
            raise VsamLoadError(
                f"timed out compiling {describe} after {compile_timeout}s (std={std!r})."
            ) from exc

        # WHY (Assumptions): success is judged by the process return code AND the binary
        # existing -- NOT by empty stderr. The gcc backend emits a benign
        # "_FORTIFY_SOURCE redefined" warning on every compile on this runner, so a
        # non-empty stderr is normal and must not be treated as failure.
        if proc.returncode != 0 or not tmp_bin.is_file():
            raise VsamLoadError(
                f"failed to compile {describe} (std={std!r}); "
                f"cobc exit code {proc.returncode}.\n"
                f"--- cobc stdout ---\n{proc.stdout}\n"
                f"--- cobc stderr ---\n{proc.stderr}\n"
                f"--- generated source ---\n{source}"
            )

        os.replace(str(tmp_bin), str(final_bin))

    return str(final_bin)


def _compiled_loader(
    reclen: int,
    key_length: int,
    *,
    key_offset: int = 0,
    alternate_keys: "tuple[tuple[int, int, bool], ...]" = (),
    std: str,
    cobc: str,
    cache_dir: "str | os.PathLike[str] | None",
    compile_timeout: float = _COMPILE_TIMEOUT_S,
) -> str:
    """Compile (or reuse a cached) loader binary for one full record geometry.

    Purpose
    -------
    Return the path to an executable loader built for exactly this record geometry
    (primary key + alternate keys), dialect, and compiler build, compiling it with the
    project ``cobc`` on the first request and reusing the cached binary on every
    subsequent request. The cache filename embeds a content-addressed digest of the
    generated source AND a compiler fingerprint, so a loader built for one layout or one
    toolchain can never be silently reused for a different one (MA-10).

    Parameters
    ----------
    reclen : int
        Fixed record length in characters.
    key_length : int
        Primary-key length in characters (``0 < key_length``).
    key_offset : int
        Zero-based offset of the primary key (keyword-only). Defaults to 0.
    alternate_keys : tuple[tuple[int, int, bool], ...]
        Alternate-key descriptors ``(offset, length, with_duplicates)`` (keyword-only).
    std : str
        Compiler dialect passed to ``cobc --std=`` (keyword-only).
    cobc : str
        ``cobc`` command name or explicit path (keyword-only), resolved via
        :func:`_resolve_cobc`.
    cache_dir : str | os.PathLike | None
        Directory for cached binaries (keyword-only). ``None`` selects
        :func:`_default_cache_dir`.
    compile_timeout : float
        Wall-clock ceiling (seconds) for the ``cobc`` invocation (keyword-only).

    Returns
    -------
    str
        Filesystem path to the compiled, executable loader binary.

    Raises
    ------
    VsamLoadError
        If ``cobc`` cannot be resolved, the cache directory cannot be secured, the
        compile exceeds ``compile_timeout``, or compilation of the generated loader
        fails.
    """
    # Delegate to the shared compile-and-cache engine. WHY (Refactoring Rationale): the
    # cache/secure-dir/atomic-publish machinery now lives once in _compile_source; this
    # function's remaining job is purely to render the loader source for this geometry and
    # hand it over with a geometry-identifying description for diagnostics.
    source = _generate_loader_source(reclen, key_length, key_offset, alternate_keys)
    describe = (
        "the GnuCOBOL indexed-file loader "
        f"(reclen={reclen}, key_length={key_length}, key_offset={key_offset}, "
        f"alternate_keys={alternate_keys!r})"
    )
    return _compile_source(
        source,
        describe=describe,
        exe_prefix="vsamldr",
        std=std,
        cobc=cobc,
        cache_dir=cache_dir,
        compile_timeout=compile_timeout,
    )


def _compiled_unloader(
    reclen: int,
    key_length: int,
    *,
    key_offset: int = 0,
    alternate_keys: "tuple[tuple[int, int, bool], ...]" = (),
    std: str,
    cobc: str,
    cache_dir: "str | os.PathLike[str] | None",
    compile_timeout: float = _COMPILE_TIMEOUT_S,
) -> str:
    """Compile (or reuse a cached) unloader binary for one full record geometry.

    Purpose
    -------
    The read-back counterpart of :func:`_compiled_loader`: return the path to an executable
    INDEXED->flat unloader built for exactly this record geometry (primary key + any
    alternate keys), dialect, and compiler build, via the shared :func:`_compile_source`
    engine (content-addressed, cached, parallel-safe). See :func:`_generate_unloader_source`
    for what the produced binary does.

    Parameters
    ----------
    reclen : int
        Fixed record length in characters.
    key_length : int
        Primary-key length in characters (``0 < key_length``).
    key_offset : int
        Zero-based offset of the primary key (keyword-only). Defaults to 0.
    alternate_keys : tuple[tuple[int, int, bool], ...]
        Alternate-key descriptors ``(offset, length, with_duplicates)`` the file was built
        with (keyword-only), declared so the OPEN INPUT key set matches the file.
    std : str
        Compiler dialect passed to ``cobc --std=`` (keyword-only).
    cobc : str
        ``cobc`` command name or explicit path (keyword-only), resolved via
        :func:`_resolve_cobc`.
    cache_dir : str | os.PathLike | None
        Directory for cached binaries (keyword-only). ``None`` selects
        :func:`_default_cache_dir`.
    compile_timeout : float
        Wall-clock ceiling (seconds) for the ``cobc`` invocation (keyword-only).

    Returns
    -------
    str
        Filesystem path to the compiled, executable unloader binary.

    Raises
    ------
    VsamLoadError
        If the geometry is invalid, ``cobc`` cannot be resolved, the cache directory cannot
        be secured, or the compile exceeds ``compile_timeout`` / fails.
    """
    source = _generate_unloader_source(reclen, key_length, key_offset, alternate_keys)
    describe = (
        "the GnuCOBOL indexed-file unloader "
        f"(reclen={reclen}, key_length={key_length}, key_offset={key_offset}, "
        f"alternate_keys={alternate_keys!r})"
    )
    return _compile_source(
        source,
        describe=describe,
        exe_prefix="vsamuld",
        std=std,
        cobc=cobc,
        cache_dir=cache_dir,
        compile_timeout=compile_timeout,
    )


def _validated_blob(
    flat_path: "str | os.PathLike[str]", reclen: int, *, binary: bool = False
) -> bytes:
    """Read a flat fixture and return a headerless fixed-width blob, rejecting bad rows.

    Purpose
    -------
    Convert a flat fixture into a deterministic byte blob of exactly ``reclen`` bytes per
    record with no separators, which the generated loader consumes as a fixed
    record-sequential file. Unlike the earlier lenient behaviour (which silently
    space-padded short rows and truncated long ones), this validator REJECTS any physical
    row whose width is not exactly ``reclen`` (CR-03): silently repairing a malformed
    fixture could mask a genuine encoding defect and load records that mis-key or shift
    every downstream field. A genuinely empty file (zero bytes, or only a trailing
    newline) is the one legitimate "zero records" case and yields an empty blob.

    Two framing modes are supported (F-VSAM-BINARY-LOAD):

    * **Textual** (``binary=False``, the default) -- the fixture is a newline-delimited
      flat file, one logical record per physical line; a single trailing CR/LF is
      stripped and every line must be exactly ``reclen`` wide. This is the shape of every
      committed CardDemo ``*.txt`` fixture (pure ASCII zoned decimal), so it is the
      default and every existing caller keeps its behaviour unchanged.
    * **Binary** (``binary=True``) -- the fixture is a raw ``reclen * N`` byte image with
      NO separators, so record boundaries are determined **solely by the declared record
      length**, never by payload bytes. This is required for records that legitimately
      contain 0x0A (e.g. a 4-byte ``COMP`` key whose value happens to include a linefeed
      byte); splitting such a record on newline would corrupt its framing.

    Parameters
    ----------
    flat_path : str | os.PathLike
        Path to the flat fixture to read.
    reclen : int
        The exact width every record must have.
    binary : bool, optional
        Keyword-only. When ``True``, frame the fixture by fixed record length over the
        raw bytes and perform NO newline parsing or text decoding (binary-safe). When
        ``False`` (default), use the legacy newline-delimited textual framing. WHY a flag
        rather than autodetection (Trade-off): a binary payload can contain any byte,
        including newlines, so there is no reliable content sniff that distinguishes a
        binary image from a textual file -- the caller, which knows the fixture's nature,
        states it explicitly. This also keeps the default behaviour (and every existing
        caller) byte-for-byte identical.

    Returns
    -------
    bytes
        ``reclen * N`` bytes where ``N`` is the number of conforming records (possibly
        zero). In textual mode the bytes are the Latin-1 encoding of the validated lines;
        in binary mode they are the fixture bytes verbatim.

    Raises
    ------
    VsamLoadError
        If the fixture cannot be read; in textual mode, if any physical row's width
        (after stripping a single trailing line terminator) is not exactly ``reclen``; in
        binary mode, if the total byte length is not a whole multiple of ``reclen``.
    """
    # --- Binary fixed-length framing (F-VSAM-BINARY-LOAD) -----------------------
    # WHY read_bytes + modulo check rather than the textual split below (Assumption):
    # a binary fixed image has exactly ``reclen`` bytes per record and no separators, so
    # the only well-formedness check is that the total length divides evenly by reclen.
    # Any newline in the payload is DATA, never a boundary -- which is the whole point of
    # the finding: a COMP key containing 0x0A must survive as one record.
    if binary:
        try:
            data = Path(os.fspath(flat_path)).read_bytes()
        except OSError as exc:
            raise VsamLoadError(
                f"cannot read binary fixture {os.fspath(flat_path)!r}: {exc}"
            ) from exc
        # A genuinely empty file -> zero records (mirrors the textual empty case), which
        # the loader opens and reads to EOF immediately.
        if data == b"":
            return b""
        if len(data) % reclen != 0:
            raise VsamLoadError(
                f"binary fixture {os.fspath(flat_path)!r} is {len(data)} bytes, not a "
                f"whole multiple of the declared record length {reclen}; record "
                "boundaries are framed by reclen only (no newline parsing in binary "
                "mode), so a non-multiple length means the fixture geometry is wrong."
            )
        return data

    try:
        # WHY encoding="latin-1": it is the identity byte<->codepoint map for 0..255, so
        # every byte in the fixture round-trips exactly; the space pad byte is 0x20. We
        # read the whole file and split ourselves (rather than iterate lines) so a
        # trailing newline at EOF is handled explicitly and cannot create a phantom row.
        raw = Path(os.fspath(flat_path)).read_text(encoding="latin-1")
    except OSError as exc:
        raise VsamLoadError(f"cannot read flat fixture {os.fspath(flat_path)!r}: {exc}") from exc

    # Genuinely empty file -> zero records. WHY: the empty_input scenarios provision an
    # empty (or newline-only) fixture and expect an empty indexed file, which the loader
    # then opens and reads to EOF immediately.
    if raw == "" or raw == "\n" or raw == "\r\n":
        return b""

    # Split on LF, then strip a single trailing CR per line (CRLF fixtures). A single
    # trailing newline at EOF produces a final empty element which we drop; a blank line
    # anywhere else is a genuine (zero-width) row and is therefore rejected below.
    lines = raw.split("\n")
    if lines and lines[-1] == "":
        lines.pop()  # drop the empty element produced by a trailing EOF newline only

    parts: list[str] = []
    for idx, line in enumerate(lines, start=1):
        if line.endswith("\r"):
            line = line[:-1]  # normalise a CRLF terminator to just the data bytes
        if len(line) != reclen:
            raise VsamLoadError(
                f"fixture {os.fspath(flat_path)!r} row {idx} is {len(line)} bytes but "
                f"the layout requires exactly {reclen}; refusing to silently "
                "pad/truncate a nonconforming record (CR-03). Fix the fixture width."
            )
        parts.append(line)

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


def _normalize_alternate_keys(
    alternate_keys: "object", reclen: int
) -> "tuple[tuple[int, int, bool], ...]":
    """Coerce an alternate-key specification into validated ``(offset, length, dup)`` tuples.

    Purpose
    -------
    Accept alternate keys in either of two shapes -- :class:`record_codec.AlternateKey`
    objects (with ``.offset``/``.length``/``.duplicates``) or plain
    ``(offset, length, with_duplicates)`` tuples -- and return a normalised, validated
    tuple form the generator and cache identity consume. Accepting both shapes lets
    callers pass ``record_codec`` layout metadata directly without a manual conversion.

    Parameters
    ----------
    alternate_keys : object
        An iterable of ``AlternateKey``-like objects or 3-tuples, or ``None``/empty.
    reclen : int
        The record length, used to bounds-check each key span.

    Returns
    -------
    tuple[tuple[int, int, bool], ...]
        The normalised alternate keys in declaration order.

    Raises
    ------
    VsamLoadError
        If an entry has the wrong shape or a span outside ``[0, reclen)``.
    """
    if not alternate_keys:
        return ()
    out: list[tuple[int, int, bool]] = []
    for i, ak in enumerate(alternate_keys, start=1):
        # Duck-type an AlternateKey object first; fall back to a positional 3-tuple.
        if hasattr(ak, "offset") and hasattr(ak, "length"):
            off = int(ak.offset)
            ln = int(ak.length)
            dup = bool(getattr(ak, "duplicates", True))
        else:
            try:
                off, ln, dup = int(ak[0]), int(ak[1]), bool(ak[2])
            except (TypeError, IndexError, ValueError) as exc:
                raise VsamLoadError(
                    f"alternate key #{i} must be an AlternateKey or a "
                    f"(offset, length, with_duplicates) tuple, got {ak!r}"
                ) from exc
        if ln <= 0 or off < 0 or off + ln > reclen:
            raise VsamLoadError(
                f"alternate key #{i} (offset={off}, length={ln}) is out of range for "
                f"reclen={reclen}"
            )
        out.append((off, ln, dup))
    return tuple(out)


def _publish_indexed(workdir: Path, base_name: str, target: Path) -> None:
    """Atomically move a freshly built indexed file (and its companions) into place.

    Purpose
    -------
    Implement the validate-first publish step (MA-10): the loader writes the new indexed
    file into a private workspace; only after it has succeeded do we remove any old
    target and move the new primary + companion index files to their final names. This
    guarantees a failed load never destroys a previously good indexed file and never
    leaves a half-written one at the target path.

    Parameters
    ----------
    workdir : pathlib.Path
        The private workspace directory containing the freshly built files.
    base_name : str
        The file name (no directory) shared by the primary and its companions.
    target : pathlib.Path
        The final destination path for the primary indexed file.

    Returns
    -------
    None

    Raises
    ------
    VsamLoadError
        If a produced file cannot be moved into place.
    """
    # Remove the previous target + companions only now that the new build has validated.
    _remove_indexed(target)
    parent = target.parent
    # Enumerate produced files: the primary (== base_name) and any companion
    # ``base_name.idx`` / ``base_name.<digits>`` (VBISAM / BDB alternate-index sidecars).
    # WHY: alternate keys (CR-02) cause the ISAM backend to emit sidecar index files that
    # must travel with the primary or the secondary index is lost.
    prefix = base_name + "."
    for produced in sorted(workdir.iterdir()):
        name = produced.name
        is_primary = name == base_name
        is_companion = name.startswith(prefix) and (
            name[len(prefix):] in _INDEX_COMPANION_LITERAL_SUFFIXES
            or name[len(prefix):].isdigit()
        )
        if not (is_primary or is_companion):
            continue  # skip the transient blob and anything unrelated
        try:
            # os.replace is atomic within one filesystem; workdir lives under parent, so
            # the move never crosses a filesystem boundary.
            os.replace(str(produced), str(parent / name))
        except OSError as exc:
            raise VsamLoadError(
                f"failed to publish built indexed file {name!r} to {parent!r}: {exc}"
            ) from exc


def load_indexed(
    flat_path: "str | os.PathLike[str]",
    indexed_path: "str | os.PathLike[str]",
    reclen: int,
    key_length: int,
    key_offset: int = 0,
    *,
    alternate_keys: "object" = (),
    binary: bool = False,
    cobc: str = "cobc",
    std: str = "ibm-strict",
    cache_dir: "str | os.PathLike[str] | None" = None,
    compile_timeout: float = _COMPILE_TIMEOUT_S,
    run_timeout: float = _RUN_TIMEOUT_S,
) -> str:
    """Load a flat fixed-width fixture into a GnuCOBOL native indexed (ISAM) file.

    Purpose
    -------
    The single public entry point of this module and the automated analog of the
    mainframe ``IDCAMS DELETE -> DEFINE -> REPRO`` file-load pattern. Given a flat
    fixture and a target path, it produces a native GnuCOBOL ``ORGANIZATION IS INDEXED``
    file -- with the given primary key and any alternate keys -- that a program under
    test can open and read.

    The load is performed **validate-first**: the new file is built in a private
    workspace and published to the target path only after the loader has succeeded, so a
    failed load can never corrupt or delete a pre-existing good file (MA-10).

    Parameters
    ----------
    flat_path : str | os.PathLike
        Path to the flat fixed-width fixture (one record per line). Every physical row
        must be exactly ``reclen`` bytes wide (after stripping one trailing line
        terminator); a nonconforming row is rejected rather than silently repaired
        (CR-03). A genuinely empty fixture yields an empty indexed file.
    indexed_path : str | os.PathLike
        Destination path for the indexed file. A pre-existing file at this path (and its
        companion index files) is deleted only after the new file has been built
        successfully. Refused if the path itself is a symbolic link.
    reclen : int
        Fixed record length in characters. Must be a positive integer.
    key_length : int
        Length in characters of the primary key. Must satisfy ``0 < key_length`` and
        ``key_offset + key_length <= reclen``.
    key_offset : int, optional
        Zero-based offset of the primary key within the record. Defaults to ``0`` (every
        current CardDemo primary key is leading), but any valid offset is now supported.
    alternate_keys : object, optional
        Alternate keys as :class:`record_codec.AlternateKey` objects or
        ``(offset, length, with_duplicates)`` tuples (keyword-only). Each becomes an
        ``ALTERNATE RECORD KEY`` clause so programs that read by a secondary key (e.g.
        ``CBACT04C`` reading XREF by account id) work. Defaults to none.
    binary : bool, optional
        Keyword-only. When ``True``, the flat fixture is framed by fixed record length
        only (raw ``reclen * N`` bytes, no newline parsing, no text decode) so records
        that legitimately contain 0x0A survive intact (F-VSAM-BINARY-LOAD). When
        ``False`` (default) the fixture is parsed as newline-delimited text, which is the
        shape of every committed CardDemo ``*.txt`` fixture. Defaults to ``False`` so
        every existing caller is unaffected.
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
    compile_timeout : float, optional
        Wall-clock ceiling (seconds) for the loader compile (keyword-only).
    run_timeout : float, optional
        Wall-clock ceiling (seconds) for the loader run (keyword-only).

    Returns
    -------
    str
        ``os.fspath(indexed_path)`` -- the path of the freshly written indexed file.

    Raises
    ------
    VsamLoadError
        If ``reclen``/``key_length``/``key_offset`` violate the geometry contract; if an
        alternate key is malformed or out of range; if the target path is a symlink; if
        the fixture contains a nonconforming row or cannot be read; if the loader fails
        to compile or times out; or if the loader run exits non-zero or times out (in
        which case the loader's ``FILE STATUS IS: NNNN`` diagnostic is included).
    """
    # --- Argument validation (fail loud, fail early) -----------------------------
    if not isinstance(reclen, int) or reclen <= 0:
        raise VsamLoadError(f"reclen must be a positive integer, got {reclen!r}")
    if not isinstance(key_length, int) or key_length <= 0:
        raise VsamLoadError(f"key_length must be a positive integer, got {key_length!r}")
    if not isinstance(key_offset, int) or key_offset < 0 or key_offset + key_length > reclen:
        raise VsamLoadError(
            f"primary key must satisfy 0 <= key_offset and key_offset+key_length <= "
            f"reclen (reclen={reclen}, key_offset={key_offset!r}, key_length={key_length})"
        )
    alt_keys = _normalize_alternate_keys(alternate_keys, reclen)

    flat = os.fspath(flat_path)
    indexed = os.fspath(indexed_path)
    target = Path(indexed)

    # --- Refuse to write through a symlinked target (MA-04) ----------------------
    # WHY: if the target path is a pre-planted symlink, publishing through it would write
    # (or delete) a file outside the intended workspace. We refuse rather than follow it.
    if target.is_symlink():
        raise VsamLoadError(
            f"refusing to write indexed file through symbolic link {indexed!r}"
        )

    # The loader's OPEN OUTPUT cannot create missing parent directories, so ensure the
    # destination directory exists before we run it.
    target.parent.mkdir(parents=True, exist_ok=True)

    # --- Validate the fixture into a headerless fixed-width blob (CR-03) ----------
    # WHY thread ``binary`` (F-VSAM-BINARY-LOAD): a binary fixture is framed by record
    # length only, so newline parsing must be suppressed; the generated loader already
    # declares ORGANIZATION SEQUENTIAL with RECORD CONTAINS reclen CHARACTERS, so a blob
    # of reclen*N raw bytes (with any embedded 0x0A treated as data) loads correctly.
    blob = _validated_blob(flat, reclen, binary=binary)

    # --- DEFINE + REPRO analog: build (or reuse) the loader -----------------------
    loader_bin = _compiled_loader(
        reclen,
        key_length,
        key_offset=key_offset,
        alternate_keys=alt_keys,
        std=std,
        cobc=cobc,
        cache_dir=cache_dir,
        compile_timeout=compile_timeout,
    )

    # --- Build into a private workspace under the target's parent, then publish ---
    # WHY (MA-04 + MA-10 validate-first): the loader writes into an isolated per-run
    # workspace (0700, created via mkdtemp inside the target's directory so the eventual
    # os.replace is same-filesystem/atomic). The blob lives inside the same workspace, so
    # nothing transient escapes into a shared temp area. Only on success do we scrub the
    # old target and move the new files into place (_publish_indexed).
    workdir = Path(tempfile.mkdtemp(dir=str(target.parent), prefix=".vsamldr-work-"))
    try:
        base_name = target.name
        built = workdir / base_name
        blob_path = workdir / (base_name + ".flat")
        blob_path.write_bytes(blob)

        child_env = _minimal_child_env(
            {_DD_FLAT_IN: str(blob_path), _DD_INDEX_OUT: str(built)}
        )
        try:
            proc = subprocess.run(
                [loader_bin],
                env=child_env,
                capture_output=True,
                text=True,
                timeout=run_timeout,
            )
        except subprocess.TimeoutExpired as exc:
            raise VsamLoadError(
                f"indexed-file loader timed out after {run_timeout}s for "
                f"{flat!r} -> {indexed!r} (reclen={reclen}, key_length={key_length})."
            ) from exc

        if proc.returncode != 0:
            raise VsamLoadError(
                f"indexed-file loader failed for {flat!r} -> {indexed!r} "
                f"(reclen={reclen}, key_length={key_length}, key_offset={key_offset}, "
                f"alternate_keys={alt_keys!r}); exit code {proc.returncode}.\n"
                f"--- loader stdout ---\n{proc.stdout}\n"
                f"--- loader stderr ---\n{proc.stderr}"
            )
        if not built.is_file():
            raise VsamLoadError(
                f"indexed-file loader reported success but produced no file at "
                f"{built!r} for {flat!r} -> {indexed!r}."
            )

        # Publish (atomic per-file move of primary + companions).
        _publish_indexed(workdir, base_name, target)
    finally:
        # Always remove the private workspace (the blob and any leftover build files).
        shutil.rmtree(workdir, ignore_errors=True)

    return indexed


def unload_indexed_bytes(
    indexed_path: "str | os.PathLike[str]",
    reclen: int,
    key_length: int,
    key_offset: int = 0,
    *,
    alternate_keys: "object" = (),
    cobc: str = "cobc",
    std: str = "ibm-strict",
    cache_dir: "str | os.PathLike[str] | None" = None,
    compile_timeout: float = _COMPILE_TIMEOUT_S,
    run_timeout: float = _RUN_TIMEOUT_S,
) -> "list[bytes]":
    """Read a GnuCOBOL indexed file back into flat logical records as raw ``bytes``.

    Purpose
    -------
    The **binary-safe** read-back primitive (F-VSAM-BINARY-UNLOAD): it returns each
    logical record as a ``reclen``-byte ``bytes`` object in ascending primary-key order,
    performing NO text decoding, so records containing arbitrary bytes (e.g. a high byte
    such as 0x8f, or an embedded 0x0A) round-trip byte-for-byte. :func:`unload_indexed`
    is the thin text layer built on top of this primitive; callers that need bytes (raw
    binary verification) use this function directly, and callers that want decoded
    strings use :func:`unload_indexed`. WHY split them (Refactoring Rationale): the
    previous single function decoded strict UTF-8 unconditionally and raised
    ``UnicodeDecodeError`` on any non-UTF-8 byte, which made faithful binary verification
    impossible; separating the byte-exact read from the optional decode makes text
    decoding an explicit, opt-in codec layer rather than a hidden, lossy step.

    The public read-back companion of :func:`load_indexed`, and the automated analog of an
    ``IDCAMS REPRO`` that dumps an indexed cluster to a flat sequential dataset. Given the
    path of a native GnuCOBOL ``ORGANIZATION IS INDEXED`` file, it returns the file's
    logical records -- each a fixed ``reclen``-character string -- in ascending
    **primary-key** order. This is what lets the golden-master comparator verify an indexed
    *output* of a program under test (ACCTFILE, TCATBAL, TRANFILE): the opaque, unstable
    ISAM on-disk byte layout is never compared directly; only the deterministic logical
    record content is (QA finding C2). WHY a compiled COBOL reader rather than parsing the
    BDB/VBISAM container in Python (Alternatives Considered): the on-disk format is an
    implementation detail of the ISAM backend and can differ by build; reading it with a
    program compiled by the *same* ``cobc`` guarantees we observe exactly the logical
    records the programs under test wrote, with zero format assumptions.

    The read is performed against the file **in place** (it is opened INPUT only, never
    written), so unloading never mutates the output being verified.

    Parameters
    ----------
    indexed_path : str | os.PathLike
        Path to the native GnuCOBOL indexed file to read. Must exist; refused if the path
        itself is a symbolic link (MA-04, matching :func:`load_indexed`).
    reclen : int
        Fixed record length in characters. Must be a positive integer and match the
        geometry the file was built with.
    key_length : int
        Length in characters of the primary key. Must satisfy ``0 < key_length`` and
        ``key_offset + key_length <= reclen``.
    key_offset : int, optional
        Zero-based offset of the primary key within the record. Defaults to ``0``.
    alternate_keys : object, optional
        Alternate keys as :class:`record_codec.AlternateKey` objects or
        ``(offset, length, with_duplicates)`` tuples (keyword-only). Supply the *same*
        alternate keys the file was built with so the OPEN INPUT key set matches the file
        (a mismatch can fail FILE STATUS 39). CardDemo's three indexed outputs are
        primary-key only, so this defaults to none.
    cobc : str, optional
        ``cobc`` command name or explicit path used to build the unloader (keyword-only).
        Defaults to ``"cobc"``. **Must be the same compiler that built the indexed file**,
        because only that compiler reliably reads its own indexed-file format.
    std : str, optional
        Compiler dialect passed to ``cobc --std=`` (keyword-only). Defaults to
        ``"ibm-strict"`` to match the repository's compile convention.
    cache_dir : str | os.PathLike | None, optional
        Directory in which the compiled unloader binary is cached (keyword-only). Defaults
        to :func:`_default_cache_dir`.
    compile_timeout : float, optional
        Wall-clock ceiling (seconds) for the unloader compile (keyword-only).
    run_timeout : float, optional
        Wall-clock ceiling (seconds) for the unloader run (keyword-only).

    Returns
    -------
    list[bytes]
        The logical records as fixed ``reclen``-byte ``bytes`` objects, in ascending
        primary-key order, byte-for-byte as stored (no decoding). An empty indexed file
        yields an empty list. Concatenating the result (``b"".join(...)``) reproduces the
        raw record image, which is what makes byte-exact binary verification possible.

    Raises
    ------
    VsamLoadError
        If ``reclen``/``key_length``/``key_offset`` violate the geometry contract; if an
        alternate key is malformed or out of range; if the file does not exist or is a
        symlink; if the unloader fails to compile or times out; if the unloader run exits
        non-zero or times out (its ``FILE STATUS IS: NNNN`` diagnostic is included); or if
        the unloaded blob length is not a whole multiple of ``reclen``.
    """
    # --- Argument validation (fail loud, fail early; mirrors load_indexed) -------
    if not isinstance(reclen, int) or reclen <= 0:
        raise VsamLoadError(f"reclen must be a positive integer, got {reclen!r}")
    if not isinstance(key_length, int) or key_length <= 0:
        raise VsamLoadError(f"key_length must be a positive integer, got {key_length!r}")
    if not isinstance(key_offset, int) or key_offset < 0 or key_offset + key_length > reclen:
        raise VsamLoadError(
            f"primary key must satisfy 0 <= key_offset and key_offset+key_length <= "
            f"reclen (reclen={reclen}, key_offset={key_offset!r}, key_length={key_length})"
        )
    alt_keys = _normalize_alternate_keys(alternate_keys, reclen)

    indexed = os.fspath(indexed_path)
    source = Path(indexed)

    # --- Refuse to read through a symlinked source (MA-04) -----------------------
    # WHY: symmetry with load_indexed's write-side guard -- if the path is a pre-planted
    # symlink we refuse to follow it rather than read a file outside the intended
    # workspace. is_symlink() is checked before exists() because a dangling symlink should
    # be reported as the symlink refusal, not as "does not exist".
    if source.is_symlink():
        raise VsamLoadError(
            f"refusing to read indexed file through symbolic link {indexed!r}"
        )
    if not source.is_file():
        raise VsamLoadError(
            f"indexed file to unload does not exist (or is not a regular file): {indexed!r}"
        )

    # --- REPRO-out analog: build (or reuse) the unloader -------------------------
    unloader_bin = _compiled_unloader(
        reclen,
        key_length,
        key_offset=key_offset,
        alternate_keys=alt_keys,
        std=std,
        cobc=cobc,
        cache_dir=cache_dir,
        compile_timeout=compile_timeout,
    )

    # --- Run the unloader into a private workspace, then read the flat blob ------
    # WHY (isolation Trade-off): the flat dump is written into a per-run 0700 workspace
    # created inside the source file's own directory (so nothing transient escapes into a
    # shared temp area), and is always removed in the finally-block. We never write next to
    # the indexed file under a predictable name, which could race a parallel worker.
    workdir = Path(tempfile.mkdtemp(dir=str(source.parent), prefix=".vsamuld-work-"))
    try:
        out_blob = workdir / (source.name + ".flatout")
        child_env = _minimal_child_env(
            {_DD_INDEX_IN: indexed, _DD_FLAT_OUT: str(out_blob)}
        )
        try:
            proc = subprocess.run(
                [unloader_bin],
                env=child_env,
                capture_output=True,
                text=True,
                timeout=run_timeout,
            )
        except subprocess.TimeoutExpired as exc:
            raise VsamLoadError(
                f"indexed-file unloader timed out after {run_timeout}s for "
                f"{indexed!r} (reclen={reclen}, key_length={key_length})."
            ) from exc

        if proc.returncode != 0:
            raise VsamLoadError(
                f"indexed-file unloader failed for {indexed!r} "
                f"(reclen={reclen}, key_length={key_length}, key_offset={key_offset}, "
                f"alternate_keys={alt_keys!r}); exit code {proc.returncode}.\n"
                f"--- unloader stdout ---\n{proc.stdout}\n"
                f"--- unloader stderr ---\n{proc.stderr}"
            )

        # An empty index legitimately produces no output file (OPEN OUTPUT with zero
        # WRITEs) or a zero-byte one; treat both as "zero records" rather than an error.
        data = out_blob.read_bytes() if out_blob.is_file() else b""
    finally:
        # Always remove the private workspace (the flat dump and any leftover files).
        shutil.rmtree(workdir, ignore_errors=True)

    # --- Frame the flat blob into fixed-width logical records --------------------
    # WHY reject a non-multiple length (Assumption): the unloader writes exactly
    # reclen-byte records with no separators, so a total that is not a whole multiple of
    # reclen means the file geometry disagrees with the declared reclen -- a corrupt read
    # that must surface loudly, never be silently truncated.
    if len(data) % reclen != 0:
        raise VsamLoadError(
            f"unloaded blob length {len(data)} is not a whole multiple of reclen "
            f"{reclen} for {indexed!r} (declared geometry disagrees with the file)."
        )
    # Frame into byte-exact records. WHY no decode here (F-VSAM-BINARY-UNLOAD): this is
    # the binary-safe primitive, so it returns the raw record bytes verbatim and leaves
    # any text interpretation to :func:`unload_indexed`. Slicing ``bytes`` by reclen is
    # already byte-aligned (each record is exactly reclen bytes), so no encoding
    # assumption is made and a high byte such as 0x8f round-trips unchanged.
    return [data[off:off + reclen] for off in range(0, len(data), reclen)]


def unload_indexed(
    indexed_path: "str | os.PathLike[str]",
    reclen: int,
    key_length: int,
    key_offset: int = 0,
    *,
    alternate_keys: "object" = (),
    encoding: str = "utf-8",
    cobc: str = "cobc",
    std: str = "ibm-strict",
    cache_dir: "str | os.PathLike[str] | None" = None,
    compile_timeout: float = _COMPILE_TIMEOUT_S,
    run_timeout: float = _RUN_TIMEOUT_S,
) -> "list[str]":
    """Read a GnuCOBOL indexed file back into flat logical records as decoded ``str``.

    Purpose
    -------
    The **text** read-back layer over :func:`unload_indexed_bytes`, and the automated
    analog of an ``IDCAMS REPRO`` that dumps an indexed cluster to a flat sequential
    dataset. It returns each logical record as a fixed ``reclen``-character string in
    ascending primary-key order, which is what the golden-master comparator consumes for
    the three indexed program outputs (ACCTFILE, TCATBAL, TRANFILE). This is the
    backward-compatible public entry point every existing caller uses; it delegates the
    byte-exact read to :func:`unload_indexed_bytes` and then decodes each record with
    ``encoding`` (default UTF-8, matching the prior behaviour).

    WHY a thin wrapper rather than a ``binary=`` flag on one function (Alternatives
    Considered): a single function returning ``list[str] | list[bytes]`` depending on a
    flag would give callers a union return type that static analysers and readers must
    disambiguate at every call site. Two functions with distinct, honest return types
    (``list[bytes]`` vs ``list[str]``) make the codec boundary explicit and keep the
    common decoded-string path -- and its exact prior semantics -- unchanged.

    Parameters
    ----------
    indexed_path : str | os.PathLike
        Path to the native GnuCOBOL indexed file to read. Must exist; refused if the path
        itself is a symbolic link (MA-04, matching :func:`load_indexed`).
    reclen : int
        Fixed record length in characters. Must be a positive integer and match the
        geometry the file was built with.
    key_length : int
        Length in characters of the primary key.
    key_offset : int, optional
        Zero-based offset of the primary key within the record. Defaults to ``0``.
    alternate_keys : object, optional
        Alternate keys (keyword-only); supply the same set the file was built with.
        Defaults to none.
    encoding : str, optional
        Keyword-only text codec used to decode each record. Defaults to ``"utf-8"`` to
        preserve the historical behaviour (CardDemo records are pure ASCII, a strict UTF-8
        subset, so a genuinely non-ASCII byte surfaces loudly as a ``UnicodeDecodeError``
        rather than silently corrupting a record). Callers verifying genuinely binary
        records should use :func:`unload_indexed_bytes` instead of forcing a codec here.
    cobc : str, optional
        ``cobc`` command name or path used to build the unloader (keyword-only). Must be
        the same compiler that built the indexed file. Defaults to ``"cobc"``.
    std : str, optional
        Compiler dialect passed to ``cobc --std=`` (keyword-only). Defaults to
        ``"ibm-strict"``.
    cache_dir : str | os.PathLike | None, optional
        Directory in which the compiled unloader binary is cached (keyword-only).
    compile_timeout : float, optional
        Wall-clock ceiling (seconds) for the unloader compile (keyword-only).
    run_timeout : float, optional
        Wall-clock ceiling (seconds) for the unloader run (keyword-only).

    Returns
    -------
    list[str]
        The logical records as fixed ``reclen``-character strings, in ascending
        primary-key order. An empty indexed file yields an empty list. Joining the result
        with ``"\\n"`` (or concatenating it) and passing it to
        :func:`golden_compare.assert_matches_golden` with the matching ``layout`` verifies
        the output byte-exactly.

    Raises
    ------
    VsamLoadError
        Any error propagated from :func:`unload_indexed_bytes` (geometry contract
        violation, missing/symlinked file, compile/run failure or timeout, or a blob
        length that is not a whole multiple of ``reclen``).
    UnicodeDecodeError
        If a record contains a byte that ``encoding`` cannot decode (e.g. a non-ASCII
        byte under the default UTF-8 codec). This is intentional: it surfaces an
        unexpected encoding rather than silently corrupting a record. Callers that expect
        genuinely binary content should use :func:`unload_indexed_bytes`.
    """
    # WHY delegate then decode (Refactoring Rationale): the byte-exact read, geometry
    # validation, symlink guard, compile, and run all live once in unload_indexed_bytes;
    # this layer adds only the optional text decode, so the two functions can never drift.
    records = unload_indexed_bytes(
        indexed_path,
        reclen,
        key_length,
        key_offset,
        alternate_keys=alternate_keys,
        cobc=cobc,
        std=std,
        cache_dir=cache_dir,
        compile_timeout=compile_timeout,
        run_timeout=run_timeout,
    )
    return [record.decode(encoding) for record in records]


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


def alternate_keys_for(layout_name: str) -> "tuple[tuple[int, int, bool], ...]":
    """Resolve a named layout's alternate keys as ``(offset, length, dup)`` tuples.

    Purpose
    -------
    Convenience mirror of :func:`geometry_for` for alternate keys, so a caller can build
    an indexed file for a named layout (e.g. ``"XREF"``) with all its secondary indexes
    without restating the copybook geometry. Defers to the single source of truth,
    :func:`record_codec.alternate_keys_of`.

    Parameters
    ----------
    layout_name : str
        A logical record name registered in :data:`record_codec.LAYOUTS`.

    Returns
    -------
    tuple[tuple[int, int, bool], ...]
        ``(offset, length, with_duplicates)`` for each alternate key (possibly empty).

    Raises
    ------
    KeyError
        If ``layout_name`` is not registered (propagated from ``alternate_keys_of``).
    ImportError
        If :mod:`tests.helpers.record_codec` cannot be imported.
    """
    from tests.helpers.record_codec import alternate_keys_of

    return tuple(
        (ak.offset, ak.length, ak.duplicates) for ak in alternate_keys_of(layout_name)
    )



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
            "geometry may be given explicitly (reclen + key_length [+ key_offset]) or "
            "resolved from a record_codec layout name via --layout (e.g. --layout XREF), "
            "which also supplies the layout's alternate keys. Additional alternate keys "
            "may be given with repeatable --alt-key OFF:LEN[:dup]."
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
        help="zero-based primary-key offset within the record (default: 0)",
    )
    parser.add_argument(
        "--layout", default=None, metavar="NAME",
        help="record_codec layout name to derive reclen/key_length AND alternate keys "
             "(e.g. XREF, ACCOUNT, DALYTRAN, DISGROUP); mutually exclusive with "
             "positional reclen/key_length",
    )
    parser.add_argument(
        "--alt-key", action="append", default=[], dest="alt_key", metavar="OFF:LEN[:dup]",
        help="add an ALTERNATE RECORD KEY at byte OFF, LEN bytes wide; optional third "
             "field dup=0 disables WITH DUPLICATES (default: duplicates allowed). "
             "Repeatable. Merged with any keys implied by --layout.",
    )
    parser.add_argument(
        "--compile-timeout", type=float, default=_COMPILE_TIMEOUT_S, dest="compile_timeout",
        metavar="SECONDS",
        help=f"wall-clock ceiling for the loader compile (default: {_COMPILE_TIMEOUT_S})",
    )
    parser.add_argument(
        "--run-timeout", type=float, default=_RUN_TIMEOUT_S, dest="run_timeout",
        metavar="SECONDS",
        help=f"wall-clock ceiling for the loader run (default: {_RUN_TIMEOUT_S})",
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

    alt_keys: "list[tuple[int, int, bool]]" = []

    # Resolve record geometry from exactly one source: --layout OR positional numbers.
    if args.layout is not None:
        # WHY: combining --layout with positional reclen/key_length is ambiguous, so we
        # reject it rather than silently preferring one over the other.
        if args.reclen is not None or args.key_length is not None:
            parser.error("--layout cannot be combined with positional reclen/key_length")
        try:
            reclen, key_length = geometry_for(args.layout)
            # --layout also contributes the layout's registered alternate keys, so a
            # by-name load of XREF gets its account-id secondary index automatically.
            alt_keys.extend(alternate_keys_for(args.layout))
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

    # Parse any explicit --alt-key OFF:LEN[:dup] specs and merge them after layout keys.
    for spec in args.alt_key:
        parts = spec.split(":")
        if len(parts) not in (2, 3):
            parser.error(f"--alt-key must be OFF:LEN[:dup], got {spec!r}")
        try:
            off = int(parts[0])
            ln = int(parts[1])
            dup = True if len(parts) == 2 else parts[2] not in ("0", "false", "no")
        except ValueError:
            parser.error(f"--alt-key OFF and LEN must be integers, got {spec!r}")
        alt_keys.append((off, ln, dup))

    try:
        result = load_indexed(
            args.flat,
            args.indexed,
            reclen,
            key_length,
            key_offset,
            alternate_keys=tuple(alt_keys),
            cobc=args.cobc,
            std=args.std,
            cache_dir=args.cache_dir,
            compile_timeout=args.compile_timeout,
            run_timeout=args.run_timeout,
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

