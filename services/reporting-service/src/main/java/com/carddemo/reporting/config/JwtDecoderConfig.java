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
 * issuer-and-time validator is obtained from the framework's own factory, so this class adds checks and
 * removes none. Writing the issuer or time checks by hand here would duplicate logic the framework
 * maintains and would be the kind of hand-rolled security code that goes stale silently.</p>
 *
 * <p>Refactoring Rationale: NO audience validator takes part, and this paragraph replaces one that said
 * otherwise. Three places in this class previously described the chain as carrying "the audience
 * validation the configured audiences imply" and declined to restate it "because two places asserting one
 * audience is two places it can be narrowed unevenly". There is no first place: no {@code audiences} key
 * exists in any profile of this module, and this module's {@code application.yml} says so in as many
 * words, on the ground that an audience validator would reject every ACCESS token the sign-on flow
 * issues -- a Cognito access token carries no audience claim at all -- while accepting exactly the
 * identity tokens this class exists to refuse. The correction matters because the false version described
 * a chain with four checks where three run, and the missing one was the check on WHICH client obtained
 * the token; the {@code client_id} comparison inside the shared validator is what actually performs
 * that, which is why a blank client id is refused in the constructor.</p>
 *
 * <p>Assumptions: the chain a token must pass is therefore exactly, and in this order: the framework's
 * signature, issuer and time-window validation, then the shared validator's token-kind check, then its
 * {@code client_id} check, then its scope check. Naming the whole list here is deliberate -- a reader
 * auditing what this resource server accepts should not have to assemble it from two files.</p>
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
     * Captures the configured values this module validates against.
     *
     * <p>Refactoring Rationale: the three {@code carddemo.security.jwt.*} keys are read here, and an
     * earlier revision read two differently-named keys instead -- {@code carddemo.security.app-client-id}
     * and {@code carddemo.security.required-scopes}. That was a real divergence rather than a naming
     * preference. Every service in this repository, including this one, declares the
     * {@code carddemo.security.jwt.*} shape in its {@code application.yml}, and the infrastructure
     * injects {@code CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID} into every task, so the old names
     * resolved to nothing a deployment set: the client-id default was BLANK, the shared validator reads
     * blank as "skip this check", and this service silently accepted a token minted for any client of
     * the pool while its own configuration file said otherwise.</p>
     *
     * <p>Assumptions: the client id now has NO fallback, so a task started without it fails at startup
     * rather than serving requests with one of the three checks quietly disabled. The token-kind value
     * is asserted against the shared validator's compiled constant for the same reason the sibling
     * contexts assert it -- the property exists so the requirement is visible to an operator, and the
     * assertion is what keeps the visible value and the enforced value from drifting apart.</p>
     *
     * @param issuerUri the user pool's issuer location, taken from the same property the framework
     *     would have used, so that one value configures both the key resolution and the issuer check
     * @param expectedTokenUse the token kind this service accepts, which must equal
     *     {@link CognitoAccessTokenValidator#ACCESS_TOKEN_USE}
     * @param appClientId the user-pool app client id the Cognito module outputs; a token naming a
     *     different client is refused, and a blank value is refused outright
     * @param requiredScopes the scopes of which a token must carry at least one, as a comma-separated
     *     list, so a token minted for another context is refused here
     * @throws IllegalStateException if {@code expectedTokenUse} does not name the access token, or if
     *     {@code appClientId} is {@code null} or blank
     */
    public JwtDecoderConfig(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${carddemo.security.jwt.expected-token-use}") String expectedTokenUse,
            @Value("${carddemo.security.jwt.expected-client-id}") String appClientId,
            @Value("${carddemo.security.jwt.required-scope}") List<String> requiredScopes) {

        if (!CognitoAccessTokenValidator.ACCESS_TOKEN_USE.equals(expectedTokenUse)) {
            throw new IllegalStateException("carddemo.security.jwt.expected-token-use must be \""
                    + CognitoAccessTokenValidator.ACCESS_TOKEN_USE + "\"");
        }

        if (appClientId == null || appClientId.isBlank()) {
            throw new IllegalStateException(
                    "carddemo.security.jwt.expected-client-id must name the app client");
        }

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
     * @return the decoder, carrying the framework's signature, issuer and time validation followed by
     *     this module's token-kind, client-id and scope validation, and no audience validation at all,
     *     for the reason recorded on this class; never {@code null}
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();

        // WHY : Assumptions: the issuer-and-time validator comes from the framework's own factory
        //       rather than being assembled here, so a future release that adds a default check gains
        //       it here too.
        // WHY : Assumptions: the composition below is the WHOLE chain. There is no audience validator
        //       in it, and none is configured elsewhere -- no `audiences` key appears in any profile of
        //       this module, which its own application.yml states explicitly. An earlier revision of
        //       this comment claimed the framework composed one in from that property and that this
        //       class declined to restate it; both halves were false, and the effect was to describe a
        //       client check that nothing performed. The client check is the `client_id` comparison
        //       inside the shared validator on the second line, which is why the constructor refuses a
        //       blank client id rather than letting that comparison be skipped.
        OAuth2TokenValidator<Jwt> composed = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                new CognitoAccessTokenValidator(appClientId, requiredScopes));

        decoder.setJwtValidator(composed);
        return decoder;
    }
}
