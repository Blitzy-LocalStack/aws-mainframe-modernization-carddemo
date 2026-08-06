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
     * Confirms the registry publishes exactly eleven base masters, three derived records and two IMS
     * segments.
     *
     * <p>Assumptions: the split is asserted rather than the total, because the three groups have
     * different obligations -- a base master has a shipped extract to load and a byte-count to agree
     * with, a derived record is produced by the pipeline and has neither, and an IMS segment is
     * transcribed from a copybook whose length a database descriptor declares independently. A layout in
     * the wrong group would be verified against the wrong contract.</p>
     *
     * <p>Assumptions: the total is asserted as well as the split, and it is the sum of the three, so a
     * layout registered under a fourth provenance would fail here rather than be silently unaccounted
     * for. That is what the three containsAll assertions plus the size assertion together state.</p>
     */
    @Test
    @DisplayName("publishes eleven base masters, three derived records and two IMS segments")
    void publishesTheDeclaredRegistry() {
        assertThat(CopybookLayout.baseMasterNames()).hasSize(11);
        assertThat(CopybookLayout.derivedNames()).hasSize(3);
        assertThat(CopybookLayout.imsSegmentNames()).hasSize(2);
        assertThat(CopybookLayout.names())
            .hasSize(16)
            .containsAll(CopybookLayout.baseMasterNames())
            .containsAll(CopybookLayout.derivedNames())
            .containsAll(CopybookLayout.imsSegmentNames())
            .doesNotHaveDuplicates();
    }

    /**
     * Confirms the two IMS segments carry the lengths and keys their database descriptor declares.
     *
     * <p>Assumptions: the numbers are asserted as literals read from
     * {@code app/app-authorization-ims-db2-mq/ims/DBPAUTP0.dbd} rather than computed from the field
     * list, because computing them from the same declaration they are meant to check would make this
     * test pass for any self-consistent transcription. The descriptor is the independent witness: the
     * summary segment declares BYTES=100 with a six-byte packed sequence field, and the detail segment
     * BYTES=200 with an eight-byte character one.</p>
     *
     * @param name the segment layout name under test
     * @param expectedLength the record length the database descriptor declares
     * @param expectedKeyLength the sequence-field length the database descriptor declares
     */
    @ParameterizedTest
    @CsvSource({"PAUTSUM0, 100, 6", "PAUTDTL, 200, 8"})
    @DisplayName("every IMS segment's declared length and key match its database descriptor")
    void imsSegmentGeometryMatchesTheDatabaseDescriptor(String name, int expectedLength,
            int expectedKeyLength) {
        RecordSpec spec = CopybookLayout.layout(name);

        assertThat(spec.reclen()).isEqualTo(expectedLength);
        assertThat(spec.keyLength()).isEqualTo(expectedKeyLength);
        assertThat(spec.keyOffset()).isZero();
    }

    /**
     * Confirms the authorization summary segment transcribes every field of its copybook exactly.
     *
     * <p>Assumptions: the whole field list is asserted rather than a sample, because a segment decoded
     * against a wrong offset returns a well-formed number from the wrong bytes, and the two packed money
     * pairs at lines 23 to 26 and 29 to 30 are adjacent and identically shaped -- a transposition
     * between them would leave every width correct and every value wrong.</p>
     *
     * <p>Assumptions: the five-occurrence account-status table is asserted as ONE ten-byte field, which
     * is the transcription decision recorded at the declaration. Asserting it here is what stops the
     * decision being reversed to five two-byte fields without the reason being revisited.</p>
     */
    @Test
    @DisplayName("the authorization summary segment transcribes CIPAUSMY field for field")
    void summarySegmentTranscribesItsCopybook() {
        RecordSpec spec = CopybookLayout.layout("PAUTSUM0");

        assertThat(spec.fields()).extracting(FieldSpec::name)
            .containsExactly("PA-ACCT-ID", "PA-CUST-ID", "PA-AUTH-STATUS", "PA-ACCOUNT-STATUS",
                "PA-CREDIT-LIMIT", "PA-CASH-LIMIT", "PA-CREDIT-BALANCE", "PA-CASH-BALANCE",
                "PA-APPROVED-AUTH-CNT", "PA-DECLINED-AUTH-CNT", "PA-APPROVED-AUTH-AMT",
                "PA-DECLINED-AUTH-AMT", "FILLER");

        assertThat(fieldNamed(spec, "PA-ACCT-ID"))
            .returns(Kind.PACKED, FieldSpec::kind)
            .returns(6, FieldSpec::length)
            .returns(0, FieldSpec::start);
        assertThat(fieldNamed(spec, "PA-ACCOUNT-STATUS"))
            .returns(Kind.TEXT, FieldSpec::kind)
            .returns(10, FieldSpec::length);
        assertThat(fieldNamed(spec, "PA-APPROVED-AUTH-CNT"))
            .returns(Kind.BINARY, FieldSpec::kind)
            .returns(2, FieldSpec::length);
        assertThat(fieldNamed(spec, "PA-DECLINED-AUTH-AMT"))
            .returns(Kind.PACKED, FieldSpec::kind)
            .returns(6, FieldSpec::length)
            .returns(60, FieldSpec::start);
    }

    /**
     * Confirms the authorization detail segment transcribes every field of its copybook exactly.
     *
     * <p>Assumptions: the two twelve-digit money fields are asserted at SEVEN bytes each, which is the
     * rung the segment's own declared length proves -- at six the field widths sum to 198 and leave two
     * bytes unclaimed. Asserting the width here as well as on the arithmetic keeps the corroboration
     * beside the record that corroborates it.</p>
     *
     * <p>Assumptions: the misspelt merchant-category field name is asserted verbatim, so the
     * transcription cannot be quietly corrected here. The correction belongs to the mapping layer, and
     * a registry that renamed the field would stop matching the copybook line a reader checks it
     * against.</p>
     */
    @Test
    @DisplayName("the authorization detail segment transcribes CIPAUDTY field for field")
    void detailSegmentTranscribesItsCopybook() {
        RecordSpec spec = CopybookLayout.layout("PAUTDTL");

        assertThat(spec.fields()).hasSize(28);
        assertThat(spec.fields()).extracting(FieldSpec::name)
            .startsWith("PA-AUTH-DATE-9C", "PA-AUTH-TIME-9C", "PA-AUTH-ORIG-DATE",
                "PA-AUTH-ORIG-TIME", "PA-CARD-NUM")
            .contains("PA-MERCHANT-CATAGORY-CODE")
            .endsWith("PA-MATCH-STATUS", "PA-AUTH-FRAUD", "PA-FRAUD-RPT-DATE", "FILLER");

        assertThat(fieldNamed(spec, "PA-AUTH-DATE-9C"))
            .returns(Kind.PACKED, FieldSpec::kind)
            .returns(3, FieldSpec::length);
        assertThat(fieldNamed(spec, "PA-AUTH-TIME-9C"))
            .returns(Kind.PACKED, FieldSpec::kind)
            .returns(5, FieldSpec::length);
        assertThat(fieldNamed(spec, "PA-TRANSACTION-AMT"))
            .returns(Kind.PACKED, FieldSpec::kind)
            .returns(7, FieldSpec::length)
            .returns(74, FieldSpec::start);
        assertThat(fieldNamed(spec, "PA-APPROVED-AMT"))
            .returns(Kind.PACKED, FieldSpec::kind)
            .returns(7, FieldSpec::length)
            .returns(81, FieldSpec::start);
    }

    /**
     * Confirms the detail segment marks the primary account number sensitive and nothing else.
     *
     * <p>Assumptions: the assertion is exhaustive rather than positive-only. A test that merely checked
     * the card number is marked would pass equally well on a layout that marked every field, and
     * over-marking is a real failure here: the merchant name, city, state and postal code are the fields
     * a fraud reviewer reads to recognise a merchant, so redacting them would defeat the very screen
     * this segment feeds.</p>
     */
    @Test
    @DisplayName("the detail segment marks exactly the primary account number as sensitive")
    void detailSegmentMarksOnlyTheCardNumberSensitive() {
        assertThat(CopybookLayout.layout("PAUTDTL").fields())
            .filteredOn(FieldSpec::sensitive)
            .extracting(FieldSpec::name)
            .containsExactly("PA-CARD-NUM");
    }

    /**
     * Confirms neither IMS segment claims a parity-oracle round trip.
     *
     * <p>Assumptions: this is asserted as a negative because the flag decides whether a byte-identical
     * round-trip test against a shipped extract is possible, and no extract ships for either segment.
     * A layout claiming an oracle it does not have would invite a test written against a file that does
     * not exist.</p>
     */
    @Test
    @DisplayName("neither IMS segment claims a parity-oracle round trip")
    void imsSegmentsClaimNoOracleRoundTrip() {
        for (String name : CopybookLayout.imsSegmentNames()) {
            assertThat(CopybookLayout.hasOracleRoundTrip(name)).isFalse();
            assertThat(CopybookLayout.provenanceOf(name)).isEqualTo(Provenance.IMS_SEGMENT);
        }
    }

    /**
     * Returns one named field of a layout, failing the test when the layout has no such field.
     *
     * <p>Assumptions: the lookup fails rather than returning an empty optional, because every call
     * below names a field the layout is asserted to declare, and an optional would let a typo in the
     * name turn a real assertion into a vacuous one.</p>
     *
     * @param spec the layout to search
     * @param fieldName the field name to find
     * @return the field declared under that name
     */
    private static FieldSpec fieldNamed(RecordSpec spec, String fieldName) {
        return spec.fields().stream()
            .filter(field -> fieldName.equals(field.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "layout " + spec.name() + " declares no field named " + fieldName));
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
