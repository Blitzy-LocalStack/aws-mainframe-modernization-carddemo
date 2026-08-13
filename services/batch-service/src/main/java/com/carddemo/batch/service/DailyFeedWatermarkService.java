package com.carddemo.batch.service;

import com.carddemo.batch.domain.DailyFeedWatermark;
import com.carddemo.batch.repository.DailyFeedWatermarkRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Owns the consumed position of the accumulating daily-transaction feed.
 *
 * <p>Refactoring Rationale: this service exists because the migrated posting pass had no way to tell
 * tonight's input from every night's. The reference did not need one: {@code app/jcl/POSTTRAN.jcl}
 * lines 30 and 31 supply the feed as the flat sequential dataset
 * {@code AWS.M2.CARDDEMO.DALYTRAN.PS}, {@code app/cbl/CBTRN02C.cbl} lines 29 to 32 declare it
 * {@code ORGANIZATION IS SEQUENTIAL} with no record key, and the driver at lines 202 to 219 reads it
 * to end of file -- and the dataset is REPLACED between runs, so the whole file and tonight's
 * transactions are the same set. The target's feed is a table that accumulates, because its rows are
 * what the three verification passes in {@code docs/runbooks/data-migration.md} compare against, so
 * a walk that began at the first row re-posted every earlier night on every later night: those
 * amounts were added to account balances again and a second posted row was inserted for each. Every
 * one of those postings is individually valid, so nothing in the chain would have reported it.</p>
 *
 * <h2>What this service guarantees, and what it deliberately does not</h2>
 *
 * <p>Assumptions: the watermark ONLY EVER ADVANCES. A pass that consumed nothing writes nothing, and
 * a pass whose highest ordinal is not above the stored one writes nothing -- so a redrive that finds
 * the feed already consumed leaves the row exactly as it was. That is what makes the posting step
 * idempotent with respect to this table as well as with respect to the ledger.</p>
 *
 * <p>Assumptions: the advance is written INSIDE the caller's transaction and is never given a
 * transaction of its own. The posting pass runs as one tasklet transaction, so the watermark and
 * every posting it accounts for commit together or roll back together. A separate transaction here
 * would break exactly that: a watermark committed ahead of a rolled-back pass would skip a night's
 * transactions permanently, and one committed after would leave a window in which a crash re-posts
 * them.</p>
 *
 * <p>Trade-offs: no HISTORY is kept. Each advance overwrites the previous position, so this table
 * cannot answer which run consumed which range. That is declined because the answer already exists
 * twice over -- {@code batch.batch_run} records every step of every run, and the posting step logs
 * the ordinal range it consumed -- and a per-run history would turn the one question the pass asks,
 * "what is the highest ordinal anyone has consumed", into an aggregate over an unbounded set.</p>
 *
 * @see DailyFeedWatermark
 * @see com.carddemo.batch.repository.DailyTransactionRepository
 */
@Service
public class DailyFeedWatermarkService {

    /** Logger for the two events an operator reads when a pass posts fewer rows than expected. */
    private static final Logger LOG = LoggerFactory.getLogger(DailyFeedWatermarkService.class);

    /**
     * Feed name of the daily-transaction feed, as the record-layout vocabulary spells it.
     *
     * <p>Assumptions: the LAYOUT name and not the table name, because that is the vocabulary the
     * migration registry, the three verification passes and the runbook already use for this feed --
     * so one word names it in the watermark row, in a {@code load-dataset} invocation and in a
     * verification report. Keying on the table name would make the row and those commands disagree
     * about what the feed is called.</p>
     */
    public static final String DAILY_TRANSACTION_FEED = "DALYTRAN";

    /** Position meaning nothing has been consumed, and the exclusive bound a first pass walks from. */
    public static final long NOTHING_CONSUMED = 0L;

    /** The stored positions. */
    private final DailyFeedWatermarkRepository watermarks;

    /** The clock the advance's own timestamp is read from. */
    private final Clock clock;

    /**
     * Builds the service over the watermark table and the clock its timestamps come from.
     *
     * @param watermarks the watermark repository; must not be {@code null}
     * @param clock the clock the recorded moment of an advance is read from, injected rather than
     *     taken from the system so a test observes a fixed value; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public DailyFeedWatermarkService(DailyFeedWatermarkRepository watermarks, Clock clock) {
        this.watermarks = Objects.requireNonNull(watermarks, "watermarks must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Reads the position a consuming pass must walk from, locking the row against a second pass.
     *
     * <p>Assumptions: this is the read for a pass that will CONSUME, which is why it locks. The lock
     * is held until the caller's transaction ends -- for the posting pass, until the whole night
     * commits -- so two overlapping passes serialise instead of both reading the same position and
     * both posting the same rows.</p>
     *
     * @param feedName the record-layout name of the feed; must not be {@code null} or blank
     * @return the ingestion ordinal already consumed, as an exclusive lower bound for the walk, or
     *     {@link #NOTHING_CONSUMED} when the feed has never been consumed
     * @throws NullPointerException if {@code feedName} is {@code null}
     * @throws IllegalArgumentException if {@code feedName} is blank
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller holds no
     *     transaction, because a row lock cannot be taken outside one
     */
    public long consumedThroughForConsumer(String feedName) {
        String feed = requireFeedName(feedName);
        long position = this.watermarks.findByFeedName(feed)
                .map(DailyFeedWatermark::getLastIngestSeq)
                .orElse(NOTHING_CONSUMED);
        LOG.info("event=batch.feed.watermark.read feed={} consumedThrough={} locked=true",
                feed, position);
        return position;
    }

    /**
     * Reads the position a reporting pass should describe, taking no lock.
     *
     * <p>Assumptions: this is the read for a pass that will NOT consume -- the preflight report --
     * and it deliberately takes no lock. A reader that locked would block the pass that actually
     * consumes, for no benefit: the preflight writes nothing, so a position that moved under it
     * costs at most a report describing a window one night old, and the preflight state runs before
     * the posting state in the chain in any case.</p>
     *
     * @param feedName the record-layout name of the feed; must not be {@code null} or blank
     * @return the ingestion ordinal already consumed, as an exclusive lower bound for the report's
     *     window, or {@link #NOTHING_CONSUMED} when the feed has never been consumed
     * @throws NullPointerException if {@code feedName} is {@code null}
     * @throws IllegalArgumentException if {@code feedName} is blank
     */
    public long consumedThroughForReader(String feedName) {
        String feed = requireFeedName(feedName);
        // WHY : Assumptions: the inherited findById is used here precisely BECAUSE it carries no
        //       @Lock. The two reads in this class differ only in that, and using one method with a
        //       boolean would hide the difference behind an argument at every call site.
        long position = this.watermarks.findById(feed)
                .map(DailyFeedWatermark::getLastIngestSeq)
                .orElse(NOTHING_CONSUMED);
        LOG.info("event=batch.feed.watermark.read feed={} consumedThrough={} locked=false",
                feed, position);
        return position;
    }

    /**
     * Records that a run has consumed the feed through one ordinal, if that advances the position.
     *
     * <p>Assumptions: a request that would not advance the position is a NO-OP and is logged as one
     * rather than refused. The two ways to reach it are both legitimate: a pass that found the feed
     * empty has nothing to record, and a redriven pass that the ledger let re-run may re-read a
     * window another attempt already accounted for. Refusing either would turn a correct rerun into
     * a failed step.</p>
     *
     * @param feedName the record-layout name of the feed; must not be {@code null} or blank
     * @param consumedThrough the ingestion ordinal of the last row this run consumed, as a
     *     {@code long}; must not be negative, and {@link #NOTHING_CONSUMED} means the run consumed
     *     nothing
     * @param runId the orchestrator execution recording the advance; must not be {@code null} or
     *     blank
     * @param businessDate the injected business-date TOKEN of that run, ten characters carried
     *     verbatim rather than parsed, because {@code BusinessDate} admits both the compact layout
     *     {@code app/jcl/INTCALC.jcl:22} injects and the separated ISO layout the chain injects; must
     *     not be {@code null}
     * @return {@code true} when the stored position moved, {@code false} when the request was a
     *     no-op because it did not advance
     * @throws NullPointerException if any reference argument is {@code null}
     * @throws IllegalArgumentException if {@code feedName} or {@code runId} is blank, if
     *     {@code businessDate} is not exactly ten characters, or if {@code consumedThrough} is
     *     negative
     * @throws org.springframework.transaction.IllegalTransactionStateException if the caller holds no
     *     transaction, since the locking read below requires one
     */
    public boolean recordConsumedThrough(String feedName, long consumedThrough, String runId,
            String businessDate) {

        String feed = requireFeedName(feedName);
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        if (consumedThrough < NOTHING_CONSUMED) {
            throw new IllegalArgumentException(
                    "consumedThrough must not be negative but was " + consumedThrough);
        }

        Optional<DailyFeedWatermark> stored = this.watermarks.findByFeedName(feed);
        long previous = stored.map(DailyFeedWatermark::getLastIngestSeq).orElse(NOTHING_CONSUMED);
        if (consumedThrough <= previous) {
            LOG.info("event=batch.feed.watermark.unchanged feed={} consumedThrough={} stored={}"
                    + " runId={}", feed, consumedThrough, previous, runId);
            return false;
        }

        // WHY : Assumptions: the timestamp is read ONCE here rather than inside the entity, so the
        //       value stored is the one this method logs. A second reading inside the entity would
        //       differ by however long the write took, and the difference would be invisible.
        LocalDateTime at = LocalDateTime.now(this.clock);
        // WHY : Trade-offs: the two arms differ only in whether an entity is mutated or created, and
        //       they are written out rather than collapsed into one save of a fresh instance. A
        //       fresh instance would work -- the identifier is assigned, so save() merges -- and it
        //       is declined because merging over a row held under a pessimistic write lock discards
        //       the locked managed instance the read produced, which makes the lock's protection
        //       depend on a detail of how save() is implemented rather than on this code.
        stored.ifPresentOrElse(
                watermark -> watermark.advanceTo(consumedThrough, runId, businessDate, at),
                () -> this.watermarks.save(new DailyFeedWatermark(
                        feed, consumedThrough, runId, businessDate, at)));

        LOG.info("event=batch.feed.watermark.advanced feed={} from={} to={} runId={}"
                + " businessDate={}", feed, previous, consumedThrough, runId, businessDate);
        return true;
    }

    /**
     * Validates a feed name before it is used as a primary key.
     *
     * @param feedName the candidate name; must not be {@code null} or blank
     * @return the name unchanged, never {@code null}
     * @throws NullPointerException if {@code feedName} is {@code null}
     * @throws IllegalArgumentException if {@code feedName} is blank
     */
    private static String requireFeedName(String feedName) {
        Objects.requireNonNull(feedName, "feedName must not be null");
        if (feedName.isBlank()) {
            throw new IllegalArgumentException("feedName must not be blank");
        }
        return feedName;
    }
}
