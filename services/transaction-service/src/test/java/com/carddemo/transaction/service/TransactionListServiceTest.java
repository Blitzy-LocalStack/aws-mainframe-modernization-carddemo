package com.carddemo.transaction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import com.carddemo.transaction.domain.Transaction;
import com.carddemo.transaction.dto.TransactionListItemResponse;
import com.carddemo.transaction.dto.TransactionListRequest;
import com.carddemo.transaction.mapper.TransactionMapper;
import com.carddemo.transaction.repository.TransactionRepository;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * Drives {@link TransactionListService} itself, so that the paging decisions it owns are gated by an
 * executable assertion rather than by inspection.
 *
 * <p>Assumptions: six behaviours of this class are load-bearing and none of them is observable from
 * the repository tests beside it, because those tests call the finders directly and never the service
 * that chooses between them. The six are: which finder an opening request selects; the separation of
 * the probe row from the rows a caller receives; the trailing boundary being sealed from the last row
 * returned rather than from the probe; the reversal a backward scan needs before display; the empty
 * page a positioning identifier that matches nothing produces; and the forward-availability answer,
 * which differs by direction. Each case below names the one it exists for.
 *
 * <p>Assumptions: the boundary-message selection is asserted here as well, even though it produces prose
 * rather than a page. It reads the same request and the very page these cases build, it is published on
 * this class and on no other, and its five strings are carried across verbatim from five different
 * reference paragraphs -- so the assertion has to sit beside the paging cases that construct its inputs.
 *
 * <p>Assumptions: the collaborators are real wherever the assertion depends on their behaviour. The
 * mapper is the production {@link TransactionMapper}, because the page size, the display ordering and
 * the envelope construction are its decisions and substituting them would leave this class asserting a
 * page shape nothing produces. The sealer is a real {@link CursorToken} over synthetic key material,
 * because the boundary tokens are opaque and the only way to assert which row a boundary names is to
 * open the token the service sealed.
 *
 * <p>Alternatives Considered: a mocking framework for the repository, which is the conventional choice
 * and is what the authorization context uses for its own listener test. Rejected here because the
 * inline mock maker attaches an instrumentation agent to the running virtual machine at first use, and
 * the machine reports that attachment as a warning on standard error for the whole forked test run.
 * This module's build currently emits no warning of any kind, the shared build treats a new warning as
 * a regression, and suppressing it would mean adding a launch argument to the shared plugin
 * configuration for the sake of one test class. A scripted stub over the repository interface answers
 * the same three questions -- which finder was called, with which arguments, and what it returned --
 * without that cost.
 *
 * <p>Trade-offs: the stub is reached through a reflective proxy rather than through a class that
 * implements the interface member by member. The interface inherits about thirty-five operations from
 * the Spring Data hierarchy while this class exercises four, so a written implementation would be
 * mostly bodies that raise, each carrying documentation the gate requires; the proxy keeps the stub to
 * one documented dispatch. What is given up is compile-time checking that a scripted name still exists
 * on the interface, and that is bought back by {@link #theScriptedFinderNamesStillExistOnTheRepository}
 * below, which resolves every scripted name reflectively and fails if one was renamed.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class TransactionListServiceTest {

    /** The finder an opening request with no positioning identifier selects. */
    private static final String FINDER_OPENING = "findAllByOrderByTranIdAsc";

    /** The finder a forward step selects, and the second read of a positioned opening request. */
    private static final String FINDER_FORWARD = "findByTranIdGreaterThanOrderByTranIdAsc";

    /** The finder a backward step selects. */
    private static final String FINDER_BACKWARD = "findByTranIdLessThanOrderByTranIdDesc";

    /** The keyed read a positioned opening request performs before its forward read. */
    private static final String FINDER_BY_ID = "findById";

    /**
     * Synthetic key material for the sealer, at the minimum width it accepts.
     *
     * <p>Assumptions: this is test-only material and is not a credential of any environment. It is a
     * literal rather than a generated value so that a sealed token is reproducible within one run, and
     * it is obviously synthetic so that no reader mistakes it for something to protect.
     */
    private static final byte[] CURSOR_KEY =
            "carddemo-transaction-list-test-key-material".getBytes(StandardCharsets.US_ASCII);

    /** The originating stamp every row below carries, injected rather than read from a clock. */
    private static final LocalDateTime ORIGIN_TS = LocalDateTime.of(2022, 6, 10, 19, 27, 53);

    /** The amount every row below carries, at the scale the money contract requires. */
    private static final BigDecimal AMOUNT = new BigDecimal("504.77");

    /** The card number every row below carries, a synthetic sixteen-digit value. */
    private static final String CARD_NUM = "4000123456789010";

    /** The production mapper, which owns the page size, the display order and the envelope. */
    private TransactionMapper mapper;

    /** The production sealer the service mints and opens boundary tokens with. */
    private CursorToken cursorToken;

    /** The script the stub answers from, keyed by finder name. */
    private Map<String, List<Transaction>> script;

    /** The finder names the stub was asked for, in call order. */
    private List<String> selectedFinders;

    /** The arguments the stub was called with, in call order, rendered for assertion. */
    private List<String> recordedArguments;

    /** The stub the service reads through. */
    private TransactionRepository repository;

    /** Builds a fresh mapper, sealer, script and stub before each case. */
    @BeforeEach
    void buildCollaborators() {
        this.mapper = new TransactionMapper();
        this.cursorToken = new CursorToken(CURSOR_KEY, Duration.ofMinutes(15));
        this.script = new HashMap<>();
        this.selectedFinders = new ArrayList<>();
        this.recordedArguments = new ArrayList<>();
        this.repository = scriptedRepository();
    }

    /**
     * Confirms an opening request with no positioning identifier selects the unfiltered opening finder.
     *
     * <p>Assumptions: this is the finder-selection behaviour, and it is asserted on the call record
     * rather than on the returned rows, because two finders can return the same rows for one fixture
     * and only the call record distinguishes which was consulted. Both other finders are asserted
     * absent, so a request that consulted the forward finder with a sentinel key would fail here.
     */
    @Test
    @DisplayName("an opening request with no identifier reads through the unfiltered opening finder")
    void anOpeningRequestReadsThroughTheUnfilteredOpeningFinder() {
        this.script.put(FINDER_OPENING, rows(1, 3));

        PageResponse<TransactionListItemResponse> page = this.list(request(null, null, null));

        assertThat(this.selectedFinders).containsExactly(FINDER_OPENING);
        assertThat(this.recordedArguments)
                .containsExactly(FINDER_OPENING + "(limit=" + (TransactionMapper.PAGE_SIZE + 1) + ")");
        assertThat(page.items()).extracting(TransactionListItemResponse::transactionId)
                .containsExactly(tranId(1), tranId(2), tranId(3));
    }

    /**
     * Confirms a scan returning one row beyond the page returns the page and reports a further page.
     *
     * <p>Assumptions: this is the probe behaviour, and the eleventh row is the whole of it. The
     * boundary is asserted against the tenth row and against the eleventh separately, because a
     * boundary sealed from the probe would still be a well-formed token naming a real row and every
     * other assertion in this class would still pass.
     */
    @Test
    @DisplayName("eleven rows scanned yield ten returned, a further page and a boundary at the tenth")
    void elevenRowsScannedYieldTenReturnedAndABoundaryAtTheTenth() {
        this.script.put(FINDER_OPENING, rows(1, TransactionMapper.PAGE_SIZE + 1));

        PageResponse<TransactionListItemResponse> page = this.list(request(null, null, null));

        assertThat(page.items()).hasSize(TransactionMapper.PAGE_SIZE);
        assertThat(page.items()).extracting(TransactionListItemResponse::transactionId)
                .doesNotContain(tranId(TransactionMapper.PAGE_SIZE + 1));
        assertThat(page.hasNext()).isTrue();
        assertThat(this.openBoundary(page.firstKey())).isEqualTo(tranId(1));
        assertThat(this.openBoundary(page.lastKey()))
                .isEqualTo(tranId(TransactionMapper.PAGE_SIZE))
                .isNotEqualTo(tranId(TransactionMapper.PAGE_SIZE + 1));
    }

    /**
     * Confirms a scan returning exactly one page reports no further page.
     *
     * <p>Assumptions: this is the exhaustion behaviour and it is the discriminator against a size
     * comparison written with the wrong sense. A page of exactly ten is the one input on which
     * {@code greater than} and {@code greater than or equal} disagree, so a case of nine or of eleven
     * would leave that mistake undetected.
     */
    @Test
    @DisplayName("exactly one page of rows reports no further page and keeps every row")
    void exactlyOnePageOfRowsReportsNoFurtherPage() {
        this.script.put(FINDER_OPENING, rows(1, TransactionMapper.PAGE_SIZE));

        PageResponse<TransactionListItemResponse> page = this.list(request(null, null, null));

        assertThat(page.items()).hasSize(TransactionMapper.PAGE_SIZE);
        assertThat(page.hasNext()).isFalse();
        assertThat(this.openBoundary(page.lastKey())).isEqualTo(tranId(TransactionMapper.PAGE_SIZE));
    }

    /**
     * Confirms a single row is returned as a page of one naming itself at both boundaries.
     *
     * <p>Assumptions: the two boundaries coincide on a page of one, and the envelope requires both to
     * be present whenever rows are. Asserting they are equal rather than merely non-null is what
     * distinguishes a correct single-row page from one whose leading boundary was left unsealed and
     * filled in from the trailing one by accident.
     */
    @Test
    @DisplayName("a single row is a page of one naming itself at both boundaries")
    void aSingleRowIsAPageOfOneNamingItselfAtBothBoundaries() {
        this.script.put(FINDER_OPENING, rows(1, 1));

        PageResponse<TransactionListItemResponse> page = this.list(request(null, null, null));

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext()).isFalse();
        assertThat(this.openBoundary(page.firstKey())).isEqualTo(tranId(1));
        assertThat(this.openBoundary(page.lastKey())).isEqualTo(tranId(1));
    }

    /**
     * Confirms a scan returning nothing yields an empty page with neither boundary nor further page.
     *
     * <p>Assumptions: the envelope refuses a further page reported without a trailing position, so an
     * empty page that claimed one would raise rather than return. The assertion is on all three
     * components together because the empty state is the one state in which every component is absent
     * and a partially-populated empty page is the failure worth catching.
     */
    @Test
    @DisplayName("a scan returning nothing yields an empty page with no boundary and no further page")
    void aScanReturningNothingYieldsAnEmptyPage() {
        this.script.put(FINDER_OPENING, List.of());

        PageResponse<TransactionListItemResponse> page = this.list(request(null, null, null));

        assertThat(page.items()).isEmpty();
        assertThat(page.firstKey()).isNull();
        assertThat(page.lastKey()).isNull();
        assertThat(page.hasNext()).isFalse();
    }

    /**
     * Confirms a backward request reads the descending finder and returns the page in display order.
     *
     * <p>Assumptions: this is the reversal behaviour. The stub answers in the descending order the
     * query declares, so the returned page being ascending is the reversal happening; a page that came
     * back descending would be the reversal missing. The probe row of a backward scan is the lowest
     * identifier read, so it is asserted absent from the page as well, which pins that the surplus row
     * is trimmed from the far end in this direction too.
     */
    @Test
    @DisplayName("a backward request reads descending and returns the page in ascending display order")
    void aBackwardRequestReadsDescendingAndReturnsAscending() {
        this.script.put(FINDER_BACKWARD, descending(rows(1, TransactionMapper.PAGE_SIZE + 1)));
        String cursor = this.seal(tranId(TransactionMapper.PAGE_SIZE + 2));

        PageResponse<TransactionListItemResponse> page =
                this.list(request(null, cursor, TransactionListRequest.Direction.PREVIOUS));

        assertThat(this.selectedFinders).containsExactly(FINDER_BACKWARD);
        assertThat(this.recordedArguments).containsExactly(FINDER_BACKWARD + "(key="
                + tranId(TransactionMapper.PAGE_SIZE + 2)
                + ", limit=" + (TransactionMapper.PAGE_SIZE + 1) + ")");
        assertThat(page.items()).hasSize(TransactionMapper.PAGE_SIZE);
        assertThat(page.items()).extracting(TransactionListItemResponse::transactionId)
                .isSortedAccordingTo(String::compareTo)
                .doesNotContain(tranId(1));
        assertThat(this.openBoundary(page.firstKey())).isEqualTo(tranId(2));
        assertThat(this.openBoundary(page.lastKey()))
                .isEqualTo(tranId(TransactionMapper.PAGE_SIZE + 1));

        // WHY : Assumptions: a backward page reports a further page unconditionally, because the caller
        //       arrived from the page that lies ahead of this one and a forward step from here
        //       necessarily has somewhere to go. It is asserted here rather than left implicit because
        //       the value does NOT come from the probe row in this direction, so a reader comparing it
        //       with the forward case would otherwise expect it to.
        assertThat(page.hasNext()).isTrue();
    }

    /**
     * Confirms a forward step reads strictly past the cursor and never consults the opening finder.
     *
     * <p>Assumptions: the recorded argument is the raw identifier the cursor carried, not the sealed
     * token, so this case also shows the service opens the token before querying. A query issued with
     * the token itself would return nothing from any real engine and would look like an empty page.
     */
    @Test
    @DisplayName("a forward step reads strictly past the opened cursor key")
    void aForwardStepReadsStrictlyPastTheOpenedCursorKey() {
        this.script.put(FINDER_FORWARD, rows(5, 7));
        String cursor = this.seal(tranId(4));

        PageResponse<TransactionListItemResponse> page =
                this.list(request(null, cursor, TransactionListRequest.Direction.NEXT));

        assertThat(this.selectedFinders).containsExactly(FINDER_FORWARD);
        assertThat(this.recordedArguments).containsExactly(FINDER_FORWARD + "(key=" + tranId(4)
                + ", limit=" + (TransactionMapper.PAGE_SIZE + 1) + ")");
        assertThat(page.items()).extracting(TransactionListItemResponse::transactionId)
                .containsExactly(tranId(5), tranId(6), tranId(7));
        assertThat(page.hasNext()).isFalse();
    }

    /**
     * Confirms a positioning identifier is honoured inclusively, by a keyed read then a forward read.
     *
     * <p>Assumptions: the second read is bounded to the page size and not to the page size plus one,
     * because the positioned row already occupies the first slot of the page. Asserting the recorded
     * limit is how that is pinned: a second read bounded to eleven would read twelve rows in total and
     * report a further page one row early, and the page returned would look correct.
     */
    @Test
    @DisplayName("a positioning identifier is read inclusively, and the following read is bounded to ten")
    void aPositioningIdentifierIsReadInclusively() {
        this.script.put(FINDER_BY_ID, rows(4, 4));
        this.script.put(FINDER_FORWARD, rows(5, 6));

        PageResponse<TransactionListItemResponse> page = this.list(request(tranId(4), null, null));

        assertThat(this.selectedFinders).containsExactly(FINDER_BY_ID, FINDER_FORWARD);
        assertThat(this.recordedArguments).containsExactly(
                FINDER_BY_ID + "(key=" + tranId(4) + ")",
                FINDER_FORWARD + "(key=" + tranId(4) + ", limit=" + TransactionMapper.PAGE_SIZE + ")");
        assertThat(page.items()).extracting(TransactionListItemResponse::transactionId)
                .containsExactly(tranId(4), tranId(5), tranId(6));
    }

    /**
     * Confirms a positioning identifier matching no row yields an empty page and no forward read.
     *
     * <p>Assumptions: the absence of the forward call is the assertion that matters. A handler that
     * fell through to an unpositioned scan would return the rows at the start of the key space, which
     * is a page the caller did not ask for and which no assertion on the rows alone would distinguish
     * from a correct answer when the table happens to start at the requested identifier.
     */
    @Test
    @DisplayName("a positioning identifier matching no row yields an empty page and no forward read")
    void anUnmatchedPositioningIdentifierYieldsAnEmptyPage() {
        this.script.put(FINDER_BY_ID, List.of());

        PageResponse<TransactionListItemResponse> page = this.list(request(tranId(9), null, null));

        assertThat(this.selectedFinders).containsExactly(FINDER_BY_ID);
        assertThat(page.items()).isEmpty();
        assertThat(page.lastKey()).isNull();
        assertThat(page.hasNext()).isFalse();
    }

    /**
     * Confirms every finder name this class scripts still exists on the repository interface.
     *
     * <p>Assumptions: the proxy dispatches on a name, so a finder renamed on the interface would leave
     * the script keyed on a name nothing calls and the affected cases would fail with an unhelpful
     * absent-script diagnostic instead of naming the rename. Resolving each name reflectively here
     * restores the compile-time guarantee the proxy gives up, and reports the rename directly.
     *
     * @throws NoSuchMethodException if a scripted finder was renamed or its parameters changed, which
     *     is the intended failure rather than a condition to handle
     */
    @Test
    @DisplayName("every scripted finder name still exists on the repository interface")
    void theScriptedFinderNamesStillExistOnTheRepository() throws NoSuchMethodException {
        assertThat(TransactionRepository.class.getMethod(FINDER_OPENING, Limit.class)).isNotNull();
        assertThat(TransactionRepository.class.getMethod(FINDER_FORWARD, String.class, Limit.class))
                .isNotNull();
        assertThat(TransactionRepository.class.getMethod(FINDER_BACKWARD, String.class, Limit.class))
                .isNotNull();
    }

    /**
     * Confirms each of the five reference boundary conditions selects its own verbatim message.
     *
     * <p>Assumptions: all five are asserted in one case because the property under test is that no two
     * conditions share a string. Five conditions mapping onto five distinct strings is the claim, and it
     * can only be checked by putting them beside one another; a page that met no boundary is asserted to
     * carry no message at all, which is the sixth outcome and the one a defect would most likely collapse
     * the others onto.</p>
     *
     * <p>Refactoring Rationale: the three "top" strings differ from one another only by a word -- one says
     * "already at the top", one says "at the top" and one says "have reached the top" -- and the reference
     * emits them from three different paragraphs. Nothing else in this module compares them, so without
     * this case a selector that returned any one of the three for all three conditions would pass every
     * other assertion here while changing what a user reads.</p>
     */
    @Test
    @DisplayName("the five boundary conditions select five distinct verbatim messages")
    void theFiveBoundaryConditionsSelectTheirOwnMessages() {
        TransactionListService service = new TransactionListService(this.repository, this.mapper);
        PageResponse<TransactionListItemResponse> empty =
                new PageResponse<>(List.of(), null, null, false);
        String cursor = this.seal(tranId(1));
        this.script.put(FINDER_OPENING, rows(1, TransactionMapper.PAGE_SIZE));
        PageResponse<TransactionListItemResponse> populated = this.list(request(null, null, null));

        assertThat(service.boundaryMessage(
                request(null, null, TransactionListRequest.Direction.PREVIOUS), empty))
                .isEqualTo(TransactionListService.MESSAGE_ALREADY_AT_TOP);
        assertThat(service.boundaryMessage(
                request(null, cursor, TransactionListRequest.Direction.PREVIOUS), empty))
                .isEqualTo(TransactionListService.MESSAGE_REACHED_TOP);
        assertThat(service.boundaryMessage(
                request(null, cursor, TransactionListRequest.Direction.NEXT), empty))
                .isEqualTo(TransactionListService.MESSAGE_ALREADY_AT_BOTTOM);
        assertThat(service.boundaryMessage(request(tranId(9), null, null), empty))
                .isEqualTo(TransactionListService.MESSAGE_AT_TOP);
        assertThat(service.boundaryMessage(request(null, null, null), empty))
                .isEqualTo(TransactionListService.MESSAGE_REACHED_BOTTOM);
        assertThat(service.boundaryMessage(request(null, null, null), populated))
                .isEqualTo(TransactionListService.NO_MESSAGE);

        assertThat(List.of(TransactionListService.MESSAGE_ALREADY_AT_TOP,
                        TransactionListService.MESSAGE_REACHED_TOP,
                        TransactionListService.MESSAGE_ALREADY_AT_BOTTOM,
                        TransactionListService.MESSAGE_AT_TOP,
                        TransactionListService.MESSAGE_REACHED_BOTTOM))
                .as("no two conditions may share a string")
                .doesNotHaveDuplicates();
    }

    /**
     * Confirms both arguments of the boundary-message selector are required.
     *
     * <p>Assumptions: the selector reads the request AND the page it produced, so neither is optional and
     * neither has a defensible default. Returning no message for a null page would report "no boundary
     * met" for a call that never produced a page, which is the one answer a caller cannot distinguish
     * from a real one.</p>
     */
    @Test
    @DisplayName("the boundary-message selector requires both of its arguments")
    void theMessageSelectorRequiresBothArguments() {
        TransactionListService service = new TransactionListService(this.repository, this.mapper);
        PageResponse<TransactionListItemResponse> empty =
                new PageResponse<>(List.of(), null, null, false);

        assertThatThrownBy(() -> service.boundaryMessage(null, empty))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.boundaryMessage(request(null, null, null), null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * The authenticated principal every case in this class produces its pages for.
     *
     * <p>Assumptions: one subject throughout, because the properties asserted here are the page
     * arithmetic and the boundary identifiers, and both are independent of who asked. The cursor
     * binding names the subject, so sealing, opening and listing all have to agree on it or every
     * boundary assertion would fail for a reason that has nothing to do with paging. That the binding
     * refuses a token issued to another subject is a separate property, asserted by
     * {@code TransactionListServiceCursorBindingTest}.</p>
     */
    private static final String SUBJECT = "keyset-paging-subject";

    /**
     * Runs one request through a freshly built service over the current script.
     *
     * @param request the list request to answer; must not be {@code null}
     * @return the page envelope the service assembled, never {@code null}
     */
    private PageResponse<TransactionListItemResponse> list(TransactionListRequest request) {
        return new TransactionListService(this.repository, this.mapper)
                .listTransactions(request, this.cursorToken, SUBJECT);
    }

    /**
     * Seals a raw identifier into a boundary token bound as the service binds them.
     *
     * @param rawKey the raw identifier to seal; must not be {@code null}
     * @return the sealed token, never {@code null}
     */
    private String seal(String rawKey) {
        return this.cursorToken.seal(binding(), rawKey);
    }

    /**
     * Opens a boundary token the service sealed, so an assertion can name the row it identifies.
     *
     * @param token the sealed token to open; must not be {@code null}
     * @return the raw identifier the token carries, never {@code null}
     */
    private String openBoundary(String token) {
        return this.cursorToken.open(binding(), token);
    }

    /**
     * Composes the cursor binding exactly as the service under test composes it.
     *
     * <p>Assumptions: the binding is rebuilt here from the same published parts rather than read from
     * a constant on the service, because the service now derives it per call from its query name and
     * the calling subject and publishes no assembled constant. Restating the parts is what keeps this
     * class sealing tokens the service will accept.</p>
     *
     * @return the binding the service seals and opens boundary tokens under, never {@code null}
     */
    private static String binding() {
        return CursorToken.binding(TransactionListService.CURSOR_QUERY_NAME, SUBJECT,
                CursorToken.SCOPE_NONE);
    }

    /**
     * Builds a list request from its three components.
     *
     * @param transactionIdFilter the positioning identifier, or {@code null} for an opening scan
     * @param cursor the sealed cursor, or {@code null} when the caller holds no page
     * @param direction the requested direction, or {@code null} to accept the forward default
     * @return the request, never {@code null}
     */
    private static TransactionListRequest request(String transactionIdFilter, String cursor,
            TransactionListRequest.Direction direction) {
        return new TransactionListRequest(transactionIdFilter, cursor, direction);
    }

    /**
     * Builds an inclusive run of rows in ascending identifier order.
     *
     * @param from the first ordinal to build, one-based
     * @param to the last ordinal to build, one-based and not below {@code from}
     * @return the rows in ascending identifier order, never {@code null}
     */
    private static List<Transaction> rows(int from, int to) {
        List<Transaction> built = new ArrayList<>(to - from + 1);
        for (int ordinal = from; ordinal <= to; ordinal++) {
            built.add(row(ordinal));
        }
        return List.copyOf(built);
    }

    /**
     * Reverses a run of rows, giving the order a descending query returns them in.
     *
     * @param ascending the rows in ascending identifier order; must not be {@code null}
     * @return the same rows in descending identifier order, never {@code null}
     */
    private static List<Transaction> descending(List<Transaction> ascending) {
        List<Transaction> reversed = new ArrayList<>(ascending);
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /**
     * Builds one row whose identifier encodes its ordinal, so an assertion can name it.
     *
     * @param ordinal the one-based position of the row within the ordered set
     * @return the row, never {@code null}
     */
    private static Transaction row(int ordinal) {
        return new Transaction(tranId(ordinal), "01", "0001", "POS TERM  ",
                "Purchase at Abshire-Lowe", AMOUNT, 800000000L, "Abshire-Lowe",
                "North Enoshaven", "72112     ", CARD_NUM, ORIGIN_TS, ORIGIN_TS);
    }

    /**
     * Renders the sixteen-digit identifier of one ordinal.
     *
     * <p>Assumptions: the identifiers are zero-padded to the declared width so that ascending
     * lexicographic order and ascending ordinal order coincide, which is the property the whole key
     * space of this table relies on. An unpadded value would sort by length first and every ordering
     * assertion below would be measuring the wrong thing.
     *
     * @param ordinal the one-based position of the row within the ordered set
     * @return the identifier at exactly sixteen digits, never {@code null}
     */
    private static String tranId(int ordinal) {
        return String.format("%016d", ordinal);
    }

    /**
     * Builds the scripted stub the service reads through.
     *
     * @return a repository that answers from {@link #script} and records what it was asked for
     */
    private TransactionRepository scriptedRepository() {
        InvocationHandler handler = new ScriptedFinderHandler();
        return (TransactionRepository) Proxy.newProxyInstance(
                TransactionRepository.class.getClassLoader(),
                new Class<?>[] {TransactionRepository.class},
                handler);
    }

    /**
     * Answers the four repository operations this suite scripts and refuses every other.
     *
     * <p>Assumptions: an unscripted operation raises rather than answering an empty result, because an
     * empty answer is indistinguishable from a correct answer on several of the cases above and would
     * turn a service that consulted the wrong finder into a passing test.
     */
    private final class ScriptedFinderHandler implements InvocationHandler {

        /**
         * Records the operation and answers it from the script.
         *
         * @param proxy the proxy the call arrived on, which this handler does not consult
         * @param method the interface operation invoked; must not be {@code null}
         * @param arguments the invocation arguments, or {@code null} for a no-argument operation
         * @return the scripted rows, an {@link Optional} for the keyed read, or the value the
         *     inherited {@link Object} operations require
         * @throws UnsupportedOperationException if the operation is not one this suite scripts
         * @throws IllegalStateException if a scripted operation was called with no script in place
         */
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            String name = method.getName();
            switch (name) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == arguments[0];
                case "toString":
                    return "ScriptedTransactionRepository";
                case FINDER_OPENING:
                    return this.answer(name, "limit=" + limitOf(arguments[0]));
                case FINDER_FORWARD:
                case FINDER_BACKWARD:
                    return this.answer(name,
                            "key=" + arguments[0] + ", limit=" + limitOf(arguments[1]));
                case FINDER_BY_ID:
                    this.record(name, "key=" + arguments[0]);
                    return this.scripted(name).stream().findFirst();
                default:
                    throw new UnsupportedOperationException(
                            "the transaction list service is not expected to call " + name);
            }
        }

        /**
         * Records one call and returns its scripted rows.
         *
         * @param name the operation called; must not be {@code null}
         * @param renderedArguments the arguments rendered for assertion; must not be {@code null}
         * @return the scripted rows, never {@code null}
         * @throws IllegalStateException if the operation has no script in place
         */
        private List<Transaction> answer(String name, String renderedArguments) {
            this.record(name, renderedArguments);
            return this.scripted(name);
        }

        /**
         * Appends one call to the two call records.
         *
         * @param name the operation called; must not be {@code null}
         * @param renderedArguments the arguments rendered for assertion; must not be {@code null}
         */
        private void record(String name, String renderedArguments) {
            selectedFinders.add(name);
            recordedArguments.add(name + "(" + renderedArguments + ")");
        }

        /**
         * Reads the script for one operation.
         *
         * @param name the operation to answer; must not be {@code null}
         * @return the scripted rows, never {@code null}
         * @throws IllegalStateException if the operation has no script in place
         */
        private List<Transaction> scripted(String name) {
            List<Transaction> answer = script.get(name);
            if (answer == null) {
                throw new IllegalStateException(
                        "no rows were scripted for " + name + ", so this case cannot assert anything"
                                + " about the finder the service selected");
            }
            return answer;
        }
    }

    /**
     * Renders the row cap a finder was called with.
     *
     * @param limit the {@link Limit} the service supplied; must not be {@code null}
     * @return the cap as text, never {@code null}
     */
    private static String limitOf(Object limit) {
        return String.valueOf(((Limit) limit).max());
    }

    /**
     * A backward step with no cursor reads nothing rather than falling back to the opening page.
     *
     * <p>Assumptions: the two are told apart deliberately. A caller asking for the page BEFORE a
     * position it did not supply is asking for something that does not exist, and answering with the
     * opening page would tell it the browse had reached its start when in fact the request was
     * malformed. The reference program reaches its previous page from a stored key and has no
     * previous-without-a-key state to reproduce.</p>
     *
     * <p>Assumptions: the assertion is that NO finder was selected, not merely that the page was empty.
     * An implementation that read the backward finder with a null key and happened to get nothing back
     * would produce the same empty page while issuing a query the contract does not admit.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a backward step with no cursor reads nothing and selects no finder")
    void aBackwardStepWithoutACursorReadsNothing() {
        PageResponse<TransactionListItemResponse> page =
                this.list(request(null, null, TransactionListRequest.Direction.PREVIOUS));

        assertThat(this.selectedFinders).isEmpty();
        assertThat(this.recordedArguments).isEmpty();
        assertThat(page.items()).isEmpty();
        assertThat(page.firstKey()).isNull();
        assertThat(page.lastKey()).isNull();
        assertThat(page.hasNext()).isFalse();
    }

    /**
     * An unstated direction resolves forward, and resolves it on the request rather than in the service.
     *
     * <p>Assumptions: the default is asserted on {@code effectiveDirection()} AND through a read, because
     * the two could disagree. The request type resolving the default while the service branched on the
     * raw member would leave a null direction taking neither branch, and a test of either half alone
     * would pass.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("an unstated direction resolves forward")
    void anUnstatedDirectionResolvesForward() {
        this.script.put(FINDER_OPENING, rows(1, 1));

        assertThat(request(null, null, null).effectiveDirection())
                .isEqualTo(TransactionListRequest.Direction.NEXT);

        PageResponse<TransactionListItemResponse> page = this.list(request(null, null, null));

        assertThat(this.selectedFinders).containsExactly(FINDER_OPENING);
        assertThat(page.items()).hasSize(1);
    }

    /**
     * Every argument of the paging operation is required, and each is refused by name.
     *
     * <p>Assumptions: the three are asserted separately rather than as one refusal, because they fail
     * for three different reasons and a single assertion would pass if one check subsumed another. The
     * subject in particular is checked here rather than only at the seal: a null reaching
     * {@code CursorToken.binding} would compose a binding naming the word null, which every caller with
     * no subject would then share.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("every argument of the paging operation is required")
    void everyArgumentIsRequired() {
        TransactionListService service = new TransactionListService(this.repository, this.mapper);

        assertThatThrownBy(() -> service.listTransactions(null, this.cursorToken, SUBJECT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.listTransactions(request(null, null, null), null, SUBJECT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                service.listTransactions(request(null, null, null), this.cursorToken, null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * Both collaborators are required at construction rather than at first use.
     *
     * <p>Assumptions: refusing at construction is what keeps a half-built service from being held at
     * all. Deferring the check to the first read would surface the fault on a request, attributing an
     * assembly error to whichever caller happened to arrive first.</p>
     *
     * <p>This test takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("both collaborators are required at construction")
    void bothCollaboratorsAreRequiredAtConstruction() {
        assertThatThrownBy(() -> new TransactionListService(null, this.mapper))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionListService(this.repository, null))
                .isInstanceOf(NullPointerException.class);
    }

}
