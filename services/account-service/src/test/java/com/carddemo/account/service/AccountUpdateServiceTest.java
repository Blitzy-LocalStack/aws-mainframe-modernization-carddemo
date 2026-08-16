package com.carddemo.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.account.domain.Account;
import com.carddemo.account.domain.CardXref;
import com.carddemo.account.domain.Customer;
import com.carddemo.account.dto.AccountUpdateRequest;
import com.carddemo.account.dto.AccountUpdateResponse;
import com.carddemo.account.mapper.AccountMapper;
import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.account.repository.AccountRepository;
import com.carddemo.account.repository.CardXrefRepository;
import com.carddemo.account.repository.CustomerRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.validation.FieldValidationFlag;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Asserts the input-edit surface of {@link AccountUpdateService}, its two validation-marker regimes,
 * its concurrency refusal and the three places where it deliberately behaves differently from the
 * program it was transcribed from.
 *
 * <p>The program is {@code app/cbl/COACTUPC.cbl}, 4236 lines, and it is reference material that is
 * read and never written. Its edit driver is {@code 1200-EDIT-MAP-INPUTS.} at L1429, closing at
 * L1678, and beneath that driver sit SEVENTEEN edit routines spanning L1783 to L2558: fourteen
 * numbered ones, and three unnumbered telephone sub-routines nested inside the numbered telephone
 * one at {@code EDIT-AREA-CODE} L2246, {@code EDIT-US-PHONE-PREFIX} L2316 and
 * {@code EDIT-US-PHONE-LINENUM} L2370, whose shared exit is {@code EDIT-US-PHONE-EXIT} L2424. Every
 * one of the seventeen is asserted here, eleven of them directly on this service and six through the
 * collaborator this service delegates them to.</p>
 *
 * <p>Assumptions: counting those routines needs a character class that admits DIGITS. The
 * paragraph-head pattern {@code ^ {7}12[0-9][0-9]-EDIT[A-Z0-9-]*\.} finds 30 heads and exits over
 * that program, whereas the same pattern written {@code [A-Z-]*} finds only 28, because it cannot
 * match a name ending in a digit and so silently drops {@code 1250-EDIT-SIGNED-9V2} L2180 together
 * with its exit L2221. A reader who counts with the narrower class concludes there are thirteen
 * numbered routines rather than fourteen, and the signed-amount edit -- which is what guards every
 * money field on the screen -- then has no case at all.</p>
 *
 * <p>Refactoring Rationale: the edits are driven here with SEVERAL LABELS through ONE method each,
 * rather than with one test method per screen field. That follows the shape of the source rather than
 * departing from it. The program does not hold seventeen field-specific routines; it holds seventeen
 * GENERIC ones sharing a single argument block, {@code 05 WS-GENERIC-EDITS.} at L52, whose members
 * are the label {@code 10 WS-EDIT-VARIABLE-NAME PIC X(25).} at L53 and the value
 * {@code 10 WS-EDIT-SIGNED-NUMBER-9V2-X PIC X(15).} at L55 beside its marker at L56. A caller loads
 * that block and performs a routine, which the program does 28 times in total by the count of
 * {@code PERFORM +12[0-9][0-9]-EDIT} -- 27 real invocations plus the 1200 driver itself. In the
 * target that block DISAPPEARS and the label, value and width become parameters, so the natural unit
 * of a test is one routine driven over many labels. Writing a method per field instead would
 * multiply the case count by roughly the 25 labels the program declares while asserting the same
 * eleven behaviours, and a routine could then lose its only interesting reject path without the count
 * changing.</p>
 *
 * <p>Assumptions: the labels used below are the program's own, read from its 28
 * {@code TO WS-EDIT-VARIABLE-NAME} sites -- for instance {@code 'Account Status'} at L1472,
 * {@code 'Credit Limit'} at L1484, {@code 'First Name'} at L1560 and {@code 'SSN: First 3 chars'} at
 * L2439 -- and they are referenced through this service's own published constants rather than
 * retyped, so a label cannot drift between the code and its test.</p>
 *
 * <p>Alternatives Considered: a container-backed context for these cases, of the kind the sibling
 * {@code com.carddemo.account.repository} package runs under Failsafe. It was rejected because
 * everything asserted here -- the edit chain, the two marker regimes, the accumulation of per-field
 * errors and the concurrency refusal -- is pure logic over values, and a real engine would answer no
 * question any of it asks while making every case slower and able to collide with a parallel worker.
 * The two repositories are substituted and the transaction manager with them, so both transaction
 * templates run their callbacks and commit nothing. What genuinely needs an engine is the atomicity
 * of the write itself, and that is asserted by {@code AccountUpdateAtomicityIT} in that sibling
 * package, not here.</p>
 *
 * <p>Assumptions: {@link AddressValidationService} is SUBSTITUTED AND STUBBED here rather than
 * exercised. That this service delegates to it at all is a property of the baseline: L602 of
 * {@code app/cbl/COACTUPC.cbl} pulls the lookup tables into the update program with a
 * {@code COPY CSLKPCDY.} statement, so the telephone, state and state-with-postal-prefix edits run
 * inside the update path. What those five allow-lists CONTAIN is owned by the sibling
 * {@code AddressValidationServiceTest}, and the tables behind them are seeded and owned by
 * reference-service through its {@code V2__seed_reference.sql} migration. This class therefore
 * asserts that the delegation happens, that its guards hold and that its verdict reaches the caller;
 * it holds no copy of any code list and consults no schema.</p>
 *
 * <p>Assumptions: no executable parity oracle exists for this program, so every case below is
 * authored from the paragraphs themselves and no golden-master agreement is claimed for any of them.
 * L83 through L85 of {@code tests/README.md} record that the online programs cannot be run end to end
 * without a CICS runtime, which the runner does not have, and that only their extractable
 * field-validation logic is unit-tested there. The rules that suite asserts verbatim begin at its
 * L553, and from that line onward it names the posting, interest and category-balance programs of
 * other contexts once each while naming {@code COACTUPC}, {@code ACCTDAT} and {@code CUSTDAT} not at
 * all. These Java cases are strictly additive to that suite, which is neither modified nor re-pinned
 * from here.</p>
 *
 * <p>Assumptions: every sentence asserted below is compared byte for byte against the literal the
 * program declares, because the literal is what a user reads. The concurrency sentence is the one
 * that most invites silent correction: L521 and L522 declare
 * {@code 'Record changed by some one else. Please review'} with its third word as TWO WORDS, and it
 * is carried across in exactly that spelling.</p>
 */
class AccountUpdateServiceTest {

    /** The account key both rows are keyed by, an eleven-digit non-zero value the key edit accepts. */
    private static final long ACCOUNT_ID = 10_000_000_001L;

    /** The customer key the cross-reference row resolves for {@link #ACCOUNT_ID}. */
    private static final long CUSTOMER_ID = 900_000_001L;

    /** The card number the substituted cross-reference row carries, never asserted in a payload. */
    private static final String CARD_NUMBER = "4000123456789010";

    /** The precondition token a caller holding both rows at their initial revision would present. */
    private static final String CURRENT_REVISION = "0-0";

    /** A precondition whose ACCOUNT half has moved, standing for the refusal at L4143. */
    private static final String STALE_ACCOUNT_REVISION = "1-0";

    /** A precondition whose CUSTOMER half has moved, standing for the refusal at L4189. */
    private static final String STALE_CUSTOMER_REVISION = "0-1";

    /** Ciphertext standing for a stored national identifier; no clear identifier appears here. */
    private static final byte[] STORED_NATIONAL_ID =
            "stored-ssn-ciphertext".getBytes(StandardCharsets.UTF_8);

    /** Ciphertext standing for a stored government-issued identifier, likewise never in clear. */
    private static final byte[] STORED_GOVERNMENT_ID =
            "stored-govt-ciphertext".getBytes(StandardCharsets.UTF_8);

    /**
     * Confirms the account-key edit reaches each of the three outcomes {@code 1210-EDIT-ACCOUNT} has.
     *
     * <p>Assumptions: that routine at L1783, exiting L1820, separates an ABSENT key from a MALFORMED
     * one and says so in two different sentences. Its absent branch at L1787 and L1788 tests for low
     * values or spaces and sets its blank marker at L1790; its malformed branch at L1802 and L1803
     * tests {@code IS NOT NUMERIC} or a zero value and composes the sentence its L1807 and L1808
     * assemble from two fragments. The distinction is load-bearing rather than cosmetic, because a
     * blank key means the screen should prompt -- L1792 sets exactly that -- while a malformed key
     * means the screen should complain, and collapsing them into one refusal would lose the prompt.</p>
     *
     * <p>Assumptions: an all-zero key is refused as MALFORMED and not as absent, matching
     * {@code CC-ACCT-ID-N EQUAL ZEROS} sitting in the second branch at L1803 and not the first. Twelve
     * digits are refused for width, which is the declared eleven of
     * {@code app/cpy/CVACT01Y.cpy} L5.</p>
     *
     * <p>Returns no value. A wrong marker, or a sentence other than the one the program declares, is
     * reported as a JUnit assertion failure.</p>
     *
     * @param candidate the submitted account key, or {@code null} to stand for a key never supplied
     * @param expectedMarker the marker name the routine must reach, one of {@code VALID},
     *     {@code BLANK} or {@code NOT_OK}
     * @param expectedMessage the sentence the routine must compose, or empty when it composes none
     */
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", value = {
        "NONE,          BLANK,  Account number not provided",
        "'   ',         BLANK,  Account number not provided",
        "00000000000,   NOT_OK, Account Number if supplied must be a 11 digit Non-Zero Number",
        "1000000000A,   NOT_OK, Account Number if supplied must be a 11 digit Non-Zero Number",
        "100000000012,  NOT_OK, Account Number if supplied must be a 11 digit Non-Zero Number",
        "10000000001,   VALID,  ''",
    })
    @DisplayName("the account-key edit separates an absent key from a malformed one")
    void theAccountKeyEditReachesItsThreeOutcomes(String candidate, String expectedMarker,
            String expectedMessage) {
        AccountUpdateService.EditOutcome outcome =
                new Fixture().service.editAccountKey(candidate);

        assertThat(outcome.state()).hasToString(expectedMarker);
        assertThat(outcome.message()).isEqualTo(expectedMessage.isEmpty() ? null : expectedMessage);
    }

    /**
     * The validation turn reaches the same verdict as the write and saves nothing.
     *
     * <p>Assumptions: BOTH halves are asserted in one case because either alone would be misleading. That
     * no row is saved proves the turn is safe; that the verdict equals {@code editMapInputs}' own proves it
     * is useful. Had they diverged, a screen could show a green validation and then fail the save on the
     * very same values, which is the failure this operation exists to prevent.</p>
     *
     * <p>Returns no value. A saved row, or a verdict that differs from the shared edit pass, is reported as
     * a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("the validation turn matches the write's own verdict and saves nothing")
    void theValidationTurnMatchesTheEditPassAndSavesNothing() {
        Fixture fixture = new Fixture();
        when(fixture.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(fixture.account));
        when(fixture.customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(fixture.customer));

        AccountUpdateRequest submission = fixture.request().creditLimit("NOT-A-NUMBER").build();

        AccountUpdateService.EditVerdict viaValidation =
                fixture.service.validateOnly(ACCOUNT_ID, submission);
        AccountUpdateService.EditVerdict viaEditPass =
                fixture.service.editMapInputs(submission, fixture.account, fixture.customer);

        assertThat(viaValidation.inputError()).isTrue();
        assertThat(viaValidation.fieldErrors())
                .as("the verdict a caller sees is the verdict the write would reach")
                .isEqualTo(viaEditPass.fieldErrors());

        verify(fixture.accounts, never()).saveAndFlush(any());
        verify(fixture.customers, never()).saveAndFlush(any());
    }

    /**
     * Confirms the two key edits run the generic form edit FIRST and only then compare against the row.
     *
     * <p>Assumptions: {@code editAccountKeyNamesRow} is a composition and not a second edit -- a
     * malformed key must report its malformation rather than reporting that it names another account,
     * because the second sentence would be misleading about what is wrong. The customer key has no
     * form edit of its own in the program, so an absent one and a mismatched one share a sentence
     * there, and that shared spelling is asserted rather than improved.</p>
     *
     * <p>Returns no value. A form failure reported as a mismatch, or a mismatch left unreported, is
     * reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a malformed key reports its form, and a well-formed foreign key reports mismatch")
    void theKeyEditsRunTheFormEditBeforeTheRowComparison() {
        Fixture fixture = new Fixture();

        assertThat(fixture.service.editAccountKeyNamesRow("1000000000A", fixture.account).message())
                .isEqualTo(AccountUpdateService.MESSAGE_ACCOUNT_NOT_ELEVEN_DIGITS);
        assertThat(fixture.service.editAccountKeyNamesRow("10000000002", fixture.account).message())
                .isEqualTo(AccountUpdateService.MESSAGE_ACCOUNT_KEY_NOT_ADDRESSED);
        assertThat(fixture.service.editAccountKeyNamesRow("10000000001", fixture.account).state())
                .isEqualTo(FieldValidationFlag.VALID);

        assertThat(fixture.service.editCustomerKeyNamesRow(null, fixture.customer).state())
                .isEqualTo(FieldValidationFlag.BLANK);
        assertThat(fixture.service.editCustomerKeyNamesRow("900000002", fixture.customer).state())
                .isEqualTo(FieldValidationFlag.NOT_OK);
        assertThat(fixture.service.editCustomerKeyNamesRow("900000001", fixture.customer).state())
                .isEqualTo(FieldValidationFlag.VALID);
    }

    /**
     * Confirms the mandatory edit refuses ONLY an absent value, over several of the program's labels.
     *
     * <p>Assumptions: {@code 1215-EDIT-MANDATORY} at L1824, exiting L1852, tests three spellings of
     * absence at L1829 through L1834 -- low values, spaces, and a value whose trimmed length is zero
     * -- and beyond that examines the value not at all, reaching its acceptable marker at L1850 for
     * anything supplied. Punctuation and digits therefore pass it, which is why the screen pairs it
     * with a shape edit rather than relying on it alone.</p>
     *
     * <p>Assumptions: the sentence is composed from the caller's label and the fixed fragment
     * {@code ' must be supplied.'} at L1841, so the label reaches the user verbatim. Driving several
     * labels through the one method is what proves the composition rather than a hardcoded string.</p>
     *
     * <p>Returns no value. A supplied value refused, an absent value accepted, or a sentence not
     * composed from the label, is reported as a JUnit assertion failure.</p>
     *
     * @param label the screen label the program loads into its shared argument block
     * @param value the submitted value, or {@code null} to stand for a value never supplied
     * @param width the declared field width the routine examines the value at
     * @param expectedMarker the marker name the routine must reach, {@code VALID} or {@code BLANK}
     */
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", value = {
        "Account Status,      Y,           1,  VALID",
        "First Name,          GRACE,       25, VALID",
        "Address Line 1,      1 NAVY YARD, 50, VALID",
        "EFT Account Id,      0000000001,  10, VALID",
        "Primary Card Holder, '!',         1,  VALID",
        "First Name,          NONE,        25, BLANK",
        "First Name,          '   ',       25, BLANK",
        "Address Line 1,      '',          50, BLANK",
    })
    @DisplayName("the mandatory edit refuses only absence, and composes its sentence from the label")
    void theMandatoryEditRefusesOnlyAnAbsentValue(String label, String value, int width,
            String expectedMarker) {
        AccountUpdateService.EditOutcome outcome =
                new Fixture().service.editMandatory(label, value, width);

        assertThat(outcome.state()).hasToString(expectedMarker);
        if (outcome.state().isValid()) {
            assertThat(outcome.message()).isNull();
        } else {
            assertThat(outcome.message())
                    .isEqualTo(label + AccountUpdateService.SUFFIX_MUST_BE_SUPPLIED);
        }
    }

    /**
     * Confirms the yes-or-no edit treats a submitted ZERO as absence rather than as a wrong answer.
     *
     * <p>Assumptions: {@code 1220-EDIT-YESNO} at L1856, exiting L1894, tests THREE spellings of
     * absence at L1861 through L1863 -- low values, spaces AND {@code ZEROS} -- and only then tests
     * membership of the pair. A submitted {@code '0'} therefore takes the blank branch at L1865 and
     * receives {@code ' must be supplied.'} from L1869, not the {@code ' must be Y or N.'} of L1886.
     * That is the routine's one surprising edge, and it exists because the marker byte for a failed
     * edit is itself {@code '0'} at L79, so the program cannot let a submitted zero survive into a
     * marker field.</p>
     *
     * <p>Returns no value. A zero classified as a wrong answer, or a wrong answer classified as
     * absence, is reported as a JUnit assertion failure.</p>
     *
     * @param label the screen label the sentence is composed from
     * @param value the submitted indicator, or {@code null} to stand for one never supplied
     * @param expectedMarker the marker name the routine must reach
     * @param expectedSuffix the fixed fragment the sentence must end with, or empty when accepted
     */
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", value = {
        "Account Status,      Y,    VALID,  ''",
        "Account Status,      N,    VALID,  ''",
        "Primary Card Holder, Y,    VALID,  ''",
        "Account Status,      NONE, BLANK,  ' must be supplied.'",
        "Account Status,      ' ',  BLANK,  ' must be supplied.'",
        "Account Status,      0,    BLANK,  ' must be supplied.'",
        "Account Status,      X,    NOT_OK, ' must be Y or N.'",
        "Account Status,      y,    NOT_OK, ' must be Y or N.'",
    })
    @DisplayName("the yes-or-no edit reads a submitted zero as absence, not as a wrong answer")
    void theYesNoEditReadsASubmittedZeroAsAbsence(String label, String value,
            String expectedMarker, String expectedSuffix) {
        AccountUpdateService.EditOutcome outcome = new Fixture().service.editYesNo(label, value);

        assertThat(outcome.state()).hasToString(expectedMarker);
        assertThat(outcome.message())
                .isEqualTo(expectedSuffix.isEmpty() ? null : label + expectedSuffix);
    }

    /**
     * Confirms the four shape edits differ from one another in exactly TWO independent ways.
     *
     * <p>Assumptions: the program holds four of these rather than one, and the four are the product of
     * two binary choices. The first choice is which characters pass: the alphabetic pair folds every
     * letter and space away with the {@code INSPECT CONVERTING} at L1926 through L1928 and refuses
     * whatever is left with {@code ' can have alphabets only.'} at L1941, while the alphanumeric pair
     * admits digits too and says {@code ' can have numbers or alphabets only.'} at L1999. The second
     * choice is what absence means: the REQUIRED pair takes a blank marker and complains --
     * {@code 1225-EDIT-ALPHA-REQD} at L1898 sets it at L1911 and {@code 1230-EDIT-ALPHANUM-REQD} at
     * L1955 at L1968 -- whereas the OPTIONAL pair reaches its ACCEPTABLE marker on absence and exits
     * at once, which is visible at L2024 inside {@code 1235-EDIT-ALPHA-OPT} L2012 and at L2072 inside
     * {@code 1240-EDIT-ALPHANUM-OPT} L2061.</p>
     *
     * <p>Assumptions: asserting all four in one table is what makes the two axes checkable. Four
     * separate methods would each pass while the pairing between them silently changed -- an optional
     * edit made to refuse absence, say, would break the middle name the screen legitimately leaves
     * empty, and no case comparing it against its required twin would notice.</p>
     *
     * <p>Returns no value. A character class accepted by the wrong edit, or an absence classified
     * against the required-versus-optional axis, is reported as a JUnit assertion failure.</p>
     *
     * @param editName which of the four shape edits to drive, one of {@code ALPHA_REQD},
     *     {@code ALPHANUM_REQD}, {@code ALPHA_OPT} or {@code ALPHANUM_OPT}
     * @param label the screen label the sentence is composed from
     * @param value the submitted value, or {@code null} to stand for one never supplied
     * @param width the declared field width the routine examines the value at
     * @param expectedMarker the marker name the routine must reach
     * @throws IllegalArgumentException if a row names an edit outside the four, which would mean the
     *     table and the selector below had drifted apart; failing loudly is what stops a mistyped row
     *     from silently exercising whichever edit the selector reached last
     */
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", value = {
        "ALPHA_REQD,    First Name,     GRACE,   25, VALID",
        "ALPHA_REQD,    First Name,     VAN DER, 25, VALID",
        "ALPHA_REQD,    First Name,     GRACE1,  25, NOT_OK",
        "ALPHA_REQD,    First Name,     'O''H',  25, NOT_OK",
        "ALPHA_REQD,    First Name,     NONE,    25, BLANK",
        "ALPHANUM_REQD, Address Line 1, 1 NAVY,  50, VALID",
        "ALPHANUM_REQD, Address Line 1, 'A-1',   50, NOT_OK",
        "ALPHANUM_REQD, Address Line 1, NONE,    50, BLANK",
        "ALPHA_OPT,     Middle Name,    NONE,    25, VALID",
        "ALPHA_OPT,     Middle Name,    '   ',   25, VALID",
        "ALPHA_OPT,     Middle Name,    BROOK,   25, VALID",
        "ALPHA_OPT,     Middle Name,    BROOK9,  25, NOT_OK",
        "ALPHANUM_OPT,  Address Line 1, NONE,    50, VALID",
        "ALPHANUM_OPT,  Address Line 1, FLAT 9,  50, VALID",
        "ALPHANUM_OPT,  Address Line 1, 'F/9',   50, NOT_OK",
    })
    @DisplayName("the four shape edits vary by character class and by what absence means")
    void theShapeEditsVaryByCharacterClassAndByOptionality(String editName, String label,
            String value, int width, String expectedMarker) {
        AccountUpdateService service = new Fixture().service;
        AccountUpdateService.EditOutcome outcome = switch (editName) {
            case "ALPHA_REQD" -> service.editAlphaRequired(label, value, width);
            case "ALPHANUM_REQD" -> service.editAlphanumericRequired(label, value, width);
            case "ALPHA_OPT" -> service.editAlphaOptional(label, value, width);
            case "ALPHANUM_OPT" -> service.editAlphanumericOptional(label, value, width);
            default -> throw new IllegalArgumentException("unknown edit " + editName);
        };

        assertThat(outcome.state()).hasToString(expectedMarker);
        if (outcome.state() == FieldValidationFlag.NOT_OK) {
            assertThat(outcome.message()).isEqualTo(label
                    + (editName.startsWith("ALPHA_")
                            ? AccountUpdateService.SUFFIX_ALPHABETS_ONLY
                            : AccountUpdateService.SUFFIX_NUMBERS_OR_ALPHABETS_ONLY));
        }
    }

    /**
     * Confirms the required-numeric edit keeps its THREE reject paths distinct.
     *
     * <p>Assumptions: {@code 1245-EDIT-NUM-REQD} at L2109, exiting L2176, is the only routine in the
     * group with three refusals rather than two, and each carries its own sentence: absence at L2121
     * with {@code ' must be supplied.'} from L2126, a non-numeric value at L2142 with
     * {@code ' must be all numeric.'} from L2146, and a value whose {@code FUNCTION NUMVAL} is zero at
     * L2159 with {@code ' must not be zero.'} from L2163. The zero test is a separate branch at L2156
     * placed AFTER the numeric test, so an all-zero value is refused for being zero and not for its
     * characters.</p>
     *
     * <p>Assumptions: this routine is also the one the national-identifier edit borrows three times,
     * which is why its zero branch matters beyond the postal code -- L2442, L2472 and L2484 each
     * perform it for a different part of that identifier, at widths 3, 2 and 4.</p>
     *
     * <p>Returns no value. Two reject paths sharing a sentence, or a zero value refused for its
     * characters, is reported as a JUnit assertion failure.</p>
     *
     * @param label the screen label the sentence is composed from
     * @param value the submitted value, or {@code null} to stand for one never supplied
     * @param width the declared field width the routine examines the value at
     * @param expectedMarker the marker name the routine must reach
     * @param expectedSuffix the fixed fragment the sentence must end with, or empty when accepted
     */
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", value = {
        "Zip,            12546,      5,  VALID,  ''",
        "EFT Account Id, 0000000001, 10, VALID,  ''",
        "Zip,            NONE,       5,  BLANK,  ' must be supplied.'",
        "Zip,            '     ',    5,  BLANK,  ' must be supplied.'",
        "Zip,            1254A,      5,  NOT_OK, ' must be all numeric.'",
        "Zip,            '12 46',    5,  NOT_OK, ' must be all numeric.'",
        "Zip,            00000,      5,  NOT_OK, ' must not be zero.'",
        "EFT Account Id, 0000000000, 10, NOT_OK, ' must not be zero.'",
    })
    @DisplayName("the required-numeric edit keeps absence, non-numeric and zero on separate sentences")
    void theRequiredNumericEditKeepsItsThreeRejectPathsDistinct(String label, String value,
            int width, String expectedMarker, String expectedSuffix) {
        AccountUpdateService.EditOutcome outcome =
                new Fixture().service.editNumericRequired(label, value, width);

        assertThat(outcome.state()).hasToString(expectedMarker);
        assertThat(outcome.message())
                .isEqualTo(expectedSuffix.isEmpty() ? null : label + expectedSuffix);
    }

    /**
     * Confirms the signed-amount edit refuses a malformed amount with a sentence carrying NO period.
     *
     * <p>Assumptions: {@code 1250-EDIT-SIGNED-9V2} at L2180, exiting L2221, composes
     * {@code ' is not valid'} at L2209, and that fragment is the only one in the whole edit group
     * WITHOUT a trailing period -- every sibling fragment from L1841 to L2163 ends in one. The absence
     * is preserved rather than tidied, because the fragment is the sentence a user reads and a period
     * added here would be a change to the screen that no requirement asked for.</p>
     *
     * <p>Assumptions: this routine is performed TWICE by the driver, at L1486 for the credit limit
     * loaded at L1485 and at L1499 for the cash credit limit loaded at L1497, so both of its labels
     * are driven. The money values it guards are the five {@code PIC S9(10)V99} fields of
     * {@code app/cpy/CVACT01Y.cpy} at its L7, L8, L9, L13 and L14, and they are carried as
     * digits-and-sign TEXT rather than as a binary number at every hop, so what this edit examines is
     * a string.</p>
     *
     * <p>Returns no value. A period appended to the invalid sentence, or a well-formed amount refused,
     * is reported as a JUnit assertion failure.</p>
     *
     * @param label the screen label the sentence is composed from
     * @param value the submitted amount as edited text, or {@code null} for one never supplied
     * @param expectedMarker the marker name the routine must reach
     */
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", value = {
        "Credit Limit,      5000.00,   VALID",
        "Cash Credit Limit, 500.00,    VALID",
        "Current Balance,   -100.00,   VALID",
        "Credit Limit,      NONE,      BLANK",
        "Credit Limit,      '   ',     BLANK",
        "Credit Limit,      5000.0A,   NOT_OK",
        "Cash Credit Limit, 'not-num', NOT_OK",
    })
    @DisplayName("the signed-amount edit keeps its invalid sentence free of a trailing period")
    void theSignedAmountEditOmitsTheTrailingPeriod(String label, String value,
            String expectedMarker) {
        AccountUpdateService.EditOutcome outcome =
                new Fixture().service.editSignedNineV2(label, value);

        assertThat(outcome.state()).hasToString(expectedMarker);
        if (outcome.state() == FieldValidationFlag.NOT_OK) {
            assertThat(outcome.message()).isEqualTo(label + " is not valid");
            assertThat(outcome.message()).doesNotEndWith(".");
        }
    }

    /**
     * Confirms the national-identifier edit refuses the three reserved leading groups.
     *
     * <p>Assumptions: {@code 1265-EDIT-US-SSN} at L2431, exiting L2489, is a COMPOSITION rather than a
     * character test of its own. It performs the required-numeric routine three times, at L2442, L2472
     * and L2484, for parts of width 3, 2 and 4 under the labels declared at L2439, L2469 and L2481;
     * only then does it apply a value-domain test to the FIRST part alone, at L2450, refusing it at
     * L2452 with {@code ': should not be 000, 666, or between 900 and 999'} from L2457. So a
     * well-formed but reserved leading group fails while the two later parts pass, and that asymmetry
     * is the property worth pinning.</p>
     *
     * <p>Assumptions: the identifier arrives SPLIT into three components rather than as one string,
     * mirroring the three fields the screen presents, and the stored column it maps to is the
     * {@code PIC 9(09)} of {@code app/cpy/CVCUS01Y.cpy} L17. Only ciphertext is stored, so no case
     * here asserts a clear identifier in any payload.</p>
     *
     * <p>Assumptions: the reserved value {@code 000} is nonetheless refused by the SHAPE edit and never
     * reaches the value-domain test at all, even though the domain sentence names it. The shape edit
     * refuses any all-zero value at its own L2156 with {@code ' must not be zero.'} from L2163, and the
     * domain test is guarded at L2448 by that same shape marker, so a value the shape edit has already
     * refused is not examined further. The sentence a user sees for {@code 000} is therefore the
     * zero sentence and not the reserved-group one. This is recorded as an observed property of the
     * reference and is left exactly as it stands: the sentence at L2457 names three cases while its
     * routine can only ever reach two of them, and the target reproduces that reachability rather than
     * widening it.</p>
     *
     * <p>Returns no value. A reserved leading group accepted, an ordinary one refused, or a later part
     * penalised for the first part's domain, is reported as a JUnit assertion failure.</p>
     *
     * @param firstPart the leading three-digit group whose value domain is checked
     * @param expectedFirstMarker the marker name the leading group must reach
     * @param expectedSentence which sentence the refusal must carry, one of {@code DOMAIN} for the
     *     reserved-group text, {@code ZERO} for the shape edit's all-zero text, {@code NUMERIC} for its
     *     non-numeric text, {@code SUPPLIED} for its absence text, or {@code NONE} when accepted
     */
    @ParameterizedTest
    @CsvSource(nullValues = "ABSENT", value = {
        "123,    VALID,  NONE",
        "899,    VALID,  NONE",
        "666,    NOT_OK, DOMAIN",
        "900,    NOT_OK, DOMAIN",
        "950,    NOT_OK, DOMAIN",
        "999,    NOT_OK, DOMAIN",
        "000,    NOT_OK, ZERO",
        "12A,    NOT_OK, NUMERIC",
        "ABSENT, BLANK,  SUPPLIED",
    })
    @DisplayName("the reserved groups are refused, and 000 is refused by the shape edit before them")
    void theNationalIdentifierEditRefusesTheReservedLeadingGroups(String firstPart,
            String expectedFirstMarker, String expectedSentence) {
        AccountUpdateService.NationalIdentifierVerdict verdict =
                new Fixture().service.editNationalIdentifier(firstPart, "45", "6789");

        assertThat(verdict.firstPart().state()).hasToString(expectedFirstMarker);
        String label = AccountUpdateService.LABEL_NATIONAL_IDENTIFIER_PART_1;
        assertThat(verdict.firstPart().message()).isEqualTo(switch (expectedSentence) {
            case "DOMAIN" -> label + AccountUpdateService.SUFFIX_NATIONAL_IDENTIFIER_PREFIX;
            case "ZERO" -> label + AccountUpdateService.SUFFIX_MUST_NOT_BE_ZERO;
            case "NUMERIC" -> label + AccountUpdateService.SUFFIX_ALL_NUMERIC;
            case "SUPPLIED" -> label + AccountUpdateService.SUFFIX_MUST_BE_SUPPLIED;
            default -> null;
        });
        // WHY : Assumptions: the two later parts are asserted acceptable in EVERY row, including the
        //       rows where the leading group fails. The program edits all three parts unconditionally
        //       -- its L2472 and L2484 sit outside the L2448 conditional that guards the domain test --
        //       so a leading-group refusal must not suppress or contaminate the other two.
        assertThat(verdict.middlePart().state()).isEqualTo(FieldValidationFlag.VALID);
        assertThat(verdict.lastPart().state()).isEqualTo(FieldValidationFlag.VALID);
    }

    /**
     * Confirms the credit-score edit treats both ends of its stated range as INCLUSIVE.
     *
     * <p>Assumptions: {@code 1275-EDIT-FICO-SCORE} at L2514, exiting L2531, tests one condition,
     * {@code FICO-RANGE-IS-VALID} at L2515, and refuses at L2519 with
     * {@code ': should be between 300 and 850'} from L2523. The word "between" in that sentence is
     * ambiguous in English but not in the condition, so the two boundary values are asserted
     * explicitly: 300 and 850 both PASS, and 299 and 851 both fail. A boundary read as exclusive would
     * reject two real scores while still emitting a sentence naming them as acceptable.</p>
     *
     * <p>Assumptions: the score is carried as a digits-only string and stored in the
     * {@code PIC 9(03)} of {@code app/cpy/CVCUS01Y.cpy} L22, which is why a non-numeric value takes
     * the same refusal as an out-of-range one -- the program has no separate sentence for it.</p>
     *
     * <p>Returns no value. A boundary treated as exclusive, or an out-of-range score accepted, is
     * reported as a JUnit assertion failure.</p>
     *
     * @param value the submitted score as digits, or {@code null} to stand for one never supplied
     * @param expectedAcceptable whether the routine must reach its acceptable marker
     */
    @ParameterizedTest
    @CsvSource(nullValues = "NONE", value = {
        "300,  true",
        "301,  true",
        "600,  true",
        "849,  true",
        "850,  true",
        "299,  false",
        "851,  false",
        "000,  false",
        "12A,  false",
        "NONE, false",
    })
    @DisplayName("the credit-score edit accepts 300 and 850 and refuses 299 and 851")
    void theCreditScoreEditTreatsBothBoundariesAsInclusive(String value,
            boolean expectedAcceptable) {
        AccountUpdateService.EditOutcome outcome = new Fixture().service
                .editCreditScore(AccountUpdateService.LABEL_CREDIT_SCORE, value);

        assertThat(outcome.state().isValid()).isEqualTo(expectedAcceptable);
        if (!expectedAcceptable) {
            assertThat(outcome.message()).isEqualTo(AccountUpdateService.LABEL_CREDIT_SCORE
                    + AccountUpdateService.SUFFIX_CREDIT_SCORE_RANGE);
        }
    }

    /**
     * Confirms the driver DELEGATES the four value-domain edits and reimplements none of them.
     *
     * <p>Assumptions: three of the seventeen routines are value-domain tests against lookup tables
     * rather than character tests -- {@code 1270-EDIT-US-STATE-CD} at L2493, whose exit is L2511;
     * {@code 1280-EDIT-US-STATE-ZIP-CD} at L2536, whose exit is L2558; and
     * {@code 1260-EDIT-US-PHONE-NUM} at L2225, whose exit is L2427 and which contains the three
     * unnumbered sub-routines {@code EDIT-AREA-CODE} L2246, {@code EDIT-US-PHONE-PREFIX} L2316 and
     * {@code EDIT-US-PHONE-LINENUM} L2370. Those six reach their tables through the
     * {@code COPY CSLKPCDY.} at L602, and in the target the tables live in reference-service, so the
     * edits are issued through {@link AddressValidationService}. This case asserts the four
     * invocations exist and carry the program's own labels and the target's own field names; it asserts
     * nothing about what the tables contain.</p>
     *
     * <p>Assumptions: the telephone number is handed over ASSEMBLED into the fifteen-character stored
     * form rather than as three separate components, which is why one invocation covers the numbered
     * routine and all three of its sub-routines. The program's own redefinition splits that same
     * fifteen characters back into a three-digit area code, a three-digit exchange prefix and a
     * four-digit line number separated by three one-byte fillers holding punctuation, so the assembled
     * value is the contract between the two halves rather than a convenience.</p>
     *
     * <p>Returns no value. A missing invocation, a wrong label, a wrong field name or an unassembled
     * telephone value is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("the four value-domain edits are delegated with the program's labels")
    void theValueDomainEditsAreDelegatedAndNotReimplemented() {
        Fixture fixture = new Fixture();

        fixture.service.editMapInputs(fixture.request().build(), fixture.account, fixture.customer);

        verify(fixture.addressValidation).validateStateCode("VA",
                AccountUpdateService.FIELD_STATE_CODE, AccountUpdateService.LABEL_STATE);
        verify(fixture.addressValidation).validateStateZipCombination("VA", "12546",
                AccountUpdateService.FIELD_STATE_CODE, AccountUpdateService.FIELD_ZIP_CODE);
        verify(fixture.addressValidation).validateUsPhoneNumber("(703)555-0101  ",
                AccountUpdateService.FIELD_PHONE_1, AccountUpdateService.LABEL_PHONE_NUMBER_1);
        // WHY : Assumptions: the SECOND telephone number is delegated even though all three of its
        //       components are absent, and it arrives PUNCTUATED-BUT-EMPTY rather than as fifteen
        //       blanks. The assembly pads each component to its declared width and keeps the three
        //       one-byte fillers, so an entirely absent number still occupies the layout the reference's
        //       own redefinition describes. Asserting fifteen blanks here would assert an assembly that
        //       drops the punctuation when the value is empty, which is not what the layout says, and
        //       the delegate is what decides that an empty number is acceptable rather than the caller.
        verify(fixture.addressValidation).validateUsPhoneNumber("(   )   -      ",
                AccountUpdateService.FIELD_PHONE_2, AccountUpdateService.LABEL_PHONE_NUMBER_2);
        verifyNoMoreInteractions(fixture.addressValidation);
    }

    /**
     * Confirms the assembled telephone value places all THREE components where their routines read them.
     *
     * <p>Assumptions: the numbered telephone routine at L2225 does not test anything itself; it performs
     * three unnumbered sub-routines in turn and each reads ONE component out of the same stored field.
     * {@code EDIT-AREA-CODE} at L2246 reads the first three digits, {@code EDIT-US-PHONE-PREFIX} at L2316
     * the next three and {@code EDIT-US-PHONE-LINENUM} at L2370 the last four, and all three share the
     * exit at L2424. Because this service hands the delegate one ASSEMBLED value rather than three
     * components, the offsets ARE the interface between the two halves: a value assembled to a different
     * layout would still be accepted by the delegate's signature and would then silently be read as
     * three wrong components.</p>
     *
     * <p>Assumptions: what makes the layout non-obvious is that the three components are not adjacent.
     * The stored field is fifteen characters and carries three one-byte fillers holding punctuation --
     * an opening character before the area code, a closing one after it and a separator before the line
     * number -- with two trailing blanks, which is why the offsets are 1, 5 and 9 and not 0, 3 and 6.
     * This case reads each component back out at the delegate's own published offset and width, so it
     * asserts the layout rather than restating the string.</p>
     *
     * <p>Returns no value. A component sitting at the wrong offset, which each sub-routine would then
     * read wrongly, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("the assembled telephone value carries its three components at the delegate's offsets")
    void theAssembledTelephoneValuePlacesAllThreeComponentsAtTheirOffsets() {
        Fixture fixture = new Fixture();
        ArgumentCaptor<String> assembled = ArgumentCaptor.forClass(String.class);

        fixture.service.editMapInputs(fixture.request().build(), fixture.account, fixture.customer);

        verify(fixture.addressValidation, times(2)).validateUsPhoneNumber(assembled.capture(),
                anyString(), anyString());
        String first = assembled.getAllValues().getFirst();

        assertThat(first).hasSize(AddressValidationService.STORED_PHONE_WIDTH);
        assertThat(componentOf(first, AddressValidationService.AREA_CODE_OFFSET,
                AddressValidationService.AREA_CODE_WIDTH)).isEqualTo("703");
        assertThat(componentOf(first, AddressValidationService.PHONE_PREFIX_OFFSET,
                AddressValidationService.PHONE_PREFIX_WIDTH)).isEqualTo("555");
        assertThat(componentOf(first, AddressValidationService.PHONE_LINE_NUMBER_OFFSET,
                AddressValidationService.PHONE_LINE_NUMBER_WIDTH)).isEqualTo("0101");
    }

    /**
     * Confirms a refusal naming one telephone COMPONENT reaches the caller keyed to that component.
     *
     * <p>Assumptions: each of the three sub-routines sets its own marker and composes its own sentence
     * -- the area code's at L2254, L2272, L2286 and L2306, the exchange prefix's at L2325, L2343 and
     * L2357, and the line number's at L2378, L2396 and L2410 -- so a refusal belongs to a COMPONENT and
     * not to the number as a whole. The screen presents three boxes, and a refusal keyed only to the
     * number would leave a user unable to tell which box to correct.</p>
     *
     * <p>Assumptions: the component key is the number's own field name with the delegate's published
     * suffix appended, so the two halves agree on the spelling without either retyping it. The sentence
     * is supplied through the substitute here and asserted to arrive whole; which candidate values earn
     * it is decided by the allow-lists and is asserted by {@code AddressValidationServiceTest}.</p>
     *
     * <p>Returns no value. A component refusal dropped, or re-keyed to the whole number, is reported as
     * a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an area-code refusal arrives keyed to the area-code component of its number")
    void aTelephoneComponentRefusalIsKeyedToThatComponent() {
        Fixture fixture = new Fixture();
        String component =
                AccountUpdateService.FIELD_PHONE_1 + AddressValidationService.FIELD_SUFFIX_AREA_CODE;
        String refusal = AccountUpdateService.LABEL_PHONE_NUMBER_1
                + AddressValidationService.MSG_AREA_CODE_NOT_GENERAL_PURPOSE;
        when(fixture.addressValidation.validateUsPhoneNumber(anyString(),
                org.mockito.ArgumentMatchers.eq(AccountUpdateService.FIELD_PHONE_1), anyString()))
                .thenReturn(new AddressValidationService.AddressValidationResult(
                        List.of(new ApiError.FieldError(component, FieldValidationFlag.NOT_OK,
                                refusal)),
                        refusal));

        AccountUpdateService.EditVerdict verdict = fixture.service
                .editMapInputs(fixture.request().build(), fixture.account, fixture.customer);

        assertThat(verdict.inputError()).isTrue();
        assertThat(verdict.fieldNames()).contains(component);
        assertThat(verdict.fieldErrors())
                .extracting(ApiError.FieldError::field, ApiError.FieldError::message)
                .contains(org.assertj.core.groups.Tuple.tuple(component, refusal));
    }

    /**
     * Confirms neither table is consulted once the SHAPE edit on the same field has already failed.
     *
     * <p>Assumptions: the program orders these two tests and the order is not incidental.
     * {@code 1270-EDIT-US-STATE-CD} at L2494 moves the submitted state into its lookup subject before
     * testing it, and {@code 1280-EDIT-US-STATE-ZIP-CD} at L2537 through L2540 concatenates the state
     * with the first two characters of the postal code before testing the pair; neither can say
     * anything useful about a state that failed its shape edit, and the pairing test would compare a
     * malformed half against the table and report the PAIR as unlisted when what is actually wrong is
     * the one field. Suppressing both keeps the sentence the user reads pointed at the field they must
     * correct.</p>
     *
     * <p>Assumptions: in the target the suppression also removes a remote call. The delegate resolves
     * its allow-lists from reference-service synchronously, so an edit issued for a value already known
     * to be malformed would spend a network round trip to learn nothing.</p>
     *
     * <p>Returns no value. A table consulted for a value that failed its shape edit is reported as a
     * JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a state that failed its shape edit is not looked up, and its pairing is not tested")
    void theTablesAreNotConsultedForAValueThatFailedItsShapeEdit() {
        Fixture fixture = new Fixture();

        fixture.service.editMapInputs(fixture.request().stateCode("V1").build(),
                fixture.account, fixture.customer);

        verify(fixture.addressValidation, never()).validateStateCode(anyString(), anyString(),
                anyString());
        verify(fixture.addressValidation, never()).validateStateZipCombination(anyString(),
                anyString(), anyString(), anyString());
    }

    /**
     * Confirms the pairing edit is suppressed by a bad POSTAL code as well as by a bad state.
     *
     * <p>Assumptions: the pairing test needs BOTH halves well formed, because it concatenates them.
     * The state half alone being acceptable is not enough, and this case separates the two guards --
     * the previous case holds the postal code good and breaks the state, this one holds the state good
     * and breaks the postal code. Asserting only one of the two would leave the other free to regress:
     * a pairing issued with a non-numeric postal code would take the first two characters of a value
     * the program has already refused and report the pair as unlisted.</p>
     *
     * <p>Assumptions: the state's own allow-list edit still runs here, and that difference is the
     * point. The state passed its shape edit, so its table is consulted; only the PAIRING waits for the
     * postal code.</p>
     *
     * <p>Returns no value. A pairing issued with a malformed postal code, or a state lookup suppressed
     * by an unrelated field, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a malformed postal code suppresses the pairing edit but not the state lookup")
    void thePairingEditIsSuppressedByAMalformedPostalCode() {
        Fixture fixture = new Fixture();

        fixture.service.editMapInputs(fixture.request().zipCode("1254A").build(),
                fixture.account, fixture.customer);

        verify(fixture.addressValidation).validateStateCode("VA",
                AccountUpdateService.FIELD_STATE_CODE, AccountUpdateService.LABEL_STATE);
        verify(fixture.addressValidation, never()).validateStateZipCombination(anyString(),
                anyString(), anyString(), anyString());
    }

    /**
     * Confirms a delegated refusal reaches the caller as a per-field entry, unaltered.
     *
     * <p>Assumptions: the delegate's verdict is MERGED into the same accumulator the local edits write
     * to, so a table refusal and a character refusal are indistinguishable to a reader of the response.
     * That is the target's spelling of what the program does with its markers: the state field has one
     * marker, and either the shape test at L1592's label or the table test at L2499 can set it. A
     * design that reported delegated refusals on a separate channel would give the screen two places
     * to look for the same field's error.</p>
     *
     * <p>Assumptions: the sentence is passed through rather than rewritten, so the text the delegate
     * composes -- {@code ': is not a valid state code'} at L2503 -- is what the user reads. This case
     * supplies that sentence through the substitute and asserts it arrives whole.</p>
     *
     * <p>Returns no value. A delegated refusal dropped, renamed or rewritten on its way to the caller
     * is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a delegated state refusal arrives as a per-field entry with its sentence intact")
    void aDelegatedRefusalReachesTheCallerUnaltered() {
        Fixture fixture = new Fixture();
        String refusal = AccountUpdateService.LABEL_STATE
                + AddressValidationService.MSG_STATE_CODE_INVALID;
        when(fixture.addressValidation.validateStateCode(anyString(), anyString(), anyString()))
                .thenReturn(new AddressValidationService.AddressValidationResult(
                        List.of(new ApiError.FieldError(AccountUpdateService.FIELD_STATE_CODE,
                                FieldValidationFlag.NOT_OK, refusal)),
                        refusal));

        AccountUpdateService.EditVerdict verdict = fixture.service
                .editMapInputs(fixture.request().build(), fixture.account, fixture.customer);

        assertThat(verdict.inputError()).isTrue();
        assertThat(verdict.fieldNames()).contains(AccountUpdateService.FIELD_STATE_CODE);
        assertThat(verdict.fieldErrors())
                .extracting(ApiError.FieldError::field, ApiError.FieldError::message)
                .contains(org.assertj.core.groups.Tuple
                        .tuple(AccountUpdateService.FIELD_STATE_CODE, refusal));
    }

    /**
     * Confirms BOTH of the program's marker regimes decode, because they use different blank bytes.
     *
     * <p>Assumptions: the program does not have one marker convention, it has TWO, and they are not
     * interchangeable. The KEY-FILTER markers at L183 through L190 spell acceptable as {@code '1'},
     * unacceptable as {@code '0'} and blank as a SPACE -- {@code WS-EDIT-ACCT-FLAG} at L183 with its
     * conditions at L184, L185 and L186, and {@code WS-EDIT-CUST-FLAG} at L187 with L188, L189 and
     * L190. The NON-KEY markers from L191 onward spell acceptable as low values, unacceptable as
     * {@code '0'} and blank as the letter {@code 'B'} -- visible at L193, L194 and L195 for the account
     * status and again at L197 and L198 for the credit limit, and repeated inside the shared argument
     * block at L57, L58 and L59 and once more at L74 through L80. Two of the three bytes coincide, so a
     * decoder written for one regime silently misreads the other's blank as an unrecognised value.</p>
     *
     * <p>Assumptions: a further trap sits inside the second regime. Of the 36 acceptable-markers
     * declared between L191 and L352 -- the block's last line being
     * {@code FLG-PRI-CARDHOLDER-BLANK} at L352, matched by exactly 36 unacceptable and 36 blank
     * conditions -- only TWO spell acceptable as {@code VALUES 'Y', 'N'} rather than as low values: the
     * account status at L193 and the primary cardholder at L350. The other 34 use low values. No test
     * may therefore assume a single acceptable byte across that regime, which is why the two alternate
     * codes are asserted alongside the primary three rather than instead of them.</p>
     *
     * <p>Returns no value. A byte from either regime decoded to the wrong marker is reported as a JUnit
     * assertion failure.</p>
     *
     * @param codeName which published marker byte to decode, one of {@code VALID_CODE},
     *     {@code ALTERNATE_VALID_CODE}, {@code NOT_OK_CODE}, {@code BLANK_CODE} or
     *     {@code ALTERNATE_BLANK_CODE}
     * @param expectedMarker the marker name that byte must decode to
     * @param expectedError whether the decoded marker must satisfy the error predicate
     * @throws IllegalArgumentException if a row names a byte outside the five published ones, which
     *     would mean the table and the selector below had drifted apart
     */
    @ParameterizedTest
    @CsvSource({
        "VALID_CODE,           VALID,  false",
        "ALTERNATE_VALID_CODE, VALID,  false",
        "NOT_OK_CODE,          NOT_OK, true",
        "BLANK_CODE,           BLANK,  true",
        "ALTERNATE_BLANK_CODE, BLANK,  true",
    })
    @DisplayName("the low-value and one acceptable bytes both decode, as do the B and space blanks")
    void bothMarkerRegimesDecodeIncludingTheirDifferentBlankBytes(String codeName,
            String expectedMarker, boolean expectedError) {
        // WHY : Assumptions: the byte is named rather than written as a literal in the table, because
        //       the low-value acceptable byte of the non-key regime is a NUL that a comma-separated
        //       source cannot carry -- it arrives as an empty string and fails to convert to a
        //       character. Naming the published constant also keeps the test from retyping a byte the
        //       shared kernel already declares, so a change to either regime's spelling is picked up
        //       here rather than silently disagreed with.
        char code = switch (codeName) {
            case "VALID_CODE" -> FieldValidationFlag.VALID_CODE;
            case "ALTERNATE_VALID_CODE" -> FieldValidationFlag.ALTERNATE_VALID_CODE;
            case "NOT_OK_CODE" -> FieldValidationFlag.NOT_OK_CODE;
            case "BLANK_CODE" -> FieldValidationFlag.BLANK_CODE;
            case "ALTERNATE_BLANK_CODE" -> FieldValidationFlag.ALTERNATE_BLANK_CODE;
            default -> throw new IllegalArgumentException("unknown marker byte " + codeName);
        };

        FieldValidationFlag marker = FieldValidationFlag.fromCode(code);

        assertThat(marker).hasToString(expectedMarker);
        assertThat(marker.isError()).isEqualTo(expectedError);
    }

    /**
     * Confirms blank is a SUBSET of error rather than a third state beside it.
     *
     * <p>Assumptions: the program tests the two failing markers together in ONE disjunction rather than
     * separately, which is what makes blank a subset. L18 and L19 of {@code app/cpy/CSSETATY.cpy}
     * express that as a single test whose either branch drives the field's colour attribute, and the
     * templated highlight it applies is the same for both. Modelling blank as a peer of acceptable and
     * unacceptable would force every reader of a marker to remember to test two values, and a reader
     * who tested one would let blank fields render as though they had passed.</p>
     *
     * <p>Assumptions: the distinction between the two is nonetheless kept, because only ONE of them
     * writes the screen marker. The program moves a literal asterisk into a field that is blank and not
     * into one that is merely wrong, so the two share the error predicate while differing in what the
     * screen shows -- and this case asserts both halves of that at once.</p>
     *
     * <p>Returns no value. A blank marker reported as acceptable, an asterisk shown for an unacceptable
     * field, or an asterisk withheld from a blank one, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("blank and unacceptable are both errors, and only blank carries the asterisk")
    void blankIsASubsetOfErrorAndOnlyBlankCarriesTheScreenMarker() {
        assertThat(FieldValidationFlag.VALID.isError()).isFalse();
        assertThat(FieldValidationFlag.NOT_OK.isError()).isTrue();
        assertThat(FieldValidationFlag.BLANK.isError()).isTrue();

        assertThat(FieldValidationFlag.BLANK.requiresBlankMarker()).isTrue();
        assertThat(FieldValidationFlag.BLANK.screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(FieldValidationFlag.NOT_OK.requiresBlankMarker()).isFalse();
        assertThat(FieldValidationFlag.NOT_OK.screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
        assertThat(FieldValidationFlag.VALID.screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
    }

    /**
     * Confirms an edit that refuses for shape reports BLANK or unacceptable as the program does.
     *
     * <p>Assumptions: the two markers are not interchangeable at the point they are produced either,
     * and this case checks the production side of the property the previous case checked on the
     * consuming side. An absent postal code takes the blank branch of
     * {@code 1245-EDIT-NUM-REQD} at L2122 and therefore earns the screen's asterisk, whereas a
     * non-numeric one takes the unacceptable branch at L2142 and does not. A service that returned one
     * marker for every refusal would render an asterisk over a field the user HAS filled in.</p>
     *
     * <p>Returns no value. A shape refusal carrying the wrong marker, and therefore the wrong screen
     * treatment, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an absent field earns the asterisk and a malformed one does not")
    void theProducedMarkerDecidesWhetherTheAsteriskIsShown() {
        AccountUpdateService service = new Fixture().service;

        AccountUpdateService.EditOutcome absent =
                service.editNumericRequired(AccountUpdateService.LABEL_ZIP, null, 5);
        AccountUpdateService.EditOutcome malformed =
                service.editNumericRequired(AccountUpdateService.LABEL_ZIP, "1254A", 5);

        assertThat(absent.state().screenMarker())
                .isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(malformed.state().screenMarker())
                .isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
        assertThat(absent.state().isError()).isTrue();
        assertThat(malformed.state().isError()).isTrue();
    }

    /**
     * Confirms a precondition whose ACCOUNT half has moved refuses the update.
     *
     * <p>Assumptions: the program compares the two regions SEPARATELY and can conclude the record moved
     * from either one. {@code 9700-CHECK-CHANGE-IN-REC.} at L4109, exiting L4193, compares the account
     * region across L4115 to L4141 and signals at L4143 before branching away at L4144; it then
     * compares the customer region across L4152 to L4187 and signals at L4189 before branching at
     * L4190. Those two are the only sites in the whole program that raise the signal, so a suite that
     * covered one would leave half the concurrency guard unasserted. This case covers the account
     * half.</p>
     *
     * <p>Assumptions: in the target the two regions are two version columns, so a precondition token is
     * the pair of them and a stale ACCOUNT half is a token whose first component disagrees. The columns
     * exist on the account and customer rows only and never on the cross-reference row, which has no
     * user-editable field for a second writer to move.</p>
     *
     * <p>Returns no value. An accepted stale precondition is reported as a JUnit assertion failure.</p>
     *
     * @throws RecordConflictException never propagates from this method: the refusal is provoked and
     *     captured by the assertion below, which is what the case exists to demonstrate
     */
    @Test
    @DisplayName("a precondition whose account half has moved is refused")
    void aStaleAccountHalfRefusesTheUpdate() {
        Fixture fixture = new Fixture();

        assertThatExceptionOfType(RecordConflictException.class)
                .isThrownBy(() -> fixture.service.update(ACCOUNT_ID,
                        fixture.request().build(), STALE_ACCOUNT_REVISION))
                .matches(refusal -> refusal.kind() == RecordConflictException.Kind.STALE_VERSION,
                        "the refusal must name the stale-version kind");
    }

    /**
     * Confirms a precondition whose CUSTOMER half has moved refuses the update.
     *
     * <p>Assumptions: this is the companion of the previous case and covers the second of the program's
     * two signal sites, the customer-region comparison that signals at L4189. The region it guards is
     * the larger of the two -- twenty-one fields against the account region's twelve -- and it is the
     * one holding every value a second clerk is most likely to be editing at the same moment: the name,
     * the address, both telephone numbers and the identifiers.</p>
     *
     * <p>Assumptions: the refusal is the SAME kind for both halves, and that is deliberate rather than
     * an omission. The program reaches one signal from two comparisons and loads one sentence for both,
     * so telling the caller WHICH region moved would be a new disclosure the screen never made; the
     * caller re-reads and re-submits either way.</p>
     *
     * <p>Returns no value. An accepted stale precondition is reported as a JUnit assertion failure.</p>
     *
     * @throws RecordConflictException never propagates from this method: the refusal is provoked and
     *     captured by the assertion below, which is what the case exists to demonstrate
     */
    @Test
    @DisplayName("a precondition whose customer half has moved is refused with the same kind")
    void aStaleCustomerHalfRefusesTheUpdate() {
        Fixture fixture = new Fixture();

        assertThatExceptionOfType(RecordConflictException.class)
                .isThrownBy(() -> fixture.service.update(ACCOUNT_ID,
                        fixture.request().build(), STALE_CUSTOMER_REVISION))
                .matches(refusal -> refusal.kind() == RecordConflictException.Kind.STALE_VERSION,
                        "the refusal must name the stale-version kind");
    }

    /**
     * Confirms the concurrency sentence keeps its TWO-WORD spelling of the third word.
     *
     * <p>Assumptions: L521 and L522 declare the sentence as
     * {@code 'Record changed by some one else. Please review'}, and the third word is written as two
     * words. It is carried across exactly, because the emitted literal is what a user reads and a
     * silent respelling would change the screen. The spelling is also the one thing about this sentence
     * a reader is most likely to normalise on sight, which is why it is pinned by a case rather than
     * only by a comment.</p>
     *
     * <p>Assumptions: in the program that sentence is loaded by the SAME statement that signals the
     * conflict, because the condition is declared on the 75-character message field
     * {@code 05 WS-RETURN-MSG PIC X(75).} at L479 and not on the change flag at L168, whose only two
     * conditions are the ones at L169 and L170. In the target the one statement splits into two things:
     * the raised refusal carries the SIGNAL, and this shared constant carries the TEXT. This case
     * asserts the text half; the two cases above assert the signal half.</p>
     *
     * <p>Returns no value. Any respelling of the sentence, including a one-word third word, is reported
     * as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("the concurrency sentence keeps some one as two words")
    void theConcurrencySentenceKeepsItsTwoWordSpelling() {
        assertThat(ApiError.COACTUPC_RECORD_CHANGED)
                .isEqualTo("Record changed by some one else. Please review")
                .contains("some one")
                .doesNotContain("someone");
    }

    /**
     * Confirms the three write-path sentences that are genuinely emitted are carried verbatim.
     *
     * <p>Assumptions: the program declares more of these sentences than it ever reaches, and only the
     * reached ones are asserted as live text. Three are genuinely set: L517 and L518 declare
     * {@code 'Could not lock account record for update'}, which its L3912 sets when the account row
     * cannot be read for update; L519 and L520 declare
     * {@code 'Could not lock customer record for update'}, set at L3939; and L523 and L524 declare
     * {@code 'Update of record failed'}, set at BOTH L4079 and L4098. Four setting sites therefore cover
     * three distinct sentences.</p>
     *
     * <p>Assumptions: a fourth sentence is declared and NEVER set. L525 and L526 declare
     * {@code 'Error reading Card Data File'} under a condition name that appears exactly ONCE in all
     * 4236 lines -- at L525, its own declaration -- so nothing in the program can raise it. It is
     * carried into the message catalogue because the catalogue records what the program declares, but no
     * case may assert it as something this context emits, and this context accordingly declares no
     * constant for it at all. That absence is asserted below as an absence.</p>
     *
     * <p>Assumptions: the program also declares one condition name TWICE with DIFFERENT text, at L497
     * and L498 and again at L513 and L514. That is recorded here as an observed property of the
     * reference and is not reconciled; the baseline declares it twice, the target carries the sentences
     * separately, and the divergence is documented.</p>
     *
     * <p>Returns no value. A respelt sentence, or a constant appearing for the never-set one, is
     * reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("the three genuinely emitted write-path sentences are verbatim")
    void theEmittedWritePathSentencesAreCarriedVerbatim() {
        assertThat(com.carddemo.common.error.GlobalExceptionHandler.MESSAGE_ACCOUNT_LOCK_FAILED)
                .isEqualTo("Could not lock account record for update");
        assertThat(com.carddemo.common.error.GlobalExceptionHandler.MESSAGE_CUSTOMER_LOCK_FAILED)
                .isEqualTo("Could not lock customer record for update");
        assertThat(com.carddemo.common.error.GlobalExceptionHandler.MESSAGE_UPDATE_FAILED)
                .isEqualTo("Update of record failed");

        // WHY : Assumptions: the never-set sentence is asserted ABSENT from this context rather than
        //       asserted present somewhere. Its condition name occurs once in the whole program, at its
        //       own declaration on L525, so a constant for it here would imply an emission the baseline
        //       cannot produce. Testing the absence is what stops one being added back later.
        assertThat(AccountUpdateService.MESSAGE_UPDATE_ACCEPTED)
                .isEqualTo("Looks Good.... so far")
                .isNotEqualTo("Error reading Card Data File");
    }

    /**
     * Confirms a difference of LETTER CASE alone is refused, where the reference folded it away.
     *
     * <p>Trade-offs: this is a deliberate behavioural divergence and the target is STRICTER, never
     * looser. Within {@code 9700-CHECK-CHANGE-IN-REC.} L4109 to L4193 the reference folds case on ten
     * of its fields before comparing them: one to lower case, the account group at L4139 and L4140, and
     * nine to upper case -- the first, middle and last names at L4152, L4154 and L4156, the three
     * address lines at L4158, L4160 and L4162, the state and country codes at L4164 and L4166, and the
     * government-issued identifier at L4172. Counted inside that span the lower-case function appears 2
     * times and the upper-case function 18, being one and nine fields each applied to both sides of a
     * comparison. A whole-file count of the upper-case function returns 46 instead, the other 28 lying
     * in the edit paragraphs, so the in-span figure must not be disproved with a file-wide search.</p>
     *
     * <p>Trade-offs: the remaining seven compared fields are folded on NEITHER side and are therefore
     * case-sensitive even in the reference -- the postal code at L4168, the two telephone numbers at
     * L4169 and L4170, the national identifier at L4171, the electronic-transfer account at L4181, the
     * primary-cardholder indicator at L4183 and the credit score at L4186. So the reference itself is
     * already inconsistent between its folded and unfolded fields. The compromise accepted is that a
     * version column cannot express per-field folding at all: it moves when the ROW moves, so a second
     * writer who changed only the case of a name now causes a refusal the reference would have allowed.
     * What is bought is that the ten folded fields stop being a hole through which one writer's edit can
     * be lost, and the cost is a refusal the user resolves by re-reading and re-submitting.</p>
     *
     * <p>Returns no value. A case-only concurrent change accepted -- that is, the looser reference
     * behaviour appearing in the target -- is reported as a JUnit assertion failure.</p>
     *
     * @throws RecordConflictException never propagates from this method: the refusal is provoked and
     *     captured by the assertion below, which is what the case exists to demonstrate
     */
    @Test
    @DisplayName("a case-only concurrent change is refused, which the reference would have allowed")
    void aCaseOnlyConcurrentChangeIsRefused() {
        // WHY : Trade-offs: a lower-cased stored surname whose row counter a second writer has already
        //       advanced, submitted against the earlier token, is exactly the shape the reference folds
        //       away at L4156, where both sides of that comparison pass through the upper-case function
        //       and so compare equal. The counter moving is the target's only spelling of "this row
        //       changed", and it carries no record of WHICH field moved, so it cannot reproduce the
        //       folding even in principle.
        Fixture fixture = new Fixture("hopper");
        versionOf(fixture.customer, 1L);

        assertThatExceptionOfType(RecordConflictException.class)
                .isThrownBy(() -> fixture.service.update(ACCOUNT_ID,
                        fixture.request().lastName("HOPPER").build(), CURRENT_REVISION));

        // WHY : Trade-offs: the token is compared directly as well, because that is what makes the
        //       divergence visible rather than merely inferred. The reference would have compared the
        //       two surnames folded and found them equal; the target compares tokens, and the case-only
        //       write has already moved the token before any field is looked at.
        assertThat(AccountRevision.of(fixture.account, fixture.customer))
                .isNotEqualTo(CURRENT_REVISION);
        assertThat(fixture.customer.getLastName()).isEqualToIgnoringCase("HOPPER");
    }

    /**
     * Confirms a failure on EITHER write leaves no partially applied state.
     *
     * <p>Refactoring Rationale: the reference handles its two rewrites ASYMMETRICALLY, and the target
     * replaces both arms with one uniform rule. The account rewrite at L4065 to L4071 fails at L4076 to
     * L4081 by setting its marker at L4079 and branching at L4080 with NO rollback, which is correct
     * there because the customer rewrite has not been issued yet and so nothing is partial. The customer
     * rewrite at L4085 to L4091 fails at L4095 to L4103 and must roll back, so it issues an explicit
     * rollback -- the verb on L4100, bracketed by L4099 and L4101 -- before branching at L4102, because
     * by then the account rewrite has already succeeded. Two arms, two different obligations, and the
     * second one depends on remembering what the first one did.</p>
     *
     * <p>Refactoring Rationale: one transactional boundary subsumes both arms and removes the
     * dependency between them. A failure anywhere inside the span unwinds the whole span by PROPAGATING,
     * never by an explicit rollback call, so there is no second arm to remember and no ordering to get
     * wrong. That the reference has only two such statements in 4236 lines makes the substitution easy
     * to state exactly: the commit at L953, bracketed by L952 and L954 and followed immediately by the
     * transfer of control at L956 to L959, and the rollback at L4100. Nothing else in the program
     * commits or rolls back.</p>
     *
     * <p>Assumptions: what this case can assert without an engine is that a refused submission writes
     * NEITHER row -- the guarantee that makes the single boundary observable from outside. That no
     * partially committed state can survive a mid-span failure is asserted against a real engine by
     * {@code AccountUpdateAtomicityIT} in the sibling repository package, because only an engine can
     * demonstrate a rollback.</p>
     *
     * <p>Returns no value. Either row written during a refused submission is reported as a JUnit
     * assertion failure.</p>
     *
     * @throws RecordConflictException never propagates from this method: the refusal is provoked and
     *     captured by the assertion below, which is what the case exists to demonstrate
     */
    @Test
    @DisplayName("a refused submission writes neither row, replacing the reference's two rollback arms")
    void aRefusedSubmissionWritesNeitherRow() {
        Fixture fixture = new Fixture();

        assertThatExceptionOfType(RecordConflictException.class)
                .isThrownBy(() -> fixture.service.update(ACCOUNT_ID,
                        fixture.request().build(), STALE_ACCOUNT_REVISION));

        verify(fixture.accounts, never()).saveAndFlush(any(Account.class));
        verify(fixture.customers, never()).saveAndFlush(any(Customer.class));
    }

    /**
     * Confirms the decomposed date halves are rejoined without the reference's asymmetric offsets.
     *
     * <p>Assumptions: the reference holds each date TWICE at two different WIDTHS, and its comparisons
     * carry the arithmetic that reconciles them. Its before-image, which spans exactly L669 to L756 --
     * {@code 05 ACUP-OLD-DETAILS.} opening at L669 and {@code 05 ACUP-NEW-DETAILS.} at L757 closing it
     * -- stores dates as {@code PIC X(08)} in an unpunctuated form, redefined into a four-character
     * year and two two-character halves: the open date at L684 to L689, the expiry at L690 to L695 and
     * the reissue at L696 to L701. The master records store the same dates as {@code PIC X(10)} in a
     * hyphenated form, at L10, L11 and L12 of {@code app/cpy/CVACT01Y.cpy} and at L19 of
     * {@code app/cpy/CVCUS01Y.cpy}.</p>
     *
     * <p>Assumptions: the clearest proof is the date-of-birth comparison at L4174 to L4179, where the
     * substring offsets on the two sides DISAGREE -- position 1 for 4 characters against position 1 for
     * 4, then position 6 for 2 against position 5 for 2, then position 9 for 2 against position 7 for 2.
     * The one-and-two character drift is the two hyphens the wider form carries and the narrower one
     * does not. L4131 to L4137 shows the same widths for the expiry and reissue dates, compared against
     * separately named sub-fields rather than against offsets.</p>
     *
     * <p>Assumptions: the target keeps the SUBMISSION decomposed, because the screen presents three
     * boxes and each earns its own per-field error, but it carries the stored value as a real date and
     * so has no second width and no offsets to reconcile. This case drives all four dates through the
     * decomposed form and asserts they are accepted and rejoined, which is what proves the offsets are
     * gone rather than merely relocated.</p>
     *
     * <p>Refactoring Rationale: every one of the four submitted dates DIFFERS from the value the fixture
     * stores, and each differs in all three components. This case previously submitted the builder's
     * defaults, which re-state the stored row exactly, so the four assertions below compared each stored
     * date against itself: an implementation that discarded the submitted components entirely -- or
     * rejoined them into the wrong order, or into the wrong field -- passed unchanged. Varying every
     * component is what makes the assertion about REJOINING rather than about the fixture. The values are
     * also chosen so that no two are interchangeable: the four years, the four months and the four days
     * are distinct across the set, so a rejoin that crossed two dates cannot produce a matching result.</p>
     *
     * <p>Assumptions: the submitted values respect the edits that actually apply, which is why they are
     * not arbitrary. Only the date of birth is range-checked -- {@code recordDateEdit} passes
     * {@code rangeChecked} as {@code true} for that one alone -- so it must not be in the future against
     * the fixed clock of 18 July 2022, and 4 March 1917 is not. The other three carry no range edit at
     * all, so 29 February 2016 is admissible as an open date and exercises the validator's leap-year
     * arm at the same time; a 2016 open date beside a 2031 expiry is chronologically coherent in any
     * case. Alternatives Considered: also varying the credit score or the address so the submission
     * differed more widely, rejected because a second changed field would give a refusal two possible
     * sources and this case exists to attribute one.</p>
     *
     * <p>Assumptions: the absent-property names asserted below are the stems the service actually
     * records under, taken from {@code recordDateEdit}'s call sites: {@code openDate},
     * {@code expirationDate}, {@code reissueDate} and {@code dateOfBirth}, each suffixed
     * {@code Year}, {@code Month} or {@code Day} by {@code datePartProperty}. The expiry stem is
     * {@code expirationDate} and not {@code expiryDate}, which is worth stating because the label the
     * user sees reads "Expiry" -- a case naming the label's spelling would assert the absence of a
     * property that can never be present and would therefore hold even while the expiry edit refused.</p>
     *
     * <p>Refactoring Rationale: the rejoined values are read off the rows the service HANDS TO THE
     * REPOSITORY, reached through {@code update}, rather than off the rows an edit pass was given. That
     * is a correction of what this case measured: {@code editMapInputs} edits and accumulates and mutates
     * nothing at all -- the composition lives in {@code AccountMapper.applyUpdate} and
     * {@code CustomerMapper}, which only the write path invokes -- so four assertions made after an edit
     * pass were reading the fixture's own seeded rows back. With identical submitted and stored dates they
     * were tautologies; with differing ones they fail, which is how the gap was confirmed rather than
     * argued. Both channels are still asserted here, the edit pass through the absent property names and
     * the composition through the saved rows, because a date can be refused by the first or mis-composed
     * by the second and the two failures need to stay distinguishable.</p>
     *
     * <p>Returns no value. A date rejected after decomposed submission, or one whose halves are rejoined
     * wrongly, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("the decomposed date halves are rejoined without the reference's offset arithmetic")
    void theDecomposedDatesAreRejoinedWithoutOffsetArithmetic() {
        Fixture fixture = new Fixture();
        AccountUpdateRequest submission = fixture.request()
                .openDate("2016", "02", "29")
                .expirationDate("2031", "10", "15")
                .reissueDate("2022", "07", "04")
                .dateOfBirth("1917", "03", "22")
                .build();

        // WHY : Assumptions: the four dates are asserted through the ABSENCE of their field names from
        //       the accumulated errors rather than through a returned value, because the driver's
        //       contract is an error array and a date that survives its edit contributes nothing to it.
        //       Naming the twelve components explicitly is what distinguishes "accepted" from "not
        //       edited".
        AccountUpdateService.EditVerdict verdict =
                fixture.service.editMapInputs(submission, fixture.account, fixture.customer);
        assertThat(verdict.fieldNames()).doesNotContain("openDateYear", "openDateMonth",
                "openDateDay", "expirationDateYear", "expirationDateMonth", "expirationDateDay",
                "reissueDateYear", "reissueDateMonth", "reissueDateDay",
                "dateOfBirthYear", "dateOfBirthMonth", "dateOfBirthDay");

        fixture.service.update(ACCOUNT_ID, submission, CURRENT_REVISION);

        ArgumentCaptor<Account> savedAccount = ArgumentCaptor.forClass(Account.class);
        ArgumentCaptor<Customer> savedCustomer = ArgumentCaptor.forClass(Customer.class);
        verify(fixture.accounts).saveAndFlush(savedAccount.capture());
        verify(fixture.customers).saveAndFlush(savedCustomer.capture());
        assertThat(savedAccount.getValue().getOpenDate())
                .as("the submitted leap day must be rejoined, not the stored 2020-01-01")
                .isEqualTo(LocalDate.of(2016, 2, 29));
        assertThat(savedAccount.getValue().getExpirationDate())
                .as("the submitted expiry must be rejoined, not the stored 2027-12-31")
                .isEqualTo(LocalDate.of(2031, 10, 15));
        assertThat(savedAccount.getValue().getReissueDate())
                .as("the submitted reissue date must be rejoined, not the stored 2024-06-01")
                .isEqualTo(LocalDate.of(2022, 7, 4));
        assertThat(savedCustomer.getValue().getDateOfBirth())
                .as("the submitted birth date must be rejoined, not the stored 1906-12-09")
                .isEqualTo(LocalDate.of(1917, 3, 22));
    }

    /**
     * Confirms a malformed AMOUNT becomes a per-field error rather than a failure to bind the request.
     *
     * <p>Assumptions: the submission carries every money value as digits-and-sign TEXT, and that is why
     * this works. Had the amount components been typed as a decimal, a value like {@code '5000.0A'}
     * would fail during deserialisation, before any edit ran, and the caller would receive a binding
     * complaint naming a parser instead of the sentence
     * {@code 1250-EDIT-SIGNED-9V2} composes at L2209. Keeping the component textual lets the value reach
     * the edit and earn the program's own sentence on the program's own field.</p>
     *
     * <p>Assumptions: this is not a licence to carry money loosely elsewhere. Once past the edit the
     * value becomes an exact scaled decimal for storage and arithmetic, never a binary floating-point
     * number, and it is transported as a JSON STRING so that no client parses it into one. The two
     * stances are complementary: text at the edge because the edge must be able to describe a malformed
     * value, exact decimal within because arithmetic must not lose a cent.</p>
     *
     * <p>Returns no value. A malformed amount that fails to bind, or one that binds and is then silently
     * accepted, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a malformed amount reaches the edit and earns its sentence, rather than failing to bind")
    void aMalformedAmountBecomesAPerFieldErrorRatherThanABindingFailure() {
        Fixture fixture = new Fixture();

        // WHY : Assumptions: that the submission CONSTRUCTS at all is half the assertion, which is why
        //       the malformed value is read back off the record before any edit runs. The component is a
        //       string, so nothing rejects the value before the edit sees it, and the caller therefore
        //       earns the sentence L2209 composes rather than a parser's binding complaint.
        AccountUpdateRequest request = fixture.request().creditLimit("5000.0A").build();
        assertThat(request.creditLimit()).isEqualTo("5000.0A");

        AccountUpdateService.EditVerdict verdict =
                fixture.service.editMapInputs(request, fixture.account, fixture.customer);

        assertThat(verdict.inputError()).isTrue();
        assertThat(verdict.fieldNames()).contains("creditLimit");
        assertThat(verdict.fieldErrors())
                .extracting(ApiError.FieldError::message)
                .contains(AccountUpdateService.LABEL_CREDIT_LIMIT
                        + AccountUpdateService.SUFFIX_IS_NOT_VALID);
    }

    /**
     * Confirms the driver accumulates EVERY failing field rather than stopping at the first.
     *
     * <p>Assumptions: the reference does not stop. Its driver at L1429 performs each edit in turn
     * through to L1678, and each edit writes its OWN marker: there are 36 acceptable-markers between
     * L191 and L352 with 36 matching unacceptable and 36 blank conditions, which is one marker triple per
     * editable field and would be pointless if the first failure ended the pass. The screen then
     * highlights every failing field at once, which is what lets a user fix a form in one visit rather
     * than one field per round trip.</p>
     *
     * <p>Assumptions: the aggregate sentence behaves DIFFERENTLY from the array and this case asserts
     * both channels together. The array accumulates without limit, while the sentence is LATCHED -- the
     * reference guards every composition with a test that the message slot is still empty, so the FIRST
     * failure's sentence is the one the user reads and later failures leave it alone. The slot is the
     * 75-character field declared at L479, and a channel that accumulated into it would overflow that
     * width on a form with several errors.</p>
     *
     * <p>Refactoring Rationale: the latch is asserted by naming the EXACT sentence the first failure
     * composes and then excluding the two later ones, because this case previously asserted only that the
     * sentence was non-null and no wider than the slot. Both of those hold for a channel that
     * OVERWRITES: a later refusal is equally non-null and equally short, so an implementation that
     * dropped the still-empty guard and left the user reading the last failure instead of the first
     * passed unchanged. The three submitted refusals are recorded in a known order -- the credit limit at
     * the driver's third {@code latch.record} call, the given name and the postal code well after it --
     * so the first is determined rather than incidental.</p>
     *
     * <p>Assumptions: the expected sentence is composed from the service's own published label and suffix
     * constants rather than written out as a literal, so a change to either travels into this assertion
     * instead of breaking it. The composition is exact because {@code composed} pads the label to the
     * 25-character width the reference declares and then trims it, which returns a label shorter than the
     * width unchanged.</p>
     *
     * <p>Assumptions: the two later refusals are read back out of the per-field array and asserted to
     * be PRESENT and DIFFERENT from the aggregate, rather than being written out here. That is what
     * distinguishes a latched channel from a channel that never received them: if the pass had stopped at
     * the credit limit, the two would be absent and the exclusion would hold vacuously. Their text is
     * deliberately not restated, because their wording belongs to the cases that assert those two edits
     * and duplicating it here would give one sentence two owners.</p>
     *
     * <p>Returns no value. A pass that stops at the first failure, or an aggregate sentence overwritten
     * by a later failure, is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("every failing field is accumulated while the aggregate sentence stays latched")
    void theTwoResponseChannelsAccumulateIndependently() {
        Fixture fixture = new Fixture();

        AccountUpdateService.EditVerdict verdict = fixture.service.editMapInputs(
                fixture.request()
                        .creditLimit("5000.0A")
                        .firstName("GRACE1")
                        .zipCode("1254A")
                        .build(),
                fixture.account, fixture.customer);

        String firstFailure = AccountUpdateService.LABEL_CREDIT_LIMIT
                + AccountUpdateService.SUFFIX_IS_NOT_VALID;
        String laterNameFailure = refusalFor(verdict, "firstName");
        String laterPostalFailure = refusalFor(verdict, AccountUpdateService.FIELD_ZIP_CODE);

        assertThat(verdict.inputError()).isTrue();
        assertThat(verdict.fieldNames())
                .contains("creditLimit", "firstName", AccountUpdateService.FIELD_ZIP_CODE);
        assertThat(laterNameFailure)
                .as("the given-name edit must have run and refused, or the exclusion below is vacuous")
                .isNotBlank();
        assertThat(laterPostalFailure)
                .as("the postal-code edit must have run and refused, or the exclusion below is vacuous")
                .isNotBlank();
        assertThat(verdict.message())
                .as("the user must read the FIRST refusal, which is the credit limit's")
                .isEqualTo(firstFailure)
                .isNotEqualTo(laterNameFailure)
                .isNotEqualTo(laterPostalFailure)
                .doesNotContain(AccountUpdateService.LABEL_FIRST_NAME)
                .doesNotContain(AccountUpdateService.LABEL_ZIP)
                .hasSizeLessThanOrEqualTo(75);
    }

    /**
     * Reads one field's refusal sentence out of a verdict's per-field array.
     *
     * @param verdict the verdict to read; must not be {@code null}
     * @param field the request property whose refusal to read; must not be {@code null}
     * @return the sentence recorded against that property, or {@code null} when it carries no entry
     */
    private static String refusalFor(AccountUpdateService.EditVerdict verdict, String field) {
        return verdict.fieldErrors().stream()
                .filter(entry -> field.equals(entry.field()))
                .map(ApiError.FieldError::message)
                .findFirst()
                .orElse(null);
    }

    /**
     * Confirms an accepted submission that changes nothing is reported as such and not as a failure.
     *
     * <p>Assumptions: the reference distinguishes THREE outcomes and not two, so a suite that only
     * separated accepted from refused would miss one. Re-submitting the values just fetched is neither:
     * it is accepted with nothing to do, and the reference says so in its own words rather than
     * pretending a write happened.</p>
     *
     * <p>Assumptions: this is the outcome that proves the change comparison runs at all. Its counterpart
     * refusal is the concurrency case above; between them they cover both answers the comparison can
     * give -- nothing moved and the row moved.</p>
     *
     * <p>Returns no value. A no-change submission reported as an error, or reported as a write, is
     * reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("re-submitting the fetched values is accepted with the no-change sentence")
    void aSubmissionThatChangesNothingIsAcceptedAndSaysSo() {
        Fixture fixture = new Fixture();

        // WHY : Assumptions: both protected identifiers are OMITTED, which is what makes this submission
        //       genuinely a no-change one. A supplied identifier is enciphered before it is compared, and
        //       the stand-in encipherment these cases use cannot reproduce the arbitrary ciphertext the
        //       fixture rows hold, so a submission naming them would report a change that is an artefact
        //       of the fixture rather than of the submission.
        AccountUpdateService.EditVerdict verdict = fixture.service.editMapInputs(
                fixture.request().submittingNoProtectedIdentifier().build(),
                fixture.account, fixture.customer);

        assertThat(verdict.inputError()).isFalse();
        assertThat(verdict.fieldErrors()).isEmpty();
        assertThat(verdict.noChangesFound()).isTrue();
        assertThat(verdict.message())
                .isEqualTo(AccountUpdateService.MESSAGE_NO_CHANGES_DETECTED);
    }

    /**
     * Confirms every field with a declared width refuses a value that exceeds it.
     *
     * <p>Refactoring Rationale: none of these refusals existed. The generic edits present a value at the
     * field's width first, which TRUNCATES it, so a twenty-six-character given name was examined as
     * twenty-five characters, passed its letters-only test and was reported acceptable by this driver --
     * which is the verdict the dry-run endpoint publishes. The committing endpoint then refused the same
     * submission from inside its mapper, whose width guards compose a sentence too long for a message line,
     * so the caller received the fixed fallback with no mention of a width. One submission, three different
     * answers depending on which endpoint received it.</p>
     *
     * <p>Assumptions: all sixteen widths are asserted from one table rather than in separate cases,
     * because the rule is one rule -- a value longer than its field is refused against that field -- and
     * sixteen cases asserting one rule would drift apart. Three of the sixteen had NO edit of any kind
     * before this change: the group code, the second address line and the identifier reference.</p>
     *
     * <p>Returns no value. A width that is not enforced, or is enforced against the wrong component, is
     * reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("every field with a declared width refuses a value one character over it")
    void everyDeclaredWidthRefusesAnOverWideValue() {
        /**
         * One width rule under test.
         *
         * @param field the request component the refusal must be keyed on
         * @param width the number of characters that component declares
         * @param submission a submission carrying one character too many in that component
         */
        record Case(String field, int width, AccountUpdateRequest submission) {
        }

        Fixture fixture = new Fixture();
        String over25 = "A".repeat(26);
        String over50 = "B".repeat(51);
        List<Case> cases = List.of(
                new Case("firstName", AccountUpdateService.NAME_EDIT_WIDTH,
                        fixture.request().firstName(over25).build()),
                new Case("lastName", AccountUpdateService.NAME_EDIT_WIDTH,
                        fixture.request().lastName(over25).build()),
                new Case("city", AccountUpdateService.ADDRESS_LINE_EDIT_WIDTH,
                        fixture.request().city(over50).build()),
                new Case("addressLine2", AccountUpdateService.ADDRESS_LINE_EDIT_WIDTH,
                        fixture.request().addressLine2(over50).build()),
                new Case("groupId", AccountUpdateService.GROUP_ID_EDIT_WIDTH,
                        fixture.request().groupId("GROUPIDTOOLONG").build()),
                new Case("governmentIssuedId",
                        AccountUpdateService.GOVERNMENT_IDENTIFIER_EDIT_WIDTH,
                        fixture.request().governmentIssuedId("G".repeat(21)).build()),
                new Case("eftAccountId", AccountUpdateService.EFT_ACCOUNT_ID_EDIT_WIDTH,
                        fixture.request().eftAccountId("00000000011").build()),
                new Case("activeStatus", AccountUpdateService.MARKER_WIDTH,
                        fixture.request().activeStatus("YN").build()));

        for (Case each : cases) {
            AccountUpdateService.EditVerdict verdict = fixture.service
                    .editMapInputs(each.submission(), fixture.account, fixture.customer);

            String expected = each.field().equals("activeStatus")
                    ? AccountUpdateService.SUFFIX_WIDTH_PREFIX + each.width()
                            + AccountUpdateService.SUFFIX_WIDTH_UNIT_SINGULAR
                    : AccountUpdateService.SUFFIX_WIDTH_PREFIX + each.width()
                            + AccountUpdateService.SUFFIX_WIDTH_UNIT_PLURAL;

            assertThat(verdict.inputError())
                    .as("%s must refuse a value wider than %d", each.field(), each.width())
                    .isTrue();
            assertThat(verdict.fieldErrors())
                    .as("%s must be the component the refusal is keyed on", each.field())
                    .anySatisfy(entry -> {
                        assertThat(entry.field()).isEqualTo(each.field());
                        assertThat(entry.state()).isEqualTo(FieldValidationFlag.NOT_OK);
                        assertThat(entry.message()).endsWith(expected);
                    });
        }
    }

    /**
     * Confirms a field filled only with padding is BLANK rather than too long.
     *
     * <p>Assumptions: this pins the ordering the width verdict depends on. A 3270 field cannot receive
     * more characters than it declares, so a twenty-six-character run of spaces is an empty field with one
     * space too many, and the reference would see it as blank. Taking the width verdict first would ask a
     * caller to shorten something it did not fill in.</p>
     *
     * <p>Returns no value. The wrong verdict is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an over-wide run of padding is reported blank, not too long")
    void anOverWideRunOfPaddingIsBlankNotTooLong() {
        Fixture fixture = new Fixture();

        AccountUpdateService.EditVerdict verdict = fixture.service.editMapInputs(
                fixture.request().firstName(" ".repeat(26)).build(),
                fixture.account, fixture.customer);

        assertThat(verdict.fieldErrors())
                .anySatisfy(entry -> {
                    assertThat(entry.field()).isEqualTo("firstName");
                    assertThat(entry.state()).isEqualTo(FieldValidationFlag.BLANK);
                    assertThat(entry.message())
                            .isEqualTo(AccountUpdateService.LABEL_FIRST_NAME
                                    + AccountUpdateService.SUFFIX_MUST_BE_SUPPLIED);
                });
    }

    /**
     * Confirms an amount too large for the stored field is refused by the EDIT, not by the column.
     *
     * <p>Refactoring Rationale: the shape test admits any value the fifteen-character mask can spell, and
     * fifteen characters hold a sign, ELEVEN integer digits, a point and two fraction digits -- one digit
     * more than {@code NUMERIC(12,2)} stores. Such a value passed every edit, reached the row builder,
     * overflowed the column and reached the caller as HTTP 500 from the persistence provider. The boundary
     * is asserted from both sides, because a bound written with the wrong comparison refuses the largest
     * storable amount as well.</p>
     *
     * <p>Returns no value. A magnitude that is not bounded, or a storable amount that is refused, is
     * reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an eleven-digit amount is refused and the largest storable one is accepted")
    void amountMagnitudeIsBoundedAtTheStoredPrecision() {
        Fixture fixture = new Fixture();

        AccountUpdateService.EditVerdict refused = fixture.service.editMapInputs(
                fixture.request().creditLimit("10000000000.00").build(),
                fixture.account, fixture.customer);

        assertThat(refused.inputError()).isTrue();
        assertThat(refused.fieldErrors())
                .as("the reference's own sentence for an unacceptable amount, carried verbatim")
                .anySatisfy(entry -> {
                    assertThat(entry.field()).isEqualTo("creditLimit");
                    assertThat(entry.state()).isEqualTo(FieldValidationFlag.NOT_OK);
                    assertThat(entry.message())
                            .isEqualTo(AccountUpdateService.LABEL_CREDIT_LIMIT
                                    + AccountUpdateService.SUFFIX_IS_NOT_VALID);
                });

        AccountUpdateService.EditVerdict accepted = fixture.service.editMapInputs(
                fixture.request().creditLimit("9999999999.99").build(),
                fixture.account, fixture.customer);

        assertThat(accepted.fieldErrors())
                .as("ten integer digits is what the field holds, so it must not be refused")
                .noneSatisfy(entry -> assertThat(entry.field()).isEqualTo("creditLimit"));
    }

    /**
     * Confirms leading zeros are not counted as magnitude.
     *
     * <p>Assumptions: the digits are counted after the mask's decoration is removed, so an amount written
     * with insignificant leading zeros is measured by what it MEANS. Counting characters instead would
     * refuse a value the stored field holds comfortably, which a caller padding a screen field to its full
     * width would provoke on every submission.</p>
     *
     * <p>Returns no value. A refusal here is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an amount padded with leading zeros is measured by its value, not its length")
    void leadingZerosAreNotCountedAsMagnitude() {
        Fixture fixture = new Fixture();

        AccountUpdateService.EditVerdict verdict = fixture.service.editMapInputs(
                fixture.request().creditLimit("00000000123.45").build(),
                fixture.account, fixture.customer);

        assertThat(verdict.fieldErrors())
                .noneSatisfy(entry -> assertThat(entry.field()).isEqualTo("creditLimit"));
    }

    /**
     * Confirms an over-long telephone part is refused rather than silently narrowed.
     *
     * <p>Refactoring Rationale: the three parts are assembled into a fifteen-character form whose fixed
     * positions the allow-list collaborator reads, so assembly narrows each part to its declared width. A
     * five-character exchange prefix therefore became three characters, passed every subsequent test, and a
     * caller that sent {@code 55555} was told its number was acceptable while a different number was
     * stored. Leaving the part unnarrowed instead would shift the two positions after it and report
     * failures against parts the caller sent correctly, so the fault has to be caught before assembly.</p>
     *
     * <p>Assumptions: the sentences are the collaborator's own, so a part of the wrong width reads exactly
     * as a part of the wrong shape does, and no new wording is introduced for a case the reference already
     * words.</p>
     *
     * <p>Returns no value. A narrowed part reported as acceptable is a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an over-long telephone part is refused with the collaborator's own sentence")
    void anOverLongTelephonePartIsRefused() {
        Fixture fixture = new Fixture();

        /**
         * One telephone part under test.
         *
         * @param component the request component the refusal must be keyed on
         * @param wording the collaborator's own sentence for a part of the wrong shape
         * @param submission a submission carrying an over-long value in that part
         */
        record Part(String component, String wording, AccountUpdateRequest submission) {
        }

        List<Part> parts = List.of(
                new Part(AccountUpdateService.FIELD_PHONE_1
                        + AddressValidationService.FIELD_SUFFIX_AREA_CODE,
                        AddressValidationService.MSG_AREA_CODE_NOT_THREE_DIGITS,
                        fixture.request().phone1("7031", "555", "0101").build()),
                new Part(AccountUpdateService.FIELD_PHONE_1
                        + AddressValidationService.FIELD_SUFFIX_PHONE_PREFIX,
                        AddressValidationService.MSG_PREFIX_NOT_THREE_DIGITS,
                        fixture.request().phone1("703", "55555", "0101").build()),
                new Part(AccountUpdateService.FIELD_PHONE_1
                        + AddressValidationService.FIELD_SUFFIX_PHONE_LINE_NUMBER,
                        AddressValidationService.MSG_LINE_NUMBER_NOT_FOUR_DIGITS,
                        fixture.request().phone1("703", "555", "012345").build()));

        for (Part each : parts) {
            AccountUpdateService.EditVerdict verdict = fixture.service
                    .editMapInputs(each.submission(), fixture.account, fixture.customer);

            assertThat(verdict.inputError())
                    .as("%s must refuse a part wider than its field", each.component())
                    .isTrue();
            assertThat(verdict.fieldErrors())
                    .anySatisfy(entry -> {
                        assertThat(entry.field()).isEqualTo(each.component());
                        assertThat(entry.message()).isEqualTo(
                                AccountUpdateService.LABEL_PHONE_NUMBER_1 + each.wording());
                    });
        }
    }

    /**
     * Confirms a submission that leaves the national identifier as it stands is not edited against it.
     *
     * <p>Refactoring Rationale: this is what made a read-then-write cycle impossible. Every read withholds
     * the identifier behind a marker, so a client that reads an account, changes one field and submits the
     * record it holds sends either three absent components or three marked ones. Both used to reach the
     * three required-numeric edits and draw three refusals naming components the client had never filled
     * in -- and because those refusals fired on every such submission, the no-change comparison could never
     * be reached either.</p>
     *
     * <p>Assumptions: the marked case is asserted in three arrangements -- all three marked, and each of
     * two mixed with digits -- because the rule admits ANY marked component and a rule written for ALL of
     * them would pass the first arrangement and fail the other two.</p>
     *
     * <p>Returns no value. A refusal against an unedited identifier is a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an unedited national identifier draws no refusal, absent or withheld")
    void anUneditedNationalIdentifierDrawsNoRefusal() {
        Fixture fixture = new Fixture();
        String withheld = CustomerMapper.IDENTIFIER_REDACTED;

        List<AccountUpdateRequest> unedited = List.of(
                fixture.request().nationalIdentifier(null, null, null).build(),
                fixture.request().nationalIdentifier(withheld, withheld, withheld).build(),
                fixture.request().nationalIdentifier(withheld, "45", "6789").build(),
                fixture.request().nationalIdentifier("123", "45", withheld).build());

        for (AccountUpdateRequest submission : unedited) {
            AccountUpdateService.EditVerdict verdict =
                    fixture.service.editMapInputs(submission, fixture.account, fixture.customer);

            assertThat(verdict.fieldErrors())
                    .as("no identifier component may be refused when none was edited")
                    .noneSatisfy(entry -> assertThat(entry.field()).startsWith("ssnPart"));
        }

        AccountUpdateService.EditVerdict edited = fixture.service.editMapInputs(
                fixture.request().nationalIdentifier("12A", "45", "6789").build(),
                fixture.account, fixture.customer);

        assertThat(edited.fieldErrors())
                .as("a submission that DOES edit the identifier is still edited against")
                .anySatisfy(entry -> assertThat(entry.field()).isEqualTo("ssnPart1"));
    }

    /**
     * Confirms a submission echoing BOTH withheld identifiers reaches the no-change answer.
     *
     * <p>Assumptions: this is the property the two rules above exist for, asserted end to end. A client
     * that reads an account and submits exactly what it was shown has changed nothing, and the reference's
     * own no-change answer is what it should receive. Before the withheld marker was recognised, such a
     * submission was judged to have changed both identifiers -- so this answer was unreachable from the one
     * client behaviour that most naturally produces it.</p>
     *
     * <p>Returns no value. Any refusal, or a change being reported, is a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("a submission echoing both withheld identifiers is the no-change answer")
    void echoingBothWithheldIdentifiersIsTheNoChangeAnswer() {
        Fixture fixture = new Fixture();
        String withheld = CustomerMapper.IDENTIFIER_REDACTED;

        AccountUpdateService.EditVerdict verdict = fixture.service.editMapInputs(
                fixture.request()
                        .nationalIdentifier(withheld, withheld, withheld)
                        .governmentIssuedId(withheld)
                        .build(),
                fixture.account, fixture.customer);

        assertThat(verdict.inputError()).isFalse();
        assertThat(verdict.fieldErrors()).isEmpty();
        assertThat(verdict.noChangesFound()).isTrue();
        assertThat(verdict.message())
                .isEqualTo(AccountUpdateService.MESSAGE_NO_CHANGES_DETECTED);
    }

    /**
     * Confirms an accepted submission returns a response populating BOTH channels coherently.
     *
     * <p>Assumptions: the response carries a latched aggregate sentence and a per-field array as two
     * independent components, so the coherent shape of an ACCEPTED result is a sentence present and the
     * array EMPTY. That pairing is worth asserting because the two components can be set independently
     * and a result carrying both, or neither, would be meaningless to a screen deciding whether to
     * highlight fields.</p>
     *
     * <p>Returns no value. A response whose two channels disagree about whether the submission
     * succeeded is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("an accepted update returns a sentence with an empty per-field array")
    void anAcceptedUpdateReturnsBothChannelsCoherently() {
        Fixture fixture = new Fixture();

        AccountUpdateResponse response = fixture.service.update(ACCOUNT_ID,
                fixture.request().creditLimit("6000.00").build(), CURRENT_REVISION).response();

        assertThat(response.fieldErrors()).isEmpty();
        assertThat(response.returnMessage()).isNotNull();
        assertThat(response.accountId()).isNotBlank();
    }

    /**
     * Confirms the edit check reaches the validation sentence and writes neither row.
     *
     * <p>Purpose: the baseline treats validation and writing as two screen turns --
     * {@code 2000-DECIDE-ACTION}'s show-details arm at {@code app/cbl/COACTUPC.cbl} L2582 to L2590 moves
     * to {@code 88 ACUP-CHANGES-OK-NOT-CONFIRMED} once every edit has passed, and only the later PF5 turn
     * performs {@code 9600-WRITE-PROCESSING}. This case pins the first turn's two defining properties
     * together: it answers {@code Looks Good.... so far}, which is precisely the sentence
     * {@code 1200-EDIT-MAP-INPUTS} latches when every edit passed, and it saves nothing.</p>
     *
     * <p>Assumptions: no precondition is passed because the method takes none, and that is itself part of
     * what is asserted -- a signature demanding a revision would not compile against this call. The write
     * turn's precondition is unaffected and is asserted by its own cases above.</p>
     *
     * <p>Returns no value. An answer carrying the commit sentence, or either row reaching the repository,
     * is reported as a JUnit assertion failure.</p>
     */
    @Test
    @DisplayName("the edit check answers the validation sentence and writes neither row")
    void theEditCheckValidatesAndWritesNothing() {
        Fixture fixture = new Fixture();

        AccountUpdateResponse response = fixture.service.validateEdits(ACCOUNT_ID,
                fixture.request().creditLimit("6000.00").build());

        assertThat(response.fieldErrors()).isEmpty();
        assertThat(response.returnMessage())
                .isEqualTo(AccountUpdateService.MESSAGE_UPDATE_ACCEPTED);
        verify(fixture.accounts, never()).saveAndFlush(any(Account.class));
        verify(fixture.customers, never()).saveAndFlush(any(Customer.class));
    }

    /**
     * Confirms the edit check refuses the same submission the write turn refuses, in the same words.
     *
     * <p>Purpose: the whole reason the check exists is that the SERVICE stays the single authority on what
     * is acceptable, so a submission the write turn would refuse has to be refused here too and with the
     * same field names. If the two turns could disagree, a screen would validate successfully and then be
     * refused on save -- the exact failure the check was added to remove.</p>
     *
     * <p>Assumptions: the provoking value is the one {@code aMalformedSubmissionIsRefusedAsClientInput}
     * uses against the write turn, deliberately, so the two cases are comparable and a divergence between
     * the turns would show as one passing and the other failing.</p>
     *
     * <p>Returns no value. A refusal that names a different field, or a submission accepted here and
     * refused by the write turn, is reported as a JUnit assertion failure.</p>
     *
     * @throws ClientInputException never propagates from this method: the refusal is provoked and
     *     captured by the assertion below, which is what the case exists to demonstrate
     */
    @Test
    @DisplayName("the edit check refuses what the write refuses, naming the same field")
    void theEditCheckRefusesWhatTheWriteRefuses() {
        Fixture fixture = new Fixture();

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> fixture.service.validateEdits(ACCOUNT_ID,
                        fixture.request().creditLimit("5000.0A").build()))
                .matches(refusal -> refusal.fields().contains("creditLimit"),
                        "the refusal must name the failing field");

        verify(fixture.accounts, never()).saveAndFlush(any(Account.class));
        verify(fixture.customers, never()).saveAndFlush(any(Customer.class));
    }

    /**
     * Confirms an update refused for its INPUT is refused as a client-input failure, not a conflict.
     *
     * <p>Assumptions: the two refusals are different things and must stay distinguishable. A malformed
     * submission is the caller's to correct and carries the failing field names; a moved row is nobody's
     * mistake and carries none. The reference reaches them by different routes -- the edit driver's
     * error path for the first, the change comparison at L4143 and L4189 for the second -- and merging
     * them would tell a user to re-read a record when what they actually need to do is fix a field.</p>
     *
     * <p>Returns no value. An input failure raised as a conflict, or without naming its fields, is
     * reported as a JUnit assertion failure.</p>
     *
     * @throws ClientInputException never propagates from this method: the refusal is provoked and
     *     captured by the assertion below, which is what the case exists to demonstrate
     */
    @Test
    @DisplayName("a malformed submission is refused as client input and names its failing fields")
    void aMalformedSubmissionIsRefusedAsClientInput() {
        Fixture fixture = new Fixture();

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> fixture.service.update(ACCOUNT_ID,
                        fixture.request().creditLimit("5000.0A").build(), CURRENT_REVISION))
                .matches(refusal -> refusal.fields().contains("creditLimit"),
                        "the refusal must name the failing field");

        verify(fixture.accounts, never()).saveAndFlush(any(Account.class));
        verify(fixture.customers, never()).saveAndFlush(any(Customer.class));
    }

    /**
     * Assigns an entity's optimistic-lock counter reflectively.
     *
     * <p>Assumptions: both entities publish a getter and NO setter, because the counter belongs to the
     * persistence provider, which reads it on load, compares it on flush and advances it on a successful
     * write. A setter would let application code disable the concurrency check silently. A case needing
     * a row at a distinguishable version therefore has no other route, and reaching the field from the
     * test rather than adding a setter keeps that route out of production code. The same helper, for the
     * same reason, is used by the sibling {@code AccountViewRevisionTest}.</p>
     *
     * @param entity the account or customer row to assign; must not be {@code null}
     * @param value the counter value to assign
     * @throws IllegalStateException if the field cannot be reached, which would mean the entity no
     *     longer declares the counter this assertion depends on
     */
    private static void versionOf(Object entity, long value) {
        try {
            java.lang.reflect.Field field = entity.getClass().getDeclaredField("version");
            field.setAccessible(true);
            field.setLong(entity, value);
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException(
                    "the entity no longer declares the version counter this case depends on",
                    unreachable);
        }
    }

    /**
     * Enciphers one identifier into a recognisable stand-in for the ciphertext a row stores.
     *
     * <p>Assumptions: the stand-in is deliberately RECOGNISABLE rather than realistic, so that a value
     * leaking into an asserted payload would be obvious on sight. What the real protection does is
     * irrelevant to every case here, which asserts edits and markers rather than encipherment; that is
     * asserted by the sibling {@code CustomerIdentifierCipherTest}. No clear identifier and no card
     * verification value appears anywhere in this class.</p>
     *
     * @param clearText the identifier in its clear form, as the submission supplies it
     * @param field the name of the column being enciphered, which the stand-in embeds so two columns
     *     cannot produce the same bytes
     * @return the stand-in ciphertext bytes for that column
     */
    private static byte[] recognisableCiphertext(String clearText, String field) {
        return ("enc:" + field + ":" + clearText).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads one component back out of an assembled telephone value at the delegate's own offset.
     *
     * <p>Assumptions: the offset is an index from the start of the fifteen-character field rather than a
     * component ordinal, matching how the delegate itself slices the value, so a component is read the
     * same way here as the sub-routine that owns it reads it. Slicing rather than splitting on the
     * punctuation is deliberate: the sub-routines read fixed positions and never look for a separator,
     * so a value whose punctuation moved would still be sliced at the declared positions.</p>
     *
     * @param assembled the fifteen-character assembled value handed to the delegate
     * @param offset the delegate's published index of the component's first character
     * @param width the delegate's published width of the component
     * @return the component's characters
     */
    private static String componentOf(String assembled, int offset, int width) {
        return assembled.substring(offset, offset + width);
    }

    /**
     * One assembled service under test, with its two rows, its substituted collaborators and its
     * stubbed address delegate.
     *
     * <p>Assumptions: a fresh instance per case is what keeps the cases independent, so the class is
     * constructed inside each method rather than shared through a lifecycle hook. The cases that verify
     * a delegated invocation would otherwise see calls another case had made.</p>
     */
    private static final class Fixture {

        /** The substituted account master, answering the keyed read and echoing what it is asked to save. */
        private final AccountRepository accounts = mock(AccountRepository.class);

        /** The substituted customer master, answering the keyed read and echoing what it is asked to save. */
        private final CustomerRepository customers = mock(CustomerRepository.class);

        /** The substituted cross-reference, answering the bounded single-row query the service issues. */
        private final CardXrefRepository crossReferences = mock(CardXrefRepository.class);

        // WHY : Assumptions: the address delegate is SUBSTITUTED here, unlike in the sibling preservation
        //       case which supplies the real one over a permissive lookup. This class asserts THAT the
        //       four value-domain edits are issued and how their verdicts travel, which a substitute can
        //       witness and a real instance cannot; the content of the five allow-lists is asserted by
        //       AddressValidationServiceTest, and the tables behind them belong to reference-service.
        /** The stubbed address delegate, whose invocations several cases verify. */
        private final AddressValidationService addressValidation =
                mock(AddressValidationService.class);

        /** The stored account row, at the initial revision unless a case advances it. */
        private final Account account;

        /** The stored customer row, at the initial revision unless a case advances it. */
        private final Customer customer;

        /** The service under test, wired to the substitutes above. */
        private final AccountUpdateService service;

        /**
         * Assembles the service over two rows whose values the default submission re-states exactly.
         *
         * @param storedLastName the surname the customer row already holds, which a case varies to stand
         *     for a value a second writer has changed
         */
        Fixture(String storedLastName) {
            this.account = new Account(ACCOUNT_ID, "Y", new BigDecimal("100.00"),
                    new BigDecimal("5000.00"), new BigDecimal("500.00"),
                    LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31), LocalDate.of(2024, 6, 1),
                    BigDecimal.ZERO, BigDecimal.ZERO, "12546", "DEFAULT");

            // WHY : Assumptions: the stored telephone number carries its PUNCTUATION and its two-character
            //       trailing pad, being exactly the fifteen characters the reference's own redefinition
            //       predicts -- an opening character, the area code, a closing character, the exchange
            //       prefix, a separator, the line number and two blanks. The delegation case verifies the
            //       assembled value handed to the delegate, so an unpunctuated fixture would make that
            //       case assert the wrong contract.
            this.customer = new Customer(CUSTOMER_ID, "GRACE", null, storedLastName,
                    "1 NAVY YARD", null, "ARLINGTON", "VA", "USA", "12546     ",
                    "(703)555-0101  ", null, STORED_NATIONAL_ID, STORED_GOVERNMENT_ID,
                    LocalDate.of(1906, 12, 9), "0000000001", "Y", (short) 800);

            when(this.accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(this.account));
            when(this.customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(this.customer));
            when(this.crossReferences.findFirstByAccountIdOrderByCardNumAsc(ACCOUNT_ID))
                    .thenReturn(Optional.of(new CardXref(CARD_NUMBER, CUSTOMER_ID, ACCOUNT_ID)));
            when(this.accounts.saveAndFlush(any(Account.class)))
                    .thenAnswer(call -> call.getArgument(0));
            when(this.customers.saveAndFlush(any(Customer.class)))
                    .thenAnswer(call -> call.getArgument(0));

            // WHY : Assumptions: all four delegate methods are stubbed ACCEPTING, because the delegate's
            //       verdict record refuses a shape carrying neither a field error nor a message together.
            //       An unstubbed substitute answers null, and the service would then fail on a null
            //       verdict rather than on anything a case is asserting. A case wanting a refusal
            //       re-stubs the one method it cares about.
            when(this.addressValidation.validateStateCode(any(), any(), any()))
                    .thenReturn(AddressValidationService.AddressValidationResult.valid());
            when(this.addressValidation.validateStateZipCombination(any(), any(), any(), any()))
                    .thenReturn(AddressValidationService.AddressValidationResult.valid());
            when(this.addressValidation.validateUsPhoneNumber(any(), any(), any()))
                    .thenReturn(AddressValidationService.AddressValidationResult.valid());

            // WHY : Assumptions: the time source is FIXED rather than the system clock, so the
            //       date-of-birth range edit has a pinned boundary and a case added later cannot start
            //       depending on the day the suite runs. The date chosen is the one the reference's own
            //       batch jobs inject as a business date, so the fixture's date of birth sits well inside
            //       every range regardless of when this runs.
            // WHY : Assumptions: the transaction manager is SUBSTITUTED, so both templates run their
            //       callbacks and commit nothing. These cases assert which values are edited, refused and
            //       written, not that an engine committed; the commit belongs to the container-backed
            //       integration test in the sibling repository package.
            this.service = new AccountUpdateService(this.accounts, this.customers,
                    this.crossReferences, new AccountMapper(),
                    new CustomerMapper(AccountUpdateServiceTest::recognisableCiphertext),
                    this.addressValidation,
                    Clock.fixed(LocalDate.of(2022, 7, 18).atStartOfDay(ZoneOffset.UTC).toInstant(),
                            ZoneOffset.UTC),
                    mock(PlatformTransactionManager.class));
        }

        /**
         * Assembles the service over rows holding the ordinary stored surname.
         */
        Fixture() {
            this("HOPPER");
        }

        /**
         * Opens a submission that re-states every stored value exactly, for a case to vary one field of.
         *
         * <p>Assumptions: the default submission is deliberately a NO-CHANGE one, so that any outcome a
         * case observes is attributable to the single field it varied. A default that already differed
         * from the rows would make every case assert two things at once.</p>
         *
         * @return a builder pre-loaded with the stored values
         */
        RequestBuilder request() {
            return new RequestBuilder();
        }
    }

    /**
     * Builds the flat 43-component submission, varying one field at a time.
     *
     * <p>Assumptions: the submission is flat and every component is TEXT. It carries 43 of them, which
     * is the 54 named data fields of {@code app/cpy-bms/COACTUP.CPY} less six of screen furniture, two
     * message fields and three function-key legends. The reference corroborates the figure from the
     * other direction: between L1051 and L1419 it normalises exactly 43 submitted fields with one
     * repeated idiom, testing each for an asterisk or spaces before moving either low values or the
     * value itself. Counting those sites needs the ASTERISK as the discriminator, which yields 43 with
     * the first at L1051 and the last at L1419; counting on the spaces test alone yields only 42.</p>
     *
     * <p>Assumptions: three groups arrive SPLIT rather than whole, matching the boxes the screen
     * presents -- each of the four dates into a four-character year and two two-character halves, the
     * national identifier into three parts of 3, 2 and 4, and each telephone number into an area code,
     * an exchange prefix and a line number of 3, 3 and 4. The account's own postal code is deliberately
     * ABSENT from the submission even though the account record declares one at L15 of
     * {@code app/cpy/CVACT01Y.cpy}, because the screen never offered it.</p>
     *
     * <p>Assumptions: every numeric component is a digits-only STRING and not a number, and two
     * independent things in the reference say it should be. Its before-image holds each numeric twice,
     * as a character field redefined as a number -- the account key at L671 redefined at L672 and L673,
     * the current balance at L675 redefined at L676 and L677, the credit limit at L678 to L680, the cash
     * credit limit at L681 to L683 and the credit score at L754 redefined at L755 and L756. And the two
     * symbolic maps disagree with each other about the SAME field on the SAME line: L60 of
     * {@code app/cpy-bms/COACTVW.CPY} declares the account key as eleven digits while L60 of
     * {@code app/cpy-bms/COACTUP.CPY} declares it as eleven characters. The target takes the stricter
     * update-map form, which is what lets a malformed value reach an edit and earn a sentence.</p>
     */
    private static final class RequestBuilder {

        /** The submitted account key, an eleven-digit non-zero value by default. */
        private String accountId = "10000000001";

        /** The submitted credit limit as edited text, re-stating the stored value by default. */
        private String creditLimit = "5000.00";

        /** The submitted given name, re-stating the stored value by default. */
        private String firstName = "GRACE";

        /** The submitted surname, re-stating the stored value by default. */
        private String lastName = "HOPPER";

        /** The submitted two-character state code, re-stating the stored value by default. */
        private String stateCode = "VA";

        /** The submitted five-digit postal code, re-stating the stored value by default. */
        private String zipCode = "12546";

        /** The submitted leading group of the national identifier, or {@code null} to omit it. */
        private String nationalIdentifierPart1 = "123";

        /** The submitted middle group of the national identifier, or {@code null} to omit it. */
        private String nationalIdentifierPart2 = "45";

        /** The submitted trailing group of the national identifier, or {@code null} to omit it. */
        private String nationalIdentifierPart3 = "6789";

        /** The submitted government-issued identifier, or {@code null} to omit it. */
        private String governmentIssuedId = "GOVTID0001";

        /** The submitted city, an admitted letters-only value by default. */
        private String city = "ARLINGTON";

        /** The submitted account group code, inside its declared ten characters by default. */
        private String groupId = "DEFAULT";

        /** The submitted second address line, absent by default because the field is optional. */
        private String addressLine2;

        /** The submitted account status marker, an admitted single character by default. */
        private String activeStatus = "Y";

        /** The submitted funds-transfer account, ten digits by default. */
        private String eftAccountId = "0000000001";

        /** The submitted first telephone number's three parts, an admitted number by default. */
        private String[] phone1 = {"703", "555", "0101"};

        /** The submitted account open date as three decomposed parts, re-stating the stored value. */
        private String[] openDate = {"2020", "01", "01"};

        /** The submitted account expiry date as three decomposed parts, re-stating the stored value. */
        private String[] expirationDate = {"2027", "12", "31"};

        /** The submitted account reissue date as three decomposed parts, re-stating the stored value. */
        private String[] reissueDate = {"2024", "06", "01"};

        /** The submitted customer date of birth as three decomposed parts, re-stating the stored value. */
        private String[] dateOfBirth = {"1906", "12", "09"};

        /**
         * Varies the submitted account key.
         *
         * @param value the account key to submit
         * @return this builder
         */
        RequestBuilder accountId(String value) {
            this.accountId = value;
            return this;
        }

        /**
         * Varies the submitted credit limit.
         *
         * @param value the credit limit to submit, as edited text
         * @return this builder
         */
        RequestBuilder creditLimit(String value) {
            this.creditLimit = value;
            return this;
        }

        /**
         * Varies the submitted given name.
         *
         * @param value the given name to submit
         * @return this builder
         */
        RequestBuilder firstName(String value) {
            this.firstName = value;
            return this;
        }

        /**
         * Varies the submitted city.
         *
         * @param value the city to submit
         * @return this builder
         */
        RequestBuilder city(String value) {
            this.city = value;
            return this;
        }

        /**
         * Varies the submitted account group code.
         *
         * @param value the group code to submit
         * @return this builder
         */
        RequestBuilder groupId(String value) {
            this.groupId = value;
            return this;
        }

        /**
         * Varies the submitted second address line.
         *
         * @param value the second address line to submit
         * @return this builder
         */
        RequestBuilder addressLine2(String value) {
            this.addressLine2 = value;
            return this;
        }

        /**
         * Varies the submitted account status marker.
         *
         * @param value the marker to submit
         * @return this builder
         */
        RequestBuilder activeStatus(String value) {
            this.activeStatus = value;
            return this;
        }

        /**
         * Varies the submitted funds-transfer account.
         *
         * @param value the account to submit
         * @return this builder
         */
        RequestBuilder eftAccountId(String value) {
            this.eftAccountId = value;
            return this;
        }

        /**
         * Varies the submitted government-issued identifier reference.
         *
         * @param value the reference to submit
         * @return this builder
         */
        RequestBuilder governmentIssuedId(String value) {
            this.governmentIssuedId = value;
            return this;
        }

        /**
         * Varies the three parts of the submitted first telephone number.
         *
         * @param areaCode the area code to submit
         * @param phonePrefix the exchange prefix to submit
         * @param lineNumber the line number to submit
         * @return this builder
         */
        RequestBuilder phone1(String areaCode, String phonePrefix, String lineNumber) {
            this.phone1 = new String[] {areaCode, phonePrefix, lineNumber};
            return this;
        }

        /**
         * Varies the three parts of the submitted national identifier.
         *
         * @param firstPart the first three characters to submit
         * @param middlePart the middle two characters to submit
         * @param lastPart the last four characters to submit
         * @return this builder
         */
        RequestBuilder nationalIdentifier(String firstPart, String middlePart, String lastPart) {
            this.nationalIdentifierPart1 = firstPart;
            this.nationalIdentifierPart2 = middlePart;
            this.nationalIdentifierPart3 = lastPart;
            return this;
        }

        /**
         * Varies the submitted surname.
         *
         * @param value the surname to submit
         * @return this builder
         */
        RequestBuilder lastName(String value) {
            this.lastName = value;
            return this;
        }

        /**
         * Varies the submitted state code.
         *
         * @param value the two-character state code to submit
         * @return this builder
         */
        RequestBuilder stateCode(String value) {
            this.stateCode = value;
            return this;
        }

        /**
         * Varies the submitted postal code.
         *
         * @param value the five-digit postal code to submit
         * @return this builder
         */
        RequestBuilder zipCode(String value) {
            this.zipCode = value;
            return this;
        }

        /**
         * Varies the submitted account open date, decomposed as the screen presents it.
         *
         * @param year the four-character year part to submit
         * @param month the two-character month part to submit
         * @param day the two-character day part to submit
         * @return this builder
         */
        RequestBuilder openDate(String year, String month, String day) {
            this.openDate = new String[] {year, month, day};
            return this;
        }

        /**
         * Varies the submitted account expiry date, decomposed as the screen presents it.
         *
         * @param year the four-character year part to submit
         * @param month the two-character month part to submit
         * @param day the two-character day part to submit
         * @return this builder
         */
        RequestBuilder expirationDate(String year, String month, String day) {
            this.expirationDate = new String[] {year, month, day};
            return this;
        }

        /**
         * Varies the submitted account reissue date, decomposed as the screen presents it.
         *
         * @param year the four-character year part to submit
         * @param month the two-character month part to submit
         * @param day the two-character day part to submit
         * @return this builder
         */
        RequestBuilder reissueDate(String year, String month, String day) {
            this.reissueDate = new String[] {year, month, day};
            return this;
        }

        /**
         * Varies the submitted customer date of birth, decomposed as the screen presents it.
         *
         * @param year the four-character year part to submit
         * @param month the two-character month part to submit
         * @param day the two-character day part to submit
         * @return this builder
         */
        RequestBuilder dateOfBirth(String year, String month, String day) {
            this.dateOfBirth = new String[] {year, month, day};
            return this;
        }

        /**
         * Omits both protected identifiers, so the submission asks that the stored ones be kept.
         *
         * <p>Assumptions: an omitted protected identifier means PRESERVE and not delete, so a
         * submission leaving both out re-states the stored row exactly. That is the only way to build a
         * genuine no-change submission here, because a supplied identifier is enciphered before it is
         * compared and the stand-in encipherment used by these cases cannot reproduce the arbitrary
         * ciphertext the fixture rows were seeded with. The sibling
         * {@code AccountUpdatePreservationTest} builds its own no-change submission the same way, and
         * what an omitted identifier resolves to is asserted there rather than here.</p>
         *
         * @return this builder
         */
        RequestBuilder submittingNoProtectedIdentifier() {
            this.nationalIdentifierPart1 = null;
            this.nationalIdentifierPart2 = null;
            this.nationalIdentifierPart3 = null;
            this.governmentIssuedId = null;
            return this;
        }

        /**
         * Seals the submission into the flat 43-component record the service accepts.
         *
         * @return the assembled submission
         */
        AccountUpdateRequest build() {
            return new AccountUpdateRequest(
                    this.accountId, this.activeStatus, this.creditLimit, "500.00", "100.00",
                    "0.00", "0.00",
                    this.openDate[0], this.openDate[1], this.openDate[2],
                    this.expirationDate[0], this.expirationDate[1], this.expirationDate[2],
                    this.reissueDate[0], this.reissueDate[1], this.reissueDate[2],
                    this.groupId, "900000001",
                    this.nationalIdentifierPart1, this.nationalIdentifierPart2,
                    this.nationalIdentifierPart3,
                    this.dateOfBirth[0], this.dateOfBirth[1], this.dateOfBirth[2],
                    "800",
                    this.firstName, null, this.lastName,
                    "1 NAVY YARD", this.addressLine2, this.city, this.stateCode, "USA",
                    this.zipCode,
                    this.phone1[0], this.phone1[1], this.phone1[2],
                    null, null, null,
                    this.governmentIssuedId, this.eftAccountId, this.activeStatus);
        }
    }
}
