package com.carddemo.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries the identifier and the refresh token a token-set renewal is performed with.
 *
 * <p>Purpose: this is the body of {@code POST /api/v1/auth/refresh}, which exchanges the refresh token
 * a previous sign-on returned for a new access token and identity token, answered as
 * {@link SignOnResponse} with a null {@code refreshToken} because the pool does not reissue one on
 * renewal.
 *
 * <h2>This record has no reference counterpart</h2>
 *
 * <p>Assumptions: the reference had nothing to renew. A sign-on under the transaction monitor lasted as
 * long as the terminal session did, so no reference program, screen field or literal corresponds to
 * either component here, and neither of the two carries a reference width. The identifier's bound is
 * the exception and it comes from the user record rather than from this operation. The operation exists
 * because a bearer token has a finite lifetime, so a session that is to outlive one access token
 * without the user re-entering a credential needs an exchange the reference never had. It is published
 * unauthenticated for a reason worth stating, since it looks like a relaxation: the token it would
 * carry is the one being renewed, and a caller whose access token has already expired must still be
 * able to renew. Authority comes from the refresh token itself, which the pool verifies.
 *
 * <h2>The refresh token is a credential</h2>
 *
 * <p>Trade-offs: the refresh token is credential material of the longest-lived kind in this system --
 * it mints access tokens without a password being presented again -- and it is treated accordingly. It
 * is never persisted, because {@code auth.users} has no column that could hold it; it is never returned
 * on this operation's response, which carries a null in that position for exactly that reason; and the
 * rendering below is what keeps it out of a log line, an assertion message or a framework trace. The
 * accepted compromise is the one {@link SignOnRequest} records: it is a plain string at the transport
 * boundary rather than a wrapper type able to clear its own storage, because a wrapper would still be
 * deserialised from the contract's property by name and would still hold the characters while the
 * exchange ran.
 *
 * <p>Assumptions: no upper bound is declared on the token, matching the committed contract, which
 * declares {@code minLength: 1} on it and no maximum. The value is opaque and provider-sized, and a
 * bound guessed here would refuse a token the pool had legitimately issued -- which for this operation
 * means locking a caller out until it signs on again with a credential it may no longer be holding. The
 * cost is that an oversized body is refused by the transport's own request-size limit rather than by a
 * field constraint, which is one hop later than this record would refuse it.
 *
 * <h2>Component order</h2>
 *
 * <p>Assumptions: the order is the committed contract's property order for this schema, identifier then
 * token, and it records the contract rather than a reported order. There is no reference chain whose
 * first matching branch decides which sentence a caller sees first, so there is nothing here for a
 * declared order to reproduce.
 *
 * <p>Alternatives Considered: implementing {@code com.carddemo.common.error.FieldOrdering} so the
 * shared advice latches one sentence. Declined for the reason above -- that interface exists to
 * reproduce a reference program's latching order, and there is no reference program to reproduce -- and
 * the consequence is registered rather than hidden: the advice accumulates one entry per offending
 * field, which is the behaviour the register records as {@code D-ERROR-ACCUMULATION} and which suits an
 * operation whose contract names both fields as reportable.
 *
 * @param userId the identifier the token set being renewed was issued for, a {@code String} of at most
 *     8 characters and not blank. Eight is the width {@code SEC-USR-ID PIC X(08)} declares at
 *     {@code app/cpy/CSUSR01Y.cpy} line 18 and the maximum the committed schema declares. A token
 *     presented against a different identifier is refused by the pool, reported as 401
 * @param refreshToken the refresh token a previous sign-on returned, a {@code String} that must not be
 *     blank and is otherwise unbounded here for the reason recorded above, replayed unaltered. It
 *     travels in this request only, appears in no response, and is never persisted or logged
 */
// WHY : Assumptions: both components publish the package's non-whitespace pattern into the generated
//       document beside their non-blank constraint, and the committed schema declares the same facet on
//       each, so the two documents describe one shape. Presence expressed only as a minimum length would
//       admit a token of spaces, which cannot be a value the pool issued and which would be relayed to a
//       third party before being refused. It is a schema-documentation annotation rather than a second
//       runtime constraint, because the non-blank constraint already refuses exactly those values; the full
//       argument is recorded on SignOnRequest.NON_WHITESPACE_PATTERN.
public record TokenRefreshRequest(
        @NotBlank(message = MESSAGE_USER_ID_REQUIRED)
        @Schema(pattern = SignOnRequest.NON_WHITESPACE_PATTERN)
        @Size(max = USER_ID_MAX_LENGTH, message = MESSAGE_USER_ID_TOO_LONG) String userId,
        @NotBlank(message = MESSAGE_REFRESH_TOKEN_REQUIRED)
        @Schema(pattern = SignOnRequest.NON_WHITESPACE_PATTERN) String refreshToken) {

    /**
     * The sentence reported when the identifier is absent from the renewal.
     *
     * <p>Assumptions: authored for the target, because the reference has no renewal exchange and so no
     * literal for this condition. It is worded in the shape the reference literals use -- a statement
     * ending in an ellipsis -- and it reuses none of the three sign-on sentences, none of which
     * describes this failure.
     */
    private static final String MESSAGE_USER_ID_REQUIRED = "Please enter User ID ...";

    /**
     * The sentence reported when the identifier exceeds its declared width.
     *
     * <p>Assumptions: authored for the target on the same footing as the sibling sentence on
     * {@link SignOnRequest} -- a reference screen field cannot overflow its own declared width, so the
     * condition has no reference branch. It names the number because the number is a reference width a
     * caller can act on.
     */
    private static final String MESSAGE_USER_ID_TOO_LONG =
            "User ID must be at most 8 characters ...";

    /**
     * The sentence reported when no refresh token was submitted.
     *
     * <p>Assumptions: the wording names the caller's only remedy rather than the internal cause. A
     * renewal without a token cannot be completed by correcting a field, so telling the caller to sign
     * on is the whole of the actionable content, and a sentence describing the token's shape would
     * describe the pool's mechanics to an unauthenticated caller for no gain.
     */
    private static final String MESSAGE_REFRESH_TOKEN_REQUIRED =
            "Please sign on again to renew your session ...";

    /**
     * The number of positions the reference declares for a user identifier.
     *
     * <p>Assumptions: eight is {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} line 18 and
     * {@code maxLength: 8} on this schema. It is named rather than written into the annotation because
     * it is the only reference-derived number in this record, and a bare literal beside a component
     * that deliberately carries no bound would read as an inconsistency rather than as a distinction.
     */
    private static final int USER_ID_MAX_LENGTH = 8;

    /**
     * Renders this request with the refresh token withheld.
     *
     * <p>Refactoring Rationale: a record's generated rendering names every component verbatim, and one
     * of these two mints access tokens on its own. The obligation that it never reaches a log is stated
     * in this file's type documentation, but the generated rendering would defeat it through paths
     * nobody writes deliberately -- an interpolated request object, an assertion message, an exception
     * message, or a framework trace of a failed request. Overriding it makes the withholding a property
     * of the type.
     *
     * <p>Alternatives Considered: rendering a prefix, a suffix or the length of the token. Rejected on
     * the ground {@link SignOnRequest} records for its own credential: any run of characters from a
     * credential narrows a guess at the whole of it, and a length is exactly the fact that makes an
     * exhaustive guess cheaper. The identifier is rendered in full because it is the only thing
     * distinguishing one renewal from another in a log, and it is not protected data.
     *
     * @return the identifier as submitted, paired with a constant placeholder in place of the token, in
     *     the component order this record declares; never {@code null}
     */
    @Override
    public String toString() {
        return "TokenRefreshRequest[userId=" + this.userId + ", refreshToken=<withheld>]";
    }
}
