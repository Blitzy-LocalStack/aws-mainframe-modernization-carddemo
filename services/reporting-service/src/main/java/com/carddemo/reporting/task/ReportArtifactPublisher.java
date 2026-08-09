package com.carddemo.reporting.task;

import com.carddemo.reporting.service.StatementService;
import com.carddemo.reporting.service.TransactionReportService;
import com.carddemo.reporting.sink.S3ArtifactWriter;
import com.carddemo.reporting.sink.S3ReportSink;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Runs the transaction-report generator and stores its artifact under the generation key.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: two tasks publish the same artifact from two different ranges -- the nightly run over one
 * business date and the on-demand run over a requested period. The generator call, the object key rule and
 * the sink lifecycle are identical for both, and stating them twice would let two copies of the key rule
 * disagree about where a report lives, which is the kind of disagreement a reader discovers by finding no
 * artifact rather than by reading two files.</p>
 *
 * <p>Assumptions: the object key carries the run's DATE and no other discriminator, following the dataset
 * convention the plan fixes -- {@code <domain>/<dataset>/dt=YYYY-MM-DD/} -- so a rerun of a date replaces
 * that date's artifact rather than accumulating a second copy. The bucket is versioned with a lifecycle
 * retaining five noncurrent versions, which is the direct analogue of the {@code LIMIT(5) SCRATCH}
 * retention the reference's generation groups declare, so the replaced artifact stays recoverable without
 * the key having to carry a generation number.</p>
 *
 * <p>Alternatives Considered: keying on the range's two bounds so that an on-demand run over a period never
 * collides with a nightly run. Rejected because it would put two different key shapes in one prefix, and a
 * reader looking for "the report for the 18th" would have to know which task produced it. The run date is
 * the discriminator an operator has, and an on-demand run over a period is keyed by the date it was
 * produced for, which is the date it reports on.</p>
 *
 * <p>Assumptions: the sink is closed inside a try-with-resources, so the artifact is published only once
 * the generator has finished writing and a failed run leaves the previous artifact in place. The reasoning
 * for preferring replacement-on-completion over the reference's explicit delete-then-write is recorded on
 * {@link S3ArtifactWriter}.</p>
 */
@Component
public class ReportArtifactPublisher {

    /**
     * Configuration property carrying the key prefix report artifacts sit under.
     *
     * <p>Assumptions: named here rather than on the service because the service publishes no report
     * artifact -- it writes to a seam -- and the prefix is a property of where a stored artifact goes.</p>
     */
    public static final String REPORT_PREFIX_PROPERTY = "carddemo.reporting.s3.report-prefix";

    /** The object name, appended after the date partition. */
    public static final String REPORT_OBJECT = "transaction-detail.txt";

    /** The date-partition segment the dataset convention fixes. */
    private static final String DATE_PARTITION = "dt=";

    /** The report generator this publisher drives. */
    private final TransactionReportService reports;

    /** The object-store client the artifact is published through. */
    private final S3Client s3;

    /** The destination bucket. */
    private final String bucket;

    /** The key prefix report artifacts sit under. */
    private final String prefix;

    /**
     * Creates the publisher.
     *
     * @param reports the report generator; must not be {@code null}
     * @param s3 the object-store client; must not be {@code null}
     * @param bucket the destination bucket, supplied by
     *     {@value StatementService#OUTPUT_BUCKET_PROPERTY}; must not be {@code null}
     * @param prefix the report key prefix, supplied by {@value #REPORT_PREFIX_PROPERTY}; must not be
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public ReportArtifactPublisher(
            TransactionReportService reports,
            S3Client s3,
            @Value("${" + StatementService.OUTPUT_BUCKET_PROPERTY + "}") String bucket,
            @Value("${" + REPORT_PREFIX_PROPERTY + "}") String prefix) {
        this.reports = Objects.requireNonNull(reports, "reports must not be null");
        this.s3 = Objects.requireNonNull(s3, "s3 must not be null");
        this.bucket = Objects.requireNonNull(bucket, "bucket must not be null");
        this.prefix = Objects.requireNonNull(prefix, "prefix must not be null");
    }

    /**
     * Generates the report for one range and publishes it under the run date's key.
     *
     * @param rangeStart the first business date to cover, inclusive; must not be {@code null}
     * @param rangeEnd the last business date to cover, inclusive; must not be {@code null}
     * @param runDate the date the artifact is keyed under, which the nightly task sets to the business date
     *     and the on-demand task sets to the date it reports on; must not be {@code null}
     * @return the generator's own summary of what it wrote, never {@code null}
     * @throws IOException if the artifact cannot be published
     * @throws NullPointerException if any argument is {@code null}
     * @throws com.carddemo.common.error.ClientInputException if the range is absent, inverted, or wider
     *     than the generator is willing to serve
     * @throws IllegalStateException if a transaction in the range does not resolve to exactly one of each
     *     dimension, which is the target's equivalent of the reference abending on an unresolved lookup
     */
    public TransactionReportService.ReportGenerationSummary publish(
            LocalDate rangeStart, LocalDate rangeEnd, LocalDate runDate) throws IOException {

        Objects.requireNonNull(rangeStart, "rangeStart must not be null");
        Objects.requireNonNull(rangeEnd, "rangeEnd must not be null");
        Objects.requireNonNull(runDate, "runDate must not be null");

        String key = prefix + DATE_PARTITION + runDate + "/" + REPORT_OBJECT;
        try (S3ReportSink sink = new S3ReportSink(new S3ArtifactWriter(s3, bucket, key))) {
            return reports.generateReport(rangeStart, rangeEnd, sink);
        }
    }
}
