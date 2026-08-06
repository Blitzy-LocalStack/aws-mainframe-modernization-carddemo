package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Verifies that a token is accepted only when it is an access token, minted by the expected client, and
 * carrying a required scope -- and that no refusal reproduces the credential it refused.
 *
 * <p>Assumptions: tokens are built directly with the claims each expectation is about. The signature,
 * issuer and time window are the framework's concern and are validated by the validators this one is
 * composed with, so no expectation here restates them.</p>
 */
class CognitoAccessTokenValidatorTest {

    /** The app client this resource server accepts tokens from. */
    private static final String CLIENT_ID = "1h57kf5cpq17m0eml12EXAMPLE";

    /** The scope this resource server requires. */
    private static final String SCOPE = "carddemo/reporting.read";

    /** The subject claim value used throughout, so an assertion about absence has something to name. */
    private static final String SUBJECT = "5f3d2c1b-0000-4a4a-9b9b-7c7c7c7c7c7c";

    /**
     * Confirms an access token from the expected client carrying the required scope is accepted.
     */
    @Test
    @DisplayName("an access token from the expected client with the required scope is accepted")
    void wellFormedAccessTokenIsAccepted() {
        OAuth2TokenValidatorResult result = validator().validate(
                token(Map.of("token_use", "access", "client_id", CLIENT_ID, "scope", SCOPE)));

        assertThat(result.hasErrors()).isFalse();
    }

    /**
     * Confirms the provider's identity token is refused even though it carries the same issuer, subject
     * and audience as the access token, which is the gap the framework's own validators leave open.
     */
    @Test
    @DisplayName("an identity token is refused, since it is a description and not a grant")
    void identityTokenIsRefused() {
        OAuth2TokenValidatorResult result = validator().validate(
                token(Map.of("token_use", "id", "client_id", CLIENT_ID, "scope", SCOPE)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().iterator().next().getDescription())
                .contains("not an access token");
    }

    /**
     * Confirms a token with no token-kind claim at all is refused, because a token whose kind cannot be
     * established must not be treated as an access grant.
     */
    @Test
    @DisplayName("a token carrying no token-kind claim is refused")
    void tokenWithoutKindClaimIsRefused() {
        OAuth2TokenValidatorResult result = validator().validate(
                token(Map.of("client_id", CLIENT_ID, "scope", SCOPE)));

        assertThat(result.hasErrors()).isTrue();
    }

    /**
     * Confirms a token minted by another app client of the same user pool is refused.
     */
    @Test
    @DisplayName("a token minted by a different client of the same pool is refused")
    void tokenFromAnotherClientIsRefused() {
        OAuth2TokenValidatorResult result = validator().validate(
                token(Map.of("token_use", "access", "client_id", "another-client", "scope", SCOPE)));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().iterator().next().getDescription())
                .contains("not issued to the client");
    }

    /**
     * Confirms a token carrying only unrelated scopes is refused, so a token minted for one bounded
     * context is not accepted by another.
     */
    @Test
    @DisplayName("a token carrying none of the required scopes is refused")
    void tokenWithoutRequiredScopeIsRefused() {
        OAuth2TokenValidatorResult result = validator().validate(token(
                Map.of("token_use", "access", "client_id", CLIENT_ID, "scope", "carddemo/cards.read")));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors().iterator().next().getDescription()).contains("none of the scopes");
    }

    /**
     * Confirms a token carrying the required scope among several is accepted, since the claim is a
     * space-delimited set rather than a single value.
     */
    @Test
    @DisplayName("a required scope is recognised among several granted scopes")
    void requiredScopeIsRecognisedAmongSeveral() {
        OAuth2TokenValidatorResult result = validator().validate(token(Map.of(
                "token_use", "access", "client_id", CLIENT_ID,
                "scope", "openid  carddemo/reporting.read profile")));

        assertThat(result.hasErrors()).isFalse();
    }

    /**
     * Confirms no refusal reproduces the token value or the subject, because an error description
     * reaches a log and a token is a usable credential.
     */
    @Test
    @DisplayName("a refusal names the requirement and never the token or its claims")
    void refusalDoesNotReproduceTheToken() {
        Jwt identityToken =
                token(Map.of("token_use", "id", "client_id", CLIENT_ID, "scope", SCOPE));

        String description = validator().validate(identityToken)
                .getErrors().iterator().next().getDescription();

        assertThat(description)
                .doesNotContain(identityToken.getTokenValue())
                .doesNotContain(SUBJECT)
                .doesNotContain(CLIENT_ID);
    }

    /**
     * Confirms an empty required-scope list skips the scope check while a null list is refused, so that
     * "no scope required" has to be stated rather than reached by omission.
     */
    @Test
    @DisplayName("an empty scope list skips the check and a null list is refused")
    void scopeConfigurationMustBeStated() {
        OAuth2TokenValidatorResult result =
                new CognitoAccessTokenValidator(CLIENT_ID, List.of())
                        .validate(token(Map.of("token_use", "access", "client_id", CLIENT_ID)));

        assertThat(result.hasErrors()).isFalse();
        assertThatThrownBy(() -> new CognitoAccessTokenValidator(CLIENT_ID, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Confirms a null or blank client id SKIPS the client check silently, which is the documented and
     * deliberately caller-hostile behaviour every caller in this repository refuses to rely on.
     *
     * <p>Refactoring Rationale: this expectation exists to pin behaviour that looks like a defect and is
     * not one, so that nobody "fixes" it here and nobody depends on it there. A shared kernel cannot tell
     * an unset property from a caller that has deliberately delegated the client check to an audience
     * validator, so raising here would break the second caller to serve the first. The refusal therefore
     * belongs to each caller that READS the property, and every {@code SecurityConfig} in this repository
     * plus {@code JwtDecoderConfig} now throws {@code IllegalStateException} while its context is being
     * built. The assertion below is what makes the division of responsibility visible at this end.</p>
     *
     * <p>Assumptions: the token used carries a client id belonging to a DIFFERENT client, so a validator
     * that had applied the check would have refused it. Passing a matching client id would leave the two
     * outcomes indistinguishable and the assertion would prove nothing.</p>
     */
    @Test
    @DisplayName("a null or blank client id skips the client check, which is why every caller refuses one")
    void blankClientIdSkipsTheClientCheck() {
        Jwt fromAnotherClient = token(
                Map.of("token_use", "access", "client_id", "7zzz9q0000000000000EXAMPLE", "scope", SCOPE));

        assertThat(validator().validate(fromAnotherClient).hasErrors())
                .as("with the client id configured, a token from another client must be refused")
                .isTrue();

        for (String unset : java.util.Arrays.asList(null, "", "   ")) {
            assertThat(new CognitoAccessTokenValidator(unset, List.of(SCOPE))
                            .validate(fromAnotherClient)
                            .hasErrors())
                    .as("a client id of [%s] leaves the check unapplied, silently", unset)
                    .isFalse();
        }
    }

    /**
     * Confirms the tokeniser produces stable, purpose-scoped, opaque identifiers and refuses key
     * material too short to key the underlying code.
     */
    @Test
    @DisplayName("an opaque identifier is stable, purpose-scoped, opaque and refuses a short key")
    void opaqueIdentifierContract() {
        byte[] key = "carddemo-opaque-id-key-for-tests".getBytes(StandardCharsets.UTF_8);
        String pan = "4111111111111111";

        String first = new OpaqueIdentifier(key).token("purpose-a", pan);
        String again = new OpaqueIdentifier(key).token("purpose-a", pan);
        String other = new OpaqueIdentifier(key).token("purpose-b", pan);
        String otherKey = new OpaqueIdentifier(
                "a-different-key-of-thirty-two-by".getBytes(StandardCharsets.UTF_8))
                .token("purpose-a", pan);

        assertThat(first).isEqualTo(again).hasSize(OpaqueIdentifier.TOKEN_LENGTH).doesNotContain(pan);
        assertThat(other).isNotEqualTo(first);
        assertThat(otherKey).isNotEqualTo(first);
        assertThatThrownBy(() -> new OpaqueIdentifier("short".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Builds the validator under test with the client and scope this resource server expects.
     *
     * @return the validator
     */
    private static CognitoAccessTokenValidator validator() {
        return new CognitoAccessTokenValidator(CLIENT_ID, List.of(SCOPE));
    }

    /**
     * Builds a decoded token carrying the given claims plus the ones every token has.
     *
     * <p>Assumptions: the header names an algorithm because the type requires a non-empty header map,
     * and no expectation here depends on its value: the signature is verified before a validator runs.</p>
     *
     * @param claims the claims the expectation is about
     * @return the decoded token
     */
    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("header.payload.signature")
                .header("alg", "RS256")
                .subject(SUBJECT)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
