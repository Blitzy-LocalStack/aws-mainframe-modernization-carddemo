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

Alternatives Considered: authoring fresh fixtures for this package, or copying a handful of
vectors into ``data-migration/tests`` and reading those. Both were rejected on what each choice
proves. The vectors under ``tests/fixtures`` are the input the reference COBOL programs are run
against and the same layouts the Java shared kernel's codecs read, so decoding them here is what
demonstrates CROSS-LANGUAGE agreement -- Python with the COBOL that wrote the bytes, and Python
with the Java that reads the same records. A vector invented in this folder would demonstrate
only that this package agrees with itself, and a cross-language disagreement returns well-formed
wrong numbers rather than an error, so self-agreement is precisely the evidence that would not
catch it. Copying would additionally duplicate a reference corpus this migration is forbidden to
modify or re-pin, leaving a second thing to keep in step with a file nobody may edit.

Assumptions: no account identifier, primary account number or other sensitive value is written
into this file as a literal. Where a test needs one it reads it out of the corpus at run time,
and one test asserts that property of this module's own source, so the rule is enforced here
rather than merely observed.

Assumptions: "EBCDIC" names two different things in this package and no test below conflates
them. In ``copybook.ebcdic_codec`` it is a CHARACTER ENCODING -- cp037 -- deciding which byte
value is which character. In ``copybook.zoned`` it is a SIGN CONVENTION, deciding which trailing
character of a display-decimal field carries the negative sign; that is the convention the
reference compile invocation selects with its sign flag, and the one whose absence silently
corrupts negative balances rather than failing. A record may need one, the other, both or
neither: the ASCII seeds need the sign convention and no code page, the EBCDIC extracts need
both, and the export payload's packed and binary spans need neither.

Assumptions: every geometry this module asserts -- offset, length, record length, scale, key
span -- is read from a descriptor in ``copybook.layouts`` and compared against a figure measured
from the committed corpus. The module declares no geometry of its own, which is the same
discipline the readers are held to, and it is what makes a failure here a statement about the
reader or the corpus rather than about a third copy of the copybook kept in a test.

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

from carddemo_migration import readers as readers_package
from carddemo_migration.copybook import ebcdic_codec, layouts, packed
from carddemo_migration.copybook.layouts import FieldSpec, Kind, LayoutError, RecordSpec
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

# WHY : Assumptions: the shared seed-access module is imported under an ALIAS because two tests in
#   this file already bind a local named ``source`` -- one reading this file's own text, one reading
#   the readers' -- and a module shadowed by a local is a module whose absence nothing reports.
from carddemo_migration.readers import source as reader_source

if TYPE_CHECKING:
    from collections.abc import Callable, Iterator, Mapping
    from types import ModuleType

    # WHY : Assumptions: the two corpus classes are imported for annotation only, under the
    #   type-checking guard, exactly as ``test_zoned`` does. pytest injects both objects as
    #   fixtures, so the names are needed to document the parameters and for nothing else;
    #   importing them unconditionally would tie collection of this file to pytest's
    #   path-insertion order.
    from conftest import FixtureCorpus, SecUserRecordBuilder, SeedCorpus


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
# WHY : Assumptions: the card reader is deliberately ABSENT from this table. Its verification
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
    """Prove all twelve reader modules exist and are reachable as package attributes.

    :returns: nothing; a reader module the plan requires but the package lacks is reported as
        an ``ImportError`` at collection or as an assertion failure here.
    """
    # WHY : Assumptions: the plan's twelve-module reader inventory is a requirement no other
    #   executable statement in this distribution carries -- an inventory satisfied only by the
    #   imports at the top of some file is satisfied by nothing a failure can name. This test
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
    """Confirm ``readers`` ships an ``__init__`` rather than being an implicit namespace.

    :returns: nothing; a namespace package, whose contents no manifest controls, is a failure.
    """
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
    :returns: nothing; a key of the wrong span is a failure.
    :raises RecordLengthError: expected of a record shorter or longer than the declared width.
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

    # WHY : Assumptions: the OVER-LONG case is asserted alongside the short one, because the
    #   padding tolerance is asymmetric and only the short direction is tolerated. An accessor
    #   that checked the short direction alone would accept a record longer than its own
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
    # WHY : Assumptions: the drop is asserted to be RECORDED as well as observed, and the two are
    #   different claims. A row that happens not to contain a pad proves nothing about a reader
    #   that never declared one -- the pad could be published under another name, or the record
    #   could have none -- whereas an inventory naming it is what a loader can be checked against.
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
    # WHY : Assumptions: the BYTE path is driven with an empty image here, which reaches the
    #   in-memory branch rather than the file branch -- an empty extract must divide into zero
    #   records, and zero is the only count a division by any record length can produce from no
    #   bytes. The committed zero-byte files exercise the file branch in the tests that follow.
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
    :raises RecordLengthError: expected of an extract that does not divide into whole records.
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
    :raises TypeError: expected of the byte entry point when handed an already-decoded string.
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
    :returns: nothing; a reader that decodes a multi-byte character is a failure.
    :raises RecordLengthError: expected of a record whose byte width exceeds its character
        count, which is how a multi-byte character presents.
    """
    # WHY : Assumptions: all eleven readers carry this refusal and its ``_field_containing``
    #   helper, and NO committed corpus can reach it -- every shipped ASCII seed is single-byte
    #   throughout -- so the branch is live code only a synthesised record enters. That is the
    #   same shape as the hazard this module pins for the digit proof, where two paths of ONE
    #   reader can enforce different contracts because only one of them is exercised. Asserting
    #   it across the whole table is what keeps the contract from drifting per reader.
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
    :raises Exception: a refusal is required, and the specific class is asserted in the body to
        be one of ``LayoutError``, ``ZonedDecimalError`` or ``PackedDecimalError`` -- which one
        depends on the field the low-value span first violates.
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
    """Sum one money column from both corpora and require the two totals to be equal.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a difference between the two totals is reported as a failure.
    """
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
    :raises LayoutError: expected of the field-scoped rendering asked for a suppressed name.
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
    # WHY : Assumptions: a rendering documented as privacy-safe depends entirely on the
    #   classification it delegates to, so an account identifier left unmarked passes through
    #   such a rendering verbatim and nothing in the rendering itself can tell. The property is
    #   asserted for ALL FOUR records that carry an account identifier rather than for one of
    #   them, because the classification lives in the layouts module and a classification either
    #   holds everywhere or is not one.
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
    :returns: nothing; an encoding that accepts a non-digit body is a failure.
    :raises Exception: a refusal is required from each path, and the body asserts the class is
        ``ZonedDecimalError`` for the character path and ``EbcdicFieldDecodeError`` or
        ``ZonedDecimalError`` for the byte path.
    """
    # WHY : Assumptions: the two paths are the same contract and are asserted together. A
    #   character path that returned an unsigned-display field's characters without proving they
    #   were digits would decode an alphabetic account identifier cleanly from a text seed while
    #   the equivalent byte image refused it -- the same record accepted by one corpus and
    #   refused by the other. Asserting both refusals in ONE test is what keeps the two
    #   contracts tied together; two separate tests can drift apart as easily as two code paths.
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
    """Confirm a retained field decoding to raw bytes fails closed without echoing the bytes.

    :returns: nothing; a silently dropped or echoed byte-valued field is reported as a failure.
    :raises LayoutError: expected of the projection when a retained field decodes to raw bytes.
    """
    # WHY : Assumptions: a projection that dropped a byte-valued field silently would let a
    #   descriptor acquiring a computational regime produce decoded records quietly missing a
    #   column -- a partial row that looks complete. Failing closed is what turns that into a
    #   visible defect. The refusal is asserted to name the field's geometry
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
    # WHY : Trade-offs: this test observes the CODEC rather than the reader's returned mapping,
    #   which is the more intrusive of the two and is chosen deliberately. A reader that built its
    #   result from the published field tuple AFTER decoding every field the branch declares would
    #   leave `EXP-CARD-CVV-CD` absent from the returned mapping and present as a decoded value in
    #   the payload mapping the decode produced -- and a traceback raised anywhere below the decode
    #   would then carry it. Asserting on the returned keys cannot tell those two apart; watching
    #   the codec fails if the span is decoded at all, whatever happens to the value afterwards.
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
    :returns: nothing; a record decoded under an unknown discriminator is a failure.
    :raises LayoutError: expected of the reader when the discriminator names no overlay, which
        is the typed equivalent of the baseline's own unknown-record branch.
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
    :raises LayoutError: expected of the field renderer for a dropped name and for a name
        belonging to another overlay.
    """
    # WHY : Assumptions: the suppression refusal and the DROPPED refusal guard different things,
    #   so both are pinned. Suppression withholds a value that exists; dropping withholds a span
    #   that is not a publishable field at all -- ``EXPORT-RECORD-DATA`` is the 460-byte opaque
    #   payload, so a renderer that fell through to it would emit the whole overlay raw, including
    #   the primary account number and the verification value the reader suppresses one branch
    #   away. That is the single worst disclosure this module could produce, and no corpus
    #   assertion reaches it, because a well-formed record never asks for a dropped name.
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
    # WHY : Alternatives Considered: driving a character trio for this reader by transcoding the
    #   shipped EBCDIC extract per record and rejoining the images as lines, which is how the other
    #   ten readers' character paths are exercised. Rejected for this record specifically: a
    #   character entry point can only be fed text somebody transcoded, and every transcode of THIS
    #   dataset is a copy of an eight-character plaintext credential written outside the one
    #   REFERENCE-only file meant to hold it. A test would have had to synthesise that input itself
    #   -- no ASCII corpus of this record exists -- so it would verify a transcode it performed.
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
    :raises LayoutError: expected of the factory asked to build a character path for a record
        whose character path is withheld by policy.
    """
    # WHY : Assumptions: withholding the security-user reader's character trio closes only one of
    #   two doors. The generic reader this package's factory builds is reachable from the command
    #   line for ANY registered layout with `--encoding ascii`, and that record is character data
    #   end to end, so it satisfies the factory's technical text-decodability test perfectly well.
    #   A suppression honoured on one entry point and not another is not a suppression, so the
    #   factory refuses it by POLICY -- and the policy is stated in the factory as data, because a
    #   factory cannot ask a reader it is used to build without inverting the dependency.
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
    # WHY : Assumptions: the shared masker redacts a sensitive field by HMAC-ing its own
    #   characters, so a whole-record rendering that handed the record straight to it would make
    #   the credential an INPUT to a digest, and two different credentials would produce two
    #   different tags. For an eight-character secret that tag is a confirmable oracle to anyone
    #   holding the key, and the reader's contract states it produces no digested form. The
    #   rendering therefore substitutes a fixed marker into the span before masking, and this test
    #   is the proof: the span must be identical across two different secrets AND across two
    #   different keys, which no keyed tag of any input can be.
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
    :raises LayoutError: expected of the field-scoped rendering asked for the withheld span.
    """
    # WHY : Alternatives Considered: asserting agreement between a character path and the byte
    #   path, which is the shape every other reader's parity test takes. Rejected here because the
    #   agreement is not the interesting property: what such a test would demonstrate is that a
    #   published entry point accepts a whole 80-byte record as a `str`, and those 80 bytes include
    #   `SEC-USR-PWD PIC X(08)`, which the baseline stores in CLEAR. Transcoding the extract to
    #   build that argument is precisely the conversion that produced every other ASCII seed -- and
    #   the reason this record has no such seed. This test pins the ABSENCE instead, together with
    #   the two properties that make the absence safe.
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
    """Scan this file's own source and require that it carries no identifier-shaped literal.

    :returns: nothing; a digit run long enough to be an account or card identifier is a failure.
    """
    # WHY : Assumptions: a test module that copies a full card number or an account identifier
    #   into its source renders that value in every failure report the module ever emits. This file
    #   reads every such value out of the corpus at run time instead, and asserts that property of
    #   itself so the rule is enforced by the suite rather than by review.
    # WHY : Trade-offs: the check is a digit-run pattern over the raw source rather than a parse
    #   of string literals only. It therefore also covers a value hidden in a comment or a
    #   docstring, at the cost of refusing a long digit run that happened to be harmless -- an
    #   acceptable trade, since no legitimate constant in a test module needs eleven digits.
    source = pathlib.Path(__file__).read_text(encoding="utf-8")
    assert _IDENTIFIER_RUN.search(source) is None


def test_the_reader_field_inventories_cover_every_declared_field() -> None:
    """Confirm published, dropped and suppressed names together account for every field.

    :returns: nothing; a declared field in none of the three inventories is unread, and is
        reported as a failure.
    """
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
    """Confirm the disclosure classification reaches money and identifiers in every record.

    :returns: nothing; a money or identifier field left unclassified is reported as a failure.
    """
    # WHY : Assumptions: a reader's rendering is only as privacy-safe as the classification it
    #   delegates to, so a layout that marked no identifier and no money field sensitive would
    #   leave every rendering's documented contract false while each reader stayed correct.
    #   Asserting the classification here -- at the layouts module, where the single authority
    #   lives -- is what keeps the two from drifting, because no reader can repair it alone.
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
    :raises LayoutError: expected of both read paths, which report the entry kind before any
        open happens, so the ``IsADirectoryError`` an open would raise never surfaces.
    """
    # WHY : Assumptions: a directory is NOT absence, even though a caller sees the same nothing
    #   from both. A presence guard written as `path.is_file()` would report `False` here and the
    #   open that might have complained is never reached, because the guard returns first. An
    #   operator who pointed `--source` at the containing directory would then get a clean run that
    #   migrated no transactions -- and because this record's verification baseline is legitimately
    #   NULL, no verification pass could contradict it.
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
    # WHY : Assumptions: the read paths raise ``LayoutError`` naming the entry KIND rather than the
    #   ``IsADirectoryError`` an open would produce, because the guard they reach reports the kind
    #   and the policy before any open happens. Asserting the type the read paths actually use puts
    #   this case in step with the eight sibling assertions in ``test_reader_hardening`` and
    #   ``test_source_hardening`` instead of contradicting all of them.
    for reader in (transaction.read_ascii_transactions, transaction.read_ebcdic_transactions):
        with pytest.raises(LayoutError, match="directory"):
            list(reader(directory))


def test_an_uninspectable_transaction_path_propagates_rather_than_reading_as_absent(
    tmp_path: pathlib.Path,
) -> None:
    """Propagate an inspection failure instead of reporting the extract absent.

    :param tmp_path: pytest-supplied empty directory.
    :returns: nothing; an inspection failure swallowed into `False` is a failure.
    :raises NotADirectoryError: expected to propagate from the presence check unchanged.
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
    :raises OSError: expected of the presence check, which refuses an entry that is neither
        absent, a directory, nor a regular file.
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
    # WHY : Trade-offs: this test reads the three modules' SOURCE to pin their delegation, which is
    #   coarser evidence than a behavioural assertion and is chosen because behaviour cannot see the
    #   property at all. The shape rule is around forty lines -- two pad constants, a uniformity
    #   helper, a per-position helper and two frozen sets of admitted separators and digits -- and
    #   three private copies of it would agree with every behavioural test in this file while
    #   disagreeing with each other on the first correction any one of them received.
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
    :raises LayoutError: expected of the reader for a stamp of the right width whose components
        name no real instant.
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


# ---------------------------------------------------------------------------
# The twelve-record length contract, verified twice over.
# ---------------------------------------------------------------------------
# WHY : Assumptions: each row below states a record length that was established TWICE and
#   independently -- once by summing the copybook's PICTURE clauses, which is what the layouts
#   module transcribes, and once by dividing the committed extract's byte size. The two agree for
#   all twelve, so an assertion that compares them cannot be satisfied by a reader that is merely
#   self-consistent: a descriptor drifting from the copybook stops dividing the file, and a file
#   replaced by one of another shape stops matching the descriptor.
# WHY : Trade-offs: the row count is carried here as DATA rather than derived from the file at
#   assertion time. Deriving it would make the test unfalsifiable -- a reader that yielded nothing
#   would agree with a count derived from what it yielded -- so the number is stated, at the cost
#   of an edit here whenever the reference corpus changes shape, which it may not.


class RecordLengthCase(NamedTuple):
    """One record's declared length, its copybook and the committed extract that confirms both.

    Purpose
    -------
    Carry the four facts the length contract needs for one record so a single parametrised test
    can assert the whole twelve-row table: which reader module owns the record, which copybook
    declares it, how long one record is, and how many records the committed extract holds.

    Assumptions: ``layout_name`` is the name the layout registry uses, which is NOT always the
    reader's module name -- the registry says ``DISGROUP`` where the module is ``discgrp``, and
    ``SECUSER`` where the module is ``usrsec``. Keeping both means a test can cross the registry,
    the dispatch table and the module surface without any of the three being assumed to agree.

    Assumptions: ``dataset`` is ``None`` for exactly one record. The posted-transaction master
    ships no extract at all, so its row count is the export scenario's fixture rather than a
    seed, and the division check has nothing to divide.
    """

    reader_name: str
    layout_name: str
    copybook: str
    reclen: int
    dataset: str | None
    rows: int


_RECORD_LENGTH_CONTRACT: Final[tuple[RecordLengthCase, ...]] = (
    RecordLengthCase("usrsec", "SECUSER", "CSUSR01Y", 80, "USRSEC.PS", 10),
    RecordLengthCase("account", "ACCOUNT", "CVACT01Y", 300, "ACCTDATA.PS", 50),
    RecordLengthCase("card", "CARD", "CVACT02Y", 150, "CARDDATA.PS", 50),
    RecordLengthCase("customer", "CUSTOMER", "CVCUS01Y", 500, "CUSTDATA.PS", 50),
    RecordLengthCase("xref", "XREF", "CVACT03Y", 50, "CARDXREF.PS", 50),
    RecordLengthCase("dalytran", "DALYTRAN", "CVTRA06Y", 350, "DALYTRAN.PS", 300),
    RecordLengthCase("transaction", "TRAN", "CVTRA05Y", 350, None, 5),
    RecordLengthCase("discgrp", "DISGROUP", "CVTRA02Y", 50, "DISCGRP.PS", 51),
    RecordLengthCase("trancatg", "TRANCAT", "CVTRA04Y", 60, "TRANCATG.PS", 18),
    RecordLengthCase("trantype", "TRANTYPE", "CVTRA03Y", 60, "TRANTYPE.PS", 7),
    RecordLengthCase("tcatbal", "TCATBAL", "CVTRA01Y", 50, "TCATBALF.PS", 50),
    RecordLengthCase("export_record", "EXPORT", "CVEXPORT", 500, "EXPORT.DATA.PS", 500),
)

_LENGTH_IDS: Final[tuple[str, ...]] = tuple(case.reader_name for case in _RECORD_LENGTH_CONTRACT)

# WHY : Assumptions: the export record is the one row whose layout is NOT in the record registry,
#   because a 500-byte export record is a fixed header plus a branch chosen per record type rather
#   than one field list. Its geometry therefore comes from ``EXPORT_HEADER_LAYOUT`` and its
#   copybook citation from this constant, which is stated once here rather than at each use.
_EXPORT_LAYOUT_NAME: Final[str] = "EXPORT"

#: The three registry records that no fixture round-trip vector covers, named so their special
#: treatment is asserted rather than assumed. See the test that reads it for why.
_LAYOUTS_WITHOUT_AN_ORACLE_VECTOR: Final[frozenset[str]] = frozenset(
    {"SECUSER", "TRANCAT", "TRANTYPE"}
)

# WHY : Assumptions: the plural stems are derived from the flat-reader table rather than restated,
#   so the length contract and the entry-point contract cannot disagree about what a reader's
#   ``read_ebcdic_*`` name is. The export reader is absent from the table and is therefore handled
#   by name wherever this mapping is consulted.
_PLURAL_STEMS: Final[dict[str, str]] = {case.module_name: case.plural for case in _FLAT_READERS}

# WHY : Assumptions: the seed-to-reader mapping is derived from the same table, so a test naming a
#   seed file reaches the reader that owns the record rather than one chosen by resemblance. Nine
#   of the eleven flat readers have an ASCII seed; the two that do not are omitted by construction
#   instead of being mapped to something that would read at the wrong width.
_READER_OF_SEED: Final[dict[str, str]] = {
    case.ascii_dataset: case.module_name for case in _FLAT_READERS if case.ascii_dataset is not None
}


def _contract_layout(case: RecordLengthCase) -> RecordSpec:
    """Resolve the descriptor one length-contract row describes.

    Parameters
    ----------
    case : RecordLengthCase
        The contract row, whose ``layout_name`` is either a registry name or ``EXPORT``.

    Returns
    -------
    RecordSpec
        The registered descriptor, or the export header descriptor for the one row the registry
        does not hold.

    Raises
    ------
    LayoutError
        Propagated from :func:`carddemo_migration.copybook.layouts.layout` if the row names a
        record the registry does not declare, which would mean this table and the registry had
        diverged.
    """
    if case.layout_name == _EXPORT_LAYOUT_NAME:
        return layouts.EXPORT_HEADER_LAYOUT
    return layouts.layout(case.layout_name)


@pytest.mark.parametrize("case", _RECORD_LENGTH_CONTRACT, ids=_LENGTH_IDS)
def test_every_record_length_is_the_one_its_copybook_declares(case: RecordLengthCase) -> None:
    """Compare each record's declared length against the contract and its own field spans.

    :param case: one row of the twelve-record length contract.
    :returns: nothing; a descriptor whose length or copybook citation disagrees is a failure.
    """
    spec = _contract_layout(case)
    assert spec.reclen == case.reclen, (
        f"{case.reader_name} declares {spec.reclen} bytes against a contracted {case.reclen}"
    )

    # WHY : Assumptions: the declared length is also proven against the SUM of the field spans,
    #   which is the arithmetic half of the double verification. A descriptor could carry the
    #   right total and describe fields that do not fill it -- the record would then decode with a
    #   hole no width check could see -- so the fields are walked for contiguity as well as total.
    offset = 0
    for field in spec.fields:
        assert field.start == offset, f"{spec.name} leaves a gap or overlap at offset {offset}"
        offset += field.length
    assert offset == case.reclen

    if case.layout_name != _EXPORT_LAYOUT_NAME:
        citation = layouts.COPYBOOK_OF[case.layout_name]
        assert citation.endswith(f"{case.copybook}.cpy"), (
            f"{case.reader_name} cites {citation} rather than {case.copybook}"
        )
        assert layouts.reclen_of(case.layout_name) == case.reclen


@pytest.mark.parametrize("case", _RECORD_LENGTH_CONTRACT, ids=_LENGTH_IDS)
def test_every_declared_record_length_divides_its_committed_extract(
    case: RecordLengthCase,
    seed_corpus: SeedCorpus,
) -> None:
    """Divide each committed extract's byte size by its declared length and require no remainder.

    :param case: one row of the twelve-record length contract.
    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a size that does not divide, or divides into the wrong count, is a failure.
    """
    if case.dataset is None:
        pytest.skip(f"{case.reader_name} has no committed extract to divide")

    size = len(seed_corpus.ebcdic_raw_bytes(case.dataset))
    # WHY : Assumptions: the DIVISION is what proves a fixed-length extract well formed, never the
    #   presence of a terminator -- none of these thirteen files holds one. Asserting the product
    #   as well as the quotient states both halves of the measurement, so a file replaced by one
    #   of a different length fails on the size rather than silently yielding a different count.
    assert size == case.reclen * case.rows, (
        f"{case.dataset} is {size} bytes against {case.rows} x {case.reclen}"
    )
    assert layouts.count_fixed_length_records(size, case.reclen) == case.rows


@pytest.mark.parametrize("case", _RECORD_LENGTH_CONTRACT, ids=_LENGTH_IDS)
def test_every_reader_yields_the_row_count_its_corpus_holds(
    case: RecordLengthCase,
    seed_corpus: SeedCorpus,
    fixture_corpus: FixtureCorpus,
) -> None:
    """Read each record's whole corpus through its own reader and count the rows produced.

    :param case: one row of the twelve-record length contract.
    :param seed_corpus: session accessor over ``app/data``.
    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a reader that drops, duplicates or invents a row is reported as a failure.
    """
    if case.dataset is None:
        # WHY : Assumptions: the posted-transaction reader is driven from the export scenario's
        #   five fixture rows, because no ``TRANSACT`` extract is committed anywhere -- the master
        #   is produced by the posting and backup pipeline rather than shipped. Skipping it here
        #   instead would leave the one reader with no corpus also with no count.
        rows = tuple(
            transaction.read_ascii_transactions(
                fixture_corpus.path("export/happy_path", "trandata.txt")
            )
        )
    elif case.reader_name == "export_record":
        rows = tuple(
            export_record.read_ebcdic_export_records(seed_corpus.ebcdic_path(case.dataset))
        )
    else:
        module = getattr(readers_package, case.reader_name)
        reader = getattr(module, f"read_ebcdic_{_PLURAL_STEMS[case.reader_name]}")
        rows = tuple(reader(seed_corpus.ebcdic_path(case.dataset)))
    assert len(rows) == case.rows, (
        f"{case.reader_name} yielded {len(rows)} rows against a contracted {case.rows}"
    )
    assert all(row for row in rows), f"{case.reader_name} yielded an empty row"


def test_the_disclosure_group_extract_carries_the_default_fallback_row(
    seed_corpus: SeedCorpus,
) -> None:
    """Confirm the disclosure-group extract holds fifty-one rows because of the DEFAULT group.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a fifty-row extract, or one with no DEFAULT group, is reported as a failure.
    """
    # WHY : Assumptions: this record's count is FIFTY-ONE where every other master in the corpus
    #   holds fifty, and the extra rows are not a rounding of the same data. They are the
    #   ``DEFAULT`` disclosure group, which the interest run falls back to whenever an account's
    #   own group key is not found, so a corpus loaded without them accrues nothing for those
    #   accounts and reports no error. Asserting the count alone would pass on fifty-one rows of
    #   the wrong kind, so the fallback group is asserted present by name as well.
    rows = tuple(discgrp.read_ebcdic_disclosure_groups(seed_corpus.ebcdic_path("DISCGRP.PS")))
    assert len(rows) == 51
    group = layouts.DISGROUP_LAYOUT.field("DIS-ACCT-GROUP-ID")
    fallback = tuple(row for row in rows if str(row["DIS-ACCT-GROUP-ID"]).strip() == "DEFAULT")
    assert fallback, "the extract carries no DEFAULT disclosure group to fall back to"
    assert len(fallback) < len(rows), "every row cannot be the fallback group"

    # WHY : Assumptions: the group identifier is space-padded to its declared width rather than
    #   trimmed, so the comparison strips before matching. A reader that returned a trimmed value
    #   would shorten a field the target column declares fixed, so the padding is asserted too.
    assert all(len(str(row["DIS-ACCT-GROUP-ID"])) == group.length for row in rows)


@pytest.mark.parametrize("layout_name", sorted(_LAYOUTS_WITHOUT_AN_ORACLE_VECTOR))
def test_a_layout_with_no_oracle_vector_is_verified_from_its_copybook_and_its_size(
    layout_name: str,
    seed_corpus: SeedCorpus,
) -> None:
    """Verify the three vector-less records from the copybook declaration and the byte division.

    :param layout_name: one of the three registry records no fixture round-trip vector covers.
    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a registry that claims a vector, or a size that fails to divide, fails.
    """
    # WHY : Trade-offs: these three are verified from the COPYBOOK declaration and the extract's
    #   exact byte-size division, which is weaker evidence than the other nine get and is the best
    #   available. The reference codec declares layouts for eight base masters only, so no
    #   cross-language oracle vector exists for the transaction type, the transaction category or
    #   the security user; a test asserting agreement with a vector this suite authored would prove
    #   only that this package agrees with itself. The division is the part no author can fake:
    #   800 = 10 x 80, 1080 = 18 x 60 and 420 = 7 x 60 hold of the committed files or they do not.
    assert not layouts.has_oracle_round_trip(layout_name), (
        f"{layout_name} now claims an oracle round-trip vector, so its verification can be"
        " strengthened from the reference corpus rather than from its copybook alone"
    )
    spec = layouts.layout(layout_name)
    case = next(row for row in _RECORD_LENGTH_CONTRACT if row.layout_name == layout_name)
    assert case.dataset is not None, f"{layout_name} must have a committed extract to divide"
    size = len(seed_corpus.ebcdic_raw_bytes(case.dataset))
    assert size == spec.reclen * case.rows
    assert layouts.count_fixed_length_records(size, spec.reclen) == case.rows
    assert layouts.COPYBOOK_OF[layout_name].endswith(f"{case.copybook}.cpy")


def test_the_other_nine_records_do_claim_an_oracle_vector() -> None:
    """Confirm exactly the three named records lack an oracle vector, and the rest declare one.

    :returns: nothing; a record silently losing or gaining a vector claim is reported as a failure.
    """
    # WHY : Assumptions: the complement is asserted so the three-name set cannot quietly grow. A
    #   record that lost its vector claim would otherwise be verified from its copybook alone with
    #   nothing recording the downgrade, which is precisely the loss of evidence the registry
    #   flag exists to make visible.
    without = frozenset(
        name for name in layouts.base_master_names() if not layouts.has_oracle_round_trip(name)
    )
    assert without == _LAYOUTS_WITHOUT_AN_ORACLE_VECTOR


def test_the_dataset_dispatch_reaches_every_reader_the_length_contract_names() -> None:
    """Resolve each contracted record through the package dispatch to the module that reads it.

    :returns: nothing; a dispatch entry missing, extra or pointing at the wrong module fails.
    """
    # WHY : Assumptions: the dispatch mapping is the orchestration contract, not a convenience.
    #   The command line's per-dataset subcommand and the batch staging step both select a reader
    #   by LAYOUT NAME through it, so a record present in the length contract but absent from the
    #   mapping is a record no pipeline step can load, however complete its reader is.
    dispatch = readers_package.DATASET_READERS
    contracted = {case.layout_name: case.reader_name for case in _RECORD_LENGTH_CONTRACT}
    assert dict(dispatch) == contracted, (
        "the dataset dispatch and the length contract name different records or different modules"
    )
    for layout_name, reader_name in contracted.items():
        module = readers_package.reader_module(layout_name)
        assert module.__name__.endswith(f".{reader_name}")

    # WHY : Assumptions: the derived layouts are asserted ABSENT from the dispatch. The reject
    #   record, the statement-ordered view and the interest-generated transaction are produced by
    #   the pipeline rather than loaded from an extract, so a dispatch entry for one of them would
    #   offer a load path for a dataset that does not exist.
    for derived in layouts.derived_names():
        assert derived not in dispatch


# ---------------------------------------------------------------------------
# The fixture corpus: two naming schemes, and seven files that are legitimately empty.
# ---------------------------------------------------------------------------

# WHY : Assumptions: five fixture file names differ from the seed tree's name for the SAME record,
#   and the difference is per domain rather than global -- the export scenario writes
#   ``trandata.txt`` and the statement scenarios write ``acctfile.txt``, ``custfile.txt``,
#   ``trnxfile.txt`` and ``xreffile.txt``, where ``app/data/ASCII`` spells the same records
#   ``dailytran.txt``, ``acctdata.txt``, ``custdata.txt`` and ``cardxref.txt``. A test that resolved
#   a fixture by the seed's name finds nothing in those two domains, which reads as a missing
#   fixture rather than as a wrong name.
_FIXTURE_NAMING: Final[tuple[tuple[str, str, str, int], ...]] = (
    ("export/happy_path", "trandata.txt", "TRAN", 350),
    ("statement/happy_path", "acctfile.txt", "ACCOUNT", 300),
    ("statement/happy_path", "custfile.txt", "CUSTOMER", 500),
    ("statement/happy_path", "trnxfile.txt", "TRNX", 350),
    ("statement/happy_path", "xreffile.txt", "XREF", 50),
)


@pytest.mark.parametrize(
    ("scenario", "dataset", "layout_name", "reclen"),
    _FIXTURE_NAMING,
    ids=[dataset for _, dataset, _, _ in _FIXTURE_NAMING],
)
def test_a_fixture_spelled_unlike_its_seed_still_resolves_to_one_record_and_width(
    scenario: str,
    dataset: str,
    layout_name: str,
    reclen: int,
    fixture_corpus: FixtureCorpus,
) -> None:
    """Resolve each differently-spelled fixture to its record and require the declared width.

    :param scenario: the ``<domain>/<scenario>`` directory holding the fixture.
    :param dataset: the fixture file name, which differs from the seed tree's name for the record.
    :param layout_name: the registry record the fixture carries.
    :param reclen: the record length that record declares.
    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a fixture resolved to the wrong record or read at the wrong width fails.
    """
    # WHY : Assumptions: the width is resolved through the corpus accessor, which maps the fixture
    #   name onto a REGISTRY record, rather than being passed to a reader as a number. That is what
    #   makes the differently-spelled names safe: a fixture and its seed can never be read at two
    #   different widths, because both widths come from the same descriptor.
    spec = fixture_corpus.record_spec(dataset)
    assert spec.name == layout_name
    assert fixture_corpus.reclen(dataset) == reclen == spec.reclen
    records = fixture_corpus.records(scenario, dataset)
    assert records, f"{scenario}/{dataset} holds no records"
    assert all(len(record) == reclen for record in records)


# WHY : Assumptions: a zero-byte fixture is VALID input that yields zero rows, not a malformed
#   record, and seven of the committed fixtures are genuinely zero bytes. Each models a real batch
#   condition -- a dataset that exists so the program opens it successfully and reads nothing. The
#   reference codec states the same rule from the other side: an empty dataset is represented by
#   the caller iterating zero rows, so it never reaches the width validator at all. A blank or
#   short ROW inside a non-empty file is the opposite case and is still refused, which the
#   companion test below asserts so the two cannot be confused for one rule.
_EMPTY_FIXTURES: Final[tuple[tuple[str, str, str, str], ...]] = (
    ("posting/empty_input", "dailytran.txt", "dalytran", "read_ascii_daily_transactions"),
    ("posting/zero_balance", "tcatbal.txt", "tcatbal", "read_ascii_category_balances"),
    ("provisioning/empty_input", "acctdata.txt", "account", "read_ascii_accounts"),
    ("provisioning/empty_input", "carddata.txt", "card", "read_ascii_cards"),
    ("provisioning/empty_input", "cardxref.txt", "xref", "read_ascii_card_xrefs"),
    ("provisioning/empty_input", "custdata.txt", "customer", "read_ascii_customers"),
    ("statement/empty_input", "trnxfile.txt", "transaction", "read_ascii_transactions"),
)


@pytest.mark.parametrize(
    ("scenario", "dataset", "reader_name", "entry_point"),
    _EMPTY_FIXTURES,
    ids=[f"{scenario.replace('/', '-')}-{dataset}" for scenario, dataset, _, _ in _EMPTY_FIXTURES],
)
def test_every_committed_zero_byte_fixture_yields_no_rows_and_no_error(
    scenario: str,
    dataset: str,
    reader_name: str,
    entry_point: str,
    fixture_corpus: FixtureCorpus,
) -> None:
    """Read each of the seven zero-byte fixtures and require an empty result rather than a refusal.

    :param scenario: the ``<domain>/<scenario>`` directory holding the empty fixture.
    :param dataset: the fixture file name.
    :param reader_name: the reader module that owns the record the fixture would carry.
    :param entry_point: the character-path read entry point to drive.
    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a reader that raises or invents a row from an empty file is a failure.
    """
    # WHY : Assumptions: the CHARACTER path is driven for all seven, because these fixtures are the
    #   text form of their records and an empty file reaches the open, the size check and the close
    #   rather than the in-memory branch an empty byte string reaches. The companion test above
    #   covers that branch, so between them both routes into the empty case are exercised.
    path = fixture_corpus.path(scenario, dataset)
    assert path.stat().st_size == 0, f"{scenario}/{dataset} is no longer empty"
    module = getattr(readers_package, reader_name)
    assert list(getattr(module, entry_point)(path)) == []


def test_the_statement_view_and_the_posted_transaction_agree_on_width() -> None:
    """Confirm the statement-ordered view and the posted-transaction record declare one width.

    :returns: nothing; two different widths would make the empty-fixture reading above unsound.
    """
    # WHY : Assumptions: the statement scenario's ``trnxfile.txt`` carries the statement-ordered
    #   view rather than the posted-transaction record, and the two differ in field naming and key
    #   width while declaring the SAME record length. Reading that scenario's empty copy through
    #   the posted-transaction reader is therefore sound only because the widths agree, so the
    #   agreement is asserted here rather than assumed at the point of use.
    assert layouts.reclen_of("TRNX") == layouts.reclen_of("TRAN")
    assert layouts.keylen_of("TRNX") != layouts.keylen_of("TRAN")


def test_no_fixture_row_carries_a_terminator_inside_its_declared_width(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Walk the whole fixture corpus and require every row to be full width and free of returns.

    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a carriage return or a short row anywhere in the corpus is a failure.
    """
    # WHY : Assumptions: the fixture corpus is uniformly newline-terminated with NO carriage
    #   return anywhere, which is the opposite of the seed tree and is why the two are asserted
    #   separately. Measuring it here means the ragged-terminator tests below are known to be
    #   about the seed tree alone, so a fixture acquiring Windows terminators surfaces as this
    #   test failing rather than as a puzzling width refusal in an unrelated scenario.
    inspected = 0
    for scenario in fixture_corpus.scenarios():
        for path in sorted(fixture_corpus.root.joinpath(scenario).glob("*.txt")):
            dataset = path.name
            raw = fixture_corpus.raw_bytes(scenario, dataset)
            assert b"\r" not in raw, f"{scenario}/{dataset} carries a carriage return"
            width = fixture_corpus.reclen(dataset)
            for record in fixture_corpus.records(scenario, dataset):
                assert len(record) == width
            inspected += 1
    assert inspected == 78, f"the fixture corpus holds {inspected} data files rather than 78"


# ---------------------------------------------------------------------------
# The short cross-reference seed, and why padding it is correct rather than lenient.
# ---------------------------------------------------------------------------


def test_the_short_cross_reference_seed_is_right_padded_to_its_declared_width(
    seed_corpus: SeedCorpus,
) -> None:
    """Prove the 36-character seed lines pad to fifty and that the missing bytes are the pad.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a padded row of the wrong width, or a pad that is not spaces, is a failure.
    """
    # WHY : Trade-offs: a SHORT row is right-padded rather than refused, and the arithmetic is the
    #   whole justification: the published fields close at offset 36 -- sixteen characters of card
    #   number, nine of customer identifier and eleven of account identifier -- and the declared
    #   record is 50, the difference being exactly the trailing ``FILLER PIC X(14)`` that carries no
    #   data and is dropped anyway. Padding on the right with spaces cannot move a field that
    #   exists, whereas refusing the row would make the ETL unable to ingest the very seed the
    #   repository ships. The accepted cost is that the reader is laxer than a strict width check.
    # WHY : Trade-offs: this DELIBERATELY differs from the Java shared kernel's codec, which
    #   refuses the same short record outright, and the divergence is not an inconsistency to be
    #   reconciled. The two guard different boundaries: this ETL ingests a physical seed file as
    #   shipped, once, whereas the Java codec guards a wire and storage contract where a short
    #   record means corruption in transit. Nothing here should be read as claiming cross-language
    #   agreement on this one behaviour; the agreement claimed elsewhere is about decoded VALUES.
    spec = layouts.XREF_LAYOUT
    published = reader_source.data_region_width(xref.LOADED_FIELDS)
    pad = spec.field("FILLER")
    assert published == 36
    assert pad.length == 14
    assert published + pad.length == spec.reclen == 50

    raw = seed_corpus.ascii_raw_bytes("cardxref.txt")
    lines = raw.split(b"\n")
    assert lines[-1] == b"", "the seed is newline-terminated, so the split leaves one empty piece"
    data_lines = lines[:-1]
    assert len(data_lines) == 50
    assert {len(line) for line in data_lines} == {published}, (
        "every seed line must carry exactly the published data region and nothing more"
    )

    records = seed_corpus.ascii_records("cardxref.txt")
    assert len(records) == 50
    for record in records:
        assert len(record) == spec.reclen
        assert record[pad.start : pad.start + pad.length] == " " * pad.length


def test_a_cross_reference_row_longer_than_its_declared_width_is_refused(
    seed_corpus: SeedCorpus,
) -> None:
    """Extend a real cross-reference row by one character and require both paths to refuse it.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; the assertion is that ``RecordLengthError`` is raised by each entry point.
    """
    # WHY : Assumptions: the tolerance is asymmetric BY DESIGN, so the long direction is asserted
    #   in the same test as the short one is proven. Truncating an over-long row to fit would move
    #   every field after the cut, and on this record it would happen to succeed silently because
    #   the record ends in dropped padding -- then fail invisibly on the first layout whose last
    #   declared field carries data. Refusing is the loud failure that keeps that from happening.
    record = seed_corpus.ascii_records("cardxref.txt")[0]
    over_long = record + " "
    assert len(over_long) == layouts.XREF_LAYOUT.reclen + 1
    with pytest.raises(layouts.RecordLengthError):
        xref.decode_ascii_card_xref(over_long)
    with pytest.raises(layouts.RecordLengthError):
        list(xref.iter_ascii_card_xrefs(over_long))


def test_the_full_width_and_short_cross_reference_forms_decode_to_the_same_rows(
    seed_corpus: SeedCorpus,
) -> None:
    """Decode the 36-character ASCII seed and the 50-byte EBCDIC extract and compare row for row.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; any decoded difference between the two physical shapes is a failure.
    """
    # WHY : Alternatives Considered: asserting the padded row's characters against the byte
    #   extract's characters, which would compare two physical images rather than two decoded
    #   rows. Rejected because the two images are legitimately different -- one is 36 characters of
    #   ASCII and the other 50 bytes of cp037 -- so a character comparison would have to encode one
    #   of them, and what the loader consumes is the DECODED row, which is the thing that must
    #   agree.
    from_text = tuple(xref.read_ascii_card_xrefs(seed_corpus.ascii_path("cardxref.txt")))
    from_bytes = tuple(xref.read_ebcdic_card_xrefs(seed_corpus.ebcdic_path("CARDXREF.PS")))
    assert len(from_text) == len(from_bytes) == 50
    assert from_text == from_bytes
    assert len(seed_corpus.ebcdic_raw_bytes("CARDXREF.PS")) == 50 * layouts.XREF_LAYOUT.reclen


def test_every_fixture_copy_of_the_cross_reference_record_is_full_width(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Confirm the eighteen cross-reference fixtures are full width or empty, never 36 characters.

    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a 36-character fixture row would mean both shapes came from one tree.
    """
    # WHY : Assumptions: BOTH physical shapes of this record must load, which is why the padding
    #   rule exists rather than one shape being declared canonical. The shipped seed is the short
    #   one and every fixture copy is the full fifty, so a reader tested only against the fixture
    #   corpus would never exercise the padding at all -- and one tested only against the seed
    #   would never exercise the full-width path. Counting the fixtures here states which side of
    #   that split the corpus sits on.
    width = layouts.XREF_LAYOUT.reclen
    counted = 0
    for scenario in fixture_corpus.scenarios():
        path = fixture_corpus.root / scenario / "cardxref.txt"
        if not path.is_file():
            continue
        counted += 1
        for record in fixture_corpus.records(scenario, "cardxref.txt"):
            assert len(record) == width
        raw = fixture_corpus.raw_bytes(scenario, "cardxref.txt")
        assert raw == b"" or {len(line) for line in raw.split(b"\n")[:-1]} == {width}
    assert counted == 18, f"the corpus holds {counted} cross-reference fixtures rather than 18"


# ---------------------------------------------------------------------------
# Ragged line endings: three seeds carry carriage returns, two of them unevenly.
# ---------------------------------------------------------------------------

# WHY : Assumptions: the raggedness is the trap, not the carriage return. ``trancatg.txt`` carries
#   CRLF on all 18 rows, while ``tcatbal.txt`` carries it on 49 of 50 and ``trantype.txt`` on 6 of
#   7 -- in both of those the FINAL row ends with a bare newline. So a reader that strips a fixed
#   number of bytes, or that selects one newline mode for a whole file, corrupts the last field of
#   most records in one file or the last record in another. Per-row detection is the only correct
#   approach, and the three files together are the proof: no per-file mode can be right for all of
#   them. The rule is to strip at most one terminator, matching a carriage-return-and-newline pair
#   FIRST, then a lone newline, then a lone carriage return -- which is exactly what the reference
#   codec's own record validator does.
_CARRIAGE_RETURN_SEEDS: Final[tuple[tuple[str, str, int, int, int, bool], ...]] = (
    ("tcatbal.txt", "TCATBALF.PS", 2599, 50, 49, False),
    ("trancatg.txt", "TRANCATG.PS", 1116, 18, 18, True),
    ("trantype.txt", "TRANTYPE.PS", 433, 7, 6, False),
)


@pytest.mark.parametrize(
    ("dataset", "twin", "size", "rows", "returns", "final_row_has_return"),
    _CARRIAGE_RETURN_SEEDS,
    ids=[dataset for dataset, *_ in _CARRIAGE_RETURN_SEEDS],
)
def test_a_carriage_return_seed_decodes_every_row_including_a_ragged_final_one(
    dataset: str,
    twin: str,
    size: int,
    rows: int,
    returns: int,
    final_row_has_return: bool,
    seed_corpus: SeedCorpus,
) -> None:
    """Decode all rows of each carriage-return seed and pin the measured terminator profile.

    :param dataset: the ASCII seed file name.
    :param twin: the EBCDIC extract of the same record, used as the row-for-row comparison.
    :param size: the seed's measured byte size, terminators included.
    :param rows: the number of data rows the seed holds.
    :param returns: the number of carriage returns measured in the seed.
    :param final_row_has_return: whether the last row ends with a carriage return as well.
    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a lost row, a mis-stripped terminator or a changed profile is a failure.
    """
    raw = seed_corpus.ascii_raw_bytes(dataset)
    assert len(raw) == size
    assert raw.count(b"\r") == returns
    assert raw.count(b"\n") == rows
    assert raw.endswith(b"\r\n" if final_row_has_return else b"\n")
    # WHY : Assumptions: the uniform file and the two ragged ones are asserted through ONE
    #   parametrisation, because the property under test is that no per-file decision is taken. A
    #   bespoke test per file would let a reader pass all three while holding three different
    #   rules, which is the failure mode the mixture exists to expose.
    assert (returns == rows) is final_row_has_return

    module = getattr(readers_package, _READER_OF_SEED[dataset])
    plural = _PLURAL_STEMS[_READER_OF_SEED[dataset]]
    from_text = tuple(getattr(module, f"read_ascii_{plural}")(seed_corpus.ascii_path(dataset)))
    from_bytes = tuple(getattr(module, f"read_ebcdic_{plural}")(seed_corpus.ebcdic_path(twin)))
    assert len(from_text) == rows
    assert from_text == from_bytes, f"{dataset} and {twin} decode to different rows"

    # WHY : Assumptions: the FINAL row is asserted specifically, over and above the row-for-row
    #   comparison, because it is the row whose terminator differs in two of these three files and
    #   the one a fixed-width strip would corrupt. Comparing the whole sequence already covers it,
    #   so this is the assertion that names it when it fails.
    assert from_text[-1] == from_bytes[-1]


def test_at_most_one_terminator_is_stripped_and_the_paired_form_is_matched_first(
    seed_corpus: SeedCorpus,
) -> None:
    """Feed one real row under all four terminator shapes and require one identical decode.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a decode that differs between terminator shapes is reported as a failure.
    """
    # WHY : Assumptions: the pair is matched FIRST and at most one terminator is removed, and both
    #   halves of that rule are load-bearing. Matching a lone newline first would leave the
    #   carriage return inside the row, making every row in the two uniform-CRLF regions one
    #   character too long -- which the over-long rule then refuses, so the whole dataset fails to
    #   load. Removing more than one would eat a trailing space, and a trailing space is DATA in a
    #   fixed-width record, so the row would then be padded back to a different value.
    record = seed_corpus.ascii_records("tcatbal.txt")[0]
    expected = tcatbal.decode_ascii_category_balance(record)
    for terminator in ("\r\n", "\n", "\r", ""):
        rows = tuple(tcatbal.iter_ascii_category_balances(record + terminator))
        assert rows == (expected,), f"a row terminated with {terminator!r} decoded differently"

    # WHY : Assumptions: a row whose last declared character is legitimately a space is the case a
    #   greedy strip destroys, so it is constructed here from a real row rather than left to the
    #   corpus to supply. The trailing pad of this record is zero-filled, so its own final character
    #   is not a space and the corpus cannot exercise the hazard on its own.
    padded = record[:-1] + " "
    assert len(padded) == layouts.TCATBAL_LAYOUT.reclen
    assert tuple(tcatbal.iter_ascii_category_balances(padded + "\r\n")) == (
        tcatbal.decode_ascii_category_balance(padded),
    )


def test_a_blank_row_inside_a_non_empty_extract_is_refused(seed_corpus: SeedCorpus) -> None:
    """Insert an empty line between two real rows and require the reader to refuse the extract.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; the assertion is that ``RecordLengthError`` names the offending line.
    """
    # WHY : Assumptions: an empty FILE and an empty LINE are different inputs and get different
    #   answers, which is why they are asserted in adjacent tests rather than one. A zero-byte
    #   dataset iterates zero rows and never reaches the width check at all; a blank line inside a
    #   non-empty dataset would pad to a record of spaces and load as a row of empty keys, so it is
    #   corrupt input and is refused.
    records = seed_corpus.ascii_records("tcatbal.txt")
    extract = f"{records[0]}\n\n{records[1]}\n"
    with pytest.raises(layouts.RecordLengthError) as refused:
        list(tcatbal.iter_ascii_category_balances(extract))
    assert "line 2" in str(refused.value)


# ---------------------------------------------------------------------------
# EBCDIC: slice by length, never split on a control byte.
# ---------------------------------------------------------------------------
# WHY : Assumptions: "EBCDIC" names two different things in this package and they must not be
#   conflated. In the codec module it is a CHARACTER ENCODING -- cp037 -- deciding which byte value
#   is which character. In the display codec it is a SIGN CONVENTION, deciding which trailing
#   character of a zoned field carries the negative sign. A record can need one, the other, both or
#   neither, and the tests below are about the first meaning except where they name the second.


def test_the_export_extract_yields_five_hundred_records_despite_its_stray_control_bytes(
    seed_corpus: SeedCorpus,
) -> None:
    """Read the export extract and require exactly 500 records despite its embedded control bytes.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; any count but 500, or a record of the wrong length, is reported as a failure.
    """
    # WHY : Assumptions: this extract is the repository's only POSITIVE proof that slicing by
    #   length rather than splitting on a terminator matters, and the evidence is measured rather
    #   than precautionary. Its 250000 bytes hold FIVE 0x0A bytes and ELEVEN 0x0D bytes at
    #   in-record offsets inside its COMP and COMP-3 spans -- one of them inside the four-byte
    #   binary sequence number in the fixed header -- so a newline-aware reader reports a handful of
    #   pieces of wildly differing lengths, each of which is a record split through a field.
    image = seed_corpus.ebcdic_raw_bytes("EXPORT.DATA.PS")
    reclen = layouts.EXPORT_HEADER_LAYOUT.reclen
    assert len(image) == 250000 == reclen * 500
    assert image.count(bytes([0x0A])) == 5
    assert image.count(bytes([0x0D])) == 11

    records = tuple(
        export_record.read_ebcdic_export_records(seed_corpus.ebcdic_path("EXPORT.DATA.PS"))
    )
    assert len(records) == 500
    sliced = seed_corpus.ebcdic_records("EXPORT.DATA.PS")
    assert len(sliced) == 500
    assert {len(record) for record in sliced} == {reclen}

    # WHY : Assumptions: the counter-case is asserted in the same test, because "500 records" alone
    #   is satisfied by an implementation that got there for the wrong reason. Splitting the same
    #   image on the newline byte yields a different number of pieces, so the two counts disagreeing
    #   is what demonstrates that the reader is not splitting.
    assert len(image.split(bytes([0x0A]))) != 500


def test_an_extract_that_does_not_divide_by_its_record_length_is_refused(
    seed_corpus: SeedCorpus,
) -> None:
    """Truncate the export extract by one byte and require the reader to refuse the whole image.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; the assertion is that ``RecordLengthError`` reports the trailing remainder.
    """
    # WHY : Assumptions: with no terminators to appeal to, the DIVISION is the only well-formedness
    #   property a fixed-length extract has. One byte is removed rather than a whole record, because
    #   a whole-record truncation still divides evenly and is indistinguishable from a shorter
    #   dataset -- only a non-dividing length can expose a final partial record being padded or
    #   silently dropped.
    image = seed_corpus.ebcdic_raw_bytes("EXPORT.DATA.PS")
    with pytest.raises(layouts.RecordLengthError) as refused:
        list(export_record.iter_ebcdic_export_records(image[:-1]))
    # WHY : Assumptions: the reported remainder is 499 rather than 1, because a remainder is what
    #   is left over AFTER the last whole record and not what was removed. Asserting the number the
    #   operator will actually read is the point of asserting the message at all -- a test that
    #   accepted any refusal would pass on a message naming the wrong quantity, which is the one
    #   fact an operator uses to decide whether the file is truncated or the length is wrong.
    assert "499 trailing bytes remain" in str(refused.value)


def test_no_computational_span_is_handed_to_a_character_decoder(seed_corpus: SeedCorpus) -> None:
    """Decode one field of each regime and require characters only where the regime is display.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a numeric or opaque span returned as characters is reported as a failure.
    """
    # WHY : Assumptions: the per-field decoder dispatches on the descriptor's REGIME before any
    #   code page is applied, and returns the untouched bytes for the three numeric regimes. That is
    #   what makes per-record decoding impossible to reach by accident: there is no signature
    #   through which a packed nibble pair or a binary word can arrive at a character decoder. The
    #   export record puts all three regimes plus an opaque overlay inside one 500-byte image, so
    #   the guarantee has to hold field by field rather than record by record.
    account_image = seed_corpus.ebcdic_records("ACCTDATA.PS")[0]
    spec = layouts.ACCOUNT_LAYOUT

    text_field = spec.field("ACCT-ACTIVE-STATUS")
    decoded_text = ebcdic_codec.decode_field(account_image, text_field)
    assert isinstance(decoded_text, str)
    assert decoded_text == account_image[text_field.start : text_field.end].decode("cp037")

    money_field = spec.field("ACCT-CURR-BAL")
    assert money_field.kind is Kind.ZONED
    decoded_money = ebcdic_codec.decode_field(account_image, money_field)
    assert isinstance(decoded_money, bytes)
    assert decoded_money == account_image[money_field.start : money_field.end]

    export_image = seed_corpus.ebcdic_records("EXPORT.DATA.PS")[0]
    header = layouts.EXPORT_HEADER_LAYOUT
    binary_field = header.field("EXPORT-SEQUENCE-NUM")
    assert binary_field.kind is Kind.BINARY
    assert isinstance(ebcdic_codec.decode_field(export_image, binary_field), bytes)

    opaque_field = header.field("EXPORT-RECORD-DATA")
    assert opaque_field.kind is Kind.OPAQUE
    assert (
        ebcdic_codec.decode_field(export_image, opaque_field)
        == (export_image[opaque_field.start : opaque_field.end])
    )

    # WHY : Assumptions: the whole-record decode is where a NUMBER appears, and it appears as an
    #   exact decimal at the field's declared scale. Asserting both halves in one test is what ties
    #   them together: the field-level call must not produce a number, and the record-level call
    #   must, so neither can be satisfied by an implementation that blurred the two.
    row = ebcdic_codec.decode_record(account_image, spec)
    assert isinstance(row[money_field.name], Decimal)
    assert -row[money_field.name].as_tuple().exponent == money_field.dec_digits


def test_every_ascii_seed_row_holds_one_byte_for_every_character(seed_corpus: SeedCorpus) -> None:
    """Require each ASCII seed row's character count to equal its byte count at the declared width.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a row whose byte length exceeds its character length is a failure.
    """
    # WHY : Assumptions: a width check counted in CHARACTERS is only equivalent to one counted in
    #   BYTES while every character is one byte wide. A single multi-byte character satisfies the
    #   character count and exceeds the byte width, which desynchronises every offset after it and
    #   still decodes to plausible values -- so the equivalence is asserted for the whole seed tree
    #   rather than assumed from the tree being called ASCII.
    for dataset in seed_corpus.ascii_datasets():
        width = seed_corpus.reclen(dataset)
        for record in seed_corpus.ascii_records(dataset):
            assert len(record) == width
            assert len(record.encode("ascii")) == width


# ---------------------------------------------------------------------------
# The security-user record: EBCDIC only, and one span that is never published.
# ---------------------------------------------------------------------------
# WHY : Assumptions: every assertion below tests the DROP rather than the value. The baseline's
#   own load job carries all ten users' credentials inline as literal text in an in-stream copy
#   step, and the target ``auth.users`` table deliberately declares no password column at all --
#   the migration's one documented refusal of parity. So the property worth pinning is that the
#   eight-byte span at offset 48 reaches no decoded mapping, no diagnostic, no log line and no
#   exception message; comparing its content to anything would require this file to hold a copy of
#   it, which is exactly what the property forbids.

#: The ten user identifiers the shipped extract carries. Identifiers only: no other field of any
#: record is reproduced here, and the type split is asserted by count rather than per user.
_SHIPPED_USER_IDS: Final[tuple[str, ...]] = (
    "ADMIN001",
    "ADMIN002",
    "ADMIN003",
    "ADMIN004",
    "ADMIN005",
    "USER0001",
    "USER0002",
    "USER0003",
    "USER0004",
    "USER0005",
)


def test_the_security_user_record_publishes_four_fields_at_their_declared_spans(
    seed_corpus: SeedCorpus,
) -> None:
    """Decode the shipped extract and require exactly the four publishable fields, at full width.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a missing field, an extra field or a trimmed value is reported as a failure.
    """
    spec = layouts.SECUSER_LAYOUT
    expected_spans = {
        "SEC-USR-ID": (0, 8),
        "SEC-USR-FNAME": (8, 28),
        "SEC-USR-LNAME": (28, 48),
        "SEC-USR-TYPE": (56, 57),
    }
    for name, (start, end) in expected_spans.items():
        field = spec.field(name)
        assert (field.start, field.end) == (start, end), f"{name} moved"

    rows = tuple(usrsec.read_ebcdic_security_users(seed_corpus.ebcdic_path("USRSEC.PS")))
    assert rows
    for row in rows:
        assert set(row) == set(expected_spans), "the published field set changed"
        for name, (start, end) in expected_spans.items():
            assert len(str(row[name])) == end - start

    # WHY : Assumptions: the withheld span sits BETWEEN two published fields rather than at either
    #   end, which is why the published set cannot be described as a prefix or a suffix of the
    #   record. Asserting the two neighbours' spans is what proves the projection skipped a span
    #   rather than truncated the record: the type field at offset 56 can only be reached by
    #   stepping over the eight bytes at 48.
    assert spec.field("SEC-USR-TYPE").start == spec.field("SEC-USR-LNAME").end + 8


def test_the_security_user_extract_holds_ten_users_split_five_and_five(
    seed_corpus: SeedCorpus,
) -> None:
    """Read the only committed form of the security file and pin its ten identifiers and type split.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a changed identifier set or type split is reported as a failure.
    """
    # WHY : Assumptions: this record ships in EBCDIC ONLY -- there is no ``app/data/ASCII``
    #   counterpart among the nine text seeds -- so its 800 bytes are the single source for every
    #   assertion about it, and 800 = 10 x 80 is what makes the count derivable without decoding a
    #   byte. A test that expected a text seed would skip silently rather than fail.
    assert "usrsec.txt" not in seed_corpus.ascii_datasets()
    reclen = layouts.SECUSER_LAYOUT.reclen
    assert len(seed_corpus.ebcdic_raw_bytes("USRSEC.PS")) == 800 == 10 * reclen

    rows = tuple(usrsec.read_ebcdic_security_users(seed_corpus.ebcdic_path("USRSEC.PS")))
    assert len(rows) == 10
    assert tuple(sorted(str(row["SEC-USR-ID"]) for row in rows)) == _SHIPPED_USER_IDS

    # WHY : Assumptions: the type split is asserted as two COUNTS rather than per user, because the
    #   property the target schema depends on is that the domain is closed and both halves occur --
    #   an administrator group and an ordinary-user group each with members. Pinning which
    #   identifier holds which type would add nothing and would tie this test to the order of a
    #   reference file it may not edit.
    types = [str(row["SEC-USR-TYPE"]) for row in rows]
    assert sorted(set(types)) == sorted(usrsec.USER_TYPE_DOMAIN) == ["A", "U"]
    assert types.count("A") == 5
    assert types.count("U") == 5


def test_the_named_trailing_pad_is_dropped_although_its_name_is_not_the_bare_pad_token(
    seed_corpus: SeedCorpus,
) -> None:
    """Require the record's named trailing pad to be dropped despite not being called ``FILLER``.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a pad reaching a decoded row, or a drop set that misses it, is a failure.
    """
    # WHY : Assumptions: this record's trailing pad is called ``SEC-USR-FILLER``, not ``FILLER``, so
    #   any drop rule written as an equality against the bare token misses it -- and the miss is
    #   silent, because the field is character data of a plausible width and would simply appear in
    #   every decoded row as twenty-three spaces. It is the only NAMED pad in the corpus, which is
    #   precisely why a rule that works for the other ten records fails on this one.
    pad = layouts.SECUSER_LAYOUT.field("SEC-USR-FILLER")
    assert pad.name != "FILLER"
    assert (pad.start, pad.length) == (57, 23)
    assert pad.start + pad.length == layouts.SECUSER_LAYOUT.reclen
    assert usrsec.DROPPED_FIELD_NAMES == frozenset({pad.name})
    assert pad.name not in {field.name for field in usrsec.LOADED_FIELDS}

    for row in usrsec.read_ebcdic_security_users(seed_corpus.ebcdic_path("USRSEC.PS")):
        assert pad.name not in row


def test_a_synthetic_security_record_never_surfaces_its_placeholder_slot(
    secuser_builder: SecUserRecordBuilder,
) -> None:
    """Build a synthetic record and require its placeholder slot to reach no output of any kind.

    :param secuser_builder: session builder producing one synthetic record with a placeholder slot.
    :returns: nothing; the placeholder appearing in a row, a rendering or a message is a failure.
    :raises LayoutError: expected of the field-scoped rendering asked for the withheld name.
    """
    # WHY : Alternatives Considered: driving this from a record taken out of the shipped extract,
    #   which every other reader test does. Rejected for this record: the shipped bytes hold real
    #   credentials, so a failing assertion would render one into a report. The synthetic builder
    #   puts an unmistakable placeholder in the eight-byte slot instead, which lets the test assert
    #   the ABSENCE of that span from every output without the assertion itself ever handling a
    #   credential -- and the placeholder matches nothing real, so a leak of it is unambiguous.
    record = secuser_builder.build(user_id="SYNTH002", user_type="A")
    assert len(record) == layouts.SECUSER_LAYOUT.reclen

    withheld = layouts.SECUSER_LAYOUT.field("SEC-USR-PWD")
    slot = record[withheld.start : withheld.end]
    assert len(slot) == withheld.length
    assert withheld.suppressed, "the descriptor must mark the span suppressed"

    encoded = secuser_builder.build_bytes(user_id="SYNTH002", user_type="A")
    row = usrsec.decode_ebcdic_security_user(encoded)
    assert withheld.name not in row
    assert slot not in {str(value) for value in row.values()}

    rendered = usrsec.render_masked_security_user_record(record)
    assert len(rendered) == layouts.SECUSER_LAYOUT.reclen
    assert slot not in rendered

    # WHY : Assumptions: the field-scoped rendering is asked for the withheld name explicitly,
    #   because a renderer that fell through to a span it has no rule for is the failure mode a
    #   whole-record assertion cannot see. The refusal must also carry no part of the span, so the
    #   message is inspected as well as the exception type.
    with pytest.raises(LayoutError) as refusal:
        usrsec.render_masked_security_user_field(record, withheld.name)
    assert slot not in str(refusal.value)


# ---------------------------------------------------------------------------
# The posted-transaction record: the one reader with no seed of its own.
# ---------------------------------------------------------------------------


def test_the_posted_transaction_offsets_are_the_ones_two_sources_corroborate() -> None:
    """Pin the three offsets the copybook sum and the report sort control independently agree on.

    :returns: nothing; a moved card-number or stamp offset is reported as a failure.
    """
    # WHY : Assumptions: these three offsets are corroborated by two unrelated sources, which is
    #   what removes the doubt from every offset-dependent decision in this package. Summing the
    #   copybook's field widths puts the card number at zero-based 262, the originating stamp at 278
    #   and the processing stamp at 304; the report's own sort control declares the card number and
    #   the processing date at one-based 263 and 305, which is the same pair. The batch alternate
    #   index over the same record is declared at offset 304 for 26 bytes, agreeing a third time.
    spec = layouts.TRAN_LAYOUT
    assert (spec.field("TRAN-CARD-NUM").start, spec.field("TRAN-CARD-NUM").length) == (262, 16)
    assert (spec.field("TRAN-ORIG-TS").start, spec.field("TRAN-ORIG-TS").length) == (278, 26)
    assert (spec.field("TRAN-PROC-TS").start, spec.field("TRAN-PROC-TS").length) == (304, 26)

    # WHY : Assumptions: the alternate key is asserted as DUPLICATES-permitted, because a processing
    #   stamp is not unique across the master -- a whole posting run shares one -- and a unique
    #   secondary index over it would refuse the second row of every run.
    alternates = {key.name: key for key in spec.alternate_keys}
    assert "TRAN-PROC-TS" in alternates
    assert (alternates["TRAN-PROC-TS"].offset, alternates["TRAN-PROC-TS"].length) == (304, 26)
    assert alternates["TRAN-PROC-TS"].duplicates


def test_an_absent_posted_transaction_extract_is_a_normal_state(tmp_path: pathlib.Path) -> None:
    """Confirm a missing posted-transaction extract reads as normal rather than as an error.

    :param tmp_path: pytest-supplied empty directory standing in for a staging area.
    :returns: nothing; a raise, or a claim that the extract is present, is reported as a failure.
    """
    # WHY : Assumptions: this record's master is REPROduced from a single primer record by its own
    #   provisioning job and then filled by the posting and backup pipeline, so there is nothing
    #   under ``app/data`` for a source argument to point at and no default to fall back on. An
    #   absent or zero-row extract is therefore the normal state before the first run, which is the
    #   shape the verification query already models as a NULL expected baseline -- so refusing it
    #   would make a correct pre-run environment unloadable.
    assert not transaction.HAS_COMMITTED_SEED_DATASET
    missing = tmp_path / "transact.ps"
    assert not transaction.seed_dataset_is_present(missing)
    assert list(transaction.read_ascii_transactions(missing)) == []
    assert list(transaction.read_ebcdic_transactions(missing)) == []


def test_the_only_committed_transaction_rows_carry_a_blank_processing_stamp(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Decode the export scenario's five rows and pin the two stamps they actually carry.

    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a refused blank stamp or a changed originating stamp is a failure.
    """
    # WHY : Alternatives Considered: synthesising transaction rows in this file, since no seed
    #   exists for the record. Rejected: the export scenario ships five real 350-byte rows that the
    #   reference programs produced, so reusing them proves the Python decoder agrees with the COBOL
    #   that wrote them, whereas a row this file assembled would prove only that the decoder agrees
    #   with this file's idea of the layout.
    spec = layouts.TRAN_LAYOUT
    records = fixture_corpus.records("export/happy_path", "trandata.txt")
    assert len(records) == 5
    assert {len(record) for record in records} == {spec.reclen}

    rows = tuple(
        transaction.read_ascii_transactions(
            fixture_corpus.path("export/happy_path", "trandata.txt")
        )
    )
    assert len(rows) == 5

    proc = spec.field("TRAN-PROC-TS")
    orig = spec.field("TRAN-ORIG-TS")
    # WHY : Assumptions: a processing stamp of 26 spaces is an INTENTIONALLY VALID unset value, not
    #   a defect in the fixture: the stamp is written by the posting run, so a row that has not been
    #   posted carries a uniformly unwritten field. All five of these rows are in that state, so a
    #   reader that required a well-formed stamp would refuse the only committed rows this record
    #   has. The originating stamp, by contrast, is deterministic business data and is identical on
    #   all five, which is what makes it usable as a fixed expectation.
    assert {str(row[proc.name]) for row in rows} == {" " * proc.length}
    assert len({str(row[orig.name]) for row in rows}) == 1
    carried = next(iter(str(row[orig.name]) for row in rows))
    assert len(carried) == orig.length
    assert carried == carried.strip(), "the originating stamp is written to its full width"


def test_a_processing_stamp_that_is_neither_blank_nor_well_formed_is_refused(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Replace a blank processing stamp with 26 characters of neither shape and require a refusal.

    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; the assertion is that ``LayoutError`` is raised and quotes no input.
    """
    # WHY : Assumptions: the two ADMISSIBLE shapes are a well-formed 26-character stamp and a
    #   uniformly unwritten one, and anything else of the right width is refused. Width alone cannot
    #   separate them, which is why the refusal exists at all: a stamp field filled with a
    #   merchant's name is exactly 26 characters and would load into a timestamp column as a cast
    #   failure at the far end of the pipeline rather than as a refusal here.
    spec = layouts.TRAN_LAYOUT
    field = spec.field("TRAN-PROC-TS")
    record = fixture_corpus.records("export/happy_path", "trandata.txt")[0]
    corrupted = record[: field.start] + "-" * field.length + record[field.end :]
    assert len(corrupted) == spec.reclen
    with pytest.raises(LayoutError) as refused:
        transaction.decode_ascii_transaction(corrupted)
    message = str(refused.value)
    assert field.describe() in message
    assert "-" * field.length not in message


def test_the_normalised_stamp_flag_comes_from_the_descriptor_and_is_not_universal() -> None:
    """Confirm the stamp asymmetry is read from the descriptor rather than re-derived per reader.

    :returns: nothing; a reader flagging both stamps, or the interest layout flagging one, fails.
    """
    # WHY : Assumptions: WHICH stamp is run-generated is a per-record property and is NOT universal.
    #   The posted-transaction and daily-transaction records flag the processing stamp alone,
    #   because their originating stamp is deterministic business data; the interest-generated
    #   transaction flags BOTH, because the run writes both. The checksum verification depends on
    #   that asymmetry to decide which fields it may compare across a rerun, so a reader that
    #   re-derived the rule by naming convention would silently exclude a comparable field.
    assert transaction.NORMALIZED_TIMESTAMP_FIELD_NAMES == frozenset({"TRAN-PROC-TS"})
    assert transaction.is_normalized_timestamp_field("TRAN-PROC-TS")
    assert not transaction.is_normalized_timestamp_field("TRAN-ORIG-TS")
    assert layouts.TRAN_LAYOUT.field("TRAN-PROC-TS").normalize_ts
    assert not layouts.TRAN_LAYOUT.field("TRAN-ORIG-TS").normalize_ts

    interest = layouts.layout("INTTRAN")
    assert interest.field("TRAN-PROC-TS").normalize_ts
    assert interest.field("TRAN-ORIG-TS").normalize_ts, (
        "the interest-generated record flags both stamps, which is why the flag is per descriptor"
    )
    assert dalytran.NORMALIZED_TIMESTAMP_FIELD_NAMES == frozenset({"DALYTRAN-PROC-TS"})


def test_a_transaction_card_number_survives_as_a_string_with_its_leading_zero(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Require the card number to stay a full-width string, including one that begins with a zero.

    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; a numeric conversion or a trimmed value is reported as a failure.
    """
    # WHY : Assumptions: the copybook declares this field as character data, not a number, and one
    #   of the five committed rows begins with a zero -- so any numeric conversion of it loses a
    #   digit and produces a fifteen-character identifier that still looks plausible. The value is
    #   read out of the corpus and compared against its own span rather than written here as a
    #   literal, because a primary account number in a test's source is rendered by every failure
    #   that test ever reports.
    spec = layouts.TRAN_LAYOUT
    field = spec.field("TRAN-CARD-NUM")
    records = fixture_corpus.records("export/happy_path", "trandata.txt")
    rows = tuple(transaction.decode_ascii_transaction(record) for record in records)
    for record, row in zip(records, rows, strict=True):
        value = row[field.name]
        assert isinstance(value, str)
        assert value == record[field.start : field.end]
        assert len(value) == field.length
    assert any(str(row[field.name]).startswith("0") for row in rows), (
        "the corpus no longer carries a leading-zero card number, so the hazard is untested"
    )


# ---------------------------------------------------------------------------
# One group name, two arities: the composite key that is declared twice.
# ---------------------------------------------------------------------------


def test_the_two_category_keys_share_a_copybook_group_name_and_nothing_else() -> None:
    """Prove the 17-byte and 6-byte category keys are separate records that share only a group name.

    :returns: nothing; equal key widths, or a shared elementary field name, is a failure.
    """
    # WHY : Assumptions: ``TRAN-CAT-KEY`` is declared in TWO copybooks with different children --
    #   17 bytes of account identifier, type code and category code in the balance record, and 6
    #   bytes of type code and category code in the category record. Descriptors are scoped
    #   per copybook and there is no global field-name map. The alternative shape, a flat registry
    #   keyed on bare field names, is the obvious one and resolves the group to the wrong arity,
    #   which mis-aligns every field after it while still returning well-formed values.
    balance = layouts.TCATBAL_LAYOUT
    category = layouts.TRANCAT_LAYOUT
    assert balance.key_length == 17
    assert category.key_length == 6
    assert balance.key_length != category.key_length

    balance_key = ("TRANCAT-ACCT-ID", "TRANCAT-TYPE-CD", "TRANCAT-CD")
    category_key = ("TRAN-TYPE-CD", "TRAN-CAT-CD")
    assert tcatbal.COMPOSITE_KEY_FIELD_NAMES == balance_key
    assert sum(balance.field(name).length for name in balance_key) == balance.key_length
    assert sum(category.field(name).length for name in category_key) == category.key_length

    # WHY : Assumptions: the group name itself appears in NEITHER descriptor, because the layouts
    #   are flattened to elementary fields -- which is the mechanical reason the collision cannot be
    #   reached. Asserting its absence is what makes that structural, rather than a convention a
    #   later edit could reintroduce a group name into.
    balance_names = {field.name for field in balance.fields}
    category_names = {field.name for field in category.fields}
    assert "TRAN-CAT-KEY" not in balance_names
    assert "TRAN-CAT-KEY" not in category_names

    # WHY : Assumptions: the only elementary name the two records share is the padding, which is
    #   dropped by both -- so no publishable field of one record can be resolved through the other,
    #   whatever their group names suggest. That is the property shared key code would violate.
    assert balance_names & category_names == {"FILLER"}
    assert "FILLER" in tcatbal.DROPPED_FIELD_NAMES
    assert "FILLER" in trancatg.DROPPED_FIELD_NAMES

    # WHY : Assumptions: the balance amount sits immediately after the composite key at zero-based
    #   17, which the category-balance report's own print positions corroborate at one-based 1, 12,
    #   14 and 18 for the three key components and the amount. Pinning the amount's offset here ties
    #   the 17-byte arity to something outside this package.
    amount = balance.field("TRAN-CAT-BAL")
    assert amount.start == balance.key_length == 17
    assert (amount.int_digits, amount.dec_digits) == (9, 2)
    assert amount.length == layouts.zoned_width(9, 2) == 11


# ---------------------------------------------------------------------------
# There is no universal fill byte.
# ---------------------------------------------------------------------------
# WHY : Assumptions: the trailing pad is SPACE-filled on five of the eleven records and
#   ZERO-filled on four of them, measured from the committed seeds. A test that assumed one fill
#   byte is therefore wrong about four of the eleven layouts -- and wrong quietly, because a pad is
#   dropped from every decoded row, so the assumption only shows up when something reads the pad
#   deliberately: a checksum over the raw record, a re-encode, or a golden comparison.
_SPACE_FILLED_SEEDS: Final[tuple[str, ...]] = (
    "acctdata.txt",
    "carddata.txt",
    "custdata.txt",
    "dailytran.txt",
    "cardxref.txt",
)
_ZERO_FILLED_SEEDS: Final[tuple[str, ...]] = (
    "discgrp.txt",
    "trancatg.txt",
    "trantype.txt",
    "tcatbal.txt",
)


def _trailing_pad(spec: RecordSpec) -> FieldSpec:
    """Return the descriptor of a record's trailing pad, whatever it is named.

    Parameters
    ----------
    spec : RecordSpec
        The record whose last field is its pad.

    Returns
    -------
    FieldSpec
        The final declared field, which every record in this corpus uses as its pad.

    Raises
    ------
    AssertionError
        If the record's final field does not close at the declared record length, which would
        mean the descriptor was not contiguous and the pad could not be identified positionally.
    """
    # WHY : Assumptions: the pad is identified POSITIONALLY -- as the last declared field -- rather
    #   than by matching its name. One record's pad is called ``SEC-USR-FILLER`` and the other ten
    #   call theirs ``FILLER``, so a name match would miss exactly the record whose pad is hardest
    #   to notice, and this helper is used by tests that must cover all of them.
    pad = spec.fields[-1]
    assert pad.start + pad.length == spec.reclen
    return pad


@pytest.mark.parametrize(
    ("dataset", "fill"),
    [(dataset, " ") for dataset in _SPACE_FILLED_SEEDS]
    + [(dataset, "0") for dataset in _ZERO_FILLED_SEEDS],
    ids=[
        *(f"space-{name}" for name in _SPACE_FILLED_SEEDS),
        *(f"zero-{name}" for name in _ZERO_FILLED_SEEDS),
    ],
)
def test_each_seed_pads_with_the_byte_its_own_family_uses(
    dataset: str,
    fill: str,
    seed_corpus: SeedCorpus,
) -> None:
    """Read each seed's trailing pad and require the fill byte its measured family uses.

    :param dataset: the ASCII seed file name.
    :param fill: the single character that seed's family pads with, space or zero.
    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a pad of the other family's byte is reported as a failure.
    """
    # WHY : Assumptions: the observed characters are accumulated over EVERY row rather than sampled
    #   from the first, because a fill byte is a property of the extract and not of one record. A
    #   single row of a zero-filled record whose pad happened to be blank would otherwise reclassify
    #   the whole dataset, and the disagreement would then be invisible.
    spec = seed_corpus.record_spec(dataset)
    pad = _trailing_pad(spec)
    observed: set[str] = set()
    for record in seed_corpus.ascii_records(dataset):
        observed.update(record[pad.start : pad.start + pad.length])
    assert observed == {fill}, f"{dataset} pads with {sorted(observed)} rather than {fill!r}"


def test_the_two_pad_families_are_disjoint_and_cover_every_seed(seed_corpus: SeedCorpus) -> None:
    """Confirm the space-filled and zero-filled families together account for all nine ASCII seeds.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a seed in neither family, or in both, is reported as a failure.
    """
    # WHY : Assumptions: the two families are asserted to PARTITION the seed tree, so a seed added
    #   or renamed cannot fall outside both lists and go unmeasured. Four of the nine are
    #   zero-filled, which is the number that makes a single-fill assumption wrong rather than
    #   merely imprecise.
    families = frozenset(_SPACE_FILLED_SEEDS) | frozenset(_ZERO_FILLED_SEEDS)
    assert frozenset(_SPACE_FILLED_SEEDS).isdisjoint(_ZERO_FILLED_SEEDS)
    assert families == frozenset(seed_corpus.ascii_datasets())
    assert len(_ZERO_FILLED_SEEDS) == 4


def test_every_pad_is_dropped_from_the_row_yet_stays_declared_in_the_descriptor() -> None:
    """Require each record's pad to be absent from the published fields and present in the layout.

    :returns: nothing; a pad missing from the descriptor, or published by a reader, is a failure.
    """
    # WHY : Assumptions: the pad is dropped from the decoded row but KEPT in the descriptor, and
    #   both halves matter. Dropping it from the row keeps padding out of the target table;
    #   keeping it in the descriptor is what keeps the field spans contiguous and the sum equal to
    #   the record length, so the geometry stays provable rather than being asserted about a record
    #   with a hole in it.
    for case in _RECORD_LENGTH_CONTRACT:
        spec = _contract_layout(case)
        pad = _trailing_pad(spec)
        module = getattr(readers_package, case.reader_name)
        published = {
            field.name
            for field in (
                module.ENVELOPE_LOADED_FIELDS
                if case.reader_name == "export_record"
                else module.LOADED_FIELDS
            )
        }
        assert pad.name in module.DROPPED_FIELD_NAMES
        assert pad.name not in published
        assert sum(field.length for field in spec.fields) == spec.reclen


# ---------------------------------------------------------------------------
# The disclosure-group rate: the only four-digit money field in the tree.
# ---------------------------------------------------------------------------


def test_the_disclosure_group_key_and_rate_spans_come_from_the_descriptor() -> None:
    """Pin the three key components and the six-byte rate against the registered descriptor.

    :returns: nothing; a moved key component or a rate of another width is reported as a failure.
    """
    spec = layouts.DISGROUP_LAYOUT
    group = spec.field("DIS-ACCT-GROUP-ID")
    assert (group.start, group.length) == (0, 10)
    assert (spec.field("DIS-TRAN-TYPE-CD").start, spec.field("DIS-TRAN-TYPE-CD").length) == (10, 2)
    assert (spec.field("DIS-TRAN-CAT-CD").start, spec.field("DIS-TRAN-CAT-CD").length) == (12, 4)
    assert spec.key_length == 16

    rate = spec.field("DIS-INT-RATE")
    # WHY : Assumptions: this rate is SIX bytes, and the number has to come from the descriptor
    #   rather than by analogy with a sibling reader. Every other money field in the corpus is a
    #   ten-or-nine-digit amount occupying eleven or twelve bytes, so an implementation reusing a
    #   money width by resemblance reads five or six bytes too many here and mis-aligns the pad --
    #   which on this record decodes without complaint, because the pad is zero-filled digits.
    assert (rate.start, rate.length) == (16, 6)
    assert rate.kind is Kind.ZONED
    assert (rate.int_digits, rate.dec_digits) == (4, 2)
    assert rate.signed
    assert rate.length == layouts.zoned_width(rate.int_digits, rate.dec_digits) == 6


def test_the_disclosure_rate_is_the_only_four_digit_money_field_in_the_registry() -> None:
    """Walk every registered record and require exactly one four-integer-digit money field.

    :returns: nothing; a second such field would mean the width above could be reached by analogy.
    """
    # WHY : Assumptions: the claim that this width is unique is asserted over the WHOLE registry
    #   rather than stated in prose, because uniqueness is what makes the width worth pinning: while
    #   it holds, nothing else in the tree can be read at six bytes by resemblance, and if a second
    #   such field ever appears this test is where the claim stops being true.
    found = [
        (spec.name, field.name)
        for name in layouts.names()
        for spec in (layouts.layout(name),)
        for field in spec.fields
        if field.kind is Kind.ZONED and (field.int_digits, field.dec_digits) == (4, 2)
    ]
    assert found == [("DISGROUP", "DIS-INT-RATE")]


def test_the_disclosure_rate_decodes_to_an_exact_decimal_from_both_corpora(
    seed_corpus: SeedCorpus,
) -> None:
    """Decode the rate from both encodings and require exact decimals at the declared scale.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a float, a wrong scale or a rate outside the declared capacity is a failure.
    """
    # WHY : Assumptions: money is exact fixed point at every hop, so the rate is asserted to be a
    #   ``Decimal`` at the declared scale rather than compared numerically to a literal. A float
    #   comparison would pass on a value that had already lost precision, which is the whole class
    #   of defect the fixed-point rule exists to prevent.
    rate = layouts.DISGROUP_LAYOUT.field("DIS-INT-RATE")
    ceiling = Decimal(10) ** rate.int_digits
    for path in (
        seed_corpus.ascii_path("discgrp.txt"),
        seed_corpus.ebcdic_path("DISCGRP.PS"),
    ):
        reader = (
            discgrp.read_ascii_disclosure_groups
            if path.suffix == ".txt"
            else discgrp.read_ebcdic_disclosure_groups
        )
        rows = tuple(reader(path))
        assert len(rows) == 51
        for row in rows:
            value = row[rate.name]
            assert isinstance(value, Decimal)
            assert -value.as_tuple().exponent == rate.dec_digits
            assert abs(value) < ceiling


# ---------------------------------------------------------------------------
# The two records that carry no money at all, and the values they must never emit.
# ---------------------------------------------------------------------------


def test_neither_the_card_nor_the_customer_record_carries_a_money_field() -> None:
    """Confirm the card and customer records declare no fixed-point money field of any regime.

    :returns: nothing; a money field appearing on either record is reported as a failure.
    """
    # WHY : Assumptions: neither record reaches the display-decimal codec at all, and one field
    #   invites the opposite conclusion. The customer's credit score is an unsigned three-digit
    #   display integer, not currency -- a reader that classified it as money would decode it at a
    #   scale of two and turn a score into a value a hundred times smaller, which is well inside the
    #   plausible range for the column and so would not look wrong anywhere downstream.
    for spec in (layouts.CARD_LAYOUT, layouts.CUSTOMER_LAYOUT):
        assert not [field for field in spec.fields if field.dec_digits], (
            f"{spec.name} now declares a scaled field, so it reaches the money path"
        )
        assert not [
            field for field in spec.fields if field.kind in {Kind.ZONED, Kind.PACKED, Kind.BINARY}
        ]
    score = layouts.CUSTOMER_LAYOUT.field("CUST-FICO-CREDIT-SCORE")
    assert score.kind is Kind.UINT
    assert (score.start, score.length, score.dec_digits) == (329, 3, 0)


def test_the_card_verification_value_never_renders_as_itself(seed_corpus: SeedCorpus) -> None:
    """Require the verification value to reach every output as a constant marker, never as digits.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a rendering or repr that reproduces the decoded value is a failure.
    """
    # WHY : Assumptions: this value is PROTECTED rather than suppressed, and the distinction is what
    #   the assertions below have to respect. The target card table declares a column for it, so a
    #   reader that never decoded the field could not populate it -- suppression would deliver a
    #   card table whose verification values were absent rather than protected, which nothing
    #   downstream can repair. So the field IS decoded, into a wrapper whose every rendering
    #   route yields one marker, and the property to assert is that no route yields the digits.
    field = layouts.CARD_LAYOUT.field("CARD-CVV-CD")
    assert (field.start, field.length) == (27, 3)
    assert field.sensitive
    assert card.PROTECTED_FIELD_NAMES == frozenset({field.name})

    record = seed_corpus.ascii_records("carddata.txt")[0]
    row = card.decode_ascii_card(record)
    protected = row[field.name]
    assert isinstance(protected, card.ProtectedValue)
    digits = record[field.start : field.end]
    for rendering in (str(protected), repr(protected), f"{protected}", format(protected)):
        assert rendering == card.ProtectedValue.MARKER
        assert digits not in rendering

    # WHY : Assumptions: the masked whole-record rendering is checked for the primary account number
    #   and the embossed name as well, because those two are the fields a card record leaks if the
    #   masking is applied to the wrong spans -- and unlike the verification value they are long
    #   enough that a partial match would not be a coincidence.
    rendered = card.render_masked_card_record(record)
    for name in ("CARD-NUM", "CARD-EMBOSSED-NAME"):
        span = layouts.CARD_LAYOUT.field(name)
        assert record[span.start : span.end] not in rendered


def test_the_customer_record_redacts_every_identifying_field_it_declares(
    seed_corpus: SeedCorpus,
) -> None:
    """Require the customer masking to replace the identifier, name and date-of-birth spans.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; any of the named spans surviving the rendering verbatim is a failure.
    :raises LayoutError: expected of the field renderer asked for a name the record does not
        declare.
    """
    # WHY : Assumptions: the four spans named here are the ones whose disclosure is regulated rather
    #   than merely inconvenient -- the national identifier, the government-issued identifier, the
    #   date of birth and the three name fields -- so they are asserted individually rather than by
    #   iterating whatever the descriptor happens to mark sensitive. Iterating the flags would make
    #   this test agree with any classification, including one that had lost a field.
    spec = layouts.CUSTOMER_LAYOUT
    regulated = {
        "CUST-SSN": (279, 288),
        "CUST-GOVT-ISSUED-ID": (288, 308),
        "CUST-DOB-YYYY-MM-DD": (308, 318),
        "CUST-FIRST-NAME": (9, 34),
        "CUST-MIDDLE-NAME": (34, 59),
        "CUST-LAST-NAME": (59, 84),
    }
    record = seed_corpus.ascii_records("custdata.txt")[0]
    rendered = customer.render_masked_customer_record(record)
    assert len(rendered) == spec.reclen
    for name, (start, end) in regulated.items():
        field = spec.field(name)
        assert (field.start, field.end) == (start, end), f"{name} moved"
        assert field.sensitive, f"{name} is no longer classified sensitive"
        original = record[start:end]
        if original.strip():
            assert original not in rendered, f"{name} survived the masked rendering"

    # WHY : Assumptions: a diagnostic must not echo raw record bytes either, so the field-scoped
    #   refusal for a name the record does not declare is checked to carry none of the record.
    with pytest.raises(LayoutError) as refusal:
        customer.render_masked_customer_field(record, "CUST-NOT-A-FIELD")
    assert record[:16] not in str(refusal.value)


# ---------------------------------------------------------------------------
# The export record: one header, five overlays, three numeric regimes.
# ---------------------------------------------------------------------------


def test_the_export_header_and_payload_spans_sum_to_the_declared_record_length() -> None:
    """Pin each header field's span and require the header plus the payload to make 500 bytes.

    :returns: nothing; a moved header field or a payload of another length is reported as a failure.
    """
    spec = layouts.EXPORT_HEADER_LAYOUT
    expected = (
        ("EXPORT-REC-TYPE", 0, 1, Kind.TEXT),
        ("EXPORT-TIMESTAMP", 1, 26, Kind.TEXT),
        ("EXPORT-SEQUENCE-NUM", 27, 4, Kind.BINARY),
        ("EXPORT-BRANCH-ID", 31, 4, Kind.TEXT),
        ("EXPORT-REGION-CODE", 35, 5, Kind.TEXT),
        ("EXPORT-RECORD-DATA", 40, 460, Kind.OPAQUE),
    )
    assert tuple((f.name, f.start, f.length, f.kind) for f in spec.fields) == expected
    assert 1 + 26 + 4 + 4 + 5 + 460 == spec.reclen == 500
    assert layouts.EXPORT_PAYLOAD_OFFSET == 40

    # WHY : Assumptions: the sequence number is a four-byte binary fullword holding nine declared
    #   digits, not a display field -- which is why one of the extract's stray 0x0A bytes sits
    #   inside it. Pinning the width against the binary width function rather than a bare 4 ties
    #   it to the same derivation the codec uses, so the two cannot disagree about a fullword.
    sequence = spec.field("EXPORT-SEQUENCE-NUM")
    assert sequence.int_digits == 9
    assert sequence.length == packed.binary_width(9, 0) == 4
    assert (layouts.EXPORT_KEY_OFFSET, layouts.EXPORT_KEY_LENGTH) == (
        sequence.start,
        sequence.length,
    )


def test_the_five_export_overlays_alias_one_payload_and_each_fills_it() -> None:
    """Require each of the five record-type overlays to describe exactly the 460-byte payload.

    :returns: nothing; an overlay of another length, or a missing discriminator, is a failure.
    """
    # WHY : Assumptions: the five overlays REDEFINE one another, so they ALIAS the same storage
    #   rather than accumulating length: the record is a discriminated union of five 460-byte
    #   shapes, not a flat 2300-byte record. Asserting each overlay's own sum AND that the payload
    #   span stays 460 states both halves -- an implementation laying the overlays end to end would
    #   satisfy the first assertion and fail the second.
    payload = layouts.EXPORT_HEADER_LAYOUT.field("EXPORT-RECORD-DATA")
    assert sorted(layouts.EXPORT_RECORD_TYPES) == ["A", "C", "D", "T", "X"]
    for discriminator, branch in layouts.EXPORT_RECORD_TYPES.items():
        assert branch.reclen == payload.length == 460, f"overlay {discriminator} is not the payload"
        assert sum(field.length for field in branch.fields) == 460
        assert layouts.export_branch(discriminator) is branch
        offset = 0
        for field in branch.fields:
            assert field.start == offset, f"overlay {discriminator} has a gap at {offset}"
            offset += field.length
        assert offset == payload.length
    assert len(layouts.EXPORT_RECORD_TYPES) * payload.length != payload.length


def test_the_export_reader_reaches_all_three_numeric_decoders(seed_corpus: SeedCorpus) -> None:
    """Decode a real record of each type and require display, packed and binary fields to appear.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a regime absent from the decoded output, or a non-decimal value, fails.
    """
    # WHY : Assumptions: this is the only record in the corpus that reaches all three numeric
    #   codecs, because its overlays mix display amounts, COMP-3 amounts and COMP identifiers inside
    #   one image. Asserting the three regimes on real records is what proves the dispatch works per
    #   field: a decoder that applied one regime to the whole payload would still return numbers.
    images = {}
    for image in seed_corpus.ebcdic_records("EXPORT.DATA.PS"):
        images.setdefault(export_record.record_type(image), image)
    assert sorted(images) == ["A", "C", "D", "T", "X"]

    seen: set[Kind] = set()
    for discriminator, image in sorted(images.items()):
        row = export_record.decode_ebcdic_export_record(image)
        for field in export_record.branch_loaded_fields(discriminator):
            if field.name not in row:
                continue
            seen.add(field.kind)
            if field.kind in {Kind.ZONED, Kind.PACKED, Kind.BINARY}:
                value = row[field.name]
                assert isinstance(value, Decimal), f"{field.name} decoded to {type(value).__name__}"
                assert -value.as_tuple().exponent == field.dec_digits
    assert {Kind.ZONED, Kind.PACKED, Kind.BINARY} <= seen

    # WHY : Assumptions: the declared widths of the four computational shapes this record uses are
    #   pinned against the codec's own width functions rather than as bare numbers, so the record
    #   and the codec cannot disagree about how many bytes a picture clause occupies.
    assert packed.packed_width(10, 2) == 7
    assert packed.packed_width(3, 0) == 2
    assert packed.binary_width(9, 0) == 4
    assert packed.binary_width(11, 0) == 8
    assert layouts.zoned_width(10, 2) == 12


def test_a_binary_span_is_never_decoded_at_the_packed_width(seed_corpus: SeedCorpus) -> None:
    """Read a real binary field at the packed width and require the packed codec to refuse it.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; the assertion is that ``PackedDecimalError`` is raised at the wrong width.
    """
    # WHY : Assumptions: the token ``COMP-3`` CONTAINS the token ``COMP``, so a picture-clause
    #   parser matching the shortest token first reads every packed field as binary and every
    #   binary field as packed. The widths differ -- a ten-digit scaled amount is seven bytes packed
    #   and eight bytes binary -- so the mistake shifts the next field by one byte and cascades
    #   through the rest of the record. This test drives the mistake deliberately at a real span, so
    #   the refusal is evidence about the corpus rather than about an invented buffer.
    branch = layouts.export_branch("A")
    binary_field = branch.field("EXP-ACCT-CURR-CYC-DEBIT")
    assert binary_field.kind is Kind.BINARY
    assert binary_field.length == packed.binary_width(10, 2) == 8
    assert packed.packed_width(10, 2) == 7 != binary_field.length

    image = next(
        record
        for record in seed_corpus.ebcdic_records("EXPORT.DATA.PS")
        if export_record.record_type(record) == "A"
    )
    payload = image[layouts.EXPORT_PAYLOAD_OFFSET :]
    span = payload[binary_field.start : binary_field.start + packed.packed_width(10, 2)]
    assert len(span) == 7
    with pytest.raises(packed.PackedDecimalError):
        packed.decode_packed(span, 10, 2, signed=True)

    # WHY : Assumptions: the correctly-widthed read is asserted in the same test, so the refusal
    #   above cannot be explained by the span being undecodable in principle. Reading the same field
    #   through its own regime returns an exact decimal at the declared scale.
    value = packed.decode_binary_field(payload, binary_field)
    assert isinstance(value, Decimal)
    assert -value.as_tuple().exponent == binary_field.dec_digits


@pytest.mark.parametrize("discriminator", sorted(layouts.EXPORT_RECORD_TYPES))
def test_every_export_overlay_masks_the_sensitive_fields_it_declares(
    discriminator: str,
    seed_corpus: SeedCorpus,
) -> None:
    """Render a real record of each type and require every sensitive span to be replaced.

    :param discriminator: the record-type character selecting one of the five overlays.
    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a sensitive value surviving a rendering verbatim is reported as a failure.
    """
    # WHY : Assumptions: masking is asserted PER OVERLAY because each overlay declares its own
    #   sensitive set -- a primary account number and an embossed name on the card shape, a national
    #   identifier and a date of birth on the customer shape, an amount and a card number on the
    #   transaction shape. A single rendering test on one record type would leave four overlays
    #   unproven while appearing to cover the record, which is the gap a discriminated union
    #   invites.
    image = next(
        record
        for record in seed_corpus.ebcdic_records("EXPORT.DATA.PS")
        if export_record.record_type(record) == discriminator
    )
    rendered = export_record.render_masked_export_record(image)
    assert rendered["EXPORT-REC-TYPE"] == discriminator

    branch = layouts.export_branch(discriminator)
    published = {field.name for field in export_record.branch_loaded_fields(discriminator)}
    payload = image[layouts.EXPORT_PAYLOAD_OFFSET :]
    sensitive = 0
    for field in branch.fields:
        if field.name not in published or not field.sensitive:
            continue
        sensitive += 1
        assert field.name in rendered, f"{field.name} has no rendering on overlay {discriminator}"
        if field.kind not in factory.TEXT_DECODABLE_KINDS:
            continue
        original = payload[field.start : field.start + field.length].decode("cp037")
        if original.strip():
            assert original not in rendered[field.name], (
                f"{field.name} survived the masked rendering of overlay {discriminator}"
            )
    assert sensitive, f"overlay {discriminator} declares no sensitive field to mask"

    # WHY : Assumptions: the verification value is the one field with NO rendering at all on the
    #   card overlay, because the target has no column for it and a field with no destination has no
    #   reason to be decoded. Asserting its absence per overlay is what keeps a later edit from
    #   restoring it through the branch that happens not to be covered elsewhere.
    for withheld in export_record.SUPPRESSED_FIELD_NAMES:
        assert withheld not in rendered


def test_the_export_reader_publishes_no_character_decode_path() -> None:
    """Require the export reader to expose byte entry points only, and no text form at all.

    :returns: nothing; a published character entry point for this record is reported as a failure.
    """
    # WHY : Assumptions: this record has NO character form to publish, and the reason is structural
    #   rather than a policy like the security record's. Its 460-byte payload carries COMP-3 amounts
    #   and COMP identifiers whose bytes are not characters in any code page, so a text entry point
    #   could only be fed an image somebody had already destroyed. The security record's trio is
    #   withheld because its characters are a credential; this one's does not exist because the
    #   characters do not.
    for withdrawn in ("decode_ascii_export_record", "iter_ascii_export_records"):
        assert not hasattr(export_record, withdrawn)
        assert withdrawn not in export_record.__all__
    assert not [name for name in export_record.__all__ if "ascii" in name]
    for published in ("decode_ebcdic_export_record", "iter_ebcdic_export_records"):
        assert callable(getattr(export_record, published))
        assert published in export_record.__all__

    # WHY : Assumptions: the record is absent from the layout registry as well, which is what makes
    #   the shared reader factory unable to build a character path for it by accident: the factory
    #   is driven by registry name, and this record has none. Asserting the absence keeps that
    #   mechanism visible instead of leaving it to be inferred from the reader's surface.
    assert _EXPORT_LAYOUT_NAME not in layouts.names()
    assert _EXPORT_LAYOUT_NAME in readers_package.DATASET_READERS


@pytest.mark.parametrize("case", _RECORD_LENGTH_CONTRACT, ids=_LENGTH_IDS)
def test_every_decoded_row_holds_exactly_the_fields_its_reader_publishes(
    case: RecordLengthCase,
    seed_corpus: SeedCorpus,
    fixture_corpus: FixtureCorpus,
) -> None:
    """Compare each decoded row's key set against the published field names for that record.

    :param case: one row of the twelve-record length contract.
    :param seed_corpus: session accessor over ``app/data``.
    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; an unexpected key, or a published field missing from a row, is a failure.
    """
    # WHY : Assumptions: the expectation is the reader's OWN published field tuple, so this test is
    #   an agreement check between two surfaces rather than a restatement of a field list. A reader
    #   whose projection dropped a field would satisfy a hand-written list that had been edited to
    #   match it; it cannot satisfy its own declared inventory.
    if case.reader_name == "export_record":
        image = seed_corpus.ebcdic_records("EXPORT.DATA.PS")[0]
        discriminator = export_record.record_type(image)
        expected = {field.name for field in export_record.ENVELOPE_LOADED_FIELDS} | {
            field.name for field in export_record.branch_loaded_fields(discriminator)
        }
        row = export_record.decode_ebcdic_export_record(image)
        assert set(row) <= expected
        assert set(row) >= {field.name for field in export_record.ENVELOPE_LOADED_FIELDS}
        return

    module = getattr(readers_package, case.reader_name)
    published = {field.name for field in module.LOADED_FIELDS}
    if case.dataset is None:
        rows = tuple(
            transaction.read_ascii_transactions(
                fixture_corpus.path("export/happy_path", "trandata.txt")
            )
        )
    else:
        plural = _PLURAL_STEMS[case.reader_name]
        reader = getattr(module, f"read_ebcdic_{plural}")
        rows = tuple(reader(seed_corpus.ebcdic_path(case.dataset)))
    assert rows
    spec = _contract_layout(case)
    dropped = set(module.DROPPED_FIELD_NAMES)
    suppressed = set(getattr(module, "SUPPRESSED_FIELD_NAMES", frozenset()))
    for row in rows:
        assert set(row) == published, f"{case.reader_name} decoded {sorted(set(row) ^ published)}"
        assert dropped.isdisjoint(row)
        assert suppressed.isdisjoint(row)
    # WHY : Assumptions: the three name sets are asserted to PARTITION the descriptor, which is what
    #   makes "the pad is dropped" a complete statement rather than a claim about one field. A field
    #   in none of the three would be silently unread, and nothing in a decoded row could reveal it.
    assert published | dropped | suppressed == {field.name for field in spec.fields}
