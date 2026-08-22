package com.carddemo.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.error.ApiError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Verifies the three properties that make a container-level refusal usable to a JSON client -- the
 * envelope's shape, the correlation identity on the response, and the media type it is served as --
 * and the guard that stops this valve from ever displacing a real answer.
 *
 * <p>Assumptions: the assertions are written against the two seams the valve is built around rather
 * than against the container-typed method that calls them. That method's parameters are the embedded
 * container's own request and response, which cannot be constructed outside a running connector
 * without wiring a protocol processor to them; the decision it makes and the bytes it writes are
 * therefore expressed as a guard taking three readings and a publication taking a servlet response,
 * both of which are assertable here. What is left to the runtime is only the extraction of those
 * three readings from a live response, and the finding this class answers was reproduced against a
 * live service, so that extraction is exercised there.</p>
 *
 * <p>Assumptions: the envelope is asserted as the bytes a caller receives, not as a record. A test
 * that asserted on the record would pass for a serialiser configuration that renamed a member,
 * dropped a null one or rendered the timestamp in a different form, and every one of those is a
 * change a JSON client would see.</p>
 *
 * <p>Assumptions: the traversal-shaped text asserted absent below is a literal in this test and is
 * never handed to the valve, because the valve is given a status and never a target. Its absence is
 * asserted to pin the decision that the target is withheld rather than masked -- a later change that
 * began populating the path member from a request would fail here.</p>
 */
class RejectedRequestErrorReportValveTest {

    /** A fixed reading, so the envelope's timestamp is a constant rather than a wall clock. */
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-02-01T09:15:30.123456Z"),
            ZoneOffset.UTC);

    /** The timestamp the fixed reading renders to in the contract's twenty-six-character form. */
    private static final String EXPECTED_TIMESTAMP = "2026-02-01 09:15:30.123456";

    /** An identity of the published shape, supplied where the test is not asserting the minting. */
    private static final String SAMPLE_ID = "CD0123456789ABCDEF012345";

    /** The statuses this kernel publishes both a code and a sentence for. */
    private static final int[] ANSWERED_STATUSES = {400, 403, 404, 405, 406, 415, 500};

    /** The statuses this valve declines, each for a reason recorded on the selector. */
    private static final int[] DECLINED_STATUSES = {409, 413, 503, 414, 501, 505};

    /** A target shaped like the one the finding reported, asserted absent from every envelope. */
    private static final String REJECTED_TARGET_SHAPE = "..%2F..%2Fetc%2Fpasswd";

    /**
     * Confirms the envelope carries the fleet's whole field set, its published code and its timestamp
     * form.
     *
     * <p>Assumptions: every member is asserted by name as it appears on the wire, including the two
     * that are empty and the one that is absent. A client parses the field SET, so a member silently
     * dropped when it has no value is a change to the contract even though no value was lost.</p>
     */
    @Test
    @DisplayName("the envelope carries every member, the published code and the contract timestamp")
    void theEnvelopeCarriesTheFleetsFieldSet() {
        String json = new String(
                RejectedRequestErrorReportValve.renderRefusal(400, SAMPLE_ID, FIXED),
                StandardCharsets.UTF_8);

        assertThat(json).contains(
                "\"code\":",
                "\"secondaryCode\":",
                "\"message\":",
                "\"severity\":",
                "\"subsystem\":",
                "\"status\":",
                "\"correlationId\":",
                "\"path\":",
                "\"timestamp\":",
                "\"fieldErrors\":",
                "\"abend\":");

        assertThat(json).contains(
                "\"code\":\"" + ApiError.CODE_VALIDATION + "\"",
                "\"secondaryCode\":\"\"",
                "\"severity\":\"WARNING\"",
                "\"subsystem\":\"APPLICATION\"",
                "\"status\":400",
                "\"correlationId\":\"" + SAMPLE_ID + "\"",
                "\"timestamp\":\"" + EXPECTED_TIMESTAMP + "\"");
    }

    /**
     * Confirms nothing about the refused request reaches the body.
     *
     * <p>Assumptions: the path member is asserted to be present and empty rather than asserted
     * absent, because emptiness is what keeps the field set unchanged while the value is withheld.</p>
     */
    @Test
    @DisplayName("the envelope withholds the refused target and every diagnostic")
    void theEnvelopeWithholdsTheRefusedTarget() {
        String json = new String(
                RejectedRequestErrorReportValve.renderRefusal(400, SAMPLE_ID, FIXED),
                StandardCharsets.UTF_8);

        assertThat(json).contains("\"path\":\"\"");
        assertThat(json).doesNotContain(REJECTED_TARGET_SHAPE);
        assertThat(json).doesNotContain("passwd");
        assertThat(json).doesNotContain("Tomcat");
        assertThat(json).doesNotContain("Exception");
        assertThat(json).doesNotContain("\"abend\":{");
    }

    /**
     * Confirms every answered status carries the code published for that status, and no other status
     * is answered at all.
     *
     * <p>Assumptions: the code is asserted to agree with the status numerically rather than compared
     * against a second table written here. That agreement is the property the selector's own note
     * argues for, and a table would restate the production mapping rather than check it.</p>
     */
    @Test
    @DisplayName("each published status answers with its own code and every other status declines")
    void eachPublishedStatusAnswersWithItsOwnCode() {
        for (int status : ANSWERED_STATUSES) {
            byte[] body = RejectedRequestErrorReportValve.renderRefusal(status, SAMPLE_ID, FIXED);
            assertThat(body).as("status " + status + " must be answered").isNotNull();

            String json = new String(body, StandardCharsets.UTF_8);
            assertThat(json)
                    .as("status " + status + " must carry the code published for it")
                    .contains("\"code\":\"CARDDEMO-0" + status + "\"")
                    .contains("\"status\":" + status);
            assertThat(json)
                    .as("status " + status + " must carry a sentence")
                    .doesNotContain("\"message\":\"\"");
        }

        for (int status : DECLINED_STATUSES) {
            assertThat(RejectedRequestErrorReportValve.renderRefusal(status, SAMPLE_ID, FIXED))
                    .as("status " + status + " has no published envelope and must be declined")
                    .isNull();
        }
    }

    /**
     * Confirms a minted identity satisfies the correlation contract the filter publishes.
     *
     * <p>Assumptions: conformance is asserted through the filter's own published predicate rather
     * than by restating its alphabet here, which is what makes the two transports provably mint one
     * format. The width is asserted against the filter's published constant for the same reason.</p>
     */
    @Test
    @DisplayName("a minted identity conforms to the published correlation contract")
    void aMintedIdentityConformsToThePublishedContract() {
        String first = RejectedRequestErrorReportValve.mintCorrelationId();
        String second = RejectedRequestErrorReportValve.mintCorrelationId();

        assertThat(first).hasSize(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);
        assertThat(CorrelationIdFilter.isConformingCorrelationId(first)).isTrue();
        assertThat(first).matches("CD[0-9A-F]{22}");
        assertThat(second).isNotEqualTo(first);
    }

    /**
     * Confirms the guard answers only a refusal that has not already answered.
     *
     * <p>Assumptions: all four declining reasons are asserted, not a representative one. Each is a
     * separate way for this valve to overwrite a real answer, and a guard written for three of them
     * would leave the fourth open.</p>
     */
    @Test
    @DisplayName("the guard declines a non-refusal, a committed response and a written one")
    void theGuardDeclinesEveryResponseThatMayHaveAnswered() {
        assertThat(RejectedRequestErrorReportValve.isRenderable(400, false, 0L)).isTrue();
        assertThat(RejectedRequestErrorReportValve.isRenderable(500, false, 0L)).isTrue();
        assertThat(RejectedRequestErrorReportValve.isRenderable(399, false, 0L)).isFalse();
        assertThat(RejectedRequestErrorReportValve.isRenderable(200, false, 0L)).isFalse();
        assertThat(RejectedRequestErrorReportValve.isRenderable(400, true, 0L)).isFalse();
        assertThat(RejectedRequestErrorReportValve.isRenderable(400, false, 17L)).isFalse();
    }

    /**
     * Confirms the envelope is served as JSON, with the correlation header and the container's own
     * status.
     *
     * @throws IOException if the response's body cannot be written, which fails the test
     */
    @Test
    @DisplayName("the envelope is published as JSON carrying the correlation header and the status")
    void theEnvelopeIsPublishedAsJsonWithTheCorrelationHeader() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(400);
        byte[] body = RejectedRequestErrorReportValve.renderRefusal(400, SAMPLE_ID, FIXED);

        assertThat(RejectedRequestErrorReportValve.publish(response, SAMPLE_ID, body)).isTrue();

        assertThat(response.getContentType()).startsWith(ApiError.MEDIA_TYPE);
        assertThat(response.getCharacterEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(response.getContentLength()).isEqualTo(body.length);
        assertThat(response.getContentAsByteArray()).isEqualTo(body);
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(SAMPLE_ID);
        assertThat(response.getStatus())
                .as("the status the container chose must be carried, never substituted")
                .isEqualTo(400);
    }

    /**
     * Confirms a response that has already been sent is left exactly as it was.
     *
     * <p>Assumptions: the absence of the header is asserted alongside the absence of the body,
     * because a header written onto a committed response is discarded without an error and a
     * publication that wrote one anyway would look successful while achieving nothing.</p>
     *
     * @throws IOException if the response's body cannot be written, which fails the test
     */
    @Test
    @DisplayName("an already-committed response is left untouched")
    void anAlreadyCommittedResponseIsLeftUntouched() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(400);
        response.flushBuffer();
        byte[] body = RejectedRequestErrorReportValve.renderRefusal(400, SAMPLE_ID, FIXED);

        assertThat(RejectedRequestErrorReportValve.publish(response, SAMPLE_ID, body)).isFalse();

        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNull();
        assertThat(response.getStatus()).isEqualTo(400);
    }

    /**
     * Confirms the container's own reporting is suppressed on every instance, however it was built.
     *
     * <p>Assumptions: both constructors are asserted, because the container builds one of them itself
     * and that instance is the one no configuration reaches. An unsuppressed instance would emit the
     * server identification and a diagnostic report on every status this valve declines.</p>
     */
    @Test
    @DisplayName("both constructors suppress the container's report and server identification")
    void bothConstructorsSuppressTheContainersOwnReport() {
        RejectedRequestErrorReportValve supplied = new RejectedRequestErrorReportValve(FIXED);
        assertThat(supplied.isShowReport()).isFalse();
        assertThat(supplied.isShowServerInfo()).isFalse();

        RejectedRequestErrorReportValve constructed = new RejectedRequestErrorReportValve();
        assertThat(constructed.isShowReport()).isFalse();
        assertThat(constructed.isShowServerInfo()).isFalse();
    }
}
