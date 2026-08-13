package com.carddemo.reporting.dto;

import com.carddemo.common.time.TimestampFormatter;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * What became of one submitted transaction report, and where to collect it.
 *
 * <h2>Purpose: the half of the lifecycle the submission acknowledgement could not carry</h2>
 *
 * <p>⚠️ Refactoring Rationale: this type exists because a review found the report lifecycle open at both
 * ends. {@code ReportSubmissionResponse} returns an orchestration handle at the moment a run is accepted --
 * correctly, since nothing has been produced yet -- and NO operation consumed that handle: a caller could
 * not learn whether the run was still going, had succeeded or had failed, and could not reach the document
 * it produced. Reading the date-range operations instead reports the CURRENT contents of the ledger, which
 * is not the same thing as the report a particular run rendered. This is what the handle is for.
 *
 * <h2>Assumptions: the status is the orchestrator's own, and the location is only present when real</h2>
 *
 * <p>The status is carried across from the orchestration by name rather than reduced to a smaller domain,
 * because the distinctions are ones an operator acts on -- a timed-out run is retried, an aborted run was
 * stopped deliberately. The artifact location and its write instant are present exactly when the store holds
 * the object, on the same terms as a statement location, so a location this type carries always resolves. A
 * run that succeeded and whose artifact a lifecycle rule has since expired reports the status with no
 * location, which is the truth rather than a link to nothing.
 *
 * <p>Assumptions: the three coordinates are echoed back. They are what the artifact's identity is made of --
 * a report type and an inclusive date range -- and two of the three report types resolve bounds the caller
 * never supplied, so echoing them is how a caller learns which range its run actually covered.
 *
 * <p>Assumptions: no monetary component and no cardholder value appears here. A report artifact is addressed
 * by type and range, neither of which names a person, which is why the location carries them in the clear
 * rather than behind an opaque selector -- the reasoning is recorded on
 * {@code com.carddemo.reporting.service.ReportArtifactLocator}.
 *
 * @param executionName the name identifying this run, as the submission returned it
 * @param status which state the orchestration reports the run in: {@code RUNNING}, {@code SUCCEEDED},
 *     {@code FAILED}, {@code TIMED_OUT}, {@code ABORTED} or {@code PENDING_REDRIVE}
 * @param startedAt when the run started, as a twenty-six-character stamp
 * @param stoppedAt when the run stopped, or {@code null} while it is still running
 * @param reportType the report type the run was started for, or {@code null} for a run started outside this
 *     surface -- the nightly schedule starts the same machine
 * @param startDate the inclusive lower bound of the range the run covered, or {@code null} on the same terms
 * @param endDate the inclusive upper bound, or {@code null} on the same terms
 * @param resultUri the path the produced report is collected from, or {@code null} when no artifact is
 *     stored for this run
 * @param resultGeneratedAt when the stored artifact was written, or {@code null} on the same terms as the
 *     location
 */
public record ReportExecutionStatusResponse(
        @Size(max = EXECUTION_NAME_WIDTH) String executionName,
        String status,
        @Size(max = TimestampFormatter.TIMESTAMP_LENGTH) String startedAt,
        @Size(max = TimestampFormatter.TIMESTAMP_LENGTH) String stoppedAt,
        @Size(max = REPORT_TYPE_WIDTH) String reportType,
        @Size(max = DATE_WIDTH) String startDate,
        @Size(max = DATE_WIDTH) String endDate,
        String resultUri,
        @Size(max = TimestampFormatter.TIMESTAMP_LENGTH) String resultGeneratedAt) {

    // WHY : Assumptions: 80 is the orchestrator's own limit on an execution name, which
    //       ReportExecutionService publishes as EXECUTION_NAME_LIMIT and composes names within. The bound
    //       here is that limit rather than the length of the names this service happens to produce, because
    //       the component also carries a name a caller sends back and the accepted width is what the
    //       orchestration accepts.
    private static final int EXECUTION_NAME_WIDTH = 80;

    // WHY : Assumptions: 10 is the width WS-REPORT-NAME PIC X(10) declares at app/cbl/CORPT00C.cbl L58,
    //       the same bound the submission acknowledgement carries for the same value. The four tokens this
    //       service publishes are shorter, and the declared width is the bound so that the two types cannot
    //       disagree about one field.
    private static final int REPORT_TYPE_WIDTH = 10;

    // WHY : Assumptions: 10 characters is a calendar date in YYYY-MM-DD order, which is the width the
    //       reference's own date fields declare and the width the request accepts.
    private static final int DATE_WIDTH = 10;

    /**
     * Validates the components that are never absent and the pairing of the two artifact components.
     *
     * <p>Assumptions: the location and its write instant are checked as a PAIR, for the same reason the
     * statement summary checks its own pair: a location without an instant describes an artifact of unknown
     * age, and an instant without a location describes one a caller cannot fetch. Either alone would leave
     * a caller guessing which half to trust.</p>
     *
     * @param executionName the run's name; must not be {@code null} or blank
     * @param status the run's state; must not be {@code null} or blank
     * @param startedAt when the run started; must not be {@code null} or blank
     * @param stoppedAt when it stopped, absent while it runs
     * @param reportType the report type, absent for a run started outside this surface
     * @param startDate the lower bound, absent on the same terms
     * @param endDate the upper bound, absent on the same terms
     * @param resultUri where the artifact is collected from, absent when none is stored
     * @param resultGeneratedAt when the artifact was written, absent on the same terms
     * @throws IllegalArgumentException if the name, the status or the start instant carries no content, if
     *     a present location carries no content, or if the location and the write instant are not both
     *     present or both absent
     */
    public ReportExecutionStatusResponse {
        requireContent(executionName, "executionName identifies the run and must carry content");
        requireContent(status, "status reports what became of the run and must carry content");
        requireContent(startedAt, "startedAt records when the run began and must carry content");

        if (resultUri != null && resultUri.isBlank()) {
            throw new IllegalArgumentException(
                    "resultUri must locate the produced report or be absent; a blank location is an "
                            + "absence a caller cannot detect");
        }

        if ((resultUri == null) != (resultGeneratedAt == null)) {
            throw new IllegalArgumentException(
                    "resultUri and resultGeneratedAt describe one stored artifact and must be present "
                            + "or absent together");
        }
    }

    /**
     * Refuses a component that is absent or carries only whitespace.
     *
     * @param value the value to check
     * @param message the refusal message, which names the component and never reproduces its value
     * @throws IllegalArgumentException if the value is {@code null} or blank
     */
    private static void requireContent(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Renders the status for a diagnostic.
     *
     * <p>Assumptions: every component is printed, and none of them is withheld. A run name, an
     * orchestration status, two instants, a report type, a date range and a served path carry no cardholder
     * value, no identifier and no monetary amount -- which is exactly why the artifact is addressed by
     * coordinates rather than by a token, and it makes this the one rendering in the module that needs to
     * hide nothing.</p>
     *
     * @return the rendering, never {@code null}
     */
    @Override
    public String toString() {
        return "ReportExecutionStatusResponse[executionName=" + executionName
                + ", status=" + status
                + ", startedAt=" + startedAt
                + ", stoppedAt=" + stoppedAt
                + ", reportType=" + reportType
                + ", startDate=" + startDate
                + ", endDate=" + endDate
                + ", resultUri=" + resultUri
                + ", resultGeneratedAt=" + resultGeneratedAt + ']';
    }

    /**
     * Reports whether this status carries a collectable artifact.
     *
     * @return {@code true} when a location and a write instant are both present
     */
    public boolean hasResult() {
        return resultUri != null;
    }

    /**
     * Builds a status carrying no artifact, for a run that has produced none.
     *
     * @param executionName the run's name; must not be {@code null}
     * @param status the run's state; must not be {@code null}
     * @param startedAt when the run began; must not be {@code null}
     * @param stoppedAt when it stopped, or {@code null} while it runs
     * @param reportType the report type, or {@code null} for a run started elsewhere
     * @param startDate the lower bound, or {@code null} on the same terms
     * @param endDate the upper bound, or {@code null} on the same terms
     * @return the status, never {@code null}
     */
    public static ReportExecutionStatusResponse withoutResult(
            String executionName,
            String status,
            String startedAt,
            String stoppedAt,
            String reportType,
            String startDate,
            String endDate) {
        return new ReportExecutionStatusResponse(executionName, status, startedAt, stoppedAt,
                reportType, startDate, endDate, null, null);
    }

    /**
     * Builds a status carrying the location of a stored artifact.
     *
     * @param base the status without an artifact; must not be {@code null}
     * @param resultUri where the artifact is collected from; must not be {@code null}
     * @param resultGeneratedAt when it was written; must not be {@code null}
     * @return the status with its artifact, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public static ReportExecutionStatusResponse withResult(
            ReportExecutionStatusResponse base, String resultUri, String resultGeneratedAt) {
        Objects.requireNonNull(base, "base must not be null");
        Objects.requireNonNull(resultUri, "resultUri must not be null");
        Objects.requireNonNull(resultGeneratedAt, "resultGeneratedAt must not be null");
        return new ReportExecutionStatusResponse(base.executionName(), base.status(), base.startedAt(),
                base.stoppedAt(), base.reportType(), base.startDate(), base.endDate(), resultUri,
                resultGeneratedAt);
    }
}
