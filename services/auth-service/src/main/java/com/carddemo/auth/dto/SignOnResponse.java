package com.carddemo.auth.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Carries the token set a completed sign-on issued, together with the identifier those tokens were
 * issued for.
 *
 * <h2>Which exchange this is, and which operations return it</h2>
 *
 * <p>This is the success body of the unauthenticated credential exchange
 * {@code POST /api/v1/auth/signon}, the target successor to the reference sign-on program
 * {@code app/cbl/COSGN00C.cbl}. The reference program ended a successful sign-on by transferring
 * control to the next screen program and passing the communication area along with it. This record
 * ends it by handing the caller a token set and an identifier, and every consequence of that
 * substitution is recorded below rather than left to be inferred from the component list.
 *
 * <p>Assumptions: three operations in
 * {@code services/auth-service/src/main/resources/openapi/auth-api.yaml} return this one shape, so a
 * component that suited only one of them would be wrong on the other two. Sign-on returns it as one
 * branch of a two-shape success, told apart by {@code outcome}. The renewal exchange
 * {@code POST /api/v1/auth/refresh} returns it directly with a null {@code refreshToken}, because
 * the user pool does not reissue one on renewal. The challenge exchange
 * {@code POST /api/v1/auth/challenge} returns it directly once a permanent credential has been
 * accepted. That {@code refreshToken} is nullable is precisely what lets one shape serve all three,
 * instead of a second and nearly identical shape existing for the renewal path alone.
 *
 * <h2>The component set and its order are read from the committed contract</h2>
 *
 * <p>Assumptions: the {@code SignOnResponse} component schema in
 * {@code services/auth-service/src/main/resources/openapi/auth-api.yaml} is the authority for which
 * components exist here and for the order they appear in, and this record mirrors it property for
 * property. The order is not a matter of taste on a response: the contract is what the
 * springdoc-generated runtime document publishes and what the browser client
 * {@code ui/src/api/auth.ts} is written against, so a component this record declares that the
 * contract does not, or an order that disagrees with it, is a defect in this file rather than a
 * cosmetic difference. The schema also declares {@code additionalProperties: false}, which means an
 * extra component added here would make the published document and the emitted body contradict one
 * another.
 *
 * <p>Assumptions: where that contract and the owning schema migration
 * {@code services/auth-service/src/main/resources/db/migration/V1__auth.sql} could be read
 * differently, the migration decides. It bears only indirectly on this record, because a token set
 * is not stored anywhere: the migration declares {@code auth.users} with exactly five columns, being
 * the identifier at line 39, the two name columns at lines 44 and 45, the one-character type at line
 * 49 and the identity-provider subject reference at line 54. The precedence is stated even so, so
 * that a later reader resolving a disagreement does not have to decide which authority wins.
 *
 * <h2>Where the identifier width comes from</h2>
 *
 * <p>Assumptions: {@code userId} is bounded at eight characters, and two independent reference
 * declarations settle that number. The sign-on map declares {@code 02 USERIDI PIC X(8)} at
 * {@code app/cpy-bms/COSGN00.CPY} line 72, which is the width the screen could accept. The security
 * record declares {@code 05 SEC-USR-ID PIC X(08)} at {@code app/cpy/CSUSR01Y.cpy} line 18, at
 * offset 0 of an eighty-byte record whose six field widths sum to exactly eighty, which is the width
 * the store held. Two sources agreeing is what makes eight a contract rather than a reading, and the
 * migration carries the same number forward as {@code user_id CHAR(8)} at {@code V1__auth.sql}
 * line 39.
 *
 * <p>Assumptions: the reference program folded the submitted identifier to upper case before it read
 * the file, at {@code app/cbl/COSGN00C.cbl} lines 132 to 134, so the identifier this record echoes
 * is the folded form the identity provider recognised rather than the exact characters the caller
 * typed. This record performs no folding of its own. It reports what was issued, and the fold
 * belongs to the exchange that issued it.
 *
 * <h2>No authoritative user type, and no group list either</h2>
 *
 * <p>Refactoring Rationale: the reference sign-on read the caller's type out of the security record
 * and put it into shared session storage. {@code app/cbl/COSGN00C.cbl} line 227 performs
 * {@code MOVE SEC-USR-TYPE TO CDEMO-USER-TYPE}, lifting the one-character type from
 * {@code app/cpy/CSUSR01Y.cpy} line 22 into the communication area field
 * {@code app/cpy/COCOM01Y.cpy} declares as {@code 10 CDEMO-USER-TYPE PIC X(01)} at line 26, whose
 * two admissible values are named at lines 27 and 28. The program then branched on that field at
 * line 230 and transferred control accordingly: to the administrator menu by
 * {@code EXEC CICS XCTL} at lines 231 to 234, whose program literal {@code 'COADM01C'} sits on line
 * 232, or to the general menu by the {@code EXEC CICS XCTL} at lines 236 to 239, whose literal
 * {@code 'COMEN01C'} sits on line 237. What was wrong with that arrangement is specific and is not a
 * matter of degree. The communication area is storage the terminal hands back on the following turn,
 * so the value the program branched on was a value the caller had most recently been holding, and a
 * caller could therefore present a type it had never been issued. This record returns no equivalent
 * component, because returning one would restore exactly that: nothing in a response body can stop a
 * client sending a value back, and a handler that read such a value to decide what the caller may do
 * would be trusting the caller's own assertion again.
 *
 * <p>Refactoring Rationale: authority in the target is carried instead by the signed
 * {@code cognito:groups} claim, which {@code com.carddemo.common.security.JwtRoleConverter} converts
 * into granted authorities, mapping the reference administrator value onto {@code carddemo-admin} and
 * the reference user value onto {@code carddemo-user}. The difference that matters is that a claim
 * inside a signed token cannot be altered by the client without invalidating the signature, whereas
 * echoed session storage could be rewritten by whoever held it. That converter is named here and
 * deliberately not imported: converting a claim is an authorisation concern and this record is a
 * payload, so importing it would put the decision in the wrong place.
 *
 * <p>Refactoring Rationale: the landing-screen choice the reference program made on the server at
 * lines 231 to 239 is now made by the client, which reads the group claim from the identity token it
 * has just received and routes itself to the administrator or the general menu. No navigation target
 * appears on this response for that reason. The reference behaviour is preserved and the mechanism
 * that produced it is not; the divergence is documented rather than hidden.
 *
 * <p>Alternatives Considered: returning the group list as a display-only convenience, so that the
 * client could pick its landing screen without reading the token. The committed contract records the
 * same rejection at the schema itself, and the reason is that a component shaped like authority
 * invites use as authority. A later reader would have no way to tell such a convenience component
 * apart from the one just removed, and the client already holds the signed token and reads the claim
 * out of it, which is one source of truth rather than two that could disagree. No group component is
 * declared here, and none may be added without changing the contract first.
 *
 * <p>Assumptions: the two admissible values of that one-character type are {@code 'A'} for
 * administrator and {@code 'U'} for user, and {@code app/cpy/COCOM01Y.cpy} is their sole authority,
 * declaring them as quoted condition literals at lines 27 and 28. This is recorded here even though
 * no such component exists on this record, so that nobody looks for the domain in the wrong place.
 * {@code app/cpy/CSUSR01Y.cpy} line 22 declares the same one-character width and names no values at
 * all, so it settles the width and cannot settle the domain. The domain is enforced in three places,
 * none of them this record: on the request and response types that do carry a type component, in the
 * service logic that administers users, and in the schema itself as
 * {@code CHECK (user_type IN ('A','U'))} at {@code V1__auth.sql} line 49.
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>An absence in a payload is invisible, so each one is written down. An author who cannot see why
 * something is missing supplies it.
 *
 * <p>Alternatives Considered: returning the caller's profile alongside the tokens, being the two
 * twenty-character name fields at {@code app/cpy/CSUSR01Y.cpy} lines 19 and 20 and the
 * identity-provider subject reference. Rejected for two reasons that are independent of one another.
 * It would restate on a second record the widths and citations that {@code UserResponse} already
 * carries for {@code GET /api/v1/users/{userId}}, so a later width correction would have two places
 * to land and could be applied to one of them only. And it would widen an unauthenticated response,
 * which is the one response in this package a caller reaches before presenting any credential at
 * all. There is also no reference face to reproduce: the sign-on map
 * {@code app/cpy-bms/COSGN00.CPY} declares no name field and no type field anywhere in its hundred
 * and fifty-two lines, in either its input group at line 17 or the output group that redefines it at
 * line 85.
 *
 * <p>Assumptions: no message component, and no width-clamping path either. The reference message
 * field is {@code ERRMSGI PIC X(78)} in all five of this package's maps, at
 * {@code app/cpy-bms/COSGN00.CPY} line 84, {@code app/cpy-bms/COUSR00.CPY} line 372,
 * {@code app/cpy-bms/COUSR01.CPY} line 90, {@code app/cpy-bms/COUSR02.CPY} line 90 and
 * {@code app/cpy-bms/COUSR03.CPY} line 84. Seventy-eight, not seventy-five: the seventy-five
 * character figure belongs to the house message contract in {@code app/cpy/CVCRD01Y.cpy}, a copybook
 * none of these five programs includes. The longest message the five emit is forty-four characters,
 * at {@code app/cbl/COUSR00C.cbl} line 273, so the declared width is never approached and there is
 * nothing for a truncation path to do. The structural reason is simpler still: this record is the
 * success shape, so the sign-on failure sentences are not its business. The two this migration can
 * report are {@code 'Wrong Password. Try again ...'} at {@code app/cbl/COSGN00C.cbl} lines 242 to 243
 * and {@code 'Unable to verify the User ...'} at line 254, and each travels in
 * {@code com.carddemo.common.error.ApiError} instead. Both carry a space before the ellipsis exactly
 * as the program writes it, and neither is reworded in transit.
 *
 * <p>Assumptions: the reference's third sentence, {@code 'User not found. Try again ...'} at line 249,
 * is named separately because it travels nowhere. The identity provider answers an unknown identifier
 * and a wrong credential identically, so the first sentence above covers both cases and the third is
 * unreachable through any published operation -- a registered divergence,
 * {@code D-SIGNON-EXISTENCE-UNIFORM} in section 7.4 of
 * {@code docs/architecture/cobol-to-service-traceability.md}. It remains in the browser application's
 * message catalogue for traceability alone. Counting it among the sentences that travel would have
 * asserted a response no caller can receive.</p>
 *
 * <p>Trade-offs: no credential travels on this response in any form, and the compromise that
 * accepting a token set instead entails is worth naming. The reference system exposed the stored
 * credential on an outbound path: {@code app/cbl/COUSR02C.cbl} line 169 performs
 * {@code MOVE SEC-USR-PWD TO PASSWDI OF COUSR2AI}, writing the eight-character stored value straight
 * onto the update screen, whose echo face {@code app/cpy-bms/COUSR02.CPY} declares as
 * {@code 02 PASSWDO PIC X(8)} at line 152. Nothing equivalent happens here. A credential presented at
 * sign-on lives only on the inbound request for the duration of that one exchange, the token exchange
 * carries none of it onward, and {@code V1__auth.sql} declares no credential column and no derived
 * form of one. What is given up is the reference system's ability to show an administrator an
 * existing credential, which cannot be reproduced once the comparison moves to a managed identity
 * provider that stores no recoverable form of it; recovery becomes a provider-side reset. The
 * divergence is documented, and the three records that carry a type or administer a user are where it
 * is recorded in full rather than here.
 *
 * <p>Assumptions: no environment identifiers and no other screen furniture. The sign-on map declares
 * {@code 02 APPLIDI PIC X(8)} at line 60 and {@code 02 SYSIDI PIC X(8)} at line 66, which name the
 * transaction monitor's own region and system to the terminal and have no target counterpart at all.
 * The heading block above them is screen furniture in the same sense. It is per-map furniture rather
 * than a byte-identical shared prefix, and the difference is easy to record wrongly: this map
 * declares {@code 02 CURTIMEI PIC X(9)} at line 54 and {@code 02 CURTIMEO PIC X(9)} at line 122,
 * whereas all four {@code COUSR} maps declare eight positions at their own line 54. Nine against
 * eight is the reason the block is described as furniture excluded per map and never as one shared
 * prefix.
 *
 * <p>Assumptions: no monitor response or reason code either. The reference program triages its file
 * read on literal codes rather than symbolic ones, at {@code app/cbl/COSGN00C.cbl} line 222 for the
 * normal case, line 247 for a missing record and line 252 for everything else. Those codes describe
 * the internals of a data store to whoever reads them, so they stop at the service boundary and
 * contribute no component here.
 *
 * <p>Assumptions: no paging member, no error member, no timestamp member and no monetary member. The
 * package charter in {@code package-info.java} states each of those absences once for the whole
 * package and names the type that owns the shape instead, and this record restates none of them.
 * Paging belongs to {@code com.carddemo.common.web.PageResponse}, whose element type here is
 * {@code UserSummary} and not this record; the problem shape belongs to
 * {@code com.carddemo.common.error.ApiError}; and the owning table's five columns include no audit
 * column for a timestamp member to mirror.
 *
 * <h2>Why the bounds are declared rather than enforced by throwing</h2>
 *
 * <p>Alternatives Considered: a compact constructor refusing an out-of-domain outcome, an over-width
 * identifier or a lifetime below one by throwing, which would make every bound hold for every caller
 * including one inside this service. Rejected, and the reason is specific to this type reporting a
 * completed exchange rather than requesting one. An instance is built only after the user pool has
 * accepted the credential and minted the tokens, so a throw at that point prevents nothing: it
 * replaces a usable token set with an unstructured server failure and sends the caller back to
 * re-enter a credential that was in fact accepted, while the minted tokens stay valid with nobody
 * holding them. Declaring the bounds instead keeps every issued token set deliverable, and a value
 * breaching one is then visible as a violation naming the component rather than as a sign-on that
 * appears to have failed.
 *
 * <p>Assumptions: what a declared bound does on a response is narrower than what it does on a
 * request, and it is stated plainly rather than left to be assumed. The framework evaluates a request
 * body's constraints as it binds it; nothing evaluates a response body's constraints on the way out.
 * These annotations are therefore the published contract and the tested one rather than an outbound
 * gate: the document this module publishes renders them as its required list, its maximum length, its
 * const and its minimum, so a client reads every bound without reading this file, and the test
 * channel evaluates the same annotations directly.
 *
 * <p>Trade-offs: {@code expiresIn} is a primitive rather than a boxed integer, so it cannot be null
 * and there is no third state between a reported lifetime and an absent one. That mirrors
 * {@code format: int32} exactly and expresses the contract's required list in the type itself rather
 * than in an annotation. The compromise is worth naming: a body omitting the member would bind to
 * zero rather than to null, so an absence would arrive looking like a value. The minimum bound is
 * what keeps that visible, because zero falls below one and is reported as a violation naming the
 * component, whereas a boxed integer would instead have admitted null for a member the contract
 * declares required.
 *
 * <p>Alternatives Considered: generating the accessors and the string form with Lombok rather than
 * declaring them. Rejected on two independent grounds. Its generated members carry no documentation,
 * so a generated accessor cannot satisfy the docstring obligation this repository enforces at build
 * time and would have to be suppressed out of that gate. And its generated string form prints every
 * component, which on this record means the three token values -- the exact disclosure the override
 * below exists to prevent.
 *

 * @param outcome which of the two sign-on outcomes this body carries, always the value
 *     {@link #OUTCOME_AUTHENTICATED} on this shape. It is the discriminating member the contract
 *     names, so a client branches on a value it can read rather than on the absence of a member it
 *     has to infer, and it is required for that reason
 * @param userId the identifier the tokens were issued for, of at most eight characters as
 *     {@code 02 USERIDI PIC X(8)} declares at {@code app/cpy-bms/COSGN00.CPY} line 72 and
 *     {@code 05 SEC-USR-ID PIC X(08)} declares at {@code app/cpy/CSUSR01Y.cpy} line 18, at offset 0
 *     of that eighty-byte record. It is echoed so the client can render it in the screen title band
 *     without decoding a token, and it carries no authority of any kind
 * @param accessToken the access token the configured user pool minted, to be presented as the HTTP
 *     bearer credential on every other operation in this contract. It is the only one of the three
 *     tokens this API accepts for authorisation, and it is never written to a log by this type
 * @param idToken the identity token the configured user pool minted, describing the authenticated
 *     user to the client. It is returned because the client reads its group claim to choose the
 *     administrator or the general landing screen, which is the client-side replacement for the
 *     server-side branch the reference program took at {@code app/cbl/COSGN00C.cbl} lines 231 to
 *     239, and it is accepted for authorisation by no operation here
 * @param refreshToken the refresh token the configured user pool minted where the app client is
 *     configured to issue one, or {@code null} where it is not and {@code null} on a renewal, because
 *     the pool does not reissue one there. It is not a bearer credential and no operation accepts it
 *     for authorisation; the renewal operation takes it in a request body, which is the one direction
 *     it travels
 * @param tokenType the credential scheme the tokens are to be presented under, as the pool reports
 *     it, which is the bearer scheme in every currently supported configuration. It is reported
 *     rather than assumed so that a client reads the scheme from the response instead of holding a
 *     second copy of it
 * @param expiresIn the lifetime of {@code accessToken} in whole seconds from issue, as the pool
 *     reports it, and never below one. The client renews or signs on again before it elapses; this
 *     service refuses an expired token rather than extending one
 */
public record SignOnResponse(
        @NotNull @Pattern(regexp = OUTCOME_AUTHENTICATED) String outcome,
        @NotNull @Size(max = USER_ID_WIDTH) String userId,
        @NotNull String accessToken,
        @NotNull String idToken,
        String refreshToken,
        @NotNull String tokenType,
        @Min(MINIMUM_LIFETIME_SECONDS) int expiresIn) {

    /**
     * The one value the discriminating member carries on this shape.
     *
     * <p>Assumptions: the contract declares {@code outcome} with {@code const: AUTHENTICATED}, so
     * this is the only value a producer may set and the only value a consumer sees on this branch of
     * the two-shape success. It is public because a producer of this record has to write that exact
     * string, and a producer that retyped the literal could differ from it in case or in spelling
     * without the build noticing: a response body's constraints are not evaluated on the way out, so
     * a drifted value would ship and the client's discriminator would then match neither shape. One
     * declaration is the whole of the remedy.
     *
     * <p>Alternatives Considered: a static factory that filled this member itself, so that a caller
     * could not supply the wrong value at all. Rejected because it would add a construction path the
     * contract does not describe, which all three returning operations would have to be routed
     * through, while the canonical constructor stayed reachable beside it and equally wrong to call.
     * Naming the value once and letting every producer reference it removes the same failure without
     * a second way to build the record existing.
     *
     * <p>Assumptions: this same declaration serves as the expression the pattern constraint on
     * {@code outcome} matches, which is sound only because the literal holds no regular-expression
     * metacharacter, being thirteen upper-case letters and nothing else. A pattern constraint is
     * satisfied only by a whole match, so the expression admits this value and refuses every other
     * one without an anchor being written. Reusing one declaration for both roles is what makes it
     * impossible for the value a producer sets and the value the constraint admits to drift apart.
     */
    public static final String OUTCOME_AUTHENTICATED = "AUTHENTICATED";

    /**
     * The number of character positions the reference declarations allow for the identifier.
     *
     * <p>Assumptions: eight is read from {@code 02 USERIDI PIC X(8)} at
     * {@code app/cpy-bms/COSGN00.CPY} line 72 and corroborated by {@code 05 SEC-USR-ID PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy} line 18. The second source carries an arithmetic check with it:
     * the six field widths of that eighty-byte security record are eight, twenty, twenty, eight, one
     * and twenty-three, summing to exactly eighty, so a width disagreeing with the copybook is wrong
     * by construction rather than by opinion. The committed contract publishes the same number as
     * {@code maxLength: 8} and the owning migration carries it as {@code CHAR(8)}.
     *
     * <p>Alternatives Considered: writing the number straight into the constraint. Rejected because
     * this package handles several declared widths -- eight for the identifier, twenty for each of
     * the two names, one for the type and seventy-eight for a screen message -- and a bare number
     * inside an annotation is the one place a reader cannot tell which of them it is or where it came
     * from. A named declaration leaves exactly one line to check against the two copybook lines it
     * cites.
     */
    private static final int USER_ID_WIDTH = 8;

    /**
     * The smallest access-token lifetime the contract admits, in whole seconds.
     *
     * <p>Assumptions: one is read from {@code minimum: 1} on {@code expiresIn} in the committed
     * contract, and it has no reference counterpart at all, because the reference system had no token
     * to expire -- a sign-on there lasted as long as the terminal session did. The bound exists to
     * refuse a nonsensical report rather than to express a policy: the real lifetime is configured on
     * the user pool, and this service deliberately holds no second copy of that configuration.
     */
    private static final int MINIMUM_LIFETIME_SECONDS = 1;

    /**
     * The placeholder standing in for a token value in this record's string form.
     *
     * <p>Assumptions: the string form of this record is reached by log statements, assertion failures
     * and debugger views, none of which is a place a bearer credential may appear. The placeholder is
     * deliberately not a truncation or a masked prefix of the value it replaces: a leading fragment of
     * a token is still material a reader can correlate across log lines, whereas a constant discloses
     * that the component was populated and nothing beyond that.
     */
    private static final String REDACTED_TOKEN = "REDACTED";

    /**
     * Renders this record for diagnostics with every token value replaced by a placeholder.
     *
     * <p>Refactoring Rationale: the string form a record generates for itself lists every component
     * beside its value, which on this record means the access token, the identity token and the
     * refresh token in full. Any log line, assertion message or exception detail that stringified an
     * instance would publish three live credentials into a log store, and a credential in a log
     * outlives the exchange that issued it. This override keeps the generated form's shape and its
     * component order and substitutes {@code REDACTED_TOKEN} for the three token values.
     *
     * <p>Trade-offs: the three token values become unreadable in a diagnostic, which is the purpose
     * rather than a cost. What is genuinely given up is the ability to tell two instances apart from
     * their printed form alone, and it is bought back where it is useful: {@code outcome},
     * {@code userId}, {@code tokenType} and {@code expiresIn} print in full because none of them is
     * a credential, and {@code refreshToken} prints as either {@code null} or the placeholder, so a
     * reader can still see whether the pool issued one -- the fact that distinguishes a sign-on
     * response from a renewal response -- without seeing its value.
     *
     * <p>Assumptions: only the string form is narrowed. Component equality is untouched, and
     * serialisation into a response body reads the component accessors rather than this method, so
     * the published contract is unaffected and a caller still receives every token value the contract
     * promises.
     *
     * @return a single-line description of this record naming every component in contract order, in
     *     which the access token, the identity token and any refresh token are represented by a
     *     placeholder and never rendered
     */
    @Override
    public String toString() {
        // Assumptions: the generated form's shape is reproduced deliberately, component order
        //   included, so that a reader who knows what a record prints is not led to think some other
        //   type produced this line. Only the three token values depart from it.
        return "SignOnResponse[outcome=" + outcome
                + ", userId=" + userId
                + ", accessToken=" + REDACTED_TOKEN
                + ", idToken=" + REDACTED_TOKEN
                + ", refreshToken=" + (refreshToken == null ? "null" : REDACTED_TOKEN)
                + ", tokenType=" + tokenType
                + ", expiresIn=" + expiresIn
                + "]";
    }
}
