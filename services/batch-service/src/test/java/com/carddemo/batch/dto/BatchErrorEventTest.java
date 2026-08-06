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
     *
     * <p>Refactoring Rationale: this field DELEGATES to the constant the type under test publishes
     * rather than restating the number nine. An earlier revision spelled the literal here, which made
     * the two independent: raising the production threshold to sixteen would have left this class
     * asserting against nine and still passing, so the assertion would have gone on reporting a
     * guarantee the production code had stopped giving. The width contract itself is asserted
     * separately by {@link #theIdentifierThresholdStaysInsideTheWidthsTheReferenceDeclares()}.</p>
     */
    private static final int SHORTEST_IDENTIFIER_DIGIT_RUN =
            BatchErrorEvent.SHORTEST_IDENTIFIER_DIGIT_RUN;

    /**
     * Diagnostic texts a caller must not be able to publish, and that this class proves it cannot.
     *
     * <p>Assumptions: every value is synthetic -- a run of zeros with a recognisable four-digit tail,
     * or a credential word with no credential beside it -- so this file carries nothing resembling a
     * real card number, account or secret. Every identifier run ends in the same four digits, and
     * they differ from the run itself, so the masking assertions can prove WHICH four digits
     * survived rather than merely counting them. Every vector also fits inside the fifty-character
     * reason width, so no vector is shortened before the guard measures it.</p>
     *
     * <p>Assumptions: the seven vectors cover the five shapes the finding named. An unseparated
     * sixteen-digit card number; the same number written for a human in space-separated and
     * hyphen-separated groups of four, which an unseparated scan would miss entirely; an
     * eleven-digit account identifier and a nine-digit customer identifier, both narrower than a card
     * number; a credential word, which has no shape to recognise; and a fixed-width record image of
     * the kind the reference's reject stream writes, which is the widest of them all.</p>
     */
    private static final List<String> ADVERSARIAL_DIAGNOSTICS = List.of(
            "insert failed for card 0000000000009010",
            "insert failed for card 0000 0000 0000 9010",
            "insert failed for card 0000-0000-0000-9010",
            "account 00000009010 not found in the cross reference",
            "customer 000009010 not found",
            "connect refused: password rejected",
            "0000000000000000000000000000000000009010POS TERMINAL 0001");

    /**
     * Diagnostic texts that are legitimate and must still construct, proving the guard is not blunt.
     *
     * <p>Assumptions: these are the shapes a guard measuring digit runs is most likely to refuse by
     * mistake, and each is content an operator genuinely needs. A business date carries a four-digit
     * year beside two-digit groups; a timestamp carries six more; a money amount carries a four-digit
     * unit part and a two-digit fraction; a merchant reference carries six digits; and an abend code
     * carries four. A guard that refused any of these would push callers towards publishing no
     * diagnostics at all, which costs more than it saves.</p>
     */
    private static final List<String> BENIGN_DIAGNOSTICS = List.of(
            "step ended abnormally",
            "business date 2022-07-18 rejected",
            "at 2022-07-18 12:34:56.789012 the step stopped",
            "amount 1234.56 exceeded limit 5000.00",
            "merchant 123456 unknown",
            "sort returned 0016");

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
     * Verifies that every adversarial diagnostic is refused when it is placed in the abend reason.
     */
    @Test
    void everyAdversarialDiagnosticIsRefusedInTheAbendReason() {
        // WHY : Refactoring Rationale: this class used to prove only that the record DECLARED no
        //       sensitive component and that a digit-free fixture rendered digit-free. Neither
        //       assertion could see the actual hazard, which was a caller passing record content
        //       through the abend reason -- a fifty-character free-text component the type inspected
        //       nowhere. The vectors below are the assertion the earlier ones could not make.
        for (String vector : ADVERSARIAL_DIAGNOSTICS) {
            assertThatThrownBy(() -> eventWithDiagnostics(detailWithReason(vector)))
                    .as("the abend reason must refuse '%s'", vector)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("abendDetail.abendReason");
        }
    }

    /**
     * Verifies that every adversarial diagnostic is refused when it is placed in the abend message.
     */
    @Test
    void everyAdversarialDiagnosticIsRefusedInTheAbendMessage() {
        // WHY : Assumptions: the message is checked as well as the reason because it is the WIDER of
        //       the two free-text components -- seventy-two characters against fifty -- so a guard
        //       covering only the reason would leave the roomier component open. Testing both is what
        //       makes the pair a contract rather than a spot check of whichever one was authored
        //       first.
        for (String vector : ADVERSARIAL_DIAGNOSTICS) {
            assertThatThrownBy(() -> eventWithDiagnostics(detailWithMessage(vector)))
                    .as("the abend message must refuse '%s'", vector)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("abendDetail.abendMsg");
        }
    }

    /**
     * Verifies that a credential word is refused in the abend culprit, the narrowest guarded
     * component.
     */
    @Test
    void aCredentialWordIsRefusedInTheAbendCulprit() {
        // WHY : Assumptions: the culprit is eight characters, so it can hold a credential WORD but
        //       not any of the reference's numeric identifiers -- the narrowest of those is nine
        //       digits. The eight-character marker is therefore the only vector that can reach this
        //       component, and the assertion proves the guard inspects all four components rather
        //       than the two wide ones a reader would think of first.
        assertThatThrownBy(() -> eventWithDiagnostics(detailWithCulprit("password")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abendDetail.abendCulprit");
    }

    /**
     * Verifies that every declared credential marker is refused, so the vocabulary is fully enforced.
     */
    @Test
    void everyDeclaredCredentialMarkerIsRefused() {
        // WHY : Alternatives Considered: asserting against a handful of markers chosen here. Rejected
        //       because a marker added to the published vocabulary but never matched would then pass
        //       unnoticed, and a vocabulary whose entries are not all enforced is a vocabulary a
        //       reader cannot trust. Iterating the published set makes the two impossible to
        //       disagree.
        for (String marker : BatchErrorEvent.PROHIBITED_DIAGNOSTIC_MARKERS) {
            String vector = "step failed while resolving " + marker + " for the run";
            assertThatThrownBy(() -> eventWithDiagnostics(detailWithReason(vector)))
                    .as("the marker '%s' must be refused", marker)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Verifies that the published credential vocabulary is populated and folded ready for matching.
     */
    @Test
    void theCredentialVocabularyIsPopulatedAndAlreadyFolded() {
        // WHY : Assumptions: matching folds the candidate text to lower case and then looks for each
        //       entry as a substring, so an entry carrying an upper-case letter could never match
        //       anything. That failure is silent -- the entry simply never fires -- so it is asserted
        //       here rather than left to be noticed by an incident.
        assertThat(BatchErrorEvent.PROHIBITED_DIAGNOSTIC_MARKERS).isNotEmpty();
        for (String marker : BatchErrorEvent.PROHIBITED_DIAGNOSTIC_MARKERS) {
            assertThat(marker)
                    .as("every declared marker must be non-blank and already folded")
                    .isNotBlank()
                    .isEqualTo(marker.toLowerCase(Locale.ROOT));
        }
        assertThat(BatchErrorEvent.PROHIBITED_DIAGNOSTIC_MARKERS)
                .contains("password", "secret", "token", "credential");
    }

    /**
     * Verifies that legitimate diagnostics still construct, so the guard refuses shape and not
     * digits.
     */
    @Test
    void legitimateDiagnosticsStillConstruct() {
        // WHY : Assumptions: this is the negative control, and without it the guard could be
        //       tightened until it refused everything and every rejection assertion above would
        //       still pass. The vectors are the near misses specifically: a date whose year is four
        //       digits, a timestamp whose fractional part is six, and an amount whose unit part is
        //       four.
        for (String vector : BENIGN_DIAGNOSTICS) {
            assertThatCode(() -> eventWithDiagnostics(detailWithReason(vector)))
                    .as("the abend reason must accept '%s'", vector)
                    .doesNotThrowAnyException();
            assertThatCode(() -> eventWithDiagnostics(detailWithMessage(vector)))
                    .as("the abend message must accept '%s'", vector)
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Verifies that the published threshold stays inside the widths the reference record layouts
     * declare.
     */
    @Test
    void theIdentifierThresholdStaysInsideTheWidthsTheReferenceDeclares() {
        // WHY : Assumptions: the threshold is bounded from BOTH sides, because either direction
        //       breaks it silently. Above nine it stops catching the nine-digit customer identifier
        //       the reference declares at app/cpy/CVCUS01Y.cpy; at four or below it starts refusing
        //       the four-digit abend code and the four-digit year of a business date, and a caller
        //       met with a rejection for writing a date publishes no diagnostics at all next time.
        assertThat(BatchErrorEvent.SHORTEST_IDENTIFIER_DIGIT_RUN)
                .as("the threshold must still catch the nine-digit customer identifier")
                .isLessThanOrEqualTo(9)
                .as("the threshold must not refuse a four-digit code or year")
                .isGreaterThan(4);
    }

    /**
     * Verifies that an over-width identifier is shortened by the shared type before the guard sees
     * it.
     */
    @Test
    void anOverWidthIdentifierIsShortenedBeforeTheGuardSeesIt() {
        // WHY : Assumptions: this documents why the two narrow components are not a hole.
        //       AbendDetail's own constructor conforms each component to the width the reference
        //       declares BEFORE this record's guard runs, so a sixteen-digit run offered as the
        //       eight-character culprit arrives as eight digits -- narrower than any identifier the
        //       migration carries -- and is correctly accepted. The event that results carries no
        //       identifier, which is the property that matters; it is asserted rather than reasoned
        //       about because the ordering of the two constructors is what makes it true.
        AbendDetail detail = new AbendDetail(
                "0999", "0000000000009010", "step ended abnormally", "abending program");

        assertThat(detail.abendCulprit()).hasSize(AbendDetail.ABEND_CULPRIT_LENGTH);
        assertThatCode(() -> eventWithDiagnostics(detail)).doesNotThrowAnyException();
        assertThat(longestDigitRun(detail.abendCulprit()))
                .isLessThan(SHORTEST_IDENTIFIER_DIGIT_RUN);
    }

    /**
     * Verifies that the rendered form omits both free-text components while keeping the closed ones.
     */
    @Test
    void theRenderedFormOmitsBothFreeTextDiagnosticComponents() {
        // WHY : Assumptions: this asserts the second half of the fix, and it has to be asserted
        //       separately from the constructor guard because the two protect against different
        //       things. The guard recognises identifier shape and credential words; it cannot
        //       recognise a name or an electronic mail address. The rendering needs to recognise
        //       nothing, because it does not read the free-text components at all -- and that is
        //       only true while nobody restores the inherited rendering, which is what this test
        //       refuses.
        String rendered = fullyPopulatedEvent().toString();

        assertThat(rendered)
                .as("the closed components must still be rendered")
                .contains(RUN_ID, STEP_NAME, CORRELATION_ID, "0999", "CBTRN02C");
        assertThat(rendered)
                .as("neither free-text component may be rendered")
                .doesNotContain("step ended abnormally", "abending program");
    }

    /**
     * Verifies that the published payload still carries the full diagnostics the rendering omits.
     */
    @Test
    void theSerialisedFormStillCarriesTheFullDiagnostics() {
        // WHY : Trade-offs: the rendering is narrowed and the PAYLOAD is not, and this test is where
        //       that distinction is pinned down. The payload goes to one queue whose access is
        //       controlled; the rendered line goes wherever log aggregation sends it. Narrowing both
        //       would leave an operator with no way to read a reason at all, which is a cure worse
        //       than the disease -- so the omission is scoped to the form that travels furthest.
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode tree = mapper.readTree(mapper.writeValueAsString(fullyPopulatedEvent()));
        JsonNode detail = tree.get("abendDetail");

        assertThat(detail.get("abendReason").stringValue()).isEqualTo("step ended abnormally");
        assertThat(detail.get("abendMsg").stringValue()).isEqualTo("abending program");
    }

    /**
     * Verifies that the redacting factory publishes every adversarial vector with nothing sensitive.
     */
    @Test
    void theRedactingFactoryPublishesEveryAdversarialVectorCleanly() {
        // WHY : Assumptions: three properties are asserted together because each alone would permit a
        //       useless implementation. That the factory CONSTRUCTS rules out a redaction whose
        //       output the guard still refuses, which would make the recovery path unusable. That
        //       the result carries no identifier-wide digit run and no marker rules out a redaction
        //       that merely relaxes the guard. That the four-digit tail SURVIVES rules out blanking
        //       the component outright, which would satisfy the first two and leave the operator
        //       nothing to read.
        for (String vector : ADVERSARIAL_DIAGNOSTICS) {
            BatchErrorEvent event = BatchErrorEvent.withRedactedDiagnostics(RUN_ID, STEP_NAME,
                    BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, CORRELATION_ID,
                    detailWithReason(vector));

            String redacted = event.abendDetail().abendReason();
            assertThat(longestDigitRun(redacted))
                    .as("'%s' must be redacted below the identifier threshold", vector)
                    .isLessThan(SHORTEST_IDENTIFIER_DIGIT_RUN);
            assertThat(redacted.toLowerCase(Locale.ROOT))
                    .as("'%s' must not survive redaction carrying a credential marker", vector)
                    .doesNotContain(BatchErrorEvent.PROHIBITED_DIAGNOSTIC_MARKERS
                            .toArray(String[]::new));
            if (!redacted.equals(BatchErrorEvent.REDACTED_DIAGNOSTIC)) {
                assertThat(redacted)
                        .as("'%s' must keep its four-digit tail", vector)
                        .contains("9010");
            }
        }
    }

    /**
     * Verifies that the redacting factory replaces a credential-bearing component rather than
     * masking it.
     */
    @Test
    void theRedactingFactoryReplacesACredentialBearingComponentOutright() {
        // WHY : Assumptions: a credential has no shape, so it has no safe remainder and masking a
        //       digit run inside the sentence would leave the secret standing. Replacement is
        //       therefore the only correct handling, and the replacement is one fixed literal
        //       rather than a message naming what was found -- naming it would describe the content
        //       where the content itself was refused.
        BatchErrorEvent event = BatchErrorEvent.withRedactedDiagnostics(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, CORRELATION_ID,
                detailWithReason("connect refused: password rejected"));

        assertThat(event.abendDetail().abendReason())
                .isEqualTo(BatchErrorEvent.REDACTED_DIAGNOSTIC);
    }

    /**
     * Verifies that masking preserves each component's length, so no component is silently shortened.
     */
    @Test
    void maskingPreservesEachComponentLength() {
        // WHY : Assumptions: masking replaces digits one for one and inserts nothing, so a redacted
        //       component still measures what it measured. That matters because AbendDetail truncates
        //       an over-width component on the RIGHT without reporting it: a redaction that
        //       lengthened a value sitting near its declared width would lose the far end of the
        //       sentence, and the loss would be invisible.
        String vector = "insert failed for card 0000 0000 0000 9010";
        BatchErrorEvent event = BatchErrorEvent.withRedactedDiagnostics(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, CORRELATION_ID,
                detailWithReason(vector));

        assertThat(event.abendDetail().abendReason()).hasSameSizeAs(vector);
    }

    /**
     * Verifies that the redacting factory leaves the run, step and correlation identifiers untouched.
     */
    @Test
    void theRedactingFactoryLeavesTheJoinKeysUntouched() {
        // WHY : Assumptions: the three character components are values this module generated, and
        //       they are the event's only route back to the run's own record of itself. Redacting
        //       them would make the payload unjoinable, so the factory redacts the diagnostics ONLY
        //       -- which is asserted here because a redaction applied to the wrong components would
        //       still pass every cleanliness assertion above.
        BatchErrorEvent event = BatchErrorEvent.withRedactedDiagnostics(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, CORRELATION_ID,
                ABEND_DETAIL);

        assertThat(event.runId()).isEqualTo(RUN_ID);
        assertThat(event.stepName()).isEqualTo(STEP_NAME);
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(event.abendDetail()).isEqualTo(ABEND_DETAIL);
    }

    /**
     * Verifies that the redacting factory refuses an absent detail rather than substituting one.
     */
    @Test
    void theRedactingFactoryRefusesAnAbsentDetail() {
        // WHY : Assumptions: the factory refuses null rather than falling back to the blank form,
        //       because the two mean different things -- one is a caller with no abend to report,
        //       which withoutAbendDetail expresses, and the other is a caller that lost track of
        //       its own argument. Substituting for the second would hide it.
        assertThatThrownBy(() -> BatchErrorEvent.withRedactedDiagnostics(RUN_ID, STEP_NAME,
                BatchJobName.POST_TRANSACTIONS, BatchReturnCode.HARD_FAILURE, CORRELATION_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ABSENT_ABEND_DETAIL");
    }

    /**
     * Verifies that a rejection message quotes no part of the value it refused.
     */
    @Test
    void aRejectionMessageQuotesNoPartOfTheRefusedValue() {
        // WHY : Assumptions: an exception message is copied into logs and incident records by every
        //       layer it passes through, so a guard that echoed the offending text would become the
        //       disclosure path it exists to close. This is the one assertion that would catch that
        //       regression, because the guard would otherwise still be refusing correctly.
        String vector = "insert failed for card 0000000000009010";

        assertThatThrownBy(() -> eventWithDiagnostics(detailWithReason(vector)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("0000000000009010")
                .hasMessageNotContaining(vector);
    }

    /**
     * Builds an event carrying the supplied diagnostics and the fixture values for every other part.
     *
     * @param abendDetail the diagnostics to carry, which may be {@code null} so that the absent
     *     case is
     *     reachable
     * @return an event carrying those diagnostics; never {@code null}
     */
    private static BatchErrorEvent eventWithDiagnostics(AbendDetail abendDetail) {
        return new BatchErrorEvent(RUN_ID, STEP_NAME, BatchJobName.POST_TRANSACTIONS,
                BatchReturnCode.HARD_FAILURE, CORRELATION_ID, abendDetail);
    }

    /**
     * Builds an abend detail carrying the supplied text as its reason and fixture values elsewhere.
     *
     * @param reason the reason text to carry; must be non-null
     * @return an abend detail whose reason is that text; never {@code null}
     */
    private static AbendDetail detailWithReason(String reason) {
        return new AbendDetail("0999", "CBTRN02C", reason, "abending program");
    }

    /**
     * Builds an abend detail carrying the supplied text as its message and fixture values elsewhere.
     *
     * @param message the message text to carry; must be non-null
     * @return an abend detail whose message is that text; never {@code null}
     */
    private static AbendDetail detailWithMessage(String message) {
        return new AbendDetail("0999", "CBTRN02C", "step ended abnormally", message);
    }

    /**
     * Builds an abend detail carrying the supplied text as its culprit and fixture values elsewhere.
     *
     * @param culprit the culprit text to carry; must be non-null
     * @return an abend detail whose culprit is that text; never {@code null}
     */
    private static AbendDetail detailWithCulprit(String culprit) {
        return new AbendDetail("0999", culprit, "step ended abnormally", "abending program");
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
