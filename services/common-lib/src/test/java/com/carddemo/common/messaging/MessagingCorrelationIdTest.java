package com.carddemo.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the canonical encoding a queue correlation identity must satisfy, and that its log rendering is
 * a separate and narrower thing.
 *
 * <p><b>Purpose.</b> Two properties are under test and they pull in opposite directions, which is why
 * they are asserted together. The canonical rule must be WIDE enough to admit every rendering a requester
 * can produce from the baseline's twenty-four byte correlation field, because a rule that refuses one
 * leaves the requester unable to pair an answer with its question. The log rendering must be NARROW
 * enough that nothing reaching a log record can terminate a field or forge a line. A single rule cannot do
 * both, and the defect these tests pin was exactly an attempt to make one rule serve both transports.</p>
 *
 * <p>Assumptions: the servlet rule is referenced here so the divergence is executable rather than
 * described. Asserting that a legitimate messaging identity FAILS the servlet rule is what proves the two
 * rules must be different; without it a later change could quietly narrow the messaging rule back to the
 * servlet one and every other assertion here would still pass.</p>
 */
class MessagingCorrelationIdTest {

    /**
     * A rendering of the baseline's twenty-four byte field as hexadecimal, which is 48 characters.
     */
    private static final String HEX_RENDERING = "0123456789abcdef0123456789abcdef0123456789abcdef";

    /**
     * A rendering as unpadded base64, which is 32 characters and carries base64's own punctuation.
     */
    private static final String BASE64_RENDERING = "AbCd+EfGh/IjKlMnOpQrSt=uVwXyZ0123";

    /**
     * Absence and blankness are reported as absent rather than as malformed.
     */
    @Test
    @DisplayName("an absent or blank attribute is absent, not malformed")
    void absenceIsNotMalformed() {
        assertThat(MessagingCorrelationId.isPresent(null)).isFalse();
        assertThat(MessagingCorrelationId.isPresent("")).isFalse();
        assertThat(MessagingCorrelationId.isPresent("   ")).isFalse();
        assertThat(MessagingCorrelationId.isCanonical(null)).isFalse();
    }

    /**
     * Every rendering of a twenty-four byte identity that a requester can produce is canonical.
     *
     * <p>Assumptions: the three shapes asserted are the ones this system actually meets -- hexadecimal,
     * base64 including its punctuation, and the opaque token the shared codec produces -- so the rule is
     * pinned against real renderings rather than against an abstract character class.</p>
     */
    @Test
    @DisplayName("hexadecimal, base64 and opaque-token renderings are all canonical")
    void everyRealisticRenderingIsCanonical() {
        assertThat(MessagingCorrelationId.isCanonical(HEX_RENDERING)).isTrue();
        assertThat(MessagingCorrelationId.isCanonical(BASE64_RENDERING)).isTrue();
        assertThat(MessagingCorrelationId.isCanonical("aB3-_x9YzQ01234567890a")).isTrue();
        assertThat(MessagingCorrelationId.isCanonical("2f6c1e3a-9b7d-4c51-8e02-77aa15b9c3d4")).isTrue();
    }

    /**
     * The servlet rule refuses identities the messaging rule must accept, which is why they are two rules.
     *
     * <p>Assumptions: this is the regression the messaging rule exists for. The servlet rule bounds an
     * identity at its own published width, so a 48-character hexadecimal rendering fails it on length
     * alone; borrowing that predicate for a queue attribute therefore discarded a legitimate identity on
     * every message that carried one.</p>
     */
    @Test
    @DisplayName("the servlet rule refuses a legitimate messaging identity, so the two rules differ")
    void theServletRuleIsNotTheMessagingRule() {
        assertThat(HEX_RENDERING.length())
                .as("the hexadecimal rendering is longer than the servlet rule's own bound")
                .isGreaterThan(CorrelationIdFilter.CORRELATION_ID_MAX_LENGTH);
        assertThat(CorrelationIdFilter.isConformingCorrelationId(HEX_RENDERING)).isFalse();
        assertThat(MessagingCorrelationId.isCanonical(HEX_RENDERING)).isTrue();
    }

    /**
     * A control character, a space and an over-long value are refused.
     *
     * <p>Assumptions: the space is refused as well as the control characters, because the value is an
     * identity rather than prose and leading or trailing whitespace would make two renderings of one
     * identity compare unequal.</p>
     */
    @Test
    @DisplayName("control characters, spaces and over-long values are refused")
    void malformedValuesAreRefused() {
        assertThat(MessagingCorrelationId.isCanonical("abc\ndef")).isFalse();
        assertThat(MessagingCorrelationId.isCanonical("abc\tdef")).isFalse();
        assertThat(MessagingCorrelationId.isCanonical("abc def")).isFalse();
        assertThat(MessagingCorrelationId.isCanonical("a".repeat(
                MessagingCorrelationId.MAX_LENGTH + 1))).isFalse();
        assertThat(MessagingCorrelationId.isCanonical("a".repeat(
                MessagingCorrelationId.MAX_LENGTH))).isTrue();
    }

    /**
     * A non-ASCII character is refused because the attribute crosses an unnegotiated encoding boundary.
     */
    @Test
    @DisplayName("a non-ASCII character is refused")
    void nonAsciiIsRefused() {
        assertThat(MessagingCorrelationId.isCanonical("ab\u00e9cd")).isFalse();
    }

    /**
     * The log rendering neutralises the characters that could forge a record, and preserves length.
     *
     * <p>Assumptions: length preservation is asserted explicitly. Removing characters instead of
     * replacing them would let two different identities render identically, which would make the log
     * field useless for the one thing it exists for.</p>
     */
    @Test
    @DisplayName("the log rendering neutralises structural characters and preserves length")
    void theLogRenderingIsNarrowerThanTheCanonicalRule() {
        String injection = "a\",\"level\":\"ERROR";

        assertThat(MessagingCorrelationId.isCanonical(injection))
                .as("punctuation is canonical, so the echo must carry it")
                .isTrue();
        assertThat(MessagingCorrelationId.logSafe(injection))
                .as("but the log rendering must not carry a quotation mark or a comma")
                .isEqualTo("a...level.:.ERROR")
                .hasSameSizeAs(injection);
    }

    /**
     * The log rendering keeps the four separators an ordinary identity is built from.
     */
    @Test
    @DisplayName("an ordinary identity reaches the log unchanged")
    void anOrdinaryIdentityIsUnchangedByTheLogRendering() {
        assertThat(MessagingCorrelationId.logSafe("req-01.ab:9_Z")).isEqualTo("req-01.ab:9_Z");
        assertThat(MessagingCorrelationId.logSafe(HEX_RENDERING)).isEqualTo(HEX_RENDERING);
    }

    /**
     * A control character never survives into the log rendering, and an absent value renders as empty.
     */
    @Test
    @DisplayName("a control character never reaches the log and absence renders as the empty string")
    void controlCharactersNeverReachTheLog() {
        assertThat(MessagingCorrelationId.logSafe("a\r\nb")).isEqualTo("a..b");
        assertThat(MessagingCorrelationId.logSafe(null)).isEmpty();
    }

    /**
     * The log rendering is bounded even when the value is not.
     */
    @Test
    @DisplayName("the log rendering is bounded at the canonical maximum")
    void theLogRenderingIsBounded() {
        assertThat(MessagingCorrelationId.logSafe("z".repeat(500)))
                .hasSize(MessagingCorrelationId.MAX_LENGTH);
    }
}
