package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.Objects;
import org.springframework.stereotype.Repository;

/**
 * Applies a fraud report with one {@code INSERT ... ON CONFLICT DO UPDATE} statement.
 *
 * <p><strong>Purpose.</strong> The implementation of {@link AuthFraudUpserter}. The interface carries
 * the reference citations, the alternatives weighed and the trade-offs accepted; this class carries
 * only the statement and the decisions that belong to writing it.</p>
 *
 * <p>Assumptions: the class is package-private and is discovered as a bean by
 * {@link Repository @Repository}, so the only type any caller can hold is the interface. Matching
 * {@code TransactionCategoryBalanceWriterImpl} in {@code transaction-service} keeps the one other
 * writer of this shape in this repository recognisable as the same pattern.</p>
 */
@Repository
class AuthFraudUpserterImpl implements AuthFraudUpserter {

    /**
     * The statement carrying both arms of the reference program's duplicate-key branch.
     *
     * <p>Assumptions: the table is named UNQUALIFIED, which is required rather than stylistic. The
     * connection {@code search_path} is pinned to the {@code authorization} schema by
     * {@code config/DataSourceConfig}, and the package charter requires every native statement in this
     * package to rely on that pin rather than hard-coding a schema name -- a qualified name here would
     * be a second place the schema is decided and would break the moment a deployment renamed it.
     *
     * <p>Assumptions: the columns are listed EXPLICITLY and in the table's declared order rather than
     * relying on positional insertion. An explicit list survives a column being added to the table
     * ahead of {@code acct_id} and {@code cust_id}, which
     * {@code db/migration/V1__authorization.sql} places last; a positional insert would then bind
     * every value one column out and would fail on a type mismatch at best and write a real but wrong
     * row at worst.
     *
     * <p>Assumptions: {@code (xmax = 0)} is the insert-versus-update discriminator, and it is a
     * property of the row this statement just wrote rather than a count or a second read. PostgreSQL
     * leaves {@code xmax} zero on a freshly inserted tuple and non-zero on one this statement's update
     * arm superseded, so the returned boolean distinguishes the two arms in the same round trip.
     * Alternatives Considered: returning the report date and comparing it, or counting rows before and
     * after. Both rejected -- the first cannot distinguish the arms at all, because both write the same
     * date, and the second reintroduces exactly the read that made the previous implementation racy.
     *
     * <p>Assumptions: the update arm sets its two columns FROM {@code EXCLUDED} rather than from
     * separate bind parameters. {@code EXCLUDED} is the row this statement proposed, so the two arms
     * cannot disagree about what was requested -- a second pair of parameters could be bound
     * differently from the first and the row would then record a state nobody asked for.
     */
    private static final String UPSERT_FRAUD_ROW = """
            INSERT INTO auth_fraud (
                card_num, auth_ts, auth_type, card_expiry_date, message_type, message_source,
                auth_id_code, auth_resp_code, auth_resp_reason, processing_code,
                transaction_amt, approved_amt, merchant_category_code, acqr_country_code,
                pos_entry_mode, merchant_id, merchant_name, merchant_city, merchant_state,
                merchant_zip, transaction_id, match_status, auth_fraud, fraud_rpt_date,
                acct_id, cust_id
            ) VALUES (
                ?1, ?2, ?3, ?4, ?5, ?6,
                ?7, ?8, ?9, ?10,
                ?11, ?12, ?13, ?14,
                ?15, ?16, ?17, ?18, ?19,
                ?20, ?21, ?22, ?23, ?24,
                ?25, ?26
            )
            ON CONFLICT (card_num, auth_ts) DO UPDATE
               SET auth_fraud = EXCLUDED.auth_fraud,
                   fraud_rpt_date = EXCLUDED.fraud_rpt_date
            RETURNING (xmax = 0)
            """;

    /** The persistence context the statement is issued through. */
    private EntityManager entityManager;

    /**
     * Receives the container-managed persistence context.
     *
     * <p>Assumptions: the injection point is a SETTER rather than the field, which is the other
     * supported form of {@link PersistenceContext @PersistenceContext} and behaves identically at
     * runtime -- the container still supplies a transaction-scoped proxy, not a bare session.
     *
     * <p>Refactoring Rationale: the annotation was on the field. It moved because this class's only
     * statement is native SQL with an {@code ON CONFLICT} clause and a system-column discriminator,
     * none of which any mock can settle, so it has to be exercised against a real engine. An
     * integration test constructs this class directly rather than raising a Spring context for one
     * bean, and a private field would have left it reaching in by reflection -- which compiles, and
     * then breaks silently the day the field is renamed. A setter is a supported seam that the
     * compiler checks. Alternatives Considered: constructor injection, which is what every other
     * collaborator in this module uses. Rejected here because Spring does not register a plain
     * {@code EntityManager} bean for a constructor to receive; {@code @PersistenceContext} is the
     * mechanism that yields the transaction-scoped proxy, and it applies to fields and setters only.
     *
     * @param entityManager the container-managed persistence context; must not be {@code null}
     * @throws NullPointerException if {@code entityManager} is {@code null}
     */
    @PersistenceContext
    void setEntityManager(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Assumptions: the persistence context is FLUSHED before the statement and NOTHING is done to it
     * afterwards. The flush is required: a native statement bypasses the persistence context entirely,
     * so a pending change to another entity could otherwise reach the database after this row and
     * overwrite what this statement decided from.
     *
     * <p>Refactoring Rationale: this method used to CLEAR the persistence context after the statement,
     * and the clear was a defect rather than a precaution. {@code EntityManager.clear()} detaches every
     * entity in the CALLER's context, not merely this table's, so the authorization the fraud-marking
     * service had loaded became detached mid-method and the fraud position it then set on that instance
     * was silently discarded at commit: the fraud row was written, the authorization's own two fraud
     * members were not, and the two tables disagreed with nothing reported. The clear was aimed at a
     * different hazard -- a previously loaded {@link AuthFraud} for this key would still carry its
     * pre-statement values, because a native write is invisible to the context -- but it addressed that
     * hazard by discarding everything, and the caller's later write was collateral.
     *
     * <p>Assumptions: no eviction replaces it, because the hazard it aimed at is not reachable from the
     * one caller. The row handed in is built by the mapper and is DETACHED, and
     * {@code FraudMarkingService} loads no {@link AuthFraud} at all -- it reads the engine's current
     * date through the repository and nothing else -- so there is no cached copy of this table's row for
     * a stale read to find. Alternatives Considered: evicting just the key's instance, which needs a
     * lookup to obtain a reference and would issue a query purely to detach what it found; and refreshing
     * it, which needs a second read of a row nobody in this transaction reads. Both buy protection
     * against a caller that does not exist, and the contract on
     * {@link AuthFraudUpserter#upsert(AuthFraud)} states the precondition instead.
     */
    @Override
    public boolean upsert(AuthFraud row) {
        Objects.requireNonNull(row, "row must not be null");
        AuthFraudKey key = Objects.requireNonNull(row.getId(), "row must carry a key");

        this.entityManager.flush();

        Query statement = this.entityManager.createNativeQuery(UPSERT_FRAUD_ROW)
                .setParameter(1, key.getCardNum())
                .setParameter(2, key.getAuthTs())
                .setParameter(3, row.getAuthType())
                .setParameter(4, row.getCardExpiryDate())
                .setParameter(5, row.getMessageType())
                .setParameter(6, row.getMessageSource())
                .setParameter(7, row.getAuthIdCode())
                .setParameter(8, row.getAuthRespCode())
                .setParameter(9, row.getAuthRespReason())
                .setParameter(10, row.getProcessingCode())
                .setParameter(11, row.getTransactionAmt())
                .setParameter(12, row.getApprovedAmt())
                .setParameter(13, row.getMerchantCategoryCode())
                .setParameter(14, row.getAcqrCountryCode())
                .setParameter(15, row.getPosEntryMode())
                .setParameter(16, row.getMerchantId())
                .setParameter(17, row.getMerchantName())
                .setParameter(18, row.getMerchantCity())
                .setParameter(19, row.getMerchantState())
                .setParameter(20, row.getMerchantZip())
                .setParameter(21, row.getTransactionId())
                .setParameter(22, row.getMatchStatus())
                .setParameter(23, row.getAuthFraud())
                .setParameter(24, row.getFraudRptDate())
                .setParameter(25, row.getAcctId())
                .setParameter(26, row.getCustId());

        Object discriminator = statement.getSingleResult();

        // WHY : Assumptions: the returned value is interrogated as a Boolean rather than cast blindly,
        //       and a value that is neither TRUE nor FALSE raises instead of defaulting. Defaulting an
        //       unrecognised discriminator to false would report every insert as a transition, which is
        //       a 200 where the contract owes a 201 -- a wrong answer that no test asserting only the
        //       final row state would catch.
        if (discriminator instanceof Boolean inserted) {
            return inserted;
        }
        throw new IllegalStateException(
                "the fraud upsert reported neither an insert nor an update for the named authorization");
    }
}
