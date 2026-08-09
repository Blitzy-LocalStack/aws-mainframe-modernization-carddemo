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
 * to L243 and {@code UPDATE-AUTH-DETAILS} at L520 to L556, and
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} {@code MAIN-PARA} at L89 to L220 and
 * {@code FRAUD-UPDATE} at L221 to L244. Every citation below is relative to that tree, which is
 * reference material this migration reads and never modifies.
 *
 * <p>Refactoring Rationale: this is ONE local transaction where the reference system needed a genuine
 * two-phase commit across two resource managers. The reference binding is explicit --
 * {@code csd/CRDDEMO2.csd} L69 to L74 defines the Db2 entry with rollback enabled at its L71 and L75 to
 * L79 attaches Db2 to one transaction only -- while the IMS side is replaced at
 * {@code cbl/COPAUS1C.cbl} L525 to L528, and the single commit point for both is the lone syncpoint in
 * the caller at L557 to L559 with the rollback at L565 to L568. Both rows now live in one PostgreSQL
 * schema, so the distributed transaction is ELIMINATED rather than emulated. This is documented
 * divergence D-6 in {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Refactoring Rationale: the write sets the state the request NAMES where the reference program
 * toggles the state it finds -- {@code cbl/COPAUS1C.cbl} L234 re-reads the segment and L236 to L241
 * inverts it, taking no action argument at all. That is documented divergence
 * D-AUTH-FRAUD-TARGET-STATE, and the reason is the transport: a request carrying a target state is
 * idempotent under the retries a network introduces, whereas a toggle reversed by a retry removes a
 * fraud tag the operator asked for and reports success either way.
 *
 * <p>Assumptions: this service performs NO authority check. The administrative authority the fraud
 * route requires is declared once, in {@code config/SecurityConfig.java}, against the path prefix
 * {@code FRAUD_PATH_PATTERN}. A second check here would be a second place the rule could differ from
 * the published contract, and a reader could no longer tell which of the two decided a refusal.
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
     * @throws IllegalStateException if the named row carries an original date the composed fraud key
     *     cannot be built from
     */
    @Transactional
    public FraudMarkOutcome mark(String selector, FraudMarkRequest request, String subject) {
        Objects.requireNonNull(selector, "selector must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(subject, "subject must not be null");

        PendingAuthDetailKey key = this.mapper.openKey(selector, subject);

        // WHY : Assumptions: the row is re-read by KEY and no lock mode is requested, because the
        //       reference programs hold nothing between a read and the write that follows it. The detail
        //       screen re-reads the segment at cbl/COPAUS1C.cbl L233 through READ-AUTH-RECORD before the
        //       replace at L525 to L528, but every retrieval it uses is a NON-HOLD form: cpy/IMSFUNCS.cpy
        //       declares the three get-hold codes at L19, L21 and L23 and no program in the reference tree
        //       passes any of them to a retrieval. Requesting a pessimistic lock here would therefore add
        //       lock-wait queueing and deadlock-victim rollback to a path that has neither today, which is
        //       new behaviour rather than preserved behaviour, and the package charter records it as the
        //       rejected alternative on that boundary.
        // WHY : Alternatives Considered: serialising two concurrent marks of one authorization on this read,
        //       so that the probe below could never see an absent fraud row twice. Rejected because the
        //       reference system does not prevent that collision either -- it lets the duplicate key fire and
        //       branches on it, inserting at cbl/COPAUS2C.cbl L199 and taking PERFORM FRAUD-UPDATE at L203
        //       and L204 on the duplicate-key code, with the update itself at L221 to L229. The fraud table's
        //       primary key is therefore the arbiter of a concurrent duplicate here as well; the consequence
        //       accepted is that of two simultaneous marks of the SAME authorization one is rejected rather
        //       than both being applied, and because both would assert the same fraud state the committed
        //       state of the row is the same either way.
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
        //       reads it" -- because the reference system dates both of its writes from the database
        //       server, at cbl/COPAUS2C.cbl L194 and L225. The two sources are not interchangeable: a
        //       container's clock, its configured zone and the database session's zone are three
        //       independent settings, so a report raised either side of midnight could be dated a day
        //       apart from the value the reference would have written, on exactly the field an
        //       investigator filters by.
        LocalDate today = this.fraudRows.currentDate();
        String segmentDate = SEGMENT_REPORT_DATE.format(today);

        // WHY : Assumptions: the two rows are written in the reference ORDER -- the fraud row first, then
        //       the segment -- because the reference program only replaces the segment when the fraud write
        //       reported success, testing that at cbl/COPAUS1C.cbl L255 and taking its rollback path at
        //       L258 otherwise. Both writes are inside one transaction here, so the order no longer decides
        //       what survives a failure; it is preserved because a reader comparing the two systems should
        //       find the same sequence, and because a failure of the fraud write still leaves the segment
        //       untouched in memory as well as uncommitted.
        boolean created = writeFraudRow(detail, key, request, today);
        applyStateToDetail(detail, request.action(), segmentDate);

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
     * @param detail the authorization being marked, re-read by key in this transaction; never
     *     {@code null}
     * @param request the validated request body; never {@code null}
     * @param today the server date both report dates are taken from; never {@code null}
     * @param key the row identity the sealed selector redeemed to, whose leading component is the
     *     account the fraud row records; never {@code null}
     * @return {@code true} when a row was created, {@code false} when an existing row was replaced
     * @throws IllegalStateException if the authorization's original date cannot be composed into the
     *     fraud key's timestamp
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
     * <p>Assumptions: the segment carries its OWN copy of the fraud state and report date, at
     * {@code cpy/CIPAUDTY.cpy} L50 and L53, and the reference system writes both -- the fraud program sets
     * them on its copy of the record at {@code cbl/COPAUS2C.cbl} L101 and L137, and the caller moves that
     * copy back over the segment at {@code cbl/COPAUS1C.cbl} L520 before replacing it. The duplication is
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
