package com.carddemo.account.repository;

import com.carddemo.account.domain.CardXref;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the card cross-reference rows this context owns.
 *
 * <p><b>Purpose.</b> This is the migrated form of the two access paths the CICS region surfaced over one
 * physical file. The keyed read is {@code CCXREF}, entered on the card number; the by-account read is the
 * {@code CXACAIX} alternate index, which the region defined as a file of its own so that online programs
 * could browse a customer's cards from an account. Both survive here as real access paths -- the first on
 * the primary key, the second on {@code idx_card_xref_account_id} -- rather than one of them degrading into
 * a table scan.</p>
 *
 * <p>Assumptions: this interface declares no {@code @Query} at all. Both methods are expressible as derived
 * queries from their names, and a hand-written statement would restate the column mapping the entity
 * already declares -- giving the schema a second definition that a later column rename would move out of
 * step with silently.</p>
 *
 * <p>Alternatives Considered: exposing the by-account read as a paginated method. Rejected because the
 * reference browses this path from an online screen and the migration handles that screen's paging with the
 * shared keyset envelope at the service layer, not here; a repository returning a page would fix the paging
 * mechanism at the wrong level and would make the batch callers -- which want every row for an account --
 * ask for an unbounded page.</p>
 */
public interface CardXrefRepository extends JpaRepository<CardXref, String> {

    /**
     * Returns every cross-reference row for one account, in card-number order.
     *
     * <p>Assumptions: the ordering is stated rather than left to the plan, because the reference browses
     * this path with {@code READNEXT} over an index and therefore observes rows in key order. An unordered
     * result would be free to differ between two runs on the same data, which is a parity difference a
     * golden comparison would report and a reader would struggle to attribute.</p>
     *
     * <p>Assumptions: an account with no cards yields an EMPTY list rather than an absent one. The
     * reference distinguishes "no rows for this key" from "this key does not exist" nowhere on this path --
     * it simply reaches end-of-file -- so an optional would invent a distinction the source does not make.
     * </p>
     *
     * @param accountId the account whose cards are wanted
     * @return every row for the account in card-number order, empty when the account has no cards; never
     *     {@code null}
     */
    List<CardXref> findByAccountIdOrderByCardNumAsc(Long accountId);

    /**
     * Returns the cross-reference row for one card.
     *
     * <p>Assumptions: this is declared explicitly rather than left to the inherited {@code findById},
     * although the two are equivalent. The name says which value is being looked up, and on this entity the
     * primary key is a card number rather than a surrogate -- so a call site reading {@code findById(value)}
     * gives no indication that the value is a primary account number and must be handled as one.</p>
     *
     * @param cardNum the primary account number; must not be {@code null}
     * @return the row, or an empty optional when the card is not cross-referenced
     */
    Optional<CardXref> findByCardNum(String cardNum);
}
