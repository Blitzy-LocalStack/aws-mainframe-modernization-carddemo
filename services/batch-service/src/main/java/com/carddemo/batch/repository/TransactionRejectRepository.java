package com.carddemo.batch.repository;

import com.carddemo.batch.domain.TransactionReject;
import org.springframework.data.repository.Repository;

/**
 * Appends one rejected daily transaction at a time to {@code ledger.transaction_rejects}, the
 * posting reject stream.
 *
 * <p>One row behind this interface is one daily-transaction record that failed posting validation,
 * carried together with the reason it failed. The migrated posting job inserts that row on exactly
 * the branch {@code app/cbl/CBTRN02C.cbl:213-215} takes when the validation reason is not zero,
 * and this interface is the only way this module reaches the table. The table itself belongs to the
 * ledger bounded context and is owned by {@code transaction-service}; this module writes it under
 * the narrowly-scoped cross-schema grant on {@code ledger} that the migration plan records at its
 * section 0.4.1.3 as the one documented exception to database-per-service purity.</p>
 *
 * <p>This interface declares a single method, so nearly everything worth knowing about the stream
 * it writes is recorded here rather than beside a signature. Two of those items are behaviours of
 * the reference program that a reader can recover neither from this file nor from the schema, and
 * they are set out in their own section below because a translation that misses either one produces
 * output a byte-exact comparison rejects.</p>
 *
 * <h2>The four reason codes this module can persist</h2>
 *
 * <p>Each entry gives the code, the description stored beside it, and the line of
 * {@code app/cbl/CBTRN02C.cbl} that assigns the pair. The descriptions are carried character for
 * character under the migration plan's transformation rule T8, which makes user-visible strings
 * verbatim. That is not a stylistic preference here: an operator reads these texts out of the
 * reject stream, and the committed expectation files under {@code tests/golden/posting} compare
 * characters 355 to 430 of every record against them, so re-casing a word or rewording a phrase
 * changes compared bytes rather than changing prose. Each text is declared once as a constant on
 * {@link TransactionReject} and is deliberately not restated as a literal in this file.</p>
 *
 * <ul>
 *   <li><b>100</b> -- {@code INVALID CARD NUMBER FOUND}. The card number the feed record carries is
 *       absent from the cross-reference. Assigned at {@code app/cbl/CBTRN02C.cbl:385} with its text
 *       at {@code :386}; declared as {@link TransactionReject#REASON_CODE_INVALID_CARD_NUMBER}.</li>
 *   <li><b>101</b> -- {@code ACCOUNT RECORD NOT FOUND}. The account the cross-reference names does
 *       not exist. Assigned at {@code app/cbl/CBTRN02C.cbl:397} with its text at {@code :398};
 *       declared as {@link TransactionReject#REASON_CODE_ACCOUNT_NOT_FOUND}.</li>
 *   <li><b>102</b> -- {@code OVERLIMIT TRANSACTION}. The projected cycle balance exceeds the credit
 *       limit. Assigned at {@code app/cbl/CBTRN02C.cbl:410} with its text at {@code :411};
 *       declared as {@link TransactionReject#REASON_CODE_OVERLIMIT}.</li>
 *   <li><b>103</b> -- {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}. The transaction date falls
 *       after the account expiration date. Assigned at {@code app/cbl/CBTRN02C.cbl:417} with its
 *       text at {@code :418}; declared as
 *       {@link TransactionReject#REASON_CODE_AFTER_EXPIRATION}.</li>
 * </ul>
 *
 * <p>Alternatives Considered: modelling the reason as a closed Java enum on the persistence surface,
 * so that this interface accepted a reason type rather than a number. Rejected because the reference
 * program leaves the reason space open by construction. Its validation chain ends at
 * {@code app/cbl/CBTRN02C.cbl:377} with the standing invitation
 * {@code * ADD MORE VALIDATIONS HERE} immediately before the paragraph exit, and the source field is
 * {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at {@code :181} -- a four-digit numeric domain rather
 * than an enumeration of four members. The owning migration encodes the same openness independently:
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} constrains the
 * column with {@code CHECK (reason_code BETWEEN 0 AND 9999)}, which admits the whole picture rather
 * than only the reachable four. So the stored column stays numeric, {@link TransactionReject} maps
 * it as a small integer, and this register lives in documentation where adding a fifth reason costs a
 * list entry instead of a schema change. A separate enum does exist in
 * {@code com.carddemo.batch.dto} for the validation service to name its outcomes with; that is a
 * transfer concern on the deciding side and is not the shape anything is stored or queried as.</p>
 *
 * <h2>Two reference behaviours a reader cannot recover unaided</h2>
 *
 * <p>Assumptions: <b>reason 103 overwrites reason 102 when both conditions hold.</b> The two tests
 * sit inside the same successful-account-read branch and are sequential and unguarded rather than
 * alternatives of one another. {@code app/cbl/CBTRN02C.cbl:407-413} tests the credit limit and is
 * closed by its own terminator at {@code :413}; {@code :414-420} then opens the expiration test at
 * the same nesting level, and nothing between the two terminates the paragraph or guards the second
 * on the outcome of the first. Each test assigns its reject inside its own inner alternative branch,
 * and what is absent is any guard BETWEEN them. A record that is both over limit and past expiration
 * therefore has 102 assigned at {@code :410}, and then both the code and the description replaced at
 * {@code :417-419}. The last writer wins, so the record reaches the stream as 103 and the over-limit
 * reason is not recorded anywhere. The reference program behaves this way; the migrated job
 * implements the same last-writer-wins evaluation, and the divergence register in
 * {@code docs/architecture/cobol-to-service-traceability.md} is where any difference between the two
 * is recorded. The practical consequence for an author is narrow and worth stating plainly: a
 * translation shaped as a chain of mutually exclusive alternatives in declaration order stores 102
 * on exactly the inputs where both conditions hold, and agrees with the reference program everywhere
 * else -- which is why the case needs a test of its own. None of the committed fixture directories
 * under {@code tests/golden/posting} exercises it, because each of the four reject fixtures
 * exercises one reason in isolation.</p>
 *
 * <p>Assumptions: <b>reason 109 exists in the reference program and can never reach this stream.</b>
 * It is assigned at {@code app/cbl/CBTRN02C.cbl:556}, on the unsuccessful-key branch of the account
 * rewrite, inside a paragraph that opens at {@code :545} and is performed only from {@code :441} --
 * which is itself reached only from {@code :212}, the arm taken when validation has already
 * SUCCEEDED. The paragraph that writes this stream is performed only from {@code :215}, the opposite
 * arm of that same decision at {@code :211-216}, and {@code :208} resets the reason before the next
 * record is examined. A 109 assigned during posting is consequently never observed by the reject
 * writer, and the committed expectation files agree: no reject expectation under
 * {@code tests/golden/posting} carries it. This is recorded rather than left to be rediscovered so
 * that nobody adds a fifth entry to the register above and nobody writes a test expecting the value.
 * Note also why a reader scanning the source for distinct texts would miss it entirely: the
 * description at {@code :557} is byte-identical to reason 101's at {@code :398}, so the reference
 * program assigns two different codes with one description and the description alone does not
 * identify the code.</p>
 *
 * <h2>The 430-byte contract, and why no generation is stored</h2>
 *
 * <p>Assumptions: a reject record is 430 characters, and that width is corroborated three
 * independent ways, which is what allows it to be stated as fact rather than as a reading. The file
 * description at {@code app/cbl/CBTRN02C.cbl:82-84} composes the record as
 * {@code FD-REJECT-RECORD PIC X(350)} followed by {@code FD-VALIDATION-TRAILER PIC X(80)}. The
 * working-storage trailer at {@code :180-182} then declares those same eighty characters at finer
 * granularity as {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} plus
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}, and four plus seventy-six is eighty, so the two
 * views agree on width and differ only in structure. The dataset allocation at
 * {@code app/jcl/POSTTRAN.jcl:36} states the total directly as
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}. Three agreeing sources matter because a reject stream
 * written at the wrong width is a file that still opens and still reads. The three columns
 * {@link TransactionReject} maps are that same decomposition, and rendering the row back to 430
 * characters -- the reason zero-padded to four, the description blank-padded to seventy-six -- is
 * the emitter's responsibility and not this interface's.</p>
 *
 * <p>Assumptions: <b>the table carries no generation column and no run identifier, and the absence
 * is deliberate.</b> The reference output is a generation dataset: {@code app/jcl/POSTTRAN.jcl:38}
 * allocates it as the relative generation {@code AWS.M2.CARDDEMO.DALYREJS(+1)}, and
 * {@code app/jcl/DALYREJS.jcl:24-28} defines the base with {@code LIMIT(5)} and {@code SCRATCH}, so
 * five generations are retained and the sixth displaces the oldest. The migration plan's
 * transformation rule T6 maps a relative generation onto a versioned object-storage generation
 * rather than onto a column, so per-run scope is a property of the artifact the job writes and of
 * the {@code batch.batch_run} step ledger, never of a row here. A column added on this side that the
 * owning migration does not declare would fail the start-up assertion rather than work.</p>
 *
 * <h2>Why the surface is append-only</h2>
 *
 * <p>Alternatives Considered: {@code JpaRepository}, which is what the sibling
 * {@code BatchRunRepository} extends and what the owning ledger context's interface of this same
 * simple name extends. Rejected here, because it inherits {@code delete}, {@code deleteAll},
 * {@code deleteById} and the whole update surface, and the reference program has no rewrite, no
 * removal and no read path over this dataset at all. The evidence is the file declaration itself:
 * {@code app/cbl/CBTRN02C.cbl:46-49} selects the stream as {@code ORGANIZATION IS SEQUENTIAL} with
 * {@code ACCESS MODE IS SEQUENTIAL} and no record key, {@code :451} is the only statement in the
 * program that writes it, and no rewrite or removal verb is issued against it anywhere. Inheriting
 * members with no counterpart in the behaviour being migrated would offer capabilities that have no
 * parity reference -- there is no expectation file that says what removing a reject should do,
 * because the reference program cannot do it. Narrowing the base to the marker interface and
 * declaring the one member this module needs makes the whole reachable surface of the type legible
 * from this file. The owning context reaching the same table through the wider base is not an
 * inconsistency to reconcile: its callers replay a captured stream and genuinely need the read and
 * removal members, so as with the sibling interfaces here, the base type follows the grant and the
 * caller rather than a wish for symmetry between two files.</p>
 *
 * <p>Assumptions: the write is authorised by the narrowly-scoped cross-schema grant on
 * {@code ledger} described above, and
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql} is normative
 * for everything about the table's shape -- its four columns, its
 * {@code CHECK (reason_code BETWEEN 0 AND 9999)} constraint, and explicitly its
 * {@code CONSTRAINT pk_transaction_rejects PRIMARY KEY (reject_seq)}. This interface accordingly
 * declares no table, no column, no primary key, no index and no constraint of its own. A query here
 * that names something that migration does not declare is this file's defect and never that
 * file's.</p>
 *
 * <h2>Why the reject tally is not a query on this interface</h2>
 *
 * <p>Trade-offs: a count of the rejects a run produced is genuinely needed --
 * {@code app/cbl/CBTRN02C.cbl:229-230} turns a non-zero {@code WS-REJECT-COUNT} into the graded
 * return code that the container exit status carries -- and it is deliberately NOT offered here. A
 * derived counting method would be the obvious thing to add and would be actively misleading,
 * because {@link TransactionReject} carries no run discriminator: no run identifier, no timestamp
 * and no generation, for the reason recorded above. A table-wide count would therefore aggregate
 * every reject ever loaded into the schema and would answer a different question than the caller
 * asked, in a way that reads as correct at the call site and is wrong only in production once the
 * table holds more than one run. The tally is instead the job's own step-execution counter, which is
 * the framework's direct equivalent of the working-storage counter the reference program increments
 * at {@code :214} in the same breath as it writes the row at {@code :215}; that counter is scoped to
 * one execution by construction. The accepted cost is that a caller wanting the number cannot get it
 * from this interface and must hold it where it is being produced, which is the one place it is
 * unambiguous.</p>
 *
 * <p>Alternatives Considered: a native statement, which is the obvious reach in a batch module
 * because a nightly pass is naturally set-shaped and one statement of SQL reads more directly than a
 * derived method. Rejected on the timing of the failure it admits, not on style. A native statement
 * binds to physical column names, and this module runs the persistence provider with schema handling
 * set to validate -- a pass that compares MAPPING METADATA against the deployed table and never
 * parses the text of a query. A property path resolving to a column the schema lacks is therefore
 * reported at start-up, before a row is written, whereas a mistyped physical column inside a native
 * statement stays invisible until that statement executes: part-way through a nightly chain, with
 * earlier steps already committed. The hazard is concrete rather than hypothetical, and this table
 * alone demonstrates it: not one of its four physical column names matches the property name it is
 * reached by, since {@code reject_seq}, {@code raw_record}, {@code reason_code} and
 * {@code reason_desc} are declared from {@code V1__ledger.sql:670} onward while
 * {@link TransactionReject} maps them as {@code rejectSeq}, {@code rawRecord}, {@code reasonCode}
 * and {@code reasonDesc}. Every one of those four is an opportunity to write a name that compiles
 * and then fails at execution. So no {@code @Query} appears in this file, {@code nativeQuery} is
 * never enabled anywhere in it, and no physical column name appears as raw text -- the single member
 * below is bound to the property names {@link TransactionReject} declares.</p>
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
 * recorded in the divergence register. Nothing here is described as amending or improving upon a
 * reference behaviour, because that behaviour is the specification this work is compared against.</p>
 */
public interface TransactionRejectRepository extends Repository<TransactionReject, Long> {

    /**
     * Appends one rejected daily transaction, with the reason it was rejected, to the reject stream.
     *
     * <p>This is the relational equivalent of the single write the reference program issues at
     * {@code app/cbl/CBTRN02C.cbl:451}, and it is the only operation this module performs against
     * the table. Each call inserts a new row; no call can replace or remove one, because the
     * inherited surface that would allow it is not present on this interface for the reasons
     * recorded on the type. The insert takes part in whatever transaction the calling step has
     * open, so a step that fails before committing leaves no partial reject stream behind.</p>
     *
     * @param reject the completed reject row to append, a {@link TransactionReject} whose three
     *     contract members are already populated -- the 350-character record image, the four-digit
     *     reason code, and that reason's verbatim description; its {@code rejectSeq} identifier must
     *     be absent, since the database assigns it, and the argument must not be {@code null}
     * @return the same {@link TransactionReject} as a managed instance, now carrying the
     *     database-assigned {@code rejectSeq} identifier that was absent on the argument; callers
     *     that need that identifier must read it from THIS returned instance rather than from the
     *     one they passed in
     * @throws org.springframework.dao.DataIntegrityViolationException if the row violates the
     *     column contract the owning migration declares -- a reason code outside the four-digit
     *     domain its check constraint admits, or a null in any of the three columns it declares
     *     {@code NOT NULL}
     * @throws IllegalArgumentException if {@code reject} is {@code null}
     */
    // Alternatives Considered: keying the row on the rejected record itself, or on that record
    //     together with its reason, so that a reject carried a natural key instead of the
    //     surrogate sequence the entity declares. Rejected for two independent reasons, either of
    //     which is sufficient. The record image is not unique: the same daily transaction can
    //     legitimately be re-presented in a later run and be rejected again, and
    //     app/jcl/DALYREJS.jcl:24-28 retains five generations of exactly that, so collapsing the
    //     two occurrences into one row would lose a reject the reference program emitted and would
    //     undercount the tally that :229-230 turns into the run's return code. And a
    //     350-character column is unsuitable as a key on its own terms, being far wider than any
    //     index this table needs. Hence the entity's generated identity key, which distinguishes
    //     the two occurrences while leaving every payload character free to repeat.
    // Assumptions: the record image reaches this method as the 350 bytes of the rejected daily
    //     transaction, held VERBATIM and UNDECOMPOSED, exactly as app/cbl/CBTRN02C.cbl:447 moves
    //     DALYTRAN-RECORD wholesale into the reject area in a single statement. The stream's whole
    //     value as an operational artifact is that it is byte-faithful -- it is what an operator
    //     inspects and what the committed expectation files compare characters 1 to 350 of -- so no
    //     caller may parse it into fields on the way in and no member of this interface offers a
    //     decomposed form. The mapping honours this by binding the image to a single character
    //     column of the declared width; the layout it holds is app/cpy/CVTRA06Y.cpy, whose
    //     thirteen fields plus trailing filler sum to that same 350.
    // Assumptions: declaring this member explicitly is what makes it reachable at all, because the
    //     base interface above is a marker that declares nothing itself. The store matches the
    //     declaration against its own insert implementation by member name and argument type, so
    //     the argument has to be the domain type this interface is typed over rather than a
    //     supertype or a projection of it.
    // Alternatives Considered: the type-variable form the wider base declares, which would bound a
    //     variable by the domain type so that a subtype could be inserted and returned as itself.
    //     Declined because the mapping declares no inheritance strategy and has no subtype, so the
    //     variable would add a type parameter to document and to read for a polymorphism this table
    //     does not have; the concrete form states the one shape that is actually inserted.
    // Assumptions: this member is not a query-derived name, so it carries no property fragment that
    //     could fail to resolve -- which matters because such a fragment is reported when the
    //     application context starts and not when this module compiles. That is also why no finder
    //     is declared beside it: the package charter records that none would have a caller, since
    //     the reject stream is written and then compared against a committed expectation file
    //     rather than re-read by the run that produced it.
    TransactionReject save(TransactionReject reject);
}
