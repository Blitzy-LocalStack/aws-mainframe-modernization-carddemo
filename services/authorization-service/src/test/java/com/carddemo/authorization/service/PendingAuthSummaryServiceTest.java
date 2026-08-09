package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.dto.PendingAuthListView;
import com.carddemo.authorization.dto.PendingAuthRowView;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.web.CursorToken;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * Verifies the migrated browse loop: page size, the look-ahead probe, both directions, both navigation
 * boundaries, and the two ways a paging request is refused.
 *
 * <p>Assumptions: the repositories are doubles and the mapper is REAL, built over a real sealer with fixed
 * key material. A mapped view is what the operation returns, so mocking the mapper would leave the returned
 * page unasserted and would also hide the property that matters most here -- that the cursor a page hands
 * out is the cursor the next request can redeem. Sealing and opening under one instance is the only way to
 * prove that.</p>
 *
 * <p>Assumptions: the reference program is the specification for every number in this class. Every citation
 * is relative to {@code app/app-authorization-ims-db2-mq}, which is reference material this migration reads
 * and never modifies.</p>
 */
class PendingAuthSummaryServiceTest {

    /**
     * The authenticated principal every case in this class seals and redeems sealed values under.
     *
     * <p>Assumptions: one subject throughout. The sealed selector and the paging cursor are bound to the
     * caller they were issued to, so sealing and redeeming have to agree on it or every case would fail on
     * a refused token rather than on the property it asserts.</p>
     */
    private static final String SUBJECT = "authorization-operator";

    /** The account every row in this class belongs to. */
    private static final long ACCOUNT_ID = 11L;

    /** The customer the summary row names. */
    private static final long CUSTOMER_ID = 11L;

    /** The card number every row in this class carries. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The Julian date component shared by every row, so ordering turns on the time component alone. */
    private static final int AUTH_DATE = 26215;

    /**
     * The time component of the newest row every descending run in this class starts from.
     *
     * <p>Assumptions: named rather than repeated as a literal, because three helpers derive positions from
     * it and a run built from one number while an expectation was built from another would fail on the
     * position rather than on the property under test.</p>
     */
    private static final int NEWEST_TIME = 9_1644_902;

    /** The summary-row double's source, rebuilt per test so no assertion depends on another. */
    private PendingAuthSummaryRepository summaries;

    /** The detail-row double. */
    private PendingAuthDetailRepository details;

    /** The real mapper, so a sealed cursor can be redeemed by the code under test. */
    private PendingAuthViewMapper mapper;

    /** The service under test. */
    private PendingAuthSummaryService service;

    /**
     * Builds the doubles, a real sealer over deterministic key material, and the service.
     *
     * <p>Assumptions: the key material is a fixed fill rather than a random value, so a failure is
     * reproducible. No assertion here depends on a token's TEXT, only on its being redeemable.</p>
     */
    @BeforeEach
    void setUp() {
        this.summaries = mock(PendingAuthSummaryRepository.class);
        this.details = mock(PendingAuthDetailRepository.class);
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        Arrays.fill(keyMaterial, (byte) 0x3C);
        this.mapper = new PendingAuthViewMapper(new CursorToken(keyMaterial, Duration.ofMinutes(5)));
        this.service = new PendingAuthSummaryService(this.summaries, this.details, this.mapper);
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryRow()));
    }

    /**
     * The page size and the look-ahead are the reference screen's five rows plus one probe row.
     *
     * <p>Assumptions: the LIMIT the repository receives is asserted, not merely the row count returned,
     * because the probe row is what sets the next-page indicator. A service that asked for five would have
     * to count a second query to answer the same question, which is what the reference program avoids by
     * discovering one occurrence more than fits -- its loop is bounded at five at
     * {@code cbl/COPAUS0C.cbl} L424 over a table declared {@code OCCURS 5 TIMES} at L126, and its probe is
     * the further retrieval at L445 to L452.</p>
     */
    @Test
    @DisplayName("the opening page asks for five rows plus one look-ahead and reports the further page")
    void openingPageAsksForOneMoreRowThanItRenders() {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                eq(ACCOUNT_ID), any(Limit.class))).thenReturn(rowsDescending(6));

        PendingAuthListView view = this.service.list(ACCOUNT_ID, null, null, SUBJECT);

        verify(this.details).findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                ACCOUNT_ID, Limit.of(PendingAuthSummaryService.PAGE_SIZE + 1));
        assertThat(view.page().items()).hasSize(PendingAuthSummaryService.PAGE_SIZE);
        assertThat(view.page().hasNext()).isTrue();
        assertThat(view.screenMessage()).isNull();
    }

    /**
     * A read that returns fewer rows than the limit reports no further page.
     */
    @Test
    @DisplayName("a short opening page reports no further page")
    void shortOpeningPageReportsNoFurtherPage() {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                eq(ACCOUNT_ID), any(Limit.class))).thenReturn(rowsDescending(3));

        PendingAuthListView view = this.service.list(ACCOUNT_ID, null, null, SUBJECT);

        assertThat(view.page().items()).hasSize(3);
        assertThat(view.page().hasNext()).isFalse();
    }

    /**
     * An account with a summary row and no authorizations is an empty page and not a not-found.
     *
     * <p>Assumptions: this is the distinction the contract states on its 200 response -- a query that
     * matched no rows succeeded -- and the reference program makes it too: a summary found with no children
     * reaches the browse and fills no rows, whereas a summary NOT found skips the browse entirely at
     * {@code cbl/COPAUS0C.cbl} L354 to L356.</p>
     */
    @Test
    @DisplayName("a summary with no authorizations is an empty page carrying no boundary sentence")
    void summaryWithNoAuthorizationsIsAnEmptyPage() {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                eq(ACCOUNT_ID), any(Limit.class))).thenReturn(List.of());

        PendingAuthListView view = this.service.list(ACCOUNT_ID, null, null, SUBJECT);

        assertThat(view.page().items()).isEmpty();
        assertThat(view.page().hasNext()).isFalse();
        assertThat(view.screenMessage()).isNull();
    }

    /**
     * An account with no summary row is answered with a zeroed summary, not with a raised condition.
     *
     * <p>Assumptions: the absent summary is a NORMAL outcome, which the reference program settles twice
     * over. Its keyed retrieval evaluates a found arm and a not-found arm and no end-of-database arm at
     * {@code cbl/COPAUS0C.cbl} L980 to L996, so absence is a state and not a failure; and its caller
     * renders that state rather than reporting it, moving zero into all six aggregate positions at L800 to
     * L807 and skipping the browse at L354 to L356 so the five row positions stay blank. Asserting a raised
     * condition here would pin the opposite behaviour, under which a request naming an account with no
     * pending authorizations is answered with an error status where the reference program answers with a
     * zeroed summary and an empty list.</p>
     *
     * <p>Assumptions: the absence of any detail query is asserted rather than assumed, and the schema is
     * why it holds: the child segment is declared {@code PARENT=((PAUTSUM0,))} at
     * {@code ims/DBPAUTP0.dbd} L36 and the migration carries that forward as
     * {@code fk_pending_auth_detail_summary}, so with no summary row there can be no authorization row to
     * find.</p>
     */
    @Test
    @DisplayName("an account with no summary row is a zeroed empty page and costs no detail query")
    void accountWithNoSummaryRowIsAZeroedEmptyPage() {
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        PendingAuthListView view = this.service.list(ACCOUNT_ID, null, null, SUBJECT);

        assertThat(view.page().items()).isEmpty();
        assertThat(view.page().hasNext()).isFalse();
        assertThat(view.page().firstKey()).isNull();
        assertThat(view.page().lastKey()).isNull();
        assertThat(view.screenMessage()).isNull();
        assertThat(view.summary().approvedAuthCnt()).isZero();
        assertThat(view.summary().declinedAuthCnt()).isZero();
        assertThat(view.summary().creditBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.summary().cashBalance().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.summary().approvedAuthAmt().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(view.summary().declinedAuthAmt().amount()).isEqualByComparingTo(BigDecimal.ZERO);
        verifyNoInteractions(this.details);
    }

    /**
     * A read returning EXACTLY the page size reports no further page, which is the off-by-one boundary.
     *
     * <p>Assumptions: this is the case the look-ahead exists to separate, and it is asserted on its own
     * because it is the one an implementation gets wrong silently. A read of exactly five rows and a read of
     * six both render five, so the two are indistinguishable from the rendered count alone; only the
     * PRESENCE of the sixth row separates them. An implementation deriving has-next from
     * {@code size >= PAGE_SIZE} rather than {@code size > PAGE_SIZE} passes every other case in this class
     * and fails only here, offering a further page that does not exist. The reference program avoids the
     * same ambiguity by carrying an explicit indicator at {@code cbl/COPAUS0C.cbl} L123 to L125, set from
     * the probe retrieval at L445 to L452, rather than by counting rendered rows.</p>
     */
    @Test
    @DisplayName("a read of exactly the page size reports no further page")
    void readOfExactlyThePageSizeReportsNoFurtherPage() {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(rowsDescending(PendingAuthSummaryService.PAGE_SIZE));

        PendingAuthListView view = this.service.list(ACCOUNT_ID, null, null, SUBJECT);

        assertThat(view.page().items()).hasSize(PendingAuthSummaryService.PAGE_SIZE);
        assertThat(view.page().hasNext()).isFalse();
        assertThat(view.screenMessage()).isNull();
    }

    /**
     * A cursor that is not a redeemable token is refused rather than coerced into a position.
     *
     * <p>Assumptions: the cursor is an OPAQUE sealed token, so a value that does not open is refused and
     * never interpreted. The alternative an implementation can drift into is to treat an unopenable cursor
     * as no cursor and answer the opening page; its concrete consequence is that a client whose token was
     * truncated in transit would be silently returned to the first page and would page the account from the
     * beginning again with nothing reporting a fault. The refusal is keyed to the cursor field so the caller
     * learns which value failed, and no detail query is issued for a request that never resolved a
     * position.</p>
     */
    @Test
    @DisplayName("a malformed cursor is refused and keyed to the cursor rather than coerced")
    void malformedCursorIsRefused() {
        assertThatExceptionOfType(PendingAuthViewMapper.InvalidSelectorException.class)
                .isThrownBy(() -> this.service.list(ACCOUNT_ID, "not-a-sealed-token", "next", SUBJECT))
                .satisfies(refusal -> assertThat(refusal.fields())
                        .containsExactly(PendingAuthViewMapper.CURSOR_FIELD));
        verifyNoInteractions(this.details);
    }

    /**
     * Forward paging across a concurrent insert neither skips a row nor repeats one.
     *
     * <p>Assumptions: this is the concrete property that decides keyset paging over counting from the start
     * of the ordering, so it is asserted against a table that CHANGES between the two requests rather than a
     * unchanging stub. The repository doubles here evaluate the real predicate over a mutable list, and a row
     * newer than every existing one is inserted after the first page is served -- which is exactly what the
     * message-driven half of this context does continuously. Because the second request resumes from the
     * VALUE of the last row it was shown, the inserted row cannot enter the second page and no row between
     * the two pages can fall out of the sequence. Under a count from the start the same insert would shift
     * every later row by one place, so the second page would begin one row earlier and repeat a row the
     * caller had already been shown.</p>
     */
    @Test
    @DisplayName("forward paging across a concurrent insert neither skips nor repeats a row")
    void forwardPagingAcrossAConcurrentInsertNeitherSkipsNorRepeats() {
        List<PendingAuthDetail> table = new ArrayList<>(rowsDescending(12));
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                eq(ACCOUNT_ID), any(Limit.class)))
                .thenAnswer(call -> olderThan(table, Integer.MAX_VALUE, call.getArgument(1)));
        when(this.details.findOlderThan(eq(ACCOUNT_ID), any(), any(), any(Limit.class)))
                .thenAnswer(call -> olderThan(table, call.getArgument(2), call.getArgument(3)));

        PendingAuthListView first = this.service.list(ACCOUNT_ID, null, null, SUBJECT);

        // WHY : Assumptions: the insert is NEWER than every row already returned, which is the position an
        //       arriving authorization takes in a newest-first ordering and therefore the position that
        //       displaces rows under a counted page. Inserting an older row would not exercise the
        //       property, because it lands behind the reader rather than ahead of it.
        table.add(0, rowAt(NEWEST_TIME + 1));

        PendingAuthListView second =
                this.service.list(ACCOUNT_ID, first.page().lastKey(), "next", SUBJECT);

        List<String> shown = new ArrayList<>(renderedTimes(first));
        shown.addAll(renderedTimes(second));
        assertThat(shown).doesNotHaveDuplicates();
        assertThat(shown).isEqualTo(expectedTimes(2 * PendingAuthSummaryService.PAGE_SIZE));
    }

    /**
     * A forward move redeems the page's trailing cursor and seeks the OLDER rows.
     *
     * <p>Assumptions: "after the cursor" is older, because this context returns authorizations newest
     * first. The predicate compares the date and the time as a PAIR, which the repository declares; this
     * test asserts the service passes the pair it redeemed rather than only one half of it.</p>
     */
    @Test
    @DisplayName("a forward move seeks strictly older rows from the redeemed position")
    void forwardMoveSeeksOlderRows() {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                eq(ACCOUNT_ID), any(Limit.class))).thenReturn(rowsDescending(6));
        PendingAuthListView opening = this.service.list(ACCOUNT_ID, null, null, SUBJECT);
        String trailing = opening.page().lastKey();

        when(this.details.findOlderThan(eq(ACCOUNT_ID), any(), any(), any(Limit.class)))
                .thenReturn(rowsDescending(2));

        PendingAuthListView next = this.service.list(ACCOUNT_ID, trailing, "next", SUBJECT);

        verify(this.details).findOlderThan(ACCOUNT_ID, AUTH_DATE,
                lastRenderedTime(), Limit.of(PendingAuthSummaryService.PAGE_SIZE + 1));
        assertThat(next.page().items()).hasSize(2);
        assertThat(next.page().hasNext()).isFalse();
    }

    /**
     * A forward move from the closing page is the reference bottom-of-page state, not an error.
     *
     * <p>Assumptions: the sentence is carried character for character from {@code cbl/COPAUS0C.cbl} L409
     * to L410, and it is informational: that program leaves the rows already on the screen untouched and
     * merely adds the sentence, which is why an empty page rather than a refusal is the stateless
     * equivalent.</p>
     */
    @Test
    @DisplayName("a forward move past the last row reports the bottom-of-page sentence")
    void forwardMovePastTheLastRowReportsBottomOfPage() {
        String cursor = sealedCursorAt(NEWEST_TIME);
        when(this.details.findOlderThan(eq(ACCOUNT_ID), any(), any(), any(Limit.class)))
                .thenReturn(List.of());

        PendingAuthListView view = this.service.list(ACCOUNT_ID, cursor, "next", SUBJECT);

        assertThat(view.screenMessage()).isEqualTo(PendingAuthListView.MESSAGE_BOTTOM_OF_PAGE);
        assertThat(view.page().items()).isEmpty();
        assertThat(view.page().hasNext()).isFalse();
    }

    /**
     * A backward move reads ascending, keeps the CLOSEST rows, reverses them, and probes forward.
     *
     * <p>Refactoring Rationale: this is the assertion that would catch the classic error in a hand-written
     * backward page. Ordering the backward query descending and applying the limit returns the newest rows
     * in the whole account rather than the ones immediately preceding the current page; keeping the tail of
     * an ascending read rather than its head does the same thing one row at a time. The rows returned here
     * are asserted to be the ones adjacent to the caller's position and in newest-first order, so either
     * mistake fails.</p>
     *
     * <p>Assumptions: the forward probe is asserted as well, because the reference program routes its
     * backward move through {@code PROCESS-PAGE-FORWARD} at {@code cbl/COPAUS0C.cbl} L376, so the closing
     * probe at L445 to L452 runs on both directions rather than only on a forward one.</p>
     */
    @Test
    @DisplayName("a backward move returns the adjacent rows newest first and probes forward for has-next")
    void backwardMoveReturnsAdjacentRowsNewestFirst() {
        List<PendingAuthDetail> ascending = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            ascending.add(rowAt(100 + index));
        }
        when(this.details.findNewerThan(eq(ACCOUNT_ID), any(), any(), any(Limit.class)))
                .thenReturn(ascending);
        when(this.details.findOlderThan(eq(ACCOUNT_ID), any(), any(), eq(Limit.of(1))))
                .thenReturn(List.of(rowAt(99)));

        PendingAuthListView view = this.service.list(ACCOUNT_ID, sealedCursorAt(99), "previous", SUBJECT);

        assertThat(view.page().items()).hasSize(PendingAuthSummaryService.PAGE_SIZE);
        assertThat(view.page().items())
                .extracting(PendingAuthRowView::authOrigTime)
                .as("the five rows nearest the caller's position, presented newest first")
                .containsExactly("000104", "000103", "000102", "000101", "000100");
        assertThat(view.page().hasNext()).isTrue();
        verify(this.details).findOlderThan(eq(ACCOUNT_ID), eq(AUTH_DATE), eq(100), eq(Limit.of(1)));
    }

    /**
     * A backward move from the opening page is the reference top-of-page state.
     *
     * <p>Assumptions: the sentence is carried character for character from {@code cbl/COPAUS0C.cbl} L381,
     * and no forward probe is issued because there is no page to probe from.</p>
     */
    @Test
    @DisplayName("a backward move from the opening page reports the top-of-page sentence")
    void backwardMoveFromTheOpeningPageReportsTopOfPage() {
        when(this.details.findNewerThan(eq(ACCOUNT_ID), any(), any(), any(Limit.class)))
                .thenReturn(List.of());

        PendingAuthListView view = this.service.list(ACCOUNT_ID, sealedCursorAt(1), "previous", SUBJECT);

        assertThat(view.screenMessage()).isEqualTo(PendingAuthListView.MESSAGE_TOP_OF_PAGE);
        assertThat(view.page().items()).isEmpty();
        verify(this.details, never()).findOlderThan(any(), any(), any(), any(Limit.class));
    }

    /**
     * A direction with no cursor is refused and keyed to the direction, not answered as the opening page.
     *
     * <p>Assumptions: refusing is what lets the caller learn which of its two parameters it dropped.
     * Answering the opening page would look like success to a client that had lost its cursor, and that
     * client would page from the beginning forever without anything reporting a fault.</p>
     */
    @Test
    @DisplayName("a direction with no cursor is refused and keyed to the direction")
    void directionWithNoCursorIsRefused() {
        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> this.service.list(ACCOUNT_ID, null, "previous", SUBJECT))
                .satisfies(refusal -> {
                    assertThat(refusal.fields())
                            .containsExactly(PendingAuthSummaryService.DIRECTION_FIELD);
                    assertThat(refusal.code())
                            .isEqualTo(PendingAuthSummaryService.PAGING_REFUSAL_CODE);
                });
        verifyNoInteractions(this.details);
    }

    /**
     * A direction outside the two published values is refused rather than treated as the default.
     */
    @Test
    @DisplayName("an unpublished direction is refused")
    void unpublishedDirectionIsRefused() {
        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> this.service.list(ACCOUNT_ID, sealedCursorAt(1), "backwards", SUBJECT))
                .satisfies(refusal -> assertThat(refusal.fields())
                        .containsExactly(PendingAuthSummaryService.DIRECTION_FIELD));
    }

    /**
     * A cursor this service issued for one account cannot position a page of another.
     *
     * <p>Refactoring Rationale: this is the property a seal alone does not give. The token is
     * authenticated, so it cannot be forged, but it carries no subject and no scope in its binding, so a
     * caller can legitimately hold one and present it while naming a different account in the scope
     * parameter. Without the comparison the predicate would be built from the scope while the position came
     * from elsewhere, answering a page of the scoped account positioned inside a different one. The
     * refusal is keyed to the cursor rather than answered as a forbidden request, so a caller probing
     * accounts learns nothing about whether the position it replayed was valid somewhere.</p>
     */
    @Test
    @DisplayName("a cursor issued for another account is refused and keyed to the cursor")
    void cursorIssuedForAnotherAccountIsRefused() {
        long otherAccount = 22L;
        when(this.summaries.findByAccountId(otherAccount))
                .thenReturn(Optional.of(new PendingAuthSummary(otherAccount, CUSTOMER_ID)));

        assertThatExceptionOfType(PendingAuthViewMapper.InvalidSelectorException.class)
                .isThrownBy(() -> this.service.list(otherAccount, sealedCursorAt(1), "next", SUBJECT))
                .satisfies(refusal -> assertThat(refusal.fields())
                        .containsExactly(PendingAuthViewMapper.CURSOR_FIELD));
        verifyNoInteractions(this.details);
    }

    /**
     * Builds a descending run of rows, newest first, as the forward queries return them.
     *
     * @param count how many rows to build
     * @return the rows, newest first
     */
    private static List<PendingAuthDetail> rowsDescending(int count) {
        List<PendingAuthDetail> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(rowAt(NEWEST_TIME - index));
        }
        return rows;
    }

    /**
     * The time component of the fifth row of a six-row descending read, which is the page's last rendered
     * row.
     *
     * @return the time key the forward predicate should be given
     */
    private static int lastRenderedTime() {
        return NEWEST_TIME - (PendingAuthSummaryService.PAGE_SIZE - 1);
    }

    /**
     * Builds one authorization row at a stated time component.
     *
     * <p>Assumptions: the originating time is rendered from the same number as the key so an assertion can
     * identify a row by a value the view publishes. The key's own components are not published by the row
     * view, by design: it publishes the sealed selector instead.</p>
     *
     * @param authTime the decoded time component to key the row on
     * @return a fully populated authorization row
     */
    private static PendingAuthDetail rowAt(int authTime) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, authTime),
                "260806", String.format("%06d", authTime % 1_000_000), CARD_NUMBER,
                "0100", "2712", "0100", "0000", "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Builds a summary row carrying limits so the money conversion is exercised.
     *
     * @return the summary row every test in this class reads
     */
    private static PendingAuthSummary summaryRow() {
        PendingAuthSummary summary = new PendingAuthSummary(ACCOUNT_ID, CUSTOMER_ID);
        summary.refreshLimits(new BigDecimal("5000.00"), new BigDecimal("1000.00"));
        summary.recordApproved(new BigDecimal("250.00"));
        return summary;
    }

    /**
     * Seals a cursor naming one position within the account under test.
     *
     * <p>Assumptions: the cursor is minted through the REAL mapper by rendering a row and taking its
     * selector, rather than by composing a payload here. Composing one would duplicate the mapper's part
     * order and separator, and a test that duplicated them could pass while the two disagreed.</p>
     *
     * @param authTime the time component the cursor should name
     * @return a sealed cursor the service can redeem
     */
    private String sealedCursorAt(int authTime) {
        return this.mapper.toRowView(rowAt(authTime), SUBJECT).key();
    }

    /**
     * Evaluates the forward keyset predicate over a live table, as the repository would.
     *
     * <p>Assumptions: the double applies the SAME predicate the repository declares -- strictly below the
     * position, newest first, limited -- because a stub returning an unchanging list cannot demonstrate anything
     * about concurrent modification. The date component is constant across every row in this class, so
     * comparing the time component alone is the whole of the row-pair comparison here.</p>
     *
     * @param table the live table, ordered newest first, which a test may modify between calls
     * @param exclusiveTime the time component the result must fall strictly below
     * @param limit the row limit the service asked for
     * @return the qualifying rows, newest first, capped at {@code limit}
     */
    private static List<PendingAuthDetail> olderThan(List<PendingAuthDetail> table,
            int exclusiveTime, Limit limit) {
        List<PendingAuthDetail> qualifying = new ArrayList<>();
        for (PendingAuthDetail row : table) {
            if (row.getId().getAuthTime() < exclusiveTime && qualifying.size() < limit.max()) {
                qualifying.add(row);
            }
        }
        return qualifying;
    }

    /**
     * Lists the originating time of every row a page rendered, in the order it rendered them.
     *
     * @param view the page to read
     * @return the rendered originating times, which identify the rows uniquely in this class
     */
    private static List<String> renderedTimes(PendingAuthListView view) {
        List<String> times = new ArrayList<>(view.page().items().size());
        for (PendingAuthRowView row : view.page().items()) {
            times.add(row.authOrigTime());
        }
        return times;
    }

    /**
     * Builds the originating times the first {@code count} rows of the untouched table would render.
     *
     * @param count how many rows the sequence should cover
     * @return the expected rendered times, newest first
     */
    private static List<String> expectedTimes(int count) {
        List<String> times = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            times.add(String.format("%06d", (NEWEST_TIME - index) % 1_000_000));
        }
        return times;
    }
}
