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

Design decisions (WHY)
----------------------
* **Golden-master over field-by-field assertions (Trade-off).** CardDemo's statement and
  report outputs are large, positionally formatted text. A single deterministic byte-diff
  of the whole artifact is easier to maintain and audit than dozens of brittle field
  offset assertions, at the cost of coarser failure locality -- a trade the project
  accepts (AAP §0.2.2 / §0.10.1). The unified diff embedded in :class:`GoldenMismatchError`
  restores locality when a mismatch does occur.
* **Normalize before diffing (Determinism).** Two program runs differ only in wall-clock
  content: the two fixed-offset timestamp fields of the daily-transaction record
  (``DALYTRAN-ORIG-TS`` / ``DALYTRAN-PROC-TS``) and any ISO-8601-like timestamp embedded
  in statement/report text. Both are blanked/replaced *before* comparison so goldens do
  not flap. Failing to normalize either source is the single most common way golden
  suites become non-deterministic, so both are handled here.
* **Offset knowledge is single-sourced, not duplicated (Refactoring Rationale).** The
  ``ORIG-TS`` / ``PROC-TS`` byte ranges live in exactly one place --
  ``tests.helpers.record_codec`` -- and are reused via
  :func:`record_codec.normalize_timestamps`. This module never hard-codes those offsets;
  it only supplies the layout *name* (e.g. ``"DALYTRAN"``).
* **Soft, lazy dependency on record_codec (Alternatives Considered).** ``record_codec`` is
  imported lazily *inside* the normalization path, and any import problem falls back to
  the regex timestamp scrubber. A hard module-level import was rejected because it would
  couple import order across the helper package and make this comparator unusable in a
  bare checkout or if ``record_codec`` is later refactored; the regex fallback keeps the
  comparator independently useful.
* **No binary floating point (Financial correctness).** This module performs only text
  normalization and comparison and deliberately uses **no** ``float`` anywhere; monetary
  values are compared as their exact textual (zoned-decimal) representation, which a
  binary ``float`` could not preserve.
* **Standard library only.** Only ``difflib``, ``os``, ``re``, ``pathlib`` and ``typing``
  are imported so the comparator works before any third-party test dependency is present.

Golden regeneration
--------------------
:func:`assert_matches_golden` can (re)write a golden from the normalized actual output
when explicitly asked -- either ``update=True`` or the environment variable
``CARDDEMO_UPDATE_GOLDENS`` set to a truthy value. This mirrors ``pytest --update-goldens``
(AAP §0.2.2) but is **OFF by default** so CI can never silently self-approve a change.
"""

from __future__ import annotations

import difflib
import os
import re
from pathlib import Path
from typing import Callable, List, Optional, Sequence, Tuple, Union

__all__ = [
    "GoldenMismatchError",
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
# :func:`_resolve_layout_normalizer` via ``record_codec`` byte offsets; the regex remains a
# best-effort safety net for the degraded (no-record_codec) mode.
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

# Environment variable that opts in to golden regeneration when ``update`` is left unset.
_UPDATE_ENV_VAR = "CARDDEMO_UPDATE_GOLDENS"

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


def _resolve_layout_normalizer(
    layout: Optional[str],
) -> Optional[Callable[[str], str]]:
    """Build a per-line structured timestamp scrubber for ``layout``, if one applies.

    Purpose
    -------
    Return a callable that blanks the fixed-offset timestamp fields of a single record
    line for the named layout, sourcing the offsets from
    :func:`tests.helpers.record_codec.normalize_timestamps`. Returns ``None`` -- meaning
    "rely on the regex scrubber alone" -- when no layout is requested, when the dependency
    cannot be imported, or when the layout declares no timestamp fields.

    Parameters
    ----------
    layout : str or None
        The logical record-layout name (a key of ``record_codec.LAYOUTS`` such as
        ``"DALYTRAN"``), or ``None`` to skip structured normalization entirely.

    Returns
    -------
    Callable[[str], str] or None
        A function mapping one raw record line to its timestamp-blanked form, or ``None``
        if structured normalization is unavailable / not applicable.

    Raises
    ------
    None
    """
    if layout is None:
        return None

    # WHY (Alternatives Considered): the import is performed here, lazily, rather than at
    # module top level. A hard top-level ``import`` was rejected because it would (a) fail
    # in a bare checkout where the helper package is not yet importable and (b) create a
    # fixed load-order coupling between two sibling helpers. Catching ``ImportError`` and
    # degrading to the regex path keeps this comparator independently usable.
    try:
        from tests.helpers import record_codec  # noqa: WPS433 (intentional local import)
    except ImportError:
        return None

    # WHY (Refactoring Rationale): ``record_codec`` is the single source of truth for the
    # ORIG-TS / PROC-TS offsets, so we look the layout up in its registry instead of
    # re-declaring byte ranges here. ``KeyError``/``AttributeError`` are tolerated so a
    # future refactor of ``record_codec`` (renamed registry, unknown layout key) still
    # degrades gracefully to the regex path rather than breaking every golden comparison.
    try:
        layout_obj = record_codec.LAYOUTS[layout]
        normalize_timestamps = record_codec.normalize_timestamps
        has_ts_fields = any(
            getattr(field, "normalize_ts", False) for field in layout_obj.fields
        )
    except (KeyError, AttributeError):
        return None

    if not has_ts_fields:
        # No fixed-offset timestamp fields to blank -- the regex scrubber alone suffices.
        return None

    def _scrub_line(line: str) -> str:
        """Blank the layout's timestamp fields in one record line (closure over layout).

        Purpose
        -------
        Apply :func:`record_codec.normalize_timestamps` to a single line using the
        resolved layout captured in the enclosing scope.

        Parameters
        ----------
        line : str
            One raw fixed-width record line (without its newline terminator).

        Returns
        -------
        str
            The line with each ``normalize_ts`` field overwritten by blanks and the line
            padded/truncated to the layout's record length.

        Raises
        ------
        None
        """
        return normalize_timestamps(line, layout_obj)

    return _scrub_line


def _build_unified_diff(
    golden_text: str,
    actual_text: str,
    *,
    fromfile: str,
    tofile: str,
    max_lines: int = _DIFF_MAX_LINES,
) -> str:
    """Render a truncated ``difflib`` unified diff of golden vs actual text.

    Purpose
    -------
    Produce a compact, human-readable unified diff for a :class:`GoldenMismatchError`
    message, oriented golden-to-actual (removed lines are golden, added lines are actual)
    and capped at ``max_lines`` so CI logs stay legible.

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
    max_lines : int
        Maximum number of diff lines to include before truncating. Defaults to
        :data:`_DIFF_MAX_LINES`.

    Returns
    -------
    str
        The unified-diff text, possibly followed by a truncation notice; empty string only
        if the two inputs are identical (which callers avoid by diffing on mismatch only).

    Raises
    ------
    None
    """
    # WHY (Trade-off): ``splitlines()`` without ``keepends`` combined with ``lineterm=""``
    # yields clean diff lines that we join with a single ``\n``. The alternative
    # (``keepends=True`` with the default ``lineterm``) risks doubled or missing newlines
    # when the final line lacks a terminator, producing a ragged, harder-to-read diff.
    diff_lines: Sequence[str] = list(
        difflib.unified_diff(
            golden_text.splitlines(),
            actual_text.splitlines(),
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
        return "\n".join(kept)
    return "\n".join(diff_lines)


# ---------------------------------------------------------------------------
# Public API.
# ---------------------------------------------------------------------------


def normalize(
    text: str,
    *,
    layout: Optional[str] = None,
    strip_trailing_ws: bool = True,
    extra_patterns: Optional[Sequence[Tuple[str, str]]] = None,
) -> str:
    """Normalize non-deterministic content out of text so goldens compare stably.

    Purpose
    -------
    Transform ``text`` into a canonical form in which the two known CardDemo
    non-determinism sources are neutralised: the fixed-offset ``DALYTRAN-ORIG-TS`` /
    ``DALYTRAN-PROC-TS`` timestamp fields (when ``layout`` identifies a record layout that
    declares them) and any ISO-8601-like timestamp embedded anywhere in the text. Line
    endings are unified and trailing whitespace optionally stripped so byte-level diffs
    reflect *semantic* differences only.

    Parameters
    ----------
    text : str
        The raw text to normalize (a decoded program-output or golden-file body).
    layout : str or None
        Optional logical record-layout name (e.g. ``"DALYTRAN"``). When given and the
        layout declares timestamp fields, each line is first blanked at those fixed byte
        offsets via ``record_codec``; when ``None`` or the offsets are unavailable, only
        the regex timestamp scrubber applies. Defaults to ``None``.
    strip_trailing_ws : bool
        When ``True`` (default), right-strip each line of trailing whitespace after
        scrubbing. Defaults to ``True``.
    extra_patterns : sequence of (str, str) or None
        Optional additional ``(regex_pattern, replacement)`` pairs applied per line after
        the default timestamp scrubbing -- e.g. to neutralise a report's run-id or page
        header. Defaults to ``None`` (no extra patterns).

    Returns
    -------
    str
        The normalized text, with ``\\n`` line endings and non-deterministic timestamps
        replaced by a stable sentinel.

    Raises
    ------
    re.error
        If a pattern in ``extra_patterns`` is not a valid regular expression.
    """
    # Unify line endings first so downstream per-line logic sees exactly one terminator
    # style. WHY (Trade-off): fixed-width batch output may be produced on platforms that
    # differ only in CR/LF vs LF; collapsing them up front prevents a whole-file diff that
    # is really just an end-of-line artifact.
    text = text.replace("\r\n", "\n").replace("\r", "\n")

    # Resolve the structured (offset-based) scrubber once, not per line, because the layout
    # lookup and lazy import are invariant across the whole text.
    line_scrubber = _resolve_layout_normalizer(layout)

    # Pre-compile any caller-supplied extra patterns once. WHY (Refactoring Rationale):
    # compiling inside the per-line loop would recompile the same patterns for every line
    # of a large statement; hoisting the compile keeps normalization linear in the input.
    compiled_extra: List[Tuple[re.Pattern, str]] = (
        [(re.compile(pat), repl) for pat, repl in extra_patterns]
        if extra_patterns
        else []
    )

    normalized_lines: List[str] = []
    # ``split("\n")`` (rather than ``splitlines()``) is used so a trailing newline is
    # preserved as a trailing empty element and round-trips through ``"\n".join`` -- keeping
    # normalization byte-faithful except for the content we deliberately scrub.
    for line in text.split("\n"):
        # Structured blanking must run FIRST, while the line still has its original
        # fixed-width geometry. WHY (Assumption): ``record_codec`` blanks by byte offset,
        # so it is only valid before the regex step (which can change line length by
        # replacing a 26-char timestamp with the shorter sentinel). Empty lines carry no
        # record and are skipped so we never fabricate padded content out of a blank line.
        if line and line_scrubber is not None:
            line = line_scrubber(line)

        # Regex timestamp scrubbing runs SECOND and is offset-independent: it catches ISO
        # timestamps embedded in free-form statement/report text as well as any residual
        # fixed-offset timestamp when the structured path was unavailable (import failure).
        line = _ISO_TS_RE.sub(_TS_SENTINEL, line)

        # Caller-supplied normalizations apply after the built-in ones so a test can layer
        # domain-specific scrubbing on top of the standard timestamp handling.
        for pattern, replacement in compiled_extra:
            line = pattern.sub(replacement, line)

        if strip_trailing_ws:
            # Right-strip only. WHY (Trade-off): fixed-width COBOL output pads records with
            # trailing spaces whose exact count is an artifact, not data; stripping the
            # right edge yields a stable diff. Leading whitespace is preserved because it is
            # positionally significant in the report layouts.
            line = line.rstrip()

        normalized_lines.append(line)

    return "\n".join(normalized_lines)


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
    output and the checked-in ``golden_path`` content (neutralising timestamps and trailing
    whitespace) and raises :class:`GoldenMismatchError` -- carrying a unified diff -- if
    they differ. Optionally (re)writes the golden from the normalized actual when explicitly
    requested, so approved baselines can be regenerated under human control.

    Parameters
    ----------
    actual : str or bytes or bytearray or os.PathLike
        The produced output. A ``str`` is the literal content; ``bytes``/``bytearray`` are
        decoded with ``encoding``; an ``os.PathLike`` is read from disk. See
        :func:`_coerce_actual_to_text`.
    golden_path : str or os.PathLike
        Path to the expected ("golden") file.
    layout : str or None
        Optional record-layout name forwarded to :func:`normalize` for both sides (e.g.
        ``"DALYTRAN"`` to blank the daily-transaction timestamp fields). Defaults to
        ``None``.
    encoding : str
        Text codec for decoding ``actual`` bytes/paths and for reading/writing the golden.
        Defaults to ``"utf-8"``.
    update : bool or None
        Golden-regeneration control. When ``True`` (or ``None`` and the environment
        variable ``CARDDEMO_UPDATE_GOLDENS`` is truthy), the normalized actual is written
        to ``golden_path`` (creating parent directories) and the function returns without
        asserting. When ``False``, regeneration is forced off regardless of the environment.
        Defaults to ``None`` (defer to the environment; effectively OFF).
    extra_patterns : sequence of (str, str) or None
        Optional additional ``(regex_pattern, replacement)`` pairs forwarded to
        :func:`normalize` for both sides. Defaults to ``None``.

    Returns
    -------
    None
        Returns normally when the normalized actual matches the golden (or after a
        successful regeneration).

    Raises
    ------
    GoldenMismatchError
        If the golden file is missing while not regenerating, or if the normalized actual
        differs from the normalized golden (the message contains a truncated unified diff).
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

    # Decide whether we are in golden-regeneration mode. WHY (Trade-off): explicit
    # ``update`` wins over the environment so a single test can force-write (or force-skip)
    # a golden, while the env var provides the ergonomic bulk "re-bless everything" switch.
    # The default (``update is None`` + unset/false env) is OFF so that a normal CI run can
    # never overwrite a golden and thereby self-approve a regression.
    do_update = update if update is not None else _env_truthy(os.environ.get(_UPDATE_ENV_VAR))

    if do_update:
        # Create the parent tree so a brand-new golden can be written on first blessing.
        golden.parent.mkdir(parents=True, exist_ok=True)
        golden.write_text(normalized_actual, encoding=encoding)
        # WHY (Assumption): after writing we return without asserting -- a regeneration run
        # is a deliberate human action to accept the current output as the new baseline, so
        # asserting it against itself would be redundant.
        return

    if not golden.exists():
        # A missing golden with regeneration OFF is a hard error, not a silent pass, so an
        # untracked/forgotten baseline cannot let a test appear to succeed. The message
        # tells the operator exactly how to create it.
        raise GoldenMismatchError(
            f"golden not found: {golden}; re-run with {_UPDATE_ENV_VAR}=1 "
            "(or pass update=True) to create it from the current output"
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
        diff = _build_unified_diff(
            normalized_golden,
            normalized_actual,
            fromfile=str(golden),
            tofile=_actual_label(actual),
        )
        raise GoldenMismatchError(
            f"actual output does not match golden {golden}:\n{diff}"
        )
