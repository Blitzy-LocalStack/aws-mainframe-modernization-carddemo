package com.carddemo.transaction.config;

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
 * <p>Refactoring Rationale: the account-context calls this service makes -- the card cross-reference lookup a
 * transaction add resolves its account through, and the account read a bill payment checks a balance against --
 * carried NO credential at all. The account context admits no anonymous caller on those addresses: an
 * earlier-ordered chain requires the internal read scope and the user chain refuses the cross-reference subtree
 * outright, so each call would have been answered 401 and every add and every payment would have reported its
 * dependency as unavailable. This class is the missing credential.</p>
 *
 * <p>Assumptions: this context is the SECOND holder of the signing key besides the account context that
 * verifies it, and the pending-authorization context is the third. All three are named explicitly in
 * {@code infra/modules/ecs-service}'s biconditional secret clause, so a fourth holder is a plan-time failure
 * rather than a silent widening -- which matters because any holder of this key can mint a token the account
 * context accepts for its internal reads.</p>
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
 * <p>Assumptions: this key is separate from the extract-transform-load masking key and from the
 * pending-authorization context's messaging derivation key. All three are keyed HMAC secrets and none may
 * substitute for another: this one authenticates a caller, the messaging key derives an opaque queue identity,
 * and the masking key derives a redaction tag. Sharing any pair would mean that holding one capability granted
 * another, and would make rotating either require coordinating both.</p>
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
     */
    public static final String TOKEN_SUBJECT = "carddemo-transaction-service";

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
            @Value("${carddemo.internal-identity.signing-key}") String key,
            @Value("${carddemo.internal-identity.token-lifetime-seconds:60}") long lifetimeSeconds,
            Clock clock) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.internal-identity.signing-key must be supplied: without it this service can"
                            + " present no credential to the account context, so every transaction add and"
                            + " every bill payment would report its dependency as unavailable");
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
