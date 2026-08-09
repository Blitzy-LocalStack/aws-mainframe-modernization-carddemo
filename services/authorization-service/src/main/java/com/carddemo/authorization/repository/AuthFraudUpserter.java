package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.AuthFraud;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one authorization's fraud row in a single statement that cannot lose a concurrent race.
 *
 * <p><strong>Purpose.</strong> This carries the write half of paragraph {@code FRAUD-INSERT} and
 * paragraph {@code FRAUD-UPDATE} of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} as ONE operation rather than two. The
 * reference program issues an {@code INSERT} at L194, tests {@code SQLCODE} for the duplicate-key
 * condition {@code -803} at L199, and on that condition performs {@code FRAUD-UPDATE} at L203 and
 * L204, whose body at L221 to L229 sets exactly two columns. Its insert and its branch are a single
 * unit of work against a single constraint; this interface is that unit expressed in one SQL
 * statement.</p>
 *
 * <p>Refactoring Rationale: the service previously implemented that branch as a
 * <em>probe-then-write</em> pair -- read the fraud row by primary key, then {@code save} a new row
 * when the read came back empty and mutate the managed row when it did not. That is not the
 * reference behaviour and it is not safe. Two investigators marking the SAME authorization at the
 * same moment both see an absent row, both take the insert arm, and the second insert violates the
 * primary key: the transaction ABORTS and the second mark is lost with a constraint error, where the
 * reference program would have caught {@code -803} and applied its update. The probe therefore
 * converted a handled branch into an unhandled failure. Restating it as
 * {@code INSERT ... ON CONFLICT DO UPDATE} restores the reference outcome exactly -- the constraint
 * is still the arbiter, and losing the race now means taking the update arm rather than failing.
 *
 * <p>Assumptions: the conflict target is the primary key over
 * {@code (card_num, auth_ts)} and it is named explicitly rather than left to a bare
 * {@code ON CONFLICT}. That pair is a REAL uniqueness contract inherited from the reference system
 * rather than one this migration invented: the {@code -803} branch above is only reachable if a
 * unique constraint refuses the insert, so the constraint has to exist for the reference code to
 * have the shape it has. Naming the columns also means a later index added over some other pair
 * cannot silently become the arbiter.
 *
 * <p>Assumptions: the update arm sets exactly TWO columns and the other twenty-four are deliberately
 * left as the first report wrote them. That is what makes the snapshot worth taking -- a row marked
 * twice still describes the authorization as it stood at the FIRST report. The reference update at
 * L222 to L225 names the same two and nothing else.
 *
 * <p>Assumptions: no pessimistic lock is taken and none is needed. The reference programs hold
 * nothing between a read and the write that follows it -- {@code cpy/IMSFUNCS.cpy} declares the three
 * get-hold codes at L19, L21 and L23 and no program in the reference tree passes any of them to a
 * retrieval. A single upsert statement needs no application-level lock because the engine
 * serialises conflicting inserts on the constraint itself: a concurrent writer waits for the
 * in-flight insert to resolve and then applies the update arm.
 *
 * <p>Alternatives Considered: (a) catching the constraint violation and issuing the update from the
 * catch block, which is the closest literal transcription of the {@code SQLCODE} test. Rejected
 * because in PostgreSQL a constraint violation marks the whole transaction as aborted, so the update
 * could only run after a {@code SAVEPOINT} and rollback-to-savepoint around every insert -- new
 * machinery, on the common path, to recover from a condition the engine can avoid entirely.
 * (b) {@code ON CONFLICT DO NOTHING} followed by a separate {@code UPDATE} when no row was inserted.
 * Rejected as two statements where one suffices; both would converge on the same final state, but the
 * discriminator would then be a row count taken between them rather than a property of the write.
 * (c) serialising marks of one authorization with a pessimistic read. Rejected on the get-hold
 * evidence above, and because it adds lock-wait queueing and deadlock-victim rollback to a path that
 * has neither.
 *
 * <p>Trade-offs: the statement is native SQL, so it names columns rather than entity attributes and
 * is not checked by the JPA metamodel. That is accepted because {@code ON CONFLICT} and the
 * insert-versus-update discriminator have no JPQL expression at all, and because a
 * {@code *RepositoryIT} in this module's test tree asserts the behaviour against a real engine --
 * including that a second write preserves the other twenty-four columns and that two concurrent
 * first marks both succeed. A statement this one cannot be checked at compile time is the reason that
 * test is required rather than optional.
 *
 * <p>Assumptions: the declaring bean is a separate collaborator rather than a Spring Data fragment on
 * {@link AuthFraudRepository}, matching {@code TransactionCategoryBalanceWriter} in
 * {@code transaction-service} -- the one other place in this repository that needs a write the
 * derived-query mechanism cannot express. Keeping the shape identical means a reader who has seen
 * one has seen both.
 */
public interface AuthFraudUpserter {

    /**
     * Applies one fraud report to the fraud table, inserting the row or transitioning an existing one.
     *
     * <p>Assumptions: the caller supplies a fully projected row from
     * {@code AuthFraudMapper.toFraudRow}, so this method performs no projection of its own and adds no
     * column of its own. That mapper remains the single authority for which twenty-four columns have
     * exactly one legitimate source; duplicating any of that here would create a second projection
     * able to disagree with it.
     *
     * <p>Assumptions: the report date on the supplied row is the value the DATABASE returned, and both
     * arms of this statement write that same value. The insert arm writes it as a column value and the
     * update arm writes it from {@code EXCLUDED}, which is the same value again -- matching the
     * reference program, which supplies the server's current date positionally in the insert's value
     * list at L194 and sets it in the update at L225.
     *
     * <p>Assumptions: {@code Propagation.MANDATORY} is declared so this can never open a transaction
     * of its own. The fraud row and the authorization segment must commit or roll back together --
     * that single boundary is the whole of divergence D-6, replacing the coordinator the reference
     * system drives to one syncpoint at {@code cbl/COPAUS1C.cbl} L557 and L558 -- so a caller that has
     * not already opened a transaction is a programming error and fails here rather than silently
     * committing one row of a pair.
     *
     * <p>Assumptions: the caller must not be holding a MANAGED {@link AuthFraud} for the same key across
     * this call, and the one caller does not. A native statement is invisible to the persistence context,
     * so a managed copy would keep its pre-statement values and could be flushed back over them at
     * commit. This is stated as a precondition rather than defended against inside the statement,
     * because every defence available there costs a query to find a row nobody in the transaction reads
     * -- and the defence this method once carried, clearing the whole context, silently discarded the
     * caller's OWN later write, which is a worse fault than the one it prevented.
     *
     * @param row the fully projected fraud row to write, carrying its own composite key, its
     *     twenty-four snapshot columns, the requested fraud indicator and the report date the database
     *     supplied; must not be {@code null} and must carry a key
     * @return {@code true} when no row existed for the key and this call inserted one, which the
     *     published contract reports as 201; {@code false} when a row existed and this call
     *     transitioned its two columns, which the contract reports as 200
     * @throws NullPointerException if {@code row} is {@code null} or carries no key
     * @throws IllegalStateException if the statement reports neither an insert nor an update, which
     *     cannot happen for a statement carrying both arms and is raised rather than returned so a
     *     future change to the statement cannot silently report every write as a transition
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean upsert(AuthFraud row);
}
