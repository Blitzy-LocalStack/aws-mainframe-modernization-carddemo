package com.carddemo.reporting.task;

import com.carddemo.reporting.service.StatementService;
import com.carddemo.reporting.service.TransactionReportService;
import com.carddemo.reporting.sink.S3ArtifactWriter;
import com.carddemo.reporting.sink.S3ReportSink;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
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
 * <p>Assumptions: the object key names EVERY input that decides the artifact's content -- the run date, the
 * report type and both range bounds -- as Hive-style partitions under the dataset convention the plan fixes,
 * so the full shape is {@code <domain>/<dataset>/dt=YYYY-MM-DD/type=<type>/from=YYYY-MM-DD/to=YYYY-MM-DD/}.
 * Two runs collide on a key exactly when they asked for the same report over the same range for the same
 * date, which is when they produce the same bytes and when replacing one with the other is what an operator
 * wants.</p>
 *
 * <p>Refactoring Rationale: the type and the two bounds are IN the key, where an earlier revision keyed on
 * the run date alone and argued that the date is "the discriminator an operator has". That argument does not
 * survive the two call sites: the nightly task publishes with the business date as all three of its
 * arguments and the on-demand task publishes with its range's END date as the run date, so an on-demand
 * report for any period ending on a nightly date wrote to the nightly artifact's exact key and silently
 * replaced it. The nightly report -- the reference's TRANREPT output -- would then be a period report an
 * operator requested, under the key an operator reads the night's figures from, with nothing in the key or
 * the object to say so. Naming the full input set removes the collision rather than documenting it.</p>
 *
 * <p>Alternatives Considered: putting the run or execution identifier in the key instead, which also
 * separates the two callers. Rejected because it separates far more than that: every rerun would get its own
 * key, so the bucket would ACCUMULATE artifacts rather than version them, and the five-noncurrent-version
 * lifecycle that stands in for the reference's {@code LIMIT(5) SCRATCH} retention would never apply to
 * anything. The run identity belongs in the LOCATOR this class returns and in the operational record, which
 * is where it now is, and not in the key. Keeping the key a function of the request means a rerun replaces
 * its predecessor and the predecessor stays recoverable as a noncurrent version, which is the generation
 * model the plan fixes.</p>
 *
 * <p>Assumptions: the bucket is versioned with a lifecycle retaining five noncurrent versions, which is the
 * direct analogue of the {@code LIMIT(5) SCRATCH} retention the reference's generation groups declare, so a
 * replaced artifact stays recoverable without the key having to carry a generation number.</p>
 *
 * <p>Assumptions: the report type reaching a key is checked against a CLOSED domain rather than sanitised,
 * because the set of legitimate values is known exactly -- the three the request screen offers plus the one
 * the schedule uses -- and an object key is not a place to discover that an unknown value was almost safe. A
 * value outside the domain is refused before any generation work starts.</p>
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

    /** The report-type partition segment. */
    private static final String TYPE_PARTITION = "type=";

    /** The range-start partition segment. */
    private static final String RANGE_START_PARTITION = "from=";

    /** The range-end partition segment. */
    private static final String RANGE_END_PARTITION = "to=";

    /**
     * The type token the scheduled nightly run publishes under.
     *
     * <p>Assumptions: the nightly run is not one of the three types the request screen offers -- it is the
     * scheduled daily run over one business date, the reference's {@code app/jcl/TRANREPT.jcl} output -- so
     * it carries its own token rather than borrowing one of theirs. Borrowing "monthly" would make a
     * one-day report indistinguishable in the key from a calendar-month one.</p>
     */
    public static final String DAILY_REPORT_TYPE = "daily";

    /**
     * The type tokens an on-demand run may publish under.
     *
     * <p>Assumptions: the three are the lower-cased forms of the report names
     * {@code com.carddemo.reporting.service.ReportExecutionService} resolves, which are what the state
     * machine forwards to the on-demand task as its report-type argument. They are compared
     * case-insensitively because the argument travels through a command line an operator can type.</p>
     */
    private static final List<String> ON_DEMAND_REPORT_TYPES = List.of("monthly", "yearly", "custom");

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
     * Generates the scheduled daily report for one business date and publishes it.
     *
     * <p>Assumptions: the range is the single business date at both bounds, and the run date is that date
     * too, which is what the nightly job does -- {@code app/jcl/TRANREPT.jcl} reports one day's postings.
     * Naming that here rather than at the call site keeps the daily type token and the range that goes with
     * it in one place, so a caller cannot pair the daily token with a period.</p>
     *
     * @param businessDate the business date to report on and key under; must not be {@code null}
     * @return the locator of the published artifact together with the generator's summary; never
     *     {@code null}
     * @throws IOException if the artifact cannot be published
     * @throws NullPointerException if {@code businessDate} is {@code null}
     * @throws com.carddemo.common.error.ClientInputException if the range is wider than the generator is
     *     willing to serve
     * @throws IllegalStateException if a transaction in the range does not resolve to exactly one of each
     *     dimension, which is the target's equivalent of the reference abending on an unresolved lookup
     */
    public PublishedArtifact publishDaily(LocalDate businessDate) throws IOException {
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        return publish(DAILY_REPORT_TYPE, businessDate, businessDate, businessDate);
    }

    /**
     * Generates the report for one requested type and range and publishes it.
     *
     * @param reportType the requested report type, one of {@code monthly}, {@code yearly} or {@code custom}
     *     in any case, or {@value #DAILY_REPORT_TYPE} for the scheduled run; must not be {@code null}
     * @param rangeStart the first business date to cover, inclusive; must not be {@code null}
     * @param rangeEnd the last business date to cover, inclusive; must not be {@code null}
     * @param runDate the date partition the artifact is keyed under, which the nightly run sets to the
     *     business date and the on-demand run sets to the date it reports on; must not be {@code null}
     * @return the locator of the published artifact together with the generator's summary; never
     *     {@code null}
     * @throws IOException if the artifact cannot be published
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if {@code reportType} is outside the closed domain, which is refused
     *     before any generation work starts so that no caller-supplied text reaches an object key
     * @throws com.carddemo.common.error.ClientInputException if the range is absent, inverted, or wider
     *     than the generator is willing to serve
     * @throws IllegalStateException if a transaction in the range does not resolve to exactly one of each
     *     dimension, which is the target's equivalent of the reference abending on an unresolved lookup
     */
    public PublishedArtifact publish(
            String reportType, LocalDate rangeStart, LocalDate rangeEnd, LocalDate runDate)
            throws IOException {

        Objects.requireNonNull(reportType, "reportType must not be null");
        Objects.requireNonNull(rangeStart, "rangeStart must not be null");
        Objects.requireNonNull(rangeEnd, "rangeEnd must not be null");
        Objects.requireNonNull(runDate, "runDate must not be null");

        String typeToken = requireKnownReportType(reportType);
        String key = prefix
                + DATE_PARTITION + runDate + "/"
                + TYPE_PARTITION + typeToken + "/"
                + RANGE_START_PARTITION + rangeStart + "/"
                + RANGE_END_PARTITION + rangeEnd + "/"
                + REPORT_OBJECT;

        // WHY : Assumptions: the writer is held in its own local so its version can be read AFTER the
        //       try-with-resources has closed it. The version exists only once the artifact is published,
        //       and publication happens in close(), so reading it inside the block would always answer
        //       null. Constructing the writer in the resource clause and reaching back for it afterwards
        //       is not possible -- the resource variable is out of scope -- which is why it is declared
        //       first and the sink wraps it.
        S3ArtifactWriter writer = new S3ArtifactWriter(s3, bucket, key);
        TransactionReportService.ReportGenerationSummary summary;
        try (S3ReportSink sink = new S3ReportSink(writer)) {
            summary = reports.generateReport(rangeStart, rangeEnd, sink);
        }

        return new PublishedArtifact(summary, bucket, key, writer.publishedVersionId());
    }

    /**
     * Canonicalises a report type against the closed domain, refusing anything outside it.
     *
     * <p>Assumptions: the returned token is lower-cased, so one type produces one key however an operator
     * capitalised the argument. Without that, {@code MONTHLY} and {@code monthly} would be two artifacts
     * for one report and the second would not replace the first.</p>
     *
     * @param reportType the requested type as the caller stated it; must not be {@code null}
     * @return the canonical lower-case token; never {@code null}
     * @throws IllegalArgumentException if the type is not in the closed domain
     */
    private static String requireKnownReportType(String reportType) {
        String token = reportType.trim().toLowerCase(Locale.ROOT);
        if (DAILY_REPORT_TYPE.equals(token) || ON_DEMAND_REPORT_TYPES.contains(token)) {
            return token;
        }

        // WHY : Assumptions: the refusal does NOT quote the offending value. It reaches an object key on
        //       the accepting path and an exception message on this one, and a message that quotes an
        //       unvalidated argument is the same exposure in a different destination -- the shared advice
        //       records an internal failure's message. The domain is stated instead, which is what a
        //       caller needs in order to correct the argument.
        throw new IllegalArgumentException(
                "the report type is not one this service publishes: the accepted tokens are "
                        + DAILY_REPORT_TYPE + " and " + ON_DEMAND_REPORT_TYPES);
    }

    /**
     * Where a published artifact is, and what the run that produced it wrote.
     *
     * <p>Purpose: a caller needs the EXACT location of what it just published, and before this record
     * existed the only return was the generator's line and band counts -- so the one fact an operator needs
     * to fetch the artifact, or to record which object a run produced, was known inside this class and
     * discarded on the way out. Both tasks logged a summary that named no object at all.</p>
     *
     * @param summary the generator's own account of what it wrote; never {@code null}
     * @param bucket the bucket the artifact is in; never {@code null}
     * @param key the object key the artifact is at; never {@code null}
     * @param versionId the stored object's version, or {@code null} when the bucket carries no versioning
     */
    public record PublishedArtifact(
            TransactionReportService.ReportGenerationSummary summary,
            String bucket,
            String key,
            String versionId) {

        /**
         * Refuses a locator missing any part a caller needs to fetch the artifact.
         *
         * @param summary the generator's account, required because the record exists to carry both
         * @param bucket the bucket, required because a key without one names nothing
         * @param key the object key, required for the same reason
         * @param versionId the stored version, optional because an unversioned bucket returns none
         * @throws NullPointerException if {@code summary}, {@code bucket} or {@code key} is {@code null}
         */
        public PublishedArtifact {
            Objects.requireNonNull(summary, "summary must not be null");
            Objects.requireNonNull(bucket, "bucket must not be null");
            Objects.requireNonNull(key, "key must not be null");
        }

        /**
         * Renders the locator as a single object URI, with the version when there is one.
         *
         * <p>Assumptions: the form is {@code s3://bucket/key} with {@code ?versionId=} appended when a
         * version exists, which is the shape the runbooks use in their fetch commands. Rendering it here
         * rather than at each log line keeps one shape rather than one per caller.</p>
         *
         * @return the object URI; never {@code null}
         */
        public String locator() {
            String base = "s3://" + bucket + "/" + key;
            return versionId == null ? base : base + "?versionId=" + versionId;
        }
    }
}
