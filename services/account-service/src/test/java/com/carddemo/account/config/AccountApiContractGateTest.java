package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.account.mapper.CustomerMapper;
import com.carddemo.common.security.InternalServiceToken;
import com.carddemo.common.security.MaskedCardNumber;
import com.carddemo.common.web.CursorToken;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the committed account contract to the runtime it describes, member by measurable member.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Refactoring Rationale: a review of this contract found five defects of one kind -- the document
 * describing a service other than the one that runs. The correlation identifier was accepted and echoed on
 * every operation by a shared filter and published on none of them. The cursor parameters accepted any
 * string up to two hundred and fifty-six characters while the runtime opens only sealed tokens of one exact
 * shape. The masked card number was documented as sixteen characters and emitted as eight, so the browser
 * client that enforced the documented shape refused every populated response. Two response examples carried
 * a member the schema forbids, and the schema forbids it because the source withdrew it. And the one
 * operation that discloses a card number in full had no published statement of the credential it demands.
 *
 * <p>Assumptions: none of the five could be caught by a test of the CODE, and none by a test of the
 * DOCUMENT alone. Each is a disagreement between the two, so each assertion here reads the committed
 * document and compares it against the constant, the filter or the route that implements it -- which is why
 * every expectation below is derived from a production constant rather than written as a literal.
 *
 * <p>Measured: each of the five cases was confirmed to discriminate by mutating the committed document and
 * observing which case failed. Deleting one operation's correlation parameter failed the first case alone;
 * lowering the cursor ceiling by one failed the second alone; narrowing the mask to a four-character prefix
 * failed the third alone; granting the purpose-bound resolve scope to a second operation failed the fourth
 * alone; and restoring the withdrawn {@code error} member to one example failed the fifth alone, reporting
 * the JSON path of the offending entry. Every mutation was reverted byte for byte.
 *
 * <h2>Why the committed file and not the served document</h2>
 *
 * <p>Alternatives Considered: asserting against the document {@code OpenApiConfig} serves. Rejected,
 * because that document is assembled from the same constants a wrong assertion would be written from, so it
 * cannot show that the SHIPPED file is right. {@code OpenApiDocumentTest} covers the served document's
 * version and security scheme; this class covers the file a consumer generates a client from.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception tag.</p>
 */
class AccountApiContractGateTest {

    /** The committed contract this module ships. */
    private static final String CONTRACT_RESOURCE = "/openapi/account-api.yaml";

    /** The reference the shared correlation parameter is published under. */
    private static final String CORRELATION_PARAMETER_REF =
            "#/components/parameters/CorrelationIdHeader";

    /** The name of the correlation response header, as the shared filter writes it. */
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** The reference the shared correlation response header is published under. */
    private static final String CORRELATION_HEADER_REF = "#/components/headers/XCorrelationId";

    /** The reference the canonical cursor-token schema is published under. */
    private static final String CURSOR_TOKEN_REF = "#/components/schemas/CursorToken";

    /** The HTTP methods a path item may carry an operation under. */
    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /**
     * The schemas permitted to publish an unmasked sixteen-digit card number.
     *
     * <p>Assumptions: the set is closed at two and each entry is there for a different reason. The request
     * shape carries a card number because the caller supplied it -- a lookup keyed by card number cannot be
     * keyed by anything else, and echoing a value back to the party that sent it discloses nothing. The
     * account-keyed response carries one because the value IS the answer: the reference program reads the
     * alternate index by account and takes the card number from the row, which becomes the ledger key its
     * consumer must write, and a ledger key declared {@code PIC X(16)} cannot be satisfied by an opaque
     * reference. That divergence is recorded on the operation, on its response and on the scope constant
     * that gates it.</p>
     *
     * <p>Assumptions: this is the assertion that keeps the divergence CONFINED. Publishing the same shape
     * on a third schema -- an end-user response, a page item, a report line -- is how a bounded exception
     * becomes an ordinary disclosure, and it would be a two-line edit that nothing else in this repository
     * would report.</p>
     */
    private static final Set<String> SCHEMAS_PERMITTED_AN_UNMASKED_CARD_NUMBER =
            Set.of("CardXrefLookupRequest", "CardXrefByAccountView");

    /** The pattern an unmasked sixteen-digit card number is published with. */
    private static final String UNMASKED_CARD_NUMBER_PATTERN = "^[0-9]{16}$";

    /** The one operation permitted to answer with an unmasked card number. */
    private static final String CARD_NUMBER_DISCLOSING_OPERATION =
            "lookupCardCrossReferenceByAccount";

    /** The vendor-extension member an operation states its required internal scope in. */
    private static final String SCOPE_EXTENSION = "x-carddemo-required-scope";

    /**
     * Asserts that every operation publishes the correlation parameter and every response its header.
     *
     * <p>Refactoring Rationale: this case answers a finding that the shared filter reads a correlation
     * identifier from every request and writes one onto every response while the document mentioned neither.
     * A generated client therefore had no member to carry the value in and no member to read it from, so the
     * one identifier that ties a client's report of a failure to this service's log of it was unreachable
     * from the contract.</p>
     *
     * <p>Assumptions: a response satisfies the rule either by declaring the header inline or by being a
     * reference to a shared component response that declares it. Both forms appear in this document, and
     * requiring the inline form would have forced five shared responses to be expanded at twenty-seven call
     * sites -- which is the duplication those components exist to avoid.</p>
     */
    @Test
    @DisplayName("every operation publishes the correlation parameter and every response its header")
    void everyOperationAndResponsePublishesTheCorrelationIdentity() {
        Map<String, Object> contract = contract();
        List<String> operationsMissingTheParameter = new ArrayList<>();
        List<String> responsesMissingTheHeader = new ArrayList<>();

        for (Map.Entry<String, Object> operation : operations(contract).entrySet()) {
            Map<String, Object> body = asMap(operation.getValue());
            if (!declaresCorrelationParameter(body)) {
                operationsMissingTheParameter.add(operation.getKey());
            }
            for (Map.Entry<String, Object> response : asMap(body.get("responses")).entrySet()) {
                if (!declaresCorrelationHeader(contract, asMap(response.getValue()))) {
                    responsesMissingTheHeader.add(operation.getKey() + " " + response.getKey());
                }
            }
        }

        assertThat(operationsMissingTheParameter)
                .as("the shared filter accepts this header on every request, so every operation has to"
                        + " publish the parameter a client would send it in")
                .isEmpty();
        assertThat(responsesMissingTheHeader)
                .as("the shared filter writes this header onto every response, including refusals, so"
                        + " every response has to publish it or reference a component response that does")
                .isEmpty();
    }

    /**
     * Asserts that the published cursor shape is the one the runtime opens, and that every cursor uses it.
     *
     * <p>Refactoring Rationale: the document declared each cursor as a free string bounded at two hundred
     * and fifty-six characters, while the runtime accepts only a sealed token of one exact shape. The gap
     * was not cosmetic: a client generated from that document would have been told it could page from any
     * string it chose, and every such request would have been refused.</p>
     *
     * <p>Assumptions: the expectation is READ from the production constants rather than restated, so a
     * change to the token's version prefix or its ceiling fails this case instead of silently making the
     * document describe the previous format.</p>
     *
     * <p>Assumptions: the published pattern is the production constant with ANCHORS ADDED, and the
     * difference is required rather than incidental. The constant is consumed by
     * {@code Matcher.matches()}, which anchors implicitly, so anchors there would be redundant -- while a
     * JSON Schema {@code pattern} is specified as a PARTIAL match, so the same expression published
     * unanchored would accept a well-formed token embedded in any surrounding text. Comparing the document
     * against the anchored composition is what holds the two to one definition while letting each carry the
     * anchoring its own matcher requires. Contrast {@code MaskedCardNumber#DOMAIN}, which is anchored at the
     * source for a reason its own documentation records, and is therefore compared verbatim below.</p>
     */
    @Test
    @DisplayName("the published cursor schema is the sealed shape the runtime opens")
    void theCursorSchemaIsTheSealedShapeTheRuntimeOpens() {
        Map<String, Object> contract = contract();
        Map<String, Object> cursor = asMap(schemas(contract).get("CursorToken"));

        assertThat(cursor)
                .as("the canonical cursor schema must exist for the parameters and page members to"
                        + " reference")
                .isNotEmpty();
        assertThat(cursor)
                .as("a JSON Schema pattern matches partially, so the published expression must be the"
                        + " production shape anchored at both ends")
                .containsEntry("pattern", "^" + CursorToken.SEALED_SHAPE_PATTERN + "$");
        assertThat(cursor).containsEntry("maxLength", CursorToken.MAX_TOKEN_LENGTH);

        Set<String> unreferenced = new TreeSet<>(cursorCarryingMembersNotReferencingTheSchema(contract));
        assertThat(unreferenced)
                .as("a cursor member declaring its own facets is a second copy of the token's shape that"
                        + " can drift from the sealer while both keep compiling")
                .isEmpty();
    }

    /**
     * Asserts that the published masked card number is the shape the shared masker produces.
     *
     * <p>Refactoring Rationale: this case answers a live break rather than a documentation gap. The
     * document said sixteen characters and gave a twelve-mask example; the mapping layer emitted four
     * asterisks and four digits from a rule of its own; and the browser client enforced the documented shape
     * and raised on every populated response. Three answers to one question, and the only one a user saw
     * was the failure.</p>
     *
     * <p>Assumptions: the expectation is composed from the shared constants, so the document, the mapper
     * and the client are now three readers of one definition rather than three authors of three.</p>
     */
    @Test
    @DisplayName("the published masked card number is the shared masker's own shape")
    void thePublishedMaskedCardNumberIsTheSharedMaskersShape() {
        Map<String, Object> masked = asMap(asMap(asMap(schemas(contract()).get("CardXrefResponse"))
                .get("properties")).get("cardNumberMasked"));

        assertThat(masked)
                .as("the end-user cross-reference response must publish the masked member")
                .isNotEmpty();
        assertThat(masked).containsEntry("pattern", MaskedCardNumber.DOMAIN);
        assertThat(masked).containsEntry("minLength", MaskedCardNumber.MASKED_LENGTH);
        assertThat(masked).containsEntry("maxLength", MaskedCardNumber.MASKED_LENGTH);
    }

    /**
     * Asserts that every published protected-identifier member is pinned to the marker the mapper emits.
     *
     * <p>Refactoring Rationale: this is a sixth defect of the same kind as the five above, found by a later
     * review. Both protected customer identifiers were published as {@code type: string} with a
     * {@code maxLength} alone -- 12 and 20, the screen-field widths -- and a bare maximum of 12 admits a
     * WHOLE formatted national identifier, {@code 123-45-6789} being eleven characters. So a service, a
     * stub or a proxy returning the clear value satisfied the schema exactly as the ten-character marker
     * does, and the browser screen reading it would have painted it. The account-view declaration
     * additionally described a mask "all but the last four" and carried the example
     * {@code '***-**-6789'}, neither of which the delivered service ever emits, while the sibling
     * declaration of the same property described the fixed marker correctly -- so the document contradicted
     * itself about the one property whose whole purpose is non-disclosure.</p>
     *
     * <p>Assumptions: the expectation is derived from {@link CustomerMapper#IDENTIFIER_REDACTED} rather than
     * written as a literal, exactly as the masked-card-number case derives from its own constant. The
     * pattern is then exercised BOTH ways: it must accept the marker the mapper publishes, and it must
     * refuse a formatted national identifier. Asserting acceptance alone would pass for a pattern that
     * accepts everything, which is the state this case was written to end.</p>
     *
     * <p>Assumptions: all FOUR declarations are checked -- both properties on the account-view grouping and
     * both on the standalone customer shape -- because pinning one and leaving the other open would leave a
     * reader comparing two declarations of one property and guessing which describes the service. The
     * standalone shape is reachable only with an internal credential, and it is pinned anyway for that
     * reason.</p>
     */
    @Test
    @DisplayName("every published protected identifier is pinned to the mapper's redaction marker")
    void everyPublishedProtectedIdentifierIsPinnedToTheRedactionMarker() {
        Map<String, Object> schemas = schemas(contract());
        List<String> shapes = List.of("CustomerDetail", "CustomerResponse");
        List<String> members = List.of("ssnMasked", "governmentIssuedIdMasked");

        for (String shape : shapes) {
            for (String member : members) {
                Map<String, Object> declared =
                        asMap(asMap(asMap(schemas.get(shape)).get("properties")).get(member));

                assertThat(declared)
                        .as("%s must declare %s", shape, member)
                        .isNotEmpty();
                Object pattern = declared.get("pattern");
                assertThat(pattern)
                        .as("%s.%s must publish the redaction pattern", shape, member)
                        .isInstanceOf(String.class);

                String expression = (String) pattern;
                assertThat(CustomerMapper.IDENTIFIER_REDACTED)
                        .as("%s.%s must admit the marker the mapper publishes", shape, member)
                        .matches(expression);
                // WHY : Assumptions: the counter-example is a formatted national identifier rather than
                //       nine bare digits, because the formatted form is the one the withdrawn example
                //       carried and the one that fitted the declared maximum. A pattern that refuses it
                //       refuses the bare form too, being anchored on a literal.
                assertThat("123-45-6789")
                        .as("%s.%s must refuse a whole formatted identifier", shape, member)
                        .doesNotMatch(expression);
                assertThat(declared)
                        .as("%s.%s must carry no example resembling an identifier", shape, member)
                        .doesNotContainKey("example");
            }
        }
    }

    /**
     * Asserts that the unmasked card number is confined to two schemas and one internal-only operation.
     *
     * <p>Refactoring Rationale: this is the executable half of the migration's one documented card-number
     * divergence. The review's suggested remedies -- returning an opaque reference, or moving the ledger
     * write behind the owning context -- were both rejected with reasons recorded on the response shape:
     * the value becomes a {@code PIC X(16)} ledger key that no opaque reference can satisfy, and moving the
     * write would put two contexts' data under one owner. What replaces them is a bound: one purpose-bound
     * scope, granted to one subject, on one address. A bound is only a control while something checks that
     * it has not widened, and this case is that check.</p>
     *
     * <p>Assumptions: three independent properties are asserted rather than one. That no third schema
     * publishes the shape; that the disclosing operation states the purpose-bound scope; and that no other
     * operation states that scope. The first two could each hold while the third failed -- granting the
     * resolve scope to a second address is the cheapest way to widen this exception, and it changes neither
     * schema.</p>
     */
    @Test
    @DisplayName("the unmasked card number is confined to two schemas and one internal-only operation")
    void theUnmaskedCardNumberIsConfinedToItsDocumentedException() {
        Map<String, Object> contract = contract();

        Set<String> disclosing = new TreeSet<>();
        for (Map.Entry<String, Object> schema : schemas(contract).entrySet()) {
            for (Map.Entry<String, Object> property
                    : asMap(asMap(schema.getValue()).get("properties")).entrySet()) {
                if (UNMASKED_CARD_NUMBER_PATTERN.equals(asMap(property.getValue()).get("pattern"))) {
                    disclosing.add(schema.getKey());
                }
            }
        }

        assertThat(disclosing)
                .as("an unmasked primary account number may appear only on the request that supplies one"
                        + " and on the account-keyed response whose whole purpose is to return one")
                .containsExactlyInAnyOrderElementsOf(
                        new TreeSet<>(SCHEMAS_PERMITTED_AN_UNMASKED_CARD_NUMBER));

        Map<String, Object> operations = operations(contract);
        Map<String, Object> disclosingOperation =
                asMap(operations.get(CARD_NUMBER_DISCLOSING_OPERATION));
        assertThat(disclosingOperation)
                .as("the disclosing operation must exist under the identifier this gate names")
                .isNotEmpty();
        assertThat(disclosingOperation)
                .as("the one address answering with a card number must publish the purpose-bound scope"
                        + " it demands, so a consumer reads the credential from the contract rather than"
                        + " from a refusal")
                .containsEntry(SCOPE_EXTENSION,
                        InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER);

        List<String> alsoDemandingTheResolveScope = new ArrayList<>();
        for (Map.Entry<String, Object> operation : operations.entrySet()) {
            if (CARD_NUMBER_DISCLOSING_OPERATION.equals(operation.getKey())) {
                continue;
            }
            if (InternalServiceToken.SCOPE_CARD_XREF_RESOLVE_CARD_NUMBER
                    .equals(asMap(operation.getValue()).get(SCOPE_EXTENSION))) {
                alsoDemandingTheResolveScope.add(operation.getKey());
            }
        }
        assertThat(alsoDemandingTheResolveScope)
                .as("the resolve scope exists to name ONE address; a second operation demanding it widens"
                        + " the exception without changing a schema")
                .isEmpty();
    }

    /**
     * Asserts that every example conforms to the closed schema it illustrates.
     *
     * <p>Refactoring Rationale: two examples on this contract carried a {@code fieldErrors[].error}
     * member. The schema forbids it -- it declares its three members and closes itself to any other -- and
     * it forbids it because the source withdrew the derived predicate that produced it. An example is what a
     * consumer copies, and a generator that validates examples would have refused the document, so the
     * defect was both misleading and latent.</p>
     *
     * <p>Assumptions: only examples illustrating a CLOSED object schema are checked, and only for their
     * member names and required members. Closure is what makes an unexpected member decidably wrong, and
     * value-level validation -- patterns, bounds, formats -- belongs to a specification validator rather
     * than to a unit test; the two defects found were both structural.</p>
     */
    @Test
    @DisplayName("every example conforms to the closed schema it illustrates")
    void everyExampleConformsToTheClosedSchemaItIllustrates() {
        Map<String, Object> contract = contract();
        List<String> violations = new ArrayList<>();
        checkExamples(contract, contract, "", violations);

        assertThat(violations)
                .as("an example is what a consumer copies, so it must satisfy the schema beside it")
                .isEmpty();
    }

    /**
     * Walks the document, checking every example that sits beside a schema reference.
     *
     * @param contract the whole parsed document, needed to resolve a reference
     * @param node the node being visited
     * @param path the location of {@code node}, for a violation to name
     * @param violations the collector every violation is added to
     */
    private static void checkExamples(Map<String, Object> contract, Object node, String path,
            List<String> violations) {
        if (node instanceof Map<?, ?> mapping) {
            Object schema = mapping.get("schema");
            if (schema != null && mapping.containsKey("example")) {
                validateExample(contract, resolve(contract, schema), mapping.get("example"),
                        path + "/example", violations);
            }
            if (schema != null && mapping.get("examples") instanceof Map<?, ?> examples) {
                for (Map.Entry<?, ?> example : examples.entrySet()) {
                    validateExample(contract, resolve(contract, schema),
                            asMap(example.getValue()).get("value"),
                            path + "/examples/" + example.getKey(), violations);
                }
            }
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                checkExamples(contract, entry.getValue(), path + "/" + entry.getKey(), violations);
            }
        } else if (node instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                checkExamples(contract, list.get(index), path + "/" + index, violations);
            }
        }
    }

    /**
     * Checks one example object against one resolved schema, for member names and required members.
     *
     * <p>Assumptions: a schema that is not a closed object, or an example that is not an object, is
     * skipped rather than reported. Neither can produce the class of defect this case exists for, and
     * reporting them would bury the ones that can.</p>
     *
     * @param contract the whole parsed document, needed to resolve a nested reference
     * @param schema the resolved schema the example illustrates
     * @param example the example value
     * @param path the location of the example, for a violation to name
     * @param violations the collector every violation is added to
     */
    private static void validateExample(Map<String, Object> contract, Map<String, Object> schema,
            Object example, String path, List<String> violations) {
        if (!(example instanceof Map<?, ?> object)) {
            return;
        }
        Map<String, Object> shape = objectShapeOf(contract, schema);
        if (!Boolean.FALSE.equals(shape.get("additionalProperties"))) {
            return;
        }
        Map<String, Object> properties = asMap(shape.get("properties"));
        for (Object member : object.keySet()) {
            if (!properties.containsKey(String.valueOf(member))) {
                violations.add(path + ": undeclared member '" + member + "'");
            }
        }
        if (shape.get("required") instanceof List<?> required) {
            for (Object member : required) {
                if (!object.containsKey(member)) {
                    violations.add(path + ": missing required member '" + member + "'");
                }
            }
        }
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            Object nested = properties.get(String.valueOf(entry.getKey()));
            if (nested == null) {
                continue;
            }
            Map<String, Object> nestedSchema = resolve(contract, nested);
            if (entry.getValue() instanceof List<?> items
                    && nestedSchema.get("items") != null) {
                Map<String, Object> itemSchema = resolve(contract, nestedSchema.get("items"));
                for (int index = 0; index < items.size(); index++) {
                    validateExample(contract, itemSchema, items.get(index),
                            path + "/" + entry.getKey() + "/" + index, violations);
                }
            } else {
                validateExample(contract, nestedSchema, entry.getValue(),
                        path + "/" + entry.getKey(), violations);
            }
        }
    }

    /**
     * Narrows a schema node to the object shape an example of it is validated against.
     *
     * <p>Measured: this exists because the case above could otherwise not see a whole class of defect, and
     * the hole was found in the equivalent gate for the card contract rather than here. An optional object
     * member is declared in these documents as a union of the object and the null type; a union carries no
     * {@code properties} and no {@code additionalProperties} of its own, so the walk returned before
     * checking anything nested inside it. In the card contract that shape held a real defect -- a required
     * selector missing from a conflict example -- and all four cases passed until the narrowing was added.
     * This document declares one such member, the abend detail on the problem shape, and every example it
     * publishes sets that member to null; so the narrowing catches nothing here TODAY, and it is added
     * because the claim the case makes -- that every example conforms to the schema it illustrates -- is
     * otherwise untrue of the first non-null abend example anyone writes.
     *
     * <p>Measured: substituting a non-null abend object carrying two of its four required members into one
     * conflict example fails the case and names both missing members and the JSON path, so the narrowing is
     * exercised rather than merely present. The substitution was reverted byte for byte.
     *
     * <p>Assumptions: a union is narrowed only when exactly ONE branch is an object, because a consumer
     * accepts an example that any branch admits, so reporting it against one of several branches would
     * report a defect that is not one. No schema here has two object branches.
     *
     * @param contract the whole parsed document
     * @param schema the resolved schema node
     * @return the object shape to validate against, or {@code schema} unchanged when there is none
     */
    private static Map<String, Object> objectShapeOf(Map<String, Object> contract,
            Map<String, Object> schema) {
        if (schema.containsKey("properties")) {
            return schema;
        }
        List<Map<String, Object>> objectBranches = new ArrayList<>();
        for (String keyword : List.of("oneOf", "anyOf")) {
            if (!(schema.get(keyword) instanceof List<?> branches)) {
                continue;
            }
            for (Object branch : branches) {
                Map<String, Object> resolved = resolve(contract, branch);
                if (resolved.containsKey("properties")) {
                    objectBranches.add(resolved);
                }
            }
        }
        return objectBranches.size() == 1 ? objectBranches.get(0) : schema;
    }

    /**
     * Reports whether an operation declares the shared correlation parameter.
     *
     * @param operation the parsed operation object
     * @return {@code true} when its parameter list references the shared component
     */
    private static boolean declaresCorrelationParameter(Map<String, Object> operation) {
        if (!(operation.get("parameters") instanceof List<?> parameters)) {
            return false;
        }
        return parameters.stream()
                .map(AccountApiContractGateTest::asMap)
                .anyMatch(parameter -> CORRELATION_PARAMETER_REF.equals(parameter.get("$ref")));
    }

    /**
     * Reports whether a response declares the correlation header, directly or through a component.
     *
     * @param contract the whole parsed document, needed to resolve a component response
     * @param response the parsed response object, which may itself be a reference
     * @return {@code true} when the response publishes the header
     */
    private static boolean declaresCorrelationHeader(Map<String, Object> contract,
            Map<String, Object> response) {
        Map<String, Object> resolved = resolve(contract, response);
        Map<String, Object> headers = asMap(resolved.get("headers"));
        Object header = headers.get(CORRELATION_HEADER);
        if (header == null) {
            return false;
        }
        Map<String, Object> headerObject = asMap(header);
        return CORRELATION_HEADER_REF.equals(headerObject.get("$ref"))
                || headerObject.containsKey("schema");
    }

    /**
     * Collects every member that carries a paging cursor without referencing the canonical schema.
     *
     * <p>Assumptions: a cursor member is recognised by NAME -- a parameter or property called
     * {@code cursor}, {@code firstKey} or {@code lastKey} -- because that is what the runtime binds, and
     * recognising it by shape would find whatever shape the document happened to declare.</p>
     *
     * @param contract the whole parsed document
     * @return the locations of cursor members not resolving to the canonical schema; never {@code null}
     */
    private static Set<String> cursorCarryingMembersNotReferencingTheSchema(
            Map<String, Object> contract) {
        Set<String> offenders = new LinkedHashSet<>();
        for (Map.Entry<String, Object> operation : operations(contract).entrySet()) {
            if (!(asMap(operation.getValue()).get("parameters") instanceof List<?> parameters)) {
                continue;
            }
            for (Object parameter : parameters) {
                Map<String, Object> object = asMap(parameter);
                if (!"cursor".equals(object.get("name"))) {
                    continue;
                }
                if (!referencesCursorSchema(object.get("schema"))) {
                    offenders.add(operation.getKey() + " parameter cursor");
                }
            }
        }
        for (Map.Entry<String, Object> schema : schemas(contract).entrySet()) {
            for (Map.Entry<String, Object> property
                    : asMap(asMap(schema.getValue()).get("properties")).entrySet()) {
                if (!List.of("firstKey", "lastKey", "cursor").contains(property.getKey())) {
                    continue;
                }
                if (!referencesCursorSchema(property.getValue())) {
                    offenders.add(schema.getKey() + "." + property.getKey());
                }
            }
        }
        return offenders;
    }

    /**
     * Reports whether a schema node resolves to the canonical cursor schema, directly or in a union.
     *
     * <p>Assumptions: a union is accepted because a nullable page boundary is expressed as a choice
     * between the token schema and the null type, which is how this document declares the first and last
     * keys of an empty page.</p>
     *
     * @param schema the schema node beside a cursor member
     * @return {@code true} when the canonical schema is referenced
     */
    private static boolean referencesCursorSchema(Object schema) {
        if (schema == null) {
            return false;
        }
        Map<String, Object> object = asMap(schema);
        if (CURSOR_TOKEN_REF.equals(object.get("$ref"))) {
            return true;
        }
        for (String keyword : List.of("oneOf", "anyOf", "allOf")) {
            if (object.get(keyword) instanceof List<?> branches
                    && branches.stream().anyMatch(AccountApiContractGateTest::referencesCursorSchema)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a node that may be a local reference into the object it names.
     *
     * <p>Assumptions: only local references are followed, because this document declares no remote ones;
     * a remote reference would be returned unresolved and would fail the assertion that reads it, which is
     * the correct outcome for a shape this gate cannot see.</p>
     *
     * @param contract the whole parsed document
     * @param node the node, which may carry a reference
     * @return the referenced object, or {@code node} itself when it carries no local reference
     */
    private static Map<String, Object> resolve(Map<String, Object> contract, Object node) {
        Map<String, Object> object = asMap(node);
        Object reference = object.get("$ref");
        if (!(reference instanceof String pointer) || !pointer.startsWith("#/")) {
            return object;
        }
        Map<String, Object> current = contract;
        for (String segment : pointer.substring(2).split("/")) {
            current = asMap(current.get(segment.replace("~1", "/").replace("~0", "~")));
        }
        return current;
    }

    /**
     * Collects every operation in the document, keyed by its operation identifier.
     *
     * @param contract the whole parsed document
     * @return one entry per operation; never {@code null}
     */
    private static Map<String, Object> operations(Map<String, Object> contract) {
        Map<String, Object> operations = new LinkedHashMap<>();
        for (Map.Entry<String, Object> path : asMap(contract.get("paths")).entrySet()) {
            for (Map.Entry<String, Object> entry : asMap(path.getValue()).entrySet()) {
                if (!METHODS.contains(entry.getKey())) {
                    continue;
                }
                Object identifier = asMap(entry.getValue()).get("operationId");
                operations.put(identifier == null
                        ? path.getKey() + " " + entry.getKey() : String.valueOf(identifier),
                        entry.getValue());
            }
        }
        return operations;
    }

    /**
     * Returns the document's component schemas.
     *
     * @param contract the whole parsed document
     * @return the schema map; never {@code null}
     */
    private static Map<String, Object> schemas(Map<String, Object> contract) {
        return asMap(asMap(contract.get("components")).get("schemas"));
    }

    /**
     * Narrows a parsed node to a string-keyed mapping, answering an empty map for anything else.
     *
     * <p>Assumptions: an absent or non-mapping node answers empty rather than raising, so a walk over a
     * document whose shape differs from the expectation collects a violation at the assertion that reads
     * the value instead of failing with a cast error that names no member.</p>
     *
     * @param node the parsed node
     * @return the node as a mapping, or an empty map; never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return node instanceof Map ? (Map<String, Object>) node : Map.of();
    }

    /**
     * Parses the committed contract from the module's own resources.
     *
     * @return the parsed document; never {@code null}
     * @throws IllegalStateException when the resource is absent, which means the module ships no contract
     *     and every assertion here would otherwise pass over an empty document
     */
    private static Map<String, Object> contract() {
        try (InputStream stream = AccountApiContractGateTest.class
                .getResourceAsStream(CONTRACT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(CONTRACT_RESOURCE + " is not on the test classpath");
            }
            Map<String, Object> parsed = new Yaml().load(stream);
            if (parsed == null || parsed.isEmpty()) {
                throw new IllegalStateException(CONTRACT_RESOURCE + " parsed to nothing");
            }
            return parsed;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("could not read " + CONTRACT_RESOURCE, failure);
        }
    }
}
