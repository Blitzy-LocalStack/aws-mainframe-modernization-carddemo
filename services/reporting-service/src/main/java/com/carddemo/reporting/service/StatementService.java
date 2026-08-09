package com.carddemo.reporting.service;

import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.security.OpaqueIdentifier;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.reporting.domain.CardXrefView;
import com.carddemo.reporting.domain.StatementTransactionView;
import com.carddemo.reporting.dto.StatementDocument;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementResponse;
import com.carddemo.reporting.dto.StatementTransactionResponse;
import com.carddemo.reporting.config.ArtifactIdentityConfig;
import com.carddemo.reporting.mapper.StatementHtmlMapper;
import com.carddemo.reporting.mapper.StatementTextMapper;
import com.carddemo.reporting.domain.AccountView;
import com.carddemo.reporting.domain.CustomerView;
import com.carddemo.reporting.repository.StatementAccountRepository;
import com.carddemo.reporting.repository.StatementCardXrefRepository;
import com.carddemo.reporting.repository.StatementCardXrefRepository.StatementHeadingRow;
import com.carddemo.reporting.repository.StatementCustomerRepository;
import com.carddemo.reporting.repository.StatementTransactionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

/**
 * Produces the cardholder statement of {@code app/cbl/CBSTM03A.CBL} in its plain-text and its
 * markup form, and reports what a single card's statement covers.
 *
 * <h2>Purpose</h2>
 *
 * <p>{@code app/cbl/CBSTM03A.CBL} is a 924-line batch program whose file handling is delegated to
 * the 230-line subprogram {@code app/cbl/CBSTM03B.CBL}, whose transaction layout is
 * {@code app/cpy/COSTM01.CPY} at 38 lines, and which the baseline drives from
 * {@code app/jcl/CREASTMT.JCL} at 97 lines. This service encodes that specification. It walks the
 * cross-reference in card order, resolves each card's customer and account, and emits two records
 * streams: the 80-character statement declared {@code FD-STMTFILE-REC PIC X(80)} at L45 and the
 * 100-character markup declared {@code FD-HTMLFILE-REC PIC X(100)} at L47.
 *
 * <p>This class orchestrates and never formats. Band geometry, every declared width, the
 * {@code Z(9).99-} edit mask, the six 80-hyphen rules and every label literal belong to
 * {@code com.carddemo.reporting.mapper}; this class calls that layer and hands each record it
 * returns to the sink it was given. The package charter one level up records why that boundary is
 * not crossed, and {@code LayeringRulesTest} asserts it.
 *
 * <h2>Refactoring Rationale: both declared arities are removed, and the two thresholds they produce
 * are registered separately</h2>
 *
 * <p>This is divergence <b>D-2</b>, and it is the one behavioural divergence this package owns.
 * {@code app/cbl/CBSTM03A.CBL} holds exactly three {@code OCCURS} clauses in its 924 lines, and all
 * three carry different meanings that must never be collapsed into one.
 *
 * <ul>
 *   <li><b>10</b> is the <i>declared</i> inner arity. L228 reads
 *       {@code 10  WS-TRAN-TBL OCCURS 10 TIMES}, nested inside the card table.</li>
 *   <li><b>512</b> is the <i>measured</i> same-card boundary. One card renders 512 transactions and
 *       the 513th overruns the inner table and takes a segmentation fault. {@code tests/README.md}
 *       names that outcome {@code F-STMT-INNER-OVERFLOW} on its L74.</li>
 *   <li><b>51</b> is <i>both declared and measured</i>, for the distinct-card boundary. L226 reads
 *       {@code 05  WS-CARD-TBL OCCURS 51 TIMES} and its parallel counter
 *       {@code 05  WS-TRN-TBL-CTR OCCURS 51 TIMES} stands at L232; 51 distinct cards render and the
 *       52nd overruns the outer table. {@code tests/README.md} names that outcome
 *       {@code F-STMT-OUTER-OVERFLOW} on its L76.</li>
 * </ul>
 *
 * <p><b>Never merge those three numbers.</b> {@code tests/README.md} L70 to L82 establishes that
 * there are two <i>independent</i> unchecked tables, and its own stated reason at L80 to L82 is that
 * a single threshold of 51 would hide the far larger same-card boundary and mislabel the outcome as
 * a transaction-count problem when it is really two separate table overruns. The phrase
 * "~51 transactions" is therefore wrong wherever it appears and must not be written anywhere in this
 * tree except, as here, to warn against writing it. Both thresholds are registered separately, under
 * their own names, in {@code docs/architecture/cobol-to-service-traceability.md}.
 *
 * <p>Refactoring Rationale: the two bounded tables are replaced outright rather than reproduced, so
 * this class declares no arity at all. There is no array sized 10, 51 or 512, no collection sized
 * against any of them, and no configurable ceiling standing in for one: a ceiling read from
 * configuration is still a ceiling, and it would cap a cardholder's statement at a number the
 * business never chose. The two boundaries are consequences of statically sized working storage at
 * L226, L228 and L232 rather than statements about how many transactions a card may carry, so
 * encoding either would carry an implementation artefact into a financial document. The baseline
 * behaves as its tables size it; this service streams and its output has no upper bound; the
 * divergence is documented rather than silent.
 *
 * <p>Assumptions: <b>D-1</b> and <b>D-3</b> are not owned here and this class does not claim them.
 * D-1 concerns the export and import pair and D-3 the interest program's final-account flush; both
 * belong to batch-service, and recording either here would leave that module's register incomplete
 * while making this one appear answerable for behaviour it does not hold.
 *
 * <h2>Refactoring Rationale: a streaming join replaces the 171,156-byte working-storage index</h2>
 *
 * <p>{@code WS-TRNX-TABLE} at L225 to L230 is an in-memory card-grouped index of the transaction
 * input: an outer table of 51 entries at L226, each holding {@code WS-CARD-NUM PIC X(16)} at L227
 * and an inner table of 10 entries at L228 whose members are {@code WS-TRAN-NUM PIC X(16)} at L229
 * and {@code WS-TRAN-REST PIC X(318)} at L230, with the parallel occupancy counter at L232. Those
 * declarations come to 16 + 318 = 334 characters per inner entry, 16 + 10 x 334 = 3,356 per card and
 * 51 x 3,356 = 171,156 characters overall. The inner member is a verbatim record slice: 318 is
 * exactly the width of {@code TRNX-REST} in {@code app/cpy/COSTM01.CPY}, whose members at L25 to L36
 * sum to 318, which is why the table's geometry is a contract rather than an implementation detail.
 *
 * <p>Because that table is pre-loaded, {@code 4000-TRNXFILE-GET} at L416 reads no file at all: L417
 * to L432 scan the table linearly and walk the inner entries of the matching card. Refactoring
 * Rationale: replacing it with a join driven by two ordered cursors removes a whole-input
 * materialisation whose size is the product of two declared arities, which is the same
 * materialisation that produces both overrun boundaries above. One statement's rows are traversed
 * once, forward, and are never accumulated as a group.
 *
 * <h2>Assumptions: the input ordering is a precondition, not a convenience</h2>
 *
 * <p>The scan at L417 to L419 terminates on {@code CR-JMP > CR-CNT} <i>or</i> on
 * {@code WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM}. That second arm is correct only if the table is in
 * card order, and the program never establishes that ordering itself: it is supplied upstream by
 * {@code app/jcl/CREASTMT.JCL} L53, {@code SORT FIELDS=(263,16,CH,A,1,16,CH,A)}, which orders by
 * card ascending and then by transaction identifier ascending. The early exit is therefore a
 * specification rather than an optimisation, and a merge over unordered input would drop
 * transactions exactly as that arm would. Both cursors this class opens carry the matching
 * {@code order by} in the repository that declares them, so the precondition travels with the query
 * and cannot be lost at a call site.
 *
 * <h2>Assumptions: four injected repositories discharge the whole of CBSTM03B</h2>
 *
 * <p>{@code app/cbl/CBSTM03B.CBL} is definitively a subprogram: L114 reads
 * {@code PROCEDURE DIVISION USING LK-M03B-AREA} and {@code app/cbl/CBSTM03A.CBL} calls it from
 * thirteen sites, four opening, four closing and five reading. It owns all four input definitions,
 * {@code TRNX-FILE} at L58, {@code XREF-FILE} at L65, {@code CUST-FILE} at L70 and
 * {@code ACCT-FILE} at L75, while the caller declares only the two output definitions at L44 to
 * L47. Its entire responsibility is therefore discharged by four read-only repositories injected
 * here as collaborators: two sequential cursors and two lookups by identity. It becomes no class of
 * its own, and no separate job.
 *
 * <p>Assumptions: the per-definition split is asymmetric and the asymmetry is the mapping.
 * {@code app/cbl/CBSTM03B.CBL} declares {@code ACCESS MODE IS SEQUENTIAL} for the transaction
 * definition at L33 and the cross-reference at L39, and {@code ACCESS MODE IS RANDOM} for the
 * customer at L45 and the account at L51, with {@code RECORD KEY IS FD-ACCT-ID} at L52. Two
 * sequential definitions become two cursors and two random definitions become two reads by
 * identifier, one for one.
 *
 * <p><b>Correction F2.</b> The peer brief for this module's README reads {@code LK-M03B-KEY-LN} as a
 * significant-key-length generic browse. The first-hand COBOL is authoritative and that reading does
 * not hold; both readings are named here rather than one being adopted silently, because the house
 * precedent is that first-hand COBOL supersedes a derived document. The callee proof is L188 to L193,
 * where {@code IF M03B-READ-K} moves {@code LK-M03B-KEY (1:LK-M03B-KEY-LN)} into {@code FD-CUST-ID}
 * and issues {@code READ CUST-FILE INTO LK-M03B-FLDT} before branching to its exit, with the
 * identical structure at L213 to L218 for {@code FD-ACCT-ID}. A browse-verb census over all 230
 * lines returns nothing: no key-greater relation, no sequential-read continuation, no browse start
 * and no browse end, the only bare start token being the paragraph label {@code 0000-START.} at
 * L116. The caller proof is {@code WS-M03B-KEY-LN PIC S9(4)} at L82 together with
 * {@code COMPUTE WS-M03B-KEY-LN = LENGTH OF XREF-CUST-ID} at L373 to L374 and the same for
 * {@code XREF-ACCT-ID} at L397 to L398; {@code app/cpy/CVACT03Y.cpy} declares those as
 * {@code PIC 9(09)} at L6 and {@code PIC 9(11)} at L7, so the computed lengths are the full 9 and
 * the full 11 and no prefix is ever intended. Consequently the keyset-over-cursor reasoning that
 * belongs to the online browse programs is not grounded on this subprogram anywhere in this class.
 *
 * <p>Assumptions: contract hygiene from the reference is kept. Every call is preceded by
 * {@code MOVE ZERO TO WS-M03B-RC} and {@code MOVE SPACES TO WS-M03B-FLDT} at L349 to L350, L375 to
 * L376 and L399 to L400, and followed by a move of the returned buffer into a typed record at L364,
 * L388 and L412. The equivalent here is that every resolution either yields a fully constructed
 * value or raises: no partly populated carrier is ever handed back, so no member can hold a previous
 * read's residue.
 *
 * <p>Refactoring Rationale: the subprogram's operation code is replaced by typed members, and the
 * reason is a latent fall-through in the reference that typed members make unrepresentable. Its
 * linkage area declares six operations at L103 to L108 of {@code app/cbl/CBSTM03B.CBL}, an open, a
 * close, a sequential read, a keyed read, a write at L107 and a rewrite at L108. The last two are
 * <b>declared and never implemented</b>: neither appears in any paragraph of the program's 230 lines
 * beyond its own declaration, so a write or rewrite request matches no branch and falls through to an
 * exit whose only statement moves the file status into the return code, at L152 for the transaction
 * input, L176 for the cross-reference, L201 for the customer and L226 for the account. The caller
 * then reads the status of the <i>previous</i> operation and sees success, so the request is a silent
 * no-op. An unrecognised input name is worse still: the dispatch at L127 to L128 branches straight to
 * the return, leaving the return code assigned nothing at all. Here there is no operation code to
 * mis-set, because each of the four reads is a separate typed method and each of the two outputs is a
 * separate typed sink method, so a request the reference would have dropped in silence cannot be
 * expressed; where a member does fail it raises rather than returning a stale status.
 *
 * <p>Assumptions: the reference's operating-system control-block members are deliberately not carried
 * across. {@code PSAPTR} at L235, {@code BUMP-TIOT} at L236 and the pointer redefinition
 * {@code TIOT-INDEX} at L237, together with the {@code LINKAGE SECTION} from L239 and the
 * {@code PSA-BLOCK} it declares, exist so the program can walk the task input/output table and read
 * the job's own allocation, reached through {@code SET ADDRESS OF PSA-BLOCK TO PSAPTR} at L266 and
 * used through L284. That is inspection of z/OS control blocks and has no analogue in the target at
 * all: the equivalent question, which inputs this run is attached to, is answered by the injected
 * collaborators themselves. The members are named here so that a reader comparing the two programs
 * finds a recorded decision rather than an apparent omission.
 *
 * <h2>Assumptions: method names are semantic because the paragraph numbering collides</h2>
 *
 * <p>The reference's paragraph numbers are not unique. {@code 1000-} names both the mainline at L316
 * and the cross-reference read at L345, {@code 8100-} names both the open driver at L726 and the
 * transaction open at L730, and {@code 9999-} names both the return at L341 and the abend at L921.
 * Numeric method names would therefore be ambiguous in three places, so every method below is named
 * for what it does and cites its paragraph in its own documentation instead.
 *
 * <h2>Assumptions: monetary values stay exact and the clock is never read</h2>
 *
 * <p>Every amount is {@code com.carddemo.common.money.Money}, carried at scale 2 and reduced under
 * that type's general mode, {@code Money.GENERAL_ROUNDING}, which is HALF_UP -- its other mode,
 * truncation, governs the interest accrual alone and this service performs none -- because
 * {@code TRNX-AMT} is declared {@code PIC S9(09)V99} at L29 of
 * {@code app/cpy/COSTM01.CPY} and is therefore exact by contract. That declaration is 11 digit
 * positions and not 12: it is narrower than the account balance and is deliberately not widened to
 * match it. IEEE-754 binary floating point, in both its primitive and its boxed shape, and a bare
 * JSON number are excluded from the money path, most clients parsing the last of those into the
 * first and losing exactness at the boundary a cardholder actually reads.
 *
 * <p>This class is a generator, so it never reads a clock: no current-date, current-time or
 * current-instant reading appears below, and no clock is accepted as a collaborator. Only
 * {@code ReportExecutionService}, at the request edge, may hold one. Timestamps are rendered from
 * values that arrive on the records themselves, through
 * {@code com.carddemo.common.time.TimestampFormatter}, whose {@code TIMESTAMP_LENGTH} is 26 because
 * {@code app/cpy/COSTM01.CPY} declares both stamps {@code PIC X(26)} at L34 and L35. A stamp of 26
 * blanks is a legitimate value to carry as it stands rather than an error to refuse.
 *
 * <h2>Assumptions: this class holds no session state</h2>
 *
 * <p>CICS pseudo-conversational state is gone rather than relocated. Identity arrives as validated
 * token claims, selection context arrives as request parameters, and navigation is the client's
 * business. The re-entry discriminator has no counterpart here at all, and no resubmission flag,
 * first-arrival flag or turn counter is reintroduced under any name. Every value that varies per
 * invocation is a parameter or a local, so one instance serves many concurrent callers safely; a
 * field behaving like the reference's working-storage table would be shared across unrelated runs.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries documentation whatever its visibility, because the project's
 * explainability rule attaches its presence clause at L15 to every function and class and names no
 * visibility qualifier. Its four rationale labels are used in the plural, unparenthesised spellings
 * that rule gives at L31 to L34, and {@code Refactoring Rationale:} is reserved for the places where
 * this class genuinely replaces reference behaviour rather than merely differing from it. The
 * written convention is {@code docs/CODE_DOCUMENTATION_STANDARD.md}.
 */
@Service
public class StatementService {

    /**
     * Property key naming the object store the two statement artifacts are published to.
     */
    public static final String OUTPUT_BUCKET_PROPERTY = "carddemo.reporting.s3.output-bucket";

    /**
     * Property key naming the key prefix the statement artifacts sit under.
     */
    public static final String STATEMENT_PREFIX_PROPERTY = "carddemo.reporting.s3.statement-prefix";

    /**
     * File name suffix of the plain-text artifact.
     *
     * <p>Assumptions: the two suffixes separate the two artifacts inside one key prefix, which is
     * what {@code app/jcl/CREASTMT.JCL} achieves with two distinct data-set names at L87 and L92.
     * Neither suffix is configurable, because both belong to the key convention rather than to a
     * deployment, and a configurable suffix would let two environments disagree about where an
     * artifact a caller already holds a reference to can be found.</p>
     */
    public static final String PLAIN_TEXT_SUFFIX = ".txt";

    /**
     * File name suffix of the markup artifact.
     */
    public static final String HTML_SUFFIX = ".html";

    // WHY : Assumptions: the culprit is the reference program this class encodes, and it is exactly
    //       the eight characters ABEND-CULPRIT declares at app/cpy/CSMSG02Y.cpy, so it reaches
    //       AbendDetail without being shortened. Naming the paragraph's own program rather than this
    //       Java type is what lets an operator carry an abend straight back to the transcribed
    //       paragraph, which is the whole value of the field in the reference.
    /**
     * Greatest number of transactions one HTTP statement response body carries, being 1000.
     *
     * <p>Refactoring Rationale: no bound existed. The request-edge path collected every transaction of
     * the requested card into a list, and the operation returning only heading data collected them too
     * and then discarded them. A statement's row count is a property of a cardholder's activity, so the
     * response size was a function of data rather than of contract, and one busy card was enough to
     * turn a request into an unbounded allocation on a request thread.</p>
     *
     * <p>Assumptions: the bound applies to the RESPONSE and not to the artifact. The rendered
     * statement is still unbounded -- that is divergence D-2 and it is what keeps a document from
     * stopping at a number the business never chose -- and the two are different destinations: an
     * artifact is written once by a batch task to object storage, a response is assembled per request
     * inside a web container. The bound on the response is registered as divergence
     * D-STMT-RESPONSE-BOUNDED in {@code docs/architecture/cobol-to-service-traceability.md}, because
     * the reference publishes no request surface at all and so has no bound to compare against.</p>
     *
     * <p>Assumptions: truncation is DETECTABLE by the caller rather than silent. The heading the same
     * request returns carries the true transaction count, taken from a database aggregate over the
     * whole card, so a caller comparing the rows it received against that count learns exactly whether
     * and by how much the body was bounded. Alternatives Considered: refusing the request outright
     * once a card passed the bound, which would make the operation unusable for precisely the
     * cardholders it matters most for; and returning a cursor, which the published contract
     * deliberately does not declare here because a statement's set is closed by its own period rather
     * than open-ended.</p>
     */
    public static final int MAX_RESPONSE_TRANSACTIONS = 1000;

    /**
     * Number of statement heading rows one whole-run chunk reads, being 200.
     *
     * <p>Assumptions: a chunk exists so that no database cursor is open while an artifact record is
     * written. 200 is chosen as a size whose row width -- a card rendering, two identifiers, nine
     * customer attributes and one money figure -- keeps a chunk comfortably small while making the
     * number of round trips a two-hundredth of the number of cards. Nothing depends on the exact
     * figure: it trades round trips against transient memory and changing it changes neither the
     * documents produced nor their order.</p>
     */
    public static final int HEADING_CHUNK_SIZE = 200;

    /**
     * Number of one card's transactions a whole-run chunk reads, being 500.
     *
     * <p>Assumptions: this matches the retrieval batch the transaction repository declares, so a chunk
     * is one server round trip rather than a fraction of one. Refactoring Rationale: the run read a
     * card's transactions through an open cursor and wrote each record to the object store as it went,
     * which held a database transaction open across every network write of the run. Reading a bounded
     * chunk, ending the transaction and then writing gives the same records in the same order with no
     * transaction open while any write is in flight.</p>
     */
    public static final int TRANSACTION_CHUNK_SIZE = 500;

    /**
     * The continuation value that starts a keyset walk from the beginning.
     *
     * <p>Assumptions: the empty string, because every key it is compared against -- a hexadecimal
     * digest and a sixteen-digit identifier -- sorts strictly above it, so one strict comparison serves
     * both the first chunk and every later one. Alternatives Considered: a nullable parameter, which
     * would make the predicate two predicates and force either two queries per chunk or a
     * null-tolerant comparison no index serves.</p>
     */
    private static final String WALK_FROM_START = "";

    /**
     * The purpose the artifact identity is tokenised under.
     *
     * <p>Assumptions: a purpose string is required by the tokeniser and is authenticated along with the
     * value, so a token minted here cannot be correlated with a token minted for another purpose under
     * the same key. It names the artifact rather than the card, because that is what the token stands
     * for.</p>
     */
    private static final String ARTIFACT_TOKEN_PURPOSE = "reporting-statement-artifact";

    private static final String ABEND_CULPRIT = "CBSTM03A";

    // WHY : Assumptions: four characters is what ABEND-CODE declares, so the code is chosen to fit
    //       rather than shortened on arrival. Trade-offs: one code covers every abend this class
    //       raises instead of one code per condition, because the reference draws no distinction
    //       either -- 9999-ABEND-PROGRAM at L921 is reached from three different reads and displays
    //       the same text for all of them -- and the reason component carries what separates them.
    private static final String ABEND_CODE = "STMT";

    /**
     * Receives the records of one statement run, in the order the reference writes them.
     *
     * <p>Assumptions: this seam exists because the reference's two outputs are files and this module
     * owns no writable relation and no file handling of its own. It is declared here, beside its one
     * caller, rather than as a free-standing type, because it has exactly one implementor per
     * deployment and no meaning outside a statement run.</p>
     *
     * <p>Assumptions: the two record methods are separate rather than one method taking a width,
     * because the reference declares two distinct output definitions of two distinct lengths,
     * {@code FD-STMTFILE-REC PIC X(80)} at L45 and {@code FD-HTMLFILE-REC PIC X(100)} at L47 of
     * {@code app/cbl/CBSTM03A.CBL}, corroborated by {@code DCB LRECL=80} at L89 and
     * {@code DCB LRECL=100} at L94 of {@code app/jcl/CREASTMT.JCL}. One method taking a length would
     * let a markup record reach the statement artifact with nothing to refuse it.</p>
     *
     * <p>Trade-offs: an implementor receives already-encoded records and is given no opportunity to
     * reformat them. That deliberately leaves an implementor unable to alter a byte, which is the
     * property that keeps every declared width in the mapper layer, and it costs an implementor the
     * ability to stream text it would rather assemble itself.</p>
     */
    public interface StatementSink {

        /**
         * Discards any artifacts a previous run left and prepares empty ones.
         *
         * <p>Assumptions: a rerun replaces rather than appends, and the reference is explicit about
         * it. {@code app/jcl/CREASTMT.JCL} runs a deletion step at L66 that names both outputs with
         * {@code DISP=(MOD,DELETE,DELETE)} across L67 to L75, and only then runs the generator at
         * L79, which creates both with {@code DISP=(NEW,CATLG,DELETE)} at L87 and L92. Two steps in
         * that order mean the second run of a day cannot extend the first run's statement.</p>
         *
         * <p>Assumptions: this is called exactly once per run and before any record is offered, so an
         * implementor may treat it as the point at which a previous artifact stops being readable. It
         * is a separate method rather than an implicit effect of the first write, because a run that
         * produces no statement at all must still clear a previous run's output; folding it into the
         * first write would leave stale artifacts readable after such a run.</p>
         *
         * @throws RuntimeException if the previous artifacts cannot be discarded, which stops the run
         *     rather than allowing a statement to be appended to stale content
         */
        void replaceArtifacts();

        /**
         * Accepts one record of the plain-text statement artifact.
         *
         * @param record the encoded statement record, at the width its band declares; never
         *     {@code null}
         * @throws RuntimeException if the record cannot be published, which stops the run rather than
         *     leaving a statement short of its trailer
         */
        void writeStatementRecord(byte[] record);

        /**
         * Accepts one record of the markup statement artifact.
         *
         * @param record the encoded markup record, at the width its band declares; never
         *     {@code null}
         * @throws RuntimeException if the record cannot be published, which stops the run rather than
         *     leaving markup without its closing fragments
         */
        void writeMarkupRecord(byte[] record);
    }

    // WHY : Assumptions: the four collaborators are the four input definitions CBSTM03B owns, and
    //       they are held as final members set once by the constructor because they are stateless
    //       roles rather than per-run values. The per-run values -- the running total, the row count
    //       and the resolved dimensions -- are locals in the methods below for the reason the package
    //       charter gives: one instance serves concurrent callers, so a member behaving like
    //       WS-TRNX-TABLE would be shared across unrelated statement runs.
    private final StatementTransactionRepository transactions;

    private final StatementCardXrefRepository cardXrefs;

    /**
     * The keyed customer read, used by the single-card request path.
     *
     * <p>Assumptions: the request path reads one card, so three keyed reads are three statements for one
     * document rather than a per-row multiplication -- which is why the whole-run path uses the joined
     * chunk query instead and this collaborator serves the request path alone.</p>
     */
    private final StatementCustomerRepository customers;

    /**
     * The keyed account read, used by the single-card request path.
     *
     * <p>Assumptions: retained for the reason recorded on the customer read above.</p>
     */
    private final StatementAccountRepository accounts;



    // WHY : Assumptions: the store and prefix are configuration rather than stored values, so no
    //       relation has to be written to record where an artifact went -- which matters because this
    //       context holds no writable relation at all. Deriving a location also lets a caller
    //       construct the same one independently, which is what the reference's constant data-set
    //       names at L87 and L92 of app/jcl/CREASTMT.JCL gave for free.
    private final String outputBucket;

    private final String statementPrefix;

    /**
     * The keyed tokeniser that turns a card's identity into the opaque component of an object key.
     *
     * <p>Assumptions: injected by NAME rather than by type, for the reason the authorization context
     * records on its own tokeniser: more than one keyed tokeniser can legitimately exist in one
     * application under different keys, and injection by type alone would bind whichever the context
     * happened to hold.</p>
     */
    private final OpaqueIdentifier artifactIdentity;

    /**
     * Builds the statement service over the four read-only roles that replace the reference's file
     * handling.
     *
     * <p>Assumptions: the four repositories arrive by constructor injection rather than being looked
     * up, so an instance cannot exist without them and a unit test can supply four doubles without a
     * database. The order below is the order {@code 1000-MAINLINE} reads in at L319 through L326 of
     * {@code app/cbl/CBSTM03A.CBL}, which keeps the declaration readable against the paragraph.</p>
     *
     * <p>Assumptions: no clock is a parameter and none is held. The package charter reserves an
     * injectable clock to the request edge, and a generator that could read one would be able to put
     * a value in an artifact that a rerun could not reproduce, which is what makes a golden master
     * meaningless.</p>
     *
     * @param transactions the card-ordered transaction cursor replacing the sequential transaction
     *     definition at L33 of {@code app/cbl/CBSTM03B.CBL}
     * @param cardXrefs the cross-reference resolution, the joined heading chunk and the ordered cursor
     *     replacing the sequential cross-reference definition at L39 of {@code app/cbl/CBSTM03B.CBL}
     * @param customers the keyed customer read replacing the random definition at L45 of
     *     {@code app/cbl/CBSTM03B.CBL}, reached by the single-card request path
     * @param accounts the keyed account read replacing the random definition at L51 of
     *     {@code app/cbl/CBSTM03B.CBL}, reached by the single-card request path
     * @param outputBucket the object store the two artifacts are published to, supplied by
     *     {@value #OUTPUT_BUCKET_PROPERTY}
     * @param statementPrefix the key prefix the artifacts sit under, supplied by
     *     {@value #STATEMENT_PREFIX_PROPERTY}
     * @param artifactIdentity the keyed tokeniser the artifact object key is built with, so that no
     *     account identifier and no part of a card number appears in a key an object store logs
     * @throws NullPointerException if any collaborator or configuration value is {@code null}
     */
    public StatementService(
            StatementTransactionRepository transactions,
            StatementCardXrefRepository cardXrefs,
            StatementCustomerRepository customers,
            StatementAccountRepository accounts,
            @Value("${" + OUTPUT_BUCKET_PROPERTY + "}") String outputBucket,
            @Value("${" + STATEMENT_PREFIX_PROPERTY + "}") String statementPrefix,
            @Qualifier(ArtifactIdentityConfig.ARTIFACT_TOKENISER)
                    OpaqueIdentifier artifactIdentity) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.cardXrefs = Objects.requireNonNull(cardXrefs, "cardXrefs must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.outputBucket = Objects.requireNonNull(outputBucket, "outputBucket must not be null");
        this.statementPrefix =
                Objects.requireNonNull(statementPrefix, "statementPrefix must not be null");
        this.artifactIdentity =
                Objects.requireNonNull(artifactIdentity, "artifactIdentity must not be null");
    }

    /**
     * One card's statement heading, however it was read.
     *
     * <p>Purpose: the two paths into this class read the same fifteen values by two different shapes --
     * the single-card request path by three keyed reads, the whole-run path by one outer-joined chunk
     * query -- and everything downstream of the read needs only the values. This record is what lets the
     * heading assembly, the score check, the name assembly and the object-key derivation each be written
     * once instead of once per read shape.</p>
     *
     * <p>Alternatives Considered: making the whole-run projection interface the common type and adapting
     * the request path to it. Rejected because the request path would then have to synthesise an
     * anonymous implementation of a fifteen-method interface, which is more code than this record and
     * puts the adaptation where the reader is least expecting it. Alternatives Considered: making the
     * request path use the joined query too, which would collapse the two shapes into one. Rejected
     * because that query positions on a keyset continuation to serve a chunk, and expressing a
     * single-card lookup as a bounded traversal makes the answer depend on where the card sits in the
     * ordering -- the same objection the cross-reference repository records against replacing its keyed
     * read with its cursor.</p>
     *
     * <p>Assumptions: every component is non-null by the time an instance exists, because both factory
     * paths refuse an unresolved dimension before constructing one. That is what lets the downstream
     * methods read a component without a null test each.</p>
     *
     * @param cardNum the masked rendering of the card, twelve asterisks and the last four digits
     * @param cardFingerprint the keyed per-card fingerprint naming the card exactly
     * @param accountId the account the card is issued against
     * @param firstName the customer's first name
     * @param middleName the customer's middle name, held as blanks when the customer has none, because
     *     the constructor below normalises an absent value onto the reference's space-filled rendering
     * @param lastName the customer's last name
     * @param addressLine1 the first address line
     * @param addressLine2 the second address line, held as blanks when the customer has none, on the
     *     same terms as the middle name above
     * @param addressLine3 the third address line
     * @param stateCode the two-character state code as stored
     * @param countryCode the three-character country code as stored
     * @param postalCode the ten-character postal code as stored
     * @param ficoCreditScore the three-digit credit score the heading band prints
     * @param currentBalance the account balance the heading band prints
     */
    private record StatementHeading(
            String cardNum,
            String cardFingerprint,
            Long accountId,
            String firstName,
            String middleName,
            String lastName,
            String addressLine1,
            String addressLine2,
            String addressLine3,
            String stateCode,
            String countryCode,
            String postalCode,
            Short ficoCreditScore,
            Money currentBalance) {

        /**
         * Renders the two genuinely optional text attributes as blanks rather than as absent values.
         *
         * <p>Purpose: {@code account.customers} declares {@code middle_name} and {@code addr_line_2}
         * nullable and every other projected attribute not null, so those two -- and only those two --
         * can reach a heading absent. Registered as divergence D-STMT-OPTIONAL-ADDRESS-BLANK in
         * {@code docs/architecture/cobol-to-service-traceability.md}, which records that the rendered
         * artifact is byte-identical to the reference's and that what changed is the code path.
         * Blanks are the reference's own rendering of an absent one:
         * {@code app/cpy/COSTM01.CPY} carries each as a fixed-width character field, and a COBOL
         * {@code MOVE} of an empty group leaves the receiving field space-filled, so a customer with no
         * second address line prints a blank line rather than stopping the run.</p>
         *
         * <p>Refactoring Rationale: normalising here rather than at each use site is what makes the
         * behaviour hold for both read shapes at once. Without it the nightly run raised a null-pointer
         * failure inside the band assembly on the first customer with no second address line -- the band
         * assembler refuses a null because a band field is required, which is correct of the assembler
         * and wrong of the caller that handed it one. That failure surfaced as a run-wide abort naming a
         * field rather than as a statement, and it would have been reached only in production, because a
         * fixture with every attribute populated cannot produce it.</p>
         *
         * <p>Alternatives Considered: refusing an absent value as an unresolved dimension, alongside the
         * customer and account checks below. Rejected because an absent second address line is a
         * legitimate customer record, not a broken join -- the column is nullable by design -- and
         * treating it as an abend would stop a whole run over a customer who simply has a one-line
         * street address. Alternatives Considered: making the band assembler tolerate a null. Rejected
         * because every other field it receives is required, so admitting a null there would weaken the
         * check that catches a genuinely missing value.</p>
         *
         * @param cardNum the masked card rendering, as read
         * @param cardFingerprint the keyed per-card fingerprint, as read
         * @param accountId the account identifier, as read
         * @param firstName the customer's first name, as read
         * @param middleName the customer's middle name, rendered as blanks when the customer has none
         * @param lastName the customer's last name, as read
         * @param addressLine1 the first address line, as read
         * @param addressLine2 the second address line, rendered as blanks when the customer has none
         * @param addressLine3 the third address line, as read
         * @param stateCode the state code, as read
         * @param countryCode the country code, as read
         * @param postalCode the postal code, as read
         * @param ficoCreditScore the credit score, as read
         * @param currentBalance the account balance, as read
         */
        StatementHeading {
            middleName = middleName == null ? "" : middleName;
            addressLine2 = addressLine2 == null ? "" : addressLine2;
        }

        /**
         * Builds a heading from one row of the joined chunk query, refusing an unresolved dimension.
         *
         * <p>Assumptions: the customer is tested through its last name and the account through its
         * balance, because both base columns are declared not null in the relations that own them -- so a
         * null here can only mean the outer join found nothing. Testing the joined identifier instead
         * would not work: it comes from the cross-reference and is present whether or not the dimension
         * row exists, which is exactly the condition being detected.</p>
         *
         * <p>Assumptions: an unresolved dimension stops the run rather than producing a partial heading.
         * {@code app/cbl/CBSTM03A.CBL} reads the customer at L368 and the account at L392 with no
         * not-found arm and reaches its abend paragraph at L921 when either fails.</p>
         *
         * @param row one row of the joined heading chunk; must not be {@code null}
         * @return the heading with every component resolved, never {@code null}
         * @throws IllegalStateException if the cross-reference names a customer or an account that does
         *     not resolve
         */
        static StatementHeading of(StatementHeadingRow row) {
            if (row.getLastName() == null) {
                throw abend("CUSTFILE",
                        "the cross-reference names a customer that does not resolve");
            }
            if (row.getCurrentBalance() == null) {
                throw abend("ACCTFILE",
                        "the cross-reference names an account that does not resolve");
            }
            return new StatementHeading(
                    row.getCardNum(),
                    row.getCardFingerprint(),
                    row.getAccountId(),
                    row.getFirstName(),
                    row.getMiddleName(),
                    row.getLastName(),
                    row.getAddressLine1(),
                    row.getAddressLine2(),
                    row.getAddressLine3(),
                    row.getStateCode(),
                    row.getCountryCode(),
                    row.getPostalCode(),
                    row.getFicoCreditScore(),
                    row.getCurrentBalance());
        }

        /**
         * Builds a heading from the three keyed reads the single-card request path performs.
         *
         * @param xref the cross-reference row the requested card resolved to; must not be {@code null}
         * @param customer the customer that cross-reference names; must not be {@code null}
         * @param account the account that cross-reference names; must not be {@code null}
         * @return the heading with every component resolved, never {@code null}
         */
        static StatementHeading of(CardXrefView xref, CustomerView customer, AccountView account) {
            return new StatementHeading(
                    xref.getCardNum(),
                    xref.getCardFingerprint(),
                    account.getAccountId(),
                    customer.getFirstName(),
                    customer.getMiddleName(),
                    customer.getLastName(),
                    customer.getAddressLine1(),
                    customer.getAddressLine2(),
                    customer.getAddressLine3(),
                    customer.getStateCode(),
                    customer.getCountryCode(),
                    customer.getPostalCode(),
                    customer.getFicoCreditScore(),
                    account.getCurrentBalance());
        }
    }

    /**
     * Reports what one card's statement covers, without producing either artifact.
     *
     * <p>Assumptions: this answers a request and does not generate. It exists separately from
     * generation because a request-time read that also wrote would produce a document that then
     * disagreed with the one the run produced.</p>
     *
     * <p>Refactoring Rationale: the total and the count come from a database AGGREGATE and no longer
     * from a traversal this method performs. It previously collected every transaction of the card into
     * a list, summed the list, counted it and discarded it -- an unbounded allocation on a request
     * thread performed to produce two scalars, in an operation whose whole purpose is to return heading
     * data. The aggregate returns the same two numbers over the same rows, and the sum is computed on a
     * {@code NUMERIC(11,2)} column so it stays exact.</p>
     *
     * @param request the card or account the statement is wanted for
     * @return the heading figures, the accumulated total, the row count and the two artifact locations
     * @throws ClientInputException if the request does not name exactly one of a card and an account,
     *     or if the named account holds more than one card
     * @throws NoSuchElementException if no card with the requested number exists, or the requested
     *     account holds no card
     * @throws IllegalStateException if the cross-reference names a customer or an account that does
     *     not resolve, which the reference treats as an abend rather than as an omission
     */
    public StatementResponse describe(StatementRequest request) {
        StatementHeading heading = resolveHeading(request);
        StatementTransactionRepository.StatementAggregate totals =
                transactions.aggregateByCardFingerprint(heading.cardFingerprint());
        return headingResponse(heading, Money.of(totals.getTotal()), totals.getLineCount());
    }

    /**
     * Reports one card's statement together with a bounded window of the lines it covers.
     *
     * <p>Assumptions: the lines are the same rows in the same order the artifact renders, so a caller
     * comparing this against a stored artifact is comparing one traversal against itself rather than
     * two independently ordered reads. The order is the transaction identifier ascending, which the
     * query's own {@code order by} fixes and which is the second sort key of
     * {@code app/jcl/CREASTMT.JCL} L53; nothing here re-sorts.</p>
     *
     * <p>Refactoring Rationale: the window is bounded at {@value #MAX_RESPONSE_TRANSACTIONS} rows,
     * where it was unbounded. The reasoning for that is recorded on the constant. The heading returned
     * beside the rows carries the TRUE count from the aggregate rather than the number of rows in the
     * body, which is what makes truncation something a caller measures rather than something it has to
     * be told.</p>
     *
     * @param request the card or account the statement is wanted for
     * @return the heading and at most {@value #MAX_RESPONSE_TRANSACTIONS} lines, in transaction order
     * @throws ClientInputException if the request does not name exactly one of a card and an account,
     *     or if the named account holds more than one card
     * @throws NoSuchElementException if no card with the requested number exists, or the requested
     *     account holds no card
     * @throws IllegalStateException if the cross-reference names a customer or an account that does
     *     not resolve, which the reference treats as an abend rather than as an omission
     */
    public StatementDocument compose(StatementRequest request) {
        StatementHeading heading = resolveHeading(request);
        String fingerprint = heading.cardFingerprint();
        StatementTransactionRepository.StatementAggregate totals =
                transactions.aggregateByCardFingerprint(fingerprint);

        List<StatementTransactionView> window = transactions.findWindowByCardFingerprint(
                fingerprint, WALK_FROM_START, MAX_RESPONSE_TRANSACTIONS);
        List<StatementTransactionResponse> lines = new ArrayList<>(window.size());
        for (StatementTransactionView row : window) {
            lines.add(toLine(heading.cardNum(), row));
        }

        StatementResponse response =
                headingResponse(heading, Money.of(totals.getTotal()), totals.getLineCount());
        return new StatementDocument(response, List.copyOf(lines));
    }

    /**
     * Resolves one request to exactly one card's heading row.
     *
     * <p>Refactoring Rationale: this replaces a lookup by the NARROWED card rendering, and the change
     * is the substance of a broken-object-selection fix rather than a refactor. The narrowed rendering
     * is twelve constant asterisks and four digits, so the old lookup selected a tail. Where the
     * requested card did not exist but a different cardholder's card shared that tail, the lookup
     * matched exactly one row and returned it -- so the caller received another cardholder's customer,
     * account and statement, with nothing recording the substitution. Where both existed it matched two
     * and raised, refusing a legitimate request. Resolution is now an equality on the whole number,
     * performed by {@code reporting.resolve_card}, which is the only construct in the reporting schema
     * that can express one: no relation this module may read publishes the unmasked number.</p>
     *
     * <p>Refactoring Rationale: the account selector is now IMPLEMENTED, where the previous
     * implementation required a card number and ignored the account except as a cross-check. The
     * published contract at {@code reporting-api.yaml} states that exactly one of the two selects the
     * statement, so a caller following the contract and sending only an account received a refusal
     * naming a field it had been told to omit.</p>
     *
     * <p>Assumptions: a request naming an account whose cards number more than one is REFUSED rather
     * than answered from one of them. A statement is a per-card document in the reference --
     * {@code app/cbl/CBSTM03A.CBL} produces one per cross-reference row -- so an account with several
     * cards has several statements and no basis exists for choosing between them. Choosing the lowest
     * would answer a different question from the one asked and would look like success.</p>
     *
     * @param request the request naming exactly one of a card and an account; must not be {@code null}
     * @return the heading for that one card, with its dimensions already resolved
     * @throws ClientInputException if neither selector is supplied, if both are, or if the named
     *     account holds more than one card
     * @throws NoSuchElementException if no card with the requested number exists, or the requested
     *     account holds no card
     * @throws IllegalStateException if the cross-reference names a customer or an account that does
     *     not resolve
     */
    private StatementHeading resolveHeading(StatementRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String card = blankToNull(request.cardNumber());
        String account = blankToNull(request.accountId());

        if (card == null && account == null) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "cardNumber",
                    "exactly one of cardNumber and accountId must be supplied");
        }
        if (card != null && account != null) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "accountId",
                    "exactly one of cardNumber and accountId must be supplied, not both");
        }

        String fingerprint = card != null
                ? fingerprintOfCard(card)
                : fingerprintOfSoleCardOf(account);
        return requireHeading(fingerprint);
    }

    /**
     * Resolves one whole card number to the fingerprint that names it exactly.
     *
     * @param cardNumber the whole primary account number as the caller supplied it; must not be
     *     {@code null}
     * @return the keyed fingerprint of that card
     * @throws NoSuchElementException if no card with that number exists, which is the caller naming
     *     something absent rather than a defect in the data
     */
    private String fingerprintOfCard(String cardNumber) {
        return cardXrefs.resolveByWholeCardNumber(cardNumber)
                .orElseThrow(() -> new NoSuchElementException(
                        "no cross-reference row for the requested card"))
                .getCardFingerprint();
    }

    /**
     * Resolves one account to the fingerprint of its single card, refusing an ambiguous account.
     *
     * <p>Assumptions: two rows are requested where one is wanted, which is the same look-ahead device
     * the reference uses to discover whether a further page exists at {@code app/cbl/COCRDLIC.cbl}
     * L1197. Asking for one row could not tell an account with one card from an account with several.
     * </p>
     *
     * @param accountId the account identifier as digits; must not be {@code null}
     * @return the keyed fingerprint of that account's single card
     * @throws ClientInputException if the account holds more than one card
     * @throws NoSuchElementException if the account holds no card at all
     */
    private String fingerprintOfSoleCardOf(String accountId) {
        List<CardXrefView> cards = cardXrefs.findCardsOfAccount(
                Long.parseLong(accountId), Limit.of(2));
        if (cards.isEmpty()) {
            throw new NoSuchElementException("no cross-reference row for the requested account");
        }
        if (cards.size() > 1) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "accountId",
                    "the requested account holds more than one card, so name the card instead");
        }
        return cards.get(0).getCardFingerprint();
    }

    /**
     * Reads one card's heading through the three keyed reads the reference performs per card.
     *
     * <p>Assumptions: three keyed reads are used on THIS path and the joined chunk query on the
     * whole-run path, and the split is deliberate rather than an inconsistency. The request path reads
     * one card, so three statements produce one document -- there is no per-row multiplication to
     * remove, and the keyed shape has independent first-hand authority: {@code app/cbl/CBSTM03B.CBL}
     * declares the customer definition {@code ACCESS MODE IS RANDOM} at L45 and the account definition
     * at L51, and {@code app/cbl/CBSTM03A.CBL} supplies the whole key at L370 and L396. The whole-run
     * path reads every card, where the same three reads per card were the {@code 1 + 3N} the joined
     * query removes.</p>
     *
     * <p>Assumptions: an unresolved customer or account stops the request rather than producing a
     * partial heading. The reference's two reads carry exactly two arms each -- {@code WHEN '00'} and
     * {@code WHEN OTHER} -- with no not-found arm at all, and the second performs the abend at L921. A
     * missing dimension for an existing cross-reference row is a referential-integrity violation and not
     * an absence to render around, which is why it is reported as a failure and not as an empty
     * document.</p>
     *
     * @param fingerprint the keyed fingerprint naming exactly one card; must not be {@code null}
     * @return the heading with every component resolved, never {@code null}
     * @throws NoSuchElementException if the fingerprint names no card, which after resolution can only
     *     be a card removed between two reads
     * @throws IllegalStateException if the cross-reference names a customer or an account that does not
     *     resolve
     */
    private StatementHeading requireHeading(String fingerprint) {
        CardXrefView xref = cardXrefs.findById(fingerprint)
                .orElseThrow(() -> new NoSuchElementException(
                        "no cross-reference row for the requested card"));
        CustomerView customer = customers.findById(xref.getCustomerId())
                .orElseThrow(() -> abend("CUSTFILE",
                        "the cross-reference names a customer that does not resolve"));
        AccountView account = accounts.findById(xref.getAccountId())
                .orElseThrow(() -> abend("ACCTFILE",
                        "the cross-reference names an account that does not resolve"));
        return StatementHeading.of(xref, customer, account);
    }

    /**
     * Assembles the heading response one request-edge operation returns.
     *
     * @param heading the resolved heading row; must not be {@code null}
     * @param total the exact sum of the card's transaction amounts; must not be {@code null}
     * @param lineCount how many transactions the card has, which is the true count and not the number
     *     of rows any body carries
     * @return the heading response, never {@code null}
     */
    private StatementResponse headingResponse(
            StatementHeading heading, Money total, long lineCount) {
        return new StatementResponse(
                heading.cardNum(),
                String.valueOf(heading.accountId()),
                assembleName(heading),
                total,
                Math.toIntExact(lineCount),
                artifactUri(heading, PLAIN_TEXT_SUFFIX),
                artifactUri(heading, HTML_SUFFIX),
                // WHY : Assumptions: the produced-at stamp is left blank on a request-edge read and is
                //       not invented. Its own contract on StatementResponse records that an all-blank
                //       value round-trips unchanged and is deliberately not refused, and this method has
                //       produced no artifact to stamp. The only honest alternative would be a clock
                //       reading, which the package charter forbids on this path and which would report a
                //       production time for a document nothing produced.
                " ".repeat(TimestampFormatter.TIMESTAMP_LENGTH));
    }

    /**
     * Reduces a blank or absent selector to {@code null}.
     *
     * <p>Assumptions: a blank string and an absent one are one condition here, because a JSON body that
     * carries an empty string for a field it means to omit is indistinguishable in intent from one that
     * omits it, and treating them differently would make the exactly-one-of rule depend on a caller's
     * serialiser.</p>
     *
     * @param value the selector as it arrived, which may be {@code null}
     * @return the value, or {@code null} when it is absent or blank
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Produces every cardholder statement of one run, encoding {@code 1000-MAINLINE} at L316 of
     * {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>Assumptions: the reference loops over the cross-reference input at L317 to L329 and produces
     * <b>one statement per cross-reference row</b>, resolving the customer at L321 and the account at
     * L322, creating the statement at L323 and only then walking that card's transactions at L326.
     * This method keeps that order exactly, so a failure occurs at the same point in the sequence and
     * names the same relation.</p>
     *
     * <p>Refactoring Rationale: the run reads in bounded CHUNKS and holds no database transaction while
     * it writes, where it previously ran as a single read-only transaction wrapping the whole run with
     * a cursor open over the cross-reference and a second cursor open per card inside it. Three
     * separate defects came from that one shape and all three close together. The query count was
     * {@code 1 + 3N} for {@code N} cards, because each row drove a keyed customer read and a keyed
     * account read; those two are now one outer-joined chunk query. Two cursors were open at once, the
     * outer pinned across the inner, which is a shape a single connection sustains only by accident of
     * driver behaviour. And a database transaction was held open across every object-store write of the
     * run, so its lifetime was the run's wall-clock time including all of its network latency -- which
     * blocks vacuum, pins a connection and makes a slow store into a database problem.</p>
     *
     * <p>Refactoring Rationale: the credit-score resolver parameter is REMOVED. The value now comes
     * from the customer row, because {@code reporting.v_customers} now projects
     * {@code fico_credit_score}. The parameter existed only because that column was withheld on the
     * ground that no reporting band printed it, and a band does print it --
     * {@code app/cbl/CBSTM03A.CBL} moves it into {@code ST-FICO-SCORE}, declared {@code PIC X(20)} at
     * L118 of {@code app/cpy/COSTM01.CPY}. The parameter was also a large part of why this method could
     * not be invoked at all: a caller had to supply a function no component in the module offered.</p>
     *
     * <p>Trade-offs: the run is no longer one read view, and that is stated rather than implied. It
     * never was one in the sense the previous prose claimed: the transaction isolation this deployment
     * runs at is read-committed, under which each statement inside a transaction takes a fresh snapshot,
     * so a long transaction gave the run a long LOCK footprint and not a stable view. Run-level
     * consistency comes from where it actually comes from -- the orchestrated batch window brackets
     * statement generation between the states that quiesce and resume online writes, states 1 and 11 of
     * the {@code carddemo-daily-batch} machine -- and per-card consistency comes from each card's rows
     * being read inside one statement. Both are recorded here so that a reader does not have to infer a
     * guarantee from a transaction boundary that never provided it.</p>
     *
     * @param sink the destination for both record streams, cleared once before the first statement
     * @return the number of statements produced, which is the number of cross-reference rows read
     * @throws NullPointerException if {@code sink} is {@code null}
     * @throws IllegalStateException if a cross-reference row names a customer or an account that does
     *     not resolve, or if a credit score cannot be carried by the statement band, either of which
     *     stops the run as the reference's abend does
     */
    public int generateStatements(StatementSink sink) {
        Objects.requireNonNull(sink, "sink must not be null");

        // WHY : Assumptions: the previous run's artifacts are discarded BEFORE the first record and
        //       unconditionally, which is the deletion step app/jcl/CREASTMT.JCL runs at L66 ahead of
        //       the generator at L79. Doing it here rather than per statement is what makes a run that
        //       produces no statement still leave no stale artifact readable.
        sink.replaceArtifacts();

        int statementsProduced = 0;
        String afterFingerprint = WALK_FROM_START;
        for (;;) {
            List<StatementHeadingRow> chunk =
                    cardXrefs.findHeadingChunk(afterFingerprint, HEADING_CHUNK_SIZE);
            if (chunk.isEmpty()) {
                return statementsProduced;
            }
            for (StatementHeadingRow row : chunk) {
                emitStatement(StatementHeading.of(row), sink);
                statementsProduced++;
                afterFingerprint = row.getCardFingerprint();
            }
        }
    }

    /**
     * Produces one card's statement, encoding the body of the mainline loop at L320 to L327 of
     * {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>Assumptions: the running total is a local of the traversal below and therefore resets with
     * every statement, which is what {@code MOVE ZERO TO WS-TOTAL-AMT} at L325 does immediately before
     * the traversal is performed at L326. A total that survived a statement would accumulate one
     * cardholder's activity into the next cardholder's trailer.</p>
     *
     * <p>Assumptions: the trailer is emitted from the traversal step and not from statement creation.
     * In the reference the trailer and the eight closing markup fragments sit inside
     * {@code 4000-TRNXFILE-GET}, which spans L416 to L456, and the mainline drives them through its
     * {@code PERFORM} at L326; {@code 5000-CREATE-STATEMENT} begins only at L458 and contains none of
     * them. The distinction is kept because the trailer carries the accumulated total, which does not
     * exist until the traversal has finished.</p>
     *
     * @param heading the heading row naming the card, its customer's printed attributes and its balance
     * @param sink the destination for both record streams
     * @throws IllegalStateException if the credit score cannot be carried by the statement band, or if
     *     the sink refuses a record
     */
    private void emitStatement(StatementHeading heading, StatementSink sink) {
        createStatement(heading, sink);
        Money cardTotal = emitTransactionsForCard(heading, sink);
        emitCardTrailer(cardTotal, sink);
    }

    /**
     * Emits one statement's heading in both artifacts, encoding {@code 5000-CREATE-STATEMENT} at L458
     * of {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>Assumptions: the heading values are prepared once and used by both artifacts, because the
     * reference builds them once into {@code STATEMENT-LINES} and then renders the markup from the
     * same items, at L462 to L485 followed by the markup paragraph at L486. Preparing them twice would
     * let the two artifacts disagree about one customer's name.</p>
     *
     * <p>Assumptions: the reference's {@code INITIALIZE STATEMENT-LINES} at L459 blanks only named
     * items and <b>skips FILLER</b>, so every {@code VALUE ALL} run and every label literal declared
     * as FILLER survives it untouched. {@code ST-LINE0} at L86 to L89 is FILLER in its entirety, an
     * asterisk run, a literal and a second asterisk run, so the reference never clears it at all. The
     * consequence for a rebuilt band is that those literal characters have to be emitted explicitly
     * rather than assumed present, and the mapper this method delegates to supplies every position of
     * every band for exactly that reason. A band assembled from blanks and then populated would lose
     * the separator rules and the labels the reference never cleared.</p>
     *
     * <p>Assumptions: the plain-text heading arrives from the mapper as one block covering the opening
     * banner at L460 together with the fifteen consecutive writes at L488 to L502, while the markup
     * heading arrives as the two blocks the reference writes from L461 and L486. The two artifacts are
     * separate destinations, so the order within each is what the reference fixes and the interleaving
     * between them is not observable in either.</p>
     *
     * @param heading the heading row carrying the customer's printed attributes and the account balance
     * @param sink the destination for both record streams
     * @throws IllegalStateException if the credit score cannot be carried by the statement band
     */
    private void createStatement(StatementHeading heading, StatementSink sink) {
        StatementTextMapper.PreparedHeaderFields header = StatementTextMapper.prepareHeaderFields(
                heading.firstName(),
                heading.middleName(),
                heading.lastName(),
                heading.addressLine1(),
                heading.addressLine2(),
                heading.addressLine3(),
                heading.stateCode(),
                heading.countryCode(),
                heading.postalCode(),
                heading.accountId(),
                heading.currentBalance(),
                creditScoreOf(heading));

        for (byte[] record : StatementTextMapper.emitHeaderBlock(header)) {
            sink.writeStatementRecord(record);
        }
        writeHtmlHeader(header, sink);
        writeHtmlNameAndAddress(header, sink);
    }

    /**
     * Emits the markup document heading, encoding {@code 5100-WRITE-HTML-HEADER} at L506 of
     * {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>Assumptions: the account identifier item is taken from the prepared heading rather than
     * rendered again here, because the reference's markup paragraph reads the same item the
     * plain-text heading holds. Rendering it a second time would put one identifier through two
     * width rules.</p>
     *
     * @param header the prepared heading values for this statement
     * @param sink the destination for the markup record stream
     * @throws IllegalStateException if the sink refuses a record, which stops the run rather than
     *     leaving a markup document without its opening fragments
     */
    private void writeHtmlHeader(StatementTextMapper.PreparedHeaderFields header,
            StatementSink sink) {
        for (byte[] record : StatementHtmlMapper.emitDocumentHeader(header.accountId())) {
            sink.writeMarkupRecord(record);
        }
    }

    /**
     * Emits the markup name, address and basic-detail cells, encoding
     * {@code 5200-WRITE-HTML-NMADBS} at L558 of {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>Assumptions: this is a separate step from the document heading above because the reference
     * separates them, performing one from L461 and the other from L486 with the heading items built
     * in between. Merging them would put the address cells ahead of values that are not yet
     * assembled when the first paragraph runs.</p>
     *
     * @param header the prepared heading values for this statement
     * @param sink the destination for the markup record stream
     * @throws IllegalStateException if the sink refuses a record, which stops the run rather than
     *     leaving a markup document with a heading and no cardholder
     */
    private void writeHtmlNameAndAddress(StatementTextMapper.PreparedHeaderFields header,
            StatementSink sink) {
        for (byte[] record : StatementHtmlMapper.emitNameAddressAndBasicDetails(header)) {
            sink.writeMarkupRecord(record);
        }
    }

    /**
     * Walks one card's transactions and accumulates their total, encoding the traversal at L417 to
     * L432 of {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>Refactoring Rationale: the reference does not read a file here. {@code WS-TRNX-TABLE} at L225
     * to L230 has already been loaded, and L417 to L432 scan it linearly, matching the card and then
     * walking the matched card's inner entries. That index is 51 outer entries of 16 characters plus
     * ten inner entries of 16 + 318 = 334 characters each, so 16 + 10 x 334 = 3,356 per card and
     * 51 x 3,356 = 171,156 characters in total, and it is the structure whose two static dimensions
     * produce both overrun boundaries D-2 records. A cursor over the one card's rows replaces it: the
     * rows are consumed one at a time and never grouped, so nothing in this method grows with the
     * number of transactions or the number of cards.</p>
     *
     * <p>Assumptions: the traversal relies on the same input ordering the reference relies on. The
     * scan's second termination arm at L419, {@code WS-CARD-NUM (CR-JMP) > XREF-CARD-NUM}, is only
     * correct on card-ordered input, and that ordering is established upstream by
     * {@code app/jcl/CREASTMT.JCL} L53 rather than by the program. The cursor this method opens
     * carries {@code order by} on the transaction identifier in the repository that declares it, and
     * the cross-reference cursor driving the caller carries {@code order by} on the card, which
     * together reproduce that sort's two keys.</p>
     *
     * <p>Assumptions: the total is accumulated as exact scale-2 decimal, one row at a time, in the
     * order the rows arrive. The reference accumulates into a packed accumulator with
     * {@code ADD TRNX-AMT TO WS-TOTAL-AMT} at L429, inside the inner walk, so the total covers the
     * matched card alone and reflects negative amounts as they stand; the golden statement's total of
     * 150.00 over amounts of 183.88, 14.00 and -47.88 is that behaviour.</p>
     *
     * @param heading the heading row naming the card whose transactions are wanted
     * @param sink the destination for both record streams
     * @return the accumulated total of the card's transactions, zero when the card has none
     * @throws IllegalStateException if the sink refuses a record, which stops the run rather than
     *     leaving a statement missing transactions it totalled
     */
    private Money emitTransactionsForCard(StatementHeading heading, StatementSink sink) {
        // WHY : Assumptions: the accumulator is a LOCAL and not a member, which is what resets it per
        //       statement without an explicit reset step. The reference needs MOVE ZERO TO
        //       WS-TOTAL-AMT at L325 precisely because its accumulator is process-wide
        //       working storage; a member here would be shared across concurrent runs as well as
        //       across statements, so the reset would be necessary and insufficient at once.
        Money cardTotal = Money.ZERO;
        String fingerprint = heading.cardFingerprint();
        String after = WALK_FROM_START;
        for (;;) {
            // WHY : Refactoring Rationale: the card's rows arrive in bounded chunks rather than through
            //       one open cursor, and the chunk is fully read before the first record of it is
            //       written. A cursor kept a database transaction open across every object-store write
            //       of the run, which made the transaction's lifetime the run's wall-clock time
            //       including its network latency. The rows, their order and the accumulated total are
            //       identical either way: the continuation is strict and on the unique transaction
            //       identifier the query orders by, so no row is skipped at a chunk boundary and none
            //       is counted twice.
            List<StatementTransactionView> chunk = transactions.findWindowByCardFingerprint(
                    fingerprint, after, TRANSACTION_CHUNK_SIZE);
            if (chunk.isEmpty()) {
                return cardTotal;
            }
            for (StatementTransactionView row : chunk) {
                writeTransaction(row, sink);
                cardTotal = cardTotal.plus(row.amount());
                after = row.key().transactionId();
            }
        }
    }

    /**
     * Emits one transaction in both artifacts, encoding {@code 6000-WRITE-TRANS} at L675 of
     * {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>Assumptions: the reference emits <b>twelve records per transaction</b> and the count is a
     * contract rather than an incidental. One is the plain-text detail line written at L679, and
     * eleven are markup records written at L682, L685, L692, L694, L697, L704, L706, L709, L716, L718
     * and L721. The two mapper calls below return one and eleven records respectively, so the
     * per-transaction budget is carried by the layer that owns the widths and is checkable there.</p>
     *
     * <p>Assumptions: the three rendered values are prepared once and shared by both artifacts,
     * because the reference moves them into the plain-text items at L676 to L678 and the markup
     * paragraph then renders those same items. Preparing them per artifact would let the identifier,
     * the description or the amount differ between the two documents for one transaction.</p>
     *
     * @param row the projected transaction row to render
     * @param sink the destination for both record streams
     * @throws IllegalStateException if the sink refuses a record, which stops the run rather than
     *     leaving a transaction present in one artifact and absent from the other
     */
    private void writeTransaction(StatementTransactionView row, StatementSink sink) {
        StatementTextMapper.PreparedTransactionFields fields =
                StatementTextMapper.prepareTransactionFields(
                        row.key().transactionId(), row.description(), row.amount());

        sink.writeStatementRecord(StatementTextMapper.emitTransactionLine(fields));
        for (byte[] record : StatementHtmlMapper.emitTransactionRow(fields)) {
            sink.writeMarkupRecord(record);
        }
    }

    /**
     * Emits one statement's trailer in both artifacts, encoding L433 to L454 of
     * {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>Assumptions: the reference converts the accumulated total in <b>two</b> moves, not one.
     * {@code MOVE WS-TOTAL-AMT TO WS-TRN-AMT} at L433 takes the packed accumulator declared under the
     * group-level {@code COMP-3} at L64 into the display item {@code WS-TRN-AMT PIC S9(9)V99} at L68,
     * and only then does {@code MOVE WS-TRN-AMT TO ST-TOTAL-TRAMT} at L434 reach the edit mask. The
     * intermediate item's nine-and-two shape is the mask's domain, so the two steps are not
     * interchangeable with one direct conversion; the mapper is handed the exact total and applies
     * that domain and the mask together, which keeps the pair of conversions in the one layer that
     * can check both.</p>
     *
     * <p>Assumptions: the plain-text trailer is three records, written at L435, L436 and L437, and the
     * markup footer is eight, written from the fragments set at L439, L441, L443, L445, L447, L449,
     * L451 and L453 in that order. Both are emitted per statement rather than once per run, because
     * both sit inside the traversal step the mainline performs at L326 for every cross-reference
     * row.</p>
     *
     * @param cardTotal the accumulated total of the card's transactions
     * @param sink the destination for both record streams
     * @throws IllegalStateException if the sink refuses a record, which stops the run rather than
     *     leaving a statement without the total it accumulated
     */
    private void emitCardTrailer(Money cardTotal, StatementSink sink) {
        StatementTextMapper.PreparedTrailerFields trailer =
                StatementTextMapper.prepareCardTrailerFields(cardTotal);

        for (byte[] record : StatementTextMapper.emitCardTrailer(trailer)) {
            sink.writeStatementRecord(record);
        }
        for (byte[] record : StatementHtmlMapper.emitDocumentFooter()) {
            sink.writeMarkupRecord(record);
        }
    }

    /**
     * Resolves the credit score the heading band carries.
     *
     * <p>Refactoring Rationale: the score is read from the customer row and no longer supplied by a
     * caller-passed resolver function, and the reasoning that justified the resolver is withdrawn
     * because its premise was false. That reasoning said the band needed the value but the projection
     * "does not carry it", citing the customer view's own comment that a credit assessment is not
     * statement heading data. It is statement heading data: {@code app/cbl/CBSTM03A.CBL} L485 moves
     * {@code CUST-FICO-CREDIT-SCORE} into {@code ST-FICO-SCORE}, declared {@code PIC X(20)} at L118 of
     * {@code app/cpy/COSTM01.CPY}, and the migrated renderer emits that band. The column is now
     * projected, so the value comes from the row it belongs to. What the resolver actually bought was
     * an unfilled seam: no production component supplied one, so the generation method it guarded could
     * not be invoked at all.</p>
     *
     * <p>Assumptions: a null score is a defect in the data rather than a state to render around, so it
     * stops the run. The base column is declared {@code SMALLINT NOT NULL}, so the only way a null
     * reaches here is a relation redefined underneath this module, which is exactly the condition
     * register entry <b>R11</b> says to report rather than work around.</p>
     *
     * <p>Assumptions: a negative score is refused here as well as at the mapper, and the duplication is
     * deliberate: the source picture {@code PIC 9(03)} is unsigned and has no position to represent a
     * sign, so a negative value cannot have come from a conforming record.</p>
     *
     * <p>Trade-offs: the refusal names no identifier. Refactoring Rationale: it named the customer
     * identifier, on the ground that an operator needs to find the offending customer -- and
     * {@code docs/architecture/observability.md} names the customer identifier among the values an
     * operator-read rendering must OMIT. What locates the failure instead is the correlation identifier
     * on the request-scoped line and the {@code batch.batch_run} step ledger for a run, both of which
     * the same document records as the substitutes for exactly this need.</p>
     *
     * @param heading the heading row whose score is wanted; must not be {@code null}
     * @return the resolved credit score
     * @throws IllegalStateException if the row carries no score, or carries a negative one, which the
     *     unsigned source picture {@code PIC 9(03)} has no position to represent
     */
    private static int creditScoreOf(StatementHeading heading) {
        Short score = heading.ficoCreditScore();
        if (score == null) {
            throw abend("CUSTFILE", "customer row carries no credit score");
        }
        if (score < 0) {
            throw abend("CUSTFILE", "customer row carries a negative credit score");
        }
        return score;
    }

    /**
     * Builds the failure that replaces the reference's abend service.
     *
     * <p>Refactoring Rationale: {@code 9999-ABEND-PROGRAM} at L921 of {@code app/cbl/CBSTM03A.CBL}
     * displays a line at L922 and then issues {@code CALL 'CEE3ABD'} at L923. That is a z/OS Language
     * Environment service with no cloud analogue at all, so it is replaced rather than mapped: nothing
     * in the target can terminate a task the way it does, and emulating it by halting the process would
     * take down a container serving other work. A thrown failure carrying the structured abend fields
     * reaches the shared advice, which renders them, and it unwinds the read-only transaction the run
     * opened.</p>
     *
     * <p>Assumptions: the fields are the four the reference's abend area declares, so an operator reads
     * the same shape as before. The code and the culprit are the constants declared above, the reason
     * names the relation and the identifier that failed, and the message is the reference's own display
     * text. Trade-offs: the detail is rendered into the failure's message rather than attached as a
     * typed member, because the shared advice already constructs its own detail for an unexpected
     * failure and a second channel for the same four fields would let the two disagree about one
     * abend.</p>
     *
     * @param relation the input the reference names in its display, such as {@code CUSTFILE}
     * @param reason what could not be resolved, including the identifier that failed
     * @return the failure to throw, never {@code null} and never itself thrown from here
     */
    private static IllegalStateException abend(String relation, String reason) {
        AbendDetail detail = new AbendDetail(ABEND_CODE, ABEND_CULPRIT, reason, "ABENDING PROGRAM");
        return new IllegalStateException("ERROR READING " + relation
                + ": abendCode=" + detail.abendCode()
                + " abendCulprit=" + detail.abendCulprit()
                + " abendReason=" + detail.abendReason()
                + " abendMsg=" + detail.abendMsg());
    }

    /**
     * Assembles the customer name the statement heads with.
     *
     * <p>Assumptions: the three parts are joined with one blank literal after each, unconditionally,
     * which is what L462 to L469 of {@code app/cbl/CBSTM03A.CBL} does. An absent middle name therefore
     * leaves a blank <b>pair</b> in the assembled name, and that pair is load-bearing: the markup
     * rendering cuts at it while the plain-text rendering does not, so the two artifacts show different
     * names for such a customer. That is registered as {@code D-STMT-PAIRED-BLANK-NAME} in
     * {@code docs/architecture/cobol-to-service-traceability.md} and is reproduced here rather than
     * reconciled, because collapsing the pair would change the characters of the first line of every
     * plain-text statement.</p>
     *
     * <p>Assumptions: this assembles the name a <b>response</b> carries, and the artifact's own name
     * band is assembled by the mapper from the same three parts. The two are separate because the band
     * has a declared width and a response component does not, and a response that reused the band's
     * padded form would publish trailing blanks as data.</p>
     *
     * @param heading the heading row carrying the three name parts for the requested card
     * @return the assembled name, with one blank after each of the three parts and any absent part
     *     contributing only its blank
     */
    private static String assembleName(StatementHeading heading) {
        // WHY : Refactoring Rationale: the null test that stood here is gone, because the heading's own
        //       constructor now renders an absent middle name as blanks and an absent second address
        //       line likewise. Keeping the test would have left two answers to one question -- this
        //       method's and the constructor's -- and the two would have had to be changed together
        //       forever after. The composed value is unchanged either way: a blank middle name still
        //       produces the two separators the reference's own MOVE of a space-filled field produces.
        return heading.firstName() + " " + heading.middleName() + " " + heading.lastName();
    }

    /**
     * Renders one projected transaction row as the statement line a caller receives.
     *
     * <p>Assumptions: the narrowed card number is passed in rather than read from the row's own key.
     * The two are equal, since the projection already narrows the number, and passing the value the
     * request resolved makes that equality the caller's single source rather than a coincidence each
     * row is trusted to reproduce.</p>
     *
     * <p>Assumptions: the merchant identifier is rendered as a digit string rather than published as a
     * number, on the rule every identifier in this migration follows: a number crossing a boundary
     * loses its leading zeros and can come back a different identifier.</p>
     *
     * <p>Assumptions: both timestamps are carried at their full declared precision of
     * {@value TimestampFormatter#TIMESTAMP_LENGTH} characters, which is what
     * {@code app/cpy/COSTM01.CPY} declares at L34 and L35. The reference's own job reaches this program
     * with less: {@code app/jcl/CREASTMT.JCL} L54 reformats only positions 1 to 328 of the 350-character
     * record and carries just the first 24 characters of the processing stamp, so the last two
     * fractional digits do not survive the sort step and positions 329 to 350 arrive blank. That is an
     * artefact of the reformatting rather than a narrower contract, so the full precision is adopted
     * here and the artefact is recorded rather than reproduced; a stamp of blanks is carried through as
     * it stands, and an absent stamp stays absent instead of being replaced by a reading of any
     * kind.</p>
     *
     * @param narrowedCard the narrowed primary account number the request resolved
     * @param row the projected transaction row
     * @return the statement line for that row
     * @throws IllegalArgumentException if the row's own components fall outside the response contract
     */
    private static StatementTransactionResponse toLine(
            String narrowedCard, StatementTransactionView row) {
        return new StatementTransactionResponse(
                narrowedCard,
                row.key().transactionId(),
                row.typeCode(),
                row.categoryCode(),
                row.source(),
                row.description(),
                row.amount(),
                row.merchantId() == null ? null : String.valueOf(row.merchantId()),
                row.merchantName(),
                row.merchantCity(),
                row.merchantPostalCode(),
                stamp(row.originatingTimestamp()),
                stamp(row.processingTimestamp()));
    }

    /**
     * Renders an instant as the reference-compatible stamp, preserving absence.
     *
     * <p>Assumptions: {@code null} is carried through rather than replaced with a formatted epoch or
     * with blanks. {@code app/cpy/COSTM01.CPY} declares both stamps {@code PIC X(26)} at L34 and L35,
     * an opaque character item that holds spaces when unset, so an absent stamp is a state the
     * reference has and substituting any reading would assert a time nothing recorded. Blanks are
     * equally not substituted, because a caller cannot tell a blank this method invented from a blank
     * the record carried.</p>
     *
     * @param value the instant to render, or {@code null}
     * @return the rendered stamp, or {@code null} when {@code value} is {@code null}
     */
    private static String stamp(LocalDateTime value) {
        return value == null ? null : TimestampFormatter.format(value);
    }

    /**
     * Builds the location of one stored statement artifact.
     *
     * <p>Refactoring Rationale: the key was the account identifier in full, a hyphen, the card's last
     * four digits and the suffix, and the comment defending it said that "no primary account number
     * appears in an object key". That was true and was answering too narrow a question. An object key is
     * written to the store's own access log for every request that touches the object, is returned by
     * every listing and appears in a bucket inventory, none of which a content-encryption key reaches --
     * so a key discloses whatever it spells out to a wider audience than the cardholder. An eleven-digit
     * account identifier is named by the sensitive-data logging contract in
     * {@code docs/architecture/observability.md} in its own right, and four card digits beside it narrow
     * a cardholder further than either does alone. The key now carries a keyed opaque token instead,
     * which discloses neither and still names one artifact stably.</p>
     *
     * <p>Assumptions: the token is computed over the account identifier and the card fingerprint
     * together, so two cards of one account yield two artifacts rather than overwriting each other, and
     * a card that moves between accounts yields a new artifact rather than silently replacing the
     * statement of its former account.</p>
     *
     * <p>Assumptions: the token is STABLE for one card under one key, so rerunning a night overwrites
     * the artifact it replaces instead of accumulating a second copy under a new name. That is the
     * property a random identifier would have cost, and it is why the tokeniser is keyed rather than
     * random -- the reasoning is recorded in full on {@code ArtifactIdentityConfig}.</p>
     *
     * @param heading the heading row naming the account and the card the artifact belongs to; must not
     *     be {@code null}
     * @param suffix the artifact suffix, either {@value #PLAIN_TEXT_SUFFIX} or {@value #HTML_SUFFIX}
     * @return the artifact location, never {@code null}
     */
    private String artifactUri(StatementHeading heading, String suffix) {
        String token = this.artifactIdentity.token(ARTIFACT_TOKEN_PURPOSE,
                heading.accountId() + ":" + heading.cardFingerprint());
        return "s3://" + outputBucket + "/" + statementPrefix + token + suffix;
    }
}
