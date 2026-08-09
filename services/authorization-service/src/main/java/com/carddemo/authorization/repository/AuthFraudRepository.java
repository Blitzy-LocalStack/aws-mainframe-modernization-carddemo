package com.carddemo.authorization.repository;

import com.carddemo.authorization.domain.AuthFraud;
import com.carddemo.authorization.domain.AuthFraudKey;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
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
 * the composite primary key; the DIRECTION of the index {@link #findFraudHistoryForCard} reads
 * through, taken from the catalogue rather than from the index's existence, since an all-ascending
 * index would satisfy a name check; that a write to an existing row leaves the other twenty-four
 * columns byte-identical; that the insert-versus-update discriminator reports each path correctly;
 * and that the report date arrives from the server on both paths rather than from a value the test
 * supplied. Nothing in this module is held to captured output -- no recorded output exists for any
 * path here and none is claimed for one, because the screens this data serves are CICS online
 * programs the reference suite documents as unable to run without a CICS runtime.
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

    /**
     * Reads one card's fraud-tagged authorizations, most recent first.
     *
     * <p>Refactoring Rationale: the ordering is {@code card_num} ascending then {@code auth_ts}
     * descending because THREE independent places in the baseline encode that one intention, and the
     * declared index is only the nearest of them. First, {@code ddl/XAUTHFRD.ddl} -- whose entire
     * content is four lines, the first of them the {@code CREATE} itself rather than a licence header
     * -- declares the index over {@code (CARD_NUM ASC, AUTH_TS DESC)} at L3. Second,
     * {@code cbl/COPAUA0C.cbl} L874 and L875 compute a nines complement, {@code 99999 - WS-YYDDD} and
     * {@code 999999999 - WS-TIME-WITH-MS}, so the hierarchical side reached the newest authorization
     * first by complementing the KEY. Third, {@code ims/DBPAUTP0.dbd} L37 declares that key as
     * {@code FIELD NAME=(PAUT9CTS,SEQ,U),START=1,BYTES=8,TYPE=C}, a plainly ASCENDING character
     * sequence -- which is why the complement was needed at all, since a DBD field has no descending
     * option. The relational side had no complement to lean on and re-expressed the same intention as
     * an index direction. The alternative was to order ascending on the second column, or to leave
     * both directions to the default; its concrete consequence is that this read stops matching the
     * declared index, so the engine answers it by walking that index backwards or by sorting, which
     * is a different access path from the one the baseline shipped.
     *
     * <p>Assumptions: newest-first is expressed over the DECODED value and never over a complement.
     * The complemented integers are an IMS key-ordering device; they are neither stored nor sorted by
     * here. The baseline itself decodes the complement the moment it wants date semantics, at
     * {@code cbl/CBPAUP0C.cbl} L280, whose {@code 99999 - PA-AUTH-DATE-9C} is the exact inverse of
     * the computation cited above. This query therefore orders a real {@code TIMESTAMP(6)}
     * descending.
     *
     * <p>Assumptions: the index this ordering matches is a SEPARATE schema object from the primary
     * key over the same two columns, which in PostgreSQL it has to be -- a unique constraint's own
     * tree ascends in both columns and so does not carry this access path, and neither would an index
     * repeating the key's directions. The baseline index is additionally unique and carries
     * {@code COPY YES}; uniqueness is already asserted by the primary key, and {@code COPY YES} is a
     * Db2 image-copy attribute whose equivalent is the cluster's automated backups and point-in-time
     * recovery, which are infrastructure rather than schema and so are not an index option. Both
     * dispositions are recorded in {@code db/migration/V1__authorization.sql}, which declares the
     * index this method reads through.
     *
     * <p>Trade-offs: the result is bounded by an explicit limit and by nothing else, so a caller
     * steps a card's history inward from its most recent end and cannot address an arbitrary position
     * within it. That is accepted because the baseline exposes no positional addressing either, and
     * because a positional bound would have to count from the start of the ordering on every call, so
     * a row inserted ahead of that point between two calls shifts every later row and the reader then
     * either misses a row or sees one twice.
     *
     * @param cardNum the sixteen-character primary account number whose history is read, matched
     *     against the card-number half of the key; must not be {@code null}
     * @param limit the greatest number of rows to return, counted from the most recent;
     *     {@code Limit.unlimited()} returns the card's whole history
     * @return that card's fraud rows ordered most recent first and bounded by {@code limit}, or an
     *     empty list when the card has none
     */
    @Query("""
            select f
              from AuthFraud f
             where f.id.cardNum = :cardNum
             order by f.id.cardNum asc, f.id.authTs desc
            """)
    List<AuthFraud> findFraudHistoryForCard(@Param("cardNum") String cardNum, Limit limit);

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
}
