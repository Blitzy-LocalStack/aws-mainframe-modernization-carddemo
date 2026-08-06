package com.carddemo.batch.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.error.AbendDetail;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds {@link BatchErrorEvent} to the two contracts it exists to keep: that only a failure reaches
 * the terminal sink, and that nothing sensitive travels on it.
 *
 * <p>Assumptions: the two assertions that matter most in this class are
 * {@link #dataMinimisationTheComponentSetIsClosedAndCarriesNoIdentifierOrRecordImage()} and the three
 * completion-tier tests. Neither property is visible at the point where it would be broken. A seventh
 * component carrying an account identifier compiles, publishes and is read by every consumer without
 * complaint, and it only becomes a problem when somebody exports the sink into an incident record. A
 * catch route wired to the wrong branch publishes a clean tier that every consumer also accepts,
 * raising an operator for a run that succeeded. Both are therefore asserted mechanically here rather
 * than described in prose alone.</p>
 *
 * <p>Assumptions: this class necessarily spells out the very name fragments the type under test
 * forbids -- identifier names, credential names and validity-horizon names -- because the only way to
 * assert that a component is not named for one of them is to name it. Their presence in this file is
 * the assertion, not a breach of it; the type itself carries none of them.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the parameterised methods below carry their own.</p>
 */
class BatchErrorEventTest {

    /**
     * A run identifier that carries nothing sensitive and no digit at all.
     *
     * <p>Assumptions: every fixture value in this class is deliberately digit-free so that the
     * rendered-form assertion below can look for a long digit run and mean something by finding
     * none. A fixture containing digits of its own would make that assertion vacuous.</p>
     */
    private static final String RUN_ID = "run-nightly-alpha";

    /**
     * A step name matching the ledger column the event is joined through.
     */
    private static final String STEP_NAME = "PostTransactions";

    /**
     * A correlation identifier in the form the logging context carries.
     */
    private static final String CORRELATION_ID = "corr-alpha-bravo-charlie";

    /**
     * The abend detail a fixture uses where an abend analogue exists.
     *
     * <p>Assumptions: the four values sit inside the widths the reference declares at
     * {@code app/cpy/CSMSG02Y.cpy:21-29}, so the shared type carries them through without shortening
     * any of them and a comparison against the value supplied stays meaningful.</p>
     */
    private static final AbendDetail ABEND_DETAIL =
            new AbendDetail("0999", "CBTRN02C", "step ended abnormally", "abending program");

    /**
     * The component names this record declares, in declaration order, and no others.
     *
     * <p>Assumptions: this is a CLOSED expectation rather than a sample. It is the single assertion
     * that fails when a seventh component is added, whatever that component is called, which is why
     * it is stated as an exact ordered list instead of as a set of properties each component must
     * satisfy.</p>
     */
    private static final List<String> DECLARED_COMPONENT_NAMES = List.of(
            "runId", "stepName", "jobName", "returnCode", "correlationId", "abendDetail");

    /**
     * Lower-case name fragments that no component and no accessor of this record may contain.
     *
     * <p>Assumptions: the set names the values the migration reduces, suppresses or encrypts
     * everywhere else -- a card number, a primary account number, an account identifier, a customer
     * identifier, a national or government-issued identifier, a card verification value, a
     * credential -- together with the raw record image the reject path writes. A terminal error sink
     * inherits none of the controls those values are held under elsewhere, so the type under test
     * carries none of them and this list is how that is checked rather than trusted.</p>
     */
    private static final List<String> FORBIDDEN_NAME_FRAGMENTS = List.of(
            "card", "pan", "account", "customer", "national", "govt", "government",
            "cvv", "verification", "credential", "secret", "password", "raw", "image", "body");

    /**
     * Lower-case name fragments belonging to the validity-horizon resolution that this sink excludes.
     *
     * <p>Assumptions: the horizon attribute the migration prescribes answers a reply path's
     * question, where a requester has stopped waiting. This sink is terminal, so a horizon here would
     * license a consumer to discard diagnostics somebody is looking for. The fragment {@code expir}
     * is used rather than a whole word so that it also catches an expiration or an expires spelling.
     * </p>
     */
    private static final List<String> FORBIDDEN_HORIZON_FRAGMENTS =
            List.of("expir", "ttl", "timetolive", "stale");

    /**
     * The shortest digit run that could be one of the reference's numeric identifiers.
     *
     * <p>Assumptions: the narrowest of the three identifiers the migration derives from the reference
     * record layouts is the nine-digit customer identifier; the account identifier is eleven digits
     * and a card number is sixteen characters. A threshold of nine therefore catches the narrowest of
     * them and everything wider.</p>
     */
    private static final int SHORTEST_IDENTIFIER_DIGIT_RUN = 9;

    /**
     * Values shaped like an identifier, used to prove the rendered form carries no such value.
     *
     * <p>Assumptions: each is deliberately synthetic -- runs of zeros and an obvious placeholder --
     * so that this file carries no value resembling a real credential or a real account. The
     * assertion is that none of them appears in the rendered form of an instance that was never given
     * one.</p>
     */
    private static final List<String> IDENTIFIER_SHAPED_VALUES = List.of(
            "0000000000000000", "00000000000", "000000000", "REDACTED_CREDENTIAL_PLACEHOLDER");

    /**
     * Builds a fully populated event carrying an abend detail.
     *
     * @return an event whose six components are the fixture values declared on this class; never
     *     {@code null}
     */
    private static BatchErrorEvent fullyPopulatedEvent() {
        return new BatchErrorEvent(RUN_ID, STEP_NAME, BatchJobName.POST_TRANSACTIONS,
                BatchReturnCode.HARD_FAILURE, CORRELATION_ID, ABEND_DETAIL);
    }

    /**
     * Verifies that the clean tier is refused, because a success must never reach the terminal sink.
     */
    @Test
    void cleanTierIsRefusedBecauseASuccessMustNeverReachTheTerminalErrorSink() {
        assertThatThrownBy(() -> new BatchErrorEvent(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.CLEAN, CORRELATION_ID, ABEND_DETAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLEAN");
    }

    /**
     * Verifies that the warn tier is refused, because a run that correctly rejected records did its
     * job and is not a failure.
     */
    @Test
    void warnTierIsRefusedBecauseARunThatCorrectlyRejectedRecordsIsNotAFailure() {
        // WHY : Assumptions: the warn tier is the one a reference run reaches BY DESIGN, at
        //       app/cbl/CBTRN02C.cbl:229-230, where a non-zero reject count selects a code of four.
        //       It is therefore the tier most likely to be published here in error, and it is
        //       asserted separately from the clean tier rather than folded in with it.
        assertThatThrownBy(() -> new BatchErrorEvent(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.SOFT_WARN, CORRELATION_ID,
                ABEND_DETAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SOFT_WARN");
    }

    /**
     * Verifies that the failure tier constructs, and that it is the only tier that does.
     *
     * @param tier each declared completion tier in turn, so the partition is asserted across the
     *     whole enumeration rather than sampled at one constant
     */
    @ParameterizedTest
    @EnumSource(BatchReturnCode.class)
    void onlyATierThatStopsTheChainConstructs(BatchReturnCode tier) {
        // WHY : Alternatives Considered: asserting the failure tier alone. Rejected because the
        //       contract is a partition over the whole enumeration, so a tier added to
        //       BatchReturnCode would silently fall outside a test that named only one constant;
        //       driving the assertion from the enumeration itself cannot miss one.
        if (tier.permitsDownstreamRun()) {
            assertThatThrownBy(() -> new BatchErrorEvent(RUN_ID, STEP_NAME,
                    BatchJobName.POST_TRANSACTIONS, tier, CORRELATION_ID, ABEND_DETAIL))
                    .isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThatCode(() -> new BatchErrorEvent(RUN_ID, STEP_NAME,
                    BatchJobName.POST_TRANSACTIONS, tier, CORRELATION_ID, ABEND_DETAIL))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Verifies that an absent or blank run identifier is refused.
     *
     * @param candidate an absent, empty or whitespace-only run identifier
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    void runIdMustBePresentAndCarryMoreThanWhitespace(String candidate) {
        assertThatThrownBy(() -> new BatchErrorEvent(candidate, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, CORRELATION_ID,
                ABEND_DETAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    /**
     * Verifies that an absent or blank step name is refused.
     *
     * @param candidate an absent, empty or whitespace-only step name
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    void stepNameMustBePresentAndCarryMoreThanWhitespace(String candidate) {
        assertThatThrownBy(() -> new BatchErrorEvent(RUN_ID, candidate,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, CORRELATION_ID,
                ABEND_DETAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepName");
    }

    /**
     * Verifies that an absent or blank correlation identifier is refused.
     *
     * @param candidate an absent, empty or whitespace-only correlation identifier
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    void correlationIdMustBePresentAndCarryMoreThanWhitespace(String candidate) {
        // WHY : Assumptions: the correlation identifier is asserted as strictly as the two ledger
        //       keys because it is the ONLY route back to a run's log lines once a message has been
        //       pulled off a dead-letter queue without its attributes. A blank one would leave the
        //       event self-describing in form and useless in fact.
        assertThatThrownBy(() -> new BatchErrorEvent(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, candidate,
                ABEND_DETAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correlationId");
    }

    /**
     * Verifies that a value carrying surrounding whitespace is refused only when it is wholly blank,
     * and is otherwise carried through unaltered.
     */
    @Test
    void anIdentifierCarryingSurroundingWhitespaceIsCarriedUnchangedRatherThanTrimmed() {
        // WHY : Assumptions: this pins the documented decision NOT to normalise. Trimming would make
        //       the published identifier differ from the one the caller believes it published, which
        //       turns a caller's error into a join that silently returns nothing.
        BatchErrorEvent event = new BatchErrorEvent(" " + RUN_ID + " ", STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, CORRELATION_ID,
                ABEND_DETAIL);

        assertThat(event.runId()).isEqualTo(" " + RUN_ID + " ");
    }

    /**
     * Verifies that an absent job name is refused.
     */
    @Test
    void jobNameMustBePresent() {
        assertThatThrownBy(() -> new BatchErrorEvent(RUN_ID, STEP_NAME, null,
                BatchReturnCode.HARD_FAILURE, CORRELATION_ID, ABEND_DETAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobName");
    }

    /**
     * Verifies that an absent completion tier is refused.
     */
    @Test
    void returnCodeMustBePresent() {
        assertThatThrownBy(() -> new BatchErrorEvent(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, null, CORRELATION_ID, ABEND_DETAIL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("returnCode");
    }

    /**
     * Verifies that an absent abend detail is refused and that the rejection names the absent form to
     * use instead.
     */
    @Test
    void abendDetailMustBePresentAndTheRejectionNamesTheAbsentFormToUseInstead() {
        assertThatThrownBy(() -> new BatchErrorEvent(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, CORRELATION_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ABSENT_ABEND_DETAIL");
    }

    /**
     * Verifies that the published absent form is the reference's own blank block rather than an
     * absent value.
     */
    @Test
    void theAbsentAbendFormIsTheReferenceBlankBlockAndNotNull() {
        // WHY : Assumptions: the reference declares all four components of its abend block
        //       VALUE SPACES, at app/cpy/CSMSG02Y.cpy:23, :25, :27 and :29, so the block always
        //       exists and blank IS its empty form. This asserts that the migrated absent form is
        //       that same blank block, which is what makes requiring the component defensible.
        assertThat(BatchErrorEvent.ABSENT_ABEND_DETAIL).isNotNull();
        assertThat(BatchErrorEvent.ABSENT_ABEND_DETAIL.abendCode()).isEmpty();
        assertThat(BatchErrorEvent.ABSENT_ABEND_DETAIL.abendCulprit()).isEmpty();
        assertThat(BatchErrorEvent.ABSENT_ABEND_DETAIL.abendReason()).isEmpty();
        assertThat(BatchErrorEvent.ABSENT_ABEND_DETAIL.abendMsg()).isEmpty();
    }

    /**
     * Verifies that the factory for a failure with no abend analogue carries the absent form and
     * validates exactly as the constructor does.
     */
    @Test
    void withoutAbendDetailCarriesTheAbsentFormAndValidatesLikeTheConstructor() {
        BatchErrorEvent event = BatchErrorEvent.withoutAbendDetail(RUN_ID, STEP_NAME,
                BatchJobName.CALCULATE_INTEREST, BatchReturnCode.HARD_FAILURE, CORRELATION_ID);

        assertThat(event.abendDetail()).isEqualTo(BatchErrorEvent.ABSENT_ABEND_DETAIL);
        assertThat(event.jobName()).isEqualTo(BatchJobName.CALCULATE_INTEREST);

        assertThatThrownBy(() -> BatchErrorEvent.withoutAbendDetail(RUN_ID, STEP_NAME,
                BatchJobName.CALCULATE_INTEREST, BatchReturnCode.CLEAN, CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BatchErrorEvent.withoutAbendDetail("  ", STEP_NAME,
                BatchJobName.CALCULATE_INTEREST, BatchReturnCode.HARD_FAILURE, CORRELATION_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies the data-minimisation contract: the component set is closed, and no component is named
     * for or typed as an identifier, a credential or a raw record image.
     */
    @Test
    void dataMinimisationTheComponentSetIsClosedAndCarriesNoIdentifierOrRecordImage() {
        // WHY : Assumptions: this test is named after the constraint rather than after the mechanism
        //       so that it cannot be deleted as a redundant reflection exercise. It is the only
        //       assertion in this class that fails when a seventh component is added, and a seventh
        //       component is exactly how a card number or an account identifier would reach a
        //       long-lived, widely readable sink.
        RecordComponent[] components = BatchErrorEvent.class.getRecordComponents();

        List<String> names = Arrays.stream(components).map(RecordComponent::getName).toList();
        assertThat(names).containsExactlyElementsOf(DECLARED_COMPONENT_NAMES);

        // WHY : Assumptions: the type list is asserted alongside the name list because a component
        //       could be renamed innocuously and still smuggle content through its TYPE. Closing the
        //       type set to two character components, the two enumerations this package declares and
        //       the shared abend detail leaves no component able to carry a record image, a numeric
        //       quantity or a monetary amount.
        List<Class<?>> types = Arrays.stream(components).map(RecordComponent::getType).toList();
        assertThat(types).containsExactly(String.class, String.class, BatchJobName.class,
                BatchReturnCode.class, String.class, AbendDetail.class);

        List<String> accessorNames = Arrays.stream(BatchErrorEvent.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        for (String fragment : FORBIDDEN_NAME_FRAGMENTS) {
            assertThat(names)
                    .as("no component name may contain '%s'", fragment)
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains(fragment));
            assertThat(accessorNames)
                    .as("no declared method name may contain '%s'", fragment)
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains(fragment));
        }
    }

    /**
     * Verifies that the rendered form carries only the component values it was given, so writing it
     * into a log line cannot disclose anything the caller did not supply.
     */
    @Test
    void dataMinimisationTheRenderedFormCarriesOnlyTheSuppliedComponentValues() {
        // WHY : Assumptions: the inherited rendering is safe BECAUSE the minimisation constraint is
        //       enforced on what goes in, not because of anything about the rendering itself. This
        //       test asserts that property from the outside: the fixtures are digit-free, so a long
        //       digit run appearing in the rendered form could only have come from somewhere else.
        String rendered = fullyPopulatedEvent().toString();

        assertThat(rendered).contains(RUN_ID, STEP_NAME, CORRELATION_ID,
                BatchJobName.POST_TRANSACTIONS.name(), BatchReturnCode.HARD_FAILURE.name());

        for (String identifierShaped : IDENTIFIER_SHAPED_VALUES) {
            assertThat(rendered)
                    .as("the rendered form must not carry '%s', which was never supplied",
                            identifierShaped)
                    .doesNotContain(identifierShaped);
        }

        assertThat(longestDigitRun(rendered))
                .as("the rendered form must carry no run of digits long enough to be an identifier")
                .isLessThan(SHORTEST_IDENTIFIER_DIGIT_RUN);
    }

    /**
     * Verifies that no component and no declared method names a validity horizon.
     */
    @Test
    void noComponentOrAccessorNamesAValidityHorizon() {
        // WHY : Alternatives Considered: leaving this to review, on the ground that the type Javadoc
        //       already states the non-applicability. Rejected because the hazard is a pattern match
        //       -- a queue payload in a migration whose specification prescribes a horizon attribute
        //       -- and a reader who adds the field is precisely a reader who did not read that far.
        //       An assertion refuses the addition at the build instead.
        List<String> names = Arrays.stream(BatchErrorEvent.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        List<String> methodNames = Arrays.stream(BatchErrorEvent.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        for (String fragment : FORBIDDEN_HORIZON_FRAGMENTS) {
            assertThat(names)
                    .as("no component name may contain '%s'", fragment)
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains(fragment));
            assertThat(methodNames)
                    .as("no declared method name may contain '%s'", fragment)
                    .noneMatch(name -> name.toLowerCase(Locale.ROOT).contains(fragment));
        }
    }

    /**
     * Verifies that the abend detail component is the shared library type and not a local equivalent.
     */
    @Test
    void abendDetailUsesTheSharedLibraryTypeAndNotALocalEquivalent() {
        // WHY : Refactoring Rationale: the abend block is a shared concern, so the migration's
        //       shared-concerns rule places it in the common library and nowhere else. A local
        //       four-component equivalent would compile and would give one inherited contract two
        //       definitions free to disagree about a declared width, with nothing to report it.
        RecordComponent abendComponent = Arrays.stream(BatchErrorEvent.class.getRecordComponents())
                .filter(component -> "abendDetail".equals(component.getName()))
                .findFirst()
                .orElseThrow();

        assertThat(abendComponent.getType().getName())
                .isEqualTo("com.carddemo.common.error.AbendDetail");
    }

    /**
     * Verifies that value equality is the contract and that a differing correlation identifier makes
     * two events unequal.
     */
    @Test
    void equalComponentsAreEqualAndADifferingCorrelationIdentifierIsNot() {
        BatchErrorEvent one = fullyPopulatedEvent();
        BatchErrorEvent same = fullyPopulatedEvent();
        BatchErrorEvent other = new BatchErrorEvent(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE,
                CORRELATION_ID + "-different", ABEND_DETAIL);

        assertThat(same).isEqualTo(one).hasSameHashCodeAs(one);
        assertThat(other).isNotEqualTo(one);
    }

    /**
     * Verifies that an event round-trips through an object mapper with no annotation declared per
     * component, and that the serialised form carries exactly the six component names.
     */
    @Test
    void roundTripsThroughTheObjectMapperCarryingExactlyTheSixComponents() {
        // WHY : Assumptions: the mapper is built here with no customisation at all, which is the
        //       point of the test. The migration decides its wire form in code once, in the common
        //       library's registration, so a shape that needs a per-component annotation to survive a
        //       plain mapper would be a shape whose wire form was decided in a second place.
        JsonMapper mapper = JsonMapper.builder().build();
        BatchErrorEvent original = fullyPopulatedEvent();

        String json = mapper.writeValueAsString(original);
        BatchErrorEvent restored = mapper.readValue(json, BatchErrorEvent.class);

        assertThat(restored).isEqualTo(original);

        JsonNode tree = mapper.readTree(json);
        assertThat(tree.size())
                .as("the serialised form must carry no field beyond the six components")
                .isEqualTo(DECLARED_COMPONENT_NAMES.size());
        for (String componentName : DECLARED_COMPONENT_NAMES) {
            assertThat(tree.has(componentName))
                    .as("the serialised form must carry the component '%s'", componentName)
                    .isTrue();
        }
    }

    /**
     * Verifies that a malformed serialised event is refused on the way in rather than accepted
     * half-populated.
     */
    @Test
    void aSerialisedEventCarryingACleanTierIsRefusedOnDeserialisation() {
        // WHY : Assumptions: the mapper binds a record through its canonical constructor, so the
        //       validation runs on the way IN as well as on the way out. That is the behaviour a
        //       consumer of a terminal sink wants: a message it cannot interpret should fail at the
        //       boundary rather than be counted as a failure report.
        JsonMapper mapper = JsonMapper.builder().build();
        String json = mapper.writeValueAsString(fullyPopulatedEvent())
                .replace("\"HARD_FAILURE\"", "\"CLEAN\"");

        assertThatThrownBy(() -> mapper.readValue(json, BatchErrorEvent.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Measures the longest run of consecutive decimal digits in a rendered value.
     *
     * @param value the rendered value to scan; must be non-null
     * @return the length of the longest run of consecutive decimal digits, or zero when the value
     *     carries no digit at all
     */
    private static int longestDigitRun(String value) {
        // WHY : Alternatives Considered: a regular expression looking for a fixed number of digits.
        //       Rejected because it answers only the question it was compiled with, whereas the
        //       longest run is reported once and compared against whichever identifier width a
        //       future reader cares about, with the threshold named as a constant rather than buried
        //       in a pattern.
        int longest = 0;
        int current = 0;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isDigit(value.charAt(index))) {
                current++;
                longest = Math.max(longest, current);
            } else {
                current = 0;
            }
        }
        return longest;
    }
}
