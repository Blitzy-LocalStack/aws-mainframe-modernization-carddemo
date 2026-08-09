package com.carddemo.reference.dto;

import com.carddemo.common.web.CursorToken;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The query parameters of the transaction-type browse, bound from the query string rather than from a
 * body because the operation is a read.
 *
 * <p>Purpose: this type is the inbound shape of the transaction-type list operation. It carries the
 * paging position, the direction that position is read in, and the two filters the browse declares --
 * and nothing else. It holds no rules of its own: the position is opened, the direction defaulted and
 * the filters applied by the service layer, so this is a validated parameter object rather than a step
 * in the browse. It reaches no store and names no collaborator.
 *
 * <p>Parameters, return values and errors at the type level: the four record components are this
 * type's parameters and each carries its own at-clause below. A type declaration returns no value and
 * raises nothing, so no return or exception at-clause appears here, and the canonical accessors the
 * compiler derives each return the component of the same name exactly as that at-clause describes it,
 * without defaulting or normalising anything. No accessor is written out, precisely so that none can
 * acquire logic that the at-clauses would then understate. The inapplicability is declared rather than
 * left silent, because a reader must be able to tell it from an oversight.
 *
 * <p>Assumptions: the position and the direction live on the request because
 * {@code com.carddemo.common.web.PageResponse} is exactly four members -- the rows, a leading boundary
 * token, a trailing boundary token and a further-page indicator. That reply says where the page ended;
 * it does not record where the caller intends to go next, and a stateless handler retains nothing
 * between two requests that could. So the caller returns one of those two tokens as the position and
 * states which of them it copied by sending the direction. That pair expresses exactly what a separate
 * forward-key and backward-key member would express: the direction next paired with the reply's
 * trailing token is the forward case, and previous paired with its leading token is the backward case.
 * One position is preferred over two members because the reply's tokens are indistinguishable in shape,
 * so a pair would let a caller populate both at once and leave the service arbitrating a request that
 * means two things.
 *
 * <p>Assumptions: the two query forms this position feeds are exact, and getting either the comparison
 * or the ordering wrong loses or repeats a row silently instead of failing. Reading next selects rows
 * whose key is strictly greater than the position and orders them ascending. Reading previous selects
 * rows whose key is strictly less than the position and orders them descending, then presents them
 * ascending so that both directions render in one sequence. Both comparisons are strict in the migrated
 * form because the position names a row the caller has already received, so admitting equality would
 * hand back that row a second time. The key those comparisons run over is the type code ascending,
 * which is the unique index at lines 1 to 3 of
 * {@code app/app-transaction-type-db2/ddl/XTRNTYPE.ddl} and the primary key of
 * {@code reference.transaction_types}.
 *
 * <p>Assumptions: the baseline's own two comparisons are not symmetric, and the difference is preserved
 * rather than normalised away. Its forward cursor selects on a key greater than or equal to its
 * start-key host variable at line 343 of
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} and orders ascending at line 351, while its
 * backward cursor selects on a key strictly less than that same variable at line 359 and orders
 * descending at line 367. The forward comparison admits equality because the variable it is handed
 * holds the next key to read rather than the last key read, so the baseline and the migrated form
 * select the same rows by different routes. Both are stated here so that a reader does not reconcile
 * them by altering one, which would shift the row that begins each page and make a caller walking the
 * set forward and then back see a row twice or not at all. The register of divergences for this
 * migration is {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Alternatives Considered: positioning the window by ordinal -- a page number, an offset or a count
 * of rows already consumed -- was evaluated and rejected because it changes which rows a caller sees,
 * not because of any measure of speed. An ordinal names how many rows precede the window, so when
 * another caller inserts or deletes a row ahead of it between two requests the rows before that
 * ordinal are counted again against a set that has changed: the window shifts, and a row that crossed
 * its edge is skipped or returned twice. A position naming a key can do neither, because a key keeps
 * its place in the ordering whatever is inserted or deleted around it. The concurrency is ordinary
 * rather than hypothetical here, because this reference data is maintained through the create, replace
 * and delete operations published alongside this very browse.
 *
 * <p>Assumptions: both filters are optional, and an omitted filter is the unfiltered browse rather
 * than a defaulted one. That is the screen's behaviour on first entry: each predicate in the baseline
 * cursors is gated on an edit flag whose unset state passes every row through, at lines 344 to 346 for
 * the type code and 347 to 350 for the description going forward, and at lines 360 to 362 and 363 to
 * 366 going back.
 *
 * <p>Assumptions: the type-code filter admits a value the create and replace shapes refuse, and that
 * asymmetry is deliberate. This filter takes any two digits, so {@code 00} passes it, whereas the
 * stored-code schema those other shapes are declared against excludes {@code 00}. A filter matching no
 * row is an empty page, which is a legitimate answer; a create carrying a code the seeded data cannot
 * hold is a request that should never be accepted. The width is asserted rather than left to the query
 * because the baseline predicate is an equality test against a two-character column, at line 345 going
 * forward and line 361 going back, so a value of any other width could never equal a stored key, and
 * because the published contract declares this filter with a minimum and a maximum length of two and a
 * two-digit pattern.
 *
 * <p>Assumptions: the description filter is a containment test and not a pattern the caller composes.
 * The baseline wraps the trimmed input in wildcards itself before binding it, so what a caller sends is
 * the text being searched for and a wildcard of its own carries no meaning it can rely on. The declared
 * widths of both filters are those of the browse map's own filter fields, TRTYPEI at line 66 and
 * TRDESCI at line 72 of {@code app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy}, which are the
 * authority for them.
 *
 * <p>Refactoring Rationale: several members the browse map declares do not travel, and naming them here
 * is what keeps them from being reintroduced. The map carries a selection character on every displayed
 * row -- TRTSEL1I at line 78 through TRTSEL7I at line 186 of
 * {@code app/app-transaction-type-db2/cpy-bms/COTRTLI.cpy}, with a further lettered triple whose
 * description stem is TRTDSCAI at line 216 rather than continuing the numeric series -- and none of
 * that appears here. A row array is terminal geometry: it exists because one screen displayed a whole
 * page at once and needed a way to say which line the operator marked. Marking a row is now a route and
 * an HTTP verb addressing a single type by its code, so neither the array nor any selection member has
 * anything left to carry. The map's displayed page number, PAGENOI at line 60, does not travel either,
 * because a position given by key has no ordinal to display. Nor does a key legend: this map spells its
 * legend as button fields, BUTNF02I, BUTNF03I, BUTNF07I, BUTNF08I and BUTNF10I from line 229, while the
 * maintain map spells its own as FKEYSI at line 84, FKEY04I at line 90, FKEY05I at line 96, FKEY06I at
 * line 102 and FKEY12I at line 108 of
 * {@code app/app-transaction-type-db2/cpy-bms/COTRTUP.cpy}, with no paging keys among them -- which is
 * why the maintain screen does not page at all. That asymmetry between the two maps is real, and it is
 * preserved by carrying no legend member on either shape, because a legend is presentation and belongs
 * to the browser client.
 *
 * <p>Trade-offs: the position and direction members are written out here, and again in
 * {@code TransactionCategoryListRequest} and {@code LookupPageRequest}, rather than inherited from one
 * paging supertype. A Java record cannot extend a class, so inheritance is unavailable without giving
 * up the record form that keeps these shapes immutable and puts every member under a documented
 * at-clause. Nesting the pair into a paging sub-record was the remaining alternative and was rejected
 * on a wire-visible ground: a nested object binds as a dotted or bracketed query parameter, which is a
 * different query string from the one the contract publishes, so the shape would no longer satisfy the
 * document it exists to satisfy. The accepted cost is that a change to paging is a change in each of
 * the three shapes.
 *
 * <p>Trade-offs: no member names how many rows a page holds, so a caller cannot ask for a wider one.
 * The window is bounded, and its bound is a constant the service owns. Accepting a size here and
 * leaving it unbounded would let one request materialise the whole table, which is an availability
 * exposure rather than an untidiness; accepting a bounded one would mean publishing both the parameter
 * and its ceiling, and the contract instead states that no page-size, page-number, offset or
 * total-count parameter appears anywhere in it and that none may be added. The cost accepted is that a
 * caller cannot size the window to its own display and pages more often for a larger one; what is
 * gained is a ceiling no request can argue with.
 *
 * <p>Every baseline path cited above is read as the specification for this shape and is never modified.
 *
 * @param cursor the paging position to continue from, as {@code String}: the opaque token a previous
 *     reply minted, copied from that reply's trailing boundary to advance past it or from its leading
 *     boundary to retreat from it. It is sealed, so a caller cannot construct, parse or compare it and
 *     must echo it exactly as received. Absent, together with the direction, on a first request, and
 *     meaningful only alongside a direction. The comparison it feeds is strict in both directions --
 *     greater than this position when read forward, less than it when read backward -- because the
 *     position names a row the caller already holds
 * @param direction the direction this position is read in, as {@link PageDirection}: next selects keys
 *     strictly greater than the position and orders them ascending, previous selects keys strictly less
 *     than the position and orders them descending. Absent means forward, which is the default the
 *     contract publishes and which the service applies, so this shape never carries a defaulted value
 * @param typeCode the type-code filter, as {@code String}: exactly two digits, narrowing the page to
 *     the single type whose code equals it. Optional, and absent means unfiltered; {@code 00} is
 *     admitted here even though the stored-code shapes refuse it
 * @param description the description filter, as {@code String}: text that a stored description must
 *     contain, with the wildcards supplied by the service rather than by the caller. Optional, and
 *     absent means unfiltered
 */
public record TransactionTypeListRequest(

        // Assumptions: these two bounds are the sealed-token shape itself rather than an estimate of
        //   one. The ceiling and the three-segment form are what the shared sealer produces and
        //   accepts, so a raw key or a hand-built value is refused while binding the request instead
        //   of surviving as far as the point where the position is opened.
        @Size(max = CursorToken.MAX_TOKEN_LENGTH)
        @Pattern(regexp = CursorToken.SEALED_SHAPE_PATTERN)
        String cursor,

        // Alternatives Considered: an enumeration rather than a constrained string. A string would
        //   accept the same two values, but every consumer would then compare against a literal, and a
        //   direction compared wrongly does not fail -- it pages the wrong way.
        PageDirection direction,

        // Assumptions: the width is asserted twice deliberately. The pattern is the operative check,
        //   admitting two digits and nothing else, while the length bounds restate the minimum and
        //   maximum the contract declares so that this shape reads the way the document does.
        @Size(min = TransactionTypeListRequest.TYPE_CODE_LENGTH,
                max = TransactionTypeListRequest.TYPE_CODE_LENGTH)
        @Pattern(regexp = TransactionTypeListRequest.TYPE_CODE_PATTERN)
        String typeCode,

        // Assumptions: a length bound and nothing further. No blankness test and no character rule is
        //   asserted, because the baseline applies none of its maintenance-screen character rules to a
        //   filter, and a filter matching no row is an empty page rather than a bad request.
        @Size(min = TransactionTypeListRequest.DESCRIPTION_MIN_LENGTH,
                max = TransactionTypeListRequest.DESCRIPTION_MAX_LENGTH)
        String description) {

    /**
     * The exact width of a type-code filter, two digits.
     *
     * <p>Refactoring Rationale: this constant and the three below are published so the browse route can
     * hold its own parameters to the identical bounds. It has to: a record's constraints are evaluated
     * when something VALIDATES the record, and the browse route constructs this shape by hand rather than
     * binding it -- so nothing validated it and a malformed filter reached a repository predicate, which
     * answered it with an empty page a caller could not distinguish from a legitimately empty result.
     * Restating the bounds as a second set of literals on the handler was the alternative, and those
     * could drift from these while still compiling.</p>
     */
    public static final int TYPE_CODE_LENGTH = 2;

    /** The closed domain of a type-code filter, as a regular expression: two digits and nothing else. */
    public static final String TYPE_CODE_PATTERN = "[0-9]{2}";

    /** The shortest description filter that can match anything. */
    public static final int DESCRIPTION_MIN_LENGTH = 1;

    /**
     * The declared width of the stored description this filter is matched against.
     *
     * <p>Assumptions: fifty is {@code TRAN-TYPE-DESC PIC X(50)} at {@code app/cpy/CVTRA03Y.cpy} line 6,
     * so a longer value could not be contained by any stored description.</p>
     */
    public static final int DESCRIPTION_MAX_LENGTH = 50;

}
