package com.carddemo.common.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds all seven published contracts to the protocol outcomes the shared runtime actually produces.
 *
 * <p>Purpose: four refusals in this system are produced centrally and were published locally. The shared
 * advice answers a wrong method with 405, an unacceptable {@code Accept} with 406 and an unacceptable
 * request body type with 415 for every route of every service, and the shared body-size filter answers an
 * oversized body with 413 ahead of the security chain; but a contract only carries those statuses where its
 * author wrote them. Measured before this class was accepted, 405 was published on 10 operations of 61, 406
 * on 5, 415 on 5 of the 31 that accept a body, and 413 on none at all -- so a generated client could not
 * anticipate a status it would certainly receive, and a contract test could not tell an unpublished status
 * from an impossible one.
 *
 * <p>Assumptions: the rules are asserted per OPERATION and derived from the operation itself rather than
 * from a list held here. A body-accepting operation is one declaring a {@code requestBody}; a body-returning
 * one is one whose success response carries content. That is what lets the class cover an operation added
 * later without being edited, and it is why the four no-content operations in this system are not required
 * to publish 406: with nothing to negotiate, the runtime raises no negotiation failure for them.
 *
 * <p>Assumptions: each refusal must be published as a {@code $ref} to the document's own canonical response
 * component, not as an inline response. Alternatives Considered: accepting either form, which would let one
 * document carry six restatements of the same rationale and let two of them drift apart -- exactly the
 * failure mode the components exist to prevent. The component NAMES are fixed here, so a document cannot
 * satisfy the rule by inventing a synonym.
 *
 * <p>Assumptions: correlation is asserted on both halves of every operation -- the request parameter and
 * every response -- because the identifier is set by a shared filter on the way in and echoed on the way
 * out, so a response that omits it in the document describes a header the runtime sends anyway. A response
 * is published either INLINE or as a {@code $ref} to a component, so the rule is split across two cases,
 * one for each form, and between them they read every published response.
 *
 * <p>⚠️ Measured: an earlier version of this class asserted the inline form only and stated that the
 * {@code $ref} form was covered where the components are read. It was not covered anywhere, and the gap was
 * not hypothetical: six of reporting's shared refusal components carried no header at all, and because each
 * is referenced many times that was 38 of that document's published responses passing a case whose name said
 * every response. The component case below exists because of it, and a gate that exempts a form has to be
 * read as covering nothing about that form.
 *
 * <p>Assumptions: no container, network endpoint or external service is used. The documents are read from
 * the FILESYSTEM rather than the classpath, because a Maven module's test classpath does not carry a sibling
 * module's resources and this class must see all seven.
 *
 * <p>⚠️ Trade-offs: this class asserts that a status is PUBLISHED and cannot assert that the runtime
 * produces it. The runtime half is asserted by {@code ProtocolRefusalRenderingTest} and
 * {@code RequestBodySizeFilterTest} in this module, and the two halves together are what make the published
 * document a description of behaviour rather than an aspiration. Stating the boundary here keeps a reader
 * from mistaking a green run for evidence of the runtime side.
 */
class PublishedContractClosureTest {

    /** The reactor aggregator, used as the marker that locates the repository root from any module. */
    private static final String ROOT_MARKER = "services/pom.xml";

    /** The directory holding one subdirectory per Maven module. */
    private static final String SERVICES_DIRECTORY = "services";

    /** Where a module keeps its published contract, relative to the module directory. */
    private static final String CONTRACT_DIRECTORY = "src/main/resources/openapi";

    /** The HTTP methods an OpenAPI path item may carry as operations. */
    private static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    /**
     * The number of contracts the reactor publishes.
     *
     * <p>Assumptions: a FLOOR rather than an equality, so an eighth contract is covered by these cases
     * rather than failing them for existing. What it catches is a walk that resolved the wrong directory,
     * which would find none and otherwise pass with nothing examined.</p>
     */
    private static final int EXPECTED_CONTRACTS = 7;

    /** The canonical component every 405 must name. */
    private static final String METHOD_NOT_ALLOWED = "MethodNotAllowed";

    /** The canonical component every 406 must name. */
    private static final String NOT_ACCEPTABLE = "NotAcceptable";

    /** The canonical component every 415 must name. */
    private static final String UNSUPPORTED_MEDIA_TYPE = "UnsupportedMediaType";

    /** The canonical component every 413 must name. */
    private static final String PAYLOAD_TOO_LARGE = "PayloadTooLarge";

    /** The shared parameter component carrying the inbound correlation header. */
    private static final String CORRELATION_PARAMETER = "CorrelationIdHeader";

    /** The one schema the request parameter, the response header and the problem member all name. */
    private static final String CORRELATION_SCHEMA = "CorrelationId";

    /** The response header name every operation echoes the correlation identifier under. */
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    /**
     * The members a keyset page envelope carries, which is how one is recognised here.
     *
     * <p>Assumptions: all four are required, so a schema is treated as an envelope only when it carries rows
     * AND the cursor pair AND the forward indicator. That is what separates an envelope from any other array
     * member -- {@code ApiError.fieldErrors} is an array with no cursor beside it and is correctly ignored.</p>
     */
    private static final Set<String> KEYSET_ENVELOPE_MEMBERS =
            Set.of("items", "firstKey", "lastKey", "hasNext");

    /**
     * The prefix a schema reference carries, used to tell one from a reference to any other component.
     *
     * <p>Assumptions: matching the PREFIX rather than merely taking the text after the last slash keeps a
     * reference to a parameter, a header or a response component out of the schema walk. Those share the
     * {@code #/components/} root and would otherwise be pushed onto the work list as schema names that do
     * not exist.</p>
     */
    private static final String SCHEMA_REFERENCE_PREFIX = "#/components/schemas/";

    /**
     * The number of property-bearing schemas the response closure reaches across the seven contracts.
     *
     * <p>Assumptions: this is a measured FLOOR, not a target -- account 15, auth 9, authorization 10, card
     * 7, reference 17, reporting 13 and transaction 10. It exists so that a walk which resolves the wrong
     * directory, or a reachability bug that reaches nothing, fails loudly instead of passing by examining
     * an empty set. Adding a response schema raises the real number and leaves this floor satisfied.</p>
     */
    private static final int EXPECTED_RESPONSE_SCHEMAS = 81;

    /** The closed problem schema every contract publishes. */
    private static final String PROBLEM_SCHEMA = "ApiError";

    /**
     * The open core two contracts carry the problem members on.
     *
     * <p>Assumptions: a contract publishing conflict variants composes them from an OPEN core and closes
     * only the schema a response names, because an {@code allOf} branch validates independently and a
     * closed branch would refuse the members its sibling contributes. Where that core exists it is the
     * document's own statement of the facets, so it is read in preference to the closed schema.</p>
     */
    private static final String PROBLEM_CORE_SCHEMA = "ApiErrorCore";

    /** The per-field entry schema every contract publishes. */
    private static final String FIELD_ERROR_SCHEMA = "FieldError";

    /** Width of every machine code the shared writer emits: {@code CARDDEMO-} and four digits. */
    private static final int CODE_WIDTH = 13;

    /**
     * Width of the widest subordinate code the shared writer can carry.
     *
     * <p>Assumptions: measured on {@code CARD-LOCK-NOT-ACQUIRED} and {@code CARD-WRITE-NOT-APPLIED}, the
     * only subordinate codes any service emits. Six services emit the empty string, so this is an upper
     * bound for them rather than a description of their output -- and it is published by all seven because
     * one generated type reads the document of any of them.</p>
     */
    private static final int SECONDARY_CODE_WIDTH = 22;

    /** Lowest status the shared writer puts in a problem document. */
    private static final int LOWEST_PROBLEM_STATUS = 400;

    /** Highest status the shared writer puts in a problem document. */
    private static final int HIGHEST_PROBLEM_STATUS = 599;

    /** Width of the timestamp the reference declares as {@code PIC X(26)}. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Width of the reference message field both the aggregate and per-field sentences are held to. */
    private static final int MESSAGE_WIDTH = 75;

    /**
     * Every operation of every contract publishes the method-refusal status the shared advice produces.
     *
     * <p>Assumptions: 405 is required on EVERY operation without exception, because the advice handling it
     * is registered for every route of every service and the container resolves a method mismatch before any
     * handler is chosen. There is no operation for which the status is impossible.
     *
     * <p>Measured: removing the entry from {@code signOn} fails this case with {@code auth-api.yaml signOn
     * does not publish 405}, and no other case of this class and no test of any service notices.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("every operation of every contract publishes 405 through the shared component")
    void everyOperationPublishesMethodNotAllowed() {
        List<String> gaps = new ArrayList<>();
        forEachOperation((contract, operation, method, spec) ->
                requirePublished(contract, operation, spec, "405", METHOD_NOT_ALLOWED, gaps));

        assertThat(gaps)
                .as("a status the runtime certainly produces must be published where a client can read it")
                .isEmpty();
    }

    /**
     * Every body-returning operation publishes the negotiation refusal, and every body-accepting one
     * publishes the two refusals that only a body can provoke.
     *
     * <p>Assumptions: the three statuses are asserted in ONE case because they are three readings of one
     * rule -- publish the refusal wherever the runtime can raise it -- and separating them would report
     * three failures for one omission on an operation that accepts and returns a body.
     *
     * <p>Assumptions: 413 and 415 are required only where a body is accepted, and 406 only where one is
     * returned. A GET carrying no body cannot exceed a body ceiling or carry an unacceptable content type,
     * and a 204 operation negotiates nothing, so requiring them everywhere would publish outcomes that
     * cannot occur -- which is the same defect as omitting one that can, read from the other side.
     *
     * <p>Measured: removing the 413 entry from {@code createTransactionType} fails this case with
     * {@code reference-api.yaml createTransactionType does not publish 413}. The four no-content operations
     * in this system pass with no 406, which is what shows the rule is keyed to what the operation carries
     * rather than applied to every operation alike.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("body-accepting operations publish 413 and 415, body-returning operations publish 406")
    void bodyOperationsPublishTheirRefusals() {
        List<String> gaps = new ArrayList<>();
        forEachOperation((contract, operation, method, spec) -> {
            if (spec.containsKey("requestBody")) {
                requirePublished(contract, operation, spec, "413", PAYLOAD_TOO_LARGE, gaps);
                requirePublished(contract, operation, spec, "415", UNSUPPORTED_MEDIA_TYPE, gaps);
            }
            if (returnsBody(spec)) {
                requirePublished(contract, operation, spec, "406", NOT_ACCEPTABLE, gaps);
            }
        });

        assertThat(gaps)
                .as("a refusal the runtime raises for a body must be published on the operations that"
                        + " accept or return one")
                .isEmpty();
    }

    /**
     * Every operation names the shared correlation parameter and every inline response declares its header.
     *
     * <p>Assumptions: the parameter may be declared on the operation or on the path item that holds it,
     * because both forms apply to the operation and this system uses the first; accepting either keeps the
     * rule about the effective contract rather than about where an author wrote it.
     *
     * <p>Assumptions: a response declared as a {@code $ref} is skipped HERE and read by the sibling case
     * below, which reads the component itself. Splitting the rule that way is what lets a finding name the
     * one place a header has to be added rather than naming the sixty references that would inherit it.
     *
     * <p>Measured: deleting the header block from the add-transaction preview response fails this case with
     * {@code transaction-api.yaml addTransaction response 200 does not declare X-Correlation-Id} -- which is
     * the exact omission a review found in that document and the reason this rule reads every response
     * rather than every operation.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("every operation and every inline response carries the correlation contract")
    void everyOperationCarriesTheCorrelationContract() {
        List<String> gaps = new ArrayList<>();
        forEachOperation((contract, operation, method, spec) -> {
            if (!namesCorrelationParameter(spec)) {
                gaps.add(contract + " " + operation + " does not name " + CORRELATION_PARAMETER);
            }
            for (Map.Entry<String, Object> response : responses(spec).entrySet()) {
                Map<String, Object> body = asMap(response.getValue());
                if (body.containsKey("$ref")) {
                    continue;
                }
                if (!asMap(body.get("headers")).containsKey(CORRELATION_HEADER)) {
                    gaps.add(contract + " " + operation + " response " + response.getKey()
                            + " does not declare " + CORRELATION_HEADER);
                }
            }
        });

        assertThat(gaps)
                .as("the identifier is set by a shared filter on the way in and echoed on the way out, so a"
                        + " document that omits it describes a header the runtime sends anyway")
                .isEmpty();
    }

    /**
     * Every shared response component declares the correlation header its references inherit.
     *
     * <p>Purpose: this is the other half of the correlation rule. Most responses in these documents are
     * {@code $ref}s -- 438 of the 555 published responses are -- so a component that omits the header omits
     * it from every reference at once, and the sibling case above cannot see it because it reads the
     * reference rather than what the reference names.
     *
     * <p>Assumptions: EVERY component is required to declare it, including the ones no operation currently
     * references, because a published component is a published response and the filter sets the header on
     * whatever the container writes. The map is required to be non-empty per contract as well, so a walk
     * that resolved a document without components cannot pass having examined nothing.
     *
     * <p>Measured: before this case, reporting's {@code BadRequest}, {@code Unauthorized}, {@code Forbidden},
     * {@code NotFound}, {@code ServiceUnavailable} and {@code InternalError} carried no {@code headers} block
     * while the four protocol refusals beside them did -- 38 published responses documenting a header the
     * runtime always sends. Removing the block from any one of them now fails this case with, for example,
     * {@code reporting-api.yaml response component BadRequest does not declare X-Correlation-Id}.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("every shared response component carries the correlation header")
    void everySharedResponseComponentCarriesTheCorrelationHeader() {
        List<String> gaps = new ArrayList<>();
        List<Path> contracts = contracts();

        assertThat(contracts)
                .as("the walk must resolve the contract tree; an empty list would examine nothing")
                .hasSizeGreaterThanOrEqualTo(EXPECTED_CONTRACTS);

        for (Path contract : contracts) {
            Map<String, Object> document = load(contract);
            String name = String.valueOf(contract.getFileName());
            Map<String, Object> components = asMap(asMap(document.get("components")).get("responses"));

            assertThat(components)
                    .as("%s must publish its refusals as shared components; an empty map would examine"
                            + " nothing", name)
                    .isNotEmpty();

            components.forEach((component, definition) -> {
                if (!asMap(asMap(definition).get("headers")).containsKey(CORRELATION_HEADER)) {
                    gaps.add(name + " response component " + component + " does not declare "
                            + CORRELATION_HEADER);
                }
            });
        }

        assertThat(gaps)
                .as("a shared component is referenced many times, so one that omits the header omits it from"
                        + " every response that names it")
                .isEmpty();
    }

    /**
     * The correlation schema admits an empty body member and the request parameter does not.
     *
     * <p>Purpose: one schema is named by three things -- the request parameter, the response header and the
     * {@code correlationId} member of every problem document -- and the three do not accept the same values.
     * {@code ApiError} normalises an absent identity to the EMPTY STRING and always carries the member, so
     * the schema has to admit it; {@code CorrelationIdFilter.conformsWithin} declines an empty value and the
     * filter answers 400, so the request parameter must not. Publishing one unnarrowed schema for both
     * granted callers a permission the runtime refuses.
     *
     * <p>Assumptions: the empty half is measured by MATCHING the published pattern against the empty string
     * with the platform's own regex engine rather than by searching the pattern text for an alternative. A
     * text search would pass on a pattern that mentions {@code ^$} inside a character class and would fail on
     * an equivalent pattern spelled another way, so it would be asserting the spelling rather than the
     * admitted language.
     *
     * <p>Assumptions: the parameter is narrowed by ONE facet composed over the shared schema rather than by a
     * second schema restating the alphabet and the width. Alternatives Considered: two independent schemas,
     * rejected because the alphabet would then be declared twice per document and fourteen times across the
     * seven, which is how two statements of one rule drift apart.
     *
     * <p>Measured: removing the parameter's bound from one document fails this case with
     * {@code account-api.yaml CorrelationIdHeader admits an empty value the filter answers 400 for}, and
     * narrowing the shared schema so it no longer admits the empty string fails with {@code account-api.yaml
     * CorrelationId refuses the empty string that ApiError emits for an absent identity}.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the correlation schema admits an empty body member and the request parameter does not")
    void theCorrelationSchemaSeparatesTheHeaderFromTheBodyMember() {
        List<String> divergences = new ArrayList<>();
        List<Path> contracts = contracts();

        assertThat(contracts)
                .as("the walk must resolve the contract tree; an empty list would examine nothing")
                .hasSizeGreaterThanOrEqualTo(EXPECTED_CONTRACTS);

        for (Path contract : contracts) {
            Map<String, Object> document = load(contract);
            String name = String.valueOf(contract.getFileName());
            Map<String, Object> components = asMap(document.get("components"));
            Map<String, Object> shared = asMap(asMap(components.get("schemas")).get(CORRELATION_SCHEMA));

            String expression = String.valueOf(shared.get("pattern"));
            if (!Pattern.matches(expression, "")) {
                divergences.add(name + " " + CORRELATION_SCHEMA + " refuses the empty string that ApiError"
                        + " emits for an absent identity");
            }

            Map<String, Object> parameter =
                    asMap(asMap(asMap(components.get("parameters")).get(CORRELATION_PARAMETER)).get("schema"));
            if (!requiresNonEmpty(parameter)) {
                divergences.add(name + " " + CORRELATION_PARAMETER + " admits an empty value the filter"
                        + " answers 400 for");
            }
        }

        assertThat(divergences)
                .as("the three things that name this schema do not accept the same values, so the parameter"
                        + " is narrowed where the body member cannot be")
                .isEmpty();
    }

    /**
     * Reports whether a parameter schema requires at least one character, directly or through composition.
     *
     * @param schema the parameter's schema node; may be empty
     * @return {@code true} when the schema or any branch it composes declares a positive {@code minLength}
     */
    private static boolean requiresNonEmpty(Map<String, Object> schema) {
        if (schema.get("minLength") instanceof Number bound && bound.intValue() >= 1) {
            return true;
        }
        if (!(schema.get("allOf") instanceof List<?> branches)) {
            return false;
        }
        return branches.stream().map(PublishedContractClosureTest::asMap)
                .anyMatch(PublishedContractClosureTest::requiresNonEmpty);
    }

    /**
     * Every page schema publishes the row ceiling its service enforces.
     *
     * <p>Purpose: a page size in this system is a server-owned constant a caller cannot vary, except for the
     * one operation that publishes a bounded size parameter. A page schema without a ceiling therefore
     * withholds a fact every response obeys, and a client sizing a rendering has to guess it.
     *
     * <p>Assumptions: an envelope is recognised by its SHAPE -- an {@code items} member beside the three
     * keyset members {@code firstKey}, {@code lastKey} and {@code hasNext} -- rather than by a name suffix or
     * from a list held here. Alternatives Considered: the {@code Page} suffix, which is what this case first
     * used. It was rejected on measurement: auth publishes its envelope as {@code PageResponse}, so the
     * suffix rule examined eleven envelopes and silently skipped a twelfth. A shape rule cannot be evaded by
     * naming, and it excludes {@code ApiError.fieldErrors} -- an array with no cursor beside it -- which a
     * bare "has an items array" rule would have demanded a ceiling of.
     *
     * <p>Measured: removing the reporting ceiling fails this case with {@code reporting-api.yaml
     * TransactionReportLinePage declares no maxItems}, and removing auth's fails with
     * {@code auth-api.yaml PageResponse declares no maxItems} -- the envelope the suffix rule could not see.
     * Eight of the twelve envelopes in this system were missing a ceiling when this case was written.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("every page schema publishes the row ceiling its service enforces")
    void everyPageSchemaPublishesItsCeiling() {
        List<String> gaps = new ArrayList<>();
        List<Path> contracts = contracts();

        assertThat(contracts)
                .as("the walk must resolve the contract tree; an empty list would examine nothing")
                .hasSizeGreaterThanOrEqualTo(EXPECTED_CONTRACTS);

        for (Path contract : contracts) {
            Map<String, Object> document = load(contract);
            Map<String, Object> schemas = asMap(asMap(document.get("components")).get("schemas"));
            schemas.forEach((name, value) -> {
                Map<String, Object> members = asMap(asMap(value).get("properties"));
                if (!members.keySet().containsAll(KEYSET_ENVELOPE_MEMBERS)) {
                    return;
                }
                Map<String, Object> rows = asMap(members.get("items"));
                if (!rows.containsKey("maxItems")) {
                    gaps.add(contract.getFileName() + " " + name + " declares no maxItems");
                }
            });
        }

        assertThat(gaps)
                .as("a page ceiling every response obeys must be published rather than left for a client"
                        + " to infer")
                .isEmpty();
    }

    /**
     * The error model of every contract carries one canonical facet set.
     *
     * <p>Purpose: a generated client reads a problem document from any of the seven services, so a facet that
     * differs between two documents is a TYPE that differs between them. Measured before this case was
     * written, {@code secondaryCode} was bounded at 13 in four contracts, 22 in two and unbounded in one;
     * {@code status} was unbounded in two; the timestamp was declared five different ways; and one contract
     * bounded two members its runtime does not bound at all.
     *
     * <p>Assumptions: each facet is resolved THROUGH one level of indirection, because two contracts carry
     * the properties on an open core the closed schema composes and two express the timestamp as an
     * {@code allOf} over a shared schema. Reading the property node alone would report those four as
     * missing facets they in fact declare more strictly, which is the difference between asserting the
     * effective contract and asserting where an author wrote it.
     *
     * <p>⚠️ Assumptions: {@code path} must carry NO length bound, and that is the one rule here stated as an
     * absence. The member holds the request target, whose length is limited by the container's header
     * ceiling rather than by anything this system declares -- so any bound a document invented could be
     * exceeded by a legitimate request, and the response would then violate the schema that describes it.
     * One contract published 2048 and it was withdrawn for that reason, not for uniformity.
     *
     * <p>Assumptions: the {@code allOf}-versus-bare-{@code $ref} split on {@code FieldError.state} is
     * deliberately NOT asserted. Under the 2020-12 dialect these documents declare, a sibling
     * {@code description} beside a {@code $ref} is valid and both forms validate identically, so the split
     * is a spelling difference with no consequence for a caller or a generated type -- and a rule against it
     * would be churn presented as closure.
     *
     * <p>Measured: three mutations, three distinct findings. Restoring {@code secondaryCode} to 13 in one
     * contract fails with {@code account-api.yaml ApiError.secondaryCode publishes maxLength 13 where the
     * canonical value is 22}. Removing the timestamp pattern from another fails with
     * {@code reference-api.yaml ApiError.timestamp declares no pattern, so its six microsecond digits are
     * prose rather than a contract}. Restoring the withdrawn path bound fails with
     * {@code reporting-api.yaml ApiError.path declares a length bound the container does not enforce, so a
     * legitimate request target could violate this schema} -- which is the absence rule discriminating,
     * and the reason it is stated as a rule at all.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the error model of every contract carries one canonical facet set")
    void theErrorModelCarriesOneCanonicalFacetSet() {
        List<String> divergences = new ArrayList<>();
        List<Path> contracts = contracts();

        assertThat(contracts)
                .as("the walk must resolve the contract tree; an empty list would examine nothing")
                .hasSizeGreaterThanOrEqualTo(EXPECTED_CONTRACTS);

        for (Path contract : contracts) {
            Map<String, Object> document = load(contract);
            Map<String, Object> schemas = asMap(asMap(document.get("components")).get("schemas"));
            String name = String.valueOf(contract.getFileName());

            Map<String, Object> problem = schemas.containsKey(PROBLEM_CORE_SCHEMA)
                    ? asMap(schemas.get(PROBLEM_CORE_SCHEMA))
                    : asMap(schemas.get(PROBLEM_SCHEMA));
            Map<String, Object> members = asMap(problem.get("properties"));

            requireFacet(name, "ApiError.code", resolved(members.get("code"), schemas),
                    "maxLength", CODE_WIDTH, divergences);
            requireFacet(name, "ApiError.secondaryCode", resolved(members.get("secondaryCode"), schemas),
                    "maxLength", SECONDARY_CODE_WIDTH, divergences);
            requireFacet(name, "ApiError.status", resolved(members.get("status"), schemas),
                    "minimum", LOWEST_PROBLEM_STATUS, divergences);
            requireFacet(name, "ApiError.status", resolved(members.get("status"), schemas),
                    "maximum", HIGHEST_PROBLEM_STATUS, divergences);

            Map<String, Object> timestamp = resolved(members.get("timestamp"), schemas);
            requireFacet(name, "ApiError.timestamp", timestamp, "maxLength", TIMESTAMP_WIDTH, divergences);
            if (!timestamp.containsKey("pattern")) {
                divergences.add(name + " ApiError.timestamp declares no pattern, so its six microsecond"
                        + " digits are prose rather than a contract");
            }

            if (resolved(members.get("path"), schemas).containsKey("maxLength")) {
                divergences.add(name + " ApiError.path declares a length bound the container does not"
                        + " enforce, so a legitimate request target could violate this schema");
            }

            Map<String, Object> entry = asMap(asMap(schemas.get(FIELD_ERROR_SCHEMA)).get("properties"));
            requireFacet(name, "FieldError.field", resolved(entry.get("field"), schemas),
                    "minLength", 1, divergences);
            requireFacet(name, "FieldError.message", resolved(entry.get("message"), schemas),
                    "minLength", 1, divergences);
            requireFacet(name, "FieldError.message", resolved(entry.get("message"), schemas),
                    "maxLength", MESSAGE_WIDTH, divergences);
        }

        assertThat(divergences)
                .as("one generated client type reads a problem document from any of the seven services, so a"
                        + " facet that differs between two documents is a type that differs between them")
                .isEmpty();
    }

    /**
     * Asserts that every property of every response-reachable schema is listed in {@code required}.
     *
     * <p>Purpose: {@code carddemo-common-defaults.yml} pins {@code default-property-inclusion} to
     * {@code always}, so Jackson writes EVERY record component of EVERY response body. A published
     * property is therefore never absent, and a member that may hold no value is required AND nullable.
     * Publishing such a member as optional describes an omission the writing service cannot produce, and
     * generates a client type that is optional AND nullable -- two states for one fact, which a caller
     * then has to tell apart for no reason.</p>
     *
     * <p>Assumptions: the rule is applied to schemas REACHABLE FROM A RESPONSE, computed here as the
     * transitive closure over {@code $ref} from every operation response and every shared response
     * component. Request schemas are deliberately outside it: the inclusion policy governs what this
     * service WRITES, not what a caller sends, so a required request property genuinely can be absent
     * from a request and an optional one is a meaningful thing to publish.</p>
     *
     * <p>Assumptions: the closure is computed rather than listed, so a schema added to a response next
     * week is examined without this test being edited. A hand-written list would have to be maintained
     * in step with seven documents and would silently stop covering whatever was added to them.</p>
     *
     * <p>Alternatives Considered: reading the DESCRIPTIONS for the words "absent" or "optional" and
     * failing on them. Rejected as unusable: a bare search over the seven documents returns thirty hits
     * of which nearly all are legitimate -- request schemas whose properties genuinely can be absent, and
     * prose about a stored VALUE being absent rather than about the member. Any such gate needs an
     * exemption list to stay green, and an exemption list is what this suite avoids everywhere else. The
     * structural rule needs none: it reads the required list against the property list and nothing else.
     * </p>
     *
     * <p>Measured: neutralising this rule and restoring one member to optionality -- removing
     * {@code payableBalance} from BillPaymentPreview's required list in transaction-api.yaml -- fails this
     * case alone with {@code transaction-api.yaml BillPaymentPreview.payableBalance is written on every
     * response but is not required}, and no other case of any suite moves.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every response schema lists every property it publishes as required")
    void everyResponseSchemaClosesItsRequiredList() {
        List<String> openMembers = new ArrayList<>();
        List<Path> contracts = contracts();
        int examined = 0;

        assertThat(contracts)
                .as("the walk must resolve the contract tree; an empty list would examine nothing")
                .hasSizeGreaterThanOrEqualTo(EXPECTED_CONTRACTS);

        for (Path contract : contracts) {
            Map<String, Object> document = load(contract);
            Map<String, Object> schemas = asMap(asMap(document.get("components")).get("schemas"));
            String name = String.valueOf(contract.getFileName());

            for (String schema : responseReachable(document, schemas)) {
                Map<String, Object> node = asMap(schemas.get(schema));
                Map<String, Object> properties = asMap(node.get("properties"));
                if (properties.isEmpty()) {
                    continue;
                }
                examined++;
                Set<String> required = new LinkedHashSet<>();
                if (node.get("required") instanceof List<?> declared) {
                    declared.forEach(member -> required.add(String.valueOf(member)));
                }
                for (String member : properties.keySet()) {
                    if (!required.contains(member)) {
                        openMembers.add(name + " " + schema + "." + member
                                + " is written on every response but is not required");
                    }
                }
            }
        }

        assertThat(examined)
                .as("the closure must reach the response schemas; zero examined would pass by examining"
                        + " nothing, which is the failure mode this count exists to catch")
                .isGreaterThanOrEqualTo(EXPECTED_RESPONSE_SCHEMAS);
        assertThat(openMembers)
                .as("the pinned inclusion policy writes every record component, so an unlisted property"
                        + " describes an omission no service in this reactor can produce")
                .isEmpty();
    }

    /**
     * Collects the schemas reachable from any response of a document.
     *
     * <p>Assumptions: shared response COMPONENTS are seeded alongside the operations' own responses,
     * because most refusal bodies in these documents are published once as a component and referenced --
     * a walk over operations alone would miss the problem model entirely.</p>
     *
     * @param document the parsed contract; must not be {@code null}
     * @param schemas the document's schema section; must not be {@code null}
     * @return the names of every schema a response can reach, never {@code null}
     */
    private static Set<String> responseReachable(Map<String, Object> document,
            Map<String, Object> schemas) {
        Deque<String> pending = new ArrayDeque<>();
        asMap(document.get("paths")).forEach((path, item) -> asMap(item).forEach((method, operation) -> {
            if (METHODS.contains(method.toLowerCase(Locale.ROOT))) {
                collectReferences(responses(asMap(operation)), pending);
            }
        }));
        collectReferences(asMap(asMap(document.get("components")).get("responses")), pending);

        // WHY : Assumptions: a name is expanded only on its FIRST visit, which both bounds the walk and
        //       makes it safe on a self-referential schema. Expanding on every visit would not terminate
        //       on a document where two schemas reference each other, a shape the specification permits.
        Set<String> reached = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            String schema = pending.pop();
            if (!schemas.containsKey(schema) || !reached.add(schema)) {
                continue;
            }
            collectReferences(asMap(schemas.get(schema)), pending);
        }
        return reached;
    }

    /**
     * Adds every schema name a node references, at any depth, to a work list.
     *
     * @param node the node to scan, which may be any parsed YAML value
     * @param sink the work list to add names to; must not be {@code null}
     */
    private static void collectReferences(Object node, Deque<String> sink) {
        if (node instanceof Map<?, ?> mapping) {
            mapping.forEach((key, value) -> {
                if ("$ref".equals(String.valueOf(key)) && value instanceof String reference
                        && reference.startsWith(SCHEMA_REFERENCE_PREFIX)) {
                    sink.push(reference.substring(reference.lastIndexOf('/') + 1));
                } else {
                    collectReferences(value, sink);
                }
            });
        } else if (node instanceof List<?> sequence) {
            sequence.forEach(value -> collectReferences(value, sink));
        }
    }

    /**
     * What one operation of one contract is handed to a rule.
     */
    private interface OperationRule {

        /**
         * Applies this rule to one operation.
         *
         * @param contract the contract file name, named in any finding so a failure points at one document
         * @param operationId the operation identifier
         * @param method the HTTP method the operation is declared under
         * @param operation the operation object
         */
        void check(String contract, String operationId, String method, Map<String, Object> operation);
    }

    /**
     * Applies a rule to every operation of every published contract.
     *
     * @param rule the rule to apply; must not be {@code null}
     * @throws AssertionError if fewer contracts are found than the reactor publishes, which means the walk
     *     resolved a directory that is not the reactor
     */
    private static void forEachOperation(OperationRule rule) {
        List<Path> contracts = contracts();

        assertThat(contracts)
                .as("the walk must resolve the contract tree; an empty list would examine nothing")
                .hasSizeGreaterThanOrEqualTo(EXPECTED_CONTRACTS);

        for (Path contract : contracts) {
            Map<String, Object> document = load(contract);
            asMap(document.get("paths")).forEach((path, item) -> {
                Map<String, Object> pathItem = asMap(item);
                pathItem.forEach((method, operation) -> {
                    if (!METHODS.contains(method.toLowerCase(Locale.ROOT))) {
                        return;
                    }
                    Map<String, Object> spec = new LinkedHashMap<>(asMap(operation));
                    // WHY : Assumptions: a parameter declared on the PATH ITEM applies to every operation
                    //       under it, so it is folded in here rather than checked separately. Reading only
                    //       the operation's own list would report a gap for a contract that declared the
                    //       parameter once for a whole path, which is a form the specification permits.
                    if (pathItem.get("parameters") instanceof List<?> shared
                            && !(spec.get("parameters") instanceof List<?>)) {
                        spec.put("parameters", shared);
                    }
                    rule.check(String.valueOf(contract.getFileName()),
                            String.valueOf(spec.getOrDefault("operationId", path + " " + method)),
                            method, spec);
                });
            });
        }
    }

    /**
     * Records a finding when an operation does not publish one status through its canonical component.
     *
     * @param contract the contract file name
     * @param operationId the operation identifier
     * @param operation the operation object
     * @param status the status the operation must publish
     * @param component the response component that status must name
     * @param into the findings to append to
     */
    private static void requirePublished(String contract, String operationId,
            Map<String, Object> operation, String status, String component, List<String> into) {
        Object declared = responses(operation).get(status);
        if (declared == null) {
            into.add(contract + " " + operationId + " does not publish " + status);
            return;
        }
        Object reference = asMap(declared).get("$ref");
        if (reference == null) {
            into.add(contract + " " + operationId + " publishes " + status + " inline rather than through "
                    + component);
            return;
        }
        String named = String.valueOf(reference);
        if (!named.endsWith("/" + component)) {
            into.add(contract + " " + operationId + " publishes " + status + " as " + named
                    + " rather than " + component);
        }
    }

    /**
     * Reports whether an operation's success response carries content.
     *
     * @param operation the operation object
     * @return {@code true} when at least one 2xx response declares content
     */
    private static boolean returnsBody(Map<String, Object> operation) {
        return responses(operation).entrySet().stream()
                .anyMatch(entry -> entry.getKey().startsWith("2")
                        && !asMap(asMap(entry.getValue()).get("content")).isEmpty());
    }

    /**
     * Reports whether an operation names the shared correlation parameter.
     *
     * @param operation the operation object, with any path-item parameters already folded in
     * @return {@code true} when the shared parameter is among its parameters
     */
    private static boolean namesCorrelationParameter(Map<String, Object> operation) {
        if (!(operation.get("parameters") instanceof List<?> declared)) {
            return false;
        }
        return declared.stream()
                .map(PublishedContractClosureTest::asMap)
                .map(parameter -> String.valueOf(parameter.get("$ref")))
                .anyMatch(reference -> reference.endsWith("/" + CORRELATION_PARAMETER));
    }

    /**
     * Reads an operation's responses as a map keyed by status, with every key rendered as text.
     *
     * @param operation the operation object
     * @return the responses, never {@code null}
     */
    private static Map<String, Object> responses(Map<String, Object> operation) {
        Map<String, Object> byStatus = new LinkedHashMap<>();
        asMap(operation.get("responses")).forEach((status, value) ->
                byStatus.put(String.valueOf(status), value));
        return byStatus;
    }

    /**
     * Records a divergence when a resolved member does not carry one expected facet value.
     *
     * @param contract the contract file name
     * @param member the member being read, named as it is published
     * @param facets the member's resolved facets
     * @param facet the facet name
     * @param expected the value every contract must publish
     * @param into the divergences to append to
     */
    private static void requireFacet(String contract, String member, Map<String, Object> facets,
            String facet, int expected, List<String> into) {
        Object declared = facets.get(facet);
        if (declared == null) {
            into.add(contract + " " + member + " declares no " + facet
                    + ", where the other contracts publish " + expected);
            return;
        }
        if (!(declared instanceof Number number) || number.intValue() != expected) {
            into.add(contract + " " + member + " publishes " + facet + " " + declared
                    + " where the canonical value is " + expected);
        }
    }

    /**
     * Resolves a member's effective facets through one level of {@code $ref} and {@code allOf}.
     *
     * <p>Assumptions: exactly ONE level is followed, and that is enough for every form these documents
     * use -- a bare reference to a shared scalar, an {@code allOf} over one such reference, and a plain
     * inline declaration. A general resolver would be more code and would let a document satisfy a facet
     * through an arbitrarily deep composition that no reader of the document could see at a glance.</p>
     *
     * @param member the member node, which may be {@code null}
     * @param schemas the document's schema section, used to follow a reference
     * @return the effective facets, with the member's own declarations winning over the referenced ones;
     *     never {@code null}
     */
    private static Map<String, Object> resolved(Object member, Map<String, Object> schemas) {
        Map<String, Object> node = asMap(member);
        Map<String, Object> effective = new LinkedHashMap<>();
        Object reference = node.get("$ref");
        if (reference == null && node.get("allOf") instanceof List<?> composed && !composed.isEmpty()) {
            reference = asMap(composed.get(0)).get("$ref");
        }
        if (reference != null) {
            String named = String.valueOf(reference);
            effective.putAll(asMap(schemas.get(named.substring(named.lastIndexOf('/') + 1))));
        }
        effective.putAll(node);
        return effective;
    }

    /**
     * Collects every published contract in the reactor.
     *
     * @return one path per contract document, module by module in name order; never {@code null}
     * @throws UncheckedIOException if a directory cannot be listed
     */
    private static List<Path> contracts() {
        Path services = repositoryRoot().resolve(SERVICES_DIRECTORY);
        List<Path> found = new ArrayList<>();
        try (Stream<Path> modules = Files.list(services)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path directory = module.resolve(CONTRACT_DIRECTORY);
                if (!Files.isDirectory(directory)) {
                    continue;
                }
                try (Stream<Path> documents = Files.list(directory)) {
                    documents.filter(Files::isRegularFile)
                            .filter(document -> document.getFileName().toString().endsWith(".yaml"))
                            .sorted()
                            .forEach(found::add);
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("could not list contracts under " + services, failure);
        }
        return found;
    }

    /**
     * Parses one contract document.
     *
     * @param contract the document to read; must not be {@code null}
     * @return the parsed document, never {@code null}
     * @throws UncheckedIOException if the document cannot be read
     */
    private static Map<String, Object> load(Path contract) {
        try (InputStream stream = Files.newInputStream(contract)) {
            return asMap(new Yaml().load(stream));
        } catch (IOException failure) {
            throw new UncheckedIOException("could not read " + contract, failure);
        }
    }

    /**
     * Reads a node as a string-keyed map, treating anything else as empty.
     *
     * <p>Assumptions: a non-mapping node yields an EMPTY map rather than raising, so a document shaped
     * unexpectedly is reported by the assertion that reads it instead of by a cast failure naming a
     * type.</p>
     *
     * @param node the node to read, which may be {@code null}
     * @return the node as a map, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        if (node instanceof Map<?, ?> mapping) {
            Map<String, Object> byName = new LinkedHashMap<>();
            mapping.forEach((key, value) -> byName.put(String.valueOf(key), value));
            return byName;
        }
        return Map.of();
    }

    /**
     * Locates the repository root by walking up to the reactor's own aggregator.
     *
     * @return the directory holding the reactor aggregator; never {@code null}
     * @throws IllegalStateException if no ancestor holds it, which means the test is running from a
     *     directory outside the checkout
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(ROOT_MARKER))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of " + Path.of("").toAbsolutePath() + " contains " + ROOT_MARKER);
    }
}
