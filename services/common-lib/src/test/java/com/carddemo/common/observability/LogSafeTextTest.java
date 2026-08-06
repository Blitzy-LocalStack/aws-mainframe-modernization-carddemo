package com.carddemo.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the log-record neutralisation rule, including the boundary that decides whether a copy is
 * made at all.
 */
class LogSafeTextTest {

    /**
     * Confirms a line feed cannot end the line the value is written into.
     *
     * <p>Assumptions: the forged continuation used here is shaped like a real event line from this
     * system, because that is the shape a forged record would have to take to be read as one by a
     * collector. Asserting that the result is a single line is therefore the assertion that matters.</p>
     */
    @Test
    @DisplayName("a line feed in the value cannot begin a second log record")
    void lineFeedCannotForgeASecondRecord() {
        String forged = "bad token\nevent=batch.job.completed code=0";

        String sanitized = LogSafeText.sanitize(forged);

        assertThat(sanitized).doesNotContain("\n").hasSameSizeAs(forged);
        assertThat(sanitized.lines()).hasSize(1);
    }

    /**
     * Confirms the whole ISO control range is covered and not the two line terminators alone.
     */
    @Test
    @DisplayName("carriage return, tab, escape and NUL are all neutralised")
    void wholeControlRangeIsNeutralised() {
        String sanitized = LogSafeText.sanitize("a\rb\tc\u001bd\u0000e");

        assertThat(sanitized).isEqualTo("a b c d e");
    }

    /**
     * Confirms the replacement preserves the value's length, so a bounded field cannot overflow and a
     * fixed-width value keeps its positions.
     */
    @Test
    @DisplayName("the replacement preserves length and position")
    void replacementPreservesLength() {
        String value = "1234\n6789";

        assertThat(LogSafeText.sanitize(value))
                .hasSameSizeAs(value)
                .isEqualTo("1234" + LogSafeText.REPLACEMENT + "6789");
    }

    /**
     * Confirms a clean value is returned as the same instance, so the ordinary case allocates nothing.
     *
     * <p>Assumptions: identity is asserted rather than equality, because the no-copy property is the
     * stated reason this is cheap enough to apply on every run and an equality assertion would pass
     * even if the property were lost.</p>
     */
    @Test
    @DisplayName("a clean value is returned as the same instance")
    void cleanValueIsNotCopied() {
        String clean = "event=batch.run.started job=post-transactions";

        assertThat(LogSafeText.sanitize(clean)).isSameAs(clean);
    }

    /**
     * Confirms absent and empty values pass through without a failure.
     */
    @Test
    @DisplayName("null and the empty string are returned unchanged")
    void nullAndEmptyArePreserved() {
        assertThat(LogSafeText.sanitize(null)).isNull();
        assertThat(LogSafeText.sanitize("")).isEmpty();
    }
}
