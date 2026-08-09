"""Prove the protected-column envelopes are the ones the owning Java services parse.

Purpose
-------
Establish the one property that cannot be observed from inside this package: that the bytes this
module writes into ``account.customers.ssn_encrypted``,
``account.customers.govt_issued_id_encrypted`` and ``card.cards.cvv_encrypted`` are readable by
the services that own those columns. Nothing in a migration reads them back, and neither service
is running when the load happens, so a framing error here produces a load that succeeds, reports
success, passes every row-count and money-parity check, and leaves three columns permanently
unreadable -- discovered the first time somebody views a card.

Alternatives Considered:
    Three ways to establish that property were evaluated. (1) Running the Java class and asking
    it to decipher what this module produced: it would be the strongest evidence and needs a JVM,
    a key-management service and a live key, none of which this suite has. (2) Asserting the
    framing against literal offsets written here: it proves the framing is stable but not that it
    is the RIGHT framing, because the literals would have been copied from the Java once and then
    never compared again. (3) Reading the two Java sources from disk and asserting this module's
    constants against the constants declared there -- adopted, because it is the only option that
    fails when either side moves. The repository already applies exactly this discipline across
    trees for its runtime configuration contract.

Assumptions:
    The Java sources are located relative to this file rather than through any build output, so
    the assertions read the same text a reviewer edits. That is deliberate: a compiled class
    could be stale, and a stale class agreeing with this module proves nothing about the code
    that will run.

Trade-offs:
    The parity assertions compare DECLARED CONSTANTS and the byte layout each side assembles from
    them; they do not parse the Java. A Java author could reorder the parts of the envelope
    without changing any constant and these tests would still pass. What they do catch is every
    failure that has actually happened in practice on a contract like this -- a changed marker, a
    changed version byte, a changed vector width, a changed tag length, a changed encryption
    context, and an endianness assumed rather than stated -- and they catch it in the tree that
    would otherwise write unreadable data.
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from pathlib import Path
from typing import Final

import pytest

from carddemo_migration.loaders.protected_columns import (
    CARD_VERIFICATION_VALUE_DIGITS,
    CUSTOMER_IDENTIFIER_COLUMNS,
    CardVerificationValueCipher,
    CustomerIdentifierCipher,
    DataKey,
    KmsDataKeySource,
    ProtectedColumnError,
)

# Assumptions: the two Java sources are the authority for the framings this module reproduces,
#   and they are named by path rather than searched for, so a file MOVED rather than edited fails
#   these tests loudly instead of quietly reducing them to nothing.
_SERVICES_ROOT: Final[Path] = Path(__file__).resolve().parents[2] / "services"
_CUSTOMER_JAVA: Final[Path] = (
    _SERVICES_ROOT
    / "account-service/src/main/java/com/carddemo/account/config"
    / "CustomerIdentifierProtectionConfig.java"
)
_CARD_CIPHER_JAVA: Final[Path] = (
    _SERVICES_ROOT
    / "card-service/src/main/java/com/carddemo/card/service/CardVerificationValueCipher.java"
)
_CARD_ENVELOPE_JAVA: Final[Path] = (
    _SERVICES_ROOT / "card-service/src/main/java/com/carddemo/card/domain/EncryptedCvv.java"
)

_WRAPPED_KEY: Final[bytes] = b"SYNTHETIC-WRAPPED-KEY"


class _FixedKeys:
    """Data-key source answering with fixed material and recording every request.

    Purpose
    -------
    Let the framings be asserted byte by byte with no key-management service, and make the
    encryption context each envelope binds observable -- which is the half of the contract that
    decides whether the owning service can unwrap the data key at all.
    """

    def __init__(self) -> None:
        """Start with no recorded requests.

        Returns
        -------
        None
            Initialises the request log.

        Raises
        ------
        None
        """
        self.requests: list[tuple[str, dict[str, str]]] = []

    def data_key(self, *, key_id: str, encryption_context: Mapping[str, str]) -> DataKey:
        """Answer with fixed key material, recording the key and context asked for.

        Parameters
        ----------
        key_id : str
            The key the caller named.
        encryption_context : Mapping[str, str]
            The context the caller bound the key to.

        Returns
        -------
        DataKey
            A fixed 32-byte key and a fixed wrapped form.

        Raises
        ------
        None
        """
        self.requests.append((key_id, dict(encryption_context)))
        return DataKey(plaintext=bytes(range(32)), wrapped=_WRAPPED_KEY)


def _java_int(source: Path, name: str) -> int:
    """Read one declared integer constant out of a Java source file.

    Purpose
    -------
    Let a parity assertion state the Java's own number rather than a copy of it, so a change on
    either side fails rather than diverging silently.

    Parameters
    ----------
    source : Path
        The Java file to read.
    name : str
        The constant's identifier.

    Returns
    -------
    int
        The declared value.

    Raises
    ------
    AssertionError
        If the file does not declare the constant, which means it was renamed or removed and this
        assertion is no longer comparing what it claims to.
    """
    matched = re.search(
        rf"\b(?:int|byte)\s+{re.escape(name)}\s*=\s*(\d+)\s*;", source.read_text(encoding="utf-8")
    )
    assert matched, f"{source.name} declares no integer constant {name}"
    return int(matched.group(1))


def _java_string(source: Path, name: str) -> str:
    """Read one declared string constant out of a Java source file.

    Parameters
    ----------
    source : Path
        The Java file to read.
    name : str
        The constant's identifier.

    Returns
    -------
    str
        The declared value, without its quotes.

    Raises
    ------
    AssertionError
        If the file does not declare the constant.
    """
    matched = re.search(
        rf"\bString\s+{re.escape(name)}\s*=\s*\"([^\"]*)\"\s*;",
        source.read_text(encoding="utf-8"),
    )
    assert matched, f"{source.name} declares no string constant {name}"
    return matched.group(1)


def test_the_customer_framing_matches_the_constants_the_account_service_declares() -> None:
    """Assemble a customer envelope whose every part matches what the Java class parses.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the vector width, tag length, encryption context or layout differs from the Java's.
    """
    keys = _FixedKeys()
    envelope = CustomerIdentifierCipher(key_id="alias/synthetic", keys=keys).seal(
        "020973888", "ssn_encrypted"
    )
    vector_length = _java_int(_CUSTOMER_JAVA, "INITIALISATION_VECTOR_LENGTH")
    tag_bits = _java_int(_CUSTOMER_JAVA, "TAG_LENGTH_BITS")
    # WHY : the layout is asserted from the JAVA's own numbers, offset by offset. The customer
    #   framing begins with its two-byte length prefix at offset ZERO -- it carries no marker and
    #   no version byte, unlike the card framing -- so a layout borrowed from the card envelope
    #   would shift every part by five bytes and store values nothing can parse.
    assert envelope[:2] == len(_WRAPPED_KEY).to_bytes(2, "big")
    assert envelope[2 : 2 + len(_WRAPPED_KEY)] == _WRAPPED_KEY
    assert len(envelope) == 2 + len(_WRAPPED_KEY) + vector_length + 9 + tag_bits // 8
    # WHY : the encryption context is the other half of the contract, and it is checked against
    #   the Java's declared strings rather than against literals. KMS refuses to unwrap a data key
    #   under a different context, so a context differing by one character makes every identifier
    #   written under it permanently unreadable -- and nothing in a migration would notice.
    purpose_key = _java_string(_CUSTOMER_JAVA, "CONTEXT_PURPOSE_KEY")
    purpose_value = _java_string(_CUSTOMER_JAVA, "CONTEXT_PURPOSE_VALUE")
    column_key = _java_string(_CUSTOMER_JAVA, "CONTEXT_COLUMN_KEY")
    assert keys.requests == [
        ("alias/synthetic", {purpose_key: purpose_value, column_key: "ssn_encrypted"})
    ]


def test_the_card_framing_matches_the_constants_the_card_service_declares() -> None:
    """Assemble a card envelope carrying the marker, version and layout ``EncryptedCvv`` parses.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the marker, version byte, offsets, minimum length or encryption context differ from
        the Java's.
    """
    keys = _FixedKeys()
    envelope = CardVerificationValueCipher(key_id="alias/synthetic", keys=keys).seal("123")
    magic_length = _java_int(_CARD_ENVELOPE_JAVA, "MAGIC_LENGTH")
    version = _java_int(_CARD_ENVELOPE_JAVA, "FORMAT_VERSION")
    vector_length = _java_int(_CARD_ENVELOPE_JAVA, "INITIALISATION_VECTOR_LENGTH")
    minimum = _java_int(_CARD_ENVELOPE_JAVA, "MIN_CIPHERTEXT_LENGTH")
    tag_bits = _java_int(_CARD_CIPHER_JAVA, "TAG_LENGTH_BITS")
    # WHY : `hasEnvelopeShape` checks the marker and the version byte BEFORE parsing anything
    #   else, and rejects on either -- so these four bytes and this one byte are what decide
    #   whether the card service will even attempt to read a stored value.
    assert envelope[:magic_length] == b"CDCV"
    assert envelope[magic_length] == version
    key_length_offset = magic_length + 1
    assert envelope[key_length_offset : key_length_offset + 2] == len(_WRAPPED_KEY).to_bytes(
        2, "big"
    )
    key_offset = key_length_offset + 2
    assert envelope[key_offset : key_offset + len(_WRAPPED_KEY)] == _WRAPPED_KEY
    ciphertext_length = CARD_VERIFICATION_VALUE_DIGITS + tag_bits // 8
    assert ciphertext_length >= minimum
    assert len(envelope) == key_offset + len(_WRAPPED_KEY) + vector_length + ciphertext_length
    # WHY : this context is PURPOSE ONLY -- no column key -- which is where the two framings
    #   differ beyond their headers. Adding a column key would bind the data key to a context the
    #   card service never presents on decrypt, so KMS would refuse to unwrap it.
    purpose_key = _java_string(_CARD_CIPHER_JAVA, "CONTEXT_PURPOSE_KEY")
    purpose_value = _java_string(_CARD_CIPHER_JAVA, "CONTEXT_PURPOSE_VALUE")
    assert keys.requests[0][1] == {purpose_key: purpose_value}


def test_the_declared_verification_value_width_matches_the_card_service() -> None:
    """Require the three-digit verification-value width to be the Java's own number.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the two widths differ.
    """
    # WHY : the card service re-checks the width on its DECIPHER path, so a value of another width
    #   would encipher here successfully and be refused there -- weeks later, against a row whose
    #   provenance is no longer obvious. Agreeing on the number is what keeps the refusal at the
    #   load boundary.
    assert CARD_VERIFICATION_VALUE_DIGITS == _java_int(
        _CARD_CIPHER_JAVA, "VERIFICATION_VALUE_DIGITS"
    )


def test_neither_framing_binds_the_encryption_context_into_the_local_cipher() -> None:
    """Confirm neither Java class calls ``updateAAD``, which is why this module binds none.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either Java class binds additional authenticated data, which would mean this module
        must bind it too.
    """
    # WHY : this is the single easiest thing in the whole framing to get wrong, and it is asserted
    #   from the Java rather than merely commented. The encryption context travels to KMS on the
    #   data-key call and binds the WRAPPED KEY; the local GCM cipher is constructed with a key
    #   and a vector and `doFinal` is called directly. Binding the canonicalised context into the
    #   local cipher here instead would produce envelopes that frame correctly, store
    #   successfully, and fail authentication the first time either service read them.
    for source in (_CUSTOMER_JAVA, _CARD_CIPHER_JAVA):
        text = source.read_text(encoding="utf-8")
        assert "updateAAD" not in text, (
            f"{source.name} now binds additional authenticated data; the Python cipher must bind"
            " the same data or every envelope it writes becomes unreadable"
        )


def test_two_envelopes_over_one_value_differ() -> None:
    """Produce different bytes for the same value under the same key, proving a per-value vector.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If two envelopes over one value are identical.
    """
    # WHY : a repeated (key, vector) pair is the one mistake AES-GCM does not survive, and a
    #   deterministic envelope would additionally turn the column into a searchable index of
    #   national identifiers -- equal ciphertext would reveal equal plaintext without decrypting
    #   anything. The key material here is FIXED, so this assertion isolates the vector.
    cipher = CustomerIdentifierCipher(key_id="alias/synthetic", keys=_FixedKeys())
    first = cipher.seal("020973888", "ssn_encrypted")
    second = cipher.seal("020973888", "ssn_encrypted")
    assert first != second
    assert len(first) == len(second)


@pytest.mark.parametrize("column", sorted(CUSTOMER_IDENTIFIER_COLUMNS))
def test_each_protected_customer_column_binds_its_own_name(column: str) -> None:
    """Bind each customer identifier envelope to the column it is stored in.

    Parameters
    ----------
    column : str
        One of the two customer columns this framing protects.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the column name does not reach the encryption context.
    """
    # WHY : the binding is what stops an envelope written for one column being read from the
    #   other. Both hold identifiers about the same customer, so without the binding a value moved
    #   between them would decipher successfully into the wrong field.
    keys = _FixedKeys()
    CustomerIdentifierCipher(key_id="alias/synthetic", keys=keys).seal("000000001", column)
    assert keys.requests[0][1]["carddemo:column"] == column


def test_an_empty_value_is_refused_rather_than_enciphered() -> None:
    """Refuse an empty identifier, quoting neither it nor its length.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an empty value is enciphered.
    """
    # WHY : the nullable column's absent state is a NULL. Enciphering an empty string would store
    #   a present envelope that deciphers to zero characters, which no reader can distinguish from
    #   a corrupted one -- so the absence has to be expressed by omitting the value.
    cipher = CustomerIdentifierCipher(key_id="alias/synthetic", keys=_FixedKeys())
    with pytest.raises(ProtectedColumnError) as refused:
        cipher.seal("", "ssn_encrypted")
    assert "ssn_encrypted" in str(refused.value)


def test_a_data_key_pair_that_cannot_be_framed_is_refused() -> None:
    """Refuse an incomplete key pair and one whose wrapped half exceeds the length prefix.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either pair is accepted.
    """
    # WHY : both cases are silent otherwise. An empty wrapped key frames a zero-length prefix, so
    #   the vector begins where the key was expected; a wrapped key above 65535 bytes wraps the
    #   two-byte prefix, so the envelope parses as a shorter key followed by a longer vector. Each
    #   produces bytes that store successfully and report only an authentication failure later.
    with pytest.raises(ProtectedColumnError):
        DataKey(plaintext=b"", wrapped=_WRAPPED_KEY)
    with pytest.raises(ProtectedColumnError):
        DataKey(plaintext=bytes(range(32)), wrapped=b"")
    with pytest.raises(ProtectedColumnError) as refused:
        DataKey(plaintext=bytes(range(32)), wrapped=b"x" * 0x10000)
    assert "unreadable" in str(refused.value)


def test_a_refused_key_service_call_names_no_value_and_no_service_message() -> None:
    """Wrap a key-service refusal without carrying its message into the diagnostic.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the service message reaches the wrapper, or the failure escapes unwrapped.
    """

    class _Refusing:
        """Key-management client that refuses every request with a quoting message.

        Purpose
        -------
        Stand in for a service whose error message echoes the request it rejected, which is the
        case the wrapper's message discipline exists for.
        """

        def generate_data_key(self, **_: object) -> Mapping[str, object]:
            """Refuse the request with a message quoting an identifier.

            Returns
            -------
            Mapping[str, object]
                Never returns.

            Raises
            ------
            RuntimeError
                Always, carrying a message that quotes a value.
            """
            raise RuntimeError("AccessDenied while encrypting 020973888 for ssn_encrypted")

    source = KmsDataKeySource(client=_Refusing())
    with pytest.raises(ProtectedColumnError) as refused:
        source.data_key(key_id="alias/synthetic", encryption_context={"a": "b"})
    message = str(refused.value)
    # WHY : the TYPE is reported and the service message is not. A service message is free text
    #   that can echo the request it rejected, and the request here carries an identifier -- so
    #   the cause keeps the detail in a traceback rather than in a string an alarm copies.
    assert "RuntimeError" in message
    assert "020973888" not in message


def test_a_key_service_answering_without_both_halves_is_refused() -> None:
    """Refuse a key-service answer missing either the raw or the wrapped key.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a partial answer yields a key pair.
    """

    class _Partial:
        """Key-management client answering with only the wrapped half.

        Purpose
        -------
        Stand in for a stubbed or future client whose response omits a member, which must be
        reported as a missing capability rather than as an index error.
        """

        def generate_data_key(self, **_: object) -> Mapping[str, object]:
            """Answer with a wrapped key and no raw material.

            Returns
            -------
            Mapping[str, object]
                A response missing ``Plaintext``.

            Raises
            ------
            None
            """
            return {"CiphertextBlob": _WRAPPED_KEY}

    source = KmsDataKeySource(client=_Partial())
    with pytest.raises(ProtectedColumnError) as refused:
        source.data_key(key_id="alias/synthetic", encryption_context={})
    assert "wrapped data key" in str(refused.value)
