package com.carddemo.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Carries one row of the administrative user list, holding the four values that row displayed.
 *
 * <p><b>Purpose.</b> The reference list screen presented a grid of ten rows, and every row showed an
 * identifier, a given name, a family name and a one-character type. This record is one of those rows
 * and nothing more. It is the element type the shared page envelope carries, so a response from the
 * list endpoint is an envelope of these rather than a bare array. It is a projection for browsing
 * and not a whole user: the identity-provider subject reference that the owning table also holds has
 * no component here, and a caller needing that value reads the individual row instead.
 *
 * <p><b>Parameters, return values, exceptions or errors at the type level.</b> The four record
 * components are this type's parameters, and each carries its own at-clause below. A type
 * declaration returns no value and raises nothing, and this record declares no member of its own, so
 * no return or exception at-clause appears here. That inapplicability is stated rather than left
 * silent, because the project's Explainability rule lists a docstring omitting parameters or return
 * values among its forbidden patterns at line 39, and a reader has to be able to tell a declared
 * inapplicability from an oversight.
 *
 * <h2>Where the four widths come from</h2>
 *
 * <p>Assumptions: one reference record is the width authority, and it is not this screen.
 * {@code app/cpy/CSUSR01Y.cpy} declares {@code 01 SEC-USER-DATA} at line 17 and its subordinate
 * fields at lines 18 to 23: an eight-character {@code SEC-USR-ID} at zero-based byte offset 0, a
 * twenty-character {@code SEC-USR-FNAME} at offset 8, a twenty-character {@code SEC-USR-LNAME} at
 * offset 28, an eight-character field at offset 48 that this record does not carry, a one-character
 * {@code SEC-USR-TYPE} at offset 56, and a twenty-three-character {@code SEC-USR-FILLER} at offset
 * 57. Those widths sum to exactly eighty bytes, which is the whole record, so the arithmetic is
 * self-checking: a component width that disagrees with that copybook is wrong by construction rather
 * than by opinion. The list map corroborates all four independently at
 * {@code app/cpy-bms/COUSR00.CPY} lines 78, 84, 90 and 96.
 *
 * <p>Assumptions: the component ORDER comes from the map rather than from the copybook, because the
 * two genuinely differ. The copybook runs identifier, given name, family name, the offset-48 field,
 * type. The grid row runs identifier, given name, family name, type, and that is the order declared
 * below. Ten row groups repeat that shape on a thirty-line stride, from {@code SEL0001I} at
 * {@code app/cpy-bms/COUSR00.CPY} line 72 through {@code UTYPE10I} at line 366. The tenth group is
 * the last the map declares, which is what makes ten the whole of a page rather than merely the
 * first ten of something longer.
 *
 * <p>Assumptions: the scratch layout inside the reference list program is deliberately not the model
 * for this record, and the distinction is load-bearing because that layout states two widths this
 * record contradicts. {@code app/cbl/COUSR00C.cbl} declares {@code 01 WS-USER-DATA} across lines 56
 * to 64, whose group repeats ten times and holds a one-character selection cell, an eight-character
 * identifier, a single COMBINED twenty-five-character {@code USER-NAME} at line 62 and a widened
 * eight-character {@code USER-TYPE} at line 64, padded to forty-eight bytes per row and four hundred
 * and eighty bytes across the ten. Neither the merged name width nor the widened type width is
 * carried here. That structure is a scroll buffer assembled for one terminal write, so it describes
 * how a row was PAINTED and not what a row IS; the map, which keeps the two names apart at twenty
 * characters each and the type at one, is the authority for the shape of a transfer object.
 *
 * <h2>The one-character type</h2>
 *
 * <p>Assumptions: {@code app/cpy/COCOM01Y.cpy} is the sole authority for the two admissible values,
 * and the same domain is asserted in three independent places that this record is only one of. Line
 * 26 of that copybook declares {@code CDEMO-USER-TYPE PIC X(01)} and lines 27 and 28 name its two
 * condition values as the quoted literals {@code 'A'} for administrator and {@code 'U'} for ordinary
 * user. {@code app/cpy/CSUSR01Y.cpy} line 22 declares the same one-character width but attaches no
 * condition names whatsoever, so it settles the width and cannot settle the domain -- which is why
 * the width citation above and the domain citation here name different files. The three enforcement
 * points are the constraint on the component below, the service logic that admits a submitted value,
 * and the {@code CHECK (user_type IN ('A','U'))} clause on the owning table at line 49 of
 * {@code V1__auth.sql}. Assumptions: the three are not redundant, because each covers a path the
 * others do not -- the constraint rejects a malformed payload before any handler runs, the service
 * governs values that reach it by other routes, and the table constraint holds for every writer
 * including a migration or an operator session that never passes through this record at all.
 *
 * <p>Assumptions: a type value appearing on this record is reported data about the user being
 * administered, never an input to an authorisation decision about the caller. The reference system
 * carried the caller's own type in a communication area the terminal handed back, so a caller could
 * in principle assert its own type; the target reads the caller's type from a signed token claim the
 * caller cannot author. A handler that consulted this component to decide what the caller may do
 * would reintroduce exactly the weakness the signed claim removes.
 *
 * <h2>What this record deliberately omits</h2>
 *
 * <p>Refactoring Rationale: the grid's selection cell has no component here. The reference row
 * carried {@code SEL0001I PIC X(1)} at {@code app/cpy-bms/COUSR00.CPY} line 72 and nine identical
 * siblings, into which an operator typed one of the two letters that
 * {@code app/cbl/COUSR00C.cbl} line 212 names in its rejection text. What was wrong with carrying it
 * forward is that it was never data about a user: it was a screen input naming an action, and an
 * action is now expressed by the client addressing that row's own identifier -- issuing
 * {@code PUT /api/v1/users/{userId}} or {@code DELETE /api/v1/users/{userId}} -- so the letter has
 * nothing left to select. Reproducing it would put a mutable field carrying no user value on every
 * row of every page.
 *
 * <p>Refactoring Rationale: the page number has no component here either, and it is omitted on
 * stronger evidence than the selection cell. The reference system declares it twice, as
 * {@code PAGENUMI PIC X(8)} at {@code app/cpy-bms/COUSR00.CPY} line 60 and as
 * {@code CDEMO-CU00-PAGE-NUM PIC 9(08)} at {@code app/cbl/COUSR00C.cbl} line 70, yet in that program
 * it is display-only: both of its uses, at lines 327 and 376, move it into the screen field, and no
 * read is ever positioned by it. Its one appearance in control flow is the guard at line 248, which
 * decides only whether to emit an at-the-top message. A counter that never positions a read cannot
 * be what positions a query either, so no page number, page length, total-count or total-page
 * component exists on this record or anywhere in this package.
 *
 * <p>Assumptions: no message component and no length-clamping path. The reference message field is
 * {@code ERRMSGI PIC X(78)} in all five maps of this bounded context -- at
 * {@code app/cpy-bms/COSGN00.CPY} line 84, {@code app/cpy-bms/COUSR00.CPY} line 372,
 * {@code app/cpy-bms/COUSR01.CPY} line 90, {@code app/cpy-bms/COUSR02.CPY} line 90 and
 * {@code app/cpy-bms/COUSR03.CPY} line 84 -- and the longest text these programs emit is the
 * forty-four-character string at {@code app/cbl/COUSR00C.cbl} line 273, which leaves thirty-four
 * characters of the declared width unused. So the truncation a narrower target width would have
 * forced is not merely unimplemented, it has no input that could reach it. Message text travels in
 * the problem shape that {@code com.carddemo.common.error} owns, and no component here carries or
 * shortens one.
 *
 * <p>Assumptions: this record carries no field the reference list map does not itself declare, and
 * for the authentication field that is a matter of physical absence rather than of policy alone. A
 * census of {@code app/cpy-bms/COUSR00.CPY} finds zero occurrences of the symbol that
 * {@code app/cpy-bms/COSGN00.CPY}, {@code app/cpy-bms/COUSR01.CPY} and
 * {@code app/cpy-bms/COUSR02.CPY} each carry ten times, so the screen this record projects never
 * held such a value to begin with. The offset-48 field of the reference record is likewise absent
 * from every response in this package.
 *
 * <h2>The page envelope, and why it is referenced rather than declared</h2>
 *
 * <p>Alternatives Considered: declaring a page wrapper in this package, so that a reader of the list
 * response would open one package instead of two. Rejected, because the envelope is already declared
 * once as {@code com.carddemo.common.web.PageResponse}, whose four components are the page items,
 * a leading cursor token, a trailing cursor token and a further-page indicator, and a second
 * declaration would be the duplication the house doctrine names directly: {@code tests/README.md}
 * instructs at its lines 540 to 542 never to duplicate a layout and to keep it single-sourced, and
 * two competing envelopes are that same duplication one level above a record layout. The concrete
 * cost of drifting is not a build failure but inconsistent paging between screens, which surfaces
 * only as rows quietly lost or repeated at a page boundary.
 *
 * <p>Alternatives Considered: positioning a page by counting rows from the start of the ordered set,
 * which the reference page number superficially resembles. Rejected on a specific defect rather than
 * a preference: under concurrent insertion the number of rows preceding a counted position changes
 * underneath a reader between one request and the next, so a page positioned that way omits some
 * rows and presents others twice, whereas a key already read keeps its place in the ordering no
 * matter what is inserted around it. The reference browse was itself keyed, holding two identifier
 * values and a flag at {@code app/cbl/COUSR00C.cbl} lines 68, 69 and 71, so the target reproduces a
 * cursor that already existed rather than inventing one.
 *
 * <p>Assumptions: the number of these rows in one page is a property of the server and never a
 * client-supplied value, which is why no component here names a page length. The reference fixes it
 * at ten twice over -- the repeating group at {@code app/cbl/COUSR00C.cbl} line 57 occurs ten times,
 * and the map declares exactly ten row groups between {@code app/cpy-bms/COUSR00.CPY} lines 72 and
 * 366 -- and neither is parameterised.
 *
 * <p>Assumptions: the further-page indicator that the envelope reports is derived by reading one row
 * beyond the ten, not by counting the set. The reference does precisely that at
 * {@code app/cbl/COUSR00C.cbl} lines 311 to 316, issuing one extra read after the grid is filled and
 * setting its flag from whether that read succeeded, with a third path at line 318 for an exhausted
 * set. Assumptions: the two cursor tokens the envelope reports are derived from the rows actually
 * returned. The baseline instead writes its leading identifier only on the first grid slot, at line
 * 389, and its trailing identifier only on the tenth, at line 435, so a final page holding between
 * two and nine rows leaves the trailing value describing an earlier page; the target derives both
 * ends from the rows it returns, and the divergence is recorded in the migration's traceability
 * notes rather than treated as a defect of this record.
 *
 * <h2>Authoring decisions for this record</h2>
 *
 * <p>Trade-offs: the one-character type stays a {@code String} of length one instead of becoming a
 * Java enumeration. What that costs is compile-time exhaustiveness: a switch over the two values
 * cannot be checked by the compiler, and a wrong letter is caught by a constraint at run time rather
 * than by a build failure. What it buys is that the published contract keeps the exact shape the
 * reference declares and the owning column stores -- one character, admitting the two letters named
 * at {@code app/cpy/COCOM01Y.cpy} lines 27 and 28 -- with no separate name for a value that is one
 * character on the screen, one character in the column and one character in the API document. An
 * enumeration would also make an unrecognised letter a deserialisation failure of the whole payload
 * instead of one reportable field error, which loses the per-field detail a caller needs.
 *
 * <p>Trade-offs: this record declares no compact constructor and no explicit accessor, so every
 * behaviour except its string form is what the record header generates. The alternative, a compact
 * constructor that trimmed the padding of the reference record or rejected an inadmissible letter, was
 * evaluated and rejected because this type has no invariant spanning two components for such a
 * constructor to enforce; each of the four constraints below is independent, and a constraint
 * annotation reports every failing component at once whereas a constructor that raises stops at the
 * first. Padding is likewise not this type's concern: the copybook's trailing blanks are record
 * padding rather than name data, and they are removed where the record is decoded, upstream of this
 * projection.
 *
 * <p>Refactoring Rationale: the string form is the one generated behaviour this record does override,
 * and the reason is that leaving it generated is not the neutral choice it appears to be. A record
 * prints every component beside its value, so the generated form publishes a given name and a family
 * name for each of the ten rows a page carries; declining to override does not withhold them, it
 * discloses them, and it does so in log lines, assertion messages and exception details rather than in
 * the response a caller asked for. Overriding is therefore the narrower behaviour, not the additional
 * one.
 *
 * @param userId the row's identifier, at most eight characters, from {@code USRID01I PIC X(8)} at
 *     {@code app/cpy-bms/COUSR00.CPY} line 78 and {@code SEC-USR-ID PIC X(08)} at offset 0 of
 *     {@code app/cpy/CSUSR01Y.cpy} line 18; it is the value a caller places in the request path to
 *     read, update or delete this row
 * @param firstName the user's given name, at most twenty characters, from
 *     {@code FNAME01I PIC X(20)} at {@code app/cpy-bms/COUSR00.CPY} line 84 and
 *     {@code SEC-USR-FNAME PIC X(20)} at offset 8 of {@code app/cpy/CSUSR01Y.cpy} line 19; the
 *     trailing blanks of the reference record were padding and are not part of the value
 * @param lastName the user's family name, at most twenty characters, from
 *     {@code LNAME01I PIC X(20)} at {@code app/cpy-bms/COUSR00.CPY} line 90 and
 *     {@code SEC-USR-LNAME PIC X(20)} at offset 28 of {@code app/cpy/CSUSR01Y.cpy} line 20, with
 *     padding excluded on the same ground
 * @param userType the administered user's role as exactly one character, from
 *     {@code UTYPE01I PIC X(1)} at {@code app/cpy-bms/COUSR00.CPY} line 96, admitting only
 *     {@code A} for administrator or {@code U} for ordinary user as the condition names at
 *     {@code app/cpy/COCOM01Y.cpy} lines 26 to 28 declare them; it describes the user in this row
 *     and is never read to decide what the caller may do
 */
public record UserSummary(
        // Assumptions: the three annotations on each component mirror the published contract keyword
        //   for keyword and add nothing to it. NotNull mirrors the schema's required list, Size
        //   mirrors its maxLength and minLength, and Pattern mirrors its enum. Restating the contract
        //   here rather than inferring a looser one keeps a payload this record accepts and a payload
        //   the API document promises from diverging silently.
        @NotNull @Size(max = 8) String userId,
        // Assumptions: twenty is the declared width of both name fields, not a guess at a longest
        //   name, so a value longer than twenty could not have come from the reference record and is
        //   rejected rather than truncated on the way through.
        @NotNull @Size(max = 20) String firstName,
        @NotNull @Size(max = 20) String lastName,
        // Assumptions: the length bound and the letter bound are separate constraints because they
        //   fail for different reasons and a caller benefits from being told which. A two-character
        //   value violates the declared one-character width; a one-character value outside the pair
        //   at app/cpy/COCOM01Y.cpy lines 27 and 28 is a well-formed character outside the domain.
        //   The pattern matches the whole value, so it admits exactly one of the two letters.
        @NotNull @Size(min = 1, max = 1) @Pattern(regexp = "[AU]") String userType) {

    /**
     * The placeholder that stands in for a component this record refuses to render.
     *
     * <p>Assumptions: the literal matches the one {@code com.carddemo.auth.dto.SignOnResponse} uses
     * for its token values and the one {@code com.carddemo.auth.dto.UserResponse} uses for its
     * personal values, so a log store holding lines from all three types shows one placeholder
     * vocabulary. It is declared here rather than borrowed from either sibling because a placeholder
     * reached across type boundaries would couple two projections that have no other relationship,
     * and the cost of the duplication is one literal whose value carries no behaviour.
     */
    private static final String REDACTED_PERSONAL = "REDACTED";

    /**
     * Renders this row for diagnostics with both name components replaced by a placeholder.
     *
     * <p>Refactoring Rationale: this record is a page item, so the exposure it carries is multiplied
     * by the ten rows a page holds -- one stringified page publishes ten given names and ten family
     * names. That is the difference between this record and a single-row response: the same generated
     * behaviour discloses a list of people rather than one, which is the shape a log store is most
     * useful to an attacker in.
     *
     * <p>Trade-offs: {@code userId} and {@code userType} print in full and the two names do not.
     * {@code userId} is the row locator a caller places in the request path, assigned by the system
     * rather than a fact about the person, and a rendering that located no row could not correlate
     * anything; {@code userType} is a two-valued role classification with no personal content. What is
     * given up is being able to tell two instances of the same row apart from their printed form, which
     * costs nothing here because a page never holds one row twice -- the ordering the cursor pages over
     * is by the identifier that prints.
     *
     * <p>Assumptions: only the string form is narrowed. Equality, hashing and serialisation are
     * untouched, so the page envelope this record travels in still carries every value the published
     * contract promises to the caller that asked for it.
     *
     * @return a single-line description of this row naming every component in contract order, in which
     *     the given name and the family name are each represented by a placeholder and never rendered
     */
    @Override
    public String toString() {
        // Assumptions: the generated form's shape is reproduced deliberately, component order
        //   included, so that a reader who knows what a record prints is not led to think some other
        //   type produced this line. Only the two name values depart from it.
        return "UserSummary[userId=" + userId
                + ", firstName=" + REDACTED_PERSONAL
                + ", lastName=" + REDACTED_PERSONAL
                + ", userType=" + userType
                + "]";
    }
}
