package com.carddemo.reporting.task;

import com.carddemo.reporting.service.CategoryBalanceReportService;
import com.carddemo.reporting.service.StatementService;
import com.carddemo.reporting.sink.S3ArtifactWriter;
import java.io.IOException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Publishes the category-balance report to object storage under its own fixed key.
 *
 * <p>Purpose: hold the object-key decision for {@code app/jcl/PRTCATBL.jcl}'s output in one place, the
 * way {@link ReportArtifactPublisher} does for the transaction report, so the task that runs the report
 * stays a dispatcher and the key layout is reviewable on its own.</p>
 *
 * <p>Assumptions: the key is FIXED at {@code <prefix>}{@value #REPORT_OBJECT} and carries no date and
 * no generation partition. The reference deletes and recreates one dataset -- {@code DELDEF} removes
 * {@code AWS.M2.CARDDEMO.TCATBALF.REPT} at {@code app/jcl/PRTCATBL.jcl:21-25} and {@code STEP10R}
 * recreates it at {@code :59} -- and completing an upload over one key is that pair in one operation.
 * A rerun therefore replaces the previous run's report as a new object VERSION, which retains the
 * replaced copy where the reference retained nothing.</p>
 *
 * <p>Assumptions: this report has no generation family and is deliberately NOT written into one. No
 * {@code DEFINE GENERATIONDATAGROUP} statement anywhere in the baseline names
 * {@code TCATBALF.REPT} -- the four files that carry such statements are {@code app/jcl/DEFGDGB.jcl},
 * {@code app/jcl/DEFGDGD.jcl}, {@code app/jcl/DALYREJS.jcl} and {@code app/jcl/REPTFILE.jcl}, and none
 * of them defines it. Reading it as a generation would make the family count eleven where the
 * migration plan, {@code infra/modules/s3-datasets}'s own validation and two sibling documents all
 * publish ten. The generation this job DOES produce is {@code TCATBALF.BKUP}, its sort's input, and
 * that one is written by the backup state of {@code batch-service}.</p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 *
 * <p>Baseline lineage: citations are provenance only. Nothing under {@code app/} is read at run time
 * and nothing under it is altered by this migration.</p>
 */
@Component
public class CategoryBalanceArtifactPublisher {

    /** Property naming the prefix this report is published under. */
    public static final String CATEGORY_BALANCE_PREFIX_PROPERTY =
            "carddemo.reporting.s3.category-balance-prefix";

    /**
     * Object name of the report inside its prefix.
     *
     * <p>Assumptions: named for the reference dataset {@code AWS.M2.CARDDEMO.TCATBALF.REPT} in the
     * lower-case hyphenated form every other artifact this context writes uses, so a bucket listing
     * reads consistently rather than mixing two naming conventions.</p>
     */
    public static final String REPORT_OBJECT = "category-balance.txt";

    /** The generator that renders each line. */
    private final CategoryBalanceReportService reports;

    /** The client the artifact is written through. */
    private final S3Client s3;

    /** The bucket the artifact is written to. */
    private final String bucket;

    /** The key prefix the artifact is written under. */
    private final String prefix;

    /**
     * Builds the publisher over its generator, client, bucket and prefix.
     *
     * <p>Assumptions: the bucket property is the one {@link StatementService} declares, so this context
     * resolves ONE output bucket for every artifact it writes. A second property would let a
     * deployment configure the statements and the reports into different buckets, which no runbook or
     * IAM policy in this repository expresses.</p>
     *
     * @param reports the generator producing the report's lines; must not be {@code null}
     * @param s3 the client the artifact is written through; must not be {@code null}
     * @param bucket the output bucket, resolved from configuration; must not be {@code null}
     * @param prefix the key prefix, resolved from configuration and ending in a separator; must not be
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public CategoryBalanceArtifactPublisher(CategoryBalanceReportService reports, S3Client s3,
            @Value("${" + StatementService.OUTPUT_BUCKET_PROPERTY + "}") String bucket,
            @Value("${" + CATEGORY_BALANCE_PREFIX_PROPERTY + "}") String prefix) {

        this.reports = Objects.requireNonNull(reports, "reports must not be null");
        this.s3 = Objects.requireNonNull(s3, "s3 must not be null");
        this.bucket = Objects.requireNonNull(bucket, "bucket must not be null");
        this.prefix = Objects.requireNonNull(prefix, "prefix must not be null");
    }

    /**
     * Generates the category-balance report and publishes it.
     *
     * <p>This operation accepts no parameters, and the absence is the contract rather than an
     * omission: {@code app/jcl/PRTCATBL.jcl:44-45} feeds its sort the whole unloaded file with no
     * {@code INCLUDE} condition and no date parameter, so the report is a full print of current
     * balances and there is no range for a caller to supply.</p>
     *
     * @return the locator of the published artifact and what the run wrote, never {@code null}
     * @throws IOException if the artifact cannot be published
     * @throws java.io.UncheckedIOException if a line cannot be written to the artifact mid-pass,
     *     raised by the generator so the walk can propagate without changing its own signature
     */
    public PublishedCategoryBalanceReport publish() throws IOException {
        String key = this.prefix + REPORT_OBJECT;

        // WHY : Assumptions: the writer is held in its own local so its version can be read AFTER the
        //       try-with-resources closes it. A version exists only once the artifact is published and
        //       publication happens in close(), so reading it inside the block would always answer
        //       null -- the same shape ReportArtifactPublisher uses and for the same reason.
        S3ArtifactWriter writer = new S3ArtifactWriter(this.s3, this.bucket, key);
        CategoryBalanceReportService.CategoryBalanceReportSummary summary;
        try (writer) {
            summary = this.reports.generateReport(writer::write);
        }
        return new PublishedCategoryBalanceReport(
                summary, this.bucket, key, writer.publishedVersionId());
    }

    /**
     * Where the published category-balance report is, and what the run that produced it wrote.
     *
     * @param summary what the generator wrote, never {@code null}
     * @param bucket the bucket the artifact landed in, never {@code null}
     * @param key the object key the artifact landed at, never {@code null}
     * @param versionId the version the completed upload produced, or {@code null} when the store
     *     reported none, which a bucket without versioning does
     */
    public record PublishedCategoryBalanceReport(
            CategoryBalanceReportService.CategoryBalanceReportSummary summary,
            String bucket,
            String key,
            String versionId) {

        /**
         * Refuses a locator missing any of its non-optional parts.
         *
         * @param summary as the record component of the same name
         * @param bucket as the record component of the same name
         * @param key as the record component of the same name
         * @param versionId as the record component of the same name
         * @throws NullPointerException if the summary, the bucket or the key is {@code null}
         */
        public PublishedCategoryBalanceReport {
            Objects.requireNonNull(summary, "summary must not be null");
            Objects.requireNonNull(bucket, "bucket must not be null");
            Objects.requireNonNull(key, "key must not be null");
        }

        /**
         * Renders the artifact's location as one log-safe locator.
         *
         * @return an {@code s3://} URI, with the version appended as a query parameter when the store
         *     reported one, never {@code null}
         */
        public String locator() {
            String base = "s3://" + this.bucket + "/" + this.key;
            return this.versionId == null ? base : base + "?versionId=" + this.versionId;
        }
    }
}
