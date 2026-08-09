package com.carddemo.authorization.config;

import com.carddemo.common.security.InternalServiceToken;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the machine identity this context presents when it calls the account context.
 *
 * <h2>Why this configuration exists</h2>
 *
 * <p>Refactoring Rationale: the three account-context calls this service makes carried NO credential at all.
 * The account context authenticates every request against the identity provider and denies anything
 * unauthenticated, so each of those calls would have been refused with a 401 -- and the calling code treats any
 * non-404 failure as a dependency being unavailable, so every authorization would have been redelivered until
 * the queue dead-lettered it. The service was therefore not merely insecure; it could not have completed a
 * single decision. This class is the missing credential.</p>
 *
 * <p>Assumptions: the credential is a short-lived signed token minted locally rather than one obtained from the
 * identity provider. The reason is concrete rather than a preference: the provisioned user pool has no hosted
 * domain -- {@code infra/modules/cognito} leaves its domain prefix unset -- so it exposes no token endpoint,
 * and giving it one would add a public hostname and a second public surface for the sole benefit of a call that
 * never leaves the private network. The shared minter records the alternatives that were weighed and why each
 * was declined.</p>
 *
 * <p>Assumptions: the signing key is a DEPLOYMENT secret with no default, and a missing value stops startup.
 * There is no safe fallback: a fixed key compiled in would be a credential published in the repository, and a
 * randomly generated per-instance key would mint tokens the account context could not verify -- so the service
 * would start and then fail every call, which is the failure this class exists to remove.</p>
 *
 * <p>Assumptions: this key is a THIRD secret, separate from both the messaging derivation key and the
 * extract-transform-load masking key. All three are keyed HMAC secrets and none may substitute for another:
 * this one authenticates a caller, the messaging key derives an opaque per-authorization identity, and the masking key
 * derives a redaction tag. Sharing any pair would mean that holding one capability granted another, and would
 * make rotating either require coordinating both.</p>
 *
 * <p>Trade-offs: the lifetime is configurable but bounded, and the bound is enforced by the shared minter
 * rather than here. A configurable lifetime lets an operator shorten it; the bound stops the same setting from
 * quietly making a per-call token into a durable credential. The default is one minute, which is far longer
 * than the account-context read timeout of three seconds and therefore cannot expire mid-call.</p>
 */
@Configuration(proxyBeanMethods = false)
public class InternalIdentityConfig {

    /**
     * The subject every token this service mints carries.
     *
     * <p>Assumptions: the value is this service's own name, so the account context's audit record says which
     * service called it rather than merely that an internal caller did. It is a constant rather than a
     * configured value because a service that could describe itself as something else would make that audit
     * record untrustworthy.</p>
     *
     * <p>Refactoring Rationale: the value is now taken from the shared kernel rather than written here as a
     * literal. The verifier admits a closed set of subjects, so this spelling and the one that set holds have
     * to agree exactly; two literals that must match are the shape in which a rename on one side becomes a
     * 401 on the other, discoverable only at run time and only on this path. Reading the constant makes the
     * two ends the same value rather than two values that happen to be equal.</p>
     */
    public static final String TOKEN_SUBJECT = InternalServiceToken.SUBJECT_AUTHORIZATION_SERVICE;

    /**
     * The default lifetime of a minted token.
     */
    public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(1);

    /**
     * Supplies the minter this service presents to the account context.
     *
     * <p>Assumptions: the key material is cleared from the intermediate array before this method returns. The
     * minter copies what it is given, so the local copy is a second live reference to a signing secret with no
     * further use. The originating {@code String} cannot be cleared -- it is held by the property source -- and
     * that limitation is recorded rather than implied by the wipe.</p>
     *
     * @param key the internal signing key from the deployment's secret store, either base64-encoded or raw
     *     text, carrying at least {@link InternalServiceToken#MIN_KEY_LENGTH} bytes once decoded; must not be
     *     {@code null} and must not be blank
     * @param lifetimeSeconds how long each minted token is valid for, in seconds
     * @param clock the clock the issue and expiry instants are read from; must not be {@code null}
     * @return the token minter, never {@code null}
     * @throws IllegalStateException if the configured key is blank, so the failure names the property rather
     *     than surfacing as a length complaint from the minter
     * @throws IllegalArgumentException if the decoded key is too short or the lifetime exceeds the shared
     *     bound, both of which the minter raises
     */
    @Bean
    public InternalServiceToken internalServiceToken(
            @Value("${carddemo.internal-identity.authorization-signing-key}") String key,
            @Value("${carddemo.internal-identity.token-lifetime-seconds:60}") long lifetimeSeconds,
            Clock clock) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.internal-identity.authorization-signing-key must be supplied: without it this service can"
                            + " present no credential to the account context, and every authorization would"
                            + " be refused with 401 and then redelivered until it dead-lettered");
        }
        byte[] material = decode(key);
        try {
            return new InternalServiceToken(material, TOKEN_SUBJECT, clock,
                    Duration.ofSeconds(lifetimeSeconds));
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    /**
     * Decodes the configured key, accepting either a base64 rendering or raw text.
     *
     * <p>Assumptions: base64 is attempted first and its result used only when it decodes to at least the
     * required length, which is the same rule the messaging key follows and for the same reason -- the two ways
     * this value legitimately arrives produce two different forms, since the infrastructure's generator emits
     * base64 while a locally exported development value is ordinary text.</p>
     *
     * @param key the configured value; must not be {@code null} and must not be blank
     * @return the key material, never {@code null}
     */
    private static byte[] decode(String key) {
        String trimmed = key.trim();
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length >= InternalServiceToken.MIN_KEY_LENGTH) {
                return decoded;
            }
        } catch (IllegalArgumentException notBase64) {
            // WHY : Assumptions: an unparseable value is treated as a RAW key rather than as an error, and
            //   the exception is neither logged nor rethrown -- it carries the offending input in its
            //   message on some implementations, and that input is the signing secret. Nothing diagnostic
            //   is lost, because a genuinely unusable key is refused on length immediately afterwards with
            //   a message naming the requirement rather than the value.
            return trimmed.getBytes(StandardCharsets.UTF_8);
        }
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }
}
