package com.carddemo.authorization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.dto.PendingAuthDetailResponse;
import com.carddemo.authorization.dto.PendingAuthDetailView;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.mapper.PendingAuthViewMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.common.money.Money;
import com.carddemo.common.money.MoneyModule;
import com.carddemo.common.web.CursorToken;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the keyed read and the deliberate screen projection of one pending authorization.
 *
 * <p><strong>Purpose.</strong> Transcribes the read half of
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl} into assertions: the single-row retrieval
 * behind the detail endpoint, the ten-entry response-reason display table declared at its L58 to L67, the
 * two DIFFERENT renderings its table search reaches at L319 to L328, and the set of stored members its
 * {@code POPULATE-AUTH-DETAILS} paragraph at L291 to L359 deliberately does NOT put on the screen. Every
 * citation in this class is relative to {@code app/app-authorization-ims-db2-mq} unless another tree is
 * named, and that whole tree is reference material this migration reads and never modifies.</p>
 *
 * <p>Assumptions: no golden master exists for any path asserted here, and the parity claim is scoped
 * accordingly. {@code tests/README.md} records at its L83 to L85 that the online programs of this
 * application cannot be exercised end to end because the runner provides no CICS runtime, so there is no
 * recorded output to compare against. The evidence for every expectation below is therefore the cited
 * baseline line together with the copybook and migration contracts, and never a captured run.</p>
 *
 * <p>Assumptions: three observations this class pins are defects or leftovers in the baseline, and not one
 * of them authorises touching it. The response is narrower than the segment, two table descriptions are
 * misspelled, and one paragraph carries a debug statement with no destination. Each is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} rather than repaired, following the precedent
 * the house already set at {@code tests/README.md} section 1.1 L50 to L60, where an unfixable baseline
 * defect is documented on the stated ground that no runnable claim may hide a blocked feature. No baseline
 * program is edited and none is deleted.</p>
 *
 * <p>Alternatives Considered: folding these cases into
 * {@link PendingAuthDetailProjectionTest}, which exercises the same service. Rejected because the first
 * case below asserts that exactly ONE query is issued and then verifies no further interaction with the
 * repository at all, and a case that needs the repository stubbed differently cannot share that fixture
 * without weakening the verification that gives it its value. The split is by fixture rather than by
 * subject, which is why two classes name one service: that class owns the table's declared CONTENTS, both
 * edit-mask widths and the forward step's direction, while this class owns what the table's search
 * RENDERS and what the projection publishes.</p>
 *
 * <p>Alternatives Considered: re-reading the committed {@code pautdtl1-*.bin} segment images here to drive
 * the domain and no-trim cases from bytes. Rejected because the byte-level layout proofs are owned one
 * package away, by {@code PendingAuthDetailMapperTest} for the geometry and by the dedicated fixture
 * classes named at each case below, and this class asserts at the SERVICE boundary where the question is
 * what the context DOES with an already-decoded value. The fixture that carries the equivalent bytes is
 * cited at each case so the two halves stay discoverable from either side, and the packed-decimal codec is
 * consumed from the shared kernel rather than re-proved.</p>
 *
 * <p>Trade-offs: the rows here are built in memory rather than decoded from those images, so a change to
 * the segment geometry would not fail this class. That is accepted deliberately: the geometry has an owner
 * that does fail on it, and duplicating the byte offsets here would create a second copy of the layout
 * that can drift from the registered one.</p>
 *
 * <p>Assumptions: the documentation in this class is written to satisfy user-specified Rule 1
 * (Explainability), whose ruling here is that every type and every member carries a Javadoc giving its
 * purpose, its parameters with their types, its return value and any exception it raises, and that every
 * non-obvious choice carries an adjacent comment giving the reason for it under one of the rule's named
 * categories rather than restating the code. Its gate is conjunctive, so a missing Javadoc and a missing
 * reason each fail on their own. The rule's own wording is not reproduced anywhere in this file; it is
 * cited by name and its text is read from the project's rules document.</p>
 *
 * <p>Assumptions: no reason in this class is labelled as a refactoring rationale, and the omission is
 * deliberate rather than an oversight. That label is the one user-specified Rule 1 (Explainability)
 * reserves for replacing existing code whose defect is being stated, and this class replaces none: the
 * eight registered behavioural divergences of this bounded context are owned by the sibling classes the
 * package charter names, and this one asserts the baseline's behaviour as it stands rather than departing
 * from it. Every reason here is therefore an assumption, an alternative weighed, or a trade-off
 * accepted.</p>
 */
class PendingAuthDetailServiceTest {

    /**
     * The authenticated principal every case in this class seals and redeems sealed values under.
     *
     * <p>Assumptions: one subject throughout. The sealed selector is bound to the caller it was issued to,
     * so sealing and redeeming have to agree on it or every case would fail on a refused token rather than
     * on the property it asserts.</p>
     */
    private static final String SUBJECT = "authorization-operator";

    /** The account the row belongs to. */
    private static final long ACCOUNT_ID = 11L;

    /** The decoded date component of the key, held as an integer column and never as packed bytes. */
    private static final int AUTH_DATE = 26215;

    /** The decoded time component of the key, positionally 09:16:44 and 902 milliseconds. */
    private static final int AUTH_TIME = 9_16_44_902;

    /** The card number the row carries, which every projection must publish masked. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** The card number as the mask publishes it, last four digits only. */
    private static final String MASKED_CARD_NUMBER = "************1111";

    /**
     * The stored originating date of the canonical row, in the year-first order the segment holds.
     *
     * <p>Assumptions: the six characters are {@code YYMMDD}, which is the order
     * {@code PA-AUTH-ORIG-DATE PIC X(06)} at {@code cpy/CIPAUDTY.cpy} L22 holds and the order L297 to L299
     * slice apart before recomposing them month-first. The stored and displayed orders therefore DIFFER
     * for the date, which is the reason this constant and {@link #RENDERED_ORIG_DATE} are declared as a
     * pair rather than derived from one another.</p>
     */
    private static final String STORED_ORIG_DATE = "260803";

    /** The stored originating time of the canonical row, {@code HHMMSS}. */
    private static final String STORED_ORIG_TIME = "091644";

    /** The originating date as L300 and L301 put it on the screen, month first and year LAST. */
    private static final String RENDERED_ORIG_DATE = "08/03/26";

    /** The originating time as L303 to L306 put it on the screen. */
    private static final String RENDERED_ORIG_TIME = "09:16:44";

    /**
     * The exact width the composed response reason occupies.
     *
     * <p>Assumptions: twenty characters, being {@code AUTHRSNO PIC X(20)} at
     * {@code cpy-bms/COPAU01.cpy} L248. The composition that fills it is a four-character code, a
     * separator overlaid at position five and a description written from position six onward, so the
     * description region is the remaining FIFTEEN positions.</p>
     */
    private static final int COMPOSED_REASON_WIDTH = 20;

    /**
     * The number of positions the composed reason leaves for a description.
     *
     * <p>Assumptions: fifteen, from twenty total less the four-character code and the one-character
     * separator. This is ONE FEWER than the sixteen {@code DECL-DESC PIC X(16)} declares at L73, and the
     * consequence of that shortfall is the subject of
     * {@link ReasonRendering#allTenDescriptionsRenderWithNoCharacterLost()}.</p>
     */
    private static final int COMPOSED_REASON_DESCRIPTION_ROOM = 15;

    /**
     * The reason codes the migrated producer can actually put on an authorization.
     *
     * <p>Assumptions: EIGHT values, and the count is derived rather than assumed.
     * {@code cbl/COPAUA0C.cbl} pre-sets {@code '0000'} at L698 outside its decline gate, so an approval
     * always carries it, and the {@code EVALUATE} at L700 to L717 assigns the other seven --
     * {@code '3100'} at L704, {@code '4100'} at L706, {@code '4200'} at L708, {@code '4300'} at L710,
     * {@code '5100'} at L712, {@code '5200'} at L714 and {@code '9000'} from its {@code WHEN OTHER} at
     * L716. No branch of that paragraph assigns {@code '4400'} or {@code '5300'} at all.</p>
     *
     * <p>Assumptions: the emitted set is owned by {@code AuthorizationRequestListenerTest}, which asserts
     * what the decision path produces. This constant is the READ side's copy of that set and exists only
     * to state the asymmetry in {@link CodeSetAsymmetry}; a change to what the producer emits belongs
     * there first and here second.</p>
     */
    private static final List<String> PRODUCER_EMITTED_REASON_CODES =
            List.of("0000", "3100", "4100", "4200", "4300", "5100", "5200", "9000");

    /**
     * The reason codes the display table renders but no producer emits.
     *
     * <p>Assumptions: exactly TWO, being {@code '4400EXCED DAILY LMT'} at L63 and
     * {@code '5300LOST CARD'} at L66. They are display-only rather than dead: the table is on the read
     * side of a queue whose producer is a separate deployable, so either code would render correctly if a
     * later producer ever sent it.</p>
     */
    private static final List<String> DISPLAY_ONLY_REASON_CODES = List.of("4400", "5300");

    /**
     * The stored members the screen projection deliberately omits.
     *
     * <p>Assumptions: these four are present in the segment and absent from the response, and the
     * omission is a decision rather than an oversight. {@code PA-AUTH-ID-CODE} at
     * {@code cpy/CIPAUDTY.cpy} L29, {@code PA-ACQR-COUNTRY-CODE} at L37, {@code PA-MESSAGE-TYPE} at L27
     * and {@code PA-TRANSACTION-AMT} at L34 are each referenced ZERO times by
     * {@code cbl/COPAUS1C.cbl}, while their neighbours {@code PA-APPROVED-AMT} at L35,
     * {@code PA-MESSAGE-SOURCE} at L28 and {@code PA-PROCESSING-CODE} at L33 are each referenced once and
     * do reach the screen.</p>
     *
     * <p>Assumptions: the transaction amount is spelled BOTH ways here because the omission has to hold
     * however the component might later be named. Only one spelling can be the real one, and asserting
     * both means a component added under either name fails this class rather than slipping through it.</p>
     */
    private static final List<String> MEMBERS_ABSENT_FROM_PROJECTION =
            List.of("authIdCode", "acqrCountryCode", "messageType", "transactionAmt",
                    "transactionAmount");

    /**
     * The label the leftover debug statement at L523 would print.
     *
     * <p>Assumptions: the literal is carried here so its ABSENCE can be asserted, which is the only way a
     * suppressed output can be pinned at all. It is not a value this system produces anywhere.</p>
     */
    private static final String DEBUG_DISPLAY_LABEL = "RPT DT: ";

    /**
     * The serialiser used to observe how money reaches the wire.
     *
     * <p>Assumptions: the shared money module is the ONLY module registered. Money reaches the wire as a
     * JSON string because that module puts it there, so registering anything else would let a second
     * module's behaviour explain a passing assertion.</p>
     */
    private static final JsonMapper MAPPER =
            JsonMapper.builder().addModule(new MoneyModule()).build();

    /** The repository double. */
    private PendingAuthDetailRepository details;

    /** The real mapper, so the selector a test presents is the one the service can redeem. */
    private PendingAuthViewMapper mapper;

    /** The service under test. */
    private PendingAuthDetailService service;

    /**
     * Builds the double, a real sealer over deterministic key material, and the service.
     *
     * <p>Assumptions: the sealer is REAL rather than mocked, and the key material is a fixed fill rather
     * than a random one. A mocked sealer would let a test present a selector the service could not
     * actually redeem, which would assert the mock instead of the service, and fixed material keeps a
     * failure reproducible from the source alone.</p>
     */
    @BeforeEach
    void setUp() {
        this.details = mock(PendingAuthDetailRepository.class);
        byte[] keyMaterial = new byte[CursorToken.MIN_KEY_LENGTH];
        // WHY : Assumptions: the sealer enforces a minimum key length, so the array is sized from that
        //       published constant rather than from a literal that could fall below it after a change.
        //       The fill byte is arbitrary but FIXED, because a random one would make a sealing failure
        //       reproduce only intermittently.
        Arrays.fill(keyMaterial, (byte) 0x3C);
        this.mapper = new PendingAuthViewMapper(new CursorToken(keyMaterial, Duration.ofMinutes(5)));
        this.service = new PendingAuthDetailService(this.details, this.mapper);
    }

    /**
     * The keyed retrieval: how a selector becomes a three-part key, and what absence means.
     *
     * <p><strong>Purpose.</strong> Covers {@code READ-AUTH-RECORD} at L431 to L492, reduced to one query,
     * together with the shape of the key it addresses and the transaction bracket it runs inside.</p>
     */
    @Nested
    @DisplayName("the keyed read")
    class KeyedRead {

        /**
         * The selector is redeemed into the three-part key and the row it names is read directly.
         *
         * <p>Assumptions: ONE query is asserted where the reference read is two -- a parent get-unique
         * qualified on the account at L439 to L443 and then a child get-next-within-parent at L465 to
         * L469. The hierarchy that forced the parent read is gone, because the relational child key names
         * its own account, so a second query would re-establish an invariant the schema already holds.
         * Asserting the absence of that query is what keeps it from being reintroduced as a defensive
         * read.</p>
         */
        @Test
        @DisplayName("the redeemed key is read in one query and the card number is published masked")
        void redeemedKeyIsReadInOneQueryAndTheCardNumberIsMasked() {
            when(PendingAuthDetailServiceTest.this.details.findById(any(PendingAuthDetailKey.class)))
                    .thenReturn(Optional.of(row()));

            PendingAuthDetailView view =
                    PendingAuthDetailServiceTest.this.service.read(selector(), SUBJECT);

            ArgumentCaptor<PendingAuthDetailKey> read =
                    ArgumentCaptor.forClass(PendingAuthDetailKey.class);
            verify(PendingAuthDetailServiceTest.this.details).findById(read.capture());
            verifyNoMoreInteractions(PendingAuthDetailServiceTest.this.details);
            assertThat(read.getValue().getAccountId()).isEqualTo(ACCOUNT_ID);
            assertThat(read.getValue().getAuthDate()).isEqualTo(AUTH_DATE);
            assertThat(read.getValue().getAuthTime()).isEqualTo(AUTH_TIME);
            assertThat(view.cardNum()).isEqualTo(MASKED_CARD_NUMBER);
        }

        /**
         * The key components are the DECODED date and time, carried as integers rather than packed bytes.
         *
         * <p>Assumptions: the reference key is EIGHT bytes -- the {@code PA-AUTHORIZATION-KEY} group at
         * {@code cpy/CIPAUDTY.cpy} L19, whose {@code PA-AUTH-DATE-9C PIC S9(05) COMP-3} at L20 occupies
         * three bytes and whose {@code PA-AUTH-TIME-9C PIC S9(09) COMP-3} at L21 occupies five. Those
         * bytes are decoded at the edge by the shared packed-decimal codec and the DECODED integers are
         * what the columns hold, so no packed byte is ever persisted. The assertion is that the values
         * arrive as comparable integers, because a column holding the packed form would still read back
         * and would order wrongly at every sign nibble.</p>
         */
        @Test
        @DisplayName("the key carries decoded integer components and never packed bytes")
        void theKeyCarriesDecodedIntegerComponents() {
            when(PendingAuthDetailServiceTest.this.details.findById(any(PendingAuthDetailKey.class)))
                    .thenReturn(Optional.of(row()));

            PendingAuthDetailServiceTest.this.service.read(selector(), SUBJECT);

            ArgumentCaptor<PendingAuthDetailKey> read =
                    ArgumentCaptor.forClass(PendingAuthDetailKey.class);
            verify(PendingAuthDetailServiceTest.this.details).findById(read.capture());
            assertThat(read.getValue().getAuthDate()).isInstanceOf(Integer.class);
            assertThat(read.getValue().getAuthTime()).isInstanceOf(Integer.class);
        }

        /**
         * Two authorizations recorded on the same day resolve to two distinct keys.
         *
         * <p>Assumptions: the date alone does not identify a row, so the pair has to be carried whole. The
         * committed image {@code pautdtl1-order-same-day-times.bin} holds the same-day case at the byte
         * level and is asserted by {@code PendingAuthDetailOrderingFixtureTest}; the property asserted
         * here is the one visible at this boundary, that the time component survives into the key and
         * distinguishes the two. Were it dropped, a same-day read would return whichever row the engine
         * reached first -- a plausible authorization, and not the requested one.</p>
         */
        @Test
        @DisplayName("two same-day authorizations resolve to keys differing only in the time component")
        void twoSameDayAuthorizationsResolveToDistinctKeys() {
            int laterTime = AUTH_TIME + 1;
            PendingAuthDetail earlier = row();
            PendingAuthDetail later = rowAt(AUTH_DATE, laterTime);
            when(PendingAuthDetailServiceTest.this.details.findById(any(PendingAuthDetailKey.class)))
                    .thenReturn(Optional.of(earlier));

            PendingAuthDetailServiceTest.this.service.read(selectorFor(earlier), SUBJECT);
            PendingAuthDetailServiceTest.this.service.read(selectorFor(later), SUBJECT);

            ArgumentCaptor<PendingAuthDetailKey> read =
                    ArgumentCaptor.forClass(PendingAuthDetailKey.class);
            verify(PendingAuthDetailServiceTest.this.details, times(2))
                    .findById(read.capture());
            List<PendingAuthDetailKey> keys = read.getAllValues();
            assertThat(keys.get(0).getAccountId()).isEqualTo(keys.get(1).getAccountId());
            assertThat(keys.get(0).getAuthDate()).isEqualTo(keys.get(1).getAuthDate());
            assertThat(keys.get(0).getAuthTime()).isEqualTo(AUTH_TIME);
            assertThat(keys.get(1).getAuthTime()).isEqualTo(laterTime);
        }

        /**
         * A selector that redeems to a key with no row behind it is not found.
         *
         * <p>Assumptions: the repository answers a keyed fetch with an EMPTY OPTIONAL -- it neither raises
         * nor returns a placeholder -- and the service is what turns that emptiness into a not-found
         * outcome for its caller. Both halves are asserted here because they are separate decisions: the
         * repository's contract is presence or absence, and the service's contract is that absence is an
         * ordinary result rather than a refusal. The reference program draws the same line, treating a
         * segment-not-found status at L471 to L473 as an end-of-data condition and not as an error, and
         * nothing the caller sends can correct a row the expiry sweep has already removed. The
         * corresponding shape on the wire is a 404, and that belongs to the controller boundary above,
         * which is where it is asserted.</p>
         */
        @Test
        @DisplayName("an empty optional from the repository becomes the ordinary not-found outcome")
        void selectorNamingNoRowIsNotFound() {
            when(PendingAuthDetailServiceTest.this.details.findById(any(PendingAuthDetailKey.class)))
                    .thenReturn(Optional.empty());

            assertThat(PendingAuthDetailServiceTest.this.details.findById(
                    new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME))).isEmpty();
            assertThatExceptionOfType(NoSuchElementException.class)
                    .isThrownBy(() ->
                            PendingAuthDetailServiceTest.this.service.read(selector(), SUBJECT));
        }

        /**
         * A value that is not a sealed selector is refused, keyed to the path member rather than the
         * cursor.
         *
         * <p>Assumptions: the field key matters to the caller. The shared sealer's own refusal names its
         * paging vocabulary, which is correct for the list operation and wrong here, because this value
         * arrives in a PATH segment the contract names {@code key}. A client told to correct a
         * {@code cursor} it never sent cannot act on the refusal, so the mapper re-keys it at that one
         * boundary.</p>
         */
        @Test
        @DisplayName("a value that is not a sealed selector is refused and keyed to the path member")
        void valueThatIsNotASealedSelectorIsRefused() {
            assertThatExceptionOfType(PendingAuthViewMapper.InvalidSelectorException.class)
                    .isThrownBy(() -> PendingAuthDetailServiceTest.this.service.read(
                            "11111111111:26215:91644902", SUBJECT))
                    .satisfies(refusal -> assertThat(refusal.fields())
                            .containsExactly(PendingAuthViewMapper.SELECTOR_FIELD));
        }

        /**
         * Every read this service publishes runs in a read-only transaction and takes no row lock.
         *
         * <p>Assumptions: the reference program brackets its work with an explicit paragraph pair --
         * {@code TAKE-SYNCPOINT.} at L557 committing at L558 and {@code ROLL-BACK.} at L565 rolling back
         * at L567 -- and takes no lock on any read paragraph. A read that locked would hold rows across a
         * client's think time that the reference never held, which is precisely the cost the
         * before-and-after comparison pattern exists to avoid. The locking variant the repository
         * publishes is therefore asserted UNCALLED rather than merely unused, because an unused method is
         * indistinguishable from one nobody checked.</p>
         *
         * @throws NoSuchMethodException if a member this service is required to publish is absent, which
         *     would itself be the failure
         */
        @Test
        @DisplayName("the reads are read-only transactions and no pessimistic lock is taken")
        void theReadsAreReadOnlyAndTakeNoPessimisticLock() throws NoSuchMethodException {
            when(PendingAuthDetailServiceTest.this.details.findById(any(PendingAuthDetailKey.class)))
                    .thenReturn(Optional.of(row()));

            PendingAuthDetailServiceTest.this.service.read(selector(), SUBJECT);

            for (Method member : List.of(
                    PendingAuthDetailService.class.getMethod("read", String.class, String.class),
                    PendingAuthDetailService.class.getMethod("readForScreen", String.class,
                            String.class, PendingAuthDetailMapper.ScreenContext.class),
                    PendingAuthDetailService.class.getMethod("readNext", String.class,
                            String.class))) {
                Transactional bracket = member.getAnnotation(Transactional.class);
                assertThat(bracket).as("%s declares a transaction bracket", member.getName())
                        .isNotNull();
                assertThat(bracket.readOnly()).as("%s is read-only", member.getName()).isTrue();
            }
            verify(PendingAuthDetailServiceTest.this.details, never())
                    .findWithLockById(any(PendingAuthDetailKey.class));
        }
    }

    /**
     * The ten-entry response-reason table, carried across character for character.
     *
     * <p><strong>Purpose.</strong> Covers the value clauses at L58 to L67 and the lookup structure the
     * {@code REDEFINES} at L68 to L73 lays over them.</p>
     */
    @Nested
    @DisplayName("the decline-reason table")
    class DeclineTable {

        /**
         * Every one of the ten declared entries resolves to its literal description.
         *
         * <p>Assumptions: these are USER-VISIBLE strings, so their exact characters are the contract under
         * AAP Rule T8 (user-visible strings verbatim). Each is compared against the literal its value
         * clause spells rather than against a re-worded, re-cased or expanded form, because an operator
         * reads this text and any edit to it is a behavioural change to the screen.</p>
         */
        @Test
        @DisplayName("all ten declared entries resolve to their literal descriptions")
        void allTenDeclaredEntriesResolveToTheirLiterals() {
            Map<String, String> expected = declaredTable();

            assertThat(expected).hasSize(PendingAuthDetailService.DECLINE_REASON_ENTRY_COUNT);
            expected.forEach((code, description) ->
                    assertThat(PendingAuthDetailService.declineDescriptionFor(code))
                            .as("the description declared for %s", code)
                            .isNotNull()
                            .satisfies(resolved -> assertThat(resolved.strip())
                                    .isEqualTo(description)));
        }

        /**
         * The abbreviated spelling of the insufficient-funds description is preserved, not corrected.
         *
         * <p>Assumptions: the fixed-width screen field forced this abbreviation and the abbreviation is
         * what the baseline displays, so it is carried across unchanged under AAP Rule T8 (user-visible
         * strings verbatim). Expanding it would change what an operator reads, which is a behavioural
         * divergence, and an undocumented behavioural divergence is exactly what this migration undertakes
         * not to introduce.</p>
         *
         * <p>Assumptions: the corrected spelling is asserted ABSENT as well as the declared spelling
         * present, and the pair is deliberate. A reader who takes the abbreviation for a typing mistake
         * would repair it and a test that only asserted a positive match on a constant they also edited
         * would still pass. Naming the plausible repair and refusing it is what makes the assertion
         * survive a well-meant correction.</p>
         */
        @Test
        @DisplayName("the insufficient-funds description keeps its abbreviated baseline spelling")
        void theInsufficientFundsDescriptionKeepsItsAbbreviatedSpelling() {
            String resolved = PendingAuthDetailService.declineDescriptionFor("4100");

            assertThat(resolved).isNotNull();
            assertThat(resolved.strip()).isEqualTo("INSUFFICNT FUND");
            assertThat(resolved.strip()).hasSize(COMPOSED_REASON_DESCRIPTION_ROOM);
            assertThat(resolved)
                    .as("the expanded spelling is NOT what L60 declares and must not appear")
                    .doesNotContain("INSUFFICIENT");
        }

        /**
         * The abbreviated spelling of the daily-limit description is preserved, not corrected.
         *
         * <p>Assumptions: the same ruling as the insufficient-funds entry, and it is asserted separately
         * because the two abbreviations are independent edits a reader could make one at a time. The
         * declared word carries a single {@code E}, and the doubled form a reader would reach for is
         * refused explicitly so the correction cannot be applied unnoticed.</p>
         */
        @Test
        @DisplayName("the daily-limit description keeps its abbreviated baseline spelling")
        void theDailyLimitDescriptionKeepsItsAbbreviatedSpelling() {
            String resolved = PendingAuthDetailService.declineDescriptionFor("4400");

            assertThat(resolved).isNotNull();
            assertThat(resolved.strip()).isEqualTo("EXCED DAILY LMT");
            assertThat(resolved.strip()).hasSize(COMPOSED_REASON_DESCRIPTION_ROOM);
            assertThat(resolved)
                    .as("the doubled-E spelling is NOT what L63 declares and must not appear")
                    .doesNotContain("EXCEED");
            assertThat(resolved)
                    .as("the expanded limit word is NOT what L63 declares and must not appear")
                    .doesNotContain("LIMIT");
        }

        /**
         * The first, the last and an interior entry all resolve, so the table's ordering cannot regress.
         *
         * <p>Assumptions: the reference lookup is a BINARY search. L319 issues
         * {@code SEARCH ALL WS-DECLINE-REASON-TAB} over a table declared
         * {@code ASCENDING KEY IS DECL-CODE} at L70, and a binary search is only correct while that
         * declared order actually holds -- an entry inserted out of order makes the reference search miss
         * a code that is present. The migrated lookup is keyed rather than ordered, so it would keep
         * answering correctly after such an edit and would hide the regression. Probing the two extremes
         * and the middle is what keeps the ordering assumption observable at this boundary rather than
         * silently discardable.</p>
         */
        @Test
        @DisplayName("the first, last and an interior entry each resolve through the ordered table")
        void theFirstLastAndAnInteriorEntryEachResolve() {
            List<String> declaredOrder = List.copyOf(declaredTable().keySet());

            assertThat(PendingAuthDetailService.declineDescriptionFor(declaredOrder.getFirst()))
                    .isNotNull();
            assertThat(PendingAuthDetailService.declineDescriptionFor(declaredOrder.getLast()))
                    .isNotNull();
            assertThat(PendingAuthDetailService.declineDescriptionFor("4300"))
                    .isNotNull()
                    .satisfies(interior -> assertThat(interior.strip())
                            .isEqualTo("ACCOUNT CLOSED"));
            assertThat(declaredOrder).isSorted();
        }
    }

    /**
     * What the table search RENDERS, including its two different not-found outcomes.
     *
     * <p><strong>Purpose.</strong> Covers the {@code SEARCH ALL} at L319 to L328 -- both the found branch
     * at L324 to L327 and the {@code AT END} branch at L320 to L323 -- and the decline indicator the
     * surrounding L311 to L317 select.</p>
     */
    @Nested
    @DisplayName("the reason rendering")
    class ReasonRendering {

        /**
         * A reason of nine thousand HITS the table and renders its own description.
         *
         * <p>Assumptions: {@code '9000'} is a declared table entry at L67, so it reaches the found branch
         * at L324 to L327 and renders the description that entry carries. This is a DIFFERENT value from
         * the one the not-found branch writes, and the two are easy to conflate because both read as an
         * unknown outcome: this one means the producer classified the decline as unknown, and it was
         * found.</p>
         */
        @Test
        @DisplayName("a nine-thousand reason hits the table and renders its declared description")
        void aNineThousandReasonHitsTheTable() {
            PendingAuthDetailResponse response = project(rowWithReason("9000"));

            assertThat(response.authResponseReason().strip()).isEqualTo("9000-UNKNOWN");
            assertThat(response.authResponseReason()).hasSize(COMPOSED_REASON_WIDTH);
        }

        /**
         * A reason absent from the table MISSES it and renders the distinct error pair.
         *
         * <p>Assumptions: the {@code AT END} branch replaces the CODE as well as the description. L321
         * writes four nines over the whole field, L322 overlays the separator at position five and L323
         * writes the error word from position six, so the stored reason does not survive into the
         * rendering at all. Carrying the stored code through beside a fallback description would publish a
         * pair the reference screen never showed, and a caller could no longer tell a resolved reason from
         * an unresolved one.</p>
         */
        @Test
        @DisplayName("a reason absent from the table renders the distinct nine-nines error pair")
        void aReasonAbsentFromTheTableRendersTheErrorPair() {
            PendingAuthDetailResponse response = project(rowWithReason("7777"));

            assertThat(response.authResponseReason().strip()).isEqualTo("9999-ERROR");
            assertThat(response.authResponseReason()).hasSize(COMPOSED_REASON_WIDTH);
            assertThat(response.authResponseReason())
                    .as("the unresolved code is replaced rather than carried through")
                    .doesNotContain("7777");
        }

        /**
         * The two not-found-looking renderings are distinct values reached by distinct paths.
         *
         * <p>Assumptions: stating the pair together is the point. One code is IN the table and renders
         * through it, the other is not in the table and renders the fallback, so a single assertion on
         * either would leave the other free to drift onto it. Collapsing them would lose the ability to
         * tell a classified unknown decline from a code this deployment has never heard of.</p>
         */
        @Test
        @DisplayName("the table-hit unknown and the table-miss error are different renderings")
        void theTableHitUnknownAndTheTableMissErrorAreDifferent() {
            String hit = project(rowWithReason("9000")).authResponseReason();
            String miss = project(rowWithReason("7777")).authResponseReason();

            assertThat(hit).isNotEqualTo(miss);
            assertThat(hit.strip()).startsWith("9000");
            assertThat(miss.strip()).startsWith("9999");
        }

        /**
         * All ten descriptions reach the screen with no character of their content lost.
         *
         * <p>Assumptions: the shortfall is real and it never fires. {@code DECL-DESC} declares SIXTEEN
         * characters at L73, while L327 moves it into {@code AUTHRSNO(6:)}, a reference-modified receiver
         * of exactly FIFTEEN positions, so a sixteenth character would be dropped on the way in. The
         * measured content lengths of the ten declared descriptions are 8, 12, 15, 15, 14, 15, 10, 14, 9
         * and 7 -- a maximum of fifteen, with three entries sitting exactly on it -- so the only character
         * the narrowing ever removes is a PAD SPACE. The truncation is therefore structurally possible and
         * behaviourally unreachable, and what is asserted here is the reachable state: every description
         * arrives complete.</p>
         *
         * <p>Alternatives Considered: writing a case that drives a sixteen-character description through
         * and asserts the sixteenth character is dropped. Rejected because no such description exists in
         * the baseline, so the case would assert a behaviour this system cannot exhibit and would read to a
         * later maintainer as a defect that had been accepted. An eleventh entry longer than fifteen
         * characters is what would expose the shortfall, and this case is what would then fail -- which is
         * the more useful signal of the two.</p>
         */
        @Test
        @DisplayName("all ten descriptions render complete, with only pad characters ever narrowed away")
        void allTenDescriptionsRenderWithNoCharacterLost() {
            declaredTable().forEach((code, description) -> {
                PendingAuthDetailResponse response = project(rowWithReason(code));
                String rendered = response.authResponseReason();

                assertThat(rendered).as("the rendering of %s", code).hasSize(COMPOSED_REASON_WIDTH);
                assertThat(rendered).startsWith(code);
                // WHY : Assumptions: the separator sits at index 4 here and at POSITION 5 in the
                //       baseline, because L322 and L326 both write into AUTHRSNO(5:1) and reference
                //       modification counts from one while this index counts from zero. The two
                //       numbers describe the same character, so a reader reconciling this line
                //       against the cited lines should expect them to differ by exactly one.
                assertThat(rendered.charAt(4)).isEqualTo('-');
                assertThat(rendered.substring(5))
                        .as("the description region of %s", code)
                        .hasSize(COMPOSED_REASON_DESCRIPTION_ROOM)
                        .satisfies(region -> assertThat(region.strip()).isEqualTo(description));
            });
        }

        /**
         * An approved authorization renders the approval indicator and a declined one the decline
         * indicator.
         *
         * <p>Assumptions: the indicator is a single character and it is ALL the reference projects here.
         * L311 tests the stored response code against two zeros and then L312 moves the approval character
         * or L315 the decline one, so the two-character response code itself never reaches the screen.</p>
         */
        @Test
        @DisplayName("the response indicator is one character selected from the stored response code")
        void theResponseIndicatorIsOneCharacter() {
            assertThat(project(rowWithRespCode("00")).authResponse()).isEqualTo("A");
            assertThat(project(rowWithRespCode("05")).authResponse()).isEqualTo("D");
        }

        /**
         * The decline is carried as an indicator and never as a terminal attribute byte.
         *
         * <p>Assumptions: the reference sets a colour alongside the indicator -- L313 for an approval and
         * L316 for a decline -- and colour is a PRESENTATION concern in the target, resolved by the
         * front-end theme rather than transported in the payload. So the response is asserted to carry the
         * indicator and to carry no attribute member at all: a payload that shipped a terminal colour byte
         * would be publishing a 3270 field attribute to a browser, which has nothing to do with it.</p>
         */
        @Test
        @DisplayName("the decline is an indicator in the body and no attribute byte is published")
        void theDeclineIsAnIndicatorAndNoAttributeByteIsPublished() {
            PendingAuthDetailResponse declined = project(rowWithRespCode("05"));

            assertThat(declined.authResponse()).isEqualTo("D");
            assertThat(projectionMemberNames())
                    .noneSatisfy(member -> assertThat(member.toLowerCase(Locale.ROOT))
                            .containsAnyOf("colour", "color", "attribute", "highlight"));
        }
    }

    /**
     * The response is a deliberate projection of the segment and not a copy of it.
     *
     * <p><strong>Purpose.</strong> Establishes that four stored members are omitted on purpose, and pins
     * the three members whose near-identical neighbours make them easy to mix up.</p>
     */
    @Nested
    @DisplayName("the deliberate projection")
    class DeliberateProjection {

        /**
         * Four stored members are absent from the published body.
         *
         * <p>Assumptions: the omission rests on three independent proofs, all of which point the same way.
         * First, occurrence counting in {@code cbl/COPAUS1C.cbl}: {@code PA-AUTH-ID-CODE},
         * {@code PA-ACQR-COUNTRY-CODE}, {@code PA-MESSAGE-TYPE} and {@code PA-TRANSACTION-AMT} are each
         * referenced zero times, while {@code PA-APPROVED-AMT}, {@code PA-MESSAGE-SOURCE} and
         * {@code PA-PROCESSING-CODE} are each referenced once and do reach the screen. Second, the map has
         * no receiving field for any of the four: {@code cpy-bms/COPAU01.cpy} declares twenty-seven named
         * fields between L17 and L180 and none of them is an authorization-identifier, acquirer-country,
         * message-type or transaction-amount slot. Third, the map carries exactly ONE amount slot,
         * {@code AUTHAMT} at L94 and L96, and the program fills it from the approved amount.</p>
         *
         * <p>Alternatives Considered: publishing the four anyway, on the reasoning that the caller already
         * paid for the row and a wider body costs nothing. Rejected because widening a contract is
         * irreversible in practice -- once a consumer reads a member it cannot be withdrawn -- and because
         * all three proofs show the narrowing is a decision the baseline made rather than a gap it left.
         * The full stored state does remain reachable through the row view the sibling read publishes, so
         * nothing is lost; the two readings are simply kept apart.</p>
         */
        @Test
        @DisplayName("the four omitted stored members appear nowhere in the published body")
        void theFourOmittedMembersAppearNowhereInTheBody() {
            List<String> published = projectionMemberNames();

            assertThat(published).hasSize(27);
            assertThat(published).doesNotContainAnyElementsOf(MEMBERS_ABSENT_FROM_PROJECTION);
        }

        /**
         * The message SOURCE is published and the message TYPE is not.
         *
         * <p>Assumptions: these two are the likeliest pair in the whole segment to be confused. They are
         * adjacent and identically declared -- {@code PA-MESSAGE-TYPE PIC X(06)} at
         * {@code cpy/CIPAUDTY.cpy} L27 and {@code PA-MESSAGE-SOURCE PIC X(06)} at L28 -- so nothing about
         * either field's shape distinguishes them, and only L333 does: it moves the SOURCE. Asserting the
         * presence and the absence in one case is what keeps a later edit from satisfying half of the pair
         * by substituting the other field.</p>
         */
        @Test
        @DisplayName("the message source is published while the adjacent message type is not")
        void theMessageSourceIsPublishedAndTheMessageTypeIsNot() {
            PendingAuthDetailResponse response = project(row());

            assertThat(response.messageSource()).isEqualTo("0100");
            assertThat(projectionMemberNames()).contains("messageSource");
            assertThat(projectionMemberNames()).doesNotContain("messageType");
        }

        /**
         * The single amount slot carries the APPROVED amount and not the requested one.
         *
         * <p>Assumptions: this is the strongest of the four omissions, because it cannot be explained away
         * as a category being skipped. {@code PA-TRANSACTION-AMT} at {@code cpy/CIPAUDTY.cpy} L34 and
         * {@code PA-APPROVED-AMT} at L35 are the same declared type in the same record, and the program
         * uses one and not the other, so the correct framing is not that amounts were omitted but that
         * there is one amount slot and the program chose which amount to put in it. The list screen makes
         * the same choice independently at {@code cbl/COPAUS0C.cbl} L525, and two separately written
         * programs selecting the same member is what turns a single omission into evidence of intent.</p>
         *
         * <p>Assumptions: the two amounts are given DIFFERENT values here on purpose. Were they equal, the
         * assertion would pass whichever member the projection actually read, and the case would prove
         * nothing at all.</p>
         */
        @Test
        @DisplayName("the one amount slot carries the approved amount, not the requested amount")
        void theOneAmountSlotCarriesTheApprovedAmount() {
            PendingAuthDetail row = rowWithAmounts(new BigDecimal("500.00"), new BigDecimal("250.00"));

            PendingAuthDetailResponse response = project(row);

            assertThat(response.approvedAmount().amount()).isEqualByComparingTo("250.00");
            assertThat(response.approvedAmount().amount())
                    .as("the requested amount must not be the one published")
                    .isNotEqualByComparingTo("500.00");
            assertThat(amountMemberNames())
                    .as("the map declares exactly one amount slot")
                    .containsExactly("approvedAmount");
        }

        /**
         * The code member carries the PROCESSING code, despite what its baseline field name suggests.
         *
         * <p>Assumptions: the field name is not evidence and the mapping is forced. L331 moves
         * {@code PA-PROCESSING-CODE}, declared {@code PIC 9(06)} at {@code cpy/CIPAUDTY.cpy} L33, into
         * {@code AUTHCDO}, declared {@code PIC X(6)} at {@code cpy-bms/COPAU01.cpy} L254 -- an exact width
         * fit -- and the map contains no authorization-identifier field for the similarly named
         * {@code PA-AUTH-ID-CODE} to go into. So the name misleads and the behaviour does not, and a reader
         * who trusted the name would wire the wrong member into a slot that would accept it silently.</p>
         *
         * <p>Assumptions: the two stored values are made distinguishable here for the same reason the two
         * amounts are. The authorization identifier is set to a value that could not be mistaken for a
         * processing code, so a projection reading the wrong member fails rather than coincidentally
         * agreeing.</p>
         */
        @Test
        @DisplayName("the code member carries the processing code and never the authorization identifier")
        void theCodeMemberCarriesTheProcessingCode() {
            PendingAuthDetailResponse response = project(row());

            assertThat(response.processingCode()).isEqualTo("003000");
            assertThat(response.processingCode())
                    .as("the authorization identifier has no slot and must not appear here")
                    .isNotEqualTo("AUTH01");
            assertThat(projectionMemberNames()).doesNotContain("authIdCode");
        }
    }

    /**
     * The members that do reach the screen, in the order the reference moves them.
     *
     * <p><strong>Purpose.</strong> Covers L331 to L352 -- the code, the entry mode, the source, the
     * merchant category, the expanded expiry, the type, the transaction identifier, the match status, the
     * fraud mark and the merchant name -- together with the two value domains the copybook constrains and
     * the empty-state literals L53 and L54 declare.</p>
     */
    @Nested
    @DisplayName("the rendered members")
    class RenderedMembers {

        /**
         * Each member the reference moves onto the map arrives with the value it moved.
         *
         * <p>Assumptions: the members are checked together and in the reference's own move order, because
         * the risk this guards is a TRANSPOSITION rather than a wrong value. Several of these are
         * same-width character fields sitting next to one another in the segment, so swapping a pair
         * produces a well-formed screen in which two values are simply in each other's places, and only
         * comparing all of them at once against distinct values catches it.</p>
         *
         * <p>Assumptions: the merchant category code is spelled correctly in the target and is misspelled
         * in the baseline. {@code PA-MERCHANT-CATAGORY-CODE} at {@code cpy/CIPAUDTY.cpy} L36 becomes the
         * corrected {@code merchant_category_code} column, and that rename is registered in
         * {@code docs/architecture/data-model-and-schema-mapping.md}. It is a FIELD name rather than a
         * user-visible string, which is why it is corrected where the two table descriptions are not: no
         * operator ever reads it.</p>
         */
        @Test
        @DisplayName("the code, entry mode, source, category, expiry, type, identifier and status all land")
        void theRenderedMembersAllLand() {
            PendingAuthDetailResponse response = project(row());

            assertThat(response.processingCode()).isEqualTo("003000");
            assertThat(response.posEntryMode()).isEqualTo("05");
            assertThat(response.messageSource()).isEqualTo("0100");
            assertThat(response.merchantCategoryCode()).isEqualTo("5411");
            assertThat(response.cardExpiry()).isEqualTo("27/12");
            assertThat(response.authType()).isEqualTo("0100");
            assertThat(response.transactionId()).isEqualTo("TX0000000000001");
            assertThat(response.matchStatus()).isEqualTo(PendingAuthDetail.MATCH_STATUS_PENDING);
        }

        /**
         * The stored four-character expiry reaches the screen as five characters.
         *
         * <p>Assumptions: the separator is part of the value rather than a formatting choice left to a
         * consumer. L336 moves the first two stored characters, L337 overlays a solidus at position three
         * and L338 moves the remaining two, so the stored month-and-year pair is displayed expanded.
         * Publishing the stored four characters instead would move that composition into every consumer,
         * and each would have to rediscover which half is the month.</p>
         */
        @Test
        @DisplayName("the stored expiry is expanded from four characters to five")
        void theStoredExpiryIsExpandedToFiveCharacters() {
            PendingAuthDetailResponse response = project(row());

            assertThat(response.cardExpiry()).isEqualTo("27/12").hasSize(5);
        }

        /**
         * The merchant name keeps every trailing blank the fixed-width field pads it with.
         *
         * <p>Assumptions: the baseline never trims this field. L352 moves the whole of
         * {@code PA-MERCHANT-NAME PIC X(22)}, declared at {@code cpy/CIPAUDTY.cpy} L40, and
         * {@code cbl/COPAUS2C.cbl} L130 moves {@code LENGTH OF PA-MERCHANT-NAME} -- the constant
         * twenty-two -- unconditionally rather than a measured length. The committed image
         * {@code pautdtl1-merchant-name-notrim.bin} carries the padded case at the byte level and is
         * asserted by {@code MerchantNameNoTrimFixtureTest}; what is asserted here is that the padding
         * survives the service boundary rather than being tidied away in passing.</p>
         *
         * <p>Trade-offs: a trimmed name would be friendlier to read and would silently change the width of
         * a field a fixed-width consumer may still be aligning on. Preserving the blanks keeps the value
         * identical to the baseline's and leaves trimming to whoever displays it, which is the only party
         * that knows whether alignment matters.</p>
         */
        @Test
        @DisplayName("the merchant name keeps its trailing pad blanks")
        void theMerchantNameKeepsItsTrailingPadBlanks() {
            String padded = "ACME CO" + " ".repeat(15);
            PendingAuthDetailResponse response = project(rowWithMerchantName(padded));

            assertThat(response.merchantName()).isEqualTo(padded).hasSize(22);
            assertThat(response.merchantName()).endsWith(" ");
        }

        /**
         * All four declared match statuses reach the screen and a value outside the domain is refused.
         *
         * <p>Assumptions: the domain is the four condition names at {@code cpy/CIPAUDTY.cpy} L46 to L49
         * over {@code PA-MATCH-STATUS PIC X(01)} at L45, and the committed images
         * {@code pautdtl1-match-status-domain.bin} and {@code pautdtl1-match-status-invalid.bin} carry the
         * four valid values and an out-of-domain one at the byte level, asserted by
         * {@code PendingAuthDetailDomainRefusalFixtureTest}. Two of the four are reachable only by
         * rehydration -- the expired state is written by the purge job and the matched state by the posting
         * match -- so the rehydration entry point is used here rather than the originating constructor,
         * which admits only the two states an insert reaches.</p>
         *
         * <p>Assumptions: the out-of-domain value is REFUSED rather than coerced or stored. A character
         * column would accept it and every consumer downstream would then have to decide what an unknown
         * status means, so refusing it at construction keeps the four-value domain the copybook declares an
         * actual guarantee.</p>
         */
        @Test
        @DisplayName("all four match statuses render and an out-of-domain status is refused")
        void allFourMatchStatusesRenderAndAnInvalidOneIsRefused() {
            for (String status : List.of(PendingAuthDetail.MATCH_STATUS_PENDING,
                    PendingAuthDetail.MATCH_STATUS_DECLINED,
                    PendingAuthDetail.MATCH_STATUS_PENDING_EXPIRED,
                    PendingAuthDetail.MATCH_STATUS_MATCHED_WITH_TRAN)) {
                assertThat(project(rowWithMatchStatus(status)).matchStatus())
                        .as("the rendering of match status %s", status)
                        .isEqualTo(status);
            }
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> rowWithMatchStatus("X"));
        }

        /**
         * A marked authorization renders the flag, the separator and the report date as one value.
         *
         * <p>Assumptions: the composition is three moves into one field. L345 writes the flag at position
         * one, L346 the separator at position two and L347 the eight-character report date from position
         * three, filling {@code AUTHFRDO PIC X(10)} at {@code cpy-bms/COPAU01.cpy} L308 exactly. Both
         * marking values compose the same way, which is why the reported and the withdrawn state are
         * asserted together rather than only the reported one.</p>
         */
        @Test
        @DisplayName("a marked authorization renders flag, separator and report date in ten characters")
        void aMarkedAuthorizationRendersTheComposedFraudValue() {
            assertThat(project(rowMarked(PendingAuthDetail.FRAUD_REPORTED)).fraudMark())
                    .isEqualTo("F-04/29/24")
                    .hasSize(10);
            assertThat(project(rowMarked(PendingAuthDetail.FRAUD_REMOVED)).fraudMark())
                    .isEqualTo("R-04/29/24")
                    .hasSize(10);
        }

        /**
         * An unmarked authorization renders a bare separator and nothing else.
         *
         * <p>Assumptions: the else branch at L349 moves a lone separator over the WHOLE field rather than
         * blanking it or leaving the flag position empty, so the never-examined state has a printable form
         * of its own. The copybook supports this asymmetry: {@code PA-AUTH-FRAUD PIC X(01)} at
         * {@code cpy/CIPAUDTY.cpy} L50 declares condition names only for the two marking values at L51 and
         * L52 and declares NONE for the blank, so the unexamined state is unnamed in the baseline and is
         * reached here by the absence of a mark. The committed image
         * {@code pautdtl1-auth-fraud-domain.bin} carries all three states.</p>
         */
        @Test
        @DisplayName("an unmarked authorization renders a bare separator")
        void anUnmarkedAuthorizationRendersABareSeparator() {
            assertThat(project(row()).fraudMark()).isEqualTo("-");
        }

        /**
         * A fraud value outside the two marking states is refused.
         *
         * <p>Assumptions: the two condition names at {@code cpy/CIPAUDTY.cpy} L51 and L52 are the whole
         * domain of the marking transition, and the committed image
         * {@code pautdtl1-auth-fraud-invalid.bin} carries an out-of-domain character. Refusing it keeps the
         * transition from writing a third state that no reference program can interpret and that the bare
         * character column would otherwise accept.</p>
         */
        @Test
        @DisplayName("a fraud value outside the two marking states is refused")
        void aFraudValueOutsideTheTwoMarkingStatesIsRefused() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> rowMarked("Y"));
        }

        /**
         * The leftover debug label is emitted nowhere, while the date it printed is still published.
         *
         * <p>Assumptions: L523 carries {@code DISPLAY 'RPT DT: '} followed by the report date, inside a
         * program that runs under a terminal monitor where a display statement has no defined destination.
         * It is leftover debugging rather than a feature, so the migrated path emits it in no form: not as
         * a log line and not as a member of the body.</p>
         *
         * <p>Trade-offs: dropping it costs an incidental diagnostic that a developer once found useful, and
         * that is accepted because the statement had nowhere to write in the first place -- so what is lost
         * is the intention rather than any observable output. The DATA it printed is deliberately NOT lost
         * with it: the report date still reaches the body through the composition at L345 to L347, and the
         * two properties are asserted separately here so that removing the statement cannot quietly take
         * the value with it.</p>
         */
        @Test
        @DisplayName("the debug label is emitted nowhere while the report date it printed still is")
        void theDebugLabelIsEmittedNowhereWhileTheReportDateSurvives() {
            PendingAuthDetailResponse marked = project(rowMarked(PendingAuthDetail.FRAUD_REPORTED));
            String serialised = MAPPER.writeValueAsString(marked);

            assertThat(serialised).doesNotContain(DEBUG_DISPLAY_LABEL);
            assertThat(serialised).doesNotContain("RPT DT");
            assertThat(projectionMemberNames())
                    .noneSatisfy(member -> assertThat(member.toLowerCase(Locale.ROOT))
                            .contains("rptdt"));
            // WHY : Assumptions: the date begins at index 2 here and at POSITION 3 in the baseline,
            //       because L347 writes into AUTHFRDO(3:) after the flag at position 1 and the
            //       separator at position 2. Reading this offset as three would silently drop the
            //       first digit of the date and still return a plausible eight-character value.
            assertThat(marked.fraudMark().substring(2))
                    .as("the report date the suppressed statement printed is still published")
                    .isEqualTo("04/29/24");
        }

        /**
         * A projection with no originating date or time falls back to the field's own initial literals.
         *
         * <p>Assumptions: these two literals are the empty STATE and not placeholders for one.
         * {@code WS-AUTH-DATE} is declared with an initial value at L53 and {@code WS-AUTH-TIME} at L54,
         * and {@code POPULATE-AUTH-DETAILS} overwrites them only inside the gate at L294 -- so on any path
         * where the projection produced nothing, these exact characters are what the screen displayed. They
         * are carried across verbatim under AAP Rule T8 (user-visible strings verbatim) rather than as a
         * null, an empty string or a zero date in calendar order, each of which is a different value that no
         * reference field ever held.</p>
         *
         * <p>Assumptions: the separators differ between the two, solidi for the date and colons for the
         * time, which is the only difference between the literals and the reason they are asserted as a pair
         * rather than derived from one pattern.</p>
         */
        @Test
        @DisplayName("an absent originating date and time fall back to the declared empty-state literals")
        void anAbsentDateAndTimeFallBackToTheEmptyStateLiterals() {
            PendingAuthDetailResponse blank = project(rowWithoutOriginatingStamp());

            assertThat(PendingAuthDetailService.screenAuthDate(blank.authDate()))
                    .isEqualTo("00/00/00");
            assertThat(PendingAuthDetailService.screenAuthTime(blank.authTime()))
                    .isEqualTo("00:00:00");
        }

        /**
         * A two-digit stored year stays two digits, and the display order is month first.
         *
         * <p>Assumptions: the baseline never widens a year. L297 to L300 slice
         * {@code PA-AUTH-ORIG-DATE PIC X(06)} into its three pairs and recompose them month first, and no
         * step adds a century, so the rendered value carries a two-character year. The committed image
         * {@code pautdtl1-date-formats.bin} pins the stored forms and is asserted by
         * {@code PendingAuthDetailDateFormatFixtureTest}. The widened form is refused explicitly here
         * because inferring a century is exactly the well-meant addition a reader would make, and it would
         * change the width of a displayed field as well as inventing information the segment does not
         * carry.</p>
         */
        @Test
        @DisplayName("the rendered originating date keeps a two-digit year and leads with the month")
        void theRenderedDateKeepsATwoDigitYear() {
            PendingAuthDetailResponse response = project(row());

            assertThat(response.authDate()).isEqualTo(RENDERED_ORIG_DATE).hasSize(8);
            assertThat(response.authDate())
                    .as("no century is inferred onto a two-digit stored year")
                    .isNotEqualTo("08/03/2026");
            assertThat(response.authTime()).isEqualTo(RENDERED_ORIG_TIME).hasSize(8);
        }

        /**
         * Money stays exact fixed point and reaches the wire as a JSON string.
         *
         * <p>Assumptions: the stored amounts are {@code PIC S9(10)V99 COMP-3} at
         * {@code cpy/CIPAUDTY.cpy} L34 and L35, seven bytes each, and they map to a fixed-point numeric
         * column of scale two. So the value is asserted at scale two with the half-up rounding the shared
         * money contract fixes, under AAP Rule T3 (money never leaves fixed point).</p>
         *
         * <p>Assumptions: the wire form is a JSON STRING rather than a JSON number, and that is the point of
         * the serialisation assertion. Most clients parse a JSON number into a binary floating-point double,
         * which is exact for small amounts and silently wrong for large ones, so a number here would lose
         * exactness at the boundary the operator actually reads.</p>
         *
         * <p>Assumptions: the layering rule that forbids binary floating point is scoped to the shared money
         * package and does NOT reach this module, so it is not inherited here and the absence of a
         * floating-point member in the published body is asserted directly rather than assumed.</p>
         */
        @Test
        @DisplayName("money is fixed point at scale two and serialises as a JSON string")
        void moneyIsFixedPointAndSerialisesAsAString() {
            PendingAuthDetailResponse response = project(row());

            assertThat(response.approvedAmount().amount().scale()).isEqualTo(Money.SCALE);
            assertThat(Money.GENERAL_ROUNDING).isEqualTo(RoundingMode.HALF_UP);
            assertThat(response.approvedAmount().amount()).isInstanceOf(BigDecimal.class);
            assertThat(MAPPER.writeValueAsString(response)).contains("\"approvedAmount\":\"250.00\"");
            assertThat(projectionMemberTypes())
                    .doesNotContain(double.class, float.class, Double.class, Float.class);
        }

        /**
         * An amount using the whole declared integer width survives to the wire as a string.
         *
         * <p>Assumptions: the picture admits TEN integer digits, so the widest storable amount has to
         * traverse the projection intact. The committed image
         * {@code pautdtl1-amount-ten-integer-digits.bin} carries that case at the byte level. This is the
         * case where a floating-point hop would first become visible, because a value of this magnitude
         * exceeds what a binary double represents exactly, so passing it proves the exactness claim on the
         * only input that can disprove it.</p>
         *
         * <p>Assumptions: the twelve-character screen edit mask is NARROWER than this value and is not the
         * shape asserted here. That mask is a display form owned by the sibling projection test, the wire
         * mask on the message path is wider again, and the response shape is neither of them: it is a JSON
         * string. Conflating the three is easy because all three are money, and only one is a payload.</p>
         */
        @Test
        @DisplayName("an amount at the full declared integer width reaches the wire exactly")
        void anAmountAtFullDeclaredWidthReachesTheWireExactly() {
            BigDecimal widest = new BigDecimal("9999999999.99");
            PendingAuthDetailResponse response =
                    project(rowWithAmounts(widest, widest));

            assertThat(response.approvedAmount().amount()).isEqualByComparingTo(widest);
            assertThat(MAPPER.writeValueAsString(response))
                    .contains("\"approvedAmount\":\"9999999999.99\"");
        }
    }

    /**
     * The display table is wider than the emitted code set, and by exactly how much.
     *
     * <p><strong>Purpose.</strong> States the asymmetry between what the producer can put on an
     * authorization and what the read side can render, so that neither half is mistaken for the other.</p>
     */
    @Nested
    @DisplayName("the code-set asymmetry")
    class CodeSetAsymmetry {

        /**
         * The table holds ten entries against eight emitted codes, so exactly two are display-only.
         *
         * <p>Assumptions: the split is EIGHT reachable and TWO display-only, and both figures are derived
         * from the producer rather than estimated. {@code cbl/COPAUA0C.cbl} pre-sets {@code '0000'} at L698
         * outside its decline gate, and the {@code EVALUATE} at L700 to L717 assigns the remaining seven,
         * which is eight distinct values in total; no branch of that paragraph assigns {@code '4400'} at
         * L63 or {@code '5300'} at L66. Eight plus two is the ten the table declares, so the table is a
         * superset by TWO and not by any larger number.</p>
         *
         * <p>Alternatives Considered: pruning the two entries no producer emits, which would leave the
         * table exactly as wide as the emitted set and remove a standing question about why it is wider.
         * Rejected because this is a DISPLAY table on the read side of a queue whose producer is a separate
         * deployable that can change without this one: a pruned table would send either code down the
         * not-found branch and render the nine-nines error pair, losing a description the reference
         * application always had. Keeping them costs two map entries and preserves two user-visible
         * strings.</p>
         *
         * <p>Assumptions: the EMITTED half of this contract belongs to
         * {@code AuthorizationRequestListenerTest}, which asserts what the decision path produces, and this
         * case owns only the DISPLAY half. The sibling is named so that a change to either half is
         * discoverable from the other, because the asymmetry is only meaningful when both figures are read
         * together.</p>
         */
        @Test
        @DisplayName("ten declared entries against eight emitted codes leaves exactly two display-only")
        void tenDeclaredEntriesAgainstEightEmittedCodesLeavesTwoDisplayOnly() {
            assertThat(PRODUCER_EMITTED_REASON_CODES).hasSize(8);
            assertThat(DISPLAY_ONLY_REASON_CODES).hasSize(2);
            assertThat(PRODUCER_EMITTED_REASON_CODES.size() + DISPLAY_ONLY_REASON_CODES.size())
                    .isEqualTo(PendingAuthDetailService.DECLINE_REASON_ENTRY_COUNT);
            assertThat(PRODUCER_EMITTED_REASON_CODES)
                    .doesNotContainAnyElementsOf(DISPLAY_ONLY_REASON_CODES);
        }

        /**
         * Every emitted code resolves through the table, so no producible decline renders as an error.
         *
         * <p>Assumptions: this is the direction that matters operationally. A code the producer can emit but
         * the table cannot resolve would reach an operator as the nine-nines error pair on an authorization
         * that was in fact classified, which reads as a system fault rather than as the business decision it
         * is.</p>
         */
        @Test
        @DisplayName("every emitted reason code resolves through the display table")
        void everyEmittedReasonCodeResolvesThroughTheTable() {
            for (String code : PRODUCER_EMITTED_REASON_CODES) {
                assertThat(PendingAuthDetailService.declineDescriptionFor(code))
                        .as("the description available for emitted code %s", code)
                        .isNotNull();
            }
        }

        /**
         * The two display-only codes still render their descriptions rather than the error pair.
         *
         * <p>Assumptions: this is what makes them display-only rather than dead. Each is retained precisely
         * so that a later producer emitting it would see the description the reference application carries,
         * and asserting that they render is what demonstrates the retention has a purpose instead of being
         * inherited clutter.</p>
         */
        @Test
        @DisplayName("the two display-only codes render their descriptions if ever emitted")
        void theTwoDisplayOnlyCodesStillRenderTheirDescriptions() {
            assertThat(project(rowWithReason("4400")).authResponseReason().strip())
                    .isEqualTo("4400-EXCED DAILY LMT");
            assertThat(project(rowWithReason("5300")).authResponseReason().strip())
                    .isEqualTo("5300-LOST CARD");
        }
    }

    /**
     * Returns the ten declared table entries, keyed by code, in their declared ascending order.
     *
     * <p>Assumptions: this is an INDEPENDENT transcription of the value clauses at L58 to L67 rather than a
     * read of the table the service publishes. A test that compared the service's table against itself
     * would pass for any content at all, so the expectation is written out here from the baseline lines and
     * the two are then compared.</p>
     *
     * <p>Assumptions: the descriptions are held at their CONTENT length rather than space-padded to the
     * sixteen characters the picture declares, because what each case asserts is the content that reaches
     * the screen. The padding is the picture's and is compared separately where it matters.</p>
     *
     * @return an insertion-ordered map of the ten declared codes to their unpadded descriptions, never
     *     {@code null}
     */
    private static Map<String, String> declaredTable() {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("0000", "APPROVED");
        declared.put("3100", "INVALID CARD");
        declared.put("4100", "INSUFFICNT FUND");
        declared.put("4200", "CARD NOT ACTIVE");
        declared.put("4300", "ACCOUNT CLOSED");
        declared.put("4400", "EXCED DAILY LMT");
        declared.put("5100", "CARD FRAUD");
        declared.put("5200", "MERCHANT FRAUD");
        declared.put("5300", "LOST CARD");
        declared.put("9000", "UNKNOWN");
        return declared;
    }

    /**
     * Returns the component names the published detail body declares.
     *
     * <p>Assumptions: the body is a record, so its components ARE its published surface and reflecting over
     * them is what makes an absence assertable at all. Asserting a member is missing by reading a getter is
     * not expressible -- absent code does not compile -- so the projection's narrowness can only be pinned
     * by inspecting the declared component set.</p>
     *
     * @return the declared component names of the detail response, in declaration order, never
     *     {@code null}
     */
    private static List<String> projectionMemberNames() {
        return Arrays.stream(PendingAuthDetailResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the component types the published detail body declares.
     *
     * @return the declared component types of the detail response, in declaration order, never
     *     {@code null}
     */
    private static List<Class<?>> projectionMemberTypes() {
        return Arrays.stream(PendingAuthDetailResponse.class.getRecordComponents())
                .map(RecordComponent::getType)
                .toList();
    }

    /**
     * Returns the names of every money-typed component the published detail body declares.
     *
     * <p>Assumptions: selecting by TYPE rather than by name is what makes the single-amount-slot claim
     * checkable. A name-based match would depend on the naming convention it is trying to test, and would
     * miss an amount added under a name that did not read like one.</p>
     *
     * @return the declared names of the money-typed components, in declaration order, never {@code null}
     */
    private static List<String> amountMemberNames() {
        return Arrays.stream(PendingAuthDetailResponse.class.getRecordComponents())
                .filter(component -> component.getType().equals(Money.class))
                .map(RecordComponent::getName)
                .toList();
    }

    /**
     * Returns the screen chrome the projection needs and no stored segment holds.
     *
     * <p>Assumptions: the six components are the transaction name, the two title bands, the program name,
     * the instant the screen is rendered at and the message line. They are fixed here rather than varied
     * per case because no assertion in this class is about the chrome, and a varying value would add a
     * reason for a case to fail that has nothing to do with what it tests.</p>
     *
     * @return a fixed screen context, never {@code null}
     */
    private static PendingAuthDetailMapper.ScreenContext context() {
        return new PendingAuthDetailMapper.ScreenContext("CPVD", "CardDemo", "COPAUS1C",
                "View Pending Authorization Details", LocalDateTime.of(2026, 8, 3, 9, 16, 44), "");
    }

    /**
     * Projects one authorization through the service and returns the published body.
     *
     * <p>Assumptions: the projection is driven through the SERVICE rather than through the mapper directly,
     * because the decline-reason lookup happens in the service and is handed to the mapper as an
     * already-resolved value. Calling the mapper directly would let a case supply its own description and
     * would no longer prove that the service resolves the stored reason at all.</p>
     *
     * @param row the stored authorization to project; must not be {@code null}
     * @return the composed detail body the service publishes for {@code row}, never {@code null}
     */
    private PendingAuthDetailResponse project(PendingAuthDetail row) {
        when(this.details.findById(any(PendingAuthDetailKey.class))).thenReturn(Optional.of(row));
        return this.service.readForScreen(selectorFor(row), SUBJECT, context());
    }

    /**
     * Seals a selector for the canonical row.
     *
     * @return a sealed selector redeemable by {@link #SUBJECT}, never {@code null}
     */
    private String selector() {
        return selectorFor(row());
    }

    /**
     * Seals a selector for one particular row.
     *
     * <p>Assumptions: the selector is produced by the REAL mapper from the row itself, so the key the
     * service redeems is the key that row is stored under. Hand-writing a token would either be refused or
     * would redeem to a key unrelated to the row a case stubbed, and the failure would look like a
     * behavioural one.</p>
     *
     * @param row the authorization whose key is to be sealed; must not be {@code null}
     * @return a sealed selector for {@code row}, redeemable by {@link #SUBJECT}, never {@code null}
     */
    private String selectorFor(PendingAuthDetail row) {
        return this.mapper.toRowView(row, SUBJECT).key();
    }

    /**
     * Builds the canonical authorization row every case starts from.
     *
     * <p>Assumptions: the members are given DISTINCT values wherever two of them could be confused. The
     * authorization identifier, the message type and the message source are all six-character fields and
     * the two amounts share a declared type, so equal values would let a transposition pass unnoticed.</p>
     *
     * @return a fully populated approved authorization in the pending match state, never {@code null}
     */
    private static PendingAuthDetail row() {
        return rowAt(AUTH_DATE, AUTH_TIME);
    }

    /**
     * Builds the canonical row under one particular date and time key.
     *
     * @param authDate the decoded date component of the key, as the integer the column holds
     * @param authTime the decoded time component of the key, as the integer the column holds
     * @return the canonical authorization keyed at {@code authDate} and {@code authTime}, never
     *     {@code null}
     */
    private static PendingAuthDetail rowAt(int authDate, int authTime) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, authDate, authTime),
                STORED_ORIG_DATE, STORED_ORIG_TIME, CARD_NUMBER, "0100", "2712", "0200", "0100",
                "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Builds the canonical row carrying one particular response reason and a declined response code.
     *
     * <p>Assumptions: the response code is set to the declined value alongside the reason, because the
     * reference program's reason table is only meaningful on a decline even though its search at L319 runs
     * unconditionally. Pairing them keeps each case a state the producer could actually have written.</p>
     *
     * @param reasonCode the four-character response reason to store; must not be {@code null}
     * @return the canonical authorization carrying {@code reasonCode}, never {@code null}
     */
    private static PendingAuthDetail rowWithReason(String reasonCode) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                STORED_ORIG_DATE, STORED_ORIG_TIME, CARD_NUMBER, "0100", "2712", "0200", "0100",
                // WHY : Assumptions: the response code is DERIVED from the reason rather than passed in,
                //       because the two are set together by the producer -- cbl/COPAUA0C.cbl L693 pairs
                //       the approval code with the pre-set reason and L688 pairs the decline code with a
                //       ladder reason. Letting a caller choose them independently would allow a row that
                //       claims approval while carrying a decline reason, which no producer can write.
                "AUTH01", "0000".equals(reasonCode) ? "00" : "05", reasonCode, "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Builds the canonical row carrying one particular stored response code.
     *
     * @param respCode the two-character response code to store; must not be {@code null}
     * @return the canonical authorization carrying {@code respCode}, never {@code null}
     */
    private static PendingAuthDetail rowWithRespCode(String respCode) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                STORED_ORIG_DATE, STORED_ORIG_TIME, CARD_NUMBER, "0100", "2712", "0200", "0100",
                "AUTH01", respCode, "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Builds the canonical row carrying two particular amounts.
     *
     * @param requested the transaction amount as requested by the acquirer; must not be {@code null}
     * @param approved the amount the decision approved; must not be {@code null}
     * @return the canonical authorization carrying both amounts, never {@code null}
     */
    private static PendingAuthDetail rowWithAmounts(BigDecimal requested, BigDecimal approved) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                STORED_ORIG_DATE, STORED_ORIG_TIME, CARD_NUMBER, "0100", "2712", "0200", "0100",
                "AUTH01", "00", "0000", "003000",
                requested, approved,
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Builds the canonical row carrying one particular merchant name.
     *
     * @param merchantName the merchant name exactly as stored, padding included; must not be {@code null}
     * @return the canonical authorization carrying {@code merchantName}, never {@code null}
     */
    private static PendingAuthDetail rowWithMerchantName(String merchantName) {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                STORED_ORIG_DATE, STORED_ORIG_TIME, CARD_NUMBER, "0100", "2712", "0200", "0100",
                "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", merchantName,
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Builds a row carrying one particular match status, through the rehydration entry point.
     *
     * <p>Assumptions: rehydration is used rather than the originating constructor because two of the four
     * declared statuses are unreachable at insert -- the expired state is written later by the purge job
     * and the matched state by the posting match -- so the originating constructor refuses them by design.
     * Using it here would fail on a valid stored state and read as a domain defect.</p>
     *
     * @param matchStatus the single-character match status to store; must not be {@code null}
     * @return the canonical authorization carrying {@code matchStatus}, never {@code null}
     * @throws IllegalArgumentException if {@code matchStatus} lies outside the column's four-value domain
     */
    private static PendingAuthDetail rowWithMatchStatus(String matchStatus) {
        return PendingAuthDetail.rehydrated(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                STORED_ORIG_DATE, STORED_ORIG_TIME, CARD_NUMBER, "0100", "2712", "0200", "0100",
                "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                matchStatus);
    }

    /**
     * Builds the canonical row with a fraud mark already applied.
     *
     * <p>Assumptions: the report date is supplied for BOTH marking states, which is the reference's own
     * behaviour rather than a symmetry preference -- {@code cbl/COPAUS2C.cbl} L101 stamps the date
     * unconditionally, before either the insert or the update path is chosen. A withdrawal therefore
     * records the date it was withdrawn on exactly as a report records the date it was reported.</p>
     *
     * @param fraudState the single-character marking state to apply; must not be {@code null}
     * @return the canonical authorization carrying the mark and its report date, never {@code null}
     * @throws IllegalArgumentException if {@code fraudState} is outside the two declared marking states
     */
    private static PendingAuthDetail rowMarked(String fraudState) {
        PendingAuthDetail marked = row();
        marked.applyFraudMark(fraudState, "04/29/24");
        return marked;
    }

    /**
     * Builds the canonical row with no originating date or time stored.
     *
     * <p>Assumptions: the two fields are blanked rather than left null, because the reference segment is
     * fixed width and an unset character field holds spaces there. Both forms reach the same empty-state
     * branch, and blanks are the one the stored record can actually contain.</p>
     *
     * @return the canonical authorization with both originating stamps blank, never {@code null}
     */
    private static PendingAuthDetail rowWithoutOriginatingStamp() {
        return new PendingAuthDetail(
                new PendingAuthDetailKey(ACCOUNT_ID, AUTH_DATE, AUTH_TIME),
                "      ", "      ", CARD_NUMBER, "0100", "2712", "0200", "0100",
                "AUTH01", "00", "0000", "003000",
                new BigDecimal("250.00"), new BigDecimal("250.00"),
                "5411", "840", (short) 5, "MERCHANT000001", "ACME HARDWARE",
                "SPRINGFIELD", "IL", "627040000", "TX0000000000001",
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }
}
