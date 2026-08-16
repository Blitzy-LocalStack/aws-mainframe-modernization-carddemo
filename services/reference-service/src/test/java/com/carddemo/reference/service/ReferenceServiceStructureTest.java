package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds three structural properties of this package that no behavioural case can observe.
 *
 * <h2>Why a structural test rather than a behavioural one</h2>
 *
 * <p>Refactoring Rationale: the properties asserted here were violated in ways every behavioural case in
 * this module passed straight through, which is exactly what makes them worth asserting from the outside.
 * Two classes each carried an {@code @SqsListener} for the SAME request queue, so the queue had two
 * registered consumers implementing different contracts and which of them received a message depended on
 * registration order -- and a unit case that calls one listener directly can never see the other. An
 * externally invoked lookup delegated to an annotated method of its own bean, so the container proxy was
 * never crossed and the declared read-only boundary was not applied -- and a unit case that constructs the
 * service with {@code new} has no proxy at all, so it reports the same result either way.</p>
 *
 * <p>⚠️ Refactoring Rationale: the queue case has been INVERTED. It asserted that exactly one method in
 * this package listened to the inquiry request queue; it now asserts that NO method in this module listens
 * to any queue at all, and the reason is a topology fact rather than a change of taste. The baseline drives
 * BOTH of its inquiry programs from ONE request destination --
 * {@code DEFINE QLOCAL('CARDDEMO.REQUEST.QUEUE')} at {@code app/app-vsam-mq/README.md} L53, aliased to
 * CICS as {@code MQQUEUE(CARDREQ)} at L71 -- and the migrated topology provisions that one queue rather
 * than one per consumer. A queue admits exactly one OWNING consumer, because a receive hides the message
 * from every other consumer rather than delivering a copy to each, so the old case's own reasoning ("two
 * consumers on one queue race for each message") applies across MODULES exactly as it applied within one.
 * The owner is {@code com.carddemo.account.service.InquiryMessageListener}, which dispatches on the
 * request's four-character function code and renders the date answer from
 * {@code com.carddemo.common.codec.DateInquiryReplyCodec}. Alternatives Considered: deleting the queue case
 * outright once the consumer left. Rejected because an absence that nothing measures is an absence that
 * gets undone -- re-adding a listener here would reinstate the race against another module, where it is
 * harder to see than it was within one package.</p>
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
            AddressLookupService.class,
            DateConversionService.class,
            DisclosureGroupService.class,
            ReferenceBatchUpdateService.class,
            TransactionCategoryService.class,
            TransactionTypeService.class);

    /**
     * The annotation type a queue binding would be declared with, named by its binary name.
     *
     * <p>Assumptions: the type is looked up by NAME rather than imported, and that is the load-bearing
     * part. Importing {@code io.awspring.cloud.sqs.annotation.SqsListener} would require the messaging
     * starter on this module's test classpath, and the starter is withdrawn from this module's
     * dependencies precisely because nothing here consumes a queue -- so an import would reinstate the
     * dependency this case exists to prove unnecessary, and the case would then pass because of its own
     * import. A name lookup that finds nothing is itself part of the assertion.</p>
     */
    private static final String QUEUE_LISTENER_ANNOTATION =
            "io.awspring.cloud.sqs.annotation.SqsListener";

    /**
     * Asserts NO method in this package binds a queue listener, on any queue.
     *
     * <p>Assumptions: zero is the correct count for this module rather than a regrettable one. The
     * reference program {@code app/app-vsam-mq/cbl/CODATE01.cbl} does consume a request queue, and that
     * queue is consumed in the migration -- by the single owner of the ONE shared request destination the
     * baseline defines, in the account context, which dispatches on the request's function code. What
     * stays here is the date EVALUATION of {@code app/cbl/CSUTLDTC.cbl}, reached synchronously, and the
     * baseline keeps those two apart as well: a search for {@code CSUTLDTC} across all 524 lines of
     * {@code CODATE01.cbl} returns zero occurrences.</p>
     *
     * <p>Assumptions: the sweep is over every annotation on every declared method rather than over one
     * annotation type, because it has to hold without the annotation type being present. A binding
     * re-introduced here would arrive together with its starter, so the annotation would then resolve and
     * this comparison by name would find it.</p>
     */
    @Test
    @DisplayName("no method in this package binds a queue listener")
    void noMethodInThisPackageBindsAQueueListener() {
        List<String> bindings = new ArrayList<>();
        for (Class<?> type : PACKAGE_CLASSES) {
            for (Method method : type.getDeclaredMethods()) {
                for (Annotation declared : method.getAnnotations()) {
                    if (QUEUE_LISTENER_ANNOTATION.equals(declared.annotationType().getName())) {
                        bindings.add(type.getSimpleName() + "#" + method.getName());
                    }
                }
            }
        }

        assertThat(bindings)
                .as("one queue admits one owning consumer, and this module is not it; a binding here"
                        + " would race the account context for every inquiry message")
                .isEmpty();
    }

    /**
     * Asserts the messaging annotation is not even resolvable from this module.
     *
     * <p>Assumptions: this is asserted separately from the sweep above, because the two failures differ
     * and the sweep alone would pass in a module that had regained the dependency but not yet used it. A
     * declared-but-unused messaging starter is not inert: its auto-configuration builds a queue client as
     * the context is constructed, which makes a region a startup requirement for a module that addresses
     * no queue, and it reads to a contributor as evidence of a message flow to go looking for.</p>
     *
     * <p>Alternatives Considered: asserting the absence by reading {@code pom.xml} instead. Rejected
     * because the declaration is not the property that matters -- the starter could arrive transitively
     * through another dependency, which a file scan would miss and a classpath probe catches.</p>
     */
    @Test
    @DisplayName("the queue-listener annotation is absent from this module's classpath")
    void theQueueListenerAnnotationIsAbsentFromTheClasspath() {
        assertThatThrownBy(() -> Class.forName(QUEUE_LISTENER_ANNOTATION))
                .as("the messaging starter is withdrawn from this module, so its annotation must not"
                        + " resolve -- if it does, a consumer can be added here without a visible"
                        + " dependency decision")
                .isInstanceOf(ClassNotFoundException.class);
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
