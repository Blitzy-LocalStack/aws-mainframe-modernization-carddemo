package com.carddemo.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Carries one stored user row back to a caller, in the shape read, create and update all return.
 *
 * <h2>One shape answering three operations</h2>
 *
 * <p>This record is the body of the single-user read and also the body the create and update
 * operations return once they have written, because the thing being described is identical in all
 * three cases: one row of {@code auth.users}, reported back after whatever the operation did to it.
 * The published contract declares one component schema and references it from all three responses.
 *
 * <p>Alternatives Considered: a separate record per operation, rejected because the baseline settles
 * it. {@code app/cbl/COUSR03C.cbl} L165 to L167 echoes a retrieved row back to the delete view in
 * three moves and {@code app/cbl/COUSR02C.cbl} L166 to L172 echoes the same row to the update view in
 * four; the only extra move is L169, which carries the credential this record omits, so once that is
 * gone the two baseline read-back surfaces are compositionally the same. Three near-identical records
 * would restate the same width and domain citations in three files with nothing observable differing,
 * and a width changed in one would then disagree with the other two silently.
 *
 * <p>Assumptions: that argument does not extend to the request side. A request is validated field by
 * field and the order its fields are declared in decides which one reports the first error, and the
 * add and update screens order their fields differently, which is why the published contract declares
 * two distinct request schemas rather than one. A response reports no first error, so it has no such
 * order to preserve.
 *
 * <h2>Where the five components come from</h2>
 *
 * <p>Assumptions: {@code app/cpy/CSUSR01Y.cpy} is the width authority for every component, and its
 * arithmetic is self-checking. It opens {@code 01 SEC-USER-DATA} at L17 and declares six subordinate
 * fields at L18 to L23 whose widths sum to 8 + 20 + 20 + 8 + 1 + 23, exactly the 80 bytes of the whole
 * record, so a component width disagreeing with this copybook is wrong by construction rather than by
 * opinion. {@code app/cpy-bms/COUSR03.CPY} corroborates the four retained widths independently at L60,
 * L66, L72 and L78. A line number alone identifies nothing across the five auth maps, which is why
 * every citation names the file beside the line: L78 is the type field in
 * {@code app/cpy-bms/COUSR03.CPY} and the credential field in {@code app/cpy-bms/COUSR02.CPY}.
 *
 * <p>Assumptions: the component order is the record order of the 80-byte security record -- L18, L19,
 * L20, L22 -- with the credential at L21 omitted and the trailing padding at L23 dropped, and
 * {@code cognitoSub} appended fifth and last. That is also the order of the five columns the owning
 * schema migration declares and the property order the published contract lists, so all three agree
 * and none is reordered to suit the other two.
 *
 * <p>Assumptions: {@code SEC-USR-FILLER PIC X(23)} at L23 is padding carrying the record out to its
 * declared 80 bytes. No program and no symbolic map addresses it, so it holds no value a caller could
 * supply or read, and reproducing it would put a field on the wire that means nothing at either end.
 * Transformation Rule T1 drops {@code FILLER} and records the drop per record; this is that record.
 *
 * <h2>The credential is not carried forward</h2>
 *
 * <p>Refactoring Rationale: the baseline both stores and compares the credential in cleartext --
 * {@code app/cpy/CSUSR01Y.cpy} L21 declares {@code SEC-USR-PWD PIC X(08)} and
 * {@code app/cbl/COSGN00C.cbl} L223 tests {@code IF SEC-USR-PWD = WS-USER-PWD} directly against the
 * stored value. The target encodes a different arrangement: Cognito holds the credential and performs
 * that comparison, while {@code auth.users} keeps only {@code cognito_sub}. This record therefore
 * declares no password component at all, and the divergence is documented.
 *
 * <p>Refactoring Rationale: the omission reaches four further baseline sites, named here so their
 * absence is legible rather than silent. {@code app/cbl/COUSR02C.cbl} L169 writes the stored
 * credential straight back to the terminal, an echo with no target analogue; and three validation arms
 * exist only because the field does -- the empty-value arm at {@code app/cbl/COUSR01C.cbl} L136 to
 * L140 whose message reads {@code 'Password can NOT be empty...'}, the same arm at
 * {@code app/cbl/COUSR02C.cbl} L200, and the arm at L227 to L229 comparing a submitted value against
 * the stored one. A field user administration never accepts cannot be tested for emptiness or
 * compared against a stored copy.
 *
 * <p>Refactoring Rationale: the baseline itself supplies the precedent for a read-back without the
 * credential, which makes this shape evidence rather than assertion.
 * {@code app/cbl/COUSR03C.cbl} L165 to L167 moves the two names and the type and nothing else, and
 * {@code app/cpy-bms/COUSR03.CPY} contains the string {@code PASSWD} zero times, so the delete-view
 * map has no field a credential could be written into.
 *
 * <h2>The one-character type, and the three places its domain is enforced</h2>
 *
 * <p>Assumptions: {@code app/cpy/COCOM01Y.cpy} is the sole authority for the two admissible values,
 * declaring {@code CDEMO-USER-TYPE PIC X(01)} at L26 and naming {@code 'A'} and {@code 'U'} at L27 and
 * L28. {@code app/cpy/CSUSR01Y.cpy} L22 declares the same width and carries no {@code 88}-level at
 * all, so it settles the width and cannot settle the domain, and the screen programs test their type
 * field for nonblank only. Citing L22 for the domain would name a value set that file does not
 * contain.
 *
 * <p>Assumptions: the domain is enforced in three places and the redundancy is deliberate -- here by
 * the pattern constraint a caller reads from the published contract, again in the service layer where
 * the ordered validation chain decides which field reports the first error, and last in the database
 * by {@code CHECK (user_type IN ('A','U'))}, which holds for every write path including one that never
 * passes through this service. Removing any one leaves a route by which a third value reaches storage.
 *
 * <p>Trade-offs: {@code userType} stays a one-character {@code String} rather than becoming a named
 * type over the two values. The declared width is part of what a caller sees --
 * {@code SEC-USR-TYPE PIC X(01)} occupies one position, and the published contract declares a
 * minimum length of one, a maximum length of one and the two-member enumeration -- and a
 * one-character string reproduces all of that without introducing a type name the contract does not
 * carry. What is given up is real and is worth naming: there is no compile-time exhaustiveness
 * check, so a handler that branches on this component gets no complaint from the compiler when it
 * omits one of the two cases. The compromise is accepted because the domain is enforced in the three
 * places just named, so an out-of-domain value is refused before it can reach such a handler rather
 * than reaching it and falling through.
 *
 * <h2>The subject reference is the one net-new component</h2>
 *
 * <p>Assumptions: {@code cognitoSub} corresponds to no field of the security record. It is the fifth
 * and last column the owning schema migration declares for {@code auth.users}, as
 * {@code UUID NOT NULL UNIQUE}, and the published contract declares it a string in the uuid format,
 * which is why the component is a {@code java.util.UUID} rather than a string that happens to look
 * like one: parsing at the boundary rejects a malformed value once, and canonicalises the rest so
 * that two spellings of one subject cannot compare unequal. The value is minted by the identity
 * provider when the pool account is created, and this service reads it back out of the provider's
 * create response -- {@code com.carddemo.auth.service.CognitoUserProvisioningService} performs that
 * creation. So nothing here generates the value and nothing here may choose it, which are two
 * different guarantees: the provider is its sole author, and no request body carries it. It is
 * returned so that an administrator can tell which pool account a row is bound to, which is the only
 * way to distinguish two similarly named users.
 *
 * <p>Refactoring Rationale: this paragraph used to say only that the value "is supplied when the
 * identity-provider account is provisioned", which left the provisioner unnamed and read as though
 * some party outside this service created the account and handed the subject in. That was accurate of
 * an earlier arrangement in which {@code com.carddemo.auth.dto.CreateUserRequest} accepted the
 * subject as a required input. It no longer is: accepting it let a caller decide which pool identity a
 * new row authenticates as, so the property was withdrawn and provisioning moved here. Naming the
 * provisioner matters because the guarantee a reader needs -- that no caller can nominate this value
 * -- follows from where the account is created, not from where the value is stored.
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Assumptions: no message component and no length-clamping path. The baseline message field is
 * {@code ERRMSGI PIC X(78)} in all five auth maps, and the longest text those programs emit is the 44
 * characters of {@code 'You are already at the bottom of the page...'} at
 * {@code app/cbl/COUSR00C.cbl} L273, so the declared width is never approached and a truncating path
 * would have nothing to do. Message text travels in
 * {@code com.carddemo.common.error.ApiError} instead, and a per-component constraint failure surfaces
 * through the per-field array that shape carries.
 *
 * <p>Assumptions: three further baseline surfaces stop at the service boundary. The programs
 * distinguish message tone by writing a colour attribute into the output map, and a terminal attribute
 * is not data, so no component carries a colour, a severity or a tone. Three programs write the
 * transaction monitor's response and reason codes to a display stream while diagnosing a failed file
 * operation, and those codes describe the internals of a data store to whoever reads them, so no
 * component returns one -- the equivalent line in {@code app/cbl/COUSR01C.cbl} at L268 is commented
 * out, which is a per-site difference and not a general rule. And the head block every map opens with
 * is screen chrome, excluded from this surface entirely.
 *
 * <p>Assumptions: no component describes a position in a result set, because this is a single-row
 * projection and the page envelope is declared once in {@code com.carddemo.common.web} over a summary
 * element type. No timestamp component either, the owning schema migration declaring no created,
 * updated or audit column among its five; and no exact-decimal component, this context holding an
 * identifier, two names and a one-character type and no balance, limit or rate of any kind.
 *
 * <h2>Why the bounds are declared rather than enforced by throwing</h2>
 *
 * <p>Alternatives Considered: a compact constructor refusing an over-width value or an out-of-domain
 * type by throwing. Rejected because an instance of this record is built after the row has already
 * been read, inserted or updated, on a path that has by then succeeded: a throw prevents nothing,
 * replaces a legible answer with an unstructured server failure, and reports a failure for an
 * operation that in fact completed. Declaring the bounds keeps every answer deliverable, and a value
 * that breaches one is visible as a constraint violation naming the component.
 *
 * <p>Assumptions: what a declared bound does on a response is narrower than on a request, stated
 * plainly rather than assumed. The framework evaluates a request body's constraints as it binds that
 * body; nothing evaluates a response body's constraints on the way out. These annotations are
 * therefore the published contract and the tested one rather than an outbound gate.
 *
 * <p>Assumptions: this record performs no padding removal, and the obligation is placed rather than
 * dropped. The identifier column is declared with a character type of exactly 8 positions, so a value
 * read from it arrives blank-padded, and stripping that padding is a storage-representation concern
 * assigned to this module's mapping layer -- so a value reaching these components has already had it
 * removed. An inbound request passes through no such layer, which is why the normalising treatment
 * belongs on a request type. A mapper that forgot to trim would surface as a trailing-blank comparison
 * failure in this module's own tests rather than as a silently padded response.
 *
 * <h2>What the test channel has to assert</h2>
 *
 * <p>Assumptions: no executable oracle exists for this bounded context, because all five auth programs
 * are online programs written against the transaction monitor's command-level interface and
 * {@code tests/README.md} records at its lines 83 to 85 that such programs cannot run end to end
 * without a CICS runtime. Every width, order and admissible value here is instead verifiable by
 * reading the cited file at the cited line, which is why the citations are exact.
 *
 * <p>Assumptions: six assertions are required of whatever tests this record -- that it declares exactly
 * these five components in this order, matching the five columns and the five published properties;
 * that a 9-character identifier is refused while an 8-character one is accepted, pinning the bound at
 * the declared width rather than one position either side; that a 21-character name is refused while a
 * 20-character one is accepted; that {@code userType} admits {@code 'A'} and {@code 'U'} and refuses a
 * null, an empty string, a blank, the lower-case forms and the two letters together; that the type
 * exposes no accessor for a credential, the assertion that would fail first if the omission above were
 * undone; and that the string form renders neither name nor the Cognito subject, stated as an
 * assertion about the rendered text rather than about the override's presence, because a rendering that
 * named a component and then printed its value would satisfy the weaker check and still disclose
 * exactly what the stronger one forbids.
 *
 * @param userId the row's identifier and primary key, of at most 8 characters, as
 *     {@code SEC-USR-ID PIC X(08)} declares at {@code app/cpy/CSUSR01Y.cpy} L18, byte position 0 of
 *     the 80-byte security record, and as {@code USRIDINI PIC X(8)} presents it at
 *     {@code app/cpy-bms/COUSR03.CPY} L60. It is required, because a response that omits the
 *     identifier does not say which row it describes. Any blank padding the declared-width column
 *     carries has been removed before a value reaches this component
 * @param firstName the user's given name, of at most 20 characters, as
 *     {@code SEC-USR-FNAME PIC X(20)} declares at {@code app/cpy/CSUSR01Y.cpy} L19, byte position 8
 *     of that record, and as {@code FNAMEI PIC X(20)} presents it at
 *     {@code app/cpy-bms/COUSR03.CPY} L66. It is required, matching the not-null column the owning
 *     schema migration declares; absence is a state the baseline record has no way to express in any
 *     case, because a declared-width character field always holds characters
 * @param lastName the user's family name, of at most 20 characters, as
 *     {@code SEC-USR-LNAME PIC X(20)} declares at {@code app/cpy/CSUSR01Y.cpy} L20, byte position 28
 *     of that record, and as {@code LNAMEI PIC X(20)} presents it at
 *     {@code app/cpy-bms/COUSR03.CPY} L72. It is required on the same ground as the given name, and
 *     it is a second component of the same declared width rather than a longer or shorter one
 * @param userType the stored role of the row being described, one character, as
 *     {@code SEC-USR-TYPE PIC X(01)} declares at {@code app/cpy/CSUSR01Y.cpy} L22, byte position 56
 *     of that record, and as {@code USRTYPEI PIC X(1)} presents it at
 *     {@code app/cpy-bms/COUSR03.CPY} L78. It is {@code 'A'} for administrator or {@code 'U'} for
 *     ordinary user and nothing else, per {@code app/cpy/COCOM01Y.cpy} L26 to L28, which is the sole
 *     authority for that domain. Read it as data about the user administered, never as authority
 *     granted to the caller that asked: a caller's own role is read from a signed claim the caller
 *     cannot author, so a handler that consulted this component to decide what the caller may do
 *     would reintroduce exactly the weakness the signed claim removes
 * @param cognitoSub the Cognito subject this row is bound to, and the one component with no
 *     counterpart in the security record. It is the fifth and last column the owning schema
 *     migration declares for {@code auth.users}, as {@code UUID NOT NULL UNIQUE}, and the published
 *     contract declares it a string in the uuid format, so it is required and is a
 *     {@code java.util.UUID} rather than a string. It is minted by the identity provider when this
 *     service provisions the pool account and is never chosen by a caller; it is returned so that an
 *     administrator can tell which pool account a row represents
 */
public record UserResponse(
        @NotNull @Size(max = USER_ID_WIDTH) String userId,
        @NotNull @Size(max = NAME_WIDTH) String firstName,
        @NotNull @Size(max = NAME_WIDTH) String lastName,
        @NotNull @Pattern(regexp = USER_TYPE_DOMAIN) String userType,
        @NotNull UUID cognitoSub) {

    /**
     * The number of positions the baseline declares for the user identifier.
     *
     * <p>Assumptions: 8 is read from {@code SEC-USR-ID PIC X(08)} at
     * {@code app/cpy/CSUSR01Y.cpy} L18, and {@code USRIDINI PIC X(8)} at
     * {@code app/cpy-bms/COUSR03.CPY} L60 states the same width independently, so the figure has
     * two sources and neither is an inference. The owning schema migration declares the column as a
     * character type of exactly this many positions, and the published contract declares the same
     * maximum length, so all three agree.
     */
    private static final int USER_ID_WIDTH = 8;

    /**
     * The number of positions the baseline declares for each of the two name fields.
     *
     * <p>Assumptions: 20 is read from {@code SEC-USR-FNAME PIC X(20)} at
     * {@code app/cpy/CSUSR01Y.cpy} L19 and from {@code SEC-USR-LNAME PIC X(20)} at L20, which
     * declare the same width, and the delete-view map repeats it for both fields at
     * {@code app/cpy-bms/COUSR03.CPY} L66 and L72. One constant serves both components because the
     * baseline gives them one width, not because the two happen to agree today.
     */
    private static final int NAME_WIDTH = 20;

    /**
     * The expression the stored role has to match in full.
     *
     * <p>Assumptions: the two admitted characters come from {@code app/cpy/COCOM01Y.cpy} L27 and
     * L28, which name them as the quoted literals {@code 'A'} and {@code 'U'} under the
     * {@code CDEMO-USER-TYPE PIC X(01)} declared at L26. They are written as an explicit
     * two-character class rather than as a wider alternation, so the domain this constant describes
     * cannot quietly widen. No anchor is written because none is needed: a pattern constraint is
     * satisfied only when the whole value matches, so a one-character class admits exactly one
     * character and refuses the two letters together without one.
     */
    private static final String USER_TYPE_DOMAIN = "[AU]";

    /**
     * The placeholder that stands in for a component this record refuses to render.
     *
     * <p>Alternatives Considered: rendering a shortened form of each withheld value -- an initial, or
     * the first several characters of the subject -- was evaluated and rejected on the ground the
     * sibling record states for its tokens: a leading fragment is still material a reader can
     * correlate across log lines, and correlating a person across lines is the exposure being closed.
     * A family name is additionally the kind of value whose first characters identify it outright in a
     * small population, so a prefix here would disclose more than a prefix of a token does.
     */
    private static final String REDACTED_PERSONAL = "REDACTED";

    /**
     * Renders this record for diagnostics with every personal component replaced by a placeholder.
     *
     * <p>Refactoring Rationale: the string form a record generates for itself lists every component
     * beside its value, which on this record means a given name, a family name and the identity
     * provider's subject for the person the row describes. Any log line, assertion message or
     * exception detail that stringified an instance would publish those three into a log store, and a
     * response type is stringified in exactly the places that happens by accident rather than by
     * intent. This override keeps the generated form's shape and its component order and substitutes
     * {@code REDACTED_PERSONAL} for the three.
     *
     * @return a single-line description of this record naming every component in contract order, in
     *     which the given name, the family name and the Cognito subject are each represented by a
     *     placeholder and never rendered
     */
    @Override
    public String toString() {
        // Assumptions: the generated form's shape is reproduced deliberately, component order
        //   included, so that a reader who knows what a record prints is not led to think some other
        //   type produced this line. Only the three personal values depart from it.
        return "UserResponse[userId=" + userId
                + ", firstName=" + REDACTED_PERSONAL
                + ", lastName=" + REDACTED_PERSONAL
                + ", userType=" + userType
                + ", cognitoSub=" + REDACTED_PERSONAL
                + "]";
    }
}
