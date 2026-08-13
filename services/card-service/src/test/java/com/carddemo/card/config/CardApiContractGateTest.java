package com.carddemo.card.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.card.api.CardController;
import com.carddemo.card.dto.AdminCardDetail;
import com.carddemo.card.dto.CardConflictError;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.error.ApiError;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
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
 * Holds the committed card contract to the Java types that serialise into it, member by measurable member.
 *
 * <h2>Why this class exists, and how it divides from its sibling</h2>
 *
 * <p>Refactoring Rationale: {@link CardApiContractTest} already holds this document against this module's
 * enforced security rules and against the shared kernel's constants -- which authority each operation
 * demands, what a selector is, how wide the correlation identity may be. It does not, and by its own
 * charter should not, hold the document against the RESPONSE RECORDS, and a review found three defects of
 * exactly that kind. The administrative detail schema required eight members while the record declared
 * seven, so the one member every card screen renders was absent from the one response that discloses the
 * number in full. Two response schemas accepted unknown members while the other five refused them, so a
 * body could gain a member with nothing to notice. And one published example omitted a member its own
 * schema requires, which means a consumer copying the example produced an invalid body.
 *
 * <p>Assumptions: none of the three is visible to either build. The Java compiler cannot read a YAML
 * document, and the TypeScript build reads the hand-written browser types rather than this file, so a
 * document that disagrees with the record compiles on both sides and fails only at a consumer. Each
 * assertion below therefore reads the committed document and compares it against the type, the constant or
 * the annotation that implements it, which is why every expectation is derived rather than written as a
 * literal.
 *
 * <p>Measured: each case was confirmed to discriminate by mutating the committed document and observing
 * which case failed. The mutations and their observed effects are recorded on the cases themselves. Every
 * mutation was reverted byte for byte.
 *
 * <h2>Why the committed file and not the served document</h2>
 *
 * <p>Alternatives Considered: asserting against the document {@link OpenApiConfig} serves. Rejected for the
 * reason its sibling records: that document is assembled from the same constants a wrong assertion would be
 * written from, so it cannot show that the SHIPPED file is right. {@link OpenApiDocumentTest} covers the
 * served document; this class covers the file a consumer generates a client from.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception tag.</p>
 */
class CardApiContractGateTest {

    /** The committed contract, read from the classpath so a stale packaged copy cannot pass. */
    private static final String CONTRACT_RESOURCE = "/openapi/card-api.yaml";

    /** The request methods a path item may carry, so a shared parameter block is not read as one. */
    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /**
     * Each response schema paired with the type whose serialisation it describes.
     *
     * <p>Assumptions: the pairing is stated here, once, and every member expectation is then DERIVED from
     * the type rather than listed. A list of member names would be a third declaration of each shape --
     * after the record and the schema -- and the defect being corrected is precisely two declarations
     * disagreeing, so adding a third would enlarge the problem rather than gate it.
     *
     * <p>Assumptions: the map covers every object schema of this document that a response body is built
     * from, including the three the shared kernel owns. The two open composition bases are deliberately
     * absent, because no response is built from either: each exists only to be composed by the closed
     * shapes above it, which is the property the closure case asserts independently.
     */
    private static final Map<String, Class<?>> SCHEMA_OWNERS = Map.of(
            "CardSummary", CardSummary.class,
            "CardDetail", CardDetail.class,
            "AdminCardDetail", AdminCardDetail.class,
            "CardConflictError", CardConflictError.class,
            "ApiError", ApiError.class,
            "FieldError", ApiError.FieldError.class,
            "AbendDetail", AbendDetail.class);

    /**
     * The three subordinate codes this context emits under the conflict status.
     *
     * <p>Assumptions: the values are read from {@link CardController}'s own constants, which are what the
     * handlers put into a body, so this set cannot agree with the document by restating the document.
     */
    private static final Set<String> EMITTED_CONFLICT_CODES = Set.of(
            CardController.CONFLICT_CODE_DATA_CHANGED,
            CardController.CONFLICT_CODE_LOCK_NOT_ACQUIRED,
            CardController.CONFLICT_CODE_WRITE_NOT_APPLIED);

    /**
     * Every response schema declares exactly the members its record serialises.
     *
     * <p>Purpose: this is the case that would have caught the administrative-detail defect on the day it
     * was introduced. The document composed seven core members plus a disclosed number and sealed the
     * result, so it required eight; the record declared seven; and the controller test asserted the eighth
     * ABSENT, so the one assertion that could have caught the omission was the one encoding it.
     *
     * <p>Assumptions: the document side is resolved through composition -- a schema's own properties plus
     * the properties of every branch it composes, recursively -- because that is how a consumer's validator
     * reads it. Comparing only a schema's own {@code properties} would have found the administrative detail
     * to declare one member and agreed with nothing.
     *
     * <p>Assumptions: the Java side is the record's components, with a component annotated
     * {@link JsonUnwrapped} replaced by the components of ITS type, recursively. That annotation is what
     * flattens the shared problem shape into the conflict body, so reading components naively would find
     * that body to have two members where the wire has twelve. The rule is written against the annotation
     * rather than against the one shape that uses it, so a second unwrapped member is covered by it.
     *
     * <p>Measured: renaming the disclosed member of the administrative schema fails this case alone,
     * printing the documented member set beside the serialised one so the disagreement is readable rather
     * than reported as a bare count. Adding a {@code cvv} property to the shared core also fails this case
     * alone, which is the important one of the two: a member added to a base reaches every shape that
     * composes it, and there is otherwise nothing in either build that would notice.</p>
     */
    @Test
    @DisplayName("every response schema declares exactly the members its record serialises")
    void everyResponseSchemaDeclaresExactlyTheMembersItsRecordSerialises() {
        Map<String, Object> contract = contract();
        Map<String, Set<String>> documented = new LinkedHashMap<>();
        Map<String, Set<String>> serialised = new LinkedHashMap<>();

        for (Map.Entry<String, Class<?>> owned : new TreeSet<>(SCHEMA_OWNERS.keySet()).stream()
                .collect(LinkedHashMap<String, Class<?>>::new,
                        (map, name) -> map.put(name, SCHEMA_OWNERS.get(name)), Map::putAll)
                .entrySet()) {
            Object schema = schemas(contract).get(owned.getKey());
            assertThat(schema)
                    .as("the document must still declare %s, which a response is built from",
                            owned.getKey())
                    .isNotNull();
            documented.put(owned.getKey(), new TreeSet<>(resolvedMembers(contract, schema)));
            serialised.put(owned.getKey(), new TreeSet<>(jsonMembers(owned.getValue())));
        }

        assertThat(documented)
                .as("a schema and the record that serialises into it are one shape declared twice")
                .isEqualTo(serialised);
    }

    /**
     * Every object schema refuses members it does not declare, or is composed only by schemas that do.
     *
     * <p>Purpose: five of this document's seven object schemas refused unknown members and two did not, so
     * a body could carry a member no schema admitted and a strict consumer and a lenient one would disagree
     * about whether it was valid. The two open ones were the shared problem shape and the abend detail --
     * the two shapes a client is most likely to parse defensively.
     *
     * <p>Assumptions: the invariant is stated as a RULE rather than as a list of names with the two
     * composition bases exempted. An object schema passes if it refuses unknown members itself, or if every
     * reference to it is an {@code allOf} branch of a schema that does. That is the actual property the
     * document relies on -- an open base composed only by sealed users cannot be reached by a body -- and it
     * needs no naming convention, so a base introduced later is covered without anyone remembering to add
     * it to an exemption list.
     *
     * <p>Assumptions: closure is recognised as either {@code additionalProperties: false} or
     * {@code unevaluatedProperties: false}, and the two are not interchangeable. A composed schema must use
     * the second: under the 2020-12 dialect each {@code allOf} branch is validated independently, so
     * {@code additionalProperties: false} on a composing schema refuses the members its own branches
     * declare and rejects every valid body. That is why the error shapes were closed by splitting an open
     * core out rather than by sealing the shape a conflict body composes.
     *
     * <p>Measured: deleting {@code unevaluatedProperties} from {@code ApiError} fails this case alone,
     * naming that schema. Sealing {@code ApiErrorCore} with {@code additionalProperties} instead, and
     * leaving the composing shapes as they are, leaves THIS case passing -- everything is then sealed
     * somewhere -- and fails the example case with all THREE conflict examples reporting the {@code card}
     * member as forbidden by the branch that seals itself. That is the failure mode the open-core split
     * exists to avoid, and it is why the composing shapes use the later keyword rather than the earlier
     * one.</p>
     */
    @Test
    @DisplayName("every object schema refuses unknown members, or is composed only by schemas that do")
    void everyObjectSchemaRefusesUnknownMembers() {
        Map<String, Object> contract = contract();
        Set<String> composedOnlyBySealedSchemas = basesComposedOnlyBySealedSchemas(contract);
        List<String> open = new ArrayList<>();

        for (Map.Entry<String, Object> schema : schemas(contract).entrySet()) {
            Map<String, Object> object = asMap(schema.getValue());
            boolean isObject = object.containsKey("properties") || object.containsKey("allOf");
            if (!isObject || isSealed(object) || composedOnlyBySealedSchemas.contains(schema.getKey())) {
                continue;
            }
            open.add(schema.getKey());
        }

        assertThat(open)
                .as("an object schema a body is validated against must say what it does not admit")
                .isEmpty();
    }

    /**
     * The conflict examples publish exactly the subordinate codes the handlers emit, and admit their width.
     *
     * <p>Purpose: the conflict response says three conditions reach this status, that they are never merged,
     * and that each carries its own subordinate code; its examples name the three values. Every body this
     * context sent carried the empty code, because the shared renderer emits none and the controller copied
     * it -- so this case holds the document and the emitters to each other in both directions: no code is
     * published that no handler emits, and no code is emitted that the document does not publish.
     *
     * <p>Assumptions: the declared width of the member is asserted to ADMIT the longest published code, and
     * this is not a redundant check on a number. The five other contracts bound this member at thirteen
     * characters, which is the width their own single code needs; two of the three codes here are longer
     * than that, so a width copied from a sibling contract would forbid the very values this schema names as
     * its discriminator -- and a body carrying one would be invalid against the document that requires it.
     *
     * <p>Measured: lowering the declared width to thirteen -- the width the five sibling contracts use --
     * fails this case alone, reporting that the document admits fewer characters than the longest code it
     * publishes. Renaming one published code also fails this case alone, on the set comparison rather than
     * the width, so the two halves of the case are separately load-bearing.</p>
     */
    @Test
    @DisplayName("the conflict examples publish exactly the subordinate codes the handlers emit")
    void theConflictExamplesPublishExactlyTheEmittedSubordinateCodes() {
        Map<String, Object> contract = contract();
        Set<String> published = new TreeSet<>();
        collectSubordinateCodes(contract, published);

        assertThat(published)
                .as("the document's discriminator values and the emitted ones are one set")
                .isEqualTo(new TreeSet<>(EMITTED_CONFLICT_CODES));

        Map<String, Object> member = asMap(asMap(
                resolve(contract, schemas(contract).get("ApiErrorCore")).get("properties"))
                .get("secondaryCode"));
        int declaredWidth = ((Number) member.get("maxLength")).intValue();
        int longestPublished = published.stream().mapToInt(String::length).max().orElseThrow();

        assertThat(declaredWidth)
                .as("a discriminator the document names must fit the member the document declares")
                .isGreaterThanOrEqualTo(longestPublished);
    }

    /**
     * Every published example satisfies the schema it illustrates.
     *
     * <p>Purpose: an example is what a consumer copies, and one conflict example omitted the selector its
     * own schema requires -- the member a retry needs most, because a retry addresses the card by it. An
     * example that does not validate is worse than none: it teaches a shape the service refuses.
     *
     * <p>Assumptions: only closed object schemas are checked for undeclared members, and required members
     * are checked wherever a required list is resolved. A schema that is not an object, or an example that
     * is not one, is skipped rather than reported, because neither can carry this class of defect and
     * reporting them would bury the ones that can.
     *
     * <p>Measured: removing the selector from the stale-record example fails this case alone and names the
     * JSON path of the offending example -- but only once the union resolver below was added. Without it the
     * same removal left all four cases PASSING, because the member is declared as a union of the detail
     * shape and the null type and the walk read no members from a union. The defect this case exists for was
     * therefore invisible to the first draft of the case, which is recorded on that resolver.</p>
     */
    @Test
    @DisplayName("every example conforms to the schema it illustrates")
    void everyExampleConformsToTheSchemaItIllustrates() {
        Map<String, Object> contract = contract();
        List<String> violations = new ArrayList<>();
        checkExamples(contract, contract, "", violations);

        assertThat(violations)
                .as("an example is what a consumer copies, so it must satisfy the schema beside it")
                .isEmpty();
    }

    /**
     * Collects the schemas that are open but reachable only as a branch of a sealed schema.
     *
     * <p>Assumptions: a reference from anywhere other than an {@code allOf} branch disqualifies a base,
     * because a response, a parameter or a property pointing straight at an open schema means a body is
     * validated against it directly and its openness is then reachable.</p>
     *
     * @param contract the whole parsed document
     * @return the names of schemas whose every reference is an {@code allOf} branch of a sealed schema
     */
    private static Set<String> basesComposedOnlyBySealedSchemas(Map<String, Object> contract) {
        Map<String, Boolean> onlySealedComposers = new LinkedHashMap<>();
        for (Map.Entry<String, Object> schema : schemas(contract).entrySet()) {
            boolean sealedComposer = isSealed(asMap(schema.getValue()));
            for (String branch : composedBranchNames(asMap(schema.getValue()))) {
                onlySealedComposers.merge(branch, sealedComposer, Boolean::logicalAnd);
            }
        }

        Set<String> referencedElsewhere = new LinkedHashSet<>();
        collectReferencesOutsideComposition(contract, referencedElsewhere);
        onlySealedComposers.keySet().removeAll(referencedElsewhere);
        return onlySealedComposers.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    /**
     * Names the component schemas a schema composes through {@code allOf}.
     *
     * @param schema the parsed schema
     * @return the referenced component names; never {@code null}
     */
    private static Set<String> composedBranchNames(Map<String, Object> schema) {
        Set<String> names = new LinkedHashSet<>();
        if (schema.get("allOf") instanceof List<?> branches) {
            for (Object branch : branches) {
                Object pointer = asMap(branch).get("$ref");
                if (pointer instanceof String reference) {
                    names.add(reference.substring(reference.lastIndexOf('/') + 1));
                }
            }
        }
        return names;
    }

    /**
     * Collects every component-schema reference that is NOT an {@code allOf} branch.
     *
     * @param node the node being visited
     * @param found the collector every referenced component name is added to
     */
    private static void collectReferencesOutsideComposition(Object node, Set<String> found) {
        if (node instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                if ("allOf".equals(entry.getKey())) {
                    continue;
                }
                if ("$ref".equals(entry.getKey()) && entry.getValue() instanceof String pointer
                        && pointer.startsWith("#/components/schemas/")) {
                    found.add(pointer.substring(pointer.lastIndexOf('/') + 1));
                }
                collectReferencesOutsideComposition(entry.getValue(), found);
            }
        } else if (node instanceof List<?> list) {
            list.forEach(element -> collectReferencesOutsideComposition(element, found));
        }
    }

    /**
     * Collects every subordinate-code value any example in the document publishes.
     *
     * <p>Assumptions: the empty value is not collected. Six of the seven published contracts show an empty
     * subordinate code for their single conflict condition, and this document's non-conflict examples do the
     * same, so collecting it would compare a set of discriminators against a set containing a non-value.</p>
     *
     * @param node the node being visited
     * @param found the collector every non-empty value is added to
     */
    private static void collectSubordinateCodes(Object node, Set<String> found) {
        if (node instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                if ("secondaryCode".equals(entry.getKey()) && entry.getValue() instanceof String value
                        && !value.isEmpty()) {
                    found.add(value);
                }
                collectSubordinateCodes(entry.getValue(), found);
            }
        } else if (node instanceof List<?> list) {
            list.forEach(element -> collectSubordinateCodes(element, found));
        }
    }

    /**
     * Resolves the members a schema declares, following composition.
     *
     * @param contract the whole parsed document
     * @param node the schema node, which may itself be a reference
     * @return every property name a body validated against it may carry; never {@code null}
     */
    private static Set<String> resolvedMembers(Map<String, Object> contract, Object node) {
        Map<String, Object> schema = resolve(contract, node);
        Set<String> members = new LinkedHashSet<>(asMap(schema.get("properties")).keySet());
        if (schema.get("allOf") instanceof List<?> branches) {
            for (Object branch : branches) {
                members.addAll(resolvedMembers(contract, branch));
            }
        }
        return members;
    }

    /**
     * Reports whether a schema refuses members it does not declare.
     *
     * @param schema the parsed schema
     * @return {@code true} when it declares either closure keyword as false
     */
    private static boolean isSealed(Map<String, Object> schema) {
        return Boolean.FALSE.equals(schema.get("additionalProperties"))
                || Boolean.FALSE.equals(schema.get("unevaluatedProperties"));
    }

    /**
     * Names the JSON members a record serialises, flattening any unwrapped component.
     *
     * @param type the record type a response is built from
     * @return the member names its serialisation carries; never {@code null}
     * @throws IllegalArgumentException if the type is not a record, because the pairing above would then
     *     be naming something that cannot describe a JSON object shape
     */
    private static Set<String> jsonMembers(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            throw new IllegalArgumentException(type.getName() + " is not a record");
        }
        Set<String> members = new LinkedHashSet<>();
        for (RecordComponent component : components) {
            if (isUnwrapped(type, component)) {
                members.addAll(jsonMembers(component.getType()));
            } else {
                members.add(component.getName());
            }
        }
        return members;
    }

    /**
     * Reports whether a record component is serialised flattened into its owner.
     *
     * <p>Measured: the annotation is looked for in three places because the record component itself is NOT
     * one of them. Its declared targets are the field, the method, the parameter and another annotation --
     * not {@code RECORD_COMPONENT} -- so the compiler propagates it to the backing field, the accessor and
     * the constructor parameter and {@code RecordComponent.isAnnotationPresent} answers false. The first
     * draft of this class asked only the component, found the conflict body to have two members where the
     * wire carries twelve, and failed with exactly that difference; the reflection is written against all
     * three declarations so a future Jackson release adding the record target changes nothing here.</p>
     *
     * @param owner the record type declaring the component, needed to reach the backing field
     * @param component the component being classified
     * @return {@code true} when the component's type is flattened into the owner's JSON object
     */
    private static boolean isUnwrapped(Class<?> owner, RecordComponent component) {
        if (component.isAnnotationPresent(JsonUnwrapped.class)
                || component.getAccessor().isAnnotationPresent(JsonUnwrapped.class)) {
            return true;
        }
        try {
            return owner.getDeclaredField(component.getName())
                    .isAnnotationPresent(JsonUnwrapped.class);
        } catch (NoSuchFieldException absent) {
            // WHY : Assumptions: a record always has a field per component, so this is unreachable for a
            //       record and answering false is the correct total answer rather than a swallowed failure.
            return false;
        }
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
                validateExample(contract, schema, mapping.get("example"), path + "/example", violations);
            }
            if (schema != null && mapping.get("examples") instanceof Map<?, ?> examples) {
                for (Map.Entry<?, ?> example : examples.entrySet()) {
                    validateExample(contract, schema, asMap(example.getValue()).get("value"),
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
     * Checks one example object against one schema, for member names and required members.
     *
     * @param contract the whole parsed document, needed to resolve a nested reference
     * @param schemaNode the schema node the example illustrates, which may be a reference
     * @param example the example value
     * @param path the location of the example, for a violation to name
     * @param violations the collector every violation is added to
     */
    private static void validateExample(Map<String, Object> contract, Object schemaNode,
            Object example, String path, List<String> violations) {
        if (!(example instanceof Map<?, ?> object)) {
            return;
        }
        Map<String, Object> schema = objectShapeOf(contract, resolve(contract, schemaNode));
        Set<String> members = resolvedMembers(contract, schema);
        if (members.isEmpty()) {
            return;
        }
        if (isSealedThroughComposition(contract, schema)) {
            for (Object member : object.keySet()) {
                if (!members.contains(String.valueOf(member))) {
                    violations.add(path + ": undeclared member '" + member + "'");
                }
            }
        }
        for (Object member : resolvedRequired(contract, schema)) {
            if (!object.containsKey(member)) {
                violations.add(path + ": missing required member '" + member + "'");
            }
        }
        checkComposedBranchClosure(contract, schema, object, path, violations);
        Map<String, Object> properties = resolvedProperties(contract, schema);
        for (Map.Entry<?, ?> entry : object.entrySet()) {
            Object nested = properties.get(String.valueOf(entry.getKey()));
            if (nested == null) {
                continue;
            }
            Map<String, Object> nestedSchema = resolve(contract, nested);
            if (entry.getValue() instanceof List<?> items && nestedSchema.get("items") != null) {
                for (int index = 0; index < items.size(); index++) {
                    validateExample(contract, nestedSchema.get("items"), items.get(index),
                            path + "/" + entry.getKey() + "/" + index, violations);
                }
            } else {
                validateExample(contract, nested, entry.getValue(), path + "/" + entry.getKey(),
                        violations);
            }
        }
    }

    /**
     * Narrows a schema node to the object shape an example of it is validated against.
     *
     * <p>Measured: this exists because without it the case above could not see the defect it was written
     * for. The conflict body declares its card member as a union of the detail shape and the null type,
     * which is how every optional object in these contracts is declared; a union carries no
     * {@code properties} of its own, so the walk found no members and returned before checking anything
     * nested inside it. Deleting the required selector from the stale-record example -- the exact defect --
     * left all four cases PASSING. With this resolver the same deletion fails the example case and names
     * the path. The hole was found by performing that deletion rather than by reading the code, which is
     * why the mutation is recorded here.
     *
     * <p>Assumptions: a union is narrowed only when exactly ONE of its branches is an object. With two or
     * more, deciding which branch an example is meant to satisfy needs full validation semantics -- a
     * consumer accepts the example if ANY branch admits it -- so the node is left unnarrowed and the example
     * goes unchecked rather than being reported against a branch it was never written for. No schema in this
     * document has two object branches, and if one is added its examples are simply not covered here, which
     * is the honest outcome for a shape this gate cannot decide.
     *
     * @param contract the whole parsed document
     * @param schema the resolved schema node
     * @return the object shape to validate against, or {@code schema} unchanged when there is none
     */
    private static Map<String, Object> objectShapeOf(Map<String, Object> contract,
            Map<String, Object> schema) {
        if (!resolvedMembers(contract, schema).isEmpty()) {
            return schema;
        }
        List<Map<String, Object>> objectBranches = new ArrayList<>();
        for (String keyword : List.of("oneOf", "anyOf")) {
            if (!(schema.get(keyword) instanceof List<?> branches)) {
                continue;
            }
            for (Object branch : branches) {
                Map<String, Object> resolved = resolve(contract, branch);
                if (!resolvedMembers(contract, resolved).isEmpty()) {
                    objectBranches.add(resolved);
                }
            }
        }
        return objectBranches.size() == 1 ? objectBranches.get(0) : schema;
    }

    /**
     * Checks an example against each composed branch INDEPENDENTLY, as a validator does.
     *
     * <p>Purpose: under the 2020-12 dialect every {@code allOf} branch is applied to the whole instance on
     * its own, so a branch declaring {@code additionalProperties: false} forbids every member the OTHER
     * branches contribute. That is the trap the error shapes of this document were closed around: sealing
     * the shared core and composing it would have made every conflict body invalid, because the core knows
     * nothing of the card member beside it. The shapes were therefore closed with
     * {@code unevaluatedProperties} on the composing schema, which is evaluated after the branches and so
     * admits what they matched.
     *
     * <p>Measured: this check exists because its absence made a claim on the closure case false. That case
     * was documented as failing the example case if the core were sealed instead of its users; performing
     * that mutation -- adding {@code additionalProperties: false} to the shared error core and leaving the
     * composing shapes as they are -- left ALL FOUR cases passing, because the walk read the union of the
     * branches' members and found the card member declared. With this check the same mutation fails the
     * example case, reporting the card member as forbidden by the branch that seals itself. The claim was
     * found false by attempting to prove it, and the gate was corrected rather than the sentence.
     *
     * @param contract the whole parsed document
     * @param schema the resolved composing schema
     * @param example the example object being validated
     * @param path the location of the example, for a violation to name
     * @param violations the collector every violation is added to
     */
    private static void checkComposedBranchClosure(Map<String, Object> contract,
            Map<String, Object> schema, Map<?, ?> example, String path, List<String> violations) {
        if (!(schema.get("allOf") instanceof List<?> branches)) {
            return;
        }
        for (Object branch : branches) {
            Map<String, Object> resolved = resolve(contract, branch);
            if (!Boolean.FALSE.equals(resolved.get("additionalProperties"))) {
                continue;
            }
            Set<String> admitted = resolvedMembers(contract, resolved);
            String name = asMap(branch).get("$ref") instanceof String pointer
                    ? pointer.substring(pointer.lastIndexOf('/') + 1)
                    : "an inline branch";
            for (Object member : example.keySet()) {
                if (!admitted.contains(String.valueOf(member))) {
                    violations.add(path + ": member '" + member + "' is forbidden by composed branch '"
                            + name + "', which seals itself and so refuses what its siblings declare");
                }
            }
        }
    }

    /**
     * Reports whether a schema, or any schema it composes, refuses unknown members.
     *
     * <p>Assumptions: closure declared on a composing schema is honoured for the members its branches
     * contribute, which is what {@code unevaluatedProperties} means, so a branch's own openness does not
     * reopen the composed shape.</p>
     *
     * @param contract the whole parsed document
     * @param schema the resolved schema
     * @return {@code true} when a body validated against it may carry no undeclared member
     */
    private static boolean isSealedThroughComposition(Map<String, Object> contract,
            Map<String, Object> schema) {
        if (isSealed(schema)) {
            return true;
        }
        if (schema.get("allOf") instanceof List<?> branches) {
            return branches.stream()
                    .anyMatch(branch -> isSealedThroughComposition(contract, resolve(contract, branch)));
        }
        return false;
    }

    /**
     * Resolves the properties a schema declares, following composition.
     *
     * @param contract the whole parsed document
     * @param schema the resolved schema
     * @return every property node, keyed by member name; never {@code null}
     */
    private static Map<String, Object> resolvedProperties(Map<String, Object> contract,
            Map<String, Object> schema) {
        Map<String, Object> properties = new LinkedHashMap<>(asMap(schema.get("properties")));
        if (schema.get("allOf") instanceof List<?> branches) {
            for (Object branch : branches) {
                properties.putAll(resolvedProperties(contract, resolve(contract, branch)));
            }
        }
        return properties;
    }

    /**
     * Resolves the required members a schema declares, following composition.
     *
     * @param contract the whole parsed document
     * @param schema the resolved schema
     * @return every required member name; never {@code null}
     */
    private static Set<String> resolvedRequired(Map<String, Object> contract,
            Map<String, Object> schema) {
        Set<String> required = new LinkedHashSet<>();
        if (schema.get("required") instanceof List<?> declared) {
            declared.forEach(member -> required.add(String.valueOf(member)));
        }
        if (schema.get("allOf") instanceof List<?> branches) {
            for (Object branch : branches) {
                required.addAll(resolvedRequired(contract, resolve(contract, branch)));
            }
        }
        return required;
    }

    /**
     * Resolves a node that may be a local reference into the object it names.
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
     * @throws IllegalStateException when the resource is absent or empty, which means the module ships no
     *     contract and every assertion here would otherwise pass over nothing
     */
    private static Map<String, Object> contract() {
        try (InputStream stream = CardApiContractGateTest.class
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
