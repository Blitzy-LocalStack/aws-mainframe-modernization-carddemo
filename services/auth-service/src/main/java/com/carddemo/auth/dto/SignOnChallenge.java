package com.carddemo.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Carries the sign-on outcome in which the pool accepted the credential but will not yet issue tokens.
 *
 * <p>This is the {@code CHALLENGE} branch of the two-shape success the published contract declares for
 * {@code POST /api/v1/auth/signon}, and the body a caller answers by calling
 * {@code POST /api/v1/auth/challenge} with the session value it carries. <b>No token of any kind
 * accompanies it.</b>
 *
 * <h2>Why this shape exists at all</h2>
 *
 * <p>Refactoring Rationale: it is required for the migrated system to be usable, and it did not exist.
 * The provisioning module creates every seeded identity with a TEMPORARY password -- see
 * {@code infra/modules/cognito} and its {@code temporary_password_validity_days} input -- and a
 * temporary password always yields {@code NEW_PASSWORD_REQUIRED} on first use. With no shape to carry
 * that outcome the service classified it as an integration failure, so the very first sign-on of every
 * provisioned user answered HTTP 500 and no operation existed through which a permanent password could
 * be set. The edge was already forwarding the answer path: {@code POST /api/v1/auth/challenge} is one
 * of the three deliberately unauthenticated route keys in
 * {@code infra/modules/api-gateway-http/variables.tf}, so the gateway routed an operation the service
 * did not serve.
 *
 * <p>Assumptions: this outcome has NO reference counterpart and none is invented for it. The reference
 * sign-on compares a stored eight-character credential directly at {@code app/cbl/COSGN00C.cbl} lines
 * 211 to 256 and has no notion of a credential that must be changed before use, so no message literal
 * is carried across here and none of that program's three sentences is reused. Reporting
 * {@code 'Wrong Password. Try again ...'} for a challenge would tell an operator to retype a credential
 * that was in fact correct. The divergence is registered as {@code D-PASSWORD-CHALLENGE} in
 * {@code docs/architecture/cobol-to-service-traceability.md}, and it follows from entry {@code D-4}
 * there, which records the plaintext credential field not being carried forward.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>Assumptions: there is no user-type component and no group list, exactly as on the authenticated
 * sibling. The reference program put {@code CDEMO-USER-TYPE} into the communication area the client
 * echoed back, so the value it branched on was one the caller had most recently held; a component
 * shaped like authority on this body would restore that. Nothing here is authority, and this body in
 * particular precedes any token, so a client holding it has been granted nothing at all.
 *
 * <p>Trade-offs: only {@code NEW_PASSWORD_REQUIRED} is admitted, not the full set of challenges a pool
 * can raise. The remainder belong to second-factor configurations that the provisioned pool does not
 * produce, and publishing a shape for a flow that has never been exercised would invite a client to be
 * written against it. An unpublished challenge therefore stays a truthful integration failure -- the
 * service genuinely cannot complete one. The cost is that enabling a second factor requires an edit
 * here and in the committed document; what it buys is that every value this record can carry is one
 * the service can actually act on.
 *
 * @param outcome the discriminating member, always {@link #OUTCOME_CHALLENGE} on this shape
 * @param challengeName what the pool requires before it will issue tokens, always
 *     {@link #CHALLENGE_NEW_PASSWORD_REQUIRED} on this shape
 * @param session the opaque continuation value the pool issued, to be sent back verbatim on the
 *     challenge operation. It is not a bearer token and no other operation accepts it; treat it as a
 *     credential -- do not log it, do not store it beyond the exchange and do not parse it
 * @param userId the identifier the challenge was raised for, echoed so a client can render it while
 *     collecting the new password. It carries no authority
 */
// WHY : Assumptions: the session travels to the caller even though possession of it plus a new password
//       completes the authentication, and the reason is structural rather than a relaxation. The pool
//       requires the value back and this service holds no per-caller state to keep it in; holding it
//       server-side would reintroduce exactly the session storage that ending the pseudo-conversational
//       design removed. It is short-lived, single-use, and bound by the pool to the one exchange it was
//       issued for, and it travels in one direction only: no operation in this contract echoes it back.
public record SignOnChallenge(
        @NotNull @Pattern(regexp = OUTCOME_CHALLENGE) String outcome,
        @NotNull @Pattern(regexp = CHALLENGE_NEW_PASSWORD_REQUIRED) String challengeName,
        @NotNull @Size(max = SESSION_MAX_LENGTH) String session,
        @NotNull @Size(max = USER_ID_WIDTH) String userId) implements SignOnOutcome {

    /**
     * The one value the discriminating member carries on this shape.
     *
     * <p>Assumptions: the contract declares {@code outcome} with {@code const: CHALLENGE}, so this is
     * the only value a producer may set and the only value a consumer sees on this branch. It is
     * public because the producer of this record has to write that exact string, and because a
     * response body's constraints are not evaluated on the way out -- a retyped literal differing in
     * case or spelling would ship, and the client's discriminator would then match neither declared
     * shape. One declaration removes that whole failure.</p>
     *
     * <p>Assumptions: this same declaration is the expression the pattern constraint on
     * {@code outcome} matches, which is sound because the literal is nine upper-case letters and holds
     * no regular-expression metacharacter, and because a pattern constraint is satisfied only by a
     * whole match. Reusing one declaration for both roles makes it impossible for the value a producer
     * sets and the value the constraint admits to drift apart.</p>
     */
    public static final String OUTCOME_CHALLENGE = "CHALLENGE";

    /**
     * The single challenge this contract publishes an answer path for.
     *
     * <p>Assumptions: the value is the provider's own name for the challenge, spelled as the provider
     * spells it, because it is compared against what the provider reports rather than translated. The
     * committed contract declares it as the sole member of an {@code enum} on
     * {@code challengeName}, so a pool configured to raise a different challenge produces an
     * integration failure rather than a body naming a challenge no client can answer.</p>
     */
    public static final String CHALLENGE_NEW_PASSWORD_REQUIRED = "NEW_PASSWORD_REQUIRED";

    /**
     * The widest session value this shape will carry.
     *
     * <p>Assumptions: four thousand and ninety-six is read from {@code maxLength: 4096} on
     * {@code session} in the committed contract. The bound exists because the value is opaque and
     * provider-sized: it has no declared width of its own, so an upper limit is what stops an
     * unbounded value being copied into a response and back out again on the answer.</p>
     */
    private static final int SESSION_MAX_LENGTH = 4096;

    /**
     * The number of character positions the reference declarations allow for the identifier.
     *
     * <p>Assumptions: eight is {@code 05 SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} line
     * 18, corroborated by {@code 02 USERIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY} line 72, and
     * published as {@code maxLength: 8} on this shape's {@code userId}. It is declared rather than
     * written into the annotation for the reason the authenticated sibling records: this package
     * handles several declared widths, and a bare number inside an annotation is the one place a
     * reader cannot tell which of them it is.</p>
     */
    private static final int USER_ID_WIDTH = 8;

    /**
     * Builds the challenge body for the one challenge this contract publishes an answer path for.
     *
     * <p>Alternatives Considered: letting each producer pass the two constant members through the
     * canonical constructor. Rejected because the discriminator and the challenge name are then
     * written at every producer, and a producer that mistyped either would ship a body whose
     * discriminator matches neither published shape -- a response body's own constraints are not
     * evaluated on the way out. This factory takes only the two values that genuinely vary, so the
     * two constants have exactly one source. The canonical constructor remains reachable, and its
     * pattern constraints refuse a wrong value when a validator is applied, which is the belt to this
     * factory's braces rather than a substitute for it.</p>
     *
     * <p>Assumptions: the two varying values are checked HERE rather than by a stricter annotation on
     * the component, and the reason is contract parity. The committed schema declares
     * {@code maxLength} on {@code session} and no {@code minLength}, and the annotations on this
     * record are what springdoc renders into the served document, so a {@code min} on the component
     * would publish a facet the committed document does not carry -- the same class of drift between
     * the document and the Java shape that this checkpoint is correcting elsewhere. A blank session is
     * nonetheless unusable, because the pool refuses the answer exchange without it and a caller
     * holding one has no way forward, so the check belongs on the production path where it can refuse
     * a malformed provider response outright.</p>
     *
     * @param session the opaque continuation value the pool issued; must not be {@code null} or blank
     * @param userId the folded identifier the challenge was raised for; must not be {@code null} or
     *     blank
     * @return the challenge body, carrying the published discriminator and challenge name; never
     *     {@code null}
     * @throws IllegalArgumentException if either value is {@code null} or holds only whitespace,
     *     which for {@code session} means the provider reported a challenge a caller could not answer
     */
    public static SignOnChallenge newPasswordRequired(String session, String userId) {
        if (session == null || session.isBlank()) {
            throw new IllegalArgumentException("session must be present to answer a challenge");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must be present on a challenge");
        }
        return new SignOnChallenge(OUTCOME_CHALLENGE, CHALLENGE_NEW_PASSWORD_REQUIRED, session,
                userId);
    }

    /**
     * Renders this body without its session value.
     *
     * <p>Assumptions: the generated rendering is overridden because a record's default one emits every
     * component, and one of these components is a credential: possession of the session plus a new
     * password completes the authentication. A record cannot decline to have a rendering, so the only
     * way to keep the session out of a log line, an assertion message or a debugger's inline display
     * is to replace it. The two constant members are emitted because they are constants, and the
     * identifier because it is echoed to the caller anyway.</p>
     *
     * @return the outcome, the challenge name and the identifier, and never the session; never
     *     {@code null}
     */
    @Override
    public String toString() {
        return "SignOnChallenge[outcome=" + this.outcome
                + ", challengeName=" + this.challengeName
                + ", userId=" + this.userId
                + ", session=<withheld>]";
    }
}
