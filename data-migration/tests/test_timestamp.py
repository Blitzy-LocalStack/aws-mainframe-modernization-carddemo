"""Prove the shared timestamp authority admits exactly the two spellings CardDemo writes.

Purpose
-------
Pin the contract three readers and one loader now depend on: which 26-character spans are
timestamps, which are stamps nobody has written, which are neither, and what a populated one
becomes on its way into a ``TIMESTAMP(6)`` column. The rule this replaced admitted spellings the
baseline never produces and validated no calendar at all, so the cases below are chosen to fail
against that rule rather than merely to describe the new one.

Alternatives Considered:
    Testing the three readers' own validators instead of the module they delegate to. Rejected
    because it would triple every case for one rule and leave the rule itself untested where it
    is stated; the readers' delegation is asserted separately, in ``test_readers.py``, by driving
    a corrupt stamp through each of them.

Assumptions:
    The two admitted spellings are read from the module rather than written out here, so a
    directive string changed in one place fails these tests instead of being silently agreed
    with. The VALUES exercised against them are literals, because a value derived from the same
    directives would pass against any directives at all.

Trade-offs:
    Every rejected case keeps the correct 26-character width. A short or long span is refused by
    the width check, which every implementation gets right, so a rejected case that also changed
    the width would prove nothing about the shape rule -- which is the half that was wrong.
"""

from __future__ import annotations

import datetime

import pytest

from carddemo_migration.copybook import timestamp


def test_both_admitted_spellings_parse_to_the_same_instant() -> None:
    """Parse the space-separated and the dotted spellings of one instant to one value.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either spelling fails to parse, or the two disagree.
    """
    # WHY : Assumptions: both forms genuinely reach the same fields. The posting program writes
    #   the space-separated form and the interest calculation writes the dotted one -- and the
    #   interest calculation writes rows into the transaction record the posting program also
    #   writes -- so a reader cannot know from the bytes which program produced them.
    expected = datetime.datetime(2022, 7, 18, 10, 30, 0, 123456)
    assert timestamp.parse("2022-07-18 10:30:00.123456") == expected
    assert timestamp.parse("2022-07-18-10.30.00.123456") == expected
    assert timestamp.ADMITTED_FORMS == (timestamp.ISO_FORM, timestamp.DOTTED_FORM)


@pytest.mark.parametrize(
    "candidate",
    [
        # WHY : these three are the spellings the previous per-position rule ADMITTED. It allowed
        #   any of four separators at each of six positions, so a value mixing them satisfied it;
        #   and it range-checked nothing, so an impossible month, day, hour, minute or second
        #   passed and then failed inside the database as a cast error naming a column.
        "2022.07-18:10.30 00-123456",
        "2022-13-45 10:30:00.123456",
        "2022-07-18 99:99:99.123456",
        "2022-02-30 10:30:00.123456",
        "2021-02-29 10:30:00.123456",
        # WHY : a short fraction keeps the 26-character width by padding, and `%f` alone accepts
        #   one to six digits -- so a parse without the round trip would admit it. The round trip
        #   is what refuses it, because formatting the parsed instant always emits six digits.
        "2022-07-18 10:30:00.1     ",
        # WHY : a two-digit year likewise parses under `%Y` -- padded here to the declared width
        #   so that the WIDTH is not what refuses it -- and cannot be reproduced at that width.
        "  22-07-18 10:30:00.123456",
        "                          ".replace(" ", "x"),
    ],
)
def test_a_span_the_baseline_never_writes_is_not_admitted(candidate: str) -> None:
    """Refuse every 26-character span that is neither admitted spelling.

    Parameters
    ----------
    candidate : str
        A 26-character span the baseline does not produce.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the span is admitted, or the case does not hold the declared width.
    """
    assert len(candidate) == timestamp.TIMESTAMP_WIDTH, "a case must keep the declared width"
    assert timestamp.parse(candidate) is None
    assert not timestamp.is_admitted(candidate)
    assert not timestamp.is_unwritten(candidate)


@pytest.mark.parametrize("pad", [" ", "\x00"])
def test_a_uniformly_unwritten_span_is_absence_rather_than_a_fault(pad: str) -> None:
    """Report a span of nothing but one pad character as unwritten, and render it as null.

    Parameters
    ----------
    pad : str
        One of the two characters an unwritten fixed-width stamp is filled with.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the span is not reported unwritten, or does not render as null.
    """
    # WHY : Assumptions: both forms are measured rather than assumed. All 300 records of
    #   AWS.M2.CARDDEMO.DALYTRAN.PS carry 26 BLANKS in DALYTRAN-PROC-TS because the posting run
    #   is what writes it, and AWS.M2.CARDDEMO.DALYTRAN.PS.INIT is a 350-byte primer of which 342
    #   bytes are 0x00, so both of its stamps hold LOW VALUES. Admitting only one of the two would
    #   refuse a form the baseline itself produces.
    span = pad * timestamp.TIMESTAMP_WIDTH
    assert timestamp.is_unwritten(span)
    assert not timestamp.is_admitted(span)
    assert timestamp.canonical(span) is None


def test_a_mixture_of_the_two_pads_is_refused_rather_than_read_as_absent() -> None:
    """Refuse a span holding some blanks and some low values, which no writer produces.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a mixed span is reported unwritten, or is rendered rather than refused.
    """
    # WHY : Assumptions: only a UNIFORM span denotes a stamp nobody wrote. A mixture is one
    #   partly written or partly overwritten, and admitting it would convert an undetected
    #   corruption into a null column. `ebcdic_codec.decode_timestamp` refuses the same case from
    #   the byte side, so the two agree by construction rather than by coincidence.
    mixed = " " * 13 + "\x00" * 13
    assert len(mixed) == timestamp.TIMESTAMP_WIDTH
    assert not timestamp.is_unwritten(mixed)
    assert not timestamp.is_admitted(mixed)
    with pytest.raises(ValueError) as refused:
        timestamp.canonical(mixed)
    # WHY : the refusal must quote NO part of the span. This function is reached from a load path
    #   handling records that carry primary account numbers, and a message shape that quotes its
    #   input is the shape that later gets copied to a field where the input is not safe to quote.
    assert mixed.strip() not in str(refused.value)


def test_the_dotted_spelling_is_rendered_into_the_one_form_the_column_accepts() -> None:
    """Render the dotted spelling as the space-separated form, and the other unchanged.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the dotted form is not converted, or the space-separated form is altered.
    """
    # WHY : Assumptions: this is the reason the renderer exists at all. PostgreSQL casts
    #   'YYYY-MM-DD HH:MM:SS.ffffff' directly and cannot cast 'YYYY-MM-DD-HH.MM.SS.NNNNNN' at all,
    #   so a load passing stamps through verbatim succeeds for every record the posting program
    #   wrote and fails for every record the interest calculation wrote -- a failure that depends
    #   on which program produced the row and so appears only once real interest output arrives.
    assert timestamp.canonical("2022-07-18-10.30.00.123456") == "2022-07-18 10:30:00.123456"
    # WHY : the already-canonical form must come back IDENTICAL rather than re-rendered into
    #   something equivalent. All 300 shipped originating stamps are in this form, so any
    #   normalisation here would change every one of them and make a parity comparison against the
    #   extract report a difference for a load that was correct.
    assert timestamp.canonical("2022-06-10 19:27:53.000000") == "2022-06-10 19:27:53.000000"


def test_a_leap_day_that_exists_is_admitted_and_one_that_does_not_is_refused() -> None:
    """Admit 29 February in a leap year and refuse it in a common year.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either verdict is wrong.
    """
    # WHY : Alternatives Considered: tightening the previous per-position rule with explicit
    #   range checks would have had to know that 2000 is a leap year and 1900 is not, and each of
    #   those is a line that can be wrong with no test noticing until a particular date arrives.
    #   Parsing through the standard library's own calendar is the same test with none of that
    #   surface, and this case is what demonstrates the difference.
    assert timestamp.is_admitted("2020-02-29 00:00:00.000000")
    assert timestamp.is_admitted("2000-02-29 00:00:00.000000")
    assert not timestamp.is_admitted("1900-02-29 00:00:00.000000")
    assert not timestamp.is_admitted("2021-02-29 00:00:00.000000")


def test_a_span_of_the_wrong_width_is_not_admitted() -> None:
    """Refuse a stamp that does not occupy exactly the declared 26 characters.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a short or long span is admitted.
    """
    assert not timestamp.is_admitted("2022-07-18 10:30:00.12345")
    assert not timestamp.is_admitted("2022-07-18 10:30:00.1234567")
    assert not timestamp.is_unwritten("")
