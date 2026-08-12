package com.carddemo.batch.job;

import com.carddemo.batch.config.BatchConfig.LedgerGuardedStep;
import com.carddemo.batch.dto.BatchJobName;
import com.carddemo.batch.dto.BatchReturnCode;
import com.carddemo.batch.dto.BusinessDate;
import com.carddemo.batch.mapper.ExportRecordMapper.RecordType;
import com.carddemo.batch.mapper.ExportRecordMapper;
import com.carddemo.common.codec.CopybookLayout;
import com.carddemo.common.codec.FixedWidthCodec;
import com.carddemo.common.observability.ThrowableDigest;
import com.carddemo.common.time.TimestampFormatter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Reads the 500-byte multi-record branch-migration dataset and splits it into six normalised
 * fixed-width artefacts, re-expressing {@code app/cbl/CBIMPORT.cbl}.
 *
 * <h2>Purpose</h2>
 *
 * <p>The reference program reads one input sequentially and, for each record, dispatches on a
 * one-character type discriminator into one of five mapping blocks. Each block clears a target record
 * area, moves the export view's fields into it one at a time, and writes it to that type's own
 * sequential output. A sixth output receives a fixed-width diagnostic line for any record whose
 * discriminator is none of the five. The purpose the baseline job control states for the run is
 * <em>"IMPORT CUSTOMER DATA FROM MULTI-RECORD EXPORT FILE AND SPLIT INTO SEPARATE NORMALIZED FILES FOR
 * TARGET SYSTEM"</em>, at {@code app/jcl/CBIMPORT.jcl:19-20}. That sentence is load-bearing for every
 * ruling below: the word is <em>split</em>, not <em>load</em>.
 *
 * <p>Parameters, return values, exceptions or errors. This is a bean-defining configuration class with
 * no state and no constructor of its own, so the type itself takes no parameter, yields no value and
 * raises nothing; every member below carries its own at-clauses. The inapplicability is stated rather
 * than passed over because user-specified Rule 1 (Explainability) forbids a docstring that omits
 * parameters, return values or purpose, and a reader must be able to tell a declared inapplicability
 * from an omission. The sibling {@link ExportJob} states it the same way.
 *
 * <h2>Assumptions: this job has a driver, and it is not a state of the nightly chain</h2>
 *
 * <p><b>It has a driver.</b> {@code app/jcl/CBIMPORT.jcl:22} reads {@code //STEP01 EXEC PGM=CBIMPORT},
 * and it carries <b>no {@code PARM}</b>. Among the migrated batch programs only {@code CBTRN01C} is
 * genuinely driverless: no member of {@code app/jcl/} names it. A reader who assumes otherwise because
 * this job is absent from the nightly chain has conflated <em>unscheduled</em> with <em>undriven</em>.
 *
 * <p>Assumptions: three sibling artefacts state the same conclusion and were each checked rather than
 * trusted, because an earlier reading of this package held the opposite. {@code README.md:71} lists this
 * job against that exact driver line, {@code README.md:76-79} states outright that export and import
 * each have a driver and only {@code CBTRN01C} does not, {@code job/package-info.java:105-108} repeats
 * it, and {@code BatchJobParameters}' own per-job table records the ground as
 * "{@code app/jcl/CBIMPORT.jcl:22} passes no parameter and the job creates no generation". All four
 * agree, so nothing here supersedes them.
 *
 * <p>⚠️ Assumptions: <b>one sibling statement IS stale, and it is not the one an earlier reading
 * flagged.</b> {@code job/package-info.java:8-13} still states that {@code ExportJob} and
 * {@code ImportJob} "have no file", and {@code BatchJobRosterTest:74-75} still declares both tokens
 * {@code NOT_YET_LANDED}. Both files now exist and both register a job bean, so both statements are
 * measurements that have expired. The roster test nevertheless still passes, because its
 * {@code JOB_CONFIGURATIONS} list at {@code :50-55} names only the other five classes and so never
 * inspects either file. Trade-offs: those two artefacts are left untouched from here. Correcting them
 * properly means adding roster entries for <em>both</em> jobs, which requires editing
 * {@link ExportJob} as well, and neither statement blocks a build or a test; recording the expiry
 * beside the job it concerns is the smaller and more honest change than editing a sibling's file to
 * make an inventory tidy.
 *
 * <p>Assumptions: <b>it is nonetheless unscheduled.</b> The eleven states of
 * {@code carddemo-daily-batch} are quiesce, stage, preflight, post, interest, backup, combine,
 * statements, reports, analyze and resume. Neither export nor import is among them. Both are
 * operator-invoked branch-migration utilities reached only through the container's {@code --job=}
 * argument, which is exactly what the job-control header quoted above implies: a branch migration is an
 * operator event, not a nightly one.
 *
 * <p>Assumptions: the reference is a main program and not a subprogram.
 * {@code app/cbl/CBIMPORT.cbl:162} is {@code PROCEDURE DIVISION.} with no {@code USING} phrase, so it
 * receives nothing through linkage. Combined with the absent {@code PARM}, a reference run takes no
 * input but its dataset, which is the observation divergence D-8 below acts on. Its top-level paragraph
 * at {@code app/cbl/CBIMPORT.cbl:165-171} performs four others in order and then returns, and the four
 * named steps of this class stand one-to-one against them so the traceability matrix can cite
 * paragraph-to-method pairs.
 *
 * <h2>⭐ Alternatives Considered: the output is six fixed-width artefacts, not a table load</h2>
 *
 * <p>This is the ruling that shapes the whole class, and the tempting error is to do more rather than
 * less. <b>The reference writes no VSAM master and touches no live data.</b> All six of its outputs are
 * selected {@code ORGANIZATION IS SEQUENTIAL} at {@code app/cbl/CBIMPORT.cbl:43-71}, and its own job
 * control says <em>split into separate normalized files</em>. It is a transformation, not a load.
 *
 * <p>So this job reads the export artefact and writes six fixed-width artefacts. It performs
 * <b>no insert, update or delete against any business table.</b> Its only database interaction is the
 * {@code batch.batch_run} step ledger, and even that is reached through the shared step builder rather
 * than from here. The six outputs and their widths, from the file descriptions at
 * {@code app/cbl/CBIMPORT.cbl:76-109}:
 *
 * <table>
 *   <caption>The six outputs, their source copybook, their record length and their baseline
 *     allocation</caption>
 *   <thead>
 *     <tr><th scope="col">Output</th><th scope="col">Copybook</th><th scope="col">Bytes</th>
 *         <th scope="col">Driver data definition</th></tr>
 *   </thead>
 *   <tbody>
 *     <tr><td>Customer</td><td>{@code CVCUS01Y}</td><td>500</td>
 *         <td>{@code app/jcl/CBIMPORT.jcl:33-37}, {@code LRECL=500}</td></tr>
 *     <tr><td>Account</td><td>{@code CVACT01Y}</td><td>300</td>
 *         <td>{@code app/jcl/CBIMPORT.jcl:38-42}, {@code LRECL=300}</td></tr>
 *     <tr><td>Card cross-reference</td><td>{@code CVACT03Y}</td><td>50</td>
 *         <td>{@code app/jcl/CBIMPORT.jcl:43-47}, {@code LRECL=50}</td></tr>
 *     <tr><td>Transaction</td><td>{@code CVTRA05Y}</td><td>350</td>
 *         <td>{@code app/jcl/CBIMPORT.jcl:48-52}, {@code LRECL=350}</td></tr>
 *     <tr><td>Card</td><td>{@code CVACT02Y}</td><td>150</td>
 *         <td>⚠️ absent — see divergence D-4</td></tr>
 *     <tr><td>Error</td><td><em>declared inline</em></td><td>132</td>
 *         <td>{@code app/jcl/CBIMPORT.jcl:56-60}, {@code LRECL=132}</td></tr>
 *   </tbody>
 * </table>
 *
 * <p>Alternatives Considered: <b>loading the five tables directly.</b> That is the obvious alternative
 * and it is rejected on two independent grounds. First, it would change the baseline contract from
 * <em>produce files for a target system</em> to <em>mutate the live masters</em>, a materially larger
 * blast radius than this program has ever had. Second, the migration plan already assigns the relational
 * load elsewhere: {@code data-migration/src/carddemo_migration/loaders/aurora.py} is the
 * {@code IDCAMS REPRO} load equivalent, per schema, using bulk copy, and that package carries
 * {@code readers/export_record.py} for this very copybook precisely so it can consume this artefact.
 * Implementing the load here would duplicate that seam, and would additionally require write grants on
 * {@code account.customers} plus an entirely new {@code card.*} write grant, where the plan scopes this
 * module's role to {@code ledger.*} and {@code account.*}.
 *
 * <p>Refactoring Rationale: producing artefacts that {@code loaders/aurora.py} consumes keeps this
 * job's responsibility identical to the baseline's and keeps schema mutation in the one package the
 * plan designates for it.
 *
 * <p>⚠️ Assumptions: <b>the asymmetry with {@link ExportJob} is real and a reader who expects symmetry
 * will be misled.</b> {@code ExportJob} reads the <em>live masters</em> — {@code app/jcl/CBEXPORT.jcl}
 * names the actual VSAM master datasets as its inputs — so it genuinely needs entity and repository
 * seams, and it carries a declared open dependency for the two masters this module does not model. This
 * job writes only flat artefacts, so it has <b>no such dependency</b>: no customer entity, no card
 * entity, no customer repository, no card repository and no {@code card.*} grant. Same copybook,
 * opposite direction, different architectural consequence.
 *
 * <h2>The five divergences this file carries, and a numbering collision</h2>
 *
 * <p>⚠️ Assumptions: the register at {@code docs/architecture/cobol-to-service-traceability.md} is the
 * home of every documented divergence, and <b>its numbering and the labels used here agree on D-1
 * only.</b> That register enumerates D-1 through D-7 under its own numbering; its D-4 is the
 * plaintext-credential field, its D-5 is the reply published before the decision commits, and its D-7 is
 * the online header clock. The import-specific differences below are labelled D-4, D-5, D-8 and D-9
 * because this file's specification and the sibling {@link ExportJob} both use those labels, and they
 * are therefore identified <b>by subject</b> as well as by label throughout. A reader following the
 * bare label into the register would land on unrelated entries, which is precisely why the collision is
 * recorded here rather than left to be discovered.
 *
 * <p>Refactoring Rationale: all five are now registered, in
 * {@code docs/architecture/cobol-to-service-traceability.md} section "Batch export and import
 * divergences", each keyed by its subject and cross-referenced to the label used here. An earlier
 * revision of this comment stated that only D-1 was registered and left the other three "for that
 * register's owner to absorb", which made every claim of a documented divergence in this file a claim
 * about a document that did not describe it. A divergence recorded only beside the code it lives in is
 * not registered: the register is what a reader consults who is comparing the two systems and does not
 * know which file to open.
 *
 * <h3>Refactoring Rationale: divergence D-1, the file-description record key</h3>
 *
 * <p>{@code app/cbl/CBIMPORT.cbl:37-41} selects the input {@code ORGANIZATION IS INDEXED} with
 * {@code ACCESS MODE IS SEQUENTIAL} and declares {@code RECORD KEY IS EXPORT-SEQUENCE-NUM} at
 * {@code app/cbl/CBIMPORT.cbl:40}. The named key is not a field of the file record. That record is
 * unstructured and carries no subordinate items at all —
 * {@code app/cbl/CBIMPORT.cbl:76-79} declare {@code RECORD CONTAINS 500 CHARACTERS.} over a bare
 * {@code 01 EXPORT-INPUT-RECORD PIC X(500).} — so the name resolves instead to the working-storage copy
 * taken at {@code app/cbl/CBIMPORT.cbl:111-113}, where {@code COPY CVEXPORT.} sits beneath
 * {@code WORKING-STORAGE SECTION.} and declares the field at {@code app/cpy/CVEXPORT.cpy:16}.
 * {@code app/cbl/CBEXPORT.cbl:68} carries the identical declaration against the identical shape.
 *
 * <p>Three consequences are verified rather than assumed: the pair does not compile under the
 * open-source compiler, so ten of the twelve batch programs build and run and these two do not; no
 * compiler flag repairs it, because the defect is semantic; and the reference-only policy over
 * {@code app/**} forbids editing it. The build script classifies the pair as known-unsupported and
 * aggregates a soft warning rather than poisoning the aggregate result, and the export/import
 * integration test is skipped for that stated reason.
 *
 * <p><b>The aggregate warn-level result this produces is the parity suite's GREEN state, and D-1 is its
 * sole cause.</b> It is not a regression, it is not attributable to this migration, and it is not to be
 * "fixed" in the COBOL.
 *
 * <p><b>The ruling.</b> The artefact is read <b>sequentially</b>. The sequence number is a field inside
 * the record at zero-based offset 27, used here for error reporting and ordering and never as a file
 * access key. There is no index, no keyed read and no record-key analogue anywhere in this class.
 * Refactoring Rationale: the indexed organisation bought the reference nothing. Its only read is
 * {@code READ EXPORT-INPUT INTO EXPORT-RECORD} at {@code app/cbl/CBIMPORT.cbl:261-267} — a sequential
 * read into the working-storage buffer — and it never accesses the file by key.
 * {@code ACCESS MODE IS SEQUENTIAL} over an indexed organisation already says the access pattern is
 * sequential, so the correct keying the migration plan asks for is <em>no file key at all</em>, with the
 * sequence number retained as data.
 *
 * <p>Trade-offs: the output is consequently not byte-comparable against any reference run, because no
 * reference run exists. The program has never executed under the open-source toolchain.
 *
 * <p>⚠️ Assumptions: <b>there is no golden master for import, so fidelity rests on transcription.</b>
 * Every other dataset this module writes has a committed expectation file produced by running the
 * reference. This one cannot: the program does not compile, so it has never run, so nothing recorded its
 * output. The register says the same, calling D-1 the one divergence with no golden master to compare
 * against and naming the record layout as the oracle instead. Fidelity here therefore comes only from
 * reading {@code app/cbl/CBIMPORT.cbl} and transcribing it, and every offset, discriminator, literal and
 * counter in this class was verified against the source line cited beside it rather than trusted. A
 * reader changing any of them should re-verify the same way instead of relying on a test to catch the
 * error. It is also why D-8 below removes the wall clock: with no oracle, comparing two runs of the
 * target against each other is the only verification left, and a clock read destroys it.
 *
 * <h3>⚠️ Assumptions and Trade-offs: divergence D-4, the missing card data definition</h3>
 *
 * <p>This finding is not available from the program alone; it requires reading the driver against the
 * program. The reference fully supports a card output: it selects
 * {@code CARD-OUTPUT ASSIGN TO CARDOUT} at {@code app/cbl/CBIMPORT.cbl:63-66}, describes it at 150
 * characters at {@code app/cbl/CBIMPORT.cbl:101-104}, <b>opens it with an abend on failure</b> at
 * {@code app/cbl/CBIMPORT.cbl:233-238}, writes to it at {@code app/cbl/CBIMPORT.cbl:414}, closes it at
 * {@code app/cbl/CBIMPORT.cbl:462} and reports its count at {@code app/cbl/CBIMPORT.cbl:475}.
 *
 * <p>⚠️ {@code app/jcl/CBIMPORT.jcl} allocates only <b>five</b> data definitions — {@code CUSTOUT} at
 * {@code :33}, {@code ACCTOUT} at {@code :38}, {@code XREFOUT} at {@code :43}, {@code TRNXOUT} at
 * {@code :48} and {@code ERROUT} at {@code :56}. <b>There is no {@code //CARDOUT DD}.</b> Because
 * {@code 1100-OPEN-FILES} at {@code app/cbl/CBIMPORT.cbl:196-245} opens all seven files and abends on
 * any failure, the missing allocation kills the run at open time, before a single record is processed.
 * The committed driver cannot run the committed program to completion. This is independent of D-1 and
 * would remain a defect even if the program compiled.
 *
 * <p><b>The ruling.</b> The target emits <b>all six</b> artefacts, including the card artefact.
 * Assumptions: the program's intent is unambiguous — it declares, opens, writes, closes and reports that
 * file — so the job control is what is incomplete, not the program. The card record type is part of the
 * export contract: {@link ExportJob} writes discriminator {@code 'D'} and this program's own dispatcher
 * routes it at {@code app/cbl/CBIMPORT.cbl:281-282}, so dropping it would lose card data silently rather
 * than loudly. Trade-offs: the target therefore produces one more artefact than the committed driver
 * allocates. That is a deliberate, documented divergence rather than an accident, and it is the reason
 * this class names six members where a reader counting data definitions in the job control would expect
 * five.
 *
 * <h3>⚠️ Assumptions and Trade-offs: divergence D-5, the checksum that does not exist</h3>
 *
 * <p>The program header at {@code app/cbl/CBIMPORT.cbl:31} promises
 * <em>"Validate data integrity using checksums"</em>. The paragraph that would do it,
 * {@code 3000-VALIDATE-IMPORT} at {@code app/cbl/CBIMPORT.cbl:449-452}, contains <b>two unconditional
 * display statements and nothing else</b>. There is no checksum logic anywhere in the program, and the
 * second message asserts a clean result unconditionally — even on a run that wrote error records. It is
 * a claim, not a check.
 *
 * <p><b>The ruling.</b> The validation step is made real and honest. It reconciles the total records
 * read against the sum of the six per-type counters, which are accumulated by separate statements and so
 * can genuinely disagree; it reports the actual error and unknown-type counts; and it preserves both
 * message strings verbatim while emitting the second one <b>only</b> when the error and unknown-type
 * counts are both zero. Assumptions: the header's stated intent is the design and the paragraph is an
 * unimplemented stub, so implementing it is completing the program rather than changing it.
 *
 * <p>Trade-offs: making an unconditional claim conditional is a behavioural change. The target's
 * validation step can now report a problem where the baseline always reported success, so a run that
 * previously logged a clean result may now log errors. That is the point of the change, but it is
 * visible from outside and is registered rather than slipped in.
 *
 * <p>Alternatives Considered: computing a real checksum — a digest over the artefact, or a per-record
 * hash. <b>Rejected, and the rejection is itself the decision worth recording.</b> Nothing in the
 * baseline specifies an algorithm, a width or a place to put the result, so any choice would be
 * fabricated, and a fabricated algorithm presented as a migrated one is exactly the undocumented
 * non-obvious choice Rule 1 forbids. Count reconciliation is the integrity check the program's own eight
 * counters actually support, so that is what is implemented, and no checksum algorithm was invented.
 *
 * <h3>Refactoring Rationale: divergence D-8, the wall clock</h3>
 *
 * <p>{@code 1000-INITIALIZE} builds its date and time from {@code FUNCTION CURRENT-DATE} at
 * {@code app/cbl/CBIMPORT.cbl:178-188} and echoes them at {@code app/cbl/CBIMPORT.cbl:192-193}; the
 * unknown-record handler reads the clock again at {@code app/cbl/CBIMPORT.cbl:429} for the error
 * record's stamp. Because {@code app/jcl/CBIMPORT.jcl:22} passes no {@code PARM}, nothing is injectable
 * and a reference run is not reproducible even in principle.
 *
 * <p><b>The ruling.</b> The run stamp is a parameter, defaulted to the current instant only when absent,
 * and that one value is used everywhere the baseline reads the clock — including every error record.
 * Refactoring Rationale: the migration plan requires the business path to be parameter-driven so a rerun
 * is reproducible, and for an artefact with <b>no golden master</b> a byte comparison of two target runs
 * is the only verification available, which a clock read makes impossible. A single injected instant also
 * makes every error record of one run share a stamp, which is what makes them correlatable.
 *
 * <p>⚠️ Assumptions: the stamp shapes in this program and in the posting and interest programs are
 * <b>not interchangeable</b>, and the shape used here is this program's own.
 * {@code app/cbl/CBIMPORT.cbl:135-136} declares {@code WS-IMPORT-DATE PIC X(10)} holding
 * {@code YYYY-MM-DD} and {@code WS-IMPORT-TIME PIC X(08)} holding {@code HH:MM:SS} as two separate
 * fields, whereas posting and interest build a single combined stamp. Both echoes are therefore derived
 * as slices of one 26-character value produced by {@link TimestampFormatter}: the ten-character date
 * prefix and the eight characters at offset eleven. The error record's stamp keeps the full 26, because
 * {@code app/cbl/CBIMPORT.cbl:153} declares that field {@code PIC X(26)} and the formatter's contract
 * width is exactly 26. Nothing here builds a timestamp string by hand.
 *
 * <h3>⚠️ Assumptions and Trade-offs: divergence D-9, registered as
 * {@code D-IMPORT-TRUNCATED-ARTEFACT} -- a truncated artefact fails the run</h3>
 *
 * <p>The reference cannot meet this condition. Its input is selected {@code ORGANIZATION IS SEQUENTIAL}
 * with {@code RECFM=FB} at {@code app/jcl/CBIMPORT.jcl:28-32}, and a fixed-blocked dataset's length is
 * always a whole multiple of its record length, so a partial trailing image is not a state the storage
 * can hold. The target's input is an object, whose length is whatever was written, so the condition is
 * reachable here and needs an answer the reference never had to give.
 *
 * <p><b>The ruling.</b> A trailing image shorter than one record <b>fails the run before any artefact is
 * published</b>. The diagnostic record is assembled and the condition is logged, and then the pass
 * raises, so {@code 4000-FINALIZE} is never reached and the six accumulators are discarded with the
 * failed transaction. The step reports the hard-failure tier.
 *
 * <p>Refactoring Rationale: this branch previously recorded the short image and stopped reading, after
 * which the run reconciled the whole records it had, published all six artefacts and returned the clean
 * tier. Every one of those steps is individually defensible and the combination is not: the artefacts
 * this job produces have <b>no golden master</b> — D-1 above is the reason — so a consumer has nothing to
 * compare them against and no way to tell a complete set from a set missing everything after a
 * truncation point. A clean status on a knowingly partial product is the one outcome that cannot be
 * detected downstream, and it is worse than a failure precisely because it is actionable-looking.
 *
 * <p>Alternatives Considered: the soft-warn tier, publishing what was read and grading the run four.
 * Rejected because that tier gates the nightly chain's {@code Choice} states rather than stopping them,
 * and because it would still leave the incomplete artefacts in the store for a loader to consume.
 * Alternatives Considered: publishing the diagnostic artefact alone, so the truncation is recorded in the
 * store as well as the log. Rejected because a diagnostic artefact standing beside five absent ones is
 * indistinguishable from a run that never wrote them, and the log line carries the same three facts —
 * offset, bytes remaining, bytes expected — through a channel the failure does not discard.
 *
 * @see ExportJob
 * @see ExportRecordMapper
 * @see BatchJobName#IMPORT
 */
@Configuration(proxyBeanMethods = false)
public class ImportJob {

    /** Diagnostic channel for this job; the reference writes the same lines to its system output. */
    private static final Logger LOG = LoggerFactory.getLogger(ImportJob.class);

    /**
     * Registered job name and ledger step name, taken from the orchestration vocabulary.
     *
     * <p>⚠️ Assumptions: the token is read from {@link BatchJobName#IMPORT} and never written as a
     * literal, because the container resolves a job through the Spring Batch registry <b>by name</b>
     * and never by importing this class, so a literal that drifted from the enumeration would fail only
     * at run time with a token the registry does not hold. The token is also the reason no symbol in
     * this file is named after it: the word is {@code import}, a Java reserved word, so it can exist
     * only as a string constant and any attempt to name a field, method or class for it would not
     * compile.</p>
     */
    public static final String JOB_NAME = BatchJobName.IMPORT.token();

    /**
     * Ledger step name, held separately from {@link #JOB_NAME} even though the two values agree.
     *
     * <p>Assumptions: the durable ledger keys on the run identifier paired with the <b>step</b> name,
     * so the step name is a persisted contract of its own rather than a display label. The five landed
     * nightly jobs each declare the pair as two constants for that reason, and this job follows them so
     * that a future second step in this job cannot silently inherit the job's own key.</p>
     */
    public static final String STEP_NAME = JOB_NAME;

    /** Key prefix the export artefact is read from; the sibling export job's write location. */
    private static final String EXPORT_KEY_PREFIX = "export/";

    /** Member name of the export artefact within its business-date partition. */
    private static final String EXPORT_MEMBER = "/export.dat";

    /** Key prefix the six separated artefacts are written under. */
    private static final String IMPORT_KEY_PREFIX = "import/";

    /**
     * Bare name of the diagnostic artefact, used for both its object key and its staging filename.
     *
     * <p>Assumptions: it is spelled once here rather than in the member constant and again in the staging
     * call, for the reason {@link #memberStem} records for the five typed artefacts.</p>
     */
    private static final String ERROR_STEM = "error";

    /**
     * Member name of the diagnostic artefact, replacing {@code ERROUT}.
     *
     * <p>Assumptions: the five per-type members are named from {@link RecordType} rather than listed
     * here, so a sixth record type could not acquire an artefact without also acquiring a name. Only
     * the error member has no record type to be named from, which is why it alone is a literal.</p>
     */
    private static final String ERROR_MEMBER = "/" + ERROR_STEM + ".dat";

    /**
     * Content type recorded on every written artefact.
     *
     * <p>Assumptions: binary, because the artefacts carry zoned-decimal sign overpunches and the input
     * additionally carries packed nibble pairs and binary words. Declaring them text would invite a
     * transcoding hop, and the parity suite records that routing binary content through a text codec
     * mangles it into replacement characters while leaving every width plausible.</p>
     */
    private static final String CONTENT_TYPE = "application/octet-stream";

    /**
     * Filename prefix of every temporary file this job stages an artefact through.
     *
     * <p>Assumptions: a single shared prefix is used so that an operator inspecting the task's
     * temporary directory can tell at a glance which files belong to this job, and so that the
     * discard path can be asserted by a test that looks for the prefix rather than for one exact
     * name. The prefix matches the convention the sibling dataset jobs already use, which is why it
     * is stated as a constant here rather than inlined at each {@code createTempFile} call.</p>
     */
    private static final String STAGING_FILE_PREFIX = "carddemo-import-";

    /**
     * Value written into the card artefact's verification-value span in place of the carried digits.
     *
     * <p>⚠️ Refactoring Rationale: the reference moves {@code EXP-CARD-CVV-CD} into
     * {@code CARD-CVV-CD} at {@code app/cbl/CBIMPORT.cbl:409}, and an earlier revision of this class
     * carried it across unchanged. That is faithful transcription into an unfaithful destination: the
     * artefact is a durable object in the dataset bucket, so carrying the value would leave sensitive
     * authentication data at rest after authorisation. The span is therefore written as zeros, which
     * keeps the record exactly its declared width and every following field at its declared offset
     * while carrying no value.</p>
     *
     * <p>Assumptions: the field is declared {@code PIC 9(03)} — {@link CopybookLayout#sensitiveUint}
     * at {@code CopybookLayout} line 1573 — so the codec renders an integral zero as {@code "000"},
     * three digit bytes, and a consumer reading the artefact under the same descriptor decodes a
     * well-formed zero rather than a blank it would have to special-case. A blank span was rejected
     * for exactly that reason: {@code unsignedText} refuses non-digit bytes, so blanks could not be
     * encoded at all without weakening the codec for every unsigned field in the corpus.</p>
     *
     * <p>Assumptions: the type is {@code Long} and not {@code BigDecimal}, because
     * {@link #adaptToTargetKind} converts a decimal to a {@code long} for a display-numeric target and
     * supplying the {@code long} directly means the redaction takes the same path as any other
     * already-integral value rather than a conversion path of its own.</p>
     */
    private static final Long REDACTED_VERIFICATION_VALUE = 0L;

    /**
     * Value written into a display-numeric identifier span in place of the carried digits.
     *
     * <p>⚠️ Refactoring Rationale: {@code CUST-SSN} is the one display-numeric span among the
     * protected fields the customer artefact declares, and it is redacted for the same reason the
     * verification value is — the artefact is a durable object in the dataset bucket, and the migration
     * plan's own data-exposure rule holds that a national identifier is stored encrypted and returned
     * masked. Writing it in clear into an object outside the database would be the one place that rule
     * did not hold, which is precisely the sort of quiet exception a security inventory exists to
     * remove.</p>
     *
     * <p>Assumptions: this is a separate constant from the verification value even though both are
     * {@code 0L}, because they are redacted under two different rules — one prohibited outright after
     * authorisation, one permitted in the database but only encrypted — and folding them into one
     * constant would make a later change to either silently change the other.</p>
     */
    private static final Long REDACTED_NUMERIC_IDENTIFIER = 0L;

    /**
     * Value written into a character identifier span in place of the carried characters.
     *
     * <p>⚠️ Refactoring Rationale: {@code CUST-GOVT-ISSUED-ID} is declared as twenty characters rather
     * than digits, so its redaction has to be a character value; the codec blank-fills a field from the
     * end of the supplied text, so the empty string leaves the span exactly its declared width of
     * blanks. A span of blanks rather than of zeros is the right redaction for a character field
     * because blanks are what {@code INITIALIZE} leaves in an alphanumeric field, so a consumer reading
     * the artefact sees the value the reference itself would have left in an unset one.</p>
     *
     * <p>Assumptions: the field is redacted under the same rule as {@link #REDACTED_NUMERIC_IDENTIFIER}
     * and the two are named apart only because the codec accepts digits for one and characters for the
     * other.</p>
     */
    private static final String REDACTED_TEXT_IDENTIFIER = "";

    /**
     * Character set the fixed-width artefacts are encoded in.
     *
     * <p>Assumptions: the shared codec's own default is this same single-byte set, and it is named here
     * only so the error record — which this class lays out itself rather than through a descriptor —
     * cannot drift onto a different one. A multi-byte set would break every offset in the layout,
     * because a fixed-width field's width is a byte count and not a character count.</p>
     */
    private static final java.nio.charset.Charset ARTEFACT_CHARSET = StandardCharsets.US_ASCII;

    /**
     * Start banner, verbatim from {@code app/cbl/CBIMPORT.cbl:176}.
     *
     * <p>Assumptions: every operator-visible line in this class is carried across
     * character-for-character under the migration plan's transformation rule T8, including this one's
     * slightly misleading wording — the run imports five record types, not only customers. It is
     * reproduced as written rather than corrected, because a message the plan requires verbatim is not
     * the place to improve on the baseline.</p>
     */
    private static final String LOG_STARTING = "CBIMPORT: Starting Customer Data Import";

    /** Stamp date echo, verbatim from {@code app/cbl/CBIMPORT.cbl:192}. */
    private static final String LOG_IMPORT_DATE = "CBIMPORT: Import Date: ";

    /** Stamp time echo, verbatim from {@code app/cbl/CBIMPORT.cbl:193}. */
    private static final String LOG_IMPORT_TIME = "CBIMPORT: Import Time: ";

    /**
     * Validation banner, verbatim from {@code app/cbl/CBIMPORT.cbl:451}.
     *
     * <p>Assumptions: this line stays <b>unconditional</b>, exactly as the reference emits it. Only its
     * companion below becomes conditional, and the split between the two is the whole of divergence
     * D-5's observable effect.</p>
     */
    private static final String LOG_VALIDATION_COMPLETED = "CBIMPORT: Import validation completed";

    /**
     * Clean-result assertion, verbatim from {@code app/cbl/CBIMPORT.cbl:452}.
     *
     * <p>⚠️ Trade-offs: the reference emits this line unconditionally, so a baseline run that wrote
     * error records still claimed no errors were detected. The string is preserved character for
     * character under rule T8, and only the condition under which it is emitted changes — it is now
     * emitted solely when the error and unknown-type counters are both zero. See divergence D-5 on the
     * class.</p>
     */
    private static final String LOG_NO_VALIDATION_ERRORS =
            "CBIMPORT: No validation errors detected";

    /** Closing banner, verbatim from {@code app/cbl/CBIMPORT.cbl:465}. */
    private static final String LOG_COMPLETED = "CBIMPORT: Import completed";

    /** Summary total read, verbatim from {@code app/cbl/CBIMPORT.cbl:466}. */
    private static final String LOG_TOTAL_READ = "CBIMPORT: Total Records Read: ";

    /**
     * Summary customer tally, verbatim from {@code app/cbl/CBIMPORT.cbl:468}.
     *
     * <p>⚠️ Assumptions: this program capitalises {@code Imported: } in <b>every</b> tally line, and
     * that is worth stating because the sibling export program does not: {@code ExportJob} carries two
     * capitalisations, a lower-case per-phase form and a capitalised summary form, and it keeps them as
     * separate constants for that reason. Reusing an export-shaped constant here, or normalising the two
     * programs onto one form, would silently alter operator-visible text that rule T8 requires
     * reproduced exactly. Each constant below therefore cites the single source line it was copied
     * from.</p>
     */
    private static final String LOG_CUSTOMERS_IMPORTED = "CBIMPORT: Customers Imported: ";

    /** Summary account tally, verbatim from {@code app/cbl/CBIMPORT.cbl:470}. */
    private static final String LOG_ACCOUNTS_IMPORTED = "CBIMPORT: Accounts Imported: ";

    /**
     * Summary cross-reference tally, verbatim from {@code app/cbl/CBIMPORT.cbl:472}.
     *
     * <p>⚠️ Assumptions: the spelling is {@code XRefs} — capital X, capital R, lower-case s. Neither
     * {@code XREFs} nor {@code Xrefs} appears anywhere in the reference, and unlike the export program
     * this one uses the abbreviation in its only tally line rather than spelling the word out in a
     * second one.</p>
     */
    private static final String LOG_XREFS_IMPORTED = "CBIMPORT: XRefs Imported: ";

    /** Summary transaction tally, verbatim from {@code app/cbl/CBIMPORT.cbl:473}. */
    private static final String LOG_TRANSACTIONS_IMPORTED = "CBIMPORT: Transactions Imported: ";

    /** Summary card tally, verbatim from {@code app/cbl/CBIMPORT.cbl:475}. */
    private static final String LOG_CARDS_IMPORTED = "CBIMPORT: Cards Imported: ";

    /** Summary error tally, verbatim from {@code app/cbl/CBIMPORT.cbl:476}. */
    private static final String LOG_ERRORS_WRITTEN = "CBIMPORT: Errors Written: ";

    /** Summary unknown-type tally, verbatim from {@code app/cbl/CBIMPORT.cbl:477}. */
    private static final String LOG_UNKNOWN_TYPES = "CBIMPORT: Unknown Record Types: ";

    /**
     * Diagnostic emitted when a record cannot be read, verbatim from
     * {@code app/cbl/CBIMPORT.cbl:264}.
     *
     * <p>Assumptions: the reference appends its two-character file status to this text. The target has
     * no file status to append, so the exception's own type is reported in its place — the same
     * information at the same point, in the vocabulary the target actually has.</p>
     */
    private static final String LOG_READ_ERROR = "ERROR: Reading EXPORT-INPUT, Status: ";

    /**
     * Diagnostic emitted when the error artefact itself cannot be written, verbatim from
     * {@code app/cbl/CBIMPORT.cbl:442}.
     *
     * <p>⚠️ Assumptions: this is the one write diagnostic in the program that is <b>not</b> followed by
     * an abend. See {@link #recordUnknownType} for why the asymmetry is preserved.</p>
     */
    private static final String LOG_ERROR_WRITE_FAILED = "ERROR: Writing error record, Status: ";

    /**
     * Abend banner, verbatim from {@code app/cbl/CBIMPORT.cbl:483}.
     *
     * <p>Refactoring Rationale: the reference reaches this line from every failure path — a failed open
     * at {@code app/cbl/CBIMPORT.cbl:196-245}, a failed read at {@code :261-267}, or any of the five
     * failed writes — and follows it at {@code app/cbl/CBIMPORT.cbl:484} with {@code CALL 'CEE3ABD'},
     * the language-environment abend primitive. The banner is carried because rule T8 makes it
     * operator-visible text like any other, and an operator who knows the baseline should recognise a
     * failed import from its log.</p>
     *
     * <p>Alternatives Considered: reproducing the abend call itself, for instance by halting the
     * process. Rejected: the primitive exists because COBOL has no exception to propagate, whereas the
     * migration plan's transformation rule T5 maps a rollback to exception propagation, and the shared
     * step's own handling already translates a thrown exception into the hard-failure tier and routes
     * the orchestration state to its failure branch. Halting would bypass that translation and lose the
     * ledger row, so the banner is logged and the exception rethrown unchanged.</p>
     */
    private static final String LOG_ABENDING = "CBIMPORT: ABENDING PROGRAM";

    /**
     * Message written into every unknown-type diagnostic record, verbatim from
     * {@code app/cbl/CBIMPORT.cbl:432}.
     */
    private static final String MSG_UNKNOWN_RECORD_TYPE = "Unknown record type encountered";

    /**
     * Message written into a diagnostic record for a trailing image shorter than one record.
     *
     * <p>⚠️ Assumptions: <b>this string is additive and has no baseline counterpart, because the
     * condition it reports cannot arise in the baseline.</b> The datasets these artefacts replace are
     * {@code RECFM=FB}, so their length is always a whole multiple of the record length and a short
     * trailing image is not representable; the reference consequently has exactly one diagnostic
     * message, the unknown-type one above. Rule T8 requires existing operator-visible strings to be
     * reproduced verbatim, which this does not contradict — there is no string here to reproduce.</p>
     *
     * <p>Alternatives Considered: reusing the unknown-type message for this case. Rejected because it
     * would name the wrong fault: a truncated artefact is not a record carrying an unrecognised
     * discriminator, and an operator told the wrong cause looks in the wrong place. Also considered and
     * rejected: treating a short remainder as end-of-data and dropping it silently, which would report a
     * partial import as a complete one.</p>
     */
    private static final String MSG_SHORT_RECORD = "Truncated record at end of export artefact";

    /**
     * Total width of the diagnostic record, from {@code app/cbl/CBIMPORT.cbl:106-109}.
     *
     * <p>⚠️ Assumptions: <b>the named fields of {@code WS-ERROR-RECORD} sum to 130 while the file
     * record is declared 132, and the discrepancy is understood rather than a defect.</b> The layout at
     * {@code app/cbl/CBIMPORT.cbl:152-160} is a 26-character stamp, a separator, a one-character type, a
     * separator, a seven-digit sequence, a separator, a 50-character message and a 43-character pad:
     * {@code 26 + 1 + 1 + 1 + 7 + 1 + 50 + 43 = 130}. The file description declares
     * {@code 01 ERROR-OUTPUT-RECORD PIC X(132).} and {@code app/jcl/CBIMPORT.jcl:56-60} allocates
     * {@code LRECL=132}, so the move at {@code app/cbl/CBIMPORT.cbl:439} space-pads the two-byte
     * shortfall. This class therefore emits exactly 132 bytes with the final two as spaces, and the
     * arithmetic is written out so a future reader does not "fix" the 130 into a 132 by widening a
     * field.</p>
     */
    /**
     * Byte capacity of the buffers wrapped around the streamed input and the staged outputs.
     *
     * <p>Assumptions: {@value} is 128 whole 500-byte export records, so a buffer boundary never falls
     * inside a record and a staged output truncated by a task kill ends on a record boundary. That makes
     * a partial artefact diagnosable by dividing its length by its declared record length. Trade-offs:
     * the whole point of streaming the input and staging the outputs is to stop holding a dataset in
     * memory, so the buffers are deliberately small enough not to become the memory problem themselves --
     * 64 000 bytes each is negligible beside a task's allocation while still reducing one system call per
     * record to one per 128.</p>
     */
    private static final int READ_BUFFER = 64_000;

    private static final int ERROR_RECORD_LENGTH = 132;

    /**
     * Width of the sequence field in the diagnostic record, from {@code app/cbl/CBIMPORT.cbl:157}.
     *
     * <p>⚠️ Trade-offs: {@code ERR-SEQUENCE} is declared {@code PIC 9(07)} while the value moved into
     * it at {@code app/cbl/CBIMPORT.cbl:431} is {@code EXPORT-SEQUENCE-NUM PIC 9(9)}
     * ({@code app/cpy/CVEXPORT.cpy:16}), so COBOL truncates the <b>two high-order digits</b> and reports
     * sequence 12,345,678 as 2,345,678. <b>The truncation is reproduced here rather than repaired.</b>
     * The alternative — widening the field to nine digits — was considered and rejected because this
     * artefact is a fixed-width interface whose layout is its contract, and any consumer parses it
     * positionally, so widening one field silently moves the message field and the pad for every reader.
     * The cost accepted is that the record misidentifies which input record failed once an artefact
     * exceeds 9,999,999 records, which is the exact situation in which the diagnostic matters most; that
     * cost is paid down in {@link #recordUnknownType}, which logs the <b>untruncated</b> sequence number
     * alongside, so the information is narrowed in the artefact but not lost.</p>
     */
    private static final int ERROR_SEQUENCE_WIDTH = 7;

    /**
     * Width of the message field in the diagnostic record, from {@code app/cbl/CBIMPORT.cbl:159}.
     */
    private static final int ERROR_MESSAGE_WIDTH = 50;

    /**
     * Modulus that truncates a sequence number to {@link #ERROR_SEQUENCE_WIDTH} digits.
     *
     * <p>Assumptions: ten raised to the field width is what a COBOL move into a shorter numeric display
     * field does — it keeps the low-order digits and discards the rest — so the arithmetic reproduces
     * the baseline rather than approximating it. It is derived from the width constant rather than
     * written as {@code 10000000}, so the two cannot disagree.</p>
     */
    private static final long ERROR_SEQUENCE_MODULUS = (long) Math.pow(10, ERROR_SEQUENCE_WIDTH);

    /** Field separator in the diagnostic record, from {@code app/cbl/CBIMPORT.cbl:154}. */
    private static final char ERROR_FIELD_SEPARATOR = '|';

    /**
     * Name of the 460-byte payload span within the record descriptor, from
     * {@code app/cpy/CVEXPORT.cpy:19}.
     *
     * <p>Assumptions: the span's offset is taken from the descriptor rather than written as 40, so the
     * prefix arithmetic {@code 1 + 26 + 4 + 4 + 5} lives in one place. The term that misleads is the
     * third: {@code EXPORT-SEQUENCE-NUM} is declared {@code PIC 9(9) COMP} at
     * {@code app/cpy/CVEXPORT.cpy:16}, so it occupies <b>four binary bytes and not nine display
     * characters</b>, and reading it as nine would shift the payload five bytes late.</p>
     */
    private static final String PAYLOAD_FIELD = "EXPORT-RECORD-DATA";

    /** Name of the discriminator field within the record descriptor, {@code app/cpy/CVEXPORT.cpy:10}. */
    private static final String DISCRIMINATOR_FIELD = "EXPORT-REC-TYPE";

    /**
     * Name of the sequence-number field within the record descriptor,
     * {@code app/cpy/CVEXPORT.cpy:16}.
     *
     * <p>Assumptions: this is the field {@code app/cbl/CBIMPORT.cbl:40} names as the file's record key
     * and which is not a field of the file record at all. Here it is read as what it actually is — data
     * inside the record — which is the whole of divergence D-1.</p>
     */
    private static final String SEQUENCE_FIELD = "EXPORT-SEQUENCE-NUM";

    /**
     * The five per-type mappings, each pairing one payload view with the artefact it is written to.
     *
     * <p>Assumptions: the map is keyed by {@link RecordType} and switched on exhaustively, so a sixth
     * record type could not be added to the export contract without this class failing its own
     * start-up geometry check rather than silently routing the new type to the unknown-type
     * handler.</p>
     */
    private static final Map<RecordType, Artefact> ARTEFACTS = artefacts();

    /**
     * Assembles the import job and registers it under the token the container resolves by name.
     *
     * <p>Assumptions: the step is built through the shared {@link LedgerGuardedStep} rather than a bare
     * step builder, which is what supplies the durable {@code (runId, stepName)} idempotency key: the
     * ledger looks the pair up before running the body and replays a recorded outcome instead of
     * repeating the work. That matters more here than in most jobs, because a redriven import that ran
     * its body twice would <b>append a second copy of every record to all six artefacts</b> rather than
     * merely repeating a read. No part of that mechanism is reimplemented here.</p>
     *
     * <p>Alternatives Considered: injecting {@code BatchRunRepository} directly to perform the
     * idempotency check in this class. Rejected because the shared ledger already performs exactly that
     * lookup against exactly that repository, so a second check here would be a competing mechanism
     * keyed on the same row — two writers of one ledger entry, with no way for a reader to tell which
     * one recorded a given outcome.</p>
     *
     * @param jobRepository the batch job repository the job and its step are registered against; must
     *     not be {@code null}
     * @param transactionManager the transaction manager whose boundary the step body runs inside; must
     *     not be {@code null}
     * @param steps the shared ledger-guarded step builder supplying idempotency and exit-status
     *     translation; must not be {@code null}
     * @param validator the shared parameter validator every job in this package is built with; must not
     *     be {@code null}
     * @param objectStore the object store the artefact is read from and the six outputs written to;
     *     must not be {@code null}
     * @param bucket the dataset bucket name; must not be {@code null} or blank
     * @param importTimestamp the 26-character run stamp to apply, or blank to fall back to the clock;
     *     must not be {@code null}
     * @param clock the fallback time source, consulted only when {@code importTimestamp} is blank; must
     *     not be {@code null}
     * @return the job, registered under exactly the token {@code import}; never {@code null}
     */
    @Bean
    public Job importDataset(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            LedgerGuardedStep steps,
            JobParametersValidator validator,
            S3Client objectStore,
            @Value("${carddemo.dataset.bucket}") String bucket,
            @Value("${carddemo.import.timestamp:}") String importTimestamp,
            Clock clock) {

        // WHY : Assumptions: resolving it here rather than per record is what makes one run carry one
        //       stamp, which is the reference's behaviour -- app/cbl/CBIMPORT.cbl:178-188 builds its
        //       date and time once inside 1000-INITIALIZE, and the whole run then reports those two
        //       values. Resolving per record would additionally give every diagnostic record of one run
        //       a different stamp, which is the opposite of what correlating them needs.
        ImportStamp stamp = ImportStamp.resolve(importTimestamp, clock);

        Step step = steps.build(STEP_NAME, BatchJobName.IMPORT, jobRepository, transactionManager,
                businessDate -> separate(businessDate, objectStore, bucket, stamp));

        // WHY : Refactoring Rationale: the shared validator is attached, which it previously was not.
        //       Five of this package's seven jobs were built with it and this job and the export were
        //       not, so a launch of either with a missing business date reached the step body and failed
        //       partway -- and for THIS job "partway" means an artefact set may already be half written.
        //       A parameter validator refuses the launch before the job instance is created, which is
        //       both earlier and cheaper. Assumptions: the validator is the single bean the batch
        //       configuration publishes rather than one constructed here, so the required and optional
        //       parameter names are stated once for all seven jobs and cannot drift between two of them.
        return new JobBuilder(JOB_NAME, jobRepository).validator(validator).start(step).build();
    }

    /**
     * Splits the export artefact into six artefacts, using the reference's own parameterless stamping.
     *
     * <p>Assumptions: this is the shape of a reference invocation, and it exists because that shape is
     * worth naming. {@code app/jcl/CBIMPORT.jcl:22} passes no {@code PARM}, so a baseline run always
     * takes its stamp from the clock. A caller wanting exactly that reaches this overload; a caller
     * wanting a reproducible artefact reaches the one below with an explicit {@link ImportStamp}.</p>
     *
     * @param businessDate the injected business date identifying the artefact partition; must not be
     *     {@code null}
     * @param objectStore the object store; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null} or blank
     * @return always {@link BatchReturnCode#CLEAN} on completion; see the exit-status note on
     *     {@link #separate(BusinessDate, S3Client, String, ImportStamp)}
     * @throws IllegalStateException if the artefact is truncated, per divergence D-9, or if the
     *     independently accumulated total disagrees with the sum of the per-type counters
     */
    static BatchReturnCode separate(BusinessDate businessDate, S3Client objectStore, String bucket) {
        // WHY : Assumptions: the clock is read through the same resolver the bean uses rather than
        //       here, so there is exactly one place in this class where a clock may be read and one
        //       place that warns about the loss of reproducibility it causes.
        return separate(businessDate, objectStore, bucket, ImportStamp.baseline(Clock.systemDefaultZone()));
    }

    /**
     * Splits the export artefact into six artefacts under an explicit run stamp.
     *
     * <p>Refactoring Rationale: the body is bracketed so the reference's abend banner still appears on
     * every failure path. {@code app/cbl/CBIMPORT.cbl} reaches {@code 9999-ABEND-PROGRAM} from a failed
     * open, a failed read and each of its five failed writes alike, so a single bracket here is the
     * faithful shape rather than a banner repeated at each call site. The exception is rethrown
     * unchanged, which leaves the tier translation and the ledger row to the step that owns them.</p>
     *
     * @param businessDate the injected business date identifying the artefact partition; must not be
     *     {@code null}
     * @param objectStore the object store; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null} or blank
     * @param stamp the run stamp applied to every diagnostic record and echoed once; must not be
     *     {@code null}
     * @return always {@link BatchReturnCode#CLEAN} on completion. ⚠️ Assumptions: {@code RETURN-CODE}
     *     appears <b>nowhere</b> in all 487 lines of {@code app/cbl/CBIMPORT.cbl}, so the reference
     *     either ends normally or abends through {@code CALL 'CEE3ABD'} at
     *     {@code app/cbl/CBIMPORT.cbl:481-484}. Its only outcomes are zero and a hard failure, and the
     *     soft-warn tier is therefore unreachable here <b>by construction</b> rather than by omission:
     *     that tier originates solely at {@code app/cbl/CBTRN02C.cbl:229-230}, and a warn path added
     *     here speculatively would be rejected downstream as well. A run that skipped records because
     *     their discriminator was unrecognised still ends at zero, which is preserved and made
     *     conspicuous in the log rather than silently tolerated; raising the exit status for it would be
     *     a behavioural change needing a divergence record of its own
     * @throws IllegalStateException if the independently accumulated total disagrees with the sum of
     *     the per-type counters
     */
    static BatchReturnCode separate(
            BusinessDate businessDate, S3Client objectStore, String bucket, ImportStamp stamp) {

        try {
            return runImport(businessDate, objectStore, bucket, stamp);
        } catch (RuntimeException failure) {
            // WHY : Refactoring Rationale: the throwable is rendered through ThrowableDigest rather than
            //       attached whole. Attaching it put every message in the cause chain into a durable log,
            //       and the chains this path produces are provider and codec chains: a storage client's
            //       message can carry an endpoint and a bucket, and the shared codec's field diagnostics
            //       quote the value they refused -- which in this artefact is a primary account number, a
            //       national identifier or a card verification value. The digest carries the type names
            //       and the frames, which is what identifies the fault, and drops every message. The step
            //       above still receives the exception itself, so nothing is lost from the failure path;
            //       only the LOG line is reduced.
            // WHY : Assumptions: the banner text is preserved verbatim beside the digest, because it is
            //       what an operator greps for and app/cbl/CBIMPORT.cbl:480 writes it before abending.
            LOG.error("{} failure={}", LOG_ABENDING, ThrowableDigest.of(failure));
            throw failure;
        }
    }

    /**
     * Performs the four paragraphs of the reference in order, having been bracketed by its caller.
     *
     * <p>Assumptions: the four calls below stand one-to-one against
     * {@code app/cbl/CBIMPORT.cbl:167-170} — {@code 1000-INITIALIZE},
     * {@code 2000-PROCESS-EXPORT-FILE}, {@code 3000-VALIDATE-IMPORT} and {@code 4000-FINALIZE} — and
     * are kept as four named methods in that order rather than inlined, so the traceability matrix can
     * cite paragraph-to-method pairs and a reviewer can audit the flow by reading top to bottom.</p>
     *
     * @param businessDate the injected business date identifying the artefact partition; must not be
     *     {@code null}
     * @param objectStore the object store; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null} or blank
     * @param stamp the run stamp; must not be {@code null}
     * @return always {@link BatchReturnCode#CLEAN} on completion
     * @throws IllegalStateException if reconciliation fails
     * @throws UncheckedIOException if an artefact cannot be staged, written or published
     */
    private static BatchReturnCode runImport(
            BusinessDate businessDate, S3Client objectStore, String bucket, ImportStamp stamp) {

        // WHY : Refactoring Rationale: the six accumulators are now a CLOSEABLE resource held in a
        //       try-with-resources, because they are seven files on the task's ephemeral volume rather
        //       than seven heap buffers. The resource block is what guarantees they are released on every
        //       path, including the abending one -- a failed run that left seven files behind would leak
        //       both volume and, because those files hold account, card and customer records, disclosure.
        try (Outputs outputs = initialize(stamp)) {
            Tally tally = processExportFile(businessDate, objectStore, bucket, stamp, outputs);
            validateImport(tally);
            finalizeRun(objectStore, bucket, businessDate, outputs, tally);
            return BatchReturnCode.CLEAN;
        } catch (IOException failure) {
            // WHY : Assumptions: an input or output failure is translated to the unchecked form rather
            //       than declared, and it is translated HERE rather than at each write. The step contract
            //       this job is invoked through takes a body that throws nothing checked, and the abend
            //       bracket in the caller catches RuntimeException -- so translating at the one boundary
            //       keeps the banner, the tier translation and the ledger row all working exactly as they
            //       did when every accumulator was a heap buffer that could not fail.
            throw new UncheckedIOException("the import artefacts could not be assembled or published,"
                    + " so the run is abandoned rather than partially published", failure);
        }
    }

    /**
     * Re-expresses {@code 1000-INITIALIZE} at {@code app/cbl/CBIMPORT.cbl:174-193}.
     *
     * <p>Assumptions: the reference's {@code 1100-OPEN-FILES} at
     * {@code app/cbl/CBIMPORT.cbl:196-245} opens seven files and abends on any failure. The target has
     * no file handle to open, so the analogue is allocating the six accumulators the outputs are built
     * in — which is what {@link Outputs} does, for all six types including the card artefact D-4
     * discusses. Allocating them here rather than lazily preserves an observable property of the
     * reference: an artefact exists after the run whether or not the input held that record type,
     * because its data definition is {@code DISP=(NEW,CATLG,DELETE)}. A consumer distinguishing "no
     * records of this type" from "the import did not run" needs the empty artefact to exist.</p>
     *
     * @param stamp the run stamp whose date and time are echoed; must not be {@code null}
     * @return the six freshly staged output artefacts plus the diagnostic one; never {@code null}
     * @throws UncheckedIOException if a staging file cannot be created or opened
     */
    private static Outputs initialize(ImportStamp stamp) {
        LOG.info(LOG_STARTING);
        LOG.info("{}{}", LOG_IMPORT_DATE, stamp.importDate());
        LOG.info("{}{}", LOG_IMPORT_TIME, stamp.importTime());
        return new Outputs();
    }

    /**
     * Re-expresses {@code 2000-PROCESS-EXPORT-FILE} at {@code app/cbl/CBIMPORT.cbl:248-256} together
     * with the read at {@code :258-267}.
     *
     * <p>Assumptions: the reference's loop is a <b>correct</b> read-ahead and is reproduced as written
     * with nothing repaired. It primes one read, then repeats until end of file: count, dispatch, read
     * again. That ordering means end of file is detected before the record is used, unlike
     * {@code app/cbl/CBTRN01C.cbl}, which processes a stale record after end of file and whose migration
     * therefore does have something to fix. The loop below has the same three statements in the same
     * order.</p>
     *
     * <p>Refactoring Rationale: the artefact is <b>streamed one record at a time</b> rather than fetched
     * as a single byte array. The previous shape called {@code getObjectAsBytes} and held the whole
     * export dataset on the heap for the duration of the pass, and the export dataset is a full extract
     * of five masters at 500 bytes a record -- so peak memory scaled with the institution while the
     * task's memory is fixed at provisioning time, and a large enough extract would have exhausted it
     * before a single artefact was written. Reading through the response stream bounds the input side at
     * one record plus the read buffer, whatever the dataset size. Assumptions: nothing about the record
     * boundary changes, because these are {@code RECFM=FB} artefacts whose boundary IS the record length
     * -- so a whole record is obtained by reading exactly that many bytes, which is what
     * {@link InputStream#readNBytes(int)} guarantees short of end of data.</p>
     *
     * <p>Alternatives Considered: staging the fetched object to a temporary file and walking that, which
     * is what the outputs of this same job do. Rejected for the input because the input is walked exactly
     * once, front to back, and never re-read -- so a staging file would add a full copy of the dataset to
     * local storage and a second full traversal to obtain nothing the stream does not already give. The
     * outputs are staged precisely because they cannot be published until the pass ends, which is the
     * property the input does not share.</p>
     *
     * @param businessDate the injected business date identifying the artefact partition; must not be
     *     {@code null}
     * @param objectStore the object store the artefact is read from; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null} or blank
     * @param stamp the run stamp applied to every diagnostic record; must not be {@code null}
     * @param outputs the six accumulators the records are appended to; must not be {@code null}
     * @return the eight counters this pass accumulated; never {@code null}
     * @throws IllegalStateException if the artefact's length is not a whole multiple of the record
     *     length, if the body ends part-way through a record, or if it cannot be read
     * @throws IOException if the artefact cannot be read or a record cannot be staged
     * @throws IllegalStateException if the artefact's length is not a whole multiple of the record
     *     length, which means it was truncated in transit; raised so the pass fails before any artefact
     *     is published rather than publishing six files that describe a partly-read input
     */
    private static Tally processExportFile(
            BusinessDate businessDate,
            S3Client objectStore,
            String bucket,
            ImportStamp stamp,
            Outputs outputs) throws IOException {

        String sourceKey = EXPORT_KEY_PREFIX + businessDate.identifierPrefix() + EXPORT_MEMBER;
        int reclen = ExportRecordMapper.recordLayout().reclen();
        Tally tally = new Tally();
        long bytesRead = 0L;

        try (ResponseInputStream<GetObjectResponse> body = objectStore.getObject(
                        GetObjectRequest.builder().bucket(bucket).key(sourceKey).build());
                InputStream buffered = new BufferedInputStream(body, READ_BUFFER)) {

            while (true) {
                byte[] image = buffered.readNBytes(reclen);
                if (image.length == 0) {
                    break;
                }

                // WHY : Assumptions: a trailing image shorter than one record is an error and NOT an
                //       end-of-data marker. These artefacts are RECFM=FB, so their length is always a
                //       whole multiple of the record length; a remainder means the artefact was
                //       truncated, and ignoring it would report a partial import as a complete one. The
                //       reference cannot reach this branch at all -- see MSG_SHORT_RECORD -- so the
                //       branch is additive.
                // WHY : Refactoring Rationale: the remainder is now REFUSED rather than merely noted.
                //       The earlier shape recorded the short image and broke out of the loop, which let
                //       the run publish all six import artefacts and return a clean tier from a source
                //       it had only partly read -- six files that look complete and are not. The
                //       diagnostic row is still composed first, because it names the offset and the
                //       remaining byte count an operator needs, and the throw then leaves the step to
                //       translate the failure and the ledger to record it, so nothing is published.
                // WHY : Alternatives Considered: continuing and downgrading the return code to the warn
                //       tier was rejected -- a warn tier is the reject-count contract of the posting
                //       job, and reusing it here would make a damaged input indistinguishable from a
                //       clean run that merely rejected rows.
                if (image.length < reclen) {
                    recordShortImage(outputs, tally, stamp, bytesRead, image.length);
                    throw new IllegalStateException("the export artefact is truncated: " + reclen
                            + "-byte records were expected and " + image.length
                            + " bytes remain at offset " + bytesRead + ", so "
                            + tally.totalRecordsRead() + " whole records were read and the rest of the"
                            + " artefact is unreadable; no import artefact is published for a truncated"
                            + " input");
                }

                bytesRead += reclen;
                tally.countRead();
                processRecordByType(image, outputs, tally, stamp);
            }
        }

        LOG.info("event=batch.import.artefact-read sourceKey={} bytes={} records={}",
                sourceKey, bytesRead, tally.totalRecordsRead());
        return tally;
    }

    /**
     * Re-expresses {@code 2200-PROCESS-RECORD-BY-TYPE} at {@code app/cbl/CBIMPORT.cbl:269-285}.
     *
     * <p>⚠️ Assumptions: the five discriminators are {@code 'C'} customer, {@code 'A'} account,
     * {@code 'X'} cross-reference, {@code 'T'} transaction and <b>{@code 'D'} card</b>, with everything
     * else falling to the unknown-type handler. <b>Card is {@code 'D'} and not {@code 'C'}</b> —
     * {@code 'C'} is already customer — and this is the single easiest error to make across the two jobs
     * and the most expensive, because a wrong letter does not fail loudly: it routes every card record
     * to the unknown-type handler and yields an import that looks like it merely met unrecognised data.
     * The letters are not written in this file at all; they are read from {@link RecordType}, which
     * holds each one once for both the export and the import direction, so the round trip cannot drift.
     * The dispatch itself is delegated to {@link ExportRecordMapper#recordTypeOf}, whose own
     * documentation names this program's dispatcher as the caller it exists for.</p>
     *
     * <p>Assumptions: an unrecognised discriminator arrives here as a thrown exception rather than as a
     * null, because the mapper deliberately fails closed rather than defaulting to any view — a
     * permissive default would read 460 bytes under the wrong geometry and every field it produced would
     * be the right shape and the wrong value. Catching it is therefore the {@code WHEN OTHER} arm, and
     * the catch is narrowed to the mapper's own exception type so a genuine decode fault in a
     * recognised view is not silently reclassified as an unknown type.</p>
     *
     * @param image one complete record image, exactly {@code reclen} bytes; must not be {@code null}
     * @param outputs the six accumulators; must not be {@code null}
     * @param tally the counters to increment; must not be {@code null}
     * @param stamp the run stamp applied to a diagnostic record; must not be {@code null}
     * @throws IOException if a translated record cannot be appended to its staged artefact
     */
    private static void processRecordByType(
            byte[] image, Outputs outputs, Tally tally, ImportStamp stamp) throws IOException {

        RecordType recordType;
        try {
            recordType = ExportRecordMapper.recordTypeOf(image);
        } catch (ExportRecordMapper.ExportRecordException unrecognised) {
            recordUnknownType(image, outputs, tally, stamp);
            return;
        }

        // WHY : Assumptions: a record whose discriminator is recognised but whose payload will not
        //       decode under that view is a DIFFERENT fault from an unrecognised discriminator, and it
        //       is allowed to propagate to the abend bracket rather than being written to the diagnostic
        //       artefact. The reference draws the same line: its WHEN OTHER arm at
        //       app/cbl/CBIMPORT.cbl:283-284 handles only the unrecognised type, while every failed
        //       write inside a recognised branch abends. Routing a malformed payload to the diagnostic
        //       artefact instead would let a corrupt input produce an artefact that claims to hold
        //       verified records.
        byte[] record = translate(recordType, image);
        DatasetPayloadWriter.append(outputs.forType(recordType), record);
        tally.countImported(recordType);
    }

    /**
     * Re-expresses one of the five mapping blocks: clears a target record area and moves the view's
     * fields into it.
     *
     * <p>⚠️ Assumptions: <b>{@code INITIALIZE} is load-bearing and is reproduced structurally.</b> Every
     * mapping block in the reference begins with {@code INITIALIZE} of its target record —
     * {@code app/cbl/CBIMPORT.cbl:290}, {@code :325}, {@code :354}, {@code :374} and {@code :404} — which
     * in COBOL sets alphanumeric fields to spaces and numeric fields to zero <b>before</b> the field
     * moves, so any target field the view does not carry lands blank or zero rather than as residue from
     * the previous record. Omitting that is a silent corruption, not a visible failure. The target
     * reproduces it in three ways that together make residue unrepresentable: the shared encoder
     * allocates a <b>fresh</b> record array on every call, so no buffer is reused between records; it
     * requires <b>every</b> named field of the layout to be supplied and throws when one is missing, so
     * a field cannot be left at whatever the array happened to hold; and it blank-fills the trailing pad
     * it is not given. The start-up check in {@link #artefacts()} is what guarantees the middle
     * condition can always be met, by proving each mapping names every named field of its target.</p>
     *
     * <p>Assumptions: the decode is delegated entirely, and this method touches no byte itself.
     * {@code app/cpy/CVEXPORT.cpy} assigns storage <b>per field</b>, so one picture clause occupies
     * different widths in one view — the account view alone carries five {@code PIC S9(10)V99} fields as
     * packed at {@code :50}, display at {@code :51}, packed at {@code :52}, display at {@code :56} and
     * plain binary at {@code :57}, and all five land in a single zoned regime in the target master. The
     * geometry comes from {@link ExportRecordMapper#viewLayout} and the target geometry from the shared
     * registry, so no offset, width or storage kind is written in this file and no packed or binary field
     * is decoded here by hand. Money crosses as exact fixed point throughout: the codec yields a decimal
     * at scale two and the encoder consumes one, so no binary floating-point type appears in this class
     * and none may be introduced. The parity suite records that the wrong sign convention silently
     * corrupts negative balances, which is precisely why this translation is a rename between two
     * descriptors and not an arithmetic step.</p>
     *
     * @param recordType the view the image is read as; must not be {@code null}
     * @param image one complete record image; must not be {@code null}
     * @return the target artefact record, exactly the declared width of that type's layout; never
     *     {@code null}
     */
    private static byte[] translate(RecordType recordType, byte[] image) {
        Artefact artefact = ARTEFACTS.get(recordType);

        CopybookLayout.FieldSpec payloadSpan =
                ExportRecordMapper.recordLayout().field(PAYLOAD_FIELD);
        byte[] payload = Arrays.copyOfRange(image, payloadSpan.start(), payloadSpan.end());

        Map<String, Object> view = FixedWidthCodec.decodeRecord(
                payload, ExportRecordMapper.viewLayout(recordType), ARTEFACT_CHARSET);

        Map<String, Object> target = new LinkedHashMap<>();
        artefact.fieldMoves().forEach((exportField, targetField) ->
                target.put(targetField, adaptToTargetKind(
                        requireMapped(view, exportField, recordType),
                        artefact.layout().field(targetField))));

        // WHY : Assumptions: the redactions are applied from the artefact's own declaration rather
        //       than read out of the decoded view, so a redacted field's source value is never bound
        //       to a name in this method and cannot reach the target map by any path. The encoder
        //       writes a fresh record, so a field supplied here is the only value that span receives.
        artefact.redactions().forEach((targetField, constant) ->
                target.put(targetField, adaptToTargetKind(
                        constant, artefact.layout().field(targetField))));

        return FixedWidthCodec.encodeRecord(target, artefact.layout(), ARTEFACT_CHARSET);
    }

    /**
     * Presents a decoded export value in the form the target field's storage kind accepts.
     *
     * <p>⚠️ Refactoring Rationale: this is the one place a value changes type on its way across, and it
     * exists because <b>five of the forty-nine carried field moves cross a storage boundary that COBOL
     * crosses for free.</b> The shared codec yields an exact decimal for a {@code COMP} or {@code COMP-3}
     * field
     * and an integral wrapper for a display numeric one, and its encoder for a display numeric field
     * accepts an integral wrapper and not a decimal. So an integer arriving as binary or packed and
     * leaving as display needs converting, whereas money arriving as packed or binary and leaving as
     * signed zoned does not, because that encoder accepts an exact decimal directly.</p>
     *
     * <p>Assumptions: the conversion is exactly what the reference's own {@code MOVE} statements do. A
     * COBOL move from a {@code COMP} or {@code COMP-3} integer into a {@code PIC 9(n)} display field is
     * a representation change performed by the compiler, and every one of the five is such a move:
     * {@code EXP-CUST-ID} at {@code app/cbl/CBIMPORT.cbl:293} and
     * {@code EXP-CUST-FICO-CREDIT-SCORE} at {@code :310} into the customer master's display fields,
     * {@code EXP-XREF-ACCT-ID} at {@code :359}, {@code EXP-TRAN-MERCHANT-ID} at {@code :383}, and
     * {@code EXP-CARD-ACCT-ID} at {@code :408}. The Java therefore performs the same change at the same
     * points rather than declining it. A sixth such move exists in the reference —
     * {@code EXP-CARD-CVV-CD} at {@code :409} — and is absent here because that field is redacted rather
     * than carried, and its replacement constant is already integral.</p>
     *
     * <p>Assumptions: the conversion is <b>exact and refuses to round</b>, which is why it is
     * {@code longValueExact} and not {@code longValue}. All five sources are declared with zero decimal
     * places, so a fractional value here would mean a field was read under the wrong geometry, and
     * truncating it silently is precisely the failure mode the migration plan's rule on fixed point
     * exists to prevent. <b>No money field is routed through this method at all</b> — every money pair
     * is decimal at both ends and passes through untouched — so no monetary value can be narrowed to a
     * {@code long} by this code.</p>
     *
     * <p>Alternatives Considered: converting in the mapping tables, by declaring a per-pair conversion
     * beside each field name. Rejected because it would put the same storage-kind knowledge in
     * forty-nine places when it is already declared once in each descriptor, and a table that stated it
     * again could disagree with the descriptor it was meant to describe. Driving the decision from the
     * target field's own declared kind means a descriptor change cannot leave a stale conversion
     * behind.</p>
     *
     * @param decoded the value the export view decoded to; must not be {@code null}
     * @param target the descriptor of the field it is being written into; must not be {@code null}
     * @return the value in a form {@code target}'s storage kind accepts; never {@code null}
     * @throws ArithmeticException if an exact decimal bound for a display numeric field carries a
     *     nonzero fractional part or exceeds the range of a {@code long}, either of which means the
     *     source was read under the wrong geometry
     */
    private static Object adaptToTargetKind(Object decoded, CopybookLayout.FieldSpec target) {
        if (target.kind() == CopybookLayout.Kind.UINT && decoded instanceof BigDecimal integral) {
            return integral.longValueExact();
        }
        return decoded;
    }

    /**
     * Reads one mapped field, refusing to carry an absent value into the target record.
     *
     * <p>Assumptions: this guard exists to protect the {@code INITIALIZE} invariant with a diagnostic
     * that names the field, rather than letting an absent value reach the encoder and surface as a type
     * complaint about the target field. Only a view's trailing pad is ever dropped during a decode, and
     * no mapping names a pad, so reaching this failure means a mapping and a view descriptor have
     * drifted apart.</p>
     *
     * @param view the decoded export view; must not be {@code null}
     * @param exportField the export field name the mapping names; must not be {@code null}
     * @param recordType the view being translated, named in the diagnostic; must not be {@code null}
     * @return the decoded value, never {@code null}
     * @throws IllegalStateException if the view carries no value under {@code exportField}
     */
    private static Object requireMapped(
            Map<String, Object> view, String exportField, RecordType recordType) {

        Object value = view.get(exportField);
        if (value == null) {
            throw new IllegalStateException("the " + recordType + " view of app/cpy/CVEXPORT.cpy"
                    + " carries no value for " + exportField + ", so the target record cannot be"
                    + " completed; a field mapping and a view descriptor have drifted apart");
        }
        return value;
    }

    /**
     * Re-expresses {@code 2700-PROCESS-UNKNOWN-RECORD} at {@code app/cbl/CBIMPORT.cbl:424-434}.
     *
     * <p>Assumptions: the four moves the reference makes are reproduced in order — the stamp, the
     * offending type character, the sequence number and the verbatim message — and the unknown-type
     * counter is incremented before the write, exactly as at {@code app/cbl/CBIMPORT.cbl:427}.</p>
     *
     * <p>⚠️ Trade-offs: the sequence number reaches the record <b>truncated</b>, reproducing the
     * baseline move of a nine-digit value into a seven-digit field, and the untruncated value is logged
     * beside it. That pairing is the whole mitigation: the fixed-width artefact keeps the layout that is
     * its contract, and the diagnostic that the truncation would otherwise destroy survives in the log
     * where no positional consumer depends on its width. See {@link #ERROR_SEQUENCE_WIDTH} for why
     * widening the field was rejected.</p>
     *
     * <p>Assumptions: the offending type character and the sequence number are safe to log, and the
     * record image is not. A record image carries a primary account number, a national identifier and,
     * for a card record, a verification value, and a log line is readable by every holder of log access.
     * Only the discriminator's code point and the sequence number are reported, which is what identifies
     * the record without disclosing it — and the code point rather than the character itself, because a
     * byte that is none of the five is frequently not printable.</p>
     *
     * @param image the record image whose discriminator was not recognised; must not be {@code null}
     * @param outputs the six accumulators, of which the diagnostic one is appended to; must not be
     *     {@code null}
     * @param tally the counters to increment; must not be {@code null}
     * @param stamp the run stamp written into the record; must not be {@code null}
     * @throws IOException if the diagnostic artefact cannot be staged
     */
    private static void recordUnknownType(
            byte[] image, Outputs outputs, Tally tally, ImportStamp stamp) throws IOException {

        tally.countUnknownType();

        long sequence = sequenceNumberOf(image);
        char discriminator = discriminatorOf(image);

        LOG.warn("event=batch.import.unknown-record-type discriminatorCodePoint={} sequenceNumber={}"
                        + " truncatedSequenceInArtefact={} reason={}",
                (int) discriminator, sequence, sequence % ERROR_SEQUENCE_MODULUS,
                MSG_UNKNOWN_RECORD_TYPE);

        writeError(outputs, tally, stamp, discriminator, sequence, MSG_UNKNOWN_RECORD_TYPE);
    }

    /**
     * Reports a trailing image shorter than one whole record.
     *
     * <p>Assumptions: this condition has no counterpart in the reference, because a {@code RECFM=FB}
     * dataset cannot hold a partial record — see {@link #MSG_SHORT_RECORD}. It is reported through the
     * same diagnostic artefact and the same 132-byte layout as an unknown type, so a consumer parses one
     * record shape rather than two, and it carries no type character because the image is too short for
     * its discriminator to be meaningful.</p>
     *
     * <p>⚠️ Assumptions: the diagnostic record this appends is <b>never published</b>, and that is
     * deliberate rather than wasteful. Its caller raises immediately afterwards, so the six accumulators
     * are discarded with the failed run; the record is still assembled because assembling it is what
     * proves the layout accepts the condition, and because the WARN line below carries the same three
     * facts to the operator through a channel the failure does not discard.</p>
     *
     * @param outputs the six accumulators; must not be {@code null}
     * @param tally the counters to increment; must not be {@code null}
     * @param stamp the run stamp written into the record; must not be {@code null}
     * @param offset the zero-based offset of the short image within the artefact; must not be negative
     * @param remaining the number of bytes the artefact held from {@code offset} onward; must be
     *     positive
     * @throws IOException if the diagnostic artefact cannot be staged
     */
    private static void recordShortImage(
            Outputs outputs, Tally tally, ImportStamp stamp, long offset, int remaining)
            throws IOException {

        LOG.warn("event=batch.import.short-record offset={} remaining={} expected={} reason={}",
                offset, remaining, ExportRecordMapper.recordLayout().reclen(), MSG_SHORT_RECORD);

        // WHY : Assumptions: the type character is a space rather than a guess. The image is shorter
        //       than one record, so nothing in it establishes which view it was meant to be, and
        //       reporting a discriminator read from a truncated image would assert a fact the bytes do
        //       not support.
        writeError(outputs, tally, stamp, ' ', offset, MSG_SHORT_RECORD);
    }

    /**
     * Re-expresses {@code 2750-WRITE-ERROR} at {@code app/cbl/CBIMPORT.cbl:436-446}.
     *
     * <p>⚠️ Assumptions: <b>this is the one write path in the program that does not abend.</b> The
     * reference displays a diagnostic when the write fails, at {@code app/cbl/CBIMPORT.cbl:441-444}, and
     * pointedly does <b>not</b> perform {@code 9999-ABEND-PROGRAM}, whereas every other write in the
     * program abends on failure. The asymmetry is deliberate and is preserved: a failure to record an
     * error must not terminate the import, because the run's real work is the five artefacts and losing
     * a diagnostic line is a smaller loss than losing them.</p>
     *
     * <p>⚠️ Assumptions: the counter is incremented <b>regardless of outcome</b>, at
     * {@code app/cbl/CBIMPORT.cbl:446}, which sits outside the failure test above it. It therefore counts
     * <b>write attempts and not successes</b>. That is reproduced exactly, and it is stated here because
     * it is what keeps the reconciliation in {@link #validateImport} honest: the attempt count is
     * reported but is deliberately not a term in the record-count identity, since a failed attempt wrote
     * no record.</p>
     *
     * @param outputs the six accumulators, of which the diagnostic one is appended to; must not be
     *     {@code null}
     * @param tally the counters to increment; must not be {@code null}
     * @param stamp the run stamp written into the record; must not be {@code null}
     * @param recordTypeCharacter the offending discriminator, or a space where none is meaningful
     * @param sequence the untruncated sequence number, truncated to the field width on the way in; must
     *     not be negative
     * @param message the diagnostic text, written into the 50-character message field; must not be
     *     {@code null}
     */
    private static void writeError(
            Outputs outputs,
            Tally tally,
            ImportStamp stamp,
            char recordTypeCharacter,
            long sequence,
            String message) {

        try {
            DatasetPayloadWriter.append(outputs.errors(),
                    errorRecord(stamp, recordTypeCharacter, sequence, message));
        } catch (RuntimeException writeFailure) {
            // WHY : Assumptions: the reference's own behaviour, not a lenient reading of it -- the
            //       failure is reported and the run continues. The exception is swallowed here and
            //       nowhere else in this class, so the abend bracket still catches every other fault.
            // WHY : Assumptions: one catch of the UNCHECKED form covers a staged-file failure as well as
            //       a heap one, because the append this guards wraps its IOException in an
            //       UncheckedIOException rather than declaring it. A checked arm here would not compile,
            //       and the property the arm exists for is unaffected: a failure to record a diagnostic
            //       must not terminate the import, which is the asymmetry app/cbl/CBIMPORT.cbl:441-444
            //       declines and the one this method exists to preserve.
            LOG.error("{}{}", LOG_ERROR_WRITE_FAILED, writeFailure.getClass().getSimpleName());
        }

        tally.countErrorWriteAttempt();
    }

    /**
     * Builds one diagnostic record in the layout of {@code WS-ERROR-RECORD} at
     * {@code app/cbl/CBIMPORT.cbl:152-160}.
     *
     * <p>Assumptions: the record is laid out here by concatenation rather than through a registered
     * descriptor because it has none — {@code app/cbl/CBIMPORT.cbl:106-109} declares the file record as
     * an unstructured {@code PIC X(132)} and the structure lives in working storage, so there is no
     * copybook for the shared registry to carry. Every span is character data, so no packed or binary
     * decoding arises and none is performed.</p>
     *
     * <p>Assumptions: the named fields occupy the first 87 bytes
     * ({@code 26 + 1 + 1 + 1 + 7 + 1 + 50}), the declared 43-byte pad follows, and the 2-byte shortfall
     * between the layout's 130 and the record's 132 follows that. All 45 trailing bytes are spaces —
     * the pad because {@code app/cbl/CBIMPORT.cbl:160} declares it {@code VALUE SPACES}, and the
     * shortfall because a COBOL move into a wider alphanumeric field space-pads. They are therefore
     * produced by one right-pad to {@link #ERROR_RECORD_LENGTH} rather than as two separate spans; the
     * arithmetic is written out on {@link #ERROR_RECORD_LENGTH} so that the collapse reads as understood
     * rather than as a field that was forgotten.</p>
     *
     * @param stamp the run stamp, whose full 26 characters fill the stamp field; must not be
     *     {@code null}
     * @param recordTypeCharacter the offending discriminator, or a space where none is meaningful
     * @param sequence the untruncated sequence number; must not be negative
     * @param message the diagnostic text; must not be {@code null}
     * @return exactly {@link #ERROR_RECORD_LENGTH} bytes, the final two of them spaces; never
     *     {@code null}
     * @throws IllegalStateException if the assembled record is not exactly
     *     {@link #ERROR_RECORD_LENGTH} bytes, which would mean a width constant and the layout have
     *     drifted apart
     */
    private static byte[] errorRecord(
            ImportStamp stamp, char recordTypeCharacter, long sequence, String message) {

        String assembled = stamp.timestamp()
                + ERROR_FIELD_SEPARATOR
                + recordTypeCharacter
                + ERROR_FIELD_SEPARATOR
                + zeroPad(sequence % ERROR_SEQUENCE_MODULUS, ERROR_SEQUENCE_WIDTH)
                + ERROR_FIELD_SEPARATOR
                + fitToWidth(message, ERROR_MESSAGE_WIDTH);

        byte[] record = fitToWidth(assembled, ERROR_RECORD_LENGTH).getBytes(ARTEFACT_CHARSET);
        if (record.length != ERROR_RECORD_LENGTH) {
            throw new IllegalStateException("a diagnostic record must be exactly "
                    + ERROR_RECORD_LENGTH + " bytes, as app/cbl/CBIMPORT.cbl:106-109 declares and"
                    + " app/jcl/CBIMPORT.jcl:56-60 allocates, but " + record.length
                    + " were assembled");
        }
        return record;
    }

    /**
     * Reads the discriminator character without interpreting the payload.
     *
     * <p>Assumptions: the field is read through the shared codec against the record descriptor rather
     * than by indexing byte zero, so the one place that knows where the discriminator sits stays the
     * descriptor. {@code app/cpy/CVEXPORT.cpy:10} declares it {@code PIC X(1)}, which admits any byte at
     * all, so no validation is applied here — this method is reached only when the character has already
     * been found not to name a view.</p>
     *
     * @param image one complete record image; must not be {@code null}
     * @return the single character held in {@code EXPORT-REC-TYPE}
     */
    private static char discriminatorOf(byte[] image) {
        CopybookLayout.FieldSpec field = ExportRecordMapper.recordLayout().field(DISCRIMINATOR_FIELD);
        return String.valueOf(FixedWidthCodec.decodeField(image, field, ARTEFACT_CHARSET)).charAt(0);
    }

    /**
     * Reads the sequence number from inside the record, never as a file key.
     *
     * <p>Assumptions: this is the D-1 ruling in code. The number is a four-byte binary field at
     * zero-based offset 27, decoded through the shared codec against the record descriptor, and it is
     * used only to identify a record in a diagnostic. Nothing in this class retrieves a record by it.
     * The decode is delegated because the field is {@code COMP}: reading four binary bytes as characters
     * would fail on any value whose high bit is set, and reading nine characters would run past the
     * field entirely.</p>
     *
     * @param image one complete record image; must not be {@code null}
     * @return the untruncated sequence number the record carries
     */
    private static long sequenceNumberOf(byte[] image) {
        CopybookLayout.FieldSpec field = ExportRecordMapper.recordLayout().field(SEQUENCE_FIELD);
        return ((Number) FixedWidthCodec.decodeField(image, field, ARTEFACT_CHARSET)).longValue();
    }

    /**
     * Re-expresses {@code 3000-VALIDATE-IMPORT} at {@code app/cbl/CBIMPORT.cbl:448-452}, made real.
     *
     * <p>This is divergence D-5 in code, and the class comment carries the argument. The reference
     * emits two unconditional lines and performs no check at all; this method performs the one check the
     * program's own counters support, reports the actual error and unknown-type counts, and emits the
     * clean-result line <b>only</b> when there is nothing to report. No checksum algorithm is invented,
     * because the baseline specifies none and a fabricated one presented as a migrated one would be the
     * undocumented non-obvious choice Rule 1 forbids.</p>
     *
     * <p>Assumptions: the identity checked is that the total read equals the sum of the five per-type
     * import counters <b>plus the unknown-type counter</b>, because every complete image the pass
     * counted was routed to exactly one of those six accumulators. The error-write counter is
     * deliberately <b>not</b> a term: it counts attempts rather than successes, and a short trailing
     * image produces an attempt without being a record that was read, so including it would make the
     * identity fail on inputs where nothing is wrong.</p>
     *
     * @param tally the counters this run accumulated; must not be {@code null}
     * @throws IllegalStateException if the total read disagrees with the sum of the six accumulators
     */
    private static void validateImport(Tally tally) {
        reconcile(tally);

        LOG.info(LOG_VALIDATION_COMPLETED);

        // WHY : Trade-offs: this is the one operator-visible behavioural change this file makes. The
        //       reference asserts a clean result unconditionally at app/cbl/CBIMPORT.cbl:452, even on a
        //       run that wrote error records, so the assertion carried no information. Gating it on the
        //       two counters makes the line mean what it says; the string itself is untouched, and the
        //       negative case is reported rather than left as silence, so an operator reading the log
        //       never has to infer the outcome from the absence of a line.
        if (tally.errorRecordsWritten() == 0L && tally.unknownRecordTypeCount() == 0L) {
            LOG.info(LOG_NO_VALIDATION_ERRORS);
        } else {
            LOG.warn("event=batch.import.validation-errors errorRecordsWritten={}"
                            + " unknownRecordTypes={} reason=the reference asserted a clean result"
                            + " unconditionally at app/cbl/CBIMPORT.cbl:452; divergence D-5 makes the"
                            + " assertion conditional so this run reports what it actually found",
                    tally.errorRecordsWritten(), tally.unknownRecordTypeCount());
        }
    }

    /**
     * Verifies the independently accumulated total against the sum of the six per-type accumulators.
     *
     * <p>Alternatives Considered: deriving the total by summing the six at the end. Rejected because it
     * is not what the reference does, and the difference is what makes this method a real check. The
     * reference increments its total once per record inside the loop at
     * {@code app/cbl/CBIMPORT.cbl:253} and increments a per-type counter separately inside each mapping
     * block, at {@code :320}, {@code :349}, {@code :369}, {@code :399} and {@code :422}, with the
     * unknown-type counter at {@code :427}. Accumulating both sides by separate statements preserves
     * that and means a disagreement is a detectable defect; a derived total cannot disagree with itself,
     * so it would report a consistency it never verified.</p>
     *
     * <p>Alternatives Considered: logging a mismatch and continuing. Rejected because a mismatch means
     * one increment was missed, which means the artefacts' record counts and the run's published
     * statistics describe different runs. For artefacts with no golden master, statistics that do not
     * describe the bytes are worse than a failure, because a consumer has no way to detect the
     * discrepancy. Failing before the artefacts are written keeps a provably inconsistent set out of the
     * store entirely.</p>
     *
     * @param tally the counters this run accumulated; must not be {@code null}
     * @throws IllegalStateException if the total read differs from the sum of the six accumulators
     */
    private static void reconcile(Tally tally) {
        long summed = tally.customerRecordsImported()
                + tally.accountRecordsImported()
                + tally.xrefRecordsImported()
                + tally.tranRecordsImported()
                + tally.cardRecordsImported()
                + tally.unknownRecordTypeCount();

        if (tally.totalRecordsRead() != summed) {
            throw new IllegalStateException("import statistics are inconsistent: the total records"
                    + " read " + tally.totalRecordsRead() + " does not equal the sum of the per-type"
                    + " counters " + summed + " (customers=" + tally.customerRecordsImported()
                    + ", accounts=" + tally.accountRecordsImported()
                    + ", xrefs=" + tally.xrefRecordsImported()
                    + ", transactions=" + tally.tranRecordsImported()
                    + ", cards=" + tally.cardRecordsImported()
                    + ", unknownTypes=" + tally.unknownRecordTypeCount()
                    + "); one increment of app/cbl/CBIMPORT.cbl's counters was missed");
        }
    }

    /**
     * Re-expresses {@code 4000-FINALIZE} at {@code app/cbl/CBIMPORT.cbl:454-478}.
     *
     * <p>Assumptions: the reference closes its seven files and only then emits its nine report lines, so
     * the artefacts are written here before any line is logged. Closing a sequential output is what makes
     * it readable by anything else, and the target's analogue of that is the put — so the order is
     * preserved rather than reversed for convenience, and a reader of the log knows that by the time the
     * first tally line appears the artefacts exist.</p>
     *
     * <p>Assumptions: all six artefacts are put, including any that is empty, and including the card
     * artefact divergence D-4 discusses. See {@link #initialize} for why an empty artefact must still
     * exist.</p>
     *
     * @param objectStore the object store the artefacts are written to; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null} or blank
     * @param businessDate the business date whose partition the artefacts are written under; must not be
     *     {@code null}
     * @param outputs the seven staged artefacts to close and put; must not be {@code null}
     * @param tally the counters to report; must not be {@code null}
     * @throws IOException if a staged artefact cannot be flushed and closed, which would mean it is short
     *     of the records appended to it
     */
    private static void finalizeRun(
            S3Client objectStore,
            String bucket,
            BusinessDate businessDate,
            Outputs outputs,
            Tally tally) throws IOException {

        String partition = IMPORT_KEY_PREFIX + businessDate.identifierPrefix();

        // WHY : Assumptions: every sink is closed before the FIRST put rather than each one before its
        //       own, so a close failure on the sixth artefact cannot happen after the first five have
        //       already been published. That ordering is what keeps the six a set: either all of them
        //       are complete on disk and all of them are put, or none is put at all.
        // WHY : Refactoring Rationale: the sinks are closed BEFORE the first put, which is the analogue
        //       of the reference's 4100-CLOSE-FILES at app/cbl/CBIMPORT.cbl:458-463 and is now
        //       load-bearing rather than merely faithful. Each artefact is a buffered stream over a staged
        //       file, so bytes still sitting in a buffer are not yet in the file -- publishing before the
        //       close would put a short artefact and lose whatever the last buffer held. A close failure
        //       therefore propagates rather than being swallowed: it means the staged artefact is short of
        //       the records appended to it, and publishing it anyway is the one outcome this method must
        //       not produce.
        outputs.closeSinks();

        for (RecordType recordType : RecordType.values()) {
            put(objectStore, bucket, partition + memberOf(recordType), outputs.pathFor(recordType));
        }
        put(objectStore, bucket, partition + ERROR_MEMBER, outputs.errorPath());

        LOG.info(LOG_COMPLETED);
        LOG.info("{}{}", LOG_TOTAL_READ, tally.totalRecordsRead());
        LOG.info("{}{}", LOG_CUSTOMERS_IMPORTED, tally.customerRecordsImported());
        LOG.info("{}{}", LOG_ACCOUNTS_IMPORTED, tally.accountRecordsImported());
        LOG.info("{}{}", LOG_XREFS_IMPORTED, tally.xrefRecordsImported());
        LOG.info("{}{}", LOG_TRANSACTIONS_IMPORTED, tally.tranRecordsImported());
        LOG.info("{}{}", LOG_CARDS_IMPORTED, tally.cardRecordsImported());
        LOG.info("{}{}", LOG_ERRORS_WRITTEN, tally.errorRecordsWritten());
        LOG.info("{}{}", LOG_UNKNOWN_TYPES, tally.unknownRecordTypeCount());

        // WHY : ⚠️ Assumptions: a run that skipped records still ends at zero, because RETURN-CODE
        //       appears nowhere in app/cbl/CBIMPORT.cbl and the unknown-type count feeds no exit status
        //       there. That is preserved, so the count is made CONSPICUOUS here rather than merely
        //       tallied on a line an operator may skim: raising the exit status for it would be a
        //       behavioural change requiring its own divergence record, and staying silent about it
        //       would leave a partial import indistinguishable from a complete one.
        if (tally.unknownRecordTypeCount() > 0L) {
            LOG.warn("event=batch.import.records-skipped unknownRecordTypes={} exitStatus={}"
                            + " reason=app/cbl/CBIMPORT.cbl sets no RETURN-CODE, so an import that"
                            + " skipped records still ends cleanly; the skipped records are reported"
                            + " in the diagnostic artefact and nowhere else",
                    tally.unknownRecordTypeCount(), BatchReturnCode.CLEAN.numericValue());
        }
    }

    /**
     * Puts one artefact at its key.
     *
     * <p>Assumptions: the body is the staging FILE rather than a byte array, so the artefact is never
     * resident in the heap and is never copied into a request body. An empty file is put as an empty
     * object, which is what an artefact for a record type the input did not hold has to be.</p>
     *
     * @param objectStore the object store; must not be {@code null}
     * @param bucket the dataset bucket; must not be {@code null} or blank
     * @param key the object key; must not be {@code null}
     * @param staged the staged file holding the artefact body, possibly empty; must not be {@code null}
     */
    private static void put(S3Client objectStore, String bucket, String key, Path staged) {
        // WHY : Refactoring Rationale: the body is supplied from the staged FILE rather than from a byte
        //       array. Reading the file into an array to hand it over would reinstate on the heap exactly
        //       the whole-artefact copy that staging exists to avoid, and the request body form that takes
        //       a path streams the file to the store while reporting its length up front -- so the put
        //       remains a single request with a known content length, which is what keeps it
        //       all-or-nothing.
        objectStore.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(CONTENT_TYPE)
                        .build(),
                RequestBody.fromFile(staged));
        LOG.debug("event=batch.import.artefact-written key={} bytes={}",
                key, DatasetPayloadWriter.stagedLength(staged));
    }

    /**
     * Derives one artefact's member name from its record type.
     *
     * <p>Assumptions: the name is derived from the enumeration constant rather than listed, so a sixth
     * record type could not acquire an artefact without a name, and the root locale is named explicitly
     * because a default locale can lower-case differently and an object key is not a display string.</p>
     *
     * @param recordType the record type whose artefact is being named; must not be {@code null}
     * @return the member name, with a leading separator, for example {@code /card_xref.dat}
     */
    private static String memberOf(RecordType recordType) {
        return "/" + memberStem(recordType) + ".dat";
    }

    /**
     * Derives one artefact's bare name, without a separator or an extension.
     *
     * <p>Refactoring Rationale: the stem is factored out of {@link #memberOf} because it is now needed in
     * two places -- the object key and the staging filename. Deriving both from one expression is what
     * keeps a staged file attributable to the artefact it will become; two independent spellings would let
     * an operator holding a surviving staging file be unable to say which artefact it belonged to.</p>
     *
     * @param recordType the record type whose artefact is being named; must not be {@code null}
     * @return the bare name, for example {@code card_xref}
     */
    private static String memberStem(RecordType recordType) {
        // WHY : Assumptions: the root locale is named explicitly, because a default locale can lower-case
        //       differently and an object key is not a display string.
        return recordType.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Renders a value as fixed-width decimal digits, zero-padded on the left.
     *
     * <p>Assumptions: left zero-padding is what a COBOL numeric display field holds, as distinct from
     * the right blank-padding an alphanumeric field holds; the two are not interchangeable and a
     * consumer reading the sequence positionally would parse blanks as a malformed number.</p>
     *
     * @param value the value to render; must not be negative
     * @param width the exact number of digits to produce; must be positive
     * @return exactly {@code width} decimal digits; never {@code null}
     */
    private static String zeroPad(long value, int width) {
        String digits = Long.toString(value);
        return "0".repeat(Math.max(0, width - digits.length())) + digits;
    }

    /**
     * Renders text at an exact width, blank-padding on the right and truncating on the right.
     *
     * <p>Assumptions: this is what a COBOL move into an alphanumeric field does — it left-justifies,
     * pads with spaces and truncates any excess from the right — so both directions are reproduced
     * rather than only the padding one. Truncation applies to the diagnostic messages alone, both of
     * which are shorter than the 50-character field, so it is a guarantee about the record's width
     * rather than a behaviour any current caller exercises.</p>
     *
     * @param text the text to fit; must not be {@code null}
     * @param width the exact number of characters to produce; must be positive
     * @return exactly {@code width} characters; never {@code null}
     */
    private static String fitToWidth(String text, int width) {
        if (text.length() >= width) {
            return text.substring(0, width);
        }
        return text + " ".repeat(width - text.length());
    }

    /**
     * Declares the five per-type mappings and proves each one covers its whole target record.
     *
     * <p>Assumptions: each mapping is written out field by field, in the order the reference moves them,
     * so a reviewer can audit it against the source block line by line. The pairs below stand against
     * {@code app/cbl/CBIMPORT.cbl:293-310} for customer, {@code :328-339} for account, {@code :357-359}
     * for cross-reference, {@code :377-389} for transaction and {@code :407-412} for card — eighteen,
     * twelve, three, thirteen and six moves respectively.</p>
     *
     * <p>Assumptions: the target layouts are taken from the shared registry by name rather than declared
     * here, so the five record lengths and every field offset within them are stated in exactly one
     * place in the codebase. The registry's entries are transcribed from the same copybooks the
     * reference's file descriptions copy in at {@code app/cbl/CBIMPORT.cbl:81-104}, which is what makes
     * the two ends of each mapping agree by construction rather than by inspection.</p>
     *
     * @return the five mappings, keyed by record type; never {@code null}
     * @throws IllegalStateException if any mapping does not name exactly the named fields of its target
     *     layout, or if the five mappings do not cover all five record types
     */
    private static Map<RecordType, Artefact> artefacts() {
        Map<RecordType, Artefact> declared = new EnumMap<>(RecordType.class);

        // ---- 'C' -> the 500-byte customer artefact, app/cbl/CBIMPORT.cbl:288-320 ------------------
        // WHY : ⚠️ Refactoring Rationale: the OCCURS flattening. app/cpy/CVEXPORT.cpy:29-30 stores the
        //       three address lines as EXP-CUST-ADDR-LINES OCCURS 3 TIMES and :34-35 stores the two
        //       phone numbers as EXP-CUST-PHONE-NUMS OCCURS 2 TIMES, while app/cpy/CVCUS01Y.cpy:9-11
        //       and :15-16 store them as numbered scalars. The numbered scalars are kept, because the
        //       fixed arity of three and two is then enforced by the record layout itself rather than by
        //       a runtime length check on an array. The index-to-suffix correspondence below is
        //       therefore exact and load-bearing: an off-by-one here swaps a customer's address lines
        //       silently, producing a record of the right width with the wrong content, and the
        //       reference's own moves at app/cbl/CBIMPORT.cbl:297-299 and :303-304 are the authority for
        //       the pairing.
        // WHY : ⚠️ Refactoring Rationale: two spans are REDACTED rather than carried -- the national
        //       identifier the reference moves at app/cbl/CBIMPORT.cbl:305 and the government-issued
        //       identifier at :306. The transcription was faithful; the destination is not. This
        //       artefact is a durable object in the dataset bucket, and the migration plan holds that
        //       both identifiers are stored encrypted and returned masked, so carrying them in clear
        //       into an object store would be the single place that rule did not hold. They are supplied
        //       as constants below, which keeps every following field at its declared offset while
        //       carrying no value, and the source values are never bound to a name in this class.
        // WHY : Assumptions: this costs nothing on a round trip through this system, because the export
        //       job redacts the same three spans on the way out -- it holds no key grant to decrypt
        //       them -- so an artefact this job produced already carries zeros there. The redaction
        //       matters for an artefact produced elsewhere, which is exactly the case where the values
        //       would be real.
        declared.put(RecordType.CUSTOMER, new Artefact(
                CopybookLayout.layout("CUSTOMER"),
                moves(
                        "EXP-CUST-ID", "CUST-ID",
                        "EXP-CUST-FIRST-NAME", "CUST-FIRST-NAME",
                        "EXP-CUST-MIDDLE-NAME", "CUST-MIDDLE-NAME",
                        "EXP-CUST-LAST-NAME", "CUST-LAST-NAME",
                        "EXP-CUST-ADDR-LINE(1)", "CUST-ADDR-LINE-1",
                        "EXP-CUST-ADDR-LINE(2)", "CUST-ADDR-LINE-2",
                        "EXP-CUST-ADDR-LINE(3)", "CUST-ADDR-LINE-3",
                        "EXP-CUST-ADDR-STATE-CD", "CUST-ADDR-STATE-CD",
                        "EXP-CUST-ADDR-COUNTRY-CD", "CUST-ADDR-COUNTRY-CD",
                        "EXP-CUST-ADDR-ZIP", "CUST-ADDR-ZIP",
                        "EXP-CUST-PHONE-NUM(1)", "CUST-PHONE-NUM-1",
                        "EXP-CUST-PHONE-NUM(2)", "CUST-PHONE-NUM-2",
                        "EXP-CUST-DOB-YYYY-MM-DD", "CUST-DOB-YYYY-MM-DD",
                        "EXP-CUST-EFT-ACCOUNT-ID", "CUST-EFT-ACCOUNT-ID",
                        "EXP-CUST-PRI-CARD-HOLDER-IND", "CUST-PRI-CARD-HOLDER-IND",
                        "EXP-CUST-FICO-CREDIT-SCORE", "CUST-FICO-CREDIT-SCORE"),
                Map.of(
                        "CUST-SSN", REDACTED_NUMERIC_IDENTIFIER,
                        "CUST-GOVT-ISSUED-ID", REDACTED_TEXT_IDENTIFIER)));

        // ---- 'A' -> the 300-byte account artefact, app/cbl/CBIMPORT.cbl:323-349 -------------------
        // WHY : Assumptions: this is the view where money crosses three storage regimes into one, and
        //       the mapping is a rename precisely so that no arithmetic happens here. The balance and
        //       the cash credit limit arrive PACKED (app/cpy/CVEXPORT.cpy:50,52), the credit limit and
        //       the cycle credit arrive ZONED (:51,:56) and the cycle debit arrives plain BINARY (:57),
        //       while app/cpy/CVACT01Y.cpy:7-14 holds all five as signed zoned display. The shared
        //       codec performs every one of those conversions; this table only says which field becomes
        //       which. Both spellings of the misspelled expiration date are the baseline's own -- the
        //       view spells it EXP-ACCT-EXPIRAION-DATE at :54 and the master spells it
        //       ACCT-EXPIRAION-DATE at CVACT01Y:11 -- so the pair below carries the misspelling on both
        //       sides and no rename to the target column's correct spelling happens in this file.
        declared.put(RecordType.ACCOUNT, new Artefact(
                CopybookLayout.layout("ACCOUNT"),
                moves(
                        "EXP-ACCT-ID", "ACCT-ID",
                        "EXP-ACCT-ACTIVE-STATUS", "ACCT-ACTIVE-STATUS",
                        "EXP-ACCT-CURR-BAL", "ACCT-CURR-BAL",
                        "EXP-ACCT-CREDIT-LIMIT", "ACCT-CREDIT-LIMIT",
                        "EXP-ACCT-CASH-CREDIT-LIMIT", "ACCT-CASH-CREDIT-LIMIT",
                        "EXP-ACCT-OPEN-DATE", "ACCT-OPEN-DATE",
                        "EXP-ACCT-EXPIRAION-DATE", "ACCT-EXPIRAION-DATE",
                        "EXP-ACCT-REISSUE-DATE", "ACCT-REISSUE-DATE",
                        "EXP-ACCT-CURR-CYC-CREDIT", "ACCT-CURR-CYC-CREDIT",
                        "EXP-ACCT-CURR-CYC-DEBIT", "ACCT-CURR-CYC-DEBIT",
                        "EXP-ACCT-ADDR-ZIP", "ACCT-ADDR-ZIP",
                        "EXP-ACCT-GROUP-ID", "ACCT-GROUP-ID")));

        // ---- 'X' -> the 50-byte cross-reference artefact, app/cbl/CBIMPORT.cbl:352-369 ------------
        declared.put(RecordType.CARD_XREF, new Artefact(
                CopybookLayout.layout("XREF"),
                moves(
                        "EXP-XREF-CARD-NUM", "XREF-CARD-NUM",
                        "EXP-XREF-CUST-ID", "XREF-CUST-ID",
                        "EXP-XREF-ACCT-ID", "XREF-ACCT-ID")));

        // ---- 'T' -> the 350-byte transaction artefact, app/cbl/CBIMPORT.cbl:372-399 ---------------
        // WHY : ⚠️⚠️ Assumptions: BOTH timestamps are verbatim passthroughs here, which is the OPPOSITE
        //       of the rule everywhere else in this package. app/cbl/CBIMPORT.cbl:388 and :389 are two
        //       plain moves -- EXP-TRAN-ORIG-TS to TRAN-ORIG-TS and EXP-TRAN-PROC-TS to TRAN-PROC-TS --
        //       with no clock read and no reformatting of either. PostTransactionsJob and
        //       CalculateInterestJob GENERATE their processing stamp, so a reader who carries that rule
        //       across to this file would overwrite a value that was already correct and would corrupt
        //       the round trip the migration plan asks for. The contrast is stated because the package's
        //       other rule is the generated one and this is the exception. Both fields are 26 bytes of
        //       character data at both ends, so the rename below copies them byte for byte; this is also
        //       why the translation deliberately does NOT route the transaction view through an entity,
        //       whose timestamp property would re-render the value into the target form and break the
        //       byte identity.
        declared.put(RecordType.TRANSACTION, new Artefact(
                CopybookLayout.layout("TRAN"),
                moves(
                        "EXP-TRAN-ID", "TRAN-ID",
                        "EXP-TRAN-TYPE-CD", "TRAN-TYPE-CD",
                        "EXP-TRAN-CAT-CD", "TRAN-CAT-CD",
                        "EXP-TRAN-SOURCE", "TRAN-SOURCE",
                        "EXP-TRAN-DESC", "TRAN-DESC",
                        "EXP-TRAN-AMT", "TRAN-AMT",
                        "EXP-TRAN-MERCHANT-ID", "TRAN-MERCHANT-ID",
                        "EXP-TRAN-MERCHANT-NAME", "TRAN-MERCHANT-NAME",
                        "EXP-TRAN-MERCHANT-CITY", "TRAN-MERCHANT-CITY",
                        "EXP-TRAN-MERCHANT-ZIP", "TRAN-MERCHANT-ZIP",
                        "EXP-TRAN-CARD-NUM", "TRAN-CARD-NUM",
                        "EXP-TRAN-ORIG-TS", "TRAN-ORIG-TS",
                        "EXP-TRAN-PROC-TS", "TRAN-PROC-TS")));

        // ---- 'D' -> the 150-byte card artefact, app/cbl/CBIMPORT.cbl:402-422 ----------------------
        // WHY : ⚠️ Refactoring Rationale: the card verification value is NOT carried into this
        //       artefact, where an earlier revision moved it across verbatim from
        //       app/cbl/CBIMPORT.cbl:409. What was wrong with that is not the transcription, which was
        //       faithful, but the destination: the artefact is a durable object in the dataset bucket,
        //       so the run left a verification value at rest in an object store after authorisation,
        //       under no retention rule, no access control of its own and no deletion control.
        //       Encryption at rest does not answer that -- storing sensitive authentication data after
        //       authorisation is prohibited whether or not the storage is encrypted. The field is
        //       therefore REDACTED: the mapping still covers it, because the target layout declares it
        //       and the encoder requires every named field, but the value written is a constant zero
        //       and the source value is discarded at the boundary rather than carried.
        // WHY : Alternatives Considered: tokenising the value, so a consumer could correlate two
        //       records of one card without holding the value. Rejected because a card verification
        //       value has three digits, so any token derived from it is reversible by exhaustion in a
        //       thousand attempts and a token would therefore be the value in a longer form.
        //       Alternatives Considered: keeping the value and documenting an issuer exception under
        //       PCI SSC FAQ 1588, which permits an issuer to store sensitive authentication data where
        //       there is a documented business need. Rejected because no business need exists here --
        //       nothing downstream of this split reads that field -- and the exception would oblige
        //       this project to implement and evidence purpose-bound access, retention and irreversible
        //       deletion for one artefact of one operator-invoked job.
        // WHY : Assumptions: the redaction is registered as divergence
        //       D-EXPORT-PROTECTED-SPANS-REDACTED in
        //       docs/architecture/cobol-to-service-traceability.md, together with the counterpart
        //       redaction the export job applies to the same field and to the two customer
        //       identifiers, because they are one rule applied in both directions.
        declared.put(RecordType.CARD, new Artefact(
                CopybookLayout.layout("CARD"),
                moves(
                        "EXP-CARD-NUM", "CARD-NUM",
                        "EXP-CARD-ACCT-ID", "CARD-ACCT-ID",
                        "EXP-CARD-EMBOSSED-NAME", "CARD-EMBOSSED-NAME",
                        "EXP-CARD-EXPIRAION-DATE", "CARD-EXPIRAION-DATE",
                        "EXP-CARD-ACTIVE-STATUS", "CARD-ACTIVE-STATUS"),
                Map.of("CARD-CVV-CD", REDACTED_VERIFICATION_VALUE)));

        verifyMappingsAreComplete(declared);
        return Map.copyOf(declared);
    }

    /**
     * Proves at class initialisation that every mapping covers its whole target record.
     *
     * <p>⚠️ Assumptions: this check is what makes the {@code INITIALIZE} guarantee structural rather
     * than a matter of care. The shared encoder refuses to write a record when a named field is missing,
     * so an incomplete mapping would fail at run time on the first record of that type — after the
     * artefact had begun to be assembled, and only for a type the input happened to contain. Checking
     * every mapping against every target layout when the class loads moves that failure to start-up and
     * makes it independent of the input. The trailing pad is excluded from the comparison because no
     * mapping names one: the encoder blank-fills it, which is what the reference's
     * {@code INITIALIZE} does to the same bytes.</p>
     *
     * <p>⚠️ Refactoring Rationale: coverage is compared as a <b>sorted</b> list of the fields the two
     * maps supply between them, where an earlier revision compared the move table's target names to the
     * layout's declaration order directly. The direct comparison could not admit a redacted field: a
     * redaction supplies a field the layout declares at an interior position, and appending it after the
     * carried names would fail an order-sensitive comparison for a mapping that is in fact complete.
     * Sorting loses nothing that matters — list equality on sorted lists is multiset equality, so an
     * omitted field, an undeclared field and a field supplied twice each still fail. The authoring order
     * this stops proving is instead proved by the subsequence check below, so both properties survive as
     * two checks with two diagnostics rather than one check carrying both.</p>
     *
     * <p>Assumptions: the subsequence check asserts that the carried fields appear in the layout's own
     * declaration order, which is the property that lets a reviewer read a mapping table against the
     * COBOL block it transcribes. It is a subsequence and not an equality because a redacted field is
     * absent from the carried list by design.</p>
     *
     * <p>Alternatives Considered: asserting this in a unit test instead. Rejected as insufficient on its
     * own rather than wrong — a test proves it for the build that ran the test, whereas this proves it
     * for the process that is about to write artefacts, and the two mappings it compares are both static
     * so the check costs one pass at load time. The unit test remains worthwhile for the diagnostic it
     * gives; this is the guarantee.</p>
     *
     * @param declared the five mappings to verify; must not be {@code null}
     * @throws IllegalStateException if a record type is unmapped, if a mapping omits a named field of
     *     its target layout, if a mapping names a field the target layout does not declare, if one field
     *     is supplied twice, or if the carried fields are not written in the target layout's order
     */
    private static void verifyMappingsAreComplete(Map<RecordType, Artefact> declared) {
        for (RecordType recordType : RecordType.values()) {
            Artefact artefact = declared.get(recordType);
            if (artefact == null) {
                throw new IllegalStateException("record type " + recordType + " (discriminator '"
                        + recordType.discriminator() + "') has no artefact mapping, so a record the"
                        + " export contract can produce would be reported as an unknown type");
            }

            List<String> expected = artefact.layout().fields().stream()
                    .map(CopybookLayout.FieldSpec::name)
                    .filter(name -> !"FILLER".equals(name))
                    .toList();

            List<String> supplied = new ArrayList<>(artefact.fieldMoves().values());
            supplied.addAll(artefact.redactions().keySet());

            if (!expected.stream().sorted().toList().equals(supplied.stream().sorted().toList())) {
                throw new IllegalStateException("the " + recordType + " mapping does not cover"
                        + " layout " + artefact.layout().name() + " exactly: the layout declares "
                        + expected + " and the mapping supplies " + supplied + " between its carried"
                        + " fields and its redactions; every named field must be supplied exactly once"
                        + " because the encoder writes a fresh record and refuses to leave one unset,"
                        + " which is how app/cbl/CBIMPORT.cbl's INITIALIZE is reproduced");
            }

            requireLayoutOrder(recordType, artefact, expected);
        }
    }

    /**
     * Proves that one mapping's carried fields are written in its target layout's declaration order.
     *
     * <p>Purpose: to keep the property that made the original single equality check valuable as a review
     * aid — that a mapping table can be read line by line against the {@code MOVE} block it transcribes
     * — now that coverage is proved by an order-insensitive comparison.</p>
     *
     * <p>Assumptions: a subsequence rather than a prefix or an equality, because a redacted field is
     * declared by the layout but absent from the carried list, so the carried names are the layout's
     * order with zero or more entries removed. Walking the two lists once with a single advancing cursor
     * is what makes that check exact: a carried name that appears out of order cannot be found at or
     * after the cursor and therefore fails.</p>
     *
     * @param recordType the record type being verified, named in the diagnostic; must not be
     *     {@code null}
     * @param artefact the mapping being verified; must not be {@code null}
     * @param expected the target layout's named fields in declaration order; must not be {@code null}
     * @throws IllegalStateException if a carried target field does not appear at or after the position
     *     of the previously carried one
     */
    private static void requireLayoutOrder(
            RecordType recordType, Artefact artefact, List<String> expected) {

        int cursor = 0;
        for (String carried : artefact.fieldMoves().values()) {
            int position = expected.subList(cursor, expected.size()).indexOf(carried);
            if (position < 0) {
                throw new IllegalStateException("the " + recordType + " mapping writes "
                        + carried + " out of the declaration order of layout "
                        + artefact.layout().name() + ", which is " + expected + "; the mapping is"
                        + " written in the reference's own MOVE order so that it can be read against"
                        + " app/cbl/CBIMPORT.cbl line by line");
            }
            cursor += position + 1;
        }
    }

    /**
     * Builds one mapping from alternating export and target field names.
     *
     * <p>Assumptions: an insertion-ordered map is used rather than {@code Map.of}, because the order is
     * meaningful — it is the order the reference performs its moves, and
     * {@link #verifyMappingsAreComplete} compares the target names against the layout's declaration
     * order, so an unordered map would make that comparison depend on a hash. The alternating form keeps
     * each pair on one line, which is what lets a reviewer read the table against the COBOL block.</p>
     *
     * @param pairs alternating export and target field names, so an even number of entries; must not be
     *     {@code null}
     * @return the ordered mapping from export field name to target field name; never {@code null}
     * @throws IllegalStateException if {@code pairs} holds an odd number of entries, or if the same
     *     export field is named twice
     */
    private static Map<String, String> moves(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalStateException("a field mapping is written as alternating export and"
                    + " target names, so an even number of entries is required but " + pairs.length
                    + " were supplied");
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            if (mapping.put(pairs[index], pairs[index + 1]) != null) {
                throw new IllegalStateException("export field " + pairs[index] + " is mapped twice,"
                        + " so one of the two targets would silently win");
            }
        }
        return mapping;
    }

    /**
     * One record type's target layout paired with the field moves that populate it.
     *
     * <p>Purpose: to hold the two halves of one mapping block together, so that a target layout can
     * never be paired with another type's field moves. Binding them in one value is what lets
     * {@link #verifyMappingsAreComplete} check the pair rather than two lists that happen to be indexed
     * alike.</p>
     *
     * <p>Assumptions: a named field of the target layout is supplied by exactly one of the two maps —
     * carried from the export view through {@code fieldMoves}, or written as a constant through
     * {@code redactions} — and {@link #verifyMappingsAreComplete} proves that the two together cover
     * the layout with no field named twice. Splitting them rather than folding a constant into the
     * move table is deliberate: a reader can see at a glance which fields cross the boundary and which
     * are deliberately not carried, and the redaction cannot be mistaken for a transcription slip.</p>
     *
     * @param layout the target record descriptor from the shared registry, whose record length is the
     *     artefact's fixed record length; never {@code null}
     * @param fieldMoves the ordered mapping from export field name to target field name, holding one
     *     entry per carried field of {@code layout}; never {@code null}
     * @param redactions constants written into named fields of {@code layout} that are deliberately
     *     not carried from the export view, keyed by target field name; empty for every artefact that
     *     carries all of its fields; never {@code null}
     */
    private record Artefact(
            CopybookLayout.RecordSpec layout,
            Map<String, String> fieldMoves,
            Map<String, Object> redactions) {

        /**
         * Declares an artefact every named field of which is carried from the export view.
         *
         * <p>Assumptions: four of the five artefacts carry every field they declare, so this
         * convenience keeps their declarations reading as one mapping table rather than a mapping
         * table plus an empty map. The one artefact that redacts a field states that explicitly
         * through the canonical constructor, which is what makes the redaction visible at the
         * declaration site instead of implied by its absence here.</p>
         *
         * @param layout the target record descriptor from the shared registry; must not be
         *     {@code null}
         * @param fieldMoves the ordered mapping from export field name to target field name; must not
         *     be {@code null}
         */
        private Artefact(CopybookLayout.RecordSpec layout, Map<String, String> fieldMoves) {
            this(layout, fieldMoves, Map.of());
        }
    }

    /**
     * The six output accumulators one import run assembles, staged outside the heap.
     *
     * <p>Purpose: to stand for the six sequential files the reference opens at
     * {@code app/cbl/CBIMPORT.cbl:205-245} and closes at {@code :458-463}, so that a record is appended
     * to an artefact chosen by its record type rather than to a variable a caller selected by hand.</p>
     *
     * <p>Refactoring Rationale: each accumulator is a <b>staged file</b> where it used to be a
     * {@code ByteArrayOutputStream}. The six artefacts together hold every record of the export dataset
     * re-encoded into its target layout, so the heap shape held a second whole copy of the dataset beside
     * the input copy -- and then a third, because each accumulator's {@code toByteArray} produced a fresh
     * array for the put. Peak memory was therefore several multiples of the extract, against a task whose
     * memory is a fixed provisioning parameter. Staging bounds it at one record plus seven buffers. The
     * observable contract is unchanged: nothing is published until the pass has ended, and all six
     * artefacts are still published whether or not the input held that record type.</p>
     *
     * <p>Alternatives Considered: publishing each artefact incrementally with a multipart upload, which
     * needs no staging at all. Rejected because a failure partway through would leave artefacts that exist
     * and are short, and a consumer reading one cannot distinguish a short artefact from a complete one --
     * the same reason the sibling {@link DatasetPayloadWriter} gives for putting a generation in one
     * request. Staging locally and putting once preserves the all-or-nothing property that the reference's
     * {@code DISP=(NEW,CATLG,DELETE)} allocation gives its outputs.</p>
     *
     * <p>Assumptions: all seven staged files are created eagerly, so an artefact that receives no record
     * is published empty rather than omitted -- the reference's outputs exist after the run whichever
     * record types the input held, and a consumer distinguishing "no records of this type" from "the
     * import did not run" needs the empty artefact to exist. The per-type entries are held in a map keyed
     * by the enumeration rather than in five fields, so a sixth record type could not be routed to an
     * accumulator that does not exist.</p>
     */
    private static final class Outputs implements AutoCloseable {

        /** One staged artefact per record type, created eagerly so an unused artefact is still written. */
        private final Map<RecordType, Staged> byType = new EnumMap<>(RecordType.class);

        /** The staged diagnostic artefact, which has no record type to be keyed by. */
        private final Staged errors;

        /**
         * Creates one staged artefact per record type plus the staged diagnostic artefact.
         *
         * <p>Assumptions: the loop covers {@code RecordType.values()} rather than a written list, so the
         * set of artefacts and the set of record types cannot diverge. A failure partway through creation
         * discards whatever was already created before rethrowing, so a constructor that did not complete
         * leaves nothing behind -- a partially constructed resource cannot be handed to a
         * try-with-resources block, so it would otherwise never be closed.</p>
         *
         * @throws UncheckedIOException if a staging file cannot be created or opened
         */
        private Outputs() {
            try {
                for (RecordType recordType : RecordType.values()) {
                    this.byType.put(recordType, Staged.create(memberStem(recordType)));
                }
                this.errors = Staged.create(ERROR_STEM);
            } catch (RuntimeException unopened) {
                discardAll();
                throw unopened;
            }
        }

        /**
         * Returns the stream one record type's artefact is assembled in.
         *
         * @param recordType the record type whose artefact is wanted; must not be {@code null}
         * @return that type's staged sink; never {@code null}
         */
        private OutputStream forType(RecordType recordType) {
            return this.byType.get(recordType).sink();
        }

        /**
         * Returns the stream the diagnostic artefact is assembled in.
         *
         * @return the staged diagnostic sink; never {@code null}
         */
        private OutputStream errors() {
            return this.errors.sink();
        }

        /**
         * Flushes and closes all seven sinks, so the staged files are complete on disk.
         *
         * <p>Assumptions: this is the analogue of the reference's {@code 4100-CLOSE-FILES} at
         * {@code app/cbl/CBIMPORT.cbl:458-463}, and it is separate from {@link #close()} for the reason
         * that paragraph exists: closing an output is what makes it readable by anything else, so it must
         * happen before the puts and not after them. {@code close} then deletes.</p>
         *
         * @throws IOException if a sink cannot be flushed or closed, which would mean a staged artefact is
         *     short of the records appended to it
         */
        private void closeSinks() throws IOException {
            for (Staged staged : this.byType.values()) {
                staged.sink().close();
            }
            this.errors.sink().close();
        }

        /**
         * Returns the staged file one record type's artefact was assembled in.
         *
         * @param recordType the record type whose staged file is wanted; must not be {@code null}
         * @return that type's staged path; never {@code null}
         */
        private Path pathFor(RecordType recordType) {
            return this.byType.get(recordType).path();
        }

        /**
         * Returns the staged file the diagnostic artefact was assembled in.
         *
         * @return the staged diagnostic path; never {@code null}
         */
        private Path errorPath() {
            return this.errors.path();
        }

        /**
         * Closes every sink and removes every staged file.
         *
         * <p>Assumptions: closing is attempted before deleting and a close failure is swallowed here,
         * because by the time this runs the puts have either happened or been abandoned -- so a close
         * failure has nothing left to protect and must not replace the outcome the caller is being told
         * about. Closing an already-closed stream is a no-op, which is what lets the successful path close
         * the sinks before publishing and still reach this method afterwards.</p>
         */
        @Override
        public void close() {
            try {
                closeSinks();
            } catch (IOException ignored) {
                LOG.warn("event=batch.import.staging-not-closed failure={}",
                        ThrowableDigest.of(ignored));
            }
            discardAll();
        }

        /** Removes every staged file created so far, whichever of them exist. */
        private void discardAll() {
            List<Staged> created = new ArrayList<>(this.byType.values());
            if (this.errors != null) {
                created.add(this.errors);
            }
            for (Staged staged : created) {
                DatasetPayloadWriter.discard(staged.path());
            }
        }
    }

    /**
     * One staged artefact: the file it is assembled in and the buffered sink it is appended through.
     *
     * @param path the staging file, deleted when the run ends however it ends
     * @param sink the buffered stream records are appended through
     */
    private record Staged(Path path, OutputStream sink) {

        /**
         * Creates a staging file and opens a buffered sink over it.
         *
         * @param stem the filename stem naming the artefact, so a surviving file is attributable
         * @return the staged artefact; never {@code null}
         * @throws UncheckedIOException if the file cannot be created or opened
         */
        private static Staged create(String stem) {
            Path path = DatasetPayloadWriter.stage("import-" + stem);
            try {
                return new Staged(path,
                        new BufferedOutputStream(java.nio.file.Files.newOutputStream(path),
                                READ_BUFFER));
            } catch (IOException unopened) {
                // WHY : Assumptions: the file is discarded before the failure propagates, because
                //       DatasetPayloadWriter.stage has already CREATED it -- an empty file left behind by
                //       a failed open would never be reached by the resource block, which is only entered
                //       once construction succeeds.
                DatasetPayloadWriter.discard(path);
                throw new UncheckedIOException("the staged import artefact " + stem + " could not be"
                        + " opened for writing, so the run is abandoned", unopened);
            }
        }
    }

    /**
     * The eight counters {@code app/cbl/CBIMPORT.cbl:139-147} declares.
     *
     * <p>Purpose: to accumulate the eight statistics the reference reports, each independently, so that
     * {@link #reconcile} compares values produced by separate statements and can therefore detect a
     * missed increment.</p>
     *
     * <p>⚠️ Assumptions: <b>the eight are kept apart rather than derived from one another</b>, which is
     * the property the reconciliation depends on. The reference increments its total inside the read loop
     * and each per-type counter inside its own mapping block, so the two sides of the identity are
     * genuinely separate; folding them together, or deriving the total from the parts, would produce a
     * check that cannot fail. The names below carry the reference's own abbreviation for the transaction
     * counter — {@code WS-TRAN-RECORDS-IMPORTED} at {@code app/cbl/CBIMPORT.cbl:144} is spelled
     * {@code TRAN} where its report line at {@code :473} spells out {@code Transactions} — so the field
     * name and the log constant differ on purpose.</p>
     *
     * <p>Assumptions: this type is deliberately mutable and deliberately not thread-safe. One import run
     * is one pass over one artefact on one thread, which is what the reference's single sequential read
     * is; a concurrent pass would break the sequence ordering long before it broke a counter, so
     * synchronisation here would buy nothing and would imply a concurrency this job does not have.</p>
     */
    private static final class Tally {

        /** Records read, from {@code WS-TOTAL-RECORDS-READ} at {@code app/cbl/CBIMPORT.cbl:140}. */
        private long totalRecordsRead;

        /** Customers, from {@code WS-CUSTOMER-RECORDS-IMPORTED} at {@code :141}. */
        private long customerRecordsImported;

        /** Accounts, from {@code WS-ACCOUNT-RECORDS-IMPORTED} at {@code :142}. */
        private long accountRecordsImported;

        /** Cross-references, from {@code WS-XREF-RECORDS-IMPORTED} at {@code :143}. */
        private long xrefRecordsImported;

        /** Transactions, from {@code WS-TRAN-RECORDS-IMPORTED} at {@code :144}. */
        private long tranRecordsImported;

        /** Cards, from {@code WS-CARD-RECORDS-IMPORTED} at {@code :145}. */
        private long cardRecordsImported;

        /** Diagnostic write attempts, from {@code WS-ERROR-RECORDS-WRITTEN} at {@code :146}. */
        private long errorRecordsWritten;

        /** Unknown types, from {@code WS-UNKNOWN-RECORD-TYPE-COUNT} at {@code :147}. */
        private long unknownRecordTypeCount;

        /**
         * Counts one complete record image, as {@code app/cbl/CBIMPORT.cbl:253} does.
         */
        private void countRead() {
            this.totalRecordsRead++;
        }

        /**
         * Counts one imported record against its own type's counter.
         *
         * <p>Assumptions: the switch is exhaustive over the enumeration with no default arm, so adding a
         * sixth record type would fail to compile here rather than silently counting it nowhere. Each arm
         * increments a distinct field, which is what keeps the six accumulators independent.</p>
         *
         * @param recordType the type of the record just written; must not be {@code null}
         */
        private void countImported(RecordType recordType) {
            switch (recordType) {
                case CUSTOMER -> this.customerRecordsImported++;
                case ACCOUNT -> this.accountRecordsImported++;
                case CARD_XREF -> this.xrefRecordsImported++;
                case TRANSACTION -> this.tranRecordsImported++;
                case CARD -> this.cardRecordsImported++;
            }
        }

        /**
         * Counts one diagnostic write attempt, as {@code app/cbl/CBIMPORT.cbl:446} does.
         *
         * <p>⚠️ Assumptions: an attempt and not a success. The reference increments this counter outside
         * the test that reports a failed write, so a failed write still counts. See {@link #writeError}
         * for why the asymmetry is preserved.</p>
         */
        private void countErrorWriteAttempt() {
            this.errorRecordsWritten++;
        }

        /**
         * Counts one record whose discriminator named no view, as {@code app/cbl/CBIMPORT.cbl:427} does.
         */
        private void countUnknownType() {
            this.unknownRecordTypeCount++;
        }

        /**
         * Returns the number of complete record images read.
         *
         * @return the count, never negative
         */
        private long totalRecordsRead() {
            return this.totalRecordsRead;
        }

        /**
         * Returns the number of customer records written.
         *
         * @return the count, never negative
         */
        private long customerRecordsImported() {
            return this.customerRecordsImported;
        }

        /**
         * Returns the number of account records written.
         *
         * @return the count, never negative
         */
        private long accountRecordsImported() {
            return this.accountRecordsImported;
        }

        /**
         * Returns the number of cross-reference records written.
         *
         * @return the count, never negative
         */
        private long xrefRecordsImported() {
            return this.xrefRecordsImported;
        }

        /**
         * Returns the number of transaction records written.
         *
         * @return the count, never negative
         */
        private long tranRecordsImported() {
            return this.tranRecordsImported;
        }

        /**
         * Returns the number of card records written.
         *
         * @return the count, never negative
         */
        private long cardRecordsImported() {
            return this.cardRecordsImported;
        }

        /**
         * Returns the number of diagnostic write attempts, successful or not.
         *
         * @return the count, never negative
         */
        private long errorRecordsWritten() {
            return this.errorRecordsWritten;
        }

        /**
         * Returns the number of records whose discriminator named no view.
         *
         * @return the count, never negative
         */
        private long unknownRecordTypeCount() {
            return this.unknownRecordTypeCount;
        }
    }

    /**
     * The one stamp that is constant across every record of one import run.
     *
     * <p>Purpose: to carry the run stamp as one validated value and to be the <b>single place in this
     * class where a clock may be read</b>. Once constructed, the import path is a pure function of its
     * inputs, which is what lets two runs given the same stamp be compared byte for byte — the only
     * verification available for artefacts with no golden master.</p>
     *
     * <p>Refactoring Rationale: divergence D-8, the wall clock. The reference builds its date and time
     * from {@code FUNCTION CURRENT-DATE} at {@code app/cbl/CBIMPORT.cbl:178-188} and reads the clock a
     * second time for every diagnostic record at {@code app/cbl/CBIMPORT.cbl:429}, and its driver passes
     * no {@code PARM} at {@code app/jcl/CBIMPORT.jcl:22}, so a reference run is not reproducible even in
     * principle. What was wrong with the clock read: with no golden master for this stream, comparing two
     * target runs against each other is the only verification there is, and a clock read makes it
     * impossible by changing 26 bytes of every diagnostic record. This type accepts the stamp as
     * configuration and consults the clock only when none is supplied, and the one value it holds is used
     * for the two echoes <b>and</b> for every diagnostic record — so the second clock read at
     * {@code :429} disappears as well, which additionally makes every diagnostic record of one run share
     * a stamp and therefore be correlatable.</p>
     *
     * <p>Trade-offs: the fallback is retained rather than made an error, so an operator who supplies
     * nothing still gets a working import with the reference's own semantics. The cost is that such a run
     * is not reproducible, which is why {@link #resolve} warns about it rather than passing over it
     * silently.</p>
     *
     * @param timestamp the 26-character stamp echoed once and written into every diagnostic record;
     *     never {@code null}
     */
    record ImportStamp(String timestamp) {

        /**
         * Width of the date portion the reference echoes, from {@code app/cbl/CBIMPORT.cbl:135}.
         */
        private static final int DATE_LENGTH = 10;

        /**
         * Offset of the time portion within the 26-character contract form.
         *
         * <p>Assumptions: eleven, because the contract form is a ten-character date, one separator and
         * then the time. The sibling export job slices the same value at the same offset, and both derive
         * it from the formatter's own rendering rather than from a copybook overlay, so the two cannot
         * disagree.</p>
         */
        private static final int TIME_OFFSET = 11;

        /**
         * Width of the time portion the reference echoes, from {@code app/cbl/CBIMPORT.cbl:136}.
         *
         * <p>⚠️ Assumptions: eight, because {@code WS-IMPORT-TIME} is declared {@code PIC X(08)} and is
         * built as {@code HH:MM:SS} at {@code app/cbl/CBIMPORT.cbl:184-188}. Echoing more would append
         * fractional seconds the reference's own echo does not have.</p>
         */
        private static final int TIME_LENGTH = 8;

        /**
         * Rejects a stamp the diagnostic record layout cannot hold.
         *
         * <p>Assumptions: the width check belongs here rather than at the point of use, so a
         * misconfigured property is named while nothing has been written, instead of failing on the first
         * record that happens to be unrecognised — which for a clean artefact would be never, leaving the
         * misconfiguration latent. The width is taken from
         * {@link TimestampFormatter#TIMESTAMP_LENGTH} rather than written as 26, and it is the same width
         * {@code ERR-TIMESTAMP PIC X(26)} at {@code app/cbl/CBIMPORT.cbl:153} declares.</p>
         *
         * @param timestamp the stamp to validate, which must be exactly
         *     {@link TimestampFormatter#TIMESTAMP_LENGTH} characters because the diagnostic record
         *     reserves exactly that many bytes for it
         * @throws IllegalArgumentException if {@code timestamp} is {@code null} or is not exactly
         *     {@link TimestampFormatter#TIMESTAMP_LENGTH} characters
         */
        ImportStamp {
            if (timestamp == null) {
                throw new IllegalArgumentException(
                        "an import stamp requires a timestamp; there is no ambient clock to fall back"
                                + " to at this point, because the fallback happens in resolve");
            }
            if (timestamp.length() != TimestampFormatter.TIMESTAMP_LENGTH) {
                throw new IllegalArgumentException("carddemo.import.timestamp must be exactly "
                        + TimestampFormatter.TIMESTAMP_LENGTH
                        + " characters because ERR-TIMESTAMP is declared PIC X(26) at"
                        + " app/cbl/CBIMPORT.cbl:153, but " + timestamp.length()
                        + " were supplied");
            }
        }

        /**
         * Returns the date portion, in the shape {@code WS-IMPORT-DATE} holds.
         *
         * <p>Assumptions: ten characters from the front, which is what
         * {@code app/cbl/CBIMPORT.cbl:178-182} composes as {@code YYYY-MM-DD} into a
         * {@code PIC X(10)} field. The slice is taken through the formatter's own accessor rather than
         * by substring, so the one place that knows the contract form's shape stays the formatter.</p>
         *
         * @return the ten-character {@code YYYY-MM-DD} portion; never {@code null}
         */
        String importDate() {
            return TimestampFormatter.datePrefix(timestamp);
        }

        /**
         * Returns the time portion, in the shape {@code WS-IMPORT-TIME} holds.
         *
         * @return the eight-character {@code HH:MM:SS} portion; never {@code null}
         */
        String importTime() {
            return timestamp.substring(TIME_OFFSET, TIME_OFFSET + TIME_LENGTH);
        }

        /**
         * Resolves the configured stamp, falling back to the clock when none is supplied.
         *
         * @param suppliedTimestamp the configured stamp, or {@code null} or blank to derive one from
         *     {@code clock}
         * @param clock the fallback time source, consulted only when {@code suppliedTimestamp} is
         *     absent; must not be {@code null}
         * @return the resolved stamp; never {@code null}
         * @throws NullPointerException if {@code clock} is {@code null} and a fallback is required
         * @throws IllegalArgumentException if {@code suppliedTimestamp} is present but is not exactly
         *     {@link TimestampFormatter#TIMESTAMP_LENGTH} characters
         */
        static ImportStamp resolve(String suppliedTimestamp, Clock clock) {
            if (suppliedTimestamp != null && !suppliedTimestamp.isBlank()) {
                return new ImportStamp(suppliedTimestamp);
            }

            // WHY : Trade-offs: a warning rather than silence, because this is the branch that gives up
            //       the one verification this stream has -- see divergence D-8 on this record. The
            //       message names the property to set, so an operator who wants a reproducible artefact
            //       is told how without reading this file.
            LOG.warn("event=batch.import.timestamp-from-clock property=carddemo.import.timestamp"
                    + " reason=absent consequence=this run is not byte-reproducible; supply the"
                    + " property to make two runs comparable");
            return new ImportStamp(TimestampFormatter.formatNow(clock));
        }

        /**
         * Resolves the stamp with no override at all, matching a parameterless reference run.
         *
         * <p>Assumptions: this is the shape of a reference invocation —
         * {@code app/jcl/CBIMPORT.jcl:22} carries no {@code PARM}, so the baseline always takes the
         * clock. It exists so a caller wanting exactly that does not have to pass a blank and rediscover
         * what the absence means.</p>
         *
         * @param clock the time source the stamp is taken from; must not be {@code null}
         * @return the resolved stamp; never {@code null}
         * @throws NullPointerException if {@code clock} is {@code null}
         */
        static ImportStamp baseline(Clock clock) {
            return resolve(null, clock);
        }
    }
}
