package com.carddemo.authorization.config;

import com.carddemo.common.security.OpaqueIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the keyed tokeniser that derives every opaque identity this context puts into queue metadata.
 *
 * <h2>Why this configuration exists</h2>
 *
 * <p>Refactoring Rationale: {@link OpaqueIdentifier} and the two accessors that use it -- the group
 * identity and the correlation identity on the shared authorization codec -- were authored and then never
 * reached by any running code, because no bean supplied a key. The consequence was concrete rather than
 * cosmetic: the reply path fell back to the CARD NUMBER as its first-in-first-out group identity, so a
 * primary account number was written into the outbox row and then into SQS message metadata on every
 * single reply. Queue metadata sits outside the message body, appears in queue telemetry and in the trace
 * of every send, and is carried into logs and metrics -- which is exactly where
 * {@code docs/adr/ADR-008-security-and-identity.md} requires an account number to be masked. This class
 * is the missing wiring, and it is deliberately a configuration of its own rather than a method on
 * {@link SqsConfig}: the tokeniser is a SECURITY primitive keyed from the deployment's secret store,
 * while {@code SqsConfig} wires a transport client, and keeping the key handling in a file whose only
 * subject is the key makes it possible to read every use of that key at once.</p>
 *
 * <p>Assumptions: the key is a DEPLOYMENT secret with no default, delivered as a container secret from
 * the secret store, so a deployment that does not supply it fails at startup. There is no safe default:
 * a fixed or absent key would reduce the token to an unkeyed digest of a sixteen-digit number, which an
 * adversary holding the token confirms by enumerating candidates -- so a default would produce a value
 * that LOOKS opaque and reverses in seconds. Failing to start is the correct outcome.</p>
 *
 * <p>Assumptions: this key is a DIFFERENT secret from the extract-transform-load masking key. The two
 * have different trust purposes and different holders: the masking key is held by a one-off migration
 * workload that reads cardholder extracts, while this key is held by a long-running request consumer and
 * is shared with every other producer on the same queue so that one card's messages land in one group.
 * Sharing one key would mean rotating it required a coordinated stop of both, and it would give the
 * migration workload the ability to compute production queue group identities.</p>
 *
 * <p>Alternatives Considered: deriving the two identities from a random per-instance key. Rejected
 * outright, because a group identity has to be equal for equal cards ACROSS producers and across
 * restarts -- that equality is the entire ordering guarantee -- and a per-instance key would put one
 * card's messages into as many groups as there are instances, silently removing the per-card ordering
 * the reference consumer gets from being single-threaded.</p>
 *
 * <p>Alternatives Considered: an unkeyed digest, or a keyed digest with a public salt. Both rejected for
 * the same reason: the protected value is a sixteen-digit number with a checksum, so the candidate space
 * is small enough to enumerate. Only a secret key makes the token unconfirmable by someone who holds it.
 * This reasoning is stated on {@link OpaqueIdentifier} itself and is repeated here because this is the
 * file that chooses where the key comes from.</p>
 *
 * <p>Trade-offs: the key is accepted in either base64 or raw-text form and the form is detected rather
 * than configured. A single mandated encoding would be simpler to reason about, and it is not used
 * because the two ways this key legitimately arrives produce two different forms -- Terraform's random
 * byte generator emits base64, while a locally exported development value is ordinary text -- and a
 * deployment that supplied the other form would fail with a length error that names nothing useful.
 * Detection costs one attempted decode at startup and never runs again.</p>
 */
@Configuration(proxyBeanMethods = false)
public class MessagingIdentityConfig {

    /**
     * The bean name every consumer of the messaging tokeniser qualifies by.
     *
     * <p>Assumptions: the bean is NAMED and injected by name rather than by type. More than one keyed
     * tokeniser can legitimately exist in one application -- a different purpose needs a different key --
     * and injection by type alone would silently bind whichever one the context happened to hold. Naming
     * it means adding a second tokeniser is a compile-time decision at each call site.</p>
     */
    public static final String MESSAGING_TOKENISER = "messagingOpaqueIdentifier";

    /**
     * Supplies the keyed tokeniser that derives queue group and correlation identities.
     *
     * <p>Assumptions: the key material is cleared from the intermediate array before this method
     * returns. {@link OpaqueIdentifier} copies what it is given, so the local copy is a second live
     * reference to secret bytes with no further use; clearing it shortens the window in which a heap dump
     * would contain two copies. The originating {@code String} cannot be cleared -- it is interned in the
     * environment and held by the property source -- which is a limitation of receiving a secret as a
     * string and is recorded here rather than implied by the wipe.</p>
     *
     * @param key the messaging HMAC key from the deployment's secret store, either base64-encoded or raw
     *     text, carrying at least {@link OpaqueIdentifier#MIN_KEY_LENGTH} bytes once decoded; must not be
     *     {@code null} and must not be blank
     * @return the keyed tokeniser, never {@code null}
     * @throws IllegalStateException if the configured value is blank, so the failure names the property
     *     rather than surfacing as a length complaint from the tokeniser
     * @throws IllegalArgumentException if the decoded key is shorter than
     *     {@link OpaqueIdentifier#MIN_KEY_LENGTH} bytes, which the tokeniser raises
     */
    @Bean(MESSAGING_TOKENISER)
    public OpaqueIdentifier messagingOpaqueIdentifier(
            @Value("${carddemo.messaging.hmac-key}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.messaging.hmac-key must be supplied: without a secret key the queue group"
                            + " identity would be an unkeyed digest of a card number, which an adversary"
                            + " holding the token confirms by enumeration");
        }
        byte[] material = decode(key);
        try {
            return new OpaqueIdentifier(material);
        } finally {
            Arrays.fill(material, (byte) 0);
        }
    }

    /**
     * Decodes the configured key, accepting either a base64 rendering or raw text.
     *
     * <p>Assumptions: base64 is attempted FIRST and its result is used only when it decodes to at least
     * the required length. A raw text key of the required length is also valid base64-looking input in
     * some cases, and decoding it would yield fewer bytes than the operator supplied; requiring the
     * decoded result to reach the minimum is what makes the wrong branch unreachable for a key that is
     * long enough as text.</p>
     *
     * <p>Trade-offs: this cannot distinguish a base64 key that decodes short from a raw key that is
     * short. Both fall through to the raw branch and are then refused by the tokeniser on length, which
     * is the same outcome by a different message, so nothing is lost.</p>
     *
     * @param key the configured value; must not be {@code null} and must not be blank
     * @return the key material, never {@code null}
     */
    private static byte[] decode(String key) {
        String trimmed = key.trim();
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length >= OpaqueIdentifier.MIN_KEY_LENGTH) {
                return decoded;
            }
        } catch (IllegalArgumentException notBase64) {
            // WHY : Assumptions: an unparseable value is a RAW key rather than an error, so control falls
            //   through to the raw branch below. The exception is not logged and not rethrown: it carries
            //   the offending input in its message on some implementations, and that input is the secret.
            //   Swallowing it loses no diagnostic, because a genuinely unusable key is refused on length
            //   immediately afterwards with a message that names the requirement and not the value.
            return trimmed.getBytes(StandardCharsets.UTF_8);
        }
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Names the qualifier annotation callers use so the constant is discoverable from one place.
     *
     * <p>Assumptions: this nested annotation exists so a consumer writes
     * {@code @MessagingTokeniser OpaqueIdentifier tokeniser} instead of repeating a bean-name string
     * literal at every injection point. A literal repeated across call sites is the form in which a
     * rename becomes a run-time failure; a qualifier annotation makes the same rename a compile error.</p>
     */
    @java.lang.annotation.Documented
    @Qualifier(MESSAGING_TOKENISER)
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD,
            java.lang.annotation.ElementType.METHOD,
            java.lang.annotation.ElementType.PARAMETER,
            java.lang.annotation.ElementType.TYPE})
    public @interface MessagingTokeniser {
    }
}
