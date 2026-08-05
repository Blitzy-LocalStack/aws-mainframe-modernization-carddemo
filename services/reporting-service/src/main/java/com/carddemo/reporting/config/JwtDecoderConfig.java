package com.carddemo.reporting.config;

import com.carddemo.common.security.CognitoAccessTokenValidator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds this module's token decoder so that a presented token is checked for its KIND, its minting
 * client and its scope, and not only for its signature, issuer, audience and time window.
 *
 * <h2>Why this class exists at all</h2>
 *
 * <p>Refactoring Rationale: this module's configuration declared an issuer location and nothing else,
 * which is the framework's minimum and is not sufficient for the provider this migration adopts. One
 * Cognito user pool issues tokens for every app client registered against it and mints two token kinds
 * per sign-in, and the identity token carries the app client id in its audience claim -- so an identity
 * token, and a token obtained by an unrelated client sharing the pool, both satisfied every check the
 * declared configuration performed. Neither the token-kind check nor the scope check is expressible as
 * a property in any release of this framework, which is why they are composed here in code rather than
 * added to {@code application.yml}.</p>
 *
 * <p>Assumptions: the framework's own validators are composed IN rather than replaced. The
 * issuer-and-time validator is obtained from the framework's own factory, and the audience validator is
 * the one the {@code audiences} property configures, so this class adds checks and removes none. Writing
 * the issuer or time checks by hand here would duplicate logic the framework maintains and would be the
 * kind of hand-rolled security code that goes stale silently.</p>
 *
 * <p>Alternatives Considered: putting these checks in a request filter or in a method-security
 * expression instead. Rejected because a filter runs after the token has already been accepted as valid,
 * so an unacceptable token would be authenticated and then rejected -- and anything that ran before the
 * filter, including the framework's own authentication event publication, would already have treated it
 * as a legitimate credential. A validator runs as part of the decision itself.</p>
 *
 * <p>Alternatives Considered: relying on the API Gateway HTTP API's JWT authorizer to require the scope
 * at the edge. Rejected as a sole control, not as a control: it stays in place, but a service inside the
 * private application subnets is reachable by every task in the tier, so a service that trusts the edge
 * for its authorization decisions is one route misconfiguration or one internal caller away from having
 * none.</p>
 */
@Configuration
public class JwtDecoderConfig {

    /** The issuer location the pool publishes, from which the signing keys are resolved. */
    private final String issuerUri;

    /** The app client id a token's client claim must name. */
    private final String appClientId;

    /** The scopes of which a presented token must carry at least one. */
    private final List<String> requiredScopes;

    /**
     * Captures the three configured values this module validates against.
     *
     * @param issuerUri the user pool's issuer location, taken from the same property the framework
     *     would have used, so that one value configures both the key resolution and the issuer check
     * @param appClientId the user-pool app client id the Cognito module outputs; a token naming a
     *     different client is refused
     * @param requiredScopes the scopes of which a token must carry at least one, as a comma-separated
     *     list; the default is the scope this bounded context publishes, so a deployment that has not
     *     set the value still refuses a token minted for another context
     */
    public JwtDecoderConfig(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${carddemo.security.app-client-id:${CARDDEMO_COGNITO_APP_CLIENT_ID:}}")
                    String appClientId,
            @Value("${carddemo.security.required-scopes:carddemo/reporting.read}")
                    List<String> requiredScopes) {
        this.issuerUri = issuerUri;
        this.appClientId = appClientId;
        this.requiredScopes = List.copyOf(requiredScopes);
    }

    /**
     * Builds the decoder the resource server authenticates every request with.
     *
     * <p>Assumptions: declaring this bean replaces the one the framework would have auto-configured
     * from the issuer property, and that is why the framework's issuer-and-time validator is obtained
     * explicitly below. Omitting it would silently drop the checks the auto-configured decoder
     * performed, which is the failure mode a hand-built decoder most often introduces.</p>
     *
     * @return the decoder, carrying the framework's issuer and time validation, the audience validation
     *     the configured audiences imply, and this module's token-kind, client and scope validation
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();

        // WHY : Assumptions: the issuer-and-time validator comes from the framework's own factory
        //       rather than being assembled here, so a future release that adds a default check gains
        //       it here too. The audience check is applied by the validator the `audiences` property
        //       configures, which the framework composes into the same chain; this class deliberately
        //       does not restate it, because two places asserting one audience is two places it can be
        //       narrowed unevenly.
        OAuth2TokenValidator<Jwt> composed = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new CognitoAccessTokenValidator(appClientId, requiredScopes));

        decoder.setJwtValidator(composed);
        return decoder;
    }
}
