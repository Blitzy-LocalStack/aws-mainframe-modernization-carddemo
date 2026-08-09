package com.carddemo.reference.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CorrelationIdFilter;
import com.carddemo.common.web.CursorToken;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the published reference contract to the shared kernel that has to serve it.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Refactoring Rationale: the review that prompted this class found that the reference contract
 * described bodies the shared advice cannot compose and inputs the request validator refuses before the
 * documented outcome can be reached. Its conflict schema added a member to a shape sealed against
 * unknown members, so every example of it was invalid against the schema declaring it; its
 * field-validation enumeration published a state the shared record refuses at construction; its abend
 * block published the internal reason rather than the reduced external form; its date mask was a closed
 * enumeration, which made the documented bad-pattern verdict unreachable; and three of its input schemas
 * were shared between a stored value and a filter that admits different text. Every one of those crossed
 * the wire as a string or as YAML, so neither the Java build nor a schema parse could see it. A
 * disagreement no build can see needs a test that can, and this is it.</p>
 *
 * <p>Assumptions: the contract is read from the CLASSPATH rather than from a source path, so this test
 * asserts against the artifact the service actually publishes. Reading
 * {@code src/main/resources/openapi/reference-api.yaml} through the file system would pass while the
 * packaged resource was stale or absent.</p>
 *
 * <p>Alternatives Considered: a running application context issuing a request per route, which is the
 * stronger form. It is not available at this checkpoint -- this module publishes no controller yet, so
 * there is no route to call -- and waiting for one would leave these rules unverified over exactly the
 * interval in which they were introduced. This test therefore compares the published document against
 * the kernel constants and patterns the eventual handler will use, which are the same values.</p>
 */
class ReferenceApiContractTest {

    /** Classpath location of the contract this module publishes. */
    private static final String CONTRACT_RESOURCE = "/openapi/reference-api.yaml";

    /** The HTTP methods an operation may be declared under, so a path item's own keys are skipped. */
    private static final List<String> HTTP_METHODS =
            List.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /** The methods that write, every one of which can meet a contention the shared advice reports. */
    private static final List<String> WRITE_METHODS = List.of("post", "put", "patch", "delete");

    /** The one response every 409 in this document must reference. */
    private static final String CONFLICT_RESPONSE_REF = "#/components/responses/Conflict";

    /** The parsed contract, loaded once per test instance. */
    private final Map<String, Object> contract = loadContract();

    /**
     * Reads and parses the published contract from the classpath.
     *
     * @return the whole document as nested maps and lists; never {@code null}
     * @throws IllegalStateException if the resource is absent from the classpath, which would mean the
     *     service publishes no contract at all, or if it cannot be read or parsed as a mapping
     */
    private static Map<String, Object> loadContract() {
        try (InputStream resource =
                ReferenceApiContractTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
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
            throw new IllegalStateException(
                    "the published contract at " + CONTRACT_RESOURCE + " could not be read", failure);
        }
    }

    /**
     * Returns one nested mapping by key.
     *
     * @param parent the enclosing mapping; must not be {@code null}
     * @param key the key to read
     * @return the nested mapping
     * @throws IllegalStateException if the key is absent or does not hold a mapping, because a
     *     structural assumption of this test would otherwise surface as a class cast far from its cause
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
     * Returns the document's schema catalogue.
     *
     * @return the {@code components.schemas} mapping; never {@code null}
     */
    private Map<String, Object> schemas() {
        return mapping(mapping(this.contract, "components"), "schemas");
    }

    /**
     * Returns one named schema.
     *
     * @param name the schema name to read
     * @return that schema as a mapping; never {@code null}
     */
    private Map<String, Object> schema(String name) {
        return mapping(schemas(), name);
    }

    /**
     * Returns one named response component.
     *
     * @param name the response name to read
     * @return that response as a mapping; never {@code null}
     */
    private Map<String, Object> response(String name) {
        return mapping(mapping(mapping(this.contract, "components"), "responses"), name);
    }

    /**
     * Returns one named parameter component.
     *
     * @param name the parameter name to read
     * @return that parameter as a mapping; never {@code null}
     */
    private Map<String, Object> parameter(String name) {
        return mapping(mapping(mapping(this.contract, "components"), "parameters"), name);
    }

    /**
     * Collects every operation in the document, keyed by method and path.
     *
     * @return an insertion-ordered map from a readable "METHOD path" label to the operation mapping
     */
    private Map<String, Map<String, Object>> operations() {
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        Map<String, Object> paths = mapping(this.contract, "paths");
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            if (!(pathEntry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> item = mapping(paths, pathEntry.getKey());
            for (String method : HTTP_METHODS) {
                if (item.containsKey(method)) {
                    found.put(method.toUpperCase(java.util.Locale.ROOT) + " " + pathEntry.getKey(),
                            mapping(item, method));
                }
            }
        }
        return found;
    }

    /**
     * Collects every value stored under a given key anywhere beneath a node.
     *
     * @param node the node to search, which may be a mapping, a sequence or a scalar
     * @param key the mapping key whose values are wanted
     * @param sink the list every match is appended to
     */
    private static void collect(Object node, String key, List<Object> sink) {
        if (node instanceof Map<?, ?> asMap) {
            for (Map.Entry<?, ?> entry : asMap.entrySet()) {
                if (key.equals(entry.getKey())) {
                    sink.add(entry.getValue());
                }
                collect(entry.getValue(), key, sink);
            }
        } else if (node instanceof List<?> asList) {
            for (Object element : asList) {
                collect(element, key, sink);
            }
        }
    }

    /**
     * Collects every string anywhere beneath a node that has the shape of a sealed cursor token.
     *
     * @param node the node to search, which may be a mapping, a sequence or a scalar
     * @param sink the list every candidate is appended to
     */
    private static void collectCursorLike(Object node, List<String> sink) {
        if (node instanceof String text) {
            if (text.startsWith(CursorToken.VERSION + ".")) {
                sink.add(text);
            }
        } else if (node instanceof Map<?, ?> asMap) {
            for (Object value : asMap.values()) {
                collectCursorLike(value, sink);
            }
        } else if (node instanceof List<?> asList) {
            for (Object element : asList) {
                collectCursorLike(element, sink);
            }
        }
    }

    /**
     * Confirms the conflict response carries the shared shape and that no operation invents its own.
     *
     * <p>Refactoring Rationale: this is the assertion the removed {@code ReferenceConflictError} needed.
     * That schema composed {@link ApiError} with a second branch contributing a {@code conflictingVersion}
     * member, and because the {@code ApiError} branch seals itself against unknown members while each
     * branch of an {@code allOf} is evaluated against the whole instance, no document could satisfy it.
     * Two operations additionally declared their 409 inline against the bare error schema and a third
     * omitted 409 altogether, so three different bodies were promised for one condition.</p>
     */
    @Test
    @DisplayName("every conflict uses the one shared response carrying the shared error shape")
    void conflictIsOneSatisfiableSharedShape() {
        assertThat(schemas()).doesNotContainKey("ReferenceConflictError");

        Map<String, Object> conflictSchema = mapping(
                mapping(mapping(response("Conflict"), "content"), "application/json"), "schema");
        assertThat(conflictSchema).containsEntry("$ref", "#/components/schemas/ApiError");

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> operation : operations().entrySet()) {
            Map<String, Object> responses = mapping(operation.getValue(), "responses");
            boolean writes = WRITE_METHODS.stream()
                    .anyMatch(method -> operation.getKey().toLowerCase(java.util.Locale.ROOT)
                            .startsWith(method + " "));
            Object declared = responses.get("409");
            if (writes && declared == null) {
                offenders.add(operation.getKey() + " declares no 409");
                continue;
            }
            if (declared == null) {
                continue;
            }
            Map<String, Object> asMap = mapping(responses, "409");
            if (!CONFLICT_RESPONSE_REF.equals(asMap.get("$ref"))) {
                offenders.add(operation.getKey() + " declares its 409 inline");
            }
        }

        // WHY : Assumptions: the maintenance batch is the one write with no 409, and it is excluded by
        //       being asserted separately below rather than by an exception here -- a blanket exception
        //       would also excuse a genuinely missing 409 on a future write.
        assertThat(offenders).containsExactly("POST /api/v1/reference/maintenance-actions declares no 409");
    }

    /**
     * Confirms no example anywhere carries the member the removed conflict schema added.
     *
     * <p>Assumptions: searching the WHOLE document rather than the conflict examples alone is deliberate.
     * The member was invalid wherever it appeared, and an example elsewhere carrying it would be just as
     * unsatisfiable as the ones the review found.</p>
     */
    @Test
    @DisplayName("no example carries a member the sealed error shape refuses")
    void noExampleCarriesAnUnknownErrorMember() {
        List<Object> found = new ArrayList<>();
        collect(this.contract, "conflictingVersion", found);
        assertThat(found).isEmpty();
    }

    /**
     * Confirms each conflict example is a body the shared advice can actually compose.
     *
     * <p>Refactoring Rationale: the three examples used to carry a per-condition subordinate code and, on
     * one of them, a sentence in the version field entry. Neither is what
     * {@link GlobalExceptionHandler} builds: {@link ApiError#ofConflict} fixes the subordinate code at
     * the empty string, and the version entry carries the version digits. Comparing against the kernel
     * constants is what stops the two drifting again.</p>
     */
    @Test
    @DisplayName("the conflict examples carry the sentences and codes the shared advice emits")
    void conflictExamplesMatchTheSharedAdvice() {
        Map<String, Object> examples = mapping(
                mapping(mapping(response("Conflict"), "content"), "application/json"), "examples");
        assertThat(examples).containsOnlyKeys(
                "childRecordsExist", "recordChangedByAnotherCaller", "couldNotLockRowForUpdate");

        Map<String, String> expectedMessage = Map.of(
                "childRecordsExist", GlobalExceptionHandler.MESSAGE_REFERENCED_ROW,
                "recordChangedByAnotherCaller", GlobalExceptionHandler.MESSAGE_RECORD_CHANGED,
                "couldNotLockRowForUpdate", GlobalExceptionHandler.MESSAGE_LOCK_UNAVAILABLE);

        for (Map.Entry<String, String> expected : expectedMessage.entrySet()) {
            Map<String, Object> value = mapping(mapping(examples, expected.getKey()), "value");
            assertThat(value)
                    .as("conflict example %s", expected.getKey())
                    .containsEntry("code", ApiError.CODE_CONFLICT)
                    .containsEntry("secondaryCode", "")
                    .containsEntry("status", 409)
                    .containsEntry("message", expected.getValue())
                    .containsEntry("subsystem", ApiError.Subsystem.RELATIONAL.name());
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versionEntries = (List<Map<String, Object>>) mapping(
                mapping(examples, "recordChangedByAnotherCaller"), "value").get("fieldErrors");
        assertThat(versionEntries).hasSize(1);
        assertThat(versionEntries.get(0))
                .containsEntry("field", GlobalExceptionHandler.FIELD_VERSION)
                .containsEntry("state", FieldValidationFlag.NOT_OK.name());

        // WHY : Assumptions: the entry's message must be the version DIGITS and nothing else, because
        //       that is literally what the advice puts there -- String.valueOf(currentVersion). A
        //       sentence in that position reads plausibly and is wrong.
        assertThat(String.valueOf(versionEntries.get(0).get("message"))).matches("^[0-9]+$");

        for (String withoutVersion : List.of("childRecordsExist", "couldNotLockRowForUpdate")) {
            assertThat(mapping(mapping(examples, withoutVersion), "value").get("fieldErrors"))
                    .as("conflict example %s", withoutVersion)
                    .isEqualTo(List.of());
        }
    }

    /**
     * Confirms the published field-validation states are exactly the states a field entry can hold.
     *
     * <p>Refactoring Rationale: the enumeration used to publish a third, accepting state. The shared
     * record refuses it at construction, because an entry that reports a field as valid would make the
     * array length stop equalling the number of offending fields, so a client written against the
     * published set would have branched on a value no response can carry.</p>
     */
    @Test
    @DisplayName("the field-validation states are only the two a field entry can hold")
    void fieldValidationStatesAreOnlyTheRefusingOnes() {
        assertThat(schema("FieldValidationState").get("enum"))
                .isEqualTo(List.of(FieldValidationFlag.NOT_OK.name(), FieldValidationFlag.BLANK.name()));

        @SuppressWarnings("unchecked")
        List<Object> states = (List<Object>) schema("FieldValidationState").get("enum");
        assertThat(states).doesNotContain(FieldValidationFlag.VALID.name());
    }

    /**
     * Confirms the abend block published to a caller is the reduced external form.
     *
     * <p>Refactoring Rationale: the schema used to illustrate the culprit, the reason and the message
     * with the internal wording -- naming the dataset and the specific row that could not be read. That
     * is the body {@link AbendDetail} withholds: its external form blanks the culprit and the reason and
     * replaces the message with one fixed sentence, so the document was showing a caller information the
     * service does not send it.</p>
     */
    @Test
    @DisplayName("the abend block published externally is the reduced form")
    void abendBlockIsTheExternalForm() {
        Map<String, Object> properties = mapping(schema("AbendDetail"), "properties");
        assertThat(mapping(properties, "abendCulprit")).containsEntry("const", "");
        assertThat(mapping(properties, "abendReason")).containsEntry("const", "");

        List<Object> messages = new ArrayList<>();
        collect(mapping(properties, "abendMsg"), "examples", messages);
        assertThat(messages).isNotEmpty();

        @SuppressWarnings("unchecked")
        List<Object> abendMessages = (List<Object>) messages.get(0);
        assertThat(abendMessages).containsExactly(AbendDetail.EXTERNAL_ABEND_MSG);
    }

    /**
     * Confirms the submitted date mask is a bounded string and not an enumeration.
     *
     * <p>Refactoring Rationale: an enumerated mask made the documented bad-pattern verdict unreachable,
     * because a request validator refuses a value outside an enumeration before any evaluation runs. The
     * bound is the baseline's own ten-character mask argument, so the wider schema is not unbounded.</p>
     */
    @Test
    @DisplayName("the submitted mask is bounded and unenumerated so the bad-pattern verdict is reachable")
    void submittedMaskAdmitsAnUnrecognisedPattern() {
        Map<String, Object> maskSchemaRef = mapping(parameter("DateMaskQuery"), "schema");
        assertThat(maskSchemaRef).containsEntry("$ref", "#/components/schemas/DateMaskInput");

        Map<String, Object> input = schema("DateMaskInput");
        assertThat(input).doesNotContainKey("enum");
        assertThat(input).containsEntry("maxLength", 10);

        // WHY : Assumptions: the recognised set stays published on its own schema, which is what F-40's
        //       resolution asks for -- a caller still needs to know which masks are interpreted, and the
        //       two facts are separable only if they are two schemas.
        assertThat(schema("DateMask").get("enum")).isEqualTo(List.of("YYYY-MM-DD", "YYYYMMDD"));

        Map<String, Object> result = mapping(schema("DateEvaluationResult"), "properties");
        List<Object> refs = new ArrayList<>();
        collect(mapping(result, "mask"), "$ref", refs);
        assertThat(refs).containsExactly("#/components/schemas/DateMaskInput");
    }

    /**
     * Confirms the stored type code refuses zero and the list filter admits it.
     *
     * <p>Refactoring Rationale: one schema served both, and the two disagree about a single value. The
     * baseline's numeric edit refuses a zero type code outright, while its list filter treats zeros,
     * spaces and low values alike as "no filter". One schema could not both refuse the value and give it
     * a meaning.</p>
     */
    @Test
    @DisplayName("the stored type code refuses 00 and the list filter admits it")
    void storedTypeCodeAndFilterAdmitDifferentValues() {
        Pattern stored = Pattern.compile((String) schema("TransactionTypeCode").get("pattern"));
        assertThat(stored.matcher("00").matches()).isFalse();
        assertThat(stored.matcher("01").matches()).isTrue();
        assertThat(stored.matcher("07").matches()).isTrue();
        assertThat(stored.matcher("99").matches()).isTrue();

        Pattern filter = Pattern.compile((String) schema("TransactionTypeCodeFilter").get("pattern"));
        assertThat(filter.matcher("00").matches()).isTrue();
        assertThat(filter.matcher("01").matches()).isTrue();

        assertThat(mapping(parameter("TypeCodeFilter"), "schema"))
                .containsEntry("$ref", "#/components/schemas/TransactionTypeCodeFilter");
        assertThat(mapping(parameter("TypeCdPath"), "schema"))
                .containsEntry("$ref", "#/components/schemas/TransactionTypeCode");
    }

    /**
     * Confirms the stored description enforces the baseline domain and the filter enforces none.
     *
     * <p>Refactoring Rationale: the stored schema declared no pattern at all and described the domain
     * wrongly, omitting digits. The same schema was then reused for the list filter, where the baseline
     * applies no character rule and wraps the caller's text in wildcards itself, so a filter and a stored
     * value admit different text and needed separating.</p>
     */
    @Test
    @DisplayName("the stored description enforces letters, digits and spaces while the filter does not")
    void storedDescriptionAndFilterAdmitDifferentText() {
        Pattern stored = Pattern.compile((String) schema("ReferenceDescription").get("pattern"));
        assertThat(stored.matcher("Purchase").matches()).isTrue();
        assertThat(stored.matcher("Regular Sales Draft").matches()).isTrue();
        assertThat(stored.matcher("Sales Draft 2").matches()).isTrue();
        assertThat(stored.matcher("   ").matches()).isFalse();
        assertThat(stored.matcher("Cash-advance").matches()).isFalse();
        assertThat(stored.matcher("Fee%").matches()).isFalse();

        assertThat(schema("ReferenceDescriptionFilter")).doesNotContainKey("pattern");
        assertThat(mapping(parameter("DescriptionFilter"), "schema"))
                .containsEntry("$ref", "#/components/schemas/ReferenceDescriptionFilter");
    }

    /**
     * Confirms the account group key keeps the copybook character domain.
     *
     * <p>Refactoring Rationale: the schema declared an upper-case alphanumeric pattern the copybook does
     * not have. The field is a ten-character alphanumeric display field and the column is fixed
     * character, so a stored group whose identifier held a lower-case letter or a space was refused as a
     * malformed request and could not be addressed at all -- which also put the interest calculation's
     * fallback group out of reach, since that fallback is reached by looking a group up and finding
     * nothing.</p>
     */
    @Test
    @DisplayName("the account group key admits the copybook's character domain")
    void accountGroupKeyKeepsTheCopybookDomain() {
        Pattern group = Pattern.compile((String) schema("AccountGroupId").get("pattern"));
        assertThat(group.matcher("DEFAULT").matches()).isTrue();
        assertThat(group.matcher("A000000000").matches()).isTrue();
        assertThat(group.matcher("zeroapr").matches()).isTrue();
        assertThat(group.matcher("A 00000001").matches()).isTrue();
        assertThat(group.matcher("A-000-0001").matches()).isTrue();

        // WHY : Assumptions: the two refusals are the transport bound and the width bound, not a
        //       narrowing of the domain -- a control character cannot travel unambiguously in a path
        //       segment, and eleven characters is longer than the column.
        assertThat(group.matcher("A\u0000000001").matches()).isFalse();
        assertThat(group.matcher("ABCDEFGHIJK").matches()).isFalse();
    }

    /**
     * Confirms the maintenance batch matches the one table the baseline program maintains.
     *
     * <p>Refactoring Rationale: an action could carry a category code, routing it at a table the baseline
     * program never touches -- all three of its statements name the transaction-type table and its input
     * record has no category field. The member was removed rather than registered as a divergence,
     * because category writes are already available on the category operations, so nothing is lost.</p>
     */
    @Test
    @DisplayName("a maintenance action addresses a transaction type and nothing else")
    void maintenanceAddressesTransactionTypesOnly() {
        assertThat(mapping(schema("MaintenanceAction"), "properties"))
                .containsOnlyKeys("action", "typeCd", "description");
        assertThat(mapping(schema("MaintenanceActionOutcome"), "properties"))
                .containsOnlyKeys("position", "action", "typeCd", "outcome", "applied", "message");
    }

    /**
     * Confirms the description requirement is enforced by the schema and tracks the action.
     *
     * <p>Refactoring Rationale: the requirement was prose only -- the text said a description is required
     * for an insert or an update and ignored for a delete, and the schema enforced neither, so a request
     * inserting a type with no description validated and a delete carrying one was accepted without any
     * statement of what became of it. The conditional makes both cases decidable by a validator.</p>
     */
    @Test
    @DisplayName("the description requirement is action-discriminated in the schema")
    void descriptionRequirementTracksTheAction() {
        Map<String, Object> action = schema("MaintenanceAction");
        assertThat(action).containsKeys("if", "then", "else");

        assertThat(mapping(mapping(mapping(action, "if"), "properties"), "action").get("enum"))
                .isEqualTo(List.of("INSERT", "UPDATE"));
        assertThat(mapping(action, "then").get("required")).isEqualTo(List.of("description"));
        assertThat(mapping(mapping(mapping(action, "else"), "properties"), "description"))
                .containsEntry("type", "null");
    }

    /**
     * Confirms the batch reports per-action outcomes and the baseline's aggregate condition code.
     *
     * <p>Refactoring Rationale: the response used to be returned only for an all-or-nothing batch, and
     * the baseline has no such transaction: its failure paragraph displays a message, sets a warning
     * condition code and returns to the read loop, so later actions are attempted and earlier successes
     * are kept. Publishing the condition code is what lets a caller detect a partial run without
     * scanning every outcome, and it is the baseline's own value rather than an invented one.</p>
     */
    @Test
    @DisplayName("the batch response carries per-action outcomes and the aggregate condition code")
    void batchResponseReportsOutcomesAndConditionCode() {
        Map<String, Object> body = schema("MaintenanceActionBatchResponse");
        assertThat(mapping(body, "properties")).containsOnlyKeys("outcomes", "returnCode");
        assertThat(body.get("required")).isEqualTo(List.of("outcomes", "returnCode"));
        assertThat(mapping(mapping(body, "properties"), "returnCode").get("enum"))
                .isEqualTo(List.of(0, 4));

        assertThat(schema("MaintenanceActionOutcomeState").get("enum"))
                .isEqualTo(List.of("APPLIED", "NO_ROWS_FOUND", "FAILED"));

        // WHY : Assumptions: the ACTION enumeration is asserted here alongside the outcome
        // enumeration, because the register entry D-REFERENCE-ACTION-DOMAIN cites this class for
        // exactly that claim and nothing asserted it. A closed enumeration is the mechanism by
        // which an unrecognised action token is refused by the validator rather than reaching the
        // service and being soft-rejected per row, which is the divergence that entry records; an
        // open enumeration would move that refusal from the contract into the implementation
        // without any document saying so.
        assertThat(schema("MaintenanceActionType").get("enum"))
                .isEqualTo(List.of("INSERT", "UPDATE", "DELETE"));

        Map<String, Object> responses = mapping(
                mapping(mapping(this.contract, "paths"), "/api/v1/reference/maintenance-actions"),
                "post");
        // WHY : Refactoring Rationale: 503 was added to this set when the online-write gate was
        //       wired, and it does NOT weaken what this assertion was written for. The load-bearing
        //       claim is the continued absence of a batch-level 404 and 409 -- those would describe
        //       an all-or-nothing run the baseline does not have. A 503 says nothing about the batch
        //       at all: it reports that the environment is not accepting mutating work, so the
        //       operation was refused before any action was read and no outcome exists to report per
        //       action. The set is still asserted exactly rather than loosened to "contains", because
        //       an exact set is what would catch a 409 being added here later.
        assertThat(mapping(responses, "responses").keySet())
                .containsExactlyInAnyOrder("200", "400", "401", "403", "500", "503");
    }

    /**
     * Confirms every published cursor example is a token the shared cursor codec would accept.
     *
     * <p>Refactoring Rationale: all ten cursor literals in this document had signature segments of the
     * wrong length, so every one was invalid against the pattern published beside it. An example a client
     * copies has to be a value the service would accept, or the first request written from the document
     * fails.</p>
     */
    @Test
    @DisplayName("every cursor example matches the published pattern and the shared codec's shape")
    void cursorExamplesAreAcceptedByTheSharedCodec() {
        Pattern published = Pattern.compile((String) schema("CursorToken").get("pattern"));

        // WHY : Refactoring Rationale: every STRING in the document is walked rather than only the
        //       cursor schema's own examples. The ten invalid literals the review found were not on that
        //       schema at all -- they sat inside page envelopes illustrating five list responses, as
        //       firstKey and lastKey members -- so an assertion scoped to the schema would have passed
        //       while every example a caller copies stayed invalid.
        List<String> cursorLike = new ArrayList<>();
        collectCursorLike(this.contract, cursorLike);

        assertThat(cursorLike).isNotEmpty();
        for (String token : cursorLike) {
            assertThat(published.matcher(token).matches())
                    .as("cursor example %s matches the published pattern", token)
                    .isTrue();
            assertThat(CursorToken.hasSealedShape(token))
                    .as("cursor example %s has the shape the shared codec opens", token)
                    .isTrue();
        }
    }

    /**
     * Confirms the published correlation domain is the domain the shared filter accepts.
     *
     * <p>Assumptions: the equivalence is asserted over vectors chosen for the boundaries that matter
     * rather than exhaustively -- the four shapes a primary account number can take, a date, a
     * date-and-time, a minted identity and an over-long value. The filter is the authority; the pattern
     * is a restatement of it, and a restatement that has drifted is worse than none.</p>
     */
    @Test
    @DisplayName("the published correlation domain matches the shared filter")
    void correlationDomainMatchesTheSharedFilter() {
        Pattern published = Pattern.compile((String) schema("CorrelationId").get("pattern"));

        // WHY : Assumptions: the empty string is excluded from the equivalence and asserted separately
        //       below, because the two are not meant to agree on it. This schema types BOTH the optional
        //       request header and the correlationId member of the error body, and the body carries the
        //       empty string for a failure raised before any identity was established -- which the
        //       schema's own text states. The filter refuses an empty header, mints an identity and
        //       proceeds, so a request never carries one.
        List<String> vectors = List.of(
                "4111111111111111",
                "4111-1111-1111-1111",
                "4111.1111.1111.1111",
                "4111_1111_1111_1111",
                "2022-07-18",
                "2022-07-18-0930",
                "CD0A1B2C3D4E5F60718293",
                "A".repeat(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH),
                "A".repeat(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH + 1),
                "has space",
                "1234567890123");

        for (String vector : vectors) {
            assertThat(published.matcher(vector).matches())
                    .as("published pattern agrees with the filter on %s", vector)
                    .isEqualTo(CorrelationIdFilter.isConformingCorrelationId(vector));
        }

        assertThat(published.matcher("").matches()).isTrue();
        assertThat(CorrelationIdFilter.isConformingCorrelationId("")).isFalse();
    }
}
