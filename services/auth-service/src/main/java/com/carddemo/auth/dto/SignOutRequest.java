package com.carddemo.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries the refresh token a sign-out revokes at the identity pool.
 *
 * <p>Purpose: this is the body of {@code POST /api/v1/auth/signout}, the operation that ends a session
 * at the pool rather than only in the browser. It answers 204 with no body, so there is no response
 * shape paired with this record.
 *
 * <h2>Why one component and no identifier</h2>
 *
 * <p>Assumptions: the token is the whole of the request, and the absence of a {@code userId} beside it
 * is deliberate rather than an omission. The provider's revocation operation takes the token and the
 * confidential client's credentials and accepts no user name -- the token identifies its own subject to
 * the pool -- so an identifier submitted here would be read by nothing. The three sibling exchanges do
 * carry one because each has something to do with it: sign-on and the challenge answer compute a keyed
 * client proof over it, and the renewal applies a local membership gate to it. Revocation has neither
 * need, and admitting a field nothing reads would invite a later reader to believe it was checked.
 *
 * <p>Assumptions: this operation makes no membership decision either, which is the second reason no
 * identifier appears. Refusing to revoke a token because {@code auth.users} no longer holds a row for
 * its subject would leave a removed user's thirty-day token live, which is the opposite of what a
 * sign-out is for.
 *
 * <h2>This record has no reference counterpart</h2>
 *
 * <p>Assumptions: the baseline had nothing to revoke. Signing off transferred control back to the
 * sign-on screen and ended nothing, because the terminal session WAS the session and it lasted until the
 * terminal disconnected. No reference program, screen field or literal corresponds to this component, so
 * it carries no reference width and the sentences below are authored rather than transcribed.
 *
 * <h2>The token is a credential</h2>
 *
 * <p>Trade-offs: the refresh token is the longest-lived credential in this system -- it mints access
 * tokens without a password being presented again -- and it is handled here exactly as
 * {@link TokenRefreshRequest} handles it: never persisted, never returned, and withheld from this
 * record's own rendering by the override below. The accepted compromise is the one that record states:
 * it is a plain string at the transport boundary rather than a wrapper able to clear its own storage,
 * because a wrapper would still be deserialised from the contract's property by name and would still
 * hold the characters while the exchange ran.
 *
 * <p>Assumptions: the maximum is the same figure {@link TokenRefreshRequest} bounds the same value at,
 * and it is declared here rather than referenced from there because that record's constant is private to
 * it. The two are the same number for the same reason -- the value is opaque and provider-sized, so the
 * bound refuses an implausible token without being able to refuse a merely large one -- and a test
 * asserts the two agree so they cannot drift into refusing a token on one operation that the other
 * accepts.
 *
 * @param refreshToken the refresh token to revoke, a {@code String} that must not be blank and is at
 *     most {@link #REFRESH_TOKEN_MAX_LENGTH} characters, forwarded to the pool unaltered. It travels in
 *     this request only, appears in no response, and is never persisted or logged
 */
// WHY : Assumptions: the component publishes the package's non-whitespace pattern into the generated
//       document beside its non-blank constraint, and the committed schema declares the same facet, so
//       the two documents describe one shape. Presence expressed only as a minimum length would admit a
//       token of spaces, which cannot be a value the pool issued and which would be relayed to the pool
//       before being refused. It is a schema-documentation annotation rather than a second runtime
//       constraint, because the non-blank constraint already refuses exactly those values; the full
//       argument is recorded on SignOnRequest.NON_WHITESPACE_PATTERN.
public record SignOutRequest(
        @NotBlank(message = MESSAGE_REFRESH_TOKEN_REQUIRED)
        @Schema(pattern = SignOnRequest.NON_WHITESPACE_PATTERN)
        @Size(max = REFRESH_TOKEN_MAX_LENGTH, message = MESSAGE_REFRESH_TOKEN_TOO_LONG)
        String refreshToken) {

    /**
     * The greatest number of characters a submitted refresh token may carry.
     *
     * <p>Assumptions: eight thousand one hundred and ninety-two, the same bound
     * {@link TokenRefreshRequest} applies to the same value, because the value is the same value. Tokens
     * this pool issues are on the order of one to two thousand characters, so the figure is several times
     * the largest observed and cannot refuse one the pool legitimately issued; it is also an eighth of
     * the shared body ceiling {@code com.carddemo.common.web.RequestBodySizeFilter} enforces, which keeps
     * the two controls ordered rather than coincident -- the filter answers 413 and names the request,
     * this bound answers 400 and names the field.
     */
    static final int REFRESH_TOKEN_MAX_LENGTH = 8192;

    /**
     * The sentence reported when no refresh token was submitted.
     *
     * <p>Assumptions: this is the sentence the renewal reports for the same absent member, reused because
     * the caller's remedy is identical -- there is nothing to revoke and the session has to be
     * re-established. A sentence describing the token's shape would describe the pool's mechanics to an
     * unauthenticated caller for no gain.
     */
    private static final String MESSAGE_REFRESH_TOKEN_REQUIRED =
            "Please sign on again to renew your session ...";

    /**
     * The sentence reported when the submitted token exceeds its declared maximum.
     *
     * <p>Assumptions: it names the number, in the shape the sibling records' overflow sentences use, and
     * the distinction from {@link #MESSAGE_REFRESH_TOKEN_REQUIRED} is deliberate. That sentence withholds
     * the token's shape because describing the pool's mechanics buys nothing; a maximum is not the pool's
     * mechanics but this API's own published facet, declared in the committed contract where any caller
     * can already read it, so naming it here tells an integrator which of the two bounds refused the
     * call.
     */
    private static final String MESSAGE_REFRESH_TOKEN_TOO_LONG =
            "Refresh token must be at most " + REFRESH_TOKEN_MAX_LENGTH + " characters ...";

    /**
     * Renders this request with the refresh token withheld.
     *
     * <p>Refactoring Rationale: a record's generated rendering names every component verbatim, and this
     * record's only component mints access tokens on its own. The obligation that it never reaches a log
     * is stated above, but the generated rendering would defeat it through paths nobody writes
     * deliberately -- an interpolated request object, an assertion message, an exception message, or a
     * framework trace of a failed request. Overriding it makes the withholding a property of the type.
     *
     * <p>Alternatives Considered: rendering a prefix, a suffix or the length of the token. Rejected on
     * the ground {@link SignOnRequest} records for its own credential: any run of characters from a
     * credential narrows a guess at the whole of it, and a length is exactly the fact that makes an
     * exhaustive guess cheaper. Nothing else is rendered because there is nothing else on this record.
     *
     * @return a constant placeholder in place of the token, naming the record type so a reader can tell
     *     which request produced the line; never {@code null}
     */
    @Override
    public String toString() {
        return "SignOutRequest[refreshToken=<withheld>]";
    }
}
