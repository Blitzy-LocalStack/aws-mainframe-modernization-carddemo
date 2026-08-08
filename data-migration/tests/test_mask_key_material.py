"""Pin the key material the redaction tag is derived with, and the floor it must clear.

Purpose
-------
``layouts.mask_field`` and ``layouts.mask_record`` render a sensitive field as a keyed HMAC tag
rather than as a blank span, so a masked diagnostic still shows WHICH field differs between two
records without disclosing either value. That property rests entirely on the key: an attacker
holding a masked diagnostic and a guess at a field's value can confirm the guess by recomputing
the digest, unless the key is unguessable. A weak key therefore returns the tag to the unkeyed
digest it was introduced to replace, while looking exactly as opaque as a strong one.

Refactoring Rationale: this module exists because the key was read from
``CARDDEMO_MASK_HMAC_KEY`` and used verbatim -- ``supplied.encode("utf-8")`` -- so any string an
operator happened to export became the key. Enforcement without a test is a comment, and the
enforcement is in one small function that every masked field passes through, so it is exactly the
kind of code a later edit removes as redundant. These cases are what make removing it fail.

Assumptions: the cases assert the RESOLVER and, once, the masking path that consults it. Asserting
only the resolver would leave a validated key nobody used; asserting only the masking path would
not distinguish which of the several refusals fired. The one connecting case is named for that job.

Trade-offs: the variable stays OPTIONAL and only its content is constrained, which is asserted
here as a decision on record rather than left implicit. A single command that prints one diagnostic
needs only within-run comparability, and the process-scoped random key gives it that without
obliging every invocation to carry a secret.
"""

from __future__ import annotations

import base64
import hashlib
import secrets

import pytest

from carddemo_migration.copybook import layouts

# Assumptions: the floor is expressed as the hash's OWN output size rather than as the literal
#   thirty-two, so the assertion below compares two independently derived values instead of a
#   constant against itself.
_HMAC_OUTPUT_BYTES = hashlib.sha256().digest_size

# Assumptions: the alphabet excludes every character a base64 or hexadecimal rendering can produce,
#   so a sentinel surviving into a tag is unambiguous evidence of a leak rather than a coincidence.
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
