package com.carddemo.reference.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The query parameters of the transaction-category browse, bound from the query string rather than
 * from a body because the operation is a read.
 *
 * <p>Purpose: this type is the inbound shape of the transaction-category list operation. It carries
 * the paging position, the direction that position is read in, and the type-code and description
 * filters the browse declares -- and nothing else. It holds no rules of its own: the position is
 * opened, the direction defaulted and the filters applied by the service layer, so this is a
 * validated parameter object rather than a step in the browse. It reaches no store and names no
 * collaborator.
 *
 * <p>Parameters, return values and errors at the type level: the record components declared below
 * are this type's parameters and each carries its own at-clause. A type declaration returns no value
 * and raises nothing, so no return or exception at-clause appears here, and the canonical accessors
 * the compiler derives each return the component of the same name exactly as that at-clause
 * describes it, without defaulting, padding or normalising anything. No accessor is written out,
 * precisely so that none can acquire logic the at-clauses would then understate. The inapplicability
 * is declared rather than left silent, because a reader must be able to tell it from an oversight.
 *
 * <p>Assumptions: the category code this browse orders and positions by is a character value of four
 * digits and is never held as an integer, and it is the constraint on which the correctness of every
 * page here rests. Its width and character form are carried by
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} line 3, which declares
 * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL}; by
 * {@code app/app-transaction-type-db2/dcl/DCLTRCAT.dcl} lines 42 to 43, which generate the host
 * variable the replaced programs read and write through as
 * {@code DCL-TRC-TYPE-CATEGORY PIC X(4)}; and by the column {@code cat_cd CHAR(4)} of
 * {@code db/migration/V1__reference.sql}. The seed extract {@code app/data/ASCII/trancatg.txt} stores
 * those codes zero-padded and keys each row by positional concatenation, so its leading key is
 * literally {@code 010001} -- the type {@code 01} followed by the category {@code 0001}. An integer
 * would drop the zeros that key depends on, turning {@code 0001} into {@code 1}, and the consequence
 * is specific rather than cosmetic: the position would no longer compare equal to any stored key, so
 * the row it names could not be located, and the page taken from it would either begin at the wrong
 * row or come back empty while every request still reported success. One baseline source disagrees
 * about the type and is not followed here -- {@code app/cpy/CVTRA04Y.cpy} line 7 declares
 * {@code TRAN-CAT-CD PIC 9(04)}, a numeric picture -- because the Db2 objects the replaced programs
 * actually operate through carry the character form, and that divergence is registered as
 * {@code D-CATEGORY-CODE-TEXT} in {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Assumptions: the comparison this position feeds is row-wise over the pair of key columns, and a
 * column-at-a-time comparison is not an equivalent formulation of it. Reading next selects rows whose
 * {@code (type_cd, cat_cd)} pair is strictly greater than the pair the position stands for, ordered
 * by {@code type_cd} ascending and then {@code cat_cd} ascending; reading previous selects rows whose
 * pair is strictly less than it, ordered descending on both columns, and presents them ascending so
 * that both directions render in one sequence. Comparing each column independently -- a
 * {@code type_cd} at or beyond the position's type code combined with a {@code cat_cd} beyond its
 * category code -- reads like the same predicate and is not: a row that begins a new type carries the
 * lowest category code under that type, {@code 0001} in the seeded data, so it satisfies the column
 * test on the type and fails the one on the category, and it is dropped. The rows lost that way are
 * exactly the rows at each type-code boundary, which is why the mistake is worth naming here: it
 * surfaces as a page quietly missing rows rather than as an error, and the caller has no way to tell
 * that anything was omitted. The ordering the two comparisons run over is the composite key itself,
 * declared {@code (type_cd, cat_cd)} in that column order by {@code pk_transaction_categories} in
 * {@code db/migration/V1__reference.sql} and declared in the same sequence, both columns ascending,
 * by {@code app/app-transaction-type-db2/ddl/XTRNTYCAT.ddl} line 3. Both comparisons are strict in
 * the migrated form because the position names a row the caller has already received, so admitting
 * equality would hand back that row a second time.
 *
 * <p>Assumptions: the position and the direction live on the request because
 * {@code com.carddemo.common.web.PageResponse} is exactly its rows, a leading boundary token, a
 * trailing boundary token and a further-page indicator. That reply says where the page ended; it
 * carries no indicator of the backward availability and no page width, and it does not record where
 * the caller intends to go next. A stateless handler retains nothing between requests that could, so
 * the caller returns one of those boundary tokens as the position and states which of them it copied
 * by sending the direction. That pair expresses what a separate forward-key and backward-key member
 * would express, and one position is preferred over two members because the reply's tokens are
 * indistinguishable in shape: a pair would let a caller populate both at once and leave the service
 * arbitrating a request that means two things.
 *
 * <p>Alternatives Considered: positioning the window by ordinal -- a page number, an offset or a
 * count of rows already consumed -- was evaluated and rejected because it changes which rows a caller
 * sees, and not on any measure of speed. An ordinal names how many rows precede the window, so when
 * another caller inserts or deletes a row ahead of it between two requests the rows before that
 * ordinal are counted again against a set that has changed: the window shifts, and a row that crossed
 * its edge is skipped or returned twice. A position naming a key can do neither, because a key keeps
 * its place in the ordering whatever is inserted or deleted around it. The concurrency is ordinary
 * rather than hypothetical here, because this reference data is maintained through the create,
 * replace and delete operations published alongside this very browse. The contract this shape
 * satisfies settles the same point for the whole document, declaring that no page number, page size,
 * offset or total-pages parameter appears anywhere in it and that none may be added.
 *
 * <p>Assumptions: both filters are optional and are bounded in length only, which is deliberately
 * unlike {@code TransactionCategoryCreateRequest}. There each half of the composite key is mandatory
 * and of an exact width, because a create supplies a whole key that must be storable; here a filter
 * that is absent is the unfiltered browse rather than a bad request, so neither filter asserts
 * presence and no blankness test is applied to either. The length bounds still appear, and they
 * narrow nothing that matters: a bound on a component is evaluated only when a value is present, so
 * stating one leaves absence entirely legal. A filter matching no row is an empty page, which is a
 * legitimate answer, whereas a create carrying an unstorable key is a request that should never be
 * accepted -- which is why the two shapes are asymmetric on the same pair of codes.
 *
 * <p>Assumptions: the type-code filter is a filter and not a parent selector, so it is not mandatory
 * even though every category belongs to exactly one type. The whole set may legitimately be browsed
 * across types, which is what an absent filter asks for, and that is the baseline's own behaviour on
 * first entry: its predicate is gated on an edit flag whose unset state passes every row through, and
 * it collapses low values, spaces and zeros into that one filter-blank state, so {@code 00} selects
 * everything exactly as an omitted value does. That is why this filter admits {@code 00} while the
 * create and replace shapes refuse it: the stored-code domain excludes it, and a filter domain need
 * not.
 *
 * <p>Assumptions: no category-code filter is declared, because the contract declares none for this
 * operation -- it publishes the category code as a path segment and as a member of the create body,
 * never as a query parameter of the browse -- and inventing one here would put a parameter on the
 * wire that no published document admits.
 *
 * <p>Trade-offs: the position and direction members are written out here, and again in
 * {@code TransactionTypeListRequest} and {@code LookupPageRequest}, rather than inherited from one
 * paging supertype. A Java record cannot extend a class, so inheritance is unavailable without giving
 * up the record form that keeps these shapes immutable and puts every member under a documented
 * at-clause. Nesting the pair into a paging sub-record was the remaining alternative and was rejected
 * on a wire-visible ground: a nested object binds as a dotted or bracketed query parameter, which is
 * a different query string from the one the contract publishes, so the shape would no longer satisfy
 * the document it exists to satisfy. One further reason applies to this shape and not to its
 * siblings, and it makes the duplication substantive rather than merely mechanical: even with
 * inheritance available, this browse could not have shared a position definition with them, because
 * the key it pages over is the composite pair while theirs is a single column, so the value each
 * position stands for is a different thing. The accepted cost is that a change to paging is a change
 * in each of the three shapes.
 *
 * <p>Trade-offs: no member names how many rows a page holds, so a caller cannot ask for a wider one.
 * The window is bounded, and its bound is a constant the service owns rather than a parameter a
 * request supplies. Accepting a width here and leaving it unbounded would let a single request
 * materialise every row of {@code reference.transaction_categories}, which is an availability
 * exposure and not an untidiness; accepting a bounded one would mean publishing both the parameter
 * and its ceiling, and the contract instead admits no such parameter anywhere. The cost accepted is
 * that a caller cannot size the window to its own display and pages more often for a larger one;
 * what is gained is a ceiling no request can argue with.
 *
 * <p>Every baseline path cited above is read as the specification for this shape and is never
 * modified.
 *
 * @param cursor the paging position to continue from, as {@code String}: the opaque token a previous
 *     reply minted, copied from that reply's trailing boundary member to advance past it or from its
 *     leading boundary member to retreat from it, so the member it was copied from is the half of the
 *     page it names. It stands for a whole {@code (type_cd, cat_cd)} pair and not for either column
 *     alone, and it is sealed, so a caller cannot construct, parse or compare it and must echo it
 *     exactly as received. Absent, together with the direction, on a first request, and meaningful
 *     only alongside a direction. The comparison it feeds is row-wise over that pair and strict in
 *     both directions, because the position names a row the caller already holds
 * @param direction the direction this position is read in, as {@link PageDirection}: next selects
 *     pairs strictly greater than the position and orders them by type code and then category code
 *     ascending, and so pairs with the trailing boundary member; previous selects pairs strictly less
 *     than it and orders them descending on both columns, and so pairs with the leading boundary
 *     member. Absent means forward, which is the default the contract publishes and which the service
 *     applies, so this shape never carries a defaulted value
 * @param typeCode the type-code filter, as {@code String}: two digits, narrowing the page to the
 *     categories of the single type whose code equals it. Optional, and absent means unfiltered;
 *     {@code 00} is admitted here, and selects everything, even though the stored-code shapes refuse
 *     it
 * @param description the description filter, as {@code String}: text that a stored description must
 *     contain, with the wildcards supplied by the service rather than by the caller. Optional, and
 *     absent means unfiltered
 */
public record TransactionCategoryListRequest(

        // Assumptions: these two bounds are the sealed-token shape itself rather than an estimate of
        //   one. The ceiling and the three-segment form are what the shared sealer produces and
        //   accepts, so a raw pair of codes or a hand-built value is refused while the request is
        //   being bound instead of surviving as far as the point where the position is opened. The
        //   token is sealed to this collection, so one minted by the transaction-type browse is
        //   refused here rather than answered with a page from the wrong set.
        @Size(max = 256)
        @Pattern(regexp = "v1\\.[A-Za-z0-9_-]{1,200}\\.[A-Za-z0-9_-]{43}")
        String cursor,

        // Alternatives Considered: an enumeration rather than a constrained string. A string would
        //   accept the same two values, but every consumer would then compare against a literal, and
        //   a direction compared wrongly does not fail -- it pages the wrong way. The type is shared
        //   with the sibling browses because the direction vocabulary is one domain; the key each of
        //   them pages over is not, which is why only this member is shared and the position is not.
        PageDirection direction,

        // Assumptions: the width is asserted twice deliberately. The pattern is the operative check,
        //   admitting two digits and nothing else, while the length bounds restate the minimum and
        //   maximum the contract declares so that this shape reads the way the document does. Neither
        //   asserts presence, so an omitted filter stays legal and browses across every type.
        @Size(min = 2, max = 2)
        @Pattern(regexp = "[0-9]{2}")
        String typeCode,

        // Assumptions: a length bound and nothing further. No blankness test and no character rule is
        //   asserted, because the baseline applies none of its maintenance-screen character rules to a
        //   filter, and a filter matching no row is an empty page rather than a bad request. The upper
        //   bound is the declared width of the description this filter is matched against,
        //   TRAN-CAT-TYPE-DESC PIC X(50) at app/cpy/CVTRA04Y.cpy line 8, so a longer value could not
        //   be contained by any stored description.
        @Size(min = 1, max = 50)
        String description) {
}
