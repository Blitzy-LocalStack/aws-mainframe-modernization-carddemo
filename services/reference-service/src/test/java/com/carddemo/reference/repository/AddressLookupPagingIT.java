// =============================================================================
// services/reference-service/src/test/java/com/carddemo/reference/repository/AddressLookupPagingIT.java
// -----------------------------------------------------------------------------
// Purpose:
//      Walks each of the three seeded address allow-lists from its first page to
//      its last, against real rows in a real engine, and asserts that the union
//      of the pages is the whole seeded domain in ascending order with no row
//      repeated and none skipped.
//
// WHY (non-obvious design decisions):
//  (1) Refactoring Rationale: this class exists because the property it asserts
//      was previously asserted at the HTTP boundary against a response no
//      deployment can produce. The boundary suite stubbed ONE page carrying all
//      490 area codes, all 56 state codes and all 240 postal-prefix pairs, while
//      AddressLookupService.PAGE_SIZE is 20 and the published page schema
//      declares maxItems 20 -- so the fixture described a page 24 times the
//      published maximum and the walk that a client actually performs was never
//      exercised at all. Multi-page continuation is exactly where a keyset browse
//      breaks: an inclusive rather than strict seek repeats a row at every
//      boundary, and a mis-sealed trailing position skips one. Neither is visible
//      in a single page.
//  (2) Assumptions: the whole-domain property belongs HERE and not at the
//      boundary, because it is a property of the seed and the query together. The
//      boundary owns what a page LOOKS like; this owns what the pages ADD UP to.
//  (3) Assumptions: the service is constructed directly over the injected
//      repositories rather than obtained from a context. The shared fixture's
//      minimal application scans entities and repositories only -- deliberately,
//      so that a persistence test needs no security chain, no queue listener and
//      no interface document -- and the three mappers plus the position sealer are
//      the whole of what the service additionally needs. Constructing them here
//      costs four lines and keeps that fixture unchanged for its eleven siblings.
//  (4) Trade-offs: the walk is bounded by an explicit maximum page count rather
//      than looping until the browse reports no further page. A browse defect that
//      always reported a further page would otherwise turn a failing assertion
//      into a run that never ends, and a hung build reports nothing at all. The
//      bound is set well above the pages the seed needs, so it cannot mask a
//      short walk.
// =============================================================================
package com.carddemo.reference.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.dto.LookupPageRequest;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.PhoneAreaCodeResponse;
import com.carddemo.reference.dto.UsStateResponse;
import com.carddemo.reference.dto.UsStateZipPrefixResponse;
import com.carddemo.reference.mapper.UsPhoneAreaCodeMapper;
import com.carddemo.reference.mapper.UsStateMapper;
import com.carddemo.reference.mapper.UsStateZipPrefixMapper;
import com.carddemo.reference.service.AddressLookupService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Holds each seeded address allow-list to arriving whole across the pages a client actually reads.
 *
 * <p>Purpose: three properties are asserted per domain, and none of them is decidable from one page.
 * The union of the pages is the complete seeded population; the union carries every row exactly once,
 * so no page boundary repeats or skips; and the concatenation is in ascending order, which is the order
 * the browse declares and the order a caller's next position is computed against.
 *
 * <p>Assumptions: the row COUNTS are the seed's own, and they are stated as constants here rather than
 * read from a count query, because a walk compared against a count taken from the same table would be
 * satisfied by a query that lost the same rows the count did. The three figures are the ones the
 * sibling repository tests in this package already assert the seed loads.
 *
 * <p>Assumptions: a forward walk is asserted for all three domains and a BACKWARD walk for one. The
 * backward direction shares the position codec and the page assembly with the forward direction and
 * differs only in which comparison the query makes and whether the rows are reversed before publication,
 * so one domain establishes it; asserting it three times would restate one property in three places.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; every member below carries its own.
 */
@DisplayName("on walking a seeded allow-list across every page a client reads")
class AddressLookupPagingIT extends ReferencePersistenceBase {

    /**
     * The number of area codes the seed loads, and the size the forward walk must add up to.
     *
     * <p>Assumptions: 490 is the figure {@code UsPhoneAreaCodeRepositoryIT} asserts the seed loads, and
     * it is the total of the two classified sub-lists {@code app/cpy/CSLKPCDY.cpy} declares.</p>
     */
    private static final int SEEDED_AREA_CODES = 490;

    /**
     * The number of state codes the seed loads: the fifty states, the federal district and five
     * territories.
     */
    private static final int SEEDED_STATES = 56;

    /** The number of state-and-postal-prefix pairs the seed loads. */
    private static final int SEEDED_ZIP_PREFIXES = 240;

    /**
     * The page width the browse publishes, restated here so the arithmetic below is legible.
     *
     * <p>Assumptions: it is asserted equal to the service's own published constant rather than merely
     * copied from it, so a change to the page width fails here rather than silently changing what
     * "every page" means.</p>
     */
    private static final int PAGE_WIDTH = 20;

    /**
     * The largest number of pages any walk below is allowed to take.
     *
     * <p>Trade-offs: the walk is bounded rather than left to terminate on the browse's own report. A
     * browse that always reported a further page would otherwise hang the build, which reports nothing;
     * the bound is far above the twenty-five pages the largest domain needs, so a genuine short walk
     * still fails on its size rather than on this limit.</p>
     */
    private static final int MAX_PAGES = 64;

    /**
     * The sealing key this class constructs its position codec with.
     *
     * <p>Assumptions: at least the thirty-two bytes {@code CursorToken.MIN_KEY_LENGTH} declares, counted
     * as bytes. A shorter literal is refused by that constructor and would fail every case here for a
     * reason unrelated to paging. It seals nothing that outlives this test and is not a credential.</p>
     */
    private static final byte[] CURSOR_KEY =
            "reference-address-lookup-paging-walk-key".getBytes(StandardCharsets.US_ASCII);

    /** The position lifetime this class constructs its codec with, ample for one walk. */
    private static final Duration CURSOR_LIFETIME = Duration.ofMinutes(10);

    /** The caller every position below is sealed against, since the browse binds positions to a name. */
    private static final String SUBJECT = "REFUSR07";

    /** The seeded area-code table, injected so the walk runs against real rows. */
    @Autowired
    private UsPhoneAreaCodeRepository areaCodes;

    /** The seeded state table, injected so the walk runs against real rows. */
    @Autowired
    private StateRepository states;

    /** The seeded state-and-postal-prefix table, injected so the walk runs against real rows. */
    @Autowired
    private UsStateZipPrefixRepository zipPrefixes;

    /** The browse under test, assembled over the injected repositories before each case. */
    private AddressLookupService lookups;

    /**
     * Assembles the browse over the injected repositories, the three mappers and a position codec.
     *
     * <p>Assumptions: it is rebuilt per case rather than once for the class, because the type holds no
     * mutable state and rebuilding removes any question of one case's walk influencing another's.</p>
     */
    @BeforeEach
    void assembleBrowse() {
        this.lookups = new AddressLookupService(
                this.areaCodes, this.states, this.zipPrefixes,
                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME),
                new UsPhoneAreaCodeMapper(), new UsStateMapper(), new UsStateZipPrefixMapper());
    }

    /**
     * The forward walk over the area codes visits all 490 rows once each, in ascending order.
     *
     * <p>Assumptions: the page WIDTHS are asserted as well as the union, and the two are different
     * properties. A browse could return the whole population across pages of the wrong size -- one row
     * per page, say -- and satisfy the union while making twenty-five reads into four hundred and ninety.
     * Every page but the last must therefore carry exactly the published width.</p>
     */
    @Test
    @DisplayName("the area-code walk visits all 490 rows once each in ascending order")
    void theAreaCodeWalkVisitsEveryRowOnce() {
        assertThat(AddressLookupService.PAGE_SIZE)
                .as("the arithmetic below is written against the published page width")
                .isEqualTo(PAGE_WIDTH);

        Walk walk = walkForward(SEEDED_AREA_CODES,
                (cursor, direction) -> mapAreaCodes(this.lookups.listAreaCodes(
                        new LookupPageRequest(cursor, direction, null), SUBJECT)));

        assertThat(walk.rows()).hasSize(SEEDED_AREA_CODES);
        assertThat(new LinkedHashSet<>(walk.rows()))
                .as("no page boundary may repeat a row, which an inclusive seek would do")
                .hasSize(SEEDED_AREA_CODES);
        assertThat(walk.rows()).isSorted();
        assertThat(walk.pages())
                .as("490 rows at a published width of 20 is 25 pages, the last carrying 10")
                .isEqualTo(25);
        assertThat(walk.fullPages())
                .as("every page but the last carries exactly the published width")
                .isEqualTo(24);
    }

    /**
     * The forward walk over the states visits all 56 rows once each, in ascending order.
     *
     * <p>Assumptions: this domain is the one that crosses a page boundary with a REMAINDER smaller than
     * half a page, sixteen rows on its third page, so it exercises a final short page distinct in size
     * from the other two domains' final pages. Asserting all three domains rather than one is what makes
     * the walk independent of any single population's arithmetic.</p>
     */
    @Test
    @DisplayName("the state walk visits all 56 rows once each in ascending order")
    void theStateWalkVisitsEveryRowOnce() {
        Walk walk = walkForward(SEEDED_STATES,
                (cursor, direction) -> mapStates(this.lookups.listStates(
                        new LookupPageRequest(cursor, direction, null), SUBJECT)));

        assertThat(walk.rows()).hasSize(SEEDED_STATES);
        assertThat(new LinkedHashSet<>(walk.rows())).hasSize(SEEDED_STATES);
        assertThat(walk.rows()).isSorted();
        assertThat(walk.pages())
                .as("56 rows at a published width of 20 is 3 pages, the last carrying 16")
                .isEqualTo(3);
        assertThat(walk.fullPages()).isEqualTo(2);
    }

    /**
     * The forward walk over the postal prefixes visits all 240 rows once each, in ascending order.
     *
     * <p>Assumptions: this domain divides EXACTLY by the published width, so its last page is full and
     * the browse must still report no further page after it. That is the boundary a surplus-row lookahead
     * gets wrong in the opposite direction from a short final page: a browse that reported a further page
     * after an exactly-full last page would send a client to an empty twelfth page.</p>
     */
    @Test
    @DisplayName("the prefix walk visits all 240 rows once each and stops after an exactly full page")
    void thePrefixWalkVisitsEveryRowOnce() {
        Walk walk = walkForward(SEEDED_ZIP_PREFIXES,
                (cursor, direction) -> mapZipPrefixes(this.lookups.listZipPrefixes(
                        new LookupPageRequest(cursor, direction, null), SUBJECT)));

        assertThat(walk.rows()).hasSize(SEEDED_ZIP_PREFIXES);
        assertThat(new LinkedHashSet<>(walk.rows())).hasSize(SEEDED_ZIP_PREFIXES);
        assertThat(walk.rows()).isSorted();
        assertThat(walk.pages())
                .as("240 rows at a published width of 20 is exactly 12 full pages")
                .isEqualTo(12);
        assertThat(walk.fullPages())
                .as("every page is full, and the walk must still end after the twelfth")
                .isEqualTo(12);
    }

    /**
     * The backward walk from the far end of the states retraces the forward walk exactly reversed.
     *
     * <p>Purpose: a backward page is published in ascending order even though it is read descending, so a
     * browse that forgot to reverse its rows would produce pages that are individually plausible and
     * collectively wrong. Comparing the backward traversal against the forward one is what detects that,
     * and a size assertion alone cannot.</p>
     *
     * <p>Assumptions: the backward walk starts from the LEADING position of the last forward page, which
     * is the position a client at the end of the list holds, and it therefore visits every row except
     * those on that final page. The expected remainder is computed from the forward walk rather than
     * restated, so the two cannot drift apart.</p>
     */
    @Test
    @DisplayName("the backward walk over the states retraces the forward walk in reverse")
    void theBackwardStateWalkRetracesTheForwardWalk() {
        List<String> forward = new ArrayList<>();
        String leadingPositionOfLastPage = null;
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            PageResponse<UsStateResponse> answered = this.lookups.listStates(
                    new LookupPageRequest(cursor, cursor == null ? null : PageDirection.NEXT, null),
                    SUBJECT);
            answered.items().forEach(row -> forward.add(row.stateCd()));
            leadingPositionOfLastPage = answered.firstKey();
            if (!answered.hasNext()) {
                break;
            }
            cursor = answered.lastKey();
        }

        assertThat(forward).hasSize(SEEDED_STATES);

        List<String> backward = new ArrayList<>();
        String reverseCursor = leadingPositionOfLastPage;
        for (int page = 0; page < MAX_PAGES && reverseCursor != null; page++) {
            PageResponse<UsStateResponse> answered = this.lookups.listStates(
                    new LookupPageRequest(reverseCursor, PageDirection.PREVIOUS, null), SUBJECT);
            List<String> published = new ArrayList<>();
            answered.items().forEach(row -> published.add(row.stateCd()));

            assertThat(published)
                    .as("a backward page is PUBLISHED ascending even though it is read descending")
                    .isSorted();

            backward.addAll(0, published);
            reverseCursor = published.isEmpty() ? null : answered.firstKey();
            if (published.isEmpty()) {
                break;
            }
        }

        // WHY : Assumptions: the expected remainder is DERIVED from the forward walk rather than written
        //       down. The backward walk begins at the leading position of the final forward page, so it
        //       cannot reach the rows on that page; computing the expectation from the forward result is
        //       what keeps the two walks describing one list instead of two independent claims.
        int rowsOnFinalForwardPage = SEEDED_STATES % PAGE_WIDTH == 0
                ? PAGE_WIDTH
                : SEEDED_STATES % PAGE_WIDTH;
        List<String> expected = forward.subList(0, SEEDED_STATES - rowsOnFinalForwardPage);

        assertThat(backward)
                .as("the backward traversal must retrace the forward one, row for row")
                .containsExactlyElementsOf(expected);
    }

    /**
     * Walks a browse forward from its first page and records what the pages add up to.
     *
     * @param expectedRows the population size, used only to bound the assertion the caller then makes;
     *     the walk itself stops when the browse reports no further page
     * @param browse a function answering one page for a position and a direction, both {@code null} on
     *     the first call
     * @return the concatenated rows, the page count and how many pages carried the full published width
     */
    private Walk walkForward(int expectedRows, BiFunction<String, PageDirection, Page> browse) {
        List<String> rows = new ArrayList<>(expectedRows);
        int pages = 0;
        int fullPages = 0;
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Page answered = browse.apply(cursor, cursor == null ? null : PageDirection.NEXT);
            pages++;
            if (answered.rows().size() == PAGE_WIDTH) {
                fullPages++;
            }
            rows.addAll(answered.rows());
            if (!answered.hasNext()) {
                break;
            }
            cursor = answered.trailingPosition();
        }
        return new Walk(rows, pages, fullPages);
    }

    /**
     * Reduces an area-code page to the three values a walk needs from it.
     *
     * @param answered the page the browse returned
     * @return the page's keys, its trailing position and whether a further page exists
     */
    private static Page mapAreaCodes(PageResponse<PhoneAreaCodeResponse> answered) {
        List<String> rows = new ArrayList<>(answered.items().size());
        for (PhoneAreaCodeResponse row : answered.items()) {
            rows.add(row.areaCd());
        }
        return new Page(rows, answered.lastKey(), answered.hasNext());
    }

    /**
     * Reduces a state page to the three values a walk needs from it.
     *
     * @param answered the page the browse returned
     * @return the page's keys, its trailing position and whether a further page exists
     */
    private static Page mapStates(PageResponse<UsStateResponse> answered) {
        List<String> rows = new ArrayList<>(answered.items().size());
        for (UsStateResponse row : answered.items()) {
            rows.add(row.stateCd());
        }
        return new Page(rows, answered.lastKey(), answered.hasNext());
    }

    /**
     * Reduces a postal-prefix page to the three values a walk needs from it.
     *
     * @param answered the page the browse returned
     * @return the page's keys, its trailing position and whether a further page exists
     */
    private static Page mapZipPrefixes(PageResponse<UsStateZipPrefixResponse> answered) {
        List<String> rows = new ArrayList<>(answered.items().size());
        for (UsStateZipPrefixResponse row : answered.items()) {
            rows.add(row.stateZipCd());
        }
        return new Page(rows, answered.lastKey(), answered.hasNext());
    }

    /**
     * One page reduced to what a walk reads from it, independent of which domain produced it.
     *
     * <p>Assumptions: the three browses answer three different row types, and a walk cares about none of
     * the differences. Reducing each to its keys here is what lets one walk serve all three rather than
     * three near-identical walks differing only in a getter.</p>
     *
     * @param rows the row keys the page published, in the order it published them
     * @param trailingPosition the sealed position naming the last row published, which the next request
     *     carries
     * @param hasNext whether the browse found a row beyond this page
     */
    private record Page(List<String> rows, String trailingPosition, boolean hasNext) {
    }

    /**
     * What a completed walk adds up to.
     *
     * @param rows every row key the walk visited, in the order the pages published them
     * @param pages how many pages the walk read
     * @param fullPages how many of those pages carried exactly the published page width
     */
    private record Walk(List<String> rows, int pages, int fullPages) {
    }
}
