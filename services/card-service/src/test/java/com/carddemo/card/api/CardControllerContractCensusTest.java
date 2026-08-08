package com.carddemo.card.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Asserts that every operation the published card contract declares has a handler behind it.
 *
 * <p>Refactoring Rationale: this test exists because of a specific, measured defect. The contract
 * declared five operations and the production tree held ZERO controllers, so every one of them answered
 * 404 while the document said otherwise -- and nothing in the build reported it, because a contract file
 * and a controller are unrelated artifacts as far as a compiler is concerned. A census is the smallest
 * thing that makes that class of gap impossible to reintroduce: it reads the operation identifiers out
 * of the contract itself and requires a mapped method for each.</p>
 *
 * <p>Alternatives Considered: asserting a fixed list of five names written into this file. Rejected
 * because it would drift in the direction that hides the defect -- an operation added to the contract
 * with no handler would leave this test passing, which is exactly the state being guarded against. The
 * count is derived rather than declared, so the guard strengthens automatically as the contract grows.</p>
 *
 * <p>Alternatives Considered: standing the web context up and probing each route. Rejected as a
 * different test rather than a better one: it would additionally exercise the security chain and the
 * message converters, so a failure would not localise to the missing-handler question this test asks.
 * Route behaviour is asserted by the module's other tests; this one asserts existence.</p>
 *
 * <p>Assumptions: the contract is read from the packaged classpath resource rather than from a path
 * under {@code src}, so the test asserts against the document that actually ships in the image. A test
 * reading the source tree would keep passing if the resource were excluded from packaging.</p>
 */
@DisplayName("the card contract-to-handler census")
class CardControllerContractCensusTest {

    /**
     * The classpath location of the published contract.
     */
    private static final String CONTRACT_RESOURCE = "/openapi/card-api.yaml";

    /**
     * Matches an {@code operationId} entry in the contract.
     *
     * <p>Assumptions: the identifier is matched rather than the path, because an identifier is the one
     * part of an operation guaranteed unique across the document, and it is what a generated client names
     * its method after. Matching paths would count {@code /api/v1/cards/{cardKey}} once while it carries
     * two operations.</p>
     */
    private static final Pattern OPERATION_ID = Pattern.compile("^\\s*operationId:\\s*(\\S+)\\s*$");

    /**
     * Reads every operation identifier the contract declares.
     *
     * @return the identifiers in document order, never empty
     * @throws IOException if the packaged contract cannot be read, which is itself a failure worth
     *     surfacing because the image would then ship without it
     * @throws IllegalStateException if the resource is absent from the classpath
     */
    private static Set<String> declaredOperations() throws IOException {
        Set<String> operations = new LinkedHashSet<>();
        try (InputStream stream =
                CardControllerContractCensusTest.class.getResourceAsStream(CONTRACT_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "the packaged contract " + CONTRACT_RESOURCE + " is absent from the classpath");
            }
            for (String line : new String(stream.readAllBytes()).split("\n")) {
                Matcher matcher = OPERATION_ID.matcher(line);
                if (matcher.matches()) {
                    operations.add(matcher.group(1));
                }
            }
        }
        return operations;
    }

    /**
     * Reads the names of every request-mapped method on the controller.
     *
     * <p>Assumptions: a method counts as a handler when it carries one of the three mapping annotations
     * this contract's operations use, or the generic one. Counting every public method instead would let
     * a helper satisfy the census, and counting only one annotation type would miss the operations
     * declared under the others.</p>
     *
     * @return the mapped method names, never {@code null}
     */
    private static Set<String> mappedHandlerNames() {
        Set<String> handlers = new LinkedHashSet<>();
        for (Method method : CardController.class.getDeclaredMethods()) {
            boolean mapped = method.isAnnotationPresent(GetMapping.class)
                    || method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(RequestMapping.class);
            if (mapped) {
                handlers.add(method.getName());
            }
        }
        return handlers;
    }

    /**
     * Every declared operation has a mapped method of the same name.
     *
     * <p>Assumptions: the match is by NAME, and naming each handler after its operation identifier is
     * therefore a convention this test enforces rather than merely observes. That is the point: a
     * name-for-name correspondence is checkable, whereas a correspondence by path and verb would have to
     * re-parse the contract's routing and would then be asserting this test's own parser.</p>
     *
     * @throws IOException if the packaged contract cannot be read
     */
    @Test
    @DisplayName("every published operation has a handler named after it")
    void everyOperationHasAHandler() throws IOException {
        Set<String> declared = declaredOperations();
        Set<String> handlers = mappedHandlerNames();

        assertThat(declared)
                .as("the contract must declare at least one operation, or this census proves nothing")
                .isNotEmpty();
        assertThat(handlers)
                .as("every operation the card contract declares must have a mapped handler; a declared"
                        + " operation with no handler answers 404 while the document says otherwise")
                .containsAll(declared);
    }

    /**
     * The five operations the contract is known to declare are each named explicitly.
     *
     * <p>Assumptions: this restates the five names alongside the derived census above, and the
     * duplication is deliberate rather than redundant. The census would still pass if the contract
     * resource were emptied or its identifiers renamed; naming the five pins the specific surface this
     * service was reviewed against, so a silent contraction of the contract fails here even though it
     * would satisfy the census.</p>
     *
     * @throws IOException if the packaged contract cannot be read
     */
    @Test
    @DisplayName("declares and handles exactly the five reviewed operations")
    void theFiveReviewedOperationsArePresent() throws IOException {
        assertThat(declaredOperations()).containsExactlyInAnyOrder(
                "listCards", "lookupCard", "getCard", "updateCard", "getAdminCardDetail");
        assertThat(mappedHandlerNames()).containsExactlyInAnyOrder(
                "listCards", "lookupCard", "getCard", "updateCard", "getAdminCardDetail");
    }

    /**
     * The controller's published path constants match the contract's paths.
     *
     * <p>Assumptions: asserted because the census matches on method names and would pass even if a
     * handler were mapped to the wrong address. These four constants are the addresses the security
     * configuration's path patterns are written against, so a divergence here would silently move an
     * operation out from under its authority rule.</p>
     */
    @Test
    @DisplayName("maps the four published addresses")
    void thePublishedAddressesAreMapped() {
        assertThat(CardController.CARDS_PATH).isEqualTo("/api/v1/cards");
        assertThat(CardController.LOOKUP_PATH).isEqualTo("/api/v1/cards/lookup");
        assertThat(CardController.CARD_PATH).isEqualTo("/api/v1/cards/{cardKey}");
        assertThat(CardController.ADMIN_CARD_PATH).isEqualTo("/api/v1/admin/cards/{cardKey}");
    }

    /**
     * The administrative disclosure has exactly one handler and it is the administrative one.
     *
     * <p>Assumptions: this is the disclosure boundary of the whole contract, so it is asserted directly
     * rather than left implied by the shape of the code. Only {@code getAdminCardDetail} may return a
     * full primary account number, and it is the only handler whose declared return type is not one of
     * the masked card shapes -- which is what makes the boundary visible in the signature list.</p>
     */
    @Test
    @DisplayName("returns an unmasked shape from the administrative handler only")
    void onlyTheAdministrativeHandlerReturnsAnUnmaskedShape() {
        Set<String> nonCardShapeHandlers = new LinkedHashSet<>();
        for (Method method : CardController.class.getDeclaredMethods()) {
            boolean mapped = method.isAnnotationPresent(GetMapping.class)
                    || method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class);
            if (mapped && !Arrays.asList("CardDetail", "PageResponse")
                    .contains(method.getReturnType().getSimpleName())) {
                nonCardShapeHandlers.add(method.getName());
            }
        }

        assertThat(nonCardShapeHandlers)
                .as("only the administrative read may answer with a shape carrying the full account"
                        + " number; every other handler must return a masked card shape")
                .containsExactly("getAdminCardDetail");
    }
}
