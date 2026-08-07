package com.carddemo.reporting.repository;

import com.carddemo.reporting.domain.CardXrefView;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

/**
 * Sequential traversal of {@code reporting.v_card_xref}, standing in for the cross-reference walk
 * that drives one statement per card.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/CBSTM03B.CBL} L39 declares the cross-reference file
 * {@code ACCESS MODE IS SEQUENTIAL} and {@code app/cbl/CBSTM03A.CBL} drives it from
 * {@code 1000-XREFFILE-GET-NEXT.} L345, so the statement run walks this relation in key order and
 * every row it reaches becomes one statement. This is one of the two roles in the package that
 * stream; the customer and account roles are keyed single-row lookups, and register entry <b>R3</b>
 * carries the proofs that separate the two shapes.
 *
 * <h2>Assumptions: the traversal is positioned by key and never by row ordinal</h2>
 *
 * <p>Register entry <b>R1</b> is the whole reason this interface has no method taking a row position.
 * A row's ordinal is not stable -- insert a row ahead of it and every ordinal behind the insertion
 * moves by one, so two consecutive requests over a relation being written omit rows entirely and
 * return others twice. The baseline's own cursor is already a key rather than an ordinal, which the
 * communication-area block at {@code app/cbl/COCRDLIC.cbl} L229-L244 shows: key pairs and a next-page
 * indicator, and no row ordinal anywhere.
 *
 * <h2>Assumptions: the key this traversal walks is the MASKED card number</h2>
 *
 * <p>The view masks the stored number to twelve asterisks and its last four digits before this module
 * ever sees it, so the ordering this traversal produces is the ordering of the masked form and not of
 * the stored number. Two consequences are stated because neither is obvious and both are
 * load-bearing. First, the ordering is still total and still deterministic: the twelve leading
 * asterisks are constant, so masked values sort by their last four digits, which is a different order
 * from the stored one but a stable one, and a rerun over the same data walks the same rows in the same
 * sequence. Second, <b>a masked value is not guaranteed unique</b>: two cards sharing a last-four
 * collide. Trade-offs: that is accepted rather than solved from here, because solving it needs a
 * column this role is not granted -- the unmasked number -- and the run does not depend on
 * uniqueness, since each returned row carries its own customer and account identifiers and is turned
 * into its own statement. What a caller must not do is treat the masked value as a card identity and
 * merge two rows that share one; the transaction relation projects a per-card grouping token
 * precisely so that a caller can tell two colliding cards apart, and that token is the identity to
 * group on.
 *
 * <h2>Assumptions: extending the marker interface is the read-only mechanism</h2>
 *
 * <p>Alternatives Considered: extending {@code CrudRepository} or {@code JpaRepository}. Rejected for
 * the reason the sibling account role records -- either would inherit five write methods onto a type
 * whose whole contract is that it has none.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The decision register that governs every choice in this package is authored once in
 * {@code package-info.java} beside this file, and entries are cited by identifier rather than
 * restated. Every member below carries a docstring because user-specified Rule 1 (Explainability)
 * attaches its presence clause to every function and names no visibility.
 */
public interface StatementCardXrefRepository extends Repository<CardXrefView, String> {

    /**
     * Reads the first page of the traversal, in ascending key order.
     *
     * <p>Assumptions: a separate method for the first page, rather than one method taking a nullable
     * position, is what keeps the continuation method's parameter non-nullable. A single method
     * admitting {@code null} would have to decide inside itself whether it was starting or
     * continuing, and a caller passing {@code null} by accident on a continuation would silently
     * restart the whole traversal from the beginning and emit every statement twice.</p>
     *
     * @param limit the maximum number of rows to return, which the caller sizes one row beyond the
     *     page it intends to process so that the existence of a further page is established by a row
     *     rather than by a count; must not be {@code null}
     * @return the rows at the head of the relation in ascending key order, at most {@code limit} of
     *     them, possibly empty; never {@code null}
     */
    List<CardXrefView> findAllByOrderByCardNumAsc(Limit limit);

    /**
     * Reads the page that follows a stated key, in ascending key order.
     *
     * <p>Assumptions: the comparison is strictly greater than rather than greater than or equal,
     * because the key supplied is the last key already returned. An inclusive comparison would return
     * that row a second time, which on this path means one card's statement produced twice.</p>
     *
     * @param cardNum the masked card number of the last row already returned; must not be
     *     {@code null}
     * @param limit the maximum number of rows to return, sized one beyond the page as above; must not
     *     be {@code null}
     * @return the rows following that key in ascending key order, at most {@code limit} of them,
     *     empty when the traversal has reached the end; never {@code null}
     */
    List<CardXrefView> findByCardNumGreaterThanOrderByCardNumAsc(String cardNum, Limit limit);

    /**
     * Reads the cross-reference row for one masked card number.
     *
     * <p>Assumptions: this is the report path's shape rather than the statement path's, and the two
     * are deliberately on one interface because they read one relation.
     * {@code app/cbl/CBTRN03C.cbl} looks the same relation up by key at
     * {@code 1500-A-LOOKUP-XREF} L484-L492, so the report line's account identifier comes from here.
     * Register entry <b>R10</b> records that an unresolvable lookup on that path aborts the run, and
     * the empty optional this method returns is what lets the caller name the unresolved key in its
     * own refusal.</p>
     *
     * <p>Trade-offs: the return is a single optional even though the masked key is not guaranteed
     * unique, so a masked collision would make this lookup ambiguous and the underlying read would
     * raise rather than pick one. That is the correct failure: choosing a row would attribute a
     * report line to whichever of two cards the engine returned first, and the report's account total
     * would then be wrong with nothing recording why.</p>
     *
     * @param cardNum the masked sixteen-character rendering of the card number; must not be
     *     {@code null}
     * @return the cross-reference row for that masked value, or an empty optional when the view has
     *     no such row; never {@code null}
     */
    Optional<CardXrefView> findByCardNum(String cardNum);
}
