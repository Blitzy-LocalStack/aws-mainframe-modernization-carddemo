package com.carddemo.reference.service;

import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.dto.PageDirection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Assembles a keyset page envelope from a bounded walk, for every browse in this context.
 *
 * <p>Purpose: five browses in this service page the same way -- read one row more than the window, use
 * the extra row as the further-page flag, seal the two boundary keys as opaque positions. This class is
 * that shared step. It exists so the arithmetic that decides which row is the extra one, and which end
 * of the list it arrived at, is written once.</p>
 *
 * <p>Alternatives Considered: repeating the assembly in each service was the alternative and was
 * rejected on a correctness ground rather than a duplication one. The extra row arrives at the END of a
 * forward walk and at the START of a backward one, because a backward walk is read descending and then
 * reversed. That asymmetry is easy to get right once and easy to get wrong five times, and getting it
 * wrong drops a row from a page rather than failing, so nothing would report it.</p>
 *
 * <p>Assumptions: this class is public and stateless, and it is not a Spring bean. Public because two
 * layers legitimately need it: the two transaction-reference browses reach it from
 * {@code com.carddemo.reference.service}, and the three seeded-lookup browses reach it from
 * {@code com.carddemo.reference.api}, which reads its membership tables directly because they carry no
 * rule for a service to hold. Not a bean because it depends on nothing, and making it one would invite a
 * dependency to be added to it later.</p>
 *
 * <p>Alternatives Considered: putting it in the {@code api} package instead was evaluated and rejected,
 * because a service would then depend on a controller package -- the wrong direction, and one the layering
 * rules exist to keep out. Duplicating it in both packages was rejected for the correctness reason above:
 * the surplus-row asymmetry is the part that must not be written twice.</p>
 */
public final class ReferencePaging {

    /**
     * Prevents instantiation of a type whose whole content is static.
     *
     * @throws AssertionError always, so a reflective instantiation fails loudly
     */
    private ReferencePaging() {
        throw new AssertionError("ReferencePaging is not instantiable");
    }

    /** The scope element naming a backward walk, so a position cannot be replayed the other way. */
    private static final String SCOPE_BACKWARD = "backward";

    /** The scope element naming a forward walk. */
    private static final String SCOPE_FORWARD = "forward";

    /** The scope element standing for a filter the caller did not supply. */
    private static final String SCOPE_UNFILTERED = "-";

    /** The response member a direction-without-cursor refusal is keyed by. */
    public static final String FIELD_DIRECTION = "direction";

    /** The stable machine code a direction-without-cursor refusal carries. */
    public static final String CURSOR_REQUIRED_CODE = "CURSOR_REQUIRED_FOR_DIRECTION";

    /**
     * The refusal sentence for a direction stated without a position to move from.
     *
     * <p>Assumptions: the wording names both members rather than only the offending one, because the
     * caller's fault is the PAIRING and a sentence naming one member alone would read as though that
     * member were malformed. It carries no value the caller sent, and it holds only the characters the
     * shared advice's reference-prose shape admits, so it reaches a client verbatim.</p>
     */
    public static final String MESSAGE_CURSOR_REQUIRED =
            "A paging direction must be sent with the cursor it moves from";

    /**
     * Composes the cursor binding for one browse, one caller and one walk direction.
     *
     * <p>Purpose: this is the one place the four facts a position is sealed against are assembled, so
     * five browses cannot bind against four different subsets of them.</p>
     *
     * <p>Refactoring Rationale: every browse in this context previously sealed under the browse NAME
     * alone, and that made a position far more portable than any of them intended. A token minted for one
     * caller opened for another, so a position was transferable between sessions; a token minted while a
     * filter was applied opened with the filter changed or removed, so a caller could reposition an
     * unfiltered walk from a filtered one and land in the middle of a set it had never been shown; and a
     * token minted as a page's leading boundary opened as though it were the trailing one, so either
     * boundary could be replayed in the wrong direction. Naming all four facts inside the seal is what
     * turns each of those into a refusal instead of a page.</p>
     *
     * <p>Assumptions: the narrowing elements are composed through {@link CursorToken#scope(String...)}
     * rather than joined here, because that composition is length-prefixed and therefore injective: two
     * different filter tuples cannot produce one scope string, which a plain separator-joined form
     * allows whenever a filter value can contain the separator. A filter the caller did not supply is
     * carried as its own element rather than omitted, so an absent filter and an empty filter are two
     * different scopes.</p>
     *
     * <p>Trade-offs: a caller that changes any filter loses its position and must start the walk again.
     * That is the intended cost and it matches what the reference screens do -- retyping a filter and
     * pressing Enter restarts the browse at the top rather than resuming it -- so the refusal is the
     * faithful behaviour rather than a new restriction.</p>
     *
     * @param queryName the browse's own name, distinct from every other browse in this service
     * @param subject the authenticated caller's identity, so a position is not transferable between
     *     callers; must not be {@code null}
     * @param backward whether the position being sealed or opened is the one a backward walk moves from
     * @param narrowing the filter values the walk was performed under, in a fixed order per browse, each
     *     {@code null} where the caller supplied none
     * @return the binding to seal or open a position of that browse, that caller and that direction under
     */
    public static String binding(String queryName, String subject, boolean backward,
            String... narrowing) {

        String[] parts = new String[narrowing.length + 1];
        parts[0] = backward ? SCOPE_BACKWARD : SCOPE_FORWARD;
        for (int index = 0; index < narrowing.length; index++) {
            parts[index + 1] = narrowing[index] == null ? SCOPE_UNFILTERED : narrowing[index];
        }
        return CursorToken.binding(queryName, subject, CursorToken.scope(parts));
    }

    /**
     * Refuses a paging direction that arrives without the position it would move from.
     *
     * <p>Refactoring Rationale: this combination previously answered the FIRST page. The published
     * contract states that the two travel together or not at all, so a caller asking to page backward
     * from nothing was silently given the opening page instead of being told its request made no sense --
     * and a client walking a set could therefore loop over the first page indefinitely while believing it
     * was retreating through the set. Refusing it is what makes the contract's statement true.</p>
     *
     * <p>Alternatives Considered: treating a direction without a cursor as forward, which is what the
     * absent-direction default already means. Rejected because it silently discards a stated intent: a
     * caller that asked for PREVIOUS would receive rows moving FORWARD, which is the one outcome a paging
     * caller cannot detect from the answer.</p>
     *
     * <p>Assumptions: the reverse pairing -- a cursor with no direction -- is NOT refused, and that is
     * deliberate rather than an omission. An absent direction means forward, which is the published
     * default and a complete instruction when a position is supplied.</p>
     *
     * @param cursor the position supplied, or {@code null} when the caller supplied none
     * @param direction the direction supplied, or {@code null} when the caller supplied none
     * @throws ClientInputException when a direction is supplied without a cursor, which the shared advice
     *     renders as HTTP 400 keyed by {@link #FIELD_DIRECTION}
     */
    public static void requireCursorForDirection(String cursor, PageDirection direction) {
        if (direction != null && cursor == null) {
            throw new ClientInputException(CURSOR_REQUIRED_CODE, FIELD_DIRECTION,
                    FieldValidationFlag.NOT_OK, MESSAGE_CURSOR_REQUIRED);
        }
    }

    /**
     * Opens a supplied position under the binding its stated direction implies.
     *
     * <p>Assumptions: a position presented with a backward direction is the LEADING boundary of the page
     * the caller is on, so it opens under the backward binding; one presented forward is the trailing
     * boundary and opens under the forward binding. Opening both under one binding is what let either
     * boundary be replayed the other way.</p>
     *
     * @param cursorToken the sealer that opens the position; must not be {@code null}
     * @param queryName the browse's own name
     * @param subject the authenticated caller's identity; must not be {@code null}
     * @param cursor the position supplied, or {@code null} when the caller supplied none
     * @param direction the direction supplied, or {@code null} meaning forward
     * @param narrowing the filter values the walk is performed under, in the browse's fixed order
     * @return the opened key, or {@code null} when no position was supplied
     * @throws CursorToken.InvalidCursorException if the position was not minted for this browse, this
     *     caller, these filters and this direction
     */
    public static String openPosition(CursorToken cursorToken, String queryName, String subject,
            String cursor, PageDirection direction, String... narrowing) {

        if (cursor == null) {
            return null;
        }
        boolean backward = direction == PageDirection.PREVIOUS;
        return cursorToken.open(binding(queryName, subject, backward, narrowing), cursor);
    }


    /**
     * Builds a page envelope from rows already ordered ascending.
     *
     * <p>Assumptions: the boundary positions are sealed under the two bindings the caller supplies, so a
     * position minted for one browse, one caller, one filter tuple and one direction cannot be replayed
     * against any other. The seal is applied to the key of the first and last row of the WINDOW rather
     * than of the walk, so a caller paging forward from the last position lands on the row after the one
     * it saw.</p>
     *
     * <p>Refactoring Rationale: the two boundaries are sealed under DIFFERENT bindings, where one binding
     * previously served both. The leading key is what a caller sends to move backward and the trailing key
     * is what it sends to move forward, so sealing both alike made each replayable as the other -- a
     * caller could present a page's leading key with a forward direction and be positioned after a row it
     * had not reached. Composing the direction into the binding, through
     * {@link #binding(String, String, boolean, String...)}, turns that into a refusal.</p>
     *
     * @param <E> the entity type walked
     * @param <R> the response type published
     * @param rows the bounded rows in ascending key order, holding at most {@code pageSize} plus one
     * @param pageSize the number of rows the page publishes
     * @param backward whether the walk was backward, in which case the surplus row is the first
     * @param backwardBinding the binding the LEADING key is sealed under, being the position a backward
     *     request moves from
     * @param forwardBinding the binding the TRAILING key is sealed under, being the position a forward
     *     request moves from
     * @param cursorToken the sealer that mints the opaque positions; must not be {@code null}
     * @param render the mapper that turns one entity into its response shape
     * @param key the accessor of the ordering key of one entity
     * @return the page envelope, never {@code null}; empty when the window holds no row
     */
    public static <E, R> PageResponse<R> page(
            List<E> rows,
            int pageSize,
            boolean backward,
            String backwardBinding,
            String forwardBinding,
            CursorToken cursorToken,
            Function<E, R> render,
            Function<E, String> key) {

        List<E> window = new ArrayList<>(rows);
        boolean more = window.size() > pageSize;
        if (more) {
            // WHY : Assumptions: which end the surplus row sits at depends on the direction walked. A
            //       backward walk was read descending from the position and reversed, so its surplus
            //       row is the EARLIEST key and now sits first; a forward walk's sits last. Removing
            //       the wrong end silently drops a row a caller should have seen.
            window.remove(backward ? 0 : window.size() - 1);
        }
        if (window.isEmpty()) {
            return PageResponse.empty();
        }
        List<R> items = new ArrayList<>(window.size());
        for (E row : window) {
            items.add(render.apply(row));
        }
        // WHY : Refactoring Rationale: no backward availability answer is published, and the leading
        //       position below is what this page owes a caller stepping back. The reference asks the
        //       backward question of the TERMINAL rather than of the table -- the browse screens hold a
        //       page ordinal in the communication area and refuse the backward key on the first page from
        //       that ordinal alone -- so the answer belongs to the client, and the shared envelope
        //       carries four members that every consumer of it declares.
        // WHY : Refactoring Rationale: forward availability is UNCONDITIONALLY true on a backward walk,
        //       where it previously reported the backward surplus. The surplus row on a backward walk lies
        //       further BACK, so reporting it as forward availability answered the wrong question: a
        //       caller that had just stepped back from a page was told nothing lay ahead of it, and the
        //       page it had come from became unreachable. The reference does the same thing this now
        //       does, and does it unconditionally -- app/app-transaction-type-db2/cbl/COTRTLIC.cbl sets
        //       CA-NEXT-PAGE-EXISTS TO TRUE at physical line 1738, at the top of its backward reader,
        //       before reading anything -- for the reason that a page reached by paging backward was
        //       reached FROM a page, which therefore exists.
        return PageResponse.ofRows(
                items,
                cursorToken.seal(backwardBinding, key.apply(window.get(0))),
                cursorToken.seal(forwardBinding, key.apply(window.get(window.size() - 1))),
                backward || more);
    }
}
