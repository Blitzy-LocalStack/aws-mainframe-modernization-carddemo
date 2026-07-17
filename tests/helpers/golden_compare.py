"""Deterministic golden-master comparator for the AWS CardDemo test suite.

Purpose
-------
This module is the **primary** golden-master comparator for the CardDemo automated
test suite. Integration (`tests/integration`) and end-to-end (`tests/e2e`) tests call
:func:`assert_matches_golden` to assert that a program's produced output file matches a
checked-in *expected* ("golden") file under ``tests/golden/**/*.expected`` -- **after
normalizing all known non-deterministic content** so the comparison is byte-deterministic
and audit-grade.

It is imported as ``from tests.helpers.golden_compare import assert_matches_golden``.
There is **no** ``__init__.py`` anywhere under ``tests/`` -- the package resolves as a
PEP 420 namespace package via ``PYTHONPATH=<repo_root>``.

Two comparison modes (the central WHY)
--------------------------------------
This comparator distinguishes **two fundamentally different kinds of artifact**, because
conflating them corrupts financial records (CR-04):

* **Record mode** -- ``layout`` names a fixed-width CardDemo record layout (e.g.
  ``"DALYTRAN"``, ``"TRAN"``). The comparison is **byte-exact**: every physical row must be
  exactly the layout's record length; the record's *total width*, trailing FILLER, and the
  deterministic business timestamp ``…-ORIG-TS`` are all **preserved verbatim**; only the
  genuinely run-generated processing timestamp ``…-PROC-TS`` is masked *in place* (blanked
  to spaces at its fixed byte offset, keeping width). No ISO-timestamp regex runs and
  trailing whitespace is **never** stripped in record mode -- doing either would clobber
  ORIG-TS or collapse a 350-byte record to ~278 characters, which was the pre-fix defect.
* **Text mode** -- ``layout`` is ``None``. The artifact is free-form statement/report text
  (plain text or HTML). Here embedded ISO-8601-like run timestamps are scrubbed to a stable
  sentinel and (by default) trailing whitespace is stripped, because in free-form text
  those are formatting artifacts, not positional data.

Other design decisions (WHY)
----------------------------
* **Golden-master over field-by-field assertions (Trade-off).** CardDemo's statement and
  report outputs are large, positionally formatted text. A single deterministic byte-diff
  of the whole artifact is easier to maintain and audit than dozens of brittle field
  offset assertions, at the cost of coarser failure locality -- a trade the project
  accepts (AAP §0.2.2 / §0.10.1). The unified diff embedded in :class:`GoldenMismatchError`
  restores locality when a mismatch does occur.
* **Offset knowledge is single-sourced, not duplicated (Refactoring Rationale).** The
  ``ORIG-TS`` / ``PROC-TS`` byte ranges (and which fields are sensitive) live in exactly one
  place -- ``tests.helpers.record_codec`` -- and are reused via
  :func:`record_codec.normalize_timestamps` and :func:`record_codec.mask_record`. This
  module never hard-codes those offsets; it only supplies the layout *name*.
* **Unknown layouts are rejected, not silently downgraded (Financial correctness).** If a
  caller requests record mode with a layout that ``record_codec`` does not define (or the
  dependency cannot be imported), the comparator raises rather than silently falling back to
  the text-mode regex -- because a silent downgrade would apply width-corrupting text rules
  to a fixed-width record. The regex scrubber is a *text-mode* tool only.
* **Privacy: masked, bounded diagnostics (MA-13).** Mismatch diffs never emit complete
  sensitive records. In record mode each line is passed through
  :func:`record_codec.mask_record` (which redacts PAN, CVV, embossed name, SSN, DOB,
  government id, …) before the diff is rendered; in text mode a best-effort PAN/SSN masker
  is applied. Diffs are also capped at :data:`_DIFF_MAX_LINES`.
* **No binary floating point (Financial correctness).** This module performs only text
  normalization and comparison and deliberately uses **no** ``float`` anywhere; monetary
  values are compared as their exact textual (zoned-decimal) representation, which a
  binary ``float`` could not preserve.
* **Standard library only.** Only ``difflib``, ``os``, ``re``, ``tempfile``, ``pathlib`` and
  ``typing`` are imported so the comparator works before any third-party test dependency is
  present.

Golden regeneration (MA-12 -- guarded)
--------------------------------------
:func:`assert_matches_golden` can (re)write a golden from the normalized actual output, but
only under a **two-step, CI-disabled, path-restricted, atomic** protocol:

1. **Two-step opt-in.** BOTH the environment variable ``CARDDEMO_UPDATE_GOLDENS`` must be
   truthy AND the caller must pass ``update=True``. Either signal alone refuses to write --
   this defeats the earlier vulnerability where an environment flag alone could self-bless
   output.
2. **CI-disabled.** If a CI environment is detected (``CI``/``GITHUB_ACTIONS``/… truthy) the
   write is refused unconditionally, so a pipeline can never approve its own regression.
3. **Path-restricted.** The resolved golden path must live inside a *golden root* (an
   ancestor directory literally named ``golden``, or the directory named by
   ``CARDDEMO_GOLDEN_ROOT``). Writes to arbitrary paths are refused.
4. **Atomic.** The new golden is written to a temp file in the same directory and
   ``os.replace``-d into place, so a reader never observes a half-written golden.
"""

from __future__ import annotations

import difflib
import os
import re
import tempfile
from pathlib import Path
from typing import Callable, List, Optional, Sequence, Tuple, Union

__all__ = [
    "GoldenMismatchError",
    "GoldenUpdateError",
    "normalize",
    "assert_matches_golden",
    "load_and_normalize",
]

# ---------------------------------------------------------------------------
# Module constants.
# ---------------------------------------------------------------------------

# Default ISO-8601-like timestamp matcher.
#
# WHY (Assumption): CardDemo output embeds run timestamps in the space-separated
# ``YYYY-MM-DD HH:MM:SS[.ffffff]`` shape -- verified against the real
# ``app/data/ASCII/dailytran.txt`` seed (e.g. ``2022-06-10 19:27:53.000000``). The ``T``
# alternative is accepted too so genuine ISO-8601 timestamps in generated statement/HTML
# text are caught by the same pattern.
#
# WHY (Division of labour / Refactoring Rationale): this regex is the scrubber for
# timestamps embedded in *free-form* statement/report text, where each stamp is delimited
# by surrounding labels or spaces, and it doubles as the graceful-degradation fallback when
# the structured ``record_codec`` path is unavailable. It is deliberately NOT the primary
# handler for the daily-transaction record, whose ORIG-TS and PROC-TS fields are *adjacent*
# fixed-width columns (cols 279-330) with no separator: a greedy ``\d+`` fractional match
# would bleed from the first stamp into the second's year and fail to blank them cleanly.
# Those adjacent fixed-offset fields are therefore blanked authoritatively by
# :func:`_record_layout_context` via ``record_codec`` byte offsets (record mode); the regex
# is a text-mode-only tool for timestamps embedded in free-form statement/report text.
#
# WHY (Trade-off): a non-capturing group ``(?:\.\d+)?`` is used for the optional fractional
# seconds because the fraction is discarded, not referenced, in the substitution -- a
# capturing group would add an unused match group and a subtle re.sub back-reference trap.
# The fractional quantifier is left unbounded (``\d+``) to honour the documented pattern and
# to accept any sub-second precision that free-form text might carry.
_ISO_TS_RE = re.compile(r"\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?")

# Constant sentinel that replaces every scrubbed ISO timestamp. Its exact spelling is
# irrelevant to correctness as long as it is stable; ``<TS>`` is chosen because it is
# visually obvious in a diff and cannot itself match :data:`_ISO_TS_RE` (so normalization
# is idempotent -- re-normalizing an already-normalized string is a no-op).
_TS_SENTINEL = "<TS>"

# Environment variable that (together with an explicit ``update=True``) opts in to golden
# regeneration. WHY (MA-12): this is only ONE of the two required signals -- it can no
# longer bless a golden on its own.
_UPDATE_ENV_VAR = "CARDDEMO_UPDATE_GOLDENS"

# Optional environment override naming the directory writes must stay within. When unset,
# the golden root is inferred as the nearest ancestor directory literally named ``golden``.
_GOLDEN_ROOT_ENV_VAR = "CARDDEMO_GOLDEN_ROOT"

# Environment variables whose truthiness indicates a CI/automation context in which golden
# regeneration must be refused. WHY (MA-12): a pipeline must never self-approve a changed
# baseline. ``CI`` is the de-facto standard set by virtually every CI system; the others
# cover common providers explicitly so detection does not rely on a single variable.
_CI_ENV_VARS = (
    "CI",
    "CONTINUOUS_INTEGRATION",
    "GITHUB_ACTIONS",
    "GITLAB_CI",
    "JENKINS_URL",
    "BUILDKITE",
    "TF_BUILD",
)

# The literal directory-name marker that identifies the golden tree when
# ``CARDDEMO_GOLDEN_ROOT`` is not set. WHY (Assumption): the AAP places every golden under a
# ``tests/golden/`` tree, so requiring a ``golden`` ancestor is a reliable, layout-stable
# fence that blocks writes to arbitrary paths without hard-coding an absolute location.
_GOLDEN_DIR_MARKER = "golden"

# Values (case-insensitive, whitespace-trimmed) treated as "truthy" for the update flag.
# WHY (Assumption): shells and CI systems spell booleans many ways ("1", "true", "yes",
# "on"); enumerating the accepted set is safer than ``bool(os.environ.get(...))`` which
# would treat the string ``"0"`` or ``"false"`` as truthy simply because it is non-empty.
_TRUTHY_ENV_VALUES = frozenset({"1", "true", "yes", "on", "y", "t"})

# Upper bound on the number of unified-diff lines carried in a mismatch message.
# WHY (Trade-off): a full diff of a multi-thousand-line statement would bury the signal
# and bloat CI logs; 200 lines is enough to localize a defect while staying readable. The
# message states explicitly when it has been truncated so nothing is silently hidden.
_DIFF_MAX_LINES = 200

# Best-effort maskers for SENSITIVE data embedded in FREE-FORM TEXT diffs (text mode only),
# where -- unlike record mode -- there is no layout to drive field-aware masking. WHY
# (MA-13 / Trade-off): a statement body can embed a full PAN or SSN in rendered text; a
# structural mask is impossible without field offsets, so we redact by shape. The patterns
# are deliberately conservative (long digit runs / dashed SSN) to avoid mangling ordinary
# monetary figures, and they run ONLY on the already-truncated diff text, never on the
# compared content itself (so they cannot affect pass/fail, only what a failure prints).
#
# PAN: 13-19 consecutive digits (ISO/IEC 7812 account-number length range).
_PAN_TEXT_RE = re.compile(r"\b\d{13,19}\b")
# SSN: 9 digits, optionally dashed as 3-2-4.
_SSN_TEXT_RE = re.compile(r"\b\d{3}-?\d{2}-?\d{4}\b")


class GoldenMismatchError(AssertionError):
    """Raised when normalized actual output does not match its golden master.

    Purpose
    -------
    Signal a golden-master comparison failure while remaining an
    :class:`AssertionError` (WHY: Assumption) so pytest reports it as an ordinary test
    assertion failure and existing ``except AssertionError`` guards keep working, yet
    callers that want the specific signal can still catch :class:`GoldenMismatchError`.
    The exception message carries the human-readable ``difflib`` unified diff (golden vs
    actual) -- or, for a missing golden, the remediation hint -- so the failure is
    self-describing in CI output without needing to re-run locally.

    Parameters
    ----------
    args : tuple
        Standard :class:`AssertionError` positional arguments -- in practice a single
        string that is either the truncated unified diff or a "golden not found" message.

    Returns
    -------
    GoldenMismatchError
        A new exception instance.

    Raises
    ------
    None
    """


class GoldenUpdateError(RuntimeError):
    """Raised when a golden-regeneration request violates the safe-update protocol.

    Purpose
    -------
    Signal that :func:`assert_matches_golden` was asked to (re)write a golden but the
    request failed one of the MA-12 guards -- it was made in a CI environment, only one of
    the two required opt-in signals was supplied, or the target path lies outside the
    permitted golden root. This is deliberately a :class:`RuntimeError` (NOT an
    :class:`AssertionError`) so it is never mistaken for an ordinary test-assertion failure:
    a blocked *update* is an operator/config error, not a golden mismatch.

    Parameters
    ----------
    args : tuple
        Standard :class:`RuntimeError` positional arguments -- in practice a single string
        explaining which guard blocked the write and how to satisfy it.

    Returns
    -------
    GoldenUpdateError
        A new exception instance.

    Raises
    ------
    None
    """


# ---------------------------------------------------------------------------
# Private helpers.
# ---------------------------------------------------------------------------


def _env_truthy(value: Optional[str]) -> bool:
    """Interpret an environment-variable string as a boolean opt-in flag.

    Purpose
    -------
    Decide whether an environment variable (in practice ``CARDDEMO_UPDATE_GOLDENS``)
    requests golden regeneration, using an explicit allow-list of truthy spellings rather
    than Python's "non-empty string is truthy" rule.

    Parameters
    ----------
    value : str or None
        The raw environment-variable value, or ``None`` when the variable is unset.

    Returns
    -------
    bool
        ``True`` only if ``value`` (trimmed, lower-cased) is one of the recognised truthy
        tokens; ``False`` for ``None``, the empty string, ``"0"``, ``"false"`` etc.

    Raises
    ------
    None
    """
    # WHY (Trade-off): treating an *unrecognised* value (e.g. a typo like "ture") as False
    # is the safe default for a destructive-ish operation -- regenerating goldens -- because
    # a false negative merely means the caller must set the flag correctly, whereas a false
    # positive could silently overwrite a golden and let a regression pass review.
    if value is None:
        return False
    return value.strip().lower() in _TRUTHY_ENV_VALUES


def _running_in_ci() -> bool:
    """Return whether the process appears to be running in a CI/automation environment.

    Purpose
    -------
    Detect a CI context so golden regeneration can be refused there (MA-12): a pipeline must
    never overwrite a golden and thereby self-approve the very change under review.

    Parameters
    ----------
    None

    Returns
    -------
    bool
        ``True`` if any recognised CI environment variable (see :data:`_CI_ENV_VARS`) is
        set to a truthy value; ``False`` otherwise.

    Raises
    ------
    None
    """
    # WHY (Assumption): almost every CI system sets ``CI=true``; the provider-specific
    # variables are checked too so detection does not hinge on a single name a runner might
    # have cleared. A truthy check (not mere presence) is used because some shells export
    # empty variables.
    return any(_env_truthy(os.environ.get(name)) for name in _CI_ENV_VARS)


def _resolve_golden_root(golden: Path) -> Path:
    """Determine the directory a golden write must stay within, for the given target.

    Purpose
    -------
    Compute the *golden root* used to fence golden regeneration (MA-12): a write is only
    permitted if the resolved target lies inside this root. This blocks the earlier
    "write arbitrary paths" vulnerability.

    Parameters
    ----------
    golden : pathlib.Path
        The (already ``resolve()``-d) target golden path.

    Returns
    -------
    pathlib.Path
        The resolved golden root: ``$CARDDEMO_GOLDEN_ROOT`` when set, otherwise the nearest
        ancestor of ``golden`` whose directory name is exactly ``golden`` (the
        :data:`_GOLDEN_DIR_MARKER`).

    Raises
    ------
    GoldenUpdateError
        If ``CARDDEMO_GOLDEN_ROOT`` is unset AND no ancestor named ``golden`` exists, i.e.
        the target is not inside a recognisable golden tree.
    """
    override = os.environ.get(_GOLDEN_ROOT_ENV_VAR)
    if override:
        # An explicit override is honoured verbatim (resolved) so an operator can relocate
        # the golden tree; the containment check in the caller still applies.
        return Path(override).resolve()
    # WHY (Alternatives Considered): walking ancestors for a literal ``golden`` component is
    # preferred over hard-coding ``<repo>/tests/golden`` because this helper has no reliable
    # notion of the repo root, and the marker-directory approach stays correct regardless of
    # where the checkout lives or the current working directory.
    for parent in golden.parents:
        if parent.name == _GOLDEN_DIR_MARKER:
            return parent
    raise GoldenUpdateError(
        f"refusing to regenerate {golden}: it is not inside a '{_GOLDEN_DIR_MARKER}' "
        f"directory and {_GOLDEN_ROOT_ENV_VAR} is not set. Golden writes are restricted to "
        "the golden tree to prevent arbitrary-path writes."
    )


def _atomic_write_text(path: Path, content: str, encoding: str) -> None:
    """Write ``content`` to ``path`` atomically (temp file in the same dir + ``os.replace``).

    Purpose
    -------
    Guarantee that a golden regeneration never leaves a half-written file at ``path`` (MA-12):
    a concurrent reader (or an interrupted run) observes either the old golden or the fully
    written new one, never a truncated intermediate.

    Parameters
    ----------
    path : pathlib.Path
        Destination file path.
    content : str
        Text to write.
    encoding : str
        Text codec used to encode ``content``.

    Returns
    -------
    None

    Raises
    ------
    OSError
        If the temp file cannot be created/written or the atomic replace fails.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    # WHY (Trade-off): mkstemp in the SAME directory guarantees the subsequent os.replace is
    # a same-filesystem atomic rename (a cross-filesystem move would not be atomic). The fd
    # is closed before writing via a Path handle for simpler encoding handling.
    fd, tmp_name = tempfile.mkstemp(dir=str(path.parent), prefix=f".{path.name}.", suffix=".tmp")
    tmp_path = Path(tmp_name)
    try:
        os.close(fd)
        tmp_path.write_text(content, encoding=encoding)
        os.replace(str(tmp_path), str(path))
    except BaseException:
        # On any failure, do not leave the temp artifact behind.
        try:
            tmp_path.unlink()
        except FileNotFoundError:
            pass
        raise


def _mask_text_diagnostic(text: str) -> str:
    """Redact shape-detected PAN/SSN sequences from free-form diagnostic text.

    Purpose
    -------
    Best-effort privacy mask (MA-13) for the TEXT-MODE unified diff, where no record layout
    is available to drive field-aware masking. Applied only to the (already truncated) diff
    string that a failure prints -- never to the content being compared -- so it can affect
    only what a failure reveals, not whether the test passes.

    Parameters
    ----------
    text : str
        The diagnostic text (a unified diff) to redact.

    Returns
    -------
    str
        ``text`` with long digit runs (candidate PANs) and SSN-shaped tokens replaced by a
        fixed redaction marker.

    Raises
    ------
    None
    """
    # SSN first (it is the more specific pattern), then PAN, so a dashed SSN is not partly
    # consumed by the PAN rule. WHY (Assumption): masking the SSN shape before the long-digit
    # rule avoids leaving a partial identifier behind.
    text = _SSN_TEXT_RE.sub("<REDACTED-SSN>", text)
    text = _PAN_TEXT_RE.sub("<REDACTED-PAN>", text)
    return text


def _coerce_actual_to_text(
    actual: Union[str, bytes, bytearray, os.PathLike],
    encoding: str,
) -> str:
    """Resolve the ``actual`` argument of :func:`assert_matches_golden` to text.

    Purpose
    -------
    Accept the three shapes callers naturally have on hand -- an in-memory string, raw
    bytes read from a program's output, or a filesystem path to that output -- and return
    a single decoded ``str`` for normalization.

    Parameters
    ----------
    actual : str or bytes or bytearray or os.PathLike
        The produced output to compare. A ``str`` is treated as the **literal output
        content** (not a path); ``bytes``/``bytearray`` are decoded with ``encoding``; an
        ``os.PathLike`` is read from disk and decoded with ``encoding``.
    encoding : str
        Text codec used to decode ``bytes`` inputs and to read path inputs.

    Returns
    -------
    str
        The output content as text.

    Raises
    ------
    TypeError
        If ``actual`` is none of the accepted types.
    FileNotFoundError
        If ``actual`` is a path that does not exist.
    UnicodeDecodeError
        If ``bytes``/file content cannot be decoded with ``encoding``.
    """
    # WHY (Assumption): a bare ``str`` is intentionally the *content*, never a path. The
    # primary call site passes a program's captured output directly as a string, and
    # overloading ``str`` to sometimes mean "a path" would make the API ambiguous. Callers
    # that hold a path pass a ``pathlib.Path`` (an ``os.PathLike``) instead.
    if isinstance(actual, str):
        return actual
    if isinstance(actual, (bytes, bytearray)):
        return bytes(actual).decode(encoding)
    if isinstance(actual, os.PathLike):
        return Path(os.fspath(actual)).read_text(encoding=encoding)
    raise TypeError(
        "actual must be str (literal content), bytes/bytearray, or os.PathLike; "
        f"got {type(actual).__name__}"
    )


def _actual_label(actual: Union[str, bytes, bytearray, os.PathLike]) -> str:
    """Derive a diff-header label for the ``actual`` side of a comparison.

    Purpose
    -------
    Provide a meaningful ``tofile`` label for the unified diff: the source path when the
    actual output came from a file, or a synthetic ``"<actual>"`` marker for in-memory
    string/bytes content.

    Parameters
    ----------
    actual : str or bytes or bytearray or os.PathLike
        The same value passed to :func:`assert_matches_golden`.

    Returns
    -------
    str
        The path (for ``os.PathLike`` inputs) or the constant ``"<actual>"``.

    Raises
    ------
    None
    """
    # WHY (Trade-off): only a real path carries a useful name; string/bytes content has no
    # location, so a stable placeholder keeps the diff header informative without inventing
    # a misleading filename.
    if isinstance(actual, os.PathLike):
        return os.fspath(actual)
    return "<actual>"


def _record_layout_context(layout: str) -> "tuple[object, int, Callable[[str], str], Callable[[str], str]]":
    """Resolve everything record-mode needs for ``layout``, or raise if it cannot.

    Purpose
    -------
    Look up a fixed-width record layout in ``record_codec`` and return its layout object,
    record length, and the two byte-exact transforms record mode applies: a PROC-TS-only
    timestamp masker (preserving ORIG-TS and width) and a sensitive-field masker for
    diagnostics. Unlike the earlier resolver, this **raises** on an unknown layout or an
    unavailable dependency instead of silently downgrading to the width-corrupting text
    regex (CR-04) -- a record layout must be compared byte-exactly or not at all.

    Parameters
    ----------
    layout : str
        The logical record-layout name (a key of ``record_codec.LAYOUTS`` such as
        ``"DALYTRAN"`` or ``"TRAN"``).

    Returns
    -------
    tuple[object, int, Callable[[str], str], Callable[[str], str]]
        ``(layout_obj, reclen, scrub_proc_ts, mask_record)`` where ``scrub_proc_ts(line)``
        blanks only the PROC-TS field (preserving ORIG-TS and total width) and
        ``mask_record(line)`` redacts sensitive fields for diagnostics.

    Raises
    ------
    ImportError
        If ``record_codec`` cannot be imported while a record layout was requested (record
        mode cannot be honoured without the authoritative byte offsets).
    ValueError
        If ``layout`` is not a registered record layout.
    """
    # WHY (Alternatives Considered): the import is still lazy (per the helper package's
    # no-top-level-internal-import convention), but a failure is now RAISED, not swallowed.
    # In record mode there is no safe fallback: applying the text regex to a fixed-width
    # record would corrupt its width and clobber ORIG-TS (the CR-04 defect).
    from tests.helpers import record_codec  # noqa: WPS433 (intentional local import)

    try:
        layout_obj = record_codec.LAYOUTS[layout]
    except KeyError as exc:
        known = ", ".join(sorted(record_codec.LAYOUTS))
        raise ValueError(
            f"unknown record layout {layout!r}; record-mode comparison requires a layout "
            f"registered in record_codec.LAYOUTS (known: {known})"
        ) from exc

    reclen = record_codec.reclen_of(layout)
    normalize_timestamps = record_codec.normalize_timestamps
    mask_record = record_codec.mask_record

    def _scrub_proc_ts(line: str) -> str:
        """Blank the layout's PROC-TS field in one record line, preserving width + ORIG-TS.

        Purpose
        -------
        Apply :func:`record_codec.normalize_timestamps` to a single line using the resolved
        layout captured in the enclosing scope. Because the layout marks only PROC-TS with
        ``normalize_ts`` (ORIG-TS is deterministic business data), this masks the run
        timestamp in place and leaves every other byte -- including ORIG-TS and trailing
        FILLER -- untouched.

        Parameters
        ----------
        line : str
            One raw fixed-width record line (without its newline terminator). Must be
            exactly the layout's record length.

        Returns
        -------
        str
            The line with the PROC-TS field blanked and the total width preserved.

        Raises
        ------
        record_codec.RecordLengthError
            If ``line`` is not exactly the layout's record length (raised by
            ``normalize_timestamps`` -- the strict-width contract from CR-03/CR-04).
        """
        return normalize_timestamps(line, layout_obj)

    def _mask_record_line(line: str) -> str:
        """Redact sensitive fields in one record line for diagnostics (closure over layout).

        Purpose
        -------
        Apply :func:`record_codec.mask_record` so a mismatch diff never prints a complete
        PAN/SSN/name/DOB/government-id (MA-13).

        Parameters
        ----------
        line : str
            One raw fixed-width record line.

        Returns
        -------
        str
            The line with sensitive fields masked (width preserved).

        Raises
        ------
        record_codec.RecordLengthError
            If ``line`` is not exactly the layout's record length.
        """
        return mask_record(line, layout_obj)

    return layout_obj, reclen, _scrub_proc_ts, _mask_record_line


def _build_unified_diff(
    golden_text: str,
    actual_text: str,
    *,
    fromfile: str,
    tofile: str,
    line_masker: Optional[Callable[[str], str]] = None,
    max_lines: int = _DIFF_MAX_LINES,
) -> str:
    """Render a truncated, privacy-masked ``difflib`` unified diff of golden vs actual.

    Purpose
    -------
    Produce a compact, human-readable, **privacy-masked** unified diff for a
    :class:`GoldenMismatchError` message, oriented golden-to-actual (removed lines are
    golden, added lines are actual) and capped at ``max_lines`` so CI logs stay legible.
    Sensitive data is redacted BEFORE the diff is rendered (MA-13): in record mode via the
    supplied field-aware ``line_masker``; in text mode (no masker) via a shape-based
    PAN/SSN redactor applied to the finished diff text.

    Parameters
    ----------
    golden_text : str
        The normalized expected (golden) content.
    actual_text : str
        The normalized produced (actual) content.
    fromfile : str
        Label for the golden side of the diff header (typically the golden file path).
    tofile : str
        Label for the actual side of the diff header (a path or ``"<actual>"``).
    line_masker : Callable[[str], str] or None
        Record-mode field-aware masker applied to EACH line of both sides before diffing.
        ``None`` selects text-mode shape-based redaction of the finished diff instead.
    max_lines : int
        Maximum number of diff lines to include before truncating. Defaults to
        :data:`_DIFF_MAX_LINES`.

    Returns
    -------
    str
        The masked, possibly-truncated unified-diff text; empty string only if the two
        inputs are identical (which callers avoid by diffing on mismatch only).

    Raises
    ------
    None
    """
    golden_lines = golden_text.splitlines()
    actual_lines = actual_text.splitlines()

    # Record mode: mask each line field-aware BEFORE diffing so no complete sensitive record
    # can ever reach the diff. WHY (MA-13): masking pre-diff (not post) guarantees the
    # redaction is structural and cannot be defeated by how difflib chunks the output.
    if line_masker is not None:
        golden_lines = [line_masker(ln) for ln in golden_lines]
        actual_lines = [line_masker(ln) for ln in actual_lines]

    # WHY (Trade-off): ``splitlines()`` without ``keepends`` combined with ``lineterm=""``
    # yields clean diff lines that we join with a single ``\n``. The alternative
    # (``keepends=True`` with the default ``lineterm``) risks doubled or missing newlines
    # when the final line lacks a terminator, producing a ragged, harder-to-read diff.
    diff_lines: Sequence[str] = list(
        difflib.unified_diff(
            golden_lines,
            actual_lines,
            fromfile=fromfile,
            tofile=tofile,
            lineterm="",
        )
    )
    if len(diff_lines) > max_lines:
        kept: List[str] = list(diff_lines[:max_lines])
        kept.append(
            f"... diff truncated to {max_lines} lines "
            f"({len(diff_lines) - max_lines} more) ..."
        )
        diff_text = "\n".join(kept)
    else:
        diff_text = "\n".join(diff_lines)

    # Text mode: no field offsets are available, so apply a shape-based PAN/SSN redactor to
    # the finished diff string as a privacy backstop (MA-13). In record mode the content is
    # already field-masked, but running the backstop is harmless and idempotent.
    if line_masker is None:
        diff_text = _mask_text_diagnostic(diff_text)
    return diff_text


# ---------------------------------------------------------------------------
# Public API.
# ---------------------------------------------------------------------------


def normalize(
    text: str,
    *,
    layout: Optional[str] = None,
    strip_trailing_ws: Optional[bool] = None,
    extra_patterns: Optional[Sequence[Tuple[str, str]]] = None,
) -> str:
    """Normalize non-deterministic content out of text so goldens compare stably.

    Operates in one of two modes, selected by ``layout`` (see the module docstring):

    * **Record mode** (``layout`` given) -- byte-exact. The body is framed into fixed-width
      records **by width**: any newlines that are present are honoured as separators and then
      each segment is sliced into exact ``reclen``-sized records. This makes a raw
      ``ORGANIZATION SEQUENTIAL`` output -- which is ``N*reclen`` concatenated bytes with NO
      delimiters (CBTRN02C's DALYREJS reject stream and CBACT04C's TRANSACT interest stream) --
      compare equal to the committed golden, which stores one record per line (QA findings
      M3 + C3). Each ``normalize_ts`` field is blanked *in place* while every other byte --
      the deterministic ORIG-TS of a posted transaction, trailing FILLER (including the
      LOW-VALUES ``0x00`` GnuCOBOL emits for un-populated fields), and total record width --
      is preserved verbatim. No ISO regex runs and trailing whitespace is never stripped.
      This fixes the pre-fix defect where a 350-byte record collapsed to ~278 characters
      (CR-04). The trailing EOF newline is canonicalised (a program output of ``N*reclen``
      bytes with no final newline and a committed golden with one final newline are treated
      as equal), so the reclen+1 golden framing round-trips faithfully.
    * **Text mode** (``layout is None``) -- free-form statement/report text. Embedded
      ISO-8601-like timestamps are replaced by a stable sentinel and (by default) trailing
      whitespace is stripped, because in free-form text those are formatting artifacts.

    Parameters
    ----------
    text : str
        The raw text to normalize (a decoded program-output or golden-file body).
    layout : str or None
        Logical record-layout name (e.g. ``"DALYTRAN"``, ``"TRAN"``) to select byte-exact
        record mode, or ``None`` (default) for free-form text mode. An unknown layout name
        raises (record mode is never silently downgraded to text rules).
    strip_trailing_ws : bool or None
        Text-mode only. ``None`` (default) means "auto": strip in text mode. Passing
        ``True`` in **record mode** raises, because stripping trailing FILLER would collapse
        a fixed-width record (the CR-04 defect). In text mode ``True``/``False`` force the
        behaviour explicitly.
    extra_patterns : sequence of (str, str) or None
        Text-mode only. Optional additional ``(regex_pattern, replacement)`` pairs applied
        per line after the default timestamp scrubbing. Passing them in **record mode**
        raises, because an arbitrary substitution could alter a record's width and break the
        byte-exact contract. Defaults to ``None``.

    Returns
    -------
    str
        The normalized text with ``\\n`` line endings. In record mode every record retains
        the layout's exact width (only PROC-TS blanked) and the output ends with exactly one
        trailing newline for non-empty content (empty input yields ``""``); in text mode
        timestamps are replaced by a stable sentinel.

    Raises
    ------
    ValueError
        If ``layout`` is unknown, or if ``strip_trailing_ws=True`` / ``extra_patterns`` are
        supplied together with a ``layout`` (both would violate byte-exactness).
    ImportError
        If ``record_codec`` cannot be imported while a ``layout`` was requested.
    record_codec.RecordLengthError
        In record mode, if any physical row is not exactly the layout's record length.
    re.error
        If a pattern in ``extra_patterns`` is not a valid regular expression (text mode).
    """
    # Unify line endings first so downstream per-line logic sees exactly one terminator
    # style. WHY (Trade-off): fixed-width batch output may be produced on platforms that
    # differ only in CR/LF vs LF; collapsing them up front prevents a whole-file diff that
    # is really just an end-of-line artifact.
    text = text.replace("\r\n", "\n").replace("\r", "\n")

    if layout is not None:
        # --- RECORD MODE: byte-exact ------------------------------------------------
        # WHY (CR-04): record mode must not accept width-altering options. Rejecting them
        # loudly is safer than silently ignoring a caller's request, which could hide a
        # test that wrongly expected text-mode behaviour on a fixed-width record.
        if strip_trailing_ws:
            raise ValueError(
                "strip_trailing_ws=True is invalid for a fixed-width record layout "
                f"({layout!r}): stripping trailing FILLER would collapse the record width."
            )
        if extra_patterns:
            raise ValueError(
                "extra_patterns is invalid for a fixed-width record layout "
                f"({layout!r}): an arbitrary substitution could change the record width."
            )
        _layout_obj, _reclen, scrub_proc_ts, _mask = _record_layout_context(layout)

        # --- EOF-newline canonicalisation (QA finding M3) ---------------------------
        # WHY: a fixed-width record file is fully self-delimited by its record length, so
        # whether the file ends with a trailing newline is a *framing artifact*, not record
        # data. CBTRN02C (DALYREJS) and the batch programs' indexed outputs are read back as
        # exactly N*reclen bytes with NO trailing newline, whereas the committed goldens are
        # stored with a single trailing newline (the reclen+1 framing: dalyrejs=431,
        # acctdat=301, tcatbal=51). The previous implementation preserved the trailing empty
        # element produced by ``split("\n")``, so a 430-byte program output compared UNEQUAL
        # to its 431-byte golden purely because of that one EOF byte, and ``update=True``
        # regeneration wrote a byte that then failed to round-trip. Canonicalising BOTH
        # sides to "records joined by \n + exactly one trailing \n" (for non-empty content)
        # makes the with/without-EOF-newline pair compare equal AND keeps regeneration
        # byte-faithful to the committed framing. Within-record bytes are untouched, so the
        # byte-exact record contract (only PROC-TS blanked) is fully preserved.
        #
        # WHY treat ""/"\n" as empty (Assumption): an empty file, or a file that is a lone
        # newline, represents zero records and must match the 0-byte empty goldens
        # (e.g. tranfile.expected for reject scenarios). This mirrors
        # ``vsam_loader._validated_blob``'s own empty-input handling, keeping the two
        # helpers' notion of "empty" identical (single source of truth).
        if text in ("", "\n"):
            return ""
        segments = text.split("\n")
        if segments and segments[-1] == "":
            # Drop the single empty element produced by a trailing EOF newline. A remaining
            # zero-width segment is handled by the per-segment guard below (it is an INTERIOR
            # blank line -- a malformed zero-width row -- and must never be silently accepted).
            segments.pop()

        # --- Width-based framing (QA findings M3 + C3) ------------------------------
        # WHY: GnuCOBOL ``ORGANIZATION IS SEQUENTIAL`` outputs are raw *concatenated*
        # fixed-width records with NO delimiter between them. CBTRN02C's DALYREJS reject
        # stream and CBACT04C's TRANSACT interest stream are both written this way, so a
        # two-record interest output arrives as a single 700-byte blob (2*350) that contains
        # zero newlines. The committed goldens, by contrast, store one record per line
        # (reclen+1 framing). Framing must therefore be by fixed WIDTH while *tolerating*
        # optional newline separators, so that BOTH the separator-less program output and the
        # newline-delimited golden reduce to the identical record sequence:
        #   * split on any newlines that ARE present (handles the golden side), then
        #   * slice each resulting segment into exact reclen-sized records (handles the raw
        #     program-output side, which arrives as one long segment).
        # WHY reject a non-multiple segment (Assumption): a segment whose length is not a
        # positive whole multiple of reclen cannot be a run of fixed-width records, so it is
        # a genuinely malformed row; it is handed to the codec, which raises RecordLengthError
        # (reporting expected-vs-actual width) rather than being silently padded/truncated.
        # The previous implementation validated each newline-split segment as ONE record,
        # which worked only for single-record outputs and wrongly rejected any multi-record
        # separator-less sequential file (the real CBACT04C interest output). Within-record
        # bytes -- including LOW-VALUES (0x00) FILLER that GnuCOBOL emits for un-populated
        # trailing fields -- are preserved verbatim; only the normalize_ts fields are blanked.
        records: List[str] = []
        for segment in segments:
            if not segment or (len(segment) % _reclen) != 0:
                # Zero-width or non-multiple: force the codec's precise RecordLengthError.
                scrub_proc_ts(segment)
            for _off in range(0, len(segment), _reclen):
                records.append(scrub_proc_ts(segment[_off:_off + _reclen]))
        return "\n".join(records) + "\n"

    # --- TEXT MODE: free-form statement/report ------------------------------------------
    effective_strip = True if strip_trailing_ws is None else bool(strip_trailing_ws)

    # Pre-compile any caller-supplied extra patterns once. WHY (Refactoring Rationale):
    # compiling inside the per-line loop would recompile the same patterns for every line
    # of a large statement; hoisting the compile keeps normalization linear in the input.
    compiled_extra: List[Tuple[re.Pattern, str]] = (
        [(re.compile(pat), repl) for pat, repl in extra_patterns]
        if extra_patterns
        else []
    )

    text_lines: List[str] = []
    for line in text.split("\n"):
        # Regex timestamp scrubbing: catches ISO timestamps embedded in free-form
        # statement/report text. This is a TEXT-mode tool only -- it is never applied to a
        # fixed-width record (record mode handled and returned above).
        line = _ISO_TS_RE.sub(_TS_SENTINEL, line)

        # Caller-supplied normalizations apply after the built-in ones so a test can layer
        # domain-specific scrubbing on top of the standard timestamp handling.
        for pattern, replacement in compiled_extra:
            line = pattern.sub(replacement, line)

        if effective_strip:
            # Right-strip only. WHY (Trade-off): free-form report text may carry trailing
            # spaces whose exact count is an artifact, not data; stripping the right edge
            # yields a stable diff. Leading whitespace is preserved because it is
            # positionally significant in the report layouts.
            line = line.rstrip()

        text_lines.append(line)

    return "\n".join(text_lines)


def load_and_normalize(
    path: Union[str, os.PathLike],
    *,
    layout: Optional[str] = None,
    encoding: str = "utf-8",
) -> str:
    """Read a file from disk and return its normalized text.

    Purpose
    -------
    Convenience wrapper for tests that want the normalized form of a file directly (for
    example to make their own assertions) without duplicating the read-then-:func:`normalize`
    sequence.

    Parameters
    ----------
    path : str or os.PathLike
        Filesystem path of the file to read.
    layout : str or None
        Optional record-layout name forwarded to :func:`normalize`. Defaults to ``None``.
    encoding : str
        Text codec used to read the file. Defaults to ``"utf-8"``.

    Returns
    -------
    str
        The file's content after :func:`normalize`.

    Raises
    ------
    FileNotFoundError
        If ``path`` does not exist.
    UnicodeDecodeError
        If the file content cannot be decoded with ``encoding``.
    """
    content = Path(os.fspath(path)).read_text(encoding=encoding)
    return normalize(content, layout=layout)


def _authorize_golden_update(update: Optional[bool], golden: Path) -> bool:
    """Decide whether a golden write is authorized under the MA-12 safe-update protocol.

    Purpose
    -------
    Centralise the golden-regeneration guards so :func:`assert_matches_golden` cannot write
    a golden unless every safety condition holds: an explicit ``update=True`` (never the env
    var alone), the ``CARDDEMO_UPDATE_GOLDENS`` arm set, not running in CI, and a target that
    resolves inside the permitted golden root.

    Parameters
    ----------
    update : bool or None
        The caller's explicit intent. ``True`` requests a write; ``False`` forces compare;
        ``None`` defers (and, because the env var alone can no longer bless, means compare).
    golden : pathlib.Path
        The target golden path (as supplied, before resolution).

    Returns
    -------
    bool
        ``True`` if and only if a write is authorized. ``False`` means "compare, do not
        write" (the normal path).

    Raises
    ------
    GoldenUpdateError
        If ``update is True`` but a guard fails: running in CI, the ``CARDDEMO_UPDATE_GOLDENS``
        arm is not set, or the target is outside the golden root.
    """
    # WHY (MA-12 -- close the "env alone self-blesses" hole): the environment variable is no
    # longer sufficient on its own. Only an explicit code-level update=True even *attempts* a
    # write; anything else compares.
    if update is not True:
        return False

    # Guard 1 -- never write in CI (a pipeline must not self-approve its own change).
    if _running_in_ci():
        raise GoldenUpdateError(
            "refusing to regenerate goldens in a CI environment "
            f"({', '.join(v for v in _CI_ENV_VARS if _env_truthy(os.environ.get(v)))}). "
            "Regenerate locally, review the diff, and commit the golden."
        )

    # Guard 2 -- two-step arm: the operator must also set the env var, so a stray committed
    # update=True cannot silently rewrite goldens on someone else's machine.
    if not _env_truthy(os.environ.get(_UPDATE_ENV_VAR)):
        raise GoldenUpdateError(
            f"golden update requires BOTH update=True AND {_UPDATE_ENV_VAR}=1 "
            "(two-step opt-in). The environment arm is not set; refusing to write."
        )

    # Guard 3 -- path containment: the resolved target must live inside the golden root.
    resolved = golden.resolve()
    root = _resolve_golden_root(resolved)
    try:
        inside = resolved == root or root in resolved.parents
    except (OSError, ValueError):  # pragma: no cover - defensive
        inside = False
    if not inside:
        raise GoldenUpdateError(
            f"refusing to regenerate {resolved}: it is outside the golden root {root}."
        )
    return True


def assert_matches_golden(
    actual: Union[str, bytes, bytearray, os.PathLike],
    golden_path: Union[str, os.PathLike],
    *,
    layout: Optional[str] = None,
    encoding: str = "utf-8",
    update: Optional[bool] = None,
    extra_patterns: Optional[Sequence[Tuple[str, str]]] = None,
) -> None:
    """Assert that produced output matches its golden master after normalization.

    Purpose
    -------
    The suite's primary golden-master assertion. Normalizes both the produced ``actual``
    output and the checked-in ``golden_path`` content (record mode byte-exact, or text mode
    per :func:`normalize`) and raises :class:`GoldenMismatchError` -- carrying a
    privacy-masked, bounded unified diff -- if they differ. Can (re)write the golden only
    under the guarded two-step protocol enforced by :func:`_authorize_golden_update`.

    Parameters
    ----------
    actual : str or bytes or bytearray or os.PathLike
        The produced output. A ``str`` is the literal content; ``bytes``/``bytearray`` are
        decoded with ``encoding``; an ``os.PathLike`` is read from disk. See
        :func:`_coerce_actual_to_text`.
    golden_path : str or os.PathLike
        Path to the expected ("golden") file.
    layout : str or None
        Optional record-layout name forwarded to :func:`normalize` for both sides. Given
        (e.g. ``"DALYTRAN"``/``"TRAN"``) it selects byte-exact record mode and also drives
        field-aware masking of the failure diff. Defaults to ``None`` (text mode).
    encoding : str
        Text codec for decoding ``actual`` bytes/paths and for reading/writing the golden.
        Defaults to ``"utf-8"``.
    update : bool or None
        Golden-regeneration control, gated by :func:`_authorize_golden_update`. Only an
        explicit ``True`` -- **together with** ``CARDDEMO_UPDATE_GOLDENS=1``, **not** in CI,
        and a target inside the golden root -- triggers an atomic rewrite; ``None``/``False``
        compare. The environment variable alone can no longer bless a golden. Defaults to
        ``None``.
    extra_patterns : sequence of (str, str) or None
        Text-mode-only additional ``(regex_pattern, replacement)`` pairs forwarded to
        :func:`normalize` for both sides. Invalid together with a record ``layout``.
        Defaults to ``None``.

    Returns
    -------
    None
        Returns normally when the normalized actual matches the golden (or after a
        successful, authorized regeneration).

    Raises
    ------
    GoldenMismatchError
        If the golden file is missing while not regenerating, or if the normalized actual
        differs from the normalized golden (the message contains a masked, truncated diff).
    GoldenUpdateError
        If a golden write is requested but blocked by a safe-update guard (CI, missing env
        arm, or out-of-root target).
    ValueError
        If ``layout`` is unknown, or record-mode-invalid options were supplied (see
        :func:`normalize`).
    TypeError
        If ``actual`` is not one of the accepted types.
    UnicodeDecodeError
        If ``actual`` or the golden cannot be decoded with ``encoding``.
    """
    actual_text = _coerce_actual_to_text(actual, encoding)
    normalized_actual = normalize(
        actual_text, layout=layout, extra_patterns=extra_patterns
    )

    # Resolve the golden path once; used for reads, writes, and the diff header label.
    golden = Path(os.fspath(golden_path))

    # Decide (and authorize) golden-regeneration under the MA-12 protocol BEFORE any write.
    if _authorize_golden_update(update, golden):
        # Atomic write into the (already-validated) golden root. WHY (MA-12): os.replace of a
        # same-dir temp file means a reader never sees a half-written golden.
        _atomic_write_text(golden, normalized_actual, encoding)
        # WHY (Assumption): after writing we return without asserting -- a regeneration run
        # is a deliberate human action to accept the current output as the new baseline, so
        # asserting it against itself would be redundant.
        return

    if not golden.exists():
        # A missing golden with regeneration OFF is a hard error, not a silent pass, so an
        # untracked/forgotten baseline cannot let a test appear to succeed. The message
        # tells the operator exactly how to create it under the two-step protocol.
        raise GoldenMismatchError(
            f"golden not found: {golden}; regenerate locally with "
            f"{_UPDATE_ENV_VAR}=1 and update=True (not in CI) to create it from the "
            "current output"
        )

    golden_text = golden.read_text(encoding=encoding)
    # Normalize the golden with the SAME rules as the actual. WHY (Assumption): although a
    # blessed golden is already normalized (regeneration writes normalized text) and
    # normalization is idempotent, applying it to both sides also tolerates a hand-authored
    # golden that still contains a raw timestamp -- keeping the comparison robust.
    normalized_golden = normalize(
        golden_text, layout=layout, extra_patterns=extra_patterns
    )

    if normalized_actual != normalized_golden:
        # Resolve a field-aware line masker for record mode so the diff cannot leak a
        # complete sensitive record (MA-13); text mode passes None (shape-based backstop).
        line_masker: Optional[Callable[[str], str]] = None
        if layout is not None:
            # The layout already validated during normalize(); this cannot fail here.
            _obj, _rl, _scrub, line_masker = _record_layout_context(layout)
        diff = _build_unified_diff(
            normalized_golden,
            normalized_actual,
            fromfile=str(golden),
            tofile=_actual_label(actual),
            line_masker=line_masker,
        )
        raise GoldenMismatchError(
            f"actual output does not match golden {golden}:\n{diff}"
        )
