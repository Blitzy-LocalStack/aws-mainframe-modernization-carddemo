package com.carddemo.account.repository;

import com.carddemo.account.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the customer master rows this context owns.
 *
 * <p><b>Purpose.</b> This is the migrated form of the keyed reads the reference performs against
 * {@code CUSTDAT} -- {@code app/cbl/CBCUS01C.cbl} sequentially and the account screens by key.</p>
 *
 * <p>Assumptions: {@code existsById} is inherited rather than declared, and it is the method the
 * account-context presence probe uses. Answering that probe with a full read would materialise a name, a
 * postal address, two phone numbers, a date of birth and two encrypted identifiers in order to discard all
 * of them; the inherited existence check compiles to a query that selects no column at all. The reference
 * does read the whole record at {@code COPAUA0C.cbl} paragraph {@code 5300-READ-CUST-RECORD} and uses none
 * of its fields, so answering the narrower question is a data-minimisation improvement over the source and
 * is documented as one rather than presented as parity.</p>
 *
 * <p>Assumptions: no finder is declared here for the same reason as on the account repository -- the
 * customer master has one access path in the reference system, its identifier.</p>
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
