package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The only route to table {@code auth_fraud}, the fraud-tagged authorizations.
 *
 * <p><strong>Purpose.</strong> Carry the embedded-SQL statements the reference fraud program issues
 * against {@code CARDDEMO.AUTHFRDS} onto a typed interface, and publish the newest-first read that
 * the baseline's own secondary index was built to serve. The table has EXACTLY 26 columns and the
 * baseline states the number itself: {@code dcl/AUTHFRDS.dcl} L88 is the generated comment reading
 * that the number of columns described by the declaration is 26, and {@code ddl/AUTHFRDS.ddl} lists
 * one column per line at L2 to L27 with the primary key at L28. Every BASELINE citation in this
 * file is relative to {@code app/app-authorization-ims-db2-mq}, which is reference material this
 * migration reads and never modifies; target-side paths are named in full. The package-wide rulings
 * this interface inherits -- unqualified table naming, the absent transaction boundary and the money
 * type -- are stated once in {@code package-info.java} and are not restated here.
 *
 * <h2>The fraud-write seam</h2>
 *
 * <p>Assumptions: a write to this table touches EXACTLY TWO columns when the row already exists, and
 * the remaining twenty-four keep the values they were first inserted with.
 * {@code cbl/COPAUS2C.cbl} pins that shape: paragraph {@code FRAUD-UPDATE} at L221 issues
 * {@code UPDATE CARDDEMO.AUTHFRDS} at L223, whose {@code SET} list at L224 and L225 names
 * {@code AUTH_FRAUD} and {@code FRAUD_RPT_DATE} and nothing else, under a {@code WHERE} clause at
 * L226 to L228 matching the key alone. A write that refreshed the whole row instead would overwrite
 * the merchant, amount, response and identifier columns, and the snapshot those columns exist to
 * hold would then describe the authorization as it stood at the LATEST report rather than the first,
 * which is the one property that makes taking a snapshot worth anything.
 *
 * <p>Assumptions: the report date comes from the DATABASE on both write paths, never from this
 * process and never from a caller. The baseline supplies it positionally in the insert's value list
 * at {@code cbl/COPAUS2C.cbl} L194 and sets it in the update at L225, both times as the server's own
 * expression rather than as a host variable, which is why {@link #currentDate()} below exists.
 *
 * <p>Assumptions: WHICH of the two write paths ran stays observable, because two separate contracts
 * depend on it. The baseline reports two different sentences -- {@code 'ADD SUCCESS'} at
 * {@code cbl/COPAUS2C.cbl} L201 when the insert succeeded at L199, and {@code 'UPDT SUCCESS'} at
 * L232 when the duplicate-key fallback update succeeded at L230 -- and
 * {@code src/main/resources/openapi/authorization-api.yaml} publishes the same split as 201 for a
 * created row and 200 for an updated one. Both sentences are carried character for character by
 * {@code com.carddemo.authorization.dto.FraudMarkResponse}, which owns them; this interface owns only
 * the property that the distinction stays derivable, and {@link #findById(AuthFraudKey)} is what
 * makes it derivable -- an empty result selects the insert path and a present one selects the update
 * path.
 *
 * <p>Assumptions: the two FAILURE sentences are not success shapes and are not carried by this
 * boundary at all. The insert's failure arm at L206 to L214 composes its message from
 * {@code ' SYSTEM ERROR DB2: CODE:'} at L211 and {@code ', STATE: '} at L212, each with its exact
 * leading space, while the update's failure arm composes a DIFFERENT literal,
 * {@code ' UPDT ERROR DB2: CODE:'} at L239, from the same state fragment at L240. Four sentences
 * therefore exist across the pair, two reporting success and two reporting a database code. Neither
 * failure arm has a counterpart here because a failed statement raises rather than returns.
 *
 * <p>Alternatives Considered: expressing that control flow as one atomic statement --
 * {@code INSERT ... ON CONFLICT (card_num, auth_ts) DO UPDATE SET auth_fraud = EXCLUDED.auth_fraud,
 * fraud_rpt_date = CURRENT_DATE} behind a single modifying method, recovering the
 * insert-versus-update signal from {@code RETURNING (xmax = 0)}, where a true value means a fresh
 * insert. That statement is the migration's own transcription of the duplicate-key branch and
 * {@code db/migration/V1__authorization.sql} names it in the note on the primary key it would
 * conflict against, so it is the obvious candidate and it is not declined on merit. Three concrete
 * reasons put the read-then-write form in force instead. Its value list has to name all 26 columns,
 * so the method would take a parameter per column and would hold a second projection of the row
 * beside {@code com.carddemo.authorization.mapper.AuthFraudMapper}, which is the one place that
 * projection is documented -- and two projections of one row are two rows that can disagree. A
 * modifying statement also needs a transaction, and no transaction boundary is declared anywhere in
 * this package. And the read-then-write form already reaches the same discriminator with the same
 * two-column narrowness, so the invariant the atomic form exists to protect is held either way. Its
 * concrete consequence if adopted alongside is an uncalled second write path beside a called one,
 * which a later reader then has to choose between.
 *
 * <p>Assumptions: the find-then-write sequence the service performs treats THIS TABLE'S PRIMARY KEY
 * as the arbiter of a concurrent duplicate, and no lock is taken on the
 * {@code pending_auth_detail} row the fraud row is derived from. The reference system settles the
 * same collision the same way rather than preventing it: it inserts at {@code cbl/COPAUS2C.cbl}
 * L199 and, on the duplicate-key code, performs its update branch at L203 and L204 with the update
 * itself at L221 to L229. Holding the derived row would have been the alternative, and the package
 * charter records why it is not taken -- the reference retrievals are all non-hold forms, so nothing
 * is held there between a read and its write. The consequence accepted is that of two simultaneous
 * marks of one authorization, one is rejected rather than both being applied; both would assert the
 * same fraud state, so the committed state of the row does not depend on which one won.
 *
 * <h2>Column and constraint shape a caller can get wrong</h2>
 *
 * <p>Assumptions: this table carries NO CHECK constraint on either character-domain column, and the
 * absence is deliberate rather than an oversight. {@code ddl/AUTHFRDS.ddl} declares none anywhere,
 * and its L23 and L24 are bare {@code CHAR(1)}. The value domains are the copybook condition names
 * instead -- {@code PA-MATCH-STATUS} admits {@code 'P'}, {@code 'D'}, {@code 'E'} and {@code 'M'} at
 * {@code cpy/CIPAUDTY.cpy} L45 to L49, and {@code PA-AUTH-FRAUD} admits {@code 'F'} at L51 and
 * {@code 'R'} at L52 -- and that shared domain is asserted on {@code pending_auth_detail}, where it
 * has to tolerate a BLANK because {@code cbl/COPAUA0C.cbl} L908 and L909 move SPACE into both fields
 * when a row is first written. No blank reaches this table, whose single writer moves one of the two
 * marked values. A caller that inferred a CHECK here from the sibling table would expect a violation
 * this schema does not raise.
 *
 * <p>Assumptions: four column shapes are easy to assume wrongly, so they are stated once.
 * {@code merchant_name} is {@code VARCHAR(22)} at {@code ddl/AUTHFRDS.ddl} L18 and is the table's
 * ONLY variable-length column, so it is the only one whose trailing blanks are not padding; nothing
 * on this boundary trims it. {@code pos_entry_mode} is {@code SMALLINT} at L16 and not a character
 * code. {@code acct_id} and {@code cust_id} are the LAST TWO columns, at L26 and L27, and they arrive
 * from the caller rather than from either IMS segment. And the target spells the merchant
 * classification {@code merchant_category_code} where the baseline persists
 * {@code MERCHANT_CATAGORY_CODE} at L14, matching {@code cpy/CIPAUDTY.cpy} L36; that spelling
 * divergence is settled in the entity and the migration and is consumed here rather than reopened.
 *
 * <p>Assumptions: the character {@code 'F'} carries TWO unrelated meanings inside this one flow and
 * they must not be conflated. On this table's {@code auth_fraud} column, and on the request that sets
 * it, {@code 'F'} is the report-fraud command paired with {@code 'R'} -- the domain declared at
 * {@code cpy/CIPAUDTY.cpy} L50 to L52. On {@code FraudMarkResponse.updateStatus} the same character
 * means FAILED. Only the first of those two domains is this interface's.
 *
 * <p>Assumptions: a {@code *RepositoryIT} in this module's own test tree is REQUIRED to settle what
 * only a live engine can, and this interface relies on those assertions rather than restating them:
 * the composite primary key; the DIRECTION of the {@code (card_num ASC, auth_ts DESC)} index declared
 * by {@code db/migration/V1__authorization.sql}, taken from the CATALOGUE rather than from the index's
 * existence, since an all-ascending index would satisfy a name check; that a write to an existing row
 * leaves the other twenty-four columns byte-identical; that the insert-versus-update discriminator
 * reports each path correctly; that TWO CONCURRENT first marks both succeed rather than one aborting
 * on the primary key; and that the report date arrives from the server on both paths rather than from
 * a value the test supplied. All but the last of those are properties of {@link AuthFraudUpserter},
 * which owns both write arms, so the test asserts them through that collaborator rather than through
 * this interface. Nothing in this module is held to captured output -- no recorded output exists for
 * any path here and none is claimed for one, because the screens this data serves are CICS online
 * programs the reference suite documents as unable to run without a CICS runtime.
 *
 * <p>Refactoring Rationale: the index-direction assertion above used to be stated as the direction
 * "{@code findFraudHistoryForCard} reads through". That query has been withdrawn as uncalled, so the
 * obligation is restated against the SCHEMA OBJECT instead. The distinction matters: the index is a
 * specified parity requirement and outlives any particular reader of it, so the test must assert the
 * catalogue rather than a Java method that may not exist.
 */
public interface AuthFraudRepository extends JpaRepository<AuthFraud, AuthFraudKey> {

    /**
     * Reads the fraud row for one card and one composed authorization timestamp.
     *
     * <p>Assumptions: this narrows the inherited {@code findById} to a named signature rather than
     * adding an access path, and it is declared explicitly because the two write paths the published
     * contract distinguishes are chosen on its result. A reader tracing the 201-versus-200 decision
     * should find the read that decision turns on named in this interface rather than inferred from a
     * supertype.
     *
     * <p>Assumptions: the key's timestamp half is a typed {@code TIMESTAMP(6)} value, so this is a
     * typed timestamp comparison and needs no format mask. The baseline needed one --
     * {@code TIMESTAMP_FORMAT (:AUTH-TS, 'YY-MM-DD HH24.MI.SSNNNNNN')} at {@code cbl/COPAUS2C.cbl}
     * L227 and L228 -- only because its host variable was a two-digit-year character string that Db2
     * had to parse. There is no target analogue, and none should be reintroduced here.
     *
     * @param id the card number and composed timestamp forming the primary key that
     *     {@code ddl/AUTHFRDS.ddl} L28 declares; must not be {@code null}
     * @return the fraud row already recorded for that key, or an empty {@code Optional} when none is,
     *     in which case the caller takes the insert path
     * @throws IllegalArgumentException if {@code id} is {@code null}, which the inherited contract
     *     asserts before the query reaches the database
     */
    Optional<AuthFraud> findById(AuthFraudKey id);

    // WHY : Refactoring Rationale: a findFraudHistoryForCard query stood here, reading one card's
    //       fraud-tagged authorizations newest-first through the (card_num ASC, auth_ts DESC) index. It
    //       has been WITHDRAWN because it had no caller -- no service, no controller and no test in this
    //       module invoked it, and the published contract in openapi/authorization-api.yaml offers no
    //       fraud-history route for it to serve. The reference system offers none either: the fraud table
    //       is written by cbl/COPAUS2C.cbl and read back by no screen in the reference tree.
    // WHY : Assumptions: an uncalled query over this table is not merely dead weight, it is a standing
    //       liability. Its first parameter is an unmasked sixteen-digit primary account number, and the
    //       rows it returns carry the twenty-four-column authorization snapshot -- so the cheapest way for
    //       a later reader to build a fraud-history endpoint is to call a method that already takes a raw
    //       PAN, which is the shape the exposure rules narrow everywhere else. Removing it means such an
    //       endpoint has to declare its own access path and be reviewed on the way in.
    // WHY : Alternatives Considered: keeping it and adding a bounded authorized consumer plus tests.
    //       Rejected because that invents a capability neither the reference system nor the target
    //       contract asks for, and a capability added to satisfy a coverage gap is the wrong way round.
    // WHY : Assumptions: the INDEX the query read through is NOT withdrawn with it and must not be
    //       "cleaned up" as unused by a later reader. It is a specified requirement -- an index on
    //       (card_num ASC, auth_ts DESC) matching the reference ddl/XAUTHFRD.ddl, whose entire content is
    //       four lines with the index declared over exactly those directions at L3 -- and it is declared
    //       in db/migration/V1__authorization.sql, which carries the full reasoning: the hierarchical side
    //       reached the newest authorization first by complementing its key at cbl/COPAUA0C.cbl L874 and
    //       L875, because the DBD field at ims/DBPAUTP0.dbd L37 is a plainly ASCENDING character sequence
    //       with no descending option, and the relational side re-expressed that same intention as an
    //       index direction instead. Preserving the access path is the parity obligation; publishing a
    //       reader for it is not.

    /**
     * Reads the database server's own current date, which is what a fraud report is dated by.
     *
     * <p>Assumptions: the value comes from the SERVER and not from this process, because that is what
     * the reference system wrote and because the two can disagree. A container's clock, its
     * configured zone and the database session's zone are three independent settings, and a report
     * raised in the minutes either side of midnight is dated a day apart when they differ -- which is
     * the case where the date matters most, since it is the date an investigator filters on.
     *
     * <p>Assumptions: the query is native and reads no table, so it names none and does not depend on
     * the connection {@code search_path} that {@code config/DataSourceConfig} pins to the
     * {@code authorization} schema. Any future native statement added here does depend on it, and the
     * package charter requires such a statement to name {@code auth_fraud} unqualified.
     *
     * <p>Assumptions: the query is native and reads no table, so it costs one round trip and no lock.
     * A round trip is accepted because the caller is already inside a transaction with an open
     * connection, having re-read the authorization being marked; the alternative of deriving the date
     * from a row's own default would date the report from whenever the ROW was written rather than
     * from when the report was made.
     *
     * <p>Alternatives Considered: deriving the date from a column default, or from
     * {@code CURRENT_TIMESTAMP} truncated to a date. A column default would date the report from
     * whenever the ROW was written rather than from when the report was made, which are different
     * instants on the update path. Truncating a timestamp would also make the time available, but the
     * column the value is written into is a date, so the time would be discarded anyway, and two
     * callers truncating the same timestamp differently is exactly the divergence that sourcing the
     * value once removes.
     *
     * @return the database server's current date; never {@code null}
     */
    @Query(value = "SELECT CURRENT_DATE", nativeQuery = true)
    LocalDate currentDate();

    /**
     * Inserts one fraud row, or does nothing when its key is already taken, and says which happened.
     *
     * <p><strong>Purpose.</strong> This is the transcription of the reference insert AND of the branch it
     * takes when that insert is refused. {@code cbl/COPAUS2C.cbl} issues
     * {@code INSERT INTO CARDDEMO.AUTHFRDS} at L141 and L142 with its twenty-six-column list at L143 to
     * L168, tests the outcome at L199, and takes {@code PERFORM FRAUD-UPDATE} at L203 and L204 when the
     * code is {@code -803} -- the duplicate-key condition. The reference program therefore treats a taken
     * key as a NORMAL outcome that selects its other arm, not as a failure. This statement reproduces
     * exactly that: the key being taken is reported as a zero row count for the caller to branch on.
     *
     * <p>Refactoring Rationale: the create arm reached this table through {@code save}, preceded by a
     * separate {@code findById} probe. That pair is not atomic, and the two failure modes it produced were
     * both wrong. Two writers whose rows compose the SAME key -- reachable because the key is
     * {@code (card_num, auth_ts)} and the timestamp half is composed from the acquirer's originating date
     * and the authorization time rather than from the row's own primary key, so two authorizations of one
     * card can compose one fraud key -- both saw an absent row and both inserted, and the second failed at
     * COMMIT with an integrity violation that rolled the whole mark back. That is not the reference
     * behaviour: the reference program's second writer takes its update arm and succeeds. And because the
     * violation surfaced from the commit rather than from a statement, no code could branch on it -- a JPA
     * persistence context is not usable after a constraint failure on flush, so catching it and continuing
     * to the update arm inside the same transaction is not available either.
     *
     * <p>Assumptions: {@code ON CONFLICT DO NOTHING} is what makes the branch reachable, because it
     * REPORTS the conflict instead of raising it. The statement leaves the transaction usable and returns
     * zero, so the caller performs the update arm in the same unit of work the reference program uses. The
     * conflict target is left implicit -- {@code DO NOTHING} with no target covers every constraint on the
     * table, and the table has exactly one, the primary key declared at
     * {@code db/migration/V1__authorization.sql}. Naming it explicitly was the alternative and is rejected
     * for a specific reason: a named target that stops matching the constraint after a schema change
     * raises rather than absorbing, so the failure would return to being a rolled-back mark.
     *
     * <p>Assumptions: this is a NATIVE statement, so it names {@code auth_fraud} unqualified and depends
     * on the connection {@code search_path} that {@code config/DataSourceConfig} pins to the
     * {@code authorization} schema -- the requirement this package's charter states for exactly this case.
     *
     * <p>Assumptions: the row's twenty-six values are bound from the entity the caller already projected,
     * rather than through twenty-six method parameters. The entity is the single place the column-to-field
     * correspondence is declared, and a twenty-six-parameter signature would be a second declaration of it
     * in positional form -- where two columns of the same type transposed against each other compile,
     * pass every type check, and write a real but wrong row.
     *
     * <p>Assumptions: the report date is BOUND rather than written as {@code CURRENT_DATE}, even though
     * the reference insert supplies the server expression positionally at {@code cbl/COPAUS2C.cbl} L194.
     * The caller reads that date once through {@link #currentDate()} and uses the one value for both this
     * row and the authorization's own copy, which is registered as divergence
     * {@code D-AUTH-FRAUD-ONE-CLOCK}; writing {@code CURRENT_DATE} here would silently reintroduce the
     * second clock read that entry exists to record the removal of.
     *
     * <p>Assumptions: this method declares no transaction of its own, in keeping with the package charter,
     * so the CALLER must already be inside one -- a modifying query outside a transaction is refused by
     * the persistence abstraction, because only {@code save}, {@code delete} and {@code flush} inherit one
     * from the repository base. {@code FraudMarkingService.mark} is the only caller and is transactional.
     *
     * @param row the fully projected fraud row to insert, whose key and twenty-six column values are read
     *     from its own members; must not be {@code null}
     * @return {@code 1} when the row was inserted, {@code 0} when a row already held its key, which is the
     *     signal to take the replace arm
     */
    @Modifying
    @Query(value = """
            INSERT INTO auth_fraud (
                card_num, auth_ts, auth_type, card_expiry_date, message_type, message_source,
                auth_id_code, auth_resp_code, auth_resp_reason, processing_code, transaction_amt,
                approved_amt, merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id,
                merchant_name, merchant_city, merchant_state, merchant_zip, transaction_id,
                match_status, auth_fraud, fraud_rpt_date, acct_id, cust_id)
            VALUES (
                :#{#row.id.cardNum}, :#{#row.id.authTs}, :#{#row.authType}, :#{#row.cardExpiryDate},
                :#{#row.messageType}, :#{#row.messageSource}, :#{#row.authIdCode},
                :#{#row.authRespCode}, :#{#row.authRespReason}, :#{#row.processingCode},
                :#{#row.transactionAmt}, :#{#row.approvedAmt}, :#{#row.merchantCategoryCode},
                :#{#row.acqrCountryCode}, :#{#row.posEntryMode}, :#{#row.merchantId},
                :#{#row.merchantName}, :#{#row.merchantCity}, :#{#row.merchantState},
                :#{#row.merchantZip}, :#{#row.transactionId}, :#{#row.matchStatus},
                :#{#row.authFraud}, :#{#row.fraudRptDate}, :#{#row.acctId}, :#{#row.custId})
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertFraudRowIfAbsent(@Param("row") AuthFraud row);
}
