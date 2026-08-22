package com.carddemo.reporting.task;

import com.carddemo.reporting.service.ReportArtifactLocator;
import com.carddemo.reporting.service.StatementService;
import com.carddemo.reporting.service.TransactionReportService;
import com.carddemo.reporting.sink.S3ArtifactWriter;
import com.carddemo.reporting.sink.S3ReportSink;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     * Configuration property carrying the key prefix report artifacts sit under, aliasing the
     * declaration on {@link ReportArtifactLocator}.
     */
    public static final String REPORT_PREFIX_PROPERTY = ReportArtifactLocator.REPORT_PREFIX_PROPERTY;

    /**
     * The object name, appended after the date partition.
     *
     * <p>⚠️ Refactoring Rationale: this constant and the property above now ALIAS
     * {@link ReportArtifactLocator}, and the four partition segments that used to sit here are gone. The
     * key convention moved to the service layer because the read side needs the identical key and could
     * not import this package without a cycle -- a review found that a submitted report's artifact was
     * reachable by nothing at all. Aliasing keeps every existing reference to these two names compiling
     * while leaving one place where a value can change.</p>
     */
    public static final String REPORT_OBJECT = ReportArtifactLocator.REPORT_OBJECT;

    /**
     * The type token the scheduled nightly run publishes under.
     *
     * <p>Assumptions: the nightly run is not one of the three types the request screen offers -- it is the
     * scheduled daily run over one business date, the reference's {@code app/jcl/TRANREPT.jcl} output -- so
     * it carries its own token rather than borrowing one of theirs. Borrowing "monthly" would make a
     * one-day report indistinguishable in the key from a calendar-month one.</p>
     */
    public static final String DAILY_REPORT_TYPE = ReportArtifactLocator.DAILY_REPORT_TYPE;

    /**
     * Bounded context owning the {@code TRANREPT} generation family, and its first key segment.
     *
     * <p>Assumptions: {@code reporting}, matching the {@code domain} that
     * {@code infra/modules/s3-datasets} declares for the {@code tranrept} family. The two must agree
     * character for character: the module's lifecycle rule filters on the composed prefix, so a
     * mismatch would write generations under a prefix carrying no retention rule at all.</p>
     */
    public static final String TRANREPT_DOMAIN = "reporting";

    /** Dataset segment of the {@code TRANREPT} generation family, matching that module's map key. */
    public static final String TRANREPT_DATASET = "tranrept";

    /**
     * Object name of the report inside its generation prefix.
     *
     * <p>Assumptions: named for the baseline base {@code AWS.M2.CARDDEMO.TRANREPT} rather than reusing
     * {@value #REPORT_OBJECT}, because the two keys hold the same bytes for different readers -- one is
     * addressed by request range, the other is the generation an operator restores from -- and one name
     * on both would leave a bucket listing unable to say which it was looking at.</p>
     */
    public static final String TRANREPT_GENERATION_OBJECT = "tranrept.txt";

    /** The journal this publisher reports the generation it wrote through. */
    private static final Logger LOG = LoggerFactory.getLogger(ReportArtifactPublisher.class);

    /** The report generator this publisher drives. */
    private final TransactionReportService reports;

    /** The object-store client the artifact is published through. */
    private final S3Client s3;

    /** The destination bucket. */
    private final String bucket;

    /** The key convention this publisher writes under, shared with the read side. */
    private final ReportArtifactLocator locator;

    /**
     * Creates the publisher.
     *
     * @param reports the report generator; must not be {@code null}
     * @param s3 the object-store client; must not be {@code null}
     * @param bucket the destination bucket, supplied by
     *     {@value StatementService#OUTPUT_BUCKET_PROPERTY}; must not be {@code null}
     * @param locator the key convention, shared with the request surface that serves the artifact; must
     *     not be {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public ReportArtifactPublisher(
            TransactionReportService reports,
            S3Client s3,
            @Value("${" + StatementService.OUTPUT_BUCKET_PROPERTY + "}") String bucket,
            ReportArtifactLocator locator) {
        this.reports = Objects.requireNonNull(reports, "reports must not be null");
        this.s3 = Objects.requireNonNull(s3, "s3 must not be null");
        this.bucket = Objects.requireNonNull(bucket, "bucket must not be null");
        this.locator = Objects.requireNonNull(locator, "locator must not be null");
    }

    /**
     * Generates the scheduled daily report for one business date and publishes it.
     *
     * <p>Assumptions: the range is the single business date at both bounds, and the run date is that date
     * too, which is what the nightly job does -- {@code app/jcl/TRANREPT.jcl} reports one day's postings.
     * Naming that here rather than at the call site keeps the daily type token and the range that goes with
     * it in one place, so a caller cannot pair the daily token with a period.</p>
     *
     * <p>Assumptions: the publication has ONE reader-visible commit point. The report is written to two
     * keys, but only the request-scoped one is resolved by anything that serves a caller, and that one is
     * completed last -- so a failure at any point in this method leaves every reader on the previous
     * report rather than on a partial, an empty or a half-published one. The reasoning, and the bounded
     * residual a failure between the two completions leaves behind, are recorded at the completion
     * calls.</p>
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

        // WHY : Assumptions: the request-scoped key is composed by the LOCATOR here as well, so the
        //       nightly run and an on-demand run address the same artifact through one convention and
        //       the status surface that serves it reads the key its writer wrote. A private copy in this
        //       class was the alternative and is what this call replaces: two spellings of one contract,
        //       either of which could be corrected without the other.
        String requestScopedKey = this.locator.key(
                DAILY_REPORT_TYPE, businessDate, businessDate, businessDate);
        int generation = GenerationKeys.nextGeneration(
                this.s3, this.bucket, TRANREPT_DOMAIN, TRANREPT_DATASET, businessDate);
        String generationKey = GenerationKeys.generationKey(
                TRANREPT_DOMAIN, TRANREPT_DATASET, businessDate, generation,
                TRANREPT_GENERATION_OBJECT);

        // WHY : Refactoring Rationale: the nightly report is published to TWO keys and used to be
        //       published to one. The second is the generation coordinate the baseline's
        //       AWS.M2.CARDDEMO.TRANREPT base resolves to -- defined at app/jcl/DEFGDGB.jcl:37-39 with
        //       LIMIT(5) SCRATCH and written as TRANREPT(+1) at app/jcl/TRANREPT.jcl:80 -- and without
        //       it that family had no production writer at all, so the prefix and lifecycle rule
        //       infra/modules/s3-datasets provisions for it governed nothing.
        // WHY : Assumptions: both keys are written in ONE generation pass over a single sink, not by
        //       running the report twice. The reference produces one report per night from one sort, so
        //       a second pass could return different rows if a posting landed between them -- and it
        //       would double the read of the largest relation this context touches for bytes that must
        //       be identical.
        // WHY : Trade-offs: the same bytes are stored twice rather than one key redirecting to the
        //       other. S3 has no server-side alias, so the alternatives were a copy after the fact --
        //       which is a second full transfer and can fail after the first key is published, leaving
        //       exactly one of the two present -- or dropping one key. Neither is better: the
        //       request-scoped key is what the on-demand path and the runbooks address by range, and
        //       the generation key is what carries the retention contract and what the retention
        //       function prunes. A 133-byte-per-transaction text artifact is the cheapest thing in this
        //       bucket to hold twice.
        S3ArtifactWriter requestScoped = new S3ArtifactWriter(this.s3, this.bucket, requestScopedKey);
        S3ArtifactWriter generational = new S3ArtifactWriter(this.s3, this.bucket, generationKey);
        TransactionReportService.ReportGenerationSummary summary;
        try (S3ReportSink primary = new S3ReportSink(requestScoped);
                S3ReportSink secondary = new S3ReportSink(generational)) {
            summary = this.reports.generateReport(businessDate, businessDate, record -> {
                primary.write(record);
                secondary.write(record);
            });

            // WHY : Refactoring Rationale: both keys are published HERE, on the success path, where the
            //       try-with-resources used to publish them as it closed. Closing published whatever had
            //       been written, so a generation that failed part way replaced a rerun date's last good
            //       report with a truncated one, and a generation that failed before its first row
            //       replaced it with an empty object. Completion is now explicit and the resource clause
            //       aborts, so a failed rerun leaves the previous report readable.
            // WHY : ⚠️ Refactoring Rationale: the GENERATION copy completes first and the REQUEST-SCOPED
            //       copy completes LAST, where the two used to complete in the opposite order. Only the
            //       request-scoped key is reader-visible: the status surface publishes it, the collection
            //       route serves it and the runbooks address a report by its range, while nothing
            //       resolves a generation coordinate at a request edge. Completing it last therefore
            //       makes it the single moment at which any reader sees this run at all, so a failure
            //       anywhere before it leaves every reader on the previous report -- which is what a
            //       failed run should look like. The old order published the reader-visible copy first
            //       and could leave a failed run looking successful to a caller.
            // WHY : Trade-offs: the residual of a failure between the two completions is an ORPHAN
            //       generation object, and it is bounded in three ways. It cannot be partial or empty,
            //       because the writer publishes only on an explicit completion. It cannot overwrite
            //       anything, because nextGeneration allocates the first unused number for the date. And
            //       it cannot mislead, because both copies hold identical bytes, so an orphan is a
            //       complete report at a coordinate nothing resolves -- the next run allocates past it
            //       and the five-generation retention rule prunes it. That is why this publication needs
            //       no pointer object while the statement run does: a statement generation is three
            //       artifacts holding DIFFERENT content that must agree with each other, so a reader can
            //       be shown a mixture, whereas here there is only one report and the question is
            //       whether a second copy of it exists.
            secondary.complete();
            primary.complete();
        }

        LOG.info("event=reporting.report.generation-published family={}/{} generation={} key={}"
                        + " versionId={}",
                TRANREPT_DOMAIN, TRANREPT_DATASET, generation, generationKey,
                generational.publishedVersionId());

        return new PublishedArtifact(
                summary, this.bucket, requestScopedKey, requestScoped.publishedVersionId());
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

        // WHY : Assumptions: the key is composed by the locator and not here, so the key this run WRITES
        //       and the key the status surface READS can never diverge. The locator also performs the
        //       closed-domain check on the type, which is why no separate check remains at this point.
        String key = locator.key(reportType, rangeStart, rangeEnd, runDate);

        // WHY : Assumptions: the writer is held in its own local so its version can be read after the
        //       block. The version exists only once the artifact is published, and publication is the
        //       completion call inside the block, so the local is what makes the value reachable at all;
        //       constructing the writer in the resource clause and reaching back for it afterwards is not
        //       possible, the resource variable being out of scope.
        S3ArtifactWriter writer = new S3ArtifactWriter(s3, bucket, key);
        TransactionReportService.ReportGenerationSummary summary;
        try (S3ReportSink sink = new S3ReportSink(writer)) {
            summary = reports.generateReport(rangeStart, rangeEnd, sink);

            // WHY : Refactoring Rationale: publication is this call and no longer the resource clause's
            //       close, for the reason recorded on the daily path above -- a generation that failed
            //       part way used to publish its partial output over the last good report for the same
            //       range.
            sink.complete();
        }

        return new PublishedArtifact(summary, bucket, key, writer.publishedVersionId());
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
