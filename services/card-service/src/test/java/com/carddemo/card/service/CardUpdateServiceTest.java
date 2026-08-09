package com.carddemo.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.card.domain.Card;
import com.carddemo.card.domain.EncryptedCvv;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardUpdateRequest;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.security.SealedSelector;
import com.carddemo.common.validation.FieldValidationFlag;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Parity evidence for {@link CardUpdateService}, the update chain transcribed from
 * {@code app/cbl/COCRDUPC.cbl} (1560 lines, CICS transaction {@code CCUP}).
 *
 * <h2>What this class asserts, and what it deliberately does not</h2>
 *
 * <p>Every case here drives the service through its two published entry points,
 * {@link CardUpdateService#update(String, CardUpdateRequest)} and
 * {@link CardUpdateService#validateAttributes(CardUpdateRequest)}, over a mocked
 * {@link CardRepository}. The claim is therefore about the edit chain and the write
 * orchestration: which gates run, in what order faults are reported, which sentence a
 * caller receives, and what the service does or does not ask the store to do. The
 * generated SQL is out of scope and belongs to {@code CardRepositoryIT} in the sibling
 * {@code com.carddemo.card.repository} test package.</p>

 * <p>Assumptions: the repository is mocked but {@link CardMapper} is real, built over a
 * test-only {@link SealedSelector}. Alternatives Considered: mocking the mapper as well,
 * which is the obvious symmetry. Rejected because two of this class's required claims are
 * assertions about what the mapper does to a stored row -- that an update leaves the
 * enciphered verification value alone, and that it carries the stored day of the month
 * forward -- and a mocked mapper would assert those against a stub of itself rather than
 * against the projection a caller actually receives. It also keeps {@link CardDetail}
 * obtainable at all: that record's compact constructor demands a genuinely sealed selector
 * and a masked number, so a hand-built instance is not available to this class.</p>
 *
 * <h2>The reference paragraphs these cases were transcribed from</h2>
 *
 * <p>Assumptions: the dispatcher is {@code 1200-EDIT-MAP-INPUTS.} at
 * {@code app/cbl/COCRDUPC.cbl:641}, exiting at {@code :717}. It drives two key gates,
 * {@code 1210-EDIT-ACCOUNT} at {@code :721} and {@code 1220-EDIT-CARD} at {@code :762},
 * and four attribute gates: {@code 1230-EDIT-NAME} at {@code :806} exiting {@code :841},
 * {@code 1240-EDIT-CARDSTATUS} at {@code :845} exiting {@code :874},
 * {@code 1250-EDIT-EXPIRY-MON} at {@code :877} exiting {@code :910}, and
 * {@code 1260-EDIT-EXPIRY-YEAR} at {@code :913} exiting {@code :945}. Searching that
 * program for {@code 12[0-9]0-EDIT} returns those seven paragraphs and their exits and
 * nothing else, which is the measurement behind the day-of-month case below.</p>
 *
 * <h2>The four decisions a reader would otherwise have to guess at</h2>
 *
 * <p>Assumptions: the chain ACCUMULATES rather than stopping at its first fault, and that
 * is the reference program's own behaviour. The dispatcher performs the four gates back to
 * back at {@code :698-708} with no branch between them and tests {@code INPUT-ERROR} only
 * afterwards, at {@code :710}. Each gate's every {@code GO TO} names only its own exit --
 * {@code :819} and {@code :836} for the name, {@code :858} and {@code :871} for the status,
 * {@code :891} and {@code :906} for the month, {@code :924} and {@code :942} for the year --
 * so a fault in one gate cannot stop the next from running. Trade-offs: a chain that
 * short-circuited would be cheaper to express and would still satisfy a test that checked
 * one fault, which is exactly why the accumulation case below inspects the whole set and
 * a second, separate case inspects the summary sentence. The two behaviours differ and
 * collapsing them into one assertion would let a short-circuiting chain pass.</p>
 *
 * <p>Assumptions: the four attribute gates run unconditionally only once BOTH of the
 * dispatcher's early exits have been passed, and there are two of them rather than one.
 * The first is {@code GO TO 1200-EDIT-MAP-INPUTS-EXIT} at {@code :661}, taken while
 * {@code CCUP-DETAILS-NOT-FETCHED} holds at {@code :645}, which reaches the two key gates
 * and none of the four. The second is at {@code :692}, guarded by {@code :685-687}, which
 * forces all four attribute flags valid at {@code :688-691} before leaving. Each has its
 * own case below, because a claim that the four gates always run is false at both.</p>
 *
 * <p>Assumptions: the stored day of the month survives an update untouched, and the
 * baseline is richer here than it first appears. Its update screen declares
 * {@code 02  EXPDAYI  PIC X(2).} at {@code app/cpy-bms/COCRDUP.CPY:96} so a day is
 * accepted; the {@code STRING} at {@code :1467-1474} assembles it into the stored date, at
 * {@code :675} a day is moved into the record, and at {@code :1507} a day is compared. Yet
 * no {@code 1270-EDIT-EXPIRY-DAY} paragraph exists, so the day is accepted, written and
 * compared while never being validated. {@link CardUpdateRequest} carries four editable
 * components and a day is not among them, so the target preserves what is stored. The
 * baseline does the one thing, this migration does the other, and the divergence is
 * recorded rather than smoothed over. No calendar validation is added here.</p>
 *
 * <p>Assumptions: the enciphered verification value is neither accepted from a caller nor
 * altered by a write. In the reference program {@code CCUP-NEW-CVV-CD} occurs on exactly
 * two lines, its declaration at {@code :306} and a single {@code MOVE} at {@code :1464}
 * where it is the SOURCE, so it is never a move target and an update cannot change it. Two
 * cases below assert the two halves of that separately, because a contract that omits the
 * component and an implementation that leaves the column alone are different properties
 * and either could regress without the other.</p>
 *
 * <p>Alternatives Considered: reproducing the literal asterisk that blanks a pre-filled
 * screen field. {@code app/cbl/COCRDSLC.cbl:614} carries the comment
 * {@code REPLACE * WITH LOW-VALUES} and the two blocks at {@code :615-620} and
 * {@code :622-627} fold an asterisk or spaces down to low values before the edits run. It
 * is not reproduced and is not tested for. On a screen whose fields always hold their whole
 * declared width there was no other way to say "this field is now empty", whereas an HTTP
 * caller says it by omitting the member. Reproducing it would make an asterisk a reserved
 * value, so a caller legitimately sending one would silently get a different request than
 * it wrote. The OUTBOUND asterisk is a different mechanism and IS asserted here, through
 * {@link FieldValidationFlag#screenMarker()}.</p>
 *
 * <h2>No parity oracle exists for this program</h2>
 *
 * <p>Assumptions: lines 83 to 85 of {@code tests/README.md} record that the online CICS
 * programs cannot be run end to end without a CICS runtime, which the runner does not
 * provide, and that only their extractable field-validation logic is unit-tested. That
 * suite's golden masters cover the batch flows and {@code COCRDUPC} is not a batch
 * program. Parity here therefore rests on transcription fidelity against the paragraphs
 * cited above plus the record contract, and on nothing stronger. No assertion in this file
 * may be read as a comparison against a recorded baseline run, because there is no such
 * run to compare against. A layout of inputs without recorded outputs is established
 * practice in that suite, which describes the same arrangement for its export domain, at
 * its lines 139 to 146, as internally consistent.</p>
 */
class CardUpdateServiceTest {

    /**
     * Test-only key material for the selector sealer, sized past the sealer's thirty-two-byte floor.
     *
     * <p>Assumptions: this is deliberately inert and self-describing rather than a value from
     * anywhere. A selector only has to round-trip within one case for these assertions to hold, so
     * no real key material is needed and none is present.</p>
     */
    private static final byte[] SELECTOR_KEY =
            "carddemo-card-update-service-test-selector-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /** Classpath name of the single-record positive control, whose active status is {@code Y}. */
    private static final String VALID_ACTIVE_RESOURCE = "fixtures/card-valid-active.txt";

    /** Classpath name of the single-record control whose active status is {@code N}. */
    private static final String VALID_INACTIVE_RESOURCE = "fixtures/card-valid-inactive.txt";

    /** Classpath name of the four records carrying every inclusive expiry bound. */
    private static final String BOUNDARY_EXPIRY_RESOURCE =
            "fixtures/card-boundary-expiry-inclusive.txt";

    /** Classpath name of the three records whose stored days are 01, 15 and 28. */
    private static final String DAY_PRESERVED_RESOURCE = "fixtures/card-expiry-day-preserved.txt";

    /** Classpath name of the two records whose names carry an apostrophe and a digit. */
    private static final String NAME_NON_ALPHA_RESOURCE =
            "fixtures/card-rule-reject-name-non-alpha.txt";

    /** Classpath name of the two records whose names are fifty spaces and fifty ASCII zeros. */
    private static final String NAME_BLANK_RESOURCE = "fixtures/card-rule-reject-name-blank.txt";

    /** Classpath name of the two records stored with expiry years 1949 and 2100. */
    private static final String YEAR_OUT_OF_RANGE_RESOURCE =
            "fixtures/card-rule-reject-expiry-year-out-of-range.txt";

    /** Classpath name of the single record whose stored active status is {@code X}. */
    private static final String STATUS_OUT_OF_DOMAIN_RESOURCE =
            "fixtures/card-schema-reject-status-out-of-domain.txt";

    /**
     * Classpath name of the two records whose expiry months are 00 and 13.
     *
     * <p>Assumptions: this corpus is read for its month field alone and is never turned into a
     * {@link Card}. Its stored dates are {@code 2025-00-12} and {@code 2023-13-23}, which no
     * calendar admits, so the records exist to drive validation and could not be persisted. The
     * fixtures README classes them accordingly.</p>
     */
    private static final String MONTH_OUT_OF_RANGE_RESOURCE =
            "fixtures/card-schema-reject-expiry-month-out-of-range.txt";

    /**
     * Bytes one corpus line occupies: the hundred-and-fifty-byte record plus its line terminator.
     *
     * <p>Assumptions: the record length is the one {@code app/cpy/CVACT02Y.cpy:4} declares for
     * {@code CARD-RECORD}, and the invariant that a corpus measures this many bytes for every record
     * it holds is stated normatively in
     * {@code services/card-service/src/test/resources/fixtures/README.md}. That file owns the byte
     * table and this class consumes it. The positions are not re-derived here, which is the
     * discipline the COBOL parity suite states at lines 540 to 542 of its own {@code README.md} --
     * never duplicate a layout, keep it single-sourced.</p>
     */
    private static final int LINE_LENGTH = 151;

    /** Bytes the record itself occupies, the line without its terminator. */
    private static final int RECORD_LENGTH = 150;

    /** Start of the {@code CARD-NUM} range; positions 1 to 16 of the record. */
    private static final int CARD_NUM_FROM = 0;

    /** End of the {@code CARD-NUM} range, exclusive. */
    private static final int CARD_NUM_TO = 16;

    /** End of the {@code CARD-ACCT-ID} range, exclusive; positions 17 to 27 of the record. */
    private static final int ACCOUNT_ID_TO = 27;

    /** Start of the {@code CARD-EMBOSSED-NAME} range; positions 31 to 80 of the record. */
    private static final int EMBOSSED_NAME_FROM = 30;

    /** End of the {@code CARD-EMBOSSED-NAME} range, exclusive. */
    private static final int EMBOSSED_NAME_TO = 80;

    /** Start of the {@code CARD-EXPIRAION-DATE} range; positions 81 to 90 of the record. */
    private static final int EXPIRATION_DATE_FROM = 80;

    /** End of the {@code CARD-EXPIRAION-DATE} range, exclusive. */
    private static final int EXPIRATION_DATE_TO = 90;

    /** End of the {@code CARD-ACTIVE-STATUS} range, exclusive; position 91 of the record. */
    private static final int ACTIVE_STATUS_TO = 91;

    /**
     * Offset of the year within the ten-character expiry field, which the reference reads as
     * {@code (1:4)}.
     */
    private static final int EXPIRY_YEAR_FROM = 0;

    /** End of the year within the expiry field, exclusive. */
    private static final int EXPIRY_YEAR_TO = 4;

    /** Offset of the month within the expiry field, which the reference reads as {@code (6:2)}. */
    private static final int EXPIRY_MONTH_FROM = 5;

    /** End of the month within the expiry field, exclusive. */
    private static final int EXPIRY_MONTH_TO = 7;

    /** Offset of the day within the expiry field, which the reference reads as {@code (9:2)}. */
    private static final int EXPIRY_DAY_FROM = 8;

    /** End of the day within the expiry field, exclusive. */
    private static final int EXPIRY_DAY_TO = 10;

    /**
     * The concurrency token a freshly built {@link Card} carries.
     *
     * <p>Assumptions: {@link Card} exposes {@code getVersion} and no setter, because the column is
     * maintained by the persistence provider. A constructed instance therefore reports zero, which
     * is the token an accepted submission has to echo.</p>
     */
    private static final int STORED_VERSION = 0;

    /** A token no constructed row carries, so a submission bearing it is stale by construction. */
    private static final int STALE_VERSION = 7;

    /** An expiry year inside the reference range, used where the year is not what a case varies. */
    private static final String NEUTRAL_YEAR = "2030";

    /** An expiry month inside the reference range, used where the month is not what a case varies. */
    private static final String NEUTRAL_MONTH = "07";

    /** Request path the rendering cases hand the shared advice, so a rendered body has one. */
    private static final String REQUEST_PATH = "/api/v1/cards/selector";

    /** The store this class drives the service against, mocked so no database is required. */
    private CardRepository cards;

    /** The real projection layer, so a published detail and an applied update are the genuine ones. */
    private CardMapper mapper;

    /** The sealer that mints the selectors these cases hand to the service. */
    private SealedSelector sealer;

    /** The unit under test, rebuilt per case so no case can observe another's state. */
    private CardUpdateService service;

    /** The shared advice, exercised where a case has to assert a rendered status and sentence. */
    private GlobalExceptionHandler advice;

    /**
     * Builds the collaborators and the unit under test before each case.
     *
     * <p>Assumptions: everything is rebuilt per case rather than once for the class, so the cases
     * stay independent and the class runs in any order. The advice takes a system clock because no
     * assertion here reads the timestamp it stamps onto a rendered body; pinning it would suggest a
     * case depended on it.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @BeforeEach
    void buildCollaborators() {
        // Assumptions: one sealer instance serves a whole case, because a selector sealed by one
        // instance opens only under an instance holding the same key and several cases hand a sealed
        // selector straight back into the service.
        this.cards = mock(CardRepository.class);
        this.sealer = new SealedSelector(SELECTOR_KEY);
        this.mapper = new CardMapper(this.sealer);
        this.service = new CardUpdateService(this.cards, this.mapper);
        this.advice = new GlobalExceptionHandler(Clock.systemUTC());
    }

    /**
     * Asserts that both collaborators are required, so a half-built service cannot exist.
     *
     * <p>Assumptions: the reference transaction is defined with {@code TWASIZE(0)} at line 369 of
     * {@code app/csd/CARDDEMO.CSD}, so its program was handed no work area to keep anything in and had
     * to be re-entrant. The target expresses the same property by holding both collaborators final and
     * keeping no mutable state, and refusing a null at construction is what makes that observable.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a service cannot be built without both collaborators")
    void bothCollaboratorsAreRequiredAtConstruction() {
        assertThatThrownBy(() -> new CardUpdateService(null, this.mapper))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CardUpdateService(this.cards, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Asserts that a selector this context did not issue is refused before the store is consulted.
     *
     * <p>Assumptions: this is the target form of the dispatcher's FIRST early exit, the
     * {@code GO TO 1200-EDIT-MAP-INPUTS-EXIT} at {@code app/cbl/COCRDUPC.cbl:661}, taken while
     * {@code CCUP-DETAILS-NOT-FETCHED} holds at {@code :645}. On that path the reference reaches its
     * two key gates, {@code 1210-EDIT-ACCOUNT} at {@code :721} and {@code 1220-EDIT-CARD} at
     * {@code :762}, and reaches none of the four attribute gates. Those two gates checked that a value
     * typed into a screen field was present and was a run of digits of the declared width, because the
     * platform delivered eleven and sixteen unexamined characters of a terminal datastream. In the
     * target, opening the sealed selector IS that check.</p>
     *
     * <p>Assumptions: the case also pins that no read is attempted, through
     * {@code verifyNoInteractions}. Trade-offs: asserting only the refusal would pass against an
     * implementation that read the row first and refused afterwards, which would hand a caller holding
     * an unopenable selector a lookup it never earned. Naming the absent interaction costs one line
     * and closes that.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a selector the service did not issue is refused before the store is touched")
    void aForgedSelectorIsRefusedBeforeTheStoreIsTouched() {
        CardUpdateRequest request = new CardUpdateRequest("Layla Ullrich", "Y",
                NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION);

        assertThatThrownBy(() -> this.service.update("not-a-selector-this-service-issued", request))
                .isInstanceOf(ClientInputException.class)
                .satisfies(failure -> assertThat(((ClientInputException) failure).field())
                        .isEqualTo(CardMapper.SELECTOR_FIELD));

        verifyNoInteractions(this.cards);
    }

    /**
     * Asserts that a selector which opens but names no stored row is reported as a missing record.
     *
     * <p>Assumptions: the reference read reports that condition with its own sentence, and the target
     * carries it verbatim as {@link CardViewService#MESSAGE_CARD_NOT_FOUND}, declared from
     * {@code 88 DID-NOT-FIND-ACCTCARD-COMBO} at {@code app/cbl/COCRDUPC.cbl:203-204}. The two
     * conditions are kept apart deliberately: an unopenable selector is the caller's error and a
     * selector naming nothing is the store's answer, so they are different outcomes even though a
     * screen showed both on the same line.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("a selector that opens but names no stored row is reported as a missing record")
    void aSelectorNamingNoStoredRowIsReportedAsMissing() throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        String selector = selectorFor(record);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.update(selector, echoOf(record, STORED_VERSION)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(CardViewService.MESSAGE_CARD_NOT_FOUND);

        verify(this.cards, never()).saveAndFlush(any(Card.class));
    }

    /**
     * Asserts that resubmitting what is already stored is accepted and writes nothing.
     *
     * <p>Assumptions: this is the target form of the dispatcher's SECOND early exit, at
     * {@code app/cbl/COCRDUPC.cbl:692}. The reference compares the whole submitted attribute group
     * against the snapshot at {@code :680-681}, sets its own sentence at {@code :682}, and the guard at
     * {@code :685-687} then leaves the paragraph. The submitted group is fifty-nine characters --
     * {@code CCUP-NEW-CARDDATA} at {@code :307} spans a fifty-character name at {@code :308}, a
     * four-character year at {@code :310}, a two-character month at {@code :311}, a two-character day
     * at {@code :312} and a one-character status at {@code :313} -- so a comparison covering anything
     * narrower would not be the reference's.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("resubmitting the stored values is accepted and writes nothing")
    void anUnchangedSubmissionIsAcceptedWithoutAWrite() throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        Card stored = cardFrom(record);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(stored));

        CardDetail answered =
                this.service.update(selectorFor(record), echoOf(record, STORED_VERSION));

        assertThat(answered.embossedName()).isEqualTo(embossedNameOf(record));
        assertThat(answered.expirationDate()).isEqualTo(expirationDateOf(record));
        assertThat(answered.activeStatus()).isEqualTo(activeStatusOf(record));
        verify(this.cards, never()).saveAndFlush(any(Card.class));
    }

    /**
     * Asserts that an unchanged submission is accepted even when its values would fail a gate.
     *
     * <p>Assumptions: this is the sharp edge of the second early exit and the reason the exit has to
     * be covered on its own. Before leaving at {@code app/cbl/COCRDUPC.cbl:692} the reference FORCES
     * all four attribute flags valid, at {@code :688-691}, so the gates at {@code :698-708} are never
     * reached for an unchanged submission and cannot refuse it. The record read here is stored with a
     * name of fifty spaces, which {@code 1230-EDIT-NAME} would class blank at {@code :811-820}; the
     * submission still has to be accepted, and nothing may be written.</p>
     *
     * <p>Trade-offs: this leans on a stored row that the edit rules would not accept as new input,
     * which looks contradictory until the two are separated -- the row is persistable and the rule
     * governs submissions. Alternatives Considered: asserting the exit with an ordinary valid record,
     * which is simpler. Rejected because such a case passes identically whether or not the flags are
     * forced valid, so it would evidence nothing about {@code :688-691}.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the blank-name corpus cannot be read from the test classpath
     */
    @Test
    @DisplayName("an unchanged submission is accepted even where the four gates would refuse it")
    void anUnchangedSubmissionSkipsTheGatesThatWouldOtherwiseRefuseIt() throws IOException {
        String record = recordsFrom(NAME_BLANK_RESOURCE).get(0);
        // Assumptions: the stored name is checked before the behaviour is exercised, because this case
        // is only evidence about the flag forcing at lines 688 to 691 if the row really does hold a
        // value the name gate would refuse. A corpus edit that tidied the name would otherwise leave
        // the case passing while proving nothing.
        assertThat(embossedNameOf(record)).isBlank();
        Card stored = cardFrom(record);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(stored));

        CardDetail answered =
                this.service.update(selectorFor(record), echoOf(record, STORED_VERSION));

        assertThat(answered.version()).isEqualTo(STORED_VERSION);
        verify(this.cards, never()).saveAndFlush(any(Card.class));
    }

    /**
     * Asserts that a submission differing from the stored values only in letter case is unchanged.
     *
     * <p>Assumptions: the reference makes this comparison case-insensitively in two separate places,
     * and the target adopts that. The dispatcher folds both sides at
     * {@code app/cbl/COCRDUPC.cbl:680-681} with {@code FUNCTION UPPER-CASE}, and the write path folds
     * the stored name again at {@code :1499-1501} with
     * {@code INSPECT CARD-EMBOSSED-NAME CONVERTING LIT-LOWER TO LIT-UPPER} before comparing at
     * {@code :1503-1508}. The fold covers exactly the twenty-six ASCII letters, because
     * {@code LIT-LOWER} and {@code LIT-UPPER} are both {@code PIC X(26)} at {@code :260-263}.</p>
     *
     * <p>Assumptions: the positive control's stored name is title-cased, so the same corpus record
     * carries both the mixed-case reading and the internal-space reading and no separate corpus is
     * needed for either.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("a submission differing only in letter case counts as no change at all")
    void aSubmissionDifferingOnlyInLetterCaseIsStillUnchanged() throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        // Assumptions: the stored name has to differ from its own upper-case form or this case would
        // submit the identical string and pass whether or not any folding happens.
        assertThat(embossedNameOf(record)).isNotEqualTo(embossedNameOf(record).toUpperCase(Locale.ROOT));
        Card stored = cardFrom(record);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(stored));

        CardUpdateRequest shouted = new CardUpdateRequest(
                embossedNameOf(record).toUpperCase(Locale.ROOT), activeStatusOf(record),
                expirationMonthOf(record), expirationYearOf(record), STORED_VERSION);

        this.service.update(selectorFor(record), shouted);

        verify(this.cards, never()).saveAndFlush(any(Card.class));
    }

    /**
     * Asserts that all four attribute gates run, so a submission faulting all four reports all four.
     *
     * <p>Assumptions: the dispatcher performs the gates back to back at
     * {@code app/cbl/COCRDUPC.cbl:698-708} with no branch between them and evaluates
     * {@code INPUT-ERROR} only afterwards at {@code :710}, and each gate's {@code GO TO} names only
     * its own exit. The four flags are independent storage -- {@code WS-EDIT-CARDNAME-FLAG} at
     * {@code :65-68}, {@code WS-EDIT-CARDSTATUS-FLAG} at {@code :69-72},
     * {@code WS-EDIT-CARDEXPMON-FLAG} at {@code :73-76} and {@code WS-EDIT-CARDEXPYEAR-FLAG} at
     * {@code :77-80} -- so all four can carry an error state from one submission.</p>
     *
     * <p>Trade-offs: this case inspects the WHOLE returned set and its exact order rather than
     * checking that it is non-empty. A chain that stopped at its first fault would satisfy the weaker
     * check and would still be a divergence, which is the whole reason the stronger one is written
     * here. Its companion case on the summary sentence asserts the opposite property, and the two are
     * kept separate because the reference behaves both ways at once.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("all four gates run, so a submission faulting all four is answered with all four")
    void allFourGatesRunSoEveryFaultIsReported() {
        CardUpdateRequest allFourWrong =
                new CardUpdateRequest("Britney Waters 2", "X", "13", "2100", STORED_VERSION);

        List<ApiError.FieldError> faults = this.service.validateAttributes(allFourWrong);

        // Trade-offs: the whole set and its order are asserted rather than its size or its first
        // entry. A chain that stopped at its first fault satisfies any weaker check, so the weaker
        // check would report success against the one divergence this case exists to catch.
        assertThat(faults).extracting(ApiError.FieldError::field).containsExactly(
                CardUpdateService.FIELD_EMBOSSED_NAME,
                CardUpdateService.FIELD_ACTIVE_STATUS,
                CardUpdateService.FIELD_EXPIRATION_MONTH,
                CardUpdateService.FIELD_EXPIRATION_YEAR);
        assertThat(faults).allMatch(ApiError.FieldError::isError);
        assertThat(faults).extracting(ApiError.FieldError::message).containsExactly(
                CardUpdateService.MESSAGE_NAME_MUST_BE_ALPHA,
                CardUpdateService.MESSAGE_STATUS_MUST_BE_YES_NO,
                CardUpdateService.MESSAGE_EXPIRY_MONTH_NOT_VALID,
                CardUpdateService.MESSAGE_EXPIRY_YEAR_NOT_VALID);
    }

    /**
     * Asserts that an acceptable attribute contributes no entry, so the set names only what faulted.
     *
     * <p>Assumptions: each gate sets its own flag valid on its acceptable path -- the name at
     * {@code app/cbl/COCRDUPC.cbl:839}, the status at {@code :864}, the month at {@code :899} and the
     * year at {@code :935} -- and only an error state reaches a per-field entry. Accumulation would be
     * indistinguishable from a set that always held four entries if this were not asserted
     * separately.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an acceptable attribute contributes no entry, so only the faulted ones are named")
    void anAcceptableAttributeContributesNoEntry() {
        CardUpdateRequest twoWrong =
                new CardUpdateRequest("Layla Ullrich", "Y", "13", "2100", STORED_VERSION);

        List<ApiError.FieldError> faults = this.service.validateAttributes(twoWrong);

        assertThat(faults).extracting(ApiError.FieldError::field).containsExactly(
                CardUpdateService.FIELD_EXPIRATION_MONTH,
                CardUpdateService.FIELD_EXPIRATION_YEAR);
    }

    /**
     * Asserts that an entirely acceptable submission produces an empty set.
     *
     * <p>Assumptions: the dispatcher reaches {@code SET CCUP-CHANGES-OK-NOT-CONFIRMED} at
     * {@code app/cbl/COCRDUPC.cbl:713} only through the {@code ELSE} of the
     * {@code IF INPUT-ERROR} at {@code :710}, so an acceptable submission is precisely one that
     * faulted nowhere. The returned set is also unmodifiable, which stops a caller from editing the
     * evidence it was handed.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an entirely acceptable submission faults nowhere and hands back a sealed set")
    void anAcceptableSubmissionFaultsNowhere() {
        CardUpdateRequest acceptable = new CardUpdateRequest("Layla Ullrich", "Y",
                NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION);

        List<ApiError.FieldError> faults = this.service.validateAttributes(acceptable);

        assertThat(faults).isEmpty();
        assertThatThrownBy(() -> faults.add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Asserts that the one summary sentence names only the attribute that faulted first.
     *
     * <p>Assumptions: the reference reports through a single {@code WS-RETURN-MSG PIC X(75)} at
     * {@code app/cbl/COCRDUPC.cbl:173} and guards every write to it with
     * {@code IF WS-RETURN-MSG-OFF}, declared at {@code :174} and tested at {@code :816} and
     * {@code :833} in the name gate, {@code :855} and {@code :868} in the status gate, {@code :888}
     * and {@code :903} in the month gate and {@code :921} and {@code :939} in the year gate. That
     * guard makes the sentence first-error-wins even while the four flags accumulate, so the two
     * behaviours coexist and this case asserts the one its companion does not.</p>
     *
     * <p>Assumptions: the same submission that yields four per-field entries yields exactly one
     * sentence, and the three sentences belonging to the later gates must be absent from it. Naming
     * their absence is what distinguishes a first-error-wins sentence from a concatenation of
     * all four.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("the one summary sentence names only the attribute that faulted first")
    void theSummarySentenceCarriesOnlyTheFirstFaultedAttribute() throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        Card stored = cardFrom(record);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(stored));
        CardUpdateRequest allFourWrong =
                new CardUpdateRequest("Britney Waters 2", "X", "13", "2100", STORED_VERSION);

        assertThatThrownBy(() -> this.service.update(selectorFor(record), allFourWrong))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(CardUpdateService.MESSAGE_NAME_MUST_BE_ALPHA)
                .hasMessageNotContaining(CardUpdateService.MESSAGE_STATUS_MUST_BE_YES_NO)
                .hasMessageNotContaining(CardUpdateService.MESSAGE_EXPIRY_MONTH_NOT_VALID)
                .hasMessageNotContaining(CardUpdateService.MESSAGE_EXPIRY_YEAR_NOT_VALID);
    }

    /**
     * Asserts that the summary sentence advances to the next gate once an earlier one is acceptable.
     *
     * <p>Assumptions: the gate order the sentence follows is the dispatcher's own performing order at
     * {@code app/cbl/COCRDUPC.cbl:698-708} -- name, then status, then month, then year. Asserting the
     * name sentence alone would pass against an implementation that always answered with the name
     * sentence, so this case walks the precedence forward by making each earlier attribute acceptable
     * in turn.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("the summary sentence advances through the gate order as earlier gates pass")
    void theSummarySentenceFollowsTheReferenceGateOrder() throws IOException {
        assertThat(refusalSentenceFor(new CardUpdateRequest(
                "Layla Ullrich", "X", "13", "2100", STORED_VERSION)))
                .isEqualTo(CardUpdateService.MESSAGE_STATUS_MUST_BE_YES_NO);
        assertThat(refusalSentenceFor(new CardUpdateRequest(
                "Layla Ullrich", "Y", "13", "2100", STORED_VERSION)))
                .isEqualTo(CardUpdateService.MESSAGE_EXPIRY_MONTH_NOT_VALID);
        assertThat(refusalSentenceFor(new CardUpdateRequest(
                "Layla Ullrich", "Y", NEUTRAL_MONTH, "2100", STORED_VERSION)))
                .isEqualTo(CardUpdateService.MESSAGE_EXPIRY_YEAR_NOT_VALID);
    }

    /**
     * Asserts that a refused submission is never written, however many attributes faulted.
     *
     * <p>Assumptions: the reference reaches its write paragraph only from the confirm arm at
     * {@code app/cbl/COCRDUPC.cbl:988-991}, whose guard requires
     * {@code CCUP-CHANGES-OK-NOT-CONFIRMED}, the state {@code :713} sets only when nothing faulted.
     * A refusal therefore cannot reach a write, and the mocked store is the place that property is
     * observable.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("a refused submission never reaches the store")
    void aRefusedSubmissionIsNeverWritten() throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(cardFrom(record)));

        assertThatThrownBy(() -> this.service.update(selectorFor(record),
                new CardUpdateRequest("Britney Waters 2", "X", "13", "2100", STORED_VERSION)))
                .isInstanceOf(ClientInputException.class);

        verify(this.cards, never()).saveAndFlush(any(Card.class));
    }

    /**
     * Asserts that the four reported identities are exactly the editable components of the request.
     *
     * <p>Assumptions: a per-field entry is only useful if a caller can bind it back to the member it
     * submitted, so the identities have to be spelled as the record's components are. Tying the two
     * together here means renaming a component without renaming its identity fails this case rather
     * than silently handing clients an identity that matches nothing they sent.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the four reported identities are exactly the editable request components")
    void theReportedIdentitiesMatchTheEditableRequestComponents() {
        List<String> components = new ArrayList<>();
        for (RecordComponent component : CardUpdateRequest.class.getRecordComponents()) {
            components.add(component.getName());
        }

        // Assumptions: the concurrency token is listed alongside the four attributes because
        // containsExactly admits no unlisted component, which is what makes this case fail if a fifth
        // editable attribute is ever added without an identity to report it under.
        assertThat(components).containsExactly(
                CardUpdateService.FIELD_EMBOSSED_NAME,
                CardUpdateService.FIELD_ACTIVE_STATUS,
                CardUpdateService.FIELD_EXPIRATION_MONTH,
                CardUpdateService.FIELD_EXPIRATION_YEAR,
                GlobalExceptionHandler.FIELD_VERSION);
    }

    /**
     * Asserts that each editable attribute can reach all three of the reference flag states.
     *
     * <p>Assumptions: the reference declares six flags and each is a triad -- {@code PIC X(1)} with
     * {@code -NOT-OK VALUE '0'}, {@code -ISVALID VALUE '1'} and {@code -BLANK VALUE ' '} -- across
     * {@code app/cbl/COCRDUPC.cbl:57-80}. Four of the six govern the editable attributes and each of
     * their three states has to remain reachable, because the blank state is what carries the screen
     * marker and collapsing it into the unacceptable state would lose that distinction
     * silently.</p>
     *
     * <p>Assumptions: the two remaining triads, {@code WS-EDIT-ACCT-FLAG} at {@code :57-60} and
     * {@code WS-EDIT-CARD-FLAG} at {@code :61-64}, governed the two search keys and do not appear
     * here. They are asserted by their own case, because in the target they collapse into the one
     * sealed selector.</p>
     *
     * @param field the response-field identity under test, one of the four editable attributes
     * @param blankValue a submitted value the gate must class blank
     * @param unacceptableValue a submitted value the gate must class unacceptable but not blank
     * @param acceptableValue a submitted value the gate must accept
     */
    @ParameterizedTest
    @CsvSource({
        "embossedName,     '  ',  'Britney Waters 2', 'Layla Ullrich'",
        "activeStatus,     ' ',   'X',                'Y'",
        "expirationMonth,  '00',  '13',               '07'",
        "expirationYear,   '0000','2100',             '2030'",
    })
    @DisplayName("each editable attribute reaches all three reference flag states")
    void everyEditableAttributeReachesAllThreeFlagStates(String field, String blankValue,
            String unacceptableValue, String acceptableValue) {

        assertThat(stateOf(field, blankValue)).isEqualTo(FieldValidationFlag.BLANK);
        assertThat(stateOf(field, unacceptableValue)).isEqualTo(FieldValidationFlag.NOT_OK);
        assertThat(stateOf(field, acceptableValue)).isNull();
    }

    /**
     * Asserts that the two search-key triads surface as the single selector identity.
     *
     * <p>Assumptions: the reference validated two separate keys, an eleven-digit account through
     * {@code 1210-EDIT-ACCOUNT} at {@code app/cbl/COCRDUPC.cbl:721} and a sixteen-digit card through
     * {@code 1220-EDIT-CARD} at {@code :762}, each with its own triad. In the target the card is named
     * by one sealed selector and the account takes no part in the lookup at all, so the two triads
     * collapse into one identity. Six reference triads therefore surface as five identities, four
     * attributes plus this one, and that arithmetic is stated here rather than left for a reader to
     * work out from the absence of an account field.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the two search-key triads collapse into the one selector identity")
    void theTwoSearchKeyTriadsCollapseIntoTheSelectorIdentity() {
        CardUpdateRequest acceptable = new CardUpdateRequest("Layla Ullrich", "Y",
                NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION);

        assertThatThrownBy(() -> this.service.update("forged", acceptable))
                .isInstanceOf(ClientInputException.class)
                .satisfies(failure -> assertThat(((ClientInputException) failure).fields())
                        .containsExactly(CardMapper.SELECTOR_FIELD));

        assertThat(this.service.validateAttributes(acceptable)).isEmpty();
    }

    /**
     * Asserts that both inclusive expiry-month bounds are accepted.
     *
     * <p>Assumptions: the domain is declared as {@code 88 VALID-MONTH VALUES 1 THRU 12} at
     * {@code app/cbl/COCRDUPC.cbl:95}, over {@code CARD-MONTH-CHECK PIC X(2)} at {@code :92} redefined
     * as {@code PIC 9(2)} at {@code :93-94}. {@code THRU} is inclusive at both ends, so both 1 and 12
     * are inside it and a case asserting only a value in the middle would not evidence that.</p>
     *
     * <p>Assumptions: the two accepted months are read out of the boundary corpus rather than written
     * here, and that corpus stores them as whole calendar dates. That matters because it makes the
     * acceptance attributable to the edit rule alone: the stored date would parse either way.</p>
     *
     * @param storedMonth the two-character month read from the boundary corpus, {@code 01} or
     *     {@code 12}
     * @throws IOException if the boundary corpus cannot be read from the test classpath
     */
    @ParameterizedTest
    @ValueSource(strings = {"01", "12"})
    @DisplayName("both inclusive expiry-month bounds are accepted")
    void bothInclusiveExpiryMonthBoundsAreAccepted(String storedMonth) throws IOException {
        assertThat(monthsStoredIn(BOUNDARY_EXPIRY_RESOURCE)).contains(storedMonth);

        List<ApiError.FieldError> faults = this.service.validateAttributes(new CardUpdateRequest(
                "Layla Ullrich", "Y", storedMonth, NEUTRAL_YEAR, STORED_VERSION));

        assertThat(faults).isEmpty();
    }

    /**
     * Asserts that a month either side of the inclusive range is refused, each in its own state.
     *
     * <p>Assumptions: the two rejections do not arrive through the same branch, and flattening them
     * would misdescribe the reference. {@code 1250-EDIT-EXPIRY-MON} tests
     * {@code EQUAL ZEROS} at {@code app/cbl/COCRDUPC.cbl:885} before it tests the range, so
     * {@code 00} is classed blank at {@code :887} and never reaches the range test at {@code :898},
     * while {@code 13} reaches it and is classed unacceptable at {@code :902}. Both refuse the
     * submission and both carry the same sentence, because {@code CARD-EXPIRY-MONTH-NOT-VALID} is set
     * from both branches, at {@code :889} and at {@code :904}.</p>
     *
     * @param month the two-character month under test, just outside one end of the range
     * @param expectedState the reference flag state that month must reach
     * @throws IOException if the out-of-range corpus cannot be read from the test classpath
     */
    @ParameterizedTest
    @CsvSource({"00, BLANK", "13, NOT_OK"})
    @DisplayName("a month either side of the inclusive range is refused, each in its own state")
    void aMonthOutsideTheInclusiveRangeIsRefused(String month, FieldValidationFlag expectedState)
            throws IOException {

        assertThat(monthsStoredIn(MONTH_OUT_OF_RANGE_RESOURCE)).contains(month);

        List<ApiError.FieldError> faults = this.service.validateAttributes(new CardUpdateRequest(
                "Layla Ullrich", "Y", month, NEUTRAL_YEAR, STORED_VERSION));

        // Assumptions: the state is asserted per value rather than merely the refusal, because the two
        // values reach the refusal through different branches and only the blank one carries the
        // screen marker. Asserting the refusal alone would let the two states be collapsed.
        assertThat(faults).singleElement().satisfies(fault -> {
            assertThat(fault.field()).isEqualTo(CardUpdateService.FIELD_EXPIRATION_MONTH);
            assertThat(fault.state()).isEqualTo(expectedState);
            assertThat(fault.message()).isEqualTo(CardUpdateService.MESSAGE_EXPIRY_MONTH_NOT_VALID);
        });
    }

    /**
     * Asserts that both inclusive expiry-year bounds are accepted.
     *
     * <p>Assumptions: the domain is declared as {@code 88 VALID-YEAR VALUES 1950 THRU 2099} at
     * {@code app/cbl/COCRDUPC.cbl:99}, over {@code CARD-YEAR-CHECK PIC X(4)} at {@code :96} redefined
     * as {@code PIC 9(4)} at {@code :97-98}, and {@code THRU} includes both ends. The year gate is
     * also the one structural outlier among the four: it sets its unacceptable flag at {@code :930},
     * after its blank branch at {@code :916-925}, where the other three set theirs first, at
     * {@code :808}, {@code :847} and {@code :880}. The observable outcome is the same, which is why
     * this case asserts the outcome and not the ordering.</p>
     *
     * @param storedYear the four-character year read from the boundary corpus, {@code 1950} or
     *     {@code 2099}
     * @throws IOException if the boundary corpus cannot be read from the test classpath
     */
    @ParameterizedTest
    @ValueSource(strings = {"1950", "2099"})
    @DisplayName("both inclusive expiry-year bounds are accepted")
    void bothInclusiveExpiryYearBoundsAreAccepted(String storedYear) throws IOException {
        assertThat(yearsStoredIn(BOUNDARY_EXPIRY_RESOURCE)).contains(storedYear);

        List<ApiError.FieldError> faults = this.service.validateAttributes(new CardUpdateRequest(
                "Layla Ullrich", "Y", NEUTRAL_MONTH, storedYear, STORED_VERSION));

        assertThat(faults).isEmpty();
    }

    /**
     * Asserts that a year either side of the inclusive range is refused as unacceptable.
     *
     * <p>Assumptions: neither {@code 1949} nor {@code 2100} is a run of zeros, so both pass the blank
     * branch at {@code app/cbl/COCRDUPC.cbl:916-925} and both reach the range test at {@code :934} to
     * be refused at {@code :938}. Unlike the month pair, both land in the same state, and stating that
     * asymmetry here is what keeps the two cases from looking like copies of each other.</p>
     *
     * <p>Assumptions: the corpus stores these two years as whole calendar dates, {@code 1949-06-15}
     * and {@code 2100-06-15}, so the refusal is attributable to the edit rule and not to a date that
     * could not be parsed.</p>
     *
     * @param year the four-character year under test, just outside one end of the range
     * @throws IOException if the out-of-range corpus cannot be read from the test classpath
     */
    @ParameterizedTest
    @ValueSource(strings = {"1949", "2100"})
    @DisplayName("a year either side of the inclusive range is refused as unacceptable")
    void aYearOutsideTheInclusiveRangeIsRefused(String year) throws IOException {
        assertThat(yearsStoredIn(YEAR_OUT_OF_RANGE_RESOURCE)).contains(year);
        // Assumptions: the date is parsed here to establish that the calendar admits it, which is what
        // attributes the refusal to the edit rule alone. Without this line a reader could not tell the
        // rule from a date the target simply could not represent.
        assertThat(LocalDate.parse(year + "-06-15")).isNotNull();

        List<ApiError.FieldError> faults = this.service.validateAttributes(new CardUpdateRequest(
                "Layla Ullrich", "Y", NEUTRAL_MONTH, year, STORED_VERSION));

        assertThat(faults).singleElement().satisfies(fault -> {
            assertThat(fault.field()).isEqualTo(CardUpdateService.FIELD_EXPIRATION_YEAR);
            assertThat(fault.state()).isEqualTo(FieldValidationFlag.NOT_OK);
            assertThat(fault.message()).isEqualTo(CardUpdateService.MESSAGE_EXPIRY_YEAR_NOT_VALID);
        });
    }

    /**
     * Asserts that the active status admits exactly the two reference values.
     *
     * <p>Assumptions: the domain is {@code 88 FLG-YES-NO-VALID VALUES 'Y', 'N'} at
     * {@code app/cbl/COCRDUPC.cbl:91}, over {@code FLG-YES-NO-CHECK PIC X(1)} at {@code :89}. Both
     * accepted values are drawn from the two single-record controls rather than written here, so the
     * pair asserted is the pair the corpus actually stores.</p>
     *
     * @param storedStatus the one-character status under test
     * @param acceptable whether the reference domain admits that status
     * @throws IOException if either single-record control cannot be read from the test classpath
     */
    @ParameterizedTest
    @CsvSource({"Y, true", "N, true", "X, false"})
    @DisplayName("the active status admits exactly Y and N")
    void theActiveStatusAdmitsExactlyTheTwoReferenceValues(String storedStatus, boolean acceptable)
            throws IOException {

        // Assumptions: the three statuses are read out of their corpora first, so the pair this case
        // calls acceptable is the pair the corpora actually store rather than a pair restated here.
        assertThat(activeStatusOf(onlyRecordOf(VALID_ACTIVE_RESOURCE))).isEqualTo("Y");
        assertThat(activeStatusOf(onlyRecordOf(VALID_INACTIVE_RESOURCE))).isEqualTo("N");
        assertThat(activeStatusOf(onlyRecordOf(STATUS_OUT_OF_DOMAIN_RESOURCE))).isEqualTo("X");

        List<ApiError.FieldError> faults = this.service.validateAttributes(new CardUpdateRequest(
                "Layla Ullrich", storedStatus, NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION));

        if (acceptable) {
            assertThat(faults).isEmpty();
        } else {
            assertThat(faults).singleElement().satisfies(fault -> {
                assertThat(fault.field()).isEqualTo(CardUpdateService.FIELD_ACTIVE_STATUS);
                assertThat(fault.message())
                        .isEqualTo(CardUpdateService.MESSAGE_STATUS_MUST_BE_YES_NO);
            });
        }
    }

    /**
     * Asserts that a name of fifty ASCII zeros is classed blank rather than non-alphabetic.
     *
     * <p>Assumptions: this is counter-intuitive and load-bearing, so it is asserted on its own.
     * {@code 1230-EDIT-NAME} puts its blank branch FIRST, at {@code app/cbl/COCRDUPC.cbl:811-820},
     * and that branch tests {@code EQUAL LOW-VALUES} at {@code :811}, {@code EQUAL SPACES} at
     * {@code :812} and {@code EQUAL ZEROS} at {@code :813}. A run of ASCII zeros satisfies the third,
     * so it leaves at {@code :819} before the alphabetic test at {@code :828} is ever reached. Reading
     * the gate as "digits are non-alphabetic" would put this value in the wrong state and give it the
     * wrong sentence and the wrong screen marker.</p>
     *
     * <p>Assumptions: the corpus deliberately carries no low-values variant of this record, because
     * embedded null bytes would defeat the byte verification the fixtures README prescribes. The two
     * reachable blank forms, spaces and zeros, are both present and both asserted.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the blank-name corpus cannot be read from the test classpath
     */
    @Test
    @DisplayName("a name of fifty ASCII zeros is blank, not non-alphabetic")
    void fiftyAsciiZerosAreBlankRatherThanNonAlphabetic() throws IOException {
        List<String> records = recordsFrom(NAME_BLANK_RESOURCE);
        String spaces = embossedNameOf(records.get(0));
        String zeros = embossedNameOf(records.get(1));
        assertThat(spaces).isBlank().hasSize(EMBOSSED_NAME_TO - EMBOSSED_NAME_FROM);
        assertThat(zeros).isEqualTo("0".repeat(EMBOSSED_NAME_TO - EMBOSSED_NAME_FROM));

        for (String blankForm : List.of(spaces, zeros)) {
            assertThat(this.service.validateAttributes(new CardUpdateRequest(blankForm, "Y",
                    NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION)))
                    .singleElement().satisfies(fault -> {
                        assertThat(fault.field())
                                .isEqualTo(CardUpdateService.FIELD_EMBOSSED_NAME);
                        assertThat(fault.state()).isEqualTo(FieldValidationFlag.BLANK);
                        assertThat(fault.message())
                                .isEqualTo(CardUpdateService.MESSAGE_NAME_NOT_PROVIDED);
                    });
        }
    }

    /**
     * Asserts that punctuation and digits inside a name make it unacceptable rather than blank.
     *
     * <p>Assumptions: past the blank branch the gate copies the name into
     * {@code CARD-NAME-CHECK PIC X(50)} at {@code app/cbl/COCRDUPC.cbl:823}, converts every alphabetic
     * character to a space at {@code :824-826}, and accepts only if nothing survives trimming at
     * {@code :828}. Anything that is neither a letter nor a space therefore survives and the name is
     * refused at {@code :832} with its own sentence at {@code :834}. The name gate is the only one of
     * the four that carries two distinct sentences; the status, month and year gates each reuse a
     * single sentence across both of their branches.</p>
     *
     * <p>Assumptions: the two records read here carry an apostrophe and a digit, which are two
     * different character classes reaching the same outcome through the same test.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the non-alphabetic corpus cannot be read from the test classpath
     */
    @Test
    @DisplayName("punctuation and digits inside a name make it unacceptable, not blank")
    void punctuationAndDigitsMakeANameUnacceptable() throws IOException {
        List<String> records = recordsFrom(NAME_NON_ALPHA_RESOURCE);
        assertThat(trimmedNameOf(records.get(0))).contains("'");
        assertThat(trimmedNameOf(records.get(1))).contains("2");

        for (String record : records) {
            assertThat(this.service.validateAttributes(new CardUpdateRequest(
                    embossedNameOf(record), "Y", NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION)))
                    .singleElement().satisfies(fault -> {
                        assertThat(fault.field())
                                .isEqualTo(CardUpdateService.FIELD_EMBOSSED_NAME);
                        assertThat(fault.state()).isEqualTo(FieldValidationFlag.NOT_OK);
                        assertThat(fault.message())
                                .isEqualTo(CardUpdateService.MESSAGE_NAME_MUST_BE_ALPHA);
                    });
        }
    }

    /**
     * Asserts that any character which is neither a letter nor a space makes a name unacceptable.
     *
     * <p>Assumptions: the accepted alphabet is the literal {@code LIT-ALL-ALPHA-FROM PIC X(52)} at
     * {@code app/cbl/COCRDUPC.cbl:255}, whose value at {@code :257} is the twenty-six upper-case
     * letters followed by the twenty-six lower-case ones. Measured, it is fifty-two characters long
     * and contains no space, which is exactly why a space survives the conversion and is then removed
     * by the trim while a digit or a symbol is not. The corpus supplies two members of that complement
     * and this case walks several more classes of it, so the rule is asserted as a character class
     * rather than as two examples.</p>
     *
     * <p>Assumptions: the base name is taken from the corpus and one character is appended to it, so
     * the acceptable part of the value is still the corpus's and only the appended character is under
     * test.</p>
     *
     * @param offending a single character drawn from outside the accepted alphabet
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @ParameterizedTest
    @ValueSource(strings = {"1", "0", "-", ".", "'", "@", "/", "_", ",", "&"})
    @DisplayName("any character outside letters and space makes a name unacceptable")
    void anyCharacterOutsideLettersAndSpaceIsRefused(String offending) throws IOException {
        String base = trimmedNameOf(onlyRecordOf(VALID_ACTIVE_RESOURCE));

        // Alternatives Considered: writing a whole unacceptable name as a literal for each character.
        // Rejected because the acceptable part would then be a second copy of the corpus value that
        // could drift from it, and appending one character keeps the varied thing to exactly the
        // character under test.
        List<ApiError.FieldError> faults = this.service.validateAttributes(new CardUpdateRequest(
                base + offending, "Y", NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION));

        assertThat(faults).singleElement().satisfies(fault -> {
            assertThat(fault.field()).isEqualTo(CardUpdateService.FIELD_EMBOSSED_NAME);
            assertThat(fault.state()).isEqualTo(FieldValidationFlag.NOT_OK);
            assertThat(fault.message()).isEqualTo(CardUpdateService.MESSAGE_NAME_MUST_BE_ALPHA);
        });
    }

    /**
     * Asserts that a name carrying internal spaces is accepted.
     *
     * <p>Assumptions: this follows mechanically from the alphabet literal at
     * {@code app/cbl/COCRDUPC.cbl:257} holding no space. An unconverted space survives the
     * {@code INSPECT} at {@code :824-826} but is then removed by
     * {@code FUNCTION TRIM} inside the test at {@code :828}, so the length reaches zero and the name is
     * accepted at {@code :839}. The corpus name is title-cased with one internal space, so it exercises
     * this without a purpose-built value.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("a name carrying internal spaces is accepted")
    void aNameCarryingInternalSpacesIsAccepted() throws IOException {
        String storedName = embossedNameOf(onlyRecordOf(VALID_ACTIVE_RESOURCE));
        assertThat(trimmedNameOf(onlyRecordOf(VALID_ACTIVE_RESOURCE))).contains(" ");

        assertThat(this.service.validateAttributes(new CardUpdateRequest(storedName, "Y",
                NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION))).isEmpty();
    }

    /**
     * Asserts that only the blank state carries the screen marker, while both states are errors.
     *
     * <p>Assumptions: the reference renders the two error states differently, and this is the
     * discriminator between the two name corpora. At {@code app/cbl/COCRDUPC.cbl:1263-1266} an
     * unacceptable name moves a highlight attribute and nothing else. At {@code :1268-1272} a blank
     * name moves a literal asterisk into the field AND the same highlight attribute. The target
     * carries that through {@link FieldValidationFlag#screenMarker()}, which yields the marker for a
     * blank attribute and an empty string otherwise, so a renderer reproduces the split without this
     * service formatting anything.</p>
     *
     * <p>Assumptions: this OUTBOUND asterisk is a different mechanism from the inbound one the
     * selection program folds to low values, and the two must not be conflated. The reference writes
     * this one itself to mark a field that came back empty; the inbound one was typed by an operator
     * to mean "clear this". Same character, opposite direction.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if either name corpus cannot be read from the test classpath
     */
    @Test
    @DisplayName("only the blank state carries the screen marker, though both states are errors")
    void onlyTheBlankStateCarriesTheScreenMarker() throws IOException {
        String blankName = embossedNameOf(recordsFrom(NAME_BLANK_RESOURCE).get(1));
        String unacceptableName = embossedNameOf(recordsFrom(NAME_NON_ALPHA_RESOURCE).get(0));

        ApiError.FieldError blankFault = soleFaultFor(blankName);
        ApiError.FieldError unacceptableFault = soleFaultFor(unacceptableName);

        assertThat(blankFault.state()).isEqualTo(FieldValidationFlag.BLANK);
        assertThat(blankFault.screenMarker()).isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(unacceptableFault.state()).isEqualTo(FieldValidationFlag.NOT_OK);
        assertThat(unacceptableFault.screenMarker()).isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
        assertThat(blankFault.isError()).isTrue();
        assertThat(unacceptableFault.isError()).isTrue();
    }

    /**
     * Asserts that an accepted change is written exactly once and answered from the saved row.
     *
     * <p>Assumptions: the reference commits in exactly one place. Searching the whole program for
     * {@code SYNCPOINT} returns a single hit, the verb at {@code app/cbl/COCRDUPC.cbl:470}, and it
     * issues one {@code REWRITE}, at {@code :1477-1483} with the verb itself on {@code :1478}. One
     * accepted submission is therefore one write, and a second write would be a divergence rather than
     * an optimisation. The target declares that boundary once, on
     * {@link CardUpdateService#update(String, CardUpdateRequest)}, and the relational store keeps
     * it -- a platform-capability difference from a file resource defined
     * {@code RECOVERY(NONE)} and {@code JOURNAL(NO)} at lines 31 and 33 of
     * {@code app/csd/CARDDEMO.CSD}, where durability was the program's own business.</p>
     *
     * <p>Assumptions: the answer is projected from the row handed back by the store rather than from
     * the request, so the token it carries is the one a following edit must echo. That is what lets a
     * caller edit twice in succession using only what it was returned.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("an accepted change is written exactly once and answered from the saved row")
    void anAcceptedChangeIsWrittenExactlyOnce() throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        Card stored = cardFrom(record);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(stored));
        when(this.cards.saveAndFlush(any(Card.class))).thenAnswer(call -> call.getArgument(0));

        CardDetail answered = this.service.update(selectorFor(record), new CardUpdateRequest(
                "Layla Ullrich", "N", NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION));

        // Assumptions: the count is pinned at one rather than left unbounded, because the reference
        // commits in exactly one place and a second write would be a divergence even if the row ended
        // up carrying the same values.
        ArgumentCaptor<Card> written = ArgumentCaptor.forClass(Card.class);
        verify(this.cards, times(1)).saveAndFlush(written.capture());
        assertThat(written.getValue().getActiveStatus()).isEqualTo("N");
        assertThat(answered.activeStatus()).isEqualTo("N");
        assertThat(answered.expirationDate())
                .startsWith(NEUTRAL_YEAR + "-" + NEUTRAL_MONTH + "-");
    }

    /**
     * Asserts that a submission echoing a token the row no longer carries is refused as a conflict.
     *
     * <p>Assumptions: the reference does this comparison by hand, and it has to, because a CICS
     * read-for-update lock was never held across an operator's thinking time. It snapshots the
     * pre-edit record into {@code CCUP-OLD-DETAILS} at {@code app/cbl/COCRDUPC.cbl:291-301}, mirrors
     * it with {@code CCUP-NEW-DETAILS} at {@code :303-313}, and {@code 9300-CHECK-CHANGE-IN-REC.} at
     * {@code :1498} compares six values at {@code :1503-1508} before any write. Finding a difference
     * it sets its own condition at {@code :1511}, refreshes the snapshot with six moves at
     * {@code :1512-1517} and abandons the write with a {@code GO TO} at {@code :1518}. The target
     * expresses the same intent through a row version the store maintains, so one token replaces the
     * field-by-field compare -- a platform-capability difference from a resource declared
     * {@code READINTEG(UNCOMMITTED)} at line 27 of {@code app/csd/CARDDEMO.CSD}.</p>
     *
     * <p>Assumptions: the reference re-displays the current record on this path, at {@code :997-998},
     * rather than reporting a distinct failure state. In the target the caller re-fetches after the
     * refusal, which is the documented structural divergence, and the version the row does hold
     * travels back so the caller knows what to echo next.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("a submission echoing a stale token is refused as a stale-version conflict")
    void aStaleTokenIsRefusedAsAStaleVersionConflict() throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        Card stored = cardFrom(record);
        assertThat(stored.getVersion()).isEqualTo(STORED_VERSION);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> this.service.update(selectorFor(record), new CardUpdateRequest(
                "Layla Ullrich", "N", NEUTRAL_MONTH, NEUTRAL_YEAR, STALE_VERSION)))
                .isInstanceOf(RecordConflictException.class)
                .satisfies(failure -> {
                    RecordConflictException conflict = (RecordConflictException) failure;
                    assertThat(conflict.kind())
                            .isEqualTo(RecordConflictException.Kind.STALE_VERSION);
                    assertThat(conflict.currentVersion()).isEqualTo((long) STORED_VERSION);
                });

        verify(this.cards, never()).saveAndFlush(any(Card.class));
    }

    /**
     * Asserts that the stale-version conflict renders as HTTP 409 carrying the reference sentence.
     *
     * <p>Assumptions: in the reference the outcome and the sentence are the SAME STORAGE.
     * {@code COULD-NOT-LOCK-FOR-UPDATE} at {@code app/cbl/COCRDUPC.cbl:205-206},
     * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} at {@code :207-208} and
     * {@code LOCKED-BUT-UPDATE-FAILED} at {@code :209-210} are message-valued conditions on the one
     * {@code WS-RETURN-MSG PIC X(75)} at {@code :173}, so setting the outcome and setting the sentence
     * were one act. The target splits that into two things, an exception carrying a kind and a status
     * chosen by the shared advice, plus a sentence constant. Both halves are therefore asserted, and
     * separately: the previous case owns the kind, and this one owns the status and the words.</p>
     *
     * <p>Assumptions: the sentence is carried character for character, which for this one means
     * {@code some one} as two words and no closing period. Both are properties of the reference
     * literal, and both are asserted explicitly because either would be an easy thing to tidy up
     * without noticing.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the stale-version conflict renders as 409 carrying the reference sentence")
    void theStaleVersionConflictRendersAsConflictWithTheReferenceSentence() {
        ResponseEntity<ApiError> rendered = this.advice.onRecordConflict(
                new RecordConflictException(RecordConflictException.Kind.STALE_VERSION,
                        (long) STORED_VERSION),
                requestAt(REQUEST_PATH));

        assertThat(rendered.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ApiError body = rendered.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(body.code()).isEqualTo(ApiError.CODE_CONFLICT);
        assertThat(body.message()).isEqualTo("Record changed by some one else. Please review");
        assertThat(body.message()).isEqualTo(CardUpdateService.MESSAGE_RECORD_CHANGED);
        // Assumptions: the two-word spelling and the absent closing period are asserted separately
        // from the equality above. Equality alone would still hold after someone rewrote both the
        // constant and this expectation together, whereas naming the shape records what is being
        // preserved and why the sentence reads the way it does.
        assertThat(body.message()).contains("some one").doesNotContain("someone");
        assertThat(body.message()).doesNotEndWith(".");
        assertThat(body.fieldErrors()).singleElement().satisfies(fault -> assertThat(fault.field())
                .isEqualTo(GlobalExceptionHandler.FIELD_VERSION));
    }

    /**
     * Asserts that the three reference failure outcomes stay three, each with its own sentence.
     *
     * <p>Assumptions: the confirm arm evaluates them as three separate arms at
     * {@code app/cbl/COCRDUPC.cbl:992-1001}: an unavailable lock becomes state {@code 'L'} at
     * {@code :993-994} and is declared at {@code :289}, a write that did not take effect becomes
     * {@code 'F'} at {@code :995-996} declared at {@code :290}, and a record changed underneath
     * re-displays at {@code :997-998}, with success falling to {@code 'C'} at {@code :999-1000}
     * declared at {@code :287}. They arise at three different points of the write path -- the lock
     * before the record is held, at {@code :1445-1448}; the change test before the write, at
     * {@code :1453-1457}; and the write result afterwards, at {@code :1488-1492} -- so they are
     * genuinely three conditions and not three names for one.</p>
     *
     * <p>Trade-offs: it would be less work to answer all three with a single conflict sentence, and a
     * caller would still know the write did not happen. Rejected because the reference distinguishes
     * them and an operator reading a log needs to know which of the three occurred: contention,
     * concurrent modification, and a store that refused are three different things to act on. The
     * grouping condition {@code CCUP-CHANGES-FAILED VALUES 'L', 'F'} at {@code :288} shows the
     * reference itself keeping the underlying two apart while still being able to speak of them
     * together.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the three reference failure outcomes keep three distinct sentences")
    void theThreeFailureOutcomesKeepThreeDistinctSentences() {
        // Assumptions: pairwise distinctness is asserted before any rendering, because that is the
        // property that would break first if the three outcomes were ever answered with one shared
        // sentence, and it breaks without depending on how the advice chooses between them.
        assertThat(List.of(CardUpdateService.MESSAGE_COULD_NOT_LOCK,
                        CardUpdateService.MESSAGE_RECORD_CHANGED,
                        CardUpdateService.MESSAGE_UPDATE_FAILED))
                .doesNotHaveDuplicates();

        assertThat(conflictSentenceFor(RecordConflictException.Kind.LOCK_UNAVAILABLE))
                .isEqualTo(CardUpdateService.MESSAGE_COULD_NOT_LOCK);
        assertThat(conflictSentenceFor(RecordConflictException.Kind.STALE_VERSION))
                .isEqualTo(CardUpdateService.MESSAGE_RECORD_CHANGED);
        assertThat(conflictSentenceFor(RecordConflictException.Kind.LOCK_UNAVAILABLE))
                .isNotEqualTo(conflictSentenceFor(RecordConflictException.Kind.STALE_VERSION));
    }

    /**
     * Asserts that a store refusing the write abandons the call rather than answering a detail.
     *
     * <p>Assumptions: this is the target form of the third outcome, the {@code 'F'} state the reference
     * sets at {@code app/cbl/COCRDUPC.cbl:1491} when its {@code REWRITE} did not return normally, as
     * tested at {@code :1488}. The reference had to inspect a response code because the resource gave
     * it no other signal; the target lets the store's own refusal propagate, which abandons the unit of
     * work the transactional boundary opened. What a caller must never receive on this path is a
     * detail, because that would report a change the store did not keep.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("a store refusing the write abandons the call instead of answering a detail")
    void aStoreRefusalAbandonsTheCall() throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(cardFrom(record)));
        when(this.cards.saveAndFlush(any(Card.class)))
                .thenThrow(new IllegalStateException(CardUpdateService.MESSAGE_UPDATE_FAILED));

        assertThatThrownBy(() -> this.service.update(selectorFor(record), new CardUpdateRequest(
                "Layla Ullrich", "N", NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(CardUpdateService.MESSAGE_UPDATE_FAILED);

        verify(this.cards, times(1)).saveAndFlush(any(Card.class));
    }

    /**
     * Asserts that an absent concurrency token is treated as stale rather than as consent.
     *
     * <p>Assumptions: the reference had no equivalent of an absent token, because its snapshot was
     * always populated -- {@code INITIALIZE} clears it and the read fills it, so the comparison at
     * {@code app/cbl/COCRDUPC.cbl:1503-1508} always had both sides. In the target a caller could omit
     * the member, and the only safe reading is refusal: treating an omission as agreement would let a
     * caller overwrite a row it never read, which is precisely the condition the reference snapshot
     * exists to prevent.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("an absent concurrency token is treated as stale, never as consent")
    void anAbsentTokenIsTreatedAsStale() throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(cardFrom(record)));

        assertThatThrownBy(() -> this.service.update(selectorFor(record), new CardUpdateRequest(
                "Layla Ullrich", "N", NEUTRAL_MONTH, NEUTRAL_YEAR, null)))
                .isInstanceOf(RecordConflictException.class);

        verify(this.cards, never()).saveAndFlush(any(Card.class));
    }

    /**
     * Asserts that the update contract carries no verification-value component at all.
     *
     * <p>Assumptions: the reference program mentions {@code CCUP-NEW-CVV-CD} on exactly two lines, its
     * declaration at {@code app/cbl/COCRDUPC.cbl:306} and the {@code MOVE} at {@code :1464} where it is
     * the source and not the target, and the group holding it is cleared by {@code INITIALIZE} at
     * {@code :586}. It is therefore written out of working storage that nothing ever populated, so an
     * update could not change it. The target states that structurally instead: the component simply
     * does not exist, so no request can carry one.</p>
     *
     * <p>Trade-offs: this is asserted by reflection over the record's components rather than by trying
     * to construct a request with one, which would not compile and so could not be a test at all. The
     * cost is a case that reads more like a contract check than a behaviour check; what it buys is that
     * adding such a component later fails here, where the reason is written down.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the update contract carries no verification-value component")
    void theUpdateContractCarriesNoVerificationValueComponent() {
        List<String> components = new ArrayList<>();
        for (RecordComponent component : CardUpdateRequest.class.getRecordComponents()) {
            components.add(component.getName().toLowerCase(Locale.ROOT));
        }

        assertThat(components).noneMatch(name -> name.contains("cvv"));
        assertThat(components).noneMatch(name -> name.contains("verification"));
        assertThat(components).noneMatch(name -> name.contains("day"));
        assertThat(components).noneMatch(name -> name.contains("accountid"));
        assertThat(components).noneMatch(name -> name.contains("confirm"));
    }

    /**
     * Asserts that an update leaves the stored enciphered verification value exactly as it was.
     *
     * <p>Assumptions: the contract omitting the component and the write leaving the column alone are
     * two different properties, and either could regress without the other, which is why this case
     * exists beside the contract one. The reference never makes the field a move target; the target
     * never sets it during an update, so the enciphered envelope a row arrived with is the one it keeps.
     * The identity of the value is asserted as well as its bytes, because a re-enciphered value with
     * equal plaintext would still be a change the reference does not make.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the positive control cannot be read from the test classpath
     */
    @Test
    @DisplayName("an update leaves the stored enciphered verification value exactly as it was")
    void theStoredVerificationValueIsUntouchedByAnUpdate() throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        Card stored = cardFrom(record);
        EncryptedCvv before = stored.getCvvEncrypted();
        assertThat(before).isNotNull();
        byte[] envelopeBefore = before.envelope();
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(stored));
        when(this.cards.saveAndFlush(any(Card.class))).thenAnswer(call -> call.getArgument(0));

        this.service.update(selectorFor(record), new CardUpdateRequest(
                "Layla Ullrich", "N", NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION));

        ArgumentCaptor<Card> written = ArgumentCaptor.forClass(Card.class);
        verify(this.cards).saveAndFlush(written.capture());
        // Assumptions: identity is asserted as well as the bytes. A re-enciphered value carrying the
        // same plaintext would compare equal byte for byte only by chance, and would still be a change
        // the reference never makes, so equality alone is the weaker of the two claims.
        assertThat(written.getValue().getCvvEncrypted()).isSameAs(before);
        assertThat(written.getValue().getCvvEncrypted().envelope()).isEqualTo(envelopeBefore);
    }

    /**
     * Asserts that the stored day of the month survives an update that changes month and year.
     *
     * <p>Assumptions: this is the documented divergence and the reason it is asserted across three
     * different stored days rather than one. The reference accepts a day from its screen, assembles it
     * into the stored date with the {@code STRING} at {@code app/cbl/COCRDUPC.cbl:1467-1474} whose day
     * operand is on {@code :1471}, moves one at {@code :675} and compares one at {@code :1507} -- yet
     * no {@code 1270-EDIT-EXPIRY-DAY} paragraph exists to validate it. The target's request carries no
     * day, so the stored one is carried forward and nothing new is imposed on it. The baseline does the
     * one thing, this migration does the other, and the difference is recorded rather than
     * smoothed over.</p>
     *
     * <p>Alternatives Considered: adding a calendar check on the resulting date, which would be the
     * natural instinct on seeing an unvalidated day. Rejected because the reference has no such rule
     * and inventing one here would refuse submissions the baseline accepts, which is a behavioural
     * change and not a tidying-up. No such check is added and none is asserted.</p>
     *
     * @param storedDay the two-character day the corpus stores for the record under test
     * @throws IOException if the day corpus cannot be read from the test classpath
     */
    @ParameterizedTest
    @ValueSource(strings = {"01", "15", "28"})
    @DisplayName("the stored day of the month survives an update that changes month and year")
    void theStoredDayOfMonthSurvivesAnUpdate(String storedDay) throws IOException {
        String record = recordWithStoredDay(storedDay);
        Card stored = cardFrom(record);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(stored));
        when(this.cards.saveAndFlush(any(Card.class))).thenAnswer(call -> call.getArgument(0));

        CardDetail answered = this.service.update(selectorFor(record), new CardUpdateRequest(
                trimmedNameOf(record), activeStatusOf(record), NEUTRAL_MONTH, NEUTRAL_YEAR,
                STORED_VERSION));

        // Assumptions: the whole date is compared against the parameter and the day alone is compared
        // back against the record. The first states what the answer should be, the second ties it to
        // the corpus, so reordering or re-authoring the corpus cannot leave a stale expectation
        // passing.
        assertThat(answered.expirationDate())
                .isEqualTo(NEUTRAL_YEAR + "-" + NEUTRAL_MONTH + "-" + storedDay);
        assertThat(answered.expirationDate().substring(EXPIRY_DAY_FROM, EXPIRY_DAY_TO))
                .isEqualTo(expirationDayOf(record));
    }

    /**
     * Asserts that moving a stored day into a shorter month is accepted rather than refused.
     *
     * <p>Assumptions: the corpus stores a day of 28, and February of a common year has exactly 28 days,
     * so this moves the largest stored day into the shortest month the calendar offers and the
     * submission still has to be accepted. It is the sharpest available statement that no calendar
     * rule was added: a validator invented for the target would be most likely to fire precisely
     * here.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     *
     * @throws IOException if the day corpus cannot be read from the test classpath
     */
    @Test
    @DisplayName("moving the stored day into the shortest month is accepted, not refused")
    void movingTheStoredDayIntoAShorterMonthIsAccepted() throws IOException {
        String record = recordWithStoredDay("28");
        Card stored = cardFrom(record);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(stored));
        when(this.cards.saveAndFlush(any(Card.class))).thenAnswer(call -> call.getArgument(0));

        CardDetail answered = this.service.update(selectorFor(record), new CardUpdateRequest(
                trimmedNameOf(record), activeStatusOf(record), "02", "2025", STORED_VERSION));

        assertThat(answered.expirationDate()).isEqualTo("2025-02-28");
    }

    /**
     * Asserts that every reference sentence this class relies on is carried character for character.
     *
     * <p>Assumptions: transformation rule T8 reproduces a user-visible string exactly, so a sentence
     * that reads oddly is still carried as it stands. Two of these are the reason the case exists.
     * {@code 88 PROMPT-FOR-CONFIRMATION} at {@code app/cbl/COCRDUPC.cbl:166-167} has no space after
     * its period, and {@code 88 NO-CHANGES-DETECTED} at {@code :187-188} ends with one. Either is the
     * kind of thing a later editor would helpfully repair, so both are pinned by their shape and not
     * only by equality.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every reference sentence relied on here is carried character for character")
    void everyReferenceSentenceIsCarriedCharacterForCharacter() {
        assertThat(CardUpdateService.MESSAGE_PROMPT_FOR_CONFIRMATION)
                .isEqualTo("Changes validated.Press F5 to save")
                .contains(".Press")
                .doesNotContain(". Press");
        assertThat(CardUpdateService.MESSAGE_NO_CHANGES_DETECTED)
                .isEqualTo("No change detected with respect to values fetched.")
                .endsWith(".");
        assertThat(CardUpdateService.MESSAGE_UPDATE_SUCCESS)
                .isEqualTo("Changes committed to database");
        assertThat(CardUpdateService.MESSAGE_INFORM_FAILURE)
                .isEqualTo("Changes unsuccessful. Please try again");
        assertThat(CardUpdateService.MESSAGE_NAME_NOT_PROVIDED)
                .isEqualTo("Card name not provided");
        assertThat(CardUpdateService.MESSAGE_NAME_MUST_BE_ALPHA)
                .isEqualTo("Card name can only contain alphabets and spaces");
        assertThat(CardUpdateService.MESSAGE_NO_INPUT_RECEIVED)
                .isEqualTo("No input received");
        assertThat(CardUpdateService.MESSAGE_STATUS_MUST_BE_YES_NO)
                .isEqualTo("Card Active Status must be Y or N");
        assertThat(CardUpdateService.MESSAGE_EXPIRY_MONTH_NOT_VALID)
                .isEqualTo("Card expiry month must be between 1 and 12");
        assertThat(CardUpdateService.MESSAGE_EXPIRY_YEAR_NOT_VALID)
                .isEqualTo("Invalid card expiry year");
        assertThat(CardViewService.MESSAGE_CARD_NOT_FOUND)
                .isEqualTo("Did not find cards for this search condition");
        assertThat(CardUpdateService.MESSAGE_COULD_NOT_LOCK)
                .isEqualTo("Could not lock record for update");
        assertThat(CardUpdateService.MESSAGE_RECORD_CHANGED)
                .isEqualTo("Record changed by some one else. Please review");
        assertThat(CardUpdateService.MESSAGE_UPDATE_FAILED)
                .isEqualTo("Update of record failed");
    }

    /**
     * Asserts that every sentence relied on here fits the reference rendering width.
     *
     * <p>Assumptions: the reference sentences the refusals use all live in
     * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:173}, so seventy-five characters
     * is the width the whole set was authored against, and the target carries that number as
     * {@link ApiError#MESSAGE_RENDERING_WIDTH}. It is a rendering constraint rather than a validation
     * rule, so nothing rejects an over-long sentence at runtime -- which is exactly why a case here
     * asserts the set still fits.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every sentence relied on here fits the reference rendering width")
    void everySentenceFitsTheReferenceRenderingWidth() {
        List<String> sentences = List.of(
                CardUpdateService.MESSAGE_NAME_NOT_PROVIDED,
                CardUpdateService.MESSAGE_NAME_MUST_BE_ALPHA,
                CardUpdateService.MESSAGE_STATUS_MUST_BE_YES_NO,
                CardUpdateService.MESSAGE_EXPIRY_MONTH_NOT_VALID,
                CardUpdateService.MESSAGE_EXPIRY_YEAR_NOT_VALID,
                CardUpdateService.MESSAGE_NO_CHANGES_DETECTED,
                CardUpdateService.MESSAGE_NO_INPUT_RECEIVED,
                CardUpdateService.MESSAGE_COULD_NOT_LOCK,
                CardUpdateService.MESSAGE_RECORD_CHANGED,
                CardUpdateService.MESSAGE_UPDATE_FAILED,
                CardViewService.MESSAGE_CARD_NOT_FOUND);

        // Assumptions: the set is asserted non-empty before it is walked, because allSatisfy holds
        // trivially over an empty set and this case would then pass having measured nothing.
        assertThat(sentences).isNotEmpty().allSatisfy(sentence -> assertThat(sentence)
                .isNotBlank()
                .hasSizeLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH));
    }

    /**
     * Reads a corpus off the test classpath and splits it into its records.
     *
     * <p>Assumptions: the byte invariant is verified here rather than trusted, because every offset
     * below is meaningless if a corpus is misaligned and a misaligned corpus would otherwise surface as
     * a puzzling assertion failure somewhere far from its cause. The record length and the byte table
     * are owned by the fixtures README and are consumed here, never re-derived.</p>
     *
     * @param resource the classpath name of the corpus to read
     * @return the records the corpus holds, in the order it stores them, each without its terminator
     * @throws IOException if the corpus is absent from the test classpath or is not a whole number of
     *     lines of the declared length
     */
    private static List<String> recordsFrom(String resource) throws IOException {
        try (InputStream stream =
                CardUpdateServiceTest.class.getClassLoader().getResourceAsStream(resource)) {

            if (stream == null) {
                throw new IOException("corpus absent from the test classpath: " + resource);
            }

            byte[] bytes = stream.readAllBytes();
            // Assumptions: the invariant is verified rather than trusted, because every offset this
            // class reads is meaningless against a misaligned corpus and the resulting failure would
            // otherwise surface far from its cause.
            if (bytes.length % LINE_LENGTH != 0) {
                throw new IOException(resource + " measures " + bytes.length
                        + " bytes, which is not a whole number of " + LINE_LENGTH + "-byte lines");
            }

            List<String> records = new ArrayList<>(bytes.length / LINE_LENGTH);
            for (int offset = 0; offset < bytes.length; offset += LINE_LENGTH) {
                records.add(new String(bytes, offset, RECORD_LENGTH, StandardCharsets.US_ASCII));
            }
            return records;
        }
    }

    /**
     * Reads a single-record corpus and returns the one record it holds.
     *
     * @param resource the classpath name of a corpus expected to hold exactly one record
     * @return the record that corpus holds
     * @throws IOException if the corpus cannot be read or does not hold exactly one record
     */
    private static String onlyRecordOf(String resource) throws IOException {
        List<String> records = recordsFrom(resource);
        if (records.size() != 1) {
            throw new IOException(resource + " holds " + records.size()
                    + " records where exactly one was expected");
        }
        return records.get(0);
    }

    /**
     * Returns the {@code CARD-NUM} field of a record, positions 1 to 16.
     *
     * @param record the hundred-and-fifty-byte record to read
     * @return the sixteen-character stored card number
     */
    private static String cardNumberOf(String record) {
        return record.substring(CARD_NUM_FROM, CARD_NUM_TO);
    }

    /**
     * Returns the {@code CARD-ACCT-ID} field of a record, positions 17 to 27.
     *
     * @param record the hundred-and-fifty-byte record to read
     * @return the eleven digits of the stored account identifier
     */
    private static String accountIdOf(String record) {
        return record.substring(CARD_NUM_TO, ACCOUNT_ID_TO);
    }

    /**
     * Returns the {@code CARD-EMBOSSED-NAME} field of a record, positions 31 to 80.
     *
     * <p>Assumptions: the value is returned at its whole declared width, trailing padding included,
     * because that is what the record stores and what the reference compared. The no-change comparison
     * pads both sides to the same width, so padding cannot change its outcome.</p>
     *
     * @param record the hundred-and-fifty-byte record to read
     * @return the fifty-character stored name, padding included
     */
    private static String embossedNameOf(String record) {
        return record.substring(EMBOSSED_NAME_FROM, EMBOSSED_NAME_TO);
    }

    /**
     * Returns the stored name with its padding removed.
     *
     * @param record the hundred-and-fifty-byte record to read
     * @return the stored name without leading or trailing padding
     */
    private static String trimmedNameOf(String record) {
        return embossedNameOf(record).strip();
    }

    /**
     * Returns the {@code CARD-EXPIRAION-DATE} field of a record, positions 81 to 90.
     *
     * <p>Assumptions: the field is returned as text and is deliberately not parsed, because two of the
     * corpora store values no calendar admits and exist precisely to drive validation. Parsing here
     * would make those corpora unreadable.</p>
     *
     * @param record the hundred-and-fifty-byte record to read
     * @return the ten-character stored expiry as it stands in the record
     */
    private static String expirationDateOf(String record) {
        return record.substring(EXPIRATION_DATE_FROM, EXPIRATION_DATE_TO);
    }

    /**
     * Returns the year within a record's expiry field, which the reference reads as {@code (1:4)}.
     *
     * @param record the hundred-and-fifty-byte record to read
     * @return the four characters of the stored expiry year
     */
    private static String expirationYearOf(String record) {
        return expirationDateOf(record).substring(EXPIRY_YEAR_FROM, EXPIRY_YEAR_TO);
    }

    /**
     * Returns the month within a record's expiry field, which the reference reads as {@code (6:2)}.
     *
     * @param record the hundred-and-fifty-byte record to read
     * @return the two characters of the stored expiry month
     */
    private static String expirationMonthOf(String record) {
        return expirationDateOf(record).substring(EXPIRY_MONTH_FROM, EXPIRY_MONTH_TO);
    }

    /**
     * Returns the day within a record's expiry field, which the reference reads as {@code (9:2)}.
     *
     * @param record the hundred-and-fifty-byte record to read
     * @return the two characters of the stored expiry day
     */
    private static String expirationDayOf(String record) {
        return expirationDateOf(record).substring(EXPIRY_DAY_FROM, EXPIRY_DAY_TO);
    }

    /**
     * Returns the {@code CARD-ACTIVE-STATUS} field of a record, position 91.
     *
     * @param record the hundred-and-fifty-byte record to read
     * @return the one-character stored active status
     */
    private static String activeStatusOf(String record) {
        return record.substring(ACTIVE_STATUS_TO - 1, ACTIVE_STATUS_TO);
    }

    /**
     * Collects the stored expiry months of every record in a corpus.
     *
     * @param resource the classpath name of the corpus to read
     * @return the two-character month of each record, in corpus order
     * @throws IOException if the corpus cannot be read from the test classpath
     */
    private static List<String> monthsStoredIn(String resource) throws IOException {
        List<String> months = new ArrayList<>();
        for (String record : recordsFrom(resource)) {
            months.add(expirationMonthOf(record));
        }
        return months;
    }

    /**
     * Collects the stored expiry years of every record in a corpus.
     *
     * @param resource the classpath name of the corpus to read
     * @return the four-character year of each record, in corpus order
     * @throws IOException if the corpus cannot be read from the test classpath
     */
    private static List<String> yearsStoredIn(String resource) throws IOException {
        List<String> years = new ArrayList<>();
        for (String record : recordsFrom(resource)) {
            years.add(expirationYearOf(record));
        }
        return years;
    }

    /**
     * Finds the record in the day corpus whose stored day is the one asked for.
     *
     * <p>Assumptions: the day is looked up rather than indexed by position, so reordering the corpus
     * cannot silently point a case at a different record than its parameter names.</p>
     *
     * @param storedDay the two-character day to look for
     * @return the record storing that day
     * @throws IOException if the corpus cannot be read or holds no record with that day
     */
    private static String recordWithStoredDay(String storedDay) throws IOException {
        for (String record : recordsFrom(DAY_PRESERVED_RESOURCE)) {
            if (expirationDayOf(record).equals(storedDay)) {
                return record;
            }
        }
        throw new IOException(DAY_PRESERVED_RESOURCE + " holds no record whose stored day is "
                + storedDay);
    }

    /**
     * Builds an enciphered verification value for a stored row to carry.
     *
     * <p>Assumptions: the envelope is framed through the domain type's own wrapping entry point, so it
     * satisfies that type's shape check rather than merely resembling one. The three byte runs are
     * inert filler chosen only to meet the declared minimum lengths; nothing here is a key, and no case
     * deciphers it. What matters is that a row arrives holding a value whose identity can be compared
     * before and after a write.</p>
     *
     * @return an enciphered verification value in the shape the column accepts
     */
    private static EncryptedCvv storedVerificationValue() {
        // Alternatives Considered: handing the column a raw byte array of the right length. Rejected
        // because the domain type checks its framing, so an array that merely had the right size would
        // be refused at construction and the case would fail for a reason unrelated to its subject.
        byte[] dataKey = new byte[] {1, 2, 3, 4, 5, 6, 7, 8};
        byte[] initialisationVector = new byte[EncryptedCvv.INITIALISATION_VECTOR_LENGTH];
        byte[] ciphertext = new byte[EncryptedCvv.MIN_CIPHERTEXT_LENGTH];
        Arrays.fill(initialisationVector, (byte) 9);
        Arrays.fill(ciphertext, (byte) 7);
        return EncryptedCvv.wrap(dataKey, initialisationVector, ciphertext);
    }

    /**
     * Builds the stored row a corpus record describes.
     *
     * <p>Assumptions: only a record whose expiry is a whole calendar date can be turned into a row,
     * which is why the two corpora holding impossible dates are read for their fields alone. A
     * constructed row reports a concurrency token of zero, because the column is maintained by the
     * persistence provider and the type exposes no setter for it.</p>
     *
     * @param record the hundred-and-fifty-byte record to build from
     * @return the stored row that record describes, carrying an enciphered verification value
     */
    private static Card cardFrom(String record) {
        return new Card(cardNumberOf(record), Long.parseLong(accountIdOf(record)),
                storedVerificationValue(), embossedNameOf(record),
                LocalDate.parse(expirationDateOf(record)), activeStatusOf(record));
    }

    /**
     * Builds a submission that echoes back exactly what a record already stores.
     *
     * @param record the record whose stored values are to be echoed
     * @param version the concurrency token the submission should carry
     * @return a submission carrying the record's own four editable values
     */
    private static CardUpdateRequest echoOf(String record, int version) {
        return new CardUpdateRequest(embossedNameOf(record), activeStatusOf(record),
                expirationMonthOf(record), expirationYearOf(record), version);
    }

    /**
     * Seals a selector naming the card a record describes.
     *
     * <p>Assumptions: the purpose string is taken from the mapper rather than written here, because a
     * selector sealed under one purpose does not open under another and a copy of the literal could
     * drift away from the one the mapper uses.</p>
     *
     * @param record the record whose card number the selector should name
     * @return a selector this class's sealer issued, which the mapper will open
     */
    private String selectorFor(String record) {
        return this.sealer.seal(CardMapper.SELECTOR_PURPOSE, cardNumberOf(record));
    }

    /**
     * Builds a submission acceptable in every attribute except the one under test.
     *
     * <p>Assumptions: holding the other three acceptable is what makes a single reported fault
     * attributable to the attribute a case varies. Were two attributes unacceptable at once, a fault
     * set of one would mean the chain had stopped early rather than that the varied attribute was the
     * only problem.</p>
     *
     * @param field the response-field identity of the attribute to vary
     * @param value the value that attribute should carry
     * @return a submission carrying that value in that attribute and acceptable values elsewhere
     * @throws IllegalArgumentException if the identity names no editable attribute
     */
    private static CardUpdateRequest requestVarying(String field, String value) {
        return switch (field) {
            case CardUpdateService.FIELD_EMBOSSED_NAME -> new CardUpdateRequest(
                    value, "Y", NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION);
            case CardUpdateService.FIELD_ACTIVE_STATUS -> new CardUpdateRequest(
                    "Layla Ullrich", value, NEUTRAL_MONTH, NEUTRAL_YEAR, STORED_VERSION);
            case CardUpdateService.FIELD_EXPIRATION_MONTH -> new CardUpdateRequest(
                    "Layla Ullrich", "Y", value, NEUTRAL_YEAR, STORED_VERSION);
            case CardUpdateService.FIELD_EXPIRATION_YEAR -> new CardUpdateRequest(
                    "Layla Ullrich", "Y", NEUTRAL_MONTH, value, STORED_VERSION);
            default -> throw new IllegalArgumentException(
                    "no editable attribute is reported under the identity " + field);
        };
    }

    /**
     * Reports the flag state one attribute reaches for one submitted value.
     *
     * @param field the response-field identity of the attribute to inspect
     * @param value the value that attribute should carry
     * @return the state the attribute reached, or {@code null} where it was acceptable and so
     *     contributed no entry at all
     */
    private FieldValidationFlag stateOf(String field, String value) {
        for (ApiError.FieldError fault
                : this.service.validateAttributes(requestVarying(field, value))) {
            if (fault.field().equals(field)) {
                return fault.state();
            }
        }
        // Alternatives Considered: answering the valid flag state here instead of nothing. Rejected
        // because an acceptable attribute contributes no entry at all, so returning a state would
        // invent a value the service never produced and would hide a missing entry behind it.
        return null;
    }

    /**
     * Reports the one fault a submitted name produces, with the other three attributes acceptable.
     *
     * @param embossedName the name to submit
     * @return the single entry the name produced
     */
    private ApiError.FieldError soleFaultFor(String embossedName) {
        List<ApiError.FieldError> faults = this.service.validateAttributes(
                requestVarying(CardUpdateService.FIELD_EMBOSSED_NAME, embossedName));
        assertThat(faults).singleElement().isNotNull();
        return faults.get(0);
    }

    /**
     * Reports the one summary sentence a refused submission carries.
     *
     * <p>Assumptions: the sentence is chosen inside the write path rather than by the validating entry
     * point, so it can only be observed by driving a whole update. The positive control supplies the
     * stored row, and every submission handed here differs from it in at least one attribute so that
     * the no-change short circuit cannot intercept the call before the gates run.</p>
     *
     * @param request the submission expected to be refused
     * @return the sentence the refusal carried
     * @throws IOException if the positive control cannot be read from the test classpath
     * @throws AssertionError if the submission was accepted, so no sentence exists to report
     */
    private String refusalSentenceFor(CardUpdateRequest request) throws IOException {
        String record = onlyRecordOf(VALID_ACTIVE_RESOURCE);
        when(this.cards.findById(cardNumberOf(record))).thenReturn(Optional.of(cardFrom(record)));

        try {
            this.service.update(selectorFor(record), request);
            throw new AssertionError(
                    "the submission was accepted, so it carries no refusal sentence");
        } catch (ClientInputException refused) {
            return refused.getMessage();
        }
    }

    /**
     * Reports the sentence the shared advice renders for one conflict condition.
     *
     * @param kind the conflict condition to render
     * @return the sentence the advice chose for that condition
     */
    private String conflictSentenceFor(RecordConflictException.Kind kind) {
        ResponseEntity<ApiError> rendered = this.advice.onRecordConflict(
                new RecordConflictException(kind), requestAt(REQUEST_PATH));
        ApiError body = rendered.getBody();
        assertThat(body).isNotNull();
        assertThat(rendered.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        return body.message();
    }

    /**
     * Builds the request the shared advice reads a path from.
     *
     * <p>Assumptions: the advice reads only the path off the request when composing a body, so a stand-in
     * carrying one is sufficient and no servlet container is involved.</p>
     *
     * @param path the request path the rendered body should report
     * @return a stand-in request reporting that path
     */
    private static MockHttpServletRequest requestAt(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", path);
        request.setRequestURI(path);
        return request;
    }
}
