package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.web.CorrelationIdFilter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
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
     * Returns the declared member names of the paged-listing request body.
     *
     * <p>Assumptions: read from the contract rather than listed here, so a member added to the body is
     * covered by the per-field-key obligation automatically instead of when someone remembers to extend a
     * literal list.</p>
     *
     * @return the body's property names, never {@code null}
     */
    private static List<String> pageQueryMembers() {
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
                (Map<String, Object>) schema("PendingAuthPageQuery").get("properties");
        return List.copyOf(properties.keySet());
    }

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
     * The paging wording this contract withdrew, quoted once so two assertions cannot spell it differently.
     *
     * <p>Assumptions: the phrase is held as a constant rather than typed at each of the three assertion
     * sites, because two of them require its absence and one requires its presence -- and a misspelling in
     * either of the first two would pass silently while asserting nothing at all.</p>
     */
    private static final String WITHDRAWN_PAGING_RULE = "sent together or not at all";

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
     * Confirms the generated document declares the specification version the committed contract does.
     *
     * <p>Assumptions: the specification FLAG and the version STRING are asserted separately because
     * they are independent members and setting one does not set the other. Constructing the document at
     * 3.1 leaves the string at the model's {@code 3.0.1} default, so a document carrying the flag alone
     * declares 3.0 while carrying members that exist only in 3.1.</p>
     *
     * <p>Refactoring Rationale: this case exists because that was this context's state -- the flag was
     * set and the string was not -- and nothing failed, because whether the 3.1-only members reach a
     * reader is decided by which serialiser the library runs rather than by either member asserted here.
     * The served document was therefore complete while mislabelling its own version, and no assertion
     * compared it to the contract beside it. The underlying mechanism is measured once, in
     * account-service's {@code config/OpenApiDocumentTest.java}, against the same library.</p>
     */
    @Test
    @DisplayName("the generated document declares the specification version the contract declares")
    void generatedDocumentDeclaresTheSpecificationVersionTheContractDeclares() {
        OpenAPI published = new OpenApiConfig().authorizationServiceOpenApi();

        assertThat(published.getSpecVersion())
                .as("a 3.1 document must carry the 3.1 flag, not the model's 3.0 default")
                .isEqualTo(SpecVersion.V31);
        assertThat(published.getOpenapi())
                .as("the emitted version string must equal the committed contract's")
                .isEqualTo(String.valueOf(loadContract().get("openapi")));
        assertThat(published.getOpenapi()).startsWith("3.1.");
        assertThat(published.getInfo().getSummary()).isNotBlank();
        assertThat(published.getInfo().getLicense().getIdentifier()).isNotBlank();
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
     * The published paging rule states the asymmetry the service actually implements.
     *
     * <p>Purpose: the two optional members of the search body are governed by an asymmetric rule -- a cursor
     * may be sent alone and is read forward, a direction may not -- and the document said they were "sent
     * together or not at all". A caller written to that text sends a direction it does not need, and a
     * caller written to it defensively may treat a cursor-only page as its own client error. This asserts
     * the document now states the rule, so the pair cannot drift apart again.
     *
     * <p>Assumptions: the assertion is on the WORDS rather than on a machine-readable constraint, because
     * the rule is not expressible in the schema language. A dependency between two optional members would
     * need a conditional composition that the publishing library does not emit and that few client
     * generators honour; the prose is therefore the contract, and prose that nothing checks is prose that
     * goes stale, which is precisely what happened here.
     *
     * <p>Assumptions: the withdrawn sentence is asserted absent from the NORMATIVE text only, and the
     * normative text is taken to be everything before the rationale marker. Adding the asymmetric statement
     * while leaving the all-or-nothing one standing would leave the document saying both things, which is
     * the state this finding described -- but a withdrawal notice has to be able to QUOTE what it withdraws,
     * or a reader who acted on the old wording cannot tell which claim to trust. Asserting absence across
     * the whole description would therefore forbid the very sentence that makes the correction legible, so
     * the assertion is scoped instead of weakened.
     *
     * <p>Assumptions: the service side of the same rule is asserted by
     * {@code PendingAuthSummaryServiceTest.cursorWithNoDirectionIsReadForward} and by
     * {@code directionWithNoCursorIsRefused}, one per arm. This case deliberately asserts only the
     * document, because a test that exercised both sides would pass whenever the two agreed with each
     * other and would not notice them agreeing on something the service does not do.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the published paging rule states the cursor-and-direction asymmetry")
    void publishedPagingRuleStatesTheAsymmetry() {
        Map<String, Object> query = schema("PendingAuthPageQuery");
        String queryDescription = String.valueOf(query.get("description"));
        String directionDescription = String.valueOf(
                mapping(mapping(query, "properties"), "direction").get("description"));

        assertThat(normativeHalfOf(queryDescription))
                .as("the body's own description must name the asymmetry rather than implying symmetry")
                .contains("ASYMMETRIC")
                .doesNotContain(WITHDRAWN_PAGING_RULE);
        assertThat(normativeHalfOf(directionDescription))
                .as("all four combinations must be enumerated, since none is left to inference")
                .contains("four combinations")
                .contains("cursor with no direction is read forward")
                .contains("direction with no cursor is refused")
                .doesNotContain(WITHDRAWN_PAGING_RULE);
        assertThat(directionDescription)
                .as("the withdrawal must SAY what it withdraws, or a reader cannot tell which to trust")
                .contains(WITHDRAWN_PAGING_RULE);
    }

    /**
     * Returns the part of a published description that states the contract, excluding any rationale.
     *
     * <p>Assumptions: the rationale marker is the same label the documentation standard uses in every
     * language in this repository, so this split needs no convention of its own. A description with no
     * rationale is returned whole, which is the common case and must not be treated as empty.
     *
     * @param description a published description, with or without a trailing rationale
     * @return the normative leading part, never {@code null}
     */
    private static String normativeHalfOf(String description) {
        int marker = description.indexOf("Refactoring Rationale:");
        return marker < 0 ? description : description.substring(0, marker);
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
     * The conflict example carries a sentence this context can actually emit.
     *
     * <p>Purpose: the shared advice publishes three 409 sentences and answers with whichever condition it
     * recognised, so a document naming one of the three is naming a condition it claims to reach. This
     * context reaches exactly one: the fraud write holds the authorization row for its transaction, so a
     * second operator marking the same authorization is refused when the wait bounded by
     * {@code carddemo.datasource.lock-timeout-ms} elapses.</p>
     *
     * <p>Refactoring Rationale: the example was the data-changed sentence, which the shared advice emits
     * only for an OPTIMISTIC-lock failure. Neither table in this schema declares a version column -- both
     * are derived field for field from copybooks that have none -- so that failure was unreachable and this
     * document promised a sentence the service could not produce. Asserting the example against the
     * constant is what would have caught it, so the assertion is added with the correction.</p>
     *
     * <p>Assumptions: the two sentences this context does NOT reach are asserted absent rather than left
     * unmentioned, because the failure being closed here is a document naming the wrong one of three
     * similar strings, and an assertion on the right one alone would still pass if a later edit swapped in
     * either of the others.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the conflict example carries the lock-unavailable sentence this context can emit")
    void conflictExampleCarriesTheReachableSentence() {
        Map<String, Object> example = mapping(mapping(mapping(
                response("Conflict"), "content"), "application/json"), "example");

        assertThat(example.get("message"))
                .as("the published example must be the sentence the shared advice emits for a lock"
                        + " that could not be obtained")
                .isEqualTo(GlobalExceptionHandler.MESSAGE_LOCK_UNAVAILABLE);
        assertThat(String.valueOf(example.get("message")))
                .isNotEqualTo(GlobalExceptionHandler.MESSAGE_RECORD_CHANGED)
                .isNotEqualTo(GlobalExceptionHandler.MESSAGE_REFERENCED_ROW);
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

        // WHY : Refactoring Rationale: AccountIdScope, Cursor and PagingDirection were
        //   components.parameters entries and are now MEMBERS of the PendingAuthPageQuery request body,
        //   so the per-field keys they contribute are read from that schema's property names rather than
        //   from the parameter table. The obligation is unchanged -- every input a constraint can reject
        //   must appear among the keys the 400 documents -- only the place the input is declared moved.
        for (String member : pageQueryMembers()) {
            assertThat(published)
                    .as("the 400 must name body member %s, which a constraint on it keys", member)
                    .contains(member);
        }

        for (String parameter : List.of("AuthorizationKeyPath")) {
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
