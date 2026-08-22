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
     * Publishes both artifacts, attempting the second even when the first fails.
     *
     * <p>Assumptions: both writers are closed on every path and the second failure is suppressed onto the
     * first. Returning after the first failure would leave the markup artifact's upload in flight with no
     * abort, which charges storage for parts that nothing will ever complete.</p>
     *
     * @throws IOException if either artifact could not be published
     */
    @Override
    public void close() throws IOException {
        // WHY : Assumptions: publication is an explicit `complete()` and close() only releases, which
        //       is why this method does both rather than relying on the close alone. S3ArtifactWriter
        //       publishes on complete() and ABORTS an upload that was never completed, so a pass that
        //       failed part way leaves the previous object standing instead of overwriting it with a
        //       truncated one. Closing without completing would therefore publish nothing at all.
        // WHY : Assumptions: both writers are completed, the second failure is suppressed onto the
        //       first, and both are closed in a finally on every path. Returning after the first
        //       failure would leave the other artifact's upload in flight with no abort, which charges
        //       storage for parts that nothing will ever complete.
        try {
            try {
                plainText.complete();
            } catch (IOException plainTextFailure) {
                try {
                    markup.complete();
                } catch (IOException markupFailure) {
                    plainTextFailure.addSuppressed(markupFailure);
                }
                throw plainTextFailure;
            }
            markup.complete();
        } finally {
            plainText.close();
            markup.close();
        }
    }
}
