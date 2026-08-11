package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.mapper.PendingAuthSummaryMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Asserts the rulings {@link UnloadService} carries across, one case per ruling.
 *
 * <p><b>Purpose.</b> The sibling {@code AuthorizationExtractRoundTripTest} asserts that the file format
 * survives a full circuit through {@link LoadService} and back. This class asserts the DECISIONS the
 * exporter makes, which a circuit cannot reach: that the prefixed form is the default and the sequential
 * form an explicit opt-in, that the two forms emit different child strides and can therefore never
 * silently converge, that the parent prefix is packed rather than text, that a summary carrying no
 * account identifier is passed over EXPLICITLY rather than in silence, that the walk terminates when a
 * page offers no key to resume from, and that the export writes nothing.
 *
 * <p>Assumptions: the committed extract fixtures are the source of the rows, and they are the oracle
 * rather than anything this class computes. Building entities by decoding
 * {@code unload-prefixed-summary-100.bin} and {@code unload-prefixed-detail-206.bin} means every
 * assertion below runs against the same two accounts and four authorizations the sibling class and the
 * repository integration test use, so a stride or an offset that drifted would fail in all three rather
 * than in one.
 *
 * <p>Assumptions: the repositories are doubles answering from those decoded rows, and the two walk
 * methods honour the ORDER and the LIMIT they declare -- ascending account for the outer walk, newest
 * first within one account for the inner one. The order matters because the output order is what the two
 * files agree on; the limit matters because the exporter decides a walk is finished by receiving a page
 * shorter than it asked for, so a double that ignored the limit would be defining that signal instead of
 * exercising it.
 *
 * <p>Alternatives Considered: a container-backed test against a real database. Rejected for this class
 * because none of the rulings above is a persistence property: the exporter declares no query and writes
 * no SQL, so a container would add startup cost while asserting nothing these doubles cannot. The
 * committed repository integration test covers the persistence side, and the two unload forms are
 * asserted there against real rows.
 *
 * <p>Parity honesty: no golden master exists for any path in this module, so no case here compares
 * against a recorded reference output. What is asserted is the geometry the copybooks, the database
 * descriptions and the job streams declare, and the branch structure of the two transcribed walks.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
@ExtendWith(MockitoExtension.class)
class UnloadServiceTest {

    /** The class-path directory the committed fixtures live in. */
    private static final String ROOT = "/fixtures/";

    /** The prefixed unload's summary file: two hundred-byte roots. */
    private static final String PREFIXED_SUMMARY = "unload-prefixed-summary-100.bin";

    /** The prefixed unload's child file: four two-hundred-and-six-byte prefixed records. */
    private static final String PREFIXED_DETAIL = "unload-prefixed-detail-206.bin";

    /** The number of roots the summary fixture holds. */
    private static final int ROOT_COUNT = 2;

    /** The number of children the detail fixture holds. */
    private static final int CHILD_COUNT = 4;

    /** The lower of the two accounts the fixtures carry. */
    private static final Long ACCOUNT_ONE = Long.valueOf(10_000_000_001L);

    /** A customer identifier used only to make a skipped row's report identifiable. */
    private static final Long ORPHAN_CUSTOMER = Long.valueOf(900_000_001L);

    /** How long a walk that must terminate is allowed to take before the case fails. */
    private static final int TERMINATION_TIMEOUT_SECONDS = 30;

    /** The summary repository the exporter's outer walk reads. */
    @Mock
    private PendingAuthSummaryRepository summaries;

    /** The authorization repository the exporter's inner walk reads. */
    @Mock
    private PendingAuthDetailRepository details;

    /** The two summary rows decoded from the committed fixture, in fixture order. */
    private final List<PendingAuthSummary> storedRoots = new ArrayList<>();

    /** The four authorization rows decoded from the committed fixture, in fixture order. */
    private final List<PendingAuthDetail> storedChildren = new ArrayList<>();

    /** The stream the summary images of the case under test are written to. */
    private final ByteArrayOutputStream rootFile = new ByteArrayOutputStream();

    /** The stream the authorization records of the case under test are written to. */
    private final ByteArrayOutputStream childFile = new ByteArrayOutputStream();

    /**
     * How many pages the outer walk double has served in this case.
     *
     * <p>Assumptions: it exists so the double can offer an unattributable row once rather than on every
     * page, for the reason recorded on the double itself.
     */
    private int pagesServed;

    /**
     * Decodes the committed fixtures into the rows every case walks.
     *
     * <p>Assumptions: the rows are decoded ONCE per case rather than shared across the class, because two
     * cases mutate the summary list to introduce a row with no account identifier. A shared list would
     * make those two cases order-dependent on every other one.
     */
    @BeforeEach
    void decodeTheFixtures() {
        for (byte[] record : split(bytes(PREFIXED_SUMMARY),
                PendingAuthSummaryMapper.unloadRecordLength())) {
            this.storedRoots.add(PendingAuthSummaryMapper.fromExtractRecord(record));
        }
        for (byte[] record : split(bytes(PREFIXED_DETAIL),
                PendingAuthDetailMapper.unloadRecordLength())) {
            this.storedChildren.add(PendingAuthDetailMapper.fromUnloadRecord(record));
        }
    }

    /**
     * The default export shape is the prefixed one, and naming it produces the identical file pair.
     *
     * <p>Assumptions: the published constant is asserted as WELL as the behaviour, because the two say
     * different things. The constant states which form the class calls its default; the byte comparison
     * states that the overload taking no form actually uses it. A case asserting only the second would
     * pass for a class whose constant said one thing and whose overload did another.
     */
    @Test
    @DisplayName("the default form is the prefixed form, and the no-form overload writes exactly it")
    void theDefaultFormIsThePrefixedForm() {
        givenTheWalksAnswerFromTheDecodedRows();

        assertThat(UnloadService.DEFAULT_FORM)
                .as("the round-trip form is the default, because the sequential pair is read by no job")
                .isEqualTo(UnloadService.UnloadForm.PREFIXED);

        UnloadService.UnloadOutcome byDefault =
                exporter().unload(this.rootFile, this.childFile);
        ByteArrayOutputStream namedRoots = new ByteArrayOutputStream();
        ByteArrayOutputStream namedChildren = new ByteArrayOutputStream();
        UnloadService.UnloadOutcome byName = exporter()
                .unload(UnloadService.UnloadForm.PREFIXED, namedRoots, namedChildren);

        assertThat(this.rootFile.toByteArray()).isEqualTo(namedRoots.toByteArray());
        assertThat(this.childFile.toByteArray()).isEqualTo(namedChildren.toByteArray());
        assertThat(byDefault).isEqualTo(byName);
        assertThat(byDefault.rootsWritten()).isEqualTo(ROOT_COUNT);
        assertThat(byDefault.childrenWritten()).isEqualTo(CHILD_COUNT);
        assertThat(byDefault.rootsSkipped()).isZero();
    }

    /**
     * The sequential form is reachable only by naming it, and its output differs where it must.
     *
     * <p>Assumptions: the two forms are compared on the CHILD file and asserted identical on the ROOT
     * file, because that is the whole of the difference between the two reference programs. A case that
     * only asserted the two child files differ would pass for an exporter that had also changed the root,
     * which would break the load's root reader while the child assertion still held.
     */
    @Test
    @DisplayName("the sequential form is an explicit opt-in and differs from the default only in the child")
    void theSequentialFormRequiresAnExplicitOptIn() {
        givenTheWalksAnswerFromTheDecodedRows();

        exporter().unload(this.rootFile, this.childFile);
        ByteArrayOutputStream sequentialRoots = new ByteArrayOutputStream();
        ByteArrayOutputStream sequentialChildren = new ByteArrayOutputStream();
        exporter().unload(UnloadService.UnloadForm.SEQUENTIAL, sequentialRoots, sequentialChildren);

        assertThat(sequentialRoots.toByteArray())
                .as("both reference programs move the whole hundred-byte root with no prefix")
                .isEqualTo(this.rootFile.toByteArray());
        assertThat(sequentialChildren.toByteArray())
                .as("the opt-in is observable: the default never emits the bare child shape")
                .isNotEqualTo(this.childFile.toByteArray());
    }

    /**
     * The prefixed form emits a 206-byte child and the sequential form a bare 200-byte child.
     *
     * <p>Assumptions: the two strides are asserted SEPARATELY and against the lengths the mappers
     * declare, so the two shapes cannot silently converge. Asserting only that they differ would hold for
     * two wrong strides, and asserting a literal here would make this case a second place that states the
     * geometry the layout descriptors own.
     */
    @Test
    @DisplayName("the two forms emit the same 100-byte root and child records of 206 and 200 bytes")
    void theTwoFormsEmitTheDeclaredStrides() {
        givenTheWalksAnswerFromTheDecodedRows();

        exporter().unload(UnloadService.UnloadForm.PREFIXED, this.rootFile, this.childFile);
        assertThat(this.rootFile.size())
                .isEqualTo(ROOT_COUNT * PendingAuthSummaryMapper.unloadRecordLength());
        assertThat(this.childFile.size())
                .isEqualTo(CHILD_COUNT * PendingAuthDetailMapper.unloadRecordLength());

        ByteArrayOutputStream sequentialRoots = new ByteArrayOutputStream();
        ByteArrayOutputStream sequentialChildren = new ByteArrayOutputStream();
        exporter().unload(UnloadService.UnloadForm.SEQUENTIAL, sequentialRoots, sequentialChildren);
        assertThat(sequentialRoots.size())
                .isEqualTo(ROOT_COUNT * PendingAuthSummaryMapper.unloadRecordLength());
        assertThat(sequentialChildren.size())
                .isEqualTo(CHILD_COUNT * PendingAuthDetailMapper.segmentLength());
    }

    /**
     * The six-byte parent prefix is written packed, and a text rendering of the same key differs.
     *
     * <p>Assumptions: the case states BOTH readings of the emitted bytes and asserts they disagree,
     * rather than asserting only that the packed reading works. A text prefix would not fail on write; it
     * would produce a record of the wrong length whose first six bytes a load decodes as packed and
     * attributes to some other account, so the property worth asserting is that the two encodings are
     * distinguishable at all.
     */
    @Test
    @DisplayName("the emitted parent prefix decodes as packed decimal and is not the key written as text")
    void theParentPrefixIsWrittenPackedAndNotAsText() {
        givenTheWalksAnswerFromTheDecodedRows();

        exporter().unload(this.rootFile, this.childFile);

        int stride = PendingAuthDetailMapper.unloadRecordLength();
        int prefixWidth = stride - PendingAuthDetailMapper.segmentLength();
        byte[] firstRecord = Arrays.copyOfRange(this.childFile.toByteArray(), 0, stride);
        byte[] emittedPrefix = Arrays.copyOfRange(firstRecord, 0, prefixWidth);

        assertThat(PendingAuthDetailMapper.unloadedAccountId(firstRecord))
                .as("read as packed decimal, the prefix names the account the walk was positioned on")
                .isEqualTo(ACCOUNT_ONE);
        assertThat(emittedPrefix)
                .as("the same key written as characters is a different byte sequence of the same width")
                .isNotEqualTo(Arrays.copyOfRange(
                        ACCOUNT_ONE.toString().getBytes(StandardCharsets.US_ASCII), 0, prefixWidth));
    }

    /**
     * A summary carrying no account identifier is passed over, counted, and its children left unread.
     *
     * <p>Assumptions: three things are asserted together because the reference guard does three things at
     * once. Its false branch writes no root, and because the child walk sits INSIDE the guard it also
     * reads no child -- so the case verifies the detail walk is never asked about the unattributable row.
     * The count on the outcome is the third, and it is divergence
     * <strong>D-UNLOAD-SKIP-REPORTED</strong>: the reference programs report nothing, and a caller
     * here can see the shortfall in the result it already holds.
     */
    @Test
    @DisplayName("D-UNLOAD-SKIP-REPORTED: a summary with no account key is skipped, counted, children unread")
    void aSummaryWithNoAccountIdentifierIsSkippedAndCounted() {
        this.storedRoots.add(0, summaryWithNoAccountIdentifier());
        givenTheWalksAnswerFromTheDecodedRows();

        UnloadService.UnloadOutcome outcome = exporter().unload(this.rootFile, this.childFile);

        assertThat(outcome.rootsSkipped())
                .as("the skip is reported on the result, which the reference programs do not do")
                .isEqualTo(1);
        assertThat(outcome.rootsWritten())
                .as("every root that does carry a key is still exported")
                .isEqualTo(ROOT_COUNT);
        assertThat(this.rootFile.size())
                .isEqualTo(ROOT_COUNT * PendingAuthSummaryMapper.unloadRecordLength());
        verify(this.details, never()).findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(null);
        verify(this.details, times(ROOT_COUNT))
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any());
    }

    /**
     * A page offering no key to resume from ends the walk instead of being requested for ever.
     *
     * <p>Assumptions: the double answers the SAME page to every call, which is exactly what a real
     * repository would do if the walk could not advance its position -- the page predicate is strictly
     * greater than that position, so an unchanged position re-selects an unchanged page. Without the
     * guard under test this case does not fail, it does not finish, which is why it carries a timeout.
     */
    @Test
    @Timeout(value = TERMINATION_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    @DisplayName("a page carrying no resumable account identifier ends the walk rather than repeating")
    void aPageWithNoResumableKeyEndsTheWalk() {
        List<PendingAuthSummary> unattributable = List.of(summaryWithNoAccountIdentifier());
        when(this.summaries.findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any()))
                .thenReturn(unattributable);

        UnloadService.UnloadOutcome outcome = exporter().unload(this.rootFile, this.childFile);

        assertThat(outcome).isEqualTo(new UnloadService.UnloadOutcome(0, 0, 1));
        assertThat(this.rootFile.size()).isZero();
        assertThat(this.childFile.size()).isZero();
    }

    /**
     * The export declares a read-only unit of work and touches no operation that writes.
     *
     * <p>Assumptions: the posture is asserted from two directions because either alone is incomplete. The
     * annotation is what makes the provider skip dirty checking and what a reviewer reads, so it is
     * asserted on BOTH public entry points; the interaction check is what proves no repository operation
     * that stores or removes a row was reached, which an annotation cannot promise. Together they state
     * the ruling the four access-specification and job-stream readings on {@link UnloadService} support.
     */
    @Test
    @DisplayName("both entry points declare a read-only unit of work and invoke only the two walks")
    void theExportDeclaresReadOnlyAndPerformsNoWrites() {
        givenTheWalksAnswerFromTheDecodedRows();

        assertThat(readOnlyDeclarationOf(OutputStream.class, OutputStream.class)).isTrue();
        assertThat(readOnlyDeclarationOf(UnloadService.UnloadForm.class, OutputStream.class,
                OutputStream.class)).isTrue();

        exporter().unload(this.rootFile, this.childFile);

        verify(this.summaries, times(ROOT_COUNT))
                .findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any());
        verify(this.details, times(ROOT_COUNT))
                .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any());
        verifyNoMoreInteractions(this.summaries, this.details);
    }

    /**
     * Every root the walk reached is either written or counted as skipped, and never neither.
     *
     * <p>Assumptions: the identity is asserted rather than inferred from the two counts happening to
     * agree in the case above. It is the property that lets a caller detect a shortfall from the result
     * alone, so a third disposition appearing later -- a root neither written nor counted -- has to fail
     * something, and this is that something.
     */
    @Test
    @DisplayName("roots written plus roots skipped is the number of summary rows the walk reached")
    void theOutcomeAccountsForEveryRootTheWalkReached() {
        this.storedRoots.add(summaryWithNoAccountIdentifier());
        int rowsPresented = this.storedRoots.size();
        givenTheWalksAnswerFromTheDecodedRows();

        UnloadService.UnloadOutcome outcome = exporter().unload(this.rootFile, this.childFile);

        assertThat(outcome.rootsWritten() + outcome.rootsSkipped()).isEqualTo(rowsPresented);
    }

    /**
     * A write failure on either stream is raised rather than counted as a completed record.
     *
     * <p>Assumptions: this is the migrated form of the abend both reference programs reach on a bad file
     * operation, each setting return code sixteen. It is asserted because the alternative -- counting the
     * record and continuing -- would return an outcome whose counts overstated what the file holds, and a
     * caller comparing those counts against the table would then conclude the export was complete.
     *
     * <p>Assumptions: only the ROOT walk is answered here. The refusal happens on the first root record,
     * before any child is reached, so answering the child walk as well would leave a stubbing this case
     * never uses -- which the strict double correctly refuses as dead test code.
     */
    @Test
    @DisplayName("a stream that cannot be written raises instead of returning overstated counts")
    void aStreamThatCannotBeWrittenRaises() {
        givenTheRootWalkAnswers();

        assertThatExceptionOfType(UncheckedIOException.class)
                .isThrownBy(() -> exporter().unload(new RefusingStream(), this.childFile))
                .withMessageContaining("could not be written");
    }

    /**
     * The counts refuse a negative child total rather than carrying one.
     *
     * <p>Assumptions: the guard is asserted because the carrier's whole value is that its components can
     * be reconciled against the table, and a negative component would let two wrong numbers sum to a
     * plausible one. The condition is unreachable from the walk, whose child count is a list size, so the
     * assertion documents the contract for any later caller of the accumulator.
     */
    @Test
    @DisplayName("the outcome carrier refuses a negative child count")
    void theOutcomeRefusesANegativeChildCount() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> UnloadService.UnloadOutcome.NOTHING.andRoot(-1))
                .withMessageContaining("must not be negative");
    }

    /**
     * Builds the exporter over the two doubles.
     *
     * @return a new exporter reading the two repository doubles; never {@code null}
     */
    private UnloadService exporter() {
        return new UnloadService(this.summaries, this.details);
    }

    /**
     * Answers both repository walk methods from the decoded rows, in their declared orders.
     */
    private void givenTheWalksAnswerFromTheDecodedRows() {
        givenTheRootWalkAnswers();
        givenTheChildWalkAnswers();
    }

    /**
     * Answers the outer walk from the decoded summary rows, honouring its order and its limit.
     *
     * <p>Assumptions: the answer filters strictly ABOVE the position it is given and honours the
     * {@link Limit}, reproducing the contract the repository declares rather than the order the fixture
     * happens to hold. The exporter depends on both -- it resumes from the last key and decides a walk is
     * finished by receiving a short page -- so a double that ignored either would be defining the signals
     * instead of exercising them.
     *
     * <p>Assumptions: a row carrying no account identifier is offered on the FIRST page only, and that is
     * the closest a double can come to how such a row can arrive at all. A keyed predicate cannot return
     * one -- a comparison against an absent value matches no row in SQL -- so the row stands for a summary
     * reaching the exporter from a source that is not that query. Offering it on every page instead would
     * present the same row to the walk repeatedly and count one skip as several.
     */
    private void givenTheRootWalkAnswers() {
        when(this.summaries.findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any()))
                .thenAnswer(invocation -> {
                    long after = invocation.<Long>getArgument(0).longValue();
                    Limit limit = invocation.getArgument(1);
                    boolean openingPage = this.pagesServed == 0;
                    this.pagesServed++;
                    return this.storedRoots.stream()
                            .filter(root -> root.getAccountId() == null
                                    ? openingPage
                                    : root.getAccountId().longValue() > after)
                            .sorted(UnloadServiceTest::byAccountIdentifierAscending)
                            .limit(limit.max())
                            .toList();
                });
    }

    /**
     * Answers the inner walk from the decoded authorization rows of one account.
     *
     * <p>Assumptions: the answer preserves the fixture's own order within an account, which is the order
     * the repository's method name declares -- the committed detail fixture was written in that order, so
     * re-sorting here would assert this class's reading of the complement key rather than the exporter's
     * behaviour.
     */
    private void givenTheChildWalkAnswers() {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any()))
                .thenAnswer(invocation -> {
                    Long accountId = invocation.getArgument(0);
                    return this.storedChildren.stream()
                            .filter(child -> child.getId().getAccountId().equals(accountId))
                            .toList();
                });
    }

    /**
     * Orders two summary rows the way the outer walk declares, with an unattributable row first.
     *
     * <p>Assumptions: a row carrying no account identifier sorts FIRST rather than being dropped here,
     * so the exporter is the thing that decides what to do with it. Dropping it in the double would make
     * every case about the guard vacuous.
     *
     * @param left the first row to compare; must not be {@code null}
     * @param right the second row to compare; must not be {@code null}
     * @return a negative value when {@code left} sorts first, zero when the two tie, positive otherwise
     */
    private static int byAccountIdentifierAscending(PendingAuthSummary left,
            PendingAuthSummary right) {
        if (left.getAccountId() == null) {
            return right.getAccountId() == null ? 0 : -1;
        }
        if (right.getAccountId() == null) {
            return 1;
        }
        return Long.compare(left.getAccountId().longValue(), right.getAccountId().longValue());
    }

    /**
     * Builds a summary row that carries no account identifier.
     *
     * <p>Assumptions: a double is used rather than a constructed entity, because the aggregate refuses an
     * absent account identifier at construction -- the schema declares that column the whole primary key
     * and not null. The condition under test is therefore reachable only from a summary that did not come
     * from that table, which is exactly what this stands for.
     *
     * @return a summary answering an absent account identifier and a present customer identifier; never
     *     {@code null}
     */
    private static PendingAuthSummary summaryWithNoAccountIdentifier() {
        PendingAuthSummary orphan = mock(PendingAuthSummary.class);
        when(orphan.getAccountId()).thenReturn(null);
        when(orphan.getCustomerId()).thenReturn(ORPHAN_CUSTOMER);
        return orphan;
    }

    /**
     * Reports whether one {@code unload} overload declares a read-only unit of work.
     *
     * @param parameterTypes the parameter types identifying the overload; must not be {@code null}
     * @return {@code true} when that overload carries a read-only transactional declaration
     * @throws IllegalStateException if no {@code unload} overload has those parameter types, which would
     *     mean this case is asserting against a method that no longer exists
     */
    private static boolean readOnlyDeclarationOf(Class<?>... parameterTypes) {
        Method overload;
        try {
            overload = UnloadService.class.getMethod("unload", parameterTypes);
        } catch (NoSuchMethodException absent) {
            throw new IllegalStateException("UnloadService declares no unload overload taking "
                    + Arrays.toString(parameterTypes), absent);
        }
        Transactional declaration = overload.getAnnotation(Transactional.class);
        return declaration != null && declaration.readOnly();
    }

    /**
     * Divides a fixture into whole records of one stride.
     *
     * @param file the fixture bytes to divide; must not be {@code null}
     * @param stride how many bytes one record occupies; must be positive
     * @return the records in file order; never {@code null}
     */
    private static List<byte[]> split(byte[] file, int stride) {
        List<byte[]> records = new ArrayList<>(file.length / stride);
        for (int offset = 0; offset < file.length; offset += stride) {
            records.add(Arrays.copyOfRange(file, offset, offset + stride));
        }
        return records;
    }

    /**
     * Reads one committed fixture from the class path.
     *
     * @param name the fixture's file name within the fixtures directory; must not be {@code null}
     * @return the fixture's bytes; never {@code null}
     * @throws IllegalStateException if the fixture is absent from the class path
     * @throws UncheckedIOException if the fixture cannot be read
     */
    private static byte[] bytes(String name) {
        try (InputStream stream = UnloadServiceTest.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IllegalStateException("fixture " + ROOT + name + " is not on the class path");
            }
            return stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("fixture " + ROOT + name + " could not be read", unreadable);
        }
    }

    /**
     * A stream that refuses every write, standing for an output that cannot be written.
     *
     * <p>Assumptions: a refusing stream is used rather than a closed one, because a closed
     * {@code ByteArrayOutputStream} still accepts writes -- so a case built on one would assert nothing.
     */
    private static final class RefusingStream extends OutputStream {

        /**
         * Refuses one byte.
         *
         * @param oneByte the byte that will not be written
         * @throws IOException always, standing for an output that cannot be written
         */
        @Override
        public void write(int oneByte) throws IOException {
            throw new IOException("this stream refuses every write");
        }

        /**
         * Refuses a whole record.
         *
         * @param record the bytes that will not be written; never {@code null}
         * @param offset where in {@code record} the write would start
         * @param length how many bytes the write would carry
         * @throws IOException always, standing for an output that cannot be written
         */
        @Override
        public void write(byte[] record, int offset, int length) throws IOException {
            throw new IOException("this stream refuses every write");
        }
    }
}
