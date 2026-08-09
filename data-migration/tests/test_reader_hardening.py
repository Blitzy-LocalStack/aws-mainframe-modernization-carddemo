"""Exercise the reader-level consequences of the shared source guards and the FICO projection.

Purpose
-------
Assert the properties that belong to the READERS rather than to the shared module they delegate to:
that every reader with a file-taking entry point refuses a path that is not a regular file instead
of blocking on it or reporting no records, that a truncated line is refused rather than padded into
a plausible row, and that the customer master's bounded credit score arrives as an integer on both
corpora while every identifier keeps its leading zeroes.

Assumptions: the special-file and truncation cases are asserted through the READERS' own published
entry points rather than against ``readers.source`` directly. ``test_source_hardening.py`` already
covers the shared implementation; what this module adds is that each reader actually reaches it —
which is the half that regresses, because a reader can be edited back to a plain open without the
shared module changing at all.
"""

from __future__ import annotations

import os
import pathlib
from collections.abc import Callable, Iterator
from dataclasses import dataclass
from typing import Final

import pytest

from carddemo_migration.copybook import layouts
from carddemo_migration.readers import (
    account,
    card,
    customer,
    dalytran,
    discgrp,
    tcatbal,
    trancatg,
    transaction,
    trantype,
    xref,
)


@dataclass(frozen=True)
class AsciiReaderCase:
    """One reader's ASCII file-taking entry point, paired with its layout.

    Parameters
    ----------
    name : str
        The reader module's name, used as the test identifier.
    read : Callable[[pathlib.Path], Iterator[object]]
        The reader's ``read_ascii_*`` entry point.
    layout : layouts.RecordSpec
        The record descriptor, supplying the declared width.
    loaded_fields : tuple[layouts.FieldSpec, ...]
        The fields the reader publishes, whose furthest end offset is the data region.

    Raises
    ------
    None
    """

    name: str
    read: Callable[[pathlib.Path], Iterator[object]]
    layout: layouts.RecordSpec
    loaded_fields: tuple[layouts.FieldSpec, ...]


# Assumptions: the nine readers with a COMMITTED ASCII seed are covered. The transaction reader is
#   absent because its input may legitimately not exist, which makes its absence semantics a
#   different property covered by its own cases; the security and export readers are absent because
#   neither publishes an ASCII entry point at all.
_ASCII_READERS: Final[tuple[AsciiReaderCase, ...]] = (
    AsciiReaderCase(
        "account", account.read_ascii_accounts, layouts.ACCOUNT_LAYOUT, account.LOADED_FIELDS
    ),
    AsciiReaderCase("card", card.read_ascii_cards, layouts.CARD_LAYOUT, card.LOADED_FIELDS),
    AsciiReaderCase(
        "customer", customer.read_ascii_customers, layouts.CUSTOMER_LAYOUT, customer.LOADED_FIELDS
    ),
    AsciiReaderCase("xref", xref.read_ascii_card_xrefs, layouts.XREF_LAYOUT, xref.LOADED_FIELDS),
    AsciiReaderCase(
        "dalytran",
        dalytran.read_ascii_daily_transactions,
        layouts.DALYTRAN_LAYOUT,
        dalytran.LOADED_FIELDS,
    ),
    AsciiReaderCase(
        "tcatbal",
        tcatbal.read_ascii_category_balances,
        layouts.TCATBAL_LAYOUT,
        tcatbal.LOADED_FIELDS,
    ),
    AsciiReaderCase(
        "discgrp",
        discgrp.read_ascii_disclosure_groups,
        layouts.DISGROUP_LAYOUT,
        discgrp.LOADED_FIELDS,
    ),
    AsciiReaderCase(
        "trantype",
        trantype.read_ascii_transaction_types,
        layouts.TRANTYPE_LAYOUT,
        trantype.LOADED_FIELDS,
    ),
    AsciiReaderCase(
        "trancatg",
        trancatg.read_ascii_transaction_categories,
        layouts.TRANCAT_LAYOUT,
        trancatg.LOADED_FIELDS,
    ),
)

_ASCII_IDS: Final[tuple[str, ...]] = tuple(case.name for case in _ASCII_READERS)


@pytest.mark.parametrize("case", _ASCII_READERS, ids=_ASCII_IDS)
def test_every_ascii_reader_refuses_a_named_pipe(
    case: AsciiReaderCase, tmp_path: pathlib.Path
) -> None:
    """Assert each reader refuses a writer-less FIFO rather than waiting on it.

    Parameters
    ----------
    case : AsciiReaderCase
        The reader under test.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Trade-offs): no timeout guards these cases. A reader edited back to a plain `Path.open`
    #   makes the suite HANG here rather than fail, which is blunt but unambiguous -- and an
    #   indefinite block in a nightly load step is exactly what the refusal exists to prevent, so a
    #   soft timeout failure would understate it.
    fifo = tmp_path / f"{case.name}.pipe"
    os.mkfifo(fifo)

    with pytest.raises(layouts.LayoutError, match="named pipe"):
        list(case.read(fifo))


@pytest.mark.parametrize("case", _ASCII_READERS, ids=_ASCII_IDS)
def test_every_ascii_reader_refuses_a_directory(
    case: AsciiReaderCase, tmp_path: pathlib.Path
) -> None:
    """Assert a directory named where a seed file was meant is refused, not read as empty.

    Parameters
    ----------
    case : AsciiReaderCase
        The reader under test.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    with pytest.raises(layouts.LayoutError, match="directory"):
        list(case.read(tmp_path))


@pytest.mark.parametrize("case", _ASCII_READERS, ids=_ASCII_IDS)
def test_every_ascii_reader_bounds_an_unterminated_line(
    case: AsciiReaderCase, tmp_path: pathlib.Path
) -> None:
    """Assert a separator-free file far longer than one record is refused on width.

    Parameters
    ----------
    case : AsciiReaderCase
        The reader under test.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the fixture is a hundred records' worth of digits with no separator, which
    #   is the shape a mis-converted extract takes when the conversion drops the line terminator.
    #   Before the bound, every reader materialised the whole line before the width check that would
    #   have refused it; the message asserted below names a LOWER bound, which is only reportable if
    #   the read stopped early.
    unbounded = tmp_path / f"{case.name}.txt"
    unbounded.write_text("7" * (case.layout.reclen * 100), encoding="latin-1")

    with pytest.raises(layouts.RecordLengthError, match="at least"):
        list(case.read(unbounded))


@pytest.mark.parametrize("case", _ASCII_READERS, ids=_ASCII_IDS)
def test_every_ascii_reader_refuses_a_line_that_stops_inside_its_data(
    case: AsciiReaderCase, tmp_path: pathlib.Path
) -> None:
    """Assert a short line is refused rather than right-padded into a plausible row.

    Parameters
    ----------
    case : AsciiReaderCase
        The reader under test.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Refactoring Rationale): the shared text iterator right-pads a short line, which is what
    #   lets the cross-reference seed be read at all -- its 50 lines carry 36 characters each, the
    #   missing 14 being exactly its trailing FILLER. It padded a line of ANY length, so a line that
    #   stopped inside a published field was completed with manufactured blanks and accepted: a
    #   blank key, a blank description, a fabricated timestamp. The fixture below stops one
    #   character short of the data region, which is the exact boundary between the tolerance and
    #   the fault.
    # WHY (Assumptions): the fixture is all digits rather than spaces, so the refusal cannot be
    #   satisfied accidentally by a display-regime complaint about a blank in a numeric field -- the
    #   only thing wrong with this row is its length.
    data_width = max(field.end for field in case.loaded_fields)
    truncated = tmp_path / f"{case.name}.txt"
    truncated.write_text("1" * (data_width - 1) + "\n", encoding="latin-1")

    with pytest.raises(layouts.RecordLengthError, match="stops inside"):
        list(case.read(truncated))


@pytest.mark.parametrize("case", _ASCII_READERS, ids=_ASCII_IDS)
def test_a_line_reaching_the_data_region_is_read_with_its_pad_restored(
    case: AsciiReaderCase, tmp_path: pathlib.Path
) -> None:
    """Assert the one tolerance survives: a line short only by its trailing pad still reads.

    Parameters
    ----------
    case : AsciiReaderCase
        The reader under test.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): this is the companion of the case above and the two differ by ONE
    #   character,
    #   which is what establishes the bound is exact rather than merely present. A bound that
    #   refused this row would break the shipped cross-reference seed outright, so the tolerance has
    #   to be asserted as carefully as the refusal.
    # WHY (Trade-offs): the row is refused for a FIELD reason or accepted, and both outcomes pass.
    #   A row of digits satisfies every numeric field but not necessarily every closed-domain or
    #   date field this corpus declares, and inventing a per-reader valid row here would restate
    #   nine records' content in a test about lengths. What must NOT happen is a length complaint,
    #   and that is what is asserted.
    data_width = max(field.end for field in case.loaded_fields)
    if data_width == case.layout.reclen:
        pytest.skip(f"{case.name} declares no trailing pad, so it has no tolerance to exercise")

    padded = tmp_path / f"{case.name}.txt"
    padded.write_text("1" * data_width + "\n", encoding="latin-1")

    try:
        list(case.read(padded))
    except layouts.RecordLengthError as exc:  # pragma: no cover - a failure path
        pytest.fail(f"a line reaching the data region must not be refused on length: {exc}")
    except layouts.LayoutError:
        pass
    except ValueError:
        pass


def test_the_customer_credit_score_is_an_integer_on_both_corpora(
    repo_root: pathlib.Path,
) -> None:
    """Assert the bounded score projects to ``int`` from the ASCII seed and the EBCDIC dataset.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root, from the shared session fixture.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Refactoring Rationale): the score used to come out as the three characters the record
    #   holds -- '001' for a score of one -- and the target column is a bounded SMALLINT, so the
    #   conversion had to happen somewhere. Leaving it to the loader would have put a decision about
    #   a copybook field's MEANING in the layer whose job is to write rows, and would have let the
    #   two corpora disagree about the type of one field.
    # WHY (Assumptions): both corpora are asserted, and their decoded rows are compared for
    #   EQUALITY, because a projection applied on one path only is the failure this most invites --
    #   and it would pass any test that read a single corpus.
    ascii_rows = list(customer.read_ascii_customers(repo_root / "app/data/ASCII/custdata.txt"))
    ebcdic_rows = list(
        customer.read_ebcdic_customers(repo_root / "app/data/EBCDIC/AWS.M2.CARDDEMO.CUSTDATA.PS")
    )

    assert len(ascii_rows) == len(ebcdic_rows) == 50
    assert ascii_rows == ebcdic_rows
    for row in ascii_rows:
        score = row["CUST-FICO-CREDIT-SCORE"]
        assert isinstance(score, int)
        assert not isinstance(score, bool)
        assert 0 <= score <= 999


def test_the_customer_identifiers_keep_their_leading_zeroes(repo_root: pathlib.Path) -> None:
    """Assert the projection is scoped to the score and touches no identifier.

    Parameters
    ----------
    repo_root : pathlib.Path
        Repository root, from the shared session fixture.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the two other unsigned display fields of this record are asserted to be
    #   fixed-width STRINGS, because they share the score's storage regime and differ from it in
    #   kind. CUST-ID is the nine-digit join key the cross-reference points at; converting either it
    #   or the national identifier to an integer would drop a leading zero and produce a value that
    #   no longer matches the record it came from -- and the shipped seed's first customer is
    #   '000000001', so the failure would be immediate and silent.
    rows = list(customer.read_ascii_customers(repo_root / "app/data/ASCII/custdata.txt"))

    for row in rows:
        identifier = row["CUST-ID"]
        national = row["CUST-SSN"]
        assert isinstance(identifier, str)
        assert isinstance(national, str)
        assert len(identifier) == layouts.CUSTOMER_LAYOUT.field("CUST-ID").length
        assert len(national) == layouts.CUSTOMER_LAYOUT.field("CUST-SSN").length
    assert rows[0]["CUST-ID"] == "000000001"


def test_the_score_projection_names_exactly_one_field() -> None:
    """Assert no field other than the score is projected away from its declared characters.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the assertion is over every field of a decoded record rather than over the
    #   projection's own constant, so a second field added to the projection later fails here
    #   whether or not the constant is still a single name. The published rule is that exactly one
    #   field of this record is not characters.
    record = "1" * layouts.CUSTOMER_LAYOUT.reclen
    decoded = customer.decode_ascii_customer(record)
    non_text = {name for name, value in decoded.items() if not isinstance(value, str)}

    assert non_text == {"CUST-FICO-CREDIT-SCORE"}


# Assumptions: the transaction reader gets its own case table rather than joining the nine above,
#   because it is the ONE reader whose input may legitimately not exist. The nine share the property
#   that a missing seed is a fault; this one has to distinguish a missing seed from a bad path, and
#   that distinction is what these cases assert. Both of its encodings are covered by the same table
#   so neither can be hardened without the other.
_TRANSACTION_READERS: Final[tuple[tuple[str, Callable[[pathlib.Path], Iterator[object]]], ...]] = (
    ("ascii", transaction.read_ascii_transactions),
    ("ebcdic", transaction.read_ebcdic_transactions),
)

_TRANSACTION_IDS: Final[tuple[str, ...]] = tuple(name for name, _ in _TRANSACTION_READERS)

_TRANSACTION_FIXTURE: Final[str] = "export/happy_path"
_TRANSACTION_DATASET: Final[str] = "trandata.txt"


def _transaction_dataset(records: tuple[str, ...], destination: pathlib.Path) -> pathlib.Path:
    """Write character records out as a fixed-length EBCDIC dataset.

    Purpose
    -------
    Produce the byte form :func:`transaction.read_ebcdic_transactions` consumes from the committed
    character fixture, so the byte path is exercised over real records rather than over filler.

    Parameters
    ----------
    records : tuple[str, ...]
        Whole records at the declared character width.
    destination : pathlib.Path
        The dataset file to write.

    Returns
    -------
    pathlib.Path
        ``destination``, for use in a single expression.

    Raises
    ------
    None
    """
    # WHY (Assumptions): the character images encode to cp037 faithfully because this package's
    #   character convention for a signed display field IS the EBCDIC one -- '{' for positive zero,
    #   'A'-'I' for positive one through nine -- and cp037 maps those to the 0xC0-0xC9 range the
    #   dataset holds. The companion test asserts the two encodings decode EQUAL, which is what
    #   proves the assumption rather than assuming it.
    destination.write_bytes(
        b"".join(record.ljust(layouts.TRAN_LAYOUT.reclen).encode("cp037") for record in records)
    )
    return destination


@pytest.mark.parametrize(("name", "read"), _TRANSACTION_READERS, ids=_TRANSACTION_IDS)
def test_a_missing_transaction_source_is_the_only_absence_either_encoding_accepts(
    name: str, read: Callable[[pathlib.Path], Iterator[object]], tmp_path: pathlib.Path
) -> None:
    """Assert a genuinely missing name yields no records and raises nothing.

    Parameters
    ----------
    name : str
        The encoding under test, used as the test identifier.
    read : Callable[[pathlib.Path], Iterator[object]]
        The reader's file-taking entry point.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): this is the tolerance the rest of these cases bound. It must be asserted
    #   first and on BOTH encodings, because a hardening change that closed the bad-path cases by
    #   also raising for a missing name would break the one state this record is allowed to be in --
    #   and `sql/verify/row_counts.sql` accepts a zero row count for it on exactly that ground.
    assert list(read(tmp_path / f"absent-{name}")) == []


@pytest.mark.parametrize(("name", "read"), _TRANSACTION_READERS, ids=_TRANSACTION_IDS)
def test_a_directory_is_not_read_as_an_absent_transaction_source(
    name: str, read: Callable[[pathlib.Path], Iterator[object]], tmp_path: pathlib.Path
) -> None:
    """Assert a directory is refused rather than reported as no seed.

    Parameters
    ----------
    name : str
        The encoding under test.
    read : Callable[[pathlib.Path], Iterator[object]]
        The reader's file-taking entry point.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Refactoring Rationale): `Path.is_file()` returns False for a directory, so this input
    #   used to produce a SUCCESSFUL load of zero rows on both encodings. That is the worst outcome
    #   available for this particular record: it is the one whose verification baseline is NULL
    #   rather than a count, so a silent zero-row load had no downstream check left to catch it.
    directory = tmp_path / f"dir-{name}"
    directory.mkdir()

    with pytest.raises(layouts.LayoutError, match="directory"):
        list(read(directory))


@pytest.mark.parametrize(("name", "read"), _TRANSACTION_READERS, ids=_TRANSACTION_IDS)
def test_a_named_pipe_is_not_read_as_an_absent_transaction_source(
    name: str, read: Callable[[pathlib.Path], Iterator[object]], tmp_path: pathlib.Path
) -> None:
    """Assert a writer-less FIFO is refused rather than waited on or reported absent.

    Parameters
    ----------
    name : str
        The encoding under test.
    read : Callable[[pathlib.Path], Iterator[object]]
        The reader's file-taking entry point.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Trade-offs): as with the nine, no timeout guards this. A regression HANGS the suite
    #   rather than failing it, which is blunt but cannot be misread, and an unbounded wait inside a
    #   nightly load step is precisely the outcome the non-blocking open exists to prevent.
    fifo = tmp_path / f"pipe-{name}"
    os.mkfifo(fifo)

    with pytest.raises(layouts.LayoutError, match="named pipe"):
        list(read(fifo))


@pytest.mark.parametrize(("name", "read"), _TRANSACTION_READERS, ids=_TRANSACTION_IDS)
def test_a_dangling_symbolic_link_is_not_read_as_an_absent_transaction_source(
    name: str, read: Callable[[pathlib.Path], Iterator[object]], tmp_path: pathlib.Path
) -> None:
    """Assert a link to nothing is refused, though a missing name is accepted.

    Parameters
    ----------
    name : str
        The encoding under test.
    read : Callable[[pathlib.Path], Iterator[object]]
        The reader's file-taking entry point.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): this is the case that most needs stating, because the operating system
    #   reports it with the SAME errno as a missing name. The distinction is intent: nobody creates
    #   a missing file, and somebody did create this link, so a link whose target has gone is a
    #   staging fault rather than the normal pre-posting state.
    dangling = tmp_path / f"link-{name}"
    dangling.symlink_to(tmp_path / f"gone-{name}")

    with pytest.raises(layouts.LayoutError, match="symbolic link"):
        list(read(dangling))


@pytest.mark.skipif(os.geteuid() == 0, reason="a superuser is not stopped by a mode bit")
@pytest.mark.parametrize(("name", "read"), _TRANSACTION_READERS, ids=_TRANSACTION_IDS)
def test_an_unreadable_transaction_source_is_not_read_as_absent(
    name: str, read: Callable[[pathlib.Path], Iterator[object]], tmp_path: pathlib.Path
) -> None:
    """Assert a permission denial propagates rather than being normalised to no records.

    Parameters
    ----------
    name : str
        The encoding under test.
    read : Callable[[pathlib.Path], Iterator[object]]
        The reader's file-taking entry point.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): skipped for a superuser rather than worked around, because a mode bit does
    #   not stop one and a test that cannot fail is worse than an absent one. CI runs as an ordinary
    #   user, where the assertion holds. `Path.is_file()` answered False here too, so an operator
    #   who staged a dataset with the wrong owner got a clean run that loaded nothing.
    denied = tmp_path / f"denied-{name}"
    denied.write_bytes(b"\x00" * layouts.TRAN_LAYOUT.reclen)
    denied.chmod(0o000)

    with pytest.raises(PermissionError):
        list(read(denied))


@pytest.mark.parametrize(("name", "read"), _TRANSACTION_READERS, ids=_TRANSACTION_IDS)
def test_an_uninspectable_transaction_path_is_not_read_as_absent(
    name: str, read: Callable[[pathlib.Path], Iterator[object]], tmp_path: pathlib.Path
) -> None:
    """Assert a path that cannot be inspected at all propagates its error rather than reading empty.

    Parameters
    ----------
    name : str
        The encoding under test.
    read : Callable[[pathlib.Path], Iterator[object]]
        The reader's file-taking entry point.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Alternatives Considered): the inaccessible case is asserted here through a path whose
    #   PARENT is a regular file, which reports ENOTDIR, rather than only through a mode bit. The
    #   mode-bit case is the more obvious one and is asserted above, but it has to be skipped for a
    #   superuser -- and this suite runs as one in the container. This variant cannot be skipped,
    #   because no privilege makes a regular file traversable, so the "inaccessible is not absence"
    #   property keeps at least one assertion that always executes.
    obstruction = tmp_path / f"file-{name}"
    obstruction.write_bytes(b"x")

    with pytest.raises(OSError) as raised:
        list(read(obstruction / "dataset.ps"))
    assert not isinstance(raised.value, FileNotFoundError)


@pytest.mark.parametrize(("name", "read"), _TRANSACTION_READERS, ids=_TRANSACTION_IDS)
def test_a_zero_byte_transaction_source_yields_nothing_without_being_called_absent(
    name: str, read: Callable[[pathlib.Path], Iterator[object]], tmp_path: pathlib.Path
) -> None:
    """Assert an empty file reads as no records and is still reported present.

    Parameters
    ----------
    name : str
        The encoding under test.
    read : Callable[[pathlib.Path], Iterator[object]]
        The reader's file-taking entry point.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): empty and absent produce the same RECORDS and different PRESENCE answers,
    #   and both halves are asserted. A staged-but-empty generation is a real state the batch chain
    #   produces, and collapsing it into "absent" would lose the only evidence that the stage ran.
    empty = tmp_path / f"empty-{name}"
    empty.write_bytes(b"")

    assert list(read(empty)) == []
    assert transaction.seed_dataset_is_present(empty) is True


def test_the_transaction_ascii_path_bounds_an_unterminated_line(tmp_path: pathlib.Path) -> None:
    """Assert a separator-free file far longer than one record is refused on width.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the bound is asserted through the LOWER-bound wording of the message, which
    #   is only reportable if the read stopped before the line ended. A reader that materialised the
    #   whole line could report an exact length, so the wording is the evidence.
    unbounded = tmp_path / "unbounded.txt"
    unbounded.write_text("7" * (layouts.TRAN_LAYOUT.reclen * 100), encoding="latin-1")

    with pytest.raises(layouts.RecordLengthError, match="at least"):
        list(transaction.read_ascii_transactions(unbounded))


def test_the_transaction_ebcdic_path_refuses_a_dataset_that_does_not_divide(
    tmp_path: pathlib.Path,
) -> None:
    """Assert an indivisible dataset fails before the first record, not at the end.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the refusal is demanded from the FIRST advance of the iterator rather than
    #   from exhausting it, which is what distinguishes an up-front size check from the
    #   end-of-stream check a stream source would otherwise fall back on. The distinction is
    #   operational: an up-front failure loads no rows, an end-of-stream failure loads all but the
    #   last.
    truncated = tmp_path / "truncated.ps"
    truncated.write_bytes(b"\x40" * (layouts.TRAN_LAYOUT.reclen + 1))

    records = transaction.read_ebcdic_transactions(truncated)
    with pytest.raises(layouts.RecordLengthError, match="does not divide"):
        next(records)


def test_the_transaction_reader_reads_its_committed_fixture_in_both_encodings(
    fixture_corpus: object, tmp_path: pathlib.Path
) -> None:
    """Assert the hardened paths still read real records, identically on both encodings.

    Parameters
    ----------
    fixture_corpus : object
        Accessor over ``tests/fixtures``, from the shared session fixture.
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Trade-offs): the positive case is asserted from the committed export fixture rather than
    #   from synthesised filler, because this record ships no seed of its own and filler proves only
    #   that lengths line up. Real records prove the decode, and comparing the two encodings for
    #   EQUALITY proves the hardening did not change one path's behaviour without the other's.
    records = fixture_corpus.records(_TRANSACTION_FIXTURE, _TRANSACTION_DATASET)  # type: ignore[attr-defined]
    assert records, "the committed transaction fixture must not be empty"

    text = tmp_path / "trandata.txt"
    text.write_text("".join(f"{record}\n" for record in records), encoding="latin-1")
    dataset = _transaction_dataset(records, tmp_path / "trandata.ps")

    from_text = list(transaction.read_ascii_transactions(text))
    from_dataset = list(transaction.read_ebcdic_transactions(dataset))

    assert len(from_text) == len(records)
    assert from_text == from_dataset


def test_the_transaction_presence_predicate_reports_only_genuine_absence(
    tmp_path: pathlib.Path,
) -> None:
    """Assert the published predicate splits missing from invalid rather than conflating them.

    Parameters
    ----------
    tmp_path : pathlib.Path
        Per-test temporary directory supplied by pytest.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the predicate is asserted separately from the two entry points even though
    #   it no longer guards them, because it stays published: a caller may ask before it starts
    #   reading, and an answer that disagreed with what the readers then do would be worse than no
    #   predicate at all. All four outcomes are covered in one place so none can drift alone.
    present = tmp_path / "there.ps"
    present.write_bytes(b"")
    dangling = tmp_path / "link.ps"
    dangling.symlink_to(tmp_path / "gone.ps")

    assert transaction.seed_dataset_is_present(present) is True
    assert transaction.seed_dataset_is_present(tmp_path / "absent.ps") is False
    # WHY : Refactoring Rationale: the type expected here is IsADirectoryError and was LayoutError.
    #   The predicate's refusals moved onto the standard filesystem exceptions, because the fault it
    #   reports is a filesystem state rather than a copybook transcription -- LayoutError declares
    #   itself the type for a broken geometry contract -- and because the sibling case for a named
    #   pipe requires an OSError that is not IsADirectoryError, which a ValueError subclass cannot
    #   be. The property asserted is unchanged: a directory is refused rather than reported absent,
    #   and the message names it.
    with pytest.raises(IsADirectoryError, match="directory"):
        transaction.seed_dataset_is_present(tmp_path)
    with pytest.raises(layouts.LayoutError, match="symbolic link"):
        transaction.seed_dataset_is_present(dangling)
