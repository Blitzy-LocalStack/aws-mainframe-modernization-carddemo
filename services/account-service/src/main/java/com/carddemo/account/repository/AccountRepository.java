package com.carddemo.account.repository;

import com.carddemo.account.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and writes the account master rows this context owns.
 *
 * <p><b>Purpose.</b> This is the migrated form of the keyed reads the reference performs against
 * {@code ACCTDAT} -- {@code app/cbl/CBACT01C.cbl} sequentially and {@code app/cbl/COACTVWC.cbl} and
 * {@code COACTUPC.cbl} by key -- expressed as a typed interface rather than as file verbs.</p>
 *
 * <p>Assumptions: the inherited keyed read is the whole interface and nothing is declared here. The account
 * master has exactly one access path in the reference system: {@code ACCTDAT} is defined with no alternate
 * index, so every program that reaches an account does so by its identifier. Declaring a second finder
 * would create an access path the source does not have, and a scan behind it.</p>
 *
 * <p>Assumptions: the optimistic-concurrency check that guards an update is declared on the entity's
 * version column rather than by a locking annotation here. The reference does not hold a lock across the
 * client gap either -- {@code app/cbl/COACTUPC.cbl} snapshots a before-image at L669 onward precisely
 * because it cannot -- so a pessimistic read here would be a stronger guarantee than the source's and would
 * hold a row for as long as a user took to fill in a screen.</p>
 */
public interface AccountRepository extends JpaRepository<Account, Long> {
}
