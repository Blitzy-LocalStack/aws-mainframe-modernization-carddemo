package com.carddemo.authorization.service;


import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.PackedDecimalCodec;
import com.carddemo.common.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Drives {@link PurgeJob} over the selection, reversal, ordering, cadence and failure behaviour it carries.
 *
 * <p>Purpose: {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} walks every summary and every
 * authorization beneath it, expires the aged ones, reverses each out of its parent's running totals, and
 * removes a summary left with nothing pending. SEVEN separable obligations follow from that, and the
 * nested groups below carry them: what selects a row ({@code SelectionIsAgeAlone}), how elapsed days are
 * measured ({@code ElapsedDaysAcrossTheYearBoundary}), what the four reversals subtract
 * ({@code SummaryReversal}), whether those reversals are persisted ({@code ReversalPersistence}), when the
 * parent is removed ({@code SummaryDeletionGuard}), what a run's parameters admit ({@code RunParameters}),
 * and how a run commits and ends ({@code CommitCadence}, {@code TerminationStatuses},
 * {@code ReadOrdering} and {@code FailureReporting} between them). The last obligation takes four groups
 * rather than one because a commit boundary, a termination status, a read order and a failure report are
 * asserted with different fixtures and different doubles, and folding them together would force one
 * fixture to serve four claims. Several of these behaviours diverge deliberately from the reference program
 * and several are preserved exactly; both kinds are asserted rather than merely documented, because a
 * divergence nothing tests is indistinguishable from a defect and a preserved asymmetry nothing tests is
 * indistinguishable from an oversight a later reader will remove.
 *
 * <p>Assumptions: three divergences are OWNED here and are registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}, which is the register of record. D-E is the
 * ordinal subtraction that is wrong across a year boundary, D-F is the delete guard that tests one counter
 * twice, and D-G is the reversal that is computed and never written back. Each is asserted by the group
 * named for it and each carries the rationale label user-specified Rule 1 (Explainability) reserves for
 * replaced code. Registering rather than repairing follows an existing house precedent rather than
 * inventing one: {@code tests/README.md} section 1.1 at lines 50 to 60 records an unfixable record-key
 * defect in two immutable baseline programs, states that the minimal-change principle forbids editing
 * them, and gives its reason as a financial-enterprise auditability requirement. No line of
 * {@code app/app-authorization-ims-db2-mq/} is edited or deleted by anything here.
 *
 * <p>Assumptions: the fixture rows are COMMITTED where a committed file carries what the case needs and
 * BUILT where none does, and the split is deliberate rather than incidental. The parent comes from
 * {@code pautsum0-purge-parent.bin} and the year-boundary children from
 * {@code pautdtl1-newyear-pair.bin}, so the counters, the accumulators and the two ordinal dates this
 * class reasons about are read from bytes under version control instead of retyped beside them. The
 * remaining children are built, because three cases need values no committed file holds: an authorization
 * a stated number of days old, one carrying the matched status, and an approved one whose transaction and
 * approved amounts differ. Note that {@code pautdtl-purge-children.bin} is NOT the file used here despite
 * its name -- it carries its key field uncomplemented, being enrolled in the raw-geometry and round-trip
 * cases which never complement, so decoding it through the entity path would refuse before any arithmetic
 * ran.
 *
 * <p>Assumptions: what is decoded here is the five members the purge's arithmetic actually reads, and
 * nothing else. {@link PackedDecimalCodec} is CONSUMED for them at this class's own service boundary; the
 * exhaustive width, offset, field-count and round-trip proofs for those same files belong to
 * {@code com.carddemo.authorization.fixtures.PendingAuthDetailNewYearFixtureTest} and its siblings.
 * Re-proving them here would leave two suites asserting one contract, so a change to the contract would
 * fail twice and a reader could not tell which assertion was the authority.
 *
 * <p>Assumptions: the transaction boundary is a REAL {@link TransactionTemplate} over a stubbed manager
 * rather than a mocked template. The number of boundaries opened is the observable form of the commit
 * cadence, so it has to be counted; mocking the template would count calls to a method whose contract the
 * test itself would then be defining.
 *
 * <p>Assumptions: parity here rests on the copybook and schema contracts plus the transcribed logic and NOT
 * on a golden master, because no golden master exists for any path in this module. The reference harness
 * compiles from {@code app/cbl/} alone and covers ten of the twelve batch programs there, so the extension
 * trees are never built and this program is never once compiled by it. This is stated so that the absence
 * is not mistaken for one that was overlooked, and so that no case here is read as a comparison against a
 * recorded run.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
@ExtendWith(MockitoExtension.class)
class PurgeJobTest {

    /** The classpath directory holding the committed segment images. */
    private static final String FIXTURE_ROOT = "/fixtures/";

    /** The committed summary the counters and accumulators of every case here are read from. */
    private static final String PARENT_FIXTURE = "pautsum0-purge-parent.bin";

    /** The committed pair of authorizations that straddle the 2023 to 2024 year boundary. */
    private static final String NEWYEAR_PAIR_FIXTURE = "pautdtl1-newyear-pair.bin";

    /** Length of one {@code PAUTSUM0} image, whose field widths close at exactly this figure. */
    private static final int SUMMARY_RECORD_LENGTH = 100;

    /** Length of one {@code PAUTDTL1} image, whose field widths close at exactly this figure. */
    private static final int DETAIL_RECORD_LENGTH = 200;

    /** Ordinal position of the year-end authorization within the committed pair. */
    private static final int YEAR_END_RECORD = 0;

    /** Ordinal position of the new-year authorization within the committed pair. */
    private static final int NEW_YEAR_RECORD = 1;

    /** The base the reference complements an ordinal date against, from L280. */
    private static final int DATE_COMPLEMENT_BASE = 99_999;

    /** The base the writing program complements a millisecond time against. */
    private static final int TIME_COMPLEMENT_BASE = 999_999_999;

    /** The response code that marks an authorization approved, from {@code cpy/CIPAUDTY.cpy} L31. */
    private static final String APPROVED = "00";

    /** A response code that is not the approved one, so the declined arm is taken. */
    private static final String DECLINED = "05";

    /** An exact zero at the scale every money column stores. */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** Ordinal 24095, the built children's authorization date: day 95 of 2024. */
    private static final int AUTH_DATE = 24_095;

    /** The calendar date {@link #AUTH_DATE} denotes. */
    private static final LocalDate AUTHORIZED_ON = LocalDate.ofYearDay(2024, 95);

    /**
     * How much the reference adds to its declared checkpoint frequency in practice.
     *
     * <p>Assumptions: one, and it is a DERIVED figure rather than a configured one.
     * {@code cbl/CBPAUP0C.cbl} L232 increments {@code WS-AUTH-SMRY-PROC-CNT} as each summary is read and
     * L160 then tests it with a strict {@code >} against {@code P-CHKP-FREQ}, so the count has to exceed
     * the frequency before L161 takes a checkpoint. The same shape appears a second time in this tree, in
     * the consumer's request window, where {@code RequestWindowBoundary} records it and
     * {@code AuthorizationRequestListenerTest} asserts it as 500 declared against 501 admitted.</p>
     */
    private static final int REFERENCE_POST_INCREMENT_OFFSET = 1;

    /** The summary repository the outer walk reads and the parent delete writes. */
    @Mock
    private PendingAuthSummaryRepository summaries;

    /** The authorization repository the inner walk reads and the child delete writes. */
    @Mock
    private PendingAuthDetailRepository details;

    /** The transaction manager whose boundaries stand in for the reference checkpoints. */
    @Mock
    private PlatformTransactionManager transactionManager;

    /**
     * The status every boundary in a case is opened with, held so that commits can be counted.
     *
     * <p>Assumptions: ONE instance serves every boundary a case opens, which is what lets a verification
     * name the argument {@code commit} and {@code rollback} are called with. A fresh status per boundary
     * would be indistinguishable from any other status at the verification, so a case could not assert
     * that the boundary it opened is the boundary that was committed. The manager is a double and holds
     * no state behind the status, so reusing it cannot leak one boundary's outcome into another's.</p>
     */
    private final SimpleTransactionStatus transactionStatus = new SimpleTransactionStatus();

    /**
     * Builds the job with a real transaction template over the stubbed manager.
     *
     * @return the job under test, never {@code null}
     */
    private PurgeJob job() {
        when(this.transactionManager.getTransaction(any())).thenReturn(this.transactionStatus);
        return new PurgeJob(this.summaries, this.details,
                new TransactionTemplate(this.transactionManager));
    }

    /**
     * Answers the outer walk from a fixed set of summaries, honouring the position and the page limit.
     *
     * <p>Assumptions: the LIMIT is honoured, not ignored, and that is load-bearing for the cadence cases.
     * The service decides a walk is finished by receiving a page shorter than it asked for, so a double
     * that returned every matching row regardless of the limit would answer the first window with
     * everything and make one window look like the whole walk.
     *
     * @param stored the summaries the walk can see; must not be {@code null}
     */
    private void givenSummaries(List<PendingAuthSummary> stored) {
        when(this.summaries.findAccountIdsAboveOrderByAccountIdAsc(any(), any()))
                .thenAnswer(invocation -> {
                    long after = invocation.<Long>getArgument(0).longValue();
                    Limit limit = invocation.getArgument(1);
                    return stored.stream()
                            .map(PendingAuthSummary::getAccountId)
                            .filter(accountId -> accountId.longValue() > after)
                            .sorted()
                            .limit(limit.max())
                            .toList();
                });
        // WHY : Assumptions: the walk answers KEYS and the second read answers the summary, because that
        //       is the order the service performs them in and the double has to be able to disagree with
        //       itself. A single stub answering summaries from the walk could not express the case below
        //       where a key is returned and its row is then gone, which is the state a concurrent purge
        //       leaves behind and the one arm of this loop that has no reference equivalent to copy.
        when(this.summaries.findByAccountId(any()))
                .thenAnswer(invocation -> stored.stream()
                        .filter(summary -> summary.getAccountId().equals(invocation.getArgument(0)))
                        .findFirst());
    }

    /**
     * Answers the inner walk with a fixed set of authorizations beneath any account.
     *
     * @param children the authorizations the inner walk returns; must not be {@code null}
     */
    private void givenChildren(List<PendingAuthDetail> children) {
        // WHY : Refactoring Rationale: the double answers the BOUNDED first chunk and the strict keyset
        //       continuation, and the unbounded read it answered before is gone. The purge now walks one
        //       account's authorizations in keyset chunks instead of materialising every one of them, so
        //       a double that returned the whole set to any call would let a purge which had gone back
        //       to reading everything still pass -- and unboundedness there is exactly the defect.
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any(), any()))
                .thenAnswer(invocation -> chunk(children, null, null, invocation.getArgument(1)));
        // WHY : Assumptions: the continuation stub is LENIENT because a fixture smaller than one chunk is
        //       answered entirely by the first call, which comes back short and ends the walk. It is still
        //       declared so the cases whose fixtures do span a chunk are answered correctly, and so a
        //       reader can see the double models the whole walk rather than only its first step.
        Mockito.lenient().when(this.details.findOlderThan(any(), any(), any(), any()))
                .thenAnswer(invocation -> chunk(children, invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3)));
    }

    /**
     * Answers the inner walk for one named account only, leaving every other account childless.
     *
     * <p>Assumptions: this exists because {@link #givenChildren} answers the same set beneath EVERY
     * account, which cannot express a walk in which one summary has children and another has none. That
     * state is what separates the child chain's own exhaustion from the end of the run, so it needs a
     * double able to distinguish two accounts.</p>
     *
     * @param accountId the only account whose chain is non-empty; must not be {@code null}
     * @param children the authorizations beneath that account; must not be {@code null}
     */
    private void givenChildrenOf(Long accountId, List<PendingAuthDetail> children) {
        when(this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any(), any()))
                .thenAnswer(invocation -> accountId.equals(invocation.getArgument(0))
                        ? chunk(children, null, null, invocation.getArgument(1))
                        : List.of());
        Mockito.lenient().when(this.details.findOlderThan(any(), any(), any(), any()))
                .thenAnswer(invocation -> accountId.equals(invocation.getArgument(0))
                        ? chunk(children, invocation.getArgument(1), invocation.getArgument(2),
                                invocation.getArgument(3))
                        : List.of());
    }

    /**
     * Returns the bounded slice of a fixed child set that follows a stated keyset position.
     *
     * <p>Assumptions: the comparison is the STRICT descending pair the production query uses -- an earlier
     * date, or the same date and an earlier time -- so the double reproduces the boundary the real query
     * has rather than a looser one that would hide an off-by-one in the caller's position handling.</p>
     *
     * @param children the whole child set, already in the order the query returns
     * @param afterDate the authorization date of the last row handled, or {@code null} for the first chunk
     * @param afterTime the authorization time of the last row handled, or {@code null} for the first chunk
     * @param limit the greatest number of rows to return
     * @return the slice, never {@code null}
     */
    private static List<PendingAuthDetail> chunk(List<PendingAuthDetail> children, Integer afterDate,
            Integer afterTime, Limit limit) {
        return children.stream()
                .filter(child -> afterDate == null
                        || child.getId().getAuthDate() < afterDate
                        || (child.getId().getAuthDate().equals(afterDate)
                                && child.getId().getAuthTime() < afterTime))
                .limit(limit.max())
                .toList();
    }

    /**
     * Reads one committed fixture from the test classpath as raw bytes.
     *
     * <p>Assumptions: the resource is read in BINARY and never through a reader. Both files hold packed
     * decimal, so every byte range this class decodes contains values outside the printable range and a
     * character decode would substitute replacement characters for them -- silently, and only in the
     * fields the arithmetic depends on.</p>
     *
     * @param name the file name inside {@link #FIXTURE_ROOT}; must not be {@code null}
     * @return the whole file, never {@code null}
     * @throws UncheckedIOException if the resource is absent or unreadable, which fails the case rather
     *     than skipping it, because a fixture this class reasons about cannot be optional
     */
    private static byte[] fixtureBytes(String name) {
        try (InputStream source = PurgeJobTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            if (source == null) {
                throw new UncheckedIOException(new IOException(
                        "the committed fixture " + FIXTURE_ROOT + name + " is not on the test classpath"));
            }
            return source.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + FIXTURE_ROOT + name, unreadable);
        }
    }

    /**
     * Rebuilds the committed parent summary, optionally under a different account identifier.
     *
     * <p>Assumptions: the layout is {@code app/app-authorization-ims-db2-mq/cpy/CIPAUSMY.cpy} L19 to L31,
     * whose field widths close at exactly {@link #SUMMARY_RECORD_LENGTH}: an eleven-digit packed account
     * identifier at offset 0 over six bytes, a nine-digit DISPLAY customer identifier at 6 over nine, a
     * one-character authorization status at 15, five two-character account statuses at 16 over ten, four
     * eleven-digit packed amounts at 26, 32, 38 and 44 over six each, the two counters
     * {@code PA-APPROVED-AUTH-CNT} and {@code PA-DECLINED-AUTH-CNT} at 50 and 52 as two-byte binary from
     * L27 and L28, the two packed accumulators at 54 and 60 over six each from L29 and L30, and a
     * thirty-four byte {@code FILLER} at 66. The widths follow the packed ladder, so eleven digits occupy
     * six bytes and a four-digit binary counter occupies two.</p>
     *
     * <p>Assumptions: the customer identifier is the one member of this record read as CHARACTERS.
     * {@code PA-CUST-ID} is {@code PIC 9(09)} with no usage clause, so it is nine ASCII digit bytes rather
     * than a packed or binary field, and decoding it as either would read the wrong width and shift every
     * later offset.</p>
     *
     * <p>Trade-offs: the account identifier is taken from the ARGUMENT and not from the file, while every
     * other member is taken from the file. Several cases need a walk that crosses more than one summary,
     * and one committed image holds one account; copying the file for each would multiply the bytes under
     * version control to vary the one member that carries no arithmetic. The identifier the file itself
     * holds is asserted separately, so the substitution cannot hide a change to it.</p>
     *
     * @param accountId the account the rebuilt summary is to belong to; must not be {@code null}
     * @return the committed summary under the stated account, never {@code null}
     */
    private static PendingAuthSummary committedParent(Long accountId) {
        byte[] image = fixtureBytes(PARENT_FIXTURE);
        return PendingAuthSummary.rehydrated(accountId,
                Long.valueOf(Long.parseLong(new String(image, 6, 9, StandardCharsets.US_ASCII))),
                new String(image, 15, 1, StandardCharsets.US_ASCII),
                new String(image, 16, 2, StandardCharsets.US_ASCII),
                new String(image, 18, 2, StandardCharsets.US_ASCII),
                new String(image, 20, 2, StandardCharsets.US_ASCII),
                new String(image, 22, 2, StandardCharsets.US_ASCII),
                new String(image, 24, 2, StandardCharsets.US_ASCII),
                PackedDecimalCodec.decodePacked(image, 26, 9, 2, true),
                PackedDecimalCodec.decodePacked(image, 32, 9, 2, true),
                PackedDecimalCodec.decodePacked(image, 38, 9, 2, true),
                PackedDecimalCodec.decodePacked(image, 44, 9, 2, true),
                Short.valueOf(PackedDecimalCodec.decodeBinary(image, 50, 4, 0, true).shortValueExact()),
                Short.valueOf(PackedDecimalCodec.decodeBinary(image, 52, 4, 0, true).shortValueExact()),
                PackedDecimalCodec.decodePacked(image, 54, 9, 2, true),
                PackedDecimalCodec.decodePacked(image, 60, 9, 2, true));
    }

    /**
     * Returns the account identifier the committed parent image itself holds.
     *
     * @return the eleven-digit packed identifier at offset 0 of {@link #PARENT_FIXTURE}, never
     *     {@code null}
     */
    private static Long committedParentAccountId() {
        return Long.valueOf(PackedDecimalCodec
                .decodePacked(fixtureBytes(PARENT_FIXTURE), 0, 11, 0, true).longValueExact());
    }

    /**
     * Returns the decoded ordinal authorization date of one record of the committed year-boundary pair.
     *
     * <p>Assumptions: the stored field holds the NINES COMPLEMENT and this removes it, which is
     * {@code COMPUTE WS-AUTH-DATE = 99999 - PA-AUTH-DATE-9C} at {@code cbl/CBPAUP0C.cbl} L280.
     * {@code PA-AUTH-DATE-9C} is {@code PIC S9(05) COMP-3} at {@code cpy/CIPAUDTY.cpy} L20, so it is three
     * packed bytes at offset 0 of the record, and the complement is what makes a descending index over the
     * key return the newest authorization first. A decoder that returned the stored value would invert the
     * meaning of every date this class reasons about while still satisfying any width check.</p>
     *
     * @param ordinal which record of the pair, counting from zero
     * @return the ordinal date in {@code YYDDD} form as the {@code auth_date} column holds it
     */
    private static int committedJulian(int ordinal) {
        return DATE_COMPLEMENT_BASE - PackedDecimalCodec
                .decodePacked(fixtureBytes(NEWYEAR_PAIR_FIXTURE), ordinal * DETAIL_RECORD_LENGTH,
                        5, 0, true)
                .intValueExact();
    }

    /**
     * Rebuilds one authorization of the committed year-boundary pair beneath the fixture account.
     *
     * <p>Assumptions: the five members decoded here are exactly the five the purge's arithmetic reads --
     * the two key components, the response code that selects the reversal arm, and the two amounts the two
     * arms subtract. Their offsets follow {@code cpy/CIPAUDTY.cpy}: the packed date at 0 from L20, the
     * five-byte packed time at 3 from L21, the two-character response code at 62 from L30, and the two
     * twelve-digit packed amounts at 74 and 81 from L34 and L35 over seven bytes each. The time carries
     * the same nines complement as the date, against {@link #TIME_COMPLEMENT_BASE}.</p>
     *
     * <p>Trade-offs: every remaining member is supplied as a neutral literal rather than decoded. The
     * purge reads none of them, so decoding twenty fields to feed a constructor that ignores them would
     * make this class a second, weaker copy of the fixtures suite's own per-field oracle, which asserts
     * all of them and is the authority for them. The cost accepted is that a change to, say, the merchant
     * name in that file would not be seen here, and that is correct: nothing here depends on it.</p>
     *
     * <p>Assumptions: the segment carries NO account identifier -- the reference hierarchy supplies it
     * from the parent -- so the key's account component is taken from the committed parent rather than
     * from the child image. That is the same pairing the fixture directory's own README describes, and it
     * is why these two files are documented as a pair rather than joined on a shared column.</p>
     *
     * @param ordinal which record of the pair, counting from zero
     * @return the authorization that record denotes, never {@code null}
     */
    private static PendingAuthDetail committedChild(int ordinal) {
        byte[] image = fixtureBytes(NEWYEAR_PAIR_FIXTURE);
        int base = ordinal * DETAIL_RECORD_LENGTH;
        int julian = DATE_COMPLEMENT_BASE
                - PackedDecimalCodec.decodePacked(image, base, 5, 0, true).intValueExact();
        int time = TIME_COMPLEMENT_BASE
                - PackedDecimalCodec.decodePacked(image, base + 3, 9, 0, true).intValueExact();
        return child(committedParentAccountId(), julian, time,
                new String(image, base + 62, 2, StandardCharsets.US_ASCII),
                PackedDecimalCodec.decodePacked(image, base + 74, 10, 2, true),
                PackedDecimalCodec.decodePacked(image, base + 81, 10, 2, true),
                PendingAuthDetail.MATCH_STATUS_PENDING);
    }

    /**
     * Builds one authorization beneath the committed fixture account.
     *
     * @param authDate the decoded ordinal authorization date
     * @param authTime the decoded time of day, which also makes each key distinct
     * @param respCode the response code, which selects the reversal arm
     * @param transactionAmount the amount the acquirer requested
     * @param approvedAmount the amount approved, zero on a decline
     * @return the authorization, never {@code null}
     */
    private static PendingAuthDetail child(int authDate, int authTime, String respCode,
            String transactionAmount, String approvedAmount) {
        return child(authDate, authTime, respCode, transactionAmount, approvedAmount,
                APPROVED.equals(respCode) ? PendingAuthDetail.MATCH_STATUS_PENDING
                        : PendingAuthDetail.MATCH_STATUS_DECLINED);
    }

    /**
     * Builds one authorization beneath the committed fixture account with a stated match status.
     *
     * @param authDate the decoded ordinal authorization date
     * @param authTime the decoded time of day, which also makes each key distinct
     * @param respCode the response code, which selects the reversal arm
     * @param transactionAmount the amount the acquirer requested
     * @param approvedAmount the amount approved, zero on a decline
     * @param matchStatus the match state the row carries, which must not narrow selection
     * @return the authorization, never {@code null}
     */
    private static PendingAuthDetail child(int authDate, int authTime, String respCode,
            String transactionAmount, String approvedAmount, String matchStatus) {
        return child(committedParentAccountId(), authDate, authTime, respCode,
                new BigDecimal(transactionAmount), new BigDecimal(approvedAmount), matchStatus);
    }

    /**
     * Builds one authorization beneath a stated account.
     *
     * <p>Assumptions: {@code rehydrated} is the entry point used and not the public constructor, because
     * only it admits the {@code 'E'} and {@code 'M'} match states. That constructor originates a new
     * decision and so accepts the two states an insert can reach, while a row this purge reads may
     * legitimately carry either of the other two -- which is precisely what the selection case below
     * needs.</p>
     *
     * @param accountId the account the authorization hangs beneath; must not be {@code null}
     * @param authDate the decoded ordinal authorization date
     * @param authTime the decoded time of day, which also makes each key distinct
     * @param respCode the response code, which selects the reversal arm
     * @param transactionAmount the amount the acquirer requested; must not be {@code null}
     * @param approvedAmount the amount approved, an exact zero on a decline; must not be {@code null}
     * @param matchStatus the match state the row carries, which must not narrow selection
     * @return the authorization, never {@code null}
     */
    private static PendingAuthDetail child(Long accountId, int authDate, int authTime, String respCode,
            BigDecimal transactionAmount, BigDecimal approvedAmount, String matchStatus) {
        return PendingAuthDetail.rehydrated(
                new PendingAuthDetailKey(accountId, authDate, authTime),
                "240404", "091500", "4000123456789010", "0100", "2712", "0100", "0000",
                "A00100", respCode, "0000", "003000",
                transactionAmount, approvedAmount,
                "5411", "840", Short.valueOf((short) 5), "MERCH000000000001", "ACME HARDWARE",
                "SEATTLE", "WA", "98101", "TXN00000000" + authTime % 1000, matchStatus);
    }

    /**
     * Builds a run of consecutively-keyed summaries, all carrying the committed counters and accumulators.
     *
     * <p>Assumptions: the identifiers ascend from the committed one by one, which is what the outer walk
     * needs and all it needs: the walk orders by account identifier and resumes strictly above the last it
     * returned, so any strictly ascending run exercises it identically. Consecutive values are chosen over
     * arbitrary ones so that a failure message naming an identifier locates a position in the run.</p>
     *
     * @param count how many summaries the walk is to see; must not be negative
     * @return the summaries in ascending key order, never {@code null}
     */
    private static List<PendingAuthSummary> parents(int count) {
        List<PendingAuthSummary> built = new ArrayList<>(count);
        long base = committedParentAccountId().longValue();
        for (int offset = 0; offset < count; offset++) {
            built.add(committedParent(Long.valueOf(base + offset)));
        }
        return List.copyOf(built);
    }

    /**
     * Converts a five-digit ordinal date into the calendar date a run's business date has to be stated as.
     *
     * <p>Assumptions: the century window is two thousand, matching the one the service resolves ordinals
     * in. A test has to perform this conversion itself because the business date crosses the entry point as
     * a {@link LocalDate} while the stored authorization date is an ordinal integer, so there is no form of
     * the case in which both are the same type. The window is restated here rather than reached for,
     * because the production constant is private and widening it to package scope in order to be asserted
     * would change the production surface to suit a test.</p>
     *
     * @param ordinalDate a two-digit year followed by a three-digit day of year, as one integer
     * @return the calendar date that ordinal denotes, never {@code null}
     */
    private static LocalDate calendarDateOf(int ordinalDate) {
        return LocalDate.ofYearDay(2000 + ordinalDate / 1000, ordinalDate % 1000);
    }

    /**
     * Asserts what makes an authorization qualify for removal, which is its age and nothing else.
     *
     * <p>Purpose: this is {@code 4000-CHECK-IF-EXPIRED} at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L277 to L298, whose only two outcomes are
     * set at L285 and L295 by the single comparison at L284.</p>
     */
    @Nested
    @DisplayName("selection is age alone")
    class SelectionIsAgeAlone {

        /**
         * An authorization one day short of the threshold survives; one exactly at it expires.
         *
         * <p>Assumptions: the boundary is INCLUSIVE, transcribing {@code IF WS-DAY-DIFF >= WS-EXPIRY-DAYS}
         * at {@code cbl/CBPAUP0C.cbl} L284. Both sides are asserted in one case because a boundary can only
         * be wrong by one and an assertion on a single side detects neither direction of that error: a
         * check written {@code >} passes a test that only proves the younger row survives, and one written
         * against the day before passes a test that only proves the older row goes.
         */
        @Test
        @DisplayName("the expiry threshold is inclusive: exactly five days old expires, four days does not")
        void theExpiryThresholdIsInclusive() {
            givenSummaries(List.of(committedParent(committedParentAccountId())));
            givenChildren(List.of(child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00")));

            PurgeJob.PurgeOutcome fourDays = job().purge(
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(4)));
            assertThat(fourDays.detailsDeleted()).isZero();
            verify(PurgeJobTest.this.details, never()).delete(any());

            PurgeJob.PurgeOutcome fiveDays = job().purge(
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(5)));
            assertThat(fiveDays.detailsDeleted()).isEqualTo(1);
            verify(PurgeJobTest.this.details, times(1)).delete(any());
        }

        /**
         * A matched authorization is removed like any other, because age alone selects a row.
         *
         * <p>Assumptions: NOTHING narrows the reference selection, and the phrasing that says otherwise is
         * refuted by measurement rather than argued against. Documentation describing this job as purging
         * "unmatched" authorizations is wrong on two independent counts. The identifier
         * {@code MATCH-STATUS} occurs zero times in all 386 lines of {@code cbl/CBPAUP0C.cbl} and no
         * spelling of the word occurs at all, so the program cannot read the field; and its entire
         * data-language verb inventory is four verbs -- one {@code CHKP} at L355, two {@code DLET} at L310
         * and L335, one {@code GN} at L223 and one {@code GNP} at L255, with no {@code REPL} and no
         * {@code ISRT} anywhere -- so it could not write the field either. The invitation to the misreading
         * is visible in the program's own function line at L5, which describes it as deleting expired
         * PENDING authorization messages; read as a status filter that word would exclude a matched row,
         * and read as the segment's name, which is what it is, it excludes nothing.
         *
         * <p>Assumptions: this case matters in the other direction too. A sibling declaration attributes
         * the {@code 'E'} pending-expired state of {@code cpy/CIPAUDTY.cpy} L48 to this program, and a
         * reader acting on that wording would add a status predicate or a status write here. Neither
         * belongs: the program deletes the row instead of restating it, and adding a predicate would change
         * which rows a run removes while appearing to follow documentation.
         */
        @Test
        @DisplayName("a matched authorization is deleted too: selection is age alone, with no match filter")
        void aMatchedAuthorizationIsDeletedAsWell() {
            Long accountId = committedParentAccountId();
            givenSummaries(List.of(committedParent(accountId)));
            givenChildren(List.of(
                    child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00",
                            PendingAuthDetail.MATCH_STATUS_MATCHED_WITH_TRAN),
                    child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00",
                            PendingAuthDetail.MATCH_STATUS_PENDING_EXPIRED)));

            PurgeJob.PurgeOutcome outcome = job().purge(
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(5)));

            assertThat(outcome.detailsDeleted()).isEqualTo(2);
            // WHY : Assumptions: the reversal counts BOTH children, which is what this case is about -- a
            //       matched authorization expires and is reversed exactly like a pending one. The count is
            //       read off the statement because the reversal no longer touches the loaded entity.
            verify(PurgeJobTest.this.summaries, times(1)).reverseExpiredAuthorizations(eq(accountId),
                    eq(2), argThat(amount -> amount.signum() > 0), eq(0),
                    argThat(amount -> amount.signum() == 0));
        }
    }

    /**
     * Asserts divergence D-E: elapsed days come from calendar dates, not from subtracted ordinals.
     *
     * <p>Refactoring Rationale: the reference measures age by subtracting one five-digit ordinal from
     * another as plain integers. {@code cbl/CBPAUP0C.cbl} L280 removes the nines complement to recover the
     * stored date and L282 then computes {@code CURRENT-YYDDD - WS-AUTH-DATE} into the
     * {@code PIC S9(4) COMP} field its L47 declares. Within one calendar year that subtraction is a day
     * count and is perfectly correct, which is exactly why the defect survived; across a year boundary it
     * is not a day count at all, because the ordinal's low three digits restart while its high two advance.
     * The committed pair makes the size of the error concrete: ordinal 23365 is 31 December 2023 and
     * ordinal 24001 is 1 January 2024, one day apart, and subtracting them yields 636. L284's inclusive
     * comparison against the five-day default from L199 then removes an authorization a single day old. The
     * receiving field is a second limb of the same defect: the widest ordinal span, 99999 less 00001, is
     * 99998 and four signed digits cannot represent it. The target converts both ordinals into calendar
     * dates and differences those instead.
     *
     * <p>Assumptions: the two ordinals are READ from {@code pautdtl1-newyear-pair.bin} rather than written
     * here, so the arithmetic above is asserted against bytes under version control instead of against
     * numbers retyped beside the claim. Its record 1 stores the packed complement of 23365 and is approved
     * for 300.00; its record 2 stores the packed complement of 24001 and is declined for 150.00. Both hang
     * beneath {@code pautsum0-purge-parent.bin}, whose counters are two and two and whose accumulators are
     * 300.00 and 150.00 -- so expiring the pair reverses one of each arm and takes the parent to one and
     * one and to 0.00 and 0.00, which is the arithmetic the reversal group below asserts.
     */
    @Nested
    @DisplayName("divergence D-E: elapsed days across a year boundary")
    class ElapsedDaysAcrossTheYearBoundary {

        /**
         * The committed pair is one calendar day apart, and the run's own threshold proves the figure.
         *
         * <p>Refactoring Rationale: this is the case that makes divergence D-E detectable at all. An
         * implementation that kept L282's ordinal subtraction would compute 636 for this pair, and 636
         * satisfies every threshold a two-digit card field can express, so no assertion about a row being
         * deleted could distinguish the two arithmetics. What distinguishes them is the exact figure, and
         * the exact figure is pinned from BOTH sides here: at a threshold of one the year-end row is
         * removed and at a threshold of two it is not, which is only true of an elapsed count of exactly
         * one.
         *
         * <p>Assumptions: the new-year row is present in the same run and is expected to survive both
         * thresholds, because it is dated ON the business date and so has an elapsed count of zero. It is
         * kept in the fixture rather than removed from it so that the two figures the case turns on --
         * zero and one -- are separated by the run itself rather than by the fixture.
         */
        @Test
        @DisplayName("the committed pair is one calendar day apart, not the 636 an ordinal subtraction gives")
        void theCommittedPairIsOneCalendarDayApart() {
            int yearEnd = committedJulian(YEAR_END_RECORD);
            int newYear = committedJulian(NEW_YEAR_RECORD);
            assertThat(newYear - yearEnd)
                    .as("what L282 computes for this pair, from the ordinals the fixture stores")
                    .isEqualTo(636);
            assertThat(newYear - yearEnd)
                    .as("and 636 clears the L199 default, so the reference removes a one-day-old row")
                    .isGreaterThanOrEqualTo(PurgeJob.DEFAULT_EXPIRY_DAYS);

            LocalDate businessDate = calendarDateOf(newYear);
            PendingAuthDetail older = committedChild(YEAR_END_RECORD);
            PendingAuthDetail newer = committedChild(NEW_YEAR_RECORD);
            givenSummaries(List.of(committedParent(committedParentAccountId())));
            givenChildren(List.of(older, newer));

            PurgeJob.PurgeOutcome atOneDay =
                    job().purge(new PurgeJob.PurgeParameters(businessDate, 1,
                            PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY,
                            PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY));
            assertThat(atOneDay.detailsRead()).isEqualTo(2);
            assertThat(atOneDay.detailsDeleted())
                    .as("one day elapsed meets a threshold of one; nought days does not")
                    .isEqualTo(1);
            verify(PurgeJobTest.this.details).delete(older);
            verify(PurgeJobTest.this.details, never()).delete(newer);

            PurgeJob.PurgeOutcome atTwoDays =
                    job().purge(new PurgeJob.PurgeParameters(businessDate, 2,
                            PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY,
                            PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY));
            assertThat(atTwoDays.detailsDeleted())
                    .as("and one day does not meet a threshold of two, which pins the count at one")
                    .isZero();
        }

        /**
         * At the reference's own five-day default the year-boundary pair survives, where it would not have.
         *
         * <p>Refactoring Rationale: this is the same divergence stated at the threshold a run actually
         * takes when its card supplies none. The reference reaches five at L199, computes 636 at L282 and
         * qualifies the row at L284; the target computes one and keeps it. Asserting at the default as well
         * as at the pinned boundary above is what connects the arithmetic to the behaviour an unconfigured
         * run would have had.
         *
         * <p>Assumptions: the reversal statement is asserted NOT to be issued at all, rather than asserted
         * to carry zeros. Nothing expired, so there is nothing to reverse, and a statement carrying four
         * zeros would be a write against a row this run had no reason to touch.
         */
        @Test
        @DisplayName("at the five-day default the one-day-old authorization survives, not read as 636 days")
        void theYearBoundaryDoesNotExpireAOneDayOldAuthorization() {
            givenSummaries(List.of(committedParent(committedParentAccountId())));
            givenChildren(List.of(committedChild(YEAR_END_RECORD), committedChild(NEW_YEAR_RECORD)));

            PurgeJob.PurgeOutcome outcome = job().purge(PurgeJob.PurgeParameters
                    .forBusinessDate(calendarDateOf(committedJulian(NEW_YEAR_RECORD))));

            assertThat(outcome.detailsRead()).isEqualTo(2);
            assertThat(outcome.detailsDeleted()).isZero();
            verify(PurgeJobTest.this.details, never()).delete(any());
            verify(PurgeJobTest.this.summaries, never())
                    .reverseExpiredAuthorizations(any(), anyInt(), any(), anyInt(), any());
            assertThat(outcome.summariesDeleted()).isZero();
        }
    }

    /**
     * Asserts the four figures an expiring authorization reverses, and which column each arm takes.
     *
     * <p>Purpose: this is the reversal half of {@code 4000-CHECK-IF-EXPIRED} at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L287 to L293. Its L287 branches on
     * {@code IF PA-AUTH-RESP-CODE = '00'}; the approved arm subtracts one at L288 and the approved amount
     * at L289; the declined arm subtracts one at L291 and the TRANSACTION amount at L292.</p>
     *
     * <p>Assumptions: the two arms taking different columns is DESIGN and not an oversight to be tidied
     * away, and the proof is that a second, independently written program pairs the same two fields the
     * same way. {@code cbl/COPAUA0C.cbl} L813 opens the same branch, L815 adds its approved amount to the
     * approved accumulator and L821 adds {@code PA-TRANSACTION-AMT} to the declined one. Accumulating
     * through one pairing and reversing through another would leave a permanent drift in whichever
     * accumulator disagreed, so passing the approved amount to both arms would be silently wrong for every
     * declined authorization -- which is all of them, because a decline approves nothing.</p>
     *
     * <p>Assumptions: the counters are {@code PIC S9(04) COMP} at {@code cpy/CIPAUSMY.cpy} L27 and L28, so
     * they are two-byte binary and the entity carries them as {@link Short}, while the six money members of
     * that record are {@code PIC S9(09)V99 COMP-3} and are carried as {@link java.math.BigDecimal}. The
     * amounts on the child are the wider {@code PIC S9(10)V99 COMP-3} of {@code cpy/CIPAUDTY.cpy} L34 and
     * L35, so the scales agree at two while the magnitudes do not, and it is the summary's narrower type
     * that would refuse an out-of-domain result.</p>
     */
    @Nested
    @DisplayName("the reversal arithmetic")
    class SummaryReversal {

        /**
         * The committed pair reverses one of each arm and leaves the parent's remaining pair standing.
         *
         * <p>Assumptions: this is the fixture pairing stated as arithmetic a reader can verify without
         * opening either binary. {@code pautsum0-purge-parent.bin} carries counters of two and two with
         * accumulators of 300.00 and 150.00; {@code pautdtl1-newyear-pair.bin} carries one approved
         * authorization for 300.00 and one declined for 150.00. Expiring both therefore reverses a count of
         * one and an amount of 300.00 on the approved arm and a count of one and 150.00 on the declined arm,
         * taking the parent from two and two to ONE AND ONE and from 300.00 and 150.00 to 0.00 AND 0.00. One
         * pair of committed files exercises both arms, and the surviving counters are what keeps the parent
         * itself in place.
         *
         * <p>Assumptions: the starting figures are ASSERTED and not merely stated, because every expected
         * value below is derived from them. A change to those bytes would otherwise turn this case's
         * expectations into arbitrary numbers that still agreed with each other.
         */
        @Test
        @DisplayName("the committed pair reverses one approved and one declined, leaving one of each")
        void theCommittedFixturePairReversesBothArms() {
            Long accountId = committedParentAccountId();
            PendingAuthSummary parent = committedParent(accountId);
            assertThat(accountId).as("the account the committed pair belongs to").isEqualTo(10_000_000_001L);
            assertThat(parent.getApprovedAuthCount()).isEqualTo((short) 2);
            assertThat(parent.getDeclinedAuthCount()).isEqualTo((short) 2);
            assertThat(parent.getApprovedAuthAmount()).isEqualByComparingTo("300.00");
            assertThat(parent.getDeclinedAuthAmount()).isEqualByComparingTo("150.00");

            givenSummaries(List.of(parent));
            givenChildren(List.of(committedChild(YEAR_END_RECORD), committedChild(NEW_YEAR_RECORD)));

            PurgeJob.PurgeOutcome outcome = job().purge(PurgeJob.PurgeParameters.forBusinessDate(
                    calendarDateOf(committedJulian(NEW_YEAR_RECORD)).plusDays(10)));

            assertThat(outcome.detailsRead()).isEqualTo(2);
            assertThat(outcome.detailsDeleted()).isEqualTo(2);
            verify(PurgeJobTest.this.summaries, times(1)).reverseExpiredAuthorizations(eq(accountId),
                    eq(1), argThat(amount -> amount.compareTo(new BigDecimal("300.00")) == 0),
                    eq(1), argThat(amount -> amount.compareTo(new BigDecimal("150.00")) == 0));
            // WHY : Assumptions: the parent SURVIVES, and that is a consequence of the arithmetic rather
            //       than a separate rule. Two minus one leaves one on each side, and one is not at or below
            //       zero, so the guard the group below asserts does not fire. Naming it here is what stops
            //       a reader taking "both arms reversed" to mean "the summary emptied".
            assertThat(outcome.summariesDeleted()).isZero();
            verify(PurgeJobTest.this.summaries, never()).delete(any());
        }

        /**
         * The approved arm subtracts the approved amount while the declined arm subtracts the requested one.
         *
         * <p>Assumptions: the fixture is chosen so that BOTH arms are discriminating, which the committed
         * pair alone cannot achieve. Its approved record is approved in full, so its two amounts are equal
         * and an implementation reading the transaction amount on that arm would still produce the right
         * figure. Here the approved authorization is a PARTIAL approval -- 120.00 requested against 100.00
         * approved -- so taking the wrong column yields 120.00, and the declined authorization approves
         * nothing, so taking the wrong column there yields 0.00. Each arm therefore fails independently if
         * the pairing is swapped or unified.
         *
         * <p>Assumptions: a partial approval is a legitimate state rather than a contrivance. The response
         * code alone decides the arm -- {@code cpy/CIPAUDTY.cpy} L31 declares the condition for
         * {@code '00'} and nothing else -- so an approval for less than was requested is approved, and the
         * two amounts of {@code cpy/CIPAUDTY.cpy} L34 and L35 exist as separate members precisely because
         * they can differ.
         */
        @Test
        @DisplayName("the approved arm takes the approved amount, the declined arm the requested amount")
        void eachArmTakesItsOwnAmountColumn() {
            Long accountId = committedParentAccountId();
            givenSummaries(List.of(committedParent(accountId)));
            givenChildren(List.of(
                    child(accountId, AUTH_DATE, 91_500_000, APPROVED,
                            new BigDecimal("120.00"), new BigDecimal("100.00"),
                            PendingAuthDetail.MATCH_STATUS_PENDING),
                    child(accountId, AUTH_DATE, 91_500_001, DECLINED,
                            new BigDecimal("50.00"), ZERO,
                            PendingAuthDetail.MATCH_STATUS_DECLINED)));

            job().purge(PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(5)));

            verify(PurgeJobTest.this.summaries, times(1)).reverseExpiredAuthorizations(eq(accountId),
                    eq(1), argThat(amount -> amount.compareTo(new BigDecimal("100.00")) == 0),
                    eq(1), argThat(amount -> amount.compareTo(new BigDecimal("50.00")) == 0));
        }

        /**
         * The reversed amounts reach the statement as fixed-point values at the scale the columns store.
         *
         * <p>Assumptions: scale is asserted separately from value because the two are independent
         * properties of a {@link java.math.BigDecimal} and every value comparison in this class uses
         * {@code compareTo}, which ignores scale outright. An amount arriving at scale nought or scale six
         * compares equal to the expected figure and is still the wrong shape for a
         * {@code NUMERIC(11,2)} column, so the value assertions elsewhere cannot carry this one.
         *
         * <p>Assumptions: the expected scale is read from {@link Money#SCALE} rather than written as two,
         * so this case asserts the shared kernel's declared money scale and not a number copied from it.
         * Its value is asserted alongside, which is what keeps the case from passing whatever that constant
         * held. AAP Rule T3 is what requires the shape: money stays fixed point at every hop, and binary
         * floating point never appears in the path -- which is why the captured type here is
         * {@link java.math.BigDecimal} and could not be a {@code double}.
         *
         * @throws AssertionError if either captured amount is at another scale, which fails the case rather
         *     than rounding it away
         */
        @Test
        @DisplayName("the reversed amounts carry the scale every money column stores")
        void theReversedAmountsCarryTheStoredScale() {
            assertThat(Money.SCALE).as("the shared kernel's declared money scale").isEqualTo(2);
            Long accountId = committedParentAccountId();
            givenSummaries(List.of(committedParent(accountId)));
            givenChildren(List.of(committedChild(YEAR_END_RECORD), committedChild(NEW_YEAR_RECORD)));

            job().purge(PurgeJob.PurgeParameters.forBusinessDate(
                    calendarDateOf(committedJulian(NEW_YEAR_RECORD)).plusDays(10)));

            ArgumentCaptor<BigDecimal> approved = ArgumentCaptor.forClass(BigDecimal.class);
            ArgumentCaptor<BigDecimal> declined = ArgumentCaptor.forClass(BigDecimal.class);
            verify(PurgeJobTest.this.summaries).reverseExpiredAuthorizations(eq(accountId), eq(1),
                    approved.capture(), eq(1), declined.capture());
            assertThat(approved.getValue().scale()).isEqualTo(Money.SCALE);
            assertThat(declined.getValue().scale()).isEqualTo(Money.SCALE);
        }

        /**
         * Expiring every child drives all four totals to zero and releases neither balance.
         *
         * <p>Assumptions: the fixture makes the two arms distinguishable a second way, by TOTAL rather than
         * by column: each declined row carries a requested amount its approved amount does not equal, so an
         * implementation passing one amount to both arms leaves the declined total short of 150.00 rather
         * than at it.
         *
         * <p>Assumptions: neither balance moves, which is the preserved asymmetry the service records as
         * D-PURGE-BALANCE. A search for {@code BALANCE} across all 386 lines of the reference program
         * returns nothing, so the credit balance the consumer reserved at {@code cbl/COPAUA0C.cbl} L817 is
         * still standing after every authorization beneath the account has expired. Asserting the absence
         * of both balances from the statement is what keeps a later reader from "completing" the reversal by
         * adding one.
         */
        @Test
        @DisplayName("expiring every child zeroes all four totals and releases neither balance")
        void expiringEveryChildBalancesTheParentToZero() {
            Long accountId = committedParentAccountId();
            PendingAuthSummary parent = committedParent(accountId);
            assertThat(parent.getCreditBalance())
                    .as("the reserved balance the reference never releases here")
                    .isEqualByComparingTo("450.00");
            givenSummaries(List.of(parent));
            givenChildren(List.of(
                    child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00"),
                    child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00"),
                    child(AUTH_DATE, 91_500_002, DECLINED, "50.00", "0.00"),
                    child(AUTH_DATE, 91_500_003, DECLINED, "100.00", "0.00")));

            PurgeJob.PurgeOutcome outcome = job().purge(
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10)));

            assertThat(outcome.detailsRead()).isEqualTo(4);
            assertThat(outcome.detailsDeleted()).isEqualTo(4);
            // WHY : Refactoring Rationale: the reversal is asserted as ONE arithmetic statement carrying all
            //       four figures, and not as the state of the loaded entity. Mutating the entity per child
            //       made the write a read-modify-write over a row this walk holds no lock on, so two purges
            //       -- or a purge and a live authorization -- could each apply a reversal to the same
            //       starting value and lose one of them. Asserting the CALL is what holds the fix: a single
            //       statement, issued once per account, whose four arguments are the exact totals the
            //       expired children carried.
            verify(PurgeJobTest.this.summaries, times(1)).reverseExpiredAuthorizations(eq(accountId),
                    eq(2), argThat(amount -> amount.compareTo(new BigDecimal("300.00")) == 0),
                    eq(2), argThat(amount -> amount.compareTo(new BigDecimal("150.00")) == 0));
            assertThat(outcome.summariesDeleted()).isEqualTo(1);
        }
    }

    /**
     * Asserts divergence D-G: the reversal is written back, in the same unit of work as the child delete.
     *
     * <p>Refactoring Rationale: the reference computes the reversal and then discards it.
     * {@code cbl/CBPAUP0C.cbl} L287 to L293 subtracts from the working-storage copy of the summary that its
     * L223 to L226 retrieved, and the program has no verb able to write that copy back: its complete
     * data-language inventory is one {@code CHKP}, two {@code DLET}, one {@code GN} and one {@code GNP},
     * with {@code REPL} occurring zero times in all 386 lines and {@code ISRT} likewise. A summary that
     * keeps at least one child is therefore left holding counters and accumulators that still include every
     * row the run has just removed, and the error accumulates without bound because nothing ever recomputes
     * them. Note the reversal is plainly INTENDED rather than vestigial -- the delete guard at L156 reads
     * the reversed values, so the arithmetic is load-bearing and only the hierarchical storage model
     * prevents it being kept. The target issues it as a statement against the row, inside the same
     * transaction as the deletes that caused it.
     *
     * <p>Alternatives Considered: reproducing the reference faithfully, by computing the reversal locally
     * and leaving the row untouched, which would keep byte parity with the summary rows a mainframe run
     * observably leaves behind. REJECTED. It would carry a data-integrity defect forward into a system where
     * the summary is queryable over an API and rendered on a screen beside the very children it counts, and
     * where the drift is unbounded rather than self-correcting. Parity is owed to business rules, and a
     * counter that disagrees with the rows beneath it is not one.
     *
     * <p>Alternatives Considered: a periodic reconciliation job recomputing every summary's counters from
     * its surviving children. REJECTED on two grounds. It leaves a window between the purge and the
     * reconciliation in which every touched summary is wrong, and any consumer reading during that window
     * reads the defect this divergence exists to remove; and it adds a second scheduled component, its own
     * failure modes and its own operational burden to obtain what one statement inside an already-open
     * transaction obtains for nothing.
     *
     * <p>Trade-offs: the engine-tier half of this claim is NOT here and cannot be. A stubbed transaction
     * manager can record that a boundary was asked to discard its work; whether the database then keeps
     * nothing is a different claim, and it is asserted against a real engine in
     * {@link PurgeWindowRollbackRepositoryIT#aFailedWindowDiscardsEveryWriteItMade()} and
     * {@link PurgeWindowRollbackRepositoryIT#aFailedReductionKeepsEveryAuthorizationItHadDeleted()}.
     * Reading the cases below as the durability proof is the misreading this pairing exists to prevent.
     */
    @Nested
    @DisplayName("divergence D-G: the reversal is persisted")
    class ReversalPersistence {

        /**
         * The reversal reaches the row as a statement, and the loaded instance is left as it was read.
         *
         * <p>Refactoring Rationale: this is the case divergence D-G consists of. Under the reference the four
         * subtractions at {@code cbl/CBPAUP0C.cbl} L288 to L292 land in the working-storage copy its L223 to
         * L226 filled, and the program owns no verb that could write that copy back, so this account's
         * summary would end the run still counting the two authorizations just removed -- permanently, and
         * cumulatively across every run. Asserting that a statement IS issued against the row is what makes
         * the difference observable; without it an implementation that computed the reversal and dropped it
         * would satisfy every other case in this class.
         *
         * <p>Assumptions: the loaded summary's own counters are asserted UNCHANGED after the run, and that
         * absence is the positive evidence that the reversal is persisted rather than applied in memory. An
         * implementation that mutated the entity and relied on the persistence context to flush it would
         * leave the instance reading one and one; one that issues the arithmetic statement leaves it reading
         * two and two while the ROW is reduced. Only the second is safe against a concurrent writer, because
         * the subtraction is then computed by the database from whatever the row currently holds rather than
         * from a value this run read earlier.
         *
         * <p>Assumptions: the statement is issued ONCE for the account rather than once per expiring child.
         * The reference subtracts per child because it is mutating a copy it already holds; a statement per
         * child would be one round trip per row and would make the account's four figures pass through
         * intermediate states that no reference behaviour produces.
         */
        @Test
        @DisplayName("the reversal is issued as one statement against the row, not applied to the instance")
        void theReversalIsPersistedRatherThanDiscarded() {
            Long accountId = committedParentAccountId();
            PendingAuthSummary parent = committedParent(accountId);
            givenSummaries(List.of(parent));
            givenChildren(List.of(committedChild(YEAR_END_RECORD), committedChild(NEW_YEAR_RECORD)));

            job().purge(PurgeJob.PurgeParameters.forBusinessDate(
                    calendarDateOf(committedJulian(NEW_YEAR_RECORD)).plusDays(10)));

            verify(PurgeJobTest.this.summaries, times(1)).reverseExpiredAuthorizations(eq(accountId),
                    eq(1), argThat(amount -> amount.compareTo(new BigDecimal("300.00")) == 0),
                    eq(1), argThat(amount -> amount.compareTo(new BigDecimal("150.00")) == 0));
            verify(PurgeJobTest.this.summaries, never()).save(any());
            assertThat(parent.getApprovedAuthCount())
                    .as("the instance is untouched; the ROW is what the statement reduces")
                    .isEqualTo((short) 2);
            assertThat(parent.getDeclinedAuthCount()).isEqualTo((short) 2);
            assertThat(parent.getApprovedAuthAmount()).isEqualByComparingTo("300.00");
            assertThat(parent.getDeclinedAuthAmount()).isEqualByComparingTo("150.00");
        }

        /**
         * A failure after a child has been removed leaves nothing committed for that window.
         *
         * <p>Assumptions: the reversal and the delete are ONE unit of work, so a failure inside the window
         * discards both. The reference commits only at its checkpoint, so a failure between two checkpoints
         * discards everything since the last one. What THIS case establishes is narrower: the service ASKS
         * for the boundary to be discarded, the manager being told to roll back and never told to commit.
         *
         * <p>Alternatives Considered: asserting that the status is marked rollback-only. REJECTED because
         * the template discards a failed boundary by calling the MANAGER's rollback rather than by flagging
         * the status, so against a stubbed manager that assertion is vacuously false whatever the service
         * did. Asserting on an in-memory counter would be equally unsound, because a counter lives on an
         * entity a discarded transaction never writes back either way.
         */
        @Test
        @DisplayName("a failure inside a window rolls the window back rather than committing part of it")
        void aFailureInsideAWindowRollsTheWindowBack() {
            givenSummaries(List.of(committedParent(committedParentAccountId())));
            givenChildren(List.of(child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00")));
            doThrow(new IllegalStateException("child delete failed"))
                    .when(PurgeJobTest.this.details).delete(any());

            PurgeJob job = job();
            PurgeJob.PurgeParameters parameters =
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10));

            assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                    .isThrownBy(() -> job.purge(parameters));

            verify(PurgeJobTest.this.transactionManager).rollback(PurgeJobTest.this.transactionStatus);
            verify(PurgeJobTest.this.transactionManager, never())
                    .commit(PurgeJobTest.this.transactionStatus);
            verify(PurgeJobTest.this.summaries, never()).delete(any());
        }

        /**
         * A failing reversal takes the child deletes down with it, so no orphaned deletion is committed.
         *
         * <p>Assumptions: this is the OTHER direction of the same atomicity, and it needs its own case
         * because the two failures occur at different points in the window and only one of them can be
         * provoked at a time. Here the deletes have already been issued when the reduction fails, so the
         * property under test is that the boundary carrying them is discarded rather than that they were
         * never attempted -- which is why the delete is asserted to HAVE happened while the commit is
         * asserted not to.
         *
         * <p>Assumptions: the reduction is the LAST write of the account's work, after every child of it has
         * been deleted, so a reduction that failed while the deletes stood would be precisely the unbounded
         * drift divergence D-G removes -- and worse than the reference, which at least loses the deletes
         * too when its checkpoint fails.
         */
        @Test
        @DisplayName("a failing reversal discards the child deletes made in the same window")
        void aFailedReversalLeavesTheChildDeleteUncommitted() {
            Long accountId = committedParentAccountId();
            givenSummaries(List.of(committedParent(accountId)));
            PendingAuthDetail expiring = child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00");
            givenChildren(List.of(expiring));
            doThrow(new IllegalStateException("reduction failed"))
                    .when(PurgeJobTest.this.summaries)
                    .reverseExpiredAuthorizations(any(), anyInt(), any(), anyInt(), any());

            PurgeJob job = job();
            PurgeJob.PurgeParameters parameters =
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10));

            assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                    .isThrownBy(() -> job.purge(parameters))
                    .withCauseInstanceOf(IllegalStateException.class);

            verify(PurgeJobTest.this.details).delete(expiring);
            verify(PurgeJobTest.this.transactionManager).rollback(PurgeJobTest.this.transactionStatus);
            verify(PurgeJobTest.this.transactionManager, never())
                    .commit(PurgeJobTest.this.transactionStatus);
            verify(PurgeJobTest.this.summaries, never()).delete(any());
        }
    }

    /**
     * Asserts divergence D-F: a summary is removed only when BOTH of its counters have fallen away.
     *
     * <p>Refactoring Rationale: the reference tests one counter twice.
     * {@code cbl/CBPAUP0C.cbl} L156 reads
     * {@code IF PA-APPROVED-AUTH-CNT <= 0 AND PA-APPROVED-AUTH-CNT <= 0}, naming the approved count on both
     * sides of its conjunction, so the second condition adds nothing and {@code PA-DECLINED-AUTH-CNT} is
     * never tested at all. The consequence is not cosmetic: a summary whose approved count has reached zero
     * satisfies that guard while unexpired DECLINED authorizations remain beneath it, and the hierarchical
     * delete at L335 to L338 then removes the root together with those rows. The target guards both.
     *
     * <p>Assumptions: the comparison stays {@code <=} rather than becoming {@code ==}, which is the half of
     * the reference guard that IS carried across unchanged. Both counters are signed four-digit fields at
     * {@code cpy/CIPAUSMY.cpy} L27 and L28, so a summary loaded from an extract whose counters understated
     * its children can be driven below zero and must still qualify for removal.
     *
     * <p>Assumptions: the precondition this divergence turns on -- a summary carrying declined children and
     * no approved ones -- is ACCUMULATED elsewhere. {@code AuthorizationRequestListenerTest} owns the write
     * path that increments the two counters separately, and this group owns the purge consequence of the
     * state it can leave. Both halves are named so that neither can be read as the whole.
     */
    @Nested
    @DisplayName("divergence D-F: the delete guard tests both counters")
    class SummaryDeletionGuard {

        /**
         * A summary still holding unexpired declined authorizations survives, where the reference removes it.
         *
         * <p>Refactoring Rationale: this is the case divergence D-F consists of. Under the reference guard
         * at {@code cbl/CBPAUP0C.cbl} L156 this summary is REMOVED -- its approved count has reached zero
         * and the duplicated condition adds nothing, so L157 performs the root delete and the hierarchical
         * delete at L335 to L338 takes the two unexpired declined authorizations with it. Rows that have
         * not aged are destroyed, and the account's own record of them goes at the same moment, so nothing
         * afterwards can detect that they were lost. Guarding both counters keeps them.
         *
         * <p>Assumptions: the fixture expires only the approved pair, which is exactly the state that
         * separates the two guards -- the approved count reaches zero while the declined count does not. Any
         * other combination is decided identically by both, so this is the only shape in which the
         * divergence is observable.
         */
        @Test
        @DisplayName("a summary with unexpired declined children survives, where the reference removes it")
        void aSummaryWithLiveDeclinedChildrenIsNotDeleted() {
            Long accountId = committedParentAccountId();
            givenSummaries(List.of(committedParent(accountId)));
            givenChildren(List.of(
                    child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00"),
                    child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00"),
                    child(AUTH_DATE + 4, 91_500_002, DECLINED, "50.00", "0.00"),
                    child(AUTH_DATE + 4, 91_500_003, DECLINED, "100.00", "0.00")));

            PurgeJob.PurgeOutcome outcome = job().purge(
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(5)));

            assertThat(outcome.detailsDeleted()).isEqualTo(2);
            // WHY : Assumptions: the declined side of the statement is asserted to be ZERO rather than
            //       simply left unasserted. The two live declined children must not be reversed, and a
            //       statement that reversed them would be indistinguishable from one that did not unless
            //       the argument is named -- while the summary's own counters can no longer be read for
            //       this, because the reversal is an arithmetic update against the row rather than a change
            //       to the instance.
            verify(PurgeJobTest.this.summaries, times(1)).reverseExpiredAuthorizations(eq(accountId),
                    eq(2), argThat(amount -> amount.compareTo(new BigDecimal("300.00")) == 0),
                    eq(0), argThat(amount -> amount.signum() == 0));
            assertThat(outcome.summariesDeleted()).isZero();
            verify(PurgeJobTest.this.summaries, never()).delete(any());
        }

        /**
         * With both counters driven to zero the summary itself is removed.
         *
         * <p>Assumptions: this is the positive arm of the same guard, and it is asserted separately from the
         * survival case because a guard can be wrong in either direction. A condition written against the
         * declined counter alone -- the mirror image of the reference defect -- would pass the survival case
         * above and fail here, and a condition that never fires would pass here only by never removing
         * anything, which the count assertion catches.
         *
         * <p>Assumptions: the summary delete is asserted to carry the very instance the walk loaded, not
         * merely to have occurred. A removal addressed at some other summary would satisfy a bare call
         * assertion, and in a walk that crosses several accounts that is a real failure mode rather than a
         * hypothetical one.
         */
        @Test
        @DisplayName("both counters at zero remove the summary as well as its children")
        void bothCountersAtZeroRemoveTheSummary() {
            Long accountId = committedParentAccountId();
            PendingAuthSummary parent = committedParent(accountId);
            givenSummaries(List.of(parent));
            givenChildren(List.of(
                    child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00"),
                    child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00"),
                    child(AUTH_DATE, 91_500_002, DECLINED, "50.00", "0.00"),
                    child(AUTH_DATE, 91_500_003, DECLINED, "100.00", "0.00")));

            PurgeJob.PurgeOutcome outcome = job().purge(
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10)));

            assertThat(outcome.summariesDeleted()).isEqualTo(1);
            verify(PurgeJobTest.this.summaries).delete(parent);
        }

        /**
         * Every child is removed before its parent is.
         *
         * <p>Assumptions: the order is part of the contract, not an incidental consequence of the loop. The
         * reference removes each qualifying child at {@code cbl/CBPAUP0C.cbl} L310 inside its inner loop at
         * L146 to L154 and only reaches the root guard at L156 afterwards, and the migrated schema states
         * the same dependency as a foreign key from the detail table to the summary, so the reverse order
         * would be refused by the database rather than merely diverge from the reference.
         */
        @Test
        @DisplayName("the child rows are deleted before the parent summary is")
        void childrenAreDeletedBeforeTheParent() {
            givenSummaries(List.of(committedParent(committedParentAccountId())));
            PendingAuthDetail first = child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00");
            PendingAuthDetail second = child(AUTH_DATE, 91_500_001, APPROVED, "200.00", "200.00");
            PendingAuthDetail third = child(AUTH_DATE, 91_500_002, DECLINED, "50.00", "0.00");
            PendingAuthDetail fourth = child(AUTH_DATE, 91_500_003, DECLINED, "100.00", "0.00");
            givenChildren(List.of(first, second, third, fourth));

            job().purge(PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10)));

            InOrder order = inOrder(PurgeJobTest.this.details, PurgeJobTest.this.summaries);
            order.verify(PurgeJobTest.this.details).delete(first);
            order.verify(PurgeJobTest.this.details).delete(second);
            order.verify(PurgeJobTest.this.details).delete(third);
            order.verify(PurgeJobTest.this.details).delete(fourth);
            order.verify(PurgeJobTest.this.summaries).delete(any());
        }
    }

    /**
     * Asserts what a run's parameters default to, what they refuse, and that none of them is a clock read.
     *
     * <p>Purpose: this is {@code 1000-INITIALIZE} at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L183 to L210, which accepts the control
     * card at L189 and then applies a default to each of its four fields at L196 to L209.</p>
     *
     * <p>Assumptions: the reference card is a positional fixed-width record of exactly SEVENTEEN bytes with
     * FOUR filler items, and the count matters because a sixteen-byte reading of it is the natural mistake.
     * {@code PRM-INFO} at L98 to L108 declares {@code P-EXPIRY-DAYS PIC 9(02)} at L99, a one-byte
     * {@code FILLER} at L100, {@code P-CHKP-FREQ PIC X(05)} at L101, a second filler at L102,
     * {@code P-CHKP-DIS-FREQ PIC X(05)} at L103, a third filler at L104,
     * {@code P-DEBUG-FLAG PIC X(01)} at L105 with its two condition names at L106 and L107, and a FOURTH
     * filler at L108: two plus one plus five plus one plus five plus one plus one plus one is seventeen. The
     * card the job ships is sixteen characters, so it lands in a seventeen-byte record with the trailing
     * filler absorbing the slack. Three of the fillers are the commas; the fourth carries nothing and is
     * what makes the arithmetic come out at seventeen rather than sixteen.</p>
     *
     * <p>Assumptions: the fourth field, the debug flag, has no counterpart among the parameters asserted
     * here. It is defaulted at L207 to L209 and read at exactly four sites -- L220, L252, L307 and L332 --
     * each of which displays a counter or an identifier under {@code IF DEBUG-ON}, and all four become
     * debug-level log statements whose emission the configured log level decides. There is therefore nothing
     * for a parameter to carry.</p>
     *
     * <p>Assumptions: the card becomes a typed parameter object rather than a seventeen-byte string, which
     * is AAP Rule T6 (JCL maps by category) applied to this job -- an executed program becomes one
     * orchestrated task and its input becomes that task's arguments. Note the card does NOT arrive as a
     * program argument even in the reference: {@code ACCEPT PRM-INFO FROM SYSIN} at L189 reads it from the
     * job's input stream, and the {@code PROCEDURE DIVISION USING} header at L132 and L133 takes only the two
     * database interface blocks, so the {@code PARM=} string at {@code jcl/CBPAUP0J.jcl} L25 is consumed by
     * the region controller the job actually executes and never reaches this program's own logic. The
     * positional layout above is recorded because it is the only place the field order and the widths are
     * stated, and those widths are what the ceilings asserted below are read off.</p>
     */
    @Nested
    @DisplayName("the run parameters")
    class RunParameters {

        /**
         * The three counts default to the reference values, and a non-positive one is refused.
         *
         * <p>Refactoring Rationale: the expiry threshold is REFUSED at zero, where the reference admits it,
         * and the mechanism is an asymmetry between three guards rather than anything about the card's
         * contents. L196 guards the expiry field with {@code IF P-EXPIRY-DAYS IS NUMERIC} and nothing else,
         * because {@code PIC 9(02)} makes a numeric test the only shape available to it, while L201 and L204
         * guard the two five-character frequency fields with {@code = SPACES OR 0 OR LOW-VALUES}, which
         * additionally tests for zero. The card shipped at {@code jcl/CBPAUP0J.jcl} L36 and L37 is
         * {@code 00,00001,00001,Y}: its first field is numeric, so L196 is satisfied, L197 moves it, the L199
         * fallback of five is never reached, and {@code WS-EXPIRY-DAYS} becomes zero -- which L284's
         * inclusive comparison then satisfies for every authorization dated on or before the run date, so
         * the shipped job purges the whole database. Its two frequency fields WOULD have defaulted had they
         * been unusable, because {@code '00001'} is neither spaces nor zero as a five-character field. The
         * asymmetry is the difference between a safe default and a full purge.
         *
         * <p>Alternatives Considered: silently substituting the five-day default for a supplied zero, which
         * is what the reference would have done had L196 carried its neighbours' zero test. REJECTED because
         * it discards operator intent without saying so: a job definition asking for a zero-day purge and a
         * job definition asking for nothing would then run identically, and the operator who meant the
         * former would never learn the difference. Refusing surfaces it at the call site, and it is the one
         * parameter standing between a run and the entire table.
         */
        @Test
        @DisplayName("the three counts default to five, five and ten, and zero is refused")
        void theRunParametersCarryTheReferenceDefaultsAndRefuseZero() {
            assertThat(PurgeJob.DEFAULT_EXPIRY_DAYS).isEqualTo(5);
            assertThat(PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY).isEqualTo(5);
            assertThat(PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY).isEqualTo(10);

            PurgeJob.PurgeParameters defaults = PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON);
            assertThat(defaults.expiryDays())
                    .as("an absent expiry takes the L199 default of five and never zero")
                    .isEqualTo(PurgeJob.DEFAULT_EXPIRY_DAYS);
            assertThat(defaults.checkpointFrequency()).isEqualTo(PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY);
            assertThat(defaults.progressLogFrequency())
                    .isEqualTo(PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY);
            assertThat(defaults.businessDate()).isEqualTo(AUTHORIZED_ON);

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the value the shipped card resolves to is refused rather than defaulted")
                    .isThrownBy(() -> new PurgeJob.PurgeParameters(AUTHORIZED_ON, 0, 5, 10))
                    .withMessageContaining("expiryDays");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PurgeJob.PurgeParameters(AUTHORIZED_ON, 5, 0, 10))
                    .withMessageContaining("checkpointFrequency");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PurgeJob.PurgeParameters(AUTHORIZED_ON, 5, 5, 0))
                    .withMessageContaining("progressLogFrequency");
            assertThatExceptionOfType(NullPointerException.class)
                    .isThrownBy(() -> new PurgeJob.PurgeParameters(null, 5, 5, 10))
                    .withMessageContaining("businessDate");
        }

        /**
         * Each run parameter is refused above the width its position on the reference card can express.
         *
         * <p>Assumptions: the accepted side is asserted as well as the refused side. A check written one off
         * -- refusing AT the width rather than above it -- would pass a case that only tried an obviously
         * oversized value, and it would refuse the largest value an operator can legitimately configure.
         *
         * <p>Assumptions: the widths come from the card layout recorded on this group and are asserted
         * through the published constants rather than as literals, so the case and the check cannot disagree
         * about which number is the bound. Their values are asserted separately, which is what keeps this
         * from being a case that would pass whatever those constants held.
         *
         * <p>Assumptions: the expiry consequence is worth stating because it is why this bound matters more
         * than it looks. An unbounded expiry turns the run into a no-op, since no authorization is old enough
         * to qualify, and a completed purge that deleted nothing is indistinguishable from a correct one that
         * had nothing to delete -- so that failure is silent where the opposite one is loud.
         */
        @Test
        @DisplayName("each run parameter is refused above the width its card position can express")
        void eachParameterIsRefusedAboveItsCardWidth() {
            assertThat(PurgeJob.MAX_EXPIRY_DAYS)
                    .as("the expiry field is two digits on the reference card")
                    .isEqualTo(99);
            assertThat(PurgeJob.MAX_CARD_FREQUENCY)
                    .as("both frequency fields are five characters on the reference card")
                    .isEqualTo(99_999);

            assertThatCode(() -> new PurgeJob.PurgeParameters(
                    AUTHORIZED_ON, PurgeJob.MAX_EXPIRY_DAYS, PurgeJob.MAX_CARD_FREQUENCY,
                    PurgeJob.MAX_CARD_FREQUENCY))
                    .as("the widest value each field holds must be accepted, not refused")
                    .doesNotThrowAnyException();

            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PurgeJob.PurgeParameters(
                            AUTHORIZED_ON, PurgeJob.MAX_EXPIRY_DAYS + 1, 5, 10))
                    .withMessageContaining("expiryDays must be between 1 and 99");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PurgeJob.PurgeParameters(
                            AUTHORIZED_ON, 5, PurgeJob.MAX_CARD_FREQUENCY + 1, 10))
                    .withMessageContaining("checkpointFrequency must be between 1 and 99999");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new PurgeJob.PurgeParameters(
                            AUTHORIZED_ON, 5, 5, PurgeJob.MAX_CARD_FREQUENCY + 1))
                    .withMessageContaining("progressLogFrequency must be between 1 and 99999");
        }

        /**
         * The business date decides which rows qualify, and no clock is reachable from the job at all.
         *
         * <p>Refactoring Rationale: the reference reads the run date from the platform, at
         * {@code ACCEPT CURRENT-YYDDD FROM DAY} on {@code cbl/CBPAUP0C.cbl} L187, and L282 differences
         * against it. That single read is what makes a reference run irreproducible: the same data yields a
         * different qualifying set on a different day, so a rerun made to investigate a run selects
         * different rows from the run it is investigating. The date is now a required component of the
         * parameters with no default, and the injected-clock dependency that once supplied one was REMOVED
         * rather than left unused -- an injected clock being exactly what a later reader would reach for the
         * next time a date was wanted here.
         *
         * <p>Assumptions: the absence is asserted STRUCTURALLY as well as behaviourally, and the two halves
         * catch different regressions. The behavioural half proves that the supplied date is what decides,
         * because one run keeps a row and another removes the same row with only the date changed -- which no
         * clock-reading implementation could do. The structural half proves the dependency cannot creep back
         * in unnoticed, because a re-added constructor parameter or field would fail here even if every
         * behavioural case still passed with the date being honoured on some paths and not others.
         *
         * <p>Assumptions: both {@link java.time.Clock} and {@link java.time.InstantSource} are named, because
         * the second is the narrower interface the first implements and injecting it would reintroduce
         * exactly the same non-determinism while satisfying a check that named only the class.
         */
        @Test
        @DisplayName("the business date decides the outcome and no clock is reachable from the job")
        void theBusinessDateDecidesTheOutcomeAndNoClockIsReachable() {
            for (Constructor<?> declared : PurgeJob.class.getDeclaredConstructors()) {
                assertThat(declared.getParameterTypes())
                        .as("no constructor of the job accepts a clock")
                        .doesNotContain(java.time.Clock.class, java.time.InstantSource.class);
            }
            for (Field declared : PurgeJob.class.getDeclaredFields()) {
                assertThat(declared.getType())
                        .as("no field of the job holds a clock")
                        .isNotEqualTo(java.time.Clock.class)
                        .isNotEqualTo(java.time.InstantSource.class);
            }

            givenSummaries(List.of(committedParent(committedParentAccountId())));
            givenChildren(List.of(child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00")));

            PurgeJob job = job();
            assertThat(job.purge(PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(4)))
                    .detailsDeleted())
                    .as("the row survives for a business date four days after it was authorized")
                    .isZero();
            assertThat(job.purge(PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(5)))
                    .detailsDeleted())
                    .as("and the same row goes for a business date one day later")
                    .isEqualTo(1);
        }

        /**
         * Two runs made for the same parameters over the same data report the same result.
         *
         * <p>Assumptions: this is the reproducibility the injected date exists to give, stated as the
         * property an operator actually relies on rather than as the absence of a clock call. A rerun for
         * investigation has to select the rows the original run selected, and an implementation reading the
         * clock would satisfy every other case in this group while failing this one on any day the two runs
         * straddled midnight.
         *
         * <p>Assumptions: the whole outcome is compared rather than one count, which the record's own
         * value equality makes exact. Comparing a single count would leave three of the four figures free to
         * differ between runs.
         */
        @Test
        @DisplayName("two runs with the same parameters over the same data report the same result")
        void twoRunsWithTheSameParametersProduceTheSameResult() {
            givenSummaries(List.of(committedParent(committedParentAccountId())));
            givenChildren(List.of(
                    child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00"),
                    child(AUTH_DATE, 91_500_002, DECLINED, "50.00", "0.00")));

            PurgeJob job = job();
            PurgeJob.PurgeParameters parameters =
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(7));

            PurgeJob.PurgeOutcome first = job.purge(parameters);
            PurgeJob.PurgeOutcome second = job.purge(parameters);

            assertThat(first).isEqualTo(second);
            assertThat(first.detailsDeleted()).isEqualTo(2);
        }
    }

    /**
     * Asserts when a run commits, which is the migrated form of the reference checkpoint.
     *
     * <p>Purpose: this is {@code 9000-TAKE-CHECKPOINT} at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L352 to L372, whose
     * {@code EXEC DLI CHKP} at L355 to L356 commits the unit of work and releases database position. That
     * verb IS the commit boundary of this program and there is nothing else: {@code SYNCPOINT} occurs zero
     * times in all 386 lines, because a batch message program has no need of one. One transaction boundary
     * per window is what stands in for it.</p>
     *
     * <p>Trade-offs: making the checkpoint interval a transaction boundary inherits the reference's own
     * compromise rather than resolving it. A larger interval means fewer commits and less overhead, and more
     * work discarded when a window fails; a smaller one means the opposite, plus a shorter span over which
     * this run holds any row it has touched, at the cost of a commit per few summaries. The parameter exists
     * precisely because neither end is right for every deployment, which is why the migration keeps it a
     * parameter instead of choosing for the operator.</p>
     *
     * <p>Assumptions: the reference uses TWO DIFFERENT comparison operators for its two counters and neither
     * is unified with the other here. L160 tests the summaries processed with a strict {@code >} against
     * {@code P-CHKP-FREQ} to decide whether to COMMIT, while L360 tests the checkpoints taken with
     * {@code >=} against {@code P-CHKP-DIS-FREQ} to decide whether to DISPLAY. Same author, same program,
     * two conventions, and each site keeps its own semantics: the first is what produces the plus-one
     * recorded on {@link #REFERENCE_POST_INCREMENT_OFFSET}, and the second would produce no such offset even
     * if its counter were incremented in the same place.</p>
     */
    @Nested
    @DisplayName("the commit cadence")
    class CommitCadence {

        /**
         * One window carries exactly the configured number of summaries, one fewer than the reference's.
         *
         * <p>Assumptions: the configured value and the observable interval are asserted SEPARATELY, because
         * for this program they are not the same number and the difference is derivable rather than
         * incidental. The reference reads a summary and increments {@code WS-AUTH-SMRY-PROC-CNT} at L232,
         * then tests that count with a strict {@code >} at L160, so the count has to EXCEED the frequency
         * before L161 commits and the reset at L163 starts the next window: a frequency of three admits four
         * summaries between commits. The migrated window instead requests a page of exactly the configured
         * size, so it commits every three. Seven summaries at a window of three therefore close three
         * boundaries, where the reference would have closed two.
         *
         * <p>Assumptions: the difference is a CADENCE difference and changes no row selection, which is why
         * it is recorded here rather than registered as a divergence of its own. Every summary is still
         * visited exactly once and every expiry decision is unchanged; what differs is how much work one
         * failure discards. Committing one summary sooner than the reference is the safer side of that
         * trade, and reproducing the plus-one would mean reproducing an increment-then-compare artifact as
         * though it were a business rule.
         *
         * <p>Assumptions: the page size is asserted through the argument the walk is actually given, not
         * inferred from the boundary count alone. A run that asked for a page of some other size and still
         * closed three boundaries over seven summaries is possible, so the count on its own would not pin
         * the window.
         */
        @Test
        @DisplayName("a window holds exactly the configured count, where the reference admits one more")
        void oneWindowHoldsExactlyTheConfiguredNumberOfSummaries() {
            int windowSize = 3;
            int referenceInterval = windowSize + REFERENCE_POST_INCREMENT_OFFSET;
            assertThat(referenceInterval)
                    .as("L232 increments then L160 compares with a strict >, so the reference admits one more")
                    .isEqualTo(4);
            assertThat(referenceInterval - windowSize)
                    .as("and the whole of the difference is that one summary")
                    .isEqualTo(REFERENCE_POST_INCREMENT_OFFSET);

            givenSummaries(parents(7));
            givenChildren(List.of());

            PurgeJob.PurgeOutcome outcome = job().purge(new PurgeJob.PurgeParameters(AUTHORIZED_ON,
                    PurgeJob.DEFAULT_EXPIRY_DAYS, windowSize,
                    PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY));

            assertThat(outcome.summariesRead()).isEqualTo(7);
            verify(PurgeJobTest.this.summaries, atLeastOnce())
                    .findAccountIdsAboveOrderByAccountIdAsc(any(), eq(Limit.of(windowSize)));
            verify(PurgeJobTest.this.transactionManager, times(3)).getTransaction(any());
            verify(PurgeJobTest.this.transactionManager, times(3))
                    .commit(PurgeJobTest.this.transactionStatus);
        }

        /**
         * A run whose only window is shorter than the interval still commits it.
         *
         * <p>Assumptions: this is {@code PERFORM 9000-TAKE-CHECKPOINT} at L169, which sits AFTER the loop
         * that ends at L167 and is guarded by nothing. Without it the reference would end a run holding
         * every summary processed since its last checkpoint, so the final partial batch would be lost --
         * and a run of fewer summaries than the interval would commit nothing whatsoever. Two summaries at a
         * window of five is exactly that shape: the residual count never reaches the interval, and the work
         * is committed regardless.
         */
        @Test
        @DisplayName("the final commit happens even when the last window is shorter than the interval")
        void theFinalCommitHappensEvenWhenTheLastWindowIsShort() {
            givenSummaries(parents(2));
            givenChildren(List.of());

            PurgeJob.PurgeOutcome outcome = job().purge(new PurgeJob.PurgeParameters(AUTHORIZED_ON,
                    PurgeJob.DEFAULT_EXPIRY_DAYS, PurgeJob.DEFAULT_CHECKPOINT_FREQUENCY,
                    PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY));

            assertThat(outcome.summariesRead()).isEqualTo(2);
            verify(PurgeJobTest.this.transactionManager, times(1)).getTransaction(any());
            verify(PurgeJobTest.this.transactionManager, times(1))
                    .commit(PurgeJobTest.this.transactionStatus);
            verify(PurgeJobTest.this.transactionManager, never()).rollback(any());
        }

        /**
         * A run whose last page fills the window still closes one further, empty, committed window.
         *
         * <p>Assumptions: the unconditional final checkpoint is observable in this shape and only in this
         * shape. When the last page is SHORT the closing commit and the last working commit are the same
         * boundary, so a run that had dropped L169 would look identical; when the last page is FULL the walk
         * cannot know it is finished until it seeks again, so the extra boundary is separately countable.
         * Four summaries at a window of two is that case: two full windows, then one that reads nothing and
         * commits anyway.
         *
         * <p>Assumptions: no summary is read twice, which the read count asserts. A closing window that
         * re-seeked from the wrong position would also produce three boundaries while double-counting the
         * last page, so the boundary count alone would not distinguish the two.
         */
        @Test
        @DisplayName("a full final page still closes one further empty window, which also commits")
        void aFullFinalPageStillCommitsAClosingWindow() {
            givenSummaries(parents(4));
            givenChildren(List.of());

            PurgeJob.PurgeOutcome outcome = job().purge(new PurgeJob.PurgeParameters(AUTHORIZED_ON,
                    PurgeJob.DEFAULT_EXPIRY_DAYS, 2, PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY));

            assertThat(outcome.summariesRead()).isEqualTo(4);
            verify(PurgeJobTest.this.transactionManager, times(3)).getTransaction(any());
            verify(PurgeJobTest.this.transactionManager, times(3))
                    .commit(PurgeJobTest.this.transactionStatus);
        }

        /**
         * One transaction is opened per window, so the window size governs the commit cadence.
         *
         * <p>Assumptions: three summaries at a window of ONE is the smallest configuration in which every
         * summary closes its own boundary, so the count of boundaries and the count of summaries can be seen
         * to move together. The fourth boundary is the closing empty window that discovers the walk is
         * finished.
         */
        @Test
        @DisplayName("one transaction is opened per window, plus the closing empty window")
        void eachWindowCommitsSeparately() {
            givenSummaries(parents(3));
            givenChildren(List.of());

            PurgeJob.PurgeOutcome outcome = job().purge(new PurgeJob.PurgeParameters(AUTHORIZED_ON,
                    PurgeJob.DEFAULT_EXPIRY_DAYS, 1, PurgeJob.DEFAULT_PROGRESS_LOG_FREQUENCY));

            assertThat(outcome.summariesRead()).isEqualTo(3);
            verify(PurgeJobTest.this.transactionManager, times(4)).getTransaction(any());
        }

        /**
         * The progress frequency changes no commit, and the window size changes no progress report.
         *
         * <p>Assumptions: the two controls are independent, and this is the case that holds them apart. The
         * reference counts summaries in {@code WS-AUTH-SMRY-PROC-CNT}, declared at L52 and incremented at
         * L232, and compares it at L160 to decide whether to COMMIT; it counts checkpoints in
         * {@code WS-NO-CHKP}, declared at L51 and incremented at L359, and compares it at L360 to decide
         * whether to DISPLAY. Different counters, different parameters, different operators and different
         * units, and the second gates only a message. Driving the same data twice with the progress
         * frequency changed and the window size fixed must therefore open an identical number of boundaries
         * and report an identical outcome; an implementation that conflated the two would not.
         */
        @Test
        @DisplayName("the progress frequency is independent of the commit cadence")
        void theProgressFrequencyIsIndependentOfTheCommitCadence() {
            givenSummaries(parents(4));
            givenChildren(List.of());

            PurgeJob job = job();
            PurgeJob.PurgeOutcome reportedEveryWindow = job.purge(new PurgeJob.PurgeParameters(
                    AUTHORIZED_ON, PurgeJob.DEFAULT_EXPIRY_DAYS, 2, 1));
            PurgeJob.PurgeOutcome reportedRarely = job.purge(new PurgeJob.PurgeParameters(
                    AUTHORIZED_ON, PurgeJob.DEFAULT_EXPIRY_DAYS, 2, 1_000));

            assertThat(reportedEveryWindow).isEqualTo(reportedRarely);
            assertThat(reportedEveryWindow.summariesRead()).isEqualTo(4);
            verify(PurgeJobTest.this.transactionManager, times(6)).getTransaction(any());
        }
    }

    /**
     * Asserts how the two walks end, which is DIFFERENTLY, and the asymmetry is deliberate.
     *
     * <p>Purpose: the reference evaluates its retrieval status in two places and admits a different set of
     * values at each. The ROOT block at {@code cbl/CBPAUP0C.cbl} L228 to L241, following the
     * {@code EXEC DLI GN} at L223, admits exactly TWO: a blank status at L229 counts the summary at L231,
     * counts it towards the checkpoint at L232 and records its account at L233, and {@code 'GB'} at L234 ends
     * the database; every other value falls to {@code WHEN OTHER} at L236 and abends at L240 -- so a
     * segment-not-found {@code 'GE'} at the root ENDS THE RUN. The CHILD block at L259 to L271, following the
     * {@code EXEC DLI GNP} at L255, admits THREE: a blank status at L260 counts the authorization at L262,
     * while {@code 'GE'} at L263 and {@code 'GB'} at L264 both fall through to L265 and merely mark the chain
     * exhausted; only the residual arm at L266 abends at L270.</p>
     *
     * <p>Assumptions: the asymmetry is CORRECT rather than an inconsistency to be tidied. A root browse that
     * cannot advance has struck a real problem, because the walk it is driving is the run itself; a parent
     * with no remaining children beneath it is the ordinary end of one child chain and says nothing about the
     * run. Unifying the two blocks in either direction breaks one of them: widening the root's set would
     * silently truncate a run at the first unexpected status, and narrowing the child's would abend on every
     * summary whose chain simply ended.</p>
     *
     * <p>Assumptions: the status field this program evaluates is the interface block's, which is why nothing
     * here is written in the other shape. {@code CBPAUP0C} is a batch message program using
     * {@code EXEC DLI}, so it evaluates {@code DIBSTAT}; exactly three of the eight programs in this context
     * call the batch language interface directly and evaluate {@code PAUT-PCB-STATUS} instead, and their
     * shape is asserted by {@code AuthorizationExtractRoundTripTest}, which carries the load and unload
     * halves of that round trip. The two shapes are never expressed in each other's vocabulary.</p>
     *
     * <p>Assumptions: THREE distinct termination sets exist across this context and none is merged with
     * another. This program's root admits a blank status and {@code 'GB'}; its child admits a blank status,
     * {@code 'GE'} and {@code 'GB'}; and the unload program's child walk admits {@code SPACES} and
     * {@code 'GE'} alone. Recording all three is what stops a reader generalising from whichever one they
     * met first.</p>
     */
    @Nested
    @DisplayName("how the two walks terminate")
    class TerminationStatuses {

        /**
         * An exhausted root walk ends the run normally, reporting nothing done.
         *
         * <p>Assumptions: this is the {@code 'GB'} arm at L234 and L235, whose analogue is a page with no
         * keys on it. Ending the run is the whole of what that arm does -- it is not a failure and it reports
         * no statistics of its own -- so the case asserts four zero counts, no exception and a boundary that
         * was committed rather than discarded.
         *
         * <p>Assumptions: the walk is stubbed HERE rather than through the shared helper, which would also
         * stub a second read that an empty walk never performs. A run that never resolves a summary would
         * then leave that stub unused, and an unused stub is reported as a defect by the strict mocking this
         * class runs under -- correctly, because it would mean the case had described behaviour the run does
         * not have.
         */
        @Test
        @DisplayName("an empty root page ends the run normally, with nothing read and nothing removed")
        void anExhaustedRootWalkEndsTheRunNormally() {
            when(PurgeJobTest.this.summaries.findAccountIdsAboveOrderByAccountIdAsc(any(), any()))
                    .thenReturn(List.of());

            PurgeJob.PurgeOutcome outcome = job().purge(
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON));

            assertThat(outcome).isEqualTo(new PurgeJob.PurgeOutcome(0, 0, 0, 0));
            verify(PurgeJobTest.this.transactionManager, times(1)).getTransaction(any());
            verify(PurgeJobTest.this.transactionManager, times(1))
                    .commit(PurgeJobTest.this.transactionStatus);
            verify(PurgeJobTest.this.transactionManager, never()).rollback(any());
            verify(PurgeJobTest.this.details, never()).delete(any());
        }

        /**
         * An unexpected failure on the root walk ends the run rather than being absorbed.
         *
         * <p>Assumptions: this is the {@code WHEN OTHER} arm at L236 to L240, which performs
         * {@code 9999-ABEND}. The set that arm catches includes {@code 'GE'}, so a not-found at the root is a
         * hard failure in the reference and is one here; the migrated walk has no separate not-found status
         * to raise -- an absent row simply produces the empty page the case above covers -- so what stands
         * for the residual arm is any failure the read itself raises, and it is terminal.
         *
         * <p>Assumptions: nothing is deleted, which is asserted rather than assumed. The failure occurs
         * before any row has been resolved, and a run that had begun removing rows before its walk succeeded
         * would be removing them on the strength of a page it had not obtained.
         */
        @Test
        @DisplayName("an unexpected failure on the root walk is terminal, as its residual arm is")
        void anUnexpectedRootFailureIsTerminal() {
            when(PurgeJobTest.this.summaries.findAccountIdsAboveOrderByAccountIdAsc(any(), any()))
                    .thenThrow(new IllegalStateException("summary walk failed"));

            PurgeJob job = job();
            PurgeJob.PurgeParameters parameters =
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON);

            assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                    .isThrownBy(() -> job.purge(parameters));

            verify(PurgeJobTest.this.details, never()).delete(any());
            verify(PurgeJobTest.this.summaries, never()).delete(any());
            verify(PurgeJobTest.this.transactionManager, never())
                    .commit(PurgeJobTest.this.transactionStatus);
        }

        /**
         * A summary with no authorizations beneath it is ordinary, and the walk carries on past it.
         *
         * <p>Assumptions: this is the child {@code 'GE'} and {@code 'GB'} arms at L263 to L265, which set
         * {@code NO-MORE-AUTHS} and return to the outer loop. The property that distinguishes them from the
         * root's second arm is that the RUN continues, so the case is built with a childless summary followed
         * by one that has an expiring child: the second summary must still be read and its child must still
         * be removed, which an implementation treating an empty chain as the end of the walk would not do.
         *
         * <p>Assumptions: the childless summary contributes no reversal at all, which is asserted against its
         * own identifier rather than by counting statements. A statement carrying four zeros would be a write
         * against a row this run had no reason to touch, and asserting only a total would let one summary's
         * reversal stand in for another's.
         */
        @Test
        @DisplayName("a childless summary is ordinary exhaustion and the walk continues past it")
        void aChildlessSummaryIsNormalExhaustionAndTheWalkContinues() {
            List<PendingAuthSummary> stored = parents(2);
            Long childless = stored.get(0).getAccountId();
            Long populated = stored.get(1).getAccountId();
            PendingAuthDetail expiring = child(populated, AUTH_DATE, 91_500_000, APPROVED,
                    new BigDecimal("100.00"), new BigDecimal("100.00"),
                    PendingAuthDetail.MATCH_STATUS_PENDING);
            givenSummaries(stored);
            givenChildrenOf(populated, List.of(expiring));

            PurgeJob.PurgeOutcome outcome = job().purge(
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10)));

            assertThat(outcome.summariesRead())
                    .as("the empty chain ends its own walk, never the run")
                    .isEqualTo(2);
            assertThat(outcome.detailsRead()).isEqualTo(1);
            assertThat(outcome.detailsDeleted()).isEqualTo(1);
            verify(PurgeJobTest.this.summaries).findByAccountId(childless);
            verify(PurgeJobTest.this.summaries).findByAccountId(populated);
            verify(PurgeJobTest.this.details).delete(expiring);
            verify(PurgeJobTest.this.summaries, never())
                    .reverseExpiredAuthorizations(eq(childless), anyInt(), any(), anyInt(), any());
            verify(PurgeJobTest.this.summaries, times(1))
                    .reverseExpiredAuthorizations(eq(populated), eq(1), any(), eq(0), any());
        }

        /**
         * An unexpected failure on the child walk ends the run, as its own residual arm does.
         *
         * <p>Assumptions: the child block's tolerance is bounded, and asserting the bound is what keeps the
         * case above from reading as "any child failure is ignored". Its L266 to L270 arm performs the same
         * {@code 9999-ABEND} the root's does, so exhaustion and failure are different things at the child
         * too -- the first two arms admit an empty chain, and nothing admits a chain that could not be read.
         */
        @Test
        @DisplayName("an unexpected failure on the child walk is terminal, as its residual arm is")
        void anUnexpectedChildFailureIsTerminal() {
            givenSummaries(List.of(committedParent(committedParentAccountId())));
            when(PurgeJobTest.this.details
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any(), any()))
                    .thenThrow(new IllegalStateException("authorization walk failed"));

            PurgeJob job = job();
            PurgeJob.PurgeParameters parameters =
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10));

            assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                    .isThrownBy(() -> job.purge(parameters))
                    .withCauseInstanceOf(IllegalStateException.class);

            verify(PurgeJobTest.this.summaries, never()).delete(any());
            verify(PurgeJobTest.this.transactionManager, never())
                    .commit(PurgeJobTest.this.transactionStatus);
        }
    }

    /**
     * Asserts the order the two reads are issued in, which is what the deletion decision rests on.
     *
     * <p>Assumptions: the reference reads its segments WITHOUT HOLD and this class asserts nothing whatever
     * about that. Its two retrievals are a plain {@code GN} at L223 and a plain {@code GNP} at L255, never
     * the hold forms, and the omission was demonstrably deliberate rather than an oversight:
     * {@code cpy/IMSFUNCS.cpy} declares all nine function codes at L17 to L26 including {@code FUNC-GHU} at
     * L19, {@code FUNC-GHN} at L21 and {@code FUNC-GHNP} at L23, and a census across all eight programs of
     * this context finds those three constants referenced zero times. Whether a delete issued without a
     * preceding hold-get actually succeeds under that data manager is NOT determinable from this repository,
     * which has no such runtime, so no claim is made about what the reference does at run time and no case
     * here asserts locking, hold semantics or a hold-less delete. What the target does is stated positively
     * instead: the deletes run inside the window's own transaction with the ordinary row protection the
     * engine gives, no service method in this package declares a lock mode, and the lost update a locking
     * read would have guarded is closed differently -- by computing the reversal as one arithmetic statement
     * in the database, which is why the summary repository publishes no locking read at all.</p>
     */
    @Nested
    @DisplayName("the order the reads are issued in")
    class ReadOrdering {

        /**
         * Every summary is read before anything beneath it is read, reversed or removed.
         *
         * <p>Purpose: the reversal at {@code cbl/CBPAUP0C.cbl} L287 to L292 is per-child arithmetic over four
         * counters the online decision path also adds to, so the order in which this run issues its reads is
         * what decides which counters the deletion is judged against. This asserts that order: the walk
         * answers keys, the summary is loaded by its own read, and only then is the authorization chunk
         * issued and the child removed.
         *
         * <p>Assumptions: the UNBOUNDED child walk is asserted never to be used from this path, and that
         * assertion is the point of the case rather than a decoration. Both overloads are declared on the
         * same repository and either compiles here, so nothing but an assertion prevents a later edit
         * restoring the one that loads an account's entire authorization history inside a transaction whose
         * size nothing bounds. The unbounded overload remains declared because the unload path legitimately
         * uses it -- it copies rows out and writes nothing -- so its presence is not evidence of a defect and
         * its absence cannot be the guard.
         *
         * <p>Refactoring Rationale: this case once asserted a LOCKING summary read and asserted the unlocked
         * one never happened, and the two verifications contradicted each other outright -- one required the
         * read the other forbade, so whichever the mocking framework evaluated first decided the result. The
         * lock is withdrawn, so the ordering property is restated against the reads that survive and the
         * guard is moved onto the axis that still has two candidates. The method name changed with it: the
         * old one said the summary was HELD, which is a claim about locking that the production code no longer
         * makes and that this class must not appear to make either.
         *
         * <p>Alternatives Considered: asserting only that each read was called. REJECTED because the failure
         * being closed is an ORDERING failure: issuing the child walk before the summary is loaded satisfies a
         * presence assertion while deciding the deletion from counters read after the children were already
         * in hand.
         */
        @Test
        @DisplayName("the summary row is read before its children are read, reversed or removed")
        void theSummaryIsReadBeforeAnythingBeneathItIsTouched() {
            Long accountId = committedParentAccountId();
            givenSummaries(List.of(committedParent(accountId)));
            givenChildren(List.of(child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00")));

            job().purge(PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON.plusDays(10)));

            InOrder order = inOrder(PurgeJobTest.this.summaries, PurgeJobTest.this.details);
            order.verify(PurgeJobTest.this.summaries)
                    .findAccountIdsAboveOrderByAccountIdAsc(any(), any());
            order.verify(PurgeJobTest.this.summaries).findByAccountId(accountId);
            order.verify(PurgeJobTest.this.details)
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(eq(accountId), any());
            order.verify(PurgeJobTest.this.details).delete(any());
            verify(PurgeJobTest.this.summaries, never())
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any());
            verify(PurgeJobTest.this.details, never())
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any());
        }

        /**
         * A key whose summary is gone by the time its own read runs is skipped, and the walk still advances.
         *
         * <p>Purpose: the two reads can disagree, because a row removed between them is exactly what a
         * concurrent purge of the same window leaves behind. This asserts the arm that has no reference
         * equivalent to copy: the missing summary contributes nothing to the statistics, no authorization
         * walk is issued for it, and the position advances past its key so the walk terminates.
         *
         * <p>Assumptions: the advance is asserted by inspecting the position the SECOND call seeks above,
         * which must be the LAST key of the first page and not the last key a summary read resolved. A
         * position that skipped back to the resolved key would re-seek the missing one on every call and the
         * run would not end, so the assertion is on the value that makes termination true rather than on the
         * absence of an exception.
         *
         * <p>Assumptions: the missing summary is not counted as read. The statistics are what the run reports
         * as its work, and the reference walk would never have returned a row that is not there, so counting
         * it would overstate the run against a program that could not have produced the count.
         */
        @Test
        @DisplayName("a summary removed between the walk and its read is skipped without stalling the walk")
        void aSummaryRemovedBeforeItsOwnReadIsSkipped() {
            Long accountId = committedParentAccountId();
            PendingAuthSummary survivor = committedParent(accountId);
            Long vanishedAccountId = Long.valueOf(accountId.longValue() + 1L);
            // WHY : Assumptions: the two reads are stubbed HERE rather than through the shared helper,
            //       because the state under test is a row present to one read and absent to the other, and
            //       the helper answers both from one list so they cannot disagree. Re-stubbing the helper's
            //       walk instead would also re-enter the helper's own answer with null arguments, which is
            //       how the mocking framework evaluates the inner call of a second stubbing over an
            //       already-stubbed method.
            when(PurgeJobTest.this.summaries.findAccountIdsAboveOrderByAccountIdAsc(any(), any()))
                    .thenAnswer(invocation -> {
                        long after = invocation.<Long>getArgument(0).longValue();
                        return List.of(accountId, vanishedAccountId).stream()
                                .filter(candidate -> candidate.longValue() > after)
                                .toList();
                    });
            when(PurgeJobTest.this.summaries.findByAccountId(accountId))
                    .thenReturn(Optional.of(survivor));
            when(PurgeJobTest.this.summaries.findByAccountId(vanishedAccountId))
                    .thenReturn(Optional.empty());
            givenChildren(List.of(child(AUTH_DATE, 91_500_000, APPROVED, "100.00", "100.00")));

            // WHY : Assumptions: the window is sized to exactly the two keys the walk returns, so the page
            //       is FULL and the run must seek again. At the default window of five a two-key page is
            //       short, which ends the walk after one call and leaves the position unobservable -- the
            //       assertion below would then be asserting against a call the run had no reason to make.
            PurgeJob.PurgeOutcome outcome = job().purge(
                    new PurgeJob.PurgeParameters(AUTHORIZED_ON.plusDays(10), 5, 2, 10));

            assertThat(outcome.summariesRead()).isEqualTo(1);
            assertThat(outcome.detailsRead()).isEqualTo(1);
            assertThat(outcome.detailsDeleted()).isEqualTo(1);
            verify(PurgeJobTest.this.summaries).findByAccountId(vanishedAccountId);
            // WHY : Assumptions: the overload named here is the BOUNDED one the purge actually issues.
            //       Naming the unbounded overload instead would pass against a run that walked the missing
            //       account's children in full, because that call is not the one being forbidden -- an
            //       absence assertion aimed at a method the subject never calls cannot fail.
            verify(PurgeJobTest.this.details, never())
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(eq(vanishedAccountId), any());
            verify(PurgeJobTest.this.summaries, times(1))
                    .findAccountIdsAboveOrderByAccountIdAsc(vanishedAccountId, Limit.of(2));
        }
    }

    /**
     * Asserts what a failed run reports, which is an exit status and a position and nothing else.
     *
     * <p>Purpose: this is {@code 9999-ABEND} at
     * {@code app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl} L377 to L383, which every failure arm of the
     * reference performs -- L240 on a summary read, L270 on a detail read, L320 on a child delete, L345 on a
     * parent delete and L369 on a checkpoint.</p>
     *
     * <p>Assumptions: that paragraph does NOT abend the task despite its name. It displays a line at L380,
     * moves sixteen to the return code at L382 and returns normally at L383, so what the surrounding
     * environment observes is an EXIT STATUS. In the target that becomes a non-zero process exit for the
     * orchestrator's catch handler to act on, and translating it into one belongs to the entry point that
     * owns the process rather than to a bean inside a running container.</p>
     *
     * <p>Assumptions: a batch return code and a test verdict are DIFFERENT CONCERNS and nothing here conflates
     * them. The graded rubric under which a warning-level aggregate counts as success belongs to the COBOL
     * parity suite alone; the gate on this side is binary. No case tolerates a non-zero status, ignores a
     * failure or continues on error, which is why sixteen appears below as a value ASSERTED and never as a
     * result accepted.</p>
     */
    @Nested
    @DisplayName("what a failed run reports")
    class FailureReporting {

        /**
         * A failure during the walk reports a non-zero exit status and no statistics.
         *
         * <p>Assumptions: the status is asserted to be both sixteen AND non-zero, because it is the non-zero
         * property an orchestrator acts on while sixteen is the particular value carried across from L382.
         * The cause is asserted attached because a status with no diagnosis behind it cannot be investigated,
         * and the reference kept its own status code and position on the line it displayed.
         *
         * <p>Assumptions: no statistics are reported on a failed run, which the reference achieves by
         * mechanism rather than by intent. It holds exactly two {@code GOBACK} statements -- L180, reachable
         * only after the statistics block at L171 to L178, and L383 inside the abend paragraph, which returns
         * from the program directly -- so a failed reference run displays nothing. Asserting that no row was
         * removed is the observable form of that here.
         */
        @Test
        @DisplayName("a failed run reports exit status sixteen, keeps the cause, and removes nothing")
        void aFailedRunReportsTheAbendExitStatus() {
            when(PurgeJobTest.this.summaries.findAccountIdsAboveOrderByAccountIdAsc(any(), any()))
                    .thenThrow(new IllegalStateException("summary read failed"));

            PurgeJob job = job();
            PurgeJob.PurgeParameters parameters =
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON);

            assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                    .isThrownBy(() -> job.purge(parameters))
                    .withCauseInstanceOf(IllegalStateException.class)
                    .satisfies(thrown -> {
                        assertThat(thrown.exitStatus()).isEqualTo(PurgeJob.ABEND_EXIT_STATUS);
                        assertThat(thrown.exitStatus()).isEqualTo(16);
                        assertThat(thrown.exitStatus()).isNotZero();
                    });
            verify(PurgeJobTest.this.details, never()).delete(any());
            verify(PurgeJobTest.this.summaries, never()).delete(any());
        }

        /**
         * A failed window names the window it stopped in and not the account it had reached.
         *
         * <p>Purpose: the message of a thrown report reaches the log through the stack trace whether or not
         * the raising site intended it to, so an identifier in that message is an identifier in the log for
         * every failed purge. This asserts the message locates the run by window ordinal and carries no
         * account, which is what the observability contract requires of a durable diagnostic.
         *
         * <p>Assumptions: the account asserted absent is the one the fixture actually uses, so the assertion
         * can fail. Asserting the absence of an arbitrary number would pass against a message carrying a
         * different account, which is the same defect.
         *
         * <p>Assumptions: the exit status is asserted alongside, because the two properties travel together
         * -- an orchestrator acts on the status and an operator reads the message -- and a change that dropped
         * the account by dropping the message altogether would satisfy the absence assertion on its own.
         */
        @Test
        @DisplayName("a failed window is reported by its ordinal, carrying no account identifier")
        void aFailedWindowNamesItsOrdinalRatherThanTheAccount() {
            when(PurgeJobTest.this.summaries.findAccountIdsAboveOrderByAccountIdAsc(any(), any()))
                    .thenThrow(new IllegalStateException("summary read failed"));

            PurgeJob job = job();
            PurgeJob.PurgeParameters parameters =
                    PurgeJob.PurgeParameters.forBusinessDate(AUTHORIZED_ON);

            assertThatExceptionOfType(PurgeJob.PurgeAbendException.class)
                    .isThrownBy(() -> job.purge(parameters))
                    .withMessageContaining("window 1")
                    .withMessageNotContaining(String.valueOf(committedParentAccountId()))
                    .satisfies(thrown ->
                            assertThat(thrown.exitStatus()).isEqualTo(PurgeJob.ABEND_EXIT_STATUS));
        }
    }
}
