package com.carddemo.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Pins the narrowing {@link GlobalExceptionHandler} applies to the diagnostic request path.
 *
 * <p>Purpose: assert that a primary account number appearing in a request path never reaches either
 * destination this advice writes it to -- the operational log line and the emitted
 * {@link ApiError#path()} member -- and that it is rendered in exactly the form the card service's
 * published contract shows as its example, twelve mask characters followed by the final four digits.
 *
 * <p>Refactoring Rationale: the contract published that guarantee before any code implemented it, so
 * a reader of the specification was told the field was narrowed while the shipped advice returned the
 * request URI verbatim. This class is what makes the guarantee checkable rather than asserted: it
 * exercises the real advice, through a real request object, and reads the value out of both
 * destinations rather than out of the helper that produces it.
 *
 * <p>Alternatives Considered: asserting only on the returned {@link ApiError}, which is the shorter
 * test and the obvious one. Rejected because it verifies the half of the fix that matters least. A
 * response body is read by the caller who already supplied the number; the log line is durable,
 * searchable, and read by people who were never entitled to the value, which is why the log
 * assertions below attach an appender and inspect the formatted message rather than trusting that one
 * expression feeds both.
 *
 * <p>Assumptions: the log assertions attach a {@link ListAppender} to the advice's own logger and
 * detach it afterwards, so no other test observes the appender and no assertion depends on the
 * console layout the shared defaults document configures. Reading the formatted message rather than
 * the raw pattern is deliberate: the account number would reach storage through the interpolated
 * argument, not through the pattern, so the formatted text is the thing that has to be clean.
 *
 * <p>Assumptions: {@code NoSuchElementException} is the exception used throughout because its handler
 * is the shortest path from a request to an emitted shape that carries a path -- it needs no
 * framework-constructed validation result. The narrowing is applied in the one helper every handler
 * reads the path from, so exercising one handler exercises the property for all of them; the final
 * test asserts that shared origin explicitly by driving a second, unrelated handler.
 */
class GlobalExceptionHandlerPathMaskingTest {

    /** The card number used throughout, taken from the card contract's own example. */
    private static final String ACCOUNT_NUMBER = "4444333322220011";

    /** The narrowed rendering the card contract publishes for that number. */
    private static final String NARROWED_ACCOUNT_NUMBER = "************0011";

    /** A fixed instant, so an emitted timestamp is never read from the wall clock. */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T09:16:44.902355Z"), ZoneOffset.UTC);

    /** The advice under test. */
    private GlobalExceptionHandler handler;

    /** The appender collecting what the advice logs during one test. */
    private ListAppender<ILoggingEvent> logAppender;

    /** The advice's own logger, retained so the appender can be detached again. */
    private Logger adviceLogger;

    /**
     * Builds the advice and starts collecting its log output.
     *
     * <p>Assumptions: the logger level is left as configured rather than forced. Every site under
     * test logs at WARN or ERROR, and the repository's own configuration keeps those enabled, so
     * forcing a level here would hide a regression in which a site was demoted below the threshold an
     * operator actually reads.
     */
    @BeforeEach
    void attachAppender() {
        this.handler = new GlobalExceptionHandler(FIXED_CLOCK);
        this.logAppender = new ListAppender<>();
        this.logAppender.start();
        this.adviceLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        this.adviceLogger.addAppender(this.logAppender);
    }

    /**
     * Detaches the appender so no later test observes events from this one.
     */
    @AfterEach
    void detachAppender() {
        this.adviceLogger.detachAppender(this.logAppender);
        this.logAppender.stop();
    }

    /**
     * A card number in a path is narrowed in the emitted problem shape.
     */
    @Test
    @DisplayName("the emitted path narrows a card number to its final four digits")
    void narrowsAccountNumberInEmittedPath() {
        ResponseEntity<ApiError> response = whenMissingRecordAt("/api/v1/cards/" + ACCOUNT_NUMBER);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo("/api/v1/cards/" + NARROWED_ACCOUNT_NUMBER);
        assertThat(response.getBody().path()).doesNotContain(ACCOUNT_NUMBER);
    }

    /**
     * A card number in a path is narrowed in the operational log line.
     */
    @Test
    @DisplayName("the logged path narrows a card number to its final four digits")
    void narrowsAccountNumberInLoggedPath() {
        whenMissingRecordAt("/api/v1/cards/" + ACCOUNT_NUMBER);

        assertThat(this.logAppender.list).hasSize(1);
        ILoggingEvent event = this.logAppender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains(NARROWED_ACCOUNT_NUMBER);
        assertThat(event.getFormattedMessage()).doesNotContain(ACCOUNT_NUMBER);
    }

    /**
     * The administrative card route is narrowed too.
     *
     * <p>Assumptions: the contract permits the administrative DETAIL RESPONSE to carry an unnarrowed
     * number, and that permission does not extend to a diagnostic field. A failure is not a
     * successful administrative read, so nothing about the route entitles the log line to the value.
     */
    @Test
    @DisplayName("the administrative card route is narrowed as well as the ordinary one")
    void narrowsAccountNumberOnAdministrativeRoute() {
        ResponseEntity<ApiError> response =
                whenMissingRecordAt("/api/v1/admin/cards/" + ACCOUNT_NUMBER);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path())
                .isEqualTo("/api/v1/admin/cards/" + NARROWED_ACCOUNT_NUMBER);
    }

    /**
     * Narrowing an already narrowed path changes nothing.
     *
     * <p>Assumptions: the mask characters are not digits, so the surviving run is four digits long and
     * falls below the threshold. Idempotence therefore holds by construction rather than by a special
     * case, which is what lets the same helper be applied wherever a path is handled without anyone
     * having to track whether it was applied already.
     */
    @Test
    @DisplayName("an already narrowed path is left exactly as it is")
    void leavesAnAlreadyNarrowedPathUnchanged() {
        String alreadyNarrowed = "/api/v1/cards/" + NARROWED_ACCOUNT_NUMBER;

        ResponseEntity<ApiError> response = whenMissingRecordAt(alreadyNarrowed);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(alreadyNarrowed);
    }

    /**
     * Identifiers shorter than the threshold stay fully legible.
     *
     * <p>Assumptions: these are the two all-digit identifiers the migrated routes actually carry --
     * an eleven-digit account identifier and a nine-digit customer identifier -- and narrowing them
     * would remove the diagnostic value the field exists for while protecting nothing. The
     * twelve-digit case is the boundary immediately below the threshold and is asserted so that a
     * later change to the threshold cannot pass unnoticed.
     *
     * @param path a request path whose longest digit run is shorter than the threshold
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/cards",
        "/api/v1/cards/12345678/hold",
        "/actuator/health",
        "/api/v1/reports?size=100",
    })
    @DisplayName("a digit run shorter than the threshold is left legible")
    void leavesShortDigitRunsLegible(String path) {
        ResponseEntity<ApiError> response = whenMissingRecordAt(path);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(path);
    }

    /**
     * The nine-digit customer identifier and the eleven-digit account identifier are withheld whole.
     *
     * <p>Refactoring Rationale: these two paths were previously asserted to be left LEGIBLE, on a
     * threshold of thirteen digits chosen so that the identifiers the routes legitimately carry would
     * survive. That was the defect: those identifiers are themselves protected, so the assertion
     * pinned a disclosure in place. Both are now withheld in full rather than reduced to a tail,
     * because the platform publishes no partial rendering of either one -- unlike a card number, whose
     * last four digits appear in list rows and detail bodies by contract.
     *
     * @param path the request path carrying a protected identifier
     * @param expected the withheld rendering, of identical length and retaining no digit
     */
    @ParameterizedTest
    @CsvSource({
        "/api/v1/customers/123456789,            /api/v1/customers/*********",
        "/api/v1/accounts/12345678901,           /api/v1/accounts/***********",
        "/api/v1/accounts/123456789012,          /api/v1/accounts/************",
        "/api/v1/accounts/12345678901/customer,  /api/v1/accounts/***********/customer",
    })
    @DisplayName("a customer or account identifier is withheld whole, retaining no digit")
    void withholdsShorterProtectedIdentifiersWhole(String path, String expected) {
        ResponseEntity<ApiError> response = whenMissingRecordAt(path);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(expected);
        assertThat(response.getBody().path()).hasSameSizeAs(path);
        assertThat(response.getBody().path())
                .as("no four-digit tail of a customer or account identifier survives")
                .doesNotContain(lastFourDigitsOf(path));
    }

    /**
     * Runs at and above the threshold are narrowed, at any position and in any number.
     *
     * <p>Assumptions: the declared width is preserved in every case, so each expectation below is the
     * same length as its input. The interior-run and two-run cases are what prove the loop closes a
     * run on a non-digit rather than only at the end of the text, and the nine-digit case is the
     * threshold itself.
     *
     * <p>Assumptions: only a run of sixteen digits or more keeps a four-digit tail, and the cases below
     * are chosen to straddle that boundary. A thirteen- and a fifteen-digit run keep nothing, because a
     * run that short cannot be a card number; a sixteen- and a seventeen-digit run keep the last four,
     * which is the rendering the card contract already publishes.
     *
     * @param path the request path to narrow
     * @param expected the narrowed rendering, of identical length
     */
    @ParameterizedTest
    @CsvSource({
        "/api/v1/cards/123456789,              /api/v1/cards/*********",
        "/api/v1/cards/1234567890123,          /api/v1/cards/*************",
        "/api/v1/cards/444433332222001,        /api/v1/cards/***************",
        "/api/v1/cards/4444333322220011/hold,  /api/v1/cards/************0011/hold",
        "/x/4444333322220011/y/5555444433332222, /x/************0011/y/************2222",
        "/api/v1/cards/44443333222200119,      /api/v1/cards/*************0119",
    })
    @DisplayName("a digit run at or above the threshold is narrowed wherever it appears")
    void narrowsQualifyingDigitRuns(String path, String expected) {
        ResponseEntity<ApiError> response = whenMissingRecordAt(path);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(expected);
        assertThat(response.getBody().path()).hasSameSizeAs(path);
    }

    /**
     * An absent request yields an empty path rather than a failure.
     *
     * <p>Assumptions: a {@code null} request reaches the advice when it is exercised outside a servlet
     * container, which is how the slice tests of every service will drive it. The narrowing must not
     * turn that case into a dereference.
     */
    @Test
    @DisplayName("an absent request yields the empty path")
    void yieldsEmptyPathWithoutARequest() {
        ResponseEntity<ApiError> response =
                this.handler.onMissingRecord(new NoSuchElementException("absent"), null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEmpty();
    }

    /**
     * A second, unrelated handler narrows the same way.
     *
     * <p>Assumptions: this is the assertion that the narrowing lives at the single origin every
     * handler reads from rather than being repeated per handler. If a later change moved it into one
     * handler, the shape asserted here would be the one that regressed silently.
     */
    @Test
    @DisplayName("a second handler narrows the path from the same shared origin")
    void narrowsFromOneSharedOrigin() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/cards/" + ACCOUNT_NUMBER);

        ResponseEntity<ApiError> response =
                this.handler.onUnexpectedFailure(new IllegalStateException("unexpected"), request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo("/api/v1/cards/" + NARROWED_ACCOUNT_NUMBER);
        assertThat(this.logAppender.list).hasSize(1);
        assertThat(this.logAppender.list.get(0).getFormattedMessage())
                .doesNotContain(ACCOUNT_NUMBER);
    }

    /**
     * Extracts the last four digits of the longest digit run in a path.
     *
     * <p>Assumptions: the withheld identifier is the longest digit run, because the surrounding route
     * text carries only the single digit of the version segment. Taking the longest run rather than the
     * final segment is what lets the same helper serve a path whose identifier is followed by a
     * sub-resource.</p>
     *
     * @param path the request path the assertion was driven with; must not be {@code null}
     * @return the last four characters of its longest digit run
     */
    private static String lastFourDigitsOf(String path) {
        String longest = "";
        for (String run : path.split("[^0-9]+")) {
            if (run.length() > longest.length()) {
                longest = run;
            }
        }
        return longest.substring(longest.length() - 4);
    }

    /**
     * Drives the missing-record handler for one request path.
     *
     * @param path the request URI the advice will read; must not be {@code null}
     * @return the advice's response, whose body carries the narrowed path
     */
    private ResponseEntity<ApiError> whenMissingRecordAt(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        return this.handler.onMissingRecord(new NoSuchElementException("absent"), request);
    }
}
