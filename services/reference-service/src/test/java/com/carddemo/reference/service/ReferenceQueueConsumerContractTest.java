package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.awspring.cloud.sqs.annotation.SqsListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Holds this context to exactly one queue consumer per queue.
 *
 * <p><b>Purpose.</b> A listener container is bound by an annotation on a method, and nothing in the
 * framework objects when two methods name one queue: both containers poll it, the transport hands each
 * message to whichever asked first, and the flow's observable contract becomes whichever consumer won
 * the race for that message. This class counts the bindings so a second consumer fails the build.</p>
 *
 * <p><b>Refactoring Rationale.</b> This is not a hypothetical. This context carried two active
 * consumers of the date-inquiry request queue at once — {@link DateInquiryMessageListener} and a
 * removed {@code DateConversionMessageListener} — and they disagreed on the reply's width, on whether
 * the reply destination could be taken from the message, on the reply's declared content type, and on
 * whether a requester's expiry was honoured at all. Two consumers of one queue is therefore a
 * correctness defect and not a redundancy, and the defect was invisible to every other gate in the
 * build: both classes compiled, both were documented, and each had a coherent contract of its own.
 * Only their coexistence was wrong, so only a check that counts them can see it.</p>
 *
 * <p><b>Alternatives Considered.</b> Asserting that one NAMED class carries the binding. Rejected in
 * both directions: it would pass while a second class added a second binding, which is the failure
 * that occurred, and it would fail on a legitimate rename that changed nothing about the contract.
 * Counting bindings per queue expression is what actually expresses the rule.</p>
 *
 * <p><b>Assumptions.</b> the count is taken per queue-name EXPRESSION, exactly as authored, rather
 * than per resolved queue name. Resolving a placeholder would need a started context with the
 * environment bound, and the property this class defends holds at the source level: two methods naming
 * the same property are two containers on the same queue whatever that property resolves to.</p>
 *
 * <p><b>Trade-offs.</b> two methods naming the same queue through two DIFFERENT expressions — one
 * placeholder and one literal, say — would not be reported. That is accepted because the queue names in
 * this module all arrive from configuration and never as literals, and closing the remaining gap would
 * require resolving properties, which is the cost this class exists to avoid. The set of expressions is
 * asserted as well as their count, so an expression added or changed is visible here.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so its members carry no
 * parameter, return or exception clauses beyond what they declare.</p>
 */
@DisplayName("the reference queue-consumer contract")
class ReferenceQueueConsumerContractTest {

    /** The package root the compiled classes of this context are imported from. */
    private static final String ANALYSED_ROOT = "com.carddemo.reference";

    /**
     * The one queue-name expression this context is permitted to consume.
     *
     * <p>Assumptions: stated as the authored expression rather than as a resolved name, matching what
     * this class measures. It is the property {@code application.yml} binds from
     * {@code CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE}.</p>
     */
    private static final String REQUEST_QUEUE_EXPRESSION =
            "${carddemo.reference.inquiry.request-queue}";

    /**
     * Collects every queue-name expression bound by a listener annotation in this context.
     *
     * @return one entry per {@code queueNames} element of every annotated method, in import order,
     *     including duplicates because duplicates are the defect this class detects
     */
    private static List<String> boundQueueExpressions() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ANALYSED_ROOT);

        List<String> bound = new ArrayList<>();
        for (JavaMethod method : production.stream()
                .flatMap(type -> type.getMethods().stream())
                .toList()) {
            if (method.isAnnotatedWith(SqsListener.class)) {
                bound.addAll(Arrays.asList(method.getAnnotationOfType(SqsListener.class).queueNames()));
            }
        }
        return bound;
    }

    /**
     * Confirms the date-inquiry request queue is claimed exactly once.
     *
     * <p>Assumptions: the assertion is an exact-sequence comparison rather than a count, so it fails
     * both when the queue is claimed twice and when a listener is bound to an expression this context
     * is not supposed to consume. A count alone would pass on the second of those.</p>
     */
    @Test
    @DisplayName("exactly one listener method is bound to the date-inquiry request queue")
    void exactlyOneListenerIsBoundToTheRequestQueue() {
        assertThat(boundQueueExpressions())
                .as("every queue-name expression bound by an @SqsListener in %s; a queue appearing"
                        + " twice means two containers poll it and the wire contract a message meets"
                        + " depends on which polled first", ANALYSED_ROOT)
                .containsExactly(REQUEST_QUEUE_EXPRESSION);
    }
}
