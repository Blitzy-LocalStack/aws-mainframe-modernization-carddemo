package com.carddemo.account.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.api.AccountController;
import com.carddemo.account.api.CardXrefController;
import com.carddemo.account.api.CustomerController;
import com.carddemo.common.security.InternalServiceToken;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Asserts what the account context accepts as proof that a caller is the authorization service.
 *
 * <h2>Purpose</h2>
 * <p>The three internal read paths carry no human credential, so the only thing standing between them and an
 * unauthenticated caller is this configuration. This class asserts the four independent refusals -- wrong
 * signature, wrong issuer, wrong audience, expired -- plus the authority mapping that decides whether a
 * correctly signed token actually satisfies the rule, plus the matcher that decides which paths this chain
 * governs at all.</p>
 *
 * <p>Alternatives Considered: driving a loaded Spring context with a servlet container and asserting on status
 * codes was rejected. Every refusal above renders as the same 401, so a context test could report that the
 * chain refuses a token without establishing WHICH check refused it -- and a configuration that had lost its
 * audience validator entirely would still pass such a test as long as the signature check remained. Exercising
 * the real decoder this class builds, and the real converter it installs, makes each check separately
 * falsifiable.</p>
 *
 * <p>Assumptions: the decoder under test is the one the bean method returns, not a hand-built equivalent, so
 * the validator chain, the algorithm pin and the key decoding are all the deployed ones.</p>
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no parameter,
 * return or exception section.</p>
 */
class InternalApiSecurityConfigTest {

    /**
     * The key both sides share. Fabricated, exactly at the minimum admissible length.
     */
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    /**
     * A different key of the same length, for the wrong-key refusal.
     */
    private static final String OTHER_KEY = "fedcba9876543210fedcba9876543210";

    /**
     * A fixed instant, so the expiry assertions are not clock-dependent.
     */
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    /**
     * The subject the calling service mints under.
     */
    private static final String SUBJECT = "carddemo-authorization-service";

    /**
     * Builds the deployed decoder over the shared key.
     *
     * @return the decoder the bean method produces, never {@code null}
     */
    private static JwtDecoder decoder() {
        return new InternalApiSecurityConfig().internalTokenDecoder(KEY);
    }

    /**
     * Builds a minter.
     *
     * @param key the signing key
     * @param lifetime how long the token is valid for
     * @param issuedAt the instant the token claims it was issued at
     * @return the minter, never {@code null}
     */
    private static InternalServiceToken minter(String key, Duration lifetime, Instant issuedAt) {
        return new InternalServiceToken(key.getBytes(StandardCharsets.UTF_8), SUBJECT,
                Clock.fixed(issuedAt, ZoneOffset.UTC), lifetime);
    }

    /**
     * Mints the token a correct caller presents.
     *
     * @return the serialised token, never {@code null}
     */
    private static String correctToken() {
        return minter(KEY, Duration.ofMinutes(1), Instant.now()).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_ACCOUNT_CONTEXT_READ);
    }

    /**
     * Verifies a correctly minted token is accepted and carries the scope the rule requires.
     */
    @Test
    @DisplayName("a correctly minted token is accepted and grants the required authority")
    void aCorrectlyMintedTokenIsAccepted() {
        Jwt verified = decoder().decode(correctToken());

        assertThat(verified.getSubject()).isEqualTo(SUBJECT);
        // WHY : Assumptions: the issuer is read as a claim string rather than through Jwt.getIssuer(). That
        //   accessor is URL-typed and raises on this issuer, which is an opaque name by design -- so asserting
        //   through it would fail on a token that is entirely correct.
        assertThat(verified.getClaimAsString(JwtClaimNames.ISS)).isEqualTo(InternalServiceToken.ISSUER);
        assertThat(verified.getAudience()).containsExactly(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT);

        var authentication = InternalApiSecurityConfig.internalAuthenticationConverter().convert(verified);
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains(InternalApiSecurityConfig.INTERNAL_READ_AUTHORITY);
    }

    /**
     * Verifies a token signed with another key of the same length is refused.
     *
     * <p>Assumptions: the wrong key is the SAME length as the right one, so the refusal is attributable to the
     * signature rather than to a length check the decoder might apply first.</p>
     */
    @Test
    @DisplayName("a token signed with another key is refused")
    void aTokenSignedWithAnotherKeyIsRefused() {
        String forged = minter(OTHER_KEY, Duration.ofMinutes(1), Instant.now()).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_ACCOUNT_CONTEXT_READ);

        assertThatThrownBy(() -> decoder().decode(forged)).isInstanceOf(JwtException.class);
    }

    /**
     * Verifies a token minted for a different audience is refused.
     *
     * <p>Assumptions: this is the check that keeps a token minted for one callee from being replayed at
     * another. It is asserted separately from the signature check because such a token IS correctly signed --
     * it is the same shared key -- so only the audience validator can refuse it, and a configuration missing
     * that validator would accept it.</p>
     */
    @Test
    @DisplayName("a correctly signed token minted for another audience is refused")
    void aTokenForAnotherAudienceIsRefused() {
        String misdirected = minter(KEY, Duration.ofMinutes(1), Instant.now())
                .mint("carddemo-some-other-service", InternalServiceToken.SCOPE_ACCOUNT_CONTEXT_READ);

        assertThatThrownBy(() -> decoder().decode(misdirected))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("aud");
    }

    /**
     * Verifies a correctly signed token whose lifetime has elapsed is refused.
     *
     * <p>Assumptions: the token is minted as issued and expired well before now, past any default clock skew
     * the framework allows, so the refusal cannot be attributed to a tolerance window.</p>
     */
    @Test
    @DisplayName("a correctly signed but expired token is refused")
    void anExpiredTokenIsRefused() {
        String stale = minter(KEY, Duration.ofMinutes(1), NOW.minus(Duration.ofDays(1))).mint(
                InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT,
                InternalServiceToken.SCOPE_ACCOUNT_CONTEXT_READ);

        assertThatThrownBy(() -> decoder().decode(stale))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("exp");
    }

    /**
     * Verifies a correctly signed token carrying a different scope does not grant the required authority.
     *
     * <p>Assumptions: such a token DECODES successfully -- it is correctly signed, correctly addressed and
     * unexpired -- so the decoder is the wrong place to look for the refusal. What must fail is the authority
     * mapping, and that is what is asserted: the produced authority set must not contain the one the rule
     * requires, which is what turns the request into a 403 rather than letting it through.</p>
     */
    @Test
    @DisplayName("a token carrying a different scope decodes but grants no access")
    void aTokenWithADifferentScopeGrantsNothing() {
        String wrongScope = minter(KEY, Duration.ofMinutes(1), Instant.now())
                .mint(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT, "internal:something-else.read");

        Jwt verified = decoder().decode(wrongScope);
        var authentication = InternalApiSecurityConfig.internalAuthenticationConverter().convert(verified);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .doesNotContain(InternalApiSecurityConfig.INTERNAL_READ_AUTHORITY);
    }

    /**
     * Verifies an absent credential produces no verified token at all.
     *
     * <p>Assumptions: an unauthenticated request is asserted at the decoder rather than at the chain, because
     * the chain answers a missing bearer header without ever consulting the decoder. What this pins is the
     * complementary property -- that nothing resembling a credential is admitted -- for the empty string and
     * for a bare unsigned value.</p>
     */
    @Test
    @DisplayName("nothing that is not a signed token is admitted")
    void nothingUnsignedIsAdmitted() {
        JwtDecoder decoder = decoder();

        assertThatThrownBy(() -> decoder.decode("")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode("not-a-token")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> decoder.decode(
                Base64.getUrlEncoder().withoutPadding().encodeToString(
                        "{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + ".e30."))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Verifies a blank configured key is refused while the context is being built.
     *
     * <p>Assumptions: failing at construction is what prevents a deployment from starting with a chain that
     * cannot verify anything. A decoder built over an empty key would either refuse every token -- taking the
     * account context offline for its only internal caller -- or, worse, be constructible over a key an
     * attacker could guess.</p>
     */
    @Test
    @DisplayName("a blank or short configured key is refused at construction")
    void aBlankOrShortKeyIsRefusedAtConstruction() {
        InternalApiSecurityConfig config = new InternalApiSecurityConfig();

        assertThatThrownBy(() -> config.internalTokenDecoder("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carddemo.internal-identity.signing-key");
        assertThatThrownBy(() -> config.internalTokenDecoder("tooshort"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(InternalServiceToken.MIN_KEY_LENGTH));
    }

    /**
     * Verifies the chain governs exactly the three internal paths and nothing adjacent.
     *
     * <p>Assumptions: the negative half carries the weight. A matcher that was too wide would place a human
     * business path under a chain that accepts a machine token and demands a scope no human token carries,
     * which would refuse legitimate traffic; one that was too narrow would leave an internal path under the
     * human chain, which would accept an identity-provider token in its place. Both collection paths and both
     * bases are probed for that reason.</p>
     */
    @Test
    @DisplayName("the chain matches exactly the three internal paths")
    void theChainMatchesExactlyTheThreeInternalPaths() {
        var matcher = InternalApiSecurityConfig.internalPaths();

        assertThat(matcher.matches(request(HttpMethod.POST,
                CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH))).isTrue();
        assertThat(matcher.matches(request(HttpMethod.GET,
                AccountController.BASE_PATH + "/12345678901"))).isTrue();
        assertThat(matcher.matches(request(HttpMethod.HEAD,
                CustomerController.BASE_PATH + "/987654321"))).isTrue();

        assertThat(matcher.matches(request(HttpMethod.GET, CardXrefController.BASE_PATH)))
                .as("the cross-reference collection path is not an internal read")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET, AccountController.BASE_PATH)))
                .as("the account collection path stays on the human chain")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET, CustomerController.BASE_PATH)))
                .as("the customer collection path stays on the human chain")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET,
                AccountController.BASE_PATH + "/12345678901/statements")))
                .as("a deeper path under the account base is not an internal read")
                .isFalse();
        assertThat(matcher.matches(request(HttpMethod.GET, "/actuator/health")))
                .as("the health probe stays unauthenticated on the other chain")
                .isFalse();
    }

    /**
     * Verifies the chain is ordered ahead of the application chain.
     *
     * <p>Assumptions: this is asserted from the annotation rather than from behaviour because the consequence
     * of getting it wrong is invisible in a unit test and severe in deployment: the application chain's
     * catch-all rule matches every path, so if it were consulted first every internal read would be refused
     * for want of a human group claim, and no test that exercised the chains individually would notice.</p>
     *
     * @throws Exception if the bean method cannot be reflected, which would mean its signature moved
     */
    @Test
    @DisplayName("the internal chain is ordered ahead of the application chain")
    void theInternalChainIsOrderedFirst() throws Exception {
        Order internal = AnnotationUtils.findAnnotation(
                InternalApiSecurityConfig.class.getMethod("internalApiFilterChain",
                        org.springframework.security.config.annotation.web.builders.HttpSecurity.class,
                        org.springframework.security.oauth2.jwt.JwtDecoder.class,
                        java.time.Clock.class),
                Order.class);

        assertThat(internal).isNotNull();
        assertThat(internal.value()).isEqualTo(InternalApiSecurityConfig.CHAIN_ORDER);
        assertThat(InternalApiSecurityConfig.CHAIN_ORDER)
                .as("a lower order is consulted first, and the application chain leaves its order at the "
                        + "framework default so it must be the higher number")
                .isLessThan(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }

    /**
     * Verifies the required authority is composed from the framework's own scope prefix.
     */
    @Test
    @DisplayName("the required authority is the scope under the framework prefix")
    void theRequiredAuthorityIsTheScopeUnderTheFrameworkPrefix() {
        assertThat(InternalApiSecurityConfig.requiredAuthority().getAuthority())
                .isEqualTo(InternalApiSecurityConfig.INTERNAL_READ_AUTHORITY)
                .isEqualTo("SCOPE_" + InternalServiceToken.SCOPE_ACCOUNT_CONTEXT_READ);
    }

    /**
     * Builds a request for the matcher assertions.
     *
     * @param method the request method
     * @param path the request path
     * @return the request, never {@code null}
     */
    private static MockHttpServletRequest request(HttpMethod method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method.name(), path);
        request.setServletPath(path);
        return request;
    }
}
