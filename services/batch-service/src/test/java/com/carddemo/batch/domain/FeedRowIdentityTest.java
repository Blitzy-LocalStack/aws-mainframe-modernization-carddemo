package com.carddemo.batch.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the identity of {@link DailyTransaction}, the read-only mapping this module scans the
 * pre-posting feed through.
 *
 * <p>Assumptions: this module is the feed's consumer -- {@code app/cbl/CBTRN02C.cbl} lines 202 to 219
 * loop over it and lines 424 to 444 post each record -- so a row this mapping cannot distinguish is a
 * record this module posts wrongly. The feed constrains nothing: lines 29 to 31 of that program select
 * it {@code ORGANIZATION IS SEQUENTIAL} with no record key, so one identifier may legitimately appear
 * on two records. A persistence context holds one instance per identity, so an identifier-keyed
 * mapping would hand this module the first row's amount twice and lose the second row's amount
 * altogether -- a monetary loss with no error anywhere.
 *
 * <p>Alternatives Considered: leaving the property to the owning service's own test, since the two
 * modules map the same table. Rejected because the two mappings are deliberately independent -- this
 * module may not import another service's domain package, and the entity's own documentation records
 * that the two can drift with no compiler to notice -- so each has to assert the property it depends
 * on.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class FeedRowIdentityTest {

    /**
     * A transaction identifier assumed to arrive on two physical rows of one extract.
     *
     * <p>Assumptions: 16 digits with no blank, the shape every identifier in
     * {@code app/data/ASCII/dailytran.txt} has, so the duplicate constructed below is one a loader
     * could genuinely receive.
     */
    private static final String REPEATED_ID = "0000000000683580";

    /**
     * Confirms the identity annotations sit on the ingestion sequence and not on the identifier.
     *
     * @throws NoSuchFieldException if either member is renamed without this test being updated, which
     *     is the intended failure rather than a condition to handle
     */
    @Test
    @DisplayName("the identity is the generated ingestion sequence and not the transaction identifier")
    void theIdentityIsTheGeneratedIngestionSequence() throws NoSuchFieldException {
        Field ingestSeq = DailyTransaction.class.getDeclaredField("ingestSeq");
        Field transactionId = DailyTransaction.class.getDeclaredField("transactionId");

        assertThat(ingestSeq.isAnnotationPresent(Id.class)).isTrue();
        assertThat(ingestSeq.getAnnotation(Column.class).name()).isEqualTo("ingest_seq");
        assertThat(ingestSeq.getType()).isEqualTo(Long.class);

        // WHY : Assumptions: the ABSENCE of a generation strategy is asserted here, where the owning
        //       service's mapping asserts its presence. This module scans the feed and never inserts
        //       into it -- the type is read-only here -- so declaring IDENTITY generation would
        //       describe a write path this module does not have. Asserting the absence pins that
        //       decision instead of leaving the two mappings looking like an oversight in one of them.
        assertThat(ingestSeq.isAnnotationPresent(GeneratedValue.class)).isFalse();

        assertThat(transactionId.isAnnotationPresent(Id.class)).isFalse();
        assertThat(transactionId.getAnnotation(Column.class).name()).isEqualTo("transaction_id");
    }

    /**
     * Confirms two rows repeating one identifier stay two rows, and that each keeps its own amount.
     *
     * <p>Assumptions: the set models the persistence context's identity map, so two elements is the
     * assertion that the collapse this finding named cannot occur, and the amounts assertion is what
     * shows the consequence would have been monetary.
     */
    @Test
    @DisplayName("two feed rows repeating one identifier remain distinct")
    void twoFeedRowsRepeatingOneIdentifierRemainDistinct() {
        DailyTransaction first = feedRow(new BigDecimal("100.00"));
        DailyTransaction second = feedRow(new BigDecimal("250.00"));
        assignIngestSeq(first, 41L);
        assignIngestSeq(second, 42L);

        assertThat(first.getTransactionId()).isEqualTo(second.getTransactionId());
        assertThat(first).isNotEqualTo(second);

        // WHY : Alternatives Considered: additionally asserting that the two hash codes DIFFER.
        //       Rejected because this entity hashes to a CONSTANT on purpose, for the reason its own
        //       hashCode documentation gives: the pair must agree, and a hash derived from a sequence
        //       that is absent until the provider hydrates it would move an instance between buckets.
        //       Distinctness in a hash-based collection comes from equals, not from the hash -- two
        //       unequal instances sharing a bucket are still two elements -- so the set assertion
        //       below is the property worth pinning.
        Set<DailyTransaction> scanned = new HashSet<>(List.of(first, second));
        assertThat(scanned).hasSize(2);
        assertThat(scanned).extracting(DailyTransaction::getAmount)
                .containsExactlyInAnyOrder(new BigDecimal("100.00"), new BigDecimal("250.00"));
    }

    /**
     * Confirms one physical row read twice is one row, so the identity denotes a row rather than a read.
     */
    @Test
    @DisplayName("two reads of one physical row compare equal")
    void twoReadsOfOnePhysicalRowCompareEqual() {
        DailyTransaction read = feedRow(new BigDecimal("100.00"));
        DailyTransaction reread = feedRow(new BigDecimal("100.00"));
        assignIngestSeq(read, 41L);
        assignIngestSeq(reread, 41L);

        assertThat(read).isEqualTo(reread);
        assertThat(read.hashCode()).isEqualTo(reread.hashCode());
    }

    /**
     * Confirms the rendering names the sequence, so a log line can say which physical row it concerns.
     *
     * <p>Assumptions: the negative half is asserted alongside, because the rendering's own
     * documentation commits to withholding the card number and the amount, and a member added to it
     * could reintroduce either.
     */
    @Test
    @DisplayName("the rendering names the ingestion sequence and still withholds card and amount")
    void theRenderingNamesTheIngestionSequence() {
        DailyTransaction row = feedRow(new BigDecimal("250.00"));
        assignIngestSeq(row, 42L);

        String rendered = row.toString();

        assertThat(rendered).contains("ingestSeq=42");
        assertThat(rendered).contains("transactionId=" + REPEATED_ID);
        assertThat(rendered).doesNotContain("4000123456789010");
        assertThat(rendered).doesNotContain("250.00");
    }

    /**
     * Builds one feed row carrying the repeated identifier and the supplied amount.
     *
     * @param amount the signed amount at scale 2 to place on the row; must not be {@code null}
     * @return a newly constructed feed row with an absent processing stamp, as the extract carries it
     */
    private DailyTransaction feedRow(BigDecimal amount) {
        return new DailyTransaction(REPEATED_ID, "01", "0001", "POS       ",
                "PURCHASE", amount, 123456789L, "ACME HARDWARE", "SEATTLE", "98101     ",
                "4000123456789010", LocalDateTime.parse("2022-06-10T19:27:53"), null);
    }

    /**
     * Assigns the generated identity that only the database would otherwise supply.
     *
     * <p>Alternatives Considered: adding a mutator to the entity so this test could assign the value
     * through the public surface. Rejected because the entity is deliberately immutable and its column
     * is {@code GENERATED ALWAYS}; a mutator would advertise an assignment the database refuses.
     *
     * @param row the instance to assign; must not be {@code null}
     * @param value the sequence to place on it, standing in for the generated value
     * @throws IllegalStateException if the member cannot be reached, which means it was renamed
     */
    private void assignIngestSeq(DailyTransaction row, long value) {
        try {
            Field field = DailyTransaction.class.getDeclaredField("ingestSeq");
            field.setAccessible(true);
            field.set(row, value);
        } catch (NoSuchFieldException | IllegalAccessException failure) {
            throw new IllegalStateException("ingestSeq is no longer assignable by reflection", failure);
        }
    }
}
