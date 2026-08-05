package com.carddemo.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Verifies the closed Cognito-group authority mapping and its startup drift guard.
 *
 * <p>Assumptions: tokens are assembled in memory because signature and issuer verification happen
 * before the converter runs. Each test therefore controls only the claim shape whose conversion it
 * asserts.</p>
 */
class JwtRoleConverterTest {

    /**
     * Confirms the two invariant Cognito groups become their verbatim authorities.
     */
    @Test
    @DisplayName("both invariant groups become authorities without a role prefix")
    void invariantGroupsBecomeAuthorities() {
        Collection<GrantedAuthority> authorities = converter().convert(token(Map.of(
                JwtRoleConverter.GROUPS_CLAIM,
                List.of(JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY))));

        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(
                        JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
    }

    /**
     * Confirms absent, empty and unrecognised group claims grant no authority.
     */
    @Test
    @DisplayName("claims outside the closed group contract grant no authority")
    void claimsOutsideContractGrantNoAuthority() {
        assertThat(converter().convert(token(Map.of()))).isEmpty();
        assertThat(converter().convert(token(Map.of(JwtRoleConverter.GROUPS_CLAIM, List.of()))))
                .isEmpty();
        assertThat(converter()
                        .convert(token(Map.of(
                                JwtRoleConverter.GROUPS_CLAIM, List.of("carddemo-unknown")))))
                .isEmpty();
    }

    /**
     * Confirms the returned authority collection cannot be changed by a caller.
     */
    @Test
    @DisplayName("the returned authority collection is immutable")
    void returnedAuthorityCollectionIsImmutable() {
        Collection<GrantedAuthority> authorities = converter().convert(token(Map.of(
                JwtRoleConverter.GROUPS_CLAIM, List.of(JwtRoleConverter.USER_AUTHORITY))));

        assertThatThrownBy(() -> authorities.clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Confirms service construction fails before request handling when configured group names drift.
     */
    @Test
    @DisplayName("configured group-name drift fails converter construction")
    void configuredGroupNameDriftFailsConstruction() {
        assertThatThrownBy(
                        () -> new JwtRoleConverter("renamed-admin", JwtRoleConverter.USER_AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("configured Cognito group name does not match the compiled authority contract")
                .hasMessageNotContaining("renamed-admin");
        assertThatThrownBy(
                        () -> new JwtRoleConverter(JwtRoleConverter.ADMIN_AUTHORITY, "renamed-user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("renamed-user");
    }

    /**
     * Confirms a missing configured name is rejected rather than converted into a string.
     */
    @Test
    @DisplayName("a missing configured group name fails converter construction")
    void missingConfiguredGroupNameFailsConstruction() {
        assertThatThrownBy(() -> new JwtRoleConverter(null, JwtRoleConverter.USER_AUTHORITY))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JwtRoleConverter(JwtRoleConverter.ADMIN_AUTHORITY, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Builds a converter using the invariant group names emitted by the infrastructure module.
     *
     * @return the converter under test
     */
    private static JwtRoleConverter converter() {
        return new JwtRoleConverter(
                JwtRoleConverter.ADMIN_AUTHORITY, JwtRoleConverter.USER_AUTHORITY);
    }

    /**
     * Builds a decoded token containing the supplied claims.
     *
     * @param claims the claim names and values the expectation exercises
     * @return a decoded token suitable for the pure converter
     */
    private static Jwt token(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("header.payload.signature")
                .header("alg", "RS256")
                .subject("converter-test-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(builder::claim);
        return builder.build();
    }
}
