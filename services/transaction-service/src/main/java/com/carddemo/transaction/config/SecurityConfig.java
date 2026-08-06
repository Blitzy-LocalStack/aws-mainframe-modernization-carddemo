package com.carddemo.transaction.config;

import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configures who may reach this context's endpoints, and which tokens are accepted at all.
 *
 * <p>This is the migrated form of the authorization check the baseline performs by reading the user type
 * out of the session structure it echoes between screen turns, declared at
 * {@code app/cpy/COCOM01Y.cpy} lines 19 to 44 with its two condition names for the administrator and
 * the ordinary user. That structure is storage the CLIENT hands back, so a client could in principle
 * assert its own user type; here the equivalent claim is signed by the identity provider and validated
 * on every request, so it cannot be asserted by the caller at all.</p>
 *
 * <p>Assumptions: this class has the same three responsibilities in every context of this repository --
 * a filter chain, the group-to-authority conversion, and the decoder that installs the token checks the
 * issuer alone does not make. It reads the same five property keys in every context, so the eight chains
 * cannot diverge in what they accept, and the shared classes it composes live in {@code common-lib}
 * rather than being restated per service. Only the route table below is specific to this context.</p>
 *
 * <p>Refactoring Rationale: an earlier revision of this repository declared those five keys in each
 * service's configuration and read none of them. The values were therefore documentation of an intent
 * rather than a control: a resource server configured with an issuer location alone validates the
 * signature, the issuer and the time window, and the identity token this provider mints from the same
 * signing key satisfies all three. This class is where the declared keys became effective.</p>
 *
 * <p>Assumptions: no route here is administrator-only, and that is a preserved property rather than an
 * oversight. The baseline reaches transaction list, view, add and bill payment from the MAIN menu,
 * {@code app/cbl/COMEN01C.cbl}, so all four are ordinary-user capabilities today. What this context
 * does tighten is the TOKEN it accepts, and it tightens it for a specific reason: these are the
 * operations whose misuse moves money, so a request admitted on an identity token would post against a
 * real account.</p>
 *
 * @see CognitoAccessTokenValidator
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The path the load balancer's health check and the container's own probe read.
     *
     * <p>Assumptions: this path is reachable WITHOUT a token, and it has to be. The target group polls
     * it with no credentials of any kind, so a chain that required one would fail every health check and
     * the task would be replaced continuously while being perfectly healthy. Only the health group is
     * opened; the remaining actuator endpoints stay behind the chain.</p>
     */
    public static final String HEALTH_PATH = "/actuator/health/**";

    /**
     * Builds the filter chain.
     *
     * <p>Assumptions: sessions are STATELESS. The baseline is pseudo-conversational and carries its
     * continuity in a structure the client echoes; the migrated form carries identity in the token and
     * selection context in the request path, so there is nothing left for a server-side session to
     * hold. Permitting one would reintroduce the sticky routing that horizontal scaling exists to
     * avoid.</p>
     *
     * <p>Trade-offs: cross-site request forgery protection is disabled, which for a cookie-authenticated
     * application would be a defect. It is not one here: every request authenticates with a bearer token
     * that a browser does not attach automatically, so the confused-deputy condition the protection
     * defends against cannot arise. Leaving it enabled would reject every non-browser client --
     * including the load balancer -- for no gain.</p>
     *
 * <p>Assumptions: every operation of this context requires authentication and none requires the
     * administrative authority, so the rule set is one permit for the probe and one blanket
     * requirement. Stating it this plainly is the point -- a reader can see that no operation was
     * forgotten, which a longer list of individually matched paths would not show.</p>
     *
     * @param http the chain builder; must not be {@code null}
     * @param authenticationConverter the token-to-authentication translation; must not be {@code null}
     * @return the configured chain, never {@code null}
     * @throws Exception when the chain cannot be built, which the builder declares
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
            JwtAuthenticationConverter authenticationConverter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HEALTH_PATH).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)))
                .build();
    }

    /**
     * Adapts the shared group-to-authority translation into the converter the resource server expects.
     *
     * <p>Assumptions: the shared converter maps a token to AUTHORITIES, while the resource server's
     * contract is a token to an AUTHENTICATION. The adapter supplies the missing half rather than the
     * shared class implementing the wider contract, which keeps the shared class free of any dependency
     * on the resource-server module -- {@code common-lib} is on the class path of every context,
     * including ones that expose no web endpoint at all.</p>
     *
     * <p>Assumptions: the authorities the shared converter emits are used VERBATIM, with no role prefix
     * added. The identity provider's group names are already the vocabulary this application authorizes
     * against, so prefixing them would create a second spelling of each group and every path expression
     * would have to know which spelling it was matching.</p>
     *
     * <p>Assumptions: the two group names are read from configuration and passed to the shared converter
     * rather than left implicit. The converter compares them with its own compiled constants and refuses
     * to start on a mismatch, so this is the point at which a deployed pool whose groups were renamed
     * becomes a startup failure instead of a service that authenticates every request and authorizes
     * none.</p>
     *
     * @param configuredAdminGroupName the administrator group name from runtime configuration; must
     *     equal {@link JwtRoleConverter#ADMIN_AUTHORITY}
     * @param configuredUserGroupName the ordinary-user group name from runtime configuration; must
     *     equal {@link JwtRoleConverter#USER_AUTHORITY}
     * @return the authentication converter, never {@code null}
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter(
            @Value("${carddemo.security.cognito.admin-group-name}") String configuredAdminGroupName,
            @Value("${carddemo.security.cognito.user-group-name}") String configuredUserGroupName) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new JwtRoleConverter(configuredAdminGroupName, configuredUserGroupName));
        return converter;
    }

    /**
     * Builds the decoder, with the provider-specific token checks delegated in behind the standard ones.
     *
     * <p>Assumptions: the decoder is declared here rather than left to the framework's own
     * auto-configuration, and that is the only way to add a validator. Setting an issuer location makes
     * the framework compose signature, issuer and time validation and offers no hook to extend the
     * composition, so a service that needs a fourth check has to build the decoder and compose the
     * validators itself. The standard set is composed FIRST and this addition second, so nothing is
     * replaced and no check is weakened -- a token still has to pass everything the framework would have
     * required.</p>
     *
     * <p>Alternatives Considered: the framework's own {@code audiences} property, which is the obvious
     * declarative route and is wrong for this provider. A Cognito ACCESS token carries no audience claim
     * at all -- the client identity travels in {@code client_id} instead -- so an audience validator
     * would reject every access token the sign-on flow issues while accepting exactly the identity
     * tokens the delegated validator exists to refuse. It would invert the control.</p>
     *
     * <p>Assumptions: the configured token-use value is asserted against the shared validator's own
     * compiled constant rather than passed to it. The validator decides the token KIND from a constant
     * because accepting a configurable kind would let a deployment configure the check away; the
     * property therefore exists so the requirement is visible in configuration, and this assertion is
     * what stops the visible value and the enforced value drifting apart.</p>
     *
     * @param issuerUri the user-pool issuer location the framework's standard validators are built
     *     from; must not be {@code null} or blank
     * @param expectedTokenUse the token kind this service accepts, which must equal
     *     {@link CognitoAccessTokenValidator#ACCESS_TOKEN_USE}
     * @param expectedClientId the app client id a token must name; must not be {@code null} or blank,
     *     because a blank value would silently skip the check
     * @param requiredScope the scope a token must carry
     * @return the decoder, never {@code null}
     * @throws IllegalStateException if {@code expectedTokenUse} does not name the access token, or if
     *     {@code expectedClientId} is blank
     */
    @Bean
    public JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${carddemo.security.jwt.expected-token-use}") String expectedTokenUse,
            @Value("${carddemo.security.jwt.expected-client-id}") String expectedClientId,
            @Value("${carddemo.security.jwt.required-scope}") String requiredScope) {

        // WHY : Assumptions: the configured kind is asserted against the shared validator's compiled
        //       constant instead of being handed to it. The validator decides the token KIND from a
        //       constant because a configurable kind could be configured away; the property exists so
        //       the requirement is visible where an operator reads configuration, and this assertion is
        //       what stops the visible value and the enforced value drifting apart.
        if (!CognitoAccessTokenValidator.ACCESS_TOKEN_USE.equals(expectedTokenUse)) {
            throw new IllegalStateException("carddemo.security.jwt.expected-token-use must be \""
                    + CognitoAccessTokenValidator.ACCESS_TOKEN_USE + "\"");
        }

        // WHY : Assumptions: a blank client id is refused rather than tolerated. The shared validator
        //       reads blank as "skip this check", which is correct only for a caller that has decided
        //       the audience validator pins the client instead. No service here has, so a blank value
        //       would mean the check was dropped by omission with nothing saying so.
        if (expectedClientId == null || expectedClientId.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.security.jwt.expected-client-id must name the app client");
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new CognitoAccessTokenValidator(expectedClientId, List.of(requiredScope))));
        return decoder;
    }
}
