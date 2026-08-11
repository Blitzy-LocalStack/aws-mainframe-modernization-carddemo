package com.carddemo.authorization.service;


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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the migrated browse loop: page size, the look-ahead probe, both directions, both navigation
 * boundaries, and the two ways a paging request is refused.
 *
 * <p><b>Purpose.</b> This class is the business-rule transcription test for
 * {@link PendingAuthSummaryService}, the stateless read behind the pending-authorization list. It asserts
 * five separable things, and the four nested groups exist because each answers a question the others cannot.
 * The cases at the top level cover the page envelope itself -- the size, the look-ahead probe, the two
 * navigation boundaries and the refusals. {@link NewestFirstKeysetDirection} covers the DIRECTION of the
 * keyset walk over a corpus spanning four dates, which is the one place a plausible predicate pages the
 * wrong way. {@link RowProjection} covers what a single row publishes, field by field.
 * {@link SystemErrorParity} covers the three failure sites the reference program distinguishes and the two
 * outcomes it refuses to merge. {@link StatelessReadBoundary} covers the read-only transaction it declares
 * and the server-side browse position it deliberately does not keep.
 *
 * <p>Assumptions: the repositories are doubles and the mapper is REAL, built over a real sealer with fixed
 * key material. A mapped view is what the operation returns, so mocking the mapper would leave the returned
 * page unasserted and would also hide the property that matters most here -- that the cursor a page hands
 * out is the cursor the next request can redeem. Sealing and opening under one instance is the only way to
 * prove that.</p>
 *
 * <p>Assumptions: the reference program is the specification for every number in this class. Every citation
 * is relative to {@code app/app-authorization-ims-db2-mq}, which is reference material this migration reads
 * and never modifies. No COBOL is corrected and none is deleted on account of anything asserted here.</p>
 *
 * <p>Assumptions: there is NO golden master for this path, and the gap is stated rather than left to be
 * discovered. {@code tests/README.md} L83 to L85 records that the online {@code CO*} CICS programs cannot
 * be run end to end without a CICS runtime, which the runner does not have, so only their extractable
 * logic is covered there. Nothing can therefore diff this service's output against a recorded run of
 * {@code cbl/COPAUS0C.cbl}: the reference program's SOURCE is the only oracle available, which is why every
 * number and every literal in this class carries the line it was read from instead of a fixture reference.
 * A reader who mistakes these assertions for golden-master comparisons would over-trust them.</p>
 *
 * <p>Assumptions: the migrated columns hold DECODED key values, and this single fact is what the ordering
 * cases below exist for. The reference key is a nines complement -- the {@code -9C} suffix on both
 * components at {@code cpy/CIPAUDTY.cpy} L20 and L21 -- so an ASCENDING walk of the sequence field
 * {@code ims/DBPAUTP0.dbd} L37 declares {@code TYPE=C} over those eight bytes is DESCENDING chronological
 * order, and the reference screen got newest-first for nothing. The complement is removed at the load
 * boundary, so the same order has to be asked for explicitly here and the forward comparison is inverted
 * relative to its intuitive reading. That is asserted rather than assumed, because the wrong form returns
 * real rows in a plausible order.</p>
 *
 * <p>Assumptions: the DL/I status shape behind the error literals quoted below is {@code DIBSTAT}, not a
 * program communication block status. {@code cbl/COPAUS0C.cbl} is a CICS program issuing {@code EXEC DLI},
 * so it reads {@code DIBSTAT} at L466, L499 and L979. The {@code CALL 'CBLTDLI'} programs of this same
 * extension read {@code PAUT-PCB-STATUS} instead, and {@code AuthorizationExtractRoundTripTest} is where
 * that other shape is asserted. The two are never unified: a status value is only meaningful against the
 * interface that produced it.</p>
 *
 * <p>Assumptions: the reference program's one implemented retry RETIRES with no target, and the absence is
 * recorded here so it does not read as an oversight. Its {@code SCHEDULE-PSB} paragraph terminates and
 * re-schedules the program specification block when the status says it was already scheduled -- L1007
 * tests {@code PSB-SCHEDULED-MORE-THAN-ONCE}, L1008 to L1009 terminate, L1011 to L1014 re-schedule and
 * L1017 to L1018 confirm -- and that is the only retry the extension implements, the three transient
 * statuses declared at L87 being referenced nowhere. Scheduling a program specification block has no
 * analogue once the datastore is reached through a connection pool, so there is no retry to assert on this
 * class and no {@code maxRetries} attribute anywhere in it.</p>
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

    /**
     * The account-context seam the four customer display fields are read through.
     */
    private AccountContextClient accountContext;

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
        this.accountContext = mock(AccountContextClient.class);
        // WHY : Assumptions: the seam is stubbed LENIENTLY to resolve nothing. Most cases here assert
        //       paging and boundary behaviour and never read a customer field, so a strict stub would be
        //       reported as unnecessary in each of them; and resolving empty rather than a fixture keeps
        //       those cases from quietly depending on data they are not about.
        Mockito.lenient().when(this.accountContext.customerDisplay(anyLong()))
                .thenReturn(Optional.empty());
        this.service = new PendingAuthSummaryService(this.summaries, this.details, this.mapper,
                this.accountContext);
        when(this.summaries.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(summaryRow()));
    }

    /**
     * The page size and the look-ahead are the reference screen's five rows plus one probe row.
     *
     * <p>Assumptions: the LIMIT the repository receives is asserted, not merely the row count returned,
     * because the probe row is what sets the next-page indicator. A service that asked for five would have
     * to count a second query to answer the same question, which is what the reference program avoids by
     * discovering one occurrence more than fits, and its probe is the further retrieval at L445 to
     * L452.</p>
     *
     * <p>Assumptions: five is the reference screen's figure and not a tuning choice, and THREE independent
     * declarations in {@code cbl/COPAUS0C.cbl} agree on it, which is why none of them is quoted alone. The
     * per-row key table is declared {@code CDEMO-CPVS-AUTH-KEYS PIC X(08) OCCURS 5 TIMES} at L126; the fill
     * loop is bounded {@code PERFORM UNTIL WS-IDX > 5 OR AUTHS-EOF OR ERR-FLG-ON} at L424; and the clearing
     * loop that blanks the row positions is bounded the same way at
     * {@code PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 5} at L611. A screen resized in one of the
     * three and not the others would be internally inconsistent, so agreement across all three is what
     * makes the figure the contract rather than an artifact of one loop.</p>
     *
     * <p>Assumptions: reading one row MORE than the page is a behavioural EQUIVALENT of the reference
     * mechanism and not a transcription of it, and the difference is stated plainly because the shapes do
     * not match. The reference reads ONE occurrence at a time and learns it has run out from the retrieval
     * itself: the two identical evaluations at L467 to L478 and L500 to L510 each route a
     * segment-not-found or an end-of-database status to {@code SET AUTHS-EOF TO TRUE} at L472 and L505
     * respectively. There is no sixth look-ahead read anywhere in the program. What IS preserved is the
     * observable answer -- whether a further page exists -- which that program carries in the explicit
     * indicator at L123 to L125. Trade-offs: this shape fetches one row on every page that is then thrown
     * away, in exchange for replacing six round trips with one; the discarded row is never rendered and
     * never contributes a boundary token, so the cost is one row of transfer and nothing observable.</p>
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
     * <p>Alternatives Considered: two other shapes of a backward page were available and each is wrong in a
     * way this assertion catches. Ordering the backward query descending and applying the limit returns the
     * newest rows in the whole account rather than the ones immediately preceding the current page; keeping
     * the tail of an ascending read rather than its head does the same thing one row at a time. The rows
     * returned here are asserted to be the ones adjacent to the caller's position and in newest-first order,
     * so either mistake fails. The label is deliberately not the one the rule reserves for replaced code:
     * nothing here corrects a defect, the target simply orders differently from the two plausible
     * alternatives.</p>
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
     * A cursor sent with no direction is read FORWARD from that cursor, not answered as the opening page.
     *
     * <p>Purpose: the two optional members have an ASYMMETRIC rule and this is the arm that is easiest to
     * get wrong in either direction. A cursor with no direction is honoured and read forward; a direction
     * with no cursor is refused, which the case below asserts. This case pins the permissive half, so a
     * later edit tightening the rule for symmetry fails here rather than silently breaking every client
     * that pages forward with a cursor alone.
     *
     * <p>Assumptions: the assertion is on the REPOSITORY call and not merely on the returned page, because
     * both the opening read and a forward move can return rows. Answering the opening page would satisfy a
     * size assertion while ignoring the caller's position entirely, and the two are distinguished only by
     * which query was issued -- the keyed walk or the strictly-older seek from the redeemed pair.
     *
     * <p>Assumptions: this is the behaviour the published contract now enumerates as one of four defined
     * combinations. The contract previously said the two members were sent together or not at all, which
     * this case shows was never true of the service; the two are corrected together, and
     * {@code AuthorizationApiContractTest} asserts the document states the rule so the pair cannot drift
     * apart again.
     */
    @Test
    @DisplayName("a cursor with no direction is read forward from that cursor")
    void cursorWithNoDirectionIsReadForward() {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                eq(ACCOUNT_ID), any(Limit.class))).thenReturn(rowsDescending(6));
        PendingAuthListView opening = this.service.list(ACCOUNT_ID, null, null, SUBJECT);
        String trailing = opening.page().lastKey();

        when(this.details.findOlderThan(eq(ACCOUNT_ID), any(), any(), any(Limit.class)))
                .thenReturn(rowsDescending(2));

        PendingAuthListView next = this.service.list(ACCOUNT_ID, trailing, null, SUBJECT);

        verify(this.details).findOlderThan(ACCOUNT_ID, AUTH_DATE,
                lastRenderedTime(), Limit.of(PendingAuthSummaryService.PAGE_SIZE + 1));
        assertThat(next.page().items()).hasSize(2);
        verify(this.details, never()).findNewerThan(any(), any(), any(), any(Limit.class));
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
     * <p>Alternatives Considered: sealing the token WITHOUT binding it to the account, which is the shape a
     * seal alone gives and which this assertion rejects. An authenticated token cannot be forged, but one
     * carrying no scope in its binding can legitimately be held and then presented while a different account
     * is named in the scope parameter; the predicate would then be built from the scope while the position
     * came from elsewhere, answering a page of the scoped account positioned inside a different one.
     * Refusing keyed to the cursor was chosen over answering a forbidden request, so a caller probing
     * accounts learns nothing about whether the position it replayed was valid somewhere else.</p>
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
     * @param count the {@code int} number of rows to build
     * @return the {@code List<PendingAuthDetail>} rows, newest first
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
     * @return the {@code int} time key the forward predicate should be given
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
     * @param authTime the {@code int} decoded time component to key the row on
     * @return the fully populated {@code PendingAuthDetail} row
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
     * @return the {@code PendingAuthSummary} row every test in this class reads
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
     * @param authTime the {@code int} time component the cursor should name
     * @return the sealed {@code String} cursor the service can redeem
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
     * @param table the live {@code List<PendingAuthDetail>} table, ordered newest first, which a test may
     *     modify between calls
     * @param exclusiveTime the {@code int} time component the result must fall strictly below
     * @param limit the {@code Limit} row cap the service asked for
     * @return the qualifying {@code List<PendingAuthDetail>} rows, newest first, capped at {@code limit}
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
     * @param view the {@code PendingAuthListView} page to read
     * @return the {@code List<String>} rendered originating times, which identify the rows uniquely in this
     *     class
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
     * @param count the {@code int} number of rows the sequence should cover
     * @return the expected {@code List<String>} rendered times, newest first
     */
    private static List<String> expectedTimes(int count) {
        List<String> times = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            times.add(String.format("%06d", (NEWEST_TIME - index) % 1_000_000));
        }
        return times;
    }

    /**
     * The four customer display fields reach the published summary, read once for the whole page.
     *
     * <p>Refactoring Rationale: this case exists because the screen published the segment's own identifiers
     * and totals and nothing else, while the record it publishes into declares a customer name, two address
     * lines and a telephone number that the reference composes from {@code GETCUSTDATA-BYCUST} at
     * {@code cbl/COPAUS0C.cbl} L920. Their absence was a functional-parity gap, and nothing failed when
     * they were absent because nothing asserted they were there. The label is used in the narrow sense that
     * user-specified Rule 1 (Explainability) reserves it for -- an earlier revision of the code under test
     * is being replaced and this states what was wrong with it -- and it is the only place in this class
     * that sense applies. It claims NONE of the registered
     * behavioural divergences: those are listed in the package charter with owners, this class owns none of
     * them, and the label here is about a corrected omission in the migration's own prior revision rather
     * than about a departure from the baseline. Everywhere else that the target merely differs from the
     * reference, this class uses {@code Alternatives Considered:} instead.</p>
     *
     * <p>Assumptions: the call COUNT is asserted as well as the values, and the count is the point. Every
     * authorization beneath a summary belongs to the same account and so the same customer, so a per-row
     * read would make the screen's cost grow with the page size for data identical on every row -- and a
     * test that only checked the values would pass either way.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("the customer display fields are published and are read once per page")
    void theCustomerDisplayFieldsArePublishedAndAreReadOncePerPage() {
        AccountContextClient.CustomerDisplay resolved = new AccountContextClient.CustomerDisplay(
                "SMITH JOHN", "1 HIGH STREET", "SPRINGFIELD IL", "5550001111");
        Mockito.reset(this.accountContext);
        when(this.accountContext.customerDisplay(anyLong())).thenReturn(Optional.of(resolved));
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                eq(ACCOUNT_ID), any(Limit.class))).thenReturn(rowsDescending(3));

        PendingAuthListView view = this.service.list(ACCOUNT_ID, null, null, SUBJECT);

        assertThat(view.summary().customerName()).isEqualTo("SMITH JOHN");
        assertThat(view.summary().addressLine1()).isEqualTo("1 HIGH STREET");
        assertThat(view.summary().addressLine2()).isEqualTo("SPRINGFIELD IL");
        assertThat(view.summary().phoneNumber1()).isEqualTo("5550001111");
        assertThat(view.page().items()).hasSize(3);
        verify(this.accountContext, times(1)).customerDisplay(anyLong());
    }

    /**
     * An unresolved customer publishes the screen with blank display fields rather than refusing it.
     *
     * <p>Assumptions: the totals are asserted PRESENT in the same case, because that is the whole argument
     * for degrading rather than refusing -- the authorization figures are what the screen is for, and
     * withdrawing them because a display name could not be resolved would remove the information the
     * operator can act on over the information they cannot. The reference has a not-found arm that leaves
     * the fields unfilled and continues.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unresolved customer leaves the display fields blank and still publishes the totals")
    void anUnresolvedCustomerLeavesTheDisplayFieldsBlankAndStillPublishesTheTotals() {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                eq(ACCOUNT_ID), any(Limit.class))).thenReturn(rowsDescending(1));

        PendingAuthListView view = this.service.list(ACCOUNT_ID, null, null, SUBJECT);

        assertThat(view.summary().customerName()).isNull();
        assertThat(view.summary().addressLine1()).isNull();
        assertThat(view.summary().phoneNumber1()).isNull();
        assertThat(view.summary().approvedAuthCnt())
                .as("the authorization totals are published whether or not a customer resolved")
                .isNotNull();
        assertThat(view.page().items()).hasSize(1);
    }

    /**
     * The complement base of the three-byte date component, which is what the load boundary subtracts from.
     *
     * <p>Assumptions: the base is all nines across the declared digits, five of them for
     * {@code PA-AUTH-DATE-9C PIC S9(05) COMP-3} at {@code cpy/CIPAUDTY.cpy} L20. It is named here only so
     * the raw-value claims below can be stated as arithmetic instead of as quoted bytes, which keeps the
     * decoding proof where the package charter puts it.</p>
     */
    private static final int DATE_COMPLEMENT_BASE = 99_999;

    /**
     * The complement base of the five-byte time component.
     *
     * <p>Assumptions: nine digits, for {@code PA-AUTH-TIME-9C PIC S9(09) COMP-3} at
     * {@code cpy/CIPAUDTY.cpy} L21.</p>
     */
    private static final int TIME_COMPLEMENT_BASE = 999_999_999;

    /**
     * Builds the seven-row corpus the ordering cases page through, newest first.
     *
     * <p>Assumptions: every date and time below is the DECODED value of a committed 200-byte segment image,
     * so this corpus is the fixture corpus rather than a set of invented numbers, and the images are named
     * against each row. Two of them are the images the ordering hazards were committed for. From
     * {@code src/test/resources/fixtures/pautdtl1-date-formats.bin}, whose two records decode to
     * {@code 99365}/{@code 235959000} and {@code 24197}/{@code 91530000}. From
     * {@code pautdtl1-raw-complement-trap.bin}, one record decoding to {@code 24100}/{@code 120000000}.
     * From {@code pautdtl1-time-leading-nines.bin}, one decoding to {@code 24100}/{@code 1000}. From
     * {@code pautdtl1-order-same-day-times.bin}, three records sharing the date {@code 24095} with times
     * {@code 163045750}, {@code 163045500} and {@code 91500000}.</p>
     *
     * <p>Assumptions: the corpus is built from DECODED values rather than by decoding those images here,
     * which is the package charter's division of labour. Whether the complement arithmetic is right is the
     * shared kernel's question and is settled by {@code PendingAuthDetailMapperTest} and
     * {@code SegmentConversionContractTest}; what this class asks is what the service DOES with values that
     * have already been decoded. Re-decoding the bytes here would restate a proof that lives elsewhere and
     * could drift from it.</p>
     *
     * <p>Assumptions: the corpus spans FOUR distinct dates and carries same-date ties on two of them, and
     * that shape is deliberate rather than incidental. A corpus on one date cannot distinguish a cursor
     * carrying both key components from one carrying only the time, and cannot distinguish a row-pair
     * comparison from a comparison on the date alone. Both distinctions are asserted below and neither is
     * observable without more than one date.</p>
     *
     * @return the {@code List<PendingAuthDetail>} corpus in newest-first order, seven rows over four dates
     */
    private static List<PendingAuthDetail> multiDayCorpus() {
        List<PendingAuthDetail> corpus = new ArrayList<>();
        corpus.add(corpusRow(99_365, 235_959_000, "991231", "235959"));
        corpus.add(corpusRow(24_197, 91_530_000, "240715", "091530"));
        corpus.add(corpusRow(24_100, 120_000_000, "240409", "120000"));
        corpus.add(corpusRow(24_100, 1_000, "240409", "000001"));
        corpus.add(corpusRow(24_095, 163_045_750, "240404", "163045"));
        corpus.add(corpusRow(24_095, 163_045_500, "240404", "163045"));
        corpus.add(corpusRow(24_095, 91_500_000, "240404", "091500"));
        return corpus;
    }

    /**
     * Builds one corpus row whose published transaction identifier encodes its own decoded key.
     *
     * <p>Assumptions: the identifier is the row's IDENTITY in every ordering assertion below, and it is
     * derived from the key rather than chosen, because the two same-date images
     * {@code pautdtl1-order-same-day-times.bin} carries at times {@code 163045750} and {@code 163045500}
     * render the SAME six-character originating time {@code 163045} -- they differ only in the millisecond
     * positions the originating time does not carry. Identifying rows by the rendered time would therefore
     * make those two indistinguishable and would silently weaken every same-date assertion. Trade-offs: the
     * identifier is synthetic where the rest of the row is fixture-derived, which is accepted because it is
     * asserted only as a label; the one case that asserts the identifier as a CONTRACT value uses the
     * fixture's own fifteen-character identifier instead.</p>
     *
     * @param authDate the {@code int} decoded date component, which is the higher-order half of the key
     * @param authTime the {@code int} decoded time component, which is the lower-order half
     * @param authOrigDate the {@code String} six-character originating date the segment carries
     * @param authOrigTime the {@code String} six-character originating time the segment carries
     * @return the {@code PendingAuthDetail} row, keyed and labelled from the two decoded components
     */
    private static PendingAuthDetail corpusRow(int authDate, int authTime, String authOrigDate,
            String authOrigTime) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, authDate, authTime),
                authOrigDate, authOrigTime, CARD_NUMBER,
                "0100", "2712", "0100", "0000", "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", String.format("T%05d%09d", authDate, authTime),
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Names one corpus row the way the assertions below refer to it.
     *
     * @param authDate the {@code int} decoded date component of the wanted row
     * @param authTime the {@code int} decoded time component of the wanted row
     * @return the {@code String} transaction identifier that row publishes
     */
    private static String corpusId(int authDate, int authTime) {
        return String.format("T%05d%09d", authDate, authTime);
    }

    /**
     * Lists the transaction identifier of every row a page rendered, in the order it rendered them.
     *
     * @param view the {@code PendingAuthListView} page to read
     * @return the {@code List<String>} identifiers, in render order
     */
    private static List<String> renderedIds(PendingAuthListView view) {
        List<String> ids = new ArrayList<>(view.page().items().size());
        for (PendingAuthRowView row : view.page().items()) {
            ids.add(row.transactionId());
        }
        return ids;
    }

    /**
     * Compares two keys as the ordered PAIR the repository declares, rather than on either half alone.
     *
     * <p>Assumptions: this is the whole of the newest-first ordering, and it is written once here so that
     * the three doubles below cannot disagree about it. A comparison on the date alone, or on the time
     * alone, is exactly the defect the cases below are built to catch, so the correct form has to exist in
     * one place that those cases can be measured against.</p>
     *
     * @param leftDate the {@code int} date component of the left key
     * @param leftTime the {@code int} time component of the left key
     * @param rightDate the {@code int} date component of the right key
     * @param rightTime the {@code int} time component of the right key
     * @return a negative {@code int} when the left key is older, zero when the two are equal, and a
     *     positive {@code int} when the left key is newer
     */
    private static int compareKeys(int leftDate, int leftTime, int rightDate, int rightTime) {
        if (leftDate != rightDate) {
            return Integer.compare(leftDate, rightDate);
        }
        return Integer.compare(leftTime, rightTime);
    }

    /**
     * Selects the rows strictly older than a position, newest first, as the forward query declares.
     *
     * @param corpus the {@code List<PendingAuthDetail>} table to select from, already newest first
     * @param authDate the {@code int} date component of the exclusive position
     * @param authTime the {@code int} time component of the exclusive position
     * @param limit the {@code Limit} row cap the service asked for
     * @return the {@code List<PendingAuthDetail>} qualifying rows, newest first, capped at {@code limit}
     */
    private static List<PendingAuthDetail> strictlyOlder(List<PendingAuthDetail> corpus, int authDate,
            int authTime, Limit limit) {
        List<PendingAuthDetail> qualifying = new ArrayList<>();
        for (PendingAuthDetail row : corpus) {
            boolean older = compareKeys(row.getId().getAuthDate(), row.getId().getAuthTime(),
                    authDate, authTime) < 0;
            if (older && qualifying.size() < limit.max()) {
                qualifying.add(row);
            }
        }
        return qualifying;
    }

    /**
     * Selects the rows strictly newer than a position in ASCENDING order, as the backward query declares.
     *
     * <p>Assumptions: ascending is the query's own order and the reversal to newest-first belongs to the
     * service, so this double must NOT pre-reverse. A double that returned newest-first here would hide
     * whichever end of the list the service trims, which is the property the backward case asserts.</p>
     *
     * @param corpus the {@code List<PendingAuthDetail>} table to select from, held newest first
     * @param authDate the {@code int} date component of the exclusive position
     * @param authTime the {@code int} time component of the exclusive position
     * @param limit the {@code Limit} row cap the service asked for
     * @return the {@code List<PendingAuthDetail>} qualifying rows, oldest first, capped at {@code limit}
     */
    private static List<PendingAuthDetail> strictlyNewerAscending(List<PendingAuthDetail> corpus,
            int authDate, int authTime, Limit limit) {
        List<PendingAuthDetail> qualifying = new ArrayList<>();
        for (int index = corpus.size() - 1; index >= 0; index--) {
            PendingAuthDetail row = corpus.get(index);
            boolean newer = compareKeys(row.getId().getAuthDate(), row.getId().getAuthTime(),
                    authDate, authTime) > 0;
            if (newer && qualifying.size() < limit.max()) {
                qualifying.add(row);
            }
        }
        return qualifying;
    }

    /**
     * Points the three page-reading doubles at one corpus, each evaluating its own declared ordering.
     *
     * <p>Assumptions: the opening read is served by taking the head of the corpus rather than by comparing
     * against a lowest-possible key, because the repository declares that case as an unqualified ordered
     * walk with no comparison at all. Inventing a sentinel position for it here would test a query the
     * service never issues.</p>
     *
     * <p>Assumptions: the doubles read the corpus LIVE on every call rather than capturing a snapshot, so a
     * test may modify the table between two requests and the second request sees the change. That is what
     * lets a concurrent insert be expressed at all.</p>
     *
     * @param corpus the {@code List<PendingAuthDetail>} table the doubles should serve, newest first
     */
    private void stubCorpus(List<PendingAuthDetail> corpus) {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(eq(ACCOUNT_ID),
                any(Limit.class)))
                .thenAnswer(call -> new ArrayList<>(corpus.subList(0,
                        Math.min(((Limit) call.getArgument(1)).max(), corpus.size()))));
        when(this.details.findOlderThan(eq(ACCOUNT_ID), any(), any(), any(Limit.class)))
                .thenAnswer(call -> strictlyOlder(corpus, call.getArgument(1), call.getArgument(2),
                        call.getArgument(3)));
        when(this.details.findNewerThan(eq(ACCOUNT_ID), any(), any(), any(Limit.class)))
                .thenAnswer(call -> strictlyNewerAscending(corpus, call.getArgument(1),
                        call.getArgument(2), call.getArgument(3)));
    }

    /**
     * Recovers the date component a corpus row's published identifier encodes.
     *
     * @param transactionId the {@code String} identifier a corpus row published
     * @return the {@code int} decoded date component that row is keyed on
     */
    private static int dateIn(String transactionId) {
        return Integer.parseInt(transactionId.substring(1, 6));
    }

    /**
     * Recovers the time component a corpus row's published identifier encodes.
     *
     * @param transactionId the {@code String} identifier a corpus row published
     * @return the {@code int} decoded time component that row is keyed on
     */
    private static int timeIn(String transactionId) {
        return Integer.parseInt(transactionId.substring(6));
    }

    /**
     * The direction of the keyset walk, which is the one place a plausible predicate pages the wrong way.
     *
     * <p>Purpose. This group asserts that a forward page moves to OLDER rows and a backward page to NEWER
     * ones, over a corpus that spans four dates and carries same-date ties. It exists because the correct
     * predicate is the counter-intuitive one, and because the incorrect one is not merely wrong but
     * plausible: it compiles, it returns real rows, it returns them in a defensible order, and no schema
     * constraint contradicts it. Every case below is chosen so that the incorrect form fails it.</p>
     *
     * <p>Assumptions: the substitution this group measures is the one AAP Rule T5 directs, under which the
     * reference browse verbs -- start-browse, read-next, read-previous and end-browse -- become ONE
     * keyset-paginated query rather than a sequence of positioned reads. What that rule does not settle, and
     * what these cases therefore have to, is the DIRECTION of the resulting comparison.</p>
     *
     * <p>Assumptions: the corpus is the DECODED fixture corpus described on {@link #multiDayCorpus()}, and
     * the repository doubles here evaluate the real ordering rather than returning a canned list. A double
     * that returned a fixed list would agree with any predicate the service passed it, which would leave
     * this entire group asserting nothing about direction.</p>
     */
    @Nested
    @DisplayName("the newest-first keyset walk")
    class NewestFirstKeysetDirection {

        /**
         * Paging forward twice walks strictly OLDER rows, across two day boundaries and into a same-date run.
         *
         * <p>Assumptions: this is the assertion the whole group exists for, and the mistake it catches is
         * the intuitive reading of the contract rather than a careless one. "The page after this one"
         * suggests a key GREATER than the cursor under an ascending order, and that is the wrong form here.
         * The reason is that the reference key is a nines complement at {@code cpy/CIPAUDTY.cpy} L20 and
         * L21, and {@code ims/DBPAUTP0.dbd} L37 declares the sequence field over those eight bytes
         * {@code TYPE=C}, so an ASCENDING byte walk of the reference is DESCENDING in time and the screen
         * received newest-first without asking. The complement is removed when the rows are loaded, so the
         * migrated columns hold ordinary increasing values and the same traversal has to be expressed as
         * {@code order by auth_date desc, auth_time desc} with a row-pair comparison strictly BELOW the
         * cursor. A greater-than comparison under an ascending order returns rows that are real, ordered
         * and newer, so it pages progressively backwards through history while looking correct; the
         * concatenation and the pairwise comparison below are what make that failure loud.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("paging forward twice walks strictly older rows across day boundaries")
        void forwardPagingWalksStrictlyOlderRowsAcrossDayBoundaries() {
            List<PendingAuthDetail> corpus = multiDayCorpus();
            stubCorpus(corpus);

            PendingAuthListView first =
                    PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, null, null, SUBJECT);
            PendingAuthListView second = PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID,
                    first.page().lastKey(), PendingAuthSummaryService.DIRECTION_NEXT, SUBJECT);

            assertThat(renderedIds(first))
                    .as("the five newest rows, spanning the dates 99365, 24197, 24100 and 24095")
                    .containsExactly(corpusId(99_365, 235_959_000), corpusId(24_197, 91_530_000),
                            corpusId(24_100, 120_000_000), corpusId(24_100, 1_000),
                            corpusId(24_095, 163_045_750));
            assertThat(first.page().hasNext()).isTrue();
            assertThat(renderedIds(second))
                    .as("the remaining two rows, both on the oldest date in the corpus")
                    .containsExactly(corpusId(24_095, 163_045_500), corpusId(24_095, 91_500_000));
            assertThat(second.page().hasNext()).isFalse();

            // WHY : Assumptions: the pairwise comparison is asserted in ADDITION to the two exact
            //       sequences, because an implementation that paged the wrong way would still produce two
            //       exact sequences -- just the wrong ones. Comparing every second-page row against every
            //       first-page row states the invariant itself rather than one instance of it, so it holds
            //       whatever rows a future corpus carries.
            for (String olderId : renderedIds(second)) {
                for (String newerId : renderedIds(first)) {
                    assertThat(compareKeys(dateIn(olderId), timeIn(olderId), dateIn(newerId),
                            timeIn(newerId)))
                            .as("%s must be strictly older than %s", olderId, newerId)
                            .isNegative();
                }
            }

            // WHY : Assumptions: the incorrect predicate is evaluated over the SAME corpus and its answer
            //       shown to be disjoint from the correct one, which is what turns "the direction is
            //       inverted" from a claim in a comment into something the build checks. The greater-than
            //       form is the backward query, so it is already available and needs no second double.
            List<PendingAuthDetail> ascendingFromSameCursor = strictlyNewerAscending(corpus, 24_095,
                    163_045_750, Limit.of(PendingAuthSummaryService.PAGE_SIZE + 1));
            assertThat(ascendingFromSameCursor).isNotEmpty();
            assertThat(ascendingFromSameCursor)
                    .extracting(PendingAuthDetail::getTransactionId)
                    .as("a greater-than comparison from the same cursor returns rows already shown")
                    .doesNotContainAnyElementsOf(renderedIds(second))
                    .isSubsetOf(renderedIds(first));
        }

        /**
         * The boundary cursor carries BOTH key components, which only a multi-date corpus can establish.
         *
         * <p>Assumptions: the eight-byte position is a group of exactly two packed items --
         * {@code PA-AUTH-DATE-9C} in three bytes at {@code cpy/CIPAUDTY.cpy} L20 and
         * {@code PA-AUTH-TIME-9C} in five at L21 -- and {@code ims/DBPAUTP0.dbd} L37 declares all eight as
         * the sequence field, so a position that carried only one of the two would not be a position at
         * all. Over a corpus on a single date the claim is unfalsifiable, because the date is then a
         * constant that any predicate agrees on; the demonstration below is what makes it falsifiable.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the forward cursor carries both the date and the time component")
        void theForwardCursorCarriesBothKeyComponents() {
            List<PendingAuthDetail> corpus = multiDayCorpus();
            stubCorpus(corpus);

            PendingAuthListView first =
                    PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, null, null, SUBJECT);
            PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, first.page().lastKey(),
                    PendingAuthSummaryService.DIRECTION_NEXT, SUBJECT);

            verify(PendingAuthSummaryServiceTest.this.details).findOlderThan(ACCOUNT_ID, 24_095,
                    163_045_750, Limit.of(PendingAuthSummaryService.PAGE_SIZE + 1));

            // WHY : Assumptions: the consequence of dropping the date is spelled out as a concrete repeat
            //       rather than left abstract. The page's closing time is 163045750 on date 24095, and two
            //       rows the caller has ALREADY been shown carry smaller times on NEWER dates -- 91530000
            //       on 24197 and 1000 on 24100. A comparison on the time alone therefore qualifies both of
            //       them and hands the caller rows from the page it just read, which is a defect a
            //       single-date corpus cannot expose because every row would share one date.
            List<String> qualifyingOnTimeAlone = new ArrayList<>();
            for (PendingAuthDetail row : corpus) {
                if (row.getId().getAuthTime().intValue() < 163_045_750) {
                    qualifyingOnTimeAlone.add(row.getTransactionId());
                }
            }
            assertThat(qualifyingOnTimeAlone)
                    .as("a time-only comparison admits rows on newer dates")
                    .contains(corpusId(24_197, 91_530_000), corpusId(24_100, 1_000));
            assertThat(renderedIds(first))
                    .as("and those two rows were already rendered, so the caller would see them twice")
                    .contains(corpusId(24_197, 91_530_000), corpusId(24_100, 1_000));
        }

        /**
         * A cursor landing inside a same-date run still returns the remainder of that run.
         *
         * <p>Alternatives Considered: expressing the forward predicate as two independent comparisons --
         * one on the date and one on the time -- instead of as a comparison of the pair. Rejected, and this
         * case is why. The closing position of the first page is {@code 24095}/{@code 163045750}, which is
         * one of three rows the corpus carries on that date, so a predicate reading
         * {@code auth_date < :cursorDate} alone qualifies NO row at all: nothing in the corpus is older
         * than {@code 24095} by date. The browse would end two rows early and report itself complete, and
         * every other case in this class would still pass. The pair comparison admits a row whose date
         * EQUALS the cursor's when its time is lower, which is the only form that finishes a partially
         * consumed date.</p>
         *
         * <p>Assumptions: the three same-date rows are the decoded contents of
         * {@code src/test/resources/fixtures/pautdtl1-order-same-day-times.bin}, which was committed for
         * exactly this hazard.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a cursor inside a same-date run still returns the remainder of that run")
        void sameDateRowsBeyondTheCursorAreStillReturned() {
            List<PendingAuthDetail> corpus = multiDayCorpus();
            stubCorpus(corpus);

            PendingAuthListView first =
                    PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, null, null, SUBJECT);
            PendingAuthListView second = PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID,
                    first.page().lastKey(), PendingAuthSummaryService.DIRECTION_NEXT, SUBJECT);

            assertThat(renderedIds(first)).endsWith(corpusId(24_095, 163_045_750));
            assertThat(renderedIds(second))
                    .as("both remaining rows of the partially consumed date")
                    .containsExactly(corpusId(24_095, 163_045_500), corpusId(24_095, 91_500_000));

            List<String> qualifyingOnDateAlone = new ArrayList<>();
            for (PendingAuthDetail row : corpus) {
                if (row.getId().getAuthDate().intValue() < 24_095) {
                    qualifyingOnDateAlone.add(row.getTransactionId());
                }
            }
            assertThat(qualifyingOnDateAlone)
                    .as("a date-only comparison from this cursor qualifies nothing, ending the browse early")
                    .isEmpty();
        }

        /**
         * The ordering is over DECODED values, so a leaked stored value would move a row.
         *
         * <p>Assumptions: two of the corpus rows share the date {@code 24100} and come from images
         * committed for this hazard -- {@code pautdtl1-raw-complement-trap.bin}, whose stored time reads as
         * an hour that cannot exist until it is decoded, and {@code pautdtl1-time-leading-nines.bin}, whose
         * stored time is nearly all nines. The claim is asserted as ARITHMETIC over the two complement
         * bases rather than by decoding the images, because whether the complement arithmetic is correct
         * belongs to the shared kernel's own tests; what is asserted here is only that a stored value
         * reaching the ordering would place a row somewhere else.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the decoded key orders the trap row behind the earlier date, not ahead of it")
        void theDecodedKeyOrdersTheTrapRowBehindTheEarlierDate() {
            stubCorpus(multiDayCorpus());

            PendingAuthListView first =
                    PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, null, null, SUBJECT);

            assertThat(renderedIds(first)).containsSubsequence(corpusId(24_197, 91_530_000),
                    corpusId(24_100, 120_000_000), corpusId(24_100, 1_000));

            // WHY : Assumptions: the stored form of the trap row's date is LARGER than the decoded date of
            //       the row that precedes it, so a descending order over stored values would lift the trap
            //       row one position and put it ahead of 24197. That single displacement is the whole of
            //       the hazard: the sequence stays well formed and only its order changes, which is why the
            //       position rather than the value is what this case pins.
            int trapStoredDate = DATE_COMPLEMENT_BASE - 24_100;
            assertThat(trapStoredDate).isEqualTo(75_899).isGreaterThan(24_197);

            // WHY : Assumptions: the two stored times are additionally shown to be unreadable as clocks,
            //       which is what distinguishes a leaked value from a merely mis-sorted one. The leading
            //       two digits of a nine-digit time are its hour, and both stored forms yield an hour past
            //       twenty-three, so a leak surfaces as an impossible reading rather than a plausible one.
            int trapStoredTime = TIME_COMPLEMENT_BASE - 120_000_000;
            int ninesStoredTime = TIME_COMPLEMENT_BASE - 1_000;
            assertThat(trapStoredTime).isEqualTo(879_999_999);
            assertThat(ninesStoredTime).isEqualTo(999_998_999);
            assertThat(trapStoredTime / 10_000_000).as("hour 87 is not a clock reading").isGreaterThan(23);
            assertThat(ninesStoredTime / 10_000_000).as("nor is hour 99").isGreaterThan(23);
        }

        /**
         * The two boundary tokens name the rows the page RENDERED, never the discarded look-ahead row.
         *
         * <p>Assumptions: the sixth row a full page reads is a probe and nothing else, so the closing
         * position stays the key of the fifth. The reference program is explicit about this: it stores each
         * displayed row's key inside the fill loop at L434 to L435, and the further retrieval at L445 to
         * L452 that discovers whether more exist stores nothing. A closing token taken from the probe row
         * would skip that row on the next request, which is a silently lost row rather than a visible
         * fault.</p>
         *
         * <p>Assumptions: the tokens are OPAQUE, so they are round-tripped and never asserted as text. What
         * position a token encodes is established by redeeming it and observing the query it produces,
         * which is also the only assertion that survives a change of keying.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the boundary tokens name the rendered rows and not the look-ahead row")
        void theBoundaryTokensNameTheRenderedRowsAndNotTheProbeRow() {
            stubCorpus(multiDayCorpus());

            PendingAuthListView first =
                    PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, null, null, SUBJECT);
            assertThat(first.page().firstKey()).isNotNull();
            assertThat(first.page().lastKey()).isNotNull();

            PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, first.page().lastKey(),
                    PendingAuthSummaryService.DIRECTION_NEXT, SUBJECT);
            verify(PendingAuthSummaryServiceTest.this.details).findOlderThan(ACCOUNT_ID, 24_095,
                    163_045_750, Limit.of(PendingAuthSummaryService.PAGE_SIZE + 1));
            verify(PendingAuthSummaryServiceTest.this.details, never()).findOlderThan(ACCOUNT_ID, 24_095,
                    163_045_500, Limit.of(PendingAuthSummaryService.PAGE_SIZE + 1));

            PendingAuthListView backward = PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID,
                    first.page().firstKey(), PendingAuthSummaryService.DIRECTION_PREVIOUS, SUBJECT);
            assertThat(backward.screenMessage())
                    .as("the leading token names the newest row, so nothing precedes it")
                    .isEqualTo(PendingAuthListView.MESSAGE_TOP_OF_PAGE);
        }
    }

    /**
     * What a list row publishes, which the reference program settles field by field.
     *
     * <p>Purpose. This group covers {@code POPULATE-AUTH-LIST} at {@code cbl/COPAUS0C.cbl} L522 to L554,
     * the paragraph that moves one retrieved occurrence into one screen row. Every case below pins a choice
     * that paragraph makes and that a reader reconstructing it from the field names alone would plausibly
     * make differently -- which amount is shown, which literal marks approval, how wide the identifier is,
     * and whether the year is widened.</p>
     *
     * <p>Assumptions: the row projection is performed by
     * {@code com.carddemo.authorization.mapper.PendingAuthViewMapper}, and this group asserts it THROUGH the
     * service because that is the composition a caller receives. What it does not assert is anything the
     * package charter places on the mapper: primary-account-number masking and card-verification-value
     * suppression are that class's own, and restating them here would create a second place they could
     * drift.</p>
     */
    @Nested
    @DisplayName("the list row projection")
    class RowProjection {

        /**
         * Builds one authorization row with the projection-relevant members set explicitly.
         *
         * <p>Assumptions: every member this group varies is a parameter and everything else is fixed, so a
         * case reads as the one difference it is about. The members left fixed are the ones the reference
         * paragraph moves without transformation.</p>
         *
         * @param authRespCode the {@code String} two-character response code the approval flag derives from
         * @param transactionAmount the {@code BigDecimal} amount requested, which the list does NOT show
         * @param approvedAmount the {@code BigDecimal} amount approved, which the list DOES show
         * @param transactionId the {@code String} identifier the row publishes
         * @param matchStatus the {@code String} one-character match status the row publishes
         * @param authOrigDate the {@code String} six-character originating date the row publishes
         * @return the {@code PendingAuthDetail} row shaped for one projection case
         */
        private PendingAuthDetail projectionRow(String authRespCode, BigDecimal transactionAmount,
                BigDecimal approvedAmount, String transactionId, String matchStatus,
                String authOrigDate) {
            return new PendingAuthDetail(
                    new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, NEWEST_TIME),
                    authOrigDate, "163045", CARD_NUMBER,
                    "0100", "2712", "0100", "0000", "AUTH01", authRespCode, "0000", "003000",
                    transactionAmount, approvedAmount,
                    "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                    "SPRINGFIELD", "IL", "627040000", transactionId, matchStatus);
        }

        /**
         * Serves one row as the whole opening page and returns what the caller received.
         *
         * @param row the {@code PendingAuthDetail} row the page read should return
         * @return the {@code PendingAuthRowView} the caller received for that row
         */
        private PendingAuthRowView projectOnly(PendingAuthDetail row) {
            when(PendingAuthSummaryServiceTest.this.details
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(eq(ACCOUNT_ID),
                            any(Limit.class)))
                    .thenReturn(List.of(row));
            PendingAuthListView view =
                    PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, null, null, SUBJECT);
            assertThat(view.page().items()).hasSize(1);
            return view.page().items().get(0);
        }

        /**
         * The amount a list row shows is the APPROVED amount, not the amount the transaction asked for.
         *
         * <p>Assumptions: the two are separate stored fields --
         * {@code PA-TRANSACTION-AMT PIC S9(10)V99 COMP-3} at {@code cpy/CIPAUDTY.cpy} L34 and
         * {@code PA-APPROVED-AMT} at L35 -- and {@code cbl/COPAUS0C.cbl} L525 moves the second of them into
         * the work field the row is built from. The two differ here on purpose: a fixture that set them
         * equal, which is the natural thing to write, would leave the choice unasserted and an
         * implementation reading the requested amount would pass. The choice is also demonstrably
         * deliberate rather than incidental, because the detail screen makes the same one and the two
         * programs would have to have agreed by accident otherwise.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the list amount is the approved amount and not the transaction amount")
        void theListAmountIsTheApprovedAmountAndNotTheTransactionAmount() {
            PendingAuthRowView row = projectOnly(projectionRow("00", new BigDecimal("250.00"),
                    new BigDecimal("175.50"), "TX0000000000001",
                    PendingAuthDetail.MATCH_STATUS_PENDING, "240715"));

            assertThat(row.amount().amount()).isEqualByComparingTo(new BigDecimal("175.50"));
            assertThat(row.amount().amount())
                    .as("the requested amount is not what a list row shows")
                    .isNotEqualByComparingTo(new BigDecimal("250.00"));
        }

        /**
         * Approval is published only for the one response code the reference treats as approved.
         *
         * <p>Assumptions: {@code cbl/COPAUS0C.cbl} L536 to L540 is an equality against {@code '00'} with an
         * unconditional {@code ELSE}, and {@code cpy/CIPAUDTY.cpy} L30 to L31 declare
         * {@code PA-AUTH-RESP-CODE PIC X(02)} with {@code 88 PA-AUTH-APPROVED VALUE '00'} as its only named
         * value. Both arms are asserted because a one-arm assertion cannot tell a correct test of the code
         * from an implementation that published approval unconditionally.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the approval flag is approved for response code 00 and declined for anything else")
        void theApprovalFlagIsApprovedOnlyForTheZeroZeroResponseCode() {
            PendingAuthRowView approved = projectOnly(projectionRow("00", new BigDecimal("250.00"),
                    new BigDecimal("250.00"), "TX0000000000001",
                    PendingAuthDetail.MATCH_STATUS_PENDING, "240715"));
            assertThat(approved.approvalStatus())
                    .isEqualTo(PendingAuthRowView.APPROVAL_STATUS_APPROVED);

            Mockito.reset(PendingAuthSummaryServiceTest.this.details);
            PendingAuthRowView declined = projectOnly(projectionRow("05", new BigDecimal("250.00"),
                    new BigDecimal("0.00"), "TX0000000000002",
                    PendingAuthDetail.MATCH_STATUS_DECLINED, "240715"));
            assertThat(declined.approvalStatus())
                    .isEqualTo(PendingAuthRowView.APPROVAL_STATUS_DECLINED);
        }

        /**
         * The identifier is published at its DECLARED fifteen characters, not at the screen slot's sixteen.
         *
         * <p>Assumptions: {@code cbl/COPAUS0C.cbl} L547 moves
         * {@code PA-TRANSACTION-ID PIC X(15)} at {@code cpy/CIPAUDTY.cpy} L44 into
         * {@code TRNID01I PIC X(16)} at {@code cpy-bms/COPAU00.cpy} L156, and a COBOL move into a wider
         * alphanumeric receiver left-justifies and blank-fills, so the screen field holds fifteen characters
         * followed by exactly one blank. That sixteenth position is a LAYOUT artifact of the map and carries
         * no data, so the published value is the fifteen characters themselves. Both failure directions are
         * asserted rather than only the width: padding to sixteen would put a trailing blank into a value
         * clients compare and index on, and truncating to fourteen would silently drop a digit. The trailing
         * blank is dropped rather than carried, and that is recorded here rather than left to be rediscovered
         * as an apparent inconsistency with the map.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the transaction identifier keeps its declared fifteen characters")
        void theTransactionIdKeepsItsDeclaredFifteenCharacters() {
            PendingAuthRowView row = projectOnly(projectionRow("00", new BigDecimal("250.00"),
                    new BigDecimal("250.00"), "TX0000000000001",
                    PendingAuthDetail.MATCH_STATUS_PENDING, "240715"));

            assertThat(row.transactionId()).isEqualTo("TX0000000000001").hasSize(15);
            assertThat(row.transactionId())
                    .as("the sixteenth position of the screen slot is layout and is not published")
                    .doesNotEndWith(" ");
        }

        /**
         * The two-digit year of the originating date is carried across, never widened to four.
         *
         * <p>Assumptions: {@code cbl/COPAUS0C.cbl} L531 to L534 slices
         * {@code PA-AUTH-ORIG-DATE} into a year, a month and a day and reassembles them month-first, and at
         * no point does it consult a century or add one -- the assembled field is two digits of year and
         * nothing more. Widening it here would be a behavioural change the reference does not make, and an
         * undocumented one, because the value carries nothing that says which century it belongs to. The
         * value asserted is the second record of
         * {@code src/test/resources/fixtures/pautdtl1-date-formats.bin}, whose originating date is
         * {@code 991231} and which was committed precisely because it sits on the far side of any pivot a
         * widening implementation would apply.</p>
         *
         * <p>Assumptions: the row publishes the six stored characters and the separators the reference
         * inserts are a rendering concern one layer out. What matters at this boundary is the YEAR WIDTH,
         * which is why the length is asserted alongside the value.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the two-digit year of the originating date is not expanded to four")
        void theTwoDigitYearOfTheOriginatingDateIsNotExpanded() {
            PendingAuthRowView row = projectOnly(projectionRow("00", new BigDecimal("250.00"),
                    new BigDecimal("250.00"), "TX0000000000001",
                    PendingAuthDetail.MATCH_STATUS_PENDING, "991231"));

            assertThat(row.authOrigDate()).isEqualTo("991231").hasSize(6);
            assertThat(row.authOrigDate())
                    .as("neither century is supplied, because the stored value names none")
                    .doesNotStartWith("19")
                    .doesNotStartWith("20");
        }

        /**
         * The two match statuses an insert can ORIGINATE are published unchanged.
         *
         * <p>Assumptions: {@code cbl/COPAUS0C.cbl} L552 moves {@code PA-MATCH-STATUS} into the row with no
         * transformation, so the projection's whole job here is to not alter it. Only two of the four
         * declared values are exercised through the constructor, and the reason is a domain invariant rather
         * than a gap in this case: {@code PendingAuthDetail.ORIGINATED_MATCH_STATUSES} narrows construction
         * to pending and declined because those are the only two the reference insert branches select, and
         * the constructor refuses the other two outright. The remaining two are covered by the case below,
         * which reaches them the way a loaded row does.</p>
         *
         * @param matchStatus the {@code String} one-character status under test, one of the two originated
         */
        @ParameterizedTest
        @ValueSource(strings = {"P", "D"})
        @DisplayName("both originated match statuses reach the caller unchanged")
        void everyOriginatedMatchStatusIsPublishedUnchanged(String matchStatus) {
            PendingAuthRowView row = projectOnly(projectionRow("00", new BigDecimal("250.00"),
                    new BigDecimal("250.00"), "TX0000000000001", matchStatus, "240715"));

            assertThat(row.matchStatus()).isEqualTo(matchStatus).hasSize(1);
            assertThat(PendingAuthDetail.ORIGINATED_MATCH_STATUSES).contains(matchStatus);
        }

        /**
         * The two match statuses only a later TRANSITION reaches are published unchanged as well.
         *
         * <p>Assumptions: {@code cpy/CIPAUDTY.cpy} L45 declares {@code PA-MATCH-STATUS PIC X(01)} and L46 to
         * L49 close its domain to four values, of which an insert originates two. Pending-expired is reached
         * by the purge sweep and matched-with-transaction by the posting match, and neither can be passed to
         * the constructor -- it refuses them by design, so that a row created in this process cannot claim an
         * outcome no insert path produces. Both must still PROJECT, because the browse reads rows that have
         * since transitioned, and a projection that mapped an unrecognised status onto a default or refused
         * it would lose exactly those two states from the screen.</p>
         *
         * <p>Alternatives Considered: widening the constructor's admitted set so these two could be built
         * directly, and reaching them through reflection into the field. Both were rejected. Widening would
         * dismantle the invariant the domain type exists to hold in order to make a test convenient, and a
         * reflective write would bind this case to a private field name. Stubbing the one accessor the
         * projection reads models what actually happens -- the provider materialises a loaded row through the
         * no-argument constructor and field assignment, bypassing the constructor's check entirely, which is
         * the mechanism the domain type's own documentation names. Trade-offs: a partial double is used where
         * every other case here uses a real object, which is accepted because it is confined to one accessor
         * and every other member the projection reads stays real.</p>
         *
         * @param matchStatus the {@code String} one-character status under test, one of the two transitioned
         */
        @ParameterizedTest
        @ValueSource(strings = {"E", "M"})
        @DisplayName("both transitioned match statuses reach the caller unchanged")
        void everyTransitionedMatchStatusIsPublishedUnchanged(String matchStatus) {
            PendingAuthDetail loaded = spy(projectionRow("00", new BigDecimal("250.00"),
                    new BigDecimal("250.00"), "TX0000000000001",
                    PendingAuthDetail.MATCH_STATUS_PENDING, "240715"));
            when(loaded.getMatchStatus()).thenReturn(matchStatus);

            PendingAuthRowView row = projectOnly(loaded);

            assertThat(row.matchStatus()).isEqualTo(matchStatus).hasSize(1);
            assertThat(PendingAuthDetail.ORIGINATED_MATCH_STATUSES)
                    .as("this status is reachable only by a transition, never by an insert")
                    .doesNotContain(matchStatus);
        }

        /**
         * The published enumeration is the segment's own four values, and it is wider than what is inserted.
         *
         * <p>Assumptions: the two sets are asserted TOGETHER because the relationship between them is the
         * contract, not either one alone. The published enumeration has to admit every state a row can ever
         * reach, since the browse renders rows that have transitioned; the origination set has to admit only
         * the two an insert selects. Asserting either in isolation would leave the division invisible, and it
         * is exactly the division a reader is likely to collapse -- either by refusing a loaded expired row
         * or by allowing one to be created.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the published match-status enumeration is wider than the originated set")
        void thePublishedMatchStatusDomainIsWiderThanTheOriginatedSet() {
            assertThat(PendingAuthRowView.MATCH_STATUSES)
                    .containsExactly(PendingAuthDetail.MATCH_STATUS_PENDING,
                            PendingAuthDetail.MATCH_STATUS_DECLINED,
                            PendingAuthDetail.MATCH_STATUS_PENDING_EXPIRED,
                            PendingAuthDetail.MATCH_STATUS_MATCHED_WITH_TRAN);
            assertThat(PendingAuthDetail.ORIGINATED_MATCH_STATUSES)
                    .containsExactly(PendingAuthDetail.MATCH_STATUS_PENDING,
                            PendingAuthDetail.MATCH_STATUS_DECLINED);
            assertThat(PendingAuthRowView.MATCH_STATUSES)
                    .as("the two states a transition adds are publishable but not insertable")
                    .containsAll(PendingAuthDetail.ORIGINATED_MATCH_STATUSES)
                    .hasSizeGreaterThan(PendingAuthDetail.ORIGINATED_MATCH_STATUSES.size());
        }

        /**
         * Row money is exact fixed point at two decimal places and never a binary floating-point value.
         *
         * <p>Assumptions: the stored field is {@code PA-APPROVED-AMT PIC S9(10)V99 COMP-3} at
         * {@code cpy/CIPAUDTY.cpy} L35, so two decimal places are declared rather than incidental, and AAP
         * Rule T3 carries that across as an exact decimal in the database, an exact decimal in this process
         * and a JSON STRING on the wire. The scale is asserted EXACTLY rather than by value comparison,
         * because a value comparison accepts a differently scaled number that renders with the wrong number
         * of decimal places. The serialised form itself belongs to the shared kernel's money module and is
         * asserted there; what this case establishes is that the value reaching that module is already
         * exact.</p>
         *
         * <p>Assumptions: the twelve-character screen mask {@code PAMT001I PIC X(12)} at
         * {@code cpy-bms/COPAU00.cpy} L192 is deliberately NOT the width asserted here, and it is named only
         * so it is not confused with the fourteen-character wire mask of the messaging path. A screen mask
         * is a presentation width; the amount published here carries no mask at all.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("row money is exact at two decimal places, not a floating-point value")
        void theRowAmountIsExactFixedPointAtScaleTwo() {
            PendingAuthRowView row = projectOnly(projectionRow("00", new BigDecimal("250.00"),
                    new BigDecimal("1234567890.99"), "TX0000000000001",
                    PendingAuthDetail.MATCH_STATUS_PENDING, "240715"));

            assertThat(row.amount().amount().scale()).isEqualTo(2);
            assertThat(row.amount().amount()).isEqualByComparingTo(new BigDecimal("1234567890.99"));
            assertThat(row.amount().toPlainString())
                    .as("ten integer digits and two decimals survive intact, as S9(10)V99 declares")
                    .isEqualTo("1234567890.99");
        }

        /**
         * A short final page carries only the rows it found, never a padded-out remainder.
         *
         * <p>Assumptions: the reference screen ALWAYS has five row positions and distinguishes a filled one
         * from an empty one by an attribute byte. {@code POPULATE-AUTH-LIST} moves the unprotected attribute
         * into the row's selection field at L554, making that row selectable, whereas
         * {@code INITIALIZE-AUTH-DATA} moves the protected attribute at L614 inside the clearing loop
         * bounded at L611 and blanks the row's fields at L615 to L620. So an operator can select a row that
         * carries data and cannot select one that does not. The migrated contract has no fixed row count to
         * blank, and the equivalent of the protected empty slot is the slot's ABSENCE: a page of three rows
         * publishes three items. Asserting the count alone would be weaker than it looks, so each published
         * row is additionally asserted to carry the members a selectable row must have.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a short final page publishes only its filled rows, with no empty selectable slots")
        void aShortFinalPageCarriesNoEmptyRowSlots() {
            when(PendingAuthSummaryServiceTest.this.details
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(eq(ACCOUNT_ID),
                            any(Limit.class)))
                    .thenReturn(rowsDescending(3));

            PendingAuthListView view =
                    PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, null, null, SUBJECT);

            assertThat(view.page().items()).hasSize(3);
            assertThat(view.page().hasNext()).isFalse();
            assertThat(view.page().items())
                    .as("every published row is a data-bearing, selectable row")
                    .allSatisfy(row -> {
                        assertThat(row.key()).isNotBlank();
                        assertThat(row.transactionId()).isNotBlank();
                        assertThat(row.matchStatus()).isIn(PendingAuthRowView.MATCH_STATUSES);
                        assertThat(row.amount()).isNotNull();
                    });
        }
    }

    /**
     * The three system-error sites the reference program distinguishes, and the two outcomes it does not.
     *
     * <p>Purpose. This group holds the migration's register for the three user-visible failure strings
     * {@code cbl/COPAUS0C.cbl} declares, and asserts the one behavioural distinction that hangs on them: an
     * absent row is a state to be rendered, whereas a failed read is an error to be reported. Collapsing the
     * two is the failure this group exists to prevent, because a failed read rendered as an empty page tells
     * an operator the account has no pending authorizations when in truth nothing was read at all.</p>
     *
     * <p>Assumptions: the status shape behind all three is {@code DIBSTAT}, evaluated at L466, L499 and L979,
     * because this is a CICS program issuing {@code EXEC DLI}. The {@code CALL 'CBLTDLI'} programs of the same
     * extension evaluate {@code PAUT-PCB-STATUS} instead and are asserted elsewhere. The two shapes are never
     * unified, so nothing in this group asserts a status VALUE -- only which failure site a string belongs to
     * and whether the outcome is reported or rendered.</p>
     */
    @Nested
    @DisplayName("the three system-error sites")
    class SystemErrorParity {

        /**
         * The literal the browse read reports with, from {@code cbl/COPAUS0C.cbl} L477.
         *
         * <p>Assumptions: the single leading space is part of the value, not an accident of the
         * continuation, and it is preserved because AAP Rule T8 carries user-visible strings across
         * character for character.</p>
         */
        private static final String READING_DETAILS_PREFIX =
                " System error while reading AUTH Details: Code:";

        /**
         * The literal the browse RE-SEEK reports with, from {@code cbl/COPAUS0C.cbl} L510.
         *
         * <p>Assumptions: the abbreviation is {@code repos.} in the source and stays abbreviated. Expanding
         * it to a whole word would read as a tidy-up and would silently change a string an operator matches
         * on.</p>
         */
        private static final String REPOSITIONING_DETAILS_PREFIX =
                " System error while repos. AUTH Details: Code:";

        /**
         * The literal the keyed summary read reports with, from {@code cbl/COPAUS0C.cbl} L989.
         *
         * <p>Assumptions: this one says {@code Summary} where the other two say {@code Details}, which is
         * what makes the three separable in a log at all.</p>
         */
        private static final String READING_SUMMARY_PREFIX =
                " System error while reading AUTH Summary: Code:";

        /**
         * All three prefixes are carried verbatim and stay three distinct strings.
         *
         * <p>Assumptions: the reference program declares THREE strings for three failure sites and does not
         * share one between them, so merging them would lose the ability to tell which read failed --
         * precisely the thing a first responder needs. Each is asserted for the single leading space, for the
         * shared trailing fragment the status is appended to, and for mutual distinctness, because those are
         * the three ways a well-meaning edit would damage them: trimming, normalising the ending, or folding
         * them into one constant.</p>
         *
         * <p>Assumptions: no target constant carries these strings, and that is deliberate rather than an
         * omission this case papers over. A failed read surfaces here as the framework's data-access
         * exception, which the shared handler renders, so there is nothing in the migrated code for a string
         * comparison to point at. This case is therefore the register itself: it pins what the baseline says
         * so a later reader can see that the target's silence about these three is a decision and not a
         * loss.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("all three system-error prefixes are verbatim, distinct and singly space-led")
        void theThreeSystemErrorPrefixesAreVerbatimAndDistinct() {
            List<String> prefixes = List.of(READING_DETAILS_PREFIX, REPOSITIONING_DETAILS_PREFIX,
                    READING_SUMMARY_PREFIX);

            assertThat(prefixes).doesNotHaveDuplicates().hasSize(3);
            assertThat(prefixes).allSatisfy(prefix -> {
                assertThat(prefix).startsWith(" ").doesNotStartWith("  ");
                assertThat(prefix)
                        .as("the status is appended to this exact ending")
                        .endsWith(": Code:");
            });

            assertThat(REPOSITIONING_DETAILS_PREFIX)
                    .as("the abbreviation is not expanded")
                    .contains("repos.")
                    .doesNotContain("reposition");
            assertThat(READING_DETAILS_PREFIX).contains("Details");
            assertThat(READING_SUMMARY_PREFIX).contains("Summary").doesNotContain("Details");
        }

        /**
         * An unreadable summary is REPORTED, where an absent summary is rendered.
         *
         * <p>Assumptions: the reference evaluation at L980 to L996 has exactly two defined arms and a
         * residual one, and the shape is the argument. A found status sets the found indicator at L981, a
         * segment-not-found status sets the not-found indicator at L983, and only the residual arm builds the
         * L989 message and repaints. There is no end-of-database arm and its absence is CORRECT rather than an
         * oversight: the retrieval is qualified on the root's own unique key, so it either finds its row or
         * does not, and only a browse can run off the end of a database. That two-plus-residual shape is what
         * maps a keyed read onto an empty optional for absence and a propagated failure for everything else,
         * and it is the structural contrast with the three-arm browse evaluations at L467 to L478 and L500 to
         * L510, which DO carry an end-of-database arm because they are browses.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("an unreadable summary is reported where an absent summary is rendered")
        void anUnreadableSummaryIsReportedWhereAnAbsentSummaryIsRendered() {
            // WHY : Assumptions: the two outcomes are asserted in ONE case so the contrast cannot be broken
            //       by editing one of them alone. Split across two cases, a change that made a failed read
            //       render as a zeroed page would leave the absence case passing and read as intentional.
            when(PendingAuthSummaryServiceTest.this.summaries.findByAccountId(ACCOUNT_ID))
                    .thenReturn(Optional.empty());
            assertThatCode(() -> PendingAuthSummaryServiceTest.this.service
                    .list(ACCOUNT_ID, null, null, SUBJECT))
                    .as("absence is a state the keyed read returns, not a condition it raises")
                    .doesNotThrowAnyException();

            when(PendingAuthSummaryServiceTest.this.summaries.findByAccountId(ACCOUNT_ID))
                    .thenThrow(new DataAccessResourceFailureException("the summary read did not complete"));
            assertThatExceptionOfType(DataAccessResourceFailureException.class)
                    .as("a read that did not complete is reported, not rendered as a zeroed summary")
                    .isThrownBy(() -> PendingAuthSummaryServiceTest.this.service
                            .list(ACCOUNT_ID, null, null, SUBJECT));
        }

        /**
         * An unreadable authorization browse is REPORTED, where an empty browse is rendered.
         *
         * <p>Assumptions: the browse evaluations at L467 to L478 and L500 to L510 route segment-not-found and
         * end-of-database to {@code SET AUTHS-EOF TO TRUE} at L472 and L505, which ends the page normally, and
         * route every other status to the L477 and L510 messages. Exhaustion and failure are therefore two
         * different outcomes in the reference and stay two here: an empty list is an empty page, and a failed
         * read propagates. An implementation that caught the failure and returned an empty page would satisfy
         * every other browse case in this class.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("an unreadable browse is reported where an exhausted browse is rendered")
        void anUnreadableBrowseIsReportedWhereAnExhaustedBrowseIsRendered() {
            when(PendingAuthSummaryServiceTest.this.details
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(eq(ACCOUNT_ID),
                            any(Limit.class)))
                    .thenReturn(List.of());
            assertThatCode(() -> PendingAuthSummaryServiceTest.this.service
                    .list(ACCOUNT_ID, null, null, SUBJECT))
                    .as("exhaustion ends the page normally, as AUTHS-EOF does at L472 and L505")
                    .doesNotThrowAnyException();

            Mockito.reset(PendingAuthSummaryServiceTest.this.details);
            when(PendingAuthSummaryServiceTest.this.details
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(eq(ACCOUNT_ID),
                            any(Limit.class)))
                    .thenThrow(new DataAccessResourceFailureException("the browse did not complete"));
            assertThatExceptionOfType(DataAccessResourceFailureException.class)
                    .as("a browse that did not complete is reported, not rendered as an empty page")
                    .isThrownBy(() -> PendingAuthSummaryServiceTest.this.service
                            .list(ACCOUNT_ID, null, null, SUBJECT));
        }
    }

    /**
     * The read boundary this operation owns, and the server-side state it deliberately does not keep.
     *
     * <p>Purpose. This group asserts two absences and one presence. The presence is a read-only transaction
     * around the whole operation. The absences are a server-held browse position and a row lock, both of
     * which the reference program has and neither of which is carried across.</p>
     *
     * <p>Assumptions: the reference program keeps its browse history SERVER-SIDE, in the twenty-slot array
     * {@code CDEMO-CPVS-PAUKEY-PREV-PG PIC X(08) OCCURS 20 TIMES} at L120, indexed by the page counter at
     * L122 and bounded by the next-page indicator at L123 to L125, with the current position at L121. That
     * whole mechanism moves to the CLIENT, which already holds the cursor of the page it is displaying, so
     * none of those four fields has a server-side equivalent. The consequence asserted below is
     * statelessness: the same request twice is the same answer twice, whatever happened in between.</p>
     */
    @Nested
    @DisplayName("the stateless read boundary")
    class StatelessReadBoundary {

        /**
         * The same request made twice returns the same page, because nothing is retained between them.
         *
         * <p>Assumptions: this is the property that lets the operation run behind a load balancer with no
         * affinity, and it is asserted by REPEATING a cursor rather than by inspecting the instance for
         * fields. A field inspection would pass over a service that kept its position somewhere reachable but
         * unexamined; repeating the request exercises whatever state exists. The reference program could not
         * satisfy this: its second turn would advance through the retained array at L120 and the page counter
         * at L122, so the same input would produce a different page.</p>
         *
         * <p>Alternatives Considered: asserting the absence of instance state directly, by reflecting over
         * the service's declared fields and requiring them all to be collaborators. Rejected because it
         * constrains the implementation's shape rather than its behaviour -- a legitimate future field such as
         * a metrics counter would fail it while breaking nothing a caller can observe.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the same cursor requested twice returns the same page")
        void theSameRequestTwiceReturnsTheSamePage() {
            List<PendingAuthDetail> corpus = multiDayCorpus();
            stubCorpus(corpus);
            PendingAuthListView opening =
                    PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, null, null, SUBJECT);
            String cursor = opening.page().lastKey();

            PendingAuthListView firstAnswer = PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID,
                    cursor, PendingAuthSummaryService.DIRECTION_NEXT, SUBJECT);
            PendingAuthListView secondAnswer = PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID,
                    cursor, PendingAuthSummaryService.DIRECTION_NEXT, SUBJECT);

            assertThat(renderedIds(secondAnswer)).isEqualTo(renderedIds(firstAnswer));
            assertThat(secondAnswer.page().hasNext()).isEqualTo(firstAnswer.page().hasNext());
            assertThat(secondAnswer.screenMessage()).isEqualTo(firstAnswer.screenMessage());

            // WHY : Assumptions: the opening page is re-requested LAST, after two paging moves, because a
            //       service that advanced a retained position would answer this third request with the page
            //       it had reached rather than with the opening page. Asserting it before the moves would
            //       leave that failure mode unexercised.
            PendingAuthListView reopened =
                    PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, null, null, SUBJECT);
            assertThat(renderedIds(reopened)).isEqualTo(renderedIds(opening));
        }

        /**
         * The operation declares a read-only transaction, which is the migrated form of the screen's commit.
         *
         * <p>Assumptions: the reference program commits on a path that only READS, and the reason is that
         * scheduling a program specification block was itself a resource to be released: L684 to L688 test
         * whether one is scheduled, clear the flag and issue {@code EXEC CICS SYNCPOINT} at L686 to L687 as
         * the screen is sent. The equivalent bracket here is the service-method boundary, and it is declared
         * read-only because the two reads inside it must see one consistent state without the boundary
         * licensing a write.</p>
         *
         * <p>Assumptions: the annotation is asserted by reflection rather than by observing a rollback,
         * because a unit test with repository doubles has no transaction to roll back -- the property is the
         * DECLARATION, and its runtime effect belongs to the module's integration tier.</p>
         *
         * @throws NoSuchMethodException if the operation's signature changes without this assertion following
         *     it, which should fail the build rather than silently stop checking the boundary
         */
        @Test
        @DisplayName("the list operation declares a read-only transaction boundary")
        void theListOperationDeclaresAReadOnlyTransactionBoundary() throws NoSuchMethodException {
            Transactional boundary = PendingAuthSummaryService.class
                    .getMethod("list", Long.class, String.class, String.class, String.class)
                    .getAnnotation(Transactional.class);

            assertThat(boundary).isNotNull();
            assertThat(boundary.readOnly())
                    .as("a screen that only reads must not carry a writable boundary")
                    .isTrue();
        }

        /**
         * No page path takes a locked read, on either direction or on the probe.
         *
         * <p>Assumptions: the claim is about THIS operation and not about the context, which does take a lock
         * elsewhere. {@code PendingAuthDetailRepository} declares a locked read for the decision path that
         * accumulates onto a row, and the package charter places that declaration on the repository boundary.
         * A browse must not use it: a lock held across a paging read would make one operator's screen refresh
         * block another's, and the reference browse holds nothing between turns -- it re-seeks from a saved
         * key precisely because its position does not survive.</p>
         *
         * <p>Assumptions: all three reads are driven before the assertion -- the opening walk, a forward move
         * and a backward move -- so the verification covers every path a page can be read by rather than the
         * one the opening request happens to take.</p>
         *
         * <p>This test takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("no page path takes a locked read")
        void noPagePathTakesALockedRead() {
            stubCorpus(multiDayCorpus());
            PendingAuthListView opening =
                    PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, null, null, SUBJECT);
            PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, opening.page().lastKey(),
                    PendingAuthSummaryService.DIRECTION_NEXT, SUBJECT);
            PendingAuthSummaryServiceTest.this.service.list(ACCOUNT_ID, opening.page().lastKey(),
                    PendingAuthSummaryService.DIRECTION_PREVIOUS, SUBJECT);

            verify(PendingAuthSummaryServiceTest.this.details, never())
                    .findWithLockById(any(PendingAuthDetailKey.class));
        }
    }
}
