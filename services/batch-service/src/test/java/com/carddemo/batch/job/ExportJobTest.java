package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.Card;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.Customer;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.dto.DatasetGeneration.DatasetFamily;
import com.carddemo.batch.mapper.ExportRecordMapper;
import com.carddemo.batch.mapper.ExportRecordMapper.Prefix;
import com.carddemo.batch.mapper.ExportRecordMapper.RecordType;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.CustomerRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.codec.PackedDecimalCodec;
import com.carddemo.common.codec.ZonedDecimalCodec;
import com.carddemo.common.time.TimestampFormatter;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Pins the producer half of the 500-byte branch-migration export contract that {@link ExportJob}
 * writes, re-expressing {@code app/cbl/CBEXPORT.cbl} under {@code app/jcl/CBEXPORT.jcl:43}.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: this is the structural-tier case file that fixes the SHAPE of the emitted dataset --
 * the 500-byte record, the 40-byte common prefix at its declared offsets, the five mutually exclusive
 * 460-byte payload views, the single monotonic sequence number that spans all five of them, the
 * per-field codec routing that one record with three storage regimes demands, and the two attribution
 * literals the reference moves into every record. It also states divergence D-1 authoritatively; no
 * other case file in this module does.
 *
 * <p>Parameters, return values, exceptions or errors: this is a test type. It declares no constructor
 * of its own, so the type takes no parameter, yields no value and raises nothing; every member below
 * carries its own at-clauses. The inapplicability is stated rather than passed over because
 * user-specified Rule 1 forbids a docstring that omits parameters, return values or purpose, and a
 * reader must be able to tell a declared inapplicability from an omission. The sibling case files in
 * this package state it the same way.
 *
 * <h2>Refactoring Rationale: divergence D-1, the file-description record key</h2>
 *
 * <p>Refactoring Rationale: {@code app/cbl/CBEXPORT.cbl:65-69} selects the export output
 * {@code ORGANIZATION IS INDEXED} with {@code ACCESS MODE IS SEQUENTIAL} and declares
 * {@code RECORD KEY IS EXPORT-SEQUENCE-NUM} at {@code app/cbl/CBEXPORT.cbl:68}. A record key must name
 * a field of the file's own record, and this one does not: the file record is unstructured --
 * {@code app/cbl/CBEXPORT.cbl:91-92} declare {@code RECORD CONTAINS 500 CHARACTERS.} and
 * {@code 01 EXPORT-OUTPUT-RECORD PIC X(500).} -- while {@code EXPORT-SEQUENCE-NUM} exists only in the
 * copybook structure copied beneath {@code WORKING-STORAGE SECTION.}, at
 * {@code app/cbl/CBEXPORT.cbl:96} against {@code app/cpy/CVEXPORT.cpy:16}.
 * {@code app/cbl/CBIMPORT.cbl:40} repeats the identical declaration. The defect is SEMANTIC, so no
 * compiler flag repairs it, and {@code app/**} is reference-only, so it is not repaired at all. What
 * the migrated Java does instead is keep the sequence number as a genuine four-byte field of the
 * emitted record at zero-based offset 27 and write the stream append-only, which is exactly the usage
 * the reference makes of its own file: all five of its writes are group moves at
 * {@code app/cbl/CBEXPORT.cbl:301}, {@code :364}, {@code :419}, {@code :484} and {@code :542}, and it
 * never reads the file back and never accesses it by key. The divergence is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Assumptions: the consequence of D-1 is the single most misreadable fact in this repository and is
 * therefore stated here in full. Because {@code CBEXPORT} and {@code CBIMPORT} do not build, only TEN
 * of the twelve batch programs compile; {@code scripts/build_test_programs.sh} classifies the pair as
 * known-unsupported, expects the documented failure and aggregates a SOFT WARN of 4 rather than
 * poisoning the aggregate result; and {@code tests/integration/test_export_import.py} is skipped for
 * that stated reason, mirrored in {@code tests/conftest.py}. That aggregate 4 is the parity oracle's
 * GREEN state and D-1 is its sole cause, per {@code tests/README.md} section 1.1 and
 * {@code src/test/resources/fixtures/README.md} section 11.3. It must never be reported as a
 * regression, and it must never be confused with the posting job's business warn tier, which
 * originates solely at {@code app/cbl/CBTRN02C.cbl:229-230}. {@code ImportJobTest} restates D-1 as one
 * of its own divergences; this file is where it is stated authoritatively.
 *
 * <h2>Trade-offs: there is no export golden, so the round trip is the specification</h2>
 *
 * <p>Trade-offs: every other dataset this module writes has a committed expectation file produced by
 * running the reference; this one cannot, because the program does not compile, so it has never run,
 * so nothing recorded its bytes. {@code tests/golden/} holds {@code interest}, {@code posting},
 * {@code provisioning}, {@code reporting} and {@code statement} and no {@code export} directory --
 * verified by listing it -- and no equivalent exists under {@code src/test/resources}. The
 * specification is instead the byte-identical round trip that
 * {@code tests/integration/test_export_import.py} frames in its own module docstring, modelled on the
 * {@code IDCAMS REPRO} pattern. The compromise accepted is that fidelity here rests on transcription
 * from {@code app/cbl/CBEXPORT.cbl} and {@code app/cpy/CVEXPORT.cpy} rather than on a byte comparison
 * against an oracle, which is why every offset, discriminator and literal asserted below cites the
 * source line it was read from, and why the consuming half of the round trip is asserted from the
 * import side in {@code ImportJobTest} rather than here. This file pins the producer contract so that
 * the consumer has something normative to consume.
 *
 * <h2>All five record types are asserted, and no ninth entity was invented to do it</h2>
 *
 * <p>Alternatives Considered: two of the five record types once had no reader seam in this module at
 * all -- there was no customer entity and no card entity -- and the shortcuts available for asserting
 * them anyway were a native query, a bulk read over a synchronous service call, or leaving the two
 * types uncovered behind an explicit skip. All three are moot now and none was taken: both seams exist,
 * as {@link com.carddemo.batch.domain.Customer} with
 * {@link com.carddemo.batch.repository.CustomerRepository} and {@link com.carddemo.batch.domain.Card}
 * with {@link com.carddemo.batch.repository.CardRepository}, and the batch role already holds
 * {@code SELECT} on both owning schemas. So every case below asserts against the seams the job itself
 * declares, no ninth domain type was added to this module's closed domain package to make an assertion
 * reachable, and NO record type is gap-limited or skipped. All five discriminators are covered
 * explicitly and all five payload views are checked.</p>
 *
 * <p>Assumptions: the per-record-type census is read from the dataset's own discriminator bytes rather
 * than from a run summary, because this job returns a bare tier and builds no
 * {@code BatchRunSummary} -- so a breakdown asserted on that type would be asserting a value nothing
 * produces. The dataset is the better source in any case: a count taken from the bytes cannot agree
 * with a defect that the counters and the summary would agree with each other about.</p>
 *
 * <h2>Trade-offs: the sink is a seam and the reader side is stubbed</h2>
 *
 * <p>Trade-offs: the object store is a stub whose put request is captured, not an emulated bucket, and
 * the five masters are stubbed repositories rather than a live schema.
 * {@code services/batch-service/pom.xml} contributes Testcontainers {@code postgresql} and
 * {@code junit-jupiter} only and no object-store emulator module at all, so an emulated sink is not
 * available to a case in this tier; and the properties this module's test profile leaves unset keep the
 * messaging gate closed, so no queue emulator is needed either. What is given up is coverage of the
 * transport, which is not this file's subject; what is kept is that every assertion below is about the
 * BYTES the job produced. The database-backed cases live in the {@code *IT} classes Failsafe runs.
 *
 * <p>Assumptions: this class is named {@code *Test} and not {@code *IT} deliberately. Surefire owns
 * the {@code *Test} pattern in the {@code test} phase while Failsafe owns {@code *IT} after packaging,
 * so an {@code *IT} name here would silently never run in the phase this file belongs to.
 *
 * @see ExportJob
 * @see ExportRecordMapper
 * @see BatchJobName#EXPORT
 */
class ExportJobTest {

    /** Dataset bucket every put is addressed to. */
    private static final String BUCKET = "carddemo-datasets-test";

    /** Durable run identifier the ledger-guarded step is built with. */
    private static final String RUN_ID = "export-run";

    /** Job instance identifier for a directly constructed execution. */
    private static final long INSTANCE_ID = 1L;

    /** Job execution identifier for a directly constructed execution. */
    private static final long EXECUTION_ID = 1L;

    /** Business date supplying the dataset partition, in the separated layout. */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate("2022-07-18");

    /** A second business date, used to show which part of the output it reaches. */
    private static final BusinessDate OTHER_BUSINESS_DATE = new BusinessDate("2022-07-19");

    /**
     * Object key the export is published under for {@link #BUSINESS_DATE}.
     *
     * <p>Assumptions: the middle segment is {@code 2022071800} and not {@code 2022-07-18}, because
     * {@code BusinessDate.identifierPrefix()} renders the separated token compactly and appends two
     * trailing digits. Writing the separated form here would compile and fail on a key comparison.</p>
     */
    private static final String EXPORT_KEY = "export/2022071800/export.dat";

    /**
     * The 26-character stamp every deterministic case injects.
     *
     * <p>Assumptions: exactly {@link TimestampFormatter#TIMESTAMP_LENGTH} characters, laid out as the
     * overlay at {@code app/cpy/CVEXPORT.cpy:12-15} describes -- ten for the date, one for the
     * separator, fifteen for the time. A shorter or longer value is refused by the stamp's own
     * constructor rather than padded, so this constant is verified against that width below.</p>
     */
    private static final String FIXED_STAMP = "2022-07-18 10:15:30.000000";

    /** Wall clock the reference's own parameterless stamping falls back to. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2022-07-18T10:15:30.123456Z"), ZoneOffset.UTC);

    /** A second clock, used to show that an injected stamp displaces the clock entirely. */
    private static final Clock OTHER_CLOCK =
            Clock.fixed(Instant.parse("2030-01-31T23:59:59.999999Z"), ZoneOffset.UTC);

    /** Card number every fixture row carries, taken from the committed export fixture. */
    private static final String CARD_NUMBER = "4111111111111111";

    /** Declared record length of {@code EXPORT-RECORD}, from {@code app/cpy/CVEXPORT.cpy:5}. */
    private static final int RECORD_LENGTH = 500;

    /** Declared width of {@code EXPORT-RECORD-DATA}, from {@code app/cpy/CVEXPORT.cpy:19}. */
    private static final int PAYLOAD_LENGTH = 460;

    /** Zero-based offset of {@code EXPORT-RECORD-DATA}, and so the width of the common prefix. */
    private static final int PAYLOAD_OFFSET = 40;

    /** Zero-based offset of {@code EXPORT-SEQUENCE-NUM}, from {@code app/cpy/CVEXPORT.cpy:16}. */
    private static final int SEQUENCE_OFFSET = 27;

    /** Digit positions {@code EXPORT-SEQUENCE-NUM} declares, being {@code PIC 9(9)}. */
    private static final int SEQUENCE_DIGITS = 9;

    /** Byte width a four-digit-or-more binary halfword pair occupies for nine digit positions. */
    private static final int SEQUENCE_WIDTH = 4;

    /** Integer digit positions of every {@code PIC S9(10)V99} field in the account view. */
    private static final int MONEY_INT_DIGITS = 10;

    /** Decimal digit positions of every money field in this record, and their required scale. */
    private static final int MONEY_DEC_DIGITS = 2;

    /** Classpath location of the committed account master fixture. */
    private static final String ACCOUNT_FIXTURE = "/fixtures/export/happy_path/acctdata.txt";

    /** Declared record length of the account master, from {@code app/cpy/CVACT01Y.cpy}. */
    private static final int ACCOUNT_MASTER_LENGTH = 300;

    /** Shared-kernel layout name of the account master, whose fields the fixture is decoded with. */
    private static final String ACCOUNT_MASTER_LAYOUT = "ACCOUNT";

    /**
     * Number of generation-dataset families the baseline defines, and which this artefact is not one of.
     *
     * <p>Assumptions: TEN and not six. Six are defined at {@code app/jcl/DEFGDGB.jcl:25-57}, three more
     * at {@code app/jcl/DEFGDGD.jcl:28-76} and the tenth at {@code app/jcl/DALYREJS.jcl:24-26}. The
     * figure is asserted alongside the absence of the export dataset so that a family added or removed
     * makes this case fail rather than letting the absence assertion pass over a changed set.</p>
     */
    private static final int GENERATION_FAMILY_COUNT = 10;

    /** The five masters, stubbed per case. */
    private final CustomerRepository customers = mock(CustomerRepository.class);

    /** The account master, emitted as record type {@code 'A'}. */
    private final AccountRepository accounts = mock(AccountRepository.class);

    /** The card cross-reference table, emitted as record type {@code 'X'}. */
    private final CardXrefRepository crossReferences = mock(CardXrefRepository.class);

    /** The transaction master, emitted as record type {@code 'T'}. */
    private final TransactionRepository transactions = mock(TransactionRepository.class);

    /** The card master, emitted as record type {@code 'D'}. */
    private final CardRepository cards = mock(CardRepository.class);

    /** The object store the dataset is published to. */
    private final S3Client objectStore = mock(S3Client.class);

    /**
     * Job repository the launched executions are registered against.
     *
     * <p>Assumptions: the resourceless repository is used rather than a stub, because the framework
     * writes to it during a launch and a stub would answer nothing where the step expects its own
     * execution back. It keeps no state beyond the run, which is what lets a case launch twice.</p>
     */
    private final ResourcelessJobRepository jobRepository = new ResourcelessJobRepository();

    /**
     * Bytes of every payload published through {@link #objectStore}, snapshotted at put time.
     *
     * <p>Assumptions: the payload is copied DURING the put and not read from the captured request
     * afterwards, because the job stages its dataset in a temporary file and deletes that file in a
     * finally block before returning -- so a body read after the call refers to a path that no longer
     * exists. Reading during the call is also the more faithful stub: a real client consumes the body
     * for the duration of the request and not beyond it.</p>
     */
    private final List<byte[]> published = new ArrayList<>();

    /**
     * Makes the object-store stub consume each body the way a real client would.
     *
     * @throws IOException if a published body cannot be read, which would mean the job under test
     *     supplied a stream no client could send
     */
    @BeforeEach
    void consumeEveryPublishedBody() throws IOException {
        when(this.objectStore.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(call -> {
                    RequestBody body = call.getArgument(1);
                    try (InputStream stream = body.contentStreamProvider().newStream()) {
                        this.published.add(stream.readAllBytes());
                    }
                    return null;
                });
    }

    /**
     * Stubs all five masters empty, so a case populates only the sources it is about.
     *
     * <p>Assumptions: an EMPTY stream is stated for every source rather than left unstubbed, because
     * an unstubbed repository answers {@code null} and the walk then fails on a null stream with a
     * diagnostic about the stub instead of about the case's own subject. Stating every absence also
     * keeps each case's expected record count derivable from what that case stubbed.</p>
     */
    private void stubEverySourceEmpty() {
        // WHY : Assumptions: the answer-first form is used here and NOT the call-first form, because a
        //       case that has already stubbed one of these finders to raise -- the hard-failure case
        //       does -- would otherwise trigger that very fault while re-stubbing, since the call-first
        //       form has to invoke the method to record the stub. This helper is re-callable within one
        //       case for that reason, which is what lets a case exercise the body twice.
        doReturn(Stream.empty()).when(this.customers).findAllByOrderByCustomerIdAsc();
        doReturn(Stream.empty()).when(this.accounts).findAllByOrderByAccountIdAsc();
        doReturn(Stream.empty()).when(this.crossReferences).findAllByOrderByCardNumAsc();
        doReturn(Stream.empty()).when(this.transactions).findAllByOrderByTransactionIdAsc();
        doReturn(Stream.empty()).when(this.cards).findAllByOrderByCardNumAsc();
    }

    /**
     * Stubs the account master to raise, so a case can observe a fault mid-phase.
     *
     * <p>Assumptions: the answer-first form is used for the same reason the helper above states -- the
     * call-first form would have to invoke the finder to record the stub, and the second invocation
     * would raise while stubbing rather than while running.</p>
     */
    private void stubAccountMasterUnreadable() {
        doThrow(new IllegalStateException("the account master is unreadable"))
                .when(this.accounts).findAllByOrderByAccountIdAsc();
    }

    /**
     * Stubs all five masters with TWO rows each, in each master's own key order.
     *
     * <p>Assumptions: two rows per type and not one, and that is what several assertions below depend
     * on. With one row per type a sequence counter restarted at every type boundary still yields five
     * records whose numbers rise, and five phases interleaved record-by-record still yields the five
     * discriminators in order -- so both defects are invisible. Two rows per type makes the expected
     * discriminator run {@code C C A A X X T T D D} and the expected sequence run one through ten, and
     * neither defect can reproduce either.</p>
     */
    private void stubTwoRowsPerType() {
        when(this.customers.findAllByOrderByCustomerIdAsc())
                .thenReturn(Stream.of(customer(1L), customer(2L)));
        when(this.accounts.findAllByOrderByAccountIdAsc())
                .thenReturn(Stream.of(account(1L), account(2L)));
        when(this.crossReferences.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(
                new CardXref(CARD_NUMBER, 1L, 1L),
                new CardXref("4111111111111112", 2L, 2L)));
        when(this.transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.of(
                transaction("0000000000000001"), transaction("0000000000000002")));
        when(this.cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(
                card(CARD_NUMBER, 1L), card("4111111111111112", 2L)));
    }

    /**
     * Runs the export body once with an explicit stamp, so the emitted bytes are reproducible.
     *
     * @param stamp the run-constant prefix values applied to every record; must not be {@code null}
     * @param businessDate the date naming the dataset partition; must not be {@code null}
     * @return the graded return code the body handed back; never {@code null}
     */
    private BatchReturnCode runExport(ExportJob.ExportStamp stamp, BusinessDate businessDate) {
        return ExportJob.writeExport(businessDate, stamp, this.customers, this.accounts,
                this.crossReferences, this.transactions, this.cards, this.objectStore, BUCKET);
    }

    /**
     * Runs the export body once with the reference's own parameterless stamping.
     *
     * @param clock the time source the stamp is taken from, as the reference takes it from the system
     *     clock at {@code app/cbl/CBEXPORT.cbl:175-176}; must not be {@code null}
     * @return the graded return code the body handed back; never {@code null}
     */
    private BatchReturnCode runExportFromClock(Clock clock) {
        return ExportJob.writeExport(BUSINESS_DATE, this.customers, this.accounts,
                this.crossReferences, this.transactions, this.cards, this.objectStore, BUCKET, clock);
    }

    /**
     * Returns the single dataset published by the run under assertion.
     *
     * @return the published bytes; never {@code null}
     */
    private byte[] dataset() {
        assertThat(this.published)
                .as("one object is published per export run, so the artefact is all or nothing")
                .hasSize(1);
        return this.published.get(0);
    }

    /**
     * Captures every put request the object store received.
     *
     * @return the captured requests in call order; never {@code null}
     */
    private List<PutObjectRequest> puts() {
        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(this.objectStore, org.mockito.Mockito.atLeast(0))
                .putObject(captor.capture(), any(RequestBody.class));
        return captor.getAllValues();
    }

    /**
     * Extracts one whole record image from a dataset by its position in write order.
     *
     * @param dataset the published dataset, whose length is a whole multiple of the record length
     * @param ordinal the zero-based position of the wanted record in write order, so {@code 0} is the
     *     first record the run emitted
     * @return exactly {@link #RECORD_LENGTH} bytes; never {@code null}
     */
    private static byte[] recordAt(byte[] dataset, int ordinal) {
        return Arrays.copyOfRange(dataset, ordinal * RECORD_LENGTH, (ordinal + 1) * RECORD_LENGTH);
    }

    /**
     * Extracts the 460-byte payload span of one record image.
     *
     * @param record one {@link #RECORD_LENGTH}-byte record image; must not be {@code null}
     * @return the payload span, being the bytes from {@link #PAYLOAD_OFFSET} to the record's end
     */
    private static byte[] payloadOf(byte[] record) {
        return Arrays.copyOfRange(record, PAYLOAD_OFFSET, RECORD_LENGTH);
    }

    /**
     * Reads a character span of one record image positionally, bypassing the decoder.
     *
     * <p>Assumptions: the span is read as US-ASCII from the raw bytes rather than through the shared
     * codec, so an assertion built on it depends on the emitted BYTES and not on the decoder agreeing
     * with the encoder. A decoded assertion passes when both halves share one wrong offset.</p>
     *
     * @param record one {@link #RECORD_LENGTH}-byte record image; must not be {@code null}
     * @param offset the zero-based byte offset the span starts at, as the copybook declares it
     * @param width the number of bytes the span occupies, as the copybook declares it
     * @return the span decoded as US-ASCII text, blanks included; never {@code null}
     */
    private static String textAt(byte[] record, int offset, int width) {
        return new String(record, offset, width, StandardCharsets.US_ASCII);
    }

    /**
     * Reads the discriminator byte of every record in a dataset, in write order.
     *
     * @param dataset the published dataset; must not be {@code null}
     * @return one character per record, taken from the record's first byte; never {@code null}
     */
    private static List<Character> discriminatorsOf(byte[] dataset) {
        List<Character> discriminators = new ArrayList<>();
        for (int offset = 0; offset < dataset.length; offset += RECORD_LENGTH) {
            discriminators.add((char) (dataset[offset] & 0xFF));
        }
        return discriminators;
    }

    /**
     * Reads the sequence number of every record in a dataset, in write order.
     *
     * @param dataset the published dataset; must not be {@code null}
     * @return one sequence number per record, in the order the records were written; never
     *     {@code null}
     */
    private static List<Long> sequenceNumbersOf(byte[] dataset) {
        List<Long> sequences = new ArrayList<>();
        for (int ordinal = 0; ordinal * RECORD_LENGTH < dataset.length; ordinal++) {
            sequences.add(ExportRecordMapper.decodePrefix(recordAt(dataset, ordinal))
                    .sequenceNumber());
        }
        return sequences;
    }

    /**
     * Builds a customer row carrying a value in every field the customer view renders.
     *
     * @param customerId the nine-digit identifier, which the view carries as a binary fullword
     * @return the customer row; never {@code null}
     */
    private static Customer customer(long customerId) {
        return new Customer(customerId, "AVA", "B", "STONE", "1 HIGH ST", "APT 2", "BLOCK C",
                "NY", "USA", "10001", "2125550100", "2125550101", LocalDate.of(1985, 4, 2),
                "9876543210", "Y", (short) 742);
    }

    /**
     * Builds an account row whose money fields are all positive.
     *
     * @param accountId the eleven-digit identifier the account view carries as display digits
     * @return the account row; never {@code null}
     */
    private static Account account(long accountId) {
        return account(accountId, new BigDecimal("100.00"), new BigDecimal("9000.00"),
                new BigDecimal("25.00"));
    }

    /**
     * Builds an account row with the three mixed-usage money fields set explicitly.
     *
     * <p>Assumptions: these three of the account view's five money fields are the ones worth varying,
     * because they are the three STORAGE REGIMES the one picture clause takes in this record --
     * {@code EXP-ACCT-CURR-BAL} is packed at {@code app/cpy/CVEXPORT.cpy:50},
     * {@code EXP-ACCT-CREDIT-LIMIT} is display zoned at {@code :51} and
     * {@code EXP-ACCT-CURR-CYC-DEBIT} is binary at {@code :57}. Setting one value for all five would
     * leave a case unable to tell which regime produced which byte.</p>
     *
     * @param accountId the eleven-digit identifier the account view carries as display digits
     * @param currentBalance the value written into the PACKED span at payload offset 12
     * @param creditLimit the value written into the DISPLAY ZONED span at payload offset 19
     * @param cycleDebit the value written into the BINARY span at payload offset 80
     * @return the account row; never {@code null}
     */
    private static Account account(long accountId, BigDecimal currentBalance,
            BigDecimal creditLimit, BigDecimal cycleDebit) {
        return new Account(accountId, "Y", currentBalance, creditLimit, new BigDecimal("500.00"),
                LocalDate.of(2013, 6, 19), LocalDate.of(2024, 8, 11), LocalDate.of(2024, 8, 11),
                new BigDecimal("0.00"), cycleDebit, "12345", "ZG");
    }

    /**
     * Builds a card row carrying a value in every field the card view renders.
     *
     * @param cardNumber the sixteen-character primary account number, which is the master's key
     * @param accountId the owning account identifier, carried as a binary field by the card view
     * @return the card row; never {@code null}
     */
    private static Card card(String cardNumber, long accountId) {
        return new Card(cardNumber, accountId, "AVA B STONE", LocalDate.of(2027, 12, 31), "Y");
    }

    /**
     * Builds a transaction row carrying a value in every field the transaction view renders.
     *
     * @param transactionId the sixteen-character identifier, which is the master's key
     * @return the transaction row; never {@code null}
     */
    private static Transaction transaction(String transactionId) {
        Transaction row = new Transaction(transactionId);
        row.setTypeCd("01");
        row.setCategoryCd("0005");
        row.setSource("POS");
        row.setDescription("purchase");
        row.setAmount(new BigDecimal("10.00"));
        row.setMerchantId(999L);
        row.setMerchantName("merchant");
        row.setMerchantCity("city");
        row.setMerchantZip("12345");
        row.setCardNum(CARD_NUMBER);
        row.setOrigTs(LocalDateTime.of(2022, 7, 18, 10, 15, 30));
        row.setProcTs(LocalDateTime.of(2022, 7, 18, 10, 15, 30));
        return row;
    }

    /**
     * Assembles a context holding this job configuration and a stand-in for every collaborator.
     *
     * <p>Assumptions: the container resolves a job by iterating the registered {@link Job} beans and
     * comparing {@code getName()} against the {@code --job=} argument, never by importing this class,
     * so the token has to be observed through an assembled registry rather than read off a constant.
     * Every collaborator is a stub because building a job bean reads no data -- it assembles a step and
     * returns it -- which keeps this case independent of a database while still exercising the context
     * assembly that is its whole subject.</p>
     *
     * @return the configured runner; never {@code null}
     */
    private static ApplicationContextRunner registryRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(BatchConfig.class, ExportJob.class)
                .withBean(BatchStepLedger.class, () -> mock(BatchStepLedger.class))
                .withBean(JobRepository.class, () -> mock(JobRepository.class))
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(CustomerRepository.class, () -> mock(CustomerRepository.class))
                .withBean(AccountRepository.class, () -> mock(AccountRepository.class))
                .withBean(CardXrefRepository.class, () -> mock(CardXrefRepository.class))
                .withBean(TransactionRepository.class, () -> mock(TransactionRepository.class))
                .withBean(CardRepository.class, () -> mock(CardRepository.class))
                .withBean(S3Client.class, () -> mock(S3Client.class))
                .withPropertyValues("carddemo.dataset.bucket=" + BUCKET,
                        BatchApplication.RUN_ID_VARIABLE + "=" + RUN_ID);
    }

    /**
     * The {@code export} token resolves through the registry to this job, and to nothing else.
     *
     * <p>Pins {@code app/jcl/CBEXPORT.jcl:43}, {@code //STEP02 EXEC PGM=CBEXPORT}: the reference is
     * selected by naming its program, and the migrated equivalent is selected by naming its token.</p>
     *
     * <p>Assumptions: the token is compared against {@link BatchJobName#EXPORT} rather than against a
     * literal spelled here, because that enumeration is the vocabulary the entry point validates an
     * argument against; a literal in this case file could agree with the job and disagree with the
     * vocabulary, which is a container that starts and then finds no job.</p>
     */
    @Test
    @DisplayName("the export token resolves through the registry to this job")
    void theExportTokenResolvesThroughTheRegistry() {
        registryRunner().run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getBeansOfType(Job.class).values())
                    .as("this configuration registers exactly one job, under exactly one name")
                    .singleElement()
                    .extracting(Job::getName)
                    .isEqualTo(BatchJobName.EXPORT.token());
        });

        assertThat(ExportJob.JOB_NAME)
                .as("the registered name is READ from the vocabulary, never retyped")
                .isEqualTo(BatchJobName.EXPORT.token());
        assertThat(ExportJob.STEP_NAME)
                .as("the persisted step name is the job token plus the vocabulary's step suffix")
                .isEqualTo(ExportJob.JOB_NAME + BatchJobName.STEP_NAME_SUFFIX)
                .endsWith(BatchJobName.STEP_NAME_SUFFIX);
        // WHY : Refactoring Rationale: this used to require the step name to EQUAL the job token, on
        //       the ground that a single-step job has nothing to distinguish. It was the only place
        //       the bare spelling was pinned, so it held two of the seven jobs outside the shape the
        //       other five share -- and a ledger selection over the chain's step rows, which the
        //       suffix invites spelling as LIKE '%-step', returned five of seven with nothing
        //       reporting the omission. The suffix is asserted as well as the composition, because
        //       the composition alone would still pass if the suffix constant itself were emptied.
        assertThat(BatchJobName.forStepName(ExportJob.STEP_NAME))
                .as("the suffixed step name still resolves back to the job that wrote it")
                .isEqualTo(BatchJobName.EXPORT);
    }

    /**
     * Every emitted record is exactly 500 bytes and nothing at all separates one from the next.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:5}, which states a total record length of 500 bytes, and
     * {@code app/jcl/CBEXPORT.jcl:33}, whose {@code RECORDSIZE(500 500)} fixes the same figure as both
     * the average and the maximum, so the records are fixed length rather than merely bounded.</p>
     *
     * <p>Assumptions: absence of a delimiter is asserted POSITIVELY, by requiring the byte at offset
     * 500 to be the discriminator of the second record rather than by requiring the total length alone
     * to divide evenly. A one-byte separator after every record would still leave the total divisible
     * by an apparent stride, so a length-only check can be satisfied by a delimited file.</p>
     */
    @Test
    @DisplayName("every emitted record is 500 bytes with no delimiter between records")
    void everyRecordIsFiveHundredBytesWithNothingBetweenThem() {
        stubTwoRowsPerType();

        assertThat(runExport(fixedStamp(), BUSINESS_DATE)).isEqualTo(BatchReturnCode.CLEAN);

        byte[] dataset = dataset();
        assertThat(ExportRecordMapper.recordLayout().reclen())
                .as("the descriptor the encoder works from carries the copybook's own 500")
                .isEqualTo(RECORD_LENGTH);
        assertThat(dataset)
                .as("ten rows across five masters, at 500 bytes each and not one byte more")
                .hasSize(10 * RECORD_LENGTH);
        assertThat((char) (dataset[RECORD_LENGTH] & 0xFF))
                .as("the byte immediately after the first record is the second record's own"
                        + " discriminator, so no separator was written between them")
                .isEqualTo(RecordType.CUSTOMER.discriminator());
    }

    /**
     * Each prefix field sits at the offset and width its copybook line declares.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:10-19} field by field: {@code EXPORT-REC-TYPE X(1)} at 0,
     * {@code EXPORT-TIMESTAMP X(26)} at 1, {@code EXPORT-SEQUENCE-NUM 9(9) COMP} at 27,
     * {@code EXPORT-BRANCH-ID X(4)} at 31, {@code EXPORT-REGION-CODE X(5)} at 35 and
     * {@code EXPORT-RECORD-DATA X(460)} at 40.</p>
     *
     * <p>Assumptions: the arithmetic is asserted TERM BY TERM -- each field against its own offset and
     * width, then the five widths against the payload offset, then prefix plus payload against the
     * record length -- rather than by checking the 500 alone. Two width errors that cancel keep the
     * total at 500, and a total-only assertion reports that a record is wrong without saying which
     * field moved; the per-term form localises the failure to the field that changed.</p>
     */
    @Test
    @DisplayName("each prefix field sits at its declared offset and width, and 40 plus 460 is 500")
    void eachPrefixFieldSitsWhereTheCopybookDeclaresIt() {
        CopybookLayout.RecordSpec spec = ExportRecordMapper.recordLayout();

        assertFieldSpan(spec, "EXPORT-REC-TYPE", 0, 1);
        assertFieldSpan(spec, "EXPORT-TIMESTAMP", 1, TimestampFormatter.TIMESTAMP_LENGTH);
        assertFieldSpan(spec, "EXPORT-SEQUENCE-NUM", SEQUENCE_OFFSET, SEQUENCE_WIDTH);
        assertFieldSpan(spec, "EXPORT-BRANCH-ID", 31, 4);
        assertFieldSpan(spec, "EXPORT-REGION-CODE", 35, 5);
        assertFieldSpan(spec, "EXPORT-RECORD-DATA", PAYLOAD_OFFSET, PAYLOAD_LENGTH);

        int prefixWidth = spec.field("EXPORT-REC-TYPE").length()
                + spec.field("EXPORT-TIMESTAMP").length()
                + spec.field("EXPORT-SEQUENCE-NUM").length()
                + spec.field("EXPORT-BRANCH-ID").length()
                + spec.field("EXPORT-REGION-CODE").length();
        assertThat(prefixWidth)
                .as("1 + 26 + 4 + 4 + 5 is the 40 bytes the payload starts after")
                .isEqualTo(PAYLOAD_OFFSET)
                .isEqualTo(spec.field("EXPORT-RECORD-DATA").start());
        assertThat(prefixWidth + spec.field("EXPORT-RECORD-DATA").length())
                .as("40 + 460 is the declared record length, so the record closes exactly")
                .isEqualTo(spec.reclen());
    }

    /**
     * The prefix of a real emitted record carries each value in its own declared span.
     *
     * <p>Pins the same {@code app/cpy/CVEXPORT.cpy:10-19} spans as the descriptor case above, this time
     * against the bytes a run actually produced, plus {@code app/cbl/CBEXPORT.cbl:274-279}, the six
     * statements the reference repeats at the head of every one of its five blocks.</p>
     *
     * <p>Assumptions: the spans are read positionally out of the raw record rather than through the
     * decoder, because the decoder and the encoder share one descriptor -- so a decoded assertion
     * passes even when both halves agree on a wrong offset. The two cases are complementary and neither
     * replaces the other: the descriptor case fixes the geometry, this one fixes that the geometry is
     * what reached the file.</p>
     */
    @Test
    @DisplayName("a real record carries the discriminator, stamp, branch and region in their spans")
    void aRealRecordCarriesEachPrefixValueInItsOwnSpan() {
        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));

        runExport(fixedStamp(), BUSINESS_DATE);

        byte[] record = recordAt(dataset(), 0);
        assertThat(textAt(record, 0, 1))
                .as("EXPORT-REC-TYPE occupies the single byte at offset zero")
                .isEqualTo(String.valueOf(RecordType.ACCOUNT.discriminator()));
        assertThat(textAt(record, 1, TimestampFormatter.TIMESTAMP_LENGTH))
                .as("EXPORT-TIMESTAMP occupies the 26 bytes from offset one")
                .isEqualTo(FIXED_STAMP);
        assertThat(textAt(record, 31, 4))
                .as("EXPORT-BRANCH-ID occupies the four bytes from offset 31")
                .isEqualTo(ExportJob.DEFAULT_BRANCH_ID);
        assertThat(textAt(record, 35, 5))
                .as("EXPORT-REGION-CODE occupies the five bytes from offset 35")
                .isEqualTo(ExportJob.DEFAULT_REGION_CODE);
        assertThat(payloadOf(record))
                .as("EXPORT-RECORD-DATA is the 460 bytes from offset 40")
                .hasSize(PAYLOAD_LENGTH);
    }

    /**
     * Every one of the five payload views closes at exactly 460 bytes with no gap and no overlap.
     *
     * <p>Pins the five {@code REDEFINES} branches of {@code app/cpy/CVEXPORT.cpy:19} -- the customer
     * branch at {@code :24}, the account branch at {@code :47}, the transaction branch at {@code :65},
     * the cross-reference branch at {@code :84} and the card branch at {@code :93} -- each of which
     * redefines the same 460-byte span and must therefore sum to it.</p>
     *
     * <p>Assumptions: contiguity is walked field by field rather than summed, because a sum is
     * satisfied by a layout with a gap and an overlap that cancel. The trailing pad of each branch is
     * what makes the walk decide the {@code USAGE} question: the cross-reference branch's 427-byte pad
     * at {@code :88} closes only if {@code EXP-XREF-ACCT-ID} is eight binary bytes rather than eleven
     * display characters, so a wrong storage assumption fails here rather than at the first field that
     * reads oddly.</p>
     *
     * @param view the payload view under assertion, supplied once per constant of the closed set of
     *     five so that no branch can be left unchecked
     */
    @ParameterizedTest
    @EnumSource(RecordType.class)
    @DisplayName("every payload view is contiguous from zero and closes at 460 bytes")
    void everyPayloadViewClosesAtFourHundredAndSixty(RecordType view) {
        CopybookLayout.RecordSpec spec = ExportRecordMapper.viewLayout(view);

        assertThat(spec.reclen())
                .as("the %s view redefines EXPORT-RECORD-DATA, so it is 460 bytes", view)
                .isEqualTo(PAYLOAD_LENGTH);

        int cursor = 0;
        for (CopybookLayout.FieldSpec field : spec.fields()) {
            assertThat(field.start())
                    .as("%s starts where its predecessor ended, with no gap and no overlap",
                            field.describe())
                    .isEqualTo(cursor);
            cursor = field.end();
        }
        assertThat(cursor)
                .as("the %s view's own fields, its trailing pad included, close it at 460", view)
                .isEqualTo(PAYLOAD_LENGTH);
    }

    /**
     * The sequence number is four BINARY bytes inside the record, not nine display digits.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:16}, {@code EXPORT-SEQUENCE-NUM PIC 9(9) COMP}. The
     * {@code USAGE} is what fixes the width: nine digit positions in binary occupy a four-byte
     * fullword, whereas the same nine positions in the default display usage would occupy nine
     * bytes.</p>
     *
     * <p>Assumptions: rendering the number as text is the plausible mistake and it is not a local one.
     * Nine bytes where four belong pushes {@code EXPORT-BRANCH-ID}, {@code EXPORT-REGION-CODE} and the
     * whole 460-byte payload five bytes further on, and the record still reaches 500 bytes because the pad
     * absorbs the shift -- so the record length cannot detect it and this assertion has to. The four
     * bytes are also compared as bytes, {@code 00 00 00 01} for the first record, because a decode
     * through the same descriptor the encode used would agree with a wrong width.</p>
     */
    @Test
    @DisplayName("the sequence number is a four-byte binary field at offset 27")
    void theSequenceNumberIsFourBinaryBytesAndNotNineDigits() {
        CopybookLayout.FieldSpec sequence =
                ExportRecordMapper.recordLayout().field("EXPORT-SEQUENCE-NUM");

        assertThat(sequence.kind())
                .as("COMP is binary storage, so the field is not a character span")
                .isEqualTo(CopybookLayout.Kind.BINARY);
        assertThat(sequence.intDigits())
                .as("nine digit positions, exactly as PIC 9(9) declares")
                .isEqualTo(SEQUENCE_DIGITS);
        assertThat(sequence.signed())
                .as("PIC 9(9) carries no S, so the field is unsigned")
                .isFalse();
        assertThat(sequence.length())
                .as("nine binary digit positions occupy a four-byte fullword, never nine bytes")
                .isEqualTo(SEQUENCE_WIDTH)
                .isEqualTo(CopybookLayout.binaryWidth(SEQUENCE_DIGITS, 0))
                .isNotEqualTo(SEQUENCE_DIGITS);

        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        runExport(fixedStamp(), BUSINESS_DATE);

        byte[] record = recordAt(dataset(), 0);
        assertThat(Arrays.copyOfRange(record, SEQUENCE_OFFSET, SEQUENCE_OFFSET + SEQUENCE_WIDTH))
                .as("the first record's key is the fullword one, in network byte order")
                .containsExactly(0x00, 0x00, 0x00, 0x01);
        assertThat(PackedDecimalCodec.decodeBinary(record, SEQUENCE_OFFSET, SEQUENCE_DIGITS, 0,
                        false))
                .as("the sanctioned binary decoder reads the same fullword back as one")
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    /**
     * One gapless sequence spans all five record types and never restarts at a type boundary.
     *
     * <p>Pins {@code app/cbl/CBEXPORT.cbl:123}, which declares {@code WS-SEQUENCE-COUNTER} ONCE, and
     * the five blocks that each increment that same counter at {@code :276}, {@code :345},
     * {@code :409}, {@code :464} and {@code :529}.</p>
     *
     * <p>Assumptions: one sequence spanning every type is what the layout itself encodes, because
     * {@code EXPORT-SEQUENCE-NUM} lives in the SHARED PREFIX at {@code app/cpy/CVEXPORT.cpy:16} and in
     * none of the five {@code REDEFINES} sub-layouts. A per-type counter would have been expressible
     * only by putting a sequence field inside each branch, which the copybook does not do -- so the
     * record shape and the counter declaration agree, and this case holds them to it.</p>
     *
     * <p>Assumptions: a per-type restart is the most plausible way to get this wrong, and two rows per
     * type is the smallest input that catches it: a restart would produce one, two, one, two, ... which
     * is still monotonic within each type and is not the run of one through ten asserted here.</p>
     */
    @Test
    @DisplayName("one gapless sequence spans all five record types without restarting")
    void oneGaplessSequenceSpansEveryRecordType() {
        stubTwoRowsPerType();

        runExport(fixedStamp(), BUSINESS_DATE);

        assertThat(sequenceNumbersOf(dataset()))
                .as("one counter shared by five phases yields one through ten, gapless, in write"
                        + " order across every type boundary")
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
                .isSorted()
                .doesNotHaveDuplicates();
    }

    /**
     * Two runs over one input produce byte-identical datasets.
     *
     * <p>Pins {@code app/jcl/CBEXPORT.jcl:43}, which passes no {@code PARM}: the reference run takes
     * every input from its files and its clock, so reproducibility is a property the target adds rather
     * than one it inherits.</p>
     *
     * <p>Trade-offs: with no golden master for this stream -- {@code tests/golden/} holds no
     * {@code export} directory -- comparing two target runs against each other is the only verification
     * available, so it is asserted here as a contract rather than assumed. The compromise is that this
     * proves self-consistency and not fidelity; fidelity rests on the transcription this file cites
     * line by line.</p>
     */
    @Test
    @DisplayName("two runs over the same input publish byte-identical datasets")
    void twoRunsOverOneInputProduceIdenticalDatasets() {
        stubTwoRowsPerType();
        runExport(fixedStamp(), BUSINESS_DATE);
        stubTwoRowsPerType();
        runExport(fixedStamp(), BUSINESS_DATE);

        assertThat(this.published).hasSize(2);
        assertThat(this.published.get(1))
                .as("nothing in the record derives from anything but the input and the fixed stamp")
                .isEqualTo(this.published.get(0));
    }

    /**
     * The sequence number is a real field of the emitted record and is that record's retrieval key.
     *
     * <p>Pins {@code app/cbl/CBEXPORT.cbl:68} against {@code app/cpy/CVEXPORT.cpy:16} -- divergence
     * D-1, stated in full on this class.</p>
     *
     * <p>Refactoring Rationale: what was wrong with the reference is not its intent but its placement.
     * It asks for the file to be keyed on {@code EXPORT-SEQUENCE-NUM} while that name resolves only in
     * working storage, because its file record is the unstructured
     * {@code 01 EXPORT-OUTPUT-RECORD PIC X(500).} at {@code app/cbl/CBEXPORT.cbl:92}; a key outside the
     * record cannot key the record, which is why {@code cobc --std=ibm-strict} rejects the program and
     * why no flag repairs it. The migrated form puts the number INSIDE the record at the offset the
     * copybook gives it and declares that span as the descriptor's retrieval key, so the emitted stream
     * is genuinely keyed and re-readable. The COBOL is left exactly as it is, per the reference-only
     * policy over {@code app/**}, and the divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Assumptions: {@code app/jcl/CBEXPORT.jcl:32} corroborates the key geometry independently, with
     * {@code KEYS(4 28)} defining a four-byte key -- the same four bytes asserted here -- though its
     * offset is stated one-based-adjacent to the zero-based 27 the copybook arithmetic gives. The
     * copybook is normative, so the number is read from the copybook and the job control is cited as
     * corroboration of the WIDTH only.</p>
     */
    @Test
    @DisplayName("the sequence field is the record's own key, so the stream is genuinely keyed")
    void theSequenceFieldMakesTheEmittedStreamGenuinelyKeyed() {
        CopybookLayout.RecordSpec spec = ExportRecordMapper.recordLayout();

        assertThat(spec.hasRetrievalKey())
                .as("the emitted record declares a key, unlike the reference's unstructured one")
                .isTrue();
        assertThat(spec.keyOffset())
                .as("the key begins where EXPORT-SEQUENCE-NUM begins, at zero-based 27")
                .isEqualTo(SEQUENCE_OFFSET)
                .isEqualTo(spec.field("EXPORT-SEQUENCE-NUM").start());
        assertThat(spec.keyLength())
                .as("the key is the whole of that field and no more")
                .isEqualTo(SEQUENCE_WIDTH)
                .isEqualTo(spec.field("EXPORT-SEQUENCE-NUM").length());
        assertThat(spec.keyOffset() + spec.keyLength())
                .as("the key interval lies wholly inside the record, which is what D-1 lacked")
                .isLessThanOrEqualTo(spec.reclen());

        stubTwoRowsPerType();
        runExport(fixedStamp(), BUSINESS_DATE);

        assertThat(sequenceNumbersOf(dataset()))
                .as("every record carries a distinct key, so the stream can be read back by key")
                .doesNotHaveDuplicates()
                .hasSize(dataset().length / RECORD_LENGTH);
    }

    /**
     * Each view carries exactly the single discriminator character the reference moves for it.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:10} for the field and the five {@code MOVE} statements that
     * fill it: {@code 'C'} at {@code app/cbl/CBEXPORT.cbl:274}, {@code 'A'} at {@code :343},
     * {@code 'X'} at {@code :407}, {@code 'T'} at {@code :462} and {@code 'D'} at {@code :527}. The
     * one-to-one correspondence with the five master inputs is corroborated by
     * {@code app/jcl/CBEXPORT.jcl:49-57}, whose {@code CUSTFILE}, {@code ACCTFILE}, {@code XREFFILE},
     * {@code TRANSACT} and {@code CARDFILE} statements are declared in that same order.</p>
     *
     * <p>Assumptions: the card discriminator is {@code 'D'} and NOT {@code 'C'}, and the letter is not
     * the initial of the type name. {@code 'C'} is already taken by the customer view, and
     * {@code app/jcl/CBEXPORT.jcl:57} pairs {@code CARDFILE} with the fifth discriminator. A wrong
     * letter here is expensive rather than loud: the import dispatcher routes an unrecognised character
     * to its unknown-type handler, so a mislabelled record decodes as unknown instead of failing where
     * it was produced.</p>
     *
     * @param view the payload view under assertion, named so that all five are covered explicitly
     * @param expected the single character {@code EXPORT-REC-TYPE} must carry for that view, taken from
     *     the corresponding {@code MOVE} in the reference
     */
    @ParameterizedTest
    @CsvSource({"CUSTOMER,C", "ACCOUNT,A", "CARD_XREF,X", "TRANSACTION,T", "CARD,D"})
    @DisplayName("each view carries the single discriminator the reference moves for it")
    void eachViewCarriesTheDiscriminatorTheReferenceMoves(RecordType view, char expected) {
        assertThat(view.discriminator())
                .as("%s is written as the character the reference moves", view)
                .isEqualTo(expected);
        assertThat(RecordType.ofDiscriminator(expected))
                .as("and that character selects the same view on the way back in")
                .isSameAs(view);
    }

    /**
     * The five types are emitted in the order the reference's main flow performs them.
     *
     * <p>Pins {@code app/cbl/CBEXPORT.cbl:151-157}, the {@code PERFORM} sequence of
     * {@code 0000-MAIN-PROCESSING}: {@code 2000-EXPORT-CUSTOMERS}, {@code 3000-EXPORT-ACCOUNTS},
     * {@code 4000-EXPORT-XREFS}, {@code 5000-EXPORT-TRANSACTIONS}, then
     * {@code 5500-EXPORT-CARDS}.</p>
     *
     * <p>Assumptions: that order was READ from the main flow at those lines and not inferred from the
     * declaration order of the input files -- the {@code DD} statements at
     * {@code app/jcl/CBEXPORT.jcl:49-57} happen to agree, which makes them corroboration rather than
     * proof. The order is part of the output contract precisely because one counter is shared: emitting
     * the same set of rows in a different phase order gives every record a different sequence
     * number.</p>
     *
     * <p>Assumptions: the discriminators are read at the record stride from the raw bytes, and two rows
     * per type are stubbed, so a run that interleaved its phases record-by-record would produce
     * {@code C A X T D C A X T D} and fail here. A single row per type cannot distinguish the two.</p>
     */
    @Test
    @DisplayName("the emission order is customer, account, cross-reference, transaction, card")
    void theEmissionOrderIsTheOrderTheMainFlowPerforms() {
        stubTwoRowsPerType();

        runExport(fixedStamp(), BUSINESS_DATE);

        assertThat(discriminatorsOf(dataset()))
                .as("each phase runs to completion before the next begins, in main-flow order")
                .containsExactly('C', 'C', 'A', 'A', 'X', 'X', 'T', 'T', 'D', 'D');
    }

    /**
     * Each record's payload is read under its own view, and never under a neighbour's.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:24}, {@code :47}, {@code :65}, {@code :84} and {@code :93} --
     * the five {@code REDEFINES} branches that give one 460-byte span five incompatible meanings, so
     * that the discriminator at offset zero is the only thing that says which applies.</p>
     *
     * <p>Assumptions: a payload read under the wrong view decodes PLAUSIBLY rather than failing -- every
     * field it produces is the right shape and the wrong value -- which is why this is asserted as an
     * exclusivity property. For each record exactly one projection is populated and it is the one the
     * record's own discriminator selects; the other four are absent.</p>
     */
    @Test
    @DisplayName("each payload is decoded under its own view and no other")
    void eachPayloadIsDecodedUnderItsOwnView() {
        stubTwoRowsPerType();
        runExport(fixedStamp(), BUSINESS_DATE);

        byte[] dataset = dataset();
        for (int ordinal = 0; ordinal < dataset.length / RECORD_LENGTH; ordinal++) {
            byte[] record = recordAt(dataset, ordinal);
            RecordType view = ExportRecordMapper.recordTypeOf(record);
            ExportRecordMapper.ExportRecord decoded = ExportRecordMapper.decode(record);

            assertThat(decoded.prefix().recordType())
                    .as("record %s decodes under the view its own first byte names", ordinal)
                    .isSameAs(view);
            assertThat(populatedProjectionOf(decoded))
                    .as("record %s populates exactly the projection view %s selects", ordinal, view)
                    .isSameAs(selectedProjectionOf(decoded, view));
        }
    }

    /**
     * Within one type the records follow that master's key order, taken from an ordered read.
     *
     * <p>Pins {@code app/cbl/CBEXPORT.cbl:41-45}, which opens the account master
     * {@code ACCESS MODE IS SEQUENTIAL} over {@code ORGANIZATION IS INDEXED} with
     * {@code RECORD KEY IS ACCT-ID}, so the reference's rows arrive in key order and the migrated read
     * has to ask for that order explicitly.</p>
     *
     * <p>Assumptions: a relational read guarantees no order unless one is requested, so the ORDERED
     * finder being the one called is asserted alongside the emitted order. The single-row finder that
     * belongs to the accrual flow is asserted never to be called, because reaching rows one key at a
     * time would produce the same bytes for this fixture and a different access shape.</p>
     */
    @Test
    @DisplayName("within one type the records follow that master's key order")
    void withinOneTypeTheRecordsFollowTheirKeyOrder() {
        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc())
                .thenReturn(Stream.of(account(1L), account(2L), account(3L)));

        runExport(fixedStamp(), BUSINESS_DATE);

        byte[] dataset = dataset();
        List<Long> emitted = new ArrayList<>();
        for (int ordinal = 0; ordinal < dataset.length / RECORD_LENGTH; ordinal++) {
            emitted.add(ExportRecordMapper.decode(recordAt(dataset, ordinal)).account()
                    .getAccountId());
        }
        assertThat(emitted)
                .as("the stream's order is the emitted order, ascending by account identifier")
                .containsExactly(1L, 2L, 3L);

        verify(this.accounts, times(1)).findAllByOrderByAccountIdAsc();
        verify(this.accounts, never()).findByAccountId(any());
    }

    /**
     * Packed, zoned and binary money in ONE record all decode, negative values included.
     *
     * <p>Pins the mixed-usage trio of {@code app/cpy/CVEXPORT.cpy:50-57}: three fields declared with the
     * identical {@code PIC S9(10)V99} in three different storage regimes --
     * {@code EXP-ACCT-CURR-BAL COMP-3} at {@code :50}, {@code EXP-ACCT-CREDIT-LIMIT} in the default
     * display usage at {@code :51} and {@code EXP-ACCT-CURR-CYC-DEBIT COMP} at {@code :57}.</p>
     *
     * <p>Assumptions: each span is read with the codec its own {@code USAGE} demands, and the three are
     * routed per FIELD rather than per record, because no single decoder can read this record. The
     * sanctioned decoders are the shared kernel's, so nothing here re-implements one: the packed spans
     * go through {@code PackedDecimalCodec.decodePacked}, the display span through
     * {@code ZonedDecimalCodec.decode} and the binary span through
     * {@code PackedDecimalCodec.decodeBinary}. The fixture contract for this tree fixes that ruling at
     * its section 5.6, that physical width comes from {@code USAGE} and never from {@code PICTURE}.</p>
     *
     * <p>Assumptions: NEGATIVE values are exercised in all three regimes, and a positive-only fixture
     * would prove nothing about them. {@code tests/README.md} section 5.2 records that the default
     * ASCII sign convention silently corrupts negative balances -- which is why the reference suite
     * compiles with EBCDIC sign handling -- so sign handling is the failure mode that produces
     * plausible wrong money rather than an error. The committed export fixture is no help here: every
     * money value in {@code fixtures/export/happy_path/acctdata.txt} ends in the overpunch character for
     * a POSITIVE zero digit, so the negatives are constructed in code.</p>
     *
     * <p>Assumptions: every amount is a {@code BigDecimal} at scale two throughout, which is asserted
     * rather than assumed. Exactness is the whole reason the money path admits no inexact numeric type,
     * and the shared architecture rules assert that prohibition for the production tree.</p>
     */
    @Test
    @DisplayName("packed, zoned and binary money in one record all decode, negatives included")
    void packedZonedAndBinaryMoneyAllDecodeIncludingNegatives() {
        BigDecimal packedValue = new BigDecimal("-1234567890.12");
        BigDecimal zonedValue = new BigDecimal("-9876543210.98");
        BigDecimal binaryValue = new BigDecimal("-4321.09");
        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc())
                .thenReturn(Stream.of(account(1L, packedValue, zonedValue, binaryValue)));

        runExport(fixedStamp(), BUSINESS_DATE);

        byte[] payload = payloadOf(recordAt(dataset(), 0));
        CopybookLayout.RecordSpec view = ExportRecordMapper.viewLayout(RecordType.ACCOUNT);

        BigDecimal packed = PackedDecimalCodec.decodePacked(payload,
                view.field("EXP-ACCT-CURR-BAL").start(), MONEY_INT_DIGITS, MONEY_DEC_DIGITS, true);
        assertThat(packed)
                .as("the COMP-3 span at app/cpy/CVEXPORT.cpy:50 carries its sign in the low nibble")
                .isEqualByComparingTo(packedValue);
        assertThat(packed.scale())
                .as("V99 is two decimal positions, so the decoded scale is two")
                .isEqualTo(MONEY_DEC_DIGITS);

        CopybookLayout.FieldSpec zonedField = view.field("EXP-ACCT-CREDIT-LIMIT");
        BigDecimal zoned = ZonedDecimalCodec.decode(
                new String(payload, zonedField.start(), zonedField.length(),
                        StandardCharsets.US_ASCII),
                MONEY_INT_DIGITS, MONEY_DEC_DIGITS, true);
        assertThat(zoned)
                .as("the display span at app/cpy/CVEXPORT.cpy:51 carries its sign as an overpunch")
                .isEqualByComparingTo(zonedValue);
        assertThat(zoned.scale()).isEqualTo(MONEY_DEC_DIGITS);

        BigDecimal binary = PackedDecimalCodec.decodeBinary(payload,
                view.field("EXP-ACCT-CURR-CYC-DEBIT").start(), MONEY_INT_DIGITS, MONEY_DEC_DIGITS,
                true);
        assertThat(binary)
                .as("the COMP span at app/cpy/CVEXPORT.cpy:57 carries its sign in two's complement")
                .isEqualByComparingTo(binaryValue);
        assertThat(binary.scale()).isEqualTo(MONEY_DEC_DIGITS);

        assertThat(ExportRecordMapper.decode(recordAt(dataset(), 0)).account())
                .as("and the record projects back onto the entity with all three signs intact")
                .extracting(Account::getCurrBal, Account::getCreditLimit, Account::getCurrCycDebit)
                .containsExactly(packedValue, zonedValue, binaryValue);
    }

    /**
     * Reading a packed span as though it were display fails, so the routing is load-bearing.
     *
     * <p>Pins the same trio at {@code app/cpy/CVEXPORT.cpy:50-51}: two fields, one {@code PICTURE}, two
     * storage regimes seven bytes and twelve bytes wide.</p>
     *
     * <p>Assumptions: this is the assertion that makes the routing case above bite. Without it, a
     * revision that read every money span through one decoder could still satisfy a value comparison on
     * the display span alone; here the packed span is deliberately handed to the display decoder and the
     * refusal is required. The shared codec reads a character field under a strict charset and verifies
     * the slice is byte-reversible, and a packed span holds nibble pairs -- the digits 90 are the single
     * byte {@code 0x90} -- so the attempt cannot succeed on valid packed input.</p>
     */
    @Test
    @DisplayName("reading a packed span through the display decoder is refused")
    void readingAPackedSpanAsDisplayIsRefused() {
        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(
                account(1L, new BigDecimal("-1234567890.12"), new BigDecimal("9000.00"),
                        new BigDecimal("25.00"))));
        runExport(fixedStamp(), BUSINESS_DATE);

        byte[] payload = payloadOf(recordAt(dataset(), 0));
        CopybookLayout.FieldSpec packedField =
                ExportRecordMapper.viewLayout(RecordType.ACCOUNT).field("EXP-ACCT-CURR-BAL");
        String misread = new String(payload, packedField.start(), packedField.length(),
                StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> ZonedDecimalCodec.decode(misread, MONEY_INT_DIGITS,
                        MONEY_DEC_DIGITS, true))
                .as("seven packed bytes are not a twelve-character display value")
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * One {@code PICTURE} occupies seven packed bytes, twelve display bytes and eight binary bytes.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:50-57} once more, this time as arithmetic rather than as
     * values, and the fixture contract's section 5.6, which records the account branch as the proof:
     * the branch closes at 460 only if {@code PIC S9(10)V99 COMP-3} is SEVEN bytes, and falls two bytes
     * short at six.</p>
     *
     * <p>Assumptions: the width difference is the entire reason a per-field layout descriptor exists,
     * and the six-byte claim is the specific error worth refuting -- {@code PIC S9(09)V99 COMP-3} IS six
     * bytes, and it appears in this same copybook at {@code :71} in the transaction branch, so the two
     * widths stand side by side and a reader who generalises from one to the other shifts every field
     * after it.</p>
     */
    @Test
    @DisplayName("one picture takes seven packed bytes, twelve display bytes and eight binary bytes")
    void onePictureTakesThreeDifferentWidths() {
        assertThat(CopybookLayout.packedWidth(MONEY_INT_DIGITS, MONEY_DEC_DIGITS))
                .as("twelve packed digit positions plus a sign nibble round up to seven bytes")
                .isEqualTo(7);
        assertThat(CopybookLayout.zonedWidth(MONEY_INT_DIGITS, MONEY_DEC_DIGITS))
                .as("display storage is one byte per digit position")
                .isEqualTo(12);
        assertThat(CopybookLayout.binaryWidth(MONEY_INT_DIGITS, MONEY_DEC_DIGITS))
                .as("twelve binary digit positions occupy eight bytes")
                .isEqualTo(8);
        assertThat(CopybookLayout.packedWidth(9, MONEY_DEC_DIGITS))
                .as("the neighbouring picture in the transaction branch really is six bytes, which is"
                        + " why the seven above may not be generalised away")
                .isEqualTo(6);

        CopybookLayout.RecordSpec view = ExportRecordMapper.viewLayout(RecordType.ACCOUNT);
        assertThat(view.field("EXP-ACCT-CURR-BAL").length()).isEqualTo(7);
        assertThat(view.field("EXP-ACCT-CASH-CREDIT-LIMIT").length()).isEqualTo(7);
        assertThat(view.field("EXP-ACCT-CREDIT-LIMIT").length()).isEqualTo(12);
        assertThat(view.field("EXP-ACCT-CURR-CYC-CREDIT").length()).isEqualTo(12);
        assertThat(view.field("EXP-ACCT-CURR-CYC-DEBIT").length()).isEqualTo(8);
    }

    /**
     * The credit score is a packed field that is not money, and is not read through the money path.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:41}, {@code EXP-CUST-FICO-CREDIT-SCORE PIC 9(03) COMP-3}.</p>
     *
     * <p>Assumptions: packed storage does not imply money. This field is unsigned, has no decimal
     * positions and occupies two bytes, so forcing it through a scale-two money path would produce a
     * value a hundred times too small and would still decode cleanly. Its own descriptor is therefore
     * asserted alongside its value, and the value's scale is required to be zero.</p>
     */
    @Test
    @DisplayName("the credit score is packed, unsigned and unscaled, and is not money")
    void theCreditScoreIsPackedButIsNotMoney() {
        CopybookLayout.FieldSpec fico = ExportRecordMapper.viewLayout(RecordType.CUSTOMER)
                .field("EXP-CUST-FICO-CREDIT-SCORE");
        assertThat(fico.kind()).isEqualTo(CopybookLayout.Kind.PACKED);
        assertThat(fico.intDigits()).isEqualTo(3);
        assertThat(fico.decDigits())
                .as("PIC 9(03) has no V, so this is not a scale-two amount")
                .isZero();
        assertThat(fico.signed())
                .as("PIC 9(03) carries no S, so no sign nibble is interpreted")
                .isFalse();
        assertThat(fico.length())
                .as("three packed digit positions plus a sign nibble round up to two bytes")
                .isEqualTo(2)
                .isEqualTo(CopybookLayout.packedWidth(3, 0));

        stubEverySourceEmpty();
        when(this.customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.of(customer(1L)));
        runExport(fixedStamp(), BUSINESS_DATE);

        Object decoded = ExportRecordMapper.decode(recordAt(dataset(), 0)).customerFields()
                .get("EXP-CUST-FICO-CREDIT-SCORE");
        assertThat(decoded).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) decoded)
                .as("the score is carried across as the integer it is")
                .isEqualByComparingTo(new BigDecimal("742"));
        assertThat(((BigDecimal) decoded).scale())
                .as("scale zero, because the field declares no decimal positions")
                .isZero();
    }

    /**
     * The branch and region defaults reproduce the reference's own literals, byte for byte.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:17-18} for the two spans and
     * {@code app/cbl/CBEXPORT.cbl:278-279} for the literals {@code '0001'} and {@code 'NORTH'}, which
     * the reference repeats in all five of its blocks at {@code :278}, {@code :347}, {@code :411},
     * {@code :466} and {@code :531}.</p>
     *
     * <p>Refactoring Rationale: the reference freezes both values as literals, and the migrated form
     * exposes them as configuration whose DEFAULTS are those same literals. What was wrong with
     * freezing them is stated by the job control itself: {@code app/jcl/CBEXPORT.jcl:19-20} gives the
     * artefact's purpose as branch migration or data transfer, and a branch identifier that cannot name
     * a branch defeats that purpose. Because the defaults reproduce the baseline byte for byte, a caller
     * who configures nothing gets the reference's output exactly -- so this is a STRUCTURAL improvement
     * with no behavioural change, and it is deliberately NOT registered as a divergence.</p>
     *
     * <p>Assumptions: each default is also required to fit its declared span, because the two widths are
     * terms in the 40-byte prefix arithmetic; a five-character branch identifier would not be a
     * different attribution, it would be a different record layout.</p>
     */
    @Test
    @DisplayName("the branch and region defaults are the baseline literals and fit their spans")
    void theBranchAndRegionDefaultsAreTheBaselineLiterals() {
        CopybookLayout.RecordSpec spec = ExportRecordMapper.recordLayout();
        assertThat(ExportJob.DEFAULT_BRANCH_ID).isEqualTo("0001");
        assertThat(ExportJob.DEFAULT_REGION_CODE).isEqualTo("NORTH");
        assertThat(ExportJob.DEFAULT_BRANCH_ID.length())
                .as("four characters, exactly the width EXPORT-BRANCH-ID reserves")
                .isEqualTo(spec.field("EXPORT-BRANCH-ID").length());
        assertThat(ExportJob.DEFAULT_REGION_CODE.length())
                .as("five characters, exactly the width EXPORT-REGION-CODE reserves")
                .isEqualTo(spec.field("EXPORT-REGION-CODE").length());

        ExportJob.ExportStamp resolved =
                ExportJob.ExportStamp.resolve(FIXED_STAMP, "", "", CLOCK);
        assertThat(resolved.branchId())
                .as("an unset property arrives as an empty value and must fall back, not be stamped")
                .isEqualTo(ExportJob.DEFAULT_BRANCH_ID);
        assertThat(resolved.regionCode()).isEqualTo(ExportJob.DEFAULT_REGION_CODE);

        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        runExport(resolved, BUSINESS_DATE);

        assertThat(ExportRecordMapper.decodePrefix(recordAt(dataset(), 0)))
                .extracting(Prefix::branchId, Prefix::regionCode)
                .containsExactly(ExportJob.DEFAULT_BRANCH_ID, ExportJob.DEFAULT_REGION_CODE);
    }

    /**
     * A configured branch identifier and region code replace the defaults in every record.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:17-18}, the two spans the configured values are written
     * into.</p>
     *
     * <p>Assumptions: the values are read back POSITIONALLY as well as through the decoder, because the
     * two spans are adjacent and of different widths -- four bytes then five -- so a one-byte error
     * would move the region code into the branch identifier's span and both decoded values would still
     * be non-blank text.</p>
     */
    @Test
    @DisplayName("a configured branch and region replace the defaults in every record")
    void aConfiguredBranchAndRegionReplaceTheDefaults() {
        ExportJob.ExportStamp stamp =
                new ExportJob.ExportStamp(FIXED_STAMP, "0042", "SOUTH");
        stubTwoRowsPerType();

        runExport(stamp, BUSINESS_DATE);

        byte[] dataset = dataset();
        for (int ordinal = 0; ordinal < dataset.length / RECORD_LENGTH; ordinal++) {
            byte[] record = recordAt(dataset, ordinal);
            assertThat(textAt(record, 31, 4))
                    .as("record %s carries the configured branch in its own four bytes", ordinal)
                    .isEqualTo("0042");
            assertThat(textAt(record, 35, 5))
                    .as("record %s carries the configured region in its own five bytes", ordinal)
                    .isEqualTo("SOUTH");
        }
    }

    /**
     * An over-wide attribution value is refused, and nothing is published.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:17} for the four-byte branch span and {@code :11} for the
     * 26-byte stamp span, both of which are terms in the prefix arithmetic.</p>
     *
     * <p>Assumptions: refusal rather than truncation is the required behaviour, and the difference
     * matters because a truncated value is undetectable downstream: a five-character branch identifier
     * silently written as four leaves a structurally valid dataset attributed to a branch nobody named.
     * The stamp is refused at resolution, while nothing has been written and the property that is wrong
     * can still be named; the branch identifier is refused by the encoder, at the first record. Both
     * happen before the single put, so a refused run publishes NOTHING rather than a partial
     * artefact.</p>
     */
    @Test
    @DisplayName("an over-wide branch or stamp is refused rather than truncated, and nothing is put")
    void anOverWideAttributionValueIsRefused() {
        assertThatThrownBy(() -> new ExportJob.ExportStamp(FIXED_STAMP + "X", "0001", "NORTH"))
                .as("EXPORT-TIMESTAMP is PIC X(26), so a 27-character stamp has no span to occupy")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app/cpy/CVEXPORT.cpy:11");
        assertThatThrownBy(() -> ExportJob.ExportStamp.resolve("2022-07-18", "0001", "NORTH", CLOCK))
                .as("a stamp shorter than the span is refused for the same reason")
                .isInstanceOf(IllegalArgumentException.class);

        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        ExportJob.ExportStamp overWide =
                new ExportJob.ExportStamp(FIXED_STAMP, "00001", "NORTH");

        assertThatThrownBy(() -> runExport(overWide, BUSINESS_DATE))
                .as("a five-character branch identifier does not fit a four-byte span")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("EXPORT-BRANCH-ID");
        verify(this.objectStore, never())
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    /**
     * An injected stamp displaces the clock entirely and fixes the same 26 bytes in every record.
     *
     * <p>Pins {@code app/cbl/CBEXPORT.cbl:175-176}, {@code ACCEPT WS-CURRENT-DATE FROM DATE YYYYMMDD}
     * and {@code ACCEPT WS-CURRENT-TIME FROM TIME}, composed into the 26-character stamp at
     * {@code app/cbl/CBEXPORT.cbl:191}. The stamp IS clock-derived in the reference, which was read at
     * those lines rather than assumed, and {@code app/cbl/CBEXPORT.cbl:165} performs that paragraph ONCE
     * from {@code 1000-INITIALIZE} -- so the reference stamps one instant per RUN and not one per
     * record.</p>
     *
     * <p>Assumptions: the stamp is the only non-deterministic field in the record, so exactly it is
     * displaced and nothing else is. Masking a wider window, or comparing the records loosely, would
     * stop testing the fields that ARE deterministic; injecting the value instead leaves every byte of
     * every record under assertion. Two runs under two different clocks are compared here, so a
     * revision that consulted the clock despite an injected stamp fails.</p>
     */
    @Test
    @DisplayName("an injected stamp displaces the clock and fixes 26 bytes in every record")
    void anInjectedStampDisplacesTheClock() {
        stubTwoRowsPerType();
        runExport(fixedStamp(), BUSINESS_DATE);
        stubTwoRowsPerType();
        assertThat(ExportJob.ExportStamp.resolve(FIXED_STAMP, ExportJob.DEFAULT_BRANCH_ID,
                        ExportJob.DEFAULT_REGION_CODE, OTHER_CLOCK).timestamp())
                .as("a supplied stamp is taken as given, whatever the clock reads")
                .isEqualTo(FIXED_STAMP);
        runExport(new ExportJob.ExportStamp(FIXED_STAMP, ExportJob.DEFAULT_BRANCH_ID,
                ExportJob.DEFAULT_REGION_CODE), BUSINESS_DATE);

        assertThat(this.published).hasSize(2);
        assertThat(this.published.get(1))
                .as("two runs under two clocks agree byte for byte once the stamp is injected")
                .isEqualTo(this.published.get(0));

        byte[] dataset = this.published.get(0);
        for (int ordinal = 0; ordinal < dataset.length / RECORD_LENGTH; ordinal++) {
            assertThat(textAt(recordAt(dataset, ordinal), 1, TimestampFormatter.TIMESTAMP_LENGTH))
                    .as("record %s carries the run's single stamp, not one of its own", ordinal)
                    .isEqualTo(FIXED_STAMP);
        }

        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        runExportFromClock(CLOCK);
        assertThat(textAt(recordAt(this.published.get(2), 0), 1,
                        TimestampFormatter.TIMESTAMP_LENGTH))
                .as("with nothing supplied the reference's own clock path is what runs")
                .isEqualTo(TimestampFormatter.formatNow(CLOCK));
    }

    /**
     * The stamp's 26 bytes are structured as ten of date, one of separator and fifteen of time.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:12-15}, the {@code EXPORT-TIMESTAMP-R} overlay that redefines
     * the same 26 bytes as {@code EXPORT-DATE X(10)}, {@code EXPORT-DATE-TIME-SEP X(1)} and
     * {@code EXPORT-TIME X(15)}.</p>
     *
     * <p>Assumptions: the overlay adds no bytes -- a {@code REDEFINES} does not advance the offset -- so
     * its three widths must sum to the 26 the redefined field already occupies. Asserting the sum
     * alongside the three values is what keeps the overlay a structure rather than an opaque span, and
     * the separator being a field of its own at {@code :14} is why the time portion begins at eleven
     * rather than ten.</p>
     */
    @Test
    @DisplayName("the stamp overlay is ten of date, one of separator and fifteen of time")
    void theStampOverlayIsDateSeparatorAndTime() {
        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));
        runExport(fixedStamp(), BUSINESS_DATE);

        Prefix prefix = ExportRecordMapper.decodePrefix(recordAt(dataset(), 0));
        assertThat(prefix.exportDate())
                .as("EXPORT-DATE is the first ten characters of the stamp")
                .isEqualTo("2022-07-18")
                .hasSize(10);
        assertThat(prefix.dateTimeSeparator())
                .as("EXPORT-DATE-TIME-SEP is one character and belongs to neither neighbour")
                .isEqualTo(" ")
                .hasSize(1);
        assertThat(prefix.exportTime())
                .as("EXPORT-TIME is the remaining fifteen characters")
                .isEqualTo("10:15:30.000000")
                .hasSize(15);
        assertThat(prefix.exportDate().length() + prefix.dateTimeSeparator().length()
                        + prefix.exportTime().length())
                .as("10 + 1 + 15 is the 26 the redefined field already occupies")
                .isEqualTo(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(FIXED_STAMP.length())
                .as("and the injected stamp is that same width, or its constructor refuses it")
                .isEqualTo(TimestampFormatter.TIMESTAMP_LENGTH);
    }

    /**
     * A completed run reports the clean tier as its returned value and as its process exit status.
     *
     * <p>Pins the absence of any {@code RETURN-CODE} statement in all 583 lines of
     * {@code app/cbl/CBEXPORT.cbl}: the reference either ends normally or abends through
     * {@code CALL 'CEE3ABD'} at {@code app/cbl/CBEXPORT.cbl:578-579}, so its only outcomes are zero and
     * a hard failure.</p>
     *
     * <p>Assumptions: the soft-warn tier is unreachable from this job BY CONSTRUCTION and not by
     * omission. The warn tier originates solely at {@code app/cbl/CBTRN02C.cbl:229-230}, where a
     * non-zero reject count selects it, and this job has no reject stream at all -- so a warn arriving
     * from here would be a defect rather than a business outcome. That is why the recorded code is
     * required to differ from four rather than merely to be acceptable.</p>
     *
     * <p>Trade-offs: the tier is asserted in exactly two forms -- the value the body returns, and the
     * exit status a completed execution carries -- and never as a tolerance configured on a test runner.
     * The graded five-tier rubric of {@code tests/README.md} section 8 belongs exclusively to the parity
     * oracle under {@code tests/**}, which aggregates the worst code seen; a Java gate is binary, so
     * borrowing that rubric here would be easy and would quietly turn a failure into a pass. The
     * fixture contract restates the same boundary at its section 7.1.6.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("a completed run reports the clean tier as a value and as an exit status")
    void theCleanTierIsTheReturnedValueAndTheProcessExitStatus() throws Exception {
        stubTwoRowsPerType();

        JobExecution execution = launch(runningLedger(), EXECUTION_ID);

        assertThat(execution.getStatus())
                .as("the run completed, so the tier is decided by what the step recorded")
                .isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("this job cannot produce the warn exit code, because it writes no rejects")
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        assertThat(recordedReturnCode(execution))
                .as("the step recorded the clean tier, which is the process exit status zero")
                .isEqualTo(BatchReturnCode.CLEAN.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_CLEAN)
                .isNotEqualTo(BatchApplication.EXIT_STATUS_SOFT_WARN);
    }

    /**
     * An empty set of masters still produces a valid empty export, and advances nothing.
     *
     * <p>Pins {@code app/cbl/CBEXPORT.cbl:249} and its four siblings, the
     * {@code PERFORM UNTIL ... EOF} loops that execute zero times when a master is at end of file on
     * its first read, and {@code app/cbl/CBEXPORT.cbl:123}, where {@code WS-SEQUENCE-COUNTER} is
     * initialised to zero and is therefore never incremented by a run with nothing to write.</p>
     *
     * <p>Assumptions: the artefact is still published, empty, rather than skipped. The reference opens
     * its output unconditionally at {@code app/cbl/CBEXPORT.cbl:235} and closes it at {@code :561}
     * whatever its inputs held, so an empty run leaves an empty dataset where a consumer expects one --
     * and a consumer facing an ABSENT object cannot tell an empty export from a run that never
     * happened.</p>
     */
    @Test
    @DisplayName("an empty set of masters publishes a valid empty export and advances no sequence")
    void anEmptySetOfMastersStillProducesAValidEmptyExport() {
        stubEverySourceEmpty();

        assertThat(runExport(fixedStamp(), BUSINESS_DATE))
                .as("a run with nothing to export is clean, not a warning and not a failure")
                .isEqualTo(BatchReturnCode.CLEAN);

        assertThat(dataset())
                .as("zero records at 500 bytes each is zero bytes, and the object still exists")
                .isEmpty();
        assertThat(sequenceNumbersOf(dataset()))
                .as("the shared counter was never incremented, so no key was consumed")
                .isEmpty();
        assertThat(puts()).singleElement()
                .extracting(PutObjectRequest::key)
                .isEqualTo(EXPORT_KEY);
    }

    /**
     * The step runs under one durable ledger entry keyed on the run and the step, and a repeat replays.
     *
     * <p>Refactoring Rationale: this is an improvement over the baseline rather than a port of it, and
     * the distinction is worth stating because a reader looking for the reference's restart contract
     * will not find one. The only {@code RESTART=} anywhere in the 38 job-control files is COMMENTED OUT,
     * at {@code app/jcl/DEFGDGD.jcl:2}, and no {@code CHKPT=} appears in any of them -- so a reference
     * rerun repeats every step from the beginning, and the export in particular deletes and redefines
     * its output cluster on every run at {@code app/jcl/CBEXPORT.jcl:27-38}. The durable step ledger
     * supplies the idempotency the job control only gestures at: a step is keyed on the run identifier
     * paired with the step name, and a redriven execution whose step already completed is skipped with
     * its recorded outcome replayed.</p>
     *
     * <p>Assumptions: the ledger is a stub here and the row uniqueness it enforces is asserted against
     * the database by the module's {@code *IT} classes, because that is where a schema exists. What
     * belongs at this tier is the pair of properties the job itself decides: which key the step is
     * claimed under, and that a skipped step does no work. The second is the one worth asserting
     * positively -- a replay that still walked the masters and still published would be a duplicate
     * rather than a no-op, and the artefact it published would carry a second run's stamps.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("the step is claimed once per run and step name, and a repeat does no work")
    void theDurableLedgerClaimsOneStepPerRunAndReplaysARepeat() throws Exception {
        stubTwoRowsPerType();
        BatchStepLedger ledger = runningLedger();

        launch(ledger, EXECUTION_ID);

        ArgumentCaptor<String> claimedRun = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> claimedStep = ArgumentCaptor.forClass(String.class);
        verify(ledger, times(1)).runStep(claimedRun.capture(), claimedStep.capture(),
                eq(BatchJobName.EXPORT), any());
        assertThat(claimedRun.getValue())
                .as("the run identifier is the one the step builder was constructed with")
                .isEqualTo(RUN_ID);
        assertThat(claimedStep.getValue())
                .as("the step name is this job's own, so the key is (run, step) and not (run, job)")
                .isEqualTo(ExportJob.STEP_NAME);

        this.published.clear();
        stubTwoRowsPerType();
        JobExecution replayed = launch(replayingLedger(), EXECUTION_ID + 1);

        assertThat(replayed.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(recordedReturnCode(replayed))
                .as("the skipped step replays the outcome the first run recorded")
                .isEqualTo(BatchReturnCode.CLEAN.numericValue());
        assertThat(this.published)
                .as("a replayed step publishes nothing, so the artefact is not written twice")
                .isEmpty();
    }

    /**
     * A hard failure publishes nothing and never reports the warn tier.
     *
     * <p>Pins {@code app/cbl/CBEXPORT.cbl:578-579}, the abend paragraph the reference reaches from a
     * failed open, a failed read or a failed write alike, and {@code app/cbl/CBEXPORT.cbl:301}, where
     * the write is the last thing each block does -- so a fault during a read has produced no output
     * yet.</p>
     *
     * <p>Assumptions: nothing is published, and that is a property of the staging design rather than an
     * accident. The records are assembled into a temporary file and the single put happens only after
     * every phase has finished and the reconciliation has passed, so a fault mid-phase leaves the key a
     * consumer reads untouched instead of holding a partial artefact.</p>
     *
     * <p>Assumptions: the warn tier is asserted to be unreachable here as well, because a failed
     * execution can still be carrying whatever exit code a step set before it failed -- so a reading
     * that consulted the exit code alone could let an abended run report a soft warning and let a
     * downstream state continue over incomplete work.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("a hard failure publishes nothing and never reports the warn tier")
    void aHardFailurePublishesNothingAndNeverWarns() throws Exception {
        stubEverySourceEmpty();
        stubAccountMasterUnreadable();

        assertThatThrownBy(() -> runExport(fixedStamp(), BUSINESS_DATE))
                .as("the fault propagates rather than being graded into a return code")
                .isInstanceOf(IllegalStateException.class);
        verify(this.objectStore, never())
                .putObject(any(PutObjectRequest.class), any(RequestBody.class));

        stubEverySourceEmpty();
        stubAccountMasterUnreadable();
        JobExecution execution = launch(runningLedger(), EXECUTION_ID);

        assertThat(execution.getStatus())
                .as("an unhandled fault fails the execution, which is the hard-failure tier")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitCode())
                .as("a failed execution must not read as a soft warning")
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        assertThat(BatchReturnCode.HARD_FAILURE.numericValue())
                .as("the hard-failure tier is eight, which is neither clean nor the warn tier")
                .isEqualTo(BatchApplication.EXIT_STATUS_HARD_FAILURE)
                .isGreaterThanOrEqualTo(BatchApplication.EXIT_STATUS_HARD_FAILURE)
                .isNotEqualTo(BatchApplication.EXIT_STATUS_SOFT_WARN);
        assertThat(this.published)
                .as("the single put happens only after every phase succeeded, so nothing was written")
                .isEmpty();
    }

    /**
     * The business date names the dataset partition and reaches no byte of any record.
     *
     * <p>Pins {@code app/jcl/CBEXPORT.jcl:43}, {@code //STEP02 EXEC PGM=CBEXPORT}, which carries NO
     * {@code PARM} at all -- the only {@code PARM} in the batch chain is
     * {@code app/jcl/INTCALC.jcl:22}, where the accrual job's business date is injected. The reference
     * export therefore receives no business date, and {@code app/cbl/CBEXPORT.cbl:146} is a
     * {@code PROCEDURE DIVISION.} with no {@code USING} phrase, so none reaches it through linkage
     * either.</p>
     *
     * <p>Assumptions: the migrated job nonetheless requires the parameter, and the reason is that it
     * names the partition the artefact is published under rather than any value inside it. That is a
     * structural addition, so what has to be asserted is the boundary: two runs whose business dates
     * differ publish DIFFERENT KEYS and BYTE-IDENTICAL DATASETS. A business date that leaked into a
     * record would make the two datasets differ, and a run predicate is deliberately not asserted at
     * all -- {@code app/jcl/CBEXPORT.jcl} carries no {@code COND=} and this job is not a state of the
     * nightly chain, so its absence is a fact about the reference rather than an oversight here.</p>
     */
    @Test
    @DisplayName("the business date names the partition and reaches no record byte")
    void theBusinessDateNamesThePartitionAndReachesNoRecordByte() {
        stubTwoRowsPerType();
        runExport(fixedStamp(), BUSINESS_DATE);
        stubTwoRowsPerType();
        runExport(fixedStamp(), OTHER_BUSINESS_DATE);

        assertThat(puts())
                .as("the partition segment of the key is the only thing the date decided")
                .extracting(PutObjectRequest::key)
                .containsExactly(EXPORT_KEY, "export/2022071900/export.dat");
        assertThat(this.published).hasSize(2);
        assertThat(this.published.get(1))
                .as("no byte of any record derives from the business date")
                .isEqualTo(this.published.get(0));
    }

    /**
     * The dataset is published under a plain key, not as a generation of a retained family.
     *
     * <p>Pins {@code app/jcl/CBEXPORT.jcl:27-38}, whose {@code IDCAMS} step DELETES and redefines the
     * export cluster on every run, and {@code app/jcl/CBEXPORT.jcl:62-63}, which mounts it
     * {@code DISP=SHR} rather than as a relative generation. That is replace-in-place, so the artefact
     * gets no {@code (+1)} allocation and no retention rule.</p>
     *
     * <p>Assumptions: the export dataset is genuinely NOT one of the ten generation families, which is
     * asserted rather than assumed -- the ten are defined at {@code app/jcl/DEFGDGB.jcl:25-57},
     * {@code app/jcl/DEFGDGD.jcl:28-76} and {@code app/jcl/DALYREJS.jcl:24-26}, and none of them names
     * {@code AWS.M2.CARDDEMO.EXPORT.DATA}. The key is therefore required to carry neither the
     * {@code dt=} nor the {@code gen=} segment of the generation convention, because a key that looked
     * generational would invite a consumer to expect a retention behaviour this artefact does not
     * have.</p>
     */
    @Test
    @DisplayName("the dataset is published under a plain key rather than a generation prefix")
    void theDatasetIsPublishedUnderAPlainKeyRatherThanAGeneration() {
        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(account(1L)));

        runExport(fixedStamp(), BUSINESS_DATE);

        assertThat(puts()).singleElement()
                .extracting(PutObjectRequest::key)
                .isEqualTo(EXPORT_KEY);
        assertThat(EXPORT_KEY)
                .as("neither segment of the generation key convention appears")
                .doesNotContain("dt=")
                .doesNotContain("gen=");
        assertThat(Arrays.stream(DatasetFamily.values()).map(DatasetFamily::mainframeBaseName))
                .as("the export dataset is not one of the ten generation families")
                .doesNotContain("AWS.M2.CARDDEMO.EXPORT.DATA")
                .hasSize(GENERATION_FAMILY_COUNT);
    }

    /**
     * The committed account fixture exports one record per row, in its own key order.
     *
     * <p>Pins the record geometry the fixture tree is authored against: {@code app/cpy/CVACT01Y.cpy}
     * gives the account master 300 bytes, which is the width the committed
     * {@code fixtures/export/happy_path/acctdata.txt} rows carry, and
     * {@code app/jcl/CBEXPORT.jcl:51} declares that master as the {@code ACCTFILE} input the account
     * phase reads.</p>
     *
     * <p>Assumptions: the fixture is decoded with the SHARED KERNEL's own account layout rather than by
     * slicing offsets here, so this case cannot disagree with the layout the rest of the module uses;
     * the fixture contract for this tree names those decoders as the sanctioned ones and forbids a
     * second. The five rows are seed-derived and every money value in them carries a POSITIVE overpunch,
     * which is why the negative-money case above constructs its input in code instead.</p>
     *
     * @throws IOException if the committed fixture cannot be read from the classpath, which would mean
     *     the resource was not packaged with the test sources
     */
    @Test
    @DisplayName("the committed account fixture exports one record per row in key order")
    void theCommittedAccountFixtureExportsOneRecordPerRow() throws IOException {
        List<Account> rows = accountsFromFixture();
        assertThat(rows)
                .as("the committed fixture carries five account rows")
                .hasSize(5);
        stubEverySourceEmpty();
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(rows.stream());

        runExport(fixedStamp(), BUSINESS_DATE);

        byte[] dataset = dataset();
        assertThat(dataset).hasSize(rows.size() * RECORD_LENGTH);
        assertThat(discriminatorsOf(dataset)).containsOnly(RecordType.ACCOUNT.discriminator());
        assertThat(sequenceNumbersOf(dataset)).containsExactly(1L, 2L, 3L, 4L, 5L);

        Account first = rows.get(0);
        assertThat(ExportRecordMapper.decode(recordAt(dataset, 0)).account())
                .as("the fixture's own identifier and money reach the emitted record unchanged")
                .extracting(Account::getAccountId, Account::getCurrBal, Account::getCreditLimit)
                .containsExactly(first.getAccountId(), first.getCurrBal(), first.getCreditLimit());
    }

    /**
     * Returns the reproducible stamp every deterministic case runs with.
     *
     * <p>Assumptions: the two attribution values are taken from the job's own defaults rather than
     * spelled here, so a case asserting the baseline literals is the only place those two strings
     * appear and no other case can drift from them.</p>
     *
     * @return the stamp carrying {@link #FIXED_STAMP} and the two baseline literals; never {@code null}
     */
    private static ExportJob.ExportStamp fixedStamp() {
        return new ExportJob.ExportStamp(FIXED_STAMP, ExportJob.DEFAULT_BRANCH_ID,
                ExportJob.DEFAULT_REGION_CODE);
    }

    /**
     * Asserts that one named field of a record descriptor occupies exactly one declared interval.
     *
     * @param spec the record descriptor to interrogate; must not be {@code null}
     * @param fieldName the copybook field name to look up, spelled as the copybook spells it
     * @param offset the ZERO-BASED byte offset the field must begin at, which is one less than the
     *     one-based column a copybook listing would show
     * @param width the number of bytes the field must occupy, being its physical width under its own
     *     {@code USAGE} rather than its count of digit positions
     */
    private static void assertFieldSpan(CopybookLayout.RecordSpec spec, String fieldName, int offset,
            int width) {
        CopybookLayout.FieldSpec field = spec.field(fieldName);
        assertThat(field.start())
                .as("%s begins at zero-based offset %s", fieldName, offset)
                .isEqualTo(offset);
        assertThat(field.length())
                .as("%s occupies %s bytes", fieldName, width)
                .isEqualTo(width);
        assertThat(field.end())
                .as("%s therefore ends where the next field begins", fieldName)
                .isEqualTo(offset + width);
    }

    /**
     * Returns whichever of a decoded record's five payload projections is populated.
     *
     * <p>Assumptions: exactly one is ever populated, which the decoded record's own constructor
     * enforces, so returning the first non-absent one is total rather than a best effort.</p>
     *
     * @param decoded the decoded record to inspect; must not be {@code null}
     * @return the single populated projection; never {@code null}
     */
    private static Object populatedProjectionOf(ExportRecordMapper.ExportRecord decoded) {
        List<Object> populated = Stream.of(decoded.customerFields(), decoded.account(),
                        decoded.transaction(), decoded.cardXref(), decoded.cardFields())
                .filter(candidate -> candidate != null)
                .toList();
        assertThat(populated)
                .as("the five views redefine one span, so they are alternatives and not companions")
                .hasSize(1);
        return populated.get(0);
    }

    /**
     * Returns the payload projection a decoded record's own discriminator selects.
     *
     * @param decoded the decoded record whose projections are read; must not be {@code null}
     * @param view the view the record's first byte resolved to, which chooses the projection
     * @return the projection belonging to {@code view}, which may be absent if the record is
     *     inconsistent; the caller asserts identity against the populated one
     */
    private static Object selectedProjectionOf(ExportRecordMapper.ExportRecord decoded,
            RecordType view) {
        return switch (view) {
            case CUSTOMER -> decoded.customerFields();
            case ACCOUNT -> decoded.account();
            case TRANSACTION -> decoded.transaction();
            case CARD_XREF -> decoded.cardXref();
            case CARD -> decoded.cardFields();
        };
    }

    /**
     * Builds the export job bean with every collaborator stubbed and the stamp injected.
     *
     * <p>Assumptions: the step builder is obtained through the configuration's own factory method
     * rather than by calling its constructor, because that constructor is package-private to the
     * configuration package -- the boundary that binds the run identifier exactly once, from the
     * container variable. Going through the factory means this case assembles the step the way
     * production does.</p>
     *
     * @param ledger the step ledger the guarded step claims its work against; must not be {@code null}
     * @return the assembled job, registered under the export token; never {@code null}
     */
    private Job exportJob(BatchStepLedger ledger) {
        JobParametersValidator validator = new BatchConfig().carddemoJobParametersValidator();
        return new ExportJob().exportDataset(this.jobRepository, new ResourcelessTransactionManager(),
                new BatchConfig().ledgerGuardedStep(ledger, RUN_ID), this.customers, validator,
                this.accounts, this.crossReferences, this.transactions, this.cards, this.objectStore,
                BUCKET, ExportJob.DEFAULT_BRANCH_ID, ExportJob.DEFAULT_REGION_CODE, FIXED_STAMP,
                CLOCK);
    }

    /**
     * Runs the assembled job once through its own step and returns the finished execution.
     *
     * <p>Assumptions: the instance and the execution are constructed directly and then registered,
     * rather than obtained from a convenience overload, because the repository's creation method takes
     * an instance -- so asking it for one by name would depend on an overload the interface this job is
     * built against does not declare. Both required parameters are supplied, because the shared
     * validator is attached to this job and a launch missing either is refused before any step runs.</p>
     *
     * @param ledger the step ledger the guarded step claims its work against; must not be {@code null}
     * @param executionId the identifier to register the execution under, distinct per launch within one
     *     case so that a second launch is not mistaken for a retry of the first
     * @return the finished execution, carrying its batch status, its exit status and its step
     *     executions; never {@code null}
     * @throws Exception if the framework's own execution path raises
     */
    private JobExecution launch(BatchStepLedger ledger, long executionId) throws Exception {
        Job job = exportJob(ledger);
        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, BUSINESS_DATE.token(), true)
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();
        JobInstance instance = new JobInstance(INSTANCE_ID, ExportJob.JOB_NAME);
        JobExecution execution = new JobExecution(executionId, instance, parameters);
        this.jobRepository.update(execution);
        job.execute(execution);
        return execution;
    }

    /**
     * Builds a step ledger that runs the body it is handed and reports what the body returned.
     *
     * <p>Assumptions: this is the arm that models a step which has NOT run before, so the body executes
     * and its graded outcome is passed straight back. It is the only way to observe the graded return
     * code without a database, because the real ledger persists the row it grades.</p>
     *
     * @return the stubbed ledger; never {@code null}
     */
    private static BatchStepLedger runningLedger() {
        BatchStepLedger ledger = mock(BatchStepLedger.class);
        when(ledger.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenAnswer(call -> new BatchStepLedger.StepOutcome(
                        call.<Supplier<BatchReturnCode>>getArgument(3).get(), false));
        return ledger;
    }

    /**
     * Builds a step ledger that skips the body and replays a recorded clean outcome.
     *
     * <p>Assumptions: this is the arm that models a REDRIVEN execution whose step already completed
     * under the same run identifier and step name. The body is deliberately never invoked, which is what
     * makes a repeat a no-op rather than a second write of the same artefact.</p>
     *
     * @return the stubbed ledger; never {@code null}
     */
    private static BatchStepLedger replayingLedger() {
        BatchStepLedger ledger = mock(BatchStepLedger.class);
        when(ledger.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenReturn(new BatchStepLedger.StepOutcome(BatchReturnCode.CLEAN, true));
        return ledger;
    }

    /**
     * Reads the numeric tier the single step of one execution recorded.
     *
     * <p>Assumptions: the tier is read from the step execution context under the key the shared step
     * builder publishes, because that is where the guarded step puts it and where the exit-status
     * listener reads it from. Reading it from anywhere else would assert a value nothing consumes.</p>
     *
     * @param execution the finished execution to read from; must not be {@code null}
     * @return the numeric return code the step recorded
     */
    private static int recordedReturnCode(JobExecution execution) {
        assertThat(execution.getStepExecutions())
                .as("this job has exactly one step, so there is exactly one tier to read")
                .hasSize(1);
        StepExecution step = execution.getStepExecutions().iterator().next();
        return step.getExecutionContext()
                .getInt(BatchConfig.LedgerGuardedStep.RETURN_CODE_KEY);
    }

    /**
     * Decodes the committed account fixture into entity rows, using the shared account layout.
     *
     * <p>Assumptions: the fixture is read as raw bytes and split on the newline that terminates each
     * record, because the rows are fixed width and positional -- a text reader that trimmed or
     * re-encoded them would change the very bytes the layout is being applied to. Each row is required
     * to be exactly the master's declared 300 bytes before it is decoded, so a mis-sized fixture fails
     * with a length diagnostic rather than as a field that reads oddly.</p>
     *
     * @return one account row per fixture line, in the order the fixture lists them; never {@code null}
     * @throws IOException if the fixture cannot be read from the classpath
     */
    private static List<Account> accountsFromFixture() throws IOException {
        byte[] fixture;
        try (InputStream stream = ExportJobTest.class.getResourceAsStream(ACCOUNT_FIXTURE)) {
            assertThat(stream)
                    .as("the committed fixture %s is packaged with the test resources",
                            ACCOUNT_FIXTURE)
                    .isNotNull();
            fixture = stream.readAllBytes();
        }

        CopybookLayout.RecordSpec layout = CopybookLayout.layout(ACCOUNT_MASTER_LAYOUT);
        assertThat(layout.reclen())
                .as("the shared account layout carries the 300 bytes app/cpy/CVACT01Y.cpy declares")
                .isEqualTo(ACCOUNT_MASTER_LENGTH);

        List<Account> rows = new ArrayList<>();
        int start = 0;
        while (start < fixture.length) {
            int end = start;
            while (end < fixture.length && fixture[end] != '\n') {
                end++;
            }
            byte[] row = Arrays.copyOfRange(fixture, start, end);
            assertThat(row)
                    .as("each fixture row is exactly one 300-byte account record")
                    .hasSize(ACCOUNT_MASTER_LENGTH);
            rows.add(accountFrom(FixedWidthCodec.decodeRecord(row, layout)));
            start = end + 1;
        }
        return rows;
    }

    /**
     * Projects one decoded account master record onto the entity the export reads from.
     *
     * @param decoded the field values of one account master record, keyed by copybook field name and
     *     already converted by the shared codec to {@code Long}, {@code BigDecimal} and {@code String}
     * @return the entity carrying those values; never {@code null}
     */
    private static Account accountFrom(Map<String, Object> decoded) {
        return new Account(
                (Long) decoded.get("ACCT-ID"),
                ((String) decoded.get("ACCT-ACTIVE-STATUS")).trim(),
                (BigDecimal) decoded.get("ACCT-CURR-BAL"),
                (BigDecimal) decoded.get("ACCT-CREDIT-LIMIT"),
                (BigDecimal) decoded.get("ACCT-CASH-CREDIT-LIMIT"),
                LocalDate.parse(((String) decoded.get("ACCT-OPEN-DATE")).trim()),
                LocalDate.parse(((String) decoded.get("ACCT-EXPIRAION-DATE")).trim()),
                LocalDate.parse(((String) decoded.get("ACCT-REISSUE-DATE")).trim()),
                (BigDecimal) decoded.get("ACCT-CURR-CYC-CREDIT"),
                (BigDecimal) decoded.get("ACCT-CURR-CYC-DEBIT"),
                ((String) decoded.get("ACCT-ADDR-ZIP")).trim(),
                ((String) decoded.get("ACCT-GROUP-ID")).trim());
    }
}
