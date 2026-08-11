package com.carddemo.authorization.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.PackedDecimalCodec;
import com.carddemo.common.money.Money;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Holds {@link LoadService} to the operational contract an operator and an orchestrator depend on.
 *
 * <p><b>Purpose.</b> {@code LoadService} is the migrated form of
 * {@code app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL}. That reference program passes three
 * conditions over in silence, and this class asserts what the migrated service does at each of the three
 * instead: it raises, it discards the unit of work it was part of, and it says so where an operator will
 * read it. Every citation below is to that tree, which this migration reads and never modifies, and every
 * line number is read with columns 73 to 80 stripped because that source carries eight-digit legacy
 * sequence numbers there.
 *
 * <p>Assumptions: the reference file name is {@code PAUDBLOD.CBL} in UPPER case. Five of the eight
 * programs in that extension tree carry a lowercase {@code .cbl} extension and three an uppercase
 * {@code .CBL}, so a citation that normalises either half points at no file on disk.
 *
 * <h2>What this class asserts that its sibling does not</h2>
 *
 * <p>Alternatives Considered: folding these cases into
 * {@link AuthorizationExtractRoundTripTest}, which already drives this same service over the same
 * fixtures. Rejected because that class is named after what it proves -- that a file format survives a
 * circuit through the loader and back out through the unloader -- and its fixture is shaped for that: it
 * remembers rows in stateful doubles so an unload has something to walk. The properties below are about
 * what the service does when the circuit CANNOT be completed, and three of them are invisible to a
 * class shaped for the happy circuit. A rollback needs an observable transaction manager rather than a
 * lenient stub. A diagnostic needs a captured appender. A refusal held against a tolerated duplicate
 * needs both outcomes in one case. Adding those to a round-trip fixture would weaken the round trip's own
 * verifications, which is the same argument the package charter records for
 * {@code PendingAuthDetailProjectionTest} being a second class over one service.
 *
 * <p>Trade-offs: two classes over one service means a reader has to know which one owns which claim, and
 * the cost is accepted because the alternative is worse in a specific way. The subject here is the SERVICE
 * BOUNDARY -- what is raised, what is written, what is logged, what is rolled back -- and the subject
 * there is the FILE FORMAT. A case that asserts a byte layout belongs there and a case that asserts an
 * operator-visible outcome belongs here, and that division is stated in the package charter beside both
 * entries so the question is answered before it is asked.
 *
 * <h2>The status shape asserted here is the PCB one, and it is never unified with the DIB one</h2>
 *
 * <p>Assumptions: exactly three programs of the eight in this extension tree take the batch shape --
 * {@code PAUDBLOD.CBL}, {@code PAUDBUNL.CBL} and {@code DBUNLDGS.CBL} -- combining
 * {@code COPY IMSFUNCS} with {@code CALL 'CBLTDLI'} and testing {@code PAUT-PCB-STATUS}. The five
 * online and message-region programs use {@code EXEC DLI} and evaluate {@code DIBSTAT} instead. This
 * class and {@code UnloadServiceTest} assert against the first shape and {@code PurgeJobTest} against the
 * second, and the two are deliberately not unified: they are different status areas set by different
 * interfaces, and a helper spanning both would assert a status area neither program has.
 *
 * <p>Assumptions: {@code PAUDBLOD.CBL} declares its function codes from {@code cpy/IMSFUNCS.cpy}
 * <strong>L17 to L26</strong>, nine {@code PIC X(04)} constants, and uses two of them -- {@code FUNC-GU}
 * at L18 for the parent positioning and {@code FUNC-ISRT} at L25 for both inserts. The three HOLD forms
 * {@code FUNC-GHU} at L19, {@code FUNC-GHN} at L21 and {@code FUNC-GHNP} at L23 are declared and are
 * referenced by no program in the tree. Nothing here asserts anything about hold semantics or about what
 * the reference program's locking outcome was, because that is not observable without an IMS runtime; the
 * absence is recorded so a reader does not mistake it for an omission. No case below declares a lock
 * mode, and this module introduces no pessimistic locking on the load path.
 *
 * <h2>There is no golden master for this path, and that is why these cases exist</h2>
 *
 * <p>Assumptions: the committed COBOL harness cannot produce an oracle for this program.
 * {@code tests/README.md} section 5.2 shows its build step compiling from {@code app/cbl/} only, and
 * section 1.1 scopes even that to ten of the twelve batch programs there -- so the three extension trees
 * under {@code app/app-*} are never compiled and never run. The behaviour asserted below is therefore
 * derived from the reference SOURCE by reading it, not from a recorded output, and the derivation is
 * written out at each case for exactly that reason.
 *
 * <p>Assumptions: no reference source is edited and none is deleted. The three seams named below are
 * defects in immutable material, and the house has already fixed the precedent for what that obliges:
 * {@code tests/README.md} section 1.1 records an unfixable record-key defect in two baseline programs,
 * states that no compiler flag can fix it and that the minimal-change principle forbids editing it, and
 * gives its reason at lines 50 to 51 -- so that no runnable claim hides a blocked feature, which it calls
 * a financial-enterprise auditability requirement. Following that precedent, each seam here is registered
 * in {@code docs/architecture/cobol-to-service-traceability.md} rather than corrected in place.
 *
 * <h2>How the governing rule is satisfied here</h2>
 *
 * <p>Assumptions: the project's user-specified Rule 1 (Explainability) governs every member of this class,
 * and its gate is conjunctive -- a member missing its documentation fails it, and a non-obvious decision
 * missing its stated reason fails it separately. The ruling it produces for a test class is what shapes
 * every comment below: the assertion itself is the WHAT and needs no narration, so the reason recorded
 * beside each one is the DERIVATION -- which line of {@code PAUDBLOD.CBL} the expected behaviour was read
 * from, and why the migrated outcome differs from it where it does. A note that merely announced an
 * expected exception would restate the assertion and satisfy nothing. Where a choice had a reasonable
 * alternative, the alternative is named and rejected with a specific consequence rather than a preference.
 * The rule's own text is not reproduced anywhere in this file; it is cited by name and its ruling stated,
 * and the text itself lives in the project's rules document.
 *
 * <p>Assumptions: a green Checkstyle run is a floor rather than a proof of that rule. The configured gate
 * measures whether a member carries documentation and whether its parameters, returns and exceptions are
 * described; no gate can measure whether a stated reason is the real one. The labelled reasons below are
 * therefore written for a reader rather than for the checker, and the checker is what stops one being
 * omitted altogether.
 *
 * <p>This class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
class LoadServiceTest {

    /** The class-path directory the committed extract fixtures live in. */
    private static final String FIXTURES = "/fixtures/";

    /**
     * The hierarchical unload's summary file: two hundred-byte roots, higher account FIRST.
     *
     * <p>Assumptions: the descending order is a property of the file rather than an accident of how it was
     * generated, recorded at {@code src/test/resources/fixtures/README.md} L83, and it is what makes the
     * two-pass cases below able to fail. A loader that depended on its input being sorted would load this
     * file and its ascending sibling to different results.
     */
    private static final String SUMMARY_FIXTURE = "unload-prefixed-summary-100.bin";

    /**
     * The hierarchical unload's child file: four two-hundred-and-six-byte prefixed records.
     *
     * <p>Assumptions: the four parent prefixes ALTERNATE -- account one, account two, account one,
     * account two -- rather than being grouped, recorded at
     * {@code src/test/resources/fixtures/README.md} L197. Interleaving is what proves parentage comes from
     * each record's own prefix and not from its adjacency to the record before it.
     */
    private static final String DETAIL_FIXTURE = "unload-prefixed-detail-206.bin";

    /** The lower of the two accounts the fixtures carry. */
    private static final Long ACCOUNT_ONE = Long.valueOf(10_000_000_001L);

    /** The higher of the two accounts the fixtures carry. */
    private static final Long ACCOUNT_TWO = Long.valueOf(10_000_000_002L);

    /**
     * The summary segment's own length, one hundred bytes.
     *
     * <p>Assumptions: from {@code cpy/CIPAUSMY.cpy} <strong>L19 to L31</strong>, thirteen declarations
     * that sum to exactly one hundred and end in {@code FILLER PIC X(34)}, and independently from
     * {@code cbl/PAUDBLOD.CBL} <strong>L44</strong>, {@code 01 INFIL1-REC PIC X(100)}. AAP Rule T1
     * (copybook is normative) is what makes the copybook the deciding source of the two.
     */
    private static final int SUMMARY_STRIDE = 100;

    /**
     * The prefixed child record's length, two hundred and six bytes.
     *
     * <p>Assumptions: from {@code cbl/PAUDBLOD.CBL} <strong>L46 to L48</strong>, where
     * {@code 01 INFIL2-REC} is {@code 05 ROOT-SEG-KEY PIC S9(11) COMP-3} ahead of
     * {@code 05 CHILD-SEG-REC PIC X(200)}. The segment's own two hundred bytes come from
     * {@code cpy/CIPAUDTY.cpy} <strong>L19 to L54</strong>, which sums to exactly two hundred and ends in
     * {@code FILLER PIC X(17)}.
     */
    private static final int DETAIL_STRIDE = 206;

    /**
     * The parent-key prefix's width in bytes, six.
     *
     * <p>Assumptions: {@code ROOT-SEG-KEY} is {@code PIC S9(11) COMP-3}, and a packed field occupies
     * {@code ceil((digits + 1) / 2)} bytes because it carries two digits per byte with a trailing sign
     * nibble -- so eleven digits occupy six bytes and NOT eleven characters. The same ladder gives three
     * digits two bytes, five digits three, nine digits five and twelve digits seven, which is why the two
     * {@code PIC S9(10)V99 COMP-3} amounts at {@code cpy/CIPAUDTY.cpy} L34 and L35 occupy SEVEN bytes
     * each rather than six. That is not a detail: at six each the segment would total one hundred and
     * ninety-eight, and the declared two hundred is the independent arithmetic that settles it.
     */
    private static final int PREFIX_WIDTH = 6;

    /** The integer digit count of the packed parent key, eleven, with no decimal places. */
    private static final int PREFIX_DIGITS = 11;

    /**
     * The detail segment's own length, two hundred bytes, behind the six-byte prefix.
     *
     * <p>Assumptions: from {@code cpy/CIPAUDTY.cpy} <strong>L19 to L54</strong>, whose declarations sum to
     * exactly two hundred and end in {@code FILLER PIC X(17)}, and from
     * {@code cbl/PAUDBLOD.CBL} <strong>L48</strong>, {@code 05 CHILD-SEG-REC PIC X(200)}.
     */
    private static final int SEGMENT_LENGTH = 200;

    /** The integer digit count of the segment's two packed money fields, ten. */
    private static final int AMOUNT_INTEGER_DIGITS = 10;

    /** The decimal digit count of the segment's two packed money fields, two. */
    private static final int AMOUNT_DECIMAL_DIGITS = 2;

    /**
     * The byte width of one {@code PIC S9(10)V99 COMP-3} field, seven.
     *
     * <p>Assumptions: twelve digits plus a sign nibble is thirteen nibbles, which occupies seven bytes and
     * not six. This is stated as its own constant because six is the intuitive answer and getting it wrong
     * moves every field offset after {@code cpy/CIPAUDTY.cpy} L34 by two bytes.
     */
    private static final int AMOUNT_PACKED_WIDTH = 7;

    /** How many roots the summary fixture holds. */
    private static final int ROOT_COUNT = 2;

    /** How many children the detail fixture holds. */
    private static final int CHILD_COUNT = 4;

    /** The summary rows the loader probes and writes. */
    private PendingAuthSummaryRepository summaries;

    /** The authorization rows the loader probes and writes. */
    private PendingAuthDetailRepository details;

    /**
     * The transaction manager each chunk's unit of work is opened against.
     *
     * <p>Assumptions: this is held as a FIELD rather than created inside a helper, because the rollback
     * assertions below interrogate it after the call under test has returned or raised. A manager created
     * and discarded inside a factory method could not be verified, which is precisely why the sibling
     * round-trip class -- whose cases never ask about rollback -- can keep its own inside one.
     */
    private PlatformTransactionManager transactionManager;

    /** The status object the manager hands back, so a rollback can be verified against the same one. */
    private TransactionStatus transactionStatus;

    /** The rows the summary double has accepted so far, in acceptance order. */
    private List<PendingAuthSummary> storedRoots;

    /** The rows the authorization double has accepted so far, in acceptance order. */
    private List<PendingAuthDetail> storedChildren;

    /** The captured log records the service emitted during one case. */
    private ListAppender<ILoggingEvent> captured;

    /** The service logger the appender is attached to, retained so it can be detached again. */
    private ch.qos.logback.classic.Logger serviceLogger;

    /** The level that logger carried before the case lowered it, restored afterwards. */
    private Level previousLevel;

    /**
     * Builds the doubles, the observable transaction manager and the log capture for one case.
     *
     * <p>Assumptions: the repositories are plain {@code mock} instances created here rather than injected
     * by the Mockito extension. The extension's strict stubbing would flag the stubs that individual
     * cases below deliberately do not exercise -- a case about a malformed extract refuses before any
     * repository is reached -- and each case states its own stubbing anyway, so the extension would add a
     * failure mode without adding a check.
     *
     * <p>Assumptions: the logger's level is lowered to {@code INFO} for the duration of a case, because
     * two of the diagnostics asserted below are emitted at that level and a container configured for
     * {@code WARN} would drop them before the appender saw them. The previous level is remembered rather
     * than assumed, so the change cannot leak into a later class in the same JVM.
     */
    @BeforeEach
    void setUp() {
        this.summaries = mock(PendingAuthSummaryRepository.class);
        this.details = mock(PendingAuthDetailRepository.class);
        this.storedRoots = new ArrayList<>();
        this.storedChildren = new ArrayList<>();
        this.transactionManager = mock(PlatformTransactionManager.class);
        this.transactionStatus = mock(TransactionStatus.class);
        // WHY : Assumptions: the stub is LENIENT because a malformed extract is refused while the record
        //       reader resolves the first chunk, which happens BEFORE any transaction is opened. Cases
        //       about malformed input therefore never ask this manager for anything, and that ordering is
        //       itself worth keeping: a mis-handed file costs a message rather than a connection.
        lenient().when(this.transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(this.transactionStatus);
        this.serviceLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LoadService.class);
        this.previousLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.INFO);
        this.captured = new ListAppender<>();
        this.captured.start();
        this.serviceLogger.addAppender(this.captured);
    }

    /**
     * Detaches the appender and restores the logger level the case found.
     *
     * <p>Assumptions: the teardown is unconditional rather than being placed in each case's own
     * {@code finally}. An appender left attached would accumulate records across the rest of the run and
     * a level left lowered would change what a later class observes, and both failures would surface
     * somewhere other than here -- which is the hardest kind of test interference to attribute.
     */
    @AfterEach
    void tearDown() {
        this.serviceLogger.detachAppender(this.captured);
        this.captured.stop();
        this.serviceLogger.setLevel(this.previousLevel);
    }

    /**
     * Builds a loader over the doubles and the observable transaction manager.
     *
     * @return a loader at the default record ceiling, writing the two repository doubles; never
     *     {@code null}
     */
    private LoadService loader() {
        return loader(LoadService.DEFAULT_MAX_RECORDS);
    }

    /**
     * Builds a loader whose extract ceiling is stated by the caller.
     *
     * @param maxRecords the greatest number of records one extract file may hold
     * @return a loader at that ceiling, writing the two repository doubles; never {@code null}
     */
    private LoadService loader(int maxRecords) {
        // WHY : Assumptions: the template propagates REQUIRES_NEW so that each chunk's unit of work is
        //       its own, which is what makes a rollback attributable to the chunk that raised. Joining an
        //       outer transaction would leave the rollback assertions below unable to say WHICH unit of
        //       work was discarded.
        TransactionTemplate template = new TransactionTemplate(this.transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new LoadService(this.summaries, this.details, template, maxRecords);
    }

    /**
     * Makes the summary double remember what it accepts and answer presence from that memory.
     *
     * <p>Assumptions: the double is STATEFUL rather than a fixed stub, and that is a requirement of the
     * subject rather than a convenience. Within one load the child pass asks whether each record's parent
     * exists, and a double answering a constant would report either every child orphaned or every summary
     * already stored -- neither of which is the situation any case here is about. A real repository
     * answers correctly because a presence query sees its own transaction's committed inserts; this
     * remembers what it accepted, which is the same observable behaviour.
     */
    private void givenSummariesRemember() {
        when(this.summaries.insertSummaryIfAbsent(any(PendingAuthSummary.class)))
                .thenAnswer(call -> {
                    PendingAuthSummary row = call.getArgument(0);
                    this.storedRoots.add(row);
                    return Integer.valueOf(1);
                });
        when(this.summaries.findExistingAccountIds(anyCollection())).thenAnswer(call -> {
            Collection<Long> asked = call.getArgument(0);
            return this.storedRoots.stream()
                    .map(PendingAuthSummary::getAccountId)
                    .filter(asked::contains)
                    .distinct()
                    .toList();
        });
    }

    /**
     * Makes the authorization double remember what it accepts and answer presence from that memory.
     */
    private void givenDetailsRemember() {
        when(this.details.insertDetailIfAbsent(any(PendingAuthDetail.class))).thenAnswer(call -> {
            PendingAuthDetail row = call.getArgument(0);
            this.storedChildren.add(row);
            return Integer.valueOf(1);
        });
        when(this.details.findExistingIds(anyCollection())).thenAnswer(call -> {
            Collection<PendingAuthDetailKey> asked = call.getArgument(0);
            return this.storedChildren.stream()
                    .map(PendingAuthDetail::getId)
                    .filter(asked::contains)
                    .toList();
        });
    }

    /**
     * Opens one committed fixture as a stream.
     *
     * @param name the fixture's file name within the class-path fixture directory; must not be
     *     {@code null}
     * @return a stream over that fixture's bytes; never {@code null}
     */
    private static InputStream open(String name) {
        return new ByteArrayInputStream(bytes(name));
    }

    /**
     * Reads one committed fixture in full.
     *
     * @param name the fixture's file name within the class-path fixture directory; must not be
     *     {@code null}
     * @return the fixture's whole contents as raw bytes; never {@code null}
     * @throws AssertionError if the fixture is absent from the test class path, which fails rather than
     *     skips: a case that silently ran against no input would report a passing verdict on nothing
     */
    private static byte[] bytes(String name) {
        try (InputStream source = LoadServiceTest.class.getResourceAsStream(FIXTURES + name)) {
            if (source == null) {
                throw new AssertionError("fixture " + name + " is not on the test class path");
            }
            return source.readAllBytes();
        } catch (IOException unreadable) {
            throw new AssertionError("fixture " + name + " could not be read", unreadable);
        }
    }

    /**
     * Collects the captured log messages at one level, with their placeholders already substituted.
     *
     * @param level the logback level to select records at; must not be {@code null}
     * @return the formatted messages of every captured record at that level, in emission order; never
     *     {@code null}
     */
    private List<String> messagesAt(Level level) {
        // WHY : Assumptions: the FORMATTED message is collected rather than the template, because what an
        //       operator reads is the substituted line. A template assertion would pass for a service
        //       that named the right field and interpolated the wrong value into it.
        return this.captured.list.stream()
                .filter(record -> record.getLevel().equals(level))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * Asserts the seam the reference program's single {@code END-IF} left unreported.
     *
     * <p><b>Purpose.</b> Divergence <strong>D-C</strong>, registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}. The cases here assert the three
     * properties an operator needs from the corrected behaviour and which the sibling round-trip class
     * does not reach: that the refusal is RAISED, that the unit of work it was part of is DISCARDED, and
     * that it is VISIBLE in the job log.
     */
    @Nested
    @DisplayName("D-C: a root positioning failure is reported and its unit of work discarded")
    class RootPositioningFailure {

        /**
         * A child whose parent summary is absent raises rather than being passed over in silence.
         *
         * <p>Refactoring Rationale: <strong>D-C.</strong> The reference program neither inserts such a
         * child nor reports it, and that is a consequence of statement structure rather than a stated
         * tolerance. In {@code 3100-INSERT-CHILD-SEG} the positioning call spans
         * {@code cbl/PAUDBLOD.CBL} <strong>L296 to L299</strong> and is terminated by a period at L299,
         * so from L300 the indentation stops tracking scope. <strong>L305</strong>,
         * {@code IF PAUT-PCB-STATUS = SPACES}, opens the branch taken when positioning SUCCEEDED, and the
         * insert follows at L309. The failure test at <strong>L310</strong>,
         * {@code IF PAUT-PCB-STATUS NOT EQUAL TO SPACES AND 'II'}, sits INSIDE that success branch, and
         * the single {@code END-IF.} at <strong>L314</strong> closes both of them. A genuine positioning
         * status therefore makes L305 false and passes control from L305 straight to L315, reaching
         * neither the insert at L309 nor the abend at L313. The record leaves the run with no row and no
         * message.
         *
         * <p>Refactoring Rationale: that reading is a localised oversight rather than a house convention,
         * and the proof is inside the same program. The sibling ROOT path
         * {@code 2100-INSERT-ROOT-SEG} is written FLAT at <strong>L253 to L262</strong> -- success at
         * L253 to L255, the duplicate status at L256 to L258, and everything else at L259 to L262
         * reaching the abend at L261 -- with each test closed by its own {@code END-IF}. The child insert
         * at <strong>L326 to L336</strong> carries that same flat three-way shape a second time. One
         * shape out of three differs, in one program, by one {@code END-IF}. And L310's own body says
         * what it was for: L311 writes {@code 'ROOT GU CALL FAIL:'} with the status and L312 writes the
         * key feedback area, so the text describes reporting a failure the enclosing branch prevents it
         * from ever seeing.
         *
         * <p>Assumptions: the positioning call is a PRECONDITION of the insert and not a lookup, which is
         * what turns a missing message into a data-integrity hazard rather than a cosmetic gap. The child
         * insert at <strong>L321 to L324</strong> passes {@code CHILD-UNQUAL-SSA}, declared at
         * <strong>L125 to L127</strong> as nine bytes -- {@code 'PAUTDTL1'} and one blank -- carrying NO
         * key of its own. The child is therefore positioned entirely by the {@code FUNC-GU} that preceded
         * it, so continuing past a failed position would insert against whatever parent was last
         * positioned rather than against the one the record names.
         *
         * @throws AssertionError if the refusal does not locate the record it refused
         */
        @Test
        @DisplayName("an absent parent summary raises, locating the record rather than passing it over")
        void anAbsentParentRaisesInsteadOfBeingPassedOver() {
            when(LoadServiceTest.this.summaries.findExistingAccountIds(anyCollection()))
                    .thenReturn(List.of());

            LoadService subject = loader();

            // WHY : Refactoring Rationale: a raise is asserted here because the reference program does
            //       NOT raise. The single END-IF. at PAUDBLOD.CBL L314 closes both the success test at
            //       L305 and the failure test at L310 nested inside it, so a genuine positioning status
            //       falls past the insert AND past the abend, leaving no row and no message.
            // WHY : Assumptions: the raise has to be fatal rather than a logged skip because the child
            //       insert's search argument, CHILD-UNQUAL-SSA at L125 to L127, carries no key. The child
            //       is placed by the positioning call alone, which makes that call a precondition rather
            //       than a lookup: continuing past a failed one inserts against whatever parent the
            //       database was last positioned on.
            assertThatExceptionOfType(LoadService.UnresolvedParentException.class)
                    .isThrownBy(() -> subject.loadDetails(open(DETAIL_FIXTURE)))
                    .satisfies(refused -> {
                        assertThat(refused.getRecordOrdinal())
                                .as("the refusal locates the record one-based, so the extract can be"
                                        + " opened at it")
                                .isEqualTo(1);
                        assertThat(refused.getAccountId())
                                .as("the first prefixed record of this fixture names the lower account")
                                .isEqualTo(ACCOUNT_ONE);
                    });
        }

        /**
         * The refusal discards the unit of work rather than leaving the chunk's earlier writes committed.
         *
         * <p>Assumptions: this is asserted separately from the refusal itself because the two are
         * independent, and a service can have one without the other. A loader that raised after its
         * template had already committed would produce the right exception and still leave a chunk of
         * authorizations stored against parents that do not exist -- which is the state the schema's own
         * {@code fk_pending_auth_detail_summary} exists to forbid, reached from the other side. The
         * rollback is what makes a corrective re-run start from a state that is wholly absent rather than
         * partly applied.
         *
         * <p>Assumptions: the assertion is on the transaction MANAGER rather than on the doubles, because
         * a mock repository cannot un-remember what it accepted. Verifying that the manager was asked to
         * roll back the same status object it handed out is the observable form of the property; asserting
         * the doubles are empty would assert the doubles' own inability to roll back.
         *
         * @throws AssertionError if the unit of work was committed rather than rolled back
         */
        @Test
        @DisplayName("the refusal rolls back the chunk's unit of work instead of committing it")
        void theRefusalRollsBackTheUnitOfWork() {
            when(LoadServiceTest.this.summaries.findExistingAccountIds(anyCollection()))
                    .thenReturn(List.of());

            LoadService subject = loader();

            assertThatExceptionOfType(LoadService.UnresolvedParentException.class)
                    .isThrownBy(() -> subject.loadDetails(open(DETAIL_FIXTURE)));

            verify(LoadServiceTest.this.transactionManager)
                    .rollback(LoadServiceTest.this.transactionStatus);
            verify(LoadServiceTest.this.transactionManager, never())
                    .commit(LoadServiceTest.this.transactionStatus);
        }

        /**
         * The refusal is visible in the job log, which is the half the reference program lost entirely.
         *
         * <p>Refactoring Rationale: <strong>D-C.</strong> What L310 to L312 were written to emit is a
         * diagnostic, and the enclosing branch at L305 is what stopped them emitting it. Restoring the
         * exception alone would recover the control flow and not the reporting: an orchestrator surfaces a
         * failed task, but the operator's question is WHICH record of the extract they are holding was
         * refused, and only a log line answers that at the moment it happens. This case asserts the line
         * exists and is at {@code ERROR}, because a refusal reported at {@code INFO} would be filtered out
         * by any production threshold and the reference program's silence would be reproduced by
         * configuration.
         *
         * <p>Assumptions: the diagnostic is asserted to name the record ORDINAL and to omit the ACCOUNT.
         * Both halves are load-bearing. Without the first the line carries nothing an operator can act
         * on; without the second the line is a durable copy of a customer identifier sitting outside the
         * store that protects it, which {@code docs/architecture/observability.md} forbids. The account is
         * still reachable -- the case above reads it from the typed accessor on the refusal -- so nothing
         * a caller needs is lost by keeping it out of the rendering.
         *
         * @throws AssertionError if no error-level diagnostic located the refused record, or if one
         *     disclosed the account
         */
        @Test
        @DisplayName("the refusal is reported at error level, naming the record and not the account")
        void theRefusalIsReportedAtErrorLevel() {
            when(LoadServiceTest.this.summaries.findExistingAccountIds(anyCollection()))
                    .thenReturn(List.of());

            LoadService subject = loader();

            assertThatExceptionOfType(LoadService.UnresolvedParentException.class)
                    .isThrownBy(() -> subject.loadDetails(open(DETAIL_FIXTURE)));

            assertThat(messagesAt(Level.ERROR))
                    .as("the reference program reports nothing here; the migrated service must report"
                            + " something, and it must locate the record")
                    .isNotEmpty()
                    .anySatisfy(line -> assertThat(line).contains("recordOrdinal=1"));
            assertThat(messagesAt(Level.ERROR))
                    .as("an account identifier may not appear in a durable diagnostic")
                    .noneMatch(line -> line.contains(String.valueOf(ACCOUNT_ONE)));
        }

        /**
         * No authorization row is written for a record whose parent could not be resolved.
         *
         * <p>Assumptions: the parent check runs for EVERY record of the chunk before the first write is
         * attempted, so the absence asserted here is total rather than partial. That ordering is what
         * makes the refusal cost one presence query rather than a query plus a write, and it is why the
         * child presence query is asserted never to have been reached: a loader that probed the child
         * table first would have done work it could not use.
         *
         * @throws AssertionError if any row was written, or if the child table was read before the
         *     parents were validated
         */
        @Test
        @DisplayName("nothing is written and the child table is never even read")
        void nothingIsWrittenForAnUnresolvableParent() {
            when(LoadServiceTest.this.summaries.findExistingAccountIds(anyCollection()))
                    .thenReturn(List.of());

            LoadService subject = loader();

            assertThatExceptionOfType(LoadService.UnresolvedParentException.class)
                    .isThrownBy(() -> subject.loadDetails(open(DETAIL_FIXTURE)));

            verify(LoadServiceTest.this.details, never()).insertDetailIfAbsent(any());
            verify(LoadServiceTest.this.details, never()).findExistingIds(anyCollection());
            assertThat(LoadServiceTest.this.storedChildren)
                    .as("the fixture holds four children and none of them may reach the store")
                    .isEmpty();
        }

        /**
         * A duplicate and an unresolvable parent are handled differently, so idempotence survives the fix.
         *
         * <p>Assumptions: this is the guard against over-correcting D-C. The reference program TOLERATES
         * the duplicate status at both levels -- {@code cbl/PAUDBLOD.CBL} L256 to L258 on the root and
         * L329 to L331 on the child, each a {@code DISPLAY} and no abend -- so a fix that made every
         * unexpected condition fatal would turn a re-run of a partly completed load into a failure. Since
         * a re-run is exactly how an operator recovers such a load, the two outcomes are asserted side by
         * side in one case: the same service, one input that duplicates and one that cannot be attributed,
         * and two different results.
         *
         * <p>Alternatives Considered: asserting the two in separate cases, which is where they would
         * naturally fall. Rejected because the property is the DIFFERENCE between them, and a difference
         * split across two cases can be broken by a change that makes both of them pass individually --
         * raising on the duplicate as well would satisfy a refusal case and a separate duplicate case
         * would then be the only thing failing, reported as a duplicate-handling defect rather than as
         * the over-correction it is.
         *
         * @throws AssertionError if a duplicate raises, or if an unresolvable parent does not
         */
        @Test
        @DisplayName("a duplicate is tolerated where an unresolvable parent is refused")
        void aDuplicateIsToleratedWhereAnUnresolvableParentIsRefused() {
            givenSummariesRemember();
            LoadService subject = loader();
            subject.loadSummaries(open(SUMMARY_FIXTURE));

            LoadService.LoadOutcome duplicated = subject.loadSummaries(open(SUMMARY_FIXTURE));

            assertThat(duplicated.alreadyPresent())
                    .as("every root of a second pass is already stored, and that is not a failure")
                    .isEqualTo(ROOT_COUNT);
            assertThat(duplicated.inserted()).isZero();

            when(LoadServiceTest.this.summaries.findExistingAccountIds(anyCollection()))
                    .thenReturn(List.of());
            assertThatExceptionOfType(LoadService.UnresolvedParentException.class)
                    .as("an unattributable child is a different condition and takes a different outcome")
                    .isThrownBy(() -> subject.loadDetails(open(DETAIL_FIXTURE)));
        }
    }

    /**
     * Asserts the child insert surfaces an unexpected failure, matching the paragraph that already did.
     *
     * <p><b>Purpose.</b> The child insert is the one path of the three in this program that needed no
     * correction, and the cases here assert the migrated service kept it that way.
     * {@code 3200-INSERT-IMS-CALL} at {@code cbl/PAUDBLOD.CBL} <strong>L318 to L339</strong> is written
     * flat and correctly: the call spans <strong>L321 to L324</strong>, success is reported at L326 to
     * L328, the duplicate status is tolerated at L329 to L331, and every other status reaches
     * <strong>L335</strong>, {@code PERFORM 9999-ABEND}, whose own paragraph sets return code 16.
     *
     * <p>Assumptions: NO case in this class asserts that a child insert fails silently, and that absence
     * is deliberate rather than an oversight. There is no such condition to assert -- L332 to L336 close
     * with their own {@code END-IF} and reach the abend -- and a case written for one would be asserting a
     * divergence from behaviour the reference program does not have. The absence is recorded here because
     * the nested shape fifty lines earlier makes it a reasonable thing to go looking for, and finding
     * nothing is otherwise indistinguishable from finding a gap.
     */
    @Nested
    @DisplayName("the child insert surfaces an unexpected failure rather than counting it as a skip")
    class ChildInsertFailure {

        /**
         * A child insert that fails for a reason other than a duplicate propagates out of the load.
         *
         * <p>Assumptions: the property is asserted at the CHILD level specifically. Its sibling
         * round-trip class covers the same property on the summary path, and the two paths are separate
         * code with separate presence handling, so a change that tolerated a store failure on one would
         * not be caught by a case aimed at the other. The reference program is likewise explicit at both
         * levels -- L259 to L262 on the root and L332 to L336 on the child -- and reproducing only one of
         * the pair would be a partial transcription.
         *
         * <p>Assumptions: the failure is distinguished from a duplicate by the SHAPE of the outcome and
         * not by a status string. A duplicate is a returned count, because the conflict-tolerant statement
         * reports zero rows affected; an unexpected condition is a raised throwable. That is the target's
         * form of the reference program's two-way test, and it is why nothing here compares a two-character
         * status literal: the migrated service has no {@code PAUT-PCB-STATUS} area to compare, only the
         * two outcomes that area selected between.
         *
         * @throws AssertionError if the store failure is swallowed, or if it is reported as a skip
         */
        @Test
        @DisplayName("a store failure on the child insert propagates instead of being swallowed")
        void aChildStoreFailurePropagates() {
            givenSummariesRemember();
            LoadService subject = loader();
            subject.loadSummaries(open(SUMMARY_FIXTURE));
            when(LoadServiceTest.this.details.findExistingIds(anyCollection())).thenReturn(List.of());
            when(LoadServiceTest.this.details.insertDetailIfAbsent(any(PendingAuthDetail.class)))
                    .thenThrow(new IllegalStateException("authorization store unavailable"));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> subject.loadDetails(open(DETAIL_FIXTURE)))
                    .withMessage("authorization store unavailable");

            verify(LoadServiceTest.this.transactionManager)
                    .rollback(LoadServiceTest.this.transactionStatus);
        }

        /**
         * A child insert that reports no rows affected is counted as a skip and never raised.
         *
         * <p>Assumptions: this is the other half of the two-way test, and it is asserted beside the
         * failure above so the pair cannot drift apart. A conflict-tolerant statement reporting zero rows
         * is the target's form of the duplicate status at L329 to L331, which the reference program
         * reports and continues past -- so a service that raised here would fail a re-run of a partly
         * completed load, and a re-run is how such a load is recovered.
         *
         * <p>Assumptions: the presence query is left answering EMPTY on purpose, so every record reaches
         * the write believing itself absent. That places the whole weight of the case on the statement's
         * own conflict handling rather than on the read that precedes it, which is the only arrangement
         * that distinguishes a tolerant insert from a probe that happened to be right.
         *
         * @throws AssertionError if a zero-row insert raises, or is reported as an insertion
         */
        @Test
        @DisplayName("a child insert reporting no rows affected is counted, not raised")
        void aZeroRowChildInsertIsCounted() {
            givenSummariesRemember();
            LoadService subject = loader();
            subject.loadSummaries(open(SUMMARY_FIXTURE));
            when(LoadServiceTest.this.details.findExistingIds(anyCollection())).thenReturn(List.of());
            when(LoadServiceTest.this.details.insertDetailIfAbsent(any(PendingAuthDetail.class)))
                    .thenReturn(Integer.valueOf(0));

            LoadService.LoadOutcome outcome = subject.loadDetails(open(DETAIL_FIXTURE));

            assertThat(outcome.read()).isEqualTo(CHILD_COUNT);
            assertThat(outcome.alreadyPresent()).isEqualTo(CHILD_COUNT);
            assertThat(outcome.inserted()).isZero();
        }
    }

    /**
     * Asserts the seam at the numeric guard, where a record left the run with no row and no message.
     *
     * <p><b>Purpose.</b> Divergence <strong>D-LOAD-PREFIX-REFUSED</strong>, registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}. In
     * {@code 3000-READ-CHILD-SEG-FILE} the read is at {@code cbl/PAUDBLOD.CBL} <strong>L272</strong> and
     * the file status is tested at <strong>L274</strong>. Inside that branch <strong>L275</strong> tests
     * {@code IF ROOT-SEG-KEY IS NUMERIC}; on success L277 moves the packed key into the search argument,
     * L280 moves the two-hundred-byte image and L281 performs the insert. <strong>L282</strong> is the
     * closing {@code END-IF} and there is NO {@code ELSE} -- the {@code ELSE} at L283 belongs to the outer
     * status test at L274, not to this one. A record whose prefix fails the numeric test therefore
     * consumes its turn and produces nothing at all.
     */
    @Nested
    @DisplayName("a parent key that does not decode is refused and reported, never dropped")
    class MalformedParentKey {

        /**
         * An undecodable six-byte prefix is refused, and the refusal locates the record.
         *
         * <p>Refactoring Rationale: the reference program drops such a record silently, and the house has
         * already settled which way that has to be corrected rather than leaving it to taste.
         * {@code tests/fixtures/README.md} at <strong>lines 140 to 152</strong> states that the committed
         * readers reject any physical row whose length is not exactly the record length, that they do not
         * pad short rows, do not truncate long ones and do not silently drop blank lines, and it gives the
         * reason at lines 151 to 152: a malformed monetary record must never be silently coerced into a
         * well-formed-looking one. A dropped record is the same failure one step further on -- not coerced
         * into a wrong value but removed from the run -- so the same stance applies, and this class
         * follows an existing precedent rather than inventing one.
         *
         * <p>Assumptions: the prefix is corrupted by writing a DIGIT into the sign position, which is the
         * last of the six bytes. A packed field's final nibble is its sign, so a digit there is exactly
         * what the reference numeric test rejects, and it is a corruption no length check can catch: the
         * record is still two hundred and six bytes. That is what makes the case able to fail for the
         * right reason.
         *
         * <p>Assumptions: the refusal names the ordinal and carries NO account, and the asymmetry with
         * D-C is the point rather than an inconsistency. An undecodable prefix yields no account
         * identifier at all, so there is nothing to report by key and the ordinal is the only handle a
         * reader has on the record. That is why the two refusals are distinct types rather than one type
         * with an optional key.
         *
         * @throws AssertionError if the record is dropped, or if the refusal does not locate it
         */
        @Test
        @DisplayName("a digit in the packed sign position is refused, naming the record ordinal")
        void anUndecodablePrefixIsRefusedAndLocated() {
            byte[] corrupted = bytes(DETAIL_FIXTURE).clone();
            corrupted[PREFIX_WIDTH - 1] = (byte) 0x05;
            LoadService subject = loader();

            assertThatExceptionOfType(LoadService.MalformedParentKeyException.class)
                    .isThrownBy(() -> subject.loadDetails(new ByteArrayInputStream(corrupted)))
                    .satisfies(refused -> assertThat(refused.getRecordOrdinal())
                            .as("the corruption was written into the first record of the extract")
                            .isEqualTo(1));

            verify(LoadServiceTest.this.details, never()).insertDetailIfAbsent(any());
        }

        /**
         * The refusal is reached before any parent is looked up, so nothing is written and nothing read.
         *
         * <p>Assumptions: the ordering between the two refusals in this pass is fixed and is asserted
         * rather than assumed. An undecodable prefix yields no account, so the parent lookup has nothing
         * to look up by and cannot run first. Asserting that no presence query was issued is what pins
         * that ordering: a service that probed first would issue a query against a key it had not
         * successfully decoded.
         *
         * @throws AssertionError if a presence query or a write was issued for an undecodable record
         */
        @Test
        @DisplayName("an undecodable prefix is refused before any parent lookup is issued")
        void anUndecodablePrefixIsRefusedBeforeAnyLookup() {
            byte[] corrupted = bytes(DETAIL_FIXTURE).clone();
            corrupted[PREFIX_WIDTH - 1] = (byte) 0x05;
            LoadService subject = loader();

            assertThatExceptionOfType(LoadService.MalformedParentKeyException.class)
                    .isThrownBy(() -> subject.loadDetails(new ByteArrayInputStream(corrupted)));

            verify(LoadServiceTest.this.summaries, never()).findExistingAccountIds(anyCollection());
            verify(LoadServiceTest.this.details, never()).findExistingIds(anyCollection());
        }
    }

    /**
     * Asserts an unexpected read condition is a failure here, where the reference wrote a line and went on.
     *
     * <p><b>Purpose.</b> Divergence <strong>D-LOAD-READ-BOUNDED</strong>, registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}. At {@code cbl/PAUDBLOD.CBL}
     * <strong>L283 to L289</strong> the child file's status is sorted into two outcomes: L284 tests for
     * {@code '10'} and L285 sets {@code END-CHILD-SEG-FILE}, and every other status falls to
     * <strong>L287</strong>, {@code DISPLAY 'ERROR READING CHILD SEG INFILE'}, which sets no flag and does
     * not abend. Read against the {@code PERFORM ... UNTIL END-CHILD-SEG-FILE = 'Y'} at
     * <strong>L181</strong> that leaves both the loop flag and the file position unchanged, so control
     * returns to the top having consumed nothing. The root file carries the same shape at
     * <strong>L235</strong> under the loop at <strong>L178</strong>.
     */
    @Nested
    @DisplayName("an unexpected read condition fails the load rather than logging and continuing")
    class ExtractReadFailure {

        /**
         * A stream that cannot be read fails the load instead of being reported and stepped over.
         *
         * <p>Refactoring Rationale: the reference program's third outcome is removed rather than
         * transcribed. Its own text at L287 calls the condition an error, and what follows it is a return
         * to a loop whose exit flag it did not set -- so the program treats an unreadable file as a
         * message rather than as a failure, and the run continues as though the records it never read did
         * not exist. Raising instead is what makes the shortfall attributable at the moment it happens.
         *
         * <p>Assumptions: the failure surfaces as an {@code UncheckedIOException} rather than a checked
         * exception, because the service's entry points declare none and a caller that wanted the checked
         * form would have to have been given it by a signature this service does not have. The cause is
         * retained, so the underlying read fault is not lost -- which is what an operator needs, the
         * reference program's message having named the file and nothing about why.
         *
         * @throws AssertionError if the read fault is swallowed or reported as a completed load
         */
        @Test
        @DisplayName("a stream that raises on read fails the load and retains the cause")
        void anUnreadableStreamFailsTheLoad() {
            LoadService subject = loader();

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> subject.loadSummaries(failingStream()))
                    .withCauseInstanceOf(IOException.class);

            verify(LoadServiceTest.this.summaries, never()).insertSummaryIfAbsent(any());
        }

        /**
         * A read that fails part-way through an extract still fails rather than reporting what it managed.
         *
         * <p>Assumptions: the case reads one whole record successfully before the fault, so the load has
         * work in hand when the read breaks. That is the arrangement the reference program's own shape
         * makes hazardous: a partial read followed by a continue reports a completed load over a subset of
         * the file, and the count it returns looks like a smaller extract rather than a broken one. Failing
         * with records already accepted is therefore the property worth asserting, not failing on an empty
         * stream.
         *
         * @throws AssertionError if the partial load returns an outcome instead of failing
         */
        @Test
        @DisplayName("a read that breaks after one whole record still fails the load")
        void aPartialReadStillFailsTheLoad() {
            givenSummariesRemember();
            LoadService subject = loader();
            byte[] firstRecord = Arrays.copyOfRange(bytes(SUMMARY_FIXTURE), 0, SUMMARY_STRIDE);

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> subject.loadSummaries(failingAfter(firstRecord)))
                    .withCauseInstanceOf(IOException.class);

            assertThat(LoadServiceTest.this.storedRoots)
                    .as("the fault is raised while the first chunk is still being read, so the chunk's"
                            + " transaction is never opened and nothing reaches the store")
                    .isEmpty();
        }

        /**
         * A stream whose length is not a whole number of records is refused, naming the stride.
         *
         * <p>Assumptions: a remainder means the file was produced against a different layout or was
         * truncated in transit, and every field offset after the short record would then be wrong -- so
         * the alternative to refusing is storing plausible values in the wrong columns. This is the same
         * stance the fixtures README fixes for record length, applied to the file rather than to the row.
         *
         * <p>Assumptions: the case is bounded by a timeout because the property under test is
         * TERMINATION, and the reference shape it corrects is a loop that returns to its own top without
         * consuming anything. A service that reproduced that would hang rather than fail, and a hung build
         * is materially worse than a red one: it reports nothing and blocks the agent that is waiting.
         *
         * @throws AssertionError if the remainder is accepted, or if the refusal does not name the stride
         */
        @Test
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        @DisplayName("a trailing partial record is refused, naming the stride, and the pass terminates")
        void aTrailingPartialRecordIsRefused() {
            byte[] whole = bytes(SUMMARY_FIXTURE);
            byte[] truncated = Arrays.copyOfRange(whole, 0, whole.length - 1);
            LoadService subject = loader();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.loadSummaries(new ByteArrayInputStream(truncated)))
                    .withMessageContaining("whole number of " + SUMMARY_STRIDE + "-byte records");

            verify(LoadServiceTest.this.summaries, never()).insertSummaryIfAbsent(any());
        }
    }

    /**
     * Asserts a record already stored is counted, skipped and reported at both levels of the hierarchy.
     *
     * <p><b>Purpose.</b> The reference program tolerates the duplicate status and says so, at both levels
     * and in the same shape. On the ROOT, {@code cbl/PAUDBLOD.CBL} <strong>L256 to L258</strong> is
     * {@code IF PAUT-PCB-STATUS = 'II'} followed by {@code DISPLAY 'ROOT SEGMENT ALREADY IN DB'} and its
     * own {@code END-IF} -- no abend. On the CHILD, <strong>L329 to L331</strong> is the same three lines
     * with {@code DISPLAY 'CHILD SEGMENT ALREADY IN DB'}. Neither is a failure, and each leaves a line in
     * the job log.
     *
     * <p>Alternatives Considered: the migrated form is a conflict-tolerant insert -- the relational
     * equivalent of doing nothing on a key collision -- that LOGS the skip and continues, and two other
     * shapes were considered against it. Treating a duplicate as an error was rejected because it breaks
     * the operational property the whole load depends on: a load interrupted part-way is recovered by
     * re-running it, and re-running is exactly what presents every already-stored record a second time, so
     * an erroring loader could never finish a load it had once failed. Swallowing the conflict with no
     * record at all was rejected for the opposite reason: it satisfies every count assertion while losing
     * the one thing the reference program did emit, so an operator reading the log of a re-run could not
     * tell a run that skipped four thousand records from a run that read none. Logging keeps a re-run
     * visibly a re-run rather than silently a no-op.
     *
     * <p>Alternatives Considered: writing each record unconditionally and letting an update resolve the
     * collision. Rejected because the reference behaviour on a duplicate is to leave the stored row
     * exactly as it stands, and an update does not: a second run would replace a row an operator had since
     * amended through the online screens with the older image the extract still carries. The skip is
     * therefore reproduced rather than improved upon.
     *
     * <p>Assumptions: this tolerance is NOT a house convention across the extension tree, and the contrast
     * is worth stating so neither posture is read as the general rule. The sequential unload's two GSAM
     * inserts are strict: {@code cbl/DBUNLDGS.CBL} <strong>L311 to L315</strong> tests
     * {@code IF PASFL-PCB-STATUS NOT EQUAL TO SPACES} and reaches {@code PERFORM 9999-ABEND} at L314 with
     * no duplicate arm at all, and <strong>L330 to L334</strong> does the same on the child through
     * {@code PADFL-PCB-STATUS}. Two deliberately different postures sit in one tree, and the reason they
     * differ is the direction of the work: an insert into a sequential output file cannot legitimately
     * collide, so a status there is a fault, whereas an insert into a keyed database re-run over the same
     * extract legitimately can. The unload half of that contrast is asserted by
     * {@link AuthorizationExtractRoundTripTest}, which owns both directions of the extract circuit.
     */
    @Nested
    @DisplayName("a record already stored is counted, skipped and reported at both levels")
    class DuplicateTolerance {

        /**
         * A root already stored is counted and reported, and the pass completes.
         *
         * <p>Assumptions: the diagnostic is asserted to exist and to LOCATE the record, because that is
         * the half of the reference behaviour a count assertion cannot see. {@code DISPLAY 'ROOT SEGMENT
         * ALREADY IN DB'} at L257 named no record, and the migrated line improves on it by carrying the
         * ordinal -- but the property being defended is that a line is emitted at all, since a silent skip
         * would satisfy every count below and still lose what the reference program told an operator.
         *
         * @throws AssertionError if a re-presented root raises, is inserted again, or is skipped silently
         */
        @Test
        @DisplayName("a root already stored is counted and its skip is reported")
        void anAlreadyStoredRootIsCountedAndReported() {
            givenSummariesRemember();
            LoadService subject = loader();
            subject.loadSummaries(open(SUMMARY_FIXTURE));
            LoadServiceTest.this.captured.list.clear();

            LoadService.LoadOutcome second = subject.loadSummaries(open(SUMMARY_FIXTURE));

            assertThat(second.read()).isEqualTo(ROOT_COUNT);
            assertThat(second.alreadyPresent()).isEqualTo(ROOT_COUNT);
            assertThat(second.inserted()).isZero();
            assertThat(messagesAt(Level.INFO))
                    .as("the reference program emits a line per tolerated duplicate, and losing it would"
                            + " make a re-run indistinguishable from a run that read nothing")
                    .anySatisfy(line -> assertThat(line).contains("summary already present")
                            .contains("recordOrdinal="));
        }

        /**
         * A child already stored is counted and reported, and the pass completes.
         *
         * <p>Assumptions: the child level is asserted separately from the root level rather than being
         * taken on trust from it, because the reference program states the tolerance twice -- at L256 to
         * L258 and again at L329 to L331 -- in two paragraphs whose migrated forms are two methods with
         * their own presence handling. A service tolerant at one level and strict at the other would pass
         * a case that only checked the first, and it is the CHILD level that a re-run stresses hardest,
         * since there are twice as many children as roots in the committed extract.
         *
         * @throws AssertionError if a re-presented child raises, is inserted again, or is skipped silently
         */
        @Test
        @DisplayName("a child already stored is counted and its skip is reported")
        void anAlreadyStoredChildIsCountedAndReported() {
            givenSummariesRemember();
            givenDetailsRemember();
            LoadService subject = loader();
            subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));
            LoadServiceTest.this.captured.list.clear();

            LoadService.LoadOutcome second = subject.loadDetails(open(DETAIL_FIXTURE));

            assertThat(second.read()).isEqualTo(CHILD_COUNT);
            assertThat(second.alreadyPresent()).isEqualTo(CHILD_COUNT);
            assertThat(second.inserted()).isZero();
            assertThat(messagesAt(Level.INFO))
                    .anySatisfy(line -> assertThat(line).contains("authorization already present")
                            .contains("recordOrdinal="));
        }

        /**
         * Loading the same extract twice leaves the store exactly as loading it once did.
         *
         * <p>Assumptions: this is the operational property that makes an orchestrator retry safe, and it is
         * asserted over the WHOLE two-file load rather than over either pass alone. A step the batch
         * orchestrator re-runs after a transient fault presents the same two files again, so the claim that
         * matters is about the end state of the pair: the same rows, the same count, and nothing added.
         * The reference program has no restart contract of its own -- the only {@code RESTART=} anywhere in
         * the module's job streams is commented out -- so this is a property the migration adds, and the
         * duplicate tolerance above is what it rests on.
         *
         * @throws AssertionError if a second load changes the stored row counts
         */
        @Test
        @DisplayName("loading the same extract twice leaves the same rows as loading it once")
        void aSecondLoadLeavesTheStoreUnchanged() {
            givenSummariesRemember();
            givenDetailsRemember();
            LoadService subject = loader();

            LoadService.LoadOutcome first = subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));
            LoadService.LoadOutcome second = subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));

            assertThat(first.inserted()).isEqualTo(ROOT_COUNT + CHILD_COUNT);
            assertThat(second.inserted()).isZero();
            assertThat(second.alreadyPresent()).isEqualTo(ROOT_COUNT + CHILD_COUNT);
            assertThat(second.read()).isEqualTo(first.read());
            assertThat(LoadServiceTest.this.storedRoots).hasSize(ROOT_COUNT);
            assertThat(LoadServiceTest.this.storedChildren).hasSize(CHILD_COUNT);
        }

        /**
         * The tolerated skip commits its unit of work rather than aborting it.
         *
         * <p>Assumptions: this is the transaction-level counterpart of the count assertions above, and it
         * is what stops the duplicate tolerance being tolerant in name only. A chunk in which every record
         * was already present must still COMMIT: a service that marked such a unit of work rollback-only,
         * or let the conflict abort it, would report the right counts to its caller and leave a re-run
         * unable to make progress through a partly loaded extract. The reference program's equivalent is
         * that L256 to L258 reaches no abend, so the transaction monitor commits at program end exactly as
         * it would have on a clean load.
         *
         * @throws AssertionError if a wholly duplicate chunk is rolled back rather than committed
         */
        @Test
        @DisplayName("a chunk of nothing but duplicates commits, and is never rolled back")
        void aWhollyDuplicateChunkCommits() {
            givenSummariesRemember();
            LoadService subject = loader();
            subject.loadSummaries(open(SUMMARY_FIXTURE));

            subject.loadSummaries(open(SUMMARY_FIXTURE));

            verify(LoadServiceTest.this.transactionManager, never())
                    .rollback(LoadServiceTest.this.transactionStatus);
            verify(LoadServiceTest.this.transactionManager, times(2))
                    .commit(LoadServiceTest.this.transactionStatus);
        }
    }

    /**
     * Asserts the load is two sequential passes, and that neither pass depends on its input being sorted.
     *
     * <p><b>Purpose.</b> {@code MAIN-PARA} at {@code cbl/PAUDBLOD.CBL} <strong>L169 to L187</strong> is
     * two {@code PERFORM ... UNTIL} loops in sequence and nothing else of substance: the entry point at
     * <strong>L171</strong>, a banner at L173, initialisation at L175, then the ROOT loop at
     * <strong>L177 to L178</strong> which runs to the end of the first file, then the CHILD loop at
     * <strong>L180 to L181</strong> on the second, then the close at L183 and {@code GOBACK} at L187. Every
     * summary is stored before any authorization is attempted.
     *
     * <p>Trade-offs: two passes over two files are accepted in exchange for two properties a single
     * interleaved pass cannot offer. The first is that a child's parent is guaranteed PRESENT by the time
     * the child is read, which is what makes the parent check a precondition that can be enforced rather
     * than a race that has to be tolerated. The second is order-insensitivity: because each child carries
     * its own parent key, no relationship between the two files' orderings is required, so an extract may
     * be delivered in any order at all. A single pass that consumed the two files together would need the
     * child file ordered to follow the summary file -- and the committed fixtures deliberately violate
     * exactly that, the summary file being descending while the detail file alternates between its two
     * parents. What is given up is one extra traversal of input that is one file pair per unload of one
     * database, which is a cost paid in reading rather than in correctness.
     *
     * <p>Assumptions: {@code ENTRY 'DLITCBL' USING PAUTBPCB} at <strong>L171</strong> is the IMS batch
     * entry convention rather than a business contract, and its migrated equivalent is simply an invocable
     * method. The program is entered by the region controller, which hands it a program communication
     * block it uses for every subsequent database call; a service reached in process has no such block to
     * be handed, so {@link LoadService#load(InputStream, InputStream)} takes the two extracts and nothing
     * else. The absence of a block-shaped argument is therefore a consequence of the runtime changing and
     * not a lost parameter, and it is recorded here so that it is not read as one.
     */
    @Nested
    @DisplayName("the load is two sequential passes and neither depends on its input being sorted")
    class TwoPassOrder {

        /**
         * Every summary insert precedes every authorization insert, asserted as an interaction sequence.
         *
         * <p>Assumptions: the ordering is asserted as a SEQUENCE of interactions rather than inferred from
         * the load succeeding. A service that interleaved the two files could still succeed against this
         * fixture by luck of ordering -- and the detail file's first record names the account the summary
         * file writes SECOND, so an interleaved implementation would fail on some inputs and pass on
         * others. Verifying the sequence pins the property for any input rather than for this one.
         *
         * <p>Assumptions: the sequence is verified on the two INSERT methods rather than on the presence
         * queries, because an insert is the observable effect whose order the reference program fixes.
         * Presence queries are an implementation of chunking whose count and position may legitimately
         * change, so asserting their order would freeze a decision this class does not own.
         *
         * @throws AssertionError if any authorization is inserted before the last summary
         */
        @Test
        @DisplayName("every summary is stored before the first authorization is attempted")
        void everySummaryPrecedesEveryAuthorization() {
            givenSummariesRemember();
            givenDetailsRemember();
            LoadService subject = loader();

            LoadService.LoadOutcome outcome =
                    subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));

            assertThat(outcome.inserted()).isEqualTo(ROOT_COUNT + CHILD_COUNT);
            var sequence = inOrder(LoadServiceTest.this.summaries, LoadServiceTest.this.details);
            sequence.verify(LoadServiceTest.this.summaries, times(ROOT_COUNT))
                    .insertSummaryIfAbsent(any(PendingAuthSummary.class));
            sequence.verify(LoadServiceTest.this.details, times(CHILD_COUNT))
                    .insertDetailIfAbsent(any(PendingAuthDetail.class));
            sequence.verifyNoMoreInteractions();
        }

        /**
         * The child pass alone fails, which is what makes the ordering a requirement rather than a habit.
         *
         * <p>Assumptions: the two properties -- that the passes run in this order, and that the order is
         * NECESSARY -- are asserted separately because a service can satisfy the first by accident. Running
         * the child pass on its own against a store that holds no summaries is the direct test of
         * necessity: it must refuse, and it must refuse for the parent reason rather than for any other, so
         * the refusal type is asserted and not merely the fact of a failure.
         *
         * @throws AssertionError if the child pass succeeds without the root pass having run
         */
        @Test
        @DisplayName("the child pass on its own is refused, so the order is required and not incidental")
        void theChildPassAloneIsRefused() {
            givenSummariesRemember();
            givenDetailsRemember();
            LoadService subject = loader();

            assertThatExceptionOfType(LoadService.UnresolvedParentException.class)
                    .as("no summary has been written, so the first authorization has no parent")
                    .isThrownBy(() -> subject.loadDetails(open(DETAIL_FIXTURE)));

            assertThat(LoadServiceTest.this.storedChildren).isEmpty();
        }

        /**
         * A descending summary file loads correctly, so the root pass does not require sorted input.
         *
         * <p>Assumptions: the committed summary fixture is DESCENDING -- the higher account first, per
         * {@code src/test/resources/fixtures/README.md} L83 -- and its ascending sibling exists precisely
         * so that ordering is asserted rather than assumed. This case asserts the loader stores the two
         * rows in the order the FILE presents them, which is the property that would fail for an
         * implementation that sorted, buffered by key, or otherwise reordered what it read. A loader that
         * reordered would satisfy a count assertion and would break the byte-identical round trip its
         * sibling class asserts, so pinning the order here localises that failure to the loader.
         *
         * @throws AssertionError if the rows are stored in an order other than the file's own
         */
        @Test
        @DisplayName("a descending summary file is loaded in the order the file presents")
        void aDescendingSummaryFileLoadsInFileOrder() {
            givenSummariesRemember();
            LoadService subject = loader();

            LoadService.LoadOutcome outcome = subject.loadSummaries(open(SUMMARY_FIXTURE));

            assertThat(outcome.inserted()).isEqualTo(ROOT_COUNT);
            assertThat(LoadServiceTest.this.storedRoots)
                    .extracting(PendingAuthSummary::getAccountId)
                    .as("the fixture is deliberately not in ascending order, and the loader must not"
                            + " impose one")
                    .containsExactly(ACCOUNT_TWO, ACCOUNT_ONE);
        }

        /**
         * An interleaved detail file attributes each record by its own prefix rather than by adjacency.
         *
         * <p>Assumptions: the committed detail fixture ALTERNATES between its two parents -- account one,
         * account two, account one, account two, per {@code src/test/resources/fixtures/README.md} L197 --
         * rather than grouping each parent's children together. That is what makes this case able to fail.
         * An implementation that carried the last decoded parent forward, which is what the reference
         * program's unqualified child search argument does in effect, would attribute the second and fourth
         * records to the wrong account and would still produce four rows and a clean count.
         *
         * <p>Assumptions: the assertion is on the attributed account of each stored row IN ORDER, so a
         * mis-attribution is caught wherever it falls. Asserting the SET of accounts, or their counts,
         * would pass for an implementation that had swapped two records' parents.
         *
         * @throws AssertionError if any authorization is attributed to an account other than the one its
         *     own prefix names
         */
        @Test
        @DisplayName("an interleaved detail file attributes each record by its own six-byte prefix")
        void anInterleavedDetailFileIsAttributedByPrefix() {
            givenSummariesRemember();
            givenDetailsRemember();
            LoadService subject = loader();

            subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));

            assertThat(LoadServiceTest.this.storedChildren)
                    .extracting(child -> child.getId().getAccountId())
                    .as("parentage comes from each record's own prefix, not from the record before it")
                    .containsExactly(ACCOUNT_ONE, ACCOUNT_TWO, ACCOUNT_ONE, ACCOUNT_TWO);
        }
    }

    /**
     * Asserts the parent key is read as packed decimal, stored as a number, and linked by its own value.
     *
     * <p><b>Purpose.</b> The reference program's qualified search argument is the whole reason the parent
     * key is a binary field rather than a text one, and the cases here assert the migrated service reads it
     * that way and stores the DECODED value.
     *
     * <p>Assumptions: {@code ROOT-QUAL-SSA} at {@code cbl/PAUDBLOD.CBL} <strong>L113 to L119</strong> is
     * TWENTY-SIX bytes, and the arithmetic is worth setting out because five of its six parts are text and
     * the sixth is not. {@code QUAL-SSA-SEG-NAME} is {@code PIC X(08) VALUE 'PAUTSUM0'}, eight bytes; a
     * {@code FILLER PIC X(01)} holds {@code '('}, one byte; {@code QUAL-SSA-KEY-FIELD} is
     * {@code PIC X(08) VALUE 'ACCNTID '}, eight bytes INCLUDING a trailing blank that pads the
     * seven-character field name to the declared width; {@code QUAL-SSA-REL-OPER} is
     * {@code PIC X(02) VALUE 'EQ'}, two bytes; {@code QUAL-SSA-KEY-VALUE} is
     * {@code PIC S9(11) COMP-3}, which is SIX PACKED BYTES and not an eleven-character string; and a
     * closing {@code FILLER PIC X(01)} holds {@code ')'}, one byte. Eight plus one plus eight plus two plus
     * six plus one is twenty-six.
     *
     * <p>Assumptions: the two unqualified search arguments are NINE bytes each and carry no key at all.
     * {@code ROOT-UNQUAL-SSA} at <strong>L121 to L123</strong> is {@code 'PAUTSUM0'} and one blank, and
     * {@code CHILD-UNQUAL-SSA} at <strong>L125 to L127</strong> is {@code 'PAUTDTL1'} and one blank. The
     * second is the one used by the child insert at <strong>L325</strong>, and its emptiness is what makes
     * the preceding parent positioning a precondition rather than a lookup: an insert that names no parent
     * is placed against whatever parent the last positioning call left the database on. That is the
     * mechanism behind divergence D-C being a data-integrity hazard and not merely a missing message, and
     * it is why the case in {@code RootPositioningFailure} asserts the child row is absent rather than
     * merely that an exception was raised.
     */
    @Nested
    @DisplayName("the parent key is decoded as packed decimal and stored as a number")
    class ParentKeyDecoding {

        /**
         * The six-byte prefix is decoded to the account the codec reads from the same bytes.
         *
         * <p>Assumptions: the codec is CONSUMED here as the oracle and its internals are not re-proved.
         * {@code com.carddemo.common.codec.PackedDecimalCodec} is the shared kernel's single implementation
         * of the packed regime and its exhaustive round-trip proofs belong to that module's own tests, so
         * this case reads the expected account out of the fixture with the codec and then asks whether the
         * SERVICE attributed its row to that same value. The question here is what this context does with a
         * decoded key, not whether the decoding was right.
         *
         * <p>Assumptions: the geometry constant is checked against the codec rather than merely declared,
         * so a change to the packed width rule cannot leave this class asserting an offset the codec no
         * longer uses.
         *
         * @throws AssertionError if the stored account differs from the value the codec reads from the
         *     record's own prefix
         */
        @Test
        @DisplayName("the stored account equals the value the codec reads from the record's prefix")
        void theStoredAccountIsTheDecodedPrefix() {
            assertThat(PackedDecimalCodec.packedWidth(PREFIX_DIGITS, 0))
                    .as("eleven digits plus a sign nibble occupy six bytes, which is the prefix width this"
                            + " class positions the segment behind")
                    .isEqualTo(PREFIX_WIDTH);
            byte[] extract = bytes(DETAIL_FIXTURE);
            BigDecimal decodedFirstPrefix =
                    PackedDecimalCodec.decodePacked(extract, 0, PREFIX_DIGITS, 0, true);
            givenSummariesRemember();
            givenDetailsRemember();
            LoadService subject = loader();

            subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));

            assertThat(LoadServiceTest.this.storedChildren.get(0).getId().getAccountId())
                    .as("the service must attribute the first record to the account its own prefix"
                            + " encodes")
                    .isEqualTo(Long.valueOf(decodedFirstPrefix.longValueExact()));
        }

        /**
         * No reading of the prefix as text could have produced that account, so the packed regime matters.
         *
         * <p>Assumptions: this is the measured form of the claim rather than an assertion that two readings
         * merely differ. The first record's prefix is the six bytes {@code 10 00 00 00 00 1C}: a packed
         * field carries two digits per byte with a trailing sign nibble, so the byte holding digits one and
         * zero is {@code 0x10} and not the characters {@code '1'} and {@code '0'}. Of those six bytes
         * exactly one is even a printable character, and NONE is the encoding of the digit it stands for.
         * A text reading therefore cannot recover the value at all -- it does not produce a wrong number,
         * it produces something that is not a number -- and this case asserts that by showing the prefix
         * contains no ASCII digit while the attributed account is nonetheless correct.
         *
         * <p>Assumptions: the assertion is written against the digit CHARACTERS rather than against a
         * decoded string, because that is the mistake it exists to catch. A reader that treated the prefix
         * as text would be looking for those characters, and their total absence is what makes the mistake
         * impossible to make silently.
         *
         * @throws AssertionError if any prefix byte is an ASCII digit, or if the attributed account is
         *     wrong
         */
        @Test
        @DisplayName("the prefix holds no ASCII digit, so it cannot have been read as text")
        void thePrefixCannotHaveBeenReadAsText() {
            byte[] prefix = Arrays.copyOfRange(bytes(DETAIL_FIXTURE), 0, PREFIX_WIDTH);

            assertThat(asciiDigitsIn(prefix))
                    .as("a packed field holds two digits per byte, so none of the six bytes is the"
                            + " character encoding of the digit it carries")
                    .isEmpty();

            givenSummariesRemember();
            givenDetailsRemember();
            LoadService subject = loader();
            subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));

            assertThat(LoadServiceTest.this.storedChildren.get(0).getId().getAccountId())
                    .as("the account is nonetheless recovered exactly, which only a packed reading does")
                    .isEqualTo(ACCOUNT_ONE);
        }

        /**
         * The stored key holds a decoded number, and no packed bytes reach a column.
         *
         * <p>Assumptions: the packed form is a TRANSPORT encoding of the extract file and never a storage
         * form, so {@code account_id} is a decoded integral column. The reference program moves the six
         * bytes straight into the search argument at L277 because IMS compares the key in its stored binary
         * form; a relational column has no such requirement, and storing the bytes would make every
         * predicate a byte comparison and every reported key unreadable.
         *
         * <p>Assumptions: the assertion is made against the DECLARED return type of the key accessor and
         * not only against a value, because a value assertion cannot distinguish a numeric column from a
         * byte array that happens to compare equal to one. Reading the declared type is what fixes the
         * column shape.
         *
         * @throws AssertionError if the key's account component is not declared as an integral type
         * @throws NoSuchMethodException if the key's account accessor is absent, which fails rather than
         *     skips: an absent accessor means the assertion below was never made
         */
        @Test
        @DisplayName("the key's account component is a decoded number, never packed bytes")
        void theStoredKeyHoldsADecodedNumber() throws NoSuchMethodException {
            Method accessor = PendingAuthDetailKey.class.getMethod("getAccountId");

            assertThat(accessor.getReturnType())
                    .as("a decoded account identifier, not the six transport bytes it arrived in")
                    .isEqualTo(Long.class);
            assertThat(accessor.getReturnType().isArray())
                    .as("packed bytes are never persisted to a column")
                    .isFalse();
        }

        /**
         * Every authorization is linked to the parent its own prefix names, across the interleaved extract.
         *
         * <p>Assumptions: linkage is asserted per record against the prefix READ INDEPENDENTLY from the
         * file, rather than against a list of accounts written into this class. Writing the expected pairs
         * out by hand would let a misreading of the fixture and a matching misreading in the service agree
         * with each other; decoding each prefix here with the shared codec and comparing it to what the
         * service stored means the two derivations are independent.
         *
         * <p>Assumptions: the walk covers all four records rather than sampling one, because the fixture
         * alternates between its two parents and a single sample cannot distinguish per-record attribution
         * from an implementation that used the first prefix for everything.
         *
         * @throws AssertionError if any stored row's account differs from its own record's decoded prefix
         */
        @Test
        @DisplayName("each authorization is linked to the parent its own prefix names")
        void everyAuthorizationIsLinkedByItsOwnPrefix() {
            byte[] extract = bytes(DETAIL_FIXTURE);
            givenSummariesRemember();
            givenDetailsRemember();
            LoadService subject = loader();

            subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));

            assertThat(LoadServiceTest.this.storedChildren).hasSize(CHILD_COUNT);
            for (int record = 0; record < CHILD_COUNT; record++) {
                Long expected = Long.valueOf(PackedDecimalCodec
                        .decodePacked(extract, record * DETAIL_STRIDE, PREFIX_DIGITS, 0, true)
                        .longValueExact());
                assertThat(LoadServiceTest.this.storedChildren.get(record).getId().getAccountId())
                        .as("record %d must be attributed to the account its own prefix encodes",
                                Integer.valueOf(record + 1))
                        .isEqualTo(expected);
            }
        }
    }

    /**
     * Asserts both record lengths are enforced and that a wrong-length extract is refused.
     *
     * <p><b>Purpose.</b> The two strides are declared once each in the reference material and every field
     * offset in both layouts depends on them, so a record of the wrong length is not a record at all.
     *
     * <p>Assumptions: the house has already fixed the stance for this, so nothing here is invented.
     * {@code tests/fixtures/README.md} at <strong>lines 140 to 152</strong> states that the committed
     * readers reject any physical row whose length is not exactly the record length, that they neither pad
     * a short row nor truncate a long one, and gives the reason at lines 151 to 152: a malformed monetary
     * record must never be silently coerced into a well-formed-looking one. Applied to a file rather than a
     * row, that is what makes a length remainder a refusal instead of a partial last record.
     */
    @Nested
    @DisplayName("both record lengths are enforced and a wrong-length extract is refused")
    class RecordGeometry {

        /**
         * The declared strides are the copybook geometry, and the prefix accounts for the difference.
         *
         * <p>Assumptions: the two strides are checked against each other through the prefix width rather
         * than merely restated, because the relationship is what a mistake would break. The child record is
         * a six-byte packed parent key ahead of the two-hundred-byte segment, so the child stride less the
         * summary stride is not meaningful, but the child stride less the SEGMENT length must be exactly
         * the packed width of an eleven-digit key. Deriving it that way is what ties the file geometry to
         * {@code cpy/CIPAUDTY.cpy} and {@code cbl/PAUDBLOD.CBL} L47 rather than to a number typed here.
         *
         * <p>Assumptions: the packed ladder is asserted across the widths this pair of layouts actually
         * uses, and the seven-byte case is the one worth pinning. {@code PIC S9(10)V99 COMP-3} is twelve
         * digits and therefore SEVEN bytes, and the detail segment carries two of them at
         * {@code cpy/CIPAUDTY.cpy} L34 and L35. At six bytes each the segment would total one hundred and
         * ninety-eight rather than the declared two hundred, so the two-hundred-byte total is the
         * independent arithmetic that settles the width -- which is exactly why it is asserted here beside
         * the total rather than taken on trust.
         *
         * @throws AssertionError if a declared stride, the prefix width, or a packed width disagrees with
         *     the copybook geometry
         */
        @Test
        @DisplayName("the strides, the prefix width and the packed ladder agree with the copybooks")
        void theDeclaredGeometryAgreesWithTheCopybooks() {
            assertThat(bytes(SUMMARY_FIXTURE).length % SUMMARY_STRIDE)
                    .as("the summary extract is a whole number of hundred-byte segment images")
                    .isZero();
            assertThat(bytes(DETAIL_FIXTURE).length % DETAIL_STRIDE)
                    .as("the detail extract is a whole number of two-hundred-and-six-byte records")
                    .isZero();
            assertThat(DETAIL_STRIDE - SEGMENT_LENGTH)
                    .as("the child record is a packed parent key ahead of the two-hundred-byte segment")
                    .isEqualTo(PREFIX_WIDTH);
            assertThat(PackedDecimalCodec.packedWidth(PREFIX_DIGITS, 0)).isEqualTo(PREFIX_WIDTH);
            assertThat(PackedDecimalCodec.packedWidth(AMOUNT_INTEGER_DIGITS, AMOUNT_DECIMAL_DIGITS))
                    .as("twelve digits occupy seven bytes, and the segment carries two such amounts;"
                            + " at six each it would total 198 rather than the declared 200")
                    .isEqualTo(AMOUNT_PACKED_WIDTH);
        }

        /**
         * A summary extract carrying a partial trailing image is refused, naming its stride.
         *
         * @throws AssertionError if the remainder is accepted, or if the refusal does not name the stride
         */
        @Test
        @DisplayName("a summary extract with a partial trailing image is refused")
        void aPartialSummaryImageIsRefused() {
            byte[] whole = bytes(SUMMARY_FIXTURE);
            LoadService subject = loader();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.loadSummaries(
                            new ByteArrayInputStream(Arrays.copyOfRange(whole, 0, SUMMARY_STRIDE + 1))))
                    .withMessageContaining(String.valueOf(SUMMARY_STRIDE));

            verify(LoadServiceTest.this.summaries, never()).insertSummaryIfAbsent(any());
        }

        /**
         * A detail extract carrying a partial trailing record is refused, naming its stride.
         *
         * <p>Assumptions: the child stride is asserted separately from the root stride because the two
         * readers are separate instances over separate layouts, and a service that enforced one and not the
         * other would pass a case aimed only at the first. The strides also differ by more than a number:
         * one is a bare segment and the other a segment behind a key, so a reader that confused them would
         * be wrong about where every field begins.
         *
         * @throws AssertionError if the remainder is accepted, or if the refusal does not name the stride
         */
        @Test
        @DisplayName("a detail extract with a partial trailing record is refused")
        void aPartialDetailRecordIsRefused() {
            byte[] whole = bytes(DETAIL_FIXTURE);
            LoadService subject = loader();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.loadDetails(
                            new ByteArrayInputStream(Arrays.copyOfRange(whole, 0, DETAIL_STRIDE + 1))))
                    .withMessageContaining(String.valueOf(DETAIL_STRIDE));

            verify(LoadServiceTest.this.details, never()).insertDetailIfAbsent(any());
        }

        /**
         * Handing the child file to the summary reader is refused before a single row is written.
         *
         * <p>Assumptions: this is the mis-handed-file case, and it is the reason the reader settles the
         * whole record count before anything is decoded rather than decoding each record as it arrives. The
         * detail extract is eight hundred and twenty-four bytes and the summary stride is one hundred, so
         * the file divides into eight whole images and a twenty-four-byte remainder. Decoded as they were
         * read, those eight images would be WRITTEN first, and a child record's bytes read against the
         * summary layout fail somewhere in the middle of a packed money field -- so the run would report a
         * malformed field and send an operator to look at a value, when the actual fault is that the wrong
         * file was supplied. Resolving the length first means the mismatch is what gets reported.
         *
         * <p>Assumptions: the case asserts BOTH that the refusal names the stride and that nothing was
         * written, because either alone is satisfiable without the other. A reader that decoded eagerly and
         * then refused would still name the stride at the end, having already stored eight wrong rows.
         *
         * @throws AssertionError if the mis-handed file is partly loaded, or if the refusal does not name
         *     the stride it was read against
         */
        @Test
        @DisplayName("the child file handed to the summary reader is refused before anything is written")
        void aMisHandedFileIsRefusedBeforeAnyWrite() {
            LoadService subject = loader();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> subject.loadSummaries(open(DETAIL_FIXTURE)))
                    .withMessageContaining(String.valueOf(SUMMARY_STRIDE));

            verify(LoadServiceTest.this.summaries, never()).insertSummaryIfAbsent(any());
            assertThat(LoadServiceTest.this.storedRoots)
                    .as("eight whole images divide out of this file before the remainder is reached, and"
                            + " not one of them may be stored")
                    .isEmpty();
        }
    }

    /**
     * Asserts the service publishes no job parameter, because the reference job supplies none.
     *
     * <p><b>Purpose.</b> {@code PRM-INFO} is declared at {@code cbl/PAUDBLOD.CBL} <strong>L129 to
     * L132</strong> and beyond -- an expiry-days field, a checkpoint frequency, a checkpoint display
     * frequency and a debug flag -- and it is populated by nothing. The cases here assert the migrated
     * service publishes no equivalent, so that a control the job never had is not invented for it.
     *
     * <p>Assumptions: the parameter area is declared and unsupplied, and both halves were checked rather
     * than assumed. Of the five job streams in {@code app/app-authorization-ims-db2-mq/jcl/} only the purge
     * job {@code CBPAUP0J.jcl} carries a {@code SYSIN} at all; {@code LOADPADB.JCL} has none, so there is
     * no stream for the area to be read from. And no name inside {@code PRM-INFO} appears anywhere in the
     * program's procedure division -- the only two {@code ACCEPT} statements in it are the date and day
     * reads at L193 and L194, which feed a {@code DISPLAY} and are stored nowhere. The contrast with
     * {@code PurgeJob} is the useful one: its control card IS supplied and its parameters ARE destructive,
     * which is why that service takes a business date and this one takes none.
     *
     * <p>Assumptions: no retry policy is applied here, and the reason is recorded because its absence looks
     * like an omission next to a declared condition name.
     * {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} sits at <strong>L107</strong> and is referenced by
     * nothing in the procedure division -- as in six of the eight programs in the tree -- so there is no
     * attempt sequence to transcribe. Were one ever added it would be a faithful realisation of DECLARED
     * BUT UNIMPLEMENTED intent and not a port of working retry code, and saying otherwise would credit the
     * reference with behaviour it does not have. Two core API details are recorded because both are easy to
     * get wrong at a use site: in the Spring Framework core the annotation attribute is {@code maxRetries}
     * and NOT {@code maxAttempts}, so the total attempts are one plus that value, and the enabling
     * annotation is {@code @EnableResilientMethods} and NOT {@code @EnableRetry}.
     *
     * <p>Alternatives Considered: including every non-success condition in such a policy's condition list.
     * Rejected on the reference source's own division. Its transient set is exactly the three
     * infrastructure statuses named at L107; the statuses OUTSIDE it describe the data rather than the
     * platform -- segment-not-found, wrong-parentage and end-of-database at L100, L102 and L103 -- so
     * retrying one repeats a decided outcome, and the duplicate status at L101 is TOLERATED by the insert
     * paragraphs rather than retried, so retrying it would convert a counted skip into an attempt sequence.
     * No circuit breaker and no external resilience library is introduced either.
     */
    @Nested
    @DisplayName("the service publishes no job parameter, because the reference job supplies none")
    class ServiceParameters {

        /**
         * Every load entry point takes extracts and nothing else.
         *
         * <p>Assumptions: the assertion is made by reading the DECLARED parameter types rather than by
         * calling the methods, because the property is about the published surface. A parameter that
         * existed and was ignored would still be a control an operator could set and a reader could
         * mistake for one the reference job had.
         *
         * @throws AssertionError if any load entry point declares a parameter that is not an extract
         *     stream
         */
        @Test
        @DisplayName("the load entry points declare only extract streams as parameters")
        void theLoadEntryPointsTakeOnlyStreams() {
            List<Method> entryPoints = Arrays.stream(LoadService.class.getDeclaredMethods())
                    .filter(method -> method.getName().startsWith("load"))
                    .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                    .toList();

            assertThat(entryPoints)
                    .as("the two passes and the whole-extract load, which is the surface a caller has")
                    .hasSize(3);
            for (Method entryPoint : entryPoints) {
                assertThat(entryPoint.getParameterTypes())
                        .as("%s must take extracts only: no expiry, no checkpoint frequency and no debug"
                                + " flag, because no load job supplies any", entryPoint.getName())
                        .allMatch(InputStream.class::equals);
            }
        }

        /**
         * The only non-collaborator construction parameter is the extract ceiling.
         *
         * <p>Assumptions: the ceiling is not a job parameter in the sense this class denies. It is a
         * container-bound safety bound on a mistake -- a stream that is not an extract at all -- rather
         * than a business control the reference job had, and it is distinguished here by being asserted as
         * the one and only such parameter. Leaving it unasserted would make the claim above vague about
         * what it permits.
         *
         * @throws AssertionError if construction takes any scalar parameter besides the ceiling
         */
        @Test
        @DisplayName("construction takes the two repositories, the transaction template and a ceiling")
        void constructionTakesNoBusinessParameter() {
            List<java.lang.reflect.Constructor<?>> constructors =
                    Arrays.asList(LoadService.class.getConstructors());

            assertThat(constructors).hasSize(1);
            Class<?>[] parameters = constructors.get(0).getParameterTypes();
            assertThat(parameters).hasSize(4);
            assertThat(Arrays.stream(parameters).filter(Class::isPrimitive).toList())
                    .as("exactly one scalar, and it is the record ceiling rather than an expiry or a"
                            + " checkpoint frequency")
                    .containsExactly(int.class);
            assertThat(LoadService.MAX_RECORDS_PROPERTY)
                    .as("the ceiling's property name is published so a refusal can name what to raise")
                    .isEqualTo("carddemo.extract.max-records");
        }
    }

    /**
     * Asserts every amount the load decodes is exact fixed point, and that nothing is a binary float.
     *
     * <p><b>Purpose.</b> AAP Rule T3 (money never leaves fixed point) requires money to be
     * {@code NUMERIC(p,2)} in the database, a {@code BigDecimal} at scale two with {@code HALF_UP} in Java,
     * and a JSON STRING on the wire. This class asserts the Java and wire halves at this service's own
     * boundary, for every amount the load decodes.
     *
     * <p>Assumptions: this module is NOT covered by the shared kernel's arithmetic rule through
     * inheritance, which is why the cases exist here at all. The layering test's prohibition on
     * {@code float} and {@code double} is scoped to {@code com.carddemo.common.money..}, so nothing outside
     * that package inherits it, and the load path is where an extract's packed amounts first become Java
     * values. Asserting it here is therefore not a duplicate of an inherited rule; it is the only place the
     * rule is applied to this path.
     *
     * <p>Assumptions: the zoned sign-overpunch convention does NOT apply to either of these layouts, and
     * saying so avoids a wrong reading of the sign handling. Every numeric in the two segments is packed
     * {@code COMP-3}, two-byte binary {@code COMP}, or one plain unsigned display field -- the customer
     * identifier at {@code cpy/CIPAUSMY.cpy} <strong>L20</strong>, {@code PIC 9(09)}. There is no zoned
     * signed field in either record, so the overpunch that dominates the base masters has no bearing on
     * this path.
     *
     * <p>Assumptions: the codec is CONSUMED and its internals are not re-proved, as the package charter
     * requires. What is asserted below is what the decoded value looks like once this service has stored
     * it, which is a property of this boundary rather than of the shared kernel.
     */
    @Nested
    @DisplayName("every amount the load decodes is exact fixed point at scale two")
    class MoneyRegime {

        /**
         * Every summary amount is a {@code BigDecimal} at scale two, including the ones that are zero.
         *
         * <p>Assumptions: the zero-valued amounts are the sharp half of this case rather than filler. The
         * committed extract's lower account carries a cash balance of {@code 0.00} and a declined total of
         * {@code 0.00}, and a decoder that returned {@code BigDecimal.ZERO} for them would produce a value
         * that COMPARES equal to the right number at scale zero -- so an equality assertion would pass and
         * the column contract would still be broken. Asserting the scale is what catches it.
         *
         * @throws AssertionError if any decoded summary amount is absent, or is not at scale two
         */
        @Test
        @DisplayName("summary amounts are all at scale two, zero-valued ones included")
        void summaryAmountsAreAtScaleTwo() {
            givenSummariesRemember();
            LoadService subject = loader();

            subject.loadSummaries(open(SUMMARY_FIXTURE));

            assertThat(LoadServiceTest.this.storedRoots).hasSize(ROOT_COUNT);
            for (PendingAuthSummary root : LoadServiceTest.this.storedRoots) {
                assertScaleTwo(root.getCreditLimit(), "creditLimit");
                assertScaleTwo(root.getCashLimit(), "cashLimit");
                assertScaleTwo(root.getCreditBalance(), "creditBalance");
                assertScaleTwo(root.getCashBalance(), "cashBalance");
                assertScaleTwo(root.getApprovedAuthAmount(), "approvedAuthAmount");
                assertScaleTwo(root.getDeclinedAuthAmount(), "declinedAuthAmount");
            }
        }

        /**
         * Both authorization amounts are {@code BigDecimal} at scale two.
         *
         * <p>Assumptions: these are the two seven-byte packed fields at {@code cpy/CIPAUDTY.cpy} L34 and
         * L35, so this case also exercises the width the geometry class asserts arithmetically. A decoder
         * that read them at six bytes would take its second amount from the wrong offset and would almost
         * certainly fail to decode at all rather than returning a wrong scale, which is why the two
         * properties are asserted in two places rather than folded together.
         *
         * @throws AssertionError if either decoded authorization amount is absent, or is not at scale two
         */
        @Test
        @DisplayName("authorization amounts are at scale two")
        void authorizationAmountsAreAtScaleTwo() {
            givenSummariesRemember();
            givenDetailsRemember();
            LoadService subject = loader();

            subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));

            assertThat(LoadServiceTest.this.storedChildren).hasSize(CHILD_COUNT);
            for (PendingAuthDetail child : LoadServiceTest.this.storedChildren) {
                assertScaleTwo(child.getTransactionAmount(), "transactionAmount");
                assertScaleTwo(child.getApprovedAmount(), "approvedAmount");
            }
        }

        /**
         * A decoded amount carries its cents in its text form, which a binary float does not.
         *
         * <p>Assumptions: this is the concrete demonstration of why AAP Rule T3 (money never leaves fixed
         * point) requires the wire form to be a STRING, and it is asserted on a real decoded value rather
         * than argued. The lower account's cash balance is zero, and the two renderings of that same value
         * differ: the fixed-point text form keeps the two cent digits the column declares, while the same
         * value routed through a binary floating-point type renders with one fractional digit and no
         * relationship to the declared scale. A JSON number is parsed into exactly that binary type by most
         * clients, so transporting the number rather than the text is what loses the contract -- not at some
         * extreme magnitude, but at zero.
         *
         * <p>Assumptions: the rounding mode is asserted by identity against the shared kernel's constant
         * rather than by exercising a rounding case. Nothing in the load path rounds -- a decode is exact by
         * construction -- so there is no arithmetic here to round, and pinning the constant records which
         * mode this migration's money uses without inventing a calculation this service does not perform.
         *
         * @throws AssertionError if the decoded amount loses its declared scale in its text form, or if the
         *     kernel's rounding contract is not the one this migration states
         */
        @Test
        @DisplayName("a decoded amount keeps its cents as text, where a binary float does not")
        void aDecodedAmountKeepsItsCentsAsText() {
            givenSummariesRemember();
            LoadService subject = loader();
            subject.loadSummaries(open(SUMMARY_FIXTURE));
            BigDecimal zeroValuedBalance = LoadServiceTest.this.storedRoots.stream()
                    .filter(root -> ACCOUNT_ONE.equals(root.getAccountId()))
                    .map(PendingAuthSummary::getCashBalance)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the summary extract must carry the lower account"));

            assertThat(zeroValuedBalance).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(zeroValuedBalance.toPlainString())
                    .as("the text form keeps the two cent digits the column declares")
                    .isEqualTo("0.00");
            assertThat(Double.toString(zeroValuedBalance.doubleValue()))
                    .as("the same value through a binary floating-point type renders differently, which is"
                            + " what a JSON number would transport")
                    .isNotEqualTo(zeroValuedBalance.toPlainString());
            assertThat(Money.SCALE).isEqualTo(AMOUNT_DECIMAL_DIGITS);
            assertThat(Money.GENERAL_ROUNDING).isEqualTo(RoundingMode.HALF_UP);
        }

        /**
         * No part of the load service's published surface uses a binary floating-point type.
         *
         * <p>Assumptions: the surface is read reflectively rather than by inspection, because the property
         * has to hold for members added later and a reading of today's source cannot say anything about
         * those. Both the parameter types and the return types are examined, since either direction would
         * admit a float into the money path.
         *
         * @throws AssertionError if any published member of the service or its outcome carrier declares a
         *     binary floating-point type
         */
        @Test
        @DisplayName("neither the service nor its outcome carrier publishes a float or a double")
        void nothingPublishedIsABinaryFloat() {
            assertThat(binaryFloatsIn(LoadService.class))
                    .as("AAP Rule T3 (money never leaves fixed point) forbids these on this path, and the"
                            + " shared kernel's layering rule does not reach this package")
                    .isEmpty();
            assertThat(binaryFloatsIn(LoadService.LoadOutcome.class))
                    .as("the counts a load reports are whole records, so nothing here is fractional at all")
                    .isEmpty();
        }
    }

    /**
     * Asserts a failure part-way through a pass leaves no mixture the schema would refuse.
     *
     * <p><b>Purpose.</b> This service owns its own transaction boundary -- the repository interfaces in this
     * module declare none -- so the atomicity of a chunk is a property of this class's subject rather than
     * of its collaborators.
     */
    @Nested
    @DisplayName("a failure part-way through a pass leaves no partial parent and child mixture")
    class TransactionBoundary {

        /**
         * A failure in the child pass discards that pass's work and never touches the summaries.
         *
         * <p>Assumptions: the two passes are asserted to be separately atomic rather than jointly so. A
         * child pass that fails must discard its own unit of work, and it must NOT undo the summaries an
         * earlier pass committed -- because those summaries are exactly what makes the corrective re-run
         * succeed. Undoing them would turn a recoverable failure into a full reload, which is the opposite
         * of the property the duplicate tolerance was built for.
         *
         * <p>Assumptions: the mixture the schema would refuse is an authorization with no parent, and the
         * reverse -- a parent with no authorization -- is legitimate and is not asserted against. The
         * reference program's own hierarchy allows a summary segment with no detail children, and the
         * relational form keeps that: {@code fk_pending_auth_detail_summary} constrains the child and says
         * nothing about a childless parent.
         *
         * @throws AssertionError if a failed child pass leaves an authorization stored, or if it discards
         *     summaries an earlier pass had committed
         */
        @Test
        @DisplayName("a failed child pass discards its own work and leaves the summaries standing")
        void aFailedChildPassLeavesTheSummariesStanding() {
            givenSummariesRemember();
            LoadService subject = loader();
            subject.loadSummaries(open(SUMMARY_FIXTURE));
            assertThat(LoadServiceTest.this.storedRoots).hasSize(ROOT_COUNT);
            when(LoadServiceTest.this.details.findExistingIds(anyCollection())).thenReturn(List.of());
            when(LoadServiceTest.this.details.insertDetailIfAbsent(any(PendingAuthDetail.class)))
                    .thenThrow(new IllegalStateException("authorization store unavailable"));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> subject.loadDetails(open(DETAIL_FIXTURE)));

            assertThat(LoadServiceTest.this.storedChildren)
                    .as("no authorization may survive a failed child pass")
                    .isEmpty();
            assertThat(LoadServiceTest.this.storedRoots)
                    .as("the summaries an earlier pass committed are what make the re-run succeed, so they"
                            + " must not be discarded with the failed pass")
                    .hasSize(ROOT_COUNT);
            verify(LoadServiceTest.this.transactionManager)
                    .rollback(LoadServiceTest.this.transactionStatus);
        }

        /**
         * The whole-extract load opens a unit of work per pass rather than one spanning both.
         *
         * <p>Assumptions: the count is asserted as a floor rather than an exact figure, because the number
         * of units of work is a function of the chunk size and of how many records each file holds -- both
         * of which are implementation decisions this class does not own. What it does own is that the two
         * passes are not run inside ONE unit of work, since a single transaction spanning the whole extract
         * is the shape whose memory grows with the file. Two or more openings is the observable form of that
         * claim; asserting exactly two would freeze the chunk size.
         *
         * @throws AssertionError if the whole load runs inside a single unit of work
         */
        @Test
        @DisplayName("the whole-extract load opens at least one unit of work per pass")
        void theWholeLoadOpensAUnitOfWorkPerPass() {
            givenSummariesRemember();
            givenDetailsRemember();
            LoadService subject = loader();

            subject.load(open(SUMMARY_FIXTURE), open(DETAIL_FIXTURE));

            verify(LoadServiceTest.this.transactionManager, atLeast(2))
                    .getTransaction(any(TransactionDefinition.class));
            verify(LoadServiceTest.this.transactionManager, never())
                    .rollback(LoadServiceTest.this.transactionStatus);
        }
    }

    /**
     * Asserts one decoded amount is present and carries the declared two decimal places.
     *
     * <p>Assumptions: presence and scale are asserted together in one helper because a null and a
     * scale-zero value are the same defect from a caller's point of view -- an amount that cannot be relied
     * on -- and reporting them through one place keeps every call site below to a single line.
     *
     * @param amount the decoded amount to check; may be {@code null}, which fails
     * @param field the field's name, used to identify the failure
     */
    private static void assertScaleTwo(BigDecimal amount, String field) {
        assertThat(amount).as("%s must be decoded, not absent", field).isNotNull();
        assertThat(amount.scale())
                .as("%s is declared with two decimal places, and AAP Rule T3 (money never leaves fixed"
                        + " point) carries that scale through to the column", field)
                .isEqualTo(AMOUNT_DECIMAL_DIGITS);
    }

    /**
     * Collects the names of every published member of one type that declares a binary floating-point type.
     *
     * <p>Assumptions: only the PUBLISHED surface is examined -- public methods and constructors -- because
     * that is what a caller can reach and therefore what the prohibition is about. A private field of a
     * floating type would be a defect too, but it is not part of the contract this helper is written to
     * hold, and widening the search to non-public members would report the synthetic members a record and a
     * nested class generate.
     *
     * @param type the type whose published surface is examined; must not be {@code null}
     * @return the member names declaring {@code float} or {@code double}, in discovery order, and an empty
     *     list when none does; never {@code null}
     */
    private static List<String> binaryFloatsIn(Class<?> type) {
        List<String> offenders = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (isBinaryFloat(method.getReturnType())
                    || Arrays.stream(method.getParameterTypes()).anyMatch(
                            LoadServiceTest::isBinaryFloat)) {
                offenders.add(method.getName());
            }
        }
        for (java.lang.reflect.Constructor<?> constructor : type.getConstructors()) {
            if (Arrays.stream(constructor.getParameterTypes())
                    .anyMatch(LoadServiceTest::isBinaryFloat)) {
                offenders.add(constructor.getName());
            }
        }
        return offenders;
    }

    /**
     * Reports whether one type is a binary floating-point type in either primitive or boxed form.
     *
     * @param candidate the type to classify; must not be {@code null}
     * @return {@code true} when the type is {@code float}, {@code double} or either wrapper
     */
    private static boolean isBinaryFloat(Class<?> candidate) {
        // WHY : Assumptions: the boxed forms are included because a boxed double in a signature carries
        //       exactly the same loss of exactness as a primitive one, and it is the form that reaches a
        //       generic container or a serialiser -- which is where an amount would actually be spoiled.
        return candidate.equals(float.class) || candidate.equals(double.class)
                || candidate.equals(Float.class) || candidate.equals(Double.class);
    }

    /**
     * Collects the bytes of one span that are ASCII digit characters.
     *
     * <p>Assumptions: the MATCHING bytes are collected rather than merely counted, so a failure reports
     * which byte broke the claim instead of only that one did. A count would leave a reader of the failure
     * having to hex-dump the fixture to find out where.
     *
     * @param span the bytes to examine; must not be {@code null}
     * @return the subset of {@code span} whose values are the characters {@code '0'} through {@code '9'},
     *     in the order they occur, and an empty list when none is; never {@code null}
     */
    private static List<Byte> asciiDigitsIn(byte[] span) {
        List<Byte> digits = new ArrayList<>();
        for (byte value : span) {
            if (value >= (byte) '0' && value <= (byte) '9') {
                digits.add(Byte.valueOf(value));
            }
        }
        return digits;
    }

    /**
     * Builds a stream that reports a read fault on its first read.
     *
     * @return a stream whose every read raises an {@link IOException}; never {@code null}
     */
    private static InputStream failingStream() {
        return failingAfter(new byte[0]);
    }

    /**
     * Builds a stream that yields a stated prefix of bytes and then reports a read fault.
     *
     * <p>Assumptions: the fault is raised from {@code read(byte[], int, int)} rather than from the
     * single-byte {@code read()}, because that is the method a bulk read reaches. Overriding only the
     * single-byte form would leave the fault unreachable and the case would pass by never testing
     * anything.
     *
     * @param prefix the bytes to deliver before the fault; must not be {@code null}
     * @return a stream that delivers {@code prefix} and then raises an {@link IOException}; never
     *     {@code null}
     */
    private static InputStream failingAfter(byte[] prefix) {
        return new InputStream() {

            /** How many of the prefix bytes have been delivered so far. */
            private int delivered;

            /**
             * Delivers one prefix byte, or reports the fault once the prefix is exhausted.
             *
             * @return the next prefix byte as an unsigned value
             * @throws IOException once every prefix byte has been delivered
             */
            @Override
            public int read() throws IOException {
                if (this.delivered < prefix.length) {
                    int next = prefix[this.delivered] & 0xFF;
                    this.delivered++;
                    return next;
                }
                throw new IOException("the extract could not be read past byte " + prefix.length);
            }

            /**
             * Delivers up to the remaining prefix bytes, or reports the fault once none remain.
             *
             * @param destination the array to copy into; must not be {@code null}
             * @param offset the first index in {@code destination} to write
             * @param length the greatest number of bytes to copy
             * @return the number of prefix bytes copied, which is at least one
             * @throws IOException once every prefix byte has been delivered
             */
            @Override
            public int read(byte[] destination, int offset, int length) throws IOException {
                if (this.delivered >= prefix.length) {
                    throw new IOException("the extract could not be read past byte " + prefix.length);
                }
                int copied = Math.min(length, prefix.length - this.delivered);
                System.arraycopy(prefix, this.delivered, destination, offset, copied);
                this.delivered += copied;
                return copied;
            }
        };
    }
}
