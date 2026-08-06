package com.carddemo.transaction.repository;

import com.carddemo.transaction.domain.TransactionCategoryBalance;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The sole data-access port onto {@code ledger.transaction_category_balances}, the per-account,
 * per-type, per-category running balance of the LEDGER bounded context.
 *
 * <p>Every relational equivalent of a file operation the reference programs issue against their
 * {@code TCATBALF} dataset is either declared in this interface or inherited into it, and none is
 * expressed anywhere else in this module. The package charter beside this file sets that boundary.
 * This interface therefore adds only the rulings specific to this one record: the general keyset
 * argument, the mapping of each file verb under AAP transformation rule T5, and the
 * transaction-list cursor contract all belong to the charter and are cited here rather than
 * restated.
 *
 * <p>Assumptions: an annotation named in this block is written as a bare type name, without the
 * at-sigil, because every one of them is a construct this interface does NOT apply. The interface
 * carries no annotation whatsoever, so each is named only as an alternative that was weighed and
 * declined; spelling one as though it were applied would misdescribe the file to a reader skimming
 * for what is actually in force.
 *
 * <p>Assumptions: no method below carries an exception at-clause, and the omission is uniform and
 * deliberate rather than an oversight. The project's Explainability rule attaches that element
 * where an exception is genuinely raisable, and the house convention reproduced at
 * {@code tests/README.md} lines 545 and 546 lists it beside purpose, parameters and returns on the
 * same condition. Every member here is a query. A query declares no checked exception, and the
 * unchecked data-access failures the framework translates -- a lost connection, a statement the
 * caller cannot influence -- are not conditions a caller handles per call site; they surface
 * through this module's shared web error contract. A speculative at-clause would add an
 * unverifiable claim rather than a fact.
 *
 * <h2>The identifier is a real composite, taken from the reference file's own record key</h2>
 *
 * <p>Assumptions: the three-part identifier is read off the reference declarations rather than
 * inferred from the column list. {@code app/cpy/CVTRA01Y.cpy} line 2 states the record length as
 * {@code RECLN = 50}; line 4 opens {@code TRAN-CAT-BAL-RECORD}; line 5 declares
 * {@code TRAN-CAT-KEY} as a group item carrying no picture of its own, spanning the three
 * level-10 items that follow it -- {@code TRANCAT-ACCT-ID PIC 9(11)} at line 6,
 * {@code TRANCAT-TYPE-CD PIC X(02)} at line 7 and {@code TRANCAT-CD PIC 9(04)} at line 8. Those
 * widths sum to 17 bytes; the eleven-byte balance at line 9 and the twenty-two-byte padding at
 * line 10 close the record at the declared 50.
 *
 * <p>Assumptions: that the group really is the access key is settled outside the copybook, which
 * matters because a leading cluster of fields and a record key look alike in a layout.
 * {@code app/cbl/CBACT04C.cbl} selects the dataset across lines 28 to 32, declaring it
 * {@code ORGANIZATION IS INDEXED} at line 29 and {@code RECORD KEY IS FD-TRAN-CAT-KEY} at line 31,
 * and its file description puts {@code 05 FD-TRAN-CAT-KEY.} first in the record at lines 61 to 63.
 * {@code app/cbl/CBTRN02C.cbl} states the same key independently at lines 57 to 61, naming it at
 * line 60. The identifier type parameter of this interface is therefore the migrated form of that
 * group, and no surrogate and no fourth component is admissible.
 *
 * <p>Alternatives Considered: the identifier is the entity's nested embedded type, and an
 * identifier-class mapping -- {@code IdClass}, the genuine alternative -- is declined. That form
 * requires the three key fields to be declared TWICE, once on the entity and once on the
 * identifier class, so the 17-byte layout of lines 6 to 8 would be written out in two places that
 * nothing keeps in step. The house precedent rules exactly that out: {@code tests/README.md} lines
 * 540 to 542 record that the reference unit tests resolve every record layout through the single
 * compiler copybook path, {@code cobc -I app/cpy}, and say never to duplicate a layout. The
 * embedded form keeps the group's three members declared once, and this interface consumes that
 * single declaration as its identifier rather than restating any part of it.
 *
 * <p>Assumptions: the component ORDER is load-bearing and is asserted identically by three
 * independent artifacts, so the ordering this interface requests is a transcription and not a
 * preference. The copybook order is account, then type code, then category code, at lines 6, 7 and
 * 8. The migration declares {@code pk_transaction_category_balances} over
 * {@code (account_id, type_cd, category_cd)} at lines 594 and 595 of
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}. The
 * reference report job sorts the same dataset on
 * {@code (TRANCAT-ACCT-ID,A,TRANCAT-TYPE-CD,A,TRANCAT-CD,A)} at {@code app/jcl/PRTCATBL.jcl} line
 * 52. All three agree, so no discrepancy between the copybook and the migration has to be reported
 * here.
 *
 * <h2>Two reference access modes become exactly two access shapes</h2>
 *
 * <p>Assumptions: the two programs that reach this dataset open it under DIFFERENT access modes,
 * and that difference -- not a judgement about which queries might be useful -- is what fixes the
 * member surface of this interface at one inherited keyed read plus one declared ordered read.
 * {@code app/cbl/CBTRN02C.cbl} line 59 declares {@code ACCESS MODE IS RANDOM} and reads one row by
 * its whole key. {@code app/cbl/CBACT04C.cbl} line 30 declares
 * {@code ACCESS MODE IS SEQUENTIAL} and walks the dataset in key order. Each mode has one
 * relational shape here and neither has two.
 *
 * <p>Alternatives Considered: the keyed read maps onto the INHERITED {@code findById}, which is
 * therefore not redeclared, and its {@code Optional} result is the create-versus-update
 * discriminator. An existence probe followed by a read -- {@code existsById} and then
 * {@code findById} -- is the alternative and is rejected on the shape of the reference branch,
 * which is a SINGLE keyed read whose own outcome decides the path. In
 * {@code app/cbl/CBTRN02C.cbl} the paragraph {@code 2700-UPDATE-TCATBAL}, spanning lines 467 to
 * 501, builds the whole key at lines 469 to 471, clears its flag at line 473, issues one
 * {@code READ TCATBAL-FILE INTO TRAN-CAT-BAL-RECORD} at line 474, and sets the flag on the
 * {@code INVALID KEY} branch at line 478. Line 481 then accepts a file status of {@code '00'}
 * <em>or</em> {@code '23'}, so an absent row is a normal outcome of that one read and not an error.
 * A probe-then-read pair would turn one round trip into two and would open a window between the
 * two statements in which another writer can insert the very row the probe reported absent, which
 * the single read has no equivalent of.
 *
 * <p>Assumptions: the account-scoped ordered read declared below maps onto the sequential mode, and
 * it is the ONLY member this interface declares. The keyed read, the insert and the update all
 * arrive inherited.
 *
 * <h2>The create-versus-update branch stays observable, so no merge is declared</h2>
 *
 * <p>Alternatives Considered: the branch between creating a row and updating one is expressed
 * through the schema and the calling service, and NO conflict-resolving merge is declared on this
 * interface -- neither a single upsert statement nor a modifying bulk statement standing in for
 * one. The reference behaviour is two separately named paragraphs reached from one predicate, and
 * both are separately asserted by the parity oracle, so collapsing them into one opaque statement
 * would erase a distinction the reference system keeps and the oracle checks.
 *
 * <p>Assumptions: the paragraph bounds below were read from the source rather than inherited, and
 * they are stated with their paragraph names so the citation checks itself. In
 * {@code app/cbl/CBTRN02C.cbl} the dispatcher {@code 2700-UPDATE-TCATBAL} occupies lines 467 to
 * 501 and branches at lines 495 to 499; the create path
 * {@code 2700-A-CREATE-TCATBAL-REC} occupies lines 503 to 524, issuing {@code INITIALIZE} at line
 * 504, moving the three key components at lines 505 to 507, adding the amount at line 508 and
 * {@code WRITE} at line 510; the update path {@code 2700-B-UPDATE-TCATBAL-REC} occupies lines 526
 * to 542, adding the amount at line 527 and {@code REWRITE} at line 528. The ensemble therefore
 * runs from line 467 to line 542 as three paragraphs, not one.
 *
 * <p>Assumptions: the inherited {@code save} covers both paths, which is precisely why no third
 * member is needed to express them. An empty {@code Optional} from {@code findById} leads to a
 * save that inserts, mirroring line 510; a present one leads to a save that updates, mirroring line
 * 528. The migration reaches the same conclusion from the other side and records it at its own
 * lines 598 to 611: the composite natural key is the sole constraint, it carries no default and no
 * generated value, and nothing conflict-shaped is provided, expressly so that a caller can still
 * tell an insert from an update.
 *
 * <p>Assumptions: which path runs, and the arithmetic that decides the value written, belong to the
 * calling service and not here. Both reference paths add an amount to a base and differ only in
 * whether that base is zero or the value just read, and the entity records the same division of
 * responsibility on its own mutator. No balance-incrementing statement is declared on this
 * interface for that reason.
 *
 * <h2>Ordering replaces control-break state; a whole-dataset cursor is not declared</h2>
 *
 * <p>Refactoring Rationale: the account-scoped ordered read replaces a sequential scan whose
 * grouping was implicit in the physical order of a dataset. What was wrong with that arrangement
 * is specific: in {@code app/cbl/CBACT04C.cbl} the walk opens at line 188 and closes at line 222,
 * fetching one row per iteration at line 190, and the grouping exists only as working-storage
 * state compared row by row -- {@code WS-LAST-ACCT-NUM} tested at line 194 and reassigned at line
 * 201, {@code WS-FIRST-TIME} at lines 195 and 198, and the running total cleared at line 200. A
 * reader cannot see the grouping in any one statement, and correctness depends on rows arriving
 * account-major, which nothing in the program states and only the access path guarantees. An
 * ordered query keyed on the account states the grouping in the signature, so the boundary is an
 * artifact of the query rather than of the order in which rows happen to arrive.
 *
 * <p>Alternatives Considered: a second member offering a continuation cursor over the WHOLE
 * dataset across all accounts is deliberately NOT declared, and the omission is recorded because
 * the reference walk really does cross every account and its absence could otherwise read as an
 * oversight. Four grounds decide it. The whole-dataset walk belongs to the batch deployable, which
 * reaches these rows through its own domain types under a narrowly scoped cross-schema grant, so
 * its scan is not a member of this interface. The reference consumer is already account-major
 * anyway: on each break it fixes the account at line 201 and then fetches that account's own
 * record and cross-reference row at lines 202 to 205, so the per-account read below plus the
 * caller's iteration over accounts reproduces the grouping without inventing a three-part cursor.
 * A continuation over the full composite would need a row-value comparison, for which the query
 * language has no constructor, so it would have to be written as vendor SQL in an interface whose
 * landed sibling keeps every predicate derived. And the charter closes this package's inventory
 * and declines to author members that no job or service calls yet.
 *
 * <p>Alternatives Considered: positioning any read here by counting rows from the start of the
 * ordered set is rejected on a defect rather than a preference, and this interface names that
 * mechanism once, here, purely to record the rejection. Counting from the start means an insert
 * landing before the position changes how many rows precede it, so a request positioned that way
 * skips rows it never returned and repeats rows it already returned. The concurrency that makes
 * this real is attested by the reference system, and the charter cites it: {@code COBIL00C} lines
 * 212 to 217 seek from high values, read backwards, end the browse, copy the key and increment it,
 * with nothing holding a lock across those six lines. Comparing keys instead preserves a returned
 * boundary no matter what is inserted around it. The framework shapes that express the rejected
 * mechanism -- the paging request abstraction and its slice and page result types -- appear nowhere
 * in this file, as parameters or as return types.
 *
 * <p>Assumptions: no page envelope is involved either, because there is no online browse screen
 * over this record at all. The shared {@code com.carddemo.common.web.PageResponse} is the envelope
 * for the transaction list and is neither returned nor imported here; were a category-balance
 * screen ever to need one, it would come from that one shared type and would not be re-declared.
 * The charter's honest tally for this bounded context is ONE true forward-and-backward browse,
 * over the transaction list, plus TWO maximum-key identifier derivations, with
 * {@code app/cbl/COBIL00C.cbl} a counter-example rather than a third browse. None of the three is
 * a category-balance operation, and no maximum-identifier member belongs in this file: allocating
 * an identifier is a transaction-ledger concern, and this record's key is supplied by its caller in
 * full.
 *
 * <h2>Money stays exact, and the physical contract stays with the migration</h2>
 *
 * <p>Assumptions: the balance is an exact scaled decimal at two places and never an approximate
 * type. {@code app/cpy/CVTRA01Y.cpy} line 9 declares {@code TRAN-CAT-BAL PIC S9(09)V99}, which the
 * migration maps to {@code balance NUMERIC(11,2)} at its line 580 -- the picture's nine integral
 * and two fractional digits, and not one digit more. The entity holds it as a decimal whose scale
 * the shared {@code com.carddemo.common.money.Money} contract pins, and this interface neither
 * widens nor narrows that: it returns entities. An approximate binary type cannot
 * represent every value the column admits, so a value routed through one would come back differing
 * in cents from the value stored; the prohibition on such types in the money path is asserted
 * mechanically by the shared architecture rules rather than left to review.
 *
 * <p>Assumptions: the twenty-two-byte padding at {@code app/cpy/CVTRA01Y.cpy} line 10 has no
 * counterpart here, and it should not be reintroduced. It pads the four data items out to the
 * declared 50 bytes, the entity records that it is dropped and why, and the migration declares four
 * columns accordingly. Rebuilding a declared-length image is the work of the shared codec package,
 * which owns record representation for the whole migration; a repository interface returns rows.
 *
 * <p>Assumptions: {@code V1__ledger.sql} is the single normative physical contract for this table,
 * and the provider consumes that contract rather than generating it -- this module sets
 * {@code spring.jpa.hibernate.ddl-auto} to {@code none} at line 473 of its
 * {@code application.yml}. Two consequences bind this file. No index annotation and no other
 * shaping metadata belongs here, because generation is off and such metadata would be inert from
 * the moment it was written while remaining free to drift from the migration that actually creates
 * the objects; section 4 of that migration in any case declares no index on this table, the
 * composite key being its only constraint. And the ordering requested below is satisfied by that
 * key rather than by anything this interface declares, since the key's leading column is the
 * account and its next two are the ordering columns.
 *
 * <h2>Boundaries this interface deliberately does not own</h2>
 *
 * <p>Assumptions: no transaction-boundary annotation appears on this interface, and none should be
 * added -- including a read-only one, which would silently fragment a caller's unit of work into a
 * separate one per query. AAP transformation rule T5 maps a reference syncpoint to a transaction
 * boundary and a syncpoint rollback to exception propagation, but the boundary belongs to the
 * service or job layer because the reference commit is issued by the PROGRAM and spans more record
 * types than any one repository owns. The measurement is exact: in {@code app/cbl/CBTRN02C.cbl}
 * the paragraph {@code 2000-POST-TRANSACTION} runs from line 424 to line 444 and performs three
 * updates in sequence -- the category balance at line 440, the account record at line 441 and the
 * posted transaction at line 442. Two of those three records are not this interface's, so a
 * boundary declared here could only ever be the wrong size.
 *
 * <p>Trade-offs: the batch deployable writes the {@code ledger} objects it needs under a narrowly
 * scoped cross-schema grant, which gives up strict schema-per-deployable isolation for that one
 * unit of work in exchange for keeping the three writes above a single atomic commit. The
 * alternative was to break the unit into committed steps with compensating reversals; it is
 * rejected because it would make a posted transaction with an unapplied balance, or an applied
 * balance with no posted transaction, observable between steps. No such intermediate state exists
 * in the paragraph cited above, so the parity oracle would correctly report the design as a
 * behavioural divergence. Neither that pattern nor a distributed two-phase commit is introduced
 * for these tables, and no code dependency between the two deployables is created.
 *
 * <p>Trade-offs: no version attribute and no optimistic locking guard this record, so two callers
 * that read one balance and then write it can lose one of the two updates, and a caller needing
 * that guarantee must take it at the boundary it owns. The compromise is accepted on two grounds.
 * No reference program compares a pre-edit image of a category-balance row: the update path reads
 * at line 474 and rewrites at line 528 inside one unit of work, with no snapshot and no
 * data-changed flag anywhere in it, and the reference system's genuine before-image pattern lives
 * in the account-update and card-update programs whose records belong to other bounded contexts.
 * A second check settles it mechanically: with generation off, a version attribute would map to a
 * column {@code V1__ledger.sql} does not create, so it would fail when a query ran rather than
 * degrade to unversioned behaviour. It follows that no HTTP conflict response originates from this
 * interface; the charter's note on surfacing a stale-state conflict as HTTP 409 describes the
 * records that do carry a version attribute.
 *
 * <p>Alternatives Considered: no stereotype annotation is applied to this interface. Declaring one
 * is the alternative and is redundant here, because the repository infrastructure already creates a
 * proxy for every interface extending the framework's repository types, and the module's
 * configuration enables that scanning. An annotation would add a second, weaker reason for the bean
 * to exist and would suggest to a reader that its absence elsewhere in this package is meaningful.
 * The landed sibling in this package omits it on the same ground.
 */
public interface TransactionCategoryBalanceRepository
        extends JpaRepository<TransactionCategoryBalance,
                TransactionCategoryBalance.TransactionCategoryBalanceId> {

    /**
     * Reads every category balance one account holds, ordered by the remaining key components.
     *
     * <p>This is the relational form of the grouped run of rows that
     * {@code app/cbl/CBACT04C.cbl} consumes between two control breaks. That program opens its walk
     * at line 188, fetches one row per iteration at line 190 through
     * {@code 1000-TCATBALF-GET-NEXT}, whose body begins at line 325, and detects a change of
     * account at line 194 with {@code IF TRANCAT-ACCT-ID NOT= WS-LAST-ACCT-NUM}, closing the
     * break block at line 206 and the walk at line 222. The rows lying between two such detections
     * are exactly one account's group, and that group is what this method returns in one call.
     *
     * @param accountId the account whose balances are wanted, of type {@code Long}; this is the
     *     LEADING component of the composite key, {@code TRANCAT-ACCT-ID PIC 9(11)} at
     *     {@code app/cpy/CVTRA01Y.cpy} line 6, carried as {@code account_id BIGINT NOT NULL} at
     *     line 557 of the ledger migration, and it is the same value the reference dispatcher moves
     *     into the key from the cross-reference row at {@code app/cbl/CBTRN02C.cbl} line 469 rather
     *     than from the incoming record; must not be {@code null}
     * @return that account's rows as a {@code List<TransactionCategoryBalance>}, ordered by type
     *     code ascending and then by category code ascending -- the second and third key components
     *     in the declared order established above, so the sequence matches the order the reference
     *     walk delivers within one account -- and an empty list when the account holds no category
     *     balance, which is a normal outcome rather than an error for the reason recorded on the
     *     file status accepted at line 481 of that program
     */
    // WHY : Refactoring Rationale: the ordering is written into the method name rather than left to
    //       the reader or to the access path, because in the reference program it was stated
    //       nowhere. Rows arrived account-major, and by type and category within an account, purely
    //       because the walk at line 188 of app/cbl/CBACT04C.cbl read an indexed dataset opened
    //       ACCESS MODE IS SEQUENTIAL at line 30 of that program. Naming both ordering components
    //       makes the sequence a property of this query, so a caller reproducing the grouped run
    //       does not depend on a storage engine returning rows in key order when nothing obliges it
    //       to. Were the ordering omitted, the grouping would still be right and the sequence
    //       within each group would be arbitrary, which is the half a control break relies on.
    // WHY : Assumptions: the two ordering components are named in the key's declared order, type
    //       code before category code, which is the order asserted alike by lines 7 and 8 of
    //       app/cpy/CVTRA01Y.cpy, by the primary key at lines 594 and 595 of the ledger migration
    //       and by the reference sort at line 52 of app/jcl/PRTCATBL.jcl. Reversing the two would
    //       leave every row present and every citation above wrong.
    // WHY : Alternatives Considered: no row cap is taken, so the whole of one account's group is
    //       returned in one call. Capping the rows was the alternative and is declined because the
    //       reference program consumes a complete group between two control breaks and never a part
    //       of one, so a cap would introduce a boundary the behaviour being reproduced does not
    //       have and would leave a caller to discover the remainder through a cursor this interface
    //       deliberately does not declare. The group is bounded by the count of type and category
    //       combinations an account carries, not by the size of the table.
    List<TransactionCategoryBalance> findByIdAccountIdOrderByIdTypeCdAscIdCategoryCdAsc(
            Long accountId);
}
