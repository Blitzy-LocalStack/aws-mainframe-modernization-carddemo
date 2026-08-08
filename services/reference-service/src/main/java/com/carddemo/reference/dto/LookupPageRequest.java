package com.carddemo.reference.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The query parameters shared by the three seeded address-lookup browses, bound from the query string
 * rather than from a body because each of the three is a read.
 *
 * <p>Purpose: this type is the inbound shape of the phone-area-code list operation, the state-code
 * list operation and the state-and-postal-prefix list operation -- {@code listUsPhoneAreaCodes},
 * {@code listUsStates} and {@code listUsStateZipPrefixes} in
 * {@code services/reference-service/src/main/resources/openapi/reference-api.yaml}. It carries the
 * paging position, the direction that position is read in, and the single classification filter the
 * area-code browse alone declares. It holds no rule of its own: the position is opened, the direction
 * defaulted and the filter applied by the layer that composes the query, so this is a validated
 * parameter object rather than a step in any browse. It reaches no store and names no collaborator.</p>
 *
 * <p>Parameters, return values and errors at the type level: the three record components are this
 * type's parameters and each carries its own at-clause below. A type declaration returns no value and
 * raises nothing, so no return or exception at-clause appears here, and the canonical accessors the
 * compiler derives each return the component of the same name exactly as that at-clause describes it,
 * without defaulting or normalising anything. No accessor is written out, precisely so that none can
 * acquire logic the at-clauses would then understate. The inapplicability is declared rather than left
 * silent, because a reader must be able to tell it from an oversight.</p>
 *
 * <p>Alternatives Considered: one shared shape serves all three browses, and the rejected alternative
 * was a dedicated request record for each. The three page over a single-column character key and
 * declare the same two paging parameters, so the position member and the direction member would be
 * identical in all three -- the same type, the same constraints and the same meaning -- and only one of
 * the three declares anything beyond them. Three records would therefore restate one pair three times
 * to express a difference of a single optional filter, and three shapes that must stay identical are
 * three places for them to stop being identical. A filter absent from a request is indistinguishable
 * from a filter the operation never declared, so the two browses that declare no filter lose nothing by
 * carrying a member they leave absent, and the contract's own query strings stay exactly as
 * published.</p>
 *
 * <p>Assumptions: the boundary of that sharing is single-column key plus at most the one filter this
 * shape already carries, and stating it is what stops this type from being taken for a paging shape
 * that any browse may reuse. The two transaction-reference browses in this same package keep their own
 * request types, {@code TransactionTypeListRequest} and {@code TransactionCategoryListRequest},
 * because each declares two filters of its own -- an exact type-code equality and a description
 * containment -- over data that is written through the create, replace and delete operations published
 * beside them. Folding those two into this shape would put four members on a type whose two smallest
 * consumers need none of them, and would leave a caller unable to tell which filters the operation it
 * is calling actually honours. A later reader who sees three near-identical paging requests and
 * collapses all three loses exactly that distinction, which is why the line is drawn here in writing
 * rather than left to be inferred from the members.</p>
 *
 * <p>Trade-offs: the paging members are written out in this shape and again in each of the two
 * transaction-reference list shapes rather than inherited from one paging parent, and inheritance was
 * never available in any case. A Java record cannot extend a class, so a common supertype would mean
 * abandoning the record form that keeps these shapes immutable and puts every member under a
 * documented at-clause. Nesting the pair into a paging sub-record was the remaining alternative and is
 * rejected on a wire-visible ground: a nested object binds as a dotted or bracketed query parameter,
 * which is a different query string from the one the contract publishes, so the shape would no longer
 * satisfy the document it exists to satisfy. Sharing therefore had to be reuse of one whole record
 * across the browses whose shape is identical, which is what this type is, and the accepted cost is
 * that a change to paging is a change in each list shape.</p>
 *
 * <p>Trade-offs: sharing one shape across three keys of different widths means the position member
 * carries no per-browse length constraint. The keys are three characters for an area code, two for a
 * state code and four for a state-and-postal-prefix pair, as {@code db/migration/V1__reference.sql}
 * declares them, so a dedicated record for each browse could have constrained its own key exactly
 * while a shared one cannot. What makes the looser constraint acceptable rather than merely convenient
 * is that this member never carries a key at all. It carries a sealed continuation token this service
 * minted and a caller echoes back, so its admissible form is the token's form and not any key's, and
 * the constraints below close that form to a declared ceiling and a three-segment shape. The token is
 * additionally sealed to the collection it was issued for, so a token minted by the state browse is
 * refused by the area-code browse even though both accept the same shape -- which is the check a
 * per-browse length constraint would have been a weak proxy for. The residual cost is that a
 * malformed-but-well-shaped value is refused where the position is opened rather than where the
 * request is bound.</p>
 *
 * <p>Assumptions: the position and the direction live on the request because
 * {@code com.carddemo.common.web.PageResponse} is exactly four members -- the rows, a leading boundary
 * token, a trailing boundary token and a further-page indicator. That envelope declares no
 * backward-availability member and no window-size member, and it says where the page in hand ended
 * rather than where the caller intends to go next; a stateless handler retains nothing between two
 * requests that could supply the difference. So the caller returns one of the envelope's two tokens as
 * the position and states which of them it copied by sending the direction. That pair expresses
 * precisely what a separate forward-position and backward-position member would express, and one
 * position is preferred over two because the envelope's two tokens are indistinguishable in shape: a
 * pair would let a caller populate both at once and leave the service arbitrating a request that means
 * two things at once. Ambiguity is therefore designed out of the shape rather than resolved after the
 * fact, which is why no mutual-exclusivity constraint appears below -- there is only ever one position
 * to be exclusive about.</p>
 *
 * <p>Assumptions: the two query forms this position feeds are exact, and getting either the comparison
 * or the ordering wrong loses or repeats a row silently instead of failing. Reading next selects rows
 * whose key is strictly greater than the position and orders them ascending. Reading previous selects
 * rows whose key is strictly less than the position and orders them descending, then presents them
 * ascending so that both directions render in one sequence. Both comparisons are strict in the migrated
 * form because the position names a row the caller has already received, so admitting equality would
 * hand that row back a second time. The key each comparison runs over is the primary key of the table
 * being browsed, taken ascending: {@code area_cd}, {@code state_cd} and {@code state_zip_cd}
 * respectively. This is the migrated form of the two browse verbs the reference online programs pair --
 * a forward read and a backward read around a single positioning field -- and the register of
 * divergences for this migration is
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Assumptions: the baseline's own two comparisons are not symmetric, and the difference is recorded
 * rather than normalised away. Its forward cursor selects on a key greater than or equal to its
 * start-key host variable at line 343 of
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} and orders ascending at line 351, while its
 * backward cursor selects on a key strictly less than that same variable at line 359 and orders
 * descending at line 367. The forward comparison admits equality because the variable it is handed
 * holds the next key to read rather than the last key read, so the baseline and the migrated form
 * select the same rows by different routes. Both are stated here so that a reader does not reconcile
 * them by altering one, which would shift the row that begins each page and make a caller walking a set
 * forward and then back see a row twice or not at all.</p>
 *
 * <p>Alternatives Considered: positioning the window by ordinal -- a page number, an offset or a count
 * of rows already consumed -- was evaluated and rejected because it changes which rows a caller sees,
 * and not on any measure of speed. An ordinal names how many rows precede the window, so when a row
 * ahead of that window is inserted or deleted between two requests the rows before the ordinal are
 * counted again against a set that has changed: the window shifts, and a row that crossed its edge is
 * skipped or returned twice. A position naming a key can do neither, because a key keeps its place in
 * the ordering whatever is inserted or deleted around it. The contract closes the same question for
 * every operation it publishes, recording that no page-number, page-size, offset or total-pages
 * parameter appears anywhere in it and that none may be added.</p>
 *
 * <p>Assumptions: no description filter is declared on any of these three browses because there is no
 * descriptive column on any of the three tables to match one against.
 * {@code db/migration/V1__reference.sql} gives {@code reference.us_phone_area_codes} a
 * {@code CHAR(3)} primary key beside a single-character classification, {@code reference.us_states} a
 * {@code CHAR(2)} primary key and nothing else, and {@code reference.us_state_zip_prefixes} a
 * {@code CHAR(4)} primary key and nothing else. The rows are seeded from condition-name lists in
 * {@code app/cpy/CSLKPCDY.cpy} -- the area-code field at L24 with its list at L30, the state field at
 * L1012 with its list at L1013, and the four-character field at L1072, subordinate to the group at
 * L1071, with its list at L1073 -- and a condition-name list carries literals and no prose. A
 * description filter would therefore have nothing to match, which is why its absence here is a
 * property of the data rather than an omission for a later revision to supply.</p>
 *
 * <p>Alternatives Considered: the classification filter is carried, and the alternative of declining it
 * to keep this shape purely positional was evaluated and rejected because the contract declares it. The
 * document publishes it as {@code codeClass} on the area-code browse alone, typed through the
 * {@code PhoneAreaCodeClass} schema, and where this type and that document disagree the document wins.
 * The filter is meaningful in the first place because the classification is the only distinction the
 * baseline draws between accepted area codes: {@code app/cpy/CSLKPCDY.cpy} overlays two narrower
 * condition names on the same three-character field, the general-purpose list at L521 and the
 * easily-recognisable list at L931, and those two partition the broad list at L30 exactly -- they share
 * no literal and their union is equal to it. A total and disjoint partition is what makes a single
 * filter member sufficient and correct, where a pair of independent flags would additionally admit a
 * both-set and a neither-set request that the copybook cannot express.</p>
 *
 * <p>Alternatives Considered: this filter is typed as {@code String} while the outbound
 * {@code PhoneAreaCodeResponse} types the same classification as its nested {@code CodeClass}
 * enumeration, and the asymmetry is deliberate rather than an inconsistency. The outbound member is the
 * stored classification of a row that always has exactly one, so an enumeration is exactly right there
 * and has a wire spelling to serialise. This member is an optional filter whose absence is the
 * unfiltered browse and whose value is handed on to a query over a single-character column, so typing
 * it as the enumeration would parse a value on the way in only to render it back to the same character
 * on the way out. The pattern constraint below closes the identical two-value domain at the point of
 * binding, which is the same guarantee reached without the round trip.</p>
 *
 * <p>Trade-offs: no member names how many rows a page holds, so a caller cannot ask for a wider one.
 * The window is bounded and its bound is a constant the answering layer owns, published as
 * {@code AddressLookupController.PAGE_SIZE}. Accepting a size here and leaving it unbounded would let
 * one request materialise an entire lookup table, which is an availability exposure rather than an
 * untidiness; accepting a bounded one would mean publishing both the parameter and its ceiling, and the
 * contract states instead that no such parameter appears anywhere in it. The cost accepted is that a
 * caller cannot size the window to its own display and pages more often for a larger one; what is
 * gained is a ceiling no request can argue with.</p>
 *
 * <p>Assumptions: only the three list operations bind this shape. Each of the three tables also
 * publishes a single-key read -- addressed by area code, by state code and by the four-character
 * prefix -- and those three declare no paging parameter at all, because addressing one row by its key
 * is a primary-key probe rather than a window over an ordered set. This shape must not be wired into
 * any of them: a probe that accepted a position and a direction would publish two parameters it has
 * nothing to do with.</p>
 *
 * <p>Refactoring Rationale: paging is what this shape adds, and it replaces no browse, because the
 * baseline never browses these lists. It compiles them into the program as condition names and tests
 * membership against them one value at a time: {@code app/cbl/COACTUPC.cbl} is the only program in the
 * baseline that copies this book at all, and it tests the area-code classification at its line 2298,
 * the state code at line 2495 and the state-and-postal-prefix pair at line 2542. What was limiting in
 * that arrangement is not the tests but that the lists were reachable only as a compiled-in literal
 * set, so nothing outside that program could read one and the migrated address validation, which runs
 * in a separate bounded context, would have had no way to obtain it. Publishing the lists as ordered
 * collections is what makes them readable across a service boundary; bounding each read by key is what
 * keeps that new reach from turning a membership test into a whole-table transfer. The paging
 * discipline itself is carried from the browses the baseline does have, rather than invented here.</p>
 *
 * <p>Every baseline path cited above is read as the specification for this shape and is never
 * modified.</p>
 *
 * @param cursor the paging position to continue from, as {@code String}: the opaque token a previous
 *     reply minted, copied from that reply's trailing boundary to advance past it or from its leading
 *     boundary to retreat from it. It is sealed to the collection, the filter and the direction it was
 *     issued for, so a caller can neither construct, parse nor compare it and must echo it exactly as
 *     received. The comparison it feeds is strict in both directions -- keys strictly greater than this
 *     position when read forward, strictly less than it when read backward -- because the position
 *     names a row the caller already holds. Absent, together with the direction, on a first request,
 *     and meaningful only alongside a direction
 * @param direction the direction this position is read in, as {@link PageDirection}: next selects keys
 *     strictly greater than the position and orders them ascending, previous selects keys strictly less
 *     than the position and orders them descending before presenting them ascending. Absent means
 *     forward, which is the default the contract publishes and which the answering layer applies, so
 *     this shape never carries a defaulted value
 * @param codeClass the phone-area-code classification filter, as {@code String}: one of the two
 *     single-character values that partition the seeded area codes, narrowing the page to the
 *     general-purpose list or to the easily-recognisable list. Declared by the area-code browse alone
 *     and absent on the state and state-and-postal-prefix browses, which pass no value for it.
 *     Optional even where it is declared, and absent means the whole seeded list, which is the
 *     membership test the consuming context actually performs
 */
public record LookupPageRequest(

        // Assumptions: these two bounds are the sealed-token shape itself rather than an estimate of
        //   one. The ceiling and the three-segment form are what the shared sealer produces and
        //   accepts, so a raw key or a hand-built value is refused while the request is being bound
        //   instead of surviving as far as the point where the position is opened. Both constraints
        //   pass an absent value, which is what keeps a first request -- one that carries no position
        //   at all -- a valid request rather than a rejected one.
        @Size(max = 256)
        @Pattern(regexp = "v1\\.[A-Za-z0-9_-]{1,200}\\.[A-Za-z0-9_-]{43}")
        String cursor,

        // Alternatives Considered: an enumeration rather than a constrained string. A string would
        //   accept the same two values, but every consumer would then compare against a literal, and a
        //   direction compared wrongly does not fail -- it pages the wrong way. The enumeration is
        //   shared with the two transaction-reference list shapes rather than nested here, because one
        //   direction domain serves every browse in this context.
        PageDirection direction,

        // Assumptions: the pattern closes the domain to the two values the partition admits, and it is
        //   the whole of the check because the value is a coded classification rather than text: it has
        //   no width to bound beyond the one character the pattern already matches, and no third value
        //   exists for the seed to hold. A value outside the domain is refused as a bad request rather
        //   than passed to a query that would answer it with an empty page, which would report an
        //   unrecognised classification as a legitimately empty result.
        @Pattern(regexp = "[GE]")
        String codeClass) {
}
