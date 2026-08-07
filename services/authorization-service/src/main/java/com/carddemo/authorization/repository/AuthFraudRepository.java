package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The only route to table {@code auth_fraud}, the fraud-tagged authorizations.
 *
 * <p><strong>Purpose.</strong> Carry the three embedded-SQL statements the reference fraud program
 * issues against {@code CARDDEMO.AUTHFRDS} onto a typed interface: the insert at
 * {@code cbl/COPAUS2C.cbl} L143 to L198, the duplicate-key branch it tests at L199 to L204, and the
 * update at L221 to L244. Every citation in this file is relative to
 * {@code app/app-authorization-ims-db2-mq}, which is reference material this migration reads and never
 * modifies.
 *
 * <p>Refactoring Rationale: this interface declares NO method of its own, and the emptiness is the
 * design rather than an unfinished state. The reference program reaches this table by its full primary
 * key and by nothing else -- the insert names every column positionally, and the update's
 * {@code WHERE} clause at L226 to L228 matches the card number and the composed timestamp exactly and
 * nothing besides -- so {@code findById}, {@code save} and {@code existsById}, which the inherited
 * interface already supplies over {@link AuthFraudKey}, are the complete access path. A derived
 * finder added here would publish a path the baseline does not have, and the descending
 * card-and-timestamp index the migration declares at its L806 exists to serve a fraud REVIEW that
 * this contract does not yet publish; adding a method for it now would be an access path with no
 * caller, which is harder to remove later than to add when a caller exists.
 *
 * <p>Alternatives Considered: expressing the reference control flow literally, as a native
 * {@code INSERT ... ON CONFLICT (card_num, auth_ts) DO UPDATE} behind one {@code @Modifying} method.
 * The migration itself names that statement at its L766 to L772 as the transcription of the
 * duplicate-key branch, so it is the obvious candidate. It is rejected for one decisive reason: the
 * operation must report WHICH of the two paths ran, because
 * {@code src/main/resources/openapi/authorization-api.yaml} distinguishes them on the status code --
 * 201 for the insert and 200 for the update -- exactly as the reference program distinguishes them in
 * the two sentences it reports at L201 and L232. A single upsert statement returns an affected-row
 * count that is 1 in both cases, so the distinction the contract publishes would be unrecoverable.
 * The service therefore performs the probe read declared below under a lock it already holds on the
 * detail row, which keeps both outcomes observable.
 *
 * <p>Assumptions: the find-then-write sequence the service performs is safe against a concurrent
 * duplicate ONLY because the caller has already taken a pessimistic write lock on the
 * {@code pending_auth_detail} row the fraud row is derived from, through
 * {@link PendingAuthDetailRepository#findWithLockById(com.carddemo.authorization.domain.PendingAuthDetailKey)}.
 * Two requests naming one selector therefore serialise on that row before either probes this table,
 * so the window in which both could observe an absent fraud row does not exist. That ordering is the
 * migrated form of the reference sequence, which re-reads the segment for update at
 * {@code cbl/COPAUS1C.cbl} L233 through {@code READ-AUTH-RECORD} before it reaches the fraud program
 * at L248 to L252. A caller that probed this table without holding that lock would be relying on a
 * uniqueness violation to surface as a conflict, which inside one transaction is unrecoverable rather
 * than retryable.
 */
public interface AuthFraudRepository extends JpaRepository<AuthFraud, AuthFraudKey> {

    /**
     * Reads the fraud row for one card and one composed authorization timestamp.
     *
     * <p>Assumptions: this is declared explicitly even though it only narrows the inherited
     * {@code findById} to a named signature, because the two write paths the contract publishes are
     * chosen on its result and a reader tracing the 201-versus-200 decision should find the read it
     * turns on named in this interface rather than inferred from a supertype.
     *
     * @param id the card number and composed timestamp forming the primary key
     *     {@code ddl/AUTHFRDS.ddl} L28 declares; must not be {@code null}
     * @return the fraud row already recorded for that key, or an empty optional when none is, in
     *     which case the caller takes the insert path
     */
    Optional<AuthFraud> findById(AuthFraudKey id);
}
