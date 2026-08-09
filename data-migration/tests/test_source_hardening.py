"""Exercise the hardened flat-file source and the two record-width guards every reader shares.

Purpose
-------
Prove the source-level properties of :mod:`carddemo_migration.readers.source` that no reader test
can reach, because each of them is about the FILE rather than about a record: that a path which is
not a regular file is refused instead of blocking or being read, that a line long enough to be a
denial-of-service is refused before it is allocated, that an absent optional dataset is
distinguished from a broken or unreadable one, that a key cannot be sliced from a record of the
wrong width, and that a short line is not padded into a plausible row.

Assumptions: these are asserted against real filesystem objects -- a directory, a named pipe, a
dangling symbolic link, an unreadable file -- rather than against mocks. A mock of
:func:`os.open` would assert that this module calls the functions its author expected it to call,
which is not the property in question: the property is what happens when the operating system is
handed something the code did not anticipate, and only the operating system can answer that.

Assumptions: the named-pipe case is asserted with NO writer attached and with no timeout guard,
which is deliberate and is the whole point. An unhardened open of a writer-less FIFO blocks
forever, so this case either returns promptly with a refusal or hangs the suite -- a hang being an
unambiguous, if blunt, report that the hardening has regressed. A timeout wrapper would convert
that into a pass with a warning.
"""

from __future__ import annotations

import os
import pathlib

import pytest

from carddemo_migration.copybook.ebcdic_codec import EbcdicRecordLengthError
from carddemo_migration.copybook.layouts import (
    LayoutError,
    RecordLengthError,
    iter_ascii_text_records,
)
from carddemo_migration.readers import dalytran, source, trantype, xref

# Assumptions: the transaction-type layout is used for the line-oriented cases because it is the
#   narrowest ASCII record in the corpus at sixty characters, so its bound is the easiest to
#   exceed deliberately; the cross-reference layout is used for the data-region cases because it
#   is the record whose shipped seed actually relies on the padding tolerance -- 36 data
#   characters against a declared 50 -- so it is where the bound has to be exactly right.
_LINE_RECLEN = trantype.TRANTYPE_LAYOUT.reclen
_XREF_DATA_WIDTH = max(field.end for field in xref.LOADED_FIELDS)


def _seed_line(text: str) -> str:
    """Return one text value padded to the transaction-type record width with a terminator.

    Parameters
    ----------
    text : str
        The data characters the line should carry.

    Returns
    -------
    str
        ``text`` right-padded with spaces to the declared record width, plus one separator.

    Raises
    ------
    None
    """
    return text.ljust(_LINE_RECLEN) + "\n"


def test_a_well_formed_seed_file_streams_its_lines(tmp_path: pathlib.Path) -> None:
    """Assert the hardened reader yields exactly the lines a plain text read would.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the happy path is asserted first and against a THREE-line file, because
    #   the hardened reader replaces handle iteration with an explicit readline loop -- and the
    #   ways a hand-written loop goes wrong are dropping the last line, emitting a phantom empty
    #   line after a trailing separator, or losing the terminator that the record iterator strips.
    #   A single-line fixture would catch none of the three.
    path = tmp_path / "trantype.txt"
    lines = [_seed_line("01AAA"), _seed_line("02BBB"), _seed_line("03CCC")]
    path.write_text("".join(lines), encoding="latin-1")

    assert list(source.iter_seed_lines(path, _LINE_RECLEN)) == lines


def test_a_zero_byte_seed_file_yields_nothing(tmp_path: pathlib.Path) -> None:
    """Assert an empty file is read as no records rather than as a fault.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    path = tmp_path / "empty.txt"
    path.write_bytes(b"")

    assert list(source.iter_seed_lines(path, _LINE_RECLEN)) == []


def test_a_final_line_without_a_terminator_is_still_yielded(tmp_path: pathlib.Path) -> None:
    """Assert a file whose last line is unterminated does not lose that line.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): this is the case the bounded read is most likely to get wrong, because a
    #   final unterminated line of exactly the record width is indistinguishable from the head of
    #   an over-long one unless the bound leaves a character of headroom. The fixture is written at
    #   exactly the declared width for that reason.
    path = tmp_path / "unterminated.txt"
    path.write_text("01AAA".ljust(_LINE_RECLEN), encoding="latin-1")

    assert list(source.iter_seed_lines(path, _LINE_RECLEN)) == ["01AAA".ljust(_LINE_RECLEN)]


def test_a_line_of_exactly_the_declared_width_with_both_terminators_is_accepted(
    tmp_path: pathlib.Path,
) -> None:
    """Assert a full-width line carrying a carriage return and a separator is not refused.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): three of the nine shipped ASCII seeds carry carriage returns on some rows,
    #   so the longest LEGAL line is the record width plus two terminator characters. A bound that
    #   allowed only one would refuse a shipped seed, which is why this boundary is asserted rather
    #   than assumed.
    path = tmp_path / "crlf.txt"
    line = "01AAA".ljust(_LINE_RECLEN) + "\r\n"
    path.write_text(line, encoding="latin-1")

    assert list(source.iter_seed_lines(path, _LINE_RECLEN)) == [line]


def test_an_over_long_line_is_refused_without_being_read_whole(tmp_path: pathlib.Path) -> None:
    """Assert a line far longer than the record is refused, naming both widths.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the fixture's single line is two orders of magnitude longer than the
    #   record, and the assertion is on the reported length rather than only on the exception. The
    #   refusal must report a LOWER BOUND close to the record width, because reporting the true
    #   length would require reading the whole line -- which is the allocation being refused. A
    #   regression that read the line and then measured it would satisfy the exception type and
    #   fail this assertion.
    path = tmp_path / "unbounded.txt"
    path.write_text("Z" * 200_000 + "\n", encoding="latin-1")

    with pytest.raises(RecordLengthError) as raised:
        list(source.iter_seed_lines(path, _LINE_RECLEN))

    assert f"at least {_LINE_RECLEN + 3} characters" in str(raised.value)
    assert str(_LINE_RECLEN) in str(raised.value)


def test_a_directory_is_refused_rather_than_reported_empty(tmp_path: pathlib.Path) -> None:
    """Assert a directory supplied where a seed file was meant is named as a directory.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    with pytest.raises(LayoutError, match="directory"):
        list(source.iter_seed_lines(tmp_path, _LINE_RECLEN))


def test_a_named_pipe_is_refused_instead_of_blocking(tmp_path: pathlib.Path) -> None:
    """Assert a writer-less FIFO is refused promptly rather than waited on.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Trade-offs): no timeout guards this case. A regression makes the suite HANG here, which
    #   is a blunter report than a failure but an unambiguous one; wrapping it in a timeout would
    #   turn an indefinite block into a soft failure, and an indefinite block in a nightly load
    #   step is exactly the outcome this refusal exists to prevent.
    fifo = tmp_path / "staged.pipe"
    os.mkfifo(fifo)

    with pytest.raises(LayoutError, match="named pipe"):
        list(source.iter_seed_lines(fifo, _LINE_RECLEN))


def test_an_absent_optional_source_yields_nothing_and_a_required_one_raises(
    tmp_path: pathlib.Path,
) -> None:
    """Assert absence is a normal state only where the caller declared it optional.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    missing = tmp_path / "not-there.txt"

    assert list(source.iter_seed_lines(missing, _LINE_RECLEN, optional=True)) == []
    with pytest.raises(FileNotFoundError):
        list(source.iter_seed_lines(missing, _LINE_RECLEN))


def test_a_dangling_symbolic_link_is_refused_even_when_the_source_is_optional(
    tmp_path: pathlib.Path,
) -> None:
    """Assert a link to nothing is a staging fault rather than an absent dataset.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the operating system reports a missing name and a link to a missing target
    #   identically, and only the second is a fault. A link exists because somebody created it, so
    #   reporting it as absence lets an optional load complete having read nothing -- and the
    #   verification pass for that one record accepts zero rows by design, so nothing downstream
    #   would catch it.
    dangling = tmp_path / "staged.txt"
    dangling.symlink_to(tmp_path / "never-created.txt")

    with pytest.raises(LayoutError, match="broken link"):
        list(source.iter_seed_lines(dangling, _LINE_RECLEN, optional=True))
    with pytest.raises(LayoutError, match="broken link"):
        source.seed_dataset_is_present(dangling)


def test_a_resolvable_symbolic_link_is_read_normally(tmp_path: pathlib.Path) -> None:
    """Assert a link to a real regular file is accepted, because staging uses links.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    target = tmp_path / "real.txt"
    target.write_text(_seed_line("01AAA"), encoding="latin-1")
    link = tmp_path / "staged.txt"
    link.symlink_to(target)

    assert source.seed_dataset_is_present(link) is True
    assert list(source.iter_seed_lines(link, _LINE_RECLEN)) == [_seed_line("01AAA")]


@pytest.mark.skipif(os.geteuid() == 0, reason="a superuser is not stopped by a mode bit")
def test_an_unreadable_file_raises_rather_than_reporting_absence(tmp_path: pathlib.Path) -> None:
    """Assert a permission denial propagates instead of being normalised to no records.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the case is skipped for a superuser rather than worked around, because a
    #   mode bit does not stop one and a test that cannot fail is worse than an absent one. CI runs
    #   this suite as an ordinary user, where the assertion holds.
    denied = tmp_path / "denied.txt"
    denied.write_text(_seed_line("01AAA"), encoding="latin-1")
    denied.chmod(0o000)

    with pytest.raises(PermissionError):
        list(source.iter_seed_lines(denied, _LINE_RECLEN, optional=True))


def test_a_present_regular_file_and_a_missing_one_are_told_apart(tmp_path: pathlib.Path) -> None:
    """Assert the optional-dataset predicate answers only the question it is asked.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    present = tmp_path / "there.txt"
    present.write_bytes(b"")

    assert source.seed_dataset_is_present(present) is True
    assert source.seed_dataset_is_present(tmp_path / "elsewhere.txt") is False
    # WHY : Refactoring Rationale: the type expected here is IsADirectoryError and was LayoutError.
    #   The predicate's refusals moved onto the standard filesystem exceptions, because the fault it
    #   reports is a filesystem state rather than a copybook transcription -- LayoutError declares
    #   itself the type for a broken geometry contract -- and because the sibling case for a named
    #   pipe requires an OSError that is not IsADirectoryError, which a ValueError subclass cannot
    #   be. The property asserted is unchanged: a directory is refused rather than reported absent,
    #   and the message names it.
    with pytest.raises(IsADirectoryError, match="directory"):
        source.seed_dataset_is_present(tmp_path)


def test_a_dataset_whose_size_does_not_divide_is_refused_before_a_handle_is_returned(
    tmp_path: pathlib.Path,
) -> None:
    """Assert the byte-mode open proves divisibility up front, not part way through a load.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the up-front check is the property being asserted, not merely that a
    #   truncated dataset eventually fails. A stream source discovers the shortfall only at the end,
    #   which for a large dataset means a load that has already written rows; checking the size on
    #   the descriptor that will be read costs one stat and reports before any row exists.
    dataset = tmp_path / "truncated.ps"
    dataset.write_bytes(b"A" * 130)

    with pytest.raises(EbcdicRecordLengthError):
        source.open_regular_binary(dataset, records_of=60)

    whole = tmp_path / "whole.ps"
    whole.write_bytes(b"A" * 120)
    handle = source.open_regular_binary(whole, records_of=60)
    assert handle is not None
    with handle:
        assert len(handle.read()) == 120


def test_a_record_of_the_wrong_width_cannot_have_a_key_sliced_from_it() -> None:
    """Assert the shared width guard refuses both a short and an over-long record.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): BOTH directions are asserted, because the guard replaced a
    #   shorter-than-declared test that accepted an over-long record. An over-long record is the
    #   more dangerous of the two: the key sliced from it comes from the right offsets of the wrong
    #   record -- two rows concatenated, most plausibly -- so it looks entirely well formed and a
    #   loader upserts on it.
    layout = trantype.TRANTYPE_LAYOUT
    exact = "X" * layout.reclen

    assert source.require_exact_record_width(exact, layout) == exact
    with pytest.raises(RecordLengthError):
        source.require_exact_record_width("X" * (layout.reclen - 1), layout)
    with pytest.raises(RecordLengthError):
        source.require_exact_record_width("X" * (layout.reclen + 1), layout)


def test_the_data_region_width_is_the_furthest_published_field_end() -> None:
    """Assert the shared bound is derived from the published tuple, in a shape order cannot break.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the FURTHEST end is asserted, not the last element's, because the two
    #   differ for a reader that publishes fields on both sides of a span it suppresses -- the
    #   security record does exactly that. Asserting the maximum states the property that holds for
    #   every reader rather than the one that happens to hold for a contiguous tuple.
    fields = xref.LOADED_FIELDS

    assert source.data_region_width(fields) == max(field.end for field in fields)
    assert source.data_region_width(tuple(reversed(fields))) == _XREF_DATA_WIDTH


def test_the_data_region_width_refuses_a_reader_that_publishes_no_field() -> None:
    """Assert an undefined data region is reported rather than raising a bare ValueError.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): an empty tuple would make `max` raise a bare ValueError naming neither the
    #   record nor the cause. The explicit refusal is what turns a misconfigured reader into a
    #   readable message.
    with pytest.raises(LayoutError, match="published no field"):
        source.data_region_width(())


def test_a_source_line_stopping_inside_the_data_region_is_refused_before_padding() -> None:
    """Assert the shared iterator refuses a short line rather than completing it with blanks.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the two fixtures differ by ONE character at the boundary, which is the only
    #   comparison that establishes the bound is exact rather than merely present. A line reaching
    #   the data region must be accepted even though the rest of the record is pad -- that is the
    #   shipped cross-reference seed, whose fifty lines carry 36 characters against a declared 50 --
    #   and a line one character shorter must be refused.
    # WHY (Assumptions): the check is asserted on the ITERATOR rather than on a padded record,
    #   because the source length is the only exact test: the daily transaction's last published
    #   field is legitimately blank on all 300 shipped records, so inspecting a padded row for a
    #   trailing space would refuse every one of them.
    layout = xref.XREF_LAYOUT
    complete = "1" * _XREF_DATA_WIDTH
    short = "1" * (_XREF_DATA_WIDTH - 1)

    accepted = list(
        iter_ascii_text_records(
            complete + "\n", layout.reclen, min_data_width=_XREF_DATA_WIDTH, layout=layout
        )
    )
    assert accepted == [complete.ljust(layout.reclen)]

    with pytest.raises(RecordLengthError, match="stops inside"):
        list(
            iter_ascii_text_records(
                short + "\n", layout.reclen, min_data_width=_XREF_DATA_WIDTH, layout=layout
            )
        )


def test_a_full_width_line_with_a_blank_trailing_field_is_still_accepted() -> None:
    """Assert the bound admits a record whose last published field is legitimately blank.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Refactoring Rationale): this is the case that decided WHERE the bound is applied. The
    #   first form of this check inspected the padded row for a trailing space, which is exact for
    #   the cross-reference record and refuses every shipped daily transaction, all 300 of which
    #   carry a blank processing timestamp because the posting run that writes it has not run.
    layout = dalytran.DALYTRAN_LAYOUT
    width = max(field.end for field in dalytran.LOADED_FIELDS)
    stamp = layout.field("DALYTRAN-PROC-TS")
    line = ("2" * stamp.start) + (" " * stamp.length)

    records = list(
        iter_ascii_text_records(line + "\n", layout.reclen, min_data_width=width, layout=layout)
    )

    assert len(records) == 1
    assert records[0][stamp.start : stamp.end] == " " * stamp.length


def test_a_data_region_bound_wider_than_the_record_is_refused() -> None:
    """Assert a mistaken bound is reported rather than clamped into vacuous behaviour.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): clamping would silently turn a mistaken bound into either "refuse
    #   everything" or "bound nothing" depending on which way it clamped, with nothing reporting the
    #   mistake -- and "bound nothing" is exactly the unbounded behaviour the parameter exists to
    #   end.
    layout = xref.XREF_LAYOUT

    with pytest.raises(LayoutError, match="data-region bound"):
        list(iter_ascii_text_records("x\n", layout.reclen, min_data_width=layout.reclen + 1))
    with pytest.raises(LayoutError, match="data-region bound"):
        list(iter_ascii_text_records("x\n", layout.reclen, min_data_width=-1))


@pytest.mark.parametrize("reclen", [0, -1])
def test_a_non_positive_record_length_is_refused(tmp_path: pathlib.Path, reclen: int) -> None:
    """Assert a record length below one is refused before any file is opened.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.
    reclen : int
        The invalid record length under test.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the guard is asserted against a path that does NOT exist, which proves the
    #   refusal happens before the open rather than after it. A bound derived from a non-positive
    #   width would make every read either empty or unbounded, so it has to be refused first.
    with pytest.raises(LayoutError):
        list(source.iter_seed_lines(tmp_path / "absent.txt", reclen))
    with pytest.raises(LayoutError):
        source.open_regular_binary(tmp_path / "absent.ps", records_of=reclen or -1)
