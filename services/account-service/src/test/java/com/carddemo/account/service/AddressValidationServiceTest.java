package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.validation.FieldValidationFlag;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Pins the four public edits of {@link AddressValidationService} against the five allow-lists the
 * reference copybook declares.
 *
 * <p>Refactoring Rationale: this class opened by naming THREE edits, and the fourth -- the
 * whole-number telephone edit that composes the area code with the prefix and the line number --
 * had no direct case anywhere. The count was not merely understated: the composition it performs
 * is where the accumulate-every-failing-part behaviour and the fifteen-character split live, and
 * both were unasserted while the three part-level edits below were covered in detail. The cases at
 * the end of this class close that, and the opening sentence now names what is actually pinned.
 *
 * <h2>Five allow-lists, three validation targets</h2>
 *
 * <p>{@code app/cpy/CSLKPCDY.cpy} is 1318 lines and carries exactly FIVE condition-name allow-lists
 * over THREE targets, a count taken from the file rather than from its three-item header at lines 2
 * to 5. Reading that header as one list per target would leave two of the five unexercised, so the
 * roster is written out here and every entry has at least one case below.</p>
 *
 * <ul>
 *   <li>{@code 01 WS-US-PHONE-AREA-CODE-TO-EDIT PIC XXX.} at line 24 owns THREE of them:
 *       {@code VALID-PHONE-AREA-CODE} at line 30, {@code VALID-GENERAL-PURP-CODE} at line 521 and
 *       {@code VALID-EASY-RECOG-AREA-CODE} at line 931, whose literals end at line 1010.</li>
 *   <li>{@code 01 US-STATE-CODE-TO-EDIT PIC X(2).} at line 1012 owns ONE,
 *       {@code VALID-US-STATE-CODE} at line 1013.</li>
 *   <li>{@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4).} at line 1072 owns ONE,
 *       {@code VALID-US-STATE-ZIP-CD2-COMBO} at line 1073, whose literals end at line 1313.</li>
 * </ul>
 *
 * <p>Refactoring Rationale: the three telephone allow-lists are three conditions over ONE field in
 * the baseline, because a COBOL {@code 88} level is a condition attached to a data item rather than a
 * table of its own -- all three of lines 30, 521 and 931 hang on the single field of line 24. The
 * migrated form names them separately, as the assignment, general-purpose and easily-recognisable
 * components of one classification, so each is asserted on its own here. Exercising only line 30, the
 * list a reader meets first, would leave the other two thirds of the telephone contract unverified,
 * and it would in particular not detect a validator that admitted every assigned area code when the
 * baseline admits only the general-purpose ones.</p>
 *
 * <p>Assumptions: the state and postal pairing compares exactly FOUR characters, and the reason is
 * structural rather than a chosen prefix length. The condition name at line 1073 sits on the
 * {@code 02} sub-level of line 1072, which is declared {@code PIC X(4)}, and not on the {@code 01}
 * group of line 1071. {@code app/cbl/COACTUPC.cbl} corroborates it independently at lines 2537 to
 * 2540, where the comparand is assembled by {@code STRING ACUP-NEW-CUST-ADDR-STATE-CD} followed by
 * {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)} {@code DELIMITED BY SIZE INTO US-STATE-AND-FIRST-ZIP2} -- a
 * two-character state code and the first two postal characters, and nothing further.</p>
 *
 * <p>Assumptions: the postal characters after those first two are deliberately NOT validated
 * anywhere. {@code app/cpy/CSLKPCDY.cpy} declares {@code 02 LAST-3-OF-ZIP PIC X(3).} at line 1314 and
 * attaches no condition name to it, which is why the file holds five allow-lists and not six. That
 * absence is asserted below as a contract in its own right, so that a later reader does not take it
 * for an omission and supply a rule the baseline never performed.</p>
 *
 * <p>Assumptions: the telephone list is a point-in-time snapshot of a registry maintained outside
 * this repository. The copybook records its own provenance at lines 27 and 28, naming the numbering
 * plan administrator and the published numbering-plan-area report its literals were taken from, so an
 * area code assigned after that snapshot is absent from the list and is refused. Every telephone
 * vector below is therefore a membership fact about that snapshot and never a claim about what is
 * dialable today; currency is a question answered by reseeding the reference table.</p>
 *
 * <h2>The tables are read across a service boundary, so the reader is substituted</h2>
 *
 * <p>Assumptions: {@code reference.us_phone_area_codes}, {@code reference.us_states} and
 * {@code reference.us_state_zip_prefixes} are owned and seeded by {@code reference-service}, in its
 * {@code V2__seed_reference.sql}, from the very copybook cited above. Nothing here seeds them, reads
 * them, or holds a production copy of any of the five lists. The three probes are answered by a
 * substituted {@link AddressValidationService.ReferenceAddressLookup}, which is the port the service
 * declares for exactly that purpose, so no database, no container and no Spring context is started by
 * any case in this class.</p>
 *
 * <p>Assumptions: the handful of codes named as constants below are TEST DATA and are not a second
 * copy of an allow-list. Each was checked against the copybook range that owns it before it was used
 * here -- {@code 703} against line 521, {@code 999} against line 931, {@code 111} against the whole of
 * line 30, {@code VA} against line 1013 and {@code VA22} against line 1073 -- and each stands for its
 * range rather than enumerating it. Widening any of them into a full list would create a second
 * source of truth for data {@code reference-service} owns, and the two could then disagree without
 * anything failing.</p>
 *
 * <p>Alternatives Considered: a retry around the substituted probe, and a circuit breaker in front of
 * it, were both evaluated for these cases and both rejected, which is why the transport case below
 * asserts that a failure propagates unchanged and that the probe is issued exactly ONCE. The service
 * contract states the same posture as an obligation on any implementation: bounded connect and read
 * timeouts and nothing else. A test that tolerated more than one probe would silently accept a retry
 * being added later, and a test that expected an invalid verdict from an unreachable reference would
 * lock in the one outcome the contract forbids -- reporting a valid area code as invalid because the
 * peer was slow.</p>
 *
 * <p>Trade-offs: membership is asserted through {@code @ParameterizedTest} tables rather than one
 * method per vector. A table is denser to read and each vector still reports as its own JUnit
 * invocation, so a single failing code is still named individually. The compromise accepted is the
 * longer parameter block every such method must document, because the documentation gate requires a
 * real description on each {@code @param} tag and rejects a bare tag name; the alternative, a method
 * per vector, would have traded that block for roughly thirty near-identical bodies in which a
 * missing vector is far harder to notice.</p>
 *
 * <h2>What an error assertion asks</h2>
 *
 * <p>Assumptions: whether a field is in error is asked as ONE question here, through the marker's own
 * error predicate, because that is how the baseline asks it -- {@code app/cpy/CSSETATY.cpy} tests the
 * unacceptable and never-supplied conditions as a single disjunction at its lines 18 and 19. Two of
 * the three marker states answer yes, so comparing against one constant would pass an unacceptable
 * value off as acceptable. The two error states are still told apart where the difference is
 * observable, and it is observable in exactly one place: only the never-supplied state carries the
 * literal marker written into the screen field at line 24 of that same copybook.</p>
 *
 * <h2>The absence of a parity oracle</h2>
 *
 * <p>Assumptions: no executable parity oracle covers any rule asserted in this class, and two
 * independent statements in {@code tests/README.md} establish it. Its lines 83 to 85 record that the
 * online programs cannot be run end to end without a terminal-monitor runtime, which the runner does
 * not have, so only their extractable field-validation logic is unit-tested there. And the business
 * rules that suite asserts verbatim, from its line 553 onward, name the posting, interest and
 * category-balance programs of other contexts and name neither {@code app/cbl/COACTUPC.cbl} nor this
 * copybook even once. Every expectation below is therefore authored from the copybook and the program
 * paragraphs directly, no golden-master comparison is claimed for any of them, and these cases are
 * strictly additive to that suite, which is reference material and is not modified.</p>
 */
class AddressValidationServiceTest {

    /**
     * An area code the general-purpose list at line 521 of the copybook holds.
     *
     * <p>Assumptions: {@code 703} is a member of the list at line 521 and consequently of the broad
     * list at line 30, and is absent from the list at line 931. That is the combination the accepting
     * vectors need, and it was read from those three ranges rather than assumed.</p>
     */
    private static final String GENERAL_PURPOSE_AREA_CODE = "703";

    /**
     * An area code the easily-recognisable list at line 931 of the copybook holds.
     *
     * <p>Assumptions: {@code 999} is the final literal of that list, at line 1010. It is an ASSIGNED
     * code, present in the broad list at line 30, yet absent from the general-purpose list at line
     * 521, which makes it the one vector that separates the two questions the telephone target
     * answers.</p>
     */
    private static final String EASILY_RECOGNISABLE_AREA_CODE = "999";

    /**
     * A second easily-recognisable area code, used so the refusal is not pinned to one literal.
     */
    private static final String SECOND_EASILY_RECOGNISABLE_AREA_CODE = "800";

    /**
     * Three digits that appear in none of the three telephone allow-lists.
     *
     * <p>Assumptions: {@code 111} is absent from the broad list at line 30, and therefore from both
     * narrower lists, so it is unassigned rather than merely outside the accepting one.</p>
     */
    private static final String UNASSIGNED_AREA_CODE = "111";

    /** A state code the allow-list at line 1013 of the copybook holds. */
    private static final String LISTED_STATE_CODE = "VA";

    /** Two characters the allow-list at line 1013 of the copybook does not hold. */
    private static final String UNLISTED_STATE_CODE = "ZZ";

    /**
     * A postal code whose first two characters pair with {@link #LISTED_STATE_CODE}.
     *
     * <p>Assumptions: the pairing {@code VA22} that this postal code and that state code produce is a
     * member of the list at line 1073; the copybook holds four pairings for this state and this is
     * one of them.</p>
     */
    private static final String PAIRED_ZIP_CODE = "22201";

    /** The four-character pairing that the listed state and the paired postal code produce. */
    private static final String LISTED_STATE_ZIP_PREFIX = "VA22";

    /**
     * A postal code whose first two characters do NOT pair with {@link #LISTED_STATE_CODE}.
     *
     * <p>Assumptions: the pairing {@code VA99} this produces is absent from the list at line 1073,
     * while the state code itself remains a listed one, so the refusal below is attributable to the
     * pairing rather than to either half being unknown.</p>
     */
    private static final String UNPAIRED_ZIP_CODE = "99201";

    /** The table token standing for a probe that finds the code in none of the three lists. */
    private static final String NO_CLASS_TOKEN = "-";

    /** The identity the area-code part of the first telephone field is reported under. */
    private static final String AREA_CODE_FIELD = "phoneNumber1.areaCode";

    /** The identity the state field is reported under. */
    private static final String STATE_FIELD = "stateCode";

    /** The identity the postal field is reported under. */
    private static final String ZIP_FIELD = "zipCode";

    /**
     * The label a telephone message is prefixed with.
     *
     * <p>Assumptions: this is the literal {@code app/cbl/COACTUPC.cbl} moves into its label field at
     * line 1632, immediately before the telephone edit runs. It is carried verbatim, because the
     * message a user reads is assembled from it.</p>
     */
    private static final String PHONE_LABEL = "Phone Number 1";

    /**
     * The label a state message is prefixed with, from line 1592 of the program.
     */
    private static final String STATE_LABEL = "State";

    // WHY : Assumptions: the port is substituted with a framework double rather than a hand-written
    //       stand-in, because every case here asserts either the value a probe RETURNS or the value a
    //       probe RECEIVES, and the double answers both without a class of its own. The service
    //       exposes no other seam -- its only field is this contract -- so substituting it is what
    //       makes the rules reachable at all, where app/cbl/COACTUPC.cbl draws the literals in
    //       textually with COPY CSLKPCDY. at its line 602 and offers no seam to substitute.
    private final AddressValidationService.ReferenceAddressLookup lookup =
            mock(AddressValidationService.ReferenceAddressLookup.class);

    // WHY : Assumptions: one service is built per test method, because JUnit constructs a fresh test
    //       instance for each of them by default. That is what keeps the probe-count assertions below
    //       meaningful: a shared double would carry invocations from an earlier method into a later
    //       one, and "the probe was issued exactly once" would then depend on execution order.
    private final AddressValidationService service = new AddressValidationService(this.lookup);

    /**
     * Supplies the state-code width vectors, pairing a submitted value with the probe it must produce.
     *
     * <p>Assumptions: a shorter value is padded on the right and a longer one is truncated on the
     * right, which is what a COBOL move of an alphanumeric value into a fixed-width field does. The
     * field this reproduces is the two-character item of {@code app/cpy/CSLKPCDY.cpy} line 1012, so
     * every expected probe below is exactly two characters wide.</p>
     *
     * @return the vectors, each carrying the submitted state code -- possibly {@code null} -- followed
     *     by the two-character value the probe must receive; never {@code null}
     */
    private static Stream<Arguments> stateCodeWidthVectors() {
        return Stream.of(
                Arguments.of("V", "V "),
                Arguments.of("VAX", "VA"),
                Arguments.of("", "  "),
                Arguments.of(null, "  "));
    }

    /**
     * Resolves a table token to the answer the substituted probe returns for it.
     *
     * <p>Assumptions: the two class tokens are resolved through the production resolver rather than a
     * mapping written here, so a table row exercises the same character-to-class decision the adapter
     * makes when it reads a stored classification. Restating that mapping locally would let this class
     * keep passing after the production one changed.</p>
     *
     * @param storedClass the table token, either a stored classification character or
     *     {@link #NO_CLASS_TOKEN} for a code the probe finds in none of the three lists
     * @return the probe answer, empty for {@link #NO_CLASS_TOKEN} and otherwise the class that
     *     character denotes; never {@code null}
     * @throws IllegalArgumentException if {@code storedClass} is neither {@link #NO_CLASS_TOKEN} nor a
     *     recognised classification character, which means a table row names a class the closed domain
     *     does not contain
     */
    private static Optional<AddressValidationService.AreaCodeClass> answerFor(String storedClass) {
        return NO_CLASS_TOKEN.equals(storedClass)
                ? Optional.empty()
                : Optional.of(AddressValidationService.AreaCodeClass.fromCode(storedClass.charAt(0)));
    }

    /**
     * Unwraps an outcome that reported exactly one failing field, checking the latch as it goes.
     *
     * <p>Assumptions: an outcome carrying one entry carries that entry's own wording as its aggregate
     * message. The baseline gates every write to its single message line on the line still being
     * clear, at {@code app/cbl/COACTUPC.cbl} line 2548 and its siblings, so with one failure the
     * aggregate and the entry cannot differ. Asserting it here rather than in each caller means the
     * invariant is checked on every single-failure case in this class without being restated.</p>
     *
     * @param result the outcome to unwrap; must not be {@code null}
     * @return the one entry it carries, never {@code null}
     */
    private static ApiError.FieldError onlyEntryOf(
            AddressValidationService.AddressValidationResult result) {

        assertThat(result.isValid()).isFalse();
        assertThat(result.fieldErrors()).hasSize(1);
        assertThat(result.message()).isEqualTo(result.fieldErrors().get(0).message());
        return result.fieldErrors().get(0);
    }

    /**
     * Asserts that an entry reports an unacceptable value rather than an unsupplied one.
     *
     * <p>Assumptions: the two error states are distinguishable only by the screen marker, so that is
     * what separates them here. An unacceptable value carries no marker; an unsupplied field carries
     * the literal one. Both answer the error predicate identically, which is why the predicate is
     * asserted as well rather than instead.</p>
     *
     * <p>Returns no value. A differing identity, a state that is not an error state, a screen marker
     * where none belongs, or a differing wording is reported as a JUnit assertion failure, which is
     * attributed to the calling case rather than to this helper.</p>
     *
     * @param entry the entry to inspect; must not be {@code null}
     * @param expectedField the identity the entry must be reported under
     * @param expectedMessage the wording the entry must carry, verbatim from the baseline literal
     */
    private static void assertUnacceptableValue(
            ApiError.FieldError entry, String expectedField, String expectedMessage) {

        assertThat(entry.field()).isEqualTo(expectedField);
        assertThat(entry.isError()).isTrue();
        assertThat(entry.state().isError()).isTrue();
        assertThat(entry.state().requiresBlankMarker()).isFalse();
        assertThat(entry.screenMarker()).isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
        assertThat(entry.message()).isEqualTo(expectedMessage);
    }

    /**
     * Confirms a candidate's standing is reported against all THREE telephone allow-lists at once.
     *
     * <p>Assumptions: one probe answers all three, because the two narrower lists partition the broad
     * one -- the 410 literals from line 521 and the 80 from line 931 sum to the 490 from line 30,
     * share no member, and their union equals it. The three rows below are the three shapes that
     * partition permits: a general-purpose member, an easily-recognisable member and a code in none of
     * them. A fourth shape, a code reported in both narrower lists, is not tested here because the
     * classification type refuses to hold it at all.</p>
     *
     * <p>Returns no value. A wrongly reported standing against any of the three lists, or a probe
     * issued for a value other than the candidate, is reported as a JUnit assertion failure.</p>
     *
     * @param areaCode the candidate area code the classification is asked for
     * @param storedClass the classification the substituted probe answers with, or
     *     {@link #NO_CLASS_TOKEN} when it finds the code in none of the three lists
     * @param expectedAssigned whether membership of the broad list at line 30 must be reported
     * @param expectedGeneralPurpose whether membership of the list at line 521 must be reported
     * @param expectedEasilyRecognisable whether membership of the list at line 931 must be reported
     */
    @ParameterizedTest
    @CsvSource({
        "703, G, true,  true,  false",
        "999, E, true,  false, true",
        "111, -, false, false, false",
    })
    @DisplayName("a candidate is classified against all three telephone allow-lists from one probe")
    void aCandidateIsClassifiedAgainstAllThreeTelephoneAllowLists(String areaCode,
            String storedClass, boolean expectedAssigned, boolean expectedGeneralPurpose,
            boolean expectedEasilyRecognisable) {

        when(this.lookup.findAreaCodeClass(areaCode)).thenReturn(answerFor(storedClass));

        AddressValidationService.AreaCodeClassification classification =
                this.service.classifyAreaCode(areaCode);

        assertThat(classification.assigned()).isEqualTo(expectedAssigned);
        assertThat(classification.generalPurpose()).isEqualTo(expectedGeneralPurpose);
        assertThat(classification.easilyRecognisable()).isEqualTo(expectedEasilyRecognisable);
        verify(this.lookup).findAreaCodeClass(areaCode);
    }

    /**
     * Confirms the accepting gate is the general-purpose list at line 521 and not the broad one.
     *
     * <p>Assumptions: {@code app/cbl/COACTUPC.cbl} line 2298 tests {@code VALID-GENERAL-PURP-CODE},
     * and the wording it emits at line 2306 names that same narrower list. An easily-recognisable code
     * is therefore an ASSIGNED code that this edit nonetheless refuses, which is the property the
     * second and third rows below exist to pin. A validator gated on the broad list at line 30 would
     * accept all 80 of them while still emitting a message stating they are not accepted.</p>
     *
     * <p>Returns no value. An accepted code that must be refused, a refused code that must be
     * accepted, or a wording other than the line 2306 literal prefixed with its label, is reported as
     * a JUnit assertion failure.</p>
     *
     * @param areaCode the candidate area code submitted to the edit
     * @param storedClass the classification the substituted probe answers with, or
     *     {@link #NO_CLASS_TOKEN} when it finds the code in none of the three lists
     * @param expectedAccepted whether the edit must accept the candidate
     */
    @ParameterizedTest
    @CsvSource({
        "703, G, true",
        "999, E, false",
        "800, E, false",
        "111, -, false",
    })
    @DisplayName("only a general-purpose area code is accepted, an assigned one alone is not enough")
    void onlyAGeneralPurposeAreaCodeIsAccepted(String areaCode, String storedClass,
            boolean expectedAccepted) {

        when(this.lookup.findAreaCodeClass(areaCode)).thenReturn(answerFor(storedClass));

        AddressValidationService.AddressValidationResult result =
                this.service.validateAreaCode(areaCode, AREA_CODE_FIELD, PHONE_LABEL);

        assertThat(result.isValid()).isEqualTo(expectedAccepted);

        if (!expectedAccepted) {
            assertUnacceptableValue(onlyEntryOf(result), AREA_CODE_FIELD,
                    PHONE_LABEL + AddressValidationService.MSG_AREA_CODE_NOT_GENERAL_PURPOSE);
        }
    }

    /**
     * Confirms an unsupplied area code is reported as never supplied and carries the screen marker.
     *
     * <p>Assumptions: this is the ONE state that the marker distinguishes from an unacceptable value
     * in an observable way. {@code app/cpy/CSSETATY.cpy} moves the error colour into the field's
     * attribute position for either error state at its lines 21 and 22, but writes the literal marker
     * into the field's DATA position at its lines 24 and 25 for the unsupplied state alone. Both
     * spellings of an unsupplied field are covered, because the baseline's own test tolerates two pad
     * bytes rather than one.</p>
     *
     * <p>Returns no value. An unsupplied area code reported as an unacceptable value, a missing screen
     * marker, or a probe issued for a field nobody filled in, is reported as a JUnit assertion
     * failure.</p>
     *
     * @param areaCode the unsupplied area code as it reaches the edit, either absent altogether, empty
     *     or filled with the pad character
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("an unsupplied area code is reported as never supplied and carries the screen marker")
    void anUnsuppliedAreaCodeIsReportedAsNeverSupplied(String areaCode) {
        AddressValidationService.AddressValidationResult result =
                this.service.validateAreaCode(areaCode, AREA_CODE_FIELD, PHONE_LABEL);

        ApiError.FieldError entry = onlyEntryOf(result);

        assertThat(entry.field()).isEqualTo(AREA_CODE_FIELD);
        assertThat(entry.isError()).isTrue();
        assertThat(entry.state().requiresBlankMarker()).isTrue();
        assertThat(entry.screenMarker()).isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(entry.message())
                .isEqualTo(PHONE_LABEL + AddressValidationService.MSG_AREA_CODE_BLANK);

        // WHY : Assumptions: none of the three allow-lists is consulted for a field nobody filled in,
        //       because app/cbl/COACTUPC.cbl leaves the paragraph at its lines 2247 and 2248 before
        //       reaching the list test at line 2298. Asserting the absence of any probe is what
        //       distinguishes that short circuit from a validator that padded the value to width and
        //       looked it up, which would answer "unsupplied" as "outside the list" instead.
        verifyNoInteractions(this.lookup);
    }

    /**
     * Confirms a non-numeric or wrong-length area code is refused before any allow-list is consulted.
     *
     * <p>Assumptions: the digits test precedes the list test in the baseline, at
     * {@code app/cbl/COACTUPC.cbl} line 2264 ahead of line 2298, so a value that is not three digits
     * never reaches a list at all. The two-character vector is the wrong-length case reaching the same
     * refusal: padded to width it holds a pad character in its third position, which is not a digit.
     * The vector spelled with a letter O rather than a zero is included because it is the shape a
     * transcription error actually takes.</p>
     *
     * <p>Returns no value. A value accepted despite not being three digits, a wording other than the
     * line 2272 literal, or a probe issued for a value the digits test already refused, is reported as
     * a JUnit assertion failure.</p>
     *
     * @param areaCode the candidate area code that is not three digits, whether through a letter, an
     *     embedded pad character or a length other than three
     */
    @ParameterizedTest
    @ValueSource(strings = {"20A", "abc", "7O3", "2 3", "70", "7"})
    @DisplayName("an area code that is not three digits is refused without consulting any allow-list")
    void anAreaCodeThatIsNotThreeDigitsIsRefusedWithoutAnyLookup(String areaCode) {
        AddressValidationService.AddressValidationResult result =
                this.service.validateAreaCode(areaCode, AREA_CODE_FIELD, PHONE_LABEL);

        assertUnacceptableValue(onlyEntryOf(result), AREA_CODE_FIELD,
                PHONE_LABEL + AddressValidationService.MSG_AREA_CODE_NOT_THREE_DIGITS);
        verifyNoInteractions(this.lookup);
    }

    /**
     * Confirms an all-zero area code is refused on its own terms rather than as an absent one.
     *
     * <p>Assumptions: the all-zero test is a THIRD check sitting between the digits test and the list
     * test, at {@code app/cbl/COACTUPC.cbl} line 2280, and it carries its own wording at line 2286. A
     * validator holding only the digits test and the list test would refuse this value too, but with
     * the line 2306 wording, so the message is what this case actually pins.</p>
     *
     * <p>Returns no value. An accepted all-zero area code, a wording other than the line 2286 literal,
     * or a probe issued for a value the zero test already refused, is reported as a JUnit assertion
     * failure.</p>
     */
    @Test
    @DisplayName("an all-zero area code is refused with its own wording and without any lookup")
    void anAllZeroAreaCodeIsRefusedWithItsOwnWording() {
        AddressValidationService.AddressValidationResult result =
                this.service.validateAreaCode("000", AREA_CODE_FIELD, PHONE_LABEL);

        assertUnacceptableValue(onlyEntryOf(result), AREA_CODE_FIELD,
                PHONE_LABEL + AddressValidationService.MSG_AREA_CODE_ZERO);
        verifyNoInteractions(this.lookup);
    }

    /**
     * Confirms an overlong area code is brought to the declared width before it is looked up.
     *
     * <p>Assumptions: the field is three characters wide, declared {@code PIC XXX} at
     * {@code app/cpy/CSLKPCDY.cpy} line 24 in the legacy short spelling, and a COBOL move of a longer
     * alphanumeric value into it truncates on the right. The probe must therefore receive three
     * characters whatever arrived, which is asserted on the captured value rather than inferred from
     * the outcome -- an outcome alone cannot distinguish truncation from a four-character probe that
     * the reference happened to answer.</p>
     *
     * <p>Returns no value. A probe of any width other than the declared one, or of any value other
     * than the leading three characters, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an overlong area code is truncated to the declared width before it is looked up")
    void anOverlongAreaCodeIsTruncatedBeforeItIsLookedUp() {
        when(this.lookup.findAreaCodeClass(anyString()))
                .thenReturn(Optional.of(AddressValidationService.AreaCodeClass.GENERAL_PURPOSE));

        AddressValidationService.AddressValidationResult result = this.service.validateAreaCode(
                GENERAL_PURPOSE_AREA_CODE + "1", AREA_CODE_FIELD, PHONE_LABEL);

        ArgumentCaptor<String> probe = ArgumentCaptor.forClass(String.class);
        verify(this.lookup).findAreaCodeClass(probe.capture());

        assertThat(probe.getValue())
                .hasSize(AddressValidationService.AREA_CODE_WIDTH)
                .isEqualTo(GENERAL_PURPOSE_AREA_CODE);
        assertThat(result.isValid()).isTrue();
    }

    /**
     * Confirms an empty candidate is classified without consulting any of the three allow-lists.
     *
     * <p>Assumptions: every literal in the list at line 30 of the copybook is three characters wide, so
     * no empty candidate could match one and the short circuit and a probe would agree. The short
     * circuit matters on the optional-telephone path, which reaches the classification with blank
     * parts on every submission that leaves a number empty, and the absence of a probe is the only
     * observable difference between the two, since both report the same standing.</p>
     *
     * <p>Returns no value. A reported membership of any of the three lists, or any probe at all, is
     * reported as a JUnit assertion failure.</p>
     *
     * @param areaCode the empty candidate, either absent altogether or holding only pad characters
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("an empty candidate is classified without consulting any of the three allow-lists")
    void anEmptyCandidateIsClassifiedWithoutConsultingAnyAllowList(String areaCode) {
        AddressValidationService.AreaCodeClassification classification =
                this.service.classifyAreaCode(areaCode);

        assertThat(classification.assigned()).isFalse();
        assertThat(classification.generalPurpose()).isFalse();
        assertThat(classification.easilyRecognisable()).isFalse();
        verifyNoInteractions(this.lookup);
    }

    /**
     * Confirms a state code is accepted exactly when the allow-list at line 1013 holds it.
     *
     * <p>Assumptions: this target carries ONE allow-list where the telephone target carries three, so
     * membership is the whole of the rule and there is no narrower gate behind it. The paragraph at
     * {@code app/cbl/COACTUPC.cbl} lines 2493 to 2511 tests the condition at line 2495 and composes
     * its message at lines 2502 and 2503 from the trimmed label followed by the literal, with no
     * separator between them, which is why the expected wording is a plain concatenation.</p>
     *
     * <p>Returns no value. An accepted code the list does not hold, a refused code it does hold, or a
     * wording other than the line 2503 literal prefixed with its label, is reported as a JUnit
     * assertion failure.</p>
     *
     * @param stateCode the candidate state code submitted to the edit
     * @param present whether the substituted probe reports the allow-list as holding that code
     */
    @ParameterizedTest
    @CsvSource({
        "VA, true",
        "CA, true",
        "ZZ, false",
        "XX, false",
    })
    @DisplayName("a state code is accepted exactly when the allow-list holds it")
    void aStateCodeIsAcceptedExactlyWhenTheAllowListHoldsIt(String stateCode, boolean present) {
        when(this.lookup.stateCodeExists(stateCode)).thenReturn(present);

        AddressValidationService.AddressValidationResult result =
                this.service.validateStateCode(stateCode, STATE_FIELD, STATE_LABEL);

        assertThat(result.isValid()).isEqualTo(present);

        if (!present) {
            assertUnacceptableValue(onlyEntryOf(result), STATE_FIELD,
                    STATE_LABEL + AddressValidationService.MSG_STATE_CODE_INVALID);
        }

        verify(this.lookup).stateCodeExists(stateCode);
    }

    /**
     * Confirms an unsupplied state code is reported as an unacceptable value, not as never supplied.
     *
     * <p>Assumptions: this diverges from the telephone target deliberately, and the paragraph settles
     * it. {@code app/cbl/COACTUPC.cbl} line 2499 sets the unacceptable-value marker on every failing
     * path of the state edit, and that paragraph has no unsupplied branch at all, because whether the
     * field was supplied is decided earlier by the mandatory-and-alphabetic edit the program runs over
     * the same value at its lines 1593 to 1595. Reporting an unsupplied state as never supplied here
     * would mark one field twice for one omission, and it would additionally attach the screen marker
     * of {@code app/cpy/CSSETATY.cpy} line 24 to a field that already carries it.</p>
     *
     * <p>Returns no value. An unsupplied state reported as never supplied, a screen marker on that
     * entry, or a probe issued for a value other than the padded field, is reported as a JUnit
     * assertion failure.</p>
     *
     * @param stateCode the unsupplied state code as it reaches the edit, either absent altogether,
     *     empty or filled with the pad character
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "  "})
    @DisplayName("an unsupplied state code is reported as an unacceptable value, not as never supplied")
    void anUnsuppliedStateCodeIsReportedAsAnUnacceptableValue(String stateCode) {
        AddressValidationService.AddressValidationResult result =
                this.service.validateStateCode(stateCode, STATE_FIELD, STATE_LABEL);

        assertUnacceptableValue(onlyEntryOf(result), STATE_FIELD,
                STATE_LABEL + AddressValidationService.MSG_STATE_CODE_INVALID);

        // WHY : Assumptions: the allow-list IS consulted for an unsupplied state, which is the
        //       consequence of the state edit having no unsupplied branch. The validator is total: it
        //       pads to width and looks the padded value up, so a caller must guard it on the earlier
        //       mandatory edit having passed, exactly as app/cbl/COACTUPC.cbl guards it at line 1599.
        //       Asserting the probe here records that obligation on the caller rather than leaving it
        //       to be discovered when an omitted state produces a message about an invalid one.
        verify(this.lookup).stateCodeExists("  ");
    }

    /**
     * Confirms a state code is brought to the declared width before the allow-list is consulted.
     *
     * <p>Assumptions: the field is two characters wide, declared {@code PIC X(2)} at
     * {@code app/cpy/CSLKPCDY.cpy} line 1012, and every literal of the list at line 1013 is written at
     * that width. A probe of any other width could not match a literal, so the width is asserted on
     * the captured value alongside the value itself, which is the only way to tell a padded probe from
     * a shorter one that the reference simply did not hold.</p>
     *
     * <p>Returns no value. A probe of any width other than the declared one, or of any value other
     * than the padded or truncated candidate, is reported as a JUnit assertion failure.</p>
     *
     * @param stateCode the candidate as submitted, shorter than the declared width, longer than it,
     *     empty or absent altogether
     * @param expectedProbe the exact two-character value the allow-list probe must receive
     */
    @ParameterizedTest
    @MethodSource("stateCodeWidthVectors")
    @DisplayName("a state code is brought to the declared width before the allow-list is consulted")
    void aStateCodeIsBroughtToTheDeclaredWidthBeforeLookup(String stateCode, String expectedProbe) {
        this.service.validateStateCode(stateCode, STATE_FIELD, STATE_LABEL);

        ArgumentCaptor<String> probe = ArgumentCaptor.forClass(String.class);
        verify(this.lookup).stateCodeExists(probe.capture());

        assertThat(probe.getValue())
                .hasSize(AddressValidationService.STATE_CODE_WIDTH)
                .isEqualTo(expectedProbe);
    }

    /**
     * Confirms the state and postal pairing probes exactly the first four characters.
     *
     * <p>Assumptions: four characters take part and no more, because the condition name at
     * {@code app/cpy/CSLKPCDY.cpy} line 1073 sits on the {@code 02 US-STATE-AND-FIRST-ZIP2 PIC X(4).}
     * of line 1072 rather than on the {@code 01} group of line 1071. That group totals seven
     * characters once the three-character item of line 1314 is counted, so a probe assembled from the
     * group would be three characters too long and would match no literal. The width is asserted on
     * the captured value for that reason, and the order of the two halves is asserted with it, since a
     * probe of the right width in the wrong order would also match nothing.</p>
     *
     * <p>Returns no value. A probe of any width other than four, of the two halves in the wrong order,
     * or a refused pairing the reference reported as held, is reported as a JUnit assertion
     * failure.</p>
     */
    @Test
    @DisplayName("the state and postal pairing probes exactly the first four characters")
    void theStateAndPostalPairingProbesExactlyFourCharacters() {
        when(this.lookup.stateZipPrefixExists(anyString())).thenReturn(true);

        AddressValidationService.AddressValidationResult result =
                this.service.validateStateZipCombination(
                        LISTED_STATE_CODE, PAIRED_ZIP_CODE, STATE_FIELD, ZIP_FIELD);

        ArgumentCaptor<String> probe = ArgumentCaptor.forClass(String.class);
        verify(this.lookup).stateZipPrefixExists(probe.capture());

        assertThat(probe.getValue())
                .hasSize(AddressValidationService.STATE_ZIP_PREFIX_WIDTH)
                .isEqualTo(LISTED_STATE_ZIP_PREFIX)
                .startsWith(LISTED_STATE_CODE)
                .endsWith(PAIRED_ZIP_CODE.substring(0, AddressValidationService.ZIP_PREFIX_DIGITS));
        assertThat(result.isValid()).isTrue();
    }

    /**
     * Confirms a pairing outside the allow-list reports BOTH fields and one shared wording.
     *
     * <p>Refactoring Rationale: two entries are expected where a single-field validator would produce
     * one, and the paragraph is why. {@code app/cbl/COACTUPC.cbl} sets both the state marker at line
     * 2546 and the postal marker at line 2547 for this one condition, so the baseline highlights both
     * boxes, while composing exactly one message at line 2550. A single entry would highlight one box
     * and leave a user to guess which half of a pairing to change, in the one check whose entire
     * subject is that the two halves disagree.</p>
     *
     * <p>Assumptions: this wording alone takes no label prefix. The assembly at
     * {@code app/cbl/COACTUPC.cbl} lines 2549 and 2550 goes straight from the verb to the literal with
     * no trimmed label between them, where every neighbouring message in this area has one, so the
     * expected text is the bare literal and prefixing it to match its neighbours would add text the
     * baseline never emits.</p>
     *
     * <p>Returns no value. A single entry, an entry naming a field other than the two submitted, an
     * order other than state before postal, or a wording carrying a label prefix, is reported as a
     * JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a pairing outside the allow-list reports both fields with one shared wording")
    void aPairingOutsideTheAllowListReportsBothFields() {
        AddressValidationService.AddressValidationResult result =
                this.service.validateStateZipCombination(
                        LISTED_STATE_CODE, UNPAIRED_ZIP_CODE, STATE_FIELD, ZIP_FIELD);

        assertThat(result.isValid()).isFalse();
        assertThat(result.fieldErrors())
                .extracting(ApiError.FieldError::field)
                .containsExactly(STATE_FIELD, ZIP_FIELD);
        assertThat(result.fieldErrors()).allSatisfy(entry -> {
            assertThat(entry.isError()).isTrue();
            assertThat(entry.state().isError()).isTrue();
            assertThat(entry.state().requiresBlankMarker()).isFalse();
            assertThat(entry.screenMarker()).isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
            assertThat(entry.message())
                    .isEqualTo(AddressValidationService.MSG_STATE_ZIP_COMBINATION_INVALID);
        });
        assertThat(result.message())
                .isEqualTo(AddressValidationService.MSG_STATE_ZIP_COMBINATION_INVALID)
                .doesNotStartWith(STATE_LABEL);
    }

    /**
     * Confirms the postal characters after the first two change neither the probe nor the outcome.
     *
     * <p>Assumptions: only the first two postal characters take part, which
     * {@code app/cbl/COACTUPC.cbl} line 2538 fixes with the reference modification
     * {@code ACUP-NEW-CUST-ADDR-ZIP(1:2)}. Every vector below shares those two characters and differs
     * in everything after them, including one vector shorter than the others, so all five must produce
     * the identical probe. A validator that read further into the postal code would produce five
     * different probes and only the first would match a literal.</p>
     *
     * <p>Returns no value. A probe differing between vectors, or an outcome differing between them, is
     * reported as a JUnit assertion failure.</p>
     *
     * @param zipCode the candidate postal code, sharing its first two characters with every other
     *     vector and differing from them in every position after that
     */
    @ParameterizedTest
    @ValueSource(strings = {"22201", "22999", "22000", "22ABC", "22"})
    @DisplayName("postal characters after the first two change neither the probe nor the outcome")
    void postalCharactersAfterTheFirstTwoChangeNothing(String zipCode) {
        when(this.lookup.stateZipPrefixExists(LISTED_STATE_ZIP_PREFIX)).thenReturn(true);

        AddressValidationService.AddressValidationResult result =
                this.service.validateStateZipCombination(
                        LISTED_STATE_CODE, zipCode, STATE_FIELD, ZIP_FIELD);

        verify(this.lookup).stateZipPrefixExists(LISTED_STATE_ZIP_PREFIX);
        verifyNoMoreInteractions(this.lookup);
        assertThat(result.isValid()).isTrue();
    }

    /**
     * Confirms the trailing postal characters are carried across with no rule applied to them at all.
     *
     * <p>Assumptions: {@code app/cpy/CSLKPCDY.cpy} declares {@code 02 LAST-3-OF-ZIP PIC X(3).} at line
     * 1314 and attaches NO condition name to it, which is why that file holds five allow-lists rather
     * than six. The baseline validates nothing in those three positions; the migrated service
     * validates nothing there either; the absence is recorded rather than filled. This case exists
     * separately from the invariance case above because the two assert different things: that one
     * asserts a shared prefix yields a shared answer, while this one submits characters no postal code
     * would plausibly hold and requires them to be accepted regardless.</p>
     *
     * <p>Assumptions: it also pins that no SECOND probe is issued for those characters. A validator
     * that had grown a rule for them would most naturally express it as a further lookup, and the
     * absence of any further interaction is what detects that.</p>
     *
     * <p>Returns no value. A refusal attributable to the trailing characters, a probe carrying any of
     * them, or any further interaction with the reference, is reported as a JUnit assertion
     * failure.</p>
     *
     * @param zipCode the candidate postal code whose characters after the first two are ones no
     *     validated postal code would hold
     */
    @ParameterizedTest
    @ValueSource(strings = {"22-!?", "22   ", "22AB", "22$%^"})
    @DisplayName("the trailing postal characters are carried across with no rule applied to them")
    void theTrailingPostalCharactersAreCarriedUnvalidated(String zipCode) {
        when(this.lookup.stateZipPrefixExists(LISTED_STATE_ZIP_PREFIX)).thenReturn(true);

        AddressValidationService.AddressValidationResult result =
                this.service.validateStateZipCombination(
                        LISTED_STATE_CODE, zipCode, STATE_FIELD, ZIP_FIELD);

        assertThat(result.isValid()).isTrue();
        verify(this.lookup, times(1)).stateZipPrefixExists(LISTED_STATE_ZIP_PREFIX);
        verify(this.lookup, never()).stateCodeExists(anyString());
        verifyNoMoreInteractions(this.lookup);
    }

    /**
     * Confirms the service refuses to exist without the contract that reads the allow-lists.
     *
     * <p>Assumptions: this is asserted because the failure it prevents is silent in the worst
     * direction. Every rule in the service consults the contract, so a service constructed without one
     * could only report every address as acceptable, and an address edit that accepts everything looks
     * exactly like an address edit that is working.</p>
     *
     * <p>Returns no value. A service constructed without the contract, or a rejection naming something
     * other than the missing contract, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("the service refuses to exist without the contract that reads the allow-lists")
    void theServiceRefusesToExistWithoutTheLookupContract() {
        assertThatThrownBy(() -> new AddressValidationService(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("lookup");
    }

    /**
     * Confirms a failure reading the allow-lists propagates and is never turned into a refusal.
     *
     * <p>Alternatives Considered: converting an unreachable reference into a refusal was the obvious
     * defensive shape and is the one behaviour this case forbids, because it would report a
     * general-purpose area code as outside the general-purpose list whenever the peer was slow -- a
     * wrong answer presented as a validated one. Propagating leaves the caller to answer with a
     * transport failure, which is a different answer a user can act on differently.</p>
     *
     * <p>Alternatives Considered: a retry and a circuit breaker were both evaluated for this hop and
     * both declined, so the probe count is asserted rather than left open. The call is a synchronous
     * hop inside the private network bounded by explicit connect and read timeouts; a retry behind
     * such a timeout multiplies the worst-case wait for an address edit by the attempt count, and a
     * breaker adds a state machine able to refuse a call the peer would have served. Pinning exactly
     * one probe is what makes either addition fail here rather than pass unnoticed.</p>
     *
     * <p>Returns no value. A swallowed failure, a substituted exception, or more than one probe, is
     * reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a failure reading the allow-lists propagates and is never turned into a refusal")
    void aFailureReadingTheAllowListsPropagates() {
        IllegalStateException transportFailure =
                new IllegalStateException("the reference context could not be reached");
        when(this.lookup.findAreaCodeClass(GENERAL_PURPOSE_AREA_CODE)).thenThrow(transportFailure);

        assertThatThrownBy(() -> this.service.validateAreaCode(
                GENERAL_PURPOSE_AREA_CODE, AREA_CODE_FIELD, PHONE_LABEL))
                .isSameAs(transportFailure);

        verify(this.lookup, times(1)).findAreaCodeClass(GENERAL_PURPOSE_AREA_CODE);
        verifyNoMoreInteractions(this.lookup);
    }

    /**
     * Confirms an unassigned code and an easily-recognisable one are refused alike yet classify apart.
     *
     * <p>Assumptions: the two questions the telephone target answers are genuinely different, and this
     * is the case that separates them. The edit gated on {@code app/cbl/COACTUPC.cbl} line 2298 refuses
     * both candidates with the single wording of line 2306, so a caller reading only the refusal cannot
     * tell an unassigned code from an assigned one this path happens to decline. The classification
     * reports the difference, because the first appears in none of the lists at lines 30, 521 and 931
     * while the second appears in those at lines 30 and 931.</p>
     *
     * <p>Assumptions: the shared wording is asserted as identical rather than merely present. Emitting
     * two different messages here would be a plausible improvement and is exactly what the baseline
     * does not do -- line 2306 is the only wording that paragraph composes for either candidate.</p>
     *
     * <p>Returns no value. A differing wording between the two candidates, an accepted candidate, or a
     * classification that fails to separate them, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an unassigned and an easily-recognisable code are refused alike yet classify apart")
    void anUnassignedAndAnEasilyRecognisableCodeAreRefusedAlike() {
        when(this.lookup.findAreaCodeClass(UNASSIGNED_AREA_CODE)).thenReturn(Optional.empty());
        when(this.lookup.findAreaCodeClass(SECOND_EASILY_RECOGNISABLE_AREA_CODE))
                .thenReturn(Optional.of(
                        AddressValidationService.AreaCodeClass.EASILY_RECOGNISABLE));

        String sharedWording =
                PHONE_LABEL + AddressValidationService.MSG_AREA_CODE_NOT_GENERAL_PURPOSE;

        assertUnacceptableValue(
                onlyEntryOf(this.service.validateAreaCode(
                        UNASSIGNED_AREA_CODE, AREA_CODE_FIELD, PHONE_LABEL)),
                AREA_CODE_FIELD, sharedWording);
        assertUnacceptableValue(
                onlyEntryOf(this.service.validateAreaCode(
                        SECOND_EASILY_RECOGNISABLE_AREA_CODE, AREA_CODE_FIELD, PHONE_LABEL)),
                AREA_CODE_FIELD, sharedWording);

        assertThat(this.service.classifyAreaCode(UNASSIGNED_AREA_CODE).assigned()).isFalse();
        assertThat(this.service.classifyAreaCode(SECOND_EASILY_RECOGNISABLE_AREA_CODE).assigned())
                .isTrue();
    }

    /**
     * Confirms the telephone target and the state target are read through separate probes.
     *
     * <p>Assumptions: the copybook's three targets are three independent fields -- lines 24, 1012 and
     * 1072 -- so a candidate accepted as an area code says nothing about the same characters as a state
     * code, and the service must not answer one target from the other's probe. Each edit is therefore
     * required to issue its own probe and no other, which is what a shared cache keyed on the raw
     * characters rather than on the target would break.</p>
     *
     * <p>Returns no value. A state probe issued during a telephone edit, a telephone probe issued
     * during a state edit, or a pairing probe issued by either, is reported as a JUnit assertion
     * failure.</p>
     */
    @Test
    @DisplayName("the telephone and state targets are read through separate probes")
    void theTelephoneAndStateTargetsAreReadThroughSeparateProbes() {
        when(this.lookup.findAreaCodeClass(EASILY_RECOGNISABLE_AREA_CODE))
                .thenReturn(Optional.of(
                        AddressValidationService.AreaCodeClass.EASILY_RECOGNISABLE));

        this.service.validateAreaCode(
                EASILY_RECOGNISABLE_AREA_CODE, AREA_CODE_FIELD, PHONE_LABEL);

        verify(this.lookup).findAreaCodeClass(EASILY_RECOGNISABLE_AREA_CODE);
        verify(this.lookup, never()).stateCodeExists(anyString());
        verify(this.lookup, never()).stateZipPrefixExists(anyString());

        when(this.lookup.stateCodeExists(UNLISTED_STATE_CODE)).thenReturn(false);
        this.service.validateStateCode(UNLISTED_STATE_CODE, STATE_FIELD, STATE_LABEL);

        verify(this.lookup).stateCodeExists(UNLISTED_STATE_CODE);
        verify(this.lookup, times(1)).findAreaCodeClass(anyString());
        verify(this.lookup, never()).stateZipPrefixExists(anyString());
    }

    // WHY : Refactoring Rationale: the cases from here to the end of the class drive the FOURTH
    //       public entry point of the service, and it had no direct case at all. The three edits
    //       above reach it only in part -- the area-code edit is one of the three parts it composes
    //       -- so nothing exercised the split of a stored number into its three parts, the optional
    //       whole-number path, the accumulation across parts, or either of the two part edits that
    //       are private and reachable only through it. A validator that returned after its first
    //       failing part, or that read the parts from the wrong offsets, would have passed every
    //       assertion in this class as it stood while reporting one part per submission where the
    //       baseline reports every wrong part at once.
    // WHY : Assumptions: the stored layout these cases submit is the one app/cbl/COACTUPC.cbl
    //       declares at its lines 82 to 100 -- a three-character area code, a separator, a
    //       three-character prefix, a separator, then a four-character line number, fifteen
    //       characters in total -- so a vector is written as the whole stored value and never as
    //       three arguments. Writing the parts separately would test a composition this class
    //       invented rather than the one the field carries.

    /**
     * Builds a stored telephone number in the fifteen-character layout of lines 82 to 100.
     *
     * <p>Assumptions: the two separators are round brackets and a hyphen exactly as the program's
     * own layout declares them, and they are written here rather than taken from a constant on the
     * service because the service publishes offsets and widths and not the punctuation between
     * them. A vector built with different punctuation would still occupy the declared positions, so
     * these characters are inert to the edit and are chosen to read as a telephone number.</p>
     *
     * @param areaCode the three characters occupying the area-code position, which may be blank
     * @param phonePrefix the three characters occupying the prefix position, which may be blank
     * @param lineNumber the four characters occupying the line-number position, which may be blank
     * @return the fifteen-character stored value; never {@code null}
     */
    private static String storedPhone(String areaCode, String phonePrefix, String lineNumber) {
        return "(" + areaCode + ")" + phonePrefix + "-" + lineNumber;
    }

    /**
     * Confirms an entirely unsupplied telephone number is acceptable and reads no allow-list.
     *
     * <p>Assumptions: a telephone number is optional, which the program states in its own comment at
     * {@code app/cbl/COACTUPC.cbl} line 2233 and implements at lines 2234 to 2241 by setting the
     * valid marker and leaving the paragraph before any part is edited. The two vectors are the two
     * spellings of "not supplied" that reach this code -- a value padded with the space character
     * and a value absent altogether -- and both must answer acceptable rather than reporting three
     * failing parts.</p>
     *
     * <p>Returns no value. Any entry reported for an unsupplied number, a message on the outcome, or
     * a probe issued for a number nobody filled in, is reported as a JUnit assertion failure.</p>
     *
     * @param storedPhoneNumber the stored value as it reaches the edit, either blank throughout or
     *     absent altogether
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"(   )   -    ", "               ", ""})
    @DisplayName("an entirely unsupplied telephone number is acceptable and reads no allow-list")
    void anEntirelyUnsuppliedTelephoneNumberIsAcceptable(String storedPhoneNumber) {
        AddressValidationService.AddressValidationResult result =
                this.service.validateUsPhoneNumber(storedPhoneNumber, "phoneNumber1", PHONE_LABEL);

        assertThat(result.isValid()).isTrue();
        assertThat(result.fieldErrors()).isEmpty();
        assertThat(result.message()).isNull();

        // WHY : Assumptions: the absence of any probe is asserted rather than only the outcome,
        //       because a validator that padded a blank number to width and looked its area code up
        //       would also answer acceptable here -- it would find nothing in the list and then be
        //       overruled by the optional-number rule -- while issuing a call per empty form.
        verifyNoInteractions(this.lookup);
    }

    /**
     * Confirms a supplied and listed telephone number is acceptable and is reported under no field.
     *
     * <p>Assumptions: the area code is the general-purpose member the baseline's list test admits,
     * because the area-code part of a whole number is edited by the same paragraph the area-code
     * case above pins and that paragraph accepts nothing weaker. The prefix and line number carry no
     * allow-list of their own -- the program tests them for presence, digits and non-zero only, at
     * lines 2316 to 2367 and 2370 to 2421 -- so their values are ordinary digits.</p>
     *
     * <p>Returns no value. An entry reported for an acceptable number, a message on the outcome, or
     * more than the single area-code probe, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a supplied telephone number whose area code is listed is acceptable")
    void aSuppliedListedTelephoneNumberIsAcceptable() {
        when(this.lookup.findAreaCodeClass(GENERAL_PURPOSE_AREA_CODE))
                .thenReturn(Optional.of(AddressValidationService.AreaCodeClass.GENERAL_PURPOSE));

        AddressValidationService.AddressValidationResult result = this.service.validateUsPhoneNumber(
                storedPhone(GENERAL_PURPOSE_AREA_CODE, "555", "0123"), "phoneNumber1", PHONE_LABEL);

        assertThat(result.isValid()).isTrue();
        assertThat(result.fieldErrors()).isEmpty();
        assertThat(result.message()).isNull();

        // WHY : Assumptions: exactly one probe, for the area code alone. The prefix and the line
        //       number have no allow-list, so a probe issued for either would mean the edit was
        //       reading a reference table for a part the baseline validates locally.
        verify(this.lookup, times(1)).findAreaCodeClass(GENERAL_PURPOSE_AREA_CODE);
        verifyNoMoreInteractions(this.lookup);
    }

    /**
     * Confirms each part is read from its own declared position within the stored value.
     *
     * <p>Assumptions: the three positions are the ones the service publishes as offsets and widths,
     * and this case is what makes an off-by-one in any of them visible. Only ONE part is made
     * unacceptable per vector, and it is made unacceptable by a shape the other two parts would not
     * be refused for, so the identity in the reported entry names the part that actually held the
     * bad characters. The area-code vector uses a non-digit, and the two remaining vectors use an
     * all-zero part, because zero is a refusal the part owns rather than one inherited from the
     * digits test.</p>
     *
     * <p>Returns no value. An entry reported under a different part's identity, a wording belonging
     * to another part, or more than one entry, is reported as a JUnit assertion failure.</p>
     *
     * @param storedPhoneNumber the stored value carrying exactly one unacceptable part
     * @param expectedSuffix the identity suffix the failing part must be reported under
     * @param expectedMessage the wording, without its label prefix, the failing part must carry
     */
    @ParameterizedTest
    @MethodSource("singleFailingPartVectors")
    @DisplayName("each telephone part is read from its own position and reported under its own name")
    void eachTelephonePartIsReportedUnderItsOwnName(
            String storedPhoneNumber, String expectedSuffix, String expectedMessage) {

        // WHY : Assumptions: the listed answer is stubbed for every vector including the one whose
        //       area code never reaches the probe, because the alternative -- stubbing only where the
        //       probe is issued -- would make the stub itself part of what each vector asserts. The
        //       area-code vector fails its digits test first, at app/cbl/COACTUPC.cbl line 2264 ahead
        //       of line 2298, so the stub is simply never consulted there. No strictness check
        //       objects, because this class substitutes its port through a direct call rather than
        //       through the framework's JUnit extension.
        when(this.lookup.findAreaCodeClass(GENERAL_PURPOSE_AREA_CODE))
                .thenReturn(Optional.of(AddressValidationService.AreaCodeClass.GENERAL_PURPOSE));

        AddressValidationService.AddressValidationResult result =
                this.service.validateUsPhoneNumber(storedPhoneNumber, "phoneNumber1", PHONE_LABEL);

        assertUnacceptableValue(onlyEntryOf(result), "phoneNumber1" + expectedSuffix,
                PHONE_LABEL + expectedMessage);
    }

    /**
     * Supplies one vector per telephone part, each with that part alone unacceptable.
     *
     * <p>Assumptions: the expected identities and wordings are read from the service's own published
     * constants rather than spelled here, so a case cannot pass by agreeing with a transcription of
     * the baseline literal that has drifted from the one the service actually emits.</p>
     *
     * @return the vectors, each carrying the stored value, the identity suffix of the failing part
     *     and that part's expected wording; never {@code null}
     */
    private static Stream<Arguments> singleFailingPartVectors() {
        return Stream.of(
                Arguments.of(storedPhone("7O3", "555", "0123"),
                        AddressValidationService.FIELD_SUFFIX_AREA_CODE,
                        AddressValidationService.MSG_AREA_CODE_NOT_THREE_DIGITS),
                Arguments.of(storedPhone(GENERAL_PURPOSE_AREA_CODE, "000", "0123"),
                        AddressValidationService.FIELD_SUFFIX_PHONE_PREFIX,
                        AddressValidationService.MSG_PREFIX_ZERO),
                Arguments.of(storedPhone(GENERAL_PURPOSE_AREA_CODE, "555", "0000"),
                        AddressValidationService.FIELD_SUFFIX_PHONE_LINE_NUMBER,
                        AddressValidationService.MSG_LINE_NUMBER_ZERO));
    }

    /**
     * Confirms a partly-filled number reports the missing parts rather than being treated as absent.
     *
     * <p>Assumptions: the optional-number rule requires ALL THREE parts to be unsupplied, so a
     * number with one part filled is a supplied number with two parts missing. This is the case that
     * separates the two readings of the baseline's line 2234 condition: a validator testing "any part
     * blank" instead of "every part blank" would accept this input silently and store a fragment.</p>
     *
     * <p>Returns no value. An acceptable outcome, an entry count other than two, or an entry that is
     * not the never-supplied state, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a number with only one part filled reports the two missing parts")
    void aNumberWithOnlyOnePartFilledReportsTheMissingParts() {
        when(this.lookup.findAreaCodeClass(GENERAL_PURPOSE_AREA_CODE))
                .thenReturn(Optional.of(AddressValidationService.AreaCodeClass.GENERAL_PURPOSE));

        AddressValidationService.AddressValidationResult result = this.service.validateUsPhoneNumber(
                storedPhone(GENERAL_PURPOSE_AREA_CODE, "   ", "    "), "phoneNumber1", PHONE_LABEL);

        assertThat(result.isValid()).isFalse();
        assertThat(result.fieldErrors()).hasSize(2);
        assertThat(result.fieldErrors().get(0).field())
                .isEqualTo("phoneNumber1" + AddressValidationService.FIELD_SUFFIX_PHONE_PREFIX);
        assertThat(result.fieldErrors().get(0).message())
                .isEqualTo(PHONE_LABEL + AddressValidationService.MSG_PREFIX_BLANK);
        assertThat(result.fieldErrors().get(0).screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(result.fieldErrors().get(1).field())
                .isEqualTo("phoneNumber1" + AddressValidationService.FIELD_SUFFIX_PHONE_LINE_NUMBER);
        assertThat(result.fieldErrors().get(1).message())
                .isEqualTo(PHONE_LABEL + AddressValidationService.MSG_LINE_NUMBER_BLANK);
    }

    /**
     * Confirms every failing part is reported, in order, with the FIRST wording latched.
     *
     * <p>Assumptions: the parts accumulate rather than short-circuiting, which the baseline states by
     * transferring from each failing area-code branch to the PREFIX sub-paragraph -- at
     * {@code app/cbl/COACTUPC.cbl} lines 2259, 2277 and 2291 -- and from each failing prefix branch
     * to the line-number sub-paragraph at lines 2330, 2348 and 2362. Not one of those transfers names
     * the exit. This case is therefore the one that fails if the composition is rewritten to return
     * on its first failing part, which would ask an operator to correct one part per submission.</p>
     *
     * <p>Assumptions: the aggregate message is the FIRST part's wording and not the last, because
     * every emitting site in the baseline is wrapped in a guard that writes the single message line
     * only while it still holds its off value, at line 2251 and its siblings. Three joined wordings
     * would also exceed the seventy-five characters that field is declared at on line 479.</p>
     *
     * <p>Returns no value. A missing part entry, entries out of evaluation order, or an aggregate
     * message other than the first part's, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("every failing telephone part is reported in order with the first wording latched")
    void everyFailingTelephonePartIsReportedInOrder() {
        AddressValidationService.AddressValidationResult result = this.service.validateUsPhoneNumber(
                storedPhone("00A", "0B0", "00C0"), "phoneNumber1", PHONE_LABEL);

        assertThat(result.isValid()).isFalse();
        assertThat(result.fieldErrors()).hasSize(3);
        assertThat(result.fieldErrors().stream().map(ApiError.FieldError::field).toList())
                .containsExactly(
                        "phoneNumber1" + AddressValidationService.FIELD_SUFFIX_AREA_CODE,
                        "phoneNumber1" + AddressValidationService.FIELD_SUFFIX_PHONE_PREFIX,
                        "phoneNumber1" + AddressValidationService.FIELD_SUFFIX_PHONE_LINE_NUMBER);
        assertThat(result.message())
                .isEqualTo(PHONE_LABEL + AddressValidationService.MSG_AREA_CODE_NOT_THREE_DIGITS);

        // WHY : Assumptions: the later wordings are asserted to be PRESENT on their own entries even
        //       though the aggregate carries only the first. The two are different contracts -- one
        //       message line, but a per-field array -- and a composition that dropped the later
        //       wordings while keeping the identities would still satisfy the aggregate assertion.
        assertThat(result.fieldErrors().get(1).message())
                .isEqualTo(PHONE_LABEL + AddressValidationService.MSG_PREFIX_NOT_THREE_DIGITS);
        assertThat(result.fieldErrors().get(2).message())
                .isEqualTo(PHONE_LABEL + AddressValidationService.MSG_LINE_NUMBER_NOT_FOUR_DIGITS);

        // WHY : Assumptions: no probe is issued, because the area code failed its digits test and the
        //       baseline reaches the list test only for a value that passed it, at line 2264 ahead of
        //       line 2298.
        verifyNoInteractions(this.lookup);
    }

    /**
     * Confirms an area code the allow-lists do not hold is refused through the whole-number edit too.
     *
     * <p>Assumptions: the refusal has to survive composition, and this is the case that proves it.
     * The two vectors are the two ways the list test can refuse -- a code in none of the three lists,
     * and a code the broad list holds but the general-purpose list does not -- and the baseline
     * accepts only the general-purpose member, testing it at line 2298. The prefix and line number
     * are acceptable in both vectors, so the single entry attributes the refusal to the area code.</p>
     *
     * <p>Returns no value. An acceptable outcome, an entry under another part's identity, or a
     * missing probe, is reported as a JUnit assertion failure.</p>
     *
     * @param areaCode the area code the substituted probe is asked about
     * @param storedClass the classification the probe answers with, or {@link #NO_CLASS_TOKEN} when
     *     it finds the code in none of the three lists
     */
    @ParameterizedTest
    @CsvSource({
        "111, -",
        "999, E",
    })
    @DisplayName("an unlisted area code is refused through the whole-number edit as well")
    void anUnlistedAreaCodeIsRefusedThroughTheWholeNumberEdit(String areaCode, String storedClass) {
        when(this.lookup.findAreaCodeClass(areaCode)).thenReturn(answerFor(storedClass));

        AddressValidationService.AddressValidationResult result = this.service.validateUsPhoneNumber(
                storedPhone(areaCode, "555", "0123"), "phoneNumber1", PHONE_LABEL);

        assertUnacceptableValue(onlyEntryOf(result),
                "phoneNumber1" + AddressValidationService.FIELD_SUFFIX_AREA_CODE,
                PHONE_LABEL + AddressValidationService.MSG_AREA_CODE_NOT_GENERAL_PURPOSE);
        verify(this.lookup, times(1)).findAreaCodeClass(areaCode);
    }

    /**
     * Confirms a stored value shorter or longer than the declared width is brought to width first.
     *
     * <p>Assumptions: a stored value is normalised to the declared fifteen characters before any part
     * is extracted, so a short value pads on the right and a long one truncates there -- which is
     * what a COBOL move into a fixed-width field does. The short vector therefore has an area code
     * and nothing else, and its two remaining parts read as never supplied rather than as absent
     * positions; the long vector's trailing characters fall outside every part and change nothing.
     * Without normalisation a short value would either raise from a substring or silently shift the
     * later parts leftwards, and both would attribute characters to the wrong part.</p>
     *
     * <p>Returns no value. A raised exception, or an entry set other than the one the normalised
     * value implies, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a stored telephone value is brought to the declared width before it is split")
    void aStoredTelephoneValueIsBroughtToTheDeclaredWidth() {
        when(this.lookup.findAreaCodeClass(GENERAL_PURPOSE_AREA_CODE))
                .thenReturn(Optional.of(AddressValidationService.AreaCodeClass.GENERAL_PURPOSE));

        AddressValidationService.AddressValidationResult truncated =
                this.service.validateUsPhoneNumber("(" + GENERAL_PURPOSE_AREA_CODE,
                        "phoneNumber1", PHONE_LABEL);

        assertThat(truncated.fieldErrors().stream().map(ApiError.FieldError::field).toList())
                .containsExactly(
                        "phoneNumber1" + AddressValidationService.FIELD_SUFFIX_PHONE_PREFIX,
                        "phoneNumber1" + AddressValidationService.FIELD_SUFFIX_PHONE_LINE_NUMBER);

        AddressValidationService.AddressValidationResult overlong =
                this.service.validateUsPhoneNumber(
                        storedPhone(GENERAL_PURPOSE_AREA_CODE, "555", "0123") + "9999",
                        "phoneNumber1", PHONE_LABEL);

        assertThat(overlong.isValid()).isTrue();
    }

    /**
     * Confirms an unreachable reference propagates instead of becoming a telephone refusal.
     *
     * <p>Assumptions: the whole-number edit must inherit the same disposition the area-code edit has
     * -- a failure to READ the allow-list is not evidence that the value is wrong -- and inheritance
     * is not automatic here, because this method composes three edits and merges their outcomes. A
     * composition that caught the failure and reported the area code as invalid would turn a
     * reference-data outage into a wave of refused telephone numbers that look like user error.</p>
     *
     * <p>Returns no value. A refusal outcome in place of a propagated failure, or a different failure
     * instance reaching the caller, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an unreachable allow-list propagates from the whole-number edit unchanged")
    void anUnreachableAllowListPropagatesFromTheWholeNumberEdit() {
        RuntimeException unreachable = new IllegalStateException("reference service unreachable");
        when(this.lookup.findAreaCodeClass(GENERAL_PURPOSE_AREA_CODE)).thenThrow(unreachable);

        assertThatThrownBy(() -> this.service.validateUsPhoneNumber(
                storedPhone(GENERAL_PURPOSE_AREA_CODE, "555", "0123"), "phoneNumber1", PHONE_LABEL))
                .isSameAs(unreachable);
    }

    /**
     * Confirms the whole-number edit refuses a missing identity or label before doing any work.
     *
     * <p>Assumptions: both are refused with an unchecked failure rather than defaulted, because an
     * entry has to be reported under some identity and a message has to be prefixed with some label;
     * inventing either would produce a per-field entry no client could bind to a control, or a
     * message an operator reads as belonging to a different field. The check is asserted to happen
     * before any probe, so a caller's programming error is not mixed with a reference read.</p>
     *
     * <p>Returns no value. An accepted null identity or label, or a probe issued despite one, is
     * reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("the whole-number edit refuses a missing identity or label before any lookup")
    void theWholeNumberEditRefusesAMissingIdentityOrLabel() {
        String stored = storedPhone(GENERAL_PURPOSE_AREA_CODE, "555", "0123");

        assertThatThrownBy(() -> this.service.validateUsPhoneNumber(stored, null, PHONE_LABEL))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> this.service.validateUsPhoneNumber(stored, "phoneNumber1", null))
                .isInstanceOf(NullPointerException.class);

        verifyNoInteractions(this.lookup);
    }
}
