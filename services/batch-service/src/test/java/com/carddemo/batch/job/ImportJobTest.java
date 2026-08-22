package com.carddemo.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.batch.BatchApplication;
import com.carddemo.batch.config.BatchConfig;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.Card;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.Customer;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BatchRunSummary;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.mapper.ExportRecordMapper;
import com.carddemo.batch.mapper.ExportRecordMapper.RecordType;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.CustomerRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.batch.service.BatchStepLedger;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.time.TimestampFormatter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
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
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Asserts the branch-migration import job against its reference program, its job control and the
 * round trip that stands in for the golden master it has never had.
 *
 * <h2>Purpose</h2>
 *
 * <p>Purpose: {@link ImportJob} re-expresses {@code app/cbl/CBIMPORT.cbl}, driven by
 * {@code app/jcl/CBIMPORT.jcl:22}. This class asserts the five subjects that belong to it and to no
 * sibling: the export-to-import <b>round trip</b>, the <b>five-way dispatch</b> and its
 * unknown-discriminator arm, the <b>six fixed-width outputs</b>, the <b>132-byte pipe-delimited
 * diagnostic record</b>, and the four registered divergences the pair carries — D-1, D-4, D-5 and
 * D-8. Three of those four are registered nowhere else in this suite, so each is stated here with
 * the line it rests on.
 *
 * <p>Parameters, return values, exceptions or errors. This is a test class with no constructor of
 * its own, so the type itself takes no parameter, yields no value and raises nothing; every member
 * below carries its own at-clauses. The inapplicability is stated rather than passed over because
 * user-specified Rule 1 (Explainability) forbids a docstring that omits parameters, return values or
 * purpose, and a reader must be able to tell a declared inapplicability from an omission. The system
 * under test states it the same way.
 *
 * <h2>Trade-offs: the round trip is the specification, because no golden master exists</h2>
 *
 * <p>Trade-offs: every other dataset this module writes is compared against a committed expectation
 * file produced by running the reference. This one cannot be. {@code tests/golden} holds
 * {@code posting}, {@code interest}, {@code provisioning}, {@code reporting} and {@code statement}
 * and <b>no {@code export} or {@code import} directory at all</b>, because the program does not
 * compile and so has never run. What stands in its place is the round trip that
 * {@code tests/integration/test_export_import.py} frames in its own module docstring as a
 * "byte-identical export -&gt; import round-trip" modelled on the {@code IDCAMS REPRO} pattern: the
 * export reads the masters, the import re-materialises them, and the two images are compared.
 *
 * <p>Trade-offs: <b>round-trip equality alone would not be enough, and this is the single most
 * important methodological caveat in the file.</b> An encode-then-decode comparison verifies that
 * the two halves agree with each other, so a systematic mis-encoding that the decoder mirrors would
 * pass it unnoticed. The mitigation is that this class also asserts <b>absolute offsets and widths
 * against the copybook layouts</b> — see {@link #everyNamedFieldTilesItsRecordAtItsCopybookOffset}
 * and {@link #theLoadBearingOffsetsAreHeldInTheReconstructedImages} — so the geometry is checked
 * against an independent reference rather than only against the other half of the pair.
 *
 * <h2>Assumptions: the 132-byte diagnostic stream is outside the round trip</h2>
 *
 * <p>Assumptions: {@code tests/integration/test_export_import.py} lists the diagnostic stream in its
 * own data-definition table as "{@code ERROUT 132 error report (not part of the round-trip)}", so it
 * is asserted separately, in its own geometry cases, rather than compared against a master image it
 * never described. Including it in the round trip would compare a diagnostic artefact against
 * nothing.
 *
 * <h2>Refactoring Rationale: divergence D-1, the file-description record key</h2>
 *
 * <p>Refactoring Rationale: {@code app/cbl/CBIMPORT.cbl:40} declares
 * {@code RECORD KEY IS EXPORT-SEQUENCE-NUM} for the input, and that name is not a field of the file
 * record — {@code app/cbl/CBIMPORT.cbl:79} declares the record as a bare
 * {@code 01 EXPORT-INPUT-RECORD PIC X(500)}, so the name resolves to the working-storage copy taken
 * at {@code app/cbl/CBIMPORT.cbl:113}. The producer side carries the identical declaration and the
 * full narrative belongs there; this class restates the consumer side only, and asserts the ruling
 * in {@link #theSequenceNumberIsCarriedAsDataAndNeverAsAFileKey}.
 *
 * <p>Assumptions: <b>the consequence is the most misreadable fact in the repository, so it is stated
 * once and precisely.</b> Because the pair does not compile, only ten of the twelve batch programs
 * build; {@code tests/README.md} section 1.1 records that the build script classifies the pair as
 * known-unsupported and <b>aggregates a soft warn of 4</b> rather than poisoning the aggregate
 * result, and that {@code tests/integration/test_export_import.py} is skipped for that stated reason,
 * mirrored in {@code tests/conftest.py}. <b>That aggregate 4 is the parity suite's GREEN state.</b>
 * It is not a regression, it is not attributable to this migration, and it is not the posting job's
 * business warn tier, which originates solely at {@code app/cbl/CBTRN02C.cbl:229-230}.
 *
 * <h2>Refactoring Rationale: divergence D-4, the missing card data definition</h2>
 *
 * <p>Refactoring Rationale: the reference program fully supports a card output — it selects
 * {@code CARD-OUTPUT ASSIGN TO CARDOUT} at {@code app/cbl/CBIMPORT.cbl:63}, describes it at 150
 * characters at {@code app/cbl/CBIMPORT.cbl:101-104}, writes to it at
 * {@code app/cbl/CBIMPORT.cbl:414}, closes it at {@code app/cbl/CBIMPORT.cbl:462} and reports its
 * count at {@code app/cbl/CBIMPORT.cbl:475}. Its job control allocates only <b>five</b> of the six:
 * the complete data-definition roster of {@code app/jcl/CBIMPORT.jcl} is {@code STEPLIB},
 * {@code EXPFILE}, {@code CUSTOUT}, {@code ACCTOUT}, {@code XREFOUT}, {@code TRNXOUT},
 * {@code ERROUT}, {@code SYSOUT} and {@code SYSPRINT}, and <b>there is no {@code //CARDOUT DD}</b>.
 * Neither file is edited — everything under {@code app/} is reference-only — the migrated job
 * produces all six, and the difference is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Alternatives Considered: reproducing the omission, so the migrated job would publish five
 * artefacts and match the job control exactly. Rejected because the export writes a {@code 'D'} card
 * record — {@code app/jcl/CBEXPORT.jcl:57} allocates {@code CARDFILE} as an export input — so an
 * import that dropped the card output would leave one exported record type with nowhere to land and
 * make the round trip <b>impossible to close</b>. Reproducing the omission would therefore break the
 * very specification this class asserts, which is what makes D-4 unavoidable rather than optional.
 *
 * <h2>Alternatives Considered: divergence D-5, the phantom checksum</h2>
 *
 * <p>Alternatives Considered: {@code app/cbl/CBIMPORT.cbl:31} states in the program header that the
 * program will "Validate data integrity using checksums", and its
 * {@code 3000-VALIDATE-IMPORT} paragraph at {@code app/cbl/CBIMPORT.cbl:449-452} consists of nothing
 * but two {@code DISPLAY} statements. <b>No digest is computed anywhere in the program.</b> Three
 * courses were available for the two strings. Carrying them across untouched follows the migration
 * rule that user-visible strings are reproduced verbatim, but emitting an unqualified claim that
 * integrity was validated is misleading output. Dropping them as vestigial loses text an operator
 * greps for. Inventing a real checksum was rejected outright: it would be an undocumented
 * behavioural addition, and the baseline holds no digest for a computed one to be compared against.
 * The system under test takes the first course with one narrowing — both strings survive, and the
 * clean assertion is gated on the two counters — and this class pins that actual choice in
 * {@link #theValidationBannerIsReproducedAndItsCleanAssertionIsGated} rather than accepting either
 * outcome.
 *
 * <h2>Refactoring Rationale: divergence D-8, the wall-clock reads</h2>
 *
 * <p>Refactoring Rationale: the reference reads the clock seven times — six
 * {@code MOVE FUNCTION CURRENT-DATE(n:m)} at {@code app/cbl/CBIMPORT.cbl:178-188} building its date
 * and time, and one more at {@code app/cbl/CBIMPORT.cbl:429} for every diagnostic record's stamp. A
 * clock-derived field cannot be compared, and with no golden master the only verification left is
 * comparing two target runs against each other, so determinism here is a <b>requirement rather than
 * a preference</b>. {@code tests/README.md} section 11 records the same conclusion for the suite it
 * governs: business dates are injected rather than read from the wall clock precisely so that reruns
 * produce identical output.
 *
 * <p>Assumptions: the injected time source is <b>not</b> the business-date parameter, and the two
 * must not be conflated. {@code app/jcl/CBIMPORT.jcl} carries no {@code PARM=} at all; the only
 * baseline step that passes a date is {@code app/jcl/INTCALC.jcl:22}, and that contract belongs to
 * the interest job's cases. The stamp arrives instead as its own configured value, which
 * {@link #theRunStampIsNotTheBusinessDateParameter} asserts.
 *
 * <h2>Assumptions: the producer's coordination gap does not reach this side</h2>
 *
 * <p>Assumptions: the export direction reads live masters and therefore needs entity and repository
 * seams for all five of them. This direction needs none: {@code app/cbl/CBIMPORT.cbl:37-71} selects
 * one indexed input and <b>six {@code ORGANIZATION IS SEQUENTIAL} outputs, with no indexed
 * output</b>, so every artefact it writes is a flat image rather than a row. That is why this class
 * can assert all six outputs with no concession, and why
 * {@link #theImportTouchesNoBusinessTable} is a faithful mapping rather than a limitation.
 *
 * <h2>Trade-offs: the output sinks are seams, not an emulated object store</h2>
 *
 * <p>Trade-offs: every artefact is observed through a stubbed object-store client rather than
 * against an emulator. {@code services/batch-service/pom.xml} declares Testcontainers
 * {@code postgresql} and {@code junit-jupiter} and <b>no cloud-storage emulator module of any
 * kind</b>, so there is no emulator on this module's test classpath to reach for. What is given up
 * is coverage of the storage client's own request handling; what is bought is that every assertion
 * below is about the <b>bytes</b> the job produced, observed at the moment they left, which is the
 * only thing a fixed-width contract can be judged on.
 */
@DisplayName("the branch-migration import job")
class ImportJobTest {

    /** The dataset bucket the stubbed object store answers under. */
    private static final String BUCKET = "carddemo-datasets-test";

    /** The injected business date, in the ten-character separated form. */
    private static final BusinessDate BUSINESS_DATE = new BusinessDate("2022-07-18");

    /** The orchestrator run identifier every case runs under. */
    private static final String RUN_ID = "batch-run-0001";

    /**
     * The 26-character run stamp the import is configured with.
     *
     * <p>Assumptions: supplied rather than left to the clock, which is the whole of divergence D-8.
     * Its width is exactly what {@code ERR-TIMESTAMP PIC X(26)} at
     * {@code app/cbl/CBIMPORT.cbl:153} reserves.</p>
     */
    private static final String IMPORT_STAMP_TOKEN = "2022-07-18 10:15:30.123456";

    /**
     * The 26-character stamp the export writes into every record's prefix.
     *
     * <p>Assumptions: deliberately DIFFERENT from the import stamp, so a case cannot pass by
     * confusing the producer's stamp with the consumer's. {@code app/cpy/CVEXPORT.cpy:11} declares
     * the export stamp inside the record while the import's own stamp reaches only the diagnostic
     * artefact, and two identical values would hide a leak between them.</p>
     */
    private static final String EXPORT_STAMP_TOKEN = "2022-07-18 09:00:00.000000";

    /**
     * The fixed fallback time source, present so that no case can read a real clock.
     *
     * <p>Assumptions: the system under test consults a clock only when no stamp is configured, and
     * every case here configures one, so this instant must never appear in an artefact. Passing a
     * fixed clock rather than the system clock is what turns "must never appear" into an assertable
     * claim.</p>
     */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-01-02T03:04:05.060708Z"), ZoneOffset.UTC);

    /** The object key the export publishes and the import reads, for this business date. */
    private static final String EXPORT_KEY = "export/2022071800/export.dat";

    /** The key prefix every import artefact is published under, for this business date. */
    private static final String IMPORT_PARTITION = "import/2022071800/";

    /** The diagnostic artefact's object key. */
    private static final String ERROR_KEY = IMPORT_PARTITION + "error.dat";

    /** The nine-digit customer identifier the fixtures carry. */
    private static final long CUSTOMER_ID = 987654321L;

    /** The eleven-digit account identifier the fixtures carry. */
    private static final long ACCOUNT_ID = 11111111111L;

    /** The sixteen-character primary account number the fixtures carry. */
    private static final String CARD_NUM = "4111111111111111";

    /** The sixteen-character transaction identifier the fixture carries. */
    private static final String TRANSACTION_ID = "0000000000000001";

    /**
     * The account balance, negative and carried as packed decimal by
     * {@code app/cpy/CVEXPORT.cpy:50}.
     */
    private static final BigDecimal CURR_BAL = new BigDecimal("-1234.56");

    /**
     * The credit limit, the one POSITIVE amount, carried as zoned display by
     * {@code app/cpy/CVEXPORT.cpy:51}.
     */
    private static final BigDecimal CREDIT_LIMIT = new BigDecimal("9000.00");

    /**
     * The cash credit limit, negative and carried as packed decimal by
     * {@code app/cpy/CVEXPORT.cpy:52}.
     */
    private static final BigDecimal CASH_CREDIT_LIMIT = new BigDecimal("-500.00");

    /**
     * The cycle credit, negative and carried as zoned display by
     * {@code app/cpy/CVEXPORT.cpy:56}.
     */
    private static final BigDecimal CURR_CYC_CREDIT = new BigDecimal("-12.34");

    /**
     * The cycle debit, negative and carried as plain binary by
     * {@code app/cpy/CVEXPORT.cpy:57}.
     */
    private static final BigDecimal CURR_CYC_DEBIT = new BigDecimal("-56.78");

    /** The transaction amount, negative so the ledger's own sign path is exercised too. */
    private static final BigDecimal TRAN_AMOUNT = new BigDecimal("-10.99");

    /** The originating timestamp the transaction fixture carries, which is business data. */
    private static final LocalDateTime ORIG_TS = LocalDateTime.of(2022, 7, 18, 10, 15, 30);

    /** The processing timestamp the transaction fixture carries. */
    private static final LocalDateTime PROC_TS = LocalDateTime.of(2022, 7, 18, 10, 15, 31);

    /** The customer's given name. */
    private static final String FIRST_NAME = "AVA";

    /** The customer's middle name. */
    private static final String MIDDLE_NAME = "B";

    /** The customer's family name. */
    private static final String LAST_NAME = "STONE";

    /** The first address line. */
    private static final String ADDR_LINE_1 = "1 HIGH ST";

    /** The second address line. */
    private static final String ADDR_LINE_2 = "APT 2";

    /** The third address line. */
    private static final String ADDR_LINE_3 = "BLOCK C";

    /** The two-character state code. */
    private static final String STATE_CD = "NY";

    /** The three-character country code. */
    private static final String COUNTRY_CD = "USA";

    /** The customer's postal code. */
    private static final String CUSTOMER_ZIP = "10001";

    /** The customer's first telephone number. */
    private static final String PHONE_1 = "2125550100";

    /** The customer's second telephone number. */
    private static final String PHONE_2 = "2125550101";

    /** The customer's date of birth, already in the ten-character separated form. */
    private static final LocalDate DATE_OF_BIRTH = LocalDate.of(1985, 4, 2);

    /** The electronic funds transfer account identifier. */
    private static final String EFT_ACCOUNT_ID = "9876543210";

    /** The primary-card-holder indicator. */
    private static final String PRI_CARD_HOLDER_IND = "Y";

    /** The credit score, a bounded small integer in the master. */
    private static final short FICO_SCORE = 742;

    /** The account's active-status flag. */
    private static final String ACCOUNT_STATUS = "Y";

    /** The account's open date. */
    private static final LocalDate OPEN_DATE = LocalDate.of(2020, 1, 1);

    /**
     * The account's expiration date, whose master column corrects the baseline field's misspelling.
     */
    private static final LocalDate ACCOUNT_EXPIRATION_DATE = LocalDate.of(2030, 1, 1);

    /** The account's reissue date. */
    private static final LocalDate REISSUE_DATE = LocalDate.of(2024, 1, 1);

    /** The account's postal code. */
    private static final String ACCOUNT_ZIP = "12345";

    /** The account's disclosure group identifier. */
    private static final String GROUP_ID = "ZG";

    /** The name embossed on the card. */
    private static final String EMBOSSED_NAME = "AVA B STONE";

    /** The card's expiration date. */
    private static final LocalDate CARD_EXPIRATION_DATE = LocalDate.of(2027, 12, 31);

    /** The card's active-status flag. */
    private static final String CARD_STATUS = "Y";

    /** The transaction's two-character type code. */
    private static final String TRAN_TYPE_CD = "01";

    /** The transaction's category code as the entity holds it, four characters wide. */
    private static final String TRAN_CAT_CD = "0005";

    /**
     * The transaction's category code as the master's own unsigned numeric field holds it.
     *
     * <p>Assumptions: the master declares the field {@code PIC 9(04)}, so its decoded form is a number
     * and not the four characters the entity carries. Both forms are stated because the fixture
     * supplies one and the expected image asserts the other.</p>
     */
    private static final long TRAN_CAT_CD_NUMERIC = 5L;

    /** The transaction's source. */
    private static final String TRAN_SOURCE = "POS";

    /** The transaction's description. */
    private static final String TRAN_DESC = "purchase";

    /** The merchant identifier. */
    private static final long MERCHANT_ID = 999L;

    /** The merchant name. */
    private static final String MERCHANT_NAME = "merchant";

    /** The merchant city. */
    private static final String MERCHANT_CITY = "city";

    /** The merchant postal code. */
    private static final String MERCHANT_ZIP = "12345";

    /**
     * The charset every artefact in this pair is encoded in.
     *
     * <p>Assumptions: this mirrors the charset the system under test declares at
     * {@code services/batch-service/src/main/java/com/carddemo/batch/job/ImportJob.java:496}, and it
     * is a SINGLE-byte set on purpose: a fixed-width field's width is a byte count, so under a
     * multi-byte set every offset asserted below would be measuring characters against a contract
     * written in bytes. Two other sets were available and neither fits — the branch-migration
     * artefacts are not the mainframe's own {@code cp037}, which belongs to the separate ETL boundary
     * that reads {@code app/data/EBCDIC}, and a byte-transparent eight-bit set would silently accept a
     * high byte that this pair's ASCII-only payload can never legitimately carry. The round-trip
     * comparisons themselves are made on raw byte arrays and so do not depend on this at all; only the
     * span reads that quote a field back in readable form do.</p>
     */
    private static final java.nio.charset.Charset ARTEFACT_CHARSET = StandardCharsets.US_ASCII;

    /** Record length of the customer artefact, from {@code app/jcl/CBIMPORT.jcl:37}. */
    private static final int CUSTOMER_RECLEN = 500;

    /** Record length of the account artefact, from {@code app/jcl/CBIMPORT.jcl:42}. */
    private static final int ACCOUNT_RECLEN = 300;

    /** Record length of the cross-reference artefact, from {@code app/jcl/CBIMPORT.jcl:47}. */
    private static final int XREF_RECLEN = 50;

    /** Record length of the transaction artefact, from {@code app/jcl/CBIMPORT.jcl:52}. */
    private static final int TRANSACTION_RECLEN = 350;

    /**
     * Record length of the card artefact, from {@code app/cbl/CBIMPORT.cbl:101-104}.
     *
     * <p>Assumptions: this one width is taken from the program's file description rather than from a
     * data definition, because divergence D-4 means there is no data definition to take it from —
     * {@code app/jcl/CBIMPORT.jcl} allocates no {@code CARDOUT}. The other five widths above are
     * cited to the job control precisely so the contrast is visible.</p>
     */
    private static final int CARD_RECLEN = 150;

    /**
     * Record length of the diagnostic artefact, from {@code app/jcl/CBIMPORT.jcl:60}.
     *
     * <p>Assumptions: it agrees with {@code 01 ERROR-OUTPUT-RECORD PIC X(132)} at
     * {@code app/cbl/CBIMPORT.cbl:109}, and it is NOT the sum of the working-storage layout's
     * fields. See {@link #DECLARED_DIAGNOSTIC_LAYOUT_WIDTH}.</p>
     */
    private static final int ERROR_RECLEN = 132;

    /**
     * Sum of the diagnostic record's declared working-storage fields, from
     * {@code app/cbl/CBIMPORT.cbl:152-160}.
     *
     * <p>⚠️ Assumptions: 26 + 1 + 1 + 1 + 7 + 1 + 50 + 43 = <b>130</b>, which is two bytes SHORT of
     * the 132-byte record those fields are written into. The arithmetic is written out here because
     * assuming 132 is the sum is the single easiest wrong constant to put in this file.</p>
     */
    private static final int DECLARED_DIAGNOSTIC_LAYOUT_WIDTH = 130;

    /** Width of the diagnostic stamp field, {@code ERR-TIMESTAMP PIC X(26)}. */
    private static final int STAMP_WIDTH = TimestampFormatter.TIMESTAMP_LENGTH;

    /** Zero-based offset of the first pipe, from the {@code FILLER} at {@code :154}. */
    private static final int FIRST_SEPARATOR_OFFSET = 26;

    /** Zero-based offset of {@code ERR-RECORD-TYPE}, from {@code :155}. */
    private static final int TYPE_OFFSET = 27;

    /** Zero-based offset of the second pipe, from the {@code FILLER} at {@code :156}. */
    private static final int SECOND_SEPARATOR_OFFSET = 28;

    /** Zero-based offset of {@code ERR-SEQUENCE}, from {@code :157}. */
    private static final int SEQUENCE_OFFSET = 29;

    /** Digit width of {@code ERR-SEQUENCE}, declared {@code PIC 9(07)} at {@code :157}. */
    private static final int SEQUENCE_WIDTH = 7;

    /** Zero-based offset of the third pipe, from the {@code FILLER} at {@code :158}. */
    private static final int THIRD_SEPARATOR_OFFSET = 36;

    /** Zero-based offset of {@code ERR-MESSAGE}, from {@code :159}. */
    private static final int MESSAGE_OFFSET = 37;

    /** Width of {@code ERR-MESSAGE}, declared {@code PIC X(50)} at {@code :159}. */
    private static final int MESSAGE_WIDTH = 50;

    /** The pipe the three {@code FILLER VALUE '|'} items carry. */
    private static final char SEPARATOR = '|';

    /** The diagnostic text {@code app/cbl/CBIMPORT.cbl:432} moves into {@code ERR-MESSAGE}. */
    private static final String UNKNOWN_TYPE_MESSAGE = "Unknown record type encountered";

    /** A discriminator none of the five mapping blocks claims, used to reach the unknown arm. */
    private static final char UNRECOGNISED_DISCRIMINATOR = 'Z';

    /**
     * A sequence number wide enough to be narrowed by the diagnostic record's seven-digit field.
     *
     * <p>Assumptions: eight significant digits, so that a move into {@code PIC 9(07)} must discard
     * the high-order one. A value under 9,999,999 would render identically whether the narrowing
     * happened or not, so the case asserting it would prove nothing.</p>
     */
    private static final long WIDE_SEQUENCE_NUMBER = 12345678L;

    /**
     * The nine report lines {@code app/cbl/CBIMPORT.cbl:465-478} displays, in that order.
     *
     * <p>⚠️ Assumptions: transcribed character for character from the source, and this format is
     * NOT the posting job's. The posting job's pair at {@code app/cbl/CBTRN02C.cbl:227-228} aligns
     * its colons by padding BEFORE them; every line here puts its single space AFTER the colon. That
     * difference is exactly why {@code com.carddemo.batch.dto.BatchRunSummary} deliberately renders
     * nothing at all — the reference programs share no summary format, so each job renders its own —
     * and it is why no line of the posting format appears anywhere in this file.</p>
     */
    private static final List<String> REPORT_LINE_PREFIXES = List.of(
            "CBIMPORT: Import completed",
            "CBIMPORT: Total Records Read: ",
            "CBIMPORT: Customers Imported: ",
            "CBIMPORT: Accounts Imported: ",
            "CBIMPORT: XRefs Imported: ",
            "CBIMPORT: Transactions Imported: ",
            "CBIMPORT: Cards Imported: ",
            "CBIMPORT: Errors Written: ",
            "CBIMPORT: Unknown Record Types: ");

    /** The banner {@code app/cbl/CBIMPORT.cbl:451} displays before its clean assertion. */
    private static final String VALIDATION_COMPLETED = "CBIMPORT: Import validation completed";

    /** The clean assertion {@code app/cbl/CBIMPORT.cbl:452} displays unconditionally. */
    private static final String NO_VALIDATION_ERRORS = "CBIMPORT: No validation errors detected";

    /** The stubbed object store the artefact is read from and the outputs published to. */
    private S3Client objectStore;

    /** Every artefact published during a case, keyed by the object key it was put under. */
    private Map<String, byte[]> published;

    /** The customer master the export reads. */
    private CustomerRepository customers;

    /** The account master the export reads. */
    private AccountRepository accounts;

    /** The cross-reference table the export reads. */
    private CardXrefRepository crossReferences;

    /** The transaction master the export reads. */
    private TransactionRepository transactions;

    /** The card master the export reads. */
    private CardRepository cards;

    /** The durable step ledger, stubbed so a case chooses whether the body runs. */
    private BatchStepLedger stepLedger;

    /** The framework's in-memory job repository. */
    private JobRepository jobRepository;

    /** The import job under test, built from its production configuration class. */
    private Job importJob;

    /** The export job, built so the round trip can be driven end to end. */
    private Job exportJob;

    /** The recorder attached to the import job's logger for the report-line cases. */
    private ListAppender<ILoggingEvent> recorded;

    /** The import job's logger, retained so the recorder can be detached again. */
    private Logger importLogger;

    /**
     * Distinguishes each launch's job instance within one case.
     *
     * <p>Assumptions: the in-memory job repository rejects a second registration under an identifier
     * it already holds, and a case that launches the export and then the import performs two launches
     * — so the identifier has to advance rather than be a constant. No assertion depends on its
     * value.</p>
     */
    private long launchOrdinal;

    /**
     * Builds the stubbed collaborators and both dataset jobs over them.
     *
     * <p>Assumptions: a fresh set is built per case rather than shared, because several cases assert
     * call counts on the step ledger and on the object store, and a shared stub would carry one
     * case's calls into the next.</p>
     *
     * <p>Assumptions: the two jobs are built from their production configuration classes with the
     * shared parameter validator, which is what makes a launch here take the same entry path the
     * deployed task takes — a state machine passes the job name and the business date as container
     * overrides rather than relying on auto-launch.</p>
     */
    @BeforeEach
    void wireTheJobsAndTheirCollaborators() {
        this.published = new LinkedHashMap<>();
        this.objectStore = mock(S3Client.class);

        // WHY : Assumptions: the put body is read INSIDE the answer rather than captured and read
        //       afterwards. Both jobs stage their outputs to files on the task's ephemeral volume and
        //       delete them before returning, so a captured request body refers to a path that no
        //       longer exists by the time an assertion runs. Copying at call time is what lets every
        //       assertion below still be about bytes.
        when(this.objectStore.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenAnswer(call -> {
                    PutObjectRequest request = call.getArgument(0);
                    RequestBody body = call.getArgument(1);
                    try (InputStream stream = body.contentStreamProvider().newStream()) {
                        this.published.put(request.key(), stream.readAllBytes());
                    }
                    return null;
                });

        this.customers = mock(CustomerRepository.class);
        this.accounts = mock(AccountRepository.class);
        this.crossReferences = mock(CardXrefRepository.class);
        this.transactions = mock(TransactionRepository.class);
        this.cards = mock(CardRepository.class);

        this.stepLedger = mock(BatchStepLedger.class);

        // WHY : Assumptions: the ledger stub EVALUATES the body it is handed. A default-returning
        //       mock would run no import at all, so every artefact assertion below would fail on an
        //       absent key rather than on the bytes, and the cases would report a defect in the job
        //       where the defect was in the stub.
        when(this.stepLedger.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenAnswer(call -> new BatchStepLedger.StepOutcome(
                        call.<Supplier<BatchReturnCode>>getArgument(3).get(), false));

        this.jobRepository = new ResourcelessJobRepository();
        BatchConfig configuration = new BatchConfig();
        BatchConfig.LedgerGuardedStep steps =
                configuration.ledgerGuardedStep(this.stepLedger, RUN_ID);
        JobParametersValidator validator = configuration.carddemoJobParametersValidator();

        this.importJob = new ImportJob().importDataset(this.jobRepository,
                new ResourcelessTransactionManager(), steps, validator, this.objectStore, BUCKET,
                IMPORT_STAMP_TOKEN, CLOCK);

        this.exportJob = new ExportJob().exportDataset(this.jobRepository,
                new ResourcelessTransactionManager(), steps, this.customers, validator,
                this.accounts, this.crossReferences, this.transactions, this.cards,
                this.objectStore, BUCKET, ExportJob.DEFAULT_BRANCH_ID,
                ExportJob.DEFAULT_REGION_CODE, EXPORT_STAMP_TOKEN, CLOCK);

        this.importLogger = (Logger) LoggerFactory.getLogger(ImportJob.class);
        this.recorded = new ListAppender<>();
        this.recorded.start();
        this.importLogger.addAppender(this.recorded);
    }

    /**
     * Detaches the log recorder so it cannot outlive the case that attached it.
     *
     * <p>Assumptions: the logger is a process-wide singleton, so an appender left attached would keep
     * accumulating events from every subsequent case in the module and the report-line cases would then
     * assert against a transcript of several runs.</p>
     */
    @AfterEach
    void detachTheLogRecorder() {
        this.importLogger.detachAppender(this.recorded);
        this.recorded.stop();
    }

    /**
     * The job registers under exactly the token the argument contract publishes.
     *
     * <p>Pins {@code com.carddemo.batch.dto.BatchJobName}'s {@code IMPORT} constant, whose token is
     * the bare word {@code import} rather than a kebab-case spelling, and the step name the durable
     * ledger keys its row on.</p>
     */
    @Test
    @DisplayName("register under the token the argument contract publishes")
    void theImportJobRegistersUnderTheImportToken() {
        assertThat(ImportJob.JOB_NAME)
                .as("a job whose bean name is not the token makes --job=import unresolvable")
                .isEqualTo(BatchJobName.IMPORT.token())
                .isEqualTo("import");
        assertThat(this.importJob.getName()).isEqualTo(ImportJob.JOB_NAME);
        // WHY : Refactoring Rationale: this used to require the step name to EQUAL the job token. That
        //       made this job and its export sibling the only two of seven whose ledger rows carried a
        //       bare name, so a selection over the chain's step rows -- LIKE '%-step', the spelling the
        //       other five invite -- returned five of seven and reported nothing. The suffix is
        //       asserted in its own right, because the composition alone would still hold if the
        //       vocabulary's suffix constant were emptied.
        assertThat(ImportJob.STEP_NAME)
                .as("the persisted step name is the job token plus the vocabulary's step suffix")
                .isEqualTo(ImportJob.JOB_NAME + BatchJobName.STEP_NAME_SUFFIX)
                .endsWith(BatchJobName.STEP_NAME_SUFFIX);
        assertThat(BatchJobName.forStepName(ImportJob.STEP_NAME))
                .as("the suffixed step name still resolves back to the job that wrote it")
                .isEqualTo(BatchJobName.IMPORT);
        assertThat(BatchApplication.JOB_NAMES)
                .as("the JOB token stays bare, because --job= and the registry both match on it")
                .contains(ImportJob.JOB_NAME);
    }

    /**
     * Both dataset jobs resolve by their registered names out of an assembled job registry.
     *
     * <p>Pins the resolution the container performs: {@code com.carddemo.batch.BatchApplication}
     * looks a job up by the name its bean registered under, so a token that resolves to nothing is a
     * command line that validates and then fails inside the state machine.</p>
     *
     * <p>Alternatives Considered: assembling a full application context with
     * {@code @SpringBootTest} and the {@code test} profile, which is the shape a reader would expect
     * for a registry assertion. Rejected on the configuration rather than on preference:
     * {@code services/batch-service/src/main/resources/application.yml:280-284} declares
     * {@code spring.config.import} with an {@code aws-parameterstore:} entry whose region is taken
     * from the environment, so Boot's configuration-data processing resolves that entry — and fails
     * on the unresolved region — before a single job bean exists. The lighter runner used here
     * assembles the same two production configuration classes and exposes the same registry, which is
     * the pattern the sibling census case in this package already establishes.</p>
     *
     * <p>Assumptions: the profile document itself supports this choice. Its register of deliberate
     * omissions records that it declares no dataset bucket, because a context needing one is a
     * context exercising the dataset path and that test supplies its own coordinates — which is
     * exactly what the property values below do.</p>
     */
    @Test
    @DisplayName("resolve both dataset jobs by their registered names through the job registry")
    void bothDatasetJobsResolveByTheirRegisteredNames() {
        new ApplicationContextRunner()
                .withUserConfiguration(BatchConfig.class, ExportJob.class, ImportJob.class)
                .withBean(BatchStepLedger.class, () -> this.stepLedger)
                .withBean(JobRepository.class, () -> this.jobRepository)
                .withBean(PlatformTransactionManager.class,
                        ResourcelessTransactionManager::new)
                .withBean(CustomerRepository.class, () -> this.customers)
                .withBean(AccountRepository.class, () -> this.accounts)
                .withBean(CardXrefRepository.class, () -> this.crossReferences)
                .withBean(TransactionRepository.class, () -> this.transactions)
                .withBean(CardRepository.class, () -> this.cards)
                .withBean(S3Client.class, () -> this.objectStore)
                .withBean(Clock.class, () -> CLOCK)
                .withPropertyValues("carddemo.dataset.bucket=" + BUCKET,
                        "carddemo.import.timestamp=" + IMPORT_STAMP_TOKEN,
                        "carddemo.export.timestamp=" + EXPORT_STAMP_TOKEN,
                        BatchApplication.RUN_ID_VARIABLE + "=" + RUN_ID)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(Job.class).values())
                            .as("the round trip is two jobs, so both names must resolve")
                            .extracting(Job::getName)
                            .contains(ExportJob.JOB_NAME, ImportJob.JOB_NAME);
                });
    }

    /**
     * The round trip re-materialises every one of the five master images byte for byte.
     *
     * <p>Pins the whole of {@code app/cbl/CBIMPORT.cbl:288-422}, whose five mapping blocks move each
     * export view's fields into the target record its file description copies in at
     * {@code app/cbl/CBIMPORT.cbl:81-104} — {@code CVCUS01Y}, {@code CVACT01Y}, {@code CVACT03Y},
     * {@code CVTRA05Y} and {@code CVACT02Y} respectively.</p>
     *
     * <p>Trade-offs: the expected image is assembled in this class from a field map this class
     * declares, then encoded through the shared codec against the registered layout. That is what
     * makes the comparison something other than the two halves of the pair agreeing with each other:
     * the VALUES come from the fixture rows, which neither the export nor the import chose. What is
     * still shared is the encoder, which is why the geometry is additionally checked against the
     * copybook offsets in {@link #everyNamedFieldTilesItsRecordAtItsCopybookOffset} rather than left
     * to this comparison alone.</p>
     *
     * <p>Assumptions: the comparison is over the FULL record array, so the trailing {@code FILLER}
     * span is part of it and no trailing whitespace is stripped. The measured per-record padding
     * bytes are contracted in section 6 of {@code services/batch-service/src/test/resources/
     * fixtures/README.md}, which overrides the general padding rule of its section 3.2, and are not
     * restated here.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("re-materialise all five master images byte for byte")
    void theRoundTripReproducesEveryMasterImageByteForByte() throws Exception {
        stubOneRowPerRecordType();
        driveTheRoundTrip();

        assertThat(artefactOf(RecordType.CUSTOMER))
                .as("the customer image the export read, re-materialised at 500 bytes")
                .isEqualTo(expectedCustomerImage());
        assertThat(artefactOf(RecordType.ACCOUNT))
                .as("the account image, whose five money fields cross three storage kinds")
                .isEqualTo(expectedAccountImage());
        assertThat(artefactOf(RecordType.CARD_XREF)).isEqualTo(expectedCrossReferenceImage());
        assertThat(artefactOf(RecordType.TRANSACTION)).isEqualTo(expectedTransactionImage());
        assertThat(artefactOf(RecordType.CARD)).isEqualTo(expectedCardImage());
    }

    /**
     * Each re-materialised artefact holds exactly the record length its contract declares.
     *
     * <p>Pins the five data-definition widths at {@code app/jcl/CBIMPORT.jcl:37}, {@code :42},
     * {@code :47}, {@code :52} and {@code :60}, and the card width at
     * {@code app/cbl/CBIMPORT.cbl:101-104} — which is the one width with no data definition to cite,
     * because of divergence D-4.</p>
     *
     * @param member the artefact's member name within the import partition, which is the record
     *     type's own name lower-cased and is what selects the published object key
     * @param reclen the declared fixed record length in bytes that the artefact's length must be a
     *     whole multiple of, and equal to for a single-record run
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @ParameterizedTest(name = "{0}.dat holds {1}-byte records")
    @CsvSource({"customer,500", "account,300", "card_xref,50", "transaction,350", "card,150"})
    @DisplayName("hold the declared record length in every re-materialised artefact")
    void eachArtefactHoldsItsDeclaredRecordLength(String member, int reclen) throws Exception {
        stubOneRowPerRecordType();
        driveTheRoundTrip();

        assertThat(artefactAt(member))
                .as("a fixed-length artefact whose length is not its record length is unreadable")
                .hasSize(reclen);
    }

    /**
     * Negative money survives the round trip through all three of the export's storage kinds.
     *
     * <p>Pins {@code app/cpy/CVEXPORT.cpy:50-57}, where the account view carries the same
     * {@code PIC S9(10)V99} picture under three different usages — packed at {@code :50} and
     * {@code :52}, zoned display at {@code :51} and {@code :56}, and plain binary at {@code :57} —
     * all of which land in a single zoned regime in {@code app/cpy/CVACT01Y.cpy}.</p>
     *
     * <p>Assumptions: the fixture makes four of the five amounts NEGATIVE on purpose. A
     * positive-only round trip proves nothing about sign handling, and {@code tests/README.md}
     * section 5.2 records that the wrong sign convention silently corrupts negative balances rather
     * than failing — so an unsigned fixture would pass against corrupted bytes. The sign is read back
     * through the shared codec rather than by inspecting overpunch characters here, because that codec
     * is the sanctioned decoder and a second one in this file could disagree with it and be
     * believed.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("carry negative money through packed, zoned and binary usages alike")
    void negativeMoneySurvivesAllThreeExportStorageKinds() throws Exception {
        stubOneRowPerRecordType();
        driveTheRoundTrip();

        byte[] account = artefactOf(RecordType.ACCOUNT);
        assertThat(decodedField(account, "ACCOUNT", "ACCT-CURR-BAL"))
                .as("packed at app/cpy/CVEXPORT.cpy:50, zoned in the master")
                .isEqualTo(CURR_BAL);
        assertThat(decodedField(account, "ACCOUNT", "ACCT-CREDIT-LIMIT"))
                .as("zoned display at app/cpy/CVEXPORT.cpy:51")
                .isEqualTo(CREDIT_LIMIT);
        assertThat(decodedField(account, "ACCOUNT", "ACCT-CASH-CREDIT-LIMIT"))
                .as("packed at app/cpy/CVEXPORT.cpy:52")
                .isEqualTo(CASH_CREDIT_LIMIT);
        assertThat(decodedField(account, "ACCOUNT", "ACCT-CURR-CYC-CREDIT"))
                .as("zoned display at app/cpy/CVEXPORT.cpy:56")
                .isEqualTo(CURR_CYC_CREDIT);
        assertThat(decodedField(account, "ACCOUNT", "ACCT-CURR-CYC-DEBIT"))
                .as("plain binary at app/cpy/CVEXPORT.cpy:57")
                .isEqualTo(CURR_CYC_DEBIT);
        assertThat(decodedField(artefactOf(RecordType.TRANSACTION), "TRAN", "TRAN-AMT"))
                .isEqualTo(TRAN_AMOUNT);
    }

    /**
     * Two imports of one artefact produce byte-identical outputs.
     *
     * <p>Pins the determinism divergence D-8 buys. The reference reads the clock six times at
     * {@code app/cbl/CBIMPORT.cbl:178-188} and once more per diagnostic record at
     * {@code app/cbl/CBIMPORT.cbl:429}, so two reference runs over one input could not agree. With
     * the stamp injected instead, they must.</p>
     *
     * <p>Assumptions: comparing two target runs against each other is the ONLY verification available
     * for this stream, because no golden master exists — so this case is not a convenience, it is the
     * property the round trip rests on.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("produce byte-identical artefacts on a rerun over the same input")
    void twoImportsOfOneArtefactProduceIdenticalBytes() throws Exception {
        stubOneRowPerRecordType();
        byte[] dataset = runTheExport();

        stubTheArtefactRead(dataset);
        runTheImport();
        Map<String, byte[]> first = new LinkedHashMap<>(this.published);

        this.published.clear();
        stubTheArtefactRead(dataset);
        runTheImport();

        assertThat(this.published.keySet()).isEqualTo(first.keySet());
        for (Map.Entry<String, byte[]> artefact : first.entrySet()) {
            assertThat(this.published.get(artefact.getKey()))
                    .as("a rerun that differs by one byte destroys the only verification there is")
                    .isEqualTo(artefact.getValue());
        }
    }

    /**
     * The diagnostic artefact is published but is not part of the round-trip comparison.
     *
     * <p>Pins {@code app/jcl/CBIMPORT.jcl:56-60}, which allocates the stream at
     * {@code LRECL=132}.</p>
     *
     * <p>Assumptions: {@code tests/integration/test_export_import.py} states in its own
     * data-definition table that the 132-byte error report is "not part of the round-trip", so it is
     * excluded here and asserted in its own geometry cases instead. Comparing it against a master
     * image would be comparing a diagnostic artefact against nothing. It is nonetheless asserted to
     * EXIST and to be empty on a clean run, because its data definition is
     * {@code DISP=(NEW,CATLG,DELETE)} — a consumer distinguishing "no diagnostics" from "the import
     * did not run" needs the empty artefact to be there.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("publish an empty diagnostic artefact on a clean run and keep it out of the round trip")
    void theDiagnosticArtefactIsNotPartOfTheRoundTrip() throws Exception {
        stubOneRowPerRecordType();
        driveTheRoundTrip();

        assertThat(this.published).containsKey(ERROR_KEY);
        assertThat(this.published.get(ERROR_KEY))
                .as("a clean run wrote no diagnostic record, so the artefact is empty and not absent")
                .isEmpty();
        assertThat(artefactOf(RecordType.CUSTOMER)).isNotEqualTo(this.published.get(ERROR_KEY));
    }

    /**
     * Each of the five discriminators routes its record to that type's own artefact and to no other.
     *
     * <p>Pins the dispatch at {@code app/cbl/CBIMPORT.cbl:272-285}, whose {@code EVALUATE} selects
     * {@code 2300-PROCESS-CUSTOMER-RECORD} for {@code 'C'}, {@code 2400} for {@code 'A'},
     * {@code 2500} for {@code 'X'}, {@code 2600} for {@code 'T'} and {@code 2650} for {@code 'D'}.</p>
     *
     * <p>Assumptions: the four artefacts the case did not seed are asserted EMPTY rather than absent,
     * so a dispatch that fanned one record out to two artefacts fails here rather than passing on the
     * strength of the one it was expected to fill.</p>
     *
     * @param recordType the one record type the export is seeded with, whose discriminator character
     *     is read from the type itself rather than written in this file so the two directions cannot
     *     drift apart
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @ParameterizedTest(name = "discriminator {0}")
    @EnumSource(RecordType.class)
    @DisplayName("route each discriminator to that type's own artefact")
    void eachDiscriminatorRoutesToItsOwnArtefact(RecordType recordType) throws Exception {
        stubEveryMasterEmpty();
        stubOnlyTheMasterFor(recordType);

        byte[] dataset = runTheExport();
        assertThat((char) (dataset[0] & 0xFF))
                .as("the export wrote the discriminator this type is selected by")
                .isEqualTo(recordType.discriminator());

        stubTheArtefactRead(dataset);
        runTheImport();

        for (RecordType candidate : RecordType.values()) {
            byte[] artefact = artefactOf(candidate);
            if (candidate == recordType) {
                assertThat(artefact).hasSize(declaredReclenOf(candidate));
            } else {
                assertThat(artefact)
                        .as("a record must reach exactly one artefact")
                        .isEmpty();
            }
        }
    }

    /**
     * The card discriminator is {@code 'D'}, and {@code 'C'} belongs to the customer.
     *
     * <p>Pins {@code app/cbl/CBIMPORT.cbl:281-282}, which selects the card mapping block for
     * {@code 'D'}, against {@code :273-274}, which has already given {@code 'C'} to the customer.</p>
     *
     * <p>⚠️ Assumptions: the letter is NOT the initial of the type name, and mis-mapping it is the
     * single most expensive error available across this pair because it does not fail loudly. A wrong
     * letter routes every card record to the unknown-type arm and yields an import that merely looks
     * as though it met unrecognised data; a letter mapped to the wrong VIEW would read 460 bytes under
     * the wrong geometry and produce a 150-byte image whose every field is the right shape and the
     * wrong value. There is no "daily" artefact for {@code 'D'} to be confused with — the daily
     * transaction feed is the posting job's input and no export record type carries it — so the
     * assertion is that the record lands in the 150-byte card artefact and in nothing else.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("map 'D' to the card artefact and leave 'C' to the customer")
    void theCardDiscriminatorIsDAndCustomerOwnsC() throws Exception {
        assertThat(RecordType.CARD.discriminator()).isEqualTo('D');
        assertThat(RecordType.CUSTOMER.discriminator()).isEqualTo('C');

        stubEveryMasterEmpty();
        stubOnlyTheMasterFor(RecordType.CARD);
        driveTheRoundTrip();

        assertThat(artefactOf(RecordType.CARD))
                .as("a 'D' record re-materialises as one 150-byte CVACT02Y image")
                .hasSize(CARD_RECLEN);
        assertThat(artefactOf(RecordType.TRANSACTION)).isEmpty();
        assertThat(artefactOf(RecordType.CUSTOMER)).isEmpty();
    }

    /**
     * An unrecognised discriminator is counted, diagnosed and does not abort the run.
     *
     * <p>Pins the {@code WHEN OTHER} arm at {@code app/cbl/CBIMPORT.cbl:283-284} and the paragraph it
     * performs at {@code :425-434}, which increments {@code WS-UNKNOWN-RECORD-TYPE-COUNT} at
     * {@code :427}, composes the diagnostic record at {@code :429-432} and writes it at {@code :434}.
     * The counter is reported at {@code app/cbl/CBIMPORT.cbl:477-478}.</p>
     *
     * <p>Assumptions: the run continues rather than abending, and the asymmetry is deliberate in the
     * reference: {@code app/cbl/CBIMPORT.cbl:441-444} reports a failed diagnostic write and pointedly
     * does NOT perform {@code 9999-ABEND-PROGRAM}, whereas every other write in the program does. The
     * run's real work is the five artefacts, and losing a diagnostic line is the smaller loss.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("count and diagnose an unrecognised discriminator without aborting")
    void anUnrecognisedDiscriminatorIsDiagnosedAndCounted() throws Exception {
        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));

        JobExecution execution = runTheImport();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(diagnosticRecords())
                .as("one unrecognised record produces exactly one diagnostic record")
                .hasSize(1);
        assertThat(reportedCounterFor("CBIMPORT: Unknown Record Types: ")).isEqualTo(1L);
        assertThat(reportedCounterFor("CBIMPORT: Errors Written: ")).isEqualTo(1L);
        assertThat(reportedCounterFor("CBIMPORT: Total Records Read: ")).isEqualTo(1L);
    }

    /**
     * One mixed run produces every per-type artefact and the diagnostic record together.
     *
     * <p>Pins the exhaustiveness of {@code app/cbl/CBIMPORT.cbl:272-285}: the {@code EVALUATE} has six
     * arms and one pass over a mixed input must reach all six.</p>
     *
     * <p>Assumptions: a run containing only unrecognised records, or only valid ones, would leave half
     * the dispatch unexercised and could not show that a diagnostic record and a re-materialised
     * record coexist in one pass — which is the property a downstream loader depends on when it reads
     * five artefacts and a report from the same partition.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("produce every per-type artefact and the diagnostic record in one pass")
    void aMixedArtefactProducesEveryOutputAndTheDiagnosticInOneRun() throws Exception {
        stubOneRowPerRecordType();
        byte[] valid = runTheExport();
        byte[] mixed = concatenated(valid,
                oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR, WIDE_SEQUENCE_NUMBER));

        this.published.clear();
        stubTheArtefactRead(mixed);
        runTheImport();

        for (RecordType recordType : RecordType.values()) {
            assertThat(artefactOf(recordType))
                    .as("every recognised type still lands beside the diagnostic record")
                    .hasSize(declaredReclenOf(recordType));
        }
        assertThat(diagnosticRecords()).hasSize(1);
        assertThat(reportedCounterFor("CBIMPORT: Total Records Read: "))
                .isEqualTo(RecordType.values().length + 1L);
    }

    /**
     * An unrecognised discriminator leaves the numeric result clean.
     *
     * <p>Pins an ABSENCE in the reference, which is why the absence was measured rather than assumed:
     * {@code RETURN-CODE} appears nowhere in the 487 lines of {@code app/cbl/CBIMPORT.cbl}, so the
     * program's only outcomes are ending normally and abending through {@code CALL 'CEE3ABD'} at
     * {@code app/cbl/CBIMPORT.cbl:481-484}. The unknown-type counter at
     * {@code app/cbl/CBIMPORT.cbl:427} feeds a report line and nothing else.</p>
     *
     * <p>Assumptions: the soft-warn tier is therefore unreachable here <b>by construction</b> rather
     * than by omission. That tier originates solely at {@code app/cbl/CBTRN02C.cbl:229-230}, where a
     * non-zero reject count selects {@code MOVE 4 TO RETURN-CODE}, and raising it here for a skipped
     * record would be a behavioural change needing a divergence record of its own.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("leave the numeric result clean when a record is skipped")
    void anUnrecognisedDiscriminatorLeavesTheTierClean() throws Exception {
        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));

        JobExecution execution = runTheImport();

        assertThat(recordedReturnCodeOf(execution))
                .as("a skipped record is reported, not graded")
                .isEqualTo(BatchReturnCode.CLEAN.numericValue());
        assertThat(execution.getExitStatus().getExitCode())
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
    }

    /**
     * All six outputs are published, including the card artefact the job control never allocated.
     *
     * <p>Pins divergence D-4. The program selects, opens, writes, closes and reports a card output at
     * {@code app/cbl/CBIMPORT.cbl:63}, {@code :233-238}, {@code :414}, {@code :462} and {@code :475};
     * {@code app/jcl/CBIMPORT.jcl} allocates {@code CUSTOUT}, {@code ACCTOUT}, {@code XREFOUT},
     * {@code TRNXOUT} and {@code ERROUT} and no {@code CARDOUT}. Five of six are allocated.</p>
     *
     * <p>Refactoring Rationale: the migrated job produces all six and neither baseline file is edited,
     * because everything under {@code app/} is reference-only. The divergence is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md} instead. What was wrong with the
     * job control is that it under-allocates the program it drives: a reference run would fail its own
     * open at {@code app/cbl/CBIMPORT.cbl:233-238} and abend, so the omission is not a narrower
     * contract but a broken one.</p>
     *
     * <p>Alternatives Considered: reproducing the omission so the migrated job would publish five
     * artefacts and agree with the job control exactly. Rejected because the export writes a
     * {@code 'D'} card record — {@code app/jcl/CBEXPORT.jcl:57} allocates {@code CARDFILE} among its
     * inputs — so dropping the card output would leave one exported type with nowhere to land and make
     * the round trip impossible to close, breaking the specification this class asserts.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("publish all six outputs including the card artefact the job control omits")
    void allSixOutputsArePublishedIncludingTheCardTheJobControlNeverAllocated() throws Exception {
        stubOneRowPerRecordType();
        driveTheRoundTrip();

        assertThat(this.published.keySet())
                .as("five re-materialised artefacts plus the diagnostic one, all under the partition")
                .containsExactlyInAnyOrder(
                        IMPORT_PARTITION + "customer.dat",
                        IMPORT_PARTITION + "account.dat",
                        IMPORT_PARTITION + "card_xref.dat",
                        IMPORT_PARTITION + "transaction.dat",
                        IMPORT_PARTITION + "card.dat",
                        ERROR_KEY);
        assertThat(this.published).hasSize(RecordType.values().length + 1);
        assertThat(artefactOf(RecordType.CARD))
                .as("the artefact with no data definition is produced regardless")
                .hasSize(CARD_RECLEN);
    }

    /**
     * Every named field of every target layout tiles its record at the copybook's own offsets.
     *
     * <p>Pins the five copybooks the file descriptions copy in at
     * {@code app/cbl/CBIMPORT.cbl:81-104} — {@code CVCUS01Y} at 500, {@code CVACT01Y} at 300,
     * {@code CVACT03Y} at 50, {@code CVTRA05Y} at 350 and {@code CVACT02Y} at 150.</p>
     *
     * <p>Trade-offs: this is the independent half of the round-trip argument. Equality after an
     * encode-then-decode cycle would still hold if both halves shared one wrong offset, so the
     * geometry is checked here against the registered descriptor — fields contiguous from zero, no
     * gap, no overlap, and the total equal to the width the job control declares. The cost is that a
     * descriptor error common to the registry and the job would still pass; that is bounded by the
     * registry's entries being transcribed from the copybooks and cross-checked against the data
     * definitions asserted below.</p>
     *
     * @param member the artefact's member name within the import partition, which selects the
     *     published object key
     * @param layoutName the registry key of the target record descriptor whose field offsets are
     *     being walked
     * @param reclen the record length the job control declares for this artefact, asserted equal to
     *     the descriptor's own so the two sources cannot disagree
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @ParameterizedTest(name = "{1} tiles {2} bytes")
    @CsvSource({"customer,CUSTOMER,500", "account,ACCOUNT,300", "card_xref,XREF,50",
            "transaction,TRAN,350", "card,CARD,150"})
    @DisplayName("tile every target record at the copybook's own field offsets")
    void everyNamedFieldTilesItsRecordAtItsCopybookOffset(String member, String layoutName,
            int reclen) throws Exception {

        CopybookLayout.RecordSpec layout = CopybookLayout.layout(layoutName);
        assertThat(layout.reclen())
                .as("the descriptor and the data definition must agree on the record length")
                .isEqualTo(reclen);

        int expectedStart = 0;
        for (CopybookLayout.FieldSpec field : layout.fields()) {
            assertThat(field.start())
                    .as("field %s must begin where the previous field ended", field.name())
                    .isEqualTo(expectedStart);
            expectedStart = field.end();
        }
        assertThat(expectedStart)
                .as("the declared fields must account for every byte of the record")
                .isEqualTo(reclen);

        stubOneRowPerRecordType();
        driveTheRoundTrip();
        assertThat(artefactAt(member)).hasSize(reclen);
    }

    /**
     * The load-bearing absolute offsets are held in the re-materialised images.
     *
     * <p>Pins the two offsets confirmed by three independent baseline sources: the transaction card
     * number at zero-based 262 and the processing timestamp at 304, which appear as the one-based sort
     * positions 263 and 305 at {@code app/jcl/TRANREPT.jcl:41-42} and as the alternate-index key
     * {@code KEYS(26 304)} at {@code app/jcl/TRANIDX.jcl:27}. Also pins the account balance at
     * zero-based 12 with a twelve-byte zoned span, from {@code app/cpy/CVACT01Y.cpy}.</p>
     *
     * <p>Trade-offs: the offsets are written as LITERALS here and read with a raw range copy, which is
     * the one place in this file that declines to go through a descriptor. That is deliberate: every
     * other assertion resolves an offset through the registry, so a wrong registry entry would be
     * invisible to all of them at once. Writing the three most corroborated offsets out longhand gives
     * one check the registry cannot satisfy by agreeing with itself. The cost is a constant that must
     * be re-derived from the sources above if the layout ever changes, which is why all three sources
     * are cited beside it.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("hold the corroborated transaction and account offsets in the re-materialised images")
    void theLoadBearingOffsetsAreHeldInTheReconstructedImages() throws Exception {
        stubOneRowPerRecordType();
        driveTheRoundTrip();

        byte[] transaction = artefactOf(RecordType.TRANSACTION);
        assertThat(textAt(transaction, 262, 16)).isEqualTo(CARD_NUM);
        assertThat(textAt(transaction, 304, 26)).isEqualTo(TimestampFormatter.format(PROC_TS));
        assertThat(textAt(transaction, 278, 26)).isEqualTo(TimestampFormatter.format(ORIG_TS));

        byte[] account = artefactOf(RecordType.ACCOUNT);
        assertThat(textAt(account, 0, 11)).isEqualTo(String.valueOf(ACCOUNT_ID));
        assertThat(textAt(account, 12, 12))
                .as("a twelve-byte zoned span whose final byte carries the sign overpunch")
                .hasSize(12);
    }

    /**
     * The diagnostic record is exactly 132 bytes.
     *
     * <p>Pins {@code LRECL=132} at {@code app/jcl/CBIMPORT.jcl:60} against
     * {@code 01 ERROR-OUTPUT-RECORD PIC X(132)} at {@code app/cbl/CBIMPORT.cbl:109}, which is the one
     * output whose two sources agree without a divergence between them.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("write a diagnostic record of exactly 132 bytes")
    void theDiagnosticRecordIsExactlyOneHundredAndThirtyTwoBytes() throws Exception {
        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));
        runTheImport();

        assertThat(this.published.get(ERROR_KEY)).hasSize(ERROR_RECLEN);
        assertThat(diagnosticRecords()).hasSize(1);
        assertThat(diagnosticRecords().get(0)).hasSize(ERROR_RECLEN);
    }

    /**
     * The three separators sit at the offsets the declared layout puts them at.
     *
     * <p>Pins {@code app/cbl/CBIMPORT.cbl:152-160}. Summing the declared widths in order —
     * {@code ERR-TIMESTAMP} 26, then a one-byte {@code FILLER VALUE '|'}, then
     * {@code ERR-RECORD-TYPE} 1, then a second pipe, then {@code ERR-SEQUENCE} 7, then a third pipe,
     * then {@code ERR-MESSAGE} 50 — puts the three pipes at zero-based 26, 28 and 36.</p>
     *
     * <p>Assumptions: each separator is asserted at its OWN offset rather than by matching the record
     * against one pattern, so a field shifted by a byte fails at the offset that moved instead of only
     * failing the record as a whole. That localisation is the point: a single pattern assertion would
     * report the same failure whichever field had moved.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("place the three separators at offsets 26, 28 and 36")
    void theThreeSeparatorsSitAtTheirDeclaredOffsets() throws Exception {
        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));
        runTheImport();

        byte[] record = diagnosticRecords().get(0);
        assertThat(charAt(record, FIRST_SEPARATOR_OFFSET))
                .as("the FILLER at app/cbl/CBIMPORT.cbl:154 follows the 26-byte stamp")
                .isEqualTo(SEPARATOR);
        assertThat(charAt(record, SECOND_SEPARATOR_OFFSET))
                .as("the FILLER at app/cbl/CBIMPORT.cbl:156 follows the one-byte type")
                .isEqualTo(SEPARATOR);
        assertThat(charAt(record, THIRD_SEPARATOR_OFFSET))
                .as("the FILLER at app/cbl/CBIMPORT.cbl:158 follows the seven-digit sequence")
                .isEqualTo(SEPARATOR);
        assertThat(textAt(record, 0, STAMP_WIDTH)).isEqualTo(IMPORT_STAMP_TOKEN);
    }

    /**
     * The two bytes beyond the declared layout are spaces.
     *
     * <p>Pins the 130-versus-132 arithmetic. {@code app/cbl/CBIMPORT.cbl:152-160} declares fields
     * summing to 26 + 1 + 1 + 1 + 7 + 1 + 50 + 43 = <b>130</b>, and
     * {@code app/cbl/CBIMPORT.cbl:439} writes that group into the {@code PIC X(132)} record declared
     * at {@code app/cbl/CBIMPORT.cbl:109} with {@code WRITE ERROR-OUTPUT-RECORD FROM WS-ERROR-RECORD}.
     * Two bytes of the record are therefore described by no field of the layout.</p>
     *
     * <p>⚠️ Assumptions: those two bytes are SPACES, and the reason is the verb rather than a
     * convention — a {@code MOVE} of a shorter alphanumeric item into a longer one left-justifies and
     * pads the remainder with spaces, so the shortfall is filled the same way the declared 43-byte
     * {@code FILLER VALUE SPACES} at {@code :160} is. All 45 trailing bytes from offset 87 are
     * consequently blank, and the case asserts the whole 45 rather than only the last two, because
     * asserting the two alone would pass on a record whose declared pad had been filled with
     * something else.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("space-fill the two bytes the declared layout does not describe")
    void theTwoBytesBeyondTheDeclaredLayoutAreSpaces() throws Exception {
        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));
        runTheImport();

        byte[] record = diagnosticRecords().get(0);
        assertThat(ERROR_RECLEN - DECLARED_DIAGNOSTIC_LAYOUT_WIDTH)
                .as("the shortfall the layout leaves undescribed")
                .isEqualTo(2);
        assertThat(charAt(record, DECLARED_DIAGNOSTIC_LAYOUT_WIDTH)).isEqualTo(' ');
        assertThat(charAt(record, DECLARED_DIAGNOSTIC_LAYOUT_WIDTH + 1)).isEqualTo(' ');
        assertThat(textAt(record, MESSAGE_OFFSET + MESSAGE_WIDTH,
                ERROR_RECLEN - MESSAGE_OFFSET - MESSAGE_WIDTH))
                .as("the declared 43-byte pad and the two-byte shortfall together")
                .isEqualTo(" ".repeat(45));
    }

    /**
     * The sequence renders as seven zero-padded digits, narrowing the nine the prefix carries.
     *
     * <p>Pins {@code ERR-SEQUENCE PIC 9(07)} at {@code app/cbl/CBIMPORT.cbl:157} against the value
     * moved into it at {@code app/cbl/CBIMPORT.cbl:431}, which is
     * {@code EXPORT-SEQUENCE-NUM PIC 9(9) COMP} from {@code app/cpy/CVEXPORT.cpy:16} — a four-byte
     * binary field of nine digits.</p>
     *
     * <p>Assumptions: the narrowing is REPRODUCED rather than repaired, so a sequence above 9,999,999
     * cannot be represented in the diagnostic stream and a record beyond that point is misidentified
     * by the report that exists to identify it. Widening the field was available and is declined:
     * this artefact is a fixed-width interface whose layout is its contract, and every consumer parses
     * it positionally, so widening one field silently moves the message span and the pad for every
     * reader. The limit is documented here instead, and the system under test logs the untruncated
     * value alongside so the information is narrowed in the artefact without being lost.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("narrow the nine-digit sequence to seven zero-padded display digits")
    void theSequenceIsNarrowedToSevenZeroPaddedDigits() throws Exception {
        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));
        runTheImport();

        String rendered = textAt(diagnosticRecords().get(0), SEQUENCE_OFFSET, SEQUENCE_WIDTH);
        assertThat(rendered)
                .as("a display numeric holds digits only, never blanks")
                .matches("\\d{" + SEQUENCE_WIDTH + "}");
        assertThat(rendered)
                .as("the low-order digits survive and the high-order ones are discarded")
                .isEqualTo("2345678");
        assertThat(Long.parseLong(rendered)).isNotEqualTo(WIDE_SEQUENCE_NUMBER);
    }

    /**
     * The type and message fields fill their declared widths.
     *
     * <p>Pins {@code ERR-RECORD-TYPE PIC X(01)} at {@code app/cbl/CBIMPORT.cbl:155} and
     * {@code ERR-MESSAGE PIC X(50)} at {@code :159}, carrying the text
     * {@code app/cbl/CBIMPORT.cbl:432} moves into it.</p>
     *
     * <p>Assumptions: the message is asserted at its FULL 50 bytes including the blank padding, not
     * trimmed. A consumer of a fixed-width record reads the span, so a message that stopped short
     * would shift nothing but would leave that consumer reading whatever preceded it in the
     * buffer.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("fill the type and message fields to their declared widths")
    void theTypeAndMessageFieldsFillTheirDeclaredWidths() throws Exception {
        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));
        runTheImport();

        byte[] record = diagnosticRecords().get(0);
        assertThat(charAt(record, TYPE_OFFSET))
                .as("the offending discriminator is reported as itself")
                .isEqualTo(UNRECOGNISED_DISCRIMINATOR);
        assertThat(textAt(record, MESSAGE_OFFSET, MESSAGE_WIDTH))
                .isEqualTo(UNKNOWN_TYPE_MESSAGE
                        + " ".repeat(MESSAGE_WIDTH - UNKNOWN_TYPE_MESSAGE.length()));
    }

    /**
     * No checksum or integrity digest is computed anywhere in the run.
     *
     * <p>Pins divergence D-5. {@code app/cbl/CBIMPORT.cbl:31} promises in the program header to
     * "Validate data integrity using checksums", and the paragraph that would do it,
     * {@code 3000-VALIDATE-IMPORT} at {@code app/cbl/CBIMPORT.cbl:449-452}, contains two
     * {@code DISPLAY} statements and nothing else. A search of the source for {@code CHECKSUM} or
     * {@code CHECK-SUM} returns only that header line.</p>
     *
     * <p>Assumptions: the invariant is asserted structurally rather than by inspecting output, because
     * a digest that is computed and discarded is still a behavioural addition. No member of the system
     * under test names a digest, no artefact carries a trailer beyond its declared record length, and
     * the published key set holds exactly the six artefacts — a seventh object holding a manifest or a
     * digest would fail here.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("compute no checksum or integrity digest despite the program header's claim")
    void noChecksumOrDigestIsComputedAnywhereInTheRun() throws Exception {
        // WHY : Assumptions: the language-generated hashCode of a record is deliberately NOT treated as
        //       a digest here. Every nested record of the system under test carries one because the
        //       language supplies it, and it hashes that record's own components rather than any
        //       artefact's bytes -- so matching on "hash" would fail this case on members that exist for
        //       an unrelated reason and would say nothing about integrity validation. The names below
        //       are the ones a real digest would have to be computed into or compared against.
        assertThat(namedMembersOfTheSystemUnderTest())
                .as("a digest would need a member to be computed into or compared against")
                .noneMatch(name -> {
                    String lowered = name.toLowerCase(Locale.ROOT);
                    return lowered.contains("checksum") || lowered.contains("digest")
                            || lowered.contains("crc") || lowered.contains("sha")
                            || lowered.contains("md5");
                });

        stubOneRowPerRecordType();
        driveTheRoundTrip();

        assertThat(this.published).hasSize(RecordType.values().length + 1);
        for (RecordType recordType : RecordType.values()) {
            assertThat(artefactOf(recordType).length % declaredReclenOf(recordType))
                    .as("a digest trailer would leave a remainder over the record length")
                    .isZero();
        }
    }

    /**
     * The validation banner is reproduced and its clean assertion is gated on the two counters.
     *
     * <p>Pins {@code app/cbl/CBIMPORT.cbl:449-452}, whose two {@code DISPLAY} statements are the whole
     * of the validation paragraph.</p>
     *
     * <p>Alternatives Considered: three courses were open for these two strings and the system under
     * test takes the first with one narrowing, which this case pins rather than accepting either
     * outcome. Carrying them across untouched follows the migration rule that user-visible strings
     * cross verbatim, but the second string asserts a clean result UNCONDITIONALLY in the reference —
     * even on a run that wrote diagnostic records — so as written it carries no information. Dropping
     * both as vestigial would lose text an operator greps for. Inventing a real digest so the claim
     * could be earned was rejected outright: it would be an undocumented behavioural addition, and the
     * baseline holds no digest for a computed one to be compared against. The narrowing chosen is that
     * both strings survive character for character and the clean assertion appears only when the error
     * and unknown-type counters are both zero, which makes the line mean what it says.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("reproduce the validation banner and gate its clean assertion")
    void theValidationBannerIsReproducedAndItsCleanAssertionIsGated() throws Exception {
        stubOneRowPerRecordType();
        driveTheRoundTrip();

        assertThat(recordedMessages())
                .as("the banner crosses verbatim from app/cbl/CBIMPORT.cbl:451")
                .contains(VALIDATION_COMPLETED)
                .contains(NO_VALIDATION_ERRORS);

        this.recorded.list.clear();
        this.published.clear();
        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));
        runTheImport();

        assertThat(recordedMessages()).contains(VALIDATION_COMPLETED);
        assertThat(recordedMessages())
                .as("a run that wrote a diagnostic record must not assert it found none")
                .doesNotContain(NO_VALIDATION_ERRORS);
    }

    /**
     * The structured result encodes no validated state.
     *
     * <p>Pins divergence D-5 on the surfaces a downstream state reads rather than on the log. The
     * reference asserts validation at {@code app/cbl/CBIMPORT.cbl:452} and sets no return code
     * anywhere, so there is no structured claim of integrity for the migration to carry across.</p>
     *
     * <p>Assumptions: whatever appears on standard output, the numeric result, the run summary and the
     * durable ledger row must be truthful — none of them may say "validated". The summary is therefore
     * asserted to carry counters and a tier and nothing that names validation, and the ledger row
     * carries the same numeric result the job returned.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("encode no validated state in the numeric result, the summary or the ledger row")
    void theStructuredResultEncodesNoValidatedState() throws Exception {
        stubOneRowPerRecordType();
        byte[] dataset = runTheExport();
        this.published.clear();
        stubTheArtefactRead(dataset);
        JobExecution execution = runTheImport();

        assertThat(recordedReturnCodeOf(execution))
                .isEqualTo(BatchReturnCode.CLEAN.numericValue());

        BatchRunSummary summary = summaryOfTheImportRun();
        assertThat(summary.returnCode()).isEqualTo(BatchReturnCode.CLEAN);
        assertThat(summary.jobName()).isEqualTo(BatchJobName.IMPORT);
        assertThat(BatchRunSummary.class.getRecordComponents())
                .as("no component of the reported summary names validation or a digest")
                .noneMatch(component -> component.getName().toLowerCase(Locale.ROOT)
                        .contains("valid"));
    }

    /**
     * Every diagnostic stamp comes from the injected run stamp rather than a clock.
     *
     * <p>Pins divergence D-8. The reference builds its date and time from six
     * {@code MOVE FUNCTION CURRENT-DATE(n:m)} statements at {@code app/cbl/CBIMPORT.cbl:178-188} and
     * reads the clock a seventh time for every diagnostic record at
     * {@code app/cbl/CBIMPORT.cbl:429}.</p>
     *
     * <p>Refactoring Rationale: determinism here is a requirement rather than a preference. With no
     * golden master for this stream, comparing two target runs against each other is the only
     * verification there is, and a clock read destroys it by changing 26 bytes of every diagnostic
     * record. {@code tests/README.md} section 11 records the same conclusion for the suite it governs:
     * business dates are injected rather than read from the wall clock precisely so reruns produce
     * identical output. The seventh read disappears as well, because one stamp serves the whole run,
     * which additionally makes every diagnostic record of one run correlatable.</p>
     *
     * <p>Assumptions: the fixed fallback clock is set to an instant in a different YEAR from the
     * injected stamp, so a stamp taken from the clock would not merely differ by microseconds — it
     * would be unmistakable in the assertion below.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("stamp every diagnostic record from the injected run stamp, never from a clock")
    void everyDiagnosticStampComesFromTheInjectedRunStamp() throws Exception {
        byte[] dataset = concatenated(
                oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR, WIDE_SEQUENCE_NUMBER),
                oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR, WIDE_SEQUENCE_NUMBER + 1L));
        stubTheArtefactRead(dataset);
        runTheImport();

        List<byte[]> records = diagnosticRecords();
        assertThat(records).hasSize(2);
        for (byte[] record : records) {
            assertThat(textAt(record, 0, STAMP_WIDTH))
                    .as("one run carries one stamp, so its records are correlatable")
                    .isEqualTo(IMPORT_STAMP_TOKEN);
        }
        assertThat(textAt(records.get(0), 0, STAMP_WIDTH))
                .doesNotStartWith(String.valueOf(CLOCK.instant().atZone(ZoneOffset.UTC).getYear()));
    }

    /**
     * The run stamp is not the business-date parameter, and the two are separate mechanisms.
     *
     * <p>Pins an absence: {@code app/jcl/CBIMPORT.jcl} carries no {@code PARM=} anywhere, so a
     * reference import receives no parameter at all. The only baseline step that passes a date is
     * {@code app/jcl/INTCALC.jcl:22}, {@code //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'}, and that
     * contract belongs to the interest job's cases rather than to these.</p>
     *
     * <p>Assumptions: the business date is nevertheless REQUIRED of this job, because the entry point
     * adds it as an identifying job parameter so that one run is a distinct job instance from the next
     * — so the two mechanisms coexist and must not be conflated. This case asserts that they are
     * independent by giving them values that share no characters: a launch under a different business
     * date still stamps the diagnostic record with the configured run stamp, and the parameter is
     * refused when absent.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("take the run stamp from its own configuration and not from the business date")
    void theRunStampIsNotTheBusinessDateParameter() throws Exception {
        assertThat(catchValidation(new JobParametersBuilder()
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters()))
                .as("the business date is an identifying parameter and is refused when absent")
                .isNotNull();

        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));
        runTheImport();

        assertThat(textAt(diagnosticRecords().get(0), 0, STAMP_WIDTH))
                .as("the stamp is the configured value, not anything derived from the date token")
                .isEqualTo(IMPORT_STAMP_TOKEN)
                .isNotEqualTo(BUSINESS_DATE.token());
    }

    /**
     * The diagnostic stamp is the 26-character contract form the export prefix also uses.
     *
     * <p>Pins {@code ERR-TIMESTAMP PIC X(26)} at {@code app/cbl/CBIMPORT.cbl:153} against the export
     * prefix's own overlay at {@code app/cpy/CVEXPORT.cpy:12-15}, which redefines the same 26 bytes as
     * a ten-character date, a one-character separator and a fifteen-character time.</p>
     *
     * <p>Assumptions: the width and the date span are taken from
     * {@code com.carddemo.common.time.TimestampFormatter} rather than written as 26 and 10 here, so
     * the shared kernel remains the one place that knows the contract form's shape and this case
     * cannot come to disagree with the encoder that produced the bytes.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("stamp the diagnostic record in the 26-character contract form")
    void theDiagnosticStampIsTheTwentySixCharacterContractForm() throws Exception {
        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));
        runTheImport();

        String stamp = textAt(diagnosticRecords().get(0), 0, TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(stamp).hasSize(TimestampFormatter.TIMESTAMP_LENGTH);
        assertThat(TimestampFormatter.datePrefix(stamp))
                .as("the first ten characters are the date span the prefix overlay declares")
                .isEqualTo("2022-07-18");
        assertThat(stamp.charAt(10))
                .as("the separator span app/cpy/CVEXPORT.cpy:14 declares")
                .isEqualTo(' ');
        assertThat(TimestampFormatter.parse(stamp)).isNotNull();
    }

    /**
     * The sequence number is carried as data and never as a file access key.
     *
     * <p>Pins divergence D-1 on the consumer side. {@code app/cbl/CBIMPORT.cbl:40} declares
     * {@code RECORD KEY IS EXPORT-SEQUENCE-NUM} for an input whose file record is the unstructured
     * {@code 01 EXPORT-INPUT-RECORD PIC X(500)} at {@code app/cbl/CBIMPORT.cbl:79}, so the name
     * resolves to the working-storage copy that {@code COPY CVEXPORT} brings in at
     * {@code app/cbl/CBIMPORT.cbl:113} and declares at {@code app/cpy/CVEXPORT.cpy:16}. The producer
     * side carries the identical declaration and holds the full narrative; this is the restatement.</p>
     *
     * <p>Refactoring Rationale: the migrated job reads the artefact SEQUENTIALLY and treats the
     * sequence number as a field inside the record, used for diagnostics and ordering and never for
     * retrieval. What was wrong with the indexed organisation is that it bought the reference nothing:
     * its only read is {@code READ EXPORT-INPUT INTO EXPORT-RECORD} at
     * {@code app/cbl/CBIMPORT.cbl:261-267} and {@code ACCESS MODE IS SEQUENTIAL} at {@code :39} already
     * says the access pattern is sequential, so the correct keying is no file key at all.</p>
     *
     * <p>Assumptions: the aggregate warn-level result this defect produces in the parity suite is that
     * suite's GREEN state and D-1 is its sole cause, per {@code tests/README.md} section 1.1 and the
     * mirrored skip in {@code tests/conftest.py}. It is not a regression and it is not the posting
     * job's business warn tier.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("carry the sequence number as data and never as a file access key")
    void theSequenceNumberIsCarriedAsDataAndNeverAsAFileKey() throws Exception {
        CopybookLayout.RecordSpec layout = ExportRecordMapper.recordLayout();
        CopybookLayout.FieldSpec sequence = layout.field("EXPORT-SEQUENCE-NUM");
        assertThat(sequence.start())
                .as("a field INSIDE the record, at the offset the prefix arithmetic puts it")
                .isEqualTo(27);
        assertThat(sequence.end()).isLessThanOrEqualTo(layout.reclen());
        assertThat(sequence.length())
                .as("four binary bytes and not nine display characters, per app/cpy/CVEXPORT.cpy:16")
                .isEqualTo(4);
        assertThat(sequence.intDigits()).isEqualTo(9);

        // WHY : Assumptions: the descriptor DOES declare this span as the record's key, faithfully
        //       transcribing app/cbl/CBIMPORT.cbl:40, and the D-1 ruling is not that the declaration is
        //       removed but that it is never ACTED on. The assertion is therefore behavioural: the
        //       artefact is fetched exactly once, whole, and walked front to back, so there is no keyed
        //       retrieval anywhere in the pass. Asserting the descriptor carried no key would assert the
        //       opposite of what the baseline declares and would fail for the right value.
        assertThat(layout.keyOffset()).isEqualTo(sequence.start());
        assertThat(layout.keyLength()).isEqualTo(sequence.length());

        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));
        runTheImport();

        assertThat(textAt(diagnosticRecords().get(0), SEQUENCE_OFFSET, SEQUENCE_WIDTH))
                .as("the number reaches the diagnostic record as reported data")
                .isEqualTo("2345678");
        verify(this.objectStore, times(1)).getObject(any(GetObjectRequest.class));
    }

    /**
     * The import touches no business table.
     *
     * <p>Pins {@code app/cbl/CBIMPORT.cbl:37-71}, whose {@code SELECT} roster declares one indexed
     * INPUT and six {@code ORGANIZATION IS SEQUENTIAL} outputs and <b>no indexed output at all</b>, so
     * the reference program never writes a VSAM master. Its job control says the same in words at
     * {@code app/jcl/CBIMPORT.jcl:19-20}: the verb is <em>split</em>, not <em>load</em>.</p>
     *
     * <p>Refactoring Rationale: this is a faithful mapping rather than a limitation. The migration plan
     * assigns the relational load to {@code data-migration/src/carddemo_migration/loaders/aurora.py},
     * the per-schema bulk-copy equivalent of {@code IDCAMS REPRO}, and that package carries
     * {@code readers/export_record.py} for this very copybook so it can consume this job's artefact. A
     * job that wrote to the database here would silently duplicate that seam.</p>
     *
     * <p>Alternatives Considered: having the import load the tables directly, which is the obvious
     * alternative and is rejected on two independent grounds. It would put two independent writers on
     * the same tables, so a reader could not tell which one produced a row; and it would break the
     * migration's own verification harness, whose row counts, record checksums and money-total parity
     * are computed against a single loader's work.</p>
     *
     * <p>Trade-offs: the invariant is asserted through the module's own seams rather than against a
     * seeded database. The job holds NO repository seam to observe — which is itself the strongest
     * form of the claim, asserted by reflection below — so a container would add a database without
     * adding an observation; {@code services/batch-service/pom.xml} declares Testcontainers
     * {@code postgresql} for the cases that genuinely persist, and none of them is here.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("write to no business table and hold no repository seam")
    void theImportTouchesNoBusinessTable() throws Exception {
        assertThat(collaboratorTypesOfTheSystemUnderTest())
                .as("a repository or a persistence context would be a way to reach a table")
                .noneMatch(type -> type.getName().startsWith("com.carddemo.batch.repository")
                        || type.getName().startsWith("jakarta.persistence"));

        stubTheArtefactRead(oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR,
                WIDE_SEQUENCE_NUMBER));
        runTheImport();

        verifyNoInteractions(this.accounts);
        verifyNoInteractions(this.crossReferences);
        verifyNoInteractions(this.transactions);
        verifyNoInteractions(this.customers);
        verifyNoInteractions(this.cards);
    }

    /**
     * The nine report lines are emitted verbatim and in the reference's own order.
     *
     * <p>Pins {@code app/cbl/CBIMPORT.cbl:465-478}, whose nine {@code DISPLAY} statements report the
     * completion banner and the eight counters declared at {@code app/cbl/CBIMPORT.cbl:139-147}.</p>
     *
     * <p>⚠️ Assumptions: this format is NOT the posting job's, and reusing that one is the most likely
     * string defect available in this file. The posting pair at {@code app/cbl/CBTRN02C.cbl:227-228}
     * pads BEFORE its colons so they align under a fixed-pitch terminal; every line here puts its
     * single space AFTER the colon. That difference is exactly why
     * {@code com.carddemo.batch.dto.BatchRunSummary} deliberately renders nothing at all — the
     * reference programs share no summary format, so a single renderer on that type would have to
     * invent one more — and it is why each job renders its own and no posting line appears here.</p>
     *
     * <p>Assumptions: the ORDER is asserted as well as the text, because the reference closes its
     * seven files before emitting any line, so a reader of the log knows the artefacts exist by the
     * time the first counter appears. A reordered report would break that reading.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("emit the nine report lines verbatim and in the reference's order")
    void theNineReportLinesAreVerbatimAndInTheReferenceOrder() throws Exception {
        stubOneRowPerRecordType();
        driveTheRoundTrip();

        List<String> emitted = recordedMessages();
        List<Integer> positions = new ArrayList<>();
        for (String prefix : REPORT_LINE_PREFIXES) {
            int position = -1;
            for (int index = 0; index < emitted.size(); index++) {
                if (emitted.get(index).startsWith(prefix)) {
                    position = index;
                    break;
                }
            }
            assertThat(position)
                    .as("the report line '%s' was not emitted", prefix)
                    .isNotNegative();
            positions.add(position);
        }
        assertThat(positions)
                .as("the report order at app/cbl/CBIMPORT.cbl:465-478 is part of the contract")
                .isSorted();
    }

    /**
     * The per-record-type breakdown sums to the total records read.
     *
     * <p>Pins the reconciliation the reference makes possible by counting both sides separately: its
     * total is incremented once per record inside the read loop at {@code app/cbl/CBIMPORT.cbl:253}
     * while each per-type counter is incremented inside its own mapping block, at {@code :320},
     * {@code :349}, {@code :369}, {@code :399} and {@code :422}, with the unknown-type counter at
     * {@code :427}.</p>
     *
     * <p>Assumptions: {@code com.carddemo.batch.dto.BatchRunSummary} carries the per-record-type
     * breakdown specifically for this pair — its own documentation names
     * {@code app/cbl/CBEXPORT.cbl:139-143} and {@code app/cbl/CBIMPORT.cbl:141-145} as the five-way
     * detail it exists for — and it maps the import's two remaining counters onto named components,
     * {@code recordsRejected} for {@code WS-ERROR-RECORDS-WRITTEN} and {@code recordsSkipped} for
     * {@code WS-UNKNOWN-RECORD-TYPE-COUNT} at {@code app/cbl/CBIMPORT.cbl:147}. The summary is
     * composed here from the counters the job reported rather than obtained from the job, because the
     * job renders its own report and constructs no summary; composing it is what checks that the value
     * type accepts the shape this job produces.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("sum the per-record-type breakdown to the total records read")
    void theRecordTypeBreakdownSumsToTheTotalRecordsRead() throws Exception {
        stubOneRowPerRecordType();
        byte[] mixed = concatenated(runTheExport(),
                oneExportRecordCarrying(UNRECOGNISED_DISCRIMINATOR, WIDE_SEQUENCE_NUMBER));
        this.published.clear();
        this.recorded.list.clear();
        stubTheArtefactRead(mixed);
        runTheImport();

        BatchRunSummary summary = summaryOfTheImportRun();
        assertThat(summary.recordTypeCounts())
                .as("one count per discriminator plus the errors and the unknowns")
                .hasSize(RecordType.values().length + 2);

        long breakdown = 0L;
        for (RecordType recordType : RecordType.values()) {
            breakdown += summary.recordTypeCounts().get(recordType.name());
        }
        breakdown += summary.recordsSkipped();

        assertThat(breakdown)
                .as("a total that disagrees with its parts describes two different runs")
                .isEqualTo(summary.recordsRead())
                .isEqualTo(RecordType.values().length + 1L);
        assertThat(summary.recordsSkipped()).isEqualTo(1L);
        assertThat(summary.recordsRejected()).isEqualTo(1L);
    }

    /**
     * A clean run reports the clean tier both as a returned value and as a process exit status.
     *
     * <p>Pins the numeric result contract. {@code app/cbl/CBIMPORT.cbl} sets no {@code RETURN-CODE} at
     * all, so a completed reference run ends at zero and a failed one abends through
     * {@code CALL 'CEE3ABD'} at {@code app/cbl/CBIMPORT.cbl:481-484}.</p>
     *
     * <p>Trade-offs: the result is asserted in exactly two forms — the value recorded for the
     * orchestrating state machine to read, and the framework exit code the same machine's choice
     * predicate reads — and never as a tolerance configured on a test runner. The graded five-tier
     * rubric documented in {@code tests/README.md} section 8 belongs exclusively to the parity oracle
     * suite, which aggregates the worst code seen; a Java gate is binary. What is given up is the
     * ability to report a partially successful build; what is bought is that a real failure cannot be
     * configured to read as an accepted warning, which is the only way the graded form could be wired
     * in here.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("report the clean tier as a recorded value and as an exit status")
    void aCleanRunReportsTierZeroAsValueAndAsExitStatus() throws Exception {
        stubOneRowPerRecordType();
        byte[] dataset = runTheExport();
        this.published.clear();
        stubTheArtefactRead(dataset);

        JobExecution execution = runTheImport();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(recordedReturnCodeOf(execution))
                .isEqualTo(BatchReturnCode.CLEAN.numericValue())
                .isEqualTo(BatchApplication.EXIT_STATUS_CLEAN);
        assertThat(execution.getExitStatus().getExitCode())
                .isNotEqualTo(BatchApplication.EXIT_CODE_COMPLETED_WITH_WARNINGS);
        assertThat(ImportJob.separate(BUSINESS_DATE, this.objectStore, BUCKET,
                new ImportJob.ImportStamp(IMPORT_STAMP_TOKEN)))
                .as("the body's own returned value, independent of the framework's status")
                .isEqualTo(BatchReturnCode.CLEAN);
    }

    /**
     * The soft-warn tier is unreportable by this job.
     *
     * <p>Pins the origin of that tier at {@code app/cbl/CBTRN02C.cbl:229-230}, where a non-zero reject
     * count selects {@code MOVE 4 TO RETURN-CODE}. No such statement exists in
     * {@code app/cbl/CBIMPORT.cbl}, so the tier is unreachable here by construction.</p>
     *
     * <p>Assumptions: the prohibition is enforced by the value type rather than merely observed — the
     * compact constructor of {@code com.carddemo.batch.dto.BatchRunSummary} refuses the soft-warn tier
     * for any job other than the posting one — so this case asserts the refusal rather than asserting
     * that the tier merely happens not to arrive.</p>
     */
    @Test
    @DisplayName("refuse the soft-warn tier for this job")
    void theSoftWarnTierIsUnreportableByThisJob() {
        assertThatThrownBy(() -> new BatchRunSummary(RUN_ID, ImportJob.STEP_NAME,
                BatchJobName.IMPORT, BatchReturnCode.SOFT_WARN, 1L, 1L, 0L, 0L, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(BatchJobName.POST_TRANSACTIONS.token());
    }

    /**
     * An empty artefact produces six valid empty outputs and zero counters.
     *
     * <p>Pins the data-definition disposition {@code DISP=(NEW,CATLG,DELETE)} that
     * {@code app/jcl/CBIMPORT.jcl:33}, {@code :38}, {@code :43}, {@code :48} and {@code :56} declare,
     * and the opens at {@code app/cbl/CBIMPORT.cbl:196-245}, which create every output whether or not
     * the input holds that record type.</p>
     *
     * <p>Assumptions: an empty artefact must EXIST rather than be omitted, because a consumer
     * distinguishing "no records of this type" from "the import did not run" has nothing else to read.
     * The card artefact is included, which is where divergence D-4 shows most plainly: the artefact
     * with no data definition is still created empty.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("produce six empty outputs and zero counters from an empty artefact")
    void anEmptyArtefactProducesSixEmptyOutputsAndZeroCounters() throws Exception {
        stubTheArtefactRead(new byte[0]);
        JobExecution execution = runTheImport();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.published).hasSize(RecordType.values().length + 1);
        for (RecordType recordType : RecordType.values()) {
            assertThat(artefactOf(recordType))
                    .as("the artefact for a type the input did not hold exists and is empty")
                    .isEmpty();
        }
        assertThat(this.published.get(ERROR_KEY)).isEmpty();
        for (String prefix : REPORT_LINE_PREFIXES.subList(1, REPORT_LINE_PREFIXES.size())) {
            assertThat(reportedCounterFor(prefix)).isZero();
        }
    }

    /**
     * A truncated artefact fails the run hard and publishes nothing at all.
     *
     * <p>Pins the refusal at {@code ImportJob}'s read loop, which reads one record length at a time and
     * treats a returned image shorter than that length -- and not empty -- as a truncated artefact
     * rather than as an end-of-data marker. These artefacts are {@code RECFM=FB}, so their length is
     * always a whole multiple of the record length; a remainder means bytes are missing.</p>
     *
     * <p>Assumptions: this branch has NO counterpart in the reference and is additive, so there is no
     * committed expectation to compare against and the ruling is stated here in full. The reference
     * reads a fixed-block dataset through record-level input, which cannot present a partial record at
     * all, so the condition it guards against is one the migrated stream can reach and the baseline
     * could not. It is registered as divergence {@code D-9}, and this case exists to hold that register
     * entry to its word: the entry claims the job logs the short image with its offset, its remaining
     * bytes and its expected bytes, raises before finalisation is reached, publishes none of the six
     * artefacts and reports the hard-failure tier. Each of those four claims is asserted below, because
     * a divergence the register describes and nothing verifies is a claim, not a guarantee.</p>
     *
     * <p>Refactoring Rationale: the refusal itself was already documented in the production body as a
     * correction of an earlier shape that recorded the short image and broke out of the loop -- which
     * let a run publish all six artefacts and report a clean tier from a source it had only partly read.
     * Nothing asserted it. The gap is the worst shape a gap can take: the earlier, wrong behaviour and
     * the corrected behaviour differ only in what happens AFTER the short image is noticed, so a
     * regression to it would leave every other case in this file passing while producing six artefacts
     * that look complete and are not.</p>
     *
     * <p>Assumptions: the artefact is a REAL export dataset cut short rather than a hand-built one, and
     * the choice was forced by a first attempt that failed for the wrong reason. Two blank-filled
     * records carrying real discriminators were refused by the record DECODER before the loop ever
     * reached the remainder -- {@code EXP-CUST-SSN} is an unsigned numeric field and blanks are not
     * digits -- so the run failed, the status assertion passed, and the case proved nothing about
     * truncation. Truncating the export's own output is what puts two genuinely decodable records in
     * front of the remainder, which is also the real-world shape of the condition: a well-formed
     * artefact whose tail is missing.</p>
     *
     * <p>Assumptions: TWO whole records precede the remainder, so the failure is reached only after the
     * loop has successfully processed records -- which is what makes the "no import artefact is
     * published" half meaningful. A dataset consisting of nothing but a remainder would fail on its
     * first read and would not distinguish a refusal from a job that could not read at all.</p>
     *
     * <p>Assumptions: FOUR distinct properties are asserted, because the resolution of this condition
     * is a conjunction and each part fails differently. The run FAILS rather than warning -- reusing the
     * warn tier would make a damaged input indistinguishable from a clean run that rejected rows.
     * NOTHING is published -- the six accumulators are discarded with the failed run. The operator is
     * TOLD, through the warning line, which is the one channel the discarded accumulators do not take
     * with them. And no numeric result is recorded for the state machine, so no downstream choice
     * predicate can read this run as clean.</p>
     *
     * <p>Assumptions: the durable failure ROW is not asserted here and its owner is named instead. The
     * step ledger is a stub in this class, so what is observable here is that the refusal happens INSIDE
     * the ledger-guarded body -- which is what routes it to the failure path -- while the path itself,
     * that a raising body marks the row {@code HARD_FAILURE} through the independent writer and
     * rethrows, is asserted by {@code com.carddemo.batch.service.BatchServicesTest}. Restating it here
     * would put one ruling in two places and the copy here would be the weaker of the two.</p>
     *
     * @throws Exception if the framework's own execution path raises, which this case does not provoke
     */
    @Test
    @DisplayName("fail hard on a truncated artefact, publishing no import artefact at all")
    void aTruncatedArtefactFailsTheRunAndPublishesNothing() throws Exception {
        int reclen = ExportRecordMapper.recordLayout().reclen();
        int remainder = reclen / 2;
        int wholeRecordBytes = 2 * reclen;
        stubOneRowPerRecordType();
        byte[] dataset = runTheExport();
        assertThat(dataset.length)
                .as("the export publishes one record per type, so there is a third record to cut")
                .isGreaterThan(wholeRecordBytes + remainder);

        byte[] truncated = Arrays.copyOf(dataset, wholeRecordBytes + remainder);
        this.recorded.list.clear();
        stubTheArtefactRead(truncated);

        JobExecution execution = runTheImport();

        assertThat(execution.getStatus())
                .as("a truncated input is a hard failure and never a warning")
                .isEqualTo(BatchStatus.FAILED);
        assertThat(failureTextOf(execution))
                .as("the refusal names the geometry, the offset and how much was readable")
                .contains("the export artefact is truncated", reclen + "-byte records were expected",
                        remainder + " bytes remain at offset " + wholeRecordBytes,
                        "2 whole records were read",
                        "no import artefact is published for a truncated input");
        assertThat(this.published)
                .as("the six accumulators are discarded with the failed run, so a consumer never sees"
                        + " an artefact assembled from a partly read source")
                .isEmpty();
        assertThat(recordedMessages())
                .as("the operator is told through the one channel the discarded accumulators do not"
                        + " take with them")
                .anySatisfy(message -> assertThat(message)
                        .contains("event=batch.import.short-record",
                                "offset=" + wholeRecordBytes,
                                "remaining=" + remainder,
                                "expected=" + reclen,
                                "Truncated record at end of export artefact"));
        assertThat(recordedReturnCodeOf(execution))
                .as("no numeric result reaches the state machine, so no downstream choice predicate"
                        + " can read this run as clean")
                .isEqualTo(Integer.MIN_VALUE);
        verify(this.stepLedger).runStep(eq(RUN_ID), eq(ImportJob.STEP_NAME),
                eq(BatchJobName.IMPORT), any());
    }

    /**
     * The durable ledger is consulted once, keyed on the run identifier and this step's name.
     *
     * <p>Refactoring Rationale: the durable step ledger is a strict IMPROVEMENT over the baseline and
     * not a port of it, and the distinction matters because a reader looking for the baseline
     * mechanism will not find one. The only {@code RESTART=} anywhere in the thirty-eight job-control
     * members is COMMENTED OUT, at {@code app/jcl/DEFGDGD.jcl:2}, and there is no {@code CHKPT=} in
     * any of them, so there is no checkpoint contract to preserve. What was wrong with the baseline is
     * that a rerun had nothing to consult: for this job in particular, a repeated body would append a
     * second copy of every record to all six artefacts rather than merely repeating a read.</p>
     *
     * <p>Assumptions: the key is the pair of the orchestrator's run identifier and the step name, which
     * is what makes one night's step distinct from the next night's and this job's step distinct from a
     * sibling's within one run.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("consult the durable ledger once per run identifier and step name")
    void theDurableLedgerIsConsultedOncePerRunAndStepName() throws Exception {
        stubTheArtefactRead(new byte[0]);
        runTheImport();

        verify(this.stepLedger, times(1)).runStep(eq(RUN_ID), eq(ImportJob.STEP_NAME),
                eq(BatchJobName.IMPORT), any());
    }

    /**
     * A replayed ledger entry publishes nothing at all.
     *
     * <p>Pins the idempotency the durable ledger supplies. When the recorded outcome for a
     * run-and-step pair already exists, the ledger replays it instead of evaluating the body.</p>
     *
     * <p>Assumptions: the assertion is that NOTHING was published, which is stronger than asserting
     * the numeric result. A replay that re-ran the body would append a second copy of every record to
     * all six artefacts, and an assertion on the tier alone would report that as success.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("publish nothing when the ledger replays a recorded outcome")
    void aReplayedLedgerEntryPublishesNothing() throws Exception {
        when(this.stepLedger.runStep(anyString(), anyString(), any(BatchJobName.class), any()))
                .thenReturn(new BatchStepLedger.StepOutcome(BatchReturnCode.CLEAN, true));
        stubTheArtefactRead(new byte[0]);

        JobExecution execution = runTheImport();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(this.published)
                .as("a replayed step must be a no-op and not a second set of artefacts")
                .isEmpty();
        verify(this.objectStore, never()).putObject(any(PutObjectRequest.class),
                any(RequestBody.class));
    }

    /**
     * The export and import steps write distinct ledger rows.
     *
     * <p>Pins the two step names the durable ledger keys on, which are the two job tokens
     * {@code com.carddemo.batch.dto.BatchJobName} publishes for this pair.</p>
     *
     * <p>Assumptions: the round trip is TWO runs and not one, so collapsing them onto one ledger row
     * would break restart semantics for both — a replay of the pair would find the export's outcome
     * recorded against the import's key and skip the wrong half. This is asserted by launching both
     * jobs under one run identifier and observing two distinct step names reach the ledger.</p>
     *
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    @Test
    @DisplayName("write distinct ledger rows for the export step and the import step")
    void theExportAndImportStepsWriteDistinctLedgerRows() throws Exception {
        assertThat(ImportJob.STEP_NAME).isNotEqualTo(ExportJob.STEP_NAME);

        stubOneRowPerRecordType();
        driveTheRoundTrip();

        verify(this.stepLedger).runStep(eq(RUN_ID), eq(ExportJob.STEP_NAME),
                eq(BatchJobName.EXPORT), any());
        verify(this.stepLedger).runStep(eq(RUN_ID), eq(ImportJob.STEP_NAME),
                eq(BatchJobName.IMPORT), any());
    }

    /**
     * Runs the export and then feeds its dataset straight to the import.
     *
     * <p>Assumptions: this is the round trip in one call, and it exists so that no case has to spell
     * the two-launch sequence out and risk one of them reading a stale dataset.</p>
     *
     * @throws Exception if either job's framework execution path raises, which no case here provokes
     */
    private void driveTheRoundTrip() throws Exception {
        // WHY : Assumptions: the round trip is TWO launches and is deliberately not collapsed into one
        //       job with two steps. The durable ledger keys a row on the pair of run identifier and step
        //       name, and each job publishes its own step name, so one launch would write one row where
        //       the orchestrator expects two and a resume would then treat a completed export as
        //       covering the import as well. The direction is the declared one: the production
        //       ImportJob carries ExportJob among its dependencies, so the consumer drives the producer
        //       and never the reverse.
        byte[] dataset = runTheExport();
        stubTheArtefactRead(dataset);
        runTheImport();
    }

    /**
     * Launches the export job and hands back the dataset it published.
     *
     * <p>Assumptions: the export's own object is REMOVED from the published map before returning, so
     * that map holds import artefacts and nothing else. Every case that counts published keys would
     * otherwise have to subtract one for the producer's object, and one that forgot would pass on a
     * count that was wrong in the other direction.</p>
     *
     * @return the 500-byte multi-record dataset the export published; never {@code null}
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    private byte[] runTheExport() throws Exception {
        runJob(this.exportJob, ExportJob.JOB_NAME);
        byte[] dataset = this.published.remove(EXPORT_KEY);
        assertThat(dataset)
                .as("the export must have published its dataset under %s", EXPORT_KEY)
                .isNotNull();
        return dataset;
    }

    /**
     * Launches the import job with both required parameters.
     *
     * @return the completed job execution; never {@code null}
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    private JobExecution runTheImport() throws Exception {
        return runJob(this.importJob, ImportJob.JOB_NAME);
    }

    /**
     * Launches one job through the framework and returns its execution.
     *
     * <p>Assumptions: the instance and the execution are constructed directly and then registered
     * rather than obtained from a convenience overload, because the repository's own creation method
     * takes an instance — so asking it for one by name would depend on an overload the interface these
     * jobs are built against does not declare. The sibling posting case establishes the same
     * approach.</p>
     *
     * @param job the job to launch, already built over the stubbed collaborators; must not be
     *     {@code null}
     * @param jobName the name the job instance is registered under, which must be the job's own
     *     registered name so the repository and the job agree
     * @return the completed job execution; never {@code null}
     * @throws Exception if the framework's own execution path raises, which no case here provokes
     */
    private JobExecution runJob(Job job, String jobName) throws Exception {
        JobParameters parameters = new JobParametersBuilder()
                .addString(BatchApplication.BUSINESS_DATE_PARAMETER, BUSINESS_DATE.token(), true)
                .addString(BatchConfig.RUN_ID_PARAMETER, RUN_ID, false)
                .toJobParameters();

        this.launchOrdinal++;
        JobInstance instance = new JobInstance(this.launchOrdinal, jobName);
        JobExecution execution = new JobExecution(this.launchOrdinal, instance, parameters);
        this.jobRepository.update(execution);
        job.execute(execution);
        return execution;
    }

    /**
     * Reports the exception the import job's validator raises for a set of parameters.
     *
     * @param parameters the parameters to validate; must not be {@code null}
     * @return the raised exception, or {@code null} when the parameters were accepted
     */
    private Exception catchValidation(JobParameters parameters) {
        try {
            this.importJob.getJobParametersValidator().validate(parameters);
            return null;
        } catch (Exception refused) {
            return refused;
        }
    }

    /**
     * Reads the numeric result the step recorded for the orchestrating state machine.
     *
     * <p>Assumptions: the value is read from the step's execution context under the key the shared
     * step builder publishes, which is the same place the exit-status listener reads it from — so this
     * asserts the value a state machine's choice predicate would actually see rather than a value
     * reconstructed here.</p>
     *
     * @param execution the completed job execution whose single step's context is read; must not be
     *     {@code null}
     * @return the recorded numeric result
     */
    private static int recordedReturnCodeOf(JobExecution execution) {
        assertThat(execution.getStepExecutions()).hasSize(1);
        StepExecution step = execution.getStepExecutions().iterator().next();
        return step.getExecutionContext()
                .getInt(BatchConfig.LedgerGuardedStep.RETURN_CODE_KEY, Integer.MIN_VALUE);
    }

    /**
     * Stubs the object store to stream one dataset back on every read.
     *
     * <p>Assumptions: a FRESH stream is supplied on every invocation rather than one shared instance,
     * because a stream is consumed by its first reader and a case that imports twice would otherwise
     * see an empty artefact the second time.</p>
     *
     * <p>Assumptions: the declared content length is set from the array's own length so that the
     * response object this stub returns is internally consistent with the stream behind it, and for no
     * other reason. It is NOT what drives the truncation refusal.</p>
     *
     * <p>Refactoring Rationale: this block previously said the import body "reads it to refuse an
     * artefact whose length is not a whole multiple of the record length" and that leaving it unset
     * "would silently skip that check". Both are false: {@code GetObjectResponse.contentLength} is read
     * nowhere in {@code ImportJob}, and the refusal is driven by the STREAM -- the read loop calls
     * {@code readNBytes} for one record length at a time and refuses the batch when the returned array
     * is shorter than that length and not empty. The distinction is not academic. The claim as written
     * described a check on a declared header, which a reader could have satisfied by trusting a length a
     * caller supplied; what the job actually does is refuse on the bytes it received, which is the only
     * form that survives a response whose declared length disagrees with its body.</p>
     *
     * @param dataset the bytes the read returns, possibly empty; must not be {@code null}
     */
    private void stubTheArtefactRead(byte[] dataset) {
        when(this.objectStore.getObject(any(GetObjectRequest.class))).thenAnswer(call ->
                new ResponseInputStream<>(
                        GetObjectResponse.builder().contentLength((long) dataset.length).build(),
                        AbortableInputStream.create(new ByteArrayInputStream(dataset))));
    }

    /**
     * Stubs all five masters with exactly one row each, so a round trip covers every record type.
     *
     * <p>Assumptions: all five and not three. With two masters empty, the customer and card branches of
     * the dispatch would be reached by no case at all while every count still reconciled with itself,
     * which is precisely the shape of gap that reaches a review rather than a build.</p>
     */
    private void stubOneRowPerRecordType() {
        when(this.customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.of(customerRow()));
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.of(accountRow()));
        when(this.crossReferences.findAllByOrderByCardNumAsc())
                .thenReturn(Stream.of(crossReferenceRow()));
        when(this.transactions.findAllByOrderByTransactionIdAsc())
                .thenReturn(Stream.of(transactionRow()));
        when(this.cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.of(cardRow()));
    }

    /**
     * Stubs every master as an empty stream.
     *
     * <p>Assumptions: an EMPTY stream rather than an unstubbed mock. An unstubbed repository returns
     * {@code null} and the export phase would fail on it, so the absence has to be stated — and stating
     * it keeps each case's expected record count derivable from what that case stubbed.</p>
     */
    private void stubEveryMasterEmpty() {
        when(this.customers.findAllByOrderByCustomerIdAsc()).thenReturn(Stream.empty());
        when(this.accounts.findAllByOrderByAccountIdAsc()).thenReturn(Stream.empty());
        when(this.crossReferences.findAllByOrderByCardNumAsc()).thenReturn(Stream.empty());
        when(this.transactions.findAllByOrderByTransactionIdAsc()).thenReturn(Stream.empty());
        when(this.cards.findAllByOrderByCardNumAsc()).thenReturn(Stream.empty());
    }

    /**
     * Re-stubs exactly one master with a single row, leaving the other four empty.
     *
     * <p>Assumptions: the switch is exhaustive over the record types, so a sixth type added to the
     * export contract would fail to compile here rather than silently seed nothing and leave a case
     * asserting an empty artefact against an empty input.</p>
     *
     * @param recordType the one record type to seed, selecting which master receives the row
     */
    private void stubOnlyTheMasterFor(RecordType recordType) {
        switch (recordType) {
            case CUSTOMER -> when(this.customers.findAllByOrderByCustomerIdAsc())
                    .thenReturn(Stream.of(customerRow()));
            case ACCOUNT -> when(this.accounts.findAllByOrderByAccountIdAsc())
                    .thenReturn(Stream.of(accountRow()));
            case CARD_XREF -> when(this.crossReferences.findAllByOrderByCardNumAsc())
                    .thenReturn(Stream.of(crossReferenceRow()));
            case TRANSACTION -> when(this.transactions.findAllByOrderByTransactionIdAsc())
                    .thenReturn(Stream.of(transactionRow()));
            case CARD -> when(this.cards.findAllByOrderByCardNumAsc())
                    .thenReturn(Stream.of(cardRow()));
        }
    }

    /**
     * Builds the customer row the round trip carries.
     *
     * <p>Assumptions: every character value is shorter than its declared span, so the encode exercises
     * the codec's blank padding rather than its exact-fit path — a fixture sized exactly to each span
     * would pass even if the padding rule were wrong.</p>
     *
     * @return the customer row; never {@code null}
     */
    private static Customer customerRow() {
        return new Customer(CUSTOMER_ID, FIRST_NAME, MIDDLE_NAME, LAST_NAME, ADDR_LINE_1,
                ADDR_LINE_2, ADDR_LINE_3, STATE_CD, COUNTRY_CD, CUSTOMER_ZIP, PHONE_1, PHONE_2,
                DATE_OF_BIRTH, EFT_ACCOUNT_ID, PRI_CARD_HOLDER_IND, FICO_SCORE);
    }

    /**
     * Builds the account row the round trip carries.
     *
     * @return the account row, four of whose five amounts are negative; never {@code null}
     */
    private static Account accountRow() {
        return new Account(ACCOUNT_ID, ACCOUNT_STATUS, CURR_BAL, CREDIT_LIMIT, CASH_CREDIT_LIMIT,
                OPEN_DATE, ACCOUNT_EXPIRATION_DATE, REISSUE_DATE, CURR_CYC_CREDIT, CURR_CYC_DEBIT,
                ACCOUNT_ZIP, GROUP_ID);
    }

    /**
     * Builds the cross-reference row the round trip carries.
     *
     * @return the cross-reference row; never {@code null}
     */
    private static CardXref crossReferenceRow() {
        return new CardXref(CARD_NUM, CUSTOMER_ID, ACCOUNT_ID);
    }

    /**
     * Builds the transaction row the round trip carries.
     *
     * @return the transaction row, carrying a negative amount; never {@code null}
     */
    private static Transaction transactionRow() {
        Transaction row = new Transaction(TRANSACTION_ID);
        row.setTypeCd(TRAN_TYPE_CD);
        row.setCategoryCd(TRAN_CAT_CD);
        row.setSource(TRAN_SOURCE);
        row.setDescription(TRAN_DESC);
        row.setAmount(TRAN_AMOUNT);
        row.setMerchantId(MERCHANT_ID);
        row.setMerchantName(MERCHANT_NAME);
        row.setMerchantCity(MERCHANT_CITY);
        row.setMerchantZip(MERCHANT_ZIP);
        row.setCardNum(CARD_NUM);
        row.setOrigTs(ORIG_TS);
        row.setProcTs(PROC_TS);
        return row;
    }

    /**
     * Builds the card row the round trip carries.
     *
     * @return the card row; never {@code null}
     */
    private static Card cardRow() {
        return new Card(CARD_NUM, ACCOUNT_ID, EMBOSSED_NAME, CARD_EXPIRATION_DATE, CARD_STATUS);
    }

    /**
     * Assembles the customer master image this class expects the import to re-materialise.
     *
     * <p>Assumptions: the two protected identifiers are asserted REDACTED rather than carried — the
     * national identifier as zero and the government-issued identifier as blank — because the migration
     * plan states at its section 0.4.1.9 that identifying values are not carried in the clear, and the
     * export elides them on the way out so there is nothing for the import to restore. The trailing
     * {@code FILLER} is omitted from the map and blank-filled by the encoder, which is what the
     * reference's own {@code INITIALIZE} at {@code app/cbl/CBIMPORT.cbl:290} does to the same bytes.</p>
     *
     * @return the expected 500-byte image; never {@code null}
     */
    private static byte[] expectedCustomerImage() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("CUST-ID", CUSTOMER_ID);
        fields.put("CUST-FIRST-NAME", FIRST_NAME);
        fields.put("CUST-MIDDLE-NAME", MIDDLE_NAME);
        fields.put("CUST-LAST-NAME", LAST_NAME);
        fields.put("CUST-ADDR-LINE-1", ADDR_LINE_1);
        fields.put("CUST-ADDR-LINE-2", ADDR_LINE_2);
        fields.put("CUST-ADDR-LINE-3", ADDR_LINE_3);
        fields.put("CUST-ADDR-STATE-CD", STATE_CD);
        fields.put("CUST-ADDR-COUNTRY-CD", COUNTRY_CD);
        fields.put("CUST-ADDR-ZIP", CUSTOMER_ZIP);
        fields.put("CUST-PHONE-NUM-1", PHONE_1);
        fields.put("CUST-PHONE-NUM-2", PHONE_2);
        fields.put("CUST-SSN", 0L);
        fields.put("CUST-GOVT-ISSUED-ID", "");
        fields.put("CUST-DOB-YYYY-MM-DD", DATE_OF_BIRTH.toString());
        fields.put("CUST-EFT-ACCOUNT-ID", EFT_ACCOUNT_ID);
        fields.put("CUST-PRI-CARD-HOLDER-IND", PRI_CARD_HOLDER_IND);
        fields.put("CUST-FICO-CREDIT-SCORE", (long) FICO_SCORE);
        return FixedWidthCodec.encodeRecord(fields, CopybookLayout.layout("CUSTOMER"),
                ARTEFACT_CHARSET);
    }

    /**
     * Assembles the account master image this class expects the import to re-materialise.
     *
     * <p>Assumptions: the misspelled field name {@code ACCT-EXPIRAION-DATE} is used as the baseline
     * spells it, because the registered descriptor transcribes the copybook rather than correcting it;
     * the correction lives in the target column name and is recorded in the migration's own schema
     * mapping.</p>
     *
     * @return the expected 300-byte image; never {@code null}
     */
    private static byte[] expectedAccountImage() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ACCT-ID", ACCOUNT_ID);
        fields.put("ACCT-ACTIVE-STATUS", ACCOUNT_STATUS);
        fields.put("ACCT-CURR-BAL", CURR_BAL);
        fields.put("ACCT-CREDIT-LIMIT", CREDIT_LIMIT);
        fields.put("ACCT-CASH-CREDIT-LIMIT", CASH_CREDIT_LIMIT);
        fields.put("ACCT-OPEN-DATE", OPEN_DATE.toString());
        fields.put("ACCT-EXPIRAION-DATE", ACCOUNT_EXPIRATION_DATE.toString());
        fields.put("ACCT-REISSUE-DATE", REISSUE_DATE.toString());
        fields.put("ACCT-CURR-CYC-CREDIT", CURR_CYC_CREDIT);
        fields.put("ACCT-CURR-CYC-DEBIT", CURR_CYC_DEBIT);
        fields.put("ACCT-ADDR-ZIP", ACCOUNT_ZIP);
        fields.put("ACCT-GROUP-ID", GROUP_ID);
        return FixedWidthCodec.encodeRecord(fields, CopybookLayout.layout("ACCOUNT"),
                ARTEFACT_CHARSET);
    }

    /**
     * Assembles the cross-reference image this class expects the import to re-materialise.
     *
     * @return the expected 50-byte image; never {@code null}
     */
    private static byte[] expectedCrossReferenceImage() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("XREF-CARD-NUM", CARD_NUM);
        fields.put("XREF-CUST-ID", CUSTOMER_ID);
        fields.put("XREF-ACCT-ID", ACCOUNT_ID);
        return FixedWidthCodec.encodeRecord(fields, CopybookLayout.layout("XREF"),
                ARTEFACT_CHARSET);
    }

    /**
     * Assembles the transaction image this class expects the import to re-materialise.
     *
     * <p>Assumptions: both timestamps are rendered through the shared formatter rather than written as
     * literals, so this class cannot come to disagree with the 26-character contract form the encoder
     * produced. The category code is supplied as a NUMBER because the master declares the field
     * unsigned numeric, unlike the four characters the entity carries.</p>
     *
     * @return the expected 350-byte image; never {@code null}
     */
    private static byte[] expectedTransactionImage() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("TRAN-ID", TRANSACTION_ID);
        fields.put("TRAN-TYPE-CD", TRAN_TYPE_CD);
        fields.put("TRAN-CAT-CD", TRAN_CAT_CD_NUMERIC);
        fields.put("TRAN-SOURCE", TRAN_SOURCE);
        fields.put("TRAN-DESC", TRAN_DESC);
        fields.put("TRAN-AMT", TRAN_AMOUNT);
        fields.put("TRAN-MERCHANT-ID", MERCHANT_ID);
        fields.put("TRAN-MERCHANT-NAME", MERCHANT_NAME);
        fields.put("TRAN-MERCHANT-CITY", MERCHANT_CITY);
        fields.put("TRAN-MERCHANT-ZIP", MERCHANT_ZIP);
        fields.put("TRAN-CARD-NUM", CARD_NUM);
        fields.put("TRAN-ORIG-TS", TimestampFormatter.format(ORIG_TS));
        fields.put("TRAN-PROC-TS", TimestampFormatter.format(PROC_TS));
        return FixedWidthCodec.encodeRecord(fields, CopybookLayout.layout("TRAN"),
                ARTEFACT_CHARSET);
    }

    /**
     * Assembles the card image this class expects the import to re-materialise.
     *
     * <p>Assumptions: the verification value is asserted REDACTED as zero. The migration plan states at
     * its section 0.4.1.9 that it is never returned, and the export carries it only as an opaque span,
     * so a re-materialised card image cannot hold it and must not appear to.</p>
     *
     * @return the expected 150-byte image; never {@code null}
     */
    private static byte[] expectedCardImage() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("CARD-NUM", CARD_NUM);
        fields.put("CARD-ACCT-ID", ACCOUNT_ID);
        fields.put("CARD-CVV-CD", 0L);
        fields.put("CARD-EMBOSSED-NAME", EMBOSSED_NAME);
        fields.put("CARD-EXPIRAION-DATE", CARD_EXPIRATION_DATE.toString());
        fields.put("CARD-ACTIVE-STATUS", CARD_STATUS);
        return FixedWidthCodec.encodeRecord(fields, CopybookLayout.layout("CARD"),
                ARTEFACT_CHARSET);
    }

    /**
     * Builds one 500-byte export record carrying a chosen discriminator and sequence number.
     *
     * <p>Assumptions: only the five prefix fields are populated and the 460-byte payload span is left
     * blank, because a record reaching the unknown-type arm is never interpreted — the dispatch fails
     * closed before any view is selected, so payload content would be unread and stating it would imply
     * otherwise. Every prefix field is written through the shared encoder against the record
     * descriptor, which matters most for the sequence number: it is a four-byte binary field, so
     * writing nine characters there by hand would shift nothing and mean nothing.</p>
     *
     * @param discriminator the single character to place in the discriminator field, which decides
     *     which dispatch arm the record reaches
     * @param sequence the sequence number to place in the four-byte binary prefix field, which the
     *     diagnostic record then narrows to seven digits
     * @return one complete record image, exactly the export record length; never {@code null}
     */
    private static byte[] oneExportRecordCarrying(char discriminator, long sequence) {
        CopybookLayout.RecordSpec layout = ExportRecordMapper.recordLayout();
        byte[] image = new byte[layout.reclen()];
        Arrays.fill(image, (byte) ' ');
        FixedWidthCodec.encodeField(String.valueOf(discriminator),
                layout.field("EXPORT-REC-TYPE"), image, ARTEFACT_CHARSET);
        FixedWidthCodec.encodeField(EXPORT_STAMP_TOKEN, layout.field("EXPORT-TIMESTAMP"), image,
                ARTEFACT_CHARSET);
        FixedWidthCodec.encodeField(sequence, layout.field("EXPORT-SEQUENCE-NUM"), image,
                ARTEFACT_CHARSET);
        FixedWidthCodec.encodeField(ExportJob.DEFAULT_BRANCH_ID, layout.field("EXPORT-BRANCH-ID"),
                image, ARTEFACT_CHARSET);
        FixedWidthCodec.encodeField(ExportJob.DEFAULT_REGION_CODE,
                layout.field("EXPORT-REGION-CODE"), image, ARTEFACT_CHARSET);
        return image;
    }

    /**
     * Joins record images into one dataset, in the order given.
     *
     * @param parts the images to join, each already a whole number of records; must not be
     *     {@code null}
     * @return one dataset holding every part end to end; never {@code null}
     */
    private static byte[] concatenated(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] joined = new byte[total];
        int cursor = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, cursor, part.length);
            cursor += part.length;
        }
        return joined;
    }

    /**
     * Returns the artefact published for one record type.
     *
     * @param recordType the record type whose artefact is wanted; must not be {@code null}
     * @return the published bytes, possibly empty; never {@code null}
     */
    private byte[] artefactOf(RecordType recordType) {
        return artefactAt(recordType.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Returns the artefact published under one member name of the import partition.
     *
     * @param member the member name without its extension, for example {@code card_xref}, which
     *     together with the partition prefix forms the object key
     * @return the published bytes, possibly empty; never {@code null}
     */
    private byte[] artefactAt(String member) {
        byte[] artefact = this.published.get(IMPORT_PARTITION + member + ".dat");
        assertThat(artefact)
                .as("no artefact was published under %s%s.dat", IMPORT_PARTITION, member)
                .isNotNull();
        return artefact;
    }

    /**
     * Reports the declared record length for one record type.
     *
     * <p>Assumptions: the switch is exhaustive, so a sixth record type could not acquire an artefact
     * without a declared width, and the five constants it returns are cited to the data definitions
     * they come from — with the card's cited to the program instead, which is divergence D-4.</p>
     *
     * @param recordType the record type whose declared width is wanted; must not be {@code null}
     * @return the fixed record length in bytes
     */
    private static int declaredReclenOf(RecordType recordType) {
        return switch (recordType) {
            case CUSTOMER -> CUSTOMER_RECLEN;
            case ACCOUNT -> ACCOUNT_RECLEN;
            case CARD_XREF -> XREF_RECLEN;
            case TRANSACTION -> TRANSACTION_RECLEN;
            case CARD -> CARD_RECLEN;
        };
    }

    /**
     * Splits the diagnostic artefact into its fixed-length records.
     *
     * @return one entry per 132-byte record, in the order written; never {@code null}
     */
    private List<byte[]> diagnosticRecords() {
        byte[] artefact = this.published.get(ERROR_KEY);
        assertThat(artefact)
                .as("the diagnostic artefact must be published even when it is empty")
                .isNotNull();
        assertThat(artefact.length % ERROR_RECLEN)
                .as("a fixed-length artefact whose length is not a whole multiple is unreadable")
                .isZero();

        List<byte[]> records = new ArrayList<>();
        for (int offset = 0; offset < artefact.length; offset += ERROR_RECLEN) {
            records.add(Arrays.copyOfRange(artefact, offset, offset + ERROR_RECLEN));
        }
        return records;
    }

    /**
     * Reads one span of a record as text.
     *
     * @param record the record image to read from; must not be {@code null}
     * @param offset the ZERO-based byte offset the span begins at, counted from the start of the
     *     record exactly as a copybook offset is
     * @param width the number of bytes the span occupies, which is the field's declared picture width
     * @return the span decoded as US-ASCII, padding included and never trimmed; never {@code null}
     */
    private static String textAt(byte[] record, int offset, int width) {
        return new String(Arrays.copyOfRange(record, offset, offset + width), ARTEFACT_CHARSET);
    }

    /**
     * Reads one byte of a record as a character.
     *
     * @param record the record image to read from; must not be {@code null}
     * @param offset the ZERO-based byte offset of the single byte wanted
     * @return that byte as a character
     */
    private static char charAt(byte[] record, int offset) {
        return (char) (record[offset] & 0xFF);
    }

    /**
     * Decodes one named field of a record through the shared codec.
     *
     * <p>Assumptions: the decode goes through the sanctioned codec against the registered descriptor
     * rather than through arithmetic written here, because that codec is the one place that knows how a
     * zoned sign overpunch, a packed nibble and a binary field each decode. A second decoder in this
     * file could disagree with it and be believed.</p>
     *
     * @param record the record image to read from; must not be {@code null}
     * @param layoutName the registry key of the descriptor the field belongs to; must not be
     *     {@code null}
     * @param fieldName the copybook name of the field to decode; must not be {@code null}
     * @return the decoded value, whose runtime type follows the field's storage kind; never
     *     {@code null}
     */
    private static Object decodedField(byte[] record, String layoutName, String fieldName) {
        return FixedWidthCodec.decodeField(record,
                CopybookLayout.layout(layoutName).field(fieldName), ARTEFACT_CHARSET);
    }

    /**
     * Flattens every failure an execution recorded, with its causes, into one searchable string.
     *
     * <p>Assumptions: the CAUSE CHAIN is walked rather than only the top message read, because the
     * framework wraps a tasklet's own exception before recording it -- so the message that names the
     * offset and the remaining byte count sits on a cause. The self-referential guard is what stops a
     * throwable whose cause is itself from looping here forever.</p>
     *
     * @param execution the finished execution whose recorded failures are wanted; must not be
     *     {@code null}
     * @return the concatenated failure messages, empty when the run recorded none; never {@code null}
     */
    private static String failureTextOf(JobExecution execution) {
        StringBuilder text = new StringBuilder();
        for (Throwable failure : execution.getAllFailureExceptions()) {
            Throwable link = failure;
            while (link != null) {
                text.append(link.getMessage()).append(System.lineSeparator());
                link = link.getCause() == link ? null : link.getCause();
            }
        }
        return text.toString();
    }

    /**
     * Returns every message the import job logged during the case, in order.
     *
     * @return the formatted messages, in emission order; never {@code null}
     */
    private List<String> recordedMessages() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : this.recorded.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }

    /**
     * Reads the counter value the import job reported on one of its nine report lines.
     *
     * <p>Assumptions: the line is located by its VERBATIM prefix and the counter is whatever follows
     * it, so a case cannot pass on a line whose label was reworded. That is the point of locating by
     * prefix rather than by position: a reordered report is caught by the ordering case, and a
     * reworded label is caught here.</p>
     *
     * @param prefix the report line's verbatim label, including its trailing space, exactly as
     *     {@code app/cbl/CBIMPORT.cbl:465-478} spells it
     * @return the counter value that followed the label
     * @throws AssertionError if no emitted line began with that label, which means the report line is
     *     absent or its text has drifted from the reference
     */
    private long reportedCounterFor(String prefix) {
        for (String message : recordedMessages()) {
            if (message.startsWith(prefix)) {
                return Long.parseLong(message.substring(prefix.length()).trim());
            }
        }
        throw new AssertionError("no report line began with '" + prefix
                + "', so the counter it carries was never emitted");
    }

    /**
     * Composes the run summary this import run's counters describe.
     *
     * <p>Assumptions: the summary is COMPOSED here from the counters the job reported rather than
     * obtained from the job, because this job renders its own nine report lines and constructs no
     * summary — the value type deliberately renders nothing, since the reference programs share no
     * summary format. Composing it is what checks that the type accepts the shape this job produces,
     * and its own documentation names this program's counters as the detail it carries: the error count
     * maps to {@code recordsRejected} and the unknown-type count at
     * {@code app/cbl/CBIMPORT.cbl:147} maps to {@code recordsSkipped}.</p>
     *
     * @return the composed summary; never {@code null}
     */
    private BatchRunSummary summaryOfTheImportRun() {
        long total = reportedCounterFor("CBIMPORT: Total Records Read: ");
        long errors = reportedCounterFor("CBIMPORT: Errors Written: ");
        long unknown = reportedCounterFor("CBIMPORT: Unknown Record Types: ");

        Map<String, Long> breakdown = new LinkedHashMap<>();
        breakdown.put(RecordType.CUSTOMER.name(),
                reportedCounterFor("CBIMPORT: Customers Imported: "));
        breakdown.put(RecordType.ACCOUNT.name(),
                reportedCounterFor("CBIMPORT: Accounts Imported: "));
        breakdown.put(RecordType.CARD_XREF.name(), reportedCounterFor("CBIMPORT: XRefs Imported: "));
        breakdown.put(RecordType.TRANSACTION.name(),
                reportedCounterFor("CBIMPORT: Transactions Imported: "));
        breakdown.put(RecordType.CARD.name(), reportedCounterFor("CBIMPORT: Cards Imported: "));
        breakdown.put("ERRORS_WRITTEN", errors);
        breakdown.put("UNKNOWN_RECORD_TYPES", unknown);

        long written = 0L;
        for (RecordType recordType : RecordType.values()) {
            written += breakdown.get(recordType.name());
        }
        return new BatchRunSummary(RUN_ID, ImportJob.STEP_NAME, BatchJobName.IMPORT,
                BatchReturnCode.CLEAN, total, written, errors, unknown, breakdown);
    }

    /**
     * Lists every member name the system under test declares, nested types included.
     *
     * <p>Assumptions: fields, methods and nested type names are gathered together because a digest
     * could be introduced as any of the three, and the invariant divergence D-5 records is that none of
     * them exists rather than that one particular shape of it does not.</p>
     *
     * @return the declared field, method and nested type names; never {@code null}
     */
    private static List<String> namedMembersOfTheSystemUnderTest() {
        List<String> names = new ArrayList<>();
        for (Field field : ImportJob.class.getDeclaredFields()) {
            names.add(field.getName());
        }
        for (Method method : ImportJob.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        for (Class<?> nested : ImportJob.class.getDeclaredClasses()) {
            names.add(nested.getSimpleName());
            for (Field field : nested.getDeclaredFields()) {
                names.add(field.getName());
            }
            for (Method method : nested.getDeclaredMethods()) {
                names.add(method.getName());
            }
        }
        return names;
    }

    /**
     * Lists every type the system under test can reach a collaborator through.
     *
     * <p>Assumptions: field types and the parameter types of every constructor and method are gathered,
     * because those are the only two ways a collaborator enters this class — it declares no static
     * lookup and no service locator. A repository or a persistence context appearing among them would
     * be a route to a business table, which is the invariant the no-table case asserts.</p>
     *
     * @return the declared field types and parameter types; never {@code null}
     */
    private static List<Class<?>> collaboratorTypesOfTheSystemUnderTest() {
        List<Class<?>> types = new ArrayList<>();
        for (Field field : ImportJob.class.getDeclaredFields()) {
            types.add(field.getType());
        }
        List<Executable> members = new ArrayList<>();
        members.addAll(Arrays.asList(ImportJob.class.getDeclaredConstructors()));
        members.addAll(Arrays.asList(ImportJob.class.getDeclaredMethods()));
        for (Executable member : members) {
            types.addAll(Arrays.asList(member.getParameterTypes()));
        }
        return types;
    }
}
