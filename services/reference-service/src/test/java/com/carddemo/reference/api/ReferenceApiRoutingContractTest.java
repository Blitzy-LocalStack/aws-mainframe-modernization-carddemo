package com.carddemo.reference.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds the published contract and the delivered controllers to each other, in both directions.
 *
 * <p>Purpose: the document at {@code src/main/resources/openapi/reference-api.yaml} and the seven
 * controllers of {@code com.carddemo.reference.api} describe one interface. This class compares them so
 * that a divergence fails the build instead of reaching a client, which is the only place the two are
 * ever compared.</p>
 *
 * <p>Assumptions: the comparison is bidirectional and both directions are needed for different reasons.
 * A declared operation with no handler is a promise the service does not keep; a handler with no declared
 * operation is a surface no client is told about and no gateway route is provisioned for. Checking one
 * direction only leaves the other free to drift.</p>
 *
 * <p>Assumptions: the delivered side is read from the annotations rather than from a started context, so
 * no database, queue or token is required and the check runs on every build.</p>
 */
@DisplayName("the reference API routing contract")
class ReferenceApiRoutingContractTest {

    /** The classpath location of the published contract. */
    private static final String CONTRACT_RESOURCE = "openapi/reference-api.yaml";

    /** The controllers that between them answer for the whole published surface. */
    private static final List<Class<?>> CONTROLLERS = List.of(
            TransactionTypeController.class,
            TransactionCategoryController.class,
            DisclosureGroupController.class,
            AddressLookupController.class,
            DateEvaluationController.class,
            ReferenceMaintenanceController.class);

    /** The number of operations the contract declares, stated so a silent removal is caught. */
    private static final int EXPECTED_OPERATION_COUNT = 19;

    /**
     * Every operation the contract declares has a handler at the same method and path.
     *
     * @throws Exception if the contract resource cannot be read
     * @throws AssertionError if a declared operation has no matching handler
     */
    @Test
    @DisplayName("every declared operation has a handler at the same method and path")
    void everyDeclaredOperationHasAHandler() throws Exception {
        assertThat(deliveredRoutes().keySet()).containsAll(contractRoutes().keySet());
    }

    /**
     * Every handler corresponds to an operation the contract declares.
     *
     * <p>Assumptions: this is the direction that catches a surface no client is told about. A handler the
     * document does not declare also has no gateway route provisioned for it, so it would answer in a
     * test and 404 in a deployment.</p>
     *
     * @throws Exception if the contract resource cannot be read
     * @throws AssertionError if a handler has no matching declared operation
     */
    @Test
    @DisplayName("every handler corresponds to a declared operation")
    void everyHandlerCorrespondsToADeclaredOperation() throws Exception {
        assertThat(contractRoutes().keySet()).containsAll(deliveredRoutes().keySet());
    }

    /**
     * The contract declares exactly the expected number of operations.
     *
     * <p>Assumptions: the count is asserted as well as the two set comparisons, because those two would
     * both still pass if an operation were deleted from the document AND its handler deleted with it. The
     * count is what makes a silent narrowing of the surface visible.</p>
     *
     * @throws Exception if the contract resource cannot be read
     * @throws AssertionError if the operation count has changed
     */
    @Test
    @DisplayName("the contract declares exactly nineteen operations")
    void theContractDeclaresExactlyNineteenOperations() throws Exception {
        assertThat(contractRoutes()).hasSize(EXPECTED_OPERATION_COUNT);
    }

    /**
     * Every declared operation identifier is distinct.
     *
     * @throws Exception if the contract resource cannot be read
     * @throws AssertionError if two operations share an identifier
     */
    @Test
    @DisplayName("every declared operation identifier is distinct")
    void everyDeclaredOperationIdentifierIsDistinct() throws Exception {
        List<String> declared = new ArrayList<>(contractRoutes().values());
        assertThat(new TreeSet<>(declared)).hasSameSizeAs(declared);
    }

    /**
     * Each handler method is named exactly as the operation it answers is identified.
     *
     * <p>Assumptions: equality rather than a looser correspondence, because the name is then the one
     * place the two artifacts are tied together -- a reader holding an operation identifier can find its
     * handler, and a rename on either side fails here rather than merely becoming confusing.</p>
     *
     * @throws Exception if the contract resource cannot be read
     * @throws AssertionError if a handler name differs from its operation identifier
     */
    @Test
    @DisplayName("each handler is named exactly as its operation is identified")
    void eachHandlerIsNamedAsItsOperationIsIdentified() throws Exception {
        Map<String, String> contract = contractRoutes();
        Map<String, String> delivered = deliveredRoutes();
        Set<String> mismatched = new LinkedHashSet<>();
        for (Map.Entry<String, String> route : delivered.entrySet()) {
            String declaredId = contract.get(route.getKey());
            if (declaredId != null && !declaredId.equals(route.getValue())) {
                mismatched.add(route.getKey() + ": handler " + route.getValue()
                        + " answers operation " + declaredId);
            }
        }
        assertThat(mismatched).isEmpty();
    }

    /**
     * Reads the contract's operations as a map of route to operation identifier.
     *
     * @return route strings of the form {@code METHOD path} mapped to their operation identifier
     * @throws Exception if the contract resource cannot be read
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> contractRoutes() throws Exception {
        Map<String, Object> document;
        try (InputStream stream = ReferenceApiRoutingContractTest.class.getClassLoader()
                .getResourceAsStream(CONTRACT_RESOURCE)) {
            assertThat(stream).as("contract resource %s", CONTRACT_RESOURCE).isNotNull();
            document = new Yaml().load(stream);
        }
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        Map<String, String> routes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> operations = (Map<String, Object>) path.getValue();
            for (Map.Entry<String, Object> operation : operations.entrySet()) {
                String method = operation.getKey().toUpperCase(java.util.Locale.ROOT);
                if (!Set.of("GET", "POST", "PUT", "DELETE", "PATCH").contains(method)) {
                    continue;
                }
                Map<String, Object> body = (Map<String, Object>) operation.getValue();
                routes.put(method + " " + path.getKey(), String.valueOf(body.get("operationId")));
            }
        }
        return routes;
    }

    /**
     * Reads the delivered handlers as a map of route to handler method name.
     *
     * <p>Assumptions: a class-level mapping is combined with a method-level one where both are present,
     * and a controller declaring only method-level full paths is handled by the same code. Both shapes
     * are in use in this package.</p>
     *
     * @return route strings of the form {@code METHOD path} mapped to their handler method name
     */
    private static Map<String, String> deliveredRoutes() {
        Map<String, String> routes = new LinkedHashMap<>();
        for (Class<?> controller : CONTROLLERS) {
            RequestMapping classMapping = controller.getAnnotation(RequestMapping.class);
            String prefix = classMapping == null || classMapping.value().length == 0
                    ? ""
                    : classMapping.value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                addRoute(routes, prefix, method);
            }
        }
        return routes;
    }

    /**
     * Adds one handler's route, if the method carries a request mapping at all.
     *
     * @param routes the accumulating map
     * @param prefix the class-level path prefix, possibly empty
     * @param method the candidate handler
     */
    private static void addRoute(Map<String, String> routes, String prefix, Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) {
            routes.put("GET " + join(prefix, get.path()), method.getName());
            return;
        }
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) {
            routes.put("POST " + join(prefix, post.path()), method.getName());
            return;
        }
        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) {
            routes.put("PUT " + join(prefix, put.path()), method.getName());
            return;
        }
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        if (delete != null) {
            routes.put("DELETE " + join(prefix, delete.path()), method.getName());
        }
    }

    /**
     * Joins a class-level prefix and a method-level path into the full route path.
     *
     * @param prefix the class-level prefix, possibly empty
     * @param paths the method-level paths, possibly empty
     * @return the full path
     */
    private static String join(String prefix, String[] paths) {
        String suffix = paths.length == 0 ? "" : paths[0];
        return prefix + suffix;
    }
}
