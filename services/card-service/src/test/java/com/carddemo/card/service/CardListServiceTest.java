package com.carddemo.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.card.domain.Card;
import com.carddemo.card.dto.CardSummary;
import com.carddemo.card.mapper.CardMapper;
import com.carddemo.card.repository.CardRepository;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.security.SealedSelector;
import com.carddemo.common.validation.FieldValidationFlag;
import com.carddemo.common.web.CursorToken;
import com.carddemo.common.web.PageResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

/**
 * Holds the browse of {@link CardListService} to the paging behaviour of the reference card list
 * program, and makes the rejection of row-counted paging an assertion rather than a statement.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@link CardListService#list(Long, String, boolean)} is the migrated successor of the indexed
 * browse that {@code app/cbl/COCRDLIC.cbl} drives across eight file verbs, and it is the reference
 * implementation of that conversion for the whole migration. This class asserts the properties that
 * make the conversion a transcription: that each direction resolves to one query, that the query is
 * positioned by a key rather than by a count of consumed rows, that one row beyond the window is
 * requested so a further page can be discovered, that a row arriving between two requests neither
 * conceals a row nor presents one twice, and that every sentence a caller can be shown is carried
 * across character for character. It takes no parameter and returns no value at the type level; each
 * member below declares its own.
 *
 * <h2>What this class asserts, and what it deliberately does not</h2>
 *
 * <p>Assumptions: only the store is substituted. A mocked {@link CardRepository} settles which query
 * the service chose, which key and direction it handed over and what it did with the rows handed
 * back, and nothing here inspects generated SQL. The charter in {@code package-info.java} beside this
 * file assigns the correctness of that SQL to {@code CardRepositoryIT} in the sibling
 * {@code com.carddemo.card.repository} test package, the single-card detail route to
 * {@code CardControllerTest} in the sibling {@code com.carddemo.card.api} test package, and layering
 * to {@code LayeringRulesTest} under {@code services/common-lib}. None of those three claims is made
 * here, and no assertion below should be read as making one.
 *
 * <p>Assumptions: the mapper, the selector sealer and the cursor signer are REAL, and only the store
 * is a substitute. Alternatives Considered: substituting the mapper as well and inspecting the
 * domain-level page through an argument captor. Rejected because two of the properties under test are
 * properties of the published envelope rather than of the internal one -- a boundary cursor must be a
 * token the same signer can open again, and a row identity must be stable enough to compare across
 * two requests -- and substituting the components that produce them would make both properties
 * properties of the substitute. The cost accepted is that a failure in the mapper or the sealer can
 * also fail a test here; that cost is visible because those components have their own tests, in
 * {@code com.carddemo.card.mapper} and in {@code services/common-lib}.
 *
 * <p>Assumptions: no comparison against recorded mainframe output exists for this program, and none is
 * claimed. The COBOL parity suite under {@code tests} records at lines 83 to 85 of its
 * {@code README.md} that the online programs cannot be run end to end without a CICS runtime, which
 * the runner does not have, and that only their extractable field-validation logic is unit tested. Its
 * golden masters cover the batch flows and {@code COCRDLIC} is not a batch program. Correctness here
 * therefore rests on transcription fidelity against the paragraphs cited line by line on each test, so
 * that every assertion can be checked against the reference rather than against this file.
 *
 * <h2>Why paging by key and not by a count of rows</h2>
 *
 * <p>Alternatives Considered: resuming a page by counting rows from the start of the ordered set,
 * which is the other way a second page can be asked for. It is rejected on what a caller observes.
 * The number of rows preceding a resume point changes whenever a row is inserted or removed ahead of
 * it, so a count-based resume shows a row twice or omits one it never showed; a key-based resume
 * cannot, because a key is unaffected by a row arriving elsewhere in the ordered set. That is asserted
 * rather than asserted-about by {@link #aRowArrivingBetweenRequestsIsNeitherHiddenNorRepeated()},
 * which is written so that reshaping the substituted store into a count-based resume fails it.
 *
 * <p>Refactoring Rationale: the substitution is one-for-one rather than an approximation, and that is
 * what makes it a transcription. The reference already holds its browse position as keys: a last-key
 * pair at {@code app/cbl/COCRDLIC.cbl:230-232}, a first-key pair at {@code :233-235} and an indicator
 * at {@code :242-244} that carries nothing but whether a further page exists. No count of consumed
 * rows appears anywhere in that structure, so nothing had to be invented for the target and nothing
 * had to be dropped from the reference. The eight verbs collapse accordingly: {@code STARTBR} at
 * {@code :1129} and {@code :1273}, {@code READNEXT} at {@code :1146} and {@code :1197},
 * {@code READPREV} at {@code :1294} and {@code :1322}, and {@code ENDBR} at {@code :1258} and
 * {@code :1376} become one query per direction and no release at all, because a query holds no
 * position to release.
 *
 * <h2>Three reference behaviours this class asserts are absent, each on purpose</h2>
 *
 * <p>Alternatives Considered: asserting the page ordinal the reference paints. The symbolic map
 * declares the field at {@code app/cpy-bms/COCRDLI.CPY:60} as {@code PAGENOI PIC X(3)} and the
 * reference fills it from {@code WS-CA-SCREEN-NUM} at {@code app/cbl/COCRDLIC.cbl:237}. There is no
 * such component on {@link PageResponse} to assert, and it is absent because producing an ordinal
 * means knowing how many rows precede the page, which is the count this class rejects two paragraphs
 * above. Trade-offs: what is given up is a caller's ability to display which page of how many it is
 * looking at; what is bought is that the boundaries of every page survive concurrent change. The
 * reference pays the opposite price, and the divergence is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than left for a reader to notice.
 *
 * <p>Alternatives Considered: reproducing the clear-a-narrowing sentinel and asserting it. The
 * reference replaces a narrowing field holding {@code '*'} or spaces with low values, under the
 * comment at {@code app/cbl/COCRDSLC.cbl:614} and in the two blocks at {@code :615-620} and
 * {@code :622-627}. Rejected because the sentinel exists to blank a field the terminal has already
 * pre-filled, and this route has no pre-filled field: a narrowing a caller does not send is simply
 * absent, which {@link #anAbsentNarrowingIsAcceptedAndNarrowsNothing()} asserts directly. There is
 * consequently no {@code '*'} case to test, and its absence is a consequence of the request shape
 * rather than an omission.
 *
 * <p>Assumptions: {@code CDEMO-PGM-CONTEXT}, the flag the reference uses to tell a first arrival from
 * a re-entry, has no counterpart here and no test asserts one. Stateless handling has no turn to
 * remember, so the field errors this class asserts are driven by the response alone -- which is a
 * difference worth stating because the reference gates its field highlighting on exactly that flag, in
 * the templated copybook {@code app/cpy/CSSETATY.cpy:17-27}.
 *
 * <h2>How the sibling classes in this package divide the work</h2>
 *
 * <p>Assumptions: {@code CardSelectorRouteTest} in this same package already asserts that a browse
 * chooses the forward query, that a backward browse renders ascending, that the account narrowing
 * reaches the query and that the surplus row is trimmed. This class does not restate those. What it
 * adds is the exactness those assertions leave open: the precise bound handed to the query rather than
 * any bound, the precise key handed to it recovered from the published cursor rather than a literal,
 * three consecutive pages over the real corpus rather than a synthetic run, the behaviour of a page
 * boundary under a concurrent arrival, and every reference sentence a caller can be shown.
 *
 * <p>Every path under {@code app/} cited in this file is reference material. It is read as the
 * specification, is never modified, and keeps running; these assertions are added beside it.
 */
class CardListServiceTest {

    /** Test-only key material for the selector sealer, sized past the sealer's thirty-two-byte floor. */
    private static final byte[] SELECTOR_KEY =
            "carddemo-card-list-service-test-selector-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /** Test-only key material for the cursor signer, sized past the signer's thirty-two-byte floor. */
    private static final byte[] CURSOR_KEY =
            "carddemo-card-list-service-test-cursor-material-not-a-secret"
                    .getBytes(StandardCharsets.UTF_8);

    /** How long a sealed cursor stays redeemable; generous, because no case here asserts expiry. */
    private static final Duration CURSOR_LIFETIME = Duration.ofHours(1);

    /** Classpath name of the eighteen-record browse corpus, three pages at the reference window. */
    private static final String CORPUS_RESOURCE = "fixtures/card-list-page-corpus.txt";

    /** Classpath name of the single-record positive control. */
    private static final String SINGLE_ROW_RESOURCE = "fixtures/card-valid-active.txt";

    /** Classpath name of the zero-byte corpus that stands for a browse matching nothing. */
    private static final String EMPTY_RESOURCE = "fixtures/card-empty-input.txt";

    /**
     * Bytes one corpus line occupies: the hundred-and-fifty-byte record plus its line terminator.
     *
     * <p>Assumptions: the record length is the one {@code app/cpy/CVACT02Y.cpy:4} declares for
     * {@code CARD-RECORD} and the invariant that a corpus measures this many bytes for every record it
     * holds is stated normatively at line 202 of
     * {@code services/card-service/src/test/resources/fixtures/README.md}. That file owns the byte
     * table and this class consumes it; the positions are not re-derived here, which is the discipline
     * the COBOL parity suite states at lines 540 to 542 of its own {@code README.md} -- never duplicate
     * a layout, keep it single-sourced.
     */
    private static final int LINE_LENGTH = 151;

    /** Half-open byte range of {@code CARD-NUM}, positions 1 to 16 of the record. */
    private static final int CARD_NUM_FROM = 0;

    /** End of the {@code CARD-NUM} range, exclusive. */
    private static final int CARD_NUM_TO = 16;

    /** End of the {@code CARD-ACCT-ID} range, exclusive; positions 17 to 27 of the record. */
    private static final int ACCOUNT_ID_TO = 27;

    /** Start of the {@code CARD-EMBOSSED-NAME} range; positions 31 to 80 of the record. */
    private static final int EMBOSSED_NAME_FROM = 30;

    /** End of the {@code CARD-EMBOSSED-NAME} range, exclusive. */
    private static final int EMBOSSED_NAME_TO = 80;

    /** Start of the {@code CARD-EXPIRAION-DATE} range; positions 81 to 90 of the record. */
    private static final int EXPIRATION_DATE_FROM = 80;

    /** End of the {@code CARD-EXPIRAION-DATE} range, exclusive; positions 81 to 90 of the record. */
    private static final int EXPIRATION_DATE_TO = 90;

    /** End of the {@code CARD-ACTIVE-STATUS} range, exclusive; position 91 of the record. */
    private static final int ACTIVE_STATUS_TO = 91;

    /** Records the corpus holds, and the count the three-page arithmetic below depends on. */
    private static final int CORPUS_SIZE = 18;

    /**
     * The smallest value too wide for the eleven-digit narrowing field, so the gate must refuse it.
     *
     * <p>Assumptions: the reference narrowing field is {@code PIC X(11)} overlaid by {@code PIC 9(11)}
     * at {@code app/cpy/CVCRD01Y.cpy:34-36}, so eleven digits is the whole domain and the smallest
     * twelve-digit value is the first one outside it. It is a width boundary rather than an account
     * anyone holds, which is why it is named for the width it exceeds.
     */
    private static final long TOO_WIDE_FOR_ELEVEN_DIGITS = 100_000_000_000L;

    /** A negative narrowing, the other value outside a field that carries unsigned digits only. */
    private static final long NEGATIVE_NARROWING = -1L;

    /** The ordered set the browse cases read, ascending by card number as the corpus is authored. */
    private List<Card> corpus;

    /** The one-record positive control, for the degenerate page whose two boundaries coincide. */
    private List<Card> singleRow;

    /** The zero-record corpus, standing for a browse that matched nothing at all. */
    private List<Card> emptyCorpus;

    /**
     * The authenticated caller every cursor in this class is bound to.
     *
     * <p>Assumptions: one subject serves the whole class because the cases here exercise paging rather
     * than isolation between callers; the isolation property has its own cases below, which name a second
     * subject explicitly so the difference between the two is visible in the case that depends on it.</p>
     */
    private static final String SUBJECT = "CARDUSR1";

    /** A second caller, used only to show that one caller's cursor is refused for another. */
    private static final String OTHER_SUBJECT = "CARDUSR2";

    /** The signer that seals and opens every cursor in this class, one instance so tokens round-trip. */
    private CursorToken sealer;

    /** The projection the service publishes through, real so a published row is a real row. */
    private CardMapper mapper;

    /**
     * Loads the browse corpus and builds the two real collaborators before each case.
     *
     * <p>Assumptions: one signer instance serves a whole case because a cursor sealed by one instance
     * is only openable by an instance holding the same key, and several cases hand a published cursor
     * straight back into a second request. Building the collaborators here rather than once for the
     * class keeps each case independent, which is what lets the whole class run in any order.
     *
     * <p>It takes no parameter and returns no value.
     *
     * @throws IOException if the corpus cannot be read from the test classpath
     */
    @BeforeEach
    void loadCorpusAndCollaborators() throws IOException {
        this.corpus = cardsFrom(CORPUS_RESOURCE);
        this.singleRow = cardsFrom(SINGLE_ROW_RESOURCE);
        this.emptyCorpus = cardsFrom(EMPTY_RESOURCE);
        this.sealer = new CursorToken(CURSOR_KEY, CURSOR_LIFETIME);
        this.mapper = new CardMapper(new SealedSelector(SELECTOR_KEY));
    }

    /**
     * Asserts that a forward step resumes past the key of the last row the previous page returned.
     *
     * <p>Assumptions: transcribed from {@code 9000-READ-FORWARD.} at {@code app/cbl/COCRDLIC.cbl:1123},
     * whose {@code READNEXT} at {@code :1146} walks the ordered set in ascending key order from a
     * position the reference established on a stored key. The key it stores is the one carried across a
     * screen turn at {@code :230-232}, so the position handed to a query here is the target's statement
     * of that same value.
     *
     * <p>Assumptions: the expected resume key is read out of the corpus rather than written into this
     * file. Alternatives Considered: naming the key as a literal, which is how a reader might expect an
     * expectation to be stated. Rejected for two reasons: the fixture is the single source of the corpus
     * and a literal would be a second copy of it that could silently disagree, and the value is a
     * primary account number, which this file has no reason to hold in its source at all.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a forward step resumes past the key of the last row the previous page returned")
    void aForwardStepResumesPastTheLastReturnedKey() {

        CardRepository cards = keysetStore(this.corpus);
        CardListService service = serviceOver(cards);

        PageResponse<CardSummary> first = service.list(null, null, false, SUBJECT);
        PageResponse<CardSummary> second = service.list(null, first.lastKey(), false, SUBJECT);

        String lastRowOfFirstPage = this.corpus.get(CardListService.PAGE_SIZE - 1).getCardNum();

        ArgumentCaptor<String> positions = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Limit> bounds = ArgumentCaptor.forClass(Limit.class);
        verify(cards, times(2))
                .findForwardFromCursor(positions.capture(), isNull(), isNull(), bounds.capture());
        verify(cards, never()).findBackwardFromCursor(any(), any(), any(), any());

        assertThat(positions.getAllValues().get(0))
                .as("an opening browse states no position, which is the reference's own first entry")
                .isNull();
        assertThat(positions.getAllValues().get(1))
                .as("the resume position is the key of the last row shown, not of the row beyond it")
                .isEqualTo(lastRowOfFirstPage);
        assertThat(this.sealer.open(forwardBinding(), first.lastKey()))
                .as("the published boundary cursor opens to that same key, so a caller can hand it back")
                .isEqualTo(lastRowOfFirstPage);
        assertThat(bounds.getAllValues())
                .allSatisfy(bound -> assertThat(bound.max())
                        .isEqualTo(CardListService.PAGE_SIZE + 1));
        assertThat(publishedIdentities(second))
                .isEqualTo(identitiesOf(this.corpus.subList(
                        CardListService.PAGE_SIZE, CardListService.PAGE_SIZE * 2)));
    }

    /**
     * Asserts that a backward step resumes before the first returned key and lands on the prior page.
     *
     * <p>Assumptions: transcribed from {@code 9100-READ-BACKWARDS.} at
     * {@code app/cbl/COCRDLIC.cbl:1264}, which repositions on the first key of the displayed page --
     * held across a screen turn at {@code :233-235} -- and then walks away from it with
     * {@code READPREV} at {@code :1294} and {@code :1322}. Reading away from a key in descending order
     * and rendering the result ascending is what one backward query states.
     *
     * <p>Assumptions: walking forward twice and then back once must land exactly on the page walked
     * away from, which is a stronger statement than asserting a row count. It also settles which end of
     * a backward read carries the surplus row: the surplus of a descending read is the LOWEST row, so
     * trimming the same end in both directions would drop a row the caller is owed. Landing back on the
     * identical set of rows can only happen if the low end was the end trimmed.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a backward step resumes before the first returned key and lands on the prior page")
    void aBackwardStepResumesBeforeTheFirstReturnedKey() {

        CardRepository cards = keysetStore(this.corpus);
        CardListService service = serviceOver(cards);

        PageResponse<CardSummary> first = service.list(null, null, false, SUBJECT);
        PageResponse<CardSummary> second = service.list(null, first.lastKey(), false, SUBJECT);
        PageResponse<CardSummary> third = service.list(null, second.lastKey(), false, SUBJECT);
        PageResponse<CardSummary> backToSecond = service.list(null, third.firstKey(), true, SUBJECT);

        String firstRowOfThirdPage = this.corpus.get(CardListService.PAGE_SIZE * 2).getCardNum();

        ArgumentCaptor<String> positions = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Limit> bounds = ArgumentCaptor.forClass(Limit.class);
        verify(cards).findBackwardFromCursor(
                positions.capture(), isNull(), isNull(), bounds.capture());

        assertThat(positions.getValue())
                .as("the backward position is the key of the first row shown, so the read moves away"
                        + " from it")
                .isEqualTo(firstRowOfThirdPage);
        assertThat(bounds.getValue().max()).isEqualTo(CardListService.PAGE_SIZE + 1);
        assertThat(publishedIdentities(backToSecond))
                .as("a step back from the third page is the second page, row for row and in order")
                .isEqualTo(publishedIdentities(second));
    }

    /**
     * Asserts that the row beyond the window is what discovers a further page, over three real pages.
     *
     * <p>Assumptions: the surplus row is the reference's own technique carried across rather than an
     * addition of this migration, which is why it is asserted as behaviour and not merely as a bound.
     * The reference counts rows against its screen limit at {@code app/cbl/COCRDLIC.cbl:1191}, leaves
     * the loop at {@code :1192}, captures the last displayed keys at {@code :1194-1195} and then issues
     * ONE further {@code READNEXT} at {@code :1197}; if that read returns a row it sets the further-page
     * condition at {@code :1210-1211}, and if it reaches the end it clears it at {@code :1216}. A query
     * bounded at the window plus one states exactly that, and nothing else does: a query bounded at the
     * window alone cannot tell a full page from a last page.
     *
     * <p>Assumptions: the corpus is authored to make both outcomes reachable, at eighteen records over
     * a window of seven, so the third page is short and the flag must fall. Its registration at line
     * 293 of {@code services/card-service/src/test/resources/fixtures/README.md} records that sizing.
     *
     * <p>Assumptions: the parameter counts REQUESTS, not rows. That distinction is the whole subject of
     * this class: a number of requests to issue is a loop bound in a test, whereas a number of rows to
     * skip would be the resume mechanism this class rejects, and the two must not be confused because
     * they read alike.
     *
     * @param requests how many consecutive forward requests to issue, each resuming from the boundary
     *     cursor the previous one published
     * @param expectedRows how many rows the page reached by that many requests must carry
     * @param expectedFurtherPage whether a further page must be reported after that many requests
     */
    @ParameterizedTest
    @CsvSource({"1, 7, true", "2, 7, true", "3, 4, false"})
    @DisplayName("the row beyond the window discovers a further page, and its absence retires the flag")
    void theRowBeyondTheWindowDiscoversAFurtherPage(
            int requests, int expectedRows, boolean expectedFurtherPage) {

        CardRepository cards = keysetStore(this.corpus);
        CardListService service = serviceOver(cards);

        PageResponse<CardSummary> page = null;
        for (int issued = 0; issued < requests; issued++) {
            page = service.list(null, page == null ? null : page.lastKey(), false, SUBJECT);
        }

        ArgumentCaptor<Limit> bounds = ArgumentCaptor.forClass(Limit.class);
        verify(cards, times(requests))
                .findForwardFromCursor(nullable(String.class), isNull(), isNull(), bounds.capture());

        assertThat(page).isNotNull();
        assertThat(page.items()).hasSize(expectedRows);
        assertThat(page.hasNext()).isEqualTo(expectedFurtherPage);
        assertThat(bounds.getAllValues())
                .as("every read asks for one row more than the window holds, on every page")
                .allSatisfy(bound -> assertThat(bound.max())
                        .isEqualTo(CardListService.PAGE_SIZE + 1));
    }

    /**
     * Asserts that a row arriving between two requests neither hides a row nor presents one twice.
     *
     * <p>Alternatives Considered: resuming the second request by counting rows from the start of the
     * ordered set, rather than from the key of the last row shown. This is the case that decides between
     * them, and it decides on what a caller observes rather than on preference. A row arriving ahead of
     * the resume point changes how many rows precede that point, so a count-based resume slides
     * backwards over ground it has already covered and shows a row a second time, and correspondingly
     * defers a row to a page that may never be asked for. A key-based resume cannot do either, because
     * the key of a row already shown does not move when a row arrives elsewhere. The arrival here is
     * placed deliberately INSIDE the span the first page returned, which is where the two mechanisms
     * diverge; an arrival past the resume point would be answered identically by both and would prove
     * nothing.
     *
     * <p>Assumptions: what is claimed is bounded, and the bound matters. No row the first page showed
     * appears again, and no row positioned after the resume key is omitted. The arriving row itself is
     * shown by neither page, and that is correct rather than an omission: it sorts BEHIND the resume key,
     * and
     * the reference behaves identically because it repositions on a stored key too -- its
     * {@code STARTBR} at {@code app/cbl/COCRDLIC.cbl:1273} and its forward reposition at {@code :1129}
     * both establish a position from a key held across the screen turn, so a row arriving behind that
     * key is equally invisible to it.
     *
     * <p>Assumptions: the arriving key is derived from the corpus so that no card number is written into
     * this file, and the derivation is verified in the case itself rather than trusted. It keeps the
     * first fifteen characters of the fifth row and raises the last, which places it above that row; it
     * remains below the sixth row because the two rows already differ in an earlier character. Both
     * relations are asserted before the arrival is used, so a corpus edited in a way that invalidates
     * the placement fails here with the reason stated instead of quietly testing nothing.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a row arriving between two requests is neither hidden nor presented twice")
    void aRowArrivingBetweenRequestsIsNeitherHiddenNorRepeated() {

        List<Card> live = new ArrayList<>(this.corpus);
        CardRepository cards = keysetStore(live);
        CardListService service = serviceOver(cards);

        PageResponse<CardSummary> firstPage = service.list(null, null, false, SUBJECT);

        int arrivalIndex = CardListService.PAGE_SIZE - 2;
        String below = this.corpus.get(arrivalIndex - 1).getCardNum();
        String above = this.corpus.get(arrivalIndex).getCardNum();

        // Assumptions: raising the final character to its highest digit places the arrival above the
        // row it is derived from without this file needing to know what that row's final digit is,
        // and it stays below the next row because the two already differ in an earlier character.
        // Alternatives Considered: adding one to the row's numeric value. Rejected because a
        // sixteen-digit key with a significant leading zero is not a number here, so incrementing it
        // would have to re-pad the result and could carry into a character the comparison relies on.
        String arrivingKey = below.substring(0, below.length() - 1) + '9';

        assertThat(arrivingKey)
                .as("the arrival must sort strictly above the row before it, or it is not an arrival"
                        + " inside the span the first page returned")
                .isGreaterThan(below);
        assertThat(arrivingKey)
                .as("the arrival must sort strictly below the next corpus row, or it is not inside that"
                        + " span either")
                .isLessThan(above);

        // Alternatives Considered: letting the arrival land past the resume key instead. Rejected
        // because both resume mechanisms answer that case identically, so it would exercise the
        // arrival without distinguishing between them; only an arrival inside the span the first page
        // already returned moves the row count while leaving the key untouched.
        live.add(arrivalIndex, cardOf(arrivingKey, this.corpus.get(arrivalIndex)));

        PageResponse<CardSummary> secondPage = service.list(null, firstPage.lastKey(), false, SUBJECT);

        List<String> shownFirst = publishedIdentities(firstPage);
        List<String> shownSecond = publishedIdentities(secondPage);

        assertThat(shownSecond)
                .as("no row the first page showed may be shown again once a row has arrived behind the"
                        + " resume key")
                .doesNotContainAnyElementsOf(shownFirst);

        List<String> shownAcross = new ArrayList<>(shownFirst);
        shownAcross.addAll(shownSecond);
        assertThat(shownAcross).doesNotHaveDuplicates();

        assertThat(shownSecond)
                .as("every row positioned after the resume key is still delivered, in order and with"
                        + " none omitted")
                .isEqualTo(identitiesOf(this.corpus.subList(
                        CardListService.PAGE_SIZE, CardListService.PAGE_SIZE * 2)));

        assertThat(shownAcross)
                .as("the arriving row sorts behind the resume key, so it is shown by neither page")
                .doesNotContain(identityOf(cardOf(arrivingKey, this.corpus.get(arrivalIndex))));

        ArgumentCaptor<String> positions = ArgumentCaptor.forClass(String.class);
        verify(cards, times(2))
                .findForwardFromCursor(positions.capture(), isNull(), isNull(), any(Limit.class));
        assertThat(positions.getAllValues().get(1))
                .as("the resume position is unchanged by the arrival, which is the mechanism the two"
                        + " assertions above rest on")
                .isEqualTo(this.corpus.get(CardListService.PAGE_SIZE - 1).getCardNum());
    }

    /**
     * Asserts that the browse window is seven rows and that the corpus is exactly three such pages.
     *
     * <p>Assumptions: seven is the reference window carried across rather than a number chosen for the
     * target, and the reference states it three independent ways, so the transcription checks itself.
     * {@code app/cbl/COCRDLIC.cbl:177-178} declares {@code WS-MAX-SCREEN-LINES PIC S9(4) COMP} with
     * {@code VALUE 7}; the comment at {@code :250} works the same number out as
     * {@code 28 CHARS X 7 ROWS = 196}, over the row array declared {@code PIC X(196)} at {@code :253},
     * redefined {@code OCCURS 7 TIMES} at {@code :255} across the twenty-eight-byte row of
     * {@code WS-ROW-ACCTNO X(11)} at {@code :258}, {@code WS-ROW-CARD-NUM X(16)} at {@code :259} and
     * {@code WS-ROW-CARD-STATUS X(1)} at {@code :260}; and the symbolic map carries seven row groups and
     * no eighth, from {@code CRDSEL1I} at {@code app/cpy-bms/COCRDLI.CPY:78} to {@code CRDSEL7I} at
     * {@code :252}.
     *
     * <p>Assumptions: walking the corpus to exhaustion and requiring the pages to account for every
     * record exactly once is what ties the window to the fixture. A page count alone would pass against
     * a window of six with a row quietly lost at each boundary.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the browse window is seven rows and three pages account for the corpus exactly once")
    void theBrowseWindowIsSevenRows() {

        assertThat(CardListService.PAGE_SIZE)
                .as("the window is the reference's seven rows")
                .isEqualTo(7);
        assertThat(this.corpus)
                .as("the corpus is authored at eighteen records so a short final page is reachable")
                .hasSize(CORPUS_SIZE);

        CardRepository cards = keysetStore(this.corpus);
        CardListService service = serviceOver(cards);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        int pagesWalked = 0;
        do {
            PageResponse<CardSummary> page = service.list(null, cursor, false, SUBJECT);
            assertThat(page.items().size())
                    .as("no page may exceed the window")
                    .isLessThanOrEqualTo(CardListService.PAGE_SIZE);
            walked.addAll(publishedIdentities(page));
            cursor = page.hasNext() ? page.lastKey() : null;
            pagesWalked++;
        } while (cursor != null);

        assertThat(pagesWalked)
                .as("eighteen records over a window of seven is three pages of seven, seven and four")
                .isEqualTo(3);
        assertThat(walked).doesNotHaveDuplicates();
        assertThat(walked)
                .as("the walk accounts for every corpus record exactly once and in key order")
                .isEqualTo(identitiesOf(this.corpus));
    }

    /**
     * Asserts that both page-boundary refusals are carried verbatim and are raised only at a boundary.
     *
     * <p>Assumptions: transcribed from the two paging arms of {@code 1400-SETUP-MESSAGE.} at
     * {@code app/cbl/COCRDLIC.cbl:895}. The backward refusal is the literal at {@code :903}, moved to
     * the message field at {@code :904} under the arm that tests the backward paging key at
     * {@code :901} together with the opening-page condition at {@code :902}; the forward refusal is the
     * literal at {@code :908}, under the arm at {@code :905} that additionally requires no further page
     * at {@code :906} and the final page already shown at {@code :907}. The two attention identifiers
     * those arms test are declared at {@code app/cpy/CVCRD01Y.cpy:14} and {@code :15}.
     *
     * <p>Refactoring Rationale: the backward refusal is asserted in ONE step, from the opening page a
     * caller is standing on, because that is where the reference raises it -- {@code :901-902} tests the
     * key together with the ordinal and never reads the file. This case previously asserted it in two
     * steps, off the page a backward request from the opening page returned, and both halves of that
     * arrangement were reported as defects: the page in question was an exhausted envelope that had
     * already replaced the caller's rows, and the opening page itself reported no refusal at all, because
     * every page carrying rows names its own first row.
     *
     * <p>Assumptions: both sentences are upper case with no trailing period, and each is asserted
     * against its own literal rather than against the other. They are also asserted DISTINCT from the
     * exhausted-read notice, because the reference deliberately declares three separate strings -- one
     * answers a key press, one reports what a read found -- and a single string reused would read as
     * correct while losing a distinction an operator relies on.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("both page-boundary refusals are verbatim and are raised only at their own boundary")
    void bothPageBoundaryRefusalsAreCarriedVerbatim() {

        assertThat(CardListService.MESSAGE_NO_PREVIOUS_PAGES)
                .isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
        assertThat(CardListService.MESSAGE_NO_MORE_PAGES)
                .isEqualTo("NO MORE PAGES TO DISPLAY");
        assertThat(CardListService.MESSAGE_NO_MORE_PAGES)
                .as("a key-press refusal and a read notice are separate reference sentences")
                .isNotEqualTo(CardListService.MESSAGE_NO_MORE_RECORDS);

        CardRepository cards = keysetStore(this.corpus);
        CardListService service = serviceOver(cards);

        PageResponse<CardSummary> opening = service.list(null, null, false, SUBJECT);
        PageResponse<CardSummary> second = service.list(null, opening.lastKey(), false, SUBJECT);
        PageResponse<CardSummary> last = service.list(null, second.lastKey(), false, SUBJECT);
        assertThat(CardListService.pagingRefusal(last, false, false))
                .contains(CardListService.MESSAGE_NO_MORE_PAGES);

        // WHY : Refactoring Rationale: the backward refusal is asserted against the OPENING page held by
        //       a caller standing on it, which is the state the reference tests. It was previously
        //       asserted against the page a backward request from that opening page returned -- an
        //       exhausted envelope -- and that arrangement encoded two defects at once: it read the
        //       refusal off a page whose rows had already been replaced by nothing, and it left the
        //       opening page itself reporting no refusal, since every page carrying rows names its own
        //       first row. Both were reported against the service.
        assertThat(CardListService.pagingRefusal(opening, true, true))
                .contains(CardListService.MESSAGE_NO_PREVIOUS_PAGES);

        assertThat(CardListService.pagingRefusal(opening, false, true))
                .as("a page reporting a further page refuses no forward step")
                .isEmpty();
        assertThat(CardListService.pagingRefusal(second, true, false))
                .as("a page naming a leading boundary refuses no backward step")
                .isEmpty();
    }

    /**
     * Asserts that a backward step from the opening page keeps the caller's rows rather than blanking
     * them, and that the refusal is available before the request is even issued.
     *
     * <p>Purpose: this is the runtime half of the reference's first-page arm. {@code app/cbl/COCRDLIC.cbl}
     * pairs the backward paging key with the first-page condition at {@code :443-444}, moves the page's
     * own first card number into the record identifier at {@code :445-446}, performs
     * {@code 9000-READ-FORWARD} -- the FORWARD paragraph -- at {@code :449-450}, and sends the map at
     * {@code :451-452}; {@code :901-903} adds the refusal sentence. The rows in front of the operator
     * never change, so an exhausted envelope is the one answer that key press cannot produce.
     *
     * <p>Assumptions: the assertion is on the PUBLISHED identities rather than on the envelope's cursor
     * text, because every seal carries its own nonce and two seals of one key never render identically.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a backward step from the opening page returns that page rather than an empty one")
    void aBackwardStepFromTheOpeningPageReturnsThatPage() {

        CardRepository cards = keysetStore(this.corpus);
        CardListService service = serviceOver(cards);

        PageResponse<CardSummary> opening = service.list(null, null, false, SUBJECT);
        PageResponse<CardSummary> steppedBack = service.list(null, opening.firstKey(), true, SUBJECT);

        assertThat(steppedBack.items())
                .as("the rows the caller already held are still the rows it holds")
                .isNotEmpty();
        assertThat(publishedIdentities(steppedBack))
                .isEqualTo(publishedIdentities(opening));
        assertThat(steppedBack.hasNext())
                .as("and the page ahead of the opening page is still reported as reachable")
                .isEqualTo(opening.hasNext());
        assertThat(steppedBack.firstKey()).isNotNull();
        assertThat(steppedBack.lastKey()).isNotNull();

        assertThat(CardListService.pagingRefusal(opening, true, true))
                .as("the refusal is derivable from the page in hand, before the request is issued")
                .contains(CardListService.MESSAGE_NO_PREVIOUS_PAGES);
        assertThat(CardListService.backwardAvailable(opening, true)).isFalse();
    }

    /**
     * Asserts that the account-narrowing refusal is verbatim, unguarded and raised before any read.
     *
     * <p>Assumptions: transcribed from {@code 2210-EDIT-ACCOUNT.} at {@code app/cbl/COCRDLIC.cbl:1003}.
     * The gate that refuses is at {@code :1017}, it marks the field not acceptable at {@code :1019}, and
     * it moves the literal at {@code :1022} into the message field across {@code :1021-1023}. That move
     * has NO precondition of any kind, which is the asymmetry this case owns: compare the card gate,
     * whose own move at {@code :1057-1059} sits inside the first-error-wins guard at {@code :1056}.
     * Because the account move is unguarded, both values outside the field's domain must earn the
     * sentence, and both are exercised here rather than one standing in for the other.
     *
     * <p>Assumptions: the literal has no space after its comma and reads {@code A} rather than
     * {@code AN} before the digit count. Both look like slips and neither is: they are how the reference
     * authors the value, and Rule T8 of the migration carries user-visible text across character for
     * character, so normalising either would be a change to something whose only purpose is to be
     * reproduced exactly.
     *
     * <p>Assumptions: the store is asserted untouched, which states the ORDER rather than merely the
     * outcome. The reference edits its narrowings in its input phase and only browses afterwards, so a
     * refusal that had already read rows would be a different program even though it returned the same
     * sentence.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the account-narrowing refusal is verbatim, unguarded, and precedes any read")
    void theAccountNarrowingRefusalIsVerbatimAndUnguarded() {

        assertThat(CardListService.MESSAGE_ACCOUNT_FILTER_INVALID)
                .isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");

        CardRepository cards = keysetStore(this.corpus);
        CardListService service = serviceOver(cards);

        assertThatThrownBy(() -> service.list(TOO_WIDE_FOR_ELEVEN_DIGITS, null, false, SUBJECT))
                .isInstanceOfSatisfying(ClientInputException.class, refusal -> {
                    assertThat(refusal.getMessage())
                            .isEqualTo(CardListService.MESSAGE_ACCOUNT_FILTER_INVALID);
                    assertThat(refusal.field())
                            .isEqualTo(CardListService.FIELD_ACCOUNT_FILTER);
                    assertThat(refusal.code()).isEqualTo(ApiError.CODE_VALIDATION);
                    assertThat(refusal.state())
                            .as("the reference marks the field not acceptable at line 1019")
                            .isEqualTo(FieldValidationFlag.NOT_OK);
                });

        assertThatThrownBy(() -> service.list(NEGATIVE_NARROWING, null, false, SUBJECT))
                .isInstanceOf(ClientInputException.class)
                .hasMessage(CardListService.MESSAGE_ACCOUNT_FILTER_INVALID);

        verifyNoInteractions(cards);
    }

    /**
     * Asserts that the card-narrowing refusal is carried verbatim and that this route raises it nowhere.
     *
     * <p>Assumptions: transcribed from {@code 2220-EDIT-CARD.} at {@code app/cbl/COCRDLIC.cbl:1036},
     * whose refusal marks the field not acceptable at {@code :1054} and then moves the literal at
     * {@code :1058} across {@code :1057-1059}, closing at {@code :1060}. That move is wrapped in the
     * guard at {@code :1056}, which emits only while the message field is still clear -- so in the
     * reference an unacceptable account narrowing SUPPRESSES this sentence, while the account sentence
     * is never suppressed by anything. This is the second half of that asymmetry and is a separate case
     * from the first deliberately: one sentence is raised unconditionally and the other conditionally,
     * and a single case asserting only the outcome would leave the condition unstated.
     *
     * <p>Assumptions: this route publishes no card-number narrowing, so the guarded sentence has no
     * branch to reach. The value is nevertheless carried across character for character under Rule T8 --
     * including the same missing space after its comma -- so the traceability matrix can account for it.
     * What is asserted is that no narrowing state produces it: absent, zero, acceptable, too wide and
     * negative are all exercised, which is falsifiable in the way that matters, because wiring the card
     * sentence onto the account gate would fail here.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the card-narrowing refusal is verbatim and no narrowing state on this route raises it")
    void theCardNarrowingRefusalIsVerbatimAndRaisedNowhere() {

        assertThat(CardListService.MESSAGE_CARD_FILTER_INVALID)
                .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        assertThat(CardListService.MESSAGE_CARD_FILTER_INVALID)
                .as("the two narrowing refusals are different reference sentences")
                .isNotEqualTo(CardListService.MESSAGE_ACCOUNT_FILTER_INVALID);

        CardListService service = serviceOver(keysetStore(this.corpus));
        Long acceptable = this.corpus.getFirst().getAccountId();

        for (Long narrowing : List.of(0L, acceptable, TOO_WIDE_FOR_ELEVEN_DIGITS,
                NEGATIVE_NARROWING)) {
            assertThat(refusalTextOf(service, narrowing))
                    .as("no narrowing state may produce the guarded card sentence")
                    .isNotEqualTo(CardListService.MESSAGE_CARD_FILTER_INVALID);
        }
        assertThat(refusalTextOf(service, null))
                .as("an absent narrowing produces no refusal at all, guarded or otherwise")
                .isNull();
    }

    /**
     * Asserts that a browse matching nothing carries no rows, no boundaries and the reference notice.
     *
     * <p>Assumptions: the sentence a browse that matched nothing reports is the one THIS program
     * declares, at {@code app/cbl/COCRDLIC.cbl:121-122}, as the value of the condition the read loop
     * sets at {@code :1244} when it reaches the end of the ordered set having placed no row on the
     * opening page, at {@code :1241-1242}. Its field is {@code WS-ERROR-MSG PIC X(75)} at {@code :117}.
     * The similar shorter sentence on the line above that set, at {@code :1243}, is COMMENTED OUT in the
     * reference and is therefore carried nowhere.
     *
     * <p>Assumptions: the sentence reading {@code Did not find cards for this search condition} is a
     * DIFFERENT route's and is deliberately not asserted here. It is declared at
     * {@code app/cbl/COCRDSLC.cbl:154} and {@code app/cbl/COCRDUPC.cbl:204}, which are the single-card
     * detail and update programs, and it appears nowhere in {@code app/cbl/COCRDLIC.cbl}. Attaching it
     * to this browse would report one route's text on another's, which Rule T8 of the migration -- text
     * carried verbatim from its originating program -- exists to prevent. The detail route is covered by
     * {@code CardControllerTest} in the sibling {@code com.carddemo.card.api} test package.
     *
     * <p>Assumptions: both boundary cursors must be absent rather than blank. A page that named a
     * boundary while carrying no row would invite a caller to page away from a position no row occupies,
     * which is why the envelope refuses that combination outright and why their absence is asserted here
     * rather than assumed.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a browse matching nothing carries no rows, no boundary cursors and the notice")
    void aBrowseMatchingNothingCarriesNoRowsAndNoBoundaries() {

        assertThat(this.emptyCorpus)
                .as("the zero-byte fixture stands for an ordered set with nothing in it")
                .isEmpty();

        CardRepository cards = keysetStore(this.emptyCorpus);
        PageResponse<CardSummary> page = serviceOver(cards).list(null, null, false, SUBJECT);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.firstKey()).isNull();
        assertThat(page.lastKey()).isNull();

        assertThat(CardListService.pageMessage(page))
                .isEqualTo("NO RECORDS FOUND FOR THIS SEARCH CONDITION.");
        assertThat(CardListService.pageMessage(page))
                .isEqualTo(CardListService.MESSAGE_NO_RECORDS_FOUND);
        assertThat(CardListService.backwardAvailable(page, true))
                .as("a page naming no leading boundary can be paged away from in neither direction")
                .isFalse();
        assertThat(CardListService.backwardAvailable(page, false))
                .as("and the opening-page argument cannot make a boundary-less page addressable")
                .isFalse();
        assertThat(CardListService.pagingRefusal(page, true, true))
                .contains(CardListService.MESSAGE_NO_PREVIOUS_PAGES);
        assertThat(CardListService.pagingRefusal(page, false, true))
                .contains(CardListService.MESSAGE_NO_MORE_PAGES);
    }

    /**
     * Asserts that an absent narrowing is accepted and narrows nothing, on both of its absent forms.
     *
     * <p>Assumptions: on THIS route both narrowings are optional, and the reference says so by the value
     * it pre-sets each gate to rather than by a comment. {@code 2210-EDIT-ACCOUNT.} at
     * {@code app/cbl/COCRDLIC.cbl:1003} opens by setting its flag blank at {@code :1004}, and
     * {@code 2220-EDIT-CARD.} at {@code :1036} does the same at {@code :1039}, so a field left empty is
     * accepted and simply narrows nothing. The single-card programs pre-set the opposite value on the
     * same two gates -- not acceptable, at {@code app/cbl/COCRDSLC.cbl:648} and {@code :688} -- which is
     * what makes the optionality here deliberate rather than lax. That contrasting route is covered by
     * {@code CardControllerTest} in the sibling {@code com.carddemo.card.api} test package and is not
     * exercised from this class.
     *
     * <p>Assumptions: zero is an absent narrowing and not a narrowing on account zero. The reference
     * treats it that way explicitly, testing the numeric overlay against zeros at
     * {@code app/cbl/COCRDLIC.cbl:1010} alongside low values and spaces at {@code :1008-1009}, all three
     * in the same condition. A target that accepted zero as a value would read as more precise and would
     * answer a browse the reference answers differently.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("an absent narrowing is accepted in both its forms and reaches the query as absent")
    void anAbsentNarrowingIsAcceptedAndNarrowsNothing() {

        CardRepository cards = keysetStore(this.corpus);
        CardListService service = serviceOver(cards);

        PageResponse<CardSummary> withoutNarrowing = service.list(null, null, false, SUBJECT);
        PageResponse<CardSummary> withZero = service.list(0L, null, false, SUBJECT);

        assertThat(publishedIdentities(withZero))
                .as("zero narrows nothing, so it must answer exactly as an omitted narrowing does")
                .isEqualTo(publishedIdentities(withoutNarrowing));
        verify(cards, times(2))
                .findForwardFromCursor(isNull(), isNull(), isNull(), any(Limit.class));
    }

    /**
     * Asserts that a narrowing reaches the query and that the further-page flag is narrowed with it.
     *
     * <p>Assumptions: transcribed from {@code 9500-FILTER-RECORDS.} at
     * {@code app/cbl/COCRDLIC.cbl:1382}, which admits a row by default at {@code :1383} and then
     * excludes it unless the account matches, at {@code :1385-1390}. In the target that selection is a
     * predicate of the query rather than a step after the read, which is why the bound is asserted
     * unchanged at the window plus one: the narrowing changes WHICH rows the bound applies to, not how
     * many are asked for.
     *
     * <p>Trade-offs: the further-page flag consequently becomes accurate here in a way the reference's
     * own is not, and that is a documented behavioural divergence under Rule T9 of the migration rather
     * than a correction of anything. The reference performs its filter paragraph after each ordinary
     * read, at {@code :1159-1160} going forward and {@code :1335-1336} going backward, and NOT over the
     * probe read at {@code :1197} -- so under an active narrowing its indicator can report a further page
     * when no further matching row exists. Here the surplus row passes through the same predicate and the
     * flag cannot over-report. The reference does the first, this does the second, the divergence is
     * registered in {@code docs/architecture/cobol-to-service-traceability.md}, and neither is described
     * as a fault of the other.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a narrowing reaches the query and narrows the row beyond the window with it")
    void aNarrowingReachesTheQueryAndNarrowsTheSurplusRow() {

        CardRepository cards = keysetStore(this.corpus);
        Long narrowing = this.corpus.getFirst().getAccountId();

        PageResponse<CardSummary> page = serviceOver(cards).list(narrowing, null, false, SUBJECT);

        ArgumentCaptor<Limit> bounds = ArgumentCaptor.forClass(Limit.class);
        verify(cards).findForwardFromCursor(isNull(), eq(narrowing), isNull(), bounds.capture());

        assertThat(bounds.getValue().max())
                .as("a narrowed read still asks for one row beyond the window")
                .isEqualTo(CardListService.PAGE_SIZE + 1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext())
                .as("the row beyond the window was narrowed too, so no further page is reported")
                .isFalse();
        assertThat(CardListService.pageMessage(page))
                .isEqualTo(CardListService.MESSAGE_NO_MORE_RECORDS);
    }

    /**
     * Asserts nothing recoverable from a published cursor is any part of a card number.
     *
     * <p>Assumptions: the assertion is made from OUTSIDE the token, by base64url-decoding every segment of
     * both boundary cursors and searching the recovered bytes. That is the position an attacker occupies, so
     * it is the only position from which the claim is worth making: a test that asked the token type whether
     * it had encrypted its payload would be asking the implementation to confirm its own intention.</p>
     *
     * <p>Assumptions: three renderings of the number are searched for, not one. The whole sixteen digits is
     * the obvious one; the LAST FOUR are searched because they are what the masked rendering already
     * discloses and their appearance in a token would let a holder confirm a guess; and the LEADING SIX are
     * searched because they are the issuer identification number, which narrows an enumeration. A test that
     * looked only for the whole number would pass against a token that leaked half of it.</p>
     */
    @Test
    @DisplayName("a decoded cursor reveals no part of any card number")
    void aDecodedCursorRevealsNoPartOfAnyCardNumber() {

        PageResponse<CardSummary> page = serviceOver(keysetStore(this.corpus)).list(null, null, false,
                SUBJECT);

        String leadingRow = this.corpus.getFirst().getCardNum();
        String trailingRow = this.corpus.get(CardListService.PAGE_SIZE - 1).getCardNum();

        for (String token : List.of(page.firstKey(), page.lastKey())) {
            StringBuilder recovered = new StringBuilder();
            for (String segment : token.split("\\.")) {
                recovered.append(new String(
                        java.util.Base64.getUrlDecoder().decode(segment), StandardCharsets.ISO_8859_1));
            }
            String material = recovered.toString();
            for (String number : List.of(leadingRow, trailingRow)) {
                assertThat(material)
                        .as("the whole card number must not be recoverable from a published cursor")
                        .doesNotContain(number)
                        .as("nor its last four digits, which the masked rendering already discloses")
                        .doesNotContain(number.substring(number.length() - 4))
                        .as("nor its leading six, which are the issuer identification number")
                        .doesNotContain(number.substring(0, 6));
            }
        }
    }

    /**
     * Asserts a cursor issued to one caller is refused for another.
     *
     * <p>Assumptions: the two requests are identical in every respect except the subject, so a refusal can
     * only come from the subject part of the binding. Without it a position issued to one caller was
     * honoured for any other, and the row it named was returned as though that caller had reached it.</p>
     */
    @Test
    @DisplayName("a cursor issued to one caller is refused for another")
    void aCursorIssuedToOneCallerIsRefusedForAnother() {

        CardRepository cards = keysetStore(this.corpus);
        PageResponse<CardSummary> mine = serviceOver(cards).list(null, null, false, SUBJECT);

        assertThatThrownBy(() -> serviceOver(cards).list(null, mine.lastKey(), false, OTHER_SUBJECT))
                .isInstanceOf(CursorToken.InvalidCursorException.class);
    }

    /**
     * Asserts a cursor issued for an unnarrowed browse is refused when presented with a narrowing.
     *
     * <p>Assumptions: the harm this prevents is a silent MISPOSITION rather than a disclosure. A cursor
     * names a card number, and the same card number is a valid position within a differently narrowed set,
     * so honouring it would answer from a place the caller was never shown -- and would do so without any
     * error, which is what makes it worth a case of its own.</p>
     */
    @Test
    @DisplayName("a cursor issued for an unnarrowed browse is refused when a narrowing is supplied")
    void aCursorIssuedForOneNarrowingIsRefusedForAnother() {

        CardRepository cards = keysetStore(this.corpus);
        PageResponse<CardSummary> unnarrowed = serviceOver(cards).list(null, null, false, SUBJECT);
        Long narrowing = this.corpus.getFirst().getAccountId();

        assertThatThrownBy(() -> serviceOver(cards).list(narrowing, unnarrowed.lastKey(), false, SUBJECT))
                .isInstanceOf(CursorToken.InvalidCursorException.class);
    }

    /**
     * Asserts the trailing cursor cannot be replayed as a backward position, nor the leading one forward.
     *
     * <p>Assumptions: the two boundary cursors of one page are sealed under different bindings precisely so
     * that this swap is refused. Presenting the trailing cursor backward would name the row the caller had
     * just been shown as the row to read back FROM, which would return a page overlapping the one in hand;
     * presenting the leading cursor forward would re-return the same page. Neither is distinguishable from a
     * legitimate step without the direction in the binding.</p>
     */
    @Test
    @DisplayName("neither boundary cursor can be replayed in the other direction")
    void neitherBoundaryCursorCanBeReplayedInTheOtherDirection() {

        CardRepository cards = keysetStore(this.corpus);
        PageResponse<CardSummary> second = serviceOver(cards)
                .list(null, serviceOver(cards).list(null, null, false, SUBJECT).lastKey(), false, SUBJECT);

        assertThatThrownBy(() -> serviceOver(cards).list(null, second.lastKey(), true, SUBJECT))
                .as("the forward boundary must not open as a backward position")
                .isInstanceOf(CursorToken.InvalidCursorException.class);
        assertThatThrownBy(() -> serviceOver(cards).list(null, second.firstKey(), false, SUBJECT))
                .as("nor the backward boundary as a forward one")
                .isInstanceOf(CursorToken.InvalidCursorException.class);
    }

    /**
     * Asserts the opening page publishes the position a backward step would be issued from, and that the
     * refusal of that step is not this service's to make.
     *
     * <p>Assumptions: the reference refuses a backward step from its first page from its own PAGE ORDINAL
     * and not from anything it reads -- {@code app/cbl/COCRDLIC.cbl:902-903} raises the sentence on the
     * condition declared at {@code :237-238}, and {@code :492} and {@code :508} are where the ordinal
     * moves. The ordinal lived in the communication area the terminal carried between turns, so its
     * migrated home is the SPA's navigation state; what this service owes the caller is the position,
     * which every page carrying rows names. This test therefore pins the position and the sentence
     * separately: the page publishes the leading boundary, and the refusal wording remains available for
     * the client that holds the ordinal to render.</p>
     */
    @Test
    @DisplayName("the opening page publishes the position a backward step is issued from")
    void theOpeningPagePublishesItsLeadingBoundary() {

        PageResponse<CardSummary> opening = serviceOver(keysetStore(this.corpus))
                .list(null, null, false, SUBJECT);

        assertThat(opening.firstKey())
                .as("the position a backward request is issued from is published on every page with rows")
                .isNotNull();
        assertThat(CardListService.backwardAvailable(opening, false))
                .as("a backward step is ADDRESSABLE from the opening page, which is what the leading"
                        + " boundary reports, and what a caller past page one composes with it")
                .isTrue();
        assertThat(CardListService.backwardAvailable(opening, true))
                .as("but it is NOT AVAILABLE to a caller standing on the opening page, which is the"
                        + " condition app/cbl/COCRDLIC.cbl:238 declares over its own ordinal")
                .isFalse();
        assertThat(CardListService.MESSAGE_NO_PREVIOUS_PAGES)
                .as("the reference sentence a client renders when its ordinal says it is on page one")
                .isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
    }

    /**
     * Asserts a backward page reports a page AHEAD of it, so the caller can step forward again.
     *
     * <p>Assumptions: this is the defect the further-page indicator carried. A following page necessarily
     * exists on a backward read -- the caller reached this page by stepping back from one, and that page is
     * still there -- but the indicator was derived from the backward read's own surplus row, which lies in
     * the opposite direction. When the backward read exhausted its end the envelope therefore reported
     * nothing ahead, {@code pagingRefusal} turned that into {@code MESSAGE_NO_MORE_PAGES}, and the step the
     * caller had just come from was withheld.</p>
     */
    @Test
    @DisplayName("a backward page reports a following page so the caller can return to it")
    void aBackwardPageReportsAFollowingPage() {

        CardRepository cards = keysetStore(this.corpus);
        PageResponse<CardSummary> opening = serviceOver(cards).list(null, null, false, SUBJECT);
        PageResponse<CardSummary> second = serviceOver(cards).list(null, opening.lastKey(), false, SUBJECT);
        PageResponse<CardSummary> backToOpening =
                serviceOver(cards).list(null, second.firstKey(), true, SUBJECT);

        assertThat(backToOpening.items())
                .as("the backward step returns the page the caller came from")
                .isEqualTo(opening.items());
        assertThat(backToOpening.hasNext())
                .as("and it must advertise the page it stepped back from")
                .isTrue();
        assertThat(CardListService.pagingRefusal(backToOpening, false, true)).isEmpty();

        // WHY : Assumptions: the two envelopes name the SAME row at their leading boundary but cannot
        //       carry the same token text, because every seal mints a fresh nonce -- so the assertion has
        //       to open both cursors and compare the keys inside. Comparing the token strings would
        //       compare two ciphertexts of one plaintext and fail on every run, which is a property of the
        //       sealer and not of the paging behaviour under test here.
        assertThat(this.sealer.open(backwardBinding(), backToOpening.firstKey()))
                .as("while still publishing the position a further backward step would be issued from")
                .isEqualTo(this.sealer.open(backwardBinding(), opening.firstKey()));
    }

    /**
     * Asserts that a backward request carrying no position is answered by the forward query.
     *
     * <p>Assumptions: this is the target's statement of the reference's opening-page condition, declared
     * at {@code app/cbl/COCRDLIC.cbl:238} as the page ordinal holding one. A backward step is expressible
     * only from a position, and a request that carries none is on the opening page by definition, so the
     * only read that can answer it is the forward one from the start of the ordered set. Asserting the
     * backward query was NOT reached is the substance here: a backward query has no null-tolerant
     * position predicate, so reaching it without a position would be a different outcome entirely rather
     * than a merely redundant call.
     *
     * <p>Refactoring Rationale: the envelope publishes the backward POSITION and no backward availability
     * answer, so what is asserted below is that the opening page and a positionless backward request are
     * answered identically and that both name their leading boundary. Whether a row waits at that
     * boundary is the caller's question, answered from the page ordinal the reference keeps at
     * {@code :237-238} and tests at {@code :902} -- a question no read of the card file can settle and
     * therefore not one this envelope answers.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a backward request carrying no position is answered by the forward query")
    void aBackwardRequestWithoutAPositionIsAnsweredForward() {

        CardRepository cards = keysetStore(this.corpus);
        CardListService service = serviceOver(cards);

        PageResponse<CardSummary> backwardWithoutPosition = service.list(null, null, true, SUBJECT);
        PageResponse<CardSummary> opening = service.list(null, null, false, SUBJECT);

        verify(cards, never()).findBackwardFromCursor(any(), any(), any(), any());
        verify(cards, times(2))
                .findForwardFromCursor(isNull(), isNull(), isNull(), any(Limit.class));

        assertThat(publishedIdentities(backwardWithoutPosition))
                .isEqualTo(publishedIdentities(opening));
        assertThat(opening.firstKey())
                .as("the position a backward request is issued from is published on the opening page")
                .isNotNull();

        // WHY : Assumptions: the comparison is made on the OPENED keys, not the token text, because each
        //       seal carries its own nonce and two seals of one key never render identically. Opening both
        //       under the backward binding also proves the positionless backward answer was sealed for the
        //       backward direction, which a string comparison of two ciphertexts could not establish.
        assertThat(this.sealer.open(backwardBinding(), backwardWithoutPosition.firstKey()))
                .as("a backward request with no position is answered as the opening page, boundary"
                        + " included")
                .isEqualTo(this.sealer.open(backwardBinding(), opening.firstKey()));
    }

    /**
     * Asserts that the boundary cursor names the last row returned and never the row beyond the window.
     *
     * <p>Refactoring Rationale: the reference stores a different key from the one published here, and the
     * difference is the point of this case. Its probe read at {@code app/cbl/COCRDLIC.cbl:1197}
     * overwrites the stored keys at {@code :1212-1214}, so the key it carries across a screen turn is the
     * FIRST UNDISPLAYED row, and it then repositions with a greater-than-or-equal browse. The target
     * publishes the LAST RETURNED row and resumes strictly past it. Both land on identical page
     * boundaries -- resuming strictly after the seventh row and resuming at-or-after the eighth both
     * begin at the eighth -- but only one of the two is safe to publish: publishing the probed row's key
     * would advance the position one row too far for a strictly-greater resume and would drop that row
     * from the following page. That is why the divergence exists and why it is asserted rather than
     * assumed.
     *
     * <p>Assumptions: the assertion is expressed by opening the published cursor with the same signer
     * that sealed it, which is what makes it a statement about the value a caller can hand back rather
     * than about an internal field.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("the boundary cursor names the last row returned, not the row beyond the window")
    void theBoundaryCursorNamesTheLastRowReturned() {

        CardRepository cards = keysetStore(this.corpus);
        PageResponse<CardSummary> opening = serviceOver(cards).list(null, null, false, SUBJECT);

        String lastReturned = this.corpus.get(CardListService.PAGE_SIZE - 1).getCardNum();
        String beyondTheWindow = this.corpus.get(CardListService.PAGE_SIZE).getCardNum();

        assertThat(this.sealer.open(forwardBinding(), opening.lastKey()))
                .isEqualTo(lastReturned);
        assertThat(this.sealer.open(forwardBinding(), opening.lastKey()))
                .as("publishing the probed row's key would drop that row from the following page")
                .isNotEqualTo(beyondTheWindow);
        assertThat(this.sealer.open(backwardBinding(), opening.firstKey()))
                .isEqualTo(this.corpus.getFirst().getCardNum());
    }

    /**
     * Asserts that a page carrying one row names that row at both of its boundaries.
     *
     * <p>Assumptions: a page of one is the degenerate case where the two boundary cursors coincide, and
     * the envelope requires both to be present on any page that carries a row -- a page a caller cannot
     * name the ends of is a page it cannot page away from. Asserting that both open to the same key is
     * what shows the two are derived from the rows shown rather than from the read's own extremes.
     *
     * <p>Assumptions: the positive-control fixture holds exactly one record, registered in
     * {@code services/card-service/src/test/resources/fixtures/README.md} as persistable and rule-valid,
     * so a page built from it exercises the short-page path without also exercising a refusal.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a page carrying one row names that row at both boundaries and reports no further page")
    void aPageCarryingOneRowNamesThatRowAtBothBoundaries() {

        assertThat(this.singleRow).hasSize(1);

        PageResponse<CardSummary> page = serviceOver(keysetStore(this.singleRow))
                .list(null, null, false, SUBJECT);
        String onlyKey = this.singleRow.getFirst().getCardNum();

        assertThat(page.items()).hasSize(1);
        assertThat(page.hasNext()).isFalse();
        assertThat(this.sealer.open(backwardBinding(), page.firstKey()))
                .isEqualTo(onlyKey);
        assertThat(this.sealer.open(forwardBinding(), page.lastKey()))
                .isEqualTo(onlyKey);
        assertThat(CardListService.pageMessage(page))
                .isEqualTo(CardListService.MESSAGE_NO_MORE_RECORDS);
    }

    /**
     * Builds the service under test over a substituted store, with a real mapper and cursor signer.
     *
     * @param cards the substituted store the service reads through
     * @return the service under test, never {@code null}
     */
    private CardListService serviceOver(CardRepository cards) {
        return new CardListService(cards, this.mapper, this.sealer);
    }

    /**
     * Returns the binding the TRAILING boundary cursor of an unnarrowed page is sealed under.
     *
     * <p>Assumptions: the two boundaries of one page are sealed under different bindings, so a test that
     * opens one must name the matching direction. Opening the trailing cursor under the backward binding
     * fails authentication, which is the property the isolation cases assert deliberately and which every
     * other case must therefore avoid doing by accident.</p>
     *
     * @return the forward binding for this class's subject and no account narrowing, never {@code null}
     */
    private static String forwardBinding() {
        return CardListService.listCursorBinding(null, SUBJECT, false);
    }

    /**
     * Returns the binding the LEADING boundary cursor of an unnarrowed page is sealed under.
     *
     * @return the backward binding for this class's subject and no account narrowing, never {@code null}
     */
    private static String backwardBinding() {
        return CardListService.listCursorBinding(null, SUBJECT, true);
    }

    /**
     * Substitutes the store with one that answers both directions by key over the supplied ordered set.
     *
     * <p>Assumptions: the answers read the supplied list AT CALL TIME rather than copying it, which is
     * what lets a case add a row between two requests and have the second request see it. That is the
     * only way to express a concurrent arrival against a substituted store, and it is stated here because
     * a reader would otherwise reasonably expect a snapshot.
     *
     * @param ordered the ordered set both directions read, ascending by card number
     * @return a store answering the two keyset queries, never {@code null}
     */
    private static CardRepository keysetStore(List<Card> ordered) {
        CardRepository cards = mock(CardRepository.class);

        // Assumptions: the third query argument is matched as null rather than as merely nullable,
        // unlike the two before it. The browse this class exercises publishes no card-number
        // narrowing, so a non-null value reaching that argument would mean the service had invented
        // one; matching it strictly turns that into an unstubbed call rather than a silent pass.
        when(cards.findForwardFromCursor(nullable(String.class), nullable(Long.class), isNull(),
                any(Limit.class)))
                .thenAnswer(call -> ascendingAfter(ordered, call.getArgument(0, String.class),
                        call.getArgument(1, Long.class), call.getArgument(3, Limit.class).max()));
        when(cards.findBackwardFromCursor(nullable(String.class), nullable(Long.class), isNull(),
                any(Limit.class)))
                .thenAnswer(call -> descendingBefore(ordered, call.getArgument(0, String.class),
                        call.getArgument(1, Long.class), call.getArgument(3, Limit.class).max()));
        return cards;
    }

    /**
     * Answers a forward keyset read: rows strictly after a key, ascending, bounded.
     *
     * <p>Assumptions: this reproduces the predicate the query declares -- strictly greater than the
     * position, the narrowing applied when one is present, ascending by key, bounded by the caller's
     * limit -- and it is deliberately strict rather than at-or-after, because an at-or-after resume would
     * return the row the previous page ended on and show it twice.
     *
     * @param ordered the ordered set to read, ascending by card number
     * @param afterCardNum the position to resume strictly after, or {@code null} to start at the
     *     beginning
     * @param accountId the narrowing to apply, or {@code null} to narrow nothing
     * @param bound the greatest number of rows to answer with
     * @return the answered rows, ascending by card number; never {@code null}
     */
    private static List<Card> ascendingAfter(
            List<Card> ordered, String afterCardNum, Long accountId, int bound) {
        List<Card> answered = new ArrayList<>(bound);
        for (Card row : ordered) {
            if (matches(row, afterCardNum, accountId, true)) {
                answered.add(row);
                if (answered.size() == bound) {
                    break;
                }
            }
        }
        return answered;
    }

    /**
     * Answers a backward keyset read: rows strictly before a key, descending, bounded.
     *
     * @param ordered the ordered set to read, ascending by card number
     * @param beforeCardNum the position to read strictly before; must not be {@code null}
     * @param accountId the narrowing to apply, or {@code null} to narrow nothing
     * @param bound the greatest number of rows to answer with
     * @return the answered rows, descending by card number; never {@code null}
     * @throws IllegalArgumentException if no position is supplied, which the query cannot express and
     *     the service is asserted never to ask for
     */
    private static List<Card> descendingBefore(
            List<Card> ordered, String beforeCardNum, Long accountId, int bound) {
        if (beforeCardNum == null) {
            throw new IllegalArgumentException("a backward read states no position; the query's own"
                    + " predicate has no null-tolerant form, so reaching it this way is a defect rather"
                    + " than an empty result");
        }
        List<Card> answered = new ArrayList<>(bound);
        for (int index = ordered.size() - 1; index >= 0 && answered.size() < bound; index--) {
            Card row = ordered.get(index);
            if (matches(row, beforeCardNum, accountId, false)) {
                answered.add(row);
            }
        }
        return answered;
    }

    /**
     * Reports whether one row satisfies a keyset position and an optional narrowing.
     *
     * @param row the row being considered
     * @param position the key the read resumes from, or {@code null} to place no bound on the key
     * @param accountId the narrowing to apply, or {@code null} to narrow nothing
     * @param ascending whether the read wants keys after the position rather than before it
     * @return {@code true} when the row belongs in the answer
     */
    private static boolean matches(Card row, String position, Long accountId, boolean ascending) {
        if (accountId != null && !accountId.equals(row.getAccountId())) {
            return false;
        }
        if (position == null) {
            return true;
        }
        int order = row.getCardNum().compareTo(position);
        return ascending ? order > 0 : order < 0;
    }

    /**
     * Reads one fixture from the test classpath and decodes it into rows.
     *
     * <p>Assumptions: the byte positions come from the table at line 133 onward of
     * {@code services/card-service/src/test/resources/fixtures/README.md}, which owns them, and the
     * record length from {@code app/cpy/CVACT02Y.cpy:4}. They are consumed here and not re-derived. The
     * fixtures are the ASCII seed tree, so the bytes are read as ASCII and no code page is involved --
     * unlike the EBCDIC tree, which the migration decodes elsewhere and per field.
     *
     * <p>Assumptions: the card verification value occupies positions 28 to 30 and is deliberately NOT
     * read. Nothing in this class needs it, a browse never publishes it, and a value never loaded cannot
     * reach a log or an assertion message by accident.
     *
     * <p>Assumptions: the length guard is not defensive padding. A fixture edited to carry an in-file
     * comment would shift every position after it and the rows would decode into plausible nonsense, so
     * the guard converts that into an immediate failure naming the file.
     *
     * @param resource the classpath name of the fixture, below the {@code fixtures/} prefix
     * @return the fixture's rows in file order, which the corpus authors ascending by card number
     * @throws IOException if the fixture cannot be read
     * @throws IllegalStateException if the fixture is absent from the classpath, or does not measure a
     *     whole number of records
     */
    private static List<Card> cardsFrom(String resource) throws IOException {
        byte[] raw;
        try (InputStream stream =
                CardListServiceTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("fixture absent from the test classpath: " + resource);
            }
            raw = stream.readAllBytes();
        }
        if (raw.length % LINE_LENGTH != 0) {
            throw new IllegalStateException("fixture " + resource + " measures " + raw.length
                    + " bytes, which is not a whole multiple of the " + LINE_LENGTH
                    + " a record and its terminator occupy");
        }
        String text = new String(raw, StandardCharsets.US_ASCII);
        List<Card> rows = new ArrayList<>(raw.length / LINE_LENGTH);
        for (int start = 0; start < text.length(); start += LINE_LENGTH) {

            // Assumptions: one byte is dropped from the end of each line because the stride covers
            // the record AND its terminator, whereas the byte positions are stated over the record
            // alone. Keeping it would carry a line terminator into the trailing field.
            String record = text.substring(start, start + LINE_LENGTH - 1);
            // Assumptions: the card verification value is handed over as absent rather than decoded
            // from positions 28 to 30. Nothing here needs it and a browse never publishes it, so a
            // value that is never loaded cannot reach a log or an assertion message by accident.
            rows.add(new Card(
                    record.substring(CARD_NUM_FROM, CARD_NUM_TO),
                    Long.parseLong(record.substring(CARD_NUM_TO, ACCOUNT_ID_TO)),
                    null,
                    record.substring(EMBOSSED_NAME_FROM, EMBOSSED_NAME_TO).stripTrailing(),
                    LocalDate.parse(record.substring(EXPIRATION_DATE_FROM, EXPIRATION_DATE_TO)),
                    record.substring(EXPIRATION_DATE_TO, ACTIVE_STATUS_TO)));
        }
        return rows;
    }

    /**
     * Builds a row carrying the supplied key and borrowing every other value from a neighbour.
     *
     * <p>Assumptions: only the key participates in ordering and in identity, so the remaining values are
     * borrowed rather than invented. Borrowing keeps the arriving row indistinguishable from a corpus row
     * in every respect the browse can observe, which is what makes it a plausible arrival rather than a
     * specially shaped one the service might treat differently.
     *
     * @param cardNumber the key the built row is ordered and identified by
     * @param neighbour the row whose remaining values are borrowed
     * @return the built row, never {@code null}
     */
    private static Card cardOf(String cardNumber, Card neighbour) {
        return new Card(cardNumber, neighbour.getAccountId(), null, neighbour.getEmbossedName(),
                neighbour.getExpirationDate(), neighbour.getActiveStatus());
    }

    /**
     * Asserts that a cursor issued to one caller is refused when a different caller presents it.
     *
     * <p>Purpose: this is the property the cursor's subject binding exists for. A published cursor is a
     * value a client holds, so nothing stops it being copied out of one operator's session and sent by
     * another; what stops the SECOND operator being answered is that the token was sealed against the
     * first one's name and does not open under the second's.
     *
     * <p>Refactoring Rationale: before the binding was composed, this browse sealed every cursor under a
     * bare query-name literal. Both requests below would then have succeeded identically, because the
     * only thing the token was bound to was the listing itself -- which every caller of this operation
     * shares. Nothing in the corpus, the store or the seal key differs between the two requests here;
     * the only difference is the name, so a pass proves the name is what was refused.
     *
     * <p>Assumptions: the SAME sealer instance serves both requests, so the refusal cannot be explained
     * by a key mismatch. The published contract states the refusal directly -- a cursor that "was not
     * issued to this caller" is among the causes of its 400 -- and the raised type is the one the shared
     * advice renders as that status.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("a cursor issued to one caller is refused when another caller presents it")
    void aCursorIsNotTransferableBetweenCallers() {

        CardListService service = serviceOver(keysetStore(this.corpus));
        PageResponse<CardSummary> issued = service.list(null, null, false, SUBJECT);

        assertThat(issued.lastKey())
                .as("the opening page must publish a trailing cursor for this case to mean anything")
                .isNotNull();

        assertThat(service.list(null, issued.lastKey(), false, SUBJECT).items())
                .as("the caller it was issued to must still be able to redeem it")
                .isNotEmpty();

        assertThatThrownBy(() -> service.list(null, issued.lastKey(), false, OTHER_SUBJECT))
                .isInstanceOf(CursorToken.InvalidCursorException.class);
    }

    /**
     * Asserts that each boundary cursor opens only for the step it was issued for.
     *
     * <p>Purpose: the two boundaries of a page are sealed under different direction scopes, so the
     * trailing cursor is redeemable only as a forward step and the leading cursor only as a backward one.
     * Presenting either with the other direction is refused at the seal.
     *
     * <p>Refactoring Rationale: the published contract promised exactly this before it was true, stating
     * that the direction is carried in the seal of each token so that replaying one with the other
     * direction "cannot silently return the wrong page", and naming a cursor "sealed for the other
     * direction" among the causes of its 400. With a bare query-name binding both mismatched requests
     * were answered with a page from the wrong end of the set instead, which is the failure the sentence
     * described and the service did not prevent.
     *
     * <p>Assumptions: both mismatches are asserted rather than one, because the two scopes are
     * independent -- sealing both boundaries under a single scope would refuse one mismatch and admit the
     * other, and a case asserting only one direction would pass against that.
     *
     * <p>Assumptions: the positive controls are asserted alongside the refusals. Without them a
     * regression that refused every cursor in either direction would satisfy the negative assertions
     * completely.
     *
     * <p>It takes no parameter and returns no value.
     */
    @Test
    @DisplayName("each boundary cursor is refused when presented with the opposite direction")
    void eachBoundaryCursorIsBoundToItsOwnDirection() {

        CardListService service = serviceOver(keysetStore(this.corpus));
        PageResponse<CardSummary> opening = service.list(null, null, false, SUBJECT);
        PageResponse<CardSummary> second = service.list(null, opening.lastKey(), false, SUBJECT);

        assertThat(second.firstKey())
                .as("the second page must publish a leading cursor for the backward case to matter")
                .isNotNull();

        assertThat(service.list(null, second.firstKey(), true, SUBJECT).items())
                .as("a leading cursor must still be redeemable as the backward step it was issued for")
                .isNotEmpty();

        assertThatThrownBy(() -> service.list(null, opening.lastKey(), true, SUBJECT))
                .as("a trailing cursor is a forward position and must not open a backward step")
                .isInstanceOf(CursorToken.InvalidCursorException.class);

        assertThatThrownBy(() -> service.list(null, second.firstKey(), false, SUBJECT))
                .as("a leading cursor is a backward position and must not open a forward step")
                .isInstanceOf(CursorToken.InvalidCursorException.class);
    }

    /**
     * Reduces a published page to the stable identity of each row it carries, in order.
     *
     * <p>Assumptions: the sealed selector a published row carries is the identity used, because sealing
     * is deterministic for a given key and value, so the same card yields the same selector on every page
     * it appears on. Alternatives Considered: comparing the masked number instead. Rejected because
     * masking keeps only the last four characters, so two cards agreeing in those four would compare
     * equal and a repeated row could pass unnoticed. Alternatives Considered: comparing raw card numbers.
     * Rejected because it would put those numbers in assertion failure output.
     *
     * @param page the published page to reduce
     * @return one identity per row, in the order the page carries them; never {@code null}
     */
    private List<String> publishedIdentities(PageResponse<CardSummary> page) {
        List<String> identities = new ArrayList<>(page.items().size());
        for (CardSummary row : page.items()) {
            identities.add(row.key());
        }
        return identities;
    }

    /**
     * Reduces stored rows to the identities their published form would carry, in order.
     *
     * @param rows the stored rows to reduce
     * @return one identity per row, in the order supplied; never {@code null}
     */
    private List<String> identitiesOf(List<Card> rows) {
        List<String> identities = new ArrayList<>(rows.size());
        for (Card row : rows) {
            identities.add(identityOf(row));
        }
        return identities;
    }

    /**
     * Reduces one stored row to the identity its published form would carry.
     *
     * @param row the stored row to reduce
     * @return the row's published identity, never {@code null}
     */
    private String identityOf(Card row) {
        return this.mapper.toSummary(row).key();
    }

    /**
     * Reports the refusal a browse earns for one narrowing, or nothing when it earns none.
     *
     * <p>Assumptions: the refusal is returned rather than rethrown so that a case can compare several
     * narrowing states in one pass. Alternatives Considered: a separate assertion per state. Rejected
     * because the property under test is that NO state produces a particular sentence, and stating that
     * as a loop over the states makes the exhaustiveness visible instead of leaving a reader to count
     * cases.
     *
     * @param service the service the browse is requested from
     * @param narrowing the account narrowing to request with, which may be {@code null}
     * @return the refusal sentence the browse earned, or {@code null} when it earned none
     */
    private static String refusalTextOf(CardListService service, Long narrowing) {
        try {
            service.list(narrowing, null, false, SUBJECT);
            return null;
        } catch (ClientInputException refused) {
            return refused.getMessage();
        }
    }
}
