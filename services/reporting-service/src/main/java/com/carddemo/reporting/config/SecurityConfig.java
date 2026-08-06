package com.carddemo.reporting.config;

import com.carddemo.common.security.CognitoAccessTokenValidator;
import com.carddemo.common.security.JwtRoleConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
 * <p>Assumptions: this class carries TWO of the three responsibilities its siblings carry -- the filter
 * chain and the group-to-authority conversion -- and deliberately not the third. The decoder that
 * installs the provider-specific token checks is {@code JwtDecoderConfig} in this same package, which
 * predates this class and already reads the same {@code carddemo.security.jwt.*} keys every sibling
 * reads. Declaring a second decoder bean here would make which of the two the resource server used
 * depend on bean ordering rather than on anything written down, so the split is stated at both ends
 * instead.</p>
 *
 * <p>Refactoring Rationale: an earlier revision of this module had a decoder but no filter chain, so
 * the framework's default chain decided authorization -- which authenticates every valid token and
 * authorizes every path. The report request and the statement download both read ledger and account data
 * through the read-only views, so a chain that admitted an unauthenticated caller would disclose them.
 * This class is where the module gained an authorization decision of its own.</p>
 *
 * <p>Assumptions: no route here is administrator-only. The baseline reaches the report request screen
 * {@code app/cbl/CORPT00C.cbl} from the MAIN menu, {@code app/cbl/COMEN01C.cbl}, which both user types
 * reach, so restricting it to an administrator would REMOVE a capability an ordinary user has today.
 * Transformation rule T9 permits no behavioural change without a documented divergence, and tightening a
 * control is still a change.</p>
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
}
