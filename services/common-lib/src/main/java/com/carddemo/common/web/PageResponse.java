package com.carddemo.common.web;

import java.util.List;

/**
 * Carries one page of an ordered result set together with the two cursor tokens and the
 * further-page indicator that a caller needs in order to walk that set by key.
 *
 * <p><b>Purpose.</b> A stateless request handler cannot remember where a caller had reached in a
 * list, so something in the response has to say it. This type is that something, and it says
 * exactly four things: the rows of the current page, a token identifying the first of those rows, a
 * token identifying the last of them, and whether a further page follows. A caller holding those
 * four values can ask for the page after this one, or the page before it, while the server retains
 * nothing whatsoever between the two requests.
 *
 * <p><b>Parameters, return values, exceptions or errors at the type level.</b> The four record
 * components are this type's parameters, and each carries its own at-clause below alongside the
 * at-clause for the element type. A type declaration returns no value and raises nothing, so no
 * return or exception at-clause appears here; the members declared in the body carry their own. The
 * inapplicability is stated rather than left silent, because the Explainability rule lists a
 * docstring that omits parameters or return values among its forbidden patterns at line 39, and a
 * reader must be able to tell a declared inapplicability from an oversight.
 *
 * <p><b>Lineage: four reference-only programs, of which exactly two browse.</b> Every program cited
 * below is reference-only. It is cited by path and line, it is never modified, and where the
 * migrated behaviour differs the framing is always that the baseline does one thing, this code
 * implements another, and the divergence is documented. There are <b>two true browse screens</b>,
 * {@code app/cbl/COCRDLIC.cbl} and {@code app/cbl/COTRN00C.cbl}, and a third program that is
 * routinely taken for one and is not.
 *
 * <p>Refactoring Rationale: this type replaces the four-verb browse and the communication-area
 * cursor that carried its state between screen turns. In {@code app/cbl/COCRDLIC.cbl} that browse
 * runs start at line 1129, read forward at line 1146, the lookahead read at line 1197 and end at
 * line 1258, then start backward at line 1273 with a priming read at line 1294, the backward loop
 * at line 1322 and end at line 1376. In {@code app/cbl/COTRN00C.cbl} each verb is its own
 * paragraph: start at line 593, read forward at line 626, read backward at line 660, end at line
 * 694. What was wrong with that arrangement is not the verbs but where the state lived. It lived in
 * a structure the client echoed back, declared from line 229 of {@code app/cbl/COCRDLIC.cbl}, so
 * continuity across a turn depended on the client returning it intact. Moving the same four values
 * into a response body ends that dependency: the server reads them back off the next request and
 * verifies them, rather than trusting a round-tripped buffer. The substitution is one-to-one rather
 * than an approximation, and that is the decisive finding of the analysis behind this type: the
 * baseline cursor was already a cursor over keys. Lines 230 to 232 hold the last key of the page and
 * lines 233 to 235 hold the first key of it, both as real key values; line 242, with its two
 * condition names at lines 243 and 244, holds nothing but whether a further page exists. There is no
 * count of rows already consumed anywhere in that structure, so nothing had to be invented here and
 * nothing had to be discarded.
 *
 * <p>Refactoring Rationale: this type is declared once in the shared kernel rather than once per
 * service, and the alternative deserves an answer rather than a dismissal, because a per-service
 * declaration removes a module from the build and lets one team change an envelope without
 * consulting seven others. The reference baseline settles it. {@code tests/README.md} line 268
 * records the compile invocation whose trailing {@code -I app/cpy} is a single include path over one
 * directory of record layouts, and lines 540 to 542 of the same file state the discipline that
 * buys: "COBOL unit tests resolve record layouts through the compiler copybook path
 * ({@code cobc -I app/cpy}) via {@code COPY CVTRA06Y.} / {@code COPY CVACT01Y.} -- never duplicate
 * a layout; keep it single-sourced from {@code app/cpy/}." The analogy is exact, one include path
 * over the copybook directory standing to one {@code COPY} statement as one dependency on the
 * shared kernel stands to one import of this type. What is wrong with the per-service alternative is
 * what is wrong with eight copies of a record layout, and it is not a matter of taste: the two true
 * browse screens already demonstrate the failure. They hand-rolled the same paging independently and
 * arrived at different conventions, including an outright disagreement about what the last key
 * means, which the next paragraph but two records. Neither such disagreement is a compilation
 * error. Both yield pages that look right and quietly lose or repeat a row at the boundary.
 *
 * <p>Alternatives Considered: positioning a page by counting rows from the start of the ordered set,
 * which is the obvious alternative to positioning it by key, was evaluated and rejected. The reason
 * is a specific defect and not a general preference. Under concurrent insertion the number of rows
 * preceding the cursor changes underneath a reader between one turn and the next, so a page
 * positioned by counting from the start omits some rows altogether and presents others twice, while
 * a page positioned by key does not, because a key already read keeps its place in the ordering no
 * matter what is inserted around it. That concurrency is not hypothetical: it is attested by the
 * baseline itself, at lines 212 to 217 of {@code app/cbl/COBIL00C.cbl}. High values are moved into
 * the transaction identifier at line 212, a browse is started at line 213, one backward read at line
 * 214 lands on the highest existing key, the browse ends at line 215, and lines 216 and 217 move
 * that key into a working field and add one to it. Nothing holds a lock across those six lines, so
 * two units of work can occupy the sequence at once and inserts therefore land in the middle of the
 * key space while other readers are part-way through it. The rule's line 41 forbids a vague
 * rationale, so the mechanism is named: rows lost and rows repeated at a page boundary, caused by
 * the row count ahead of the cursor moving, evidenced in a cited program.
 *
 * <p>Alternatives Considered: those same lines are also why {@code app/cbl/COBIL00C.cbl} is cited
 * here as a <b>counter-example</b> and never as a paging source. It does not browse. Its start at
 * line 441 carries no greater-or-equal option at all, its end-of-file path at line 488 moves zeros
 * into the identifier as an empty sentinel, its browse closes at lines 501 to 505, and decisively,
 * in all 572 lines of that program there is no forward-read paragraph whatsoever. What lines 210 to
 * 219 perform is a highest-key lookup followed by an increment, whose relational equivalent is a
 * maximum over the key column rather than a page. This envelope must therefore never be wired into
 * the bill-payment path, and the count that matters is two browse screens rather than three.
 *
 * <p>Alternatives Considered: a second type parameter for the key was possible and was rejected in
 * favour of one type parameter over the element and opaque textual cursor tokens. The two true
 * browse screens do not share a key shape. The cursor of {@code app/cbl/COCRDLIC.cbl} is a
 * composite of twenty-seven characters, a card number of sixteen characters followed by an account
 * identifier of eleven digits, at lines 230 to 232. The cursor of {@code app/cbl/COTRN00C.cbl} is a
 * single scalar transaction identifier, carried in the record identification field at line 595 with
 * its key length at line 596. A type parameterised over the key would be instantiated at two
 * unrelated shapes and would push the composing and splitting of the composite into every caller,
 * whereas an opaque token carries either shape without the type changing at all. What that costs, in
 * checking the compiler can no longer do, is stated plainly further down.
 *
 * <p>Alternatives Considered: {@code lastKey} identifies the last row actually returned in
 * {@code items}, and this had to be chosen rather than inherited, because the two browse screens
 * disagree. {@code app/cbl/COCRDLIC.cbl} overwrites its stored last key with the probe row's key at
 * lines 1207 to 1214, so the value it retains identifies the eighth record on a page of seven, a row
 * the terminal never displayed. {@code app/cbl/COTRN00C.cbl} discards the probe row entirely at
 * lines 305 to 313 and keeps only the boolean. The first behaviour was rejected: a token that
 * identifies a row the caller never received cannot be verified by that caller, and a backward
 * request built from it would seek from a position the caller cannot account for. Symmetrically
 * {@code firstKey} identifies the first row actually returned. Both tokens therefore always name
 * rows the caller holds.
 *
 * <p>Assumptions: the cursor ordering is the card number followed by the account identifier, per
 * lines 230 to 232 of {@code app/cbl/COCRDLIC.cbl}, which is the physical key order of the card
 * file. It is emphatically <b>not</b> the display-row ordering at lines 258 to 260 of the same
 * program, which is the account number, then the card number, then the card status, in a reading
 * order meant for a person. The two layouts differ in length as well as in order, twenty-seven
 * characters against twenty-eight, and the twenty-eighth character is the <b>card status</b> at line
 * 260. It is not a row-selection marker, and reading it as one would put a status character into a
 * cursor. Conflating the two orders would produce a query that pages in the wrong sequence while
 * still returning plausible-looking rows, which is why the distinction is recorded here rather than
 * left to be rediscovered.
 *
 * <p>Assumptions: {@code hasNext} is discovered by requesting one row beyond the page and observing
 * whether that row exists, and never from any running tally of how many rows or how many pages there
 * are altogether. Two independent programs attest to it, which is why it is treated as a contract
 * rather than as one program's habit. In {@code app/cbl/COCRDLIC.cbl} the fill loop exits when its
 * counter reaches the seven declared at lines 177 and 178, at line 1191, captures the last keys at
 * lines 1194 and 1195, and then issues one extra read at line 1197 purely as a probe; a normal or
 * duplicate response at lines 1207 to 1214 sets the indicator true, and an end-of-file response at
 * lines 1215 to 1221 sets it false. In {@code app/cbl/COTRN00C.cbl} the same probe runs at line 308,
 * setting the indicator at line 310 or line 312. A caller building this envelope therefore asks its
 * store for one more row than it intends to return, puts the surplus row nowhere, and reports its
 * presence here.
 *
 * <p>Assumptions: both tokens are required because a single one cannot express both directions, and
 * the two directions are the two read verbs of the baseline. Reading forward is the analogue of the
 * forward read at lines 1146 and 1197 of {@code app/cbl/COCRDLIC.cbl} and at line 626 of
 * {@code app/cbl/COTRN00C.cbl}: query for keys strictly greater than {@code lastKey}, ordered
 * ascending, limited to one more row than the page is to hold. Reading backward is the analogue of
 * the backward read at lines 1294 and 1322 of {@code app/cbl/COCRDLIC.cbl} and at line 660 of
 * {@code app/cbl/COTRN00C.cbl}: query for keys strictly less than {@code firstKey}, ordered
 * descending, which is precisely what a backward read does. The baseline makes the seek point
 * explicit at line 1268, where the first key of the current page is moved into the field the
 * backward browse is positioned from. Downstream, the two directions bind to the two paging function
 * keys, backward to the seventh and forward to the eighth, each enabled from the corresponding
 * availability reported here.
 *
 * <p>Assumptions: both tokens are nullable, and absent is spelled exactly one way, as {@code null}.
 * The baseline spells absence three ways, and collapsing them is deliberate. Low values mark the
 * no-further-page state at line 243 of {@code app/cbl/COCRDLIC.cbl} and clear the display array at
 * lines 1124 and 1266. High values mark the seek-from-the-top position at line 212 of
 * {@code app/cbl/COBIL00C.cbl}. Zeros mark exhaustion at lines 487 and 488 of that same program.
 * Three encodings of absence in one baseline is three chances for a caller to test for the wrong
 * one, so this type recognises one. A token that is absent, empty or entirely blank means the same
 * thing here and is normalised to {@code null} by the constructor. A token that is present is passed
 * through unaltered, because it is opaque: it may legitimately carry the blank padding of a
 * declared-width key field, and trimming its interior would be an interpretation this type has no
 * standing to make.
 *
 * <p>Assumptions: a page may carry rows whose values include money, and this type never sees one.
 * Money is exact decimal at every hop, it is transported as a JSON string, and it is never a JSON
 * number and never a binary approximation of a decimal, so that no client can route it through an
 * inexact numeric type at the boundary. The serialisation that guarantees it is
 * {@code MoneyModule}, which belongs to {@code com.carddemo.common.money} and is registered by the
 * consuming service rather than by anything shipped from this package. The prohibition is enforced
 * by an architecture test and not by this Javadoc. What this type contributes is restraint: it
 * carries whatever rows a caller puts into it and never interprets one of their values, so it
 * imports nothing from the money package and adds no numeric type of its own.
 *
 * <p>Assumptions: no executable oracle exists for this type, and no claim to the contrary should be
 * read into the lineage above. The three-layer functional-parity suite rooted at {@code tests} is an
 * oracle for the batch programs and exercises no browse screen at all, because the online programs
 * need a transaction monitor its runner does not have. This type is therefore verified entirely by
 * unit tests under {@code services/common-lib/src/test/java/com/carddemo/common/web}, and the
 * programs cited throughout are the specification those tests were written against rather than a
 * harness they run against. The two test trees are different things and are kept textually distinct:
 * {@code tests} is the reference parity oracle, and {@code services/common-lib/src/test} is this
 * module's own test source.
 *
 * <p>Trade-offs: two of the baseline's cursor fields are deliberately not carried across, and the
 * capability they provided is given up rather than reproduced. Line 237 of
 * {@code app/cbl/COCRDLIC.cbl} declares a one-digit screen ordinal, with its first-page condition at
 * line 238, and line 239 declares a one-digit last-page-displayed indicator, with its two conditions
 * at lines 240 and 241. Neither has an equivalent here. An envelope positioned by key cannot derive
 * an absolute ordinal without enumerating everything ahead of the cursor, which is the very
 * enumeration this design exists to avoid, and {@code hasNext} already says everything the
 * last-page indicator said. The compromise is real and is worth naming: a caller cannot be told that
 * it is looking at the fourth page of anything. The evidence that little is lost comes from the
 * other browse screen, where the ordinal is a display quantity and nothing more: in
 * {@code app/cbl/COTRN00C.cbl} it is incremented at lines 306 and 307, defaulted at lines 316 to
 * 319, moved to the map field at line 324, and decremented or floored at lines 361 to 367, and no
 * read anywhere in that program is ever positioned by it. One detail of the discarded indicator is
 * recorded so that nobody restores what looks like a transcription error: its polarity runs against
 * intuition, because line 240 gives shown the value 0 while line 241 gives not-shown the value 9, so
 * the truthy state is the lower value. This type states positively that a further page exists and
 * inverts nothing.
 *
 * <p>Trade-offs: this type carries no component naming how many rows a page holds. That count is a
 * property of the screen that asked, not of the envelope that answers, and the baseline proves it by
 * disagreeing with itself. {@code app/cbl/COCRDLIC.cbl} holds seven rows, declared at lines 177 and
 * 178 and corroborated by the comment at line 250 recording the display array as 28 characters by 7
 * rows totalling 196, which agrees with the three row fields at lines 258 to 260 summing to exactly
 * 28. {@code app/cbl/COTRN00C.cbl} holds ten, its forward loop running until its index reaches
 * eleven at line 297 and its backward loop starting that index at ten at line 349. The
 * counter-example reads effectively one row, a single backward read. A component naming one of those
 * three would be wrong for the other two, and a caller that needs the number already knows it,
 * because it is the caller that chose how many rows to request. The cost accepted is that the
 * envelope alone cannot be inspected to learn what a full page was; the gain is that it never
 * carries a number it could contradict.
 *
 * <p>Trade-offs: making the tokens opaque text buys one structure for two key shapes and gives up
 * compile-time checking of their contents. Neither this type nor the compiler can tell a
 * well-formed composite from a malformed one, so a caller that composes a token in one order and
 * splits it in another gets no diagnostic from here. That risk is answered where it can be answered,
 * by stating the composite ordering above and by testing it, rather than by a type parameter whose
 * only effect would be to move the same composing and splitting into every call site while
 * fragmenting the one envelope the services share.
 *
 * @param <T> the element type of the rows this page carries; the envelope never inspects a row, so
 *     any type a service can serialise is admissible, and no reference-baseline entity is named here
 * @param items the rows of this page, in the query's ordering, never {@code null}; an exhausted page
 *     carries an empty list rather than {@code null}, and the constructor stores an unmodifiable copy
 * @param firstKey the opaque cursor token identifying the first row in {@code items}, which drives
 *     reading backward, or {@code null} when this page carries no rows; where the token is composite
 *     it is ordered as the underlying physical key is ordered, the card number preceding the account
 *     identifier, and never in the order a screen happened to display
 * @param lastKey the opaque cursor token identifying the last row in {@code items}, which drives
 *     reading forward, or {@code null} when this page carries no rows; it identifies the last row the
 *     caller actually received and never the surplus row read to settle {@code hasNext}
 * @param hasNext whether a further page follows this one, established by requesting one row beyond
 *     the page and observing whether that row exists, and never from any tally of how many rows or
 *     pages exist altogether
 */
public record PageResponse<T>(List<T> items, String firstKey, String lastKey, boolean hasNext) {

    /**
     * Validates and normalises the four components so that every instance in existence already obeys
     * this type's cursor contract.
     *
     * <p>Refactoring Rationale: the baseline had nowhere to put a check like this. Its cursor lived
     * in a communication-area structure that any of the eighteen online programs could move a value
     * into, declared from line 229 of {@code app/cbl/COCRDLIC.cbl}, so no single place saw every
     * assignment and nothing could reject an inconsistent one. A canonical constructor is that single
     * place: an instance cannot come into being without passing through it, so the contract holds by
     * construction rather than by every caller remembering it.
     *
     * @param items the rows of this page, which must not be {@code null} and must contain no
     *     {@code null} element; an unmodifiable copy is stored
     * @param firstKey the cursor token for the first row, or {@code null}, empty or blank to mean
     *     absent, all three of which are stored as {@code null}
     * @param lastKey the cursor token for the last row, or {@code null}, empty or blank to mean
     *     absent, all three of which are stored as {@code null}
     * @param hasNext whether a further page follows, which is accepted as given because only the
     *     caller that issued the query beyond the page can know it
     * @throws NullPointerException if {@code items} is {@code null}, or if any element of
     *     {@code items} is {@code null}; a {@code null} row would serialise as a hole in the page and
     *     leave a caller unable to tell an absent row from a row of absent values
     * @throws IllegalArgumentException if {@code items} is empty while either cursor token is
     *     present, because a page carrying no rows has no first or last returned row for a token to
     *     identify
     */
    public PageResponse {
        // Alternatives Considered: letting a null list through and treating it as an empty page was
        //   evaluated and rejected. It would make two encodings of "no rows" reachable, which is the
        //   same multiple-sentinel problem the three baseline absence markers already demonstrate,
        //   and it would hide the caller's mistake at the point where it is still cheap to report.
        if (items == null) {
            throw new NullPointerException(
                    "items must not be null; an exhausted page carries an empty list");
        }

        // Trade-offs: the incoming list is copied rather than retained. A record is only as immutable
        //   as its components, so retaining the caller's list would leave a page whose rows can change
        //   after it was built, and a response that changes after it is assembled cannot be reasoned
        //   about. The cost is one shallow copy per page, paid on a list already bounded by the page
        //   the caller asked for; the copy also rejects a null element, which is why no separate scan
        //   for one is written here.
        items = List.copyOf(items);

        // Assumptions: absence arrives in whichever of the baseline's three spellings the caller's
        //   own data carried, so it is collapsed here to the single spelling the accessors promise.
        //   Normalising at construction rather than at each accessor keeps the stored state and the
        //   returned state identical, so no caller has to know which of the two it is looking at.
        firstKey = absentWhenBlank(firstKey);
        lastKey = absentWhenBlank(lastKey);

        // Alternatives Considered: rejecting an empty page that reports a further page was considered
        //   and deliberately NOT done, because the baseline itself can produce exactly that state. The
        //   card list applies a post-read filter, at paragraph 9500-FILTER-RECORDS. on line 1382 of
        //   app/cbl/COCRDLIC.cbl, after the records have been read; every row of a read can therefore
        //   be filtered away while further rows remain beyond it. Forbidding the combination would
        //   contradict a cited program. Only the cursor invariant below is enforced, because that one
        //   is unconditional: with no rows returned there is no returned row to identify.
        if (items.isEmpty() && (firstKey != null || lastKey != null)) {
            throw new IllegalArgumentException(
                    "a page carrying no rows must carry no cursor token, because there is no first "
                            + "or last returned row for a token to identify");
        }
    }

    /**
     * Builds the page that reports an ordered set exhausted, carrying no rows, no cursor token and no
     * further page.
     *
     * <p>Alternatives Considered: a shared constant was rejected in favour of a generic method.
     * A constant would have to be raw or wildcard-typed and every caller would then cast or suppress
     * at the use site, whereas a generic method infers the element type from its assignment context
     * and stays type-safe. Allocating one empty page per call is the accepted cost, and it is
     * negligible against the query the caller has just run.
     *
     * <p>Assumptions: this is the exhaustion state the baseline reaches on an end-of-file response,
     * where lines 1215 to 1221 of {@code app/cbl/COCRDLIC.cbl} set the no-further-page condition, and
     * where lines 487 and 488 of {@code app/cbl/COBIL00C.cbl} move zeros in as an empty sentinel. It
     * is not a general-purpose blank value: a page that has rows must be built through the
     * constructor with the tokens that identify them.
     *
     * @param <T> the element type the caller's page is declared over, inferred from the assignment
     *     context, so that an exhausted page composes with a populated one of the same type
     * @return an exhausted page whose rows are empty and unmodifiable, whose two cursor tokens are
     *     both {@code null}, and whose further-page indicator is {@code false}
     */
    public static <T> PageResponse<T> empty() {
        return new PageResponse<>(List.of(), null, null, false);
    }

    /**
     * Reports whether a request for the page before this one can be expressed.
     *
     * <p>Assumptions: this states expressibility and not verified existence, and the distinction is
     * deliberate. Reading backward seeks keys strictly less than {@code firstKey}, so the request is
     * expressible exactly when a first key exists; whether that query then yields rows is settled by
     * running it. The baseline draws the same distinction and resolves it the same way: on its
     * backward path it sets the further-page condition unconditionally at line 1287 of
     * {@code app/cbl/COCRDLIC.cbl}, with no probe at all, because arriving at a page from a
     * neighbouring one already implies the neighbour was reachable. Claiming verified existence here
     * would require a probe in the opposite direction that no caller has run.
     *
     * <p>Assumptions: one consequence is worth stating outright, because it surprises on first
     * reading. The very first page of a set reports {@code true} here, since it carries a first key
     * like any other page, and a backward request from it is therefore expressible and simply yields
     * nothing. This type cannot say otherwise. A page at the start of a set and a page in the middle
     * of one are structurally identical in these four components, and distinguishing them would take
     * a further stored component, which the design refuses for the reason given just below.
     * Nothing is lost by that: a caller that reached a page by issuing the opening query with no
     * cursor at all already knows it is at the start, from its own request rather than from this
     * response, and it is that caller which decides whether to offer a backward step.
     *
     * <p>Trade-offs: this is derived from an existing component rather than carried as a fifth one.
     * A stored flag would be a second thing to keep in step with {@code firstKey} and could contradict
     * it; deriving it cannot. The cost is that a caller cannot assert backward availability
     * independently of the token, which is the intended constraint rather than a limitation.
     *
     * @return {@code true} when this page carries a first cursor token, so a backward request is
     *     expressible, and {@code false} when it does not, which is the case for an exhausted page
     */
    public boolean hasPrev() {
        return firstKey != null;
    }

    /**
     * Collapses every spelling of an absent cursor token onto {@code null}, leaving a present token
     * untouched.
     *
     * <p>Assumptions: a token reaching this type may have been read out of a field of declared width
     * that pads with blanks, so a wholly blank value means absent rather than a token made of blanks.
     * Only the wholly absent case is rewritten. A token with any non-blank character is returned
     * exactly as received, including its padding, because the token is opaque: this type does not
     * know where one key ends and the next begins inside a composite, and trimming its interior would
     * be an interpretation that could change which row the token names.
     *
     * <p>Alternatives Considered: trimming every token, and rejecting a blank one outright, were both
     * evaluated. Trimming was rejected on the opacity ground just stated. Rejecting was rejected
     * because a blank token is not a caller error: it is what the baseline's own blank and zero
     * sentinels decode to, so treating it as absent is the faithful reading rather than a lenient one.
     *
     * @param cursorToken the candidate token as supplied, which may be {@code null}, empty, blank or
     *     a genuine key value
     * @return {@code null} when the candidate is {@code null}, empty or entirely blank, and otherwise
     *     the candidate unchanged
     */
    private static String absentWhenBlank(String cursorToken) {
        return cursorToken == null || cursorToken.isBlank() ? null : cursorToken;
    }
}
