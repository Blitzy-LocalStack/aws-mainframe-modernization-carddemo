package com.carddemo.card.config;

import com.carddemo.common.security.SealedSelector;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the keyed sealer that every card route's opaque selector is minted and opened with.
 *
 * <h2>What this class is</h2>
 *
 * <p>{@link com.carddemo.card.mapper.CardMapper} requires a {@link SealedSelector} through its
 * constructor, because a selector it mints must be openable by the same instance and under the same key.
 * Nothing contributed one, so the mapper had no construction site and no route in this context could
 * resolve a selector to a row. This class is that construction site.
 *
 * <p>Refactoring Rationale: the selector mechanism this bean keys is now the ONLY one in this context.
 * A second, unrelated mechanism existed alongside it -- a durable random {@code card_selector} UUID column
 * on {@code card.cards}, with a unique constraint and an accessor -- and it had no reader: no repository
 * method addressed the column, so no request could resolve to a row through it, while the sealed token was
 * what the mapper actually emitted. The column is removed and this sealer is the single authoritative
 * mechanism. The rationale for choosing this one over the UUID is recorded on
 * {@code com.carddemo.card.domain.Card} beside where the column used to be declared.
 *
 * <h2>Why the key is fail-fast and has no default</h2>
 *
 * <p>Assumptions: the property is bound without a fallback, so a deployment that does not supply it fails
 * at startup rather than at the first request. A default would be worse than an absence in both directions:
 * a shared literal would let anyone holding this source mint a selector for any card number, and a random
 * per-task value would make every selector unopenable by any task but the one that minted it, so a list
 * from one task and a detail read from another would disagree with no error naming the cause.
 *
 * <p>Assumptions: this key is separate from the pagination cursor signing key the shared kernel binds, even
 * though both seal a value into an opaque token. They rotate on different schedules and protect different
 * things -- a cursor is a position in one browse and expires, a selector is a row's address and does not --
 * so one key for both would tie a selector's lifetime to a cursor rotation.
 */
@Configuration
public class CardSelectorConfig {

    /** The property the sealer's key material is bound from. */
    public static final String SELECTOR_KEY_PROPERTY = "carddemo.security.card-selector.signing-key";

    /**
     * Builds the deployment's card-selector sealer from the configured key material.
     *
     * <p>Assumptions: the decoded material is zeroed once the sealer has been constructed. The sealer
     * copies what it is given, so the local array is a second live copy of a secret and leaving it for the
     * collector would keep it readable in the heap for an unbounded time.
     *
     * <p>Assumptions: the bean is conditional on none already being present, so a test may contribute a
     * fixed sealer and exercise a route without supplying deployment key material.
     *
     * @param key the configured key material, base64 or raw text, carrying at least
     *     {@link SealedSelector#MIN_KEY_LENGTH} bytes once decoded; must not be blank
     * @return the sealer every card selector is minted and opened with, never {@code null}
     * @throws IllegalStateException if the property is absent or blank, which would leave every card route
     *     unable to address a row
     * @throws IllegalArgumentException if the decoded material is shorter than
     *     {@link SealedSelector#MIN_KEY_LENGTH} bytes, which the sealer itself raises
     */
    @Bean
    @ConditionalOnMissingBean(SealedSelector.class)
    public SealedSelector cardSelectorSealer(
            @Value("${" + SELECTOR_KEY_PROPERTY + "}") String key) {

        if (key == null || key.isBlank()) {
            throw new IllegalStateException(SELECTOR_KEY_PROPERTY
                    + " must be supplied: without a secret key a card selector would be an unkeyed"
                    + " encoding of a card number, which anyone holding a selector could reverse");
        }

        byte[] material = decode(key);
        try {
            return new SealedSelector(material);
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    /**
     * Decodes the configured key, accepting either a base64 rendering or raw text.
     *
     * <p>Assumptions: base64 is attempted first and raw text is the fallback, which is the same order the
     * authorization context's keyed tokeniser uses. A secret store commonly holds binary key material as
     * base64, while a developer setting an environment variable by hand types characters; accepting both
     * means the same property serves a deployment and a local run without a second key name.
     *
     * <p>Assumptions: a base64 decode that succeeds but yields too few bytes is treated as raw text rather
     * than accepted, because a short pass-phrase can be valid base64 by coincidence -- and decoding it as
     * base64 would then discard most of its entropy before the length check ever ran.
     *
     * @param key the configured value, base64 or raw text; must not be {@code null}
     * @return the key material as bytes, never {@code null}
     */
    private static byte[] decode(String key) {

        String trimmed = key.trim();
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length >= SealedSelector.MIN_KEY_LENGTH) {
                return decoded;
            }
        } catch (IllegalArgumentException notBase64) {
            // WHY : Assumptions: a value that is not base64 is not an error here, it is the other
            //       accepted form, so the failure is swallowed rather than reported. The length check
            //       below is what refuses material that is genuinely too short, and it applies to both
            //       forms alike -- so nothing reaches the sealer unchecked by falling through here.
            return trimmed.getBytes(StandardCharsets.UTF_8);
        }
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }
}
