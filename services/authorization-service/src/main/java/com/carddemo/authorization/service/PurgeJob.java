package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes pending authorizations that have aged past the expiry threshold, and the summaries left empty.
 *
 * <p><strong>Purpose.</strong> Carry across
 * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl}: {@code 1000-INITIALIZE} at L183 to L213
 * reads the run parameters, {@code MAIN-PARA} at L136 to L180 walks every summary and every
 * authorization beneath it, {@code 4000-CHECK-IF-EXPIRED} at L277 to L300 decides expiry and reverses the
 * parent's running totals, {@code 5000-DELETE-AUTH-DTL} at L303 to L325 deletes the authorization,
 * {@code 6000-DELETE-AUTH-SUMMARY} at L328 to L349 deletes a summary with nothing left beneath it, and
 * {@code 9000-TAKE-CHECKPOINT} at L352 to L374 commits. Every citation is relative to that tree, which is
 * reference material this migration reads and never modifies.
 *
 * <h2>Three documented divergences from the reference program</h2>
 *
 * <p>Refactoring Rationale: <strong>D-PURGE-YEAR-BOUNDARY.</strong> Elapsed days are computed by
 * converting both ordinal dates into real calendar dates and differencing THOSE, where the reference
 * subtracts the five-digit ordinals directly at L282. The plain subtraction is wrong across a year
 * boundary and wrong by a wide margin: Julian 23365 is 31 December 2023 and Julian 24001 is 1 January
 * 2024, one day apart, yet subtracting them yields 636 -- and L284's inclusive test against the
 * five-day default from L199 turns that into a deletion of a one-day-old authorization. The committed
 * fixture pair {@code pautdtl1-newyear-pair.bin} and {@code pautsum0-purge-parent.bin} exists to hold this
 * arithmetic to the correct answer, and {@code PendingAuthDetailNewYearFixtureTest} names both the right
 * value and the wrong one so an implementation cannot be right by accident. Reproducing the defect was
 * considered and rejected: it destroys live authorizations, so it is not a behaviour any parity argument
 * can justify preserving.
 *
 * <p>Refactoring Rationale: <strong>D-PURGE-DELETE-GUARD.</strong> A summary is deleted when BOTH of its
 * counters have fallen to zero or below, where the reference tests one counter twice --
 * {@code IF PA-APPROVED-AUTH-CNT <= 0 AND PA-APPROVED-AUTH-CNT <= 0} at L156 names the approved count on
 * both sides of the conjunction and never mentions the declined one. The consequence is not cosmetic: a
 * summary whose approved count is zero but which still has unexpired DECLINED authorizations beneath it
 * satisfies the reference guard and is deleted, taking those live children with it through the
 * hierarchical delete. The declined counter is tested here instead. Reproducing the typo was rejected on
 * the same ground as the arithmetic above -- it deletes data that has not expired.
 *
 * <p>Refactoring Rationale: <strong>D-PURGE-COUNTER-PERSISTENCE.</strong> The reversed counters and
 * totals are PERSISTED on a summary that survives the purge, where the reference program's are not. The
 * reference reverses them at L288 to L292 into the working-storage copy its L223 to L226 retrieved, and
 * it never replaces the root -- {@code CBPAUP0C.cbl} contains no replace call of any kind -- so a summary
 * that keeps some children is left with counters and totals that still include the authorizations just
 * deleted. Alternatives Considered: reproducing that by computing the reversal locally and leaving the row
 * untouched. Rejected on two grounds. First, the reference program plainly INTENDS the reversal, because
 * its own delete guard at L156 reads the reversed values -- the arithmetic is load-bearing, and only the
 * hierarchical storage model prevents it being kept. Second, in the relational target the retrieved copy
 * and the row ARE the same object, so not persisting would mean writing extra code to detach or shadow
 * the entity in order to reproduce an artifact of the reference's storage model rather than any of its
 * business rules -- and the result would be a summary whose displayed counts permanently disagree with
 * the authorizations under it, on a screen that shows both. The divergence is narrow: it is observable
 * only in the two counters and two totals of a summary that survives a purge, and it moves them towards
 * agreement with the rows that remain.
 *
 * <p>Assumptions: the credit balance is NOT released when an authorization expires, which is the
 * reference program's own asymmetry and is recorded on
 * {@link PendingAuthSummary#reverseApproved(java.math.BigDecimal)} rather than repeated here.
 *
 * <p>Assumptions: the two arms of the reversal take their amount from DIFFERENT columns of the same
 * expiring authorization -- the approved arm the approved amount at L289, the declined arm the
 * transaction amount at L292 -- and the response code selects the arm. Passing one amount to both arms
 * would be silently wrong for every declined authorization whose two amounts differ, which is all of
 * them, because a decline approves nothing.
 */
@Service
public class PurgeJob {

    /**
     * The expiry threshold in days the reference program falls back to when its parameter is not numeric.
     *
     * <p>Assumptions: five, from {@code MOVE 5 TO WS-EXPIRY-DAYS} at
     * {@code cbl/CBPAUP0C.cbl} L199, reached whenever the two-digit parameter field at its L99 does not
     * hold digits.
     */
    public static final int DEFAULT_EXPIRY_DAYS = 5;

    /**
     * The number of summaries the reference program processes between checkpoints.
     *
     * <p>Assumptions: five, from {@code MOVE 5 TO P-CHKP-FREQ} at {@code cbl/CBPAUP0C.cbl} L202, applied
     * when the parameter arrives blank, zero or low values. Its L160 takes a checkpoint once the count of
     * summaries processed EXCEEDS this value, so the window is five summaries and the checkpoint falls on
     * the sixth.
     */
    public static final int DEFAULT_CHECKPOINT_FREQUENCY = 5;

    /**
     * The response code that marks an authorization approved.
     *
     * <p>Assumptions: {@code '00'}, and the test is EQUALITY with it rather than membership of a set. The
     * reference program branches on {@code IF PA-AUTH-RESP-CODE = '00'} at L287, so every other value --
     * including one no decline reason in the tree produces -- takes the declined arm. Testing for a known
     * decline code instead would leave an unrecognised code reversing neither counter.
     */
    private static final String RESPONSE_CODE_APPROVED = "00";

    /**
     * The century the two-digit year of an ordinal date resolves into.
     *
     * <p>Assumptions: two thousand, matching the window the fixture contract test asserts and the one the
     * listener writes into: it renders the key's year as the calendar year modulo one hundred, so every
     * date this service reads was written by a clock in this century. The reference program never widens
     * the year at all -- it subtracts the ordinals as integers -- so the window is supplied by the
     * migration, and it is stated here rather than shared with the fraud path's pivot of seventy on
     * purpose: that pivot widens a PAST acquirer-supplied date over a hundred-year window, whereas this
     * value widens a date this system itself wrote, and one constant serving both would have to be wrong
     * for one of them.
     */
    private static final int ORDINAL_DATE_CENTURY = 2000;

    /**
     * The divisor separating the two-digit year from the three-digit day of year in an ordinal date.
     */
    private static final int ORDINAL_YEAR_DIVISOR = 1000;

    /**
     * The declared decimal scale of every money column this job reads.
     *
     * <p>Assumptions: two, which is the scale {@code PIC S9(09)V99 COMP-3} declares for both amount
     * fields of the detail segment. It is named rather than written into the substitution below so the
     * substituted zero and the stored amounts cannot end up at different scales, which would make the
     * reversal's result differ by scale depending on whether the column was set.
     */
    private static final int MONEY_SCALE = 2;

    /**
     * The lowest account identifier the outer walk can start below.
     *
     * <p>Assumptions: zero, and the walk seeks strictly above it. The schema declares the account
     * identifier a positive value, so nothing stored can be at or below zero and no summary is skipped by
     * starting here.
     */
    private static final long BEFORE_FIRST_ACCOUNT = 0L;

    /**
     * The log the run's progress and its per-summary decisions are reported on.
     */
    private static final Logger LOG = LoggerFactory.getLogger(PurgeJob.class);

    /**
     * The summary rows, walked in ascending key order and deleted when emptied.
     */
    private final PendingAuthSummaryRepository summaries;

    /**
     * The authorization rows, read beneath each summary and deleted when expired.
     */
    private final PendingAuthDetailRepository details;

    /**
     * The transaction boundary one checkpoint window runs inside.
     */
    private final TransactionTemplate transactions;

    /**
     * The clock the default business date is read from.
     */
    private final Clock clock;

    /**
     * Builds the job over its two repositories, the transaction boundary and the clock.
     *
     * @param summaries the summary repository; must not be {@code null}
     * @param details the authorization repository; must not be {@code null}
     * @param transactions the transaction boundary each checkpoint window runs inside; must not be
     *     {@code null}
     * @param clock the clock the default business date is read from; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public PurgeJob(PendingAuthSummaryRepository summaries, PendingAuthDetailRepository details,
            TransactionTemplate transactions, Clock clock) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.details = Objects.requireNonNull(details, "details must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Runs the purge for the server's current date with the reference defaults.
     *
     * <p>Assumptions: the business date defaults to the clock because the reference program reads it from
     * the platform, {@code ACCEPT CURRENT-YYDDD FROM DAY} at {@code cbl/CBPAUP0C.cbl} L187, and takes no
     * date parameter at all. The parameterised form below exists so a run can be made reproducible, which
     * is what an orchestrated batch step and a test both need; this form exists so the default path is
     * the reference's own.
     *
     * @return what the run read and deleted; never {@code null}
     */
    public PurgeOutcome purge() {
        return purge(LocalDate.now(this.clock), DEFAULT_EXPIRY_DAYS, DEFAULT_CHECKPOINT_FREQUENCY);
    }

    /**
     * Runs the purge for a stated business date, expiry threshold and checkpoint window.
     *
     * <p>Assumptions: the expiry test is {@code >=} and therefore INCLUSIVE, transcribing
     * {@code IF WS-DAY-DIFF >= WS-EXPIRY-DAYS} at {@code cbl/CBPAUP0C.cbl} L284. An authorization exactly
     * at the threshold expires; one day short of it survives. The boundary is called out because the
     * inclusive form is the one an implementation gets wrong, and it is the same inclusive convention the
     * posting program applies to its own limit tests.
     *
     * <p>Trade-offs: the walk commits once per checkpoint window rather than once for the whole run, which
     * is what the reference checkpoint at L161 does and is why the parameter is honoured rather than
     * ignored. A failure part-way therefore leaves the summaries already processed deleted and the rest
     * untouched, and a re-run resumes correctly because the work is idempotent -- an authorization already
     * deleted is simply not found, and a summary already deleted is not walked. The alternative, one
     * transaction for the whole run, would make a large purge hold locks and undo for its full duration
     * and would discard every completed window on any failure.
     *
     * @param businessDate the date elapsed days are measured to; must not be {@code null}
     * @param expiryDays the inclusive threshold in days at which an authorization expires; must be
     *     positive
     * @param checkpointFrequency the number of summaries processed per committed window; must be positive
     * @return what the run read and deleted; never {@code null}
     * @throws NullPointerException if {@code businessDate} is {@code null}
     * @throws IllegalArgumentException if {@code expiryDays} or {@code checkpointFrequency} is not
     *     positive
     */
    public PurgeOutcome purge(LocalDate businessDate, int expiryDays, int checkpointFrequency) {
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        requirePositive(expiryDays, "expiryDays");
        requirePositive(checkpointFrequency, "checkpointFrequency");

        PurgeOutcome total = PurgeOutcome.nothing();
        long position = BEFORE_FIRST_ACCOUNT;

        while (true) {
            long windowStart = position;

            // WHY : Assumptions: one checkpoint window is one transaction, which is the migrated form of
            //       the reference checkpoint call -- that call commits the unit of work and releases
            //       database position, and a transaction boundary is what does both here. The template is
            //       used rather than an annotation on a second method of this class because a call from
            //       one method of a bean to another bypasses the proxy that would start the transaction,
            //       so the annotation would be silently inert and the whole run would share one boundary.
            PurgeWindow window = this.transactions
                    .execute(status -> purgeWindow(windowStart, businessDate, expiryDays,
                            checkpointFrequency));

            total = total.combinedWith(window.outcome());
            if (window.exhausted()) {
                LOG.info("purge complete businessDate={} expiryDays={} summariesRead={}"
                                + " summariesDeleted={} detailsRead={} detailsDeleted={}",
                        businessDate, expiryDays, total.summariesRead(), total.summariesDeleted(),
                        total.detailsRead(), total.detailsDeleted());
                return total;
            }
            position = window.lastAccountId();
        }
    }

    /**
     * Purges one checkpoint window of summaries, starting strictly above a stated account identifier.
     *
     * @param startAfterAccountId the account identifier the window seeks strictly above
     * @param businessDate the date elapsed days are measured to; never {@code null}
     * @param expiryDays the inclusive expiry threshold in days
     * @param windowSize the number of summaries this window covers
     * @return what this window read and deleted, and where it stopped; never {@code null}
     */
    private PurgeWindow purgeWindow(long startAfterAccountId, LocalDate businessDate, int expiryDays,
            int windowSize) {

        List<PendingAuthSummary> page = this.summaries.findByAccountIdGreaterThanOrderByAccountIdAsc(
                Long.valueOf(startAfterAccountId), Limit.of(windowSize));
        if (page.isEmpty()) {
            return new PurgeWindow(PurgeOutcome.nothing(), startAfterAccountId, true);
        }

        PurgeOutcome outcome = PurgeOutcome.nothing();
        long lastAccountId = startAfterAccountId;
        for (PendingAuthSummary summary : page) {
            lastAccountId = summary.getAccountId().longValue();
            outcome = outcome.combinedWith(purgeSummary(summary, businessDate, expiryDays));
        }
        return new PurgeWindow(outcome, lastAccountId, page.size() < windowSize);
    }

    /**
     * Purges the expired authorizations beneath one summary, and the summary itself when it is emptied.
     *
     * @param summary the summary to process; never {@code null}
     * @param businessDate the date elapsed days are measured to; never {@code null}
     * @param expiryDays the inclusive expiry threshold in days
     * @return what this summary read and deleted; never {@code null}
     */
    private PurgeOutcome purgeSummary(PendingAuthSummary summary, LocalDate businessDate,
            int expiryDays) {

        Long accountId = summary.getAccountId();
        List<PendingAuthDetail> children =
                this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(accountId);
        int detailsDeleted = 0;

        for (PendingAuthDetail child : children) {
            if (!hasExpired(child, businessDate, expiryDays)) {
                continue;
            }
            reverse(summary, child);
            this.details.delete(child);
            detailsDeleted++;
        }

        // WHY : Assumptions: BOTH counters are tested, which is divergence D-PURGE-DELETE-GUARD recorded
        //       on this class -- the reference guard names the approved counter on both sides of its
        //       conjunction and would delete a summary that still has unexpired declined authorizations
        //       beneath it. The comparison is <= rather than == because the counters are signed and the
        //       schema admits their negative half, so an extract that arrived with counters below its
        //       actual children can drive them negative and must still qualify.
        boolean summaryDeleted = summary.getApprovedAuthCount() <= 0
                && summary.getDeclinedAuthCount() <= 0;
        if (summaryDeleted) {
            // WHY : Assumptions: any authorization still beneath this summary goes with it, which is what
            //       the reference hierarchical delete does -- deleting a root removes its dependents --
            //       and what fk_pending_auth_detail_summary reproduces with ON DELETE CASCADE. That case
            //       is reachable only from an extract whose counters understated its children, and
            //       leaving those rows behind would strand them with no parent for the foreign key to
            //       satisfy.
            this.summaries.delete(summary);
            LOG.info("summary deleted, nothing pending beneath it accountId={}", accountId);
        }
        return new PurgeOutcome(1, summaryDeleted ? 1 : 0, children.size(), detailsDeleted);
    }

    /**
     * Reverses one expiring authorization out of its parent's running counters and totals.
     *
     * @param summary the parent whose counters are reversed; never {@code null}
     * @param child the expiring authorization; never {@code null}
     */
    private static void reverse(PendingAuthSummary summary, PendingAuthDetail child) {
        if (RESPONSE_CODE_APPROVED.equals(child.getAuthRespCode())) {
            summary.reverseApproved(amountOrZero(child.getApprovedAmount()));
        } else {
            summary.reverseDeclined(amountOrZero(child.getTransactionAmount()));
        }
    }

    /**
     * Decides whether an authorization has aged past the expiry threshold.
     *
     * <p>Assumptions: the key's date component is the DECODED ordinal date and never the nines complement
     * it is stored as in the segment, so no complement arithmetic is repeated here. The reference program
     * decodes it at L280 because its copy of the segment still holds the complement; this key type holds
     * the decoded value, and its own constructor refuses anything outside the ordinal-date domain.
     *
     * @param child the authorization to test; never {@code null}
     * @param businessDate the date elapsed days are measured to; never {@code null}
     * @param expiryDays the inclusive expiry threshold in days
     * @return {@code true} when at least {@code expiryDays} days have elapsed since the authorization
     */
    private static boolean hasExpired(PendingAuthDetail child, LocalDate businessDate,
            int expiryDays) {
        LocalDate authorizedOn = calendarDateOf(child.getId().getAuthDate().intValue());
        return ChronoUnit.DAYS.between(authorizedOn, businessDate) >= expiryDays;
    }

    /**
     * Converts a five-digit ordinal date into the calendar date it denotes.
     *
     * <p>Assumptions: this conversion is the whole of divergence D-PURGE-YEAR-BOUNDARY. Differencing two
     * calendar dates is what makes 31 December and 1 January one day apart instead of the 636 a plain
     * subtraction of their ordinals yields.
     *
     * @param ordinalDate a two-digit year followed by a three-digit day of year, as one integer
     * @return the calendar date that ordinal denotes in this century
     */
    private static LocalDate calendarDateOf(int ordinalDate) {
        return LocalDate.ofYearDay(ORDINAL_DATE_CENTURY + ordinalDate / ORDINAL_YEAR_DIVISOR,
                ordinalDate % ORDINAL_YEAR_DIVISOR);
    }

    /**
     * Supplies an amount for the reversal, substituting an exact zero for an absent one.
     *
     * <p>Assumptions: an absent amount reverses nothing rather than refusing the run. Both amount columns
     * are nullable because an extract may leave them unset, and a purge that abends on one such row would
     * leave every later summary unprocessed for a value that contributes zero either way.
     *
     * @param amount the stored amount, or {@code null} when the column is unset
     * @return the amount unchanged, or an exact zero
     */
    private static BigDecimal amountOrZero(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO.setScale(MONEY_SCALE) : amount;
    }

    /**
     * Refuses a run parameter that is not positive.
     *
     * @param value the parameter value
     * @param name the parameter name, used to identify it in the refusal
     * @throws IllegalArgumentException if {@code value} is not positive
     */
    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }

    /**
     * One checkpoint window's result: what it purged, where it stopped, and whether the walk is done.
     *
     * <p>Assumptions: exhaustion is decided by the page being SHORTER than the window rather than by a
     * separate count query. A short page means the keyed seek found no further summaries above the
     * position, which is the same signal the reference walk takes from its end-of-database status, and it
     * costs nothing extra.
     *
     * @param outcome what this window read and deleted
     * @param lastAccountId the highest account identifier this window processed, which the next window
     *     seeks strictly above
     * @param exhausted {@code true} when no summaries remain above {@code lastAccountId}
     */
    private record PurgeWindow(PurgeOutcome outcome, long lastAccountId, boolean exhausted) {
    }

    /**
     * What a purge run did, in the four counts the reference program reports at its L173 to L176.
     *
     * @param summariesRead the number of summaries walked
     * @param summariesDeleted the number of summaries deleted for having nothing pending beneath them
     * @param detailsRead the number of authorizations walked
     * @param detailsDeleted the number of authorizations deleted for having expired
     */
    public record PurgeOutcome(int summariesRead, int summariesDeleted, int detailsRead,
            int detailsDeleted) {

        /**
         * Returns the identity for accumulation: a run that has processed nothing.
         *
         * @return a carrier holding four zeros, never {@code null}
         */
        static PurgeOutcome nothing() {
            return new PurgeOutcome(0, 0, 0, 0);
        }

        /**
         * Adds another result's counts to these.
         *
         * @param other the counts to add; must not be {@code null}
         * @return a new carrier holding the component-wise sum, never {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        PurgeOutcome combinedWith(PurgeOutcome other) {
            Objects.requireNonNull(other, "other must not be null");
            return new PurgeOutcome(this.summariesRead + other.summariesRead,
                    this.summariesDeleted + other.summariesDeleted,
                    this.detailsRead + other.detailsRead,
                    this.detailsDeleted + other.detailsDeleted);
        }
    }
}
