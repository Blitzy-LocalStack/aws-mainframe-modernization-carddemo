package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionDetailResponse;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the single-record transaction detail read: its guard, its three outcomes and its masking.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link TransactionViewService} had no executable consumer, so nothing held its three outcomes
 * apart. They are genuinely three and not one: a field left empty is a VALIDATION refusal carrying a
 * per-field entry, a key that matches no row is a NOT-FOUND refusal, and a read that fails underneath is
 * a SERVER failure that must not be reported as either of the other two. Each carries its own verbatim
 * message from the reference program, and a service that collapsed any pair of them would still answer
 * every request and would report the wrong thing for one of them.</p>
 *
 * <p>The successful outcome carries its own obligation: the card number leaves this boundary MASKED. The
 * detail response is the one shape in this context that carries a primary account number at all, so the
 * masking is asserted positively -- the response must hold the masked form -- and negatively -- it must
 * not hold the unmasked digits anywhere.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor a caller
 * invokes, no value it yields and no exception it raises outside the test engine, so the type itself
 * accepts no parameter, returns nothing and throws nothing. The inapplicability is stated rather than
 * passed over, because user-specified Rule 1 forbids a docstring that omits parameters, return values
 * or purpose and a reader has to be able to tell a declared inapplicability from an oversight. Every
 * member below carries its own at-clauses.</p>
 *
 * <h2>Assumptions: the repository is mocked and the mapper is real</h2>
 *
 * <p>The repository is a test double because its keyed read belongs to the persistence layer and is
 * proved against a real engine by the sibling integration tests, and because two of the three outcomes
 * can only be reached by making that read behave in ways a real one will not on demand. The mapper is
 * the genuine collaborator, because the masking under test is the mapper's own and a stubbed mapper
 * would assert the stub.</p>
 */
@DisplayName("TransactionViewService: the blank guard, the three outcomes and the masking")
class TransactionViewServiceTest {

    /** The sixteen-character identifier the cases read for. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /** The unmasked primary account number the stored row carries. */
    private static final String CARD_NUMBER = "4859452612877065";

    /** The repository test double every case stubs. */
    private TransactionRepository repository;

    /** The real mapper, which owns the masking and the money and timestamp rendering. */
    private TransactionMapper mapper;

    /** The service under test. */
    private TransactionViewService service;

    /** Builds a fresh service over a fresh double before each case. */
    @BeforeEach
    void setUp() {
        this.repository = mock(TransactionRepository.class);
        this.mapper = new TransactionMapper();
        this.service = new TransactionViewService(this.repository, this.mapper);
    }

    /**
     * Builds the stored row the successful case reads.
     *
     * @return a {@link Transaction} carrying all thirteen mapped members
     */
    private static Transaction storedRow() {
        return new Transaction(
                TRANSACTION_ID,
                "01",
                "0001",
                "POS TERM",
                "Purchase at Abshire-Lowe",
                new BigDecimal("504.77"),
                800000000L,
                "Abshire-Lowe",
                "North Enoshaven",
                "72112",
                CARD_NUMBER,
                LocalDateTime.parse("2022-06-10T19:27:53"),
                LocalDateTime.parse("2022-06-11T01:02:03.123456"));
    }

    /**
     * A stored row is rendered whole, with the card number masked and the amount exact.
     *
     * <p>Assumptions: the amount's SCALE is asserted as well as its value, because an exact decimal
     * compares by scale as well as by value and this response is the one a client reads a money figure
     * from. The absent return message is asserted as null rather than as an empty string, because the
     * reference clears its message on every turn and sets one only on a failure, so a successful read
     * carries none and the two spellings of that are not interchangeable to a client testing for it.</p>
     */
    @Test
    @DisplayName("a stored row is rendered whole, masked, and with no return message")
    void aStoredRowIsRenderedWhole() {
        when(repository.findById(TRANSACTION_ID)).thenReturn(Optional.of(storedRow()));

        TransactionDetailResponse detail = service.viewTransaction(TRANSACTION_ID);

        assertThat(detail.transactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(detail.typeCode()).isEqualTo("01");
        assertThat(detail.categoryCode()).isEqualTo("0001");
        assertThat(detail.source()).isEqualTo("POS TERM");
        assertThat(detail.description()).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(detail.amount().amount()).isEqualByComparingTo(new BigDecimal("504.77"));
        assertThat(detail.amount().amount().scale()).isEqualTo(2);
        assertThat(detail.merchantName()).isEqualTo("Abshire-Lowe");
        assertThat(detail.merchantCity()).isEqualTo("North Enoshaven");
        assertThat(detail.merchantZip()).isEqualTo("72112");
        assertThat(detail.originTimestamp()).isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(detail.processTimestamp()).isEqualTo("2022-06-11 01:02:03.123456");
        assertThat(detail.returnMessage())
                .as("a successful read carries no message, and absence is null rather than empty")
                .isNull();
    }

    /**
     * The card number leaves this boundary masked, and the unmasked digits leave it nowhere.
     *
     * <p>Assumptions: the negative half of the assertion is the one that matters and is easy to omit. A
     * response that carried the masked form in its card member and the unmasked digits in any other
     * member would satisfy a positive-only check while still publishing the account number, so every
     * member is searched for the unmasked value.</p>
     */
    @Test
    @DisplayName("the card number is masked and the unmasked digits appear in no member")
    void theCardNumberIsMasked() {
        when(repository.findById(TRANSACTION_ID)).thenReturn(Optional.of(storedRow()));

        TransactionDetailResponse detail = service.viewTransaction(TRANSACTION_ID);

        assertThat(detail.cardNumber()).isNotEqualTo(CARD_NUMBER).endsWith("7065");
        assertThat(detail.toString())
                .as("no member of the response may carry the unmasked account number")
                .doesNotContain(CARD_NUMBER);
    }

    /**
     * A field left unsupplied is refused as a validation problem keyed by the field it names.
     *
     * <p>Assumptions: the four spellings of absence are all asserted because the reference compares its
     * input field against SPACES and against LOW-VALUES, so a run of either pad is absent to it, and
     * over HTTP the same field arrives null or empty instead. Treating any one of the four as present
     * would send a pad value into the keyed read, which then reports a key that was never supplied as a
     * key that was not found -- the wrong message and the wrong status for the same mistake.</p>
     *
     * @param unsupplied the spelling of absence to offer for this case
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "                ", "\u0000\u0000\u0000"})
    @DisplayName("every spelling of an unsupplied identifier is refused as a validation problem")
    void anUnsuppliedIdentifierIsRefused(String unsupplied) {
        assertThatThrownBy(() -> service.viewTransaction(unsupplied))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionViewService.MESSAGE_TRAN_ID_EMPTY)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ClientInputException.class))
                .satisfies(refusal -> {
                    assertThat(refusal.code()).isEqualTo(ApiError.CODE_VALIDATION);
                    assertThat(refusal.field())
                            .isEqualTo(TransactionViewService.FIELD_TRANSACTION_ID);
                    // WHY : Assumptions: the validation STATE is asserted as well as the code and the
                    //       field, because the reference distinguishes a field left blank from one
                    //       filled in wrongly -- FLG-*-BLANK against FLG-*-NOT-OK -- and only the
                    //       blank arm additionally renders the '*' marker on the screen. A refusal
                    //       that reported the not-ok state for an unsupplied field would render
                    //       without that marker, which is a visible behavioural difference.
                    assertThat(refusal.state()).isEqualTo(FieldValidationFlag.BLANK);
                });
        verify(repository, never()).findById(any());
    }

    /**
     * A null identifier is refused by the same guard rather than by a null dereference.
     */
    @Test
    @DisplayName("a null identifier is refused by the same guard")
    void aNullIdentifierIsRefused() {
        assertThatThrownBy(() -> service.viewTransaction(null))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionViewService.MESSAGE_TRAN_ID_EMPTY);
        verify(repository, never()).findById(any());
    }

    /**
     * A key matching no row is a not-found refusal carrying the reference's own message.
     *
     * <p>Assumptions: the type is the not-found one and not the validation one, because the field was
     * supplied and was well formed -- what failed was the lookup. Reporting it as a validation problem
     * would key an error to a field the client filled in correctly.</p>
     */
    @Test
    @DisplayName("a key matching no row is refused as not found")
    void anAbsentRowIsReportedAsNotFound() {
        when(repository.findById(TRANSACTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewTransaction(TRANSACTION_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND);
    }

    /**
     * A read that fails underneath is a server failure that keeps its cause and hides nothing.
     *
     * <p>Assumptions: the cause is asserted to be RETAINED, because a failure whose cause is dropped
     * leaves an operator with a message and no way to reach the driver-level detail that explains it.
     * The type is asserted to be neither of the client-facing refusals, because a lookup failure
     * reported as a not-found row tells a client its key was wrong when the key was never reached.</p>
     */
    @Test
    @DisplayName("a failing read is reported as a server failure that keeps its cause")
    void aFailingReadIsReportedAsAServerFailure() {
        RuntimeException driverFailure = new RuntimeException("connection reset");
        when(repository.findById(TRANSACTION_ID)).thenThrow(driverFailure);

        assertThatThrownBy(() -> service.viewTransaction(TRANSACTION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION)
                .hasCause(driverFailure)
                .isNotInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(ClientInputException.class);
    }

    /**
     * The three outcome messages are distinct and are the reference's own strings.
     *
     * <p>Assumptions: distinctness is asserted explicitly because the three are what a client
     * distinguishes the outcomes by, and two conditions sharing a string would make one of them
     * unreportable. The strings themselves are transformation rule T8 values, carried across character
     * for character, so they are named literally here rather than derived.</p>
     */
    @Test
    @DisplayName("the three outcome messages are distinct and verbatim")
    void theThreeOutcomeMessagesAreDistinct() {
        assertThat(TransactionViewService.MESSAGE_TRAN_ID_EMPTY)
                .isEqualTo("Tran ID can NOT be empty...");
        assertThat(TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND)
                .isEqualTo("Transaction ID NOT found...");
        assertThat(java.util.List.of(TransactionViewService.MESSAGE_TRAN_ID_EMPTY,
                        TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND,
                        TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION))
                .doesNotHaveDuplicates();
        assertThat(TransactionViewService.FIELD_TRANSACTION_ID).isEqualTo("transactionId");
        // WHY : Assumptions: this program's failed-read sentence is asserted DISTINCT from the
        //       browse's, because the two are separate reference strings that differ only in
        //       capitalisation and a reader comparing them by eye would take them for one constant.
        //       Collapsing them onto a single value would report a detail read's failure with the
        //       list screen's wording, which is the wrong screen's message on the wrong screen.
        assertThat(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION)
                .isNotEqualTo(TransactionListService.MESSAGE_LOOKUP_FAILED);
    }

    /**
     * Both collaborators are required at construction.
     */
    @Test
    @DisplayName("both collaborators are required at construction")
    void bothCollaboratorsAreRequiredAtConstruction() {
        assertThatThrownBy(() -> new TransactionViewService(null, mapper))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionViewService(repository, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * The read goes through the plain keyed lookup, and that lookup is the one the service calls.
     *
     * <p>Assumptions: the call is verified rather than inferred from the outcome, because the outcome
     * of an absent row is the same whichever member produced it. What this case pins is WHICH member
     * runs, which is the first half of the unlocked-read claim the next case completes.</p>
     */
    @Test
    @DisplayName("the read goes through the plain keyed lookup")
    void readsThroughThePlainKeyedLookup() {
        when(repository.findById(TRANSACTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewTransaction(TRANSACTION_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND);

        verify(repository).findById(TRANSACTION_ID);
    }

    /**
     * No method the repository declares or inherits asks for a lock mode.
     *
     * <p>Assumptions: the reference reads its record with the update option at line 275 of
     * {@code app/cbl/COTRN01C.cbl} while containing no rewrite anywhere in its 330 lines, so the
     * target deliberately holds FEWER locks than the baseline. That direction of change is a
     * registered divergence, and this case is the evidence the register cites: it asserts that no
     * lock mode is requested anywhere on the repository this service reads through, so the claim
     * cannot quietly stop being true.</p>
     *
     * <p>Assumptions: both the persistence annotation and the framework's repository annotation are
     * looked for BY NAME rather than by type, so the assertion holds whether or not either annotation
     * is on this module's test classpath. Naming them as strings is what lets the check live in a
     * module that need not depend on the annotation whose absence it asserts.</p>
     */
    @Test
    @DisplayName("no lock mode is requested anywhere on the repository")
    void repositoryRequestsNoLockMode() {
        for (Method declared : TransactionRepository.class.getMethods()) {
            assertThat(declared.getAnnotations())
                    .as("method %s must not request a lock mode", declared.getName())
                    .noneMatch(present -> present.annotationType().getName()
                            .endsWith("persistence.Lock")
                            || present.annotationType().getName()
                                    .endsWith("jpa.repository.Lock"));
        }
    }
}
