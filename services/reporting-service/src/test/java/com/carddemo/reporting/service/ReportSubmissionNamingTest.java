package com.carddemo.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.reporting.dto.ReportRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

/**
 * Tests the identity a submission is started under, which decides which repeat submissions are refused.
 *
 * <p>Purpose: the execution name is the ONLY thing that decides whether a second submission of the same
 * report over the same range starts a run or is rejected by the orchestrator as a duplicate, and it is
 * composed privately inside {@link ReportExecutionService#start}. It is therefore observable only in the
 * request handed to the orchestrator client, which is what every case here asserts on.
 *
 * <p>Refactoring Rationale: these cases sit in their own class beside {@link ReportExecutionServiceTest}
 * rather than inside it, and the split is by SUBJECT rather than by size. This class asserts one thing --
 * the name and the idempotency key that composes it -- over a service built once with a fixed clock and an
 * orchestrator that accepts every start. Its sibling asserts the request edge: which mark resolves to which
 * report, which answer confirms, which range each preset covers, and which refusals the reference raises;
 * those cases build the service per case around a pinned instant because the range under test depends on
 * it. Merging the two would have meant one fixture serving two incompatible needs, and the module already
 * carries this shape for the payment path, where branch order and committed effects are separate classes
 * over one service.</p>
 *
 * <p>Assumptions: the orchestrator client is mocked and the name is read out of the captured request
 * rather than asserted against a literal. The name carries a random component by design when no key is
 * supplied, so a case asserting an exact string could only pass by removing the property under test.
 *
 * <p>Assumptions: the clock is fixed, because the response carries a submission timestamp; the name
 * itself deliberately does NOT come from the clock, and one case asserts that two submissions inside the
 * same fixed instant still differ -- which is the property a timestamp-derived name would not have.
 */
class ReportSubmissionNamingTest {

    /**
     * A fixed instant, so the submitted-at stamp is reproducible and the clock cannot vary between the
     * two submissions a repeat case makes.
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T09:14:27.481903Z");

    /** The state machine the service is configured to start executions on. */
    private static final String MACHINE_ARN =
            "arn:aws:states:eu-west-2:000000000000:stateMachine:carddemo-report";

    /** The confirming answer, so every submission below reaches the start rather than the gate. */
    private static final String MARK = "Y";

    private SfnClient sfnClient;

    private ReportExecutionService service;

    /**
     * Builds the service over a mocked orchestrator client that accepts every start.
     */
    @BeforeEach
    void setUp() {
        sfnClient = mock(SfnClient.class);
        when(sfnClient.startExecution(any(StartExecutionRequest.class)))
                .thenReturn(StartExecutionResponse.builder()
                        .executionArn(MACHINE_ARN + ":run")
                        .build());
        service = new ReportExecutionService(
                sfnClient, MACHINE_ARN, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
    }

    // WHY : Assumptions: this is the case the whole change exists for, so it asserts the two names are
    //       DIFFERENT rather than asserting one name's shape. A deterministic name was unique to the
    //       report and the range for the ninety days the orchestrator remembers a completed execution,
    //       so the second submission of a range was refused however legitimate it was -- a rerun after
    //       the underlying rows were corrected, or a rerun after the output objects were deleted. Two
    //       submissions inside one fixed instant are used deliberately: a name derived from a clock
    //       would collide here, so this case also forecloses that as the fix.
    /**
     * Asserts that two submissions of the same report and range are two distinct executions.
     */
    @Test
    @DisplayName("two submissions of one report and range start two distinct executions")
    void twoSubmissionsOfTheSameReportAndRangeAreDistinct() {
        submit(null);
        submit(null);

        List<String> names = startedNames(2);
        assertThat(names.get(0))
                .as("a second submission of the same report over the same range must be its own run")
                .isNotEqualTo(names.get(1));
    }

    // WHY : Assumptions: the retry protection the previous shape provided is asserted to STILL be
    //       reachable, not merely described. Without this case the change could be satisfied by making
    //       every name unique, which would leave a client that retried an abandoned call starting a
    //       second run -- the failure the previous rationale correctly identified.
    /**
     * Asserts that resending one submission key produces the identical execution name.
     */
    @Test
    @DisplayName("the same submission key produces the same execution name")
    void theSameSubmissionKeyReproducesTheName() {
        submit("monthly-2022-07-retry-1");
        submit("monthly-2022-07-retry-1");

        List<String> names = startedNames(2);
        assertThat(names.get(0))
                .as("a retry carrying the key of the attempt it retries must name that same execution")
                .isEqualTo(names.get(1));
    }

    /**
     * Asserts that two different submission keys name two different executions.
     */
    @Test
    @DisplayName("two different submission keys name two different executions")
    void differentSubmissionKeysNameDifferentExecutions() {
        submit("first-attempt");
        submit("second-attempt");

        List<String> names = startedNames(2);
        assertThat(names.get(0)).isNotEqualTo(names.get(1));
    }

    // WHY : Assumptions: the report type and both bounds are asserted to still be PRESENT in the name.
    //       They are what makes an execution recognisable in the orchestrator's console, and a change
    //       that appended an identifier could have replaced them instead of extending them.
    /**
     * Asserts the name still opens with the report type and both range bounds.
     */
    @Test
    @DisplayName("the execution name still carries the report type and both bounds")
    void theNameStillCarriesTheReportTypeAndBothBounds() {
        submit(null);

        assertThat(startedNames(1).get(0))
                .startsWith("monthly-2022-07-01-2022-07-31-");
    }

    // WHY : Assumptions: the ceiling is asserted against the orchestrator's own published limit rather
    //       than against the key ceiling, because the key ceiling exists only to keep the COMPOSED name
    //       inside that limit. Asserting the derived figure would restate the constant; asserting the
    //       composed length checks the derivation.
    /**
     * Asserts a widest-case name stays inside the orchestrator's name limit.
     */
    @Test
    @DisplayName("a name built from the longest admissible key stays inside the orchestrator's limit")
    void theWidestNameFitsTheOrchestratorLimit() {
        String widestKey = "k".repeat(ReportExecutionService.IDEMPOTENCY_KEY_MAX_LENGTH);
        submit(widestKey);

        assertThat(startedNames(1).get(0).length())
                .isLessThanOrEqualTo(ReportExecutionService.EXECUTION_NAME_LIMIT);
    }

    /**
     * Asserts a key carrying a character an execution name may not hold is refused, and nothing starts.
     */
    @Test
    @DisplayName("a key carrying an unadmitted character is refused and starts nothing")
    void aKeyWithAnUnadmittedCharacterIsRefused() {
        assertThatThrownBy(() -> submit("has a space"))
                .isInstanceOf(ClientInputException.class)
                .hasMessageContaining("submission key");

        verify(sfnClient, times(0)).startExecution(any(StartExecutionRequest.class));
    }

    /**
     * Asserts an over-long key is refused, named against the header the caller sent it in.
     */
    @Test
    @DisplayName("an over-long key is refused naming the header it was sent in")
    void anOverLongKeyIsRefused() {
        String tooLong = "k".repeat(ReportExecutionService.IDEMPOTENCY_KEY_MAX_LENGTH + 1);

        assertThatThrownBy(() -> submit(tooLong))
                .isInstanceOf(ClientInputException.class)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ClientInputException.class))
                .satisfies(refused -> {
                    assertThat(refused.field())
                            .isEqualTo(ReportExecutionService.IDEMPOTENCY_KEY_FIELD);
                    assertThat(refused.code()).isEqualTo(ApiError.CODE_VALIDATION);
                });

        verify(sfnClient, times(0)).startExecution(any(StartExecutionRequest.class));
    }

    // WHY : Assumptions: a blank key is treated as no key rather than as a key whose value is the empty
    //       string. A header a client sends unset is indistinguishable from one it omits, so treating
    //       blank as a value would make every such client share one execution name and see its second
    //       submission refused -- the very lockout this change removes.
    /**
     * Asserts a blank key reads as no key, so submissions carrying one stay distinct.
     */
    @Test
    @DisplayName("a blank key reads as no key and leaves submissions distinct")
    void aBlankKeyReadsAsNoKey() {
        submit("   ");
        submit("");

        List<String> names = startedNames(2);
        assertThat(names.get(0)).isNotEqualTo(names.get(1));
    }

    // WHY : Assumptions: sixteen unkeyed submissions are made rather than two, because two distinct
    //       names can be produced by a weak distinguisher that still collides in practice. Asserting
    //       every name in a batch is distinct exercises the generator rather than one draw from it.
    /**
     * Asserts a batch of unkeyed submissions produces no repeated execution name.
     */
    @Test
    @DisplayName("a batch of unkeyed submissions produces no repeated name")
    void unkeyedSubmissionsDoNotRepeatAName() {
        int submissions = 16;
        IntStream.range(0, submissions).forEach(each -> submit(null));

        assertThat(startedNames(submissions))
                .as("every unkeyed submission must have its own identity")
                .doesNotHaveDuplicates();
    }

    /**
     * Submits a confirmed monthly report over a fixed range.
     *
     * <p>Assumptions: the range is passed explicitly rather than resolved, because the resolution is a
     * separate concern with its own cases and holding it fixed here keeps every assertion about the name
     * about the name alone.
     *
     * @param idempotencyKey the submission key to send, or {@code null} to send none
     */
    private void submit(String idempotencyKey) {
        service.start(
                confirmedMonthlyRequest(),
                ReportExecutionService.MONTHLY_REPORT_NAME,
                LocalDate.of(2022, 7, 1),
                LocalDate.of(2022, 7, 31),
                idempotencyKey);
    }

    /**
     * Reads the execution names the orchestrator client was asked to start, in order.
     *
     * @param expected the number of starts the case made; the capture is asserted to hold exactly that
     *     many, so a case cannot silently read fewer names than it submitted
     * @return the captured names in submission order; never {@code null}
     */
    private List<String> startedNames(int expected) {
        ArgumentCaptor<StartExecutionRequest> captor =
                ArgumentCaptor.forClass(StartExecutionRequest.class);
        verify(sfnClient, times(expected)).startExecution(captor.capture());
        return captor.getAllValues().stream().map(StartExecutionRequest::name).toList();
    }

    /**
     * Builds a confirmed monthly report request.
     *
     * @return a request whose monthly selector is marked and whose confirmation answers yes; never
     *     {@code null}
     */
    private static ReportRequest confirmedMonthlyRequest() {
        return new ReportRequest(
                null, null, null, null, null, null, MARK, null, null, null, null, MARK, null);
    }
}
