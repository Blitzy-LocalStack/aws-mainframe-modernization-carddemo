package com.carddemo.transaction.repository;

import com.carddemo.common.money.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Reads and reduces the balance of one {@code account.accounts} row from inside this module's own
 * transaction, so that a bill payment is a single ACID commit.
 *
 * <p><b>Purpose.</b> {@code app/cbl/COBIL00C.cbl} commits two writes under one CICS syncpoint: the
 * payment transaction at line 233, through {@code WRITE-TRANSACT-FILE} at line 510, and the reduced
 * account balance at line 235, through {@code UPDATE-ACCTDAT-FILE} at line 377 whose
 * {@code EXEC CICS REWRITE} is at lines 379 to 382. The read that precedes them is
 * {@code READ-ACCTDAT-FILE} at line 343, which carries the {@code UPDATE} option at line 351 with
 * {@code RIDFLD(ACCT-ID)} at line 349 -- a read for update, whose lock the rewrite then consumes.
 * This class is the account-master half of that unit of work, expressed so that it shares the
 * enclosing local transaction with the ledger insert.</p>
 *
 * <h2>Why this reaches another context's schema at all</h2>
 *
 * <p>Refactoring Rationale: an earlier revision of {@code BillPaymentService} issued the balance read
 * and the balance change over the account context's REST API, through a seam operation named
 * {@code applyPayment}. Two things were wrong with that and only one of them was visible. The visible
 * one: the endpoint it addressed, {@code POST /api/v1/accounts/payments}, was never published by
 * account-service, so every confirmed payment failed outright. The invisible one, which publishing
 * that endpoint would NOT have fixed: a separate connection is a separate transaction, so a balance
 * reduced by its owner followed by a local commit that then failed would leave the state the baseline
 * cannot produce -- a reduced balance with no payment to show for it. The previous class note
 * acknowledged that exposure and narrowed it by ordering the seam call last; ordering cannot close it,
 * because the two commits remain two commits.</p>
 *
 * <p>Assumptions: reaching one table in the account schema is the arrangement the migration plan
 * sanctions for exactly this shape of problem. AAP section 0.4.1.3 records the one documented
 * exception to schema-per-service purity -- a genuinely multi-record unit of work stays a single ACID
 * commit under narrowly scoped cross-schema grants -- and explicitly rejects a saga or a transactional
 * outbox with a compensating reversal, because both make a partly-applied state observable where the
 * baseline has none. {@code data-migration/sql/V0__schemas_and_roles.sql} section 4b issues those
 * grants: {@code USAGE} on the {@code account} schema plus {@code SELECT} and {@code UPDATE} on
 * {@code account.accounts} and nothing else. This class issues no grant of any kind; that file is the
 * single owner of the privilege graph.</p>
 *
 * <p>Alternatives Considered: mapping the account master as a fifth JPA entity in this module.
 * Rejected because the sibling domain charter closes its inventory at four entity types on the ground
 * that an entity here would claim ownership of a table another context owns -- ownership being what
 * decides whose migration declares the columns, widths, nullability and key. Native statements over
 * two named columns claim nothing: they neither describe the table nor let any other member of this
 * module read a column this payment does not need. The narrowness is the point.</p>
 *
 * <p>Alternatives Considered: a second {@code DataSource} pointed at the account schema. Rejected for
 * the reason that rules out the REST seam -- a separate connection is a separate transaction, which
 * forfeits the atomicity this class exists to obtain.</p>
 *
 * <p>Alternatives Considered: a Spring Data JPA interface, which is what every other repository in
 * this package is. Not available here: a Spring Data repository is declared over a managed entity
 * type, and this class deliberately maps none. A class holding an entity manager is the framework's
 * own answer for statements that have no entity, and it keeps the two statements visible in one file
 * rather than behind a derived-query convention.</p>
 *
 * <h2>The schema is named in every statement</h2>
 *
 * <p>Assumptions: {@code account.accounts} is written schema-qualified in all three statements rather
 * than relying on the connection's search path. This module sets its path to {@code ledger} alone --
 * {@code services/transaction-service/src/main/resources/application.yml} binds
 * {@code connection-init-sql: SET search_path TO ledger} -- so an unqualified name would not resolve
 * here at all. Qualifying also means the statements keep working if that path is ever widened, and
 * cannot silently bind to a same-named table in another schema.</p>
 *
 * @see TransactionRepository the owned ledger table the payment row is written to inside the same
 *     transaction
 */
@Repository
public class AccountBalanceRepository {

    /**
     * Reads one account's balance and locks the row against a concurrent writer.
     *
     * <p>Assumptions: {@code FOR UPDATE} is written out because the lock is the contract, not an
     * optimisation. It is what {@code app/cbl/COBIL00C.cbl} line 351 obtains with the {@code UPDATE}
     * option and what the rewrite at line 379 consumes, and it is what makes the read-then-reduce
     * below indivisible: a second payer reaching this statement blocks until the first commits and
     * then reads the balance the first left, rather than reading the same balance and subtracting
     * from it twice.</p>
     */
    // WHY : Refactoring Rationale: the three column names and the qualified table name are PUBLISHED
    //       constants and every statement below is assembled from them, so the one place a name is
    //       written is the one place a test can read it. Two independently authored resolutions of the
    //       same atomicity defect met here, and each brought its own hard-coded copy of the same
    //       identifiers -- which is how a repository and the migration that creates its table drift
    //       apart without either side failing. AccountBalanceSchemaAgreementTest reads these constants
    //       and asserts the owning migration creates exactly them.
    /** The schema-qualified table the balance lives in, owned by the account context. */
    public static final String TABLE = "account.accounts";

    /** The account-identifier column, the key every statement narrows on. */
    public static final String COLUMN_ACCOUNT_ID = "account_id";

    /** The current-balance column the payment reduces, {@code ACCT-CURR-BAL} in the copybook. */
    public static final String COLUMN_CURRENT_BALANCE = "curr_bal";

    /** The optimistic-concurrency column the reduction advances, so a concurrent writer is detected. */
    public static final String COLUMN_VERSION = "version";

    /** The statement that reads the balance under the row lock the paying turn consumes. */
    private static final String LOCK_BALANCE =
            "SELECT " + COLUMN_CURRENT_BALANCE + " FROM " + TABLE
                    + " WHERE " + COLUMN_ACCOUNT_ID + " = :accountId FOR UPDATE";

    /**
     * Reads one account's balance without locking the row.
     *
     * <p>Assumptions: this is the statement the reporting turns use, and it is a DIFFERENT statement
     * rather than the one above with a flag, so no caller can reach a lock by accident. The reference
     * does take its lock on the reporting turn too -- line 184 reaches the same read-for-update as
     * line 177 -- and it can afford to, because a CICS task ends at the screen and releases the lock
     * with it. Holding an exclusive row lock here to answer a turn that writes nothing would let one
     * operator's unconfirmed preview block another operator's payment for the whole of a request, so
     * the lock is taken only on the turn that is about to write.</p>
     *
     * <p>Trade-offs: the balance a preview reports may therefore be superseded before the operator
     * confirms. That is already true of the baseline, where the preview and the payment are two
     * separate tasks with two separate locks, and it is why the paying turn re-reads under its own
     * lock rather than trusting a figure carried back from a previous turn.</p>
     */
    private static final String READ_BALANCE =
            "SELECT " + COLUMN_CURRENT_BALANCE + " FROM " + TABLE
                    + " WHERE " + COLUMN_ACCOUNT_ID + " = :accountId";

    /**
     * Subtracts an amount from one account's balance and advances its optimistic-lock revision.
     *
     * <p>Assumptions: the AMOUNT is subtracted in the statement rather than a computed balance being
     * assigned, which is what preserves the reference's arithmetic. Line 234 computes
     * {@code ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT}, so the subtraction is performed against the
     * balance as it then stands. An assignment would replace that computation, which transformation
     * rule T4 forbids, and would overwrite rather than adjust.</p>
     *
     * <p>⚠️ Refactoring Rationale: the revision column is advanced, and omitting it would introduce a
     * lost update that nothing in this module could detect. {@code account.accounts} carries
     * {@code version BIGINT NOT NULL DEFAULT 0} at line 375 of
     * {@code services/account-service/src/main/resources/db/migration/V1__account.sql}, and
     * account-service maps it as a JPA version attribute so that the account-update screen's
     * before-image comparison -- {@code ACUP-OLD-DETAILS} at {@code app/cbl/COACTUPC.cbl} line 669
     * onwards, whose change flag is tested at line 521 -- is enforced by the database rather than by
     * convention. A balance change that left the revision untouched would be invisible to that
     * mechanism: an account update already in flight would still match on its stale revision and would
     * write its own balance over the payment. Advancing the revision here makes the payment visible to
     * exactly the check that exists to notice it, so such an update matches no row, raises the
     * optimistic-lock failure and is answered with the reference's own data-changed conflict.</p>
     *
     * <p>Assumptions: the row is already locked by {@link #LOCK_BALANCE} on this path, so no
     * revision predicate is needed on this statement. The lock, not a compare-and-set, is what makes
     * the read and the reduction one indivisible step; adding a predicate would express the same
     * guarantee a second time and would then have to be kept in step with the read.</p>
     */
    private static final String REDUCE_BALANCE = "UPDATE " + TABLE
            + " SET " + COLUMN_CURRENT_BALANCE + " = " + COLUMN_CURRENT_BALANCE + " - :amount,"
            + " " + COLUMN_VERSION + " = " + COLUMN_VERSION + " + 1"
            + " WHERE " + COLUMN_ACCOUNT_ID + " = :accountId";

    /** Name of the account-identifier parameter every statement above binds. */
    private static final String PARAM_ACCOUNT_ID = "accountId";

    /** Name of the amount parameter the reduction binds. */
    private static final String PARAM_AMOUNT = "amount";

    /**
     * The persistence context the three statements are issued through.
     *
     * <p>Assumptions: the container hands over a transaction-scoped proxy, so every statement joins the
     * transaction in progress on the calling thread. That is the whole mechanism this class depends on: a
     * statement issued outside the caller's transaction would commit separately and would reintroduce
     * exactly the split commit the class note rejects.</p>
     */
    private final EntityManager entityManager;

    /**
     * Builds the access path over the persistence context the container supplies.
     *
     * <p>Refactoring Rationale: the context arrives through the CONSTRUCTOR rather than through field
     * injection on an {@code @PersistenceContext} member. Both forms receive the same transaction-scoped
     * proxy, so the atomicity property is unaffected; what the constructor adds is that the dependency
     * cannot be absent. A field-injected member is null until the container populates it, which makes the
     * class unusable outside one and -- more to the point here -- makes it impossible to stand the real
     * access path up in an integration test beside a real engine, which is where the only property that
     * matters, enrolment in the caller's transaction, is observable at all. The field is also final now,
     * so nothing can replace the context after construction.</p>
     *
     * @param entityManager the persistence context every statement below is issued through; must not be
     *     {@code null}
     * @throws NullPointerException if {@code entityManager} is {@code null}
     */
    public AccountBalanceRepository(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    /**
     * Reads an account's balance and locks the row for the enclosing transaction.
     *
     * <p>Purpose: this is {@code READ-ACCTDAT-FILE} at line 343 of {@code app/cbl/COBIL00C.cbl} as it
     * is reached from the affirmative branch at line 177, where the read carries the {@code UPDATE}
     * option and its lock is consumed by the rewrite that follows.</p>
     *
     * @param accountId the account identifier, as the eleven-digit numeric value the column holds
     * @return the balance the row carries, or an empty optional when no row carries that identifier
     * @throws org.springframework.dao.DataAccessException if the statement could not be executed,
     *     which includes the permission failure a database provisioned without section 4b's grants
     *     produces
     */
    public Optional<Money> lockCurrentBalance(long accountId) {
        return firstBalance(LOCK_BALANCE, accountId);
    }

    /**
     * Reads an account's balance without taking a lock.
     *
     * <p>Purpose: this is the same paragraph reached from the withheld branch at line 184 of
     * {@code app/cbl/COBIL00C.cbl}, which reads the account so the balance can be displayed at lines
     * 193 and 194 beside the confirmation prompt and writes nothing.</p>
     *
     * @param accountId the account identifier, as the eleven-digit numeric value the column holds
     * @return the balance the row carries, or an empty optional when no row carries that identifier
     * @throws org.springframework.dao.DataAccessException if the statement could not be executed
     */
    public Optional<Money> findCurrentBalance(long accountId) {
        return firstBalance(READ_BALANCE, accountId);
    }

    /**
     * Reduces an account's balance by one amount inside the enclosing transaction.
     *
     * <p>Purpose: this is {@code UPDATE-ACCTDAT-FILE} at line 377 of {@code app/cbl/COBIL00C.cbl},
     * carrying the subtraction line 234 computes.</p>
     *
     * <p>Assumptions: the affected-row count is RETURNED rather than discarded, and the caller decides
     * what a count other than one means. The reference distinguishes two failures at this point -- a
     * not-found status answered at line 392 and any other status answered at line 399 -- so the count
     * is the fact this method has and the sentence is the caller's to choose.</p>
     *
     * @param accountId the account identifier whose balance is being reduced
     * @param amount the amount to subtract, being the whole balance as it was read under the lock;
     *     must not be {@code null}
     * @return the number of rows the statement changed, which is one when the account exists and zero
     *     when it does not
     * @throws NullPointerException if {@code amount} is {@code null}
     * @throws org.springframework.dao.DataAccessException if the statement could not be executed,
     *     which includes the permission failure a database provisioned without section 4b's grants
     *     produces
     */
    public int reduceCurrentBalance(long accountId, Money amount) {
        Query update = this.entityManager.createNativeQuery(REDUCE_BALANCE);
        update.setParameter(PARAM_ACCOUNT_ID, accountId);
        // WHY : Assumptions: the exact decimal is bound, not a primitive. The column is
        //       NUMERIC(12,2) and the shared money type's own scale is two, so binding its exact
        //       decimal keeps the subtraction in fixed point from end to end. Binding a double here
        //       would compile and would silently round, which transformation rule T3 forbids and the
        //       module's own architecture rule refuses.
        update.setParameter(PARAM_AMOUNT, amount.amount());
        return update.executeUpdate();
    }

    /**
     * Runs one balance statement and reads its single column.
     *
     * <p>Assumptions: the result is read as a list and its first element taken, rather than through a
     * single-result accessor. A single-result accessor raises when nothing was found, so an absent
     * account -- a condition the reference answers with an ordinary screen message at line 361 --
     * would arrive as an exception and would have to be caught to be turned back into an absence.</p>
     *
     * <p>Assumptions: the column is read as an exact decimal and converted through the shared money
     * type, whose constructor re-asserts the scale and the reference picture's magnitude bound. The
     * driver returns the column as an exact decimal already, so the conversion adds a check rather
     * than a representation change.</p>
     *
     * @param statement the statement to run, being one of the two reads declared above; must not be
     *     {@code null}
     * @param accountId the account identifier to bind
     * @return the balance the row carries, or an empty optional when the statement matched no row
     * @throws org.springframework.dao.DataAccessException if the statement could not be executed
     */
    private Optional<Money> firstBalance(String statement, long accountId) {
        Query read = this.entityManager.createNativeQuery(statement, BigDecimal.class);
        read.setParameter(PARAM_ACCOUNT_ID, accountId);
        // WHY : Assumptions: the result list is cast rather than the query being typed, because
        //       createNativeQuery's result-class overload returns a raw Query in this API generation
        //       rather than a TypedQuery. The result-class argument still selects the mapping the driver
        //       applies, which is why it is passed; the cast is the type the compiler cannot infer from a
        //       raw handle. It is confined to this one line so no caller sees an untyped value.
        List<?> rows = read.getResultList();
        return rows.stream()
                .findFirst()
                .map(BigDecimal.class::cast)
                .map(Money::of);
    }
}
