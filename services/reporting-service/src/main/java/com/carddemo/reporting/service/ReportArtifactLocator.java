package com.carddemo.reporting.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Composes the object key of a transaction-report artifact, and the path a caller collects it from.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>⚠️ Refactoring Rationale: the key convention lived inside {@code ReportArtifactPublisher}, which is a
 * WRITE path in the batch entry point, and a review found the consequence: a report submitted through the
 * request surface returned an orchestration handle that no operation consumed, and the artifact the run
 * produced could not be reached at all. Serving it needs the same key the run wrote to, and the read side
 * cannot import the write side -- the batch package already depends on this one, so the dependency would be
 * a cycle. The convention therefore moves HERE, where both sides can reach it, and the publisher composes
 * its key through this class.
 *
 * <h2>Assumptions: the coordinates are the report's identity, and they are not confidential</h2>
 *
 * <p>An artifact is identified by a report type and an inclusive date range, and the run date is the range's
 * upper bound because that is what the on-demand task passes -- {@code GenerateAdHocReportTask} calls the
 * publisher with the end bound as the run date. Those three values are what a caller already supplied in its
 * own request, so the collection path carries them in the clear.
 *
 * <p>Alternatives Considered: sealing the three coordinates into an opaque selector, the way a statement
 * artifact is addressed. Rejected for two reasons. A statement artifact's selector protects a CARD identity,
 * and there is nothing of the kind here -- a report type and a date range describe a query, not a person.
 * And an opaque token would stop an operator from constructing the location of a run whose coordinates they
 * know, which is precisely what a runbook does when a report is being chased after the fact. What replaces
 * the token as a safeguard is validation: the type is admitted only from a closed domain and each bound is
 * parsed as a calendar date, so no caller-supplied text reaches a key uninterpreted.
 */
@Service
public class ReportArtifactLocator {

    /**
     * Configuration property carrying the key prefix report artifacts sit under.
     *
     * <p>Assumptions: this is the SAME property name the write path published before the convention moved
     * here, so no deployment configuration changes and a running environment cannot end up with a reader
     * and a writer looking at two prefixes.</p>
     */
    public static final String REPORT_PREFIX_PROPERTY = "carddemo.reporting.s3.report-prefix";

    /** The object name, appended after the partitions. */
    public static final String REPORT_OBJECT = "transaction-detail.txt";

    /**
     * The type token the scheduled nightly run publishes under, being {@code daily}.
     *
     * <p>Assumptions: the nightly run is not one of the three types the request surface offers -- it is the
     * scheduled run over one business date, the reference's {@code app/jcl/TRANREPT.jcl} output -- so it
     * carries its own token. Borrowing {@code monthly} would make a one-day report indistinguishable in the
     * key from a calendar-month one.</p>
     */
    public static final String DAILY_REPORT_TYPE = "daily";

    /**
     * The type tokens an on-demand run may publish under.
     *
     * <p>Assumptions: the three are the lower-cased report names {@link ReportExecutionService} resolves,
     * which are what the state machine forwards to the on-demand task. They are compared
     * case-insensitively because the argument travels through a command line an operator can type.</p>
     */
    public static final List<String> ON_DEMAND_REPORT_TYPES = List.of("monthly", "yearly", "custom");

    /**
     * Path every operation of this context is served under, being {@code /api/v1/reports}.
     *
     * <p>Assumptions: declared here and ALIASED by {@code ReportController}, for the same reason the
     * statement path is declared on {@code StatementService}: this class composes a location that has to
     * be a path the controller serves, and two independent spellings of one path is how a published
     * location comes to name a route nothing answers. Every piece stays a compile-time constant so the
     * route pattern can sit in an annotation.</p>
     */
    public static final String REPORTS_BASE_PATH = "/api/v1/reports";

    /** Path segment of the transaction-report surface, relative to the base path. */
    public static final String TRANSACTION_REPORT_SEGMENT = "/transaction-report";

    /** Path segment of the artifact collection operation, relative to the report surface. */
    public static final String ARTIFACT_SEGMENT = "/artifact";

    /** Path the report-artifact collection operation is served under. */
    public static final String ARTIFACT_PATH =
            REPORTS_BASE_PATH + TRANSACTION_REPORT_SEGMENT + ARTIFACT_SEGMENT;

    /** Query-parameter name carrying the report type. */
    public static final String TYPE_PARAMETER = "type";

    /** Query-parameter name carrying the inclusive lower bound. */
    public static final String START_DATE_PARAMETER = "startDate";

    /** Query-parameter name carrying the inclusive upper bound. */
    public static final String END_DATE_PARAMETER = "endDate";

    /** The date-partition segment the dataset convention fixes. */
    private static final String DATE_PARTITION = "dt=";

    /** The report-type partition segment. */
    private static final String TYPE_PARTITION = "type=";

    /** The range-start partition segment. */
    private static final String RANGE_START_PARTITION = "from=";

    /** The range-end partition segment. */
    private static final String RANGE_END_PARTITION = "to=";

    /** The key prefix report artifacts sit under. */
    private final String prefix;

    /**
     * Creates the locator over the configured report prefix.
     *
     * @param prefix the report key prefix, supplied by {@value #REPORT_PREFIX_PROPERTY}; must not be
     *     {@code null}
     * @throws NullPointerException if {@code prefix} is {@code null}
     */
    public ReportArtifactLocator(
            @Value("${" + REPORT_PREFIX_PROPERTY + "}") String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix must not be null");
    }

    /**
     * Composes the object key one report artifact is stored at.
     *
     * <p>Assumptions: the type is canonicalised to lower case, so one report produces one key however an
     * operator capitalised the argument -- without that, {@code MONTHLY} and {@code monthly} would be two
     * artifacts for one report and the second would not replace the first.</p>
     *
     * @param reportType the report type as the caller stated it; must not be {@code null}
     * @param rangeStart the inclusive lower bound of the reported range; must not be {@code null}
     * @param rangeEnd the inclusive upper bound; must not be {@code null}
     * @param runDate the date the run is attributed to; must not be {@code null}
     * @return the object key, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the type is not one this service publishes
     */
    public String key(String reportType, LocalDate rangeStart, LocalDate rangeEnd, LocalDate runDate) {
        Objects.requireNonNull(reportType, "reportType must not be null");
        Objects.requireNonNull(rangeStart, "rangeStart must not be null");
        Objects.requireNonNull(rangeEnd, "rangeEnd must not be null");
        Objects.requireNonNull(runDate, "runDate must not be null");
        return prefix
                + DATE_PARTITION + runDate + "/"
                + TYPE_PARTITION + requireKnownReportType(reportType) + "/"
                + RANGE_START_PARTITION + rangeStart + "/"
                + RANGE_END_PARTITION + rangeEnd + "/"
                + REPORT_OBJECT;
    }

    /**
     * Composes the path a caller collects one report artifact from.
     *
     * <p>Assumptions: the path is composed here rather than at the request edge, so the location a status
     * response publishes and the route that serves it are derived from one place. The bounds are rendered
     * in ISO order, which is both what the parameters accept and what the key partitions carry.</p>
     *
     * @param reportType the report type; must not be {@code null}
     * @param rangeStart the inclusive lower bound; must not be {@code null}
     * @param rangeEnd the inclusive upper bound; must not be {@code null}
     * @return the collection path, never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws IllegalArgumentException if the type is not one this service publishes
     */
    public String artifactPath(String reportType, LocalDate rangeStart, LocalDate rangeEnd) {
        return ARTIFACT_PATH
                + "?" + TYPE_PARAMETER + "=" + requireKnownReportType(reportType)
                + "&" + START_DATE_PARAMETER + "=" + Objects.requireNonNull(rangeStart, "rangeStart")
                + "&" + END_DATE_PARAMETER + "=" + Objects.requireNonNull(rangeEnd, "rangeEnd");
    }

    /**
     * Canonicalises a report type against the closed domain, refusing anything outside it.
     *
     * <p>Assumptions: the refusal does NOT quote the offending value. The same value reaches an object key
     * on the accepting path, and a message quoting an unvalidated argument is the same exposure in a
     * different destination -- the shared advice records an internal failure's message. The domain is stated
     * instead, which is what a caller needs in order to correct the argument.</p>
     *
     * @param reportType the requested type as the caller stated it; must not be {@code null}
     * @return the canonical lower-case token; never {@code null}
     * @throws NullPointerException if {@code reportType} is {@code null}
     * @throws IllegalArgumentException if the type is not in the closed domain
     */
    public static String requireKnownReportType(String reportType) {
        String token = Objects.requireNonNull(reportType, "reportType must not be null")
                .trim().toLowerCase(Locale.ROOT);
        if (DAILY_REPORT_TYPE.equals(token) || ON_DEMAND_REPORT_TYPES.contains(token)) {
            return token;
        }
        throw new IllegalArgumentException(
                "the report type is not one this service publishes: the accepted tokens are "
                        + DAILY_REPORT_TYPE + " and " + ON_DEMAND_REPORT_TYPES);
    }
}
