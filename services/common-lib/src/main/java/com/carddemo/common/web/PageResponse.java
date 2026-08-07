package com.carddemo.common.web;

import java.util.List;

/**
 * Carries one page of an ordered result set together with the two cursor tokens and the
 * further-page indicator that a caller needs in order to walk that set by key.
 *
 * <p><b>Purpose.</b> A stateless request handler cannot remember where a caller had reached in a
 * list, so something in the response has to say it. This type is that something, and it says exactly
 * four things: the rows of the current page, the token naming the leading boundary of those rows, the
 * token naming their trailing boundary, and whether a further page follows. A caller holding those
 * values can ask for the page after this one, or the page before it, while the server retains nothing
 * whatsoever between the two requests.
 *
 * <p>Refactoring Rationale: <b>four components, not seven.</b> A revision of this envelope carried
 * two further cursor components -- a forward and a backward <em>request position</em> held separately
 * from the two <em>row identities</em> -- together with a backward availability indicator, on the
 * ground that three states were otherwise representable that no caller could act on. The four
 * components named above are the shape this migration fixed for the envelope, and the additional three
 * are withdrawn: two artifacts authored against the fixed shape already describe it as exactly these
 * four ({@code com.carddemo.transaction.dto} and {@code com.carddemo.reporting.dto} both quote the
 * four-component signature in their package documentation), and the browser client reads exactly these
 * four. An envelope every consumer describes with four members may not publish seven.
 *
 * <p>Refactoring Rationale: none of the three defects the withdrawn components were introduced for is
 * reintroduced, because each is answered by an invariant on the four instead. The two tokens are the
 * page's <em>boundary positions</em> rather than strictly its row identities: on an ordinary page they
 * are the first and the last row returned, and on a page whose every row was removed by a post-read
 * filter -- which the card list genuinely produces, filtering rows <em>after</em> reading them at
 * paragraph {@code 9500-FILTER-RECORDS.} on line 1382 of {@code app/cbl/COCRDLIC.cbl} -- they are the
 * keys at which scanning stopped in each direction. So a page that returns rows it cannot be paged away
 * from is rejected by the constructor; a page that reports a further page without the position to
 * pursue it from is rejected too, because {@code hasNext} is admitted only alongside a present
 * {@code lastKey}; and backward availability is no longer a separate claim at all, being the presence
 * of {@code firstKey}, which is precisely what the browser client binds its backward control to.
 *
 * <p>Trade-offs: expressing backward availability as the presence of {@code firstKey} means the
 * opening page of a set, which names its own first row like every other page, offers a backward step
 * that returns an empty page rather than refusing outright. That is accepted for two reasons. It is
 * what the reference does -- its backward path sets the further-page condition unconditionally at line
 * 1287 of {@code app/cbl/COCRDLIC.cbl}, with no probe -- so no behaviour is lost against the baseline;
 * and the alternative, a fifth component stating the answer, is the component this envelope is fixed
 * not to have. A caller that wants to withhold the control on the opening page already knows it issued
 * no cursor.
 *
 * <p><b>Parameters, return values, exceptions or errors at the type level.</b> The four record
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
 * key shape -- {@code app/cbl/COCRDLIC.cbl} pages the card file by its sixteen-character card number,
 * while {@code app/cbl/COTRN00C.cbl} carries a sixteen-character transaction identifier at line 595 --
 * and a future browse over a genuinely composite key would be a third shape again. A type parameterised
 * over the key would be instantiated at those unrelated shapes and would push composing and splitting a
 * key into every caller; an opaque token carries any shape unchanged.
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
 * <p>Assumptions: the card list's cursor key is the <b>sixteen-character card number and nothing
 * else</b>, and that is read off the browse itself rather than off the structure that surrounds it.
 * {@code app/cbl/COCRDLIC.cbl} declares the record identification field at lines 137 to 139 and then
 * positions and reads with {@code RIDFLD(WS-CARD-RID-CARDNUM)} and
 * {@code KEYLENGTH(LENGTH OF WS-CARD-RID-CARDNUM)} at lines 1131 to 1132, 1150 to 1151, 1201 to 1202
 * and 1276 onward -- one operand, sixteen characters, which is the primary key of the card file.
 * Refactoring Rationale: an earlier revision of this charter described the cursor key as a
 * twenty-seven-character composite of the card number followed by the eleven-digit account identifier,
 * taken from the communication-area group at lines 230 to 232. That reading was wrong and is corrected
 * here rather than quietly dropped, because a cursor implementation that encoded the second component
 * would seal a key the store cannot seek on. The account half of that group is <b>dead
 * scaffolding</b>: every statement that would move an account identifier into the record
 * identification field is commented out, at lines 449, 476, 491, 507 and 577, so the field's account
 * portion is never populated and never contributes to a browse position. The group is retained in the
 * baseline as context the screen redisplays, not as a key.</p>
 *
 * <p>Assumptions: the cursor key is emphatically <b>not</b> the display-row layout at lines 258 to 260,
 * which is the account number, then the card number, then the card status, in a reading order meant for
 * a person. That layout is twenty-eight characters and its final character is the <b>card status</b> at
 * line 260 rather than a row-selection marker. Conflating a display layout with a key would produce a
 * query that pages in the wrong sequence while still returning plausible-looking rows.
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
 * key from a malformed one, so a caller that seals a token from one column and seeks on another gets no
 * diagnostic from here. That risk is answered by stating the key of each browse above and by testing it,
 * rather than by a type parameter whose only effect would be to move the same composing and splitting
 * into every call site while fragmenting the one envelope the services share.
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
 * the raw key -- which is what a keyset query naturally yields -- satisfied it. That would
 * have serialised a primary account number into every page of the card list and handed it to the
 * client to replay. Every cursor component present here must now match the shape
 * {@link CursorToken} produces, whose payload carries the key inside an authenticated envelope bound
 * to the query and the subject it was issued for. The physical key described above still governs what
 * is sealed inside the token; it is simply no longer the token.
 *
 * @param <T> the element type of the rows this page carries; the envelope never inspects a row, so
 *     any type a service can serialise is admissible, and no reference-baseline entity is named here
 * @param items the rows of this page, in the query's ordering, never {@code null}; an exhausted page
 *     carries an empty list rather than {@code null}, and the constructor stores an unmodifiable copy
 * @param firstKey the sealed cursor token naming this page's leading boundary, produced by
 *     {@link CursorToken#seal(String, String)}: the first row in {@code items} on an ordinary page,
 *     or the key at which a backward scan stopped on a page whose rows were all filtered away after
 *     being read, and {@code null} when neither exists. Where the token is composite it is ordered as
 *     the underlying physical key is ordered, the card number preceding the account identifier, and
 *     never in the order a screen happened to display. Its presence is what tells a caller a backward
 *     step is expressible
 * @param lastKey the sealed cursor token naming this page's trailing boundary, produced by
 *     {@link CursorToken#seal(String, String)}: the last row in {@code items} on an ordinary page --
 *     the last row the caller actually received, never the surplus row read to settle
 *     {@code hasNext} -- or the key at which a forward scan stopped on a filtered-away page, and
 *     {@code null} when neither exists
 * @param hasNext whether a further page follows this one, established by requesting one row beyond
 *     the page and observing whether that row exists, and never from any tally of how many rows or
 *     pages exist altogether; it is admitted as {@code true} only alongside a present
 *     {@code lastKey}, so a caller told to continue always holds the position to continue from
 */
public record PageResponse<T>(
        List<T> items,
        String firstKey,
        String lastKey,
        boolean hasNext) {

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
     * @param firstKey the token naming this page's leading boundary, or {@code null}, empty or blank to
     *     mean absent, all three of which are stored as {@code null}
     * @param lastKey the token naming this page's trailing boundary, or {@code null}, empty or blank to
     *     mean absent, all three of which are stored as {@code null}
     * @param hasNext whether a further page follows, which is accepted as given because only the
     *     caller that issued the query beyond the page can know it
     * @throws NullPointerException if {@code items} is {@code null}, or if any element of
     *     {@code items} is {@code null}; a {@code null} row would serialise as a hole in the page and
     *     leave a caller unable to tell an absent row from a row of absent values
     * @throws IllegalArgumentException if {@code items} is non-empty while either boundary token is
     *     absent, because every returned page must be navigable away from; if {@code hasNext} is
     *     {@code true} while {@code lastKey} is absent, because that is the one state a caller cannot
     *     act on -- told to continue with nowhere to continue from; or if either present token is not
     *     a token sealed by {@link CursorToken}
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

        // Assumptions: a page that returned rows must name both of its boundaries -- it is those two
        //   values a subsequent request is verified against, so a page a caller cannot name the ends of
        //   is a page it cannot page away from.
        // Refactoring Rationale: the converse is deliberately NOT asserted, and the change is what
        //   lets four components carry what seven carried. A page with no rows may still name a
        //   boundary, because the card list applies its filter AFTER reading -- paragraph
        //   9500-FILTER-RECORDS. at line 1382 of app/cbl/COCRDLIC.cbl -- so a read whose every record
        //   was filtered away has keys at which scanning stopped and no returned row to take them
        //   from. Forbidding that combination would either contradict a cited program or force the two
        //   scan positions back into components of their own; admitting it, and holding hasNext to the
        //   presence of lastKey below, keeps the state expressible without them.
        if (!items.isEmpty() && (firstKey == null || lastKey == null)) {
            throw new IllegalArgumentException(
                    "a page carrying " + items.size() + " row(s) must carry both a firstKey and a "
                            + "lastKey, because a page a caller cannot name the ends of is a page it "
                            + "cannot page away from");
        }

        // Alternatives Considered: rejecting an empty page that reports a further page was considered
        //   and deliberately NOT done, because the baseline itself produces exactly that state -- see
        //   the post-read filter cited above. What IS forbidden is reporting a further page without the
        //   position a forward request is issued from: that is the one state a caller cannot act on,
        //   having been told to continue and given nowhere to continue from.
        // Trade-offs: only this direction of the implication is enforced. A present lastKey while
        //   hasNext is false is a legitimate final page -- it still names its trailing boundary, which
        //   is what a caller paging backward off it seeks from -- so the biconditional an earlier
        //   revision asserted between a forward indicator and a forward cursor would refuse the last
        //   page of every set. The asymmetry is therefore the contract rather than an omission, and it
        //   is stated here so nobody restores the stricter test.
        if (hasNext && lastKey == null) {
            throw new IllegalArgumentException(
                    "hasNext=true requires a lastKey, because a further page must be reported together "
                            + "with the position a forward request is issued from; reporting one "
                            + "without the other tells a caller to continue with nowhere to continue "
                            + "from");
        }

        // Refactoring Rationale: both cursor components are now required to be SEALED tokens, and
        //   this check is the enforcement this type previously lacked. It described them as opaque from
        //   the outset and nothing held a caller to it, so the raw key a keyset query
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
     * @return an exhausted page whose rows are empty and unmodifiable, whose two boundary tokens are
     *     both {@code null}, and which reports no further page
     */
    public static <T> PageResponse<T> empty() {
        return new PageResponse<>(List.of(), null, null, false);
    }

    /**
     * Builds the ordinary page: one that returned rows, whose boundaries are the first and last of
     * those rows.
     *
     * <p>Assumptions: on a page that returned rows the position a forward request is issued from is
     * the last row returned, and the position a backward request is issued from is the first row
     * returned. That is the baseline's own arrangement -- line 1268 of {@code app/cbl/COCRDLIC.cbl}
     * moves the first key of the current page into the field its backward browse is positioned from --
     * so this factory is what a service calls for every page that is not the filtered-away special
     * case below. It exists so that the ordinary call site cannot get the pairing wrong: supplying the
     * two tokens crossed would produce an envelope that pages in the wrong direction while remaining
     * perfectly valid.</p>
     *
     * <p>Trade-offs: forward availability stays a parameter rather than being derived from the tokens,
     * because it is not knowable from the page's own contents: it comes from the probe read -- one row
     * beyond the page, per the two cited programs -- which only the caller that issued the query
     * performed. Backward availability takes no parameter at all, because on a page that returned rows
     * a backward step is always expressible from {@code firstKey}, which this factory requires;
     * whether that step yields rows is a question only the store can answer, and the reference answers
     * it the same way, reporting a further page on its backward path unconditionally at line 1287 of
     * {@code app/cbl/COCRDLIC.cbl}.</p>
     *
     * @param <T> the element type of the rows this page carries
     * @param items the rows returned, which must not be {@code null} and must not be empty; use
     *     {@link #empty()} or {@link #ofFilteredEmpty(String, String)} for a page with no rows
     * @param firstKey the token identifying the first returned row, which must be present
     * @param lastKey the token identifying the last returned row, which must be present
     * @param hasNext whether the probe read found a row beyond this page
     * @return a page carrying the rows supplied, naming both of its boundaries, and reporting a further
     *     page exactly as {@code hasNext} states
     * @throws NullPointerException if {@code items} is {@code null} or contains a {@code null} element
     * @throws IllegalArgumentException if either boundary token is absent while rows are present, or if
     *     {@code hasNext} is {@code true} while {@code lastKey} is absent, both of which the canonical
     *     constructor rejects
     */
    public static <T> PageResponse<T> ofRows(
            List<T> items, String firstKey, String lastKey, boolean hasNext) {
        return new PageResponse<>(items, firstKey, lastKey, hasNext);
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
     * <p>Refactoring Rationale: this factory is why the four components suffice. The scan positions it
     * receives are stored in the two boundary components, which is exactly what they are on this page,
     * so the state needs no components of its own: a caller that honours the reported further page
     * sends back {@code lastKey} as it would from any other page, and one stepping backward sends back
     * {@code firstKey}. Naming the parameters after the direction each continues, rather than after the
     * component each lands in, keeps the call site readable at the point where the two are easiest to
     * cross.</p>
     *
     * @param <T> the element type the caller's page is declared over, inferred from the assignment
     *     context
     * @param forwardPosition the key at which the forward scan stopped, stored as {@code lastKey}, or
     *     {@code null} when nothing remains ahead; a further page is reported exactly when this is
     *     present
     * @param backwardPosition the key at which the backward scan stopped, stored as {@code firstKey}, or
     *     {@code null} when nothing remains behind; a backward step is expressible exactly when this is
     *     present
     * @return a page carrying no rows and whichever continuation positions were supplied, in the two
     *     boundary components
     * @throws IllegalArgumentException if either supplied position is not a token sealed by
     *     {@link CursorToken}, which the canonical constructor rejects
     */
    public static <T> PageResponse<T> ofFilteredEmpty(String forwardPosition, String backwardPosition) {
        return new PageResponse<>(
                List.of(),
                backwardPosition,
                forwardPosition,
                absentWhenBlank(forwardPosition) != null);
    }

    /**
     * Collapses every spelling of an absent cursor token onto {@code null}, leaving a present token
     * untouched.
     *
     * <p>Assumptions: a token reaching this type may have been read out of a field of declared width
     * that pads with blanks, so a wholly blank value means absent rather than a token made of blanks.
     * Only the wholly absent case is rewritten. A token with any non-blank character is returned
     * exactly as received, including its padding, because the token is opaque: this type does not
     * know where one key ends and the next begins inside a multi-column key, and trimming its interior would
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
