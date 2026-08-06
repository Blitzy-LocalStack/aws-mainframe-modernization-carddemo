package com.carddemo.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Carries the two values the sign-on screen collects, for one exchange with the identity provider.
 *
 * <h2>What this record is</h2>
 *
 * <p>This is the request body of the unauthenticated sign-on operation that
 * {@code com.carddemo.auth.api} exposes in place of the reference sign-on transaction. The published
 * contract at {@code services/auth-service/src/main/resources/openapi/auth-api.yaml} declares that
 * operation reachable without a credential of any other kind, which is what makes this record the one
 * inbound shape a caller can send before it holds anything at all.
 *
 * <p>Two components constitute it and no more. The reference screen collects exactly two values,
 * {@code USERIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY} line 72 and {@code PASSWDI PIC X(8)} at
 * line 78, and every other field on that map is either screen furniture or an outbound message.
 *
 * <h2>Why the components are in this order</h2>
 *
 * <p>Assumptions: the order is the reference screen's own and is observable, so it is a contract rather
 * than a formatting preference. The map declares the identifier at {@code app/cpy-bms/COSGN00.CPY}
 * line 72 ahead of the credential at line 78, and the reference program checks them in that same order
 * in a chain that stops at the first failure: {@code app/cbl/COSGN00C.cbl} line 118 tests the
 * identifier and line 120 reports {@code 'Please enter User ID ...'}, while line 123 tests the
 * credential and line 125 reports {@code 'Please enter Password ...'}. A caller submitting both values
 * empty is therefore told about the identifier and not about the credential, and that first message is
 * a user-visible string the migration carries across unchanged under transformation rule T8. Swapping
 * the two components here would change which message such a caller receives.
 *
 * <p>Assumptions: the ordered chain itself is not implemented by this record. The constraints below are
 * the transport-level guard, which rejects a malformed body per field; the first-error ordering that
 * the reference chain produces belongs to the sign-on service logic that consumes this record. This
 * record's contribution to that ordering is only that it declares the components in the order the
 * reference presents them.
 *
 * <h2>The credential, and what becomes of it</h2>
 *
 * <p>Trade-offs: this is the only record in this package that carries a credential, and it carries one
 * on narrow terms that are worth stating concretely rather than as a posture. The value is never
 * persisted: the owning migration {@code V1__auth.sql}, under this module's
 * {@code src/main/resources/db/migration} directory, declares {@code auth.users} with exactly five
 * columns -- {@code user_id}, {@code first_name}, {@code last_name}, {@code user_type} and
 * {@code cognito_sub} -- and no column of any kind that could hold it, so there is no destination to
 * write it to. It is never logged, which is what the rendering below exists to guarantee. And it is
 * never returned: no response record in this package declares such a component, in deliberate
 * contrast to the reference update-user program, which moves the stored value back onto the screen at
 * {@code app/cbl/COUSR02C.cbl} line 169 into the echo field {@code PASSWDO PIC X(8)} that
 * {@code app/cpy-bms/COUSR02.CPY} line 152 declares for it.
 * The reference behaves that way; the Java has no such path; the divergence is documented.
 *
 * <p>Trade-offs: the accepted compromise is that the component is a plain string at the transport
 * boundary rather than a wrapper type able to clear its own storage. A wrapper would still have to be
 * deserialised from the contract's property by name and would still hold the characters while the
 * exchange ran, so it would move the value rather than remove it, and it would add a seventh type to a
 * package whose charter at {@code com.carddemo.auth.dto} closes the set at six records. What is taken
 * instead is the narrower guarantee above: one inbound hop, no column, no log line, no response.
 *
 * <p>Assumptions: the submitted characters reach the identity provider exactly as they arrived, and
 * that is a divergence from the reference worth recording because the reference does the opposite.
 * {@code app/cbl/COSGN00C.cbl} lines 132 to 136 push both values through {@code FUNCTION UPPER-CASE}
 * before the file read and before the comparison -- the identifier at line 132 and the credential into
 * {@code WS-USER-PWD} at line 136 -- so the reference comparison at line 223 is case-insensitive in
 * both. The provider this migration delegates to is case-sensitive on a credential, so folding a
 * submitted value here would present characters the caller did not type and refuse a credential the
 * provider would have accepted. The reference folds; the Java forwards unchanged; the divergence is
 * documented.
 *
 * <h2>Where the two bounds come from, and why they differ</h2>
 *
 * <p>Assumptions: the identifier's bound of eight is the reference width, corroborated twice.
 * {@code app/cpy-bms/COSGN00.CPY} line 72 declares {@code USERIDI PIC X(8)} on the screen and
 * {@code app/cpy/CSUSR01Y.cpy} line 18 declares {@code SEC-USR-ID PIC X(08)} at byte position 0 of the
 * eighty-byte user record. The published contract agrees, declaring the same property at a maximum
 * length of eight, so screen, record and contract are one number and this constraint is that number.
 *
 * <p>Refactoring Rationale: the credential's bound is deliberately NOT the reference width, and the
 * asymmetry is the single most surprising thing about this record. The reference field is
 * {@code SEC-USR-PWD PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} line 21, byte position 48, and this
 * migration does not carry that field forward at all -- the five-column migration above has no column
 * for it, because the comparison it fed moved to the managed identity provider. Bounding this
 * component at eight would therefore enforce the width of a field that no longer exists, against a
 * provider that cannot accept a value that short: {@code infra/modules/cognito/variables.tf} declares
 * {@code password_minimum_length} with a default of 14 and an input validation refusing anything below
 * 12, so no environment this repository can express accepts fewer than twelve characters. Every
 * credential the provider would accept would be refused here first, and the operation would answer a
 * correct submission with a validation failure before the provider was ever consulted. The bound is
 * 256, which is the value the committed contract declares for that property.
 *
 * <p>Assumptions: no minimum length beyond non-emptiness and no character-class rule is asserted here,
 * even though the provider enforces both. Restating the provider's policy in this record would put it
 * in two places, and this copy is the one that goes stale when an environment tightens the pool, at
 * which point this record would refuse credentials the provider accepts. The upper bound is retained
 * because it is what stops an oversized body being relayed onward, and the presence check is retained
 * because it is the reference program's own at {@code app/cbl/COSGN00C.cbl} line 123.
 *
 * <p>Assumptions: the presence constraint is the analogue of the reference test, not an addition to it.
 * {@code app/cbl/COSGN00C.cbl} lines 118 and 123 each test {@code = SPACES OR LOW-VALUES}, treating a
 * field of blanks and a field of low values alike as absent, and a bean-validation non-blank constraint
 * rejects both an empty string and a string of whitespace. A non-null constraint would not: it admits
 * the string of blanks that the reference explicitly refuses, which is a caller submitting an empty
 * screen and being let through.
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Refactoring Rationale: there is no user-type component, and the reference gives two independent
 * reasons rather than one. The sign-on map declares no user-type field whatsoever -- a search of
 * {@code app/cpy-bms/COSGN00.CPY} for that symbol returns nothing -- so a component for it would have
 * no reference face to derive a width or a position from. And the reference does not obtain the value
 * from the caller in the first place: it reads the record and then moves {@code SEC-USR-TYPE} into the
 * communication area at {@code app/cbl/COSGN00C.cbl} line 227, after the read has succeeded, before
 * branching to the administrative or the ordinary menu at lines 231 to 234 and 236 to 239. Accepting a
 * type on this request would let a caller nominate its own authority, whereas the target reads that
 * authority from a signed claim the caller cannot author.
 *
 * <p>Assumptions: there is no component for the reference screen's environment fields.
 * {@code app/cpy-bms/COSGN00.CPY} declares {@code APPLIDI} at line 60 and {@code SYSIDI} at line 66;
 * both name the transaction monitor's own installation rather than anything a caller supplies or reads,
 * so neither has an analogue on this surface. The transaction name, titles, date, program name and time
 * fields above them are screen furniture on the same terms. That head block is per-map and not a
 * shared prefix: this map declares {@code CURTIMEI PIC X(9)} at line 54, and its outbound counterpart
 * {@code CURTIMEO PIC X(9)} at line 122, where all four of the user-administration maps declare eight
 * positions at their own line 54.
 *
 * <p>Assumptions: there is no message component and no length-clamping path either. The reference
 * message field is {@code ERRMSGI PIC X(78)} in all five maps of this family, at
 * {@code app/cpy-bms/COSGN00.CPY} line 84, {@code app/cpy-bms/COUSR00.CPY} line 372,
 * {@code app/cpy-bms/COUSR01.CPY} line 90, {@code app/cpy-bms/COUSR02.CPY} line 90 and
 * {@code app/cpy-bms/COUSR03.CPY} line 84; the longest message the five programs emit is forty-four
 * characters, at {@code app/cbl/COUSR00C.cbl} line 273, so the declared width is never approached and
 * there is nothing for a truncation path to do. The three sign-on failure messages this operation can
 * report -- {@code 'Wrong Password. Try again ...'} spanning {@code app/cbl/COSGN00C.cbl} lines 242 and
 * 243, {@code 'User not found. Try again ...'} at line 249 and
 * {@code 'Unable to verify the User ...'} at line 254 -- travel in
 * {@code com.carddemo.common.error.ApiError} rather than in any component declared here.
 *
 * <h2>Authoring decisions for this record</h2>
 *
 * <p>Alternatives Considered: a compact constructor normalising both values, which is what the sibling
 * {@code com.carddemo.reporting.dto.StatementRequest} does for its own two components, trimming the
 * padding a declared-width field carries. Rejected here, and specifically because of the credential. A
 * value presented as a credential is compared byte for byte by the provider, so removing a leading or
 * trailing space would present characters the caller did not type and turn a correct submission into a
 * refusal that names no cause. Normalising only the identifier and not the credential was the obvious
 * middle course and is rejected too: it would make the record's treatment of its two components differ
 * with nothing in the reference to derive the difference from, since lines 132 to 136 of
 * {@code app/cbl/COSGN00C.cbl} treat both alike. Both values are consequently stored as submitted, and
 * the guard against a value being merely blank is the presence constraint on each component.
 *
 * <p>Alternatives Considered: generating the accessors and the rendering from an annotation processor.
 * Rejected because a generated member cannot carry the documentation this repository's Explainability
 * rule requires of it, and a generated rendering names every component verbatim -- which on this
 * record means printing the credential, the one outcome the section above exists to prevent. A record
 * with an explicit rendering gives the same brevity with members that can be documented and audited.
 *
 * @param userId the identifier of the user signing on, at most eight characters and not blank; eight
 *     is the width {@code USERIDI PIC X(8)} declares at {@code app/cpy-bms/COSGN00.CPY} line 72 and
 *     {@code SEC-USR-ID PIC X(08)} declares at byte position 0 of the record at
 *     {@code app/cpy/CSUSR01Y.cpy} line 18, and the value is carried as submitted rather than folded
 *     to upper case as {@code app/cbl/COSGN00C.cbl} line 132 folds it
 * @param password the credential presented for this one exchange, not blank and bounded only so that
 *     an oversized body is refused before being relayed; it is forwarded to the identity provider for
 *     evaluation and is never persisted, never written to a log or a diagnostic and never returned on
 *     any response, the reference field {@code SEC-USR-PWD PIC X(08)} at
 *     {@code app/cpy/CSUSR01Y.cpy} line 21 having no column in the owning schema at all
 */
public record SignOnRequest(
        @NotBlank @Size(max = USER_ID_MAX_LENGTH) String userId,
        @NotBlank @Size(max = PASSWORD_MAX_LENGTH) String password) {

    /**
     * The number of positions the reference declares for a user identifier.
     *
     * <p>Assumptions: eight is read from {@code USERIDI PIC X(8)} at {@code app/cpy-bms/COSGN00.CPY}
     * line 72 and corroborated by {@code SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} line
     * 18, whose six field widths sum to the eighty bytes of the whole record and so check themselves.
     * The published contract declares the same maximum for this property, making three independent
     * statements of one number.
     *
     * <p>Alternatives Considered: writing the literal into the constraint annotation directly, which
     * is shorter. Rejected because a bare number inside an annotation carries no provenance, and this
     * record's two bounds differ for reasons that a reader has to be able to trace: eight is a
     * reference width and the other bound is not. A named constant with this comment leaves one place
     * to compare against {@code app/cpy-bms/COSGN00.CPY} line 72.
     */
    private static final int USER_ID_MAX_LENGTH = 8;

    /**
     * The largest credential this transport accepts before refusing the body outright.
     *
     * <p>Assumptions: 256 is the maximum length the committed contract declares for this property in
     * {@code services/auth-service/src/main/resources/openapi/auth-api.yaml}, and this constant exists
     * to agree with it. It is emphatically not the reference width: {@code SEC-USR-PWD PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy} line 21 is a field this migration does not carry forward, and a
     * bound of eight here would refuse every credential the identity provider can accept, whose
     * configured minimum is 14 with an input validation floor of 12 in
     * {@code infra/modules/cognito/variables.tf}.
     *
     * <p>Trade-offs: an upper bound is imposed at all even though the provider's own policy is
     * expressed as a minimum rather than a maximum, so the number is this transport's choice and looks
     * arbitrary. What it buys is that an oversized body is refused at this boundary instead of being
     * relayed to a third party, and 256 sits far enough above the configured minimum that no policy
     * this repository can express reaches it.
     */
    private static final int PASSWORD_MAX_LENGTH = 256;

    /**
     * Renders this request for a log or a diagnostic with the credential withheld entirely.
     *
     * <p>Refactoring Rationale: the rendering a record generates for itself names every component
     * verbatim, and one of this record's two components is a credential. The obligation that the
     * credential never reaches a log is stated in this file's type documentation, but the generated
     * rendering would defeat it through a path nobody has to write deliberately -- a request object
     * interpolated into a log statement, an assertion message, an exception message or a framework's
     * own trace of a failed request. This override closes that path, so withholding the value is a
     * property of the type rather than of every place the type is mentioned.
     *
     * <p>Alternatives Considered: masking the credential the way this migration masks a primary
     * account number, revealing its trailing characters. Rejected because the two values are not alike
     * in what a fragment of them is worth: four trailing digits of a card number let an operator match
     * a log line to a support call while being useless on their own, whereas any run of characters
     * from a credential narrows a guess at the whole of it. Alternatives Considered: rendering the
     * credential's length instead. Rejected on the same ground, since a length is exactly the fact
     * that makes an exhaustive guess cheaper, and it settles nothing an operator needs.
     *
     * <p>Assumptions: the identifier is rendered in full, because it is the only thing that
     * distinguishes one sign-on attempt from another in a log and it is not protected data -- the
     * reference displays it on the screen that collects it, at {@code app/cpy-bms/COSGN00.CPY} line 72,
     * and the target carries it in a request path. A rendering that withheld both components would be
     * unable to tell two attempts apart, which is the one situation this method exists for.
     *
     * <p>Assumptions: nothing parses this string. It is read by a person, so the placeholder standing
     * in for the withheld component is written to be unmistakable rather than to a format.
     *
     * @return the identifier as submitted, paired with a constant placeholder in place of the
     *     credential, in the component order the record declares
     */
    @Override
    public String toString() {
        return "SignOnRequest[userId=" + userId + ", password=<withheld>]";
    }
}
