package com.carddemo.batch.repository;

import com.carddemo.batch.domain.Account;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads and rewrites the account master rows that the migrated batch jobs adjust.
 *
 * <p>The table behind this interface is {@code account.accounts}. Three migrated programs reach it
 * and every one of them reaches it the same way -- by key. The pre-posting pass reads it at
 * {@code app/cbl/CBTRN01C.cbl:243}, the posting job at {@code app/cbl/CBTRN02C.cbl:395} and the
 * interest accrual at {@code app/cbl/CBACT04C.cbl:373}. Two of the three then rewrite the record
 * they just read: posting at {@code app/cbl/CBTRN02C.cbl:554} and accrual at
 * {@code app/cbl/CBACT04C.cbl:356}. A keyed read, an ordered walk for the operator-invoked export,
 * and an update through the inherited save are therefore the whole of what this interface has to
 * offer.</p>
 *
 * <h2>The table is not this module's to own</h2>
 *
 * <p>Assumptions: this module's authority over {@code account.accounts} is a GRANT and not
 * ownership. {@code account-service} owns the table; the migration plan's section 0.4.1.3 records
 * the narrowly-scoped cross-schema privilege on {@code account} that lets a batch job rewrite an
 * account master, and names it the one documented exception to database-per-service purity in the
 * entire migration. Reading that as ownership is the mistake to avoid, because it would invite
 * exactly the reach this interface refuses: an insert, a delete, or a second table in the same
 * schema. The grant carries the read and the update and stops there.</p>
 *
 * <p>Trade-offs: {@code services/account-service/src/main/resources/db/migration/V1__account.sql}
 * is normative for the shape of the table, and nothing here restates it. This interface declares no
 * data definition, no index and no constraint, so a claim about a column made in this file could
 * only ever be a copy that drifts. What is accepted in exchange is that the two artifacts have to
 * be read together: the property names this interface binds to live on
 * {@code com.carddemo.batch.domain.Account}, and the columns those properties map to live in that
 * migration. The compensating control is that the mapping is asserted against the deployed schema
 * when the process starts -- {@code services/batch-service/src/main/resources/application.yml} sets
 * schema handling to {@code validate} -- so a disagreement is reported before a row is read rather
 * than part-way through a nightly chain.</p>
 *
 * <h2>Why the update crosses a schema boundary instead of calling the owning service</h2>
 *
 * <p>The reason is a single observation about the reference posting paragraph, and it is short
 * enough to check. {@code app/cbl/CBTRN02C.cbl:440} performs the category-balance update,
 * {@code app/cbl/CBTRN02C.cbl:441} performs the account update and
 * {@code app/cbl/CBTRN02C.cbl:442} performs the transaction write, one immediately after another,
 * and the paragraph reaches its exit at {@code app/cbl/CBTRN02C.cbl:444} with all three applied or
 * with none of them applied. <b>There is no commit verb between them.</b> The migration plan's
 * section 0.4.1.3 requires that to remain one atomic commit, which is what forces the update to be
 * a local one issued inside the caller's own transaction.</p>
 *
 * <p>Alternatives Considered: a remote call to {@code account-service} to perform the account
 * update. Rejected because a call over the network cannot enlist in the caller's local transaction,
 * so the three writes above would stop committing together: the category balance and the
 * transaction would commit here while the account update committed, or failed, somewhere else. The
 * atomicity the reference paragraph gets for free would have to be rebuilt, and the rebuilt version
 * would be weaker than the original.</p>
 *
 * <p>Alternatives Considered: a saga, with each of the three writes committing separately and a
 * compensating reversal for whichever step failed. Rejected because it makes an intermediate state
 * observable that the reference never produces -- a transaction posted to the ledger while the
 * account balance still lacks it -- and the committed expectation files compare the posted
 * transaction, the category balance and the account master together. A window in which they
 * disagree is a parity failure that the comparison would correctly report, so the design would
 * break the oracle it is measured against.</p>
 *
 * <p>Alternatives Considered: a transactional outbox holding the account update, drained after the
 * ledger write commits, with a compensating reversal on failure. Rejected for the same reason as the
 * saga and with the same consequence: the outbox row is durable but the account balance is not yet
 * adjusted while it sits there, so the observable window exists just as it does above. The outbox
 * pattern earns its place in this migration where the thing being published is a MESSAGE whose loss
 * is otherwise unrecoverable, which is the authorization context's reply, and not where the thing
 * being deferred is a write that already had atomicity.</p>
 *
 * <h2>Where the account key comes from, and why an optional is a safe return</h2>
 *
 * <p>Assumptions: the account identifier is the primary key of a single-valued lookup, and that is
 * asserted from two independent places rather than assumed. {@code app/jcl/ACCTFILE.jcl:40} defines
 * the cluster with {@code KEYS(11 0)} -- an eleven-byte key beginning at displacement zero -- and
 * {@code app/cpy/CVACT01Y.cpy:5} declares {@code ACCT-ID PIC 9(11)} as the first member of the
 * three-hundred-byte record, so the key is the account identifier and nothing else. On the target
 * side {@code V1__account.sql} declares {@code CONSTRAINT pk_accounts PRIMARY KEY (account_id)}.
 * Both sides therefore match at most one row, which is what makes an optional an honest return type
 * here instead of a signature that would raise an incorrect-result-size failure the first time a
 * second row matched.</p>
 *
 * <p>Assumptions: <b>the key arrives from a preceding lookup and never from the daily transaction
 * record.</b> This is the easiest thing on this interface to get wrong, because a caller that took
 * the identifier from the wrong place would still compile and would still find an account. In the
 * posting job the cross-reference is resolved by card number first, and
 * {@code app/cbl/CBTRN02C.cbl:394} moves that result's account identifier into the account key
 * immediately before the read on the following line. In the interest accrual the identifier instead
 * comes from the leading component of the category-balance key, moved into the account key at
 * {@code app/cbl/CBACT04C.cbl:202}. Two callers, two origins, and neither of them the feed record
 * -- the reference never uses a field of the daily transaction for this purpose.</p>
 *
 * <p>Assumptions: absence is reported neutrally because the two callers disagree about what it
 * means, and the disagreement is theirs to settle rather than this interface's. In the posting job a
 * missing account is a business outcome: {@code app/cbl/CBTRN02C.cbl:397} moves reject reason 101
 * and {@code app/cbl/CBTRN02C.cbl:398} moves the text {@code ACCOUNT RECORD NOT FOUND}, and the
 * record goes to the reject stream while the run continues. In the interest accrual the same
 * absence is fatal -- {@code app/cbl/CBACT04C.cbl:389} performs the abend paragraph. In the
 * pre-posting pass it is softer still, {@code app/cbl/CBTRN01C.cbl:247} moving a status of 4. A
 * repository that raised on absence would collapse those three outcomes into one and would turn a
 * documented reject into an abend, so the read below must not raise and does not.</p>
 *
 * <h2>The optimistic-locking ruling for batch callers</h2>
 *
 * <p>{@code com.carddemo.batch.domain.Account} carries a {@code @Version} member, so a lost update
 * is detected by the persistence provider when the caller's transaction flushes. What a batch
 * caller must then DO is the part worth stating plainly, because the instinctive answer is the wrong
 * one.</p>
 *
 * <p>Trade-offs: <b>a batch step that loses the version race must fail the step and let the
 * orchestrator's retry re-execute it. It must not quietly re-read the row and re-apply its
 * adjustment.</b> The reason is behavioural fidelity rather than convenience. The reference rewrite
 * accepts a clean file status and nothing else: {@code app/cbl/CBACT04C.cbl:357} tests the status
 * for {@code '00'}, {@code app/cbl/CBACT04C.cbl:360} sets a failing result for anything else, and
 * {@code app/cbl/CBACT04C.cbl:368} performs the abend paragraph. Posting is no more forgiving --
 * the rewrite at {@code app/cbl/CBTRN02C.cbl:554} carries an invalid-key branch that records reason
 * 109 at {@code app/cbl/CBTRN02C.cbl:556}. Neither program re-reads and retries in place. A silent
 * retry here would convert an abend in the reference into a success in the migration, which is a
 * behavioural divergence, and it would risk applying the same monetary adjustment twice. What is
 * accepted in exchange for the harsher failure is that the re-execution path is genuinely safer
 * than a retry inside the step would be: the per-state retry of the orchestrated chain re-runs the
 * step from a known point, and the durable step ledger in {@code batch.batch_run}, reached through
 * {@link BatchRunRepository}, is what stops an already-completed step from repeating its writes.</p>
 *
 * <p>Assumptions: a conflict is an operational signal rather than routine contention, which is why
 * failing is proportionate. The batch window is bracketed by the quiesce and resume states of the
 * migration plan's section 0.4.1.7, so an online write landing inside it means the read-only flag
 * was not honoured, and two batch steps contending for one account master means two steps
 * overlapped that were sequenced not to.</p>
 *
 * <p>Assumptions: optimistic concurrency is not an invention of this migration, and framing it as
 * one would misstate the lineage. The online account-update program already implements it by hand
 * across the pseudo-conversational gap: {@code app/cbl/COACTUPC.cbl:669} snapshots the whole
 * pre-edit record into a before-image, and the condition
 * {@code DATA-WAS-CHANGED-BEFORE-UPDATE} at {@code app/cbl/COACTUPC.cbl:521} is the comparison that
 * refuses a rewrite when the stored record moved underneath the edit. The version member is the
 * native expression of a pattern the reference already contains, not a capability added on top of
 * one that lacked it.</p>
 *
 * <h2>The interest job's account update is also a billing-cycle reset</h2>
 *
 * <p>Assumptions: the update the accrual performs on a control break does THREE things, and a
 * caller that wrote only the balance would leave the row half-adjusted.
 * {@code app/cbl/CBACT04C.cbl:352} adds the accumulated interest to the current balance,
 * {@code app/cbl/CBACT04C.cbl:353} moves zero into the current-cycle credit member and
 * {@code app/cbl/CBACT04C.cbl:354} moves zero into the current-cycle debit member, and only then
 * does {@code app/cbl/CBACT04C.cbl:356} rewrite the record. The accrual step is consequently a
 * billing-cycle reset as well as a balance adjustment. This is recorded here because it is a side
 * effect that the migration plan's own text does not mention, so a reader working from the plan
 * alone would write the balance, leave the two cycle members holding the closed cycle's totals, and
 * carry the error forward into the next cycle where nothing would flag it.</p>
 *
 * <p>Refactoring Rationale: the reference flushes the last account of the walk through a path that
 * cannot be reached, and the migrated job calls the update for it. {@code app/cbl/CBACT04C.cbl:188}
 * opens the control-break loop with a termination condition evaluated before each iteration, and
 * the branch at {@code app/cbl/CBACT04C.cbl:219} and {@code app/cbl/CBACT04C.cbl:220} -- the only
 * path that flushes the final account -- is entered only once the end-of-file flag is already set,
 * which that same condition prevents. The final account's accumulated interest is therefore never
 * added to its balance and its two cycle members are never returned to zero. The migrated job calls
 * the update once per control break and once more after the walk ends, so every account including
 * the last is flushed; the reference is unaltered and the divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}. The register lives there rather than
 * here -- this interface only needs its reader to know that the update arrives once per control
 * break and once again at the end, because that is what makes the number of writes it sees add
 * up.</p>
 *
 * <h2>What this interface deliberately does not offer</h2>
 *
 * <p>Alternatives Considered: a richer finder set -- by active status, by disclosure group, by
 * expiration date, by balance -- on the reasoning that a batch module will eventually want to select
 * accounts rather than fetch them one at a time. Rejected on evidence: no migrated program performs
 * any such lookup. All three read by key and only by key, at
 * {@code app/cbl/CBTRN02C.cbl:395}, {@code app/cbl/CBACT04C.cbl:373} and
 * {@code app/cbl/CBTRN01C.cbl:243}, and the migration plan authorises no speculative surface. A
 * signature with no caller cannot be justified against the project's Explainability rule, which
 * makes an undocumented choice a review failure precisely where a reasonable alternative exists;
 * the honest documentation of such a method would have to say that nothing needs it.</p>
 *
 * <p>Assumptions: the two boundary validations belong to
 * {@code com.carddemo.batch.service.PostingValidationService} and not to this interface, and the
 * distinction is parity-critical rather than stylistic. The over-limit test at
 * {@code app/cbl/CBTRN02C.cbl:403} through {@code app/cbl/CBTRN02C.cbl:413} computes a projected
 * balance from the two cycle members and the transaction amount and then compares it against the
 * credit limit with an INCLUSIVE accept at {@code app/cbl/CBTRN02C.cbl:407} -- a projection landing
 * exactly on the limit posts. The expiration test at {@code app/cbl/CBTRN02C.cbl:414} through
 * {@code app/cbl/CBTRN02C.cbl:420} compares the expiry against the first ten characters of the
 * feed record's originating stamp, and it too accepts equality, so a transaction dated exactly on
 * the expiry date posts. Both read members of a row that has already been loaded. Expressing either
 * as a query predicate would move a boundary the committed expectations pin into the data-access
 * layer, where it could no longer be unit-tested against the documented boundary values, and where
 * an inverted comparison would present as a missing row rather than as a wrong decision.</p>
 *
 * <p>Alternatives Considered: native SQL, which is the obvious reach in a batch module because a
 * nightly pass is naturally set-shaped. Rejected on the timing of the failure it admits. The schema
 * check this module runs at start-up compares MAPPING METADATA against the deployed table and never
 * parses the text of a native statement, so a property path naming something the schema does not
 * have is reported before a row is read, while a mistyped physical column inside a native statement
 * stays invisible until that statement executes -- part-way through a nightly chain, with earlier
 * steps already committed. The hazard is concrete rather than hypothetical here: the batch-local
 * mapping and {@code V1__account.sql} do not agree on every spelling a reader might expect, since
 * {@code app/cpy/CVACT01Y.cpy:11} declares the expiry member as {@code ACCT-EXPIRAION-DATE} while
 * the column is {@code expiration_date}. Both members below are derived methods bound to property
 * names declared on {@code Account}, so no physical column name appears anywhere in this file.</p>
 *
 * <p>Assumptions: no arithmetic is performed here and no monetary type other than the entity's own
 * appears. Balances are exact decimals held at scale two; the additive balance adjustment and the
 * interest computation belong to the service and job layers through
 * {@code com.carddemo.common.money.Money}, which owns the rounding contracts. Binary floating point
 * is barred from any member position by the architecture rules the {@code architecture-rules}
 * execution enforces, so it could not be introduced here even inadvertently.</p>
 *
 * <p>Assumptions: no date is read from a clock. The business date is a job parameter --
 * {@code app/jcl/INTCALC.jcl:22} injects it into the accrual step as
 * {@code PARM='2022071800'} -- which is what makes a re-run over identical input produce identical
 * output. Any comparison against a date therefore takes that date as an argument and belongs to the
 * service layer, and no member of this interface accepts or derives one.</p>
 *
 * <h2>The name collision with account-service is deliberate</h2>
 *
 * <p>Assumptions: {@code com.carddemo.account.repository.AccountRepository} also exists. Same simple
 * name, different package, different declaring module, and <b>the duplication must not be resolved
 * by reaching for the other one.</b> It is barred twice over. That interface is typed on
 * {@code com.carddemo.account.domain} entities, so importing it would take a dependency on another
 * bounded context's domain model and fail the layering rule that forbids exactly that; and
 * {@code account-service} is not a dependency of this module at all -- the only intra-repository
 * Maven dependency declared here is {@code common-lib} -- so those types are not on this module's
 * compile classpath to be imported in the first place. The duplication is the deliberate
 * consequence of the local-mapping decision recorded on
 * {@code com.carddemo.batch.domain.Account}, and the two contexts agree through the physical schema
 * rather than through code. It is stated on this interface, and not left to the package charter
 * alone, because this is the file whose name collides and whose reader is therefore the one most
 * likely to try to tidy it away.</p>
 */
// Assumptions: the base type is the full read-and-write repository rather than a narrower marker,
//     and the choice follows the GRANT rather than a wish for symmetry with the siblings. The
//     update surface is genuinely exercised: the posting job mutates the account it read and saves
//     it, and so does the accrual job on each control break, so a read-only base type here would
//     compile and then leave both jobs unable to express the rewrite that
//     app/cbl/CBTRN02C.cbl:554 and app/cbl/CBACT04C.cbl:356 both perform. The charter withholds
//     every write member from DisclosureGroupRepository for the mirror-image reason: this module
//     holds no write privilege on the reference schema, so a write there would be refused by the
//     database rather than caught by a compiler.
// Trade-offs: the inherited insert and delete members come with that base type and no caller in
//     this module uses either, which is the cost accepted for the update. It is a bounded cost
//     rather than a latent hazard, because the grant behind this table carries the read and the
//     update only -- an insert or a delete issued through an inherited member would be refused by
//     the database, so the narrowing is enforced somewhere real even though it is not enforced by
//     this type. No such call exists, and the package charter records that none may be added.
// Alternatives Considered: carrying the Repository stereotype annotation on this declaration.
//     Rejected because Spring Data already registers a proxy for every interface extending its
//     base types, so the annotation adds a second declaration of a role the container has already
//     assigned, and the package charter withholds it from every interface in this package for that
//     reason rather than for this one file's sake.
public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Reads one account master row by its identifier.
     *
     * <p>This is the migrated form of the keyed read that all three batch programs perform:
     * {@code app/cbl/CBTRN02C.cbl:395} in the posting job, {@code app/cbl/CBACT04C.cbl:373} in the
     * interest accrual and {@code app/cbl/CBTRN01C.cbl:243} in the pre-posting pass. Each is a
     * random read against a cluster keyed on the account identifier, and each is preceded by a move
     * of that identifier into the key member -- from the cross-reference at
     * {@code app/cbl/CBTRN02C.cbl:394}, and from the category-balance key at
     * {@code app/cbl/CBACT04C.cbl:202}.</p>
     *
     * <p>The row this returns is managed by the caller's persistence context, which is what lets
     * the posting and accrual jobs adjust it and have the adjustment written by the flush of the
     * transaction they already run inside. That is the mechanism by which the account update stays
     * in the same unit of work as the ledger write beside it.</p>
     *
     * @param accountId the eleven-digit account identifier to read, as a {@code Long}, obtained
     *     from a preceding cross-reference or category-balance lookup and never from a field of the
     *     daily transaction record; must not be {@code null}
     * @return the one {@code Account} carrying that identifier, as an {@code Optional<Account>}, or
     *     an EMPTY optional when no account holds it -- which is a documented business outcome
     *     rather than a fault, and which each caller resolves differently
     * @throws org.springframework.dao.OptimisticLockingFailureException if a version conflict on an
     *     account this same transaction adjusted earlier is surfaced by the automatic flush that
     *     precedes this query; the caller must let it propagate and fail the step rather than
     *     re-reading and re-applying, for the reasons recorded on this interface
     */
    // Assumptions: this method is declared even though the inherited by-identifier read expresses
    //     the same query, and the reason is that its contract needs the three paragraphs above.
    //     Where the key comes from, what an empty optional means to each of the three callers, and
    //     why the returned row is managed are all obligations a caller has to know and none of them
    //     can be attached to an inherited member. The alternative -- relying on the inherited read
    //     and documenting it on the type instead -- was declined because the key-origin rule is the
    //     one a caller is most likely to breach, and a rule stated only on the type is read once
    //     while a rule stated on the method is read at every call site.
    // Trade-offs: the optimistic-lock failure is documented on a READ, which looks misplaced until
    //     the flush ordering is taken into account. The provider flushes pending changes before
    //     executing a query so that the query sees them, so the posting job's second iteration can
    //     surface a conflict raised by the account it adjusted on its first. Documenting the
    //     failure only on the write path would leave that arrival point undocumented, which is the
    //     more misleading of the two options.
    Optional<Account> findByAccountId(Long accountId);

    /**
     * Streams every account master row in ascending identifier order.
     *
     * <p>This serves the operator-invoked export, which walks the account master in key order to
     * emit one export record per account. The ordering is by identifier ascending because that is
     * the order a sequential pass over a cluster keyed on that column yields, so the exported
     * sequence matches the one the reference produces rather than merely being deterministic. The
     * primary key declared by {@code V1__account.sql} is what supports the ordering, and no index
     * is declared here to obtain it.</p>
     *
     * <p>No windowed or partial read is offered alongside this one, because no caller wants a
     * portion of the account master: the export consumes the whole walk, and the three programs
     * that want a single account use the keyed read above. Offering both a full walk and a
     * positional variant would mean publishing an access path that no reference paragraph performs
     * and that nothing in this module would exercise.</p>
     *
     * @return a lazily-evaluated {@code Stream<Account>} over every account in ascending identifier
     *     order; never {@code null}, possibly empty, and the CALLER owns closing it
     * @throws org.springframework.dao.InvalidDataAccessApiUsageException if the stream is opened
     *     without a surrounding transaction to keep the underlying connection open for the duration
     *     of the walk; the caller satisfies this by consuming the stream inside the transaction its
     *     step already runs in
     * @throws org.springframework.dao.OptimisticLockingFailureException if a version conflict on a
     *     row this same transaction adjusted earlier is surfaced by the automatic flush that
     *     precedes the query backing this stream; the ruling recorded on this interface applies
     *     unchanged
     */
    // Trade-offs: a stream rather than a list, matching the sibling feed and ledger interfaces. The
    //     account master is unbounded in principle, so materialising it would hold every row in
    //     the heap at once and make the export's memory profile a function of how long the
    //     institution has been in business. The cost is a resource the caller has to release,
    //     which is why the obligation is stated on the return tag above and why the export opens
    //     it in a try-with-resources block; a stream left unclosed holds a cursor open for the rest
    //     of the transaction.
    // Assumptions: the ordering is declared on the method name rather than left to the query
    //     planner. Without it the emitted sequence would be whichever order the planner happened
    //     to produce -- stable in practice and guaranteed by nothing -- and the export's round-trip
    //     comparison would then depend on a property no contract states.
    Stream<Account> findAllByOrderByAccountIdAsc();
}
