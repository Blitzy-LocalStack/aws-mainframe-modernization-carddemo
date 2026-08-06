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
 * <p>This record is the body of the single-user read, and it is also the body the create and the
 * update operations return once they have written. The three answers are identical because the
 * thing being described is identical in all three cases: one row of {@code auth.users}, reported
 * back after whatever the operation did to it. The published contract for this service says the
 * same, declaring one component schema and referencing it from all three responses.
 *
 * <p>Alternatives Considered: a separate record per operation was evaluated and rejected, and the
 * baseline is what settles it. {@code app/cbl/COUSR03C.cbl} L165 to L167 echoes a retrieved row
 * back to the delete view in three moves, and {@code app/cbl/COUSR02C.cbl} L166 to L172 echoes the
 * same row back to the update view in four. The only move the second block has that the first does
 * not is L169, which carries the credential this record omits for the reason given below, so once
 * that one move is gone the two baseline read-back surfaces are compositionally the same and one
 * target shape reproduces both rather than approximating either. Three near-identical records would
 * instead restate the same width citations and the same domain citation in three files with nothing
 * observable differing between them, and a width changed in one of the three would then disagree
 * with the other two silently.
 *
 * <p>Assumptions: that argument does not extend to the two request records, and the contrast is
 * stated here so this paragraph is not read as licence to unify those as well. A request is
 * validated field by field, and the order its fields are declared in decides which one reports the
 * first error; the add screen and the update screen order their fields differently, so
 * {@code CreateUserRequest} and {@code UpdateUserRequest} stay separate types. A response reports no
 * first error, so it has no such order to preserve.
 *
 * <h2>Where the five components come from</h2>
 *
 * <p>Assumptions: {@code app/cpy/CSUSR01Y.cpy} is the width authority for every component here, and
 * its arithmetic is self-checking. It opens {@code 01 SEC-USER-DATA} at L17 and declares six
 * subordinate fields at L18 to L23: {@code SEC-USR-ID PIC X(08)} at byte position 0,
 * {@code SEC-USR-FNAME PIC X(20)} at 8, {@code SEC-USR-LNAME PIC X(20)} at 28,
 * {@code SEC-USR-PWD PIC X(08)} at 48, {@code SEC-USR-TYPE PIC X(01)} at 56 and
 * {@code SEC-USR-FILLER PIC X(23)} at 57. Those widths sum to 8 + 20 + 20 + 8 + 1 + 23, which is
 * exactly the 80 bytes of the whole record, so a component width that disagrees with this copybook
 * is wrong by construction rather than by opinion. The identifier bound of 8 in particular is read
 * from L18, and {@code app/cpy-bms/COUSR03.CPY} L60 corroborates it independently by presenting the
 * same field to a terminal as {@code USRIDINI PIC X(8)}.
 *
 * <p>Assumptions: the delete-view map corroborates the other three retained widths at the same
 * remove: {@code FNAMEI PIC X(20)} at {@code app/cpy-bms/COUSR03.CPY} L66,
 * {@code LNAMEI PIC X(20)} at L72 and {@code USRTYPEI PIC X(1)} at L78. A line number alone
 * identifies nothing across the five auth maps, which is why every citation here names the file
 * beside the line. L78 is the clearest case: it is {@code USRTYPEI} in
 * {@code app/cpy-bms/COUSR03.CPY} and the credential field in {@code app/cpy-bms/COUSR02.CPY}, so
 * the component this record keeps and the one it omits sit on the same line number in two different
 * files.
 *
 * <p>The component order is not a style choice. It is the record order of the 80-byte security
 * record -- L18, then L19, then L20, then L22 -- with the credential at L21 omitted and the trailing
 * padding at L23 dropped, and {@code cognitoSub} appended fifth and last. That is also the order of
 * the five columns the owning schema migration declares for {@code auth.users}, and it is the
 * property order the published contract lists, so all three agree and none of them is reordered to
 * suit the other two.
 *
 * <p>Assumptions: {@code SEC-USR-FILLER PIC X(23)} at {@code app/cpy/CSUSR01Y.cpy} L23, byte
 * position 57, is padding that carries the record out to its declared length of 80 bytes. No program
 * and no symbolic map addresses it, so it holds no value a caller could supply or read, and
 * reproducing it as a component would put a field on the wire that means nothing at either end.
 * Transformation Rule T1 drops {@code FILLER} and records the drop per record; this paragraph is
 * that record for this one.
 *
 * <h2>The credential is not carried forward</h2>
 *
 * <p>Refactoring Rationale: the baseline both stores and compares the credential in cleartext.
 * {@code app/cpy/CSUSR01Y.cpy} L21 declares {@code SEC-USR-PWD PIC X(08)} at byte position 48 of the
 * security record, and {@code app/cbl/COSGN00C.cbl} L223 tests {@code IF SEC-USR-PWD = WS-USER-PWD}
 * directly against the value read out of that record. The target encodes a different arrangement:
 * Cognito holds the credential and performs that comparison, while {@code auth.users} keeps only
 * {@code cognito_sub}, the subject reference the migration adds as the table's fifth column. This
 * record therefore declares no password component at all. The baseline does what L21 and L223
 * describe, the Java encodes the arrangement just named, and the divergence is documented.
 *
 * <p>Refactoring Rationale: the omission reaches four further baseline sites, and each is named here
 * so that its absence is legible rather than silent. {@code app/cbl/COUSR02C.cbl} L169 moves
 * {@code SEC-USR-PWD} into {@code PASSWDI OF COUSR2AI} and so writes the stored credential straight
 * back to the terminal on the update screen; with no component to carry it, that echo has no target
 * analogue. Nor do three validation arms that exist only because the field does: the empty-value arm
 * at {@code app/cbl/COUSR01C.cbl} L136 to L140, whose message at L138 reads
 * {@code 'Password can NOT be empty...'}, the same arm in the update chain at
 * {@code app/cbl/COUSR02C.cbl} L200, and the arm at {@code app/cbl/COUSR02C.cbl} L227 to L229 that
 * compares a submitted value against the stored one and marks the row modified when the two differ.
 * A field user administration never accepts cannot be tested for emptiness or compared against a
 * stored copy, so all three are absent here. Recording them is what Rule 1 asks for at its lines 38
 * to 41, which forbid leaving a non-obvious choice undocumented when a reasonable alternative
 * exists, and carrying the field forward unchanged was that alternative.
 *
 * <p>Refactoring Rationale: the baseline itself supplies the precedent for a user-facing read-back
 * without the credential, which is what makes this shape evidence rather than assertion.
 * {@code app/cbl/COUSR03C.cbl} L165 to L167 is a three-move echo -- {@code SEC-USR-FNAME} to
 * {@code FNAMEI}, {@code SEC-USR-LNAME} to {@code LNAMEI}, {@code SEC-USR-TYPE} to
 * {@code USRTYPEI} -- and it moves nothing else, while {@code app/cpy-bms/COUSR03.CPY} contains the
 * string {@code PASSWD} zero times, so the delete-view map has no field a credential could be
 * written into. The baseline's own read-back surface is consequently composed of exactly the
 * identifier, the two names and the type, which is this record minus {@code cognitoSub}.
 *
 * <h2>The one-character type, and the three places its domain is enforced</h2>
 *
 * <p>Assumptions: {@code app/cpy/COCOM01Y.cpy} is the sole authority for the two admissible values.
 * Its L26 declares {@code CDEMO-USER-TYPE PIC X(01)}, and its L27 and L28 name the two condition
 * values as the quoted literals {@code 'A'} for administrator and {@code 'U'} for ordinary user.
 * {@code app/cpy/CSUSR01Y.cpy} L22 declares the same one-character width and carries no
 * {@code 88}-level at all, so it settles the width and cannot settle the domain; the screen programs
 * test their type field for nonblank only, so they cannot settle it either. Citing L22 for the
 * domain would name a value set that file does not contain.
 *
 * <p>Assumptions: the domain is enforced in three places, and the redundancy is deliberate. It is
 * enforced here, by the pattern constraint on {@code userType}, which is the form a caller reads
 * from the published contract. It is enforced again in {@code com.carddemo.auth.service}, where the
 * ordered validation chain decides which field reports the first error. And it is enforced last in
 * the database, by {@code CHECK (user_type IN ('A','U'))} on {@code auth.users}, which holds for
 * every write path including one that never passes through this service. Removing any one of the
 * three leaves a route by which a third value reaches storage.
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
 * that two spellings of one subject cannot compare unequal. The value is supplied when the
 * identity-provider account is provisioned. This service does not create credentials and does not
 * mint this value, so nothing here generates one. It is returned so that an administrator can tell
 * which pool account a row is bound to, which is the only way to distinguish two similarly named
 * users.
 *
 * <h2>What this record deliberately does not carry</h2>
 *
 * <p>Assumptions: no message component, and no length-clamping path either. The baseline message
 * field is {@code ERRMSGI PIC X(78)} in all five auth maps -- {@code app/cpy-bms/COSGN00.CPY} L84,
 * {@code app/cpy-bms/COUSR00.CPY} L372, {@code app/cpy-bms/COUSR01.CPY} L90,
 * {@code app/cpy-bms/COUSR02.CPY} L90 and {@code app/cpy-bms/COUSR03.CPY} L84 -- and 78 is the width
 * in every one of them. The longest text those five programs emit is the 44 characters of
 * {@code 'You are already at the bottom of the page...'} at {@code app/cbl/COUSR00C.cbl} L273, so
 * the declared width is never approached and a truncating path would have nothing to do. Message
 * text travels in {@code com.carddemo.common.error.ApiError} instead, and a per-component constraint
 * failure surfaces through the per-field array that shape carries, so neither belongs in a component
 * here.
 *
 * <p>Assumptions: three further baseline surfaces stop at the service boundary. The programs
 * distinguish message tone by writing a colour attribute into the output map -- {@code DFHGREEN} at
 * {@code app/cbl/COUSR01C.cbl} L254, {@code DFHRED} at {@code app/cbl/COUSR02C.cbl} L241 and
 * {@code DFHNEUTR} at {@code app/cbl/COUSR03C.cbl} L285 -- and a terminal attribute is not data, so
 * no component here carries a colour, a severity or a tone. Three programs write the transaction
 * monitor's response and reason codes to a display stream while diagnosing a failed file operation,
 * at {@code app/cbl/COUSR00C.cbl} L608, L642 and L676, at {@code app/cbl/COUSR02C.cbl} L347 and
 * L384, and at {@code app/cbl/COUSR03C.cbl} L294 and L330; those codes describe the internals of a
 * data store to whoever reads them, so no component here returns one. The equivalent line in
 * {@code app/cbl/COUSR01C.cbl} at L268 is commented out, which is a per-site difference and not a
 * general rule, so it is cited as itself rather than generalised. And the head block every map opens
 * with -- transaction name, two title lines, date and time -- is screen chrome, declared per map
 * rather than shared, and is excluded from this surface entirely.
 *
 * <p>Assumptions: no component describes a position in a result set. This record is a single-row
 * projection, and the page envelope this migration uses is declared once in
 * {@code com.carddemo.common.web} and referenced rather than restated; {@code UserSummary}, not this
 * record, is the element type that envelope carries. No timestamp component either: the owning
 * schema migration declares no created, updated or audit column among its five, so there is nothing
 * for one to report. And no exact-decimal component, because this bounded context holds an
 * identifier, two names and a one-character type, and no balance, limit or rate of any kind.
 *
 * <h2>Why the bounds are declared rather than enforced by throwing</h2>
 *
 * <p>Alternatives Considered: a compact constructor that refuses an over-width value or an
 * out-of-domain type by throwing was evaluated and rejected, and the reason is specific to this
 * being an answer rather than a request. An instance of this record is built after the row has
 * already been read, inserted or updated, on a path that has by then succeeded. A throw at that
 * point prevents nothing; it replaces a legible answer with an unstructured server failure and
 * reports a failure for an operation that in fact completed, which inverts the one fact the caller
 * asked about. Declaring the bounds instead keeps every answer deliverable, and a value that
 * breaches one is then visible as a constraint violation naming the component.
 *
 * <p>Assumptions: what a declared bound does on a response is narrower than what it does on a
 * request, and it is stated plainly here rather than left to be assumed. The framework evaluates a
 * request body's constraints as it binds that body; nothing evaluates a response body's constraints
 * on the way out. These annotations are therefore the published contract and the tested one rather
 * than an outbound gate: the module's contract document renders each width as a maximum length and
 * the type domain as an enumeration, so a caller reads both without reading this file, and the test
 * channel evaluates the same annotations directly.
 *
 * <p>Assumptions: this record performs no padding removal. The identifier column is declared with a
 * character type of exactly 8 positions, so a value read from it arrives blank-padded, and that
 * padding is a storage representation concern which this context places in its
 * {@code com.carddemo.auth.mapper} package. A mapper stands between the stored row and this record,
 * so the values reaching these components have already had it removed; an inbound request has no
 * such intermediary, which is why the normalising treatment belongs on a request type and not here.
 *
 * <h2>What the test channel has to assert</h2>
 *
 * <p>Assumptions: no executable oracle exists for this bounded context, so the obligations below
 * fall on assertions against cited lines rather than against a reference run. All five auth programs
 * are online programs written against the transaction monitor's command-level interface, and
 * {@code tests/README.md} records at its lines 83 to 85 that such programs cannot run end to end
 * without a CICS runtime, which is absent on the runner. Nothing stated in this file can therefore
 * be settled by running a baseline program, and every width, order and admissible value here is
 * instead verifiable by reading the cited file at the cited line, which is why the citations are
 * exact.
 *
 * <p>Assumptions: five assertions are required of whatever tests this record. That it declares
 * exactly these five components in exactly this order, matching the five columns of
 * {@code auth.users} and the five properties of the published schema. That a 9-character identifier
 * is refused while an 8-character one is accepted, pinning the bound at the width
 * {@code app/cpy/CSUSR01Y.cpy} L18 declares rather than one position either side of it. That a
 * 21-character name is refused while a 20-character one is accepted. That {@code userType} admits
 * {@code 'A'} and {@code 'U'} and refuses everything else, which means a null, an empty string, a
 * blank, the lower-case forms of either letter, and the two letters together are each rejected. And
 * that the type exposes no accessor for a credential, which is the assertion that would fail first
 * if the omission documented above were ever undone.
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
 *     {@code java.util.UUID} rather than a string. It is supplied when the identity-provider account
 *     is provisioned and is never minted here; it is returned so that an administrator can tell
 *     which pool account a row represents
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
     *
     * <p>Alternatives Considered: writing 8 straight into the constraint annotation was the obvious
     * alternative and is rejected. Three distinct widths govern this record's four character
     * components -- 8 here, 20 for each name and 1 for the type -- and a bare number inside an
     * annotation is the one place a reader cannot tell which copybook line it was read from. A named
     * constant leaves exactly one line to check against L18.
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
     *
     * <p>Trade-offs: sharing a constant across the two components couples them, so a future change
     * to one name width would have to split this constant rather than edit it. That is accepted
     * because the coupling is the contract: L19 and L20 are two fields of one 80-byte record whose
     * widths sum with the others to exactly that length, and a change to one of them alone would
     * break that arithmetic rather than being absorbed by it.
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
     *
     * <p>Assumptions: because the class matches exactly one character, this one expression also
     * carries the minimum length of one and the maximum length of one that the published contract
     * declares for the property. A separate size constraint alongside it would report one
     * out-of-domain value twice, naming the same component in two violations for a single mistake,
     * which is why the width is enforced by this expression rather than beside it.
     *
     * <p>Assumptions: the expression is case-sensitive, and that is load-bearing. A COBOL condition
     * name compares the bytes of its field against the literal it was declared with, so
     * {@code CDEMO-USRTYP-ADMIN} at L27 is satisfied by {@code 'A'} and not by {@code 'a'}.
     * Admitting the lower-case forms would let this type accept values the baseline condition would
     * have refused, and the database check constraint on {@code auth.users} would then reject on
     * write what this contract had published as valid.
     */
    private static final String USER_TYPE_DOMAIN = "[AU]";
}
