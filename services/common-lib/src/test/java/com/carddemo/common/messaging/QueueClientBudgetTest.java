package com.carddemo.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the relationship a queue consumer's time bounds must satisfy against its visibility period.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: the three synchronous queue clients in this repository were built with no call
 * bound at all, which is the software development kit's default -- a call retries and waits indefinitely.
 * Two of those clients publish from INSIDE a message handler, so an unbounded call is an unbounded handler,
 * and a handler that outlives its message's visibility period is not merely slow: the queue makes the
 * message visible again, a second consumer takes it, and two handlers act on one request concurrently.
 * These cases pin the rule that refuses such a configuration at startup, because the failure it prevents is
 * indistinguishable in a log from ordinary redelivery.</p>
 *
 * <p>Assumptions: the durations here are deliberately small and unrealistic. What is under test is the
 * comparison between three values, not any particular tuning, and small numbers make each case's intent
 * legible at a glance.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class QueueClientBudgetTest {

    /**
     * Confirms a budget whose bounds ascend correctly is accepted and retains its three values.
     */
    @Test
    @DisplayName("a whole-call bound shorter than visibility, with a per-attempt bound inside it, is accepted")
    void anAscendingBudgetIsAccepted() {
        QueueClientBudget budget = new QueueClientBudget(Duration.ofSeconds(10),
                Duration.ofSeconds(5), Duration.ofSeconds(60));

        assertThat(budget.apiCallTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(budget.apiCallAttemptTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(budget.visibilityTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    /**
     * Confirms a per-attempt bound equal to the whole-call bound is accepted.
     *
     * <p>Assumptions: equality is the configuration that permits exactly one attempt and no retry, which is
     * a legitimate choice, so it must not be refused. It is asserted beside the refusal of the excess below
     * because the two together are the boundary: a check written with the wrong comparison would fail
     * exactly one of them.</p>
     */
    @Test
    @DisplayName("a per-attempt bound equal to the whole-call bound is accepted as one attempt")
    void aSingleAttemptBudgetIsAccepted() {
        QueueClientBudget budget = new QueueClientBudget(Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofSeconds(30));

        assertThat(budget.apiCallAttemptTimeout()).isEqualTo(budget.apiCallTimeout());
    }

    /**
     * Confirms a per-attempt bound greater than the whole-call bound is refused.
     */
    @Test
    @DisplayName("a per-attempt bound above the whole-call bound is refused as unreachable")
    void anUnreachableAttemptBoundIsRefused() {
        assertThatThrownBy(() -> new QueueClientBudget(Duration.ofSeconds(5),
                Duration.ofSeconds(6), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiCallAttemptTimeout")
                .hasMessageContaining("can never be reached");
    }

    /**
     * Confirms a whole-call bound that reaches or exceeds the visibility period is refused.
     *
     * <p>Assumptions: equality is refused as well as excess. A call that used its entire allowance would
     * finish at the exact instant the message became visible again, so whether a second consumer took it
     * would be decided by scheduling -- which is not a guarantee.</p>
     */
    @Test
    @DisplayName("a whole-call bound reaching visibility is refused, equality included")
    void aCallBoundThatOutlivesVisibilityIsRefused() {
        assertThatThrownBy(() -> new QueueClientBudget(Duration.ofSeconds(60),
                Duration.ofSeconds(5), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visibility")
                .hasMessageContaining("second consumer");

        assertThatThrownBy(() -> new QueueClientBudget(Duration.ofSeconds(61),
                Duration.ofSeconds(5), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visibility");
    }

    /**
     * Confirms a zero or negative bound is refused, whichever of the three it is.
     */
    @Test
    @DisplayName("a zero or negative bound is refused, naming the member at fault")
    void aNonPositiveBoundIsRefused() {
        assertThatThrownBy(() -> new QueueClientBudget(Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiCallTimeout must be positive");

        assertThatThrownBy(() -> new QueueClientBudget(Duration.ofSeconds(10),
                Duration.ofSeconds(-1), Duration.ofSeconds(60)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiCallAttemptTimeout must be positive");

        assertThatThrownBy(() -> new QueueClientBudget(Duration.ofSeconds(10),
                Duration.ofSeconds(5), Duration.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("visibilityTimeout must be positive");
    }

    /**
     * Confirms a {@code null} bound is refused rather than reaching a comparison.
     */
    @Test
    @DisplayName("an absent bound is refused before any comparison")
    void anAbsentBoundIsRefused() {
        assertThatThrownBy(() -> new QueueClientBudget(null, Duration.ofSeconds(5),
                Duration.ofSeconds(60)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("apiCallTimeout");
    }

    /**
     * Confirms the handler check adds the whole-call bound to the caller's other work.
     *
     * <p>Assumptions: the sum is what is compared, not either part, and the case proves it by supplying an
     * "other work" figure that fits on its own and does not fit once the call is added. A check that
     * compared only the caller's figure would pass this case.</p>
     */
    @Test
    @DisplayName("the handler check compares the SUM of other work and the whole-call bound")
    void theHandlerCheckComparesTheSum() {
        QueueClientBudget budget = new QueueClientBudget(Duration.ofSeconds(10),
                Duration.ofSeconds(5), Duration.ofSeconds(60));

        budget.requireFitsVisibility(Duration.ofSeconds(45), "forty-five seconds of other work");

        assertThatThrownBy(() -> budget.requireFitsVisibility(Duration.ofSeconds(55),
                "fifty-five seconds of other work"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("handler budget")
                .hasMessageContaining("fifty-five seconds of other work");
    }

    /**
     * Confirms the handler check refuses a sum that exactly reaches visibility, and a negative figure.
     */
    @Test
    @DisplayName("the handler check refuses a sum that reaches visibility and refuses a negative figure")
    void theHandlerCheckRefusesEqualityAndNegatives() {
        QueueClientBudget budget = new QueueClientBudget(Duration.ofSeconds(10),
                Duration.ofSeconds(5), Duration.ofSeconds(60));

        assertThatThrownBy(() -> budget.requireFitsVisibility(Duration.ofSeconds(50), "fifty seconds"))
                .as("a sum equal to visibility leaves the outcome to scheduling")
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> budget.requireFitsVisibility(Duration.ofSeconds(-1), "negative"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("negative");

        assertThatThrownBy(() -> budget.requireFitsVisibility(null, "absent"))
                .isInstanceOf(NullPointerException.class);
    }
}
