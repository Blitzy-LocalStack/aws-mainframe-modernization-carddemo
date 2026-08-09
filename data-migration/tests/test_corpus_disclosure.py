"""Exercise the corpus-wide fail-closed disclosure policy and the masking key's strength floor.

Purpose
-------
Execute the two controls that stand between a decoded CardDemo extract and an operator's log:
the allowlist in :mod:`carddemo_migration.copybook.layouts` that decides which fields a
diagnostic may render, and the enforcement that refuses masking-key material too weak to make a
redaction tag unconfirmable. Both were fail-open before, and both fail in the same silent way
when they regress -- a disclosed field decodes and renders perfectly, and a weak key produces a
tag that looks identical to a strong one -- so neither can be verified by reading the source.

Assumptions: the disclosure properties are asserted over EVERY field of EVERY record this
module declares, not over a sample. A fail-closed policy earns its keep on the field nobody
thought about, so the test that matters is "every field is either named or withheld"; a test
listing today's known-sensitive names passes for a policy that is merely right today.

Assumptions: the negative-disclosure assertions are made on rendered OUTPUT and never on the
``sensitive`` flag alone. The flag is a marker that performs no masking itself, so a suite that
checked flags would keep passing if the masking function stopped consulting them. Each field is
filled with a sentinel unique to its position, which is what makes a leak attributable to the
field that leaked rather than merely detectable.

Assumptions: the two authorization segments are covered here only by the corpus-wide
properties. Their own allowlist, its name-for-name equality with the Java transcription and its
per-field application are asserted in ``test_authorization_disclosure.py`` and in
``services/common-lib``; duplicating them here would give two places to disagree.
"""

from __future__ import annotations

import base64
import hashlib
import secrets

import pytest

from carddemo_migration.copybook import layouts

# Assumptions: the population is taken from the module's own namespace walker rather than from
#   a list written here, for the same reason the walker exists: a list in the test would have the
#   identical failure mode as a list in the module, so a record added without being closed would
#   go unnoticed by exactly the test meant to notice it.
_DECLARED_LAYOUTS = layouts._declared_layouts()

# Assumptions: these are the field names the review named as disclosed and prohibited --
#   identifiers, money, balances, merchant text and the national identifier -- listed explicitly
#   rather than matched by a name pattern. A pattern would be a guess: TRAN-TYPE-DESC and
#   TRAN-CAT-TYPE-DESC end in -DESC and ARE disclosable, because they are seeded reference text
#   with no customer linkage, while TRAN-DESC is a purchase description and is not.
_PROHIBITED_NAMES = (
    # Money, limits, balances and cycle totals, on the master and its export projection.
    "ACCT-CURR-BAL",
    "ACCT-CREDIT-LIMIT",
    "ACCT-CASH-CREDIT-LIMIT",
    "ACCT-CURR-CYC-CREDIT",
    "ACCT-CURR-CYC-DEBIT",
    "EXP-ACCT-CURR-BAL",
    "EXP-ACCT-CREDIT-LIMIT",
    "EXP-ACCT-CASH-CREDIT-LIMIT",
    "EXP-ACCT-CURR-CYC-CREDIT",
    "EXP-ACCT-CURR-CYC-DEBIT",
    "TRAN-AMT",
    "TRNX-AMT",
    "DALYTRAN-AMT",
    "EXP-TRAN-AMT",
    "TRAN-CAT-BAL",
    # Card and transaction identifiers, and the customer identifier.
    "CARD-NUM",
    "XREF-CARD-NUM",
    "TRAN-CARD-NUM",
    "TRNX-CARD-NUM",
    "DALYTRAN-CARD-NUM",
    "EXP-CARD-NUM",
    "EXP-XREF-CARD-NUM",
    "EXP-TRAN-CARD-NUM",
    "TRAN-ID",
    "TRNX-ID",
    "DALYTRAN-ID",
    "EXP-TRAN-ID",
    "CUST-ID",
    "XREF-CUST-ID",
    "EXP-CUST-ID",
    "EXP-XREF-CUST-ID",
    # Merchant identity and location, and free-text purchase descriptions.
    "TRAN-MERCHANT-ID",
    "TRAN-MERCHANT-NAME",
    "TRAN-MERCHANT-CITY",
    "TRAN-MERCHANT-ZIP",
    "TRNX-MERCHANT-ID",
    "TRNX-MERCHANT-NAME",
    "TRNX-MERCHANT-CITY",
    "TRNX-MERCHANT-ZIP",
    "DALYTRAN-MERCHANT-ID",
    "DALYTRAN-MERCHANT-NAME",
    "DALYTRAN-MERCHANT-CITY",
    "DALYTRAN-MERCHANT-ZIP",
    "EXP-TRAN-MERCHANT-ID",
    "EXP-TRAN-MERCHANT-NAME",
    "EXP-TRAN-MERCHANT-CITY",
    "EXP-TRAN-MERCHANT-ZIP",
    "TRAN-DESC",
    "TRNX-DESC",
    "DALYTRAN-DESC",
    "EXP-TRAN-DESC",
    # Cardholder identity, credentials and the two encrypted identifiers.
    "CARD-CVV-CD",
    "CARD-EMBOSSED-NAME",
    "CARD-EXPIRAION-DATE",
    "EXP-CARD-CVV-CD",
    "EXP-CARD-EMBOSSED-NAME",
    "EXP-CARD-EXPIRAION-DATE",
    "CUST-SSN",
    "EXP-CUST-SSN",
    "CUST-GOVT-ISSUED-ID",
    "EXP-CUST-GOVT-ISSUED-ID",
    "CUST-DOB-YYYY-MM-DD",
    "EXP-CUST-DOB-YYYY-MM-DD",
    "CUST-FICO-CREDIT-SCORE",
    "EXP-CUST-FICO-CREDIT-SCORE",
    "ACCT-ADDR-ZIP",
    "EXP-ACCT-ADDR-ZIP",
    "CUST-ADDR-ZIP",
    "EXP-CUST-ADDR-ZIP",
    "SEC-USR-PWD",
)

# Assumptions: 32 is asserted against the hash's own output size rather than written twice, so
#   the floor cannot drift away from the reason for it if the construction ever changes.
_HMAC_OUTPUT_BYTES = hashlib.sha256().digest_size


# Assumptions: each field is filled with ONE character used by no other field of the same
#   record, and the alphabet deliberately excludes every character a redaction can emit -- the
#   hexadecimal digits, the angle brackets, the asterisk and the space. That exclusion is what
#   makes "this field's span appears nowhere in the masked record" a falsifiable statement: with
#   a multi-character marker, a two-byte field's marker can occur inside a wider neighbour's
#   repetition, and the assertion then fails on a record that leaked nothing.
_SENTINEL_ALPHABET = "GHIJKLMNOPQRSTUVWXYZghijklmnopqrstuvwxyz"


def _sentinel_record(layout: layouts.RecordSpec) -> str:
    """Build a record of declared length whose every field carries a unique sentinel.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record whose geometry the sentinels are laid out against.

    Returns
    -------
    str
        Exactly ``layout.reclen`` characters, each field's span filled with a character
        unique to that field's ordinal position within the record.

    Raises
    ------
    AssertionError
        If the record declares more fields than the sentinel alphabet can distinguish.
    """
    # WHY (Assumptions): the sentinel is chosen by the field's ORDINAL and not by its name,
    #   because a name-derived marker would let a leak of one field be mistaken for a leak of a
    #   similarly named one -- TRAN-MERCHANT-ZIP and TRNX-MERCHANT-ZIP share a suffix. An ordinal
    #   is unique within the record by construction.
    assert len(layout.fields) <= len(_SENTINEL_ALPHABET), (
        f"record {layout.name} declares {len(layout.fields)} fields, more than the sentinel"
        " alphabet can keep distinct"
    )
    return "".join(
        _SENTINEL_ALPHABET[ordinal] * field.length for ordinal, field in enumerate(layout.fields)
    )


@pytest.mark.parametrize("layout", _DECLARED_LAYOUTS, ids=lambda spec: spec.name)
def test_every_field_of_every_record_is_named_or_withheld(
    layout: layouts.RecordSpec,
) -> None:
    """Assert each field is admitted by an allowlist or else marked sensitive.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record descriptor under test.

    Returns
    -------
    None
        The assertion is the result.
    """
    admitted = layouts._CORPUS_DISCLOSABLE_FIELDS | layouts._AUTHORIZATION_DISCLOSABLE_FIELDS
    assert layout.fields, f"record {layout.name} declares no fields at all"
    for field in layout.fields:
        if field.sensitive:
            continue
        assert field.name in admitted, (
            f"field {field.name} of {layout.name} is rendered verbatim by every diagnostic but"
            " no allowlist admits it"
        )


def test_the_import_time_audit_reports_no_unnamed_disclosure() -> None:
    """Assert the module's own import-time audit found nothing to refuse.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): this re-runs the audit over the FULLY imported module rather than
    #   trusting the constant the module computed while importing. A record declared below the
    #   audit's own call site would escape that call, and re-running here is what closes the
    #   gap -- so the two assertions are not redundant.
    assert layouts._UNNAMED_DISCLOSURES == ()
    assert layouts._unnamed_disclosures(layouts._declared_layouts()) == ()


def test_the_audit_detects_a_record_left_unclosed() -> None:
    """Assert the audit reports a disclosable field no allowlist admits.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the guard is exercised against a record built HERE rather than by
    #   editing one of the module's own, because "the audit returned nothing" is only evidence
    #   that the corpus is closed if the audit returns something when it is not. Without this
    #   case an audit that had been reduced to `return ()` would pass every other test in this
    #   file.
    unclosed = layouts.RecordSpec(
        "SYNTHETIC-UNCLOSED",
        20,
        4,
        0,
        (
            layouts.text("SYNTHETIC-KEY", 0, 4),
            layouts.text("FILLER", 4, 16),
        ),
    ).validate_geometry()
    findings = layouts._unnamed_disclosures((unclosed,))
    assert findings == ("SYNTHETIC-UNCLOSED.SYNTHETIC-KEY",), findings

    # WHY (Assumptions): the same record passed through the closure helper reports nothing,
    #   which is what proves the finding above was the POLICY speaking and not the audit
    #   objecting to a synthetic record on some unrelated ground.
    closed = layouts.RecordSpec(
        "SYNTHETIC-CLOSED",
        20,
        4,
        0,
        layouts._closed(
            (
                layouts.text("SYNTHETIC-KEY", 0, 4),
                layouts.text("FILLER", 4, 16),
            )
        ),
    ).validate_geometry()
    assert layouts._unnamed_disclosures((closed,)) == ()


def test_the_audit_walks_every_record_the_module_declares() -> None:
    """Assert the audited population is every declared record and not a subset.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): the count is asserted against the three populations that make it up
    #   rather than against a bare number, so a record moved between them still reconciles while
    #   a record dropped from the walk does not. The registry holds the flat datasets; the export
    #   projections and the two IMS segments are declared outside it.
    audited = {spec.name for spec in _DECLARED_LAYOUTS}
    assert set(layouts.LAYOUTS) <= audited
    for spec in (
        layouts.EXPORT_HEADER_LAYOUT,
        layouts.EXPORT_ACCOUNT_LAYOUT,
        layouts.EXPORT_CARD_LAYOUT,
        layouts.EXPORT_CARD_XREF_LAYOUT,
        layouts.EXPORT_CUSTOMER_LAYOUT,
        layouts.EXPORT_TRANSACTION_LAYOUT,
        layouts.PENDING_AUTH_SUMMARY_LAYOUT,
        layouts.PENDING_AUTH_DETAIL_LAYOUT,
    ):
        assert spec.name in audited, f"{spec.name} is declared but never audited"
    assert len(audited) == len(_DECLARED_LAYOUTS), "two records share one name"


@pytest.mark.parametrize("name", _PROHIBITED_NAMES)
def test_a_prohibited_field_is_withheld_wherever_it_is_declared(name: str) -> None:
    """Assert a named prohibited field is marked sensitive in every record declaring it.

    Parameters
    ----------
    name : str
        The copybook field name the disclosure policy must withhold.

    Returns
    -------
    None
        The assertion is the result.
    """
    declaring = [spec for spec in _DECLARED_LAYOUTS if any(f.name == name for f in spec.fields)]
    # WHY (Assumptions): the case fails when NO record declares the name, rather than passing
    #   vacuously. A prohibited name that has been renamed or removed would otherwise leave this
    #   case green while asserting nothing at all, which is the failure mode a hand-kept list of
    #   names is most prone to.
    assert declaring, f"no record declares {name}; the prohibited list is stale"
    for spec in declaring:
        assert spec.field(name).sensitive, (
            f"{name} must be withheld from diagnostics but {spec.name} discloses it"
        )


def test_the_allowlist_names_no_field_the_corpus_does_not_declare() -> None:
    """Assert every admitted name belongs to a real field of a real record.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): a stale allowlist entry is not merely untidy. It is a standing
    #   permission for whatever field is declared under that name next, granted before anybody
    #   looks at what that field holds, which is the exact reverse of the decision order a
    #   fail-closed policy exists to impose.
    declared = {field.name for spec in _DECLARED_LAYOUTS for field in spec.fields}
    stale = layouts._CORPUS_DISCLOSABLE_FIELDS - declared
    assert stale == set(), f"the corpus allowlist names undeclared fields: {sorted(stale)}"


@pytest.mark.parametrize("layout", _DECLARED_LAYOUTS, ids=lambda spec: spec.name)
def test_a_masked_record_carries_no_withheld_field_s_content(
    layout: layouts.RecordSpec,
) -> None:
    """Assert masking removes every withheld field's characters and keeps every admitted one.

    Parameters
    ----------
    layout : layouts.RecordSpec
        The record descriptor under test.

    Returns
    -------
    None
        The assertion is the result.
    """
    raw = _sentinel_record(layout)
    assert len(raw) == layout.reclen
    masked = layouts.mask_record(raw, layout)
    assert len(masked) == layout.reclen, "a masked record must still tile the record it describes"

    for field in layout.fields:
        span = raw[field.start : field.end]
        rendered = masked[field.start : field.end]
        if not field.sensitive:
            assert rendered == span, f"{field.name} is admitted and must render verbatim"
            continue
        if field.name in layouts._LAST4_REVEAL:
            # WHY (Assumptions): the four card-number names keep the documented last-four
            #   concession, so their span is asserted to be masked EXCEPT its final four
            #   characters rather than absent. Asserting absence here would contradict the
            #   policy, and asserting nothing would leave the concession's width unchecked --
            #   which is what would let it widen to eight unnoticed.
            assert rendered.endswith(span[-4:])
            assert rendered[:-4] == "*" * (field.length - 4)
            continue
        assert rendered != span, f"{field.name} is withheld and must not render verbatim"
        assert span not in masked, f"{field.name}'s content reappears elsewhere in the rendering"


def test_the_customer_national_identifier_is_never_revealed_in_part() -> None:
    """Assert neither national-identifier field reveals its trailing characters.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Refactoring Rationale): CUST-SSN was in the last-four concession and is removed, so
    #   this case pins the removal. It asserts the rendered OUTPUT and not just the membership,
    #   because membership alone would keep passing if the masking function grew a second route
    #   to a partial reveal.
    for spec, name in (
        (layouts.CUSTOMER_LAYOUT, "CUST-SSN"),
        (layouts.EXPORT_CUSTOMER_LAYOUT, "EXP-CUST-SSN"),
    ):
        assert name not in layouts._LAST4_REVEAL
        field = spec.field(name)
        raw = _sentinel_record(spec)
        span = raw[field.start : field.end]
        rendered = layouts.mask_record(raw, spec)[field.start : field.end]
        assert span[-4:] not in rendered, f"{name} must not reveal its trailing characters"
        assert "*" not in rendered, f"{name} must not render the partial-reveal marker"


def test_the_last_four_concession_covers_card_numbers_only() -> None:
    """Assert the partial-reveal set is exactly the four card-number fields.

    Returns
    -------
    None
        The assertion is the result.
    """
    assert layouts._LAST4_REVEAL == frozenset(
        {"CARD-NUM", "XREF-CARD-NUM", "TRAN-CARD-NUM", "DALYTRAN-CARD-NUM"}
    )
    # WHY (Assumptions): every member is additionally required to be a declared, withheld
    #   sixteen-character field, so the set cannot be satisfied by a name that no longer exists
    #   or by one whose width has changed -- the width is what bounds how much four revealed
    #   characters disclose.
    for name in layouts._LAST4_REVEAL:
        declaring = [s for s in _DECLARED_LAYOUTS if any(f.name == name for f in s.fields)]
        assert declaring, f"{name} is granted a partial reveal but no record declares it"
        for spec in declaring:
            field = spec.field(name)
            assert field.sensitive and field.length == 16


def test_the_key_floor_is_the_hash_s_own_output_size() -> None:
    """Assert the minimum key length is the HMAC output size rather than an arbitrary number.

    Returns
    -------
    None
        The assertion is the result.
    """
    assert layouts._MASK_HMAC_KEY_MIN_BYTES == _HMAC_OUTPUT_BYTES == 32


def test_an_unset_key_falls_back_to_strong_process_material(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert an unconfigured run uses the process key and that the key clears the floor.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to remove the masking-key variable for the duration of the test.

    Returns
    -------
    None
        The assertion is the result.
    """
    monkeypatch.delenv(layouts.ENV_MASK_HMAC_KEY, raising=False)
    assert layouts._mask_hmac_key() == layouts._PROCESS_MASK_KEY
    assert len(layouts._PROCESS_MASK_KEY) >= layouts._MASK_HMAC_KEY_MIN_BYTES
    assert len(set(layouts._PROCESS_MASK_KEY)) > 1

    # WHY (Assumptions): empty and whitespace-only are asserted to take the same path as
    #   unset, because a deployment that references the variable conditionally renders it empty
    #   and the fallback is the safe outcome there. The case is stated so that behaviour is a
    #   decision on record rather than something a later reader tightens without noticing that
    #   tightening it refuses a correct deployment.
    for blank in ("", "   ", "\n"):
        monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, blank)
        assert layouts._mask_hmac_key() == layouts._PROCESS_MASK_KEY


@pytest.mark.parametrize(
    ("supplied", "reason"),
    [
        ("hunter2!", "a passphrase carrying characters outside the base64 alphabet"),
        ("password", "a passphrase that is valid base64 but decodes to six bytes"),
        (base64.b64encode(bytes(range(16))).decode(), "sixteen bytes, half the floor"),
        (base64.b64encode(bytes(range(31))).decode(), "thirty-one bytes, one short of the floor"),
        ("-__--__--__--__--__--__--__--__--__--__--__-", "the URL-safe alphabet"),
        (base64.b64encode(bytes(32)).decode(), "thirty-two zero bytes"),
        (base64.b64encode(b"\xff" * 48).decode(), "forty-eight copies of one byte"),
    ],
)
def test_weak_or_malformed_key_material_is_refused(
    supplied: str,
    reason: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse key material that is malformed, below the floor, or a single repeated byte.

    Parameters
    ----------
    supplied : str
        The value configured for the masking-key variable.
    reason : str
        Prose naming why the value is unacceptable, carried into the failure message so a
        failing case identifies itself.
    monkeypatch : pytest.MonkeyPatch
        Used to configure the masking-key variable for the duration of the test.

    Returns
    -------
    None
        The refusal is the result.
    """
    monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, supplied)
    with pytest.raises(layouts.LayoutError) as refusal:
        layouts._mask_hmac_key()
    message = str(refusal.value)
    assert layouts.ENV_MASK_HMAC_KEY in message, f"the refusal must name the variable ({reason})"
    # WHY (Assumptions): the refusal is required NOT to echo the value. A message naming the
    #   rejected key would put candidate key material into whatever log captured the failure,
    #   which is a worse disclosure than the weak key it was refusing.
    assert supplied not in message, f"the refusal must not echo the value ({reason})"
    # WHY (Assumptions): the refusal must also name the remedy, because this abort reaches an
    #   operator through a command that has stopped working and the generation command is the
    #   only thing that makes it actionable.
    assert "secrets.token_bytes" in message


def test_a_non_canonical_encoding_of_conforming_material_is_refused(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse a base64 spelling whose unused trailing bits are non-zero.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to configure the masking-key variable for the duration of the test.

    Returns
    -------
    None
        The refusal is the result.
    """
    # WHY (Assumptions): the variant is built by flipping an UNUSED bit of the final data
    #   character, so it decodes to the identical thirty-two bytes and clears every other check.
    #   That is what isolates the canonicality rule: without this case the rule could be deleted
    #   and every remaining case would still pass, because the other refusals are reached first.
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    material = bytes(range(1, 33))
    canonical = base64.b64encode(material).decode()
    variant = canonical[:-2] + alphabet[alphabet.index(canonical[-2]) ^ 1] + canonical[-1]
    assert base64.b64decode(variant, validate=True) == material
    assert variant != canonical

    monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, variant)
    with pytest.raises(layouts.LayoutError) as refusal:
        layouts._mask_hmac_key()
    assert "CANONICAL" in str(refusal.value)

    monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, canonical)
    assert layouts._mask_hmac_key() == material


@pytest.mark.parametrize("length", [32, 33, 48, 64])
def test_conforming_key_material_is_accepted_at_or_above_the_floor(
    length: int,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Accept canonical base64 of at least the floor, at several lengths.

    Parameters
    ----------
    length : int
        The number of random bytes the configured value encodes.
    monkeypatch : pytest.MonkeyPatch
        Used to configure the masking-key variable for the duration of the test.

    Returns
    -------
    None
        The assertion is the result.
    """
    material = secrets.token_bytes(length)
    monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, base64.b64encode(material).decode())
    assert layouts._mask_hmac_key() == material

    # WHY (Trade-offs): a trailing newline is asserted to be tolerated because a secret store
    #   and a shell here-document both add one, and refusing a correct key over a transport
    #   artefact would push operators toward stripping it themselves -- or toward a shorter key
    #   that avoids the problem.
    monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, f"\n {base64.b64encode(material).decode()} \n")
    assert layouts._mask_hmac_key() == material


def test_masking_refuses_to_run_at_all_under_weak_key_material(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert the enforcement reaches the masking path and is not merely a helper.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to configure the masking-key variable for the duration of the test.

    Returns
    -------
    None
        The refusal is the result.
    """
    # WHY (Assumptions): this is asserted through mask_record rather than through the key
    #   resolver, because a resolver nobody consulted would enforce nothing. It is the only case
    #   in this file that proves the two are connected.
    layout = layouts.CARD_LAYOUT
    raw = _sentinel_record(layout)
    monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, "not base64 at all!")
    with pytest.raises(layouts.LayoutError):
        layouts.mask_record(raw, layout)

    monkeypatch.setenv(
        layouts.ENV_MASK_HMAC_KEY, base64.b64encode(secrets.token_bytes(32)).decode()
    )
    assert len(layouts.mask_record(raw, layout)) == layout.reclen


def test_a_tag_is_stable_under_one_key_and_unrelated_across_keys(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Assert the tag is a function of the key, so replacing the key replaces every tag.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to configure the masking-key variable for the duration of the test.

    Returns
    -------
    None
        The assertion is the result.
    """
    # WHY (Assumptions): key dependence is asserted because it is what makes the strength
    #   floor matter. If the tag did not depend on the key, refusing weak material would be
    #   theatre -- so this case is the one that gives every refusal above its purpose.
    layout = layouts.CUSTOMER_LAYOUT
    field = layout.field("CUST-GOVT-ISSUED-ID")
    raw = _sentinel_record(layout)

    first = base64.b64encode(secrets.token_bytes(32)).decode()
    second = base64.b64encode(secrets.token_bytes(32)).decode()
    monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, first)
    once = layouts.mask_record(raw, layout)[field.start : field.end]
    again = layouts.mask_record(raw, layout)[field.start : field.end]
    monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, second)
    other = layouts.mask_record(raw, layout)[field.start : field.end]

    assert once == again, "one key must render one value identically"
    assert once != other, "two keys must render one value differently"
