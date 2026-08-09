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
 * Wires the single keyed tokeniser this context holds for identities it derives rather than echoes.
 *
 * <h2>Why this configuration exists</h2>
 *
 * <p>Refactoring Rationale: this class was authored to key {@link OpaqueIdentifier} for the two
 * first-in-first-out identities on the reply path, so that a primary account number stopped reaching SQS
 * message metadata. That purpose is withdrawn. Sections 0.4.1.8 and 0.7.6 of the technical specification
 * freeze those identities as literal values -- {@code MessageGroupId = card_num} and
 * {@code MessageDeduplicationId = transaction_id} -- and the specification is the agreed source of truth.
 * The derivation was not a free improvement either: a group identity is an ordering guarantee only while
 * it is EQUAL for equal cards across every producer on the queue, and a deduplication identity suppresses
 * a duplicate only while the REQUESTER that may resend can predict it, so keying both from this service's
 * own secret removed both guarantees for anybody else on the same queue. The reply path therefore emits
 * the frozen values, and the metadata exposure that follows is registered as divergence
 * {@code D-AUTHORIZATION-FIFO-IDENTITY-METADATA} in
 * {@code docs/architecture/cobol-to-service-traceability.md}, bounded by the queue's
 * customer-managed-key encryption, its private-network-only reachability and task-role-scoped read
 * access.</p>
 *
 * <p>Assumptions: what this class supplies is still a real primitive rather than a leftover -- the keyed
 * tokeniser the derived-identity surfaces of {@code .mapper} take as a parameter, namely
 * {@code AuthorizationMessageMapper.businessCorrelationToken} and
 * {@code MappingDiagnostic.structuredFields(OpaqueIdentifier)}, both of which stand for values this
 * context computes for itself rather than values a producer contract fixes. No component injects the bean
 * at present. It is retained rather than retired for one stated reason and not from inertia: the
 * deployment contract that delivers its key is asserted by {@code infra/modules/ecs-service}, which
 * requires the authorization task and no other to receive {@code CARDDEMO_MESSAGING_HMAC_KEY}, and
 * provisioned by both environment roots -- withdrawing the bean means withdrawing a provisioned secret
 * and a module validation condition together, which is a change of a different scope from correcting an
 * identity.</p>
 *
 * <p>Assumptions: it is deliberately a configuration of its own rather than a method on
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
 * workload that reads cardholder extracts, while this key is held by a long-running request consumer.
 * Sharing one key would mean rotating it required a coordinated stop of both, and it would give the
 * migration workload the ability to compute values a production consumer derives.</p>
 *
 * <p>Alternatives Considered: a random per-instance key. Rejected because every identity worth deriving
 * from this key has to be STABLE across restarts and across instances to be worth anything -- a
 * correlation token that changed per instance would not join two log lines about one authorization, and a
 * diagnostic digest that changed per instance would not group two reports of one recurring fault. This
 * reasoning is what previously ruled a per-instance key out for the queue group identity as well; that
 * identity is now literal, and the stability requirement survives for the identities that remain.</p>
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
                    "carddemo.messaging.hmac-key must be supplied: without a secret key every identity"
                            + " derived through this tokeniser would be an unkeyed digest of a small"
                            + " candidate space, which an adversary holding the token confirms by"
                            + " enumeration");
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
