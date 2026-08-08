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
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME));

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
                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME));

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
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME));

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
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME));

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
                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME));

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
                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME));

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
        String cursor = sealer.seal(TransactionTypeService.CURSOR_BINDING, "04");
        when(types.findByTypeCdGreaterThanOrderByTypeCdAsc(eq("04"), any(Limit.class)))
                .thenReturn(rows(2));

        new TransactionTypeService(types, mock(TransactionCategoryRepository.class)).list(request(cursor, PageDirection.NEXT, null, null),
                sealer);

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
        String cursor = sealer.seal(TransactionTypeService.CURSOR_BINDING, "09");
        String expected = TransactionTypeRepository.descriptionFilterPattern("Description");
        when(types.countFilterMatches(isNull(), eq(expected))).thenReturn(9L);
        when(types.findFilteredPageBefore(isNull(), eq(expected), eq("09"), any(Limit.class)))
                .thenReturn(rows(3).reversed());

        PageResponse<TransactionTypeResponse> page = new TransactionTypeService(types, mock(TransactionCategoryRepository.class))
                .list(request(cursor, PageDirection.PREVIOUS, null, "Description"), sealer);

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
                .list(request(null, null, "99", null), new CursorToken(CURSOR_KEY, CURSOR_LIFETIME)))
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
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME)))
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
                        new CursorToken(CURSOR_KEY, CURSOR_LIFETIME)))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        ClientInputException.class))
                .extracting(ClientInputException::fields)
                .isEqualTo(List.of(TransactionTypeService.FIELD_TYPE_CODE,
                        TransactionTypeService.FIELD_DESCRIPTION));
    }
}
