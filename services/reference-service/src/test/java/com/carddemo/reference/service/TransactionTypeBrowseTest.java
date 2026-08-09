package com.carddemo.reference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.reference.domain.TransactionType;
import com.carddemo.reference.dto.PageDirection;
import com.carddemo.reference.dto.TransactionTypeListRequest;
import com.carddemo.reference.dto.TransactionTypeResponse;
import com.carddemo.reference.repository.TransactionCategoryRepository;
import com.carddemo.reference.repository.TransactionTypeRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * Verifies the transaction-type browse: its window, its surplus row and its two filters.
 *
 * <p>This class exists because the browse had no test at all. Two defects lived in that gap and neither
 * could have been caught without one. The published window was ten rows where
 * {@code app/app-transaction-type-db2/cbl/COTRTLIC.cbl} declares seven at physical line 60, and the
 * type-code and description filters were validated, documented and published and then never passed to a
 * query, so a narrowed request was answered with the whole table.
 */
class TransactionTypeBrowseTest {

    /** The key browse positions are sealed under; its value is immaterial beyond its length. */
    private static final byte[] CURSOR_KEY =
            "carddemo-reference-type-cursor-k!".repeat(2).getBytes(StandardCharsets.UTF_8);

    /** How long a sealed position stays redeemable; generous, because no case asserts expiry. */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /**
     * The authenticated caller every case pages as.
     *
     * <p>Assumptions: a subject is now part of every cursor binding, so a case that sealed a position
     * under one name and opened it under another would be asserting the refusal rather than the walk.
     * One constant keeps every positive case on one identity and lets the refusal cases state the second
     * identity explicitly.</p>
     */
    private static final String SUBJECT = "REFUSER1";

    /**
     * Builds a run of types with sequential two-digit codes.
     *
     * @param count how many rows to build
     * @return the rows in ascending code order, never {@code null}
     */
    private static List<TransactionType> rows(int count) {
        List<TransactionType> built = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            String code = String.format("%02d", index);
            built.add(new TransactionType(code, "Description " + code));
        }
        return built;
    }

    /**
     * Builds a request carrying only the parts a case needs.
     *
     * @param cursor the sealed position, or {@code null} for a first page
     * @param direction the direction, or {@code null} for forward
     * @param typeCode the type-code filter, or {@code null} for none
     * @param description the description filter, or {@code null} for none
     * @return the request, never {@code null}
     */
    private static TransactionTypeListRequest request(String cursor, PageDirection direction,
            String typeCode, String description) {
        return new TransactionTypeListRequest(cursor, direction, typeCode, description);
    }

    /**
     * Confirms the published window is seven rows and the eighth row only sets the flag.
     *
     * <p>Assumptions: eight rows are offered and seven are published, which is the property the constant
     * governs. At ten a page carried three rows the baseline never showed together, so every page
     * boundary and every sealed position differed from the reference.
     */
    @Test
    @DisplayName("the window is seven rows and the surplus eighth row only sets the further-page flag")
    void theWindowIsSevenRows() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        when(types.findAllByOrderByTypeCdAsc(any(Limit.class))).thenReturn(rows(8));

        PageResponse<TransactionTypeResponse> page =
                new TransactionTypeService(types, mock(TransactionCategoryRepository.class)).list(request(null, null, null, null),
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME), SUBJECT);

        assertThat(page.items()).hasSize(TransactionTypeService.PAGE_SIZE);
        assertThat(TransactionTypeService.PAGE_SIZE).isEqualTo(7);
        assertThat(page.items()).extracting(TransactionTypeResponse::typeCd)
                .containsExactly("01", "02", "03", "04", "05", "06", "07");
        assertThat(page.hasNext()).isTrue();
    }

    /**
     * Confirms the repository is asked for exactly one row more than the window.
     *
     * <p>Assumptions: the surplus row is how the baseline settles the flag too, at physical lines 1657 to
     * 1673, rather than by a count query. Asking for the window exactly would leave the flag unknowable.
     */
    @Test
    @DisplayName("the repository is asked for the window plus one surplus row")
    void theRepositoryIsAskedForOneSurplusRow() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        when(types.findAllByOrderByTypeCdAsc(any(Limit.class))).thenReturn(rows(8));

        new TransactionTypeService(types, mock(TransactionCategoryRepository.class)).list(request(null, null, null, null),
                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME), SUBJECT);

        verify(types).findAllByOrderByTypeCdAsc(Limit.of(8));
    }

    /**
     * Confirms a full page with nothing beyond it reports no further page.
     */
    @Test
    @DisplayName("exactly seven rows with no surplus reports no further page")
    void aFullPageWithNoSurplusReportsNoFurtherPage() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        when(types.findAllByOrderByTypeCdAsc(any(Limit.class))).thenReturn(rows(7));

        PageResponse<TransactionTypeResponse> page =
                new TransactionTypeService(types, mock(TransactionCategoryRepository.class)).list(request(null, null, null, null),
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME), SUBJECT);

        assertThat(page.items()).hasSize(7);
        assertThat(page.hasNext())
                .as("the seed migration loads exactly seven types, so seeded data alone is one page")
                .isFalse();
    }

    /**
     * Confirms a type-code filter reaches the query rather than being discarded.
     */
    @Test
    @DisplayName("a type-code filter reaches the filtered forward query")
    void aTypeCodeFilterReachesTheQuery() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        when(types.countFilterMatches(eq("03"), isNull())).thenReturn(1L);
        when(types.findFilteredPageAfter(eq("03"), isNull(), isNull(), any(Limit.class)))
                .thenReturn(List.of(new TransactionType("03", "Description 03")));

        PageResponse<TransactionTypeResponse> page =
                new TransactionTypeService(types, mock(TransactionCategoryRepository.class)).list(request(null, null, "03", null),
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME), SUBJECT);

        assertThat(page.items()).extracting(TransactionTypeResponse::typeCd).containsExactly("03");
        verify(types).findFilteredPageAfter(eq("03"), isNull(), isNull(), any(Limit.class));
        verify(types, never()).findAllByOrderByTypeCdAsc(any(Limit.class));
    }

    /**
     * Confirms a description filter reaches the query as an escaped containment pattern.
     *
     * <p>Assumptions: the expected value is taken from the repository's own helper rather than written
     * out, so this asserts that the service uses that helper and not that the helper produces a
     * particular string -- which is the helper's own contract to state.
     */
    @Test
    @DisplayName("a description filter reaches the filtered forward query already escaped")
    void aDescriptionFilterReachesTheQuery() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        String expected = TransactionTypeRepository.descriptionFilterPattern("Purchase");
        when(types.countFilterMatches(isNull(), eq(expected))).thenReturn(2L);
        when(types.findFilteredPageAfter(isNull(), eq(expected), isNull(), any(Limit.class)))
                .thenReturn(rows(2));

        new TransactionTypeService(types, mock(TransactionCategoryRepository.class)).list(request(null, null, null, "Purchase"),
                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME), SUBJECT);

        verify(types).findFilteredPageAfter(isNull(), eq(expected), isNull(), any(Limit.class));
    }

    /**
     * Confirms an unfiltered browse still uses the derived walks and asks for no count.
     */
    @Test
    @DisplayName("an unfiltered browse uses the derived walks and runs no count query")
    void anUnfilteredBrowseUsesTheDerivedWalks() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        when(types.findAllByOrderByTypeCdAsc(any(Limit.class))).thenReturn(rows(3));

        new TransactionTypeService(types, mock(TransactionCategoryRepository.class)).list(request(null, null, null, "   "),
                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME), SUBJECT);

        verify(types).findAllByOrderByTypeCdAsc(any(Limit.class));
        verify(types, never()).countFilterMatches(any(), any());
        verify(types, never()).findFilteredPageAfter(any(), any(), any(), any(Limit.class));
    }

    /**
     * Confirms the forward walk from a position excludes the row the caller already holds.
     */
    @Test
    @DisplayName("a forward page from a position reads strictly past it")
    void aForwardPageReadsStrictlyPastThePosition() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        CursorToken sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        String cursor = sealer.seal(forwardBinding(null, null), "04");
        when(types.findByTypeCdGreaterThanOrderByTypeCdAsc(eq("04"), any(Limit.class)))
                .thenReturn(rows(2));

        new TransactionTypeService(types, mock(TransactionCategoryRepository.class)).list(request(cursor, PageDirection.NEXT, null, null),
                sealer, SUBJECT);

        verify(types).findByTypeCdGreaterThanOrderByTypeCdAsc(eq("04"), any(Limit.class));
    }

    /**
     * Confirms a filtered backward page reads before the position and renders ascending.
     */
    @Test
    @DisplayName("a filtered backward page reads before the position and renders ascending")
    void aFilteredBackwardPageRendersAscending() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        CursorToken sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        String cursor = sealer.seal(
                backwardBinding(null, TransactionTypeRepository.descriptionFilterPattern("Description")),
                "09");
        String expected = TransactionTypeRepository.descriptionFilterPattern("Description");
        when(types.countFilterMatches(isNull(), eq(expected))).thenReturn(9L);
        when(types.findFilteredPageBefore(isNull(), eq(expected), eq("09"), any(Limit.class)))
                .thenReturn(rows(3).reversed());

        PageResponse<TransactionTypeResponse> page = new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                .list(request(cursor, PageDirection.PREVIOUS, null, "Description"), sealer, SUBJECT);

        verify(types).findFilteredPageBefore(isNull(), eq(expected), eq("09"), any(Limit.class));
        assertThat(page.items()).extracting(TransactionTypeResponse::typeCd)
                .as("a backward walk reads descending and is reversed for display")
                .containsExactly("01", "02", "03");
    }

    /**
     * Confirms a filter matching nothing anywhere is a field refusal rather than an empty page.
     *
     * <p>Assumptions: this is {@code 1290-CROSS-EDITS} at physical lines 1239 to 1267. Reporting it as an
     * empty page would leave a caller paging forever through a filter that can never match.
     */
    @Test
    @DisplayName("a filter matching no row anywhere is a field refusal carrying the verbatim sentence")
    void aFilterMatchingNothingIsAFieldRefusal() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        when(types.countFilterMatches(eq("99"), isNull())).thenReturn(0L);

        assertThatThrownBy(() -> new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                .list(request(null, null, "99", null), new CursorToken(CURSOR_KEY, CURSOR_LIFETIME),
                        SUBJECT))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(TransactionTypeService.MESSAGE_NO_RECORDS_FOR_FILTER)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ClientInputException.class))
                .extracting(ClientInputException::fields)
                .isEqualTo(List.of(TransactionTypeService.FIELD_TYPE_CODE));
        verify(types, never()).findFilteredPageAfter(any(), any(), any(), any(Limit.class));
    }

    /**
     * Confirms only the filters the caller supplied are named in the refusal.
     *
     * <p>Assumptions: this matches the two guarded flag assignments at physical lines 1253 to 1259.
     * Naming a filter the caller left absent would mark a control it never filled in.
     */
    @Test
    @DisplayName("the refusal names only the filters the caller supplied")
    void theRefusalNamesOnlySuppliedFilters() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        String expected = TransactionTypeRepository.descriptionFilterPattern("Nothing");
        when(types.countFilterMatches(isNull(), eq(expected))).thenReturn(0L);

        assertThatThrownBy(() -> new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                .list(request(null, null, null, "Nothing"),
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME), SUBJECT))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ClientInputException.class))
                .extracting(ClientInputException::fields)
                .isEqualTo(List.of(TransactionTypeService.FIELD_DESCRIPTION));
    }

    /**
     * Confirms both filters are named when both were supplied.
     */
    @Test
    @DisplayName("both filters are named when both were supplied")
    void bothFiltersAreNamedWhenBothWereSupplied() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        String expected = TransactionTypeRepository.descriptionFilterPattern("Nothing");
        when(types.countFilterMatches(eq("99"), eq(expected))).thenReturn(0L);

        assertThatThrownBy(() -> new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                .list(request(null, null, "99", "Nothing"),
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME), SUBJECT))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ClientInputException.class))
                .extracting(ClientInputException::fields)
                .isEqualTo(List.of(TransactionTypeService.FIELD_TYPE_CODE,
                        TransactionTypeService.FIELD_DESCRIPTION));
    }

    /**
     * Composes the binding a FORWARD position of this browse is sealed under.
     *
     * <p>Assumptions: a case that seals a position must seal it under the same four facts the service
     * opens it under -- browse, caller, filters, direction -- or it is asserting the refusal rather than
     * the walk. These two helpers exist so a case states which direction's position it is minting, which
     * is the fact that is easiest to get silently wrong.</p>
     *
     * @param typeCodeFilter the normalised type-code filter, or {@code null} for none
     * @param descriptionFilter the normalised description filter, or {@code null} for none
     * @return the binding a trailing position is sealed under, never {@code null}
     */
    private static String forwardBinding(String typeCodeFilter, String descriptionFilter) {
        return ReferencePaging.binding(TransactionTypeService.CURSOR_BINDING, SUBJECT, false,
                typeCodeFilter, descriptionFilter);
    }

    /**
     * Composes the binding a BACKWARD position of this browse is sealed under.
     *
     * @param typeCodeFilter the normalised type-code filter, or {@code null} for none
     * @param descriptionFilter the normalised description filter, or {@code null} for none
     * @return the binding a leading position is sealed under, never {@code null}
     */
    private static String backwardBinding(String typeCodeFilter, String descriptionFilter) {
        return ReferencePaging.binding(TransactionTypeService.CURSOR_BINDING, SUBJECT, true,
                typeCodeFilter, descriptionFilter);
    }

    /**
     * Confirms a position minted for one caller is refused for another.
     *
     * <p>Assumptions: this is asserted from OUTSIDE the service, by minting under a second subject and
     * presenting the result, because the property under test is that the seal carries the subject at all.
     * A test that inspected the binding string would pass while the seal ignored it.</p>
     */
    @Test
    @DisplayName("a position issued to one caller is refused for another")
    void aPositionIssuedToOneCallerIsRefusedForAnother() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        CursorToken sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        String foreign = sealer.seal(
                ReferencePaging.binding(TransactionTypeService.CURSOR_BINDING, "OTHERUSR", false,
                        null, null),
                "04");

        assertThatThrownBy(() ->
                new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                        .list(request(foreign, PageDirection.NEXT, null, null), sealer, SUBJECT))
                .isInstanceOf(CursorToken.InvalidCursorException.class);
    }

    /**
     * Confirms a position minted under one filter is refused once the filter changes.
     */
    @Test
    @DisplayName("a position issued under one filter is refused once the filter changes")
    void aPositionIssuedUnderOneFilterIsRefusedWhenTheFilterChanges() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        CursorToken sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        String unfiltered = sealer.seal(forwardBinding(null, null), "04");

        assertThatThrownBy(() ->
                new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                        .list(request(unfiltered, PageDirection.NEXT, "03", null), sealer, SUBJECT))
                .isInstanceOf(CursorToken.InvalidCursorException.class);
    }

    /**
     * Confirms neither boundary position can be replayed in the other direction.
     */
    @Test
    @DisplayName("neither boundary position can be replayed in the other direction")
    void neitherBoundaryPositionCanBeReplayedInTheOtherDirection() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        CursorToken sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        String trailing = sealer.seal(forwardBinding(null, null), "04");
        String leading = sealer.seal(backwardBinding(null, null), "04");

        assertThatThrownBy(() ->
                new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                        .list(request(trailing, PageDirection.PREVIOUS, null, null), sealer, SUBJECT))
                .as("a trailing position replayed backward")
                .isInstanceOf(CursorToken.InvalidCursorException.class);

        assertThatThrownBy(() ->
                new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                        .list(request(leading, PageDirection.NEXT, null, null), sealer, SUBJECT))
                .as("a leading position replayed forward")
                .isInstanceOf(CursorToken.InvalidCursorException.class);
    }

    /**
     * Confirms a direction stated without a position is refused rather than answered with page one.
     *
     * <p>Assumptions: both directions are asserted. The published contract states the pair travels
     * together or not at all, and answering the opening page for PREVIOUS is the case that let a client
     * loop over the first page while believing it was retreating.</p>
     */
    @Test
    @DisplayName("a direction without a position is refused, in both directions")
    void aDirectionWithoutAPositionIsRefused() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        CursorToken sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);

        for (PageDirection direction : PageDirection.values()) {
            assertThatThrownBy(() ->
                    new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                            .list(request(null, direction, null, null), sealer, SUBJECT))
                    .as("direction %s with no cursor", direction)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            ClientInputException.class))
                    .extracting(ClientInputException::fields)
                    .isEqualTo(List.of(ReferencePaging.FIELD_DIRECTION));
        }
    }

    /**
     * Confirms a backward page always reports that a further page follows it.
     *
     * <p>Assumptions: this is the reference's own unconditional behaviour, set at physical line 1738 of
     * {@code COTRTLIC.cbl} at the top of its backward reader. Reporting the backward surplus instead told
     * a caller that had just stepped back that nothing lay ahead, which made the page it came from
     * unreachable.</p>
     */
    @Test
    @DisplayName("a backward page reports that a further page follows")
    void aBackwardPageReportsAFollowingPage() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        CursorToken sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        String leading = sealer.seal(backwardBinding(null, null), "04");
        when(types.findByTypeCdLessThanOrderByTypeCdDesc(eq("04"), any(Limit.class)))
                .thenReturn(rows(3).reversed());

        PageResponse<TransactionTypeResponse> page =
                new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                        .list(request(leading, PageDirection.PREVIOUS, null, null), sealer, SUBJECT);

        assertThat(page.hasNext())
                .as("the caller stepped back from a page, so that page still lies ahead")
                .isTrue();
        assertThat(page.hasPrevious())
                .as("three rows read against a window of seven leaves no surplus behind them")
                .isFalse();
    }

    /**
     * Confirms the opening page reports no earlier page even though it carries a leading position.
     */
    @Test
    @DisplayName("the opening page reports no earlier page")
    void theOpeningPageReportsNoEarlierPage() {
        TransactionTypeRepository types = mock(TransactionTypeRepository.class);
        when(types.findAllByOrderByTypeCdAsc(any(Limit.class))).thenReturn(rows(8));

        PageResponse<TransactionTypeResponse> page =
                new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                        .list(request(null, null, null, null),
                                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME), SUBJECT);

        assertThat(page.hasPrevious())
                .as("nothing precedes the opening page, whatever leading position it publishes")
                .isFalse();
        assertThat(page.firstKey()).isNotNull();
    }
}
