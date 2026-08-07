package com.carddemo.authorization.config;

import com.carddemo.common.security.OpaqueIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the keyed tokeniser that keeps protected values out of queue metadata.
 *
 * <p>This configuration has no baseline counterpart, and the reason it exists is a property of the
 * target transport rather than of the migrated logic. The reference consumer correlates a reply to its
 * request with the card number followed by the acquirer's transaction identifier, and it groups nothing
 * because one long-running task reads its queue serially -- on z/OS both facts stayed inside the same
 * protected boundary as the data. In the migrated system the ordering group and the deduplication
 * identity are message ATTRIBUTES: the queue's server-side encryption covers a message body, not its
 * metadata, so those two values appear in queue telemetry, in the trace of every send and in anything
 * observing the queue. This bean supplies the keyed authentication code that stands in for them, so the
 * per-card ordering and the exactly-once acceptance the queue provides are preserved while the primary
 * account number and the transaction identifier never leave the body.</p>
 *
 * <p>Assumptions: the key material is the SAME value every other producer in the environment uses,
 * delivered as {@code CARDDEMO_MASK_HMAC_KEY} from the deployment's secret store. Sharing one key is
 * what makes two publisher tasks derive one group for one card; a per-task or per-process key would put
 * one card's replies into as many groups as there are tasks, which would silently drop the ordering
 * guarantee while leaving every message deliverable. The infrastructure states the same expectation
 * where the queue is declared, in {@code infra/modules/sqs/main.tf}.</p>
 *
 * <p>Assumptions: the property carries NO default. A committed default would be a committed secret and
 * would make every token in every environment computable from the source tree; an absent value therefore
 * stops start-up, which is the same policy the datasource, the issuer and the reply allowlist already
 * follow in this service's configuration.</p>
 */
@Configuration(proxyBeanMethods = false)
public class MessagingTokenConfig {

    /**
     * Supplies the tokeniser that queue group and deduplication identities are derived with.
     *
     * <p>Assumptions: the configured value is accepted in either of two forms -- standard base64, which
     * is how a randomly generated key of exact byte length is carried through a secret store without a
     * character-set question, or raw text, which is how a human-set development value is supplied. The
     * base64 form is attempted first and the raw bytes are used when it does not decode, so a value that
     * happens to be valid base64 is treated as encoded key material. That order matters and is stated
     * because the two readings of one string produce two different keys, and a mismatch between producer
     * and consumer would show up only as a lost ordering guarantee.</p>
     *
     * <p>Trade-offs: the decoded material is handed to the tokeniser, which copies it, and this method
     * keeps no reference of its own. It cannot clear the {@link String} the framework resolved the
     * property into -- an immutable string is not erasable -- so the key remains reachable in the heap
     * for the life of the process exactly as any injected credential is. That is accepted because the
     * alternative, reading the secret from the store on every derivation, would put a network call on
     * the path of every message and would still hold the value in a string while it was in use.</p>
     *
     * @param configuredKey the shared key material, base64-encoded or raw, at least
     *     {@link OpaqueIdentifier#MIN_KEY_LENGTH} bytes once decoded; must not be blank
     * @return the tokeniser, never {@code null}
     * @throws IllegalArgumentException if the decoded material is shorter than
     *     {@link OpaqueIdentifier#MIN_KEY_LENGTH} bytes, which the tokeniser refuses rather than
     *     silently keying a weaker code
     */
    @Bean
    public OpaqueIdentifier messagingTokeniser(
            @Value("${carddemo.security.mask-hmac-key}") String configuredKey) {
        return new OpaqueIdentifier(keyMaterialOf(configuredKey));
    }

    /**
     * Decodes the configured key, preferring the base64 reading.
     *
     * @param configuredKey the configured value; must not be {@code null}
     * @return the key material as bytes, never {@code null}
     */
    private byte[] keyMaterialOf(String configuredKey) {
        try {
            return Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException notBase64) {
            // WHY : Assumptions: a value that is not base64 is key material as typed, which is how a
            //       development environment supplies a passphrase. The failure is not logged and the
            //       exception is not rethrown, because the only information it carries is a property of
            //       the secret itself and the tokeniser refuses material that is too short anyway.
            return configuredKey.getBytes(StandardCharsets.UTF_8);
        }
    }
}
