package com.carddemo.reference.service;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
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

    /**
     * Builds a page envelope from rows already ordered ascending.
     *
     * <p>Assumptions: the boundary positions are sealed under the binding the caller supplies, so a
     * position minted for one browse cannot be replayed against another. The seal is applied to the key
     * of the first and last row of the WINDOW rather than of the walk, so a caller paging forward from
     * the last position lands on the row after the one it saw.</p>
     *
     * @param <E> the entity type walked
     * @param <R> the response type published
     * @param rows the bounded rows in ascending key order, holding at most {@code pageSize} plus one
     * @param pageSize the number of rows the page publishes
     * @param backward whether the walk was backward, in which case the surplus row is the first
     * @param binding the cursor binding of this browse
     * @param cursorToken the sealer that mints the opaque positions; must not be {@code null}
     * @param render the mapper that turns one entity into its response shape
     * @param key the accessor of the ordering key of one entity
     * @return the page envelope, never {@code null}; empty when the window holds no row
     */
    public static <E, R> PageResponse<R> page(
            List<E> rows,
            int pageSize,
            boolean backward,
            String binding,
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
        return PageResponse.ofRows(
                items,
                cursorToken.seal(binding, key.apply(window.get(0))),
                cursorToken.seal(binding, key.apply(window.get(window.size() - 1))),
                more);
    }
}
