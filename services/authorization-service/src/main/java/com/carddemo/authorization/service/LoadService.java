package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.mapper.PendingAuthSummaryMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.PackedDecimalCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * two files by hand. Refusing names the account on the first such record, which is the difference between
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
 * neither which record nor which account. The explicit check is what lets the failure carry the key.
 *
 * <h2>Two adjacent conditions the reference source also passes over</h2>
 *
 * <p>Refactoring Rationale: <strong>D-LOAD-PREFIX-REFUSED.</strong> A child record whose six-byte prefix
 * does not decode is refused here, naming its ordinal position in the file. {@code 3000-READ-CHILD-SEG-FILE} guards the insert with
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
 * <p>Trade-offs: the stream is drained whole and divided by the stride before any record is decoded,
 * rather than being read one record at a time. Reading one at a time would hold only one record in memory;
 * draining holds the whole extract. Draining is chosen for a diagnostic reason that record-at-a-time
 * reading cannot match. A length remainder is the symptom of one of these two files being handed to the
 * other's reader, and the strides are a hundred and two hundred and six -- so the child file divided by
 * the root stride leaves two whole records and a six-byte remainder. Read one at a time, those two records
 * would be DECODED first, and a child record's bytes read against the summary layout fail somewhere in the
 * middle of a packed money field: the run would then report a malformed field rather than the mismatched
 * file that actually caused it. Resolving the length first means the stride mismatch is what gets reported,
 * before a single row is written. The memory cost is bounded by the same transaction that already holds
 * every entity the load produces, and the entities are the larger half: a hundred or two hundred and six
 * bytes of image against a persistent object.
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
     * The summary rows, probed for an existing row and inserted when absent.
     */
    private final PendingAuthSummaryRepository summaries;

    /**
     * The authorization rows, probed for an existing row and inserted when absent.
     */
    private final PendingAuthDetailRepository details;

    /**
     * Builds the loader over the two repositories it writes.
     *
     * @param summaries the summary repository the root images are loaded into; must not be {@code null}
     * @param details the authorization repository the child records are loaded into; must not be
     *     {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public LoadService(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.details = Objects.requireNonNull(details, "details must not be null");
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
    @Transactional
    public LoadOutcome load(InputStream rootImages, InputStream childRecords) {
        Objects.requireNonNull(rootImages, "rootImages must not be null");
        Objects.requireNonNull(childRecords, "childRecords must not be null");

        LoadOutcome roots = loadSummaries(rootImages);
        LoadOutcome children = loadDetails(childRecords);
        LoadOutcome total = roots.combinedWith(children);

        LOG.info("extract load complete roots={} children={} read={} inserted={} alreadyPresent={}",
                roots.read(), children.read(), total.read(), total.inserted(),
                total.alreadyPresent());
        return total;
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
    @Transactional
    public LoadOutcome loadSummaries(InputStream rootImages) {
        Objects.requireNonNull(rootImages, "rootImages must not be null");

        int read = 0;
        int inserted = 0;
        int alreadyPresent = 0;

        for (byte[] image : records(rootImages, PendingAuthSummaryMapper.unloadRecordLength(),
                "summary")) {
            read++;
            PendingAuthSummary summary = PendingAuthSummaryMapper.fromExtractRecord(image);
            Long accountId = summary.getAccountId();

            // WHY : Assumptions: an existing row is skipped rather than replaced, transcribing the
            //       duplicate-status arm at L256 to L258, and the probe runs before the save rather than
            //       the save being attempted and its failure inspected. The probe reads correctly inside
            //       this transaction because an existence query flushes the transaction's own pending
            //       inserts first, so a root repeated within one file is recognised on its second
            //       occurrence and not only on a later run.
            if (this.summaries.existsById(accountId)) {
                alreadyPresent++;
                LOG.info("summary already present, skipped accountId={} recordOrdinal={}",
                        accountId, read);
                continue;
            }
            this.summaries.save(summary);
            inserted++;
        }
        return new LoadOutcome(read, inserted, alreadyPresent);
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
     *     if a record is malformed for the detail layout
     * @throws ArithmeticException if a decoded key component or amount exceeds the range its column holds
     * @throws MalformedParentKeyException if a record's six-byte prefix does not decode
     * @throws UnresolvedParentException if a record names an account with no summary row
     */
    @Transactional
    public LoadOutcome loadDetails(InputStream childRecords) {
        Objects.requireNonNull(childRecords, "childRecords must not be null");

        int read = 0;
        int inserted = 0;
        int alreadyPresent = 0;

        for (byte[] record : records(childRecords, PendingAuthDetailMapper.unloadRecordLength(),
                "prefixed detail")) {
            read++;
            PendingAuthDetail detail = decodeChild(record, read);
            PendingAuthDetailKey key = detail.getId();
            requireParent(key.getAccountId(), read);

            // WHY : Assumptions: an existing row is skipped rather than replaced, transcribing the child
            //       duplicate arm at L329 to L331, and the whole key is probed rather than the account
            //       alone. The key is the account together with the two stored complements, so an account
            //       that already has authorizations still admits a new one; probing by account would
            //       report every child of a loaded parent as a duplicate.
            if (this.details.existsById(key)) {
                alreadyPresent++;
                LOG.info("authorization already present, skipped accountId={} authDate={} authTime={}"
                        + " recordOrdinal={}", key.getAccountId(), key.getAuthDate(),
                        key.getAuthTime(), read);
                continue;
            }
            this.details.save(detail);
            inserted++;
        }
        return new LoadOutcome(read, inserted, alreadyPresent);
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
     * @throws IllegalArgumentException if the embedded segment is malformed for the detail layout, which
     *     includes a stored complement out of range and a match status an insert cannot originate
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
        return PendingAuthDetailMapper.toEntity(PendingAuthDetailMapper.unloadedSegment(record),
                accountId);
    }

    /**
     * Refuses a child record whose parent summary is absent, naming the account it could not resolve.
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
     * @param accountId the account the record's prefix named; never {@code null}
     * @param ordinal the one-based position of the record in the extract, used to identify it
     * @throws UnresolvedParentException if no summary row exists for that account
     */
    private void requireParent(Long accountId, int ordinal) {
        if (this.summaries.existsById(accountId)) {
            return;
        }
        // WHY : Assumptions: the refusal is LOGGED as well as raised, and the two are not redundant. The
        //       raise ends this transaction, so an orchestrator sees a failed task; the log line is what
        //       survives in the job log alongside the records that loaded before it, which is the record
        //       an operator reconciles the two extract files against. The reference program's own
        //       intended diagnostic at L311 to L312 was a pair of writes for the same reason.
        LOG.error("authorization refused, its account has no summary row accountId={}"
                + " recordOrdinal={}", accountId, ordinal);
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
     * than transcribed, and this is where the non-termination hazard described on this class is closed. The reference status tests each have a
     * branch that neither sets an end flag nor abends -- <strong>L235</strong> for the root file and
     * <strong>L287</strong> for the child file -- reached from any status that is neither success nor
     * end-of-file, and returning from it leaves the enclosing {@code PERFORM ... UNTIL} at L178 or L181
     * with an unchanged flag and an unchanged file position. Resolving the whole stream once removes the
     * possibility: after this method returns the element count is settled, and a caller's walk of it
     * cannot be extended by anything a record contains.
     *
     * <p>Assumptions: a remainder is a refusal rather than a partial last record. It means the file was
     * produced against a different layout, or was truncated in transit, and either way every field offset
     * in that remainder is wrong -- so accepting it would store plausible values in the wrong columns
     * instead of reporting a fault. The message names the stride, because the likeliest cause is one of
     * the two files being handed to the other's reader.
     *
     * @param stream the extract to read; never {@code null}
     * @param stride the declared record length in bytes
     * @param description the record kind, used to identify the layout in a refusal
     * @return each record in file order, as a newly allocated array of exactly {@code stride} bytes;
     *     never {@code null}
     * @throws UncheckedIOException if the stream cannot be read
     * @throws IllegalArgumentException if the stream length is not a whole multiple of {@code stride}
     */
    private static List<byte[]> records(InputStream stream, int stride, String description) {
        byte[] all;
        try {
            // WHY : Assumptions: the whole stream is drained with one call that is specified to read
            //       until the end rather than with a single read into a buffer. A stream may return fewer
            //       bytes than asked for without having ended, so treating one short return as the end
            //       would silently truncate a load whose source is a network or a compressed file.
            all = stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "the " + description + " extract could not be read", unreadable);
        }
        if (all.length % stride != 0) {
            throw new IllegalArgumentException("a " + description + " extract must hold a whole number"
                    + " of " + stride + "-byte records but held " + all.length + " bytes");
        }
        List<byte[]> split = new ArrayList<>(all.length / stride);
        for (int offset = 0; offset < all.length; offset += stride) {
            byte[] record = new byte[stride];
            System.arraycopy(all, offset, record, 0, stride);
            split.add(record);
        }
        return split;
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
     * registered in {@code docs/architecture/cobol-to-service-traceability.md}. It carries the account it
     * could not resolve and the record's position in the extract, so the failure names the data rather
     * than only the fact of failing.
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
         * <p>Assumptions: retained as a field and not only formatted into the message, so a caller can
         * act on it -- reporting which summaries an operator has to load -- without parsing text.
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
            super("record " + recordOrdinal + " of the prefixed detail extract names account "
                    + Objects.requireNonNull(accountId, "accountId must not be null")
                    + ", which has no pending-authorization summary row; load the summary extract for"
                    + " that account and re-run");
            this.accountId = accountId;
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
}
