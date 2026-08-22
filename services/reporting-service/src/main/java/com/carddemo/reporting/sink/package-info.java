/**
 * The object-store adapters that turn a generator's record stream into a stored artifact.
 *
 * <h2>What this package holds</h2>
 *
 * <p>Purpose: both reporting generators write through a seam rather than to a file. The statement
 * generator accepts a sink taking one plain-text band and one markup band, and the transaction-report
 * generator accepts a sink taking one 133-column record; neither knows where its records come to rest.
 * This package holds the three classes that give those seams a destination, and nothing else:</p>
 *
 * <ul>
 *   <li>{@code S3ArtifactWriter} -- the one place a record stream becomes an object. It buffers a fixed
 *       part, uploads a part at a time, and publishes nothing until it is told to complete -- a
 *       close without that instruction abandons the upload instead.</li>
 *   <li>{@code S3StatementSink} -- the statement seam's adapter, routing the two bands to the two
 *       datasets the reference declares for a whole run.</li>
 *   <li>{@code S3ReportSink} -- the report seam's adapter, a single writer behind a single method.</li>
 * </ul>
 *
 * <p>Assumptions: the roster is closed at three. A fourth artifact shape would arrive with a fourth
 * generator, and the writer is already generic over the object key, so a new artifact needs a new sink
 * rather than a new writer.</p>
 *
 * <h2>Why the destination is a package and not a service concern</h2>
 *
 * <p>Purpose: the reason this seam exists at all is that the generators run inside a database read and
 * the artifact write is network I/O against a different system. Holding a database transaction open
 * across an object-store round trip is the defect the migration's own review raised against the earlier
 * shape of this module, and the fix was to make the two halves separately observable: the generator owns
 * the records and the bounded reads that produce them, and this package owns the bytes and the failure
 * modes of storing them. A reader auditing "what is open while what is in flight" can now answer the
 * question by looking at two files rather than at one method that does both.</p>
 *
 * <p>Alternatives Considered: letting each generator hold an {@code S3Client} directly and write as it
 * goes. Rejected for two independent reasons. First, it would put an AWS SDK type inside a service class,
 * which the module's ArchUnit layering rule forbids for the whole {@code com.carddemo} tree, and the rule
 * is not decorative -- it is what keeps a generator unit-testable with an in-memory sink and no
 * credentials. Second, an incremental write publishes a partially-written artifact under its final key,
 * so a failed run would leave a truncated report that reads as a complete one; there is no marker in a
 * 133-column fixed-width dataset that says "this stopped early".</p>
 *
 * <p>Alternatives Considered: writing to a local temporary file and uploading it whole at the end.
 * Rejected because it converts an unbounded record count into an unbounded disk requirement on a Fargate
 * task whose ephemeral storage is fixed, and it moves the failure from "the upload failed" to "the task
 * filled its disk", which is both harder to attribute and harder to retry. The multipart form the writer
 * uses is bounded in memory by one part and bounded on disk by nothing at all.</p>
 *
 * <h2>Atomicity, and what it deliberately does not reproduce</h2>
 *
 * <p>Trade-offs: the reference deletes the previous statement datasets in a preliminary step and then
 * writes fresh ones, so a failed run leaves NO artifact. These sinks instead leave the PREVIOUS artifact
 * in place until a run completes, because publication happens only at completion. That is a deliberate
 * divergence and it is the safer of the two: an operator reading yesterday's statements is better served
 * than one reading none, the bucket is versioned so the replaced object stays recoverable, and a
 * half-written object never becomes visible under either scheme. The reasoning is recorded again at the
 * point of use, on {@code S3ArtifactWriter}, so a reader who reaches the code without reading this
 * charter still finds it.</p>
 *
 * <p>Assumptions: a run that produces no records still publishes an artifact -- an empty one. The
 * reference's generators open their output datasets unconditionally, so an empty dataset is the
 * baseline's own outcome for an empty input, and failing instead would turn a quiet night into an
 * operator page.</p>
 *
 * <h2>Failure discipline</h2>
 *
 * <p>Assumptions: these classes raise {@code IOException} and never a service-level abend type. The
 * conversion from a storage failure into the reference's abend vocabulary belongs to the generator that
 * owns the abend code and culprit, and doing it here would put two different classes in the business of
 * naming the same failure. Assumptions: where a sink holds more than one writer, closing them in
 * sequence aborts every one of them, because {@code close} on a writer is specified not to throw --
 * an abort failure is logged and swallowed there, since it leaves only uploaded parts for the
 * bucket's incomplete-upload lifecycle rule to expire and there is nothing a caller can do about it.
 * COMPLETION is the opposite and deliberately so: a sink completes its writers in sequence and a
 * refused completion propagates immediately, so the second artifact is never published beside a first
 * that failed. Trade-offs: that leaves a window one refused call wide in which the first artifact is
 * stored and the second is not, and it stays unaddressable because the manifest write sits after both
 * -- which is a strictly smaller exposure than publishing a matched pair of partial artifacts.</p>
 *
 * <p>Assumptions: no exception message composed here carries an object key, a bucket name, an account
 * identifier or a card number. An object key is derived from an opaque token precisely so that a storage
 * failure can be reported without disclosing what the artifact is about, and re-inserting the key into a
 * message would undo that at the one moment the message is most likely to be forwarded.</p>
 *
 * <h2>Why this charter exists</h2>
 *
 * <p>Assumptions: the project Explainability rule requires a docstring on every module entry point, and
 * in Java a package's entry point is its package declaration, which only {@code package-info.java} can
 * carry. Two Checkstyle modules enforce that independently: {@code JavadocPackage} requires this file to
 * exist in any directory holding an audited source file, and {@code MissingJavadocPackage} requires it
 * to carry Javadoc, so a bare package statement satisfies the first and fails the second. The written
 * convention every block here follows is {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and
 * never restated. No parameter, return or exception at-clause appears, because a package declaration
 * accepts no argument, yields no value and raises nothing.</p>
 */
package com.carddemo.reporting.sink;
