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
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves one page of one account's pending authorizations, positioned by key.
 *
 * <p><strong>Purpose.</strong> Carry across the browse loop of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl}: {@code PROCESS-PAGE-FORWARD} at L415 to
 * L457, {@code GET-AUTHORIZATIONS} at L458 to L487, {@code REPOSITION-AUTHORIZATIONS} at L488 to L521,
 * {@code POPULATE-AUTH-LIST} at L522 to L607 and {@code GET-AUTH-SUMMARY} at L966 to L1000. Every
 * citation below is relative to that tree, which is reference material this migration reads and never
 * modifies.
 *
 * <p>Refactoring Rationale: the reference program holds its browse position in the communication area
 * the terminal echoes back -- a last key at L121, a stack of page-start keys at L120, a page number at
 * L122 and a next-page flag at L123 -- and it resumes by re-seeking to a saved key rather than by
 * offset. This class holds no position at all: the position arrives sealed in the request and the
 * repository seeks straight to it, so any task behind the load balancer can serve any page of any
 * browse. The four values the communication area carried are the four the page envelope carries, so the
 * state is not lost, it moves to the client.
 *
 * <p>Alternatives Considered: offset paging, which would let a caller jump to a page number as the
 * reference stack of twenty page-start keys nearly does. Rejected because under concurrent inserts --
 * and this table is written continuously by the message-driven half of this context -- an offset page
 * skips rows and repeats others, which a browse that steps by key never does. The reference program
 * cannot skip a row either, so offset paging would be a behavioural change disguised as a convenience.
 */
@Service
public class PendingAuthSummaryService {

    /**
     * The number of authorizations one page carries.
     *
     * <p>Assumptions: five, and the figure is the reference screen's rather than a tuning choice. The
     * program fills a table declared {@code OCCURS 5 TIMES} at {@code cbl/COPAUS0C.cbl} L126 under a loop
     * bounded at five at L424, and the response record this context publishes carries exactly five row
     * groups for the same reason. The contract publishes no page-size parameter, so a caller cannot ask
     * for a different size and this constant is the whole of the decision.</p>
     */
    public static final int PAGE_SIZE = 5;

    /**
     * The paging direction that reads keys after the cursor, which is the contract's default.
     */
    public static final String DIRECTION_NEXT = "next";

    /**
     * The paging direction that reads keys before the cursor.
     *
     * <p>Assumptions: this is the migrated form of the reference reposition at {@code cbl/COPAUS0C.cbl}
     * L488 to L497, reached from the backward attention identifier tested at L425.</p>
     */
    public static final String DIRECTION_PREVIOUS = "previous";

    /**
     * The stable token operational tooling matches a refused paging request on.
     */
    public static final String PAGING_REFUSAL_CODE = "AUTH_PAGING_REFUSED";

    /**
     * The contract field name a refused paging direction is keyed by.
     *
     * <p>Assumptions: {@code direction} is one of the six per-field keys the shared 400 response of
     * {@code src/main/resources/openapi/authorization-api.yaml} declares its handlers can emit.</p>
     */
    public static final String DIRECTION_FIELD = "direction";

    /**
     * The look-ahead probe reads exactly one row beyond the page.
     *
     * <p>Assumptions: one, because the reference program issues exactly one further retrieval purely as a
     * probe at {@code cbl/COPAUS0C.cbl} L445 to L452 and sets its next-page indicator from whether that
     * retrieval succeeded. Reading more would answer a question nothing asks.</p>
     */
    private static final int PROBE_ROWS = 1;

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
     * @throws NullPointerException if any argument is {@code null}
     */
    public PendingAuthSummaryService(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details, PendingAuthViewMapper mapper) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.details = Objects.requireNonNull(details, "details must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Returns one account's authorization summary together with one page of its authorizations.
     *
     * <p>Assumptions: an account with no summary row is NOT FOUND, while an account whose summary row
     * carries no authorizations is an empty page. The two are different states in the reference program
     * as well: {@code GET-AUTH-SUMMARY} sets a not-found condition at {@code cbl/COPAUS0C.cbl} L984 and
     * the caller then skips the browse entirely at L354 to L356, whereas a summary that is found with no
     * children reaches the browse and fills no rows. Collapsing them would answer a request about an
     * account this context has never seen with an empty page, which a client cannot tell from an account
     * that is simply quiet.
     *
     * <p>Assumptions: the direction is meaningful only alongside a cursor, and the two arrive together or
     * not at all. A direction with no cursor is refused rather than treated as an opening page, because a
     * caller that sent one and not the other has made a mistake in exactly one of the two and answering
     * the opening page would hide it.
     *
     * @param accountId the account whose authorizations are wanted; must not be {@code null}
     * @param cursor the sealed paging position to continue from, or {@code null} for the opening page
     * @param direction {@link #DIRECTION_NEXT} or {@link #DIRECTION_PREVIOUS}, or {@code null} to default
     *     to next; meaningful only when {@code cursor} is present
     * @param subject the authenticated principal the page's boundary tokens are sealed against;
     *     must not be {@code null}, because a cursor bound to no subject is redeemable by every other
     *     authorized operator
     * @return the summary block, one page of rows newest first, and the boundary sentence when the request
     *     was a paging move that had already reached a boundary; never {@code null}
     * @throws NullPointerException if {@code accountId} is {@code null}
     * @throws ClientInputException if {@code direction} is supplied without a {@code cursor}, or names
     *     neither published value
     * @throws PendingAuthViewMapper.InvalidSelectorException if {@code cursor} cannot be redeemed
     * @throws NoSuchElementException if the account has no summary row
     */
    @Transactional(readOnly = true)
    public PendingAuthListView list(Long accountId, String cursor, String direction,
            String subject) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        boolean backward = resolveBackward(cursor, direction);

        // WHY : Assumptions: the summary is read FIRST and its absence short-circuits the browse, which is
        //       the reference order at cbl/COPAUS0C.cbl L350 to L357: the account details are gathered,
        //       the summary is read inside them at L785, and the page is processed only when the summary
        //       was found. Reading the children first would spend a query on an account that cannot be
        //       answered.
        PendingAuthSummary summary = this.summaries.findByAccountId(accountId)
                .orElseThrow(() -> new NoSuchElementException(
                        "no pending-authorization summary exists for the requested account"));

        if (cursor == null) {
            return openingPage(summary, accountId, subject);
        }

        PendingAuthDetailKey position = this.mapper.openCursor(cursor, accountId, subject);
        return backward ? backwardPage(summary, accountId, position, subject)
                : forwardPage(summary, accountId, position, subject);
    }

    /**
     * Reads the opening page, the one an absent cursor asks for.
     *
     * <p>Assumptions: an ABSENT cursor and not an empty string is what asks for the opening page, because
     * the reference program opens one by moving low values into its forward position at
     * {@code cbl/COPAUS0C.cbl} L422, and low values and spaces are two distinct empty states in that
     * program rather than one.</p>
     *
     * @param summary the account's summary row, never {@code null}
     * @param accountId the account being paged, never {@code null}
     * @param subject the authenticated principal the page's boundary tokens are sealed against;
     *     never {@code null}
     * @return the opening page, never {@code null}
     */
    private PendingAuthListView openingPage(PendingAuthSummary summary, Long accountId,
            String subject) {
        List<PendingAuthDetail> read = this.details
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(accountId, pageLimit());
        return page(summary, read, subject);
    }

    /**
     * Reads the page after a supplied position, which in newest-first order is the older rows.
     *
     * <p>Assumptions: "after the cursor" means OLDER, because this context returns authorizations newest
     * first. A reader who takes after to mean later in time would invert the browse.</p>
     *
     * @param summary the account's summary row, never {@code null}
     * @param accountId the account being paged, never {@code null}
     * @param position the key of the last row already shown, never {@code null}
     * @param subject the authenticated principal the page's boundary tokens are sealed against;
     *     never {@code null}
     * @return the following page, or an empty page carrying the bottom-of-page sentence when no row
     *     follows; never {@code null}
     */
    private PendingAuthListView forwardPage(PendingAuthSummary summary, Long accountId,
            PendingAuthDetailKey position, String subject) {

        List<PendingAuthDetail> read = this.details.findOlderThan(accountId, position.getAuthDate(),
                position.getAuthTime(), pageLimit());
        if (read.isEmpty()) {
            // WHY : Assumptions: this is the reference bottom-of-page state, reached at cbl/COPAUS0C.cbl
            //       L409 to L410 when a forward move is attempted from the closing page, and it is
            //       INFORMATIONAL rather than an error: that program leaves the rows already on the screen
            //       untouched and merely adds the sentence. The stateless equivalent is an empty page plus
            //       the sentence, and the contract states the same thing on its own boundary-message
            //       schema -- the rows already returned remain valid and no error state is set -- so a
            //       conforming client keeps what it is displaying rather than clearing it.
            return this.mapper.toListView(summary, List.of(), false,
                    PendingAuthListView.MESSAGE_BOTTOM_OF_PAGE, subject);
        }
        return page(summary, read, subject);
    }

    /**
     * Reads the page before a supplied position, which in newest-first order is the newer rows.
     *
     * <p>Refactoring Rationale: the backward query is ASCENDING and its result is reversed here, and the
     * two-step shape is the point rather than an inconvenience. Ordering it descending and applying the
     * limit would return the newest rows in the whole account rather than the ones immediately preceding
     * the current page, which is a page the caller never asked for; ascending keeps the rows nearest the
     * caller's position and reversing presents them in the same order every other page uses.</p>
     *
     * @param summary the account's summary row, never {@code null}
     * @param accountId the account being paged, never {@code null}
     * @param position the key of the first row already shown, never {@code null}
     * @param subject the authenticated principal the page's boundary tokens are sealed against;
     *     never {@code null}
     * @return the preceding page, or an empty page carrying the top-of-page sentence when no row precedes;
     *     never {@code null}
     */
    private PendingAuthListView backwardPage(PendingAuthSummary summary, Long accountId,
            PendingAuthDetailKey position, String subject) {

        List<PendingAuthDetail> ascending = this.details.findNewerThan(accountId,
                position.getAuthDate(), position.getAuthTime(), pageLimit());
        if (ascending.isEmpty()) {
            // WHY : Assumptions: the reference top-of-page state, reached at cbl/COPAUS0C.cbl L381 when a
            //       backward move is attempted from the opening page, and informational on the same terms
            //       as the bottom-of-page state above.
            return this.mapper.toListView(summary, List.of(), false,
                    PendingAuthListView.MESSAGE_TOP_OF_PAGE, subject);
        }

        // WHY : Assumptions: the CLOSEST rows are kept and the furthest discarded, which is the opposite
        //       end of the list from the forward case. The extra look-ahead row of an ascending backward
        //       read is the row furthest from the caller's position, so trimming from the tail is what
        //       leaves the page adjacent to where the caller was.
        List<PendingAuthDetail> nearest = new ArrayList<>(
                ascending.subList(0, Math.min(PAGE_SIZE, ascending.size())));
        Collections.reverse(nearest);

        // WHY : Refactoring Rationale: has-next is established by a PROBE READ even on a backward move,
        //       rather than assumed true because the caller came from a following page. The reference
        //       program routes its backward move through PROCESS-PAGE-FORWARD at cbl/COPAUS0C.cbl L376,
        //       whose closing probe at L445 to L452 therefore runs on both directions, so probing here is
        //       the transcription. Assuming it would also be wrong in one reachable case: the row the
        //       caller's cursor names can have been removed by the expiry sweep between the two requests,
        //       and the caller would then be told a page follows that no longer does.
        PendingAuthDetailKey last = nearest.get(nearest.size() - 1).getId();
        boolean hasNext = !this.details.findOlderThan(accountId, last.getAuthDate(),
                last.getAuthTime(), Limit.of(PROBE_ROWS)).isEmpty();
        return this.mapper.toListView(summary, nearest, hasNext, null, subject);
    }

    /**
     * Trims the look-ahead row off a forward read and renders the page.
     *
     * <p>Assumptions: the extra row is discarded HERE and not in the repository, which is the seam that
     * package's charter declares: a keyset method returns one row more than the page size and the service
     * discards it, reads has-next from whether it arrived, and assembles the envelope. Anything written
     * against that boundary assuming a list of exactly the page size silently drops a row.</p>
     *
     * @param summary the account's summary row, never {@code null}
     * @param read the rows the repository returned, up to one more than the page size, never {@code null}
     * @param subject the authenticated principal the page's boundary tokens are sealed against;
     *     never {@code null}
     * @return the rendered page with no boundary sentence, never {@code null}
     */
    private PendingAuthListView page(PendingAuthSummary summary, List<PendingAuthDetail> read,
            String subject) {
        boolean hasNext = read.size() > PAGE_SIZE;
        List<PendingAuthDetail> rows = hasNext ? read.subList(0, PAGE_SIZE) : read;
        return this.mapper.toListView(summary, rows, hasNext, null, subject);
    }

    /**
     * Returns the row limit a page read uses: the page size plus the look-ahead row.
     *
     * @return the limit, never {@code null}
     */
    private static Limit pageLimit() {
        return Limit.of(PAGE_SIZE + PROBE_ROWS);
    }

    /**
     * Resolves the requested direction, refusing the two shapes the contract declares invalid.
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
            //       contract states and which costs a caller one round trip. What it buys is that the
            //       caller learns which of its two parameters it omitted: silently answering the opening
            //       page would look like success to a client that had dropped its cursor, and it would
            //       page from the beginning forever without ever reporting anything wrong.
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
        // WHY : Assumptions: the refused value is NOT quoted. The direction is a short enumerated token
        //       rather than a card number, so quoting it would disclose nothing -- but the two published
        //       values say everything a caller needs, and a message that never echoes input is one fewer
        //       place a future edit can start echoing something sensitive.
        throw new ClientInputException(PAGING_REFUSAL_CODE, DIRECTION_FIELD,
                "a paging direction is either " + DIRECTION_NEXT + " or " + DIRECTION_PREVIOUS);
    }

}
