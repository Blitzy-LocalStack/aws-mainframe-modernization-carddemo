package com.carddemo.common.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CopybookLayout.FieldSpec;
import com.carddemo.common.codec.CopybookLayout.Kind;
import com.carddemo.common.codec.CopybookLayout.LayoutException;
import com.carddemo.common.codec.CopybookLayout.Provenance;
import com.carddemo.common.codec.CopybookLayout.RecordSpec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the layout registry: that every registered record is geometrically sound, that the declared
 * record lengths match the reference contract, and that the width arithmetic each numeric regime uses
 * is the one its storage form requires.
 *
 * <p>Assumptions: this registry is the single source of every byte offset in the migration, so an
 * undetected error here mis-aligns a field in a way that still returns well-formed values. The tests
 * therefore assert STRUCTURAL properties over the whole registry rather than sampling one layout: every
 * field contiguous, no gap, no overlap, the last field ending exactly at the declared length. A property
 * asserted over all fourteen layouts cannot be satisfied by a layout that happens to be right.</p>
 *
 * <p>Assumptions: the eleven declared record lengths are asserted as literals against the dataset
 * contract documented in the data-migration package, because those lengths are the one fact two
 * independent artefacts agree on -- the copybook field sums and the shipped extract byte counts -- and
 * a literal here is what makes a drift in either of them fail at build time.</p>
 */
class CopybookLayoutTest {

    /**
     * Supplies every registered layout name, so a structural property is asserted over all of them.
     *
     * @return every registered layout name as a {@link List}
     */
    private static List<String> everyLayoutName() {
        return CopybookLayout.names();
    }

    /**
     * Confirms the registry publishes exactly the eleven base masters and the three derived records.
     *
     * <p>Assumptions: the split is asserted rather than the total, because the two groups have different
     * obligations -- a base master has a shipped extract to load and a byte-count to agree with, while a
     * derived record is produced by the pipeline and has neither. A layout in the wrong group would be
     * verified against the wrong contract.</p>
     */
    @Test
    @DisplayName("publishes eleven base masters and three derived records, and nothing else")
    void publishesTheDeclaredRegistry() {
        assertThat(CopybookLayout.baseMasterNames()).hasSize(11);
        assertThat(CopybookLayout.derivedNames()).hasSize(3);
        assertThat(CopybookLayout.names())
            .hasSize(14)
            .containsAll(CopybookLayout.baseMasterNames())
            .containsAll(CopybookLayout.derivedNames())
            .doesNotHaveDuplicates();
    }

    /**
     * Confirms each group's provenance marking agrees with the group it is published in.
     *
     * @param name the registered layout name under test
     */
    @ParameterizedTest
    @MethodSource("everyLayoutName")
    @DisplayName("every layout's provenance agrees with the group it is published in")
    void provenanceAgreesWithTheGroup(String name) {
        Provenance provenance = CopybookLayout.provenanceOf(name);

        if (CopybookLayout.baseMasterNames().contains(name)) {
            assertThat(provenance).isEqualTo(Provenance.BASE_MASTER);
        } else {
            assertThat(provenance).isNotEqualTo(Provenance.BASE_MASTER);
        }
    }

    /**
     * Confirms every registered layout has fields covering its declared length with no gap or overlap.
     *
     * <p>Assumptions: contiguity is the property that matters, and it is checked by walking the fields
     * in declared order and requiring each start to equal the running cursor. A gap means a byte no
     * field claims, which is a dropped column; an overlap means two fields reading the same byte, which
     * is a corrupted one. Both are invisible from any single field's own declaration.</p>
     *
     * @param name the registered layout name under test
     */
    @ParameterizedTest
    @MethodSource("everyLayoutName")
    @DisplayName("every layout covers its declared length contiguously, with no gap and no overlap")
    void everyLayoutIsContiguous(String name) {
        RecordSpec spec = CopybookLayout.layout(name);

        int cursor = 0;
        for (FieldSpec field : spec.fields()) {
            assertThat(field.start())
                .describedAs("%s field %s starts at the running cursor", name, field.name())
                .isEqualTo(cursor);
            assertThat(field.length())
                .describedAs("%s field %s has a positive length", name, field.name())
                .isPositive();
            assertThat(field.end())
                .describedAs("%s field %s ends where start plus length says", name, field.name())
                .isEqualTo(field.start() + field.length());
            cursor = field.end();
        }

        assertThat(cursor)
            .describedAs("%s fields sum to the declared record length", name)
            .isEqualTo(spec.reclen());
    }

    /**
     * Confirms every layout declares at least one field and a positive record length.
     *
     * @param name the registered layout name under test
     */
    @ParameterizedTest
    @MethodSource("everyLayoutName")
    @DisplayName("every layout declares a positive record length and at least one field")
    void everyLayoutIsNonDegenerate(String name) {
        RecordSpec spec = CopybookLayout.layout(name);

        assertThat(spec.reclen()).isPositive();
        assertThat(spec.fields()).isNotEmpty();
        assertThat(spec.name()).isEqualTo(name);
    }

    /**
     * Confirms every layout's declared key lies inside the record it keys.
     *
     * <p>Assumptions: a key that ran past the record end would produce a read of bytes the record does
     * not contain, which is exactly the defect the reference baseline's own export program carries -- a
     * declared key that exists only in working storage. Asserting the key fits is how that class of
     * error is kept out of the target.</p>
     *
     * @param name the registered layout name under test
     */
    @ParameterizedTest
    @MethodSource("everyLayoutName")
    @DisplayName("every declared key lies wholly inside the record it keys")
    void everyKeyLiesInsideItsRecord(String name) {
        RecordSpec spec = CopybookLayout.layout(name);

        assertThat(spec.keyOffset()).isNotNegative();
        assertThat(spec.keyLength()).isPositive();
        assertThat(spec.keyOffset() + spec.keyLength()).isLessThanOrEqualTo(spec.reclen());
    }

    /**
     * Confirms every field name inside a layout is distinct, so a lookup can never be ambiguous.
     *
     * @param name the registered layout name under test
     */
    @ParameterizedTest
    @MethodSource("everyLayoutName")
    @DisplayName("field names are distinct within a layout")
    void fieldNamesAreDistinctWithinALayout(String name) {
        List<String> fieldNames = CopybookLayout.layout(name).fields().stream()
            .map(FieldSpec::name)
            .toList();

        assertThat(fieldNames).doesNotHaveDuplicates();
    }

    /**
     * Confirms every declared record length matches the reference dataset contract.
     *
     * <p>Assumptions: these eleven numbers are corroborated by two unrelated sources -- the copybook
     * field sums and the shipped extract byte counts, each of which divides exactly by its record
     * length. Writing them as literals here makes a drift in the registry fail immediately rather than
     * at the first load of a real extract.</p>
     *
     * @param name the registered layout name under test
     * @param expectedLength the record length the dataset contract declares
     */
    @ParameterizedTest
    @CsvSource({
        "ACCOUNT, 300", "CARD, 150", "CUSTOMER, 500", "XREF, 50",
        "DALYTRAN, 350", "TRAN, 350", "DISGROUP, 50", "TCATBAL, 50",
        "SECUSER, 80", "TRANCAT, 60", "TRANTYPE, 60",
    })
    @DisplayName("every base master's declared length matches the dataset contract")
    void baseMasterLengthsMatchTheDatasetContract(String name, int expectedLength) {
        assertThat(CopybookLayout.layout(name).reclen()).isEqualTo(expectedLength);
    }

    /**
     * Confirms the three derived records carry the lengths their producing pipeline declares.
     *
     * <p>Assumptions: the reject record's 430 bytes is the one worth naming -- it is the 350-byte daily
     * transaction plus the 80 bytes of reason data the posting program appends, so a change to either
     * component must show up here.</p>
     *
     * @param name the derived layout name under test
     * @param expectedLength the record length the pipeline declares
     */
    @ParameterizedTest
    @CsvSource({"TRNX, 350", "REJECT, 430", "INTTRAN, 350"})
    @DisplayName("every derived record's declared length matches its producing pipeline")
    void derivedLengthsMatchTheirPipeline(String name, int expectedLength) {
        assertThat(CopybookLayout.layout(name).reclen()).isEqualTo(expectedLength);
    }

    /**
     * Confirms the width arithmetic of each numeric regime is the one its storage form requires.
     *
     * <p>Assumptions: the three regimes are genuinely different and confusing them is a silent
     * mis-alignment. A zoned field occupies one byte per digit; a packed field occupies one nibble per
     * digit plus a sign nibble, rounded up to a whole byte; a binary field occupies the smallest word
     * that holds its digit range. Asserting all three from one entry point is what shows they are not
     * interchangeable.</p>
     */
    @Test
    @DisplayName("each numeric regime computes the width its storage form requires")
    void eachNumericRegimeComputesItsOwnWidth() {
        assertThat(CopybookLayout.zonedWidth(10, 2)).isEqualTo(12);
        assertThat(CopybookLayout.zonedWidth(9, 2)).isEqualTo(11);
        assertThat(CopybookLayout.zonedWidth(4, 2)).isEqualTo(6);

        assertThat(CopybookLayout.packedWidth(10, 2)).isEqualTo(7);
        assertThat(CopybookLayout.packedWidth(9, 2)).isEqualTo(6);
        assertThat(CopybookLayout.packedWidth(4, 2)).isEqualTo(4);

        assertThat(CopybookLayout.widthOf(Kind.ZONED, 10, 2))
            .isEqualTo(CopybookLayout.zonedWidth(10, 2));
        assertThat(CopybookLayout.widthOf(Kind.PACKED, 10, 2))
            .isEqualTo(CopybookLayout.packedWidth(10, 2));
    }

    /**
     * Confirms a packed field is never wider than the zoned field of the same digit count, and is
     * strictly narrower from three digits upward.
     *
     * <p>Assumptions: the boundary is at three digits and is worth stating precisely rather than
     * approximating as "packed is smaller". A packed field stores one nibble per digit plus a sign
     * nibble rounded up to a whole byte, so its width is the ceiling of digits-plus-one over two: one
     * digit needs one byte and two digits need two, exactly as zoned does, and only from three digits
     * does the packing begin to save anything. Asserting the strict inequality at every size would be a
     * false claim that happens to hold for the sizes this migration uses, which is the kind of test that
     * fails later for a reason unrelated to the change that broke it.</p>
     *
     * @param intDigits the integer digit count under test
     * @param decDigits the decimal digit count under test
     */
    @ParameterizedTest
    @CsvSource({"1, 0", "2, 0", "3, 0", "4, 2", "9, 2", "10, 2", "15, 0", "16, 2", "18, 0"})
    @DisplayName("a packed field is never wider than zoned, and is narrower from three digits upward")
    void packedIsNeverWiderThanZoned(int intDigits, int decDigits) {
        int zoned = CopybookLayout.zonedWidth(intDigits, decDigits);
        int packed = CopybookLayout.packedWidth(intDigits, decDigits);

        assertThat(packed).isLessThanOrEqualTo(zoned);
        if (intDigits + decDigits >= 3) {
            assertThat(packed).isLessThan(zoned);
        } else {
            assertThat(packed).isEqualTo(zoned);
        }
    }

    /**
     * Confirms a digit count beyond the platform maximum is refused rather than silently narrowed.
     *
     * <p>Assumptions: eighteen digit positions is the reference platform's limit for a packed field, and
     * a specification asking for nineteen has no valid storage form. Refusing it is what stops a layout
     * from being registered with a width that cannot hold the values it claims to.</p>
     */
    @Test
    @DisplayName("a digit count beyond the platform maximum is refused")
    void digitCountBeyondTheMaximumIsRefused() {
        assertThatThrownBy(() -> CopybookLayout.packedWidth(18, 2))
            .isInstanceOf(LayoutException.class)
            .hasMessageContaining("18");
        assertThat(CopybookLayout.packedWidth(16, 2)).isEqualTo(10);
    }

    /**
     * Confirms a field can be looked up by name and that an unknown name is refused.
     *
     * <p>Assumptions: refusal is asserted alongside the successful lookup because a lookup that returned
     * a null or an empty specification for an unknown name would let a typo read offset zero, which is a
     * plausible-looking value from the wrong field.</p>
     */
    @Test
    @DisplayName("a field is retrievable by name and an unknown name is refused")
    void fieldLookupIsExactAndRefusesTheUnknown() {
        RecordSpec account = CopybookLayout.layout("ACCOUNT");
        FieldSpec first = account.fields().get(0);

        assertThat(account.field(first.name())).isEqualTo(first);
        assertThatThrownBy(() -> account.field("NOT-A-FIELD"))
            .isInstanceOf(LayoutException.class);
    }

    /**
     * Confirms an unknown layout name is refused rather than silently yielding an empty specification.
     */
    @Test
    @DisplayName("an unknown layout name is refused")
    void unknownLayoutNameIsRefused() {
        assertThatThrownBy(() -> CopybookLayout.layout("NOT-A-LAYOUT"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms the published field and record lists cannot be altered through their accessors.
     *
     * <p>Assumptions: the registry is shared static state read by every reader in the migration, so a
     * caller that could mutate a returned list would change the offsets every later reader sees. The
     * immutability is therefore a correctness property and not a stylistic one.</p>
     */
    @Test
    @DisplayName("the published registry and field lists are immutable")
    void publishedListsAreImmutable() {
        assertThatThrownBy(() -> CopybookLayout.names().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> CopybookLayout.baseMasterNames().clear())
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> CopybookLayout.layout("ACCOUNT").fields().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Confirms the sensitive marking is carried by the fields that must never be rendered in full.
     *
     * <p>Assumptions: the marking is what a mapping layer reads to decide masking, so a field that lost
     * it would be rendered in full by code that is doing exactly what it was told. Asserting that the
     * card and account layouts each carry at least one marked field pins the mechanism without encoding
     * the whole list here, which belongs to the layouts themselves.</p>
     */
    @Test
    @DisplayName("the layouts carrying protected values mark at least one field sensitive")
    void protectedLayoutsMarkSensitiveFields() {
        assertThat(CopybookLayout.layout("CARD").fields())
            .anySatisfy(field -> assertThat(field.sensitive()).isTrue());
        assertThat(CopybookLayout.layout("CUSTOMER").fields())
            .anySatisfy(field -> assertThat(field.sensitive()).isTrue());
    }

    /**
     * Confirms a specification relocated to a new offset keeps every other declared property.
     *
     * <p>Assumptions: relocation is how a derived record reuses a base master's field declarations at a
     * different position, so anything it altered besides the offset would silently change the meaning
     * of the reused field.</p>
     */
    @Test
    @DisplayName("relocating a field changes only its offset")
    void relocationChangesOnlyTheOffset() {
        FieldSpec original = CopybookLayout.layout("ACCOUNT").fields().get(1);
        FieldSpec moved = original.relocatedTo(original.start() + 100);

        assertThat(moved.start()).isEqualTo(original.start() + 100);
        assertThat(moved.length()).isEqualTo(original.length());
        assertThat(moved.kind()).isEqualTo(original.kind());
        assertThat(moved.name()).isEqualTo(original.name());
        assertThat(moved.signed()).isEqualTo(original.signed());
        assertThat(moved.sensitive()).isEqualTo(original.sensitive());
        assertThat(moved.end()).isEqualTo(moved.start() + original.length());
    }

    /**
     * Confirms geometry validation accepts every registered layout, so the registry is self-consistent.
     *
     * <p>Assumptions: the registry exposes its own validator, and running it over every layout is a
     * stronger statement than the contiguity walk above because it also applies whatever additional
     * checks the validator carries. Both are kept: the walk names the offending field when it fails,
     * while the validator asserts the registry against its own rules.</p>
     *
     * @param name the registered layout name under test
     */
    @ParameterizedTest
    @MethodSource("everyLayoutName")
    @DisplayName("every registered layout passes its own geometry validation")
    void everyLayoutPassesItsOwnGeometryValidation(String name) {
        RecordSpec spec = CopybookLayout.layout(name);

        assertThat(spec.validateGeometry()).isSameAs(spec);
    }

    /**
     * Confirms the oracle-round-trip marking is answered for every registered layout.
     *
     * <p>Assumptions: the marking records whether a shipped extract exists to compare a decode against,
     * which is what decides whether a byte-identical round-trip test is possible for that layout. The
     * query must therefore answer for every name rather than throwing for some.</p>
     *
     * @param name the registered layout name under test
     */
    @ParameterizedTest
    @MethodSource("everyLayoutName")
    @DisplayName("the oracle-round-trip marking is answered for every layout")
    void oracleMarkingIsAnsweredForEveryLayout(String name) {
        assertThat(CopybookLayout.hasOracleRoundTrip(name)).isIn(true, false);
    }
}
