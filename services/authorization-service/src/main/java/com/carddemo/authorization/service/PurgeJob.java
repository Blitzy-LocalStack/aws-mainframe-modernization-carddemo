package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.money.Money;
import com.carddemo.common.observability.ThrowableDigest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
     * The largest expiry threshold the reference parameter card's two-digit field can express.
     *
     * <p>Assumptions: this is the WIDTH of {@code PRM-EXPIRY-DAYS} on the positional card recorded on
     * {@link PurgeParameters}, not a retention policy. Ninety-nine days is what two digits hold; no
     * retention period is stated anywhere in the reference material, so the width is the only bound that
     * is a fact about the reference rather than an invention of this migration.
     */
    public static final int MAX_EXPIRY_DAYS = 99;

    /**
     * The largest value either frequency's five-character card field can express.
     *
     * <p>Assumptions: both frequencies occupy five characters on the same card, so they share one ceiling
     * rather than each carrying its own copy of the same number. They are otherwise independent controls
     * -- one gates commits and the other gates a message -- and sharing the ceiling says only that they
     * are written in fields of equal width, which they are.
     */
    public static final int MAX_CARD_FREQUENCY = 99_999;

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
     * How many of one account's authorizations are read in a single keyset chunk.
     *
     * <p>Assumptions: the chunk is independent of the window cap above, because the two bound different
     * things -- the window caps how many SUMMARIES a transaction commits, matching the reference's
     * checkpoint frequency, while this caps how many CHILDREN are read at once beneath any one of them.
     * A single figure could not serve both: the reference's checkpoint frequency is a handful, and
     * reading an account's history a handful of rows at a time would issue a query per few rows.</p>
     */
    private static final int CHILD_CHUNK_SIZE = 500;

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
            PurgeWindow window = commitWindow(position, committedWindows + 1, parameters);
            total = total.combinedWith(window.outcome());
            committedWindows++;
            logProgress(committedWindows, total.summariesRead(), total.summariesDeleted(), parameters);

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
     * @param windowOrdinal the one-based position of this window in the run, used to identify it in a
     *     failure without naming the account it reached
     * @param parameters the run parameters, supplying the window size and the expiry threshold; never
     *     {@code null}
     * @return what this window read and removed, where it stopped, and whether the walk is finished; never
     *     {@code null}
     * @throws PurgeAbendException if the window's reads, deletes or commit fail, carrying
     *     {@link #ABEND_EXIT_STATUS}
     */
    private PurgeWindow commitWindow(long startAfterAccountId, int windowOrdinal,
            PurgeParameters parameters) {
        try {
            return this.transactions.execute(
                    status -> purgeWindow(startAfterAccountId, windowOrdinal, parameters));
        } catch (PurgeAbendException located) {
            // WHY : Refactoring Rationale: a refusal that already names the account position within the
            //       window is passed through UNWRAPPED, because wrapping it again would bury the more
            //       precise position behind the less precise one and would add a link to the cause chain
            //       that carries nothing the outer link does not already say.
            throw located;
        } catch (RuntimeException failure) {
            // WHY : Trade-offs: the cause is attached rather than absorbed, and the message names only the
            //       position the run reached. Naming the position is what makes a failed run resumable by
            //       inspection, while the cause carries the datastore's own diagnosis for whoever reads
            //       the log; the reference displays its status code and its position at L366 to L368 and
            //       keeps nothing else. What is deliberately not included is any row content, because a
            //       failure message is the one place authorization data reaches a log by accident.
            // WHY : Assumptions: the refusal locates the failure by the WINDOW rather than by the account
            //       it stopped above. An exception message reaches a log, a monitor and often a ticket,
            //       so an eleven-digit account number in it is a copy of a customer identifier in three
            //       places that do not protect it; the window's start is recoverable from the run's own
            //       progress lines, which name windows, so nothing diagnostic is lost.
            throw new PurgeAbendException("purge ended at exit status " + ABEND_EXIT_STATUS
                    + " while processing window " + windowOrdinal + " of summaries; see the run's"
                    + " progress lines for the last window it committed", failure);
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
     * <p>Purpose of the two reads: the walk returns KEYS and each summary is then loaded by its own read,
     * so a page this run is deleting from never carries entities whose state predates the window. The
     * lost update this arrangement once used a pessimistic lock for is closed elsewhere and differently:
     * the four counters are reversed by ONE statement computed in the database rather than by mutating a
     * loaded entity, which is why {@code PendingAuthSummaryRepository} declares no locking read at all
     * and records why taking one would be concurrency machinery the reference system does not have.
     *
     * <p>Alternatives Considered: keeping the walk's own entity page and reversing those instances
     * directly, which is one read fewer. Rejected because it makes the deletion decision depend on
     * counters read before the window began, and the row is being deleted from underneath that snapshot
     * by this very run.
     *
     * <p>Assumptions: a key the walk returned whose summary the individual read cannot find is SKIPPED
     * and contributes nothing, rather than ending the window or being counted as read. The row was removed
     * between the two reads, by a concurrent purge of the same window or by an operator, and the
     * reference walk would simply never have returned it; counting a summary this run did not process
     * would overstate the statistics the run reports. The position still advances past the missing key,
     * because a window that refused to advance past it would re-seek the same key on the next call and
     * the walk would not terminate.
     *
     * @param startAfterAccountId the account identifier this window seeks strictly above
     * @param windowOrdinal the one-based position of this window in the run, named in a failure so that a
     *     refusal locates itself without naming the account it reached
     * @param parameters the run parameters, supplying the window size and the expiry threshold; never
     *     {@code null}
     * @return what this window read and removed, where it stopped, and whether the walk is finished; never
     *     {@code null}
     * @throws PurgeAbendException if the work beneath one summary fails, naming that summary's position
     *     within this window and carrying {@link #ABEND_EXIT_STATUS}
     */
    private PurgeWindow purgeWindow(long startAfterAccountId, int windowOrdinal,
            PurgeParameters parameters) {
        List<Long> page = this.summaries.findAccountIdsAboveOrderByAccountIdAsc(
                Long.valueOf(startAfterAccountId), Limit.of(parameters.checkpointFrequency()));
        if (page.isEmpty()) {
            return new PurgeWindow(PurgeOutcome.nothing(), startAfterAccountId, true);
        }

        PurgeOutcome outcome = PurgeOutcome.nothing();
        long lastAccountId = startAfterAccountId;
        int accountOrdinal = 0;
        for (Long accountId : page) {
            // WHY : Assumptions: the position advances past every key the walk returned, INCLUDING one
            //       whose row the second read cannot find. The row was removed between the two reads --
            //       by a concurrent run or by an operator -- and a window that declined to advance past
            //       it would re-seek the same key on the next call and never terminate. It contributes
            //       nothing to the statistics, because this run did not process a summary there.
            lastAccountId = accountId.longValue();
            accountOrdinal++;
            Optional<PendingAuthSummary> summary = this.summaries.findByAccountId(accountId);
            if (summary.isEmpty()) {
                LOG.debug("summary vanished between the walk and its read accountOrdinal={}",
                        accountOrdinal);
                continue;
            }
            // WHY : ⚠️ Refactoring Rationale: a failure beneath one summary is located to THAT summary
            //       before it leaves the window, where previously it reached the enclosing boundary naming
            //       only the window. A window holds up to the checkpoint frequency of summaries, so a
            //       failure could name a range rather than a row, and the run's own progress lines name
            //       windows too -- an operator therefore had no way to narrow the failure any further than
            //       the run had already reported. The account's per-run ordinal narrows it to one summary,
            //       and the debug line this method's callee writes for each account carries the same
            //       ordinal, so the two correlate.
            // WHY : Assumptions: the ordinal is used and the account identifier is NOT, which is the same
            //       rule every line and every message in this class follows -- an exception message reaches
            //       a log, a monitor and often a ticket, none of which protects a customer identifier.
            try {
                outcome = outcome.combinedWith(purgeSummary(summary.get(), parameters, accountOrdinal));
            } catch (RuntimeException failure) {
                // WHY : Assumptions: the position is LOGGED here as well as carried on the refusal, because
                //       the two reach different readers. The refusal's own message reaches whatever catches
                //       it -- the task entry point translates it into an exit status and records a
                //       message-free digest, since a wrapped driver message can quote key values and on
                //       this schema those are a card number and a transaction identifier. A named event
                //       carrying only ordinals and the fault's type chain is safe to write, and it is what
                //       an operator reads to decide which window to re-run.
                LOG.error("event=authorization.purge.abend windowOrdinal={} accountOrdinal={}"
                        + " exitStatus={} failure={}", windowOrdinal, accountOrdinal, ABEND_EXIT_STATUS,
                        ThrowableDigest.of(failure));
                throw new PurgeAbendException("purge ended at exit status " + ABEND_EXIT_STATUS
                        + " on summary " + accountOrdinal + " of window " + windowOrdinal
                        + "; the whole window is rolled back and earlier windows stand", failure);
            }
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
     * <p>Assumptions: the {@code detailsDeleted} figure this returns is EXACT, and it is exact because the
     * summary delete below is withheld while any child remains. Every removal is therefore performed by the
     * loop that counts it, and the foreign key's cascade -- which removes rows without passing through that
     * loop, and so cannot be counted by it -- is left with nothing to remove. A run's statistics and the
     * rows it took are the same set.
     *
     * @param summary the summary to process; never {@code null}
     * @param parameters the run parameters, supplying the business date and the expiry threshold; never
     *     {@code null}
     * @param accountOrdinal this account's one-based position within the current window, which is what
     *     every line this method logs names the account by instead of its identifier
     * @return what this summary read and removed; never {@code null}
     */
    private PurgeOutcome purgeSummary(PendingAuthSummary summary, PurgeParameters parameters,
            int accountOrdinal) {
        Long accountId = summary.getAccountId();
        Reversal reversal = new Reversal();
        int childrenRead = 0;
        int detailsDeleted = 0;
        Integer afterDate = null;
        Integer afterTime = null;
        // WHY : Refactoring Rationale: the children are traversed in STRICT KEYSET CHUNKS rather than
        //       materialised in one list. An earlier revision read every authorization beneath one
        //       account in a single unbounded query, which meant the window cap on the enclosing loop
        //       bounded only how many SUMMARIES a transaction touched and not how much it read: one
        //       account with a large history loaded its whole history into the persistence context
        //       inside a transaction whose size nothing limited. Chunking bounds the read while leaving
        //       the ORDER untouched, because each chunk continues from the last key of the one before.
        // WHY : Assumptions: the order stays newest-first, matching the reference's own reverse-ordered
        //       traversal, and every child is deleted before the summary is considered. That is the
        //       child-before-parent order the foreign key requires, and chunking cannot disturb it
        //       because the chunk boundary falls between children, never between the last child and
        //       the parent.
        while (true) {
            List<PendingAuthDetail> chunk = afterDate == null
                    ? this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(accountId,
                            Limit.of(CHILD_CHUNK_SIZE))
                    : this.details.findOlderThan(accountId, afterDate, afterTime,
                            Limit.of(CHILD_CHUNK_SIZE));
            if (chunk.isEmpty()) {
                break;
            }
            childrenRead += chunk.size();
            for (PendingAuthDetail child : chunk) {
                afterDate = child.getId().getAuthDate();
                afterTime = child.getId().getAuthTime();
                if (!hasExpired(child, parameters.businessDate(), parameters.expiryDays(),
                        accountOrdinal)) {
                    continue;
                }
                reversal.accumulate(child);
                this.details.delete(child);
                detailsDeleted++;
                // WHY : Assumptions: the account is named by its per-run ORDINAL and never by its
                //       identifier, here and in every other line this class emits. A maintenance log is
                //       read by more people than the data is, and an eleven-digit account number in it
                //       is a durable copy of a customer identifier outside the store that protects it.
                //       The ordinal locates the record within this run, which is what a diagnostic
                //       needs, and correlates with nothing outside it.
                LOG.debug("authorization deleted accountOrdinal={} authDate={} authTime={}",
                        accountOrdinal, child.getId().getAuthDate(), child.getId().getAuthTime());
            }
            if (chunk.size() < CHILD_CHUNK_SIZE) {
                break;
            }
        }
        LOG.debug("authorizations read beneath summary accountOrdinal={} count={}", accountOrdinal,
                childrenRead);
        // WHY : Refactoring Rationale: the reversal is applied ONCE per account through an arithmetic
        //       statement, rather than per child through the loaded summary entity. Mutating the entity
        //       per child made the write a read-modify-write over a row this walk holds no lock on, so
        //       two purges -- or a purge and a live authorization -- could each apply a reversal to the
        //       same starting value and lose one of them. Reversing all four figures in one statement
        //       computed in the database removes the lost update without taking a lock, which is the
        //       same non-locking discipline this service's charter requires of every other write.
        if (reversal.isPresent()) {
            reversal.applyTo(this.summaries, summary, accountOrdinal);
        }

        // WHY : Refactoring Rationale: BOTH counters are tested, which is divergence D-F recorded on this
        //       class -- the reference guard at L156 names the approved counter on both sides of its
        //       conjunction, so a summary with unexpired DECLINED authorizations beneath it satisfies it and
        //       is removed together with those rows.
        //       Assumptions: the comparison is <= rather than == because these counters are SIGNED four-digit
        //       fields whose negative half the schema's own check constraint admits, and the domain type
        //       floors a decrement at -9999 rather than at zero, so a summary loaded from an extract whose
        //       counters understated its children can be driven below zero and must still qualify.
        // WHY : Assumptions: the post-reversal counters are COMPUTED from the loaded values and the
        //       reversal just applied, rather than re-read from the row. Alternatives Considered: reading
        //       the summary back after the statement; rejected because a modifying query bypasses the
        //       persistence context, so a re-read inside the same transaction can be answered from the
        //       cached instance and silently return the pre-reversal counters -- a stale-read hazard that
        //       would make the deletion decision depend on cache state. Subtracting is exact, needs no
        //       round trip, and is the same arithmetic the statement performed.
        //       Assumptions: the comparison is <= rather than == because these counters are SIGNED
        //       four-digit fields whose negative half the schema's check constraint admits, so a summary
        //       loaded from an extract that understated its children can be driven below zero and must
        //       still qualify. That is also why the floor the domain type applies does not matter here:
        //       flooring a negative number cannot change the outcome of a test for at-most-zero.
        int remainingApproved = summary.getApprovedAuthCount() - reversal.approvedCount();
        int remainingDeclined = summary.getDeclinedAuthCount() - reversal.declinedCount();
        boolean countersExhausted = remainingApproved <= 0 && remainingDeclined <= 0;

        // WHY : ⚠️ Refactoring Rationale: the counter test is no longer SUFFICIENT on its own, and the
        //       second condition closes a data-loss path. The detail table's foreign key cascades on
        //       delete, so removing a summary removes every authorization beneath it -- and the counters
        //       this decision was taken from can reach zero while live, UNEXPIRED authorizations remain:
        //       the sweep itself decrements them and the schema admits their negative half, an extract
        //       load restores summaries and children from two SEPARATE files so a partial load commits
        //       one without the other, and nothing reconciles them afterwards. In that state the previous
        //       guard deleted the parent and the cascade silently took rows this run had judged NOT to
        //       have expired, while the run's statistics reported none removed, because a cascade is
        //       invisible to the tally the loop keeps. Requiring the child table itself to be empty makes
        //       the destructive step conditional on the thing that is actually at risk.
        // WHY : Assumptions: the remaining population is READ rather than computed, for the reason
        //       recorded on the repository method -- an authorization committed after this account's walk
        //       began carries a newer key than the walk will visit, so subtracting the loop's own tallies
        //       would report zero survivors for an account that had just acquired one.
        // WHY : Assumptions: BOTH conditions are kept rather than replacing the counters with the row
        //       count. The counter test is the reference program's own guard at {@code cbl/CBPAUP0C.cbl}
        //       L156 as divergence D-F widens it, so dropping it would delete summaries the reference
        //       leaves standing -- an account whose every authorization expired but whose counters still
        //       record them is exactly the row the reference keeps. The row count only ever WITHHOLDS a
        //       delete the counters would have allowed; it never causes one.
        long remainingChildren = this.details.countByIdAccountId(accountId);
        boolean summaryDeleted = countersExhausted && remainingChildren == 0;
        if (summaryDeleted) {
            this.summaries.delete(summary);
            LOG.debug("summary deleted, nothing pending beneath it accountOrdinal={}",
                    accountOrdinal);
        } else if (countersExhausted) {
            // WHY : Assumptions: the withheld delete is REPORTED rather than passed over silently,
            //       because the counters and the rows disagreeing is a reconciliation fault an operator
            //       should know about even though this run handled it safely. The line names the account's
            //       per-run ordinal and the two figures that disagreed, and no account identifier, which
            //       is the discipline every other line this class writes follows.
            LOG.warn("event=authorization.purge.summary-retained reason=children-remain"
                    + " accountOrdinal={} remainingChildren={} remainingApproved={}"
                    + " remainingDeclined={}", accountOrdinal, remainingChildren, remainingApproved,
                    remainingDeclined);
        }
        return new PurgeOutcome(1, summaryDeleted ? 1 : 0, childrenRead, detailsDeleted);
    }

    /**
     * Accumulates the four figures one account's expiring authorizations reverse.
     *
     * <p>Assumptions: the counts and the amounts are accumulated separately for the approved and the
     * declined side, because the reference reverses them through two different fields and the summary
     * row carries two independent counters. Collapsing them would make the reversal unable to restore
     * either side exactly.</p>
     */
    private static final class Reversal {

        /** How many approved authorizations are being reversed. */
        private int approvedCount;

        /** The approved amount being reversed. */
        private BigDecimal approvedAmount = Money.ZERO.amount();

        /** How many declined authorizations are being reversed. */
        private int declinedCount;

        /** The declined amount being reversed. */
        private BigDecimal declinedAmount = Money.ZERO.amount();

        /**
         * Adds one expiring authorization to the reversal.
         *
         * <p>Assumptions: the approved side is selected by the response code and the amount taken is the
         * APPROVED amount, while the declined side takes the TRANSACTION amount. That asymmetry is the
         * reference's, not a simplification: a declined authorization has no approved amount to restore.
         * </p>
         *
         * @param child the expiring authorization; must not be {@code null}
         */
        void accumulate(PendingAuthDetail child) {
            if (RESPONSE_CODE_APPROVED.equals(child.getAuthRespCode())) {
                this.approvedCount++;
                this.approvedAmount =
                        this.approvedAmount.add(reversalAmount(child.getApprovedAmount()));
            } else {
                this.declinedCount++;
                this.declinedAmount =
                        this.declinedAmount.add(reversalAmount(child.getTransactionAmount()));
            }
        }

        /**
         * Reports whether anything was accumulated.
         *
         * @return {@code true} when at least one authorization was added
         */
        boolean isPresent() {
            return this.approvedCount > 0 || this.declinedCount > 0;
        }

        /**
         * Returns how many approved authorizations are being reversed.
         *
         * @return the approved count
         */
        int approvedCount() {
            return this.approvedCount;
        }

        /**
         * Returns how many declined authorizations are being reversed.
         *
         * @return the declined count
         */
        int declinedCount() {
            return this.declinedCount;
        }

        /**
         * Applies the whole accumulated reversal to one summary row in a single statement.
         *
         * <p>⚠️ Assumptions: the statement is given the bound of the summary's own money domain in BOTH
         * directions, and it needs both because it SUBTRACTS. The amounts being reversed were read from
         * {@code pending_auth_detail}, whose two money columns are {@code PIC S9(10)V99} at
         * {@code cpy/CIPAUDTY.cpy} L34 to L35, and they are being taken out of a {@code PIC S9(09)V99}
         * total -- so one authorization an order of magnitude wider than the total drives the result past
         * the column's NEGATIVE bound. Unbounded, that raised a numeric-overflow error which abended the
         * whole sweep: windows already committed stayed committed, the table was left partly purged, and no
         * later run could ever complete while such a row existed. Bounding keeps the sweep running over the
         * rest of the table.</p>
         *
         * <p>Assumptions: the negation is passed as a parameter rather than written into the statement
         * because the query language has no unary negation of a bind parameter, so
         * {@code - :ceiling} is not expressible there.</p>
         *
         * <p>Assumptions: the reduction is REPORTED before the statement runs, and it is detected here
         * rather than inside the statement because a modifying query returns only a row count and cannot
         * say which of its four assignments was clamped. The line names the field and the account's per-run
         * ordinal and never an amount or an identifier, which is the discipline every other line this class
         * writes follows.</p>
         *
         * @param summaries the boundary the arithmetic statement is issued through; must not be
         *     {@code null}
         * @param summary the summary being reversed, read for its pre-reversal totals so that a reduction
         *     can be reported against the field it applies to; must not be {@code null}
         * @param accountOrdinal this account's one-based position within the current window, which is how
         *     the reduction is located without naming the account
         */
        void applyTo(PendingAuthSummaryRepository summaries, PendingAuthSummary summary,
                int accountOrdinal) {
            reportNarrowing("approvedAuthAmount",
                    summary.getApprovedAuthAmount().subtract(this.approvedAmount), accountOrdinal);
            reportNarrowing("declinedAuthAmount",
                    summary.getDeclinedAuthAmount().subtract(this.declinedAmount), accountOrdinal);
            summaries.reverseExpiredAuthorizations(summary.getAccountId(), this.approvedCount,
                    this.approvedAmount, this.declinedCount, this.declinedAmount,
                    PendingAuthSummary.MONEY_MAX_MAGNITUDE,
                    PendingAuthSummary.MONEY_MAX_MAGNITUDE.negate());
        }

        /**
         * Warns when one member of the reversal will be reduced to the column's bound.
         *
         * <p>Assumptions: the amount ITSELF is never logged, only the field it was about to be stored in
         * and the bound that will be stored instead. An authorization amount is transaction detail, and a
         * maintenance log is read by more people than the data is.</p>
         *
         * @param field the member whose stored value will be reduced; must not be {@code null}
         * @param reversed the value the subtraction produces before any bound is applied; must not be
         *     {@code null}
         * @param accountOrdinal this account's one-based position within the current window
         */
        private static void reportNarrowing(String field, BigDecimal reversed, int accountOrdinal) {
            if (PendingAuthSummary.exceedsStoredDomain(reversed)) {
                LOG.warn("event=authorization.purge.money-narrowed field={} accountOrdinal={} bound={}",
                        field, accountOrdinal, PendingAuthSummary.MONEY_MAX_MAGNITUDE);
            }
        }
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
     * @param child the authorization whose age is judged; must not be {@code null}
     * @param businessDate the run's business date, which is a parameter rather than a clock read so a
     *     rerun produces the same result; must not be {@code null}
     * @param expiryDays how many days old an authorization must be to have expired
     * @param accountOrdinal this account's one-based position within the current window, passed through so
     *     that a clamped ordinal date can be reported against a locatable account without naming it
     * @return {@code true} when the authorization is at or past the expiry threshold
     */
    private static boolean hasExpired(PendingAuthDetail child, LocalDate businessDate, int expiryDays,
            int accountOrdinal) {
        LocalDate authorizedOn = calendarDateOf(child.getId().getAuthDate().intValue(), accountOrdinal);
        return ChronoUnit.DAYS.between(authorizedOn, businessDate) >= expiryDays;
    }

    /**
     * Converts a five-digit ordinal date into the calendar date it denotes, for every value the column
     * admits.
     *
     * <p>Assumptions: this conversion is the whole of divergence D-E. Differencing two calendar dates is
     * what makes 31 December and 1 January one day apart instead of the 636 a plain subtraction of their
     * ordinals yields at {@code cbl/CBPAUP0C.cbl} L282, and it removes the four-digit ceiling that
     * program's {@code WS-DAY-DIFF} field imposes at its L47.
     *
     * <p>⚠️ Refactoring Rationale: this decode is now TOTAL over the stored domain, and the change closes a
     * defect that could disable retention for the whole table. The schema deliberately admits day 366 for
     * EVERY two-digit year -- {@code ck_pending_auth_detail_auth_date_domain} in
     * {@code db/migration/V1__authorization.sql} bounds the day component at 366 and its own recorded
     * reasoning is that resolving the leap year would need a century pivot the baseline never chose, so
     * "admitting the wider of the two cannot lose a real row". This method DOES pivot, at
     * {@link #ORDINAL_DATE_CENTURY}, so the two disagreed about exactly one value per non-leap year: an
     * ordinal such as {@code 99366} is stored without complaint and then resolved as day 366 of 2099, which
     * is not a leap year. {@code LocalDate.ofYearDay} raises for it, {@link #hasExpired} decodes EVERY child
     * before testing expiry, and the raise propagates as {@link PurgeAbendException} -- so one such row,
     * reachable through {@code --job=load-authorizations} from an extract, ended every purge run for every
     * account on every business date, leaving the table to grow without bound while earlier windows stayed
     * committed.
     *
     * <p>Assumptions: the resolution is applied HERE and the constraint is deliberately NOT tightened. A
     * check expressing leap years would contradict the reasoning recorded on that constraint and would
     * reject a genuine leap-day authorization whose century the schema cannot know; and it could not repair
     * a row already stored under the wider domain, which the decode must still be able to read. Making the
     * reader total fixes the condition for stored and future rows alike.
     *
     * <p>Alternatives Considered: skipping such a row with a warning and leaving it in place. Rejected
     * because a row nothing can decode is a row nothing can ever expire, so it would accumulate silently --
     * the same unbounded growth in a quieter form. Clamping to the last day the resolved year HAS is the
     * conservative reading of the value: day 366 of a 365-day year names its end, so the row expires no
     * EARLIER than the stored ordinal could possibly mean and the reversal arithmetic beneath it is
     * unaffected.
     *
     * @param ordinalDate a two-digit year followed by a three-digit day of year, as one integer
     * @param accountOrdinal this account's one-based position within the current window, named in the
     *     warning so an operator can locate the row without an account identifier reaching the log
     * @return the calendar date that ordinal denotes in {@link #ORDINAL_DATE_CENTURY}, with a day of year
     *     past the resolved year's length taken as its final day; never {@code null}
     */
    private static LocalDate calendarDateOf(int ordinalDate, int accountOrdinal) {
        int year = ORDINAL_DATE_CENTURY + ordinalDate / ORDINAL_YEAR_DIVISOR;
        int dayOfYear = ordinalDate % ORDINAL_YEAR_DIVISOR;
        int lengthOfYear = Year.of(year).length();
        if (dayOfYear > lengthOfYear) {
            // WHY : Assumptions: the line names the STORED ORDINAL and the account's per-run ordinal and
            //       nothing else, which is the same discipline every other line this class writes follows
            //       -- an eleven-digit account identifier in a maintenance log is a durable copy of a
            //       customer identifier outside the store that protects it. The ordinal pair is what an
            //       operator needs to find the row: the run's own progress lines carry the same account
            //       ordinal, and the stored date is a key column they can select on.
            LOG.warn("event=authorization.purge.ordinal-date-clamped accountOrdinal={} storedDate={}"
                    + " dayOfYear={} lengthOfYear={}", accountOrdinal, ordinalDate, dayOfYear,
                    lengthOfYear);
            dayOfYear = lengthOfYear;
        }
        return LocalDate.ofYearDay(year, dayOfYear);
    }

    /**
     * Supplies an amount for the reversal at the one scale every money column stores.
     *
     * <p>Assumptions: the amount is routed through {@link Money} rather than used as it arrives, so the
     * subtraction happens at exactly two decimal places under the general rounding contract,
     * {@code Money.GENERAL_ROUNDING}. Truncation applies to the interest accrual alone and no
     * authorization path performs one. The two detail columns are {@code PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L34 and
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
     * so far and the account it had reached at L362 to L363. Refactoring Rationale: the account half of
     * that report is deliberately NOT reproduced -- see the rationale at the emission site below.
     *
     * <p>Refactoring Rationale: this reports the SUMMARIES READ so far and not the account most recently
     * reached, where it reported the account. Of the two values the reference displays, the account
     * identifier is one this migration's observability contract names among the values a durable diagnostic
     * may not hold, and the contract requires such a value omitted rather than abbreviated. The other --
     * a running count -- is carried across unchanged, so what a progress line is for survives: an operator
     * watching a long run sees it advancing. Assumptions: the position the next window resumes from is
     * unaffected, because that is read from the window's own record at the loop rather than from anything
     * logged here; withdrawing the value from the message does not withdraw it from the walk.</p>
     *
     * <p>Assumptions: this frequency counts COMMITTED WINDOWS and gates nothing but a message, so it can
     * never cause or suppress a commit. It is the second of two independent controls, and conflating it
     * with the window size is the mistake it is written to prevent: the reference's own two counters are
     * declared separately at L51 and L52, incremented at different sites, and compared against different
     * parameters with different operators.
     *
     * <p>Refactoring Rationale: the reference reports the account it had reached at L363 and this member
     * deliberately does NOT, which is the one respect in which it departs from the paragraph it
     * transcribes. {@code docs/architecture/observability.md} names account identifiers among the values
     * a diagnostic must omit rather than abbreviate, and the reference wrote to a job log read by one
     * operator at a terminal while this writes to a retained, queryable log store. Reporting a resume
     * point every few windows would put a stream of account identifiers into that store for the life of
     * its retention, which is the disclosure the omission rule exists to prevent. Trade-offs: an operator
     * loses the ability to read the resume point out of the log and see how far a killed run had reached.
     * That is accepted because the resume point is not lost, only relocated: the window boundary is held
     * in the caller's own loop variable and the run's outcome counts are reported in full when the walk
     * ends, so what the log gives up is a convenience rather than the only copy.
     *
     * @param committedWindows the number of windows committed so far, counting from one
     * @param summariesSoFar how many summaries the run has read across every committed window, which
     *     locates progress without naming any account
     * @param summariesDeletedSoFar how many summaries the run has removed across every committed window,
     *     reported beside the read count because the two together say whether the run is finding work or
     *     merely walking past it
     * @param parameters the run parameters, supplying the progress frequency; never {@code null}
     */
    private void logProgress(int committedWindows, long summariesSoFar, long summariesDeletedSoFar,
            PurgeParameters parameters) {
        // WHY : Assumptions: the modulo expresses the reference's count-and-reset without keeping a second
        //       piece of mutable state. Its L359 increments a counter and its L361 zeroes it on the same
        //       comparison, which is a remainder test written as an accumulator; a field here would have to
        //       be reset on exactly that boundary to mean the same thing, and it would be reachable from
        //       two windows at once if a caller ever ran two purges against one bean.
        // WHY : Assumptions: progress is reported as a COUNT of summaries read, never as the account the
        //       last window reached. The keyset position is still carried internally -- it has to be, it
        //       is the cursor -- but writing it to a log turns a run's ordinary progress line into a
        //       durable record of customer account numbers in a place read far more widely than the
        //       table is. A running count answers the operational question, which is whether the run is
        //       advancing and how far through it is.
        // WHY : Assumptions: BOTH running counts are reported and not just the read count. A read count
        //       alone rises identically whether the run is deleting everything it walks or nothing at
        //       all, so it answers that the run is advancing while leaving unanswered whether it is doing
        //       any work -- which is the question an operator watching a long purge actually has. The two
        //       counts are the same pair the completion event reports, so a progress line and the final
        //       line are read the same way.
        if (committedWindows % parameters.progressLogFrequency() == 0) {
            LOG.info("purge progress committedWindows={} summariesRead={} summariesDeleted={}",
                    committedWindows, summariesSoFar, summariesDeletedSoFar);
        }
    }

    /**
     * Refuses a run parameter that is not positive or that exceeds what its card position can express.
     *
     * <p>Assumptions: both halves are one check and one message, because a caller that supplied an
     * out-of-range value needs the range and not the half of it that was violated. The message names the
     * parameter, the bound and the value, which is what lets an operator correct a job definition without
     * reading this source.
     *
     * @param value the parameter value being checked
     * @param name the parameter name, used to identify it in the refusal
     * @param ceiling the largest value the parameter's position on the reference card can express
     * @throws IllegalArgumentException if {@code value} is not positive or exceeds {@code ceiling}
     */
    private static void requireInRange(int value, String name, int ceiling) {
        if (value <= 0 || value > ceiling) {
            throw new IllegalArgumentException(name + " must be between 1 and " + ceiling + " but was "
                    + value);
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
         * Validates the four components, refusing a business date that is absent and any count outside
         * the range its position on the reference parameter card can express.
         *
         * <p>Refactoring Rationale: the expiry threshold is refused at zero, which is divergence
         * D-PURGE-EXPIRY-FLOOR recorded on the enclosing class. Assumptions: the two frequencies are
         * refused at zero for their own reasons rather than by analogy - a window of zero summaries would
         * request an empty page and end the walk before it began, and a report frequency of zero would be a
         * division by zero in the remainder test that throttles it. All three are checked here rather than
         * at the entry point so that a parameter set cannot exist in an unusable state, which is what lets
         * the entry point treat a refusal as a caller defect rather than as a run that failed.
         *
         * <p>Refactoring Rationale: each count is now refused ABOVE its position's width as well as at
         * zero, and the widths are the card's own. Only the lower half was checked, so this type accepted
         * values the reference card cannot express at all - an expiry of a thousand days against a
         * two-digit field, a window of a million summaries against a five-character one. That is not a
         * pedantic bound: an unbounded expiry silently turns the run into a no-op, because no
         * authorization is old enough to qualify, and a completed purge that deleted nothing is
         * indistinguishable from a correct one that had nothing to delete. An unbounded window is the
         * opposite failure - it asks for one page holding every summary in the schema and one transaction
         * holding every row of it, which is the commit cadence the parameter exists to control being
         * removed by setting it.
         *
         * <p>Assumptions: the ceilings are read off the positional layout recorded on the enclosing class -
         * {@code PRM-INFO} at {@code cbl/CBPAUP0C.cbl} L98 to L108 in the shape {@code NN,NNNNN,NNNNN,X} -
         * so the expiry admits two digits and each frequency five characters. They are stated as named
         * constants rather than as literals here because the same widths are what a future card reader
         * would parse by, and a bound that agrees with the layout by coincidence is one a later edit can
         * separate from it.
         *
         * <p>Alternatives Considered: bounding the expiry by a business rule instead - the retention period
         * an authorization is actually held for. Rejected because no such period is stated anywhere in the
         * reference material, so any figure would be this migration's invention presented as a
         * transcription; the field width is a fact about the reference and is the defensible bound. The
         * consequence is recorded plainly: a deployment wanting a longer expiry than the card can express
         * has to widen this bound deliberately, which is the intended way to change it.
         *
         * @param businessDate the date elapsed days are measured to; must not be {@code null}
         * @param expiryDays the inclusive expiry threshold in days; must be positive and at most
         *     {@link #MAX_EXPIRY_DAYS}
         * @param checkpointFrequency the number of summaries per committed window; must be positive and at
         *     most {@link #MAX_CARD_FREQUENCY}
         * @param progressLogFrequency the number of committed windows between progress reports; must be
         *     positive and at most {@link #MAX_CARD_FREQUENCY}
         * @throws NullPointerException if {@code businessDate} is {@code null}
         * @throws IllegalArgumentException if {@code expiryDays}, {@code checkpointFrequency} or
         *     {@code progressLogFrequency} is outside the range its card position can express
         */
        public PurgeParameters {
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            requireInRange(expiryDays, "expiryDays", MAX_EXPIRY_DAYS);
            requireInRange(checkpointFrequency, "checkpointFrequency", MAX_CARD_FREQUENCY);
            requireInRange(progressLogFrequency, "progressLogFrequency", MAX_CARD_FREQUENCY);
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
