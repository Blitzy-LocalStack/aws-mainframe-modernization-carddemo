package com.carddemo.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the keyset page envelope: that it carries boundaries rather than an offset, that its
 * cursors are sealed tokens, and that the two states the reference browse can genuinely produce are
 * both expressible while the one a caller cannot act on is refused.
 *
 * <p>Assumptions: every cursor used here is produced by sealing a raw composite through
 * {@link CursorToken}, because the envelope requires sealed tokens and a hand-written string would
 * only test the rejection path. The key material and the lifetime are fixed constants so a sealed
 * token is reproducible and no test depends on a clock beyond the lifetime it sets.</p>
 *
 * <p>Assumptions: the envelope carries no page number, no offset and no total count, and the tests
 * assert that absence structurally by enumerating the record's components. An offset reintroduced as a
 * component would skip and repeat rows under concurrent inserts, which is the failure keyset paging
 * exists to avoid, so its absence is a contract rather than an omission.</p>
 */
class PageResponseTest {

    /** Key material of the required width, fixed so a sealed token is reproducible across runs. */
    private static final byte[] KEY =
        "carddemo-page-response-test-key-material".getBytes(StandardCharsets.UTF_8);

    /** A lifetime long enough that no assertion here is affected by token expiry. */
    private static final Duration LIFETIME = Duration.ofHours(1);

    /** The binding a cursor is sealed against, standing in for one browse's identity. */
    private static final String BINDING = "card-list";

    /** The sealer every fixture cursor is produced by. */
    private static final CursorToken SEALER = new CursorToken(KEY, LIFETIME);

    /**
     * Seals a raw composite cursor into the opaque form the envelope requires.
     *
     * @param rawCursor the raw composite key a keyset query produced, a {@link String}
     * @return the sealed token form of that cursor
     */
    private static String cursor(String rawCursor) {
        return SEALER.seal(BINDING, rawCursor);
    }

    /**
     * Confirms the envelope carries exactly five components and none of them is an offset.
     *
     * <p>Assumptions: this is asserted reflectively over the record's components rather than by reading
     * the source, so a sixth component added later fails here regardless of what it is named. The five
     * permitted names are listed explicitly because the point is the closed set, not the count.</p>
     *
     * <p>Refactoring Rationale: the closed set gained {@code hasPrevious} and the reason is recorded
     * here as well as on the type, because a reader arriving at this assertion is entitled to know why
     * the number moved. Backward availability was previously read off {@code firstKey}, which every page
     * carrying rows supplies, so the opening page advertised an earlier page that did not exist and a
     * client following the advertisement replaced its rows with an empty page. The three names this test
     * still forbids -- offset, total and page -- are unchanged, because none of them was ever the
     * problem: the fix is one more key-derived fact, not a positional one.</p>
     */
    @Test
    @DisplayName("carries exactly five components, none of them an offset or a page number")
    void carriesExactlyFiveComponents() {
        List<String> components = Arrays.stream(PageResponse.class.getRecordComponents())
            .map(component -> component.getName())
            .toList();

        assertThat(components)
            .containsExactly("items", "firstKey", "lastKey", "hasNext", "hasPrevious");
        assertThat(components).noneSatisfy(name -> assertThat(name.toLowerCase())
            .contains("offset"));
        assertThat(components).noneSatisfy(name -> assertThat(name.toLowerCase())
            .contains("total"));
        assertThat(components).noneSatisfy(name -> assertThat(name.toLowerCase())
            .contains("page"));
    }

    /**
     * Confirms the empty page names no boundary and reports no further page.
     */
    @Test
    @DisplayName("an empty page names no boundary and reports no further page")
    void emptyPageNamesNoBoundary() {
        PageResponse<String> page = PageResponse.empty();

        assertThat(page.items()).isEmpty();
        assertThat(page.firstKey()).isNull();
        assertThat(page.lastKey()).isNull();
        assertThat(page.hasNext()).isFalse();
    }

    /**
     * Confirms a page carrying rows round trips its rows, its boundaries and its forward indicator.
     */
    @Test
    @DisplayName("a page carrying rows round trips its rows, boundaries and forward indicator")
    void populatedPageRoundTrips() {
        String first = cursor("4111111111111111" + "00000000001");
        String last = cursor("4111111111111199" + "00000000099");

        PageResponse<String> page =
            PageResponse.ofRows(List.of("row-1", "row-2", "row-3"), first, last, true, true);

        assertThat(page.items()).containsExactly("row-1", "row-2", "row-3");
        assertThat(page.firstKey()).isEqualTo(first);
        assertThat(page.lastKey()).isEqualTo(last);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.hasPrevious()).isTrue();
    }

    /**
     * Confirms a page carrying rows must name both of its boundaries.
     *
     * <p>Assumptions: a page whose ends a caller cannot name is a page it cannot page away from, so the
     * refusal is the useful behaviour. Both directions of the omission are tested because a check
     * written for one boundary only would let the other through.</p>
     */
    @Test
    @DisplayName("a page carrying rows is refused when either boundary is missing")
    void populatedPageRequiresBothBoundaries() {
        String sealed = cursor("4111111111111111" + "00000000001");
        List<String> rows = List.of("row-1");

        assertThatThrownBy(() -> PageResponse.ofRows(rows, null, sealed, false, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageResponse.ofRows(rows, sealed, null, false, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms reporting a further page without the position to request it from is refused.
     *
     * <p>Assumptions: this is the one combination a caller genuinely cannot act on -- told to continue
     * and given nowhere to continue from -- which is why it is the only one of the four boundary
     * combinations that is forbidden outright.</p>
     */
    @Test
    @DisplayName("reporting a further page without a forward cursor is refused")
    void furtherPageWithoutAForwardCursorIsRefused() {
        assertThatThrownBy(() -> PageResponse.ofRows(List.of(), null, null, true, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms reporting an earlier page without the position to request it from is refused.
     *
     * <p>Assumptions: this is the backward mirror of the forbidden forward combination, and it is
     * forbidden for the same reason: a caller told it may step back with nowhere to step back from has
     * been given an instruction it cannot carry out.</p>
     */
    @Test
    @DisplayName("reporting an earlier page without a backward cursor is refused")
    void earlierPageWithoutABackwardCursorIsRefused() {
        assertThatThrownBy(() -> PageResponse.ofRows(List.of(), null, null, false, true))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PageResponse.ofFilteredEmpty(null, null, true))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms the opening page names its leading boundary while reporting no earlier page.
     *
     * <p>Refactoring Rationale: this is the exact state the earlier four-component envelope could not
     * express, and it is asserted directly so that no future revision reinstates the derivation. The page
     * names its own first row -- which a later backward request needs -- and simultaneously reports that
     * nothing precedes it, which is what the reference does at lines 1301 and 1302 of
     * {@code app/cbl/COCRDLIC.cbl} when a backward request arrives on the first page.</p>
     */
    @Test
    @DisplayName("the opening page names its leading boundary and still reports no earlier page")
    void openingPageNamesItsLeadingBoundaryAndReportsNoEarlierPage() {
        String first = cursor("4111111111111111" + "00000000001");
        String last = cursor("4111111111111150" + "00000000050");

        PageResponse<String> opening =
            PageResponse.ofRows(List.of("row-1", "row-2"), first, last, true, false);

        assertThat(opening.firstKey()).isEqualTo(first);
        assertThat(opening.hasPrevious()).isFalse();
        assertThat(opening.hasNext()).isTrue();
    }

    /**
     * Confirms a final page may still name its trailing boundary.
     *
     * <p>Assumptions: the implication is deliberately one-directional. A present trailing cursor with no
     * further page is the last page of a set, and it still names the boundary a caller paging BACKWARD
     * off it seeks from, so asserting a biconditional here would refuse the last page of every browse.
     * This test exists so nobody restores the stricter check.</p>
     */
    @Test
    @DisplayName("a final page may still name its trailing boundary")
    void finalPageMayNameItsTrailingBoundary() {
        String first = cursor("4111111111111111" + "00000000001");
        String last = cursor("4111111111111150" + "00000000050");

        PageResponse<String> page = PageResponse.ofRows(List.of("row-1"), first, last, false, true);

        assertThat(page.hasNext()).isFalse();
        assertThat(page.lastKey()).isEqualTo(last);
        assertThat(page.hasPrevious()).isTrue();
    }

    /**
     * Confirms an empty page may still name a boundary, matching the reference post-read filter.
     *
     * <p>Assumptions: the reference card list applies its filter AFTER reading, so a read whose every
     * record was filtered away has the keys at which scanning stopped and no returned row to take them
     * from. Admitting that state is what lets four components carry what an earlier design needed seven
     * for, so it is asserted rather than left as an accident of the constructor.</p>
     */
    @Test
    @DisplayName("an empty page may still name the position scanning stopped at")
    void emptyPageMayNameWhereScanningStopped() {
        String forward = cursor("4111111111111199" + "00000000099");
        String backward = cursor("4111111111111100" + "00000000001");

        PageResponse<String> filtered = PageResponse.ofFilteredEmpty(forward, backward, true);

        assertThat(filtered.items()).isEmpty();
        assertThat(filtered.lastKey()).isEqualTo(forward);
        assertThat(filtered.firstKey()).isEqualTo(backward);
        assertThat(filtered.hasNext()).isTrue();
        assertThat(filtered.hasPrevious()).isTrue();
    }

    /**
     * Confirms a filtered-empty page with no forward position reports no further page.
     */
    @Test
    @DisplayName("a filtered-empty page with no forward position reports no further page")
    void filteredEmptyWithoutForwardPositionReportsNoFurtherPage() {
        PageResponse<String> exhausted = PageResponse.ofFilteredEmpty(null, null, false);

        assertThat(exhausted.items()).isEmpty();
        assertThat(exhausted.hasNext()).isFalse();
        assertThat(exhausted.hasPrevious()).isFalse();
        assertThat(exhausted.lastKey()).isNull();
    }

    /**
     * Confirms a null row list is refused rather than treated as an empty page.
     *
     * <p>Assumptions: admitting a null would make two encodings of "no rows" reachable, and the
     * migration already carries three spellings of absence in its baseline markers. One encoding of an
     * exhausted page is the whole point of refusing here.</p>
     */
    @Test
    @DisplayName("a null row list is refused rather than read as an empty page")
    void nullRowListIsRefused() {
        assertThatThrownBy(() -> PageResponse.ofRows(null, null, null, false, false))
            .isInstanceOf(NullPointerException.class);
    }

    /**
     * Confirms the row list is copied at construction so a later mutation cannot alter the page.
     *
     * <p>Assumptions: a record is only as immutable as its components, so retaining the caller's list
     * would leave a response whose rows change after it was assembled. Mutating the source list after
     * construction is the only way to demonstrate the copy actually happened.</p>
     */
    @Test
    @DisplayName("the row list is copied, so a later mutation of the source cannot alter the page")
    void rowListIsCopiedAtConstruction() {
        List<String> mutable = new ArrayList<>(List.of("row-1"));
        String first = cursor("4111111111111111" + "00000000001");
        String last = cursor("4111111111111111" + "00000000001");

        PageResponse<String> page = PageResponse.ofRows(mutable, first, last, false, false);
        mutable.add("row-2-added-after-construction");

        assertThat(page.items()).containsExactly("row-1");
        assertThatThrownBy(() -> page.items().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    /**
     * Confirms a blank boundary is normalised to the single absent spelling the accessors promise.
     *
     * <p>Assumptions: absence arrives in whichever of the baseline's spellings a caller's data carried,
     * and collapsing them at construction is what keeps the stored state and the returned state
     * identical. Without it a caller would have to know which of two it was looking at.</p>
     *
     * @param blankBoundary a boundary value that carries no position
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   "})
    @DisplayName("a blank boundary is normalised to the single absent spelling")
    void blankBoundaryIsNormalised(String blankBoundary) {
        PageResponse<String> page =
            PageResponse.ofRows(List.of(), blankBoundary, blankBoundary, false, false);

        assertThat(page.firstKey()).isNull();
        assertThat(page.lastKey()).isNull();
    }

    /**
     * Confirms a raw unsealed composite key is refused as a cursor.
     *
     * <p>Assumptions: this is the enforcement the envelope previously lacked. It described its cursors
     * as opaque and nothing held a caller to it, so a raw keyset key -- a card number followed by an
     * account identifier -- could travel to a client verbatim. Refusing it is what makes the opacity
     * real, and the value used here is exactly the shape the reference browse produces.</p>
     */
    @Test
    @DisplayName("a raw unsealed composite key is refused as a cursor")
    void rawCompositeKeyIsRefusedAsACursor() {
        String rawCursor = "4111111111111111" + "00000000001";

        assertThat(CursorToken.hasSealedShape(rawCursor)).isFalse();
        assertThatThrownBy(
            () -> PageResponse.ofRows(List.of("row-1"), rawCursor, rawCursor, false, false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Confirms a sealed cursor carries neither component of the raw key it was sealed from.
     *
     * <p>Assumptions: the envelope is what a browse returns to a browser, so a cursor that leaked the
     * card number would put a protected value in a query string, a bookmark and a referrer header. The
     * assertion is on the ABSENCE of both components rather than on the token's format, because the
     * format may change while the absence must not.</p>
     */
    @Test
    @DisplayName("a sealed cursor carries neither the card number nor the account identifier")
    void sealedCursorLeaksNeitherComponent() {
        String cardNumber = "4111111111111111";
        String accountId = "00000000001";
        String sealed = cursor(cardNumber + accountId);

        PageResponse<String> page = PageResponse.ofRows(List.of("row-1"), sealed, sealed, false, false);

        assertThat(page.firstKey()).doesNotContain(cardNumber).doesNotContain(accountId);
        assertThat(page.lastKey()).doesNotContain(cardNumber).doesNotContain(accountId);
    }
}
