package com.carddemo.reporting.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.money.Money;
import com.carddemo.reporting.ReportingTask;
import com.carddemo.reporting.ReportingTaskRunner;
import com.carddemo.reporting.service.CategoryBalanceReportService;
import com.carddemo.reporting.service.ReportArtifactLocator;
import com.carddemo.reporting.service.StatementIndexEntry;
import com.carddemo.reporting.service.StatementRunOutcome;
import com.carddemo.reporting.service.StatementService;
import com.carddemo.reporting.service.TransactionReportService;
import com.carddemo.reporting.sink.S3StatementSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Pins that every task name the orchestrator dispatches resolves to a bean that can actually run, and
 * that each one covers the range it was given and keys its artifact where the convention says.
 *
 * <p>Purpose: the runner turns a {@code --job=} token into a bean lookup by that exact name. Two
 * existing checks bracket that lookup and neither covers it: the runner's own test proves the token is
 * ACCEPTED, and the state-machine source proves the token is SENT. Between those two, nothing proved a
 * bean answered to the name -- so the module shipped in a state where all three accepted names were
 * dispatched, validated, and then failed to resolve, and a report submission was acknowledged while
 * producing nothing. This class closes that gap by resolving each name for real.
 *
 * <p>Assumptions: the names are read from {@link ReportingTaskRunner#JOB_NAMES} rather than restated
 * here. A restated list agrees with whatever it was copied from and cannot detect a fourth name added
 * without a fourth bean, which is the failure this class exists for.
 *
 * <p>Assumptions: a real Spring context is refreshed rather than the annotations being read
 * reflectively. Reading the annotation would prove a name is DECLARED; refreshing proves it is
 * REGISTERED, which is a different claim -- two beans declaring one name, or a component the scan does
 * not reach, both satisfy the first and fail the second.
 *
 * <p>Assumptions: the two collaborating services and the storage client are supplied as mocks, and the
 * three configuration values as literals. Nothing here contacts a database or an object store: the
 * properties under test are the wiring, the range each task derives, and the object key each artifact
 * lands on -- and the last of those is observable from the request the storage client is handed.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.
 */
class TaskDispatchWiringTest {

    /** A ten-character date token of the width the orchestrator validates before dispatching. */
    private static final String DATE_TOKEN = "2022-07-18";

    /** The bucket every task in this class publishes to, standing for the deployment's own. */
    private static final String BUCKET = "carddemo-datasets-test";

    /** The statement key prefix, matching the base configuration document. */
    private static final String STATEMENT_PREFIX = "statements/";

    /** The report key prefix, matching the base configuration document. */
    private static final String REPORT_PREFIX = "reports/transaction-detail/";

    /** The category-balance report key prefix, matching the base configuration document. */
    private static final String CATEGORY_BALANCE_PREFIX = "reports/category-balance/";

    /**
     * The context under test: the task package scanned, its collaborators mocked, its values supplied.
     *
     * <p>Assumptions: only the task package is scanned. Scanning the module root would build the filter
     * chain and the token decoder, and the decoder resolves an issuer's discovery document over the
     * network at bean-creation time -- so a context started to resolve three bean names would fail for a
     * reason unrelated to them.</p>
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TaskScanConfiguration.class)
            .withBean(StatementService.class, () -> mock(StatementService.class))
            .withBean(TransactionReportService.class, () -> mock(TransactionReportService.class))
            .withBean(CategoryBalanceReportService.class,
                    () -> mock(CategoryBalanceReportService.class))
            .withBean(S3Client.class, () -> mock(S3Client.class))
            // WHY : Assumptions: the key locator is registered as a REAL instance rather than a mock,
            //       because the key it composes is what one case below asserts -- a mocked locator would
            //       answer null and the assertion would be comparing a key against nothing. It lives in
            //       the service package, which this slice does not scan, so it is supplied here.
            .withBean(ReportArtifactLocator.class, () -> new ReportArtifactLocator(REPORT_PREFIX))
            .withPropertyValues(
                    StatementService.OUTPUT_BUCKET_PROPERTY + "=" + BUCKET,
                    StatementService.STATEMENT_PREFIX_PROPERTY + "=" + STATEMENT_PREFIX,
                    ReportArtifactPublisher.REPORT_PREFIX_PROPERTY + "=" + REPORT_PREFIX,
                    CategoryBalanceArtifactPublisher.CATEGORY_BALANCE_PREFIX_PROPERTY + "="
                            + CATEGORY_BALANCE_PREFIX);

    /**
     * Asserts that each accepted task name resolves to exactly one runnable task bean.
     */
    @Test
    @DisplayName("every accepted task name resolves to a runnable task bean")
    void everyAcceptedTaskNameResolvesToABean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            for (String name : ReportingTaskRunner.JOB_NAMES) {
                assertThat(context.getBean(name, ReportingTask.class))
                        .as("the orchestrator dispatches --job=%s and the runner looks a bean up by"
                                + " that exact name", name)
                        .isNotNull();
            }
        });
    }

    // WHY : Assumptions: the roster is asserted CLOSED as well as complete. A fourth task bean carrying
    //       a name the runner does not accept is unreachable code that reads as reachable, and the two
    //       lists drifting apart in that direction is exactly as misleading as drifting apart in the
    //       other -- a reader auditing the package would conclude a job exists that nothing can start.
    /**
     * Asserts that no task bean exists that the runner would not accept.
     */
    @Test
    @DisplayName("no task bean carries a name the runner does not accept")
    void noTaskBeanIsUnreachable() {
        contextRunner.run(context -> assertThat(context.getBeanNamesForType(ReportingTask.class))
                .as("every registered task is reachable from a --job= token")
                .containsExactlyInAnyOrderElementsOf(ReportingTaskRunner.JOB_NAMES));
    }

    // WHY : Assumptions: the two report tasks are asserted to derive DIFFERENT ranges, because that is
    //       the whole reason two beans exist rather than one. A single task reading whichever parameters
    //       happened to be present would satisfy the resolution assertions above while making a nightly
    //       run and a submitted request indistinguishable in every journal line and execution history.
    /**
     * Asserts that the nightly report task treats its business date as a one-day inclusive range.
     *
     * @throws Exception if the task raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("the nightly report task covers its business date as a one-day range")
    void theNightlyReportTaskCoversOneDay() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publishDaily(any())).thenReturn(published());

        new GenerateReportsTask(publisher, balancePublisher()).run(Map.of(
                ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN));

        // WHY : Refactoring Rationale: the nightly task is verified against publishDaily rather than
        //       against the four-argument publication, because the one-day range and the daily type
        //       token belong together and the publisher now states that pairing once. Verifying the
        //       general form here would let a future caller pair the daily token with a period and still
        //       satisfy this case.
        LocalDate businessDate = LocalDate.parse(DATE_TOKEN);
        verify(publisher).publishDaily(businessDate);
    }

    // WHY : Assumptions: the nightly state is asserted to produce BOTH reports, because the state it
    //       runs stands in for two reference jobs -- app/jcl/TRANREPT.jcl and app/jcl/PRTCATBL.jcl --
    //       and a task producing only the first would satisfy every other case in this class while a
    //       night's category-balance report silently never appeared.
    /**
     * Asserts that the nightly state produces the category-balance report as well as the transaction one.
     *
     * @throws Exception if either publication raises, which the assertions below would not reach
     */
    @Test
    @DisplayName("the nightly state produces both the transaction and the category-balance report")
    void theNightlyStateProducesBothReports() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publishDaily(any())).thenReturn(published());
        CategoryBalanceArtifactPublisher balances = balancePublisher();

        new GenerateReportsTask(publisher, balances).run(Map.of(
                ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN));

        verify(publisher).publishDaily(LocalDate.parse(DATE_TOKEN));
        // WHY : Assumptions: the category-balance publication is verified to take NO argument, which is
        //       the whole content of its contract. app/jcl/PRTCATBL.jcl:44-45 feeds its sort the entire
        //       unloaded file with no INCLUDE condition, unlike app/jcl/TRANREPT.jcl:47-48 -- so a task
        //       that narrowed it to a business date would report a period the reference never reports.
        verify(balances).publish();
    }

    // WHY : Assumptions: a failing category-balance publication is asserted to FAIL THE STATE rather
    //       than to be absorbed, and the transaction report is asserted to have been published first.
    //       A state that swallowed the second failure would report a night complete having produced one
    //       of its two reports, which is exactly the class of silent gap this finding was raised over.
    /**
     * Asserts that a category-balance failure fails the state after the first report is published.
     *
     * @throws Exception if the mock setup raises, which the assertions below would not reach
     */
    @Test
    @DisplayName("a category-balance failure fails the state, after the first report is published")
    void aCategoryBalanceFailureFailsTheState() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publishDaily(any())).thenReturn(published());
        CategoryBalanceArtifactPublisher balances = mock(CategoryBalanceArtifactPublisher.class);
        when(balances.publish())
                .thenThrow(new IOException("the category-balance artifact could not be published"));

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> new GenerateReportsTask(publisher, balances).run(Map.of(
                        ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN)));

        verify(publisher).publishDaily(LocalDate.parse(DATE_TOKEN));
    }

    /**
     * Asserts that the on-demand task covers the range it was given and keys under its end.
     *
     * @throws Exception if the task raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("the on-demand task covers the requested range and keys under its end")
    void theOnDemandTaskCoversTheRequestedRange() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publish(any(), any(), any(), any())).thenReturn(published());

        new GenerateAdHocReportTask(publisher).run(Map.of(
                ReportingTaskRunner.START_DATE_PARAMETER, "2022-07-01",
                ReportingTaskRunner.END_DATE_PARAMETER, DATE_TOKEN,
                ReportingTaskRunner.REPORT_TYPE_PARAMETER, "Custom"));

        // WHY : Assumptions: the requested TYPE is verified alongside the range, because the type is now
        //       part of the artifact key and a task that dropped it would publish an on-demand report
        //       over the key some other type owns.
        verify(publisher).publish("Custom",
                LocalDate.of(2022, 7, 1), LocalDate.parse(DATE_TOKEN), LocalDate.parse(DATE_TOKEN));
    }

    // WHY : Assumptions: an absent bound is refused rather than defaulted to the clock, and the refusal
    //       is asserted to quote the COMMAND-LINE option. Defaulting a date would make a rerun produce a
    //       different answer from the run it repeats, which destroys the reproducibility every
    //       golden-master comparison in this project depends on.
    /**
     * Asserts that the on-demand task refuses an absent bound rather than inventing one.
     */
    @Test
    @DisplayName("the on-demand task refuses an absent bound, quoting the option")
    void theOnDemandTaskRefusesAnAbsentBound() {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new GenerateAdHocReportTask(publisher).run(Map.of(
                        ReportingTaskRunner.START_DATE_PARAMETER, "2022-07-01")))
                .withMessageContaining(ReportingTaskRunner.END_DATE_OPTION);
    }

    /**
     * Asserts that the nightly report task refuses an absent business date.
     */
    @Test
    @DisplayName("the nightly report task refuses an absent business date")
    void theNightlyReportTaskRefusesAnAbsentBusinessDate() {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new GenerateReportsTask(publisher, balancePublisher())
                        .run(Map.of()))
                .withMessageContaining(ReportingTaskRunner.BUSINESS_DATE_OPTION);
    }

    // WHY : Assumptions: the report key is asserted through the request the storage client is HANDED,
    //       which is the only place a key is observable from outside. A run producing no record closes
    //       its writer without ever having started a multipart upload, so it publishes by whole-object
    //       put -- which is why this case can read the key from a put request rather than having to
    //       drive a five-mebibyte artifact to reach a completion call.
    /**
     * Asserts that a report artifact key names the run date, the type and both range bounds.
     *
     * @throws Exception if the publication raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("a report artifact key names the run date, the report type and both bounds")
    void aReportArtifactIsKeyedUnderItsFullInputSet() throws Exception {
        TransactionReportService reports = mock(TransactionReportService.class);
        when(reports.generateReport(any(), any(), any())).thenReturn(summary());
        S3Client s3 = storageAnsweringVersion("v-42");

        LocalDate runDate = LocalDate.parse(DATE_TOKEN);
        ReportArtifactPublisher.PublishedArtifact result =
                new ReportArtifactPublisher(reports, s3, BUCKET, new ReportArtifactLocator(REPORT_PREFIX))
                        .publish("Custom", LocalDate.of(2022, 7, 1), runDate, runDate);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(put.capture(), any(RequestBody.class));
        String expectedKey = REPORT_PREFIX + "dt=" + DATE_TOKEN + "/type=custom/from=2022-07-01/to="
                + DATE_TOKEN + "/" + ReportArtifactPublisher.REPORT_OBJECT;
        assertThat(put.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(put.getValue().key())
                .as("the key names every input that decides the artifact's content")
                .isEqualTo(expectedKey);
        verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));

        // WHY : Assumptions: the returned locator is asserted to agree with the key the client was
        //       handed, rather than merely being non-empty. A locator assembled from a different rule
        //       than the one the write used would name an object that does not exist, and only holding
        //       the two to each other detects that.
        assertThat(result.bucket()).isEqualTo(BUCKET);
        assertThat(result.key()).isEqualTo(expectedKey);
        assertThat(result.versionId()).isEqualTo("v-42");
        assertThat(result.locator()).isEqualTo("s3://" + BUCKET + "/" + expectedKey + "?versionId=v-42");
        assertThat(result.summary()).isEqualTo(summary());
    }

    // WHY : Assumptions: the two keys are compared for INEQUALITY, which is the whole content of the
    //       defect this case exists for. The nightly task keyed on its business date and the on-demand
    //       task keyed on its range's END date, so an on-demand report over any period ending on a
    //       nightly date wrote to the nightly artifact's exact key and replaced it silently. Asserting
    //       each key's shape separately would not have caught that -- both shapes were correct; it was
    //       their collision that was wrong.
    /**
     * Asserts that a period report ending on a nightly date does not overwrite the nightly artifact.
     *
     * @throws Exception if either publication raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("a period report ending on a nightly date keys apart from the nightly artifact")
    void aPeriodReportDoesNotCollideWithTheNightlyArtifact() throws Exception {
        TransactionReportService reports = mock(TransactionReportService.class);
        when(reports.generateReport(any(), any(), any())).thenReturn(summary());
        S3Client s3 = storageAnsweringVersion(null);
        ReportArtifactPublisher publisher =
                new ReportArtifactPublisher(reports, s3, BUCKET, new ReportArtifactLocator(REPORT_PREFIX));

        LocalDate runDate = LocalDate.parse(DATE_TOKEN);
        String nightly = publisher.publishDaily(runDate).key();
        String period = publisher.publish("monthly", LocalDate.of(2022, 7, 1), runDate, runDate).key();

        assertThat(nightly).isNotEqualTo(period);
        assertThat(nightly)
                .as("the scheduled run publishes under its own type token")
                .contains("type=" + ReportArtifactPublisher.DAILY_REPORT_TYPE);
        assertThat(period).contains("type=monthly");
    }

    // WHY : Assumptions: the refusal is asserted to happen BEFORE any generation or storage call, not
    //       merely to happen. The type reaches an object key, so a value admitted and then rejected
    //       after the generator had streamed an artifact would already have written it somewhere.
    /**
     * Asserts that a report type outside the closed domain is refused before any work starts.
     *
     * @throws Exception if the mock setup raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("a report type outside the closed domain is refused before any write")
    void anUnknownReportTypeIsRefusedBeforeAnyWrite() throws Exception {
        TransactionReportService reports = mock(TransactionReportService.class);
        S3Client s3 = mock(S3Client.class);
        ReportArtifactPublisher publisher =
                new ReportArtifactPublisher(reports, s3, BUCKET, new ReportArtifactLocator(REPORT_PREFIX));
        LocalDate runDate = LocalDate.parse(DATE_TOKEN);

        for (String rejected : List.of("", "   ", "quarterly",
                "Custom\nevent=reporting.report.produced records=0", "../../etc")) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the type is part of the object key, so an unknown one is not publishable")
                    .isThrownBy(() -> publisher.publish(rejected, runDate, runDate, runDate));
        }

        verify(reports, never()).generateReport(any(), any(), any());
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    // WHY : Assumptions: the four accepted tokens are exercised in a capitalisation an operator might
    //       type, because the argument travels through a command line and the state machine forwards a
    //       lower-cased form. One key per report is only true if the canonicalisation holds.
    /**
     * Asserts that the accepted types canonicalise to one lower-case token each.
     *
     * @throws Exception if a publication raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("an accepted report type canonicalises to one lower-case key token")
    void anAcceptedReportTypeCanonicalises() throws Exception {
        TransactionReportService reports = mock(TransactionReportService.class);
        when(reports.generateReport(any(), any(), any())).thenReturn(summary());
        S3Client s3 = storageAnsweringVersion(null);
        ReportArtifactPublisher publisher =
                new ReportArtifactPublisher(reports, s3, BUCKET, new ReportArtifactLocator(REPORT_PREFIX));
        LocalDate runDate = LocalDate.parse(DATE_TOKEN);

        for (String accepted : List.of("MONTHLY", "Yearly", " custom ",
                ReportArtifactPublisher.DAILY_REPORT_TYPE)) {
            assertThat(publisher.publish(accepted, runDate, runDate, runDate).key())
                    .contains("type=" + accepted.trim().toLowerCase(java.util.Locale.ROOT));
        }
    }

    // WHY : Assumptions: the absence of a version is asserted as its own case, because the production
    //       bucket is versioned and every local and test bucket is not -- so the unversioned answer is
    //       the one a developer meets first, and a locator that appended a null there would name an
    //       object nothing can fetch.
    /**
     * Asserts that an unversioned bucket yields a locator without a version fragment.
     *
     * @throws Exception if the publication raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("an unversioned bucket yields a locator carrying no version fragment")
    void anUnversionedBucketYieldsAPlainLocator() throws Exception {
        TransactionReportService reports = mock(TransactionReportService.class);
        when(reports.generateReport(any(), any(), any())).thenReturn(summary());
        S3Client s3 = storageAnsweringVersion(null);

        LocalDate runDate = LocalDate.parse(DATE_TOKEN);
        ReportArtifactPublisher.PublishedArtifact result =
                new ReportArtifactPublisher(reports, s3, BUCKET, new ReportArtifactLocator(REPORT_PREFIX))
                        .publishDaily(runDate);

        assertThat(result.versionId()).isNull();
        assertThat(result.locator())
                .isEqualTo("s3://" + BUCKET + "/" + result.key())
                .doesNotContain("versionId");
    }

    // WHY : Assumptions: the nightly publication is asserted to write TWO keys, and the second is
    //       asserted by its exact generation coordinate. The reference writes TRANREPT(+1) against a
    //       generation base defined at app/jcl/DEFGDGB.jcl:37-39, and until this publication existed
    //       that family had no production writer -- so the prefix and the five-generation lifecycle rule
    //       infra/modules/s3-datasets provisions for it governed nothing at all.
    /**
     * Asserts that the nightly report lands on both its request-scoped key and its generation key.
     *
     * @throws Exception if the publication raises, which the assertions below would not reach
     */
    @Test
    @DisplayName("the nightly report lands on both its request-scoped key and its generation key")
    void theNightlyReportIsPublishedToBothKeys() throws Exception {
        TransactionReportService reports = mock(TransactionReportService.class);
        when(reports.generateReport(any(), any(), any())).thenReturn(summary());
        S3Client s3 = storageAnsweringVersion(null);

        LocalDate runDate = LocalDate.parse(DATE_TOKEN);
        ReportArtifactPublisher.PublishedArtifact result =
                new ReportArtifactPublisher(reports, s3, BUCKET,
                        new ReportArtifactLocator(REPORT_PREFIX)).publishDaily(runDate);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).putObject(put.capture(), any(RequestBody.class));
        String generationKey = ReportArtifactPublisher.TRANREPT_DOMAIN + "/"
                + ReportArtifactPublisher.TRANREPT_DATASET + "/dt=" + DATE_TOKEN + "/gen=0001/"
                + ReportArtifactPublisher.TRANREPT_GENERATION_OBJECT;
        assertThat(put.getAllValues().stream().map(PutObjectRequest::key).toList())
                .as("one generation pass publishes the request-scoped key and the generation key")
                .containsExactlyInAnyOrder(result.key(), generationKey);

        // WHY : Assumptions: the returned locator is asserted to remain the REQUEST-SCOPED key, because
        //       every existing caller and every runbook addresses a nightly report by its range. Widening
        //       the return to the generation key would have moved a published contract while the finding
        //       being closed asked only for the missing family to gain a writer.
        assertThat(result.key()).startsWith(REPORT_PREFIX);
    }

    // WHY : Assumptions: an occupied date is exercised as its own case, because allocating over a
    //       generation that already holds bytes is the failure a numbering scheme exists to prevent, and
    //       the empty-listing case above cannot detect it -- it allocates the first number either way.
    /**
     * Asserts that the nightly generation is numbered above the highest one the date already holds.
     *
     * @throws Exception if the publication raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("the nightly generation is numbered above the highest one already present")
    void theNightlyGenerationFollowsTheHighestPresent() throws Exception {
        TransactionReportService reports = mock(TransactionReportService.class);
        when(reports.generateReport(any(), any(), any())).thenReturn(summary());
        S3Client s3 = storageAnsweringVersion(null);
        String datePrefix = ReportArtifactPublisher.TRANREPT_DOMAIN + "/"
                + ReportArtifactPublisher.TRANREPT_DATASET + "/dt=" + DATE_TOKEN + "/";
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(
                                S3Object.builder().key(datePrefix + "gen=0001/tranrept.txt").build(),
                                S3Object.builder().key(datePrefix + "gen=0003/tranrept.txt").build(),
                                S3Object.builder().key(datePrefix + "not-a-generation").build())
                        .build());

        new ReportArtifactPublisher(reports, s3, BUCKET, new ReportArtifactLocator(REPORT_PREFIX))
                .publishDaily(LocalDate.parse(DATE_TOKEN));

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getAllValues().stream().map(PutObjectRequest::key).toList())
                .as("the next generation follows the highest present, and an unnumbered key is ignored")
                .contains(datePrefix + "gen=0004/"
                        + ReportArtifactPublisher.TRANREPT_GENERATION_OBJECT);
    }

    // WHY : Assumptions: the statement task's objects are asserted by KEY and by COUNT, because the
    //       reference produces exactly two datasets for a whole run -- one eighty-column plain-text and
    //       one hundred-column markup -- and a task that wrote one object per statement would still
    //       satisfy an assertion that only counted records.
    // WHY : ⚠️ Refactoring Rationale: the ORDER is now asserted too, and it is the property a review
    //       found missing. The three objects went to fixed keys, so each became visible as it was
    //       written and a reader between two of them held one run's index over another run's artifact --
    //       a position that addressed an unrelated cardholder. The manifest is the run's commit, so
    //       asserting it is written LAST is asserting that no reader can see half a run; asserting the
    //       other three share ONE run prefix is asserting that a rerun cannot overwrite them.
    /**
     * Asserts that a run publishes its three objects under one run prefix and the manifest last.
     *
     * @throws Exception if the run raises, which the assertions below would not reach
     */
    @Test
    @DisplayName("a statement run publishes three run objects and then the manifest")
    void aStatementRunPublishesThreeObjectsThenTheManifest() throws Exception {
        StatementService statements = mock(StatementService.class);
        when(statements.generateStatements(any())).thenAnswer(invocation -> {
            StatementService.StatementSink sink = invocation.getArgument(0);
            sink.replaceArtifacts();
            return new StatementRunOutcome(0, List.of());
        });
        S3Client s3 = storageAnsweringVersion(null);

        new GenerateStatementsTask(statements, s3, BUCKET, STATEMENT_PREFIX)
                .run(Map.of(ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN));

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3, org.mockito.Mockito.times(4)).putObject(put.capture(), body.capture());
        List<String> keys = put.getAllValues().stream().map(PutObjectRequest::key).toList();
        assertThat(keys.get(3))
                .as("the manifest is the run's commit and is written after every object it names")
                .isEqualTo(StatementService.manifestKey(STATEMENT_PREFIX));

        String runId = publishedRunOf(body.getAllValues().get(3));
        assertThat(runId)
                .as("a run identifier is 32 lower-case hexadecimal characters, so no two runs collide")
                .matches("[0-9a-f]{" + StatementService.RUN_ID_LENGTH + "}");
        assertThat(keys.subList(0, 3))
                .as("the three objects of one run share that run's own prefix and overwrite nothing")
                .containsExactlyInAnyOrderElementsOf(expectedStatementKeys(runId));
    }

    // WHY : Refactoring Rationale: this is the case the retired shape could not pass at all. Its three
    //       writes went to fixed keys, so a run failing after the first one had already replaced part of
    //       the previous run and left a reader pairing objects from two of them. Nothing a failed run
    //       writes is addressable now, and the assertion is that the pointer never moved: the previous
    //       run stays whole and current, which is what makes a redrive of the state safe to attempt.
    /**
     * Asserts that a run failing before its objects are complete publishes no manifest.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a failed statement run publishes no manifest, leaving the previous run current")
    void aFailedStatementRunPublishesNoManifest() {
        StatementService statements = mock(StatementService.class);
        when(statements.generateStatements(any()))
                .thenThrow(new IllegalStateException("a cross-reference row names no customer"));
        S3Client s3 = storageAnsweringVersion(null);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new GenerateStatementsTask(statements, s3, BUCKET, STATEMENT_PREFIX)
                        .run(Map.of(ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN)));

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, org.mockito.Mockito.atLeast(0)).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getAllValues().stream().map(PutObjectRequest::key).toList())
                .as("a run that did not finish must not be published to a single reader")
                .doesNotContain(StatementService.manifestKey(STATEMENT_PREFIX));
    }

    // WHY : Refactoring Rationale: the case above fails the run in its GENERATOR, before any of the four
    //       writes is attempted, so it cannot distinguish an ordering guarantee from a run that simply
    //       never started writing. This one fails the run at its THIRD write -- the index -- which is the
    //       last point at which the two artifacts are already stored and only the pointer is outstanding,
    //       and therefore the one point where an implementation that published the manifest unaware of
    //       the index's outcome would leave a reader resolving a run whose positions do not exist. The
    //       failure is selected by KEY SHAPE rather than by call ordinal, so the case keeps asserting the
    //       index specifically if the two artifact writes are ever reordered between themselves.
    /**
     * Asserts that a failure publishing the run index leaves the manifest unwritten.
     *
     * <p>This case takes no parameter and yields no value.</p>
     */
    @Test
    @DisplayName("a failed index write publishes no manifest, so no reader resolves a positionless run")
    void aFailedIndexWritePublishesNoManifest() {
        StatementService statements = mock(StatementService.class);
        when(statements.generateStatements(any())).thenAnswer(invocation -> {
            StatementService.StatementSink sink = invocation.getArgument(0);
            sink.replaceArtifacts();
            return new StatementRunOutcome(
                    1, List.of(new StatementIndexEntry("a".repeat(64), 0L, 24L)));
        });
        S3Client s3 = storageRefusingKeysEndingIn(StatementService.INDEX_OBJECT);

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> new GenerateStatementsTask(statements, s3, BUCKET, STATEMENT_PREFIX)
                        .run(Map.of(ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN)));

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3, org.mockito.Mockito.atLeast(0)).putObject(put.capture(), any(RequestBody.class));
        List<String> keys = put.getAllValues().stream().map(PutObjectRequest::key).toList();
        assertThat(keys)
                .as("a run whose index did not store must not be named by the manifest any read resolves")
                .doesNotContain(StatementService.manifestKey(STATEMENT_PREFIX));
        assertThat(keys)
                .as("the index was attempted, so the run failed at the write this case is about")
                .anyMatch(key -> key.endsWith(StatementService.INDEX_OBJECT));
    }

    // WHY : Assumptions: the business date is tolerated as absent by the statement task and required by
    //       both report tasks, and the asymmetry is deliberate rather than an oversight. A statement run
    //       takes no date selector at all in the reference -- across the whole of CREASTMT.JCL there is
    //       no parameter field and no date symbol -- so the date reaches this task only to attribute its
    //       journal line, and refusing an absent one would refuse a run the reference performs.
    /**
     * Asserts that the statement task runs without a business date and still publishes its artifacts.
     *
     * @throws Exception if the run raises, which the assertions below would not reach
     */
    @Test
    @DisplayName("the statement task runs without a business date, because the reference takes none")
    void theStatementTaskRunsWithoutABusinessDate() throws Exception {
        StatementService statements = mock(StatementService.class);
        when(statements.generateStatements(any())).thenReturn(new StatementRunOutcome(0, List.of()));
        S3Client s3 = storageAnsweringVersion(null);

        new GenerateStatementsTask(statements, s3, BUCKET, STATEMENT_PREFIX).run(Map.of());

        verify(statements).generateStatements(any());
        verify(s3, org.mockito.Mockito.times(4))
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /**
     * Asserts that a task's own failure propagates, because the runner owns the exit-code rubric.
     *
     * @throws Exception if the mock setup raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("a publication failure propagates out of the task")
    void aPublicationFailurePropagates() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publishDaily(any()))
                .thenThrow(new IOException("the artifact could not be published"));

        assertThatExceptionOfType(IOException.class)
                .isThrownBy(() -> new GenerateReportsTask(publisher, balancePublisher()).run(Map.of(
                        ReportingTaskRunner.BUSINESS_DATE_PARAMETER, DATE_TOKEN)));
    }

    // WHY : Assumptions: the value is exercised through the task rather than through the sanitiser
    //       directly. The sanitiser has its own tests in the shared kernel; what is unproven here is
    //       that this task ROUTES the one caller-supplied string it journals through it, and only a call
    //       carrying a terminator all the way to the log statement can establish that.
    // WHY : Refactoring Rationale: the publisher is a MOCK here on purpose, and the case is deliberately
    //       kept even though a real publisher now refuses this value against its closed domain. The two
    //       controls answer different questions: the domain proves such a value cannot reach an object
    //       key, and this case proves that the journal line does not carry a caller's raw string even so.
    //       Deleting it would leave the sanitiser call with no test at all, and a later change that
    //       widened the domain would remove the remaining protection unnoticed.
    /**
     * Asserts that the on-demand task journals its report type through the sanitiser.
     *
     * @throws Exception if the task raises, which the assertion below would not reach
     */
    @Test
    @DisplayName("a report type carrying a line terminator is journalled through the sanitiser")
    void aReportTypeCarryingATerminatorIsSanitised() throws Exception {
        ReportArtifactPublisher publisher = mock(ReportArtifactPublisher.class);
        when(publisher.publish(any(), any(), any(), any())).thenReturn(published());

        Map<String, String> parameters = new HashMap<>();
        parameters.put(ReportingTaskRunner.START_DATE_PARAMETER, "2022-07-01");
        parameters.put(ReportingTaskRunner.END_DATE_PARAMETER, DATE_TOKEN);
        parameters.put(ReportingTaskRunner.REPORT_TYPE_PARAMETER,
                "Custom\nevent=reporting.report.produced records=0");

        new GenerateAdHocReportTask(publisher).run(parameters);

        verify(publisher).publish(any(), any(), any(), any());
    }

    // WHY : Refactoring Rationale: this case asserted that an absent report type "does not stop an
    //       on-demand run", which was true while the type was only journalled. It is now part of the
    //       artifact key, so a run with no type has nowhere defensible to publish and is refused -- the
    //       runner already requires the option in any case, so the refusal is reachable only by a caller
    //       driving the task directly. Asserting the old tolerance would assert that an on-demand report
    //       can be published under a key that does not say what it is.
    /**
     * Asserts that an absent report type is refused, because the artifact key names the type.
     */
    @Test
    @DisplayName("an absent report type is refused because the artifact key names the type")
    void anAbsentReportTypeIsRefused() {
        TransactionReportService reports = mock(TransactionReportService.class);
        S3Client s3 = mock(S3Client.class);
        ReportArtifactPublisher publisher =
                new ReportArtifactPublisher(reports, s3, BUCKET, new ReportArtifactLocator(REPORT_PREFIX));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new GenerateAdHocReportTask(publisher).run(Map.of(
                        ReportingTaskRunner.START_DATE_PARAMETER, "2022-07-01",
                        ReportingTaskRunner.END_DATE_PARAMETER, DATE_TOKEN)));
    }

    /**
     * Builds one generation summary standing for a run that wrote a handful of records.
     *
     * @return the summary
     */
    private static TransactionReportService.ReportGenerationSummary summary() {
        return new TransactionReportService.ReportGenerationSummary(
                12L, 7L, 1L, 2L, Money.of("-1234.56"));
    }

    /**
     * Builds one published-artifact locator standing for a completed publication.
     *
     * <p>Assumptions: the key here is a stand-in and is deliberately NOT assembled by the rule under
     * test. A helper that rebuilt the real key would agree with whatever it was copied from, which is the
     * failure mode the key cases above exist to detect.</p>
     *
     * @return the locator
     */
    private static ReportArtifactPublisher.PublishedArtifact published() {
        return new ReportArtifactPublisher.PublishedArtifact(
                summary(), BUCKET, REPORT_PREFIX + "stand-in/artifact.txt", null);
    }

    /**
     * Builds a storage client whose whole-object put answers a given version.
     *
     * <p>Assumptions: the put is stubbed rather than left at its default, because a Mockito default
     * answers {@code null} for the response object and the writer reads a member off it -- so a default
     * would fail with a null dereference rather than exercising the version capture.</p>
     *
     * <p>Assumptions: the generation listing is stubbed to an EMPTY page, so every nightly publication
     * driven through this helper allocates the first generation of its date. The listing is what the
     * nightly path reads to number its generation coordinate, and a Mockito default answers {@code null}
     * there -- so leaving it unstubbed would fail on a null response rather than on the property each
     * case is about.</p>
     *
     * @param versionId the version to answer, or {@code null} to stand for an unversioned bucket
     * @return the client
     */
    private static S3Client storageAnsweringVersion(String versionId) {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId(versionId).build());
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().build());
        return s3;
    }

    /**
     * Builds a storage client that refuses any put whose key ends with a given object name.
     *
     * <p>Assumptions: the refusal is keyed on the object NAME rather than on the call ordinal, because
     * the run prefix carries an identifier minted inside the task under test and is therefore not known
     * to the caller. Selecting by name also keeps the case pinned to the write it is about if the two
     * artifact writes are ever reordered relative to each other.</p>
     *
     * <p>Assumptions: {@code SdkClientException} stands for the store refusing the write, because that is
     * the shape the real client raises when a put cannot be completed. The writer translates it into an
     * {@code IOException} before the task sees it, so a case built on this helper asserts the translated
     * type rather than this one -- which is the propagation path the task actually meets.</p>
     *
     * @param objectName the trailing object name whose put must fail; must not be {@code null}
     * @return the client
     */
    private static S3Client storageRefusingKeysEndingIn(String objectName) {
        S3Client s3 = mock(S3Client.class);
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(invocation -> {
                    PutObjectRequest request = invocation.getArgument(0);
                    if (request.key().endsWith(objectName)) {
                        throw SdkClientException.create("the object store refused " + request.key());
                    }
                    return PutObjectResponse.builder().build();
                });
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().build());
        return s3;
    }

    // WHY : Assumptions: the stand-in publisher answers a real summary rather than being left at its
    //       Mockito default. The nightly task reads the returned locator and line count into its journal
    //       line, so a default null answer would fail every nightly case with a null dereference from a
    //       collaborator none of them is about.
    /**
     * Builds a stand-in category-balance publisher answering one completed publication.
     *
     * @return the publisher
     * @throws IOException never, and declared only because the stubbed member does
     */
    private static CategoryBalanceArtifactPublisher balancePublisher() throws IOException {
        CategoryBalanceArtifactPublisher publisher = mock(CategoryBalanceArtifactPublisher.class);
        when(publisher.publish()).thenReturn(
                new CategoryBalanceArtifactPublisher.PublishedCategoryBalanceReport(
                        new CategoryBalanceReportService.CategoryBalanceReportSummary(
                                3L, Money.of("40.50")),
                        BUCKET,
                        CATEGORY_BALANCE_PREFIX + CategoryBalanceArtifactPublisher.REPORT_OBJECT,
                        null));
        return publisher;
    }

    /**
     * Renders the three object keys one statement run publishes, for readability in a failure message.
     *
     * @param runId the run identifier the manifest names, recovered from the run under assertion
     * @return the three expected keys, in the order the reference declares its datasets
     */
    private static List<String> expectedStatementKeys(String runId) {
        // WHY : ⚠️ Refactoring Rationale: a run publishes THREE objects, where it published two, and all
        //       three now sit under the run's OWN prefix. The third is the run index, and it is asserted
        //       here rather than in a case of its own because the property worth pinning is the WHOLE
        //       set a run writes -- a case naming only the index would pass while one of the two
        //       artifacts silently stopped being written. The prefix is composed by the value under test
        //       rather than spelled out, so a change of convention cannot pass by being made twice.
        String runPrefix = StatementService.runKeyPrefix(STATEMENT_PREFIX, runId);
        return List.of(
                runPrefix + S3StatementSink.PLAIN_TEXT_OBJECT,
                runPrefix + S3StatementSink.HTML_OBJECT,
                runPrefix + StatementService.INDEX_OBJECT);
    }

    /**
     * Reads back the run identifier a captured manifest write names.
     *
     * <p>Assumptions: the BODY is read rather than the identifier being taken from one of the object
     * keys. The manifest and the run objects are written by separate calls, and a task that minted a
     * second identifier for the manifest would satisfy every key assertion while publishing a run whose
     * objects no reader could find.</p>
     *
     * @param body the request body the manifest write was handed; must not be {@code null}
     * @return the identifier the manifest names, with its line ending removed
     * @throws IOException if the captured body cannot be read
     */
    private static String publishedRunOf(RequestBody body) throws IOException {
        try (var stream = body.contentStreamProvider().newStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
    }

    /**
     * The narrowest configuration that registers this package's components.
     *
     * <p>Assumptions: declared as a nested type rather than as a file of its own, which keeps this
     * directory to the two files it needs, and scoped to this one package for the reason recorded on the
     * context runner above.</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ComponentScan("com.carddemo.reporting.task")
    static class TaskScanConfiguration {
    }
}
