package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.dto.FraudMarkResponse;
import com.carddemo.authorization.mapper.AuthFraudMapper;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.AuthFraudRepository;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sets the fraud state of one pending authorization, writing the detail row and the fraud row together.
 *
 * <p><strong>Purpose.</strong> Carry across the two reference programs that share this action:
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} paragraphs {@code MARK-AUTH-FRAUD} at L230
 * to L266 and {@code UPDATE-AUTH-DETAILS} at L520 to L552, and
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} {@code MAIN-PARA} at L89 to L220 and
 * {@code FRAUD-UPDATE} at L221 to L244. Every citation below is relative to that tree, which is
 * reference material this migration reads and never modifies; target-side paths are named in full.
 *
 * <h2>Divergence D-6: the distributed commit is removed, not emulated</h2>
 *
 * <p>Refactoring Rationale: this is ONE local transaction where the reference system needed a genuine
 * two-phase commit across two resource managers, and a reader who knows the reference tree will come
 * here looking for the second resource manager, so what became of it is stated rather than left to be
 * inferred. The split is exact and each half is provable by census: {@code cbl/COPAUS1C.cbl} owns the
 * hierarchical half and contains NO {@code EXEC SQL} anywhere in its 604 lines, replacing the segment
 * at L525 to L528; {@code cbl/COPAUS2C.cbl} owns the relational half and contains NO {@code EXEC DLI}
 * and no program communication block anywhere in its 244 lines, inserting the Db2 row at L141 and L142
 * and updating it at L223. {@code csd/CRDDEMO2.csd} then binds the two into one unit of work --
 * L69 to L74 define the Db2 entry and L75 to L79 attach it to transaction {@code CPVD} -- and the lone
 * commit point for BOTH writes is the single syncpoint in the caller at L557 and L558, with its
 * rollback at L565 and L567. Both rows now live in one PostgreSQL schema, so the distributed
 * transaction is ELIMINATED rather than emulated: there is no coordinator, no two-phase protocol and
 * no equivalent construct anywhere in the target. This is documented divergence D-6 in
 * {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Refactoring Rationale: the dated evidence in the resource definitions is what makes the removal a
 * restoration of the original shape rather than a redesign, so it is recorded here where the collapse
 * happens. Four {@code DEFINETIME} stamps in {@code csd/CRDDEMO2.csd} order the topology: the Db2 entry
 * {@code AWS01PLN} is stamped {@code 22/11/27 19:11:50} at L73, {@code COPAUS1C} is stamped
 * {@code 23/03/13 15:32:12} at L30, {@code COPAUS2C} is stamped {@code 23/03/24 11:15:11} at L37, and
 * the {@code DB2TRAN(CPVDTRAN)} that attaches Db2 to transaction {@code CPVD} is stamped
 * {@code 23/03/24 11:16:32} at L77 -- the same day as the program it serves and EIGHTY-ONE SECONDS
 * after it. Before {@code COPAUS2C} existed, {@code CPVD} therefore had no Db2 attachment at all and
 * was purely hierarchical. The second resource manager is an incremental accretion onto a
 * single-manager transaction, not an original design, and one schema returns the action to the one
 * unit of work it began as.
 *
 * <p>Assumptions: the transaction machinery this class depends on is declared once, in
 * {@code config/DataSourceConfig.java}, and this class introduces NONE of its own. That configuration
 * publishes a single non-distributed {@code DataSource} and leaves Spring Boot's default
 * {@code JpaTransactionManager} in place, which is what already expresses D-6 at the configuration
 * layer -- no XA data source, no JTA transaction manager and no distributed-transaction coordinator
 * exists to enlist a second participant in. Its own contract records the rejected alternative at that
 * layer. The single {@link Transactional} annotation below is the whole of this class's transaction
 * declaration; a manager or a nested boundary declared here would be a second place the commit scope
 * could differ from the one the configuration publishes.
 *
 * <p>Assumptions: the reference rollback is a TRANSACTION-MANAGER property and not only a program
 * statement, which is why exception propagation is a faithful translation of it rather than a
 * convenience. {@code csd/CRDDEMO2.csd} L45 declares {@code ACTION(BACKOUT) WAIT(YES)} on transaction
 * {@code CPVD}, so an abending task backs its updates out instead of committing them, and L71 declares
 * {@code DROLLBACK(YES)} on the Db2 entry, so a deadlock or timeout victim is rolled back rather than
 * being handed back a code to interpret. Both are the monitor's half of the same semantic that letting
 * an exception leave the annotated method below carries, which is the mapping rule T5 states for
 * {@code SYNCPOINT ROLLBACK}.
 *
 * <p>Trade-offs: the reference concurrency ceiling is deliberately NOT inherited, and what that costs
 * is named as well as what it buys. Three settings compose that ceiling: {@code csd/CRDDEMO2.csd} L72
 * declares {@code THREADLIMIT(1)} with {@code THREADWAIT(YES)}, so tasks QUEUE for one Db2 thread
 * rather than running beside one another; L43 declares {@code DTIMOUT(NO)} on {@code CPVD}, as do L53
 * and L63 on its two sibling transactions, so a queued task waits INDEFINITELY and the reference wait
 * is unbounded; and {@code CONCURRENCY(QUASIRENT)} on all four programs at L14, L21, L28 and L35
 * reinforces it, since such a program is serialised on the monitor's main task control block. The
 * target answers concurrent marks from a connection pool instead, under the explicit connect,
 * read/socket, statement and lock timeouts {@code config/DataSourceConfig.java} sets. What is given up
 * is real: a bounded timeout can fail a request that the reference would eventually have completed
 * after an arbitrarily long wait, so a caller here can see a timeout where the reference showed only
 * slowness. That is accepted because an unbounded wait on an administrative screen consumes a request
 * thread with no ceiling and gives an operator no signal to act on, whereas a bounded failure is
 * retryable by the caller and observable to the alerting.
 *
 * <h2>Two reference programs, one method</h2>
 *
 * <p>Alternatives Considered: standing the relational half up as a SEPARATE FRAUD MICROSERVICE that
 * this class calls over HTTP, on the reading that two programs should become two deployables. Rejected
 * on a concrete consequence: an HTTP hop puts the two writes in two processes with two connections and
 * two commit scopes, so the second write could commit while the first was rolled back, and recovering
 * atomicity across that boundary needs exactly the distributed coordination -- or the compensating
 * reversal that stands in for it -- that D-6 exists to remove. It would reintroduce the second
 * transactional participant across a network boundary, which is a worse position than the reference
 * tree's, since the reference at least had a coordinator. Rule T5 also settles the mapping directly:
 * {@code EXEC CICS LINK} becomes an IN-PROCESS METHOD CALL, and only {@code XCTL} becomes a transfer.
 * The reference call is a {@code LINK}, issued at {@code cbl/COPAUS1C.cbl} L248 to L252, so both writes
 * belong in one method here.
 *
 * <p>Assumptions: the linked program is reached ONLY by that call and was never independently
 * startable, which is what makes folding it into this method a faithful reading rather than a
 * flattening of two entry points into one. Three properties of the reference definitions say so
 * together: {@code csd/CRDDEMO2.csd} L36 gives {@code COPAUS2C} the transaction identifier
 * {@code CPVD} as its own primary transaction only, L39 and L40 define transaction {@code CPVD} with
 * {@code PROGRAM(COPAUS1C)} and name {@code COPAUS2C} nowhere, and the program contains no
 * {@code EXEC CICS SYNCPOINT} at all -- its only three monitor calls are the two clock requests at L91
 * and L95 and the return at L218 -- so it returns UNCOMMITTED and both of its writes hang on the
 * caller's syncpoint.
 *
 * <p>Assumptions: the reference communication area is 272 bytes and its shape is what the two
 * parameters below stand for. {@code cbl/COPAUS2C.cbl} declares it at L73 to L86 as an account
 * identifier of 11 digits, a customer identifier of 9, the 200-byte authorization record injected by
 * {@code COPY CIPAUDTY.} at L78 under a group item at level {@code 02}, then the one-character
 * requested action, the one-character outcome and a 50-character sentence. The first three members are
 * inputs the caller had already read and the last three are the outcome; the target passes the row it
 * re-read and the requested action, and returns the outcome, so no byte of that area travels as a
 * structure and no client composes it.
 *
 * <p>Refactoring Rationale: the write sets the state the request NAMES where the reference program
 * toggles the state it finds -- {@code cbl/COPAUS1C.cbl} L234 re-reads the segment and L236 to L242
 * inverts it, taking no action argument at all. That is documented divergence
 * D-AUTH-FRAUD-TARGET-STATE, and the reason is the transport: a request carrying a target state is
 * idempotent under the retries a network introduces, whereas a toggle reversed by a retry removes a
 * fraud tag the operator asked for and reports success either way.
 *
 * <p>Assumptions: TWO closed one-character domains use the SAME two letters for different things, and
 * conflating them would write a correct-looking value into the wrong field. {@code WS-FRD-ACTION} is
 * the REQUESTED ACTION -- the state to end in -- declared with {@code WS-REPORT-FRAUD} for {@code 'F'}
 * and {@code WS-REMOVE-FRAUD} for {@code 'R'} at {@code cbl/COPAUS2C.cbl} L80 to L82, and it is what
 * {@link FraudMarkRequest#action()} carries. {@code PA-AUTH-FRAUD} is the STORED STATE on the
 * authorization itself, declared at {@code cpy/CIPAUDTY.cpy} L50 with {@code PA-FRAUD-CONFIRMED} for
 * {@code 'F'} at L51 and {@code PA-FRAUD-REMOVED} for {@code 'R'} at L52. The reference program joins
 * them by moving one into the other at {@code cbl/COPAUS2C.cbl} L137, which is only sound because the
 * two domains coincide character for character; they are nonetheless different fields with different
 * lifetimes, and a third domain compounds the hazard -- {@code 'F'} on
 * {@link FraudMarkResponse#updateStatus()} means FAILED and has nothing to do with either.
 *
 * <p>Assumptions: the fraud action produces TWO SEPARATE message families and only one of them is this
 * service's. The 50-character sentence the linked program reports -- {@code 'ADD SUCCESS'} at
 * {@code cbl/COPAUS2C.cbl} L201 and {@code 'UPDT SUCCESS'} at L232 -- is chosen by which write path
 * ran, and it is what {@link FraudMarkResponse} carries. The confirmation the DETAIL SCREEN displays is
 * a different pair chosen by the state now reached: {@code cbl/COPAUS1C.cbl} L534 tests
 * {@code PA-FRAUD-REMOVED} and selects {@code 'AUTH FRAUD REMOVED...'} at L535, otherwise
 * {@code 'AUTH MARKED FRAUD...'} at L537. Those two are the screen's own working field and are carried
 * by {@code ui/src/messages/messages.ts} and described in
 * {@code src/main/resources/openapi/authorization-api.yaml}; neither is a member of this service's
 * response. Rule T8 requires all four to survive character for character, and they do -- in the two
 * places that own them. A reader looking here for the state-selected pair should find that boundary
 * rather than a third copy of the strings.
 *
 * <h2>An administrative operation, and what the reference tree checked</h2>
 *
 * <p>Assumptions: this operation REQUIRES the administrative group authority, and this service performs
 * no authority check itself. The requirement is declared once, in {@code config/SecurityConfig.java},
 * which owns the route matrix and matches {@code FRAUD_PATH_PATTERN} against the administrative
 * authority so that a token carrying only the user group is refused. A second check here would be a
 * second place the rule could differ from the published contract, and a reader could no longer tell
 * which of the two decided a refusal. A confirmation step in the interface is not part of this: the
 * reference detail screen marks fraud on a function key at {@code cbl/COPAUS1C.cbl} L187 and L188 with
 * no re-keying, and a client-side confirmation is a guard against a slip, never an authorization.
 *
 * <p>Assumptions: that authority requirement is an ADDITION and is not carried across from anything,
 * so it is described as one rather than as a port. The reference tree performs NO resource-level and no
 * command-level security checking anywhere in this application: {@code csd/CRDDEMO2.csd} L46 declares
 * {@code RESSEC(NO) CMDSEC(NO)} on transaction {@code CPVD}, and L56 and L66 declare the same pair on
 * its two siblings, so reaching the transaction was the whole of the check. Describing the group claim
 * as preserved behaviour would misstate what the reference did; describing it as added states what it
 * is, and {@code docs/adr/ADR-008-security-and-identity.md} records the substitution.
 *
 * <p>Assumptions: the same definitions leave the primary account number visible to diagnostic tracing.
 * {@code csd/CRDDEMO2.csd} L45 declares {@code CONFDATA(NO)} on {@code CPVD}, which is the setting that
 * would otherwise suppress confidential data in the monitor's trace, so an account number moving
 * through this action could appear in a trace entry. The masking the target applies therefore answers
 * an OBSERVABLE exposure rather than a hypothetical one. That masking is performed in
 * {@code com.carddemo.authorization.mapper} and nowhere else, and this class deliberately does not
 * mask: it holds the whole authorization because both writes need columns the masked projection has
 * removed, and a second masking site would be a second definition of what "masked" means.
 *
 * <p>Assumptions: no mutable instance state exists on this class, and every value a request needs lives
 * on the stack of the method serving it. {@code csd/CRDDEMO2.csd} L42 declares {@code ISOLATE(YES)} on
 * {@code CPVD}, as do L52 and L62 on its siblings, so each reference task ran in its own storage
 * subspace and could not reach another task's working storage. A field holding per-request data on this
 * singleton would be shared by every concurrent request and would lose exactly that property, so the
 * four members below are the injected collaborators and nothing else.
 *
 * <h2>Retry discipline</h2>
 *
 * <p>Assumptions: no retry is declared on this operation, and the reference tree is what bounds how
 * narrow one would have to be if a later change added it. {@code csd/CRDDEMO2.csd} L44 declares
 * {@code RESTART(NO)} on {@code CPVD}, as do L54 and L64 on its siblings, so no automatic transaction
 * restart existed and every retry has to be explicit. The reference programs then name the only
 * statuses they treat as retryable: {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} at
 * {@code cbl/COPAUS1C.cbl} L88 and identically at {@code cbl/COPAUS0C.cbl} L87 -- three TRANSIENT
 * INFRASTRUCTURE statuses. Two of them carry their own condition names in the same declaration and so
 * name themselves: {@code DATABASE-UNAVAILABLE} for {@code 'BA'} at L85 and
 * {@code COULD-NOT-SCHEDULE-PSB} for {@code 'TE'} at L87; {@code 'FH'} appears only inside the retry
 * list and is given no condition name of its own. The statuses the same declaration deliberately does
 * NOT admit to that list are the four describing the DATA -- {@code SEGMENT-NOT-FOUND},
 * {@code DUPLICATE-SEGMENT-FOUND}, {@code WRONG-PARENTAGE} and {@code END-OF-DB} at L81 to L84.
 * Retrying any of those four repeats a request whose answer will not change, and an included list here
 * would have to be equally narrow.
 *
 * <p>Trade-offs: retrying THIS method is additionally the wrong granularity even for a transient
 * failure, which is the concrete reason none is declared rather than a preference. The method is one
 * transaction spanning a re-read, a probe and two writes, so an attempt that failed part way has
 * already been rolled back and a retry re-runs the whole sequence including the probe -- which then
 * observes the state the failed attempt did not leave, so the created-versus-replaced answer a caller
 * receives can differ between attempts of one logical request. The target instead lets the failure
 * reach the caller, which the request's named target state makes safe to repeat: a repeat asserts the
 * same end state rather than inverting the current one.
 *
 * <p>Alternatives Considered: adding an external resilience library or a circuit breaker to obtain
 * that retry. Rejected because Spring Framework 7, which arrives inside the Spring Boot parent this
 * module inherits, moved retry into the framework core, so the dependency would buy nothing and
 * {@code io.github.resilience4j:resilience4j-spring-boot3} publishes against Spring Boot 3.x in any
 * case; a breaker would additionally add a failure mode -- an open circuit refusing a request the
 * database would have served -- to a path whose only remote party is the one database this transaction
 * is already open against. The decision is recorded in {@code docs/adr/ADR-002-compute-platform.md},
 * this module's own POM records the non-adoption, and
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * asserts that no class imports the library, so the absence is a failing test rather than a convention.
 * Two details of the core API are easy to get wrong and are written down for whoever does add a retry
 * somewhere it fits: the bounding attribute
 * is {@code maxRetries} and NOT {@code maxAttempts}, so the total number of attempts is one plus its
 * value and defaults to three, and the enabling annotation is {@code @EnableResilientMethods} and NOT
 * the older {@code @EnableRetry}.
 */
@Service
public class FraudMarkingService {

    /**
     * The pattern the reference system formats the detail row's fraud report date with.
     *
     * <p>Assumptions: month first with solidus separators and a two-digit year, which is what
     * {@code EXEC CICS FORMATTIME ... MMDDYY(WS-CUR-DATE) DATESEP} at {@code cbl/COPAUS2C.cbl} L95 to
     * L100 produces, and its L101 moves the result straight into the segment field. The eight characters
     * are therefore NOT ISO-ordered, which is why that column stores characters rather than a date.</p>
     */
    private static final DateTimeFormatter SEGMENT_REPORT_DATE =
            DateTimeFormatter.ofPattern("MM/dd/yy");

    /**
     * The authorization rows, re-read by key and updated in place.
     */
    private final PendingAuthDetailRepository details;

    /**
     * The parent-summary repository, read for the one fraud-row column the detail segment does not carry.
     *
     * <p>Refactoring Rationale: the customer identifier used to arrive in the request body. The body now
     * carries the fraud action alone, because the sealed path selector is the whole address of the row and
     * a body that repeated the address let a caller name one row and address another. The identifier the
     * fraud row still needs is therefore read from the authorization's own parent, which is the only place
     * this context holds it -- {@code PA-CUSTOMER-ID} sits on the summary segment at
     * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} and not on the detail child.</p>
     */
    private final PendingAuthSummaryRepository summaries;

    /**
     * The fraud rows, probed by key and then inserted or replaced.
     */
    private final AuthFraudRepository fraudRows;

    /**
     * The only route from a sealed selector to a persistent key.
     */
    private final PendingAuthViewMapper mapper;

    /**
     * Builds the service over its three repositories and the view mapper.
     *
     * <p>Refactoring Rationale: a {@link java.time.Clock} was injected here and is not any more. It
     * supplied the fraud report date, which the reference system takes from the DATABASE server rather
     * than from the application -- so the clock was answering a question it was the wrong source for,
     * and the date now comes from {@link AuthFraudRepository#currentDate()}. The dependency is removed
     * rather than left unused, because an injected clock is exactly what a later reader would reach for
     * when a second date was needed, which is how the divergence arose the first time.</p>
     *
     * @param details the authorization repository; must not be {@code null}
     * @param summaries the parent-summary repository the customer identifier is read from; must not be
     *     {@code null}
     * @param fraudRows the fraud-row repository, which also supplies the database's report date; must
     *     not be {@code null}
     * @param mapper the view mapper that redeems the path selector; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public FraudMarkingService(PendingAuthDetailRepository details,
            PendingAuthSummaryRepository summaries, AuthFraudRepository fraudRows,
            PendingAuthViewMapper mapper) {
        this.details = Objects.requireNonNull(details, "details must not be null");
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.fraudRows = Objects.requireNonNull(fraudRows, "fraudRows must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Sets the fraud state the request names on the authorization the path selector names.
     *
     * <p><strong>Purpose.</strong> This is the transcription of paragraph {@code MARK-AUTH-FRAUD} of
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} at L230 to L266, the paragraph the
     * reference detail screen reaches from its function-key branch at L187 and L188. Its two collaborating
     * paragraphs -- the linked program's own two, and {@code UPDATE-AUTH-DETAILS} -- are transcribed by the
     * two methods this one calls, so a reviewer can move between paragraph and method one pair at a
     * time.</p>
     *
     * <p>Assumptions: the sealed path selector is the SOLE naming of the row, and the body carries the
     * fraud action and nothing else. The selector is sealed and bound to the caller, so it cannot have
     * been forged; a body that repeated the account, the customer and the two clock components would be
     * plain JSON a client composes, and admitting it would create a second, weaker address for the same
     * row -- one a caller could disagree with the selector about. Alternatives Considered: keeping the
     * repeated members and refusing the request when the two namings disagree. Rejected because the
     * disagreement it detects can only exist if the second naming exists, so withdrawing the naming
     * removes the failure mode rather than reporting it.
     *
     * <p>Assumptions: the identifiers the fraud row records are read from the row being marked and from
     * its parent summary rather than from the request. The account identifier is the leading component of
     * the redeemed key, and the customer identifier is a summary-segment column, so neither depends on
     * anything the caller supplied.
     *
     * @param selector the sealed selector from the request path; must not be {@code null}
     * @param request the validated request body, carrying the fraud action alone; must not be
     *     {@code null}
     * @param subject the authenticated principal the selector was issued to; must not be {@code null}
     * @return the outcome, carrying the success body and whether the fraud row was created; never
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws PendingAuthViewMapper.InvalidSelectorException if the selector cannot be redeemed
     * @throws NoSuchElementException if the selector redeems to a key that names no row, or the row's
     *     account has no parent summary to read the customer identifier from
     * @throws IllegalArgumentException if the requested action lies outside the closed two-character
     *     domain, which the mapper refuses on either write arm before touching a row; a request arriving
     *     through the published route cannot carry such a value, because the request type constrains the
     *     member, so this reports a caller that bypassed that constraint
     * @throws IllegalStateException if the named row carries an original date the composed fraud key
     *     cannot be built from
     */
    @Transactional
    public FraudMarkOutcome mark(String selector, FraudMarkRequest request, String subject) {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(subject, "subject must not be null");

        PendingAuthDetailKey key = this.mapper.openKey(selector, subject);

        // WHY : Assumptions: the row is fetched BY COMPOSITE KEY before it is written, and the target has
        //       to do that explicitly because the reference replace has no key on it at all. The reference
        //       paragraph re-reads the segment at cbl/COPAUS1C.cbl L234 through READ-AUTH-RECORD and then
        //       issues EXEC DLI REPL ... SEGMENT (PAUTDTL1) FROM (PENDING-AUTH-DETAILS) at L525 to L528,
        //       which is UNQUALIFIED -- it carries no segment search argument and replaces whatever the
        //       program's CURRENT POSITION in the hierarchy happens to be, a position established by that
        //       earlier retrieval. Nothing in a relational target holds a cursor across statements that
        //       way, so the position has to become an explicit key: this findById is the by-composite-key
        //       path PendingAuthDetailRepository declares for exactly this purpose, and the row it returns
        //       is the one both writes below then operate on. Reproducing the reference sequence without
        //       the fetch would leave the update naming no row.
        // WHY : Assumptions: no lock mode is requested, because the reference programs hold nothing
        //       between a read and the write that follows it. Every retrieval they use is a NON-HOLD form:
        //       cpy/IMSFUNCS.cpy declares the three get-hold codes at L19, L21 and L23 and no program in
        //       the reference tree passes any of them to a retrieval. Requesting a pessimistic lock here
        //       would therefore add lock-wait queueing and deadlock-victim rollback to a path that has
        //       neither today, which is new behaviour rather than preserved behaviour, and the package
        //       charter records it as the rejected alternative on that boundary.
        // WHY : Alternatives Considered: serialising two concurrent marks of one authorization on this read,
        //       so that the probe below could never see an absent fraud row twice. Rejected because the
        //       reference system does not prevent that collision either -- it lets the duplicate key fire and
        //       branches on it, testing the insert at cbl/COPAUS2C.cbl L199 and taking PERFORM FRAUD-UPDATE
        //       at L203 and L204 when SQLCODE is -803, with the update itself at L221 to L229. The fraud
        //       table's primary key is therefore the arbiter of a concurrent duplicate here as well; the
        //       consequence accepted is that of two simultaneous marks of the SAME authorization one is
        //       rejected rather than both being applied, and because both would assert the same fraud state
        //       the committed state of the row is the same either way.
        // WHY : Assumptions: that -803 branch is also what makes the pair (card number, composed timestamp)
        //       a REAL uniqueness contract rather than an assumption this migration imposed. -803 is the
        //       duplicate-key condition, so the reference program can only have reached its update path by
        //       having a unique constraint on those two columns refuse the insert -- the constraint has to
        //       exist for the branch to be reachable. That is why the primary key the probe below reads
        //       through is declared over exactly that pair and no wider, and why a conflict on exactly that
        //       pair is the faithful translation of the branch.
        PendingAuthDetail detail = this.details.findById(key)
                .orElseThrow(() -> new NoSuchElementException(
                        "the selector names no pending authorization"));

        // WHY : Trade-offs: the report date is read ONCE and both writes below derive from it, which is
        //       registered as divergence D-AUTH-FRAUD-ONE-CLOCK in
        //       docs/architecture/cobol-to-service-traceability.md. The reference system reads two clocks
        //       for this one action -- the segment copy comes from the transaction monitor at
        //       cbl/COPAUS2C.cbl L91 to L101 and the table column from the database server at L194 and
        //       L225 -- so its two values can name different days across midnight or under unequal local
        //       times. Both values now live in one schema written by one local transaction, so a second
        //       clock read could only introduce that disagreement; the register entry records why the
        //       collapse is accepted and what it costs.
        // WHY : Refactoring Rationale: the date comes from the DATABASE and it used to come from an
        //       injected application clock. AuthFraudMapper.toFraudRow states the requirement in its own
        //       contract -- "the report date as the DATABASE supplies it, never as an application clock
        //       reads it" -- because the reference program dates BOTH of its relational write paths from
        //       the database server, supplying CURRENT DATE positionally in the insert's value list at
        //       cbl/COPAUS2C.cbl L194 and setting it in the update at L225. The two sources are not
        //       interchangeable: a container's clock, its configured zone and the database session's zone
        //       are three independent settings, so a report raised either side of midnight could be dated
        //       a day apart from the value the reference would have written, on exactly the field an
        //       investigator filters by.
        // WHY : Assumptions: reading the date at all is CORRECT here and is not the injected-business-date
        //       divergence that this migration's batch jobs carry, so it must not be "improved" into a job
        //       parameter by a later reader who recognises the pattern. That divergence exists because a
        //       batch job can be rerun for a past business date, so a clock read would silently date the
        //       rerun today. This path is ONLINE and operator-driven: an investigator marks an
        //       authorization at the moment of marking it, so today genuinely IS the business date, which
        //       is precisely why the reference program reads a clock here rather than accepting a date --
        //       its communication area at cbl/COPAUS2C.cbl L73 to L86 has no date member for a caller to
        //       supply one through. Accepting a report date as a parameter would let a caller backdate a
        //       fraud report, which is a capability the reference never offered.
        LocalDate today = this.fraudRows.currentDate();
        String segmentDate = SEGMENT_REPORT_DATE.format(today);

        // WHY : Assumptions: the two rows are written in the reference ORDER -- the fraud row first, then
        //       the segment -- because the reference program only replaces the segment when the fraud write
        //       reported success, testing it at cbl/COPAUS1C.cbl L254, performing the replace at L255 and
        //       taking its rollback path at L258 otherwise. Both writes are inside one transaction here, so
        //       the order no longer decides what survives a failure; it is preserved because a reader
        //       comparing the two systems should find the same sequence, and because a failure of the fraud
        //       write still leaves the segment untouched in memory as well as uncommitted.
        // WHY : Assumptions: BOTH writes are inside the ONE transaction this method declares, and that is
        //       the whole of divergence D-6 at the point where it takes effect. Neither call below opens a
        //       boundary of its own, so an exception escaping either one rolls the other back with it --
        //       which is what the reference obtains only by having a coordinator drive two managers to a
        //       single syncpoint at cbl/COPAUS1C.cbl L557 and L558. There is deliberately no compensating
        //       reversal and no second commit here, because a partially-marked authorization is a state
        //       neither system can produce and adding a reversal would create the very window it exists to
        //       close.
        boolean created = writeFraudRow(detail, key, request, today);
        applyStateToDetail(detail, request.action(), segmentDate);

        // WHY : Assumptions: reaching this point means BOTH writes are staged and the transaction will
        //       commit, and the two outcomes are reported through TWO DIFFERENT CHANNELS rather than one,
        //       because the reference program treats them asymmetrically. Its success arm at
        //       cbl/COPAUS1C.cbl L532 to L538 takes the syncpoint and sets a confirmation, and it does NOT
        //       re-send the screen; only its failure arm does, rolling back at L540 and performing
        //       SEND-AUTHVIEW-SCREEN at L550 after composing the sentence that begins
        //       ' System error while FRAUD Tagging, ROLLBACK||' at L545. The target keeps them distinct:
        //       success RETURNS the outcome below, which the controller publishes as 201 or 200, while a
        //       failure PROPAGATES its exception, which rolls the transaction back and is rendered by the
        //       shared problem shape. Collapsing the two into one response -- returning a body carrying a
        //       failure indicator -- would commit the transaction on the failure path, which is the one
        //       outcome the reference rollback exists to prevent.
        // WHY : Trade-offs: no golden master exists for either arm, and none is claimed. This context's
        //       programs are CICS online programs that the reference suite documents as unable to run
        //       end to end without a CICS runtime, so parity here rests on the copybook and data-definition
        //       contracts plus the transcribed logic above, asserted by this module's own tests. The
        //       reference suite's documented warn-level aggregate result is unrelated to this module.

        // WHY : Refactoring Rationale: the response body is projected by the mapper rather than chosen
        //       here with a conditional over the two factories. The two are equivalent today, and the
        //       reason for the change is that the mapper is where the created-versus-replaced decision is
        //       already documented against the two sentences the reference reports at
        //       cbl/COPAUS2C.cbl L201 and L232 -- so one type now decides both which sentence a caller
        //       reads and which status the contract publishes for it, instead of that pairing living in
        //       one place and being re-derived in another.
        return new FraudMarkOutcome(AuthFraudMapper.markResponse(created), created);
    }

    /**
     * Inserts the fraud row, or replaces the state on the one already there.
     *
     * <p><strong>Purpose.</strong> This is the transcription of the whole linked program: paragraph
     * {@code MAIN-PARA} of {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} at L89 to L220,
     * whose insert path runs to L216, together with paragraph {@code FRAUD-UPDATE} at L221 to L244, which
     * that path branches to on the duplicate-key condition. The two reference paragraphs become the two
     * arms of this one method because they are two halves of one decision, not two operations a caller
     * could choose between.</p>
     *
     * <p>Assumptions: the fraud table has EXACTLY 26 COLUMNS, and which of them a write may touch depends
     * on the arm. The reference insert at {@code cbl/COPAUS2C.cbl} L141 and L142 names all 26 in its
     * column list at L143 to L168, so the create arm supplies a whole row; the reference update at L223
     * names exactly two in its {@code SET} list at L224 and L225, so the replace arm leaves the other 24
     * at the values they were first inserted with. Both column dispositions are documented once on
     * {@link AuthFraudRepository} and are consumed here rather than restated.</p>
     *
     * @param detail the authorization being marked, re-read by key in this transaction; never
     *     {@code null}
     * @param key the row identity the sealed selector redeemed to, whose leading component is the
     *     account the fraud row records; never {@code null}
     * @param request the validated request body; never {@code null}
     * @param today the server date both report dates are taken from; never {@code null}
     * @return {@code true} when a row was created, {@code false} when an existing row was replaced
     * @throws NoSuchElementException if the authorization's account has no parent summary, so the
     *     customer identifier the fraud row records cannot be read; reached only on the create arm
     * @throws IllegalStateException if the authorization's original date cannot be composed into the
     *     fraud key's timestamp
     * @throws IllegalArgumentException if the requested action lies outside the closed two-character
     *     domain, which the mapper refuses on the replace arm before touching the row
     */
    private boolean writeFraudRow(PendingAuthDetail detail, PendingAuthDetailKey key,
            FraudMarkRequest request, LocalDate today) {

        // WHY : Refactoring Rationale: the key is composed by the mapper and it used to be composed here.
        //       AuthFraudMapper.fraudRowKey delegates the timestamp half to
        //       PendingAuthDetailMapper.authTimestamp, which is the one transcription of the reference
        //       composition at cbl/COPAUS2C.cbl L103 to L111 -- the acquirer-supplied originating date
        //       sliced year, month then day, and the server-derived time key divided out of its
        //       positional form. This method held a third copy of that arithmetic, with its own century
        //       pivot and its own field moduli, and two copies of a key composition are two rows that can
        //       be addressed: a divergence in either half would not fail, it would read and write a real
        //       but different authorization's fraud row.
        AuthFraudKey fraudKey = AuthFraudMapper.fraudRowKey(detail);
        Optional<AuthFraud> existing = this.fraudRows.findById(fraudKey);

        if (existing.isPresent()) {
            // WHY : Assumptions: only the two columns the reference UPDATE names are touched -- its L222 to
            //       L225 set the indicator and the current date and nothing else -- so the twenty-four-column
            //       snapshot the row took when it was first inserted is deliberately left as it was. A row
            //       replaced here therefore still describes the authorization as it stood at the first
            //       report, which is the property that makes taking the snapshot worth anything.
            // WHY : Refactoring Rationale: the transition goes through the mapper, which applies the same
            //       two columns through the entity's own operation and additionally refuses an action
            //       outside the closed domain before touching the row. Calling the entity directly here
            //       left the create path and the replace path reaching the row through two different
            //       types, so a rule added to one would silently not apply to the other.
            AuthFraudMapper.applyFraudState(existing.get(), request, today);
            return false;
        }

        Long customerId = this.summaries.findByAccountId(key.getAccountId())
                .orElseThrow(() -> new NoSuchElementException(
                        "the authorization's account has no pending-authorization summary, so the fraud"
                                + " row's customer identifier cannot be read"))
                .getCustomerId();

        // WHY : Refactoring Rationale: the row is projected by the mapper, where it used to be built
        //       through the entity's own factory with the account identifier passed in beside the
        //       authorization. The mapper reads that identifier from the authorization's OWN key instead
        //       of accepting it, so the row cannot be written naming an account the authorization does not
        //       belong to -- an argument that was available to be passed wrongly is now not available at
        //       all. The projection also carries the documentation of which twenty-four columns have
        //       exactly one legitimate source, which is the knowledge this call site was silently
        //       depending on.
        this.fraudRows.save(AuthFraudMapper.toFraudRow(detail, request, customerId, today));
        return true;
    }

    /**
     * Applies the requested state and the report date to the authorization row itself.
     *
     * <p><strong>Purpose.</strong> This is the transcription of paragraph {@code UPDATE-AUTH-DETAILS} of
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} at L520 to L552 -- its data half. The
     * paragraph's control half does not survive translation: its replace at L525 to L528, its syncpoint
     * through {@code TAKE-SYNCPOINT} at L533, its rollback through {@code ROLL-BACK} at L540 and its
     * screen re-send at L550 are all carried by the transaction boundary and the response channels
     * described on {@link #mark(String, FraudMarkRequest, String)}, leaving this method the two field
     * moves.</p>
     *
     * <p>Assumptions: the segment carries its OWN copy of the fraud state and report date, at
     * {@code cpy/CIPAUDTY.cpy} L50 and L53, and the reference system writes both -- the fraud program sets
     * them on its copy of the record at {@code cbl/COPAUS2C.cbl} L101 and L137, and the caller moves that
     * copy back over the segment at {@code cbl/COPAUS1C.cbl} L522 before replacing it. The duplication is
     * the baseline's and is reproduced rather than normalised away.</p>
     *
     * @param detail the authorization being marked, re-read by key in this transaction; never
     *     {@code null}
     * @param action the requested state, either the reported or the removed character; never {@code null}
     * @param segmentDate the report date in the segment's own month-first eight-character form; never
     *     {@code null}
     * @throws IllegalStateException if {@code action} is neither published character, which the request
     *     type's own closed domain should already have refused
     */
    private static void applyStateToDetail(PendingAuthDetail detail, String action,
            String segmentDate) {

        // WHY : Assumptions: the domain is checked HERE as well as inside the entity, and the duplication
        //       is deliberate because the two refusals belong in different channels. The entity refuses an
        //       out-of-domain state with an IllegalArgumentException, which the shared advice reports as
        //       caller input -- correct for a loader or a repair path that supplies the state directly.
        //       Reaching this method with such a state means the request type's closed two-character
        //       domain was bypassed, which is a defect in this service and belongs in the 500 channel the
        //       alerting watches rather than being reported to a caller as its own mistake.
        if (!PendingAuthDetail.FRAUD_REPORTED.equals(action)
                && !PendingAuthDetail.FRAUD_REMOVED.equals(action)) {
            throw new IllegalStateException(
                    "fraud action reached the write outside its published two-character domain");
        }

        // WHY : Assumptions: the transition is applied through the ENTITY'S OWN operation rather than by
        //       assigning the two members here, so the state and its report date can only move together.
        //       Either state stamps the date: a removal is a reached state recording when the report was
        //       withdrawn, not a return to the blank never-examined state, which is what
        //       cbl/COPAUS2C.cbl L95-L101 does by formatting the date and moving it in unconditionally,
        //       before either its insert path or its update path is chosen.
        detail.applyFraudMark(action, segmentDate);
    }

    /**
     * What a fraud write did: the body to return, and whether it created the fraud row.
     *
     * <p>Refactoring Rationale: the created flag travels beside the body rather than inside it, because
     * the contract carries the insert-versus-update distinction on the STATUS CODE -- 201 against 200 --
     * and its response schema publishes exactly the two members the reference communication area's
     * response direction declares. A third member would put the document and the returned record out of
     * agreement, and the contract test asserts that member list exactly. A carrier record is how the
     * distinction reaches the controller without reaching the wire.</p>
     *
     * <p>Alternatives Considered: having the controller infer the path from the sentence in the body, the
     * two being verbatim and distinct. Rejected because it would make a user-visible string load-bearing:
     * a future edit to that sentence would silently change the status code, and rule T8 requires those
     * strings to be carried across unchanged for display and nothing else.</p>
     *
     * @param body the success body to return, never {@code null}
     * @param created {@code true} when the fraud row was inserted, {@code false} when it was replaced
     */
    public record FraudMarkOutcome(FraudMarkResponse body, boolean created) {

        /**
         * Refuses an outcome with no body.
         *
         * @param body the success body to return; must not be {@code null}
         * @param created {@code true} when the fraud row was inserted, {@code false} when it was
         *     replaced
         * @throws NullPointerException if {@code body} is {@code null}
         */
        public FraudMarkOutcome {
            Objects.requireNonNull(body, "body is required");
        }
    }
}
