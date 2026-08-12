package com.carddemo.card.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.carddemo.card.domain.Card;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.card.service.CardAdminViewService;
import com.carddemo.card.service.CardListService;
import com.carddemo.card.service.CardUpdateService;
import com.carddemo.card.service.CardViewService;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.GlobalExceptionHandler;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.security.SealedSelector;
import com.carddemo.common.web.CursorToken;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Limit;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the card browse through a real dispatcher rather than by calling the handler.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Purpose: the sibling census in this package compares the published contract against the method
 * table, and the service tests call {@link CardListService} directly. Neither exercises what happens
 * between an HTTP request and a handler argument, and that gap is where this operation's cursor
 * protection actually lives: the caller's name is not a body member, a query parameter or a path segment
 * -- it is resolved from the security context into a {@link Principal} argument. A test that calls the
 * handler passes the name in itself and therefore proves nothing about whether the dispatcher would have
 * supplied one.
 *
 * <p>Purpose: a review recorded that this module had no dispatcher coverage at all and that a static
 * census "missed the literal POST/GET break and does not exercise binding, validation, advice, headers,
 * or status rendering". Each case below asserts one of those: the optional body binding, the bean
 * validation on the body, the shared advice's rendering of a refused cursor, and the status a caller
 * receives.
 *
 * <p>Assumptions: the read service is REAL rather than mocked, over a mocked store and a real sealer.
 * That is required rather than thorough: two of the cases assert that a cursor is refused, and a mocked
 * service would answer whatever it was stubbed to answer, so the assertion would be about the stub. With
 * a real service and a real sealer, the refusal is produced by the same code a deployment runs.
 *
 * <p>Assumptions: the dispatcher is assembled with {@code standaloneSetup} and no security chain, so a
 * failure here localises to request mapping, argument resolution, binding, validation or advice. Which
 * authority each route demands is asserted in {@code com.carddemo.card.config} against the chain's own
 * installed authorization managers, and duplicating it here would give a later change two places to
 * update and one to forget.
 *
 * <p>Assumptions: the principal is supplied per request through the request builder rather than through a
 * security context holder, because the handler declares {@link Principal} and the framework resolves that
 * argument from the request itself. Setting a context would exercise a bridge this route does not use.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; each method below carries its own.
 */
class CardDispatcherTest {

    /** Key material for the real seal; test-only and deliberately not a credential. */
    private static final byte[] CURSOR_KEY =
            "carddemo-card-dispatcher-test-cursor-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /** Key material for the real row-identity seal; test-only and deliberately not a credential. */
    private static final byte[] SELECTOR_KEY =
            "carddemo-card-dispatcher-test-selector-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /** How long a sealed cursor stays redeemable; generous, because no case here asserts expiry. */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /** The instant the advice stamps on every problem document, fixed so a body is comparable. */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-02-01T00:00:00Z");

    /** The caller every request is dispatched as, standing in for a validated principal's name. */
    private static final Principal OPERATOR = () -> "operator-under-test";

    /** A second caller, used only to prove a cursor issued to one operator is refused for another. */
    private static final Principal OTHER_OPERATOR = () -> "another-operator";

    /** The account every stubbed row belongs to, inside the eleven-digit domain the contract declares. */
    private static final long ACCOUNT_ID = 10_000_000_001L;

    /** The store the real read service reads through, substituted so a case controls the rows. */
    private CardRepository cards;

    /** The real seal, so a published cursor is a real cursor and a refusal is a real refusal. */
    private CursorToken sealer;

    /** The dispatcher under test. */
    private MockMvc mockMvc;

    /**
     * Builds the dispatcher over the real read service, a mocked store and the shared advice.
     *
     * <p>Assumptions: the money module is installed on the converter even though a card summary carries
     * no amount, because the converter is shared with every other response this controller can render and
     * a converter assembled differently from the running one would make a body assertion describe this
     * test's own configuration.</p>
     */
    @BeforeEach
    void buildDispatcher() {
        this.cards = mock(CardRepository.class);
        this.sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);

        CardListService reads = new CardListService(this.cards,
                new CardMapper(new SealedSelector(SELECTOR_KEY)), this.sealer);

        JacksonJsonHttpMessageConverter converter = new JacksonJsonHttpMessageConverter(
                JsonMapper.builder().addModule(new MoneyModule()).build());

        GlobalExceptionHandler advice =
                new GlobalExceptionHandler(Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

        this.mockMvc = MockMvcBuilders
                .standaloneSetup(new CardController(reads, mock(CardViewService.class),
                        mock(CardAdminViewService.class), mock(CardUpdateService.class), advice))
                .setMessageConverters(converter)
                .setControllerAdvice(advice)
                .build();
    }

    /**
     * A request carrying no body at all is accepted and answered with the opening page.
     *
     * <p>Assumptions: this asserts the {@code required = false} on the body, which is the list screen's
     * initial state and is reachable only through a dispatcher -- a direct handler call passes
     * {@code null} explicitly and so cannot distinguish an optional body from a rejected one.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("POST /api/v1/cards/search with no body answers the opening page")
    void anAbsentBodyIsAcceptedAndAnswersTheOpeningPage() throws Exception {
        stubForwardRead(rows(CardListService.PAGE_SIZE));

        this.mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType("application/json")
                        .principal(OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(CardListService.PAGE_SIZE))
                .andExpect(jsonPath("$.lastKey").isNotEmpty())
                // WHY : Assumptions: the row identity is asserted to be absent of the card number rather
                //       than merely present, because a summary publishes a sealed selector in its place
                //       and a regression that published the number would still satisfy a presence check.
                .andExpect(jsonPath("$.items[0].key").isNotEmpty())
                .andExpect(jsonPath("$.items[0].cardNumber").doesNotExist());
    }

    /**
     * Reads one opening page as the nominated caller and returns the forward cursor it published.
     *
     * <p>Assumptions: the cursor is taken from the RESPONSE rather than composed here. The composition is
     * package-private to the service package for the reason recorded on it, and re-deriving it in this
     * package would put the binding vocabulary in a second place -- which is how the two come to
     * disagree. Reading the published token also matches what a client actually does with it.</p>
     *
     * @param caller the authenticated caller the opening page is read as; must not be {@code null}
     * @return the {@code lastKey} the opening page published, never {@code null}
     * @throws Exception if the request cannot be performed or the envelope carries no forward cursor
     */
    private String publishedForwardCursor(java.security.Principal caller) throws Exception {
        String body = this.mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType("application/json")
                        .principal(caller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastKey").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.lastKey");
    }

    /**
     * A cursor issued to one caller is refused with the published 400 when another caller presents it.
     *
     * <p>Purpose: this is the dispatcher-level statement of the cursor's subject binding. The two requests
     * differ in nothing a client controls -- same address, same body, same token -- only in the
     * authenticated caller the dispatcher resolves, which is exactly the difference the seal is meant to
     * detect.</p>
     *
     * <p>Refactoring Rationale: before the binding was composed from the caller's name, the second request
     * below was answered with a page. The published contract already listed a cursor that "was not issued
     * to this caller" among the causes of its 400, so the document described a refusal the service did not
     * make.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a cursor issued to one caller is refused 400 when another caller presents it")
    void aCursorFromAnotherCallerIsRefused() throws Exception {
        stubForwardRead(rows(CardListService.PAGE_SIZE));

        String cursor = publishedForwardCursor(OPERATOR);

        this.mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType("application/json")
                        .principal(OPERATOR)
                        .content("{\"cursor\":\"" + cursor + "\",\"direction\":\"next\"}"))
                .andExpect(status().isOk());

        this.mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType("application/json")
                        .principal(OTHER_OPERATOR)
                        .content("{\"cursor\":\"" + cursor + "\",\"direction\":\"next\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));
    }

    /**
     * A cursor sealed for one direction is refused with the published 400 when the other is stated.
     *
     * <p>Refactoring Rationale: the contract promised this before it was true, stating that the direction
     * is carried in the seal of each token so that replaying one with the other direction "cannot silently
     * return the wrong page". With a bare query-name binding the request below was answered with a page
     * from the wrong end of the set.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("a forward cursor presented as a backward step is refused 400")
    void aCursorPresentedWithTheOppositeDirectionIsRefused() throws Exception {
        stubForwardRead(rows(CardListService.PAGE_SIZE));

        String forwardCursor = publishedForwardCursor(OPERATOR);

        this.mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType("application/json")
                        .principal(OPERATOR)
                        .content("{\"cursor\":\"" + forwardCursor + "\",\"direction\":\"previous\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));
    }

    /**
     * An account narrowing outside the published shape is refused before any read is attempted.
     *
     * <p>Assumptions: the offending member is asserted by name in the field-error array, because the
     * published 400 promises that array names each offending field. A status-only assertion would pass
     * against a body that named nothing, which is the outcome a caller cannot act on.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an account narrowing of the wrong width is refused 400 naming the member")
    void aMalformedAccountNarrowingIsRefused() throws Exception {
        this.mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType("application/json")
                        .principal(OPERATOR)
                        .content("{\"accountId\":\"123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("accountId"));
    }

    /**
     * An account narrowing of eleven zero digits is accepted over HTTP and narrows nothing.
     *
     * <p>Purpose: this pins the reference reading at the layer a caller reaches, so that the published
     * document and the running service state one rule. {@code 2210-EDIT-ACCOUNT.} places
     * {@code CC-ACCT-ID-N EQUAL ZEROS} in the same disjunction as low values and spaces at
     * {@code app/cbl/COCRDLIC.cbl:1007-1009} and sends all three to the not-supplied exit at
     * {@code :1010-1012} without raising its input-error condition, and {@code 9500-FILTER-RECORDS.}
     * applies its predicate only when the flag is valid at {@code :1385}. So a zero-filled filter field
     * lists across all accounts on this screen.</p>
     *
     * <p>Assumptions: the absent predicate is asserted at the STORE rather than only by the status,
     * because a 200 alone would also be returned by a narrowing on account zero over a corpus that
     * happened to match nothing. Asserting that the query was issued with no account is what distinguishes
     * "not supplied" from "supplied and matched nothing".</p>
     *
     * <p>Refactoring Rationale: the published description of this member previously required it to be
     * "not all zeros" and cited the card UPDATE screen's mandatory-field sentence at
     * {@code app/cbl/COCRDUPC.cbl:189-192}. The document has been corrected to this screen's own
     * paragraph rather than the behaviour being changed to match it, which would have been an
     * unregistered divergence from the reference; this case is what keeps the two agreeing.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an account narrowing of eleven zero digits is accepted and narrows nothing")
    void anAllZeroAccountNarrowingIsReadAsNotSupplied() throws Exception {
        stubForwardRead(rows(CardListService.PAGE_SIZE));

        this.mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType("application/json")
                        .principal(OPERATOR)
                        .content("{\"accountId\":\"00000000000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(CardListService.PAGE_SIZE));

        verify(this.cards).findForwardFromCursor(isNull(), isNull(), isNull(), any(Limit.class));
    }

    /**
     * A paging direction outside the published enumeration is refused 400 naming the member, rather than
     * being read as the forward default.
     *
     * <p>Purpose: the published {@code PageDirection} schema enumerates exactly {@code next} and
     * {@code previous} with {@code next} as its default, and the handler compares the submitted value
     * against the single backward literal. Without a declared domain on the member every other spelling
     * satisfied that comparison as "not backward" and was answered with a forward page and no complaint,
     * so a caller asking to page backward in the wrong case was silently served the page it already held.
     * This case pins each spelling a client actually produces.</p>
     *
     * <p>Assumptions: the offending member is asserted by NAME, because a status-only assertion would
     * pass against a body that named nothing -- and the whole defect being closed here is a caller unable
     * to tell which member it got wrong.</p>
     *
     * @param direction a spelling outside the published enumeration
     * @throws Exception if the request cannot be performed
     */
    @ParameterizedTest
    @ValueSource(strings = {"PREVIOUS", "Previous", "NEXT", "sideways", "", " previous", "previous "})
    @DisplayName("a paging direction outside the published enumeration is refused 400 naming it")
    void anUnpublishedPagingDirectionIsRefused(String direction) throws Exception {
        this.mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType("application/json")
                        .principal(OPERATOR)
                        .content("{\"direction\":\"" + direction + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("direction"));
    }

    /**
     * Both published paging directions are accepted, so the domain above refuses nothing legitimate.
     *
     * <p>Assumptions: this is the positive control for the refusals above. Without it a constraint that
     * refused every value, including the two the contract publishes, would satisfy every negative case
     * completely. The backward member is exercised with no cursor, which the service answers with the
     * opening page, so the case turns on the member's domain rather than on a token.</p>
     *
     * @param direction one of the two spellings the contract enumerates
     * @throws Exception if the request cannot be performed
     */
    @ParameterizedTest
    @ValueSource(strings = {"next", "previous"})
    @DisplayName("both published paging directions are accepted")
    void bothPublishedPagingDirectionsAreAccepted(String direction) throws Exception {
        this.mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType("application/json")
                        .principal(OPERATOR)
                        .content("{\"direction\":\"" + direction + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    /**
     * An unreadable cursor is refused with the published 400 rather than reaching a predicate.
     *
     * <p>Assumptions: the presented value is well-formed text that is simply not a token this browse
     * sealed, which is the case a client actually produces by truncating or re-encoding what it was
     * given.</p>
     *
     * @throws Exception if the request cannot be performed
     */
    @Test
    @DisplayName("an unreadable cursor is refused 400")
    void anUnreadableCursorIsRefused() throws Exception {
        this.mockMvc.perform(post(CardController.SEARCH_PATH)
                        .contentType("application/json")
                        .principal(OPERATOR)
                        .content("{\"cursor\":\"not-a-token\",\"direction\":\"next\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiError.CODE_VALIDATION));
    }

    /**
     * Stubs both keyset reads to answer with the supplied rows.
     *
     * <p>Assumptions: the forward query is stubbed for any position, because the cases here vary the
     * cursor and the caller rather than the rows, and a per-position stub would make a refusal
     * indistinguishable from an unstubbed call answering an empty list.</p>
     *
     * @param ordered the rows the store answers with, ascending by card number
     */
    private void stubForwardRead(List<Card> ordered) {
        when(this.cards.findForwardFromCursor(any(), eq(null), isNull(), any(Limit.class)))
                .thenReturn(ordered);
        when(this.cards.findBackwardFromCursor(any(), eq(null), isNull(), any(Limit.class)))
                .thenReturn(new ArrayList<>(ordered).reversed());
    }

    /**
     * Builds an ascending run of stored cards.
     *
     * @param count how many rows to build
     * @return the rows, ascending by card number; never {@code null}
     */
    private static List<Card> rows(int count) {
        List<Card> rows = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            rows.add(new Card(cardNumber(index), ACCOUNT_ID + index, null,
                    "HOLDER " + index, LocalDate.of(2028, 5, 31), "Y"));
        }
        return rows;
    }

    /**
     * Builds the sixteen-digit number of one stored row.
     *
     * <p>Assumptions: the numbers are demonstration values in the published CardDemo seed range,
     * identifying no real account, and they are composed rather than read from a fixture because these
     * cases assert statuses and never a row's content.</p>
     *
     * @param index the row's one-based ordinal
     * @return the sixteen-digit number; never {@code null}
     */
    private static String cardNumber(int index) {
        return String.format("41111111111100%02d", index);
    }
}
