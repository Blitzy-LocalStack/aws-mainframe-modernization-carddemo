package com.carddemo.reporting.repository;

import com.carddemo.reporting.domain.StatementTransactionView;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.repository.Repository;

/**
 * Card-ordered traversal of {@code reporting.v_statement_transactions}, standing in for the
 * card-keyed transaction file the statement generator reads.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/CBSTM03B.CBL} L33 declares the transaction file
 * {@code ACCESS MODE IS SEQUENTIAL}, and the file it reads is not the transaction master: register
 * entry <b>R9</b> records that {@code app/jcl/CREASTMT.JCL} L44-L61 sorts the master into card-first
 * order and then loads a second keyed data set, and that it is the second data set the statement
 * generator reads through the data definition at L83. The target defines no relation for it and copies
 * no bytes -- the card-first ordering is an index plus a read-only projection over rows the
 * transaction context owns -- so this role reads the same rows in the same order without a second
 * physical copy to keep in step.
 *
 * <h2>Assumptions: the per-card identity to group on is the fingerprint, not the masked number</h2>
 *
 * <p>This is the single most important property of this interface and it is stated before the methods
 * for that reason. The projection's key is the pair of the masked card number and the transaction
 * identifier, because that is the key the reference record declares at {@code app/cpy/COSTM01.CPY}.
 * The masked number is <b>not</b> a card identity: two cards sharing a last-four mask to the same
 * sixteen characters. The view therefore projects {@code card_fingerprint} beside it -- a keyed digest
 * of the unmasked number, mixed with a secret this module holds no privilege to read -- and that token
 * is one value per card and a different value for every other card.
 *
 * <p>Alternatives Considered: keying the per-card read on the masked number alone, which reads
 * naturally and is what the reference key implies. Rejected because it would merge two colliding cards
 * into one statement: their transactions would appear together, the card total rendered at
 * {@code app/cbl/CBSTM03A.CBL} would be the sum of two cards' activity, and neither cardholder's
 * statement would be correct -- with nothing in the output recording that a merge had happened.
 * Alternatives Considered: reading the unmasked number so that the identity needs no token. Rejected
 * because it is not available: the view masks before this module sees a row, and that masking is the
 * reason the fingerprint exists at all. The fingerprint read is therefore the primary per-card
 * method below, and the masked-number read is offered beside it only for the traversal, where the
 * ordering rather than the identity is what is being used.
 *
 * <h2>Assumptions: positioning is by key and the ordering carries a stable secondary</h2>
 *
 * <p>Register entry <b>R1</b> rules out any method taking a row position. Register entry <b>R6</b>
 * requires a stable secondary key behind the primary one, and it is supplied on every ordering below:
 * the transaction identifier. Without it, two rows sharing a card would come back in whatever order
 * the engine produced, and a rerun over the same data could render the same statement with its
 * transactions in a different sequence -- which is a byte difference the golden comparison would
 * report and no reader would understand.
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
public interface StatementTransactionRepository
        extends Repository<StatementTransactionView, StatementTransactionView.StatementTransactionKey> {

    /**
     * Reads the first page of one card's transactions, identified by its grouping token.
     *
     * <p>Assumptions: this is the authoritative per-card read, for the reason the type-level charter
     * gives at length: the grouping token is one value per card while the masked number is not.</p>
     *
     * @param cardFingerprint the per-card grouping token the view projects; must not be {@code null}
     * @param limit the maximum number of rows to return, which the caller sizes one row beyond the
     *     page it intends to render so that the existence of a further page is established by a row
     *     rather than by a count; must not be {@code null}
     * @return that card's transactions in ascending transaction-identifier order, at most
     *     {@code limit} of them, possibly empty; never {@code null}
     */
    List<StatementTransactionView> findByCardFingerprintOrderByKeyTransactionIdAsc(
            String cardFingerprint, Limit limit);

    /**
     * Reads the page of one card's transactions that follows a stated transaction identifier.
     *
     * <p>Assumptions: the comparison is strictly greater than, because the identifier supplied is the
     * last one already rendered. An inclusive comparison would render that transaction twice, which on
     * a statement means one charge shown twice and a card total that no longer reconciles.</p>
     *
     * @param cardFingerprint the per-card grouping token the view projects; must not be {@code null}
     * @param transactionId the transaction identifier of the last row already rendered; must not be
     *     {@code null}
     * @param limit the maximum number of rows to return, sized one beyond the page as above; must not
     *     be {@code null}
     * @return that card's following transactions in ascending transaction-identifier order, at most
     *     {@code limit} of them, empty when the card has no further activity; never {@code null}
     */
    List<StatementTransactionView>
            findByCardFingerprintAndKeyTransactionIdGreaterThanOrderByKeyTransactionIdAsc(
                    String cardFingerprint, String transactionId, Limit limit);

    /**
     * Reads the first page of the whole relation, in card-first order.
     *
     * <p>Assumptions: this method exists for the <b>traversal</b> and not for a per-card read, and the
     * distinction is why it orders on the masked number while the two methods above key on the
     * fingerprint. A statement run that walks the transaction relation directly -- rather than being
     * driven by the cross-reference walk -- needs a total order over every row, and the masked number
     * followed by the transaction identifier is one; what it must not do is treat a change of masked
     * number as a change of card, because the fingerprint on each returned row is the value that
     * settles that.</p>
     *
     * @param limit the maximum number of rows to return, sized one beyond the page; must not be
     *     {@code null}
     * @return the rows at the head of the relation in card-first order, at most {@code limit} of them,
     *     possibly empty; never {@code null}
     */
    List<StatementTransactionView> findAllByOrderByKeyCardNumberAscKeyTransactionIdAsc(Limit limit);

    /**
     * Reads one masked card number's transactions, in ascending transaction-identifier order.
     *
     * <p>Assumptions: this reads on the <b>masked</b> key rather than on the grouping token, and it is
     * the one method here for which that is correct. A request naming a card number can be masked --
     * masking is idempotent and this module holds no unmasked value -- but it cannot be turned into a
     * grouping token, because the token is derived inside the view from the unmasked number mixed with
     * a secret this module is not granted. Reading on the masked key is therefore how a request-time
     * lookup reaches a card at all, and the rows it returns carry their own tokens so the caller can
     * detect that two cards masked alike and refuse rather than merge them.</p>
     *
     * <p>Alternatives Considered: reading the head of the whole relation in card-first order and
     * filtering to the wanted card in memory, which needs no method beyond the traversal above.
     * Rejected because it is only correct when the wanted card happens to sit inside the first page:
     * a card later in the ordering would return no rows and the caller would report a statement with no
     * activity, which is indistinguishable from a genuine one.</p>
     *
     * @param cardNumber the masked sixteen-character rendering of the card number; must not be
     *     {@code null}
     * @param limit the maximum number of rows to return; must not be {@code null}
     * @return that masked card's transactions in ascending transaction-identifier order, at most
     *     {@code limit} of them, possibly empty; never {@code null}
     */
    List<StatementTransactionView> findByKeyCardNumberOrderByKeyTransactionIdAsc(
            String cardNumber, Limit limit);

    /**
     * Counts one card's transactions, identified by its grouping token.
     *
     * <p>Assumptions: the tally is read from the store rather than derived by paging to the end,
     * because the statement response publishes a transaction count and a caller that paged to obtain
     * it would read every row twice. The count is over the grouping token for the same reason the
     * per-card reads are: a count over the masked number would be the sum of two cards' activity
     * wherever two cards collide.</p>
     *
     * @param cardFingerprint the per-card grouping token the view projects; must not be {@code null}
     * @return the number of rows the relation holds for that card, zero when it holds none
     */
    long countByCardFingerprint(String cardFingerprint);
}
