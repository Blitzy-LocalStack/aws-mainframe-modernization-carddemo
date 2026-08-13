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
 * <p>Assumptions: the read below takes a PESSIMISTIC WRITE lock, and the whole posting pass runs in
 * one transaction, so the lock is held for the pass's duration. That is deliberate: it serialises
 * two posting passes that somehow overlap, rather than letting both read the same position and both
 * post the same rows. The chain's own online-write lease already makes an overlap unlikely -- the
 * quiesce state acquires a bracket that a second execution cannot -- so this is a second line
 * rather than the only one, and it costs a single-row lock on a table nothing else reads.</p>
 *
 * <p>Trade-offs: locking a row that may not exist gives no protection for the very first pass, and
 * that gap is accepted rather than closed with an advisory lock or a seeded row. Two concurrent
 * first passes would both find nothing, both post from the beginning, and both attempt to INSERT the
 * same primary key -- so one of them is refused by {@code pk_daily_feed_watermark} and its whole
 * transaction rolls back, including its postings. The outcome is therefore one completed pass and
 * one failed step rather than a double post, which is the same outcome the lock produces on every
 * later pass.</p>
 *
 * <h2>Rulings this interface inherits from the package charter</h2>
 *
 * <p>Alternatives Considered: native SQL -- in particular one {@code INSERT ... ON CONFLICT DO
 * UPDATE} statement that would advance or create the row in a single round trip. Rejected on the
 * timing of the failure it admits, which is the charter's standing reason: this module runs the
 * persistence provider with schema handling set to {@code validate}, and that pass compares mapping
 * metadata against the deployed table while never parsing the text of a native query. A mistyped
 * physical column inside a native upsert stays invisible until the statement executes, which for
 * this module means at the END of a nightly posting pass with every posting already written. The
 * members here are a derived-name query and the inherited {@code save}, both bound to property names
 * declared on {@code DailyFeedWatermark}, so no physical column name appears in this file.</p>
 *
 * @see DailyFeedWatermark
 */
public interface DailyFeedWatermarkRepository extends JpaRepository<DailyFeedWatermark, String> {

    /**
     * Reads one feed's consumed position, taking a write lock on the row.
     *
     * <p>This is the read the posting pass performs before it walks anything, and the lock it takes
     * is what stops a second pass reading the same position concurrently.</p>
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
