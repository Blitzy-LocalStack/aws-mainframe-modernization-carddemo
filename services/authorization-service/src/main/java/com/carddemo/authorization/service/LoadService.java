package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.mapper.PendingAuthSummaryMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.PackedDecimalCodec;
import com.carddemo.common.observability.FailureSummary;
import com.carddemo.common.observability.ThrowableDigest;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bulk-loads pending-authorization summary and detail images from two extract files into the schema.
 *
 * <p><strong>Purpose.</strong> Carry across
 * {@code app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL}. Each significant paragraph becomes a named
 * method of this class, so the paragraph-to-method pairs in
 * {@code docs/architecture/cobol-to-service-traceability.md} can be read in both directions:
 *
 * <ul>
 *   <li>{@code MAIN-PARA} at <strong>L169 to L187</strong> becomes {@link #load(InputStream,
 *       InputStream)}. It performs two loops in sequence: the root loop at L177 to L178 and the child
 *       loop at L180 to L181.</li>
 *   <li>{@code 2000-READ-ROOT-SEG-FILE} at <strong>L222 to L237</strong> and
 *       {@code 2100-INSERT-ROOT-SEG} at <strong>L242 to L263</strong> become
 *       {@link #loadSummaries(InputStream)}.</li>
 *   <li>{@code 3000-READ-CHILD-SEG-FILE} at <strong>L269 to L289</strong>,
 *       {@code 3100-INSERT-CHILD-SEG} at <strong>L292 to L316</strong> and
 *       {@code 3200-INSERT-IMS-CALL} at <strong>L318 to L339</strong> become
 *       {@link #loadDetails(InputStream)}.</li>
 *   <li>{@code 1000-INITIALIZE} at <strong>L190 to L215</strong> and {@code 4000-FILE-CLOSE} at
 *       <strong>L341 to L356</strong> have no method of their own: both open and close the two files,
 *       and this class receives streams a caller already owns. {@code 9999-ABEND} at
 *       <strong>L360 to L369</strong>, which sets return code 16 at its L365, becomes a propagated
 *       exception.</li>
 * </ul>
 *
 * <p>Every citation is relative to that tree, which is reference material this migration reads and never
 * modifies. Its line numbers are read with columns 73 to 80 stripped, because that source carries
 * eight-digit legacy sequence numbers there.
 *
 * <p>Three divergences from that program are claimed by this class and are registered by identifier in
 * {@code docs/architecture/cobol-to-service-traceability.md}: <strong>D-C</strong>,
 * <strong>D-LOAD-PREFIX-REFUSED</strong> and <strong>D-LOAD-READ-BOUNDED</strong>. Each is described
 * below beside the code that causes it, and no behaviour differs from the reference program that is not
 * one of those three.
 *
 * <h2>Divergence D-C: an unresolvable parent is reported rather than passed over</h2>
 *
 * <p>Refactoring Rationale: <strong>D-C.</strong> A child record whose parent cannot be resolved is
 * refused here with the account identifier in the message, where the reference program neither inserts
 * the child nor reports it. This is a consequence of the nested-{@code IF} structure of
 * {@code 3100-INSERT-CHILD-SEG} rather than a stated behaviour, and the structure is worth setting out
 * because indentation does not reveal it. The parent-positioning call spans L296 to L299 and is
 * terminated by a <strong>period at L299</strong>, so from L300 onwards the indentation no longer tracks
 * scope. <strong>L305</strong>, {@code IF PAUT-PCB-STATUS = SPACES}, opens the branch taken when that
 * positioning SUCCEEDED. The insert follows at L309. The failure test at <strong>L310</strong>,
 * {@code IF PAUT-PCB-STATUS NOT EQUAL TO SPACES AND 'II'}, sits INSIDE that success branch, and one
 * {@code END-IF.} at <strong>L314</strong> closes both. A genuine positioning failure therefore makes
 * L305 false, and control passes from L305 directly to L315 without reaching either the insert or the
 * failure test.
 *
 * <p>Refactoring Rationale: two further readings of the same source establish D-C as a structural
 * consequence rather than an intended tolerance, and both are internal to the program so neither rests
 * on an outside judgement. First, the sibling ROOT path {@code 2100-INSERT-ROOT-SEG} is shaped
 * differently: <strong>L253 to L262</strong> are three FLAT tests, each closed by its own {@code END-IF}
 * -- success at L253 to L255, the duplicate status at L256 to L258, and everything else at L259 to L262,
 * which reaches the abend. The child insert at <strong>L326 to L336</strong> carries that same flat
 * three-way shape a second time. The nested shape at L305 to L314 is the only one of the three that
 * differs, inside one program. Second, L310's own body says what it was for: <strong>L311</strong> writes
 * {@code 'ROOT GU CALL FAIL:'} with the status and <strong>L312</strong> writes the key feedback area, so
 * the text and the diagnostic both describe reporting a positioning failure that the enclosing branch
 * prevents them from seeing.
 *
 * <p>Alternatives Considered for D-C: reproducing the fall-through, so that an unattributable child would
 * be counted and passed over exactly as the reference passes it over. Rejected because the two outcomes
 * are not equally recoverable. Passing over the record loses an authorization with no trace of which one:
 * the detail extract has rows the summary extract does not account for, and after the run neither the
 * target nor the log names them, so the only way to find out what was lost is to re-derive it from the
 * two files by hand. Refusing names the first such record, and carries its account in typed form on the
 * refusal for a caller entitled to read it, which is the difference between
 * one corrective pass -- load the missing summaries, re-run, and let the duplicate tolerance below skip
 * everything already in place -- and a silent shortfall discovered later from a balance that does not
 * agree. The refusal is safe to make loud precisely because the re-run is safe.
 *
 * <p>Assumptions for D-C: the relational form asserts the same parent dependency the hierarchical form
 * did, so this is a check the schema would make in any case.
 * {@code fk_pending_auth_detail_summary} in {@code db/migration/V1__authorization.sql} declares
 * {@code account_id} a foreign key onto {@code pending_auth_summary}. Leaving the check to the constraint
 * was considered and rejected: a constraint violation surfaces from a flush, names the constraint rather
 * than the extract record, and arrives at whatever point the provider chose to flush at, so it identifies
 * neither which record nor which account. The explicit check is what lets the failure carry both -- the
 * record ordinal in its message and the account on the refusal itself, the latter deliberately not written
 * to the log.
 *
 * <h2>Two adjacent conditions the reference source also passes over</h2>
 *
 * <p>Refactoring Rationale: <strong>D-LOAD-PREFIX-REFUSED.</strong> A child record whose six-byte prefix
 * does not decode is refused here, naming its ordinal position in the file.
 * {@code 3000-READ-CHILD-SEG-FILE} guards the insert with
 * {@code IF ROOT-SEG-KEY IS NUMERIC} at <strong>L275</strong> and supplies no {@code ELSE}, so a prefix
 * that fails that test takes the record out of the run with no message. Refusing instead is the same
 * argument as D-C with less to work with: an undecodable prefix cannot be attributed to any account at
 * all, so it cannot even be reported by key, and the ordinal is the only handle a reader has on it.
 * Continuing past it would consume a record that no later run has any way to identify as missing.
 *
 * <p>Refactoring Rationale: <strong>D-LOAD-READ-BOUNDED.</strong> Neither load loop here can fail to
 * terminate. Both reference read loops end
 * only when their file-status test sets an end flag -- {@code END-ROOT-SEG-FILE} at L233 and
 * {@code END-CHILD-SEG-FILE} at L285, each reached only from a status of {@code '10'} -- and each program
 * has one further branch that sets no flag and does not abend: <strong>L235</strong> for the root file
 * and <strong>L287</strong> for the child file, both of which write a message and return. Read against
 * {@code PERFORM ... UNTIL} at L178 and L181, a status that is neither success nor {@code '10'} leaves
 * both the flag and the file position unchanged, so the loop re-reads the same condition indefinitely.
 * The target has no continuation condition at all: {@link #records(InputStream, int, String)} resolves the
 * stream to a known number of whole records ONCE, and each pass is then a counted walk of that many
 * elements. A loop whose bound is settled before it starts, and whose body cannot extend it, terminates
 * whatever the input was; there is no status a record can carry that returns control to the top without
 * consuming an element.
 *
 * <h2>Idempotence at both levels</h2>
 *
 * <p>Assumptions: a record whose row is already present is counted and skipped -- never overwritten, and
 * never a failure -- and a condition that is anything else is raised. That is the reference program's own
 * two-way treatment, and it is stated at both levels rather than at one. On the ROOT,
 * <strong>L256 to L258</strong> report the duplicate status and continue while
 * <strong>L259 to L262</strong> reach the abend for every other status. On the CHILD,
 * <strong>L329 to L331</strong> and <strong>L332 to L336</strong> are the same pair. Both halves matter:
 * turning a duplicate into a failure would make a re-run of a partly completed load fail, and a re-run is
 * how an operator recovers one, since this program is the recovery tool for the extract
 * {@code UnloadService} writes. Swallowing anything else would let a load report success having stored
 * less than it read.
 *
 * <p>Alternatives Considered: writing each record unconditionally and letting the store resolve the
 * collision, whether by an insert that ignores a conflict or by an update. Rejected because the reference
 * behaviour on a duplicate is to leave the stored row exactly as it stands, and an update does not: it
 * would let a second run replace a row an operator had since amended through the online screens with
 * the older image the extract still carries. The skip is therefore reproduced rather than replaced, and
 * the count of skips is reported so a re-run is visibly a re-run rather than silently a no-op.
 *
 * <h2>What this class deliberately does not have</h2>
 *
 * <p>Assumptions: this takes NO job parameters, because the reference job supplies none. Neither load job
 * has a {@code SYSIN} DD -- across the module's five job streams only the purge job does -- and
 * {@code PRM-INFO}, declared at {@code cbl/PAUDBLOD.CBL} L129, is never referenced in its procedure
 * division. Inventing a parameter would publish a control this job never had.
 *
 * <p>Assumptions: no date is read and none is accepted. The two clock reads at
 * <strong>L193 to L194</strong> feed a single {@code DISPLAY} at L197 and are stored nowhere:
 * {@code CURRENT-YYDDD} is never referenced again, and {@code WS-AUTH-DATE},
 * {@code WS-EXPIRY-DAYS} and {@code WS-DAY-DIFF} are declared and never used. So there is no date to
 * parameterise here -- which is the same reproducibility position the purge reaches by taking its
 * business date as an argument, arrived at from the other direction. A clock read that fed a stored value
 * would have to become a parameter; one that feeds only a log line has nothing to carry across.
 *
 * <p>Assumptions: this runs CONCURRENTLY with the online workload and takes no lock to do so.
 * {@code jcl/LOADPADB.JCL} <strong>L26 to L27</strong> runs the program as
 * {@code PARM='BMP,PAUDBLOD,PSBPAUTB'}, a message-region job that shares the database with the online
 * region rather than holding it exclusively. A fifth discriminator in the same file confirms the reading:
 * its database DD statements at <strong>L40 to L41</strong> are COMMENTED OUT, because a job of that kind
 * reaches the database through the control region, whereas the batch-exclusive unload job carries the
 * same two statements ACTIVE at {@code jcl/UNLDPADB.JCL} L58 to L59 under
 * {@code PARM='DLI,PAUDBUNL,PAUTBUNL,,,,,,,,,,,N'}. No lock mode is declared here and no locking scheme
 * is introduced, because the reference program has none and the duplicate tolerance above is what makes
 * concurrent arrival of the same row safe.
 *
 * <p>Assumptions: no condition-code translation applies to this job, and the absence is stated because
 * the migration treats it as a headline hazard elsewhere. All five job streams in
 * {@code app/app-authorization-ims-db2-mq/jcl/} were read whole with columns 73 to 80 stripped: there is
 * no {@code COND=} of either form -- neither the step-gating parameter nor the record-selecting
 * {@code INCLUDE COND=} that shares its keyword -- no job-level {@code IF}, {@code THEN} or {@code ELSE},
 * no {@code RESTART=} and no {@code CHKPT=}. There is nothing to invert and no branch predicate to
 * derive, so a reader looking for one here is looking for something that does not exist.
 *
 * <p>Assumptions: no retry policy is applied, and were one ever added its condition list would have to be
 * narrow, because the reference source fixes the boundary. Its transient set is exactly three
 * infrastructure statuses, {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} at
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} <strong>L87</strong>, declared identically at
 * {@code cbl/PAUDBLOD.CBL} L107. The statuses NOT in it are the ones a retry must leave alone: the
 * segment-not-found, wrong-parentage and end-of-database statuses at PAUDBLOD L100, L102 and L103
 * describe the data rather than the platform, so retrying them repeats a decided outcome, and the
 * duplicate status at its L101 is TOLERATED by the paragraphs above rather than retried, so retrying it
 * would convert a successful skip into an attempt sequence. Neither condition name is referenced in
 * either program's procedure division, so no attempt sequence is transcribed.
 *
 * <p>Alternatives Considered: adopting a resilience library to express that retry. Rejected: retry is in
 * the Spring Framework core the Spring Boot 4.1.0 parent brings in, so a library would be a second retry
 * authority competing with the durable tiers that actually cover this path -- per-state retry in the
 * batch orchestrator, described in {@code docs/adr/ADR-005-batch-orchestration.md}, which survives the
 * task being replaced mid-work where no in-process loop can. Two core API details are recorded because
 * both are easy to get wrong at a use site: the annotation attribute is {@code maxRetries} and NOT
 * {@code maxAttempts}, so the total number of attempts is one plus that value and defaults to three, and
 * the enabling annotation is {@code @EnableResilientMethods} and NOT {@code @EnableRetry}. No circuit
 * breaker is configured either. The decision of record is
 * {@code docs/adr/ADR-002-compute-platform.md}.
 *
 * <p>Assumptions: this is NOT a chunk-oriented batch job and acquires no job repository. It is an entry
 * point the batch orchestrator invokes as a task, per {@code docs/adr/ADR-005-batch-orchestration.md};
 * the batch starter and its restart repository are scoped to the batch context, whose module declares
 * them, and this module's own build declares no batch starter of any kind.
 *
 * <h2>Trade-offs accepted</h2>
 *
 * <p>Trade-offs: the whole load runs in ONE transaction, so a failure part-way leaves nothing behind. The
 * reference program takes no syncpoint of its own -- it contains no checkpoint call, and the module's job
 * streams contain no {@code CHKPT=} -- and relies on the transaction monitor committing at program end,
 * so one unit of work is the closer reading. The alternative, committing per record, would leave a failed
 * load half-applied and would make the duplicate tolerance above load-bearing for correctness rather than
 * merely for convenience. Keeping it atomic means every re-run starts from a state that is either wholly
 * loaded or wholly absent. What is given up is that a load too large for one transaction's resources
 * cannot be split, which the extract this reads does not approach: it is one file pair per unload of one
 * database.
 *
 * <p>Trade-offs: every record of a file is resolved before any record is decoded, rather than each being
 * decoded as it is read. That costs memory proportional to the file, and it is chosen for a diagnostic
 * reason record-at-a-time decoding cannot match. A length remainder is the symptom of one of these two
 * files being handed to the other's reader, and the strides are a hundred and two hundred and six -- so
 * the child file divided by the root stride leaves two whole records and a six-byte remainder. Decoded as
 * read, those two records would be written first, and a child record's bytes read against the summary
 * layout fail somewhere in the middle of a packed money field: the run would then report a malformed field
 * rather than the mismatched file that actually caused it. Resolving the length first means the stride
 * mismatch is what gets reported, before a single row is written.
 *
 * <p>Refactoring Rationale: that memory cost is now BOUNDED by a stated ceiling, {@link
 * #MAX_RECORDS_PROPERTY}, and the file is read one stride at a time rather than drained into a single
 * array. The rationale that stood here claimed the cost was bounded by the transaction that holds the
 * entities, which was false in two ways: the bytes are read before any entity exists, so the transaction
 * bounds nothing about them, and the two costs are additive rather than one standing in for the other.
 * With no ceiling at all, a stream that is not an extract -- an object-storage key pointing at the wrong
 * file -- was read until the heap ended, reporting an allocation failure naming a byte count instead of a
 * refusal naming the file.
 */
@Service
public class LoadService {

    /**
     * The log the per-record decisions and the refusals are reported on.
     *
     * <p>Assumptions: the reference program reports every insert, every duplicate and every failure to
     * the job log with {@code DISPLAY}, and an operator reads that log to decide whether a load
     * succeeded. A structured log is the equivalent, because a container's standard output is not
     * addressable per record.
     *
     * <p>Assumptions: nothing logged here came off a message queue, so the package-level prohibition on
     * logging wire values is not narrowed by these lines. An account identifier and a file ordinal are
     * the two values reported, and the primary account number the detail segment carries is never among
     * them.
     */
    private static final Logger LOG = LoggerFactory.getLogger(LoadService.class);

    /**
     * The property that states the largest number of records one extract file may hold.
     *
     * <p>Assumptions: the name is published as a constant so that the refusal message can name the
     * property an operator must raise, and so that the binding below and that message cannot name
     * different properties. It is a compile-time constant expression, which is what lets it appear inside
     * the annotation.</p>
     */
    public static final String MAX_RECORDS_PROPERTY = "carddemo.extract.max-records";

    /**
     * The default ceiling on records in one extract file.
     *
     * <p>Assumptions: two hundred thousand is a deliberate over-estimate of a real unload rather than a
     * measured limit, and it is a ceiling on a MISTAKE rather than a capacity plan. The extract is one
     * file pair per unload of one database, so a genuine file is far below this; what the ceiling exists to
     * stop is a stream that is not an extract at all -- an object-storage key pointing at the wrong file --
     * being read until the heap ends. At the child stride of two hundred and six bytes this bounds the read
     * at roughly forty megabytes, which the container's heap holds while leaving the transaction its own
     * room.</p>
     */
    public static final int DEFAULT_MAX_RECORDS = 200_000;

    /**
     * The summary rows, probed for an existing row and inserted when absent.
     */
    private final PendingAuthSummaryRepository summaries;

    /**
     * The authorization rows, probed for an existing row and inserted when absent.
     */
    private final PendingAuthDetailRepository details;

    /**
     * The template each bounded chunk's unit of work is opened through.
     *
     * <p>Refactoring Rationale: the load is a SEQUENCE of bounded transactions rather than one, and the
     * template is what makes the boundary visible in the code. A single declarative transaction over the
     * whole load accumulated every entity of every record in one persistence context, so the memory the
     * load needed grew with the extract rather than staying flat -- on top of an extract that had itself
     * been read into memory entirely. Ending the transaction per chunk also ends the persistence context
     * per chunk, which is why no explicit clear is needed.</p>
     *
     * <p>Trade-offs: the load is no longer atomic across chunks, so a failure part-way leaves earlier
     * chunks committed. That is acceptable precisely because both halves of this loader are idempotent --
     * a record whose row is already present is counted and skipped -- so a re-run after a failure
     * completes the load rather than duplicating it, which is a stronger operational property than
     * all-or-nothing over an extract large enough for the difference to matter.</p>
     */
    private final TransactionTemplate transactions;

    /**
     * How many extract records one chunk holds.
     *
     * <p>Assumptions: the figure bounds three things at once and so is chosen for the tightest of them --
     * the records held in memory, the entities in one persistence context, and the bind parameters in the
     * presence queries issued per chunk. Five hundred is comfortably inside every engine's bind ceiling
     * while still amortising the round trip across a useful number of records.</p>
     */
    private static final int CHUNK_SIZE = 500;

    /**
     * The greatest number of records one extract file may hold, from {@link #MAX_RECORDS_PROPERTY}.
     *
     * <p>Assumptions: this bound and the chunking above answer DIFFERENT questions and neither replaces
     * the other. Chunking keeps the working set flat however long the extract is; this ceiling refuses a
     * stream that is not an extract at all -- an object-storage key pointing at the wrong file -- which
     * chunking would otherwise read forever without ever exhausting anything.</p>
     */
    private final int maxRecords;

    /**
     * Builds the loader over the two repositories it writes.
     *
     * @param summaries the summary repository the root images are loaded into; must not be {@code null}
     * @param details the authorization repository the child records are loaded into; must not be
     *     {@code null}
     * @param transactions the template each bounded chunk's unit of work is opened through; must not be
     *     {@code null}
     * @param maxRecords the greatest number of records one extract file may hold, which must be positive
     * @throws NullPointerException if any repository or the template is {@code null}
     * @throws IllegalArgumentException if {@code maxRecords} is not positive, which is refused HERE rather
     *     than on the first load so that a container configured that way fails at startup, where the cause
     *     is legible, instead of hours later
     */
    public LoadService(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details, TransactionTemplate transactions,
            @Value("${" + MAX_RECORDS_PROPERTY + ":" + DEFAULT_MAX_RECORDS + "}") int maxRecords) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.details = Objects.requireNonNull(details, "details must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        if (maxRecords <= 0) {
            throw new IllegalArgumentException(MAX_RECORDS_PROPERTY + " must be positive but was "
                    + maxRecords + "; a ceiling admitting no records would refuse every extract,"
                    + " including an empty one");
        }
        this.maxRecords = maxRecords;
    }

    /**
     * Loads a whole extract in two passes: every root image, and then every prefixed child record.
     *
     * <p>Purpose. This is {@code MAIN-PARA} at {@code cbl/PAUDBLOD.CBL} <strong>L169 to L187</strong>,
     * whose body is two {@code PERFORM ... UNTIL} loops in sequence: the root loop at L177 to L178 runs
     * to the end of the first file before the child loop at L180 to L181 begins on the second.
     *
     * <p>Assumptions: the two passes are SEQUENTIAL and the order is a requirement rather than a
     * convenience. Every root is loaded before any child is attempted, so that a child's parent is
     * present by the time the child is read. Interleaving the two files, or reading the child file first,
     * would make the parent check above refuse records the reference program loads -- which is the same
     * dependency {@code fk_pending_auth_detail_summary} asserts in the schema, reached from the reference
     * program's own control flow rather than from the constraint.
     *
     * <p>Assumptions: neither stream is opened, flushed or closed here; the caller owns both. That is
     * where {@code 1000-INITIALIZE} at L190 to L215 and {@code 4000-FILE-CLOSE} at L341 to L356 went. The
     * caller decides whether a stream comes from a file, from object storage or from a test resource, and
     * closing one it still intends to reuse would end a run half-read with no error.
     *
     * @param rootImages the summary extract, a whole number of hundred-byte segment images; must not be
     *     {@code null}
     * @param childRecords the detail extract, a whole number of two-hundred-and-six-byte prefixed
     *     records; must not be {@code null}
     * @return the counts of what was read, inserted and skipped across both files; never {@code null}
     * @throws NullPointerException if either stream is {@code null}, or if a summary image carries
     *     neither identifier, both of which the schema declares not null
     * @throws UncheckedIOException if either stream cannot be read
     * @throws IllegalArgumentException if either stream does not hold a whole number of records, or if a
     *     record is malformed for the layout it declares
     * @throws ArithmeticException if a decoded key component or amount exceeds the range its column holds
     * @throws MalformedParentKeyException if a child record's six-byte prefix does not decode
     * @throws UnresolvedParentException if a child record names an account with no summary row
     */
    public LoadOutcome load(InputStream rootImages, InputStream childRecords) {
        Objects.requireNonNull(rootImages, "rootImages must not be null");
        Objects.requireNonNull(childRecords, "childRecords must not be null");

        LoadOutcome roots;
        LoadOutcome children;
        // WHY : ⚠️ Refactoring Rationale: a refusal is now RECORDED here before it propagates, and it
        //       previously was not. A malformed record raised its refusal from deep inside the decode, and
        //       the only line an operator saw was the maintenance runner's, which names the job and the
        //       chain of types. Two facts were therefore missing from the record of the one failure that
        //       needs both: WHICH record of the extract was refused, and WHAT was wrong with it. The first
        //       lives in the refusal type as a field, the second in its cause's message, and neither
        //       reached a log line -- so an operator held a file of half a million records and a type name.
        // WHY : ⚠️ Assumptions: this catches the LOCATED refusal types only and lets everything else pass
        //       untouched. A stream that cannot be read, or a whole-file length that is not a multiple of
        //       the stride, is a fault of the file rather than of a record, and inventing an ordinal for it
        //       would name a record that is not at fault. Those keep reaching the runner as they did.
        // WHY : ⚠️ Assumptions: the message is rendered through the digit-redacting summary and not the
        //       plain one, because the cause here is composed by a MAPPER over a record's own bytes and can
        //       quote the account identifier the record carries. That value is one this context's
        //       observability contract names as inadmissible in a durable diagnostic, and the sibling
        //       assertion in {@code AuthorizationDiagnosticDisclosureTest} holds this class to it.
        // WHY : ⚠️ Assumptions: three catch clauses rather than one multi-catch, because a multi-catch
        //       resolves its variable to the three types' common SUPERtype -- which is the platform's own
        //       illegal-argument type and declares no ordinal accessor. Each clause therefore reads the
        //       ordinal from the type that carries it and hands it to one reporting method, so the three
        //       lines are identical in shape without the ordinal being fetched reflectively or parsed back
        //       out of a message.
        try {
            roots = loadSummaries(rootImages);
            children = loadDetails(childRecords);
        } catch (MalformedParentKeyException prefix) {
            throw reportRefusedRecord(prefix.getRecordOrdinal(), prefix);
        } catch (MalformedSegmentException segment) {
            throw reportRefusedRecord(segment.getRecordOrdinal(), segment);
        } catch (UnresolvedParentException unresolved) {
            // WHY : ⚠️ Assumptions: this type is reported here too, although the method that raises it
            //       already writes a line of its own naming the ordinal. What that line does not carry is
            //       the detail field, and the event name here is shared by all three, so one query finds
            //       every refused record whatever refused it.
            throw reportRefusedRecord(unresolved.getRecordOrdinal(), unresolved);
        } catch (IllegalArgumentException fileFault) {
            // WHY : ⚠️ Refactoring Rationale: a fault of the FILE is now reported too, under its own event
            //       and with no ordinal, and it was measured before it was written: handing this loader an
            //       extract one byte short produced a run whose whole record was a chain of type names
            //       ending at the record reader. The reader's own message names the stride the file failed
            //       to be a multiple of, which is the sentence that turns "the load failed" into "you were
            //       handed a truncated file", and it reached no log at all.
            // WHY : ⚠️ Assumptions: this clause is LAST among the illegal-argument arms, and the ordering
            //       is what makes it mean "of the file". Both located record refusals above are subtypes of
            //       this type, so they are matched first and never reach here; what remains is a fault the
            //       reader raised about the extract as a whole, for which no single record is at fault.
            throw reportRefusedExtract(fileFault);
        } catch (UncheckedIOException unreadable) {
            // WHY : ⚠️ Assumptions: an unreadable stream is reported through the same file-scope event as
            //       the length fault above, rather than being left to the caller, because the two are the
            //       same class of fault from an operator's position: the extract could not be consumed and
            //       no record is to blame. It is a separate clause only because this type is not an
            //       illegal argument and so cannot share one.
            throw reportRefusedExtract(unreadable);
        }
        LoadOutcome total = roots.combinedWith(children);

        LOG.info("extract load complete roots={} children={} read={} inserted={} alreadyPresent={}",
                roots.read(), children.read(), total.read(), total.inserted(),
                total.alreadyPresent());
        return total;
    }

    /**
     * Records one refused extract record, naming its position and its condition, and returns the refusal.
     *
     * <p>⚠️ Assumptions: the refusal is RETURNED for the caller to throw rather than thrown here, so the
     * raise site stays visible at the call site and the compiler still sees the {@code throw}. That is the
     * same convention the codec in the shared kernel uses for its own field failures, deliberately, so a
     * reader who has followed one has followed both.
     *
     * <p>⚠️ Assumptions: the detail is rendered through the digit-redacting summary. The cause of a record
     * refusal is composed by a mapper over that record's bytes and can quote the account identifier it
     * carries, which this context's observability contract names as inadmissible in a durable diagnostic;
     * the redaction replaces every run of three or more digits, so the field name and the constraint reach
     * the line and the values do not.
     *
     * @param ordinal the one-based position of the refused record within its extract
     * @param refusal the refusal to record and return; must not be {@code null}
     * @return the same refusal, so the caller can throw it and keep the raise site at the fault
     */
    private static RuntimeException reportRefusedRecord(int ordinal, RuntimeException refusal) {
        LOG.error("event=authorization.load.record-refused recordOrdinal={} fault={} detail={}",
                ordinal, ThrowableDigest.of(refusal), FailureSummary.redactedOf(refusal));
        return refusal;
    }

    /**
     * Records one refused EXTRACT, naming its condition and deliberately naming no record.
     *
     * <p>⚠️ Purpose: this is the file-scope companion of {@link #reportRefusedRecord(int, RuntimeException)}
     * and it exists for the same complaint about a different fault. A truncated extract, or one that cannot
     * be read at all, previously reached an operator as a chain of type names ending at the record reader;
     * the sentence that says WHICH stride the file failed to be a multiple of was composed, raised and never
     * logged.
     *
     * <p>⚠️ Assumptions: no ordinal is rendered, and the absence is the point rather than an omission. When
     * a file's total length is not a multiple of its stride, every record in it may be well formed, so any
     * ordinal this line named would send a reader to inspect bytes that are correct. A separate event name
     * is what lets the two be told apart in a query: one asks which record was refused, the other asks which
     * file was.
     *
     * <p>⚠️ Trade-offs: the condition is rendered through the digit-redacting summary, as the record-scope
     * companion's is, and here that has a visible cost worth stating rather than discovering. These messages
     * are composed in this class and name a stride and a byte count rather than a customer, so the redaction
     * hashes the harmless 206 along with anything harmful: the line reads "a whole number of ###-byte
     * records". It is still rendered that way for two reasons. A location can reach this field -- an object
     * key or a filesystem path carries digits, and the extract locations are caller-supplied -- and a rule
     * that could tell a record length from an account identifier would have to understand the message, which
     * is composed in a dozen places. What the reader needs from this line survives the hashing: the file is
     * not a whole number of records, so it was truncated or handed to the wrong reader. The exact stride is a
     * layout constant, and it is carried unhashed by the RAISED message, which is where an exact figure
     * belongs.
     *
     * @param <E> the refusal's own type, preserved so the caller's {@code throw} stays exact
     * @param refusal the file-scope refusal to record and return; must not be {@code null}
     * @return the same refusal, so the caller can throw it and keep the raise site at the fault
     */
    private static <E extends RuntimeException> E reportRefusedExtract(E refusal) {
        LOG.error("event=authorization.load.extract-refused fault={} detail={}",
                ThrowableDigest.of(refusal), FailureSummary.redactedOf(refusal));
        return refusal;
    }

    /**
     * Loads the root images of an extract, skipping every summary whose row is already present.
     *
     * <p>Purpose. This is {@code 2000-READ-ROOT-SEG-FILE} at {@code cbl/PAUDBLOD.CBL}
     * <strong>L222 to L237</strong>, which reads one hundred-byte image and moves it into the summary
     * segment at its L229, together with {@code 2100-INSERT-ROOT-SEG} at <strong>L242 to L263</strong>,
     * whose three flat tests tolerate the duplicate status at L256 to L258 and abend on anything else at
     * L259 to L262.
     *
     * <p>Assumptions: the stride is taken from the layout descriptor the mapper owns rather than written
     * here as a hundred, and the geometry it reports is single-sourced in the reference material too.
     * {@code jcl/UNLDPADB.JCL} declares the root file's record length once, at its <strong>L48</strong>
     * and <strong>L50</strong>, and the child file's at its <strong>L53</strong> and <strong>L55</strong>;
     * {@code jcl/LOADPADB.JCL} then reads those same two data sets as {@code INFILE1} at its
     * <strong>L36</strong> and {@code INFILE2} at its <strong>L38</strong> with NO record-length
     * attributes of its own. The writer declares the geometry and the reader inherits it -- which is the
     * job-stream analogue of one copybook serving every program that includes it. Restating the stride in
     * this method would add a second declaration that could disagree with the first.
     *
     * <p>Assumptions: the load-path decode is used, not the decision-path one, and the two accept
     * different inputs deliberately. {@code PendingAuthSummaryMapper.toEntity} refuses an image carrying
     * running state so that the decision path cannot silently discard it, and every root in a real extract
     * DOES carry running state, because the unload writes the account's authorization status, its five
     * status occurrences, both balances and all four counters. That decode would therefore refuse every
     * record a live database produced.
     *
     * @param rootImages the summary extract, a whole number of hundred-byte segment images; must not be
     *     {@code null}
     * @return the counts of what was read, inserted and skipped; never {@code null}
     * @throws NullPointerException if {@code rootImages} is {@code null}, or if an image carries neither
     *     identifier, both of which the schema declares not null
     * @throws UncheckedIOException if the stream cannot be read
     * @throws IllegalArgumentException if the stream does not hold a whole number of segment images, or
     *     if an image is malformed for the summary layout
     */
    public LoadOutcome loadSummaries(InputStream rootImages) {
        Objects.requireNonNull(rootImages, "rootImages must not be null");
        RecordStream stream =
                new RecordStream(rootImages, PendingAuthSummaryMapper.unloadRecordLength(), "summary",
                        this.maxRecords);
        LoadOutcome total = new LoadOutcome(0, 0, 0);
        int ordinalBase = 0;
        while (true) {
            List<byte[]> chunk = stream.nextChunk(CHUNK_SIZE);
            if (chunk.isEmpty()) {
                return total;
            }
            int base = ordinalBase;
            LoadOutcome outcome = this.transactions.execute(status -> insertSummaries(chunk, base));
            total = total.combinedWith(Objects.requireNonNull(outcome));
            ordinalBase += chunk.size();
        }
    }

    /**
     * Inserts one bounded chunk of summary records inside its own transaction.
     *
     * <p>Assumptions: presence is settled for the WHOLE chunk in one statement before anything is
     * written, and the resulting set is then consulted in memory. Refactoring Rationale: this replaces an
     * identity probe issued once per record, so a chunk of five hundred records now issues one presence
     * query rather than five hundred, and the load's cost stops growing with the record count
     * independently of the work each record represents.</p>
     *
     * <p>Assumptions: a duplicate WITHIN the chunk is caught by the same set, because each accepted
     * account is added to it as it is accepted. That preserves the behaviour the probe had for free -- it
     * read the transaction's own pending inserts -- which a batched pre-read would otherwise lose, and
     * without it a chunk naming one account twice would violate the primary key and turn a counted skip
     * into a failed transaction.</p>
     *
     * @param chunk the extract records to insert; must not be {@code null}
     * @param ordinalBase how many records preceded this chunk, so logged ordinals count from the start of
     *     the extract rather than from the start of the chunk
     * @return what this chunk read, inserted and skipped; never {@code null}
     */
    private LoadOutcome insertSummaries(List<byte[]> chunk, int ordinalBase) {
        List<PendingAuthSummary> decoded = new ArrayList<>(chunk.size());
        for (byte[] image : chunk) {
            decoded.add(PendingAuthSummaryMapper.fromExtractRecord(image));
        }
        Set<Long> present = new HashSet<>(this.summaries.findExistingAccountIds(
                decoded.stream().map(PendingAuthSummary::getAccountId).toList()));
        int inserted = 0;
        int alreadyPresent = 0;
        // WHY : Assumptions: presence is settled TWICE, by two mechanisms answering two different
        //       questions, and neither makes the other redundant. The chunk-wide query catches the
        //       duplicate this chunk carries within itself -- two records for one account in the same
        //       extract, which the reference counts as already present -- in one round trip rather than
        //       one per record. The conflict-tolerant insert catches the row that appeared BETWEEN that
        //       query and this write, which no read can catch by construction: the online decision path
        //       inserts a summary for an account that has none, and a second run over the same extract
        //       writes the same keys. Keeping only the query leaves that gap, and a save through the
        //       persistence context closes it by REPLACING the newer row in every column, silently
        //       resetting counters a live decision had already moved.
        // WHY : Assumptions: the short circuit is load-bearing rather than stylistic. A record the
        //       chunk-wide query already accounted for issues no statement at all, so the second
        //       mechanism costs a round trip only for records that are actually about to be written.
        for (int index = 0; index < decoded.size(); index++) {
            PendingAuthSummary summary = decoded.get(index);
            if (!present.add(summary.getAccountId())
                    || this.summaries.insertSummaryIfAbsent(summary) == 0) {
                alreadyPresent++;
                // WHY : Assumptions: the skip is logged by RECORD ORDINAL and never by account. A load
                //       log is read by more people than the table is, so an eleven-digit account number
                //       in it is a durable copy of a customer identifier outside the store that protects
                //       it. The ordinal is what an operator needs anyway, because it locates the record
                //       in the extract they are holding.
                // WHY : Assumptions: ONE diagnostic covers both arms rather than one apiece. The
                //       operator's question is which record of the extract was not written, and that is
                //       answered identically either way; a second message would also raise the count of
                //       skip diagnostics this class emits, which its disclosure test asserts.
                LOG.info("summary already present, skipped recordOrdinal={}", ordinalBase + index + 1);
                continue;
            }
            inserted++;
        }
        // WHY : Refactoring Rationale: the explicit flush that stood here is gone with the batched save
        //       it belonged to. It existed so a constraint this chunk violated was raised inside the
        //       chunk that caused it rather than at a commit whose diagnostics no longer name the record;
        //       a statement executed at its own call site raises there by construction, so the property
        //       now holds without a call to arrange it.
        return new LoadOutcome(chunk.size(), inserted, alreadyPresent);
    }

    /**
     * Loads the prefixed child records of an extract, refusing any whose parent cannot be resolved.
     *
     * <p>Purpose. This is {@code 3000-READ-CHILD-SEG-FILE} at {@code cbl/PAUDBLOD.CBL}
     * <strong>L269 to L289</strong>, {@code 3100-INSERT-CHILD-SEG} at <strong>L292 to L316</strong> and
     * {@code 3200-INSERT-IMS-CALL} at <strong>L318 to L339</strong>. The reference paragraphs read a
     * record, take its parent key from the prefix at L277, position on that parent at L296 to L299, and
     * insert the child at L321 to L324.
     *
     * <p>Assumptions: the record is two hundred and six bytes -- a six-byte packed parent key ahead of a
     * two-hundred-byte segment -- declared at {@code cbl/PAUDBLOD.CBL} <strong>L46 to L48</strong> as
     * {@code 05 ROOT-SEG-KEY PIC S9(11) COMP-3} followed by {@code 05 CHILD-SEG-REC PIC X(200)}. The
     * stride comes from the mapper's layout descriptor for the reason
     * {@link #loadSummaries(InputStream)} records, and the segment's own two hundred bytes are confirmed
     * independently by {@code ims/PADFLDBD.DBD} <strong>L27</strong>, {@code RECORD=(200),RECFM=F}.
     *
     * <p>Assumptions: the two refusals below are ordered as they are because the earlier one is the
     * precondition of the later. A prefix that does not decode yields no account identifier, so there is
     * nothing to look a parent up by; the parent check can only run once the key is in hand.
     *
     * @param childRecords the detail extract, a whole number of two-hundred-and-six-byte prefixed
     *     records; must not be {@code null}
     * @return the counts of what was read, inserted and skipped; never {@code null}
     * @throws NullPointerException if {@code childRecords} is {@code null}
     * @throws UncheckedIOException if the stream cannot be read
     * @throws IllegalArgumentException if the stream does not hold a whole number of prefixed records, or
     *     if a record is malformed for the detail layout, in which case the refusal is the
     *     {@link MalformedSegmentException} subtype naming the record's ordinal
     * @throws ArithmeticException if a decoded key component or amount exceeds the range its column holds
     * @throws MalformedParentKeyException if a record's six-byte prefix does not decode
     * @throws UnresolvedParentException if a record names an account with no summary row
     */
    public LoadOutcome loadDetails(InputStream childRecords) {
        Objects.requireNonNull(childRecords, "childRecords must not be null");
        RecordStream stream = new RecordStream(childRecords,
                PendingAuthDetailMapper.unloadRecordLength(), "prefixed detail", this.maxRecords);
        LoadOutcome total = new LoadOutcome(0, 0, 0);
        int ordinalBase = 0;
        while (true) {
            List<byte[]> chunk = stream.nextChunk(CHUNK_SIZE);
            if (chunk.isEmpty()) {
                return total;
            }
            int base = ordinalBase;
            LoadOutcome outcome = this.transactions.execute(status -> insertDetails(chunk, base));
            total = total.combinedWith(Objects.requireNonNull(outcome));
            ordinalBase += chunk.size();
        }
    }

    /**
     * Inserts one bounded chunk of authorization records inside its own transaction.
     *
     * <p>Assumptions: parent presence and row presence are each settled for the WHOLE chunk in one
     * statement before anything is written. Refactoring Rationale: this replaces TWO probes per record --
     * one for the parent and one for the row -- so a chunk of five hundred records now issues two queries
     * where it previously issued a thousand.</p>
     *
     * <p>Assumptions: a parent inserted by an EARLIER chunk of the same run counts as present, because
     * the presence query reads the table and earlier chunks have committed. That is the ordering both
     * halves of the load already require -- summaries before authorizations -- so chunking does not
     * weaken it.</p>
     *
     * @param chunk the extract records to insert; must not be {@code null}
     * @param ordinalBase how many records preceded this chunk, so logged and refused ordinals count from
     *     the start of the extract rather than from the start of the chunk
     * @return what this chunk read, inserted and skipped; never {@code null}
     * @throws UnresolvedParentException if a record names an account with no summary row
     * @throws MalformedParentKeyException if a record's parent-key prefix cannot be decoded
     */
    private LoadOutcome insertDetails(List<byte[]> chunk, int ordinalBase) {
        List<PendingAuthDetail> decoded = new ArrayList<>(chunk.size());
        for (int index = 0; index < chunk.size(); index++) {
            decoded.add(decodeChild(chunk.get(index), ordinalBase + index + 1));
        }
        Set<Long> parents = new HashSet<>(this.summaries.findExistingAccountIds(
                decoded.stream().map(detail -> detail.getId().getAccountId()).distinct().toList()));
        // WHY : Assumptions: EVERY parent in the chunk is validated before the child presence query is
        //       issued, in its own pass. That ordering is deliberate and is the one the per-record
        //       version had for free: a chunk naming an account with no summary row is refused without
        //       the loader ever having read the child table, so a malformed extract costs one query
        //       rather than two and the refusal cannot be preceded by a read it did not need.
        for (int index = 0; index < decoded.size(); index++) {
            requireParent(parents, decoded.get(index).getId().getAccountId(),
                    ordinalBase + index + 1);
        }
        Set<PendingAuthDetailKey> present = new HashSet<>(this.details.findExistingIds(
                decoded.stream().map(PendingAuthDetail::getId).toList()));
        int inserted = 0;
        int alreadyPresent = 0;
        // WHY : Assumptions: the two mechanisms above the summary loop apply here for the same reasons
        //       and against the same two failures -- the chunk-wide query for a duplicate the extract
        //       carries within itself, the conflict-tolerant insert for a key committed between that
        //       query and this write. The child key is a composite of account, date and time, so the
        //       conflict target names all three and a collision on any other constraint raises rather
        //       than being counted as the duplicate this arm exists to tolerate.
        for (int index = 0; index < decoded.size(); index++) {
            PendingAuthDetail detail = decoded.get(index);
            int ordinal = ordinalBase + index + 1;
            if (!present.add(detail.getId())
                    || this.details.insertDetailIfAbsent(detail) == 0) {
                alreadyPresent++;
                LOG.info("authorization already present, skipped recordOrdinal={} authDate={}"
                        + " authTime={}", ordinal, detail.getId().getAuthDate(),
                        detail.getId().getAuthTime());
                continue;
            }
            inserted++;
        }
        return new LoadOutcome(chunk.size(), inserted, alreadyPresent);
    }

    /**
     * Decodes one prefixed child record, refusing a prefix that is not well-formed packed decimal.
     *
     * <p>Purpose. This is divergence <strong>D-LOAD-PREFIX-REFUSED</strong>, registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}. It stands at the guard at
     * {@code cbl/PAUDBLOD.CBL} <strong>L275</strong>,
     * {@code IF ROOT-SEG-KEY IS NUMERIC}, whose true branch moves the key at L277 and performs the insert
     * at L281 and whose false branch is absent, so a record failing it leaves the run with no message.
     *
     * <p>Assumptions: the prefix is PACKED and is never read as text. Decoding is delegated to
     * {@code PendingAuthDetailMapper.unloadedAccountId}, which reads it through
     * {@code com.carddemo.common.codec.PackedDecimalCodec} at the declared eleven digits. The six bytes
     * carry two digits each and a sign nibble, so almost none of them coincides with the character
     * encoding of the digit it stands for: read as text they produce a printable-looking token that is a
     * different number, which is a wrong value rather than a failure. Nothing in this class re-derives the
     * decode, so there is one implementation of the packed regime rather than one per reader.
     *
     * <p>Refactoring Rationale: the codec's own refusal is caught and re-raised as
     * {@link MalformedParentKeyException} rather than propagated as it stands. The codec reports which
     * byte offset of which field was malformed, which is the right diagnostic for a field and the wrong
     * one for a file: what an operator needs is WHICH RECORD to look at, and the codec cannot know the
     * ordinal because it is handed one array. The cause is retained, so the byte-level detail is not lost.
     *
     * <p>Assumptions: the prefix is decoded in its OWN attempt and the segment outside it, rather than the
     * record being decoded in one call under one catch. The reason is that both halves of the record
     * contain packed fields -- the prefix, and the two amounts the segment declares at
     * {@code cpy/CIPAUDTY.cpy} L34 and L35 -- so they raise the same codec refusal for entirely different
     * faults. A catch wide enough to cover the whole record would report a malformed transaction amount as
     * an undecodable parent key, sending an operator to look at the six bytes that were the one part of
     * the record that was well formed.
     *
     * @param record one prefixed child record, exactly the declared stride; never {@code null}
     * @param ordinal the one-based position of this record in the extract, used to identify it
     * @return the decoded authorization, its key carrying the account taken from the prefix; never
     *     {@code null}
     * @throws MalformedParentKeyException if the six-byte prefix is not well-formed packed decimal, or
     *     holds a value the account identifier cannot represent
     * @throws MalformedSegmentException if the embedded segment is malformed for the detail layout, which
     *     includes a stored complement out of range, a match status an insert cannot originate, and a
     *     fraud position outside the domain the column admits
     * @throws ArithmeticException if a key component or amount inside the SEGMENT exceeds the range its
     *     column holds. Assumptions: this is deliberately NOT folded into the prefix refusal above, for
     *     the reason that refusal's own scoping records -- an arithmetic overflow in the segment is a
     *     different fault from an undecodable prefix, and reporting it as one would misdirect a reader
     */
    private static PendingAuthDetail decodeChild(byte[] record, int ordinal) {
        Long accountId;
        try {
            accountId = PendingAuthDetailMapper.unloadedAccountId(record);
        } catch (PackedDecimalCodec.PackedDecimalException | ArithmeticException undecodable) {
            throw new MalformedParentKeyException(ordinal, undecodable);
        }
        // WHY : Refactoring Rationale: the segment decode's own refusal is now re-raised with the
        //       record's ordinal, for exactly the reason the prefix refusal above is. The mapper is
        //       handed one array and cannot know which record of the extract it came from, so its message
        //       named the offending FIELD and left an operator holding a file of five hundred thousand
        //       records with no way to find the one at fault. The refusal type is a subtype of the one
        //       this method already raised, so every caller's contract is unchanged.
        try {
            return PendingAuthDetailMapper.toEntity(PendingAuthDetailMapper.unloadedSegment(record),
                    accountId);
        } catch (MalformedSegmentException alreadyIdentified) {
            throw alreadyIdentified;
        } catch (IllegalArgumentException malformed) {
            throw new MalformedSegmentException(ordinal, malformed);
        }
    }

    /**
     * Refuses a child record whose parent summary is absent, naming the record rather than the account.
     *
     * <p>Refactoring Rationale: this method's summary line said it names "the account it could not
     * resolve", and both the log line and the raised message did. Neither does now. This migration's
     * observability contract names account identifiers among the values a durable diagnostic may not hold
     * and requires them omitted rather than abbreviated, and a thrown message is a durable diagnostic in
     * the same sense a log line is: it is written to the job log by whatever catches it, and it is
     * attached to the failure an orchestrator surfaces. The record ordinal takes its place at both sites,
     * and it identifies the refused record within the extract the operator is reconciling -- which is
     * closer to the artifact in hand than a database key was.</p>
     *
     * <p>Assumptions: the account identifier is still carried on the exception as a typed accessor, and
     * that is not a contradiction. The prohibition is on what a diagnostic RENDERS for an operator to
     * read; a caller that catches this type and needs the unresolved key to decide something reads it
     * through the accessor, in memory, without it passing through a log. Alternatives Considered: dropping
     * the field as well, so the value could not be reached at all. Rejected because it would remove a
     * caller's ability to act on the refusal in order to protect a rendering that no longer includes
     * it.</p>
     *
     * <p>Purpose. This is divergence D-C, recorded on this class and registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}. It stands where the reference
     * positioning call at {@code cbl/PAUDBLOD.CBL} L296 to L299 stood, and it reports the failure that
     * L305's enclosing branch prevents L310 to L313 from reporting.
     *
     * <p>Assumptions: the probe is by account identifier alone, because that is the whole of the parent's
     * key -- {@code pending_auth_summary} is keyed on {@code account_id} -- and it is the whole of what
     * the reference positioning call qualifies on, which moves the prefix into a single key value at L277
     * for a search argument whose key field is the account.
     *
     * @param parents the accounts this chunk's single presence query found summary rows for; never
     *     {@code null}
     * @param accountId the account the record's prefix named; never {@code null}
     * @param ordinal the one-based position of the record in the extract, used to identify it
     * @throws UnresolvedParentException if no summary row exists for that account
     */
    private static void requireParent(Set<Long> parents, Long accountId, int ordinal) {
        if (parents.contains(accountId)) {
            return;
        }
        // WHY : Assumptions: the refusal is LOGGED by ordinal only, while the exception still carries the
        //       account on an accessor. The two are different exposures: a log line is durable, widely
        //       readable and outlives the run, whereas the accessor is read by the caller that is already
        //       holding the extract. The message this raises names the ordinal alone for the same reason.
        LOG.error("authorization refused, its account has no summary row recordOrdinal={}", ordinal);
        throw new UnresolvedParentException(accountId, ordinal);
    }

    /**
     * Resolves a stream into a known number of whole fixed-length records, refusing a remainder.
     *
     * <p>Purpose. This is the read at {@code cbl/PAUDBLOD.CBL} L226 and L272 together with the file-status
     * tests that follow each, reduced to the two outcomes those tests exist to distinguish: the file held
     * records, or the file ended.
     *
     * <p>Refactoring Rationale: this is divergence <strong>D-LOAD-READ-BOUNDED</strong>, registered in
     * {@code docs/architecture/cobol-to-service-traceability.md}. The third outcome is removed rather
     * than transcribed, and this is where the non-termination hazard described on this class is closed.
     * The reference status tests each have a branch that neither sets an end flag nor abends --
     * <strong>L235</strong> for the root file and <strong>L287</strong> for the child file -- reached from
     * any status that is neither success nor end-of-file, and returning from it leaves the enclosing
     * {@code PERFORM ... UNTIL} at L178 or L181 with an unchanged flag and an unchanged file position.
     * Resolving the whole stream once removes the possibility: after this method returns the element count
     * is settled, and a caller's walk of it cannot be extended by anything a record contains.
     *
     * <p>Refactoring Rationale: the stream is read ONE STRIDE AT A TIME and the record count is capped,
     * where the whole stream was previously drained into a single array with no ceiling of any kind. Two
     * things were wrong with the drain. It allocates a contiguous array the size of the input, so an
     * extract larger than the heap fails as an allocation error naming a byte count rather than as a
     * refusal naming the file -- and it fails before any of this method's own diagnostics can run.
     * The rationale that stood here claimed the cost was "bounded by the same transaction that already
     * holds every entity the load produces", and that was simply false: the transaction bounds nothing
     * about a byte array read before any entity exists, and the two are additive rather than alternative.
     * A stride-sized read plus an explicit ceiling makes the bound a stated number an operator can raise
     * deliberately, instead of an unstated one the heap discovers.
     *
     * <p>Assumptions: the diagnostic ORDER that the drain was chosen for is preserved exactly, and that is
     * why this still resolves every record before returning rather than yielding them one at a time to the
     * caller. A length remainder is the symptom of one of these two files being handed to the other's
     * reader, and the strides are a hundred and two hundred and six -- so the child file divided by the
     * root stride leaves two whole records and a six-byte remainder. Yielded one at a time, those two
     * records would be DECODED and written first, and a child record's bytes read against the summary
     * layout fail somewhere in the middle of a packed money field: the run would report a malformed field
     * rather than the mismatched file that actually caused it. Resolving the length first means the stride
     * mismatch is what gets reported, before a single row is written.
     *
     * <p>Assumptions: a remainder is a refusal rather than a partial last record. It means the file was
     * produced against a different layout, or was truncated in transit, and either way every field offset
     * in that remainder is wrong -- so accepting it would store plausible values in the wrong columns
     * instead of reporting a fault. The message names the stride, because the likeliest cause is one of
     * the two files being handed to the other's reader.
     *
     * <p>Refactoring Rationale: this is a READER rather than a splitter, which is the substance of the
     * change. What it replaces returned every record of the extract in one list, having first read the
     * whole stream into a second array, so the loader held two complete copies before it wrote a single
     * row and no way of looping over the result could make that bounded.</p>
     */
    private static final class RecordStream {

        /** The stream the records are read from. */
        private final InputStream stream;

        /** How many bytes one record occupies. */
        private final int stride;

        /** What the extract is called in a refusal. */
        private final String description;

        /** The greatest number of records this extract may hold before it is refused. */
        private final int ceiling;

        /** How many records have been handed out so far, which the ceiling is measured against. */
        private int taken;

        /**
         * Wraps one extract stream as a source of fixed-width records.
         *
         * @param stream the stream to read; must not be {@code null}
         * @param stride how many bytes one record occupies; must be positive
         * @param description what the extract is called in a refusal; must not be {@code null}
         * @param ceiling the greatest number of records the extract may hold; must be positive
         */
        RecordStream(InputStream stream, int stride, String description, int ceiling) {
            this.stream = stream;
            this.stride = stride;
            this.description = description;
            this.ceiling = ceiling;
        }

        /**
         * Reads up to a stated number of whole records, or fewer at the end of the extract.
         *
         * <p>Refactoring Rationale: the stream is read a CHUNK AT A TIME rather than in full. An earlier
         * revision read the entire extract into one array and then copied every record out of it into a
         * second list, so the load held two complete copies of the extract in memory before it wrote a
         * single row, and nothing about either the extract or the caller bounded that. Reading
         * incrementally holds one chunk, and the caller's transaction ends per chunk, so the memory the
         * load needs is flat in the size of the extract.</p>
         *
         * <p>Assumptions: the whole-record check is applied at the END of the stream rather than by
         * dividing a known total, because a stream's total is exactly what is no longer read. A trailing
         * partial record is still refused as a malformed extract, so the contract is unchanged even
         * though the mechanism is.</p>
         *
         * @param records the greatest number of records to return
         * @return up to {@code records} whole records, and an empty list at the end of the extract
         * @throws UncheckedIOException if the extract cannot be read
         * @throws IllegalArgumentException if the extract ends part-way through a record
         */
        List<byte[]> nextChunk(int records) {
            List<byte[]> chunk = new ArrayList<>(records);
            for (int index = 0; index < records; index++) {
                // WHY : Assumptions: the ceiling is tested BEFORE the read that would exceed it, so the
                //       refusal names the file rather than arriving as a heap failure naming a byte
                //       count. It is tested per record rather than per chunk because a chunk boundary
                //       need not fall on the ceiling, and a per-chunk test would admit up to a chunk
                //       beyond the stated bound.
                if (this.taken >= this.ceiling) {
                    throw new IllegalArgumentException("a " + this.description + " extract must hold at"
                            + " most " + this.ceiling + " records; raise " + MAX_RECORDS_PROPERTY
                            + " deliberately if this file is genuinely larger, and check first that it"
                            + " is the extract this loader was given");
                }
                byte[] record;
                try {
                    record = this.stream.readNBytes(this.stride);
                } catch (IOException unreadable) {
                    throw new UncheckedIOException(
                            "the " + this.description + " extract could not be read", unreadable);
                }
                if (record.length == 0) {
                    break;
                }
                if (record.length != this.stride) {
                    throw new IllegalArgumentException("a " + this.description + " extract must hold a"
                            + " whole number of " + this.stride + "-byte records but ended with "
                            + record.length + " trailing bytes");
                }
                chunk.add(record);
                this.taken++;
            }
            return chunk;
        }
    }

    /**
     * What a load did: how many records it read, how many rows it wrote, and how many it skipped.
     *
     * <p>Assumptions: for a load that RETURNS, {@code read} equals {@code inserted} plus
     * {@code alreadyPresent}, and the identity is the point of the shape rather than an accident of it.
     * Every record a completed load saw was either written or recognised as already stored; the two
     * conditions the reference program passes over silently now raise instead, so there is no third
     * disposition a returned outcome can be hiding. A caller can assert the identity, and a caller that
     * receives an outcome at all knows nothing was discarded.
     *
     * <p>Refactoring Rationale: an earlier shape of this carrier had a fourth component counting child
     * records whose parent could not be resolved. It is removed because divergence D-C, recorded on the
     * enclosing class, raises on that condition instead of counting it -- so the component could only ever
     * report zero, and a zero-valued counter for a condition that cannot occur advertises a tolerance this
     * service does not have. A reader would reasonably conclude from its presence that some records are
     * passed over, which is the belief the divergence exists to correct.
     *
     * @param read the number of records read from the extract
     * @param inserted the number of rows written
     * @param alreadyPresent the number of records whose row already existed and which were skipped
     */
    public record LoadOutcome(int read, int inserted, int alreadyPresent) {

        /**
         * Adds another load's counts to these, for reporting a two-file load as one result.
         *
         * @param other the counts to add; must not be {@code null}
         * @return a new carrier holding the component-wise sum; never {@code null}
         * @throws NullPointerException if {@code other} is {@code null}
         */
        public LoadOutcome combinedWith(LoadOutcome other) {
            Objects.requireNonNull(other, "other must not be null");
            return new LoadOutcome(this.read + other.read, this.inserted + other.inserted,
                    this.alreadyPresent + other.alreadyPresent);
        }
    }

    /**
     * Raised when a child record names an account that has no summary row.
     *
     * <p>Purpose. This is the reported form of divergence D-C, described on {@link LoadService} and
     * registered in {@code docs/architecture/cobol-to-service-traceability.md}. Its MESSAGE names the
     * record's position in the extract and the remedy; the account it could not resolve is carried on the
     * type as a typed accessor and appears in no rendering.
     *
     * <p>Refactoring Rationale: the account identifier was formatted INTO the message, and that made this
     * exception a carrier for a prohibited value. A throwable's message is a durable diagnostic in the same
     * sense a log line is -- whatever catches it writes it, an orchestrator surfaces it with the failed
     * task, and this migration's observability contract requires an account identifier to be omitted from
     * such a rendering rather than shortened. What the message loses in specificity the ordinal restores:
     * it names the exact record in the extract file the operator is holding.</p>
     *
     * <p>Assumptions: it extends the platform's illegal-state exception because the refusal is about the
     * STATE the load is running against -- the summary the record depends on is not there -- and not
     * about the record's own bytes, which are well formed. The malformed-prefix refusal beside it extends
     * the argument exception for the opposite reason. A caller that wants both catches the runtime
     * exception they share.
     */
    public static class UnresolvedParentException extends IllegalStateException {

        /**
         * The serialization identity of this exception type.
         *
         * <p>Assumptions: declared because the platform's throwable hierarchy is serializable, so a type
         * without one takes a computed identity that changes when the class does.
         */
        private static final long serialVersionUID = 1L;

        /**
         * The account the refused record named.
         *
         * <p>Assumptions: retained as a field and DELIBERATELY not formatted into the message, so a caller
         * can act on it -- reporting which summaries an operator has to load -- while no rendering of this
         * exception discloses it. It was previously both, which is what made the message a carrier for a
         * value the observability contract prohibits. Reading it now requires calling the accessor, which
         * is an in-memory decision rather than a diagnostic.</p>
         */
        private final Long accountId;

        /**
         * The one-based position of the refused record in the extract.
         */
        private final int recordOrdinal;

        /**
         * Builds the refusal for one record.
         *
         * @param accountId the account the record's prefix named; must not be {@code null}
         * @param recordOrdinal the one-based position of the record in the extract
         * @throws NullPointerException if {@code accountId} is {@code null}
         */
        UnresolvedParentException(Long accountId, int recordOrdinal) {
            // WHY : Assumptions: the message names the record and the remedy and NOT the account, and the
            //   null check stays on the argument rather than moving into the message expression it used to
            //   sit inside. A caller reads the value through the accessor, so the field must still be
            //   non-null; removing the check with the interpolation would have let a null reach a caller
            //   that the message previously guaranteed against.
            super("record " + recordOrdinal + " of the prefixed detail extract names an account with no"
                    + " pending-authorization summary row; load the summary extract for that account and"
                    + " re-run");
            this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
            this.recordOrdinal = recordOrdinal;
        }

        /**
         * Returns the account the refused record named.
         *
         * @return the account identifier; never {@code null}
         */
        public Long getAccountId() {
            return this.accountId;
        }

        /**
         * Returns the one-based position of the refused record in the extract.
         *
         * @return the record's ordinal position
         */
        public int getRecordOrdinal() {
            return this.recordOrdinal;
        }
    }

    /**
     * Raised when a child record's six-byte packed prefix does not decode to an account identifier.
     *
     * <p>Purpose. This is the reported form of the condition the guard at {@code cbl/PAUDBLOD.CBL}
     * <strong>L275</strong> tests for and supplies no {@code ELSE} branch against.
     *
     * <p>Assumptions: it carries only the ordinal, because there is nothing else to carry. A prefix that
     * does not decode names no account, so unlike the refusal beside it this one cannot report a key --
     * which is the reason the ordinal is reported at all. It extends the platform's argument exception,
     * matching the codec refusal it wraps, so a caller already handling malformed extract data catches
     * both with one clause.
     */
    public static class MalformedParentKeyException extends IllegalArgumentException {

        /**
         * The serialization identity of this exception type.
         *
         * <p>Assumptions: declared for the same reason as its sibling's -- the throwable hierarchy is
         * serializable, and an undeclared identity is a computed one.
         */
        private static final long serialVersionUID = 1L;

        /**
         * The one-based position of the refused record in the extract.
         */
        private final int recordOrdinal;

        /**
         * Builds the refusal for one record, retaining the codec's own account of what was wrong.
         *
         * @param recordOrdinal the one-based position of the record in the extract
         * @param cause the codec or arithmetic refusal this wraps; must not be {@code null}
         * @throws NullPointerException if {@code cause} is {@code null}
         */
        MalformedParentKeyException(int recordOrdinal, Throwable cause) {
            super("record " + recordOrdinal + " of the prefixed detail extract carries a six-byte parent"
                    + " key that is not a decodable packed eleven-digit account identifier",
                    Objects.requireNonNull(cause, "cause must not be null"));
            this.recordOrdinal = recordOrdinal;
        }

        /**
         * Returns the one-based position of the refused record in the extract.
         *
         * @return the record's ordinal position
         */
        public int getRecordOrdinal() {
            return this.recordOrdinal;
        }
    }

    /**
     * Refuses one child record whose embedded segment is malformed for the detail layout.
     *
     * <p>Purpose. This names WHICH record of the extract was refused, which the mapper cannot: it is
     * handed one array and knows nothing of the file it came from. The wrapped refusal carries what was
     * wrong with the record; this type carries where it is.
     *
     * <p>Assumptions: it extends {@code IllegalArgumentException} for the same reason its sibling does --
     * the load operations documented that type for a malformed record before this type existed, and a
     * subtype keeps every caller's contract intact while letting a caller that wants the ordinal ask for
     * it.
     *
     * <p>Trade-offs: the message names the ordinal and not the account. An operator fixing an extract
     * works from record positions, and this service's logging rules keep account identifiers out of
     * diagnostics; the caller that supplied the record already holds the account.
     */
    public static class MalformedSegmentException extends IllegalArgumentException {

        /**
         * The serialization identity of this exception type.
         *
         * <p>Assumptions: declared for the same reason as its sibling's -- the throwable hierarchy is
         * serializable, and an undeclared identity is a computed one.
         */
        private static final long serialVersionUID = 1L;

        /**
         * The one-based position of the refused record in the extract.
         */
        private final int recordOrdinal;

        /**
         * Builds the refusal for one record, retaining the mapper's own account of what was wrong.
         *
         * @param recordOrdinal the one-based position of the record in the extract
         * @param cause the mapper's refusal this wraps; must not be {@code null}
         * @throws NullPointerException if {@code cause} is {@code null}
         */
        MalformedSegmentException(int recordOrdinal, Throwable cause) {
            super("record " + recordOrdinal + " of the prefixed detail extract carries a segment that is"
                    + " malformed for the authorization-detail layout",
                    Objects.requireNonNull(cause, "cause must not be null"));
            this.recordOrdinal = recordOrdinal;
        }

        /**
         * Returns the one-based position of the refused record in the extract.
         *
         * @return the record's ordinal position
         */
        public int getRecordOrdinal() {
            return this.recordOrdinal;
        }
    }
}
