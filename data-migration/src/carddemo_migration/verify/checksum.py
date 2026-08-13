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

import datetime as _datetime
import enum
import hashlib
import itertools
import uuid as _uuid
from collections.abc import Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from types import MappingProxyType
from typing import Final

# WHY : Assumptions: the four timestamp names are taken from the SUBPACKAGE boundary rather
#   than from `copybook.timestamp` directly, because that boundary declares itself the stable
#   spelling for exactly this and re-exports the curated set through its own `__all__`. Importing
#   the submodule would bind this pass to an internal path the subpackage is free to rearrange,
#   for no gain: the names reached either way are the same objects.
# WHY : Refactoring Rationale: `ISO_FORM` is the fourth, and the boundary was extended to publish
#   it when this pass began admitting a driver-native `datetime` on the read-back side. That value
#   has to be rendered back into the ONE spelling the load direction writes, and `canonical` next
#   to it renders only a str. Taking the directive from the boundary is what keeps this pass and
#   `loaders.aurora`'s timestamp projection rendering the same instant identically; a format string
#   re-declared here would be a second calendar spelling that could drift from the one loaded.
from carddemo_migration.copybook import (
    ADMITTED_FORMS,
    ISO_FORM,
    is_admitted,
    is_unwritten,
    layouts,
)

__all__ = [
    "DIGEST_NAME",
    "Canon",
    "FRAME_DELIMITER",
    "REPORTED_DIFFERENCE_LIMIT",
    "SEALED_ENVELOPE_MARKERS",
    "SEALED_ENVELOPE_MIN_BYTES",
    "ChecksumComparison",
    "ChecksumDifference",
    "ChecksumValueError",
    "ChecksumVerificationError",
    "DifferenceKind",
    "NULL_RENDERING",
    "RecordDigest",
    "SealableValueTally",
    "SealedColumnAudit",
    "TimestampContractError",
    "audit_sealed_columns",
    "canonical_key",
    "canonicalisation_of",
    "compare_record_digests",
    "refined_canonicalisation",
    "deterministic_field_names",
    "digest_of_record",
    "digest_records",
    "key_field_names",
    "normalized_timestamp_field_names",
    "validated_timestamp",
]

# WHY : Alternatives Considered: SHA-256 rather than a CRC or MD5. A CRC is designed to detect
#   transmission noise and collides readily under deliberate or structured change, and MD5's
#   collision resistance is broken. This digest is a data-integrity claim about money records
#   that an auditor may rely on, so the cost of a stronger hash -- which is negligible beside
#   the input decode -- buys a claim that does not need qualifying.
DIGEST_NAME: Final[str] = "sha256"

# WHY : ⚠️ Refactoring Rationale: the framing is TYPE-AND-LENGTH PREFIXED, and it replaces a
#   delimiter framing that joined fields on U+001F and terminated records with U+001E. The
#   reasoning behind those delimiters was recorded here as "byte values that cannot occur in a
#   decoded field" -- and that reasoning is FALSE of this corpus. The readers decode text spans
#   through cp037 and through the ASCII seed path, and both admit control code points: cp037 maps
#   0x1F to U+001F and 0x1E to U+001E, so a corrupt or mis-sliced span can carry either. The field
#   pair ("A", "B\x1fC") and the pair ("A\x1fB", "C") therefore produced BYTE-IDENTICAL hash
#   input, and a value carrying U+001E could terminate a record early -- so two different loads
#   could verify as one. That is precisely the false agreement this pass exists to rule out.
# WHY : Assumptions: the frame is `<tag>:<decimal length>:<payload>`, which is injective for any
#   payload whatsoever. The tag is one ASCII letter naming the VALUE CLASS, the length is the
#   payload's exact byte count in decimal ASCII, and the payload follows unaltered -- so a reader
#   of the byte stream recovers the same field boundaries the writer used no matter which bytes the
#   payload holds. Alternatives Considered: escaping the two separators instead, which is the
#   smaller edit. Rejected because an escape scheme has to be injective too and is easy to get
#   subtly wrong, whereas a length prefix cannot be ambiguous; and because the tag buys a second
#   property the delimiters never had -- the class of a value is inside the digest, so a text
#   "11" and an integer 11 cannot digest alike by accident.
FRAME_DELIMITER: Final[bytes] = b":"

# WHY : Assumptions: each tag names a VALUE CLASS rather than a Python type, because the two sides
#   of a comparison reach the same logical value through different Python types -- the source
#   decodes an unsigned display key to Decimal while the driver reads the BIGINT column it loads
#   into back as int, and the source carries a date as ten characters while the driver reads the
#   DATE column back as datetime.date. Canonicalising by CLASS is what makes those pairs compare
#   equal without making a text field and a numeric field compare equal, and it is why the tag is
#   derived from the field's `Canon` rule rather than from `type(value)`.
# WHY : Assumptions: the tags exist because an UNTAGGED rendering collapsed values that are not the
#   same stored value. `None` and the empty string both rendered to nothing at all, and
#   `Decimal("5")`, `"5"` and `b"5"` all rendered to `b"5"` -- so a digest could not distinguish an
#   absent middle name from an empty one, or an exact five from the character five, and would
#   certify a load that had turned one into the other.
# WHY : Alternatives Considered: single C0 bytes for the tags, chosen to be disjoint from a
#   separator-delimited framing. Rejected together with that framing: the delimiters were withdrawn
#   above once the corpus was shown able to carry both of them, and with a length-prefixed frame a
#   tag needs no byte-value property at all -- so a readable letter is preferred, because it is what
#   an operator sees in a report's identity string.
_TAG_TEXT: Final[bytes] = b"s"
_TAG_INTEGER: Final[bytes] = b"n"
_TAG_DECIMAL: Final[bytes] = b"d"
_TAG_BYTES: Final[bytes] = b"b"
_TAG_ABSENT: Final[bytes] = b"z"
_TAG_FIELD: Final[bytes] = b"f"
_TAG_RECORD: Final[bytes] = b"r"

# WHY : Assumptions: a NULL is rendered as its own byte rather than as the empty string, because
#   the two are DIFFERENT stored values and a digest that conflated them would report agreement
#   between a column holding a zero-length string and one holding no value at all. The load
#   projections make that distinction real: `TRIMMED_OR_NULL` yields `None` exactly where trimming
#   left nothing, so a record can carry the empty string in one field and a NULL in another.
# WHY : Alternatives Considered: a longer sentinel string such as b"<NULL>". Rejected for the same
#   reason the separators are control bytes -- a printable sentinel is a value a text field could
#   itself hold, so a customer whose address line read "<NULL>" would digest identically to one
#   whose address line was absent. The group separator is outside the range a decoded character
#   field or a rendered decimal can produce, and the only comparable values that are raw bytes are
#   the sealed columns, which every target excludes from its comparable field set by construction.
# WHY : ⚠️ Assumptions: "outside the range" is scoped to the layouts this pass DIGESTS, and the
#   scope is load-bearing rather than a hedge. Measured over all twenty-one shipped extracts, 0x1D
#   occurs eight times and every one of them is in AWS.M2.CARDDEMO.EXPORT.DATA.PS -- the
#   packed-decimal export record, whose bytes are a COMP-3 field's nibbles and can take any value.
#   No layout this pass digests reads that file, so the sentinel cannot collide with a comparable
#   value; a future pass that did digest packed bytes as characters would have to revisit this.
# WHY : Alternatives Considered: a single NUL byte, which a competing draft of this module proposed
#   and this measurement rules out decisively -- 0x00 occurs 4153 times across the same extracts,
#   because the readers treat a trailing low value as content rather than as padding. A doubled-NUL
#   bracket around the word NULL was the same draft's answer and would also have been safe, at zero
#   occurrences; it is not adopted because a multi-byte printable-cored sentinel sits outside the
#   control-byte scheme the frame delimiter and the one-letter class tags already establish here.
NULL_RENDERING: Final[bytes] = b"\x1d"

# WHY : Assumptions: the tag distinguishes the three decoded types, so a text field holding
#   "1.50" and a `Decimal("1.50")` in the same position cannot frame identically. Without the tag
#   the length prefix alone would still admit that collision, and it is a real one rather than a
#   contrived one: `prepare_record` projects some columns to text and leaves others exact, so the
#   same characters genuinely arrive as either type depending on the projection. A one-byte tag
#   is chosen over a longer type name because the digest is never parsed back by anything but the
#   documentation of this scheme, so the shortest distinguishing token is sufficient.

# WHY : Refactoring Rationale: a SECOND field-framing design stood here -- `_type_tag`,
#   `_framed_field` and the three `_TYPE_TAG_*` class bytes -- and it is withdrawn. It framed a
#   field as a one-letter class tag, the payload length in ASCII, a FIELD_SEPARATOR and then the
#   payload. Two independent facts retired it. It was UNREACHABLE: `_framed_field` had no caller,
#   `_type_tag` was called only from `_framed_field`, and the three class bytes were read only by
#   `_type_tag`, while the live path frames through `_record_frame` and `_canonical_values`. It was
#   also NOT CALLABLE: it referenced `FIELD_SEPARATOR`, which this module no longer defines,
#   because the record header now DECLARES its field count and body length instead of delimiting
#   them -- so calling it would have raised NameError rather than framing anything.
# WHY : Assumptions: the properties that design existed to guarantee are kept, not dropped. A
#   payload byte equal to a separator can no longer frame as a boundary, and a text value and an
#   exact decimal spelling the same characters still digest differently, because the declared
#   header carries length and class. Those two properties are asserted directly against the live
#   path in `test_verification.py`, which is why the tests that came with this design are kept
#   while the design itself is not: a test of a property outlives the implementation it was
#   written against, and deleting it with the code would have removed the only statement that
#   the surviving implementation still holds the property.

# WHY : Trade-offs: the per-field differences a comparison RECORDS are bounded while the counts
#   it reports are exact. A load that wrote every row wrong would otherwise produce one report
#   line per field per record -- for the transaction master, thirteen lines times three hundred
#   thousand records -- and the operator who has to act on it would receive a file rather than a
#   diagnostic, held entirely in memory to be rendered. The bound costs the tail of a large
#   failure, which the withheld count still reports as a number, and it keeps the localisation
#   readable for the case it was built for: a handful of records out of many.
REPORTED_DIFFERENCE_LIMIT: Final[int] = 20

# WHY : Assumptions: an aware instant's offset is appended rather than dropped, and the suffix is
#   declared here so the rendering has one spelling. No column in the migration is
#   `TIMESTAMP WITH TIME ZONE` -- the four stamp columns are `TIMESTAMP(6)`, so a driver returns a
#   naive `datetime` and this suffix is unreachable today. It exists because the alternative for an
#   aware value is to render only its wall-clock part, which would make two instants ten hours
#   apart digest identically; a verification pass must not have a case in which it quietly compares
#   less than it was given.
_UTC_OFFSET_FORM: Final[str] = "%z"

# WHY : Assumptions: the admitted domain is declared as a type alias rather than repeated at each
#   of the SIX public signatures that carry it, so widening it is one edit and a reader sees the
#   whole domain in one place. Two members are formally redundant and deliberately absent:
#   `datetime` is a subclass of `date` and `bool` of `int`, so both are already admitted -- and
#   both are handled EXPLICITLY by the renderers, ahead of the base they derive from, because the
#   subclass check has to come first for the rendering to be the subclass's own.
# WHY : Assumptions: the alias is used at the PUBLIC entry points only, and the private renderers
#   below keep `object`. That asymmetry is deliberate: those helpers are the layer that refuses a
#   value outside this domain, so narrowing them to the domain would misplace the enforcement --
#   the parameter would read as though an out-of-domain value could not arrive, when refusing one
#   is the helper's job. `bool` is the case that makes this concrete: it satisfies the alias
#   through `int` and is refused at the renderer with its own exact-cents reason.
type ChecksumValue = (
    str | Decimal | bytes | bytearray | memoryview | int | _datetime.date | _uuid.UUID | None
)


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


# WHY : Assumptions: this refusal is BOTH a verification error and a `TypeError`, and the second
#   base is load-bearing rather than decorative. Every consumer that classifies a step's outcome
#   catches the verification base, so the exit tier is unchanged; and what this refusal reports is
#   always the same thing -- a value of a type no migrated column holds reached the canonicaliser --
#   which is what `TypeError` means in Python. Naming only the verification base made a caller
#   asserting on the CATEGORY of fault have to know this module's own hierarchy, and naming only
#   `TypeError` would have let the refusal escape every step guard as an unclassified traceback.
class ChecksumValueError(ChecksumVerificationError, TypeError):
    """Raised when a value belongs to no class this pass can canonicalise.

    Purpose
    -------
    Keep every refusal this module can raise inside the typed hierarchy the command line's exit
    contract is built on.

    WHY : ⚠️ Refactoring Rationale: the renderers raised a bare :class:`TypeError` for an
        unrenderable value. ``cli._verify_checksum`` catches ``(*_DECODE_ERRORS,
        ChecksumVerificationError)``, so a ``TypeError`` bypassed it entirely and surfaced as a
        traceback rather than as one of the documented exit codes -- a verification run could
        therefore end with NO verdict at all, which is the one outcome a cutover gate cannot act
        on. Deriving from the module's own base is what puts the refusal back inside the contract.

    WHY : Assumptions: the message names the value's TYPE and never any part of its content, for
        the reason every diagnostic in this pass observes -- the values reaching it are cardholder
        data, and a refusal that quoted one would publish it to whatever reads the exit log.
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


class Canon(enum.Enum):
    """How one field's values are rendered for digesting, given the type its column holds.

    Purpose
    -------
    Name the small set of canonicalisation rules a comparison needs, so that the two sides of one
    load are rendered by the SAME rule even though they arrive as different Python types. The
    source side arrives from a fixed-width decoder as characters and exact decimals; the target
    side arrives from the database driver as integers, dates, timestamps, decimals and nulls. A
    rule per field is what makes those two readings comparable.

    ⚠️ Refactoring Rationale: this exists because the renderer accepted three types -- characters,
    exact decimals and raw bytes -- and the read-back carries four more. Verification either
    raised ``TypeError`` on the first integer column it met, or, where it did not raise, compared a
    zero-padded source identifier against the integer the column stores and reported a correct
    load as corrupt. Both outcomes are worse than no verification, because both are indistinguish-
    able from a real defect at the point an operator reads the report.

    Parameters
    ----------
    None
        An enumeration declares members, not parameters.

    Raises
    ------
    None
        Membership is static.
    """

    INTEGER = "integer"
    """A whole number, whatever type carries it.

    WHY : Assumptions: this is the rule for a field the copybook declares as unsigned display --
        ``PIC 9(n)`` -- because every such field in this corpus is mapped to an integral column.
        The decoder yields the digits with their declared leading zeros, the column stores the
        NUMBER, and the driver reads back an ``int``: ``"00000000001"`` and ``1`` are one value
        recorded twice. Rendering both through the integer normalises the padding away.

    WHY : Trade-offs: normalising the padding cannot mask a real difference, which is the property
        that makes it safe. Both sides pass through the same rule, so two values that differ as
        numbers still differ as renderings; what is discarded is only a leading zero the target
        never stored.
    """

    EXACT = "exact"
    """An exact decimal, carrying its own scale.

    WHY : Assumptions: the scale is PRESERVED rather than normalised, because a numeric column's
        scale is part of its contract -- the reasoning is recorded on the renderer itself. An
        integer arriving on this rule is widened to a decimal rather than rejected, because a
        column declared ``NUMERIC`` with no fractional digits reads back as an ``int``.
    """

    TEXT = "text"
    """Characters, or a date or timestamp the column stores as a temporal value.

    WHY : Assumptions: one rule covers all three because the source side of every one of them is
        characters. A ``PIC X(10)`` holding ``"2025-05-20"`` is loaded into a ``DATE`` column and
        read back as a date; the same ten characters are what the date renders to. Splitting this
        into three rules would need the column's type, which this pass would then hold a second
        opinion about; rendering a temporal value back to the characters it came from needs only
        the value in hand.
    """

    UUID = "uuid"
    """A universally unique identifier, whichever of its two forms carries it.

    WHY : ⚠️ Assumptions: this rule exists for ONE field and would be an over-generalisation
        without it. The security-user record's subject reference is resolved from the published
        seed-user document as CHARACTERS and stored in a ``UUID`` column, which the driver reads
        back as an identifier OBJECT -- so the two sides of that one comparison are a string and a
        UUID. Rendering both through the parsed identifier also makes the comparison insensitive to
        letter case and to the presence of the hyphens, neither of which the column stores: two
        documents naming one subject in different spellings describe the same row, and reporting
        them as a difference would send an operator looking for data corruption that is not there.

    WHY : Trade-offs: a malformed source value now fails with a stated reason instead of being
        digested as characters that could never match. That is the intended direction -- the
        alternative reports "this field differs" for every row and names no cause.
    """

    BYTES = "bytes"
    """Raw bytes, rendered verbatim.

    WHY : Assumptions: retained for completeness rather than for use. The comparable field set
        excludes every enciphered column, because an envelope's initialisation vector is drawn per
        value and would differ on every run; this rule therefore has no field in the corpus today
        and exists so that a caller digesting an opaque field is not forced onto a text rule that
        would corrupt it.
    """


def canonicalisation_of(layout: layouts.RecordSpec) -> Mapping[str, Canon]:
    """Derive the per-field canonicalisation rule from a record's declared storage regimes.

    Purpose
    -------
    Produce the rule map a comparison needs from the ONE authority that already describes every
    field -- the layout. Deriving it means no second table can come to disagree with the copybook
    about what a field holds.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record descriptor whose fields are classified.

    Returns
    -------
    Mapping[str, Canon]
        One rule per declared field, keyed by field name. Fields a comparison does not name are
        harmless: the map is consulted by name and a caller compares the fields it chose.

    Raises
    ------
    None
        Every regime the layout can declare maps to a rule, so classification is total.
    """
    # WHY : Assumptions: the packed and zoned regimes share the exact-decimal rule and the binary
    #   regime joins them, because a binary integer widens to a decimal without loss while the
    #   reverse -- forcing a scaled amount through the integer rule -- would discard its cents.
    #   Choosing the wider rule where two could apply is the safe direction here.
    rules = {
        layouts.Kind.UINT: Canon.INTEGER,
        layouts.Kind.ZONED: Canon.EXACT,
        layouts.Kind.PACKED: Canon.EXACT,
        layouts.Kind.BINARY: Canon.EXACT,
        layouts.Kind.TEXT: Canon.TEXT,
        layouts.Kind.OPAQUE: Canon.BYTES,
    }
    return {field.name: rules[field.kind] for field in layout.fields}


def refined_canonicalisation(
    base: Mapping[str, Canon],
    target_records: Sequence[Mapping[str, ChecksumValue]],
    fields: Sequence[str],
) -> Mapping[str, Canon]:
    """Narrow the declared rules to what the loaded columns actually hold.

    Purpose
    -------
    Settle the one question the copybook alone cannot answer: which form the TARGET stores a field
    in. A declared regime says what the source characters mean, and for most fields that determines
    the column -- but not for all of them, and the exceptions are the ones that matter.

    ⚠️ Refactoring Rationale: deriving the rules from the declared regime alone was the first
    attempt and it was wrong for five fields. The transaction category code is declared
    ``PIC 9(04)`` -- unsigned display, which the regime rule classifies as a whole number -- and it
    is loaded into ``CHAR(4)``, because specification rule T1 treats a numeric field used as a fixed
    code as a fixed-width code whose leading zeros are part of the value. Comparing those five as
    whole numbers would still have passed every correct load, which is what makes the mistake worth
    recording: it would ALSO have passed a corruption that changed the padding, rendering a stored
    ``"1   "`` and a source ``"0001"`` as the same number. Trading a false failure for a false pass
    is a worse outcome than the defect being fixed.

    Assumptions: the narrowing reads the values the driver returned rather than a table of column
    types kept here. One column yields one Python type, and the driver derives it from the column's
    own declaration, so the observation IS the target's declaration -- and unlike a table in this
    package it cannot drift from the schema.

    Parameters
    ----------
    base : Mapping[str, Canon]
        The declared rules, as :func:`canonicalisation_of` derives them from the layout.
    target_records : Sequence[Mapping[str, ChecksumValue]]
        The rows read back from the target. An empty sequence leaves every rule as declared.
    fields : Sequence[str]
        The field names being compared.

    Returns
    -------
    Mapping[str, Canon]
        The rules to render both sides by: the observed rule for every field a loaded row carries a
        value for, and the declared rule for the rest.

    Raises
    ------
    None
        A field with no observable value keeps its declared rule, which is why this cannot fail on
        an empty table or an all-null column.
    """
    observed = dict(base)
    for name in fields:
        for record in target_records:
            value = record.get(name)
            if value is None:
                # Assumptions: a null observes nothing, so the search continues to the next row
                #   rather than concluding. A nullable column whose first row happens to be unset
                #   would otherwise keep the declared rule while a later row could have settled it.
                continue
            rule = _observed_rule(value)
            if rule is not None:
                observed[name] = rule
            break
    return observed


def _observed_rule(value: object) -> Canon | None:
    """Classify one loaded value by the form the column returned it in.

    Parameters
    ----------
    value : object
        A value read back from the target, never ``None``.

    Returns
    -------
    Canon | None
        The rule the value's form implies, or ``None`` for a form that implies none, which leaves
        the declared rule in force.

    Raises
    ------
    None
        An unrecognised form yields ``None`` rather than raising, because a rule this function
        cannot settle is one the layout has already settled.
    """
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return Canon.INTEGER
    if isinstance(value, Decimal):
        return Canon.EXACT
    if isinstance(value, (str, _datetime.date)):
        # WHY : Assumptions: characters, dates and timestamps share ONE rule because the source side
        #   of all three is characters -- the text rule renders a date back to the ten characters it
        #   was loaded from and a stamp back to its twenty-six. A date is matched here through
        #   `date`, which covers `datetime` as its subclass; the renderer distinguishes the two in
        #   the other direction, where the order does matter.
        return Canon.TEXT
    if isinstance(value, _uuid.UUID):
        return Canon.UUID
    if isinstance(value, (bytes, bytearray, memoryview)):
        return Canon.BYTES
    return None


def _integer_rendering(value: object) -> bytes:
    """Render a whole number as its canonical digits, whatever type carried it.

    Parameters
    ----------
    value : object
        An ``int``, a decimal with no fractional part, or characters holding digits with any
        number of leading zeros.

    Returns
    -------
    bytes
        The number's canonical decimal digits, or :data:`NULL_RENDERING` when the characters are
        blank, which is how an unwritten fixed-width numeric field arrives.

    Raises
    ------
    TypeError
        If the value is of no type this rule can render.
    ValueError
        If characters on this rule are not digits, which means the field's declared regime and its
        loaded column disagree and the comparison would otherwise report a difference at every
        record without saying why.
    """
    if isinstance(value, bool):
        # WHY : Assumptions: a boolean is refused rather than rendered as 0 or 1, because Python
        #   treats it as an integer and no field in this corpus is declared boolean. Accepting one
        #   would silently digest a mis-typed value as a number.
        raise ChecksumValueError("a boolean is not a value any declared numeric field holds")
    if isinstance(value, int):
        return str(value).encode("utf-8")
    if isinstance(value, Decimal):
        # WHY : Assumptions: a FRACTIONAL decimal is refused rather than rounded to a whole
        #   number. A driver may return a `numeric` column as a Decimal, and an unsigned display
        #   field loads into an integral column, so the pairing is expected -- but if a fraction
        #   ever arrives here the declared regime and the loaded column disagree, and
        #   `to_integral_value()` would turn 100.9 into a plausible 101 that compares equal to a
        #   value the source never held. Refusing names the field instead.
        # WHY : Trade-offs: this rule could have declined the value and let it fall through to the
        #   exact-decimal rendering, which would report a difference rather than an error. The
        #   error is preferred because the difference would recur on every row of the dataset with
        #   no indication that the CAUSE is a mis-declared field rather than corrupted data.
        if value != value.to_integral_value():
            raise ChecksumValueError(
                "a field declared as unsigned display carries a fractional value, so its declared"
                " regime and the column it loads into disagree; it is refused rather than rounded"
                " into a whole number the source never held"
            )
        return str(value.to_integral_value()).encode("utf-8")
    if isinstance(value, str):
        if not value.strip():
            return NULL_RENDERING
        try:
            return str(int(value.strip())).encode("utf-8")
        except ValueError as exc:
            raise ValueError(
                "a field declared as unsigned display carries characters that are not digits, so"
                " it cannot be compared against the integral column it loads into"
            ) from exc
    raise ChecksumValueError(
        f"a value of type {type(value).__name__} cannot be rendered as a whole number"
    )


def _exact_rendering(value: object) -> bytes:
    """Render an exact decimal at its own scale, whatever type carried it.

    Parameters
    ----------
    value : object
        A ``Decimal``, an ``int``, or characters holding a decimal literal.

    Returns
    -------
    bytes
        The decimal's rendering at its own scale, or :data:`NULL_RENDERING` for blank characters.

    Raises
    ------
    TypeError
        If the value is of no type this rule can render.
    ValueError
        If characters on this rule are not a decimal literal.
    """
    if isinstance(value, bool):
        raise ChecksumValueError("a boolean is not a value any declared numeric field holds")
    if isinstance(value, Decimal):
        # WHY : Assumptions: rendered through `str` at its own scale, never through `float` and
        #   never normalised. The reasoning is the module's original one and is unchanged: a
        #   NUMERIC(12,2) holding 1.50 is not the same stored value as one holding 1.5, and no
        #   binary float can represent ten cents exactly.
        return str(value).encode("utf-8")
    if isinstance(value, int):
        return str(Decimal(value)).encode("utf-8")
    if isinstance(value, str):
        if not value.strip():
            return NULL_RENDERING
        try:
            return str(Decimal(value.strip())).encode("utf-8")
        except InvalidOperation as exc:
            raise ValueError(
                "a field declared in a numeric regime carries characters that are not an exact"
                " decimal, so it cannot be compared against the numeric column it loads into"
            ) from exc
    raise ChecksumValueError(
        f"a value of type {type(value).__name__} cannot be rendered as an exact decimal"
    )


def _uuid_rendering(value: object) -> bytes:
    """Render a unique identifier in one canonical spelling, whichever form carried it.

    Parameters
    ----------
    value : object
        An identifier object, or characters holding one in any admitted spelling.

    Returns
    -------
    bytes
        The identifier's canonical lower-case hyphenated form.

    Raises
    ------
    TypeError
        If the value is neither an identifier nor characters.
    ValueError
        If characters do not hold a unique identifier, which means the published document and the
        column disagree about what the field holds.
    """
    if isinstance(value, _uuid.UUID):
        return str(value).encode("utf-8")
    if isinstance(value, str):
        try:
            return str(_uuid.UUID(value.strip())).encode("utf-8")
        except ValueError as exc:
            raise ValueError(
                "a field stored as a unique identifier carries characters that are not one, so it"
                " cannot be compared against the column it loads into"
            ) from exc
    raise ChecksumValueError(
        f"a value of type {type(value).__name__} cannot be rendered as a unique identifier"
    )


def _text_rendering(value: object) -> bytes:
    """Render characters, or the characters a temporal value came from.

    Parameters
    ----------
    value : object
        Characters, a ``date``, a ``datetime`` or raw bytes.

    Returns
    -------
    bytes
        The characters, verbatim for a string; the ISO day for a date; the twenty-six character
        stamp for a timestamp.

    Raises
    ------
    TypeError
        If the value is of no type this rule can render.
    """
    if isinstance(value, str):
        return value.encode("utf-8")
    if isinstance(value, bytes):
        return value
    # WHY : Assumptions: the datetime test precedes the date test, and the order is not
    #   interchangeable -- `datetime` is a SUBCLASS of `date`, so testing date first would render
    #   every timestamp as its day alone and report two rows differing only in their time as
    #   identical. That is a silent loss of a whole field's content.
    if isinstance(value, _datetime.datetime):
        # WHY : Assumptions: rendered to the reference's own twenty-six character form -- a space
        #   between the day and the time, six fractional digits, no zone -- because that is the
        #   form the source side carries as characters and the form `copybook.timestamp` admits.
        #   `isoformat` would emit a `T` and would drop the fractional digits entirely at whole
        #   seconds, so a stored stamp of exactly midnight would render differently from the
        #   twenty-six characters it was loaded from.
        # WHY : Refactoring Rationale: the directive is the boundary's published `ISO_FORM` rather
        #   than a format string spelled out here. A literal at this site would be a SECOND calendar
        #   spelling of the one `loaders.aurora` writes and `copybook.timestamp` parses, and two
        #   spellings of one format drift silently: the day they disagree, a correctly loaded stamp
        #   verifies as corrupt and the report names the field rather than the format.
        return value.strftime(ISO_FORM).encode("utf-8")
    if isinstance(value, _datetime.date):
        return value.isoformat().encode("utf-8")
    raise ChecksumValueError(
        f"a value of type {type(value).__name__} cannot be rendered as characters"
    )


_RENDERERS: Final[Mapping[Canon, object]] = {
    Canon.INTEGER: _integer_rendering,
    Canon.EXACT: _exact_rendering,
    Canon.TEXT: _text_rendering,
    Canon.UUID: _uuid_rendering,
    Canon.BYTES: _text_rendering,
}


# WHY : Assumptions: the tag table parallels the renderer table above rather than living on the
#   enum, so that adding a rule without deciding its value class is a KeyError at the one call
#   site instead of a silently untagged rendering. UUID renders under the text tag deliberately:
#   its canonical form IS characters on both sides, and giving it a tag of its own would make a
#   correctly-loaded identifier differ from the same characters read back from a text column.
_TAGS: Final[Mapping[Canon, bytes]] = {
    Canon.INTEGER: _TAG_INTEGER,
    Canon.EXACT: _TAG_DECIMAL,
    Canon.TEXT: _TAG_TEXT,
    Canon.UUID: _TAG_TEXT,
    Canon.BYTES: _TAG_BYTES,
}


def _canonical_by(canon: Canon | None, value: object) -> bytes:
    """Render one value under a declared rule, or under the type-total default when there is none.

    Parameters
    ----------
    canon : Canon | None
        The rule for the field, or ``None`` to render by the value's own type alone.
    value : object
        The value to render.

    Returns
    -------
    bytes
        The canonical rendering.

    Raises
    ------
    TypeError
        If the value is of no type the rule can render.
    ValueError
        If the value cannot satisfy a numeric rule.
    """
    # WHY : Assumptions: the rendering is TAGGED with the value class its rule names, so the
    #   class is inside the digest rather than merely implied by the bytes. Without the tag the
    #   text "11" and the integer 11 render to the same three bytes and digest alike, which would
    #   report a text column loaded into a numeric one -- or the reverse -- as agreement. The tag
    #   comes from the RULE and not from `type(value)`, because the two sides of one comparison
    #   reach the same logical value through different Python types by design.
    return _frame(_class_tag(canon, value), _rendered_value(canon, value))


def _class_tag(canon: Canon | None, value: object) -> bytes:
    """Name the value class a rendering is framed under.

    Parameters
    ----------
    canon : Canon | None
        The rule for the field, or ``None`` to take the class from the value itself.
    value : object
        The value whose class is named when there is no rule.

    Returns
    -------
    bytes
        The one-letter class tag.

    Raises
    ------
    None
        A value the tag cannot be taken from is refused by the renderer, not here.
    """
    if value is None:
        return _TAG_ABSENT
    if canon is not None:
        return _TAGS[canon]
    # WHY : ⚠️ Assumptions: with no rule to name the class, the class is taken from the value ITSELF
    #   through `_observed_rule` rather than defaulting to text. Defaulting would tag a text "11"
    #   and
    #   an integer 11 identically, and since both render to the same three bytes they would then
    #   digest alike -- so a text column loaded into a numeric one, or the reverse, would verify as
    #   agreement. `_observed_rule` answers None only for a value no renderer accepts, and the
    #   renderer refuses that value anyway.
    observed = _observed_rule(value)
    return _TAGS.get(observed, _TAG_TEXT) if observed else _TAG_TEXT


def _rendered_value(canon: Canon | None, value: object) -> bytes:
    """Render one value under its rule, UNFRAMED, for display and for ordering.

    Purpose
    -------
    Separate the rendering from the framing, because the two have different consumers with
    incompatible requirements. The digest needs the frame, which makes the encoding injective; the
    report's identity string and the pairing sort need the bare rendering, because a length prefix
    is neither readable nor order-preserving.

    Parameters
    ----------
    canon : Canon | None
        The rule for the field, or ``None`` to render by the value's own type alone.
    value : object
        The value to render.

    Returns
    -------
    bytes
        The rendering, with no class tag and no length prefix.

    Raises
    ------
    ChecksumValueError
        If the value is of no type the rule can render.
    ValueError
        If the value cannot satisfy a numeric rule.
    """
    # WHY : ⚠️ Refactoring Rationale: this body used to BE `_canonical_by`, and the framing was
    #   added around it in place. Extracting it is not tidying: adding the frame in place put the
    #   tag and the length into the report's identity string -- which then read `n:1:2` where an
    #   operator needs `2` -- and into the pairing sort key, where the length prefix orders 9 after
    #   1000000000 because its first digit decides. Two consumers with opposite requirements were
    #   reading one function.
    if value is None:
        return NULL_RENDERING
    if canon is None:
        return _canonical(value)
    renderer = _RENDERERS[canon]
    return renderer(value)  # type: ignore[operator]


def _canonical(value: object) -> bytes:
    """Render one decoded value as the bytes the digest consumes.

    Purpose
    -------
    Make the rendering of each value type total and unambiguous, so the same data digests
    identically on any host and in any Python build. This is the PAYLOAD only: the framing that
    makes a sequence of payloads unambiguous is applied by :func:`_record_frame`.

    Parameters
    ----------
    value : object
        A decoded field value, or a value read back from the loaded row.

    Returns
    -------
    bytes
        The canonical byte rendering, carrying no framing of its own.

    Raises
    ------
    ChecksumValueError
        If the value is outside the admitted domain. Also catchable as :exc:`TypeError`, which is
        the contract the digest entry points published before this pass classified its own
        refusals, and which the class keeps as its second base for exactly that reason.
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
    # WHY : ⚠️ Refactoring Rationale: the four types below were absent and the function raised on
    #   each of them. A reader does yield only characters, exact decimals and bytes -- which is why
    #   the original three were the whole set -- but the comparison digests the LOADED rows too, and
    #   the driver reads an integral column back as an `int`, a date column as a `date`, a timestamp
    #   column as a `datetime` and a null as `None`. Verification therefore raised on the first
    #   integral column it reached, which on this corpus is the primary key of the first dataset.
    # WHY : Assumptions: this default renders by TYPE alone and cannot normalise across types -- it
    #   renders `1` and `"00000000001"` differently, because with no declared rule in hand it has no
    #   licence to decide that the padding is not data. A caller comparing two sides that carry one
    #   value in two types passes a `Canon` map, and `_canonical_by` applies it. This path exists so
    #   that a caller digesting ONE side, where the types are already uniform, needs no map.
    if value is None:
        # WHY : Assumptions: a null renders to the dedicated sentinel here as well as on the rule
        #   path, so the two entry points cannot disagree about what an absent value digests to.
        #   Leaving it to the rule path alone would make a single-sided digest raise on a nullable
        #   column while a comparison of the same data succeeded.
        # WHY : Assumptions: `None` is admitted rather than refused because it is a value BOTH sides
        #   genuinely hold. `aurora.prepare_record` yields it for the `TRIMMED_OR_NULL` and
        #   `TIMESTAMP_OR_NULL` projections and a driver returns it for the same nullable
        #   columns, so refusing it made every record carrying an unset middle name or an
        #   unwritten stamp
        #   undigestible -- the SOURCE side included, which is why this is not merely a read-back
        #   concern.
        return NULL_RENDERING
    if isinstance(value, bool):
        raise ChecksumValueError(
            "a boolean is not a decoded field value; no field in this corpus is declared boolean"
            " and rendering one as a number would digest a mis-typed value silently"
        )
    # WHY : Assumptions: a driver hands a `bytea` column back as a MEMORYVIEW over its own wire
    #   buffer, and a `bytearray` is the mutable twin of the same bytes. Both are COPIED into
    #   immutable bytes rather than digested in place: a view is valid only while the cursor's
    #   buffer lives, so digesting the view itself would either raise once the buffer was released
    #   or -- far worse -- silently read memory the driver had reused for another row.
    # WHY : Assumptions: the copy renders IDENTICALLY to the same bytes arriving as `bytes`, which
    #   is what lets one side of a comparison come from a reader and the other from a cursor. A
    #   branch that rendered a view differently would report a difference on a correct load for
    #   every sealed column, which is precisely the column class this path exists to reach.
    if isinstance(value, (memoryview, bytearray)):
        return bytes(value)
    if isinstance(value, int):
        return str(value).encode("utf-8")
    if isinstance(value, _datetime.datetime):
        # WHY : Assumptions: the directive is the boundary's published `ISO_FORM`, the same constant
        #   the rule-driven renderer and `loaders.aurora`'s projection use. A format string spelled
        #   out here would be a second calendar spelling of one format, and two spellings drift:
        #   the day they disagree, a correctly loaded stamp verifies as corrupt.
        return value.strftime(ISO_FORM).encode("utf-8")
    if isinstance(value, _datetime.date):
        return value.isoformat().encode("utf-8")
    if isinstance(value, _uuid.UUID):
        return str(value).encode("utf-8")
    raise ChecksumValueError(
        f"a decoded field value of type {type(value).__name__} cannot be digested; a reader"
        " yields characters, an exact monetary value or raw bytes, and a read-back row adds a whole"
        " number, a date, a timestamp and a null"
    )


def _require_fields(fields: Sequence[str]) -> tuple[str, ...]:
    """Freeze a caller's field selection into an order, refusing an empty one.

    Purpose
    -------
    Hold both digest entry points and the comparison to ONE refusal of an empty field set, so the
    three cannot come to disagree about whether digesting nothing is allowed. All three call it:
    :func:`digest_of_record`, :func:`digest_records` and :func:`compare_record_digests`.

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


def _frame(tag: bytes, payload: bytes) -> bytes:
    """Wrap one payload in the injective type-and-length frame the digest consumes.

    Purpose
    -------
    Give every value and every record an unambiguous boundary, so that no two different
    assignments of bytes to fields can produce one hash input. This is the mechanism the frame
    constants above record the reasoning for.

    Parameters
    ----------
    tag : bytes
        The one-letter value-class or container tag.
    payload : bytes
        The bytes being framed, unaltered and of any content.

    Returns
    -------
    bytes
        ``tag`` then the payload's decimal byte length then the payload, delimited by
        :data:`FRAME_DELIMITER`.

    Raises
    ------
    None
        Framing cannot fail: every byte string has a length and the tag is a module constant.
    """
    return tag + FRAME_DELIMITER + str(len(payload)).encode("ascii") + FRAME_DELIMITER + payload


def _record_frame(values: Sequence[bytes]) -> bytes:
    """Wrap one record's per-field frames in the record frame the digest consumes.

    Purpose
    -------
    Hold the record-level framing to ONE definition, so that the streaming digest and the lockstep
    comparison cannot come to frame a record differently -- which would make the two digests they
    compute incomparable while both looked correct in isolation.

    Parameters
    ----------
    values : Sequence[bytes]
        One canonical per-field rendering per digested field, in field order.

    Returns
    -------
    bytes
        The concatenated field frames inside one record frame.

    Raises
    ------
    None
        Framing cannot fail.
    """
    # WHY : ⚠️ Refactoring Rationale: the record boundary is a LENGTH-PREFIXED FRAME and it used
    #   to be a U+001E separator appended after every record. Appending rather than interposing did
    #   stop a one-record dataset colliding with a different framing, and it still left the
    #   collision this frame closes: a field VALUE carrying U+001E, which cp037 and the ASCII seed
    #   path both admit, could terminate a record early and make two different datasets digest
    #   alike.
    return _frame(_TAG_RECORD, b"".join(_frame(_TAG_FIELD, value) for value in values))


def _canonical_values(
    record: Mapping[str, object],
    fields: Sequence[str],
    canon: Mapping[str, Canon] | None = None,
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
    record : Mapping[str, object]
        One decoded record, or one row read back from the target. This helper keeps the widest
        annotation while the public entry points above it name :data:`ChecksumValue`, because it is
        the boundary at which a value outside that domain is refused -- with the FIELD NAME
        attached, which is the whole reason the refusal is caught and re-raised here.
    fields : Sequence[str]
        The field names to render, in order.
    canon : Mapping[str, Canon] | None
        The per-field canonicalisation rules, or ``None`` to render by value type alone. Supplying
        them is what lets one side's characters and the other side's integers, dates and
        timestamps render identically for values that are equal.

    Returns
    -------
    tuple[bytes, ...]
        One canonical rendering per named field, in the same order.

    Raises
    ------
    KeyError
        If the record does not carry a named field. The field NAME is in the message and no
        value is.
    ChecksumValueError
        If a value is outside the admitted domain. Also catchable as :exc:`TypeError`.
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
    rules = canon or {}
    rendered: list[bytes] = []
    for field in fields:
        try:
            rendered.append(_canonical_by(rules.get(field), record[field]))
        except ChecksumValueError as refused:
            # WHY : ⚠️ Assumptions: the refusal is re-raised carrying the FIELD NAME and never the
            #   value. A renderer sees a value and not the field it came from, so without this the
            #   message named a type and left an operator to find which of a hundred-odd fields
            #   carried it. The value stays out because a verification report is the artefact most
            #   likely to be pasted into an issue tracker and these records carry primary account
            #   numbers -- the type is diagnostic, the content is disclosure.
            raise ChecksumValueError(f"field {field}: {refused}") from refused
    return tuple(rendered)


def digest_of_record(
    record: Mapping[str, ChecksumValue],
    fields: Sequence[str],
    *,
    layout: layouts.RecordSpec | None = None,
) -> bytes:
    """Render one record as the bytes contributing to a digest.

    Purpose
    -------
    Frame one record's named fields into a single byte string, so that a shifted field boundary
    cannot produce the bytes a correct record produces. This is the unit the whole-dataset digest
    is built from and the unit a caller comparing two single records reaches for.

    Parameters
    ----------
    record : Mapping[str, ChecksumValue]
        One decoded record.
    fields : Sequence[str]
        The field names to include, in order.

    Returns
    -------
    bytes
        The record's canonical contribution: its field count, a field separator, its body length,
        a record separator, then the concatenated framed fields. The header is part of the return
        value, so concatenating two of these is unambiguous and a caller adds no separator of its
        own.

    Raises
    ------
    ValueError
        If no field is named, which would render every record to the same empty byte string.
    KeyError
        If the record does not carry a named field. The field NAME is in the message and no
        value is.
    ChecksumValueError
        If a value is outside the admitted domain. Also catchable as :exc:`TypeError`, which is
        the contract this function published before the pass classified its own refusals.
    """
    # WHY : Assumptions: the rules are DERIVED from the layout when one is supplied rather than
    #   asked of the caller, so the copybook stays the single authority on what each field holds.
    #   Without a layout each value is rendered by its own observed class, which is right for a
    #   caller comparing two readings of one side and wrong for a caller comparing a source against
    #   a read-back row -- there the same logical value arrives as two different Python types.
    return _record_frame(
        _canonical_values(
            record, fields, canonicalisation_of(layout) if layout is not None else None
        )
    )


def digest_records(
    records: Iterable[Mapping[str, ChecksumValue]],
    fields: Sequence[str],
) -> RecordDigest:
    """Digest a stream of decoded records over an explicit, ordered field set.

    Purpose
    -------
    Produce one comparable value for a whole dataset, computed in a single streaming pass so a
    large dataset never has to be held.

    Parameters
    ----------
    records : Iterable[Mapping[str, ChecksumValue]]
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
    ChecksumValueError
        If a value is outside the admitted domain. Also catchable as :exc:`TypeError`.
    """
    ordered = _require_fields(fields)
    hasher = hashlib.new(DIGEST_NAME)
    counted = 0
    for record in records:
        # WHY : Refactoring Rationale: this loop used to append a bare RECORD_SEPARATOR after each
        #   record, on the reasoning that terminating rather than interposing keeps a one-record
        #   dataset from colliding with a one-field framing. That reasoning was sound about
        #   ordering and unsound about content: a payload byte equal to the separator framed
        #   identically to the boundary, so two different streams could digest alike. The record's
        #   own header now carries its field count and body length, so the boundary is declared
        #   rather than delimited and no separator is appended here at all.
        hasher.update(digest_of_record(record, ordered))
        # WHY : Assumptions: the record separator is appended AFTER every record including the
        #   last, rather than interposed between records. Interposing makes the digest of one
        #   record equal to the digest of its own single field set, so a one-record dataset and
        #   a one-field-per-record framing could collide.
        counted += 1
    return RecordDigest(digest=hasher.hexdigest(), records=counted, fields=ordered)


def key_field_names(layout: layouts.RecordSpec) -> tuple[str, ...]:
    """Name the fields a record's own key window covers, in field order.

    Purpose
    -------
    Resolve the identity a comparison pairs two sides on from the DESCRIPTOR rather than from a list
    written here, so the pass pairs on the same identity the load conflicts on.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record descriptor whose key window is read.

    Returns
    -------
    tuple[str, ...]
        The covered field names in field order, empty when the descriptor declares no key window.

    Raises
    ------
    None
        Reading a declared window cannot fail.
    """
    # WHY : Assumptions: the window is read as a byte RANGE and a field is covered when it overlaps
    #   that range, rather than a name list being kept here. `loaders/aurora.py` derives the merge's
    #   conflict target from the same window, so pairing on it is what keeps the comparison keyed on
    #   the identity the load itself conflicts on -- two declarations of one identity could drift,
    #   and the drift would show up as a verification failure over a correct load.
    if not layout.key_length:
        return ()
    start = layout.key_offset
    end = start + layout.key_length
    return tuple(
        field.name
        for field in layout.fields
        if field.start < end and field.end > start and not field.suppressed
    )


def canonical_key(
    record: Mapping[str, ChecksumValue],
    fields: Sequence[str],
    *,
    layout: layouts.RecordSpec | None = None,
) -> bytes:
    """Render one record's key as the bytes both sides of a comparison are ordered by.

    Purpose
    -------
    Give the two sides of one load a single key rendering to sort on, so that neither the server's
    collation nor either side's arrival order decides which record is compared against which row.

    Parameters
    ----------
    record : Mapping[str, ChecksumValue]
        One prepared source record or one read-back row.
    fields : Sequence[str]
        The key field names, in order.
    layout : layouts.RecordSpec | None
        The descriptor naming each key field's rule, or ``None`` to render by observed class.

    Returns
    -------
    bytes
        The framed key rendering, comparable and orderable across both sides.

    Raises
    ------
    ValueError
        If no field is named, since every record would then render alike and the sort would be no
        sort at all.
    KeyError
        If the record does not carry a named key field. The field NAME is in the message and no
        value is.
    ChecksumValueError
        If a key value belongs to no canonicalisable class.
    """
    # WHY : ⚠️ Assumptions: the key is rendered through the same per-field CANONICALISATION the
    #   digest uses -- so an unsigned display identifier and the integer column it loads into render
    #   alike, which is what lets the two sides sort into one order at all -- but NOT through the
    #   digest's own length-prefixed FRAME. A length prefix is not order-preserving: framing 9 and
    #   12 yields `n:1:9` and `n:2:12`, which happens to order correctly, while framing 9 and
    #   1000000000 yields `n:1:9` and `n:10:1000000000`, where the prefix's first digit decides and
    #   puts the larger value first. The corpus reaches those widths -- an account identifier is
    #   eleven digits and a transaction identifier sixteen -- so the frame would misorder real keys.
    # WHY : Assumptions: `_identity_order` supplies the orderable rendering instead, which
    #   zero-pads a numeric key to a fixed width so that its byte order IS its numeric order, and
    #   the parts are joined by :data:`FRAME_DELIMITER`. The delimiter never decides an ordering
    #   here because every part of one key field is the same width on both sides, so the comparison
    #   is settled inside the parts themselves.
    # WHY : Trade-offs: sorting on these bytes orders a text key by code point rather than by any
    #   database collation, and that is the point. Ordering the read-back with `ORDER BY` instead
    #   would resolve through the server's collation -- which on a linguistic collation gives
    #   punctuation no primary weight -- so the two sides would be sorted by two different rules on
    #   exactly the identifiers the interest accrual produces, whose committed layout carries
    #   hyphens. Sorting both sides in this process removes the collation from the comparison.
    ordered = _require_fields(fields)
    canon = canonicalisation_of(layout) if layout is not None else None
    return FRAME_DELIMITER.join(
        part.encode("utf-8") for part in _identity_order(record, ordered, canon)
    )


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

    The admitted set is closed and is stated by type: ``None`` for an unwritten nullable column, a
    naive :class:`datetime.datetime` for a written one, or characters of the field's declared width
    that are one of the two admitted spellings or uniformly unwritten. Every other type is refused,
    including :class:`bytes`, :class:`decimal.Decimal`, :class:`datetime.date` and :class:`int`.

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
        admitted spellings nor uniformly unwritten, or characters of the wrong width; if it is a
        time-zone-aware :class:`datetime.datetime`, which the declared ``TIMESTAMP(6)`` without
        time zone cannot have produced; or if it is of any other type at all. The message names the
        field through :meth:`layouts.FieldSpec.describe` and, for a wrongly-typed value, names the
        type -- it quotes no part of the content in any case.
    """
    # WHY : Assumptions: the two non-character values that occur are admitted BY TYPE and
    #   everything else is refused. The load projection renders an unwritten stamp to `None` for a
    #   nullable `TIMESTAMP(6)` column, and the driver reads a written one back as a `datetime`;
    #   both have already been constrained by the column's own type, so re-deriving the calendar
    #   here would be this module maintaining a second one -- which is the duplication
    #   `copybook.timestamp` was extracted to remove.
    # WHY : Trade-offs: this admitted set is deliberately WIDER than the readers' -- both
    #   `readers.transaction._require_validated_timestamps` and its `readers.dalytran` counterpart
    #   refuse every non-character value outright -- and the difference is not a disagreement about
    #   the policy. A reader only ever holds the FILE side, decoded from bytes, where a value that
    #   is not characters means the descriptor no longer declares the regime a timestamp is written
    #   in. This pass holds both sides, and the database side legitimately produces `None` and
    #   `datetime`. Narrowing to the readers' rule would refuse every loaded row; widening theirs
    #   to this one would stop them detecting the descriptor fault they exist to catch.
    # WHY : ⚠️ Refactoring Rationale: this arm read `if not isinstance(value, str): return value`,
    #   which admitted EVERY non-character value while its own comment named only the two that
    #   occur. The gap between the comment and the code was the defect, and it was in the silent
    #   direction: this function's whole purpose is that a field EXCLUDED from the compared span is
    #   still looked at, so a value it admits is a value nothing downstream ever inspects again.
    #   `bytes` from a column that is not the type this layout says it is, a `Decimal` from a
    #   mis-projected numeric, a `date` from a column narrowed to a day, an `int` from an epoch
    #   rendering -- each was accepted as a well-formed stamp and then dropped from the digest, so
    #   a comparison over a corrupt or wrongly-typed column reported no difference at all. Naming
    #   the admitted types is what makes the exclusion an assertion rather than a blind spot.
    if value is None:
        return value
    if isinstance(value, _datetime.datetime):
        # WHY : Assumptions: awareness is the one property of a driver-supplied stamp worth
        #   checking here, and precision is not. Python's `datetime` cannot carry finer than
        #   microseconds, so a value that arrived at all already satisfies the `TIMESTAMP(6)`
        #   contract; but a value carrying an offset did NOT come from that contract -- the
        #   migration declares every stamp `TIMESTAMP(6)` without time zone, so an aware value
        #   means the column is `timestamptz` and the comparison is reading a converted instant
        #   rather than the written one. Refusing it names a schema defect that would otherwise
        #   surface as an unexplained difference in a later run, or as none at all.
        if value.tzinfo is not None:
            raise TimestampContractError(
                f"field {field.describe()} holds a timestamp carrying a time-zone offset, so the"
                " column it was read from is not the TIMESTAMP(6) without time zone this layout"
                " declares"
            )
        return value
    if not isinstance(value, str):
        # WHY : Assumptions: the refusal names the TYPE and not the value, which keeps it
        #   consistent with every other message in this module -- a stamp's content can be
        #   business data and a harness diagnostic is not a place to publish it. The type alone
        #   localises the fault, because the question it answers is which side produced a value
        #   the column's own declaration could not have.
        raise TimestampContractError(
            f"field {field.describe()} holds a value of type {type(value).__name__} where this"
            " pass admits only a naive datetime, an unwritten span or characters of the declared"
            " width, so the value cannot be excluded from the compared span as a run-clock stamp"
        )
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

# WHY : Assumptions: the two notes name neither a position nor a key, because one comparison can
#   pair either way and the line already carries whichever locator applies. Wording them around
#   "this position" was accurate only under positional pairing and read as a contradiction beside a
#   printed key.
_ABSENT_FROM_TARGET_NOTE: Final[str] = "no row was read back for this record"
_ABSENT_FROM_SOURCE_NOTE: Final[str] = "no source record corresponds to this row"


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
    identity : str
        The record's key, rendered, or an empty string when the comparison paired the two sides by
        position and so has no key to name.

        ⚠️ Assumptions: the key is the ONE part of a record this report does name, and the
        exemption is deliberate. Every other value is withheld because these records carry account
        numbers, national identifiers and dates of birth; a key is what an operator has to have in
        order to look the record up under the access that entitles them to see it, and a position
        in a stream is not that -- it names a different record as soon as the extract is
        regenerated.
        The key of a keyed dataset is also already present in the row-count and money-parity
        artifacts, so naming it here discloses nothing those do not.

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
    identity: str = ""

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
        # WHY : Assumptions: the key is appended rather than replacing the position, so a report
        #   read against a keyed comparison carries both readings -- the position an operator
        #   scrolls to and the key they query by. Replacing it would break the line-diffable
        #   guarantee for the positional comparison, which has no key to print.
        located = f"record={self.ordinal}"
        if self.identity:
            located = f"{located}  key={self.identity}"
        if self.kind is DifferenceKind.FIELD:
            described = self.field.describe() if self.field is not None else self.field_name
            return f"{_VERDICTS[self.kind]}  {located}  {described}"
        note = (
            _ABSENT_FROM_TARGET_NOTE
            if self.kind is DifferenceKind.ABSENT_FROM_TARGET
            else _ABSENT_FROM_SOURCE_NOTE
        )
        return f"{_VERDICTS[self.kind]}  {located}  {note}"


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
            True when the digests agree and no difference was located. An empty comparison of an
            empty extract against an empty table verifies; see the rationale below.

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
        # WHY : ⚠️ Refactoring Rationale: a comparison that walked NO position VERIFIES, where it
        #   used to fail on the reasoning that a run which judged nothing has proved nothing. That
        #   reasoning is right for a REPORT built from a query -- where an empty result set is
        #   indistinguishable from broken plumbing, which is why the row-count report keeps the rule
        #   -- and wrong here, because this pass is handed both sides explicitly and every
        #   accidental route to an empty one RAISES before a verdict is reached: an unreadable
        #   extract raises `OSError`, a truncated one `RecordLengthError`, an unknown layout
        #   `LayoutError`, and a load that never ran leaves a non-empty source paired against an
        #   empty table, which reports absent records rather than nothing.
        # WHY : Assumptions: what remained under the old rule was a GENUINE state reported as a
        #   failure an operator could do nothing about -- an empty extract loaded into an empty
        #   table, which is the normal state of the transaction master, since no `TRANSACT` dataset
        #   ships in either corpus.
        return self.matched and not self.differing_records and not self.absent_records

    @property
    def unpaired_records(self) -> int:
        """Count the positions this comparison could not pair between the two sides.

        Purpose
        -------
        Publish the pairing failure under its own name, so a caller can tell "the two sides are not
        in one key order" from "a field's value differs" without reading the difference list.

        Returns
        -------
        int
            The number of positions occupied on one side only.

        Raises
        ------
        None
            Reading a recorded count cannot fail.
        """
        # WHY : Assumptions: this is the same quantity `absent_records` carries and not a second
        #   measurement of it. The two names exist because the two readings of it are different
        #   questions -- an operator asks how many rows are missing, and a caller asserting the
        #   pairing asks whether the sides corresponded at all -- and one count answering both is
        #   what stops them disagreeing.
        return self.absent_records

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


def _identity_rendering(
    record: Mapping[str, object], identity: Sequence[str], canon: Mapping[str, Canon] | None
) -> str:
    """Render one record's key as the text a report names it by.

    Parameters
    ----------
    record : Mapping[str, object]
        The record or row whose key is rendered.
    identity : Sequence[str]
        The key field names, in declaration order.
    canon : Mapping[str, Canon] | None
        The per-field rules, so a key renders identically on both sides.

    Returns
    -------
    str
        The key's parts joined by a solidus, which no key value in this corpus contains.

    Raises
    ------
    KeyError
        If the record does not carry a key field, which means the caller named an identity the
        record does not have and every pairing would be arbitrary.
    """
    rules = canon or {}
    parts = [
        _rendered_value(rules.get(name), record[name]).decode("utf-8", "replace")
        for name in identity
    ]
    return "/".join(parts)


def _identity_order(
    record: Mapping[str, object], identity: Sequence[str], canon: Mapping[str, Canon] | None
) -> tuple[str, ...]:
    """Build the sort key that puts both sides of a comparison into one agreed order.

    Purpose
    -------
    Order the two sides identically, which is what lets a merge pair them by key. The ordering has
    to be a property of the KEY VALUES alone: the source side arrives in physical extract order and
    the target side in whatever order the read-back requested, and neither is the other's.

    Parameters
    ----------
    record : Mapping[str, object]
        The record or row being ordered.
    identity : Sequence[str]
        The key field names, in declaration order.
    canon : Mapping[str, Canon] | None
        The per-field rules.

    Returns
    -------
    tuple[str, ...]
        One sortable part per key field.

    Raises
    ------
    KeyError
        If the record does not carry a key field.
    """
    rules = canon or {}
    ordered: list[str] = []
    for name in identity:
        value = record[name]
        rule = rules.get(name)
        if rule is Canon.INTEGER and value is not None:
            # WHY : Assumptions: a numeric key is ordered by its value, through a zero-padded
            #   rendering, while it is DIGESTED unpadded. The two renderings serve different
            #   purposes and cannot be the same one: the digest must not depend on padding the
            #   target never stored, and the report must not list record 10 between records 1 and 2.
            #   Twenty digits covers the widest declared numeric key in the corpus with room to
            #   spare, and a key too wide for it still orders consistently on both sides.
            digits = _integer_rendering(value).decode("utf-8")
            ordered.append(digits.rjust(20, "0") if digits.isdigit() else digits)
            continue
        ordered.append(_rendered_value(rule, value).decode("utf-8", "replace"))
    return tuple(ordered)


def _paired_records(
    source_records: Iterable[Mapping[str, object]],
    target_records: Iterable[Mapping[str, object]],
    identity: Sequence[str],
    canon: Mapping[str, Canon] | None,
) -> Iterator[tuple[Mapping[str, object] | None, Mapping[str, object] | None, str]]:
    """Pair the two sides of one load, by key where a key is named and by position where none is.

    Purpose
    -------
    Hold the whole of the pairing decision in one place, so the comparison body reads the same way
    whichever pairing is in force and neither pairing can drift from the other's accounting.

    Parameters
    ----------
    source_records : Iterable[Mapping[str, object]]
        The source side.
    target_records : Iterable[Mapping[str, object]]
        The read-back side.
    identity : Sequence[str]
        The key field names, or empty to pair by position.
    canon : Mapping[str, Canon] | None
        The per-field rules, used to render and order the key identically on both sides.

    Yields
    ------
    tuple[Mapping[str, object] | None, Mapping[str, object] | None, str]
        The source record, the target row and the rendered key. Either side is ``None`` where the
        other side has a record it does not. The key is empty under positional pairing.

    Raises
    ------
    KeyError
        If a record does not carry a named key field.
    ValueError
        If a key value cannot satisfy the numeric rule its field declares.
    """
    if not identity:
        # WHY : Assumptions: positional pairing is RETAINED rather than replaced, and is the
        #   behaviour when no key is named. A caller comparing two sides it already knows to be in
        #   one order -- two readings of the same extract, say -- has no key to supply and needs no
        #   collection; `zip_longest` keeps that path at one record of memory per side.
        for source_record, target_record in itertools.zip_longest(source_records, target_records):
            yield source_record, target_record, ""
        return

    # WHY : Trade-offs: keyed pairing COLLECTS both sides, because neither arrives in key order and
    #   a merge cannot begin until each side can be ordered. The cost is bounded by the corpus: the
    #   largest seed extract is a few thousand fixed-width records, and the read-back side was
    #   already collected before the walk began because the connection closes around it. A
    #   database-side ordering plus a streaming merge was considered and rejected for the source
    #   half: it would put the ordering authority in the extract, which is exactly the assumption
    #   that made positional pairing wrong.
    ordered_source = sorted(
        source_records, key=lambda record: _identity_order(record, identity, canon)
    )
    ordered_target = sorted(
        target_records, key=lambda record: _identity_order(record, identity, canon)
    )
    source_index = 0
    target_index = 0
    while source_index < len(ordered_source) or target_index < len(ordered_target):
        source_record = ordered_source[source_index] if source_index < len(ordered_source) else None
        target_record = ordered_target[target_index] if target_index < len(ordered_target) else None
        source_key = (
            _identity_order(source_record, identity, canon) if source_record is not None else None
        )
        target_key = (
            _identity_order(target_record, identity, canon) if target_record is not None else None
        )
        # WHY : Assumptions: only the side holding the LOWER key advances on an unpaired record,
        #   which is the property that stops one absent row cascading. Advancing both -- which is
        #   what positional pairing does by construction -- would pair every subsequent source
        #   record against the following row and report the whole tail as differing.
        if target_key is None or (source_key is not None and source_key < target_key):
            source_index += 1
            yield source_record, None, _identity_rendering(source_record, identity, canon)
        elif source_key is None or target_key < source_key:
            target_index += 1
            yield None, target_record, _identity_rendering(target_record, identity, canon)
        else:
            source_index += 1
            target_index += 1
            yield source_record, target_record, _identity_rendering(source_record, identity, canon)


def compare_record_digests(
    record_name: str,
    qualified_table: str,
    source_records: Iterable[Mapping[str, ChecksumValue]],
    target_records: Iterable[Mapping[str, ChecksumValue]],
    fields: Sequence[str],
    *,
    layout: layouts.RecordSpec | None = None,
    canon: Mapping[str, Canon] | None = None,
    identity: Sequence[str] = (),
    key_fields: Sequence[str] = (),
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
    source_records : Iterable[Mapping[str, ChecksumValue]]
        The source side, as decoded records. Without an ``identity`` an iterator is consumed once
        and never held, so a large dataset costs one record of memory; with one, both sides are
        collected so they can be brought into key order.
    target_records : Iterable[Mapping[str, ChecksumValue]]
        The read-back side, keyed by the same field names.
    fields : Sequence[str]
        The field names to compare, in the order both sides will use. Pass this through
        :func:`deterministic_field_names` first so no run-clock stamp is inside it.
    layout : layouts.RecordSpec | None
        The record descriptor, when the caller has it. Supplying it turns on the validate-before
        -blank check over every run-clock stamp the records carry, and it is what lets a reported
        difference name a field's byte interval and storage regime rather than only its name.
    canon : Mapping[str, Canon] | None
        The per-field canonicalisation rules, as :func:`canonicalisation_of` derives them. Supply
        them whenever the two sides can carry one value in two types -- which is always true of a
        database read-back, where an identifier arrives as an integer and a date as a date.
    identity : Sequence[str]
        The key field names to pair the two sides by, in declaration order. Empty pairs them BY
        POSITION, which is only correct when both sides are known to be in one agreed order.
    reported_differences : int
        How many located differences the result carries. Counts stay exact whatever this is.

    Returns
    -------
    ChecksumComparison
        The outcome, whether or not the two sides agree. A disagreement is REPORTED rather than
        raised, so one run reports every difference it can carry instead of stopping at the first.

        ⚠️ Refactoring Rationale: pairing by key was added because pairing by position made two
        correct loads look corrupt and one real defect unreadable. The source side is in physical
        extract order and the read-back was ordered by its columns, so any dataset whose extract is
        not already in that order paired every record against the wrong row -- reported as every
        record differing. And a single row missing from the target shifted every subsequent pair by
        one, so one absent row was reported as an absent row plus a tail of differences that did
        not exist. Pairing by key reports the missing row, once, and nothing else.

    Raises
    ------
    ValueError
        If no field is named, if ``reported_differences`` is below one, or if a value cannot
        satisfy the numeric rule its field declares.
    TimestampContractError
        If a layout was supplied and a run-clock stamp on either side is neither an admitted
        timestamp nor uniformly unwritten.
    KeyError
        If either side's record does not carry a named field.
    ChecksumValueError
        If a value on either side is outside the admitted domain. Also catchable as
        :exc:`TypeError`, which is the contract this function published before the pass classified
        its own refusals.
    """
    # WHY : ⚠️ Assumptions: `key_fields` and `identity` name ONE parameter under two spellings
    #   and the two are folded here rather than both being carried through the walk. They arrived
    #   from two independent corrections of the same defect -- positional pairing over two
    #   differently ordered sides -- and keeping both as separate knobs would have let a caller
    #   supply two different identities for one comparison, with nothing to say which won.
    #   `key_fields` is the spelling callers are written against; `identity` is retained because
    #   the report's own per-difference field is named for it.
    # WHY : ⚠️ Assumptions: a caller that supplies a LAYOUT and no explicit rules gets the rules
    #   derived from that layout, rather than the pass falling back to rendering each value by its
    #   own observed class. Rendering by observed class is right when the two sides are two readings
    #   of one side and wrong here: the source carries a zero-padded display identifier where the
    #   driver returns the integer of the column it loaded into, so the two would render as text and
    #   as an integer, fail to pair, and a correct load would report every record absent from both
    #   sides. The explicit `canon` still wins where a caller has narrowed the rules against the
    #   columns actually read back.
    identity = tuple(identity) or tuple(key_fields)
    if canon is None and layout is not None:
        canon = canonicalisation_of(layout)
    ordered = _require_fields(fields)
    # WHY : Alternatives Considered: a reporting limit below one is REFUSED rather than clamped to
    #   zero. Clamping would let a caller obtain a comparison that counts differences and can name
    #   none of them, which is a weakened pass reached by an argument rather than by an edit -- and
    #   the three passes are required to be unweakenable. Refusing costs one check.
    # WHY : ⚠️ Assumptions: a key field the comparison does not DIGEST is refused rather than
    #   accepted, because the pass would then pair on a value it never compares -- two records
    #   agreeing on the key and differing in it would be reported as one verified record. The caller
    #   believes the key is checked; only naming it in `fields` makes that true.
    outside = tuple(name for name in identity if name not in ordered)
    if outside:
        raise ValueError(
            "a pairing key must be among the compared fields, or the pairing is asserted on a value"
            f" the comparison never digests; {', '.join(outside)} is not among the compared fields"
        )
    if reported_differences < 1:
        raise ValueError(
            "a comparison that can report no difference cannot localise one, which is this pass's"
            f" whole purpose; {reported_differences} was requested and at least 1 is required"
        )
    source_hasher = hashlib.new(DIGEST_NAME)
    target_hasher = hashlib.new(DIGEST_NAME)
    # WHY : Assumptions: the descriptors and the canonicalisation rules are both derived from the
    #   ONE layout above -- the rules render each value, these descriptors name the byte interval a
    #   reported difference sits in. Taking both from one source is what stops a reported difference
    #   describing a field under a different regime from the one its rendering used, which is the
    #   single inconsistency a localising report must not have.
    declared = {field.name: field for field in layout.fields} if layout is not None else {}
    differences: list[ChecksumDifference] = []
    withheld = 0
    differing_records = 0
    absent_records = 0
    source_count = 0
    target_count = 0
    ordinal = 0
    # WHY : ⚠️ Refactoring Rationale: the walk pairs by KEY when the caller names one, and it used
    #   to pair by position unconditionally. The comment this replaces argued that positional
    #   pairing was not weaker than keyed pairing because the whole-dataset digest is
    #   order-sensitive anyway -- and the argument was circular. The digest was order-sensitive
    #   because the walk chose to make it so, and the two orders it compared were not the same
    #   order: extract order on one side, column order on the other. Both digests are now computed
    #   in ONE agreed order, which is what makes the whole-dataset verdict and the per-record
    #   report two readings of one comparison rather than two different comparisons.
    # WHY : Assumptions: an unpaired record is still REPORTED rather than skipped, under either
    #   pairing, so a load that stopped early or ran twice is visible. What changed is the blast
    #   radius: under keyed pairing an absent row is reported once, at its own key, and the records
    #   after it pair with the rows they belong to.
    for source_record, target_record, record_key in _paired_records(
        source_records, target_records, tuple(identity), canon
    ):
        ordinal += 1
        source_values: tuple[bytes, ...] = ()
        target_values: tuple[bytes, ...] = ()
        if source_record is not None:
            source_count += 1
            if layout is not None:
                _validate_stamps(layout, source_record)
            source_values = _canonical_values(source_record, ordered, canon)
            source_hasher.update(_record_frame(source_values))
        if target_record is not None:
            target_count += 1
            if layout is not None:
                _validate_stamps(layout, target_record)
            target_values = _canonical_values(target_record, ordered, canon)
            target_hasher.update(_record_frame(target_values))
        if source_record is None or target_record is None:
            absent_records += 1
            kind = (
                DifferenceKind.ABSENT_FROM_SOURCE
                if source_record is None
                else DifferenceKind.ABSENT_FROM_TARGET
            )
            if len(differences) < reported_differences:
                differences.append(
                    ChecksumDifference(ordinal=ordinal, kind=kind, identity=record_key)
                )
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
                        identity=record_key,
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


# =============================================================================
# Sealed-column envelope audit
# -----------------------------------------------------------------------------
# WHY : Refactoring Rationale: the three enciphered columns are excluded from the record digest
#   by design -- an envelope carries a fresh initialisation vector per seal, so two seals of one
#   plaintext are different bytes and no digest comparison over them can mean anything. That
#   exclusion left a hole a row count cannot see either: a load that wrote a NULL, an empty
#   envelope or a plaintext into one of those columns produces the right number of rows with the
#   right digests over every other field. The audit below closes it by checking the SHAPE of what
#   was written rather than its value, which is the strongest statement available without the key.
# WHY : Alternatives Considered: deciphering each envelope and comparing the plaintext against the
#   source field. Rejected because it would put the customer-identifier and card-verification keys
#   in the hands of the verification role, which exists precisely so the gate holds no privilege
#   it does not need; the audit runs on the SELECT-only login and reads ciphertext it cannot open.
# =============================================================================


#: Markers the two sealing ciphers write at the head of every envelope they produce.
#:
#: Assumptions: the markers are spelled here rather than imported from
#: `carddemo_migration.loaders.protected_columns`, and the duplication is deliberate. This module
#: verifies what was STORED; importing the writer's own constants would make the audit agree with
#: the writer by construction, so a writer that changed its framing would take the check with it
#: and the audit would certify whatever it happened to produce. A test holds the two equal, which
#: is a mechanical comparison rather than a shared definition.
SEALED_ENVELOPE_MARKERS: Final[frozenset[bytes]] = frozenset({b"CDCI", b"CDCV"})


#: Smallest envelope either cipher can produce: four marker bytes, one version byte, a two-byte
#: wrapped-key length, at least one wrapped-key byte, a twelve-byte initialisation vector and a
#: sixteen-byte authentication tag.
#:
#: Assumptions: the floor is a LOWER BOUND and not an exact length, because the wrapped data key's
#: size is decided by the key-management service and is not this package's to predict. A length
#: check against an exact value would fail on a correct load the first time that service changed
#: its wrapping format; a floor catches the failure that actually matters -- a truncated or
#: placeholder value stored where an envelope should be.
SEALED_ENVELOPE_MIN_BYTES: Final[int] = 4 + 1 + 2 + 1 + 12 + 16


@dataclass(frozen=True)
class SealedColumnAudit:
    """What one dataset's protected columns were certified to hold, without recovering plaintext.

    Purpose
    -------
    Give the sealed columns a verdict of their own. They are excluded from the digest comparison
    for a sound reason -- each envelope draws a fresh initialisation vector, so the same value
    enciphered twice differs and a digest reports a difference on every correct run -- but excluding
    them left three columns across two of the eleven loadable records certified by nothing at all.
    A load that silently dropped every primary identifier would have passed.

    Parameters
    ----------
    record_name : str
        The record layout audited, rendered as the heading. Never a value.
    qualified_table : str
        The table audited.
    columns : tuple[str, ...]
        The sealed columns audited, in the target's own order.
    expected : Mapping[str, int]
        Per column, how many source records carried a value that must have been sealed.
    stored : Mapping[str, int]
        Per column, how many rows hold a non-null envelope.
    malformed : Mapping[str, int]
        Per column, how many stored envelopes do not begin with a known marker or are shorter than
        :data:`SEALED_ENVELOPE_MIN_BYTES`.

    Returns
    -------
    None
        Construction validates nothing; :func:`audit_sealed_columns` is what measures.

    Raises
    ------
    None
        Holding counts cannot fail.
    """

    record_name: str
    qualified_table: str
    columns: tuple[str, ...]
    expected: Mapping[str, int]
    stored: Mapping[str, int]
    malformed: Mapping[str, int]

    @property
    def verified(self) -> bool:
        """Report whether every sealed column stored exactly what was sent, well-formed.

        Parameters
        ----------
        None
            Reads the three count mappings.

        Returns
        -------
        bool
            True when, for every audited column, the stored count equals the expected count and no
            stored envelope is malformed. A record with NO sealed column is verified vacuously,
            because there is nothing about it that could be wrong.

        Raises
        ------
        None
        """
        # WHY : Assumptions: a record with no sealed column verifies TRUE, unlike the empty
        #   aggregate elsewhere in this package which verifies False. The two emptinesses are not
        #   the same claim: an empty aggregate means a pass judged nothing it was supposed to judge,
        #   while nine of the eleven loadable records genuinely declare no sealing projection, and
        #   reporting those as unverified would make the combined gate fail on a correct load.
        return all(
            self.stored.get(column, 0) == self.expected.get(column, 0)
            and self.malformed.get(column, 0) == 0
            for column in self.columns
        )

    def render(self) -> str:
        """Render one line per sealed column, with the verdict.

        Parameters
        ----------
        None
            Reads the three count mappings.

        Returns
        -------
        str
            The heading and one line per column, carrying COUNTS only -- never a stored byte, never
            a marker, never a length of an individual value. Deterministic, so two audits over
            unchanged data diff to nothing.

        Raises
        ------
        None
        """
        # WHY : Assumptions: the rendering carries counts and never a value, a prefix or a per-row
        #   length. The whole point of auditing a sealed column without deciphering it is that the
        #   audit discloses nothing, and an individual envelope's length is a fact about one
        #   customer's identifier -- a government-issued identifier and a social security number
        #   differ in width, so a per-row length is a weak disclosure rather than none.
        lines = [
            f"sealed columns {self.record_name} -> {self.qualified_table}:"
            f" {len(self.columns)} column(s)"
        ]
        for column in self.columns:
            expected = self.expected.get(column, 0)
            stored = self.stored.get(column, 0)
            malformed = self.malformed.get(column, 0)
            state = "SEALED" if stored == expected and malformed == 0 else "VIOLATED"
            lines.append(
                f"  {column}: expected {expected}, stored {stored}, malformed {malformed} [{state}]"
            )
        return "\n".join(lines)


class SealableValueTally:
    """Count, as records stream past, how many carried a value that had to be sealed.

    Purpose
    -------
    Let the sealed-column audit take its expected count from the SAME traversal of the extract that
    the digest comparison already makes, so certifying the sealed columns costs no second read of
    the source. A second read is not merely slower here: an extract materialised from the object
    store is written once into an invocation-scoped work directory and re-fetching it is refused, so
    a two-pass audit would fail outright on exactly the deployment the batch task uses.

    Parameters
    ----------
    fields : Mapping[str, str]
        The sealed fields, mapping copybook field name to stored column name. Tallies are keyed by
        COLUMN, because that is the side :func:`audit_sealed_columns` compares them against.

    Returns
    -------
    None
        Construction measures nothing; :meth:`tap` is what counts.

    Raises
    ------
    None
        Holding a field mapping cannot fail.
    """

    def __init__(self, fields: Mapping[str, str]) -> None:
        """Prepare a tally over one target's sealed fields.

        Parameters
        ----------
        fields : Mapping[str, str]
            Copybook field name to stored column name, in the target's own order.

        Returns
        -------
        None

        Raises
        ------
        None
        """
        # WHY : Assumptions: every column starts at zero rather than being absent until first seen.
        #   A column whose source field carried no value in any record must report zero expected,
        #   not a missing key -- and `audit_sealed_columns` refuses a missing measurement, so an
        #   absent key would turn "this extract sealed nothing" into a refusal to run.
        self._fields = dict(fields)
        self._counts: dict[str, int] = {column: 0 for column in self._fields.values()}
        self._tapped = False
        self._exhausted = False

    def tap(
        self, records: Iterable[Mapping[str, ChecksumValue]]
    ) -> Iterator[Mapping[str, ChecksumValue]]:
        """Pass records through unchanged, counting sealable values as they go.

        Parameters
        ----------
        records : Iterable[Mapping[str, ChecksumValue]]
            The decoded source records, keyed by copybook field name.

        Yields
        ------
        Mapping[str, ChecksumValue]
            Each record exactly as received, unmodified and in the order received.

        Raises
        ------
        ChecksumVerificationError
            If the tally has already been tapped, because one tally measures one traversal and a
            second stream would add its values to the first stream's counts.
        """
        # WHY : Assumptions: a SECOND tap is refused rather than accumulating into the same counts.
        #   A tally measures ONE traversal of ONE extract; two streams sharing it would double every
        #   count, and the audit would then report twice as many values expected as were stored and
        #   name a correct load as a total loss.
        if self._tapped:
            raise ChecksumVerificationError(
                "this sealable-value tally has already been tapped; one tally measures one"
                " traversal, and a second stream would add its counts to the first stream's"
            )
        self._tapped = True
        # WHY : Assumptions: the tap yields the record UNCHANGED and holds no reference to it, so
        #   the consumer's own streaming is preserved and peak memory stays a function of ONE
        #   record.
        #   Collecting the records here to count them would reintroduce exactly the materialisation
        #   this whole verification path was changed to avoid.
        for record in records:
            for field_name, column in self._fields.items():
                if _has_sealable_value(record.get(field_name)):
                    self._counts[column] += 1
            yield record
        self._exhausted = True

    @property
    def counts(self) -> Mapping[str, int]:
        """Report the per-column expected counts, refusing an incomplete measurement.

        Parameters
        ----------
        None
            Reads the accumulated counts.

        Returns
        -------
        Mapping[str, int]
            A read-only snapshot, keyed by stored column name.

        Raises
        ------
        ChecksumVerificationError
            If the stream returned by :meth:`tap` was never created, or was abandoned before it
            ended, because the counts would then be a prefix of the extract rather than the whole
            of it.
        """
        # WHY : Assumptions: an incomplete tally RAISES rather than answering a prefix. The
        #   completeness of these counts depends on a consumer this class cannot see draining the
        #   tap -- today `compare_record_digests`, which walks both sides with `zip_longest` to
        #   exhaustion and only limits what it REPORTS. That is a true but implicit coupling, and a
        #   consumer that later stopped early would silently under-count every sealed column and
        #   report a correct load as a violation. Refusing converts that class of change from a
        #   wrong verdict into a named failure at the point of the mistake.
        if not self._exhausted:
            raise ChecksumVerificationError(
                "the sealable-value tally was read before its stream ended; the expected counts"
                " would cover only the part of the extract that was consumed"
            )
        # WHY : Assumptions: a read-only SNAPSHOT is returned rather than a live view of the
        #   dictionary, so the numbers a verdict was computed from cannot change afterwards.
        return MappingProxyType(dict(self._counts))


def audit_sealed_columns(
    record_name: str,
    qualified_table: str,
    columns: Mapping[str, str],
    expected: Mapping[str, int],
    stored_envelopes: Mapping[str, Iterable[object]],
) -> SealedColumnAudit:
    """Certify a dataset's protected columns by presence and framing, never by plaintext.

    Purpose
    -------
    Close the one verification gap the digest comparison cannot close. A sealed column's stored
    bytes are not a function of its source value -- a fresh initialisation vector per value makes
    them differ on every write -- so it is excluded from the digest and was therefore certified by
    nothing. This certifies the two properties that ARE functions of the source and are checkable
    without any decryption: that every source value which had to be sealed produced exactly one
    stored envelope, and that every stored envelope carries a known marker and a plausible length.

    Parameters
    ----------
    record_name : str
        The record layout being audited, used only as a heading.
    qualified_table : str
        The table being audited, used only as a heading.
    columns : Mapping[str, str]
        The sealed fields, mapping copybook field name to stored column name, in target order.
    expected : Mapping[str, int]
        Per column, how many source records carried a value that had to be sealed. Produced by
        :class:`SealableValueTally` from the same traversal the digest comparison makes.
    stored_envelopes : Mapping[str, Iterable[object]]
        Per column, the values the target holds for it -- one entry per row, ``None`` for a null.

    Returns
    -------
    SealedColumnAudit
        The per-column counts and the single binary verdict over them.

    Raises
    ------
    ChecksumVerificationError
        If ``expected`` or ``stored_envelopes`` omits an audited column, because an absent
        measurement would be counted as zero and would report a correct load as a total loss.
    """
    # WHY : Assumptions: an absent measurement RAISES rather than defaulting to zero. Zero stored
    #   envelopes against fifty expected is the loudest possible failure, so defaulting would turn a
    #   caller's omission into a spectacular false negative that an operator would spend the cutover
    #   window investigating. Refusing names the omission instead. BOTH sides are checked, because
    #   an omitted expectation fails the other way -- fifty stored against nothing expected -- and
    #   that reading is just as wrong and just as expensive to investigate.
    missing = sorted(
        {column for column in columns.values() if column not in stored_envelopes}
        | {column for column in columns.values() if column not in expected}
    )
    if missing:
        raise ChecksumVerificationError(
            f"no complete measurement was supplied for sealed column(s) {missing} of"
            f" {qualified_table}; an absent measurement would be counted as nothing"
        )

    stored: dict[str, int] = {}
    malformed: dict[str, int] = {}
    for column in columns.values():
        present = 0
        bad = 0
        for value in stored_envelopes[column]:
            if value is None:
                continue
            present += 1
            if not _is_well_formed_envelope(value):
                bad += 1
        stored[column] = present
        malformed[column] = bad
    return SealedColumnAudit(
        record_name=record_name,
        qualified_table=qualified_table,
        columns=tuple(columns.values()),
        # WHY : Assumptions: the expected counts are SNAPSHOT into a new mapping rather than held by
        #   reference. `SealableValueTally.counts` already answers a snapshot, but this function
        #   accepts ANY mapping -- a caller is free to hand it a dictionary it still holds -- and
        #   the numbers a verdict was computed from must not change after the verdict.
        expected=MappingProxyType(dict(expected)),
        stored=MappingProxyType(stored),
        malformed=MappingProxyType(malformed),
    )


def _has_sealable_value(value: object) -> bool:
    """Report whether a source field carried a value that had to be sealed.

    Parameters
    ----------
    value : object
        The decoded source value, of whatever type the owning reader produced.

    Returns
    -------
    bool
        True when the value is present and not blank.

    Raises
    ------
    None
    """
    # WHY : Assumptions: a BLANK field counts as carrying nothing, because that is exactly what the
    #   sealing ciphers do -- both refuse an empty value and the loader stores NULL for it. Counting
    #   a blank as expected would make the audit demand an envelope the writer is forbidden to
    #   produce, and `app/data/EBCDIC/AWS.M2.CARDDEMO.CUSTDATA.PS` carries blank government-issued
    #   identifiers, so the audit would fail on the shipped extract.
    # WHY : Assumptions: the value is stringified before it is stripped rather than being required
    #   to be a string. A reader may hand back a `ProtectedValue` wrapper for a sealed field -- that
    #   is precisely what `readers/card.py` does for the verification value -- and asking such a
    #   value whether it is blank must not depend on this module knowing the wrapper's type.
    if value is None:
        return False
    return bool(str(value).strip())


def _is_well_formed_envelope(value: object) -> bool:
    """Report whether one stored value has the shape a sealing cipher produces.

    Parameters
    ----------
    value : object
        The value the driver returned for a sealed column: bytes, a buffer, or something else.

    Returns
    -------
    bool
        True when the value is a byte sequence beginning with a known marker and at least
        :data:`SEALED_ENVELOPE_MIN_BYTES` long.

    Raises
    ------
    None
    """
    # WHY : Assumptions: a non-bytes value is malformed rather than an error. A sealed column is
    #   declared BYTEA, so text or a number arriving from it means the column's type was changed or
    #   the wrong column was read -- both are findings about the load, which is what this audit
    #   reports, rather than faults in the audit itself.
    if isinstance(value, memoryview | bytearray):
        value = bytes(value)
    if not isinstance(value, bytes):
        return False
    if len(value) < SEALED_ENVELOPE_MIN_BYTES:
        return False
    return value[:4] in SEALED_ENVELOPE_MARKERS
