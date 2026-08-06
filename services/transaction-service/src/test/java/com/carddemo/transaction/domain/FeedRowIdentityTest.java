package com.carddemo.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.transaction.repository.DailyTransactionRepository;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the one property of the pre-posting feed that cannot be asserted by reading a value back: that
 * a row's identity is the generated ingestion sequence and never the transaction identifier the feed
 * supplies.
 *
 * <p>Assumptions: {@code ledger.daily_transactions} constrains none of its thirteen copybook columns,
 * because {@code app/cbl/CBTRN02C.cbl} lines 29 to 31 read the dataset {@code ORGANIZATION IS
 * SEQUENTIAL} with no record key, so a feed carrying one identifier twice is a feed the baseline
 * processes as two records. Two mechanisms then depend on the identity being unique. A persistence
 * context holds ONE instance per identity, so an identifier-keyed entity turns two such rows into one
 * instance and hands a caller the first row's amount twice. And a continuation query keyed STRICTLY
 * beyond the last value of a non-unique column excludes every other row sharing that value, so the
 * second row is skipped by the next chunk as well. Both losses are silent and both are monetary.
 *
 * <p>Alternatives Considered: proving this against a live database through Testcontainers, which would
 * exercise the generated column itself. That belongs to the repository integration test this package's
 * charter names, and it cannot replace what is asserted here: the mapping and the derived query names
 * are what decide the outcome, and both are visible by reflection without a database. Asserting them
 * here means the property is checked on every build rather than only where a container can start.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class FeedRowIdentityTest {

    /**
     * A transaction identifier the feed is assumed to repeat across two physical rows.
     *
     * <p>Assumptions: the value is 16 digits with no blank, the shape every identifier in
     * {@code app/data/ASCII/dailytran.txt} has, so the duplicate this test constructs is a duplicate
     * the loader could genuinely receive rather than a malformed value.
     */
    private static final String REPEATED_ID = "0000000000683580";

    /**
     * Confirms the ingestion sequence carries the identity annotations and the identifier carries none.
     *
     * <p>Assumptions: the two halves are asserted together because either alone would pass on a broken
     * mapping -- an entity with the annotation on both members would satisfy a presence check, and one
     * with it on neither would satisfy an absence check.
     *
     * @throws NoSuchFieldException if either member is renamed without this test being updated, which
     *     is the intended failure rather than a condition to handle
     */
    @Test
    @DisplayName("the identity is the generated ingestion sequence and not the transaction identifier")
    void theIdentityIsTheGeneratedIngestionSequence() throws NoSuchFieldException {
        Field ingestSeq = DailyTransaction.class.getDeclaredField("ingestSeq");
        Field tranId = DailyTransaction.class.getDeclaredField("tranId");

        assertThat(ingestSeq.isAnnotationPresent(Id.class)).isTrue();
        assertThat(ingestSeq.getAnnotation(GeneratedValue.class).strategy())
                .isEqualTo(GenerationType.IDENTITY);
        assertThat(ingestSeq.getAnnotation(Column.class).name()).isEqualTo("ingest_seq");
        assertThat(ingestSeq.getType()).isEqualTo(Long.class);

        assertThat(tranId.isAnnotationPresent(Id.class)).isFalse();
        assertThat(tranId.isAnnotationPresent(GeneratedValue.class)).isFalse();
        assertThat(tranId.getAnnotation(Column.class).name()).isEqualTo("transaction_id");
    }

    /**
     * Confirms two rows that repeat one identifier stay two rows, in equality, hashing and a set.
     *
     * <p>Assumptions: the set is what models the persistence context's identity map. Collapsing there
     * is exactly the failure this finding named, so a set of the two instances holding two elements is
     * the assertion that the collapse cannot happen.
     */
    @Test
    @DisplayName("two feed rows repeating one identifier remain distinct")
    void twoFeedRowsRepeatingOneIdentifierRemainDistinct() {
        DailyTransaction first = feedRow(new BigDecimal("100.00"));
        DailyTransaction second = feedRow(new BigDecimal("250.00"));
        assignIngestSeq(first, 41L);
        assignIngestSeq(second, 42L);

        assertThat(first.getTranId()).isEqualTo(second.getTranId());
        assertThat(first).isNotEqualTo(second);

        // WHY : Alternatives Considered: additionally asserting that the two hash codes DIFFER.
        //       Rejected because the entity hashes to a constant on purpose: the sequence is null
        //       until the database assigns it, and a hash derived from it would change on insert and
        //       leave an already-hashed instance unreachable in its bucket. Distinctness in a
        //       hash-based collection is delivered by equals, not by the hash -- two unequal
        //       instances sharing a bucket are still two elements -- so the set assertion below is
        //       the property worth pinning and a hash-inequality assertion would pin an
        //       implementation the entity deliberately does not have.
        Set<DailyTransaction> scanned = new HashSet<>(List.of(first, second));
        assertThat(scanned).hasSize(2);
        assertThat(scanned).extracting(DailyTransaction::getTranAmt)
                .containsExactlyInAnyOrder(new BigDecimal("100.00"), new BigDecimal("250.00"));
    }

    /**
     * Confirms one row read twice is one row, so the identity is an identity and not a serial number.
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
     * Confirms two unpersisted instances are distinct, which a bare value comparison would not give.
     *
     * <p>Assumptions: an instance with no sequence has no identity, so the only defensible answer is
     * reference identity. Comparing two absent sequences as equal would make every unpersisted
     * instance equal to every other, which would collapse a batch of pending rows to one element.
     */
    @Test
    @DisplayName("two unpersisted instances are equal only to themselves")
    void twoUnpersistedInstancesAreEqualOnlyToThemselves() {
        DailyTransaction first = feedRow(new BigDecimal("100.00"));
        DailyTransaction second = feedRow(new BigDecimal("100.00"));

        assertThat(first.getIngestSeq()).isNull();
        assertThat(first).isEqualTo(first);
        assertThat(first).isNotEqualTo(second);
        assertThat(new HashSet<>(List.of(first, second))).hasSize(2);
    }

    /**
     * Confirms the repository scans and resumes on the ingestion sequence and is keyed by {@code Long}.
     *
     * <p>Assumptions: the derived query names are the whole implementation of the cursor, so asserting
     * them by name is asserting the behaviour. A method named for a strict comparison on the
     * identifier would reintroduce the skip this finding named, so its absence is asserted as well as
     * the presence of the sequence-keyed pair.
     */
    @Test
    @DisplayName("the repository cursor is the ingestion sequence and its identity type is Long")
    void theRepositoryCursorIsTheIngestionSequence() {
        List<String> declared = List.of(DailyTransactionRepository.class.getDeclaredMethods()).stream()
                .map(Method::getName)
                .toList();

        assertThat(declared).containsExactlyInAnyOrder(
                "findAllByOrderByIngestSeqAsc", "findByIngestSeqGreaterThanOrderByIngestSeqAsc");
        assertThat(declared).noneMatch(name -> name.contains("TranId"));
        assertThat(identityTypeOf(DailyTransactionRepository.class)).isEqualTo(Long.class);
    }

    /**
     * Builds one feed row carrying the repeated identifier and the supplied amount.
     *
     * <p>Assumptions: every other component is held constant, so the only difference between two rows
     * this method builds is the amount -- which is what makes a collapse visible as a lost amount
     * rather than as a lost row.
     *
     * @param amount the signed amount at scale 2 to place on the row; must not be {@code null}
     * @return a newly constructed feed row with an absent processing timestamp, as the extract carries
     *     it
     */
    private DailyTransaction feedRow(BigDecimal amount) {
        return new DailyTransaction(REPEATED_ID, "01", "0001", "POS       ",
                "PURCHASE", amount, 123456789L, "ACME HARDWARE", "SEATTLE", "98101     ",
                "4000123456789010", LocalDateTime.parse("2022-06-10T19:27:53"), null);
    }

    /**
     * Assigns the generated identity that only the database would otherwise supply.
     *
     * <p>Alternatives Considered: exposing a setter on the entity so this test could assign the value
     * through the public surface. Rejected because the column is {@code GENERATED ALWAYS} and a setter
     * would advertise an assignment the database refuses; reflection keeps the production surface
     * honest and confines the write to this test.
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

    /**
     * Resolves the identity type a repository interface is parameterised with.
     *
     * @param repository the repository interface to inspect; must not be {@code null}
     * @return the second type argument of its {@code JpaRepository} supertype, which is the identity
     *     type
     * @throws IllegalStateException if the interface does not extend a parameterised supertype, which
     *     would mean the declaration changed shape rather than changed key
     */
    private Type identityTypeOf(Class<?> repository) {
        for (Type supertype : repository.getGenericInterfaces()) {
            if (supertype instanceof ParameterizedType parameterized) {
                return parameterized.getActualTypeArguments()[1];
            }
        }
        throw new IllegalStateException("no parameterised supertype on " + repository.getName());
    }
}
