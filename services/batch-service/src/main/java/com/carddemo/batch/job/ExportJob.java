package com.carddemo.batch.job;

import com.carddemo.batch.config.BatchConfig.LedgerGuardedStep;
import com.carddemo.batch.domain.Account;
import com.carddemo.batch.domain.CardXref;
import com.carddemo.batch.domain.Transaction;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.mapper.ExportRecordMapper;
import com.carddemo.batch.mapper.ExportRecordMapper.ExportRecord;
import com.carddemo.batch.mapper.ExportRecordMapper.Prefix;
import com.carddemo.batch.mapper.ExportRecordMapper.RecordType;
import com.carddemo.batch.repository.AccountRepository;
import com.carddemo.batch.repository.CardXrefRepository;
import com.carddemo.batch.repository.TransactionRepository;
import com.carddemo.common.time.TimestampFormatter;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Writes the 500-byte multi-record branch-migration dataset, re-expressing
 * {@code app/cbl/CBEXPORT.cbl}.
 *
 * <h2>Purpose</h2>
 *
 * <p>The reference program walks five masters in a fixed order and appends one fixed-width record per
 * row to a single output. Each record is a 40-byte common prefix -- a one-character type
 * discriminator, a 26-character stamp, a four-byte binary sequence number, a branch identifier and a
 * region code -- followed by one of five mutually exclusive 460-byte payload views. The purpose the
 * baseline job control states for the artefact is
 * <em>"EXPORT CUSTOMER DATA FROM VSAM FILES TO MULTI-RECORD EXPORT FILE FOR BRANCH MIGRATION OR DATA
 * TRANSFER PURPOSES"</em>, at {@code app/jcl/CBEXPORT.jcl:19-20}. That stated purpose is load-bearing
 * below: it is why the branch identifier and region code are configurable here rather than frozen.
 *
 * <p>Parameters, return values, exceptions or errors. This is a bean-defining configuration class
 * with no state and no constructor of its own, so the type itself takes no parameter, yields no value
 * and raises nothing; every member below carries its own at-clauses. The inapplicability is stated
 * rather than passed over because user-specified Rule 1 (Explainability) forbids a docstring that
 * omits parameters, return values or purpose, and a reader must be able to tell a declared
 * inapplicability from an omission. The sibling jobs in this package state it the same way.
 *
 * <h2>Assumptions: this job has a driver, and it is not a state of the nightly chain</h2>
 *
 * <p>Two facts about this job's provenance are each easy to get backwards, and they pull in opposite
 * directions, so both are recorded here rather than inferred.
 *
 * <p><b>It has a driver.</b> {@code app/jcl/CBEXPORT.jcl:43} reads
 * {@code //STEP02 EXEC PGM=CBEXPORT}, preceded at {@code app/jcl/CBEXPORT.jcl:24} by an
 * {@code IDCAMS} step that deletes and redefines the target cluster. Among the batch programs only
 * {@code CBTRN01C} is genuinely driverless. The charter beside this file records the same conclusion
 * at {@code job/package-info.java:100-115}, and this module's {@code README.md:70,76-87} agrees, so
 * the three statements are consistent and a reader who finds a fourth claiming otherwise should
 * prefer the job control itself.
 *
 * <p><b>It is nonetheless unscheduled.</b> The eleven states of {@code carddemo-daily-batch} are
 * quiesce, stage, preflight, post, interest, backup, combine, statements, reports, analyze and
 * resume; export and import are not among them. Assumptions: this job is reachable only through the
 * container's {@code --job=} argument and nothing schedules it, which is exactly what the job control
 * header above implies -- a branch migration is an operator event, not a nightly one. A reader who
 * conflates <em>unscheduled</em> with <em>undriven</em> will look for a scheduling dependency that
 * does not exist, and a reader who conflates them the other way will look for a nightly slot that
 * does not exist either.
 *
 * <p>Assumptions: the reference is a main program and not a subprogram.
 * {@code app/cbl/CBEXPORT.cbl:146} is {@code PROCEDURE DIVISION.} with no {@code USING} phrase, so it
 * receives no parameter through linkage, unlike {@code app/cbl/CBACT04C.cbl}, which does. Combined
 * with the absence of any {@code PARM} on its driver -- the only {@code PARM} in the whole batch chain
 * is {@code app/jcl/INTCALC.jcl:22} -- the reference run is parameterless, which is the observation
 * divergence D-8 below acts on.
 *
 * <h2>Refactoring Rationale: divergence D-1, the file-description record key</h2>
 *
 * <p>{@code app/cbl/CBEXPORT.cbl:65-69} selects the output {@code ORGANIZATION IS INDEXED} with
 * {@code ACCESS MODE IS SEQUENTIAL} and declares {@code RECORD KEY IS EXPORT-SEQUENCE-NUM} at
 * {@code app/cbl/CBEXPORT.cbl:68}. The named key is not a field of the file record. The record is
 * unstructured -- {@code app/cbl/CBEXPORT.cbl:91-92} declare
 * {@code RECORD CONTAINS 500 CHARACTERS.} and {@code 01 EXPORT-OUTPUT-RECORD PIC X(500).} -- and
 * {@code EXPORT-SEQUENCE-NUM} is declared in working storage instead, because
 * {@code app/cbl/CBEXPORT.cbl:96} copies {@code CVEXPORT} beneath the
 * {@code WORKING-STORAGE SECTION.} at {@code app/cbl/CBEXPORT.cbl:94}.
 * {@code app/cbl/CBIMPORT.cbl:40} carries the identical declaration against the identical shape.
 *
 * <p>Three consequences are verified rather than assumed. The pair does not compile under the
 * open-source compiler, so ten of the twelve batch programs build and run and these two do not; no
 * compiler flag repairs it, because the defect is semantic; and the reference-only policy over
 * {@code app/**} forbids editing it. The build script therefore classifies the pair as
 * known-unsupported and aggregates a soft warning rather than poisoning the aggregate result, and the
 * export/import integration test is skipped for that stated reason.
 *
 * <p><b>The aggregate warn-level result this produces is the parity suite's GREEN state, and D-1 is
 * its sole cause.</b> It is not a regression, it is not attributable to this migration, and it is not
 * to be "fixed" in the COBOL.
 *
 * <p><b>The ruling.</b> Records are written sequentially, in emit order. The sequence number is a
 * field inside the payload, generated here and carried at zero-based offset 27; it is not a file
 * access key. There is no index, no keyed write and no record-key analogue anywhere in this class.
 * Refactoring Rationale: the indexed organisation bought the reference nothing. Every one of its five
 * writes is a group move from the working-storage buffer --
 * {@code WRITE EXPORT-OUTPUT-RECORD FROM EXPORT-RECORD} at {@code app/cbl/CBEXPORT.cbl:301},
 * {@code :364}, {@code :419}, {@code :484} and {@code :542} -- and the program never reads the file
 * back and never accesses it by key. An append-only sequential artefact expresses that usage exactly,
 * so the correct keying is no file key at all, with the sequence number retained as data.
 *
 * <p>Trade-offs: the output is consequently not byte-comparable against any reference run, because no
 * reference run exists. The program has never executed under the open-source toolchain.
 *
 * <p>Assumptions: a third inconsistency is recorded and deliberately not reconciled.
 * {@code app/jcl/CBEXPORT.jcl:30-38} defines the cluster with {@code KEYS(4 28)} -- length four at
 * offset 28 -- while the sequence number sits at zero-based offset 27, since the discriminator and
 * stamp ahead of it occupy 1 + 26 bytes. The job control is off by one. The copybook is normative, per
 * the migration plan's transformation rule T1, so the discrepancy is noted and neither artefact is
 * adjusted to match the other.
 *
 * <h2>Assumptions: there is no golden master, so fidelity rests on transcription</h2>
 *
 * <p>Every other dataset this module writes has a committed expectation file produced by running the
 * reference. This one cannot: the program does not compile, so it has never run, so nothing recorded
 * its output. <b>Fidelity here cannot be established by comparing bytes against an oracle; it can only
 * come from reading {@code app/cbl/CBEXPORT.cbl} and transcribing it.</b> Every offset, discriminator,
 * literal and counter in this class was therefore verified against the source line cited beside it
 * rather than trusted, and a reader changing any of them should re-verify the same way instead of
 * relying on a test to catch the error. This is also why divergence D-8 below removes the wall clock:
 * with no oracle, comparing two runs of the target against each other is the only verification left,
 * and a clock read destroys it.
 *
 * <h2>Assumptions: the payload views assign storage per field, not per type</h2>
 *
 * <p>The encoding belongs to {@link ExportRecordMapper} and none of it is repeated here, but the
 * geometry is stated because this class is what proves the record closes. The prefix is
 * {@code 1 + 26 + 4 + 4 + 5 + 460}, which is 500, from {@code app/cpy/CVEXPORT.cpy:10-19}. The term
 * that misleads is the third: {@code EXPORT-SEQUENCE-NUM} is declared {@code PIC 9(9) COMP} at
 * {@code app/cpy/CVEXPORT.cpy:16}, so it occupies <b>four binary bytes and not nine display
 * characters</b>. Reading it as nine puts every prefix field after it five bytes late and shifts the
 * whole payload with them.
 *
 * <p>Assumptions: within one view, identical picture clauses occupy different byte widths, because
 * {@code app/cpy/CVEXPORT.cpy} attaches {@code USAGE} per field. The account view is the extreme case
 * and carries five {@code PIC S9(10)V99} fields in three storage regimes:
 * {@code EXP-ACCT-CURR-BAL} is {@code COMP-3} in seven bytes at {@code :50},
 * {@code EXP-ACCT-CREDIT-LIMIT} is display in twelve at {@code :51},
 * {@code EXP-ACCT-CASH-CREDIT-LIMIT} is {@code COMP-3} in seven at {@code :52},
 * {@code EXP-ACCT-CURR-CYC-CREDIT} is display in twelve at {@code :56} and
 * {@code EXP-ACCT-CURR-CYC-DEBIT} is plain binary in eight at {@code :57}. The customer view does the
 * same with {@code PIC 9(09)}: {@code EXP-CUST-ID} is {@code COMP} in four at {@code :25} while
 * {@code EXP-CUST-SSN} is display in nine at {@code :36}. Assuming one regime per type produces a
 * record that still reaches its declared 500 bytes and is wrong from the first money field onward.
 *
 * <p>The trailing pad of each view is the checksum that catches exactly that error, and all five were
 * confirmed to close at 460 using each field's own declared width: customer 326 + 134 at {@code :42},
 * account 108 + 352 at {@code :60}, transaction 320 + 140 at {@code :79}, cross-reference 33 + 427 at
 * {@code :88} and card 87 + 373 at {@code :100}. The cross-reference pad is the most informative of
 * the five, because 427 is what proves {@code EXP-XREF-ACCT-ID} is eight binary bytes rather than
 * eleven display characters. A view that does not close means the usage assumption is wrong, not the
 * copybook.
 *
 * <p>Money crosses this boundary as exact fixed point throughout -- packed or zoned decimal in the
 * record, {@code BigDecimal} at scale two in the entity. No money value materialises in this class at
 * all: it is read from an entity and handed to the mapper, so no binary floating-point type appears
 * here and none may be introduced. The card verification value is likewise never named, logged or
 * copied here; it reaches the record only inside the mapper's opaque carrier, which is the sole
 * handling a payment-card secret crossing a serialisation boundary is allowed.
 *
 * <h2>OPEN DEPENDENCY: two of the five record types have no data seam in this module</h2>
 *
 * <p><b>This is an unresolved cross-package dependency, not a settled divergence, and it is stated
 * here so that it is acted on rather than inherited.</b> The reference reads all five masters --
 * {@code app/cbl/CBEXPORT.cbl:260} customer, {@code :329} account, {@code :393} cross-reference,
 * {@code :448} transaction and {@code :513} card. This class emits three of the five. The two absent
 * are customer and card, and the reason is neither the encoder nor the database:
 *
 * <ul>
 *   <li>{@link ExportRecordMapper} already encodes all five views, and exposes
 *       {@code ExportRecord.ofCustomer} and {@code ExportRecord.ofCard} as field maps precisely
 *       because nothing in this module models those two rows.</li>
 *   <li>The privilege exists. {@code data-migration/sql/V0__schemas_and_roles.sql:1093} grants the
 *       batch role usage on {@code ledger}, {@code account}, {@code card} and {@code reference};
 *       {@code :1168} grants it select on every table in {@code account}, which includes
 *       {@code account.customers}; and {@code :1206} grants it select on every table in {@code card},
 *       which includes {@code card.cards}. <b>An earlier reading that this module holds no privilege
 *       on the card schema is out of date</b>, and the resolution below is cheaper than that reading
 *       implies.</li>
 *   <li>What is missing is only the Java seam. {@code com.carddemo.batch.domain} closes its roster at
 *       eight entities and {@code com.carddemo.batch.repository} at eight interfaces, and neither
 *       {@code Customer} nor {@code Card} is among them. The domain charter reasons its closure from
 *       the preflight program, which opens six files and reads three; that reasoning is sound for
 *       preflight and does not extend to this program, which reads all five.</li>
 * </ul>
 *
 * <p>Alternatives Considered: three workarounds were available and all three are rejected. A native
 * query or a raw entity-manager call here would put data access in the job layer and native SQL in a
 * module whose repository charter admits none, and the shared architecture rules assert that boundary
 * as a test. A synchronous call to the owning service for a bulk read would move a table-sized
 * transfer onto a request path built for single-row work. Emitting three types and saying nothing
 * would be the worst of the three: the five discriminators share one sequence counter and the import
 * dispatcher accepts a three-type file as structurally complete, so the loss would be silent in an
 * artefact whose entire purpose is branch migration. This class therefore emits what it can, reports
 * the shortfall in its own summary counters, and warns on every run.
 *
 * <p>The preferred minimal resolution, for whoever owns those two packages: add {@code Customer} and
 * {@code Card} entities to {@code com.carddemo.batch.domain}; add read-only
 * {@code CustomerRepository} and {@code CardRepository} to {@code com.carddemo.batch.repository}
 * shaped like {@code DisclosureGroupRepository}, which extends the narrow {@code Repository} base and
 * so contributes no write surface, since export needs none; then extend the two phases held open
 * below. No grant work is required. Precedent exists in the migration plan, whose section 0.4.1.3
 * gives {@code reporting-service} read-only cross-schema access under a select-only role. The
 * shortfall is registered in {@code docs/architecture/cobol-to-service-traceability.md}, which the
 * plan designates the register of every documented divergence.
 *
 * @see ExportRecordMapper
 * @see BatchJobName#EXPORT
 */
@Configuration(proxyBeanMethods = false)
public class ExportJob {

    /** Diagnostic channel for this job; the reference writes the same lines to its system output. */
    private static final Logger LOG = LoggerFactory.getLogger(ExportJob.class);

    /**
     * Branch identifier stamped on every record when no override is configured, from
     * {@code app/cbl/CBEXPORT.cbl:278} ({@code MOVE '0001' TO EXPORT-BRANCH-ID}).
     */
    static final String DEFAULT_BRANCH_ID = "0001";

    /**
     * Region code stamped on every record when no override is configured, from
     * {@code app/cbl/CBEXPORT.cbl:279} ({@code MOVE 'NORTH' TO EXPORT-REGION-CODE}).
     */
    static final String DEFAULT_REGION_CODE = "NORTH";

    /**
     * Key prefix the export dataset is written under, ahead of the business-date partition.
     *
     * <p>Assumptions: {@code AWS.M2.CARDDEMO.EXPORT.DATA} is <b>not</b> a generation family, so this
     * artefact deliberately sits outside the generation model. It appears in none of the ten
     * generation bases the baseline defines across {@code app/jcl/DEFGDGB.jcl},
     * {@code app/jcl/DEFGDGD.jcl} and {@code app/jcl/DALYREJS.jcl}; its driver instead deletes and
     * redefines the cluster on every run, at {@code app/jcl/CBEXPORT.jcl:27-38}, and mounts it
     * {@code DISP=SHR} rather than as a relative generation at {@code app/jcl/CBEXPORT.jcl:62-63}.
     * That is replace-in-place, not generational, so it gets no {@code (+1)} allocation and no
     * five-noncurrent-version retention.</p>
     *
     * <p>Alternatives Considered: routing this through {@code DatasetGeneration} and the shared
     * {@code DatasetPayloadWriter}, as the backup and combine jobs do. Rejected because
     * {@code DatasetGeneration} is closed over exactly those ten families and the shared writer
     * requires one, so using it would mean inventing an eleventh family and, with it, retention
     * behaviour the baseline does not have. A run-keyed prefix under the same bucket keeps the
     * artefact addressable without claiming a generational contract it never had.</p>
     */
    private static final String EXPORT_KEY_PREFIX = "export/";

    /** Member name the export dataset is written as, beneath its business-date partition. */
    private static final String EXPORT_MEMBER = "/export.dat";

    /**
     * Content type recorded on the written object.
     *
     * <p>Assumptions: binary, because the record carries packed decimal and a four-byte binary
     * sequence number. Declaring it as text would invite a transcoding hop, and the reference suite
     * records that routing binary content through a text codec mangles it into replacement
     * characters.</p>
     */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /**
     * Start banner, verbatim from {@code app/cbl/CBEXPORT.cbl:163}.
     *
     * <p>Assumptions: every operator-visible line in this class is carried across
     * character-for-character under the migration plan's transformation rule T8, including this one's
     * slightly misleading wording -- the run exports five record types, not only customers. It is
     * reproduced as written rather than corrected, because a message the plan requires verbatim is not
     * the place to improve on the baseline.</p>
     */
    private static final String LOG_STARTING = "CBEXPORT: Starting Customer Data Export";

    /** Per-phase banner, verbatim from {@code app/cbl/CBEXPORT.cbl:245}. */
    private static final String LOG_PROCESSING_CUSTOMERS = "CBEXPORT: Processing customer records";

    /** Per-phase banner, verbatim from {@code app/cbl/CBEXPORT.cbl:314}. */
    private static final String LOG_PROCESSING_ACCOUNTS = "CBEXPORT: Processing account records";

    /** Per-phase banner, verbatim from {@code app/cbl/CBEXPORT.cbl:378}. */
    private static final String LOG_PROCESSING_XREFS =
            "CBEXPORT: Processing cross-reference records";

    /** Per-phase banner, verbatim from {@code app/cbl/CBEXPORT.cbl:433}. */
    private static final String LOG_PROCESSING_TRANSACTIONS =
            "CBEXPORT: Processing transaction records";

    /** Per-phase banner, verbatim from {@code app/cbl/CBEXPORT.cbl:498}. */
    private static final String LOG_PROCESSING_CARDS = "CBEXPORT: Processing card records";

    /**
     * Per-phase customer tally, verbatim from {@code app/cbl/CBEXPORT.cbl:254}.
     *
     * <p>⚠️ Assumptions: the per-phase tallies use a <b>lower-case</b> {@code "exported: "} and the
     * closing summary uses a <b>capitalised</b> {@code "Exported: "}. The two forms are not
     * interchangeable and are not normalised into one here. Collapsing them would silently alter
     * thirteen operator-visible lines that rule T8 requires reproduced exactly, and the difference is
     * invisible to any test that compares case-insensitively -- which is why each constant cites the
     * single source line it was copied from.</p>
     */
    private static final String LOG_CUSTOMERS_PROGRESS = "CBEXPORT: Customers exported: ";

    /** Per-phase account tally, verbatim from {@code app/cbl/CBEXPORT.cbl:323}. */
    private static final String LOG_ACCOUNTS_PROGRESS = "CBEXPORT: Accounts exported: ";

    /**
     * Per-phase cross-reference tally, verbatim from {@code app/cbl/CBEXPORT.cbl:387}.
     *
     * <p>⚠️ Assumptions: this phase is the one whose two forms differ by more than case. The
     * per-phase line reads {@code "Cross-references exported: "} while the summary line reads
     * {@code "XRefs Exported: "} -- a different word, not a different capitalisation. Deriving either
     * from the other is impossible, so both are carried as separate constants.</p>
     */
    private static final String LOG_XREFS_PROGRESS = "CBEXPORT: Cross-references exported: ";

    /** Per-phase transaction tally, verbatim from {@code app/cbl/CBEXPORT.cbl:442}. */
    private static final String LOG_TRANSACTIONS_PROGRESS = "CBEXPORT: Transactions exported: ";

    /** Per-phase card tally, verbatim from {@code app/cbl/CBEXPORT.cbl:507}. */
    private static final String LOG_CARDS_PROGRESS = "CBEXPORT: Cards exported: ";

    /** Closing banner, verbatim from {@code app/cbl/CBEXPORT.cbl:563}. */
    private static final String LOG_COMPLETED = "CBEXPORT: Export completed";

    /** Summary customer tally, verbatim from {@code app/cbl/CBEXPORT.cbl:564}. */
    private static final String LOG_CUSTOMERS_SUMMARY = "CBEXPORT: Customers Exported: ";

    /** Summary account tally, verbatim from {@code app/cbl/CBEXPORT.cbl:566}. */
    private static final String LOG_ACCOUNTS_SUMMARY = "CBEXPORT: Accounts Exported: ";

    /**
     * Summary cross-reference tally, verbatim from {@code app/cbl/CBEXPORT.cbl:568}.
     *
     * <p>Assumptions: the spelling is {@code XRefs} -- capital X, capital R, lower-case s. Neither
     * {@code XREFs} nor {@code Xrefs} appears anywhere in the reference.</p>
     */
    private static final String LOG_XREFS_SUMMARY = "CBEXPORT: XRefs Exported: ";

    /** Summary transaction tally, verbatim from {@code app/cbl/CBEXPORT.cbl:569}. */
    private static final String LOG_TRANSACTIONS_SUMMARY = "CBEXPORT: Transactions Exported: ";

    /** Summary card tally, verbatim from {@code app/cbl/CBEXPORT.cbl:571}. */
    private static final String LOG_CARDS_SUMMARY = "CBEXPORT: Cards Exported: ";

    /** Summary grand tally, verbatim from {@code app/cbl/CBEXPORT.cbl:572}. */
    private static final String LOG_TOTAL_SUMMARY = "CBEXPORT: Total Records Exported: ";

    /** Stamp date echo, verbatim from {@code app/cbl/CBEXPORT.cbl:168}. */
    private static final String LOG_EXPORT_DATE = "CBEXPORT: Export Date: ";

    /** Stamp time echo, verbatim from {@code app/cbl/CBEXPORT.cbl:169}. */
    private static final String LOG_EXPORT_TIME = "CBEXPORT: Export Time: ";

    /**
     * Abend banner, verbatim from {@code app/cbl/CBEXPORT.cbl:578}.
     *
     * <p>Refactoring Rationale: the reference reaches this line from every failure path -- a failed
     * open at {@code app/cbl/CBEXPORT.cbl:200-240}, a failed read, or a failed write -- and follows it
     * at {@code app/cbl/CBEXPORT.cbl:579} with {@code CALL 'CEE3ABD'}, the language-environment abend
     * primitive. The banner is carried because rule T8 makes it operator-visible text like any other,
     * and an operator who knows the baseline should recognise a failed export from its log.</p>
     *
     * <p>Alternatives Considered: reproducing the abend call itself, for instance by halting the
     * process. Rejected: the abend primitive exists because COBOL has no exception to propagate, whereas
     * the migration plan's transformation rule T5 maps a rollback to exception propagation, and the
     * step's own catch already translates a thrown exception into the hard-failure tier and routes the
     * orchestration state to its failure branch. Halting would bypass that translation and lose the
     * ledger row. The banner is therefore logged and the exception rethrown unchanged.</p>
     */
    private static final String LOG_ABENDING = "CBEXPORT: ABENDING PROGRAM";

    /**
     * Assembles the export job and registers it under the token the container resolves by name.
     *
     * <p>Assumptions: the registered name is taken from {@link BatchJobName#EXPORT} rather than
     * written as a literal, because the container resolves a job through the Spring Batch registry by
     * name and never by importing this class. A literal that drifted from the enum would fail only at
     * run time, with a token the registry does not hold.</p>
     *
     * <p>Assumptions: the step is built through the shared {@link LedgerGuardedStep} rather than a
     * bare step builder, which is what supplies the durable {@code (runId, stepName)} idempotency key.
     * A redriven execution whose step already completed is skipped by the ledger and its recorded
     * outcome replayed, so no part of that mechanism is reimplemented here.</p>
     *
     * @param jobRepository the batch job repository the job and its step are registered against; must
     *     not be {@code null}
     * @param transactionManager the transaction manager whose boundary the streamed reads run inside;
     *     must not be {@code null}
     * @param steps the shared ledger-guarded step builder supplying idempotency and exit-status
     *     translation; must not be {@code null}
     * @param accounts the account master, emitted as record type {@code 'A'}; must not be {@code null}
     * @param crossReferences the card cross-reference table, emitted as record type {@code 'X'}; must
     *     not be {@code null}
     * @param transactions the transaction master, emitted as record type {@code 'T'}; must not be
     *     {@code null}
     * @param objectStore the object store the dataset is written to; must not be {@code null}
     * @param bucket the dataset bucket name; must not be {@code null} or blank
     * @param branchId the branch identifier to stamp, defaulting to the baseline literal
     *     {@code 0001}; must not be {@code null}
     * @param regionCode the region code to stamp, defaulting to the baseline literal {@code NORTH};
     *     must not be {@code null}
     * @param exportTimestamp the 26-character stamp to apply to every record, or blank to fall back to
     *     the clock; must not be {@code null}
     * @param clock the fallback time source, consulted only when {@code exportTimestamp} is blank;
     *     must not be {@code null}
     * @return the job, registered under exactly the token {@code export}; never {@code null}
     */
    @Bean
    public Job exportDataset(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            LedgerGuardedStep steps,
            AccountRepository accounts,
            CardXrefRepository crossReferences,
            TransactionRepository transactions,
            S3Client objectStore,
            @Value("${carddemo.dataset.bucket}") String bucket,
            @Value("${carddemo.export.branch-id:" + DEFAULT_BRANCH_ID + "}") String branchId,
            @Value("${carddemo.export.region-code:" + DEFAULT_REGION_CODE + "}") String regionCode,
            @Value("${carddemo.export.timestamp:}") String exportTimestamp,
            Clock clock) {

        String name = BatchJobName.EXPORT.token();

        // WHAT: the stamp is resolved once, before the step body is built, and captured by it.
        // WHY : Assumptions: resolving it here rather than per record is what makes one dataset carry
        //       one stamp, which is the reference's behaviour -- app/cbl/CBEXPORT.cbl:165 performs
        //       1050-GENERATE-TIMESTAMP once from 1000-INITIALIZE, and all five blocks then move the
        //       same WS-FORMATTED-TIMESTAMP into their prefix. Resolving per record would stamp rows
        //       from one run with different instants.
        ExportStamp stamp = ExportStamp.resolve(exportTimestamp, branchId, regionCode, clock);

        Step step = steps.build(name, jobRepository, transactionManager,
                businessDate -> writeExport(businessDate, stamp, accounts, crossReferences,
                        transactions, objectStore, bucket));

        return new JobBuilder(name, jobRepository).start(step).build();
    }

    /**
     * Streams the owned masters, encodes one record per row and writes the dataset.
     *
     * <p>Refactoring Rationale: one monotonic sequence counter spans all five record types, so the
     * phase order is part of the output contract. {@code WS-SEQUENCE-COUNTER} is declared once, at
     * {@code app/cbl/CBEXPORT.cbl:123}, and every block increments that same counter -- so sequence
     * numbers are globally ordered across the whole file rather than restarting per type. Two
     * consequences follow and both are load-bearing. The order established by
     * {@code app/cbl/CBEXPORT.cbl:151-157} -- customer, account, cross-reference, transaction, card --
     * <b>must not be reordered</b>, because reordering emits the same set of records under different
     * sequence numbers. And the phases <b>must not run concurrently</b>, because a counter shared
     * across parallel phases yields an interleaving that is not reproducible even with a fixed stamp.
     * The single local variable below is what makes both properties structural: there is one counter to
     * share, and the sequential loops share it by construction rather than by convention.</p>
     *
     * <p>Alternatives Considered: deriving the grand total by summing the five per-type counters at the
     * end. Rejected because it is not what the reference does, and the difference is useful. Every
     * block performs two increments -- one on its own counter and one on the total -- at
     * {@code app/cbl/CBEXPORT.cbl:309-310}, {@code :372-373}, {@code :427-428}, {@code :492-493} and
     * {@code :550-551}. Accumulating the total independently preserves that, and it makes
     * {@link #reconcile} a real check: because the two sides are computed by separate statements, a
     * disagreement is a detectable defect. A derived total cannot disagree with itself, so it would
     * report consistency it never verified.</p>
     *
     * <p>Alternatives Considered: collapsing the three populated phases into one generic helper
     * parameterised by record type, source supplier and encoder. Rejected, even though the loops are
     * near-identical, because the phase order is part of the output contract and a generic helper hides
     * it: the order would then live in a call list, and the shared counter would have to become a
     * mutable holder passed between calls. Written out, the five phases appear in the file in the order
     * the reference performs them, each numbered, so a reviewer can audit the contract by reading top to
     * bottom. Trade-offs: the cost is roughly four repeated lines per phase, accepted because what is
     * repeated is the loop mechanics and what would be hidden is the contract. The part genuinely worth
     * factoring -- the six-statement prefix preamble -- is factored, in {@link #prefix}.</p>
     *
     * <p>Assumptions: each phase walks its own table exactly once, in that table's key order, and the
     * ordering is part of the contract rather than incidental. The reference opens each input
     * {@code ACCESS MODE IS SEQUENTIAL} over an indexed organisation, so rows arrive in record-key
     * order; a relational read guarantees no order unless one is asked for, so each repository method
     * used here names its ordering. The cross-reference pass is one record per <b>card</b> and not per
     * account, because {@code app/cbl/CBEXPORT.cbl:376-389} reads that file to end of file and an
     * account legitimately holds several cards.</p>
     *
     * <p>Assumptions: this job writes nothing to the database, so it takes no business transaction.
     * The reference issues no write verb against any of its five inputs -- they are all
     * {@code OPEN INPUT}, at {@code app/cbl/CBEXPORT.cbl:200-233} -- and its only output is the
     * sequential file. The step still runs inside the framework's transaction boundary, which is what
     * lets the cursors stream and what commits the ledger row recording the step; no second boundary is
     * declared here, and a reader looking for an absent {@code @Transactional} is looking for something
     * that would have nothing to protect.</p>
     *
     * <p>Trade-offs: the encoded records accumulate in memory before the single object write, rather
     * than streaming to the store. The database reads do stream -- every source is walked one row at a
     * time through a cursor and no table is materialised as a list -- but the object-store put needs a
     * complete payload, and the counterpart import reads the dataset whole for the same reason. The
     * accepted cost is that peak memory scales with the dataset rather than with one record; the
     * alternative, a multipart upload, would add a second failure mode and a partially written artefact
     * to a job whose output is meant to be all-or-nothing.</p>
     *
     * @param businessDate the injected business date the dataset is partitioned under; must not be
     *     {@code null}
     * @param stamp the resolved prefix values applied to every record of this run; must not be
     *     {@code null}
     * @param accounts the account master, emitted as record type {@code 'A'}; must not be {@code null}
     * @param crossReferences the cross-reference table, walked in full and emitted as record type
     *     {@code 'X'}; must not be {@code null}
     * @param transactions the transaction master, emitted as record type {@code 'T'}; must not be
     *     {@code null}
     * @param objectStore the object store the dataset is written to; must not be {@code null}
     * @param bucket the dataset bucket name; must not be {@code null} or blank
     * @return always {@link BatchReturnCode#CLEAN} on completion
     * @throws IllegalStateException if the independently accumulated grand total disagrees with the sum
     *     of the per-type counters
     */
    static BatchReturnCode writeExport(
            BusinessDate businessDate,
            ExportStamp stamp,
            AccountRepository accounts,
            CardXrefRepository crossReferences,
            TransactionRepository transactions,
            S3Client objectStore,
            String bucket) {

        // WHY : Refactoring Rationale: the whole body is bracketed so that the reference's abend banner
        //       still appears on every failure path. app/cbl/CBEXPORT.cbl reaches
        //       9999-ABEND-PROGRAM from a failed open, read or write alike, so a single bracket here is
        //       the faithful shape rather than a banner repeated at each call site. The exception is
        //       rethrown unchanged, which is what leaves the tier translation and the ledger row to the
        //       step that owns them -- see LOG_ABENDING for why the abend primitive itself is not
        //       reproduced.
        try {
            return export(businessDate, stamp, accounts, crossReferences, transactions, objectStore,
                    bucket);
        } catch (RuntimeException failure) {
            LOG.error(LOG_ABENDING, failure);
            throw failure;
        }
    }

    /**
     * Performs the five export phases, having been bracketed by the abend reporting of its caller.
     *
     * @param businessDate the injected business date the dataset is partitioned under; must not be
     *     {@code null}
     * @param stamp the resolved prefix values applied to every record of this run; must not be
     *     {@code null}
     * @param accounts the account master, emitted as record type {@code 'A'}; must not be {@code null}
     * @param crossReferences the cross-reference table, emitted as record type {@code 'X'}; must not be
     *     {@code null}
     * @param transactions the transaction master, emitted as record type {@code 'T'}; must not be
     *     {@code null}
     * @param objectStore the object store the dataset is written to; must not be {@code null}
     * @param bucket the dataset bucket name; must not be {@code null} or blank
     * @return always {@link BatchReturnCode#CLEAN} on completion
     * @throws IllegalStateException if the independently accumulated grand total disagrees with the sum
     *     of the per-type counters
     */
    private static BatchReturnCode export(
            BusinessDate businessDate,
            ExportStamp stamp,
            AccountRepository accounts,
            CardXrefRepository crossReferences,
            TransactionRepository transactions,
            S3Client objectStore,
            String bucket) {

        LOG.info(LOG_STARTING);
        LOG.info("{}{}", LOG_EXPORT_DATE, stamp.exportDate());
        LOG.info("{}{}", LOG_EXPORT_TIME, stamp.exportTime());

        ByteArrayOutputStream payload = new ByteArrayOutputStream();

        // WHAT: one counter for the sequence number, five for the per-type tallies, one for the total.
        // WHY : Assumptions: these are the six counters app/cbl/CBEXPORT.cbl:139-144 declares plus the
        //       sequence counter at :123, kept as seven distinct values rather than folded together.
        //       The sequence number and the total happen to agree in this implementation because every
        //       counted record is also a written record, and they are still kept apart: the sequence
        //       number is data inside the record and the total is a statistic about the run, and a
        //       future phase that counted a row without emitting it would separate them.
        long sequence = 0L;
        long customerRecords = 0L;
        long accountRecords = 0L;
        long crossReferenceRecords = 0L;
        long transactionRecords = 0L;
        long cardRecords = 0L;
        long totalRecords = 0L;

        // ---- Phase 1 of 5: customers, record type 'C' -------------------------------------------
        // WHAT: the phase is announced and its tally reported even though it emits nothing.
        // WHY : Assumptions: the open dependency on the class comment is the reason the tally is zero,
        //       and reporting zero here without saying so would be indistinguishable from an empty
        //       customer table. The warning below states which of the two it is; the verbatim lines are
        //       kept so the operator-visible sequence still matches the reference phase for phase.
        LOG.info(LOG_PROCESSING_CUSTOMERS);
        LOG.info("{}{}", LOG_CUSTOMERS_PROGRESS, customerRecords);

        // ---- Phase 2 of 5: accounts, record type 'A' --------------------------------------------
        LOG.info(LOG_PROCESSING_ACCOUNTS);
        try (Stream<Account> rows = accounts.findAllByOrderByAccountIdAsc()) {
            for (Account row : (Iterable<Account>) rows::iterator) {
                sequence++;
                DatasetPayloadWriter.append(payload, ExportRecordMapper.toRecord(
                        ExportRecord.ofAccount(
                                prefix(RecordType.ACCOUNT, stamp, sequence), row)));
                accountRecords++;
                totalRecords++;
            }
        }
        LOG.info("{}{}", LOG_ACCOUNTS_PROGRESS, accountRecords);

        // ---- Phase 3 of 5: card cross-references, record type 'X' -------------------------------
        LOG.info(LOG_PROCESSING_XREFS);
        try (Stream<CardXref> rows = crossReferences.findAllByOrderByCardNumAsc()) {
            for (CardXref row : (Iterable<CardXref>) rows::iterator) {
                sequence++;
                DatasetPayloadWriter.append(payload, ExportRecordMapper.toRecord(
                        ExportRecord.ofCardXref(
                                prefix(RecordType.CARD_XREF, stamp, sequence), row)));
                crossReferenceRecords++;
                totalRecords++;
            }
        }
        LOG.info("{}{}", LOG_XREFS_PROGRESS, crossReferenceRecords);

        // ---- Phase 4 of 5: transactions, record type 'T' ----------------------------------------
        LOG.info(LOG_PROCESSING_TRANSACTIONS);
        try (Stream<Transaction> rows = transactions.findAllByOrderByTransactionIdAsc()) {
            for (Transaction row : (Iterable<Transaction>) rows::iterator) {
                sequence++;
                DatasetPayloadWriter.append(payload, ExportRecordMapper.toRecord(
                        ExportRecord.ofTransaction(
                                prefix(RecordType.TRANSACTION, stamp, sequence), row)));
                transactionRecords++;
                totalRecords++;
            }
        }
        LOG.info("{}{}", LOG_TRANSACTIONS_PROGRESS, transactionRecords);

        // ---- Phase 5 of 5: cards, record type 'D' -----------------------------------------------
        // WHAT: held open by the same dependency as phase 1, and announced for the same reason.
        // WHY : ⚠️ Assumptions: the card discriminator is 'D' and NOT 'C'. 'C' is already taken by the
        //       customer view at app/cbl/CBEXPORT.cbl:274, and the card block sets 'D' at
        //       app/cbl/CBEXPORT.cbl:527. This is the single easiest error to make in this file and the
        //       most expensive, because the import dispatcher at app/cbl/CBIMPORT.cbl:272-285 routes
        //       'D' to its card branch and sends anything it does not recognise to its unknown-type
        //       handler -- so a wrong letter yields a file that imports as unknown records rather than
        //       one that fails loudly. The letter is not written here at all: it is taken from
        //       RecordType.CARD, which holds it once for both jobs.
        LOG.info(LOG_PROCESSING_CARDS);
        LOG.info("{}{}", LOG_CARDS_PROGRESS, cardRecords);

        warnOnUnexportedTypes();

        reconcile(totalRecords, customerRecords, accountRecords, crossReferenceRecords,
                transactionRecords, cardRecords);

        String key = EXPORT_KEY_PREFIX + businessDate.identifierPrefix() + EXPORT_MEMBER;
        objectStore.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(CONTENT_TYPE)
                        .build(),
                RequestBody.fromBytes(payload.toByteArray()));

        LOG.info(LOG_COMPLETED);
        LOG.info("{}{}", LOG_CUSTOMERS_SUMMARY, customerRecords);
        LOG.info("{}{}", LOG_ACCOUNTS_SUMMARY, accountRecords);
        LOG.info("{}{}", LOG_XREFS_SUMMARY, crossReferenceRecords);
        LOG.info("{}{}", LOG_TRANSACTIONS_SUMMARY, transactionRecords);
        LOG.info("{}{}", LOG_CARDS_SUMMARY, cardRecords);
        LOG.info("{}{}", LOG_TOTAL_SUMMARY, totalRecords);

        LOG.info("event=batch.export.completed key={} bytes={} customers={} accounts={}"
                        + " crossReferences={} transactions={} cards={} records={}",
                key, payload.size(), customerRecords, accountRecords, crossReferenceRecords,
                transactionRecords, cardRecords, totalRecords);

        // WHY : Assumptions: the clean tier is the only success tier this job can report, and the
        //       soft-warn tier is unreachable from here BY CONSTRUCTION rather than by omission.
        //       RETURN-CODE appears nowhere in all 582 lines of app/cbl/CBEXPORT.cbl -- the program
        //       either ends normally or abends through CALL 'CEE3ABD' at app/cbl/CBEXPORT.cbl:578-579 --
        //       so its only outcomes are zero and a hard failure. The warn tier originates solely at
        //       app/cbl/CBTRN02C.cbl:229-230, and BatchRunSummary refuses to carry it for any job but
        //       posting, so a warn path added here speculatively would be rejected downstream as well.
        // WHY : Alternatives Considered: building a BatchRunSummary here to carry the six counters as
        //       its per-type breakdown. Rejected on cost against benefit. The summary requires a run
        //       identifier and a step name, neither of which the StepBody contract passes to a step
        //       body, so both would have to be threaded through this method for a value nothing
        //       persists -- BatchStepLedger records the return code alone. The counter check that type
        //       would have contributed is performed directly by reconcile() above, with an error
        //       message naming the reference's paired counters, and the tier rule it enforces is relied
        //       on rather than duplicated: the note above cites it as the downstream guarantee.
        return BatchReturnCode.CLEAN;
    }

    /**
     * Streams the owned masters using the reference's own parameterless stamping.
     *
     * <p>Assumptions: this is the shape of a reference invocation, and it exists because that shape is
     * worth naming. {@code app/jcl/CBEXPORT.jcl:43} passes no {@code PARM}, so the baseline run always
     * takes its stamp from the clock and always attributes the dataset to branch
     * {@value #DEFAULT_BRANCH_ID} in region {@value #DEFAULT_REGION_CODE}. A caller wanting exactly
     * that reaches this overload; a caller wanting a reproducible or differently attributed artefact
     * reaches the one above with an explicit {@link ExportStamp}.</p>
     *
     * @param businessDate the injected business date the dataset is partitioned under; must not be
     *     {@code null}
     * @param accounts the account master, emitted as record type {@code 'A'}; must not be {@code null}
     * @param crossReferences the cross-reference table, emitted as record type {@code 'X'}; must not be
     *     {@code null}
     * @param transactions the transaction master, emitted as record type {@code 'T'}; must not be
     *     {@code null}
     * @param objectStore the object store the dataset is written to; must not be {@code null}
     * @param bucket the dataset bucket name; must not be {@code null} or blank
     * @param clock the time source the stamp is taken from; must not be {@code null}
     * @return always {@link BatchReturnCode#CLEAN} on completion
     * @throws IllegalStateException if the independently accumulated grand total disagrees with the sum
     *     of the per-type counters
     */
    static BatchReturnCode writeExport(
            BusinessDate businessDate,
            AccountRepository accounts,
            CardXrefRepository crossReferences,
            TransactionRepository transactions,
            S3Client objectStore,
            String bucket,
            Clock clock) {

        return writeExport(businessDate, ExportStamp.baseline(clock), accounts, crossReferences,
                transactions, objectStore, bucket);
    }

    /**
     * Builds the 40-byte common prefix shared by all five record types.
     *
     * <p>Refactoring Rationale: the reference repeats the same six statements at the head of all five
     * of its blocks -- {@code app/cbl/CBEXPORT.cbl:274-279}, {@code :343-348}, {@code :407-412},
     * {@code :462-467} and {@code :527-532} each move the type literal, move the stamp, increment the
     * sequence counter, move it into the record, move {@code '0001'} and move {@code 'NORTH'}. Five
     * copies of one rule drift the moment one is edited, and a drifted prefix is invisible in the
     * bytes, so the rule is expressed once here. Behaviour is unchanged: the same five values land in
     * the same five fields in the same order, which is what the migration plan's transformation rule T9
     * asks for.</p>
     *
     * @param recordType the view discriminator for the record being built; must not be {@code null}
     * @param stamp the run-constant stamp, branch identifier and region code; must not be {@code null}
     * @param sequence the record's one-based position in write order across all five types; must be
     *     positive
     * @return the populated prefix; never {@code null}
     */
    private static Prefix prefix(RecordType recordType, ExportStamp stamp, long sequence) {
        return new Prefix(recordType, stamp.timestamp(), sequence, stamp.branchId(),
                stamp.regionCode());
    }

    /**
     * Verifies the independently accumulated grand total against the sum of the per-type counters.
     *
     * <p>Alternatives Considered: logging a mismatch and continuing. Rejected because the two sides are
     * accumulated by separate statements precisely so that they can be compared, and a disagreement
     * means one increment was missed -- which means the dataset's record count and its statistics
     * describe different runs. For an artefact with no golden master, published statistics that do not
     * describe the bytes are worse than a failure, because a consumer has no way to detect the
     * discrepancy. Failing before the object is written keeps a provably inconsistent dataset out of
     * the store entirely.</p>
     *
     * @param total the grand total accumulated alongside each per-type increment
     * @param customers the customer records emitted, record type {@code 'C'}
     * @param accounts the account records emitted, record type {@code 'A'}
     * @param crossReferences the cross-reference records emitted, record type {@code 'X'}
     * @param transactions the transaction records emitted, record type {@code 'T'}
     * @param cards the card records emitted, record type {@code 'D'}
     * @throws IllegalStateException if {@code total} differs from the sum of the five per-type counters
     */
    private static void reconcile(long total, long customers, long accounts, long crossReferences,
            long transactions, long cards) {

        long summed = customers + accounts + crossReferences + transactions + cards;
        if (total != summed) {
            throw new IllegalStateException("export statistics are inconsistent: the grand total "
                    + total + " does not equal the sum of the per-type counters " + summed
                    + " (customers=" + customers + ", accounts=" + accounts + ", crossReferences="
                    + crossReferences + ", transactions=" + transactions + ", cards=" + cards
                    + "); one increment of app/cbl/CBEXPORT.cbl's paired counters was missed");
        }
    }

    /**
     * Reports, on every run, the two record types this module cannot yet read.
     *
     * <p>Alternatives Considered: omitting this and relying on the class comment. Rejected because the
     * shortfall has to be visible to an operator holding the dataset, not only to a reader holding the
     * source. The import dispatcher accepts a three-type file as structurally complete, so nothing
     * downstream can raise the question; this line is the only place a run says which types it did not
     * contain and why. It is deliberately emitted even when the export is otherwise entirely
     * successful, and it will be deleted -- not downgraded -- when the two seams named in the class
     * comment exist.</p>
     *
     * <p>Trade-offs: this reports rather than throws. Refusing to export at all until the two seams
     * exist was considered and rejected: three of the five types are complete and correct, and a
     * consumer who needs those three is better served by a dataset that names its own gap than by no
     * dataset at all. The cost is that a caller who ignores this line gets a partial artefact, which is
     * why the shortfall is also carried in the summary counters.</p>
     */
    private static void warnOnUnexportedTypes() {
        LOG.warn("event=batch.export.record-types-unavailable missing={},{} reason=no entity or"
                        + " repository exists in com.carddemo.batch for account.customers or"
                        + " card.cards; the SELECT grants already exist"
                        + " (data-migration/sql/V0__schemas_and_roles.sql:1168,1206) so only the"
                        + " Java seam is outstanding. Registered in"
                        + " docs/architecture/cobol-to-service-traceability.md",
                RecordType.CUSTOMER.discriminator(), RecordType.CARD.discriminator());
    }

    /**
     * The three prefix values that are constant across every record of one export run.
     *
     * <p>Purpose: to carry the stamp, branch identifier and region code as one validated value, and to
     * be the <b>single place in this class where a clock may be read</b>. Once constructed, the write
     * path below is a pure function of its inputs, which is what lets two runs given the same stamp be
     * compared byte for byte.</p>
     *
     * <p>Refactoring Rationale: divergence D-8, the wall clock. The reference builds its stamp from
     * the system clock -- {@code app/cbl/CBEXPORT.cbl:175-176} are
     * {@code ACCEPT WS-CURRENT-DATE FROM DATE YYYYMMDD} and {@code ACCEPT WS-CURRENT-TIME FROM TIME},
     * composed into {@code WS-FORMATTED-TIMESTAMP} at {@code app/cbl/CBEXPORT.cbl:191} -- and its
     * driver passes no {@code PARM} at {@code app/jcl/CBEXPORT.jcl:43}, so a reference run is not
     * reproducible even in principle. The target accepts the stamp as configuration and consults the
     * clock only when none is supplied. What was wrong with the clock read: with no golden master for
     * this stream, comparing two target runs against each other is the only verification available,
     * and a clock read makes that comparison impossible by changing 26 bytes of every record. The
     * business date is already parameter-driven for the same reason across this package; this extends
     * the same discipline to the one remaining non-deterministic input. D-8 is registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * <p>Trade-offs: the fallback is retained rather than made an error, so an operator who supplies
     * nothing still gets a working export with the reference's own semantics. The cost is that such a
     * run is not reproducible, which is why {@link #resolve} warns about it instead of passing over it
     * silently.</p>
     *
     * @param timestamp the 26-character stamp written into every record's prefix; never {@code null}
     * @param branchId the branch identifier written into every record's prefix; never {@code null}
     * @param regionCode the region code written into every record's prefix; never {@code null}
     */
    record ExportStamp(String timestamp, String branchId, String regionCode) {

        /** Width of the date portion, from {@code app/cpy/CVEXPORT.cpy:13}. */
        private static final int DATE_LENGTH = 10;

        /**
         * Offset of the time portion within the stamp, from {@code app/cpy/CVEXPORT.cpy:14-15}.
         *
         * <p>Assumptions: eleven, not ten, because the separator at {@code app/cpy/CVEXPORT.cpy:14} is
         * a field of its own rather than part of either neighbour.</p>
         */
        private static final int TIME_OFFSET = 11;

        /** Width the reference echoes, from {@code WS-EXPORT-TIME} at {@code app/cbl/CBEXPORT.cbl:121}. */
        private static final int TIME_LENGTH = 8;

        /**
         * Validates the three components, rejecting a stamp the record layout cannot hold.
         *
         * <p>Assumptions: the width check belongs here as well as in {@link Prefix}, even though the
         * mapper enforces it too. Failing at resolution names the misconfigured property while nothing
         * has been written; failing later names a record, after the first phase has already streamed.
         * The two checks agree because both derive the width from
         * {@link TimestampFormatter#TIMESTAMP_LENGTH} rather than writing 26 down.</p>
         *
         * @param timestamp the 26 characters of {@code EXPORT-TIMESTAMP}, which must be exactly that
         *     width because the record layout reserves exactly that many bytes for it
         * @param branchId the branch identifier written into {@code EXPORT-BRANCH-ID}, which may not be
         *     {@code null}
         * @param regionCode the region code written into {@code EXPORT-REGION-CODE}, which may not be
         *     {@code null}
         * @throws IllegalArgumentException if any component is {@code null}, or if {@code timestamp}
         *     is not exactly {@link TimestampFormatter#TIMESTAMP_LENGTH} characters
         */
        ExportStamp {
            if (timestamp == null || branchId == null || regionCode == null) {
                throw new IllegalArgumentException(
                        "an export stamp requires a timestamp, a branch identifier and a region"
                                + " code, and none of the three may be absent");
            }
            if (timestamp.length() != TimestampFormatter.TIMESTAMP_LENGTH) {
                throw new IllegalArgumentException(
                        "carddemo.export.timestamp must be exactly "
                                + TimestampFormatter.TIMESTAMP_LENGTH
                                + " characters because EXPORT-TIMESTAMP is declared PIC X(26) at"
                                + " app/cpy/CVEXPORT.cpy:11, but " + timestamp.length()
                                + " were supplied");
            }
        }

        /**
         * Returns the date portion of the stamp, as the reference echoes it.
         *
         * <p>Assumptions: ten characters from the front, which is
         * {@code EXPORT-DATE PIC X(10)} in the overlay at {@code app/cpy/CVEXPORT.cpy:13} and equally
         * {@code WS-EXPORT-DATE PIC X(10)} at {@code app/cbl/CBEXPORT.cbl:120}. The two agree because
         * {@code app/cbl/CBEXPORT.cbl:191} composes the stamp from that very field.</p>
         *
         * @return the ten-character date portion; never {@code null}
         */
        String exportDate() {
            return timestamp.substring(0, DATE_LENGTH);
        }

        /**
         * Returns the time portion of the stamp, as the reference echoes it.
         *
         * <p>⚠️ Assumptions: <b>eight characters and not fifteen.</b> The overlay's
         * {@code EXPORT-TIME} is {@code PIC X(15)} at {@code app/cpy/CVEXPORT.cpy:15}, but the field the
         * reference displays at {@code app/cbl/CBEXPORT.cbl:169} is {@code WS-EXPORT-TIME PIC X(08)},
         * declared at {@code app/cbl/CBEXPORT.cbl:121} and built as {@code HH:MM:SS} at
         * {@code app/cbl/CBEXPORT.cbl:185}. Echoing fifteen characters here would append the fractional
         * seconds the reference's own echo omits, so the narrower field is the right one and the
         * similarly named wider one is the trap.</p>
         *
         * @return the eight-character {@code HH:MM:SS} portion; never {@code null}
         */
        String exportTime() {
            return timestamp.substring(TIME_OFFSET, TIME_OFFSET + TIME_LENGTH);
        }

        /**
         * Resolves the configured stamping values, applying the baseline literals where absent.
         *
         * <p>Alternatives Considered: freezing the branch identifier and region code as constants, as
         * the reference does -- {@code MOVE '0001'} and {@code MOVE 'NORTH'} appear in all five of its
         * blocks. Rejected because {@code app/jcl/CBEXPORT.jcl:19-20} states the artefact's purpose is
         * branch migration or data transfer, and a branch identifier that cannot name a branch defeats
         * that purpose outright. Also rejected: changing the defaults, which would alter the output of
         * every existing consumer. Parameterising with the baseline literals as the defaults preserves
         * behaviour for a caller who configures nothing and makes the intended variability
         * reachable.</p>
         *
         * <p>Trade-offs: the values are now configurable, so a misconfigured run produces a
         * structurally valid dataset attributed to the wrong branch -- an error no consumer can detect
         * from the bytes. That is precisely why the defaults must be the baseline literals and why a
         * non-default value is reported at info level below rather than applied quietly.</p>
         *
         * @param suppliedTimestamp the configured stamp, or {@code null} or blank to derive one from
         *     {@code clock}
         * @param branchId the configured branch identifier, or {@code null} or blank for
         *     {@value ExportJob#DEFAULT_BRANCH_ID}
         * @param regionCode the configured region code, or {@code null} or blank for
         *     {@value ExportJob#DEFAULT_REGION_CODE}
         * @param clock the fallback time source, consulted only when {@code suppliedTimestamp} is
         *     absent; must not be {@code null}
         * @return the resolved stamping values; never {@code null}
         * @throws NullPointerException if {@code clock} is {@code null} and a fallback is required
         * @throws IllegalArgumentException if {@code suppliedTimestamp} is present but is not exactly
         *     {@link TimestampFormatter#TIMESTAMP_LENGTH} characters
         */
        static ExportStamp resolve(
                String suppliedTimestamp, String branchId, String regionCode, Clock clock) {

            String resolvedBranch = orDefault(branchId, DEFAULT_BRANCH_ID);
            String resolvedRegion = orDefault(regionCode, DEFAULT_REGION_CODE);

            if (!resolvedBranch.equals(DEFAULT_BRANCH_ID)
                    || !resolvedRegion.equals(DEFAULT_REGION_CODE)) {
                LOG.info("event=batch.export.attribution-overridden branchId={} regionCode={}",
                        resolvedBranch, resolvedRegion);
            }

            if (suppliedTimestamp != null && !suppliedTimestamp.isBlank()) {
                return new ExportStamp(suppliedTimestamp, resolvedBranch, resolvedRegion);
            }

            // WHAT: the only clock read in this class, and it is reported.
            // WHY : Trade-offs: a warn rather than silence, because this is the branch that gives up
            //       the one verification the stream has -- see D-8 on this record. The message names
            //       the property to set, so an operator who wants a reproducible artefact is told how
            //       without reading this file.
            LOG.warn("event=batch.export.timestamp-from-clock property=carddemo.export.timestamp"
                    + " reason=absent consequence=this run is not byte-reproducible; supply the"
                    + " property to make two runs comparable");
            return new ExportStamp(
                    TimestampFormatter.formatNow(clock), resolvedBranch, resolvedRegion);
        }

        /**
         * Resolves the stamping values with no override at all, matching a parameterless run.
         *
         * <p>Assumptions: this is the shape of a reference invocation --
         * {@code app/jcl/CBEXPORT.jcl:43} carries no {@code PARM}, so the baseline always takes the
         * clock and always stamps {@code 0001} and {@code NORTH}. It exists so that a caller wanting
         * exactly that does not have to pass three blanks and rediscover which literals the defaults
         * are.</p>
         *
         * @param clock the time source the stamp is taken from; must not be {@code null}
         * @return the resolved stamping values, carrying the two baseline literals; never {@code null}
         * @throws NullPointerException if {@code clock} is {@code null}
         */
        static ExportStamp baseline(Clock clock) {
            return resolve(null, DEFAULT_BRANCH_ID, DEFAULT_REGION_CODE, clock);
        }

        /**
         * Returns {@code candidate} when it carries a value, and {@code fallback} otherwise.
         *
         * <p>Assumptions: blank is treated as absent rather than as a value, because an unset Spring
         * property injected through a colon default arrives as an empty string. Treating it as a value
         * would stamp four spaces into the branch identifier and pass every width check.</p>
         *
         * @param candidate the configured value, possibly {@code null} or blank
         * @param fallback the baseline literal to use when {@code candidate} carries nothing; must not
         *     be {@code null}
         * @return whichever of the two carries a value; never {@code null}
         */
        private static String orDefault(String candidate, String fallback) {
            return candidate == null || candidate.isBlank() ? fallback : candidate;
        }
    }
}
