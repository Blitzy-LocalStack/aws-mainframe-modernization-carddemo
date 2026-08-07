package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.web.CorrelationIdFilter;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the published pending-authorization contract and this module's compiled constants to each other.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: the review that prompted this class found six statements in the contract that a
 * compiler cannot see and that had each drifted from the code. The problem shape's code member was bounded
 * at nine characters while every code the shared kernel emits is thirteen, so a conforming client would
 * reject every error body it received. A success body admitted a failure status the write path cannot reach.
 * The shared error message was bounded at this context's own screen width rather than the shared kernel's.
 * The correlation domain published a superset of what the shared filter accepts, so the document described
 * requests the service refuses. The conflict example named a subsystem the assembling factory did not emit.
 * And the 401 declared a body the security chain did not produce and omitted the challenge header it did.
 * Every one of those is a string or a number inside a YAML document, so the only durable guard is a test
 * that reads the document and compares it against the constant it mirrors.
 *
 * <p>Assumptions: the contract is read from the classpath, so these assertions are made against the artifact
 * this module packages rather than against a source file that may not be the one shipped.
 *
 * <p>Alternatives Considered: asserting these properties with a running application context and a request
 * per route, which is the stronger form. Not available at this checkpoint — no controller is authored — and
 * deferring the comparison until one is would leave the contract unchecked over exactly the interval in
 * which these corrections were made.
 */
class AuthorizationApiContractTest {

    /** Classpath location of the contract this module publishes. */
    private static final String CONTRACT_RESOURCE = "/openapi/authorization-api.yaml";

    /** The parsed contract, loaded once because it is immutable for the run. */
    private static final Map<String, Object> CONTRACT = loadContract();

    /**
     * Reads and parses the published contract from the classpath.
     *
     * @return the parsed document, never {@code null}
     * @throws IllegalStateException if the resource is absent or is not a mapping, either of which means the
     *     module packaged no usable contract and every assertion below would otherwise fail obscurely
     */
    private static Map<String, Object> loadContract() {
        try (InputStream resource =
                AuthorizationApiContractTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException(
                        "the published contract is absent from the classpath at " + CONTRACT_RESOURCE);
            }
            Object parsed = new Yaml().load(resource);
            if (!(parsed instanceof Map)) {
                throw new IllegalStateException(
                        "the published contract at " + CONTRACT_RESOURCE + " is not a mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> document = (Map<String, Object>) parsed;
            return document;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("the published contract could not be read", failure);
        }
    }

    /**
     * Descends one level into a mapping, failing loudly when the shape is not what the caller assumed.
     *
     * @param parent the mapping to read from
     * @param key the key to descend through
     * @return the nested mapping, never {@code null}
     * @throws IllegalStateException if the value is absent or is not a mapping
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map)) {
            throw new IllegalStateException("expected a mapping at \"" + key + "\"");
        }
        return (Map<String, Object>) value;
    }

    /**
     * Returns one named schema from the document's component set.
     *
     * @param name the schema name
     * @return the schema mapping, never {@code null}
     */
    private static Map<String, Object> schema(String name) {
        return mapping(mapping(mapping(CONTRACT, "components"), "schemas"), name);
    }

    /**
     * Returns one named response from the document's component set.
     *
     * @param name the response name
     * @return the response mapping, never {@code null}
     */
    private static Map<String, Object> response(String name) {
        return mapping(mapping(mapping(CONTRACT, "components"), "responses"), name);
    }

    /**
     * The problem shape's code bound admits every code the shared kernel can emit.
     *
     * <p>Assumptions: the codes are of the form {@code CARDDEMO-nnnn}, which is thirteen characters, and
     * three of the kernel's own constants are checked rather than one so a bound that happened to fit the
     * shortest of them cannot pass.</p>
     */
    @Test
    @DisplayName("the problem code bound admits every code the shared kernel emits")
    void problemCodeBoundAdmitsEveryEmittedCode() {
        Map<String, Object> properties = mapping(schema("ApiError"), "properties");
        int declared = (Integer) mapping(properties, "code").get("maxLength");

        for (String code : List.of(ApiError.CODE_INTERNAL, ApiError.CODE_CONFLICT,
                ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED, GlobalExceptionHandler.CODE_FORBIDDEN)) {
            assertThat(code.length())
                    .as("the published code bound of %d refuses '%s', which the shared kernel emits",
                            declared, code)
                    .isLessThanOrEqualTo(declared);
        }
    }

    /**
     * The shared error message is bounded at the shared kernel's width, not at this context's screen width.
     *
     * <p>Assumptions: the two widths are 75 and 78 and they are different regimes rather than a rounding.
     * 75 is the house error and return message contract every service shares; 78 is the width of the
     * {@code ERRMSG} field on this context's own two maps. A body assembled by the shared advice can only
     * ever carry the narrower of the two, so publishing the wider one on a shared member would describe a
     * value nothing produces.</p>
     */
    @Test
    @DisplayName("the shared error message is bounded at the shared width and the screen message at its own")
    void sharedAndScreenMessageWidthsAreDistinct() {
        assertThat(schema("SharedErrorMessage").get("maxLength")).isEqualTo(75);
        assertThat(schema("ScreenMessage").get("maxLength")).isEqualTo(78);

        Map<String, Object> apiErrorMessage =
                mapping(mapping(schema("ApiError"), "properties"), "message");
        assertThat(apiErrorMessage.toString())
                .as("the problem shape's message must reference the shared width, not the screen width")
                .contains("SharedErrorMessage");
    }

    /**
     * The fraud action domain is exactly the two characters the reference program persists.
     *
     * <p>Assumptions: the domain is asserted against the two literals rather than against a constant
     * exported from {@code FraudMarkRequest}, because that record keeps its pattern private and publishes
     * no action constants. The literals are the reference program's own: {@code cbl/COPAUS2C.cbl} declares
     * {@code 88 WS-REPORT-FRAUD VALUE 'F'} at L81 and {@code 88 WS-REMOVE-FRAUD VALUE 'R'} at L82, and its
     * L137 moves whichever arrived straight into the fraud row's column, so the published pair IS the pair
     * persisted. Reading them from a constant would only move the question one file along.</p>
     */
    @Test
    @DisplayName("the fraud action domain is exactly the two reference characters")
    void fraudActionDomainIsTheTwoReferenceCharacters() {
        @SuppressWarnings("unchecked")
        List<String> published = (List<String>) mapping(
                mapping(schema("FraudMarkRequest"), "properties"), "action").get("enum");

        assertThat(published).containsExactly("F", "R");
    }

    /**
     * A successful fraud write publishes only the success status, a failure being a non-2xx body.
     *
     * <p>Assumptions: the member is declared {@code const} rather than a one-value {@code enum}, and the
     * assertion reads the {@code const} key for that reason. A body describing a completed write and a
     * failed write at once has no reference counterpart: {@code cbl/COPAUS1C.cbl} L255-L258 performs the
     * segment update only when the write reported success and otherwise takes its rollback path.</p>
     */
    @Test
    @DisplayName("a 200 fraud body publishes only the success status")
    void successBodyPublishesOnlyTheSuccessStatus() {
        Map<String, Object> updateStatus =
                mapping(mapping(schema("FraudMarkResponse"), "properties"), "updateStatus");

        assertThat(updateStatus.get("const")).isEqualTo("S");
        assertThat(updateStatus).doesNotContainKey("enum");

        assertThat(mapping(schema("FraudMarkResponse"), "properties").keySet())
                .as("the write response carries the response direction of the reference area and nothing"
                        + " more: which write path ran is carried on the status code, 201 against 200")
                .containsExactly("updateStatus", "message");
    }

    /**
     * The match-status domain published for a row is the one the row record enforces.
     */
    @Test
    @DisplayName("the published match-status domain is the one the row record enforces")
    void matchStatusDomainMatchesTheRowRecord() {
        @SuppressWarnings("unchecked")
        List<String> published = (List<String>) mapping(
                mapping(schema("PendingAuthListItem"), "properties"), "matchStatus").get("enum");

        assertThat(published).containsExactlyElementsOf(PendingAuthRowView.MATCH_STATUSES);
    }

    /**
     * The published correlation domain is exactly what the shared filter accepts, neither wider nor narrower.
     *
     * <p>Assumptions: the comparison is made over vectors on both sides of the boundary rather than only the
     * refused side. The four card-number forms must be refused because this identity is echoed on the
     * response and written to every log line; a timestamp-like value, a value carrying a letter and a
     * minted-shaped value must be accepted, each being a realistic identity a caller or this service
     * supplies.</p>
     */
    @Test
    @DisplayName("the published correlation domain is exactly the shared filter's")
    void correlationDomainMatchesTheSharedFilter() {
        Map<String, Object> correlationId = schema("CorrelationId");
        assertThat(correlationId.get("maxLength"))
                .isEqualTo(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);

        Pattern declared = Pattern.compile(String.valueOf(correlationId.get("pattern")));
        assertThat(declared.matcher("").matches())
                .as("an absent identity is rendered as the empty string inside an error body")
                .isTrue();

        for (String vector : List.of("4111111111111111", "4111-1111-1111-1111", "4111.1111.1111.1111",
                "4111_1111_1111_1111", "1234567890123", "123456789012", "2022-07-18-0930",
                "a1b2c3d4e5f6a7b8c9d0e1f2", "CD0123456789ABCDEF012345")) {
            assertThat(declared.matcher(vector).matches())
                    .as("the published pattern and"
                            + " CorrelationIdFilter.isConformingCorrelationId disagree on '%s'", vector)
                    .isEqualTo(CorrelationIdFilter.isConformingCorrelationId(vector));
        }
    }

    /**
     * The conflict example names the subsystem the assembling factory actually emits.
     *
     * <p>Assumptions: all three conflicts the shared advice recognises are raised by the datastore -- a
     * version check, a lock acquisition and a foreign-key check -- so the relational subsystem is the
     * correct value rather than the convenient one.</p>
     */
    @Test
    @DisplayName("the conflict example names the relational subsystem the factory emits")
    void conflictExampleNamesTheEmittedSubsystem() {
        Map<String, Object> example = mapping(mapping(mapping(
                response("Conflict"), "content"), "application/json"), "example");

        assertThat(example.get("subsystem")).isEqualTo(ApiError.Subsystem.RELATIONAL.name());
        assertThat(example.get("code")).isEqualTo(ApiError.CODE_CONFLICT);
        assertThat(example.get("status")).isEqualTo(409);
    }

    /**
     * The 401 declares both the challenge header the resource server sends and the problem body.
     *
     * <p>Refactoring Rationale: the header is asserted because it is the one the OAuth 2.0 bearer-token
     * specification requires and a client's refresh logic reads. The shared entry point delegates to the
     * framework's own entry point first precisely so the challenge is composed unchanged, and a document
     * that declared the body while omitting the header left a generated client with no typed access to the
     * header it needs on the one response it needs it on.</p>
     */
    @Test
    @DisplayName("the 401 declares the challenge header as well as the problem body")
    void unauthorizedDeclaresTheChallengeHeaderAndTheBody() {
        Map<String, Object> headers = mapping(response("Unauthorized"), "headers");

        assertThat(headers).containsKeys(CorrelationIdFilter.CORRELATION_ID_HEADER, "WWW-Authenticate");
        assertThat(mapping(mapping(response("Unauthorized"), "content"), "application/json").toString())
                .contains("ApiError");
        assertThat(String.valueOf(response("Unauthorized").get("description")))
                .as("the 401 description must name the code the shared entry point renders")
                .contains(ApiErrorSecurityHandlers.CODE_UNAUTHENTICATED);
        assertThat(String.valueOf(response("Forbidden").get("description")))
                .as("the 403 description must name the code the shared denied handler renders")
                .contains(GlobalExceptionHandler.CODE_FORBIDDEN);
    }

    /**
     * The 400 names every per-field key this module's own code can emit, and no key it cannot.
     *
     * <p>Refactoring Rationale: this assertion exists because the published set was wrong in a way no
     * compiler could catch. It named {@code fraudAction} for the fraud body's action member, and no such
     * property exists -- the request schema declares {@code action}, and that is the name the shared advice
     * reports, since it keys a rejected body member by its record component. A client written against that
     * list had a control to mark for a refusal it can never receive, and none for the refusal it can.</p>
     *
     * <p>Assumptions: the set is small because the fraud body declares ONE member. An earlier revision of
     * that body repeated the row's three key components, and the published set named them; they are gone
     * from both, and the second half of this test is what holds them gone -- a member reinstated on the
     * body without being published here fails the loop, and a key published here that no member produces
     * fails the closing assertion.</p>
     *
     * <p>Assumptions: the body-member half of the set is read from the request SCHEMA rather than restated,
     * so a member renamed in the document cannot leave this list stale. The parameter half is read from the
     * document's own parameter components for the same reason. Only the literal {@code request} is written
     * out, because it is the shared advice's own fallback and belongs to no declared member.</p>
     */
    @Test
    @DisplayName("the 400 names every per-field key the module can emit and no key it cannot")
    void badRequestNamesEveryEmittablePerFieldKey() {
        String published = String.valueOf(response("BadRequest").get("description"));

        Map<String, Object> requestMembers =
                mapping(schema("FraudMarkRequest"), "properties");
        for (String member : requestMembers.keySet()) {
            assertThat(published)
                    .as("the 400 must name fraud-request member %s, which a domain violation keys", member)
                    .contains(member);
        }

        for (String parameter : List.of("AccountIdScope", "Cursor", "PagingDirection",
                "AuthorizationKeyPath")) {
            String name = String.valueOf(parameter(parameter).get("name"));
            assertThat(published)
                    .as("the 400 must name parameter %s, which a constraint on it keys", name)
                    .contains(name);
        }

        assertThat(published)
                .as("the shared advice's own fallback key must be named as well")
                .contains("request");
        assertThat(published)
                .as("a key no member of this module produces must not be published as one")
                .doesNotContain("fraudAction");
    }

    /**
     * Returns one named parameter from the document's component set.
     *
     * @param name the parameter component name
     * @return the parameter mapping, never {@code null}
     */
    private static Map<String, Object> parameter(String name) {
        return mapping(mapping(mapping(CONTRACT, "components"), "parameters"), name);
    }
}
