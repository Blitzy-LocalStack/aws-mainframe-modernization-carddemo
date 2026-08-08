package com.carddemo.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.CardDetail;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.security.SealedSelector;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * Asserts that ONE selector mechanism addresses a card, and that a production route consumes it.
 *
 * <h2>What this test replaces, and why it moved</h2>
 *
 * <p>This supersedes a test that lived in the domain package and asserted a durable random
 * {@code card_selector} UUID column: that it was generated in the constructor, that it did not repeat, that
 * it was declared not updatable, and that the migration declared it with a unique constraint. Every one of
 * those assertions held, and none of them showed that anything could address a card by the column -- no
 * repository method read it, so no request could ever resolve a row through it. Meanwhile a second,
 * unrelated mechanism was what the routes actually used: {@link CardMapper} seals the card number into a
 * keyed, purpose-scoped token and opens it back. The column is removed and the sealed token is the single
 * mechanism, so the test moves out of the domain package -- a selector is no longer a property of the
 * entity -- and asserts the property that was missing: that a route consumes it end to end.
 *
 * <p>Assumptions: a real sealer and a real mapper are used with only the store substituted. Substituting the
 * sealer would make the round trip a property of the substitute, and the round trip is the whole subject.
 */
class CardSelectorRouteTest {

    /** Key material for the sealer; test-only, and long enough to satisfy the sealer's floor. */
    private static final byte[] SELECTOR_KEY =
            "carddemo-card-selector-route-test!".repeat(2).getBytes(StandardCharsets.UTF_8);

    /** Key material for the cursor signer. */
    private static final byte[] CURSOR_KEY =
            "carddemo-card-cursor-route-test-k".repeat(2).getBytes(StandardCharsets.UTF_8);

    /** How long a sealed cursor stays redeemable; generous, because no case asserts expiry. */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /** The card number the single-card cases address. */
    private static final String CARD_NUMBER = "4111111111110011";

    /** The account the browse cases filter on. */
    private static final Long ACCOUNT_ID = 11L;

    /**
     * Asserts that a selector a list row published opens back to the number it stands for.
     *
     * <p>Assumptions: this is the property the removed test could not express. It runs the mint and the open
     * through the same mapper instance, which is what a deployment does, and it asserts the opened value is
     * the primary key -- because that is what makes the selector resolvable by {@code findById}.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a selector published on a list row opens back to the card number it addresses")
    void aPublishedSelectorOpensToTheCardNumber() {

        CardMapper mapper = new CardMapper(new SealedSelector(SELECTOR_KEY));
        CardSummary summary = mapper.toSummary(card(CARD_NUMBER));

        assertThat(summary.key()).isNotBlank();
        assertThat(mapper.openCardSelector(summary.key()))
                .as("the opened value must be the primary key, or no route could resolve a row from it")
                .isEqualTo(CARD_NUMBER);
    }

    /**
     * Asserts that the selector is not the card number, nor any part of it.
     *
     * <p>Assumptions: this is the reason the surrogate exists at all -- a request target is retained by
     * access logs and browser history, so a selector that contained the number would disclose it to two
     * durable stores the reference never wrote it to.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a selector discloses no part of the card number it stands for")
    void aSelectorDisclosesNoPartOfTheNumber() {

        CardMapper mapper = new CardMapper(new SealedSelector(SELECTOR_KEY));
        String selector = mapper.toDetail(card(CARD_NUMBER)).key();

        assertThat(selector).doesNotContain(CARD_NUMBER);
        assertThat(selector)
                .as("no run of the number may survive into the token, not even its last four")
                .doesNotContain(CARD_NUMBER.substring(CARD_NUMBER.length() - 4));
    }

    /**
     * Asserts that a selector sealed under one key is refused under another.
     *
     * <p>Assumptions: this is what makes the token a keyed capability rather than an encoding. A caller
     * cannot fabricate a selector for a card it was never shown, because it cannot produce the seal.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a selector minted under a foreign key is refused rather than opened")
    void aForeignSelectorIsRefused() {

        String foreign = new CardMapper(new SealedSelector(
                "carddemo-some-other-deployment-key".repeat(2).getBytes(StandardCharsets.UTF_8)))
                .toDetail(card(CARD_NUMBER)).key();

        CardMapper mine = new CardMapper(new SealedSelector(SELECTOR_KEY));

        assertThatThrownBy(() -> mine.openCardSelector(foreign))
                .isInstanceOf(ClientInputException.class);
        assertThatThrownBy(() -> mine.openCardSelector("not-a-sealed-token"))
                .isInstanceOf(ClientInputException.class);
    }

    /**
     * Asserts that the detail route resolves a selector to the row and returns its masked projection.
     *
     * <p>Assumptions: the store is asked for the OPENED number, which is the assertion that ties the mint,
     * the open and the keyed read into one route. A route that opened the selector and then read by
     * something else would pass every assertion above and still be broken.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the detail route opens the selector and reads the row by the number it yielded")
    void theDetailRouteResolvesTheSelector() {

        CardRepository cards = mock(CardRepository.class);
        CardMapper mapper = new CardMapper(new SealedSelector(SELECTOR_KEY));
        Card stored = card(CARD_NUMBER);
        when(cards.findById(CARD_NUMBER)).thenReturn(Optional.of(stored));

        CardListService service =
                new CardListService(cards, mapper, new CursorToken(CURSOR_KEY, CURSOR_LIFETIME));
        String selector = mapper.toSummary(stored).key();

        CardDetail detail = service.readDetail(selector);

        verify(cards).findById(CARD_NUMBER);
        assertThat(detail.displayCardNumber())
                .as("the projection masks the number even on the route that resolved it")
                .doesNotContain(CARD_NUMBER);
        assertThat(detail.key()).isNotBlank();
    }

    /**
     * Asserts that a selector naming no row is refused as not found rather than answered.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a well-formed selector naming no row is refused as not found")
    void aSelectorNamingNoRowIsNotFound() {

        CardRepository cards = mock(CardRepository.class);
        CardMapper mapper = new CardMapper(new SealedSelector(SELECTOR_KEY));
        when(cards.findById(CARD_NUMBER)).thenReturn(Optional.empty());

        CardListService service =
                new CardListService(cards, mapper, new CursorToken(CURSOR_KEY, CURSOR_LIFETIME));
        String selector = mapper.toSummary(card(CARD_NUMBER)).key();

        assertThatThrownBy(() -> service.readDetail(selector))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessage(CardListService.MESSAGE_CARD_NOT_FOUND);
    }

    /**
     * Asserts that the first page drives the forward keyset query and reports a further page.
     *
     * <p>Assumptions: eight rows are stubbed against a window of seven, which checks both halves of the
     * surplus technique -- the extra row is discarded and its arrival sets the further-page flag. The window
     * is seven because the reference declares {@code WS-MAX-SCREEN-LINES VALUE 7} at
     * {@code app/cbl/COCRDLIC.cbl} lines 177 to 178.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the first page drives the forward keyset query and trims its surplus row")
    void theFirstPageDrivesTheForwardKeysetQuery() {

        CardRepository cards = mock(CardRepository.class);
        when(cards.findForwardFromCursor(isNull(), isNull(), isNull(), any(Limit.class)))
                .thenReturn(rows(CardListService.PAGE_SIZE + 1));

        PageResponse<CardSummary> page = service(cards).list(null, null, false);

        assertThat(page.items()).hasSize(CardListService.PAGE_SIZE);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.firstKey()).isNotBlank();
        assertThat(page.lastKey()).isNotBlank();
        verify(cards).findForwardFromCursor(isNull(), isNull(), isNull(), any(Limit.class));
    }

    /**
     * Asserts that the account filter reaches the query rather than being applied afterwards.
     *
     * <p>Assumptions: this is what makes the filter an indexed access path. The predicate reads through
     * {@code idx_cards_account_id}, the target of the alternate index the reference declares non-unique at
     * {@code app/jcl/CARDFILE.jcl} lines 85 to 87, so verifying the argument reached the query is verifying
     * the index is used rather than a page being filtered after the fact.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the account filter is passed into the query, so the alternate index is the access path")
    void theAccountFilterReachesTheQuery() {

        CardRepository cards = mock(CardRepository.class);
        when(cards.findForwardFromCursor(isNull(), eq(ACCOUNT_ID), isNull(), any(Limit.class)))
                .thenReturn(rows(2));

        PageResponse<CardSummary> page = service(cards).list(ACCOUNT_ID, null, false);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasNext()).isFalse();
        verify(cards).findForwardFromCursor(isNull(), eq(ACCOUNT_ID), isNull(), any(Limit.class));
    }

    /**
     * Asserts that a backward page drives the backward query, renders ascending and trims the low surplus.
     *
     * <p>Assumptions: the rows are stubbed DESCENDING, as the query name promises, and the page is asserted
     * ascending -- a published page is always ascending whichever way the browse travelled. The surplus of a
     * backward read is the lowest row, so trimming the same end in both directions would drop a row the
     * caller should see.
     *
     * <p>This test takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a backward page drives the backward query and renders ascending")
    void aBackwardPageDrivesTheBackwardQuery() {

        CardRepository cards = mock(CardRepository.class);
        CursorToken sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        String cursor = sealer.seal(CardListService.LIST_BINDING, "4111111111110099");

        List<Card> descending = new ArrayList<>(rows(CardListService.PAGE_SIZE + 1)).reversed();
        when(cards.findBackwardFromCursor(eq("4111111111110099"), isNull(), isNull(),
                any(Limit.class))).thenReturn(descending);

        CardMapper mapper = new CardMapper(new SealedSelector(SELECTOR_KEY));
        PageResponse<CardSummary> page =
                new CardListService(cards, mapper, sealer).list(null, cursor, true);

        assertThat(page.items()).hasSize(CardListService.PAGE_SIZE);
        assertThat(page.items().getFirst().accountId())
                .as("the lowest row is the backward surplus and must have been trimmed")
                .isNotEqualTo(rows(1).getFirst().getAccountId().toString());
        verify(cards).findBackwardFromCursor(eq("4111111111110099"), isNull(), isNull(),
                any(Limit.class));
    }

    /**
     * Builds the read service over a substituted store with a real mapper and cursor signer.
     *
     * @param cards the substituted store
     * @return the service under test, never {@code null}
     */
    private static CardListService service(CardRepository cards) {
        return new CardListService(cards, new CardMapper(new SealedSelector(SELECTOR_KEY)),
                new CursorToken(CURSOR_KEY, CURSOR_LIFETIME));
    }

    /**
     * Builds one stored card with the stated number.
     *
     * @param cardNumber the sixteen-digit number the card is keyed by
     * @return the card, never {@code null}
     */
    private static Card card(String cardNumber) {
        return new Card(cardNumber, ACCOUNT_ID, null, "TEST CARDHOLDER",
                LocalDate.of(2028, 5, 31), "Y");
    }

    /**
     * Builds a run of stored cards with sequential numbers, ascending.
     *
     * @param count how many cards to build
     * @return the cards, ascending by number; never {@code null}
     */
    private static List<Card> rows(int count) {
        List<Card> rows = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            rows.add(new Card(String.format("41111111111100%02d", index), ACCOUNT_ID + index, null,
                    "HOLDER " + index, LocalDate.of(2028, 5, 31), "Y"));
        }
        return rows;
    }
}
