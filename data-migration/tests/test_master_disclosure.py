"""Exercise the fail-closed disclosure policy the account-bearing masters are read under.

Purpose
-------
Execute :data:`carddemo_migration.copybook.layouts._ACCOUNT_MASTER_DISCLOSABLE_FIELDS` and the
helper that applies it, and drive the two reader-level renderings that depend on it, rather
than reading the allowlist and trusting it. The account master, its packed export branch and
the transaction-category balance previously declared NO sensitive field at all, which made
:func:`carddemo_migration.copybook.layouts.mask_record` an identity function over them -- so
``render_masked_account_record`` and ``render_masked_category_balance_record`` returned the
eleven-digit account identifier and every monetary amount in clear while being named and
documented as privacy-safe renderings.

Assumptions: the properties below are asserted over EVERY field of all three records rather
than over a sample. A fail-closed policy earns its keep on the field nobody thought about, so
the test that matters is "every field is either named or withheld" and not "these
known-sensitive names are marked" -- the second form passes for a policy that happens to be
right today and says nothing about the next field added to a copybook transcription.

Assumptions: the withholding assertions probe for the CLEAR value in the rendered output rather
than for the shape of the redaction. A rendering can only regress by disclosing, so a probe that
fails exactly when a clear value reappears is the failure mode these tests exist to catch; the
shape of the tag is the shared masking helper's contract and is asserted where that helper is.

Trade-offs: geometry is asserted alongside disclosure in the same cases rather than in separate
ones. The two are one property in practice -- a masked record is only useful to an operator if
offsets are still countable across it -- and splitting them would let a change that fixed
disclosure by shortening the record pass one case while failing the other with no indication
that they were the same edit.
"""

from __future__ import annotations

from decimal import Decimal

import pytest

from carddemo_migration.copybook import layouts
from carddemo_migration.copybook.zoned import decode_zoned_field
from carddemo_migration.readers.account import render_masked_account_record
from carddemo_migration.readers.tcatbal import render_masked_category_balance_record

# WHY : Assumptions: the three records governed by the master disclosure policy are named as a
#   literal rather than discovered, because the policy is a decision about THESE records. A
#   discovered list would silently start covering a record the policy was never reasoned about.
_GOVERNED = ("ACCOUNT_LAYOUT", "TCATBAL_LAYOUT", "EXPORT_ACCOUNT_LAYOUT")

# WHY : Assumptions: a synthetic 300-byte account record built from parts that are individually
#   searchable in the output. Real seed bytes were rejected as a fixture here: several of their
#   fields are all zeros or all blanks, which appear in the rendering for reasons unrelated to
#   masking and would make a `not in` probe pass without proving anything.
# WHY : Assumptions: every S9(n)V99 span below ends in an OVERPUNCHED low-order digit rather
#   than a plain one, because a signed display field carries its sign there and the zoned codec
#   REFUSES a signed span whose last character is a plain digit -- measured, not assumed: it
#   raises ZonedDecimalError naming the zero-based index. A fixture written with plain trailing
#   digits masks and renders identically, so it would pass every disclosure case in this module
#   while being undecodable, and the one case that decodes would fail for a reason unrelated to
#   what it asserts. '{' is the positive-zero overpunch and '}' the negative.
_ACCOUNT_ID = "00000000011"
_CURRENT_BALANCE = "00000150477{"
_CREDIT_LIMIT = "00002500000{"
_CASH_CREDIT_LIMIT = "00000500000{"
_CYCLE_CREDIT = "00000001000{"
_CYCLE_DEBIT = "00000002000{"
_POSTAL_CODE = "98101     "
_GROUP_ID = "ZEROAPR   "
_ACCOUNT_RECORD = (
    _ACCOUNT_ID
    + "Y"
    + _CURRENT_BALANCE
    + _CREDIT_LIMIT
    + _CASH_CREDIT_LIMIT
    + "2020-01-01"
    + "2026-01-01"
    + "2024-01-01"
    + _CYCLE_CREDIT
    + _CYCLE_DEBIT
    + _POSTAL_CODE
    + _GROUP_ID
    + " " * 178
)

_CATEGORY_BALANCE = "0000150477{"
_CATEGORY_RECORD = _ACCOUNT_ID + "01" + "0001" + _CATEGORY_BALANCE + " " * 22


@pytest.mark.parametrize("layout_name", _GOVERNED)
def test_every_governed_field_is_named_or_withheld(layout_name: str) -> None:
    """Assert the policy is total over one governed record.

    Parameters
    ----------
    layout_name : str
        The attribute name of the record specification under assertion.

    Returns
    -------
    None
        Nothing; a field that is neither named disclosable nor marked sensitive is reported as
        an assertion failure.
    """
    layout = getattr(layouts, layout_name)
    allowlist = layouts._ACCOUNT_MASTER_DISCLOSABLE_FIELDS  # noqa: SLF001

    for field in layout.fields:
        named = field.name in allowlist
        assert named != field.sensitive, (
            f"{field.name} of {layout.name} is {'both' if named and field.sensitive else 'neither'}"
            " named disclosable and marked sensitive; the policy must decide exactly one"
        )


@pytest.mark.parametrize(
    ("layout_name", "expected"),
    [
        (
            "ACCOUNT_LAYOUT",
            (
                "ACCT-ID",
                "ACCT-CURR-BAL",
                "ACCT-CREDIT-LIMIT",
                "ACCT-CASH-CREDIT-LIMIT",
                "ACCT-CURR-CYC-CREDIT",
                "ACCT-CURR-CYC-DEBIT",
                "ACCT-ADDR-ZIP",
            ),
        ),
        ("TCATBAL_LAYOUT", ("TRANCAT-ACCT-ID", "TRAN-CAT-BAL")),
        (
            "EXPORT_ACCOUNT_LAYOUT",
            (
                "EXP-ACCT-ID",
                "EXP-ACCT-CURR-BAL",
                "EXP-ACCT-CREDIT-LIMIT",
                "EXP-ACCT-CASH-CREDIT-LIMIT",
                "EXP-ACCT-CURR-CYC-CREDIT",
                "EXP-ACCT-CURR-CYC-DEBIT",
                "EXP-ACCT-ADDR-ZIP",
            ),
        ),
    ],
)
def test_the_withheld_set_is_exactly_the_identifier_the_money_and_the_postal_code(
    layout_name: str, expected: tuple[str, ...]
) -> None:
    """Pin the withheld set of one governed record name by name.

    Parameters
    ----------
    layout_name : str
        The attribute name of the record specification under assertion.
    expected : tuple[str, ...]
        Every field name that must be withheld, in no particular order.

    Returns
    -------
    None
        Nothing; a difference in either direction is reported as an assertion failure.
    """
    # WHY : Assumptions: pinned by NAME rather than by count. A count would pass a change that
    #   swapped a withheld money field for a disclosed date, which is the substitution most
    #   likely to be made by accident while editing a copybook transcription.
    layout = getattr(layouts, layout_name)
    withheld = tuple(field.name for field in layout.fields if field.sensitive)

    assert sorted(withheld) == sorted(expected)


def test_the_account_rendering_withholds_the_identifier_and_every_amount() -> None:
    """Assert the account reader's masked rendering discloses none of the protected values.

    Returns
    -------
    None
        Nothing; a clear identifier, amount or postal code in the rendering is reported as an
        assertion failure.
    """
    rendered = render_masked_account_record(_ACCOUNT_RECORD)

    assert len(rendered) == layouts.ACCOUNT_LAYOUT.reclen, (
        "the rendering must stay byte-aligned so an operator can still count offsets across it"
    )
    for clear, label in (
        (_ACCOUNT_ID, "account identifier"),
        (_CURRENT_BALANCE, "current balance"),
        (_CREDIT_LIMIT, "credit limit"),
        (_CASH_CREDIT_LIMIT, "cash credit limit"),
        (_CYCLE_CREDIT, "cycle credit total"),
        (_CYCLE_DEBIT, "cycle debit total"),
        (_POSTAL_CODE.strip(), "postal code"),
    ):
        assert clear not in rendered, f"the {label} reached a privacy-safe rendering in clear"


def test_the_account_rendering_still_discloses_what_identifies_the_row() -> None:
    """Assert the disclosed fields survive, so the rendering remains useful for a comparison.

    Returns
    -------
    None
        Nothing; a missing disclosable field is reported as an assertion failure.
    """
    # WHY : Assumptions: this is the paired half of the case above and it is why the policy is
    #   an allowlist rather than a blanket redaction. A rendering that withheld everything would
    #   satisfy every probe in the previous case and tell an operator nothing about WHICH field
    #   differs between two records, which is the whole purpose the helper documents.
    rendered = render_masked_account_record(_ACCOUNT_RECORD)

    assert rendered[11] == "Y", "the active-status flag is disclosable and must survive"
    assert "2026-01-01" in rendered, "the expiration date is disclosable and must survive"
    assert _GROUP_ID.strip() in rendered, (
        "the disclosure-group code names a rate table, not a person"
    )


def test_the_category_balance_rendering_withholds_the_identifier_and_the_balance() -> None:
    """Assert the category-balance rendering withholds its two protected fields and keeps geometry.

    Returns
    -------
    None
        Nothing; a clear identifier or balance, or a changed width, is reported as an assertion
        failure.
    """
    rendered = render_masked_category_balance_record(_CATEGORY_RECORD)

    assert len(rendered) == layouts.TCATBAL_LAYOUT.reclen
    assert _ACCOUNT_ID not in rendered, "the account identifier reached the rendering in clear"
    assert _CATEGORY_BALANCE not in rendered, "the running balance reached the rendering in clear"
    # WHY : Assumptions: the two key codes are asserted at their exact offsets rather than by
    #   containment, because both are short digit strings that could appear anywhere in a
    #   redaction tag by chance and a containment probe would then pass for the wrong reason.
    assert rendered[11:13] == "01", "the transaction type code is disclosable"
    assert rendered[13:17] == "0001", "the transaction category code is disclosable"


@pytest.mark.parametrize("layout_name", _GOVERNED)
def test_closing_disclosure_leaves_the_geometry_contract_intact(layout_name: str) -> None:
    """Assert the policy changed sensitivity only, never a field's position, width or regime.

    Parameters
    ----------
    layout_name : str
        The attribute name of the record specification under assertion.

    Returns
    -------
    None
        Nothing; a moved, resized or re-typed field is reported as an assertion failure.
    """
    # WHY : Assumptions: this is what makes the policy safe to apply BETWEEN the field
    #   declarations and validate_geometry(). The declared record length is re-derived from the
    #   fields here rather than trusted, so a helper that silently dropped or reordered a field
    #   would fail even though every individual field still looked correct.
    layout = getattr(layouts, layout_name)

    offset = 0
    for field in layout.fields:
        assert field.start == offset, (
            f"{field.name} of {layout.name} starts at {field.start}, not at {offset}"
        )
        offset += field.length
    assert offset == layout.reclen


def test_the_policy_names_no_field_that_no_governed_record_declares() -> None:
    """Assert every allowlisted name is reachable, so a typo cannot hide as a disclosure.

    Returns
    -------
    None
        Nothing; an allowlisted name that no governed record declares is reported as an
        assertion failure.
    """
    # WHY : Assumptions: a misspelled entry in a fail-closed allowlist is invisible in the safe
    #   direction -- the field it was meant to disclose simply stays withheld -- so nothing else
    #   in this module would catch it. It matters because the next reader deletes the redundant
    #   entry or, worse, "fixes" the field name to match it.
    declared = {field.name for name in _GOVERNED for field in getattr(layouts, name).fields}

    unreachable = sorted(
        layouts._ACCOUNT_MASTER_DISCLOSABLE_FIELDS - declared  # noqa: SLF001
    )

    assert unreachable == []


def test_the_master_policy_is_separate_from_the_authorization_policy() -> None:
    """Assert the two disclosure policies remain distinct declarations.

    Returns
    -------
    None
        Nothing; a merged policy is reported as an assertion failure.
    """
    # WHY : Assumptions: the two lists genuinely disagree -- the authorization policy discloses
    #   PA-ACCT-ID while this one withholds ACCT-ID -- and that disagreement is the reason they
    #   are separate. This case exists so a later merge is a deliberate act with a failing test
    #   to answer rather than a tidy-up, because the authorization list is pinned field by field
    #   from the Java side by AuthorizationDisclosurePolicyTest.
    master = layouts._ACCOUNT_MASTER_DISCLOSABLE_FIELDS  # noqa: SLF001
    authorization = layouts._AUTHORIZATION_DISCLOSABLE_FIELDS  # noqa: SLF001

    assert master is not authorization
    assert "PA-ACCT-ID" in authorization
    assert "ACCT-ID" not in master
    # WHY : Assumptions: FILLER is the one name both lists carry, and it is asserted so the
    #   overlap is a recorded fact rather than a coincidence a reader has to re-derive.
    assert master & authorization == {"FILLER"}


def test_a_negatively_signed_amount_is_still_decodable_after_the_policy_applies() -> None:
    """Assert marking a money field sensitive did not disturb its decoding.

    Returns
    -------
    None
        Nothing; a decoding difference is reported as an assertion failure.
    """
    # WHY : Assumptions: sensitivity is a RENDERING flag and decoding must be untouched by it.
    #   The check is made on a NEGATIVE amount specifically, because the sign lives in the
    #   low-order digit of a zoned field as a sign overpunch, and a helper that rebuilt a field
    #   descriptor rather than copying it could plausibly lose the signedness while leaving
    #   offset and width correct -- a defect no geometry assertion would see.
    field = layouts.ACCOUNT_LAYOUT.field("ACCT-CURR-BAL")

    assert field.sensitive is True
    assert field.signed is True
    assert decode_zoned_field(_ACCOUNT_RECORD, field) == Decimal("15047.70")

    # WHY : Assumptions: the overpunched form is decoded through the SAME descriptor, so the
    #   assertion is that sensitivity did not disturb the sign path rather than that the codec
    #   handles overpunch -- which zoned's own suite already covers at length.
    negative = _ACCOUNT_RECORD[:23] + "}" + _ACCOUNT_RECORD[24:]
    assert decode_zoned_field(negative, field) == Decimal("-15047.70")
