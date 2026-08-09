"""Open and bound one flat source file, and guard the two record widths every reader shares.

Purpose
-------
Hold the source-side decisions every reader in this package used to make for itself: how a seed
file is opened, how much of a line may be read before the read itself is a fault, whether an
absent optional dataset is absence or error, how wide a record has to be before its key may be
sliced, and how short a line may be before right-padding it would invent data rather than restore
padding. Each of those had between eight and ten identical copies across the reader modules, and
this module is the one place each is now stated.

Parameters
----------
None
    This module is imported for the four callables and one predicate it publishes; it declares no
    module-level parameter and reads no argument at import.

Returns
-------
None
    Importing binds names only. Nothing is computed, no file is opened, no environment variable is
    read and no network is reached at import time.

Raises
------
None
    Import cannot fail on this module's own account. It imports only
    :mod:`carddemo_migration.copybook`, whose import-time self-checks may raise
    :class:`carddemo_migration.copybook.layouts.LayoutError` if a declared layout is inconsistent;
    that failure belongs to that module and is not re-raised or re-worded here.

What this module publishes
--------------------------
:func:`iter_seed_lines`
    Stream the lines of one ASCII seed file from a hardened, atomically validated file
    descriptor, refusing anything that is not a regular file and refusing a line longer than the
    record it claims to be.
:func:`open_regular_binary`
    Open one fixed-length dataset for byte-mode reading through the same hardened path, optionally
    proving up front that its size divides into whole records.
:func:`seed_dataset_is_present`
    Answer whether an OPTIONAL dataset exists, distinguishing genuine absence from a path that
    exists but is not a readable regular file.
:func:`require_exact_record_width`
    Refuse a record that is not exactly its layout's declared width, before any field is sliced
    out of it.
:func:`data_region_width`
    Derive the offset through which a record's source must carry data, which is the bound the
    shared text-record iterator applies before it pads a short line.

WHY (Refactoring Rationale)
---------------------------
Every ASCII path reader in this package was the same two statements -- a ``Path.open`` in text mode
under the single-byte code page with newline translation pinned to the separator, followed by
handing the handle to the module's own line iterator -- and both statements were wrong in the same
two ways. ``Path.open`` is a BLOCKING open,
so naming a FIFO or a character device where a seed file was expected does not fail: it waits,
indefinitely, with no diagnostic, in a step an operator is watching for a load to finish. And
iterating a text handle reads to the next separator with no bound at all, so a file whose first
separator lies far past the record length is materialised in full before any width check can
refuse it -- the check that would have rejected it runs after the allocation that made it a
problem. Both are properties of a source rather than of a record, and a reader whose subject is
one record layout is the wrong place to decide either.

WHY (Alternatives Considered)
-----------------------------
Hardening each reader in place, which is what fixing the eight sites independently would mean.
Rejected because the eight would then hold eight statements of the same policy, and the failure
mode of a divergence between them is silence: a reader that validated the file type slightly
differently from its siblings still returns well-formed records for every ordinary input, so the
disagreement surfaces only on the pathological input the hardening exists for.

Also considered and rejected: putting these helpers in
:mod:`carddemo_migration.copybook.layouts`, beside the terminator policy they feed. That module is
the copybook authority -- it describes bytes and declares no I/O of its own -- and giving it a
file descriptor would make the package's geometry source also its file-access layer. The split
kept here is the one already in place for the byte path, where
:mod:`carddemo_migration.copybook.ebcdic_codec` owns the dataset open: geometry is declared in one
layer and reached for in another.

WHY (Trade-offs)
----------------
The hardened path is built on :func:`os.open` and :func:`os.fstat` rather than on
:class:`pathlib.Path`, which costs the readability of the higher-level API and gains the only
property that matters here: the file type is checked on the descriptor that will be read, not on
the name that was passed. A ``Path.is_file()`` followed by ``Path.open()`` inspects the name twice
and can be given two different files, so a path that was a regular file when it was checked can be
a FIFO by the time it is opened. Opening first and asking the descriptor what it is admits no such
window.
"""

from __future__ import annotations

import errno
import io
import os
import pathlib
import stat
from collections.abc import Iterator, Sequence
from typing import Final

from carddemo_migration.copybook.ebcdic_codec import EbcdicRecordLengthError
from carddemo_migration.copybook.layouts import (
    FieldSpec,
    LayoutError,
    RecordLengthError,
    RecordSpec,
    count_fixed_length_records,
)

__all__ = [
    "SEED_TEXT_CODE_PAGE",
    "data_region_width",
    "iter_seed_lines",
    "open_regular_binary",
    "require_exact_record_width",
    "seed_dataset_is_present",
]

# WHY : Assumptions: the code page is the total single-byte one every reader in this package
#   already named at its own open, hoisted here so the eight sites cannot drift apart on it. It is
#   deliberately not a strict ASCII decode: both reject a non-conforming file, but a strict decode
#   fails inside the interpreter's reader with an untyped encoding error, which would make this
#   package's own single-byte guard unreachable, whereas a total page maps each byte to exactly one
#   character so the failure surfaces as a record-length or field error naming the offset.
SEED_TEXT_CODE_PAGE: Final[str] = "latin-1"

# WHY : Assumptions: line splitting is pinned to the separator alone rather than left to universal
#   newline translation, matching the whole-text scanner in
#   `carddemo_migration.copybook.layouts.iter_ascii_text_records` exactly. That is what makes
#   streaming a handle line by line and passing the whole text produce identical records, and it
#   matters for this corpus specifically: three of the nine shipped ASCII seeds carry carriage
#   returns on some rows and not others, so translating them here would move terminator policy out
#   of the module that owns it and into every caller.
_SEED_LINE_SEPARATOR: Final[str] = "\n"

# WHY : Assumptions: two characters of terminator allowance -- a carriage return and a separator --
#   because that is exactly what the terminator policy strips, and one further character so that an
#   over-long line is DETECTABLE rather than merely truncated. A bounded read that stopped at the
#   record width could not tell a record of exactly that width from the first slice of a longer
#   one; reading one character past the longest legal line distinguishes them, and it is the
#   smallest allowance that does.
_TERMINATOR_ALLOWANCE: Final[int] = 2
_OVERFLOW_PROBE: Final[int] = 1


def _open_regular_descriptor(path: pathlib.Path, *, optional: bool) -> int | None:
    """Open one path read-only and return its descriptor once it is proven a regular file.

    Purpose
    -------
    Perform the single atomic open-then-inspect this module is built on: acquire the descriptor
    without blocking, ask that descriptor what kind of file it is, and refuse anything that is not
    a regular file before one byte is read from it.

    Parameters
    ----------
    path : pathlib.Path
        The exact file to open. No directory is searched and no pattern is expanded.
    optional : bool
        When true, a path that genuinely does not exist yields ``None`` instead of raising. Every
        other failure -- a permission denial, a broken mount, an existing path of the wrong kind --
        raises whether this is set or not.

    Returns
    -------
    int | None
        An open file descriptor the caller owns and must close, or ``None`` when ``optional`` is
        set and nothing exists at ``path``.

    Raises
    ------
    FileNotFoundError
        If nothing exists at ``path`` and ``optional`` is not set.
    OSError
        If the path cannot be opened for any other reason, which includes a permission denial.
    LayoutError
        If something exists at ``path`` and is not a regular file, or if ``path`` is a symbolic
        link whose target does not resolve. The message names the kind found so an operator can
        see that a directory, a device or a dangling link was supplied, rather than being told the
        dataset was empty.
    """
    # WHY : Assumptions: O_NONBLOCK is set for the OPEN and cleared immediately afterwards. It is
    #   what stops the open itself waiting -- opening a FIFO with no writer blocks until one
    #   appears, and a character device may block in its own driver -- and it is cleared before any
    #   read because a regular file's reads must not return EAGAIN on a filesystem that honours the
    #   flag. So the flag is used for exactly the window it is needed for and for no longer.
    # WHY : Assumptions: O_CLOEXEC is set because this package's command-line entry point may spawn
    #   a subprocess -- a psql invocation for the verification passes, for instance -- and a
    #   descriptor on a seed file inherited by an unrelated process is a leak of read access to a
    #   dataset holding primary account numbers.
    # WHY : Trade-offs: os.open is used in preference to Path.open even though the latter reads
    #   better. Path.open cannot express either flag, and both are the point.
    flags = os.O_RDONLY | os.O_NONBLOCK | getattr(os, "O_CLOEXEC", 0)
    try:
        descriptor = os.open(os.fspath(path), flags)
    except FileNotFoundError:
        # WHY : Assumptions: this is the ONLY error that may be read as absence, and it is caught
        #   by its own exception type rather than by inspecting an errno. A permission denial, a
        #   loop of symbolic links, a name too long and an unreachable mount all arrive as other
        #   OSError subclasses and all propagate, because each of them means the caller cannot
        #   tell whether the dataset exists -- and reporting "no records" for a file that may be
        #   full of them is the failure mode this distinction exists to prevent.
        _refuse_dangling_link(path)
        if optional:
            return None
        raise

    try:
        mode = os.fstat(descriptor).st_mode
    except OSError:
        os.close(descriptor)
        raise

    if not stat.S_ISREG(mode):
        os.close(descriptor)
        # WHY : Trade-offs: the refusal names the KIND of file found and the path, and it quotes no
        #   content because none has been read. Naming the kind is what makes the message
        #   actionable: the three ways an operator arrives here are a directory supplied where a
        #   file was meant, a device name typed by mistake, and a FIFO left behind by a staging
        #   script, and each reads differently in the message.
        # WHY : Alternatives Considered: reporting a non-regular path as an empty dataset, which is
        #   what an unguarded open of a directory very nearly does -- it raises, but with an errno
        #   that names neither the record nor the policy that admitted it, and what an unguarded
        #   open of a FIFO does is worse than either, because it does not report at all.
        raise LayoutError(
            f"{path} is not a regular file but a {_describe_file_kind(mode)}, so it cannot be a"
            " fixed-width dataset: a directory, a device or a named pipe supplied where a seed"
            " file was meant is a caller mistake, and reading it would either block indefinitely"
            " or yield bytes that are not records"
        )

    # WHY : Assumptions: blocking mode is restored on the descriptor rather than the flag being
    #   left set. The file has already been proven regular, for which reads never block on a
    #   correctly behaving filesystem, and leaving O_NONBLOCK set would make a short read on an
    #   unusual filesystem indistinguishable from end of file.
    os.set_blocking(descriptor, True)
    return descriptor


def _refuse_dangling_link(path: pathlib.Path) -> None:
    """Raise if a path that reported "no such file" is in fact a symbolic link to nothing.

    Purpose
    -------
    Separate the two states the operating system reports identically. Opening or stating a path
    that does not exist and opening a path that exists as a link to something that does not exist
    both raise :class:`FileNotFoundError`, and only the first is absence: the second is a name
    somebody created deliberately, pointing somewhere it should not.

    Parameters
    ----------
    path : pathlib.Path
        The path whose absence has just been reported by an open or a stat.

    Returns
    -------
    None
        Returning at all means the path genuinely does not exist, and the caller may treat it as
        absent.

    Raises
    ------
    LayoutError
        If the path exists as a symbolic link whose target does not resolve.
    """
    # WHY : Assumptions: os.lstat does NOT follow the link, which is the whole point -- it answers
    #   "does this NAME exist" where the failed call answered "does its TARGET exist". Its own
    #   failure is swallowed rather than reported, because reaching here already means the caller
    #   was told the path is absent; if lstat also cannot see it, absence is the correct answer and
    #   a second error would replace a true statement with a confusing one.
    # WHY : Trade-offs: this stat runs AFTER a failed open rather than before a successful one, so
    #   it introduces no check-then-use window. The open has already decided that nothing was read;
    #   this call decides only how to describe why, so a racing change to the path can at worst
    #   produce a stale explanation and can never cause a file to be read or skipped.
    try:
        os.lstat(path)
    except OSError:
        return
    raise LayoutError(
        f"{path} exists as a symbolic link whose target cannot be resolved, so it is a broken"
        " link rather than an absent dataset. An absent optional source yields no records; a link"
        " that was created and now points nowhere is a staging or configuration fault, and"
        " reporting it as absence would let a load complete having read nothing"
    )


def _describe_file_kind(mode: int) -> str:
    """Name the kind of filesystem object one stat mode describes.

    Purpose
    -------
    Turn a stat mode into the word an operator would use for it, so a refusal can say what was
    found instead of printing an octal mode.

    Parameters
    ----------
    mode : int
        The ``st_mode`` of the object, as :func:`os.fstat` reports it.

    Returns
    -------
    str
        One of ``directory``, ``named pipe``, ``character device``, ``block device``, ``socket``,
        ``symbolic link`` or ``file of unknown kind``.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the tests are ordered by how likely each is to be the actual mistake --
    #   a directory first, then a FIFO left by a staging script -- rather than by the order the
    #   stat module happens to declare them. The final fallback names the mode as unknown instead
    #   of guessing, because a mode this list does not cover is a platform difference rather than
    #   a caller error and inventing a name for it would mislead.
    if stat.S_ISDIR(mode):
        return "directory"
    if stat.S_ISFIFO(mode):
        return "named pipe"
    if stat.S_ISCHR(mode):
        return "character device"
    if stat.S_ISBLK(mode):
        return "block device"
    if stat.S_ISSOCK(mode):
        return "socket"
    if stat.S_ISLNK(mode):
        return "symbolic link"
    return "file of unknown kind"


def iter_seed_lines(
    path: pathlib.Path,
    reclen: int,
    *,
    optional: bool = False,
) -> Iterator[str]:
    """Stream one ASCII seed file's lines from a hardened descriptor, bounding every read.

    Purpose
    -------
    Produce the lines of a seed file in the exact shape
    :func:`carddemo_migration.copybook.layouts.iter_ascii_text_records` consumes -- one element per
    line, terminator included, no phantom trailing element -- while refusing the two source-level
    faults that function cannot see: a path that is not a regular file, and a line long enough
    that reading it is itself the problem.

    Parameters
    ----------
    path : pathlib.Path
        The exact seed file to read. The caller names the file; no directory is searched, because
        the seed directories hold a zero-byte placeholder and, in the EBCDIC tree, names differing
        by a single character, so a pattern match would either sweep the placeholder in or load a
        dataset twice.
    reclen : int
        The declared record length in characters, normally ``<LAYOUT>.reclen``. It bounds each
        read; it does not pad, trim or validate a record, all of which remain the terminator
        policy's business.
    optional : bool, keyword-only
        When true, a genuinely absent file yields no lines instead of raising, for the one record
        whose input may legitimately not exist yet.

    Returns
    -------
    Iterator[str]
        Each line in file order with its terminator as read, at most ``reclen`` plus two
        characters long. A zero-byte file yields nothing and is not an error.

    Raises
    ------
    FileNotFoundError
        If nothing exists at ``path`` and ``optional`` is not set.
    OSError
        If the file cannot be opened or read for any reason other than genuine absence.
    LayoutError
        If ``reclen`` is below one, if ``path`` exists and is not a regular file, or if ``path`` is
        a symbolic link whose target does not resolve.
    RecordLengthError
        If a line is longer than ``reclen`` plus its two terminator characters. The message names
        the line number and both widths and quotes no part of the line.
    """
    if reclen < 1:
        raise LayoutError(
            f"a record length must be at least one character, but reclen={reclen}; the bound on"
            " each read is derived from it, so a non-positive value would make every read either"
            " empty or unbounded"
        )

    descriptor = _open_regular_descriptor(path, optional=optional)
    if descriptor is None:
        return

    # WHY : Assumptions: the read bound is the longest LEGAL line plus one character. A line of
    #   exactly the bound therefore always carries its terminator, and a returned chunk that fills
    #   the bound without a terminator can only be the head of a longer line -- which is the test
    #   below. Without the extra character the two cases are indistinguishable and an over-long
    #   line would be silently truncated into a plausible record.
    limit = reclen + _TERMINATOR_ALLOWANCE + _OVERFLOW_PROBE

    # WHY : Assumptions: the descriptor is handed to io.open with closefd=True, so the wrapper owns
    #   it from here and closing the wrapper closes the descriptor exactly once -- including when
    #   the caller abandons the generator part way through, because the with block unwinds on the
    #   GeneratorExit that abandonment raises. Wrapping is done inside a try that closes the raw
    #   descriptor on failure, since a wrapper that was never constructed cannot close it.
    try:
        handle = io.open(  # noqa: UP020 -- io.open is explicit about which open this is
            descriptor,
            mode="r",
            encoding=SEED_TEXT_CODE_PAGE,
            newline=_SEED_LINE_SEPARATOR,
            closefd=True,
        )
    except BaseException:
        os.close(descriptor)
        raise

    with handle:
        number = 0
        while True:
            # WHY : Trade-offs: readline with an explicit size is used rather than iterating the
            #   handle, which is the shorter expression and has no bound at all. The cost is this
            #   explicit loop and the end-of-file test below; what it buys is that a file holding
            #   one separator-free gigabyte is refused after reading a few hundred characters
            #   instead of after allocating the gigabyte.
            line = handle.readline(limit)
            if not line:
                return
            number += 1
            if len(line) == limit and not line.endswith(_SEED_LINE_SEPARATOR):
                # WHY : Assumptions: the reported length is a lower bound and is described as such,
                #   because the rest of the line was deliberately not read. Reporting the true
                #   length would require reading it, which is the allocation being refused.
                raise RecordLengthError(
                    f"ASCII seed line {number} of {path} is at least {len(line)} characters, which"
                    f" exceeds the declared record length of {reclen} even allowing for a carriage"
                    f" return and a separator; an over-long line means the field offsets have"
                    " moved, so it is refused rather than truncated, and the rest of the line is"
                    " not read"
                )
            yield line


def open_regular_binary(
    path: pathlib.Path,
    *,
    records_of: int | None = None,
    optional: bool = False,
) -> io.BufferedReader | None:
    """Open one fixed-length dataset for byte-mode reading, refusing anything but a regular file.

    Purpose
    -------
    Give the byte path the same single atomic open the character path gets, so that a dataset whose
    name turns out to be a directory or a device is refused by name and kind rather than by an
    errno raised somewhere inside a decode -- and so that the presence of an optional dataset is
    decided by the open that reads it rather than by a separate look at the name.

    Parameters
    ----------
    path : pathlib.Path
        The exact dataset to open.
    records_of : int | None, keyword-only
        When given, the declared record length the dataset's byte size must divide by. The check
        runs before the handle is returned, so a truncated dataset fails up front instead of part
        way through a load.
    optional : bool, keyword-only
        When true, a genuinely absent file yields ``None`` instead of raising.

    Returns
    -------
    io.BufferedReader | None
        An open buffered binary handle the caller owns and must close, or ``None`` when ``optional``
        is set and nothing exists at ``path``.

    Raises
    ------
    FileNotFoundError
        If nothing exists at ``path`` and ``optional`` is not set.
    OSError
        If the file cannot be opened or inspected for any other reason.
    LayoutError
        If ``path`` exists and is not a regular file, if ``path`` is a symbolic link whose target
        does not resolve, or if ``records_of`` is below one.
    EbcdicRecordLengthError
        If ``records_of`` is given and the dataset's size is not an exact multiple of it. The
        message names the dataset, the declared length, the observed size and the remainder.
    """
    if records_of is not None and records_of < 1:
        raise LayoutError(
            "a record length must be at least one byte, but"
            f" records_of={records_of}; the divisibility check is derived from it"
        )

    descriptor = _open_regular_descriptor(path, optional=optional)
    if descriptor is None:
        return None

    try:
        if records_of is not None:
            # WHY : Assumptions: the size comes from the descriptor already opened and the
            #   divisibility rule comes from the layouts module's own published function, so this
            #   check states neither the size nor the rule itself. Restating the rule here is
            #   exactly the drift that would let a stream source and a path source disagree about
            #   whether the same dataset is well formed.
            # WHY : Trade-offs: the layouts error is re-raised as the EBCDIC one so the exception
            #   type a caller documents for a dataset fault stays the dataset-specific type, which
            #   additionally names the file. The original is kept as the cause, so nothing is lost.
            size = os.fstat(descriptor).st_size
            try:
                count_fixed_length_records(size, records_of)
            except RecordLengthError as exc:
                raise EbcdicRecordLengthError(
                    f"dataset {path} does not divide into whole records of {records_of} bytes:"
                    f" {exc}"
                ) from exc
        handle = open(descriptor, mode="rb", closefd=True)
    except BaseException:
        os.close(descriptor)
        raise

    return handle


def seed_dataset_is_present(path: pathlib.Path) -> bool:
    """Report whether an OPTIONAL dataset exists, without treating a bad path as absence.

    Purpose
    -------
    Answer the one question the record whose input may legitimately not exist has to ask, and
    answer only that question: is there genuinely nothing at this path? Every other outcome --
    something that exists but is not a readable regular file -- is a caller mistake and is
    reported as one.

    Parameters
    ----------
    path : pathlib.Path
        The exact source to test. Nothing is opened for reading, no byte is decoded and no
        directory is searched.

    Returns
    -------
    bool
        ``True`` when a regular file exists at ``path``; ``False`` only when nothing exists there.

    Raises
    ------
    IsADirectoryError
        If a directory occupies the path. A directory is not an absent dataset, and reporting it as
        one loads zero rows from a path an operator believes they supplied.
    OSError
        If the path exists but cannot be inspected -- a permission denial on a parent directory or
        a broken mount, for instance -- because in that state the caller cannot know whether the
        dataset exists, and answering "absent" would report a fault as a normal state; and if
        something exists at the path and is neither a directory nor a regular file.
    LayoutError
        If ``path`` is a symbolic link whose target does not resolve.
    """
    # WHY : Refactoring Rationale: this predicate used to be `path.is_file()`, and that single call
    #   collapsed four distinct outcomes into one. is_file() returns False for a directory, for a
    #   broken symbolic link, for a path whose parent denies traversal and for a device -- so every
    #   one of those was reported as "no seed", and the reader then completed successfully having
    #   loaded nothing. A load that silently produces zero rows is the worst available outcome for
    #   an optional dataset, because the verification pass that would catch a short load is written
    #   to accept zero rows for exactly this record.
    # WHY : Assumptions: os.stat FOLLOWS symbolic links, which is deliberate. A link to a regular
    #   file is a perfectly ordinary way to name a staged dataset and must be accepted. A link to
    #   NOTHING raises FileNotFoundError from the stat exactly as a missing name does, and the two
    #   are separated below rather than conflated, because a dangling link is a name somebody
    #   created on purpose and is a fault rather than a normal empty state.
    try:
        mode = os.stat(path).st_mode
    except FileNotFoundError:
        _refuse_dangling_link(path)
        return False

    # WHY : Assumptions: the refusal is an OSError -- IsADirectoryError for a directory and a plain
    #   OSError for every other kind -- rather than a layout error, because the fault being reported
    #   is a filesystem state and not a transcription of a copybook. LayoutError declares itself the
    #   type for a geometry contract broken by a declaration, and the caller that wants to
    #   distinguish "you pointed me at the containing folder" from "this record's geometry is wrong"
    #   can then do so with the standard exception the operating system already defines for it.
    # WHY : Trade-offs: a DIRECTORY gets its own type while a named pipe, a device and a socket
    #   share one. The directory is the mistake an operator actually makes -- supplying the folder a
    #   dataset sits in -- so it is worth catching by type; the remaining kinds are rare enough that
    #   naming the kind in the message serves better than a type apiece, and the message names it.
    # WHY : Assumptions: the errno is EINVAL, an invalid argument, because that is exactly what the
    #   path is: something exists there and it cannot be a fixed-width dataset. ENOENT would claim
    #   nothing is there, which is the conflation this predicate exists to prevent.
    if stat.S_ISDIR(mode):
        raise IsADirectoryError(
            errno.EISDIR,
            f"{path} is a directory rather than a regular file, so it is neither an absent optional"
            " dataset nor a readable one; a directory supplied where a seed file was meant is a"
            " caller mistake and is reported rather than silently treated as absence",
            str(path),
        )
    if not stat.S_ISREG(mode):
        raise OSError(
            errno.EINVAL,
            f"{path} exists but is a {_describe_file_kind(mode)} rather than a regular file, so it"
            " is neither an absent optional dataset nor a readable one. An absent source is a"
            " normal state for this record and yields no records; a path that exists and cannot be"
            " read as a dataset is a caller mistake and is reported rather than silently treated"
            " as absence",
            str(path),
        )
    return True


def require_exact_record_width(record: str, layout: RecordSpec) -> str:
    """Return one record unchanged once its width is proven to equal the declared length.

    Purpose
    -------
    Give every reader's key accessor and every width-sensitive rendering one exact test, so a
    record the decoders would refuse cannot be accepted by a function that only slices part of it.

    Parameters
    ----------
    record : str
        One whole character record, as an iterator yields it or as a caller holds it.
    layout : RecordSpec
        The descriptor whose ``reclen`` is the required width. The width is read from the
        descriptor, never written at the call site.

    Returns
    -------
    str
        ``record`` unchanged.

    Raises
    ------
    RecordLengthError
        If the record is not exactly ``layout.reclen`` characters. The message names both widths
        and quotes no part of the record.
    """
    # WHY : Refactoring Rationale: the eight flat readers each tested `len(record) < reclen` in
    #   their key accessor, which accepted an OVERLONG record their own decoders reject. That
    #   asymmetry is worse than a missing check: a caller that keys a record successfully and then
    #   fails to decode it has been told the record is usable by the function whose whole purpose
    #   is to identify it, and the key sliced from an over-long record is drawn from the right
    #   offsets of the wrong record -- a concatenation of two rows, most plausibly -- so it looks
    #   entirely well formed and a loader upserts on it.
    # WHY : Assumptions: exact equality is the right test for BOTH directions because a record's
    #   width is part of its contract rather than a minimum. A short record makes a sliced key
    #   short, which collides with a sibling row instead of raising; a long one means the source
    #   was cut on the wrong boundary, and no offset in it can be trusted even though the early
    #   ones look right.
    if len(record) != layout.reclen:
        raise RecordLengthError(
            f"a {layout.name} record of {len(record)} characters is not the declared"
            f" {layout.reclen}, so its {layout.key_length}-character key cannot be sliced from the"
            " declared offset; a short record yields a short key that collides with a sibling row"
            " and an over-long one means the source was cut on the wrong boundary"
        )
    return record


def data_region_width(loaded_fields: Sequence[FieldSpec]) -> int:
    """Return the offset through which a record's source must carry data.

    Purpose
    -------
    Derive, in one place, the bound the shared text-record iterator applies before it right-pads a
    short line: the end of the last field the reader PUBLISHES. Everything beyond it is trailing
    pad the seed conversion may legitimately have dropped; everything before it is a value the
    source has to have supplied.

    Parameters
    ----------
    loaded_fields : Sequence of FieldSpec
        The fields the reader publishes, in any order. Taken from the reader's own published
        tuple, so the bound and the published shape are provably the same thing and the padding
        rule is not restated anywhere.

    Returns
    -------
    int
        The furthest end offset among the published fields.

    Raises
    ------
    LayoutError
        If the sequence is empty, which would leave the data region undefined.
    """
    # WHY : Assumptions: the FURTHEST end offset is taken rather than the last field's, and the
    #   difference matters for a record whose published fields are not contiguous -- the security
    #   record publishes fields on both sides of a span it suppresses. The maximum is correct in
    #   both shapes and states no assumption about declaration order.
    # WHY : Refactoring Rationale: this replaces a guard that inspected the PADDED record for a
    #   trailing space, which was correct for the one record it was written for and wrong for the
    #   corpus. The daily transaction's last published field is a processing timestamp that is
    #   legitimately BLANK on all 300 shipped records, so a rule that refused a blank at the end of
    #   the data region would have refused every one of them. The bound has to be compared against
    #   the SOURCE line's length before padding, which only the iterator can do -- so this function
    #   computes the bound and `iter_ascii_text_records` applies it.
    if not loaded_fields:
        raise LayoutError(
            "a reader published no field, so the width of its data region is undefined and a short"
            " line cannot be distinguished from a padded one"
        )
    return max(field.end for field in loaded_fields)
