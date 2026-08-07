package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.FraudMarkRequest;
import com.carddemo.authorization.dto.FraudMarkResponse;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.AuthFraudRepository;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
     * The number of characters the acquirer-supplied original date carries.
     *
     * <p>Assumptions: six, from {@code PA-AUTH-ORIG-DATE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L22, and
     * the three two-character slices the reference takes of it at {@code cbl/COPAUS2C.cbl} L103 to
     * L105.</p>
     */
    private static final int ORIG_DATE_LENGTH = 6;

    /**
     * The century a two-digit year of 70 or above resolves into.
     */
    private static final int TWENTIETH_CENTURY = 1900;

    /**
     * The century a two-digit year below 70 resolves into.
     */
    private static final int TWENTY_FIRST_CENTURY = 2000;

    /**
     * The two-digit year at and above which the earlier century is chosen.
     *
     * <p>Refactoring Rationale: the pivot is 70, and it is stated here because the migration's schema
     * comment requires it to be named exactly once, "outside the schema", so that a stored value is
     * unambiguous rather than left for every reader to guess. It is not invented: the reference write
     * hands its assembled string to Db2's {@code TIMESTAMP_FORMAT} under the mask
     * {@code 'YY-MM-DD HH24.MI.SSNNNNNN'} at {@code cbl/COPAUS2C.cbl} L171 to L172 and L227 to L228, and
     * that function's documented window for a two-digit year is 1970 through 2069. Choosing any other
     * pivot would make the target's composed timestamp differ from the reference's for the same input,
     * which is the one thing this key must not do -- it is the primary key both systems address the row
     * by.
     *
     * <p>Assumptions: the pivot applies to THIS field only and must not be reused for the card expiry.
     * The original date is a PAST value while an expiry is a FUTURE one, so no single pivot serves both;
     * the authorization fixture contract test records that asymmetry independently.</p>
     */
    private static final int CENTURY_PIVOT = 70;

    /**
     * The divisor that separates the millisecond component from the clock half of the decoded time key.
     *
     * <p>Assumptions: one thousand, because the writer multiplies the clock time by one thousand and adds
     * the milliseconds at {@code cbl/COPAUA0C.cbl} L871 to L872.</p>
     */
    private static final int MILLIS_PER_SECOND = 1000;

    /**
     * The divisor that separates one two-digit clock field from the next.
     *
     * <p>Assumptions: one hundred, and NOT sixty. The clock half of this key is the POSITIONAL form
     * {@code HHMMSS} -- {@code cbl/COPAUA0C.cbl} L868 moves the six characters {@code FORMATTIME}
     * produced into a numeric field, so the digits sit side by side -- and the reference reader splits it
     * exactly that way, taking two characters at a time at {@code cbl/COPAUS2C.cbl} L108 to L110 and
     * three for the milliseconds at L111. Dividing by sixty would read 104530 as 29 hours rather than as
     * half past ten, and the composed key would then name a different row than the reference writes.</p>
     */
    private static final int CLOCK_FIELD_MODULUS = 100;

    /**
     * The number of nanoseconds in one millisecond.
     */
    private static final int NANOS_PER_MILLI = 1_000_000;

    /**
     * The authorization rows, read under a pessimistic lock and updated in place.
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
     * The clock both report dates are taken from.
     */
    private final Clock clock;

    /**
     * Builds the service over its two repositories, the view mapper and the clock.
     *
     * @param details the authorization repository; must not be {@code null}
     * @param summaries the parent-summary repository the customer identifier is read from; must not be
     *     {@code null}
     * @param fraudRows the fraud-row repository; must not be {@code null}
     * @param mapper the view mapper that redeems the path selector; must not be {@code null}
     * @param clock the clock the report date is read from; must not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public FraudMarkingService(PendingAuthDetailRepository details,
            PendingAuthSummaryRepository summaries, AuthFraudRepository fraudRows,
            PendingAuthViewMapper mapper, Clock clock) {
        this.details = Objects.requireNonNull(details, "details must not be null");
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.fraudRows = Objects.requireNonNull(fraudRows, "fraudRows must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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

        // WHY : Assumptions: the row is read under a PESSIMISTIC WRITE lock, which is the migrated form of
        //       the reference re-read for update -- cbl/COPAUS1C.cbl L233 performs READ-AUTH-RECORD, whose
        //       retrievals run against an update-capable program communication block, before the replace at
        //       L525 to L528. The lock also makes the probe below safe: two requests naming one
        //       authorization serialise here, so they cannot both observe an absent fraud row and both take
        //       the insert path, where the second would hit a primary-key violation that inside one
        //       transaction is unrecoverable rather than retryable.
        PendingAuthDetail detail = this.details.findWithLockById(key)
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
        LocalDate today = LocalDate.now(this.clock);
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

        return new FraudMarkOutcome(
                created ? FraudMarkResponse.added() : FraudMarkResponse.updated(), created);
    }

    /**
     * Inserts the fraud row, or replaces the state on the one already there.
     *
     * @param detail the authorization being marked, already locked; never {@code null}
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

        LocalDateTime authTs = composeAuthTimestamp(detail);
        AuthFraudKey fraudKey = new AuthFraudKey(detail.getCardNum(), authTs);
        Optional<AuthFraud> existing = this.fraudRows.findById(fraudKey);

        if (existing.isPresent()) {
            // WHY : Assumptions: only the two columns the reference UPDATE names are touched -- its L222 to
            //       L225 set the indicator and the current date and nothing else -- so the twenty-four-column
            //       snapshot the row took when it was first inserted is deliberately left as it was. A row
            //       replaced here therefore still describes the authorization as it stood at the first
            //       report, which is the property that makes taking the snapshot worth anything.
            existing.get().applyState(request.action(), today);
            return false;
        }

        Long customerId = this.summaries.findByAccountId(key.getAccountId())
                .orElseThrow(() -> new NoSuchElementException(
                        "the authorization's account has no pending-authorization summary, so the fraud"
                                + " row's customer identifier cannot be read"))
                .getCustomerId();
        this.fraudRows.save(AuthFraud.from(detail, authTs, key.getAccountId(), customerId,
                request.action(), today));
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
     * @param detail the authorization being marked, already locked; never {@code null}
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
     * Composes the fraud key's timestamp from the authorization's original date and its time key.
     *
     * <p>Assumptions: the composition is the reference one and its two halves come from DIFFERENT sources,
     * which is worth knowing before anyone treats the result as a single trustworthy instant. The date
     * part is sliced year, month then day out of the ACQUIRER-supplied original date at
     * {@code cbl/COPAUS2C.cbl} L103 to L105; the time part is the SERVER-derived key, decoded from its
     * nines complement at L107 and split into hours, minutes, seconds and milliseconds at L108 to L111.
     * The key this repository stores is already decoded, so no complement arithmetic is repeated here.</p>
     *
     * <p>Assumptions: the three low fractional digits are always zero BY CONSTRUCTION and not by accident.
     * The reference assembles twenty-three characters ending in a three-digit millisecond field followed
     * by a literal {@code '000'} at {@code cbl/COPAUS2C.cbl} L38 to L51, and reads them back as six
     * fractional digits, so half the declared microsecond precision is literal padding. Nobody computing
     * a duration from two such values should read precision into them.</p>
     *
     * @param detail the authorization being marked; never {@code null}
     * @return the composed timestamp forming the second half of the fraud row's key, never {@code null}
     * @throws IllegalStateException if the original date is absent, is not six characters, or does not
     *     parse as a calendar date, or if the time key is absent
     */
    private static LocalDateTime composeAuthTimestamp(PendingAuthDetail detail) {
        String origDate = detail.getAuthOrigDate();
        Integer timeKey = detail.getId().getAuthTime();

        // WHY : Assumptions: an unparseable original date is a 500 and not a 400, and it IS reachable: the
        //       migration records that this column is acquirer-supplied and must tolerate a value that is
        //       blank or will not parse, which is why it stores characters rather than a date. The caller
        //       of this operation supplied none of it, so reporting the condition as the caller's mistake
        //       would be wrong; the reference outcome is the same class of failure, since its own composed
        //       string reaches Db2's conversion function and comes back as a system error that the caller
        //       rolls back and reports at cbl/COPAUS1C.cbl L256 to L258.
        if (origDate == null || origDate.length() != ORIG_DATE_LENGTH) {
            throw new IllegalStateException(
                    "the authorization carries no six-character original date, so the fraud row's"
                            + " composed key cannot be built");
        }
        Objects.requireNonNull(timeKey, "the authorization carries no authorization time key");

        int year;
        int month;
        int day;
        try {
            int twoDigitYear = Integer.parseInt(origDate.substring(0, 2));
            year = (twoDigitYear >= CENTURY_PIVOT ? TWENTIETH_CENTURY : TWENTY_FIRST_CENTURY)
                    + twoDigitYear;
            month = Integer.parseInt(origDate.substring(2, 4));
            day = Integer.parseInt(origDate.substring(4, ORIG_DATE_LENGTH));
        } catch (NumberFormatException malformed) {
            throw new IllegalStateException(
                    "the authorization's original date is not six digits, so the fraud row's composed"
                            + " key cannot be built");
        }

        int composed = timeKey.intValue();
        int millis = composed % MILLIS_PER_SECOND;
        int clock = composed / MILLIS_PER_SECOND;
        int second = clock % CLOCK_FIELD_MODULUS;
        clock /= CLOCK_FIELD_MODULUS;
        int minute = clock % CLOCK_FIELD_MODULUS;
        int hour = clock / CLOCK_FIELD_MODULUS;

        try {
            return LocalDateTime.of(year, month, day, hour, minute, second,
                    millis * NANOS_PER_MILLI);
        } catch (java.time.DateTimeException impossible) {
            // WHY : Assumptions: this catches a date or time that parsed as digits but names no instant --
            //       month 13, day 31 of February, hour 24. The request body names no key member at
            //       all -- the sealed selector is the row's only address -- and the original date is
            //       stored data no constraint in this context has ever seen, so the composition is the
            //       first place its impossibility can be discovered.
            throw new IllegalStateException(
                    "the authorization's original date and time key name no instant, so the fraud row's"
                            + " composed key cannot be built");
        }
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
