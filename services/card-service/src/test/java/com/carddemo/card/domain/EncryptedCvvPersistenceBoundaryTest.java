package com.carddemo.card.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The persistence assertion: a plaintext verification value cannot be stored in {@code cvv_encrypted}.
 *
 * <p>This is the one property the whole encipherment design is for, and it is asserted at the boundary
 * the persistence provider actually uses rather than at the entity's accessors. The provider reads and
 * writes a mapped attribute directly, so a check that lived only on a setter would bind whoever
 * remembered to call it; the check that binds the database is the declared conversion, which is what these
 * cases exercise.</p>
 *
 * <p>Assumptions: no database is started. What is under test is which VALUES can cross the boundary, and
 * that is decided by the converter and the attribute's type before any statement is issued -- so a
 * container would add a dependency without adding evidence. The column's own declaration is asserted by
 * reflection instead, because the mapping is the other half of the boundary: a converter that is not
 * attached to the attribute would leave every case here passing while the provider stored raw bytes.</p>
 *
 * <p>Assumptions: three independent barriers are asserted, because any one of them alone would be a
 * convention. The attribute's TYPE makes plaintext unassignable at compile time, the converter's read
 * direction refuses plaintext bytes already sitting in the column, and the mapping declares the converter
 * so that both directions are actually on the provider's path.</p>
 */
class EncryptedCvvPersistenceBoundaryTest {

    /**
     * The plaintext verification value the previous bare-array member accepted.
     */
    private static final byte[] PLAINTEXT_CVV = "123".getBytes(StandardCharsets.US_ASCII);

    /**
     * A well-formed envelope, assembled from stand-in parts.
     */
    private static final EncryptedCvv ENVELOPE = EncryptedCvv.wrap(
            filled(184, (byte) 0xA7), filled(EncryptedCvv.INITIALISATION_VECTOR_LENGTH, (byte) 0x5C),
            filled(19, (byte) 0x3E));

    /**
     * The converter under test, instantiated the way the provider instantiates it.
     */
    private final EncryptedCvvConverter converter = new EncryptedCvvConverter();

    /**
     * Bytes already in the column that are not an envelope are refused when the row is read.
     *
     * <p>Assumptions: this is the direction a reader is most likely to assume is safe -- what is already
     * stored must have been written correctly -- and it is the direction that makes the absence of
     * plaintext a property of the DATA rather than of the write path alone. A row can predate the type, can
     * have been loaded by a tool that bypassed the application, or can have been edited in place.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("plaintext already in the column is refused when the row is hydrated")
    void plaintextInTheColumnIsRefusedOnRead() {
        assertThatThrownBy(() -> this.converter.convertToEntityAttribute(PLAINTEXT_CVV))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cvv_encrypted")
                .hasMessageNotContaining("123");
    }

    /**
     * A well-formed envelope crosses the boundary in both directions unchanged.
     *
     * <p>Assumptions: asserted together with the refusals, because a boundary that refused everything
     * would satisfy every other case here and store nothing. Round-tripping is what shows the barrier is
     * a filter rather than a wall.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a well-formed envelope round-trips through the conversion")
    void anEnvelopeRoundTrips() {
        byte[] stored = this.converter.convertToDatabaseColumn(ENVELOPE);

        assertThat(stored).isEqualTo(ENVELOPE.envelope());
        assertThat(this.converter.convertToEntityAttribute(stored)).isEqualTo(ENVELOPE);
    }

    /**
     * An absent value passes both directions unchanged, the column being the one nullable column.
     *
     * <p>Assumptions: this is asserted rather than left to inference because a converter that refused
     * {@code null} would make the column unusable as declared -- it is nullable deliberately, so that a
     * deployment retaining no verification value stores nothing rather than a placeholder that would
     * afterwards have to be told apart from a genuine value.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an absent value passes both directions unchanged")
    void anAbsentValuePasses() {
        assertThat(this.converter.convertToDatabaseColumn(null)).isNull();
        assertThat(this.converter.convertToEntityAttribute(null)).isNull();
    }

    /**
     * The entity's attribute is typed and the conversion is declared on it.
     *
     * <p>Assumptions: asserted by reflection because this is the half of the boundary the other cases
     * cannot see. They exercise the converter directly, so they would all pass even if the converter were
     * never attached to the attribute -- in which case the provider would fall back to storing a raw byte
     * array and every barrier above would be bypassed on the only path that matters.</p>
     *
     * <p>Assumptions: the attribute's declared TYPE is asserted as well as the annotation. The type is
     * what makes a plaintext assignment a compilation failure rather than a runtime one, which is the
     * barrier that cannot be forgotten at a call site.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     *
     * @throws NoSuchFieldException if the attribute has been renamed, which is itself a failure of this
     *     assertion rather than an error in it
     */
    @Test
    @DisplayName("the mapped attribute is typed and carries the declared conversion")
    void theMappedAttributeCarriesTheConversion() throws NoSuchFieldException {
        Field attribute = Card.class.getDeclaredField("cvvEncrypted");

        assertThat(attribute.getType())
                .as("a typed attribute makes a plaintext assignment a compilation failure")
                .isEqualTo(EncryptedCvv.class);
        assertThat(attribute.getAnnotation(Column.class).name()).isEqualTo("cvv_encrypted");
        assertThat(attribute.getAnnotation(Convert.class))
                .as("the conversion has to be declared, or the provider stores raw bytes")
                .isNotNull()
                .extracting(Convert::converter)
                .isEqualTo(EncryptedCvvConverter.class);
    }

    /**
     * The entity exposes no route that accepts raw bytes for this attribute.
     *
     * <p>Assumptions: asserted by reflection over the whole public surface rather than by naming the one
     * setter that was removed. Naming it would pass the moment an equivalent was added under a different
     * name, which is exactly how a removed hazard comes back; enumerating the surface asserts the property
     * instead of the edit.</p>
     *
     * <p>Assumptions: the constructor is included in the search. It took a byte array before, and a
     * constructor is as much a writer as a setter is.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("no public member of the entity accepts raw bytes")
    void noPublicMemberAcceptsRawBytes() {
        assertThat(Card.class.getMethods())
                .filteredOn(method -> Arrays.asList(method.getParameterTypes())
                        .contains(byte[].class))
                .as("a method taking raw bytes would reopen the assignment the type closed")
                .isEmpty();
        assertThat(Card.class.getConstructors())
                .filteredOn(constructor -> Arrays.asList(constructor.getParameterTypes())
                        .contains(byte[].class))
                .as("a constructor taking raw bytes would reopen it on the creation path")
                .isEmpty();
        assertThat(new Card("4111111111110011", 11L, ENVELOPE, "TEST CARDHOLDER",
                LocalDate.of(2030, 1, 31), "Y").getCvvEncrypted())
                .as("the typed route stores the envelope it was given")
                .isEqualTo(ENVELOPE);
    }

    /**
     * Builds an array of the requested length filled with one byte value.
     *
     * @param length how many bytes to produce
     * @param value the byte to fill with
     * @return the filled array, never {@code null}
     */
    private static byte[] filled(int length, byte value) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }
}
