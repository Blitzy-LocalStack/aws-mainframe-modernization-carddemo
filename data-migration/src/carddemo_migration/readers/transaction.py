"""Streaming reader for the CardDemo transaction master, in both shipped encodings.

Purpose
-------
Turn the transaction master extract into decoded records the Aurora loaders
and the verification passes can consume, one record at a time, from either of the two forms the
baseline ships: the
line-oriented ASCII seed and the fixed-length EBCDIC dataset. Both entry points yield the same
decoded shape, so a caller can swap corpora and compare the two without adapting to a second
contract.

This module follows the contract ``carddemo_migration.readers.account`` established and differs
from it only in which record descriptor it names. Where the reasoning behind a step is identical
to that module's it is referenced rather than restated, because two copies of one justification
drift apart and then one of them is wrong; where this record differs, the difference is recorded
here at the point it matters.

What this module reads
---------------------
The record is ``TRAN-RECORD`` as declared in ``app/cpy/CVTRA05Y.cpy``, and its byte geometry is
resolved exclusively through ``TRAN_LAYOUT`` in ``carddemo_migration.copybook.layouts``.
Assumptions: this record has NO committed seed dataset in either tree, and that is a
property of the baseline rather than a gap here -- the transaction master is produced by
the posting pipeline, not shipped as an initial load, so ``app/data`` holds the DAILY
transactions it consumes and not the master it writes. The corpora this reader is
exercised against are therefore the committed fixtures under ``tests/fixtures/export``
and, at run time, the backup and combined generations the batch chain stages to object
storage. Both entry points are still published, because a staged generation arrives as
a fixed-length byte image and a fixture arrives as text.

An absent source is a NORMAL state, not a failure
-------------------------------------------------
This is the one reader whose input may legitimately not exist, and it is the property that
distinguishes it from its eleven siblings. Because no extract ships, the two file-taking entry
points treat an ABSENT path exactly as they treat an EMPTY one: they yield zero records and
raise nothing. :data:`HAS_COMMITTED_SEED_DATASET` states the fact and
:func:`seed_dataset_is_present` is the guard both of them apply, so the policy is a decision
taken in one named place rather than a by-product of how an iterator happens to behave.

Assumptions: ``data-migration/sql/verify/row_counts.sql`` already encodes the same fact from the
other end. Its baseline for ``ledger.transactions`` is ``NULL`` rather than zero, on the stated
grounds that the table is filled by the posting job, so "a correct fresh load cannot read as a
failure". A reader that raised on the missing input would contradict the verification pass that
is meant to check it, and the contradiction would surface as a failed migration rather than as
the disagreement it actually is.

Assumptions: nothing is SYNTHESISED to stand in for the absent extract. The baseline's own
primer record is available -- ``app/jcl/TRANFILE.jcl`` copies it at L67-L74 -- and emitting it,
or any zero-filled placeholder, is rejected in :func:`seed_dataset_is_present`. It would insert
a row the source does not contain, which the row-count and money-total passes would then
correctly report as a parity failure.

The two-timestamp asymmetry
---------------------------
``TRAN-ORIG-TS`` at offset 278 and ``TRAN-PROC-TS`` at offset 304 are the same width and the
same regime, and they must NOT be treated alike. The originating stamp is deterministic business
data copied from the input transaction; the processing stamp is written from the run clock by the
posting program and is the only value in this record that differs between two runs over identical
data. ``data-migration/README.md`` section 10.1 fixes that as the contract for a checksum, which
must exclude the second and must not exclude the first.

Which stamp is which is read from the DESCRIPTOR's ``normalize_ts`` mark and is never re-derived
from a field name written here. The mark is surfaced as
:data:`NORMALIZED_TIMESTAMP_FIELD_NAMES`, its complement as :data:`DETERMINISTIC_FIELD_NAMES`,
and :func:`is_normalized_timestamp_field` answers for a single field, so a verification pass
partitions the record by reading the mark rather than by carrying a list of its own.

A published ``TRAN-PROC-TS`` is also VALIDATED rather than trusted: an unwritten stamp is
accepted, and a stamp that is neither unwritten nor well formed is refused. See
:func:`_require_timestamp_shape` for the rule and for why refusing beats blanking.

Assumptions: an INTEREST transaction record decodes identically through this reader.
``INTTRAN`` is registered separately in the layouts module to carry DERIVED provenance
and its own alternate key, and it declares the same copybook, the same 350-byte width
and the same field geometry as this record -- the two differ only in provenance and in
which timestamps a golden comparison normalises, neither of which affects decoding. A
thirteenth reader for it would therefore have been a second statement of one layout.

This record also carries the ``TRANSACT.VSAM.AIX`` batch access path: the processing
timestamp at offset 304 is the alternate key ``app/jcl/TRANIDX.jcl`` builds, and the
target replaces it with a non-unique secondary index on the same column. The descriptor
carries that alternate key, so a caller reads it from there.

Decoded shape
-------------
A decoded record is a ``dict`` keyed by the field name exactly as the copybook spells it, in
declaration order, which is the record's byte order. A character field maps to its characters at
full declared width, untrimmed. An unsigned display field maps to its characters too,
once they are proven to be the digits its picture clause requires, so the leading zeroes
in the four-digit category code and the nine-digit merchant identifier survive.
The one signed display field maps to an exact
:class:`decimal.Decimal` at the scale its picture clause declares.
The trailing pad is absent; see :data:`DROPPED_FIELD_NAMES`.

That shape is deliberately the one ``carddemo_migration.copybook.ebcdic_codec.decode_record``
publishes, minus the pad, which is what makes the two encodings comparable rather than merely
similar.

Transaction exposure control
----------------------------
Two of this record's thirteen data fields are marked sensitive by the descriptor: the
primary account number and the amount. Both are **emitted** in the decoded mapping,
because the ledger table stores both, and both are **redacted** in every diagnostic
this module produces, with the account number revealing at most its trailing four
digits. The transaction identifier, the type and category codes, the source, the
description, the merchant identity and the two timestamps stay verbatim: none of them
designates a cardholder, and they are what makes a masked transaction diff readable.
Diagnostics never echo record content of any kind, sensitive or otherwise.

Design decisions (WHY)
----------------------
Assumptions:
    **Every offset, length, storage regime and record length is imported, never declared.**
    This module states no byte position of its own, and in particular states no key width: the
    16-byte key is read from the descriptor's own ``key_offset`` and ``key_length``. That is
    the Python analogue of compiling every COBOL program against a single ``cobc -I app/cpy``
    include path. The consequence of breaking it is specific and undetectable -- a re-declared
    offset lets this reader and a sibling reading the same bytes drift apart, and a record read
    one byte out of alignment still decodes to plausible characters, so nothing raises and no
    test fails.
Trade-offs:
    **Records are streamed, never materialised.** Both entry points are generators, so memory is
    constant in the record count. The accepted cost is a single forward pass; what it buys is
    that this reader behaves identically on the committed seed and on a production extract many
    orders larger, so the one proven against the seed is the one that runs.
Assumptions:
    **Money is exact fixed point and there is no ``float`` in this module.** An IEEE-754 binary
    value cannot represent ten cents exactly, so a total accumulated in one drifts from the total
    the baseline computed -- silently, and by an amount that grows with the row count. Every
    signed display field therefore leaves this module as a :class:`decimal.Decimal` at its
    declared scale.
Trade-offs:
    **Standard library plus ``carddemo_migration.copybook``, and nothing else.** No database
    driver, no AWS SDK and no character-set package is imported here, so this reader imports and
    runs on a bare checkout with no credential configured. Choosing a dataset, opening a
    connection and writing rows belong to a loader.
"""

from __future__ import annotations

import pathlib
import string
from collections.abc import Iterable, Iterator, Mapping
from decimal import Decimal
from typing import Final

# WHY : Assumptions: these imports are absolute and rooted at the distribution package rather
#   than relative, and the record descriptor named below is the ONLY statement of this record's
#   geometry anywhere in the package. A relative import is how the single-sourcing guarantee gets
#   broken quietly: a module moved between `readers/` and `loaders/` keeps importing successfully
#   but against a different sibling, and this project's ruff configuration bans relative imports
#   outright for that reason.
# WHY : Assumptions: the descriptor is selected by NAME and never by record length, and for this
#   record that is a correctness requirement rather than a preference. `CVTRA05Y` and `CVTRA06Y`
#   are byte-for-byte identical in geometry -- same widths, same offsets, the same 350-byte record
#   length -- and differ only in the prefix on every field name, so `TRAN_LAYOUT` and
#   `DALYTRAN_LAYOUT` are indistinguishable by width. A length-based lookup would return whichever
#   of the two was registered first, decode every field to the right characters under the wrong
#   names, and give a loader a row it would insert into the wrong table with no width check left
#   to catch it. The layouts module additionally registers `TRNX_LAYOUT`, `REJECT_LAYOUT` and
#   `INTTRAN_LAYOUT` over related geometry; this module reads `TRAN_LAYOUT` alone.
# WHY : Assumptions: two offsets in this descriptor are load-bearing beyond the record itself, and
#   three independent sources agree on both. Summing the copybook field widths puts
#   `TRAN-CARD-NUM` at zero-based 262 and `TRAN-PROC-TS` at 304; `app/jcl/TRANREPT.jcl` L41-L42
#   declares them as ONE-based DFSORT positions 263 and 305; and `app/jcl/TRANFILE.jcl` L84 builds
#   the `TRANSACT.VSAM.AIX` alternate index with a zero-based `KEYS(26 304)`. Those are the two
#   columns the target replaces with `idx_transactions_card_num` and `idx_transactions_proc_ts`,
#   so an off-by-one here would break the report's sort and every by-card lookup while still
#   decoding to plausible digits.
from carddemo_migration.copybook.ebcdic_codec import decode_record, iter_ebcdic_records
from carddemo_migration.copybook.layouts import (
    TRAN_LAYOUT,
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    iter_ascii_text_records,
    mask_field,
    mask_record,
)
from carddemo_migration.copybook.zoned import decode_zoned_field

__all__ = [
    "DETERMINISTIC_FIELD_NAMES",
    "DROPPED_FIELD_NAMES",
    "HAS_COMMITTED_SEED_DATASET",
    "LOADED_FIELDS",
    "NORMALIZED_TIMESTAMP_FIELD_NAMES",
    "TRAN_LAYOUT",
    "DecodedTransaction",
    "decode_ascii_transaction",
    "decode_ebcdic_transaction",
    "is_normalized_timestamp_field",
    "iter_ascii_transactions",
    "iter_ebcdic_transactions",
    "read_ascii_transactions",
    "read_ebcdic_transactions",
    "record_key",
    "render_masked_transaction_field",
    "render_masked_transaction_record",
    "seed_dataset_is_present",
]

# WHY : Assumptions: the decoded value type is a union of exactly two members because this
#   record declares three storage regimes and two of them -- character and unsigned
#   display -- yield characters while the third yields an exact decimal. The union
#   deliberately excludes `bytes`, which the record decoder can return for a
#   mixed-regime area, because this record declares no such area; admitting it here
#   would oblige every caller to narrow a case the layout makes unreachable.
DecodedTransaction = dict[str, str | Decimal]

# WHY : Assumptions: the trailing pad is identified by NAME and not by position, and the
#   convention was measured rather than assumed: across all fourteen registered layouts every
#   record declares exactly one pad, named either `FILLER` or, where the copybook qualified it,
#   with that word as its final hyphenated component -- which is exactly this record's case for
#   the security file and is why a positional rule would have had to special-case it. Testing the
#   name keeps this module free of any byte position of its own.
_PAD_FIELD_NAME: Final[str] = "FILLER"
_PAD_NAME_SUFFIX: Final[str] = f"-{_PAD_FIELD_NAME}"


def _is_padding_field(field: FieldSpec) -> bool:
    """Report whether a field is the record's trailing pad rather than data.

    Purpose
    -------
    Decide, from the field's declared name alone, whether it exists only to fill the record out
    to its fixed length. This is the single place that judgement is made, so the projection and
    the record of what was dropped cannot disagree about it.

    Parameters
    ----------
    field : FieldSpec
        The field descriptor under test. Only its ``name`` is consulted.

    Returns
    -------
    bool
        ``True`` when the field is a pad and must be dropped from a decoded record; ``False``
        for every field that carries data.

    Raises
    ------
    None
    """
    return field.name == _PAD_FIELD_NAME or field.name.endswith(_PAD_NAME_SUFFIX)


# WHY : Assumptions: the pad is DROPPED from every decoded record because its 20 trailing
#   bytes pad the record out to its fixed 350-byte length and carry no data. The EBCDIC
#   record decoder deliberately returns it, stating that dropping it is a projection decision
#   belonging to the reader that maps a record onto a table, so this is that decision and this is
#   where it is taken. Both names below are published rather than kept private so the drop is a
#   fact a caller and a verification pass can assert, instead of a silent omission that would
#   make a decoded record a partial description of the bytes it came from.
LOADED_FIELDS: Final[tuple[FieldSpec, ...]] = tuple(
    field for field in TRAN_LAYOUT.fields if not _is_padding_field(field)
)
DROPPED_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in TRAN_LAYOUT.fields if _is_padding_field(field)
)

# WHY : Assumptions: WHICH stamp is non-deterministic is read off the DESCRIPTOR's
#   `normalize_ts` mark and is never re-derived from a field name written here. The asymmetry is
#   load-bearing rather than incidental: `TRAN-ORIG-TS` is copied from the input transaction and
#   does not vary between runs, whereas `TRAN-PROC-TS` is stamped from the run clock by the posting
#   program. A checksum or a golden comparison must leave the second out of its span and must NOT
#   leave the first out, and `data-migration/README.md` section 10.1 fixes that as the contract:
#   the pass reads the mark rather than carrying its own list of offsets. Deriving the same fact
#   twice is how the two come to disagree, and the disagreement would be silent -- a digest that
#   included the wall-clock stamp differs between two loads of identical data, which reads as a
#   data defect rather than as the measurement defect it is.
# WHY : Trade-offs: both sets are PUBLISHED rather than kept private, and the complement is
#   published as an ORDERED tuple rather than as a set. A digest is computed over an explicit
#   ordered sequence of field names precisely because a mapping's iteration order is a property of
#   how it was built, so handing a caller a set would oblige the caller to choose an order -- and
#   two callers choosing differently would compute two incomparable digests of identical data. The
#   order here is the descriptor's declaration order, which is the record's byte order. Together
#   the two names partition the published fields exactly, so a caller can assert the split rather
#   than trust it.
NORMALIZED_TIMESTAMP_FIELD_NAMES: Final[frozenset[str]] = frozenset(
    field.name for field in LOADED_FIELDS if field.normalize_ts
)
DETERMINISTIC_FIELD_NAMES: Final[tuple[str, ...]] = tuple(
    field.name for field in LOADED_FIELDS if not field.normalize_ts
)

# WHY : Assumptions: this record ships NO extract of its own, in either encoding, and the fact is
#   published rather than left in prose so a caller can branch on it instead of hard-coding the
#   same conclusion. Measured: `app/data/ASCII/` holds nine seeds and none of them is a transaction
#   master, and `app/data/EBCDIC/` holds thirteen datasets and none of them is either.
#   `app/jcl/TRANFILE.jcl` defines the cluster at L49-L54 and then primes it at L67-L74 by copying
#   `AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`, which is a single 350-byte record, so even the baseline job
#   loads no master data here -- the content is written later by the posting and backup pipeline.
#   `data-migration/README.md` marks this record separately from the ten that do ship an extract
#   for exactly this reason.
HAS_COMMITTED_SEED_DATASET: Final[bool] = False


def is_normalized_timestamp_field(field_name: str) -> bool:
    """Report whether one named field of this record is the wall-clock stamp.

    Purpose
    -------
    Answer, for a single field, the question a checksum or a golden comparison has to ask before
    it includes that field in a compared span: is this value written from the run clock, and
    therefore expected to differ between two runs over the same data?

    Parameters
    ----------
    field_name : str
        The field name exactly as the copybook spells it.

    Returns
    -------
    bool
        ``True`` when the descriptor marks the field as a run-clock stamp a parity comparison may
        blank; ``False`` for every field carrying deterministic data, the originating stamp
        included.

    Raises
    ------
    LayoutError
        If no field of that name is declared by this record. Raised by the descriptor's own
        lookup.
    """
    # WHY : Assumptions: the flag is read off the descriptor for the NAMED field rather than
    #   tested against the published set, so an unknown or misspelled name RAISES here instead of
    #   answering `False`. `False` is the answer that means "this field is deterministic", so a
    #   typo would otherwise place a wall-clock stamp inside a checksummed span, and the digest
    #   would then differ between two loads of identical data with nothing naming the cause.
    return TRAN_LAYOUT.field(field_name).normalize_ts


def seed_dataset_is_present(path: pathlib.Path) -> bool:
    """Report whether a named transaction source exists to be read.

    Purpose
    -------
    Decide, in one named place, whether there is anything at ``path`` to decode. It is the guard
    both file-taking entry points apply, so this record's defining property -- that its input may
    legitimately not exist -- is an explicit decision rather than an accident of how an iterator
    behaves when handed a missing file.

    Assumptions: an absent source is a NORMAL state for this record and for no sibling. No extract
    ships in either encoding, so there is nothing under ``app/data`` for a caller to point at and
    no default path to fall back on; the content is produced by the posting and backup pipeline.
    ``data-migration/sql/verify/row_counts.sql`` encodes the same fact as a ``NULL`` baseline
    rather than a count of zero, on the stated grounds that a correct fresh load must not read as
    a failure. Raising here would make this reader contradict the verification pass written to
    check it.

    Alternatives Considered: standing in for the absent extract rather than reporting it absent.
    Two stand-ins were available and both are rejected. The baseline's own primer record is
    committed and could be emitted, and a zero-filled record of the declared width could be
    synthesised. Either would put a row into the target that the source does not contain, and the
    row-count and money-total passes would then correctly report a parity failure -- so the cost of
    the convenience is a false alarm in the checks that exist to catch real ones.

    Alternatives Considered: reporting absence rather than raising and letting the caller catch.
    Catching would work, but it makes every caller responsible for knowing which of the twelve
    readers has an optional input, and a caller that guarded the wrong exception type would turn a
    normal state into a stopped load. A predicate states the fact once where the fact is known.

    Parameters
    ----------
    path : pathlib.Path
        The exact source to test. Nothing is opened, read or decoded here, and no directory is
        searched: the caller names the file.

    Returns
    -------
    bool
        ``True`` when a readable file exists at ``path``; ``False`` when nothing is there, in
        which case the caller yields no records and reports no error.

    Raises
    ------
    OSError
        If the path cannot be inspected at all, which is a broken mount or a permission fault
        rather than an absent dataset, and is therefore left to propagate.
    """
    # WHY : Trade-offs: the test is `is_file` and not `exists`, so a DIRECTORY at this path
    #   reports absent rather than readable. The alternative -- treating any existing entry as a
    #   source -- would defer the failure to the open, which reports a directory as a permission
    #   or type error and names neither this record nor the policy that admitted it. A directory
    #   where a dataset was expected is a caller mistake, and reporting no records for it is the
    #   same answer this function gives for the case the mistake resembles.
    # WHY : Assumptions: a ZERO-BYTE file is deliberately NOT filtered out here and is reported
    #   present. Both iterators already yield nothing for an empty source -- the text one produces
    #   no line and the fixed-length one divides zero bytes into zero records -- so the empty case
    #   needs no special handling and gets none. Adding one would put a second, redundant statement
    #   of "no records" in this module, which is how the two would come to disagree about, for
    #   instance, a file holding a single stray separator.
    return path.is_file()


# WHY : Assumptions: an unwritten stamp has exactly TWO uniform forms, and this module recognises
#   the same two the package's timestamp authority does. `copybook.ebcdic_codec.decode_timestamp`
#   documents both on measured evidence: a processing stamp is blank wherever the posting run that
#   writes it has not yet run, and the shipped primer record `AWS.M2.CARDDEMO.DALYTRAN.PS.INIT` was
#   never written at all, so its stamp spans hold low values. Recognising only one of the two would
#   refuse a form the baseline itself produces, and this record is written by exactly the pipeline
#   that produces both.
_BLANK: Final[str] = " "
_LOW_VALUE: Final[str] = "\x00"

# WHY : Assumptions: these are positions WITHIN one decoded stamp and the characters admitted at
#   them -- not record geometry, of which this module still states none, and the stamp's own width
#   is taken from its descriptor rather than written here. The rule admits the union of four
#   separators at these six positions so that ONE rule covers both dialects CardDemo emits: the
#   `YYYY-MM-DD HH:MM:SS.ffffff` form the posting program writes, and the
#   `YYYY-MM-DD-HH.MM.SS.NNNNNN` form the interest program takes from the current date -- and the
#   interest program writes rows into THIS record, so both dialects genuinely arrive here. Every
#   other position is a digit. A dialect-specific matcher would have to know which program wrote
#   the record, which a reader cannot know from the bytes it was handed.
_TIMESTAMP_SEPARATOR_OFFSETS: Final[frozenset[int]] = frozenset({4, 7, 10, 13, 16, 19})
_TIMESTAMP_SEPARATOR_CHARACTERS: Final[frozenset[str]] = frozenset("-.: ")

# WHY : Trade-offs: the digit test is this explicit ASCII set rather than the string method that
#   reads more naturally. That method also answers true for a superscript and for the digit forms
#   of other scripts, so a mis-decoded span could satisfy it while holding characters no timestamp
#   column can parse. The accepted cost is one more name; what it buys is that the check means what
#   it says on BOTH paths, including the byte path, where which characters appear is decided by the
#   code page rather than by anything a caller controls.
_TIMESTAMP_DIGITS: Final[frozenset[str]] = frozenset(string.digits)


def _is_uniformly(value: str, character: str) -> bool:
    """Report whether every position of a value holds one given character.

    Purpose
    -------
    Recognise one of the two uniform forms an unwritten fixed-width stamp takes, as a single test
    both forms are checked through, so the two cannot be recognised by slightly different rules.

    Parameters
    ----------
    value : str
        The decoded field characters to test.
    character : str
        The single character the whole value must consist of.

    Returns
    -------
    bool
        ``True`` when the value is non-empty and every position equals ``character``; ``False``
        otherwise, an empty value included.

    Raises
    ------
    None
    """
    # WHY : Assumptions: an EMPTY value must not read as uniform, which is why the emptiness test
    #   is here rather than left to the generator. A test over no positions is vacuously true, so
    #   without this an empty span would be accepted as an unwritten stamp -- and an empty span is
    #   a field that was sliced wrongly, which is the opposite of a stamp nobody has written yet.
    return bool(value) and all(position == character for position in value)


def _is_timestamp_position(offset: int, character: str) -> bool:
    """Report whether one position of a stamp holds a character its shape admits there.

    Purpose
    -------
    Express the timestamp shape as a per-position rule, so the whole-value test reads as the
    quantifier it is and the two kinds of position are decided in one named place.

    Parameters
    ----------
    offset : int
        The zero-based position WITHIN the stamp, not within the record.
    character : str
        The single character at that position.

    Returns
    -------
    bool
        ``True`` when a separator position holds one of the admitted separators, or a
        non-separator position holds a digit; ``False`` otherwise.

    Raises
    ------
    None
    """
    if offset in _TIMESTAMP_SEPARATOR_OFFSETS:
        return character in _TIMESTAMP_SEPARATOR_CHARACTERS
    return character in _TIMESTAMP_DIGITS


def _require_timestamp_shape(value: str, field: FieldSpec) -> str:
    """Require a run-clock stamp to be either unwritten or a well-formed timestamp.

    Purpose
    -------
    Validate the one field of this record whose value a parity comparison is allowed to blank,
    BEFORE it is published, so that a corrupt stamp is reported rather than carried into a load
    and then blanked out of the very comparison that would have caught it.

    Assumptions: an unwritten stamp is a LEGITIMATE value and not a decode failure. COBOL leaves a
    stamp it has not written in the state the record was initialised to, and this record is the one
    the posting run writes, so a row staged before that run carries the blank span its input
    carried. Treating either uniform form as corrupt would refuse records the baseline considers
    valid.

    Trade-offs: a stamp that is NEITHER unwritten NOR well formed is refused rather than accepted
    or quietly blanked. Blanking by position would paper over exactly two faults a financial
    pipeline must surface: a record whose bytes are corrupt, and a reader whose offsets have moved
    -- and this stamp sits at offset 304, which is the alternate-index key, so a moved offset here
    still yields plausible characters while breaking the access path the report depends on. The
    cost accepted is that a genuinely new timestamp dialect fails loudly here instead of passing
    through, which is the cheaper of the two failures because it names the field.

    Trade-offs: a span holding a MIXTURE of the two unwritten forms falls through to the same
    refusal, deliberately. Only a uniform span denotes a stamp nobody has written; a mixture is one
    partly written or partly overwritten, and no writer in this corpus produces one. The package's
    timestamp authority refuses that case for the same reason, so accepting it here would make the
    reader and that codec disagree about what an unwritten stamp is.

    Parameters
    ----------
    value : str
        The decoded characters of the stamp, at the field's full declared width.
    field : FieldSpec
        The descriptor for the stamp, supplying its declared width for the check and its geometry
        for the diagnostic. Nothing about the field is taken from anywhere else.

    Returns
    -------
    str
        ``value`` unchanged. Nothing is normalised, blanked or reformatted here: the mark on the
        descriptor says which field a COMPARISON may blank, and producing that rendering belongs to
        the comparison, because a reader that blanked on the way through would leave the parity
        check unable to show what actually differed.

    Raises
    ------
    LayoutError
        If the value is not the field's declared width, or is neither uniformly unwritten nor a
        well-formed timestamp in either dialect.
    """
    if len(value) == field.length:
        if _is_uniformly(value, _BLANK) or _is_uniformly(value, _LOW_VALUE):
            return value
        if all(_is_timestamp_position(offset, character) for offset, character in enumerate(value)):
            return value

    # WHY : Trade-offs: the refusal names the field's GEOMETRY and the declared width it failed
    #   against, and quotes NO part of the value -- not the offending character and not its
    #   position. `LayoutError` is chosen over the two nearer alternatives for reasons that are not
    #   stylistic: a record-length error would misdescribe a record of exactly the right width whose
    #   CONTENT is wrong, and the display-numeric codec's error belongs to a numeric regime this
    #   character field does not declare, so borrowing it would send a reader looking in the wrong
    #   codec. It is a `ValueError` either way, so a caller guarding that base type still catches
    #   it.
    raise LayoutError(
        f"field {field.describe()} of record {TRAN_LAYOUT.name} holds neither a well-formed"
        f" {field.length}-character timestamp nor a uniformly unwritten span, so either the stamp"
        " is corrupt or the field offsets have moved; it is refused rather than accepted or"
        " blanked, because blanking it would remove the evidence from the comparison that would"
        " otherwise have reported it"
    )


def _field_containing(offset: int) -> FieldSpec | None:
    """Find the declared field whose byte span covers a record offset.

    Purpose
    -------
    Let a failure report WHICH field a bad byte falls in, using the layout's own spans, so a
    diagnostic can be specific about location without quoting any record content.

    Parameters
    ----------
    offset : int
        A zero-based offset into the record.

    Returns
    -------
    FieldSpec | None
        The descriptor whose half-open span contains ``offset``, or ``None`` when the offset lies
        beyond the declared record; the fields cover the record contiguously, so ``None`` means
        the offset itself is out of range.

    Raises
    ------
    None
    """
    for field in TRAN_LAYOUT.fields:
        if field.start <= offset < field.end:
            return field
    return None


def _require_single_byte_record(record: str, number: int) -> str:
    """Require a record whose character count provably equals its byte count.

    Purpose
    -------
    Close the one width failure the shared text-record iterator cannot see. That iterator
    enforces the declared length in CHARACTERS, which is the right check for text; this one
    additionally proves every character occupies a single byte, so the character contract also
    holds at the byte level the field offsets are expressed in.

    Parameters
    ----------
    record : str
        One whole record, already cut to the declared length by the shared iterator.
    number : int
        The one-based record number within the source, reported so a rejection names the row
        that failed.

    Returns
    -------
    str
        ``record`` unchanged, once every character is proven to be single-byte.

    Raises
    ------
    RecordLengthError
        If any character is outside the single-byte range. The record's declared width would
        then differ from its width in bytes.
    """
    # WHY : Assumptions: a multi-byte character satisfies a CHARACTER-count check while occupying
    #   more than one byte, so it passes the shared iterator's declared-length test and then
    #   desynchronises every offset after it -- and because a record read one byte out of
    #   alignment still decodes to plausible characters, nothing later would raise. This check,
    #   and every other validation in this module, is enforced by an explicit `raise` and never by
    #   an `assert`: running the interpreter with `-O` strips assert statements outright, so an
    #   assertion is a validation that disappears in exactly the deployment where a misaligned
    #   record costs something.
    if record.isascii():
        return record

    offset = next(index for index, char in enumerate(record) if not char.isascii())
    field = _field_containing(offset)

    # WHY : Trade-offs: the message names the record number, the zero-based offset and the
    #   containing field's geometry, and it quotes NO part of the record -- not the offending
    #   character and not its code point. That shows exactly WHERE the record failed while
    #   emitting none of its content, so the diagnostic is safe to log wherever its consumer
    #   sends it. The reference codec does echo the offending character; the stricter form is
    #   adopted here because this record holds a primary account number
    #   at offset 262 and an amount at 132, and a reader cannot know which fields of a
    #   future record are sensitive, so no content is echoed at all.
    location = "beyond the declared record" if field is None else f"in field {field.describe()}"
    raise RecordLengthError(
        f"record {number} of {TRAN_LAYOUT.name} holds a character outside the single-byte"
        f" range at zero-based offset {offset}, {location}; a multi-byte character satisfies"
        f" the {TRAN_LAYOUT.reclen}-character width check while occupying more bytes, which"
        " moves every field offset after it, so the record is rejected rather than decoded"
    )


def _decode_text_field_value(record: str, field: FieldSpec) -> str | Decimal:
    """Decode one field of a character record, routing it by its declared storage regime.

    Purpose
    -------
    Produce one field's final value from a record that is already characters. Each regime this
    record declares is named explicitly and routed to the codec that owns it, and anything else
    is refused.

    Parameters
    ----------
    record : str
        One whole record at its declared character width.
    field : FieldSpec
        The descriptor supplying the offset, the width and the regime. Nothing about the field's
        position is taken from anywhere else.

    Returns
    -------
    str | Decimal
        The field's characters at full declared width for a character field, and the same for
        an unsigned display field once its digits are proven; an exact
        :class:`decimal.Decimal` at the field's declared scale for the signed display
        field.

    Raises
    ------
    LayoutError
        If the field declares a storage regime that cannot occur in a character record, which is
        any computational or mixed-regime area, or if a run-clock stamp holds neither an unwritten
        span nor a well-formed timestamp.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    if field.kind is Kind.TEXT:
        characters = record[field.start : field.end]
        # WHY : Assumptions: the stamp check is driven by the descriptor's `normalize_ts` mark, so
        #   the ORIGINATING stamp -- which the descriptor does not mark -- passes through as
        #   ordinary business data while the RUN-CLOCK one is validated. Naming a field here
        #   instead would restate a fact the descriptor already carries, and the two would then be
        #   free to disagree with the verification pass, which reads the same mark.
        if field.normalize_ts:
            return _require_timestamp_shape(characters, field)
        return characters

    if field.kind is Kind.UINT:
        # WHY : Trade-offs: the decimal this returns is DISCARDED and the characters are returned
        #   instead, which costs one parse whose result is thrown away. What it buys is the content
        #   check the picture clause states -- an unsigned display field holding a letter is
        #   refused rather than passed through -- while keeping the value a digit string of
        #   declared width. An integer would drop a leading zero, and a leading zero is
        #   significant in every identifier this corpus declares as `PIC 9`.
        # WHY : Assumptions: this check runs on BOTH paths of this reader. The EBCDIC path gets it
        #   as a by-product of having to decode a code page at all; performing it here keeps the
        #   two entry points enforcing ONE contract, so the same defective record cannot decode
        #   differently depending on which corpus it arrived in.
        decode_zoned_field(record, field)
        return record[field.start : field.end]

    if field.kind is Kind.ZONED:
        # WHY : Alternatives Considered: the FIELD-oriented entry point is called rather than the
        #   span-oriented one, which would have meant slicing here and passing the digit counts and
        #   the sign flag as three separate arguments. The field-oriented form derives all of them
        #   from this descriptor and slices by it too, so this module restates no part of the
        #   geometry. Passing the descriptor also lets that codec name the field in its own
        #   diagnostics and honour its sensitivity flag.
        # WHY : Assumptions: the result is an exact decimal at the scale the picture clause
        #   declares and is never converted to a binary floating-point type. A binary float cannot
        #   represent ten cents exactly, so a total accumulated in one drifts from the total the
        #   baseline computed, by an amount that grows with the row count.
        return decode_zoned_field(record, field)

    # WHY : Assumptions: every regime this record declares is named explicitly above and anything
    #   else raises, rather than the last branch doubling as a default. A computational or
    #   mixed-regime area cannot be read from a character record at all: its bytes are packed
    #   nibbles, machine words or a differently-described overlay, and a character decode of them
    #   succeeds, keeps the declared width and yields plausible text, so the damage would be
    #   invisible. Raising here also means a regime added to this record later cannot fall
    #   silently into whichever branch happens to be last.
    raise LayoutError(
        f"field {field.describe()} of record {TRAN_LAYOUT.name} declares a storage regime"
        " that cannot be decoded from a character record; only the character and display regimes"
        " can, and a computational or mixed-regime area must be read from the byte image through"
        " the EBCDIC record decoder"
    )


def _project_decoded_fields(
    values: Mapping[str, str | Decimal | bytes],
) -> DecodedTransaction:
    """Drop the trailing pad from a fully decoded record.

    Purpose
    -------
    Apply the projection that separates the bytes on disk from the columns a row carries, so both
    encodings converge on the same published shape, and fail closed on a value the published
    shape cannot hold.

    Parameters
    ----------
    values : Mapping[str, str | Decimal | bytes]
        Every declared field of one record, keyed by field name, including the pad. The parameter
        admits the record decoder's own wider value union.

    Returns
    -------
    DecodedTransaction
        The same mapping without any padding field, in
        declaration order.

    Raises
    ------
    LayoutError
        If a retained field decoded to raw bytes, which means the descriptor has acquired a
        computational or mixed-regime area this reader publishes no representation for.
    """
    # WHY : Trade-offs: an unexpected `bytes` value is REJECTED rather than filtered out. The
    #   filter would be justified by this record declaring no computational area today, and that
    #   is true -- but the moment a descriptor edit or a decoder change made it false, a filter
    #   would silently OMIT a real field and hand a loader a plausible partial row whose missing
    #   column inserts as NULL with nothing reporting it. Failing closed converts an invisible
    #   data-integrity fault into a named one.
    # WHY : Trade-offs: the pad is dropped BEFORE the type is judged, so a pad that decoded to
    #   bytes cannot raise. The pad is not published, so its regime is not this reader's contract
    #   to enforce, and raising over a value nobody receives would turn a harmless descriptor
    #   detail into a load failure.
    projected: DecodedTransaction = {}
    for name, value in values.items():
        if name in DROPPED_FIELD_NAMES:
            continue
        if isinstance(value, bytes):
            # WHY : Trade-offs: the rejection names the field's GEOMETRY through the descriptor
            #   and never renders the bytes, not even as a length-bounded excerpt, because an
            #   unexpected computational span could sit anywhere in this record including over a
            #   field the disclosure policy protects. Naming the field is enough to locate the
            #   defect, which is in the descriptor rather than in the data.
            raise LayoutError(
                f"field {TRAN_LAYOUT.field(name).describe()} of record"
                f" {TRAN_LAYOUT.name} decoded to raw bytes rather than a published value, so"
                " it declares a computational or mixed-regime area; such an area must be decoded"
                " by the codec that owns its regime rather than dropped from the row"
            )
        projected[name] = value
    return projected


def _require_validated_timestamps(values: DecodedTransaction) -> DecodedTransaction:
    """Apply the run-clock stamp policy to an already projected record.

    Purpose
    -------
    Give the byte path the same stamp validation the character path applies field by field, so a
    corrupt processing timestamp is refused whichever corpus the record arrived in.

    Parameters
    ----------
    values : DecodedTransaction
        One projected record, keyed by field name, as :func:`_project_decoded_fields` returns it.
        It is a freshly built mapping, so the marked field is rewritten in place rather than the
        whole record copied.

    Returns
    -------
    DecodedTransaction
        The same mapping, with every run-clock stamp proven to be either unwritten or well formed.
        No value is altered: the policy validates and returns, it does not normalise.

    Raises
    ------
    LayoutError
        If a marked stamp decoded to a non-character value, or holds neither an unwritten span nor
        a well-formed timestamp.
    """
    # WHY : Alternatives Considered: this is a separate pass over the MARKED fields rather than a
    #   branch inside the projection loop, and it iterates the published mark rather than every
    #   field. The projection has one job -- drop the pad and refuse an unpublishable value -- and a
    #   second condition inside it would run on all thirteen fields to reach the one that needs it.
    #   Both paths still call ONE policy function, which is what makes it impossible for the two
    #   encodings to enforce different rules; splitting the POLICY rather than the call site is the
    #   drift this arrangement exists to avoid.
    for name in NORMALIZED_TIMESTAMP_FIELD_NAMES:
        field = TRAN_LAYOUT.field(name)
        value = values[name]

        # WHY : Trade-offs: a marked field that did not decode to characters is REFUSED rather
        #   than skipped. A skip would be justified by this record declaring its stamps as
        #   character data today, which it does -- but the moment a descriptor edit made that
        #   false, a skip would silently stop validating the one field a comparison is allowed to
        #   blank, and an unvalidated stamp is exactly what the blanking would then hide. The
        #   package's timestamp codec refuses a non-character stamp for the same reason.
        if not isinstance(value, str):
            raise LayoutError(
                f"field {field.describe()} of record {TRAN_LAYOUT.name} is marked as a"
                " run-clock stamp but decoded to a value that is not characters, so its"
                " descriptor no longer declares the character regime a timestamp is written in"
            )
        values[name] = _require_timestamp_shape(value, field)
    return values


def record_key(record: str) -> str:
    """Return the primary key of one character record, sliced by the descriptor.

    Purpose
    -------
    Expose the key a loader upserts on and a verification pass groups by, taken from the
    descriptor's own key offset and length rather than from a width written here.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.

    Returns
    -------
    str
        The key characters at their full declared width, untrimmed, so a significant leading
        zero survives.

    Raises
    ------
    RecordLengthError
        If the record is shorter than the declared width, which would make the sliced key short.
    """
    # WHY : Assumptions: the key is sliced by `key_offset` and `key_length` from the descriptor,
    #   never by a literal. Writing the width here would be a second statement of it, and a key
    #   sliced one character short still looks like a key -- it collides with a sibling record
    #   instead of raising, which a loader would resolve as an upsert onto the wrong row.
    if len(record) < TRAN_LAYOUT.reclen:
        raise RecordLengthError(
            f"a {TRAN_LAYOUT.name} record of {len(record)} characters is shorter than the"
            f" declared {TRAN_LAYOUT.reclen}, so its"
            f" {TRAN_LAYOUT.key_length}-character key cannot be sliced"
        )
    start = TRAN_LAYOUT.key_offset
    return record[start : start + TRAN_LAYOUT.key_length]


def decode_ascii_transaction(
    record: str,
    *,
    number: int = 1,
) -> DecodedTransaction:
    """Decode one transaction record from the ASCII seed form.

    Purpose
    -------
    Turn a single full-width character record into the published decoded shape: every data field
    keyed by its copybook name, with the trailing pad dropped.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared record width, with no line terminator. Records
        at the declared width are what the shared text-record iterator yields.
    number : int
        The one-based record number within the source, used only so a rejection names the row
        that failed. Defaults to 1 for a caller decoding a record in isolation.

    Returns
    -------
    DecodedTransaction
        One entry per data field, keyed by the field name exactly as the copybook spells it, in
        declaration order. The pad is absent.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width, or holds a character outside the single-byte
        range.
    LayoutError
        If a declared field's storage regime cannot be decoded from a character record, or a
        run-clock stamp holds neither an unwritten span nor a well-formed timestamp.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Trade-offs: a record of the WRONG width is rejected here rather than padded or cut to
    #   fit. Truncation would silently discard real data and padding would invent it, and either
    #   way the row would still decode to well-formed characters, so a wrong-width record would
    #   load under a misread key with nothing reporting it. The accepted cost is that a genuinely
    #   malformed source stops the load instead of loading partially.
    if len(record) != TRAN_LAYOUT.reclen:
        raise RecordLengthError(
            f"record {number} of {TRAN_LAYOUT.name} is {len(record)} characters against a"
            f" declared width of {TRAN_LAYOUT.reclen}; a record of the wrong width means the"
            " field offsets have moved, so it is rejected rather than padded or truncated"
        )

    checked = _require_single_byte_record(record, number)

    # WHY : Assumptions: the fields are walked in the descriptor's declaration order, which is the
    #   record's byte order, so the resulting mapping iterates the record left to right. The pad
    #   is excluded by iterating the published field tuple rather than by decoding every field and
    #   filtering afterwards, which also avoids decoding 20 bytes of pad on every record.
    return {field.name: _decode_text_field_value(checked, field) for field in LOADED_FIELDS}


def iter_ascii_transactions(
    source: str | Iterable[object],
) -> Iterator[DecodedTransaction]:
    """Decode the ASCII seed form of the transaction master, one record at a time.

    Purpose
    -------
    Stream decoded records from character data the caller already holds or is already iterating,
    delegating every record boundary decision to the shared text-record iterator.

    Parameters
    ----------
    source : str | Iterable[object]
        The seed data as either the whole text, or an iterable whose every element is one LINE,
        with or without its terminator. An open text stream is such an iterable. A byte object is
        refused, because no character encoding is guessed here.

    Returns
    -------
    Iterator[DecodedTransaction]
        Each record in order, in the published decoded shape. A source with no records yields
        nothing at all.

    Raises
    ------
    LayoutError
        If the source is a byte object, is neither text nor iterable, or produces an element that
        is not a line, if a field declares a regime a character record cannot hold, or if a
        run-clock stamp holds neither an unwritten span nor a well-formed timestamp.
    RecordLengthError
        If a line is longer than the declared record width, or a record holds a character outside
        the single-byte range.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the record cut is DELEGATED and not written again here. That iterator
    #   owns one statement of the text-mode contract for the whole package: it splits on the
    #   separator, removes at most one trailing carriage return and separator per row so trailing
    #   blanks stay data, drops the single phantom empty piece a text ending in a separator
    #   produces, right-pads a short line, and REJECTS an over-long one. A second implementation
    #   here is exactly the drift this dependency edge exists to prevent, and it would be
    #   invisible: two readers stripping terminators slightly differently both return well-formed
    #   records.
    # WHY : Trade-offs: that iterator's tolerance for a SHORT line -- right-padding it
    #   with blanks -- is inherited deliberately. Padding on the right cannot move a field
    #   that is present, and the characters added are precisely the pad a text form
    #   omitted; the committed fixtures for this record are already full width, so the
    #   tolerance does not engage.
    records = iter_ascii_text_records(source, TRAN_LAYOUT.reclen)

    for number, record in enumerate(records, start=1):
        yield decode_ascii_transaction(record, number=number)


def read_ascii_transactions(path: pathlib.Path) -> Iterator[DecodedTransaction]:
    """Stream the transaction master from an ASCII seed file at an explicit path.

    Purpose
    -------
    Open one named seed file, stream its records through the shared text-record contract, and
    close it when the caller stops reading.

    Parameters
    ----------
    path : pathlib.Path
        The exact source file to read. The caller names the file; this function never searches a
        directory for it. The file need not exist: see the return description.

    Returns
    -------
    Iterator[DecodedTransaction]
        Each record in order, in the published decoded shape. An ABSENT path and a zero-byte file
        both yield nothing, and neither is an error -- this record ships no extract, so having
        nothing to read is a normal state rather than a fault.

    Raises
    ------
    OSError
        If the path exists but cannot be inspected, opened or read. An absent path is reported as
        no records instead.
    LayoutError
        If the file produces an element that is not a line, a field declares a regime a character
        record cannot hold, or a run-clock stamp holds neither an unwritten span nor a well-formed
        timestamp.
    RecordLengthError
        If a line is longer than the declared record width, or a record holds a character outside
        the single-byte range.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the caller supplies an EXPLICIT file and this function never globs a
    #   directory to find one. The seed directories make that concrete: they hold a zero-byte
    #   placeholder and, in the EBCDIC tree, names differing by a single character, so a pattern
    #   match would either sweep the placeholder in or load a dataset twice -- and a doubled image
    #   still divides by the record length with remainder zero, so nothing downstream would catch
    #   it and every money total would come out doubled.
    # WHY : Alternatives Considered: the file is decoded through a single-byte code page that is
    #   total over all 256 byte values rather than through a strict ASCII decode. Both reject a
    #   non-conforming file but differ in WHERE: a strict decode fails inside the interpreter's
    #   reader with an untyped encoding error, which would make the single-byte guard above
    #   unreachable, whereas a total page maps each byte to one character so the failure surfaces
    #   as this package's own record-length error naming the offset.
    # WHY : Assumptions: line splitting is pinned to the separator alone, matching the shared
    #   iterator's own whole-text scanner, so streaming this handle line by line and passing the
    #   whole text produce identical records. Leaving the default in place would let the
    #   interpreter translate and split on a carriage return as well, moving terminator policy out
    #   of the module that owns it -- which matters for this corpus specifically, because three of
    #   the nine ASCII seeds carry carriage returns on some rows and not others.
    # WHY : Assumptions: the absence check comes BEFORE the open and is the reason this reader
    #   differs from its eleven siblings, none of which needs one because each has a committed
    #   extract to point at. Opening a missing file raises, and for this record a missing file is
    #   the expected state before the posting pipeline has produced any output, so the raise would
    #   report a correct pipeline state as a failed load. The check is stated here rather than left
    #   to the caller so that both encodings answer identically; see `seed_dataset_is_present` for
    #   why a stand-in record is not emitted instead.
    if not seed_dataset_is_present(path):
        return

    with path.open("r", encoding="latin-1", newline="\n") as handle:
        yield from iter_ascii_transactions(handle)


def decode_ebcdic_transaction(
    record: bytes | bytearray | memoryview,
) -> DecodedTransaction:
    """Decode one transaction record from the EBCDIC dataset form.

    Purpose
    -------
    Turn a single fixed-length record image into the published decoded shape, delegating the
    per-field character conversion and the numeric decode to the EBCDIC record codec.

    Parameters
    ----------
    record : bytes | bytearray | memoryview
        One whole record image of exactly the declared record length, read in binary mode. A
        ``str`` is refused by the codec, because character data has already lost the byte values
        a sign overpunch depends on.

    Returns
    -------
    DecodedTransaction
        One entry per data field, keyed by the field name exactly as the copybook spells it, in
        declaration order, with the pad dropped. The shape is identical to the ASCII path's,
        which is what makes the two corpora comparable.

    Raises
    ------
    TypeError
        If the record is a ``str`` or is not a one-dimensional image of single bytes.
    EbcdicRecordLengthError
        If the record is not exactly the declared record length. This is a record-length error,
        so a caller guarding either encoding may catch the shared base type.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte and re-encode to those same
        bytes.
    LayoutError
        If a published field decoded to raw bytes, which means the descriptor has acquired a
        computational or mixed-regime area, or if a run-clock stamp holds neither an unwritten span
        nor a well-formed timestamp.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the conversion is DELEGATED per field and never performed on the record
    #   as a whole. That codec decodes each declared span on its own, dispatching on the
    #   descriptor's regime BEFORE any character set is applied, so no span of packed nibbles or
    #   low-value padding is ever handed to a character decoder. Decoding a whole record through a
    #   code page is the single most likely mistake on this path and the most damaging: it
    #   succeeds, preserves the declared width, and yields a record that looks almost right.
    # WHY : Assumptions: the stamp policy is applied on this path too, through the same function
    #   the character path calls. The record codec deliberately returns a stamp exactly as decoded
    #   rather than validating its shape, so without this step the byte path would publish a
    #   corrupt processing timestamp that the character path refuses -- and the two corpora would
    #   then disagree about the validity of one record while agreeing on its every other field.
    decoded = _project_decoded_fields(decode_record(record, TRAN_LAYOUT))
    return _require_validated_timestamps(decoded)


def iter_ebcdic_transactions(
    source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
) -> Iterator[DecodedTransaction]:
    """Decode the EBCDIC dataset form of the transaction master, one record at a time.

    Purpose
    -------
    Stream decoded records from a fixed-length dataset, cut strictly on the record length the
    layout declares.

    Parameters
    ----------
    source : pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object]
        The dataset, in one of four shapes: a path, which the codec opens read-only in binary
        mode, streams and closes; a whole byte image the caller already holds; an open binary
        stream, read strictly forward and neither seeked nor closed; or an iterable of byte pieces
        whose boundaries may fall anywhere. A ``str`` is refused.

    Returns
    -------
    Iterator[DecodedTransaction]
        Each record in order, in the published decoded shape. A dataset with no bytes yields
        nothing at all.

    Raises
    ------
    TypeError
        If the source is a ``str``, which is ambiguous between a dataset location and character
        data somebody has already decoded.
    EbcdicRecordLengthError
        If the dataset does not divide into whole records of the declared length.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte.
    LayoutError
        If the source is neither a byte image nor readable nor iterable, produces a piece that is
        not a byte object, a published field decoded to raw bytes, or a run-clock stamp holds
        neither an unwritten span nor a well-formed timestamp.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Assumptions: the dataset is cut on the declared record length ALONE, and no line
    #   terminator is looked for, honoured, stripped or padded on this path. A fixed-length
    #   blocked dataset carries no terminators, so a byte that happens to equal a newline is field
    #   data -- a low-order digit, a packed nibble pair or a pad byte -- and splitting on it would
    #   produce pieces of wildly differing lengths, most cut through the middle of a field. The
    #   correctness test for this dataset is that its size divides by the declared record length
    #   with no remainder, which the delegated iterator checks before it yields the first record.
    # WHY : Assumptions: the text form's tolerances must never reach here. Right-padding a short
    #   piece or stripping a trailing byte would turn a genuine length failure into a plausible
    #   record, which is why this path reaches a different entry point of the layouts module and
    #   shares no code with the text one.
    for record in iter_ebcdic_records(source, TRAN_LAYOUT):
        yield decode_ebcdic_transaction(record)


def read_ebcdic_transactions(path: pathlib.Path) -> Iterator[DecodedTransaction]:
    """Stream the transaction master from an EBCDIC dataset file at an explicit path.

    Purpose
    -------
    Read one named fixed-length dataset and stream its decoded records, with the size validated
    against the declared record length before the first record is produced.

    Parameters
    ----------
    path : pathlib.Path
        The exact dataset file to read. The caller names the file; this function never searches a
        directory for it. The file need not exist: see the return description.

    Returns
    -------
    Iterator[DecodedTransaction]
        Each record in order, in the published decoded shape. An ABSENT path and a zero-byte file
        both yield nothing, and neither is an error -- this record ships no extract in either
        encoding, so having nothing to read is a normal state rather than a fault.

    Raises
    ------
    OSError
        If the path exists but cannot be inspected or opened. An absent path is reported as no
        records instead.
    EbcdicRecordLengthError
        If the file size does not divide into whole records of the declared length.
    EbcdicFieldDecodeError
        If a span does not decode to exactly one character per byte.
    LayoutError
        If a published field decoded to raw bytes, or a run-clock stamp holds neither an unwritten
        span nor a well-formed timestamp.
    ZonedSpanWidthError
        If an unsigned display field's declared span is not available, or the descriptor's display
        geometry is internally inconsistent. Raised by the display codec.
    ZonedDecimalError
        If a display span violates its contract: a non-digit in the body of either display regime,
        or a low-order byte that is not a valid sign overpunch in a signed one. Raised by the
        display codec.
    """
    # WHY : Alternatives Considered: the path is handed to the codec rather than opened here and
    #   passed as a stream. The codec validates the file size against the declared record length
    #   BEFORE yielding a first record, so a truncated dataset fails up front instead of part way
    #   through a load, and it owns the open, the forward-only read and the close. Opening the file
    #   here would duplicate that lifecycle for no gain.
    # WHY : Assumptions: the same absence check the character path applies is applied here, through
    #   the same predicate, so the two encodings agree that a missing source yields no records. The
    #   codec would otherwise raise on the open it performs, and this record legitimately has no
    #   committed dataset in EITHER encoding -- so leaving the check on one path only would make the
    #   answer depend on which entry point a caller happened to choose.
    if not seed_dataset_is_present(path):
        return

    yield from iter_ebcdic_transactions(pathlib.PurePath(path))


def render_masked_transaction_record(record: str) -> str:
    """Render one character record with every sensitive field redacted.

    Purpose
    -------
    Give an operator a privacy-safe, byte-aligned rendering of a whole record, so a failed
    comparison can show a record's shape and which field differs without emitting content whose
    sensitivity the reader cannot judge.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.

    Returns
    -------
    str
        A rendering of exactly the declared width, with each sensitive field replaced by a
        same-width redaction and every other field left verbatim, so offsets can still be counted
        across it.

    Raises
    ------
    RecordLengthError
        If the record is not the declared width. Raised by the shared masking helper, whose width
        check is the reason this rendering can be relied on to stay byte-aligned.
    LayoutError
        If a field slice comes out the wrong width, which the descriptor's own geometry
        validation makes unreachable.
    """
    # WHY : Assumptions: the redaction is delegated to the shared helper rather than applied here,
    #   so marking a field sensitive in the layout remains the ONLY change ever needed to redact
    #   it in this rendering. The amount is redacted to a keyed tag rather than
    #   blanked, so two transactions differing only in amount still render differently and
    #   a reconciliation can locate the row that moved.
    return mask_record(record, TRAN_LAYOUT)


def render_masked_transaction_field(record: str, field_name: str) -> str:
    """Render one named field of a character record with redaction applied.

    Purpose
    -------
    Produce a privacy-safe rendering of a single field, for a diagnostic that needs to show one
    field rather than a whole record.

    Parameters
    ----------
    record : str
        One whole record at exactly the declared character width.
    field_name : str
        The field name exactly as the copybook spells it, including any baseline misspelling,
        since the descriptor preserves the copybook's own spelling.

    Returns
    -------
    str
        A rendering of exactly that field's declared width: the characters verbatim when the field
        is not sensitive, otherwise a same-width redaction.

    Raises
    ------
    LayoutError
        If no field of that name is declared, or the sliced span is not the field's declared
        width, which happens when the record is short.
    """
    # WHY : Assumptions: the field is resolved through the layout by name and then sliced by its
    #   own declared span, so this module still states no offset of its own and an unknown name
    #   fails loudly here rather than silently rendering the wrong bytes. Resolution is scoped to
    #   THIS record's descriptor rather than to a package-wide field table, which is what keeps a
    #   name declared over different children in a sibling copybook from resolving to the wrong
    #   geometry.
    field = TRAN_LAYOUT.field(field_name)
    return mask_field(field, record[field.start : field.end])
