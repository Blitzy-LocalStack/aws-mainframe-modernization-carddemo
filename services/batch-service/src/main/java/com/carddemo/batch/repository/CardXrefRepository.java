package com.carddemo.batch.repository;

import com.carddemo.batch.domain.CardXref;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads the card cross-reference rows the posting and pre-posting jobs resolve a card number through.
 *
 * <p>Purpose: every daily transaction names a card, and neither the account nor the customer it belongs
 * to is carried on the feed record. The reference resolves the card to both by a keyed read of the
 * cross-reference -- {@code app/cbl/CBTRN02C.cbl:380-392} for posting and
 * {@code app/cbl/CBTRN01C.cbl:227-239} for the pre-posting pass -- and a read that finds nothing is
 * reject reason 100, {@code INVALID CARD NUMBER FOUND}. That single lookup is the whole of this
 * interface.</p>
 *
 * <p>Assumptions: the table behind this interface is {@code account.card_xref}, owned by the account
 * context and reached under the same narrowly-scoped cross-schema grant recorded on
 * {@link AccountRepository}. Nothing in this module writes it: the cross-reference is maintained by the
 * online card and account flows, and the batch chain only reads it.</p>
 *
 * <p>Assumptions: both access paths the reference declares are exposed here, because the batch chain uses
 * both. {@code app/cbl/CBACT04C.cbl:37-38} declares the file with {@code RECORD KEY IS FD-XREF-CARD-NUM}
 * and {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID}, and {@code app/jcl/INTCALC.jcl:31-32} supplies the
 * alternate index's own path as a second data definition -- so the interest accrual really does read this
 * file by ACCOUNT while posting reads it by CARD. The by-account method below is the migrated form of the
 * {@code CXACAIX} alternate index, and the secondary index that supports it is declared on the owning
 * context's table rather than here.</p>
 */
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Reads the first cross-reference row of one account in card-number order.
     *
     * <p>Assumptions: the FIRST row in card order is returned rather than all of them, matching
     * {@code app/cbl/CBACT04C.cbl:1110-GET-XREF-DATA}, which issues a keyed READ against the alternate
     * index path and therefore receives exactly one record -- the first under that key. An account with
     * several cards resolves to the lowest card number, and the reference's generated interest
     * transactions carry that one card. Returning a list and letting the caller choose would move the
     * choice out of the place the index makes it and invite a different card being picked.</p>
     *
     * <p>Assumptions: the ordering is stated explicitly rather than left to the query planner. Without it
     * the row returned for a multi-card account would be whichever the planner reached first, which is
     * stable in practice and guaranteed by nothing -- and the generated transaction's card number would
     * then vary between runs of identical input, breaking the reproducibility the injected business date
     * exists to provide.</p>
     *
     * @param accountId the eleven-digit account identifier; must not be {@code null}
     * @return the lowest-numbered card's cross-reference row for that account, or an empty optional when
     *     the account has no card at all
     */
    Optional<CardXref> findFirstByAccountIdOrderByCardNumAsc(Long accountId);

    /**
     * Reads one cross-reference row by card number.
     *
     * <p>Assumptions: an absent row is reported as an empty optional rather than raised, because absence
     * is reject reason 100 at {@code app/cbl/CBTRN02C.cbl:380-392} and therefore a business outcome the
     * posting job writes to the reject stream.</p>
     *
     * @param cardNum the sixteen-character card number exactly as the feed record carries it, unpadded
     *     and unmasked; must not be {@code null}
     * @return the cross-reference row when one exists, otherwise an empty optional
     */
    Optional<CardXref> findByCardNum(String cardNum);
}
