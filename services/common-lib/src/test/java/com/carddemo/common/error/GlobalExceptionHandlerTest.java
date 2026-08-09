package com.carddemo.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.common.control.OnlineWritesDisabledException;
import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.common.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

/**
 * Verifies that the shared advice never lets a primary account number out of the process, through
 * either of the two destinations it writes a request path to.
 *
 * <p>Refactoring Rationale: the path used throughout is a MISTAKEN card path -- the shape of
 * {@code /api/v1/cards/{cardKey}} with a sixteen-digit number where the selector belongs. It was
 * previously described as "the resolved form of the published card route", which it no longer is: that
 * route's parameter now admits only a canonical selector and refuses a card number outright, and
 * {@code CardApiContractTest.noOperationAcceptsACardNumberInARequestTarget} asserts that no published
 * operation accepts one in a path or a query at all.</p>
 *
 * <p>Assumptions: the cases are KEPT with that path rather than retired with the route, because this
 * advice is the last line for a number that reaches a path anyway -- a caller submitting one where a
 * selector belongs is refused with 400, and this is what stops the refusal from copying the number
 * into a stored diagnostic on its way out. Asserting against a path no route accepts is therefore the
 * point rather than a staleness: it is the case the masking exists for now that the contract handles
 * the rest. The number itself is a synthetic test value that identifies no account.</p>
 * <p>Assumptions: the card routes no longer carry a number at all -- a single card is addressed by an
 * opaque sealed selector and the list's {@code cardNumber} query filter was withdrawn with it,
 * registered together as {@code D-CARD-SELECTOR} in the divergence register -- so the exposure these
 * assertions guard is narrower than it was. It is deliberately still guarded: this advice serves every
 * context, a caller may be typed against a route no contract publishes any more, and a redaction that
 * only held while every contract happened to avoid the value would be no redaction at all.</p>
 *
 * <p>Assumptions: two destinations are asserted separately because they fail separately. The emitted
 * problem shape is read from the returned entity; the operational record is read from a list appender
 * attached to the advice's own logger, so the assertion is made against the formatted message a log
 * pipeline would ship rather than against the argument the call site passed.</p>
 */
class GlobalExceptionHandlerTest {

    /** A synthetic sixteen-digit card number, the width the published path parameter admits. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The resolved card route carrying that number, exactly as a container would report it. */
    private static final String CARD_PATH = "/api/v1/cards/" + CARD_NUMBER;

    /** The rendering the published contract gives as its example: twelve masks, four retained. */
    private static final String MASKED_CARD_PATH = "/api/v1/cards/************1111";

    /** A fixed clock so an emitted timestamp is reproducible rather than wall-clock dependent. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC);

    /** The advice under test, rebuilt per test so no state can carry between them. */
    private GlobalExceptionHandler handler;

    /** The appender capturing what the advice actually wrote, attached for the duration of a test. */
    private ListAppender<ILoggingEvent> captured;

    /** The advice's own logger, held so the appender can be detached again. */
    private Logger adviceLogger;

    /**
     * Attaches a list appender to the advice's logger and builds the advice under test.
     *
     * <p>Assumptions: the logger is addressed by the class rather than by a literal name, so a rename
     * cannot leave this test silently capturing nothing. The level is lowered explicitly because the
     * two sites asserted here log at warn and at error, and a configuration that raised the threshold
     * would make an empty capture look like a passing masking assertion.</p>
     */
    @BeforeEach
    void attachAppender() {
        this.handler = new GlobalExceptionHandler(CLOCK);
        this.adviceLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        this.captured = new ListAppender<>();
        this.captured.start();
        this.adviceLogger.addAppender(this.captured);
        this.adviceLogger.setLevel(Level.WARN);
    }

    /**
     * Detaches the appender so one test's capture cannot be read by the next.
     */
    @AfterEach
    void detachAppender() {
        this.adviceLogger.detachAppender(this.captured);
        this.captured.stop();
    }

    /**
     * Confirms the 404 site masks the number in the emitted problem shape, rendering it exactly as the
     * published example does.
     */
    @Test
    @DisplayName("an absent record emits the path with the card number masked to its last four")
    void absentRecordEmitsMaskedPath() {
        ResponseEntity<ApiError> response =
                this.handler.onMissingRecord(new NoSuchElementException(), requestFor(CARD_PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(MASKED_CARD_PATH);
        assertThat(response.getBody().path()).doesNotContain(CARD_NUMBER);
    }

    /**
     * Confirms the 404 site writes no unmasked number into the operational record, which is the
     * destination the finding this test closes was raised against.
     */
    @Test
    @DisplayName("an absent record logs the masked path and never the card number")
    void absentRecordLogsMaskedPath() {
        this.handler.onMissingRecord(new NoSuchElementException(), requestFor(CARD_PATH));

        assertThat(loggedMessages()).isNotEmpty();
        assertThat(loggedMessages()).allSatisfy(line -> {
            assertThat(line).doesNotContain(CARD_NUMBER);
            assertThat(line).contains(MASKED_CARD_PATH);
        });
    }

    /**
     * Confirms the 500 site behaves identically, because both destinations read the same helper and a
     * fix applied at one call site only would leave this one exposed.
     */
    @Test
    @DisplayName("an unexpected failure logs and emits the masked path at error level")
    void unexpectedFailureMasksBothDestinations() {
        ResponseEntity<ApiError> response = this.handler.onUnexpectedFailure(
                new IllegalStateException("failure text is never rendered"), requestFor(CARD_PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(MASKED_CARD_PATH);
        assertThat(loggedMessages()).isNotEmpty();
        assertThat(loggedMessages()).allSatisfy(line -> assertThat(line).doesNotContain(CARD_NUMBER));
    }

    /**
     * Confirms a run longer than the declared width is masked too, so an endpoint that ever accepted a
     * seventeen-to-nineteen-digit number could not start passing one through unnoticed.
     */
    @Test
    @DisplayName("a nineteen-digit run is masked with only its last four retained")
    void longerThanDeclaredWidthIsMasked() {
        ResponseEntity<ApiError> response = this.handler.onMissingRecord(
                new NoSuchElementException(), requestFor("/api/v1/cards/4111111111111111222"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo("/api/v1/cards/***************1222");
    }

    /**
     * Confirms the shorter identifiers a migrated path carries are withheld whole, retaining no digit.
     *
     * <p>Refactoring Rationale: this test previously asserted that these identifiers were "carried
     * through unmasked", on the ground that masking them would cost diagnostic value "where there is no
     * exposure to remove". The premise was wrong -- eleven digits is {@code ACCT-ID PIC 9(11)} and nine
     * is {@code CUST-ID PIC 9(09)}, both protected identifiers -- so the assertion held a disclosure in
     * place on every failure those routes can return. The single-digit trailing segment is retained in
     * the expectation to show the rule still fires on runs and not on segments.</p>
     */
    @Test
    @DisplayName("account and customer identifiers are withheld whole")
    void shorterIdentifiersAreWithheldWhole() {
        ResponseEntity<ApiError> response = this.handler.onMissingRecord(
                new NoSuchElementException(), requestFor("/api/v1/accounts/00000000011/customer/9"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo("/api/v1/accounts/***********/customer/9");
    }

    /**
     * Confirms every qualifying run in one path is masked, not merely the first, and that the text
     * around them is preserved byte for byte.
     */
    @Test
    @DisplayName("two card-shaped runs in one path are both masked")
    void everyQualifyingRunIsMasked() {
        ResponseEntity<ApiError> response = this.handler.onMissingRecord(new NoSuchElementException(),
                requestFor("/api/v1/admin/cards/4111111111111111/replaces/5555444433332222"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path())
                .isEqualTo("/api/v1/admin/cards/************1111/replaces/************2222");
    }

    /**
     * Confirms masking is idempotent: a value that already carries the mask characters is returned
     * unchanged, so a path assembled from an already-masked value is not masked twice into a shorter
     * or differently-shaped string.
     */
    @Test
    @DisplayName("an already-masked path is returned unchanged")
    void maskingIsIdempotent() {
        ResponseEntity<ApiError> response = this.handler
                .onMissingRecord(new NoSuchElementException(), requestFor(MASKED_CARD_PATH));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(MASKED_CARD_PATH);
    }

    /**
     * Confirms the absent-request case still yields the empty string, which is the value the published
     * contract names for a problem shape with no path available.
     */
    @Test
    @DisplayName("a missing servlet request yields the empty path")
    void absentRequestYieldsEmptyPath() {
        ResponseEntity<ApiError> response =
                this.handler.onMissingRecord(new NoSuchElementException(), null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEmpty();
    }

    /**
     * Confirms the width this package records from the baseline is not the width the transport mints,
     * which is the reason that constant is provenance rather than an enforceable contract.
     *
     * <p>Assumptions: this is the assertion that makes the narrowed visibility of
     * {@code ApiError.CORRELATION_ID_WIDTH} safe to rely on. If the two ever became equal, the
     * constant would stop being a record of a superseded width and the note on it would need
     * rewriting; asserting the inequality is what makes that a build failure rather than a silent
     * drift.</p>
     */
    @Test
    @DisplayName("the recorded baseline correlation width is narrower than the minted identity")
    void recordedCorrelationWidthIsNotTheLiveContract() {
        assertThat(ApiError.CORRELATION_ID_WIDTH).isEqualTo(20);
        assertThat(ApiError.CORRELATION_ID_WIDTH)
                .isLessThan(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);
    }

    /**
     * Confirms the narrowing is a property of the shared path reader rather than of one handler, by
     * asserting it on a second handler that emits a different code and status.
     *
     * <p>Assumptions: the reading of the request target is factored into one helper inside the advice,
     * so covering a second handler that reaches it shows the guarantee belongs to the reader and not to
     * one response shape.</p>
     */
    @Test
    @DisplayName("the same masking applies to the 403 body")
    void cardNumberInTargetIsMaskedOutOfForbiddenBody() {
        ResponseEntity<ApiError> response = this.handler
                .onAccessDenied(new AccessDeniedException("denied"), requestFor(CARD_PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(MASKED_CARD_PATH);
        assertThat(response.getBody().path()).doesNotContain(CARD_NUMBER);
    }

    /**
     * Confirms the target every published single-card route actually carries is echoed unchanged.
     *
     * <p>Assumptions: this is the case that matters for diagnosis. A narrowing measure that also
     * altered a well-formed opaque selector would make the path member useless for the purpose it
     * exists for, so the pass-through is asserted rather than assumed.</p>
     */
    @Test
    @DisplayName("a published opaque-selector target is echoed unchanged")
    void publishedOpaqueTargetIsEchoedUnchanged() {
        String target = "/api/v1/cards/8Qk2vN7pR4tL0aXsY6bJdF/unmasked";

        ResponseEntity<ApiError> response =
                this.handler.onMissingRecord(new NoSuchElementException(), requestFor(target));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo(target);
    }

    /**
     * Confirms the emitted shape masks a path supplied directly to it, so the guarantee does not
     * depend on this advice being the only producer of one.
     *
     * <p>Assumptions: this asserts the canonical constructor rather than the advice, because a service
     * adding its own advice, or a component assembling a failure of its own, reaches the shape without
     * passing through the reader the tests above drive. The two together are what make the masking a
     * rule rather than a convention.</p>
     */
    @Test
    @DisplayName("the emitted shape masks a path supplied directly to it")
    void suppliedPathIsMaskedByTheShapeItself() {
        ApiError emitted = ApiError.of(ApiError.CODE_NOT_FOUND, "absent", HttpStatus.NOT_FOUND.value(),
                "", CARD_PATH, CLOCK);

        assertThat(emitted.path()).isEqualTo(MASKED_CARD_PATH);
        assertThat(emitted.path()).doesNotContain(CARD_NUMBER);
    }

    /**
     * Confirms a failure the caller's own input provoked is answered 400 with a field array, not 500
     * with an abend.
     *
     * <p>Refactoring Rationale: every caller-input failure that was not one of three named persistence
     * conflicts used to be answered as an internal failure -- HTTP 500, critical severity, abend block
     * populated -- so a malformed date raised the alert severity reserved for a broken service.
     * Transformation rule T7 makes the per-field array the way a rejection is expressed, so all five
     * properties are asserted together: the status, the code, the severity, the ABSENCE of the abend
     * block, and a non-empty array with something for a client to display.</p>
     *
     * <p>Refactoring Rationale: the failure handed in is a {@link ClientInputException} and no longer a
     * bare {@code IllegalArgumentException}. The handler used to claim that whole family, which also
     * carries every internal invariant in the migration, so a service defect was reported to the caller
     * as a request to correct and never reached the 500 channel the alerting watches. This test now
     * asserts the narrowed contract, and its counterpart below asserts that a bare
     * {@code IllegalArgumentException} is answered as an internal failure instead.</p>
     */
    @Test
    @DisplayName("a rejected-input failure is answered 400 with a field array and no abend block")
    void rejectedCallerInputIsAnsweredAsFourHundred() {
        ResponseEntity<ApiError> response = this.handler.onRejectedCallerInput(
                new ClientInputException("DATE_WIDTH", "openDate",
                        "Open Date: date carries content at width 9"),
                requestFor(CARD_PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ApiError.CODE_VALIDATION);
        assertThat(response.getBody().severity()).isEqualTo(ApiError.Severity.WARNING);
        assertThat(response.getBody().abend()).isNull();
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.MESSAGE_VALIDATION_FAILED);
        assertThat(response.getBody().fieldErrors()).hasSize(1);
        assertThat(response.getBody().fieldErrors().get(0).field()).isEqualTo("openDate");
        assertThat(response.getBody().path()).isEqualTo(MASKED_CARD_PATH);
    }

    /**
     * Confirms a refusal that names no field is still keyed by the request as a whole.
     *
     * <p>Assumptions: this is the fallback branch of the same handler and it has to keep working, because
     * the shared authorization codec raises refusals about a whole payload rather than about one member.
     * Answering with an empty array would give a client a 400 with nothing to display, which
     * transformation rule T7 forbids.</p>
     */
    @Test
    @DisplayName("a refusal naming no field is keyed by the request")
    void rejectedCallerInputWithoutFieldIsKeyedByRequest() {
        ResponseEntity<ApiError> response = this.handler.onRejectedCallerInput(
                new ClientInputException("AUTH_WIRE_MALFORMED", "payload carries 17 fields"),
                requestFor(CARD_PATH));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().fieldErrors()).hasSize(1);
        assertThat(response.getBody().fieldErrors().get(0).field())
                .isEqualTo(GlobalExceptionHandler.FIELD_REQUEST);
    }

    /**
     * Confirms the caught diagnostic is written to the operational record and never to the body.
     *
     * <p>Assumptions: the client-facing sentence is fixed while the caught text goes to the log under
     * the same correlation identity. The caught text is safe to log ONLY because the claimed type
     * guarantees its own message is redacted -- every subclass composes it through a per-field
     * sensitivity gate -- which is why the stable code is asserted alongside it: an alert rule matches on
     * the code, not on the sentence.</p>
     */
    @Test
    @DisplayName("the rejected-input diagnostic and its stable code are logged, never rendered")
    void rejectedCallerInputDiagnosticIsLoggedNotRendered() {
        String diagnostic = "PA-RQ-TRANSACTION-ID arrived as 14 characters";

        ResponseEntity<ApiError> response = this.handler.onRejectedCallerInput(
                new ClientInputException("AUTH_WIRE_MALFORMED", diagnostic), requestFor(CARD_PATH));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).doesNotContain(diagnostic);
        assertThat(this.captured.list).isNotEmpty();
        assertThat(this.captured.list.get(0).getFormattedMessage())
                .contains("status=400")
                .contains("reason=AUTH_WIRE_MALFORMED")
                .contains(diagnostic)
                .doesNotContain(CARD_NUMBER);
    }

    /**
     * Confirms a catalogue sentence that does NOT end with the reference terminator still reaches the
     * caller verbatim, in the aggregate and in every field entry the refusal names.
     *
     * <p>Refactoring Rationale: the provenance gate recognised only sentences ending in the terminator,
     * and thirty-four of the sixty-seven migrated message constants do not end that way. The card
     * maintenance sentences asserted here are declared without one at {@code app/cbl/COCRDUPC.cbl} lines
     * 195 and 196, while the card contract publishes a worked four-hundred example quoting that exact
     * wording in both {@code message} and {@code fieldErrors[].message} -- so before the second admitted
     * shape existed, a published contract described a body no response could produce.</p>
     *
     * <p>Assumptions: both destinations of the sentence are asserted, because they are populated from the
     * same decision but by different statements, and a client renders the aggregate in the message band
     * and the entry against the control. Asserting only one would leave the other free to regress.</p>
     */
    @Test
    @DisplayName("an unterminated catalogue sentence is rendered verbatim in the aggregate and the entry")
    void proseShapedCatalogueSentenceIsRenderedVerbatim() {
        String sentence = "Card Active Status must be Y or N";

        ResponseEntity<ApiError> response = this.handler.onRejectedCallerInput(
                new ClientInputException("CARD_ATTRIBUTES_REFUSED", "activeStatus",
                        com.carddemo.common.validation.FieldValidationFlag.BLANK, sentence),
                requestFor(CARD_PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo(sentence);
        assertThat(response.getBody().fieldErrors()).hasSize(1);
        assertThat(response.getBody().fieldErrors().get(0).message()).isEqualTo(sentence);
        assertThat(response.getBody().fieldErrors().get(0).state())
                .isEqualTo(com.carddemo.common.validation.FieldValidationFlag.BLANK);
    }

    /**
     * Confirms a sentence of foreign provenance is still replaced by the fixed aggregate, whichever of
     * the two admitted shapes it fails.
     *
     * <p>Assumptions: the cases are the ways a library, parser, converter or driver actually writes what
     * it could not handle, plus the two identifier widths this system carries. A parser quotes the token
     * it read; a driver labels its report with a colon and brackets its own diagnostic; a converter names
     * the type it wanted with a dotted class name; a copybook field name arrives hyphenated; and a value
     * a caller supplied is echoed as an unbroken digit run. Every one of those fails the reference-prose
     * alphabet, its dotted-identifier rule or its digit bound, and none ends with the terminator, so each
     * must arrive as the fixed sentence.</p>
     *
     * <p>Trade-offs: this does NOT assert that every conceivable library sentence is refused, because the
     * shape test does not claim that. A sentence such as {@code 'Index 5 out of bounds for length 3'}
     * quotes nothing, names no dotted type and holds no long number, so the prose shape admits it; the
     * rationale on the gate records why that residual is accepted and why no path in this repository
     * reaches it. Asserting an absolute here would be asserting a property the implementation does not
     * have, which is worse than leaving the boundary stated where it actually falls.</p>
     *
     * <p>Assumptions: the two identifier cases are the point of the digit bound and are asserted
     * separately from the punctuation cases. The prose shape refuses a run longer than four, so an
     * eleven-digit account identifier and a sixteen-digit card number are both refused even though every
     * other character in those sentences is admitted -- which is what makes the second shape stricter
     * than the first on the one axis this gate exists to protect.</p>
     */
    @Test
    @DisplayName("a library, driver, parser or identifier-bearing sentence is replaced by the fixed one")
    void foreignSentencesAreNotRendered() {
        String[] foreign = {
            "For input string: \"12\"",
            "could not execute statement [ERROR: duplicate key]",
            "Failed to convert value of type java.lang.String",
            "No enum constant com.carddemo.card.CardStatus.MAYBE",
            "PA-RQ-TRANSACTION-ID arrived as 14 characters",
            "Account 00000000011 was refused",
            "Card " + CARD_NUMBER + " was refused",
        };

        for (String message : foreign) {
            ResponseEntity<ApiError> response = this.handler.onRejectedCallerInput(
                    new ClientInputException("REFUSED", "payload", message), requestFor(CARD_PATH));

            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().message())
                    .as("sentence of foreign provenance: %s", message)
                    .isEqualTo(GlobalExceptionHandler.MESSAGE_VALIDATION_FAILED);
            assertThat(response.getBody().fieldErrors().get(0).message())
                    .isEqualTo(GlobalExceptionHandler.MESSAGE_VALIDATION_FAILED);
        }
    }

    /**
     * Confirms the rejected-input answer is reached by the runtime entry point as well as by dispatch.
     *
     * <p>Assumptions: a component inside the application, and every test of the mapping, calls the
     * runtime handler directly rather than going through the framework's dispatch, so the two routes
     * have to agree.</p>
     *
     * <p>Refactoring Rationale: the second half of this test asserts the OPPOSITE of what it used to. A
     * bare {@code IllegalArgumentException} and a {@code NumberFormatException} were answered 400 here,
     * on the reasoning that inheritance lets one handler claim the money parser, the codecs and the date
     * validator at once. That reasoning also claimed every internal invariant, because this migration
     * expresses those with the same exception: a transfer object refusing a component the service
     * constructed it with, an edit mask refusing a band the service composed. Both now reach the internal
     * answer, which is the correct classification -- a service that parses an unvalidated caller value
     * with a platform parser is missing a validation, and reporting the missing validation as a server
     * fault is what gets it fixed.</p>
     */
    @Test
    @DisplayName("the runtime entry point maps a declared refusal to 400 and a bare one to 500")
    void runtimeEntryPointDelegatesRejectedInput() {
        ResponseEntity<ApiError> fromDeclared = this.handler.onRuntimeFailure(
                new ClientInputException("DATE_WIDTH", "width 9"), requestFor(CARD_PATH));
        ResponseEntity<ApiError> fromArgument = this.handler.onRuntimeFailure(
                new IllegalArgumentException("width 9"), requestFor(CARD_PATH));
        ResponseEntity<ApiError> fromNumber = this.handler.onRuntimeFailure(
                new NumberFormatException("not-a-number"), requestFor(CARD_PATH));

        assertThat(fromDeclared.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fromDeclared.getBody()).isNotNull();
        assertThat(fromDeclared.getBody().abend()).isNull();
        assertThat(fromDeclared.getBody().code()).isEqualTo(ApiError.CODE_VALIDATION);

        assertThat(fromArgument.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fromNumber.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fromArgument.getBody()).isNotNull();
        assertThat(fromNumber.getBody()).isNotNull();
        assertThat(fromArgument.getBody().abend()).isNotNull();
        assertThat(fromNumber.getBody().code()).isEqualTo(ApiError.CODE_INTERNAL);
    }

    /**
     * Confirms a contention a service declares carries the relational subsystem and the current version.
     *
     * <p>Assumptions: both properties are asserted together because each was separately unachievable
     * before. The subsystem was always the application one, since every conflict was composed through a
     * factory that hardcoded it, while the published contracts declare a contention refusal as arising in
     * the relational store; and the version was reported nowhere at all, so a caller told the record had
     * changed had to re-read it to learn what it changed to.</p>
     */
    @Test
    @DisplayName("a declared contention carries the relational subsystem and the current version")
    void declaredContentionCarriesSubsystemAndVersion() {
        ResponseEntity<ApiError> response = this.handler.onRecordConflict(
                new RecordConflictException(RecordConflictException.Kind.STALE_VERSION, 7L),
                requestFor(CARD_PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ApiError.CODE_CONFLICT);
        assertThat(response.getBody().subsystem()).isEqualTo(ApiError.Subsystem.RELATIONAL);
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.MESSAGE_RECORD_CHANGED);
        assertThat(response.getBody().fieldErrors()).hasSize(1);
        assertThat(response.getBody().fieldErrors().get(0).field())
                .isEqualTo(GlobalExceptionHandler.FIELD_VERSION);
        assertThat(response.getBody().fieldErrors().get(0).message()).isEqualTo("7");
    }

    /**
     * Confirms a contention with no version to report carries an empty field array rather than a filler.
     *
     * <p>Assumptions: a lock that could not be obtained compared nothing, so there is no version to
     * report, and an entry naming one this response does not know would be a value invented for the
     * shape's sake. An empty array is what every other non-validation problem shape in this class
     * carries.</p>
     */
    @Test
    @DisplayName("a contention with no version carries an empty field array")
    void contentionWithoutVersionCarriesNoFieldEntry() {
        ResponseEntity<ApiError> response = this.handler.onRecordConflict(
                new RecordConflictException(RecordConflictException.Kind.LOCK_UNAVAILABLE),
                requestFor(CARD_PATH));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().subsystem()).isEqualTo(ApiError.Subsystem.RELATIONAL);
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.MESSAGE_LOCK_UNAVAILABLE);
        assertThat(response.getBody().fieldErrors()).isEmpty();
    }

    /**
     * Confirms both entity-agnostic contention sentences are the baseline literals, character for
     * character.
     *
     * <p>Refactoring Rationale: this assertion exists because both constants used to be paraphrases and
     * neither needed to be. The lock sentence carried an inserted definite article, and the referential
     * sentence described the rule in this migration's own words. Every published contract that quoted
     * the baseline wording therefore described a body this advice would not return, and nothing in the
     * build noticed, because the tests that touched these constants referred to them by NAME and so
     * agreed with whatever they happened to say. Comparing against the literal is what makes a
     * reversion fail here rather than in a reviewer's reading of an OpenAPI example.</p>
     *
     * <p>Assumptions: the expected strings are written out in full rather than read from the baseline at
     * run time. The COBOL is reference-only and is not on the test classpath, so a run-time read would
     * mean parsing fixed-format source to recover a level-88 literal -- which would make this test
     * depend on a parser rather than on the text. The citations are given instead: line 205 to 206 of
     * app/cbl/COCRDUPC.cbl and 181 to 182 of app/app-transaction-type-db2/cbl/COTRTUPC.cbl for the lock
     * sentence, and line 1919 of that tree's COTRTLIC.cbl with line 1641 of its COTRTUPC.cbl for the
     * referential one.</p>
     */
    @Test
    @DisplayName("the two contention sentences are the baseline literals")
    void contentionSentencesAreBaselineLiterals() {
        assertThat(GlobalExceptionHandler.MESSAGE_LOCK_UNAVAILABLE)
                .isEqualTo("Could not lock record for update");
        assertThat(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW)
                .isEqualTo("Please delete associated child records first:");

        // WHY : Assumptions: the emitted BODY is asserted as well as the constant, because a contract
        //       reader is promised the sentence a 409 carries and not the value of a field. A future
        //       change that left the constant alone and selected a different one for a kind would
        //       satisfy the two assertions above and still break the promise.
        ResponseEntity<ApiError> referential = this.handler.onRecordConflict(
                new RecordConflictException(RecordConflictException.Kind.REFERENCED_ROW),
                requestFor(CARD_PATH));
        ResponseEntity<ApiError> locked = this.handler.onRecordConflict(
                new RecordConflictException(RecordConflictException.Kind.LOCK_UNAVAILABLE),
                requestFor(CARD_PATH));

        assertThat(referential.getBody()).isNotNull();
        assertThat(locked.getBody()).isNotNull();
        assertThat(referential.getBody().message())
                .isEqualTo("Please delete associated child records first:");
        assertThat(locked.getBody().message()).isEqualTo("Could not lock record for update");
    }

    /**
     * Confirms a genuinely unexpected failure is still an internal failure with its abend block.
     *
     * <p>Assumptions: this is the counterpart that keeps the widening honest. Neither a null
     * dereference nor an illegal state is a member of the rejected-input family, so both must keep
     * reaching the internal answer; without this assertion a later widening of the 400 mapping could
     * quietly reclassify a broken service as a bad request, which is the same defect in the opposite
     * direction.</p>
     */
    @Test
    @DisplayName("an unexpected failure still yields 500 with its abend block")
    void unexpectedFailuresAreStillInternal() {
        ResponseEntity<ApiError> fromNull = this.handler.onRuntimeFailure(
                new NullPointerException("npe"), requestFor(CARD_PATH));
        ResponseEntity<ApiError> fromState = this.handler.onRuntimeFailure(
                new IllegalStateException("bad state"), requestFor(CARD_PATH));

        assertThat(fromNull.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fromState.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(fromNull.getBody()).isNotNull();
        assertThat(fromState.getBody()).isNotNull();
        assertThat(fromNull.getBody().severity()).isEqualTo(ApiError.Severity.CRITICAL);
        assertThat(fromNull.getBody().abend()).isNotNull();
        assertThat(fromState.getBody().abend()).isNotNull();
        assertThat(fromState.getBody().code()).isEqualTo(ApiError.CODE_INTERNAL);
    }

    /**
     * Confirms the catch-all attaches no throwable to the event it logs, so neither the failure's own
     * message nor any stack frame reaches log storage.
     *
     * <p>Assumptions: the assertion is made on the captured event's throwable slot rather than on the
     * rendered line, because that slot is what a pattern layout expands into the message and the trace.
     * A line-only assertion would pass against a call site that still passed the throwable, since the
     * expansion happens in the appender rather than at the call site.</p>
     *
     * <p>Assumptions: the message given to the failure is the shape of the exposure this closes -- the
     * statement mapper's over-width diagnostic quoted the whole assembled record, so a customer name and
     * a street address reached the one destination the path masking in this class does not cover. The
     * value here is synthetic and identifies nobody.</p>
     */
    @Test
    @DisplayName("an unexpected failure logs no throwable, so no message text and no stack frame")
    void unexpectedFailureLogsNoThrowableContent() {
        String sensitive = "Doe                      John          1 Main Street";

        this.handler.onUnexpectedFailure(new IllegalStateException(sensitive), requestFor(CARD_PATH));

        assertThat(this.captured.list).hasSize(1);
        assertThat(this.captured.list.get(0).getThrowableProxy()).isNull();
        assertThat(this.captured.list.get(0).getFormattedMessage())
                .doesNotContain(sensitive)
                .contains("exception=java.lang.IllegalStateException")
                // WHY : Refactoring Rationale: the reduced rendering is asserted through the digest
                //       field rather than through a "causes=" field this advice no longer emits. Both
                //       existed for a while: one revision rendered a class-name-only cause chain and
                //       another rendered the type chain WITH each link's originating frames, and the
                //       second supersedes the first because it carries strictly more diagnostic value
                //       under the same guarantee -- a frame is code position, never migrated data. What
                //       had to be preserved is the property this case is about, that no message text
                //       and no throwable object reaches the record, and both are still asserted above.
                .contains("failure=java.lang.IllegalStateException")
                .doesNotContain(ThrowableDigest.CAUSE_SEPARATOR);
    }

    /**
     * Confirms the cause chain field carries fully-qualified type names only, in outermost-first order,
     * and none of the messages those causes carry.
     *
     * <p>Assumptions: both nested failures are given messages that would leak if the chain rendered
     * anything but a type, so a regression that appended a message would fail here rather than passing
     * on the strength of the type names alone.</p>
     */
    @Test
    @DisplayName("the cause chain names types only, outermost first, and no message")
    void unexpectedFailureCauseChainNamesTypesOnly() {
        String innerText = "SSN 123456789";
        String outerText = "balance 000000012345";
        Exception failure = new IllegalStateException(outerText,
                new java.io.UncheckedIOException(innerText, new java.io.IOException(innerText)));

        this.handler.onUnexpectedFailure(failure, requestFor(CARD_PATH));

        assertThat(this.captured.list).hasSize(1);
        // WHY : Assumptions: the three type names are asserted individually and in order through the
        //       separator count rather than as one concatenated literal, because each link now carries
        //       its originating frames between the type names and a single literal could no longer
        //       express the sequence. What the case fixes is unchanged: every type in the chain is
        //       named, outermost first, and neither message reaches the record.
        String logged = this.captured.list.get(0).getFormattedMessage();
        assertThat(logged)
                .contains("java.lang.IllegalStateException")
                .contains("java.io.UncheckedIOException")
                .contains("java.io.IOException")
                .doesNotContain(innerText)
                .doesNotContain(outerText);
        assertThat(logged.indexOf("java.io.UncheckedIOException"))
                .isLessThan(logged.indexOf("java.io.IOException"));
        assertThat(logged.split(ThrowableDigest.CAUSE_SEPARATOR, -1)).hasSize(3);
    }

    /**
     * Confirms a failure whose cause names itself terminates the walk instead of looping.
     *
     * <p>Assumptions: the standard cause accessor returns the throwable itself for a throwable
     * constructed that way rather than {@code null}, so an unguarded walk would not terminate. The
     * construction is done by overriding the accessor because the platform's own cause initialiser
     * refuses self-causation outright, which means this state can only arise from a provider type that
     * overrides the accessor -- exactly the case the guard exists for.</p>
     */
    @Test
    @DisplayName("a self-causing failure renders the word for absence rather than looping")
    void selfCausingFailureTerminatesTheWalk() {
        this.handler.onUnexpectedFailure(new SelfCausingFailure(), requestFor(CARD_PATH));

        assertThat(this.captured.list).hasSize(1);
        // WHY : Assumptions: termination is asserted as the ABSENCE of a second link rather than as a
        //       word for absence, because the digest renders the failure's own type and frames whether
        //       or not it has a cause. A walk that failed to terminate would render this one type eight
        //       times and then the truncation marker, so a rendering with no cause separator at all is
        //       the exact evidence that the self-cause was recognised and dropped.
        assertThat(this.captured.list.get(0).getFormattedMessage())
                .contains("failure=" + SelfCausingFailure.class.getName())
                .doesNotContain(ThrowableDigest.CAUSE_SEPARATOR);
    }

    /**
     * Confirms a chain longer than the bound is truncated at the bound rather than rendered whole.
     *
     * <p>Assumptions: the bound is asserted by counting the separators rather than by naming the
     * constant, because the constant is private to the advice. Twenty causes are nested so the rendered
     * chain is provably shorter than the chain supplied, which is the property that matters -- a chain a
     * provider built cyclically must not be able to make a log line unbounded.</p>
     */
    @Test
    @DisplayName("a cause chain longer than the walk bound is truncated")
    void aDeepCauseChainIsBounded() {
        Throwable cause = new IllegalArgumentException("depth 0");
        for (int depth = 1; depth < 20; depth++) {
            cause = new IllegalStateException("depth " + depth, cause);
        }

        this.handler.onUnexpectedFailure(new IllegalStateException("outermost", cause),
                requestFor(CARD_PATH));

        assertThat(this.captured.list).hasSize(1);
        String logged = this.captured.list.get(0).getFormattedMessage();
        assertThat(logged).endsWith(ThrowableDigest.TRUNCATION_MARKER);
        // WHY : Assumptions: the count is compared against the published bound rather than against a
        //       literal, so the case follows the bound if it is ever retuned instead of failing for a
        //       reason that has nothing to do with what it fixes. The truncation marker itself contains
        //       the separator, which is why the expected count is the bound rather than the bound plus
        //       one: the trailing marker contributes the last split.
        assertThat(logged.split(ThrowableDigest.CAUSE_SEPARATOR, -1))
                .hasSize(ThrowableDigest.MAX_CAUSE_DEPTH + 1);
        assertThat(logged).doesNotContain("depth ");
    }

    /**
     * A failure whose cause accessor returns the failure itself, which the platform initialiser forbids.
     *
     * <p>Assumptions: this exists only so the guard against self-causation is exercised against a real
     * throwable rather than against a mock, and it is declared private so nothing outside this test can
     * take it for a general-purpose type.</p>
     */
    private static final class SelfCausingFailure extends RuntimeException {

        /** Serialisation identity, declared because the parent is serialisable. */
        private static final long serialVersionUID = 1L;

        /** Creates the failure with a fixed message that carries no migrated data. */
        SelfCausingFailure() {
            super("self-causing");
        }

        /**
         * Returns this failure as its own cause.
         *
         * @return {@code this}, never {@code null}
         */
        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    /**
     * Confirms every status this advice emits is a real HTTP status inside the published code range.
     *
     * <p>Assumptions: this is where the status half of the shape's construction contract is decided,
     * rather than inside the factory. The factory renders a failure, so refusing an argument there
     * would replace an odd status with no body at all; every status the system actually emits comes
     * from a handler, and a handler's status is decidable without running the application, so
     * asserting the whole set here places the failure at build time without adding a way for an error
     * response to fail.</p>
     */
    @Test
    @DisplayName("every emitted status is inside the HTTP status range")
    void everyEmittedStatusIsAValidHttpStatus() {
        MockHttpServletRequest request = requestFor(CARD_PATH);

        assertThat(this.handler.onMissingRecord(new NoSuchElementException(), request).getBody())
                .isNotNull()
                .satisfies(body -> assertThat(body.status()).isEqualTo(HttpStatus.NOT_FOUND.value()));
        assertThat(this.handler.onAccessDenied(new AccessDeniedException("d"), request).getBody())
                .isNotNull()
                .satisfies(body -> assertThat(body.status()).isEqualTo(HttpStatus.FORBIDDEN.value()));
        assertThat(this.handler.onRejectedCallerInput(new ClientInputException("X", "x"), request)
                .getBody())
                .isNotNull()
                .satisfies(body ->
                        assertThat(body.status()).isEqualTo(HttpStatus.BAD_REQUEST.value()));
        assertThat(this.handler.onUnexpectedFailure(new IllegalStateException("x"), request).getBody())
                .isNotNull()
                .satisfies(body -> assertThat(body.status())
                        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value()));
        assertThat(this.handler
                        .onWritesQuiesced(new OnlineWritesDisabledException("closed"), request)
                        .getBody())
                .isNotNull()
                .satisfies(body -> assertThat(body.status())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value()));
    }

    /**
     * Confirms a write refused during the batch window is rendered as 503 and not as a failure.
     *
     * <p>Assumptions: the STATUS and the SEVERITY are asserted together, because the two carry
     * different halves of one statement and only one of them is obvious. A 503 alone would be derived
     * by this type's own status rule into a critical severity, and every write attempted during a
     * scheduled nightly window would then be recorded as a critical entry for a control working exactly
     * as designed -- which is how a severity field stops being read at all.</p>
     *
     * <p>Assumptions: the refusal's own message is asserted NOT to reach the body. The gate raises a
     * message naming the condition, but the body must carry the migration's own sentence so that one
     * wording answers a closed window everywhere, and so that a cause carried for an operator -- an
     * access denial or a timeout on the flag read -- cannot reach a caller through it.</p>
     */
    @Test
    @DisplayName("a write refused during the batch window is a 503 carrying a warning severity")
    void quiescedWriteIsRenderedAsServiceUnavailable() {
        OnlineWritesDisabledException refusal = new OnlineWritesDisabledException(
                "flag /carddemo/dev/batch/online-writes-enabled could not be read",
                new IllegalStateException("access denied"));

        ResponseEntity<ApiError> response =
                this.handler.onWritesQuiesced(refusal, requestFor(CARD_PATH));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ApiError.CODE_WRITES_QUIESCED);
        assertThat(response.getBody().status()).isEqualTo(ApiError.SERVICE_UNAVAILABLE_STATUS);
        assertThat(response.getBody().severity()).isEqualTo(ApiError.Severity.WARNING);
        assertThat(response.getBody().message())
                .isEqualTo(GlobalExceptionHandler.MESSAGE_WRITES_QUIESCED);
        assertThat(response.getBody().fieldErrors()).isEmpty();

        assertThat(response.getBody().message())
                .as("neither the parameter nor the underlying failure may reach a caller")
                .doesNotContain("online-writes-enabled")
                .doesNotContain("access denied");
        assertThat(response.getBody().path())
                .as("the shared path narrowing applies to this body as it does to every other")
                .isEqualTo(MASKED_CARD_PATH);
    }

    /**
     * Confirms a body that declares a check order gets its entries ordered by it, and gets the FIRST
     * declared failure as the aggregate message.
     *
     * <p>Refactoring Rationale: a validation provider reports constraint violations in an order it does
     * not define, so the aggregate message a screen displays was previously whichever failure the
     * provider happened to report first. The reference checks its fields in a fixed order and displays
     * the first failure it reaches -- the sign-on program tests the user identifier before the password
     * at lines 118 to 126 of {@code app/cbl/COSGN00C.cbl} -- so an unordered aggregate can show the
     * second failure of two and send a user to the wrong control.</p>
     *
     * <p>Assumptions: the entries are handed in DEFERRED order -- password before user identifier -- so
     * a passing assertion cannot be explained by the provider order already being correct. The
     * unrecognised name is included because a declared order that omits a field must degrade to placing
     * that field last rather than dropping its entry.</p>
     */
    @Test
    @DisplayName("a body declaring a check order latches its first declared failure as the aggregate")
    void declaredFieldOrderDecidesTheAggregateMessage() {
        OrderedBody target = new OrderedBody();
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(target, "orderedBody");
        binding.addError(new FieldError("orderedBody", "password", "Please enter Password ...", false,
                null, null, "Please enter Password ..."));
        binding.addError(new FieldError("orderedBody", "surprise", "unknown field", false, null, null,
                "unknown field"));
        binding.addError(new FieldError("orderedBody", "userId", "Please enter User ID ...", false,
                null, null, "Please enter User ID ..."));

        ApiError body = this.handler
                .onInvalidBody(new MethodArgumentNotValidException(null, binding), requestFor(CARD_PATH))
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.message()).isEqualTo("Please enter User ID ...");
        assertThat(body.fieldErrors()).extracting(ApiError.FieldError::field)
                .containsExactly("userId", "password", "surprise");
    }

    /**
     * A request body that declares its check order, standing in for a real one.
     *
     * <p>Assumptions: this is declared here rather than reusing a service transfer object, because the
     * shared kernel may not depend on a service package and a test that did would make the shared
     * assertion unrunnable from the kernel's own module.</p>
     */
    private static final class OrderedBody implements FieldOrdering {

        /** The identifier field, checked first, matching the reference's own order. */
        private String userId;

        /** The secret field, checked second. */
        private String password;

        /**
         * Reports the declared check order.
         *
         * @return the two field names in the order the reference checks them, never {@code null}
         */
        @Override
        public java.util.List<String> fieldOrder() {
            return java.util.List.of("userId", "password");
        }

        /**
         * Reports the identifier, present so the declared field is a real property.
         *
         * @return the identifier, which may be {@code null}
         */
        String getUserId() {
            return this.userId;
        }

        /**
         * Reports the secret, present so the declared field is a real property.
         *
         * @return the secret, which may be {@code null}
         */
        String getPassword() {
            return this.password;
        }
    }

    /**
     * Builds a request reporting the given URI, with no query string.
     *
     * @param uri the request URI a container would report, never {@code null}
     * @return a request whose {@code getRequestURI} returns exactly {@code uri}
     */
    private static MockHttpServletRequest requestFor(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    /**
     * Reads the formatted messages the advice logged during the current test.
     *
     * @return one entry per captured event, each already interpolated, so an assertion reads what a
     *     log pipeline would ship rather than the pattern and its arguments separately
     */
    private java.util.List<String> loggedMessages() {
        return this.captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Confirms the 500 site attaches no throwable to its event, so nothing downstream can render one.
     *
     * <p>Assumptions: the throwable proxy is asserted absent rather than the message being asserted
     * clean, because the two are different guarantees and only this one is structural. A logging event
     * that carries a throwable is rendered by whatever pattern the deployment happens to configure, and
     * every default pattern prints the exception message and every cause's message. Since this
     * repository ships NO appender configuration at all -- there is no {@code logback.xml},
     * {@code logback-spring.xml} or {@code log4j2.xml} anywhere in it, deliberately, because three
     * environment profiles record that adding one would take over the appender chain wholesale -- an
     * attached throwable is rendered in full by the framework default. Asserting the proxy is null
     * proves the disclosure is impossible rather than merely absent from this build's output.</p>
     */
    @Test
    @DisplayName("an unexpected failure attaches no throwable to its logging event")
    void unexpectedFailureAttachesNoThrowableToItsEvent() {
        this.handler.onUnexpectedFailure(
                new IllegalStateException("card " + CARD_NUMBER + " could not be posted"),
                requestFor("/api/v1/accounts/00000000011"));

        assertThat(this.captured.list).isNotEmpty();
        assertThat(this.captured.list)
                .allSatisfy(event -> assertThat(event.getThrowableProxy())
                        .describedAs("no logging event from the 500 site may carry a throwable, because"
                                + " the framework default renders its message and every cause's message")
                        .isNull());
    }

    /**
     * Confirms no message text from any link of a caught cause chain reaches the log line.
     *
     * <p>Assumptions: three links carry three different sentinels, so an implementation that dropped
     * only the outermost message -- the most plausible partial fix -- fails on the second sentinel
     * rather than passing. The sentinels are shaped like the values that actually matter: an unmasked
     * account number, a national identifier and a fixed-width record image, which are respectively what
     * a driver, a validator and a codec quote when they fail.</p>
     */
    @Test
    @DisplayName("no message from any link of the caught cause chain reaches the log line")
    void noCaughtMessageReachesTheLogLine() {
        String nationalIdentifier = "123-45-6789";
        String recordImage = "DOE       JOHN      0000012345";
        Exception deepest = new NumberFormatException("cannot parse '" + recordImage + "'");
        Exception middle = new IllegalArgumentException(
                "customer " + nationalIdentifier + " rejected", deepest);
        Exception outermost = new IllegalStateException(
                "posting " + CARD_NUMBER + " failed", middle);

        this.handler.onUnexpectedFailure(outermost, requestFor("/api/v1/accounts/00000000011"));

        assertThat(loggedMessages()).isNotEmpty();
        assertThat(loggedMessages()).allSatisfy(line -> assertThat(line)
                .doesNotContain(CARD_NUMBER)
                .doesNotContain(nationalIdentifier)
                .doesNotContain(recordImage));
    }

    /**
     * Confirms the reduction still names the failing types, so the log line stays diagnostic.
     *
     * <p>Assumptions: this is the counterweight to the two omission tests above. A site that logged
     * neither the messages nor anything else about the failure would satisfy both of them while being
     * useless, so the three type names of the chain are asserted present -- which is exactly the
     * information an operator uses to decide where to look.</p>
     */
    @Test
    @DisplayName("the reduced representation still names every type in the caught chain")
    void theReducedRepresentationStillNamesEveryType() {
        Exception cause = new NumberFormatException("no sentinel");
        Exception outermost = new IllegalStateException("no sentinel", cause);

        this.handler.onUnexpectedFailure(outermost, requestFor("/api/v1/accounts/00000000011"));

        assertThat(loggedMessages()).anySatisfy(line -> assertThat(line)
                .contains(IllegalStateException.class.getName())
                .contains(NumberFormatException.class.getName()));
    }
}
