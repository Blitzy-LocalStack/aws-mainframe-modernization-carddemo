package com.carddemo.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.validation.FieldValidationFlag;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the emitted JSON property set of the shared problem shape and of one field entry.
 *
 * <p>Purpose: assert, through a real mapper of the generation the services resolve, exactly which
 * properties {@link ApiError} and {@link ApiError.FieldError} put on the wire — no more and no fewer —
 * so that the seven published OpenAPI contracts can be read as true statements about a response rather
 * than as an intention about one.
 *
 * <p>Refactoring Rationale: every one of the seven contracts declares both schemas with
 * {@code additionalProperties: false}, which makes the emitted property set part of the contract rather
 * than an implementation detail. Nothing asserted it before, and the consequence was already live: the
 * derived {@code isError()} predicate on the field entry was published as a fourth {@code error}
 * property, so six of the seven contracts described a body their own service could not send, and the
 * seventh had been amended to admit the surplus member instead of the member being withdrawn. A
 * property set that no test reads is a property set that drifts silently, because neither the compiler
 * nor a schema validator run against a live response sees it.
 *
 * <p>Alternatives Considered: asserting the shape through a {@code @WebMvcTest} in each service, using
 * {@code jsonPath} on a real response. Rejected as the primary check because the shape belongs to this
 * module and would then be pinned seven times over, in seven places that can disagree; and because a
 * {@code jsonPath} assertion names the properties it expects to FIND and stays silent about a surplus
 * one, which is the exact failure mode here. Comparing the whole key set is what catches an addition.
 *
 * <p>Assumptions: the mapper is a bare {@link JsonMapper} with no module and no Spring context. Neither
 * schema carries a monetary amount, so the money module is irrelevant to them, and a context-backed
 * mapper could pass because a service's own configuration happened to suppress a member — which would
 * leave the property set undefined for any service configured differently.
 */
final class ApiErrorWireShapeTest {

    /** A fixed instant, so the emitted timestamp is a value rather than a moving one. */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2022-07-18T22:10:31Z"), ZoneOffset.UTC);

    /** The correlation identifier echoed into every problem shape built here. */
    private static final String CORRELATION_ID = "11111111-2222-3333-4444-555555555555";

    /**
     * Builds the mapper the assertions read through.
     *
     * @return a mapper with no module registered, never {@code null}
     */
    private static ObjectMapper mapper() {
        return JsonMapper.builder().build();
    }

    /**
     * Reads the top-level property names of one serialised value, in no particular order.
     *
     * @param value the value to serialise and inspect; must not be {@code null}
     * @return the emitted top-level property names, never {@code null}
     */
    private static List<String> propertiesOf(Object value) {
        ObjectMapper objectMapper = mapper();
        return List.copyOf(objectMapper.readTree(objectMapper.writeValueAsString(value)).propertyNames());
    }

    /**
     * Asserts a field entry emits its three record components and nothing else.
     *
     * <p>Assumptions: the two derived accessors are asserted ABSENT by name as well as through the set
     * comparison. The set comparison is what fails on any surplus member, and naming these two is what
     * says which surplus members were once present, so a reader of a failure knows whether the
     * annotation was removed or something new was added.
     */
    @Test
    @DisplayName("a field entry emits exactly field, state and message")
    void fieldEntryEmitsExactlyItsThreeComponents() {
        ApiError.FieldError entry = new ApiError.FieldError(
                "userId", FieldValidationFlag.BLANK, "Please enter User ID ...");

        assertThat(propertiesOf(entry))
                .as("the three components the seven contracts declare, with additionalProperties false")
                .containsExactlyInAnyOrder("field", "state", "message")
                .doesNotContain("error", "screenMarker");
    }

    /**
     * Asserts the derived predicate is still callable in the JVM despite being withheld from the wire.
     *
     * <p>Assumptions: this is the other half of the withdrawal. Suppressing the property by DELETING the
     * predicate would also have satisfied the contract, and would have taken a value callers inside the
     * JVM use, so the test states that the predicate survives and only its publication was removed.
     */
    @Test
    @DisplayName("the derived error predicate remains callable although it is not published")
    void derivedPredicateSurvivesItsWithdrawalFromTheWire() {
        ApiError.FieldError blank = new ApiError.FieldError(
                "userId", FieldValidationFlag.BLANK, "Please enter User ID ...");
        ApiError.FieldError refused = new ApiError.FieldError(
                "userId", FieldValidationFlag.NOT_OK, "User ID must be eight characters");

        assertThat(blank.isError()).isTrue();
        assertThat(refused.isError()).isTrue();
        assertThat(blank.screenMarker()).isEqualTo(FieldValidationFlag.BLANK_SCREEN_MARKER);
        assertThat(refused.screenMarker()).isEqualTo(FieldValidationFlag.NO_SCREEN_MARKER);
    }

    /**
     * Asserts the problem shape emits all eleven components, including the two that may be null.
     *
     * <p>Refactoring Rationale: this is the assertion the {@code required} lists of the seven contracts
     * rest on. A record component is always written, so {@code message} and {@code abend} are PRESENT
     * with a null value rather than absent when there is nothing to report — which is a different
     * statement from being optional, and the one the contracts have to make. A client generated from a
     * contract that called them optional would treat a null as "not supplied" and a missing key as the
     * same thing, and the two are not the same thing here: the key is never missing.
     */
    @Test
    @DisplayName("the problem shape emits all eleven members, message and abend included as null")
    void problemShapeEmitsEveryMemberIncludingTheNullableOnes() {
        ApiError withoutMessageOrAbend = new ApiError(
                ApiError.CODE_INTERNAL,
                "",
                null,
                ApiError.Severity.CRITICAL,
                ApiError.Subsystem.APPLICATION,
                500,
                CORRELATION_ID,
                "/api/v1/accounts/00000000011",
                "2022-07-18 22:10:31.000000",
                List.of(),
                null);

        assertThat(propertiesOf(withoutMessageOrAbend))
                .as("every member the contracts declare, none of them omitted for being null")
                .containsExactlyInAnyOrder(
                        "code",
                        "secondaryCode",
                        "message",
                        "severity",
                        "subsystem",
                        "status",
                        "correlationId",
                        "path",
                        "timestamp",
                        "fieldErrors",
                        "abend");

        String json = mapper().writeValueAsString(withoutMessageOrAbend);
        assertThat(json).contains("\"message\":null").contains("\"abend\":null");
    }

    /**
     * Asserts a validation problem carries its entries in the emitted array under the same three keys.
     *
     * <p>Assumptions: the nested assertion matters because the field entry is reached through an array
     * rather than directly, and Jackson resolves a property set per TYPE rather than per position. A
     * surplus member would therefore appear here too, and asserting it at both depths is what makes the
     * absence a property of the type rather than of one call site.
     */
    @Test
    @DisplayName("nested field entries in a validation problem carry the same three keys")
    void nestedFieldEntriesCarryTheSameThreeKeys() {
        ApiError problem = ApiError.ofFieldErrors(
                "Please correct the highlighted fields",
                400,
                CORRELATION_ID,
                "/api/v1/users",
                List.of(new ApiError.FieldError(
                        "userId", FieldValidationFlag.BLANK, "Please enter User ID ...")),
                FIXED);

        ObjectMapper objectMapper = mapper();
        assertThat(objectMapper
                        .readTree(objectMapper.writeValueAsString(problem))
                        .get("fieldErrors")
                        .get(0)
                        .propertyNames())
                .containsExactlyInAnyOrder("field", "state", "message");
    }
}
