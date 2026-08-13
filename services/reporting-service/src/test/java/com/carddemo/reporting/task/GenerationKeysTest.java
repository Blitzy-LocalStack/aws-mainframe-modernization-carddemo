package com.carddemo.reporting.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Pins the generation-key convention and the number a new write is allocated.
 *
 * <p>Purpose: the baseline addresses a generation dataset relatively -- {@code TRANREPT(+1)} at
 * {@code app/jcl/TRANREPT.jcl:80} against a base defined {@code LIMIT(5) SCRATCH} at
 * {@code app/jcl/DEFGDGB.jcl:37-39} -- and the catalog resolves the number. Object storage has no
 * catalog, so the number is resolved by reading what is already there. Two properties therefore have to
 * hold and are asserted here: the key a number composes into, and that the number chosen is above every
 * number already present for that date.
 *
 * <p>Assumptions: the expected keys are written as literals rather than composed from the class's own
 * constants. Composing them would make the assertion agree with the composition whatever it produced,
 * including a partition marker changed to something no lifecycle filter or retention function matches --
 * which is the failure these cases exist to catch, because the prefix is a contract shared with
 * {@code infra/modules/s3-datasets} and {@code infra/lambda/dataset_generation_retention.py}.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.
 */
class GenerationKeysTest {

    /** The bucket every listing in this class is issued against. */
    private static final String BUCKET = "carddemo-datasets-test";

    /** The domain the transaction detail report's generation family belongs to. */
    private static final String DOMAIN = "reporting";

    /** The dataset segment of that family, matching the s3-datasets family key. */
    private static final String DATASET = "tranrept";

    /** The business date every key in this class is partitioned under. */
    private static final LocalDate DATE = LocalDate.of(2022, 7, 18);

    /**
     * Asserts the family prefix is the two segments the module's own prefix map publishes.
     */
    @Test
    @DisplayName("the family prefix is domain then dataset, each one segment")
    void theFamilyPrefixIsDomainThenDataset() {
        assertThat(GenerationKeys.familyPrefix(DOMAIN, DATASET))
                .as("infra/modules/s3-datasets derives the same prefix for the tranrept family")
                .isEqualTo("reporting/tranrept/");
    }

    // WHY : Assumptions: the generation number is asserted to be FOUR digits and zero-padded, which is
    //       what makes a lexical listing of the prefix return generations in numeric order. Three
    //       digits or an unpadded number would sort gen=10 before gen=2, so the retention function --
    //       which prunes past the newest five by key order -- would delete the wrong generations.
    /**
     * Asserts the composed key carries both partitions, a four-digit generation and the object name.
     */
    @Test
    @DisplayName("the generation key carries both partitions, four padded digits and the object")
    void theGenerationKeyCarriesBothPartitions() {
        assertThat(GenerationKeys.generationKey(DOMAIN, DATASET, DATE, 1, "tranrept.txt"))
                .isEqualTo("reporting/tranrept/dt=2022-07-18/gen=0001/tranrept.txt");
        assertThat(GenerationKeys.generationKey(DOMAIN, DATASET, DATE, 42, "tranrept.txt"))
                .isEqualTo("reporting/tranrept/dt=2022-07-18/gen=0042/tranrept.txt");
        assertThat(GenerationKeys.generationKey(DOMAIN, DATASET, DATE, 9999, "tranrept.txt"))
                .isEqualTo("reporting/tranrept/dt=2022-07-18/gen=9999/tranrept.txt");
    }

    /**
     * Asserts a generation outside the representable range is refused.
     */
    @Test
    @DisplayName("a generation outside one through 9999 is refused")
    void aGenerationOutsideTheRangeIsRefused() {
        for (int rejected : new int[] {0, -1, 10000}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> GenerationKeys.generationKey(
                            DOMAIN, DATASET, DATE, rejected, "tranrept.txt"))
                    .withMessageContaining("must be between");
        }
    }

    // WHY : Assumptions: a segment carrying a slash is asserted to be REFUSED rather than accepted and
    //       flattened. A dataset value of "tranrept/extra" would compose a key one level deeper than
    //       the family prefix the lifecycle rule filters on, so the object would be written and would
    //       then never be pruned -- a silent unbounded cost rather than a visible failure.
    /**
     * Asserts a blank or slash-bearing segment is refused.
     */
    @Test
    @DisplayName("a blank or slash-bearing key segment is refused")
    void aBlankOrSlashBearingSegmentIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> GenerationKeys.familyPrefix("  ", DATASET))
                .withMessageContaining("blank");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> GenerationKeys.familyPrefix(DOMAIN, "tranrept/extra"))
                .withMessageContaining("one key segment");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> GenerationKeys.generationKey(
                        DOMAIN, DATASET, DATE, 1, "nested/tranrept.txt"))
                .withMessageContaining("one key segment");
    }

    /**
     * Asserts an empty date partition allocates the first generation.
     */
    @Test
    @DisplayName("a date holding no generation allocates the first")
    void anEmptyDateAllocatesTheFirstGeneration() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().build());

        assertThat(GenerationKeys.nextGeneration(s3, BUCKET, DOMAIN, DATASET, DATE))
                .isEqualTo(GenerationKeys.MINIMUM_GENERATION);

        ArgumentCaptor<ListObjectsV2Request> listing =
                ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3).listObjectsV2(listing.capture());
        assertThat(listing.getValue().prefix())
                .as("the listing is scoped to the family AND the date, so numbers restart per date")
                .isEqualTo("reporting/tranrept/dt=2022-07-18/");
        assertThat(listing.getValue().bucket()).isEqualTo(BUCKET);
    }

    // WHY : Assumptions: the highest number is taken rather than the count of keys. A date holding
    //       gen=0001 and gen=0003 -- which is what a pruned or a partly failed history looks like --
    //       has two keys and a highest of three, so counting would reallocate 0003 and overwrite an
    //       artifact that already holds bytes.
    /**
     * Asserts the allocation follows the highest number present, not the number of keys.
     */
    @Test
    @DisplayName("the allocation follows the highest generation present, not the key count")
    void theAllocationFollowsTheHighestPresent() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(false, null,
                "reporting/tranrept/dt=2022-07-18/gen=0001/tranrept.txt",
                "reporting/tranrept/dt=2022-07-18/gen=0003/tranrept.txt"));

        assertThat(GenerationKeys.nextGeneration(s3, BUCKET, DOMAIN, DATASET, DATE)).isEqualTo(4);
    }

    /**
     * Asserts a key carrying no readable generation is ignored rather than failing the allocation.
     */
    @Test
    @DisplayName("an unreadable key under the prefix is ignored, not fatal")
    void anUnreadableKeyIsIgnored() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(false, null,
                "reporting/tranrept/dt=2022-07-18/notes.txt",
                "reporting/tranrept/dt=2022-07-18/gen=abcd/tranrept.txt",
                "reporting/tranrept/dt=2022-07-18/gen=12/tranrept.txt",
                "reporting/tranrept/dt=2022-07-18/gen=0002/tranrept.txt"));

        assertThat(GenerationKeys.nextGeneration(s3, BUCKET, DOMAIN, DATASET, DATE))
                .as("only the four-digit all-numeric form counts, so 0002 is the highest read")
                .isEqualTo(3);
    }

    // WHY : Assumptions: the continuation token is asserted to be FOLLOWED, and the case is built with
    //       two pages rather than trusted from a code read. One page holds at most a thousand keys and
    //       five retained generations of a report can exceed that between them, so a one-page read
    //       would silently miss the highest number -- which reads as success and overwrites bytes.
    /**
     * Asserts every page of a truncated listing is read before the number is chosen.
     */
    @Test
    @DisplayName("a truncated listing is followed to its last page")
    void aTruncatedListingIsFollowedToTheLastPage() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(page(true, "page-2",
                        "reporting/tranrept/dt=2022-07-18/gen=0001/tranrept.txt"))
                .thenReturn(page(false, null,
                        "reporting/tranrept/dt=2022-07-18/gen=0007/tranrept.txt"));

        assertThat(GenerationKeys.nextGeneration(s3, BUCKET, DOMAIN, DATASET, DATE)).isEqualTo(8);

        ArgumentCaptor<ListObjectsV2Request> listings =
                ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3, org.mockito.Mockito.times(2)).listObjectsV2(listings.capture());
        assertThat(listings.getAllValues().stream().map(ListObjectsV2Request::continuationToken)
                .toList())
                .as("the first call carries no token and the second carries the one it was given")
                .containsExactly(null, "page-2");
    }

    // WHY : Assumptions: exhaustion is asserted to RAISE rather than to wrap to one or to widen the
    //       number. Wrapping would overwrite the oldest retained generation of the same date, and
    //       widening would produce a key that sorts before every four-digit one, which would make the
    //       retention function prune the newest artifact rather than the oldest.
    /**
     * Asserts an exhausted generation space is refused rather than wrapped.
     */
    @Test
    @DisplayName("an exhausted generation space is refused, naming the date prefix")
    void anExhaustedGenerationSpaceIsRefused() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page(false, null,
                "reporting/tranrept/dt=2022-07-18/gen=9999/tranrept.txt"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> GenerationKeys.nextGeneration(s3, BUCKET, DOMAIN, DATASET, DATE))
                .withMessageContaining("reporting/tranrept/dt=2022-07-18/")
                .withMessageContaining("exhausted");
    }

    /**
     * Asserts the helper refuses instantiation, because it holds only the convention.
     *
     * @throws Exception if the constructor cannot be reached reflectively, which would itself be the
     *     failure this case reports
     */
    @Test
    @DisplayName("the key helper refuses reflective instantiation")
    void theKeyHelperIsNotInstantiable() throws Exception {
        var constructor = GenerationKeys.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatExceptionOfType(java.lang.reflect.InvocationTargetException.class)
                .isThrownBy(constructor::newInstance)
                .withCauseInstanceOf(AssertionError.class);
    }

    /**
     * Builds one listing page.
     *
     * @param truncated whether the page reports more to follow
     * @param nextToken the continuation token the page carries, or {@code null}
     * @param keys the object keys the page holds
     * @return the response
     */
    private static ListObjectsV2Response page(boolean truncated, String nextToken, String... keys) {
        return ListObjectsV2Response.builder()
                .contents(List.of(keys).stream()
                        .map(key -> S3Object.builder().key(key).build())
                        .toList())
                .isTruncated(truncated)
                .nextContinuationToken(nextToken)
                .build();
    }
}
