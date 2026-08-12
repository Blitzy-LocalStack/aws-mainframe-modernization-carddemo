package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthListView;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.error.ClientInputException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves one account's authorization summary together with one page of its authorizations.
 *
 * <p><strong>Purpose.</strong> This class carries across the browse behaviour of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl}, the 1032-line online program CICS
 * transaction {@code CPVS} runs, which {@code csd/CRDDEMO2.csd} binds to that program. It reads the
 * per-account summary, reads one page of that account's authorizations newest first, decides whether a
 * further page exists, and hands the whole thing to the mapper that publishes it. It holds no browse
 * position, opens no transaction of its own beyond the read boundary declared below, and formats
 * nothing.
 *
 * <p>Every line citation in this file is relative to {@code app/app-authorization-ims-db2-mq} unless a
 * different root is named. That tree is the specification this migration reads and never modifies, and
 * each reference paragraph below became one named method here so that
 * {@code docs/architecture/cobol-to-service-traceability.md} can cite paragraph-to-method pairs:
 *
 * <ul>
 *   <li>{@code GATHER-DETAILS} L342-L358 and {@code PROCESS-ENTER-KEY} L261-L338 to
 *       {@link #list(Long, String, String, String)}</li>
 *   <li>the summary-absent arm of {@code GATHER-ACCOUNT-DETAILS} L800-L807 to
 *       {@link #unopenedAccountPage(Long, String)}</li>
 *   <li>{@code PROCESS-PAGE-FORWARD} L415-L454 entered with its seeded position at L422 to
 *       {@link #openingPage(PendingAuthSummary, Long, String)}</li>
 *   <li>{@code PROCESS-PF8-KEY} L388-L412 with {@code REPOSITION-AUTHORIZATIONS} L488-L519 to
 *       {@link #forwardPage(PendingAuthSummary, Long, PendingAuthDetailKey, String)}</li>
 *   <li>{@code PROCESS-PF7-KEY} L362-L385 to
 *       {@link #backwardPage(PendingAuthSummary, Long, PendingAuthDetailKey, String)}</li>
 *   <li>the five-row fill loop at L424-L443 and the look-ahead probe at L445-L452 to
 *       {@link #page(PendingAuthSummary, List, String)} and {@link #pageLimit()}</li>
 *   <li>the attention-identifier evaluation L224-L250 to {@link #resolveBackward(String, String)}</li>
 * </ul>
 *
 * <h2>Why the page is positioned by key</h2>
 *
 * <p>Alternatives Considered: two mechanisms can serve this screen, and the reference program uses the
 * one not adopted here. Its mechanism is ANCHOR REPLAY. It retains the key that opened each page in
 * {@code CDEMO-CPVS-PAUKEY-PREV-PG PIC X(08) OCCURS 20 TIMES} at L120, filling one slot per page turn at
 * L436-L441, and it reaches an earlier page by restoring that page's key at L368-L369 and then running
 * the FORWARD path again at L379 -- there is no backward retrieval verb anywhere in the program. Two
 * consequences follow from that structure and both are concrete. The retained array holds twenty slots
 * and each page holds the five rows declared at L126, so navigable history stops at one hundred rows: a
 * caller on page twenty-one has no slot to write into and no key to return to. And because a previous
 * page is reproduced by replaying forward from a retained anchor, every backward turn re-walks the rows
 * between that anchor and the wanted page rather than reading the wanted page alone. The mechanism
 * adopted instead is a KEYSET QUERY: the position travels as a value, so the reader resumes from it
 * directly. Its consequences are the mirror of the above -- history is unbounded because no server-side
 * array holds it, and one page turn is one read of one page, in either direction. What both mechanisms
 * share, and what makes the substitution a structural equivalent rather than an approximation, is
 * covered next.
 *
 * <p>Alternatives Considered: locating the page by counting rows from the start of the ordering, which
 * is what a row-offset clause or a page-number abstraction would do. Rejected because a count from the
 * start is only stable while nothing is inserted or removed ahead of the counted position, and this
 * table is written continuously by the message-driven half of this context and thinned by the expiry
 * sweep. An insert landing ahead of the boundary between two requests shifts every later row by one
 * place, so the caller is either shown a row it has already seen or never shown one at all. A browse
 * that resumes from a VALUE cannot do either, and the reference program cannot do either, so offset
 * paging would introduce a behavioural difference while looking like a convenience. No offset, page
 * number or total-row count appears anywhere in this class for that reason.
 *
 * <p>Assumptions: the paging cursor is an ENCODED, OPAQUE {@code String}. It is never a human-readable
 * date and time and never a row offset. What it stands for is the eight-byte position
 * {@code PA-AUTHORIZATION-KEY} at {@code cpy/CIPAUDTY.cpy} L19, itself a group of exactly two packed
 * items -- {@code PA-AUTH-DATE-9C PIC S9(05) COMP-3} in three bytes at L20 and
 * {@code PA-AUTH-TIME-9C PIC S9(09) COMP-3} in five at L21 -- which together are the eight bytes
 * {@code ims/DBPAUTP0.dbd} L37 declares as the child segment's sequence field. Opacity is load-bearing
 * rather than tidy: this operation is account-scoped, and a caller able to author its own cursor could
 * name a position outside the scope it was granted, so the token is sealed against both the account and
 * the authenticated subject by {@code com.carddemo.authorization.mapper.PendingAuthViewMapper} and is
 * redeemed only there. This class passes cursors through that boundary and never parses one.
 *
 * <p>Assumptions: newest-first ordering makes the forward predicate STRICTLY LESS THAN, and this is
 * stated because the intuitive keyset shape is the wrong one here. The reference key is a nines
 * complement -- the {@code -9C} suffix on both components at {@code cpy/CIPAUDTY.cpy} L20 and L21 -- so
 * ascending traversal of the reference sequence field IS descending chronological order. The migrated
 * columns hold DECODED values, so the same order has to be asked for explicitly, and
 * {@code PendingAuthDetailRepository} declares it: {@code order by auth_date desc, auth_time desc} with
 * a row-pair comparison below the cursor. A greater-than comparison under an ascending order compiles,
 * runs and returns real rows in a plausible order while paging progressively further BACK through
 * history, and no schema constraint would contradict it. This class therefore relies on that
 * repository's declared direction and inverts nothing.
 *
 * <h2>Ownership boundaries this class observes</h2>
 *
 * <p>Assumptions: {@code pending_auth_summary} needs NO secondary index, and the absence is recorded
 * affirmatively so that a later reader does not supply one. The summary is reached only by its own
 * primary key, which {@code src/main/resources/db/migration/V1__authorization.sql} declares as
 * {@code pk_pending_auth_summary PRIMARY KEY (account_id)}, and no query in this class orders or filters
 * that table by anything else. The one directional index this context owns is
 * {@code (card_num ASC, auth_ts DESC)} on the fraud table, which is a different table reached through a
 * different interface. An index over {@code account_id} would be a second tree over the column the
 * primary key already covers, maintained on every write and answering no query the primary key does not
 * already answer.
 *
 * <p>Refactoring Rationale: the reference program performs THREE cross-context reads, and this class now
 * accounts for all three rather than declining all three. {@code GETCUSTDATA-BYCUST} at L920 is
 * PERFORMED, through {@link AccountContextClient#customerDisplay(long)}: the four fields it supplies --
 * the customer name, two address lines and a telephone number -- are declared by the record this
 * operation publishes into, so omitting them was a functional-parity gap and not the design choice an
 * earlier revision of this paragraph described it as. {@code GETACCTDATA-BYACCT} at L869 is NOT performed,
 * because the two figures the screen takes from it -- the credit limit and the cash credit limit -- are
 * already mirrored onto this context's own summary row by
 * {@link com.carddemo.authorization.domain.PendingAuthSummary#refreshLimits} on every authorization, so a
 * read would fetch what this context already holds. {@code GETCARDXREF-BYACCT} at L818 is NOT performed,
 * because the screen displays nothing from it: the reference reads it only to reach the customer
 * identifier, which this context's own segment carries.
 *
 * <p>Assumptions: the customer record is still NOT reproduced as a table here. The dependency is on the
 * seam interface, so this context declares no entity for it and imports no other service's {@code domain}
 * package, which {@code common-lib}'s architecture test asserts at build time. The seam also narrows what
 * crosses it to the four fields the screen renders -- the customer master's national identifier,
 * government-issued identifier and credit score are not among them and never enter this process.
 *
 * <p>Assumptions: no retry is declared on this class, and the reference program is what bounds the
 * decision rather than an omission. Its retryable set is exactly three transient infrastructure
 * statuses, {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} at L87, and it pointedly excludes the
 * outcomes declared beside it at L80 to L83 -- segment-not-found, duplicate-segment, wrong-parentage and
 * end-of-database -- because retrying any of those repeats a query whose answer will not change. The
 * migrated equivalents of those four are an empty {@code Optional}, a constraint violation, a
 * foreign-key violation and an empty list, none of which is an error here at all, so there is no
 * narrower {@code includes} list left to declare: this operation reads, and a transient connection
 * failure surfaces as the framework's data-access exception. Should retry ever be added to a method in
 * this context, two API facts govern it and are easy to get wrong: the attribute is {@code maxRetries},
 * NOT {@code maxAttempts}, and total attempts are one plus its value with a default of three; and the
 * enabling annotation is {@code @EnableResilientMethods}, NOT {@code @EnableRetry}.
 *
 * <p>Alternatives Considered: adopting an external resilience library and a circuit breaker for this
 * context. Rejected and recorded in {@code docs/adr/ADR-002-compute-platform.md}: retry now lives in the
 * framework core that the parent already brings in, so a library would add a dependency for a capability
 * already present, and a breaker protects a caller from a slow remote dependency where this method has
 * none -- it reads two tables in one database over a pooled local connection. A breaker there would add
 * a failure mode, opening under database latency and refusing reads the database was still answering,
 * without removing one.
 *
 * <p>Assumptions: the transaction boundary belongs to THIS class and is read-only, which is the
 * package charter's ruling rather than a choice made here; the repositories declare no boundary and no
 * lock mode. Nothing on this path locks a row. The reference program's own commit for this screen is the
 * {@code EXEC CICS SYNCPOINT} at L686, taken as the screen is sent, and the analogue of that bracket is
 * the service method boundary. Money crosses this class only as {@code BigDecimal} carried on the
 * entities and converted by the mapper; no binary floating-point type appears here.
 */
@Service
public class PendingAuthSummaryService {

    /**
     * The number of authorizations one page carries.
     *
     * <p>Assumptions: five, and the figure is the reference screen's rather than a tuning choice. Three
     * independent declarations agree on it -- the row-key table {@code OCCURS 5 TIMES} at L126, the fill
     * loop bounded {@code UNTIL WS-IDX > 5} at L424, and the clearing loop bounded the same way at L611
     * -- and the screen itself has five row positions selected by the five-way evaluation at L287-L305.
     * The published contract carries no page-size parameter, so a caller cannot ask for another size and
     * this constant is the whole of the decision.</p>
     */
    public static final int PAGE_SIZE = 5;

    /**
     * The paging direction that reads keys after the cursor, which is the contract's default.
     *
     * <p>Assumptions: "after" means OLDER, because this operation returns authorizations newest first.
     * The reference program reaches this direction from the forward attention identifier tested at
     * L242.</p>
     */
    public static final String DIRECTION_NEXT = "next";

    /**
     * The paging direction that reads keys before the cursor.
     *
     * <p>Assumptions: this is the migrated form of the reference backward move, reached from the
     * attention identifier tested at L239 and served by {@code PROCESS-PF7-KEY} at L362-L385.</p>
     */
    public static final String DIRECTION_PREVIOUS = "previous";

    /**
     * The stable token operational tooling matches a refused paging request on.
     */
    public static final String PAGING_REFUSAL_CODE = "AUTH_PAGING_REFUSED";

    /**
     * The contract field name a refused paging direction is keyed by.
     *
     * <p>Assumptions: {@code direction} is one of the per-field keys the shared 400 response of
     * {@code src/main/resources/openapi/authorization-api.yaml} declares its handlers can emit.</p>
     */
    public static final String DIRECTION_FIELD = "direction";

    /**
     * The look-ahead probe reads exactly one row beyond the page.
     *
     * <p>Assumptions: one, because the reference program issues exactly one further retrieval purely as
     * a probe at L445-L452 and sets its next-page indicator from whether that retrieval succeeded.
     * Reading more would answer a question nothing asks.</p>
     */
    private static final int PROBE_ROWS = 1;

    /**
     * The customer identifier published for an account this context has no summary row for.
     *
     * <p>Assumptions: zero, and it is a named constant because it is a contract obligation rather than a
     * value with meaning. The reference screen sources its customer identifier from the cross-context
     * customer read at L920, which this context does not perform, so with no summary row there is no
     * customer identifier available to it at all. The published summary block requires nine digits, and
     * zero-filling is the same treatment the reference program applies to every other position it cannot
     * source from an absent segment when it moves zero into all six of them at L800-L807.</p>
     */
    private static final long UNKNOWN_CUSTOMER_ID = 0L;

    /**
     * The per-account summary rows, which are the parent of every authorization this service pages.
     */
    private final PendingAuthSummaryRepository summaries;

    /**
     * The authorization rows themselves, read one page plus one probe row at a time.
     */
    private final PendingAuthDetailRepository details;

    /**
     * The only route from persistent rows to the bodies this context publishes.
     */
    private final PendingAuthViewMapper mapper;

    /**
     * The seam the four customer display fields are read through.
     *
     * <p>Refactoring Rationale: this collaborator is added because the screen was publishing the segment's
     * own identifiers and totals and nothing else, while the record it publishes into declares a customer
     * name, two address lines and a telephone number that the reference composes from
     * {@code GETCUSTDATA-BYCUST} at {@code cbl/COPAUS0C.cbl} L920. Assumptions: the dependency is on the
     * INTERFACE and not on the account context's entities, so this context still declares no entity for
     * the customer record and imports no other service's {@code domain} package -- which is what
     * {@code common-lib}'s architecture test asserts at build time.</p>
     */
    private final AccountContextClient accounts;

    /**
     * Builds the service over its two repositories and the view mapper.
     *
     * <p>Assumptions: the collaborators are injected through the constructor rather than assigned by the
     * container into fields, which is what lets this class be unit-tested with no database and no
     * application context at all.</p>
     *
     * @param summaries the per-account summary repository; must not be {@code null}
     * @param details the authorization repository; must not be {@code null}
     * @param mapper the view mapper that seals row selectors and masks card numbers; must not be
     *     {@code null}
     * @param accounts the account-context seam the four customer display fields are read through; must
     *     not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public PendingAuthSummaryService(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details, PendingAuthViewMapper mapper,
            AccountContextClient accounts) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.details = Objects.requireNonNull(details, "details must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
    }

    /**
     * Returns one account's authorization summary together with one page of its authorizations.
     *
     * <p>Purpose: this is the whole of the summary screen's read behaviour, standing for
     * {@code GATHER-DETAILS} at L342-L358 and the validated entry it is performed from at L261-L338. It
     * reads the summary, then reads one page of that account's authorizations in the direction asked
     * for.</p>
     *
     * <p>Assumptions: an account with NO summary row is a normal outcome and never an error, and the
     * reference program settles this explicitly in two places. Its keyed retrieval evaluates exactly two
     * defined outcomes and one error arm at L980-L996: a found indicator at L981-L982, a NOT-FOUND
     * indicator at L983-L984, and only the residual arm treats the status as a system error -- there is
     * no end-of-database arm, because a retrieval qualified on the root's own unique key either finds its
     * row or does not. Its caller then renders that absence rather than reporting it, moving zero into
     * all six aggregate positions at L800-L807 and skipping the browse entirely at L354-L356 so the five
     * row positions stay as {@code INITIALIZE-AUTH-DATA} left them at L608-L662. The alternative was to
     * raise a not-found condition from here and let the shared error handler answer it; its concrete
     * consequence is that a request naming an account with no pending authorizations would be answered
     * with an error status where the reference program answers with a zeroed summary and an empty list,
     * which is a difference a client can see and act on.</p>
     *
     * <p>Assumptions: the direction is meaningful only alongside a cursor, and the two arrive together or
     * not at all. A direction with no cursor is refused rather than treated as an opening page, because a
     * caller that sent one and not the other has made a mistake in exactly one of the two, and answering
     * the opening page would hide it.</p>
     *
     * @param accountId the account whose summary and authorizations are wanted; must not be {@code null}
     * @param cursor the sealed paging position to continue from, or {@code null} for the opening page
     * @param direction {@link #DIRECTION_NEXT} or {@link #DIRECTION_PREVIOUS}, or {@code null} to default
     *     to next; meaningful only when {@code cursor} is present
     * @param subject the authenticated principal the page's boundary tokens are sealed against; must not
     *     be {@code null}, because a cursor bound to no subject is redeemable by every other authorized
     *     operator
     * @return the summary block, one page of rows newest first, and the boundary sentence when the
     *     request was a paging move that had already reached a boundary; never {@code null}
     * @throws NullPointerException if {@code accountId} or {@code subject} is {@code null}
     * @throws ClientInputException if {@code direction} is supplied without a {@code cursor}, or names
     *     neither published value
     * @throws PendingAuthViewMapper.InvalidSelectorException if {@code cursor} cannot be redeemed against
     *     this account and this subject
     */

    @Transactional(readOnly = true)
    public PendingAuthListView list(Long accountId, String cursor, String direction,
            String subject) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(subject, "subject must not be null");

        // WHY : Trade-offs: the direction is resolved BEFORE either read, so a malformed request costs no
        //       query at all. The compromise is that a caller sending both a bad direction and an unknown
        //       account learns about the direction only; naming both would mean running a read whose
        //       result is discarded, and the direction is the one of the two the caller can act on.
        boolean backward = resolveBackward(cursor, direction);

        // WHY : Assumptions: the summary is read FIRST and its absence short-circuits the browse, which is
        //       the reference order at L350-L357: the account details are gathered, the summary is read
        //       inside them at L785, and the page is processed only when the summary was found. Reading
        //       the children first would spend a query on a parent that turns out not to exist.
        PendingAuthSummary summary = this.summaries.findByAccountId(accountId).orElse(null);
        if (summary == null) {
            return unopenedAccountPage(accountId, subject);
        }

        if (cursor == null) {
            return openingPage(summary, accountId, subject);
        }

        PendingAuthDetailKey position = this.mapper.openCursor(cursor, accountId, subject);
        return backward ? backwardPage(summary, accountId, position, subject)
                : forwardPage(summary, accountId, position, subject);
    }

    /**
     * Renders the zeroed summary and empty page an account with no summary row is answered with.
     *
     * <p>Purpose: this stands for the summary-absent arm of {@code GATHER-ACCOUNT-DETAILS} at L800-L807,
     * which moves zero into the approved count, the declined count, the credit balance, the cash balance,
     * the approved total and the declined total, taken together with the guard at L354-L356 that leaves
     * the five row positions blank.</p>
     *
     * <p>Assumptions: the zeroed state is built by the domain type's OWN identified-and-otherwise-zeroed
     * constructor rather than assembled field by field here. That constructor already sets both counters
     * to zero and all six monetary components to zero at scale two, which is the same set of positions the
     * reference program zeroes, so reproducing the list here would be a second place the set could drift
     * from the segment layout at {@code cpy/CIPAUSMY.cpy} L19-L31. The instance is never persisted and is
     * not associated with the persistence context; it exists only to carry those zeros to the mapper,
     * which is this context's one route to a published body.</p>
     *
     * <p>Assumptions: NO authorization query is issued on this path, and the schema is what makes that
     * sound rather than an assumption about the data. The child segment is declared
     * {@code PARENT=((PAUTSUM0,))} at {@code ims/DBPAUTP0.dbd} L36, so a hierarchical occurrence cannot
     * exist without its root, and the migration carries that forward as
     * {@code fk_pending_auth_detail_summary FOREIGN KEY (account_id) REFERENCES pending_auth_summary
     * (account_id)}. With no summary row there can be no authorization row to find, so a query would be
     * one guaranteed to return nothing.</p>
     *
     * <p>Trade-offs: the published body cannot distinguish an account this context has never recorded an
     * authorization for from an account whose summary exists and is quiet, beyond the zeroed customer
     * identifier that the first carries and the second does not. That is accepted because it is the
     * distinction the reference screen itself draws -- both render as zeros in the six aggregate positions
     * with no rows beneath them -- and inventing a stronger signal would publish a state the screen has no
     * way to express. A caller needing the account's existence settled asks the context that owns the
     * account master, which is named in the cross-context paragraph of this class's documentation.</p>
     *
     * @param accountId the account the request named, which is the one identifier this path can publish;
     *     never {@code null}
     * @param subject the authenticated principal the empty page is issued to; never {@code null}
     * @return a body carrying the zeroed summary block, no rows, no boundary cursors and no boundary
     *     sentence; never {@code null}
     */
    private PendingAuthListView unopenedAccountPage(Long accountId, String subject) {
        PendingAuthSummary zeroed = new PendingAuthSummary(accountId, UNKNOWN_CUSTOMER_ID);
        return this.mapper.toListView(zeroed, List.of(), false, null, subject,
                customerDisplayOf(zeroed));
    }

    /**
     * Reads the opening page, the one an absent cursor asks for.
     *
     * <p>Purpose: this stands for {@code PROCESS-PAGE-FORWARD} at L415-L454 entered on a first display,
     * where the retained position has just been seeded at L422.</p>
     *
     * <p>Assumptions: an ABSENT cursor and not an empty string is what asks for the opening page, because
     * the reference program opens one by moving low values into its retained position at L422, and low
     * values and spaces are two distinct empty states in that program rather than one -- it tests for both
     * separately at L391. The repository serves this case with no comparison at all rather than a
     * comparison against a lowest-possible key, which avoids inventing a sentinel for two columns the
     * schema declares not null.</p>
     *
     * @param summary the account's summary row, never {@code null}
     * @param accountId the account being paged, never {@code null}
     * @param subject the authenticated principal the page's boundary tokens are sealed against; never
     *     {@code null}
     * @return the opening page, never {@code null}
     */
    private PendingAuthListView openingPage(PendingAuthSummary summary, Long accountId,
            String subject) {
        List<PendingAuthDetail> read = this.details
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(accountId, pageLimit());

        // WHY : Assumptions: nothing here tells the client whether an earlier page exists, because the
        //       opening page is precisely the state the client itself recognises: it holds the page ordinal
        //       the reference keeps in its communication area (cbl/COPAUS0C.cbl L122, tested at L365) and
        //       renders the top-of-page sentence from L381 without asking. What this page owes it is the
        //       leading boundary token to seek from, which the envelope carries.
        return page(summary, read, subject);
    }

    /**
     * Reads the page after a supplied position, which in newest-first order is the older rows.
     *
     * <p>Purpose: this stands for {@code PROCESS-PF8-KEY} at L388-L412 together with the re-seek it
     * performs through {@code REPOSITION-AUTHORIZATIONS} at L488-L519.</p>
     *
     * <p>Assumptions: "after the cursor" means OLDER, because this operation returns authorizations newest
     * first. A reader taking "after" to mean later in time would invert the browse, and the query would
     * still return real rows in a plausible order.</p>
     *
     * <p>Assumptions: no re-seek statement of this method's own is needed, and the reference program's
     * extra paragraph is the reason it looks as though one would be. Its probe retrieval ADVANCES the
     * hierarchical position, so it cannot continue from where it stands and must restore the key it saved
     * at L394 and re-seek at L397. A predicate consumes no position, so seeking from the key is inherent
     * to every call here.</p>
     *
     * @param summary the account's summary row, never {@code null}
     * @param accountId the account being paged, never {@code null}
     * @param position the key of the last row already shown, never {@code null}
     * @param subject the authenticated principal the page's boundary tokens are sealed against; never
     *     {@code null}
     * @return the following page, or an empty page carrying the bottom-of-page sentence when no row
     *     follows; never {@code null}
     */
    private PendingAuthListView forwardPage(PendingAuthSummary summary, Long accountId,
            PendingAuthDetailKey position, String subject) {

        List<PendingAuthDetail> read = this.details.findOlderThan(accountId, position.getAuthDate(),
                position.getAuthTime(), pageLimit());
        if (read.isEmpty()) {
            // WHY : Assumptions: this is the reference bottom-of-page state, reached at L409-L410 when a
            //       forward move is attempted from the closing page, and it is INFORMATIONAL rather than
            //       an error: that program leaves the rows already on the screen untouched and merely adds
            //       the sentence. The stateless equivalent is an empty page plus the sentence, and the
            //       published contract states the same thing on its boundary-message schema, so a
            //       conforming client keeps what it is displaying rather than clearing it.
            return this.mapper.toListView(summary, List.of(), false,
                    PendingAuthListView.MESSAGE_BOTTOM_OF_PAGE, subject,
                customerDisplayOf(summary));
        }
        return page(summary, read, subject);
    }

    /**
     * Reads the page before a supplied position, which in newest-first order is the newer rows.
     *
     * <p>Purpose: this serves the backward move the reference program performs at {@code PROCESS-PF7-KEY}
     * L362-L385.</p>
     *
     * <p>Refactoring Rationale: the reference program has no backward retrieval verb, and stating its
     * actual mechanism matters because the obvious description of this method is not a description of it.
     * It steps back by restoring a retained page-start key at L368-L369 and running the FORWARD path again
     * at L379. The consequence of that structure is that the history it steps through has to be held
     * somewhere, and it is held server-side in the twenty-slot array at L120 that the terminal echoes back
     * -- so the reachable history is twenty pages and the page counter and last-page flag at L122 and L123
     * exist to index it. Under the stateless model that history belongs to the client, which already holds
     * the cursor of the page it is showing, so those three fields have no target equivalent and are
     * dropped rather than ported: a has-next indicator plus a client-held cursor carry the same
     * information without them.</p>
     *
     * <p>Trade-offs: the underlying query is ASCENDING and its result is reversed here, and the two-step
     * shape is the point rather than an inconvenience. Ordering it descending and applying the limit would
     * return the newest rows in the whole account rather than the rows immediately preceding the caller's
     * position, which is a page the caller never asked for; ascending keeps the rows nearest that position
     * within reach of the limit, and reversing presents them in the same order every other page uses.</p>
     *
     * @param summary the account's summary row, never {@code null}
     * @param accountId the account being paged, never {@code null}
     * @param position the key of the first row already shown, never {@code null}
     * @param subject the authenticated principal the page's boundary tokens are sealed against; never
     *     {@code null}
     * @return the preceding page, or an empty page carrying the top-of-page sentence when no row precedes;
     *     never {@code null}
     */
    private PendingAuthListView backwardPage(PendingAuthSummary summary, Long accountId,
            PendingAuthDetailKey position, String subject) {

        List<PendingAuthDetail> ascending = this.details.findNewerThan(accountId,
                position.getAuthDate(), position.getAuthTime(), pageLimit());
        if (ascending.isEmpty()) {
            // WHY : Assumptions: the reference top-of-page state, reached at L381 when a backward move is
            //       attempted from the opening page, and informational on the same terms as the
            //       bottom-of-page state above.
            return this.mapper.toListView(summary, List.of(), false,
                    PendingAuthListView.MESSAGE_TOP_OF_PAGE, subject,
                customerDisplayOf(summary));
        }

        // WHY : Assumptions: the CLOSEST rows are kept and the furthest discarded, which is the opposite
        //       end of the list from the forward case. The look-ahead row of an ascending backward read is
        //       the row furthest from the caller's position, so trimming from the tail is what leaves the
        //       page adjacent to where the caller was.
        List<PendingAuthDetail> nearest = new ArrayList<>(
                ascending.subList(0, Math.min(PAGE_SIZE, ascending.size())));
        Collections.reverse(nearest);

        // WHY : Assumptions: has-next is established by a PROBE READ even on a backward move, rather than
        //       assumed true because the caller arrived from a following page. The reference program routes
        //       its backward move through PROCESS-PAGE-FORWARD at L379, whose closing probe at L445-L452
        //       therefore runs on both directions, so probing here is the transcription. Assuming it would
        //       also be wrong in one reachable case: the row the caller's cursor names can have been
        //       removed by the expiry sweep between the two requests, and the caller would then be told a
        //       page follows that no longer does.
        PendingAuthDetailKey last = nearest.get(nearest.size() - 1).getId();
        boolean hasNext = !this.details.findOlderThan(accountId, last.getAuthDate(),
                last.getAuthTime(), Limit.of(PROBE_ROWS)).isEmpty();

        // WHY : Refactoring Rationale: the look-ahead row of this backward read is NOT published as a
        //       backward availability answer, because the shared envelope carries the backward POSITION and
        //       not that answer. The reference asks the same question of its own communication area rather
        //       than of the database -- cbl/COPAUS0C.cbl tests CDEMO-CPVS-PAGE-NUM at L365 and raises
        //       'You are already at the top of the page...' at L381 -- so the answer belongs to the client
        //       that holds the ordinal. The row itself is still read, because the same list is what the
        //       page is trimmed from.
        return this.mapper.toListView(summary, nearest, hasNext, null, subject,
                customerDisplayOf(summary));
    }

    /**
     * Discards the look-ahead row off a forward read and renders the page.
     *
     * <p>Purpose: this is the seam the reference program expresses as its five-row fill loop at L424-L443
     * followed by the probe retrieval at L445-L452, and it is where the substitution of a keyset query for
     * anchor replay is shown to be structural rather than approximate. That program stores each displayed
     * row's key into its retained position INSIDE the loop at L434-L435 and then, after the loop closes,
     * reads once more for no purpose other than to discover whether a further row exists, setting its
     * next-page indicator at L448 or L450. The probe row's key is never stored, so the page's closing
     * position remains the key of the last row actually displayed.</p>
     *
     * <p>Assumptions: the extra row is discarded HERE and not in the repository, which is the seam the
     * package charter declares: a keyset method returns one row more than the page size and leaves the
     * probe row present, and the service discards it, reads has-next from whether it arrived, and
     * assembles the envelope. Anything written against that boundary assuming a list of exactly the page
     * size silently drops a row. Has-next is read from the row's PRESENCE and never from the row count on
     * its own, because a full page and a final page can carry the same number of rows -- which is why the
     * reference program carries an explicit indicator at L123-L125 instead of counting.</p>
     *
     * @param summary the account's summary row, never {@code null}
     * @param read the rows the repository returned, up to one more than the page size, never {@code null}
     * @param subject the authenticated principal the page's boundary tokens are sealed against; never
     *     {@code null}
     * @return the rendered page with no boundary sentence, never {@code null}
     */
    private PendingAuthListView page(PendingAuthSummary summary, List<PendingAuthDetail> read,
            String subject) {
        boolean hasNext = read.size() > PAGE_SIZE;
        List<PendingAuthDetail> rows = hasNext ? read.subList(0, PAGE_SIZE) : read;
        return this.mapper.toListView(summary, rows, hasNext, null, subject,
                customerDisplayOf(summary));
    }

    /**
     * Returns the row limit a page read uses: the page size plus the one look-ahead row.
     *
     * <p>Assumptions: the limit is composed from the two named constants rather than written as a literal
     * six, so that the five rows the reference screen declares and the single probe row it reads stay
     * separately visible. A literal would leave a reader unable to tell which of the two a change was
     * meant to alter.</p>
     *
     * @return the limit to apply to a page read, never {@code null}
     */
    private static Limit pageLimit() {
        return Limit.of(PAGE_SIZE + PROBE_ROWS);
    }

    /**
     * Resolves the requested direction, refusing the two shapes the contract declares invalid.
     *
     * <p>Purpose: this stands for the attention-identifier evaluation at L224-L250, which routes the
     * backward move at L239, the forward move at L242, and anything else to the invalid-key message at
     * L245-L249.</p>
     *
     * @param cursor the sealed paging position the request carried, or {@code null}
     * @param direction the direction the request carried, or {@code null} to default to next
     * @return {@code true} when the request asks for the preceding page, {@code false} for the following
     *     page
     * @throws ClientInputException if a direction accompanies no cursor, or names neither published value
     */
    private static boolean resolveBackward(String cursor, String direction) {
        if (direction == null) {
            return false;
        }
        if (cursor == null) {
            // WHY : Trade-offs: a direction with no cursor is REFUSED rather than ignored, which the
            //       published contract states and which costs a caller one round trip. What it buys is
            //       that the caller learns which of its two parameters it omitted: silently answering the
            //       opening page would look like success to a client that had dropped its cursor, and it
            //       would page from the beginning indefinitely without anything reporting a fault.
            throw new ClientInputException(PAGING_REFUSAL_CODE, DIRECTION_FIELD,
                    "a paging direction is meaningful only alongside a cursor, and neither is sent"
                            + " without the other");
        }
        if (DIRECTION_PREVIOUS.equals(direction)) {
            return true;
        }
        if (DIRECTION_NEXT.equals(direction)) {
            return false;
        }
        // WHY : Assumptions: the refused value is NOT quoted back. The direction is a short enumerated
        //       token rather than a card number, so quoting it would disclose nothing -- but the two
        //       published values say everything a caller needs, and a message that never echoes input is
        //       one fewer place a later edit can start echoing something sensitive.
        throw new ClientInputException(PAGING_REFUSAL_CODE, DIRECTION_FIELD,
                "a paging direction is either " + DIRECTION_NEXT + " or " + DIRECTION_PREVIOUS);
    }


    /**
     * Reads the four customer display fields the screen shows, once for the whole page.
     *
     * <p>Refactoring Rationale: this call is added because the screen was publishing the segment's own
     * identifiers and totals and nothing else, while the record it publishes into declares a customer name,
     * two address lines and a telephone number. The reference composes all four from
     * {@code GETCUSTDATA-BYCUST} at {@code cbl/COPAUS0C.cbl} L920, so their absence was a
     * functional-parity gap and not the design choice this class's documentation described it as.</p>
     *
     * <p>Assumptions: ONE call per page, not one per row. Every authorization beneath a summary belongs to
     * the same account and so the same customer, so a per-row read would make the screen's cost grow with
     * the page size for data identical on every row. That is what bounded means here -- the number of
     * cross-context calls this operation makes is one, whatever the page holds.</p>
     *
     * <p>Assumptions: an absent customer, and a segment that names no customer identifier, both yield
     * {@code null} and the screen renders blanks. The reference has a not-found arm that leaves the fields
     * unfilled and continues, so this preserves its behaviour rather than adding a refusal it does not
     * have; refusing the whole screen because a display name could not be resolved would withdraw the
     * authorization totals the operator can act on over the four fields they cannot.</p>
     *
     * <p>Trade-offs: a transport FAILURE is not caught here and propagates as the account context's own
     * unavailable exception, unlike an absent record. The distinction is the point: a customer that does
     * not exist is an answer, whereas one that could not be reached is not, and rendering blanks for the
     * second would present an unreachable dependency as an empty record. The timeouts that bound how long
     * such a failure takes to arrive are configured on the client rather than restated here.</p>
     *
     * @param summary the summary whose customer identifier is resolved; must not be {@code null}
     * @return the display fields, or {@code null} when the segment names no customer or none was resolved
     */
    private AccountContextClient.CustomerDisplay customerDisplayOf(PendingAuthSummary summary) {
        Long customerId = summary.getCustomerId();
        if (customerId == null) {
            return null;
        }
        return this.accounts.customerDisplay(customerId.longValue()).orElse(null);
    }
}
