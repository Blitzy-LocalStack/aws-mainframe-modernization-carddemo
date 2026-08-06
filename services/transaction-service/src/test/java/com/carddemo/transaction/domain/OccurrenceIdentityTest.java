package com.carddemo.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Id;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pins the occurrence identity of the two keyless ledger streams, so that neither can regress to an
 * identity drawn from its payload.
 *
 * <p>Both {@link DailyTransaction} and {@link TransactionReject} mirror a sequential dataset the
 * baseline appends to and never keys into, so the same business value may legitimately arrive twice.
 * An earlier revision mapped a payload column as the identity in each -- the transaction identifier on
 * the feed and the 350-byte record image on the reject stream -- and both mappings lost rows: a
 * persistence context cannot tell two rows with one identity apart, so it collapses them, and a
 * hash-based collection discards one of the pair. Each table now carries a generated per-occurrence
 * sequence and each entity keys on that instead.
 *
 * <p>Assumptions: the properties asserted here are the ones that regress silently. A payload-keyed
 * mapping still compiles, still passes a schema validation that only checks columns exist, and still
 * returns correct results for every input that happens to contain no duplicate -- which every fixture
 * derived from the current extract does, because its 300 identifiers are all distinct. The defect
 * surfaces only on a duplicate, so a duplicate is what this class constructs.
 *
 * <p>Alternatives Considered: asserting this through a repository integration test against a migrated
 * database instead. Rejected as the primary guard, though it remains complementary: a database test
 * proves the schema accepts two occurrences, which the migration's own primary key already
 * guarantees, whereas the loss being prevented happens in the persistence context and in collection
 * semantics -- above the database and reachable without one. Running without a container also means
 * this guard executes on every build rather than only where Docker is available.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own.
 */
class OccurrenceIdentityTest {

    /**
     * The value a test assigns to stand in for a database-generated sequence.
     *
     * <p>Assumptions: any non-null value serves, because the assertions below concern whether the
     * identity is consulted and not what it contains.
     */
    private static final long FIRST_SEQUENCE = 1L;

    /** The second generated sequence, distinct from {@link #FIRST_SEQUENCE}. */
    private static final long SECOND_SEQUENCE = 2L;

    /**
     * Confirms the feed keys on its ingestion sequence and not on the transaction identifier.
     *
     * <p>Assumptions: the annotation is located by reflection over declared fields rather than asserted
     * on a named member, so a mapping that moved the annotation onto another column fails here rather
     * than passing because the named member still carried it.
     */
    @Test
    void theFeedKeysOnItsIngestionSequenceAndNotOnTheTransactionIdentifier() {
        assertThat(identityMemberOf(DailyTransaction.class)).isEqualTo("ingestSeq");
    }

    /**
     * Confirms the reject stream keys on its occurrence sequence and not on the record image.
     *
     * <p>Assumptions: the record image was the superseded identity, so naming it explicitly is what
     * makes a reversion legible to whoever reads a failure message.
     */
    @Test
    void theRejectStreamKeysOnItsOccurrenceSequenceAndNotOnTheRecordImage() {
        assertThat(identityMemberOf(TransactionReject.class)).isEqualTo("rejectSeq");
    }

    /**
     * Confirms two feed records repeating one transaction identifier stay distinct.
     *
     * <p>Assumptions: this is the defect the ingestion sequence exists to remove, expressed as the
     * smallest case that exhibits it -- two arrivals carrying one identifier, which the sequential
     * baseline processes as two records without complaint. Both remain in a set, and neither displaces
     * the other.
     */
    @Test
    void twoFeedRecordsRepeatingOneIdentifierRemainDistinct() {
        DailyTransaction first = feedRecordWith("TRN000000000001", FIRST_SEQUENCE);
        DailyTransaction second = feedRecordWith("TRN000000000001", SECOND_SEQUENCE);

        assertThat(first).isNotEqualTo(second);

        Set<DailyTransaction> retained = new HashSet<>();
        retained.add(first);
        retained.add(second);
        assertThat(retained).hasSize(2);
    }

    /**
     * Confirms two byte-identical rejects stay distinct.
     *
     * <p>Assumptions: the reject stream is append-only and the same record rejected on two runs is two
     * entries, so identical images must not collapse. The two instances below differ in nothing except
     * the sequence, which is precisely the case an image-keyed identity could not represent.
     */
    @Test
    void twoIdenticalRejectsRemainDistinct() {
        TransactionReject first = rejectWith("SAME RECORD IMAGE", FIRST_SEQUENCE);
        TransactionReject second = rejectWith("SAME RECORD IMAGE", SECOND_SEQUENCE);

        assertThat(first).isNotEqualTo(second);

        Set<TransactionReject> retained = new HashSet<>();
        retained.add(first);
        retained.add(second);
        assertThat(retained).hasSize(2);
    }

    /**
     * Confirms two unpersisted instances of one type are equal only when they are the same object.
     *
     * <p>Assumptions: this is the deliberate opposite of the superseded behaviour and it is the case a
     * loader actually reaches. Two rejects built from one batch and not yet inserted both carry a null
     * sequence; treating a null identity as equal would let a set discard one of them before the
     * database ever assigned the values that distinguish them, reintroducing the loss at the collection
     * layer instead of the provider layer.
     */
    @Test
    void unpersistedInstancesAreEqualOnlyToThemselves() {
        TransactionReject first = new TransactionReject();
        TransactionReject second = new TransactionReject();

        assertThat(first).isEqualTo(first);
        assertThat(first).isNotEqualTo(second);

        DailyTransaction firstRecord = new DailyTransaction();
        DailyTransaction secondRecord = new DailyTransaction();

        assertThat(firstRecord).isEqualTo(firstRecord);
        assertThat(firstRecord).isNotEqualTo(secondRecord);
    }

    /**
     * Confirms one row read twice compares equal on its sequence alone.
     *
     * <p>Assumptions: equality has to remain usable for the case it exists to serve -- two
     * representations of one stored row, which two queries populating different column subsets both
     * produce. The two instances below share a sequence and differ in payload, and comparing equal is
     * correct because the sequence names the row.
     */
    @Test
    void oneRowReadTwiceComparesEqualOnItsSequence() {
        TransactionReject read = rejectWith("IMAGE AS FIRST READ", FIRST_SEQUENCE);
        TransactionReject reread = rejectWith("PROJECTION OMITTED IT", FIRST_SEQUENCE);

        assertThat(read).isEqualTo(reread);
    }

    /**
     * Confirms an instance placed in a hash-based collection before insertion is still found after the
     * sequence is assigned.
     *
     * <p>Assumptions: this is why the hash is a constant rather than a function of the sequence. A
     * loader adds an instance while its sequence is null and the database assigns the value on flush;
     * a hash derived from the sequence would change underneath the bucket the instance was filed in,
     * and the collection could no longer find it. Asserting the hash is unchanged across that
     * transition pins the reason.
     */
    @Test
    void anInstanceStaysFindableAfterTheDatabaseAssignsItsSequence() {
        TransactionReject pending = new TransactionReject();
        Set<TransactionReject> filed = new HashSet<>();
        filed.add(pending);

        int hashBeforeInsert = pending.hashCode();
        assignSequence(pending, "rejectSeq", FIRST_SEQUENCE);

        assertThat(pending.hashCode()).isEqualTo(hashBeforeInsert);
        assertThat(filed).contains(pending);
    }

    /**
     * Confirms mutating the record image does not change the reject's identity.
     *
     * <p>Assumptions: the superseded mapping exposed its identity through a public setter, so a caller
     * could reassign the mapped identity of a row without changing the row. The setter still exists
     * because the image is still a mutable payload column, and this case pins that mutating it no
     * longer moves the identity with it.
     */
    @Test
    void mutatingTheRecordImageDoesNotChangeTheIdentity() {
        TransactionReject reject = rejectWith("ORIGINAL IMAGE", FIRST_SEQUENCE);
        TransactionReject sameRow = rejectWith("ORIGINAL IMAGE", FIRST_SEQUENCE);

        reject.setRawRecord("REWRITTEN IMAGE");

        assertThat(reject).isEqualTo(sameRow);
        assertThat(reject.getRejectSeq()).isEqualTo(FIRST_SEQUENCE);
    }

    /**
     * Returns the name of the member a type annotates as its persistent identity.
     *
     * @param type the entity type to inspect, which must declare exactly one annotated identity
     * @return the name of the single member annotated {@link Id}
     * @throws AssertionError when the type declares no identity member or more than one, either of
     *     which is a mapping fault this method reports rather than hides
     */
    private static String identityMemberOf(Class<?> type) {
        var annotated = java.util.Arrays.stream(type.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(Id.class))
                .map(Field::getName)
                .toList();

        assertThat(annotated)
                .as("%s must declare exactly one identity member", type.getSimpleName())
                .hasSize(1);
        return annotated.get(0);
    }

    /**
     * Builds a feed record carrying a transaction identifier and a stand-in ingestion sequence.
     *
     * @param tranId the transaction identifier to carry, which may repeat across two calls because
     *     repetition is exactly what these cases exercise
     * @param sequence the ingestion sequence to stand in for the value the database would assign
     * @return a feed record with both values populated and every other member left absent
     */
    private static DailyTransaction feedRecordWith(String tranId, long sequence) {
        DailyTransaction record = new DailyTransaction();
        record.setTranId(tranId);
        assignSequence(record, "ingestSeq", sequence);
        return record;
    }

    /**
     * Builds a reject row carrying a record image and a stand-in occurrence sequence.
     *
     * @param rawRecord the record image to carry, which may be byte-identical across two calls because
     *     identical rejects are exactly what these cases exercise
     * @param sequence the occurrence sequence to stand in for the value the database would assign
     * @return a reject row with both values populated and no reason code or description
     */
    private static TransactionReject rejectWith(String rawRecord, long sequence) {
        TransactionReject reject = new TransactionReject();
        reject.setRawRecord(rawRecord);
        assignSequence(reject, "rejectSeq", sequence);
        return reject;
    }

    /**
     * Assigns an identity value the way the persistence provider does, by reflection.
     *
     * <p>Alternatives Considered: adding a setter for the identity so a test could assign it through
     * the public surface. Rejected because the ABSENCE of that setter is part of what these cases
     * assert -- the superseded mapping's identity was publicly reassignable, and adding one back to
     * make a test convenient would reopen the defect the test exists to prevent. Reflection confines
     * the privilege to the test.
     *
     * @param entity the instance to populate
     * @param memberName the name of the identity member to assign
     * @param sequence the value to assign
     * @throws AssertionError when the member cannot be found or assigned, which means the mapping was
     *     renamed and these cases no longer describe it
     */
    private static void assignSequence(Object entity, String memberName, long sequence) {
        try {
            Field member = entity.getClass().getDeclaredField(memberName);
            member.setAccessible(true);
            member.set(entity, sequence);
        } catch (ReflectiveOperationException cause) {
            throw new AssertionError(
                    "could not assign " + memberName + " on " + entity.getClass().getSimpleName(),
                    cause);
        }
    }
}
