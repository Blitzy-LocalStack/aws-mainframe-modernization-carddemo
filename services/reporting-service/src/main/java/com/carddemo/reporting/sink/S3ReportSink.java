package com.carddemo.reporting.sink;

import com.carddemo.reporting.service.TransactionReportService;
import java.io.IOException;
import java.util.Objects;

/**
 * Publishes one transaction-report run's 133-column artifact to object storage.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: {@link TransactionReportService.ReportRecordSink} is the seam the report generator writes
 * through, and it had no implementation anywhere in the module -- so the orchestrated
 * {@code GenerateReports} state could run this image to completion and produce no artifact. This class is
 * the destination the seam was declared for.</p>
 *
 * <p>Assumptions: one artifact and not one per group. {@code app/cbl/CBTRN03C.cbl} declares a single
 * output definition and {@code app/jcl/TRANREPT.jcl} allocates a single dataset for it, so a run's output
 * is one file carrying every band -- headings, detail lines, page totals, group totals and the grand
 * total -- in emission order.</p>
 *
 * <p>Assumptions: the checked write failure the seam declares is propagated UNCHANGED rather than being
 * converted here. The generator catches it deliberately, converts it into the abend the reference performs
 * at L630 and counts the record only on success, so converting it at this boundary would bypass the one
 * place that knows how many records had already been written.</p>
 *
 * <p>Assumptions: this class is created per run rather than registered as a singleton bean, for the reason
 * recorded on the statement sink: it holds an open upload and its lifecycle is exactly one run's.</p>
 */
public final class S3ReportSink implements TransactionReportService.ReportRecordSink, AutoCloseable {

    /** The writer for the 133-column artifact. */
    private final S3ArtifactWriter artifact;

    /**
     * Creates a sink over one artifact writer.
     *
     * @param artifact the writer for the report artifact; must not be {@code null}
     * @throws NullPointerException if {@code artifact} is {@code null}
     */
    public S3ReportSink(S3ArtifactWriter artifact) {
        this.artifact = Objects.requireNonNull(artifact, "artifact must not be null");
    }

    /**
     * Appends one band to the report artifact.
     *
     * @param record the encoded 133-character band; must not be {@code null}
     * @throws IOException if the record cannot be appended, which the generator converts into the
     *     reference's own write-failure abend
     * @throws NullPointerException if {@code record} is {@code null}
     */
    @Override
    public void write(byte[] record) throws IOException {
        artifact.write(record);
    }

    /**
     * Publishes the artifact.
     *
     * @throws IOException if the artifact could not be published
     */
    @Override
    public void close() throws IOException {
        artifact.close();
    }
}
