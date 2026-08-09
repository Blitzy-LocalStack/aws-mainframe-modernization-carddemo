package com.carddemo.common.messaging;

import java.time.Duration;
import java.util.Objects;

/**
 * The time bounds a queue consumer's work must fit inside its message's visibility timeout.
 *
 * <p><b>Purpose.</b> A received queue message is invisible to other consumers for a fixed period and
 * becomes visible again when that period elapses, whether or not the first consumer is still working on
 * it. Every bound that decides how long a consumer can be busy therefore has to be read together with
 * that period, and this type is where the three of them are compared: the whole-call bound and the
 * per-attempt bound applied to the queue client, and the visibility period itself.</p>
 *
 * <p>Refactoring Rationale: the three synchronous queue clients in this repository were built with no
 * call bound at all, which is the software development kit's default -- a call retries and waits
 * indefinitely. The consequence is not a slow consumer but a DUPLICATED one: a stalled publish inside a
 * message handler outlives the visibility period, the queue delivers the same message to a second
 * consumer, and two handlers then act on one request concurrently. Nothing in a log distinguishes that
 * from ordinary redelivery, so the failure is silent. Stating the three bounds as one validated value
 * makes the relationship between them a startup condition instead of an assumption.</p>
 *
 * <p>Assumptions: this type holds no client, no queue reference and no software development kit type. It
 * is a rule over three durations, so it is unit-testable without a queue and it is usable by any consumer
 * regardless of which client library applies the values. The shared kernel declares no dependency on the
 * software development kit, and keeping this type free of one is what lets the rule live here rather than
 * being written once per service.</p>
 *
 * <p>Alternatives Considered: a private validator in each service's queue configuration. Rejected because
 * the rule is one contract across three services and an infrastructure module -- the visibility period is
 * set by {@code infra/modules/sqs} and consumed by all three -- so three copies would be three chances for
 * one of them to be relaxed while the other two still claimed the guarantee. Alternatives Considered:
 * expressing the bounds as plain longs on each configuration method and comparing them inline. Rejected
 * because a millisecond value and a second value were already in use for different bounds in the same
 * file, and an inline comparison of two differently-denominated numbers is the mistake this type's
 * {@link Duration} members make unrepresentable.</p>
 *
 * @param apiCallTimeout the whole-call bound, covering every retry attempt of one queue call together;
 *     must be positive and must be shorter than {@code visibilityTimeout}
 * @param apiCallAttemptTimeout the per-attempt bound, covering one network attempt of that call; must be
 *     positive and must not exceed {@code apiCallTimeout}
 * @param visibilityTimeout how long a received message stays invisible to other consumers, which is the
 *     period every bound above has to fit inside; must be positive
 */
public record QueueClientBudget(Duration apiCallTimeout, Duration apiCallAttemptTimeout,
        Duration visibilityTimeout) {

    /**
     * The property every service reads {@link #apiCallTimeout()} from, in milliseconds.
     *
     * <p>Refactoring Rationale: the three property names are published HERE rather than repeated in each
     * service's queue configuration, and each configuration binds through these constants. The reason is
     * that this type raises the startup failure while the services own the bindings, so a name written in
     * two places could drift and the failure would then direct an operator at a property that is not the
     * one that was read -- a worse outcome than naming nothing at all.</p>
     *
     * <p>Assumptions: the same three names are used by every service that holds a queue client, so one
     * operational runbook covers all of them. A per-service prefix was the alternative and would have made
     * a shared bound look like three unrelated settings.</p>
     */
    public static final String PROPERTY_API_CALL_TIMEOUT = "carddemo.messaging.api-call-timeout-ms";

    /** The property every service reads {@link #apiCallAttemptTimeout()} from, in milliseconds. */
    public static final String PROPERTY_API_CALL_ATTEMPT_TIMEOUT =
            "carddemo.messaging.api-call-attempt-timeout-ms";

    /** The property every service reads {@link #visibilityTimeout()} from, in seconds. */
    public static final String PROPERTY_VISIBILITY_TIMEOUT =
            "carddemo.messaging.visibility-timeout-seconds";

    /**
     * Validates the three bounds against each other.
     *
     * <p>Assumptions: the whole-call bound must be STRICTLY shorter than the visibility period rather
     * than equal to it. Equality would mean a call that used its entire allowance finished at the exact
     * instant the message became visible again, so whether a second consumer took it would be decided by
     * scheduling; a strict comparison leaves the difference as headroom for the rest of the handler.</p>
     *
     * <p>Assumptions: the per-attempt bound may EQUAL the whole-call bound, which is the configuration
     * that permits exactly one attempt and no retry. It may not exceed it, because a per-attempt bound
     * above the whole-call bound can never be reached -- the call is abandoned first -- so a deployment
     * setting one would believe it had configured a retry allowance it does not have.</p>
     *
     * @throws NullPointerException if any bound is {@code null}
     * @throws IllegalStateException if any bound is zero or negative, if the per-attempt bound exceeds
     *     the whole-call bound, or if the whole-call bound is not shorter than the visibility period; the
     *     failure names the relationship that does not hold so an operator repairs the right value
     */
    public QueueClientBudget {
        Objects.requireNonNull(apiCallTimeout, "apiCallTimeout must not be null");
        Objects.requireNonNull(apiCallAttemptTimeout, "apiCallAttemptTimeout must not be null");
        Objects.requireNonNull(visibilityTimeout, "visibilityTimeout must not be null");
        requirePositive(apiCallTimeout, "apiCallTimeout", PROPERTY_API_CALL_TIMEOUT);
        requirePositive(apiCallAttemptTimeout, "apiCallAttemptTimeout",
                PROPERTY_API_CALL_ATTEMPT_TIMEOUT);
        requirePositive(visibilityTimeout, "visibilityTimeout", PROPERTY_VISIBILITY_TIMEOUT);
        if (apiCallAttemptTimeout.compareTo(apiCallTimeout) > 0) {
            throw new IllegalStateException("apiCallAttemptTimeout " + apiCallAttemptTimeout
                    + " exceeds apiCallTimeout " + apiCallTimeout
                    + ": a per-attempt bound above the whole-call bound can never be reached, because"
                    + " the call is abandoned first; configured by " + PROPERTY_API_CALL_ATTEMPT_TIMEOUT
                    + " and " + PROPERTY_API_CALL_TIMEOUT);
        }
        if (apiCallTimeout.compareTo(visibilityTimeout) >= 0) {
            throw new IllegalStateException("apiCallTimeout " + apiCallTimeout
                    + " is not shorter than the queue's visibility timeout " + visibilityTimeout
                    + ": a call that can outlive visibility lets the queue deliver the same message to a"
                    + " second consumer while the first is still working on it; configured by "
                    + PROPERTY_API_CALL_TIMEOUT + " and " + PROPERTY_VISIBILITY_TIMEOUT);
        }
    }

    /**
     * Refuses a handler whose other work, added to the whole-call bound, does not fit inside visibility.
     *
     * <p>Assumptions: the caller supplies the worst case of everything ELSE its handler does inside one
     * message -- outbound calls to other services, database work, and anything else bounded by a
     * configured timeout -- and this method adds the queue call to it. The sum is what has to fit, because
     * a handler that publishes a reply spends both.</p>
     *
     * <p>Assumptions: a handler that issues no queue call of its own still passes its other work here.
     * The whole-call bound is then a small overstatement, and overstating a bound that fails startup is
     * the safe direction: the alternative is a check that passes for a budget which does not fit.</p>
     *
     * @param otherWork the worst-case duration of everything else one message's handling can spend; must
     *     not be {@code null} and must not be negative
     * @param description what the supplied duration is composed of, quoted in the failure so an operator
     *     can see which bounds were added; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if the sum reaches or exceeds the visibility period
     */
    public void requireFitsVisibility(Duration otherWork, String description) {
        Objects.requireNonNull(otherWork, "otherWork must not be null");
        Objects.requireNonNull(description, "description must not be null");
        if (otherWork.isNegative()) {
            throw new IllegalStateException("otherWork " + otherWork + " is negative");
        }
        Duration total = otherWork.plus(this.apiCallTimeout);
        if (total.compareTo(this.visibilityTimeout) >= 0) {
            throw new IllegalStateException("the handler budget " + total + " (" + description
                    + " plus apiCallTimeout " + this.apiCallTimeout
                    + ") is not shorter than the queue's visibility timeout " + this.visibilityTimeout
                    + ": a handler that can outlive visibility lets the queue deliver the same message to"
                    + " a second consumer while the first is still working on it; the period is configured"
                    + " by " + PROPERTY_VISIBILITY_TIMEOUT);
        }
    }

    /**
     * Refuses a non-positive bound, naming both the member and the property it was configured by.
     *
     * <p>Assumptions: the member name comes FIRST and the property name is appended, so the sentence reads
     * as a statement about the value while still telling an operator which setting to repair. Putting the
     * property first was the alternative and would have made every message start with the same prefix,
     * which is the part a reader skips.</p>
     *
     * @param bound the duration to check; must not be {@code null}
     * @param name the member being checked, quoted in the failure; must not be {@code null}
     * @param property the property the member is configured by, quoted in the failure; must not be
     *     {@code null}
     * @throws IllegalStateException if the duration is zero or negative
     */
    private static void requirePositive(Duration bound, String name, String property) {
        if (bound.isZero() || bound.isNegative()) {
            throw new IllegalStateException(name + " must be positive but was " + bound
                    + ": a zero or negative bound is not a shorter deadline, it is an immediate one;"
                    + " configured by " + property);
        }
    }
}
