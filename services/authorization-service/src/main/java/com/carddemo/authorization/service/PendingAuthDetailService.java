package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.PendingAuthDetailView;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the full state of one pending authorization, addressed by its sealed selector.
 *
 * <p><strong>Purpose.</strong> Carry across the read half of the reference detail screen
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl}: {@code READ-AUTH-RECORD} at L431 to L492
 * and {@code POPULATE-AUTH-DETAILS} at L291 to L359. Every citation below is relative to that tree,
 * which is reference material this migration reads and never modifies.
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
 * flag, a separator and a report date at L345 to L347 or a lone separator at L349. Rejected because the
 * contract publishes the STORED values and describes each composition beside the property, so exactly one
 * reading of every field exists on the wire and the client composes what it wants to display. Composing
 * here would put two readings of the same field in circulation, and a client would have no way to
 * recover the stored one.
 */
@Service
public class PendingAuthDetailService {

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
     * <p>Assumptions: a selector that redeems cleanly but names no row is NOT FOUND rather than refused.
     * The two conditions are different and are reported differently: a selector that cannot be redeemed is
     * the caller's to fix and is a 400, while a selector that redeems to a key with no row behind it
     * describes an authorization the expiry sweep has since removed, which is nothing the caller can
     * correct. The reference program distinguishes them the same way, treating a segment-not-found status
     * at {@code cbl/COPAUS1C.cbl} L471 to L473 as an end-of-data condition rather than as an error.
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
}
