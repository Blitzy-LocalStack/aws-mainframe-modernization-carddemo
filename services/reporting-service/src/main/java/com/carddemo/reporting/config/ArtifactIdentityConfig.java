package com.carddemo.reporting.config;

import com.carddemo.common.security.OpaqueIdentifier;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the keyed tokeniser that names a stored statement artifact without naming its cardholder.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: a statement is published as two objects in an object store, and an object key is not a
 * private thing. The store writes it to its own access log for every request that touches it, indexes it
 * for listing, and reports it in a bucket inventory; a content-encryption key protects the object's
 * BYTES and reaches none of that. So whatever a key spells out is disclosed to everyone who can read
 * those streams, which is a wider audience than the cardholder the statement belongs to.</p>
 *
 * <p>Refactoring Rationale: the key spelled out the account identifier in full followed by the last four
 * digits of the card, and the comment that accompanied it argued that this was safe because "no primary
 * account number appears in an object key". That argument answered a narrower question than the one that
 * matters. An eleven-digit account identifier is named by the sensitive-data logging contract in
 * {@code docs/architecture/observability.md} in its own right, and four card digits beside it narrow a
 * cardholder further than either value does alone -- so the key disclosed two protected values to a
 * stream no application-side control reaches. The token this class produces discloses neither and still
 * names one artifact stably, which is the whole of what a key has to do.</p>
 *
 * <p>Assumptions: the tokeniser is KEYED and not a bare digest, and the reason is the same one the
 * shared kernel records on {@link OpaqueIdentifier} itself: an account identifier is eleven digits and a
 * card number sixteen, so an unkeyed digest of either is confirmed by guessing across a space small
 * enough to enumerate. A confirmable token discloses the value it was meant to withhold while looking
 * like a control, which is worse than a token that discloses nothing.</p>
 *
 * <p>Alternatives Considered: a random identifier per artifact, held in a mapping relation. That is
 * unconditionally unlinkable and therefore stronger, and it was rejected on the same operational ground
 * the reporting views record for choosing a keyed digest over a surrogate: it needs a writable relation
 * and a row inserted per artifact, and this bounded context owns neither a writable relation nor a
 * maintenance job. A keyed token is computed on read, needs no storage, and is stable across runs -- so
 * a rerun of a night overwrites the artifact it replaces instead of accumulating a second copy under a
 * new name.</p>
 *
 * <p>Trade-offs: the token is recoverable BY the holder of the key, which is the deployment itself and
 * no one else. That is what makes an operator able to locate a named cardholder's artifact deliberately,
 * through the same lookup the application performs, rather than by reading it off a key.</p>
 *
 * <p>Assumptions: this configuration keeps its key and its purpose scope in the context that owns them
 * rather than generalising a keyed-tokeniser factory into the shared kernel. Alternatives Considered:
 * such a factory, taking the property name and the purpose scope as arguments. Rejected because it would
 * save the bean method and nothing else, while making the key a context tokenises under something a
 * reader has to trace through a parameter instead of reading in the context that owns it. Refactoring
 * Rationale: this paragraph cited {@code com.carddemo.authorization.config.MessagingIdentityConfig} as
 * the sibling this class was modelled on. That class is withdrawn -- nothing injected the bean it
 * declared once the queue identities became the literal values the specification freezes -- so the
 * citation is replaced by the reasoning it was standing in for. This is now the only keyed-tokeniser
 * configuration in the reactor, and the pattern is stated here rather than referred to elsewhere.</p>
 *
 * <p>Assumptions: {@code proxyBeanMethods = false} because no bean method here calls another, so the
 * CGLIB subclass a proxying configuration creates would add start-up cost and no behaviour.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ArtifactIdentityConfig {

    /**
     * Configuration property carrying the artifact-identity key material.
     *
     * <p>Assumptions: the value is supplied from the deployment's secret store through an environment
     * variable and appears in no configuration file with a value. It is declared with no default,
     * deliberately: a default key would make every deployment tokenise identically, so a token minted in
     * one environment would name the same artifact in another and the scoping the key provides would be
     * nominal.</p>
     */
    public static final String ARTIFACT_HMAC_KEY_PROPERTY = "carddemo.reporting.artifact.hmac-key";

    /**
     * The bean name every consumer of the artifact tokeniser qualifies by.
     *
     * <p>Assumptions: the bean is named and injected by name rather than by type, so that adding a
     * second tokeniser under a different key is a compile-time decision at each call site rather than a
     * silent rebinding of whichever instance the context happened to hold.</p>
     */
    public static final String ARTIFACT_TOKENISER = "artifactOpaqueIdentifier";

    /**
     * Supplies the keyed tokeniser artifact object keys are built with.
     *
     * <p>Assumptions: the intermediate key array is cleared before this method returns.
     * {@link OpaqueIdentifier} copies what it is given, so the local array is a second live reference to
     * secret bytes with no further use, and clearing it shortens the window in which a heap dump would
     * hold two copies. The originating string cannot be cleared -- it is held by the property source --
     * which is a limitation of receiving a secret as a string and is recorded here rather than implied
     * by the wipe.</p>
     *
     * @param key the artifact-identity key from the deployment's secret store, either base64-encoded or
     *     raw text, carrying at least {@link OpaqueIdentifier#MIN_KEY_LENGTH} bytes once decoded; must
     *     not be {@code null} and must not be blank
     * @return the keyed tokeniser, never {@code null}
     * @throws IllegalStateException if the configured value is blank, so that the failure names the
     *     property rather than surfacing as a length complaint from the tokeniser
     * @throws IllegalArgumentException if the decoded key is shorter than
     *     {@link OpaqueIdentifier#MIN_KEY_LENGTH} bytes, which the tokeniser raises
     */
    @Bean(ARTIFACT_TOKENISER)
    public OpaqueIdentifier artifactOpaqueIdentifier(
            @Value("${" + ARTIFACT_HMAC_KEY_PROPERTY + "}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    ARTIFACT_HMAC_KEY_PROPERTY + " must be supplied: without a secret key the artifact"
                            + " identity would be an unkeyed digest of an account identifier, which an"
                            + " adversary holding the key name confirms by enumerating eleven digits");
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
     * <p>Assumptions: base64 is attempted first and its result is used only when it decodes to at least
     * the required length. Raw text of the required length can itself look like valid base64 input, and
     * decoding it would yield fewer bytes than the operator supplied; requiring the decoded result to
     * reach the minimum is what makes the wrong branch unreachable for a key that is long enough as
     * text.</p>
     *
     * <p>Trade-offs: this cannot tell a base64 key that decodes short from a raw key that is short.
     * Both fall through to the raw branch and are refused by the tokeniser on length, which is the same
     * outcome by a different message, so nothing is lost.</p>
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
            // WHY : Assumptions: an unparseable value is treated as a RAW key rather than as an error,
            //       so control falls through to the return below. An operator supplying a long random
            //       passphrase rather than a base64 blob is supplying valid key material, and refusing
            //       it would force a rendering choice on a secret for no cryptographic reason. The
            //       exception is named and not swallowed silently: it carries no information beyond
            //       "this was not base64", which the fall-through already expresses.
            return trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return trimmed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
