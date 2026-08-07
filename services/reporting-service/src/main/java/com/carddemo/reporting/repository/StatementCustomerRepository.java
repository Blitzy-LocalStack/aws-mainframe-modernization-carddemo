package com.carddemo.reporting.repository;

import com.carddemo.reporting.domain.CustomerView;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Keyed single-row read of {@code reporting.v_customers}, standing in for the random-access customer
 * read the statement generator performs once per card.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} reads the customer row at {@code 2000-CUSTFILE-GET.} L368 by whole
 * key, and {@code app/cbl/CBSTM03B.CBL} L45 declares that file {@code ACCESS MODE IS RANDOM}. As with
 * the sibling account role, a randomly-accessed indexed file cannot be browsed, so this is a
 * single-row lookup with no cursor; register entry <b>R3</b> in the package charter carries the
 * proofs.
 *
 * <h2>Assumptions: the whole key is supplied, never a prefix</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} L373-L374 zeroes the key length and then sets it from the length of
 * the customer identifier, giving 9, which matches {@code XREF-CUST-ID PIC 9(09)} at
 * {@code app/cpy/CVACT03Y.cpy} L6 exactly.
 *
 * <h2>Assumptions: this is the narrowest reach of any role in the package</h2>
 *
 * <p>The relation behind it projects eleven of the eighteen members
 * {@code app/cpy/CVCUS01Y.cpy} declares, and the seven it omits are omitted at the view rather than
 * here -- the two enciphered national-identifier columns, the credit score, both telephone numbers and
 * the transfer-account reference are not in the view's select list, so the login role this module
 * authenticates as cannot read them even by writing its own query. That is why no accessor on this
 * interface could expose them and no masking rule is needed on this path: there is nothing to mask.
 *
 * <h2>Assumptions: extending the marker interface is the read-only mechanism</h2>
 *
 * <p>Alternatives Considered: extending {@code CrudRepository} or {@code JpaRepository}. Rejected for
 * the reason the sibling account role records at length -- either would inherit five write methods
 * onto a type whose whole contract is that it has none.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>The decision register that governs every choice in this package is authored once in
 * {@code package-info.java} beside this file, and entries are cited by identifier rather than
 * restated. Every member below carries a docstring because user-specified Rule 1 (Explainability)
 * attaches its presence clause to every function and names no visibility.
 */
public interface StatementCustomerRepository extends Repository<CustomerView, Long> {

    /**
     * Reads the customer row for one complete customer identifier.
     *
     * <p>Assumptions: the return is an optional rather than a nullable row, which is register entry
     * <b>R13</b> applied here. An absent row is fatal on this path for the reason register entry
     * <b>R10</b> records -- the customer read at {@code app/cbl/CBSTM03A.CBL} L379-L386 has no
     * end-of-file arm, so a cross-reference row naming an absent customer abends the run -- and the
     * refusal is raised by the caller so that it can name the unresolved key.</p>
     *
     * @param customerId the complete customer identifier, declared {@code PIC 9(09)} at
     *     {@code app/cpy/CVCUS01Y.cpy} L5; must not be {@code null}
     * @return the customer projection for that identifier, or an empty optional when the view has no
     *     such row; never {@code null}
     */
    Optional<CustomerView> findByCustomerId(Long customerId);
}
