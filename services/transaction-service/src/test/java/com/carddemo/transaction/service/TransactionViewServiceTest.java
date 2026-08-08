package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionDetailResponse;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

/**
 * Pins the "Transaction View" single-record read against the paragraphs of {@code COTRN01C}.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link TransactionViewService} transcribes {@code app/cbl/COTRN01C.cbl}, 330 lines, transaction
 * {@code CT01}, named "Transaction View" in the inventory table of the repository root
 * {@code README.md} at line 299. This class holds that transcription to four separate obligations:
 * the blank guard the reference selection applies before it reads, the three mutually exclusive
 * outcomes of the read itself, the shape of the response the successful outcome publishes, and -- the
 * property that distinguishes this program from its three siblings -- that the read is the ONLY thing
 * that reaches the table at all.</p>
 *
 * <p>Assumptions: the screen name is taken from the inventory row rather than paraphrased. The
 * plausible mis-citation is "Transaction Detail", which reads naturally beside a detail endpoint and
 * appears nowhere in that table. Naming a screen the inventory does not name would leave an operator
 * searching for the screen they are debugging unable to find the class that pins it.</p>
 *
 * <p>Assumptions: the inventory rows are cited at lines 298 to 302 as the tree stands. The pristine
 * baseline carried the same five rows twenty lines earlier, at lines 278 to 282, because the migration
 * section this plan adds to {@code README.md} moved them down. Both figures are recorded in the charter
 * beside this class, so a reader meeting two citations of one table reads a shift with a known cause.
 * The verifiable claim is the row content; the line numbers are the convenience.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This type is a test class: no caller constructs
 * it, it yields no value and it raises nothing outside the test engine, so the type itself accepts no
 * parameter, returns nothing and throws nothing. The inapplicability is stated rather than passed over,
 * because the user-specified Explainability rule forbids a docstring that omits parameters, return
 * values or purpose, and a reader has to be able to tell a declared inapplicability from an oversight.
 * Every member below carries its own at-clauses.</p>
 *
 * <h2>Alternatives Considered: an application context, rejected in favour of plain mocks</h2>
 *
 * <p>Alternatives Considered: starting a Spring context for these cases, either whole or sliced to the
 * web layer, and reaching the persistence layer through containers. All three were rejected. The unit
 * under test is a stateless class whose two collaborators arrive through its constructor, so every
 * branch below is reachable by handing it two stand-ins; a context would add startup cost and a second
 * failure mode -- wiring -- to cases that are about transcription. The container-backed machinery
 * declared in {@code src/test/resources/application-test.yml} exists for the {@code RepositoryIT}
 * classes in the sibling {@code repository} package, and that file is read here rather than duplicated.
 * Two of the three read outcomes are additionally unreachable against a working engine on demand: a
 * lookup cannot be made to fail underneath a real connection at will.</p>
 *
 * <p>Trade-offs: the extension form used here applies strict stubbing, so a stubbing no case exercises
 * fails the build, whereas the three sibling classes in this package assemble their stand-ins with a
 * plain factory call and do not. The stricter form is accepted here because this class asserts a
 * negative -- that nothing beyond one keyed read is called -- and a drifting stub is exactly how such a
 * negative rots into a case that passes while proving nothing. The cost is that a case which stubs more
 * than it uses fails, which is the intended pressure.</p>
 *
 * <h2>Assumptions: what is mocked, and what is consumed as declared</h2>
 *
 * <p>Assumptions: both collaborators are mocked, matching the list the charter beside this class
 * fixes. {@link TransactionRepository} is mocked because its keyed read belongs to the persistence
 * layer and is proved against a real engine by the sibling integration class. {@link TransactionMapper}
 * is mocked because what this class asserts is that the service DELEGATES the response construction --
 * the masking, the money construction and the timestamp rendering are the mapper's own obligations and
 * are asserted where that type is tested. A real mapper here would make every case below pass or fail
 * for two reasons at once.</p>
 *
 * <p>Assumptions: the repository's identifier type is {@link String} and not a numeric type. The
 * entity's identity attribute is the {@code String} member {@code tranId}, bound to a constant-length
 * character column, because the baseline transaction identifier is a sixteen-character display field.
 * A stub written against a numeric identifier would assert a lookup the production code cannot
 * perform.</p>
 *
 * <p>Assumptions: nothing here reads a shared contract it could re-declare. The money invariant comes
 * from {@link Money}, the problem codes and the message band from {@link ApiError}, the absence
 * predicate and the blank state from {@link FieldValidationFlag}, and the record layout from
 * {@link Transaction} and {@link TransactionDetailResponse}. That is the direction
 * {@code tests/README.md} lines 540 to 542 states for the parity oracle's own copybook layouts, applied
 * to this tree.</p>
 *
 * <h2>Assumptions: NO golden master covers this program</h2>
 *
 * <p>Assumptions: no golden master exists for this path and none is claimed. {@code tests/README.md}
 * lines 83 to 85 record that the online {@code CO*} programs cannot be run end to end without a CICS
 * runtime, which the runner does not have, so only their extractable validation logic is unit-tested.
 * {@code COTRN01C} is one of those programs. Parity therefore rests on two things and is asserted as
 * such: validation logic transcribed branch by branch with each branch cited to its line, and the
 * copybook record contracts. No assertion below is justified by pointing at a committed output file,
 * and nothing here creates, regenerates or reads anything under the oracle's fixture or golden trees.
 * The nightly posting program does have such coverage and writes three of the tables this context
 * owns, and that coverage is not coverage of this screen: the tables are the same tables, and the
 * programs that write them are not the same programs.</p>
 *
 * <p>Assumptions: the graded condition-code rubric the oracle aggregates belongs to that suite alone.
 * This class runs under a gate that is binary -- a case either passes or fails -- so nothing below
 * tolerates a degree of failure, and no case here is wired into the oracle's pipeline.</p>
 *
 * <h2>Assumptions: determinism is supplied, never read</h2>
 *
 * <p>Assumptions: no case below reads an ambient clock. The two timestamps the record carries are
 * supplied as parsed literals, so a rerun compares the same bytes. Normalising an ambient reading
 * before comparison is the oracle's technique for a batch program compared against a committed file,
 * and the class under test generates no timestamp at all -- it reads whichever the stored row holds --
 * so there is nothing here to normalise. Each case builds its own row and its own stand-ins, sharing no
 * mutable state, which is what keeps the class safe to run in parallel with any other.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the detail screen: one keyed read, three outcomes, and nothing else")
class TransactionViewServiceTest {

    /** The sixteen-character identifier every case reads on, at the width the key column declares. */
    private static final String TRANSACTION_ID = "0000000000683580";

    /**
     * The sixteen-digit primary account number the stored row carries, masked by the mapper and never
     * by this service.
     *
     * <p>Refactoring Rationale: this member was described as "the account number", which names a
     * different field. {@code TRAN-CARD-NUM} is a sixteen-digit primary account number; the account
     * identifier is the eleven-digit {@code ACCT-ID} that reaches this context through the card
     * cross-reference and appears nowhere on this row. The two are not interchangeable in the
     * disclosure rule that governs both: a primary account number may be rendered THROUGH
     * {@code CardNumberMasker} and an account identifier is omitted outright, so a reader who took
     * this constant for an account identifier would conclude the masked rendering below was a rule
     * violation rather than the rule being followed.</p>
     */
    private static final String PRIMARY_ACCOUNT_NUMBER = "4859452612877065";

    /** The masked rendering the mocked mapper answers with, standing for the mapper's own output. */
    private static final String MASKED_CARD_NUMBER = "************7065";

    /** The keyed reader of the posted-transaction table, standing in for the reference dataset. */
    @Mock
    private TransactionRepository repository;

    /** The converter that owns the masking, the money construction and the timestamp rendering. */
    @Mock
    private TransactionMapper transactionMapper;

    /** The unit under test, rebuilt over fresh stand-ins before each case. */
    private TransactionViewService service;

    /**
     * Builds a service over freshly created stand-ins before every case.
     *
     * <p>Assumptions: the service is rebuilt rather than shared because it is constructed around the
     * two stand-ins, and a service retained across cases would hold the previous case's stubbings. The
     * class under test keeps no mutable instance state of its own, so rebuilding it costs nothing.</p>
     */
    @BeforeEach
    void setUp() {
        this.service = new TransactionViewService(this.repository, this.transactionMapper);
    }

    /**
     * Builds the stored row the reading cases return from the table.
     *
     * <p>Assumptions: all thirteen mapped members are populated, and the amount is given nine integer
     * digits or fewer because {@code TRAN-AMT PIC S9(09)V99} at line 10 of {@code app/cpy/CVTRA05Y.cpy}
     * is the domain the entity normalises against and a wider value is refused at construction.</p>
     *
     * @return a {@link Transaction} carrying the thirteen members the record contract declares, with
     *     the {@code FILLER PIC X(20)} of line 18 absent because the entity drops it
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
                PRIMARY_ACCOUNT_NUMBER,
                LocalDateTime.parse("2022-06-10T19:27:53"),
                LocalDateTime.parse("2022-06-11T01:02:03.123456"));
    }

    /**
     * Builds the response the mocked mapper answers with for a successful read.
     *
     * <p>Assumptions: the components are supplied in the order the canonical constructor declares them,
     * which is the record order of lines 5 to 17 of {@code app/cpy/CVTRA05Y.cpy} and deliberately not
     * the screen order. The card member carries the masked rendering, because the mapper masks and this
     * stands for the mapper's output rather than for the stored row.</p>
     *
     * @param returnMessage the message the response is to carry, of type {@code String}, or
     *     {@code null} for the successful read, which carries none
     * @return a {@link TransactionDetailResponse} whose members correspond to {@link #storedRow()}
     */
    private static TransactionDetailResponse mappedResponse(String returnMessage) {
        return new TransactionDetailResponse(
                TRANSACTION_ID,
                "01",
                "0001",
                "POS TERM",
                "Purchase at Abshire-Lowe",
                Money.of("504.77"),
                "800000000",
                "Abshire-Lowe",
                "North Enoshaven",
                "72112",
                MASKED_CARD_NUMBER,
                "2022-06-10 19:27:53.000000",
                "2022-06-11 01:02:03.123456",
                returnMessage);
    }

    /**
     * Collects the values of every publicly readable text constant the service declares.
     *
     * <p>Assumptions: only static text members are read, and each is read reflectively rather than
     * named one by one, so a constant added later is covered without this helper being revisited. That
     * matters for the navigation case below, whose whole claim is about what the class does NOT
     * declare.</p>
     *
     * <p>Refactoring Rationale: the returned order is UNSPECIFIED, and this paragraph replaces a
     * {@code @return} that promised declaration order. Nothing guarantees that: the reflective member
     * enumeration this helper walks is documented to return the members in no particular order, so the
     * promise was a property of one runtime's behaviour rather than of the platform, and a run on
     * which it did not hold would have broken a caller that relied on it. No caller does -- both
     * consumers ask only whether a value is present or absent in the collection -- so the promise was
     * removed rather than made true by sorting. Sorting was the alternative and was declined because
     * it would impose an order on a collection nothing reads in order, and a reader could then take
     * that order to be meaningful.</p>
     *
     * @return the value of every {@code public static final String} on
     *     {@link TransactionViewService}, in no guaranteed order; callers must treat the result as a
     *     set of values rather than as a sequence
     * @throws IllegalAccessException if a member reported as publicly readable cannot be read, which
     *     would mean the reflective assumption above no longer holds
     */
    private static List<String> publicTextConstants() throws IllegalAccessException {
        List<String> values = new ArrayList<>();
        for (Field declared : TransactionViewService.class.getDeclaredFields()) {
            int modifiers = declared.getModifiers();
            if (Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers)
                    && declared.getType() == String.class) {
                values.add((String) declared.get(null));
            }
        }
        return values;
    }

    /**
     * A stored row is read once and handed to the mapper with no message, and travels back unchanged.
     *
     * <p>This pins the successful arm of {@code PROCESS-ENTER-KEY}, lines 172 to 191 of
     * {@code app/cbl/COTRN01C.cbl}: line 172 moves the keyed-in field into the record key, line 173
     * performs {@code READ-TRANSACT-FILE}, line 176 tests the error flag off, and lines 177 to 190 then
     * move the record into the screen before line 191 sends it. It also pins the normal arm of
     * {@code READ-TRANSACT-FILE}, lines 280 to 282, where a normal response code continues without
     * setting a message.</p>
     *
     * <p>Assumptions: the identifier reaches the query UNCHANGED, so the argument is matched exactly
     * rather than loosely. Line 172 moves the screen field straight into the record key with no
     * conversion and no re-padding, so a service that trimmed or widened the value would read on a key
     * the reference program never forms.</p>
     *
     * <p>Assumptions: the second argument to the mapper is asserted to be NULL rather than blank. The
     * reference program clears its message at line 91 on every turn and sets one only on a failure, so
     * a successful read carries none, and the response's message member is the one field for which
     * absence is representable. A blank string and a null are not interchangeable to a client testing
     * for a message, so which one the service passes is a behaviour and not an implementation detail.
     * </p>
     *
     * <p>Assumptions: the returned object is asserted to be the very instance the mapper produced. That
     * is what says the service adds nothing after the conversion -- no re-wrapping, no substitution and
     * no second pass -- which is the whole of its contribution on this path.</p>
     */
    @Test
    @DisplayName("a stored row is read once, mapped with no message, and returned unchanged")
    void aStoredRowIsReadOnceAndMappedWithNoMessage() {
        Transaction stored = storedRow();
        TransactionDetailResponse mapped = mappedResponse(null);
        when(repository.findById(TRANSACTION_ID)).thenReturn(Optional.of(stored));
        when(transactionMapper.toDetailResponse(stored, null)).thenReturn(mapped);

        TransactionDetailResponse detail = service.viewTransaction(TRANSACTION_ID);

        assertThat(detail)
                .as("the service publishes the mapper's own object, adding nothing after it")
                .isSameAs(mapped);
        verify(repository).findById(TRANSACTION_ID);
        verify(transactionMapper).toDetailResponse(stored, null);
    }

    /**
     * The read is one keyed lookup: no browse, no write, and no arithmetic on the amount.
     *
     * <p>This is the property that separates {@code COTRN01C} from the three programs its siblings pin,
     * and it is measured rather than inferred. Across all 330 lines of {@code app/cbl/COTRN01C.cbl} the
     * count of {@code STARTBR}, of {@code READNEXT}, of {@code READPREV}, of {@code ENDBR}, of
     * {@code EXEC CICS WRITE}, of {@code REWRITE}, of {@code COMPUTE} and of {@code SYNCPOINT} is zero
     * in every one of the eight cases. The only file verb in the program is the single
     * {@code EXEC CICS READ} of lines 269 to 278, inside {@code READ-TRANSACT-FILE} at line 267.</p>
     *
     * <p>Assumptions: the three browse analogues named below are the repository members that would
     * carry a browse if one existed -- an opening read from the top, a read forward past a key and a
     * read backward before a key, which are what the absent {@code STARTBR}, {@code READNEXT} and
     * {@code READPREV} would have become. They are named individually so that the case reads as the
     * claim it makes, and the exhaustive check that follows then covers every other member including
     * ones added later.</p>
     *
     * <p>Assumptions: the amount is asserted to be the SAME instance the mapper produced, which is how
     * the absence of arithmetic is observable from outside. The reference program performs no
     * {@code COMPUTE} at all; line 177 moves the record amount into an edited display field and line
     * 183 moves that edited field to the screen, and a move is not a calculation. A service that
     * re-scaled, rounded or re-signed the value would answer with a different object even where the
     * numeric comparison still matched.</p>
     *
     * <p>Trade-offs: the exhaustive check is the load-bearing half and the individually named negatives
     * are redundant to it. They are kept because a failure against a named member says which absent verb
     * reappeared, whereas the exhaustive check alone reports only that something extra was called.</p>
     */
    @Test
    @DisplayName("one keyed read only: no browse, no write, no arithmetic")
    void theReadIsOneKeyedLookupWithNoBrowseNoWriteAndNoArithmetic() {
        Transaction stored = storedRow();
        TransactionDetailResponse mapped = mappedResponse(null);
        when(repository.findById(TRANSACTION_ID)).thenReturn(Optional.of(stored));
        when(transactionMapper.toDetailResponse(stored, null)).thenReturn(mapped);

        TransactionDetailResponse detail = service.viewTransaction(TRANSACTION_ID);

        verify(repository).findById(TRANSACTION_ID);
        verify(repository, never()).findAllByOrderByTranIdAsc(any(Limit.class));
        verify(repository, never())
                .findByTranIdGreaterThanOrderByTranIdAsc(anyString(), any(Limit.class));
        verify(repository, never())
                .findByTranIdLessThanOrderByTranIdDesc(anyString(), any(Limit.class));
        verify(repository, never()).save(any(Transaction.class));
        verify(repository, never()).deleteById(anyString());
        verifyNoMoreInteractions(repository);

        assertThat(detail.amount())
                .as("no arithmetic is performed on the amount, so the mapper's object is published")
                .isSameAs(mapped.amount());
    }

    /**
     * No lock mode is requested anywhere on the repository this service reads through.
     *
     * <p>This registers a deliberate divergence. The reference read at lines 269 to 278 of
     * {@code app/cbl/COTRN01C.cbl} supplies the {@code UPDATE} option at line 275, which acquires an
     * exclusive read-for-update lock, and the program then contains no {@code REWRITE} and no
     * {@code EXEC CICS WRITE} anywhere in its 330 lines. The lock is therefore acquired and never used,
     * so the target reads without one.</p>
     *
     * <p>Refactoring Rationale: the same {@code UPDATE} option in the payment program is NOT dropped,
     * and conflating the two would be an error in both directions. At line 351 of
     * {@code app/cbl/COBIL00C.cbl} the option is supplied and is then followed through by a genuine
     * {@code EXEC CICS REWRITE} at line 379, inside {@code UPDATE-ACCTDAT-FILE.} at line 377, naming its
     * dataset at line 380 and its source record at line 381. There the lock is load-bearing and is
     * preserved; here it protects nothing. Two identical reference options thus map to two different
     * targets, each decided by whether a write follows it, and the sibling case that pins the payment
     * side must assert the opposite of this one.</p>
     *
     * <p>Trade-offs: the consequence is accepted and stated rather than glossed. This target holds FEWER
     * locks than the baseline, so a concurrent writer the baseline would have blocked for the duration
     * of the read now proceeds. That is tolerable because the transcribed path performs no write of its
     * own, so there is no read-then-write window for a competing writer to corrupt; the divergence is
     * registered in the traceability document the service's own charter names.</p>
     *
     * <p>Assumptions: both the persistence annotation and the framework's repository annotation are
     * looked for BY NAME rather than by type, so the check holds whether or not either annotation is on
     * this module's test classpath. Naming them as text is what lets an assertion about an annotation's
     * absence live in a module that need not depend on that annotation.</p>
     */
    @Test
    @DisplayName("the dropped update lock: no lock mode is requested anywhere")
    void noLockModeIsRequestedAnywhereOnTheRepository() {
        for (Method declared : TransactionRepository.class.getMethods()) {
            assertThat(declared.getAnnotations())
                    .as("member %s must request no lock mode", declared.getName())
                    .noneMatch(present -> present.annotationType().getName()
                            .endsWith("persistence.Lock")
                            || present.annotationType().getName()
                                    .endsWith("jpa.repository.Lock"));
        }
    }

    /**
     * Every spelling of an unsupplied identifier is refused before anything reaches the table.
     *
     * <p>This pins the first branch of {@code PROCESS-ENTER-KEY}, lines 146 to 152 of
     * {@code app/cbl/COTRN01C.cbl}: the selection opens at line 146, line 147 tests the keyed-in field
     * against {@code SPACES} and against {@code LOW-VALUES}, line 148 raises the error flag, line 149
     * moves the refusal text, line 151 repositions the cursor onto the field and line 152 sends the
     * screen back. The read at line 173 is guarded by the error flag being off at line 158, so on this
     * branch the table is never reached.</p>
     *
     * <p>Assumptions: four spellings of absence are offered because the reference test at line 147
     * compares against two figurative constants, so a run of either pad is absent to it, while over HTTP
     * the same field arrives empty instead. Treating any one of the four as present would send a pad
     * value into the keyed read, which would then report a key that was never supplied as a key that was
     * not found -- the wrong message and the wrong status for one mistake.</p>
     *
     * <p>Assumptions: the validation STATE is asserted alongside the code and the field key. The
     * reference distinguishes a field left blank from one filled in unacceptably, and only the blank arm
     * additionally renders a marker into the field's own subfield, per lines 24 and 25 of
     * {@code app/cpy/CSSETATY.cpy} against the colour move at lines 21 and 22. A refusal reporting the
     * unacceptable-value state for an unsupplied field would render without that marker, which is a
     * visible difference on the screen.</p>
     *
     * <p>Assumptions: both collaborators are asserted untouched, not merely the table. The reference
     * selection leaves on its first matching branch, so no conversion happens either, and a service that
     * called the mapper on this path would be building a response for a request it is refusing.</p>
     *
     * @param unsupplied the spelling of absence this case offers, of type {@code String}, being empty,
     *     one pad character, a full sixteen-character pad run, or a run of the low-value pad
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "                ", "\u0000\u0000\u0000"})
    @DisplayName("an unsupplied identifier is refused as blank, and nothing is read")
    void anUnsuppliedIdentifierIsRefusedAsBlank(String unsupplied) {
        assertThatThrownBy(() -> service.viewTransaction(unsupplied))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionViewService.MESSAGE_TRAN_ID_EMPTY)
                .satisfies(raised -> {
                    ClientInputException refusal = (ClientInputException) raised;
                    assertThat(refusal.code()).isEqualTo(ApiError.CODE_VALIDATION);
                    assertThat(refusal.field())
                            .isEqualTo(TransactionViewService.FIELD_TRANSACTION_ID);
                    assertThat(refusal.state())
                            .as("the blank state is what draws the marker the reference draws")
                            .isEqualTo(FieldValidationFlag.BLANK);
                });

        verifyNoInteractions(repository, transactionMapper);
    }

    /**
     * An absent identifier is refused by the same guard rather than by a null dereference.
     *
     * <p>This pins the same branch at lines 146 to 152 of {@code app/cbl/COTRN01C.cbl} for the one
     * spelling of absence the reference screen cannot produce. A 3270 field always arrives as bytes, so
     * the reference has no null to test at line 147; an HTTP path parameter can be absent outright.</p>
     *
     * <p>Assumptions: the guard is asserted to answer with the same refusal as the other four spellings,
     * not with a distinct one. A separate null path would give a client two different messages for one
     * mistake, and the shared absence predicate exists precisely so the five collapse onto one.</p>
     */
    @Test
    @DisplayName("an absent identifier is refused by the same guard")
    void anAbsentIdentifierIsRefusedByTheSameGuard() {
        assertThatThrownBy(() -> service.viewTransaction(null))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionViewService.MESSAGE_TRAN_ID_EMPTY);

        verifyNoInteractions(repository, transactionMapper);
    }

    /**
     * A key matching no row is reported as not found, carrying the reference's own sentence.
     *
     * <p>This pins the not-found arm of {@code READ-TRANSACT-FILE}, lines 283 to 288 of
     * {@code app/cbl/COTRN01C.cbl}: the arm opens at line 283, line 284 raises the error flag and
     * <b>line 285</b> moves the refusal text, continuing onto line 286, before line 287 repositions the
     * cursor and line 288 sends the screen.</p>
     *
     * <p>Assumptions: line 285 is cited and not line 284. Line 284 moves the error flag and carries no
     * text at all, so citing it would attach this assertion to a branch marker rather than to the
     * sentence being asserted, and a later reader checking the citation would find no string there.</p>
     *
     * <p>Assumptions: the outcome is the not-found kind and not the validation kind, because the field
     * was supplied and was well formed -- what failed was the lookup. Reporting it as a validation
     * problem would key an error to a control the client filled in correctly. The reference itself says
     * this is ordinary rather than exceptional by handling it inside the same selection as a normal read
     * instead of abending.</p>
     *
     * <p>Assumptions: the mapper is asserted untouched, because there is no row to convert. A service
     * that called it with an absent row would be relying on the converter to raise, which would surface
     * the converter's sentence instead of the reference's.</p>
     */
    @Test
    @DisplayName("a key matching no row is reported as not found")
    void aKeyMatchingNoRowIsReportedAsNotFound() {
        when(repository.findById(TRANSACTION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.viewTransaction(TRANSACTION_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage("Transaction ID NOT found...")
                .hasMessage(TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND);

        verify(repository).findById(TRANSACTION_ID);
        verifyNoInteractions(transactionMapper);
    }

    /**
     * A lookup that fails underneath is a server failure that keeps its cause and hides nothing.
     *
     * <p>This pins the catch-all arm of {@code READ-TRANSACT-FILE}, lines 289 to 295 of
     * {@code app/cbl/COTRN01C.cbl}: the arm opens at line 289 over every response code other than
     * normal and not-found, line 290 displays the response and reason codes, line 291 raises the error
     * flag and <b>line 292</b> moves the refusal text, continuing onto line 293.</p>
     *
     * <p>Assumptions: line 292 is cited and neither line 291 nor line 290. Line 291 moves the error flag
     * and line 290 is a display statement, so only line 292 carries the sentence this case asserts.</p>
     *
     * <p>Assumptions: the sentence carries an upper-case noun and is asserted literally in that form.
     * This spelling has five sites across the repository -- line 292 here, two in the capture program
     * and two in the payment program -- and a lower-case spelling of the same sentence is a DIFFERENT
     * constant belonging to the browse program, asserted apart in the case below.</p>
     *
     * <p>Assumptions: the cause is asserted RETAINED. Line 290 displays the failed read's response and
     * reason codes, and the equivalent context here is the failure object itself; a failure whose cause
     * is dropped leaves an operator with a sentence and no route to the driver-level detail that
     * explains it. The reference display carries no correlation identity of any kind, which is the gap
     * the target's correlation filter closes at the edge rather than in this class.</p>
     *
     * <p>Assumptions: the outcome is asserted to be NEITHER client-facing refusal. A lookup failure
     * reported as a not-found row would tell a client its key was wrong when the key was never reached,
     * and reported as a validation problem would blame a control that was filled in correctly.</p>
     */
    @Test
    @DisplayName("a failing lookup is a server failure that keeps its cause")
    void aFailingLookupIsAServerFailureThatKeepsItsCause() {
        RuntimeException driverFailure = new RuntimeException("connection reset");
        when(repository.findById(TRANSACTION_ID)).thenThrow(driverFailure);

        assertThatThrownBy(() -> service.viewTransaction(TRANSACTION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to lookup Transaction...")
                .hasMessage(TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION)
                .hasCause(driverFailure)
                .isNotInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(ClientInputException.class);

        verifyNoInteractions(transactionMapper);
    }

    /**
     * This screen's failed-lookup sentence is a different constant from the browse screen's.
     *
     * <p>This pins line 292 of {@code app/cbl/COTRN01C.cbl} against lines 615, 649 and 683 of
     * {@code app/cbl/COTRN00C.cbl}. The two sentences differ in exactly one character's case: the noun
     * is capitalised on this screen and is not on the browse screen. Both are reference strings carried
     * across character for character, so both survive.</p>
     *
     * <p>Alternatives Considered: collapsing the two onto one shared constant, which is what a reader
     * comparing them by eye would naturally propose. Rejected because it cannot be done without
     * re-wording one of the two screens: whichever spelling lost would report its own failure in the
     * other screen's words. The two are therefore kept as two constants, and this case asserts the
     * inequality so a later consolidation fails here rather than silently changing a screen.</p>
     *
     * <p>Assumptions: the difference is asserted to be case ALONE, by comparing the two ignoring case
     * and finding them equal while comparing them exactly and finding them different. Asserting mere
     * inequality would still pass if one constant were re-worded outright, which is the change this case
     * exists to catch.</p>
     */
    @Test
    @DisplayName("the failed-lookup sentence is this screen's, not the browse screen's")
    void theFailedLookupSentenceIsNotTheBrowseScreens() {
        String detailSentence = TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION;
        String browseSentence = TransactionListService.MESSAGE_LOOKUP_FAILED;

        assertThat(detailSentence).isEqualTo("Unable to lookup Transaction...");
        assertThat(browseSentence)
                .as("the browse screen's spelling is the lower-case one and stays that way")
                .isEqualTo("Unable to lookup transaction...");
        assertThat(detailSentence)
                .as("two constants, not one, and they differ only in the noun's case")
                .isNotEqualTo(browseSentence)
                .isEqualToIgnoringCase(browseSentence);
    }

    /**
     * Every sentence this screen can publish fits the seventy-five-character message band.
     *
     * <p>The width chain the reference program establishes has three links and narrows at each one.
     * {@code WS-MESSAGE} is declared {@code PIC X(80)} at line 38 of {@code app/cbl/COTRN01C.cbl}; line
     * 217, inside {@code SEND-TRNVIEW-SCREEN} at line 213 and after {@code POPULATE-HEADER-INFO} at line
     * 215, moves it into the map's message field, which is narrower; and the band the migrated response
     * answers in is the seventy-five of the reference message fields, which
     * {@link ApiError#MESSAGE_RENDERING_WIDTH} carries.</p>
     *
     * <p>Assumptions: the assertion made is that no sentence this screen can publish is long enough for
     * the narrowing to bite. All three are well inside the band, so the chain never truncates in
     * practice, and asserting the widths of the intermediate links would assert the reference program's
     * declarations rather than this service's behaviour. Recording the chain and asserting the outcome is
     * what keeps a later, longer sentence from being introduced silently.</p>
     *
     * <p>Assumptions: the eighty of line 38 is the widest link and is deliberately NOT the width the
     * response answers in. Taking eighty as the contract would let a sentence through that the reference
     * screen itself could not display, so the narrowest link governs.</p>
     *
     * @throws IllegalAccessException if a publicly readable constant cannot be read, propagated from the
     *     reflective helper this case reads the sentences through
     */
    @Test
    @DisplayName("every publishable sentence fits the seventy-five-character band")
    void everyPublishableSentenceFitsTheMessageBand() throws IllegalAccessException {
        assertThat(ApiError.MESSAGE_RENDERING_WIDTH)
                .as("the band is the narrowest link in the chain, not the eighty of line 38")
                .isEqualTo(75);

        List<String> sentences = publicTextConstants();
        assertThat(sentences)
                .as("the three refusal sentences and the field key are all read reflectively")
                .contains(TransactionViewService.MESSAGE_TRAN_ID_EMPTY,
                        TransactionViewService.MESSAGE_TRANSACTION_ID_NOT_FOUND,
                        TransactionViewService.MESSAGE_UNABLE_TO_LOOKUP_TRANSACTION);
        assertThat(sentences).allSatisfy(sentence ->
                assertThat(sentence.length()).isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH));
    }

    /**
     * The response's members follow the record order and deliberately not the screen order.
     *
     * <p>Two orders exist and they differ. Lines 5 to 17 of {@code app/cpy/CVTRA05Y.cpy} declare the
     * record as identifier, type code, category code, source, description, amount, merchant identifier,
     * merchant name, merchant city, merchant postal code, card number, originating timestamp, processing
     * timestamp. The screen moves at lines 178 to 190 of {@code app/cbl/COTRN01C.cbl} use a different
     * order, placing the card number second at line 179 and the description after the amount, and the
     * merchant members last. The response follows the RECORD order.</p>
     *
     * <p>Alternatives Considered: following the screen order instead, so that a reader holding the map
     * beside the response would find the members in the same sequence. Rejected because the record is
     * the durable contract and the screen is one of several consumers: the same thirteen members are
     * read by the browse projection and by the reporting projection, which arrange them differently
     * again. Ordering the response after one consumer's layout would make the other two look
     * re-ordered, whereas ordering it after the record leaves every consumer equally far from it.</p>
     *
     * <p>Assumptions: the member names are asserted in sequence rather than merely as a set, because
     * what the previous paragraph decides is an ORDER, and a set comparison would pass against the screen
     * order too. The message member is asserted last and is the one member with no counterpart in the
     * record, so it sits after the thirteen rather than among them.</p>
     */
    @Test
    @DisplayName("the response follows the record order, not the screen order")
    void theResponseFollowsTheRecordOrderNotTheScreenOrder() {
        List<String> declared =
                Arrays.stream(TransactionDetailResponse.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();

        assertThat(declared)
                .as("lines 5 to 17 of the record contract, then the message member")
                .containsExactly(
                        "transactionId",
                        "typeCode",
                        "categoryCode",
                        "source",
                        "description",
                        "amount",
                        "merchantId",
                        "merchantName",
                        "merchantCity",
                        "merchantZip",
                        "cardNumber",
                        "originTimestamp",
                        "processTimestamp",
                        "returnMessage");
    }

    /**
     * The amount travels as the shared exact-decimal money type and never as a bare number.
     *
     * <p>The reference program routes the amount through an edited display field: line 49 of
     * {@code app/cbl/COTRN01C.cbl} declares {@code WS-TRAN-AMT PIC +99999999.99}, line 177 moves the
     * record amount into it and line 183 moves that edited field to the screen rather than moving the
     * record amount directly. The stored value itself is {@code TRAN-AMT PIC S9(09)V99} at line 10 of
     * {@code app/cpy/CVTRA05Y.cpy}: signed, nine integer digits, two decimals.</p>
     *
     * <p>Assumptions: the member's declared TYPE is asserted, positively, to be the shared money type.
     * That is a complete statement of the invariant, because a member of that type cannot hold an
     * approximate binary value whatever a caller passes; asserting the absence of the approximate types
     * by name is the complementary negative and is owned by the architectural gate in the sibling
     * {@code architecture} package, which fails the build for the whole module if one appears.</p>
     *
     * <p>Assumptions: the wire form is a JSON string and that property is CITED, not re-asserted here.
     * It belongs to the serialisation module in {@code common-lib} and is pinned by that module's own
     * test, so re-asserting it in this module would give the same contract two owners that could drift
     * apart. What matters at this boundary is the scale, asserted here, because an exact decimal compares
     * by scale as well as by value and this response is the one a client reads a money figure from.</p>
     */
    @Test
    @DisplayName("the amount is exact-decimal money at scale two")
    void theAmountIsExactDecimalMoneyAtScaleTwo() {
        RecordComponent amountMember =
                Arrays.stream(TransactionDetailResponse.class.getRecordComponents())
                        .filter(component -> "amount".equals(component.getName()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "the response declares no amount member"));

        assertThat(amountMember.getType())
                .as("the money member carries the shared exact-decimal type")
                .isEqualTo(Money.class);
        assertThat(Money.SCALE)
                .as("two decimals, as the record field declares")
                .isEqualTo(2);
        assertThat(mappedResponse(null).amount().amount().scale())
                .as("the published value carries that scale, not merely a comparable value")
                .isEqualTo(Money.SCALE);
    }

    /**
     * The service decides no navigation, so it declares no program name.
     *
     * <p>The reference program decides where control goes next. At line 125 of
     * {@code app/cbl/COTRN01C.cbl} the fifth function key is handled by moving the browse program's name
     * into the control-transfer field at line 126 and performing the return paragraph at line 127, whose
     * transfer of control sits at line 197. The fourth key clears the screen at lines 123 and 124, and
     * any other key raises the error flag at line 129 and moves the fifty-character invalid-key constant
     * declared at line 20 of {@code app/cpy/CSMSG01Y.cpy} into the message at line 130. The single
     * return sits at line 136.</p>
     *
     * <p>Assumptions: on THIS screen the fifth key navigates and does not save. Across the online
     * programs that key most often commits an edit, so the natural reading is wrong here: lines 125 to
     * 127 perform a transfer of control to the browse program and this program contains no write of any
     * kind. A migrated path that treated the fifth key as a save would introduce a write to a read-only
     * screen.</p>
     *
     * <p>Refactoring Rationale: none of that behaviour is transcribed into this service, and its absence
     * is the decision this case records. Key handling and the choice of the next program are navigation,
     * which the migrated client owns as a route change; a stateless service that returned the name of a
     * program to visit next would be re-creating the passed session structure the migration removes. So
     * the assertion is a negative: no publicly readable constant on this class carries a program name.
     * </p>
     *
     * <p>Assumptions: the three names looked for are the ones the reference program itself moves into
     * the control-transfer field -- the browse program at line 126, the menu program at line 117 and the
     * sign-on program at line 95 -- rather than an invented list, so the case fails only if this class
     * starts holding a destination the reference actually names.</p>
     *
     * @throws IllegalAccessException if a publicly readable constant cannot be read, propagated from the
     *     reflective helper this case reads the constants through
     */
    @Test
    @DisplayName("the service decides no navigation and names no program")
    void theServiceDecidesNoNavigation() throws IllegalAccessException {
        List<String> constants = publicTextConstants();

        assertThat(constants)
                .as("navigation is the client's, so no constant here names a destination program")
                .noneMatch(value -> value.contains("COTRN00C")
                        || value.contains("COMEN01C")
                        || value.contains("COSGN00C"));
    }

    /**
     * Neither collaborator may be absent at construction.
     *
     * <p>This case pins NO reference paragraph, and the absence of a citation is deliberate rather than
     * an omission. Every other case above names the lines of {@code app/cbl/COTRN01C.cbl} it
     * transcribes; this one has nothing to name, because the reference program has no constructor to
     * transcribe. Its file and its working storage are declared statically and are resolved by the
     * region at start-up, so there is no moment in it at which a collaborator could be supplied or
     * omitted. Inventing a line citation here would attach a reference authority to a target-side
     * wiring invariant that the reference does not have.</p>
     *
     * <p>Assumptions: the check is asserted at CONSTRUCTION rather than at first use. A container that
     * cannot supply one of the two then fails while the context is being built, naming the member,
     * instead of answering the first request of the day with a dereference whose stack trace names a line
     * inside the service rather than the wiring that actually differs. That is the target-side analogue
     * of the region refusing to start a transaction whose file is not defined to it.</p>
     */
    @Test
    @DisplayName("neither collaborator may be absent at construction")
    void neitherCollaboratorMayBeAbsentAtConstruction() {
        assertThatThrownBy(() -> new TransactionViewService(null, transactionMapper))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionViewService(repository, null))
                .isInstanceOf(NullPointerException.class);
    }
}
