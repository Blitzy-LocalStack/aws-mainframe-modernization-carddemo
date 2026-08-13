package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.authorization.dto.PendingAuthDetailResponse;
import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.authorization.dto.PendingAuthSummaryView;
import com.carddemo.authorization.service.PendingAuthDetailService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.web.CorrelationIdFilter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * per route, which is the stronger form. Reading the document keeps the comparison anchored to the
 * PUBLISHED artifact, which is what a generated client is built from, and a request-per-route assertion
 * cannot observe a facet -- a bound, a pattern, a required list -- that the server never exercises.
 *
 * <p>⚠️ Refactoring Rationale: this paragraph said the stronger form was "not available at this checkpoint
 * — no controller is authored". Two controllers are authored and
 * {@code com.carddemo.authorization.api.PendingAuthControllerTest} asserts their sent bodies, so the
 * sentence would send a reader looking for an absent test rather than to the sibling that holds the other
 * half. The two halves are deliberately paired: this class asserts what the document PROMISES and that
 * class asserts what the service SENDS, and a member is only settled when both agree.
 */
class AuthorizationApiContractTest {

    /** Classpath location of the contract this module publishes. */
    private static final String CONTRACT_RESOURCE = "/openapi/authorization-api.yaml";

    /** The parsed contract, loaded once because it is immutable for the run. */
    private static final Map<String, Object> CONTRACT = loadContract();

    /**
     * The keys an OpenAPI path item may declare an operation under.
     *
     * <p>Assumptions: a path item also carries non-operation keys -- {@code parameters}, {@code summary},
     * {@code $ref} -- so a walk that treated every child as an operation would descend into a sequence and
     * fail obscurely. This set is the filter, and it names all eight methods the specification allows rather
     * than only the five this contract currently uses, so a path served by {@code HEAD} or {@code OPTIONS}
     * later is covered without editing it.</p>
     */
    private static final Set<String> OPERATION_KEYS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

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
     * The withdrawn boundary claim, held as a constant for the same reason as the paging rule above.
     *
     * <p>⚠️ Assumptions: the phrase is the exact one the description carried, so the withdrawal notice and
     * the absence assertion cannot drift apart. It is quoted rather than paraphrased because a paraphrase
     * would let the original sentence return under its own wording while this constant still matched
     * nothing.</p>
     */
    private static final String WITHDRAWN_BOUNDARY_RULE = "still carries the rows that were on display";

    /**
     * The four summary members that are composed from the customer master rather than stored on the segment.
     *
     * <p>Assumptions: they are named here rather than derived, because what makes them different from the
     * sixteen beside them is their SOURCE and no property of the schema records that. Deriving them from the
     * required list would make the assertion circular.</p>
     */
    private static final List<String> DISPLAY_MEMBERS =
            List.of("customerName", "addressLine1", "addressLine2", "phoneNumber1");

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
     * Confirms the summary schema publishes exactly the members the summary view emits.
     *
     * <p>Purpose: the schema is CLOSED -- it declares {@code additionalProperties: false} -- so a member the
     * view emits and the schema does not declare is not a documentation gap. It is a body the published
     * document rejects, produced by the server that published it, which means a client validating responses
     * refuses an answer the server considers correct and a client that does not validate silently accepts a
     * shape it was never told about. Comparing the two SETS is the only durable guard, because the record
     * head and the YAML are edited in different files.</p>
     *
     * <p>Refactoring Rationale: this case exists because that was this context's state. The view emitted
     * twenty members and the schema declared sixteen with the closed flag set, so the four customer display
     * members -- {@code customerName}, {@code addressLine1}, {@code addressLine2} and
     * {@code phoneNumber1} -- were forbidden by the document that described them. They were published rather
     * than removed because the reference screen renders all four:
     * {@code app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy} declares {@code CNAMEI} at L66,
     * {@code ADDR001I} at L78, {@code ADDR002I} at L90 and {@code PHONE1I} at L96, and
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} composes them at L757 through L779.</p>
     *
     * <p>Assumptions: the comparison is EXACT in both directions rather than a containment check. A schema
     * declaring a property the view cannot emit is the same class of disagreement seen from the other side:
     * a client would provision for a member that never arrives, and the closed flag would not catch it.</p>
     *
     * <p>⚠️ Refactoring Rationale: the four display members are asserted REQUIRED, and this case asserted
     * the opposite. It said they must be optional "because they are read from a neighbouring context", which
     * confuses a null value with an absent member. The services pin
     * {@code default-property-inclusion: always} in
     * {@code services/common-lib/src/main/resources/carddemo-common-defaults.yml}, so Jackson writes every
     * record component of this view on every response: an unresolved customer yields four members present and
     * NULL, never four members missing. Publishing them as optional described an omission the writer cannot
     * produce, and a generated type then declared them optional AND nullable, obliging a caller to
     * distinguish two states that are one state. The sibling case on the forward-move body already states the
     * correct rule -- require every member the writer always sends, and admit null where it sends null -- so
     * this file previously contradicted itself across two cases.</p>
     *
     * <p>Assumptions: the required set is asserted to equal the emitted set rather than merely to contain the
     * four display members, because the reason applies to every member of a record-backed body and a rule
     * naming four would leave the next one to be argued again.</p>
     *
     * <p>Assumptions: required and nullable are asserted TOGETHER for those four, because either alone is
     * the wrong claim -- required without the type union would say a summary always carries a customer
     * name, and the union without required would leave the key optional again. That the writer really does
     * send the key with a null value is exercised directly by
     * {@code PendingAuthSummaryServiceTest.anUnresolvedCustomerLeavesTheDisplayFieldsBlankAndStillPublishesTheTotals},
     * so this document's claim and the service's behaviour are pinned to one another rather than each
     * asserted alone.</p>
     */
    @Test
    @DisplayName("the summary schema publishes exactly the members the summary view emits")
    void summarySchemaPublishesExactlyTheViewMembers() {
        Set<String> declared = mapping(schema("PendingAuthSummary"), "properties").keySet();
        Set<String> emitted = Arrays.stream(PendingAuthSummaryView.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(declared)
                .as("a closed schema must declare every member the view emits, or the server's own document"
                        + " rejects the server's own body")
                .containsExactlyInAnyOrderElementsOf(emitted);

        assertThat(schema("PendingAuthSummary").get("additionalProperties"))
                .as("the schema must stay closed, because that is what makes the comparison above binding")
                .isEqualTo(Boolean.FALSE);

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema("PendingAuthSummary").get("required");
        assertThat(required)
                .as("every component the view emits is serialised, so every member is always present")
                .containsExactlyInAnyOrderElementsOf(emitted);
        for (String display : DISPLAY_MEMBERS) {
            assertThat(emitted)
                    .as("the view must still emit display member %s, which the reference screen renders",
                            display)
                    .contains(display);
            assertThat(nullableMemberTypes("PendingAuthSummary", display))
                    .as("display member %s is read from a neighbouring context, so its value must be able"
                            + " to be null even though the key is always sent", display)
                    .contains("null");
        }
        assertThat(required)
                .as("the writer emits every record component on every response, so a member left out of"
                        + " required describes an omission it cannot produce")
                .containsExactlyInAnyOrderElementsOf(emitted);
    }

    /**
     * Reads the type union one schema member declares, so a nullability claim can be asserted on it.
     *
     * <p>Assumptions: the union is read from the {@code type} keyword of the member itself, which is the
     * 3.1 spelling this document uses throughout and the one {@link #noThreeZeroNullabilityKeywordSurvives}
     * holds it to. A member declaring a scalar type yields a single-element list, so a caller asserting on
     * {@code "null"} fails against it rather than passing vacuously.</p>
     *
     * @param schemaName the schema to read
     * @param member the member whose declared type is wanted
     * @return the declared type tokens, never {@code null} and never empty
     * @throws IllegalStateException if the member declares no {@code type} keyword at all
     */
    private static List<String> nullableMemberTypes(String schemaName, String member) {
        Object declared = mapping(mapping(schema(schemaName), "properties"), member).get("type");
        if (declared instanceof List<?> union) {
            return union.stream().map(String::valueOf).toList();
        }
        if (declared instanceof String scalar) {
            return List.of(scalar);
        }
        throw new IllegalStateException(
                schemaName + "." + member + " declares no type keyword, so its nullability cannot be read");
    }

    /**
     * Confirms the screen schema publishes exactly the members the screen record emits, and closes.
     *
     * <p>Purpose: the operation this schema serves published an unconstrained {@code type: object} with
     * {@code additionalProperties: true} and no property at all, so the document said nothing whatever about
     * the twenty-seven-member body the service returns. A client could not generate a type from it, could not
     * validate a response against it, and had no way to know which members are always present -- and a member
     * added or renamed on the server would have satisfied that schema exactly as well as the correct body
     * did.</p>
     *
     * <p>Assumptions: the comparison is EXACT in both directions and the schema is asserted CLOSED, because
     * either alone is weak. An exact comparison against an open schema still lets an undeclared member reach a
     * client at run time; a closed schema whose member list has drifted rejects the server's own body.</p>
     *
     * <p>Assumptions: the required set is asserted as a whole rather than member by member, and it is asserted
     * to be a SUBSET of the emitted members as well. A required name the record does not emit is a member a
     * client would wait for and never receive, and no closed-schema check catches it.</p>
     *
     * <p>⚠️ Refactoring Rationale: the required set must now EQUAL the emitted set, and this case asserted
     * that {@code message} was excluded from it "because the message line is null on this route". That is a
     * null value, not an absent member: the pinned {@code default-property-inclusion: always} makes the writer
     * emit all twenty-seven components on every response, so thirteen of them were published as optional while
     * arriving on every body. Fourteen members moved into the required list for that reason -- the nullability
     * each already declared is untouched, and it is the nullability that carries the fact a value may be
     * absent from the authorizer's message.</p>
     *
     * <p>Assumptions: the claim is EQUALITY and not containment, which is the stronger of the two and the
     * one the wire supports: a member added to the record and left out of the required list now fails here
     * rather than being published as optional and quietly waited for by a generated client.</p>
     */
    @Test
    @DisplayName("the screen schema publishes exactly the members the screen record emits and is closed")
    void screenSchemaPublishesExactlyTheScreenRecordMembers() {
        Set<String> declared = mapping(schema("PendingAuthScreen"), "properties").keySet();
        Set<String> emitted = Arrays.stream(PendingAuthDetailResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(declared)
                .as("the screen projection must be described member for member, not as an open object")
                .containsExactlyInAnyOrderElementsOf(emitted);
        assertThat(schema("PendingAuthScreen").get("additionalProperties"))
                .as("an open schema lets a member added on the server reach a client never told about it")
                .isEqualTo(Boolean.FALSE);

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema("PendingAuthScreen").get("required");
        assertThat(required)
                .as("a required name the record cannot emit is a member a client waits for and never gets")
                .isSubsetOf(emitted);
        assertThat(required)
                .as("the six chrome members are produced by this service, so all six are always present")
                .contains("transactionName", "title01", "currentDate", "programName", "title02",
                        "currentTime");
        assertThat(required)
                .as("these members project NOT NULL columns or always-resolving compositions")
                .contains("cardNumber", "authResponse", "authResponseReason", "approvedAmount",
                        "transactionId", "matchStatus", "fraudMark");
        assertThat(required)
                .as("the writer emits every record component on every response, so a member left out of"
                        + " required -- including the message line, which arrives null rather than absent --"
                        + " describes an omission it cannot produce")
                .containsExactlyInAnyOrderElementsOf(emitted);
    }

    /**
     * Reaches the inline schema of the forward-move success body.
     *
     * <p>Assumptions: the schema is inline rather than a named component, so it is reached through the path
     * item instead of through {@code components/schemas}. It is reached by NAVIGATION rather than restated
     * here, because a copy of the shape in this file would agree with itself while the document drifted.</p>
     *
     * @return the {@code 200} response schema of the forward-move operation, never {@code null}
     * @throws AssertionError if any step of the path is absent or is not a mapping
     */
    private static Map<String, Object> nextResponseSchema() {
        Map<String, Object> operation = mapping(mapping(mapping(CONTRACT, "paths"),
                "/api/v1/authorizations/{key}/next"), "get");
        return mapping(mapping(mapping(mapping(mapping(operation, "responses"), "200"),
                "content"), "application/json"), "schema");
    }

    /**
     * The forward-move body requires every member it always sends, and admits null where it sends null.
     *
     * <p>⚠️ Refactoring Rationale: this case exists because the published shape and the sent shape
     * disagreed in the one direction no closed-schema check can catch. The response is the
     * {@code NextAuthorization} record serialised under a shared inclusion policy of {@code always}, so all
     * three of its members are present on both moves and the exhausted move sends
     * {@code "authorization": null}. The document declared that member OPTIONAL and, through an
     * {@code allOf} over the detail schema, NON-nullable -- so a conforming client validating responses
     * rejected the documented end-of-data body, and a client generated from the document treated an absent
     * key as an answer it will never receive. Both halves are now asserted: the required set is the whole
     * record, and the member carries an explicit null branch.</p>
     *
     * <p>Assumptions: the required set is compared for EQUALITY with the record's components rather than
     * for containment. Containment in one direction admits a required name the record cannot emit, and in
     * the other admits an always-sent member published as optional, which is the defect this case was
     * written for.</p>
     *
     * <p>Assumptions: the null branch is asserted as a {@code oneOf} member with {@code type: 'null'},
     * which is 3.1's only expression for it -- the sibling case below refuses the 3.0 keyword document
     * wide, so an implementation could not satisfy this by reintroducing {@code nullable}.</p>
     *
     * <p>Trade-offs: this body's members are required while the members of {@code PendingAuthScreen} and
     * {@code PendingAuthSummary} that are null on their route are optional, and the difference is
     * deliberate rather than drift. Those members each publish an explicit null branch, so the always-sent
     * key validates against them and "optional" is merely permissive; this member published NO null
     * branch, so its body was rejected outright, and the required list is tightened alongside the type so
     * the shape is described once and exactly. The nullability is the correctness half; requiredness is
     * the precision half.</p>
     */
    @Test
    @DisplayName("the forward-move body requires every member it sends and admits null for the absent one")
    void forwardMoveBodyRequiresEveryMemberItSends() {
        Map<String, Object> schema = nextResponseSchema();
        Set<String> declared = mapping(schema, "properties").keySet();
        Set<String> emitted = Arrays.stream(
                        PendingAuthDetailService.NextAuthorization.class.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(declared)
                .as("the body must be described member for member, not as an open object")
                .containsExactlyInAnyOrderElementsOf(emitted);
        assertThat(schema.get("additionalProperties"))
                .as("an open shape lets a member added on the server reach a client never told about it")
                .isEqualTo(Boolean.FALSE);

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required)
                .as("every member of the record is sent on every move, so every one of them is required")
                .containsExactlyInAnyOrderElementsOf(emitted);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> branches = (List<Map<String, Object>>)
                mapping(mapping(schema, "properties"), "authorization").get("oneOf");
        assertThat(branches)
                .as("the exhausted move sends this member as null, so a null branch must be published")
                .anySatisfy(branch -> assertThat(branch.get("type")).isEqualTo("null"));
        assertThat(branches)
                .as("the ordinary move sends the detail shape, which must still be reachable")
                .anySatisfy(branch -> assertThat(branch.get("$ref"))
                        .isEqualTo("#/components/schemas/PendingAuthDetail"));
        assertThat(mapping(mapping(schema, "properties"), "message").get("type"))
                .as("the message member is null on every move that found an authorization")
                .isEqualTo(List.of("string", "null"));
    }

    /**
     * Confirms the document uses no OpenAPI-3.0 nullability keyword anywhere.
     *
     * <p>Purpose: this document declares {@code openapi: 3.1.x}, and 3.1 REMOVED the {@code nullable}
     * keyword in favour of a type union. A 3.1 reader treats {@code nullable} as an unrecognised extension
     * keyword and ignores it, so a member declared nullable that way is published as NON-nullable while the
     * server answers null for it -- and a conforming client validating responses rejects an ordinary success
     * body. Nothing fails at build time, which is why this is asserted rather than reviewed.</p>
     *
     * <p>Refactoring Rationale: this case exists because the following-authorization response declared
     * {@code nullable: true} on its message member. It is now a {@code [string, 'null']} union. The whole
     * document is walked rather than that one member re-asserted, because the defect is a habit rather than a
     * typo and the next occurrence would be somewhere else.</p>
     *
     * <p>Assumptions: the walk descends through mappings AND sequences, because a schema can appear inside a
     * {@code oneOf}, an {@code allOf} or a {@code parameters} list, and a walk over mappings alone would miss
     * every one of those positions.</p>
     */
    @Test
    @DisplayName("no OpenAPI-3.0 nullable keyword survives anywhere in the 3.1 document")
    void noThreeZeroNullabilityKeywordSurvives() {
        assertThat(String.valueOf(CONTRACT.get("openapi")))
                .as("the assertion below is only meaningful because this document declares 3.1")
                .startsWith("3.1");
        assertThat(pathsDeclaring(CONTRACT, "nullable", ""))
                .as("3.1 removed the nullable keyword, so a member using it is published non-nullable")
                .isEmpty();
    }

    /**
     * The published boundary member describes the envelope the service actually sends at a boundary.
     *
     * <p>⚠️ Refactoring Rationale: the description said "the page above still carries the rows that were on
     * display", and the service sends the opposite: a move already at a boundary answers an EMPTY page with
     * both cursors null and no forward availability, on both arms. A client generated from that sentence
     * renders whatever the page member holds, so it blanked its table at exactly the moment the reference
     * screen leaves the rows where they are. The behaviour is correct and the sentence was not: section
     * 0.7.1 removed the structure that let the reference redisplay a screen, so "leave the rows in place" is
     * an instruction to the client and cannot be a claim about the response.
     *
     * <p>⚠️ Assumptions: the withdrawn sentence is asserted absent from the NORMATIVE text and present in
     * the description as a whole, which is the same split {@link #publishedPagingRuleStatesTheAsymmetry}
     * makes and for the same reason -- a withdrawal has to be able to quote what it withdraws, or a client
     * author who acted on the old wording cannot tell which of the two statements to trust.
     *
     * <p>⚠️ Assumptions: the recovery path is asserted too, not just the correction. The boundary envelope
     * carries no cursor, so a client that overwrote its state from it has discarded its own position; a
     * corrected description that did not say how to get back would leave that client with no route other
     * than guessing.
     */
    @Test
    @DisplayName("the published boundary member describes the empty envelope the service sends")
    void publishedBoundaryMemberDescribesTheEmptyEnvelope() {
        String published = String.valueOf(mapping(mapping(schema("PendingAuthListResponse"), "properties"),
                "screenMessage").get("description"));

        assertThat(normativeHalfOf(published))
                .as("the normative text must state that no rows accompany a boundary sentence")
                .contains("page.items is empty")
                .contains("page.firstKey and page.lastKey are null")
                .doesNotContain(WITHDRAWN_BOUNDARY_RULE);
        assertThat(normativeHalfOf(published))
                .as("the recovery path must be published, since the boundary envelope carries no cursor")
                .contains("re-issuing the request with no cursor and no direction");
        assertThat(published)
                .as("the withdrawal must SAY what it withdraws, or a reader cannot tell which to trust")
                .contains(WITHDRAWN_BOUNDARY_RULE);
    }

    /**
     * Every published operation declares the dispatch refusals the framework can answer it with.
     *
     * <p>Purpose: a method the dispatcher does not map on a path it does map, and a body media type an
     * operation does not consume, are refused by the framework before any handler in this module runs. Both
     * are therefore responses the service produces on every operation whether or not the document says so,
     * and a client generated from a document that omits them has no typed branch for a status it will
     * receive the first time a caller sends the wrong verb or the wrong {@code Content-Type}.</p>
     *
     * <p>Refactoring Rationale: this loop reads the operation set from the document rather than listing the
     * five operations, because the obligation belongs to every operation the contract declares -- including
     * one added after this test was written. Listing them would leave a sixth operation unchecked until
     * someone remembered to extend the list, which is the failure mode this whole class exists to close.</p>
     *
     * <p>Assumptions: the 415 obligation is conditioned on the operation declaring a {@code requestBody},
     * and the absence half is asserted as well. A body-less operation cannot produce a media-type refusal --
     * there is no body to negotiate a type for -- so publishing 415 on one would document a response the
     * service cannot send, which is the same class of defect in the opposite direction.</p>
     *
     * <p>⚠️ Refactoring Rationale: the 406 obligation was missing from this loop, and its absence is the
     * reason the document was able to omit that response from all five operations while this class passed.
     * The shared advice renders 406 for a request whose {@code Accept} header admits nothing this service
     * produces, which a browser reaches with its default header, so it was a status every operation could
     * answer with and none declared.</p>
     *
     * <p>⚠️ Assumptions: the 406 obligation is UNCONDITIONAL, unlike the 415 obligation beside it, and the
     * asymmetry is the point rather than an oversight. The two refusals run in opposite directions: a 415
     * refuses the media type of a body the caller SENT, so an operation that consumes none cannot produce
     * it, while a 406 refuses the media type of the representation the caller asked to RECEIVE, and every
     * operation here produces a representation.</p>
     */
    @Test
    @DisplayName("every operation publishes the dispatch refusals the framework can answer with")
    void everyOperationPublishesTheDispatchRefusals() {
        Map<String, Object> paths = mapping(CONTRACT, "paths");

        assertThat(paths).as("the document must declare at least one path to make this loop meaningful").isNotEmpty();
        for (String path : paths.keySet()) {
            Map<String, Object> item = mapping(paths, path);
            for (String method : item.keySet()) {
                if (!OPERATION_KEYS.contains(method)) {
                    continue;
                }
                Map<String, Object> operation = mapping(item, method);
                Map<String, Object> responses = mapping(operation, "responses");

                assertThat(responses)
                        .as("%s %s must publish 405 -- the dispatcher answers it for any other verb on this path",
                                method, path)
                        .containsKey("405");
                assertThat(responses)
                        .as("%s %s produces a representation, so a request accepting none is refused 406",
                                method, path)
                        .containsKey("406");
                if (operation.containsKey("requestBody")) {
                    assertThat(responses)
                            .as("%s %s consumes a body, so the media-type refusal is reachable and must be published",
                                    method, path)
                            .containsKey("415");
                } else {
                    assertThat(responses)
                            .as("%s %s consumes no body, so a published 415 documents a response it cannot send",
                                    method, path)
                            .doesNotContainKey("415");
                }
            }
        }
    }

    /**
     * The three dispatch-refusal responses declare each recovery header on the terms the renderer keeps.
     *
     * <p>Purpose: two of the three are not actionable from their body alone. A 405 tells a caller its verb
     * was wrong and the {@code Allow} header tells it which verb to use; a 415 tells it its media type was
     * wrong and the {@code Accept} header tells it which type to send. The third needs no header, because
     * the one representation this service produces is named in the sentence itself.</p>
     *
     * <p>⚠️ Refactoring Rationale: this case asserted both headers {@code required: true} and justified it
     * on the ground that the renderer omits them "only when the framework reports no alternatives at all --
     * a state this service's mappings cannot produce". Two things were wrong with that. It is a claim about
     * the internals of a framework this contract cannot see, load-bearing in a published promise; and it is
     * contradicted by the shared kernel's own suite, which asserts both omission branches directly -- the
     * absent {@code Allow} and the absent {@code Accept} are specified, tested behaviour of the very
     * renderer that produces these responses. A contract may not promise a header its renderer is tested to
     * withhold, so the requiredness is inverted and the CONDITION is asserted published in its place. This
     * also removes a contradiction between two of this repository's contracts, the sibling card document
     * having declared {@code Allow} optional with the same reasoning all along.</p>
     *
     * <p>⚠️ Assumptions: {@code required: false} is asserted as an explicit {@code Boolean.FALSE} rather
     * than by asserting the key merely absent. Absent and false mean the same thing to a generator, but only
     * the explicit value carries the description that says WHEN the header arrives, and it is that
     * description a client author acts on.</p>
     *
     * <p>⚠️ Assumptions: the 406 is asserted to declare NO {@code Accept} header. On a response that header
     * names the media types the server accepts in a request body, so using it to advertise producible
     * representations would say something the specification gives it no meaning for -- and a client reading
     * it as a list of producible types would draw exactly the wrong conclusion.</p>
     *
     * <p>Refactoring Rationale: the code constants are compared rather than restated, so a code renumbered
     * in the shared kernel fails here instead of leaving the document describing a body no client will
     * match. This is the same guard the 401 and 403 descriptions already carry, extended to the three
     * statuses that were previously answered as 500 with a code naming an abend.</p>
     */
    @Test
    @DisplayName("the dispatch refusals declare each recovery header on the terms the renderer keeps")
    void dispatchRefusalsDeclareTheirRecoveryHeaders() {
        Map<String, Object> methodHeaders = mapping(response("MethodNotAllowed"), "headers");
        Map<String, Object> mediaTypeHeaders = mapping(response("UnsupportedMediaType"), "headers");
        Map<String, Object> negotiationHeaders = mapping(response("NotAcceptable"), "headers");

        assertThat(methodHeaders).containsKeys(CorrelationIdFilter.CORRELATION_ID_HEADER, HttpHeaders.ALLOW);
        assertThat(mapping(methodHeaders, HttpHeaders.ALLOW).get("required"))
                .as("the renderer is tested to omit Allow when no set was reported, so it cannot be promised")
                .isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(mapping(methodHeaders, HttpHeaders.ALLOW).get("description")))
                .as("an optional header is only usable if the document says when it arrives")
                .contains("Present whenever")
                .contains("omitted rather than sent empty");

        assertThat(mediaTypeHeaders).containsKeys(CorrelationIdFilter.CORRELATION_ID_HEADER, HttpHeaders.ACCEPT);
        assertThat(mapping(mediaTypeHeaders, HttpHeaders.ACCEPT).get("required"))
                .as("the renderer is tested to omit Accept when no type was reported, so it cannot be promised")
                .isEqualTo(Boolean.FALSE);
        assertThat(String.valueOf(mapping(mediaTypeHeaders, HttpHeaders.ACCEPT).get("description")))
                .as("an optional header is only usable if the document says when it arrives")
                .contains("Present whenever")
                .contains("omitted rather than sent empty");

        assertThat(negotiationHeaders)
                .as("the 406 carries the correlation identity and must not borrow Accept to list what it produces")
                .containsKey(CorrelationIdFilter.CORRELATION_ID_HEADER)
                .doesNotContainKey(HttpHeaders.ACCEPT);

        assertThat(String.valueOf(response("MethodNotAllowed").get("description")))
                .as("the 405 description must name the code and sentence the shared advice renders")
                .contains(ApiError.CODE_METHOD_NOT_ALLOWED)
                .contains(GlobalExceptionHandler.MESSAGE_METHOD_NOT_ALLOWED);
        assertThat(String.valueOf(response("UnsupportedMediaType").get("description")))
                .as("the 415 description must name the code and sentence the shared advice renders")
                .contains(ApiError.CODE_UNSUPPORTED_MEDIA_TYPE)
                .contains(GlobalExceptionHandler.MESSAGE_UNSUPPORTED_MEDIA_TYPE);
        assertThat(String.valueOf(response("NotAcceptable").get("description")))
                .as("the 406 description must name the code and sentence the shared advice renders")
                .contains(GlobalExceptionHandler.CODE_NOT_ACCEPTABLE)
                .contains(GlobalExceptionHandler.MESSAGE_NOT_ACCEPTABLE);

        assertThat(mapping(mapping(response("MethodNotAllowed"), "content"), "application/json").toString())
                .as("all three refusals carry the shared problem body rather than a bespoke shape")
                .contains("ApiError");
        assertThat(mapping(mapping(response("UnsupportedMediaType"), "content"), "application/json").toString())
                .contains("ApiError");
        assertThat(mapping(mapping(response("NotAcceptable"), "content"), "application/json").toString())
                .as("the 406 body is itself JSON, which is the type the caller just refused, deliberately")
                .contains("ApiError");
    }

    /**
     * The published 406 example is the body the shared renderer actually emits for that refusal.
     *
     * <p>Purpose: the example is what a client author reads before writing a branch, and an example that
     * disagrees with the emitter teaches the wrong shape more effectively than no example at all. Review
     * reported this response missing from the document entirely, so every one of its members is new and none
     * of it has ever been reconciled against the emitter.</p>
     *
     * <p>Assumptions: the status member is asserted as the NUMBER 406 rather than as a string, because the
     * shared problem type carries it as an integer and a quoted example would generate a client that
     * compares a string to a number and never matches.</p>
     *
     * <p>Assumptions: the per-field array is asserted EMPTY. The refusal is raised before any body is bound
     * -- indeed the request may carry no body at all -- so a client must not be led to expect a field entry
     * to explain it, which is the same property the 405 and 415 examples beside it publish.</p>
     */
    @Test
    @DisplayName("the published 406 example matches the body the shared renderer emits")
    void published406ExampleMatchesTheRenderedBody() {
        Map<String, Object> example = mapping(
                mapping(mapping(response("NotAcceptable"), "content"), "application/json"), "example");

        assertThat(example.get("code")).isEqualTo(GlobalExceptionHandler.CODE_NOT_ACCEPTABLE);
        assertThat(example.get("message")).isEqualTo(GlobalExceptionHandler.MESSAGE_NOT_ACCEPTABLE);
        assertThat(example.get("status"))
                .as("the shared problem type carries the status as a number, so a quoted example misleads")
                .isEqualTo(HttpStatus.NOT_ACCEPTABLE.value());
        assertThat(example.get("fieldErrors"))
                .as("nothing was bound, so no field entry can explain this refusal")
                .isEqualTo(List.of());
        assertThat(example.get("abend"))
                .as("a negotiation refusal is a client-correctable outcome and carries no abend block")
                .isNull();
    }


    /**
     * Collects the location of every occurrence of a key anywhere beneath a node.
     *
     * @param node the mapping, sequence or scalar to walk; may be {@code null}
     * @param key the key to report occurrences of; must not be {@code null}
     * @param path the slash-separated location of {@code node}, used to make a report legible
     * @return the locations at which the key occurs, empty when it occurs nowhere, never {@code null}
     */
    private static List<String> pathsDeclaring(Object node, String key, String path) {
        List<String> found = new java.util.ArrayList<>();
        if (node instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                String child = path + "/" + entry.getKey();
                if (key.equals(entry.getKey())) {
                    found.add(child);
                }
                found.addAll(pathsDeclaring(entry.getValue(), key, child));
            }
        } else if (node instanceof List<?> sequence) {
            for (int index = 0; index < sequence.size(); index++) {
                found.addAll(pathsDeclaring(sequence.get(index), key, path + "[" + index + "]"));
            }
        }
        return found;
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

    /**
     * Asserts that every published operation declares the transport refusals this service can produce,
     * and that each of the three has a shape a client can bind to.
     *
     * <p>⚠️ Refactoring Rationale: all three were reachable and UNDECLARED. The shared advice in
     * {@code services/common-lib} answers a wrong method with 405, a body in the wrong media type with
     * 415 and an unsatisfiable {@code Accept} header with 406, on every route of every service, and this
     * contract declared none of them. A client generated from it had no branch for any of the three and a
     * hand-written one, {@code ui/src/api/client.ts}, could only report each as an unrecognised body. The
     * census is written as a loop rather than as a list of paths so that an operation added later is
     * covered without this case being edited.</p>
     *
     * <p>Assumptions: the operation count is asserted so the loop cannot pass vacuously. A scanner that
     * matched nothing -- because a path item gained a member, or because the document was restructured --
     * would otherwise assert nothing at all while reading as though it had checked every route.</p>
     */
    @Test
    @DisplayName("every operation declares the transport refusals the shared advice can produce")
    void theTransportRefusalsAreDeclaredWhereTheyCanOccur() {
        Map<String, Object> document = CONTRACT;
        Map<String, Object> paths = mapping(document, "paths");
        int operations = 0;

        for (String path : paths.keySet()) {
            Map<String, Object> methods = mapping(paths, path);
            for (String method : methods.keySet()) {

                // WHY : Assumptions: a path item carries members that are not operations -- a shared
                //       description, a shared parameter list -- so an operation is identified by carrying
                //       an operationId rather than by the key not being one of those. Naming the
                //       exclusions instead would need amending every time a path item gained a member.
                if (!(methods.get(method) instanceof Map)) {
                    continue;
                }
                Map<String, Object> operation = mapping(methods, method);
                if (!operation.containsKey("operationId")) {
                    continue;
                }
                operations++;
                Map<String, Object> responses = mapping(operation, "responses");
                assertThat(responses)
                        .as("%s %s must declare both refusals every route can produce", method, path)
                        .containsKeys("405", "406");
                if (operation.containsKey("requestBody")) {
                    assertThat(responses)
                            .as("%s %s accepts a body, so it can refuse the body's media type",
                                    method, path)
                            .containsKey("415");
                }
            }
        }

        assertThat(operations)
                .as("the census is not vacuous; every published operation was examined")
                .isEqualTo(5);

        Map<String, Object> declared = mapping(mapping(document, "components"), "responses");
        assertThat(declared)
                .as("each refusal is declared once and referenced, so the three cannot drift apart")
                .containsKeys("MethodNotAllowed", "NotAcceptable", "UnsupportedMediaType");
        assertThat(mapping(mapping(declared, "MethodNotAllowed"), "headers"))
                .as("a 405 names the methods the route does publish, which is what a client acts on")
                .containsKey("Allow");
    }
}
