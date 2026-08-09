"""The one authority on what a 26-character CardDemo timestamp is, and what it becomes.

Purpose
-------
Decide, in a single place, whether the characters occupying a ``PIC X(26)`` stamp are a
timestamp CardDemo actually writes, are a stamp nobody has written yet, or are neither --
and, for the first case, render the one spelling a ``TIMESTAMP(6)`` column accepts. Three
readers need that decision and one loader needs that rendering, so it is declared here
rather than in any of them.

The two admitted forms
----------------------
CardDemo writes a 26-character stamp in exactly two spellings, and both reach the same
fields:

* ``YYYY-MM-DD HH:MM:SS.ffffff`` -- what the posting program moves into
  ``DALYTRAN-PROC-TS`` and ``TRAN-PROC-TS``, and what all 300 populated
  ``DALYTRAN-ORIG-TS`` values in ``app/data/ASCII/dailytran.txt`` carry.
* ``YYYY-MM-DD-HH.MM.SS.NNNNNN`` -- the form a program taking ``FUNCTION CURRENT-DATE``
  produces, which the interest calculation moves into BOTH stamps of the transaction
  records it writes.

Nothing else is a timestamp. A span that is neither of those two forms nor uniformly
unwritten is refused by every caller.

Design decisions (WHY)
----------------------
Refactoring Rationale:
    This module replaces three byte-identical copies of a per-position character test that
    lived in ``readers/dalytran.py``, ``readers/transaction.py`` and
    ``readers/export_record.py``. Each copy admitted the UNION of four separator characters
    at each of the six separator positions and a digit everywhere else, which was three
    problems rather than one. It admitted spellings CardDemo never writes -- a value
    separated ``2022.07-18:10.30 00-123456`` satisfied every position. It validated no
    calendar and no clock, so ``2022-13-45 99:99:99.123456`` passed and then failed much
    later, inside the database, as a cast error naming a column rather than a record. And
    being three copies, a correction to the rule had to be made three times or the readers
    would disagree about what a timestamp is while all three continued to look right.
Alternatives Considered:
    A tightened per-position rule -- separator sets fixed per position, plus explicit range
    checks on the month, day, hour, minute and second. Rejected because it re-implements a
    calendar: it still has to know that April has thirty days and that 2100 is not a leap
    year, and each of those is a line that can be wrong without any test noticing until a
    specific date arrives. Parsing with the standard library's own calendar and then
    checking the result is the same test with none of that surface.
Trade-offs:
    Validation is a PARSE AND ROUND TRIP rather than a parse alone, and the round trip is
    the load-bearing half. ``%f`` accepts one to six fraction digits, and ``%Y`` accepts
    fewer than four year digits, so a parse alone would admit ``2022-07-18 10:30:00.1``
    followed by five blanks -- a value that is not 26 characters of timestamp and that a
    later re-encode could not reproduce. Formatting the parsed value back through the same
    directive string and requiring byte equality admits exactly the spellings that
    round-trip, which is the property a fixed-width column contract needs. The accepted
    cost is one extra format call per stamp, on a path that reads whole datasets.
Assumptions:
    An UNWRITTEN stamp is a legitimate value rather than a fault, it has exactly two
    uniform forms, and a MIXTURE of them is not one of them. COBOL leaves a field it has
    not moved a value into holding the state its record was initialised to: all 300 records
    of ``AWS.M2.CARDDEMO.DALYTRAN.PS`` carry 26 blanks in ``DALYTRAN-PROC-TS`` because the
    posting run is what writes it, and ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` is a single
    350-byte primer of which 342 bytes are ``0x00``, so both of its stamps hold low values.
    A span holding some of each is partly written or partly overwritten, and no writer in
    this corpus produces one, so admitting it would turn an undetected corruption into a
    null column. ``ebcdic_codec.decode_timestamp`` records the same three assumptions from
    the byte side and reports the same two forms as absence, so the two agree by
    construction rather than by coincidence.
Trade-offs:
    This module raises nothing. It answers questions and renders values, and each caller
    raises its own error naming its own record and field -- which is the diagnostic an
    operator needs, and which a shared raiser could not produce without being told the
    record, the field and the layout name anyway.
"""

from __future__ import annotations

import datetime
from typing import Final

__all__ = [
    "ADMITTED_FORMS",
    "BLANK",
    "DOTTED_FORM",
    "ISO_FORM",
    "LOW_VALUE",
    "TIMESTAMP_WIDTH",
    "canonical",
    "is_admitted",
    "is_unwritten",
    "parse",
]

# Assumptions: 26 characters, because every timestamp in the migration contract is
#   declared PIC X(26) -- app/cpy/CVTRA05Y.cpy L16 and L17, app/cpy/CVTRA06Y.cpy L16 and L17,
#   app/cpy/COSTM01.CPY L35 and the export record's own stamp. The width is stated here for
#   the two admitted forms to be checked against; a CALLER still takes the width it enforces
#   from its own field descriptor, so a layout correction moves one number rather than two.
TIMESTAMP_WIDTH: Final[int] = 26

# Assumptions: the two forms COBOL writes, as strptime directive strings rather than as
#   character-position tables. Written this way they are simultaneously the parser and the
#   formatter, which is what makes the round-trip check below possible at all -- a position
#   table can recognise a spelling but cannot reproduce one.
ISO_FORM: Final[str] = "%Y-%m-%d %H:%M:%S.%f"
DOTTED_FORM: Final[str] = "%Y-%m-%d-%H.%M.%S.%f"
ADMITTED_FORMS: Final[tuple[str, ...]] = (ISO_FORM, DOTTED_FORM)

# Assumptions: the two uniform forms an unwritten fixed-width stamp takes. The blank is
#   what COBOL leaves in a character field nobody has moved a value into; the low value is the
#   same fact for a record whose storage was never written at all.
BLANK: Final[str] = " "
LOW_VALUE: Final[str] = "\x00"


def _is_uniformly(value: str, character: str) -> bool:
    """Report whether every position of a value holds one given character.

    Purpose
    -------
    Recognise one of the two uniform forms an unwritten stamp takes, as the single test both
    forms are checked through, so the two cannot come to be recognised by slightly different
    rules.

    Parameters
    ----------
    value : str
        The characters to test.
    character : str
        The single character every position must equal.

    Returns
    -------
    bool
        ``True`` when the value is non-empty and every position equals ``character``;
        ``False`` otherwise, an empty value included.

    Raises
    ------
    None
    """
    # Assumptions: an EMPTY value must not read as uniform, which is why the emptiness
    #   test is here rather than left to the generator. A test over no positions is vacuously
    #   true, so without this an empty span would be accepted as an unwritten stamp -- and an
    #   empty span is a field that was sliced wrongly, which is the opposite of a stamp nobody
    #   has written yet.
    return bool(value) and all(position == character for position in value)


def is_unwritten(value: str) -> bool:
    """Report whether a stamp span is one nobody has written a value into.

    Purpose
    -------
    Separate the corpus's one legitimate empty case from a decode fault, so a caller can
    admit it without admitting anything else.

    Parameters
    ----------
    value : str
        The decoded characters of the stamp, at the field's full declared width.

    Returns
    -------
    bool
        ``True`` when EVERY position is a blank, or EVERY position is a low value; ``False``
        for a populated stamp, for an empty span, and for a span holding a MIXTURE of the two
        pad characters.

    Raises
    ------
    None
    """
    return _is_uniformly(value, BLANK) or _is_uniformly(value, LOW_VALUE)


def parse(value: str) -> datetime.datetime | None:
    """Parse a stamp span in either admitted form, or report that it is in neither.

    Purpose
    -------
    Turn 26 characters into an exact instant, using the standard library's own calendar so
    that an impossible month, day, hour, minute or second is rejected by the calendar rather
    than by a range table this package would have to maintain.

    Parameters
    ----------
    value : str
        The decoded characters of the stamp. An unwritten span, a span of the wrong width and
        a malformed span are all answered with ``None`` rather than an exception.

    Returns
    -------
    datetime.datetime | None
        The instant, when the value is exactly one of :data:`ADMITTED_FORMS` and round-trips
        back to itself through that same form; ``None`` otherwise.

    Raises
    ------
    None
    """
    # Trade-offs: the width is checked before either parse rather than left to the parse
    #   to notice. `strptime` is total over trailing content only in the sense that it raises,
    #   so this check buys no correctness -- what it buys is that a span of the wrong width
    #   costs one comparison instead of two failed parses on a path that reads whole datasets.
    if len(value) != TIMESTAMP_WIDTH:
        return None
    for form in ADMITTED_FORMS:
        try:
            parsed = datetime.datetime.strptime(value, form)
        except ValueError:
            continue
        # Assumptions: the ROUND TRIP is what makes this a width-exact contract rather
        #   than a lenient parse. `%f` accepts one to six fraction digits and `%Y` accepts
        #   fewer than four year digits, so `2022-7-18 10:30:00.1` parses -- and then differs
        #   from what formatting the parsed instant produces. Requiring byte equality admits
        #   exactly the spellings a fixed-width field can hold and reproduce.
        if parsed.strftime(form) == value:
            return parsed
    return None


def is_admitted(value: str) -> bool:
    """Report whether a stamp span is a well-formed timestamp in either admitted form.

    Purpose
    -------
    Offer the predicate a reader's validation reads as, so the reader states the question it
    is asking rather than discarding a parsed instant it has no use for.

    Parameters
    ----------
    value : str
        The decoded characters of the stamp.

    Returns
    -------
    bool
        ``True`` when :func:`parse` yields an instant; ``False`` otherwise, an unwritten span
        included -- absence is :func:`is_unwritten`'s question, not this one's.

    Raises
    ------
    None
    """
    return parse(value) is not None


def canonical(value: str) -> str | None:
    """Render a stamp span in the one spelling a ``TIMESTAMP(6)`` column accepts.

    Purpose
    -------
    Supply the load-direction rendering. PostgreSQL casts the space-separated form directly
    and cannot cast the dotted one at all, so a load that passed a stamp through verbatim
    would succeed for every record the posting program wrote and fail for every record the
    interest calculation wrote -- a failure that depends on which program produced the row
    and therefore appears only once real interest output reaches the pipeline.

    Parameters
    ----------
    value : str
        The decoded characters of the stamp, already validated by its reader.

    Returns
    -------
    str | None
        The instant rendered as ``YYYY-MM-DD HH:MM:SS.ffffff``, which is :data:`ISO_FORM` and
        is therefore returned unchanged for a value already in that form; ``None`` when the
        span is unwritten, which is the value a nullable timestamp column takes.

    Raises
    ------
    ValueError
        If the span is neither unwritten nor a well-formed timestamp. A caller reaching this
        function has already validated the span through its own reader, so arriving here with
        an unparseable value means the two disagree -- which is a defect to report rather than
        a row to write, and reporting it names no part of the value.
    """
    if is_unwritten(value):
        return None
    parsed = parse(value)
    if parsed is None:
        # Trade-offs: the refusal quotes NO part of the value, not even its length. A
        #   stamp is not itself sensitive, but this function is reached from a load path that
        #   handles records carrying primary account numbers, and a message shape that quotes
        #   its input is the shape that later gets copied to a field where the input is not
        #   safe to quote. The width and the two admitted forms are enough to diagnose it.
        raise ValueError(
            f"a {TIMESTAMP_WIDTH}-character stamp reached the load rendering in neither"
            f" admitted form ({' or '.join(ADMITTED_FORMS)}) and is not uniformly unwritten,"
            " so its reader and this renderer disagree about what a timestamp is"
        )
    return parsed.strftime(ISO_FORM)
