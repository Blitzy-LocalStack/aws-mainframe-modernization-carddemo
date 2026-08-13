package com.carddemo.reporting.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.money.Money;
import com.carddemo.reporting.service.CategoryBalanceReportService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * Pins where the category-balance report lands and what its locator says.
 *
 * <p>Purpose: the report's content is pinned by {@code CategoryBalanceLineLayoutTest} and its generation
 * pass by {@code CategoryBalanceReportServiceTest}. What neither can see is the object key, because a
 * key is observable only from the request the storage client is handed -- and a report published under
 * the wrong key is indistinguishable from a report published correctly, from inside the service that
 * generated it.
 *
 * <p>Assumptions: the key is asserted to be FIXED and to carry no date or range partition, unlike the
 * transaction report's. {@code app/jcl/PRTCATBL.jcl:59-63} writes to a plain sequential dataset with no
 * generation base and deletes it first at L21-L25, so each run replaces the last -- which object
 * versioning expresses as a new current version. A key carrying a date would accumulate one artifact per
 * night where the reference keeps one.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.
 */
class CategoryBalanceArtifactPublisherTest {

    /** The bucket the publisher writes to, standing for the deployment's own. */
    private static final String BUCKET = "carddemo-datasets-test";

    /** The prefix the base configuration document declares for this artifact. */
    private static final String PREFIX = "reports/category-balance/";

    /** The generator the publisher drives. */
    private final CategoryBalanceReportService reports = mock(CategoryBalanceReportService.class);

    /**
     * Asserts the artifact lands on the fixed key and the locator agrees with it.
     *
     * @throws Exception if the publication raises, which the assertions below would not reach
     */
    @Test
    @DisplayName("the report lands on the fixed key and the locator names that same key")
    void theReportLandsOnTheFixedKey() throws Exception {
        S3Client s3 = storageAnsweringVersion("v-7");
        when(reports.generateReport(any())).thenAnswer(call -> {
            call.<CategoryBalanceReportService.ReportLineSink>getArgument(0)
                    .write("00000000011 01 0001 000000010.00        ".getBytes(
                            StandardCharsets.US_ASCII));
            return summary(1L, "10.00");
        });

        CategoryBalanceArtifactPublisher.PublishedCategoryBalanceReport published =
                new CategoryBalanceArtifactPublisher(reports, s3, BUCKET, PREFIX).publish();

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        String expectedKey = PREFIX + CategoryBalanceArtifactPublisher.REPORT_OBJECT;
        assertThat(put.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(put.getValue().key())
                .as("a plain sequential dataset with no generation base carries no dt= partition")
                .isEqualTo(expectedKey)
                .doesNotContain("dt=")
                .doesNotContain("gen=");
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));

        // WHY : Assumptions: the locator is asserted to agree with the key the client was HANDED rather
        //       than merely to be non-empty. A locator assembled by a different rule than the write
        //       used would name an object that does not exist, and holding the two to each other is the
        //       only way to detect that from outside.
        assertThat(published.bucket()).isEqualTo(BUCKET);
        assertThat(published.key()).isEqualTo(expectedKey);
        assertThat(published.versionId()).isEqualTo("v-7");
        assertThat(published.locator())
                .isEqualTo("s3://" + BUCKET + "/" + expectedKey + "?versionId=v-7");
        assertThat(published.summary()).isEqualTo(summary(1L, "10.00"));
    }

    // WHY : Assumptions: the bytes reaching the store are asserted, not only the key, because the sink
    //       the publisher hands the generator is a method reference to the writer -- so a publisher that
    //       passed a discarding sink would produce a correctly-keyed EMPTY artifact and satisfy every
    //       key assertion above.
    // WHY : Assumptions: each record is expected to be followed by a NEWLINE, and the expectation is
    //       written with the terminators visible rather than the assertion being loosened to a
    //       containment check. The reference's dataset is RECFM=FB, where record boundaries are a
    //       property of the dataset and not bytes in it; an S3 object has no record structure, so the
    //       writer supplies a terminator and that is what makes the artifact readable line by line by
    //       every ordinary tool. The consequence a reader needs is that the object is 41 bytes per
    //       record rather than the 40 app/jcl/PRTCATBL.jcl:61 declares, and the declared 40 remains the
    //       width of the record itself -- which is what CategoryBalanceLineLayoutTest asserts.
    /**
     * Asserts every generated line reaches the store in order, each terminated.
     *
     * @throws Exception if the publication raises, which the assertions below would not reach
     */
    @Test
    @DisplayName("every generated line reaches the store, in order and newline-terminated")
    void everyGeneratedLineReachesTheStore() throws Exception {
        S3Client s3 = storageAnsweringVersion(null);
        when(reports.generateReport(any())).thenAnswer(call -> {
            CategoryBalanceReportService.ReportLineSink sink = call.getArgument(0);
            sink.write("first-line".getBytes(StandardCharsets.US_ASCII));
            sink.write("second-line".getBytes(StandardCharsets.US_ASCII));
            return summary(2L, "0.00");
        });

        new CategoryBalanceArtifactPublisher(reports, s3, BUCKET, PREFIX).publish();

        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3).putObject(any(PutObjectRequest.class), body.capture());
        assertThat(new String(body.getValue().contentStreamProvider().newStream().readAllBytes(),
                StandardCharsets.US_ASCII))
                .isEqualTo("first-line\nsecond-line\n");
    }

    /**
     * Asserts an unversioned bucket yields a locator with no version fragment.
     *
     * @throws Exception if the publication raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("an unversioned bucket yields a locator carrying no version fragment")
    void anUnversionedBucketYieldsAPlainLocator() throws Exception {
        S3Client s3 = storageAnsweringVersion(null);
        when(reports.generateReport(any())).thenReturn(summary(0L, "0.00"));

        CategoryBalanceArtifactPublisher.PublishedCategoryBalanceReport published =
                new CategoryBalanceArtifactPublisher(reports, s3, BUCKET, PREFIX).publish();

        assertThat(published.versionId()).isNull();
        assertThat(published.locator())
                .isEqualTo("s3://" + BUCKET + "/" + published.key())
                .doesNotContain("versionId");
    }

    /**
     * Asserts a generator failure propagates rather than publishing a partial artifact as complete.
     *
     * @throws Exception if the mock setup raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("a generator failure propagates out of the publisher")
    void aGeneratorFailurePropagates() throws Exception {
        S3Client s3 = storageAnsweringVersion(null);
        when(reports.generateReport(any()))
                .thenThrow(new IllegalStateException("the balance did not render"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new CategoryBalanceArtifactPublisher(reports, s3, BUCKET, PREFIX)
                        .publish());
    }

    /**
     * Asserts the publisher refuses construction with any argument absent.
     */
    @Test
    @DisplayName("the publisher refuses construction with any argument absent")
    void thePublisherRefusesAnAbsentArgument() {
        S3Client s3 = mock(S3Client.class);

        assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                () -> new CategoryBalanceArtifactPublisher(null, s3, BUCKET, PREFIX));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                () -> new CategoryBalanceArtifactPublisher(reports, null, BUCKET, PREFIX));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                () -> new CategoryBalanceArtifactPublisher(reports, s3, null, PREFIX));
        assertThatExceptionOfType(NullPointerException.class).isThrownBy(
                () -> new CategoryBalanceArtifactPublisher(reports, s3, BUCKET, null));
    }

    /**
     * Builds one generation summary.
     *
     * @param lines the number of lines written
     * @param total the exact total, as a decimal string
     * @return the summary
     */
    private static CategoryBalanceReportService.CategoryBalanceReportSummary summary(
            long lines, String total) {
        return new CategoryBalanceReportService.CategoryBalanceReportSummary(
                lines, Money.of(total));
    }

    /**
     * Builds a storage client whose whole-object put answers a given version.
     *
     * <p>Assumptions: the put is stubbed rather than left at its default, because a Mockito default
     * answers {@code null} for the response object and the writer reads a member off it -- so a default
     * would fail with a null dereference rather than exercising the version capture.</p>
     *
     * @param versionId the version to answer, or {@code null} to stand for an unversioned bucket
     * @return the client
     */
    private static S3Client storageAnsweringVersion(String versionId) {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId(versionId).build());
        return s3;
    }
}
