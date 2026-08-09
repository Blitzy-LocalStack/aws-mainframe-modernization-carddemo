package com.carddemo.authorization.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.codec.CsvAuthCodec;
import com.carddemo.common.security.OpaqueIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts the keyed tokeniser this context holds is supplied, is keyed from configuration alone, and
 * refuses to exist without a key.
 *
 * <h2>Purpose</h2>
 * <p>Refactoring Rationale: no bean supplied this tokeniser at all, and these tests were written when the
 * omission left the reply path publishing the card number as its first-in-first-out group identity for
 * want of any other per-card stable value. That framing no longer describes the reply path: specification
 * &sect;0.4.1.8 freezes {@code MessageGroupId} as {@code card_num} and {@code MessageDeduplicationId} as
 * {@code transaction_id}, so both are published literally by design and the exposure is registered as
 * divergence {@code D-AUTHORIZATION-FIFO-IDENTITY-METADATA} rather than derived away. What these tests
 * still assert is the property that outlives the framing: a keyed tokeniser exists, its key comes from
 * configuration and nowhere else, no default can satisfy it, and the values it produces are stable per
 * input, distinct across inputs and free of the input's digits. Every derived-identity surface in this
 * context depends on all four.</p>
 *
 * <p>Assumptions: the configuration is exercised by calling the factory method directly rather than by
 * starting a context. What is under test is the key handling -- which forms are accepted, what happens
 * when the value is absent or too short -- and a context start would additionally require a datasource, a
 * queue client and an issuer, so a failure would no longer be attributable to the key.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception section.</p>
 */
class MessagingIdentityConfigTest {

    /**
     * A card number used to observe that a token discloses none of it.
     */
    private static final String CARD_NUMBER = "4111111111112345";

    /**
     * A second card number, so stability and distinctness can be told apart.
     */
    private static final String OTHER_CARD_NUMBER = "5500000000004321";

    /**
     * A raw-text key of exactly the minimum admissible length.
     */
    private static final String RAW_KEY = "0123456789abcdef0123456789abcdef";

    /**
     * The configuration under test.
     */
    private final MessagingIdentityConfig config = new MessagingIdentityConfig();

    /**
     * Verifies a raw-text key of the minimum length yields a working tokeniser.
     */
    @Test
    @DisplayName("a raw-text key of the minimum length yields a tokeniser")
    void aRawTextKeyYieldsATokeniser() {
        assertThat(RAW_KEY.getBytes(StandardCharsets.UTF_8))
                .as("the fixture must be exactly at the boundary so the boundary is what is tested")
                .hasSize(OpaqueIdentifier.MIN_KEY_LENGTH);

        OpaqueIdentifier tokeniser = this.config.messagingOpaqueIdentifier(RAW_KEY);

        assertThat(tokeniser.token(CsvAuthCodec.GROUP_PURPOSE, CARD_NUMBER))
                .hasSize(OpaqueIdentifier.TOKEN_LENGTH);
    }

    /**
     * Verifies a base64-encoded key is decoded rather than used as its own text.
     *
     * <p>Assumptions: the two tokenisers are compared for INEQUALITY. Both keys are admissible, so both
     * produce a token; the only way to observe that the base64 form was decoded rather than taken
     * literally is that its token differs from the token the same characters produce as raw text.</p>
     */
    @Test
    @DisplayName("a base64 key is decoded rather than taken as literal text")
    void aBase64KeyIsDecoded() {
        byte[] material = new byte[OpaqueIdentifier.MIN_KEY_LENGTH];
        for (int index = 0; index < material.length; index++) {
            material[index] = (byte) (index + 100);
        }
        String encoded = Base64.getEncoder().encodeToString(material);

        String fromEncoded = this.config.messagingOpaqueIdentifier(encoded)
                .token(CsvAuthCodec.GROUP_PURPOSE, CARD_NUMBER);
        String fromRaw = new OpaqueIdentifier(encoded.getBytes(StandardCharsets.UTF_8))
                .token(CsvAuthCodec.GROUP_PURPOSE, CARD_NUMBER);

        assertThat(fromEncoded)
                .hasSize(OpaqueIdentifier.TOKEN_LENGTH)
                .isEqualTo(new OpaqueIdentifier(material)
                        .token(CsvAuthCodec.GROUP_PURPOSE, CARD_NUMBER))
                .isNotEqualTo(fromRaw);
    }

    /**
     * Verifies an absent or blank key stops startup and names the property.
     *
     * <p>Assumptions: the failure must NAME the property. A tokeniser built from an empty key would fail
     * on length instead, with a message about digest key sizes that says nothing about which
     * configuration value a deployment forgot.</p>
     */
    @Test
    @DisplayName("an absent or blank key stops startup and names the property")
    void anAbsentKeyStopsStartup() {
        for (String absent : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> this.config.messagingOpaqueIdentifier(absent))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("carddemo.messaging.hmac-key");
        }
    }

    /**
     * Verifies a key shorter than the minimum is refused rather than accepted and weakened.
     */
    @Test
    @DisplayName("a key shorter than the minimum is refused")
    void aShortKeyIsRefused() {
        assertThatThrownBy(() -> this.config.messagingOpaqueIdentifier("too-short"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Verifies a per-card derivation is stable per card, distinct across cards, and PAN-free.
     *
     * <p>Assumptions: all three properties are asserted together because a derivation is only usable if
     * all three hold. Stability alone would be satisfied by a constant, which would make every card
     * indistinguishable. Distinctness alone would be satisfied by a random value, which would make two
     * derivations of one card disagree and correlate nothing. Absence of the number alone would be
     * satisfied by either of those.</p>
     */
    @Test
    @DisplayName("a per-card derivation is stable per card, distinct across cards and free of its digits")
    void theGroupIdentityIsStableDistinctAndPanFree() {
        OpaqueIdentifier tokeniser = this.config.messagingOpaqueIdentifier(RAW_KEY);

        String first = tokeniser.token(CsvAuthCodec.GROUP_PURPOSE, CARD_NUMBER);
        String again = tokeniser.token(CsvAuthCodec.GROUP_PURPOSE, CARD_NUMBER);
        String other = tokeniser.token(CsvAuthCodec.GROUP_PURPOSE, OTHER_CARD_NUMBER);

        assertThat(first).as("one input must derive one value").isEqualTo(again);
        assertThat(first).as("two inputs must derive two values").isNotEqualTo(other);
        assertThat(first)
                .as("a derived value must disclose no part of its input")
                .doesNotContain(CARD_NUMBER)
                .doesNotContain(CARD_NUMBER.substring(0, 6))
                .doesNotContain(CARD_NUMBER.substring(CARD_NUMBER.length() - 4));
    }

    /**
     * Verifies the group and correlation purposes yield different tokens under one key.
     *
     * <p>Assumptions: purpose scoping is what keeps one key usable for two identities. Without it a
     * correlation value and a per-card value derived from the same card would be the same string, so
     * anything holding one would hold the other and the two could be substituted for each other.</p>
     */
    @Test
    @DisplayName("the two purposes yield different tokens under one key")
    void theTwoPurposesAreScopedApart() {
        OpaqueIdentifier tokeniser = this.config.messagingOpaqueIdentifier(RAW_KEY);

        assertThat(tokeniser.token(CsvAuthCodec.GROUP_PURPOSE, CARD_NUMBER))
                .isNotEqualTo(tokeniser.token(CsvAuthCodec.CORRELATION_PURPOSE, CARD_NUMBER));
    }

    /**
     * Verifies the bean name constant is the one the qualifier annotation carries.
     *
     * <p>Assumptions: the constant and the qualifier must not be able to drift apart. A qualifier naming
     * a bean that does not exist fails at context start with a resolution error rather than at compile
     * time, so pinning the pairing here is what turns that into a test failure.</p>
     */
    @Test
    @DisplayName("the qualifier annotation names the bean the configuration declares")
    void theQualifierNamesTheDeclaredBean() {
        org.springframework.beans.factory.annotation.Qualifier qualifier =
                MessagingIdentityConfig.MessagingTokeniser.class
                        .getAnnotation(org.springframework.beans.factory.annotation.Qualifier.class);

        assertThat(qualifier).isNotNull();
        assertThat(qualifier.value()).isEqualTo(MessagingIdentityConfig.MESSAGING_TOKENISER);
    }
}
