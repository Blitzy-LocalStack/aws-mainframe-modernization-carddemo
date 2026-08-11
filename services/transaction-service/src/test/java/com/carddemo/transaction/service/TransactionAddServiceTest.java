package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.error.RecordConflictException;
import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.common.validation.DateEditValidator;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionAddRequest;
import com.carddemo.transaction.dto.TransactionAddResponse;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Pins the "Transaction Add" capture screen against the paragraphs of {@code COTRN02C}.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link TransactionAddService} transcribes {@code app/cbl/COTRN02C.cbl}, 783 lines, transaction
 * {@code CT02}, named "Transaction Add" in the inventory table of the repository root
 * {@code README.md} at line 300. That program carries the largest validation surface of the four
 * this package pins, and this class holds the transcription to five obligations: the three-way key
 * selection and which of two supplied keys wins, the eight validation blocks of the data phase and
 * the single sentence each of them can publish, the derivation of the next identifier from a
 * descending single-row probe, the three outcomes of the append, and the confirmation evaluation
 * that decides whether the append is reached at all.</p>
 *
 * <p>Assumptions: the screen name is taken from the inventory row rather than paraphrased. The
 * plausible mis-citation is "Add Transaction", which reads naturally beside a create endpoint and
 * appears nowhere in that table, so a method named for it could not be found by an operator
 * searching for the screen they are debugging.</p>
 *
 * <p>Assumptions: the inventory row is cited at line 300 as the tree stands, and the five online
 * rows occupy lines 298 to 302. The pristine baseline carried the same rows twenty lines earlier,
 * with this one at line 280, because the migration section this plan adds to {@code README.md} moved
 * them down. Both figures are recorded here and in the charter beside this class, so a reader
 * meeting two citations of one table reads a shift with a known cause rather than an error in either
 * file. The verifiable claim is the row content; the line number is the convenience.</p>
 *
 * <p>Parameters, return values, exceptions or errors. This type is a test class: no caller
 * constructs it, it yields no value and it raises nothing outside the test engine, so the type
 * itself accepts no parameter, returns nothing and throws nothing. The inapplicability is stated
 * rather than passed over, because the user-specified Explainability rule forbids a docstring that
 * omits parameters, return values or purpose, and a reader has to be able to tell a declared
 * inapplicability from an oversight. Every member below carries its own at-clauses.</p>
 *
 * <h2>Alternatives Considered: plain stand-ins rather than an application context</h2>
 *
 * <p>Alternatives Considered: starting a framework context for these cases, whole or sliced to the
 * web layer, and reaching persistence through containers. All were rejected. The unit under test is
 * a stateless class whose four collaborators arrive through its constructor, so every branch below
 * is reachable by handing it four stand-ins; a context would add startup cost and a second failure
 * mode -- wiring -- to cases that are about transcription. The container-backed machinery declared
 * in {@code src/test/resources/application-test.yml} exists for the {@code RepositoryIT} classes in
 * the sibling {@code repository} package, and that file is read here rather than duplicated. Two of
 * the three append outcomes are additionally unreachable against a working engine on demand: an
 * integrity violation and a store failure cannot be provoked underneath a real connection at will.
 * </p>
 *
 * <p>Trade-offs: the extension form used here applies strict stubbing, so a stubbing no case
 * exercises fails the build. The cost is that the write span's stand-in status has to be supplied
 * only by the cases that actually reach the span, which is why {@link #writeSpanRunsInline()} is a
 * call each such case makes rather than a blanket arrangement. That cost is accepted because
 * several cases below assert a negative -- that the append is never reached, or that one direction
 * of the cross-reference is never called -- and a drifting stub is exactly how such a negative rots
 * into a case that passes while proving nothing.</p>
 *
 * <h2>Alternatives Considered: the cross-reference is a mocked PORT, and no breaker is added</h2>
 *
 * <p>Assumptions: {@code READ-CXACAIX-FILE} at line 576 and {@code READ-CCXREF-FILE} at line 609 of
 * {@code app/cbl/COTRN02C.cbl} read the card cross-reference, which the ACCOUNT context owns
 * together with the by-account index that replaces the {@code CXACAIX} alternate index. The
 * production service therefore reaches it over synchronous HTTP through
 * {@link AccountContextClient}, and it is that INTERFACE which is stood in for here. Nothing below
 * reaches the cross-reference through a repository over the account schema, and no type from the
 * account context's domain package is imported in either direction.</p>
 *
 * <p>Alternatives Considered: adding a circuit breaker or a resilience library around that hop, and
 * asserting its behaviour here. Rejected, and the rejection is recorded at the class level because
 * three cases below stand on it. The only synchronous service-to-service hops in this deployment
 * are in-VPC to an internal load balancer under an explicit connect and read timeout, so a breaker
 * would add a failure mode -- a tripped breaker refusing calls the dependency would have answered
 * -- without removing one, while the timeouts already bound the wait a caller can be made to
 * endure. The two failure shapes that do exist are therefore the only two asserted: an answer
 * carrying no entry, and a transport failure the seam reports as its own unavailability.</p>
 *
 * <h2>Alternatives Considered: the record boundary is held REAL</h2>
 *
 * <p>Alternatives Considered: standing in for {@link TransactionMapper} as the sibling class that
 * pins the detail screen does. Rejected here, and the divergence between two classes of one package
 * is deliberate rather than an inconsistency. What the detail screen asserts is that its service
 * DELEGATES the conversion, so a stand-in is exactly right there. What this class asserts includes
 * which value lands in the stored row's card column at line 459, what shape the two timestamp
 * columns carry after lines 464 and 465, and that the amount the answer publishes is the value that
 * survived the reference's edited picture. A stand-in would answer all three from a literal written
 * in this file, so each assertion would hold whatever the transcription did. The conversion is a
 * pure function over its arguments, so holding it real adds no wiring and no ambient state.</p>
 *
 * <h2>Refactoring Rationale: one sentence in the reference, a per-field array in the target</h2>
 *
 * <p>Refactoring Rationale: the baseline short-circuits at the FIRST failure. Every validation arm
 * ends with {@code PERFORM SEND-TRNADD-SCREEN}, whose paragraph at line 516 reaches
 * {@code EXEC CICS RETURN} at lines 530 to 534 and ends the CICS task; only two such verbs exist in
 * the whole program, at lines 156 and 530. The baseline therefore emits exactly ONE sentence per
 * failing request and never a collection. The target instead reports one refusal that names one
 * field and carries one state, which the shared kernel renders as a per-field array, and the
 * divergence is documented rather than absorbed. No case below asserts two simultaneous sentences
 * as reference behaviour; several assert the opposite, that the second complaint is unreachable
 * while the first stands.</p>
 *
 * <p>Assumptions: the per-field array's own shape comes from {@code app/cpy/CSSETATY.cpy} lines 17
 * to 27, a templated block that moves a highlight colour into a field whose flag is not-acceptable
 * or blank at lines 18 to 22 and, for the blank case only, additionally moves an asterisk into the
 * field's data at lines 23 to 25. A not-acceptable flag therefore draws the colour alone and a blank
 * flag draws the colour and the marker, which is why the cases below distinguish
 * {@link FieldValidationFlag#NOT_OK} from {@link FieldValidationFlag#BLANK} rather than treating
 * every refusal alike. Line 20 additionally gates the whole template on the pseudo-conversational
 * re-entry discriminator, and the target severs that gate entirely: the array is populated on every
 * failing request and never on a remembered turn count.</p>
 *
 * <h2>Assumptions: the reference declares no explicit syncpoint anywhere</h2>
 *
 * <p>Assumptions: the count of {@code SYNCPOINT} across all 783 lines of
 * {@code app/cbl/COTRN02C.cbl} is zero, so no case below cites one. Atomicity in the baseline is
 * the CICS task's own implicit syncpoint at {@code EXEC CICS RETURN}, and the unit of work that
 * therefore has to be reproduced is the identifier derivation together with the one file write of
 * {@code ADD-TRANSACTION} at line 442, expressed in the target as an explicit programmatic
 * transaction boundary. A rollback has no counterpart at all, so a failure propagates as an
 * exception; that is what {@link #aFailingWriteReportsTheFailedAddSentence()} observes.</p>
 *
 * <h2>Alternatives Considered: a descending single-row probe, never a positional skip</h2>
 *
 * <p>Alternatives Considered: reading the last identifier by counting rows past a position. The
 * count of {@code READNEXT} in the program is also zero, so lines 444 to 449 are not a paged browse
 * at all: line 444 positions past the end of the key range, lines 445 to 447 open one backward read
 * and close it, and lines 448 and 449 move that key into a numeric work field and add one. The
 * target expression is therefore a single descending row -- the degenerate case of the same
 * key-ordered read the sibling browse screen uses -- and a query that skipped a row count was
 * rejected because under concurrent inserts it answers with a different row than the key ordering
 * does, which would change a value the reference derives deterministically. Nothing below frames
 * this probe as a page.</p>
 *
 * <h2>Assumptions: NO golden master covers this program</h2>
 *
 * <p>Assumptions: no golden master exists for this path and none is claimed.
 * {@code tests/README.md} lines 83 to 85 record that the online {@code CO*} programs cannot be run
 * end to end without a CICS runtime, which the runner does not have, so only their extractable
 * validation logic is unit-tested. {@code COTRN02C} is one of those programs. Parity therefore
 * rests on two things and is asserted as such: validation logic transcribed branch by branch with
 * each branch cited to its line, and the copybook record contract of
 * {@code app/cpy/CVTRA05Y.cpy}. No assertion below is justified by pointing at a committed output
 * file, and nothing here creates, regenerates or reads anything under the oracle's fixture or
 * golden trees.</p>
 *
 * <p>Assumptions: the graded condition-code rubric the oracle aggregates belongs to that suite
 * alone. This class runs under a gate that is binary -- a case either passes or fails -- so nothing
 * below tolerates a degree of failure, performs arithmetic on a return code, or is wired into the
 * oracle's pipeline. The one graded value that is genuinely part of a COBOL specification, the
 * warn-level code the nightly posting program sets at lines 229 and 230 of
 * {@code app/cbl/CBTRN02C.cbl}, belongs to a batch module and is not this screen's concern.</p>
 *
 * <h2>Assumptions: determinism is supplied, never read</h2>
 *
 * <p>Assumptions: no case below reads an ambient clock, and none needs to. The two timestamp
 * columns this screen writes are taken from the two submitted dates at lines 464 and 465, so every
 * value a case compares is a literal it supplied. That is why
 * {@code src/test/resources/application-test.yml} carries no clock property at all, as its own
 * lines 68 to 78 record: determinism for the twenty-six-character rendering is supplied to
 * {@link TimestampFormatter} as an argument, and that file is read here rather than duplicated.
 * Normalising an ambient reading before comparison is the oracle's technique for a batch program
 * compared against a committed file, and there is no such file and no ambient reading here. Each
 * case builds its own submission and its own stand-ins, sharing no mutable state, which is what
 * keeps this class safe to run in parallel with any other.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the capture screen: three key branches, eight validation blocks, three append outcomes")
class TransactionAddServiceTest {

    /** The eleven-digit account identifier every keyed case submits, already at the key width. */
    private static final String ACCOUNT_ID = "00000000011";

    /**
     * The sixteen-digit primary account number the cross-reference answers with.
     *
     * <p>Refactoring Rationale: this member is named for the column it lands in rather than "the
     * account number", which names a different field. {@code TRAN-CARD-NUM} at line 15 of
     * {@code app/cpy/CVTRA05Y.cpy} is a sixteen-character primary account number; the account
     * identifier is the eleven-digit key that reaches this context through the cross-reference. A
     * reader who took the two for one field could not read the precedence case below at all, since
     * its whole claim is that one of them replaces the other.</p>
     */
    private static final String RESOLVED_CARD_NUMBER = "4111111111111111";

    /** A different sixteen-digit card number, submitted so the precedence case can discard it. */
    private static final String SUBMITTED_CARD_NUMBER = "5500000000000004";

    /** The stored maximum identifier the populated-table cases answer the probe with. */
    private static final String STORED_MAXIMUM = "0000000000000008";

    /** The identifier the populated-table cases expect, being the stored maximum plus one. */
    private static final String NEXT_IDENTIFIER = "0000000000000009";

    /** The identifier an exhausted probe yields, being zero plus one at the declared width. */
    private static final String FIRST_IDENTIFIER = "0000000000000001";

    /** The origination date every accepted submission carries, in the mask the screen declares. */
    private static final String ORIGIN_DATE = "2026-01-15";

    /** The processing date every accepted submission carries, distinct from the origination one. */
    private static final String PROCESS_DATE = "2026-01-16";

    /**
     * A well-shaped date the reference's date service declines with the forgiven message number.
     *
     * <p>Assumptions: this value is before the supported calendar's lower bound rather than merely
     * unusual, which is what makes the date service answer with the one message number the
     * reference forgives. The cases that use it assert that number directly, so the value cannot
     * quietly become an ordinary acceptable date and leave the suppression untested.</p>
     */
    private static final String OUT_OF_RANGE_DATE = "1200-01-15";

    /** A date of the declared shape that denotes no calendar day, February having 28 days in 2026. */
    private static final String IMPOSSIBLE_DATE = "2026-02-31";

    /** The keyed reader of the owned transaction table, standing in for the reference dataset. */
    @Mock
    private TransactionRepository transactions;

    /**
     * The seam onto the account context's cross-reference, standing in for the two reference reads.
     *
     * <p>Assumptions: this is an INTERFACE stand-in and not a repository. The class-level note on
     * the mocked port records why, and why no breaker sits behind it.</p>
     */
    @Mock
    private AccountContextClient accounts;

    /** The manager the service builds its write span over, so the span can be observed. */
    @Mock
    private PlatformTransactionManager transactionManager;

    /** The record boundary, held real so the stored row and the answer are both observable. */
    private TransactionMapper transactionMapper;

    /** The unit under test, rebuilt over fresh stand-ins before each case. */
    private TransactionAddService service;

    /**
     * Builds a service over freshly created stand-ins before every case.
     *
     * <p>Assumptions: the service is rebuilt rather than shared because it is constructed around its
     * four collaborators, and a service retained across cases would hold the previous case's
     * arrangements. The class under test keeps no mutable instance state of its own beyond the write
     * template it derives from the manager, so rebuilding it costs nothing.</p>
     */
    @BeforeEach
    void setUp() {
        this.transactionMapper = new TransactionMapper();
        this.service = new TransactionAddService(this.transactions, this.accounts,
                this.transactionMapper, this.transactionManager);
    }

    /**
     * One arm of a validation construct, named for the field it guards and the line that guards it.
     *
     * <p>Assumptions: the expected sentence is carried here as a reference to the published constant
     * rather than re-typed as a literal, because the sentence itself is the contract and two
     * spellings of one contract drift apart. What each case then proves is that the named arm selects
     * that constant, not that this file can copy a string.</p>
     *
     * <p>Alternatives Considered: carrying the sentence as a literal so the case would fail if the
     * published constant were edited. Rejected because the constants are asserted verbatim against
     * the reference program in one place -- {@link #everyPublishedSentenceIsTheReferenceSentence()}
     * -- and spreading the same literal across a dozen arms would mean a wording change failed twelve
     * cases without any of them naming the wording as its subject.</p>
     *
     * @param field the submitted component this arm guards, spelled as the service's own field key
     *     names it, so a failure names the field a client would see
     * @param referenceLine the line of {@code app/cbl/COTRN02C.cbl} carrying this arm's sentence, so
     *     a failure cites the reference rather than only the expected text
     * @param expectedSentence the sentence this arm publishes, taken from the published constant
     * @param expectedState the validation state the refusal carries, being
     *     {@link FieldValidationFlag#BLANK} where the reference's template would draw its asterisk
     *     and {@link FieldValidationFlag#NOT_OK} where it would draw the colour alone
     */
    private record ValidationArm(String field, int referenceLine, String expectedSentence,
            FieldValidationFlag expectedState) {

        /**
         * Names this arm's field and the reference line it was transcribed from.
         *
         * <p>Assumptions: this form is what a parameterized case name shows, so it is kept to the two
         * facts that identify the case. The generated record form would print the whole sentence and
         * push the field name off the end of a console line, which makes a run of eleven cases
         * unreadable.</p>
         *
         * @return the field key followed by the reference line that declares its sentence
         */
        @Override
        public String toString() {
            return field + " at line " + referenceLine;
        }
    }

    /**
     * Supplies the eleven arms of the mandatory-field construct in the reference's written order.
     *
     * <p>Assumptions: the order below is the order lines 252 to 316 of
     * {@code app/cbl/COTRN02C.cbl} write the arms in, and that order is load-bearing rather than
     * cosmetic: the construct is one {@code EVALUATE TRUE} whose arms all end the CICS task, so the
     * written order IS the priority order and only the first satisfied arm can ever publish. The
     * exhaustive case below walks it, and the short-circuit case relies on the first entry being the
     * type code.</p>
     *
     * <p>Assumptions: every arm carries {@link FieldValidationFlag#BLANK} because every one of them
     * fires on a component that was never supplied, which is the state
     * {@code app/cpy/CSSETATY.cpy} lines 23 to 25 draw the asterisk for.</p>
     *
     * @return the eleven arms, each wrapped as a single parameterized argument, in reference order
     */
    private static Stream<Arguments> mandatoryFieldArms() {
        return Stream.of(
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_TYPE_CODE, 254,
                        TransactionAddRequest.TYPE_CODE_REQUIRED, FieldValidationFlag.BLANK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_CATEGORY_CODE, 260,
                        TransactionAddRequest.CATEGORY_CODE_REQUIRED, FieldValidationFlag.BLANK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_SOURCE, 266,
                        TransactionAddRequest.SOURCE_REQUIRED, FieldValidationFlag.BLANK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_DESCRIPTION, 272,
                        TransactionAddRequest.DESCRIPTION_REQUIRED, FieldValidationFlag.BLANK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_AMOUNT, 278,
                        TransactionAddRequest.AMOUNT_REQUIRED, FieldValidationFlag.BLANK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_ORIGIN_DATE, 284,
                        TransactionAddRequest.ORIGIN_DATE_REQUIRED, FieldValidationFlag.BLANK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_PROCESS_DATE, 290,
                        TransactionAddRequest.PROCESS_DATE_REQUIRED, FieldValidationFlag.BLANK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_MERCHANT_ID, 296,
                        TransactionAddRequest.MERCHANT_ID_REQUIRED, FieldValidationFlag.BLANK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_MERCHANT_NAME, 302,
                        TransactionAddRequest.MERCHANT_NAME_REQUIRED, FieldValidationFlag.BLANK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_MERCHANT_CITY, 308,
                        TransactionAddRequest.MERCHANT_CITY_REQUIRED, FieldValidationFlag.BLANK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_MERCHANT_ZIP, 314,
                        TransactionAddRequest.MERCHANT_ZIP_REQUIRED, FieldValidationFlag.BLANK)));
    }

    /**
     * Supplies the three composition arms that demand digits, each with its own sentence.
     *
     * <p>Assumptions: two of the three sit in the construct at lines 322 to 337 and the third is a
     * separate conditional at lines 430 to 436, and all three are grouped here because what a case
     * observes is identical in each: a value of the declared width whose characters are not all
     * digits is refused with that field's own composition sentence and the not-acceptable state.</p>
     *
     * <p>Assumptions: each offending value below is supplied at the width its screen field declares
     * in {@code app/cpy-bms/COTRN02.CPY} -- two characters for the type code at line 72, four for the
     * category code at line 78 and nine for the merchant identifier at line 114 -- and differs from an
     * accepted value in one trailing character. A value of the wrong width would be refused for its
     * width and the case would then prove nothing about composition.</p>
     *
     * @return one argument per arm, each carrying the arm and the offending value to submit
     */
    private static Stream<Arguments> digitOnlyArms() {
        return Stream.of(
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_TYPE_CODE, 325,
                        TransactionAddRequest.TYPE_CODE_NOT_NUMERIC, FieldValidationFlag.NOT_OK),
                        "0A"),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_CATEGORY_CODE, 331,
                        TransactionAddRequest.CATEGORY_CODE_NOT_NUMERIC,
                        FieldValidationFlag.NOT_OK), "000A"),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_MERCHANT_ID, 432,
                        TransactionAddRequest.MERCHANT_ID_NOT_NUMERIC, FieldValidationFlag.NOT_OK),
                        "00000000A"));
    }

    /**
     * Supplies the two arms that refuse a date whose characters do not sit where the mask says.
     *
     * <p>Assumptions: the two constructs at lines 353 to 366 and 368 to 381 are mirror images of one
     * another, five alternatives each testing one character position, and they publish two DIFFERENT
     * sentences. Grouping them in one family and carrying the sentence per arm is what keeps the pair
     * from being unified into a single message, which is the plausible simplification.</p>
     *
     * <p>Assumptions: the offending value keeps the ten-character width the screen field declares and
     * carries a separator the mask does not name at positions five and eight, which is the second and
     * fourth alternative of each construct. A shorter value would fail the width instead and the case
     * would then not reach the position tests at all.</p>
     *
     * @return one argument per arm, each carrying the arm and the offending value to submit
     */
    private static Stream<Arguments> lexicalDateArms() {
        return Stream.of(
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_ORIGIN_DATE, 360,
                        TransactionAddRequest.ORIGIN_DATE_FORMAT, FieldValidationFlag.NOT_OK),
                        "2026/01/15"),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_PROCESS_DATE, 375,
                        TransactionAddRequest.PROCESS_DATE_FORMAT, FieldValidationFlag.NOT_OK),
                        "2026/01/16"));
    }

    /**
     * Supplies the two arms that refuse a well-shaped date denoting no calendar day.
     *
     * <p>Assumptions: these are the semantic halves at lines 389 to 407 and 409 to 425, each calling
     * the reference's date service and each publishing its own sentence. They are separate from the
     * positional family above because a value can fail one and pass the other, and the four
     * sentences between the two families are all distinct.</p>
     *
     * @return the two arms, each wrapped as a single parameterized argument
     */
    private static Stream<Arguments> calendarDateArms() {
        return Stream.of(
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_ORIGIN_DATE, 401,
                        TransactionAddService.MESSAGE_ORIGIN_DATE_INVALID,
                        FieldValidationFlag.NOT_OK)),
                Arguments.of(new ValidationArm(TransactionAddService.FIELD_PROCESS_DATE, 421,
                        TransactionAddService.MESSAGE_PROCESS_DATE_INVALID,
                        FieldValidationFlag.NOT_OK)));
    }

    /**
     * Supplies amounts no rendering of the reference's twelve-character edited picture can hold.
     *
     * <p>Assumptions: the four alternatives at lines 340 to 343 test character POSITIONS -- the sign
     * at one, eight digits from two, the point at ten, two digits from eleven -- and share a single
     * action, so they form a disjunction that publishes one sentence. Each value below fails a
     * different alternative, and each is expected to answer with the same sentence, which is what
     * makes the disjunction observable from outside.</p>
     *
     * @return one argument per value, each carrying the amount and a label naming the alternative it
     *     fails, in the order the alternatives are written
     */
    private static Stream<Arguments> amountsTheEditedPictureCannotHold() {
        return Stream.of(
                Arguments.of(Money.of("100000000.00"), "nine integer digits, line 341"),
                Arguments.of(Money.of("-100000000.00"), "nine integer digits and a sign, line 341"),
                Arguments.of(Money.of("9999999999.99"), "the widest value the type admits, line 341"));
    }


    /**
     * Builds a submission every validation block accepts, keyed by whichever field is supplied.
     *
     * <p>Assumptions: the components are supplied in the order the canonical constructor declares
     * them, which places the account identifier first, then the twelve record-derived components in
     * the order lines 6 to 17 of {@code app/cpy/CVTRA05Y.cpy} declare them, and the confirmation
     * last. No identifier component exists on this shape at all, because the identifier is derived
     * at lines 444 to 449 rather than submitted.</p>
     *
     * <p>Assumptions: every value here clears every block, so a case that wants one block to fire
     * replaces exactly one component and the failure it observes can only be that block's. A
     * submission that failed two blocks would report the earlier one and prove nothing about the
     * later.</p>
     *
     * @param accountId the account identifier to submit, of type {@code String}, empty to leave the
     *     account direction unsupplied
     * @param cardNumber the card number to submit, of type {@code String}, empty to leave the card
     *     direction unsupplied
     * @param confirmation the confirmation discriminator to submit, of type {@code String}, empty for
     *     the never-supplied spelling
     * @return a {@link TransactionAddRequest} carrying all fourteen components, never {@code null}
     */
    private static TransactionAddRequest submission(String accountId, String cardNumber,
            String confirmation) {
        return new TransactionAddRequest(accountId, "01", "0001", "POS TERM", "GROCERY PURCHASE",
                Money.of("125.50"), "123456789", "CORNER STORE", "SEATTLE", "98101", cardNumber,
                ORIGIN_DATE, PROCESS_DATE, confirmation);
    }

    /**
     * Builds a submission differing from the original in exactly one text component.
     *
     * <p>Assumptions: the field key must be one of the ten text components a validation block
     * guards, and the guard below makes a mistyped key fail at the call site rather than silently
     * returning the original unchanged. A helper that ignored an unknown key would turn a typo into a
     * case that exercised nothing while still passing.</p>
     *
     * <p>Alternatives Considered: ten separate single-purpose helpers, one per component. Rejected
     * because the parameterized families below choose their component at run time from the arm they
     * were handed, so a set of compile-time helpers could not be reached from them at all.</p>
     *
     * @param original the submission to derive from; must not be {@code null}
     * @param field the field key naming the component to replace, of type {@code String}, one of the
     *     ten text components the service's own field keys name
     * @param value the value to place in that component, of type {@code String}, empty for the
     *     never-supplied spelling
     * @return a {@link TransactionAddRequest} identical to {@code original} but for that one
     *     component, never {@code null}
     */
    private static TransactionAddRequest replacing(TransactionAddRequest original, String field,
            String value) {
        assertThat(replaceableFields())
                .as("the field key names a text component a validation block guards")
                .contains(field);

        return new TransactionAddRequest(
                original.accountId(),
                TransactionAddService.FIELD_TYPE_CODE.equals(field) ? value : original.typeCode(),
                TransactionAddService.FIELD_CATEGORY_CODE.equals(field)
                        ? value : original.categoryCode(),
                TransactionAddService.FIELD_SOURCE.equals(field) ? value : original.source(),
                TransactionAddService.FIELD_DESCRIPTION.equals(field)
                        ? value : original.description(),
                original.amount(),
                TransactionAddService.FIELD_MERCHANT_ID.equals(field)
                        ? value : original.merchantId(),
                TransactionAddService.FIELD_MERCHANT_NAME.equals(field)
                        ? value : original.merchantName(),
                TransactionAddService.FIELD_MERCHANT_CITY.equals(field)
                        ? value : original.merchantCity(),
                TransactionAddService.FIELD_MERCHANT_ZIP.equals(field)
                        ? value : original.merchantZip(),
                original.cardNumber(),
                TransactionAddService.FIELD_ORIGIN_DATE.equals(field) ? value : original.originDate(),
                TransactionAddService.FIELD_PROCESS_DATE.equals(field)
                        ? value : original.processDate(),
                original.confirmation());
    }

    /**
     * Lists the ten text components {@link #replacing(TransactionAddRequest, String, String)} knows.
     *
     * <p>Assumptions: the amount is absent from this list on purpose. It is the one guarded component
     * that is not text, so it is replaced through {@link #withAmount(TransactionAddRequest, Money)}
     * instead, which keeps the money path in the money type rather than routing a value through a
     * string on its way to an assertion.</p>
     *
     * @return the ten field keys, in the order the mandatory construct guards them
     */
    private static List<String> replaceableFields() {
        return List.of(TransactionAddService.FIELD_TYPE_CODE,
                TransactionAddService.FIELD_CATEGORY_CODE,
                TransactionAddService.FIELD_SOURCE,
                TransactionAddService.FIELD_DESCRIPTION,
                TransactionAddService.FIELD_ORIGIN_DATE,
                TransactionAddService.FIELD_PROCESS_DATE,
                TransactionAddService.FIELD_MERCHANT_ID,
                TransactionAddService.FIELD_MERCHANT_NAME,
                TransactionAddService.FIELD_MERCHANT_CITY,
                TransactionAddService.FIELD_MERCHANT_ZIP);
    }

    /**
     * Builds a submission differing from the original only in its amount.
     *
     * <p>Alternatives Considered: routing the amount through
     * {@link #replacing(TransactionAddRequest, String, String)} alongside the ten text components.
     * Rejected because that helper substitutes one {@code String} component and the amount is the one
     * component declared as {@link Money}, so the generic form cannot express it. Handing
     * {@code null} here is also the only way to spell a never-supplied amount, a {@link Money}
     * having no blank spelling the way a text component does.</p>
     *
     * @param original the submission to derive from; must not be {@code null}
     * @param amount the amount to submit, of type {@link Money}, or {@code null} for the
     *     never-supplied spelling of a component that has no blank string
     * @return a {@link TransactionAddRequest} identical to {@code original} but for the amount, never
     *     {@code null}
     */
    private static TransactionAddRequest withAmount(TransactionAddRequest original, Money amount) {
        return new TransactionAddRequest(original.accountId(), original.typeCode(),
                original.categoryCode(), original.source(), original.description(), amount,
                original.merchantId(), original.merchantName(), original.merchantCity(),
                original.merchantZip(), original.cardNumber(), original.originDate(),
                original.processDate(), original.confirmation());
    }

    /**
     * Builds a stored row for the copy path to read back.
     *
     * <p>Assumptions: the two timestamp members are given midnight readings because a row this screen
     * wrote carries only a date, per lines 464 and 465. The copy at lines 487 and 488 moves a
     * twenty-six-character record field into a ten-character screen field, so a row carrying a time
     * would lose it; giving the row midnight makes the round trip exact and keeps the case about the
     * copy rather than about truncation.</p>
     *
     * @param tranId the identifier the stored row carries, of type {@code String}, at the sixteen
     *     characters the key column declares
     * @return a {@link Transaction} carrying all thirteen mapped members, never {@code null}
     */
    private static Transaction storedRow(String tranId) {
        return new Transaction(tranId, "02", "0002", "ATM TERM", "FUEL PURCHASE",
                Money.of("42.75").amount(), 987654321L, "FUEL STOP", "TACOMA", "98402",
                RESOLVED_CARD_NUMBER, LocalDateTime.of(2026, 1, 10, 0, 0),
                LocalDateTime.of(2026, 1, 11, 0, 0));
    }

    /**
     * Arranges the account-keyed cross-reference read to answer with an entry.
     *
     * <p>Alternatives Considered: arranging the read with a wildcard argument matcher. Rejected
     * because an argument-matched arrangement is what makes a submission keyed on anything other than
     * {@code ACCOUNT_ID} behave as an unarranged read, naming the mis-keyed submission at the point it
     * happens; a wildcard would answer every key alike, so a case built on a mistyped one would still
     * pass while proving nothing.</p>
     *
     * @param cardNumber the card number the cross-reference entry carries, of type {@code String};
     *     must not be {@code null}
     */
    private void accountResolvesTo(String cardNumber) {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(new AccountContextClient.CardXref(ACCOUNT_ID, cardNumber)));
    }

    /**
     * Arranges the card-keyed cross-reference read to answer with an entry.
     *
     * <p>Assumptions: the entry answers with the SAME card number it was keyed on, because line 223
     * moves only the account identifier back onto the screen and leaves the card field as the
     * operator typed it. The account direction is the one that replaces a field, not this one.</p>
     */
    private void cardResolvesToItself() {
        when(this.accounts.findCardXrefByCardNumber(RESOLVED_CARD_NUMBER))
                .thenReturn(Optional.of(
                        new AccountContextClient.CardXref(ACCOUNT_ID, RESOLVED_CARD_NUMBER)));
    }

    /** Arranges the descending probe to answer that the table holds no row. */
    private void theTableHoldsNoRow() {
        when(this.transactions.findMaxTranId()).thenReturn(Optional.empty());
    }

    /**
     * Arranges the descending probe to answer with a stored maximum identifier.
     *
     * @param stored the identifier the probe answers with, of type {@code String}; must not be
     *     {@code null}
     */
    private void theTableMaximumIs(String stored) {
        when(this.transactions.findMaxTranId()).thenReturn(Optional.of(stored));
    }

    /**
     * Arranges the append to answer with whichever row it was handed.
     *
     * <p>Alternatives Considered: answering with a prepared row rather than echoing the one handed
     * in. Rejected because the row handed to the append is the subject of every stored-column
     * assertion in this file, and a prepared answer would discard it -- those cases would then be
     * reading a row this file wrote rather than the row the service built.</p>
     */
    private void theAppendEchoesTheRow() {
        when(this.transactions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    /**
     * Arranges the write span to run its body without a database.
     *
     * <p>Assumptions: this is arranged only by the cases that actually enter the span, because strict
     * stubbing fails a case whose arrangement goes unused. A case that is refused during validation
     * never reaches the span at all, and several such cases below assert exactly that.</p>
     */
    private void writeSpanRunsInline() {
        when(this.transactionManager.getTransaction(any()))
                .thenAnswer(call -> new SimpleTransactionStatus());
    }

    /**
     * Captures the row the service handed to the append.
     *
     * <p>Assumptions: exactly one append is expected, so the capture also serves as the assertion that
     * the reference's single {@code EXEC CICS WRITE} at lines 713 to 721 became a single insert. A
     * second insert would fail the verification rather than being silently taken as the last one.</p>
     *
     * @return the {@link Transaction} the append received, never {@code null}
     */
    private Transaction appendedRow() {
        ArgumentCaptor<Transaction> appended = ArgumentCaptor.forClass(Transaction.class);
        verify(this.transactions).saveAndFlush(appended.capture());
        return appended.getValue();
    }

    /**
     * Collects the value of every publicly readable sentence the service declares.
     *
     * <p>Assumptions: the members are read reflectively rather than named one by one, so a sentence
     * added later is covered by the band case below without this helper being revisited. The returned
     * order is UNSPECIFIED, because the reflective member enumeration this walks is documented to
     * return members in no particular order; both consumers ask only whether a value is present or
     * how long it is, so neither reads it as a sequence.</p>
     *
     * @return the value of every {@code public static final String} on
     *     {@link TransactionAddService}, in no guaranteed order; callers must treat the result as a
     *     collection of values rather than as a sequence
     * @throws IllegalAccessException if a member reported as publicly readable cannot be read, which
     *     would mean the reflective assumption above no longer holds
     */
    private static List<String> publishedTextConstants() throws IllegalAccessException {
        List<String> values = new ArrayList<>();
        for (Field declared : TransactionAddService.class.getDeclaredFields()) {
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
     * Supplies the two key directions whose submitted value is not composed of digits.
     *
     * <p>Assumptions: each direction guards its own conversion, at line 197 for the account and line
     * 211 for the card, and each publishes its own sentence. They are carried as one family because a
     * case observes the same thing in both: the numeric test runs BEFORE the conversion, so a value
     * the conversion has no defined result for never reaches it.</p>
     *
     * @return one argument per direction, each carrying the account identifier to submit, the card
     *     number to submit, the sentence expected and the field key expected
     */
    private static Stream<Arguments> keysWhoseCharactersAreNotAllDigits() {
        return Stream.of(
                Arguments.of("0000000001A", "", TransactionAddRequest.ACCOUNT_ID_NOT_NUMERIC,
                        TransactionAddService.FIELD_ACCOUNT_ID),
                Arguments.of("", "411111111111111X", TransactionAddRequest.CARD_NUMBER_NOT_NUMERIC,
                        TransactionAddService.FIELD_CARD_NUMBER));
    }

    /**
     * A submission carrying neither key is refused with the blank state before anything is read.
     *
     * <p>This pins the third arm of {@code VALIDATE-INPUT-KEY-FIELDS}, lines 224 to 229 of
     * {@code app/cbl/COTRN02C.cbl}: line 224 is the {@code WHEN OTHER} the construct opened at line
     * 195 falls through to, line 225 raises the error flag, line 226 carries the sentence and line 228
     * positions the cursor on the account field before line 229 re-sends the screen.</p>
     *
     * <p>Assumptions: the state asserted is {@link FieldValidationFlag#BLANK} and not
     * {@link FieldValidationFlag#NOT_OK}, because this arm is reached when neither control was filled
     * in rather than when one carried a value the screen refuses. Only the blank state asks the shared
     * kernel to draw the asterisk of {@code app/cpy/CSSETATY.cpy} lines 23 to 25, so the two states
     * are not interchangeable in what the operator is shown.</p>
     *
     * <p>Assumptions: the field named is the account identifier because line 228 positions the cursor
     * there, even though the arm complains about both controls. The reference chooses one field to
     * point at, and pointing at a different one would move the operator's cursor.</p>
     */
    @Test
    @DisplayName("neither key supplied: refused with the blank state, and nothing is read")
    void neitherKeySuppliedIsRefusedWithTheBlankState() {
        assertThatThrownBy(() -> this.service.addTransaction(submission("", "", "Y")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddService.MESSAGE_KEY_REQUIRED)
                .satisfies(failure -> {
                    ClientInputException refusal = (ClientInputException) failure;
                    assertThat(refusal.code())
                            .as("a submitted value the screen refuses is a validation refusal")
                            .isEqualTo(ApiError.CODE_VALIDATION);
                    assertThat(refusal.field())
                            .as("line 228 positions the cursor on the account field")
                            .isEqualTo(TransactionAddService.FIELD_ACCOUNT_ID);
                    assertThat(refusal.state())
                            .as("the blank state is what draws the asterisk, not the refused state")
                            .isEqualTo(FieldValidationFlag.BLANK);
                });

        verify(this.accounts, never()).findCardXrefByAccountId(any());
        verify(this.accounts, never()).findCardXrefByCardNumber(any());
        verify(this.transactions, never()).findMaxTranId();
        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * Both keys supplied resolves through the account alone, and the submitted card is discarded.
     *
     * <p>This pins the precedence the construct at lines 195 to 230 of
     * {@code app/cbl/COTRN02C.cbl} establishes. An {@code EVALUATE TRUE} takes the FIRST satisfied
     * alternative, and the account alternative is written first at line 196, so a submission carrying
     * both keys never reaches the card alternative at line 210. Line 208 then reads the cross-reference
     * by account and line 209 moves the entry's card number over the submitted one.</p>
     *
     * <p>Assumptions: what is persisted is the CROSS-REFERENCE's card number and not the operator's
     * entry, and that is observable rather than inferred: line 459 moves the same screen field line
     * 209 has just overwritten into the row's card column. The submitted value is therefore discarded
     * silently, with no sentence reporting the substitution, and a service that stored the submitted
     * value instead would agree with the reference on every message while writing a different row.</p>
     *
     * <p>Assumptions: the card direction is asserted never to be called, because a service that
     * consulted both and preferred one would satisfy the row assertion above while making two remote
     * calls where the reference makes one. The seam is a network hop, so the count is a behaviour.</p>
     */
    @Test
    @DisplayName("both keys supplied: the account direction wins and replaces the submitted card")
    void theAccountDirectionWinsAndTheSubmittedCardIsSilentlyReplaced() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        this.service.addTransaction(submission(ACCOUNT_ID, SUBMITTED_CARD_NUMBER, "Y"));

        assertThat(appendedRow().getCardNum())
                .as("line 459 stores the field line 209 overwrote, so the entry's value is stored")
                .isEqualTo(RESOLVED_CARD_NUMBER)
                .isNotEqualTo(SUBMITTED_CARD_NUMBER);
        verify(this.accounts).findCardXrefByAccountId(ACCOUNT_ID);
        verify(this.accounts, never()).findCardXrefByCardNumber(any());
    }

    /**
     * The card direction is reached only when the account field was left unsupplied.
     *
     * <p>This pins the second arm of {@code VALIDATE-INPUT-KEY-FIELDS}, lines 210 to 223 of
     * {@code app/cbl/COTRN02C.cbl}: line 211 tests the submitted value for digits, lines 218 to 221
     * convert it and write it back at the key width, line 222 reads the cross-reference by card and
     * line 223 moves the entry's account identifier onto the screen.</p>
     *
     * <p>Assumptions: line 223 writes back the ACCOUNT identifier and leaves the card field as the
     * operator typed it, which is the mirror image of the account arm rather than a second overwrite of
     * the same field. The row's card column therefore carries the submitted value on this path, and it
     * carries the entry's value on the other -- the same column, two provenances.</p>
     */
    @Test
    @DisplayName("card only: the card direction resolves and the account direction is never called")
    void theCardDirectionIsReachedOnlyWhenTheAccountFieldIsUnsupplied() {
        cardResolvesToItself();
        theTableHoldsNoRow();
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        this.service.addTransaction(submission("", RESOLVED_CARD_NUMBER, "Y"));

        assertThat(appendedRow().getCardNum())
                .as("the card arm leaves the card field as submitted, per line 223")
                .isEqualTo(RESOLVED_CARD_NUMBER);
        verify(this.accounts).findCardXrefByCardNumber(RESOLVED_CARD_NUMBER);
        verify(this.accounts, never()).findCardXrefByAccountId(any());
    }

    /**
     * A key whose characters are not all digits is refused with that direction's own sentence.
     *
     * <p>This pins the conditional each key arm opens before its conversion: line 197 for the account,
     * whose sentence is at line 199, and line 211 for the card, whose sentence is at line 213, both in
     * {@code app/cbl/COTRN02C.cbl}.</p>
     *
     * <p>Assumptions: the test precedes the conversion because the reference's numeric conversion has
     * no defined result for characters that are not a number, and the reference guards it rather than
     * relying on one. A service that converted first would answer for whatever that conversion
     * happened to produce, which is not a behaviour the reference has.</p>
     *
     * <p>Assumptions: the state is {@link FieldValidationFlag#NOT_OK} here and blank in the third arm,
     * because a value WAS supplied and was refused. The distinction decides whether the operator sees
     * the asterisk marker.</p>
     *
     * @param submittedAccountId the account identifier to submit, of type {@code String}, empty when
     *     the card direction is the one under test
     * @param submittedCardNumber the card number to submit, of type {@code String}, empty when the
     *     account direction is the one under test
     * @param expectedSentence the sentence that direction publishes, of type {@code String}
     * @param expectedField the field key that direction names, of type {@code String}
     */
    @ParameterizedTest(name = "{3} refused with {2}")
    @MethodSource("keysWhoseCharactersAreNotAllDigits")
    void aKeyWhoseCharactersAreNotAllDigitsIsRefused(String submittedAccountId,
            String submittedCardNumber, String expectedSentence, String expectedField) {

        assertThatThrownBy(() -> this.service.addTransaction(
                submission(submittedAccountId, submittedCardNumber, "Y")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(expectedSentence)
                .satisfies(failure -> {
                    ClientInputException refusal = (ClientInputException) failure;
                    assertThat(refusal.field()).isEqualTo(expectedField);
                    assertThat(refusal.state())
                            .as("a supplied value that was refused is not a blank field")
                            .isEqualTo(FieldValidationFlag.NOT_OK);
                });

        verify(this.accounts, never()).findCardXrefByAccountId(any());
        verify(this.accounts, never()).findCardXrefByCardNumber(any());
    }

    /**
     * An account key that resolves to no entry reports the account arm's own absence sentence.
     *
     * <p>This pins the not-found arm of {@code READ-CXACAIX-FILE}, lines 591 to 596 of
     * {@code app/cbl/COTRN02C.cbl}, whose sentence is at line 593, reached from line 208.</p>
     *
     * <p>Assumptions: an absent entry is one of only two failure shapes this seam has, the other
     * being its own unavailability, and the class-level note records why no third shape -- a tripped
     * breaker -- is introduced. An absent entry is reported as an absence rather than as a refusal
     * because the submitted value was well formed and simply matched nothing.</p>
     */
    @Test
    @DisplayName("account key resolving to no entry: the account arm's absence sentence")
    void anAbsentAccountEntryReportsTheAccountArmsAbsenceSentence() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(TransactionAddService.MESSAGE_ACCOUNT_NOT_FOUND);

        assertThat(TransactionAddService.MESSAGE_ACCOUNT_NOT_FOUND)
                .as("the two directions publish two sentences, per lines 593 and 626")
                .isNotEqualTo(TransactionAddService.MESSAGE_CARD_NOT_FOUND);
        verify(this.transactions, never()).findMaxTranId();
    }

    /**
     * A card key that resolves to no entry reports the card arm's own absence sentence.
     *
     * <p>This pins the not-found arm of {@code READ-CCXREF-FILE}, lines 624 to 629 of
     * {@code app/cbl/COTRN02C.cbl}, whose sentence is at line 626, reached from line 222.</p>
     *
     * <p>Assumptions: this case exists alongside its account twin rather than being folded into it,
     * because the two sentences differ and a single case could pass against either. Holding them apart
     * is what stops the pair being unified into one absence message.</p>
     */
    @Test
    @DisplayName("card key resolving to no entry: the card arm's absence sentence")
    void anAbsentCardEntryReportsTheCardArmsAbsenceSentence() {
        when(this.accounts.findCardXrefByCardNumber(RESOLVED_CARD_NUMBER))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                this.service.addTransaction(submission("", RESOLVED_CARD_NUMBER, "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(TransactionAddService.MESSAGE_CARD_NOT_FOUND);

        verify(this.transactions, never()).findMaxTranId();
    }

    /**
     * An unreachable account context reports the direction's own failed-read sentence.
     *
     * <p>This pins the catch-all arms of the two cross-reference paragraphs: lines 597 to 603 of
     * {@code app/cbl/COTRN02C.cbl}, whose sentence is at line 600, and lines 630 to 636, whose
     * sentence is at line 633. The reference reaches them on any response code that is neither normal
     * nor not-found.</p>
     *
     * <p>Alternatives Considered: retrying the hop, or standing a breaker in front of it, and
     * asserting either here. Rejected for the reason recorded at the class level: the hop is in-VPC to
     * an internal load balancer under an explicit connect and read timeout, so a breaker would add a
     * failure mode without removing one, and a declarative retry would need an enabling annotation on
     * a configuration class this package may not add to. What is asserted instead is that the seam's
     * own unavailability becomes the reference's sentence for that direction, unchanged.</p>
     *
     * <p>Assumptions: the two sentences differ, so the direction the failure came from survives into
     * what the operator reads. A single shared failure sentence would lose which read failed.</p>
     */
    @Test
    @DisplayName("unreachable account context: each direction keeps its own failed-read sentence")
    void anUnreachableAccountContextKeepsEachDirectionsFailedReadSentence() {
        when(this.accounts.findCardXrefByAccountId(ACCOUNT_ID))
                .thenThrow(new AccountContextClient.AccountContextUnavailableException(
                        "the account context did not answer", new IllegalStateException("transport")));

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED);

        assertThat(TransactionAddService.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED)
                .as("lines 600 and 633 publish two sentences, one per direction")
                .isNotEqualTo(TransactionAddService.MESSAGE_CARD_XREF_LOOKUP_FAILED);
    }

    /**
     * An unreachable account context on the card direction reports that direction's sentence.
     *
     * <p>This pins the catch-all arm of {@code READ-CCXREF-FILE}, lines 630 to 636 of
     * {@code app/cbl/COTRN02C.cbl}, whose sentence is at line 633.</p>
     *
     * <p>Assumptions: the failure is reported as an internal condition rather than as a refusal,
     * because the operator supplied a well-formed key and can do nothing to correct a transport
     * failure. That is the same treatment the account direction receives, and only the sentence
     * differs.</p>
     */
    @Test
    @DisplayName("unreachable account context on the card direction: line 633's sentence")
    void anUnreachableAccountContextOnTheCardDirectionReportsItsOwnSentence() {
        when(this.accounts.findCardXrefByCardNumber(RESOLVED_CARD_NUMBER))
                .thenThrow(new AccountContextClient.AccountContextUnavailableException(
                        "the account context did not answer", new IllegalStateException("transport")));

        assertThatThrownBy(() ->
                this.service.addTransaction(submission("", RESOLVED_CARD_NUMBER, "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_CARD_XREF_LOOKUP_FAILED);
    }


    /**
     * Supplies the two date fields, so a case can name one of them and leave the other accepted.
     *
     * <p>Assumptions: the two are supplied as a family rather than asserted together, because the
     * reference tolerance under test is written TWICE -- once at line 400 and once at line 420 -- and a
     * case that made both dates offend at once would pass while only one of the two sites forgave.</p>
     *
     * @return one argument per date field, each carrying that field's key
     */
    private static Stream<Arguments> theTwoDateFields() {
        return Stream.of(
                Arguments.of(TransactionAddService.FIELD_ORIGIN_DATE),
                Arguments.of(TransactionAddService.FIELD_PROCESS_DATE));
    }

    /**
     * Each mandatory arm publishes its own sentence when its component was never supplied.
     *
     * <p>This walks the eleven arms of the construct at lines 251 to 320 of
     * {@code app/cbl/COTRN02C.cbl}, each arm's sentence cited by the argument it was handed. Line 251
     * opens one {@code EVALUATE TRUE}, line 318 is its {@code WHEN OTHER}, line 319 continues and line
     * 320 closes it.</p>
     *
     * <p>Assumptions: exactly one component is left unsupplied per case, so the arm that fires can only
     * be that component's. Leaving two unsupplied would report the earlier arm and say nothing about
     * the later one, which is precisely what the short-circuit case below asserts instead.</p>
     *
     * <p>Assumptions: the amount is withheld as an absent value rather than as an empty string,
     * because it is the one guarded component carried as an exact-decimal type and that type has no
     * blank spelling. The reference's screen field is text and its arm at line 276 tests it for spaces,
     * so absence is the target's representation of the same state.</p>
     *
     * @param arm the arm under test, carrying the field it guards, the reference line that declares its
     *     sentence, the sentence itself and the validation state it publishes
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("mandatoryFieldArms")
    void eachMandatoryArmPublishesItsOwnSentence(ValidationArm arm) {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        TransactionAddRequest accepted = submission(ACCOUNT_ID, "", "Y");
        TransactionAddRequest deficient = TransactionAddService.FIELD_AMOUNT.equals(arm.field())
                ? withAmount(accepted, null)
                : replacing(accepted, arm.field(), "");

        assertThatThrownBy(() -> this.service.addTransaction(deficient))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(arm.expectedSentence())
                .satisfies(failure -> {
                    ClientInputException refusal = (ClientInputException) failure;
                    assertThat(refusal.field())
                            .as("the arm names the component whose control the cursor moves to")
                            .isEqualTo(arm.field());
                    assertThat(refusal.state())
                            .as("an unsupplied component is blank, which draws the asterisk")
                            .isEqualTo(arm.expectedState());
                });

        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * Several unsupplied components publish only the FIRST arm's sentence, never a collection.
     *
     * <p>This is the short-circuit the construct at lines 251 to 320 of
     * {@code app/cbl/COTRN02C.cbl} imposes. Every arm ends with {@code PERFORM SEND-TRNADD-SCREEN},
     * whose paragraph at line 516 reaches {@code EXEC CICS RETURN} at lines 530 to 534 and ends the
     * CICS task, so the WRITTEN order of the arms is the priority order and no later arm can be
     * reached once an earlier one fires. The type code is written first, at line 252.</p>
     *
     * <p>Refactoring Rationale: the target answers one refusal naming one field where the reference
     * publishes one sentence, and the shared kernel renders that as a per-field array. The divergence
     * is the ARRAY SHAPE and not the count: the array a failing request carries here holds one entry,
     * so a client that renders an array is served without the reference's single-sentence behaviour
     * being altered. Asserting two simultaneous sentences would assert behaviour the reference does not
     * have, so this case asserts the opposite -- that the second complaint is unreachable while the
     * first stands.</p>
     *
     * <p>Assumptions: the eleven components are emptied in one submission rather than two, so the
     * comparison is against a request that offends every arm at once. That is the strongest form of the
     * claim: even with every arm satisfied, only one publishes.</p>
     */
    @Test
    @DisplayName("many unsupplied components: only the first arm publishes, and it publishes once")
    void severalUnsuppliedComponentsPublishOnlyTheFirstArmsSentence() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        TransactionAddRequest allUnsupplied = withAmount(submission(ACCOUNT_ID, "", "Y"), null);
        for (String field : replaceableFields()) {
            allUnsupplied = replacing(allUnsupplied, field, "");
        }
        TransactionAddRequest deficient = allUnsupplied;

        assertThatThrownBy(() -> this.service.addTransaction(deficient))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddRequest.TYPE_CODE_REQUIRED)
                .satisfies(failure -> assertThat(((ClientInputException) failure).fields())
                        .as("one failing request names one field, per lines 530 to 534")
                        .containsExactly(TransactionAddService.FIELD_TYPE_CODE));

        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * Each arm that demands digits publishes its own composition sentence.
     *
     * <p>This walks the construct at lines 322 to 337 of {@code app/cbl/COTRN02C.cbl}, whose two arms
     * carry sentences at lines 325 and 331, together with the separate conditional at lines 430 to 436
     * whose sentence is at line 432. All three test composition rather than presence, so each case
     * supplies a value of the declared width whose last character is not a digit.</p>
     *
     * <p>Assumptions: the merchant identifier's conditional is the LAST thing the data phase does,
     * after both date blocks, so a submission that offends it must clear everything before it. That
     * ordering is why its offending value is supplied alone rather than alongside another.</p>
     *
     * @param arm the arm under test, carrying its field, its reference line, its sentence and its state
     * @param offendingValue the value to submit into that arm's component, of type {@code String}, at
     *     the width the screen field declares and with one character that is not a digit
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("digitOnlyArms")
    void eachDigitOnlyArmPublishesItsOwnCompositionSentence(ValidationArm arm,
            String offendingValue) {

        accountResolvesTo(RESOLVED_CARD_NUMBER);
        TransactionAddRequest deficient =
                replacing(submission(ACCOUNT_ID, "", "Y"), arm.field(), offendingValue);

        assertThatThrownBy(() -> this.service.addTransaction(deficient))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(arm.expectedSentence())
                .satisfies(failure -> {
                    ClientInputException refusal = (ClientInputException) failure;
                    assertThat(refusal.field()).isEqualTo(arm.field());
                    assertThat(refusal.state())
                            .as("a supplied value that was refused is not a blank component")
                            .isEqualTo(arm.expectedState());
                });

        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * Four different malformations of the amount all publish the one format sentence.
     *
     * <p>This pins the construct at lines 339 to 351 of {@code app/cbl/COTRN02C.cbl}. Four
     * alternatives are written at lines 340 to 343, testing the sign at character one, eight digits
     * from character two, the point at character ten and two digits from character eleven, and they
     * SHARE one action at lines 344 to 348 whose sentence is at line 345. A construct whose
     * alternatives share an action is a disjunction, so it publishes one sentence however many of the
     * four are satisfied.</p>
     *
     * <p>Assumptions: the layout the four alternatives describe is twelve characters -- one sign, eight
     * integer digits, one point, two fractional digits -- which is exactly the width
     * {@code app/cpy-bms/COTRN02.CPY} declares for the screen field at line 96. The two readings agree,
     * so the width is not taken from either alone.</p>
     *
     * @param amount the amount to submit, of type {@link Money}, chosen so that its rendering through
     *     the reference's edited picture fails one of the four alternatives
     * @param failedAlternative a label naming the alternative that value fails, of type
     *     {@code String}, so a failing case reads as the claim it makes
     */
    @ParameterizedTest(name = "{1}")
    @MethodSource("amountsTheEditedPictureCannotHold")
    void everyMalformationOfTheAmountPublishesTheOneFormatSentence(Money amount,
            String failedAlternative) {

        accountResolvesTo(RESOLVED_CARD_NUMBER);
        TransactionAddRequest deficient = withAmount(submission(ACCOUNT_ID, "", "Y"), amount);

        assertThatThrownBy(() -> this.service.addTransaction(deficient))
                .as("the disjunction at lines 340 to 343 publishes one sentence: %s",
                        failedAlternative)
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddRequest.AMOUNT_FORMAT)
                .satisfies(failure -> assertThat(((ClientInputException) failure).field())
                        .isEqualTo(TransactionAddService.FIELD_AMOUNT));

        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * Nine integer digits fit the stored picture and are still refused by the screen edit.
     *
     * <p>Trade-offs: the reference carries TWO widths for one amount and they disagree by one digit.
     * Line 58 of {@code app/cbl/COTRN02C.cbl} declares the accumulator
     * {@code WS-TRAN-AMT-N PIC S9(9)V99}, nine integer digits, and line 10 of
     * {@code app/cpy/CVTRA05Y.cpy} declares the stored column {@code TRAN-AMT PIC S9(09)V99}, also
     * nine; but the screen edit at line 341 tests only EIGHT digit positions. A value with nine integer
     * digits therefore fits both the accumulator and the row and is still refused before it reaches
     * either. The narrower width governs, and the compromise accepted is that the target refuses a
     * value its own storage could hold -- which is the reference's behaviour, and widening the edit to
     * match the accumulator would accept a value the reference screen refuses.</p>
     *
     * <p>Assumptions: the stored picture's acceptance is demonstrated rather than asserted from the
     * declaration, by constructing the row type over the same value and observing that it normalises
     * without complaint. Reading the width from the entity and asserting the entity agrees with it
     * would assert nothing; the two readings have to be able to disagree.</p>
     */
    @Test
    @DisplayName("nine integer digits: accepted by the stored picture, refused by the screen edit")
    void nineIntegerDigitsFitTheStoredPictureAndAreStillRefusedByTheScreenEdit() {
        Money nineIntegerDigits = Money.of("100000000.00");

        Transaction rowCarryingTheSameValue = new Transaction(FIRST_IDENTIFIER, "01", "0001",
                "POS TERM", "GROCERY PURCHASE", nineIntegerDigits.amount(), 0L, "CORNER STORE",
                "SEATTLE", "98101", RESOLVED_CARD_NUMBER, LocalDateTime.of(2026, 1, 15, 0, 0),
                LocalDateTime.of(2026, 1, 16, 0, 0));
        assertThat(rowCarryingTheSameValue.getTranAmt())
                .as("line 10 of the record contract admits nine integer digits")
                .isEqualByComparingTo(nineIntegerDigits.amount());

        accountResolvesTo(RESOLVED_CARD_NUMBER);
        TransactionAddRequest deficient =
                withAmount(submission(ACCOUNT_ID, "", "Y"), nineIntegerDigits);

        assertThatThrownBy(() -> this.service.addTransaction(deficient))
                .as("line 341 tests eight digit positions, so the ninth is refused")
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddRequest.AMOUNT_FORMAT);
    }

    /**
     * A date whose characters do not sit where the mask says publishes the positional sentence.
     *
     * <p>This walks the two mirrored constructs at lines 353 to 366 and 368 to 381 of
     * {@code app/cbl/COTRN02C.cbl}, whose sentences are at lines 360 and 375. Each construct writes
     * five alternatives testing one character position apiece, sharing one action, so each is a
     * disjunction publishing one sentence.</p>
     *
     * <p>Assumptions: the positional block runs BEFORE the semantic block that calls the reference's
     * date service, so a value that fails a position never reaches the service at all and cannot
     * publish the semantic sentence. That ordering is what keeps the four date sentences distinct in
     * practice rather than only in declaration.</p>
     *
     * @param arm the arm under test, carrying its date field, its reference line, its sentence and its
     *     state
     * @param offendingValue the ten-character value to submit into that date component, of type
     *     {@code String}, carrying a separator the mask does not name
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("lexicalDateArms")
    void aDateOutsideTheMaskPublishesThePositionalSentence(ValidationArm arm,
            String offendingValue) {

        accountResolvesTo(RESOLVED_CARD_NUMBER);
        TransactionAddRequest deficient =
                replacing(submission(ACCOUNT_ID, "", "Y"), arm.field(), offendingValue);

        assertThatThrownBy(() -> this.service.addTransaction(deficient))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(arm.expectedSentence())
                .satisfies(failure -> assertThat(((ClientInputException) failure).field())
                        .isEqualTo(arm.field()));

        assertThat(arm.expectedSentence())
                .as("the positional sentence is not the semantic one for the same field")
                .isNotEqualTo(TransactionAddService.MESSAGE_ORIGIN_DATE_INVALID)
                .isNotEqualTo(TransactionAddService.MESSAGE_PROCESS_DATE_INVALID);
    }

    /**
     * A well-shaped date denoting no calendar day publishes the semantic sentence.
     *
     * <p>This walks the two semantic blocks at lines 389 to 407 and 409 to 425 of
     * {@code app/cbl/COTRN02C.cbl}. Each moves the submitted date and the mask into the parameter
     * block at lines 389 to 391, calls the reference's date service at lines 393 to 395, tests the
     * returned severity at line 397 and, on a non-zero severity, publishes its own sentence at line 401
     * or line 421.</p>
     *
     * <p>Assumptions: the value submitted is of the declared shape and denotes no day of the calendar,
     * February having twenty-eight days in the year used, so it clears the positional block and is
     * declined by the date service. The verdict is read directly as well, so a value that quietly
     * became acceptable would fail this case rather than pass it vacuously.</p>
     *
     * @param arm the arm under test, carrying its date field, its reference line, its sentence and its
     *     state
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("calendarDateArms")
    void aWellShapedDateDenotingNoCalendarDayPublishesTheSemanticSentence(ValidationArm arm) {
        DateEditValidator.LanguageEnvironmentResult verdict = DateEditValidator
                .evaluateWithLanguageEnvironment(IMPOSSIBLE_DATE, DateEditValidator.DATE_FORMAT_MASK);
        assertThat(verdict.acceptable())
                .as("the date service declines the value, which is what line 397 tests")
                .isFalse();
        assertThat(verdict.messageNumber())
                .as("and it declines it for a reason lines 400 and 420 do not forgive")
                .isNotEqualTo(DateEditValidator.MSG_NO_UNSUPP_RANGE);

        accountResolvesTo(RESOLVED_CARD_NUMBER);
        TransactionAddRequest deficient =
                replacing(submission(ACCOUNT_ID, "", "Y"), arm.field(), IMPOSSIBLE_DATE);

        assertThatThrownBy(() -> this.service.addTransaction(deficient))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(arm.expectedSentence())
                .satisfies(failure -> assertThat(((ClientInputException) failure).field())
                        .isEqualTo(arm.field()));

        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * The one message number the reference forgives is suppressed at BOTH date sites.
     *
     * <p>This pins the tolerance written twice: line 400 of {@code app/cbl/COTRN02C.cbl} publishes the
     * origination sentence only when the returned message number is not 2513, and line 420 does the
     * same for the processing sentence. Each case below places an offending value in one date field and
     * leaves the other accepted, so a target that forgave at one site and not the other fails exactly
     * one of the two cases.</p>
     *
     * <p>Assumptions: the value used genuinely carries that message number, and that is asserted
     * against the date service directly before the submission is made. Without that reading the case
     * could pass because the value was simply an acceptable date, which would leave the suppression
     * itself untested -- the failure mode a tolerance case is most exposed to.</p>
     *
     * <p>Assumptions: the date service's contract is that it reports a severity and a message number
     * as two separate values, and that a declined value carrying the forgiven number is a well-formed
     * calendar date outside the span the service supports. The target reads both, which is what lets it
     * forgive one decline and refuse another; a target reading severity alone could not express the
     * reference's behaviour at all.</p>
     *
     * <p>Assumptions: the two semantic blocks set their sentence BEFORE raising their error flag,
     * which is the opposite order from every other block on this screen. That difference has no
     * observable effect, so nothing below asserts on it; it is recorded so a reader comparing the
     * blocks does not take the asymmetry for a transcription slip.</p>
     *
     * @param dateField the date component that carries the out-of-range value, of type {@code String},
     *     being one of the two date field keys
     */
    @ParameterizedTest(name = "{0} forgiven")
    @MethodSource("theTwoDateFields")
    void theForgivenMessageNumberIsSuppressedAtBothDateSites(String dateField) {
        DateEditValidator.LanguageEnvironmentResult verdict = DateEditValidator
                .evaluateWithLanguageEnvironment(OUT_OF_RANGE_DATE,
                        DateEditValidator.DATE_FORMAT_MASK);
        assertThat(verdict.acceptable())
                .as("the value is declined, so line 397 takes its else arm")
                .isFalse();
        assertThat(verdict.messageNumber())
                .as("and the decline carries the one number lines 400 and 420 forgive")
                .isEqualTo(DateEditValidator.MSG_NO_UNSUPP_RANGE);
        assertThat(verdict.unsupportedRange())
                .as("which the result publishes as its own tolerance predicate")
                .isTrue();

        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        theAppendEchoesTheRow();
        writeSpanRunsInline();
        TransactionAddRequest forgiven =
                replacing(submission(ACCOUNT_ID, "", "Y"), dateField, OUT_OF_RANGE_DATE);

        TransactionAddResponse answer = this.service.addTransaction(forgiven);

        assertThat(answer.transactionId())
                .as("a forgiven decline is suppressed, so the append is reached")
                .isEqualTo(FIRST_IDENTIFIER);
        verify(this.transactions).saveAndFlush(any());
    }


    /**
     * The answer publishes the amount that survived the reference's edited picture.
     *
     * <p>This pins the normalisation at lines 383 to 386 of {@code app/cbl/COTRN02C.cbl}. Lines 383
     * and 384 compute the submitted characters into the numeric accumulator, line 385 moves that
     * accumulator into the edited display field declared at line 59, and LINE 386 moves the edited
     * field back onto the screen field. Line 386 is what makes the normalisation observable at all:
     * without it the value would be canonical only inside the program.</p>
     *
     * <p>Assumptions: the write-back happens BEFORE the confirmation is evaluated at line 169, so a
     * submission that is not confirmed still shows the normalised value. This case therefore submits a
     * non-affirmative confirmation on purpose: the amount it reads back cannot have come from a stored
     * row, because no row was appended, so the only thing it can have come from is the write-back.</p>
     *
     * <p>Assumptions: the value travels as the shared exact-decimal type at the scale that type
     * declares, and never as a binary approximation. The reference's own carrier is a display picture
     * with two fractional positions, so a submitted value of one fractional digit comes back with two,
     * and a comparison that passed on a binary reading would not distinguish the two.</p>
     */
    @Test
    @DisplayName("the answer carries the amount that survived the edited picture, per line 386")
    void theAnswerCarriesTheAmountThatSurvivedTheEditedPicture() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        TransactionAddRequest oneFractionalDigit =
                withAmount(submission(ACCOUNT_ID, "", "N"), Money.of("125.5"));

        TransactionAddResponse answer = this.service.addTransaction(oneFractionalDigit);

        assertThat(answer.amount())
                .as("the value that survives lines 383 to 386, not the characters submitted")
                .isEqualTo(Money.of("125.50"));
        assertThat(answer.amount().amount().scale())
                .as("two fractional positions, as the picture at line 59 declares")
                .isEqualTo(Money.SCALE);
        assertThat(answer.amount().toPlainString())
                .as("rendered without an exponent, so no client has to re-derive the scale")
                .isEqualTo("125.50");
        assertThat(answer.transactionId())
                .as("no row was appended, so the amount cannot have come from one")
                .isNull();
        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * A negative amount keeps its sign through the edited picture and into the stored row.
     *
     * <p>This pins the sign position the construct at line 340 of {@code app/cbl/COTRN02C.cbl} tests
     * and the accumulator at line 58 carries, {@code WS-TRAN-AMT-N PIC S9(9)V99} being a signed
     * picture, together with the second conversion at lines 456 to 458 that fills the row's amount.</p>
     *
     * <p>Assumptions: the sign is asserted on the STORED value and not only on the answer, because the
     * reference converts the screen field a second time at lines 456 to 458 rather than reusing the
     * accumulator from line 383. Two conversions of one value is where a sign is most easily lost, so
     * both ends are read.</p>
     */
    @Test
    @DisplayName("a negative amount keeps its sign through both conversions")
    void aNegativeAmountKeepsItsSignThroughBothConversions() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        theAppendEchoesTheRow();
        writeSpanRunsInline();
        TransactionAddRequest refund =
                withAmount(submission(ACCOUNT_ID, "", "Y"), Money.of("-125.50"));

        TransactionAddResponse answer = this.service.addTransaction(refund);

        assertThat(answer.amount().isNegative())
                .as("the sign position of line 340 admits a leading minus")
                .isTrue();
        assertThat(appendedRow().getTranAmt())
                .as("lines 456 to 458 convert a second time, and the sign survives that too")
                .isEqualByComparingTo(Money.of("-125.50").amount());
    }

    /**
     * An exhausted probe yields the first identifier at the declared width.
     *
     * <p>This pins the file-exhausted arm of {@code READPREV-TRANSACT-FILE} at line 673 of
     * {@code app/cbl/COTRN02C.cbl}: the construct opens at line 685, its normal arm continues at lines
     * 686 and 687, and LINE 688's exhausted arm moves zeros into the key at line 689. Lines 448 and 449
     * of {@code ADD-TRANSACTION} then move that key into the work field and add one, so an empty table
     * yields one rather than failing.</p>
     *
     * <p>Assumptions: the two arms are expressed as one addition over a starting value rather than as
     * two branches, so the reference's own single increment stays single. The observable consequence is
     * the value below, padded to the sixteen characters the key column declares.</p>
     */
    @Test
    @DisplayName("an exhausted probe yields the first identifier, per lines 688, 689 and 449")
    void anExhaustedProbeYieldsTheFirstIdentifier() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        TransactionAddResponse answer = this.service.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        assertThat(answer.transactionId())
                .as("zeros at line 689 plus one at line 449, at the key width")
                .isEqualTo(FIRST_IDENTIFIER);
        assertThat(answer.transactionId().length())
                .as("the key column is sixteen characters, per line 5 of the record contract")
                .isEqualTo(TransactionAddService.TRANSACTION_ID_WIDTH);
    }

    /**
     * A populated table yields the stored maximum plus one.
     *
     * <p>This pins the derivation at lines 444 to 449 of {@code app/cbl/COTRN02C.cbl}. Line 444
     * positions past the end of the key range, line 445 opens the browse, line 446 reads one record
     * backwards, line 447 closes it, LINE 448 moves the key it landed on into the numeric work field
     * declared at line 57, and LINE 449 adds one. The move and the addition are the derivation; citing
     * the browse alone would describe how the maximum was found and omit how the next value was made.
     * </p>
     *
     * <p>Alternatives Considered: expressing that probe as a query that skips a row count. Rejected,
     * and the rejection is what keeps the derived value deterministic: the count of {@code READNEXT} in
     * the program is zero, so this is a single descending row and not a page of them, and a query that
     * counted rows past a position would answer with a different row than the key ordering does under a
     * concurrent insert. Nothing here treats the probe as a page.</p>
     */
    @Test
    @DisplayName("a populated table yields the stored maximum plus one, per lines 444 to 449")
    void aPopulatedTableYieldsTheStoredMaximumPlusOne() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableMaximumIs(STORED_MAXIMUM);
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        TransactionAddResponse answer = this.service.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        assertThat(answer.transactionId())
                .as("line 448 takes the key, line 449 adds one")
                .isEqualTo(NEXT_IDENTIFIER);
        assertThat(appendedRow().getTranId())
                .as("and the derived value is what line 451 moves into the row")
                .isEqualTo(NEXT_IDENTIFIER);
    }

    /**
     * A failing probe reports the reference's failed-read sentence, in its upper-case spelling.
     *
     * <p>This pins the two catch-all arms the derivation can fail through: lines 661 to 667 of
     * {@code app/cbl/COTRN02C.cbl}, inside {@code STARTBR-TRANSACT-FILE} at line 642, whose sentence is
     * at line 664; and lines 690 to 696, inside {@code READPREV-TRANSACT-FILE} at line 673, whose
     * sentence is at line 693. Both carry the same text, so one sentence covers both conditions.</p>
     *
     * <p>Assumptions: the spelling is the UPPER-CASE one. Five sites in the reference tree carry it --
     * {@code app/cbl/COTRN01C.cbl} line 292, {@code app/cbl/COTRN02C.cbl} lines 664 and 693, and
     * {@code app/cbl/COBIL00C.cbl} lines 463 and 492 -- while three sites in
     * {@code app/cbl/COTRN00C.cbl}, at lines 615, 649 and 683, carry a lower-case spelling of the same
     * words. They are two different constants owned by two different screens, and merging them would
     * change the text one of the two publishes.</p>
     */
    @Test
    @DisplayName("a failing probe reports the upper-case failed-read sentence, never the lower-case")
    void aFailingProbeReportsTheUpperCaseFailedReadSentence() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        when(this.transactions.findMaxTranId()).thenThrow(new IllegalStateException("unreadable"));
        writeSpanRunsInline();

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_TRANSACTION_LOOKUP_FAILED);

        assertThat(TransactionAddService.MESSAGE_TRANSACTION_LOOKUP_FAILED)
                .as("lines 664 and 693 capitalise the noun; COTRN00C's three sites do not")
                .isEqualTo("Unable to lookup Transaction...")
                .isNotEqualTo("Unable to lookup transaction...");
        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * A stored maximum outside the digit form this screen writes is reported as a failed read.
     *
     * <p>This registers what the reference cannot represent. Line 448 of
     * {@code app/cbl/COTRN02C.cbl} moves the sixteen-character key into a numeric work field declared
     * {@code PIC 9(16)} at line 57, and a display-numeric move has no defined result for characters
     * that are not digits. The reference's key column is populated only by this screen and by the
     * nightly posting program, both of which write digits, so the state has no reference behaviour at
     * all.</p>
     *
     * <p>Assumptions: the failed-read sentence is reused rather than a new one invented, because the
     * operator's situation is identical to a probe that could not be performed -- the next identifier
     * cannot be derived and nothing they can retype will change that. Inventing a sentence would put
     * text on this screen that the reference never publishes.</p>
     */
    @Test
    @DisplayName("a stored maximum outside the digit form is reported as a failed read")
    void aStoredMaximumOutsideTheDigitFormIsReportedAsAFailedRead() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableMaximumIs("2026-01-15000001");
        writeSpanRunsInline();

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_TRANSACTION_LOOKUP_FAILED);

        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * The stored row carries every move the reference makes, in the record's own order.
     *
     * <p>This pins {@code ADD-TRANSACTION} at line 442 of {@code app/cbl/COTRN02C.cbl} move by move:
     * line 450 clears the record, line 451 moves the derived identifier, lines 452 to 455 the type code,
     * category code, source and description, lines 456 to 458 the converted amount, line 459 the card
     * number, lines 460 to 463 the four merchant fields and lines 464 and 465 the two timestamps, before
     * line 466 performs the write.</p>
     *
     * <p>Assumptions: the record's own {@code FILLER PIC X(20)} at line 18 of
     * {@code app/cpy/CVTRA05Y.cpy} has no counterpart on the row at all, because it is padding to the
     * declared 350-byte length rather than data. Thirteen members are therefore read here and the
     * fourteenth declaration is deliberately absent.</p>
     *
     * <p>Assumptions: the merchant identifier is submitted as a nine-character digit string and stored
     * as an integral value, matching {@code TRAN-MERCHANT-ID PIC 9(09)} at line 11 of the record
     * contract. A submitted value with a leading zero would therefore lose that zero in storage and
     * regain it on rendering, which is why the value used here has none.</p>
     */
    @Test
    @DisplayName("the stored row carries all thirteen moves of lines 450 to 465")
    void theStoredRowCarriesEveryMoveTheReferenceMakes() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableMaximumIs(STORED_MAXIMUM);
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        this.service.addTransaction(submission(ACCOUNT_ID, SUBMITTED_CARD_NUMBER, "Y"));

        Transaction stored = appendedRow();
        assertThat(stored.getTranId()).as("line 451").isEqualTo(NEXT_IDENTIFIER);
        assertThat(stored.getTranTypeCd()).as("line 452").isEqualTo("01");
        assertThat(stored.getTranCatCd()).as("line 453").isEqualTo("0001");
        assertThat(stored.getTranSource()).as("line 454").isEqualTo("POS TERM");
        assertThat(stored.getTranDesc()).as("line 455").isEqualTo("GROCERY PURCHASE");
        assertThat(stored.getTranAmt()).as("lines 456 to 458")
                .isEqualByComparingTo(Money.of("125.50").amount());
        assertThat(stored.getCardNum()).as("line 459, the cross-reference's value")
                .isEqualTo(RESOLVED_CARD_NUMBER);
        assertThat(stored.getMerchantId()).as("line 460").isEqualTo(123456789L);
        assertThat(stored.getMerchantName()).as("line 461").isEqualTo("CORNER STORE");
        assertThat(stored.getMerchantCity()).as("line 462").isEqualTo("SEATTLE");
        assertThat(stored.getMerchantZip()).as("line 463").isEqualTo("98101");
        assertThat(stored.getOrigTs()).as("line 464")
                .isEqualTo(LocalDateTime.of(2026, 1, 15, 0, 0));
        assertThat(stored.getProcTs()).as("line 465")
                .isEqualTo(LocalDateTime.of(2026, 1, 16, 0, 0));
    }

    /**
     * The two stored timestamps carry the date-only pattern this screen alone writes.
     *
     * <p>This pins lines 464 and 465 of {@code app/cbl/COTRN02C.cbl}, which move the two
     * {@code PIC X(10)} screen dates into the two {@code PIC X(26)} record fields declared at lines 16
     * and 17 of {@code app/cpy/CVTRA05Y.cpy}. A ten-character value moved into a twenty-six-character
     * field is left-justified and padded, so a row this screen wrote carries a DATE and no time at all.
     * </p>
     *
     * <p>Assumptions: three timestamp patterns coexist in the migrated tree and none of them may be
     * normalised into another. The payment screen composes a full reading with a zero microsecond
     * fraction; the nightly posting program takes the originating reading from its input feed at line
     * 436 of {@code app/cbl/CBTRN02C.cbl} and the processing reading from the clock at lines 437 and
     * 438, so its two readings DIFFER; and this screen writes a date only, so its two readings are
     * whatever two dates the operator typed. Coercing them to one shape would make a row's provenance
     * unreadable and would change what a statement or a report prints.</p>
     *
     * <p>Assumptions: the rendering is read through the shared formatter rather than composed here,
     * and the twenty-six characters it produces are the record field's declared width. The rendering
     * carries a midnight time-of-day because that is what a date-only value denotes once it is placed
     * in a field that has positions for a time; the reference pads with spaces instead, and the
     * difference is a rendering of the same instant rather than a different value.</p>
     */
    @Test
    @DisplayName("the stored timestamps carry the date-only pattern of lines 464 and 465")
    void theStoredTimestampsCarryTheDateOnlyPattern() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        this.service.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        Transaction stored = appendedRow();
        assertThat(TimestampFormatter.format(stored.getOrigTs()))
                .as("line 464 moves a ten-character date into a twenty-six-character field")
                .isEqualTo("2026-01-15 00:00:00.000000")
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(TimestampFormatter.format(stored.getProcTs()))
                .as("line 465 does the same with the other submitted date")
                .isEqualTo("2026-01-16 00:00:00.000000")
                .hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(stored.getOrigTs())
                .as("the two readings are the two dates submitted, so they differ from each other")
                .isNotEqualTo(stored.getProcTs());
    }


    /**
     * The acknowledgement carries TWO consecutive spaces, exactly as the reference assembles it.
     *
     * <p>This pins the assembly at lines 728 to 733 of {@code app/cbl/COTRN02C.cbl}. Line 728
     * contributes a literal that ENDS with a space and line 730 contributes one that BEGINS with a
     * space, both delimited by their declared size at lines 729 and 730 so neither is trimmed; line 731
     * contributes the identifier delimited by a space, line 732 the full stop and line 733 names the
     * target. Two adjacent literals each contributing a boundary space put two spaces in the assembled
     * text.</p>
     *
     * <p>Assumptions: the two spaces are the reference's own output and are preserved rather than
     * tidied. A reader meeting them will read them as a slip, so they are asserted explicitly and from
     * both sides -- the two-space form must be present and the one-space form must be absent -- so
     * that a well-meaning tidy-up fails this case instead of silently changing what an operator reads.
     * </p>
     *
     * <p>Assumptions: the whole identifier reaches the text even though line 731 delimits it by a
     * space, because line 451 fills the key from the numeric work field of line 57 and a display
     * numeric pads with zeros rather than spaces. A key holding a space would be truncated at it,
     * which is a state the reference's own writers cannot produce.</p>
     */
    @Test
    @DisplayName("the acknowledgement keeps the two consecutive spaces of lines 728 to 733")
    void theAcknowledgementKeepsTheTwoConsecutiveSpaces() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableMaximumIs(STORED_MAXIMUM);
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        TransactionAddResponse answer = this.service.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        assertThat(answer.returnMessage())
                .as("both literals are delimited by size, so both boundary spaces survive")
                .isEqualTo("Transaction added successfully.  Your Tran ID is " + NEXT_IDENTIFIER + ".")
                .contains("successfully.  Your")
                .doesNotContain("successfully. Your");
        assertThat(answer.returnMessage())
                .as("the whole sixteen-character key reaches the text, per line 731")
                .contains(NEXT_IDENTIFIER);
    }

    /**
     * A successful append answers with the acknowledgement and raises nothing.
     *
     * <p>This pins the normal arm at lines 724 to 734 of {@code app/cbl/COTRN02C.cbl}, whose line 727
     * moves the terminal's GREEN attribute into the message field's colour before the sentence is
     * assembled. The reference therefore renders a success as a success and not as an error, which the
     * two neighbouring arms at lines 735 and 742 do not.</p>
     *
     * <p>Assumptions: the target carries no colour attribute, so the observable form of line 727 is
     * that the acknowledgement arrives as a RETURNED value rather than as a raised refusal, and that its
     * text is none of the sentences the failing arms publish. Asserting a colour would assert a screen
     * attribute the target does not have; asserting the channel the text arrives on is the same claim
     * expressed in what the target does have.</p>
     *
     * @throws IllegalAccessException if a published sentence cannot be read, propagated from the
     *     reflective helper the refusal sentences are collected through
     */
    @Test
    @DisplayName("a successful append answers, and the sentence is none of the refusal sentences")
    void aSuccessfulAppendAnswersAndIsNotARefusal() throws IllegalAccessException {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        TransactionAddResponse answer = this.service.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        assertThat(answer.returnMessage())
                .as("the sentence arrives as a value, which is line 727's observable form")
                .isNotNull();
        assertThat(answer.transactionId()).isEqualTo(FIRST_IDENTIFIER);
        assertThat(publishedTextConstants())
                .as("no refusal sentence equals the acknowledgement")
                .doesNotContain(answer.returnMessage());
    }

    /**
     * A duplicate identifier is a DUPLICATE-KEY conflict, and never a stale-version conflict.
     *
     * <p>This pins the arms at lines 735 to 741 of {@code app/cbl/COTRN02C.cbl}. Two CICS conditions
     * are written, a duplicate key at line 735 and a duplicate record at line 736, and they fall
     * through to ONE action whose sentence is at line 738 before line 740 positions the cursor and line
     * 741 re-sends the screen. One conflict therefore covers both conditions.</p>
     *
     * <p>Refactoring Rationale: this 409 is a duplicate-key 409 and CANNOT be an optimistic-lock one,
     * and the distinction is recorded here so the two are never harmonised. No ledger entity carries a
     * version attribute: {@link Transaction} declines one on measured evidence, because
     * {@code app/cbl/COTRN00C.cbl}, {@code app/cbl/COTRN01C.cbl} and {@code app/cbl/COTRN02C.cbl}
     * contain no rewrite of any kind and the one rewrite in {@code app/cbl/COBIL00C.cbl} targets the
     * ACCOUNT record rather than a ledger row, so this table is insert-only along every reference path
     * and there is no before-image to migrate. A second reason is mechanical: schema generation is
     * switched off, so a version attribute would map to a column the migration does not create and
     * would fail when a query ran. A stale-version conflict is therefore unreachable on this path,
     * whereas the account and card contexts legitimately do have one -- and treating this screen's 409
     * as theirs would attribute a retry-after-refresh remedy to a condition whose remedy is to submit
     * again.</p>
     *
     * <p>Assumptions: the sentence is asserted alongside the kind, because the kind alone would let the
     * shared rendering be changed to publish other wording without this screen noticing. The reference's
     * own singular verb is carried across as written.</p>
     */
    @Test
    @DisplayName("a duplicate identifier is a duplicate-key conflict, per lines 735, 736 and 738")
    void aDuplicateIdentifierIsADuplicateKeyConflictAndNeverAStaleVersionOne() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        when(this.transactions.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value"));
        writeSpanRunsInline();

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(RecordConflictException.class)
                .satisfies(failure -> {
                    RecordConflictException conflict = (RecordConflictException) failure;
                    assertThat(conflict.kind())
                            .as("lines 735 and 736 fall through to one duplicate action")
                            .isEqualTo(RecordConflictException.Kind.DUPLICATE_KEY);
                    assertThat(conflict.kind())
                            .as("no ledger entity carries a version attribute, so this is not staleness")
                            .isNotEqualTo(RecordConflictException.Kind.STALE_VERSION);
                    assertThat(conflict.currentVersion())
                            .as("and with no version attribute there is no version to report")
                            .isNull();
                });

        assertThat(GlobalExceptionHandler.MESSAGE_DUPLICATE_KEY)
                .as("the shared rendering publishes the sentence of line 738 verbatim")
                .isEqualTo("Tran ID already exist...");
        assertThat(ApiError.CODE_CONFLICT)
                .as("and renders it under the conflict code, which is the published 409")
                .isEqualTo("CARDDEMO-0409");
    }

    /**
     * A store failure that is not a duplicate reports the reference's failed-add sentence.
     *
     * <p>This pins the catch-all arm at lines 742 to 748 of {@code app/cbl/COTRN02C.cbl}: line 742 is
     * the {@code WHEN OTHER} the construct opened at line 723 falls through to, line 743 records the
     * response codes, line 744 raises the error flag and line 745 carries the sentence before line 747
     * positions the cursor and line 748 re-sends the screen.</p>
     *
     * <p>Assumptions: rollback is expressed as the failure propagating rather than as an explicit
     * instruction, because no {@code SYNCPOINT ROLLBACK} exists anywhere in the reference program to
     * transcribe. The write span's own boundary undoes the work when the failure leaves it, which is
     * what the CICS task's implicit syncpoint does when the task ends abnormally.</p>
     *
     * <p>Assumptions: this sentence is not the payment screen's own failed-write sentence, and the two
     * are held apart here. Both screens append to the same table and each names the operation it was
     * performing, so merging them would tell an operator the wrong operation failed.</p>
     */
    @Test
    @DisplayName("a store failure reports the failed-add sentence of line 745")
    void aFailingWriteReportsTheFailedAddSentence() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        when(this.transactions.saveAndFlush(any()))
                .thenThrow(new IllegalStateException("the store did not accept the row"));
        writeSpanRunsInline();

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(TransactionAddService.MESSAGE_ADD_FAILED);

        assertThat(TransactionAddService.MESSAGE_ADD_FAILED)
                .as("line 745 names this screen's operation, not the payment screen's")
                .isEqualTo("Unable to Add Transaction...")
                .isNotEqualTo("Unable to Add Bill pay Transaction...");
    }

    /**
     * Either spelling of an affirmative confirmation reaches the append.
     *
     * <p>This pins the first arm of the construct at lines 169 to 188 of
     * {@code app/cbl/COTRN02C.cbl}: line 170 writes the upper-case spelling, line 171 the lower-case
     * one, and both fall through to line 172, which performs {@code ADD-TRANSACTION}.</p>
     *
     * <p>Assumptions: the two spellings are two written alternatives rather than a case-insensitive
     * comparison, so exactly two values reach the append and no third does. A target that folded case
     * would additionally accept spellings the reference refuses through its final arm.</p>
     *
     * @param confirmation the affirmative spelling under test, of type {@code String}, being one of the
     *     two the reference writes
     */
    @ParameterizedTest(name = "confirmation {0} appends")
    @ValueSource(strings = {"Y", "y"})
    void eitherAffirmativeSpellingReachesTheAppend(String confirmation) {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        TransactionAddResponse answer =
                this.service.addTransaction(submission(ACCOUNT_ID, "", confirmation));

        assertThat(answer.transactionId()).isEqualTo(FIRST_IDENTIFIER);
        verify(this.transactions).saveAndFlush(any());
    }

    /**
     * A refused confirmation shares the prompt with a never-supplied one, and appends nothing.
     *
     * <p>This pins the second arm group of the construct at lines 169 to 188 of
     * {@code app/cbl/COTRN02C.cbl}. FOUR values are written into one group -- the two spellings of a
     * refusal at lines 173 and 174, spaces at line 175 and an unfilled field at line 176 -- and they
     * fall through to one action that raises the error flag at line 177 and carries the sentence at line
     * 178.</p>
     *
     * <p>Assumptions: a refusal DOES publish a sentence on this screen, and that is the discriminator
     * against the payment screen rather than an incidental similarity. {@code app/cbl/COBIL00C.cbl}
     * writes its own construct at lines 173 to 191 with FOUR arm groups instead of three, giving a
     * refusal its own group at lines 178 to 181 whose action clears the screen and raises the flag while
     * publishing NO sentence at all. The two screens therefore answer a refusal differently, the sibling
     * class that pins the payment screen asserts the opposite of this case, and unifying the two would
     * break exactly one of them.</p>
     *
     * <p>Assumptions: nothing is appended on this path, so the answer carries no identifier. The
     * reference re-sends the same screen and asks again, which is not a refusal of the submission -- so
     * the target answers rather than raises, and the amount it echoes is the normalised one line 386 put
     * back before line 169 was reached.</p>
     *
     * @param confirmation the non-affirmative spelling under test, of type {@code String}, being one of
     *     the four values the reference groups into this arm
     */
    @ParameterizedTest(name = "confirmation [{0}] prompts")
    @ValueSource(strings = {"N", "n", "", " "})
    void aRefusedConfirmationSharesThePromptWithANeverSuppliedOne(String confirmation) {
        accountResolvesTo(RESOLVED_CARD_NUMBER);

        TransactionAddResponse answer =
                this.service.addTransaction(submission(ACCOUNT_ID, "", confirmation));

        assertThat(answer.returnMessage())
                .as("all four values of lines 173 to 176 publish the sentence of line 178")
                .isEqualTo(TransactionAddService.MESSAGE_CONFIRM_ADD);
        assertThat(answer.transactionId())
                .as("the arm re-asks rather than appending, so no identifier is derived")
                .isNull();
        assertThat(answer.amount())
                .as("the amount echoed is the normalised one line 386 wrote back before line 169")
                .isEqualTo(Money.of("125.50"));
        verify(this.transactions, never()).findMaxTranId();
        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * Any other confirmation value is refused, naming the confirmation field.
     *
     * <p>This pins the final arm of the construct at lines 169 to 188 of
     * {@code app/cbl/COTRN02C.cbl}: line 182 is its {@code WHEN OTHER}, line 183 raises the error flag
     * and line 184 carries the sentence before line 186 positions the cursor and line 187 re-sends the
     * screen.</p>
     *
     * <p>Assumptions: this arm is a REFUSAL and the previous one is a prompt, so this one raises where
     * that one answers. The field's declared domain is one character and this value is outside it, which
     * the operator can correct; the previous arm's values are inside the domain and simply have not
     * agreed yet.</p>
     *
     * <p>Assumptions: this sentence is shared with the payment screen byte for byte --
     * {@code app/cbl/COTRN02C.cbl} line 184 and {@code app/cbl/COBIL00C.cbl} line 187 -- and neither
     * screen owns it exclusively. Both classes may assert it, and a reader meeting it twice is meeting
     * one constant rather than a duplication to be resolved.</p>
     */
    @Test
    @DisplayName("any other confirmation value is refused, per lines 182 to 184")
    void anyOtherConfirmationValueIsRefused() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);

        assertThatThrownBy(() -> this.service.addTransaction(submission(ACCOUNT_ID, "", "Q")))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddService.MESSAGE_INVALID_CONFIRMATION)
                .satisfies(failure -> {
                    ClientInputException refusal = (ClientInputException) failure;
                    assertThat(refusal.field())
                            .as("line 186 positions the cursor on the confirmation control")
                            .isEqualTo(TransactionAddService.FIELD_CONFIRMATION);
                    assertThat(refusal.state())
                            .as("a value outside the domain was supplied, so the field is not blank")
                            .isEqualTo(FieldValidationFlag.NOT_OK);
                });

        assertThat(TransactionAddService.MESSAGE_INVALID_CONFIRMATION)
                .as("line 184 and COBIL00C line 187 carry one constant between them")
                .isEqualTo("Invalid value. Valid values are (Y/N)...");
        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * The key phase is settled before the confirmation is ever evaluated.
     *
     * <p>This pins the statement order of {@code PROCESS-ENTER-KEY} at line 164 of
     * {@code app/cbl/COTRN02C.cbl}: line 166 performs the key validation, line 167 the data validation,
     * and only then does line 169 open the confirmation construct. The order is observable, because a
     * submission deficient in both a key and a confirmation is answered for the KEY.</p>
     *
     * <p>Assumptions: the confirmation used here is one the reference would itself complain about, so
     * the case can only pass if the key complaint outranks it. Using an affirmative confirmation would
     * leave the ordering untested, because there would be no second complaint to outrank.</p>
     */
    @Test
    @DisplayName("a submission deficient in both a key and a confirmation is answered for the key")
    void theKeyPhaseIsSettledBeforeTheConfirmationIsEvaluated() {
        assertThatThrownBy(() -> this.service.addTransaction(submission("", "", "Q")))
                .as("line 166 runs before line 169, so the key complaint outranks the confirmation")
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionAddService.MESSAGE_KEY_REQUIRED)
                .satisfies(failure -> assertThat(((ClientInputException) failure).field())
                        .isNotEqualTo(TransactionAddService.FIELD_CONFIRMATION));
    }

    /**
     * The cross-reference is resolved before the write span opens, and the append follows inside it.
     *
     * <p>This pins the order the reference performs its work in and the boundary the target draws
     * around part of it. Line 208 of {@code app/cbl/COTRN02C.cbl} reads the cross-reference during the
     * key phase at line 166, and {@code ADD-TRANSACTION} at line 442 derives the identifier and writes
     * the row afterwards.</p>
     *
     * <p>Assumptions: the target's write boundary encloses the derivation and the append and NOT the
     * cross-reference read, and the order below is what makes that observable. A read over the network
     * inside a database transaction holds a pooled connection for the whole of a remote wait, so a slow
     * account context would consume the write pool of every task rather than only the request that is
     * waiting; the reference has no equivalent exposure because both of its reads are local to the
     * region, so keeping the remote read outside the boundary preserves its isolation properties rather
     * than departing from them.</p>
     */
    @Test
    @DisplayName("the cross-reference read precedes the write span, and the append follows inside it")
    void theCrossReferenceIsResolvedBeforeTheWriteSpanOpens() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        this.service.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        InOrder order = inOrder(this.accounts, this.transactionManager, this.transactions);
        order.verify(this.accounts).findCardXrefByAccountId(ACCOUNT_ID);
        order.verify(this.transactionManager).getTransaction(any());
        order.verify(this.transactions).findMaxTranId();
        order.verify(this.transactions).saveAndFlush(any());
    }

    /**
     * One write span covers the derivation and the append together, and commits once.
     *
     * <p>Assumptions: the reference declares NO explicit syncpoint anywhere -- the count of that verb
     * across all 783 lines of {@code app/cbl/COTRN02C.cbl} is zero -- so what is reproduced here is the
     * CICS task's own implicit syncpoint, which commits the derivation at lines 444 to 449 and the write
     * at line 466 together because they run in one task. The target expresses that as one explicit
     * boundary, and this case observes that exactly one is opened and exactly one is committed.</p>
     *
     * <p>Assumptions: the derivation must sit INSIDE the boundary and not merely before the append,
     * because splitting them would widen the window in which another writer claims the identifier this
     * one derived -- and the duplicate that results is the condition at lines 735 and 736, so a narrow
     * span is what makes that condition rare rather than routine.</p>
     */
    @Test
    @DisplayName("one span opens, covers the derivation and the append, and commits once")
    void oneWriteSpanCoversTheDerivationAndTheAppend() {
        TransactionStatus span = new SimpleTransactionStatus();
        when(this.transactionManager.getTransaction(any())).thenReturn(span);
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();
        theAppendEchoesTheRow();

        this.service.addTransaction(submission(ACCOUNT_ID, "", "Y"));

        verify(this.transactionManager, times(1)).getTransaction(any());
        verify(this.transactionManager, times(1)).commit(span);
        verify(this.transactionManager, never()).rollback(any());
    }


    /**
     * The copy path re-enters the FULL chain, so a refused confirmation still only prompts.
     *
     * <p>This pins {@code COPY-LAST-TRAN-DATA} at line 471 of {@code app/cbl/COTRN02C.cbl}. Line 473
     * validates the key fields, lines 475 to 478 read the most recent record, lines 480 to 493 copy its
     * data columns into the screen, and LINE 495 performs {@code PROCESS-ENTER-KEY} -- the same
     * paragraph the enter key itself performs. The copied values therefore travel the whole validation
     * chain and the whole confirmation construct rather than a shortened path.</p>
     *
     * <p>Assumptions: line 493 is the {@code END-IF} that closes the copy block and line 494 is blank,
     * so the performing statement is at line 495. Citing one line early would attribute the re-entry to
     * the end of a conditional and hide that the copy path is validated at all.</p>
     *
     * <p>Assumptions: the amount read back is the STORED row's and not the submitted one, which is how
     * the copy is observable on a path that appends nothing. The confirmation is refused on purpose so
     * that the case can only pass if the copy happened before the confirmation was evaluated.</p>
     */
    @Test
    @DisplayName("the copy path re-enters the full chain, per lines 473 and 495")
    void theCopyPathReEntersTheFullChainSoARefusalStillOnlyPrompts() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableMaximumIs(STORED_MAXIMUM);
        when(this.transactions.findById(STORED_MAXIMUM))
                .thenReturn(Optional.of(storedRow(STORED_MAXIMUM)));

        TransactionAddResponse answer =
                this.service.copyLastTransactionData(submission(ACCOUNT_ID, "", "N"));

        assertThat(answer.returnMessage())
                .as("line 495 re-enters the construct at line 169, which prompts for a refusal")
                .isEqualTo(TransactionAddService.MESSAGE_CONFIRM_ADD);
        assertThat(answer.amount())
                .as("lines 481 and 485 copied the stored amount over the submitted one")
                .isEqualTo(Money.of("42.75"));
        assertThat(answer.transactionId()).isNull();
        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * The copy path's own probe adds nothing, so the appended identifier is the maximum plus ONE.
     *
     * <p>This pins the difference between the two probes the program performs. Lines 475 to 478 of
     * {@code app/cbl/COTRN02C.cbl} position past the end of the key range, read one record backwards and
     * close the browse -- and that is ALL they do: there is no move into the numeric work field and no
     * addition, unlike lines 448 and 449 in {@code ADD-TRANSACTION}. The copy path then performs
     * {@code PROCESS-ENTER-KEY} at line 495, which reaches {@code ADD-TRANSACTION} at line 442 and does
     * its OWN derivation.</p>
     *
     * <p>Assumptions: exactly one increment happens across the whole copy path, and the appended
     * identifier below is what makes that observable: with a stored maximum of eight the row is appended
     * as nine and never as ten. A target that incremented at the copy probe as well would agree with
     * every other case in this class while writing a row under the wrong key, and would leave a gap in
     * the key sequence the reference does not leave.</p>
     *
     * <p>Assumptions: the probe is nevertheless performed twice, once for the copy and once for the
     * derivation, which is why the read count below is two. Collapsing the two into one read would be a
     * departure from the reference, which opens and closes a browse in each paragraph.</p>
     */
    @Test
    @DisplayName("the copy path's probe adds nothing: the maximum plus one, never plus two")
    void theCopyPathsOwnProbeAddsNothingToTheDerivedIdentifier() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableMaximumIs(STORED_MAXIMUM);
        when(this.transactions.findById(STORED_MAXIMUM))
                .thenReturn(Optional.of(storedRow(STORED_MAXIMUM)));
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        TransactionAddResponse answer =
                this.service.copyLastTransactionData(submission(ACCOUNT_ID, "", "Y"));

        assertThat(answer.transactionId())
                .as("lines 475 to 478 carry no addition, so only line 449 increments")
                .isEqualTo(NEXT_IDENTIFIER)
                .isNotEqualTo("0000000000000010");
        verify(this.transactions, times(2)).findMaxTranId();
        verify(this.transactions).saveAndFlush(any());
    }

    /**
     * The copied submission replaces the data columns and leaves the keys to the key phase.
     *
     * <p>This pins the copy block at lines 480 to 493 of {@code app/cbl/COTRN02C.cbl}. Line 480 guards
     * the block on the error flag being clear, line 481 renders the record's amount through the edited
     * picture of line 59, lines 482 to 484 move the type code, category code and source, line 485 moves
     * the rendered amount, line 486 the description, lines 487 and 488 the two dates and lines 489 to
     * 492 the four merchant columns, before line 493 closes the block. That is TWELVE moves across lines
     * 481 to 492, and the identifier is deliberately not among them.</p>
     *
     * <p>Assumptions: the copy is a REPLACEMENT and not a merge. A submission whose data fields were
     * already populated loses them, which is the reference behaviour, so the assertions below read the
     * stored row's values in every copied column even though the submission carried different ones.</p>
     *
     * <p>Assumptions: the card column is filled by the KEY phase and not by the copy, because lines 482
     * to 492 move nothing into either key field. The row therefore carries the cross-reference's card
     * number on this path for the same reason it does on the ordinary path -- line 459 -- and not because
     * the copied row happened to carry the same value.</p>
     *
     * <p>Assumptions: the identifier is derived rather than copied, so the appended row's key differs
     * from the row it was copied from. Copying the key would reproduce the duplicate condition at lines
     * 735 and 736 on every use of this path.</p>
     */
    @Test
    @DisplayName("the copied submission replaces the twelve data columns of lines 481 to 492")
    void theCopiedSubmissionReplacesTheDataColumnsAndLeavesTheKeysAlone() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableMaximumIs(STORED_MAXIMUM);
        when(this.transactions.findById(STORED_MAXIMUM))
                .thenReturn(Optional.of(storedRow(STORED_MAXIMUM)));
        theAppendEchoesTheRow();
        writeSpanRunsInline();

        this.service.copyLastTransactionData(submission(ACCOUNT_ID, "", "Y"));

        Transaction appended = appendedRow();
        assertThat(appended.getTranTypeCd()).as("line 482").isEqualTo("02");
        assertThat(appended.getTranCatCd()).as("line 483").isEqualTo("0002");
        assertThat(appended.getTranSource()).as("line 484").isEqualTo("ATM TERM");
        assertThat(appended.getTranAmt()).as("lines 481 and 485")
                .isEqualByComparingTo(Money.of("42.75").amount());
        assertThat(appended.getTranDesc()).as("line 486").isEqualTo("FUEL PURCHASE");
        assertThat(appended.getOrigTs()).as("line 487")
                .isEqualTo(LocalDateTime.of(2026, 1, 10, 0, 0));
        assertThat(appended.getProcTs()).as("line 488")
                .isEqualTo(LocalDateTime.of(2026, 1, 11, 0, 0));
        assertThat(appended.getMerchantId()).as("line 489").isEqualTo(987654321L);
        assertThat(appended.getMerchantName()).as("line 490").isEqualTo("FUEL STOP");
        assertThat(appended.getMerchantCity()).as("line 491").isEqualTo("TACOMA");
        assertThat(appended.getMerchantZip()).as("line 492").isEqualTo("98402");

        assertThat(appended.getCardNum())
                .as("the key phase filled this column, per line 459, not the copy block")
                .isEqualTo(RESOLVED_CARD_NUMBER);
        assertThat(appended.getTranId())
                .as("the identifier is derived at line 449 and is not among the twelve moves")
                .isEqualTo(NEXT_IDENTIFIER)
                .isNotEqualTo(STORED_MAXIMUM);
    }

    /**
     * A table holding no row leaves the copy path nothing to copy, and it says so.
     *
     * <p>This pins the state the exhausted arm at lines 688 and 689 of
     * {@code app/cbl/COTRN02C.cbl} leaves the copy path in. Lines 475 to 478 read backwards from past
     * the end of the key range, and on an empty table the record area holds no copied row at all, so the
     * eleven moves of lines 482 to 492 would carry nothing.</p>
     *
     * <p>Assumptions: the sentence published is the reference's failed-read one from lines 664 and 693
     * rather than a new one, because the operator's situation is the same in both cases -- there is
     * nothing to copy and nothing they can retype changes that. Inventing a sentence would put text on
     * this screen that the reference never publishes, and reference message text is carried across
     * verbatim rather than extended.</p>
     *
     * <p>Assumptions: the copy path answers this way while the ORDINARY path treats an exhausted probe
     * as a starting point of zero, per lines 688, 689 and 449. The same reference arm therefore has two
     * consequences depending on which paragraph reached it, and both are asserted in this class.</p>
     */
    @Test
    @DisplayName("an empty table leaves the copy path nothing to copy, per lines 688 and 689")
    void theCopyPathWithNoStoredRowReportsTheFailedReadSentence() {
        accountResolvesTo(RESOLVED_CARD_NUMBER);
        theTableHoldsNoRow();

        assertThatThrownBy(() ->
                this.service.copyLastTransactionData(submission(ACCOUNT_ID, "", "Y")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(TransactionAddService.MESSAGE_TRANSACTION_LOOKUP_FAILED);

        verify(this.transactions, never()).saveAndFlush(any());
    }

    /**
     * Every sentence this screen publishes is the reference's sentence, character for character.
     *
     * <p>This pins the whole message catalogue of {@code app/cbl/COTRN02C.cbl}, whose thirty literals
     * run from line 178 to line 745 and reach across every paragraph that can publish: the confirmation
     * construct at lines 178 and 184, the key construct at lines 199, 213 and 226, the mandatory
     * construct at lines 254 to 314, the composition and format constructs at lines 325 to 432, the two
     * cross-reference paragraphs at lines 593 to 633, the two browse paragraphs at lines 664 and 693, and
     * the write paragraph at lines 728 to 745.</p>
     *
     * <p>Assumptions: this is the one place the texts are compared against the reference program rather
     * than against a published constant, so the arms above can name their sentence by constant without
     * any of them being the only thing that pins the wording. Each assertion cites the line that carries
     * the literal, except the duplicate sentence, which the shared kernel publishes on this screen's
     * behalf.</p>
     *
     * <p>Assumptions: the trailing ellipses, the capitalisation of "NOT", the spelling "Numeric" with a
     * capital, the singular verb in the duplicate sentence and the absence of an ellipsis on the two
     * format sentences are all the reference's own and are carried across unchanged. Each looks like a
     * slip in isolation and each is what an operator reads today, so a tidy-up of any of them fails
     * here.</p>
     */
    @Test
    @DisplayName("every published sentence matches the reference program character for character")
    void everyPublishedSentenceIsTheReferenceSentence() {
        assertThat(TransactionAddService.MESSAGE_CONFIRM_ADD).as("line 178")
                .isEqualTo("Confirm to add this transaction...");
        assertThat(TransactionAddService.MESSAGE_INVALID_CONFIRMATION).as("line 184")
                .isEqualTo("Invalid value. Valid values are (Y/N)...");
        assertThat(TransactionAddRequest.ACCOUNT_ID_NOT_NUMERIC).as("line 199")
                .isEqualTo("Account ID must be Numeric...");
        assertThat(TransactionAddRequest.CARD_NUMBER_NOT_NUMERIC).as("line 213")
                .isEqualTo("Card Number must be Numeric...");
        assertThat(TransactionAddService.MESSAGE_KEY_REQUIRED).as("line 226")
                .isEqualTo("Account or Card Number must be entered...");

        assertThat(TransactionAddRequest.TYPE_CODE_REQUIRED).as("line 254")
                .isEqualTo("Type CD can NOT be empty...");
        assertThat(TransactionAddRequest.CATEGORY_CODE_REQUIRED).as("line 260")
                .isEqualTo("Category CD can NOT be empty...");
        assertThat(TransactionAddRequest.SOURCE_REQUIRED).as("line 266")
                .isEqualTo("Source can NOT be empty...");
        assertThat(TransactionAddRequest.DESCRIPTION_REQUIRED).as("line 272")
                .isEqualTo("Description can NOT be empty...");
        assertThat(TransactionAddRequest.AMOUNT_REQUIRED).as("line 278")
                .isEqualTo("Amount can NOT be empty...");
        assertThat(TransactionAddRequest.ORIGIN_DATE_REQUIRED).as("line 284")
                .isEqualTo("Orig Date can NOT be empty...");
        assertThat(TransactionAddRequest.PROCESS_DATE_REQUIRED).as("line 290")
                .isEqualTo("Proc Date can NOT be empty...");
        assertThat(TransactionAddRequest.MERCHANT_ID_REQUIRED).as("line 296")
                .isEqualTo("Merchant ID can NOT be empty...");
        assertThat(TransactionAddRequest.MERCHANT_NAME_REQUIRED).as("line 302")
                .isEqualTo("Merchant Name can NOT be empty...");
        assertThat(TransactionAddRequest.MERCHANT_CITY_REQUIRED).as("line 308")
                .isEqualTo("Merchant City can NOT be empty...");
        assertThat(TransactionAddRequest.MERCHANT_ZIP_REQUIRED).as("line 314")
                .isEqualTo("Merchant Zip can NOT be empty...");

        assertThat(TransactionAddRequest.TYPE_CODE_NOT_NUMERIC).as("line 325")
                .isEqualTo("Type CD must be Numeric...");
        assertThat(TransactionAddRequest.CATEGORY_CODE_NOT_NUMERIC).as("line 331")
                .isEqualTo("Category CD must be Numeric...");
        assertThat(TransactionAddRequest.AMOUNT_FORMAT).as("line 345")
                .isEqualTo("Amount should be in format -99999999.99");
        assertThat(TransactionAddRequest.ORIGIN_DATE_FORMAT).as("line 360")
                .isEqualTo("Orig Date should be in format YYYY-MM-DD");
        assertThat(TransactionAddRequest.PROCESS_DATE_FORMAT).as("line 375")
                .isEqualTo("Proc Date should be in format YYYY-MM-DD");
        assertThat(TransactionAddService.MESSAGE_ORIGIN_DATE_INVALID).as("line 401")
                .isEqualTo("Orig Date - Not a valid date...");
        assertThat(TransactionAddService.MESSAGE_PROCESS_DATE_INVALID).as("line 421")
                .isEqualTo("Proc Date - Not a valid date...");
        assertThat(TransactionAddRequest.MERCHANT_ID_NOT_NUMERIC).as("line 432")
                .isEqualTo("Merchant ID must be Numeric...");

        assertThat(TransactionAddService.MESSAGE_ACCOUNT_NOT_FOUND).as("line 593")
                .isEqualTo("Account ID NOT found...");
        assertThat(TransactionAddService.MESSAGE_ACCOUNT_XREF_LOOKUP_FAILED).as("line 600")
                .isEqualTo("Unable to lookup Acct in XREF AIX file...");
        assertThat(TransactionAddService.MESSAGE_CARD_NOT_FOUND).as("line 626")
                .isEqualTo("Card Number NOT found...");
        assertThat(TransactionAddService.MESSAGE_CARD_XREF_LOOKUP_FAILED).as("line 633")
                .isEqualTo("Unable to lookup Card # in XREF file...");
        assertThat(TransactionAddService.MESSAGE_TRANSACTION_LOOKUP_FAILED).as("lines 664 and 693")
                .isEqualTo("Unable to lookup Transaction...");

        assertThat(TransactionAddService.MESSAGE_ADDED_PREFIX).as("line 728, ending in a space")
                .isEqualTo("Transaction added successfully. ");
        assertThat(TransactionAddService.MESSAGE_ADDED_INFIX).as("line 730, beginning with a space")
                .isEqualTo(" Your Tran ID is ");
        assertThat(TransactionAddService.MESSAGE_ADDED_SUFFIX).as("line 732").isEqualTo(".");
        assertThat(GlobalExceptionHandler.MESSAGE_DUPLICATE_KEY).as("line 738")
                .isEqualTo("Tran ID already exist...");
        assertThat(TransactionAddService.MESSAGE_ADD_FAILED).as("line 745")
                .isEqualTo("Unable to Add Transaction...");
    }

    /**
     * Every sentence this screen can publish fits the seventy-five-character message band.
     *
     * <p>The width chain the reference establishes narrows at each of its three links.
     * {@code WS-MESSAGE} is declared {@code PIC X(80)} at line 38 of {@code app/cbl/COTRN02C.cbl}; line
     * 520, inside {@code SEND-TRNADD-SCREEN} at line 516 and after {@code POPULATE-HEADER-INFO} at line
     * 518, moves it into the map's message field, declared {@code PIC X(78)} at line 272 of
     * {@code app/cpy-bms/COTRN02.CPY}; and the band the migrated answer publishes in is the
     * seventy-five of the shared session structure's message fields, which
     * {@link ApiError#MESSAGE_RENDERING_WIDTH} carries.</p>
     *
     * <p>Assumptions: the NARROWEST link governs, and the eighty of line 38 is deliberately not taken
     * as the contract. Taking the widest would let a sentence through that the reference screen itself
     * could not display.</p>
     *
     * <p>Assumptions: what is asserted is that no sentence is long enough for the narrowing to bite,
     * rather than the widths of the intermediate links. Asserting those would assert the reference's own
     * declarations rather than this screen's behaviour, and every sentence is well inside the band, so
     * the chain never truncates in practice. Recording the chain and asserting the outcome is what keeps
     * a later, longer sentence from being introduced silently.</p>
     *
     * <p>Assumptions: the assembled acknowledgement is measured as well as the constants, because it is
     * the only text this screen publishes that no single constant carries -- three constants and a
     * sixteen-character identifier are concatenated at lines 728 to 733, and the sum is what has to fit.
     * </p>
     *
     * @throws IllegalAccessException if a member reported as publicly readable cannot be read,
     *     propagated from the reflective helper the sentences are collected through
     */
    @Test
    @DisplayName("every publishable sentence fits the seventy-five-character band")
    void everyPublishedSentenceFitsTheMessageBand() throws IllegalAccessException {
        assertThat(ApiError.MESSAGE_RENDERING_WIDTH)
                .as("the band is the narrowest link, not the eighty of line 38")
                .isEqualTo(75);

        List<String> sentences = publishedTextConstants();
        assertThat(sentences)
                .as("the sentences are read reflectively, so one added later is covered here")
                .contains(TransactionAddService.MESSAGE_CONFIRM_ADD,
                        TransactionAddService.MESSAGE_KEY_REQUIRED,
                        TransactionAddService.MESSAGE_TRANSACTION_LOOKUP_FAILED,
                        TransactionAddService.MESSAGE_ADD_FAILED);
        assertThat(sentences).allSatisfy(sentence ->
                assertThat(sentence.length()).isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH));

        String assembled = TransactionAddService.MESSAGE_ADDED_PREFIX
                + TransactionAddService.MESSAGE_ADDED_INFIX
                + NEXT_IDENTIFIER
                + TransactionAddService.MESSAGE_ADDED_SUFFIX;
        assertThat(assembled.length())
                .as("the assembled acknowledgement of lines 728 to 733 fits the band too")
                .isLessThanOrEqualTo(ApiError.MESSAGE_RENDERING_WIDTH);
    }

}
