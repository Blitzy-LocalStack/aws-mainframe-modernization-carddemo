"""Digest decoded records so a corrupted field is detectable without rendering any field.

Purpose
-------
Catch the failure a row count cannot see: the counts agree and a field's value is wrong. A
digest over every loaded field of every record changes when any byte of any field changes, so
comparing a source digest against a target digest proves the two hold the same data.
"""

from __future__ import annotations

import hashlib
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Final

__all__ = [
    "DIGEST_NAME",
    "FIELD_SEPARATOR",
    "RECORD_SEPARATOR",
    "RecordDigest",
    "digest_records",
    "digest_of_record",
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
    """

    digest: str
    records: int
    fields: tuple[str, ...]

    def describe(self) -> str:
        """Render the digest as one privacy-safe line.

        Returns
        -------
        str
            The digest, the record count and the field count. No field VALUE appears; that is
            the property that makes a digest publishable where the records are not.
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
        return str(value).encode("utf-8")
    if isinstance(value, str):
        return value.encode("utf-8")
    raise TypeError(
        f"a decoded field value of type {type(value).__name__} cannot be digested; a reader"
        " yields characters, an exact decimal or raw bytes and nothing else"
    )


def digest_of_record(record: Mapping[str, str | Decimal | bytes], fields: Sequence[str]) -> bytes:
    """Render one record as the bytes contributing to a digest.

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
    return FIELD_SEPARATOR.join(_canonical(record[field]) for field in fields)


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
    ordered = tuple(fields)
    if not ordered:
        raise ValueError(
            "a digest over no field would be identical for every record and would report two"
            " different datasets as matching; name the fields to digest"
        )
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
