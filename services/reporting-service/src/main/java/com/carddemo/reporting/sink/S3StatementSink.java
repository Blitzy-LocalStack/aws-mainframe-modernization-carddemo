package com.carddemo.reporting.sink;

import com.carddemo.reporting.service.StatementService;
import java.io.IOException;
import java.util.Objects;

/**
 * Publishes one statement run's two artifacts to object storage.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: {@link StatementService.StatementSink} is the seam the statement generator writes through,
 * and it had no implementation anywhere in the module. That is not a stylistic gap: the orchestrated
 * {@code GenerateStatements} state runs this image with {@code --job=generate-statements} and the run could
 * produce no artifact at all, because nothing existed to receive a record. This class is the destination
 * the seam was declared for.</p>
 *
 * <p>Assumptions: the run writes TWO artifacts and not one per statement, which is the reference's own
 * shape -- {@code FD-STMTFILE-REC PIC X(80)} at L45 and {@code FD-HTMLFILE-REC PIC X(100)} at L47 of
 * {@code app/cbl/CBSTM03A.CBL} are two datasets holding every statement of the run, with the two record
 * lengths corroborated by {@code DCB LRECL=80} at L89 and {@code LRECL=100} at L94 of
 * {@code app/jcl/CREASTMT.JCL}. The two are separate destinations, so a markup record can never reach the
 * plain-text artifact and the interleaving between them is not observable in either.</p>
 *
 * <p>⚠️ Assumptions: each artifact is written by its own {@link S3ArtifactWriter} to a key that carries
 * the run's identifier, so each is bounded to one part buffer, each becomes visible only when it is
 * complete, and NEITHER replaces anything. The reference deletes the previous run's output before
 * generating, at L66 of {@code app/jcl/CREASTMT.JCL}; that step is deliberately not reproduced as a
 * deletion, and it is no longer reproduced as an overwrite either. The overwrite reading was the one
 * recorded here before, and a review found the cost of it: three objects replaced one at a time are
 * three separate moments at which a reader sees a mixture of two runs. The previous run is now left
 * entirely alone and the new one is disclosed by a single manifest write in
 * {@code GenerateStatementsTask}, so {@link #replaceArtifacts()} asserts the destination rather than
 * clearing it and nothing this class writes is ever mutated.</p>
 *
 * <p>Trade-offs: a checked write failure is converted to an unchecked one at this boundary, because the
 * seam's two record methods declare no checked exception -- deliberately, so that the generator's body
 * reads as the paragraph it encodes. The generator's own contract is that a refused record stops the run
 * rather than leaving a statement missing transactions it totalled, and an unchecked failure carrying the
 * cause is what delivers that.</p>
 *
 * <p>Assumptions: this class is created per run rather than registered as a singleton bean. It holds two
 * open uploads, so a shared instance would let two runs write into one artifact, and its lifecycle is
 * exactly one run's.</p>
 *
 * <p>⚠️ Refactoring Rationale: publication is {@link #complete()} and {@link #close()} ABORTS, where
 * {@code close()} used to complete both writers. The old shape read as though it honoured the
 * complete-versus-close split {@code S3ArtifactWriter} declares, and it defeated it on the one path the
 * split exists for: every caller holds this sink in a try-with-resources, so a generation that raised
 * part way unwound through {@code close()}, which completed both uploads and thereby PUBLISHED the
 * partial artifacts of a run that had just failed. A run that could not render one of its statements left
 * a complete-looking plain-text object and a complete-looking markup object under its own run prefix,
 * with no index and no manifest naming them, and no pending multipart upload to show they had been
 * abandoned -- so nothing reclaimed them and nothing said they were partial. With publication explicit,
 * an unwind reaches an abort: nothing is stored, which is the disposition the reference has, because
 * {@code app/jcl/CREASTMT.JCL} deletes both statement outputs at L66 before allocating them fresh at L79
 * and a run that ends before the allocation therefore leaves neither behind.</p>
 *
 * <p>Alternatives Considered: keeping publication in {@code close()} and deleting the two objects from
 * the failure path instead. Rejected because it publishes and then unpublishes -- a reader listing the
 * run prefix between the two moments sees exactly the misleading artifact the abort avoids -- and
 * because it needs a delete grant this task's role does not hold and should not: the write path is
 * additive by design so that nothing a previous run stored can be removed by a later one's failure.</p>
 *
 * <p>Trade-offs: the residue window narrows rather than closing outright. Both artifacts are published by
 * two separate storage calls, so a store that accepts the first completion and refuses the second leaves
 * the plain-text object stored while the markup object is aborted. That window is one refused call wide
 * where it used to be every failed run wide, no object store offers a multi-object commit that would
 * close it, and the manifest scheme means the residue is never addressable -- the run is disclosed only
 * by the manifest write that a refused completion never reaches.</p>
 */
public final class S3StatementSink implements StatementService.StatementSink, AutoCloseable {

    /**
     * Object name of the plain-text artifact, being {@code statements.txt}.
     *
     * <p>⚠️ Refactoring Rationale: this constant used to DECLARE the name and now ALIASES the
     * declaration on {@link StatementService}, because the read side has to name the same object the
     * write side writes and a review found the two disagreeing -- the statement response published a
     * per-card location while this writer wrote a run-wide object, so every published location resolved
     * to nothing. Aliasing keeps every existing reference to this constant compiling while leaving one
     * place where the value can be changed. The direction is forced: this class already depends on
     * {@code StatementService} for the sink seam it implements, so the declaration can only live there
     * without creating a package cycle.
     */
    public static final String PLAIN_TEXT_OBJECT = StatementService.PLAIN_TEXT_OBJECT;

    /** Object name of the markup artifact, aliasing the declaration on {@link StatementService}. */
    public static final String HTML_OBJECT = StatementService.HTML_OBJECT;

    /** The writer for the eighty-character plain-text artifact. */
    private final S3ArtifactWriter plainText;

    /** The writer for the hundred-character markup artifact. */
    private final S3ArtifactWriter markup;

    /**
     * Creates a sink over two artifact writers.
     *
     * <p>Assumptions: the writers are supplied rather than constructed here, so that the object keys and
     * the client are decided by the task that owns the run and this class holds only the routing rule
     * between the two record streams. That is also what lets a test drive the routing without an object
     * store.</p>
     *
     * @param plainText the writer for the plain-text artifact; must not be {@code null}
     * @param markup the writer for the markup artifact; must not be {@code null}
     * @throws NullPointerException if either writer is {@code null}
     */
    public S3StatementSink(S3ArtifactWriter plainText, S3ArtifactWriter markup) {
        this.plainText = Objects.requireNonNull(plainText, "plainText must not be null");
        this.markup = Objects.requireNonNull(markup, "markup must not be null");
    }

    /**
     * Asserts the destination the run will publish to.
     *
     * <p>Assumptions: nothing is deleted and nothing is truncated, for the reason recorded on the class:
     * this run writes to keys of its own that hold no previous object, so there is nothing at the
     * destination to clear and the previous run stays readable throughout. This method exists on the seam
     * because the reference has a discrete deletion step, and it is honoured here by writing somewhere
     * else rather than by emptying somewhere.</p>
     */
    @Override
    public void replaceArtifacts() {
        // WHY : Assumptions: the body is deliberately empty rather than unwritten, and the emptiness is
        //       the decision. The reference's deletion step exists because its writes append to a dataset
        //       that would otherwise still hold the previous night's records; this run's keys are new, so
        //       there are no previous records at them to clear. Deleting anything reachable from here
        //       would mean deleting the run a reader is currently resolving against, which is the state
        //       the manifest scheme exists to keep intact until a whole new run has landed.
    }

    /**
     * Appends one record to the plain-text artifact.
     *
     * @param record the encoded eighty-character record; must not be {@code null}
     * @throws IllegalStateException if the record cannot be written, which stops the run as the
     *     reference's abend does
     * @throws NullPointerException if {@code record} is {@code null}
     */
    @Override
    public void writeStatementRecord(byte[] record) {
        try {
            plainText.write(record);
        } catch (IOException refusal) {
            throw new IllegalStateException(
                    "the plain-text statement artifact refused a record", refusal);
        }
    }

    /**
     * Appends one record to the markup artifact.
     *
     * @param record the encoded hundred-character record; must not be {@code null}
     * @throws IllegalStateException if the record cannot be written, which stops the run as the
     *     reference's abend does
     * @throws NullPointerException if {@code record} is {@code null}
     */
    @Override
    public void writeMarkupRecord(byte[] record) {
        try {
            markup.write(record);
        } catch (IOException refusal) {
            throw new IllegalStateException("the markup statement artifact refused a record", refusal);
        }
    }

    /**
     * Publishes both artifacts, which a caller reaches only when the whole run was written.
     *
     * <p>Purpose: this is the ONE call that makes a run's two artifacts readable, and it is reached from
     * the success path only. Everything about the failure disposition recorded on the class follows from
     * that: {@link #close()} cannot publish, so an unwind cannot.</p>
     *
     * <p>Assumptions: the plain-text artifact is completed FIRST and a refusal there stops this method
     * without attempting the markup artifact, whose upload the subsequent {@code close()} then aborts.
     * Attempting the second completion after the first was refused would store an artifact for a run that
     * is already known to have failed, which is the residue this shape exists to avoid; the abort needs
     * no suppression handling of its own because {@code S3ArtifactWriter#close()} does not throw.</p>
     *
     * @throws IOException if either artifact could not be published, in which case the manifest write
     *     that would have disclosed this run is never reached and the previous run stays current
     * @throws IllegalStateException if this sink has already been completed or already been closed,
     *     because either means a caller has lost track of which run it is publishing
     */
    public void complete() throws IOException {
        plainText.complete();
        markup.complete();
    }

    /**
     * Discards artifacts that were never completed, publishing nothing.
     *
     * <p>⚠️ Refactoring Rationale: this method used to complete both writers, and completing them is
     * what published them; the reasoning for moving that to {@link #complete()} is recorded in full on
     * the class. What is left here is the resource-clause half: it runs on every path, including the
     * unwind of a failed run, and on that path it must leave the run's two keys holding nothing.</p>
     *
     * <p>Assumptions: this declares no checked exception, matching {@code S3ReportSink} and the writer
     * beneath it, so a failed generation's own exception reaches the caller unaccompanied by a
     * storage-housekeeping failure it did not cause. The reasoning is recorded on
     * {@code S3ArtifactWriter#close()}.</p>
     *
     * <p>Assumptions: both writers are closed unconditionally and in sequence, so a close after a
     * completion is a no-op on each and a close after a partial completion aborts only the writer that
     * was never completed. That idempotence is required rather than incidental: every caller holds this
     * sink in a try-with-resources, so this method always runs after {@link #complete()} on the success
     * path and must neither fail there nor publish anything a second time.</p>
     */
    @Override
    public void close() {
        plainText.close();
        markup.close();
    }
}
