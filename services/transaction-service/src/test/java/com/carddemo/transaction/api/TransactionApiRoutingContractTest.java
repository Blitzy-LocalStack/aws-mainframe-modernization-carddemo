package com.carddemo.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.security.JwtRoleConverter;
import com.carddemo.transaction.config.SecurityConfig;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the published contract and the delivered routes to each other in both directions.
 *
 * <p>Purpose: the module's contract declared four operations while its source tree held no controller at
 * all, so every one of them was unreachable and nothing failed. This test makes that state impossible to
 * reach again: a published operation with no delivered route fails it, and a delivered route with no
 * published operation fails it too.</p>
 *
 * <p>Assumptions: the comparison is made against the mapping ANNOTATIONS rather than against a started
 * application context. A context for this module needs a resolvable token issuer and a reachable database,
 * neither of which exists on a build agent, and the annotations are what the framework itself builds its
 * route table from -- so reading them tests the same facts without either dependency.</p>
 */
@DisplayName("the published transaction contract and the delivered routes")
class TransactionApiRoutingContractTest {

    /** The published contract, read from the module's own resources rather than from a copy. */
    private static final String CONTRACT = "openapi/transaction-api.yaml";

    /** The two controllers that together deliver the whole published surface. */
    private static final List<Class<?>> CONTROLLERS =
            List.of(TransactionController.class, BillPaymentController.class);

    /** The operation-level extension field the authority model publishes its values under. */
    private static final String AUTHORITY_FIELD = "x-required-authority";

    /** The document-level object declaring the field, its admitted values and who enforces them. */
    private static final String AUTHORITY_MODEL = "x-authority-model";

    /**
     * A single concrete path segment substituted for every template variable.
     *
     * <p>Assumptions: the only template variable in this contract is the transaction identifier, and a
     * request matcher selects on segment structure rather than on the value in a segment, so one
     * substitution serves every path. Sixteen digits is used all the same, because a value of the
     * published width keeps the substituted path readable as the thing it stands for.</p>
     */
    private static final String SAMPLE_TRANSACTION_ID = "0000000000683580";

    /**
     * Every published operation is delivered by a controller method, and every delivered route is
     * published.
     */
    @Test
    @DisplayName("agree exactly, in both directions")
    void publishedOperationsAndDeliveredRoutesAgree() {
        assertThat(deliveredRoutes()).containsExactlyInAnyOrderElementsOf(publishedRoutes());
    }

    /**
     * The contract publishes exactly the five operations the four migrated programs need.
     *
     * <p>Refactoring Rationale: five rather than four, because {@code COTRN02C} contributes MORE THAN
     * ONE operation. Its Enter path is the capture at {@code app/cbl/COTRN02C.cbl} line 172 and its PF5
     * path is the copy-last action at lines 146 and 147, performing {@code COPY-LAST-TRAN-DATA} at line
     * 471. The second was transcribed in the service and published nowhere, so the count and the surface
     * were both corrected here rather than the transcription being deleted.</p>
     *
     * <p>⚠️ Refactoring Rationale: FIVE and not six. A separate read-only {@code lookupLastTransaction}
     * operation was published beside the copy for a time, on the ground that the PF5 paragraph splits at
     * its own seam -- line 473 validates only the key fields, lines 480 to 493 paint eleven values into
     * the form, and line 495 is where the capture begins -- and that one operation binding the capture
     * request could not be called from an empty form, its eleven data members being required. The
     * premise stopped holding when the copy operation was given its OWN request shape:
     * {@code CopyLastRequest} declares the two key components and the confirmation only, so the copy is
     * callable from an empty form, and its withheld answer carries {@code CopiedTransactionData} -- the
     * eleven painted values together with the resolved account and card. That single operation is
     * therefore both halves: the read that paints and, on a confirming turn, the capture. Two operations
     * over one paragraph would leave a client choosing which of two documents it is confirming, which is
     * the split the reference does not have. The count is asserted rather than inferred so that
     * publishing a further operation is a deliberate edit here as well.</p>
     */
    @Test
    @DisplayName("cover exactly five operations")
    void contractPublishesFiveOperations() {
        assertThat(publishedRoutes()).hasSize(5);
    }

    /** No two published operations share an identifier, so each names one operation unambiguously. */
    @Test
    @DisplayName("carry distinct operation identifiers")
    void operationIdentifiersAreDistinct() {
        List<String> identifiers = new ArrayList<>();
        eachOperation((method, path, operation) -> identifiers.add((String) operation.get("operationId")));
        assertThat(identifiers).doesNotHaveDuplicates().hasSize(5);
    }

    /** The delivered method names are the published operation identifiers, so the two read alike. */
    @Test
    @DisplayName("name their delivered methods after their operation identifiers")
    void operationIdentifiersMatchMethodNames() {
        Set<String> identifiers = new LinkedHashSet<>();
        eachOperation((method, path, operation) -> identifiers.add((String) operation.get("operationId")));

        Set<String> methodNames = new LinkedHashSet<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method candidate : controller.getDeclaredMethods()) {
                if (candidate.isAnnotationPresent(GetMapping.class)
                        || candidate.isAnnotationPresent(PostMapping.class)) {
                    methodNames.add(candidate.getName());
                }
            }
        }
        assertThat(methodNames).containsExactlyInAnyOrderElementsOf(identifiers);
    }

    /**
     * Asserts that the authority model publishes exactly the two group names the shared converter
     * recognises, and that every operation declares one of them.
     *
     * <p>Purpose: this contract was the only one in the reactor carrying no authority metadata at all,
     * which left the question of who may reach these four operations answerable only from prose that no
     * build reads. The model is compared against the converter's own constants rather than against
     * literals, so a group rename in the shared kernel cannot leave this document naming a group that no
     * longer exists.</p>
     */
    @Test
    @DisplayName("declare a required authority drawn from the published model")
    void everyOperationDeclaresAPublishedAuthority() {
        List<String> admitted = admittedAuthorities();
        assertThat(admitted)
                .as("the model must publish exactly the two authorities the shared converter mints")
                .containsExactlyInAnyOrder(
                        JwtRoleConverter.USER_AUTHORITY, JwtRoleConverter.ADMIN_AUTHORITY);

        List<String> undeclared = new ArrayList<>();
        eachOperation((method, path, operation) -> {
            Object declared = operation.get(AUTHORITY_FIELD);
            if (!admitted.contains(String.valueOf(declared))) {
                undeclared.add(method + " " + path + " declares " + declared);
            }
        });
        assertThat(undeclared)
                .as("every operation must declare %s with an admitted value", AUTHORITY_FIELD)
                .isEmpty();
    }

    // WHY : Assumptions: this is the assertion the metadata exists for. It compares two independently
    //       authored statements of one rule - the field on each operation, and the authorization
    //       manager the filter chain actually installs - so neither can be edited alone. Comparing the
    //       field against a literal instead would only restate the contract back to itself.
    // WHY : Refactoring Rationale: the enforced side is obtained by DRIVING the manager rather than by
    //       reading a rule table, because this module has no rule table to read: its chain permits the
    //       health probe, confines the management namespace to loopback and authorizes every remaining
    //       request through one hasAnyAuthority manager over both groups. Adding a lookup method to
    //       production code purely so a test could ask it a question was rejected - it would be an API
    //       no caller uses, and it would be a third statement of the rule rather than a check of the
    //       two that exist.
    /**
     * Asserts that a caller holding only the declared authority is admitted by the chain's own
     * authorization manager, that the other group is admitted too, and that a caller holding neither is
     * refused.
     *
     * <p>Assumptions: {@code carddemo-user} on an operation means any authenticated caller of this
     * context may reach it, which is why holding either group must be sufficient. If a future revision
     * narrowed one of these operations to the administrative group, the token carrying only
     * {@code carddemo-user} would still be admitted by this chain and this assertion would fail --
     * which is the point of asserting the sufficiency of both rather than only the declared one.</p>
     */
    @Test
    @DisplayName("declare the authority their own filter chain enforces")
    void declaredAuthorityIsTheAuthorityTheChainEnforces() {
        List<String> mismatches = new ArrayList<>();
        eachOperation((method, path, operation) -> {
            String declared = String.valueOf(operation.get(AUTHORITY_FIELD));
            String concrete = concretePath(path);
            if (!JwtRoleConverter.USER_AUTHORITY.equals(declared)) {
                mismatches.add(method + " " + path + " declares " + declared
                        + ", but this chain authorizes every business route with hasAnyAuthority over"
                        + " both groups, which is what carddemo-user asserts");
                return;
            }
            if (!isGranted(concrete, JwtRoleConverter.USER_AUTHORITY)) {
                mismatches.add(method + " " + path + " declares " + declared
                        + ", but a token carrying only that authority is refused");
            }
            if (!isGranted(concrete, JwtRoleConverter.ADMIN_AUTHORITY)) {
                mismatches.add(method + " " + path
                        + " refuses the administrative group, which no operation of this context does");
            }
            if (isGranted(concrete, "carddemo-nobody")) {
                mismatches.add(method + " " + path
                        + " admits a caller holding neither published group");
            }
        });
        assertThat(mismatches)
                .as("the published authority and the enforced authority must agree per operation")
                .isEmpty();
    }

    // WHY : Assumptions: this guards the one way the agreement above could be true and still mislead.
    //       The operator patterns are authorized by NETWORK POSITION rather than by authority, so a
    //       business path that one of them also matched would be reachable only from the task's own
    //       loopback while its contract still declared a group - and the assertion above would pass,
    //       because it drives the business manager directly rather than resolving the chain's order.
    /**
     * Asserts that no operator pattern of the filter chain matches a published business path.
     */
    @Test
    @DisplayName("sit outside every loopback-confined operator pattern")
    void noPublishedPathIsConfinedToLoopback() {
        List<String> confined = new ArrayList<>();
        for (String pattern : SecurityConfig.operatorPaths()) {
            eachOperation((method, path, operation) -> {
                MockHttpServletRequest request =
                        new MockHttpServletRequest(method.toUpperCase(java.util.Locale.ROOT),
                                concretePath(path));
                if (PathPatternRequestMatcher.pathPattern(pattern).matches(request)) {
                    confined.add(method + " " + path + " is matched by operator pattern " + pattern);
                }
            });
        }
        assertThat(confined)
                .as("an operator pattern matching a business path would confine it to loopback while"
                        + " its contract still declared a group")
                .isEmpty();
    }

    /**
     * Asserts that the authority model names files that exist, so its enforcement and assertion claims
     * can be followed.
     *
     * <p>Assumptions: the two values are repository-root-relative paths, so the root is located by
     * walking up from the working directory rather than assumed, which lets this pass whether the module
     * is built alone or from the reactor.</p>
     */
    @Test
    @DisplayName("name an enforcing class and an asserting test that both exist")
    void theAuthorityModelNamesFilesThatExist() {
        Map<String, Object> model = authorityModel();
        for (String claim : List.of("enforcedBy", "assertedBy")) {
            String relative = String.valueOf(model.get(claim)).trim();
            assertThat(repositoryRoot().resolve(relative))
                    .as("%s names %s, which must exist for the claim to be checkable", claim, relative)
                    .exists();
        }
        assertThat(String.valueOf(model.get("assertedBy")).trim())
                .as("the asserting test named must be this one")
                .endsWith(TransactionApiRoutingContractTest.class.getSimpleName() + ".java");
    }

    /**
     * Reports whether the chain's business authorization manager admits a caller holding one authority.
     *
     * @param concretePath the request path, with every template variable already substituted
     * @param authority the single authority the caller's token carries
     * @return {@code true} when the manager grants the request
     */
    private static boolean isGranted(String concretePath, String authority) {
        Authentication caller = new TestingAuthenticationToken("caller", "n/a", authority);
        RequestAuthorizationContext context =
                new RequestAuthorizationContext(new MockHttpServletRequest("POST", concretePath));
        return SecurityConfig.businessAccess().authorize(() -> caller, context).isGranted();
    }

    /**
     * Substitutes one concrete segment for every template variable in a published path.
     *
     * @param pathTemplate the published path template
     * @return a concrete request path a matcher and a manager can both be evaluated against
     */
    private static String concretePath(String pathTemplate) {
        return pathTemplate.replaceAll("\\{[^}]+}", SAMPLE_TRANSACTION_ID);
    }

    /**
     * Reads the authority values the document admits.
     *
     * @return the admitted authority names, in document order, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static List<String> admittedAuthorities() {
        return (List<String>) authorityModel().get("values");
    }

    /**
     * Reads the document-level authority model.
     *
     * @return the model object, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> authorityModel() {
        Map<String, Object> model = (Map<String, Object>) contract().get(AUTHORITY_MODEL);
        assertThat(model).as("the contract must declare %s", AUTHORITY_MODEL).isNotNull();
        return model;
    }

    /**
     * Locates the repository root by walking up from the working directory.
     *
     * @return the first ancestor holding both {@code infra} and {@code services}, never {@code null}
     * @throws IllegalStateException if no ancestor qualifies
     */
    private static java.nio.file.Path repositoryRoot() {
        java.nio.file.Path candidate = java.nio.file.Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (java.nio.file.Files.isDirectory(candidate.resolve("infra"))
                    && java.nio.file.Files.isDirectory(candidate.resolve("services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of the working directory holds both infra/ and services/");
    }

    /**
     * Reads the published method-and-path pairs from the contract document.
     *
     * @return one entry per published operation, never {@code null}
     */
    private static Set<String> publishedRoutes() {
        Set<String> routes = new LinkedHashSet<>();
        eachOperation((method, path, operation) -> routes.add(method + " " + path));
        return routes;
    }

    /**
     * Reads the delivered method-and-path pairs from the controllers' own mapping annotations.
     *
     * <p>Assumptions: both annotation aliases are read for every mapping. The framework's shorthand form
     * populates {@code value()} and leaves {@code path()} empty, so a reader consulting one alias alone
     * reports the wrong path for whichever mappings happen to be written in the other form.</p>
     *
     * @return one entry per delivered route, never {@code null}
     */
    private static Set<String> deliveredRoutes() {
        Set<String> routes = new LinkedHashSet<>();
        for (Class<?> controller : CONTROLLERS) {
            RequestMapping base = controller.getAnnotation(RequestMapping.class);
            String prefix = subPath(base.path(), base.value());
            for (Method candidate : controller.getDeclaredMethods()) {
                GetMapping read = candidate.getAnnotation(GetMapping.class);
                if (read != null) {
                    routes.add("get " + prefix + subPath(read.path(), read.value()));
                }
                PostMapping write = candidate.getAnnotation(PostMapping.class);
                if (write != null) {
                    routes.add("post " + prefix + subPath(write.path(), write.value()));
                }
            }
        }
        return routes;
    }

    /**
     * Returns the single configured path from whichever of the two annotation aliases carries it.
     *
     * @param path the {@code path} alias, possibly empty
     * @param value the {@code value} alias, possibly empty
     * @return the configured path, or the empty string when neither alias carries one
     */
    private static String subPath(String[] path, String[] value) {
        if (path.length > 0) {
            return path[0];
        }
        return value.length > 0 ? value[0] : "";
    }

    /**
     * Visits every published operation in the contract document.
     *
     * @param visitor the visitor to call once per operation, never {@code null}
     * @throws AssertionError if the contract resource is absent or unreadable
     */
    @SuppressWarnings("unchecked")
    private static void eachOperation(OperationVisitor visitor) {
        Map<String, Object> paths = (Map<String, Object>) contract().get("paths");
        assertThat(paths).as("the contract must publish paths").isNotEmpty();
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> operations = (Map<String, Object>) path.getValue();
            for (Map.Entry<String, Object> operation : operations.entrySet()) {
                if ("get".equals(operation.getKey()) || "post".equals(operation.getKey())) {
                    visitor.visit(operation.getKey(), path.getKey(),
                            (Map<String, Object>) operation.getValue());
                }
            }
        }
    }

    /**
     * Reads the published contract document from the module's own resources.
     *
     * <p>Assumptions: it is read from the class path rather than from a path on disk, so the copy under
     * test is the copy that ships in the artifact. A file read would pass against a source tree whose
     * resource had not been copied.</p>
     *
     * @return the whole document, never {@code null}
     * @throws AssertionError if the contract resource is absent or unreadable
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> contract() {
        try (InputStream source = TransactionApiRoutingContractTest.class.getClassLoader()
                .getResourceAsStream(CONTRACT)) {
            assertThat(source).as("the published contract must be on the classpath").isNotNull();
            return (Map<String, Object>) new Yaml().load(source);
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("the published contract could not be read", unreadable);
        }
    }

    /** The visitor the contract walk calls once per published operation. */
    @FunctionalInterface
    private interface OperationVisitor {

        /**
         * Accepts one published operation.
         *
         * @param method the HTTP method, lower case as the document spells it
         * @param path the published path template
         * @param operation the operation object itself
         */
        void visit(String method, String path, Map<String, Object> operation);
    }
}
