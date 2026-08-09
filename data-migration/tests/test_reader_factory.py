"""Exercise the layout-driven reader the command line actually builds, for every record.

Purpose
-------
Execute :mod:`carddemo_migration.readers.factory`. Until this module existed nothing in the
distribution constructed a :class:`~carddemo_migration.readers.factory.RecordReader` except
``cli.py``, so the reader behind every ``load-dataset``, ``verify-row-counts``,
``verify-checksum`` and ``verify-money-parity`` invocation had no test of its own. Its twelve
hand-written siblings are covered by ``test_readers.py``, which imports them by name and never
touches this one -- and that asymmetry is what let the two implementations of one contract
drift apart on the field that mattered most.

Refactoring Rationale: the drift this module now pins was real and not hypothetical. The
specialised security-user reader withheld ``SEC-USR-PWD``, an eight-character password the
baseline stores in clear; the generic reader built from the same descriptor published it,
because the suppression was declared as a set of names inside the specialised module where no
other path could see it. The generic reader is the one the command line uses, so the covered
path was the safe one and the uncovered path was the exposed one. The judgement now lives on
the field descriptor, and the tests below assert it through this reader rather than through the
module that already agreed.

Assumptions: every assertion drives the PUBLIC entry points -- ``decode_ascii``,
``iter_ascii``, ``read_ascii``, ``decode_ebcdic``, ``iter_ebcdic``, ``read_ebcdic`` and the two
masked renderings -- and the corpora are the shipped extracts under ``app/data``. Reaching into
a private helper would prove the implementation agrees with itself; a caller can only see what
these eight names return.

Assumptions: no password, account identifier or primary account number appears in this file as
a literal. Where a test needs to prove a value is absent it derives that value from the corpus
at run time, so the file itself never carries one.
"""

from __future__ import annotations

import pathlib
from typing import TYPE_CHECKING, Final

import pytest

from carddemo_migration.copybook import layouts
from carddemo_migration.copybook.layouts import LayoutError, RecordLengthError
from carddemo_migration.readers import usrsec
from carddemo_migration.readers.factory import RecordReader

if TYPE_CHECKING:
    from tests.conftest import SeedCorpus

# WHY : Assumptions: the security-user record is named once, here, because it is the one record
#   in the registry that declares a suppressed field. Every test below that needs it reads this
#   name, and the inventory test proves the registry contains no second such record -- so a
#   future record that suppressed a field would fail that test rather than silently escape this
#   module's coverage.
_SUPPRESSING_RECORD: Final[str] = "SECUSER"

# WHY : the shipped extract for that record, in the only form it ships in. `app/data/ASCII`
#   holds nine text twins and none of them is this record, which is the corpus half of the
#   reason its character entry points are refused.
_SUPPRESSING_EXTRACT: Final[str] = "AWS.M2.CARDDEMO.USRSEC.PS"

_CHARACTER_ENTRY_POINTS: Final[tuple[str, ...]] = ("decode_ascii", "iter_ascii", "read_ascii")


def _reader(record_name: str) -> RecordReader:
    """Build the reader for a record exactly as the command line builds it.

    :param record_name: the registered layout name.
    :returns: a reader bound to that layout.
    """
    # WHY : the layout is fetched through `layouts.layout` rather than indexed out of the
    #   registry mapping, because that is the call `cli._reader_and_records` makes. A test that
    #   built the reader by a different route could pass while the CLI's route was broken.
    return RecordReader(layouts.layout(record_name))


def test_exactly_one_registered_record_withholds_a_field() -> None:
    """Pin the inventory of suppressed fields across the whole layout registry.

    :returns: nothing; a second suppressing record, or none, is reported as a failure.
    """
    # WHY : Trade-offs: this asserts a COUNT rather than merely that the security-user record
    #   suppresses its password. The count is what makes the rest of this module honest: every
    #   other test here reasons about "the suppressing record" in the singular, and if a second
    #   one were added those tests would silently cover only the first. A failure here is the
    #   instruction to extend this module, which is the outcome that keeps coverage complete.
    suppressing = {
        name: sorted(field.name for field in layouts.layout(name).fields if field.suppressed)
        for name in layouts.names()
    }
    withheld = {name: fields for name, fields in suppressing.items() if fields}
    assert withheld == {_SUPPRESSING_RECORD: ["SEC-USR-PWD"]}


def test_a_suppressed_field_is_absent_from_every_record_the_byte_path_yields(
    seed_corpus: SeedCorpus,
) -> None:
    """Decode the whole shipped extract and prove the withheld field never appears.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a published credential is reported as a failure.
    """
    # WHY : Assumptions: EVERY record in the extract is checked rather than the first, because
    #   suppression is applied per record and a projection that depended on a value -- on a blank
    #   password, say -- would hold for some rows and not others. Ten records is the whole file.
    reader = _reader(_SUPPRESSING_RECORD)
    records = list(reader.read_ebcdic(seed_corpus.ebcdic_path(_SUPPRESSING_EXTRACT)))
    assert records, "the shipped extract must yield records for this assertion to mean anything"
    for record in records:
        assert "SEC-USR-PWD" not in record
        assert set(record) == {field.name for field in reader.loaded_fields}


def test_no_value_the_byte_path_yields_carries_the_withheld_span(
    seed_corpus: SeedCorpus,
) -> None:
    """Prove the withheld content is absent from the VALUES, not merely from the keys.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a credential surfacing under another key is reported as a failure.
    """
    # WHY : Alternatives Considered: asserting only that the key is gone, which the sibling test
    #   above does. That is necessary and not sufficient: a reader that decoded the record with
    #   the wrong offsets, or that merged two adjacent spans, would drop the key and still emit
    #   the eight characters under the name of the field beside it. This test reads the expected
    #   plaintext out of the raw image at the descriptor's own offsets -- so it is derived, never
    #   written here -- and then proves no published value contains it.
    layout = layouts.layout(_SUPPRESSING_RECORD)
    field = layout.field("SEC-USR-PWD")
    path = seed_corpus.ebcdic_path(_SUPPRESSING_EXTRACT)
    image = pathlib.Path(path).read_bytes()[: layout.reclen]
    withheld = image[field.start : field.end].decode("cp037")
    assert withheld.strip(), "the shipped record must hold a non-blank password here"

    reader = _reader(_SUPPRESSING_RECORD)
    first = next(iter(reader.iter_ebcdic(pathlib.PurePath(path))))
    for name, value in first.items():
        assert withheld not in str(value), f"{name} carries the withheld span"


@pytest.mark.parametrize("entry_point", _CHARACTER_ENTRY_POINTS)
def test_every_character_entry_point_is_refused_for_a_suppressing_record(
    entry_point: str,
    tmp_path: pathlib.Path,
) -> None:
    """Refuse all three ASCII entry points for a record that withholds a field.

    :param entry_point: the public character-path name under test.
    :param tmp_path: pytest-provided directory for the file-path case.
    :returns: nothing; an admitted character decode is reported as a failure.
    """
    # WHY : Assumptions: all three are asserted rather than one, because they are three separate
    #   public names and the refusal is installed in a helper each one calls. A test covering
    #   only `decode_ascii` would pass while `read_ascii` -- the one a caller reaches through the
    #   CLI's `--encoding ascii` -- stayed open.
    # WHY : Trade-offs: the record handed in is all-zero filler of the declared width rather
    #   than anything resembling a real row. The refusal has to happen BEFORE any field is
    #   decoded, so a well-formed input is not needed to reach it -- and using filler keeps this
    #   file free of a record shaped like a credential.
    reader = _reader(_SUPPRESSING_RECORD)
    layout = layouts.layout(_SUPPRESSING_RECORD)
    record = "0" * layout.reclen
    source = tmp_path / "transcoded.txt"
    source.write_text(record + "\n", encoding="latin-1")

    with pytest.raises(LayoutError) as refused:
        result = getattr(reader, entry_point)(source if entry_point == "read_ascii" else record)
        if entry_point != "decode_ascii":
            list(result)

    message = str(refused.value)
    assert _SUPPRESSING_RECORD in message
    assert "SEC-USR-PWD" in message
    # The message must send the caller to the path that works, not merely say no.
    assert "EBCDIC" in message
    # WHY : and it must not echo the record it was handed. The refusal fires before any decode,
    #   so there is nothing it needs to quote, and a caller who transcoded a real extract to get
    #   here would otherwise have that content copied into a diagnostic.
    assert record not in message


def test_the_specialised_reader_and_the_layout_driven_one_withhold_the_same_field() -> None:
    """Prove the two implementations of one contract now read a single authority.

    :returns: nothing; disagreement between the two readers is reported as a failure.
    """
    # WHY : Refactoring Rationale: this is the assertion whose absence allowed the defect. The
    #   two readers published different field sets for the same record, and every existing test
    #   drove the specialised one, so nothing compared them. Comparing them here means a future
    #   change that re-localises the judgement into one module fails immediately.
    reader = _reader(_SUPPRESSING_RECORD)
    assert reader.suppressed_field_names == usrsec.SUPPRESSED_FIELD_NAMES
    assert {field.name for field in reader.loaded_fields} == {
        field.name for field in usrsec.LOADED_FIELDS
    }
    # The two exclusions stay distinguishable: a pad and a withheld credential are different
    # facts, and a caller inspecting a reader can tell them apart.
    assert reader.dropped_field_names == usrsec.DROPPED_FIELD_NAMES
    assert not reader.dropped_field_names & reader.suppressed_field_names


def test_the_two_readers_agree_field_for_field_on_every_shipped_record(
    seed_corpus: SeedCorpus,
) -> None:
    """Decode the shipped extract through both readers and compare the published rows.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; any decoded difference is reported as a failure.
    """
    # WHY : Trade-offs: the comparison is over decoded VALUES and not only field names, because
    #   the two readers reach their rows differently -- the specialised one decodes field by
    #   field through the per-field boundary, the layout-driven one decodes the whole image
    #   through the record codec and then projects. Those are two routes to one contract, and
    #   the only assertion that they still meet is to run both over real bytes and compare.
    path = seed_corpus.ebcdic_path(_SUPPRESSING_EXTRACT)
    layout_driven = list(_reader(_SUPPRESSING_RECORD).read_ebcdic(path))
    specialised = list(usrsec.read_ebcdic_security_users(pathlib.Path(path)))
    assert layout_driven == specialised


def test_a_record_declaring_a_pad_but_no_withheld_field_keeps_its_character_path(
    seed_corpus: SeedCorpus,
) -> None:
    """Prove the refusal is scoped to suppression and does not close a pad-bearing record.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a wrongly refused character path is reported as a failure.
    """
    # WHY : Assumptions: this is the negative control for the refusal above, and it is needed.
    #   The withheld span and the trailing pad are both excluded from a decoded record, so a fix
    #   that keyed the refusal on "this record excludes a field" instead of "this record
    #   withholds one" would close the ASCII path for most of the corpus -- and every ASCII load
    #   in the runbook would stop working. The account master declares a pad and no withheld
    #   field, and its ASCII twin is one of the nine shipped ones.
    reader = _reader("ACCOUNT")
    assert reader.dropped_field_names
    assert not reader.suppressed_field_names
    rows = list(reader.read_ascii(seed_corpus.ascii_path("acctdata.txt")))
    assert rows
    assert "FILLER" not in rows[0]


def test_a_suppressed_field_may_not_be_declared_disclosable() -> None:
    """Refuse a descriptor that withholds a field from records but admits it to diagnostics.

    :returns: nothing; an accepted declaration is reported as a failure.
    """
    # WHY : Assumptions: the implication is asserted at the DESCRIPTOR, because that is where it
    #   is enforced, and it closes the one route that would otherwise remain open. A field
    #   withheld from every record but renderable by the masking helpers would leak through a
    #   diagnostic, and the declaration would read as deliberate rather than as an oversight.
    with pytest.raises(LayoutError) as refused:
        layouts.FieldSpec("SEC-USR-PWD", 48, 8, layouts.Kind.TEXT, suppressed=True)
    message = str(refused.value)
    assert "suppressed" in message
    assert "sensitive" in message


def test_the_masked_rendering_of_a_withheld_field_emits_no_content(
    seed_corpus: SeedCorpus,
) -> None:
    """Prove the reader's two masked renderings do not reopen the withheld span.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a rendered credential is reported as a failure.
    """
    # WHY : Alternatives Considered: trusting the descriptor's sensitivity flag, which the
    #   suppression invariant now forces to be set. That is the mechanism, and this asserts the
    #   OUTCOME through the two public rendering names -- the pair a diagnostic actually calls --
    #   so the guarantee survives a change to how the flag is honoured.
    layout = layouts.layout(_SUPPRESSING_RECORD)
    field = layout.field("SEC-USR-PWD")
    image = pathlib.Path(seed_corpus.ebcdic_path(_SUPPRESSING_EXTRACT)).read_bytes()
    record = image[: layout.reclen].decode("cp037")
    withheld = record[field.start : field.end]
    assert withheld.strip()

    reader = _reader(_SUPPRESSING_RECORD)
    assert withheld not in reader.render_masked_record(record)
    assert withheld not in reader.render_masked_field(record, "SEC-USR-PWD")


def test_a_withheld_span_still_holds_its_place_in_the_record_geometry(
    seed_corpus: SeedCorpus,
) -> None:
    """Prove withholding a field moved no other field's offset.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a shifted field is reported as a failure.
    """
    # WHY : Assumptions: this is the property that distinguishes withholding a field from
    #   deleting it, and it is worth its own test because the failure mode is silent. A layout
    #   that dropped the eight-byte span instead of declaring it would move the two fields after
    #   it left by eight bytes, and both are character fields, so every record would still
    #   decode -- into a user type read out of the middle of the pad.
    layout = layouts.layout(_SUPPRESSING_RECORD)
    assert sum(field.length for field in layout.fields) == layout.reclen
    following = layout.field("SEC-USR-TYPE")
    withheld = layout.field("SEC-USR-PWD")
    assert following.start == withheld.end

    reader = _reader(_SUPPRESSING_RECORD)
    records = list(reader.read_ebcdic(seed_corpus.ebcdic_path(_SUPPRESSING_EXTRACT)))
    # Every shipped row's user type is one of the two the baseline admits, which it could only
    # be if the span after the withheld field is still being read at the declared offset.
    assert {record["SEC-USR-TYPE"] for record in records} <= {"A", "U"}


def test_the_layout_driven_reader_refuses_a_record_of_the_wrong_width() -> None:
    """Prove the width guard still fires for a record that withholds a field.

    :returns: nothing; an accepted short image is reported as a failure.
    """
    # WHY : Trade-offs: the byte path is used because the character path is refused outright for
    #   this record, and the width guard has to be reachable on the path that IS open. A short
    #   image is the one corruption that a per-field decode would otherwise discover only when
    #   it ran off the end, several fields in.
    reader = _reader(_SUPPRESSING_RECORD)
    layout = layouts.layout(_SUPPRESSING_RECORD)
    with pytest.raises(RecordLengthError):
        reader.decode_ebcdic(b"\x40" * (layout.reclen - 1))
