"""Exercise the fail-closed disclosure policy the two authorization segments are read under.

Purpose
-------
Execute :data:`carddemo_migration.copybook.layouts._AUTHORIZATION_DISCLOSABLE_FIELDS` and the
helper that applies it, rather than reading the allowlist and trusting it. The two IMS
authorization segments are the only records in this corpus whose content is also decoded by a
second implementation in a second language -- ``CopybookLayout.java`` and ``CsvAuthCodec.java``
in ``services/common-lib`` -- so a sensitivity stated here and stated differently there means
the same byte is withheld from one diagnostic and printed in another. That is precisely what had
happened: the Java transcription marked one field sensitive in the detail segment and none at
all in the summary, while this module withheld sixteen.

Assumptions: the properties below are asserted over EVERY field of both segments rather than
over a sample. A fail-closed policy earns its keep on the field nobody thought about, so the
test that matters is "every field is either named or withheld" and not "these known-sensitive
names are marked". The second form passes for a policy that happens to be right today.

Assumptions: the cross-language set equality is asserted from the Java side, by a test in
``services/common-lib`` that reads THIS module's source and compares the two literals. It is not
duplicated here, because two tests asserting one equality can only ever agree or contradict each
other, and the Java build is the one CI runs on every change to either tree. What this module
asserts instead is the half the Java test cannot see: that the policy is actually APPLIED here,
and that a decode diagnostic for a withheld field carries no content.
"""

from __future__ import annotations

import pytest

from carddemo_migration.copybook import ebcdic_codec, layouts, packed

# Assumptions: the two segments are addressed through their MODULE CONSTANTS and not through
#   :func:`carddemo_migration.copybook.layouts.layout`. Neither is entered in the record registry
#   -- that registry holds the fourteen flat datasets the extract loaders read, and these two are
#   IMS segments with no dataset of their own -- so a name lookup would raise. Referencing the
#   constants also means a rename is an import error at collection time rather than a lookup
#   failure inside every test.
_AUTHORIZATION_SEGMENTS = (
    layouts.PENDING_AUTH_SUMMARY_LAYOUT,
    layouts.PENDING_AUTH_DETAIL_LAYOUT,
)

# Assumptions: 0xFF is invalid for every numeric regime these segments declare and decodes to no
#   ASCII digit, so one filler byte drives the failure path for the packed and the display fields
#   alike. Its high nibble is what the packed codec reports as an invalid digit nibble, which is
#   the reading a withheld field must not disclose.
_PROBE_BYTE = 0xFF

# Assumptions: these two literals are the observable difference the flag makes, taken from the
#   codecs' own renderings. Matching them rather than asserting on the flag is the point: the
#   flag is only a marker, so a test that checked the flag alone would pass while a codec printed
#   the bytes anyway.
_NIBBLE_CLAUSE = "found 0x"
_VALUE_CLAUSE = "value "


def _withheld(segment: layouts.RecordSpec) -> tuple[str, ...]:
    """Name the fields of one segment that the policy withholds.

    Parameters
    ----------
    segment : layouts.RecordSpec
        The segment descriptor.

    Returns
    -------
    tuple[str, ...]
        The withheld field names, in declaration order.
    """
    return tuple(field.name for field in segment.fields if field.sensitive)


@pytest.mark.parametrize("segment", _AUTHORIZATION_SEGMENTS, ids=lambda spec: spec.name)
def test_every_authorization_field_is_named_or_withheld(
    segment: layouts.RecordSpec,
) -> None:
    """Assert each field is disclosable by name or else marked sensitive.

    Parameters
    ----------
    segment : layouts.RecordSpec
        The segment descriptor under test.

    Returns
    -------
    None
        The assertion is the result.
    """
    assert segment.fields, f"segment {segment.name} declares no fields at all"
    for field in segment.fields:
        expected = field.name not in layouts._AUTHORIZATION_DISCLOSABLE_FIELDS
        assert field.sensitive is expected, (
            f"field {field.name} of {segment.name} must be withheld unless the allowlist names it"
        )


def test_the_summary_segment_withholds_its_financial_and_customer_fields() -> None:
    """Assert the summary segment withholds the customer identifier, limits, balances, amounts.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: the seven are literals rather than derived from the allowlist. A derived
    #   expectation restates the policy and then passes for whatever the policy happens to say,
    #   which is the one thing a regression test must not do.
    assert set(_withheld(layouts.PENDING_AUTH_SUMMARY_LAYOUT)) == {
        "PA-CUST-ID",
        "PA-CREDIT-LIMIT",
        "PA-CASH-LIMIT",
        "PA-CREDIT-BALANCE",
        "PA-CASH-BALANCE",
        "PA-APPROVED-AUTH-AMT",
        "PA-DECLINED-AUTH-AMT",
    }


def test_the_detail_segment_withholds_the_wire_codecs_base_names() -> None:
    """Assert the detail segment withholds the nine base names the Java wire codec covers.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: these nine are the base names of CsvAuthCodec.SENSITIVE_FIELD_NAMES with the
    #   request and reply infixes removed, so the count is as load-bearing as the membership --
    #   eight or ten would mean one of the two artifacts had drifted from the other.
    assert set(_withheld(layouts.PENDING_AUTH_DETAIL_LAYOUT)) == {
        "PA-CARD-NUM",
        "PA-CARD-EXPIRY-DATE",
        "PA-TRANSACTION-AMT",
        "PA-APPROVED-AMT",
        "PA-MERCHANT-ID",
        "PA-MERCHANT-NAME",
        "PA-MERCHANT-CITY",
        "PA-MERCHANT-ZIP",
        "PA-TRANSACTION-ID",
    }


def test_the_allowlist_names_no_field_that_neither_segment_declares() -> None:
    """Assert every allowlisted name belongs to one of the two segments.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: a dead name in an allowlist is worse than useless. It reads as a decision that
    #   some field is disclosable when no such field exists, and if a field is later added under
    #   that name it becomes disclosable with nobody having decided so.
    declared = {field.name for segment in _AUTHORIZATION_SEGMENTS for field in segment.fields}
    assert layouts._AUTHORIZATION_DISCLOSABLE_FIELDS <= declared


def test_the_merchant_state_is_disclosable_and_the_narrowing_fields_are_not() -> None:
    """Assert the two-character merchant state stays readable while the other four do not.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: pinned separately because this is the one classification a later contributor
    #   is most likely to "correct" for symmetry. Four merchant fields withheld and a fifth not
    #   reads as an oversight until the reason is read, so the build defends the asymmetry.
    withheld = set(_withheld(layouts.PENDING_AUTH_DETAIL_LAYOUT))
    assert "PA-MERCHANT-STATE" not in withheld
    assert {
        "PA-MERCHANT-ID",
        "PA-MERCHANT-NAME",
        "PA-MERCHANT-CITY",
        "PA-MERCHANT-ZIP",
    } <= withheld


def _decode_one(image: bytes, field: layouts.FieldSpec) -> object:
    """Decode one field through the codec its storage regime belongs to.

    Assumptions: the dispatch is explicit rather than routed through the character codec for
    everything, because that codec returns the untouched bytes for the three numeric regimes
    by design -- it exists so that a packed nibble pair can never reach a character decoder.
    Sending a packed field there would therefore never fail, and a probe that cannot fail
    cannot show what a failure discloses.

    Parameters
    ----------
    image : bytes
        The record image to decode from.
    field : layouts.FieldSpec
        The field descriptor selecting the span and the regime.

    Returns
    -------
    object
        Whatever the selected codec returns.

    Raises
    ------
    Exception
        Whatever the selected codec raises for the given bytes; the caller is asserting on
        the message, so no type is narrowed here.
    """
    if field.kind is layouts.Kind.PACKED:
        return packed.decode_packed_field(image, field)
    if field.kind is layouts.Kind.BINARY:
        return packed.decode_binary_field(image, field)
    return ebcdic_codec.decode_field(image, field)


@pytest.mark.parametrize("segment", _AUTHORIZATION_SEGMENTS, ids=lambda spec: spec.name)
def test_no_withheld_field_leaks_content_into_a_decode_diagnostic(
    segment: layouts.RecordSpec,
) -> None:
    """Assert a decode failure on any withheld field quotes neither a nibble nor a value.

    Parameters
    ----------
    segment : layouts.RecordSpec
        The segment descriptor under test.

    Returns
    -------
    None
        The assertion is the result.
    """
    image = bytes([_PROBE_BYTE]) * segment.reclen

    probed = 0
    for field in segment.fields:
        if not field.sensitive:
            continue
        try:
            _decode_one(image, field)
        except Exception as failure:  # noqa: BLE001
            # Assumptions: the base Exception is caught deliberately and narrowed by the
            #   assertions rather than by the clause. The point of the probe is that NOTHING a
            #   codec raises may carry content, so pinning the exception type here would let a
            #   newly introduced error type escape the very check this test exists to make.
            probed += 1
            text = str(failure)
            assert field.name in text, (
                f"the diagnostic for {field.name} of {segment.name} must name the field"
            )
            assert _NIBBLE_CLAUSE not in text, (
                f"the diagnostic for withheld field {field.name} of {segment.name} quoted a nibble"
            )
            assert _VALUE_CLAUSE not in text, (
                f"the diagnostic for withheld field {field.name} of {segment.name} quoted a value"
            )

    # Assumptions: the count is asserted so the sweep cannot become vacuous. A character field
    #   decodes any byte sequence and raises nothing, which is correct and expected, but if every
    #   withheld field stopped raising -- because a codec grew tolerant, say -- the loop above
    #   would assert nothing at all and still pass.
    assert probed > 0, (
        f"no withheld field of {segment.name} produced a diagnostic, so this probe proves nothing"
    )


@pytest.mark.parametrize("segment", _AUTHORIZATION_SEGMENTS, ids=lambda spec: spec.name)
def test_closing_disclosure_leaves_the_geometry_contract_intact(
    segment: layouts.RecordSpec,
) -> None:
    """Assert applying the policy moved no field and resized none.

    Parameters
    ----------
    segment : layouts.RecordSpec
        The segment descriptor under test.

    Returns
    -------
    None
        The assertion is the result.
    """
    # Assumptions: the helper replaces two flags and carries geometry through, and this asserts
    #   the carrying is real rather than assumed. A helper that rebuilt a field from defaults
    #   would move offsets, and because both segments' widths sum to their declared lengths the
    #   error would surface only as a mis-decode of a money field.
    assert sum(field.length for field in segment.fields) == segment.reclen
    offset = 0
    for field in segment.fields:
        assert field.start == offset, (
            f"field {field.name} of {segment.name} starts at {field.start}, not at {offset}"
        )
        offset += field.length
