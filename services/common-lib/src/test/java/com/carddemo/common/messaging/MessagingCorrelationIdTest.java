package com.carddemo.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /**
     * A requester-supplied identifier is echoed in full and never reaches a log.
     *
     * <p>Purpose: this is the asymmetry the class exists for, asserted on the one input where getting it
     * wrong costs something. A correlation attribute is chosen by the requester, so it can BE a primary
     * account number; the value has to be echoed exactly, because the requester pairs its answer on it,
     * and it must not reach the mapped diagnostic context, because everything there lands on every log
     * line the message produces.</p>
     *
     * <p>Assumptions: each specimen is one of the four identifier widths the baseline declares, so the
     * bound is exercised at the narrowest of them rather than only at a card number. Nine is
     * {@code CUST-ID PIC 9(09)}, eleven is {@code ACCT-ID PIC 9(11)} and sixteen is both
     * {@code CARD-NUM PIC X(16)} and {@code TRAN-ID PIC X(16)}.</p>
     *
     * <p>Assumptions: the rendering is asserted to contain no digit of the value at all, not merely to
     * differ from it. A masked rendering would differ and would still disclose four digits, so an
     * assertion of inequality alone would pass on the weaker outcome this case exists to rule out.</p>
     *
     * @param identifier the bare-numeric value a requester supplied, of type {@code String}
     */
    @ParameterizedTest(name = "{0} is echoed and never logged")
    @ValueSource(strings = {"123456789", "00000000011", "4111111111111111",
        "9999999999999999999999"})
    void aBareNumericIdentityIsEchoedInFullAndNeverLogged(String identifier) {
        assertThat(MessagingCorrelationId.isCanonical(identifier))
                .as("the echo must carry the requester's own bytes, whatever they are")
                .isTrue();

        String rendered = MessagingCorrelationId.logSafe(identifier);

        assertThat(rendered)
                .as("the log rendering names the shape and the digit count, and no digit of the value")
                .isEqualTo(MessagingCorrelationId.NUMERIC_IDENTITY_MARKER + identifier.length())
                .doesNotContain(identifier);
        assertThat(rendered.chars().filter(Character::isDigit).count())
                .as("only the digit COUNT is numeric in the rendering")
                .isEqualTo(String.valueOf(identifier.length()).length());
    }

    /**
     * A separated card number is redacted too, which is the case a bare-digit rule misses.
     *
     * <p>Assumptions: separators are counted as belonging to the number rather than disqualifying it,
     * because {@code 4111-1111-1111-1111} is a card number written the way a human writes one. This is
     * the same correction {@code com.carddemo.common.web.CorrelationIdFilter} carries for the servlet
     * transport, and asserting it here is what stops the two transports from disagreeing about it.</p>
     */
    @Test
    @DisplayName("a separated card number is redacted, and its digit count is what is reported")
    void aSeparatedCardNumberIsRedacted() {
        assertThat(MessagingCorrelationId.logSafe("4111-1111-1111-1111"))
                .as("sixteen digits and three separators: the digits are what is counted")
                .isEqualTo(MessagingCorrelationId.NUMERIC_IDENTITY_MARKER + 16);
        // WHY : Assumptions: the two redaction layers use DIFFERENT separator sets and this specimen is
        //       where that shows, so the outcome is asserted rather than assumed to match the line above.
        //       A space is not an identity separator here -- an identity round-trips through a
        //       declared-width character field that pads with spaces, so a space inside one could not be
        //       told from padding -- which means this value is not bare-numeric and the wholesale
        //       replacement does not fire. CardNumberMasker's own separator set DOES admit the space, so
        //       the masking layer catches it instead, and the space is then neutralised by the character
        //       filter. The value is redacted either way, which is the property that matters; the two
        //       layers overlap deliberately rather than partitioning the input.
        assertThat(MessagingCorrelationId.logSafe("4111 1111 1111 1111"))
                .as("caught by the masking layer rather than the bare-numeric one, and still redacted")
                .isEqualTo("****.****.****.1111")
                .doesNotContain("4111 1111 1111 1111");
    }

    /**
     * A card number embedded in an otherwise diagnostic value is masked, and the context survives.
     *
     * <p>Assumptions: this is the case the whole-value rule cannot see, because one letter disqualifies
     * the bare-numeric shape, and it is the case where the surrounding characters ARE the diagnostic
     * content -- a prefix naming the requester's own scheme is exactly what an operator correlates on.
     * Masking rather than replacing wholesale is therefore right here and wrong for a bare number, which
     * is why the two redactions are separate and ordered.</p>
     */
    @Test
    @DisplayName("an embedded card number is masked while the surrounding value survives")
    void anEmbeddedCardNumberIsMaskedAndTheContextSurvives() {
        String rendered = MessagingCorrelationId.logSafe("req-4111111111111111");

        assertThat(rendered)
                .as("the prefix is diagnostic and is kept; the number is masked to its last four")
                .isEqualTo("req-************1111")
                .doesNotContain("4111111111111111");
        assertThat(MessagingCorrelationId.isCanonical("req-4111111111111111"))
                .as("and the echo still carries the value the requester sent")
                .isTrue();
    }

    /**
     * A short bare number is left alone, so the bound is a bound rather than a blanket rule.
     *
     * <p>Assumptions: this is asserted because a redaction that fired on every numeric value would
     * satisfy every case above while destroying the log rendering of legitimate identities. The
     * specimens sit one digit below {@link MessagingCorrelationId#NUMERIC_IDENTITY_MIN_DIGITS}, so they
     * pin the boundary itself and not merely the interior of the admitted range.</p>
     */
    @Test
    @DisplayName("a bare number shorter than the narrowest identifier is logged as itself")
    void aShortBareNumberIsLoggedAsItself() {
        String justUnder = "1".repeat(MessagingCorrelationId.NUMERIC_IDENTITY_MIN_DIGITS - 1);

        assertThat(MessagingCorrelationId.logSafe(justUnder)).isEqualTo(justUnder);
        assertThat(MessagingCorrelationId.logSafe("42")).isEqualTo("42");
    }
}
