"""Exercise the carrier that keeps the card verification value loadable and unrenderable.

Purpose
-------
Drive :class:`carddemo_migration.readers.card.ProtectedValue` and the card reader that produces
it, rather than reading the design note and trusting it. The reader previously excluded the
verification value before a byte of it was read, which protected it and also made
``card.cards.cvv_encrypted`` impossible to populate from an extract -- the column the migration
contract requires. The carrier restores the value and removes the accidental routes by which it
would otherwise reach a log, a traceback or a test diff.

Assumptions: every rendering route is probed SEPARATELY rather than a representative one being
taken as proof of the rest. They dispatch differently in CPython and that is the whole hazard:
a container renders its members through ``repr``, an f-string carrying a format specification
reaches ``__format__`` and never ``__str__``, and ``object.__format__`` falls back to ``str``
only for an EMPTY specification. A type that overrode one of the three would look protected in
whichever probe happened to be written.

Trade-offs: the assertions look for the CLEAR value in the rendered text rather than for the
marker. A rendering can only regress by disclosing, so a probe that fails exactly when the clear
value reappears is the failure mode these tests exist to catch; the marker is additionally
asserted once, so a carrier that rendered as the empty string could not pass by disclosing
nothing at all.
"""

from __future__ import annotations

import pytest

from carddemo_migration.copybook.layouts import LayoutError, layout
from carddemo_migration.readers.card import (
    LOADED_FIELDS,
    PROTECTED_FIELD_NAMES,
    ProtectedValue,
    decode_ascii_card,
    decode_ebcdic_card,
    render_masked_card_field,
    render_masked_card_record,
)

# WHY : Assumptions: a synthetic 150-byte card record whose verification value is a digit string
#   that appears nowhere else in it. A value that also occurred inside the account identifier or
#   the expiration date would make every `not in` probe below pass for the wrong reason.
_VERIFICATION_VALUE = "917"
_PRIMARY_ACCOUNT_NUMBER = "4859452612877065"
_CARD_RECORD = (
    _PRIMARY_ACCOUNT_NUMBER
    + "00000000011"
    + _VERIFICATION_VALUE
    + "JOHN Q PUBLIC".ljust(50)
    + "2026-12-31"
    + "Y"
    + " " * 59
)


def test_the_record_fixture_is_the_declared_width() -> None:
    """Guard the fixture, so a width error cannot make every case below fail for that reason.

    Returns
    -------
    None
        Nothing; a fixture of the wrong width is reported as an assertion failure.
    """
    # WHY : Assumptions: asserted first and separately because the reader REFUSES a record of the
    #   wrong width outright. Without this case, one mis-sized fixture would surface as six
    #   unrelated failures and the disclosure properties would appear to be broken.
    assert len(_CARD_RECORD) == 150


def test_the_verification_value_is_decoded_rather_than_discarded() -> None:
    """Assert the value reaches the decoded record, which is what makes its column loadable.

    Returns
    -------
    None
        Nothing; an absent key or an unwrapped value is reported as an assertion failure.
    """
    decoded = decode_ascii_card(_CARD_RECORD)

    assert "CARD-CVV-CD" in decoded, (
        "the encrypted target column cannot be populated from an extract the reader drops"
    )
    assert isinstance(decoded["CARD-CVV-CD"], ProtectedValue)
    assert decoded["CARD-CVV-CD"].reveal() == _VERIFICATION_VALUE
    # WHY : Assumptions: the field is also asserted to be in the LOADED projection, because that
    #   tuple is what a loader iterates. A value present in the mapping but absent from the
    #   projection would be decoded and still never written.
    assert "CARD-CVV-CD" in {field.name for field in LOADED_FIELDS}
    assert PROTECTED_FIELD_NAMES == frozenset({"CARD-CVV-CD"})


def test_both_encodings_carry_the_value_inside_the_same_carrier() -> None:
    """Assert the byte path protects the value exactly as the character path does.

    Returns
    -------
    None
        Nothing; an unwrapped value from either path, or two paths that disagree, is reported as
        an assertion failure.
    """
    # WHY : Refactoring Rationale: this case exists because the two paths DID disagree. The
    #   wrapping was applied in the character path's per-field helper, whose comment recorded that
    #   both record decoders reached it; the byte path does not -- it decodes each field through
    #   the EBCDIC codec -- so an ASCII seed yielded a ProtectedValue and an EBCDIC extract yielded
    #   a bare `str` holding the same three digits. That is the wrong way round for the defect to
    #   fall: the thirteen EBCDIC datasets are the authoritative extracts, so every guard the
    #   carrier provides was missing on the only path a real migration runs, and a value that
    #   reached a log or a traceback from there would have done so in the clear.
    # WHY : Assumptions: the two decoded records are compared WHOLE rather than field by field, so
    #   this case also fails if any other field diverges between the encodings -- the corpus
    #   comparison in test_readers.py asserts that over the shipped seeds, and asserting it here
    #   over a synthetic record keeps the two statements independent.
    from_text = decode_ascii_card(_CARD_RECORD)
    from_bytes = decode_ebcdic_card(_CARD_RECORD.encode("cp037"))

    assert isinstance(from_bytes["CARD-CVV-CD"], ProtectedValue), (
        "the byte path must protect the verification value; a bare string here is the value in "
        "the clear on the path the migration actually runs"
    )
    assert from_bytes["CARD-CVV-CD"].reveal() == _VERIFICATION_VALUE
    assert str(from_bytes["CARD-CVV-CD"]) == "[PROTECTED]"
    assert from_text == from_bytes


@pytest.mark.parametrize(
    ("render", "route"),
    [
        (repr, "repr, which is how a container renders its members"),
        (str, "str, which is how print and a bare f-string render"),
        (lambda value: f"{value}", "an f-string with no format specification"),
        (lambda value: f"{value:>20}", "an f-string WITH a format specification"),
        (lambda value: format(value, ""), "format with an empty specification"),
        (lambda value: "%s" % (value,), "percent formatting"),
        (lambda value: "".join([str(value)]), "str.join over a rendered value"),
    ],
)
def test_no_rendering_route_discloses_the_protected_value(render, route: str) -> None:
    """Assert one rendering route yields the marker and never the value.

    Parameters
    ----------
    render : collections.abc.Callable[[object], str]
        The rendering route under test.
    route : str
        A description of the route, used in the failure message.

    Returns
    -------
    None
        Nothing; a route that reproduces the value is reported as an assertion failure.
    """
    carrier = ProtectedValue(_VERIFICATION_VALUE)

    rendered = render(carrier)

    assert _VERIFICATION_VALUE not in rendered, f"the value escaped through {route}"
    assert ProtectedValue.MARKER in rendered, (
        f"{route} must yield the marker; rendering as the empty string would pass a"
        " disclosure probe while telling a reader nothing"
    )


def test_a_rendering_of_the_whole_decoded_record_discloses_nothing() -> None:
    """Assert the mapping the reader returns can be rendered whole without disclosure.

    Returns
    -------
    None
        Nothing; a clear verification value in either rendering is reported as an assertion
        failure.
    """
    # WHY : Assumptions: this is the route that matters most in practice and it is asserted on the
    #   real reader output rather than on a hand-built dict. A decoded record reaches a log as a
    #   whole far more often than one field does, and a dict renders its members through repr --
    #   so this case is what would have caught a carrier that overrode only __str__.
    decoded = decode_ascii_card(_CARD_RECORD)

    assert _VERIFICATION_VALUE not in repr(decoded)
    assert _VERIFICATION_VALUE not in str(decoded)


def test_the_carrier_refuses_comparison_against_a_plain_string() -> None:
    """Assert the carrier cannot be used to confirm a guessed value.

    Returns
    -------
    None
        Nothing; a carrier that compared equal to a matching string is reported as an assertion
        failure.
    """
    # WHY : Assumptions: a permissive comparison would make the carrier an ORACLE -- a caller
    #   could test candidates one at a time and never call reveal, which for a three-digit domain
    #   is a thousand guesses. Equality between two carriers is kept because comparing two decoded
    #   corpora is a legitimate need that discloses nothing.
    carrier = ProtectedValue(_VERIFICATION_VALUE)

    assert (carrier == _VERIFICATION_VALUE) is False
    assert (carrier != _VERIFICATION_VALUE) is True
    assert carrier == ProtectedValue(_VERIFICATION_VALUE)
    assert carrier != ProtectedValue("000")
    assert hash(carrier) == hash(ProtectedValue(_VERIFICATION_VALUE))


def test_the_carrier_refuses_a_value_that_is_not_characters() -> None:
    """Assert the carrier refuses a decoded value of a regime this record does not declare.

    Returns
    -------
    None
        Nothing; an accepted non-string is reported as an assertion failure.
    """
    with pytest.raises(TypeError):
        ProtectedValue(917)  # type: ignore[arg-type]


def test_the_masked_renderings_still_withhold_the_verification_value() -> None:
    """Assert the reader's two diagnostic renderings are unchanged by the carrier.

    Returns
    -------
    None
        Nothing; a disclosed value, a changed record width, or an accepted field rendering is
        reported as an assertion failure.
    """
    # WHY : Assumptions: both renderings are asserted because the carrier changed the DECODE path
    #   and not the rendering path, so this case is a regression guard rather than a new property.
    #   The whole-record form must keep the span present to preserve offsets; the per-field form
    #   must keep refusing, because a caller naming this field is asking for its value.
    rendered = render_masked_card_record(_CARD_RECORD)

    assert len(rendered) == 150
    # WHY : Refactoring Rationale: the verification value is looked for in the SPAN THAT HELD IT
    #   rather than anywhere in the whole record, and the offset is resolved from the layout rather
    #   than typed here. The whole-record probe was intermittently wrong, not merely imprecise:
    #   every masked span carries a keyed-HMAC hex tag whose alphabet includes all ten decimal
    #   digits, so this three-digit value can appear inside ANOTHER field's tag by coincidence --
    #   measured at roughly one run in four hundred against the per-process mask key, which made
    #   the whole suite flaky. A coincidence inside the embossed name's tag is not a disclosure of
    #   the verification value, while the value reappearing in its own span is exactly one, so the
    #   span probe fails on the disclosure this case exists to catch and on nothing else.
    verification_field = next(
        field for field in layout("CARD").fields if field.name == "CARD-CVV-CD"
    )
    verification_span = rendered[
        verification_field.start : verification_field.start + verification_field.length
    ]
    assert len(verification_span) == len(_VERIFICATION_VALUE)
    assert _VERIFICATION_VALUE not in verification_span
    # WHY : Assumptions: the sixteen-digit account number keeps its whole-record probe. A run of
    #   sixteen specific digits appearing inside a hex tag by chance is not a rate this suite has
    #   to design around, so nothing is given up by leaving that assertion at its widest.
    assert _PRIMARY_ACCOUNT_NUMBER not in rendered

    with pytest.raises(LayoutError):
        render_masked_card_field(_CARD_RECORD, "CARD-CVV-CD")
