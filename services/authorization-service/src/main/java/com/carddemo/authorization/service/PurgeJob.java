package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
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
 * Deletes pending authorizations that have aged past an expiry threshold, and the summaries left empty.
 *
 * <h2>Purpose and paragraph map</h2>
 *
 * <p>This carries across {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl}, a 386-line batch
 * program. Every citation below is to that immutable reference tree, which this migration reads and
 * never modifies, and every line range names the paragraph's own statements rather than the
 * {@code *-EXIT} paragraph that follows it. Each significant paragraph becomes one named method whose
 * Javadoc repeats its range, so a reviewer can move between the two in either direction.
 *
 * <ul>
 *   <li>{@code MAIN-PARA} L136 to L180 - {@link #purge(PurgeParameters)}</li>
 *   <li>{@code 1000-INITIALIZE} L183 to L210 - {@link PurgeParameters}</li>
 *   <li>{@code 2000-FIND-NEXT-AUTH-SUMMARY} L216 to L242 - {@code purgeWindow}</li>
 *   <li>{@code 3000-FIND-NEXT-AUTH-DTL} L248 to L272 - {@code purgeSummary}</li>
 *   <li>{@code 4000-CHECK-IF-EXPIRED} L277 to L298 - {@code hasExpired} and {@code reverse}</li>
 *   <li>{@code 5000-DELETE-AUTH-DTL} L303 to L323 - the child delete inside {@code purgeSummary}</li>
 *   <li>{@code 6000-DELETE-AUTH-SUMMARY} L328 to L347 - the parent delete inside {@code purgeSummary}</li>
 *   <li>{@code 9000-TAKE-CHECKPOINT} L352 to L372 - the committed window and {@code logProgress}</li>
 *   <li>{@code 9999-ABEND} L377 to L383 - {@link PurgeAbendException}</li>
 * </ul>
 *
 * <h2>What selects a row, and what does not</h2>
 *
 * <p>Assumptions: selection is AGE ALONE, and the comparison is INCLUSIVE. {@code 4000-CHECK-IF-EXPIRED}
 * decodes the stored date at L280, differences it at L282 and qualifies the row at L284 with
 * {@code IF WS-DAY-DIFF >= WS-EXPIRY-DAYS}; its only two outcomes are set at L285 and L295. An
 * authorization exactly at the threshold IS removed and one day short of it survives. The inclusive form
 * is stated because it is the half of the comparison an implementation drops, and it is the same
 * inclusive convention the posting program applies to its own limit tests.
 *
 * <p>Assumptions: NO match status narrows that selection, so MATCHED authorizations are removed alongside
 * pending and declined ones. This rests on a measurement rather than a reading: the identifier
 * {@code MATCH-STATUS} occurs zero times in all 386 lines, no spelling of the word occurs at all, and the
 * program's complete data-language verb inventory is four verbs - one {@code CHKP} at L355, two
 * {@code DLET} at L310 and L335, one {@code GN} at L223 and one {@code GNP} at L255 - with no
 * {@code REPL} and no {@code ISRT} anywhere. A program that cannot replace a segment cannot record a
 * match state, and one that never names the field cannot read it either. Sibling documentation reads the
 * other way and is the reason this is spelled out here: {@code domain/PendingAuthDetail.java} attributes
 * the {@code 'E'} PENDING-EXPIRED state of {@code cpy/CIPAUDTY.cpy} L48 to this program at its L142, L166
 * and L756. Those 386 lines do not carry that write - they delete the row instead - so no match-status
 * predicate and no match-status write is added here. Adding either from the sibling wording would change
 * which rows a run removes while appearing to follow documentation, which is why
 * {@code PurgeJobTest} asserts that a matched authorization IS deleted.
 *
 * <h2>Divergences from the reference program</h2>
 *
 * <p>Four differences are deliberate and are registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. Each carries two identifiers because the
 * module already names them two ways - the letter scheme in the test package charters and the descriptive
 * scheme in this class - and citing both is what lets either one be reconciled by search.
 *
 * <p>Refactoring Rationale: <strong>D-E, also D-PURGE-YEAR-BOUNDARY.</strong> Elapsed days are computed
 * by converting both ordinal dates into calendar dates and differencing THOSE, where the reference
 * subtracts the five-digit ordinals directly at L282 into a {@code PIC S9(4) COMP} field declared at L47.
 * The plain subtraction is wrong across a year boundary and wrong by a wide margin: ordinal 23365 is
 * 31 December 2023 and ordinal 24001 is 1 January 2024, one day apart, yet subtracting them yields 636,
 * and L284's inclusive test against the five-day default from L199 turns that into the removal of a
 * one-day-old authorization. The four-digit receiving field is the second limb: the widest ordinal span,
 * 99999 less 00001, is 99998 and cannot be represented in it at all. The committed fixtures
 * {@code pautdtl1-newyear-pair.bin} and {@code pautsum0-purge-parent.bin} hold this arithmetic to the
 * calendar answer, and {@code PendingAuthDetailNewYearFixtureTest} names both 1 and 636 so an
 * implementation cannot be right by accident. Alternatives Considered: reproducing the ordinal
 * subtraction for parity. Rejected because it removes authorizations that have not aged, and a run that
 * destroys live rows is not a behaviour parity can be argued for.
 *
 * <p>Refactoring Rationale: <strong>D-F, also D-PURGE-DELETE-GUARD.</strong> A summary is removed when
 * BOTH of its counters have fallen to zero or below, where the reference tests one counter twice -
 * {@code IF PA-APPROVED-AUTH-CNT <= 0 AND PA-APPROVED-AUTH-CNT <= 0} at L156 names the approved count on
 * both sides of the conjunction and never mentions the declined one, so the second condition adds
 * nothing. The consequence is not cosmetic: a summary whose approved count is zero but which still has
 * unexpired DECLINED authorizations beneath it satisfies that guard and is removed, taking those rows
 * with it through the hierarchical delete. The declined counter is tested here as well. Alternatives
 * Considered: reproducing the duplicated condition. Rejected on the same ground as the arithmetic above -
 * it removes rows that have not aged.
 *
 * <p>Refactoring Rationale: <strong>D-G, also D-PURGE-COUNTER-PERSISTENCE.</strong> The reversed counters
 * and totals are PERSISTED on a summary that survives the run, where the reference program's are not. Its
 * L288 to L292 reverse them into the working-storage copy that L223 to L226 retrieved, and it has no verb
 * able to write that copy back - the four-verb inventory above contains no {@code REPL} - so a summary
 * keeping some children is left holding counters that still include the rows just removed. Alternatives
 * Considered: reproducing that by computing the reversal locally and leaving the row untouched. Rejected
 * on two grounds. The reference program plainly INTENDS the reversal, because its own delete guard at
 * L156 reads the reversed values, so the arithmetic is load-bearing and only the hierarchical storage
 * model prevents it being kept; and in the relational target the retrieved copy and the row ARE the same
 * object, so not persisting would mean adding code to detach or shadow the entity in order to reproduce
 * an artifact of the reference's storage model rather than any of its business rules. The difference is
 * narrow - it is observable only in the two counters and two totals of a surviving summary - and it moves
 * them towards agreement with the rows that remain, on a screen that displays both.
 *
 * <p>Refactoring Rationale: <strong>D-PURGE-EXPIRY-FLOOR.</strong> An expiry threshold of zero is
 * REFUSED before anything is read, where the reference admits it. Assumptions: the mechanism is an
 * asymmetry between three guards in {@code 1000-INITIALIZE}, not the content of any control card. L196
 * guards the expiry parameter with {@code IF P-EXPIRY-DAYS IS NUMERIC} alone, while L201 and L204 guard
 * the two checkpoint parameters with {@code = SPACES OR 0 OR LOW-VALUES}, which additionally tests for
 * zero. The card shipped at {@code jcl/CBPAUP0J.jcl} L37 is {@code 00,00001,00001,Y}; its first field is
 * numeric, so L196 is satisfied, L197 moves it, the L199 fallback of five is never reached, and L284
 * reads as a comparison against zero, which holds for every authorization dated on or before the run
 * date. Had L196 carried the same zero test as its two neighbours the shipped card would have taken the
 * default. Alternatives Considered: admitting zero and relying on the caller. Rejected because the
 * threshold is the only value standing between a run and the whole table, and a parameter that
 * disqualifies nothing is indistinguishable at the call site from one that was never supplied.
 *
 * <h2>What is preserved exactly</h2>
 *
 * <p>Assumptions: the credit and cash balances are NOT released when an authorization expires. That is
 * the reference program's own asymmetry - {@code cbl/COPAUA0C.cbl} L817 adds an approved amount to
 * {@code PA-CREDIT-BALANCE} and L818 clears {@code PA-CASH-BALANCE}, while a search for
 * {@code BALANCE} across all 386 lines of the purge program returns nothing - so an account whose
 * authorizations all expire retains the reserved balance until some other process releases it. It is
 * preserved and recorded rather than adjusted, because there is no reference behaviour to derive an
 * adjustment from; the rationale for the pairing sits on
 * {@link PendingAuthSummary#reverseApproved(java.math.BigDecimal)} and is not repeated at every reader.
 * The identifier for this preserved asymmetry is D-PURGE-BALANCE.
 *
 * <p>Assumptions: this class follows the ABEND model and {@link AuthorizationRequestListener} follows the
 * OUTBOX model, and the two are deliberately different disciplines inside one module. A failed checkpoint
 * ends the reference run at L369, so a failure here is terminal for the run and surfaces as
 * {@link PurgeAbendException} carrying {@link #ABEND_EXIT_STATUS}. The consumer instead commits its reply
 * row with its decision and publishes afterwards, so a failure there is retried rather than terminal.
 * Unifying them would either make a batch failure recoverable that the reference treats as final, or make
 * a lost reply terminal that the outbox exists to recover.
 *
 * <p>Assumptions: the reference runs CONCURRENTLY with the online region and holds nothing, so this job
 * may run while the service serves traffic and takes no lock: {@code jcl/CBPAUP0J.jcl} L25 passes
 * {@code PARM='BMP,CBPAUP0C,PSBPAUTB'}, {@code ims/PSBPAUTB.psb} L17 declares {@code PROCOPT=AP} with no
 * exclusive option, and no get-hold function code appears anywhere in the program.
 *
 * <p>Assumptions: there is no condition-code inversion to perform for this program and no branch
 * predicate to derive from its job. All five job definitions in
 * {@code app/app-authorization-ims-db2-mq/jcl/} were read in full with columns 73 to 80 stripped, and
 * each contains zero {@code COND=}, zero job-control {@code IF}, zero {@code RESTART=} and zero
 * {@code CHKPT=} data-definition parameters. Neither of the two forms that share the {@code COND}
 * keyword - the step gate and the record filter - occurs here, so every step edge is the unconditional
 * success edge. This is recorded because its absence is otherwise indistinguishable from an omission.
 *
 * <h2>Deliberate absences</h2>
 *
 * <p>Alternatives Considered: expressing this as a Spring Batch job. Rejected, and the module is built so
 * that it cannot become one: the parent manages {@code spring-boot-starter-batch} and only
 * {@code batch-service} declares it, so this module has no job repository and no second transaction
 * owner. This is a scheduled service method and a Step Functions-invoked entry point, which is what the
 * package charter records, and a job repository here would add a restart store alongside the one the
 * orchestrator already provides.
 *
 * <p>Alternatives Considered: no retry is transcribed and no resilience library is used. The reference
 * declares {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} at L92, and identically at
 * {@code cbl/COPAUS0C.cbl} L87, and evaluates it NOWHERE - each declaration is the only occurrence in its
 * program, and every failure arm goes straight to {@code 9999-ABEND} at L240, L270, L320, L345 and L369.
 * Annotating this service would therefore issue a failing database operation more times than the
 * reference does, during a run that is concurrently deleting rows from the table it is reading. Durable
 * retry for this path is per-state orchestrator retry, which is safe here because the work is idempotent:
 * a row already removed is simply not found. Assumptions: if a narrow retry is ever added it must be
 * limited to those three transient infrastructure statuses and must not cover the data conditions
 * declared beside them at L85 {@code 'GE'}, L86 {@code 'II'}, L87 {@code 'GP'} and L88 {@code 'GB'},
 * which the program handles by branching or by ending the run. Two facts about the framework API are
 * recorded here because both are easy to get wrong and neither is exercised anywhere in this module: the
 * attribute bounding an attempt sequence is {@code maxRetries} and not {@code maxAttempts}, with total
 * attempts being one plus that value and defaulting to three, and the enabling annotation is
 * {@code @EnableResilientMethods} and not {@code @EnableRetry}. The decision to add no library at all,
 * and to omit a circuit breaker, is recorded in {@code docs/adr/ADR-002-compute-platform.md}.
 *
 * <p>Assumptions: the reference had no usable restart origin, so the orchestrator's ledger and redrive
 * complete a mechanism rather than reproduce or invent one. The program does take checkpoints - one
 * {@code EXEC DLI CHKP} at L355 - but the job that runs it dummies the log a restart would read them
 * from, at {@code jcl/CBPAUP0J.jcl} L44 {@code //IMSLOGR DD DUMMY} alongside L43
 * {@code //IEFRDER DD DUMMY}. The commit points exist and the restart origin does not.
 * {@code docs/architecture/batch-orchestration.md} scopes its own "no baseline checkpoint contract"
 * statement to the job-control tier and already records this call; the module-local addition here is that
 * the contract is left incomplete by the shipped job, which is why redrive plus a durable step ledger is
 * described as completing it.
 *
 * <p>Assumptions: parity for every path in this module rests on the copybook and schema contracts plus
 * transcribed logic, and NOT on a golden master, because none exists for any of them. The reference test
 * suite covers the base batch programs and does not reach this extension, and its documented warn-level
 * aggregate return code belongs to an out-of-scope export and import pair rather than to anything here.
 *
 * @see PendingAuthSummary#reverseApproved(java.math.BigDecimal)
 * @see PendingAuthSummary#reverseDeclined(java.math.BigDecimal)
 */
@Service
public class PurgeJob {

    /**
     * The expiry threshold in days a run falls back to when none is stated.
     *
     * <p>Assumptions: five, from {@code MOVE 5 TO WS-EXPIRY-DAYS} at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L199, which the reference reaches when the
     * two-digit field declared at its L99 does not hold digits.
     */
    public static final int DEFAULT_EXPIRY_DAYS = 5;

    /**
     * The number of summaries a run processes between commits when none is stated.
     *
     * <p>Assumptions: five, from {@code MOVE 5 TO P-CHKP-FREQ} at {@code cbl/CBPAUP0C.cbl} L202, applied
     * when the five-character field at its L101 arrives blank, zero or low values. Its L160 takes a
     * checkpoint once the count of summaries processed EXCEEDS this value, so the window is five summaries
     * and the checkpoint falls on the sixth.
     */
    public static final int DEFAULT_CHECKPOINT_FREQUENCY = 5;

    /**
     * The number of committed windows between progress log lines when none is stated.
     *
     * <p>Assumptions: ten, from {@code MOVE 10 TO P-CHKP-DIS-FREQ} at {@code cbl/CBPAUP0C.cbl} L205,
     * applied when the five-character field at its L103 arrives blank, zero or low values. This is a
     * SEPARATE control from {@link #DEFAULT_CHECKPOINT_FREQUENCY} and the two are not interchangeable: the
     * reference counts summaries in {@code WS-AUTH-SMRY-PROC-CNT} declared at L52, increments it at L232
     * once per summary read and compares it with {@code >} at L160 to decide whether to COMMIT, while it
     * counts checkpoints in {@code WS-NO-CHKP} declared at L51, increments it at L359 once per successful
     * checkpoint and compares it with {@code >=} at L360 to decide whether to DISPLAY. Different counters,
     * different parameters, different operators and different units, and the second one gates only a
     * message. With both defaults in force the reference commits every five summaries and reports every
     * tenth commit, so a reader who conflated them would either commit ten times too rarely or report five
     * times too often.
     */
    public static final int DEFAULT_PROGRESS_LOG_FREQUENCY = 10;

    /**
     * The process exit status a failed run reports.
     *
     * <p>Assumptions: sixteen, from {@code MOVE 16 TO RETURN-CODE} at {@code cbl/CBPAUP0C.cbl} L382. The
     * paragraph holding it is named {@code 9999-ABEND} and does NOT abend anything: it displays a line at
     * L380, sets this value and returns normally at L383. It is therefore carried here as an exit status
     * and not as an abnormal termination, which matters because an orchestrator observes a container's
     * exit code rather than the manner of its ending. Mapping this value onto the process exit belongs to
     * the entry point that owns the process, because this class is also a bean inside a running container
     * and must not end one.
     */
    public static final int ABEND_EXIT_STATUS = 16;

    /**
     * The response code that marks an authorization approved.
     *
     * <p>Assumptions: {@code '00'}, and the test is EQUALITY with it rather than membership of a set. The
     * reference branches on {@code IF PA-AUTH-RESP-CODE = '00'} at {@code cbl/CBPAUP0C.cbl} L287, which is
     * the condition {@code 88 PA-AUTH-APPROVED} declares at {@code cpy/CIPAUDTY.cpy} L31, so every other
     * value takes the declined arm - including one no decline reason in the reference tree produces.
     * Testing for a known decline code instead would leave an unrecognised code reversing neither counter,
     * and the row would be removed with its parent's totals still carrying it.
     */
    private static final String RESPONSE_CODE_APPROVED = "00";

    /**
     * The century the two-digit year of an ordinal date resolves into.
     *
     * <p>Assumptions: two thousand, matching the window the fixture contract asserts and the one the
     * authorization consumer writes into, since that path renders the key's year as the calendar year
     * modulo one hundred and so every date this service reads was written by a clock in this century. The
     * reference never widens the year at all - it subtracts the ordinals as integers - so the window is
     * supplied by the migration. It is stated here rather than shared with the fraud path's pivot of
     * seventy on purpose: that pivot widens a PAST acquirer-supplied date over a hundred-year window,
     * whereas this value widens a date this system itself wrote, and one constant serving both would have
     * to be wrong for one of them.
     */
    private static final int ORDINAL_DATE_CENTURY = 2000;

    /**
     * The divisor separating the two-digit year from the three-digit day of year in an ordinal date.
     *
     * <p>Assumptions: one thousand, because the stored form is five digits of which the low three are the
     * day of year - {@code PA-AUTH-DATE-9C} is {@code PIC S9(05) COMP-3} at {@code cpy/CIPAUDTY.cpy} L20.
     */
    private static final int ORDINAL_YEAR_DIVISOR = 1000;

    /**
     * The lowest account identifier the outer walk can resume above.
     *
     * <p>Assumptions: zero, and the walk seeks STRICTLY above it, so a single predicate serves the first
     * call and every later one. The schema declares the account identifier positive, so nothing stored can
     * be at or below zero and no summary is skipped by opening the walk here.
     */
    private static final long BEFORE_FIRST_ACCOUNT = 0L;

    /**
     * The log a run's start, its throttled progress, its per-summary decisions and its statistics go to.
     */
    private static final Logger LOG = LoggerFactory.getLogger(PurgeJob.class);

    /**
     * The summary rows, walked in ascending key order and removed once emptied.
     */
    private final PendingAuthSummaryRepository summaries;

    /**
     * The authorization rows, read beneath each summary and removed once expired.
     */
    private final PendingAuthDetailRepository details;

    /**
     * The transaction boundary one committed window runs inside.
     */
    private final TransactionTemplate transactions;

    /**
     * Builds the job over its two repositories and the transaction boundary its windows commit at.
     *
     * <p>Refactoring Rationale: a {@link java.time.Clock} was injected here and is not any more, and the
     * removal is the constructor-level half of divergence D-E. The clock supplied a default business date,
     * reproducing {@code ACCEPT CURRENT-YYDDD FROM DAY} at {@code cbl/CBPAUP0C.cbl} L187 - and that read
     * is precisely what makes a reference run irreproducible, because the same data yields a different set
     * of qualifying rows on a different day. The date now arrives as a component of
     * {@link PurgeParameters} and has no default at all, so a run states the date it is being made for.
     * The dependency is removed rather than left unused, because an injected clock is what a later reader
     * would reach for the next time a date was needed here. Alternatives Considered: retaining a no-argument
     * entry point that read the clock, so that the reference's own default path stayed available. Rejected
     * because a caller that omitted the date would silently obtain the non-determinism this divergence
     * exists to remove, and a rerun for investigation would then select different rows from the run it was
     * investigating.
     *
     * @param summaries the summary repository the walk reads and the parent delete writes; must not be
     *     {@code null}
     * @param details the authorization repository the child read and the child delete use; must not be
     *     {@code null}
     * @param transactions the transaction boundary each committed window runs inside, standing for the
     *     reference checkpoint; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public PurgeJob(PendingAuthSummaryRepository summaries, PendingAuthDetailRepository details,
            TransactionTemplate transactions) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.details = Objects.requireNonNull(details, "details must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    /**
     * Runs one purge over every summary in the database for the parameters supplied.
     *
     * <p>Purpose: this is {@code MAIN-PARA} at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L136 to L180. It resolves the run
     * parameters, walks every summary and every authorization beneath it, commits on the window boundary,
     * and reports the four statistics the reference displays at its L173 to L176.
     *
     * <p>Assumptions: the statistics are reported ONLY when the run completes, which preserves the
     * reference's own behaviour by mechanism rather than by intent. That program contains exactly two
     * {@code GOBACK} statements: L180, reached only after the statistics block at L171 to L178, and L383
     * inside {@code 9999-ABEND}, which returns from the program directly. Every failure arm performs that
     * paragraph - L240 on a summary read, L270 on a detail read, L320 on a child delete, L345 on a parent
     * delete and L369 on a checkpoint - so a failed reference run displays no statistics. Its
     * {@code WS-ERR-FLG} would have offered a second route out of the loop at L142, but nothing in the
     * program ever sets that flag, so the abend return is the only failure route there is.
     *
     * <p>Assumptions: a failure during the walk is TERMINAL for the run and is reported as an exit status
     * rather than propagated as whatever the datastore raised. The reference collapses five distinct
     * failure arms onto one paragraph, so the migrated form collapses them onto one translation at the
     * boundary of the walk. Parameter refusal is deliberately outside that translation: the reference
     * substitutes defaults for its parameters at L196 to L209 and never ends a run over one, so a rejected
     * parameter is a caller defect reported as an argument failure and not a run that ended in failure.
     *
     * @param parameters the business date and the three counts the run is made for; must not be
     *     {@code null}
     * @return what the run read and removed, in the four counts the reference reports; never {@code null}
     * @throws NullPointerException if {@code parameters} is {@code null}
     * @throws PurgeAbendException if any read, delete or commit fails, carrying
     *     {@link #ABEND_EXIT_STATUS}; the windows already committed stand and the statistics are not
     *     reported
     */
    public PurgeOutcome purge(PurgeParameters parameters) {
        Objects.requireNonNull(parameters, "parameters must not be null");

        // WHY : Assumptions: the run's inputs are echoed before any row is touched, standing for the
        //       reference startup block at L190 to L194, which displays the program name, the parameter
        //       card it received and the date it resolved. Echoing the RESOLVED values rather than the raw
        //       card is the one change: the card is a positional seventeen-byte string whose defaults are
        //       applied after it is displayed, so the reference line shows what arrived while this one
        //       shows what will actually be used.
        LOG.info("purge starting businessDate={} expiryDays={} checkpointFrequency={}"
                        + " progressLogFrequency={}",
                parameters.businessDate(), parameters.expiryDays(), parameters.checkpointFrequency(),
                parameters.progressLogFrequency());

        PurgeOutcome total = PurgeOutcome.nothing();
        long position = BEFORE_FIRST_ACCOUNT;
        int committedWindows = 0;

        while (true) {
            long windowStart = position;
            PurgeWindow window = commitWindow(windowStart, parameters);
            total = total.combinedWith(window.outcome());
            committedWindows++;
            logProgress(committedWindows, window.lastAccountId(), parameters);

            if (window.exhausted()) {
                // WHY : Assumptions: the four counts are emitted as ONE structured event rather than as
                //       the reference's six separate display lines plus two rules. The reference writes to
                //       a job log read by eye, where the rules delimit a block; this writes to a
                //       structured log where one event with four fields is queryable and six lines are
                //       four values a reader has to reassemble. The values themselves and their meanings
                //       are unchanged, which is what the parity argument rests on.
                LOG.info("purge complete businessDate={} expiryDays={} summariesRead={}"
                                + " summariesDeleted={} detailsRead={} detailsDeleted={}"
                                + " committedWindows={}",
                        parameters.businessDate(), parameters.expiryDays(), total.summariesRead(),
                        total.summariesDeleted(), total.detailsRead(), total.detailsDeleted(),
                        committedWindows);
                return total;
            }
            position = window.lastAccountId();
        }
    }

    /**
     * Commits one window of summaries and reports where it stopped.
     *
     * <p>Purpose: this is {@code 9000-TAKE-CHECKPOINT} at {@code cbl/CBPAUP0C.cbl} L352 to L372, whose
     * {@code EXEC DLI CHKP} at L355 to L356 commits the unit of work and releases database position. A
     * transaction boundary is what does both here.
     *
     * <p>Alternatives Considered: the boundary is opened with a {@link TransactionTemplate} rather than by
     * annotating a second method of this class. Rejected because a call from one method of a bean to
     * another does not pass through the proxy that would begin the transaction, so the annotation would be
     * silently inert and the entire run would share one boundary - which is the opposite of what the
     * parameter asks for, and it would fail without any diagnostic.
     *
     * <p>Assumptions: a checkpoint failure ends the reference run at L369, so any failure escaping this
     * boundary ends this one. The datastore's own exception is translated here rather than at the call
     * site, because this is the single point at which the reference's five failure arms converge.
     *
     * @param startAfterAccountId the account identifier this window seeks strictly above
     * @param parameters the run parameters, supplying the window size and the expiry threshold; never
     *     {@code null}
     * @return what this window read and removed, where it stopped, and whether the walk is finished; never
     *     {@code null}
     * @throws PurgeAbendException if the window's reads, deletes or commit fail, carrying
     *     {@link #ABEND_EXIT_STATUS}
     */
    private PurgeWindow commitWindow(long startAfterAccountId, PurgeParameters parameters) {
        try {
            return this.transactions.execute(status -> purgeWindow(startAfterAccountId, parameters));
        } catch (RuntimeException failure) {
            // WHY : Trade-offs: the cause is attached rather than absorbed, and the message names only the
            //       position the run reached. Naming the position is what makes a failed run resumable by
            //       inspection, while the cause carries the datastore's own diagnosis for whoever reads
            //       the log; the reference displays its status code and its position at L366 to L368 and
            //       keeps nothing else. What is deliberately not included is any row content, because a
            //       failure message is the one place authorization data reaches a log by accident.
            throw new PurgeAbendException("purge ended at exit status " + ABEND_EXIT_STATUS
                    + " while processing summaries above account " + startAfterAccountId, failure);
        }
    }

    /**
     * Purges one window of summaries, starting strictly above a stated account identifier.
     *
     * <p>Purpose: this is {@code 2000-FIND-NEXT-AUTH-SUMMARY} at {@code cbl/CBPAUP0C.cbl} L216 to L242,
     * whose unqualified {@code EXEC DLI GN} against the root at L223 to L226 advances the walk one summary
     * at a time and whose {@code 'GB'} arm at L234 ends it. One call here covers a whole window rather than
     * one row, because the window is what the boundary above commits.
     *
     * <p>Alternatives Considered: the walk resumes from the KEY it last returned rather than from a counted
     * offset. A counted page locates its first row by counting from the start of the ordering on every
     * call, and this run DELETES from the very table it is walking, so an offset page would shift under its
     * own reader by construction and a skipped summary is one whose counters are never reconciled.
     * Resuming from the last key reproduces the get-next it stands for exactly.
     *
     * <p>Assumptions: exhaustion is decided by the page being SHORTER than the window rather than by a
     * separate count query. A short page means the keyed seek found no further summaries above the
     * position, which is the same signal the reference takes from its end-of-database status, and it costs
     * no additional statement.
     *
     * @param startAfterAccountId the account identifier this window seeks strictly above
     * @param parameters the run parameters, supplying the window size and the expiry threshold; never
     *     {@code null}
     * @return what this window read and removed, where it stopped, and whether the walk is finished; never
     *     {@code null}
     */
    private PurgeWindow purgeWindow(long startAfterAccountId, PurgeParameters parameters) {
        List<PendingAuthSummary> page = this.summaries.findByAccountIdGreaterThanOrderByAccountIdAsc(
                Long.valueOf(startAfterAccountId), Limit.of(parameters.checkpointFrequency()));
        if (page.isEmpty()) {
            return new PurgeWindow(PurgeOutcome.nothing(), startAfterAccountId, true);
        }

        PurgeOutcome outcome = PurgeOutcome.nothing();
        long lastAccountId = startAfterAccountId;
        for (PendingAuthSummary summary : page) {
            lastAccountId = summary.getAccountId().longValue();
            outcome = outcome.combinedWith(purgeSummary(summary, parameters));
        }
        return new PurgeWindow(outcome, lastAccountId, page.size() < parameters.checkpointFrequency());
    }

    /**
     * Purges the expired authorizations beneath one summary, and the summary itself once it is emptied.
     *
     * <p>Purpose: this is {@code 3000-FIND-NEXT-AUTH-DTL} at {@code cbl/CBPAUP0C.cbl} L248 to L272, whose
     * {@code EXEC DLI GNP} at L255 to L258 walks the children of the summary the outer read is positioned
     * on, together with the two deletes it drives - {@code 5000-DELETE-AUTH-DTL} at L303 to L323, whose
     * {@code DLET} of the child is at L310 to L313, and {@code 6000-DELETE-AUTH-SUMMARY} at L328 to L347,
     * whose {@code DLET} of the root is at L335 to L338.
     *
     * <p>Assumptions: removal proceeds CHILD BEFORE PARENT, and the order is part of the contract. The
     * reference deletes every qualifying child inside its inner loop at L146 to L154 and only then reaches
     * the root guard at L156, and the migrated schema states the same dependency as a foreign key from the
     * detail table to the summary, so the reverse order would be refused by the database rather than merely
     * diverge from the reference.
     *
     * <p>Alternatives Considered: removing the aged rows with one set-based delete per account. Rejected
     * because the reference performs per-row arithmetic on the PARENT as it walks - L287 to L292 selects
     * one of four subtractions using a value only the row being removed carries - so a statement that
     * deleted the rows in bulk would leave the parent's four running totals describing children that no
     * longer exist, with nothing in the schema able to detect the drift.
     *
     * <p>Assumptions: the reversal and the delete reach the database in ONE unit of work, because both
     * happen inside the window boundary opened above and are flushed at its single commit. Splitting them
     * across two transactions, or reversing afterwards as a compensation, would both introduce a state the
     * reference does not have - a removed authorization whose parent still counts it, or the reverse - and
     * a reader of the summary screen would see totals that disagree with the rows beneath them.
     *
     * @param summary the summary to process; never {@code null}
     * @param parameters the run parameters, supplying the business date and the expiry threshold; never
     *     {@code null}
     * @return what this summary read and removed; never {@code null}
     */
    private PurgeOutcome purgeSummary(PendingAuthSummary summary, PurgeParameters parameters) {
        Long accountId = summary.getAccountId();
        List<PendingAuthDetail> children =
                this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(accountId);
        LOG.debug("authorizations read beneath summary accountId={} count={}", accountId,
                children.size());

        int detailsDeleted = 0;
        for (PendingAuthDetail child : children) {
            if (!hasExpired(child, parameters.businessDate(), parameters.expiryDays())) {
                continue;
            }
            reverse(summary, child);
            this.details.delete(child);
            detailsDeleted++;
            LOG.debug("authorization deleted accountId={} authDate={} authTime={}", accountId,
                    child.getId().getAuthDate(), child.getId().getAuthTime());
        }

        // WHY : Refactoring Rationale: BOTH counters are tested, which is divergence D-F recorded on this
        //       class -- the reference guard at L156 names the approved counter on both sides of its
        //       conjunction, so a summary with unexpired DECLINED authorizations beneath it satisfies it and
        //       is removed together with those rows.
        //       Assumptions: the comparison is <= rather than == because these counters are SIGNED four-digit
        //       fields whose negative half the schema's own check constraint admits, and the domain type
        //       floors a decrement at -9999 rather than at zero, so a summary loaded from an extract whose
        //       counters understated its children can be driven below zero and must still qualify.
        boolean summaryDeleted = summary.getApprovedAuthCount() <= 0
                && summary.getDeclinedAuthCount() <= 0;
        if (summaryDeleted) {
            // WHY : Assumptions: any authorization still beneath this summary goes with it, which is what
            //       the reference hierarchical delete does -- removing a root removes its dependents -- and
            //       what the detail table's foreign key reproduces with its cascade. That case is reachable
            //       only from an extract whose counters understated its children, and leaving those rows
            //       behind would strand them with no parent for the key to satisfy.
            this.summaries.delete(summary);
            LOG.debug("summary deleted, nothing pending beneath it accountId={}", accountId);
        }
        return new PurgeOutcome(1, summaryDeleted ? 1 : 0, children.size(), detailsDeleted);
    }

    /**
     * Reverses one expiring authorization out of its parent's running counters and totals.
     *
     * <p>Purpose: this is the reversal half of {@code 4000-CHECK-IF-EXPIRED} at {@code cbl/CBPAUP0C.cbl}
     * L287 to L293.
     *
     * <p>Assumptions: the two arms take their amount from DIFFERENT columns of the same expiring
     * authorization, and the response code selects the arm. The approved arm subtracts the APPROVED amount
     * at L289 and the declined arm subtracts the TRANSACTION amount at L292, mirroring the writer exactly -
     * {@code cbl/COPAUA0C.cbl} adds the approved amount at L815 and the transaction amount at L821. The
     * asymmetry is design and not oversight, and it is preserved on both sides: passing one amount to
     * whichever arm was taken would be silently wrong for every declined authorization whose two amounts
     * differ, which is all of them, because a decline approves nothing.
     *
     * <p>Assumptions: only the four counters and totals move. Neither balance is released here, which is
     * the preserved asymmetry D-PURGE-BALANCE recorded on this class.
     *
     * @param summary the parent whose counters and totals are reversed; never {@code null}
     * @param child the expiring authorization supplying the amount and the response code; never
     *     {@code null}
     */
    private static void reverse(PendingAuthSummary summary, PendingAuthDetail child) {
        if (RESPONSE_CODE_APPROVED.equals(child.getAuthRespCode())) {
            summary.reverseApproved(reversalAmount(child.getApprovedAmount()));
        } else {
            summary.reverseDeclined(reversalAmount(child.getTransactionAmount()));
        }
    }

    /**
     * Decides whether an authorization has aged to or past the expiry threshold.
     *
     * <p>Purpose: this is the qualification half of {@code 4000-CHECK-IF-EXPIRED} at
     * {@code cbl/CBPAUP0C.cbl} L280 to L284 and L295.
     *
     * <p>Assumptions: the key's date component is the DECODED ordinal date and never the nines complement
     * the segment stores. The reference decodes it at L280 because its copy of the segment still holds the
     * complement; this key type holds the decoded value and its own constructor refuses anything outside
     * the ordinal-date domain, so repeating the complement arithmetic here would decode a value that was
     * already decoded.
     *
     * @param child the authorization to test; never {@code null}
     * @param businessDate the date elapsed days are measured to; never {@code null}
     * @param expiryDays the inclusive threshold in days at which an authorization expires
     * @return {@code true} when at least {@code expiryDays} calendar days have elapsed since the
     *     authorization, so that a row exactly at the threshold qualifies
     */
    private static boolean hasExpired(PendingAuthDetail child, LocalDate businessDate, int expiryDays) {
        LocalDate authorizedOn = calendarDateOf(child.getId().getAuthDate().intValue());
        return ChronoUnit.DAYS.between(authorizedOn, businessDate) >= expiryDays;
    }

    /**
     * Converts a five-digit ordinal date into the calendar date it denotes.
     *
     * <p>Assumptions: this conversion is the whole of divergence D-E. Differencing two calendar dates is
     * what makes 31 December and 1 January one day apart instead of the 636 a plain subtraction of their
     * ordinals yields at {@code cbl/CBPAUP0C.cbl} L282, and it removes the four-digit ceiling that
     * program's {@code WS-DAY-DIFF} field imposes at its L47.
     *
     * @param ordinalDate a two-digit year followed by a three-digit day of year, as one integer
     * @return the calendar date that ordinal denotes in {@link #ORDINAL_DATE_CENTURY}
     * @throws java.time.DateTimeException if the day of year is outside the range the resolved year admits,
     *     which the key type's own domain check already excludes for any stored row
     */
    private static LocalDate calendarDateOf(int ordinalDate) {
        return LocalDate.ofYearDay(ORDINAL_DATE_CENTURY + ordinalDate / ORDINAL_YEAR_DIVISOR,
                ordinalDate % ORDINAL_YEAR_DIVISOR);
    }

    /**
     * Supplies an amount for the reversal at the one scale every money column stores.
     *
     * <p>Assumptions: the amount is routed through {@link Money} rather than used as it arrives, so the
     * subtraction happens at exactly two decimal places under one rounding contract for the whole money
     * path. The two detail columns are {@code PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L34 and
     * L35, which is precisely the domain {@link Money} bounds, while the summary totals they are
     * subtracted from are the narrower {@code PIC S9(09)V99 COMP-3} of {@code cpy/CIPAUSMY.cpy} L29 and
     * L30 - so the scales agree and the magnitudes do not, and it is the summary type that refuses an
     * out-of-domain result.
     *
     * <p>Assumptions: an absent amount reverses an exact zero rather than ending the run. The columns
     * themselves are declared not-null, so a row read from the database cannot carry one; what can is an
     * instance built in memory, because the entity's constructor does not refuse a null amount. Ending a
     * run over a value that contributes zero either way would leave every later summary unprocessed.
     *
     * @param amount the stored amount, or {@code null} when an in-memory instance carries none
     * @return the amount at scale two, or an exact zero at the same scale; never {@code null}
     */
    private static BigDecimal reversalAmount(BigDecimal amount) {
        return amount == null ? Money.ZERO.amount() : Money.of(amount).amount();
    }

    /**
     * Reports progress once every stated number of committed windows.
     *
     * <p>Purpose: this is the throttled display of {@code 9000-TAKE-CHECKPOINT} at
     * {@code cbl/CBPAUP0C.cbl} L358 to L364, which counts successful checkpoints at L359, compares that
     * count with the display frequency at L360, resets it at L361 and only then reports the summaries read
     * so far and the account it had reached at L362 to L363.
     *
     * <p>Assumptions: this frequency counts COMMITTED WINDOWS and gates nothing but a message, so it can
     * never cause or suppress a commit. It is the second of two independent controls, and conflating it
     * with the window size is the mistake it is written to prevent: the reference's own two counters are
     * declared separately at L51 and L52, incremented at different sites, and compared against different
     * parameters with different operators.
     *
     * @param committedWindows the number of windows committed so far, counting from one
     * @param lastAccountId the highest account identifier the most recent window reached
     * @param parameters the run parameters, supplying the progress frequency; never {@code null}
     */
    private void logProgress(int committedWindows, long lastAccountId, PurgeParameters parameters) {
        // WHY : Assumptions: the modulo expresses the reference's count-and-reset without keeping a second
        //       piece of mutable state. Its L359 increments a counter and its L361 zeroes it on the same
        //       comparison, which is a remainder test written as an accumulator; a field here would have to
        //       be reset on exactly that boundary to mean the same thing, and it would be reachable from
        //       two windows at once if a caller ever ran two purges against one bean.
        if (committedWindows % parameters.progressLogFrequency() == 0) {
            LOG.info("purge progress committedWindows={} lastAccountId={}", committedWindows,
                    lastAccountId);
        }
    }

    /**
     * Refuses a run parameter that is not positive.
     *
     * @param value the parameter value being checked
     * @param name the parameter name, used to identify it in the refusal
     * @throws IllegalArgumentException if {@code value} is not positive
     */
    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive but was " + value);
        }
    }

    /**
     * The parameters one purge run is made for, standing for the reference program's control card.
     *
     * <p>Purpose: this is {@code 1000-INITIALIZE} at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L183 to L210, which reads the card and
     * applies a default to each of its four fields.
     *
     * <p>Assumptions: the reference card is a POSITIONAL fixed-width record of exactly seventeen bytes,
     * declared as {@code PRM-INFO} at L98 to L108 in the shape {@code NN,NNNNN,NNNNN,X} - a two-digit
     * expiry, a five-character commit frequency, a five-character display frequency and a one-character
     * debug flag, with the three {@code FILLER PIC X(01)} items at L100, L102 and L104 being the commas and
     * a fourth at L108 closing the record. It arrives on the job's input stream, by
     * {@code ACCEPT PRM-INFO FROM SYSIN} at L189, and NOT as a program argument: the
     * {@code PROCEDURE DIVISION USING} header at L132 to L133 takes only the two database interface blocks,
     * so the {@code PARM=} string at {@code jcl/CBPAUP0J.jcl} L25 is consumed by the region controller the
     * job actually executes and never reaches this program's own logic. Carrying the four values as typed
     * components rather than as a seventeen-byte string is the migrated form; the positional layout is
     * recorded because it is the only place the field order and widths are stated.
     *
     * <p>Assumptions: the card's fourth field has no component here. It is the debug flag, defaulted to
     * {@code 'N'} at L207 to L209 and read at exactly four sites - L220, L252, L307 and L332 - each of which
     * displays a counter or an account identifier under {@code IF DEBUG-ON}. Those four become debug-level
     * log statements, so whether they are emitted is decided by the configured log level. Alternatives
     * Considered: carrying the flag as a fifth component and branching on it. Rejected because it would put
     * a second switch in front of statements the log level already governs, and a run could then be started
     * with tracing requested and the level set to discard it, or with the level raised and the flag off - two
     * controls that can disagree, where one cannot.
     *
     * <p>Refactoring Rationale: the business date is a REQUIRED component with no default, where the
     * reference reads it from the platform at L187. This is the parameter half of divergence D-E: a date
     * read from a clock makes the qualifying set of rows depend on the day the run happens, so the same
     * data purges differently on two days and a rerun cannot reproduce the run it is meant to reproduce.
     * Stating it makes a run repeatable and makes a test able to name the boundary it is exercising.
     *
     * @param businessDate the date elapsed days are measured to, which the reference reads from the
     *     platform at L187; must not be {@code null}
     * @param expiryDays the inclusive threshold in days at which an authorization expires, defaulting to
     *     {@link PurgeJob#DEFAULT_EXPIRY_DAYS}; must be positive, so the zero the reference admits is
     *     refused
     * @param checkpointFrequency the number of summaries processed per committed window, defaulting to
     *     {@link PurgeJob#DEFAULT_CHECKPOINT_FREQUENCY}; must be positive
     * @param progressLogFrequency the number of committed windows between progress reports, defaulting to
     *     {@link PurgeJob#DEFAULT_PROGRESS_LOG_FREQUENCY} and governing only reporting; must be positive
     */
    public record PurgeParameters(LocalDate businessDate, int expiryDays, int checkpointFrequency,
            int progressLogFrequency) {

        /**
         * Validates the four components, refusing a business date that is absent and any count that is not
         * positive.
         *
         * <p>Refactoring Rationale: the expiry threshold is refused at zero, which is divergence
         * D-PURGE-EXPIRY-FLOOR recorded on the enclosing class. Assumptions: the two frequencies are
         * refused at zero for their own reasons rather than by analogy - a window of zero summaries would
         * request an empty page and end the walk before it began, and a report frequency of zero would be a
         * division by zero in the remainder test that throttles it. All three are checked here rather than
         * at the entry point so that a parameter set cannot exist in an unusable state, which is what lets
         * the entry point treat a refusal as a caller defect rather than as a run that failed.
         *
         * @param businessDate the date elapsed days are measured to; must not be {@code null}
         * @param expiryDays the inclusive expiry threshold in days; must be positive
         * @param checkpointFrequency the number of summaries per committed window; must be positive
         * @param progressLogFrequency the number of committed windows between progress reports; must be
         *     positive
         * @throws NullPointerException if {@code businessDate} is {@code null}
         * @throws IllegalArgumentException if {@code expiryDays}, {@code checkpointFrequency} or
         *     {@code progressLogFrequency} is not positive
         */
        public PurgeParameters {
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            requirePositive(expiryDays, "expiryDays");
            requirePositive(checkpointFrequency, "checkpointFrequency");
            requirePositive(progressLogFrequency, "progressLogFrequency");
        }

        /**
         * Builds the parameters for a stated business date using all three reference defaults.
         *
         * <p>Assumptions: this reproduces the card the reference resolves when every one of its three
         * numeric fields arrives unusable - five, five and ten, from L199, L202 and L205 respectively. It
         * takes the business date because that value has no reference default to reproduce, which is the
         * divergence recorded on this record.
         *
         * @param businessDate the date elapsed days are measured to; must not be {@code null}
         * @return parameters carrying that date and the three defaults; never {@code null}
         * @throws NullPointerException if {@code businessDate} is {@code null}
         */
        public static PurgeParameters forBusinessDate(LocalDate businessDate) {
            return new PurgeParameters(businessDate, DEFAULT_EXPIRY_DAYS, DEFAULT_CHECKPOINT_FREQUENCY,
                    DEFAULT_PROGRESS_LOG_FREQUENCY);
        }
    }

    /**
     * One committed window's result: what it purged, where it stopped, and whether the walk is finished.
     *
     * @param outcome what this window read and removed
     * @param lastAccountId the highest account identifier this window processed, which the next window
     *     seeks strictly above
     * @param exhausted {@code true} when no summaries remain above {@code lastAccountId}
     */
    private record PurgeWindow(PurgeOutcome outcome, long lastAccountId, boolean exhausted) {
    }

    /**
     * What a purge run did, in the four counts the reference program reports at its L173 to L176.
     *
     * <p>Assumptions: the four counts are exactly the reference's four, in its order and with its meanings -
     * summaries walked, summaries removed for having nothing pending beneath them, authorizations walked,
     * and authorizations removed for having expired. They are the reference's {@code WS-NO-SUMRY-READ},
     * {@code WS-NO-SUMRY-DELETED}, {@code WS-NO-DTL-READ} and {@code WS-NO-DTL-DELETED}, each declared as a
     * signed eight-digit binary field, so no count here can exceed what the reference could hold.
     *
     * @param summariesRead the number of summaries walked
     * @param summariesDeleted the number of summaries removed for having nothing pending beneath them
     * @param detailsRead the number of authorizations walked
     * @param detailsDeleted the number of authorizations removed for having expired
     */
    public record PurgeOutcome(int summariesRead, int summariesDeleted, int detailsRead,
            int detailsDeleted) {

        /**
         * Returns the identity for accumulation: a run that has processed nothing.
         *
         * @return a carrier holding four zeros; never {@code null}
         */
        static PurgeOutcome nothing() {
            return new PurgeOutcome(0, 0, 0, 0);
        }

        /**
         * Adds another result's counts to these.
         *
         * @param other the counts to add; must not be {@code null}
         * @return a new carrier holding the component-wise sum; never {@code null}
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

    /**
     * Reports that a purge run ended without completing, carrying the exit status the run reports.
     *
     * <p>Purpose: this is {@code 9999-ABEND} at {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl}
     * L377 to L383, which every failure arm of the reference program performs.
     *
     * <p>Assumptions: that paragraph does NOT abend the task despite its name. It displays a line at L380,
     * moves sixteen to the return code at L382 and returns normally at L383, so what the surrounding
     * environment observes is an exit status. This type therefore carries a status rather than signalling an
     * abnormal end, and translating it into a process exit belongs to the entry point that owns the process.
     *
     * <p>Trade-offs: this is unchecked, so no caller is compelled to handle it. That is the intended shape
     * for a batch entry point whose only two outcomes are a completed run and a run reported as failed:
     * making it checked would put a handler in every caller whose correct body is to let it through, and the
     * one caller that must act on it is the process boundary, which acts on it precisely by not handling it.
     */
    public static final class PurgeAbendException extends RuntimeException {

        /**
         * The serialization identity of this exception type.
         */
        private static final long serialVersionUID = 1L;

        /**
         * Builds the report with a message naming the position reached and the cause that ended the run.
         *
         * @param message what the run was doing when it ended, naming the position and no row content;
         *     must not be {@code null}
         * @param cause the failure the datastore or the transaction boundary raised; must not be
         *     {@code null}
         */
        PurgeAbendException(String message, Throwable cause) {
            super(Objects.requireNonNull(message, "message must not be null"),
                    Objects.requireNonNull(cause, "cause must not be null"));
        }

        /**
         * Returns the exit status this run reports.
         *
         * @return {@link PurgeJob#ABEND_EXIT_STATUS}, which is sixteen and never zero, so an orchestrator
         *     watching the process exit code observes the failure
         */
        public int exitStatus() {
            return ABEND_EXIT_STATUS;
        }
    }
}
