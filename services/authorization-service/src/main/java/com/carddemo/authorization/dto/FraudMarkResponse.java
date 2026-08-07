package com.carddemo.authorization.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Reports the outcome of one fraud-marking attempt on a pending authorization.
 *
 * <h2>Which half of the exchange this is</h2>
 *
 * <p>The baseline conducts the whole fraud-marking exchange over a single area.
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} declares {@code 01 DFHCOMMAREA} at L74
 * and receives a request in it, writes an outcome back into the same storage, and returns. This
 * record is the outcome half of that area and carries two components and nothing else: the
 * success-or-failure flag and the operator-facing text that accompanies it.
 *
 * <h2>Which edge this record serves</h2>
 *
 * <p>Refactoring Rationale: this record is both the projection of what the 3270 fraud-marking subprogram
 * reported AND the body the HTTP edge returns, and it is one type rather than two because
 * {@code src/main/resources/openapi/authorization-api.yaml} -- the contract of record for this context's
 * HTTP edge -- was aligned to it component for component. An earlier state of this package published the
 * same payload under a second schema name with four further members, which left the document and the
 * record disagreeing on how many components a reply has and left one operation described under two names.
 * Alternatives Considered: declaring a separate HTTP realisation beside this record, which is the shape
 * the two symbolic-map projections in this package genuinely need. Rejected here because this record
 * carries no screen chrome to strip and no page envelope to add -- it is the two-field response direction
 * of {@code cbl/COPAUS2C.cbl} L83 and L86 and nothing else -- so a second type would have added a name
 * without adding a distinction. Trade-offs: the insert-versus-update outcome is therefore not a member of
 * this body; it is carried on the status code, 201 against 200.</p>
 *
 * <p>Assumptions: the baseline sets both components on every path it can take, so an outcome is
 * always composed rather than sometimes omitted. There are four such paths and each is checkable.
 * An insert that succeeds sets the success condition and the text {@code 'ADD SUCCESS'} at L200 to
 * L201. A duplicate key, which the program tests for as {@code SQLCODE = -803} at L203, diverts to
 * an update instead of failing, and that update in turn either sets the success condition and
 * {@code 'UPDT SUCCESS'} at L231 to L232 or sets the failure condition and a Db2 code-and-state
 * string at L234 to L242. An insert that fails for any other reason sets the failure condition and
 * a differently-worded Db2 code-and-state string at L206 to L214. Marking is therefore an
 * insert-or-update attempt reported as one outcome, which is why a single flag suffices for both
 * shapes of success.
 *
 * <p>Refactoring Rationale: only the TWO SUCCESS paths of those four are carried by this record.
 * The two failure paths are answered with a non-2xx {@code com.carddemo.common.error.ApiError}
 * instead, so this body's flag has a single-value domain and its message never carries database
 * code, state or vendor text. Two independent reasons decide it. A 2xx envelope carrying a body
 * that says the write failed is a contradiction a client cannot resolve, and the reference has no
 * such ambiguity to preserve because it has no status code at all -- {@code cbl/COPAUS1C.cbl} L255
 * to L258 tests the success condition and, when it does not hold, moves the failure sentence into
 * the message line and performs {@code ROLL-BACK}, so a failed write reaches the operator as an
 * error screen. And the database code-and-state text is vendor diagnostic detail about this
 * service's own store, so publishing it to an API caller would disclose internals that
 * {@code GlobalExceptionHandler} withholds everywhere else; it is retained for the operator in the
 * failure log instead, which is the channel scoped to the people who may read it.
 *
 * <h2>One area in the baseline, two records in the target</h2>
 *
 * <p>Assumptions: the area is 272 bytes and serves both directions at once, which is a shape HTTP
 * does not have. The 272 is the sum of its parts: {@code WS-ACCT-ID PIC 9(11)} at L75 contributes
 * 11 and {@code WS-CUST-ID PIC 9(9)} at L76 contributes 9; the {@code COPY CIPAUDTY} at L78
 * contributes 200, computed from the 28 elementary items of
 * {@code app/app-authorization-ims-db2-mq/cpy/CIPAUDTY.cpy} whose fields begin at L19; and the
 * group item opened at L79 contributes 1, 1 and 50. HTTP separates a request from a response, so
 * the one area becomes two records. This record is the L83 and L86 half of it. Of the request half,
 * only the L80 action is carried by {@code FraudMarkRequest}; the L75 and L76 identifiers are not
 * accepted from a caller, the row being named by the operation's sealed path selector.
 *
 * <p>Assumptions: the two single-character flags sit immediately after one another inside the one
 * group item opened at L79 -- {@code WS-FRD-ACTION} at L80 then {@code WS-FRD-UPDATE-STATUS} at L83
 * -- and that adjacency is the reason the character collision described on {@code updateStatus}
 * below is easy to read past. Nothing in the baseline is ambiguous about it, because the two
 * conditions are declared on two different fields and a COBOL condition name tests the field it was
 * declared under. The hazard belongs entirely to the migration: it arises only if the two fields are
 * collapsed into one target type, and it is recorded here so that they are not.
 *
 * <h2>The message width is 50, and three other widths in this module are not it</h2>
 *
 * <p>Assumptions: this record's message is bounded at 50 characters because
 * {@code WS-FRD-ACT-MSG PIC X(50)} declares 50 positions at L86. Three further message widths
 * circulate in this module and none of them governs this record. 78 is the width of the
 * {@code ERRMSGI} and {@code ERRMSGO} fields on both authorization screens, at
 * {@code app/app-authorization-ims-db2-mq/cpy-bms/COPAU00.cpy} L390 and L764 and at
 * {@code cpy-bms/COPAU01.cpy} L180 and L344, and that regime governs
 * {@code PendingAuthSummaryResponse} and {@code PendingAuthDetailResponse} instead. 75 is the house
 * {@code CCARD-ERROR-MSG} and {@code CCARD-RETURN-MSG} contract at {@code app/cpy/CVCRD01Y.cpy} L28
 * to L29, in a copybook this module never includes, so it applies nowhere in this package. 80 is
 * {@code WS-MESSAGE PIC X(80)}, the working-storage feeder the screen programs hold at
 * {@code cbl/COPAUS0C.cbl} L37 and {@code cbl/COPAUS1C.cbl} L37, itself wider than the
 * 78-character screen field it feeds.
 *
 * <p>Assumptions: sizing this component from any of those three has a consequence in both
 * directions. A bound taken from the 75-character or 78-character regime would accept a message 25
 * or 28 characters longer than the baseline field can hold, so a value this type declared as valid
 * could not be written back into {@code WS-FRD-ACT-MSG} without losing its tail. A bound below 50
 * would truncate a message the baseline actually emits. The second half of that is not
 * hypothetical, and the arithmetic is worth stating because it settles the width by measurement
 * rather than by reading a picture clause alone: the failure text assembled at L211 to L213
 * concatenates a 24-character literal, the 7 positions of {@code WS-SQLCODE PIC +9(06)} declared at
 * L55, a 9-character literal, and the 10 positions of {@code WS-SQLSTATE PIC +9(09)} declared at
 * L56, which is exactly 50 characters. The widest message the program can produce fills the field
 * to its last byte with nothing to spare, so 50 is both the declared width and the observed one.
 *
 * <h2>Why the two bounds are declared rather than enforced by throwing</h2>
 *
 * <p>Alternatives Considered: the available alternative was a compact constructor that refuses an
 * out-of-domain flag or an over-width message by throwing, which would make the bounds hold for
 * every caller including one inside this service. It is rejected, and the reason is specific to
 * this type being an outcome rather than a request. An instance of this record is built after the
 * state change has already been attempted and, on the success paths, after it has been committed.
 * A throw at that point does not prevent anything; it replaces a legible outcome with an
 * unstructured server failure, and it does so at the moment the outcome matters most: it would
 * report a failure for a mark that was applied, which inverts the one fact the caller asked for.
 * Declaring the bounds instead keeps every outcome deliverable, and a value that breaches one is
 * then visible as a constraint violation naming the component rather than as a request that appears
 * to have failed.
 *
 * <p>Assumptions: what a declared bound does on a response is narrower than what it does on a
 * request, and it is stated plainly here rather than left to be assumed. The framework evaluates a
 * request body's constraints as it binds it; nothing evaluates a response body's constraints on the
 * way out. These two annotations are therefore the published contract and the tested one, not an
 * outbound gate: this module's OpenAPI 3.1 document renders the width as a maximum length and the
 * domain as a pattern, so a client reads both bounds without reading this file, and the test
 * channel evaluates the same annotations directly.
 *
 * <p>Assumptions: this record performs no padding removal, because a declared-width field's
 * trailing padding is a copybook representation concern and this context places those in its
 * {@code com.carddemo.authorization.mapper} package, whose charter names a padding field among the
 * concerns it exists to absorb. The direction of travel is what makes that placement work here: a
 * mapper stands between the stored representation and this record, so the values reaching these
 * components have already had their padding removed. A request bound straight from a document has
 * no such intermediary, which is why the normalising treatment belongs on an inbound type and not
 * on this one.
 *
 * <h2>This record is not the problem shape</h2>
 *
 * <p>Trade-offs: expressing failure only through {@code com.carddemo.common.error.ApiError} and
 * returning no body at all on success was the alternative, and it was not taken. Carrying the
 * baseline's own flag and its 50-character text lets a client observe the outcome verbatim, in the
 * words the program chose, which a generic problem document cannot reproduce. The compromise
 * accepted is real and is worth naming: there are now two ways for a caller to learn that something
 * went wrong, and two ways is one more than one. The boundary between them is what keeps that
 * workable. {@code ApiError} carries failures of protocol and of validation, where the request was
 * not understood or not permitted or not well-formed, and it is produced centrally by
 * {@code com.carddemo.common.error.GlobalExceptionHandler}. This record carries the outcome of a
 * state change the server did understand, did permit and did attempt. A caller reads the response
 * status to learn whether the request was accepted, and reads {@code updateStatus} to learn whether
 * the mark was applied. Neither shape is re-declared here, and a per-component validation failure
 * is never folded into {@code message}: those surface through the per-field array
 * {@code ApiError} carries and through
 * {@code com.carddemo.common.validation.FieldValidationFlag}.
 *
 * <p>Assumptions: the two bounds are deliberately asymmetric -- the flag is required and the
 * message is not -- and the asymmetry follows from what each component carries rather than from
 * convenience. Without the flag the response states no outcome whatsoever, so it answers nothing
 * and is refused. Without the message the response still states {@code 'S'} or {@code 'F'}, so the
 * outcome remains legible and only the accompanying diagnostic is thinner. A bound that refused an
 * absent message would reject a response that is imperfect but still answers the question asked.
 *
 * <h2>What the test channel has to assert</h2>
 *
 * <p>Assumptions: the obligations that fall on this record's tests are written down here rather than
 * left to be inferred from the component declarations, because two of the three are about what must
 * be REJECTED and a reader cannot derive a rejection set from an annotation alone. Three assertions
 * are required. First, that {@code updateStatus} admits
 * {@code 'S'} and {@code 'F'} and nothing else, which means a null, an empty string, a blank, a
 * lowercase {@code 's'} and the two characters together are each rejected. Second, that a message
 * of exactly 50 characters is accepted while one of 51 is rejected, which pins the bound at the
 * width L86 declares rather than one position either side of it. Third, that an
 * {@code updateStatus} of {@code 'F'} is not interchangeable with a {@code FraudMarkRequest}
 * {@code action} of {@code 'F'} -- the two are separate components of separate types with separate domains, and a
 * test that can pass either one where the other belongs has compiled the distinction away.
 *
 * @param updateStatus that the attempt SUCCEEDED, as the one character
 *     {@code WS-FRD-UPDATE-STATUS PIC X(01)} declares at
 *     {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L83. The only admitted value is
 *     {@link #UPDATE_STATUS_SUCCESS}, the success condition {@code WS-FRD-UPDT-SUCCESS} declared at
 *     L84, and it is required because a response stating no outcome answers nothing. The reference
 *     program's failure value {@code 'F'} -- {@code WS-FRD-UPDT-FAILED} at L85 -- is deliberately NOT
 *     admitted here; a failed write is answered with a non-2xx {@code ApiError} instead, for the
 *     reason argued in the section above. Read the reference {@code 'F'} carefully wherever it does
 *     appear: on the response side it means the update failed, whereas the same character in the
 *     adjacent request field means report this authorization as fraudulent, which
 *     {@code WS-REPORT-FRAUD} declares at L81 alongside {@code WS-REMOVE-FRAUD} for {@code 'R'} at
 *     L82. This component consequently never shares a type with the action component of
 *     {@code FraudMarkRequest}; if either were ever expressed as an enum they would have to be two
 *     distinct enums, because one type spanning both domains would let a request to report fraud be
 *     read as a failed update and the reverse
 * @param message the operator-facing text accompanying the outcome, as
 *     {@code WS-FRD-ACT-MSG PIC X(50)} declares at L86, of at most 50 characters, or {@code null}
 *     when no text accompanies the outcome. 50 is the width that governs this component, and
 *     neither the 78-character screen-message regime of the two authorization maps nor the
 *     75-character house message contract of {@code app/cpy/CVCRD01Y.cpy} reaches it. Only the two
 *     SUCCESS wordings reach it -- {@code 'ADD SUCCESS'} at L201 when the fraud row was inserted and
 *     {@code 'UPDT SUCCESS'} at L232 when an existing row was updated -- and the database
 *     code-and-state strings the reference program assembles on its failure paths at L211 to L213
 *     and L239 to L241 never do, because this body is not the failure carrier. The 50-character
 *     bound is nonetheless taken from the declared width rather than from the longer of the two
 *     admitted wordings, so that the component still describes the field it migrates
 */
public record FraudMarkResponse(
        @NotNull @Pattern(regexp = UPDATE_STATUS_DOMAIN) String updateStatus,
        @Size(max = ACTION_MESSAGE_WIDTH) String message) {

    /**
     * The number of positions the baseline declares for the outcome message.
     *
     * <p>Assumptions: 50 is read from {@code WS-FRD-ACT-MSG PIC X(50)} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L86, and the program corroborates
     * it from the inside: the longest text it assembles, at L211 to L213, comes to exactly 50
     * characters. A declared width that the declaring program also fills exactly is a contract
     * rather than a reading, so this constant has two independent sources and neither is an
     * inference.
     *
     * <p>Alternatives Considered: writing 50 straight into the constraint annotation was the
     * obvious alternative and is rejected. This module handles four message widths -- 50, 78, 75
     * and 80 -- and a bare number inside an annotation is the one place a reader cannot tell which
     * of the four it is. A named constant carrying its provenance leaves exactly one line to check
     * against L86. The width also appears in this file's prose, which is documentation and not a
     * second executable source, since nothing reads those sentences to decide anything.
     */
    private static final int ACTION_MESSAGE_WIDTH = 50;

    /**
     * The one value this response's outcome flag may carry: the write succeeded.
     *
     * <p>Assumptions: {@code 'S'} is the success condition {@code WS-FRD-UPDT-SUCCESS} declared at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L84. It is published as a named
     * constant rather than as a literal inside the expression below so that the accompanying test and
     * the published contract's {@code const} can be compared against one symbol.</p>
     */
    public static final String UPDATE_STATUS_SUCCESS = "S";

    /**
     * The expression the outcome flag has to match in full.
     *
     * <p>Refactoring Rationale: the domain is ONE character and previously it was two. Admitting the
     * reference program's failure value {@code 'F'} on this body described a response that says the
     * write FAILED inside a status that says the request SUCCEEDED, which is a contradiction no client
     * can resolve: a caller branching on the status code would record a fraud report that was never
     * persisted, and a caller branching on this member would ignore a 2xx. The reference has no such
     * ambiguity to preserve because it has no status code -- {@code cbl/COPAUS1C.cbl} L255 to L258
     * tests {@code WS-FRD-UPDT-SUCCESS} and, when it does not hold, moves the failure sentence into
     * the message line and performs {@code ROLL-BACK}, so a failed write reaches the operator as an
     * error screen and never as a confirmation. The migrated form of that error screen is a non-2xx
     * {@code com.carddemo.common.error.ApiError}, which is where the failure value went. The
     * published contract states the same narrowing as a {@code const}, so the two now agree.</p>
     *
     * <p>Trade-offs: the member is KEPT rather than deleted even though its domain is now a single
     * value. It is the migrated face of a reference field a client may already read, and its
     * {@code 'S'} is what distinguishes this body from a body of some future shape that reports a
     * partial write. Alternatives Considered: deleting it and letting the 200 alone carry the meaning.
     * Rejected because a body read away from its envelope -- in a log, in a replayed capture, in a
     * client that stored it -- has to remain self-describing.</p>
     *
     * <p>Assumptions: the admitted character is written as an explicit one-character class rather than
     * as a shorthand or an alternation, so the domain cannot quietly widen. The expression carries no
     * anchors because it does not need them: a constraint of this kind is satisfied only when the
     * whole value matches, so a one-character class refuses {@code "SS"} without an anchor.</p>
     *
     * <p>Assumptions: the expression is case-sensitive, which is deliberate and load-bearing. A COBOL
     * condition name compares the bytes of its field against the literal it was declared with, so
     * {@code WS-FRD-UPDT-SUCCESS} at L84 is satisfied by {@code 'S'} and not by {@code 's'}. Admitting
     * the lowercase form would let this type accept a value the baseline condition would have
     * rejected, which is a widening of the domain rather than a leniency.</p>
     *
     * <p>Alternatives Considered: pairing this expression with a not-blank constraint instead of a
     * not-null one. Rejected because it would report one invalid value twice. A blank flag already
     * fails this expression, since a blank is not the admitted character, so a not-blank constraint
     * would add a second violation naming the same component for it. A not-null constraint covers the
     * one case this expression cannot see at all: constraint evaluation treats an absent value as
     * satisfied, so without it a null flag would pass and the domain would have a second state.</p>
     */
    private static final String UPDATE_STATUS_DOMAIN = "[S]";

    /**
     * The sentence the reference program reports when the fraud row was CREATED.
     *
     * <p>Assumptions: carried character for character from {@code MOVE 'ADD SUCCESS'} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L201, which transformation rule T8
     * requires of every user-visible string. It is published here rather than restated at the write
     * site because a literal typed twice is a literal that can differ in one place.
     */
    public static final String MESSAGE_ADD_SUCCESS = "ADD SUCCESS";

    /**
     * The sentence the reference program reports when an existing fraud row was REPLACED.
     *
     * <p>Assumptions: carried character for character from {@code MOVE 'UPDT SUCCESS'} at
     * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl} L232, reached only on the
     * duplicate-key branch that program tests at L203. The abbreviation is the baseline's own and is
     * not expanded: expanding it would be a change to a user-visible string.
     */
    public static final String MESSAGE_UPDATE_SUCCESS = "UPDT SUCCESS";

    /**
     * Builds the body for the path that created a fraud row.
     *
     * <p>Assumptions: a factory rather than a constructor call at the write site, because both
     * components are fixed for this outcome and a two-argument call would let one of them be paired
     * with the other outcome's value. There is no state to pass, so there is nothing for a caller to
     * get wrong.
     *
     * @return the success body carrying the reference insert sentence, never {@code null}
     */
    public static FraudMarkResponse added() {
        return new FraudMarkResponse(UPDATE_STATUS_SUCCESS, MESSAGE_ADD_SUCCESS);
    }

    /**
     * Builds the body for the path that replaced the state on an existing fraud row.
     *
     * @return the success body carrying the reference update sentence, never {@code null}
     */
    public static FraudMarkResponse updated() {
        return new FraudMarkResponse(UPDATE_STATUS_SUCCESS, MESSAGE_UPDATE_SUCCESS);
    }
}
