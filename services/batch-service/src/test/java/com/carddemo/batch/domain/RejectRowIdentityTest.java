package com.carddemo.batch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the identity of {@link TransactionReject} across the moment the database assigns its ordinal.
 *
 * <p>Assumptions: this module constructs a reject while validating one record and hands it to the
 * persistence context afterwards, so an instance genuinely exists in both states -- ordinal absent
 * and ordinal assigned -- and both states are reachable by a caller. A hash derived from the ordinal
 * would take one value in the first state and another in the second, and the hashed collections in
 * the JDK read an element's bucket once at insertion and never rehash it, so the element would become
 * unreachable from the bucket its new value selects. The consequence is silent: {@code contains}
 * answers false for an element the collection still holds and {@code remove} does not remove it, and
 * nothing raises.
 *
 * <p>Assumptions: the assertions below are written against the two states rather than against a
 * particular implementation of the hash, so the property survives a change of hashing scheme that
 * keeps the value stable. What they refuse is any scheme whose value moves when the ordinal arrives.
 *
 * <p>Alternatives Considered: leaving this property to an integration test that persists a row and
 * reads it back. Rejected because the failure this class guards against occurs entirely in memory,
 * between constructing an instance and flushing it, so it needs no database to reproduce and no
 * container to observe -- and an integration test would be slower while proving less directly.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class RejectRowIdentityTest {

    /**
     * The declared width of the record image the reject stream carries.
     *
     * <p>Assumptions: 350 is the width of {@code REJECT-TRAN-DATA} at
     * {@code app/cbl/CBTRN02C.cbl} line 177, so an image built at this width is one the column
     * accepts without padding or truncation.
     */
    private static final int IMAGE_WIDTH = 350;

    /** The reason code the images below carry, being the missing-cross-reference reason. */
    private static final short REASON_CARD_NOT_FOUND = 100;

    /** The verbatim description that reason carries at {@code app/cbl/CBTRN02C.cbl} line 386. */
    private static final String TEXT_CARD_NOT_FOUND = "INVALID CARD NUMBER FOUND";

    /** A synthetic ordinal standing in for the value the identity column would assign. */
    private static final long ASSIGNED_ORDINAL = 41L;

    /**
     * Confirms the hash does not move when the ordinal arrives, which is the whole property.
     *
     * <p>Assumptions: the value is captured before the assignment and compared after it, so the case
     * fails for any scheme that reads the ordinal. Asserting a specific number instead would pass for
     * a scheme that happened to produce that number in one state and a different one in the other.
     */
    @Test
    @DisplayName("the hash is unchanged by the ordinal the database assigns")
    void theHashIsUnchangedByTheAssignedOrdinal() {
        TransactionReject pending = reject(image('A'));

        int hashBeforeAssignment = pending.hashCode();
        assignRejectSeq(pending, ASSIGNED_ORDINAL);

        assertThat(pending.getRejectSeq()).isEqualTo(ASSIGNED_ORDINAL);
        assertThat(pending.hashCode()).isEqualTo(hashBeforeAssignment);
    }

    /**
     * Confirms an instance placed in a hashed collection before the flush is still found afterwards.
     *
     * <p>Assumptions: the set is the model of the failure this class exists for. The element is added
     * while the ordinal is absent and interrogated after it is assigned, both by itself and by a
     * distinct instance carrying the same ordinal, because those are the two lookups a caller makes.
     * The map is exercised alongside the set because the two share the bucket mechanism and a caller
     * indexing rejects by row would hit the same failure through {@code get}.
     */
    @Test
    @DisplayName("a reject hashed before its flush stays findable once the ordinal is assigned")
    void aRejectHashedBeforeFlushStaysFindableAfterAssignment() {
        TransactionReject pending = reject(image('A'));
        Set<TransactionReject> collected = new HashSet<>();
        Map<TransactionReject, String> indexed = new HashMap<>();
        collected.add(pending);
        indexed.put(pending, TEXT_CARD_NOT_FOUND);

        assignRejectSeq(pending, ASSIGNED_ORDINAL);

        assertThat(collected).contains(pending);
        assertThat(indexed).containsKey(pending);

        TransactionReject reread = reject(image('A'));
        assignRejectSeq(reread, ASSIGNED_ORDINAL);

        // WHY : Assumptions: the re-read instance is a second object on one stored row, which is what
        //       a caller holds after clearing the persistence context and reading the row again. It is
        //       asserted separately from the identical-reference lookup above because that lookup
        //       succeeds on reference identity alone and so cannot distinguish a working equality
        //       contract from a broken one.
        assertThat(collected).contains(reread);
        assertThat(indexed).containsKey(reread);
        assertThat(collected.remove(reread)).isTrue();
        assertThat(collected).isEmpty();
    }

    /**
     * Confirms two rejects of one identical record stay two entries while both ordinals are absent.
     *
     * <p>Assumptions: the migration owning this table declares no unique constraint over the three
     * payload columns, so two identical images are two legitimate rows. The set is the model of a
     * chunk being built, and two elements is the assertion that the second reject is not absorbed by
     * the first.
     */
    @Test
    @DisplayName("two unflushed rejects of one identical record remain two entries")
    void twoUnflushedRejectsOfOneRecordRemainTwoEntries() {
        TransactionReject first = reject(image('A'));
        TransactionReject second = reject(image('A'));

        assertThat(first.getRawRecord()).isEqualTo(second.getRawRecord());
        assertThat(first).isNotEqualTo(second);

        // WHY : Assumptions: distinctness in a hashed collection comes from equals and not from the
        //       hash, so two unequal elements sharing one bucket are still two elements. The hashes
        //       are deliberately equal here -- this type answers a constant -- and asserting the set
        //       size rather than a hash inequality is what states the property that actually matters.
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        Set<TransactionReject> chunk = new HashSet<>();
        chunk.add(first);
        chunk.add(second);
        assertThat(chunk).hasSize(2);
    }

    /**
     * Confirms two instances on one assigned ordinal compare equal and agree on their hash.
     *
     * <p>Assumptions: the images differ between the two instances, so this case shows the ordinal and
     * nothing else decides equality. Building both from one image would leave the case satisfied by an
     * equality over the payload columns, which is the contract this table cannot have.
     */
    @Test
    @DisplayName("two instances on one assigned ordinal compare equal whatever their payload")
    void twoInstancesOnOneOrdinalCompareEqual() {
        TransactionReject read = reject(image('A'));
        TransactionReject reread = reject(image('B'));
        assignRejectSeq(read, ASSIGNED_ORDINAL);
        assignRejectSeq(reread, ASSIGNED_ORDINAL);

        assertThat(read.getRawRecord()).isNotEqualTo(reread.getRawRecord());
        assertThat(read).isEqualTo(reread);
        assertThat(read.hashCode()).isEqualTo(reread.hashCode());
    }

    /**
     * Confirms an ordinal-derived hash is genuinely unstable, so the rejected alternative is measured.
     *
     * <p>Assumptions: this case asserts a property of {@link Objects#hash(Object...)} rather than of
     * the entity, and it is here because the entity's rationale cites two concrete numbers -- the
     * value for an absent ordinal and the value for an assigned one -- and a cited number that nobody
     * checks is exactly the kind of rationale a reader would trust wrongly. Thirty-one is what a
     * one-element array whose seed is one produces for a null element, which is not the zero a reader
     * might assume.
     */
    @Test
    @DisplayName("the rejected ordinal-derived hash would move, and would not start at zero")
    void theRejectedOrdinalDerivedHashWouldMove() {
        assertThat(Objects.hash((Object) null)).isEqualTo(31).isNotZero();
        assertThat(Objects.hash(ASSIGNED_ORDINAL)).isNotEqualTo(Objects.hash((Object) null));
    }

    /**
     * Confirms the rendering names the ordinal and the reason and still withholds the record image.
     *
     * <p>Assumptions: the negative half is asserted alongside the positive one because the image
     * carries a primary account number and this rendering reaches log lines, so a member added to it
     * could reintroduce that exposure with nothing else in the module looking wrong.
     */
    @Test
    @DisplayName("the rendering names the ordinal and the reason and withholds the record image")
    void theRenderingWithholdsTheRecordImage() {
        TransactionReject row = reject(image('A'));
        assignRejectSeq(row, ASSIGNED_ORDINAL);

        String rendered = row.toString();

        assertThat(rendered).contains(String.valueOf(ASSIGNED_ORDINAL));
        assertThat(rendered).contains(TEXT_CARD_NOT_FOUND);
        assertThat(rendered).doesNotContain(row.getRawRecord());
        assertThat(rendered).doesNotContain("AAAA");
    }

    /**
     * Builds one reject carrying a full-width image made of one repeated character.
     *
     * <p>Assumptions: the image is a repeated character rather than a decoded transaction record,
     * because no assertion here reads a field inside it -- the cases are about identity, not about
     * layout -- and a synthetic filler makes it unmistakable that no account holder value is present.
     *
     * @param filler the character to repeat across the declared width of the image
     * @return a newly constructed reject with an absent ordinal, as a caller holds it before the flush
     */
    private TransactionReject reject(String filler) {
        return new TransactionReject(filler, REASON_CARD_NOT_FOUND, TEXT_CARD_NOT_FOUND);
    }

    /**
     * Renders a full-width record image made of one repeated character.
     *
     * @param filler the character to repeat
     * @return a string of exactly {@value #IMAGE_WIDTH} copies of {@code filler}
     */
    private String image(char filler) {
        return String.valueOf(filler).repeat(IMAGE_WIDTH);
    }

    /**
     * Assigns the ordinal that only the identity column would otherwise supply.
     *
     * <p>Alternatives Considered: adding a mutator to the entity so this test could assign the value
     * through the public surface. Rejected because the entity is deliberately immutable and carries no
     * setter at all; a mutator added for a test would advertise a mutation path the reject stream does
     * not have, and the baseline writes each reject once with no rewrite anywhere.
     *
     * @param row the instance to assign; must not be {@code null}
     * @param value the ordinal to place on it, standing in for the generated value
     * @throws IllegalStateException if the member cannot be reached, which means it was renamed and is
     *     the intended failure rather than a condition to handle
     */
    private void assignRejectSeq(TransactionReject row, long value) {
        try {
            Field field = TransactionReject.class.getDeclaredField("rejectSeq");
            field.setAccessible(true);
            field.set(row, value);
        } catch (NoSuchFieldException | IllegalAccessException failure) {
            throw new IllegalStateException("rejectSeq is no longer assignable by reflection", failure);
        }
    }
}
