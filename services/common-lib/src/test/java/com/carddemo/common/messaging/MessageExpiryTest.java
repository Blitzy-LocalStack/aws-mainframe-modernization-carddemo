package com.carddemo.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the one rule every queue consumer in this system uses to decide whether to discard a message.
 *
 * <h2>Purpose</h2>
 * <p>The baseline sets a message expiry that the target queue service cannot express, so the obligation moved
 * to the consumer. Three consumers now carry it, and a disagreement between them would present as one
 * requester's expiry honoured differently by two services -- an intermittently unanswered request rather than
 * an error anywhere. This class asserts the two decisions that make the rule safe to share: that an absent
 * attribute means no expiry, and that an unparseable one yields no expiry INSTANT while still being reported
 * as malformed, so a caller can tell the two apart.</p>
 *
 * <p>Assumptions: the second of those is the one worth a test of its own, and the reason is that it is the
 * seam where the three consumers legitimately differ. This helper decides only whether a value parses and
 * whether it has passed; whether an unreadable attribute is then refused or answered is the consumer's
 * decision, and the two answers are recorded at their sites -- the authorization consumer refuses, the two
 * inquiry consumers treat it as absent. Asserting the parse outcome here rather than a discard policy is what
 * lets both postures rest on one shared rule.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no parameter,
 * return or exception section.</p>
 */
class MessageExpiryTest {

    /**
     * The instant every comparison is made against.
     */
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    /**
     * Verifies an absent attribute means no expiry.
     *
     * <p>Assumptions: null, empty and whitespace are all asserted. A producer that sets an attribute to an
     * empty string has expressed no expiry just as surely as one that set none, and treating the two
     * differently would make the behaviour depend on how a client library serialises an unset value.</p>
     */
    @Test
    @DisplayName("an absent, empty or blank attribute means no expiry")
    void anAbsentAttributeMeansNoExpiry() {
        assertThat(MessageExpiry.isExpired(null, NOW)).isFalse();
        assertThat(MessageExpiry.isExpired("", NOW)).isFalse();
        assertThat(MessageExpiry.isExpired("   ", NOW)).isFalse();
        assertThat(MessageExpiry.parse(null)).isNull();
        assertThat(MessageExpiry.parse("  ")).isNull();
        assertThat(MessageExpiry.isMalformed(null)).isFalse();
        assertThat(MessageExpiry.isMalformed("   ")).isFalse();
    }

    /**
     * Verifies an unparseable attribute is treated as absent, and is reported as malformed.
     *
     * <p>Assumptions: both halves matter. The value must not expire the message, and it must still be
     * distinguishable from an absent one so a caller can warn that a producer believes it set an expiry that
     * is not being honoured.</p>
     */
    @Test
    @DisplayName("an unparseable attribute is treated as absent but reported as malformed")
    void anUnparseableAttributeIsTreatedAsAbsent() {
        for (String malformed : new String[] {"soon", "2026-13-45T99:99:99", "12:00", "-1x", "1e9"}) {
            assertThat(MessageExpiry.isExpired(malformed, NOW))
                    .as("%s must not expire the message", malformed)
                    .isFalse();
            assertThat(MessageExpiry.parse(malformed)).isNull();
            assertThat(MessageExpiry.isMalformed(malformed))
                    .as("%s must be reported as malformed", malformed)
                    .isTrue();
        }
    }

    /**
     * Verifies the epoch-millisecond form parses.
     */
    @Test
    @DisplayName("the epoch-millisecond form parses, including its sub-second part")
    void theEpochMillisecondFormParses() {
        long millis = NOW.toEpochMilli();

        assertThat(MessageExpiry.parse(String.valueOf(millis))).isEqualTo(NOW);
        assertThat(MessageExpiry.parse(String.valueOf(millis + 250)))
                .isEqualTo(NOW.plusMillis(250));
        assertThat(MessageExpiry.parse("  " + millis + "  ")).isEqualTo(NOW);
    }

    /**
     * Verifies the zone-less form parses and is read as coordinated universal time.
     *
     * <p>Assumptions: the zone reading is asserted explicitly. A value carrying no zone still names an
     * instant, so reading it in the container's local zone would make the same message expire at different
     * moments in two deployments -- and would pass a test run on a machine whose zone happened to be
     * universal time.</p>
     */
    @Test
    @DisplayName("the zone-less form parses and is read as coordinated universal time")
    void theZonelessFormIsReadAsUtc() {
        assertThat(MessageExpiry.parse("2026-08-07T12:00:00")).isEqualTo(NOW);
        assertThat(MessageExpiry.parse("2026-08-07T12:00:00.500"))
                .isEqualTo(NOW.plusMillis(500));
    }

    /**
     * Verifies the trailing-Z instant rendering parses.
     *
     * <p>Assumptions: this is the case that found the gap this class exists to prevent. It is what
     * {@code Instant.toString()} emits, so it is the single most likely rendering a Java producer would send,
     * and the zone-less parser rejects it outright -- so before it was accepted, such a producer's expiry was
     * silently ignored on every message while the producer believed it had set one.</p>
     */
    @Test
    @DisplayName("the trailing-Z instant rendering parses")
    void theInstantRenderingParses() {
        assertThat(MessageExpiry.parse(NOW.toString())).isEqualTo(NOW);
        assertThat(MessageExpiry.parse("2026-08-07T12:00:00Z")).isEqualTo(NOW);
        assertThat(MessageExpiry.isMalformed(NOW.toString())).isFalse();
    }

    /**
     * Verifies an offset-bearing rendering parses and its offset is honoured rather than discarded.
     *
     * <p>Assumptions: the offset is asserted to CHANGE the resulting instant. A parser that accepted the
     * syntax while dropping the offset would produce an instant wrong by the offset -- an hour or more -- and
     * would still pass a test that only checked the value parsed.</p>
     */
    @Test
    @DisplayName("an offset-bearing rendering parses and its offset is honoured")
    void anOffsetBearingRenderingIsHonoured() {
        assertThat(MessageExpiry.parse("2026-08-07T14:00:00+02:00")).isEqualTo(NOW);
        assertThat(MessageExpiry.parse("2026-08-07T07:00:00-05:00")).isEqualTo(NOW);
        assertThat(MessageExpiry.parse("2026-08-07T12:00:00+02:00"))
                .as("the offset must shift the instant rather than being ignored")
                .isNotEqualTo(NOW);
    }

    /**
     * Verifies the comparison is inclusive of the expiry instant itself.
     *
     * <p>Assumptions: a message whose expiry equals the current instant is expired. That matches how a
     * broker-side time-to-live behaves at its boundary, and the exclusive reading would make the last
     * millisecond of validity depend on clock resolution.</p>
     */
    @Test
    @DisplayName("the comparison is inclusive of the expiry instant")
    void theComparisonIsInclusive() {
        assertThat(MessageExpiry.isExpired(NOW.toString(), NOW))
                .as("an expiry equal to now is expired")
                .isTrue();
        assertThat(MessageExpiry.isExpired(NOW.plusMillis(1).toString(), NOW)).isFalse();
        assertThat(MessageExpiry.isExpired(NOW.minusMillis(1).toString(), NOW)).isTrue();
    }

    /**
     * Verifies a value already expired long ago is expired, and one far ahead is not.
     */
    @Test
    @DisplayName("a long-past expiry is expired and a far-future one is not")
    void obviousCasesResolveCorrectly() {
        assertThat(MessageExpiry.isExpired("0", NOW)).isTrue();
        assertThat(MessageExpiry.isExpired(String.valueOf(NOW.plusSeconds(86_400).toEpochMilli()), NOW))
                .isFalse();
    }

    /**
     * Verifies non-ASCII digits are not accepted as a number.
     *
     * <p>Assumptions: this is asserted because the obvious implementation would use a digit test that accepts
     * every Unicode decimal script, and the numeric parser accepts those too -- so a value in non-ASCII digits
     * would parse to an instant here while a producer or a broker assuming ASCII read it differently.</p>
     */
    @Test
    @DisplayName("non-ASCII digits are not accepted as an epoch value")
    void nonAsciiDigitsAreNotANumber() {
        assertThat(MessageExpiry.parse("\u0661\u0662\u0663")).isNull();
        assertThat(MessageExpiry.isMalformed("\u0661\u0662\u0663")).isTrue();
    }

    /**
     * Verifies the header name is a single shared constant.
     */
    @Test
    @DisplayName("the attribute name is one shared constant")
    void theAttributeNameIsShared() {
        assertThat(MessageExpiry.HEADER_EXPIRES_AT).isEqualTo("expiresAt");
    }

    /**
     * Verifies the utility holder cannot be instantiated.
     *
     * @throws Exception if the declared constructor cannot be reflected, which would mean it was removed
     */
    @Test
    @DisplayName("the utility holder cannot be instantiated")
    void theHolderCannotBeInstantiated() throws Exception {
        var constructor = MessageExpiry.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        assertThatThrownBy(constructor::newInstance).hasRootCauseInstanceOf(AssertionError.class);
    }
}
