package com.carddemo.card.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * The single persistence boundary between {@link EncryptedCvv} and the {@code cvv_encrypted} column.
 *
 * <p><b>Purpose.</b> This converter is the only place the enciphered verification value crosses between
 * its typed form and the raw bytes the column holds, in either direction. Every write passes through
 * {@link #convertToDatabaseColumn(EncryptedCvv)} and every read through
 * {@link #convertToEntityAttribute(byte[])}, because the provider reads and writes the mapped attribute
 * itself rather than through the entity's accessors -- so a boundary declared here binds paths that no
 * accessor sees.</p>
 *
 * <p>Refactoring Rationale: the attribute was a bare {@code byte[]} mapped straight onto the column, so
 * the framing check that {@link EncryptedCvv} performs would have bound only callers who happened to use
 * the type. Declaring the conversion is what puts the check on the persistence path itself: bytes that are
 * not an envelope cannot be written, and bytes already in the column that are not an envelope are refused
 * when the row is hydrated rather than handed onward as though they were ciphertext.</p>
 *
 * <p>Assumptions: the read direction validates as strictly as the write direction, and the asymmetry a
 * reader might expect -- trust what is already stored -- is deliberately absent. A row can predate this
 * type, can have been loaded by a tool that bypassed the application, or can have been edited in place, so
 * validating on read is what makes the absence of plaintext a property of the data rather than of the
 * write path alone. Trade-offs: a legacy row holding plaintext then fails its own read instead of being
 * silently returned, which is the louder of the two failures and the one that gets fixed.</p>
 *
 * <p>Assumptions: {@code null} passes in both directions unchanged. The column is the one nullable column
 * of the table, deliberately, so that a deployment retaining no verification value stores nothing rather
 * than a placeholder that would afterwards have to be told apart from a genuine value; a converter that
 * refused {@code null} would make that column unusable as declared.</p>
 *
 * <p>Alternatives Considered: performing the encipherment here, so that the entity could hold the three
 * digits and the converter would encipher on the way to the column. Rejected because a converter is
 * instantiated by the persistence provider and cannot be given a key-management client without a static
 * hook, and because it would put plaintext back inside an entity that any query can hydrate -- which is
 * the state {@link EncryptedCvv} exists to make inexpressible. The encipherment happens in
 * {@code com.carddemo.card.service.CardVerificationValueCipher}, before an entity is ever assigned.</p>
 *
 * <p>Alternatives Considered: registering this globally with {@code autoApply = true}. Rejected because
 * the conversion applies to exactly one attribute of one entity, and a global registration would make any
 * future attribute of this type convert silently -- which reads as convenient until an attribute wants a
 * different column form and the global registration is the reason it cannot have one.</p>
 *
 * <p><strong>Return value.</strong> Each member documents its own return value.</p>
 */
@Converter
public class EncryptedCvvConverter implements AttributeConverter<EncryptedCvv, byte[]> {

    /**
     * Creates the converter.
     *
     * <p>Assumptions: the persistence provider instantiates this reflectively through a no-argument
     * constructor. It is written out rather than left implicit because the documentation gate requires a
     * docstring on every constructor, and because this class holds no state to initialise -- which is
     * itself the reason it can be provider-instantiated at all.</p>
     */
    public EncryptedCvvConverter() {
        // Assumptions: empty by design. The framing rule is static on EncryptedCvv, so this converter
        // needs no configuration and holds no key material -- it validates a shape, it does not decipher.
    }

    /**
     * Converts the typed value to the bytes the column stores.
     *
     * @param attribute the value to store, or {@code null} when the row retains no verification value
     * @return the framed bytes, or {@code null} when {@code attribute} is {@code null}
     */
    @Override
    public byte[] convertToDatabaseColumn(EncryptedCvv attribute) {
        return attribute == null ? null : attribute.envelope();
    }

    /**
     * Converts the stored bytes back to the typed value, refusing anything that is not an envelope.
     *
     * @param dbData the bytes as stored, or {@code null} when the row retains no verification value
     * @return the typed value, or {@code null} when {@code dbData} is {@code null}
     * @throws IllegalArgumentException if the stored bytes do not carry the envelope framing, which
     *     includes the case of a plaintext verification value having been written by some other path
     */
    @Override
    public EncryptedCvv convertToEntityAttribute(byte[] dbData) {
        return dbData == null ? null : EncryptedCvv.ofEnvelope(dbData);
    }
}
