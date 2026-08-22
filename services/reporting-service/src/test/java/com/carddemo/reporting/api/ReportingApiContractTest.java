package com.carddemo.reporting.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.web.CursorToken;
import com.carddemo.reporting.api.ReportController;
import com.carddemo.reporting.config.OpenApiConfig;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementTransactionCollection;
import com.carddemo.reporting.service.ReportExecutionService;
import com.carddemo.reporting.service.StatementService;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Pins the packaged OpenAPI contract of this bounded context against the document its
 * {@link OpenApiConfig} bean serves, and against the two deployed artifacts that route to it.
 *
 * <p>Refactoring Rationale: this class exists because the three statements it checks were previously
 * made in prose only, and one of them was false. {@code OpenApiConfig} named
 * {@code src/main/resources/openapi/reporting-api.yaml} the contract of record in the same Javadoc
 * block that recorded the file as "not yet present in the tree"; {@code pom.xml} asserted the same
 * file and a browser client written against it; and the package charter of
 * {@code com.carddemo.reporting} restated both. Prose cannot fail a build, so the contradiction
 * survived every green run. The contract now exists, and every claim about it that a reader would
 * otherwise have to take on trust is asserted here instead, so a claim and the tree can no longer
 * disagree while the build stays green.</p>
 *
 * <p>Assumptions: this is a plain unit test with no Spring context. The bean under comparison is a
 * pure factory over class constants -- it reads no property, injects nothing and touches no
 * environment -- so instantiating {@link OpenApiConfig} directly compares exactly the values a
 * running application would serve, without a context to start. Comparing against literals restated
 * here was the alternative, and it was rejected because a literal copied into a test agrees with the
 * test rather than with the bean: renaming the contract in the bean would leave both the test and
 * the document unchanged and the mismatch undetected.</p>
 *
 * <p>Trade-offs: the reference check below is textual rather than a schema walk. A walk would follow
 * only the references reachable from the paths block, so a component referenced solely from another
 * component -- which is most of the shared problem shape here -- would go unchecked, and a reference
 * whose target had been renamed would be reported as an absent branch rather than as a broken link.
 * The cost accepted is that the pattern has to match the document's reference spelling exactly; the
 * offsetting benefit is that a self-check fails loudly if the pattern ever stops matching, so the
 * check cannot silently degrade into asserting nothing.</p>
 */
class ReportingApiContractTest {

    /**
     * Classpath location of the packaged contract.
     *
     * <p>Assumptions: the leading slash is required. A relative name would resolve against this
     * test's own package and find nothing, and the failure would read as an absent contract rather
     * than as a mislocated lookup.</p>
     */
    private static final String CONTRACT_RESOURCE = "/openapi/reporting-api.yaml";

    /**
     * Path prefix every published operation of this context must sit beneath.
     *
     * <p>Assumptions: this value is not a convention of this document. It is the forwarding pattern
     * the load balancer applies to this workload, {@code "/api/v1/reports"} together with its
     * wildcard sibling in the {@code local.online_services} entry of
     * {@code infra/envs/{dev,prod}/main.tf}, and the route key
     * {@code "ANY /api/v1/reports/{proxy+}"} the public HTTP API publishes from the
     * {@code route_keys} default in {@code infra/modules/api-gateway-http/variables.tf}. An
     * operation declared outside it is unreachable through both hops while remaining a perfectly
     * valid OpenAPI declaration, which is exactly the failure this constant exists to catch.</p>
     */
    private static final String ROUTED_PREFIX = "/api/v1/reports";

    /** The two authority values the document's own authority model admits. */
    private static final Set<String> ADMITTED_AUTHORITIES = Set.of("carddemo-user", "carddemo-admin");

    /**
     * The authority this context's filter chain requires of every operation the catch-all rule governs.
     *
     * <p>Assumptions: the ordinary group and not the administrative one, because
     * {@code SecurityConfig}'s catch-all rule admits a token carrying either group and refuses one
     * carrying neither. Declaring the administrative authority on an operation the catch-all governs
     * would describe a restriction the chain does not apply, which is the drift this constant pins.</p>
     */
    private static final String ENFORCED_AUTHORITY = "carddemo-user";

    /**
     * The authority the chain requires of the operations it guards with a rule of their own.
     *
     * <p>⚠️ Assumptions: this map is keyed by operation identifier and is the only admitted exception to
     * {@link #ENFORCED_AUTHORITY}. It exists because {@code SecurityConfig} now declares a rule BEFORE
     * its catch-all: the statement artifact collection route admits the administrative authority alone,
     * because the objects it serves cover a whole statement run rather than one card. A review found
     * that route inheriting the catch-all, so the document and the chain agreeing about it is exactly
     * what wants pinning -- a contract still promising the ordinary group here would tell a caller it
     * may collect what the chain refuses.</p>
     *
     * <p>Alternatives Considered: dropping the per-operation assertion and checking only that each
     * authority is one the model admits. Rejected because that is the assertion this class was written
     * to avoid: it passes for a contract that declares the administrative group on every operation, and
     * would have passed for the defect this exception records.</p>
     */
    private static final Map<String, String> OPERATION_AUTHORITY_EXCEPTIONS =
            Map.of("collectArtifact", "carddemo-admin");

    /** Number of references the document is known to declare, guarding the textual check below. */
    private static final int MINIMUM_EXPECTED_REFERENCES = 20;

    /** Matches one internal component reference exactly as this document spells it. */
    private static final Pattern REFERENCE = Pattern.compile("\\$ref: '#/components/(\\w+)/(\\w+)'");

    /**
     * Verifies that the packaged contract is present and parses as a mapping.
     *
     * @throws Exception if the document is absent from the classpath or unreadable, either of which
     *     means the packaged contract is not the one under test
     */
    @Test
    @DisplayName("the packaged contract is present on the classpath and parses")
    void theContractIsPackagedAndParses() throws Exception {
        Map<String, Object> root = contractRoot();

        assertThat(root).containsKeys("openapi", "info", "servers", "security", "paths", "components");
    }

    /**
     * Verifies that the document's specification version equals the one the bean serves.
     *
     * @throws Exception if the packaged document is absent or unreadable
     */
    @Test
    @DisplayName("the contract declares the same specification version the bean serves")
    void theSpecificationVersionAgreesWithTheBean() throws Exception {
        OpenAPI served = new OpenApiConfig().reportingServiceOpenApi();

        assertThat(contractRoot().get("openapi")).isEqualTo(served.getOpenapi());
    }

    /**
     * Verifies that the document's information block equals the one the bean serves, member by
     * member.
     *
     * <p>Assumptions: all five members are compared and not merely the title, because each is read
     * for a different purpose -- the title and version identify the contract to a consumer, the
     * summary and description tell a caller what it covers, and the licence is machine-readable. A
     * comparison of one member would let the other four drift.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable
     */
    @Test
    @DisplayName("the contract information block equals the one the bean serves")
    void theInformationBlockAgreesWithTheBean() throws Exception {
        OpenAPI served = new OpenApiConfig().reportingServiceOpenApi();
        Map<String, Object> info = mapping(contractRoot(), "info");

        assertThat(info.get("title")).isEqualTo(served.getInfo().getTitle());
        assertThat(info.get("version")).isEqualTo(served.getInfo().getVersion());
        assertThat(info.get("summary")).isEqualTo(served.getInfo().getSummary());
        assertThat(info.get("description")).isEqualTo(served.getInfo().getDescription());

        Map<String, Object> license = mapping(info, "license");
        assertThat(license.get("name")).isEqualTo(served.getInfo().getLicense().getName());
        assertThat(license.get("identifier")).isEqualTo(served.getInfo().getLicense().getIdentifier());
    }

    /**
     * Verifies that the document declares the same single security scheme the bean declares, and
     * requires it at document level.
     *
     * @throws Exception if the packaged document is absent or unreadable
     */
    @Test
    @DisplayName("the contract declares the bean's bearer scheme and requires it at document level")
    void theSecuritySchemeAgreesWithTheBean() throws Exception {
        OpenAPI served = new OpenApiConfig().reportingServiceOpenApi();
        String schemeName = served.getComponents().getSecuritySchemes().keySet().iterator().next();
        SecurityScheme servedScheme = served.getComponents().getSecuritySchemes().get(schemeName);

        Map<String, Object> schemes =
                mapping(mapping(contractRoot(), "components"), "securitySchemes");
        assertThat(schemes).hasSize(1).containsKey(schemeName);

        Map<String, Object> declared = mapping(schemes, schemeName);
        assertThat(declared.get("type")).isEqualTo(servedScheme.getType().toString());
        assertThat(declared.get("scheme")).isEqualTo(servedScheme.getScheme());
        assertThat(declared.get("bearerFormat")).isEqualTo(servedScheme.getBearerFormat());
        assertThat(declared.get("description")).isEqualTo(servedScheme.getDescription());

        List<Map<String, Object>> requirements = sequence(contractRoot(), "security");
        assertThat(requirements)
                .as("the requirement must be asserted once at document level, not per operation")
                .hasSize(1);
        assertThat(requirements.get(0)).containsOnlyKeys(schemeName);
        // WHY : Assumptions: the scope list must be EMPTY and not merely present. The specification
        //       requires an empty list for any scheme that is neither of the two identity-federation
        //       types, and this one is of HTTP type, so a populated list here would be invalid rather
        //       than merely unusual -- and an absent list would be indistinguishable from a forgotten
        //       one.
        assertThat(requirements.get(0).get(schemeName)).isInstanceOf(List.class);
        assertThat((List<?>) requirements.get(0).get(schemeName)).isEmpty();
    }

    /**
     * Pins the published surface to exactly the eight paths and eight operation identifiers this
     * context declares.
     *
     * <p>Refactoring Rationale: the surface is pinned by an exact set rather than by a lower bound,
     * following the convention {@code AccountContextContractTest} establishes for the sibling
     * contract. A lower bound would let an operation be added to the document without a
     * corresponding route key in {@code infra/modules/api-gateway-http/variables.tf}, which
     * publishes an address the edge answers with its own 404 while the service is running, healthy
     * and correct -- the least diagnosable of the two possible mismatches.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable
     */
    @Test
    @DisplayName("the contract publishes exactly the eight declared operations")
    void thePublishedSurfaceIsPinned() throws Exception {
        Map<String, Object> paths = mapping(contractRoot(), "paths");

        assertThat(paths.keySet())
                .containsExactlyInAnyOrder(
                        ROUTED_PREFIX + "/transaction-report",
                        ROUTED_PREFIX + "/transaction-report/lines",
                        ROUTED_PREFIX + "/transaction-report/totals",
                        ROUTED_PREFIX + "/statements",
                        ROUTED_PREFIX + "/statements/transactions",
                        ROUTED_PREFIX + "/statements/artifacts/{selector}",
                        ROUTED_PREFIX + "/executions/{executionName}",
                        ROUTED_PREFIX + "/transaction-report/artifact");
        assertThat(operationIdentifiers())
                .containsExactlyInAnyOrder(
                        "submitTransactionReport",
                        "listTransactionReportLines",
                        "readTransactionReportTotals",
                        "generateStatement",
                        "listStatementTransactions",
                        "collectArtifact",
                        "readReportExecution",
                        "collectReportArtifact");
    }

    /**
     * Verifies that every declared path sits beneath the prefix the two routing hops forward.
     *
     * @throws Exception if the packaged document is absent or unreadable
     */
    @Test
    @DisplayName("every declared path sits beneath the routed prefix")
    void everyPathIsReachableThroughBothHops() throws Exception {
        Set<String> paths = mapping(contractRoot(), "paths").keySet();

        assertThat(paths).isNotEmpty();
        assertThat(paths).allSatisfy(path -> assertThat(path).startsWith(ROUTED_PREFIX + "/"));
    }

    /**
     * Verifies that every operation carries a unique identifier and the authority the chain
     * enforces for it.
     *
     * <p>Assumptions: the expected authority is the catch-all's, except for the operations
     * {@link #OPERATION_AUTHORITY_EXCEPTIONS} names, which the chain guards with a rule of their own.
     * The exception is stated per operation rather than admitted for any operation, so a contract that
     * quietly widened or narrowed a second route would still fail here.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable
     */
    @Test
    @DisplayName("every operation carries a unique identifier and the enforced authority")
    void everyOperationIsIdentifiedAndAuthorized() throws Exception {
        List<String> identifiers = new ArrayList<>();

        for (Map.Entry<String, Object> pathEntry : mapping(contractRoot(), "paths").entrySet()) {
            Map<String, Object> item = asMapping(pathEntry.getValue(), pathEntry.getKey());
            for (Map.Entry<String, Object> methodEntry : item.entrySet()) {
                if (!isHttpMethod(methodEntry.getKey())) {
                    continue;
                }
                Map<String, Object> operation =
                        asMapping(methodEntry.getValue(), pathEntry.getKey() + '.' + methodEntry.getKey());

                Object identifier = operation.get("operationId");
                assertThat(identifier)
                        .as("%s %s must declare an operationId", methodEntry.getKey(), pathEntry.getKey())
                        .isInstanceOf(String.class);
                identifiers.add((String) identifier);

                Object authority = operation.get("x-required-authority");
                String expected = OPERATION_AUTHORITY_EXCEPTIONS
                        .getOrDefault((String) identifier, ENFORCED_AUTHORITY);
                assertThat(authority)
                        .as("%s must declare x-required-authority %s", identifier, expected)
                        .isEqualTo(expected);
                assertThat(ADMITTED_AUTHORITIES).contains((String) authority);
            }
        }

        assertThat(identifiers).isNotEmpty().doesNotHaveDuplicates();
    }

    /**
     * Verifies that every internal reference in the document resolves to a declared component.
     *
     * <p>Assumptions: the reference count is asserted before the resolution loop runs. Without that
     * self-check a pattern that stopped matching would make this test vacuously green, which is the
     * one failure mode a textual check has and an unchecked one would hide.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable
     */
    @Test
    @DisplayName("every internal reference resolves to a declared component")
    void everyReferenceResolves() throws Exception {
        Map<String, Object> components = mapping(contractRoot(), "components");
        Set<String> seen = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();

        Matcher matcher = REFERENCE.matcher(contractText());
        while (matcher.find()) {
            String kind = matcher.group(1);
            String name = matcher.group(2);
            seen.add(kind + '/' + name);
            Object bucket = components.get(kind);
            if (!(bucket instanceof Map<?, ?> declared) || !declared.containsKey(name)) {
                unresolved.add(kind + '/' + name);
            }
        }

        assertThat(seen)
                .as("the reference pattern must still match this document's spelling")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_EXPECTED_REFERENCES);
        assertThat(unresolved).isEmpty();
    }

    /**
     * Verifies that no card number reaches a target and that the published rendering is masked.
     *
     * <p>Assumptions: the target check is a check on the PATH TEMPLATES and not on the schemas,
     * because a target is composed before this service sees the request. The load balancer writes
     * the template's resolved value into its access log before any handler runs and the browser
     * retains it in history, so neither store is reachable by anything this module could add: a
     * number kept out of a target is the only control that acts on them. The one place an unmasked
     * number is admitted is a request body, which neither store retains.</p>
     *
     * @throws Exception if the packaged document is absent or unreadable
     */
    @Test
    @DisplayName("no card number appears in a path template and the published rendering is masked")
    void noCardNumberReachesATarget() throws Exception {
        for (String path : mapping(contractRoot(), "paths").keySet()) {
            assertThat(path.toLowerCase(java.util.Locale.ROOT))
                    .as("%s must not address a card by number", path)
                    .doesNotContain("card");
        }

        Map<String, Object> schemas = mapping(mapping(contractRoot(), "components"), "schemas");
        assertThat(mapping(schemas, "MaskedCardNumber").get("pattern"))
                .isEqualTo("^\\*{12}[0-9]{4}$");
    }

    /**
     * Collects the identifier of every operation the contract declares.
     *
     * <p>Assumptions: a path item carries members that are not methods -- a description and a shared
     * parameter list -- so the walk filters on the five method names rather than taking every member,
     * which would read a description as an operation and fail for the wrong reason.</p>
     *
     * @return one identifier per declared operation, in document order, never {@code null}
     * @throws Exception if the packaged document is absent or unreadable
     */
    private List<String> operationIdentifiers() throws Exception {
        List<String> identifiers = new ArrayList<>();
        for (Map.Entry<String, Object> pathEntry : mapping(contractRoot(), "paths").entrySet()) {
            Map<String, Object> item = asMapping(pathEntry.getValue(), pathEntry.getKey());
            for (Map.Entry<String, Object> methodEntry : item.entrySet()) {
                if (isHttpMethod(methodEntry.getKey())) {
                    Map<String, Object> operation = asMapping(
                            methodEntry.getValue(), pathEntry.getKey() + '.' + methodEntry.getKey());
                    identifiers.add(String.valueOf(operation.get("operationId")));
                }
            }
        }
        return identifiers;
    }

    /**
     * Asserts the two paging facets this document publishes are the runtime's own, exactly.
     *
     * <p>Refactoring Rationale: the review found BOTH of them wrong, in opposite directions, and both
     * failures were invisible to a compiler because one side of each is a value in a document. The
     * direction enumeration declared {@code previous} while the handler compared against
     * {@code "prev"}, so a conforming backward request was processed as forward, opened the caller's
     * leading position under the forward binding, and was answered with an opaque refusal of a cursor
     * this service had itself just issued -- there was no request a client could send to page backward
     * at all. And the cursor schema declared {@code maxLength: 512} with no pattern, twice the width
     * the sealer accepts and admitting any string whatever, so a body this document called valid was
     * refused by the service. Both are now read from the runtime rather than restated.</p>
     *
     * <p>Assumptions: the enumeration is asserted as an EQUALITY against the handler's two constants
     * rather than as a containment. A containment check would pass for a document that published a
     * third value the handler refuses, which is the same class of disagreement in the other
     * direction.</p>
     *
     * @throws Exception if the document is absent from the classpath or unreadable
     */
    @Test
    @DisplayName("the direction enumeration and the cursor facets are the runtime's own values")
    void thePagingFacetsAreTheRuntimeValues() throws Exception {
        Map<String, Object> schemas = mapping(mapping(contractRoot(), "components"), "schemas");

        Map<String, Object> direction = mapping(schemas, "PageDirection");
        assertThat(direction.get("enum"))
                .as("the published direction domain must be the two values the handler compares")
                .isEqualTo(List.of(ReportController.NEXT_DIRECTION,
                        ReportController.PREVIOUS_DIRECTION));
        assertThat(direction.get("default"))
                .as("an absent direction means forward, and the document must say which value that is")
                .isEqualTo(ReportController.NEXT_DIRECTION);

        Map<String, Object> cursor = mapping(schemas, "CursorToken");
        assertThat(cursor.get("maxLength"))
                .as("a published width above %s admits a token the sealer refuses",
                        CursorToken.MAX_TOKEN_LENGTH)
                .isEqualTo(CursorToken.MAX_TOKEN_LENGTH);
        assertThat(cursor.get("pattern"))
                .as("the published shape must be the sealer's own, so a contract-valid token is one"
                        + " the service can open")
                .isEqualTo("^" + CursorToken.SEALED_SHAPE_PATTERN + "$");
    }

    // WHY : Assumptions: the sentence is held to the PUBLISHED CATALOGUE rather than merely to itself.
    //       The contract states that every message this operation may carry is reproduced verbatim from
    //       the reference and lists them, because the browser client copies those strings into its own
    //       catalogue -- so a sentence the service raises that is not on the list is a message no client
    //       has been written to display. An earlier revision raised "exactly one of monthly, yearly or
    //       custom must be selected but 0 were", which was authored in this repository and appears nowhere
    //       in the list; nothing detected that, because neither side referred to the other.
    // WHY : Refactoring Rationale: this case exists because three paragraphs of the contract asserted
    //       that the response ceiling was declared as maxItems on this array while the document declared
    //       no maxItems anywhere. Nothing detected it: the ceiling lived in a Java constant, the claim
    //       lived in prose, and no test read one against the other -- so a generated client bounded
    //       nothing, and a reader auditing the bound found only the claim that it existed.
    //       Assumptions: the member list is read from the RECORD by reflection rather than written out
    //       here. A list written here would be a third statement of the same fact and would agree with
    //       the document while both disagreed with the record.
    /**
     * Asserts the statement-transaction body publishes the record's members and the service's own bound.
     *
     * @throws Exception if the document is absent from the classpath or unreadable
     */
    @Test
    @DisplayName("the statement-transaction body publishes the record's members and the service's bound")
    void theStatementTransactionBodyPublishesItsBound() throws Exception {
        Map<String, Object> schemas = mapping(mapping(contractRoot(), "components"), "schemas");
        Map<String, Object> collection = mapping(schemas, "StatementTransactionCollection");

        List<String> components = new ArrayList<>();
        for (RecordComponent component : StatementTransactionCollection.class.getRecordComponents()) {
            components.add(component.getName());
        }

        assertThat(mapping(collection, "properties").keySet())
                .as("a published member the record omits is a value a client waits for and never"
                        + " receives, and the reverse is a value it discards")
                .containsExactlyInAnyOrderElementsOf(components);

        List<String> required = new ArrayList<>();
        for (Object entry : (List<?>) collection.get("required")) {
            required.add(String.valueOf(entry));
        }
        assertThat(required)
                .as("a count a caller cannot rely on being present cannot be used to detect truncation")
                .containsExactlyInAnyOrderElementsOf(components);

        assertThat(collection.get("additionalProperties")).isEqualTo(false);
        assertThat(mapping(mapping(collection, "properties"), "items").get("maxItems"))
                .as("the published ceiling must be the one the service applies, otherwise the document"
                        + " promises a bound nothing enforces or hides one that is enforced")
                .isEqualTo(StatementService.MAX_RESPONSE_TRANSACTIONS);
    }

    // WHY : Refactoring Rationale: this case exists because the exactly-one-of rule of the statement
    //       selector was stated in prose on BOTH sides and the two sentences disagreed --
    //       StatementRequest's Javadoc said the document declared the rule, and the document said the
    //       rule was one only the service could state. Neither sentence could fail a build, so the
    //       contradiction survived every green run. The rule is now in the document as two mutually
    //       exclusive branches, and it is asserted here so a later edit cannot quietly drop it back to
    //       prose. Alternatives Considered: asserting the two prose blocks agree, by text. Rejected:
    //       two sentences can agree with each other and both be wrong about the schema, which is the
    //       exact failure this replaces.
    /**
     * Asserts the statement selector publishes exactly-one-of as two mutually exclusive branches.
     *
     * <p>Assumptions: the branches are read STRUCTURALLY rather than by searching the document for a
     * sentence. A generated client and a validating proxy apply {@code oneOf} and {@code not}, and
     * neither reads a description, so the structure is the only part of this rule that has an effect
     * on a caller.</p>
     *
     * <p>Assumptions: the absence of a top-level {@code required} array is asserted too, because that
     * absence is what makes the branches necessary -- either component may be the omitted one, so
     * neither can be required unconditionally, and a reader who finds no required array needs to see
     * that the rule is carried somewhere rather than nowhere.</p>
     *
     * @throws Exception if the document is absent from the classpath or unreadable
     */
    @Test
    @DisplayName("the statement selector publishes exactly-one-of as two exclusive branches")
    void theStatementSelectorPublishesExclusiveBranches() throws Exception {
        Map<String, Object> schemas = mapping(mapping(contractRoot(), "components"), "schemas");
        Map<String, Object> selector = mapping(schemas, "StatementRequest");

        List<String> components = new ArrayList<>();
        for (RecordComponent component : StatementRequest.class.getRecordComponents()) {
            components.add(component.getName());
        }

        assertThat(mapping(selector, "properties").keySet())
                .as("the published members must be the record's own, name for name")
                .containsExactlyInAnyOrderElementsOf(components);
        assertThat(selector.get("additionalProperties"))
                .as("the body is closed, which is what makes an undeclared member a refusal rather"
                        + " than a value the reader discards")
                .isEqualTo(false);
        assertThat(selector.get("required"))
                .as("neither component may be required unconditionally, because either may be the"
                        + " omitted one")
                .isNull();

        List<?> branches = (List<?>) selector.get("oneOf");
        assertThat(branches)
                .as("exactly-one-of over two components is two branches: one per component")
                .hasSize(2);

        for (String required : components) {
            String forbidden = components.get(1 - components.indexOf(required));

            assertThat(branches)
                    .as("a body supplying only %s must satisfy one branch, and a body supplying both"
                            + " must satisfy neither -- so the branch requiring %s has to forbid %s",
                            required, required, forbidden)
                    .anySatisfy(branch -> {
                        Map<?, ?> arm = (Map<?, ?>) branch;
                        assertThat(namesOf(arm.get("required"))).containsExactly(required);
                        assertThat(namesOf(((Map<?, ?>) arm.get("not")).get("required")))
                                .containsExactly(forbidden);
                    });
        }
    }

    /**
     * Asserts that the zero-mark refusal sentence is one the contract publishes for this operation.
     *
     * @throws Exception if the document is absent from the classpath or unreadable
     */
    @Test
    @DisplayName("the zero-mark refusal sentence is in the contract's published message catalogue")
    void theZeroMarkRefusalIsPublished() throws Exception {
        assertThat(contractText())
                .as("a refused submission must carry a message the published catalogue lists")
                .contains("'" + ReportExecutionService.MESSAGE_NO_REPORT_TYPE_SELECTED + "'");
    }

    // WHY : Assumptions: the document is asserted NOT to promise exclusivity, by searching for the phrase
    //       that promised it. The reference resolves the first marked type and refuses nothing, so a
    //       document telling a client that exactly one mark is permitted describes a refusal the service
    //       does not make -- and a client written against it would refuse the request locally, reproducing
    //       the defect on the other side of the wire where no fix to this service could reach it.
    /**
     * Asserts that the submission contract states precedence rather than exclusivity.
     *
     * @throws Exception if the document is absent from the classpath or unreadable
     */
    @Test
    @DisplayName("the submission contract states report-type precedence, not exclusivity")
    void theSubmissionContractStatesPrecedence() throws Exception {
        String document = contractText();

        assertThat(document)
                .as("the reference accepts more than one mark and runs the first")
                .doesNotContain("Exactly one of the three type selectors")
                .doesNotContain("Exactly one of monthly, yearly and custom");
        assertThat(document)
                .as("the precedence order must be stated where a client can read it")
                .contains("monthly, yearly, custom");
    }

    /**
     * Reads a document sequence as member names.
     *
     * <p>Assumptions: the values are rendered through {@code String.valueOf} rather than cast, which
     * is the same conversion the sibling cases in this class apply to a sequence read from the
     * document. A member name is a string in every document this contract admits, and converting
     * rather than casting means a document that carried something else fails on the comparison that
     * follows -- naming the value it carried -- instead of on a cast inside the helper.</p>
     *
     * @param sequence the value read from the document, expected to be a sequence; must not be
     *     {@code null}, which is the caller's own assertion
     * @return the sequence's entries as strings, in document order, never {@code null}
     */
    private static List<String> namesOf(Object sequence) {
        List<String> names = new ArrayList<>();
        for (Object entry : (List<?>) sequence) {
            names.add(String.valueOf(entry));
        }
        return names;
    }

    /**
     * Reads the packaged contract as a mapping.
     *
     * @return the document root keyed by top-level member, never {@code null}
     * @throws Exception if the document is absent from the classpath or is not readable as a mapping
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> contractRoot() throws Exception {
        try (InputStream document = getClass().getResourceAsStream(CONTRACT_RESOURCE)) {
            assertThat(document).as("%s must be on the classpath", CONTRACT_RESOURCE).isNotNull();
            Map<String, Object> root = new Yaml().load(document);
            assertThat(root).as("%s must parse as a mapping", CONTRACT_RESOURCE).isNotNull();
            return root;
        }
    }

    /**
     * Reads the packaged contract as text.
     *
     * @return the whole document, decoded as UTF-8, never {@code null}
     * @throws Exception if the document is absent from the classpath or unreadable
     */
    private String contractText() throws Exception {
        try (InputStream document = getClass().getResourceAsStream(CONTRACT_RESOURCE)) {
            assertThat(document).as("%s must be on the classpath", CONTRACT_RESOURCE).isNotNull();
            return new String(document.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts one nested mapping, failing rather than returning null when it is absent.
     *
     * @param parent the mapping to read from
     * @param key the member to extract
     * @return the nested mapping, never {@code null}
     */
    private static Map<String, Object> mapping(Map<String, Object> parent, String key) {
        assertThat(parent).containsKey(key);
        return asMapping(parent.get(key), key);
    }

    /**
     * Narrows one parsed value to a mapping, naming the member on failure.
     *
     * @param value the parsed value
     * @param name the member name, used only in the failure description
     * @return the value as a mapping, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMapping(Object value, String name) {
        assertThat(value).as("%s must be a mapping", name).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    /**
     * Extracts one nested sequence of mappings.
     *
     * @param parent the mapping to read from
     * @param key the member to extract
     * @return the sequence entries, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sequence(Map<String, Object> parent, String key) {
        assertThat(parent).containsKey(key);
        assertThat(parent.get(key)).as("%s must be a sequence", key).isInstanceOf(List.class);
        return (List<Map<String, Object>>) parent.get(key);
    }

    /**
     * Reports whether a path-item member names an HTTP method rather than a description or a shared
     * parameter list.
     *
     * @param member the path-item member name
     * @return {@code true} when the member is one of the five methods this contract may declare
     */
    private static boolean isHttpMethod(String member) {
        return List.of("get", "post", "put", "delete", "patch").contains(member);
    }

    /**
     * Every published operation is served by a declared handler, and every handler is published.
     *
     * <p>Purpose: a path template and an HTTP method cross the wire as text, so a document declaring
     * one route and a controller mapping another both compile, both pass their own tests, and disagree
     * only in front of a client. This case is the comparison, and it is made in BOTH directions: a
     * documented route with no handler answers 404 to every caller that reads the document, and a
     * handler on an undocumented route is unreachable through the edge, which forwards only the
     * prefix this document declares.
     *
     * <p>Assumptions: the handlers are read by REFLECTION over their mapping annotations rather than
     * by standing up a web context. The declared route table is exactly what the annotations hold, and
     * reflection reaches it without a server, a data source or a token issuer -- none of which this
     * module can start from a unit test. The runtime counterpart, one mock request per route, belongs
     * to the sibling controller tests.
     *
     * @throws Exception if the packaged document is absent or unreadable
     */
    @Test
    @DisplayName("the published routes and the declared handler routes agree in both directions")
    void publishedRoutesAndDeclaredHandlersAgree() throws Exception {
        Set<String> published = new LinkedHashSet<>();
        for (Map.Entry<String, Object> pathEntry : mapping(contractRoot(), "paths").entrySet()) {
            Map<String, Object> item = asMapping(pathEntry.getValue(), pathEntry.getKey());
            for (String member : item.keySet()) {
                if (isHttpMethod(member)) {
                    published.add(member + " " + pathEntry.getKey());
                }
            }
        }

        assertThat(declaredRoutes())
                .as("every documented route is served and every served route is documented")
                .containsExactlyInAnyOrderElementsOf(published);
    }

    /**
     * Reads the route table the two controllers declare through their mapping annotations.
     *
     * <p>Assumptions: the class-level mapping supplies the root and the method-level mapping supplies
     * the remainder, which is how Spring composes the path, so the two are concatenated here rather
     * than either being read alone. A method carrying no path contributes the root itself, which is
     * how a collection-root operation is declared.
     *
     * @return the declared routes as {@code method path} pairs, never {@code null}
     */
    private static Set<String> declaredRoutes() {
        Set<String> routes = new LinkedHashSet<>();
        for (Class<?> controller : List.of(ReportController.class, StatementController.class)) {
            String root = controller.getAnnotation(RequestMapping.class).path()[0];
            for (Method handler : controller.getDeclaredMethods()) {
                GetMapping read = handler.getAnnotation(GetMapping.class);
                if (read != null) {
                    routes.add("get " + root + suffix(read.path()));
                }
                PostMapping write = handler.getAnnotation(PostMapping.class);
                if (write != null) {
                    routes.add("post " + root + suffix(write.path()));
                }
            }
        }
        return routes;
    }

    /**
     * Yields the method-level path of a handler, or the empty string when it declares none.
     *
     * @param declared the path member of a mapping annotation, which is empty when unset
     * @return the single declared sub-path, or the empty string
     */
    private static String suffix(String[] declared) {
        return declared.length == 0 ? "" : declared[0];
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
     *
     * @throws Exception if the packaged contract is absent from the classpath or does not parse, either
     *     of which means this census had nothing to examine
     */
    @Test
    @DisplayName("every operation declares the transport refusals the shared advice can produce")
    void theTransportRefusalsAreDeclaredWhereTheyCanOccur() throws Exception {
        Map<String, Object> document = contractRoot();
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
                // WHY : Refactoring Rationale: EIGHT, measured from the published document rather than
                //       carried forward. This figure was 5 and the contract has since grown three
                //       operations -- the statement transaction projection, the statement artifact
                //       selector and the execution status read -- each of which declares the refusals
                //       the loop above requires, so the only thing that was stale was the count. It is
                //       stated as an equality rather than a floor because that is the idiom every
                //       sibling contract test in this reactor uses, and because an equality is what
                //       makes an operation added without a published refusal shape fail here.
                .isEqualTo(8);

        Map<String, Object> declared = mapping(mapping(document, "components"), "responses");
        assertThat(declared)
                .as("each refusal is declared once and referenced, so the three cannot drift apart")
                .containsKeys("MethodNotAllowed", "NotAcceptable", "UnsupportedMediaType");
        assertThat(mapping(mapping(declared, "MethodNotAllowed"), "headers"))
                .as("a 405 names the methods the route does publish, which is what a client acts on")
                .containsKey("Allow");
    }
}
