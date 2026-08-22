package com.carddemo.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.CardNumberMasker;
import com.carddemo.common.security.OpaqueIdentifier;
import com.carddemo.reporting.domain.AccountView;
import com.carddemo.reporting.domain.CardXrefView;
import com.carddemo.reporting.domain.CustomerView;
import com.carddemo.reporting.domain.StatementTransactionView;
import com.carddemo.reporting.dto.StatementDocument;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementResponse;
import com.carddemo.reporting.mapper.CobolEditMask;
import com.carddemo.reporting.mapper.StatementBandLayouts;
import com.carddemo.reporting.mapper.StatementHtmlMapper;
import com.carddemo.reporting.mapper.StatementTextMapper;
import com.carddemo.reporting.repository.StatementAccountRepository;
import com.carddemo.reporting.repository.StatementCardXrefRepository;
import com.carddemo.reporting.repository.StatementCustomerRepository;
import com.carddemo.reporting.repository.StatementTransactionRepository;
import com.carddemo.reporting.sink.S3StatementSink;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.domain.Limit;

/**
 * Pins how a statement is SELECTED, how much it reads, and what its artifact locations disclose.
 *
 * <p>Purpose: every case here corresponds to a specific defect that shipped in the earlier shape of
 * this class, and each is written to fail against that shape rather than merely to describe the new
 * one. Four of them are about selection: a statement was chosen by the masked card rendering, which
 * names a tail rather than a card, so a request for a card that did not exist could be answered with a
 * different cardholder's statement. Two are about boundedness: a heading-only read materialised every
 * transaction the card had. One is about disclosure: the artifact object key carried the account
 * identifier and the card's last four digits, and an object key is metadata that appears in a bucket
 * listing.
 *
 * <p>Assumptions: the repositories are mocked and the two keyed collaborators are REAL. The tokeniser
 * is constructed over fixed test key material rather than stubbed, because the property under test is
 * what a token does NOT contain -- a stubbed tokeniser returning a constant would satisfy every
 * absence assertion here while proving nothing about the derivation. The fingerprints are literal
 * sixty-four-character strings rather than computed digests, because this class asserts that the
 * service passes the fingerprint it was given through unchanged; how the digest is produced is a
 * property of {@code data-migration/sql/V1__reporting_views.sql} and is verified against a live engine
 * rather than here.
 *
 * <p>Refactoring Rationale: the fixture-driven groups below replace nothing that existed and are added
 * because the cases above assert what a service RETURNS while a statement run is judged by what it
 * EMITS. Two independent arities were removed from the baseline when this service was written, and each
 * removal is stated here separately because collapsing them produces a figure that is wrong on both
 * axes. The inner, same-card table is declared {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} at L228 of
 * {@code app/cbl/CBSTM03A.CBL} and its MEASURED overrun is 512 -- 512 transactions on one card render
 * and the 513th faults, which {@code tests/README.md} records at L70 to L82 under the marker
 * {@code F-STMT-INNER-OVERFLOW}. The outer, distinct-card table is declared
 * {@code 05 WS-CARD-TBL OCCURS 51 TIMES} at L226 with its parallel counter
 * {@code 05 WS-TRN-TBL-CTR OCCURS 51 TIMES} at L232, and both its declaration and its measurement are
 * 51 -- 51 distinct cards render and the 52nd faults, recorded at the same lines under
 * {@code F-STMT-OUTER-OVERFLOW}. The declared inner arity, the measured inner overrun and the outer
 * card limit are three separate numbers; this file uses 10 only as the declared inner arity and never
 * as a bound. The migrated service holds no table of either kind, which is divergence {@code D-2} in
 * {@code docs/architecture/cobol-to-service-traceability.md}; the baseline under {@code app/} is read as
 * the specification and is never edited, so what is asserted here is the migrated behaviour and never a
 * repair of the reference.
 *
 * <p>Trade-offs: the fixtures driven below EXCEED both thresholds where the COBOL suite deliberately
 * stays under them. {@code tests/README.md} L78 to L80 keeps every one of its fixtures safely under both
 * bounds, and it must: the baseline faults past either one, so a larger fixture there would crash the
 * program rather than measure it. This tree has the opposite obligation, because the property under test
 * is the ABSENCE of a bound, and a fixture inside both bounds cannot distinguish that absence from a
 * limit that happens to be larger. The two choices are opposite on purpose and the difference is not a
 * drift to be aligned away: {@code trnxfile.txt} supplies 600 transactions on one card against a
 * measured 512, and {@code xreffile.txt} supplies 88 distinct cards against a declared and measured 51,
 * so an off-by-one cap at either boundary cannot pass.
 *
 * <p>Alternatives Considered: modelling the baseline's file-handling subprogram as one collaborator with
 * an operation code, mirroring its own shape. {@code app/cbl/CBSTM03B.CBL} is a called subprogram rather
 * than a job -- L114 declares {@code PROCEDURE DIVISION USING LK-M03B-AREA}, where
 * {@code app/cbl/CBSTM03A.CBL} L262 declares a bare {@code PROCEDURE DIVISION} -- and it dispatches on
 * the data-definition name first, at L118 over the four names at L119 to L126, and only then on the
 * operation code its L103 to L108 declares as open, close, read, keyed read, write and rewrite. The
 * thirteen {@code CALL 'CBSTM03B'} sites at L351, L377, L401, L734, L746, L769, L787, L805, L835, L860,
 * L877, L893 and L909 are therefore thirteen calls into four different inputs. Modelling that as one
 * mock taking an operation code was rejected: it would reproduce the dispatch inside the stub itself, so
 * a case could pass while the service read the wrong input. Four separately mocked read-only repositories
 * make the input part of the method being stubbed, and only the four operations this module performs --
 * open, close, read and keyed read -- have any surface at all, because writing belongs to a path this
 * module does not own.
 *
 * <p>Trade-offs: the four mappers are REAL and not mocked, and the cost is that a failure here can be a
 * fault of the band assembler rather than of the service. That cost is accepted because the two claims
 * this file carries about the removed arities are claims about complete, correct OUTPUT: a mocked mapper
 * would return whatever it was told to, so a run that dropped every second transaction would satisfy
 * every assertion written against it. Per-band byte arithmetic is not re-derived here -- it belongs to
 * {@code StatementBandLayoutsTest}, {@code StatementTextMapperTest}, {@code StatementHtmlMapperTest} and
 * {@code CobolEditMaskTest} in the sibling mapper package -- so the cases below read band items through
 * the band descriptors those classes own rather than through offsets written out again.
 *
 * <p>Assumptions: the width assertions govern the record the service EMITS and not the line the shipped
 * oracle stores, and the two are different forms of the same statement. The emitted plain-text record is
 * exactly 80 bytes, declared {@code 01 FD-STMTFILE-REC PIC X(80)} at L45 of
 * {@code app/cbl/CBSTM03A.CBL} and confirmed by {@code LRECL=80} at L73 and L89 of
 * {@code app/jcl/CREASTMT.JCL}; the emitted markup record is exactly 100 bytes, declared
 * {@code 01 FD-HTMLFILE-REC PIC X(100)} at L47, restated as {@code 05 HTML-FIXED-LN PIC X(100)} at L149
 * and confirmed by {@code LRECL=100} at L94. The {@code LRECL=80} at L69 sits on the deletion step's
 * markup stanza, where {@code IEFBR14} writes no data at all, so it is inert and is not an alternative
 * markup width. Both goldens are right-trimmed rather than fixed width, because the runtime drops
 * trailing blanks as it writes: {@code tests/golden/statement/happy_path/statement.txt.expected} stores
 * 22 lines measuring 9, 14, 16, 16, 23, 31, 32, 46, 49, three of 79 and ten of 80, and
 * {@code statement.html.expected} stores 97 lines from 4 characters up to 85. Any comparison against
 * either artifact therefore normalises the emitted record DOWN by the same trim, and an assertion that a
 * stored golden LINE is 80 or 100 bytes long could never pass. The plain-text side needs no padding at
 * all for a separate reason worth stating: all seventeen {@code ST-LINE} bands are natively exactly 80,
 * which is the exact inverse of the 133-column report, where six of seven bands are natively short and
 * are padded to reach the declared width.
 *
 * <p>Assumptions: an exhausted driving cursor and an unresolved dimension are opposite outcomes, and the
 * difference is the whole of the missing-row policy. The cross-reference read is the only statement read
 * paragraph carrying an end-of-file arm -- L353 to L362 of {@code app/cbl/CBSTM03A.CBL}, whose
 * {@code WHEN '10'} at L357 moves the end-of-file flag -- so its exhaustion ENDS the run normally with
 * every statement already produced left intact. The customer read at L368 to L390 and the account read
 * at L392 to L414 carry no such arm: their {@code EVALUATE} blocks at L379 to L386 and L403 to L410 have
 * only a success arm and a catch-all, so any other status displays a message and performs the abend
 * paragraph at L921 to L923. A dimension missing for an existing cross-reference row is a
 * referential-integrity violation rather than an absence to render around, which is why the cases below
 * assert a failure carrying the four {@code AbendDetail} fields and never an empty document.
 *
 * <p>Assumptions: a rerun REPLACES both artifacts and never appends to them.
 * {@code app/jcl/CREASTMT.JCL} runs {@code IEFBR14} as its own step at L66 to L75, gated
 * {@code COND=(0,NE)} and holding {@code DISP=(MOD,DELETE,DELETE)} on the markup output at L67 and on
 * the plain-text output at L72, and only then does L79 onward run the generator with
 * {@code DISP=(NEW,CATLG,DELETE)} on both. Two runs of the night therefore leave one copy of each
 * artifact rather than two, which is asserted below by running the writer twice into one destination.
 *
 * <p>Assumptions: the processing timestamp is carried at reduced precision by the baseline's own sort
 * step, and that is reproduced as an observation rather than put right anywhere. L54 of
 * {@code app/jcl/CREASTMT.JCL} reformats the record as
 * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)}, which populates 328 of the 350 bytes: with
 * {@code TRAN-CARD-NUM} at 263 to 278, {@code TRAN-ORIG-TS} at 279 to 304 and {@code TRAN-PROC-TS} at
 * 305 to 330 by the declared widths of {@code app/cpy/CVTRA05Y.cpy}, the output's 305 to 330 receives
 * only 24 of its 26 characters and the final two microsecond digits are lost. The statement bands render
 * neither timestamp, so the reduced precision cannot reach either artifact at all, and the case below
 * asserts that absence so that a band added later cannot introduce the truncation unnoticed.
 *
 * <p>Assumptions: a statement's total covers its own card alone. {@code MOVE ZERO TO WS-TOTAL-AMT} at
 * L325 of {@code app/cbl/CBSTM03A.CBL} stands immediately before the traversal the mainline performs at
 * L326, so the accumulator resets once per cross-reference row. A single-card fixture cannot observe
 * that reset -- every total is correct when there is only one -- so the case below drives four cards
 * whose fixture totals are all different and asserts each card's own trailer independently.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause; the methods below carry their own where they have any.
 */
class StatementServiceTest {

    /** A card number from the published demonstration seed, {@code app/data/ASCII/cardxref.txt}. */
    private static final String SEED_CARD_NUMBER = "4859452612877065";

    /** The masked rendering the reporting relations publish for {@link #SEED_CARD_NUMBER}. */
    private static final String MASKED_CARD = "************7065";

    /**
     * The keyed fingerprint standing for {@link #SEED_CARD_NUMBER}.
     *
     * <p>Assumptions: sixty-four hexadecimal characters, which is the width the view's
     * {@code encode(sha256(...), 'hex')} produces and the width {@code CardXrefView} maps. A shorter
     * stand-in would let a mapping that truncated the column pass.</p>
     */
    private static final String FINGERPRINT = "a".repeat(63) + "1";

    /** A second card's fingerprint, so a two-card account can be expressed. */
    private static final String OTHER_FINGERPRINT = "b".repeat(63) + "2";

    /**
     * The measured transaction threshold of the baseline's inner same-card table.
     *
     * <p>Assumptions: 512, from {@code tests/README.md}, which records that one card renders up to 512
     * transactions and that the 513th overruns {@code WS-TRAN-TBL} at line 228 of
     * {@code app/cbl/CBSTM03A.CBL} and terminates the process. It is the MEASURED threshold rather than
     * the declared arity of that table, and the two are different numbers -- the declaration reads
     * {@code OCCURS 10 TIMES} -- which is exactly why the measured figure is cited to the document that
     * measured it rather than derived from the copybook.</p>
     */
    private static final int BASELINE_INNER_TABLE_THRESHOLD = 512;

    /**
     * The declared and measured card arity of the baseline's outer table.
     *
     * <p>Assumptions: 51, from {@code WS-CARD-TBL OCCURS 51 TIMES} at line 226 of
     * {@code app/cbl/CBSTM03A.CBL} and corroborated as measured in {@code tests/README.md}, which records
     * that the 52nd distinct card overruns it. This is the one of the three arity figures where the
     * declaration and the measurement agree, and it is cited to both so a reader need not decide which
     * to trust.</p>
     */
    private static final int BASELINE_OUTER_TABLE_ARITY = 51;

    /** The account the seeded card is issued against, at the declared eleven positions. */
    private static final long ACCOUNT_ID = 21_820_493_291L;

    /** The customer the cross-reference names. */
    private static final long CUSTOMER_ID = 100_000_001L;

    /** A stored artifact size, standing for whatever a run happens to have written. */
    private static final long ARTIFACT_SIZE = 4_096L;

    /**
     * The instant the store reports an artifact was written, in the twenty-six-character form.
     *
     * <p>Assumptions: a fixed value rather than a clock reading, so the case asserting that the
     * response reports the STORE's instant can assert an exact value. A clock reading would only let
     * the case assert that something non-blank arrived, which the 26 blanks this replaces would also
     * have satisfied.</p>
     */
    private static final String WRITTEN_AT = "2022-07-18 03:14:15.926535";

    /** The key prefix artifacts sit under, matching the base configuration document. */
    private static final String PREFIX = "statements/";

    /**
     * The run the manifest names in this class.
     *
     * <p>Assumptions: a FIXED identifier rather than one minted per case, so the key every assertion
     * below expects is reproducible from the source alone. The production identifier is random per run --
     * which is what stops a rerun overwriting the run it replaces -- and a case asserting a key would
     * otherwise have to recover the identifier from the value under test before it could compare
     * anything.</p>
     */
    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";

    /** The key prefix that run's three objects sit under. */
    private static final String RUN_PREFIX = PREFIX + StatementService.RUN_SEGMENT + RUN_ID + "/";

    /**
     * The audience every case here reads under, bar the one asserting what a cardholder is told.
     *
     * <p>Assumptions: the operator audience is the default for this class because it is the audience
     * every artifact assertion needs -- a cardholder response carries no location, no instant and no
     * position at all, so a class defaulting to it would assert nothing about any of them. The one case
     * that names {@link StatementService.ArtifactAudience#CARDHOLDER} asserts exactly that
     * withholding.</p>
     */
    private static final StatementService.ArtifactAudience OPERATOR =
            StatementService.ArtifactAudience.OPERATOR;

    /**
     * Fixed tokeniser key material, at the tokeniser's minimum length.
     *
     * <p>Assumptions: fixed rather than random so a token asserted here is reproducible from the
     * source alone, and declared here rather than defaulted inside the tokeniser, because a tokeniser
     * that defaulted a key is how a development default becomes the committed secret.</p>
     */
    private static final byte[] ARTIFACT_KEY =
            "carddemo-reporting-artifact-test!".repeat(2).getBytes(StandardCharsets.UTF_8);

    /** The directory on the test classpath holding the four fixed-width statement fixtures. */
    private static final String FIXTURE_DIRECTORY = "fixtures";

    /** The 350-byte transaction fixture, which is the input side of BOTH removed-arity cases. */
    private static final String TRANSACTION_FIXTURE = "trnxfile.txt";

    /** The 50-byte cross-reference fixture, which is the driving cursor's source. */
    private static final String CROSS_REFERENCE_FIXTURE = "xreffile.txt";

    /** The 500-byte customer fixture, supplying every printed heading attribute. */
    private static final String CUSTOMER_FIXTURE = "custfile.txt";

    /** The 300-byte account fixture, supplying the heading balance at its declared scale. */
    private static final String ACCOUNT_FIXTURE = "acctfile.txt";

    /**
     * The one card of {@link #TRANSACTION_FIXTURE} carrying more rows than the inner table admits.
     *
     * <p>Assumptions: this is a card of the published demonstration seed and it is named as a literal
     * rather than discovered as "the card with the most rows", so a fixture edit that moved the bulk to
     * a different card fails the case instead of silently retargeting it.</p>
     */
    private static final String INNER_OVERFLOW_CARD = "0500024453765740";

    /**
     * How many rows {@link #TRANSACTION_FIXTURE} holds for {@link #INNER_OVERFLOW_CARD}.
     *
     * <p>Assumptions: 600, measured on the shipped fixture, which stands decisively above the measured
     * inner overrun of {@link #BASELINE_INNER_TABLE_THRESHOLD} rather than one past it. The case one past
     * it already exists above; this figure is what an off-by-one cap cannot satisfy.</p>
     */
    private static final int INNER_OVERFLOW_FIXTURE_ROWS = 600;

    /**
     * How many distinct cards {@link #CROSS_REFERENCE_FIXTURE} holds.
     *
     * <p>Assumptions: 88, measured on the shipped fixture, standing decisively above the declared and
     * measured outer card limit of {@link #BASELINE_OUTER_TABLE_ARITY}.</p>
     */
    private static final int OUTER_OVERFLOW_FIXTURE_CARDS = 88;

    /** How many rows {@link #TRANSACTION_FIXTURE} holds across all of its cards. */
    private static final int TRANSACTION_FIXTURE_ROWS = 700;

    /**
     * The card whose heading and lines reproduce every width class the shipped oracle stores.
     *
     * <p>Assumptions: this card's account balance is positive and exactly one of its four rows is
     * negative, which is what lets one run exercise both the trailing-blank and the trailing-minus form
     * of the two thirteen-character masks.</p>
     */
    private static final String WIDTH_CLASS_CARD = "4859452612877065";

    /**
     * The four cards whose fixture totals are all different, in ascending walk order.
     *
     * <p>Assumptions: four distinct totals rather than four cards, because the property under test is
     * that no total survives into the next statement; two cards sharing a total would let a leak pass on
     * one of them.</p>
     */
    private static final List<String> DISTINCT_TOTAL_CARDS = List.of(
            "9900001020000001", "9900001010000029", "9900000000000502", "4859452612877065");

    private StatementTransactionRepository transactions;

    private StatementCardXrefRepository cardXrefs;

    private StatementCustomerRepository customers;

    private StatementAccountRepository accounts;

    private ArtifactStore artifacts;

    private StatementService service;

    /**
     * Builds the service over mocked reads, a mocked artifact store and a real tokeniser.
     *
     * <p>Assumptions: the store is stubbed to HOLD both artifacts by default, because that is the
     * state every case other than the two absence cases is about, and a default of absent would make
     * each of them restate the same stubbing. The two absence cases override it explicitly, so the
     * state they exercise is visible at the case rather than inherited from here.</p>
     */
    @BeforeEach
    void setUp() {
        transactions = Mockito.mock(StatementTransactionRepository.class);
        cardXrefs = Mockito.mock(StatementCardXrefRepository.class);
        customers = Mockito.mock(StatementCustomerRepository.class);
        accounts = Mockito.mock(StatementAccountRepository.class);
        artifacts = Mockito.mock(ArtifactStore.class);
        // WHY : Assumptions: the two ARTIFACTS are stubbed present and the INDEX is stubbed absent by
        //       default, which is deliberately the awkward combination. It is the state a deployment is
        //       in between a run written by an earlier revision and the first run of this one, and
        //       defaulting to it means every case that does not care about positions still asserts that
        //       an absent index degrades to no position rather than to a failure.
        when(artifacts.describe(anyString())).thenAnswer(call -> Optional.of(
                new ArtifactStore.ArtifactDescriptor(
                        call.getArgument(0), ARTIFACT_SIZE, WRITTEN_AT)));
        when(artifacts.describe(RUN_PREFIX + StatementService.INDEX_OBJECT))
                .thenReturn(Optional.empty());
        // WHY : Assumptions: the manifest is stubbed to name ONE published run, because that is the
        //       state every artifact case is about -- a run's objects are addressable only through it.
        //       The cases that exercise an unpublished run and a corrupt manifest override this stub
        //       explicitly, so the state each of them turns on is visible at the case.
        when(artifacts.readRange(eq(StatementService.manifestKey(PREFIX)), anyLong(), anyLong()))
                .thenReturn(StatementService.encodeManifest(RUN_ID));
        service = new StatementService(transactions, cardXrefs, customers, accounts,
                PREFIX, artifacts, new OpaqueIdentifier(ARTIFACT_KEY));
    }

    // WHY : Assumptions: the two halves of exactly-one-of are asserted separately and each names the
    //       component a caller has to act on. One case covering both would pass against an
    //       implementation that refused every request with the same message, which is a refusal a
    //       caller cannot correct from.
    /**
     * Asserts that a request naming neither selector is refused, naming the card component.
     */
    @Test
    @DisplayName("a request naming neither a card nor an account is refused")
    void aRequestNamingNeitherSelectorIsRefused() {
        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.describe(new StatementRequest(null, null), OPERATOR))
                .satisfies(refusal -> assertThat(refusal.fields()).contains("cardNumber"));

        verify(cardXrefs, never()).resolveByWholeCardNumber(anyString());
        verify(cardXrefs, never()).findCardsOfAccount(anyLong(), any());
    }

    /**
     * Asserts that a request naming both selectors is refused, naming the account component.
     */
    @Test
    @DisplayName("a request naming both a card and an account is refused")
    void aRequestNamingBothSelectorsIsRefused() {
        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.describe(
                        new StatementRequest(SEED_CARD_NUMBER, String.valueOf(ACCOUNT_ID)), OPERATOR))
                .satisfies(refusal -> assertThat(refusal.fields()).contains("accountId"));

        verify(cardXrefs, never()).resolveByWholeCardNumber(anyString());
        verify(cardXrefs, never()).findCardsOfAccount(anyLong(), any());
    }

    // WHY : Refactoring Rationale: this is the broken-object-selection case. The earlier shape reduced
    //       the caller's number to its last four digits and looked a statement up by that display
    //       mask, so where the requested card did not exist and a different cardholder's card shared
    //       the tail, the caller received that cardholder's statement with nothing recording the
    //       substitution. The assertion is on the ARGUMENT the repository receives, because that is
    //       the only place the difference between a card and a tail is visible from outside.
    /**
     * Asserts that a card is resolved by its whole number and never by a masked rendering.
     */
    @Test
    @DisplayName("a card is resolved by the whole number, never by its masked tail")
    void aCardIsResolvedByTheWholeNumber() {
        stubOneCard();

        service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        ArgumentCaptor<String> selector = ArgumentCaptor.forClass(String.class);
        verify(cardXrefs).resolveByWholeCardNumber(selector.capture());
        assertThat(selector.getValue())
                .as("the whole number selects the card")
                .isEqualTo(SEED_CARD_NUMBER);
        assertThat(selector.getValue())
                .as("no masked rendering may reach a selection query")
                .doesNotContain("*");
    }

    /**
     * Asserts that a card the cross-reference does not hold is reported absent rather than substituted.
     */
    @Test
    @DisplayName("a card that does not resolve is reported absent")
    void aCardThatDoesNotResolveIsReportedAbsent() {
        when(cardXrefs.resolveByWholeCardNumber(anyString())).thenReturn(Optional.empty());

        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR));

        verify(cardXrefs, never()).findById(anyString());
    }

    // WHY : Assumptions: the bound handed to the cross-reference is asserted to be TWO and not one.
    //       One row is all a happy path needs, but a bound of one cannot tell an account holding a
    //       single card from an account holding several -- it would answer from the first either way,
    //       which is the outcome the refusal below exists to prevent.
    /**
     * Asserts that an account selector resolves through a bounded two-row cross-reference read.
     */
    @Test
    @DisplayName("an account selector resolves through a two-row bounded read")
    void anAccountSelectorResolvesThroughABoundedRead() {
        stubOneCard();
        when(cardXrefs.findCardsOfAccount(eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(List.of(xref(FINGERPRINT)));

        service.describe(new StatementRequest(null, String.valueOf(ACCOUNT_ID)), OPERATOR);

        ArgumentCaptor<Limit> bound = ArgumentCaptor.forClass(Limit.class);
        verify(cardXrefs).findCardsOfAccount(eq(ACCOUNT_ID), bound.capture());
        assertThat(bound.getValue().max())
                .as("two rows are read so a multi-card account is detectable")
                .isEqualTo(2);
    }

    /**
     * Asserts that an account holding more than one card is refused rather than answered from one.
     */
    @Test
    @DisplayName("an account holding two cards is refused, naming the account")
    void anAccountHoldingTwoCardsIsRefused() {
        when(cardXrefs.findCardsOfAccount(eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(List.of(xref(FINGERPRINT), xref(OTHER_FINGERPRINT)));

        assertThatExceptionOfType(ClientInputException.class)
                .isThrownBy(() -> service.describe(
                        new StatementRequest(null, String.valueOf(ACCOUNT_ID)), OPERATOR))
                .satisfies(refusal -> assertThat(refusal.fields()).contains("accountId"));

        verify(transactions, never()).aggregateByCardFingerprint(anyString());
    }

    /**
     * Asserts that an account holding no card is reported absent.
     */
    @Test
    @DisplayName("an account holding no card is reported absent")
    void anAccountHoldingNoCardIsReportedAbsent() {
        when(cardXrefs.findCardsOfAccount(eq(ACCOUNT_ID), any(Limit.class)))
                .thenReturn(List.of());

        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> service.describe(
                        new StatementRequest(null, String.valueOf(ACCOUNT_ID)), OPERATOR));
    }

    // WHY : Refactoring Rationale: this is the boundedness case. The earlier shape opened the card's
    //       whole transaction stream to produce a heading, so a summary carrying one total and one
    //       count cost a read proportional to the card's whole history. The assertion is that no row
    //       read happens at all, which is stronger than asserting the aggregate was used: an
    //       implementation could call both.
    /**
     * Asserts that a heading-only read touches the aggregate alone and reads no transaction row.
     */
    @Test
    @DisplayName("a heading-only read materialises no transaction row")
    void aHeadingOnlyReadMaterialisesNoTransactionRow() {
        stubOneCard();

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.transactionCount()).isEqualTo(7);
        assertThat(response.totalAmount()).isEqualTo(Money.of("-1234.56"));
        verify(transactions).aggregateByCardFingerprint(FINGERPRINT);
        verify(transactions, never()).findWindowByCardFingerprint(anyString(), anyString(), anyInt());
        verify(transactions, never()).streamByCardFingerprint(anyString());
    }

    // WHY : Assumptions: the count in the heading is asserted against the AGGREGATE and not against
    //       the number of rows in the body. Those two agree on every statement short of the cap and
    //       disagree on exactly the statements where the difference matters, so asserting the capped
    //       case is what makes truncation measurable from outside.
    /**
     * Asserts that a capped window still reports the card's true transaction count.
     */
    @Test
    @DisplayName("a capped transaction window still reports the true count")
    void aCappedWindowStillReportsTheTrueCount() {
        stubOneCard();
        when(transactions.aggregateByCardFingerprint(FINGERPRINT))
                .thenReturn(aggregate("-1234.56", 4_211L));
        when(transactions.findWindowByCardFingerprint(
                eq(FINGERPRINT), anyString(), eq(StatementService.MAX_RESPONSE_TRANSACTIONS)))
                .thenReturn(List.of());

        StatementDocument document = service.compose(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(document.statement().transactionCount())
                .as("the heading reports what the card holds, not what the body carries")
                .isEqualTo(4_211);
        verify(transactions).findWindowByCardFingerprint(
                FINGERPRINT, "", StatementService.MAX_RESPONSE_TRANSACTIONS);
    }

    // WHY : Refactoring Rationale: this is the metadata-disclosure case, and it survives a change of
    //       design. The earlier object key was composed from the account identifier and the card's
    //       last four digits, and an object key appears in a bucket listing, an access log, a
    //       lifecycle report and a storage inventory export -- so listing one prefix enumerated the
    //       portfolio without a single object being read. The published value is now a served path
    //       rather than an object location, and it reaches a wider audience than a key does: it is
    //       returned to a browser, kept in its history and written into every intermediary's access
    //       log. So the absence assertions are kept verbatim and only the prefix assertion moves.
    /**
     * Asserts that neither artifact location carries an identifier or any card-number fragment.
     */
    @Test
    @DisplayName("an artifact location carries no account identifier and no card-number fragment")
    void anArtifactLocationCarriesNoIdentifier() {
        stubOneCard();

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        for (String uri : List.of(response.plainTextUri(), response.htmlUri())) {
            assertThat(uri).startsWith(StatementService.ARTIFACT_LOCATION_PREFIX);
            assertThat(uri)
                    .as("no location may carry the account identifier")
                    .doesNotContain(String.valueOf(ACCOUNT_ID));
            assertThat(uri)
                    .as("no location may carry any part of the card number")
                    .doesNotContain(SEED_CARD_NUMBER)
                    .doesNotContain("7065")
                    .doesNotContain("485945");
            assertThat(uri)
                    .as("no location may carry the fingerprint either, which names the card exactly")
                    .doesNotContain(FINGERPRINT);
        }
        assertThat(response.plainTextUri())
                .as("the two artifacts of one run are two locations, not one repeated")
                .isNotEqualTo(response.htmlUri());
    }

    /**
     * Asserts that the artifact selector is the tokeniser's declared width and nothing longer.
     */
    @Test
    @DisplayName("the artifact selector is exactly the tokeniser's declared width")
    void theArtifactSelectorIsTheDeclaredWidth() {
        stubOneCard();

        String uri = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR).plainTextUri();
        String selector = uri.substring(StatementService.ARTIFACT_LOCATION_PREFIX.length());

        assertThat(selector).hasSize(OpaqueIdentifier.TOKEN_LENGTH);
    }

    // WHY : Refactoring Rationale: this is the case the retired shape could not have passed, and it is
    //       the whole point of the change. The response used to compose a per-card location from a
    //       token, while the writer publishes exactly two run-wide objects, so every published
    //       location named an object nothing writes. The assertion follows the location the response
    //       returns all the way back to a key and compares it with the writer's OWN constant, so the
    //       two sides can no longer be changed independently.
    /**
     * Asserts that each published location resolves to the object the statement writer writes.
     */
    @Test
    @DisplayName("a published artifact location resolves to the object the writer wrote")
    void aPublishedLocationResolvesToTheWrittenObject() {
        stubOneCard();

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(service.resolveArtifactKey(selectorOf(response.plainTextUri())))
                .as("the plain-text location resolves to the object S3StatementSink writes")
                .contains(RUN_PREFIX + S3StatementSink.PLAIN_TEXT_OBJECT);
        assertThat(service.resolveArtifactKey(selectorOf(response.htmlUri())))
                .as("the markup location resolves to the object S3StatementSink writes")
                .contains(RUN_PREFIX + S3StatementSink.HTML_OBJECT);
    }

    // WHY : Assumptions: absence is asserted on ALL THREE members together, because the three are one
    //       statement about the world -- there is no artifact -- and a response reporting an absent
    //       location beside a present production instant would be reporting a document that both does
    //       and does not exist.
    /**
     * Asserts that an absent artifact is reported as absent rather than as an unreachable location.
     */
    @Test
    @DisplayName("no stored artifact yields no location and no production instant")
    void noStoredArtifactYieldsNoLocation() {
        stubOneCard();
        when(artifacts.describe(anyString())).thenReturn(Optional.empty());

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.plainTextUri())
                .as("a location that resolves to nothing is worse than no location")
                .isNull();
        assertThat(response.htmlUri()).isNull();
        assertThat(response.generatedAt())
                .as("26 blanks claimed a production instant for a document nothing produced")
                .isNull();
    }

    // WHY : Assumptions: the two artifacts are described independently, so this case stubs ONE of them
    //       present. A lifecycle rule expiring one object of the published run leaves the store in
    //       exactly this state, and an implementation that reported both on the strength of either
    //       would publish a location that resolves to nothing. Note what this no longer covers: a run
    //       INTERRUPTED between its two writes used to reach a reader this way and no longer can,
    //       because an incomplete run never reaches the manifest at all.
    /**
     * Asserts that one stored artifact is reported without the other being invented.
     */
    @Test
    @DisplayName("one stored artifact is reported without inventing the other")
    void oneStoredArtifactIsReportedAlone() {
        stubOneCard();
        when(artifacts.describe(anyString())).thenReturn(Optional.empty());
        when(artifacts.describe(RUN_PREFIX + S3StatementSink.PLAIN_TEXT_OBJECT))
                .thenReturn(Optional.of(new ArtifactStore.ArtifactDescriptor(
                        RUN_PREFIX + S3StatementSink.PLAIN_TEXT_OBJECT, ARTIFACT_SIZE, WRITTEN_AT)));

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.plainTextUri()).isNotNull();
        assertThat(response.htmlUri())
                .as("the markup artifact is absent and is reported absent")
                .isNull();
        assertThat(response.generatedAt())
                .as("the instant comes from the artifact that exists")
                .isEqualTo(WRITTEN_AT);
    }

    /**
     * Asserts that the production instant is the store's own record and not a clock reading.
     */
    @Test
    @DisplayName("the production instant is the artifact's own write instant")
    void theProductionInstantIsTheStoreInstant() {
        stubOneCard();

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.generatedAt()).isEqualTo(WRITTEN_AT);
    }

    // WHY : Assumptions: the disposition is asserted at the RESPONSE boundary, because that is where the
    //       narrowing decision is taken -- the two request-edge operations return a total without
    //       emitting an artifact, so nothing downstream of them would ever narrow it. The figure used is
    //       the smallest one requiring a tenth integer position, so the case pins the boundary and not an
    //       arbitrary excess.
    // WHY : Refactoring Rationale: this asserted a raised failure, and raising discarded the heading,
    //       the name, the line count and both artifact locations along with the total -- every one of
    //       which was already computed -- in exchange for an internal error. The reference discards the
    //       high-order digits it cannot carry and reports the rest, which is what the register entry for
    //       edit-mask overflow records and what this case now asserts.
    /**
     * Asserts that a total too large for the reference regime is narrowed rather than refused.
     */
    @Test
    @DisplayName("a statement total needing a tenth integer digit is narrowed")
    void aTotalNeedingATenthIntegerDigitIsNarrowed() {
        stubOneCard();
        when(transactions.aggregateByCardFingerprint(FINGERPRINT))
                .thenReturn(aggregate("1000000001.23", 3L));

        StatementResponse response =
                service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.totalAmount())
                .as("the tenth integer digit is discarded and the cents survive")
                .isEqualTo(Money.of(new BigDecimal("1.23")));
        assertThat(response.transactionCount())
                .as("everything else the response carries is still answered")
                .isEqualTo(3);
        assertThat(response.plainTextUri()).isNotNull();
    }

    // WHY : Assumptions: the negative vector is asserted separately because it is the one an
    //       always-positive modulus would get wrong, and it would get it wrong in the direction that
    //       matters -- a cardholder's credit reported as a charge of nearly a thousand million.
    /**
     * Asserts that an over-wide credit total stays a credit when it is narrowed.
     */
    @Test
    @DisplayName("an over-wide credit total is narrowed and stays a credit")
    void anOverWideCreditTotalStaysACredit() {
        stubOneCard();
        when(transactions.aggregateByCardFingerprint(FINGERPRINT))
                .thenReturn(aggregate("-1000000001.23", 3L));

        assertThat(service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR)
                .totalAmount())
                .isEqualTo(Money.of(new BigDecimal("-1.23")));
    }

    /**
     * Asserts that the greatest total the reference regime can hold is still published.
     */
    @Test
    @DisplayName("a statement total at nine integer digits is published")
    void aTotalAtNineIntegerDigitsIsPublished() {
        stubOneCard();
        when(transactions.aggregateByCardFingerprint(FINGERPRINT))
                .thenReturn(aggregate("999999999.99", 3L));

        assertThat(service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR).totalAmount())
                .isEqualTo(Money.of(new BigDecimal("999999999.99")));
    }

    // WHY : Assumptions: an unknown selector and an absent artifact are asserted to be the SAME
    //       refusal, because telling them apart would tell a caller which selectors are real. The
    //       fabricated selector is the right length, so the case cannot pass merely because a length
    //       check rejected it.
    /**
     * Asserts that a selector this service did not mint resolves to nothing.
     */
    @Test
    @DisplayName("a selector this service did not mint is refused")
    void anUnmintedSelectorIsRefused() {
        assertThat(service.resolveArtifactKey("f".repeat(OpaqueIdentifier.TOKEN_LENGTH))).isEmpty();
        assertThat(service.resolveArtifactKey(null)).isEmpty();
        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> service.collectArtifact(
                        "f".repeat(OpaqueIdentifier.TOKEN_LENGTH)));
        verify(artifacts, never()).open(anyString());
    }

    // WHY : Assumptions: the key the store is asked for is CAPTURED rather than assumed, because the
    //       property under test is that no part of a caller-supplied selector reaches an object key --
    //       an implementation that concatenated the selector into a key would satisfy every assertion
    //       above and still let a caller address another run's artifact.
    /**
     * Asserts that collection opens the resolved key and never a caller-composed one.
     */
    @Test
    @DisplayName("collection opens the resolved key and nothing derived from the selector")
    void collectionOpensTheResolvedKey() {
        stubOneCard();
        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);
        when(artifacts.open(anyString())).thenReturn(new ArtifactStore.OpenArtifact(
                ARTIFACT_SIZE, new ByteArrayInputStream(new byte[] {0})));

        service.collectArtifact(selectorOf(response.htmlUri()));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(artifacts).open(key.capture());
        assertThat(key.getValue()).isEqualTo(RUN_PREFIX + S3StatementSink.HTML_OBJECT);
    }

    // WHY : Refactoring Rationale: this is the confidentiality case a review filed against the
    //       statement surface, and it is the reason the audience parameter exists. The two artifacts
    //       hold EVERY cardholder's statement in the portfolio and the index positions are coordinates
    //       into them, so a per-card answer carrying either handed an ordinary caller the address of
    //       the whole run -- one card it was entitled to, in exchange for all of them. The assertion is
    //       on all five run-wide members together, because withholding four of them and publishing the
    //       fifth is the same disclosure with one more step.
    /**
     * Asserts that a cardholder audience is told nothing about the run-wide artifacts.
     */
    @Test
    @DisplayName("a cardholder audience is told no location, no instant and no position")
    void aCardholderIsToldNothingOfTheRunWideArtifacts() {
        stubOneCard();
        stubIndex(new StatementIndexEntry(FINGERPRINT, 41L, 7L));

        StatementResponse response = service.describe(
                new StatementRequest(SEED_CARD_NUMBER, null),
                StatementService.ArtifactAudience.CARDHOLDER);

        assertThat(response.plainTextUri()).isNull();
        assertThat(response.htmlUri()).isNull();
        assertThat(response.generatedAt()).isNull();
        assertThat(response.firstRecord())
                .as("a position is a coordinate into the run-wide artifact and discloses it too")
                .isNull();
        assertThat(response.recordCount()).isNull();
        assertThat(response.totalAmount())
                .as("the card's own figures are still the answer to the card's own question")
                .isEqualTo(Money.of(new BigDecimal("-1234.56")));
        assertThat(response.transactionCount()).isEqualTo(7);
        // WHY : Assumptions: the store is asserted UNTOUCHED rather than merely unreported, because an
        //       implementation that read the manifest, both artifacts and the index and then discarded
        //       what it read would satisfy every assertion above while spending four requests per
        //       statement on fields it is not allowed to return.
        Mockito.verifyNoInteractions(artifacts);
    }

    // WHY : Refactoring Rationale: this is the coherence case, and it is the one the retired shape
    //       could not express. The three objects of a run were written to fixed keys, so each became
    //       visible the moment it was written and a reader could hold the previous run's index over the
    //       new run's artifact -- reporting a position that addressed an unrelated cardholder. A run is
    //       now addressable only through the manifest, so this case asserts the manifest is what the
    //       response resolves against: ONE read of it, and every key derived from that one answer.
    /**
     * Asserts that one response resolves the manifest once and reads only that run's objects.
     */
    @Test
    @DisplayName("one response resolves one run and reads only that run's objects")
    void oneResponseResolvesOneRun() {
        stubOneCard();
        stubIndex(new StatementIndexEntry(FINGERPRINT, 41L, 7L));

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.firstRecord()).isEqualTo(41L);
        verify(artifacts, Mockito.times(1))
                .readRange(eq(StatementService.manifestKey(PREFIX)), anyLong(), anyLong());
        verify(artifacts).describe(RUN_PREFIX + S3StatementSink.PLAIN_TEXT_OBJECT);
        verify(artifacts).describe(RUN_PREFIX + S3StatementSink.HTML_OBJECT);
        verify(artifacts).describe(RUN_PREFIX + StatementService.INDEX_OBJECT);
        // WHY : Assumptions: the manifest is read through a BOUNDED range, and the bound is asserted --
        //       an unbounded read of an object whose size this service does not control would size an
        //       allocation from the store's answer.
        verify(artifacts).readRange(
                StatementService.manifestKey(PREFIX), 0L, StatementService.RUN_ID_LENGTH + 15L);
    }

    // WHY : Assumptions: an unpublished run is asserted to be an ORDINARY state rather than a failure,
    //       because a deployment whose first statement run has not happened has no manifest, and that
    //       is the same state as the one in which no artifact exists. Failing the statement read would
    //       deny a caller its own figures over the absence of a document it did not ask for.
    /**
     * Asserts that a deployment with no published run reports no artifact rather than failing.
     */
    @Test
    @DisplayName("no published run yields no location and probes no artifact")
    void anUnpublishedRunYieldsNoArtifact() {
        stubOneCard();
        when(artifacts.readRange(eq(StatementService.manifestKey(PREFIX)), anyLong(), anyLong()))
                .thenThrow(new NoSuchElementException("no manifest"));

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.plainTextUri()).isNull();
        assertThat(response.htmlUri()).isNull();
        assertThat(response.generatedAt()).isNull();
        assertThat(response.firstRecord()).isNull();
        assertThat(response.recordCount()).isNull();
        verify(artifacts, never()).describe(anyString());
    }

    // WHY : Assumptions: a manifest that is present but does not name a run identifier is a FAILURE
    //       rather than an absence, because the alternative is to compose an object key out of whatever
    //       it holds. The second value is the case that makes the difference visible: a relative path
    //       would address an object outside the statement prefix entirely if it were concatenated, and
    //       an implementation that trusted its own manifest would do exactly that.
    /**
     * Asserts that a manifest not naming a well-formed run is refused rather than used as a key part.
     */
    @Test
    @DisplayName("a manifest not naming a run identifier is refused")
    void aManifestNamingNoRunIsRefused() {
        stubOneCard();
        // WHY : Assumptions: the case-sensitivity rejection is DERIVED from the accepted value rather
        //       than transcribed as a second literal. StatementService declares the run identifier as
        //       thirty-two LOWER-case hexadecimal characters, so upper-casing the accepted constant
        //       produces the one negative case that differs from it in nothing but case -- a hand-written
        //       constant could drift from RUN_ID and then stop testing case at all. Trade-offs: it also
        //       keeps a thirty-two character hexadecimal literal out of the source, which the
        //       repository's secret scan reads as a credential shape wherever it appears unreviewed.
        String upperCasedRun = RUN_ID.toUpperCase(Locale.ROOT);
        for (String bogus : List.of("../../etc/passwd", upperCasedRun, RUN_ID + RUN_ID, "")) {
            when(artifacts.readRange(eq(StatementService.manifestKey(PREFIX)), anyLong(), anyLong()))
                    .thenReturn(bogus.getBytes(StandardCharsets.US_ASCII));

            assertThatExceptionOfType(IllegalStateException.class)
                    .as("a manifest holding %s must not reach an object key", bogus)
                    .isThrownBy(() ->
                            service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR))
                    .withMessageContaining("manifest");
        }
        verify(artifacts, never()).describe(anyString());
    }

    // WHY : Refactoring Rationale: this is the case that makes immutable keys worth having. Keys that
    //       are never overwritten stop a run's bytes changing underneath a reader, but on their own
    //       they would still let a selector minted for one run resolve against whichever run is current
    //       -- so a caller holding yesterday's selector, published beside yesterday's positions, would
    //       be handed today's artifact and told nothing had changed. Binding the run into the tokenised
    //       value is what closes that, and the assertion is that the OLD selector stops resolving
    //       rather than that the new one starts.
    /**
     * Asserts that a selector minted for a superseded run resolves to nothing once the manifest moves.
     */
    @Test
    @DisplayName("a selector of a superseded run resolves to nothing")
    void aSelectorOfASupersededRunResolvesToNothing() {
        stubOneCard();
        StatementResponse published =
                service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);
        String stale = selectorOf(published.plainTextUri());
        String laterRun = "fedcba9876543210fedcba9876543210";
        when(artifacts.readRange(eq(StatementService.manifestKey(PREFIX)), anyLong(), anyLong()))
                .thenReturn(StatementService.encodeManifest(laterRun));

        assertThat(service.resolveArtifactKey(stale))
                .as("the superseded run's selector must not open the current run's bytes")
                .isEmpty();
        assertThatExceptionOfType(NoSuchElementException.class)
                .isThrownBy(() -> service.collectArtifact(stale));
        verify(artifacts, never()).open(anyString());
        assertThat(service.artifactSelector(laterRun, S3StatementSink.PLAIN_TEXT_OBJECT))
                .as("a selector names one artifact of one run, so the two runs differ")
                .isNotEqualTo(stale);
    }

    // WHY : Refactoring Rationale: this is the other half of what a review found wrong with the
    //       statement surface. The two artifacts cover the WHOLE run, so pointing a caller at them
    //       without saying where its own statement sits inside them left the caller with a document
    //       covering every cardholder and no way to find one. The index is what closes that, and this
    //       case follows it end to end: the position the run recorded is the position the response
    //       reports.
    /**
     * Asserts that a card named by the run index is reported with its position in the artifact.
     */
    @Test
    @DisplayName("a card named by the run index is reported with its position in the artifact")
    void aCardInTheIndexIsReportedWithItsPosition() {
        stubOneCard();
        stubIndex(new StatementIndexEntry(FINGERPRINT, 240L, 27L));

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.firstRecord()).isEqualTo(240L);
        assertThat(response.recordCount()).isEqualTo(27L);
    }

    // WHY : Assumptions: the index is searched by BISECTION, so a case with one entry cannot tell a
    //       search from a linear scan of one element. Three entries with the wanted card LAST is the
    //       smallest shape that distinguishes them: a bisection probes the middle, finds it low and
    //       moves right, which a scan that stopped at the first entry would never reach.
    /**
     * Asserts that a card sitting last in the index is still found.
     */
    @Test
    @DisplayName("a card sitting last in the index is found by bisection")
    void aCardLastInTheIndexIsFound() {
        stubOneCard();
        stubIndex(
                new StatementIndexEntry("0".repeat(63) + "1", 0L, 30L),
                new StatementIndexEntry("5".repeat(63) + "5", 30L, 12L),
                new StatementIndexEntry(FINGERPRINT, 42L, 9L));

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.firstRecord()).isEqualTo(42L);
        assertThat(response.recordCount()).isEqualTo(9L);
    }

    // WHY : Assumptions: a card ABSENT from the index yields no position rather than a failure or a
    //       neighbouring card's position. The neighbouring position is the dangerous answer -- a
    //       bisection that returned its last probe rather than nothing would send a caller to another
    //       cardholder's records -- so the fabricated index deliberately brackets the wanted card.
    /**
     * Asserts that a card the index does not name yields no position rather than a neighbour's.
     */
    @Test
    @DisplayName("a card the index does not name yields no position")
    void aCardAbsentFromTheIndexYieldsNoPosition() {
        stubOneCard();
        stubIndex(
                new StatementIndexEntry("0".repeat(63) + "1", 0L, 30L),
                new StatementIndexEntry("f".repeat(63) + "f", 30L, 12L));

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.firstRecord()).isNull();
        assertThat(response.recordCount()).isNull();
    }

    /**
     * Asserts that an absent index degrades to no position rather than to a failure.
     */
    @Test
    @DisplayName("an absent index degrades to no position")
    void anAbsentIndexYieldsNoPosition() {
        stubOneCard();

        StatementResponse response = service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.firstRecord()).isNull();
        assertThat(response.recordCount()).isNull();
        // WHY : Assumptions: the verification names the INDEX key rather than any key, because the
        //       manifest is itself read through a bounded range -- so a verification of "no ranged read
        //       at all" would now fail for the read that establishes which run to look in.
        verify(artifacts, never())
                .readRange(eq(RUN_PREFIX + StatementService.INDEX_OBJECT), anyLong(), anyLong());
    }

    // WHY : Assumptions: a TRUNCATED index is not searched at all, because an artifact whose size is not
    //       a whole number of strides makes every derived position wrong -- a probe would land mid-record
    //       and decode a fingerprint spliced from two cards. The alignment test is the load-bearing half
    //       of this case and it is asserted through the store: no ranged read of the index is issued.
    // WHY : Refactoring Rationale: the size used here is the stride plus one, where it was the entry
    //       CONTENT WIDTH plus one. Under the object's real framing that former value is one whole
    //       entry and is therefore perfectly aligned, so the case stopped describing a truncation the
    //       moment the reader began stepping by the stride the writer frames at.
    // WHY : Refactoring Rationale: the outcome asserted is a degraded response and no longer a raised
    //       failure. An index that cannot be used is a state of a stored object rather than anything the
    //       caller did, and raising cost the caller the heading, the total, the line count and both
    //       artifact locations -- every one of which is computed without the index -- in exchange for an
    //       internal error. Nothing unsafe is admitted: no position is derived from a misaligned object,
    //       which is the wrong-cardholder answer the alignment test exists to prevent.
    /**
     * Asserts that an index of a partial entry yields no position and is never probed.
     */
    @Test
    @DisplayName("an index that is not a whole number of strides yields no position and is not probed")
    void aTruncatedIndexYieldsNoPositionWithoutBeingProbed() {
        stubOneCard();
        when(artifacts.describe(RUN_PREFIX + StatementService.INDEX_OBJECT))
                .thenReturn(Optional.of(new ArtifactStore.ArtifactDescriptor(
                        RUN_PREFIX + StatementService.INDEX_OBJECT,
                        StatementIndexEntry.ON_OBJECT_STRIDE + 1L, WRITTEN_AT)));

        StatementResponse response =
                service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.firstRecord()).isNull();
        assertThat(response.recordCount()).isNull();
        assertThat(response.plainTextUri())
                .as("everything computed without the index is still answered")
                .isNotNull();
        verify(artifacts, never())
                .readRange(eq(RUN_PREFIX + StatementService.INDEX_OBJECT), anyLong(), anyLong());
    }

    // WHY : Assumptions: an object whose LENGTH is aligned but whose CONTENT will not decode is the
    //       other unusable state, and it is reached only by a probe -- so it exercises the recovery
    //       around the search rather than the alignment test before it. Blanks are used as the
    //       undecodable content because they are what a producer framing its records differently would
    //       most plausibly leave in the positions this reader expects hexadecimal and digits in.
    /**
     * Asserts that a stride-aligned index whose entries will not decode yields no position.
     */
    @Test
    @DisplayName("a stride-aligned index whose content will not decode yields no position")
    void anUndecodableIndexYieldsNoPosition() {
        stubOneCard();
        String key = RUN_PREFIX + StatementService.INDEX_OBJECT;
        when(artifacts.describe(key)).thenReturn(Optional.of(new ArtifactStore.ArtifactDescriptor(
                key, (long) StatementIndexEntry.ON_OBJECT_STRIDE, WRITTEN_AT)));
        when(artifacts.readRange(eq(key), anyLong(), anyLong())).thenReturn(
                " ".repeat(StatementIndexEntry.ENCODED_WIDTH).getBytes(StandardCharsets.US_ASCII));

        StatementResponse response =
                service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(response.firstRecord()).isNull();
        assertThat(response.recordCount()).isNull();
    }

    // WHY : Assumptions: this is the case the shipped reader failed and every other index case here
    //       passed. It asserts the two facts that together prove the framing: the object's own length is
    //       the entry count times the stride, and the position answered for a card in the MIDDLE of it is
    //       that card's own and not its neighbour's. A reader stepping by the content width satisfies
    //       neither -- the length test refuses the object outright, and had it not, the middle probe
    //       would return a record spliced from two entries.
    /**
     * Asserts that a newline-framed index is measured and probed at the stride the writer frames at.
     */
    @Test
    @DisplayName("a newline-framed index is measured and probed at the writer's own stride")
    void aNewlineFramedIndexIsProbedAtTheWritersStride() {
        stubOneCard();
        StatementIndexEntry before = new StatementIndexEntry("0".repeat(63) + "1", 0L, 30L);
        StatementIndexEntry wanted = new StatementIndexEntry(FINGERPRINT, 30L, 12L);
        StatementIndexEntry after = new StatementIndexEntry("f".repeat(63) + "f", 42L, 7L);
        stubIndex(before, wanted, after);

        StatementResponse response =
                service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(frameIndexObject(before, wanted, after))
                .as("three framed entries occupy three strides, terminators included")
                .hasSize(3 * StatementIndexEntry.ON_OBJECT_STRIDE);
        assertThat(response.firstRecord()).isEqualTo(30L);
        assertThat(response.recordCount()).isEqualTo(12L);
        ArgumentCaptor<Long> firstBytes = ArgumentCaptor.forClass(Long.class);
        verify(artifacts, Mockito.atLeastOnce()).readRange(
                eq(RUN_PREFIX + StatementService.INDEX_OBJECT), firstBytes.capture(), anyLong());
        assertThat(firstBytes.getAllValues())
                .as("every probe begins on a stride boundary, never inside a record")
                .allSatisfy(firstByte ->
                        assertThat(firstByte % StatementIndexEntry.ON_OBJECT_STRIDE).isZero());
    }

    // WHY : Assumptions: the probe count is asserted, not just the answer. A search that read every
    //       entry would return the same position and would transfer the whole index on every statement
    //       read, which is the cost this shape exists to avoid -- and a wrong probe count is the only
    //       symptom.
    /**
     * Asserts that locating one card among seven costs three probes rather than seven.
     */
    @Test
    @DisplayName("locating a card costs a logarithmic number of probes")
    void locatingACardCostsLogarithmicProbes() {
        stubOneCard();
        List<StatementIndexEntry> entries = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            entries.add(new StatementIndexEntry(String.valueOf(i).repeat(64), i * 10L, 10L));
        }
        entries.add(new StatementIndexEntry(FINGERPRINT, 60L, 10L));
        entries.sort((left, right) -> left.cardFingerprint().compareTo(right.cardFingerprint()));
        stubIndex(entries.toArray(new StatementIndexEntry[0]));

        service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        verify(artifacts, Mockito.atMost(3)).readRange(
                eq(RUN_PREFIX + StatementService.INDEX_OBJECT), anyLong(), anyLong());
    }

    /**
     * Stubs the store to hold an index artifact composed of the supplied entries, in the order given.
     *
     * <p>Assumptions: the stub materialises the object's ACTUAL BYTES, terminator included, and answers
     * every ranged read by slicing them. Refactoring Rationale: it used to divide the requested first
     * byte by the entry's content width and hand back that entry whole, which made the stub agree with
     * whatever stride the reader used and so could not have detected the framing defect at all -- the
     * shipped reader stepped by the content width over an object framed at the content width plus a
     * terminator, and every case here passed while every real index was refused. Slicing real bytes
     * means a reader that steps by the wrong stride asks for a range spanning two entries and gets a
     * spliced record that fails to decode, which is the failure the defect deserved.</p>
     *
     * @param entries the index entries in the order the artifact holds them; must be sorted by
     *     fingerprint for a search to be correct
     */
    private void stubIndex(StatementIndexEntry... entries) {
        String key = RUN_PREFIX + StatementService.INDEX_OBJECT;
        byte[] object = frameIndexObject(entries);
        when(artifacts.describe(key)).thenReturn(Optional.of(
                new ArtifactStore.ArtifactDescriptor(key, object.length, WRITTEN_AT)));
        when(artifacts.readRange(eq(key), anyLong(), anyLong())).thenAnswer(call -> {
            long firstByte = call.getArgument(1);
            long lastByte = call.getArgument(2);
            return Arrays.copyOfRange(object, (int) firstByte, (int) lastByte + 1);
        });
    }

    /**
     * Frames index entries into the object the artifact writer would publish.
     *
     * <p>Assumptions: a single line feed follows every entry, because the writer that publishes this
     * artifact terminates every record it accepts and does so for the index exactly as it does for the
     * two statement streams. That one byte per entry is the whole of the difference between an entry's
     * content width and the distance between two entries in the object, and it is reproduced here so
     * the search arithmetic is exercised against the framing it will actually meet.</p>
     *
     * @param entries the entries to frame, in the order the artifact holds them
     * @return the object's bytes, being each entry's content followed by one terminator
     */
    private static byte[] frameIndexObject(StatementIndexEntry... entries) {
        byte[] object = new byte[entries.length * StatementIndexEntry.ON_OBJECT_STRIDE];
        int at = 0;
        for (StatementIndexEntry entry : entries) {
            byte[] content = entry.encode();
            System.arraycopy(content, 0, object, at, content.length);
            object[at + StatementIndexEntry.ENCODED_WIDTH] = (byte) '\n';
            at += StatementIndexEntry.ON_OBJECT_STRIDE;
        }
        return object;
    }

    /**
     * Extracts the opaque selector from a published artifact location.
     *
     * @param location the location a statement response published; must not be {@code null}
     * @return the selector alone, with the served path prefix removed
     */
    private static String selectorOf(String location) {
        return location.substring(StatementService.ARTIFACT_LOCATION_PREFIX.length());
    }

    // WHY : Assumptions: the whole-run walk is asserted by the SECOND chunk's continuation argument.
    //       A walk that restarted from the beginning would loop forever on a real portfolio and would
    //       pass any assertion that only counted statements, because the first chunk alone satisfies
    //       a count.
    // WHY : Refactoring Rationale: the continuation is asserted as the WHOLE ordering tuple, where it
    //       was asserted as the fingerprint alone. Both fixture cards deliberately carry the SAME
    //       masked rendering, which is the case the single-component predicate mishandled -- it
    //       compared a component the sequence does not lead on, so cards were skipped and repeated.
    //       A regression to that predicate cannot satisfy this case, because the stub it would call
    //       carries a different argument list and Mockito answers an unstubbed call with an empty
    //       list, which fails the count below.
    /**
     * Asserts that the whole-run walk continues from the whole ordering tuple of the previous chunk.
     */
    @Test
    @DisplayName("the whole-run walk continues by keyset from the previous chunk")
    void theWholeRunWalkContinuesByKeyset() {
        when(cardXrefs.findHeadingChunk("", "", StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of(headingRow(FINGERPRINT), headingRow(OTHER_FINGERPRINT)));
        when(cardXrefs.findHeadingChunk(MASKED_CARD, OTHER_FINGERPRINT,
                StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of());
        when(transactions.aggregateByCardFingerprint(anyString()))
                .thenReturn(aggregate("0.00", 0L));
        when(transactions.findWindowByCardFingerprint(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        RecordingSink sink = new RecordingSink();
        StatementRunOutcome outcome = service.generateStatements(sink);

        assertThat(outcome.statementsProduced()).as("both cards produced a statement").isEqualTo(2);
        // WHY : Assumptions: the index is asserted to cover BOTH cards and to be contiguous, which is
        //       the property a consumer of the run-wide artifact depends on. A gap between one card's
        //       last record and the next card's first would mean records belonging to no statement, and
        //       an overlap would mean one card's records reported inside another card's statement.
        assertThat(outcome.index()).hasSize(2);
        assertThat(outcome.index().get(0).firstRecord()).isZero();
        assertThat(outcome.index().get(1).firstRecord())
                .as("the second statement begins where the first ended")
                .isEqualTo(outcome.index().get(0).recordCount());
        assertThat(outcome.index().get(0).recordCount() + outcome.index().get(1).recordCount())
                .as("the index accounts for every plain-text record the run wrote")
                .isEqualTo(sink.plainRecords.size());
        assertThat(sink.replacements).as("the previous run's artifacts are cleared once").isEqualTo(1);
        assertThat(sink.plainRecords).as("the plain-text artifact received records").isNotEmpty();
        assertThat(sink.markupRecords).as("the markup artifact received records").isNotEmpty();
        verify(cardXrefs).findHeadingChunk("", "", StatementService.HEADING_CHUNK_SIZE);
        verify(cardXrefs).findHeadingChunk(MASKED_CARD, OTHER_FINGERPRINT,
                StatementService.HEADING_CHUNK_SIZE);
    }

    // WHY : Assumptions: this drives the walk in the order that DISAGREES with fingerprint order, which
    //       is the ordinary case rather than an edge one: the walk is anchored on card number and a
    //       fingerprint is a digest, so the two orders coincide only by accident. The previous fixture
    //       happened to list its two cards in ascending fingerprint order too, so it could not tell a
    //       sorted index from an unsorted one -- which is how a binary search over an unordered index
    //       went unnoticed until the read path stopped refusing every index outright.
    /**
     * Asserts that the index is ordered for lookup even when the walk wrote the statements in another
     * order, and that each entry keeps the position it named.
     */
    @Test
    @DisplayName("the index is fingerprint-ordered even when the walk is not")
    void theIndexIsFingerprintOrderedEvenWhenTheWalkIsNot() {
        when(cardXrefs.findHeadingChunk("", "", StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of(headingRow(OTHER_FINGERPRINT), headingRow(FINGERPRINT)));
        when(cardXrefs.findHeadingChunk(MASKED_CARD, FINGERPRINT,
                StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of());
        when(transactions.aggregateByCardFingerprint(anyString()))
                .thenReturn(aggregate("0.00", 0L));
        when(transactions.findWindowByCardFingerprint(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        RecordingSink sink = new RecordingSink();
        StatementRunOutcome outcome = service.generateStatements(sink);

        assertThat(outcome.statementsProduced()).isEqualTo(2);
        assertThat(outcome.index()).hasSize(2);
        assertThat(outcome.index())
                .as("a binary search over the index compares fingerprints, so it must be sorted by one")
                .isSortedAccordingTo(
                        java.util.Comparator.comparing(StatementIndexEntry::cardFingerprint));
        assertThat(outcome.index().get(0).cardFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(outcome.index().get(1).cardFingerprint()).isEqualTo(OTHER_FINGERPRINT);
        // WHY : Assumptions: the LATER-written statement now sits FIRST in the index, so its first
        //       record cannot be zero and the earlier-written one's must be. That is what distinguishes
        //       sorting the entries from renumbering them: each entry keeps the position it named in the
        //       artifact, and the artifact itself is not reordered.
        assertThat(outcome.index().get(0).firstRecord())
                .as("the entry sorted first was written second, so it does not start the artifact")
                .isNotZero();
        assertThat(outcome.index().get(1).firstRecord())
                .as("the entry sorted second was written first, so it starts the artifact")
                .isZero();
        assertThat(outcome.index().get(1).recordCount())
                .as("the first-written statement's records precede the second-written one's")
                .isEqualTo(outcome.index().get(0).firstRecord());
        assertThat(outcome.index().get(0).recordCount() + outcome.index().get(1).recordCount())
                .as("the index still accounts for every plain-text record the run wrote")
                .isEqualTo(sink.plainRecords.size());
    }

    // WHY : Assumptions: the three cases below are about the DISPOSITION of a rendering refusal and not
    //       about the refusals themselves, which stay exactly as strict as they were. A field carrying a
    //       character the fixed-width charset cannot represent must still be refused, because a
    //       substituted byte would corrupt a record whose width is its interface; a cell whose escaped
    //       form will not fit must still be refused, because a truncated one emits half an entity. What
    //       these cases pin is that ONE cardholder's unrenderable row costs ONE statement.
    // WHY : Assumptions: the description column the two inputs arrive through is declared
    //       {@code varchar(100)} with no check constraint, so both are schema-legal and neither could
    //       have been kept out by the relation. That is what makes the disposition the whole of the
    //       question.
    /**
     * Asserts that a card whose field cannot be encoded is omitted while the run continues.
     */
    @Test
    @DisplayName("a card whose field cannot be encoded is omitted and the run continues")
    void aCardWhoseFieldCannotBeEncodedIsOmittedAndTheRunContinues() {
        stubTwoCardWalk();
        stubDescriptionPerCard("CAF\u00c9 UNICODE", "SPECIMEN LINE");

        RecordingSink sink = new RecordingSink();
        StatementRunOutcome outcome = service.generateStatements(sink);

        assertThat(outcome.statementsProduced())
                .as("the second card's statement is produced, the first card's is omitted")
                .isEqualTo(1);
        assertThat(outcome.index()).hasSize(1);
        // WHY : Assumptions: a first record of zero is the assertion that the omitted statement wrote
        //       NOTHING. Had any of its records reached the artifact, the surviving statement would begin
        //       after them -- and every index position of the run would then name a record belonging to a
        //       statement that was never completed.
        assertThat(outcome.index().get(0).firstRecord())
                .as("the omitted statement left no record behind it")
                .isZero();
        assertThat(outcome.index().get(0).recordCount())
                .as("the index accounts for every plain-text record the run wrote")
                .isEqualTo(sink.plainRecords.size());
        assertThat(sink.markupRecords).as("the markup artifact received the surviving card").isNotEmpty();
    }

    /**
     * Asserts that a card whose markup cell over-expands is omitted while the run continues.
     */
    @Test
    @DisplayName("a card whose markup cell over-expands is omitted and the run continues")
    void aCardWhoseMarkupCellOverExpandsIsOmittedAndTheRunContinues() {
        stubTwoCardWalk();
        // WHY : Assumptions: the ampersand is the expanding character, because escaping it produces five
        //       characters where the source had one, so a cell filled with them is the input that grows
        //       furthest past its declared width. A run of them is also the input the reference has no
        //       notion of at all, since it emits the source characters unescaped.
        stubDescriptionPerCard("&".repeat(100), "SPECIMEN LINE");

        RecordingSink sink = new RecordingSink();
        StatementRunOutcome outcome = service.generateStatements(sink);

        assertThat(outcome.statementsProduced()).isEqualTo(1);
        assertThat(outcome.index()).hasSize(1);
        assertThat(outcome.index().get(0).firstRecord())
                .as("the omitted statement left no record behind it")
                .isZero();
    }

    // WHY : Assumptions: this case is the boundary's other half and it is the one that fails if the
    //       staged records are flushed INSIDE the recovery. A sink that cannot accept a record cannot
    //       produce any statement, so recovering from it would walk the whole portfolio reporting every
    //       card as omitted and answer with an empty artifact and a successful run.
    /**
     * Asserts that a sink refusing a record still stops the run rather than omitting a statement.
     */
    @Test
    @DisplayName("a sink refusing a record still stops the run")
    void aSinkRefusingARecordStillStopsTheRun() {
        stubTwoCardWalk();
        stubDescriptionPerCard("SPECIMEN LINE", "SPECIMEN LINE");
        StatementService.StatementSink refusing = new StatementService.StatementSink() {
            @Override
            public void replaceArtifacts() {
                // WHY : Assumptions: the reset succeeds so the case fails on the RECORD and not on the
                //       artifact discard, which is a different path with a different disposition.
            }

            @Override
            public void writeStatementRecord(byte[] record) {
                throw new IllegalStateException("the object store refused a statement record");
            }

            @Override
            public void writeMarkupRecord(byte[] record) {
                throw new IllegalStateException("the object store refused a markup record");
            }
        };

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> service.generateStatements(refusing))
                .withMessageContaining("refused a statement record");
    }

    // WHY : Assumptions: the two rows are each at the widest amount the ledger's own picture admits, so
    //       the card total is reachable from conforming rows and is not a fabricated figure. Their sum
    //       needs a tenth integer position where the statement regime declares nine, which is the
    //       condition the reference meets by moving the figure into a narrower field and losing the
    //       digits it cannot carry.
    // WHY : Refactoring Rationale: this figure used to end the run, so the OTHER card's statement was
    //       lost as well -- the amount that could not be printed cost a statement to a cardholder whose
    //       own transactions were entirely ordinary. Both halves are asserted: the run produces both
    //       statements, and the second one begins exactly where the first one ended, which is what makes
    //       every index position of the artifact answerable.
    /**
     * Asserts that a card total past the regime's width is narrowed and costs no statement.
     */
    @Test
    @DisplayName("a card total past the regime's width is narrowed and costs no statement")
    void anOverWideCardTotalIsNarrowedAndCostsNoStatement() {
        stubTwoCardWalk();
        when(transactions.findWindowByCardFingerprint(anyString(), anyString(), anyInt()))
                .thenAnswer(call -> {
                    String fingerprint = call.getArgument(0);
                    String after = call.getArgument(1);
                    if (!after.isEmpty()) {
                        return List.of();
                    }
                    if (FINGERPRINT.equals(fingerprint)) {
                        return List.of(
                                transactionWith("SPECIMEN LINE", "999999999.99", 0),
                                transactionWith("SPECIMEN LINE", "999999999.99", 1));
                    }
                    return List.of(transactionWith("SPECIMEN LINE", "-1.00", 0));
                });

        RecordingSink sink = new RecordingSink();
        StatementRunOutcome outcome = service.generateStatements(sink);

        assertThat(outcome.statementsProduced())
                .as("neither card loses its statement to the other card's arithmetic")
                .isEqualTo(2);
        assertThat(outcome.index()).hasSize(2);
        assertThat(outcome.index().get(0).firstRecord()).isZero();
        assertThat(outcome.index().get(1).firstRecord())
                .as("the second statement begins where the first one ended, so the index is contiguous")
                .isEqualTo(outcome.index().get(0).recordCount());
        assertThat(outcome.index().get(0).recordCount() + outcome.index().get(1).recordCount())
                .as("every plain-text record the run wrote belongs to one of the two statements")
                .isEqualTo(sink.plainRecords.size());
    }

    /**
     * Stubs a whole-run walk over two cards that ends after one chunk.
     *
     * <p>Assumptions: both rows carry the same masked rendering, which is the leading component of the
     * order the heading query declares, so the continuation the walk composes is the pair and not the
     * fingerprint alone.</p>
     */
    private void stubTwoCardWalk() {
        when(cardXrefs.findHeadingChunk("", "", StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of(headingRow(FINGERPRINT), headingRow(OTHER_FINGERPRINT)));
        when(cardXrefs.findHeadingChunk(MASKED_CARD, OTHER_FINGERPRINT,
                StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of());
        when(transactions.aggregateByCardFingerprint(anyString()))
                .thenReturn(aggregate("0.00", 0L));
    }

    /**
     * Stubs one transaction per card, each carrying the description supplied for that card.
     *
     * <p>Assumptions: each card's window is exhausted after its single row, because the continuation the
     * walk composes from that row's identifier is stubbed to answer with nothing. One row per card is
     * enough for these cases and keeps the failing row unambiguous: a card that produced no statement
     * had exactly one row it could have failed on.</p>
     *
     * @param firstCardDescription the description the card named by the leading fingerprint carries
     * @param secondCardDescription the description the other card carries
     */
    private void stubDescriptionPerCard(String firstCardDescription, String secondCardDescription) {
        when(transactions.findWindowByCardFingerprint(anyString(), anyString(), anyInt()))
                .thenAnswer(call -> {
                    String fingerprint = call.getArgument(0);
                    String after = call.getArgument(1);
                    if (!after.isEmpty()) {
                        return List.of();
                    }
                    return List.of(transactionWith(FINGERPRINT.equals(fingerprint)
                            ? firstCardDescription
                            : secondCardDescription));
                });
    }

    /**
     * Builds one transaction projection carrying a supplied description.
     *
     * @param description the description the row carries, which may be a value no band can render
     * @return the projection, with the three members a statement line reads assigned
     */
    private static StatementTransactionView transactionWith(String description) {
        return transactionWith(description, "-1.00", 0);
    }

    /**
     * Builds one transaction projection carrying a supplied description, amount and ordinal.
     *
     * <p>Assumptions: the ordinal is what makes two rows of one card distinguishable, because the walk
     * continues from the last row's identifier and two rows sharing one would make the continuation
     * re-read the chunk it had just finished.</p>
     *
     * @param description the description the row carries, which may be a value no band can render
     * @param amount the row's amount, as the decimal text the shared money type parses
     * @param ordinal the row's position within its card, which its identifier is derived from
     * @return the projection, with the three members a statement line reads assigned
     */
    private static StatementTransactionView transactionWith(
            String description, String amount, int ordinal) {
        StatementTransactionView row = newProjection();
        setMember(row, "key", new StatementTransactionView.StatementTransactionKey(
                SEED_CARD_NUMBER, transactionId(ordinal)));
        setMember(row, "description", description);
        setMember(row, "amount", Money.of(amount));
        return row;
    }

    // WHY : Assumptions: the two cases below are the ONLY executable controls in this reactor over the
    //       two arity thresholds tests/README.md measures on app/cbl/CBSTM03A.CBL, and they are here
    //       rather than in the mapper package because arity is a property of what ACCUMULATES a
    //       statement. StatementTextMapper is a stateless per-line assembler that holds no table, which
    //       its own class documentation states, so no arity assertion is available to write there.
    // WHY : Assumptions: both cases assert that the migrated Java has NO fixed arity, and neither
    //       asserts that the baseline was put right. app/** is reference-only, so the baseline's two
    //       unchecked tables stay exactly as they are; what these cases pin is the migrated behaviour
    //       and the divergence D-2 registered in docs/architecture/cobol-to-service-traceability.md.
    //       Framing them as repairs would assert an edit to app/cbl/CBSTM03A.CBL that never happened.
    /**
     * Asserts one card carrying more transactions than the baseline's inner table admits still statements.
     *
     * <p>Purpose: {@code tests/README.md} records the measured threshold of the inner same-card table in
     * {@code app/cbl/CBSTM03A.CBL} -- a single card renders up to 512 transactions and the 513th overruns
     * the table and terminates the process with a memory fault. The migrated service holds no table, so
     * the 513th transaction must be an ordinary one. This case drives exactly that count and asserts
     * every line survives to the document.</p>
     *
     * <p>Assumptions: 513 is driven rather than a comfortable round number above it, because the
     * threshold is a boundary and the first value past it is the one that distinguishes "no arity" from
     * "an arity that happens to be larger". A run at 1,000 would pass against a service whose limit was
     * 600 and would report the boundary as covered.</p>
     *
     * <p>Assumptions: the count is asserted on the DOCUMENT's transaction list rather than on the
     * repository call, because a service that fetched 513 rows and then truncated its output at 512
     * would satisfy a call assertion while producing exactly the baseline's defect in a quieter form.
     * The window request is bounded by {@code MAX_RESPONSE_TRANSACTIONS}, which is 1,000 and therefore
     * above this boundary -- so a truncation at 513 could only come from an arity, not from the cap.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a card carrying 513 transactions statements every one of them")
    void aCardPastTheBaselineInnerTableThresholdStatementsEveryTransaction() {
        stubOneCard();
        when(transactions.aggregateByCardFingerprint(FINGERPRINT))
                .thenReturn(aggregate("-1234.56", BASELINE_INNER_TABLE_THRESHOLD + 1L));
        when(transactions.findWindowByCardFingerprint(
                eq(FINGERPRINT), anyString(), eq(StatementService.MAX_RESPONSE_TRANSACTIONS)))
                .thenReturn(transactionWindow(BASELINE_INNER_TABLE_THRESHOLD + 1));

        StatementDocument document = service.compose(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

        assertThat(document.transactions())
                .as("the 513th transaction overruns the baseline's inner table at "
                        + "app/cbl/CBSTM03A.CBL line 228; the migrated service must hold no table")
                .hasSize(BASELINE_INNER_TABLE_THRESHOLD + 1);
        assertThat(document.statement().transactionCount())
                .as("the heading must report the true count past the threshold as well")
                .isEqualTo(BASELINE_INNER_TABLE_THRESHOLD + 1);
        assertThat(document.transactions().getLast().transactionId())
                .as("the LAST line is asserted by identity, so a truncation that kept the count "
                        + "by repeating a row could not pass")
                .isEqualTo(transactionId(BASELINE_INNER_TABLE_THRESHOLD));
    }

    /**
     * Asserts a run over more distinct cards than the baseline's outer table admits statements them all.
     *
     * <p>Purpose: {@code tests/README.md} records the measured threshold of the outer card table --
     * {@code WS-CARD-TBL OCCURS 51 TIMES} at line 226 of {@code app/cbl/CBSTM03A.CBL} renders up to 51
     * distinct cards and the 52nd overruns it and terminates the process. The migrated run walks the
     * portfolio by keyset in chunks and holds no card table, so the 52nd card must be an ordinary one.
     * This case drives 52 distinct cards across two chunk reads and asserts a statement, and an index
     * entry, for every one of them.</p>
     *
     * <p>Assumptions: the 52 cards are split ACROSS the chunk boundary rather than delivered in one
     * read, because a single read would exercise the absence of a table while leaving the continuation
     * unexercised, and a run that restarted each chunk from the beginning would loop on a real portfolio.
     * The split is 51 then 1, which puts the boundary card alone in the second chunk so that a failure
     * naming it is unambiguous.</p>
     *
     * <p>Assumptions: the index is asserted contiguous over all 52 entries as well as complete. A gap
     * would mean records belonging to no statement and an overlap would mean one card's records reported
     * inside another's, and both are states a count alone cannot distinguish from a correct run.</p>
     *
     * <p>It takes no parameter and returns no value.</p>
     */
    @Test
    @DisplayName("a run over 52 distinct cards produces a statement for every one of them")
    void aRunPastTheBaselineOuterTableThresholdStatementsEveryCard() {
        List<StatementCardXrefRepository.StatementHeadingRow> firstChunk = new ArrayList<>();
        for (int index = 0; index < BASELINE_OUTER_TABLE_ARITY; index++) {
            firstChunk.add(headingRow(distinctFingerprint(index)));
        }
        String boundaryFingerprint = distinctFingerprint(BASELINE_OUTER_TABLE_ARITY);

        when(cardXrefs.findHeadingChunk("", "", StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(firstChunk);
        when(cardXrefs.findHeadingChunk(MASKED_CARD,
                distinctFingerprint(BASELINE_OUTER_TABLE_ARITY - 1),
                StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of(headingRow(boundaryFingerprint)));
        when(cardXrefs.findHeadingChunk(MASKED_CARD, boundaryFingerprint,
                StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of());
        when(transactions.aggregateByCardFingerprint(anyString()))
                .thenReturn(aggregate("0.00", 0L));
        when(transactions.findWindowByCardFingerprint(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        RecordingSink sink = new RecordingSink();
        StatementRunOutcome outcome = service.generateStatements(sink);

        assertThat(outcome.statementsProduced())
                .as("the 52nd card overruns the baseline's outer table at app/cbl/CBSTM03A.CBL "
                        + "line 226; the migrated run must hold no card table")
                .isEqualTo(BASELINE_OUTER_TABLE_ARITY + 1);
        assertThat(outcome.index()).hasSize(BASELINE_OUTER_TABLE_ARITY + 1);

        long accountedRecords = 0;
        for (int position = 0; position < outcome.index().size(); position++) {
            assertThat(outcome.index().get(position).firstRecord())
                    .as("statement %d must begin where statement %d ended", position, position - 1)
                    .isEqualTo(accountedRecords);
            accountedRecords += outcome.index().get(position).recordCount();
        }
        assertThat(accountedRecords)
                .as("the index must account for every plain-text record all 52 statements wrote")
                .isEqualTo(sink.plainRecords.size());
        verify(cardXrefs).findHeadingChunk(MASKED_CARD,
                distinctFingerprint(BASELINE_OUTER_TABLE_ARITY - 1),
                StatementService.HEADING_CHUNK_SIZE);
    }

    // WHY : Refactoring Rationale: this case exists because the run aborted on it. Two of the projected
    //       customer attributes are nullable in the owning relation -- the middle name and the second
    //       address line -- and the band assembler refuses a null because a band field is required, so
    //       the first customer with a one-line street address raised a null-pointer failure and took the
    //       whole night's statements with it. A fixture with every attribute populated cannot reach that
    //       path, which is why it survived to be found here rather than in the existing cases.
    /**
     * Asserts that a customer with no middle name and no second address line still produces a statement.
     */
    @Test
    @DisplayName("a customer with no middle name and no second address line still statements")
    void aCustomerMissingOptionalAttributesStillStatements() {
        when(cardXrefs.findHeadingChunk("", "", StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of(sparseHeadingRow()));
        when(cardXrefs.findHeadingChunk(MASKED_CARD, FINGERPRINT,
                StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of());
        when(transactions.aggregateByCardFingerprint(anyString()))
                .thenReturn(aggregate("0.00", 0L));
        when(transactions.findWindowByCardFingerprint(anyString(), anyString(), anyInt()))
                .thenReturn(List.of());

        RecordingSink sink = new RecordingSink();

        assertThatCode(() -> service.generateStatements(sink)).doesNotThrowAnyException();
        assertThat(sink.plainRecords).as("the statement was produced rather than abandoned").isNotEmpty();
    }

    /**
     * Asserts that a run over an empty portfolio still clears the previous artifacts.
     */
    @Test
    @DisplayName("a run over an empty portfolio still clears the previous artifacts")
    void anEmptyRunStillClearsThePreviousArtifacts() {
        when(cardXrefs.findHeadingChunk("", "", StatementService.HEADING_CHUNK_SIZE))
                .thenReturn(List.of());

        RecordingSink sink = new RecordingSink();
        assertThatCode(() -> service.generateStatements(sink)).doesNotThrowAnyException();

        assertThat(sink.replacements).isEqualTo(1);
        assertThat(sink.plainRecords).isEmpty();
    }

    /**
     * Stubs the three keyed reads a single-card request path makes, plus its aggregate.
     */
    private void stubOneCard() {
        CardXrefView xref = xref(FINGERPRINT);
        when(cardXrefs.resolveByWholeCardNumber(SEED_CARD_NUMBER)).thenReturn(Optional.of(xref));
        when(cardXrefs.findById(FINGERPRINT)).thenReturn(Optional.of(xref));
        when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer()));
        when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(account()));
        when(transactions.aggregateByCardFingerprint(FINGERPRINT))
                .thenReturn(aggregate("-1234.56", 7L));
    }

    /**
     * Builds a cross-reference projection naming one fingerprint.
     *
     * @param fingerprint the fingerprint the projection carries
     * @return the projection
     */
    private static CardXrefView xref(String fingerprint) {
        return new CardXrefView(MASKED_CARD, fingerprint, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * Builds the customer projection the cross-reference names.
     *
     * @return the projection, carrying a credit score so the score's provenance is exercised
     */
    private static CustomerView customer() {
        return new CustomerView(CUSTOMER_ID, "ADA", "B", "LOVELACE",
                "1 MAIN ST", null, "ANYTOWN", "NY", "USA", "10001",
                LocalDate.of(1815, 12, 10), (short) 742);
    }

    /**
     * Builds the account projection the cross-reference names.
     *
     * @return the projection
     */
    private static AccountView account() {
        return new AccountView(ACCOUNT_ID, "Y", Money.of("100.00"), Money.of("5000.00"),
                LocalDate.of(2020, 1, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2024, 1, 1),
                "DEFAULT   ");
    }

    /**
     * Builds a window of transaction projections, distinct by identifier, for the arity cases.
     *
     * <p>Assumptions: the projection has no public constructor and no setters -- it is an entity whose
     * no-argument constructor is protected so the persistence provider can reach it -- so it is both
     * INSTANTIATED and populated reflectively. Field access is the same mechanism
     * {@code com.carddemo.reporting.domain.DiagnosticRenderingTest} already uses for this type; that
     * class calls the constructor directly because it sits in the projection's own package, and this one
     * cannot. Widening the constructor to satisfy a test was rejected outright: protected is the
     * narrowest visibility the persistence contract admits and the projection's own documentation says
     * so, and a production visibility loosened for a fixture is a change to the type's contract. Only
     * the three members a statement line reads are assigned, because the assembler at
     * {@code StatementService.writeTransaction} prepares a line from the transaction identifier, the
     * description and the amount alone; assigning the other ten would suggest they were load-bearing
     * here.</p>
     *
     * <p>Assumptions: every row carries a DISTINCT identifier, so a truncation that preserved a count by
     * repeating one row cannot pass the identity assertion the consuming case makes on the last line.</p>
     *
     * @param count the number of rows to build, which must be positive
     * @return the window in identifier order; never {@code null}
     */
    private static List<StatementTransactionView> transactionWindow(int count) {
        List<StatementTransactionView> window = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            StatementTransactionView row = newProjection();
            setMember(row, "key", new StatementTransactionView.StatementTransactionKey(
                    SEED_CARD_NUMBER, transactionId(index)));
            setMember(row, "description", "SPECIMEN LINE");
            setMember(row, "amount", Money.of("-1.00"));
            window.add(row);
        }
        return window;
    }

    /**
     * Instantiates the transaction projection through its protected no-argument constructor.
     *
     * @return a projection with every member unassigned; never {@code null}
     * @throws IllegalStateException if the constructor cannot be reached, which means the projection's
     *     declared shape changed rather than that a case under test failed
     */
    private static StatementTransactionView newProjection() {
        try {
            java.lang.reflect.Constructor<StatementTransactionView> declared =
                    StatementTransactionView.class.getDeclaredConstructor();
            declared.setAccessible(true);
            return declared.newInstance();
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException(
                    "the transaction projection declares no reachable no-argument constructor",
                    unreachable);
        }
    }

    /**
     * Renders the sixteen-character transaction identifier of one window position.
     *
     * <p>Assumptions: sixteen characters with the position zero-padded into the tail, matching the width
     * the transaction key declares. A shorter rendering would be refused while the line is prepared, so
     * the padding is part of the fixture rather than decoration.</p>
     *
     * @param index the zero-based window position
     * @return that position's identifier; never {@code null}
     */
    private static String transactionId(int index) {
        return "TRN" + String.format("%013d", index);
    }

    /**
     * Renders a distinct sixty-four-character card fingerprint for one position in a run.
     *
     * <p>Assumptions: the width is the fingerprint's declared sixty-four and the position is rendered
     * into the tail, so every value is distinct and every value is well formed. The run orders cards by
     * the whole heading tuple, so distinctness here is what makes a skipped or repeated card visible.</p>
     *
     * @param index the zero-based position in the run
     * @return that position's fingerprint; never {@code null}
     */
    private static String distinctFingerprint(int index) {
        return "c".repeat(58) + String.format("%06d", index);
    }

    /**
     * Assigns one declared member of a projection by field access.
     *
     * <p>Assumptions: a failure to reach a member is rethrown unchecked rather than reported as an
     * assertion failure, because a renamed member is a fault in this fixture and not a property the
     * consuming cases measure. Surfacing the two differently keeps them attributable.</p>
     *
     * @param target the instance to assign on; must not be {@code null}
     * @param member the declared field name; must not be {@code null}
     * @param value the value to assign
     * @throws IllegalStateException if the member cannot be reached or assigned
     */
    private static void setMember(Object target, String member, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(member);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException unreachable) {
            throw new IllegalStateException(
                    "the projection declares no assignable member " + member, unreachable);
        }
    }

    /**
     * Builds one aggregate answer.
     *
     * @param total the exact total the card's transactions sum to
     * @param lineCount how many transactions the card holds
     * @return the aggregate
     */
    private static StatementTransactionRepository.StatementAggregate aggregate(
            String total, long lineCount) {
        return new StatementTransactionRepository.StatementAggregate() {
            @Override
            public BigDecimal getTotal() {
                return new BigDecimal(total);
            }

            @Override
            public long getLineCount() {
                return lineCount;
            }
        };
    }

    /**
     * Builds one whole-run heading row naming a fingerprint, in the shape a populated customer produces.
     *
     * @param fingerprint the fingerprint the row carries
     * @return the row
     */
    private static StatementCardXrefRepository.StatementHeadingRow headingRow(String fingerprint) {
        return headingRow(fingerprint, "B", null);
    }

    /**
     * Builds one whole-run heading row whose two nullable attributes are both absent.
     *
     * <p>Assumptions: both are absent in one row rather than one each in two rows, because they are
     * normalised by one mechanism and a fix addressing only one of them should fail here rather than
     * half-pass.</p>
     *
     * @return the row
     */
    private static StatementCardXrefRepository.StatementHeadingRow sparseHeadingRow() {
        return headingRow(FINGERPRINT, null, null);
    }

    /**
     * Builds one whole-run heading row naming a fingerprint, with both optional attributes supplied.
     *
     * <p>Assumptions: the row is an anonymous implementation of the closed projection rather than a
     * mock, because every one of its fifteen accessors is read while a statement is assembled and a
     * mock returning {@code null} for an unstubbed accessor would fail the run's own resolution guard
     * rather than the case under test.</p>
     *
     * @param fingerprint the fingerprint the row carries
     * @param middleName the middle name the row carries, or {@code null} for a customer with none
     * @param addressLine2 the second address line the row carries, or {@code null} for a customer with
     *     none
     * @return the row
     */
    private static StatementCardXrefRepository.StatementHeadingRow headingRow(
            String fingerprint, String middleName, String addressLine2) {
        return new StatementCardXrefRepository.StatementHeadingRow() {
            @Override
            public String getCardNum() {
                return MASKED_CARD;
            }

            @Override
            public String getCardFingerprint() {
                return fingerprint;
            }

            @Override
            public Long getCustomerId() {
                return CUSTOMER_ID;
            }

            @Override
            public Long getAccountId() {
                return ACCOUNT_ID;
            }

            @Override
            public String getFirstName() {
                return "ADA";
            }

            @Override
            public String getMiddleName() {
                return middleName;
            }

            @Override
            public String getLastName() {
                return "LOVELACE";
            }

            @Override
            public String getAddressLine1() {
                return "1 MAIN ST";
            }

            @Override
            public String getAddressLine2() {
                return addressLine2;
            }

            @Override
            public String getAddressLine3() {
                return "ANYTOWN";
            }

            @Override
            public String getStateCode() {
                return "NY";
            }

            @Override
            public String getCountryCode() {
                return "USA";
            }

            @Override
            public String getPostalCode() {
                return "10001";
            }

            @Override
            public Short getFicoCreditScore() {
                return (short) 742;
            }

            @Override
            public Money getCurrentBalance() {
                return Money.of("100.00");
            }
        };
    }

    /**
     * A sink that records what a run offered it, so the run's shape is observable.
     *
     * <p>Assumptions: a recording implementation rather than a mock, because the cases above assert
     * ORDER as well as count -- that the clearing call precedes every record -- and an implementation
     * that appends is the plainest way to hold that.</p>
     */
    private static final class RecordingSink implements StatementService.StatementSink {

        /** How many times a run asked for the previous artifacts to be discarded. */
        private int replacements;

        /** The plain-text records a run offered, in the order it offered them. */
        private final List<byte[]> plainRecords = new ArrayList<>();

        /** The markup records a run offered, in the order it offered them. */
        private final List<byte[]> markupRecords = new ArrayList<>();

        /**
         * Records one clearing call, refusing one that arrives after a record has been written.
         *
         * <p>Assumptions: the ordering is enforced here rather than asserted afterwards, because a
         * clearing call that arrived late would discard records already offered and the resulting
         * artifact would be short by however many arrived first -- a difference a count taken at the
         * end cannot see.</p>
         *
         * @throws IllegalStateException if a record was written before the artifacts were cleared
         */
        @Override
        public void replaceArtifacts() {
            if (!plainRecords.isEmpty() || !markupRecords.isEmpty()) {
                throw new IllegalStateException(
                        "the previous artifacts were discarded after a record had been written");
            }
            replacements++;
        }

        /**
         * Records one plain-text record, defensively copied.
         *
         * <p>Assumptions: the array is copied rather than retained, because a producer is free to reuse
         * one buffer across records and a retained reference would make every recorded record equal to
         * the last one.</p>
         *
         * @param record the encoded statement record offered by the run
         */
        @Override
        public void writeStatementRecord(byte[] record) {
            plainRecords.add(record.clone());
        }

        /**
         * Records one markup record, defensively copied for the reason stated above.
         *
         * @param record the encoded markup record offered by the run
         */
        @Override
        public void writeMarkupRecord(byte[] record) {
            markupRecords.add(record.clone());
        }
    }

    /**
     * A destination that DISCARDS what it holds when a run clears it, as the baseline's job step does.
     *
     * <p>Assumptions: clearing empties the two buffers rather than counting the call, which is what
     * makes a second run over the same destination observable. {@code app/jcl/CREASTMT.JCL} deletes both
     * outputs in its own step at L66 to L75 and only then creates them at L79 onward, so two runs of the
     * night leave one copy of each artifact. A destination that merely counted clearings would report the
     * call and still hold both copies, which is exactly the state the case using this class exists to
     * rule out.</p>
     */
    private static final class ReplacingSink implements StatementService.StatementSink {

        /** How many times a run asked for the previous artifacts to be discarded. */
        private int replacements;

        /** The plain-text records the current artifact holds, in the order they were offered. */
        private final List<byte[]> plainRecords = new ArrayList<>();

        /** The markup records the current artifact holds, in the order they were offered. */
        private final List<byte[]> markupRecords = new ArrayList<>();

        /**
         * Discards both artifacts, so what follows is a replacement rather than an addition.
         */
        @Override
        public void replaceArtifacts() {
            plainRecords.clear();
            markupRecords.clear();
            replacements++;
        }

        /**
         * Appends one plain-text record to the current artifact, defensively copied.
         *
         * @param record the encoded statement record offered by the run
         */
        @Override
        public void writeStatementRecord(byte[] record) {
            plainRecords.add(record.clone());
        }

        /**
         * Appends one markup record to the current artifact, defensively copied.
         *
         * @param record the encoded markup record offered by the run
         */
        @Override
        public void writeMarkupRecord(byte[] record) {
            markupRecords.add(record.clone());
        }
    }

    /**
     * One fixture card: its walk anchor, its selector, its joined heading row and its own rows.
     *
     * @param cardNumber the whole sixteen-digit card number as {@code xreffile.txt} holds it
     * @param maskedCardNum the masked rendering the reporting relations publish, which is the leading
     *     component of the order the heading query declares
     * @param fingerprint the sixty-four-character selector standing for this card
     * @param headingRow the joined heading row a whole-run walk reads for this card
     * @param rows this card's transaction projections in ascending identifier order
     */
    private record FixtureCard(
            String cardNumber,
            String maskedCardNum,
            String fingerprint,
            StatementCardXrefRepository.StatementHeadingRow headingRow,
            List<StatementTransactionView> rows) {
    }

    /**
     * Reads one fixture file and returns its rows as fixed-width byte arrays.
     *
     * <p>Assumptions: the fixture is located on the test CLASSPATH rather than by a path relative to the
     * module, so a case behaves identically under a reactor build and under a single-module build. Each
     * line is one record of the declared length, which {@code ReportingFixtureContractTest} already
     * asserts for each of these four files, so no length check is repeated here.</p>
     *
     * @param fileName the fixture file name, relative to the fixtures directory on the test classpath
     * @return one byte array per fixture row, in file order; never {@code null}
     * @throws IllegalStateException if the fixture directory is not on the test classpath or is not
     *     addressable as a path, which is a broken test resource rather than a failure of the subject
     * @throws UncheckedIOException if the named file cannot be read
     */
    private static List<byte[]> fixtureRows(String fileName) {
        java.net.URL located =
                StatementServiceTest.class.getClassLoader().getResource(FIXTURE_DIRECTORY);
        if (located == null) {
            throw new IllegalStateException(
                    "fixture directory " + FIXTURE_DIRECTORY + " is not on the test classpath");
        }
        try {
            Path file = Path.of(located.toURI()).resolve(fileName);
            List<byte[]> rows = new ArrayList<>();
            for (String row : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                if (!row.isEmpty()) {
                    rows.add(row.getBytes(StandardCharsets.US_ASCII));
                }
            }
            return rows;
        } catch (java.net.URISyntaxException malformed) {
            throw new IllegalStateException("fixture directory is not addressable as a path", malformed);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("fixture " + fileName + " could not be read", unreadable);
        }
    }

    /**
     * Decodes every row of one fixture against the layout the shared registry holds for it.
     *
     * <p>Assumptions: the offsets come from {@link CopybookLayout} and are never written out here. That
     * registry transcribes {@code app/cpy/} and is asserted against it by its own tests, so a second
     * copy of a byte position in this file would be a second place for one number to be wrong in, with
     * each suite passing against its own arithmetic.</p>
     *
     * @param fileName the fixture file name on the test classpath
     * @param layoutName the registry name of the layout to decode against, such as {@code TRNX}
     * @return one decoded field map per row, keyed by copybook field name, in file order; never
     *     {@code null}
     */
    private static List<Map<String, Object>> decodedFixture(String fileName, String layoutName) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(layoutName);
        List<Map<String, Object>> decoded = new ArrayList<>();
        for (byte[] row : fixtureRows(fileName)) {
            decoded.add(FixedWidthCodec.decodeRecord(row, spec));
        }
        return decoded;
    }

    /**
     * Decodes one fixture and keys its rows by one unsigned identifier field.
     *
     * @param fileName the fixture file name on the test classpath
     * @param layoutName the registry name of the layout to decode against
     * @param keyField the copybook field name of the unsigned identifier to key by
     * @return the decoded rows keyed by that identifier, in file order; never {@code null}
     */
    private static Map<Long, Map<String, Object>> keyedFixture(
            String fileName, String layoutName, String keyField) {
        Map<Long, Map<String, Object>> keyed = new LinkedHashMap<>();
        for (Map<String, Object> row : decodedFixture(fileName, layoutName)) {
            keyed.put((Long) row.get(keyField), row);
        }
        return keyed;
    }

    /**
     * Reads one decoded text field with its declared blank padding removed.
     *
     * @param fields the decoded row as {@link FixedWidthCodec} returned it
     * @param fieldName the copybook field name to read
     * @return that field's value with trailing blanks removed; never {@code null}
     */
    private static String trimmedText(Map<String, Object> fields, String fieldName) {
        return String.valueOf(fields.get(fieldName)).stripTrailing();
    }

    /**
     * Groups the transaction fixture into projections by card, in ascending identifier order.
     *
     * <p>Assumptions: the fixture is already ordered by card and then by identifier, which is the order
     * {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at L53 of {@code app/jcl/CREASTMT.JCL} produces, so
     * grouping in file order preserves the within-card order the repository query also declares. No
     * re-sort is applied, because a re-sort here would hide a fixture that had stopped being ordered.</p>
     *
     * <p>Assumptions: only the three members a statement line reads are assigned -- the key, the
     * description and the amount -- because {@code writeTransaction} prepares a line from those alone.
     * Assigning the other ten would suggest they were load-bearing here.</p>
     *
     * @return the card's rows keyed by whole card number; never {@code null}
     */
    private static Map<String, List<StatementTransactionView>> fixtureTransactionsByCard() {
        Map<String, List<StatementTransactionView>> byCard = new LinkedHashMap<>();
        for (Map<String, Object> row : decodedFixture(TRANSACTION_FIXTURE, "TRNX")) {
            String cardNumber = trimmedText(row, "TRNX-CARD-NUM");
            StatementTransactionView projection = newProjection();
            setMember(projection, "key", new StatementTransactionView.StatementTransactionKey(
                    cardNumber, trimmedText(row, "TRNX-ID")));
            setMember(projection, "description", trimmedText(row, "TRNX-DESC"));
            setMember(projection, "amount", Money.of((BigDecimal) row.get("TRNX-AMT")));
            byCard.computeIfAbsent(cardNumber, card -> new ArrayList<>()).add(projection);
        }
        return byCard;
    }

    /**
     * Renders the sixty-four-character selector standing for one whole card number.
     *
     * <p>Assumptions: the whole card number is placed in the tail of the selector, so every selector is
     * distinct, every selector orders the way the card number it stands for does, and the selector
     * CONTAINS the card number. The last of those is deliberate: the disclosure case below asserts that
     * no emitted record carries a whole card number, and embedding it in the selector means a rendering
     * that leaked either value fails that one assertion rather than only the one written for it. A
     * digest would satisfy neither property -- it reorders the cards, so the expected statement order
     * would depend on the digest function rather than on the fixture.</p>
     *
     * @param cardNumber the whole card number the selector stands for
     * @return the selector at the width {@link CardXrefView#CARD_FINGERPRINT_WIDTH}; never {@code null}
     */
    private static String fingerprintOf(String cardNumber) {
        return "f".repeat(CardXrefView.CARD_FINGERPRINT_WIDTH - cardNumber.length()) + cardNumber;
    }

    /**
     * Builds one joined heading row from a cross-reference row and its customer and account rows.
     *
     * <p>Assumptions: the row is an anonymous implementation of the closed projection rather than a
     * mock, for the reason the builder above records -- all fifteen accessors are read while a statement
     * is assembled, and a mock answering an unstubbed accessor with {@code null} would fail the run's
     * own resolution guard rather than the case under test.</p>
     *
     * <p>Assumptions: the text attributes arrive right-trimmed, because the relation projects
     * {@code VARCHAR} columns in which a trailing blank is padding rather than data, and the band
     * assembler materialises each item back to its declared width. Handing over the fixture's padded
     * bytes instead would reach the same bytes for the three name parts, which are cut at their first
     * blank anyway, so trimming states the contract the relation actually has rather than resting on
     * that coincidence.</p>
     *
     * @param cardNumber the whole card number the cross-reference row names
     * @param customerId the customer identifier that row names
     * @param accountId the account identifier that row names
     * @param customer the decoded customer fixture row for {@code customerId}
     * @param account the decoded account fixture row for {@code accountId}
     * @return the heading row with all fifteen components resolved; never {@code null}
     */
    private static StatementCardXrefRepository.StatementHeadingRow fixtureHeadingRow(
            String cardNumber, long customerId, long accountId,
            Map<String, Object> customer, Map<String, Object> account) {
        return new StatementCardXrefRepository.StatementHeadingRow() {
            @Override
            public String getCardNum() {
                return CardNumberMasker.mask(cardNumber);
            }

            @Override
            public String getCardFingerprint() {
                return fingerprintOf(cardNumber);
            }

            @Override
            public Long getCustomerId() {
                return customerId;
            }

            @Override
            public Long getAccountId() {
                return accountId;
            }

            @Override
            public String getFirstName() {
                return trimmedText(customer, "CUST-FIRST-NAME");
            }

            @Override
            public String getMiddleName() {
                return trimmedText(customer, "CUST-MIDDLE-NAME");
            }

            @Override
            public String getLastName() {
                return trimmedText(customer, "CUST-LAST-NAME");
            }

            @Override
            public String getAddressLine1() {
                return trimmedText(customer, "CUST-ADDR-LINE-1");
            }

            @Override
            public String getAddressLine2() {
                return trimmedText(customer, "CUST-ADDR-LINE-2");
            }

            @Override
            public String getAddressLine3() {
                return trimmedText(customer, "CUST-ADDR-LINE-3");
            }

            @Override
            public String getStateCode() {
                return trimmedText(customer, "CUST-ADDR-STATE-CD");
            }

            @Override
            public String getCountryCode() {
                return trimmedText(customer, "CUST-ADDR-COUNTRY-CD");
            }

            @Override
            public String getPostalCode() {
                return trimmedText(customer, "CUST-ADDR-ZIP");
            }

            @Override
            public Short getFicoCreditScore() {
                return ((Long) customer.get("CUST-FICO-CREDIT-SCORE")).shortValue();
            }

            @Override
            public Money getCurrentBalance() {
                return Money.of((BigDecimal) account.get("ACCT-CURR-BAL"));
            }
        };
    }

    /**
     * Builds the fixture cards a run walks, in the order the heading query declares.
     *
     * <p>Assumptions: the walk order is the pair (masked rendering, selector) ascending, because that is
     * the order {@code findHeadingChunk} declares, and it is NOT ascending whole card number. The
     * relation publishes the masked rendering, so the leading component carries only the last four
     * digits and the selector breaks a tie between two cards sharing them. Sorting here by the same pair
     * makes the expected statement order a property of the query rather than of the fixture's file
     * order.</p>
     *
     * <p>Assumptions: each heading row is JOINED from the three fixtures rather than invented, so every
     * printed attribute, the credit score and the balance are the shipped seed's own values at their
     * declared widths. The cross-reference fixture names four customers and four accounts across its 88
     * cards and both are present in their own fixtures, so every selected row resolves.</p>
     *
     * @param cardNumbers the whole card numbers to select, or none at all to select every card the
     *     cross-reference fixture holds
     * @return the selected cards in ascending walk order; never {@code null}
     * @throws IllegalStateException if a selected cross-reference row names a customer or an account the
     *     fixtures do not hold, which is a broken test resource rather than a failure of the subject
     */
    private static List<FixtureCard> fixtureCards(String... cardNumbers) {
        List<String> selection = List.of(cardNumbers);
        Map<Long, Map<String, Object>> customers =
                keyedFixture(CUSTOMER_FIXTURE, "CUSTOMER", "CUST-ID");
        Map<Long, Map<String, Object>> accounts =
                keyedFixture(ACCOUNT_FIXTURE, "ACCOUNT", "ACCT-ID");
        Map<String, List<StatementTransactionView>> rowsByCard = fixtureTransactionsByCard();

        List<FixtureCard> cards = new ArrayList<>();
        for (Map<String, Object> crossReference : decodedFixture(CROSS_REFERENCE_FIXTURE, "XREF")) {
            String cardNumber = trimmedText(crossReference, "XREF-CARD-NUM");
            if (!selection.isEmpty() && !selection.contains(cardNumber)) {
                continue;
            }
            long customerId = (Long) crossReference.get("XREF-CUST-ID");
            long accountId = (Long) crossReference.get("XREF-ACCT-ID");
            Map<String, Object> customer = customers.get(customerId);
            Map<String, Object> account = accounts.get(accountId);
            if (customer == null || account == null) {
                throw new IllegalStateException("the cross-reference fixture names customer "
                        + customerId + " or account " + accountId + " and its own fixture omits it");
            }
            cards.add(new FixtureCard(cardNumber, CardNumberMasker.mask(cardNumber),
                    fingerprintOf(cardNumber),
                    fixtureHeadingRow(cardNumber, customerId, accountId, customer, account),
                    rowsByCard.getOrDefault(cardNumber, List.of())));
        }
        cards.sort(Comparator.comparing(FixtureCard::maskedCardNum)
                .thenComparing(FixtureCard::fingerprint));
        return List.copyOf(cards);
    }

    /**
     * Installs keyset-faithful answers for the two cursors a whole run drives.
     *
     * <p>Assumptions: both answers implement the CONTINUATION PREDICATE the repository declares rather
     * than returning a prepared chunk per call. The heading answer admits a row strictly past the
     * anchor pair and the window answer admits a row strictly past the last identifier, each bounded by
     * the limit it was asked for, so a service that failed to advance either anchor loops on the same
     * chunk and a service that advanced it by the wrong component skips or repeats rows. A
     * call-count-keyed stub would answer the second call correctly however the anchor had been built,
     * which is the defect these answers are shaped to catch.</p>
     *
     * <p>Assumptions: the aggregate read is deliberately left unstubbed. A whole run reads the two
     * cursors alone, so a stubbed aggregate would state a collaboration the run does not have.</p>
     *
     * @param cards the run's cards in ascending walk order; must not be {@code null}
     */
    private void stubFixtureWalk(List<FixtureCard> cards) {
        when(cardXrefs.findHeadingChunk(anyString(), anyString(), anyInt())).thenAnswer(call -> {
            String afterCardNum = call.getArgument(0);
            String afterFingerprint = call.getArgument(1);
            int limit = call.getArgument(2);
            List<StatementCardXrefRepository.StatementHeadingRow> chunk = new ArrayList<>();
            for (FixtureCard card : cards) {
                int byCardNum = card.maskedCardNum().compareTo(afterCardNum);
                boolean pastTheAnchor = byCardNum > 0
                        || (byCardNum == 0 && card.fingerprint().compareTo(afterFingerprint) > 0);
                if (pastTheAnchor && chunk.size() < limit) {
                    chunk.add(card.headingRow());
                }
            }
            return List.copyOf(chunk);
        });

        Map<String, List<StatementTransactionView>> rowsBySelector = new LinkedHashMap<>();
        for (FixtureCard card : cards) {
            rowsBySelector.put(card.fingerprint(), card.rows());
        }
        when(transactions.findWindowByCardFingerprint(anyString(), anyString(), anyInt()))
                .thenAnswer(call -> {
                    String fingerprint = call.getArgument(0);
                    String after = call.getArgument(1);
                    int limit = call.getArgument(2);
                    List<StatementTransactionView> window = new ArrayList<>();
                    for (StatementTransactionView row
                            : rowsBySelector.getOrDefault(fingerprint, List.of())) {
                        if (row.key().transactionId().compareTo(after) > 0 && window.size() < limit) {
                            window.add(row);
                        }
                    }
                    return List.copyOf(window);
                });
    }

    /**
     * Reads one band item out of an emitted record, at the offset the band descriptor declares.
     *
     * @param record one emitted record, of the band's declared length
     * @param band the band descriptor the record was assembled from
     * @param itemName the declared field name of the item to read
     * @return that item exactly as emitted, including any declared padding; never {@code null}
     */
    private static String bandItem(byte[] record, CopybookLayout.RecordSpec band, String itemName) {
        CopybookLayout.FieldSpec item = band.field(itemName);
        return new String(record, item.start(), item.length(), StandardCharsets.US_ASCII);
    }

    /**
     * Right-trims one emitted record the way line-sequential output trims a written record.
     *
     * <p>Assumptions: the two shipped oracles store right-trimmed lines -- the plain-text artifact in
     * eleven width classes from 9 to 80 and the markup artifact from 4 to 85 characters -- because the
     * runtime drops trailing blanks on the way out. Normalising the EMITTED record down to that form is
     * the only way a comparison against either artifact can hold, and it is done here rather than by
     * relaxing the padding requirement: the service still emits the full declared width, which the
     * width case asserts separately on every record of a run.</p>
     *
     * @param record one emitted record
     * @return the record rendered with its trailing blanks removed; never {@code null}
     */
    private static String rightTrimmed(byte[] record) {
        return new String(record, StandardCharsets.US_ASCII).stripTrailing();
    }

    /**
     * Extracts the transaction identifiers of the detail lines in one emitted plain-text stream.
     *
     * <p>Assumptions: a detail line is recognised by its leading item being sixteen DIGITS, which is
     * what {@code ST-TRANID} carries and what no other band of the statement can carry. The two banners
     * lead with an asterisk run, the six rules with hyphens, the three basic-detail bands with their
     * label literals, the column headings with {@code Tran ID}, the trailer with {@code Total EXP:}, and
     * the four name and address bands with the shipped seed's own values, none of which begins with
     * sixteen consecutive digits. Matching on the band descriptor would be stronger still, but a band
     * descriptor is not recoverable from an emitted record -- the sink receives bytes, which is exactly
     * what the artifact holds.</p>
     *
     * @param plainRecords the plain-text records a run offered, in offer order
     * @return the identifiers of the detail lines, in the order they were emitted; never {@code null}
     */
    private static List<String> renderedTransactionIdentifiers(List<byte[]> plainRecords) {
        List<String> identifiers = new ArrayList<>();
        for (byte[] record : plainRecords) {
            String leading = bandItem(record, StatementBandLayouts.ST_LINE14, "ST-TRANID");
            if (leading.chars().allMatch(Character::isDigit)) {
                identifiers.add(leading);
            }
        }
        return identifiers;
    }

    /**
     * Reports how many plain-text records one statement of a given size occupies.
     *
     * @param transactionCount how many transactions that statement renders
     * @return the header block, one line per transaction and the trailer, summed
     */
    private static int plainRecordsPerStatement(int transactionCount) {
        return StatementTextMapper.HEADER_BLOCK_LINE_COUNT + transactionCount
                + StatementTextMapper.CARD_TRAILER_LINE_COUNT;
    }

    /**
     * Reports how many markup records one statement of a given size occupies.
     *
     * @param transactionCount how many transactions that statement renders
     * @return the document header, the name and address block, eleven lines per transaction and the
     *     document footer, summed
     */
    private static int markupRecordsPerStatement(int transactionCount) {
        return StatementHtmlMapper.DOCUMENT_HEADER_LINE_COUNT
                + StatementHtmlMapper.NAME_ADDRESS_BASIC_DETAIL_LINE_COUNT
                + StatementHtmlMapper.TRANSACTION_ROW_LINE_COUNT * transactionCount
                + StatementHtmlMapper.DOCUMENT_FOOTER_LINE_COUNT;
    }

    /**
     * Finds the first emitted record whose named band item carries an expected value.
     *
     * <p>Assumptions: the item is located through the band descriptor rather than through a written-out
     * offset, so a band whose geometry changed fails in the mapper package that owns it rather than
     * silently reading the wrong span here.</p>
     *
     * @param records the emitted records to search, in offer order
     * @param band the band descriptor whose item names the position to read
     * @param itemName the declared field name of the item to compare
     * @param expectedItem the value that item carries in the wanted record, at its declared width
     * @return the first matching record; never {@code null}
     * @throws IllegalStateException if no record carries that value, which means the band was not
     *     emitted at all and is reported separately from an assertion about its content
     */
    private static byte[] firstRecordWithItem(List<byte[]> records, CopybookLayout.RecordSpec band,
            String itemName, String expectedItem) {
        for (byte[] record : records) {
            if (bandItem(record, band, itemName).equals(expectedItem)) {
                return record;
            }
        }
        throw new IllegalStateException(
                "no emitted record carries " + expectedItem + " in " + band.name() + "." + itemName);
    }

    /**
     * Splits one emitted plain-text stream into the detail identifiers of each statement.
     *
     * <p>Assumptions: the split is on the opening banner, which
     * {@code WRITE FD-STMTFILE-REC FROM ST-LINE0} at L460 of {@code app/cbl/CBSTM03A.CBL} writes exactly
     * once per statement. Splitting on the banner rather than counting a fixed block size keeps the
     * grouping independent of how many records a heading happens to occupy, which is the mapper
     * package's concern rather than this one's.</p>
     *
     * @param plainRecords the plain-text records a run offered, in offer order
     * @return one list of identifiers per statement, in the order the statements were emitted; never
     *     {@code null}
     */
    private static List<List<String>> identifiersPerStatement(List<byte[]> plainRecords) {
        String openingBanner = StatementBandLayouts.OPENING_ASTERISK_RUN
                + StatementBandLayouts.START_OF_STATEMENT_SENTINEL
                + StatementBandLayouts.OPENING_ASTERISK_RUN;
        List<List<String>> perStatement = new ArrayList<>();
        for (byte[] record : plainRecords) {
            if (new String(record, StandardCharsets.US_ASCII).equals(openingBanner)) {
                perStatement.add(new ArrayList<>());
                continue;
            }
            String leading = bandItem(record, StatementBandLayouts.ST_LINE14, "ST-TRANID");
            if (!perStatement.isEmpty() && leading.chars().allMatch(Character::isDigit)) {
                perStatement.get(perStatement.size() - 1).add(leading);
            }
        }
        return perStatement;
    }

    /**
     * Renders the transaction identifiers one fixture card holds, in fixture order.
     *
     * @param card the fixture card whose rows are wanted
     * @return that card's identifiers in ascending order; never {@code null}
     */
    private static List<String> identifiersOf(FixtureCard card) {
        return card.rows().stream().map(row -> row.key().transactionId()).toList();
    }

    /**
     * The two arities the migrated generator does not have, driven decisively past both of them.
     *
     * <p>Purpose: these two cases are the executable form of divergence {@code D-2}, and they are two
     * cases rather than one because two independent tables were removed. Each drives its own axis from a
     * shipped fixture and asserts what the run EMITTED, so an implementation that fetched every row and
     * then dropped some while rendering fails here rather than passing quietly.</p>
     */
    @Nested
    @DisplayName("the two removed statement-table arities")
    class RemovedArities {

        /**
         * Asserts one card's 600 transactions all reach both artifacts, past the inner overrun.
         *
         * <p>Purpose: {@code F-STMT-INNER-OVERFLOW} is the marker under which {@code tests/README.md}
         * records the inner, same-card table's measured overrun at its L70 to L82: one card renders up to
         * 512 transactions and the 513th faults. The migrated service holds no such table, so the 600th
         * transaction of a card must be as ordinary as its first. This case drives the shipped fixture's
         * 600 rows for one card and asserts the emitted identifiers are those 600, in order, with the
         * per-artifact record budgets to match.</p>
         *
         * <p>Refactoring Rationale: the two thresholds are stated here separately because the numbers
         * differ on both axes and by two orders of magnitude. The inner table is declared
         * {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} at L228 of {@code app/cbl/CBSTM03A.CBL} and measured at
         * 512, so its declaration is not its failure boundary; the outer table is declared
         * {@code 05 WS-CARD-TBL OCCURS 51 TIMES} at L226 with its parallel counter
         * {@code 05 WS-TRN-TBL-CTR OCCURS 51 TIMES} at L232 and measured at 51 distinct CARDS, which is a
         * different axis entirely. {@code tests/README.md} L70 to L82 states the same conclusion in its
         * own words and records its reasoning at L80 to L82. Neither figure is the other's, and this case
         * asserts only the transaction axis; its sibling below asserts the card axis under
         * {@code F-STMT-OUTER-OVERFLOW}.</p>
         *
         * <p>Trade-offs: the fixture EXCEEDS the measured threshold by 88 rows where the COBOL suite
         * stays under it. {@code tests/README.md} L78 to L80 keeps every one of its fixtures safely under
         * both bounds, and it has to, because the baseline faults past either. The opposite choice is made
         * here on purpose: the property under test is the absence of a bound, which a fixture inside the
         * bound cannot distinguish from a larger bound.</p>
         *
         * <p>WHY: the provenance is the inner traversal of {@code 4000-TRNXFILE-GET} at L416 to L432 of
         * {@code app/cbl/CBSTM03A.CBL}, whose {@code PERFORM VARYING TR-JMP} is bounded by
         * {@code WS-TRCT (CR-JMP)} and calls {@code 6000-WRITE-TRANS} at L428 once per row. The streaming
         * design replaces that bounded traversal, and this case is what holds it to rendering every
         * row.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a card carrying 600 transactions renders every one of them, past F-STMT-INNER-OVERFLOW")
        void everyTransactionOfACardPastTheInnerOverflowMarkerReachesBothArtifacts() {
            List<FixtureCard> cards = fixtureCards(INNER_OVERFLOW_CARD);
            stubFixtureWalk(cards);
            List<String> fixtureIdentifiers = identifiersOf(cards.get(0));

            RecordingSink sink = new RecordingSink();
            StatementRunOutcome outcome = service.generateStatements(sink);

            assertThat(fixtureIdentifiers)
                    .as("the shipped fixture supplies 600 rows for card %s", INNER_OVERFLOW_CARD)
                    .hasSize(INNER_OVERFLOW_FIXTURE_ROWS);
            assertThat(INNER_OVERFLOW_FIXTURE_ROWS)
                    .as("and 600 stands decisively past the measured inner overrun of 512, so an "
                            + "off-by-one cap at that boundary cannot pass this case")
                    .isGreaterThan(BASELINE_INNER_TABLE_THRESHOLD);
            assertThat(renderedTransactionIdentifiers(sink.plainRecords))
                    .as("every fixture row reaches the plain-text artifact, in its own order; the "
                            + "identifiers are compared rather than counted so a run that preserved the "
                            + "count by repeating a row could not pass")
                    .containsExactlyElementsOf(fixtureIdentifiers);
            assertThat(sink.plainRecords)
                    .as("the plain-text artifact holds the header block, 600 detail lines and the trailer")
                    .hasSize(plainRecordsPerStatement(INNER_OVERFLOW_FIXTURE_ROWS));
            assertThat(sink.markupRecords)
                    .as("and the markup artifact holds eleven records for each of the same 600 rows, "
                            + "so a row dropped from one artifact alone would fail here")
                    .hasSize(markupRecordsPerStatement(INNER_OVERFLOW_FIXTURE_ROWS));
            assertThat(outcome.statementsProduced()).as("one card, one statement").isEqualTo(1);
            assertThat(outcome.index()).hasSize(1);
            assertThat(outcome.index().get(0).recordCount())
                    .as("and the index accounts for every record that statement wrote")
                    .isEqualTo(plainRecordsPerStatement(INNER_OVERFLOW_FIXTURE_ROWS));
        }

        /**
         * Asserts a run over 88 distinct cards statements every one of them, past the outer overrun.
         *
         * <p>Purpose: {@code F-STMT-OUTER-OVERFLOW} is the marker under which {@code tests/README.md}
         * records the outer, distinct-card table's limit at its L70 to L82: 51 distinct cards render and
         * the 52nd faults. The migrated run walks the portfolio by keyset and holds no card table, so the
         * 88th card must be as ordinary as the first. This case drives every card the cross-reference
         * fixture holds and asserts a statement for each, sized to that card's own row count, with the
         * run's 700 detail lines all present.</p>
         *
         * <p>Refactoring Rationale: the two thresholds are stated here separately for the same reason
         * they are separated in the sibling case above. The outer table is declared
         * {@code 05 WS-CARD-TBL OCCURS 51 TIMES} at L226 of {@code app/cbl/CBSTM03A.CBL}, with the
         * parallel counter {@code 05 WS-TRN-TBL-CTR OCCURS 51 TIMES} at L232, and 51 is both its
         * declaration and its measurement -- on the CARD axis. The inner table is declared
         * {@code 10 WS-TRAN-TBL OCCURS 10 TIMES} at L228 and measured at 512 under
         * {@code F-STMT-INNER-OVERFLOW} -- on the TRANSACTION axis, where its declaration is not its
         * boundary. {@code tests/README.md} L70 to L82 records both and its own reasoning at L80 to L82.
         * This case asserts the card axis alone.</p>
         *
         * <p>Trade-offs: 88 cards EXCEED the outer limit by 37 where the COBOL suite stays under it, for
         * the reason its own L78 to L80 gives -- the baseline faults past 51, so its fixtures cannot go
         * there. This tree must, because a run of 51 or fewer cards cannot tell the absence of a card
         * table from a card table that happens to be larger.</p>
         *
         * <p>WHY: the provenance is the outer traversal of {@code 4000-TRNXFILE-GET} at L416 to L432 of
         * {@code app/cbl/CBSTM03A.CBL}, whose {@code PERFORM VARYING CR-JMP} is bounded by
         * {@code CR-CNT} over {@code WS-CARD-NUM (CR-JMP)}, driven once per cross-reference row from the
         * mainline at L326. The chunked keyset walk replaces that bounded traversal.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a run over 88 distinct cards produces every statement, past F-STMT-OUTER-OVERFLOW")
        void everyCardOfARunPastTheOuterOverflowMarkerProducesItsOwnStatement() {
            List<FixtureCard> cards = fixtureCards();
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            StatementRunOutcome outcome = service.generateStatements(sink);

            assertThat(cards)
                    .as("the shipped cross-reference fixture holds 88 distinct cards")
                    .hasSize(OUTER_OVERFLOW_FIXTURE_CARDS);
            assertThat(OUTER_OVERFLOW_FIXTURE_CARDS)
                    .as("and 88 stands decisively past the declared and measured outer limit of 51")
                    .isGreaterThan(BASELINE_OUTER_TABLE_ARITY);
            assertThat(outcome.statementsProduced())
                    .as("every card is statemented, so no card table bounds this run")
                    .isEqualTo(OUTER_OVERFLOW_FIXTURE_CARDS);
            assertThat(outcome.index())
                    .extracting(StatementIndexEntry::cardFingerprint)
                    .as("and the index names each card exactly once, so a skipped or repeated card "
                            + "could not pass")
                    .containsExactlyInAnyOrderElementsOf(
                            cards.stream().map(FixtureCard::fingerprint).toList());
            // WHY : Refactoring Rationale: the membership above is asserted WITHOUT regard to order and
            //       the ordering is asserted separately below, where both were previously asserted at
            //       once by comparing the index against the walk-order list. They are two different
            //       orders on purpose: the run walks by masked card number and the index is sorted by
            //       fingerprint for the binary search that reads it, and this fixture is one where they
            //       disagree -- every fingerprint here is a constant prefix followed by the WHOLE card
            //       number, while the walk leads on the masked rendering, which is the last four digits.
            //       Asserting the two together made a correctly-sorted index fail a case whose subject
            //       is the absence of a card-table arity.
            assertThat(outcome.index())
                    .as("the index is ordered for the binary search that reads it")
                    .isSortedAccordingTo(
                            java.util.Comparator.comparing(StatementIndexEntry::cardFingerprint));

            // WHY : Assumptions: contiguity is walked in the order the statements were WRITTEN and each
            //       card's entry is looked up BY FINGERPRINT rather than by its ordinal in the index.
            //       Indexing positionally asserted the index's order a second time; looking the entry up
            //       keeps the property that matters -- that the statements tile the artifact with no gap
            //       and no overlap -- while leaving the index free to be sorted for lookup.
            Map<String, StatementIndexEntry> entriesByFingerprint = new LinkedHashMap<>();
            for (StatementIndexEntry entry : outcome.index()) {
                entriesByFingerprint.put(entry.cardFingerprint(), entry);
            }
            long accountedRecords = 0;
            for (int position = 0; position < cards.size(); position++) {
                FixtureCard card = cards.get(position);
                StatementIndexEntry entry = entriesByFingerprint.get(card.fingerprint());
                assertThat(entry)
                        .as("card %d of the walk has an index entry", position)
                        .isNotNull();
                assertThat(entry.firstRecord())
                        .as("statement %d begins where the statement before it ended", position)
                        .isEqualTo(accountedRecords);
                assertThat(entry.recordCount())
                        .as("statement %d renders every one of its %d fixture rows", position,
                                card.rows().size())
                        .isEqualTo(plainRecordsPerStatement(card.rows().size()));
                accountedRecords += entry.recordCount();
            }

            assertThat(renderedTransactionIdentifiers(sink.plainRecords))
                    .as("and the run's detail lines are all 700 rows of the transaction fixture")
                    .hasSize(TRANSACTION_FIXTURE_ROWS);
            assertThat(accountedRecords)
                    .as("with the index accounting for every plain-text record all 88 statements wrote")
                    .isEqualTo(sink.plainRecords.size());
        }
    }

    /**
     * The order a statement is assembled in, and the accumulator that resets between statements.
     *
     * <p>Purpose: the mainline at L316 to L339 of {@code app/cbl/CBSTM03A.CBL} fixes an order and one
     * reset, and both are invisible to a case that only counts records. These cases assert the order the
     * records arrive in, the reads the keyed path performs, and that a total covers its own card.</p>
     */
    @Nested
    @DisplayName("the mainline order and the per-card total")
    class MainlineOrder {

        /**
         * Asserts the keyed request path reads the cross-reference, then the customer, then the account.
         *
         * <p>Purpose: {@code 1000-MAINLINE} performs {@code 1000-XREFFILE-GET-NEXT} at L319,
         * {@code 2000-CUSTFILE-GET} at L321 and {@code 3000-ACCTFILE-GET} at L322, in that order, and the
         * two dimension reads are keyed by values the cross-reference row supplies -- L372 to L374 moves
         * {@code XREF-CUST-ID} and computes the key length as {@code LENGTH OF XREF-CUST-ID}, and L396 to
         * L398 does the same for the eleven digits of {@code XREF-ACCT-ID}. A path that read a dimension
         * first would have nothing to key it with.</p>
         *
         * <p>Assumptions: the order is asserted on the keyed single-card path rather than on a whole run,
         * because the run reads its heading from one joined chunk and therefore makes no separate
         * dimension calls at all. Asserting an order that the run does not have would state a
         * collaboration this service deliberately removed.</p>
         *
         * <p>WHY: the provenance is L319, L321 and L322 of {@code app/cbl/CBSTM03A.CBL} for the sequence
         * and L372 to L374 with L396 to L398 for the keys those two reads use.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the keyed path reads the cross-reference, then the customer, then the account")
        void theKeyedPathReadsTheCrossReferenceThenTheCustomerThenTheAccount() {
            stubOneCard();

            service.describe(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR);

            InOrder reads = Mockito.inOrder(cardXrefs, customers, accounts);
            reads.verify(cardXrefs).resolveByWholeCardNumber(SEED_CARD_NUMBER);
            reads.verify(cardXrefs).findById(FINGERPRINT);
            reads.verify(customers).findById(CUSTOMER_ID);
            reads.verify(accounts).findById(ACCOUNT_ID);
        }

        /**
         * Asserts each card's heading is emitted once and before that card's own detail lines.
         *
         * <p>Purpose: the mainline performs {@code 5000-CREATE-STATEMENT} at L323 and only then
         * {@code 4000-TRNXFILE-GET} at L326, once per cross-reference row, so a statement's banner and
         * heading block stand ahead of its detail lines and are written exactly once per card. A run that
         * emitted one heading for the whole night, or one per transaction, would still write the right
         * total of records for a single-card fixture, which is why this case drives four cards.</p>
         *
         * <p>Assumptions: the heading is counted by its opening banner, which
         * {@code WRITE FD-STMTFILE-REC FROM ST-LINE0} at L460 writes once per statement, and the closing
         * banner written at L437 counts the trailers. Counting the banners rather than the whole block
         * makes the assertion independent of the block's internal composition, which the mapper package
         * owns.</p>
         *
         * <p>WHY: the provenance is L323 and L326 of {@code app/cbl/CBSTM03A.CBL} for the order, L460 for
         * the run of banner writes and L437 for the closing banner.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("each card's heading is emitted once and ahead of that card's detail lines")
        void eachCardsHeadingIsEmittedOnceAndAheadOfItsDetailLines() {
            List<FixtureCard> cards = fixtureCards(DISTINCT_TOTAL_CARDS.toArray(new String[0]));
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);

            String openingBanner = StatementBandLayouts.OPENING_ASTERISK_RUN
                    + StatementBandLayouts.START_OF_STATEMENT_SENTINEL
                    + StatementBandLayouts.OPENING_ASTERISK_RUN;
            String closingBanner = StatementBandLayouts.CLOSING_ASTERISK_RUN
                    + StatementBandLayouts.END_OF_STATEMENT_SENTINEL
                    + StatementBandLayouts.CLOSING_ASTERISK_RUN;
            List<String> rendered = new ArrayList<>();
            for (byte[] record : sink.plainRecords) {
                rendered.add(new String(record, StandardCharsets.US_ASCII));
            }

            assertThat(rendered.stream().filter(openingBanner::equals).count())
                    .as("one heading per card and no more")
                    .isEqualTo(cards.size());
            assertThat(rendered.stream().filter(closingBanner::equals).count())
                    .as("and one trailer per card")
                    .isEqualTo(cards.size());
            assertThat(rendered.get(0))
                    .as("the run opens with a heading rather than with a detail line")
                    .isEqualTo(openingBanner);
            assertThat(rendered.get(rendered.size() - 1))
                    .as("and closes with a trailer")
                    .isEqualTo(closingBanner);

            int statementIndex = -1;
            for (int position = 0; position < rendered.size(); position++) {
                if (rendered.get(position).equals(openingBanner)) {
                    statementIndex++;
                    continue;
                }
                String leading = bandItem(sink.plainRecords.get(position),
                        StatementBandLayouts.ST_LINE14, "ST-TRANID");
                if (leading.chars().allMatch(Character::isDigit)) {
                    assertThat(cards.get(statementIndex).rows())
                            .as("detail line %s falls inside the statement of the card it belongs to",
                                    leading)
                            .anySatisfy(row -> assertThat(row.key().transactionId())
                                    .isEqualTo(leading));
                }
            }
        }

        /**
         * Asserts each card's trailer total covers that card's own transactions alone.
         *
         * <p>Purpose: {@code MOVE ZERO TO WS-TOTAL-AMT} at L325 of {@code app/cbl/CBSTM03A.CBL} stands
         * immediately before the traversal performed at L326, which accumulates with
         * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at L429 and moves the result into the trailer band at
         * L433 and L434. Without that reset every card after the first would carry the previous cards'
         * activity as well as its own.</p>
         *
         * <p>Assumptions: four cards whose fixture totals are all different, so a total that survived
         * into the next statement is visible on every card after the first. A single-card fixture cannot
         * observe the reset at all, and two cards sharing a total would let a leak pass on one of
         * them.</p>
         *
         * <p>Assumptions: the expected total is accumulated through {@link Money} in fixture order, which
         * is the order the run adds them in, and is rendered through the same named edit mask the trailer
         * band uses. Comparing rendered items rather than numbers is what makes the case sensitive to the
         * mask as well as to the arithmetic.</p>
         *
         * <p>WHY: the provenance is L325 of {@code app/cbl/CBSTM03A.CBL} for the reset, L429 for the
         * accumulation and L436 for the trailer write that carries the result.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("each card's trailer total covers that card's own transactions alone")
        void eachCardsTrailerTotalCoversThatCardsTransactionsAlone() {
            List<FixtureCard> cards = fixtureCards(DISTINCT_TOTAL_CARDS.toArray(new String[0]));
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);

            List<String> expectedTotals = new ArrayList<>();
            for (FixtureCard card : cards) {
                Money total = Money.ZERO;
                for (StatementTransactionView row : card.rows()) {
                    total = total.plus(row.amount());
                }
                expectedTotals.add(CobolEditMask.formatStatementAmount(total));
            }

            List<String> renderedTotals = new ArrayList<>();
            for (byte[] record : sink.plainRecords) {
                if (bandItem(record, StatementBandLayouts.ST_LINE14A, "FILLER-1")
                        .equals(StatementBandLayouts.TOTAL_EXPENDITURE_LABEL)) {
                    renderedTotals.add(
                            bandItem(record, StatementBandLayouts.ST_LINE14A, "ST-TOTAL-TRAMT"));
                }
            }

            assertThat(expectedTotals)
                    .as("the four fixture cards carry four DIFFERENT totals, so a total that survived "
                            + "one statement into the next is observable on three of them")
                    .doesNotHaveDuplicates();
            assertThat(renderedTotals)
                    .as("each card's trailer carries its own total and not a running sum")
                    .containsExactlyElementsOf(expectedTotals);
        }
    }

    /**
     * The shape of what a run emits: the two declared record widths and the repetitions inside them.
     *
     * <p>Purpose: the two artifacts are fixed-width record streams, and every case here is about a
     * property of the bytes rather than of the values in them. Both widths are asserted on every record
     * of a run, the shipped oracles' stored width classes are reproduced through the same right-trim the
     * oracles encode, and the bands the reference writes more than once are asserted to arrive more than
     * once.</p>
     */
    @Nested
    @DisplayName("the emitted record shape")
    class EmittedRecordShape {

        /**
         * Asserts every plain-text record is exactly 80 bytes and every markup record exactly 100.
         *
         * <p>Purpose: the plain-text width is declared {@code 01 FD-STMTFILE-REC PIC X(80)} at L45 of
         * {@code app/cbl/CBSTM03A.CBL} and confirmed by {@code LRECL=80} at L73 and L89 of
         * {@code app/jcl/CREASTMT.JCL}; the markup width is declared
         * {@code 01 FD-HTMLFILE-REC PIC X(100)} at L47, restated as {@code 05 HTML-FIXED-LN PIC X(100)}
         * at L149 and confirmed by {@code LRECL=100} at L94. Every record of a whole run is measured
         * rather than one of each kind, because a band that reached the wrong width would otherwise have
         * to be the one sampled.</p>
         *
         * <p>Assumptions: the {@code LRECL=80} at L69 of {@code app/jcl/CREASTMT.JCL} is INERT and is not
         * a second markup width. It sits on the markup stanza of the deletion step, whose program is
         * {@code IEFBR14} at L66 -- a program that opens nothing and writes no data -- so the attribute
         * describes a data set that step never writes. Reading it as an alternative markup width would
         * contradict the four places that agree on 100.</p>
         *
         * <p>WHY: the provenance is L45 and L47 of {@code app/cbl/CBSTM03A.CBL} for the two file
         * definitions, with L73, L89 and L94 of {@code app/jcl/CREASTMT.JCL} confirming both.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("every statement record is 80 bytes and every markup record is 100")
        void everyStatementRecordIsEightyBytesAndEveryMarkupRecordIsOneHundred() {
            List<FixtureCard> cards = fixtureCards(DISTINCT_TOTAL_CARDS.toArray(new String[0]));
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);

            assertThat(sink.plainRecords).isNotEmpty();
            assertThat(sink.markupRecords).isNotEmpty();
            for (byte[] record : sink.plainRecords) {
                assertThat(record.length)
                        .as("statement record %s is the declared width",
                                rightTrimmed(record))
                        .isEqualTo(StatementBandLayouts.STATEMENT_LINE_LENGTH);
            }
            for (byte[] record : sink.markupRecords) {
                assertThat(record.length)
                        .as("markup record %s is the declared width", rightTrimmed(record))
                        .isEqualTo(StatementHtmlMapper.HTML_RECORD_LENGTH);
            }
        }

        /**
         * Asserts the right-trim normalisation reproduces the width classes the shipped oracle stores.
         *
         * <p>Purpose: {@code tests/golden/statement/happy_path/statement.txt.expected} stores 22 lines
         * measuring 9, 14, 16, 16, 23, 31, 32, 46, 49, three of 79 and ten of 80 -- measured on the
         * shipped file, not estimated -- because line-sequential output drops trailing blanks as it
         * writes. This case emits a statement and asserts that the same trim puts each structural band
         * into the class the oracle holds it in, which is what makes a comparison against that artifact
         * meaningful.</p>
         *
         * <p>Assumptions: an assertion that a stored golden LINE is 80 bytes long could never pass, so
         * none is written. The trim runs on the EMITTED record and the padding requirement itself is
         * asserted separately by the case above, on every record of a run. The classes are content rather
         * than a second width contract: the balance band trims to 32 for a non-negative balance because
         * the trailing sign position is blank, and to 33 for a negative one because the position then
         * carries a minus, so this case drives a card whose balance is positive and whose rows include
         * one negative amount in order to exercise both forms.</p>
         *
         * <p>Assumptions: all seventeen {@code ST-LINE} bands are natively exactly 80, so the plain-text
         * statement needs no padding at all to reach its declared width -- the exact inverse of the
         * 133-column report, six of whose seven bands are natively short. The classes below are therefore
         * the columns each band's own content ends at.</p>
         *
         * <p>WHY: the provenance is the band block at L86 to L146 of {@code app/cbl/CBSTM03A.CBL}, whose
         * items fix where each band's content ends, and the shipped oracle that stores the trimmed
         * result.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the right-trim normalisation reproduces the oracle's stored width classes")
        void theRightTrimReproducesTheOraclesStoredWidthClasses() {
            List<FixtureCard> cards = fixtureCards(WIDTH_CLASS_CARD);
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);
            List<byte[]> records = sink.plainRecords;

            assertThat(rightTrimmed(records.get(0)))
                    .as("the opening banner survives the trim whole, the oracle's 80-byte class")
                    .hasSize(StatementBandLayouts.STATEMENT_LINE_LENGTH);
            assertThat(rightTrimmed(records.get(records.size() - 1)))
                    .as("and so does the closing banner")
                    .hasSize(StatementBandLayouts.STATEMENT_LINE_LENGTH);
            assertThat(rightTrimmed(firstRecordWithItem(records, StatementBandLayouts.ST_LINE12,
                    "FILLER-1", StatementBandLayouts.HYPHEN_RULE)))
                    .as("an all-hyphen rule survives whole, because its last byte is a hyphen")
                    .hasSize(StatementBandLayouts.HYPHEN_RULE_LENGTH);
            assertThat(rightTrimmed(firstRecordWithItem(records, StatementBandLayouts.ST_LINE13,
                    "FILLER-1", StatementBandLayouts.TRAN_ID_HEADING)))
                    .as("the column headings end at the last letter of the amount heading, at column 80")
                    .hasSize(StatementBandLayouts.STATEMENT_LINE_LENGTH);
            assertThat(rightTrimmed(firstRecordWithItem(records, StatementBandLayouts.ST_LINE7,
                    "FILLER-1", StatementBandLayouts.ACCOUNT_ID_LABEL)))
                    .as("the account band ends at the last of the eleven identifier digits, "
                            + "the oracle's 31-byte class")
                    .hasSize(StatementBandLayouts.ACCOUNT_ID_LABEL.length()
                            + StatementTextMapper.ACCOUNT_ID_DIGITS);
            assertThat(rightTrimmed(firstRecordWithItem(records, StatementBandLayouts.ST_LINE8,
                    "FILLER-1", StatementBandLayouts.CURRENT_BALANCE_LABEL)))
                    .as("a non-negative balance leaves its trailing sign position blank, so the band "
                            + "trims to the oracle's 32-byte class")
                    .hasSize(StatementBandLayouts.CURRENT_BALANCE_LABEL.length()
                            + CobolEditMask.STATEMENT_AMOUNT_WIDTH - 1);
            assertThat(rightTrimmed(firstRecordWithItem(records, StatementBandLayouts.ST_LINE9,
                    "FILLER-1", StatementBandLayouts.FICO_SCORE_LABEL)))
                    .as("the score band ends at the last of three digits, the oracle's 23-byte class")
                    .hasSize(StatementBandLayouts.FICO_SCORE_LABEL.length()
                            + StatementTextMapper.CREDIT_SCORE_DIGITS);
            assertThat(rightTrimmed(firstRecordWithItem(records, StatementBandLayouts.ST_LINE11,
                    "FILLER-2", StatementBandLayouts.TRANSACTION_SUMMARY_HEADING)))
                    .as("the summary heading ends at its last letter, the oracle's 49-byte class")
                    .hasSize(49);
            assertThat(rightTrimmed(records.get(6)))
                    .as("the basic-details heading ends at its last letter, the oracle's 46-byte class")
                    .hasSize(46);

            List<byte[]> detailLines = new ArrayList<>();
            for (byte[] record : records) {
                if (bandItem(record, StatementBandLayouts.ST_LINE14, "ST-TRANID")
                        .chars().allMatch(Character::isDigit)) {
                    detailLines.add(record);
                }
            }
            byte[] positiveLine = null;
            byte[] negativeLine = null;
            for (byte[] record : detailLines) {
                String amount = bandItem(record, StatementBandLayouts.ST_LINE14, "ST-TRANAMT");
                if (amount.endsWith("-")) {
                    negativeLine = record;
                } else {
                    positiveLine = record;
                }
            }

            assertThat(positiveLine).as("the fixture card carries a positive amount").isNotNull();
            assertThat(negativeLine).as("and exactly one negative amount").isNotNull();
            assertThat(rightTrimmed(positiveLine))
                    .as("a positive amount leaves the trailing sign blank, so the line trims to the "
                            + "oracle's 79-byte class")
                    .hasSize(StatementBandLayouts.STATEMENT_LINE_LENGTH - 1);
            assertThat(rightTrimmed(negativeLine))
                    .as("a negative amount ends in a minus, so the line stays in the 80-byte class -- "
                            + "which is why the oracle holds three lines of 79 and one detail line of 80")
                    .hasSize(StatementBandLayouts.STATEMENT_LINE_LENGTH);
            assertThat(rightTrimmed(firstRecordWithItem(records, StatementBandLayouts.ST_LINE14A,
                    "FILLER-1", StatementBandLayouts.TOTAL_EXPENDITURE_LABEL)))
                    .as("and this card's total is positive, so its trailer trims to 79 as the oracle's "
                            + "does")
                    .hasSize(StatementBandLayouts.STATEMENT_LINE_LENGTH - 1);
        }

        /**
         * Asserts the six hyphen rules stand at their declared positions and that none is collapsed.
         *
         * <p>Purpose: the reference writes three distinct rule bands and writes two of them TWICE.
         * {@code ST-LINE5} is written at L492 and again at L494 of {@code app/cbl/CBSTM03A.CBL}, once on
         * each side of the basic-details heading; {@code ST-LINE12} is written at L500 and again at L502,
         * once on each side of the column headings, and a third time in the card trailer at L435; and
         * {@code ST-LINE10} is written once at L498. All three bands render as 80 hyphens, so a run that
         * emitted each of them once would still produce a plausible statement -- two lines shorter, with
         * every remaining line byte-correct.</p>
         *
         * <p>Assumptions: the repetitions are asserted by POSITION rather than by counting alone, because
         * the bands are indistinguishable in the emitted bytes. Positions 5 and 7 bracket the
         * basic-details heading at 6 and are therefore the pair from L492 and L494; positions 13 and 15
         * bracket the column headings at 14 and are the pair from L500 and L502; position 11 is the
         * single rule from L498; and the last rule stands immediately before the trailer's total, which
         * is the write at L435. Counting six without their positions would pass a run that emitted one
         * pair twice and the other pair not at all.</p>
         *
         * <p>WHY: the provenance is the fifteen consecutive writes at L488 to L502 of
         * {@code app/cbl/CBSTM03A.CBL} and the three trailer writes at L435 to L437.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the six hyphen rules stand at their declared positions and none is collapsed")
        void theSixHyphenRulesStandAtTheirDeclaredPositionsAndNoneIsCollapsed() {
            List<FixtureCard> cards = fixtureCards(WIDTH_CLASS_CARD);
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);
            int transactionCount = cards.get(0).rows().size();

            List<Integer> rulePositions = new ArrayList<>();
            for (int position = 0; position < sink.plainRecords.size(); position++) {
                if (rightTrimmed(sink.plainRecords.get(position))
                        .equals(StatementBandLayouts.HYPHEN_RULE)) {
                    rulePositions.add(position);
                }
            }

            assertThat(rulePositions)
                    .as("two rules bracket the basic-details heading, two bracket the column headings, "
                            + "one stands between the two heading groups and one opens the trailer")
                    .containsExactly(5, 7, 11, 13, 15,
                            StatementTextMapper.HEADER_BLOCK_LINE_COUNT + transactionCount);
            assertThat(rulePositions)
                    .as("which is the per-statement count the assembler publishes")
                    .hasSize(StatementTextMapper.HYPHEN_RULES_PER_STATEMENT);
            assertThat(bandItem(sink.plainRecords.get(6), StatementBandLayouts.ST_LINE6, "FILLER-2")
                    .stripTrailing())
                    .as("the first pair brackets the basic-details heading")
                    .isEqualTo(StatementBandLayouts.BASIC_DETAILS_HEADING);
            assertThat(bandItem(sink.plainRecords.get(14), StatementBandLayouts.ST_LINE13, "FILLER-1"))
                    .as("and the second pair brackets the column headings")
                    .isEqualTo(StatementBandLayouts.TRAN_ID_HEADING);
            assertThat(bandItem(sink.plainRecords.get(
                    StatementTextMapper.HEADER_BLOCK_LINE_COUNT + transactionCount + 1),
                    StatementBandLayouts.ST_LINE14A, "FILLER-1"))
                    .as("and the sixth stands immediately before the trailer's total")
                    .isEqualTo(StatementBandLayouts.TOTAL_EXPENDITURE_LABEL);
        }

        /**
         * Asserts the markup name cell assembled into the record area survives as one record per card.
         *
         * <p>Purpose: L568 of {@code app/cbl/CBSTM03A.CBL} is the program's only
         * {@code WRITE FD-HTMLFILE-REC.} with no {@code FROM} clause: the name cell is assembled directly
         * into the output record area by the preceding {@code STRING} and written bare, where the three
         * address cells that follow are assembled into a separate buffer and written from it. A cell with
         * no source item is the one a rebuild is most likely to lose, because there is no buffer to
         * notice the absence of.</p>
         *
         * <p>Assumptions: the cell is identified by the emphasised cell prefix TOGETHER WITH the
         * customer's own surname, because that prefix is shared by several fixed fragments of the
         * document -- the bank name, the basic-details heading, the summary heading and the three column
         * headings all carry it. Requiring the surname is what distinguishes the one cell whose content
         * comes from the customer row.</p>
         *
         * <p>WHY: the provenance is L566 and L568 of {@code app/cbl/CBSTM03A.CBL} for the bare write, and
         * L574 to L592 for the three buffered address cells it is deliberately unlike.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the markup name cell written from the record area survives as one record per card")
        void theMarkupNameCellWrittenFromTheRecordAreaSurvivesAsOneRecordPerCard() {
            List<FixtureCard> cards = fixtureCards(WIDTH_CLASS_CARD);
            stubFixtureWalk(cards);
            String surname = cards.get(0).headingRow().getLastName();

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);

            List<String> nameCells = new ArrayList<>();
            for (byte[] record : sink.markupRecords) {
                String rendered = rightTrimmed(record);
                if (rendered.startsWith(StatementHtmlMapper.NAME_CELL_PREFIX)
                        && rendered.contains(surname)) {
                    nameCells.add(rendered);
                }
            }

            assertThat(surname).as("the fixture customer has a surname to look for").isNotEmpty();
            assertThat(nameCells)
                    .as("exactly one markup record carries the customer's name in the emphasised cell")
                    .hasSize(1);
            assertThat(nameCells.get(0))
                    .as("and that cell is closed, so the bare write carries a whole element")
                    .endsWith(StatementHtmlMapper.CELL_SUFFIX);
            assertThat(sink.markupRecords)
                    .as("with the whole markup document at its per-statement budget, so no other record "
                            + "of the name and address block is lost either")
                    .hasSize(markupRecordsPerStatement(cards.get(0).rows().size()));
        }
    }

    /**
     * The order rows are grouped and read in, and the continuation that walks past one chunk.
     *
     * <p>Purpose: {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)} at L53 of {@code app/jcl/CREASTMT.JCL} is a
     * TWO-key sort: the card number groups the rows and the transaction identifier orders them inside
     * each group. Both halves are asserted here, and so is the continuation that carries a card whose
     * rows exceed one chunk.</p>
     */
    @Nested
    @DisplayName("grouping, ordering and chunk continuation")
    class OrderingAndContinuation {

        /**
         * Asserts the emitted statements group by card and order by identifier inside each card.
         *
         * <p>Purpose: the two sort keys at L53 of {@code app/jcl/CREASTMT.JCL} are the card number at
         * position 263 and the transaction identifier at position 1, in that precedence, and the migrated
         * run reproduces both -- one statement per cross-reference row, and each card's rows read in
         * ascending identifier order. Comparing each statement's identifiers against that card's own
         * fixture rows asserts the grouping and the ordering together, so a row rendered inside the wrong
         * card's statement fails as surely as a row out of order.</p>
         *
         * <p>Assumptions: the card ORDER of the statements themselves follows the pair the heading query
         * declares, which leads on the masked rendering the relation publishes rather than on the whole
         * card number. That is a consequence of publishing a masked column and it changes which statement
         * comes first; it does not change which transactions belong to a statement or their order inside
         * it, which is what L53 fixes.</p>
         *
         * <p>WHY: the provenance is L53 of {@code app/jcl/CREASTMT.JCL} for the two-key sort and L416 to
         * L432 of {@code app/cbl/CBSTM03A.CBL}, whose traversal renders one card's rows in the order the
         * sorted input holds them.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("statements group by card and each card's lines ascend by transaction identifier")
        void statementsGroupByCardAndEachCardsLinesAscendByIdentifier() {
            List<FixtureCard> cards = fixtureCards(DISTINCT_TOTAL_CARDS.toArray(new String[0]));
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);

            List<List<String>> emitted = identifiersPerStatement(sink.plainRecords);
            List<List<String>> expected = cards.stream()
                    .map(StatementServiceTest::identifiersOf)
                    .map(identifiers -> (List<String>) new ArrayList<>(identifiers))
                    .toList();

            assertThat(emitted)
                    .as("each statement carries exactly its own card's rows, in ascending identifier "
                            + "order")
                    .containsExactlyElementsOf(expected);
            for (List<String> statement : emitted) {
                assertThat(statement)
                        .as("and no statement's identifiers are out of order or repeated")
                        .isSorted()
                        .doesNotHaveDuplicates();
            }
        }

        /**
         * Asserts a card whose rows exceed one chunk continues from the previous chunk's last identifier.
         *
         * <p>Purpose: the run reads a card's rows in bounded chunks and advances a strict continuation on
         * the identifier the query orders by, so a card of 600 rows is read in three calls: the first
         * from the start, the second from the 500th identifier, and a third that returns nothing and ends
         * the card. A run that restarted each chunk from the start would loop on a real card, and one
         * that advanced by the wrong value would skip or repeat rows at the boundary -- neither of which a
         * count of rendered lines alone can distinguish from a correct read.</p>
         *
         * <p>Assumptions: the two boundary identifiers are taken from the fixture rather than written as
         * literals, so the case states the continuation rule instead of restating the fixture's
         * contents.</p>
         *
         * <p>WHY: the provenance is the inner traversal at L416 to L432 of
         * {@code app/cbl/CBSTM03A.CBL}, which the chunked read replaces, and L53 of
         * {@code app/jcl/CREASTMT.JCL}, whose second sort key is the identifier the continuation
         * advances on.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a card larger than one chunk continues from the previous chunk's last identifier")
        void aCardLargerThanOneChunkContinuesFromThePreviousChunksLastIdentifier() {
            List<FixtureCard> cards = fixtureCards(INNER_OVERFLOW_CARD);
            stubFixtureWalk(cards);
            List<String> identifiers = identifiersOf(cards.get(0));
            String lastOfFirstChunk = identifiers.get(StatementService.TRANSACTION_CHUNK_SIZE - 1);
            String lastOfCard = identifiers.get(identifiers.size() - 1);

            service.generateStatements(new RecordingSink());

            ArgumentCaptor<String> continuation = ArgumentCaptor.forClass(String.class);
            verify(transactions, Mockito.times(3)).findWindowByCardFingerprint(
                    eq(cards.get(0).fingerprint()), continuation.capture(),
                    eq(StatementService.TRANSACTION_CHUNK_SIZE));
            assertThat(continuation.getAllValues())
                    .as("the first read starts from the beginning, the second from the 500th identifier "
                            + "and the third from the card's last, which ends the card")
                    .containsExactly("", lastOfFirstChunk, lastOfCard);
        }
    }

    /**
     * What ends a run normally, and what stops it.
     *
     * <p>Purpose: three of the run's four reads behave differently when a row is absent, and the
     * difference is the whole of the missing-row policy. The cross-reference cursor's exhaustion is the
     * run's normal ending; an unresolved customer or account is a referential-integrity violation that
     * stops it. These cases assert both arms so that neither can be quietly turned into the other.</p>
     */
    @Nested
    @DisplayName("normal ending and the two aborting reads")
    class MissingRowPolicy {

        /**
         * Asserts an unresolved customer stops the request rather than producing a partial statement.
         *
         * <p>Purpose: {@code 2000-CUSTFILE-GET} at L368 to L390 of {@code app/cbl/CBSTM03A.CBL} carries
         * an {@code EVALUATE} at L379 to L386 with a success arm and a catch-all and NO not-found arm at
         * all, so any other status displays {@code ERROR READING CUSTFILE} at L383 and performs the abend
         * paragraph at L385. The migrated read raises instead, and the failure carries the four elements
         * of the abend structure declared at L21 to L29 of {@code app/cpy/CSMSG02Y.cpy}.</p>
         *
         * <p>Assumptions: the captured failure is a nested {@link IllegalStateException} and NOT a client
         * input refusal, and the distinction is the point of asserting the type. A refusal would tell the
         * caller to correct the request, when what has actually happened is that a cross-reference row
         * names a customer the customer relation does not hold -- a state no caller can correct and one
         * that a partial document would conceal.</p>
         *
         * <p>Assumptions: the account read is asserted NOT to have happened, which is what places the
         * failure at the customer read rather than merely somewhere in the path. The order is the
         * reference's own, at L321 and L322.</p>
         *
         * <p>WHY: the provenance is L379 to L386 of {@code app/cbl/CBSTM03A.CBL} for the absent
         * not-found arm, L383 for the display text carried into the message, and L921 to L923 for the
         * abend paragraph, whose own display is the message element.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a cross-reference row naming an unresolved customer stops the request")
        void aCrossReferenceRowNamingAnUnresolvedCustomerStopsTheRequest() {
            stubOneCard();
            when(customers.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.compose(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR))
                    .withMessageStartingWith("ERROR READING CUSTFILE")
                    .withMessageContainingAll("abendCode=", "abendReason=")
                    .withMessageContaining("abendCulprit=CBSTM03A")
                    .withMessageContaining("abendMsg=ABENDING PROGRAM");

            assertThat("CBSTM03A".length())
                    .as("the culprit is the program being transcribed, at the width ABEND-CULPRIT "
                            + "declares at L24 of app/cpy/CSMSG02Y.cpy")
                    .isEqualTo(AbendDetail.ABEND_CULPRIT_LENGTH);
            verify(accounts, never()).findById(anyLong());
        }

        /**
         * Asserts an unresolved account stops the request rather than producing a partial statement.
         *
         * <p>Purpose: {@code 3000-ACCTFILE-GET} at L392 to L414 of {@code app/cbl/CBSTM03A.CBL} is shaped
         * identically to the customer read and its {@code EVALUATE} at L403 to L410 likewise has no
         * not-found arm, displaying {@code ERROR READING ACCTFILE} at L407 before the same abend
         * paragraph. The two are asserted separately because they name different relations in their
         * message, and a single case covering both would pass against an implementation that reported the
         * wrong one -- which is the one thing the message is for.</p>
         *
         * <p>Assumptions: the captured failure is a nested {@link IllegalStateException}, for the reason
         * the customer case records, and the customer read is asserted to have happened first so that the
         * failure is located at the account read.</p>
         *
         * <p>WHY: the provenance is L403 to L410 of {@code app/cbl/CBSTM03A.CBL} for the absent
         * not-found arm, L407 for the display text, and L921 to L923 for the abend paragraph.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a cross-reference row naming an unresolved account stops the request")
        void aCrossReferenceRowNamingAnUnresolvedAccountStopsTheRequest() {
            stubOneCard();
            when(accounts.findById(ACCOUNT_ID)).thenReturn(Optional.empty());

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> service.compose(new StatementRequest(SEED_CARD_NUMBER, null), OPERATOR))
                    .withMessageStartingWith("ERROR READING ACCTFILE")
                    .withMessageContainingAll("abendCode=", "abendReason=")
                    .withMessageContaining("abendCulprit=CBSTM03A")
                    .withMessageContaining("abendMsg=ABENDING PROGRAM");

            verify(customers).findById(CUSTOMER_ID);
        }

        /**
         * Asserts an exhausted cross-reference cursor ends the run with every statement left intact.
         *
         * <p>Purpose: the cross-reference read is the only statement read paragraph carrying an
         * end-of-file arm -- L353 to L362 of {@code app/cbl/CBSTM03A.CBL}, whose {@code WHEN '10'} at
         * L357 moves the end-of-file flag that the mainline's {@code PERFORM UNTIL} at L317 tests -- so
         * running out of rows is how a successful run ENDS. This case drives four cards, lets the walk
         * read past the last of them, and asserts the run returned normally with all four statements
         * whole. It is the deliberate contrast with the two cases above: the same absence of a row is a
         * normal ending on one read and a stop on the other two.</p>
         *
         * <p>Assumptions: intactness is asserted as the per-card record budget and one closing banner per
         * card, not merely as a count of statements. A run that ended after writing a heading and no
         * trailer would still report the statement it had started.</p>
         *
         * <p>WHY: the provenance is L353 to L362 of {@code app/cbl/CBSTM03A.CBL} for the end-of-file arm
         * and L317 for the loop it ends.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("an exhausted cross-reference cursor ends the run with every statement intact")
        void anExhaustedCrossReferenceCursorEndsTheRunWithEveryStatementIntact() {
            List<FixtureCard> cards = fixtureCards(DISTINCT_TOTAL_CARDS.toArray(new String[0]));
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            StatementRunOutcome outcome = service.generateStatements(sink);

            int expectedRecords = 0;
            for (FixtureCard card : cards) {
                expectedRecords += plainRecordsPerStatement(card.rows().size());
            }
            String closingBanner = StatementBandLayouts.CLOSING_ASTERISK_RUN
                    + StatementBandLayouts.END_OF_STATEMENT_SENTINEL
                    + StatementBandLayouts.CLOSING_ASTERISK_RUN;
            long trailers = sink.plainRecords.stream()
                    .filter(record -> new String(record, StandardCharsets.US_ASCII)
                            .equals(closingBanner))
                    .count();

            assertThat(outcome.statementsProduced())
                    .as("the walk ended by running out of rows, with every card statemented")
                    .isEqualTo(cards.size());
            assertThat(sink.plainRecords)
                    .as("and each of those statements is whole, not merely started")
                    .hasSize(expectedRecords);
            assertThat(trailers)
                    .as("with one closing banner per card")
                    .isEqualTo(cards.size());
            verify(cardXrefs, Mockito.times(2))
                    .findHeadingChunk(anyString(), anyString(), anyInt());
        }
    }

    /**
     * Rerunning a night, and the absence of anything that would make two runs differ.
     *
     * <p>Purpose: a statement run is compared against a stored oracle, so two runs over one input have to
     * produce one byte stream, and a second run has to leave one copy of each artifact rather than two.
     * Both are properties nothing else in this file would reveal.</p>
     */
    @Nested
    @DisplayName("rerunning a night")
    class RerunAndDeterminism {

        /**
         * Asserts a second run replaces both artifacts rather than appending to them.
         *
         * <p>Purpose: {@code app/jcl/CREASTMT.JCL} deletes both outputs in a step of its own --
         * {@code IEFBR14} at L66, gated {@code COND=(0,NE)}, with {@code DISP=(MOD,DELETE,DELETE)} on the
         * markup output at L67 and on the plain-text output at L72 -- and only then does L79 onward run
         * the generator with {@code DISP=(NEW,CATLG,DELETE)} on both. A rerun therefore leaves one copy of
         * each artifact. This case runs the writer twice into one destination that discards what it holds
         * when it is cleared, and asserts the destination ends with exactly one run's records.</p>
         *
         * <p>Assumptions: the destination is asked to clear itself rather than to count clearings,
         * because a destination that only counted would report the call and still hold both copies -- and
         * holding both copies is precisely the state this case exists to rule out. The first run's stream
         * is captured separately so the comparison is against a whole run's records rather than against a
         * number.</p>
         *
         * <p>WHY: the provenance is L66 to L75 of {@code app/jcl/CREASTMT.JCL} for the deletion step and
         * L79 onward for the creation that follows it.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("a second run replaces both artifacts rather than appending to them")
        void aSecondRunReplacesBothArtifactsRatherThanAppendingToThem() {
            List<FixtureCard> cards = fixtureCards(DISTINCT_TOTAL_CARDS.toArray(new String[0]));
            stubFixtureWalk(cards);

            RecordingSink singleRun = new RecordingSink();
            service.generateStatements(singleRun);

            ReplacingSink artifacts = new ReplacingSink();
            service.generateStatements(artifacts);
            service.generateStatements(artifacts);

            assertThat(artifacts.replacements)
                    .as("each run cleared the previous artifacts before writing its first record")
                    .isEqualTo(2);
            assertThat(artifacts.plainRecords)
                    .as("so the plain-text artifact holds one run's records and not two")
                    .hasSameSizeAs(singleRun.plainRecords);
            assertThat(artifacts.markupRecords)
                    .as("and so does the markup artifact")
                    .hasSameSizeAs(singleRun.markupRecords);
            for (int position = 0; position < singleRun.plainRecords.size(); position++) {
                assertThat(artifacts.plainRecords.get(position))
                        .as("record %d of the reran artifact is the record a single run wrote", position)
                        .isEqualTo(singleRun.plainRecords.get(position));
            }
        }

        /**
         * Asserts two runs over identical input emit byte-identical streams.
         *
         * <p>Purpose: this is the property a comparison against a stored oracle rests on. Anything
         * carried between two runs -- a field holding an accumulator, a counter surviving a run, a value
         * read from the environment -- would show as a difference in the second stream. The reference's
         * own accumulator is process-wide working storage, which is exactly the arrangement that needs
         * the reset at L325 of {@code app/cbl/CBSTM03A.CBL} and which would fail here.</p>
         *
         * <p>Assumptions: this is also how the clock-free property is asserted for the run itself, and it
         * is asserted this way rather than with a time source that throws when consulted because there is
         * no clock seam to inject one through. The companion case below asserts that absence
         * structurally.</p>
         *
         * <p>WHY: the provenance is L325 of {@code app/cbl/CBSTM03A.CBL}, whose reset exists because the
         * reference's accumulator outlives a statement.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("two runs over identical input emit byte-identical streams")
        void twoRunsOverIdenticalInputEmitByteIdenticalStreams() {
            List<FixtureCard> cards = fixtureCards(DISTINCT_TOTAL_CARDS.toArray(new String[0]));
            stubFixtureWalk(cards);

            RecordingSink first = new RecordingSink();
            RecordingSink second = new RecordingSink();
            service.generateStatements(first);
            service.generateStatements(second);

            assertThat(second.plainRecords).hasSameSizeAs(first.plainRecords);
            assertThat(second.markupRecords).hasSameSizeAs(first.markupRecords);
            for (int position = 0; position < first.plainRecords.size(); position++) {
                assertThat(second.plainRecords.get(position))
                        .as("no value survives one run into the next, at plain-text record %d", position)
                        .isEqualTo(first.plainRecords.get(position));
            }
            for (int position = 0; position < first.markupRecords.size(); position++) {
                assertThat(second.markupRecords.get(position))
                        .as("nor at markup record %d", position)
                        .isEqualTo(first.markupRecords.get(position));
            }
        }

        /**
         * Asserts the service declares no time source on any field, constructor or method.
         *
         * <p>Purpose: a statement is compared against a stored oracle, so a generator that read the wall
         * clock would produce a different artifact every day from identical input and the comparison it
         * exists to satisfy could never pass twice. Every date this service renders comes from a row it
         * read.</p>
         *
         * <p>Alternatives Considered: injecting a {@link Clock} whose accessors throw, so that any
         * consultation would fail a run. Rejected because this service has no clock seam to inject one
         * through -- it takes four read-only repositories, a key prefix, an artifact store and a
         * tokeniser -- and adding a constructor parameter merely to prove it is unused would introduce the
         * very dependency the case exists to deny. Asserting the absence directly is the stronger
         * statement: a throwing clock proves only that the exercised path does not consult it, where this
         * proves no path can, because there is nothing to consult.</p>
         *
         * <p>WHY: the provenance is the injected business date of the batch window rather than a line of
         * {@code app/cbl/CBSTM03A.CBL}, which reads no clock either: its only dated values arrive in the
         * records it reads, and {@code app/jcl/CREASTMT.JCL} supplies no date parameter at all.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the service declares no clock, on any field, constructor or method")
        void theServiceDeclaresNoClock() {
            for (java.lang.reflect.Field field : StatementService.class.getDeclaredFields()) {
                assertThat(field.getType())
                        .as("field %s is not a time source", field.getName())
                        .isNotEqualTo(Clock.class);
            }
            for (java.lang.reflect.Constructor<?> constructor
                    : StatementService.class.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes())
                        .as("no constructor takes a time source")
                        .doesNotContain(Clock.class);
            }
            for (java.lang.reflect.Method method : StatementService.class.getDeclaredMethods()) {
                assertThat(method.getParameterTypes())
                        .as("method %s takes no time source", method.getName())
                        .doesNotContain(Clock.class);
            }
        }
    }

    /**
     * What the two artifacts must not carry, and the two money masks that are not interchangeable.
     *
     * <p>Purpose: the statement's transaction layout carries a whole card number and its money items use
     * a sign convention opposite to the report's, so both are places where a plausible artifact can be
     * wrong. These cases assert what the emitted records do not contain and which named mask each money
     * position is rendered through.</p>
     */
    @Nested
    @DisplayName("disclosure and the two statement money masks")
    class DisclosureAndMoney {

        /**
         * Asserts no emitted record carries a whole card number, and that no projection carries a
         * verification value.
         *
         * <p>Purpose: {@code TRNX-CARD-NUM PIC X(16)} at L22 of {@code app/cpy/COSTM01.CPY} carries the
         * WHOLE card number, so a statement assembled straight from that layout would print it. The
         * migrated bands render no card number at all, which makes this a regression guard rather than a
         * masking assertion: a band added later that rendered the card would fail here.</p>
         *
         * <p>Assumptions: the run's own anchor is asserted to be a masked rendering, because the walk
         * carries a card value in its continuation and a walk anchored on the whole number would put one
         * in every query the run issues even if no artifact showed it.</p>
         *
         * <p>Assumptions: the verification value is asserted STRUCTURALLY, on the four projections this
         * service reads, rather than by searching the artifacts for a three-digit value. A three-digit
         * string occurs inside almost every rendered amount, so a search would report a match that means
         * nothing; a projection declaring no such member cannot supply one to any band.</p>
         *
         * <p>Assumptions: the response-level masking of a card number is NOT asserted here. That belongs
         * to {@code ReportingDtoMapperTest} in the sibling mapper test package, and duplicating it would
         * leave two owners for one rule.</p>
         *
         * <p>WHY: the provenance is L22 of {@code app/cpy/COSTM01.CPY} for the whole card number in the
         * statement's own layout, and the absence of any card item among the seventeen bands declared at
         * L86 to L146 of {@code app/cbl/CBSTM03A.CBL}.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("no emitted record carries a whole card number and no projection carries a "
                + "verification value")
        void noEmittedRecordCarriesAWholeCardNumberAndNoProjectionCarriesAVerificationValue() {
            List<FixtureCard> cards = fixtureCards();
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);

            StringBuilder emitted = new StringBuilder();
            for (byte[] record : sink.plainRecords) {
                emitted.append(new String(record, StandardCharsets.US_ASCII));
            }
            for (byte[] record : sink.markupRecords) {
                emitted.append(new String(record, StandardCharsets.US_ASCII));
            }
            String[] wholeCardNumbers = cards.stream().map(FixtureCard::cardNumber)
                    .toArray(String[]::new);

            assertThat(emitted.toString())
                    .as("neither artifact carries any of the 88 whole card numbers the run walked")
                    .doesNotContain(wholeCardNumbers);
            for (FixtureCard card : cards) {
                assertThat(card.headingRow().getCardNum())
                        .as("and the walk itself is anchored on a masked rendering")
                        .matches(CardNumberMasker.MASKED_FORM_PATTERN);
            }
            for (Class<?> projection : List.of(CardXrefView.class, CustomerView.class,
                    AccountView.class, StatementTransactionView.class)) {
                for (java.lang.reflect.Field member : projection.getDeclaredFields()) {
                    assertThat(member.getName().toLowerCase(java.util.Locale.ROOT))
                            .as("%s declares no verification-value member", projection.getSimpleName())
                            .doesNotContain("cvv", "verification");
                }
            }
        }

        /**
         * Asserts the balance uses the unsuppressed mask and the two amount items the suppressed one.
         *
         * <p>Purpose: the statement carries two thirteen-character money masks of the same shape that
         * differ in one respect. {@code ST-CURR-BAL PIC 9(9).99-} at L113 of
         * {@code app/cbl/CBSTM03A.CBL} spells its nine integer positions with DIGIT positions, so leading
         * zeros print -- which is why the shipped oracle shows a balance of {@code 000000492.00}.
         * {@code ST-TRANAMT PIC Z(9).99-} at L137 and {@code ST-TOTAL-TRAMT PIC Z(9).99-} at L142 spell
         * the same nine with SUPPRESSION positions, so leading zeros blank. Substituting one for the
         * other yields a value that still fills the item and still reads as the same number, so only a
         * byte comparison shows it.</p>
         *
         * <p>Assumptions: the assertions name the {@link CobolEditMask} method and the PICTURE clause the
         * method transcribes, and never a numbered regime. The numbering differs between documents, so a
         * numeric reference would rot while still reading as precise.</p>
         *
         * <p>Assumptions: BOTH masks trail their sign, which is the opposite of every report regime.
         * {@code CobolEditMask#formatReportDetailAmount} places its sign in a fixed LEADING position, and
         * a zero under it blanks the whole fifteen-character item, where a zero under the statement's
         * suppressed mask keeps the point and the two decimal positions. The two conventions are asserted
         * side by side here so that neither is carried into the other's bands.</p>
         *
         * <p>WHY: the provenance is L113, L137 and L142 of {@code app/cbl/CBSTM03A.CBL} for the three
         * pictures, and L484 for the move that reaches the balance item.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the balance uses the unsuppressed mask and the amount items the suppressed one")
        void theBalanceUsesTheUnsuppressedMaskAndTheAmountItemsTheSuppressedOne() {
            List<FixtureCard> cards = fixtureCards(WIDTH_CLASS_CARD);
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);

            Money balance = cards.get(0).headingRow().getCurrentBalance();
            Money total = Money.ZERO;
            for (StatementTransactionView row : cards.get(0).rows()) {
                total = total.plus(row.amount());
            }

            assertThat(bandItem(firstRecordWithItem(sink.plainRecords, StatementBandLayouts.ST_LINE8,
                    "FILLER-1", StatementBandLayouts.CURRENT_BALANCE_LABEL),
                    StatementBandLayouts.ST_LINE8, "ST-CURR-BAL"))
                    .as("the balance band is rendered through the unsuppressed statement balance mask")
                    .isEqualTo(CobolEditMask.formatStatementBalance(balance));
            assertThat(bandItem(firstRecordWithItem(sink.plainRecords, StatementBandLayouts.ST_LINE14A,
                    "FILLER-1", StatementBandLayouts.TOTAL_EXPENDITURE_LABEL),
                    StatementBandLayouts.ST_LINE14A, "ST-TOTAL-TRAMT"))
                    .as("and the trailer total through the suppressed statement amount mask")
                    .isEqualTo(CobolEditMask.formatStatementAmount(total));

            assertThat(CobolEditMask.formatStatementBalance(Money.of("492.00")))
                    .as("PIC 9(9).99- prints its leading zeros, which is why the oracle holds "
                            + "000000492.00")
                    .isEqualTo("000000492.00 ")
                    .hasSize(CobolEditMask.STATEMENT_AMOUNT_WIDTH);
            assertThat(CobolEditMask.formatStatementAmount(Money.of("183.88")))
                    .as("PIC Z(9).99- blanks them, which is why the oracle holds a leading run of "
                            + "blanks before 183.88")
                    .isEqualTo("      183.88 ")
                    .hasSize(CobolEditMask.STATEMENT_AMOUNT_WIDTH);
            assertThat(CobolEditMask.formatStatementAmount(Money.of("-47.88")))
                    .as("a negative amount trails its sign, as the oracle's one negative line does")
                    .isEqualTo("       47.88-");
            assertThat(CobolEditMask.formatStatementAmount(Money.ZERO))
                    .as("and a zero keeps the point and both decimal positions, so it is nine blanks, "
                            + "a point, two zeros and a blank sign")
                    .isEqualTo("         .00 ");
            assertThat(CobolEditMask.formatStatementAmount(Money.of("492.00")))
                    .as("the two thirteen-character masks are never interchangeable for one value")
                    .isNotEqualTo(CobolEditMask.formatStatementBalance(Money.of("492.00")));
            assertThat(CobolEditMask.formatReportDetailAmount(Money.of("-47.88")))
                    .as("and the report convention leads with its sign where the statement trails it")
                    .startsWith("-")
                    .hasSize(CobolEditMask.REPORT_AMOUNT_WIDTH);
            assertThat(CobolEditMask.formatReportDetailAmount(Money.ZERO))
                    .as("with a zero blanking its whole item, unlike the statement mask above")
                    .isBlank();
        }

        /**
         * Asserts the transaction and balance precisions stay distinct rather than being unified.
         *
         * <p>Purpose: two money precisions coexist in the statement path and unifying them would break
         * one of them. The transaction amount is declared with nine integer positions and two decimals,
         * which the target carries as {@code NUMERIC(11,2)}, and the account balance with TEN integer
         * positions and two decimals, carried as {@code NUMERIC(12,2)}. The statement's own balance item
         * holds only nine, which is why the reference's move at L484 of {@code app/cbl/CBSTM03A.CBL}
         * discards a high-order digit for a balance of a thousand million or more.</p>
         *
         * <p>Assumptions: the target REFUSES such a balance where the reference narrows it silently, and
         * that difference is registered as {@code D-EDIT-MASK-OVERFLOW} in
         * {@code docs/architecture/cobol-to-service-traceability.md}. It is cited rather than restated
         * here, and this case asserts the refusal so the registered divergence is executable: a
         * nine-digit string that had silently dropped its leading digit would understate a balance by at
         * least a thousand million while filling the item and parsing cleanly.</p>
         *
         * <p>Assumptions: the two precisions are read from the shared layout registry rather than written
         * out here, so the case measures the declaration that the schema and the codecs both derive from
         * rather than a copy of it.</p>
         *
         * <p>WHY: the provenance is L484 of {@code app/cbl/CBSTM03A.CBL} for the narrowing move, L113 for
         * the nine-position balance item and the two declared field widths of
         * {@code app/cpy/CVTRA05Y.cpy} and {@code app/cpy/CVACT01Y.cpy} for the two precisions.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("the transaction and balance precisions stay distinct")
        void theTransactionAndBalancePrecisionsStayDistinct() {
            CopybookLayout.FieldSpec transactionAmount =
                    CopybookLayout.layout("TRNX").field("TRNX-AMT");
            CopybookLayout.FieldSpec accountBalance =
                    CopybookLayout.layout("ACCOUNT").field("ACCT-CURR-BAL");

            assertThat(transactionAmount.intDigits() + transactionAmount.decDigits())
                    .as("the transaction amount is NUMERIC(11,2)")
                    .isEqualTo(11);
            assertThat(accountBalance.intDigits() + accountBalance.decDigits())
                    .as("and the account balance is NUMERIC(12,2), which is one integer digit wider")
                    .isEqualTo(12);
            assertThat(accountBalance.intDigits())
                    .as("the difference is on the integer side alone")
                    .isEqualTo(transactionAmount.intDigits() + 1);
            assertThat(accountBalance.decDigits())
                    .as("and both carry exactly two decimal positions")
                    .isEqualTo(transactionAmount.decDigits());

            assertThatCode(() -> CobolEditMask.formatStatementAmount(Money.of("999999999.99")))
                    .as("the statement mask holds the transaction precision exactly")
                    .doesNotThrowAnyException();
            assertThatExceptionOfType(ArithmeticException.class)
                    .as("and refuses the balance precision's tenth integer digit rather than "
                            + "discarding it as the reference's move does")
                    .isThrownBy(() -> CobolEditMask.formatStatementBalance(Money.of("1000000000.00")));
        }

        /**
         * Asserts neither processing nor originating timestamp reaches either artifact.
         *
         * <p>Purpose: the baseline's own sort step carries the processing timestamp at reduced precision.
         * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at L54 of {@code app/jcl/CREASTMT.JCL}
         * populates 328 of the record's 350 bytes, and with the card number at 263 to 278, the
         * originating stamp at 279 to 304 and the processing stamp at 305 to 330 by the declared widths of
         * {@code app/cpy/CVTRA05Y.cpy}, the output's processing stamp receives only 24 of its 26
         * characters -- the final two microsecond digits are lost.</p>
         *
         * <p>Assumptions: this is an artifact observation and is reproduced as one. The seventeen
         * statement bands render no timestamp of any kind, so the reduced precision cannot reach either
         * output, and asserting that absence is what keeps a band added later from introducing the
         * truncation unnoticed. Both the whole stamp and its 24-character prefix are searched for, so a
         * band rendering either form would fail.</p>
         *
         * <p>WHY: the provenance is L54 of {@code app/jcl/CREASTMT.JCL} for the reformatting, the field
         * widths of {@code app/cpy/CVTRA05Y.cpy} for the arithmetic, and the band block at L86 to L146 of
         * {@code app/cbl/CBSTM03A.CBL}, which declares no timestamp item at all.</p>
         *
         * <p>It takes no parameter and returns no value.</p>
         */
        @Test
        @DisplayName("neither timestamp of a transaction reaches either artifact")
        void neitherTimestampOfATransactionReachesEitherArtifact() {
            List<FixtureCard> cards = fixtureCards(WIDTH_CLASS_CARD);
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);

            StringBuilder emitted = new StringBuilder();
            for (byte[] record : sink.plainRecords) {
                emitted.append(new String(record, StandardCharsets.US_ASCII));
            }
            for (byte[] record : sink.markupRecords) {
                emitted.append(new String(record, StandardCharsets.US_ASCII));
            }

            int truncatedWidth = 24;
            List<String> stamps = new ArrayList<>();
            for (Map<String, Object> row : decodedFixture(TRANSACTION_FIXTURE, "TRNX")) {
                if (!trimmedText(row, "TRNX-CARD-NUM").equals(WIDTH_CLASS_CARD)) {
                    continue;
                }
                for (String field : List.of("TRNX-PROC-TS", "TRNX-ORIG-TS")) {
                    String stamp = trimmedText(row, field);
                    stamps.add(stamp);
                    stamps.add(stamp.substring(0, truncatedWidth));
                }
            }

            assertThat(stamps)
                    .as("the fixture rows for this card carry both stamps, so there is something to "
                            + "look for")
                    .isNotEmpty();
            assertThat(emitted.toString())
                    .as("and no band renders either stamp, in the whole or the 24-character form the "
                            + "sort step's reformatting would leave")
                    .doesNotContain(stamps.toArray(new String[0]));
        }
    }
    /**
     * Reads one fixture file out of a named directory and returns its rows as fixed-width byte arrays.
     *
     * <p>Assumptions: an empty line is skipped rather than returned as a zero-length record, because a
     * fixture file's trailing newline would otherwise decode as a record of no bytes and fail the
     * layout's own width check with a message about the subject.</p>
     *
     * @param directory the directory holding the fixture; must not be {@code null}
     * @param fileName the fixture file name within it; must not be {@code null}
     * @return one byte array per fixture row, in file order; never {@code null}
     * @throws UncheckedIOException if the named file cannot be read
     */
    private static List<byte[]> fixtureRowsFrom(Path directory, String fileName) {
        try {
            List<byte[]> rows = new ArrayList<>();
            for (String row : Files.readAllLines(
                    directory.resolve(fileName), StandardCharsets.US_ASCII)) {
                if (!row.isEmpty()) {
                    rows.add(row.getBytes(StandardCharsets.US_ASCII));
                }
            }
            return rows;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("fixture " + fileName + " could not be read", unreadable);
        }
    }

    /**
     * Builds the fixture cards of a named directory, in the order the heading query declares.
     *
     * <p>Assumptions: every property the varargs form documents holds here unchanged -- the walk order
     * is the pair (masked rendering, selector) ascending, and each heading row is joined from the three
     * fixtures rather than invented.</p>
     *
     * @param directory the directory holding the four fixtures; must not be {@code null}
     * @param selection the whole card numbers to select, or an empty list to select every card the
     *     cross-reference fixture holds; must not be {@code null}
     * @return the selected cards in ascending walk order; never {@code null}
     * @throws IllegalStateException if a selected cross-reference row names a customer or an account the
     *     fixtures do not hold, which is a broken test resource rather than a failure of the subject
     */
    private static List<FixtureCard> fixtureCardsFrom(Path directory, List<String> selection) {
        Map<Long, Map<String, Object>> customers =
                keyedFixtureFrom(directory, CUSTOMER_FIXTURE, "CUSTOMER", "CUST-ID");
        Map<Long, Map<String, Object>> accounts =
                keyedFixtureFrom(directory, ACCOUNT_FIXTURE, "ACCOUNT", "ACCT-ID");
        Map<String, List<StatementTransactionView>> rowsByCard =
                fixtureTransactionsByCardFrom(directory);

        List<FixtureCard> cards = new ArrayList<>();
        for (Map<String, Object> crossReference
                : decodedFixtureFrom(directory, CROSS_REFERENCE_FIXTURE, "XREF")) {
            String cardNumber = trimmedText(crossReference, "XREF-CARD-NUM");
            if (!selection.isEmpty() && !selection.contains(cardNumber)) {
                continue;
            }
            long customerId = (Long) crossReference.get("XREF-CUST-ID");
            long accountId = (Long) crossReference.get("XREF-ACCT-ID");
            Map<String, Object> customer = customers.get(customerId);
            Map<String, Object> account = accounts.get(accountId);
            if (customer == null || account == null) {
                throw new IllegalStateException("the cross-reference fixture names customer "
                        + customerId + " or account " + accountId + " and its own fixture omits it");
            }
            cards.add(new FixtureCard(cardNumber, CardNumberMasker.mask(cardNumber),
                    fingerprintOf(cardNumber),
                    fixtureHeadingRow(cardNumber, customerId, accountId, customer, account),
                    rowsByCard.getOrDefault(cardNumber, List.of())));
        }
        cards.sort(Comparator.comparing(FixtureCard::maskedCardNum)
                .thenComparing(FixtureCard::fingerprint));
        return List.copyOf(cards);
    }

    /**
     * Decodes one fixture in a named directory and keys its rows by one unsigned identifier field.
     *
     * @param directory the directory holding the fixture; must not be {@code null}
     * @param fileName the fixture file name within it; must not be {@code null}
     * @param layoutName the registry name of the layout to decode against
     * @param keyField the copybook field name of the unsigned identifier to key by
     * @return the decoded rows keyed by that identifier, in file order; never {@code null}
     */
    private static Map<Long, Map<String, Object>> keyedFixtureFrom(
            Path directory, String fileName, String layoutName, String keyField) {
        Map<Long, Map<String, Object>> keyed = new LinkedHashMap<>();
        for (Map<String, Object> row : decodedFixtureFrom(directory, fileName, layoutName)) {
            keyed.put((Long) row.get(keyField), row);
        }
        return keyed;
    }

    /**
     * Groups the transaction fixture of a named directory into projections by card.
     *
     * <p>Assumptions: every property the no-argument form documents holds here unchanged -- file order
     * is preserved as the within-card order, the key's card component is the masked rendering, and only
     * the four members a statement line and the group walk read are assigned.</p>
     *
     * @param directory the directory holding {@link #TRANSACTION_FIXTURE}; must not be {@code null}
     * @return the cards' rows keyed by whole card number; never {@code null}
     */
    private static Map<String, List<StatementTransactionView>> fixtureTransactionsByCardFrom(
            Path directory) {
        Map<String, List<StatementTransactionView>> byCard = new LinkedHashMap<>();
        for (Map<String, Object> row : decodedFixtureFrom(directory, TRANSACTION_FIXTURE, "TRNX")) {
            String cardNumber = trimmedText(row, "TRNX-CARD-NUM");
            StatementTransactionView projection = newProjection();
            setMember(projection, "key", new StatementTransactionView.StatementTransactionKey(
                    CardNumberMasker.mask(cardNumber), trimmedText(row, "TRNX-ID")));
            setMember(projection, "cardFingerprint", fingerprintOf(cardNumber));
            setMember(projection, "description", trimmedText(row, "TRNX-DESC"));
            setMember(projection, "amount", Money.of((BigDecimal) row.get("TRNX-AMT")));
            byCard.computeIfAbsent(cardNumber, card -> new ArrayList<>()).add(projection);
        }
        return byCard;
    }

    /**
     * Decodes every row of one fixture in a named directory against its registered layout.
     *
     * @param directory the directory holding the fixture; must not be {@code null}
     * @param fileName the fixture file name within it; must not be {@code null}
     * @param layoutName the registry name of the layout to decode against, such as {@code TRNX}
     * @return one decoded field map per row, keyed by copybook field name, in file order; never
     *     {@code null}
     */
    private static List<Map<String, Object>> decodedFixtureFrom(
            Path directory, String fileName, String layoutName) {
        CopybookLayout.RecordSpec spec = CopybookLayout.layout(layoutName);
        List<Map<String, Object>> decoded = new ArrayList<>();
        for (byte[] row : fixtureRowsFrom(directory, fileName)) {
            decoded.add(FixedWidthCodec.decodeRecord(row, spec));
        }
        return decoded;
    }

    /**
     * Resolves the repository root by walking up from the working directory.
     *
     * <p>Assumptions: located by the presence of {@code services/pom.xml} rather than by a count of
     * parent steps, so this class runs identically from the reactor root and from the module directory.
     * That is the same rule the module's integration tests use to reach repository-level files, and a
     * relative path would resolve differently between those two invocations.</p>
     *
     * @return the repository root; never {@code null}
     * @throws IllegalStateException if no ancestor carries the reactor descriptor
     */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("services/pom.xml"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException(
                "no ancestor of the working directory carries services/pom.xml");
    }

    /**
     * Whole-stream comparison of a run's two artifacts against the committed COBOL oracles.
     *
     * <h2>Why this suite exists</h2>
     *
     * <p>Purpose: every other case in this file asserts a PROPERTY of the emitted records -- a band's
     * width class, a field's edit mask, the order two cards appear in. A property suite cannot see
     * systematic content drift: a heading rendered with the wrong label, a band emitted in the wrong
     * order, or a line missing altogether satisfies every width and mask assertion in the file while
     * producing a document the reference never produced. This suite opens the two committed oracles at
     * {@code tests/golden/statement/happy_path} and compares the COMPLETE normalised stream of each,
     * byte for byte, so drift anywhere in either document fails.</p>
     *
     * <p>Assumptions: the input is the oracle's OWN input -- the four fixed-width fixtures at
     * {@code tests/fixtures/statement/happy_path}, which is what the reference pair was run over to
     * produce those two files. Driving this from the module's own 88-card fixtures would compare two
     * documents describing different portfolios, so the comparison would have to be loosened to
     * whatever the two had in common, which is the loosening this suite exists to replace.</p>
     *
     * <p>Assumptions: both files are REFERENCE. They are read and never written, and no case here
     * regenerates one -- an oracle a test may rewrite asserts nothing at all.</p>
     *
     * <p>Assumptions: the comparison applies the HARNESS's normalisation to this service's fixed-width
     * output rather than expecting the oracle to be fixed width. The reference writes fixed records --
     * {@code FD-STMTFILE-REC PIC X(80)} and {@code FD-HTMLFILE-REC PIC X(100)} at L45 and L47 of
     * {@code app/cbl/CBSTM03A.CBL}, with {@code DCB LRECL=80} and {@code LRECL=100} at L89 and L94 of
     * {@code app/jcl/CREASTMT.JCL} -- and it is
     * {@code tests/helpers/statement_compat.py} that frames and right-trims them before they are
     * stored, joining {@code record.rstrip() + "\n"} at L337 to L341. Reproducing that framing here is
     * what makes the two streams comparable; expecting 80-byte and 100-byte lines in the stored files
     * would fail against every line.</p>
     */
    @Nested
    @DisplayName("whole-stream parity with the committed COBOL oracles")
    class GoldenParity {

        /** Directory of the four fixtures the committed oracles were produced from. */
        private static final String GOLDEN_INPUT_DIRECTORY = "tests/fixtures/statement/happy_path";

        /** Directory holding the two committed oracles. */
        private static final String GOLDEN_DIRECTORY = "tests/golden/statement/happy_path";

        /** The plain-text oracle, stored right-trimmed and newline-terminated. */
        private static final String PLAIN_TEXT_ORACLE = "statement.txt.expected";

        /** The markup oracle, stored the same way. */
        private static final String MARKUP_ORACLE = "statement.html.expected";

        // WHY : Assumptions: the whole normalised stream is compared, not a line count and not a
        //       selection of lines. A count passes against two documents that differ in every byte, and a
        //       selection passes against drift in whatever it did not select -- which is exactly what
        //       this suite replaces.
        /**
         * Asserts that the plain-text stream is byte-for-byte the committed plain-text oracle.
         */
        @Test
        @DisplayName("the plain-text stream is byte-for-byte the committed oracle")
        void thePlainTextStreamIsTheCommittedOracle() {
            RecordingSink sink = runOverTheOraclesInput();

            assertThat(normalise(sink.plainRecords))
                    .as("the whole plain-text statement, framed as the harness frames it")
                    .isEqualTo(oracle(PLAIN_TEXT_ORACLE));
        }

        /**
         * Asserts that the markup stream is byte-for-byte the committed markup oracle.
         */
        @Test
        @DisplayName("the markup stream is byte-for-byte the committed oracle")
        void theMarkupStreamIsTheCommittedOracle() {
            RecordingSink sink = runOverTheOraclesInput();

            assertThat(normalise(sink.markupRecords))
                    .as("the whole markup statement, framed as the harness frames it")
                    .isEqualTo(oracle(MARKUP_ORACLE));
        }

        // WHY : Assumptions: the two record widths are asserted on the PRODUCED records rather than
        //       inferred from the oracles, and they are asserted in this suite as well as by the width
        //       cases above. The comparison above normalises the produced stream, so a generator that
        //       emitted short records would still match the oracle after trimming -- this case is what
        //       keeps the byte-level width contract observable alongside the content contract, so a
        //       change that satisfied the oracle by dropping padding cannot pass unremarked.
        /**
         * Asserts that the records normalised into the oracles were themselves at their declared widths.
         */
        @Test
        @DisplayName("the records behind the oracle comparison are at their declared fixed widths")
        void theRecordsBehindTheComparisonAreFixedWidth() {
            RecordingSink sink = runOverTheOraclesInput();

            assertThat(sink.plainRecords).isNotEmpty().allSatisfy(record ->
                    assertThat(record).hasSize(StatementBandLayouts.STATEMENT_LINE_LENGTH));
            assertThat(sink.markupRecords).isNotEmpty().allSatisfy(record ->
                    assertThat(record).hasSize(StatementHtmlMapper.HTML_RECORD_LENGTH));
        }

        /**
         * Runs a whole statement generation over the fixtures the committed oracles were produced from.
         *
         * @return the sink holding every record the run offered; never {@code null}
         */
        private RecordingSink runOverTheOraclesInput() {
            List<FixtureCard> cards = fixtureCardsFrom(
                    repositoryRoot().resolve(GOLDEN_INPUT_DIRECTORY), List.of());
            assertThat(cards)
                    .as("the oracle's input names exactly one card, so the stream is one statement")
                    .hasSize(1);
            stubFixtureWalk(cards);

            RecordingSink sink = new RecordingSink();
            service.generateStatements(sink);
            return sink;
        }

        /**
         * Frames a run's fixed-width records the way the harness frames them before storing an oracle.
         *
         * <p>Assumptions: each record is right-trimmed and newline-terminated, and the results are
         * concatenated. That is {@code statement_compat.py}'s
         * {@code "".join(record.rstrip() + "\n" for record in records)} at L341, and it is the reason
         * the stored oracles hold lines of 22 different lengths rather than lines of 80.</p>
         *
         * @param records the records the run offered, in the order it offered them; must not be
         *     {@code null}
         * @return the framed stream; never {@code null}
         */
        private String normalise(List<byte[]> records) {
            StringBuilder framed = new StringBuilder();
            for (byte[] record : records) {
                framed.append(new String(record, StandardCharsets.US_ASCII).stripTrailing())
                        .append('\n');
            }
            return framed.toString();
        }

        /**
         * Reads one committed oracle.
         *
         * @param fileName the oracle's file name within {@link #GOLDEN_DIRECTORY}; must not be
         *     {@code null}
         * @return the oracle's whole content; never {@code null}
         * @throws UncheckedIOException if the oracle cannot be read, which is a broken checkout rather
         *     than a failure of the subject
         */
        private String oracle(String fileName) {
            Path file = repositoryRoot().resolve(GOLDEN_DIRECTORY).resolve(fileName);
            try {
                return Files.readString(file, StandardCharsets.US_ASCII);
            } catch (IOException unreadable) {
                throw new UncheckedIOException(
                        "the committed oracle " + fileName + " could not be read", unreadable);
            }
        }
    }

}
