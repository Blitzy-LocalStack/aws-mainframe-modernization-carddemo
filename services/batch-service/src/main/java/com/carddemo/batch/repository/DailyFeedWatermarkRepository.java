package com.carddemo.batch.repository;

import com.carddemo.batch.domain.DailyFeedWatermark;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the consumed position of each accumulating feed.
 *
 * <p>The table behind this interface is {@code batch.daily_feed_watermark}, and one row of it stands
 * for one feed -- in this deployment, one row for {@code DALYTRAN}. The row answers a question the
 * reference pipeline never had to ask, because the reference's feed was a flat dataset replaced
 * between runs: how much of an ACCUMULATING feed has already been posted. Without it the posting
 * pass walked from the first row every night and re-posted every previous night's transactions,
 * which under the additive model of {@code app/cbl/CBTRN02C.cbl:202-219} adds those amounts to
 * account balances a second time.</p>
 *
 * <h2>Why the full read and write surface</h2>
 *
 * <p>Alternatives Considered: the narrow {@code Repository} marker, as
 * {@code DailyTransactionRepository} uses, so that only the members declared here were reachable.
 * Rejected because this module genuinely writes this table -- the migration that created it is this
 * module's own and the posting step advances the row -- so withholding the write surface would
 * withhold a capability the grant permits and the step needs. The base type follows the grant, which
 * is the same rule that gives the feed repository a read-only base type: this module holds no writer
 * on the feed and every writer on this table.</p>
 *
 * <h2>Why the read is locked</h2>
 *
 * <p>Assumptions: the read below takes a PESSIMISTIC WRITE lock, and it is held for exactly as long
 * as the CALLING transaction -- which, for the posting pass, is one transaction per feed record and
 * one short transaction for the starting read, never the pass as a whole. So the lock serialises two
 * overlapping passes one ADVANCE at a time rather than for a night: each advance re-reads the row
 * under this lock inside its record's transaction, so two passes cannot interleave a read and a
 * write of the same position, and the service's monotonic guard turns the loser's lower value into a
 * no-op instead of moving the position backwards.</p>
 *
 * <p>Refactoring Rationale: this section stated that "the whole posting pass runs in one transaction,
 * so the lock is held for the pass's duration". That was true of {@code PostTransactionsJob} before
 * its boundary moved to the record, and the code now builds the step with
 * {@code PROPAGATION_NOT_SUPPORTED} and opens one {@code TransactionTemplate} transaction per
 * record. The claim is corrected rather than softened because it named this lock as what keeps two
 * passes apart: the primary mechanism is the chain's online-write lease -- the quiesce state acquires
 * a bracket a second execution cannot -- and this lock is the second line, at per-advance
 * granularity. A reader who believed the pass-long hold would size the mechanism wrongly in both
 * directions, expecting protection this lock no longer gives and lock contention it no longer
 * causes.</p>
 *
 * <p>Trade-offs: locking a row that may not exist gives no protection for the very first pass, and
 * that gap is accepted rather than closed with an advisory lock or a seeded row. Two concurrent first
 * passes would both find nothing and both attempt to INSERT the same primary key, so one of them is
 * refused by {@code pk_daily_feed_watermark}. Under the per-record boundary that refusal rolls back
 * only the RECORD being posted when it happened -- the records that pass had already committed stay
 * committed -- and the step fails, which the orchestrator's per-state retry re-runs. The retry then
 * finds the winner's row and resumes above it, so the outcome is still one advancing position and no
 * double post; what it is not, any longer, is an all-or-nothing rollback of that pass.</p>
 *
 * <h2>Rulings this interface inherits from the package charter</h2>
 *
 * <p>Alternatives Considered: native SQL -- in particular one {@code INSERT ... ON CONFLICT DO
 * UPDATE} statement that would advance or create the row in a single round trip. Rejected on the
 * timing of the failure it admits, which is the charter's standing reason: this module runs the
 * persistence provider with schema handling set to {@code validate}, and that pass compares mapping
 * metadata against the deployed table while never parsing the text of a native query. A mistyped
 * physical column inside a native upsert stays invisible until the statement executes, which for
 * this module means partway through a nightly posting pass -- on the first record that reaches the
 * advance, with that record's ledger and account writes already made inside the same transaction.
 * Refactoring Rationale: this read "at the END of a nightly posting pass with every posting already
 * written", which described the step's withdrawn pass-long boundary. Under the per-record boundary
 * the failure arrives earlier and rolls back one record rather than the night; the reason for
 * preferring a mapped query is unchanged either way, since {@code validate} cannot see inside native
 * SQL in either arrangement. The
 * members here are a derived-name query and the inherited {@code save}, both bound to property names
 * declared on {@code DailyFeedWatermark}, so no physical column name appears in this file.</p>
 *
 * @see DailyFeedWatermark
 */
public interface DailyFeedWatermarkRepository extends JpaRepository<DailyFeedWatermark, String> {

    /**
     * Reads one feed's consumed position, taking a write lock on the row.
     *
     * <p>This is the read the posting pass performs before it walks anything, and the read each
     * advance performs before it moves the position. The lock it takes lasts for the caller's
     * transaction only -- a short one for the starting read, and the record's own for each advance --
     * so it stops two passes interleaving a read and a write of this row, and does not hold the row
     * for the length of a pass.</p>
     *
     * @param feedName the record-layout name of the feed, for example {@code DALYTRAN}, a
     *     {@code String} of at most thirty characters and the table's primary key; must not be
     *     {@code null}
     * @return the one {@code DailyFeedWatermark} stored for that feed, as an
     *     {@code Optional<DailyFeedWatermark>}, or an EMPTY optional when the feed has never been
     *     consumed -- which is the ordinary state before the first pass rather than an error
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller holds no
     *     transaction, since a row lock cannot be taken outside one and the propagation below is
     *     {@code MANDATORY}
     */
    // Assumptions: the single-result contract is a claim about the SCHEMA. V2's
    //     pk_daily_feed_watermark keys the table on feed_name alone, so the name matches at most
    //     one row and the optional is earned rather than assumed. Were the key ever widened, this
    //     signature would become a latent incorrect-result-size failure at run time, which is why
    //     the constraint is cited here and not merely in the migration.
    // Alternatives Considered: the inherited findById, which addresses the same row and needs no
    //     declaration. Rejected because it cannot carry the lock: @Lock applies to the method it
    //     annotates, and annotating an inherited method is not possible without redeclaring it --
    //     at which point a named method is clearer about WHY it exists than an override would be.
    // Assumptions: MANDATORY propagation rather than the default REQUIRED, for the reason the feed
    //     repository's cursor states in its own words: REQUIRED would start a transaction when the
    //     caller had none and commit it as this method returned, so the lock would be released
    //     before the caller used the value and the protection would be silently absent. MANDATORY
    //     refuses the call and names the mistake at the call site.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<DailyFeedWatermark> findByFeedName(@Param("feedName") String feedName);
}
