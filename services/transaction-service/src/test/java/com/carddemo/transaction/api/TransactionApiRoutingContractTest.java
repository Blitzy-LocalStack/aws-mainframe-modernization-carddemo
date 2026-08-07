package com.carddemo.transaction.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    /**
     * Every published operation is delivered by a controller method, and every delivered route is
     * published.
     */
    @Test
    @DisplayName("agree exactly, in both directions")
    void publishedOperationsAndDeliveredRoutesAgree() {
        assertThat(deliveredRoutes()).containsExactlyInAnyOrderElementsOf(publishedRoutes());
    }

    /** The contract publishes exactly the four operations the four migrated programs need. */
    @Test
    @DisplayName("cover exactly four operations")
    void contractPublishesFourOperations() {
        assertThat(publishedRoutes()).hasSize(4);
    }

    /** No two published operations share an identifier, so each names one operation unambiguously. */
    @Test
    @DisplayName("carry distinct operation identifiers")
    void operationIdentifiersAreDistinct() {
        List<String> identifiers = new ArrayList<>();
        eachOperation((method, path, operation) -> identifiers.add((String) operation.get("operationId")));
        assertThat(identifiers).doesNotHaveDuplicates().hasSize(4);
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
        Map<String, Object> document;
        try (InputStream source = TransactionApiRoutingContractTest.class.getClassLoader()
                .getResourceAsStream(CONTRACT)) {
            assertThat(source).as("the published contract must be on the classpath").isNotNull();
            document = new Yaml().load(source);
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("the published contract could not be read", unreadable);
        }

        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
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
