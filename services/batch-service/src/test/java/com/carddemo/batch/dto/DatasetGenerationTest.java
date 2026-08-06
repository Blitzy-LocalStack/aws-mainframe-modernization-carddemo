package com.carddemo.batch.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.dto.DatasetGeneration.GenerationReference;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Holds {@link DatasetGeneration} to the baseline evidence it encodes: ten families, two relative
 * reference forms, a partition date derived from either token layout, and a key prefix that carries
 * no configuration.
 *
 * <p>Assumptions: two of the assertions below exist because of a specific, documented way this file
 * can go wrong rather than because of a general wish for coverage.
 * {@link #declaresExactlyTenFamiliesGuardingAgainstTheSixFamilyUndercount()} pins the family count
 * because six is the number a careful reader arrives at from {@code app/jcl/DEFGDGB.jcl} alone, and
 * a later edit that trimmed the enumeration to match a narrower document would otherwise compile,
 * pass every other test here, and silently leave four families without a retention rule.
 * {@link #partitionDateDerivesTheSameDateFromBothObservedTokenLayouts(String, String)} pins both
 * derivation branches because an implementation that handled only the separated layout would pass
 * any test written with a separated token and fail on
 * {@code app/jcl/INTCALC.jcl:22}'s {@code PARM='2022071800'}, which is the layout the baseline
 * actually supplies to the job that writes a generation.</p>
 *
 * <p>Alternatives Considered: asserting the ten base names by looping over the enumeration and
 * checking only that each is non-blank and starts with the common qualifier. Rejected, because that
 * assertion cannot disagree with a typo: a base name mistyped in the enumeration would satisfy it.
 * The literals below are typed from the JCL independently of the enumeration, so the comparison has
 * two sources and can therefore fail.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the parameterised methods below carry their own.</p>
 */
class DatasetGenerationTest {

    /**
     * The number of generation-dataset families the reference baseline defines, ten.
     *
     * <p>Assumptions: six from {@code app/jcl/DEFGDGB.jcl} at lines 25, 31, 37, 43, 49 and 55, three
     * from {@code app/jcl/DEFGDGD.jcl} at lines 28, 51 and 74, and one from
     * {@code app/jcl/DALYREJS.jcl} at line 25.</p>
     */
    private static final int BASELINE_FAMILY_COUNT = 10;

    /**
     * The number of relative generation reference forms the baseline uses, two.
     *
     * <p>Assumptions: a search of all thirty-eight members of {@code app/jcl} finds {@code (0)} and
     * {@code (+1)} and nothing else; there is no {@code (-1)} anywhere.</p>
     */
    private static final int BASELINE_REFERENCE_FORM_COUNT = 2;

    /**
     * The retention limit the baseline declares on every family, five.
     *
     * <p>Assumptions: {@code LIMIT(5)}, for example at {@code app/jcl/DEFGDGB.jcl:26}.</p>
     */
    private static final int BASELINE_RETENTION_LIMIT = 5;

    /**
     * The compact business-date token the baseline's interest job supplies.
     *
     * <p>Assumptions: {@code app/jcl/INTCALC.jcl:22} reads {@code PARM='2022071800'} -- eight date
     * digits followed by two further characters, ten in all.</p>
     */
    private static final String COMPACT_TOKEN = "2022071800";

    /**
     * The separated business-date token the orchestration state supplies as a container override.
     */
    private static final String SEPARATED_TOKEN = "2022-07-18";

    /**
     * The partition date both observed token layouts must derive.
     */
    private static final String EXPECTED_PARTITION_DATE = "2022-07-18";

    /**
     * Verifies that the enumeration declares exactly ten families, which is the guard against the
     * six-family undercount.
     *
     * <p>Assumptions: this is the single most load-bearing assertion in this class. The four families
     * missing from a six-family reading are the two reference backups beyond the first, the
     * disclosure-group backup and the reject stream, and a step writing into an unprovisioned prefix
     * does not fail -- it accumulates generations with no retention rule.</p>
     */
    @Test
    void declaresExactlyTenFamiliesGuardingAgainstTheSixFamilyUndercount() {
        assertThat(DatasetFamily.values()).hasSize(BASELINE_FAMILY_COUNT);
    }

    /**
     * Verifies that every family carries its baseline generation-data-group base name character for
     * character.
     *
     * <p>Assumptions: the two shapes most likely to be regularised are asserted deliberately -- the
     * two-level {@code TRANSACT.BKUP} suffix and the three-level {@code TRANCATG.PS.BKUP}, whose
     * {@code .PS.} qualifier appears on no other family and is dropped from that family's path
     * segment but kept in its base name.</p>
     */
    @Test
    void everyFamilyCarriesItsBaselineBaseNameVerbatim() {
        assertThat(DatasetFamily.TRANSACT_BKUP.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.TRANSACT.BKUP");
        assertThat(DatasetFamily.TRANSACT_DALY.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.TRANSACT.DALY");
        assertThat(DatasetFamily.TRANREPT.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.TRANREPT");
        assertThat(DatasetFamily.TCATBALF_BKUP.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.TCATBALF.BKUP");
        assertThat(DatasetFamily.SYSTRAN.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.SYSTRAN");
        assertThat(DatasetFamily.TRANSACT_COMBINED.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.TRANSACT.COMBINED");
        assertThat(DatasetFamily.TRANTYPE_BKUP.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.TRANTYPE.BKUP");
        assertThat(DatasetFamily.TRANCATG_BKUP.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.TRANCATG.PS.BKUP");
        assertThat(DatasetFamily.DISCGRP_BKUP.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.DISCGRP.BKUP");
        assertThat(DatasetFamily.DALYREJS.mainframeBaseName())
                .isEqualTo("AWS.M2.CARDDEMO.DALYREJS");
    }

    /**
     * Verifies that no two families share a path segment.
     *
     * <p>Assumptions: two families resolving to one prefix would interleave their generations under
     * a single {@code dt=}/{@code gen=} tree, so one family's retention rule would age out the
     * other's objects and a reader walking the prefix would see records of two datasets as one.</p>
     */
    @Test
    void everyFamilyPathSegmentIsDistinct() {
        Set<String> distinctPathSegments = Set.copyOf(Arrays.stream(DatasetFamily.values())
                .map(DatasetFamily::pathSegment)
                .toList());
        assertThat(distinctPathSegments).hasSize(BASELINE_FAMILY_COUNT);
    }

    /**
     * Verifies that no two families share a baseline base name.
     */
    @Test
    void everyFamilyBaseNameIsDistinct() {
        Set<String> distinctBaseNames = Set.copyOf(Arrays.stream(DatasetFamily.values())
                .map(DatasetFamily::mainframeBaseName)
                .toList());
        assertThat(distinctBaseNames).hasSize(BASELINE_FAMILY_COUNT);
    }

    /**
     * Verifies that a family's path segment is its domain and its dataset segment joined, and that
     * it ends with a separator.
     *
     * <p>Assumptions: the trailing separator is what makes this value byte-identical to the prefix
     * the authored {@code infra/modules/s3-datasets} module publishes for the same family, so the
     * assertion is on the composed shape rather than on the two parts alone.</p>
     *
     * @param family one of the ten families, supplied by the enumeration source so that a family
     *     added later is asserted without this test being edited
     */
    @ParameterizedTest
    @EnumSource(DatasetFamily.class)
    void familyPathSegmentComposesDomainAndDatasetAndEndsWithASeparator(DatasetFamily family) {
        assertThat(family.pathSegment())
                .isEqualTo(family.domain() + "/" + family.datasetSegment() + "/")
                .endsWith("/");
        assertThat(family.domain()).doesNotContain("/");
        assertThat(family.datasetSegment()).doesNotContain("/");
    }

    /**
     * Verifies that the ten families split across the three owning domains as six, three and one.
     *
     * <p>Assumptions: the split is stated in the enumeration's own documentation so that the total
     * can be verified by addition, and it is asserted here so the two cannot drift. The domains
     * follow the owning bounded context's schema, which is why {@code reporting} appears once.</p>
     */
    @Test
    void familiesSplitAcrossDomainsAsSixLedgerThreeReferenceAndOneReporting() {
        assertThat(countFamiliesInDomain("ledger")).isEqualTo(6);
        assertThat(countFamiliesInDomain("reference")).isEqualTo(3);
        assertThat(countFamiliesInDomain("reporting")).isEqualTo(1);
    }

    /**
     * Verifies that every family resolves from its own base name back to itself.
     *
     * @param family one of the ten families, supplied by the enumeration source
     */
    @ParameterizedTest
    @EnumSource(DatasetFamily.class)
    void resolveByMainframeBaseNameRoundTripsEveryFamily(DatasetFamily family) {
        // WHY : Assumptions: identity rather than equality is asserted, because an enumeration
        //       constant is a singleton and a resolver returning some other instance carrying equal
        //       state would satisfy an equality assertion while breaking every switch downstream.
        assertThat(DatasetFamily.resolveByMainframeBaseName(family.mainframeBaseName()))
                .isSameAs(family);
    }

    /**
     * Verifies that resolving an unknown base name is refused rather than defaulted.
     *
     * @param unknownBaseName a value that is not one of the ten base names
     */
    @ParameterizedTest
    @ValueSource(strings = {"AWS.M2.CARDDEMO.TRANSACT", "aws.m2.carddemo.systran", "", "   "})
    void resolveByMainframeBaseNameRejectsAnUnknownName(String unknownBaseName) {
        assertThatThrownBy(() -> DatasetFamily.resolveByMainframeBaseName(unknownBaseName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unrecognised generation-data-group base name");
    }

    /**
     * Verifies that resolving a null base name is refused with the same outcome as a misspelled one.
     */
    @Test
    void resolveByMainframeBaseNameRejectsNull() {
        assertThatThrownBy(() -> DatasetFamily.resolveByMainframeBaseName(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies that both observed business-date token layouts derive the one partition date.
     *
     * <p>Assumptions: this is the assertion that matters most in this class after the family count.
     * The compact layout is what {@code app/jcl/INTCALC.jcl:22} injects and the separated layout is
     * what the orchestration state supplies, so an implementation that handled either one alone
     * would be correct against half of the inputs the system actually produces.</p>
     *
     * @param token the business-date token, in one of the two observed layouts
     * @param expectedPartitionDate the separated date both layouts are required to derive
     */
    @ParameterizedTest
    @CsvSource({
        "2022071800, 2022-07-18",
        "2022-07-18, 2022-07-18",
        "1999123100, 1999-12-31",
        "1999-12-31, 1999-12-31",
    })
    void partitionDateDerivesTheSameDateFromBothObservedTokenLayouts(
            String token, String expectedPartitionDate) {
        DatasetGeneration coordinate =
                new DatasetGeneration(DatasetFamily.SYSTRAN, new BusinessDate(token), 1);
        assertThat(coordinate.partitionDate()).isEqualTo(expectedPartitionDate);
    }

    /**
     * Verifies that deriving the partition date leaves the business-date token byte-identical.
     *
     * <p>Assumptions: the token is concatenated verbatim into {@code TRAN-ID PIC X(16)} at
     * {@code app/cbl/CBACT04C.cbl:476-480}, so a derivation that normalised it in place would change
     * a stored identifier. This test calls every member that reads the token and then compares the
     * token to the literal it was constructed from.</p>
     */
    @Test
    void partitionDateLeavesTheBusinessDateTokenByteIdentical() {
        BusinessDate businessDate = new BusinessDate(COMPACT_TOKEN);
        DatasetGeneration coordinate =
                new DatasetGeneration(DatasetFamily.DALYREJS, businessDate, 1);

        assertThat(coordinate.partitionDate()).isEqualTo(EXPECTED_PARTITION_DATE);
        assertThat(coordinate.datePartitionSegment()).isEqualTo("dt=" + EXPECTED_PARTITION_DATE);
        assertThat(coordinate.keyPrefix()).contains(EXPECTED_PARTITION_DATE);

        // WHY : Assumptions: both the record's accessor and the originally constructed instance are
        //       compared, because the two would diverge under different faults. A derivation that
        //       re-stored a normalised token would fail the first; one that mutated the instance
        //       handed in would fail the second.
        assertThat(coordinate.businessDate().token()).isEqualTo(COMPACT_TOKEN);
        assertThat(businessDate.token()).isEqualTo(COMPACT_TOKEN);
    }

    /**
     * Verifies that a ten-character token matching neither layout is refused when a partition date is
     * asked for.
     *
     * <p>Assumptions: the refusal is an illegal-state failure rather than an illegal-argument one,
     * because the coordinate was validly constructed and the method takes no argument -- it is the
     * held state, not a parameter, that cannot be rendered.</p>
     *
     * @param unrenderableToken a ten-character token that is neither hyphen-separated at the two
     *     expected indices nor led by eight digits
     */
    @ParameterizedTest
    @ValueSource(strings = {"20-22-0718", "ABCDEFGHIJ", "2022/07/18", "202207-800", "----------"})
    void partitionDateRejectsATokenMatchingNeitherLayout(String unrenderableToken) {
        DatasetGeneration coordinate = new DatasetGeneration(
                DatasetFamily.SYSTRAN, new BusinessDate(unrenderableToken), 1);

        assertThatThrownBy(coordinate::partitionDate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(unrenderableToken)
                .hasMessageContaining("neither the separated layout");
    }

    /**
     * Verifies that the generation number is rendered zero-padded to exactly four digits.
     *
     * <p>Assumptions: the padding is what makes lexicographic key order agree with numeric order, so
     * the pairs asserted here span the widths that would disagree without it -- one digit, two
     * digits and the full four.</p>
     *
     * @param generationNumber the generation number to render
     * @param expectedSegment the segment that number is required to render as
     */
    @ParameterizedTest
    @CsvSource({
        "0, gen=0000",
        "1, gen=0001",
        "2, gen=0002",
        "10, gen=0010",
        "42, gen=0042",
        "999, gen=0999",
        "9999, gen=9999",
    })
    void generationSegmentIsZeroPaddedToFourDigits(int generationNumber, String expectedSegment) {
        DatasetGeneration coordinate = new DatasetGeneration(
                DatasetFamily.TRANREPT, new BusinessDate(SEPARATED_TOKEN), generationNumber);
        assertThat(coordinate.generationSegment()).isEqualTo(expectedSegment);
    }

    /**
     * Verifies that padded generation segments sort in the same order as the numbers they render.
     *
     * <p>Assumptions: this is the property the padding exists for, and it is asserted directly rather
     * than inferred from the widths above. Without padding, {@code gen=10} precedes {@code gen=2} in
     * the lexicographic ordering an object listing offers, so a caller taking the trailing key as the
     * current generation would read the wrong one.</p>
     */
    @Test
    void paddedGenerationSegmentsSortInNumericOrder() {
        BusinessDate businessDate = new BusinessDate(SEPARATED_TOKEN);
        String second = new DatasetGeneration(DatasetFamily.SYSTRAN, businessDate, 2)
                .generationSegment();
        String tenth = new DatasetGeneration(DatasetFamily.SYSTRAN, businessDate, 10)
                .generationSegment();
        assertThat(second).isLessThan(tenth);
    }

    /**
     * Verifies the complete rendered key prefix for one concrete coordinate.
     *
     * <p>Assumptions: the whole string is asserted rather than its parts, because the separators
     * between the segments are as much part of the convention as the segments themselves and an
     * assertion on the parts cannot detect a missing or doubled one.</p>
     */
    @Test
    void keyPrefixRendersTheCompleteCoordinateIncludingTheTrailingSeparator() {
        DatasetGeneration coordinate = new DatasetGeneration(
                DatasetFamily.DALYREJS, new BusinessDate(COMPACT_TOKEN), 1);

        assertThat(coordinate.keyPrefix()).isEqualTo("ledger/dalyrejs/dt=2022-07-18/gen=0001/");
    }

    /**
     * Verifies that the rendered prefix carries no bucket name, environment name or URI scheme.
     *
     * <p>Assumptions: those three are configuration, owned by the infrastructure module, and their
     * absence is what makes one coordinate valid unchanged in every environment. The assertion is
     * written as an absence check because the failure it guards against is an addition -- a later
     * author composing a convenience method that folded a bucket in.</p>
     *
     * @param family one of the ten families, so the absence holds for every prefix rather than for
     *     one sampled example
     */
    @ParameterizedTest
    @EnumSource(DatasetFamily.class)
    void keyPrefixCarriesNoBucketEnvironmentOrScheme(DatasetFamily family) {
        String prefix = new DatasetGeneration(family, new BusinessDate(SEPARATED_TOKEN), 1)
                .keyPrefix();

        assertThat(prefix)
                .doesNotContain("s3://")
                .doesNotContain("://")
                .doesNotContain("carddemo-datasets")
                .doesNotContain("arn:aws")
                .doesNotStartWith("/");
        assertThat(prefix).endsWith("/");
    }

    /**
     * Verifies that the rendered prefix has exactly the four segments the convention names.
     *
     * @param family one of the ten families
     */
    @ParameterizedTest
    @EnumSource(DatasetFamily.class)
    void keyPrefixHasExactlyFourSegments(DatasetFamily family) {
        String prefix = new DatasetGeneration(family, new BusinessDate(COMPACT_TOKEN), 7)
                .keyPrefix();

        // WHY : Assumptions: the trailing separator makes the split yield four entries rather than
        //       five, because a trailing empty field is discarded by this overload. Asserting four
        //       therefore also asserts that no segment is empty, which is what a doubled separator
        //       would produce.
        assertThat(prefix.split("/")).hasSize(4);
        assertThat(prefix).isEqualTo(family.pathSegment() + "dt=2022-07-18/gen=0007/");
    }

    /**
     * Verifies that the date partition segment carries the marker the convention names.
     */
    @Test
    void datePartitionSegmentCarriesTheDateMarker() {
        DatasetGeneration coordinate = new DatasetGeneration(
                DatasetFamily.TRANSACT_BKUP, new BusinessDate(SEPARATED_TOKEN), 1);
        assertThat(coordinate.datePartitionSegment()).isEqualTo("dt=2022-07-18");
    }

    /**
     * Verifies that the relative-reference enumeration declares exactly two forms.
     *
     * <p>Assumptions: two, because a search of all thirty-eight members of {@code app/jcl} finds
     * {@code (0)} and {@code (+1)} and no third spelling. A third constant would model a reference
     * the baseline never makes, and a reader would reasonably assume some step used it.</p>
     */
    @Test
    void generationReferenceDeclaresExactlyTwoForms() {
        assertThat(GenerationReference.values()).hasSize(BASELINE_REFERENCE_FORM_COUNT);
    }

    /**
     * Verifies that the two relative-reference forms carry the notations the baseline spells them
     * with.
     */
    @Test
    void generationReferenceCarriesTheBaselineNotations() {
        assertThat(GenerationReference.CURRENT.jclNotation()).isEqualTo("(0)");
        assertThat(GenerationReference.NEW.jclNotation()).isEqualTo("(+1)");
    }

    /**
     * Verifies that no relative-reference form spells a negative offset.
     *
     * <p>Assumptions: this is the companion to the count assertion above and fails differently. A
     * third constant added with a negative notation would fail the count; an existing constant
     * re-spelled to a negative offset would fail only this.</p>
     */
    @Test
    void noRelativeReferenceFormSpellsANegativeOffset() {
        for (GenerationReference reference : GenerationReference.values()) {
            assertThat(reference.jclNotation()).doesNotContain("-");
        }
    }

    /**
     * Verifies that a token whose characters name no day that exists is refused before it can become
     * a partition prefix, in both accepted layouts.
     *
     * <p>Assumptions: each token below is ten characters and passes the width check the token type
     * applies, and each also passes the digit-and-position shape test, so nothing before this
     * refusal can catch it. The refusal matters because the rendering it would otherwise produce is
     * accepted by the loader's own key pattern, so the objects would be written and then filed for
     * ever under a day that never occurred, with nothing reporting an error.</p>
     *
     * @param token a ten-character business-date token naming an impossible day, in either layout
     */
    @ParameterizedTest
    @ValueSource(strings = {"2022-99-99", "2022999900", "2022-13-01", "2022130100", "2022-02-30",
        "2022023000"})
    void anImpossibleDateIsRefusedBeforeItReachesAPartitionPrefix(String token) {
        DatasetGeneration coordinate =
                new DatasetGeneration(DatasetFamily.TRANSACT_BKUP, new BusinessDate(token), 1);

        assertThatThrownBy(coordinate::partitionDate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a day that exists");
        assertThatThrownBy(coordinate::keyPrefix)
                .as("the whole prefix must fail too, or a caller could route round the refusal")
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Verifies that the twenty-ninth of February is refused in a common year and accepted in a leap
     * year, which no digit-and-position test can distinguish.
     *
     * <p>Assumptions: this is the boundary that separates a shape test from a calendar one. Both
     * tokens are ten characters with digits in the same positions, so only a strict calendar
     * resolution tells them apart, and a lenient one would accept the first by rolling it into
     * March -- filing a generation under a date the caller never named.</p>
     */
    @Test
    void theLeapDayBoundaryIsResolvedStrictly() {
        DatasetGeneration commonYear =
                new DatasetGeneration(DatasetFamily.SYSTRAN, new BusinessDate("2022-02-29"), 1);
        assertThatThrownBy(commonYear::partitionDate).isInstanceOf(IllegalStateException.class);

        DatasetGeneration leapYear =
                new DatasetGeneration(DatasetFamily.SYSTRAN, new BusinessDate("2024-02-29"), 1);
        assertThat(leapYear.partitionDate()).isEqualTo("2024-02-29");
        assertThat(new DatasetGeneration(DatasetFamily.SYSTRAN, new BusinessDate("2024022900"), 1)
                .partitionDate())
                .as("the compact layout of the same real day must render identically")
                .isEqualTo("2024-02-29");
    }

    /**
     * Verifies that the retained-generation constant is the baseline's own retention limit.
     *
     * <p>Assumptions: five, from {@code LIMIT(5)} at {@code app/jcl/DEFGDGB.jcl:26} and on the other
     * nine defining statements. The constant exists so the batch code and the infrastructure module
     * state one number, and this assertion is what makes a change to it visible.</p>
     */
    @Test
    void retainedGenerationCountMatchesTheBaselineRetentionLimit() {
        assertThat(DatasetGeneration.RETAINED_GENERATION_COUNT)
                .isEqualTo(BASELINE_RETENTION_LIMIT);
    }

    /**
     * Verifies that the rendered generation width and the accepted range agree with each other.
     *
     * <p>Assumptions: the ceiling is the largest value the declared width can hold, so the two are
     * one fact. Asserting the relationship rather than the literal is what keeps a later widening of
     * one from silently leaving the other behind.</p>
     */
    @Test
    void acceptedGenerationRangeIsDerivedFromTheRenderedWidth() {
        assertThat(DatasetGeneration.GENERATION_DIGITS).isEqualTo(4);
        assertThat(DatasetGeneration.MINIMUM_GENERATION_NUMBER).isZero();
        assertThat(DatasetGeneration.MAXIMUM_GENERATION_NUMBER).isEqualTo(9999);
    }

    /**
     * Verifies that a coordinate without a family is refused.
     */
    @Test
    void constructorRejectsNullFamily() {
        BusinessDate businessDate = new BusinessDate(SEPARATED_TOKEN);
        assertThatThrownBy(() -> new DatasetGeneration(null, businessDate, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("family");
    }

    /**
     * Verifies that a coordinate without a business date is refused.
     */
    @Test
    void constructorRejectsNullBusinessDate() {
        assertThatThrownBy(() -> new DatasetGeneration(DatasetFamily.SYSTRAN, null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("business date");
    }

    /**
     * Verifies that a generation number outside the accepted range is refused at either end.
     *
     * <p>Assumptions: the value above the ceiling matters more than the one below the floor, because
     * it is the one that would otherwise still render. A five-digit segment formats successfully and
     * then sorts ahead of every four-digit one, so an unchecked value produces a prefix that looks
     * valid and lists in the wrong place.</p>
     *
     * @param outOfRangeGeneration a generation number outside the accepted range
     */
    @ParameterizedTest
    @ValueSource(ints = {-1, -42, 10000, 99999, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void constructorRejectsAGenerationNumberOutsideTheAcceptedRange(int outOfRangeGeneration) {
        BusinessDate businessDate = new BusinessDate(SEPARATED_TOKEN);
        assertThatThrownBy(() -> new DatasetGeneration(
                DatasetFamily.SYSTRAN, businessDate, outOfRangeGeneration))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the accepted range");
    }

    /**
     * Verifies that both ends of the accepted generation range are admitted.
     *
     * @param inRangeGeneration a generation number at or inside the accepted range boundary
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 9998, 9999})
    void constructorAcceptsBothEndsOfTheAcceptedRange(int inRangeGeneration) {
        DatasetGeneration coordinate = new DatasetGeneration(
                DatasetFamily.SYSTRAN, new BusinessDate(SEPARATED_TOKEN), inRangeGeneration);
        assertThat(coordinate.generationNumber()).isEqualTo(inRangeGeneration);
    }

    /**
     * Verifies that value equality is the contract, component by component.
     *
     * <p>Assumptions: token equality rather than derived-date equality is what the record compares.
     * Two coordinates whose tokens are spelled differently derive the same partition date and are
     * still not equal, and that asymmetry is deliberate: the token is part of a stored identifier
     * while the partition date is not.</p>
     */
    @Test
    void valueEqualityIsTheContract() {
        DatasetGeneration one = new DatasetGeneration(
                DatasetFamily.SYSTRAN, new BusinessDate(COMPACT_TOKEN), 3);
        DatasetGeneration same = new DatasetGeneration(
                DatasetFamily.SYSTRAN, new BusinessDate(COMPACT_TOKEN), 3);
        DatasetGeneration differentToken = new DatasetGeneration(
                DatasetFamily.SYSTRAN, new BusinessDate(SEPARATED_TOKEN), 3);

        assertThat(one).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(one).isNotEqualTo(differentToken);
        assertThat(one.partitionDate()).isEqualTo(differentToken.partitionDate());
    }

    /**
     * Verifies that the accessors return exactly what the constructor was handed.
     */
    @Test
    void accessorsReturnTheConstructedComponents() {
        BusinessDate businessDate = new BusinessDate(COMPACT_TOKEN);
        DatasetGeneration coordinate =
                new DatasetGeneration(DatasetFamily.TRANCATG_BKUP, businessDate, 55);

        assertThat(coordinate.family()).isSameAs(DatasetFamily.TRANCATG_BKUP);
        assertThat(coordinate.businessDate()).isSameAs(businessDate);
        assertThat(coordinate.generationNumber()).isEqualTo(55);
    }

    /**
     * Counts the families whose owning domain is the one named.
     *
     * @param domain the domain to count families for
     * @return the number of families declaring that domain
     */
    private static long countFamiliesInDomain(String domain) {
        return Arrays.stream(DatasetFamily.values())
                .filter(family -> family.domain().equals(domain))
                .count();
    }
}
