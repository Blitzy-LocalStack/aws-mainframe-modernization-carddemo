package com.carddemo.reporting.task;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Composes and allocates the generation coordinates a baseline {@code (+1)} reference resolves to.
 *
 * <p>Purpose: the reference's generation data groups are addressed relatively -- {@code (+1)} means a
 * new generation and {@code (0)} the current one -- and the migration plan's section 0.4.1.7 fixes the
 * target convention as
 * {@code <domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/}. This class is the one place that convention is
 * spelled inside {@code reporting-service}, so the report state's artifacts land under a coordinate
 * the dataset bucket already provisions a prefix and a lifecycle rule for.</p>
 *
 * <p>Assumptions: retention is NOT performed here, and the omission is deliberate rather than missing.
 * {@code infra/lambda/dataset_generation_retention.py} handles the bucket's {@code ObjectCreated}
 * notifications, lists every {@code dt=}/{@code gen=} prefix of the affected family and deletes the
 * current objects under every prefix past the newest five. So a writer that lands a key in this shape
 * inherits the {@code LIMIT(5) SCRATCH} contract from the infrastructure. Reimplementing the prune in
 * this service would give the family two independent pruners with two independent notions of which
 * five are newest, which is strictly worse than one.</p>
 *
 * <p>Assumptions: allocation is a LISTING and not a durable claim. A repeated run for one business date
 * therefore consumes a new generation, which is exactly what resubmitting the reference job does --
 * {@code (+1)} allocates again. The risk this leaves is two executions of the same date racing and both
 * resolving to the same number, and it is accepted because the nightly chain holds a single-execution
 * quiesce bracket over the whole window: the report state cannot be running twice concurrently without
 * that bracket already having been violated. The sibling batch module reserves through a conditional
 * claim object because its Map branches DO run concurrently, which is the difference between the two.
 * </p>
 *
 * <p>Documentation convention: {@code docs/CODE_DOCUMENTATION_STANDARD.md}.</p>
 *
 * <p>Baseline lineage: citations are provenance only. Nothing under {@code app/} is read at run time
 * and nothing under it is altered by this migration.</p>
 */
public final class GenerationKeys {

    /** The business-date partition marker, from the migration plan's generation key convention. */
    public static final String DATE_PARTITION = "dt=";

    /** The generation partition marker, from the same convention. */
    public static final String GENERATION_PARTITION = "gen=";

    /**
     * Digits in a generation number, so {@code gen=0001} sorts lexically in numeric order.
     *
     * <p>Assumptions: FOUR, matching both the sibling batch module's coordinate type and the retention
     * function's own key grammar. A varying width would break lexical ordering at ten, which is what
     * the retention function sorts by.</p>
     */
    public static final int GENERATION_DIGITS = 4;

    /** The first generation number, matching a baseline group's first {@code (+1)}. */
    public static final int MINIMUM_GENERATION = 1;

    /** The highest generation number the four-digit segment can express. */
    public static final int MAXIMUM_GENERATION = 9999;

    /** Separator between key segments. */
    private static final String SEGMENT_SEPARATOR = "/";

    /**
     * Refuses instantiation of this key-composition holder.
     *
     * @throws AssertionError always, because the type holds only static composition. It is raised
     *     rather than left as an empty body so a reflective instantiation fails loudly instead of
     *     yielding a useless instance
     */
    private GenerationKeys() {
        throw new AssertionError("GenerationKeys composes keys and is not instantiable");
    }

    /**
     * Reports the family prefix a generation of one dataset lives under.
     *
     * @param domain the owning bounded context, the first key segment; must not be {@code null} or
     *     blank
     * @param dataset the dataset segment, matching the key {@code infra/modules/s3-datasets} uses in
     *     {@code var.dataset_families}; must not be {@code null} or blank
     * @return the family prefix, for example {@code reporting/tranrept/}, always ending in a
     *     separator, never {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if either argument is blank or already contains a separator,
     *     because a segment carrying one would compose a prefix outside the family it names
     */
    public static String familyPrefix(String domain, String dataset) {
        return requireSegment(domain, "domain") + SEGMENT_SEPARATOR
                + requireSegment(dataset, "dataset") + SEGMENT_SEPARATOR;
    }

    /**
     * Composes the full object key of one generation of one dataset.
     *
     * @param domain the owning bounded context; must not be {@code null} or blank
     * @param dataset the dataset segment; must not be {@code null} or blank
     * @param businessDate the business date the generation is partitioned under; must not be
     *     {@code null}
     * @param generation the generation number; must be between {@value #MINIMUM_GENERATION} and
     *     {@value #MAXIMUM_GENERATION}
     * @param objectName the object name inside the generation prefix; must not be {@code null} or
     *     blank and must not contain a separator
     * @return the full key, never {@code null}
     * @throws NullPointerException if any reference argument is {@code null}
     * @throws IllegalArgumentException if a segment is blank or carries a separator, or if the
     *     generation is outside the representable range
     */
    public static String generationKey(String domain, String dataset, LocalDate businessDate,
            int generation, String objectName) {

        Objects.requireNonNull(businessDate, "businessDate must not be null");
        if (generation < MINIMUM_GENERATION || generation > MAXIMUM_GENERATION) {
            throw new IllegalArgumentException("the generation must be between "
                    + MINIMUM_GENERATION + " and " + MAXIMUM_GENERATION + ", was " + generation);
        }
        return familyPrefix(domain, dataset)
                + DATE_PARTITION + businessDate + SEGMENT_SEPARATOR
                + GENERATION_PARTITION
                + String.format(Locale.ROOT, "%0" + GENERATION_DIGITS + "d", generation)
                + SEGMENT_SEPARATOR
                + requireSegment(objectName, "objectName");
    }

    /**
     * Resolves the generation number a new write for one business date should carry.
     *
     * <p>Assumptions: the listing is scoped to the family AND the business date, not to the family
     * alone. Generation numbers restart per date under this convention -- which is what makes
     * {@code dt=} the outer partition -- so a family-wide listing would return yesterday's numbers and
     * push today's first generation past one.</p>
     *
     * <p>Trade-offs: unparseable keys under the prefix are IGNORED rather than treated as failures. A
     * key an operator placed by hand, or a leftover from a previous key grammar, would otherwise stop
     * the nightly report from publishing at all; ignoring it means the allocation is derived from the
     * generations it can read, which is the same set the retention function prunes.</p>
     *
     * @param s3 the client the listing is issued through; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null} or blank
     * @param domain the owning bounded context; must not be {@code null} or blank
     * @param dataset the dataset segment; must not be {@code null} or blank
     * @param businessDate the business date to allocate within; must not be {@code null}
     * @return {@value #MINIMUM_GENERATION} when the date holds no readable generation, otherwise one
     *     past the highest it holds
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if a segment is blank or carries a separator
     * @throws IllegalStateException if the date's generation space is exhausted, which is reported
     *     rather than silently wrapping to a number that would overwrite a retained generation
     */
    public static int nextGeneration(S3Client s3, String bucket, String domain, String dataset,
            LocalDate businessDate) {

        Objects.requireNonNull(s3, "s3 must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        String datePrefix = familyPrefix(domain, dataset)
                + DATE_PARTITION + businessDate + SEGMENT_SEPARATOR;

        int highest = 0;
        String continuation = null;
        do {
            ListObjectsV2Response page = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(requireSegment(bucket, "bucket"))
                    .prefix(datePrefix)
                    .continuationToken(continuation)
                    .build());
            for (S3Object object : page.contents()) {
                highest = Math.max(highest, generationOf(object.key(), datePrefix));
            }
            // WHY : Assumptions: the loop follows the continuation token rather than reading one page.
            //       A single page holds at most a thousand keys, and five retained generations of a
            //       report can exceed that between them, so a one-page read would silently miss the
            //       highest number and reallocate a generation that already holds bytes.
            continuation = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
        } while (continuation != null);

        int next = highest + 1;
        if (next > MAXIMUM_GENERATION) {
            throw new IllegalStateException("the generation space for " + datePrefix
                    + " is exhausted at gen=" + MAXIMUM_GENERATION);
        }
        return Math.max(next, MINIMUM_GENERATION);
    }

    /**
     * Reads the generation number out of one key, or reports zero when it carries none.
     *
     * @param key the object key as the listing returned it; may be {@code null}, which reads as no
     *     generation
     * @param datePrefix the date prefix the listing was scoped to; must not be {@code null}
     * @return the generation number, or zero when the key does not carry a readable one
     */
    private static int generationOf(String key, String datePrefix) {
        if (key == null || !key.startsWith(datePrefix)) {
            return 0;
        }
        String remainder = key.substring(datePrefix.length());
        if (!remainder.startsWith(GENERATION_PARTITION)) {
            return 0;
        }
        int end = remainder.indexOf(SEGMENT_SEPARATOR, GENERATION_PARTITION.length());
        String digits = end < 0
                ? remainder.substring(GENERATION_PARTITION.length())
                : remainder.substring(GENERATION_PARTITION.length(), end);
        if (digits.length() != GENERATION_DIGITS || !digits.chars().allMatch(Character::isDigit)) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    /**
     * Refuses a key segment that is absent, blank or already separated.
     *
     * @param value the segment to check; must not be {@code null}
     * @param name the argument name used in the refusal message; must not be {@code null}
     * @return the segment unchanged, never {@code null}
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is blank or contains a separator
     */
    private static String requireSegment(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.contains(SEGMENT_SEPARATOR)) {
            throw new IllegalArgumentException(
                    name + " must be one key segment and must not contain " + SEGMENT_SEPARATOR);
        }
        return value;
    }
}
