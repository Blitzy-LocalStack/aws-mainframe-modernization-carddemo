"""Produce the ciphertext envelopes the two protected-column families store.

Purpose
-------
Encipher the three baseline values whose target columns are ``BYTEA`` rather than text --
``account.customers.ssn_encrypted``, ``account.customers.govt_issued_id_encrypted`` and
``card.cards.cvv_encrypted`` -- in EXACTLY the framing the owning Java service reads back.
Without this module the customer and card records have no load path at all, because a
``NOT NULL BYTEA`` column cannot be filled with plaintext and must not be filled with a
placeholder.

The two framings, and why there are two
---------------------------------------
Both are AES-256/GCM envelope encryption under one AWS KMS customer-managed key, with a
per-value data key. Both carry the SAME header shape -- a four-byte marker, a version byte and a
two-byte big-endian wrapped-key length -- and differ only in which marker they carry and in their
encryption context. Each is the exact byte layout one Java class parses, so a value written under
the wrong one is unreadable by the service that owns the column.

*Customer identifiers* -- written and read by
``com.carddemo.account.service.CustomerIdentifierCipher``::

    ["CDCI"][version byte 1][2-byte big-endian wrapped-key length][wrapped data key]
    [12-byte IV][ciphertext || 16-byte tag]

with encryption context ``{"carddemo:purpose": "customer-identifier", "carddemo:column": <column>}``
where ``<column>`` is the literal target column name, and plaintext taken as UTF-8 exactly as
given -- no trimming, no numeric conversion, so a leading zero is preserved.

*Card verification value* -- read by ``com.carddemo.card.domain.EncryptedCvv``::

    ["CDCV"][version byte 1][2-byte big-endian wrapped-key length][wrapped data key]
    [12-byte IV][ciphertext || 16-byte tag]

with encryption context ``{"carddemo:purpose": "card-cvv"}`` -- purpose only, no column key --
and plaintext taken as US-ASCII from exactly three digits.

⚠️ Refactoring Rationale:
    This module previously wrote the customer envelope with NO marker and NO version byte, so its
    wrapped-key length prefix sat at offset zero, and this docstring recorded that asymmetry as
    deliberate. It was not deliberate; it was a transcription of the WRONG Java writer. Two Java
    classes framed this one column at the time: a private nested implementation inside
    ``com.carddemo.account.config.CustomerIdentifierProtectionConfig`` wrote the unmarked layout,
    while the component-scanned ``CustomerIdentifierCipher`` -- which supersedes it under
    ``@ConditionalOnMissingBean`` in any context that scans the service package -- wrote
    ``["CDCI"][version]`` first. Whichever bean a context happened to register decided the format,
    and because the account service publishes no decipher path for these two columns nothing read
    the bytes back to notice. The account service now has exactly ONE writer -- the cipher -- and
    this module reproduces its framing, marker and version byte included.
Trade-offs:
    Any envelope written into ``account.customers`` before that alignment is five bytes short of
    what the account service parses, and a re-run of the loader does NOT repair it: no role this
    package uses holds ``DELETE`` or ``TRUNCATE``, so an already-populated table is refused rather
    than replaced -- see :mod:`carddemo_migration.loaders.aurora`. Repairing pre-alignment rows is
    an operator action on the cluster, and ``docs/runbooks/data-migration.md`` states it as a
    cutover-gate condition rather than leaving a reader to infer that a reload suffices. Accepting
    that cost, rather than teaching a reader here to tolerate both framings, is deliberate: a load
    that accepted the old layout would keep two formats alive in one column permanently, which is
    the state this change exists to end.
Assumptions:
    The two markers stay DISTINCT rather than being unified into one. A byte array recovered from
    ``card.cards.cvv_encrypted`` must not read as a customer identifier envelope and vice versa,
    because the encryption contexts differ and a cross-column read would otherwise get as far as
    a key-unwrap refusal instead of failing on the four bytes that say which framing it is.

Design decisions (WHY)
----------------------
Refactoring Rationale:
    ``loaders/aurora.py`` previously declared these two records unloadable and refused them by
    name, on the stated grounds that their columns hold "ciphertext produced by the owning
    service's own cipher, under a key this package has no access to and should not have". Two
    of those three clauses were wrong. The key is a KMS customer-managed key reached by alias,
    not a secret held by any process, so "no access" describes an IAM grant rather than a
    capability -- and the batch task that runs this ETL is exactly the principal such a grant
    is written for. And "should not have" left the migration with no way to populate five
    target tables, which is not a boundary but a gap: a migration that cannot load
    ``card.cards`` has not migrated the card master.
Alternatives Considered:
    Calling ``kms:Encrypt`` directly on each value, which would need no local cipher at all.
    Rejected because it produces a KMS ciphertext blob, and neither Java class can read one:
    both parse the framings above and derive their key from a wrapped data key carried INSIDE
    the value. A load using ``kms:Encrypt`` would succeed, write well-formed bytes, and leave
    every protected column permanently unreadable by the service that owns it -- discovered
    only when someone first tried to view a card.
Alternatives Considered:
    Having the ETL call an internal endpoint on the account and card services so that each
    service enciphers its own values. Rejected on two grounds. It makes a bulk data load
    depend on two running services and on network reachability from wherever the load runs,
    converting a data step into an availability problem; and it would need a write endpoint
    taking a cleartext national identifier, which is a worse surface than the one this module
    replaces. The framing is a published, versioned byte contract, so reproducing it is the
    lower-risk half of that trade.
Trade-offs:
    ``cryptography`` is now a runtime dependency of this distribution, and it is the only
    platform-tagged one. That cost is accepted rather than avoided: AES-GCM is not in the
    standard library, and the alternative -- implementing an authenticated cipher here -- is
    not a trade a financial migration is entitled to make. ``data-migration/requirements.txt``
    records the pin, its two transitive dependencies and the platform consequence.
Assumptions:
    A data key is generated PER VALUE rather than per run or per table, matching both Java
    classes. Reusing one data key across rows would halve the KMS call count and is refused for
    the reason GCM requires: safety rests on never repeating an (key, IV) pair, and a per-value
    key removes any need to reason about IV uniqueness across a multi-million-row load at all.
Assumptions:
    Nothing here decrypts. The card service publishes a decipher path and the account service
    deliberately publishes none, and this module matches that asymmetry by offering neither: a
    migration writes protected columns and never reads them back, so a decrypt member would be
    an unused capability whose only effect is to widen what a compromised ETL task can do.
Trade-offs:
    No message raised by this module names a value, a length, a column's contents, or a KMS
    response field. The inputs here are a national identifier, a government-issued identifier
    and a card verification value, so a diagnostic that quoted its argument would be the single
    worst disclosure this package could produce. The column NAME is named, because that is what
    an operator needs and it discloses nothing.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, Final, Protocol

__all__ = [
    "CARD_VERIFICATION_VALUE_DIGITS",
    "CARD_VERIFICATION_VALUE_PURPOSE",
    "CONTEXT_PURPOSE_KEY",
    "CUSTOMER_IDENTIFIER_COLUMNS",
    "CUSTOMER_IDENTIFIER_PURPOSE",
    "CardVerificationValueCipher",
    "CustomerIdentifierCipher",
    "DataKey",
    "DataKeySource",
    "KmsDataKeySource",
    "ProtectedColumnError",
]

# Assumptions: 256-bit AES, 12-byte initialisation vector and a 128-bit authentication tag,
#   each transcribed from the Java class it has to interoperate with rather than chosen here.
#   `DataKeySpec.AES_256` fixes the key length, `EncryptedCvv.INITIALISATION_VECTOR_LENGTH` and
#   `CustomerIdentifierCipher.INITIALISATION_VECTOR_LENGTH` both declare 12, and both classes pass
#   `TAG_LENGTH_BITS = 128` to their GCM parameter spec. A different value on this side would
#   produce bytes that parse and then fail authentication.
_KEY_SPEC: Final[str] = "AES_256"
_VECTOR_LENGTH: Final[int] = 12
_TAG_LENGTH_BITS: Final[int] = 128

# Assumptions: the two-byte big-endian length prefix both framings carry, and the largest
#   wrapped key it can describe. Both Java classes refuse a wrapped key above this bound rather
#   than truncating the prefix, and so does this module -- an envelope whose declared key length
#   wrapped around would be parsed as a shorter key followed by a longer vector, which decrypts
#   to nothing and reports only an authentication failure.
_LENGTH_PREFIX_BYTES: Final[int] = 2
_MAX_WRAPPED_KEY_LENGTH: Final[int] = 0xFFFF

# Assumptions: the encryption-context keys are the literal strings both Java classes use.
#   Encryption context is AUTHENTICATED additional data, so it is part of the ciphertext's
#   integrity check: a single character's difference here yields envelopes that decrypt nowhere.
# Refactoring Rationale: the purpose key and its two values are PUBLISHED, where all three were
#   private. They are not internal details: each environment root conditions the migration task's
#   `kms:GenerateDataKey*` grant on `kms:EncryptionContext:carddemo:purpose` matching these exact
#   values, so the strings are a contract between this module and the infrastructure. Held
#   privately they could be renamed here with the grant left behind, and a condition that no
#   longer matches fails exactly as a missing grant does -- an access denial on the first sealed
#   record -- but is harder to diagnose because the policy still looks present. Publishing them
#   lets that agreement be asserted rather than assumed; the column key stays private because no
#   policy conditions on it.
CONTEXT_PURPOSE_KEY: Final[str] = "carddemo:purpose"
_CONTEXT_COLUMN_KEY: Final[str] = "carddemo:column"
CUSTOMER_IDENTIFIER_PURPOSE: Final[str] = "customer-identifier"
CARD_VERIFICATION_VALUE_PURPOSE: Final[str] = "card-cvv"

# Assumptions: each family's marker and version byte, transcribed from the Java constant that
#   writes it -- `EncryptedCvv.MAGIC` / `EncryptedCvv.FORMAT_VERSION` for the card column and
#   `CustomerIdentifierCipher.ENVELOPE_MAGIC` / `CustomerIdentifierCipher.FORMAT_VERSION` for the
#   two customer columns. BOTH framings carry a marker and a version, and the markers differ so a
#   value recovered from one column cannot be parsed as an envelope of the other kind.
# WHY : ⚠️ Refactoring Rationale: only the card pair stood here, with a comment stating that the
#   customer framing deliberately carried neither and that adding a marker would shift its length
#   prefix by five bytes and make every value unreadable. That was transcribed from a Java writer
#   that has since been deleted -- see this module's docstring -- and the surviving writer,
#   `CustomerIdentifierCipher`, has always framed `["CDCI"][version]` first. The five-byte shift
#   the old comment warned about is what CORRECTS the framing rather than what breaks it.
# Assumptions: the version byte is written even though nothing reads it yet, for the reason the
#   Java constant gives: a format that does not record its own version cannot gain a second one
#   without a reader having to guess which it is holding.
_CUSTOMER_ENVELOPE_MAGIC: Final[bytes] = b"CDCI"
_CUSTOMER_ENVELOPE_VERSION: Final[bytes] = b"\x01"
_CARD_ENVELOPE_MAGIC: Final[bytes] = b"CDCV"
_CARD_ENVELOPE_VERSION: Final[bytes] = b"\x01"

# Assumptions: exactly the two customer columns declared BYTEA, named here so a caller can be
#   checked against the set rather than trusted to spell a column that becomes authenticated
#   additional data. `account.customers.ssn_encrypted` is NOT NULL and
#   `account.customers.govt_issued_id_encrypted` is nullable, but both are protected identically
#   -- nullability decides whether a value is present, not how a present value is protected.
CUSTOMER_IDENTIFIER_COLUMNS: Final[frozenset[str]] = frozenset(
    {"ssn_encrypted", "govt_issued_id_encrypted"}
)

# Assumptions: three digits, from `CARD-CVV-CD PIC 9(03)` at app/cpy/CVACT02Y.cpy L7 and from
#   `CardVerificationValueCipher.VERIFICATION_VALUE_DIGITS`. The width is enforced rather than
#   assumed because the card service's decipher path re-checks it, so a value of another width
#   would encipher here and be refused there.
CARD_VERIFICATION_VALUE_DIGITS: Final[int] = 3


class ProtectedColumnError(RuntimeError):
    """Raised when a protected column's value cannot be enciphered.

    Purpose
    -------
    Separate a cipher failure from the bulk-load failure that contains it, so an operator can
    tell a key-management grant that was refused from a database that rejected a row. It
    subclasses :class:`RuntimeError` for the same reason
    :class:`carddemo_migration.loaders.aurora.AuroraLoadError` does, so a caller guarding the
    load path's base type still catches this.
    """


@dataclass(frozen=True)
class DataKey:
    """One single-use data key, in both the form that enciphers and the form that is stored.

    Parameters
    ----------
    plaintext : bytes
        The raw key material, used once and never persisted anywhere.
    wrapped : bytes
        The same key enciphered under the customer-managed key, which travels inside the
        envelope and is what lets the owning service decipher without holding key material.

    Raises
    ------
    ProtectedColumnError
        If either half is empty, or the wrapped half is longer than the two-byte length prefix
        both framings use can describe.
    """

    plaintext: bytes
    wrapped: bytes

    def __post_init__(self) -> None:
        """Refuse a key pair that cannot produce a readable envelope.

        Parameters
        ----------
        None

        Returns
        -------
        None
            Nothing. Returning normally IS the acceptance of the pair; the only other outcome is
            the refusal below, which is why no caller reads a value from this.

        Raises
        ------
        ProtectedColumnError
            If either half is empty, or the wrapped half exceeds the length prefix's range.
        """
        # Trade-offs: an empty half is refused here rather than at the point it would fail.
        #   An empty plaintext key would be accepted by the cipher construction on some backends
        #   and produce an envelope nothing can read; an empty wrapped key would frame a
        #   zero-length prefix and make the vector start where the key was expected. Both are
        #   silent, so both are refused at the boundary where the pair is assembled.
        if not self.plaintext or not self.wrapped:
            raise ProtectedColumnError(
                "a data key must carry both raw material and its wrapped form; an incomplete"
                " pair produces an envelope no service can decipher"
            )
        if len(self.wrapped) > _MAX_WRAPPED_KEY_LENGTH:
            raise ProtectedColumnError(
                f"the wrapped data key is {len(self.wrapped)} bytes, which the"
                f" {_LENGTH_PREFIX_BYTES}-byte length prefix both envelope framings use cannot"
                " describe; the envelope would be unreadable rather than merely large"
            )


class DataKeySource(Protocol):
    """The key-generation surface this module uses, narrowed to the one call it makes.

    Purpose
    -------
    Let a test supply a deterministic double without reaching a key-management service, and make
    this module's demand on that service explicit: one data key per value, bound to an
    encryption context. It is the same Protocol-over-a-narrow-surface discipline
    :mod:`carddemo_migration.loaders.aurora` applies to the database driver.
    """

    def data_key(self, *, key_id: str, encryption_context: Mapping[str, str]) -> DataKey:
        """Generate one single-use data key bound to an encryption context.

        Parameters
        ----------
        key_id : str
            The customer-managed key, named by alias or by identifier.
        encryption_context : Mapping[str, str]
            The authenticated additional data the key is bound to.

        Returns
        -------
        DataKey
            The raw material and its wrapped form.
        """
        ...  # pragma: no cover - Protocol declaration


@dataclass(frozen=True)
class KmsDataKeySource:
    """Generate data keys from AWS KMS, through the package's single client factory.

    Parameters
    ----------
    client : Any
        A key-management client exposing ``generate_data_key``. Typed loosely because the SDK
        generates its client classes at run time from service models, so no importable static
        type exists to annotate -- the same reason
        :func:`carddemo_migration.config.aws_client` returns ``Any``.

    Raises
    ------
    ProtectedColumnError
        Never at construction. Failures surface from :meth:`data_key`.
    """

    client: Any

    @classmethod
    def from_environment(cls) -> KmsDataKeySource:
        """Build a source over the package's own configured key-management client.

        Purpose
        -------
        Keep client construction in one place for the whole distribution, so the region
        requirement, the timeout and retry policy and the caching decision are made once.

        Returns
        -------
        KmsDataKeySource
            A source over the shared client.

        Raises
        ------
        carddemo_migration.config.ConfigurationError
            If the SDK is absent, the environment names no region, or the client cannot be
            constructed. Raised by the shared factory.
        """
        # Alternatives Considered: the factory is imported here rather than at module scope,
        #   matching how `config` imports the SDK. A module-scope import would make importing this
        #   module read the environment, and the package's own contract is that importing it does
        #   not -- the copybook subpackage's standard-library-only guarantee depends on that
        #   discipline holding one level up as well.
        from carddemo_migration.config import aws_client

        return cls(client=aws_client("kms"))

    def data_key(self, *, key_id: str, encryption_context: Mapping[str, str]) -> DataKey:
        """Ask the key-management service for one data key bound to an encryption context.

        Parameters
        ----------
        key_id : str
            The customer-managed key, named by alias or identifier. The alias is preferred by
            the infrastructure that publishes it, because an alias survives replacement of the
            key behind it.
        encryption_context : Mapping[str, str]
            The authenticated additional data to bind the key to.

        Returns
        -------
        DataKey
            The raw material and its wrapped form.

        Raises
        ------
        ProtectedColumnError
            If the service refuses the call, or answers without both halves of the pair.
        """
        try:
            response = self.client.generate_data_key(
                KeyId=key_id,
                KeySpec=_KEY_SPEC,
                EncryptionContext=dict(encryption_context),
            )
        except Exception as exc:
            # Trade-offs: the exception TYPE is reported and the service message is not. A
            #   service message is free text that can echo the request it rejected, and the
            #   request here carries the encryption context including a column name; chaining with
            #   `from exc` keeps the full message and the request identifier in the traceback,
            #   which is where that detail belongs rather than in a string an alarm copies.
            #   The guard is deliberately broad because the SDK's exception classes are generated
            #   at run time, so naming them would require importing the SDK at module scope --
            #   exactly what `from_environment` avoids.
            raise ProtectedColumnError(
                "the key-management service refused to generate a data key"
                f" ({type(exc).__name__}); no protected column value was produced"
            ) from exc

        plaintext = response.get("Plaintext") if isinstance(response, dict) else None
        wrapped = response.get("CiphertextBlob") if isinstance(response, dict) else None
        if not isinstance(plaintext, bytes | bytearray) or not isinstance(
            wrapped, bytes | bytearray
        ):
            # Assumptions: the response is navigated defensively for the same reason
            #   `config._ssm_parameter` navigates its own -- a stubbed or future client could answer
            #   without a member, and an index error here would report a missing key rather than the
            #   missing capability it means.
            raise ProtectedColumnError(
                "the key-management service answered without both a raw and a wrapped data key,"
                " so no envelope could be framed"
            )
        return DataKey(plaintext=bytes(plaintext), wrapped=bytes(wrapped))


def _encipher(key: DataKey, plaintext: bytes) -> tuple[bytes, bytes]:
    """Encipher one value under a single-use key, returning its vector and its ciphertext.

    Purpose
    -------
    Hold the one call to the authenticated cipher, so both framings share exactly one statement
    of the algorithm, the vector source and the vector width.

    Parameters
    ----------
    key : DataKey
        The single-use key. Only its raw half is used here.
    plaintext : bytes
        The value to encipher, already encoded by the caller that knows its character set.

    Returns
    -------
    tuple[bytes, bytes]
        The initialisation vector, and the ciphertext with its authentication tag appended.

    Raises
    ------
    ProtectedColumnError
        If the cipher library is not installed, or the platform refuses the transformation.
    """
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError as exc:  # pragma: no cover - exercised only on an incomplete install
        raise ProtectedColumnError(
            "the cryptography distribution is required to protect a column but is not"
            " installed; install data-migration/requirements.txt"
        ) from exc

    # Assumptions: the vector comes from `os.urandom`, which is the operating system's
    #   cryptographic source, and NOT from `random`. The module-level `random` is a Mersenne
    #   twister whose state is recoverable from its output, so a vector drawn from it would be
    #   predictable -- and a predictable vector under GCM is the one mistake this construction
    #   does not survive. Both Java classes draw theirs from `SecureRandom` for the same reason.
    vector = os.urandom(_VECTOR_LENGTH)

    # WHY : Assumptions: NO additional authenticated data is bound into the local cipher, and
    #   this is the single easiest thing in the whole module to get wrong. The encryption context
    #   travels to KMS on the data-key call and binds the WRAPPED KEY, not the ciphertext: both
    #   Java classes construct their `Cipher` with a key and a `GCMParameterSpec` and call
    #   `doFinal` directly -- neither calls `updateAAD`, verified in both files -- and the card
    #   service's decipher path likewise passes the context to `kms.decrypt` and then deciphers
    #   with no additional data. Binding the canonicalised context here instead would produce an
    #   envelope that frames correctly, stores successfully, and then fails authentication the
    #   first time either service read it -- a failure that surfaces days later against a row
    #   whose provenance is gone. The context still does its job: KMS refuses to unwrap the data
    #   key under a different context, so an envelope moved between the two purposes, or between
    #   the two customer columns, is unreadable exactly as intended.
    try:
        sealed = AESGCM(bytes(key.plaintext)).encrypt(vector, plaintext, None)
    except Exception as exc:
        # Trade-offs: the wrapper names the transformation and the failure type and NOTHING
        #   about the value. A cipher library's own message can quote the argument it rejected,
        #   and the argument here is a national identifier or a verification value, so the cause
        #   carries the rest to a traceback instead of into a message a handler might log.
        raise ProtectedColumnError(
            "the platform refused the AES-GCM transformation while protecting a column"
            f" ({type(exc).__name__})"
        ) from exc

    # Assumptions: the tag width is asserted rather than trusted. This library appends a
    #   128-bit tag, which is what both Java classes' GCM parameter spec expects, and a backend
    #   appending a shorter one would produce an envelope whose ciphertext parses and whose
    #   authentication fails -- reported by the service, days later, as unreadable data.
    if len(sealed) < len(plaintext) + _TAG_LENGTH_BITS // 8:
        raise ProtectedColumnError(
            "the authenticated cipher produced a shorter tag than the"
            f" {_TAG_LENGTH_BITS}-bit tag both consuming services expect, so the envelope would"
            " fail authentication rather than decipher"
        )
    return vector, sealed


def _length_prefix(wrapped: bytes) -> bytes:
    """Render a wrapped data key's length as the two-byte big-endian prefix both framings carry.

    Parameters
    ----------
    wrapped : bytes
        The wrapped data key, already bounded by :class:`DataKey`.

    Returns
    -------
    bytes
        Exactly two bytes, most significant first.

    Raises
    ------
    None
    """
    # Assumptions: big-endian, because both Java classes write the high byte first -- the
    #   `(byte) (encipheredDataKey.length >>> Byte.SIZE)` store in `CustomerIdentifierCipher.frame`
    #   and the same shift in `EncryptedCvv.wrap`. `int.to_bytes` states the order explicitly rather
    #   than inheriting a platform default, which is the difference between a portable framing and
    #   one that works on the machine it was written on.
    # Assumptions: the prefix is the same two bytes in both framings and sits at the same offset in
    #   both -- five, after a four-byte marker and a version byte -- so this helper is shared by
    #   both ciphers and neither owns it.
    return len(wrapped).to_bytes(_LENGTH_PREFIX_BYTES, "big")


@dataclass(frozen=True)
class CustomerIdentifierCipher:
    """Frame a customer identifier the way ``account-service`` reads it back.

    Parameters
    ----------
    key_id : str
        The customer-managed key, published by infrastructure as the account workload's
        ``CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID`` parameter.
    keys : DataKeySource
        Where single-use data keys come from.

    Raises
    ------
    ProtectedColumnError
        Never at construction. Failures surface from :meth:`seal`.
    """

    key_id: str
    keys: DataKeySource

    def seal(self, value: str, column: str) -> bytes:
        """Encipher one identifier for one named column.

        Purpose
        -------
        Produce the exact bytes ``account.customers`` stores, bound to the column it is stored
        in so that a value moved between the two protected columns fails authentication instead
        of decrypting into the wrong field.

        Parameters
        ----------
        value : str
            The identifier exactly as it is to be stored. Not trimmed and not converted to a
            number: ``CUST-SSN`` is nine display digits and a leading zero is part of the value,
            so any normalisation here would store an identifier the source does not contain.
        column : str
            The target column, which becomes part of the authenticated encryption context and
            must therefore be one of :data:`CUSTOMER_IDENTIFIER_COLUMNS`.

        Returns
        -------
        bytes
            The framed envelope: the four-byte ``CDCI`` marker, the version byte, a two-byte
            length, the wrapped key, the vector, then the ciphertext and its tag.

        Raises
        ------
        ProtectedColumnError
            If the value is empty, the column is not one this framing protects, the key service
            refuses, or the platform refuses the transformation.
        """
        # Trade-offs: an EMPTY value is refused rather than enciphered, matching the Java
        #   class, which refuses the same case. The nullable column's absent state is expressed by
        #   omitting the value -- a NULL -- and enciphering an empty string would instead store a
        #   present envelope that deciphers to nothing, which no reader can distinguish from a
        #   corrupted one. The COLUMN is named in the message and the value is not.
        if not value:
            raise ProtectedColumnError(
                f"column {column} was given an empty identifier to protect; an absent identifier"
                " is expressed by omitting the value, never by enciphering nothing"
            )
        # Assumptions: the column is checked against the declared set because it is
        #   AUTHENTICATED additional data rather than a label. A misspelling would produce an
        #   envelope that frames correctly, stores successfully and then fails its integrity check
        #   the first time the account service reads it -- so the spelling is verified here, where
        #   the mistake is still cheap.
        if column not in CUSTOMER_IDENTIFIER_COLUMNS:
            raise ProtectedColumnError(
                f"column {column} is not one this framing protects; the encryption context binds"
                f" the column name, so it must be one of"
                f" {', '.join(sorted(CUSTOMER_IDENTIFIER_COLUMNS))}"
            )

        context = MappingProxyType(
            {
                CONTEXT_PURPOSE_KEY: CUSTOMER_IDENTIFIER_PURPOSE,
                _CONTEXT_COLUMN_KEY: column,
            }
        )
        key = self.keys.data_key(key_id=self.key_id, encryption_context=context)
        # Assumptions: UTF-8, matching `clearText.getBytes(StandardCharsets.UTF_8)` on the
        #   Java side. Every identifier in this corpus is ASCII, so the two encodings agree on the
        #   shipped data -- but they would diverge on a non-ASCII value, and matching the reader
        #   rather than the corpus is what keeps that divergence from being introduced later.
        vector, sealed = _encipher(key, value.encode("utf-8"))
        # Assumptions: the marker and the version byte lead, matching `CustomerIdentifierCipher`
        #   `frame`, which copies `ENVELOPE_MAGIC` at offset zero, writes `FORMAT_VERSION` at
        #   offset four, and only then writes the high and low bytes of the wrapped-key length.
        #   The account service parses the marker and the version BEFORE anything else, so these
        #   five bytes are what decide whether it will attempt to read a stored value at all.
        # WHY : ⚠️ Refactoring Rationale: this expression previously began at `_length_prefix`,
        #   reproducing a second, marker-less Java writer that no longer exists. The two framings
        #   now agree byte for byte, and `data-migration/tests/test_protected_columns.py` asserts
        #   the marker, the version and every offset against the constants the surviving Java
        #   class declares, so the two cannot drift apart again without a red test.
        return (
            _CUSTOMER_ENVELOPE_MAGIC
            + _CUSTOMER_ENVELOPE_VERSION
            + _length_prefix(key.wrapped)
            + key.wrapped
            + vector
            + sealed
        )


@dataclass(frozen=True)
class CardVerificationValueCipher:
    """Frame a card verification value the way ``card-service`` reads it back.

    Parameters
    ----------
    key_id : str
        The customer-managed key, published by infrastructure as the card workload's
        ``CARDDEMO_SECURITY_CVV_KEY_ID`` parameter. It resolves to the same application key the
        account workload uses: the two purposes are kept apart by their encryption context, not
        by separate keys, so one rotation schedule and one grant cover both.
    keys : DataKeySource
        Where single-use data keys come from.

    Raises
    ------
    ProtectedColumnError
        Never at construction. Failures surface from :meth:`seal`.
    """

    key_id: str
    keys: DataKeySource

    def seal(self, value: str) -> bytes:
        """Encipher one verification value into the self-describing envelope the column stores.

        Purpose
        -------
        Produce the exact bytes ``card.cards.cvv_encrypted`` stores, including the marker and
        version byte the card service checks before it parses anything else.

        Parameters
        ----------
        value : str
            Exactly :data:`CARD_VERIFICATION_VALUE_DIGITS` digit characters.

        Returns
        -------
        bytes
            The framed envelope: the marker, the version, a two-byte length, the wrapped key,
            the vector, then the ciphertext and its tag.

        Raises
        ------
        ProtectedColumnError
            If the value is not exactly three digits, the key service refuses, or the platform
            refuses the transformation.
        """
        # Trade-offs: the width and the digit test are enforced here even though the reader
        #   enforces them too, and the message names NEITHER the value nor its length. The card
        #   service's decipher path re-checks the shape, so a value of another width would
        #   encipher successfully and be refused on first read -- weeks later, against a row whose
        #   provenance is no longer obvious. Refusing at the load boundary names the record set
        #   being loaded instead.
        if len(value) != CARD_VERIFICATION_VALUE_DIGITS or not value.isdigit():
            raise ProtectedColumnError(
                "a card verification value must be exactly"
                f" {CARD_VERIFICATION_VALUE_DIGITS} digits; the value supplied is not, and is"
                " refused here rather than enciphered into a column whose reader would refuse it"
            )

        context = MappingProxyType({CONTEXT_PURPOSE_KEY: CARD_VERIFICATION_VALUE_PURPOSE})
        key = self.keys.data_key(key_id=self.key_id, encryption_context=context)
        # Assumptions: US-ASCII, matching `verificationValue.getBytes(US_ASCII)` on the Java
        #   side rather than the UTF-8 the customer framing uses. The two agree for three digits,
        #   and each side matching its own reader is what keeps them from drifting apart on some
        #   value neither corpus contains today.
        vector, sealed = _encipher(key, value.encode("ascii"))
        return (
            _CARD_ENVELOPE_MAGIC
            + _CARD_ENVELOPE_VERSION
            + _length_prefix(key.wrapped)
            + key.wrapped
            + vector
            + sealed
        )
