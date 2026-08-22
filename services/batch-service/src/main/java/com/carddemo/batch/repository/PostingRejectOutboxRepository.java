package com.carddemo.batch.repository;

import com.carddemo.batch.domain.PostingRejectOutbox;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The posting step's durable reject outbox, in {@code batch.posting_reject_outbox}.
 *
 * <p>This interface carries the four operations the posting pass needs to survive a failure between
 * its last per-record commit and a durable reject dataset: an insert made inside each record's own
 * transaction, two per-run counts, and one ordered replay of a run's reject images followed by a
 * marking statement once the object holding them is durable.</p>
 *
 * <h2>Why the counts live here and not on the reject stream's own interface</h2>
 *
 * <p>Assumptions: {@link TransactionRejectRepository} deliberately offers NO count, and its own
 * contract records why -- {@code ledger.transaction_rejects} carries no run discriminator, so a count
 * over it aggregates every reject ever loaded into that schema and answers a different question than
 * the caller asked. This table carries the discriminator explicitly, so the same two counts are
 * meaningful here and are meaningless there. That is the whole reason the counters were moved rather
 * than added next door.</p>
 *
 * <p>Assumptions: the counts are the RUN's totals and not the current attempt's, which is what makes
 * a redriven step able to report what {@code app/cbl/CBTRN02C.cbl:227-228} prints for the night rather
 * than for the attempt. A redrive that walks no new feed rows still reports the run's real processed
 * and rejected figures, and the tier {@code app/cbl/CBTRN02C.cbl:229-230} grades from the rejected
 * figure is therefore the tier the run actually earned.</p>
 *
 * <h2>Read discipline</h2>
 *
 * <p>Assumptions: the ordered replay is keyset-continued on the feed ordinal, matching the walk it
 * reproduces and the discipline this package requires of every ordered read. The ordinal is the
 * ordering key because it is the order the walk appended in, so replaying ascending reproduces the
 * byte sequence the walk would have produced -- across attempts as well as within one, since a later
 * attempt starts strictly above the watermark an earlier one advanced.</p>
 *
 * <p>Assumptions: no query here binds to a physical column name and none is native, matching this
 * package's standing prohibition. The one statement written out below is expressed over the property
 * paths {@link PostingRejectOutbox} declares, so a mistyped path is reported when the application
 * context starts rather than part-way through a nightly chain.</p>
 *
 * @see PostingRejectOutbox for the property names every member below binds to
 */
public interface PostingRejectOutboxRepository extends Repository<PostingRejectOutbox, Long> {

    /**
     * Records that one feed record has been accounted for, with its reject image when it was rejected.
     *
     * <p>The insert takes part in whatever transaction the caller has open, and the caller opens one
     * per feed record around that record's own writes and its watermark advance. That is the whole
     * point of this member: the image, the row it describes and the position that accounts for it
     * commit or roll back together, so a redrive can neither lose a reject nor re-post a record.</p>
     *
     * @param accountedRecord the row to insert, built through one of the two named factories on
     *     {@link PostingRejectOutbox} so that a posted record and a rejected one are distinguishable
     *     at the call site; must not be {@code null}
     * @return the same row as a managed instance, now carrying the database-assigned ordinal that was
     *     absent on the argument
     * @throws org.springframework.dao.DataIntegrityViolationException if the row violates the column
     *     contract the owning migration declares -- a blank identifier, a business-date token of the
     *     wrong width, an all-blank image, a staging marker with only one of its two members, or a
     *     second row for a record this run has already accounted for
     * @throws IllegalArgumentException if {@code accountedRecord} is {@code null}
     */
    // Assumptions: declaring this member explicitly is what makes it reachable, because the base
    //     interface is a marker that declares nothing. Only the four members below it are reachable,
    //     so nothing on this boundary can delete a row -- which matches the privileges the runtime
    //     role actually holds, since V0 grants it SELECT, INSERT and UPDATE on this schema's tables
    //     and no DELETE at all.
    PostingRejectOutbox save(PostingRejectOutbox accountedRecord);

    /**
     * Counts every feed record one run has accounted for, posted and rejected alike.
     *
     * <p>This is the processed counter {@code app/cbl/CBTRN02C.cbl:227} prints, read from durable rows
     * so that it states the run's total rather than the current attempt's.</p>
     *
     * @param runId the orchestrator execution to count for; must not be {@code null}
     * @param feedName the record-layout name of the feed, so a run that posted two feeds counts each
     *     separately; must not be {@code null}
     * @return how many records that run has accounted for on that feed, zero when it has accounted
     *     for none
     * @throws org.springframework.dao.DataAccessException if the count cannot be executed, for
     *     instance because the connection's search path does not resolve the table
     */
    long countByRunIdAndFeedName(String runId, String feedName);

    /**
     * Counts the records one run rejected, being those whose row carries an image.
     *
     * <p>This is the rejected counter {@code app/cbl/CBTRN02C.cbl:228} prints and the figure
     * {@code :229-230} grades the return code from, read from the same durable rows for the same
     * reason.</p>
     *
     * @param runId the orchestrator execution to count for; must not be {@code null}
     * @param feedName the record-layout name of the feed; must not be {@code null}
     * @return how many of that run's accounted records were rejected, zero when none was
     * @throws org.springframework.dao.DataAccessException if the count cannot be executed, for
     *     instance because the connection's search path does not resolve the table
     */
    // Assumptions: the predicate is "carries an image" rather than a separate rejected flag, because a
    //     second column recording the same fact could disagree with the first one. The image IS the
    //     evidence of rejection: a posted record has none, and the owning migration refuses an
    //     all-blank one, so the two states are exactly the two the predicate distinguishes.
    long countByRunIdAndFeedNameAndRejectRecordIsNotNull(String runId, String feedName);

    /**
     * Reads the next slice of one run's reject images, in the order the dataset carries them.
     *
     * @param runId the orchestrator execution whose dataset is being assembled; must not be
     *     {@code null}
     * @param feedName the record-layout name of the feed; must not be {@code null}
     * @param ingestSeq the exclusive lower bound to continue from, being the ordinal of the last row
     *     already written, or a value below every ordinal to start
     * @param limit the greatest number of rows to return, so one round trip's memory is bounded by a
     *     slice rather than by a night; must not be {@code null}
     * @return the rows in ascending ordinal order, empty when the run has no further reject beyond the
     *     bound
     * @throws org.springframework.dao.DataAccessException if the read cannot be executed, for instance
     *     because the connection's search path does not resolve the table
     */
    // Assumptions: EVERY reject of the run is replayed on each staging attempt, not only the rows no
    //     marking statement has reached, which is why this method takes no staged-state predicate. An
    //     attempt that uploaded the object and then failed before marking must rewrite the whole
    //     object on its next attempt, because the generation coordinate is memoised per run and the
    //     retry therefore overwrites the same key: assembling from unmarked rows alone would replace a
    //     complete dataset with its own tail.
    // Assumptions: no fetch-size hint is set, unlike the ordered walks elsewhere in this package. Those
    //     walks are unbounded and stream a whole table, so the transfer size is worth pinning; this one
    //     is already bounded by the limit above, which is at most the chunk size, so a hint could only
    //     restate a bound the query already carries.
    List<PostingRejectOutbox>
            findByRunIdAndFeedNameAndRejectRecordIsNotNullAndIngestSeqGreaterThanOrderByIngestSeqAsc(
                    String runId, String feedName, long ingestSeq, Limit limit);

    /**
     * Marks every unstaged reject image of one run as having reached a durable object.
     *
     * @param runId the orchestrator execution whose images were staged; must not be {@code null}
     * @param feedName the record-layout name of the feed; must not be {@code null}
     * @param stagedAt the instant the object became durable, read from the module's injected clock;
     *     must not be {@code null}
     * @param objectKey the key of the object the images reached; must not be {@code null}
     * @return how many rows the statement marked, which is zero on a run with no reject and on a
     *     repeat of a marking that already succeeded
     * @throws org.springframework.dao.InvalidDataAccessApiUsageException if no transaction is open,
     *     since a modifying statement cannot be executed outside one
     * @throws org.springframework.dao.DataAccessException if the statement cannot be executed, for
     *     instance because the runtime role holds no update privilege on the table
     */
    // Assumptions: one set-shaped statement rather than a read-mutate-save per row. The rows have just
    //     been read as bytes, outside any transaction, so they are detached; saving each one back
    //     would re-select it and then update it, turning a single statement into two round trips per
    //     reject. Nothing else in the pass holds these rows in a live persistence context, which is
    //     what makes bypassing that context safe here.
    // Assumptions: the predicate excludes rows already marked, so a second marking of the same run is
    //     a no-op that reports zero rather than moving an instant that already recorded a real
    //     publication. A redriven step whose predecessor staged successfully never reaches this
    //     statement at all, because the durable step ledger short-circuits a completed step; this
    //     guard covers the narrower case of one attempt that staged twice.
    // Assumptions: the statement is JPQL over declared property paths and not native SQL, matching
    //     this package's prohibition, so an unresolvable path fails when the context starts rather
    //     than in the middle of a nightly chain.
    @Modifying
    @Query("""
            update PostingRejectOutbox o
               set o.stagedAt = :stagedAt,
                   o.stagedObjectKey = :objectKey
             where o.runId = :runId
               and o.feedName = :feedName
               and o.rejectRecord is not null
               and o.stagedAt is null
            """)
    int markRejectsStaged(@Param("runId") String runId, @Param("feedName") String feedName,
            @Param("stagedAt") LocalDateTime stagedAt, @Param("objectKey") String objectKey);
}
