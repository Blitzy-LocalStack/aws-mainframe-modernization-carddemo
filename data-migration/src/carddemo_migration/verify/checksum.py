"""Digest decoded records so a corrupted field is detectable without rendering any field.

Purpose
-------
Catch the failure a row count cannot see: the counts agree and a field's value is wrong. A
digest over every loaded field of every record changes when any byte of any field changes, so
comparing a source digest against a target digest proves the two hold the same data. When the
two disagree, the same walk names WHICH record and WHICH field carries the difference, so a
mismatch is actionable without a primary account number reaching a log line.

The two-timestamp asymmetry
---------------------------
A digest taken over a whole record is not reproducible, and the reason is a two-stamp
asymmetry that is easy to invert. ``DALYTRAN-PROC-TS`` / ``TRAN-PROC-TS`` at offset 304 is
stamped from the WALL CLOCK by the posting program, so it differs between two runs over
identical data. ``ORIG-TS`` at offset 278 is the same width and carries the DETERMINISTIC
originating stamp copied from the input transaction -- every populated value in
``app/data/ASCII/dailytran.txt`` reads ``2022-06-10 19:27:53.000000``, and the processing stamp
in those same records is 26 blanks because the posting run is what writes it.

Which stamp is which is READ from each field descriptor's ``normalize_ts`` mark in
:mod:`carddemo_migration.copybook.layouts` and is never re-derived here. See
:func:`deterministic_field_names`, whose rationale records why deriving it a second time would
be wrong rather than merely redundant.

Relationship to the other two passes
------------------------------------
This is pass 2 of the three the migration plan requires -- row counts, record checksums and
money-total parity -- and each is blind to the failure the next one catches.

Design decisions (WHY)
----------------------
Assumptions:
    None of the three passes may be skipped, made optional by default, or weakened, because a
    row count cannot detect a sign-overpunch or a packed-nibble defect at all: a mis-decoded
    sign yields exactly the right NUMBER of rows with the wrong money in them. That is why this
    module exists beside :mod:`carddemo_migration.verify.row_counts` rather than inside it, and
    why the combined invocation in :mod:`carddemo_migration.cli` runs all three with no flag
    able to switch one off.
Assumptions:
    Every routine here refuses to render a field VALUE, and the refusal is stronger than the
    reference codec's. ``tests/helpers/record_codec.py`` echoes the offending characters when a
    zoned span fails to decode; a verification report is the artifact most likely to be pasted
    into an issue tracker, so a difference is reported as a field NAME, its half-open byte
    interval and its storage regime through :meth:`FieldSpec.describe`, and never as content.
Trade-offs:
    This module performs no input and output of any kind: it opens no dataset, imports no
    database driver and issues no statement. Both sides of a comparison are supplied as
    iterables of decoded records, which costs the caller two calls it would otherwise not
    make and buys two properties. The digest and the comparison are testable with hand-built
    records and no provisioned environment; and the row-level read-back stays with the caller,
    which is where the privilege to perform it lives -- ``carddemo_reporting``, the role both
    SQL passes run as, holds ``SELECT`` on the two aggregate-only views
    ``data-migration/sql/V3__verification_surfaces.sql`` creates and no ``USAGE`` on any base
    schema, so a row-level read issued from here could not run at all.
"""

from __future__ import annotations

import enum
import hashlib
import itertools
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Final

# WHY : Assumptions: the three timestamp names are taken from the SUBPACKAGE boundary rather
#   than from `copybook.timestamp` directly, because that boundary declares itself the stable
#   spelling for exactly this and re-exports the curated set through its own `__all__`. Importing
#   the submodule would bind this pass to an internal path the subpackage is free to rearrange,
#   for no gain: the names reached either way are the same objects.
from carddemo_migration.copybook import ADMITTED_FORMS, is_admitted, is_unwritten, layouts

__all__ = [
    "DIGEST_NAME",
    "FIELD_SEPARATOR",
    "RECORD_SEPARATOR",
    "REPORTED_DIFFERENCE_LIMIT",
    "ChecksumComparison",
    "ChecksumDifference",
    "ChecksumVerificationError",
    "DifferenceKind",
    "RecordDigest",
    "TimestampContractError",
    "compare_record_digests",
    "deterministic_field_names",
    "digest_of_record",
    "digest_records",
    "normalized_timestamp_field_names",
    "validated_timestamp",
]

# WHY : Alternatives Considered: SHA-256 rather than a CRC or MD5. A CRC is designed to detect
#   transmission noise and collides readily under deliberate or structured change, and MD5's
#   collision resistance is broken. This digest is a data-integrity claim about money records
#   that an auditor may rely on, so the cost of a stronger hash -- which is negligible beside
#   the input decode -- buys a claim that does not need qualifying.
DIGEST_NAME: Final[str] = "sha256"

# WHY : Assumptions: the separators are byte values that cannot occur in a decoded field.
#   A decoded field is characters or an exact decimal rendered as text, so a unit separator and
#   a record separator from the C0 control range are unambiguous. Joining on a printable
#   character -- a comma, say -- would let a field CONTAINING that character produce the same
#   digest as a different split of the same bytes, which is exactly the ambiguity a digest must
#   not have.
FIELD_SEPARATOR: Final[bytes] = b"\x1f"
RECORD_SEPARATOR: Final[bytes] = b"\x1e"

# WHY : Trade-offs: the per-field differences a comparison RECORDS are bounded while the counts
#   it reports are exact. A load that wrote every row wrong would otherwise produce one report
#   line per field per record -- for the transaction master, thirteen lines times three hundred
#   thousand records -- and the operator who has to act on it would receive a file rather than a
#   diagnostic, held entirely in memory to be rendered. The bound costs the tail of a large
#   failure, which the withheld count still reports as a number, and it keeps the localisation
#   readable for the case it was built for: a handful of records out of many.
REPORTED_DIFFERENCE_LIMIT: Final[int] = 20


class ChecksumVerificationError(RuntimeError):
    """Raised when the checksum pass cannot reach a verdict it is entitled to publish.

    Purpose
    -------
    Give the pass one catchable base, so a caller distinguishes "this pass could not judge the
    data" from a decode fault raised by a reader and from a database failure raised by a driver.
    The sibling row-count pass publishes :class:`RowCountVerificationError` for the same reason,
    and ``cli.py`` catches both alongside its step errors.

    Assumptions: every refusal in this module RAISES and none of them asserts, which is not a
    stylistic preference. ``python -O`` and ``PYTHONOPTIMIZE`` strip every ``assert`` statement
    from the bytecode, so an assertion is a validation that disappears in exactly the
    configuration a container image is most likely to run -- and a verification pass whose checks
    vanish under optimisation reports success by not looking. ``copybook/layouts.py`` and
    ``loaders/aurora.py`` hold themselves to the same convention.

    Parameters
    ----------
    None
        Inherits the base exception's own arguments.

    Raises
    ------
    None
        Declaring an exception class raises nothing.
    """


class TimestampContractError(ChecksumVerificationError):
    """Raised when a run-clock stamp is neither a well-formed timestamp nor uniformly unwritten.

    Purpose
    -------
    Report that a field the layout marks as a wall-clock stamp holds something no CardDemo
    program writes, so a wrong offset or a corrupted record fails loudly instead of being
    quietly blanked out of the compared span.

    Parameters
    ----------
    None
        Inherits the base exception's own arguments.

    Raises
    ------
    None
        Declaring an exception class raises nothing.
    """


@dataclass(frozen=True)
class RecordDigest:
    """A digest over a set of records, with the count it covered.

    Parameters
    ----------
    digest : str
        Lower-case hexadecimal digest.
    records : int
        How many records the digest covers.
    fields : tuple[str, ...]
        The field names, in the order they were fed to the digest.

    Raises
    ------
    None
        Construction validates nothing and cannot fail. The inapplicability is stated rather than
        left silent, so a reader can tell a value object that cannot refuse from a docstring that
        forgot to say how it does.
    """

    digest: str
    records: int
    fields: tuple[str, ...]

    def describe(self) -> str:
        """Render the digest as one privacy-safe line.

        Parameters
        ----------
        None
            Reads the three components.

        Returns
        -------
        str
            The digest, the record count and the field count. No field VALUE appears; that is
            the property that makes a digest publishable where the records are not.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        return f"{DIGEST_NAME}={self.digest} records={self.records} fields={len(self.fields)}"


def _canonical(value: str | Decimal | bytes) -> bytes:
    """Render one decoded value as the bytes the digest consumes.

    Purpose
    -------
    Make the rendering of each value type total and unambiguous, so the same data digests
    identically on any host and in any Python build.

    Parameters
    ----------
    value : str | Decimal | bytes
        A decoded field value.

    Returns
    -------
    bytes
        The canonical byte rendering.

    Raises
    ------
    TypeError
        If the value is none of the three decoded types.
    """
    if isinstance(value, bytes):
        return value
    if isinstance(value, Decimal):
        # WHY : Assumptions: the decimal is rendered at its own scale through a plain string,
        #   never through float or scientific notation. `Decimal("1.50")` and `Decimal("1.5")`
        #   are numerically equal and MUST digest differently, because the scale is part of the
        #   column contract -- a NUMERIC(12,2) holding 1.50 is not the same stored value as one
        #   holding 1.5 -- and `str` preserves it where `format(value, "f")` on a normalised
        #   decimal would not.
        # WHY : Assumptions: a binary float cannot represent ten cents exactly, so rendering a
        #   money value through `float` would make the digest depend on representation error
        #   rather than on the data: two loads of the same cents could digest differently, and
        #   two different amounts could digest identically. No `float` appears in this module.
        return str(value).encode("utf-8")
    if isinstance(value, str):
        return value.encode("utf-8")
    raise TypeError(
        f"a decoded field value of type {type(value).__name__} cannot be digested; a reader"
        " yields characters, an exact decimal or raw bytes and nothing else"
    )


def _require_fields(fields: Sequence[str]) -> tuple[str, ...]:
    """Freeze a caller's field selection into an order, refusing an empty one.

    Purpose
    -------
    Hold the two digest entry points and the comparison to ONE refusal of an empty field set, so
    the three cannot come to disagree about whether digesting nothing is allowed.

    Parameters
    ----------
    fields : Sequence[str]
        The field names to digest, in order.

    Returns
    -------
    tuple[str, ...]
        The same names as an immutable ordered tuple.

    Raises
    ------
    ValueError
        If no field is named, which would digest every record to the same empty value and
        report agreement between datasets that differ.
    """
    ordered = tuple(fields)
    if not ordered:
        raise ValueError(
            "a digest over no field would be identical for every record and would report two"
            " different datasets as matching; name the fields to digest"
        )
    return ordered


def _canonical_values(
    record: Mapping[str, str | Decimal | bytes], fields: Sequence[str]
) -> tuple[bytes, ...]:
    """Render one record's named fields as the per-field bytes a digest consumes.

    Purpose
    -------
    Produce the per-field renderings once, so that the whole-record digest and the field-level
    localisation are computed from the SAME bytes. Two separate renderings could disagree, and
    a comparison whose digest said "different" while its localisation said "no field differs"
    would leave an operator with nothing to act on.

    Parameters
    ----------
    record : Mapping[str, str | Decimal | bytes]
        One decoded record.
    fields : Sequence[str]
        The field names to render, in order.

    Returns
    -------
    tuple[bytes, ...]
        One canonical rendering per named field, in the same order.

    Raises
    ------
    KeyError
        If the record does not carry a named field. The field NAME is in the message and no
        value is.
    TypeError
        If a value is not a decoded type.
    """
    # WHY : Alternatives Considered: the digest is taken over the DECODED FIELD VALUES, and a hash
    #   of the raw record image was rejected -- which is the cheaper implementation and the wrong
    #   one. The two physical forms of one record are not the same bytes: `app/data/ASCII/
    #   cardxref.txt` carries 50 lines of 36 characters because the text form simply omits the
    #   trailing `FILLER X(14)`, while `CVACT03Y` declares a record length of 50 and the
    #   mainframe-character-set twin carries all 50 -- so the reader right-pads the short row. A
    #   raw slice would therefore differ between the two representations of one logical record, and
    #   differ again across that padding, and would report those as differences in the data. It
    #   would also fold in the padding the target never stores and the enciphered columns whose
    #   bytes differ on every write by design. Hashing the values the two sides actually hold is
    #   what makes the comparison mean "the same data" rather than "the same image".
    return tuple(_canonical(record[field]) for field in fields)


def digest_of_record(record: Mapping[str, str | Decimal | bytes], fields: Sequence[str]) -> bytes:
    """Render one record as the bytes contributing to a digest.

    Purpose
    -------
    Frame one record's named fields into a single byte string, so that a shifted field boundary
    cannot produce the bytes a correct record produces. This is the unit the whole-dataset digest
    is built from and the unit a caller comparing two single records reaches for.

    Parameters
    ----------
    record : Mapping[str, str | Decimal | bytes]
        One decoded record.
    fields : Sequence[str]
        The field names to include, in order.

    Returns
    -------
    bytes
        The record's canonical contribution, field-separated.

    Raises
    ------
    KeyError
        If the record does not carry a named field. The field NAME is in the message and no
        value is.
    TypeError
        If a value is not a decoded type.
    """
    return FIELD_SEPARATOR.join(_canonical_values(record, fields))


def digest_records(
    records: Iterable[Mapping[str, str | Decimal | bytes]],
    fields: Sequence[str],
) -> RecordDigest:
    """Digest a stream of decoded records over an explicit, ordered field set.

    Purpose
    -------
    Produce one comparable value for a whole dataset, computed in a single streaming pass so a
    large dataset never has to be held.

    Parameters
    ----------
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        Decoded records, as a reader yields them.
    fields : Sequence[str]
        The field names to digest, in order. Supplying the order explicitly is what makes the
        digest reproducible: a mapping's iteration order is a property of how it was built.

    Returns
    -------
    RecordDigest
        The digest and the count it covers.

    Raises
    ------
    ValueError
        If no field is named, which would digest every record to the same empty value and
        report agreement between datasets that differ.
    KeyError
        If a record does not carry a named field.
    """
    ordered = _require_fields(fields)
    hasher = hashlib.new(DIGEST_NAME)
    counted = 0
    for record in records:
        hasher.update(digest_of_record(record, ordered))
        # WHY : Assumptions: the record separator is appended AFTER every record including the
        #   last, rather than interposed between records. Interposing makes the digest of one
        #   record equal to the digest of its own single field set, so a one-record dataset and
        #   a one-field-per-record framing could collide.
        hasher.update(RECORD_SEPARATOR)
        counted += 1
    return RecordDigest(digest=hasher.hexdigest(), records=counted, fields=ordered)


def normalized_timestamp_field_names(layout: layouts.RecordSpec) -> frozenset[str]:
    """Name the fields of one record that hold a run-clock stamp.

    Purpose
    -------
    Publish, for any layout in the registry, the set two readers already publish for their own
    two records -- so a caller comparing a record no reader has a constant for asks the layout
    rather than assembling the answer from field names.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record descriptor whose fields are inspected.

    Returns
    -------
    frozenset[str]
        Every field name whose descriptor sets ``normalize_ts``. Empty for nine of the fourteen
        layouts in the registry, which carry no marked stamp at all; the five that do are the
        daily transaction, the transaction master, the statement view, the reject stream and the
        interest transaction.

    Raises
    ------
    None
        Reading a validated descriptor cannot fail.
    """
    return frozenset(field.name for field in layout.fields if field.normalize_ts)


def deterministic_field_names(layout: layouts.RecordSpec, fields: Sequence[str]) -> tuple[str, ...]:
    """Drop the run-clock stamps from a candidate field selection, keeping its order.

    Purpose
    -------
    Decide which of a caller's fields may enter a digest, by reading each field descriptor's
    ``normalize_ts`` mark. This is the mechanism ``data-migration/README.md`` section 10.1
    commits the pass to: the processing timestamp is excluded from the checksummed span, and the
    pass reads the mark rather than carrying its own list of offsets.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record descriptor the candidate names are resolved against.
    fields : Sequence[str]
        The candidate field names, in the order both sides of a comparison will use them --
        typically ``TableTarget.comparable_fields()``, which is the target's own column order.

    Returns
    -------
    tuple[str, ...]
        The candidates that carry deterministic data, in the order they were given. A candidate
        the layout does not declare is KEPT, for the reason recorded below.

    Raises
    ------
    None
        An unresolvable candidate is kept rather than refused, so this call raises nothing of its
        own.
    """
    # WHY : Assumptions: which stamp is non-deterministic is READ from the descriptor and is never
    #   re-derived from a field name written here, and the asymmetry is layout-defined rather than
    #   universal. `DALYTRAN` and `TRAN` mark only their processing stamp, because the originating
    #   stamp is copied from the input transaction; `INTTRAN` marks BOTH, because `CBACT04C` moves
    #   the run clock into both stamps of every interest transaction it writes. A hand-rolled rule
    #   spelled "always keep ORIG-TS" is therefore not merely duplicated -- it is outright WRONG
    #   for the interest-transaction layout, and wrong in the silent direction: the digest would
    #   differ between two comparisons of identical data, which reads as a data defect rather than
    #   as the measurement defect it is.
    # WHY : Assumptions: excluding the processing stamp is what makes the pass usable at all. It
    #   is stamped from the wall clock, so including it makes every record's digest differ on
    #   every run, and a report that always differs trains its reader to ignore it. The
    #   originating stamp is NOT excluded, because blanking it would discard business data the
    #   comparison exists to verify and would shorten the effectively compared record; it is
    #   deterministic, and every populated value in `app/data/ASCII/dailytran.txt` reads
    #   `2022-06-10 19:27:53.000000` -- exactly 26 characters, the final four microsecond digits
    #   structurally zero.
    # WHY : Alternatives Considered: a candidate the layout does not declare is kept rather than
    #   refused, where the descriptor lookup would otherwise raise. The concrete case is
    #   `SECUSER`, whose target maps a DERIVED column, `cognito_sub`, that no copybook field
    #   declares; refusing it would make this pass unable to compare that record at all. Keeping
    #   it is safe in the direction that matters, because a name with no descriptor carries no
    #   `normalize_ts` mark and so cannot be a wall-clock stamp -- and a MISSPELLED name still
    #   fails loudly rather than silently, at `_canonical_values`, whose KeyError names the field
    #   the record does not carry.
    declared = {field.name: field for field in layout.fields}
    return tuple(name for name in fields if not (name in declared and declared[name].normalize_ts))


def validated_timestamp(field: layouts.FieldSpec, value: object) -> object:
    """Admit a run-clock stamp's value only if it is a real timestamp or an unwritten span.

    Purpose
    -------
    Apply the validate-before-blank discipline to a field this pass is about to leave OUT of the
    compared span. A field is excluded because its value is expected to vary, not because its
    value is expected to be arbitrary, so the exclusion is granted to a well-formed stamp and to
    a span nobody has written into -- and to nothing else.

    Parameters
    ----------
    field : layouts.FieldSpec
        The descriptor of the field being checked, used for the declared width and for the
        content-free description a refusal carries.
    value : object
        The field's value as its side of the comparison holds it.

    Returns
    -------
    object
        The value unchanged. The return exists so a caller can validate inside an expression;
        nothing about the value is altered, because this pass never rewrites its input.

    Raises
    ------
    TimestampContractError
        If the value is characters of the declared width that are neither one of the two
        admitted spellings nor uniformly unwritten, or characters of the wrong width. The
        message names the field through :meth:`layouts.FieldSpec.describe` and quotes no part of
        the content.
    """
    # WHY : Assumptions: a non-character value is admitted untouched, and the two that occur are
    #   named rather than left general. The load projection renders an unwritten stamp to `None`
    #   for a nullable `TIMESTAMP(6)` column, and a driver reads that column back as a
    #   `datetime`; both have already been constrained by the column's own type, so re-deriving
    #   that here would be this module maintaining a second calendar -- which is the duplication
    #   `copybook.timestamp` was extracted to remove.
    if not isinstance(value, str):
        return value
    # WHY : Alternatives Considered: blanking or excluding the field by POSITION alone, with no
    #   shape check, which is what a comparison that simply skipped the marked offsets would do.
    #   Rejected because it hides the two defects this pass is built to surface: a record sliced
    #   at the wrong offset, and a record whose bytes are corrupt. Both put arbitrary characters
    #   where a stamp belongs, and both would be silently discarded along with the stamp -- a
    #   financial harness has to surface that class of defect, not paper over it.
    if len(value) != field.length:
        raise TimestampContractError(
            f"field {field.describe()} holds {len(value)} characters where its declared width is"
            f" {field.length}, so the record is not sliced where this layout says it is"
        )
    # WHY : Assumptions: the shape question is answered by `copybook.timestamp`, which parses
    #   each candidate through the standard library's own calendar and requires it to format back
    #   to itself. The per-position separator test this could have re-implemented is exactly what
    #   that module was extracted to REPLACE: three byte-identical copies of it admitted the union
    #   of four separators at each of six positions -- spellings CardDemo never writes -- and
    #   validated no calendar, so an impossible month passed here and failed later inside the
    #   database as a cast error naming a column rather than a record.
    # WHY : Assumptions: an unwritten stamp is a VALID value rather than a defect, so it is
    #   admitted alongside a populated one. COBOL leaves a field it has not moved a value into
    #   holding what its record was initialised to, and all 300 records of the shipped daily
    #   transaction extract carry 26 blanks in the processing stamp because the posting run is
    #   what writes it. Treating that as a decode fault would fail every verification of a
    #   pre-posting extract.
    if is_admitted(value) or is_unwritten(value):
        return value
    raise TimestampContractError(
        f"field {field.describe()} holds {field.length} characters that are neither an admitted"
        f" timestamp ({' or '.join(ADMITTED_FORMS)}) nor uniformly unwritten, so it"
        " cannot be excluded from the compared span as a run-clock stamp"
    )


def _validate_stamps(layout: layouts.RecordSpec, record: Mapping[str, object]) -> None:
    """Validate every run-clock stamp one record actually carries.

    Purpose
    -------
    Reach the fields the digest deliberately does not, so that excluding a stamp from the
    compared span never becomes a way of not looking at it.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record descriptor whose ``normalize_ts`` marks select what is checked.
    record : Mapping[str, object]
        One record from either side of a comparison.

    Returns
    -------
    None
        Nothing. The effect is the refusal, or its absence.

    Raises
    ------
    TimestampContractError
        If a stamp the record carries is neither admitted nor uniformly unwritten.
    """
    # WHY : Assumptions: only a stamp the record actually carries is checked. A reader drops
    #   padding and withholds a suppressed field, and a target's comparable field set omits an
    #   enciphered column, so a layout field is routinely absent from a record that is entirely
    #   correct. Demanding presence here would fail those records for a reason that has nothing
    #   to do with their content.
    for field in layout.fields:
        if field.normalize_ts and field.name in record:
            validated_timestamp(field, record[field.name])


class DifferenceKind(enum.Enum):
    """What kind of disagreement one reported difference records.

    Purpose
    -------
    Separate "the two sides hold a different value for this field" from "one side has no record
    at this position", because the two call for different action: the first localises a decode or
    projection defect, the second means the load stopped early, ran twice, or ordered its rows
    differently from the extract.

    Parameters
    ----------
    None
        An enumeration member carries only its own value.

    Raises
    ------
    None
        Declaring an enumeration raises nothing.
    """

    FIELD = "field"
    """One named field's canonical rendering differs between the two sides."""

    ABSENT_FROM_TARGET = "absent_from_target"
    """The source has a record at this position and the read-back side has none."""

    ABSENT_FROM_SOURCE = "absent_from_source"
    """The read-back side has a row at this position and the source has no record."""


# WHY : Assumptions: the three verdict tokens are constants of ONE width, because the report's
#   whole value is that two runs over unchanged data diff to nothing and that a reader can scan a
#   column. Two spellings of the same verdict drifting apart between the per-line and the summary
#   form would show up as a diff an operator has to read before dismissing.
_FIELD_VERDICT: Final[str] = "DIFFER"
_ABSENT_VERDICT: Final[str] = "ABSENT"
_EXTRA_VERDICT: Final[str] = "EXTRA "

_VERDICTS: Final[Mapping[DifferenceKind, str]] = {
    DifferenceKind.FIELD: _FIELD_VERDICT,
    DifferenceKind.ABSENT_FROM_TARGET: _ABSENT_VERDICT,
    DifferenceKind.ABSENT_FROM_SOURCE: _EXTRA_VERDICT,
}

_ABSENT_FROM_TARGET_NOTE: Final[str] = "no row was read back at this position"
_ABSENT_FROM_SOURCE_NOTE: Final[str] = "no source record occupies this position"


@dataclass(frozen=True)
class ChecksumDifference:
    """One disagreement between the two sides, located and named but never quoted.

    Purpose
    -------
    Carry enough to act on -- which record, which field, which kind of disagreement -- and
    nothing that discloses a value.

    Parameters
    ----------
    ordinal : int
        The one-based position in the compared streams. This is the record's location in the
        source extract, which is what lets an operator go straight to it.
    kind : DifferenceKind
        Which kind of disagreement this is.
    field : layouts.FieldSpec | None
        The descriptor of the differing field, or ``None`` for a record-level difference and for
        a differing field the layout does not declare.
    field_name : str
        The differing field's name, or an empty string for a record-level difference. Carried
        separately from ``field`` so a derived target column, which has no descriptor, is still
        named in the report.

    Raises
    ------
    None
        Construction validates nothing and cannot fail. The inapplicability is stated rather than
        left silent, so a reader can tell a value object that cannot refuse from a docstring that
        forgot to say how it does.
    """

    ordinal: int
    kind: DifferenceKind
    field: layouts.FieldSpec | None = None
    field_name: str = ""

    def describe(self) -> str:
        """Render this difference as one privacy-safe, run-invariant line.

        Parameters
        ----------
        None
            Reads the located difference.

        Returns
        -------
        str
            A line naming the verdict, the record's position and either the field or the reason
            the position is unpaired. Nothing run-varying and no field content appears.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        # WHY : Trade-offs: the field is rendered through `FieldSpec.describe` -- name, half-open
        #   byte interval and storage regime -- and its VALUE is not rendered at all, on either
        #   side. This is stricter than the reference codec, which echoes the offending characters
        #   when a zoned span fails to decode, and the reason is where the two artifacts go: a
        #   verification report is the one most likely to be pasted into an issue tracker, and
        #   these records carry primary account numbers, national identifiers and dates of birth.
        #   The cost is a less immediately diagnosable failure -- an operator learns WHICH field
        #   moved, not what it moved to -- and the masked per-record renderings the readers publish
        #   are the supported way to look at the two values under the access that entitles it.
        # WHY : Alternatives Considered: the keyed masking helpers in `copybook.layouts` were
        #   evaluated for rendering the two values here and rejected for this report. Their tag is
        #   derived under a process-scoped random key whenever `CARDDEMO_MASK_HMAC_KEY` is unset,
        #   which is the ordinary case, so a masked value would differ between two runs over
        #   identical data -- defeating the line-diffable guarantee this rendering exists to keep.
        if self.kind is DifferenceKind.FIELD:
            described = self.field.describe() if self.field is not None else self.field_name
            return f"{_VERDICTS[self.kind]}  record={self.ordinal}  {described}"
        note = (
            _ABSENT_FROM_TARGET_NOTE
            if self.kind is DifferenceKind.ABSENT_FROM_TARGET
            else _ABSENT_FROM_SOURCE_NOTE
        )
        return f"{_VERDICTS[self.kind]}  record={self.ordinal}  {note}"


@dataclass(frozen=True)
class ChecksumComparison:
    """The outcome of one dataset's checksum comparison, with a single binary verdict over it.

    Purpose
    -------
    Carry the two whole-dataset digests beside the located differences, so a caller reads one
    named outcome instead of comparing two hexadecimal strings and then having nothing to say
    about where they diverged.

    Parameters
    ----------
    record_name : str
        The layout name of the dataset compared.
    qualified_table : str
        The schema-qualified target table the read-back side came from.
    source : RecordDigest
        The digest over the source records.
    target : RecordDigest
        The digest over the read-back rows, taken over the same fields in the same order.
    differences : tuple[ChecksumDifference, ...]
        The located differences, in stream order, bounded by the comparison's reporting limit.
    differing_records : int
        How many positions held two records that disagreed on at least one field. Exact, and
        therefore not bounded by the reporting limit.
    absent_records : int
        How many positions were occupied on one side only. Exact.
    withheld_differences : int
        How many differences were counted but not carried in ``differences``.

    Raises
    ------
    None
        Construction validates nothing, because every component was computed by the comparison
        that built it.
    """

    record_name: str
    qualified_table: str
    source: RecordDigest
    target: RecordDigest
    differences: tuple[ChecksumDifference, ...]
    differing_records: int
    absent_records: int
    withheld_differences: int

    @property
    def compared(self) -> int:
        """Report how many positions the comparison walked.

        Parameters
        ----------
        None
            Reads the two record counts.

        Returns
        -------
        int
            The greater of the two record counts, which is the number of positions that had a
            record on at least one side.

        Raises
        ------
        None
            Comparing two integers cannot fail.
        """
        return max(self.source.records, self.target.records)

    @property
    def matched(self) -> bool:
        """Report whether the two whole-dataset digests are identical.

        Parameters
        ----------
        None
            Reads the two digests.

        Returns
        -------
        bool
            True when every named field of every record rendered identically on both sides, in
            the same order.

        Raises
        ------
        None
            Comparing two strings cannot fail.
        """
        return self.source.digest == self.target.digest

    @property
    def verified(self) -> bool:
        """Report the one binary verdict this pass produces.

        Parameters
        ----------
        None
            Reads the two digests and the located counts.

        Returns
        -------
        bool
            True when the digests agree, no difference was located and at least one position was
            compared.

        Raises
        ------
        None
            Reducing computed counts to one verdict cannot fail.
        """
        # WHY : Alternatives Considered: the verdict is BINARY -- verified or not -- and this
        #   module deliberately borrows nothing from the graded aggregate return-code rubric the
        #   COBOL parity suite under `tests/**` uses, in which 4 is a soft warn, 8 a failure and
        #   16 an abend, aggregated worst-wins. A warn tier was considered for the unpaired-record
        #   case and rejected: it would make a non-zero verification result ambiguous between
        #   "warned" and "failed", so a caller branching on the number would have to know which.
        #   That rubric belongs to the parity oracle; the migration CLI commits to a strictly
        #   binary exit status, and this property is what it branches on.
        # WHY : Trade-offs: the digest comparison AND the located differences must both agree
        #   before this reports success, which is deliberately redundant. The two are independent
        #   observations of one question computed from the same per-field bytes, so they cannot
        #   disagree unless this module is wrong -- and requiring both turns that into a reported
        #   failure rather than a silent pass by the half that happened to be right.
        # WHY : Assumptions: a comparison that walked NO position fails rather than vacuously
        #   passing, matching the row-count report's own rule. Two empty digests are equal, which
        #   would otherwise turn "the reader yielded nothing and the table is empty" -- an
        #   unreadable extract, a load that never ran -- into a clean bill of health.
        if not self.compared:
            return False
        return self.matched and not self.differing_records and not self.absent_records

    def describe(self) -> str:
        """Render the outcome as one privacy-safe summary line.

        Parameters
        ----------
        None
            Reads the comparison.

        Returns
        -------
        str
            A line naming the verdict, the dataset, the target table and the two record counts.
            No field value appears.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        verdict = "MATCH" if self.verified else "DIFFER"
        return (
            f"{verdict} {self.record_name} -> {self.qualified_table}"
            f" source={self.source.records} target={self.target.records}"
            f" differing={self.differing_records} absent={self.absent_records}"
        )

    def render(self) -> str:
        """Render the whole comparison as deterministic, line-diffable text.

        Parameters
        ----------
        None
            Reads the comparison and its located differences.

        Returns
        -------
        str
            A header line, the two digest lines, one line per located difference, a withheld-count
            line when any difference was withheld, and one verdict line. No trailing newline, so
            a caller decides how it is emitted.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        # WHY : Assumptions: NOTHING run-varying appears anywhere in this text -- no timestamp, no
        #   duration, no elapsed time, no run identifier, no hostname, no process id and no
        #   connection detail. Two runs over unchanged data must diff to nothing, so that a real
        #   change stands out instead of arriving inside a diff an operator has learned to skim.
        #   The two SQL passes give the same guarantee, and one varying value in this rendering
        #   would be enough to defeat it for the pass between them.
        lines = [
            f"record checksum verification: {self.record_name} -> {self.qualified_table}",
            f"  source {self.source.describe()}",
            f"  target {self.target.describe()}",
        ]
        lines.extend(f"  {difference.describe()}" for difference in self.differences)
        if self.withheld_differences:
            lines.append(
                f"  ... {self.withheld_differences} further difference(s) counted and withheld,"
                f" the report carrying the first {len(self.differences)}"
            )
        lines.append(
            f"record checksum verification {'PASSED' if self.verified else 'FAILED'}:"
            f" {self.compared} position(s) compared,"
            f" {self.differing_records} record(s) differing,"
            f" {self.absent_records} position(s) occupied on one side only"
        )
        return "\n".join(lines)


def compare_record_digests(
    record_name: str,
    qualified_table: str,
    source_records: Iterable[Mapping[str, str | Decimal | bytes]],
    target_records: Iterable[Mapping[str, str | Decimal | bytes]],
    fields: Sequence[str],
    *,
    layout: layouts.RecordSpec | None = None,
    reported_differences: int = REPORTED_DIFFERENCE_LIMIT,
) -> ChecksumComparison:
    """Digest both sides of one load in lockstep and locate every difference between them.

    Purpose
    -------
    Answer the two questions this pass exists for in a single streaming walk: do the source
    records and the loaded rows hold the same data, and if not, which record and which field
    disagree.

    Parameters
    ----------
    record_name : str
        The layout name of the dataset compared, reported verbatim.
    qualified_table : str
        The schema-qualified target table the read-back rows came from, reported verbatim.
    source_records : Iterable[Mapping[str, str | Decimal | bytes]]
        The source side, as decoded records in extract order. An iterator is consumed once and
        never held, so a large dataset costs one record of memory.
    target_records : Iterable[Mapping[str, str | Decimal | bytes]]
        The read-back side, keyed by the same field names, in the order the read-back produced.
    fields : Sequence[str]
        The field names to compare, in the order both sides will use. Pass this through
        :func:`deterministic_field_names` first so no run-clock stamp is inside it.
    layout : layouts.RecordSpec | None
        The record descriptor, when the caller has it. Supplying it turns on the validate-before
        -blank check over every run-clock stamp the records carry, and it is what lets a reported
        difference name a field's byte interval and storage regime rather than only its name.
    reported_differences : int
        How many located differences the result carries. Counts stay exact whatever this is.

    Returns
    -------
    ChecksumComparison
        The outcome, whether or not the two sides agree. A disagreement is REPORTED rather than
        raised, so one run reports every difference it can carry instead of stopping at the first.

    Raises
    ------
    ValueError
        If no field is named, or if ``reported_differences`` is below one.
    TimestampContractError
        If a layout was supplied and a run-clock stamp on either side is neither an admitted
        timestamp nor uniformly unwritten.
    KeyError
        If either side's record does not carry a named field.
    TypeError
        If a value on either side is not a decoded type.
    """
    ordered = _require_fields(fields)
    # WHY : Alternatives Considered: a reporting limit below one is REFUSED rather than clamped to
    #   zero. Clamping would let a caller obtain a comparison that counts differences and can name
    #   none of them, which is a weakened pass reached by an argument rather than by an edit -- and
    #   the three passes are required to be unweakenable. Refusing costs one check.
    if reported_differences < 1:
        raise ValueError(
            "a comparison that can report no difference cannot localise one, which is this pass's"
            f" whole purpose; {reported_differences} was requested and at least 1 is required"
        )
    source_hasher = hashlib.new(DIGEST_NAME)
    target_hasher = hashlib.new(DIGEST_NAME)
    declared = {field.name: field for field in layout.fields} if layout is not None else {}
    differences: list[ChecksumDifference] = []
    withheld = 0
    differing_records = 0
    absent_records = 0
    source_count = 0
    target_count = 0
    ordinal = 0
    # WHY : Assumptions: the two streams are walked in LOCKSTEP by position, which is the same
    #   comparison the whole-dataset digest already makes -- that digest is order-sensitive by
    #   construction, because the source side is in extract order and the read-back side is
    #   ordered by the target's own key columns. Pairing by position is therefore not a weaker
    #   substitute for pairing by key: it is what the digest means, made visible per record.
    # WHY : Trade-offs: `zip_longest` rather than `zip`, so a load that stopped early or ran twice
    #   is REPORTED at the position it diverged. Plain `zip` stops at the shorter side, which would
    #   silently compare a prefix and then report agreement over it while the digests -- computed
    #   over what was actually consumed -- disagreed, leaving the report and the verdict at odds.
    for source_record, target_record in itertools.zip_longest(source_records, target_records):
        ordinal += 1
        source_values: tuple[bytes, ...] = ()
        target_values: tuple[bytes, ...] = ()
        if source_record is not None:
            source_count += 1
            if layout is not None:
                _validate_stamps(layout, source_record)
            source_values = _canonical_values(source_record, ordered)
            source_hasher.update(FIELD_SEPARATOR.join(source_values))
            source_hasher.update(RECORD_SEPARATOR)
        if target_record is not None:
            target_count += 1
            if layout is not None:
                _validate_stamps(layout, target_record)
            target_values = _canonical_values(target_record, ordered)
            target_hasher.update(FIELD_SEPARATOR.join(target_values))
            target_hasher.update(RECORD_SEPARATOR)
        if source_record is None or target_record is None:
            absent_records += 1
            kind = (
                DifferenceKind.ABSENT_FROM_SOURCE
                if source_record is None
                else DifferenceKind.ABSENT_FROM_TARGET
            )
            if len(differences) < reported_differences:
                differences.append(ChecksumDifference(ordinal=ordinal, kind=kind))
            else:
                withheld += 1
            continue
        # WHY : Assumptions: the two sides are compared field by field from the SAME per-field
        #   renderings the two digests were fed, so a located difference and a differing digest are
        #   two readings of one computation. Re-rendering either side for the comparison would let
        #   the report and the verdict disagree, which is the one failure mode a verification pass
        #   must not have.
        disagreeing = tuple(
            name
            for name, source_value, target_value in zip(
                ordered, source_values, target_values, strict=True
            )
            if source_value != target_value
        )
        if not disagreeing:
            continue
        differing_records += 1
        for name in disagreeing:
            if len(differences) < reported_differences:
                differences.append(
                    ChecksumDifference(
                        ordinal=ordinal,
                        kind=DifferenceKind.FIELD,
                        field=declared.get(name),
                        field_name=name,
                    )
                )
            else:
                withheld += 1
    return ChecksumComparison(
        record_name=record_name,
        qualified_table=qualified_table,
        source=RecordDigest(digest=source_hasher.hexdigest(), records=source_count, fields=ordered),
        target=RecordDigest(digest=target_hasher.hexdigest(), records=target_count, fields=ordered),
        differences=tuple(differences),
        differing_records=differing_records,
        absent_records=absent_records,
        withheld_differences=withheld,
    )
