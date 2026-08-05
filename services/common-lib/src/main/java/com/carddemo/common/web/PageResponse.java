package com.carddemo.common.web;

import java.util.List;

/**
 * Carries one page of an ordered result set together with the two cursor tokens and the
 * further-page indicator that a caller needs in order to walk that set by key.
 *
 * <p><b>Purpose.</b> A stateless request handler cannot remember where a caller had reached in a
 * list, so something in the response has to say it. This type is that something, and it says exactly
 * seven things: the rows of the current page, a token identifying the first of those rows, a token
 * identifying the last of them, the token a forward request is issued from, the token a backward
 * request is issued from, and one availability indicator per direction. A caller holding those values
 * can ask for the page after this one, or the page before it, while the server retains nothing
 * whatsoever between the two requests.
 *
 * <p>Refactoring Rationale: the first three of those tokens are <em>row identities</em> and the next
 * two are <em>request positions</em>, and separating them is the correction this type carries. An
 * earlier shape of this envelope held only the two row identities and one forward indicator, and
 * three states were then representable that no caller could act on. A page could return rows and
 * carry no token, leaving the caller holding rows it could not page away from. A page could return no
 * rows and still claim that a further page followed -- which the card list genuinely produces, because
 * it filters rows <em>after</em> reading them at paragraph {@code 9500-FILTER-RECORDS.} on line 1382
 * of {@code app/cbl/COCRDLIC.cbl} -- and the caller was then told to continue with no position to
 * continue from. And backward availability was inferred from the mere presence of a first row, so the
 * opening page of every set claimed a previous page that did not exist. Each of the three is now
 * unrepresentable: the canonical constructor requires a row identity for every returned page, requires
 * a request position for every direction reported available, and takes backward availability as a
 * stated value rather than deriving one.
 *
 * <p><b>Parameters, return values, exceptions or errors at the type level.</b> The seven record
 * components are this type's parameters, and each carries its own at-clause below alongside the
 * at-clause for the element type. A type declaration returns no value and raises nothing, so no
 * return or exception at-clause appears here; the members declared in the body carry their own. The
 * inapplicability is stated rather than left silent, because the Explainability rule lists a
 * docstring that omits parameters or return values among its forbidden patterns at line 39, and a
 * reader must be able to tell a declared inapplicability from an oversight.
 *
 * <p>Refactoring Rationale: this type replaces the four-verb browse and the communication-area
 * cursor that carried its state between screen turns. In {@code app/cbl/COCRDLIC.cbl} that browse
 * runs start at line 1129, read forward at line 1146, the lookahead read at line 1197 and end at
 * line 1258, then start backward at line 1273 with a priming read at line 1294, the backward loop
 * at line 1322 and end at line 1376. In {@code app/cbl/COTRN00C.cbl} each verb is its own
 * paragraph: start at line 593, read forward at line 626, read backward at line 660, end at line
 * 694. What was wrong with that arrangement is not the verbs but where the state lived. It lived in
 * a structure the client echoed back, declared from line 229 of {@code app/cbl/COCRDLIC.cbl}, so
 * continuity across a turn depended on the client returning it intact. Moving the same values into a
 * response body ends that dependency: the server reads them back off the next request and
 * verifies them, rather than trusting a round-tripped buffer. The substitution is one-to-one rather
 * than an approximation, and that is the decisive finding of the analysis behind this type: the
 * baseline cursor was already a cursor over keys. Lines 230 to 232 hold the last key of the page and
 * lines 233 to 235 hold the first key of it, both as real key values; line 242, with its two
 * condition names at lines 243 and 244, holds nothing but whether a further page exists. There is no
 * count of rows already consumed anywhere in that structure, so nothing had to be invented here and
 * nothing had to be discarded.
 *
 * <p>Refactoring Rationale: this type is declared once in the shared kernel rather than once per
 * service, for the same reason the reference suite forbids duplicating a record layout instead of
 * resolving it through one include path ({@code tests/README.md} lines 540 to 542). The failure is
 * already demonstrated: the two browse screens hand-rolled the same paging independently and reached
 * different conventions, including an outright disagreement about what the last key means. Neither
 * disagreement is a compilation error; both yield pages that look right and quietly lose or repeat a
 * row at the boundary.
 *
 * <p>Alternatives Considered: positioning a page by counting rows from the start of the ordered set.
 * Rejected on a specific defect rather than a preference: under concurrent insertion the number of
 * rows preceding the cursor changes underneath a reader between one turn and the next, so a page
 * positioned by counting omits some rows and presents others twice, whereas a key already read keeps
 * its place in the ordering no matter what is inserted around it. The concurrency is attested by the
 * baseline itself at lines 212 to 217 of {@code app/cbl/COBIL00C.cbl}, where a highest-key lookup runs
 * over six lines with nothing holding a lock across them.
 *
 * <p>Alternatives Considered: {@code app/cbl/COBIL00C.cbl} is cited here as a <b>counter-example</b>
 * and never as a paging source, because it does not browse. Its start at line 441 carries no
 * greater-or-equal option, and in all 572 lines there is no forward-read paragraph whatsoever. What
 * lines 210 to 219 perform is a highest-key lookup followed by an increment, whose relational
 * equivalent is a maximum over the key column rather than a page. This envelope must therefore never
 * be wired into the bill-payment path, and the count that matters is two browse screens, not three.
 *
 * <p>Alternatives Considered: a second type parameter for the key, rejected in favour of one type
 * parameter over the element and opaque textual cursor tokens. The two browse screens do not share a
 * key shape -- {@code app/cbl/COCRDLIC.cbl} composes twenty-seven characters at lines 230 to 232, a
 * sixteen-character card number followed by an eleven-digit account identifier, while
 * {@code app/cbl/COTRN00C.cbl} carries a single scalar transaction identifier at line 595. A type
 * parameterised over the key would be instantiated at two unrelated shapes and would push composing
 * and splitting the composite into every caller; an opaque token carries either shape unchanged.
 *
 * <p>Alternatives Considered: {@code lastKey} identifies the last row actually returned in
 * {@code items}, which had to be chosen rather than inherited because the two browse screens
 * disagree. {@code app/cbl/COCRDLIC.cbl} overwrites its stored last key with the probe row's key at
 * lines 1207 to 1214, so the value it retains identifies the eighth record on a page of seven -- a row
 * the terminal never displayed. That behaviour is rejected: a token identifying a row the caller never
 * received cannot be verified by that caller, and a backward request built from it would seek from a
 * position the caller cannot account for. Symmetrically {@code firstKey} identifies the first row
 * actually returned, so both tokens always name rows the caller holds.
 *
 * <p>Assumptions: the cursor ordering is the card number followed by the account identifier, per
 * lines 230 to 232 of {@code app/cbl/COCRDLIC.cbl}, which is the physical key order of the card
 * file. It is emphatically <b>not</b> the display-row ordering at lines 258 to 260, which is the
 * account number, then the card number, then the card status, in a reading order meant for a person.
 * The two layouts differ in length as well as order, twenty-seven characters against twenty-eight,
 * and the twenty-eighth character is the <b>card status</b> at line 260 rather than a row-selection
 * marker. Conflating the two orders would produce a query that pages in the wrong sequence while
 * still returning plausible-looking rows.
 *
 * <p>Assumptions: {@code hasNext} is discovered by requesting one row beyond the page and observing
 * whether that row exists, never from a running tally of how many rows or pages there are. Both
 * browse screens attest to it, which is why it is a contract rather than one program's habit:
 * {@code app/cbl/COCRDLIC.cbl} issues the extra read at line 1197 purely as a probe, setting the
 * indicator true at lines 1207 to 1214 and false at lines 1215 to 1221, and
 * {@code app/cbl/COTRN00C.cbl} runs the same probe at line 308. A caller building this envelope
 * therefore asks its store for one more row than it intends to return, puts the surplus row nowhere,
 * and reports its presence here.
 *
 * <p>Assumptions: both tokens are required because a single one cannot express both directions.
 * Reading forward queries for keys strictly greater than {@code lastKey}, ordered ascending, limited
 * to one more row than the page holds -- the analogue of the forward reads at lines 1146 and 1197 of
 * {@code app/cbl/COCRDLIC.cbl} and line 626 of {@code app/cbl/COTRN00C.cbl}. Reading backward queries
 * for keys strictly less than {@code firstKey}, ordered descending, which is precisely what a backward
 * read does; the baseline makes the seek point explicit at line 1268, where the first key of the
 * current page is moved into the field the backward browse is positioned from. Downstream the two
 * directions bind to the two paging function keys, backward to the seventh and forward to the eighth.
 *
 * <p>Assumptions: both tokens are nullable, and absent is spelled exactly one way, as {@code null}.
 * The baseline spells absence three ways -- low values at line 243 of {@code app/cbl/COCRDLIC.cbl},
 * high values at line 212 of {@code app/cbl/COBIL00C.cbl} and zeros at lines 487 and 488 of that same
 * program -- and three encodings of absence is three chances for a caller to test for the wrong one.
 * A token that is absent, empty or entirely blank means the same thing here and is normalised to
 * {@code null} by the constructor. A token that is present is passed through unaltered, because it is
 * opaque: it may legitimately carry the blank padding of a declared-width key field, and trimming its
 * interior would be an interpretation this type has no standing to make.
 *
 * <p>Assumptions: a page may carry rows whose values include money, and this type never sees one.
 * Money is exact decimal at every hop, it is transported as a JSON string, and it is never a JSON
 * number and never a binary approximation of a decimal, so that no client can route it through an
 * inexact numeric type at the boundary. The serialisation that guarantees it is
 * {@code MoneyModule}, which belongs to {@code com.carddemo.common.money} and which every consuming
 * mapper registers by discovery, through the provider-configuration file that module publishes in this
 * library's own resource tree -- so the guarantee does not depend on a consuming service remembering a
 * wiring step. The prohibition is enforced by an architecture test and not by this Javadoc. What this type contributes is restraint: it
 * carries whatever rows a caller puts into it and never interprets one of their values, so it
 * imports nothing from the money package and adds no numeric type of its own.
 *
 * <p>Trade-offs: this type carries no component naming how many rows a page holds, because that count
 * is a property of the screen that asked rather than of the envelope that answers -- and the baseline
 * proves it by disagreeing with itself, seven rows at lines 177 and 178 of
 * {@code app/cbl/COCRDLIC.cbl} against ten at line 297 of {@code app/cbl/COTRN00C.cbl}. A component
 * naming one would be wrong for the other, and the caller that needs the number already knows it. The
 * cost is that the envelope alone cannot be inspected to learn what a full page was; the gain is that
 * it never carries a number it could contradict.
 *
 * <p>Trade-offs: making the tokens opaque text buys one structure for two key shapes and gives up
 * compile-time checking of their contents. Neither this type nor the compiler can tell a well-formed
 * composite from a malformed one, so a caller that composes a token in one order and splits it in
 * another gets no diagnostic from here. That risk is answered by stating the composite ordering above
 * and by testing it, rather than by a type parameter whose only effect would be to move the same
 * composing and splitting into every call site while fragmenting the one envelope the services share.
 *
 * <p>Trade-offs: the baseline's one-digit screen ordinal at line 237 of {@code app/cbl/COCRDLIC.cbl}
 * and its last-page-displayed indicator at line 239 are deliberately not carried across, so a caller
 * cannot be told it is looking at the fourth page of anything. An envelope positioned by key cannot
 * derive an absolute ordinal without enumerating everything ahead of the cursor, which is the
 * enumeration this design exists to avoid, and {@code hasNext} already says what the last-page
 * indicator said. One detail of the discarded indicator is recorded so nobody restores what looks like
 * a transcription error: its polarity runs against intuition, because line 240 gives shown the value 0
 * while line 241 gives not-shown the value 9. This type states positively that a further page exists
 * and inverts nothing.
 *
 * <p>Every reference program cited above is read and cited only. None is modified, and where the
 * migrated behaviour differs the divergence is stated here rather than introduced silently.
 *
 * <p>Refactoring Rationale: opaque now means SEALED, and the canonical constructor enforces it. When
 * this type was first authored, opacity was a description of intent that nothing held a caller to, so
 * the raw composite key -- which is what a keyset query naturally yields -- satisfied it. That would
 * have serialised a primary account number into every page of the card list and handed it to the
 * client to replay. Every cursor component present here must now match the shape
 * {@link CursorToken} produces, whose payload carries the key inside an authenticated envelope bound
 * to the query and the subject it was issued for. The composite ordering described above still
 * governs the key sealed inside the token; it is simply no longer the token.
 *
 * @param <T> the element type of the rows this page carries; the envelope never inspects a row, so
 *     any type a service can serialise is admissible, and no reference-baseline entity is named here
 * @param items the rows of this page, in the query's ordering, never {@code null}; an exhausted page
 *     carries an empty list rather than {@code null}, and the constructor stores an unmodifiable copy
 * @param firstKey the sealed cursor token identifying the first row in {@code items}, produced by
 *     {@link CursorToken#seal(String, String)}, or {@code null} exactly
 *     when this page carries no rows; where the token is composite it is ordered as the underlying
 *     physical key is ordered, the card number preceding the account identifier, and never in the
 *     order a screen happened to display
 * @param lastKey the sealed cursor token identifying the last row in {@code items}, produced by
 *     {@link CursorToken#seal(String, String)}, or {@code null} exactly
 *     when this page carries no rows; it identifies the last row the caller actually received and
 *     never the surplus row read to settle {@code hasNext}
 * @param nextCursor the sealed position a forward request is issued from -- the query seeks keys
 *     strictly greater than it, ascending -- which must be present exactly when {@code hasNext} is
 *     {@code true} and absent otherwise; it is normally {@code lastKey}, and it is deliberately a
 *     separate component because a page whose rows were all filtered away after the read has a
 *     scan position and no last row
 * @param prevCursor the sealed position a backward request is issued from -- the query seeks keys
 *     strictly less than it, descending -- which must be present exactly when {@code hasPrev} is
 *     {@code true} and absent otherwise; it is normally {@code firstKey}, and it is separate for the
 *     same filtered-away reason
 * @param hasNext whether a further page follows this one, established by requesting one row beyond
 *     the page and observing whether that row exists, and never from any tally of how many rows or
 *     pages exist altogether
 * @param hasPrev whether a page precedes this one, stated by the caller from its own request context
 *     -- it is {@code false} for the opening query, which supplied no cursor, and {@code true} for any
 *     page reached from a neighbouring one -- and never inferred from this page's own contents
 */
public record PageResponse<T>(
        List<T> items,
        String firstKey,
        String lastKey,
        String nextCursor,
        String prevCursor,
        boolean hasNext,
        boolean hasPrev) {

    /**
     * Validates and normalises the seven components so that every instance in existence already obeys
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
     * @param firstKey the token identifying the first returned row, or {@code null}, empty or blank to
     *     mean absent, all three of which are stored as {@code null}
     * @param lastKey the token identifying the last returned row, or {@code null}, empty or blank to
     *     mean absent, all three of which are stored as {@code null}
     * @param nextCursor the position a forward request is issued from, or {@code null}, empty or blank
     *     to mean absent
     * @param prevCursor the position a backward request is issued from, or {@code null}, empty or
     *     blank to mean absent
     * @param hasNext whether a further page follows, which is accepted as given because only the
     *     caller that issued the query beyond the page can know it
     * @param hasPrev whether a page precedes this one, likewise accepted as given because only the
     *     caller that issued the request knows whether it arrived from a neighbouring page
     * @throws NullPointerException if {@code items} is {@code null}, or if any element of
     *     {@code items} is {@code null}; a {@code null} row would serialise as a hole in the page and
     *     leave a caller unable to tell an absent row from a row of absent values
     * @throws IllegalArgumentException if {@code items} is empty while {@code firstKey} or
     *     {@code lastKey} is present, because a page carrying no rows has no first or last returned
     *     row for a token to identify; if {@code items} is non-empty while either of those two is
     *     absent, because every returned page must be navigable away from; or if either availability
     *     indicator disagrees with the presence of its own cursor, in either direction
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
        nextCursor = absentWhenBlank(nextCursor);
        prevCursor = absentWhenBlank(prevCursor);

        // Assumptions: a row identity exists exactly when a row exists. With no rows returned there
        //   is no returned row for a token to identify, and with rows returned the caller must be
        //   able to name the two ends of what it received -- it is those two values a subsequent
        //   request is verified against. The two directions of this check are one rule, so they are
        //   enforced together rather than as two unrelated guards.
        if (items.isEmpty() && (firstKey != null || lastKey != null)) {
            throw new IllegalArgumentException(
                    "a page carrying no rows must carry no first or last row token, because there is "
                            + "no returned row for either to identify");
        }
        if (!items.isEmpty() && (firstKey == null || lastKey == null)) {
            throw new IllegalArgumentException(
                    "a page carrying " + items.size() + " row(s) must carry both a firstKey and a "
                            + "lastKey, because a page a caller cannot name the ends of is a page it "
                            + "cannot page away from");
        }

        // Alternatives Considered: rejecting an empty page that reports a further page was considered
        //   and deliberately NOT done, because the baseline itself produces exactly that state. The
        //   card list applies a post-read filter, at paragraph 9500-FILTER-RECORDS. on line 1382 of
        //   app/cbl/COCRDLIC.cbl, after the records have been read; every row of a read can therefore
        //   be filtered away while further rows remain beyond it. Forbidding the combination would
        //   contradict a cited program. What IS forbidden is reporting a direction as available
        //   without the position a request in that direction is issued from -- the state that told a
        //   caller to continue and gave it nowhere to continue from. The biconditional is enforced in
        //   both senses on purpose: a cursor present while the indicator says unavailable is equally
        //   a contradiction, and leaving that direction unchecked would let two components disagree
        //   about the same fact.
        if (hasNext != (nextCursor != null)) {
            throw new IllegalArgumentException(
                    "hasNext=" + hasNext + " disagrees with nextCursor being "
                            + (nextCursor == null ? "absent" : "present")
                            + "; a further page must be reported together with the position a forward "
                            + "request is issued from, and neither without the other");
        }
        if (hasPrev != (prevCursor != null)) {
            throw new IllegalArgumentException(
                    "hasPrev=" + hasPrev + " disagrees with prevCursor being "
                            + (prevCursor == null ? "absent" : "present")
                            + "; a preceding page must be reported together with the position a "
                            + "backward request is issued from, and neither without the other");
        }

        // Refactoring Rationale: all four cursor components are now required to be SEALED tokens, and
        //   this check is the enforcement this type previously lacked. It described them as opaque from
        //   the outset and nothing held a caller to it, so the raw composite key a keyset query
        //   produces -- a card number followed by an account identifier, per lines 230 to 232 of
        //   app/cbl/COCRDLIC.cbl -- satisfied the type exactly as well as an opaque token did. That is
        //   a primary account number serialised into a response body, held by the client and replayed
        //   on the next request, which is the one thing the word opaque was there to prevent.
        //   CursorToken.hasSealedShape decides shape without needing key material, so this type stays
        //   free of configuration while the raw key becomes unrepresentable here.
        // Trade-offs: shape and not authenticity is what can be decided here, because verifying a
        //   token needs both the key and the binding it was issued for, and neither belongs to a
        //   response envelope. Authenticity is verified where the token is redeemed, by
        //   CursorToken.open(String, String). What this check buys is that the mistake which was easy
        //   and silent -- assigning the raw key -- is now impossible, while the mistake it cannot
        //   catch requires a caller to mint a token with the deployment's own key.
        requireSealed(firstKey, "firstKey");
        requireSealed(lastKey, "lastKey");
        requireSealed(nextCursor, "nextCursor");
        requireSealed(prevCursor, "prevCursor");
    }

    /**
     * Confirms a present cursor component carries a sealed token rather than a raw key.
     *
     * @param cursorToken the normalised component value, or {@code null} for an absent cursor, which
     *     is accepted because an exhausted page and the boundary of an unpaged query both have no
     *     token
     * @param component the component name, reproduced in the refusal so it names the one that failed
     * @throws IllegalArgumentException if {@code cursorToken} is present and is not a sealed token;
     *     the message names the component and the expected form and never the value, because the
     *     value a caller is most likely to have supplied by mistake is the raw key itself
     */
    private static void requireSealed(String cursorToken, String component) {
        if (cursorToken != null && !CursorToken.hasSealedShape(cursorToken)) {
            throw new IllegalArgumentException(
                    component + " must be a token sealed by CursorToken, not a raw keyset cursor: a"
                            + " cursor built from the key columns would publish those columns to the"
                            + " client, and one of them is a primary account number");
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
     * @return an exhausted page whose rows are empty and unmodifiable, whose four tokens are all
     *     {@code null}, and whose two availability indicators are both {@code false}
     */
    public static <T> PageResponse<T> empty() {
        return new PageResponse<>(List.of(), null, null, null, null, false, false);
    }

    /**
     * Builds the ordinary page: one that returned rows, whose request positions are the identities of
     * the rows returned.
     *
     * <p>Assumptions: on a page that returned rows the position a forward request is issued from is
     * the last row returned, and the position a backward request is issued from is the first row
     * returned. That is the baseline's own arrangement -- line 1268 of {@code app/cbl/COCRDLIC.cbl}
     * moves the first key of the current page into the field its backward browse is positioned from --
     * so this factory is what a service calls for every page that is not the filtered-away special
     * case below. It exists so that the ordinary call site cannot get the pairing wrong: supplying the
     * cursors by hand and crossing them would produce an envelope that pages in the wrong direction
     * while remaining perfectly valid.</p>
     *
     * <p>Trade-offs: the two availability indicators stay parameters rather than being derived from
     * the tokens, because neither is knowable from the page's own contents. Forward availability comes
     * from the probe read -- one row beyond the page, per the two cited programs -- and backward
     * availability comes from the caller's request context, which is the only place that records
     * whether this page was reached from a neighbouring one.</p>
     *
     * @param <T> the element type of the rows this page carries
     * @param items the rows returned, which must not be {@code null} and must not be empty; use
     *     {@link #empty()} or {@link #ofFilteredEmpty(String, String)} for a page with no rows
     * @param firstKey the token identifying the first returned row, which must be present
     * @param lastKey the token identifying the last returned row, which must be present
     * @param hasNext whether the probe read found a row beyond this page
     * @param hasPrev whether the caller's request arrived from a preceding page
     * @return a page whose forward position is {@code lastKey} when a further page follows and whose
     *     backward position is {@code firstKey} when a preceding page exists, each absent otherwise
     * @throws NullPointerException if {@code items} is {@code null} or contains a {@code null} element
     * @throws IllegalArgumentException if {@code items} is empty, or if either row token is absent
     *     while rows are present, both of which the canonical constructor rejects
     */
    public static <T> PageResponse<T> ofRows(
            List<T> items, String firstKey, String lastKey, boolean hasNext, boolean hasPrev) {
        return new PageResponse<>(
                items,
                firstKey,
                lastKey,
                hasNext ? lastKey : null,
                hasPrev ? firstKey : null,
                hasNext,
                hasPrev);
    }

    /**
     * Builds the page that returned no rows because every row read was filtered away, while the scan
     * can still be continued in one or both directions.
     *
     * <p>Assumptions: this state is the reference baseline's, not an invention. The card list reads a
     * screen's worth of records and only then applies paragraph {@code 9500-FILTER-RECORDS.} at line
     * 1382 of {@code app/cbl/COCRDLIC.cbl}, so a read whose every record fails the filter displays
     * nothing while records remain on both sides of it. The distinguishing property of the state is
     * that the continuation positions are <em>not</em> row identities: there is no returned row to take
     * them from, so they are the keys at which scanning stopped in each direction, which only the
     * caller that ran the query holds.</p>
     *
     * <p>Refactoring Rationale: this is the factory that closes the defect the type-level note
     * records. The state used to be expressible with a forward indicator and no forward position, and
     * a caller that honoured the indicator had nothing to send. Requiring the position here, and
     * rejecting an indicator without one in the canonical constructor, makes the unusable form
     * unreachable while keeping the usable one available.</p>
     *
     * @param <T> the element type the caller's page is declared over, inferred from the assignment
     *     context
     * @param nextCursor the key at which the forward scan stopped, or {@code null} when nothing remains
     *     ahead; forward availability is reported exactly when this is present
     * @param prevCursor the key at which the backward scan stopped, or {@code null} when nothing
     *     remains behind; backward availability is reported exactly when this is present
     * @return a page carrying no rows, no row identities, and whichever continuation positions were
     *     supplied
     */
    public static <T> PageResponse<T> ofFilteredEmpty(String nextCursor, String prevCursor) {
        return new PageResponse<>(
                List.of(),
                null,
                null,
                nextCursor,
                prevCursor,
                absentWhenBlank(nextCursor) != null,
                absentWhenBlank(prevCursor) != null);
    }

    /**
     * Reports whether a page precedes this one, as stated by the caller that issued the request.
     *
     * <p>Refactoring Rationale: this is the record component's own accessor, and it replaces a derived
     * method that returned whether a first-row token existed. That derivation was wrong in a way that
     * was invisible from inside this type: the opening page of a set carries a first row like every
     * other page, so it reported a preceding page that cannot exist, and a screen binding its backward
     * function key to this value offered a step that could only ever return nothing. The value is now
     * supplied, because the only place the answer exists is the request that produced this page -- an
     * opening query supplies no cursor and therefore has nothing behind it, while a page reached from a
     * neighbouring one does. The baseline reaches the same answer from the same place: on its backward
     * path it sets the further-page condition unconditionally at line 1287 of
     * {@code app/cbl/COCRDLIC.cbl}, with no probe at all, because arriving at a page from a
     * neighbouring one already implies the neighbour was reachable.</p>
     *
     * <p>Trade-offs: a stated value is one more component to keep in step with {@code prevCursor},
     * which a derivation could not contradict. The cost is answered rather than accepted: the canonical
     * constructor enforces the biconditional between this indicator and that cursor, so the two cannot
     * disagree in either direction, and the {@link #ofRows(List, String, String, boolean, boolean)}
     * factory sets them together from one argument. What is bought is an answer that is correct on the
     * opening page, which no derivation from this page's contents can be.</p>
     *
     * @return {@code true} when the caller reported that this page was reached from a preceding one, in
     *     which case {@code prevCursor} is present; {@code false} otherwise, including for the opening
     *     page of a set and for an exhausted page
     */
    public boolean hasPrev() {
        return hasPrev;
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
