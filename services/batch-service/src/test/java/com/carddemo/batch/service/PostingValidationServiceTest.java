package com.carddemo.batch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.dto.PostingValidationResult;
import com.carddemo.batch.dto.RejectReason;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.service.PostingValidationService.PostingDecision;
import com.carddemo.common.codec.CopybookLayout;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Settles which posting condition a daily transaction fails and which of those failures is reported.
 *
 * <h2>Purpose</h2>
 *
 * <p>This class drives {@link PostingValidationService}, the transcription of
 * {@code 1500-VALIDATE-TRAN} at {@code app/cbl/CBTRN02C.cbl:370} and of the two paragraphs it
 * performs, {@code 1500-A-LOOKUP-XREF} at {@code :380} and {@code 1500-B-LOOKUP-ACCT} at
 * {@code :393}. Each case below names the baseline line or the committed expectation file it pins, so
 * a failure identifies a specific transcription rather than a general disagreement.</p>
 *
 * <h2>The two precedence rules differ, and that asymmetry is the point</h2>
 *
 * <p>The reference does not apply one uniform rule across the four reasons:</p>
 *
 * <ul>
 *   <li><b>First reason wins across the two lookups.</b> {@code :372} performs the account lookup
 *       only while no reason has been assigned, so an unresolved card EXCLUDES the account reason
 *       rather than merely outranking it. {@link LookupOrder} pins that as a read that never
 *       happens.</li>
 *   <li><b>Last writer wins across the two boundaries.</b> {@code :413} closes the credit-limit
 *       block and {@code :414} opens the expiration block with nothing between them that tests the
 *       reason, so {@code MOVE 103} at {@code :417} overwrites {@code MOVE 102} at {@code :410}.
 *       {@link BoundaryPrecedence} pins that.</li>
 * </ul>
 *
 * <p>Assumptions: the absence of a guard between {@code :413} and {@code :414} is a positive finding
 * rather than a failure to look. The string {@code IF WS-VALIDATION-FAIL-REASON = 0} occurs exactly
 * twice in the 731 lines of that program, at {@code :211} and at {@code :372}, and at neither of
 * those positions does it separate the two boundary blocks.</p>
 *
 * <h2>What this class does NOT assert</h2>
 *
 * <p>Assumptions: the subject counts no rejects, sets no return code, builds no posted transaction
 * and writes no record, so none of those appears below. The reject tally and the exit tier belong to
 * {@code com.carddemo.batch.job.PostTransactionsJob}, and the record image belongs to
 * {@code com.carddemo.batch.mapper.TransactionRejectRecordMapper}. The wire renderings this class
 * does assert are taken from {@link RejectReason}'s and {@link PostingValidationResult}'s own padding
 * accessors, which is the vocabulary this tier pins; the byte layout of the surrounding record is not
 * asserted here.</p>
 *
 * <p>Assumptions: no transaction boundary is asserted anywhere below, because the production package
 * charter fixes that no method in {@code com.carddemo.batch.service} carries a transaction
 * annotation. The chunk boundary belongs to the batch configuration and the transaction manager to
 * the data-source configuration, so a boundary asserted here would describe a collaborator this
 * subject does not have.</p>
 *
 * <h2>Why plain mocks and no container</h2>
 *
 * <p>Trade-offs: the subject is instantiated directly with two mocked repositories rather than
 * through an application context. The context would additionally prove the wiring, and that proof is
 * bought instead by the job and repository tiers, which stand up the real context and a real
 * database. What this tier buys in exchange is that every case here runs with no database, no
 * container and no network, so a boundary that depends on one cent or one day can be driven from a
 * value written in this file rather than from a row loaded somewhere else.</p>
 *
 * <p>Alternatives Considered: holding the constructed vectors in a shared abstract base class or a
 * standalone builder type. Both were rejected because the package charter closes this directory to a
 * sixth type and to any subdirectory, and because a vector whose arithmetic decides an inclusive
 * boundary is only auditable beside the assertion that depends on it. The vectors are therefore
 * private helpers of this class.</p>
 *
 * <p>Alternatives Considered: expressing the money vectors as a primitive decimal type, which reads
 * more briefly than a scaled decimal built from a string. Rejected because these values decide
 * boundaries that turn on a single cent, and a binary floating-point type cannot hold 2065.01
 * exactly -- the nearest representable value is a shade either side of it, so the one-cent
 * discrimination this file exists to prove could be decided by a representation error rather than by
 * the transcribed guard. Every amount below is therefore a decimal constructed from its decimal
 * string and held at two places, matching {@code WS-TEMP-BAL PIC S9(09)V99} at
 * {@code app/cbl/CBTRN02C.cbl:187}. The prohibition is not merely observed here: it is enforced for
 * the whole tree by the architecture rules in {@code common-lib}, which fail the build on a binary
 * floating-point type anywhere in the money path.</p>
 *
 * <p>Refactoring Rationale: this class replaces an earlier revision that asserted nine of these
 * rulings and left three unasserted. It carried no case for the fixed-width renderings, no case for
 * the ten-character reference sub-string, and no comparison of the four description literals against
 * the text the reference moves, so a rendering or a literal could have drifted without anything here
 * failing. Its vectors were also invented rather than taken from the committed fixtures, which meant
 * a boundary case could agree with this file while disagreeing with the oracle the migration is
 * measured against. Every vector below is now decoded from a fixture and every ruling carries the
 * baseline line or expectation file it pins.</p>
 *
 * <h2>Provenance</h2>
 *
 * <p>Baseline paths above and below are cited for provenance only. Nothing under {@code app/**} is
 * read at run time and nothing under it is altered by this migration: the reference is the
 * behavioural oracle and stays byte-identical. Where the migrated code departs from the reference,
 * the reference does one thing, the Java does another, and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Line numbers refer to the source as
 * committed, and columns 73 to 80 of a COBOL or JCL line carry a sequence field that is not part of
 * the statement.</p>
 */
@DisplayName("the posting validation chain")
class PostingValidationServiceTest {

    /**
     * The stored form of the 26-character originating stamp, used to prove the reference sub-string.
     *
     * <p>Assumptions: {@code DALYTRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA06Y.cpy:16} holds a
     * date, a single space, a time and six fractional digits, which is what the fixtures carry --
     * {@code tests/fixtures/posting/boundary_expiry_equal/dailytran.txt} holds
     * {@code 2024-12-13 19:27:53.000000} at that field. The pattern is declared here so the
     * whole-field form can be built exactly and compared against the ten-character slice the guard at
     * {@code app/cbl/CBTRN02C.cbl:414} actually reads.</p>
     */
    private static final DateTimeFormatter STORED_STAMP_FORM =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");

    /**
     * The declared width of the originating stamp, 26 characters.
     *
     * <p>Assumptions: the width is asserted against {@link CopybookLayout} rather than trusted, so
     * this constant is a statement of intent that the layout registry confirms. Writing an offset
     * table in this file was rejected outright; {@code tests/README.md} section 12 requires record
     * layouts to be resolved through the copybook path and never duplicated.</p>
     */
    private static final int ORIGINATING_STAMP_WIDTH = 26;

    /**
     * The number of leading characters of the originating stamp the expiration guard compares, ten.
     *
     * <p>Assumptions: {@code app/cbl/CBTRN02C.cbl:414} reads
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)}, and that reference modification is
     * the entire reason this constant is not simply the field width. Ten of the twenty-six characters
     * take part in the comparison and the other sixteen do not.</p>
     */
    private static final int COMPARED_DATE_LENGTH = 10;

    /**
     * The card number every posting fixture carries.
     *
     * <p>Assumptions: the value is read from the synthetic seed-derived fixtures rather than invented,
     * so the vectors below decide the same boundaries the committed expectation files decide. All four
     * of {@code boundary_exact_limit}, {@code reject_102_overlimit}, {@code boundary_expiry_equal} and
     * {@code reject_103_expired} carry it at {@code DALYTRAN-CARD-NUM}, offset 262 of the 350-byte
     * record.</p>
     */
    private static final String FIXTURE_CARD_NUMBER = "4859452612877065";

    /** The account identifier the posting fixtures cross-reference, account seven. */
    private static final Long FIXTURE_ACCOUNT_ID = 7L;

    /** The customer identifier the posting fixtures carry on the cross-reference row. */
    private static final Long FIXTURE_CUSTOMER_ID = 7L;

    /**
     * The credit limit the posting fixtures declare, 2065.00.
     *
     * <p>Assumptions: the four fixtures hold the twelve characters {@code 00000020650} followed by a
     * positive-zero sign overpunch, written as an opening brace, at {@code ACCT-CREDIT-LIMIT}, offset
     * 24 of the 300-byte account record. Decoding that overpunch is what turns
     * {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:8} into exactly this
     * value; read as plain digits it would come to ten times as much.</p>
     */
    private static final BigDecimal FIXTURE_CREDIT_LIMIT = new BigDecimal("2065.00");

    /**
     * The current balance the posting fixtures declare, 193.00.
     *
     * <p>Assumptions: this value takes NO part in any credit-limit decision and is carried only so the
     * constructed accounts match the fixtures they stand for. The quantity the guard at
     * {@code app/cbl/CBTRN02C.cbl:407} compares is formed at {@code :403-405} from the cycle
     * accumulators, and {@code ACCT-CURR-BAL} is absent from that expression.</p>
     */
    private static final BigDecimal FIXTURE_CURRENT_BALANCE = new BigDecimal("193.00");

    /**
     * The expiration date the posting fixtures declare, the thirteenth of December 2024.
     *
     * <p>Assumptions: the fixtures hold {@code 2024-12-13} at offset 58 of the account record, the
     * field the reference spells {@code ACCT-EXPIRAION-DATE} at {@code app/cpy/CVACT01Y.cpy:11}. The
     * baseline spells it that way and the migrated column is named {@code expiration_date}; the
     * baseline spelling is cited as it stands.</p>
     */
    private static final LocalDate FIXTURE_EXPIRATION_DATE = LocalDate.of(2024, 12, 13);

    /**
     * The time of day the posting fixtures carry inside the originating stamp.
     *
     * <p>Assumptions: a non-zero time of day is what makes the ten-character slice observable. Were
     * the fixtures to carry midnight, a whole-field comparison and a ten-character comparison would
     * agree on the equal-date vector and the reference sub-string could not be pinned at all.</p>
     */
    private static final LocalTime FIXTURE_TIME_OF_DAY = LocalTime.of(19, 27, 53);

    /**
     * The amount that lands the projection exactly on the credit limit, 2065.00.
     *
     * <p>Assumptions: {@code tests/fixtures/posting/boundary_exact_limit/dailytran.txt} holds the
     * eleven characters {@code 0000020650} followed by a positive-zero sign overpunch, written as an
     * opening brace, at {@code DALYTRAN-AMT}, offset 132, which
     * {@code DALYTRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA06Y.cpy:10} decodes to this value.
     * With both cycle accumulators at zero the projection equals the limit exactly, and
     * {@code tests/golden/posting/boundary_exact_limit/dalyrejs.expected} is an EMPTY file, so the
     * reference posts it.</p>
     */
    private static final BigDecimal AT_LIMIT_AMOUNT = new BigDecimal("2065.00");

    /**
     * The amount that puts the projection one cent beyond the credit limit, 2065.01.
     *
     * <p>Assumptions: {@code tests/fixtures/posting/reject_102_overlimit/dailytran.txt} holds
     * {@code 0000020650A} at the same offset, whose trailing {@code A} is a zoned overpunch standing
     * for a positive one digit, giving one cent more than {@link #AT_LIMIT_AMOUNT}. Its expectation
     * file holds one 430-character reject record, so the reference refuses it. The pair differs in
     * that single character, which is what makes the boundary a boundary.</p>
     */
    private static final BigDecimal ONE_CENT_OVER_AMOUNT = new BigDecimal("2065.01");

    /**
     * The amount the two expiration fixtures carry, 504.77, far below the credit limit.
     *
     * <p>Assumptions: both {@code boundary_expiry_equal} and {@code reject_103_expired} hold
     * {@code 0000005047G} at {@code DALYTRAN-AMT}, whose trailing {@code G} is a zoned overpunch
     * standing for a positive seven digit, so the eleven characters decode to this value. It is used
     * for every expiration case because a projection of 504.77 against a limit of 2065.00 cannot
     * reach the credit-limit guard's failing arm, which isolates the expiration boundary from the
     * credit-limit one.</p>
     */
    private static final BigDecimal UNDER_LIMIT_AMOUNT = new BigDecimal("504.77");

    /**
     * An originating date comfortably inside the expiration date, the tenth of June 2022.
     *
     * <p>Assumptions: this is the date the two credit-limit fixtures carry, so the credit-limit cases
     * below cannot have their verdict decided by the expiration guard. It is the mirror image of
     * {@link #UNDER_LIMIT_AMOUNT}: each boundary is driven with the other condition held safely
     * passing.</p>
     */
    private static final LocalDate WITHIN_EXPIRY_DATE = LocalDate.of(2022, 6, 10);

    /**
     * The originating date one day beyond the expiration date, the fourteenth of December 2024.
     *
     * <p>Assumptions: {@code tests/fixtures/posting/reject_103_expired/dailytran.txt} carries
     * {@code 2024-12-14 19:27:53.000000}, one day past {@link #FIXTURE_EXPIRATION_DATE}, and its
     * expectation file holds one reject record whose trailer reads
     * {@code 0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION}.</p>
     */
    private static final LocalDate ONE_DAY_PAST_EXPIRY_DATE = LocalDate.of(2024, 12, 14);

    /** A zero cycle accumulator at the scale the projection is held to. */
    private static final BigDecimal ZERO_CYCLE_AMOUNT = new BigDecimal("0.00");

    /** The by-card access path the cross-reference lookup reads, stubbed per case. */
    private final CardXrefRepository crossReferences = mock(CardXrefRepository.class);

    /** The by-account access path the account lookup reads, stubbed per case. */
    private final AccountRepository accounts = mock(AccountRepository.class);

    /** The subject, constructed over the two mocked access paths with no context involved. */
    private final PostingValidationService service =
            new PostingValidationService(this.crossReferences, this.accounts);

    /**
     * Builds an account carrying the fixtures' limit, balance and expiration date.
     *
     * @param cycleCredit the cycle credit accumulator opening the projection, of type
     *     {@link BigDecimal} at scale two
     * @param cycleDebit the cycle debit accumulator the projection subtracts, of type
     *     {@link BigDecimal} at scale two
     * @param expirationDate the date the originating date is compared against, of type
     *     {@link LocalDate}
     * @return an account matching the posting fixtures in every field the two guards read, never
     *     {@code null}
     */
    private static Account fixtureAccount(BigDecimal cycleCredit, BigDecimal cycleDebit,
            LocalDate expirationDate) {
        return account(FIXTURE_CURRENT_BALANCE, FIXTURE_CREDIT_LIMIT, expirationDate, cycleCredit,
                cycleDebit);
    }

    /**
     * Builds an account with every field the two guards read supplied independently.
     *
     * @param currentBalance the stored balance, of type {@link BigDecimal}, which no guard reads and
     *     which is varied only to prove that fact
     * @param creditLimit the limit the projection is compared against, of type {@link BigDecimal}
     * @param expirationDate the date the originating date is compared against, of type
     *     {@link LocalDate}
     * @param cycleCredit the cycle credit accumulator opening the projection, of type
     *     {@link BigDecimal}
     * @param cycleDebit the cycle debit accumulator the projection subtracts, of type
     *     {@link BigDecimal}
     * @return the constructed account, never {@code null}
     */
    private static Account account(BigDecimal currentBalance, BigDecimal creditLimit,
            LocalDate expirationDate, BigDecimal cycleCredit, BigDecimal cycleDebit) {
        // WHY : Assumptions: the remaining components are filled from the fixtures rather than left
        //       null so that a constructed account is a faithful stand-in for the row the reference
        //       reads. The open and reissue dates and the cash limit are read by no paragraph this
        //       subject transcribes -- app/cbl/CBTRN02C.cbl:393-422 touches only the limit, the two
        //       cycle accumulators and the expiration date -- so their values cannot decide a case.
        return new Account(FIXTURE_ACCOUNT_ID, "Y", currentBalance, creditLimit,
                new BigDecimal("264.00"), LocalDate.of(2012, 10, 12), expirationDate,
                FIXTURE_EXPIRATION_DATE, cycleCredit, cycleDebit, "A000000000", "DEFAULT");
    }

    /**
     * Builds a daily transaction with the amount and originating stamp a case needs.
     *
     * @param amount the transaction amount the projection adds, of type {@link BigDecimal} at scale
     *     two
     * @param originatingStamp the stamp whose leading ten characters the expiration guard compares, of
     *     type {@link LocalDateTime}
     * @return the constructed transaction, never {@code null}
     */
    private static DailyTransaction transaction(BigDecimal amount, LocalDateTime originatingStamp) {
        // WHY : Assumptions: the record carries NO account identifier at all, which is why the account
        //       lookup has to be keyed from the cross-reference. app/cpy/CVTRA06Y.cpy declares
        //       thirteen fields and none of them is an account, and app/cbl/CBTRN02C.cbl:394 moves
        //       XREF-ACCT-ID rather than anything from this record into the account key.
        return new DailyTransaction("0000000000000001", "01", "0001", "POS", "GROCERY", amount,
                999999999L, "STORE", "SEATTLE", "98101", FIXTURE_CARD_NUMBER, originatingStamp,
                originatingStamp);
    }

    /**
     * Builds the 26-character originating stamp for a calendar date at the fixtures' time of day.
     *
     * @param date the calendar date the stamp's leading ten characters spell, of type
     *     {@link LocalDate}
     * @return the stamp at {@link #FIXTURE_TIME_OF_DAY}, never {@code null}
     */
    private static LocalDateTime stampOn(LocalDate date) {
        return date.atTime(FIXTURE_TIME_OF_DAY);
    }

    /**
     * Builds the cross-reference row the fixtures' card number resolves to.
     *
     * @return a row naming {@link #FIXTURE_ACCOUNT_ID}, never {@code null}
     */
    private static CardXref crossReference() {
        return new CardXref(FIXTURE_CARD_NUMBER, FIXTURE_CUSTOMER_ID, FIXTURE_ACCOUNT_ID);
    }

    /**
     * The two lookups, the guard between them, and what a decision hands back.
     *
     * <p>Assumptions: these cases drive the resolving entry point rather than the decision half
     * wherever the ruling is about a read HAPPENING or NOT HAPPENING. The guard at
     * {@code app/cbl/CBTRN02C.cbl:372} performs {@code 1500-B-LOOKUP-ACCT} only while no reason has
     * been assigned, so its effect is the absence of a read and only the entry point that owns both
     * reads can be observed to skip one.</p>
     */
    @Nested
    @DisplayName("the two lookups and the guard between them")
    class LookupOrder {

        /**
         * A transaction failing no condition is accepted and carries its projection at scale two.
         *
         * <p>Pins {@code app/cbl/CBTRN02C.cbl:211}, where a reason of zero selects
         * {@code 2000-POST-TRANSACTION} over the reject writer, and the projection formed at
         * {@code :403-405}.</p>
         */
        @Test
        @DisplayName("accept a transaction that fails no condition")
        void acceptsATransactionFailingNoCondition() {
            PostingValidationResult outcome = PostingValidationServiceTest.this.service.decide(
                    transaction(AT_LIMIT_AMOUNT, stampOn(WITHIN_EXPIRY_DATE)),
                    Optional.of(crossReference()),
                    Optional.of(fixtureAccount(ZERO_CYCLE_AMOUNT, ZERO_CYCLE_AMOUNT,
                            FIXTURE_EXPIRATION_DATE)));

            assertThat(outcome.isAccepted()).isTrue();
            assertThat(outcome.rejectReason()).isEmpty();

            // WHY : Assumptions: the scale is asserted as an equality on the scale itself rather than
            //       through a value comparison, because WS-TEMP-BAL PIC S9(09)V99 at
            //       app/cbl/CBTRN02C.cbl:187 has room for exactly two decimal places. A projection
            //       arriving at scale 0 or 3 compares equal to this one under compareTo, so a value
            //       comparison alone would accept a quantity the reference field cannot hold.
            assertThat(outcome.projectedCycleBalance()).isEqualByComparingTo(AT_LIMIT_AMOUNT);
            assertThat(outcome.projectedCycleBalance().scale()).isEqualTo(2);
        }

        /**
         * An unresolved card number reports reason 100 and leaves the account path entirely unread.
         *
         * <p>Pins the guard at {@code app/cbl/CBTRN02C.cbl:372} together with the reject site at
         * {@code :385-387}, and the trailer of
         * {@code tests/golden/posting/reject_100_card_missing/dalyrejs.expected}, which reads
         * {@code 0100INVALID CARD NUMBER FOUND}.</p>
         */
        @Test
        @DisplayName("report reason 100 and never consult the account path")
        void unresolvedCardReportsReasonOneHundredAndLeavesTheAccountPathUnread() {
            when(PostingValidationServiceTest.this.crossReferences
                    .findByCardNum(FIXTURE_CARD_NUMBER)).thenReturn(Optional.empty());

            PostingDecision decision = PostingValidationServiceTest.this.service
                    .validate(transaction(ONE_CENT_OVER_AMOUNT, stampOn(ONE_DAY_PAST_EXPIRY_DATE)));

            assertThat(decision.outcome().rejectReason())
                    .hasValue(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);
            assertThat(decision.outcome().rejectReason().orElseThrow().description())
                    .isEqualTo("INVALID CARD NUMBER FOUND");

            // WHY : Alternatives Considered: asserting only the reported reason code, which every
            //       first-reason-wins implementation would satisfy. Rejected because a chain that
            //       reads the account and then discards the surplus finding reports the same code
            //       while issuing a query the reference never issues: :372 tests the reason and :373
            //       performs the paragraph only inside that test, so the read genuinely does not
            //       happen. Only an interaction assertion can tell the two implementations apart, and
            //       it is what makes reason 100 EXCLUDE reason 101 rather than merely outrank it.
            // WHY : Assumptions: the vector deliberately fails both boundary conditions as well --
            //       one cent over the limit and one day past expiry -- so nothing but the guard can
            //       account for the reported reason.
            //       The guard is app/cbl/CBTRN02C.cbl:372; the two conditions this vector also
            //       trips are :407 and :414.
            verifyNoInteractions(PostingValidationServiceTest.this.accounts);
        }

        /**
         * An absent account reports reason 101, keyed on the identifier the cross-reference named.
         *
         * <p>Pins {@code app/cbl/CBTRN02C.cbl:394}, which moves {@code XREF-ACCT-ID} into the account
         * key, and the reject site at {@code :397-399}, whose trailer appears in
         * {@code tests/golden/posting/reject_101_acct_missing/dalyrejs.expected} as
         * {@code 0101ACCOUNT RECORD NOT FOUND}.</p>
         */
        @Test
        @DisplayName("report reason 101 keyed on the cross-reference account identifier")
        void absentAccountReportsReasonOneHundredOneKeyedOnTheCrossReference() {
            when(PostingValidationServiceTest.this.crossReferences
                    .findByCardNum(FIXTURE_CARD_NUMBER)).thenReturn(Optional.of(crossReference()));
            when(PostingValidationServiceTest.this.accounts.findByAccountId(FIXTURE_ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            PostingDecision decision = PostingValidationServiceTest.this.service
                    .validate(transaction(UNDER_LIMIT_AMOUNT, stampOn(WITHIN_EXPIRY_DATE)));

            assertThat(decision.outcome().rejectReason())
                    .hasValue(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
            assertThat(decision.outcome().rejectReason().orElseThrow().description())
                    .isEqualTo("ACCOUNT RECORD NOT FOUND");

            // WHY : Assumptions: the identifier is asserted to have come from the cross-reference row
            //       and not from the feed record, which is a distinction the reference makes at :394
            //       and which the feed record could not supply in any case -- app/cpy/CVTRA06Y.cpy
            //       declares thirteen fields and none is an account. Verifying the exact argument is
            //       what pins the key's provenance; a read verified only to have happened would pass
            //       for an implementation keyed on the card number instead.
            verify(PostingValidationServiceTest.this.accounts, times(1))
                    .findByAccountId(FIXTURE_ACCOUNT_ID);
            assertThat(crossReference().getAccountId()).isEqualTo(FIXTURE_ACCOUNT_ID);
        }

        /**
         * A resolving validation hands back both records it read, so its caller needs no second read.
         *
         * <p>Pins the single-read expectation of {@code app/cbl/CBTRN02C.cbl:440-442}, which updates
         * the category balance, rewrites the account and writes the transaction from the records the
         * validation paragraphs already resolved.</p>
         */
        @Test
        @DisplayName("hand back both records the two reads resolved, each read once")
        void theDecisionCarriesTheRecordsItRead() {
            CardXref resolvedCrossReference = crossReference();
            Account resolvedAccount = fixtureAccount(ZERO_CYCLE_AMOUNT, ZERO_CYCLE_AMOUNT,
                    FIXTURE_EXPIRATION_DATE);
            when(PostingValidationServiceTest.this.crossReferences
                    .findByCardNum(FIXTURE_CARD_NUMBER))
                    .thenReturn(Optional.of(resolvedCrossReference));
            when(PostingValidationServiceTest.this.accounts.findByAccountId(FIXTURE_ACCOUNT_ID))
                    .thenReturn(Optional.of(resolvedAccount));

            PostingDecision decision = PostingValidationServiceTest.this.service
                    .validate(transaction(UNDER_LIMIT_AMOUNT, stampOn(WITHIN_EXPIRY_DATE)));

            assertThat(decision.outcome().isAccepted()).isTrue();

            // WHY : Assumptions: identity is asserted rather than equality, because a decision
            //       carrying equal-but-distinct objects would leave a caller free to act on a row read
            //       at a different moment, and the reference resolves each record exactly once per
            //       feed row. Asserting the counts alongside the identities closes the other half:
            //       the same instance could otherwise be returned by a second, redundant read.
            //       The three uses that need these records are app/cbl/CBTRN02C.cbl:440, :441 and
            //       :442.
            assertThat(decision.crossReference()).containsSame(resolvedCrossReference);
            assertThat(decision.account()).containsSame(resolvedAccount);
            verify(PostingValidationServiceTest.this.crossReferences, times(1))
                    .findByCardNum(FIXTURE_CARD_NUMBER);
            verify(PostingValidationServiceTest.this.accounts, times(1))
                    .findByAccountId(FIXTURE_ACCOUNT_ID);
        }

        /**
         * A reason 101 decision still carries the cross-reference row that resolved.
         *
         * <p>Pins the mutually exclusive branches of the account read at
         * {@code app/cbl/CBTRN02C.cbl:396} and {@code :400}: reason 101 is exactly the state in which
         * the card resolved and the account it named did not.</p>
         */
        @Test
        @DisplayName("carry the resolved cross-reference back on a reason 101 decision")
        void anAbsentAccountStillCarriesTheCrossReference() {
            CardXref resolvedCrossReference = crossReference();
            when(PostingValidationServiceTest.this.crossReferences
                    .findByCardNum(FIXTURE_CARD_NUMBER))
                    .thenReturn(Optional.of(resolvedCrossReference));
            when(PostingValidationServiceTest.this.accounts.findByAccountId(FIXTURE_ACCOUNT_ID))
                    .thenReturn(Optional.empty());

            PostingDecision decision = PostingValidationServiceTest.this.service
                    .validate(transaction(UNDER_LIMIT_AMOUNT, stampOn(WITHIN_EXPIRY_DATE)));

            assertThat(decision.outcome().rejectReason())
                    .hasValue(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
            assertThat(decision.crossReference()).containsSame(resolvedCrossReference);
            assertThat(decision.account()).isEmpty();
        }

        /**
         * A decision refuses to be assembled with any component absent.
         *
         * <p>Pins a precondition of the decision type rather than a single baseline statement, and the
         * reason it exists is still a property of the reference: the two reads at
         * {@code app/cbl/CBTRN02C.cbl:383} and {@code :395} each answer either with a record or with
         * their {@code INVALID KEY} branch at {@code :384} and {@code :396}, which is exactly the
         * present-or-absent pair an optional carries. A {@code null} component corresponds to neither
         * branch, so it reports nothing at all and is a wiring defect in the producer rather than a
         * state a consumer should have to test for.</p>
         */
        @Test
        @DisplayName("refuse a decision assembled with a null component")
        void aDecisionRefusesANullComponent() {
            PostingValidationResult accepted = PostingValidationResult.accepted(AT_LIMIT_AMOUNT);

            assertThatNullPointerException().isThrownBy(
                    () -> new PostingDecision(null, Optional.empty(), Optional.empty()));
            assertThatNullPointerException().isThrownBy(
                    () -> new PostingDecision(accepted, null, Optional.empty()));
            assertThatNullPointerException().isThrownBy(
                    () -> new PostingDecision(accepted, Optional.empty(), null));
        }
    }


    /**
     * The credit-limit boundary, stated in both framings and driven one cent either side.
     *
     * <p>Assumptions: <b>the baseline comparison is a PASS guard, so the reject predicate is its
     * STRICT complement.</b> {@code app/cbl/CBTRN02C.cbl:407} reads
     * {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} and takes {@code CONTINUE} at {@code :408} when it
     * holds, so the inclusive operator belongs to the arm that POSTS. Restated as the predicate an
     * assertion is usually phrased around, a transaction rejects only when the projection is strictly
     * greater than the limit. Writing the inclusive operator into the reject predicate instead would
     * refuse the exactly-at-limit transaction the reference posts, which is why both framings are
     * stated here and both sides of the boundary are driven below.</p>
     *
     * <p>Assumptions: the two cases differ in the single character the fixtures differ in. Both carry
     * the fixtures' originating date of June 2022, comfortably inside the December 2024 expiration
     * date, so the expiration guard cannot decide either verdict.</p>
     */
    @Nested
    @DisplayName("the inclusive credit-limit guard at line 407")
    class CreditLimitBoundary {

        /**
         * A projection landing exactly on the credit limit is accepted.
         *
         * <p>Pins the inclusive arm of {@code app/cbl/CBTRN02C.cbl:407-408} and the fixture pairing
         * {@code tests/fixtures/posting/boundary_exact_limit}, whose expectation file
         * {@code tests/golden/posting/boundary_exact_limit/dalyrejs.expected} is EMPTY.</p>
         */
        @Test
        @DisplayName("post a projection landing exactly on the credit limit")
        void aProjectionExactlyOnTheLimitPosts() {
            Account atTheLimit = fixtureAccount(ZERO_CYCLE_AMOUNT, ZERO_CYCLE_AMOUNT,
                    FIXTURE_EXPIRATION_DATE);

            PostingValidationResult outcome = PostingValidationServiceTest.this.service.decide(
                    transaction(AT_LIMIT_AMOUNT, stampOn(WITHIN_EXPIRY_DATE)),
                    Optional.of(crossReference()), Optional.of(atTheLimit));

            assertThat(outcome.isAccepted()).isTrue();
            assertThat(outcome.rejectReason()).isEmpty();

            // WHY : Assumptions: the projection is asserted to have reached the limit exactly, so the
            //       case cannot pass by landing safely under it. An equality here is what distinguishes
            //       the boundary from an ordinary passing amount, and it is the arithmetic the fixture
            //       README works through: both cycle accumulators are zero, so the projection formed
            //       at :403-405 is the transaction amount alone.
            assertThat(outcome.projectedCycleBalance())
                    .isEqualByComparingTo(atTheLimit.getCreditLimit());
        }

        /**
         * A projection one cent beyond the credit limit reports reason 102.
         *
         * <p>Pins the failing arm at {@code app/cbl/CBTRN02C.cbl:410-412} and the trailer of
         * {@code tests/golden/posting/reject_102_overlimit/dalyrejs.expected}, which reads
         * {@code 0102OVERLIMIT TRANSACTION}.</p>
         */
        @Test
        @DisplayName("report reason 102 one cent beyond the credit limit")
        void oneCentBeyondTheLimitReportsReasonOneHundredTwo() {
            Account oneCentUnder = fixtureAccount(ZERO_CYCLE_AMOUNT, ZERO_CYCLE_AMOUNT,
                    FIXTURE_EXPIRATION_DATE);

            PostingValidationResult outcome = PostingValidationServiceTest.this.service.decide(
                    transaction(ONE_CENT_OVER_AMOUNT, stampOn(WITHIN_EXPIRY_DATE)),
                    Optional.of(crossReference()), Optional.of(oneCentUnder));

            assertThat(outcome.rejectReason()).hasValue(RejectReason.OVER_CREDIT_LIMIT);
            assertThat(outcome.rejectReason().orElseThrow().description())
                    .isEqualTo("OVERLIMIT TRANSACTION");

            // WHY : Assumptions: the gap between this vector and the accepted one is asserted to be
            //       exactly one cent, so the pair demonstrably brackets the boundary rather than
            //       straddling some wider interval in which a wrong operator would still pass both.
            //       The two fixtures differ in one byte at DALYTRAN-AMT:
            //       tests/fixtures/posting/boundary_exact_limit against
            //       tests/fixtures/posting/reject_102_overlimit.
            assertThat(ONE_CENT_OVER_AMOUNT.subtract(AT_LIMIT_AMOUNT))
                    .isEqualByComparingTo(new BigDecimal("0.01"));
            assertThat(outcome.projectedCycleBalance())
                    .isGreaterThan(oneCentUnder.getCreditLimit());
        }
    }

    /**
     * The expiration boundary, stated in both framings, and the ten-character reference sub-string.
     *
     * <p>Assumptions: <b>this comparison is also a PASS guard, so an equal date POSTS.</b>
     * {@code app/cbl/CBTRN02C.cbl:414} reads
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} and takes {@code CONTINUE} at
     * {@code :415} when it holds, so a transaction dated exactly ON the expiration date posts and only
     * one dated later rejects. The field name is misspelled in the reference, at
     * {@code app/cpy/CVACT01Y.cpy:11}, and is cited as it is spelled there because that spelling is
     * the contract; the migrated column is named {@code expiration_date}.</p>
     *
     * <p>Assumptions: every case here carries {@link #UNDER_LIMIT_AMOUNT} against a limit of 2065.00,
     * so the credit-limit guard cannot fire and the expiration boundary is isolated. The two fixtures
     * this group stands on are built the same way for the same reason.</p>
     */
    @Nested
    @DisplayName("the inclusive expiration guard at line 414")
    class ExpirationBoundary {

        /**
         * A transaction dated exactly on the expiration date is accepted.
         *
         * <p>Pins the inclusive arm of {@code app/cbl/CBTRN02C.cbl:414-415} and the fixture pairing
         * {@code tests/fixtures/posting/boundary_expiry_equal}, whose expectation file
         * {@code tests/golden/posting/boundary_expiry_equal/dalyrejs.expected} is EMPTY.</p>
         */
        @Test
        @DisplayName("post a transaction dated exactly on the expiration date")
        void anEqualDatePosts() {
            PostingValidationResult outcome = PostingValidationServiceTest.this.service.decide(
                    transaction(UNDER_LIMIT_AMOUNT, stampOn(FIXTURE_EXPIRATION_DATE)),
                    Optional.of(crossReference()),
                    Optional.of(fixtureAccount(ZERO_CYCLE_AMOUNT, ZERO_CYCLE_AMOUNT,
                            FIXTURE_EXPIRATION_DATE)));

            assertThat(outcome.isAccepted()).isTrue();
            assertThat(outcome.rejectReason()).isEmpty();
        }

        /**
         * A transaction dated one day past the expiration date reports reason 103.
         *
         * <p>Pins the failing arm at {@code app/cbl/CBTRN02C.cbl:417-419} and the trailer of
         * {@code tests/golden/posting/reject_103_expired/dalyrejs.expected}, which reads
         * {@code 0103TRANSACTION RECEIVED AFTER ACCT EXPIRATION}.</p>
         */
        @Test
        @DisplayName("report reason 103 one day past the expiration date")
        void oneDayPastExpiryReportsReasonOneHundredThree() {
            PostingValidationResult outcome = PostingValidationServiceTest.this.service.decide(
                    transaction(UNDER_LIMIT_AMOUNT, stampOn(ONE_DAY_PAST_EXPIRY_DATE)),
                    Optional.of(crossReference()),
                    Optional.of(fixtureAccount(ZERO_CYCLE_AMOUNT, ZERO_CYCLE_AMOUNT,
                            FIXTURE_EXPIRATION_DATE)));

            assertThat(outcome.rejectReason())
                    .hasValue(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION);
            assertThat(outcome.rejectReason().orElseThrow().description())
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION");

            // WHY : Assumptions: the two expiration vectors are asserted to be exactly one day apart,
            //       so the pair brackets the boundary rather than merely sitting on opposite sides of
            //       some wider gap. The fixtures differ in that one day and in nothing else.
            //       The two ORIG-TS values are 2024-12-13 and 2024-12-14, in
            //       tests/fixtures/posting/boundary_expiry_equal and
            //       tests/fixtures/posting/reject_103_expired.
            assertThat(ONE_DAY_PAST_EXPIRY_DATE).isEqualTo(FIXTURE_EXPIRATION_DATE.plusDays(1));
        }

        /**
         * Only the leading ten characters of the 26-character originating stamp take part.
         *
         * <p>Pins the reference modification {@code (1:10)} at {@code app/cbl/CBTRN02C.cbl:414}
         * against the field width {@code DALYTRAN-ORIG-TS PIC X(26)} at
         * {@code app/cpy/CVTRA06Y.cpy:16}, resolved through {@link CopybookLayout} rather than from a
         * width written out here.</p>
         */
        @Test
        @DisplayName("compare only the date portion of the 26-character originating stamp")
        void onlyTheDatePortionOfTheOriginatingStampIsCompared() {
            Account expiringToday = fixtureAccount(ZERO_CYCLE_AMOUNT, ZERO_CYCLE_AMOUNT,
                    FIXTURE_EXPIRATION_DATE);
            LocalDateTime firstMomentOfTheDay = FIXTURE_EXPIRATION_DATE.atStartOfDay();
            LocalDateTime lastMomentOfTheDay = FIXTURE_EXPIRATION_DATE.atTime(23, 59, 59);

            // WHY : Assumptions: a whole-field comparison is shown to DISAGREE with the reference on
            //       this very vector, which is what makes the sub-string observable rather than
            //       incidental. The ten characters of the expiration date are a strict PREFIX of the
            //       26-character stamp, so comparing the two whole strings lexically makes the shorter
            //       the lesser, the inclusive guard at :414 fails, and the transaction is refused --
            //       where the reference compares only the first ten, finds them equal, and posts. The
            //       expectation file tests/golden/posting/boundary_expiry_equal/dalyrejs.expected is
            //       EMPTY, so the reference's answer is the posting one.
            String storedStamp = STORED_STAMP_FORM.format(lastMomentOfTheDay);
            assertThat(storedStamp).hasSize(ORIGINATING_STAMP_WIDTH);
            assertThat(storedStamp.substring(0, COMPARED_DATE_LENGTH))
                    .isEqualTo(FIXTURE_EXPIRATION_DATE.toString());
            assertThat(FIXTURE_EXPIRATION_DATE.toString().compareTo(storedStamp)).isNegative();

            // WHY : Assumptions: both extremes of the same calendar day are driven, so the sixteen
            //       characters outside the slice are shown to move the verdict not at all. A single
            //       time of day could agree with the reference by coincidence; midnight and the last
            //       second of the day cannot both do so unless the comparison genuinely ignores them.
            //       The sixteen characters outside the slice are positions 11 to 26 of DALYTRAN-
            //       ORIG-TS PIC X(26) at app/cpy/CVTRA06Y.cpy:16, which the (1:10) modification at
            //       app/cbl/CBTRN02C.cbl:414 does not read.
            assertThat(PostingValidationServiceTest.this.service
                    .decide(transaction(UNDER_LIMIT_AMOUNT, firstMomentOfTheDay),
                            Optional.of(crossReference()), Optional.of(expiringToday))
                    .isAccepted()).isTrue();
            assertThat(PostingValidationServiceTest.this.service
                    .decide(transaction(UNDER_LIMIT_AMOUNT, lastMomentOfTheDay),
                            Optional.of(crossReference()), Optional.of(expiringToday))
                    .isAccepted()).isTrue();

            // WHY : Alternatives Considered: writing the width and offset of the stamp as literals in
            //       this file. Rejected because tests/README.md section 12 requires a record layout to
            //       be resolved through the copybook path and never duplicated, and a second copy of a
            //       geometry is a copy that can drift. The registry is asked instead, so this case
            //       fails if the declared field ever stops being 26 characters at offset 278.
            CopybookLayout.FieldSpec originatingStamp =
                    CopybookLayout.layout("DALYTRAN").field("DALYTRAN-ORIG-TS");
            assertThat(originatingStamp.length()).isEqualTo(ORIGINATING_STAMP_WIDTH);
            assertThat(originatingStamp.start()).isEqualTo(278);
            assertThat(COMPARED_DATE_LENGTH).isLessThan(originatingStamp.length());
        }
    }


    /**
     * The precedence between the two boundary reasons, which no committed fixture exercises.
     *
     * <p>Assumptions: <b>reason 103 beats reason 102, because the reference records a reason by moving
     * a value into one shared field and the later assignment overwrites the earlier one.</b> The
     * credit-limit block and the expiration block are two SEQUENTIAL, UNGUARDED {@code IF} blocks
     * inside the account read's {@code NOT INVALID KEY} branch: {@code app/cbl/CBTRN02C.cbl:413}
     * closes the first and {@code :414} opens the second with nothing between them that tests whether a
     * reason has already been assigned. A transaction failing both therefore has {@code MOVE 103} at
     * {@code :417} overwrite {@code MOVE 102} at {@code :410}, and the surviving reason is the
     * expiration one.</p>
     *
     * <p>Alternatives Considered: relying on the committed posting fixtures to cover this, which
     * would have needed no new vector. Rejected on measurement rather than on preference: NONE of the
     * posting fixtures makes both conditions fail, because {@code reject_102_overlimit} carries a
     * passing originating date of June 2022 against a December 2024 expiration date, and
     * {@code reject_103_expired} carries a passing amount of 504.77 against a limit of 2065.00. The
     * ruling is consequently unproven by any existing vector, and a naive transcription that reports
     * the first failing condition would agree with every one of those fixtures while disagreeing with
     * the reference here. The vector is therefore constructed in code, which is the only way to reach
     * the overlap without writing a fixture -- and {@code tests/**} is the parity oracle and is never
     * written to.</p>
     */
    @Nested
    @DisplayName("the unguarded boundary pair at lines 407 and 414")
    class BoundaryPrecedence {

        /**
         * A transaction failing BOTH boundary conditions reports 103 and expressly not 102.
         *
         * <p>Pins the overwrite of {@code app/cbl/CBTRN02C.cbl:410} by {@code :417}, made reachable by
         * the absence of any reason test between {@code :413} and {@code :414}.</p>
         */
        @Test
        @DisplayName("report reason 103, not 102, when both boundary guards fail")
        void bothBoundaryFailuresReportReasonOneHundredThree() {
            Account overLimitAndExpired = fixtureAccount(ZERO_CYCLE_AMOUNT, ZERO_CYCLE_AMOUNT,
                    FIXTURE_EXPIRATION_DATE);
            DailyTransaction failingBoth =
                    transaction(ONE_CENT_OVER_AMOUNT, stampOn(ONE_DAY_PAST_EXPIRY_DATE));

            // WHY : Assumptions: both conditions are asserted to HOLD before the reported reason is
            //       examined, so the case cannot pass because the credit-limit condition quietly failed
            //       to fire. Without these two the assertion below would be satisfied by an
            //       implementation in which 102 never arises at all, which is a different defect that
            //       happens to produce the same reported reason on this one vector.
            //       The two conditions are the guards at app/cbl/CBTRN02C.cbl:407 and :414.
            assertThat(PostingValidationServiceTest.this.service.isOverCreditLimit(
                    PostingValidationServiceTest.this.service
                            .computeProjectedBalance(overLimitAndExpired, failingBoth),
                    overLimitAndExpired)).isTrue();
            assertThat(PostingValidationServiceTest.this.service
                    .isReceivedAfterExpiration(overLimitAndExpired, failingBoth)).isTrue();

            PostingValidationResult outcome = PostingValidationServiceTest.this.service.decide(
                    failingBoth, Optional.of(crossReference()), Optional.of(overLimitAndExpired));

            assertThat(outcome.rejectReason())
                    .hasValue(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION);

            // WHY : Assumptions: the negative control is stated explicitly rather than left implicit in
            //       the positive assertion above, because 102 is the specific wrong answer this case
            //       exists to exclude. A chain written as an either-or in the order the source reads
            //       the two blocks reports 102 here, agrees with every single-condition fixture, and
            //       would surface only as a byte-for-byte reject-stream divergence on the population
            //       least likely to appear in a hand-built fixture.
            //       The two assignments are MOVE 102 at app/cbl/CBTRN02C.cbl:410 and MOVE 103 at
            //       :417, and the single-condition expectations that would keep agreeing are
            //       tests/golden/posting/reject_102_overlimit and
            //       tests/golden/posting/reject_103_expired.
            assertThat(outcome.rejectReason().orElseThrow())
                    .isNotEqualTo(RejectReason.OVER_CREDIT_LIMIT);
            assertThat(outcome.rejectReason().orElseThrow().code()).isEqualTo(103);
        }

        /**
         * The surviving reason is the later assignment even when only the expiration condition is new.
         *
         * <p>Pins {@link RejectReason#lastWriterWins(RejectReason)} against the assignment order of
         * {@code app/cbl/CBTRN02C.cbl:410} and {@code :417}, in both argument orders.</p>
         */
        @Test
        @DisplayName("select the later assignment regardless of the order the two are offered")
        void theLaterAssignmentWinsInEitherArgumentOrder() {
            // WHY : Assumptions: both orders are driven because the ruling must be a property of the
            //       baseline's assignment sequence and not of the order a caller happens to supply the
            //       two candidates. An implementation that returned its first argument would satisfy
            //       one of these two assertions and fail the other, which is precisely the confusion
            //       the ruling exists to prevent.
            //       The assignment order is MOVE 102 at app/cbl/CBTRN02C.cbl:410 before MOVE 103 at
            //       :417.
            assertThat(RejectReason.OVER_CREDIT_LIMIT
                    .lastWriterWins(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION))
                    .isEqualTo(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION);
            assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION
                    .lastWriterWins(RejectReason.OVER_CREDIT_LIMIT))
                    .isEqualTo(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION);

            // WHY : Assumptions: the two lookup reasons are asserted to TERMINATE validation while the
            //       two boundary reasons are asserted not to, which is the asymmetry that makes the
            //       overwrite reachable at all. The guard at :372 ends validation after reason 100, and
            //       reason 101 sits in the INVALID KEY branch at :396 that excludes both boundary
            //       tests; neither boundary reason carries any such guard.
            assertThat(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.terminatesValidation())
                    .isTrue();
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.terminatesValidation()).isTrue();
            assertThat(RejectReason.OVER_CREDIT_LIMIT.terminatesValidation()).isFalse();
            assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.terminatesValidation())
                    .isFalse();
        }
    }

    /**
     * Where the compared quantity comes from, and at what scale it is carried.
     *
     * <p>Assumptions: {@code app/cbl/CBTRN02C.cbl:403-405} forms the quantity as
     * {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}, and {@code ACCT-CURR-BAL}
     * takes no part in it. The account record declares all three fields, at
     * {@code app/cpy/CVACT01Y.cpy:7}, {@code :13} and {@code :14}, so substituting the stored balance
     * compiles perfectly well and yields credit-limit decisions that look entirely reasonable while
     * being wrong on every account whose cycle totals differ from its balance.</p>
     */
    @Nested
    @DisplayName("the projected balance and its provenance")
    class ProjectionProvenance {

        /**
         * The projection comes from the cycle accumulators, on a vector where the balance disagrees.
         *
         * <p>Pins {@code app/cbl/CBTRN02C.cbl:403-405} and the width
         * {@code WS-TEMP-BAL PIC S9(09)V99} at {@code :187}.</p>
         */
        @Test
        @DisplayName("form the projection from the cycle accumulators, not the stored balance")
        void theProjectionComesFromTheCycleAccumulators() {
            // WHY : Assumptions: the vector is chosen so the two candidate quantities land on OPPOSITE
            //       sides of the limit, which is what makes the assertion discriminating rather than
            //       merely correct. The cycle-derived projection is 400.00 - 25.00 + 504.77 = 879.77,
            //       comfortably within the 2065.00 limit, whereas the stored balance of 2000.00 plus
            //       the same amount would come to 2504.77 and refuse the transaction. A vector whose
            //       two candidates agreed would pass against either implementation.
            //       The projection is formed at app/cbl/CBTRN02C.cbl:403-405; the balance it must
            //       not use is ACCT-CURR-BAL at app/cpy/CVACT01Y.cpy:7.
            Account balanceDisagreesWithCycle = account(new BigDecimal("2000.00"),
                    FIXTURE_CREDIT_LIMIT, FIXTURE_EXPIRATION_DATE, new BigDecimal("400.00"),
                    new BigDecimal("25.00"));

            PostingValidationResult outcome = PostingValidationServiceTest.this.service.decide(
                    transaction(UNDER_LIMIT_AMOUNT, stampOn(WITHIN_EXPIRY_DATE)),
                    Optional.of(crossReference()), Optional.of(balanceDisagreesWithCycle));

            assertThat(outcome.isAccepted()).isTrue();
            assertThat(outcome.projectedCycleBalance())
                    .isEqualByComparingTo(new BigDecimal("879.77"));

            // WHY : Assumptions: the rejected alternative is asserted to have been genuinely available,
            //       so the case records WHY it discriminates instead of leaving a reader to recompute
            //       it. Had the stored balance been the operand, this projection would have exceeded
            //       the limit and the outcome above could not have been an acceptance.
            //       The field that would have produced it is ACCT-CURR-BAL at
            //       app/cpy/CVACT01Y.cpy:7.
            assertThat(balanceDisagreesWithCycle.getCurrBal().add(UNDER_LIMIT_AMOUNT))
                    .isGreaterThan(balanceDisagreesWithCycle.getCreditLimit());
            assertThat(outcome.projectedCycleBalance().scale()).isEqualTo(2);
        }

        /**
         * The cycle debit accumulator is SUBTRACTED, exactly as the baseline expression writes it.
         *
         * <p>Pins the operator at {@code app/cbl/CBTRN02C.cbl:404}, which is the one term of
         * {@code :403-405} whose sign a reader is most likely to invert.</p>
         */
        @Test
        @DisplayName("subtract the cycle debit accumulator rather than adding it")
        void theCycleDebitIsSubtracted() {
            // WHY : Assumptions: the subtraction is transcribed as written even though the accumulator
            //       holds signed values rather than magnitudes -- :551 adds a negative amount into it
            //       -- because the COMPUTE statement is the contract. Reversing the operator to suit
            //       the sign convention would change the quantity the inclusive guard at :407 compares,
            //       and on this vector it would move the projection from 1060.00 to 2060.00 and then
            //       to the far side of the limit for a slightly larger amount.
            Account withCycleDebit = account(FIXTURE_CURRENT_BALANCE, FIXTURE_CREDIT_LIMIT,
                    FIXTURE_EXPIRATION_DATE, new BigDecimal("1000.00"), new BigDecimal("500.00"));

            PostingValidationResult outcome = PostingValidationServiceTest.this.service.decide(
                    transaction(new BigDecimal("560.00"), stampOn(WITHIN_EXPIRY_DATE)),
                    Optional.of(crossReference()), Optional.of(withCycleDebit));

            assertThat(outcome.projectedCycleBalance())
                    .isEqualByComparingTo(new BigDecimal("1060.00"));
            assertThat(outcome.isAccepted()).isTrue();
        }
    }


    /**
     * Which code and which text each failure produces, in the fixed-width form the stream carries.
     *
     * <p>Assumptions: the four descriptions are DATA rather than presentation copy. They are bytes in
     * a file the parity comparison reads character for character, so each is asserted against the
     * literal the reference moves and against its exact length. Three spellings are worth naming so
     * that a later reader does not improve them: {@code OVERLIMIT} is one word; {@code ACCT} is
     * abbreviated in the expiration description while {@code ACCOUNT} is spelled out in the account
     * one; and the cross-reference description ends in {@code FOUND} while describing a row that was
     * not found.</p>
     *
     * <p>Assumptions: the renderings are taken from {@link RejectReason}'s and
     * {@link PostingValidationResult}'s own padding accessors, which is the division of labour the
     * package charter sets out -- the byte layout of the surrounding record is owned and asserted by
     * the {@code dto} and {@code mapper} tiers, and what this tier settles is which code and which
     * text a given failure produces. No record image is built here.</p>
     *
     * <p>Assumptions: the widths come from {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at
     * {@code app/cbl/CBTRN02C.cbl:181} and {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at
     * {@code :182}, whose four and 76 characters make the {@code VALIDATION-TRAILER PIC X(80)} of
     * {@code :178}. The trailer follows {@code REJECT-TRAN-DATA PIC X(350)} at {@code :177}, and
     * {@code 2500-WRITE-REJECT-REC} at {@code :446-448} moves the two halves in that order, giving the
     * 430 characters {@code app/jcl/POSTTRAN.jcl:36} allocates as {@code LRECL=430}.</p>
     */
    @Nested
    @DisplayName("the code and the text each failure produces")
    class WireRendering {

        /**
         * Each of the four descriptions matches its baseline literal character for character.
         *
         * <p>Pins the literals at {@code app/cbl/CBTRN02C.cbl:386}, {@code :398}, {@code :411} and
         * {@code :418}, whose lengths are 25, 24, 21 and 42 characters.</p>
         */
        @Test
        @DisplayName("carry the four description literals character for character")
        void theFourDescriptionsAreVerbatim() {
            assertThat(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.description())
                    .isEqualTo("INVALID CARD NUMBER FOUND").hasSize(25);
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.description())
                    .isEqualTo("ACCOUNT RECORD NOT FOUND").hasSize(24);
            assertThat(RejectReason.OVER_CREDIT_LIMIT.description())
                    .isEqualTo("OVERLIMIT TRANSACTION").hasSize(21);
            assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.description())
                    .isEqualTo("TRANSACTION RECEIVED AFTER ACCT EXPIRATION").hasSize(42);

            // WHY : Assumptions: the lengths are asserted alongside the text because the descriptions
            //       are padded into a fixed-width field, so a description one character longer or
            //       shorter than the reference's would shift the pad without changing any word. The
            //       longest of the four is 42 characters against a field of 76, so every one of them
            //       pads on the right and none is ever truncated.
            //       The field is WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at
            //       app/cbl/CBTRN02C.cbl:182 and the four literals are at :386, :398, :411 and
            //       :418.
            assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.description().length())
                    .isLessThanOrEqualTo(RejectReason.DESCRIPTION_WIDTH);
        }

        /**
         * Reasons 101 and 109 carry byte-identical descriptions, so a code is what identifies a reason.
         *
         * <p>Pins the literal at {@code app/cbl/CBTRN02C.cbl:398} against the one at {@code :557},
         * which are the same 24 characters under two different codes.</p>
         */
        @Test
        @DisplayName("share one description between reasons 101 and 109 under different codes")
        void twoReasonsShareOneDescription() {
            // WHY : Assumptions: this pairing is the reason every assertion in this class keys on the
            //       CODE and never on the text alone. The reference assigns 101 at :397 when the
            //       account READ finds nothing and 109 at :556 when the account REWRITE finds nothing,
            //       and it moves the same 24-character literal in both places, so a case matching on
            //       description would accept either where it meant exactly one.
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.description())
                    .isEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.description());
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.code())
                    .isNotEqualTo(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.code());
        }

        /**
         * Each reason code renders as four zero-padded characters, not as a minimal-width integer.
         *
         * <p>Pins characters 351 to 354 of the four committed expectation files under
         * {@code tests/golden/posting/}, which read {@code 0100}, {@code 0101}, {@code 0102} and
         * {@code 0103}.</p>
         */
        @Test
        @DisplayName("render each reason code as four zero-padded characters")
        void eachCodeRendersAsFourZeroPaddedCharacters() {
            // WHY : Assumptions: the pad character is a ZERO rather than a blank because the field is
            //       numeric-display, WS-VALIDATION-FAIL-REASON PIC 9(04) at app/cbl/CBTRN02C.cbl:181,
            //       and a three-digit code occupies such a field with a leading zero. The four
            //       committed reject expectations show exactly that, so the rendering is read from
            //       bytes rather than inferred from the picture clause alone.
            assertThat(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.codeField())
                    .isEqualTo("0100");
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.codeField()).isEqualTo("0101");
            assertThat(RejectReason.OVER_CREDIT_LIMIT.codeField()).isEqualTo("0102");
            assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.codeField())
                    .isEqualTo("0103");

            // WHY : Assumptions: the stored column and the wire field are two different
            //       representations of one value and are asserted separately so they cannot be
            //       conflated. The stored form is the numeric code, which is what a caller compares;
            //       the four-character zero-padded string is what the reject stream carries.
            //       The wire field is WS-VALIDATION-FAIL-REASON PIC 9(04) at
            //       app/cbl/CBTRN02C.cbl:181.
            assertThat(RejectReason.OVER_CREDIT_LIMIT.code()).isEqualTo(102);
            assertThat(RejectReason.OVER_CREDIT_LIMIT.codeField())
                    .hasSize(RejectReason.CODE_WIDTH);
        }

        /**
         * Each description pads to 76 characters and composes exactly 80 with its code.
         *
         * <p>Pins characters 355 to 430 of the committed expectation files against
         * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at {@code app/cbl/CBTRN02C.cbl:182} and the
         * {@code VALIDATION-TRAILER PIC X(80)} of {@code :178}.</p>
         */
        @Test
        @DisplayName("pad each description to 76 characters and compose 80 in all")
        void eachDescriptionPadsToSeventySixAndComposesEighty() {
            for (RejectReason reason : RejectReason.values()) {
                // WHY : Assumptions: every declared reason is driven rather than the four that reach
                //       the stream, because the two widths are properties of the trailer fields and not
                //       of the reachability of any one reason. Restricting the loop would leave the
                //       fifth reason's rendering unasserted while it remains callable.
                //       The two fields are declared at app/cbl/CBTRN02C.cbl:181 and :182.
                assertThat(reason.descriptionField()).hasSize(RejectReason.DESCRIPTION_WIDTH)
                        .startsWith(reason.description());
                assertThat(reason.descriptionField().substring(reason.description().length()))
                        .isBlank();
                assertThat(reason.trailerField()).hasSize(RejectReason.TRAILER_WIDTH)
                        .isEqualTo(reason.codeField() + reason.descriptionField());
            }

            // WHY : Assumptions: the total is asserted as the SUM of the two field widths rather than
            //       against a literal 80, so the identity that makes the record cohere is checked
            //       rather than restated. Writing the literal would let one of the two field widths
            //       change without the sum following it.
            //       The sum has to equal VALIDATION-TRAILER PIC X(80) at app/cbl/CBTRN02C.cbl:178.
            assertThat(RejectReason.TRAILER_WIDTH)
                    .isEqualTo(RejectReason.CODE_WIDTH + RejectReason.DESCRIPTION_WIDTH);
        }

        /**
         * A rejected outcome renders the trailer its own reason renders.
         *
         * <p>Pins {@code app/cbl/CBTRN02C.cbl:448}, which moves the trailer group as a whole, against
         * the trailer of {@code tests/golden/posting/reject_102_overlimit/dalyrejs.expected}.</p>
         */
        @Test
        @DisplayName("render the trailer of the reason a real outcome reports")
        void aRejectedOutcomeRendersItsReasonsTrailer() {
            PostingValidationResult outcome = PostingValidationServiceTest.this.service.decide(
                    transaction(ONE_CENT_OVER_AMOUNT, stampOn(WITHIN_EXPIRY_DATE)),
                    Optional.of(crossReference()),
                    Optional.of(fixtureAccount(ZERO_CYCLE_AMOUNT, ZERO_CYCLE_AMOUNT,
                            FIXTURE_EXPIRATION_DATE)));

            // WHY : Assumptions: the rendering is driven from an outcome the subject actually produced
            //       rather than from a reason named directly, which is what connects a validation
            //       decision to the bytes the parity comparison reads. A rendering asserted only on the
            //       enum would hold even if the subject reported a different reason entirely.
            //       The bytes it has to agree with are characters 351 to 430 of
            //       tests/golden/posting/reject_102_overlimit/dalyrejs.expected.
            assertThat(outcome.trailerField()).hasSize(RejectReason.TRAILER_WIDTH)
                    .startsWith("0102OVERLIMIT TRANSACTION")
                    .isEqualTo(RejectReason.OVER_CREDIT_LIMIT.trailerField());
        }

        /**
         * The trailer sits behind the 350-character transaction image, giving 430 characters in all.
         *
         * <p>Pins the record geometry through {@link CopybookLayout}, whose {@code REJECT} layout
         * extends the {@code DALYTRAN} layout, against {@code app/jcl/POSTTRAN.jcl:36}, which allocates
         * the stream as {@code LRECL=430}.</p>
         */
        @Test
        @DisplayName("place the 80-character trailer behind the 350-character image")
        void theTrailerFollowsTheTransactionImage() {
            // WHY : Alternatives Considered: writing 350, 80 and 430 as literals here, which needs no
            //       collaborator. Rejected because tests/README.md section 12 requires a record layout
            //       to be resolved through the copybook path and never duplicated, and because the
            //       three numbers are not independent -- asking the registry proves the arithmetic
            //       holds, where three literals would merely agree with each other.
            CopybookLayout.RecordSpec dailyTransaction = CopybookLayout.layout("DALYTRAN");
            CopybookLayout.RecordSpec rejectRecord = CopybookLayout.layout("REJECT");

            assertThat(dailyTransaction.reclen()).isEqualTo(350);
            assertThat(rejectRecord.reclen())
                    .isEqualTo(dailyTransaction.reclen() + RejectReason.TRAILER_WIDTH)
                    .isEqualTo(430);

            // WHY : Assumptions: the two trailer fields are asserted to begin where the image ends and
            //       to abut one another, because their ORDER is the contract -- :448 moves the group as
            //       a whole and that group declares the code at :181 before the description at :182, so
            //       transposing them would still yield 80 characters and the wrong record.
            assertThat(rejectRecord.field("WS-VALIDATION-FAIL-REASON").start()).isEqualTo(350);
            assertThat(rejectRecord.field("WS-VALIDATION-FAIL-REASON").length())
                    .isEqualTo(RejectReason.CODE_WIDTH);
            assertThat(rejectRecord.field("WS-VALIDATION-FAIL-REASON-DESC").start()).isEqualTo(354);
            assertThat(rejectRecord.field("WS-VALIDATION-FAIL-REASON-DESC").length())
                    .isEqualTo(RejectReason.DESCRIPTION_WIDTH);
        }
    }

    /**
     * Reason 109 belongs to the posting path and is never a validation outcome.
     *
     * <p>Assumptions: the reference assigns reason 109 at {@code app/cbl/CBTRN02C.cbl:556}, in the
     * {@code INVALID KEY} branch of the account {@code REWRITE} inside
     * {@code 2800-UPDATE-ACCOUNT-REC} at {@code :545}. That paragraph is performed only from
     * {@code :441}, inside {@code 2000-POST-TRANSACTION}, which {@code :212} enters only on the branch
     * taken when validation assigned NO reason at all. The paragraph that writes the reject stream is
     * performed from {@code :215}, the other arm of that same decision at {@code :211-216}, and
     * {@code :208} resets the reason before the next record. Nothing can therefore carry 109 to the
     * writer: it describes a transaction that PASSED validation and then could not be recorded, which
     * is not a validation outcome in any sense, and {@code tests/README.md} section 13 lists only 100,
     * 101, 102 and 103 as posting reject reasons.</p>
     *
     * <p>Assumptions: reason 109 shares its 24-character description with reason 101 verbatim, so this
     * group keys on the code alone. {@link WireRendering#twoReasonsShareOneDescription()} pins that
     * shared text separately.</p>
     */
    @Nested
    @DisplayName("the reason assigned after posting begins")
    class NonValidationReason {

        /**
         * No combination of findings makes the subject report reason 109.
         *
         * <p>Pins {@code app/cbl/CBTRN02C.cbl:556} as unreachable from validation, against the four
         * reasons {@code tests/README.md} section 13 lists.</p>
         */
        @Test
        @DisplayName("never report reason 109 for any input")
        void noInputEverReportsTheRewriteReason() {
            Account failingBothBoundaries = fixtureAccount(ZERO_CYCLE_AMOUNT, ZERO_CYCLE_AMOUNT,
                    FIXTURE_EXPIRATION_DATE);
            DailyTransaction failingEverything =
                    transaction(ONE_CENT_OVER_AMOUNT, stampOn(ONE_DAY_PAST_EXPIRY_DATE));

            // WHY : Assumptions: all four reachable shapes are driven rather than one, because the
            //       claim is about the subject's whole output range and a single shape could not
            //       establish it. The transaction used for the three rejecting shapes fails BOTH
            //       boundary conditions, so no shape here is passing by accident.
            //       The three rejecting shapes correspond to the branches at
            //       app/cbl/CBTRN02C.cbl:372, :396 and :400.
            assertThat(PostingValidationServiceTest.this.service
                    .decide(failingEverything, Optional.empty(), Optional.empty()).rejectReason())
                    .hasValue(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE);
            assertThat(PostingValidationServiceTest.this.service
                    .decide(failingEverything, Optional.of(crossReference()), Optional.empty())
                    .rejectReason()).hasValue(RejectReason.ACCOUNT_NOT_FOUND_ON_READ);
            assertThat(PostingValidationServiceTest.this.service
                    .decide(failingEverything, Optional.of(crossReference()),
                            Optional.of(failingBothBoundaries))
                    .rejectReason()).hasValue(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION);
            assertThat(PostingValidationServiceTest.this.service
                    .decide(transaction(UNDER_LIMIT_AMOUNT, stampOn(WITHIN_EXPIRY_DATE)),
                            Optional.of(crossReference()), Optional.of(failingBothBoundaries))
                    .rejectReason()).isEmpty();
        }

        /**
         * Exactly the four validation reasons can reach the reject stream, and 109 cannot.
         *
         * <p>Pins the reachability finding above against
         * {@link RejectReason#isPersistedToRejectStream()}, and against the absence of the characters
         * {@code 0109} from every committed expectation file under {@code tests/golden/posting/}.</p>
         */
        @Test
        @DisplayName("admit four reasons to the reject stream and exclude the fifth")
        void onlyTheFourValidationReasonsReachTheStream() {
            // WHY : Assumptions: the roster is asserted as a partition of all five declared reasons
            //       rather than as a list of four, so a sixth reason added later cannot slip into
            //       neither set unnoticed. The exclusion rests on reachability, not on preference: the
            //       paragraph assigning 109 and the paragraph writing the stream sit on opposite arms
            //       of the decision at :211-216.
            assertThat(RejectReason.values()).hasSize(5);
            assertThat(RejectReason.CARD_NUMBER_NOT_IN_CROSS_REFERENCE.isPersistedToRejectStream())
                    .isTrue();
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_READ.isPersistedToRejectStream()).isTrue();
            assertThat(RejectReason.OVER_CREDIT_LIMIT.isPersistedToRejectStream()).isTrue();
            assertThat(RejectReason.RECEIVED_AFTER_ACCOUNT_EXPIRATION.isPersistedToRejectStream())
                    .isTrue();
            assertThat(RejectReason.ACCOUNT_NOT_FOUND_ON_REWRITE.isPersistedToRejectStream())
                    .isFalse();
        }
    }

}
