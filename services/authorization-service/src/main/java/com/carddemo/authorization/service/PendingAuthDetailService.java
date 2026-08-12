package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.PendingAuthDetailResponse;
import com.carddemo.authorization.dto.PendingAuthDetailView;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads one pending authorization in full and projects it the way the reference detail screen did.
 *
 * <p><strong>Purpose.</strong> Carry across the read half of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}, which the package charter assigns to this
 * class as three paragraphs: {@code POPULATE-AUTH-DETAILS} at L291 to L359, {@code READ-AUTH-RECORD} at
 * L431 to L492 and {@code READ-NEXT-AUTH-RECORD} at L493 to L519. Every citation below is relative to
 * that tree, which is reference material this migration reads and never modifies.
 *
 * <p>Refactoring Rationale: the reference read is TWO retrievals -- a parent get-unique qualified on the
 * account at L439 to L443, then a child get-next-within-parent qualified on the eight-byte key at L465
 * to L469 -- and this class issues ONE. The reason is that the hierarchy which forced the parent read is
 * gone: the relational child key names its own account, so the child row is directly addressable, and
 * the foreign key declared at migration L624 to L625 guarantees the parent exists whenever the child
 * does. Reading the parent as well would spend a query to re-establish an invariant the schema already
 * holds, and the account-scope check the parent read implicitly performed is instead performed on the
 * selector itself.
 *
 * <p>Alternatives Considered: composing the several display values the reference screen builds -- the
 * five-character card expiry it makes by overlaying a solidus at L337, and the fraud mark it makes into a
 * flag, a separator and a report date at L345 to L347 or a lone separator at L349 -- into
 * {@link PendingAuthDetailView}. Rejected because that contract publishes the STORED values and describes
 * each composition beside the property, so exactly one reading of every field exists on the wire and the
 * client composes what it wants to display. Composing there would put two readings of the same field in
 * circulation, and a client would have no way to recover the stored one. The composed, screen-shaped form
 * is published separately by {@link #readForScreen} through {@link PendingAuthDetailResponse}, so both
 * readings exist without either displacing the other.
 *
 * <p>Assumptions: the write half of this screen is NOT here. {@code UPDATE-AUTH-DETAILS} at L520 to L556
 * belongs to {@link FraudMarkingService}, including the behavioural asymmetry that its success branch at
 * L532 to L538 does not re-send the screen while its failure branch does at L550, and including the
 * rollback message this program composes at L545. That paragraph is referenced here and deliberately not
 * duplicated, so the two classes cannot come to disagree about what marking an authorization does.
 *
 * <p>Assumptions: the transaction bracket the reference program opens for these reads is an explicit
 * paragraph pair -- {@code TAKE-SYNCPOINT.} at L557 with its commit at L558, and {@code ROLL-BACK.} at
 * L565 with its rollback at L567. Each read here is a single {@code @Transactional} unit declared on this
 * class rather than on the repository, and a rollback is reached by letting an exception propagate rather
 * than by an explicit call. No read declares a pessimistic lock, because the reference program takes none
 * on any of these three paragraphs and a read that locked would hold rows across a client's think time
 * that the reference never held.
 *
 * <p>Assumptions: no golden master exists for any path in this class, so parity is not claimed from one.
 * The online programs of this application cannot be run end to end without a CICS runtime, which the test
 * harness does not provide, so the evidence for these transcriptions is the copybook and migration
 * contracts plus the paragraph-by-paragraph reading cited at each member below. The harness's aggregate
 * warn-level return code is unrelated to this module and is not a signal about it.
 *
 * <p>Alternatives Considered: adopting a resilience library so these reads could carry a retry policy.
 * Rejected because retry lives in the Spring Framework core that arrives inside the Spring Boot parent --
 * {@code @Retryable}, {@code @ConcurrencyLimit} and a programmatic retry policy -- so a library would be
 * a second retry authority for a capability the platform already has. Two details of that API are
 * recorded here because both are easy to write the other way round: the annotation attribute is
 * {@code maxRetries} and not {@code maxAttempts}, so the total number of attempts is one plus its value
 * and defaults to three, and the enabling annotation is {@code @EnableResilientMethods} and not
 * {@code @EnableRetry}. Neither {@code resilience4j-spring-boot3}, whose published artifact targets the
 * previous major of the framework, nor the superseded {@code spring-retry} is adopted, and no circuit
 * breaker is configured. The absence of the library is recorded in
 * {@code docs/adr/ADR-002-compute-platform.md}.
 *
 * <p>Assumptions: no retry policy is applied at any member of this class, and the boundary that would
 * govern one is recorded rather than left to a later reader to guess. The reference program's own
 * transient-condition set is exactly three infrastructure statuses --
 * {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} at L88, being a database unavailable, a handler
 * failure and a failure to schedule the program's data access -- so an {@code includes} list added here
 * would have to be equally narrow. The four statuses the same declaration block names and deliberately
 * omits from that set are {@code 'GE'} segment-not-found at L81, {@code 'II'} duplicate-segment at L82,
 * {@code 'GP'} wrong-parentage at L83 and {@code 'GB'} end-of-database at L84; each describes a state of
 * the data rather than of the infrastructure, so retrying one would repeat a read that has already
 * answered. The reason no annotation is written now is concrete rather than stylistic:
 * {@code @EnableResilientMethods} appears on no configuration class in this module, so an annotation
 * added here would be inert and would read as protection that does not exist.
 */
@Service
public class PendingAuthDetailService {

    /**
     * The declared form of this screen's money edit mask, {@code WS-AUTH-AMT} at L52.
     *
     * <p>Assumptions: this is the TWELVE-character screen mask {@code -zzzzzzz9.99} and it is not the
     * fourteen-character wire mask {@code -zzzzzzzzz9.99} that {@code cbl/COPAUA0C.cbl} declares at its
     * L66 as {@code WS-APPROVED-AMT-DIS}. The two differ by two zero-suppression positions, so a value
     * rendered through the wrong one is a well-formed amount whose digits sit in the wrong columns -- an
     * output that looks correct and is wrong at a fixed offset. This class renders the screen form only;
     * the fourteen-character wire form belongs to the message path and is composed by the shared
     * authorization codec, which is why neither width is expressed twice.
     */
    public static final String SCREEN_AMOUNT_MASK = "-zzzzzzz9.99";

    /**
     * The exact number of characters {@link #SCREEN_AMOUNT_MASK} occupies.
     *
     * <p>Assumptions: one sign position, seven zero-suppression positions, one mandatory integer digit, a
     * decimal point and two mandatory decimal digits, which is 1 + 7 + 1 + 1 + 2. The count is declared
     * rather than measured from the mask string so that a rendering can be asserted against the width the
     * reference field holds instead of against its own output.
     */
    public static final int SCREEN_AMOUNT_WIDTH = 12;

    /**
     * The integer digit positions {@link #SCREEN_AMOUNT_MASK} provides.
     *
     * <p>Assumptions: eight, being the seven suppression positions plus the one mandatory digit that
     * keeps a zero amount printing. This is NARROWER than the ten integer digits the stored column holds
     * as {@code PA-APPROVED-AMT PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L35, and the
     * consequence of that narrowing is stated at {@link #renderScreenAmount}.
     */
    public static final int SCREEN_AMOUNT_INTEGER_DIGITS = 8;

    /**
     * The leading integer positions {@link #SCREEN_AMOUNT_MASK} suppresses leading zeros in.
     *
     * <p>Assumptions: seven, one fewer than {@link #SCREEN_AMOUNT_INTEGER_DIGITS}, because the final
     * integer position of the mask is a mandatory digit rather than a suppression position. That one
     * character is why a zero amount renders as a zero and not as an empty integer region.
     */
    public static final int SCREEN_AMOUNT_SUPPRESSED_DIGITS = 7;

    /**
     * The value {@code WS-AUTH-DATE} carries at L53 before any authorization has been projected into it.
     *
     * <p>Assumptions: this literal is the empty state and not a placeholder for one. The reference field
     * is declared {@code PIC X(08) VALUE '00/00/00'} at L53, and {@code POPULATE-AUTH-DETAILS} only
     * overwrites it inside the {@code IF ERR-FLG-OFF} gate at L294 -- so on every path where the read did
     * not produce a row, this is verbatim what the screen displayed. It is carried across as the literal
     * eight characters rather than as {@code null}, an empty string or a zero date in calendar order,
     * because each of those three is a different value that no reference field ever held.
     */
    public static final String EMPTY_AUTH_DATE = "00/00/00";

    /**
     * The value {@code WS-AUTH-TIME} carries at L54 before any authorization has been projected into it.
     *
     * <p>Assumptions: the counterpart of {@link #EMPTY_AUTH_DATE}, declared
     * {@code PIC X(08) VALUE '00:00:00'} at L54, and governed by the same L294 gate. Its separators are
     * colons where the date's are solidi, which is the one difference between the two literals and the
     * reason they are declared separately rather than derived from one pattern.
     */
    public static final String EMPTY_AUTH_TIME = "00:00:00";

    /**
     * The message the reference screen shows when a forward step is already at the oldest authorization.
     *
     * <p>Assumptions: carried character for character from L283, trailing ellipsis included. This is a
     * user-visible string, so its exact bytes are the contract and no re-wording, re-punctuation or
     * sentence-casing is applied to it.
     */
    public static final String LAST_AUTHORIZATION_REACHED = "Already at the last Authorization...";

    /**
     * The number of entries {@code WS-DECLINE-REASON-TABLE} declares.
     *
     * <p>Assumptions: ten, matching {@code OCCURS 10 TIMES} at L69 and the ten value clauses at L58 to
     * L67. The count is declared so that a test can assert the table has neither grown nor been thinned,
     * which matters because six of the ten entries are unreachable from the current producer and would
     * otherwise look removable. The reasoning is at {@link #declineReasonDescriptions()}.
     */
    public static final int DECLINE_REASON_ENTRY_COUNT = 10;

    /**
     * The width {@code DECL-DESC} declares at L73.
     *
     * <p>Assumptions: sixteen characters. The composed screen value truncates this to fifteen on its way
     * into a reference-modified receiver, and that truncation belongs to the composer in
     * {@link PendingAuthDetailResponse} rather than to this table, so the descriptions are held here at
     * their declared width and narrowed once at the point of composition.
     */
    public static final int DECLINE_DESCRIPTION_WIDTH = 16;

    /**
     * The response-reason display table, keyed by reason code, in the order L58 to L67 declares it.
     *
     * <p>Assumptions: every description is carried character for character from its value clause,
     * including the two the fixed-width screen forced into abbreviated spellings --
     * {@code 'INSUFFICNT FUND'} at L60 and {@code 'EXCED DAILY LMT'} at L63. Both are user-visible
     * strings, so expanding, correcting or re-spelling either would change what an operator reads.
     */
    private static final Map<String, String> DECLINE_REASON_DESCRIPTIONS = declineReasonTable();

    /**
     * The number of rows a forward step retrieves.
     *
     * <p>Assumptions: one, because {@code READ-NEXT-AUTH-RECORD} at L493 to L519 issues a single
     * unqualified get-next-within-parent and advances by exactly one authorization. No look-ahead row is
     * requested: a forward step reports end-of-data by returning nothing at all, which is how the
     * reference program's own L504 to L506 detect it, so a second row would be fetched and discarded.
     */
    private static final Limit NEXT_AUTHORIZATION_LIMIT = Limit.of(1);

    /**
     * The authorization rows this service reads.
     */
    private final PendingAuthDetailRepository details;

    /**
     * The only route from a persistent row to the body this context publishes.
     */
    private final PendingAuthViewMapper mapper;

    /**
     * Builds the service over its repository and the view mapper.
     *
     * @param details the authorization repository; must not be {@code null}
     * @param mapper the view mapper that redeems selectors and masks card numbers; must not be
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public PendingAuthDetailService(PendingAuthDetailRepository details,
            PendingAuthViewMapper mapper) {
        this.details = Objects.requireNonNull(details, "details must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Returns the pending authorization one sealed selector stands for.
     *
     * <p><strong>Purpose.</strong> The migrated form of {@code READ-AUTH-RECORD} at L431 to L492, reduced
     * to the single keyed retrieval this class's charter explains above.
     *
     * <p>Assumptions: the row is addressed by a THREE-column primary key -- the account together with the
     * decoded authorization date and time -- while the sealed selector a caller sends encodes only TWO of
     * those three, being the {@code PA-AUTHORIZATION-KEY} group at {@code cpy/CIPAUDTY.cpy} L19 whose
     * three-byte packed date at L20 and five-byte packed time at L21 make the reference key eight bytes.
     * The account is the third column and it arrives from the scope the selector was issued under rather
     * than from the selector's own eight bytes, exactly as the reference hierarchy supplied it from the
     * parent segment. The distinction matters because the two counts are easy to conflate: a reader who
     * took the eight-byte key for the whole primary key would look for an account inside it and find none,
     * and one who took the primary key for the token's contents would expect a selector to be redeemable
     * outside the account it was issued for.
     *
     * <p>Assumptions: a selector that redeems cleanly but names no row is NOT FOUND rather than refused.
     * The two conditions are different and are reported differently: a selector that cannot be redeemed is
     * the caller's to fix and is a refusal, while a selector that redeems to a key with no row behind it
     * describes an authorization the expiry sweep has since removed, which is nothing the caller can
     * correct. The reference program distinguishes them the same way, treating a segment-not-found status
     * at L471 to L473 as an end-of-data condition rather than as an error.
     *
     * @param selector the sealed selector taken from the {@code key} property of a list row; must not be
     *     {@code null}
     * @param subject the authenticated principal the selector was issued to; must not be {@code null},
     *     because a selector bound to no subject is redeemable by every other authorized operator
     * @return the authorization's full state with its primary account number masked to its last four
     *     digits, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws PendingAuthViewMapper.InvalidSelectorException if the selector cannot be redeemed
     * @throws NoSuchElementException if the selector redeems to a key that names no row
     */
    @Transactional(readOnly = true)
    public PendingAuthDetailView read(String selector, String subject) {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        PendingAuthDetailKey key = this.mapper.openKey(selector, subject);

        // WHY : Trade-offs: the not-found message names NEITHER the account nor the two clock values the
        //       selector redeemed to. A diagnostic naming them would be more useful to an operator and
        //       would also place an account identifier in a response body and a log line, which is the one
        //       destination the masking this context applies at its edge does not reach. The correlation
        //       identity on the response is the handle into server-side diagnostics instead.
        PendingAuthDetail detail = this.details.findById(key)
                .orElseThrow(() -> new NoSuchElementException(
                        "the selector names no pending authorization"));
        return this.mapper.toDetailView(detail, subject);
    }

    /**
     * Returns one pending authorization projected onto the composed, screen-shaped detail body.
     *
     * <p><strong>Purpose.</strong> The migrated form of {@code POPULATE-AUTH-DETAILS} at L291 to L359.
     * That paragraph both retrieves nothing and decides nothing; it projects an already-read segment onto
     * the map, which is why this method performs the same keyed read as {@link #read} and then differs
     * only in what it publishes.
     *
     * <p>Assumptions: the decline-reason lookup is performed HERE and its result is handed to the
     * projection, because the projection's contract asks its caller for an already-resolved description
     * and treats a {@code null} as the no-entry case. That division follows the reference program, where
     * the table and its search at L319 to L328 sit inside this paragraph while the composition of the
     * four-character code, the separator and the description is a property of the receiving field. The
     * consequence is that the {@code '9999'} and {@code 'ERROR'} pair the no-entry branch writes at L321
     * to L323 is applied by the projection rather than by this method, so this method returns the
     * description it found or nothing at all and never invents a substitute.
     *
     * @param selector the sealed selector taken from the {@code key} property of a list row; must not be
     *     {@code null}
     * @param subject the authenticated principal the selector was issued to; must not be {@code null}
     * @param context the six components of screen chrome no stored segment holds, being the transaction
     *     name, the two title bands, the program name, the instant the screen is rendered at and the
     *     message line; must not be {@code null}
     * @return the composed detail body, with the card number masked and the reason resolved through the
     *     display table, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws PendingAuthViewMapper.InvalidSelectorException if the selector cannot be redeemed
     * @throws NoSuchElementException if the selector redeems to a key that names no row
     */
    @Transactional(readOnly = true)
    public PendingAuthDetailResponse readForScreen(String selector, String subject,
            PendingAuthDetailMapper.ScreenContext context) {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(context, "context must not be null");
        PendingAuthDetailKey key = this.mapper.openKey(selector, subject);

        PendingAuthDetail detail = this.details.findById(key)
                .orElseThrow(() -> new NoSuchElementException(
                        "the selector names no pending authorization"));
        return PendingAuthDetailMapper.toResponse(
                detail, declineDescriptionFor(detail.getAuthRespReason()), context);
    }

    /**
     * Resolves one response-reason code to the description the reference display table carries for it.
     *
     * <p><strong>Purpose.</strong> The migrated form of the {@code SEARCH ALL WS-DECLINE-REASON-TAB} at
     * L319 to L328, which the reference program runs on EVERY authorization rather than only on declined
     * ones -- the search is unconditional and the approval code {@code '0000'} is itself a table entry at
     * L58.
     *
     * <p>Refactoring Rationale: the reference lookup is a binary search over a table declared
     * {@code ASCENDING KEY IS DECL-CODE} at L70 and indexed at L71; this is a hash lookup. The declared
     * ascending order is nonetheless retained in the table's construction, because that order is what
     * makes the ten entries auditable line by line against L58 to L67 -- not because the lookup needs it.
     * The consequence of the change is confined to that: an entry inserted out of order would break the
     * reference search and would not break this one, which is why the order is asserted by a test rather
     * than relied on at run time.
     *
     * <p>Assumptions: a code with no entry yields {@code null}, which is the signal the projection's
     * contract defines for the no-entry case, and is how the {@code AT END} branch at L320 to L323
     * reaches its {@code '9999'} and {@code 'ERROR'} pair. Returning a substitute description from here
     * instead would put a resolved-looking value where the reference wrote a distinct pair, and the
     * caller could no longer tell a resolved reason from an unresolved one.
     *
     * @param reasonCode the four-character response reason as stored,
     *     {@code PA-AUTH-RESP-REASON PIC X(04)} at {@code cpy/CIPAUDTY.cpy} L32, which may be
     *     {@code null} or blank when the segment carries none
     * @return the description the table holds for {@code reasonCode} at its declared sixteen-character
     *     width, or {@code null} when the code is absent, blank or not in the table
     */
    public static String declineDescriptionFor(String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return null;
        }

        // WHY : Assumptions: the stored field is fixed-width and space-padded, so a code arriving from the
        //       segment may carry trailing blanks that the table's four-character keys do not. Trimming
        //       before the lookup is what makes a padded 'a code plus blanks' find its entry; comparing
        //       the raw value would miss every entry and send every authorization down the no-entry
        //       branch, which is a failure that produces a plausible screen rather than an error.
        return DECLINE_REASON_DESCRIPTIONS.get(reasonCode.trim());
    }

    /**
     * Returns the ten-entry response-reason display table, keyed by code and in its declared order.
     *
     * <p><strong>Purpose.</strong> Publishes {@code WS-DECLINE-REASON-TABLE} at L57 to L73 so that the
     * table's completeness can be asserted rather than assumed.
     *
     * <p>Assumptions: the table is a SUPERSET BY SIX of what the current producer can emit, and it is
     * kept whole for that reason. {@code 6000-MAKE-DECISION} in
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl} at L657 to L734 pre-sets {@code '0000'}
     * at L698 and gates every other assignment behind {@code IF AUTH-RESP-DECLINED} at L699, so an
     * approval always keeps {@code '0000'}; the branches it can then reach are {@code '3100'} at L701 to
     * L704, {@code '4100'} at L706 and {@code '9000'} from its {@code WHEN OTHER} at L716. That is four
     * producible codes. The four ladder branches {@code '4200'} at L708, {@code '4300'} at L710,
     * {@code '5100'} at L712 and {@code '5200'} at L714 are unreachable given the available-credit fork
     * that decides the declines, and the table additionally carries {@code '4400'} at L63 and
     * {@code '5300'} at L66, which that paragraph never assigns at all.
     *
     * <p>Alternatives Considered: pruning the six entries no current producer emits, which would leave a
     * table of four and a smaller surface to maintain. Rejected because the table is a DISPLAY table on
     * the read side of a queue, and the producer is a separate deployable that can be changed without
     * this one: a code arriving from a later producer would then fall to the no-entry branch and render
     * as {@code '9999'} and {@code 'ERROR'}, losing a description the reference application had all
     * along. Keeping all ten costs six map entries and preserves that rendering.
     *
     * @return an unmodifiable view of the display table, iterating in the L58 to L67 declaration order,
     *     never {@code null} and always holding {@link #DECLINE_REASON_ENTRY_COUNT} entries
     */
    public static Map<String, String> declineReasonDescriptions() {
        return DECLINE_REASON_DESCRIPTIONS;
    }

    /**
     * Steps forward from one authorization to the next one within the same account.
     *
     * <p><strong>Purpose.</strong> The migrated form of {@code READ-NEXT-AUTH-RECORD} at L493 to L519,
     * whose unqualified get-next-within-parent at L495 to L498 advances the child chain by one, and whose
     * L504 to L506 turn a segment-not-found or end-of-database status into the end-of-data flag its caller
     * reads at L281.
     *
     * <p>Assumptions: "next" means the next OLDER authorization, and establishing that requires the key
     * encoding rather than the verb. {@code cbl/COPAUA0C.cbl} stores the key NINES-COMPLEMENTED --
     * {@code COMPUTE PA-AUTH-DATE-9C = 99999 - WS-YYDDD} at L874 and
     * {@code COMPUTE PA-AUTH-TIME-9C = 999999999 - WS-TIME-WITH-MS} at L875 -- so the ascending chain
     * order that a get-next walks is DESCENDING chronology. This schema stores the DECODED values
     * instead, so the same traversal is expressed as a row-value comparison STRICTLY LESS THAN the current
     * position under a descending order, which is exactly the predicate the repository declares. A
     * greater-than comparison, or an ascending order, would return real rows in a plausible order while
     * paging backward through history: nothing would error and no constraint would be violated, the era
     * returned would simply be the wrong one.
     *
     * <p>Assumptions: the two key components are compared as a PAIR and never independently, because a
     * date-only comparison would either skip every remaining authorization that shares the boundary date
     * or return one already shown. The repository owns that predicate; this method owns only the decision
     * to step by one and the end-of-data outcome.
     *
     * <p>Trade-offs: this returns an outcome object carrying either the next authorization or the
     * end-of-data message, rather than returning {@code null} or raising when the chain is exhausted.
     * Exhaustion is an ordinary result of stepping forward -- the reference program treats it as a
     * message and leaves the current screen in place rather than as an error -- so a raise would turn a
     * normal outcome into an exceptional one, and a {@code null} would carry the verbatim message
     * nowhere.
     *
     * <p>⚠️ Assumptions: an EXHAUSTED chain and an ABSENT ANCHOR are two different outcomes and are
     * reported differently -- the first as the end-of-data message on a 200, the second as the same
     * not-found condition {@link #read} raises. The distinction belongs to the caller: exhaustion means
     * the authorization it is showing is the oldest one, while an absent anchor means the authorization it
     * is showing no longer exists, and a client told the first when the second is true keeps a stale row
     * on display with nothing to tell it to refresh.
     *
     * @param selector the sealed selector of the authorization currently being shown; must not be
     *     {@code null}
     * @param subject the authenticated principal the selector was issued to; must not be {@code null}
     * @return the next older authorization, or an end-of-data outcome carrying
     *     {@link #LAST_AUTHORIZATION_REACHED}, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws PendingAuthViewMapper.InvalidSelectorException if the selector cannot be redeemed
     * @throws NoSuchElementException if the selector redeems to a key that names no row, which is the
     *     anchor this step would advance from
     */
    @Transactional(readOnly = true)
    public NextAuthorization readNext(String selector, String subject) {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        PendingAuthDetailKey key = this.mapper.openKey(selector, subject);

        // WHY : ⚠️ Refactoring Rationale: the anchor is READ before the step, and it previously was not.
        //       Without it this method stepped from a position that need not exist: a selector whose row
        //       the expiry sweep had removed was answered 200 with the next older authorization, while the
        //       two sibling reads on the same selector answered 404. A caller therefore received a
        //       plausible authorization it had not asked for, with nothing in the answer to say that the
        //       one it did ask for was gone -- and the 404 the document publishes for this route was
        //       unreachable, because the only other outcome is the end-of-data arm below.
        // WHY : ⚠️ Assumptions: this is faithful to the reference rather than merely contract-driven.
        //       READ-NEXT-AUTH-RECORD at L493 to L519 issues an unqualified get-next, which advances from
        //       the position an earlier successful get-unique established; there is no position to advance
        //       from when that retrieval found nothing, so the reference cannot reach its get-next with a
        //       missing anchor either.
        // WHY : ⚠️ Trade-offs: one extra primary-key lookup per forward step, inside the same read-only
        //       transaction. The alternative -- inferring the anchor's existence from the successor query
        //       -- cannot distinguish an anchor the sweep removed from an anchor that is genuinely the
        //       oldest row, and those two states must answer 404 and 200 respectively.
        // WHY : ⚠️ Assumptions: the diagnostic is the SAME sentence #read raises, deliberately, so the
        //       three reads of one selector are indistinguishable in their absence reporting; it names
        //       neither the account nor the clock values for the reason recorded on that method.
        this.details.findById(key)
                .orElseThrow(() -> new NoSuchElementException(
                        "the selector names no pending authorization"));

        List<PendingAuthDetail> next = this.details.findOlderThan(
                key.getAccountId(), key.getAuthDate(), key.getAuthTime(), NEXT_AUTHORIZATION_LIMIT);
        if (next.isEmpty()) {
            return new NextAuthorization(null, true, LAST_AUTHORIZATION_REACHED);
        }
        return new NextAuthorization(this.mapper.toDetailView(next.get(0), subject), false, null);
    }

    /**
     * Renders an amount through this screen's twelve-character money edit mask.
     *
     * <p><strong>Purpose.</strong> The migrated form of the two moves at L308 to L309, which take
     * {@code PA-APPROVED-AMT} into {@code WS-AUTH-AMT} and then onto the map. The amount published is the
     * APPROVED one and not the requested one; the two differ on a declined authorization, so rendering the
     * requested amount would show a decline as though it had gone through.
     *
     * <p>Assumptions: the mask is {@link #SCREEN_AMOUNT_MASK}, declared at L52, and the fourteen-character
     * form at {@code cbl/COPAUA0C.cbl} L66 is a different field on a different path. Crossing them is the
     * specific hazard this method exists to prevent, because both produce a well-formed money string and
     * the wrong one is wrong only in which column each digit lands in.
     *
     * <p>Assumptions: money stays exact fixed point throughout. The value arrives as a scale-two decimal
     * from the shared money type and is rendered from its unscaled digits, so no binary floating-point
     * type appears on this path at any point; a rendering that went through a double would be exact for
     * small amounts and silently wrong for large ones.
     *
     * <p>Assumptions: the sign position emits a minus for a negative amount and a SPACE otherwise, which
     * is what a single leading {@code -} sign control does. A mask written with a leading {@code +} would
     * emit a plus for a non-negative amount instead, and this field is not that mask.
     *
     * <p>Alternatives Considered: reusing the edit-mask renderer the reporting context already owns.
     * Rejected because it is a type in a sibling SERVICE module, and a dependency from one service module
     * on another is forbidden and asserted against by the shared layering test, so importing it is not
     * available regardless of its fit. The rendering is therefore expressed here for this one field, and
     * it follows the same overflow ruling so that the two contexts agree in behaviour without sharing
     * code.
     *
     * <p>Alternatives Considered: reproducing the reference truncation, in which moving a ten-digit value
     * into an eight-position mask silently discards the two high-order digits. Rejected in favour of
     * raising, which is the ruling already recorded as D-EDIT-MASK-OVERFLOW in
     * {@code docs/architecture/cobol-to-service-traceability.md}. A truncated amount is unrecoverable
     * downstream because the rendered string carries no evidence of the digits it lost, so an operator
     * would read a hundred-million-unit authorization as a smaller one with no indication anything had
     * happened. The guard is reachable only because the shared money type admits ten integer digits where
     * this mask provides eight.
     *
     * @param amount the approved amount to render, {@code PA-APPROVED-AMT PIC S9(10)V99 COMP-3} at
     *     {@code cpy/CIPAUDTY.cpy} L35; must not be {@code null}
     * @return the edited amount in exactly {@link #SCREEN_AMOUNT_WIDTH} characters, never {@code null}
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws ArithmeticException if {@code amount} does not carry exactly two decimal places, or if its
     *     magnitude needs more than {@link #SCREEN_AMOUNT_INTEGER_DIGITS} integer digits
     */
    public static String renderScreenAmount(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");

        String digits = screenMagnitudeDigits(amount);
        StringBuilder edited = new StringBuilder(SCREEN_AMOUNT_WIDTH);
        edited.append(amount.isNegative() ? '-' : ' ');
        edited.append(suppressLeadingZeros(digits));
        edited.append('.');
        edited.append(digits, SCREEN_AMOUNT_INTEGER_DIGITS, digits.length());

        String rendered = edited.toString();
        if (rendered.length() != SCREEN_AMOUNT_WIDTH) {
            throw new ArithmeticException("rendering " + amount.amount().toPlainString()
                    + " through the mask " + SCREEN_AMOUNT_MASK + " produced "
                    + rendered.length() + " characters where the field declares "
                    + SCREEN_AMOUNT_WIDTH);
        }
        return rendered;
    }

    /**
     * Applies the originating-date field's own initial value wherever no date was projected into it.
     *
     * <p><strong>Purpose.</strong> The migrated form of the empty state that L53 establishes and that the
     * {@code IF ERR-FLG-OFF} gate at L294 preserves. L297 to L301 slice the six stored characters and move
     * the recomposed group into {@code WS-AUTH-DATE}; on every path where that move does not happen the
     * field still holds the {@code VALUE '00/00/00'} its declaration gave it, and that literal is what the
     * screen displayed.
     *
     * <p>Refactoring Rationale: this takes the ALREADY-RENDERED value rather than the stored authorization,
     * so the month-first re-ordering L297 to L300 performs is not written a second time here. The
     * projection mapper owns that rule for every consumer of it, and a re-ordering duplicated in two places
     * could come to disagree -- a date read year-first when it is month-first is entirely well-formed and
     * wrong, so the rule is kept in one place and this method decides only between a rendered value and the
     * field's initial one.
     *
     * @param renderedDate the eight-character month-first form the projection produced, or {@code null}
     *     when the authorization carries no originating date to render
     * @return {@code renderedDate} when it carries a value, otherwise {@link #EMPTY_AUTH_DATE}, never
     *     {@code null}
     */
    public static String screenAuthDate(String renderedDate) {
        return renderedDate == null || renderedDate.isBlank() ? EMPTY_AUTH_DATE : renderedDate;
    }

    /**
     * Applies the originating-time field's own initial value wherever no time was projected into it.
     *
     * <p><strong>Purpose.</strong> The counterpart of {@link #screenAuthDate} for {@code WS-AUTH-TIME},
     * whose initial value L54 declares and whose three moves at L303 to L305 write only positions one to
     * two, four to five and seven to eight -- the colons at positions three and six come from the
     * declaration itself. Where those moves do not happen the field reads {@code '00:00:00'}.
     *
     * <p>Assumptions: the stored order and the displayed order AGREE for the time, where they do not agree
     * for the date. That is stated because the two methods look interchangeable and are not: generalising
     * the date's re-ordering onto the time would corrupt the time, and generalising the time's
     * pass-through onto the date would corrupt the date.
     *
     * @param renderedTime the eight-character colon-separated form the projection produced, or
     *     {@code null} when the authorization carries no originating time to render
     * @return {@code renderedTime} when it carries a value, otherwise {@link #EMPTY_AUTH_TIME}, never
     *     {@code null}
     */
    public static String screenAuthTime(String renderedTime) {
        return renderedTime == null || renderedTime.isBlank() ? EMPTY_AUTH_TIME : renderedTime;
    }

    /**
     * Reduces an amount to the zero-padded magnitude digits this screen's mask consumes.
     *
     * <p>Assumptions: the scale is asserted rather than adjusted. The shared money type holds its value
     * invariantly at two decimal places and this mask declares exactly two, so checking the agreement here
     * makes a future change to that invariant fail loudly instead of quietly emitting a string of the right
     * length with the decimal point in the wrong column.
     *
     * <p>Alternatives Considered: reading the magnitude from the money type's whole-cents accessor rather
     * than from its decimal value. Rejected because that accessor returns a primitive integral type, and
     * negating a primitive integral type has a documented edge at its own minimum where the result stays
     * negative; taking the magnitude as an arbitrary-precision integer has no such edge and yields the
     * digit characters directly.
     *
     * @param amount the amount to reduce; already rejected as {@code null} by the calling method
     * @return the magnitude of {@code amount} as exactly {@link #SCREEN_AMOUNT_INTEGER_DIGITS} plus two
     *     digit characters, left-padded with zeros and carrying no sign, never {@code null}
     * @throws ArithmeticException if {@code amount} does not carry exactly two decimal places, or if its
     *     magnitude needs more than {@link #SCREEN_AMOUNT_INTEGER_DIGITS} integer digits
     */
    private static String screenMagnitudeDigits(Money amount) {
        BigDecimal value = amount.amount();
        if (value.scale() != Money.SCALE) {
            throw new ArithmeticException("amount " + value.toPlainString() + " carries scale "
                    + value.scale() + " and the mask " + SCREEN_AMOUNT_MASK + " declares "
                    + Money.SCALE);
        }

        String digits = value.unscaledValue().abs().toString();
        int declared = SCREEN_AMOUNT_INTEGER_DIGITS + Money.SCALE;
        if (digits.length() > declared) {
            throw new ArithmeticException("amount " + value.toPlainString() + " needs "
                    + (digits.length() - Money.SCALE) + " integer digits and the mask "
                    + SCREEN_AMOUNT_MASK + " provides " + SCREEN_AMOUNT_INTEGER_DIGITS);
        }
        return "0".repeat(declared - digits.length()) + digits;
    }

    /**
     * Blanks the leading zeros of the integer region the way the mask's suppression positions do.
     *
     * <p>Assumptions: suppression covers {@link #SCREEN_AMOUNT_SUPPRESSED_DIGITS} positions and stops one
     * short of the decimal point, because the mask's final integer position is a mandatory digit. That one
     * position is why a zero amount renders as a zero rather than as an empty integer region, and it is the
     * reason this loop is bounded by the suppression count and not by the integer-digit count.
     *
     * <p>Assumptions: no grouping separators are inserted. The mask declares seven consecutive suppression
     * positions with nothing between them, so adding thousands separators would return more characters
     * than the twelve-position field can hold.
     *
     * @param digits the zero-padded magnitude digits, integer region first, as produced by
     *     {@link #screenMagnitudeDigits}
     * @return the integer region in exactly {@link #SCREEN_AMOUNT_INTEGER_DIGITS} characters, with leading
     *     zeros replaced by spaces, never {@code null}
     */
    private static String suppressLeadingZeros(String digits) {
        StringBuilder region = new StringBuilder(digits.substring(0, SCREEN_AMOUNT_INTEGER_DIGITS));
        for (int position = 0; position < SCREEN_AMOUNT_SUPPRESSED_DIGITS; position++) {
            if (region.charAt(position) != '0') {
                break;
            }
            region.setCharAt(position, ' ');
        }
        return region.toString();
    }

    /**
     * Builds the response-reason display table in the order its value clauses declare it.
     *
     * <p>Assumptions: each entry is one value clause split at its fourth character, exactly as the
     * {@code REDEFINES} at L68 to L73 splits the twenty declared characters into a four-character
     * {@code DECL-CODE} and a sixteen-character {@code DECL-DESC}. The descriptions are written here at
     * their declared width, trailing blanks included, so that the table's own widths match L73 and the
     * narrowing to fifteen characters happens once at the point of composition rather than twice.
     *
     * <p>Assumptions: an insertion-ordered map is used so the iteration order is the L58 to L67
     * declaration order, which is also the ascending code order that {@code ASCENDING KEY IS DECL-CODE} at
     * L70 requires of the reference table. Preserving it keeps the ten entries auditable against the source
     * lines one for one.
     *
     * <p>Alternatives Considered: sealing the result with {@code Map.copyOf}, which is the shorter way to
     * obtain an unmodifiable map and was written here first. Rejected because that factory makes no
     * ordering guarantee at all -- its iteration order is unspecified and in practice is neither insertion
     * nor ascending -- so it silently discarded the declaration order this method exists to preserve and
     * left the table unauditable against L58 to L67. Wrapping the insertion-ordered map keeps both
     * properties, immutability and order, and the order is asserted by a test so the shorter factory cannot
     * be reinstated unnoticed.
     *
     * @return a fixed, unmodifiable, insertion-ordered map of the ten declared entries, never {@code null}
     */
    private static Map<String, String> declineReasonTable() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("0000", pad("APPROVED"));
        table.put("3100", pad("INVALID CARD"));
        table.put("4100", pad("INSUFFICNT FUND"));
        table.put("4200", pad("CARD NOT ACTIVE"));
        table.put("4300", pad("ACCOUNT CLOSED"));
        table.put("4400", pad("EXCED DAILY LMT"));
        table.put("5100", pad("CARD FRAUD"));
        table.put("5200", pad("MERCHANT FRAUD"));
        table.put("5300", pad("LOST CARD"));
        table.put("9000", pad("UNKNOWN"));
        return Collections.unmodifiableMap(table);
    }

    /**
     * Space-pads one description to the width its picture declares.
     *
     * <p>Assumptions: this is the alphanumeric fill rule and nothing more. A value clause shorter than the
     * twenty declared characters leaves the remainder of {@code DECL-DESC} blank, so padding here is what
     * makes each table entry the sixteen characters L73 declares instead of the length of the literal
     * somebody typed.
     *
     * @param description the description literal exactly as its value clause spells it
     * @return {@code description} in exactly {@link #DECLINE_DESCRIPTION_WIDTH} characters, never
     *     {@code null}
     */
    private static String pad(String description) {
        return description + " ".repeat(DECLINE_DESCRIPTION_WIDTH - description.length());
    }

    /**
     * The result of stepping forward from one authorization to the next within an account.
     *
     * <p>Assumptions: exactly one of the two states is populated. When {@code endOfData} is false the
     * authorization is present and the message is absent; when it is true the authorization is absent and
     * the message is the verbatim literal from L283. Modelling both in one type mirrors the reference
     * program, whose forward step sets either the end-of-data flag its L281 reads or a new current
     * authorization, never both.
     *
     * @param authorization the next older authorization, or {@code null} when the chain is exhausted
     * @param endOfData whether the step found no further authorization, being the migrated form of the
     *     {@code AUTHS-EOF} condition L504 to L506 set
     * @param message the verbatim message the screen shows on exhaustion, or {@code null} when an
     *     authorization was found
     */
    public record NextAuthorization(PendingAuthDetailView authorization, boolean endOfData,
            String message) {

        /**
         * Rejects any combination the reference program's forward step cannot produce.
         *
         * <p>Assumptions: the two states are checked rather than trusted, because a caller reading
         * {@code authorization()} without first reading {@code endOfData()} would otherwise receive a
         * {@code null} with no indication of why. Refusing the impossible combinations at construction
         * means every instance that exists describes one of the two outcomes L504 to L506 can reach.
         *
         * @param authorization the next older authorization, or {@code null} when the chain is exhausted
         * @param endOfData whether the step found no further authorization
         * @param message the verbatim exhaustion message, or {@code null} when an authorization was found
         * @throws IllegalArgumentException if an authorization is present alongside the end-of-data flag,
         *     or if neither an authorization nor the end-of-data flag is present
         */
        public NextAuthorization {
            if (endOfData && authorization != null) {
                throw new IllegalArgumentException(
                        "an exhausted forward step carries no authorization");
            }
            if (!endOfData && authorization == null) {
                throw new IllegalArgumentException(
                        "a forward step that found an authorization must carry it");
            }
        }
    }
}
