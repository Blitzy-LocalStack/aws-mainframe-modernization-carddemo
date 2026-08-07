package com.carddemo.reporting.repository;

import com.carddemo.reporting.domain.AccountView;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Keyed single-row read of {@code reporting.v_accounts}, standing in for the random-access account
 * read the statement generator performs once per card.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} reads the account row at {@code 3000-ACCTFILE-GET.} L392 by whole
 * key, and {@code app/cbl/CBSTM03B.CBL} L51 declares that file {@code ACCESS MODE IS RANDOM}. A
 * randomly-accessed indexed file cannot be browsed at all, so this role is a single-row lookup and
 * needs no cursor: register entry <b>R3</b> in the package charter records the five independent
 * proofs that the operation code the caller sends is a keyed read rather than a generic browse, and
 * this interface is the direct consequence.
 *
 * <h2>Assumptions: the whole key is supplied, never a prefix</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} L397-L398 zeroes the key length and then sets it from the length
 * of the account identifier, giving 11, which matches {@code XREF-ACCT-ID PIC 9(11)} at
 * {@code app/cpy/CVACT03Y.cpy} L7 exactly. There is no prefix and no partial-key intent anywhere on
 * this path, so the lookup takes one complete identifier and returns at most one row.
 *
 * <h2>Assumptions: extending the marker interface is the read-only mechanism</h2>
 *
 * <p>Alternatives Considered: extending {@code CrudRepository} or {@code JpaRepository}, which is
 * the reflex base for a Spring Data interface and is refused here. Either one would inherit
 * {@code save}, {@code saveAll}, {@code delete}, {@code deleteAll} and {@code deleteById} onto this
 * type, so the read-only posture the package charter states would become a convention that the
 * type's own surface contradicts -- a caller could reach a write method through code completion, and
 * the failure would arrive from the database at run time rather than from the compiler. Extending the
 * bare marker interface inherits nothing, so the surface is exactly the methods declared below. The
 * database privilege is the other half of the control and neither half is redundant: this one fails
 * at compile time and the privilege fails in production.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The decision register that governs every choice in this package is authored once in
 * {@code package-info.java} beside this file, and entries are cited by identifier rather than
 * restated. Every member below carries a docstring because user-specified Rule 1 (Explainability)
 * attaches its presence clause to every function and names no visibility.
 */
public interface StatementAccountRepository extends Repository<AccountView, Long> {

    /**
     * Reads the account row for one complete account identifier.
     *
     * <p>Assumptions: the return is an optional rather than a nullable row or a raising lookup, which
     * is register entry <b>R13</b> applied here: a query boundary returns a fully populated
     * projection or nothing, never a half-filled instance a caller has to inspect field by field to
     * find out whether the read succeeded.</p>
     *
     * <p>Assumptions: an absent row is <b>fatal on this path</b>, and that decision belongs to the
     * caller rather than to this method. Register entry <b>R10</b> records the evidence: the
     * cross-reference read at {@code app/cbl/CBSTM03A.CBL} L353-L362 tolerates end-of-file through a
     * {@code WHEN '10'} arm, while the account read at L403-L410 has no such arm at all, so a
     * cross-reference row naming an absent account abends the whole run at
     * {@code 9999-ABEND-PROGRAM.} L921. Returning an empty optional rather than raising from here is
     * what lets the caller name the unresolved key in its own refusal, which is exactly what the
     * three baseline lookup paragraphs do when they display the offending key before abending.</p>
     *
     * @param accountId the complete account identifier, declared {@code PIC 9(11)} at
     *     {@code app/cpy/CVACT01Y.cpy} L5; must not be {@code null}
     * @return the account projection for that identifier, or an empty optional when the view has no
     *     such row; never {@code null}
     */
    Optional<AccountView> findByAccountId(Long accountId);
}
