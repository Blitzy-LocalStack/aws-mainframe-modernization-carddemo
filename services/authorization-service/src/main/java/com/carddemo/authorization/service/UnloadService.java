package com.carddemo.authorization.service;

import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.mapper.PendingAuthSummaryMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk-exports pending-authorization summary and detail rows to the two extract files a load reads back.
 *
 * <p><strong>Purpose.</strong> Carry across the TWO reference unload programs, which perform the same
 * walk of the same database and differ only in the shape of the child record they emit. Each significant
 * paragraph of both becomes a named method here, so the paragraph-to-method pairs in
 * {@code docs/architecture/cobol-to-service-traceability.md} can be read in either direction. The
 * program each pair belongs to is named, because this one class transcribes two:
 *
 * <ul>
 *   <li>{@code MAIN-PARA} -- {@code cbl/PAUDBUNL.CBL} <strong>L157 to L170</strong> and
 *       {@code cbl/DBUNLDGS.CBL} <strong>L164 to L179</strong> -- becomes
 *       {@link #unload(UnloadForm, OutputStream, OutputStream)}. Both bodies are one
 *       {@code PERFORM ... UNTIL WS-END-OF-ROOT-SEG = 'Y'} over the root walk, at PAUDBUNL L163 to
 *       L164 and DBUNLDGS L172 to L173 respectively.</li>
 *   <li>{@code 2000-FIND-NEXT-AUTH-SUMMARY} -- PAUDBUNL <strong>L207 to L247</strong> and DBUNLDGS
 *       <strong>L216 to L257</strong> -- becomes {@link #writeRoot(UnloadForm, OutputStream,
 *       OutputStream, PendingAuthSummary)} together with the walk that drives it. PAUDBUNL moves the
 *       whole hundred-byte root at its L227 and writes it at its L233; DBUNLDGS moves it at its L236
 *       and reaches the same image through {@code 3100-INSERT-PARENT-SEG-GSAM}.</li>
 *   <li>The numeric guard on the account identifier -- PAUDBUNL <strong>L232</strong> and DBUNLDGS
 *       <strong>L241</strong> -- becomes {@link #exportableAccountId(PendingAuthSummary, int)}. It is
 *       the one branch of these programs that decides whether a root is emitted at all, which is why
 *       it is a method of its own rather than a condition inside the walk.</li>
 *   <li>{@code 3000-FIND-NEXT-AUTH-DTL} -- PAUDBUNL <strong>L253 to L284</strong> and DBUNLDGS
 *       <strong>L263 to L295</strong> -- becomes {@link #writeChildren(UnloadForm, OutputStream,
 *       Long)}. Both repeat a get-next-within-parent until the parent's children are exhausted, which
 *       each detects from a segment-not-found status at PAUDBUNL L273 and DBUNLDGS L284.</li>
 *   <li>{@code 3100-INSERT-PARENT-SEG-GSAM} at DBUNLDGS <strong>L300 to L315</strong> and
 *       {@code 3200-INSERT-CHILD-SEG-GSAM} at DBUNLDGS <strong>L319 to L334</strong> become
 *       {@link #rootRecord(PendingAuthSummary)} and {@link #childRecord(UnloadForm,
 *       PendingAuthDetail)}. Those two paragraphs exist only in the sequential program, and they are
 *       the whole of the difference between the two forms.</li>
 *   <li>{@code 1000-INITIALIZE} -- PAUDBUNL <strong>L173 to L200</strong> and DBUNLDGS
 *       <strong>L182 to L194</strong> -- and {@code 4000-FILE-CLOSE} -- PAUDBUNL <strong>L289 to
 *       L304</strong> and DBUNLDGS <strong>L338 to L339</strong> -- have no method of their own. Both
 *       open and close the two output files, and this class receives streams a caller already owns.
 *       {@code 9999-ABEND} -- PAUDBUNL <strong>L308 to L314</strong>, which sets return code 16 at its
 *       L313, and DBUNLDGS <strong>L357 to L363</strong>, which sets it at its L362 -- becomes a
 *       propagated exception.</li>
 * </ul>
 *
 * <p>Every citation is relative to {@code app/app-authorization-ims-db2-mq/}, which is reference
 * material this migration reads and never modifies. Its line numbers are read with columns 73 to 80
 * stripped, because all three of that directory's upper-case {@code .CBL} members carry eight-digit
 * legacy sequence numbers there; a paragraph-label search that does not strip them returns nothing at
 * all. The same stripping is applied to the job streams, two of which carry sequence numbers inside
 * continuation lines -- {@code jcl/UNLDPADB.JCL} at its L50, L51, L55 and L56.
 *
 * <h2>Two export shapes, and why the prefixed one is the default</h2>
 *
 * <p>Assumptions: shape and stride. The PREFIXED form writes a hundred-byte root and a child of two
 * hundred and six bytes, being a six-byte packed parent key ahead of the two-hundred-byte segment;
 * {@code cbl/PAUDBUNL.CBL} declares that record at its <strong>L45 to L48</strong> as
 * {@code 05 ROOT-SEG-KEY PIC S9(11) COMP-3} followed by {@code 05 CHILD-SEG-REC PIC X(200)}. The
 * SEQUENTIAL form writes the same hundred-byte root and a BARE two-hundred-byte child with no prefix.
 * The root record is identical between the two forms, and that is the point rather than a coincidence:
 * both programs move the whole summary segment in one statement and neither adds anything to it,
 * because a root's first six bytes already ARE its key.
 *
 * <p>Assumptions: the LIVE authority for the sequential form's two hundred bytes is the database
 * description and the insert operands, and NOT the record group that appears in
 * {@code cbl/DBUNLDGS.CBL}. That program's {@code FILE SECTION} is commented out at its
 * <strong>L42 to L48</strong> and both of its {@code WRITE} statements are commented out at its
 * <strong>L242</strong> and <strong>L281</strong>, so the two-hundred-and-six-byte group surviving at
 * its <strong>L53 to L56</strong> is working storage that nothing writes -- it is used as a DL/I
 * input-output area and never as a record. What the program actually emits is two sequential inserts,
 * {@code 3100-INSERT-PARENT-SEG-GSAM} at its <strong>L302 to L304</strong> and
 * {@code 3200-INSERT-CHILD-SEG-GSAM} at its <strong>L321 to L323</strong>, each passing the SEGMENT
 * alone. The declared geometry is {@code ims/PASFLDBD.DBD} <strong>L27</strong>,
 * {@code RECORD=(100),RECFM=F}, and {@code ims/PADFLDBD.DBD} <strong>L27</strong>,
 * {@code RECORD=(200),RECFM=F}. The chain from job to declaration is complete and checkable: the
 * sequential job's data-definition names {@code PASFILOP} at {@code jcl/UNLDGSAM.JCL}
 * <strong>L36</strong> and {@code PADFILOP} at its <strong>L39</strong> are exactly the {@code DD2=}
 * operands of those two descriptions. Reading the commented-out group as the contract instead yields
 * two hundred and six where the truth is two hundred, and a consumer dividing that file by two hundred
 * and six would report a remainder on a file that is not malformed.
 *
 * <p>Alternatives Considered: making the SEQUENTIAL form the default, on the ground that its child
 * record is the segment exactly and therefore the simpler shape. Rejected on three findings that the
 * job streams and the database descriptions settle between them, each of which distinguishes the two
 * forms concretely rather than by preference.
 *
 * <ul>
 *   <li><strong>Only the prefixed form closes a round trip.</strong> A census of every dataset name in
 *       the module resolves this. {@code AWS.M2.CARDDEMO.PAUTDB.ROOT.GSAM} and
 *       {@code ...CHILD.GSAM} appear at {@code jcl/UNLDGSAM.JCL} <strong>L36</strong> and
 *       <strong>L39</strong> and NOWHERE else -- no job in the module reads either back. The prefixed
 *       pair {@code ...ROOT.FILEO} and {@code ...CHILD.FILEO} is written by {@code jcl/UNLDPADB.JCL}
 *       at its <strong>L48</strong> and <strong>L53</strong> and read back by
 *       {@code jcl/LOADPADB.JCL} as {@code INFILE1} at its <strong>L36</strong> and {@code INFILE2} at
 *       its <strong>L38</strong>. So the sequential form is terminal and the prefixed form is the one
 *       {@link LoadService} restores from; defaulting to the terminal form would hand an operator, by
 *       default, a file pair nothing in this migration can load.</li>
 *   <li><strong>Only the prefixed form provisions its own output.</strong> {@code jcl/UNLDPADB.JCL}
 *       declares both outputs {@code DISP=(NEW,CATLG,DELETE)} with {@code UNIT=3390} and
 *       {@code SPACE=(400,(20,20),RLSE)} at its <strong>L49 to L51</strong> and <strong>L54 to
 *       L56</strong>. {@code jcl/UNLDGSAM.JCL} declares {@code DISP=(OLD,KEEP,KEEP)} at its
 *       <strong>L37</strong> and <strong>L40</strong> with no unit, no space and no device
 *       characteristics at all, and {@code OLD} requires the dataset to exist already -- so the
 *       sequential unload cannot run on a system where its two datasets have not been allocated by
 *       something else.</li>
 *   <li><strong>Only the prefixed form is repeatable by construction.</strong> Its job is the module's
 *       ONE multi-step stream: {@code STEP0 EXEC PGM=IEFBR14} at {@code jcl/UNLDPADB.JCL}
 *       <strong>L25</strong> deletes both outputs with {@code DISP=(OLD,DELETE,DELETE)} at its
 *       <strong>L33 to L36</strong> before the unload step runs. The sequential job has no such step,
 *       so a second run appends to whatever the first left behind.</li>
 * </ul>
 *
 * <p>Trade-offs: a fourth and fifth discriminator are recorded rather than used, because they describe
 * the two forms without deciding between them. The six-byte key prefix is present in one and absent in
 * the other, which is the difference this class parameterises. And the two forms declare their geometry
 * in DIFFERENT PLACES -- the prefixed form in the job stream, {@code RECFM=FB} at
 * {@code jcl/UNLDPADB.JCL} <strong>L50</strong> and <strong>L55</strong>, and the sequential form in
 * the database description, {@code RECFM=F} at both descriptions' <strong>L27</strong>, its job stream
 * carrying no device characteristics whatever. That is what "self-describing against order-dependent"
 * amounts to concretely: a reader holding the prefixed file can recover the stride and the parent of
 * every record from the file and its job; a reader holding the sequential file can recover neither
 * without the description, and can recover the parent from nothing at all.
 *
 * <p>Assumptions: the record geometry of the round trip is declared in exactly ONE place and this class
 * preserves that property. {@code jcl/UNLDPADB.JCL} carries {@code LRECL=100} at its
 * <strong>L50</strong> and {@code LRECL=206} at its <strong>L55</strong>, and
 * {@code jcl/LOADPADB.JCL} reads the same two datasets with NO device characteristics of its own. The
 * target keeps the single source by taking every stride from the layout descriptors the two mappers
 * own -- {@link PendingAuthSummaryMapper#unloadRecordLength()} and
 * {@link PendingAuthDetailMapper#unloadRecordLength()} -- so this exporter and {@link LoadService}
 * cannot disagree about a length, and neither states one.
 *
 * <p>Assumptions: the two output streams correspond to the data-definition names {@code OUTFIL1} and
 * {@code OUTFIL2}, and the distinction from the two file names beside them is worth stating because
 * confusing the pair misroutes a file with no error. {@code cbl/PAUDBUNL.CBL} <strong>L26</strong> and
 * <strong>L32</strong> read {@code SELECT OPFILE1 ASSIGN TO OUTFIL1} and
 * {@code SELECT OPFILE2 ASSIGN TO OUTFIL2}: {@code OPFILE1} and {@code OPFILE2} are the program's
 * internal file names, whose records {@code OPFIL1-REC} and {@code OPFIL2-REC} this class's method
 * documentation cites, while {@code OUTFIL1} and {@code OUTFIL2} are the assignment targets the job
 * stream binds at {@code jcl/UNLDPADB.JCL} <strong>L48</strong> and <strong>L53</strong>. The loader's
 * counterparts are {@code INFILE1} and {@code INFILE2} at {@code jcl/LOADPADB.JCL} L36 and L38. Only the
 * assignment targets appear in a job stream, so a caller wiring this export to two destinations matches
 * its first argument to {@code OUTFIL1} and its second to {@code OUTFIL2}; matching against the internal
 * file names instead names something the job stream never mentions, and the two orderings are
 * indistinguishable once the streams are open.
 *
 * <h2>There are exactly two export shapes</h2>
 *
 * <p>Assumptions: {@code jcl/DBPAUTP0.jcl} is a platform-utility job and RETIRES WITH NO TARGET in
 * this module, so a third export shape does not exist and must not be inferred. Its
 * <strong>L16</strong> is {@code PARM=(ULU,DFSURGU0,DBPAUTP0)}, the vendor's own reorganisation-unload
 * utility in utility-unload mode, and it names no application program at all. Its output at its
 * <strong>L25 to L29</strong> is a variable-blocked dump of {@code LRECL=27990}, which is none of the
 * hundred, two hundred and two hundred and six byte fixed-length application records the two forms
 * above declare; it is also the only job in the module registered with the database recovery control
 * datasets, at its <strong>L40 to L42</strong>, and the only one carrying a utility control statement,
 * at its <strong>L34 to L35</strong>. Its migrated analogue is the managed backup and snapshot configuration in
 * {@code infra/modules/aurora-postgresql}, not a service method. The absence is stated because the
 * arithmetic invites a wrong conclusion: the module holds FIVE job streams beside THREE load and
 * unload programs, and a reader reconciling those two counts could reasonably invent a third shape
 * from the surplus. There are two.
 *
 * <h2>Read-only, batch-exclusive, and unparameterised</h2>
 *
 * <p>Assumptions: this export WRITES NOTHING, and four independent readings of the reference material
 * agree on it, which is why the boundary below is declared read-only rather than merely left without
 * writes. First, both unload access specifications are get-only: {@code ims/PAUTBUNL.PSB}
 * <strong>L18</strong> and {@code ims/DLIGSAMP.PSB} <strong>L18</strong> each declare
 * {@code PROCOPT=GOTP} on the database access block. Second, both jobs run batch-exclusive rather than
 * inside the online region -- {@code jcl/UNLDPADB.JCL} <strong>L38 to L39</strong> is
 * {@code PARM='DLI,PAUDBUNL,PAUTBUNL,,,,,,,,,,,N'} and {@code jcl/UNLDGSAM.JCL} <strong>L26 to
 * L27</strong> is {@code PARM='DLI,DBUNLDGS,DLIGSAMP,,,,,,,,,,,N'}. Third, the access specification
 * names differ BY MODE: the update-capable {@code PSBPAUTB}, which {@code ims/PSBPAUTB.psb}
 * <strong>L17</strong> declares {@code PROCOPT=AP}, is what the load and purge jobs pass, while the
 * two unloads pass the read-only {@code PAUTBUNL} and {@code DLIGSAMP}. Fourth, both unload jobs carry
 * ACTIVE database data-definition statements -- {@code jcl/UNLDPADB.JCL} <strong>L58 to L59</strong>
 * and {@code jcl/UNLDGSAM.JCL} <strong>L42 to L43</strong> -- where the load job's equivalents at
 * {@code jcl/LOADPADB.JCL} <strong>L40 to L41</strong> are commented out, which is the signature of a
 * job holding the database itself rather than reaching it through the online region.
 *
 * <p>Assumptions: this class is therefore a batch entry point and does not run alongside online
 * traffic, where {@link LoadService} and {@link PurgeJob} both ran in the online region's message
 * mode and MAY. The consequence for this class is only that its reads see a database no online
 * transaction is changing under it, so it needs nothing to hold that state still.
 *
 * <p>Assumptions: no lock mode is declared and no locking scheme is introduced, because the reference
 * material has none to carry across. {@code cpy/IMSFUNCS.cpy} declares three get-hold function codes
 * and no reference program in this module passes any of them, so there is nothing here for a
 * pessimistic declaration to be the migrated form OF. A read-only export has no row to protect in any
 * case: it changes nothing, so nothing it reads can be lost by another writer proceeding.
 *
 * <p>Assumptions: this takes NO job parameters, because neither reference job supplies any. Neither
 * unload stream has a {@code SYSIN} data definition -- across the module's five streams only the purge
 * job does -- and {@code PRM-INFO}, declared at {@code cbl/PAUDBUNL.CBL} <strong>L119</strong> and
 * {@code cbl/DBUNLDGS.CBL} <strong>L123</strong>, is read by a statement that is COMMENTED OUT in both
 * programs, at their <strong>L179</strong> and <strong>L188</strong> respectively, and is referenced
 * nowhere else in either procedure division. Inventing a parameter -- an as-of date, a batch size, an
 * account range -- would publish a control this job never had and would have to be honoured forever
 * after.
 *
 * <p>Assumptions: no date is read and none is accepted. The two clock reads at {@code cbl/PAUDBUNL.CBL}
 * <strong>L176 to L177</strong> and {@code cbl/DBUNLDGS.CBL} <strong>L185 to L186</strong> feed a
 * single display of the date at their L182 and L191 and are stored nowhere. An export whose content
 * depended on the clock would not be reproducible; this one has no clock-dependent content to make
 * reproducible.
 *
 * <h2>What this class deliberately does not have</h2>
 *
 * <p>Assumptions: no condition-code translation applies to either job, and the absence is stated
 * outright because this migration treats condition-code inversion as a headline hazard and warns that
 * the step-gating parameter and the record-selecting {@code INCLUDE} predicate share a keyword. All
 * five job streams in {@code app/app-authorization-ims-db2-mq/jcl/} were read whole with columns 73 to
 * 80 stripped: there is NO {@code COND=} of either form, no job-level {@code IF}, {@code THEN} or
 * {@code ELSE}, no {@code RESTART=} and no {@code CHKPT=}. Even the prefixed form's two-step stream has
 * no gate between its delete step and its unload step, so every step edge in this module is the
 * unconditional success edge. A reader looking for a predicate to invert here is looking for something
 * that does not exist.
 *
 * <p>Assumptions: no retry is applied, and were one ever added its condition list would have to be
 * narrow, because the reference source fixes the boundary. Its transient set is exactly three
 * infrastructure statuses, {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} at
 * {@code app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl} <strong>L87</strong>, declared identically
 * at {@code cbl/PAUDBUNL.CBL} L105 and {@code cbl/DBUNLDGS.CBL} L109. The statuses NOT in that set are
 * the ones a retry must leave alone: the segment-not-found, duplicate, wrong-parentage and
 * end-of-database statuses declared beside it describe the DATA rather than the platform, and two of
 * them are load-bearing control values in the walks above -- end-of-database ends the root walk and
 * segment-not-found ends a parent's children -- so retrying either would repeat a decided outcome or
 * re-read an exhausted parent. Neither condition name is referenced in either program's procedure
 * division, so no attempt sequence is transcribed.
 *
 * <p>Alternatives Considered: adopting a resilience library to express that retry. Rejected: retry is
 * in the Spring Framework core that the Spring Boot parent brings in, so a library would be a second
 * retry authority competing with the durable tier that actually covers this path -- per-state retry in
 * the batch orchestrator, described in {@code docs/adr/ADR-005-batch-orchestration.md}, which survives
 * the task being replaced mid-work where no in-process loop can. Two core API details are recorded
 * because both are easy to get wrong at a use site: the annotation attribute is {@code maxRetries} and
 * NOT {@code maxAttempts}, so the total number of attempts is one plus that value and defaults to
 * three, and the enabling annotation is {@code @EnableResilientMethods} and NOT {@code @EnableRetry}.
 * No circuit breaker is configured either. The decision of record is
 * {@code docs/adr/ADR-002-compute-platform.md}.
 *
 * <p>Assumptions: this is NOT a chunk-oriented batch job and acquires no job repository. It is an entry
 * point the batch orchestrator invokes as a task, per {@code docs/adr/ADR-005-batch-orchestration.md};
 * the batch starter and its restart repository are scoped to the batch context, whose module declares
 * them, and this module's own build declares no batch starter of any kind.
 *
 * <h2>Divergence D-UNLOAD-SKIP-REPORTED: an unattributable root is reported</h2>
 *
 * <p>Assumptions: the identifier is DESCRIPTIVE rather than a letter, and the choice is deliberate
 * because the letter sequence is already spoken for. {@code D-D} names the receive-failure difference
 * in {@link AuthorizationRequestListener}, cited under that letter by both of this service's test
 * package charters, so a second difference under the same letter would make every citation of it
 * ambiguous in the one direction a reviewer reconciling code against the register searches.
 *
 * <p>Refactoring Rationale: <strong>D-UNLOAD-SKIP-REPORTED.</strong> A summary row that carries no account identifier is
 * skipped here exactly as the reference programs skip it, and the skip is COUNTED on the returned
 * outcome and reported on the log, where the reference programs report nothing. The guard is
 * {@code IF PA-ACCT-ID IS NUMERIC} at {@code cbl/PAUDBUNL.CBL} <strong>L232</strong> and
 * {@code cbl/DBUNLDGS.CBL} <strong>L241</strong>; its false branch reaches the {@code END-IF} at their
 * L237 and L247 having written no root and, because the child walk is performed INSIDE the guard at
 * their L235 and L245, having read no child either. Both halves of that are reproduced: a skipped root
 * emits nothing and its children are never read. What is added is the report. The divergence is
 * registered in {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Alternatives Considered for D-UNLOAD-SKIP-REPORTED: reproducing the silence exactly, so that a
 * skipped root left no
 * trace of any kind. Rejected because the two outcomes differ in what an operator can conclude from a
 * completed run. In silence, the extract simply has fewer roots than the table has rows, and nothing
 * in the run says which rows are missing or that any are; the shortfall is discoverable only by
 * counting the file against the table afterwards, and a load of that extract then succeeds, because
 * every record it does contain is well formed. With the count on the outcome, a caller comparing
 * {@link UnloadOutcome#rootsWritten()} against {@link UnloadOutcome#rootsSkipped()} sees the shortfall
 * in the result it already has, and the log names the point in the walk to look at. Raising instead was
 * also considered and rejected: refusing would abandon an export over a row the schema cannot produce,
 * and would lose the roots already written for no gain in what the operator learns.
 *
 * <p>Assumptions for D-UNLOAD-SKIP-REPORTED: the condition is unreachable through the schema, and
 * reproducing the guard is
 * a statement about the walk rather than a live code path.
 * {@code db/migration/V1__authorization.sql} declares {@code account_id BIGINT NOT NULL} as
 * {@code pending_auth_summary}'s whole primary key, so no stored row can lack one. The guard is
 * reproduced so that a reader comparing this walk against either reference program finds the same
 * structure and the same branch, and so that a summary reaching this class from somewhere other than
 * that table -- an unsaved instance, a projection assembled by a caller -- is handled by a stated
 * decision rather than by whatever a dereference happens to do.
 *
 * <h2>Trade-offs accepted</h2>
 *
 * <p>Trade-offs: the root walk is PAGED where the reference walk holds one root at a time, so this
 * class holds up to a page of summaries and one account's children at once. The page size is a batch
 * size and not a contract: the walk resumes from the last key it returned, so no page boundary is
 * observable in the output, and a reader must not depend on the figure. What is bought is one round
 * trip per page instead of one per root; what is given up is a working set proportional to the page
 * rather than to a single row.
 *
 * <p>Trade-offs: the export is written row by row to the two streams as the walk proceeds, rather than
 * assembled and then written. A failure part-way therefore leaves both files partially written, which
 * the reference programs also do -- they write as they walk and their abend path at PAUDBUNL L308 and
 * DBUNLDGS L357 closes nothing. Buffering both files entirely would make a failure leave nothing
 * behind, at the cost of holding the whole export in memory, and the prefixed form's own job stream
 * already supplies the recovery the buffering would be for: its delete step removes both outputs
 * before each run, so a re-run starts from an empty pair whatever the previous one left.
 *
 * <p>Parity honesty: no golden master exists for any path in this module, so nothing here is verified
 * against a recorded reference output. Parity rests on the copybook, database-description and job-stream
 * geometry contracts, on the transcribed walk above, and on the committed extract fixtures that state
 * both strides -- which is what the verification section of the divergence register records rather than
 * claiming an oracle this module does not have.
 */
@Service
public class UnloadService {

    /**
     * The log the skipped-root reports are written to.
     *
     * <p>Assumptions: the reference programs report the progress of a run to the job log with
     * {@code DISPLAY} -- {@code cbl/PAUDBUNL.CBL} does so at its L180 to L183, its L276 to L277 and its
     * L290 -- and an operator reads that log to decide whether a run did what it claimed. A structured
     * log is that artifact's equivalent, because a container's standard output is not addressable per
     * record.
     *
     * <p>Assumptions: nothing logged here came off a message queue, so the package-level prohibition on
     * logging wire values is not narrowed by these lines. The only value reported is the account
     * identifier the walk is resuming from, and the primary account number the detail segment carries is
     * never among them.
     */
    private static final Logger LOG = LoggerFactory.getLogger(UnloadService.class);

    /**
     * The lowest account identifier the walk can start below.
     *
     * <p>Assumptions: zero, and the walk seeks strictly ABOVE it, so the first page begins at the lowest
     * real account. {@code db/migration/V1__authorization.sql} declares the column a positive account
     * identifier and the reference programs guard it with a numeric test, so no stored account can be at
     * or below zero and none can be skipped by opening the walk here. The alternative of opening with an
     * absent value would need a second query or a null-tolerant predicate, and a comparison against an
     * absent value matches no row at all in SQL -- which would end the walk before it began.
     */
    private static final long BEFORE_FIRST_ACCOUNT = 0L;

    /**
     * The number of summary rows fetched per step of the outer walk.
     *
     * <p>Assumptions: the walk is PAGED rather than materialising every summary at once, because the
     * reference walk holds exactly one root at a time and an export must not need memory proportional to
     * the database. Two hundred is a batch size and not a contract, for the reason the class-level
     * trade-off records.
     */
    private static final int SUMMARY_PAGE_SIZE = 200;

    /**
     * The export shape used when a caller names none.
     *
     * <p>Assumptions: it is the PREFIXED form, for the three reasons the class-level alternative
     * records, and it is published as a constant so that a caller can assert the default without
     * repeating the choice. Reading it is how a test states that the default IS this form rather than
     * that a particular call happens to produce it.
     */
    public static final UnloadForm DEFAULT_FORM = UnloadForm.PREFIXED;

    /**
     * The summary rows, walked in ascending key order.
     */
    private final PendingAuthSummaryRepository summaries;

    /**
     * The authorization rows, walked beneath each summary in the reference order.
     */
    private final PendingAuthDetailRepository details;

    /**
     * Builds the exporter over the two repositories it reads.
     *
     * @param summaries the summary repository the outer walk reads; must not be {@code null}
     * @param details the authorization repository the inner walk reads; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    public UnloadService(PendingAuthSummaryRepository summaries,
            PendingAuthDetailRepository details) {
        this.summaries = Objects.requireNonNull(summaries, "summaries must not be null");
        this.details = Objects.requireNonNull(details, "details must not be null");
    }

    /**
     * Writes the whole pending-authorization database in the DEFAULT export shape.
     *
     * <p>Purpose. This is the round-trip path: it emits the file pair {@link LoadService} reads back,
     * which is the prefixed form for the three reasons recorded on this class. A caller that wants the
     * sequential form must name it, through
     * {@link #unload(UnloadForm, OutputStream, OutputStream)}.
     *
     * <p>Alternatives Considered: leaving the form a required argument at every call site, which is
     * what this class offered before this overload existed. Rejected because a required argument makes
     * the two shapes equally weighted at the point of use, and they are not equal: one is read back by
     * a job in this module and the other is read back by nothing at all. A caller that has no view on
     * the question -- an orchestrator step exporting for a later load -- would then have to form one,
     * and half of the ways it could go produce a file pair nothing can restore. Naming the default here
     * makes the terminal form an explicit opt-in and leaves the ordinary path unable to choose it by
     * accident.
     *
     * @param rootFile the stream the summary images are written to; must not be {@code null}
     * @param childFile the stream the authorization records are written to; must not be {@code null}
     * @return the counts of roots written, children written and roots skipped; never {@code null}
     * @throws NullPointerException if either stream is {@code null}
     * @throws UncheckedIOException if either stream cannot be written
     * @throws IllegalStateException if a stored row cannot be encoded into the record its layout
     *     declares
     */
    @Transactional(readOnly = true)
    public UnloadOutcome unload(OutputStream rootFile, OutputStream childFile) {
        return unload(DEFAULT_FORM, rootFile, childFile);
    }

    /**
     * Writes the whole pending-authorization database to a root file and a child file.
     *
     * <p>Purpose. This is {@code MAIN-PARA} -- {@code cbl/PAUDBUNL.CBL} <strong>L157 to L170</strong>
     * and {@code cbl/DBUNLDGS.CBL} <strong>L164 to L179</strong> -- whose body is one
     * {@code PERFORM ... UNTIL} over the root walk followed by the file close.
     *
     * <p>Assumptions: both files are written by ONE walk, interleaved exactly as both reference
     * programs interleave them -- a root is written, then every child beneath it, then the next root.
     * The two files therefore agree on the order of accounts, which is what lets a load read the roots
     * first and the children second and still find each parent present. Walking the two tables
     * independently would produce the same rows in an order nothing guaranteed to agree, and for the
     * sequential form it would destroy the only attribution its child records have.
     *
     * <p>Assumptions: the walk resumes from the HIGHEST identifier the page carried rather than from
     * the last element of it. Ascending order is the query's declared contract, so the two are the same
     * value for any conforming answer; taking the maximum means a page that arrived out of order cannot
     * make the walk revisit rows it has already exported, which a positional read of the last element
     * would.
     *
     * @param form which reference program's record shapes to write; must not be {@code null}
     * @param rootFile the stream the summary images are written to; must not be {@code null}
     * @param childFile the stream the authorization records are written to; must not be {@code null}
     * @return the counts of roots written, children written and roots skipped; never {@code null}
     * @throws NullPointerException if any argument is {@code null}
     * @throws UncheckedIOException if either stream cannot be written
     * @throws IllegalStateException if a stored row cannot be encoded into the record its layout
     *     declares
     */
    @Transactional(readOnly = true)
    public UnloadOutcome unload(UnloadForm form, OutputStream rootFile, OutputStream childFile) {
        Objects.requireNonNull(form, "form must not be null");
        Objects.requireNonNull(rootFile, "rootFile must not be null");
        Objects.requireNonNull(childFile, "childFile must not be null");

        UnloadOutcome outcome = UnloadOutcome.NOTHING;
        long position = BEFORE_FIRST_ACCOUNT;

        while (true) {
            List<PendingAuthSummary> page = this.summaries
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(Long.valueOf(position),
                            Limit.of(SUMMARY_PAGE_SIZE));
            if (page.isEmpty()) {
                return outcome;
            }
            long resumeFrom = position;
            for (PendingAuthSummary summary : page) {
                Long accountId = exportableAccountId(summary, outcome.rootsSkipped());
                if (accountId == null) {
                    outcome = outcome.andRootSkipped();
                    continue;
                }
                // WHY : Assumptions: the resume key is the GREATEST identifier the page carried, not the
                //       last element of it. Ascending order is the query's declared contract, so for any
                //       conforming answer the two are the same value; taking the maximum means an answer
                //       that arrived out of order cannot set the position backwards and make the next
                //       page re-deliver rows this one already exported.
                resumeFrom = Math.max(resumeFrom, accountId.longValue());
                outcome = outcome.andRoot(writeRoot(form, rootFile, childFile, summary, accountId));
            }
            // WHY : Assumptions: a page that yielded no resumable key ENDS the walk, and the guard is
            //       here because without it this loop would not terminate. The page predicate is
            //       strictly greater than the position, so re-issuing it with an unchanged position
            //       returns the same page forever -- and every row of such a page was skipped by the
            //       guard above, so no progress is available from it. The condition cannot arise from
            //       the table, whose key column is declared not null and therefore cannot answer that
            //       predicate at all; it is reachable only from a summary source that is not the table.
            if (resumeFrom == position) {
                LOG.warn("unload walk ended at resumeKey={} because the page it received carried no"
                        + " account identifier to resume from; rootsSkipped={}", Long.valueOf(position),
                        Integer.valueOf(outcome.rootsSkipped()));
                return outcome;
            }
            position = resumeFrom;
        }
    }

    /**
     * Answers the account identifier a root is exportable under, or reports that it has none.
     *
     * <p>Purpose. This is the numeric guard on the account identifier --
     * {@code cbl/PAUDBUNL.CBL} <strong>L232</strong> and {@code cbl/DBUNLDGS.CBL}
     * <strong>L241</strong> -- and it is divergence <strong>D-UNLOAD-SKIP-REPORTED</strong>, described
     * on {@link UnloadService} and registered under that identifier in
     * {@code docs/architecture/cobol-to-service-traceability.md}. The reference guard's false branch
     * emits nothing and reads no child; this returns an absent identifier, which the caller treats the
     * same way, and reports the occurrence.
     *
     * <p>Assumptions: absence of the identifier is the migrated form of failing a numeric test on it.
     * The reference field is a packed eleven-digit value, so the test it fails is one of digit
     * representation; the relational column is an integer whose type admits no non-numeric state, and
     * the only way a summary reaching this method can lack a usable key is for the column to be unset.
     * Testing the value's range or sign instead would invent a condition the reference programs do not
     * test, and testing nothing would leave the branch untranscribed.
     *
     * @param summary the summary row the walk has reached; must not be {@code null}
     * @param alreadySkipped how many roots the run has skipped before this one, reported so the log
     *     line names the position of this occurrence within the run
     * @return the account identifier to export the root under, or {@code null} when the row carries
     *     none and must be skipped
     * @throws NullPointerException if {@code summary} is {@code null}
     */
    private static Long exportableAccountId(PendingAuthSummary summary, int alreadySkipped) {
        Objects.requireNonNull(summary, "summary must not be null");
        Long accountId = summary.getAccountId();
        if (accountId == null) {
            // WHY : Assumptions: the report names the customer identifier and not the account, because
            //       the account is exactly the value that is absent. It is the only other identifier
            //       the summary segment carries -- cpy/CIPAUSMY.cpy declares it at L20 -- so it is the
            //       one handle an operator has on which row was passed over.
            LOG.warn("unload skipped a summary row carrying no account identifier, so neither it nor"
                    + " its authorizations were exported; customerId={} skippedSoFar={}",
                    summary.getCustomerId(), Integer.valueOf(alreadySkipped));
        }
        return accountId;
    }

    /**
     * Writes one root image and every authorization beneath it.
     *
     * <p>Purpose. This is {@code 2000-FIND-NEXT-AUTH-SUMMARY} -- {@code cbl/PAUDBUNL.CBL}
     * <strong>L207 to L247</strong> and {@code cbl/DBUNLDGS.CBL} <strong>L216 to L257</strong> --
     * after its guard has admitted the root. The reference order is preserved: the root is written
     * first, at PAUDBUNL L233 and by way of DBUNLDGS L243, and only then is the child walk performed,
     * at their L235 and L245.
     *
     * <p>Assumptions: the child walk is reached only from here, so a root the guard rejected has its
     * children left unread rather than read and discarded. That is the reference structure exactly --
     * the child {@code PERFORM} sits INSIDE the guard in both programs -- and it also means a rejected
     * root costs no query.
     *
     * @param form which record shape the child file carries; must not be {@code null}
     * @param rootFile the stream the summary image is written to; must not be {@code null}
     * @param childFile the stream the authorization records are written to; must not be {@code null}
     * @param summary the summary row to write; must not be {@code null}
     * @param accountId the identifier the guard admitted this root under; must not be {@code null}
     * @return the number of authorization records written beneath this root
     * @throws UncheckedIOException if either stream cannot be written
     */
    private int writeRoot(UnloadForm form, OutputStream rootFile, OutputStream childFile,
            PendingAuthSummary summary, Long accountId) {
        write(rootFile, rootRecord(summary));
        return writeChildren(form, childFile, accountId);
    }

    /**
     * Encodes one summary row into the root record BOTH forms write.
     *
     * <p>Purpose. This is {@code MOVE PENDING-AUTH-SUMMARY TO OPFIL1-REC} at
     * {@code cbl/PAUDBUNL.CBL} <strong>L227</strong>, whose written record its
     * <strong>L44</strong> declares as {@code 01 OPFIL1-REC PIC X(100)} -- the segment verbatim, with
     * no key prefix -- and the same move at {@code cbl/DBUNLDGS.CBL} <strong>L236</strong> ahead of the
     * sequential insert its {@code 3100-INSERT-PARENT-SEG-GSAM} performs at <strong>L302 to
     * L304</strong>, which passes the summary segment alone.
     *
     * <p>Assumptions: the form does not reach this method, and the omission is the contract rather than
     * an oversight. Both reference programs emit the identical hundred-byte root, so a root encoder
     * that took the form would have a parameter it could not use, and a reader would reasonably infer
     * from its presence that the two forms differ here. They differ only in the child.
     *
     * @param summary the summary row to encode; must not be {@code null}
     * @return the hundred-byte root record; never {@code null}
     */
    private static byte[] rootRecord(PendingAuthSummary summary) {
        // WHY : Alternatives Considered: the mapper's unload entry point is used rather than its
        //       general segment encoder, and rather than this class assembling the record itself. The
        //       mapper owns the layout descriptor that declares the stride, so calling it is what keeps
        //       the geometry single-sourced between this exporter and the loader that reads the file
        //       back; assembling here would make this the second place that knows a summary's shape,
        //       and the two would drift the first time a column was added.
        return PendingAuthSummaryMapper.toUnloadRecord(summary);
    }

    /**
     * Writes every authorization beneath one account in the reference order.
     *
     * <p>Purpose. This is {@code 3000-FIND-NEXT-AUTH-DTL} -- {@code cbl/PAUDBUNL.CBL} <strong>L253 to
     * L284</strong> and {@code cbl/DBUNLDGS.CBL} <strong>L263 to L295</strong> -- which repeats a
     * get-next-within-parent until the parent's children are exhausted.
     *
     * <p>Assumptions: the whole child set of one account is read in one call, because both reference
     * loops run to exhaustion within the parent with no page boundary anywhere in them, and the
     * ordering the read declares is the sequence those loops return. Paging the children would
     * introduce a boundary the reference walk does not have inside a group whose order the output
     * depends on.
     *
     * @param form which record shape the child file carries; must not be {@code null}
     * @param childFile the stream the records are written to; must not be {@code null}
     * @param accountId the account whose authorizations are written; must not be {@code null}
     * @return the number of records written for that account
     * @throws UncheckedIOException if the stream cannot be written
     */
    private int writeChildren(UnloadForm form, OutputStream childFile, Long accountId) {
        List<PendingAuthDetail> children =
                this.details.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(accountId);
        for (PendingAuthDetail child : children) {
            write(childFile, childRecord(form, child));
        }
        return children.size();
    }

    /**
     * Encodes one authorization row into the child record the requested form declares.
     *
     * <p>Purpose. This is the ONE place the two reference programs differ. The prefixed form is
     * {@code WRITE OPFIL2-REC} at {@code cbl/PAUDBUNL.CBL} <strong>L271</strong>, whose record its
     * <strong>L45 to L48</strong> declares as a {@code PIC S9(11) COMP-3} parent key ahead of a
     * {@code PIC X(200)} segment and whose prefix its <strong>L230</strong> populates from the
     * parent's own key. The sequential form is {@code 3200-INSERT-CHILD-SEG-GSAM} at
     * {@code cbl/DBUNLDGS.CBL} <strong>L319 to L334</strong>, whose insert at its <strong>L321 to
     * L323</strong> passes {@code PENDING-AUTH-DETAILS} -- the segment alone.
     *
     * <p>Assumptions: the six-byte prefix is PACKED DECIMAL and is encoded through
     * {@code com.carddemo.common.codec.PackedDecimalCodec}, reached by the mapper's unload entry point,
     * never written as text. The reference declaration is {@code COMP-3}, and the eleven digits of an
     * account identifier occupy six bytes packed against eleven as characters, so a text prefix would
     * be the wrong LENGTH as well as the wrong bytes -- and at the wrong length every subsequent record
     * in the file is misaligned. A load reading a text prefix as packed does not fail either: it
     * decodes to a different account and attributes the authorization to it.
     *
     * @param form which record shape to encode; must not be {@code null}
     * @param child the authorization row to encode; must not be {@code null}
     * @return the child record, of the stride the requested form declares; never {@code null}
     */
    private static byte[] childRecord(UnloadForm form, PendingAuthDetail child) {
        // WHY : Assumptions: the switch is exhaustive over the enumeration and has no default arm, so
        //       adding a third form would fail to compile here rather than silently emitting one of the
        //       two existing shapes under a new name. That matters because the shapes are
        //       indistinguishable to a reader of the file until it is divided by a stride.
        return switch (form) {
            case PREFIXED -> PendingAuthDetailMapper.toUnloadRecord(child);
            case SEQUENTIAL -> PendingAuthDetailMapper.toSegment(child);
        };
    }

    /**
     * Writes one fixed-length record, translating a write failure into an unchecked one.
     *
     * <p>Assumptions: the stream is NOT flushed or closed here. The caller owns it -- it may be a file,
     * a buffer or an object-storage upload -- and closing a stream a caller still intends to write the
     * second half of the export to would end the export half-written with no error. That is also where
     * {@code 1000-INITIALIZE} and {@code 4000-FILE-CLOSE} went in both reference programs.
     *
     * @param target the stream to write to; must not be {@code null}
     * @param record the record bytes to write; must not be {@code null}
     * @throws UncheckedIOException if the stream cannot be written
     */
    private static void write(OutputStream target, byte[] record) {
        try {
            target.write(record);
        } catch (IOException unwritable) {
            // WHY : Assumptions: the failure is re-raised rather than counted, which is the migrated
            //       form of the abend both programs reach on a bad file operation -- PAUDBUNL L308 to
            //       L314 and DBUNLDGS L357 to L363, each setting return code 16. An export that could
            //       not write a record has not exported the database, so continuing would produce a
            //       file whose completeness the returned counts would then misstate.
            throw new UncheckedIOException("an unload record could not be written", unwritable);
        }
    }

    /**
     * Which reference program's record shapes an export writes.
     *
     * <p>Assumptions: the two constants name the reference programs' output SHAPES and not their access
     * methods, which is why neither is called after the storage technology it happened to use. What a
     * consumer needs in order to read the file is the child record's stride, and that is what each
     * constant states. There are exactly two, for the reason the enclosing class records against
     * {@code jcl/DBPAUTP0.jcl}.
     */
    public enum UnloadForm {

        /**
         * The prefixed form: a hundred-byte root and a child prefixed with its packed parent key.
         *
         * <p>Assumptions: this is {@code cbl/PAUDBUNL.CBL}, whose child output record is declared at
         * its <strong>L45 to L48</strong> as a {@code PIC S9(11) COMP-3} parent key ahead of a
         * {@code PIC X(200)} segment, giving two hundred and six bytes. It is the form
         * {@link LoadService} reads back and the value of {@link UnloadService#DEFAULT_FORM}, because
         * the prefix is the only thing that attributes a child to a parent once the two are in flat
         * files.
         */
        PREFIXED,

        /**
         * The sequential form: a hundred-byte root and a bare two-hundred-byte child segment.
         *
         * <p>Assumptions: this is {@code cbl/DBUNLDGS.CBL}, whose two inserts at <strong>L302 to
         * L304</strong> and <strong>L321 to L323</strong> each pass the SEGMENT alone with no prefix,
         * and whose declared record lengths are the {@code RECORD=(100)} and {@code RECORD=(200)}
         * operands at {@code ims/PASFLDBD.DBD} and {@code ims/PADFLDBD.DBD} <strong>L27</strong>. A
         * child written this way cannot be attributed to an account from its own bytes, so a consumer
         * must rely on the interleaved order of the two files. That is why this form is an explicit
         * opt-in and not the default, and why it is kept at all rather than being treated as a subset
         * of the prefixed one.
         */
        SEQUENTIAL
    }

    /**
     * What an export wrote: the root images, the child records, and the roots it passed over.
     *
     * <p>Assumptions: for a run that RETURNS, {@code rootsWritten} plus {@code rootsSkipped} is the
     * number of summary rows the walk reached, and the identity is the point of the shape rather than
     * an accident of it. Every root the walk saw was either exported or reported as unattributable, so
     * there is no third disposition a returned outcome can be hiding -- which is what lets a caller
     * detect divergence <strong>D-UNLOAD-SKIP-REPORTED</strong> from the result it already holds
     * instead of by counting
     * the file against the table.
     *
     * @param rootsWritten the number of summary images written to the root file
     * @param childrenWritten the number of authorization records written to the child file
     * @param rootsSkipped the number of summary rows passed over for carrying no account identifier
     */
    public record UnloadOutcome(int rootsWritten, int childrenWritten, int rootsSkipped) {

        /**
         * The counts of an export that has not yet written anything.
         *
         * <p>Assumptions: this is the walk's opening value and also the whole result of exporting an
         * empty database, and the two coinciding is correct rather than ambiguous -- a walk that
         * reached no root wrote nothing and skipped nothing.
         */
        public static final UnloadOutcome NOTHING = new UnloadOutcome(0, 0, 0);

        /**
         * Adds one exported root and the children written beneath it.
         *
         * @param children the number of authorization records written beneath that root, which must not
         *     be negative
         * @return a new carrier holding the increased counts; never {@code null}
         * @throws IllegalArgumentException if {@code children} is negative
         */
        public UnloadOutcome andRoot(int children) {
            if (children < 0) {
                throw new IllegalArgumentException("children must not be negative but was " + children);
            }
            return new UnloadOutcome(this.rootsWritten + 1, this.childrenWritten + children,
                    this.rootsSkipped);
        }

        /**
         * Adds one root passed over for carrying no account identifier.
         *
         * @return a new carrier holding the increased skip count; never {@code null}
         */
        public UnloadOutcome andRootSkipped() {
            // WHY : Assumptions: no child count is taken, because a skipped root's children are never
            //       READ -- the reference guard encloses the child walk, so there is no number here to
            //       accumulate and an argument for one would suggest otherwise.
            return new UnloadOutcome(this.rootsWritten, this.childrenWritten, this.rootsSkipped + 1);
        }
    }
}
