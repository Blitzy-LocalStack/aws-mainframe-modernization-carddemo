"""Build a record reader from a layout descriptor, so one descriptor yields one reader.

Purpose
-------
Hold the whole of the reader contract once, parameterised by a
:class:`carddemo_migration.copybook.layouts.RecordSpec`, so that a reader module for a
record is the layout plus a name and nothing else. The migration plan names this pattern
explicitly for this package -- "Factory: codec and reader factories in ``common-lib`` and
``data-migration``; replaces repeated inline record-layout knowledge; one layout
descriptor, many readers" -- and this module is that factory.

WHY (Refactoring Rationale)
---------------------------
The three readers that landed before this module -- account, card and tcatbal -- are
hand-written and between 796 and 1004 lines each, and the overwhelming majority of each is
identical to the others: the same pad-detection rule, the same offset-to-field search for
diagnostics, the same single-byte width guard, the same six streaming entry points and the
same two masked renderings, differing only in which layout constant they name. Continuing
that way for the remaining nine records would have added roughly seven thousand lines in
which every real decision appears nine times, and a correction to one of them -- the sign
overpunch handling, say, or the pad rule -- would have to be found and repeated in nine
places or else silently disagree between records. The pad-detection comment in the account
reader anticipates this directly: it says the rule is tested by NAME rather than by position
so that "a sibling reader can reuse the rule unchanged". This module is where it is reused.

WHY (Alternatives Considered)
-----------------------------
Copying the account reader nine times, which is what the existing three readers' shape
invites. Rejected for the duplication above. Generating the nine modules from a template at
build time was also rejected: a generated module cannot carry the record-specific reasoning
the project's documentation rule requires, and it would put a code generator between a
reader and the reviewer reading it.

WHY (Trade-offs)
----------------
Two implementations of one contract now coexist in this package: the three hand-written
readers and the nine built here. The public surface is identical either way -- the same
names, the same signatures, the same exceptions -- so a caller cannot tell them apart, and
that is what makes the coexistence tolerable. What is given up is single-implementation
uniformity inside the package; retrofitting the three onto this factory is a mechanical
follow-up deliberately not taken here, because those three are covered by passing tests and
rewriting them would risk a regression for a tidiness gain rather than a behavioural one.
"""

from __future__ import annotations

import pathlib
from collections.abc import Iterable, Iterator
from decimal import Decimal
from typing import Final

from carddemo_migration.copybook.ebcdic_codec import decode_record, iter_ebcdic_records
from carddemo_migration.copybook.layouts import (
    FieldSpec,
    Kind,
    LayoutError,
    RecordLengthError,
    RecordSpec,
    iter_ascii_text_records,
    mask_field,
    mask_record,
)
from carddemo_migration.copybook.zoned import decode_zoned_field

__all__ = [
    "TEXT_DECODABLE_KINDS",
    "Decoded",
    "RecordReader",
]

# WHY : Assumptions: the decoded value type is the union the record-oriented EBCDIC decoder
#   can return, which is wider than any single record needs. A record declaring only
#   character and display regimes never yields `bytes`, and the hand-written readers narrow
#   their own alias accordingly; this factory serves records of both kinds, including the
#   export image, whose opaque and computational areas do yield `bytes`. Narrowing here
#   would make the factory unusable for exactly the one record that most needs a reader.
Decoded = dict[str, str | Decimal | bytes]

# WHY : Assumptions: only these three regimes can be read out of a CHARACTER record.
#   Computational, binary and opaque areas are byte patterns whose meaning does not survive
#   a text decode -- a packed nibble pair and an unsigned binary halfword both contain byte
#   values that are not characters at all -- so a record declaring any of them has no ASCII
#   seed form and the ASCII entry points must refuse it rather than return plausible
#   nonsense. This is the same judgement the hand-written readers make per field; hoisting it
#   to a set lets the factory answer it once per RECORD, which is also the level at which the
#   answer is actually useful to a caller choosing an entry point.
TEXT_DECODABLE_KINDS: Final[frozenset[Kind]] = frozenset({Kind.TEXT, Kind.UINT, Kind.ZONED})

# WHY : Assumptions: the trailing pad is identified by NAME and not by position, measured
#   across every registered layout: each declares at most one pad, named either `FILLER` or
#   with that word as its final hyphenated component. Testing the name keeps this module free
#   of any byte position of its own.
_PAD_FIELD_NAME: Final[str] = "FILLER"
_PAD_NAME_SUFFIX: Final[str] = f"-{_PAD_FIELD_NAME}"


def _is_padding_field(field: FieldSpec) -> bool:
    """Report whether a field is a record's trailing pad rather than data.

    Purpose
    -------
    Decide, from the declared name alone, whether a field exists only to fill the record out
    to its fixed length, so that the judgement is made in one place for every record.

    Parameters
    ----------
    field : FieldSpec
        The declared field to judge.

    Returns
    -------
    bool
        True when the field is padding and must not appear in a decoded record.

    Raises
    ------
    None
    """
    return field.name == _PAD_FIELD_NAME or field.name.endswith(_PAD_NAME_SUFFIX)


class RecordReader:
    """Every reader entry point for one record layout.

    Purpose
    -------
    Expose the reader contract -- decode, iterate and read for both the ASCII seed form and
    the EBCDIC dataset form, plus the two masked renderings -- bound to a single layout, so a
    reader module is a layout and a set of names.

    Parameters
    ----------
    layout : RecordSpec
        The record descriptor supplying the name, the declared length and every field's
        offset, width, regime, scale and sign contract. Nothing about a field's position is
        taken from anywhere else.

    Raises
    ------
    None
    """

    def __init__(self, layout: RecordSpec) -> None:
        """Bind the reader to one layout and derive its loaded and dropped field sets.

        Parameters
        ----------
        layout : RecordSpec
            The record descriptor this reader serves.

        Raises
        ------
        None
        """
        self.layout: Final[RecordSpec] = layout
        self.loaded_fields: Final[tuple[FieldSpec, ...]] = tuple(
            field for field in layout.fields if not _is_padding_field(field)
        )
        self.dropped_field_names: Final[frozenset[str]] = frozenset(
            field.name for field in layout.fields if _is_padding_field(field)
        )
        # WHY : Assumptions: whether the record has an ASCII seed form is a property of the
        #   record and is answered once here rather than discovered per field at decode time.
        #   Answering it eagerly is what lets the ASCII entry points refuse an
        #   undecodable record with a message naming the regime, instead of failing partway
        #   through a record having already returned some fields.
        self.text_decodable: Final[bool] = all(
            field.kind in TEXT_DECODABLE_KINDS for field in layout.fields
        )

    def _field_containing(self, offset: int) -> FieldSpec | None:
        """Find the declared field whose byte span covers a record offset.

        Purpose
        -------
        Let a failure report WHICH field a bad byte falls in, using the layout's own spans, so
        a diagnostic can be specific about location without quoting any record content.

        Parameters
        ----------
        offset : int
            A zero-based offset into the record.

        Returns
        -------
        FieldSpec | None
            The covering field, or None when the offset lies beyond every declared field.

        Raises
        ------
        None
        """
        for field in self.layout.fields:
            if field.start <= offset < field.end:
                return field
        return None

    def _require_single_byte_record(self, record: str, number: int) -> str:
        """Require a record whose character count provably equals its byte count.

        Purpose
        -------
        Close the one width failure the shared text-record iterator cannot see. That iterator
        enforces the declared length in CHARACTERS, which is correct for text; a multi-byte
        character satisfies that check while occupying more bytes, which moves every field
        offset after it.

        Parameters
        ----------
        record : str
            One whole record at its declared character width.
        number : int
            The record's one-based position, for the diagnostic only.

        Returns
        -------
        str
            The same record, once every character is proven single-byte.

        Raises
        ------
        RecordLengthError
            If any character lies outside the single-byte range.
        """
        if record.isascii():
            return record
        offset = next(index for index, char in enumerate(record) if not char.isascii())
        field = self._field_containing(offset)
        location = "beyond the declared record" if field is None else f"in field {field.describe()}"
        raise RecordLengthError(
            f"record {number} of {self.layout.name} holds a character outside the single-byte"
            f" range at zero-based offset {offset}, {location}; a multi-byte character satisfies"
            f" the {self.layout.reclen}-character width check while occupying more bytes, which"
            " moves every field offset after it, so the record is rejected rather than decoded"
        )

    def _decode_text_field_value(self, record: str, field: FieldSpec) -> str | Decimal:
        """Decode one field out of a character record through the codec that owns its regime.

        Purpose
        -------
        Route each of the three character-decodable regimes to its own codec and refuse
        anything else, so no regime is decoded by a codec that does not own it.

        Parameters
        ----------
        record : str
            One whole record at its declared character width.
        field : FieldSpec
            The descriptor supplying the offset, width, regime, scale and sign contract.

        Returns
        -------
        str | Decimal
            The field's characters at full declared width for a character field, and the same
            for an unsigned display field once its digits are proven; an exact
            :class:`decimal.Decimal` at the field's declared scale for a signed display field.

        Raises
        ------
        LayoutError
            If the field declares a regime that cannot occur in a character record.
        RecordLengthError
            If the record ends before the field's declared span. Raised by the display codec.
        ValueError
            If a display span violates its contract. Raised by the display codec.
        """
        if field.kind is Kind.TEXT:
            return record[field.start : field.end]
        if field.kind is Kind.UINT:
            # WHY : Assumptions: the codec is called for its VALIDATION and its result is
            #   discarded, because an unsigned display field's published value is its
            #   characters at full declared width, leading zeros included. Returning the
            #   decoded number instead would drop those zeros, and an identifier that loses
            #   them no longer matches the fixed-character column it loads into.
            decode_zoned_field(record, field)
            return record[field.start : field.end]
        if field.kind is Kind.ZONED:
            return decode_zoned_field(record, field)
        raise LayoutError(
            f"field {field.describe()} of record {self.layout.name} declares a storage regime"
            " that cannot be decoded from a character record; only character and display"
            " regimes can, and a computational or mixed-regime area must be read from the byte"
            " image through the EBCDIC record decoder"
        )

    def _project(self, values: Decoded) -> Decoded:
        """Drop every padding field from a decoded record.

        Parameters
        ----------
        values : Decoded
            Every declared field of one record, keyed by field name, including any pad.

        Returns
        -------
        Decoded
            The same mapping without any padding field, in declaration order.

        Raises
        ------
        None
        """
        return {
            name: value for name, value in values.items() if name not in self.dropped_field_names
        }

    def _require_text_decodable(self) -> None:
        """Refuse an ASCII entry point for a record that has no character form.

        Purpose
        -------
        Fail with a message naming the offending regime BEFORE any field is decoded, rather
        than partway through a record.

        Raises
        ------
        LayoutError
            If the record declares any regime outside the character-decodable set.
        """
        if self.text_decodable:
            return
        offending = sorted(
            {
                field.kind.name
                for field in self.layout.fields
                if field.kind not in TEXT_DECODABLE_KINDS
            }
        )
        raise LayoutError(
            f"record {self.layout.name} declares the {', '.join(offending)} storage regime(s),"
            " which have no character form, so it has no ASCII seed representation and must be"
            " read from its byte image through the EBCDIC entry points; decoding it as text"
            " would return values that look plausible and are wrong"
        )

    def decode_ascii(self, record: str, *, number: int = 1) -> Decoded:
        """Decode one record from the ASCII seed form.

        Parameters
        ----------
        record : str
            One whole record at its declared character width.
        number : int, optional
            The record's one-based position, for diagnostics only.

        Returns
        -------
        Decoded
            Every data field keyed by its copybook name, with the trailing pad dropped.

        Raises
        ------
        LayoutError
            If the record has no character form at all.
        RecordLengthError
            If the record's width is not the declared width, or a character is multi-byte.
        ValueError
            If a display span violates its contract.
        """
        self._require_text_decodable()
        if len(record) != self.layout.reclen:
            raise RecordLengthError(
                f"record {number} of {self.layout.name} is {len(record)} characters against a"
                f" declared width of {self.layout.reclen}; a record of the wrong width means the"
                " field offsets have moved, so it is rejected rather than padded or truncated"
            )
        checked = self._require_single_byte_record(record, number)
        return {
            field.name: self._decode_text_field_value(checked, field)
            for field in self.loaded_fields
        }

    def iter_ascii(self, source: str | Iterable[object]) -> Iterator[Decoded]:
        """Decode the ASCII seed form one record at a time.

        Parameters
        ----------
        source : str | Iterable[object]
            Character data the caller holds, or an iterable of lines already being read.

        Yields
        ------
        Decoded
            One decoded record per declared record in the source.

        Raises
        ------
        LayoutError
            If the record has no character form.
        RecordLengthError
            If any record's width is wrong.
        """
        self._require_text_decodable()
        for number, record in enumerate(
            iter_ascii_text_records(source, self.layout.reclen), start=1
        ):
            yield self.decode_ascii(record, number=number)

    def read_ascii(self, path: pathlib.Path) -> Iterator[Decoded]:
        """Stream the ASCII seed form from a file at an explicit path.

        Parameters
        ----------
        path : pathlib.Path
            The seed file to read.

        Yields
        ------
        Decoded
            One decoded record at a time; the file closes when the caller stops reading.

        Raises
        ------
        OSError
            If the file cannot be opened.
        LayoutError
            If the record has no character form.
        RecordLengthError
            If any record's width is wrong.
        """
        # WHY : Assumptions: latin-1 is the decode, not utf-8, because a seed record is
        #   single-byte data whose every byte value must map to exactly one character for the
        #   declared offsets to hold. latin-1 is total over the byte range; utf-8 would raise
        #   on a byte that is legitimately part of a sign overpunch or a high-value pad.
        with path.open("r", encoding="latin-1", newline="\n") as handle:
            yield from self.iter_ascii(handle)

    def decode_ebcdic(self, record: bytes | bytearray | memoryview) -> Decoded:
        """Decode one record from the EBCDIC dataset form.

        Parameters
        ----------
        record : bytes | bytearray | memoryview
            One fixed-length record image at its declared byte length.

        Returns
        -------
        Decoded
            Every data field keyed by its copybook name, with the trailing pad dropped.

        Raises
        ------
        RecordLengthError
            If the image is not the declared length.
        ValueError
            If a field's byte pattern violates its declared contract.
        """
        return self._project(decode_record(record, self.layout))

    def iter_ebcdic(
        self,
        source: pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object],
    ) -> Iterator[Decoded]:
        """Decode the EBCDIC dataset form one record at a time.

        Parameters
        ----------
        source : pathlib.PurePath | bytes | bytearray | memoryview | Iterable[object]
            A dataset path, an image the caller holds, or an iterable of images.

        Yields
        ------
        Decoded
            One decoded record per fixed-length image in the source.

        Raises
        ------
        RecordLengthError
            If the source length is not a whole multiple of the declared record length.
        ValueError
            If a field's byte pattern violates its declared contract.
        """
        for record in iter_ebcdic_records(source, self.layout):
            yield self.decode_ebcdic(record)

    def read_ebcdic(self, path: pathlib.Path) -> Iterator[Decoded]:
        """Stream the EBCDIC dataset form from a file at an explicit path.

        Parameters
        ----------
        path : pathlib.Path
            The dataset file to read.

        Yields
        ------
        Decoded
            One decoded record at a time, with the file size validated against the declared
            record length before the first record is produced.

        Raises
        ------
        OSError
            If the file cannot be opened.
        RecordLengthError
            If the file size is not a whole multiple of the declared record length.
        """
        yield from self.iter_ebcdic(pathlib.PurePath(path))

    def render_masked_record(self, record: str) -> str:
        """Render one character record with every sensitive field redacted.

        Purpose
        -------
        Give an operator a privacy-safe, byte-aligned rendering of a whole record, so a failed
        comparison can show its shape and which field differs without emitting a protected
        value.

        Parameters
        ----------
        record : str
            One whole record at its declared character width.

        Returns
        -------
        str
            The record with every field the layout marks sensitive replaced by its redaction.

        Raises
        ------
        RecordLengthError
            If the record's width is not the declared width.
        """
        return mask_record(record, self.layout)

    def render_masked_field(self, record: str, field_name: str) -> str:
        """Render one named field of a character record with redaction applied.

        Parameters
        ----------
        record : str
            One whole record at its declared character width.
        field_name : str
            The copybook name of the field to render.

        Returns
        -------
        str
            The field's rendering, redacted when the layout marks it sensitive.

        Raises
        ------
        LayoutError
            If the record declares no field of that name.
        """
        field = self.layout.field(field_name)
        return mask_field(field, record[field.start : field.end])
