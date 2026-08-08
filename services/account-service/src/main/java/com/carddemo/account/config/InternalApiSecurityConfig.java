package com.carddemo.account.config;

import com.carddemo.account.api.AccountController;
import com.carddemo.account.api.CardXrefController;
import com.carddemo.account.api.CustomerController;
import com.carddemo.common.error.ApiErrorSecurityHandlers;
import com.carddemo.common.security.InternalServiceToken;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Authorises the three internal reads another bounded context makes against this one.
 *
 * <h2>Why a second filter chain</h2>
 *
 * <p>Refactoring Rationale: this context authenticates every request against the identity provider, and the
 * three account-context reads are made by a QUEUE CONSUMER rather than by a signed-in person -- so there is no
 * identity-provider token for it to present, and every one of those calls would have been refused. That refusal
 * is not a security outcome but an outage: the caller treats any non-not-found failure as the dependency being
 * unavailable, so each authorization would have been redelivered until the queue dead-lettered it. Adding these
 * three paths to the human chain with a permissive rule would have been the other way to make the calls
 * succeed, and it is the wrong one -- the paths would then be open to any authenticated cardholder, and one of
 * them resolves a primary account number.</p>
 *
 * <p>Assumptions: this chain is ordered BEFORE the identity-provider chain and matches only the three internal
 * paths, so every other request falls through to the chain that was already there. Ordering matters absolutely
 * here: the framework offers a request to each chain in order and the first whose matcher accepts it decides it,
 * so a lower-precedence internal chain would never see a request at all.</p>
 *
 * <p>Assumptions: the token is verified against a SHARED SYMMETRIC key rather than against the identity
 * provider's published keys. The reasoning belongs to the shared minter and is not restated here beyond the
 * consequence: verification involves no network call, so an internal read cannot fail because a key document
 * was unreachable.</p>
 *
 * <p>Assumptions: FOUR properties of the token are checked and not one. The signature says it was minted by a
 * holder of the key; the issuer distinguishes it from a token the identity provider minted with an unrelated
 * key; the audience says it was minted for THIS service, so a token intended for another internal callee cannot
 * be replayed here; and the scope says it authorises this family of operations. Checking the signature alone
 * would admit any internal token for any purpose, which is the same mistake as having one credential for the
 * whole system.</p>
 *
 * <p>Trade-offs: the three paths are enumerated from the controllers' own constants rather than written as a
 * pattern such as an internal path prefix. A prefix would be shorter and would automatically cover a fourth
 * operation added later -- which is exactly the objection to it: a new operation would become reachable by an
 * internal caller without anyone deciding it should be. Enumeration makes each addition a visible edit here.</p>
 */
@Configuration(proxyBeanMethods = false)
public class InternalApiSecurityConfig {

    /**
     * The precedence of this chain, ahead of the identity-provider chain.
     *
     * <p>Assumptions: the value is stated as a constant so a test can assert the ordering rather than a reader
     * having to compare two annotations in two files. It is well below the default precedence the other chain
     * takes, and the gap is deliberate: it leaves room for a chain that must run even earlier without either
     * value having to be renumbered.</p>
     */
    public static final int CHAIN_ORDER = 10;

    /**
     * The authority a verified internal token confers.
     *
     * <p>Assumptions: the authority is the scope value itself with the framework's default prefix, because the
     * default authority converter prepends that prefix to each scope it reads. The composed value is stated as
     * a constant so the rule below and any test agree on one spelling rather than two that must match.</p>
     */
    public static final String INTERNAL_READ_AUTHORITY =
            "SCOPE_" + InternalServiceToken.SCOPE_ACCOUNT_CONTEXT_READ;

    /**
     * The exact paths this chain governs.
     *
     * <p>Assumptions: composed from the controllers' own published constants rather than from literals, so a
     * controller whose path moved could not leave this chain matching the old one -- which would silently
     * expose the moved path to the human chain instead.</p>
     *
     * <p>Trade-offs: package-visible rather than private. A test in this package asserts that this matcher
     * accepts exactly the three internal paths and refuses every neighbouring one, which is the property that
     * decides whether a path is governed by this chain or by the human one. Reaching that through the assembled
     * chain instead would require a servlet container and would report a mismatch as a status code, naming
     * neither the pattern that matched nor the one that did not. Widening to package visibility keeps the
     * method out of the module's API while making the discriminator directly assertable.</p>
     *
     * @return a matcher accepting exactly the three internal paths and nothing else, never {@code null}
     */
    static RequestMatcher internalPaths() {
        PathPatternRequestMatcher.Builder matchers = PathPatternRequestMatcher.withDefaults();

        // WHY : Refactoring Rationale: each matcher is bound to the METHOD its operation serves, where
        //   all three were previously bound to a path alone. A path-only matcher claims every method at
        //   that address, and the account address is served by TWO surfaces: the machine read is a GET
        //   and the end-user edit is a PUT on the same address, separated by chain rather than by
        //   prefix. Claiming the PUT here demanded the internal read scope from a browser token, which a
        //   browser token never carries, so the end-user update was unreachable -- a 403 with no
        //   explanation, and the kind of defect a path-only matcher produces silently the moment a
        //   second method is mounted. Measured before the change and after it.
        // WHY : Assumptions: the customer probe binds BOTH methods rather than only the HEAD its
        //   consumer issues. One handler serves both -- it is declared as a GET mapping and the
        //   framework answers HEAD from it by discarding the body -- so binding HEAD alone would leave
        //   the GET form of the same operation governed by the human chain, which denies the whole
        //   customer subtree, and the two forms of one operation would then answer differently.
        // WHY : Trade-offs: a method a matcher does not claim now falls through to the application
        //   chain instead of being authorised here and refused by the dispatcher afterwards. For the
        //   two internal-only subtrees that is strictly narrower -- the application chain denies them
        //   outright -- so a GET to the lookup address is refused rather than authorised and then
        //   answered as an unsupported method.
        return new OrRequestMatcher(
                matchers.matcher(HttpMethod.POST,
                        CardXrefController.BASE_PATH + CardXrefController.LOOKUP_PATH),
                matchers.matcher(HttpMethod.GET, AccountController.BASE_PATH + "/{accountId}"),
                matchers.matcher(HttpMethod.GET, CustomerController.BASE_PATH + "/{customerId}"),
                matchers.matcher(HttpMethod.HEAD, CustomerController.BASE_PATH + "/{customerId}"));
    }

    /**
     * Builds the chain that authorises the internal reads.
     *
     * <p>Assumptions: the chain is stateless and stores no session, matching the other chain and matching the
     * caller -- which presents a freshly minted token on every request and has nothing to keep between them.
     * </p>
     *
     * <p>Assumptions: cross-site request forgery protection is disabled on this chain. It defends a
     * cookie-authenticated browser session, and there is no browser and no cookie on this path: the credential
     * is a bearer token the caller has to construct, so a third-party site cannot cause it to be sent.</p>
     *
     * <p>Assumptions: the refusal bodies are rendered by the shared handlers, so a refusal on an internal path
     * has the same problem-document shape as one on a business path. Without them the framework answers with a
     * status and a challenge header and no body, and the caller's own error handling reads an unparseable
     * response as a dependency failure rather than as an authorization one.</p>
     *
     * @param http the chain builder; must not be {@code null}
     * @param decoder the internal token decoder; must not be {@code null}
     * @param clock the clock the rendered refusal bodies read their failure instant from; must not be
     *     {@code null}
     * @return the configured chain, never {@code null}
     * @throws Exception when the chain cannot be built, which the builder declares
     */
    @Bean
    @Order(CHAIN_ORDER)
    public SecurityFilterChain internalApiFilterChain(HttpSecurity http,
            @InternalTokenDecoder JwtDecoder decoder, Clock clock) throws Exception {
        return http
                .securityMatcher(internalPaths())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .anyRequest()
                        .hasAuthority(INTERNAL_READ_AUTHORITY))
                .oauth2ResourceServer(server -> server
                        .authenticationEntryPoint(ApiErrorSecurityHandlers.entryPoint(clock))
                        .accessDeniedHandler(ApiErrorSecurityHandlers.accessDeniedHandler(clock))
                        .jwt(jwt -> jwt
                                .decoder(decoder)
                                .jwtAuthenticationConverter(internalAuthenticationConverter())))
                .exceptionHandling(ApiErrorSecurityHandlers.renderingRefusals(clock))
                .build();
    }

    /**
     * Builds the decoder that verifies an internal token.
     *
     * <p>Assumptions: the decoder is pinned to ONE algorithm, taken from the shared constant. A decoder that
     * accepted whatever its input's header named is how a verifier comes to accept an unsigned token, so the
     * algorithm is stated on this side rather than read from the token.</p>
     *
     * <p>Assumptions: three validators are composed with the framework's default set rather than replacing it.
     * The default set is what enforces the expiry, and an internal token's short life is only a control if
     * something checks it -- so the additions are issuer, audience and nothing else, and the expiry keeps being
     * enforced by the machinery that already did.</p>
     *
     * <p>Assumptions: the bean is qualified rather than declared as the only {@code JwtDecoder}. This context
     * already has one, for the identity provider's tokens, and two unqualified beans of one type would make the
     * choice of which chain got which decoder depend on bean-definition order.</p>
     *
     * @param key the shared internal signing key from the deployment's secret store, base64 or raw text; must
     *     not be {@code null} and must not be blank
     * @return the decoder, never {@code null}
     * @throws IllegalStateException if the configured key is blank, so the failure names the property
     */
    @Bean
    @InternalTokenDecoder
    public JwtDecoder internalTokenDecoder(
            @Value("${carddemo.internal-identity.signing-key}") String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.internal-identity.signing-key must be supplied: without it this service cannot"
                            + " verify an internal caller, and the account-context reads the authorization"
                            + " service depends on would be refused");
        }
        // WHY : Assumptions: the length is checked HERE rather than left to the decoder builder, and this
        //   was established by running it: NimbusJwtDecoder.withSecretKey accepts a key shorter than the
        //   digest width without complaint, so a deployment given a short value would start, verify nothing
        //   reliably, and present a weakened credential boundary with no signal at all. The minting side
        //   refuses the same value at construction, so checking it on both ends keeps a misconfiguration a
        //   startup failure on whichever side is deployed first rather than a run-time authorization puzzle.
        byte[] keyMaterial = decode(key);
        if (keyMaterial.length < InternalServiceToken.MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "carddemo.internal-identity.signing-key must supply at least "
                            + InternalServiceToken.MIN_KEY_LENGTH
                            + " bytes to key the digest; a shorter key weakens the only credential standing"
                            + " between the internal account-context reads and an unauthenticated caller");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(keyMaterial,
                        InternalServiceToken.SIGNING_ALGORITHM_NAME))
                .macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm
                        .from(InternalServiceToken.SIGNING_ALGORITHM_NAME))
                .build();

        OAuth2TokenValidator<Jwt> issuerAndAudience = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                // WHY : (1) Assumptions: the issuer is validated as a STRING claim. The shared issuer is a
                //   deliberately opaque name carrying no colon, so the framework's default claim converter
                //   leaves it a String rather than converting it to a URL, and Jwt.getIssuer() -- which is
                //   URL-typed -- would raise on it. (2) Alternatives Considered: naming an HTTPS issuer so the
                //   typed accessor worked was rejected because it would invent a location that resolves to
                //   nothing and invite a reader to expect discovery metadata at it; there is no provider here,
                //   only two services sharing a key.
                new JwtClaimValidator<String>(JwtClaimNames.ISS,
                        InternalServiceToken.ISSUER::equals),
                // WHY : Assumptions: the audience claim is a LIST in the token, so the predicate tests
                //   membership rather than equality. A token may legitimately carry more than one audience,
                //   and an equality test against a single-element list would refuse a token that named this
                //   service correctly alongside another.
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audiences -> audiences != null
                                && audiences.contains(InternalServiceToken.AUDIENCE_ACCOUNT_CONTEXT)));
        decoder.setJwtValidator(issuerAndAudience);
        return decoder;
    }

    /**
     * Decodes the configured key, accepting either a base64 rendering or raw text.
     *
     * <p>Assumptions: the same rule as on the minting side, and deliberately the same code shape: the two ends
     * must derive identical bytes from one configured value, so a difference in how each decodes it would
     * produce a signature the other could not verify -- a failure that appears only at run time, only on this
     * path, and only as a 401.</p>
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
            // WHY : Assumptions: an unparseable value is a raw key. The exception is swallowed rather than
            //   logged because it carries the offending input on some implementations and that input is the
            //   signing secret. A value too short to key the digest is refused by the explicit length check
            //   in the caller above -- NOT by the decoder builder, which accepts one silently.
            return trimmed.getBytes(StandardCharsets.UTF_8);
        }
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the converter that turns a verified internal token into an authentication.
     *
     * <p>Assumptions: the default scope-to-authority conversion is used with its default prefix rather than
     * configured away. The authority the rule above requires is composed from that same prefix in one
     * constant, so the two agree by construction; changing the prefix here would require changing the constant
     * and nothing would report a mismatch except a 403 in production.</p>
     *
     * <p>Trade-offs: package-visible rather than private, for the same reason as the matcher above. A test in
     * this package feeds it a verified token and asserts the authority set it produces, which is what decides
     * whether the scope claim reaches the rule; asserting that through the chain would observe only the
     * resulting status.</p>
     *
     * @return the converter, never {@code null}
     */
    static JwtAuthenticationConverter internalAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(InternalServiceToken.SCOPE_CLAIM);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * Reports the authority a verified internal token must carry, for a test to assert against.
     *
     * <p>Assumptions: this exists so a test can pin the composed authority without reconstructing the prefix,
     * which is the part most likely to be got wrong in two places.</p>
     *
     * @return the required authority, never {@code null}
     */
    public static SimpleGrantedAuthority requiredAuthority() {
        return new SimpleGrantedAuthority(INTERNAL_READ_AUTHORITY);
    }

    /**
     * Marks the decoder that verifies internal tokens, as distinct from the identity provider's.
     *
     * <p>Assumptions: a qualifier annotation rather than a bean-name string at each injection point. This
     * context holds two decoders of the same type, so injection by type alone would bind whichever the context
     * happened to hold; a qualifier makes the choice explicit and makes a rename a compile error instead of a
     * run-time authorization failure.</p>
     */
    @java.lang.annotation.Documented
    @org.springframework.beans.factory.annotation.Qualifier("internalTokenDecoder")
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({java.lang.annotation.ElementType.FIELD,
            java.lang.annotation.ElementType.METHOD,
            java.lang.annotation.ElementType.PARAMETER,
            java.lang.annotation.ElementType.TYPE})
    public @interface InternalTokenDecoder {
    }

}
