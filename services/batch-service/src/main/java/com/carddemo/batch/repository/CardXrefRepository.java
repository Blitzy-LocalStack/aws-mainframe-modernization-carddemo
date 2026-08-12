package com.carddemo.batch.repository;

import com.carddemo.batch.domain.CardXref;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.repository.Repository;

/**
 * Resolves card cross-reference rows for the batch chain, by card number and by account identifier.
 *
 * <p>The table behind this interface is {@code account.card_xref}, and one row of it ties a card
 * number to the account and the customer that hold it. The chain depends on that resolution because
 * a daily transaction names only a card: the account identifier a posting or an accrual step works
 * with is read from HERE rather than taken from the record being processed.</p>
 *
 * <h2>Why every member here is a read</h2>
 *
 * <p>Alternatives Considered: {@code JpaRepository}, which four siblings in this package extend and
 * which would have made this file shorter. Rejected because it inherits {@code save},
 * {@code saveAll}, {@code delete}, {@code deleteAll} and {@code flush}, and this interface must
 * expose none of them. {@code org.springframework.data.repository.Repository} contributes no member
 * of its own while still giving Spring Data enough to build a proxy, so the reachable surface is
 * exactly the three methods declared below, and the read-only guarantee is structural rather than a
 * convention that a later edit could relax by adding one call.</p>
 *
 * <p>Assumptions: the cross-reference has no writer anywhere in this module, and the evidence for
 * that is documentary rather than stylistic. All three reference programs consume it by reading --
 * {@code app/cbl/CBTRN02C.cbl:383}, {@code app/cbl/CBACT04C.cbl:394} and
 * {@code app/cbl/CBTRN01C.cbl:229} -- and not one of them issues a write or a rewrite against it.
 * The target states the same restriction through privileges rather than through style:
 * {@code data-migration/sql/V0__schemas_and_roles.sql:1215} grants this module's role
 * {@code SELECT} on the tables of the {@code account} schema, and line 1226 grants {@code UPDATE}
 * on {@code account.accounts} BY NAME, so no insert, update or delete privilege on the
 * cross-reference reaches this module at all. A mutator here would compile, deploy, and then be
 * refused by the database. {@link CardXref} declares no version column for the same reason -- there
 * is no update path for one to guard -- so exposing a mutator would offer a capability the owning
 * context has itself declined to model.</p>
 *
 * <h2>Three access paths over one table, and why they are not interchangeable</h2>
 *
 * <p>Assumptions: the second path is not an invention of this migration, and the reference expresses
 * both paths through a SINGLE file declaration. {@code app/cbl/CBACT04C.cbl:34-39} declares one file
 * carrying {@code RECORD KEY IS FD-XREF-CARD-NUM} at line 37 and
 * {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID} at line 38. Its driver corroborates that
 * independently by supplying TWO data definitions for the one dataset -- the base cluster at
 * {@code app/jcl/INTCALC.jcl:29-30} and the alternate-index path at
 * {@code app/jcl/INTCALC.jcl:31-32}, under the name {@code XREFFIL1}, which follows the convention
 * of appending a digit to name a first alternate key. The contrast settles the point:
 * {@code app/cbl/CBTRN02C.cbl:40-44} declares the same dataset with no alternate-key clause at all,
 * and {@code app/jcl/POSTTRAN.jcl:32-33} accordingly supplies the base cluster alone. One logical
 * table, two access paths, and a driver that mounts only the paths its program declares.</p>
 *
 * <p>Assumptions: the two KEYED paths run in OPPOSITE directions, which is what makes them
 * non-interchangeable rather than merely distinct. The by-card path yields an ACCOUNT IDENTIFIER:
 * posting moves the resolved value into an account key at {@code app/cbl/CBTRN02C.cbl:394} and reads
 * again at line 395, then moves that same value into the leading component of the category-balance
 * key at {@code app/cbl/CBTRN02C.cbl:469}. The by-account path yields a CARD NUMBER: accrual moves
 * the resolved value onto every generated interest transaction at
 * {@code app/cbl/CBACT04C.cbl:495}. Recording the direction of each path is what stops a reader
 * reaching for the one that cannot answer the question being asked.</p>
 *
 * <p>Assumptions: the THIRD path asks no question of a key at all, and it too is expressed by the
 * reference rather than introduced here. {@code app/cbl/CBEXPORT.cbl:47-51} declares the same dataset
 * a third way -- {@code ACCESS MODE IS SEQUENTIAL}, one record key, no alternate -- and its driver
 * mounts the base cluster alone at {@code app/jcl/CBEXPORT.jcl:53-54}, matching the pattern that a
 * program is given only the paths it declares. What distinguishes it from the two above is not the
 * key it reads but the CARDINALITY of its result: both keyed paths answer with one row, and this one
 * answers with the whole table. Confusing the third with the second is what loses records, so the
 * three are enumerated here rather than left for a reader to infer from three method names.</p>
 *
 * <h2>The by-account path is served by a NON-UNIQUE index</h2>
 *
 * <p>This is the most consequential fact about this interface, and two independent sources agree on
 * it. {@code app/jcl/XREFFILE.jcl:72-82} defines the alternate index over eleven bytes beginning at
 * byte 25, at line 74, and declares it {@code NONUNIQUEKEY} at line 75. Byte 25 is precisely the
 * sixteen-byte card number of {@code app/cpy/CVACT03Y.cpy:5} plus the nine-digit customer identifier
 * of line 6, so the indexed field is the eleven-digit account identifier of line 7. Independently of
 * the reference, the relational replacement is non-unique too:
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql:726} creates
 * {@code idx_card_xref_account_id} with a plain {@code CREATE INDEX} rather than a unique one, over
 * a table whose only uniqueness constraint is {@code pk_card_xref} on the card number at line 695.
 * <b>One account therefore legitimately holds many cards</b>, and every consequence recorded on the
 * by-account method below follows from that one sentence.</p>
 *
 * <p>Refactoring Rationale: this paragraph recorded that "only the single-row path has a caller, so
 * no list-returning member is declared", and invited a caller that genuinely needed every card of an
 * account to add such a member "ordered by card number so it stays deterministic, with its own
 * recorded rationale". That invitation is now taken up by the third member below, and it had to be:
 * {@code ExportJob} was listed here as a by-account caller that "needs no more than one row", and
 * that description was wrong. {@code app/cbl/CBEXPORT.cbl:47-51} declares the cross-reference file
 * {@code ACCESS MODE IS SEQUENTIAL} keyed on the card number and its paragraph at
 * {@code app/cbl/CBEXPORT.cbl:376-389} reads until end of file, so the reference exports one record
 * per CARD ROW. Reaching those rows one-per-account dropped every card of every multi-card account
 * from the exported dataset -- silently, because the dataset is still well formed and every record in
 * it is correct. Losing rows is the one failure a round-trip test cannot detect if the same shape
 * writes and reads.</p>
 *
 * <p>Trade-offs: the census of callers is therefore three paths across four sites, and the pairing
 * matters more than the count. {@code PreflightDailyTransactionsJob} and {@code PostTransactionsJob}
 * read by CARD because a feed record names one; {@code CalculateInterestJob} reads by ACCOUNT at a
 * control break because {@code app/cbl/CBACT04C.cbl:394-395} issues one keyed read per account and
 * never advances; {@code ExportJob} walks the WHOLE table because its reference paragraph does. No
 * by-account list-returning member is declared even now, because no caller wants the cards of one
 * account -- the export wants every row of the table, which the ordered walk below answers directly
 * and without a per-account query.</p>
 *
 * <h2>What this interface deliberately does not declare</h2>
 *
 * <p>Alternatives Considered: a native query, which is a standing temptation in a batch module
 * because a nightly pass is naturally set-shaped and reads more directly as one statement. Rejected
 * on the timing of the failure it admits. This module runs the persistence provider with schema
 * handling set to validation, and that pass compares MAPPING METADATA against the deployed table; it
 * never parses the text of a native statement. A property path that resolves to a column the schema
 * does not have is therefore reported at start-up, before a row is read, whereas a mistyped physical
 * column inside a native statement stays invisible until that statement executes -- which for this
 * module means part-way through a nightly chain, with earlier steps already committed. The hazard is
 * concrete rather than theoretical here: the batch-local mapping and the owning migration do not
 * agree on every physical spelling, since {@link CardXref} maps {@code customerId} onto
 * {@code customer_id} and {@code accountId} onto {@code account_id}. All three members below are
 * derived methods bound to property names {@link CardXref} declares, so no physical column name
 * appears anywhere in this file.</p>
 *
 * <p>Refactoring Rationale: two mainframe artifacts of the by-account path are retired outright
 * rather than translated, under the migration plan's transformation rule T6. The explicit index
 * build, {@code IDCAMS BLDINDEX} at {@code app/jcl/XREFFILE.jcl:100-102}, is retired because
 * PostgreSQL maintains an index transactionally as rows change, so a translated build step would be
 * scheduled work that accomplishes nothing. The path object defined at
 * {@code app/jcl/XREFFILE.jcl:90-92}, which exists so an alternate index can be presented to a
 * program as though it were a file, is retired because a relational secondary index is queryable
 * without any such object standing in front of it -- the second method below simply names the
 * property. What survives of all three artifacts is the KEY alone, as the index recorded above, and
 * <b>this interface must not declare that index</b>: it is owned by
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql}, and a second
 * declaration in a module holding no authority over the {@code account} schema would be a duplicate
 * that nothing could detect drifting from the first.</p>
 *
 * <h2>A simple name that collides with account-service, deliberately</h2>
 *
 * <p>Assumptions: {@code com.carddemo.account.repository.CardXrefRepository} also exists. Same
 * simple name, different package, different declaring module, and the collision must not be resolved
 * by reaching for the other one. That is barred twice over. Account-service's interface is typed on
 * a {@code com.carddemo.account.domain} entity, so importing it would take a dependency on a foreign
 * {@code domain} class and fail the layering rule that
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * enforces as a test rather than as a review comment; and account-service is not a dependency of
 * this module at all, since the only intra-repository Maven dependency declared in
 * {@code services/batch-service/pom.xml} is {@code common-lib}, so that type is not on this module's
 * compile classpath to be imported in the first place. The duplication is the deliberate consequence
 * of mapping this table locally, and it is not an oversight to be tidied away.</p>
 *
 * <h2>How the citations above are to be read</h2>
 *
 * <p>Assumptions: every {@code app/} path cited above is reference material, read for provenance
 * only. Nothing under {@code app/} is read at run time and nothing under it is modified by this
 * migration: the reference programs remain byte-identical and keep running, which is precisely what
 * lets them serve as the oracle this module is measured against. Line numbers refer to the source as
 * committed, and columns 73 to 80 of a COBOL or JCL line carry a sequence field that is not part of
 * the statement. Where the two implementations differ, the permitted framing is settled and narrow:
 * the reference program does one thing, the migrated job does another, and the difference is
 * recorded in the divergence register at
 * {@code docs/architecture/cobol-to-service-traceability.md}. Nothing here is described as amending
 * or improving upon a reference behaviour, because that behaviour is the specification this work is
 * compared against.</p>
 *
 * @see CardXref
 */
public interface CardXrefRepository extends Repository<CardXref, String> {

    /**
     * Resolves one cross-reference row by the card number that keys it.
     *
     * <p>This is the lookup the pre-posting and posting steps perform on every record of the daily
     * feed, and it is the whole of the by-card access path. The row it returns carries the account
     * identifier those steps go on to read the account master by.</p>
     *
     * @param cardNum the sixteen-character card number exactly as the feed record carries it,
     *     neither padded nor masked, of type {@code String}; must not be {@code null}
     * @return the one matching row as an {@code Optional<CardXref>}, or an EMPTY optional when no
     *     row is keyed by that card number -- which is a business outcome rather than an error, so
     *     this method reports absence and never raises on it
     * @throws org.springframework.dao.DataAccessException if the read itself cannot be carried out
     *     -- most usefully, a permission failure when the {@code SELECT} grant recorded on this
     *     interface is missing, which surfaces here rather than at start-up because a privilege is
     *     checked when a statement executes and not when the mapping is validated
     */
    // Alternatives Considered: declaring this read as findById, which is the conventional name for
    //     a lookup on a primary key and which the card number genuinely is. Rejected for two
    //     reasons. The narrow base type recorded on this interface contributes no member of its
    //     own, so findById is not inherited here and would have to be declared explicitly either
    //     way -- the choice is purely one of name, not of surface. Given that, naming the property
    //     is the more informative of the two: findByCardNum states WHICH column answers the query,
    //     where findById leaves a reader to open the entity to discover it, and it is the name the
    //     four call sites in com.carddemo.batch.job already read by. It also keeps every member of
    //     this interface in one derived-name idiom, so none looks like the special case.
    // Assumptions: the single-result contract this optional states is a claim about the SCHEMA,
    //     exactly as the sibling BatchRunRepository records for its own finder, and here the claim
    //     is discharged by a primary key rather than by a secondary constraint. The base cluster
    //     keys on all sixteen bytes from byte zero, app/jcl/XREFFILE.jcl:43, matching the card
    //     number at the head of the fifty-byte layout in app/cpy/CVACT03Y.cpy:5, and the target
    //     agrees at V1__account.sql:695 where pk_card_xref is declared on card_num alone. So this
    //     lookup IS single valued and the optional is genuinely safe -- in explicit contrast with
    //     the by-account method below, whose index carries no such guarantee and which therefore
    //     cannot be shaped this way.
    // Assumptions: both consumers of this path are given the base cluster and nothing else, so
    //     neither can reach the alternate index even accidentally. Posting declares the file with
    //     no alternate-key clause at app/cbl/CBTRN02C.cbl:40-44 and its driver supplies only the
    //     base cluster at app/jcl/POSTTRAN.jcl:32-33; the pre-posting pass declares the same single
    //     record key at app/cbl/CBTRN01C.cbl:43 and names it explicitly on the read itself at
    //     app/cbl/CBTRN01C.cbl:230.
    // Assumptions: absence is reported neutrally because the two callers of this method do
    //     different things with it, and a repository that chose for them would have to be wrong for
    //     one. Posting turns a miss into reject reason 100 with the text
    //     'INVALID CARD NUMBER FOUND' -- app/cbl/CBTRN02C.cbl:385 and 386 -- and continues with the
    //     next feed record; the pre-posting pass merely records a soft status and carries on, at
    //     app/cbl/CBTRN01C.cbl:233. Raising here would convert both of those business outcomes into
    //     a failed step.
    Optional<CardXref> findByCardNum(String cardNum);

    /**
     * Resolves the lowest-numbered card of one account, over the non-unique by-account index.
     *
     * <p>This is the by-account access path, and it is the lookup the interest accrual step performs
     * once per account at a control break. The row it returns supplies the card number that accrual
     * writes onto every interest transaction it generates for that account.</p>
     *
     * <p>The declaration is ordered and bounded on purpose. Because the index behind it admits many
     * rows per account, an ordering makes the result reproducible and the bound of one row makes the
     * optional return type safe: this method cannot raise an incorrect-result-size failure however
     * many cards the account holds, which is the guarantee the shape exists to provide.</p>
     *
     * @param accountId the eleven-digit account identifier to resolve a card for, of type
     *     {@code Long}, and the sole column of the non-unique index that answers this query; must
     *     not be {@code null}
     * @return the lowest-numbered card's row for that account as an {@code Optional<CardXref>}, or
     *     an EMPTY optional when the account holds no card at all -- absence is reported and never
     *     raised, and what it means is the caller's to decide
     * @throws org.springframework.dao.DataAccessException if the read itself cannot be carried out
     *     -- most usefully, a permission failure when the {@code SELECT} grant recorded on this
     *     interface is missing, which surfaces here rather than at start-up because a privilege is
     *     checked when a statement executes and not when the mapping is validated
     */
    // Alternatives Considered: the obvious one-line finder, Optional<CardXref>
    //     findByAccountId(Long) -- unordered and unbounded -- was evaluated and REJECTED, and it is
    //     recorded here rather than merely avoided because it is the trap this whole interface is
    //     shaped around. It compiles, it reads better than the name below, and it passes any test
    //     fixture built from accounts holding a single card. It then raises an incorrect-result-size
    //     data-access failure in production, on entirely valid data, the first time it meets an
    //     account holding two -- because the index it reads through is NOT unique, per
    //     app/jcl/XREFFILE.jcl:74-75 declaring KEYS(11,25) NONUNIQUEKEY and per
    //     V1__account.sql:726 creating idx_card_xref_account_id as a plain non-unique index. Both
    //     sources are recorded on the type above. A latent failure that only valid data can trigger
    //     is worse than one a fixture catches, which is why the bound below is not optional.
    // Assumptions: bounding to one row is FAITHFUL rather than lossy, because the reference itself
    //     consumes exactly one row per account even though many may match. The read at
    //     app/cbl/CBACT04C.cbl:394-395 is a single keyed READ naming the alternate key,
    //     KEY IS FD-XREF-ACCT-ID, issued after the account identifier is moved into that key at
    //     app/cbl/CBACT04C.cbl:204; a keyed read over a non-unique alternate index returns the FIRST
    //     matching record in alternate-key order and never advances, and the program never issues a
    //     subsequent read to reach the rest. Returning a collection here would therefore hand the
    //     caller rows the reference never sees, and returning one row is what reproduces the
    //     behaviour being migrated.
    // Trade-offs: the tie-break among an account's cards is OURS, and that has to be admitted
    //     rather than presented as parity. The reference's notion of "first" is an artefact of the
    //     physical ordering of the alternate index and is not reproducible in a relational store,
    //     which returns rows in no guaranteed order unless one is requested. Ordering by the card
    //     number -- the primary key, per V1__account.sql:695 -- is the only tie-break that is stable
    //     across loads and across environments, so it is chosen deliberately. The reference selects
    //     the first row in physical alternate-key order, this method selects the lowest card number,
    //     and the divergence is registered in
    //     docs/architecture/cobol-to-service-traceability.md rather than left implicit. The
    //     compromise accepted is that a multi-card account may resolve to a different card here than
    //     on the reference platform; what is bought is that it resolves to the SAME card on every
    //     run, which the injected business date and the golden-master comparison both require. An
    //     unordered read would not fail, it would silently vary the card number on a generated
    //     interest transaction between runs of identical input.
    // Assumptions: this method reports absence neutrally, and the decision about what absence MEANS
    //     belongs to the caller -- which matters here because the reference treats it far more
    //     harshly than the by-card path does. At app/cbl/CBACT04C.cbl:396-397 a miss displays
    //     'ACCOUNT NOT FOUND: ' with the identifier, but the status test immediately after at
    //     app/cbl/CBACT04C.cbl:400 admits a clean status alone, and the failure branch at
    //     app/cbl/CBACT04C.cbl:408-411 abends the program. The migrated callers choose otherwise:
    //     CalculateInterestJob resolves an absent row to an empty card number and logs the account
    //     rather than abending, which is registered as divergence D-INTEREST-ORPHAN-ROW in
    //     docs/architecture/cobol-to-service-traceability.md, and ExportJob passes over the
    //     account. That divergence is the callers' to own and to justify; pushing it into this
    //     method by raising on absence would take the choice away from all of them at once and
    //     would make the by-account path unusable by the one caller that legitimately tolerates a
    //     gap.
    Optional<CardXref> findFirstByAccountIdOrderByCardNumAsc(Long accountId);

    /**
     * Walks every cross-reference row once, in ascending card-number order.
     *
     * <p>This is the third and last access path, and it belongs to the export alone. It is the
     * sequential pass of {@code app/cbl/CBEXPORT.cbl:4000-EXPORT-XREFS} at {@code :376-389}: an
     * unkeyed {@code READ} at {@code :393} repeated until the end-of-file condition declared at
     * {@code :107}, over a file opened {@code ACCESS MODE IS SEQUENTIAL} with
     * {@code RECORD KEY IS XREF-CARD-NUM} at {@code :47-51}. One record is written per ROW of the
     * table, which is one per card rather than one per account.</p>
     *
     * @return a lazily-evaluated {@code Stream<CardXref>} over every cross-reference row in
     *     ascending card-number order; never {@code null}, possibly empty, and the CALLER owns
     *     closing it
     * @throws org.springframework.dao.InvalidDataAccessApiUsageException if the stream is opened
     *     without a surrounding transaction to keep the underlying connection open for the duration
     *     of the walk; the caller satisfies this by consuming the stream inside the transaction its
     *     step already runs in
     * @throws org.springframework.dao.DataAccessException if the read itself cannot be carried out
     *     -- most usefully, a permission failure when the {@code SELECT} grant recorded on this
     *     interface is missing, which surfaces here rather than at start-up because a privilege is
     *     checked when a statement executes and not when the mapping is validated
     */
    // Assumptions: the ordering is on the CARD NUMBER and is declared on the method name rather than
    //     left to the query planner, because the card number is what the reference's sequential pass
    //     is ordered by -- app/cbl/CBEXPORT.cbl:50 names it as the record key and an indexed
    //     sequential read returns rows in record-key order. Ordering by anything else, or by nothing,
    //     would emit the same SET of records in a different sequence, and the export's record
    //     sequence number is assigned in write order, so the sequence numbers would then differ
    //     between two runs over identical data.
    // Trade-offs: a stream rather than a list, matching the account and ledger walks the same job
    //     already opens. The cross-reference holds one row per card issued, so materialising it would
    //     make the export's memory profile a function of the size of the portfolio. The cost is a
    //     resource the caller must release, which is why the obligation is stated on the return tag
    //     and why the export opens it in a try-with-resources block.
    // Alternatives Considered: reaching these rows through the by-account member above, once per
    //     exported account, which is what this job did before and which needed no new member at all.
    //     REJECTED because it is lossy rather than merely indirect: that member is bounded to one row
    //     by design -- the bound is what makes its optional return type safe over a NONUNIQUE index
    //     -- so an account holding three cards contributed one cross-reference record and the other
    //     two were absent from the dataset with nothing reporting it. It also issued one query per
    //     account to read a table the reference reads once.
    // Alternatives Considered: an unbounded findAllByAccountIdOrderByCardNumAsc(Long) returning a
    //     list per account, keeping the per-account loop but making it complete. Rejected on two
    //     counts. It would still issue one query per account, and it would emit records grouped by
    //     account rather than ordered by card number, so the record sequence would not match the
    //     reference's even though the set would.
    Stream<CardXref> findAllByOrderByCardNumAsc();
}
