package com.carddemo.batch.service;

import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.DailyTransaction;
import com.carddemo.batch.domain.TransactionCategoryBalance;
import com.carddemo.batch.domain.TransactionCategoryBalance.TransactionCategoryBalanceId;
import com.carddemo.batch.repository.TransactionCategoryBalanceRepository;
import com.carddemo.common.money.Money;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Accumulates a posted transaction amount onto the running per-category balance, through two arms
 * that stay separately observable.
 *
 * <p>This is the migrated form of three paragraphs of {@code app/cbl/CBTRN02C.cbl}, and the split
 * between them is reproduced rather than smoothed over. {@code 2700-UPDATE-TCATBAL} at line 467
 * builds the three-part key, reads the row and selects an arm; {@code 2700-A-CREATE-TCATBAL-REC} at
 * line 503 is the create arm; {@code 2700-B-UPDATE-TCATBAL-REC} at line 526 is the update arm. The
 * branch between the two is at lines 495 to 499 and turns on a working-storage flag,
 * {@code WS-CREATE-TRANCAT-REC PIC X(01) VALUE 'N'} declared at line 190, which line 473 presets to
 * the update value and the read's {@code INVALID KEY} clause sets to {@code 'Y'} at line 478.</p>
 *
 * <h2>What the two arms actually differ by, which is less than it looks</h2>
 *
 * <p>Assumptions: <b>both arms are ADDITIVE and neither assigns.</b> Line 508 in the create arm and
 * line 527 in the update arm are the same statement, character for character:
 * {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL}. The create arm reaches the same result as an assignment
 * only because {@code INITIALIZE TRAN-CAT-BAL-RECORD} at line 504 has zeroed the balance first, so
 * the amount accumulates onto zero rather than replacing anything. The whole of the difference
 * between the arms is that {@code INITIALIZE}, the three key moves at lines 505 to 507, and
 * {@code WRITE} at line 510 against {@code REWRITE} at line 528.</p>
 *
 * <p>Assumptions: <b>the account component of the key arrives from the card cross-reference and
 * never from the transaction being posted.</b> Line 469 moves {@code XREF-ACCT-ID}, the value the
 * earlier cross-reference read produced, while lines 470 and 471 move {@code DALYTRAN-TYPE-CD} and
 * {@code DALYTRAN-CAT-CD} from the transaction. The daily-transaction record has no account field to
 * read at all: {@code app/cpy/CVTRA06Y.cpy} declares a card number and no account. The same ruling
 * is recorded independently by the corroborating sibling
 * {@code com.carddemo.batch.mapper.TransactionCategoryBalanceRecordMapper}, so a reader meeting the
 * value on either side of that boundary meets one statement rather than two.</p>
 *
 * <p>Assumptions: <b>a missing row is an ordinary outcome and not a failure.</b> Line 481 accepts
 * file status {@code '00'} or {@code '23'} as clean, {@code '23'} being record-not-found, and only a
 * third status reaches the abend path at lines 489 to 492. An absent row is therefore what the first
 * transaction of a category produces, and it selects the create arm rather than reporting anything.
 * Nothing here throws on it, logs it as an error, or counts it as a failure.</p>
 *
 * <h2>What this class is measured against</h2>
 *
 * <p>Assumptions: the value written here is read straight back out by interest accrual, so an error
 * in this arithmetic does not stay local. {@code app/cbl/CBACT04C.cbl} lines 464 and 465 compute
 * {@code WS-MONTHLY-INT = ( TRAN-CAT-BAL * DIS-INT-RATE) / 1200}, taking this balance as the
 * multiplicand of the one formula in the migration that has to agree to the cent. A cent lost or
 * accumulated wrongly here therefore surfaces twice: once against the category-balance expectation
 * and again, scaled by a rate, against the accrual expectation.</p>
 *
 * <p>Assumptions: the visible output of this class in a parity run is a single row.
 * {@code tests/golden/posting/*}{@code /tcatbal.expected} is 51 bytes for every one of the eight
 * posting scenarios -- the 50-byte record that {@code app/cpy/CVTRA01Y.cpy} declares plus its
 * trailing newline. There is consequently no aggregate for an off-by-one in either arm to hide
 * inside, which is the property that makes a stale balance on the create arm detectable at all.</p>
 *
 * @see TransactionCategoryBalance
 * @see TransactionCategoryBalanceRepository
 */
// Trade-offs: the two arms are kept visibly distinct rather than collapsed into one upsert, and the
//     cost of that is real: this class carries two write paths and one extra read where a single
//     save, or a database-side insert-on-conflict, would carry neither. It is paid for the property
//     the shorter form destroys. tests/README.md section 13 names 2700-A-CREATE and 2700-B-UPDATE
//     and requires the branch exercised both ways, and the migration plan's section 0.5.1.7 requires
//     both paths distinguishable and separately tested. An upsert resolves the branch inside the
//     database, where no caller and no test can observe which way it went, so the assertion those
//     two documents ask for would have nothing to bind to.
// Alternatives Considered: reporting the arm through a log line or a metric rather than through the
//     return value. Rejected because a test would then have to assert against an appender or a
//     registry to learn which arm ran, which couples the parity assertion to the observability
//     configuration; the Outcome record below carries the fact in the value itself.
@Service
public class CategoryBalanceService {

    /**
     * The logger for the two diagnostics the reference emits when it takes the create arm.
     *
     * <p>Assumptions: the reference writes both to {@code SYSOUT} through {@code DISPLAY} at lines
     * 476 and 477, which is a job-log diagnostic rather than a user-facing message, so the migrated
     * form is a log statement rather than anything that reaches a response.</p>
     */
    private static final Logger LOG = LoggerFactory.getLogger(CategoryBalanceService.class);

    /**
     * The rows this service reads and writes, reached through the scoped cross-schema grant.
     *
     * <p>Assumptions: the table is {@code ledger.transaction_category_balances}, owned by
     * {@code transaction-service}. This module writes it under the narrowly-scoped cross-schema
     * grant that the migration plan's section 0.4.1.3 records as the one documented exception to
     * database-per-service purity, and that exception exists so the three writes at
     * {@code app/cbl/CBTRN02C.cbl} lines 440 to 442 can remain one commit.</p>
     */
    private final TransactionCategoryBalanceRepository balances;

    /**
     * Builds the service over the repository it reads and writes the category-balance table through.
     *
     * @param balances the {@link TransactionCategoryBalanceRepository} over
     *     {@code ledger.transaction_category_balances}; must not be {@code null}
     * @throws NullPointerException if {@code balances} is {@code null}, which is a wiring defect
     *     rather than a condition to tolerate, and one worth naming here because every method below
     *     would otherwise fail identically and much later
     */
    public CategoryBalanceService(TransactionCategoryBalanceRepository balances) {
        // WHY : Alternatives Considered: constructor injection rather than an injected field or a
        //       setter. The migration plan's section 0.4.3 adopts it across the services precisely so
        //       that a unit test can build this class with a mock repository and no container, which
        //       is how the two arms below are asserted separately; a field-injected dependency would
        //       need reflection or a context to populate. It also lets the reference be final, so no
        //       instance of this class can exist in a half-wired state.
        this.balances = Objects.requireNonNull(balances, "balances must not be null");
    }

    /**
     * Accumulates one posted transaction onto its category balance, composing the key from the record
     * and the cross-reference it resolved through.
     *
     * <p>This is the whole of {@code 2700-UPDATE-TCATBAL} at {@code app/cbl/CBTRN02C.cbl} line 467,
     * including the key construction at lines 469 to 471 that the reference performs before its read.
     * It is the entry point the posting job calls once per accepted record.</p>
     *
     * <p>Assumptions: the two arguments are the two sources the reference draws the key from, and
     * they are not interchangeable. The account component comes from {@code resolved} because line
     * 469 moves {@code XREF-ACCT-ID}; the type and category components come from {@code feedRecord}
     * because lines 470 and 471 move {@code DALYTRAN-TYPE-CD} and {@code DALYTRAN-CAT-CD}. The amount
     * comes from {@code feedRecord} as well, from {@code DALYTRAN-AMT PIC S9(09)V99} at
     * {@code app/cpy/CVTRA06Y.cpy} line 10.</p>
     *
     * @param feedRecord the {@link DailyTransaction} being posted, supplying the transaction type
     *     code, the transaction category code and the signed amount; must not be {@code null}
     * @param resolved the {@link CardXref} the record's card number resolved to, and the only source
     *     of the account component of the key; must not be {@code null}
     * @return the {@link Outcome} naming which arm ran and the balance the row now carries, never
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}, or if the record carries no
     *     type code, category code or amount, since none of the three has a meaningful absent value
     *     on a record that has already been accepted for posting
     * @throws IllegalArgumentException if the record's type code is not exactly two characters or its
     *     category code is not exactly four digits, which
     *     {@link TransactionCategoryBalanceId#TransactionCategoryBalanceId(Long, String, String)}
     *     refuses because a component short of its column's width is stored padded and compares as a
     *     different key
     * @throws ArithmeticException if the resulting balance needs more integer digits than
     *     {@code TRAN-CAT-BAL PIC S9(09)V99} declares
     */
    public Outcome accumulatePostedTransaction(DailyTransaction feedRecord, CardXref resolved) {
        Objects.requireNonNull(feedRecord, "feedRecord must not be null");
        Objects.requireNonNull(resolved, "resolved must not be null");

        // WHY : Assumptions: the three components are read BY NAME from two different objects, which
        //       is the point of composing the key here rather than accepting a finished one. The
        //       account identifier is taken from the cross-reference because app/cbl/CBTRN02C.cbl:469
        //       moves XREF-ACCT-ID, and the daily-transaction record has no account field to take it
        //       from -- app/cpy/CVTRA06Y.cpy declares a card number and no account. A positional or
        //       inferred construction would compile just as well while keying every balance to the
        //       wrong account, and that failure is close to invisible: every balance would still be a
        //       plausible number, and only a card belonging to an unexpected account would expose it.
        TransactionCategoryBalanceId id = new TransactionCategoryBalanceId(
                resolved.getAccountId(), feedRecord.getTypeCd(), feedRecord.getCategoryCd());

        return accumulate(id, Money.of(feedRecord.getAmount()));
    }

    /**
     * Reads the category row by its whole key and accumulates the amount through whichever arm the
     * result selects.
     *
     * <p>This is the read and the branch of {@code 2700-UPDATE-TCATBAL}, at
     * {@code app/cbl/CBTRN02C.cbl} lines 474 to 499: the keyed read at line 474, and the choice of
     * arm at lines 495 to 499.</p>
     *
     * <p>Refactoring Rationale: this was described as "kept separately callable from the entry point
     * above so that a caller already holding a composite key does not have to synthesise a record and
     * a cross-reference to reach the branch", and that sentence described a caller which did not
     * exist. Worse, it was the sentence the posting job followed: the job composed the key itself and
     * called this method, so the key composition existed in two places and the entry point above --
     * documented as the one the job calls -- was called by nothing. The job now calls the entry point.
     * This method remains public and separately callable for the SAME reason its two arms below give,
     * which is the honest one: the parity suite drives the read-and-branch with a key made for the
     * case under test. That is a test affordance, and it is stated as one rather than dressed as a
     * convenience for a production caller that was never written.</p>
     *
     * <p>Assumptions: no transaction is opened here, and that omission is deliberate rather than an
     * oversight. See the note on the absence of a boundary inside the method.</p>
     *
     * @param id the {@link TransactionCategoryBalanceId} whole three-part key of the category being
     *     accumulated onto -- the account, the transaction type code and the transaction category
     *     code that form the seventeen-byte group at {@code app/cpy/CVTRA01Y.cpy} line 5; must not be
     *     {@code null}
     * @param amount the {@link Money} transaction amount to add, which may be zero or negative
     *     because {@code TRAN-CAT-BAL PIC S9(09)V99} is signed; must not be {@code null}
     * @return the {@link Outcome} naming which arm ran and the balance the row now carries, never
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws ArithmeticException if the resulting balance needs more integer digits than
     *     {@code TRAN-CAT-BAL PIC S9(09)V99} declares, which the entity's own mutator reports against
     *     that picture rather than against the widest money field in the reference set
     */
    public Outcome accumulate(TransactionCategoryBalanceId id, Money amount) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        // WHY : Trade-offs: there is deliberately NO transaction boundary on this method or on either
        //       arm below. app/cbl/CBTRN02C.cbl lines 440 to 442 perform the category-balance update,
        //       the account update and the transaction write as one unit of work, and the migration
        //       plan's section 0.5.1.7 places that boundary on the posting job's chunk. A boundary
        //       here would let this row commit independently of the other two, making a
        //       partial-posting state observable -- a balance moved with no transaction row to explain
        //       it -- which the baseline never exhibits and which is the precise reason the plan's
        //       section 0.4.3 rejected a saga for this flow. What is given up is that this method
        //       cannot be called safely outside a caller's transaction; that is stated rather than
        //       defended against, because the alternative trades a local convenience for a parity
        //       failure the golden masters would report.
        Optional<TransactionCategoryBalance> existing = this.balances.findByIdIs(id);

        // WHY : Assumptions: an ABSENT row is a normal outcome and is exactly what selects the create
        //       arm. app/cbl/CBTRN02C.cbl:481 accepts file status '00' OR '23' as clean, '23' being
        //       record-not-found, and only a third status reaches the abend path at lines 489 to 492.
        //       So nothing here throws, logs an error or counts a failure on an empty result. The
        //       reference needs a working-storage flag to carry that fact ten statements past an error
        //       check that shares the same status field; an Optional carries it in the value, so the
        //       preset at line 473 has no counterpart and cannot be left stale.
        if (existing.isEmpty()) {
            // WHY : Trade-offs: the reference's two diagnostics are reproduced verbatim EXCEPT that
            //       the key renders without its account identifier, and the divergence is the
            //       masking. app/cbl/CBTRN02C.cbl lines 476 and 477 display the raw seventeen-byte
            //       FD-TRAN-CAT-KEY, whose leading component is the account; the migrated form logs the
            //       key through TransactionCategoryBalanceId.toString(), which withholds that
            //       component because docs/architecture/observability.md names account identifiers as
            //       protected. The cost is that two accounts' rows for one category are no longer
            //       distinguishable from a log line alone, which is paid down by the batch.batch_run
            //       step ledger; the alternative would re-disclose in a durable log exactly what the
            //       entity and its key already withhold from every other rendering.
            // WHY : Assumptions: DEBUG is the level because the reference emits this per created
            //       category on an ordinary run rather than on an error, so at a higher level the
            //       first posting run over a fresh table would log one line per category and read as
            //       a fault.
            LOG.debug("TCATBAL record not found for key : {}.. Creating.", id);

            return createCategoryBalance(id, amount);
        }

        return updateCategoryBalance(existing.get(), amount);
    }

    /**
     * Writes a category row that did not exist, carrying the amount accumulated onto zero.
     *
     * <p>This is {@code 2700-A-CREATE-TCATBAL-REC} at {@code app/cbl/CBTRN02C.cbl} line 503, in its
     * four steps and in its order: {@code INITIALIZE TRAN-CAT-BAL-RECORD} at line 504, the three key
     * moves at lines 505 to 507, {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at line 508, and
     * {@code WRITE} at line 510.</p>
     *
     * <p>Assumptions: this arm is reached only when the read in {@link #accumulate} found no row. It
     * is public so that the branch the parity suite exercises in both directions can be asserted
     * directly, without a test having to arrange an empty repository result to reach it.</p>
     *
     * @param id the {@link TransactionCategoryBalanceId} whole three-part key of the row being
     *     created; must not be {@code null}
     * @param amount the {@link Money} transaction amount to accumulate onto the new row's zero, which
     *     may be zero or negative; must not be {@code null}
     * @return the {@link Outcome} reporting {@link Arm#CREATED} and the balance the new row carries,
     *     never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws ArithmeticException if the amount needs more integer digits than
     *     {@code TRAN-CAT-BAL PIC S9(09)V99} declares
     */
    public Outcome createCategoryBalance(TransactionCategoryBalanceId id, Money amount) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        // WHY : Assumptions: a NEW instance is constructed on every call and its balance starts at an
        //       explicit zero, because INITIALIZE TRAN-CAT-BAL-RECORD at app/cbl/CBTRN02C.cbl:504 is
        //       load-bearing rather than tidiness. The read at :474 FAILED to reach this arm, and a
        //       failed COBOL read leaves its target group item untouched -- so at :504 the record
        //       still holds the PREVIOUS iteration's bytes, including some unrelated account, type and
        //       category's balance. The INITIALIZE is what clears that before the additive ADD at
        //       :508. The entity's zero-seeded constructor is used rather than a locally written zero
        //       literal so that the scale comes from the money contract that owns it.
        // WHY : Alternatives Considered: two shorter forms were available and both are wrong.
        //       (a) Reusing a loop-scoped or field-held instance across iterations, which is the
        //       literal transcription of a COBOL record area and would reproduce the stale bytes the
        //       INITIALIZE exists to clear -- a second category would silently inherit the first's
        //       balance. (b) Treating this arm as an ASSIGNMENT, passing the amount straight to the
        //       two-argument constructor. That reaches the same number today and misstates why: :508
        //       is an ADD, identical to :527, so the create arm accumulates onto a cleared field
        //       rather than replacing it. Encoding the assignment would leave the next reader believing
        //       the arms differ in their arithmetic, when they differ only in what the arithmetic
        //       starts from.
        TransactionCategoryBalance created = new TransactionCategoryBalance(id);

        // WHY : Assumptions: the addend is read from the row's OWN freshly-initialised balance rather
        //       than from a zero written here, which makes the ADD at :508 literal instead of implied
        //       and leaves this arm structurally identical to the update arm below. That is the shape
        //       the reference has: the two paragraphs run the same statement over a field that either
        //       was just cleared or was just read. It also means the base cannot drift if the entity's
        //       zero-seeded constructor is ever changed, because this line reads whatever that
        //       constructor actually produced instead of assuming it.
        Money accumulated = Money.of(created.getBalance()).plus(amount);
        created.setBalance(accumulated.amount());

        this.balances.save(created);

        return new Outcome(Arm.CREATED, accumulated.amount());
    }

    /**
     * Adds the amount to the balance a category row already carries and writes it back.
     *
     * <p>This is {@code 2700-B-UPDATE-TCATBAL-REC} at {@code app/cbl/CBTRN02C.cbl} line 526, which is
     * two statements: {@code ADD DALYTRAN-AMT TO TRAN-CAT-BAL} at line 527 and {@code REWRITE} at
     * line 528.</p>
     *
     * <p>Assumptions: this arm is reached only when the read in {@link #accumulate} found a row, and
     * it accumulates onto the balance that read returned rather than onto anything recomputed. It is
     * public for the same reason the create arm is.</p>
     *
     * @param existing the {@link TransactionCategoryBalance} row the keyed read returned, whose
     *     balance is the base the amount accumulates onto; must not be {@code null}
     * @param amount the {@link Money} transaction amount to add, which may be zero or negative; must
     *     not be {@code null}
     * @return the {@link Outcome} reporting {@link Arm#UPDATED} and the balance the row now carries,
     *     never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws ArithmeticException if the resulting balance needs more integer digits than
     *     {@code TRAN-CAT-BAL PIC S9(09)V99} declares, which is the overflow a long-running category
     *     would reach rather than a defect in any one transaction
     */
    public Outcome updateCategoryBalance(TransactionCategoryBalance existing, Money amount) {
        Objects.requireNonNull(existing, "existing must not be null");
        Objects.requireNonNull(amount, "amount must not be null");

        // WHY : Assumptions: the arithmetic is exact decimal through the shared money type and
        //       never an IEEE-754 binary type, and the sum is formed here rather than inside the
        //       entity because the entity states that it holds a value and never computes one. That
        //       keeps the single arithmetic boundary the migration plan's transformation rules T3 and
        //       T4 require in one place; rule A3 of
        //       services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java
        //       fails the build on an IEEE-754 binary type anywhere under com.carddemo, so the ban
        //       is executable rather than advisory. It matters on this member in particular because
        //       the balance accumulates across every transaction of a category for a whole cycle, so a
        //       representation error would compound rather than cancel.
        // WHY : Assumptions: the amount is ADDED and is not conditioned on its sign, because
        //       TRAN-CAT-BAL is declared signed at app/cpy/CVTRA01Y.cpy:9 and :527 adds a signed
        //       amount unconditionally. A refund therefore reduces the balance here. Only the ACCOUNT
        //       side of posting inspects the sign, at app/cbl/CBTRN02C.cbl:548, and it does so to
        //       choose between two cycle accumulators rather than to change the arithmetic.
        Money accumulated = Money.of(existing.getBalance()).plus(amount);
        existing.setBalance(accumulated.amount());

        // WHY : Trade-offs: the WRITE at :510 and the REWRITE at :528 both become the same repository
        //       save, because a keyed insert and a keyed update over a row whose identity is already
        //       settled are one operation to a persistence provider. What the reference expresses through
        //       two verbs is preserved instead in the Arm this method reports, so the distinction the
        //       parity suite asserts survives even though the write call does not distinguish itself.
        this.balances.save(existing);

        return new Outcome(Arm.UPDATED, accumulated.amount());
    }

    /** Which of the reference's two arms an accumulation took. */
    public enum Arm {

        /**
         * The key was absent, so a row was written whose balance is the amount accumulated onto zero.
         *
         * <p>This is {@code 2700-A-CREATE-TCATBAL-REC} at {@code app/cbl/CBTRN02C.cbl} line 503,
         * selected when the read at line 474 entered its {@code INVALID KEY} clause.</p>
         */
        CREATED,

        /**
         * The key was present, so the amount was added to the balance the read returned.
         *
         * <p>This is {@code 2700-B-UPDATE-TCATBAL-REC} at {@code app/cbl/CBTRN02C.cbl} line 526.</p>
         */
        UPDATED
    }

    /**
     * What one accumulation did, being the arm it took and the balance it left behind.
     *
     * <p>Assumptions: the arm is returned rather than merely logged, because it is the fact the
     * parity suite has to observe. {@code tests/README.md} section 13 requires the branch exercised
     * both ways, and the migration plan's section 0.5.1.7 requires the two paths distinguishable, so
     * a caller and a test both need to learn which one ran without issuing a second read.</p>
     *
     * @param arm which of the two arms ran, never {@code null}
     * @param balance the balance the row carries after the accumulation, exact at two decimal places
     *     as {@code TRAN-CAT-BAL PIC S9(09)V99} declares, and signed, never {@code null}
     */
    public record Outcome(Arm arm, BigDecimal balance) {
    
        /**
         * Renders the ARM taken, never the resulting balance.
         *
         * <p>Purpose. The balance is a monetary value withheld by
         * {@code docs/architecture/observability.md} L1093 to L1112, and this record is produced once per
         * posted transaction, so the generated rendering emitted a category balance for every posting the
         * nightly chain performed.</p>
         *
         * <p>Assumptions: the arm is the whole diagnostic content and it is a bounded value. The reference
         * program distinguishes a create from an update at its two paragraphs, and which of the two ran is
         * the parity property the golden masters assert; the resulting figure is asserted against the
         * masters themselves rather than read from a log.</p>
         *
         * @return a rendering naming which arm was taken, with the resulting balance omitted; never
         *     {@code null}
         */
        @Override
        public String toString() {
            return "Outcome[arm=" + this.arm + ']';
        }
}
}
