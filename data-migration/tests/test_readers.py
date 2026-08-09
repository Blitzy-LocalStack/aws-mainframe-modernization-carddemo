"""Exercise all twelve record readers against the corpora the migration actually loads.

Purpose
-------
Execute :mod:`carddemo_migration.readers` rather than merely reading it. Until this module
existed, nothing in the distribution imported a reader at all: the decode path that turns every
committed extract into rows had no caller, so a defect in it could only surface during a load.
Each test below pins one property of the published reader contract, and the whole module is
also the inventory assertion -- it imports every one of the twelve modules the AAP names, so a
missing reader is an import error at collection rather than a discovery somebody makes later.

Assumptions: the corpora are the SHIPPED ones under ``app/data`` and ``tests/fixtures``, reached
through the ``seed_corpus`` and ``fixture_corpus`` accessors, and no record is synthesised except
where a test needs a value the corpus does not contain -- a non-digit body, an unknown record
type. A synthetic corpus would only prove the readers agree with whatever this file encoded;
the committed extracts were produced by the reference compiler and nothing in this package
chose their bytes.

Assumptions: no account identifier, primary account number or other sensitive value is written
into this file as a literal. Where a test needs one it reads it out of the corpus at run time,
and one test asserts that property of this module's own source, so the rule is enforced here
rather than merely observed.

Trade-offs: the reader table below drives most tests through one parametrisation instead of
eleven near-identical test bodies. The cost is that a failure names a case identifier rather
than a bespoke function; what it buys is that a property proven for one flat reader is proven
for all of them, which is the only way a shared contract stays shared as readers are added.
"""

from __future__ import annotations

import base64
import importlib
import os
import pathlib
import re
from decimal import Decimal
from typing import TYPE_CHECKING, Final, NamedTuple

import pytest

from carddemo_migration.copybook import ebcdic_codec, layouts
from carddemo_migration.copybook.layouts import Kind, LayoutError, RecordSpec
from carddemo_migration.readers import (
    account,
    card,
    customer,
    dalytran,
    discgrp,
    export_record,
    factory,
    tcatbal,
    trancatg,
    transaction,
    trantype,
    usrsec,
    xref,
)

if TYPE_CHECKING:
    from collections.abc import Callable, Iterator, Mapping
    from types import ModuleType

    # WHY : Assumptions: the two corpus classes are imported for annotation only, under the
    #   type-checking guard, exactly as ``test_zoned`` does. pytest injects both objects as
    #   fixtures, so the names are needed to document the parameters and for nothing else;
    #   importing them unconditionally would tie collection of this file to pytest's
    #   path-insertion order.
    from conftest import FixtureCorpus, SeedCorpus


class FlatReaderCase(NamedTuple):
    """One flat reader and the corpora it reads, for the shared parametrisation.

    Purpose
    -------
    Name everything a test needs in order to exercise one reader without restating any of it:
    the module, the descriptor it reads through, the two naming stems its entry points are built
    from, and which committed datasets carry that record in each encoding.

    Assumptions: ``singular`` and ``plural`` are the stems the readers' own entry-point names
    use -- ``read_ascii_<plural>`` and ``render_masked_<singular>_record`` -- so resolving an
    entry point is a ``getattr`` that FAILS when a reader does not publish it. That is
    deliberate: the inventory assertion and the behaviour tests then share one mechanism, and a
    reader cannot pass the behaviour tests while quietly omitting an entry point.

    Assumptions: ``character_path`` records whether the reader publishes the ``decode_ascii_*`` /
    ``iter_ascii_*`` / ``read_ascii_*`` trio at all, as DATA rather than as a module-name
    comparison inside each test. Ten of the eleven do. The security-user reader deliberately does
    not: its record is the credential-bearing one and every character form of it is a transcode of
    a plaintext password written somewhere outside the single REFERENCE-only dataset, so the trio
    was removed rather than kept for callers holding such text. Expressing that as a flag means a
    test asserts the ABSENCE where it matters instead of silently skipping a reader.
    """

    module_name: str
    module: ModuleType
    layout: RecordSpec
    singular: str
    plural: str
    ascii_dataset: str | None
    ebcdic_dataset: str | None
    character_path: bool = True


# WHY : Assumptions: the table names the ELEVEN flat readers. The export reader is absent
#   because its record has no character form at all -- its payload carries packed and binary
#   spans -- so it publishes no text entry points and cannot satisfy a parametrisation that
#   assumes six. It is exercised by its own tests further down, against the same properties.
# WHY : Assumptions: the dataset pairings are the ones the corpus accessor already resolves,
#   and nine of the eleven records ship in BOTH encodings. The two that do not are stated as
#   ``None`` rather than omitted, so the parity test skips them explicitly and a reader that
#   later acquires a second corpus is a one-line change here.
_FLAT_READERS: Final[tuple[FlatReaderCase, ...]] = (
    FlatReaderCase(
        "account",
        account,
        layouts.ACCOUNT_LAYOUT,
        "account",
        "accounts",
        "acctdata.txt",
        "AWS.M2.CARDDEMO.ACCTDATA.PS",
    ),
    FlatReaderCase(
        "card",
        card,
        layouts.CARD_LAYOUT,
        "card",
        "cards",
        "carddata.txt",
        "AWS.M2.CARDDEMO.CARDDATA.PS",
    ),
    FlatReaderCase(
        "customer",
        customer,
        layouts.CUSTOMER_LAYOUT,
        "customer",
        "customers",
        "custdata.txt",
        "AWS.M2.CARDDEMO.CUSTDATA.PS",
    ),
    FlatReaderCase(
        "xref",
        xref,
        layouts.XREF_LAYOUT,
        "card_xref",
        "card_xrefs",
        "cardxref.txt",
        "AWS.M2.CARDDEMO.CARDXREF.PS",
    ),
    FlatReaderCase(
        "transaction",
        transaction,
        layouts.TRAN_LAYOUT,
        "transaction",
        "transactions",
        None,
        None,
    ),
    FlatReaderCase(
        "dalytran",
        dalytran,
        layouts.DALYTRAN_LAYOUT,
        "daily_transaction",
        "daily_transactions",
        "dailytran.txt",
        "AWS.M2.CARDDEMO.DALYTRAN.PS",
    ),
    FlatReaderCase(
        "tcatbal",
        tcatbal,
        layouts.TCATBAL_LAYOUT,
        "category_balance",
        "category_balances",
        "tcatbal.txt",
        "AWS.M2.CARDDEMO.TCATBALF.PS",
    ),
    FlatReaderCase(
        "discgrp",
        discgrp,
        layouts.DISGROUP_LAYOUT,
        "disclosure_group",
        "disclosure_groups",
        "discgrp.txt",
        "AWS.M2.CARDDEMO.DISCGRP.PS",
    ),
    FlatReaderCase(
        "trantype",
        trantype,
        layouts.TRANTYPE_LAYOUT,
        "transaction_type",
        "transaction_types",
        "trantype.txt",
        "AWS.M2.CARDDEMO.TRANTYPE.PS",
    ),
    FlatReaderCase(
        "trancatg",
        trancatg,
        layouts.TRANCAT_LAYOUT,
        "transaction_category",
        "transaction_categories",
        "trancatg.txt",
        "AWS.M2.CARDDEMO.TRANCATG.PS",
    ),
    FlatReaderCase(
        "usrsec",
        usrsec,
        layouts.SECUSER_LAYOUT,
        "security_user",
        "security_users",
        None,
        "AWS.M2.CARDDEMO.USRSEC.PS",
        character_path=False,
    ),
)

# WHY : Assumptions: the twelve module names the AAP requires are listed independently of the
#   table above, so the inventory assertion is a statement about the PLAN rather than about
#   this file's own contents. A reader dropped from the table would still have to be missing
#   from the package for the inventory test to fail, which is the property that matters.
_REQUIRED_READER_MODULES: Final[tuple[str, ...]] = (
    "account",
    "card",
    "customer",
    "xref",
    "transaction",
    "dalytran",
    "tcatbal",
    "discgrp",
    "trantype",
    "trancatg",
    "usrsec",
    "export_record",
)

# WHY : Assumptions: the two corpus divergences are named here as DATA rather than tolerated as
#   "some difference", because tolerating any difference would let a real decode defect hide
#   behind an expected one. Both are recorded in ``data-migration/README.md`` section 15: the
#   account seed's record 49 disagrees on its ZIP field, and the disclosure-group seed's record
#   34 -- the mandatory DEFAULT fallback row -- disagrees on its interest rate, where the EBCDIC
#   extract is authoritative. The indices below are zero-based, so they read one lower.
_DOCUMENTED_DIVERGENCES: Final[Mapping[str, Mapping[int, frozenset[str]]]] = {
    "account": {48: frozenset({"ACCT-ADDR-ZIP"})},
    "discgrp": {33: frozenset({"DIS-INT-RATE"})},
}

# WHY : Assumptions: every reader that withholds a field is listed here with the field it
#   withholds, so the suppression contract is asserted from one place for all of them rather than
#   in whichever reader's own test happened to remember it. A value excluded by one reader and
#   emitted by another is not excluded at all.
# WHY : Refactoring Rationale: the card reader is NOT in this table, and it was. Its verification
#   value is PROTECTED rather than suppressed: `card.cards.cvv_encrypted` is a column the
#   migration contract requires populated, and a reader that never decodes the field cannot
#   populate it -- so suppression at the reader would have delivered a card table whose
#   verification values were absent rather than protected, which nothing downstream can repair.
#   The field is instead decoded into `readers.card.ProtectedValue`, whose every rendering route
#   yields a constant marker, and it is classified sensitive so the masked renderings redact its
#   span. The disclosure property is therefore asserted by
#   `data-migration/tests/test_card_protected_value.py`, which covers both encodings, rather than
#   here -- this table is about fields NO representation of which exists.
# WHY : Assumptions: the security record's password and the export branch's verification value
#   stay here, and the difference from the card case is that the target has no column for either.
#   `auth.users` declares no password column at all, which is the migration's one deliberate
#   refusal of parity, and the export branch is a diagnostic projection rather than a load source.
#   A field with no destination is a field with no reason to be decoded.
_SUPPRESSING_READERS: Final[tuple[tuple[str, str], ...]] = (
    ("usrsec", "SEC-USR-PWD"),
    ("export_record", "EXP-CARD-CVV-CD"),
)

# WHY : Assumptions: a run of eleven or more digits is the shape of every identifier this
#   corpus treats as sensitive -- an eleven-digit account identifier and a sixteen-digit card
#   number -- so one pattern covers both. Eleven is the lower bound rather than sixteen because
#   the account identifier is the shorter of the two and a rule that missed it would miss the
#   finding that prompted this check.
_IDENTIFIER_RUN = re.compile(r"\d{11,}")


def _entry(case: FlatReaderCase, verb: str, encoding: str) -> Callable[..., object]:
    """Resolve one of a flat reader's six decode entry points.

    Parameters
    ----------
    case : FlatReaderCase
        The reader under test.
    verb : str
        ``decode``, ``iter`` or ``read``.
    encoding : str
        ``ascii`` or ``ebcdic``.

    Returns
    -------
    Callable[..., object]
        The published callable.

    Raises
    ------
    AttributeError
        If the reader does not publish that entry point, which is itself the assertion that
        every reader implements the shared contract.
    """
    # WHY : Assumptions: the stem is SINGULAR for a decode and PLURAL for an iterate or a read,
    #   because that is the convention every reader follows and it is not cosmetic: the
    #   plurality of the name matches the plurality of the result, so ``decode_ascii_account``
    #   takes one record and ``read_ascii_accounts`` produces many. Resolving the name from the
    #   verb rather than from one stem is what lets this helper assert the convention instead of
    #   working around it.
    stem = case.singular if verb == "decode" else case.plural
    return getattr(case.module, f"{verb}_{encoding}_{stem}")


def _rendering(case: FlatReaderCase, scope: str) -> Callable[..., str]:
    """Resolve one of a flat reader's two privacy-safe renderings.

    Parameters
    ----------
    case : FlatReaderCase
        The reader under test.
    scope : str
        ``record`` or ``field``.

    Returns
    -------
    Callable[..., str]
        The published callable.

    Raises
    ------
    AttributeError
        If the reader does not publish that rendering.
    """
    return getattr(case.module, f"render_masked_{case.singular}_{scope}")


def _character_records(
    case: FlatReaderCase,
    seed_corpus: SeedCorpus,
    fixture_corpus: FixtureCorpus,
) -> tuple[str, ...]:
    """Return this record's committed images as characters at the declared width.

    Purpose
    -------
    Supply the whole-record character images the two masked renderings take, from whichever
    corpus carries the record, so the disclosure tests cover every reader rather than only the
    nine with an ASCII seed.

    Parameters
    ----------
    case : FlatReaderCase
        The reader under test.
    seed_corpus : SeedCorpus
        Accessor over ``app/data``.
    fixture_corpus : FixtureCorpus
        Accessor over ``tests/fixtures``.

    Returns
    -------
    tuple[str, ...]
        Each record as characters, padded to the declared width.

    Raises
    ------
    None
    """
    if case.ascii_dataset is not None:
        return seed_corpus.ascii_records(case.ascii_dataset)
    if case.module_name == "transaction":
        # WHY : Assumptions: no TRANSACT seed ships, so the posted-transaction record's only
        #   committed images are the export scenario's five fixture rows. That is stated in the
        #   reader's own docstring and is why the fixture corpus is reached here at all.
        return fixture_corpus.records("export/happy_path", "trandata.txt")

    # WHY : Trade-offs: the security record's only corpus is EBCDIC, so its character images are
    #   produced by decoding whole records through the code page -- the one place this file does
    #   that, and safe here for a reason specific to this record: every field it declares is
    #   character data, so there is no packed nibble, sign overpunch or binary span for a
    #   whole-record decode to destroy. Any other record would have to be decoded per field,
    #   which is what the readers themselves do and what the codec exists to enforce.
    return tuple(
        image.decode("cp037") for image in seed_corpus.ebcdic_records(case.ebcdic_dataset or "")
    )


def _decoded_records(
    case: FlatReaderCase,
    seed_corpus: SeedCorpus,
    fixture_corpus: FixtureCorpus,
) -> tuple[Mapping[str, object], ...]:
    """Decode this record's committed images through the reader under test.

    Purpose
    -------
    Give a test the decoded rows for any reader, from whichever corpus and whichever encoding
    that record ships in, so a property can be asserted for all eleven flat readers uniformly.

    Parameters
    ----------
    case : FlatReaderCase
        The reader under test.
    seed_corpus : SeedCorpus
        Accessor over ``app/data``.
    fixture_corpus : FixtureCorpus
        Accessor over ``tests/fixtures``.

    Returns
    -------
    tuple[Mapping[str, object], ...]
        The decoded rows in file order.

    Raises
    ------
    None
    """
    if case.ebcdic_dataset is not None:
        reader: Callable[..., Iterator[Mapping[str, object]]] = _entry(case, "read", "ebcdic")
        return tuple(reader(seed_corpus.ebcdic_path(case.ebcdic_dataset)))
    decode = _entry(case, "decode", "ascii")
    return tuple(
        decode(image)  # type: ignore[arg-type]
        for image in _character_records(case, seed_corpus, fixture_corpus)
    )


_FLAT_IDS: Final[tuple[str, ...]] = tuple(case.module_name for case in _FLAT_READERS)

# WHY : Assumptions: ONE of the eleven flat readers publishes no character decode path at all,
#   and it is named here rather than tested for by `hasattr`, so a reader that lost its character
#   trio by accident fails instead of being quietly excused. `usrsec` is that reader: the baseline
#   ships `USRSEC` in EBCDIC only -- there is no `app/data/ASCII/usrsec.txt` among the nine ASCII
#   seeds -- and its 80-byte record carries `SEC-USR-PWD PIC X(08)` in CLEAR, so a whole-record
#   character form of it is a Python `str` holding a live credential. The reader's byte path
#   decodes field by field over `LOADED_FIELDS` and never slices that span, which is the property
#   the character trio could not offer, so the trio was withdrawn rather than tolerated.
_BYTE_ONLY_READERS: Final[frozenset[str]] = frozenset({"usrsec"})

# WHY : Assumptions: the three withdrawn names are listed explicitly so their ABSENCE is asserted
#   rather than assumed. A reader re-acquiring any one of them would restore a whole-record
#   credential surface, and nothing else in this suite would notice.
_WITHDRAWN_CHARACTER_ENTRY_POINTS: Final[tuple[str, ...]] = (
    "decode_ascii_security_user",
    "iter_ascii_security_users",
    "read_ascii_security_users",
)


def test_every_reader_module_the_plan_requires_is_importable() -> None:
    """Prove all twelve reader modules exist and are reachable as package attributes."""
    # WHY : Refactoring Rationale: this is the direct assertion for the gap that prompted this
    #   file. Nine of the twelve readers were absent and nothing imported the other three, so
    #   the plan's reader inventory was satisfied by no executable statement anywhere. This test
    #   states the requirement explicitly, so the failure names the inventory rather than an
    #   import somewhere near the top of a file.
    # WHY : Alternatives Considered: each module is imported BY NAME here rather than read off
    #   the package as an attribute. The package now resolves a reader lazily on attribute access,
    #   so reading an attribute WOULD work -- and it would assert less: an attribute lookup finds a
    #   module this file has already imported at its top, so the assertion would pass because of
    #   its own imports rather than because the modules exist. Importing by name reaches the
    #   filesystem every time the interpreter has not already cached the module, which is the
    #   property being asserted. The registry-to-directory agreement that the lazy surface makes
    #   checkable is asserted separately, in test_package_surfaces.py.
    for name in _REQUIRED_READER_MODULES:
        module = importlib.import_module(f"carddemo_migration.readers.{name}")
        assert module.__doc__, f"reader module {name!r} carries no module docstring"


def test_the_reader_tree_is_a_regular_package() -> None:
    """Confirm ``readers`` ships an ``__init__`` rather than being an implicit namespace."""
    # WHY : Assumptions: the property asserted is the FILE, not merely that the import worked,
    #   because an implicit namespace package imports perfectly well and is exactly what this
    #   check exists to refuse: setuptools finds a regular package by its ``__init__.py`` and a
    #   namespace child only by falling back, so the difference decides whether the modules
    #   reach an installed wheel at all.
    from carddemo_migration import readers

    assert readers.__file__ is not None
    assert readers.__file__.endswith("__init__.py")
    assert readers.__doc__


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_every_flat_reader_publishes_the_shared_entry_points(case: FlatReaderCase) -> None:
    """Resolve all six decode entry points, both renderings and the key accessor.

    :param case: the flat reader under test, from the module-level table.
    :returns: nothing; a reader missing any published name is reported as an ``AttributeError``.
    """
    # WHY : Assumptions: the encodings a reader must publish are derived from
    #   `_BYTE_ONLY_READERS` rather than fixed at both, and the byte-only reader's character names
    #   are then asserted ABSENT. Loosening this to "publishes at least the byte trio" would let
    #   any reader drop its character path silently; naming the one exception keeps the shared
    #   contract at six entry points for the other ten and turns a re-added character entry point
    #   on the security-user record into a failure rather than an unnoticed API widening.
    encodings = ("ebcdic",) if case.module_name in _BYTE_ONLY_READERS else ("ascii", "ebcdic")
    for verb in ("decode", "iter", "read"):
        for encoding in encodings:
            assert callable(_entry(case, verb, encoding))
    if case.module_name in _BYTE_ONLY_READERS:
        published = set(case.module.__all__)
        for withdrawn in _WITHDRAWN_CHARACTER_ENTRY_POINTS:
            assert not hasattr(case.module, withdrawn), (
                f"{case.module_name} re-published {withdrawn}, which takes a whole record as"
                " characters and therefore takes the baseline's cleartext password with it"
            )
            assert withdrawn not in published
    for scope in ("record", "field"):
        assert callable(_rendering(case, scope))

    # WHY : Assumptions: the key accessor is named for the SHAPE of the key rather than
    #   uniformly, and the one exception is asserted rather than excused. Ten records key on a
    #   single leading field and publish ``record_key``; the transaction-category-balance record
    #   keys on three fields at once and publishes ``composite_key`` alongside the component
    #   names, because a caller keying on it needs to know it is not one field. Accepting either
    #   name keeps this test a statement about every reader having a key accessor, which is the
    #   property a loader depends on, instead of a statement about spelling.
    if case.module_name == "tcatbal":
        assert callable(case.module.composite_key)
        assert case.module.COMPOSITE_KEY_FIELD_NAMES
    else:
        assert callable(case.module.record_key)
    assert isinstance(case.module.LOADED_FIELDS, tuple)
    assert isinstance(case.module.DROPPED_FIELD_NAMES, frozenset)


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_the_key_accessor_returns_the_descriptor_declared_span(
    case: FlatReaderCase,
    seed_corpus: SeedCorpus,
    fixture_corpus: FixtureCorpus,
) -> None:
    """Slice a real record's key and require it to equal the descriptor's declared span.

    :param case: the flat reader under test, from the module-level table.
    :param seed_corpus: session accessor over ``app/data``.
    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a key of the wrong span, or a short record accepted, is a failure.
    """
    accessor = (
        case.module.composite_key if case.module_name == "tcatbal" else case.module.record_key
    )
    start = case.layout.key_offset
    end = start + case.layout.key_length
    for image in _character_records(case, seed_corpus, fixture_corpus):
        # WHY : Assumptions: the expectation is computed from the DESCRIPTOR here too, so this
        #   test proves the accessor and the layout agree rather than proving the accessor agrees
        #   with a span this file chose. A key sliced one character short does not raise -- it
        #   collides with a sibling record -- so agreement is the only observable property.
        assert accessor(image) == image[start:end]
        assert len(accessor(image)) == case.layout.key_length

    # WHY : Assumptions: a short record must be REFUSED rather than yielding a short key, because
    #   a truncated key is indistinguishable from a legitimate one at the point a loader upserts
    #   on it.
    with pytest.raises(layouts.RecordLengthError):
        accessor("0" * (case.layout.reclen - 1))

    # WHY : Refactoring Rationale: the OVER-LONG case is asserted too, and its absence was a real
    #   gap rather than an omission of symmetry. Every one of these accessors tested only for a
    #   record SHORTER than the declared width, so each accepted a record longer than its own
    #   decoders will parse -- and an over-long record is the more dangerous of the two, because the
    #   key sliced from it comes from the right offsets of the WRONG record, two rows concatenated
    #   most plausibly, so it looks entirely well formed and a loader upserts on it.
    with pytest.raises(layouts.RecordLengthError):
        accessor("0" * (case.layout.reclen + 1))


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_no_reader_restates_a_byte_position_of_its_own(case: FlatReaderCase) -> None:
    """Prove each published field IS a field of the registered descriptor, not a copy of one.

    :param case: the flat reader under test, from the module-level table.
    :returns: nothing; a reader holding its own field object is reported as a failure.
    """
    # WHY : Alternatives Considered: identity is asserted rather than equality. A reader that
    #   re-declared a field with the same name, offset and length would compare equal and would
    #   still be a second statement of the geometry -- free to drift from the copybook at the
    #   next edit. Identity can only hold if the reader filtered the descriptor's own tuple,
    #   which is the single-sourcing property the plan requires and the one whose breakage is
    #   undetectable: a record read one byte out of alignment still decodes to plausible values.
    assert case.layout is layouts.layout(case.layout.name)
    declared = {id(field) for field in case.layout.fields}
    for field in case.module.LOADED_FIELDS:
        assert id(field) in declared, f"{case.module_name} publishes a field the layout does not"


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_the_trailing_pad_is_dropped_and_the_drop_is_recorded(
    case: FlatReaderCase,
    seed_corpus: SeedCorpus,
    fixture_corpus: FixtureCorpus,
) -> None:
    """Confirm every reader drops its pad, records the drop, and never returns it.

    :param case: the flat reader under test, from the module-level table.
    :param seed_corpus: session accessor over ``app/data``.
    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a pad reaching a decoded record is reported as a failure.
    """
    dropped = case.module.DROPPED_FIELD_NAMES
    assert dropped, f"{case.module_name} records no dropped field"
    published = {field.name for field in case.module.LOADED_FIELDS}
    assert published.isdisjoint(dropped)
    declared = {field.name for field in case.layout.fields}
    assert dropped <= declared
    for row in _decoded_records(case, seed_corpus, fixture_corpus):
        assert dropped.isdisjoint(row)


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_the_two_encodings_decode_the_shipped_seeds_identically(
    case: FlatReaderCase,
    seed_corpus: SeedCorpus,
) -> None:
    """Compare both encodings row for row, pinning the two divergences the README records.

    :param case: the flat reader under test, from the module-level table.
    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; any difference outside the documented pair is reported as a failure.
    """
    if case.ascii_dataset is None or case.ebcdic_dataset is None:
        pytest.skip(f"{case.module_name} ships in one encoding only")

    from_text = tuple(_entry(case, "read", "ascii")(seed_corpus.ascii_path(case.ascii_dataset)))
    from_bytes = tuple(_entry(case, "read", "ebcdic")(seed_corpus.ebcdic_path(case.ebcdic_dataset)))
    assert len(from_text) == len(from_bytes)

    expected = _DOCUMENTED_DIVERGENCES.get(case.module_name, {})
    for index, (text_row, byte_row) in enumerate(zip(from_text, from_bytes, strict=True)):
        differing = frozenset(name for name in text_row if text_row[name] != byte_row[name])
        # WHY : Assumptions: the comparison is EXACT and the two known divergences are asserted
        #   as equalities rather than skipped. A reader defect that happened to land on record 49
        #   or 34 would otherwise be absorbed by an allowance meant for a corpus fact.
        assert differing == expected.get(index, frozenset()), (
            f"{case.module_name} record {index} differs between encodings in {sorted(differing)}"
        )


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_an_empty_byte_source_yields_no_records(case: FlatReaderCase) -> None:
    """Confirm a dataset with no bytes is an empty result rather than an error.

    :param case: the flat reader under test, from the module-level table.
    :returns: nothing; a reader that raises or invents a record is reported as a failure.
    """
    assert list(_entry(case, "iter", "ebcdic")(b"")) == []  # type: ignore[call-arg]


def test_a_committed_empty_extract_yields_no_records(fixture_corpus: FixtureCorpus) -> None:
    """Read the shipped zero-byte fixtures and confirm each yields nothing.

    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a reader producing a record from an empty file is reported as a failure.
    """
    # WHY : Alternatives Considered: the empty case is proven against COMMITTED zero-byte files
    #   as well as against an empty byte string, because the two exercise different code: an
    #   empty string reaches the in-memory branch, whereas a real file reaches the open, the
    #   size check and the close. The provisioning and posting scenarios ship four and one such
    #   files respectively, which is why they exist.
    empty_cases = (
        (account, "read_ascii_accounts", "provisioning/empty_input", "acctdata.txt"),
        (card, "read_ascii_cards", "provisioning/empty_input", "carddata.txt"),
        (customer, "read_ascii_customers", "provisioning/empty_input", "custdata.txt"),
        (xref, "read_ascii_card_xrefs", "provisioning/empty_input", "cardxref.txt"),
        (
            dalytran,
            "read_ascii_daily_transactions",
            "posting/empty_input",
            "dailytran.txt",
        ),
    )
    for module, entry, scenario, dataset in empty_cases:
        path = fixture_corpus.path(scenario, dataset)
        assert path.stat().st_size == 0
        assert list(getattr(module, entry)(path)) == []


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_a_dataset_that_does_not_divide_into_whole_records_is_refused(
    case: FlatReaderCase,
    seed_corpus: SeedCorpus,
) -> None:
    """Truncate a real extract and confirm the reader refuses rather than decoding partially.

    :param case: the flat reader under test, from the module-level table.
    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a reader that accepts a ragged dataset is reported as a failure.
    """
    if case.ebcdic_dataset is None:
        pytest.skip(f"{case.module_name} has no committed fixed-length extract")

    image = seed_corpus.ebcdic_raw_bytes(case.ebcdic_dataset)
    # WHY : Assumptions: one byte is removed rather than a whole record, because a whole-record
    #   truncation still divides evenly and is indistinguishable from a shorter dataset. The
    #   failure this guards is a final partial record being right-padded or silently dropped,
    #   which only a non-dividing length can expose.
    with pytest.raises(layouts.RecordLengthError):
        list(_entry(case, "iter", "ebcdic")(image[:-1]))  # type: ignore[call-arg]


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_character_data_is_refused_by_the_byte_entry_point(case: FlatReaderCase) -> None:
    """Confirm passing an already-decoded string to the byte path raises rather than decoding.

    :param case: the flat reader under test, from the module-level table.
    :returns: nothing; a reader accepting characters where bytes are required is a failure.
    """
    # WHY : Assumptions: this is the mistake the corpus punishes hardest and the reason the two
    #   record-boundary modes take different types. A record decoded to characters has already
    #   lost the byte values a sign overpunch and a packed nibble are made of, so a byte path
    #   that accepted text would decode plausible wrong numbers rather than failing.
    with pytest.raises(TypeError):
        list(_entry(case, "iter", "ebcdic")("0" * case.layout.reclen))  # type: ignore[call-arg]


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_a_character_outside_the_single_byte_range_is_refused_naming_its_field(
    case: FlatReaderCase,
) -> None:
    """Require every reader to refuse a multi-byte character that keeps the declared width.

    :param case: the flat reader under test, from the module-level table.
    :returns: nothing; a reader that decodes a multi-byte character is reported as a failure.
    """
    # WHY : Refactoring Rationale: all eleven readers carry this refusal and its
    #   ``_field_containing`` helper, and until this test not one of them entered it -- every
    #   committed ASCII seed is single-byte, so the branch was live code no test reached. That is
    #   the same shape as the defect this module already pins for the digit proof, where two
    #   paths of ONE reader enforced different contracts because only one of them was exercised.
    #   Asserting it across the whole table is what keeps the contract from drifting per reader.
    # WHY : Trade-offs: the character is substituted INTO the record rather than appended, so the
    #   image keeps its declared character width and the width check cannot be what refuses it.
    #   Appending would only re-prove that an over-long row is rejected -- which a separate test
    #   already covers -- and would leave this branch unentered, which is the whole point here.
    # WHY : Assumptions: the byte-only reader is skipped here rather than exempted with an
    #   alternative assertion, because this refusal is a property of a CHARACTER decode path and
    #   that reader publishes none: its equivalent guard is the codec's decode-and-re-encode proof
    #   on the byte path, which `test_a_span_that_does_not_round_trip_is_refused` covers. The skip
    #   names the reason so it cannot be read as an untested reader.
    if not case.character_path:
        pytest.skip(f"{case.module_name} publishes no character decode path to corrupt")
    offset = 5
    record = "0" * case.layout.reclen
    corrupted = record[:offset] + "\u00e9" + record[offset + 1 :]
    assert len(corrupted) == case.layout.reclen, "the corruption must not change the width"
    with pytest.raises(layouts.RecordLengthError) as failure:
        _entry(case, "decode", "ascii")(corrupted)
    message = str(failure.value)
    assert case.layout.name in message
    assert str(offset) in message
    # The fault is located by the CONTAINING field's declared geometry, so a reader of the log
    # can tell which column moved without being shown any of the record.
    containing = next(field for field in case.layout.fields if field.start <= offset < field.end)
    assert containing.describe() in message
    # WHY : Assumptions: the offending character itself must not reach the message. A diagnostic
    #   that quoted it would emit record content from a span that may sit anywhere in the record,
    #   including over an account identifier, which is the disclosure rule the readers hold to
    #   everywhere else -- so it has to hold here, on the path that reports a corruption.
    assert "\u00e9" not in message


def test_the_shipped_initialiser_record_is_refused_with_a_geometry_naming_message(
    seed_corpus: SeedCorpus,
) -> None:
    """Refuse the committed low-values initialiser record, naming the field that failed.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; acceptance of the initialiser record is reported as a failure.
    """
    # WHY : Alternatives Considered: the malformed case is a REAL committed record rather than a
    #   synthesised one. ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` is a single 350-byte image whose
    #   category-code span is four NUL bytes -- a dataset primer, not data -- so it is exactly
    #   the shape a reader must refuse, and it was shipped by the baseline rather than invented
    #   here. A synthetic record would only prove the reader rejects what this file corrupted.
    path = seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.DALYTRAN.PS.INIT")
    with pytest.raises(Exception) as failure:
        list(dalytran.read_ebcdic_daily_transactions(path))
    message = str(failure.value)
    assert layouts.DALYTRAN_LAYOUT.field("DALYTRAN-CAT-CD").describe() in message


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_money_decodes_to_an_exact_decimal_at_the_declared_scale(
    case: FlatReaderCase,
    seed_corpus: SeedCorpus,
    fixture_corpus: FixtureCorpus,
) -> None:
    """Confirm every numeric value is a ``Decimal`` at its declared scale and never a float.

    :param case: the flat reader under test, from the module-level table.
    :param seed_corpus: session accessor over ``app/data``.
    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a float, or a scale other than the declared one, is a failure.
    """
    scales = {
        field.name: field.dec_digits
        for field in case.module.LOADED_FIELDS
        if field.kind in (Kind.ZONED, Kind.PACKED, Kind.BINARY)
    }
    rows = _decoded_records(case, seed_corpus, fixture_corpus)
    for row in rows:
        for name, value in row.items():
            assert not isinstance(value, float), f"{case.module_name}.{name} decoded to a float"
            if name in scales:
                assert isinstance(value, Decimal)
                # WHY : Assumptions: the exponent is compared against the DESCRIPTOR's declared
                #   decimal places rather than against the literal -2. Not every numeric field
                #   here is money -- a credit score declares no decimals -- so a fixed
                #   expectation would either fail on those or stop proving anything about scale.
                assert value.as_tuple().exponent == -scales[name]


def test_the_daily_transaction_money_total_agrees_across_both_encodings(
    seed_corpus: SeedCorpus,
) -> None:
    """Sum one money column from both corpora and require the two totals to be equal."""
    # WHY : Alternatives Considered: a TOTAL is asserted in addition to the row-for-row equality
    #   proven above, because the two fail differently. Row equality catches a single wrong
    #   value; a total catches a systematic sign or scale error that flipped two values in
    #   opposite directions, which row equality would report as two unrelated differences and a
    #   reviewer might read as corpus drift.
    from_text = sum(
        row["DALYTRAN-AMT"]
        for row in dalytran.read_ascii_daily_transactions(seed_corpus.ascii_path("dailytran.txt"))
    )
    from_bytes = sum(
        row["DALYTRAN-AMT"]
        for row in dalytran.read_ebcdic_daily_transactions(
            seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.DALYTRAN.PS")
        )
    )
    assert isinstance(from_text, Decimal)
    assert from_text == from_bytes
    assert from_text.as_tuple().exponent == -2


@pytest.mark.parametrize("case", _FLAT_READERS, ids=_FLAT_IDS)
def test_no_sensitive_field_survives_a_masked_record_rendering(
    case: FlatReaderCase,
    seed_corpus: SeedCorpus,
    fixture_corpus: FixtureCorpus,
) -> None:
    """Confirm every sensitive span is replaced, and every other span left verbatim.

    :param case: the flat reader under test, from the module-level table.
    :param seed_corpus: session accessor over ``app/data``.
    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a sensitive span reaching a masked rendering unchanged is a failure.
    """
    render = _rendering(case, "record")
    # WHY : Assumptions: three of the eleven records declare NO sensitive field, and that is a
    #   property of the data rather than a gap: the transaction-type and transaction-category
    #   tables are reference code lists, and the disclosure-group rate is a published product
    #   term held against a group rather than against any cardholder. For those the meaningful
    #   assertion is the opposite one -- the rendering must return the record verbatim -- so the
    #   loop below covers both cases from one body instead of excusing three readers from the
    #   test, which is how a genuinely unclassified sensitive field would come to be missed.
    for image in _character_records(case, seed_corpus, fixture_corpus):
        masked = render(image)
        # WHY : Assumptions: the rendering must stay the DECLARED WIDTH, because that is the
        #   single property a whole-record rendering exists for -- an operator counts offsets
        #   across it. A redaction that changed a field's width would move every later field.
        assert len(masked) == case.layout.reclen
        for field in case.layout.fields:
            span = slice(field.start, field.end)
            if field.sensitive:
                # WHY : Assumptions: the comparison is on the SPAN and not on a substring
                #   search, and the difference matters. A one-character value's digit appears
                #   inside a sixteen-character hexadecimal tag by chance roughly every other
                #   record, so a substring test would report leaks that are not leaks; comparing
                #   the span asks the only question that matters -- were these bytes replaced.
                if image[span].strip():
                    assert masked[span] != image[span]
            else:
                assert masked[span] == image[span]


@pytest.mark.parametrize(("module_name", "field_name"), _SUPPRESSING_READERS)
def test_a_suppressed_field_is_absent_from_every_decoded_record(
    module_name: str,
    field_name: str,
    seed_corpus: SeedCorpus,
) -> None:
    """Confirm a suppressed field is never decoded and never rendered by name.

    :param module_name: the reader that withholds the field.
    :param field_name: the copybook name of the withheld field.
    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a suppressed value reaching a caller is reported as a failure.
    """
    module = importlib.import_module(f"carddemo_migration.readers.{module_name}")
    assert field_name in module.SUPPRESSED_FIELD_NAMES

    if module_name == "usrsec":
        rows = tuple(
            module.read_ebcdic_security_users(seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.USRSEC.PS"))
        )
        record = seed_corpus.ebcdic_records("AWS.M2.CARDDEMO.USRSEC.PS")[0].decode("cp037")
        rendering = module.render_masked_security_user_field
    else:
        rows = tuple(
            module.read_ebcdic_export_records(
                seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.EXPORT.DATA.PS")
            )
        )
        record = None
        rendering = None

    assert rows
    for row in rows:
        assert field_name not in row

    # WHY : Assumptions: asking for the field BY NAME must be refused rather than answered with
    #   a redaction, because a caller naming it is asking for its value and no representation of
    #   that value exists in this reader. Answering with a tag would let the field be reached
    #   through one door after being excluded from another.
    if rendering is None:
        image = seed_corpus.ebcdic_records("AWS.M2.CARDDEMO.EXPORT.DATA.PS")[0]
        with pytest.raises(LayoutError) as refusal:
            module.render_masked_export_field(image, field_name)
    else:
        with pytest.raises(LayoutError) as refusal:
            rendering(record, field_name)
    assert field_name in str(refusal.value)


def test_an_account_identifier_is_redacted_by_every_reader_that_carries_one(
    seed_corpus: SeedCorpus,
) -> None:
    """Confirm the account identifier is redacted in each of the four records that hold it.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a raw identifier surviving a masked rendering is reported as a failure.
    """
    # WHY : Refactoring Rationale: this pins the specific defect that prompted the disclosure
    #   work. Three readers published renderings documented as privacy-safe while the account
    #   identifier was not marked sensitive at all, so it passed through verbatim. The property
    #   is asserted for all four records that carry an account identifier rather than for the
    #   three that were reported, because the fix was a classification in the layouts module and
    #   a classification either holds everywhere or is not one.
    holders = (
        ("account", "acctdata.txt", "ACCT-ID", account.render_masked_account_record),
        ("card", "carddata.txt", "CARD-ACCT-ID", card.render_masked_card_record),
        ("xref", "cardxref.txt", "XREF-ACCT-ID", xref.render_masked_card_xref_record),
        (
            "tcatbal",
            "tcatbal.txt",
            "TRANCAT-ACCT-ID",
            tcatbal.render_masked_category_balance_record,
        ),
    )
    for module_name, dataset, field_name, render in holders:
        layout = layouts.layout(
            next(case.layout.name for case in _FLAT_READERS if case.module_name == module_name)
        )
        field = layout.field(field_name)
        assert field.sensitive, f"{field_name} is not classified sensitive"
        for image in seed_corpus.ascii_records(dataset):
            masked = render(image)
            assert masked[field.start : field.end] != image[field.start : field.end]


def test_an_unsigned_display_body_that_is_not_digits_is_refused_on_both_paths(
    seed_corpus: SeedCorpus,
) -> None:
    """Corrupt one unsigned-display field and require both encodings to refuse it alike.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; an encoding that accepts a non-digit body is reported as a failure.
    """
    # WHY : Refactoring Rationale: the two paths once disagreed here. The character path
    #   returned an unsigned-display field's characters without proving they were digits, so an
    #   alphabetic account identifier decoded cleanly from a text seed and raised from the
    #   equivalent byte image -- the same record accepted by one corpus and refused by the
    #   other. Asserting both refusals in ONE test is what keeps the two contracts tied
    #   together; two separate tests could drift apart exactly as the code did.
    field = layouts.CARD_LAYOUT.field("CARD-ACCT-ID")
    assert field.kind is Kind.UINT

    text_image = seed_corpus.ascii_records("carddata.txt")[0]
    corrupt_text = text_image[: field.start] + "A" * field.length + text_image[field.end :]
    with pytest.raises(Exception) as text_failure:
        card.decode_ascii_card(corrupt_text)

    byte_image = seed_corpus.ebcdic_records("AWS.M2.CARDDEMO.CARDDATA.PS")[0]
    corrupt_bytes = (
        byte_image[: field.start] + ("A" * field.length).encode("cp037") + byte_image[field.end :]
    )
    with pytest.raises(Exception) as byte_failure:
        card.decode_ebcdic_card(corrupt_bytes)

    assert field.describe() in str(text_failure.value)
    assert field.describe() in str(byte_failure.value)
    # WHY : Assumptions: the diagnostics must not carry the card number that sits beside the
    #   corrupted field. The value is read out of the corpus here rather than written as a
    #   literal, so this assertion holds without committing a card number to this file.
    pan = layouts.CARD_LAYOUT.field("CARD-NUM")
    assert text_image[pan.start : pan.end] not in str(text_failure.value)
    assert text_image[pan.start : pan.end] not in str(byte_failure.value)


def test_leading_zeroes_survive_an_unsigned_display_field(seed_corpus: SeedCorpus) -> None:
    """Confirm an unsigned-display identifier keeps its declared width and its leading zeroes.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a normalised or numerically-decoded identifier is reported as a failure.
    """
    # WHY : Assumptions: the digit proof added to the character path must VALIDATE without
    #   converting. An identifier returned as a number, or stripped of its leading zeroes, would
    #   no longer match the fixed-width key the target table joins on -- so the property that
    #   matters is that the characters come back exactly as stored.
    field = layouts.CARD_LAYOUT.field("CARD-ACCT-ID")
    image = seed_corpus.ascii_records("carddata.txt")[0]
    decoded = card.decode_ascii_card(image)["CARD-ACCT-ID"]
    assert decoded == image[field.start : field.end]
    assert len(decoded) == field.length
    assert decoded.startswith("0")


def test_a_bytes_valued_projection_is_refused_naming_only_geometry() -> None:
    """Confirm a retained field decoding to raw bytes fails closed without echoing the bytes."""
    # WHY : Refactoring Rationale: the projection once dropped a byte-valued field silently, so
    #   a descriptor that acquired a computational regime would have produced decoded records
    #   quietly missing a column -- a partial row that looks complete. Failing closed is what
    #   turns that into a visible defect. The refusal is asserted to name the field's geometry
    #   and NOT the bytes, because an unexpected span on this record sits beside an account
    #   identifier and a balance.
    field = layouts.TCATBAL_LAYOUT.field("TRANCAT-ACCT-ID")
    secret = b"\xc1\xc2\xc3"
    with pytest.raises(LayoutError) as refusal:
        tcatbal._project_decoded_fields({field.name: secret})
    message = str(refusal.value)
    assert field.describe() in message
    assert repr(secret) not in message
    assert secret.hex() not in message


def test_the_export_extract_decodes_to_one_shape_per_record_type(
    seed_corpus: SeedCorpus,
) -> None:
    """Decode the whole export extract and confirm each record type yields its own field set.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a record decoded against the wrong overlay is reported as a failure.
    """
    # WHY : Assumptions: the shape is asserted PER TYPE because all five payload overlays are
    #   the same 460 bytes. A record decoded against the wrong branch does not raise, keeps its
    #   declared width and returns a full set of well-formed meaningless values, so the only
    #   evidence that the dispatch works is that each type produced its own branch's fields.
    path = seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.EXPORT.DATA.PS")
    rows = tuple(export_record.read_ebcdic_export_records(path))
    assert len(rows) == len(seed_corpus.ebcdic_records("AWS.M2.CARDDEMO.EXPORT.DATA.PS"))

    envelope_names = tuple(field.name for field in export_record.ENVELOPE_LOADED_FIELDS)
    seen: dict[str, int] = {}
    for row in rows:
        record_type = row["EXPORT-REC-TYPE"]
        assert isinstance(record_type, str)
        seen[record_type] = seen.get(record_type, 0) + 1
        branch_names = tuple(
            field.name for field in export_record.branch_loaded_fields(record_type)
        )
        assert tuple(row) == envelope_names + branch_names
        assert "EXPORT-RECORD-DATA" not in row
        assert "FILLER" not in row
    assert set(seen) == set(layouts.EXPORT_RECORD_TYPES)


def test_the_export_key_orders_records_by_their_sequence_number(
    seed_corpus: SeedCorpus,
) -> None:
    """Confirm the raw key span orders records exactly as the decoded sequence number does.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a key whose byte order disagrees with its numeric order is a failure.
    """
    # WHY : Assumptions: this is the property that justifies returning the key as RAW BYTES. An
    #   unsigned big-endian integer of fixed width compares lexically in the same order as it
    #   compares numerically, so a caller may group or order images without decoding them. The
    #   claim is proven against the real extract rather than asserted in a docstring, because it
    #   would silently stop holding if the field were ever declared signed.
    images = seed_corpus.ebcdic_records("AWS.M2.CARDDEMO.EXPORT.DATA.PS")
    keys = [export_record.record_key(image) for image in images]
    numbers = [
        export_record.decode_ebcdic_export_record(image)["EXPORT-SEQUENCE-NUM"] for image in images
    ]
    assert keys == sorted(keys)
    assert numbers == sorted(numbers)
    assert all(len(key) == layouts.EXPORT_KEY_LENGTH for key in keys)
    # WHY : Assumptions: the discriminator is read through the published accessor as well as out
    #   of a decoded record, so the cheap partitioning path and the full decode are proven to
    #   agree. They are separate code paths and could report different types for one image.
    assert [export_record.record_type(image) for image in images] == [
        export_record.decode_ebcdic_export_record(image)["EXPORT-REC-TYPE"] for image in images
    ]


def test_the_export_verification_value_is_never_handed_to_a_decoder(
    seed_corpus: SeedCorpus,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Read the whole export extract and require the suppressed span to reach no codec.

    :param seed_corpus: session accessor over ``app/data``.
    :param monkeypatch: pytest fixture used to observe the per-field decode.
    :returns: nothing; a decode of the suppressed field is reported as a failure.
    """
    # WHY : Refactoring Rationale: the reader used to build its result from the published field
    #   tuple AFTER decoding every field the selected branch declares, so `EXP-CARD-CVV-CD` was
    #   absent from the returned mapping and present as a decoded value in the payload mapping the
    #   decode produced. Asserting only on the returned keys -- which an earlier test did -- could
    #   not tell the two apart, and that is precisely the gap: a traceback raised anywhere below the
    #   decode carried the value. This test observes the CODEC instead of the result, so it fails if
    #   the span is decoded at all, whatever happens to the value afterwards.
    # WHY : Trade-offs: the observation wraps the codec's private per-field entry point rather than
    #   a public hook, which couples this test to an internal name. That is accepted
    #   because it is the only place every field decode funnels through, and a public hook existing
    #   solely for a test would be a worse trade; if the name changes, this test fails loudly on the
    #   attribute rather than passing vacuously.
    decoded_names: list[str] = []
    original = ebcdic_codec._decode_one_field

    def _observed(image: bytes, field: object, code_page: str) -> object:
        """Record the field name, then delegate to the real per-field decoder.

        :param image: the whole record image being decoded.
        :param field: the field descriptor under decode.
        :param code_page: the character set in force.
        :returns: whatever the real decoder returns.
        """
        decoded_names.append(field.name)  # type: ignore[attr-defined]
        return original(image, field, code_page)

    monkeypatch.setattr(ebcdic_codec, "_decode_one_field", _observed)
    rows = list(
        export_record.read_ebcdic_export_records(
            seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.EXPORT.DATA.PS")
        )
    )

    assert rows, "the shipped export extract must not be empty"
    assert decoded_names, "the observation must have seen the decode it is asserting about"
    assert export_record.SUPPRESSED_FIELD_NAMES == frozenset({"EXP-CARD-CVV-CD"})
    for suppressed in export_record.SUPPRESSED_FIELD_NAMES:
        assert suppressed not in set(decoded_names)
        assert all(suppressed not in row for row in rows)


def test_an_unknown_export_record_type_is_refused(seed_corpus: SeedCorpus) -> None:
    """Replace the discriminator with an undeclared value and require a refusal.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a record decoded under an unknown discriminator is reported as a failure.
    """
    # WHY : Assumptions: refusing an unknown type is load-bearing rather than defensive. Every
    #   overlay is 460 bytes, so defaulting to a branch would decode the payload without raising
    #   and return values read out of the wrong fields entirely.
    image = seed_corpus.ebcdic_records("AWS.M2.CARDDEMO.EXPORT.DATA.PS")[0]
    unknown = "Q".encode("cp037") + image[1:]
    with pytest.raises(LayoutError) as refusal:
        export_record.decode_ebcdic_export_record(unknown)
    assert "Q" in str(refusal.value)


def test_a_dropped_or_foreign_export_field_has_no_masked_rendering(
    seed_corpus: SeedCorpus,
) -> None:
    """Require the export field renderer to refuse a dropped name and a name from another overlay.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a rendering produced for either name is reported as a failure.
    """
    # WHY : Refactoring Rationale: the suppression refusal is already pinned for this reader, but
    #   the DROPPED refusal was not, and the two guard different things. Suppression withholds a
    #   value that exists; dropping withholds a span that is not a publishable field at all --
    #   ``EXPORT-RECORD-DATA`` is the 460-byte opaque payload, so a renderer that fell through to
    #   it would emit the whole overlay raw, including the primary account number and the
    #   verification value the reader suppresses one branch away. That is the single worst
    #   disclosure this module can produce, and it was reachable code no test entered.
    image = seed_corpus.ebcdic_records("AWS.M2.CARDDEMO.EXPORT.DATA.PS")[0]
    for dropped in sorted(export_record.DROPPED_FIELD_NAMES):
        with pytest.raises(LayoutError) as refusal:
            export_record.render_masked_export_field(image, dropped)
        assert dropped in str(refusal.value)
    # WHY : Assumptions: the payload is redefined five ways over the same 460 bytes, so a field
    #   name belonging to a DIFFERENT record type is not merely absent -- asking for it would
    #   decode this record's bytes through the wrong overlay and return a plausible wrong value.
    #   The refusal therefore has to name the branch this record actually carries, and it does.
    carried = export_record.record_type(image)
    foreign = "EXP-TRAN-AMT" if carried != "T" else "EXP-ACCT-ID"
    with pytest.raises(LayoutError) as mismatch:
        export_record.render_masked_export_field(image, foreign)
    message = str(mismatch.value)
    assert foreign in message
    assert repr(carried) in message


def test_the_security_user_reader_publishes_no_character_decode_path() -> None:
    """Require the credential-bearing reader to expose the byte path and nothing else.

    :returns: nothing; any published character decode entry point is reported as a failure.
    """
    # WHY : Refactoring Rationale: this reader USED to publish the character trio, and a test here
    #   used to drive it by transcoding the shipped EBCDIC extract per record and rejoining the
    #   images as lines. That test is gone with the API, and its replacement asserts the opposite
    #   property, because the trio was itself the defect: a character entry point can only be fed
    #   text somebody transcoded, and every transcode of THIS dataset is a copy of an
    #   eight-character plaintext password written outside the one REFERENCE-only file meant to
    #   hold it. The old test had to synthesise its own input for exactly that reason -- no ASCII
    #   corpus of this record exists -- so it verified a transcode the test itself performed.
    # WHY : Assumptions: both `dir` and `__all__` are checked. A name removed from `__all__` but
    #   left defined is still reachable by attribute access, and a name left in `__all__` but not
    #   defined breaks a star import; the contract here is that neither holds.
    for absent in (
        "decode_ascii_security_user",
        "iter_ascii_security_users",
        "read_ascii_security_users",
    ):
        assert not hasattr(usrsec, absent), f"{absent} must not be published"
        assert absent not in usrsec.__all__
    assert callable(usrsec.decode_ebcdic_security_user)
    assert callable(usrsec.iter_ebcdic_security_users)
    assert callable(usrsec.read_ebcdic_security_users)


def test_the_withheld_character_path_set_matches_the_readers_that_publish_none() -> None:
    """Require the factory's policy set and the readers' published surfaces to agree exactly.

    :returns: nothing; a disagreement in either direction is reported as a failure.
    """
    # WHY : Refactoring Rationale: removing the security-user reader's character trio closed one
    #   door and left another open. The generic reader this package's factory builds is reachable
    #   from the command line for ANY registered layout with `--encoding ascii`, and that record is
    #   character data end to end, so it passed the factory's technical text-decodability test
    #   perfectly well. A suppression honoured on one entry point and not another is not a
    #   suppression, so the factory now refuses it by policy -- and the policy is stated in the
    #   factory as data, because a factory cannot ask a reader it is used to build without inverting
    #   the dependency.
    # WHY : Assumptions: the audit runs in BOTH directions, which is what makes the duplication safe
    #   rather than merely brief. A layout named in the set whose reader still publishes a character
    #   path would mean the two disagree; a text-decodable layout whose reader publishes none while
    #   being absent from the set would mean the generic route stayed open. Either alone would pass
    #   a one-way check.
    withheld = factory.CHARACTER_PATH_WITHHELD_RECORDS
    published: set[str] = set()
    unpublished: set[str] = set()
    for case in _FLAT_READERS:
        stem = f"read_ascii_{case.plural}"
        (published if hasattr(case.module, stem) else unpublished).add(case.layout.name)

    assert withheld == unpublished, (
        "the factory's withheld set must name exactly the text-decodable records whose readers"
        f" publish no character path; withheld={sorted(withheld)},"
        f" unpublished={sorted(unpublished)}"
    )
    assert not (withheld & published), "a withheld record must publish no character path"
    for name in withheld:
        reader = factory.RecordReader(layouts.layout(name))
        with pytest.raises(LayoutError, match="by policy"):
            list(reader.iter_ascii(""))


def test_the_security_user_password_is_absent_from_every_decoded_record(
    seed_corpus: SeedCorpus,
) -> None:
    """Read the shipped extract and require the suppressed field to appear in no record.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a decoded record carrying the password field is reported as a failure.
    """
    # WHY : Assumptions: the assertion is over the SHIPPED extract rather than a fixture, because
    #   the suppression has to hold for the corpus that actually carries credentials. The field
    #   name is taken from the reader's published suppression set rather than written here, so a
    #   change to that set is a failure rather than a silently narrowed test.
    records = list(
        usrsec.read_ebcdic_security_users(seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.USRSEC.PS"))
    )
    assert records, "the shipped security-user extract must not be empty"
    assert usrsec.SUPPRESSED_FIELD_NAMES == frozenset({"SEC-USR-PWD"})
    for suppressed in usrsec.SUPPRESSED_FIELD_NAMES:
        assert all(suppressed not in record for record in records)
        assert suppressed not in {field.name for field in usrsec.LOADED_FIELDS}


def test_the_security_user_masked_record_withholds_the_password_under_every_key(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Require the whole-record rendering to carry no key-dependent form of the password.

    :param monkeypatch: pytest fixture used to vary the masking key.
    :returns: nothing; a password-dependent or key-dependent span is reported as a failure.
    """
    # WHY : Refactoring Rationale: the whole-record rendering used to hand the record straight to
    #   the shared masker, which redacts a sensitive field by HMAC-ing its own characters -- so the
    #   password WAS an input to a digest and two different passwords produced two different tags.
    #   For an eight-character credential that tag is a confirmable oracle to anyone holding the
    #   key, and the reader's own contract states it produces no digested form. The rendering now
    #   substitutes a fixed marker into the span before masking, and this test is the proof: the
    #   span must be identical across two different passwords AND across two different keys, which
    #   no keyed tag of any input can be.
    # WHY : Assumptions: the key is varied through the environment variable alone, with no module
    #   reload, because the masker resolves the key at CALL time -- which is the pattern
    #   `test_corpus_disclosure.py` already relies on. Reloading the layouts module would replace
    #   its class and enum objects and break identity for every module that already imported them.
    layout = usrsec.SECUSER_LAYOUT
    field = layout.field("SEC-USR-PWD")

    def _record(password: str) -> str:
        """Assemble one full-width record carrying the given password in its declared span.

        :param password: exactly the declared width of the suppressed field.
        :returns: one record of exactly the declared record length.
        """
        assert len(password) == field.length
        head = "USER0001" + "Ann".ljust(20) + "Smith".ljust(20)
        return (head.ljust(field.start) + password + "A").ljust(layout.reclen)

    def _rendered(password: str, key: bytes) -> str:
        """Render one record under one masking key.

        :param password: the password to place in the suppressed span.
        :param key: raw key material, base64-encoded into the masking-key variable.
        :returns: the masked whole-record rendering.
        """
        monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, base64.b64encode(key).decode())
        return usrsec.render_masked_security_user_record(_record(password))

    key_one = bytes(range(32))
    key_two = bytes(range(32, 64))
    first = _rendered("PASSWD01", key_one)
    second = _rendered("PASSWD02", key_one)
    third = _rendered("PASSWD01", key_two)

    span = slice(field.start, field.end)
    assert len(first) == layout.reclen
    assert first[span] == second[span], "the span must not depend on the password"
    assert first[span] == third[span], "the span must not depend on the masking key"
    assert set(first[span]) == {"*"}
    for rendering, password in ((first, "PASSWD01"), (second, "PASSWD02"), (third, "PASSWD01")):
        assert password not in rendering

    # WHY : Assumptions: a NON-suppressed sensitive field is asserted to remain key-dependent, so
    #   this test cannot pass by the masking having been disabled altogether. The name fields are
    #   redacted to keyed tags by design, and that design is what makes a masked diff useful.
    assert first[8:28] != third[8:28]


def test_the_security_user_reader_offers_no_whole_record_character_surface(
    seed_corpus: SeedCorpus,
) -> None:
    """Prove the security-user reader offers no whole-record character surface, and needs none.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a re-published character entry point, or a password reaching a decoded
        record or a masked rendering, is reported as a failure.
    """
    # WHY : Refactoring Rationale: this test replaces one that DROVE the character trio -- it
    #   transcoded the shipped EBCDIC extract per record and required the character path to agree
    #   with the byte path. The agreement held, and that was the problem: what it proved was that a
    #   published entry point accepted a whole 80-byte record as a `str`, and those 80 bytes include
    #   `SEC-USR-PWD PIC X(08)` which the baseline stores in CLEAR. The test's own comment noted the
    #   transcode "is precisely what the conversion that produced every other ASCII seed did" --
    #   true, and the reason this record has no such seed. The trio is withdrawn, so this test now
    #   pins the ABSENCE and the two properties that make the absence safe.
    layout = layouts.layout("SECUSER")
    for withdrawn in _WITHDRAWN_CHARACTER_ENTRY_POINTS:
        assert not hasattr(usrsec, withdrawn)
        assert withdrawn not in usrsec.__all__

    # WHY : Assumptions: absence alone would be satisfied by a reader that simply could not read
    #   this record, so the byte path is exercised against the SHIPPED extract in the same test.
    #   Ten records is the corroborated count -- 800 bytes over an 80-byte record, and the ten
    #   in-stream users of `app/jcl/DUSRSECJ.jcl` L35-L44 -- so a path that returned nothing would
    #   fail here rather than pass by vacuity.
    decoded = list(
        usrsec.read_ebcdic_security_users(seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.USRSEC.PS"))
    )
    assert len(decoded) == 10
    assert {record["SEC-USR-TYPE"] for record in decoded} == usrsec.USER_TYPE_DOMAIN

    # WHY : Assumptions: the suppression is asserted over the PUBLISHED FIELD SET and over every
    #   decoded record, not just over one. The span is excluded by iterating `LOADED_FIELDS`, so a
    #   descriptor edited to publish it would reintroduce the credential on the byte path with the
    #   character path already gone -- which is the failure this pair of assertions catches.
    assert "SEC-USR-PWD" in usrsec.SUPPRESSED_FIELD_NAMES
    assert all(field.name != "SEC-USR-PWD" for field in usrsec.LOADED_FIELDS)
    assert all("SEC-USR-PWD" not in record for record in decoded)

    # WHY : Assumptions: the two surviving character-taking members are diagnostics and are proven
    #   to stay diagnostics. `record_key` yields the declared eight-character key and nothing more,
    #   and the whole-record rendering keeps the declared width while emitting no part of the
    #   password span -- so neither is a decode path readmitted under another name.
    raw = seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.USRSEC.PS").read_bytes()
    image = raw[: layout.reclen].decode("cp037")
    assert usrsec.record_key(image) == image[: layout.key_length]
    password = layout.field("SEC-USR-PWD")
    rendered = usrsec.render_masked_security_user_record(image)
    assert len(rendered) == layout.reclen
    assert image[password.start : password.end] not in rendered
    with pytest.raises(LayoutError):
        usrsec.render_masked_security_user_field(image, "SEC-USR-PWD")


def test_the_export_money_total_agrees_with_the_transaction_corpus(
    seed_corpus: SeedCorpus,
) -> None:
    """Sum the export extract's packed amounts and compare against the daily-transaction seed.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a disagreement between the two corpora's totals is reported as a failure.
    """
    # WHY : Alternatives Considered: the export total is compared against ANOTHER corpus rather
    #   than against a number written here. The export extract stores its amounts as COMP-3 and
    #   the daily-transaction seed stores the same amounts as zoned display, so an equal total
    #   proves the packed and the display codecs agree about the same money -- which a literal
    #   expectation could not, because a literal would have been copied from whichever of the
    #   two this file happened to run first.
    export_total = sum(
        row["EXP-TRAN-AMT"]
        for row in export_record.read_ebcdic_export_records(
            seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.EXPORT.DATA.PS")
        )
        if row["EXPORT-REC-TYPE"] == "T"
    )
    daily_total = sum(
        row["DALYTRAN-AMT"]
        for row in dalytran.read_ebcdic_daily_transactions(
            seed_corpus.ebcdic_path("AWS.M2.CARDDEMO.DALYTRAN.PS")
        )
    )
    assert isinstance(export_total, Decimal)
    assert export_total == daily_total


def test_this_module_commits_no_raw_identifier_literal() -> None:
    """Scan this file's own source and require that it carries no identifier-shaped literal."""
    # WHY : Refactoring Rationale: a sibling test module was found to have copied a full card
    #   number and an account identifier into its source, where every failure rendered them.
    #   This file reads every such value out of the corpus at run time instead, and asserts that
    #   property of itself so the rule is enforced by the suite rather than by review.
    # WHY : Trade-offs: the check is a digit-run pattern over the raw source rather than a parse
    #   of string literals only. It therefore also covers a value hidden in a comment or a
    #   docstring, at the cost of refusing a long digit run that happened to be harmless -- an
    #   acceptable trade, since no legitimate constant in a test module needs eleven digits.
    source = pathlib.Path(__file__).read_text(encoding="utf-8")
    assert _IDENTIFIER_RUN.search(source) is None


def test_the_reader_field_inventories_cover_every_declared_field() -> None:
    """Confirm published, dropped and suppressed names together account for every field."""
    # WHY : Assumptions: this closes the inventory over the whole tree rather than per reader. A
    #   decoded record is only a complete description of its bytes if every declared field is
    #   either published, recorded as dropped, or recorded as suppressed; a field in none of the
    #   three would be a silent omission, which is precisely what a caller cannot detect.
    for case in _FLAT_READERS:
        published = {field.name for field in case.module.LOADED_FIELDS}
        suppressed = frozenset(getattr(case.module, "SUPPRESSED_FIELD_NAMES", frozenset()))
        accounted = published | case.module.DROPPED_FIELD_NAMES | suppressed
        declared = {field.name for field in case.layout.fields}
        assert accounted == declared, f"{case.module_name} does not account for every field"

    # WHY : Assumptions: the export reader's dropped set spans the envelope AND all five
    #   overlays -- it names the opaque payload area and the pad each branch declares -- so the
    #   relation asserted for it is containment rather than equality. Equality would require the
    #   header to declare a pad it does not have, and the containment still proves the property
    #   that matters: no declared field of either half is unaccounted for.
    envelope = {field.name for field in export_record.ENVELOPE_LOADED_FIELDS}
    accounted = envelope | export_record.DROPPED_FIELD_NAMES
    assert {field.name for field in layouts.EXPORT_HEADER_LAYOUT.fields} <= accounted
    for record_type, branch in layouts.EXPORT_RECORD_TYPES.items():
        branch_names = {field.name for field in export_record.branch_loaded_fields(record_type)}
        accounted = (
            branch_names | export_record.DROPPED_FIELD_NAMES | export_record.SUPPRESSED_FIELD_NAMES
        )
        assert {field.name for field in branch.fields} <= accounted


def _sensitive_names(layout: RecordSpec) -> frozenset[str]:
    """Return the names of a layout's sensitive fields.

    Parameters
    ----------
    layout : RecordSpec
        The layout to inspect.

    Returns
    -------
    frozenset[str]
        Every field name the descriptor marks sensitive.

    Raises
    ------
    None
    """
    return frozenset(field.name for field in layout.fields if field.sensitive)


def test_every_money_and_identifier_field_is_classified_sensitive() -> None:
    """Confirm the disclosure classification reaches money and identifiers in every record."""
    # WHY : Refactoring Rationale: the readers' renderings were documented as privacy-safe while
    #   the classification they delegate to marked no identifier and no money field sensitive at
    #   all, so the documentation and the behaviour disagreed. Asserting the classification here
    #   -- at the layouts module, where the single authority lives -- is what stops the two
    #   drifting again, because a reader cannot restore the gap by itself.
    expectations: tuple[tuple[RecordSpec, tuple[str, ...]], ...] = (
        (layouts.ACCOUNT_LAYOUT, ("ACCT-ID", "ACCT-CURR-BAL", "ACCT-CREDIT-LIMIT")),
        (layouts.CARD_LAYOUT, ("CARD-NUM", "CARD-ACCT-ID", "CARD-CVV-CD")),
        (layouts.CUSTOMER_LAYOUT, ("CUST-ID", "CUST-SSN")),
        (layouts.XREF_LAYOUT, ("XREF-CARD-NUM", "XREF-ACCT-ID", "XREF-CUST-ID")),
        (layouts.TCATBAL_LAYOUT, ("TRANCAT-ACCT-ID", "TRAN-CAT-BAL")),
        (layouts.DALYTRAN_LAYOUT, ("DALYTRAN-CARD-NUM", "DALYTRAN-AMT")),
        (layouts.TRAN_LAYOUT, ("TRAN-CARD-NUM", "TRAN-AMT")),
        (layouts.EXPORT_ACCOUNT_LAYOUT, ("EXP-ACCT-ID", "EXP-ACCT-CURR-BAL")),
        (layouts.EXPORT_CARD_LAYOUT, ("EXP-CARD-NUM", "EXP-CARD-ACCT-ID", "EXP-CARD-CVV-CD")),
    )
    for layout, required in expectations:
        classified = _sensitive_names(layout)
        missing = tuple(name for name in required if name not in classified)
        assert not missing, f"{layout.name} does not classify {missing} sensitive"

    # WHY : Assumptions: the interest rate is asserted NOT sensitive, and the exclusion is
    #   deliberate rather than an oversight. A disclosure-group rate is a published product term
    #   held against a group of accounts and not against any one cardholder, and the
    #   command-line decode of that record is the one case an operator has to be able to read.
    assert "DIS-INT-RATE" not in _sensitive_names(layouts.DISGROUP_LAYOUT)


def test_an_absent_transaction_source_yields_no_records_and_no_error(
    tmp_path: pathlib.Path,
) -> None:
    """Report a missing transaction extract as absent, yielding nothing from either entry point.

    :param tmp_path: pytest-supplied empty directory.
    :returns: nothing; an exception, or any record yielded, is reported as a failure.
    """
    # WHY : Assumptions: this is the one reader whose input may legitimately not exist, because no
    #   TRANSACT extract ships in either encoding and `sql/verify/row_counts.sql` gives
    #   `ledger.transactions` a NULL baseline rather than zero for exactly that reason. A reader
    #   that raised here would report a correct fresh load as a failed one.
    missing = tmp_path / "no-such-extract.txt"
    assert transaction.seed_dataset_is_present(missing) is False
    assert list(transaction.read_ascii_transactions(missing)) == []
    assert list(transaction.read_ebcdic_transactions(missing)) == []


def test_a_directory_where_a_transaction_extract_belongs_is_refused(
    tmp_path: pathlib.Path,
) -> None:
    """Refuse a directory at the extract path instead of reporting it absent.

    :param tmp_path: pytest-supplied empty directory.
    :returns: nothing; reporting a directory as absent is a failure.
    """
    # WHY : Refactoring Rationale: this used to report `False`. The guard was `path.is_file()`, and
    #   its comment argued a directory "resembles" absence and that the open would report it
    #   anyway -- both halves wrong. It resembles absence only in what the caller sees, which is
    #   nothing at all, and the open is never reached because the guard returns first. An operator
    #   who pointed `--source` at the containing directory therefore got a clean run that migrated
    #   no transactions, and the NULL baseline meant no verification pass could contradict it.
    directory = tmp_path / "extracts"
    directory.mkdir()
    with pytest.raises(IsADirectoryError) as refused:
        transaction.seed_dataset_is_present(directory)
    assert str(directory) in str(refused.value)
    # WHY : Assumptions: the PREDICATE and the READ paths refuse with different types, and the
    #   difference is the contract rather than an inconsistency. The predicate answers a question
    #   about the filesystem, so its refusals are the standard filesystem exceptions a caller would
    #   already be guarding an open with. A read is decoding a dataset against a declared layout,
    #   and its guard exists precisely to replace an errno that "names neither the record nor the
    #   policy" with a refusal naming both -- so it raises the package's own error, as every other
    #   non-regular-kind case on the read paths asserts.
    # WHY : Refactoring Rationale: these two expected IsADirectoryError, which the read paths have
    #   never raised: the guard they reach reports the kind and the policy. Asserting the type the
    #   read paths actually use puts this case in step with the eight sibling assertions in
    #   test_reader_hardening and test_source_hardening instead of contradicting all of them.
    for reader in (transaction.read_ascii_transactions, transaction.read_ebcdic_transactions):
        with pytest.raises(LayoutError, match="directory"):
            list(reader(directory))


def test_an_uninspectable_transaction_path_propagates_rather_than_reading_as_absent(
    tmp_path: pathlib.Path,
) -> None:
    """Propagate an inspection failure instead of reporting the extract absent.

    :param tmp_path: pytest-supplied empty directory.
    :returns: nothing; an inspection failure swallowed into `False` is reported as a failure.
    """
    # WHY : Assumptions: `pathlib` implements `is_file` by calling `stat` inside its own
    #   `except OSError: return False`, so a permission fault on the path or any parent, a broken
    #   mount and a symlink loop ALL reported "absent" -- and absent is the one state whose
    #   documented consequence is to load nothing and raise nothing. A migration run against an
    #   unmounted volume therefore reported success having written zero rows. The docstring already
    #   promised that an inspection failure propagates; only the code did not.
    # WHY : Alternatives Considered: the fault is produced by naming a child of a FILE rather than
    #   by revoking a permission. A permission test is skipped when the suite runs as root, which
    #   is how this suite runs in the container, so it would have proved nothing there; a path
    #   component that is not a directory raises `NotADirectoryError` for every user.
    blocker = tmp_path / "not-a-directory.txt"
    blocker.write_text("x", encoding="ascii")
    with pytest.raises(NotADirectoryError):
        transaction.seed_dataset_is_present(blocker / "child.txt")


def test_a_non_file_entry_at_the_transaction_path_is_refused(tmp_path: pathlib.Path) -> None:
    """Refuse an entry that is neither absent, a directory, nor a regular file.

    :param tmp_path: pytest-supplied empty directory.
    :returns: nothing; accepting a non-file entry is reported as a failure.
    """
    # WHY : Assumptions: a fifo is the case that matters most and is why the mode is tested
    #   POSITIVELY for a regular file rather than negatively against a list. Reading a fifo would
    #   BLOCK rather than fail, so a load pointed at one would hang with no diagnostic at all --
    #   and a kind this package has never seen is refused rather than admitted by omission.
    fifo = tmp_path / "a-fifo"
    os.mkfifo(fifo)
    with pytest.raises(OSError) as refused:
        transaction.seed_dataset_is_present(fifo)
    assert not isinstance(refused.value, IsADirectoryError)
    assert "regular file" in str(refused.value)


def test_a_zero_byte_transaction_extract_is_present_and_yields_nothing(
    tmp_path: pathlib.Path,
) -> None:
    """Report an empty extract as present, and yield no records from it.

    :param tmp_path: pytest-supplied empty directory.
    :returns: nothing; an empty file read as absent, or as an error, is reported as a failure.
    """
    # WHY : Assumptions: an empty file is present rather than filtered out, because both iterators
    #   already yield nothing for one -- the text path produces no line and the fixed-length path
    #   divides zero bytes into zero records. A second statement of "no records" here is how the
    #   two would come to disagree about, for instance, a file holding a single stray separator.
    empty = tmp_path / "empty.txt"
    empty.write_bytes(b"")
    assert transaction.seed_dataset_is_present(empty) is True
    assert list(transaction.read_ascii_transactions(empty)) == []


@pytest.mark.parametrize(
    "module_name",
    ["dalytran", "transaction", "export_record"],
)
def test_every_stamp_bearing_reader_delegates_to_the_shared_timestamp_authority(
    module_name: str,
) -> None:
    """Prove each stamp-bearing reader imports the shared authority and keeps no rule of its own.

    :param module_name: one of the three readers that validate a 26-character stamp.
    :returns: nothing; a reader carrying its own copy of the rule is reported as a failure.
    """
    # WHY : Refactoring Rationale: the shape rule existed as forty byte-identical lines in each of
    #   these three modules -- two pad constants, a uniformity helper, a per-position helper and
    #   two frozen sets of admitted separators and digits. Three copies meant a correction had to
    #   be made three times or the three would disagree about what a timestamp is while all three
    #   continued to look right. This test pins the single source rather than the deletion, because
    #   a copy re-introduced later would pass every behavioural test in this file.
    source = (
        pathlib.Path(__file__).resolve().parents[1]
        / "src/carddemo_migration/readers"
        / f"{module_name}.py"
    ).read_text(encoding="utf-8")
    assert "from carddemo_migration.copybook import timestamp" in source
    assert "timestamp.is_unwritten(" in source
    assert "timestamp.is_admitted(" in source
    for withdrawn in (
        "_TIMESTAMP_SEPARATOR_OFFSETS",
        "_TIMESTAMP_SEPARATOR_CHARACTERS",
        "_TIMESTAMP_DIGITS",
        "def _is_timestamp_position",
        "def _is_uniformly",
    ):
        assert withdrawn not in source, (
            f"{module_name} carries its own copy of the timestamp shape rule ({withdrawn});"
            " the rule belongs to carddemo_migration.copybook.timestamp alone"
        )


def test_a_corrupt_stamp_is_refused_by_the_reader_that_carries_it(
    seed_corpus: SeedCorpus,
) -> None:
    """Drive a well-formed-looking but impossible stamp through a reader and require a refusal.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; acceptance of an impossible stamp is reported as a failure.
    """
    # WHY : Assumptions: the corruption is a spelling the OLD rule admitted -- an impossible
    #   calendar date whose every character sits in an admitted position -- so this case fails
    #   against the rule that was replaced and passes only against the one that parses. It is
    #   applied to a REAL committed record rather than a synthesised one, so every other field
    #   stays exactly as the baseline wrote it and the refusal can only be about the stamp.
    # WHY : Assumptions: the PROCESSING stamp is the field corrupted, not the originating one,
    #   because the reader validates only the fields its descriptor marks run-generated -- the
    #   originating stamp is deterministic business data and is carried through unexamined. Where
    #   the originating stamp is checked is the LOAD boundary, which has to render both of them
    #   into a castable form and refuses a value it cannot; `test_aurora_loader.py` pins that half.
    layout = layouts.layout("DALYTRAN")
    field = layout.field("DALYTRAN-PROC-TS")
    assert field.normalize_ts, "the corrupted field must be one the reader validates"
    record = seed_corpus.ascii_records("dailytran.txt")[0]
    corrupted = record[: field.start] + "2022-13-45 10:30:00.123456" + record[field.end :]
    assert len(corrupted) == layout.reclen, "the corruption must not change the width"
    with pytest.raises(layouts.LayoutError) as refused:
        dalytran.decode_ascii_daily_transaction(corrupted)
    message = str(refused.value)
    assert field.describe() in message
    # WHY : no part of the offending value may reach the diagnostic. This record carries a primary
    #   account number, and a message shape that quotes its input is the shape that later gets
    #   copied to a field where the input is not safe to quote.
    assert "2022-13-45" not in message
