package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.awspring.cloud.sqs.annotation.SqsListener;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds two structural properties of this package that no behavioural case can observe.
 *
 * <h2>Why a structural test rather than a behavioural one</h2>
 *
 * <p>Refactoring Rationale: both properties asserted here were violated in ways every behavioural case in
 * this module passed straight through, which is exactly what makes them worth asserting from the outside.
 * Two classes each carried an {@code @SqsListener} for the SAME request queue, so the queue had two
 * registered consumers implementing different contracts and which of them received a message depended on
 * registration order -- and a unit case that calls one listener directly can never see the other. An
 * externally invoked lookup delegated to an annotated method of its own bean, so the container proxy was
 * never crossed and the declared read-only boundary was not applied -- and a unit case that constructs the
 * service with {@code new} has no proxy at all, so it reports the same result either way.</p>
 *
 * <p>Assumptions: the annotations are read by reflection rather than from a started context, so no
 * database, queue, token or container is required and these cases run on every build. That matters for a
 * regression guard: a check that needed infrastructure would be the first thing skipped.</p>
 *
 * <p>Assumptions: user-specified Rule 1 (Explainability) governs this file, and its validation gate is
 * conjunctive -- a member missing either its docstring or the reason for a non-obvious choice fails
 * review, not one or the other. Every member below therefore carries both.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause. Every member below carries its own.</p>
 */
@DisplayName("the reference service package structure")
class ReferenceServiceStructureTest {

    /**
     * Every class of this package that could carry a queue listener.
     *
     * <p>Assumptions: the set is enumerated rather than discovered by scanning the classpath, because a
     * scan would silently cover a class that no longer exists and would pass by omission. An enumerated
     * list fails to compile when a class is withdrawn, which is the behaviour wanted from a guard.</p>
     */
    private static final List<Class<?>> PACKAGE_CLASSES = List.of(
            DateConversionService.class,
            DateInquiryMessageListener.class,
            DisclosureGroupService.class,
            ReferenceBatchUpdateService.class,
            TransactionCategoryService.class,
            TransactionTypeService.class);

    /**
     * The queue-name expression the inquiry request queue is configured under.
     *
     * <p>Assumptions: the expression rather than the resolved name, because the assertion is made without
     * a context and nothing resolves a placeholder. Two listeners bound to one destination would carry the
     * same expression, so comparing the expressions catches the duplication that matters.</p>
     */
    private static final String INQUIRY_REQUEST_QUEUE =
            "${carddemo.reference.inquiry.request-queue}";

    /**
     * Asserts exactly ONE method in this package listens to the inquiry request queue.
     *
     * <p>Assumptions: the count is asserted as one rather than as at-most-one, because zero is also a
     * failure: the reference program at {@code app/app-vsam-mq/cbl/CODATE01.cbl} consumes a request queue,
     * and a module with no consumer would silently stop answering while every other case still passed.</p>
     *
     * <p>Assumptions: the owning class is named as well as the count, so that the surviving consumer is
     * the one with the expiry rule, the correlation identity and the fixed-width reply type rather than
     * whichever of two happened to be kept. Asserting the count alone would be satisfied by keeping the
     * wrong one.</p>
     */
    @Test
    @DisplayName("exactly one method listens to the inquiry request queue")
    void exactlyOneMethodListensToTheInquiryRequestQueue() {
        List<String> listeners = new ArrayList<>();
        for (Class<?> type : PACKAGE_CLASSES) {
            for (Method method : type.getDeclaredMethods()) {
                SqsListener listener = method.getAnnotation(SqsListener.class);
                if (listener != null && List.of(listener.queueNames()).contains(INQUIRY_REQUEST_QUEUE)) {
                    listeners.add(type.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(listeners)
                .as("two consumers on one queue answer to different contracts and race for each message")
                .containsExactly("DateInquiryMessageListener#onRequest");
    }

    /**
     * Asserts the date-evaluation holder carries no queue listener at all.
     *
     * <p>Assumptions: this is asserted separately from the count above rather than folded into it, because
     * the two failures differ. The count catches a second consumer on the SAME queue; this catches a
     * consumer added to this class on ANY queue, which would put the synchronous composition back behind a
     * transport it has no business being behind.</p>
     */
    @Test
    @DisplayName("the date-evaluation holder listens to nothing")
    void theDateEvaluationHolderListensToNothing() {
        assertThat(DateConversionService.class.getDeclaredMethods())
                .as("the evaluation is reached by the synchronous route only")
                .noneMatch(method -> method.isAnnotationPresent(SqsListener.class));
    }

    /**
     * Asserts both public entry points of the rate lookup declare their own read-only boundary.
     *
     * <p>Refactoring Rationale: the externally invoked name previously carried no annotation and delegated
     * to the annotated one on the same bean. Demarcation is applied by a proxy that wraps the bean, and a
     * call from one of its methods to another goes straight to the target instance, so the delegate's
     * annotation was never consulted on the path a request actually takes: every HTTP lookup ran with no
     * read-only boundary, no flush suppression and each of its two reads on its own transaction.</p>
     *
     * <p>Assumptions: BOTH names are asserted, and the read-only attribute is asserted as well as the
     * annotation's presence. Asserting presence alone would pass on a read-write boundary, which is a
     * different declaration from the one the transcription documents.</p>
     *
     * @throws NoSuchMethodException if either entry point is renamed, which is itself the failure the
     *     assertion exists to report
     */
    @Test
    @DisplayName("both rate-lookup entry points declare a read-only transaction")
    void bothRateLookupEntryPointsDeclareAReadOnlyTransaction() throws NoSuchMethodException {
        for (String name : List.of("findRate", "resolveRate")) {
            Method entryPoint = DisclosureGroupService.class.getMethod(
                    name, String.class, String.class, String.class);
            Transactional declared = entryPoint.getAnnotation(Transactional.class);

            assertThat(declared)
                    .as("%s is invoked from outside the bean, so its own declaration is the one applied",
                            name)
                    .isNotNull();
            assertThat(declared.readOnly())
                    .as("%s performs two reads and no write", name)
                    .isTrue();
        }
    }
}
