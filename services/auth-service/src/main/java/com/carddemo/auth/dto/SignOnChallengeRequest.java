package com.carddemo.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries the answer to a {@code NEW_PASSWORD_REQUIRED} sign-on challenge.
 *
 * <p>Purpose: this is the body of {@code POST /api/v1/auth/challenge}, the operation that completes a
 * sign-on which returned {@link SignOnChallenge} instead of a token set. It carries the session value
 * that challenge issued, the identifier the challenge was raised for, and the permanent password to
 * set. On acceptance the pool issues the token set the sign-on could not, returned as
 * {@link SignOnResponse}.
 *
 * <h2>This record has no reference counterpart, and that is stated rather than implied</h2>
 *
 * <p>Assumptions: the reference sign-on compares a stored eight-character credential directly at
 * {@code app/cbl/COSGN00C.cbl} lines 211 to 256 and has no notion of a credential that must be
 * changed before use, so there is no reference field to derive a width from for two of these three
 * components and no reference sentence to carry across under transformation rule T8. The only width
 * with a reference source is the identifier's. This record exists because the credential moved to a
 * managed user pool whose accounts are created with temporary passwords -- see
 * {@code infra/modules/cognito} and its {@code temporary_password_validity_days} input -- which makes
 * this exchange the first thing every provisioned user performs. The divergence is registered as
 * {@code D-PASSWORD-CHALLENGE} in {@code docs/architecture/cobol-to-service-traceability.md}, and it
 * follows from entry {@code D-4} there, which records the plaintext credential field not being carried
 * forward.
 *
 * <h2>Component order, and why it is provenance rather than reported order</h2>
 *
 * <p>Assumptions: the order is the committed contract's own property order for this schema --
 * identifier, session, then new password -- and it means less here than it does on the sign-on body.
 * There is no reference chain whose first matching branch decides which sentence a caller is told
 * first, because there is no reference program. Declaration order therefore records the contract and
 * nothing more.
 *
 * <p>Alternatives Considered: implementing {@code com.carddemo.common.error.FieldOrdering}, which
 * {@link SignOnRequest} declares so that the shared advice latches its earliest failing field's
 * sentence. Declined here for the reason just given: that interface exists to reproduce a reference
 * program's latching order, and this operation has no reference program, so a declared order would be
 * an invention presented as a transcription. The consequence is registered rather than hidden --
 * without the interface the shared advice accumulates one entry per offending field instead of
 * latching one, which is the behaviour the register records as {@code D-ERROR-ACCUMULATION}, and it is
 * the correct behaviour for an operation whose contract names all three fields as reportable.
 *
 * <h2>Two of the three components are credentials</h2>
 *
 * <p>Trade-offs: the session and the new password are both credential material, and neither is
 * persisted, logged or echoed. There is no column for either -- the owning migration
 * {@code V1__auth.sql} declares {@code auth.users} with five columns, none of which could hold one --
 * no response record in this package declares a component for either, and the rendering below is what
 * keeps both out of a log line, an assertion message or a framework trace of a failed request. The
 * accepted compromise is the one {@link SignOnRequest} already records for its own credential: both
 * are plain strings at the transport boundary rather than wrapper types able to clear their own
 * storage, because a wrapper would still be deserialised from the contract's property by name and
 * would still hold the characters while the exchange ran.
 *
 * <p>Assumptions: the new password is bounded and present-checked here and its strength is judged
 * nowhere in this record, matching the committed contract exactly. The pool applies the configured
 * policy -- minimum length 14 with an input-validation floor of 12, and lower case, upper case, digit
 * and symbol each required, per {@code infra/modules/cognito/variables.tf} -- and a value failing it is
 * reported through the operation's 400 carrying the pool's own reason. Restating that policy here would
 * put it in two places, and this copy is the one that goes stale when an environment tightens the pool,
 * at which point this record would refuse passwords the pool accepts.
 *
 * @param userId the identifier the challenge was raised for, a {@code String} of at most 8 characters
 *     and not blank, as returned on the challenge body. Eight is the width
 *     {@code SEC-USR-ID PIC X(08)} declares at {@code app/cpy/CSUSR01Y.cpy} line 18 and the maximum
 *     the committed schema declares. It must name the user the session was issued for; the pool
 *     refuses the exchange otherwise, which the operation reports as 401
 * @param session the opaque continuation value from the challenge body, a {@code String} of at most
 *     4096 characters and not blank, echoed back verbatim. It is single-use: a second attempt with the
 *     same value requires a fresh sign-on. It is not a bearer token and no other operation accepts it
 * @param newPassword the permanent password to set, a {@code String} of at most 256 characters and not
 *     blank. Its length and composition are governed by the pool's password policy rather than by this
 *     record, and it is never persisted, logged or echoed by this service
 */
// WHY : Assumptions: all three components publish the package's non-whitespace pattern into the generated
//       document beside their non-blank constraint, and the committed schema declares the same facet on
//       each, so the two documents describe one shape. Presence expressed only as a minimum length would
//       admit a session of 4096 spaces, which cannot be a value the pool issued and which would be relayed
//       to a third party before being refused. It is a schema-documentation annotation rather than a second
//       runtime constraint, because the non-blank constraint already refuses exactly those values; the full
//       argument is recorded on SignOnRequest.NON_WHITESPACE_PATTERN.
public record SignOnChallengeRequest(
        @NotBlank(message = MESSAGE_USER_ID_REQUIRED)
        @Schema(pattern = SignOnRequest.NON_WHITESPACE_PATTERN)
        @Size(max = USER_ID_MAX_LENGTH, message = MESSAGE_USER_ID_TOO_LONG) String userId,
        @NotBlank(message = MESSAGE_SESSION_REQUIRED)
        @Schema(pattern = SignOnRequest.NON_WHITESPACE_PATTERN)
        @Size(max = SESSION_MAX_LENGTH, message = MESSAGE_SESSION_TOO_LONG) String session,
        @NotBlank(message = MESSAGE_NEW_PASSWORD_REQUIRED)
        @Schema(pattern = SignOnRequest.NON_WHITESPACE_PATTERN)
        @Size(max = NEW_PASSWORD_MAX_LENGTH,
                message = MESSAGE_NEW_PASSWORD_TOO_LONG) String newPassword) {

    /**
     * The sentence reported when the identifier is absent from the answer.
     *
     * <p>Assumptions: this sentence is authored for the target rather than carried across, because the
     * reference has no challenge exchange and therefore no literal for this condition. It is worded to
     * match the house shape the reference literals use -- a statement ending in an ellipsis -- so that
     * a body carrying it is indistinguishable in form from one carrying a transcribed sentence, and it
     * deliberately reuses none of the three sign-on sentences, none of which describes this failure.
     */
    private static final String MESSAGE_USER_ID_REQUIRED = "Please enter User ID ...";

    /**
     * The sentence reported when the identifier exceeds its declared width.
     *
     * <p>Assumptions: authored for the target on the same footing as the sibling sentence on
     * {@link SignOnRequest}, and for the same reason: a reference screen field cannot overflow its own
     * declared width, so no reference branch and no reference literal exists for the condition. It
     * names the number because the number is a reference width a caller can act on.
     */
    private static final String MESSAGE_USER_ID_TOO_LONG =
            "User ID must be at most 8 characters ...";

    /**
     * The sentence reported when the session value is absent.
     *
     * <p>Assumptions: the wording tells the caller what to do rather than naming the internal cause,
     * because the only remedy is to sign on again and obtain a fresh challenge. A sentence naming the
     * session's shape or origin would describe the pool's mechanics to an unauthenticated caller and
     * would still leave it with the same single course of action.
     */
    private static final String MESSAGE_SESSION_REQUIRED =
            "Please sign on again to obtain a new session ...";

    /**
     * The sentence reported when the session value is longer than this transport carries.
     *
     * <p>Assumptions: no number is named. The bound is the committed contract's, chosen because the
     * value is opaque and provider-sized rather than declared anywhere, so quoting it would invite a
     * caller to read a transport limit as a property of the pool's session format.
     */
    private static final String MESSAGE_SESSION_TOO_LONG =
            "Session is longer than this service accepts ...";

    /**
     * The sentence reported when no new password was submitted.
     *
     * <p>Assumptions: this states the absence and states nothing about composition, because the pool
     * owns the policy and reports its own reason for a value that fails it. A sentence restating the
     * policy would be a second copy of it and would be the copy that goes stale.
     */
    private static final String MESSAGE_NEW_PASSWORD_REQUIRED = "Please enter new Password ...";

    /**
     * The sentence reported when the proposed password exceeds this transport's bound.
     *
     * <p>Assumptions: no number is named, for the reason {@link SignOnRequest} records against its own
     * credential bound -- the figure is this transport's choice rather than the pool's policy, and
     * quoting it invites a caller to read it as the policy.
     */
    private static final String MESSAGE_NEW_PASSWORD_TOO_LONG =
            "Password is longer than this service accepts ...";

    /**
     * The number of positions the reference declares for a user identifier.
     *
     * <p>Assumptions: eight is {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} line 18,
     * corroborated by {@code USERIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY} line 72, and
     * published as {@code maxLength: 8} on this schema. It is named rather than written into the
     * annotation because this record declares three different bounds and only this one is a reference
     * width; a bare number would leave a reader unable to tell which is which.
     */
    private static final int USER_ID_MAX_LENGTH = 8;

    /**
     * The widest session value this record accepts.
     *
     * <p>Assumptions: 4096 is {@code maxLength: 4096} on {@code session} in the committed contract, and
     * it is the same number {@link SignOnChallenge} bounds the outbound copy of the value at, so the
     * value this service hands out and the value it accepts back cannot differ in what they admit.
     */
    private static final int SESSION_MAX_LENGTH = 4096;

    /**
     * The widest proposed password this record accepts.
     *
     * <p>Assumptions: 256 is {@code maxLength: 256} on {@code newPassword} in the committed contract,
     * the same bound {@link SignOnRequest} carries on the credential being presented. Bounding the
     * password being SET on the same basis as the one being PRESENTED is deliberate: a caller that can
     * set a value this service would later refuse to relay would lock itself out of its own account.
     */
    private static final int NEW_PASSWORD_MAX_LENGTH = 256;

    /**
     * Renders this request with both credential components withheld.
     *
     * <p>Refactoring Rationale: a record's generated rendering names every component verbatim, and two
     * of these three are credential material -- possession of the session plus a password completes an
     * authentication. The obligation that neither reaches a log is stated in this file's type
     * documentation, but the generated rendering would defeat it through paths nobody writes
     * deliberately: a request object interpolated into a log statement, an assertion message, an
     * exception message, or a framework trace of a failed request. Overriding it makes the withholding
     * a property of the type rather than of every place the type is mentioned.
     *
     * <p>Alternatives Considered: rendering a prefix or the length of either value. Rejected on the
     * ground {@link SignOnRequest} records for its own credential: a length is exactly the fact that
     * makes an exhaustive guess cheaper, and any run of characters from a credential narrows a guess at
     * the whole of it. The identifier is rendered in full because it is the only thing distinguishing
     * one attempt from another in a log, and it is not protected data.
     *
     * @return the identifier as submitted, with a constant placeholder in place of each of the two
     *     credential components, in the component order this record declares; never {@code null}
     */
    @Override
    public String toString() {
        return "SignOnChallengeRequest[userId=" + this.userId
                + ", session=<withheld>, newPassword=<withheld>]";
    }
}
