package com.carddemo.common.security;

import java.util.List;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a token that is signed by the right issuer and issued for the right client but is still not
 * an access credential for this system.
 *
 * <h2>What the framework's own validators do not decide</h2>
 *
 * <p>A resource server configured with an issuer location validates the signature, the issuer, and the
 * time window; adding an audience validates the client the token was issued for. Those four leave two
 * gaps that matter, and both are specific to the identity provider this migration adopts rather than
 * general.</p>
 *
 * <p><strong>The token kind.</strong> A Cognito user pool mints an identity token and an access token
 * for the same sign-in. Both are signed by the same issuer, both carry the same subject, and the
 * identity token carries the app client id in its audience claim -- so an identity token satisfies every
 * check listed above. An identity token describes WHO a user is, for a client to render; it is not an
 * authorization credential and it does not carry the scopes that say what may be done. Accepting one is
 * accepting a description in place of a grant. The pool distinguishes them with a claim naming the
 * token's use, and this validator requires that claim to name the access token.</p>
 *
 * <p><strong>The scope.</strong> The access token carries the scopes the client was granted. A resource
 * server that ignores them accepts a token minted for any purpose the pool serves, so a token obtained
 * for one bounded context is accepted by another. This validator requires at least one of a configured
 * set of scopes to be present, so a token minted without the scope this service publishes is refused
 * here rather than at the edge alone.</p>
 *
 * <p>Alternatives Considered: relying on the edge to perform both checks, since the API Gateway HTTP
 * API's JWT authorizer can require a scope. Rejected because it leaves the service accepting anything
 * that reaches its port, and inside the private application subnets that is every task in the tier --
 * so a single service-to-service call, or one misconfigured route, would bypass both checks entirely.
 * The edge check stays; this one makes the service independently sound.</p>
 *
 * <p>Assumptions: the claim names are the ones the provider publishes -- {@code token_use} for the kind
 * and {@code client_id} for the minting client -- and they are declared as constants below rather than
 * written at the point of use, so a reader can see exactly which provider contract this class depends
 * on. The scope claim is a single space-delimited string on this provider, which is the form the
 * standard specifies, and it is read as such rather than as a list.</p>
 *
 * <p>Assumptions: every refusal reports the error code and a description naming which check failed, and
 * never the token or any claim value. A token is a bearer credential: reproducing one in a log turns
 * that log into a store of usable credentials, which is the reason the descriptions below name the
 * requirement instead of the value that missed it.</p>
 */
public final class CognitoAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    /** The claim naming which of the provider's two token kinds a token is. */
    public static final String TOKEN_USE_CLAIM = "token_use";

    /** The value that claim carries on an access token. */
    public static final String ACCESS_TOKEN_USE = "access";

    /** The claim naming the app client that obtained the token. */
    public static final String CLIENT_ID_CLAIM = "client_id";

    /** The claim carrying the granted scopes, as one space-delimited string. */
    public static final String SCOPE_CLAIM = "scope";

    /**
     * The error code every refusal from this validator carries.
     *
     * <p>Assumptions: the code is the standard one for a token the resource server will not accept,
     * so a client's existing handling for an unacceptable token applies unchanged and no client has to
     * learn a code specific to this system.</p>
     */
    public static final String ERROR_CODE = "invalid_token";

    /**
     * The app client id a token must name, or null when the client check is not applied at all.
     *
     * <p>Assumptions: this field is null ONLY when the caller passed a null or blank id, and that input
     * is fail-open by construction -- the check below is skipped entirely and the validator reports
     * nothing about having skipped it. Every caller in this repository therefore refuses a blank value
     * while its application context is being built rather than passing it here; the collapse to null
     * survives so that a future caller with a genuine audience validator can express "the client is
     * pinned elsewhere", not because any caller today may rely on it.</p>
     */
    private final String requiredClientId;

    /** The scopes of which a token must carry at least one. */
    private final List<String> requiredScopes;

    /**
     * Creates a validator for one client and one set of acceptable scopes.
     *
     * @param requiredClientId the app client id a token's client claim must equal. A {@code null} or
     *     blank value SKIPS the client check silently, so this parameter is deliberately hostile to a
     *     caller that supplies an unset property: it is appropriate only where an audience validator
     *     already pins the same client and the provider populates both claims identically, which is
     *     true of no caller in this repository. Every service here rejects a blank value at context
     *     build time instead, and this validator does not raise on it because a shared kernel cannot
     *     tell an unset property from a deliberate delegation -- only the caller reading the property
     *     can
     * @param requiredScopes the scopes of which a token must carry at least one; an empty list skips
     *     the scope check, which is appropriate only for a service publishing no scope of its own
     * @throws NullPointerException if {@code requiredScopes} is {@code null}; an empty list is the way
     *     to express "no scope required", because a null would leave it ambiguous whether the caller
     *     meant that or forgot to configure it
     */
    public CognitoAccessTokenValidator(String requiredClientId, List<String> requiredScopes) {
        Objects.requireNonNull(requiredScopes, "requiredScopes must not be null");

        this.requiredClientId = requiredClientId == null || requiredClientId.isBlank()
                ? null : requiredClientId;

        // WHY : Trade-offs: the list is copied into an immutable one, so a caller that later mutates
        //       the list it passed cannot change which scopes this validator accepts after the
        //       application context is built. One copy per context is the cost.
        this.requiredScopes = List.copyOf(requiredScopes);
    }

    /**
     * Applies the token-kind, client and scope checks to one decoded token.
     *
     * @param token the decoded token, already signature-, issuer- and time-validated by the validators
     *     the framework composes before this one. Audience is deliberately NOT among them: no caller in
     *     this repository configures an audience validator, because a Cognito access token carries no
     *     audience claim, which is exactly why the client check below exists
     * @return a success result when every configured check passes, or a failure result carrying one
     *     error naming the check that did not
     * @throws NullPointerException if {@code token} is {@code null}, which would mean the framework
     *     invoked a validator with nothing to validate
     */
    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Objects.requireNonNull(token, "token must not be null");

        String tokenUse = token.getClaimAsString(TOKEN_USE_CLAIM);
        if (!ACCESS_TOKEN_USE.equals(tokenUse)) {
            // WHY : Assumptions: an absent claim and a claim naming the identity token are refused by
            //       the same branch, and that is deliberate. A token from this provider always carries
            //       the claim, so its absence means the token came from somewhere else -- and a token
            //       whose kind cannot be established must not be treated as an access grant.
            return failure("the token is not an access token; this resource server accepts only a"
                    + " token whose " + TOKEN_USE_CLAIM + " claim is \"" + ACCESS_TOKEN_USE + "\"");
        }

        if (requiredClientId != null
                && !requiredClientId.equals(token.getClaimAsString(CLIENT_ID_CLAIM))) {
            return failure("the token was not issued to the client this resource server accepts");
        }

        if (!requiredScopes.isEmpty() && !carriesAnyRequiredScope(token)) {
            return failure("the token carries none of the scopes this resource server requires");
        }

        return OAuth2TokenValidatorResult.success();
    }

    /**
     * Reports whether a token carries at least one of the required scopes.
     *
     * @param token the decoded token
     * @return {@code true} when the scope claim is present and names at least one required scope
     */
    private boolean carriesAnyRequiredScope(Jwt token) {
        String scope = token.getClaimAsString(SCOPE_CLAIM);
        if (scope == null || scope.isBlank()) {
            return false;
        }

        // WHY : Assumptions: the claim is split on whitespace rather than on a single space, because
        //       the standard's delimiter is a space but a producer that emitted two would otherwise
        //       yield an empty element that matches nothing and silently fails a valid token.
        for (String granted : scope.split("\\s+")) {
            if (requiredScopes.contains(granted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds a failure result carrying one error that names the requirement rather than the token.
     *
     * @param description which check failed, phrased as the requirement that was not met
     * @return the failure result
     */
    private static OAuth2TokenValidatorResult failure(String description) {
        // WHY : Assumptions: the third argument is the specification's own error-registry location, so
        //       a client receiving the error can look the code up rather than searching this codebase
        //       for the string. It is a URI in the response and is not resolved by anything at run
        //       time.
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(ERROR_CODE, description,
                "https://datatracker.ietf.org/doc/html/rfc6750#section-3.1"));
    }
}
