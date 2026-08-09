package com.carddemo.reporting.service;

import com.carddemo.common.error.AbendDetail;
import com.carddemo.common.error.ApiError;
import com.carddemo.common.error.ClientInputException;
import com.carddemo.common.money.Money;
import com.carddemo.common.time.TimestampFormatter;
import com.carddemo.reporting.domain.AccountView;
import com.carddemo.reporting.domain.CardXrefView;
import com.carddemo.reporting.domain.CustomerView;
import com.carddemo.reporting.domain.StatementTransactionView;
import com.carddemo.reporting.dto.StatementDocument;
import com.carddemo.reporting.dto.StatementRequest;
import com.carddemo.reporting.dto.StatementResponse;
import com.carddemo.reporting.dto.StatementTransactionResponse;
import com.carddemo.reporting.mapper.ReportingDtoMapper;
import com.carddemo.reporting.mapper.StatementHtmlMapper;
import com.carddemo.reporting.mapper.StatementTextMapper;
import com.carddemo.reporting.repository.StatementAccountRepository;
import com.carddemo.reporting.repository.StatementCardXrefRepository;
import com.carddemo.reporting.repository.StatementCustomerRepository;
import com.carddemo.reporting.repository.StatementTransactionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>Every amount is {@code com.carddemo.common.money.Money}, carried at scale 2 with HALF_UP
 * rounding, because {@code TRNX-AMT} is declared {@code PIC S9(09)V99} at L29 of
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

    private final StatementCustomerRepository customers;

    private final StatementAccountRepository accounts;

    // WHY : Assumptions: the store and prefix are configuration rather than stored values, so no
    //       relation has to be written to record where an artifact went -- which matters because this
    //       context holds no writable relation at all. Deriving a location also lets a caller
    //       construct the same one independently, which is what the reference's constant data-set
    //       names at L87 and L92 of app/jcl/CREASTMT.JCL gave for free.
    private final String outputBucket;

    private final String statementPrefix;

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
     * @param cardXrefs the cross-reference cursor and keyed read replacing the sequential
     *     cross-reference definition at L39 of {@code app/cbl/CBSTM03B.CBL}
     * @param customers the customer read by identity replacing the random definition at L45 of
     *     {@code app/cbl/CBSTM03B.CBL}
     * @param accounts the account read by identity replacing the random definition at L51 of
     *     {@code app/cbl/CBSTM03B.CBL}
     * @param outputBucket the object store the two artifacts are published to, supplied by
     *     {@value #OUTPUT_BUCKET_PROPERTY}
     * @param statementPrefix the key prefix the artifacts sit under, supplied by
     *     {@value #STATEMENT_PREFIX_PROPERTY}
     * @throws NullPointerException if any collaborator or configuration value is {@code null}
     */
    public StatementService(
            StatementTransactionRepository transactions,
            StatementCardXrefRepository cardXrefs,
            StatementCustomerRepository customers,
            StatementAccountRepository accounts,
            @Value("${" + OUTPUT_BUCKET_PROPERTY + "}") String outputBucket,
            @Value("${" + STATEMENT_PREFIX_PROPERTY + "}") String statementPrefix) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.cardXrefs = Objects.requireNonNull(cardXrefs, "cardXrefs must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.outputBucket = Objects.requireNonNull(outputBucket, "outputBucket must not be null");
        this.statementPrefix =
                Objects.requireNonNull(statementPrefix, "statementPrefix must not be null");
    }

    /**
     * Reports what one card's statement covers, without producing either artifact.
     *
     * <p>Assumptions: this answers a request and does not generate, so it repeats the four reads the
     * reference performs per card and then stops. It exists separately from generation because a
     * request-time read that also wrote would produce a document that then disagreed with the one the
     * run produced.</p>
     *
     * <p>Assumptions: the running total is accumulated over the same rows in the same card order the
     * artifact would use, so the figure reported here is the figure the artifact's trailer carries at
     * L433 to L434 of {@code app/cbl/CBSTM03A.CBL}.</p>
     *
     * @param request the card the statement is wanted for, optionally naming the account it is
     *     expected to resolve to
     * @return the heading figures, the accumulated total, the row count and the two artifact
     *     locations
     * @throws ClientInputException if no card number is supplied, if the stated account is not the
     *     one the cross-reference resolves to, or if two distinct cards share the requested masked
     *     rendering
     * @throws NoSuchElementException if no cross-reference row exists for the requested card
     * @throws IllegalStateException if the cross-reference names a customer or an account that does
     *     not resolve, which the reference treats as an abend rather than as an omission
     */
    @Transactional(readOnly = true)
    public StatementResponse describe(StatementRequest request) {
        return read(request).heading();
    }

    /**
     * Reports one card's statement together with the lines it covers, without producing either
     * artifact.
     *
     * <p>Assumptions: the lines are the same rows in the same order the artifact renders, so a caller
     * comparing this against a stored artifact is comparing one traversal against itself rather than
     * two independently ordered reads. The order is the transaction identifier ascending, which the
     * cursor's own {@code order by} fixes and which is the second sort key of
     * {@code app/jcl/CREASTMT.JCL} L53; nothing here re-sorts, because a second ordering rule could
     * disagree with the one the artifact was written under.</p>
     *
     * @param request the card the statement is wanted for, optionally naming the account it is
     *     expected to resolve to
     * @return the heading and one line per transaction, in card-then-transaction order
     * @throws ClientInputException if no card number is supplied, if the stated account is not the
     *     one the cross-reference resolves to, or if two distinct cards share the requested masked
     *     rendering
     * @throws NoSuchElementException if no cross-reference row exists for the requested card
     * @throws IllegalStateException if the cross-reference names a customer or an account that does
     *     not resolve, which the reference treats as an abend rather than as an omission
     */
    @Transactional(readOnly = true)
    public StatementDocument compose(StatementRequest request) {
        ResolvedStatement resolved = read(request);
        return new StatementDocument(resolved.heading(), resolved.lines());
    }

    /**
     * One card's resolved statement, carried between the two request-edge methods.
     *
     * <p>Assumptions: this exists so that the four reads and the traversal happen once for either
     * entry point. Trade-offs: the alternative was for the heading method to read without the lines,
     * which would have been the cheaper read but would have accumulated the total from a second
     * traversal, and two traversals of a relation that concurrent writers may extend can disagree
     * about the total they report.</p>
     *
     * @param heading the heading figures and artifact locations for the card
     * @param lines one line per transaction, in the order the artifact renders them
     */
    private record ResolvedStatement(
            StatementResponse heading, List<StatementTransactionResponse> lines) {
    }

    /**
     * Resolves one requested card to its heading and its lines through the reference's four reads.
     *
     * <p>Assumptions: the requested number is narrowed before any read, because every relation in the
     * reporting schema presents a card number already narrowed and a stored number would match none
     * of them. The narrowing is delegated to the mapper that owns it for this context rather than
     * repeated here: a second narrowing rule would give one value two renderings, so a lookup could
     * narrow one way while a response narrowed the other and the two would silently fail to match.
     * The operation is idempotent by its own contract, so a caller that has already narrowed reaches
     * the same row.</p>
     *
     * <p>Assumptions: the traversal is collected because the total, the count and the rendered lines
     * each need the rows and a forward-only cursor can be traversed once. Nothing bounds that
     * collection: this is the request-edge counterpart of the same decision D-2 records, so a card
     * with more transactions than the reference's inner table could hold is reported in full rather
     * than reported short.</p>
     *
     * @param request the card the statement is wanted for, optionally naming its expected account
     * @return the heading and the lines for that card
     * @throws ClientInputException if no card number is supplied, if the stated account is not the
     *     one the cross-reference resolves to, or if two distinct cards share the narrowed rendering
     * @throws NoSuchElementException if no cross-reference row exists for the requested card
     * @throws IllegalStateException if the cross-reference names a customer or an account that does
     *     not resolve
     */
    private ResolvedStatement read(StatementRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String requestedCard = request.cardNumber();
        if (requestedCard == null || requestedCard.isBlank()) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "cardNumber",
                    "cardNumber must be supplied");
        }
        String narrowedCard = ReportingDtoMapper.maskPrimaryAccountNumber(requestedCard);

        // WHY : Assumptions: a card a caller ASKED for and that does not exist is a different
        //       condition from a dimension the cross-reference itself names and that does not
        //       resolve. The first is the caller naming something absent, which this module reports
        //       as an absent element; the second is a referential-integrity violation inside the
        //       data, which the reference abends on at L921. Collapsing the two would either turn a
        //       broken load into a benign answer or turn a mistyped card number into a run failure.
        CardXrefView xref = cardXrefs.findByCardNum(narrowedCard)
                .orElseThrow(() -> new NoSuchElementException(
                        "no cross-reference row for the requested card"));
        requireStatedAccountMatches(request, xref);

        CustomerView customer = requireCustomer(xref);
        AccountView account = requireAccount(xref);

        List<StatementTransactionResponse> lines = new ArrayList<>();
        Money runningTotal = Money.ZERO;
        String groupingToken = null;
        try (Stream<StatementTransactionView> rows =
                transactions.streamByCardNumber(narrowedCard)) {
            for (StatementTransactionView row : (Iterable<StatementTransactionView>) rows::iterator) {
                groupingToken = requireOneCard(groupingToken, row);
                runningTotal = runningTotal.plus(row.amount());
                lines.add(toLine(narrowedCard, row));
            }
        }

        StatementResponse heading = new StatementResponse(
                narrowedCard,
                String.valueOf(account.getAccountId()),
                assembleName(customer),
                runningTotal,
                lines.size(),
                artifactUri(account.getAccountId(), narrowedCard, PLAIN_TEXT_SUFFIX),
                artifactUri(account.getAccountId(), narrowedCard, HTML_SUFFIX),
                // WHY : Assumptions: the produced-at stamp is left blank on a request-edge read and
                //       is not invented. Its own contract on StatementResponse records that an
                //       all-blank value round-trips unchanged and is deliberately not refused, and
                //       this method has produced no artifact to stamp. The only honest alternative
                //       would be a clock reading, which the package charter forbids on this path and
                //       which would report a production time for a document nothing produced.
                " ".repeat(TimestampFormatter.TIMESTAMP_LENGTH));

        return new ResolvedStatement(heading, List.copyOf(lines));
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
     * <p>Assumptions: the cursor is opened over the cross-reference in card order and the transactions
     * of one card are read inside that traversal, which is the streaming join that replaces the
     * working-storage index. Neither dimension is bounded: a run may produce more statements than the
     * reference's outer table could hold and any one statement may carry more transactions than its
     * inner table could hold, which is divergence D-2 as the class documentation sets out. The cursors
     * carry a retrieval batch size in the repositories that declare them; a batch size governs how
     * many rows a fetch brings back at once and cannot end a traversal, so it is not a ceiling and
     * cannot shorten an artifact.</p>
     *
     * <p>Assumptions: the whole run is one read-only transaction. Both cursors require an enclosing
     * transaction rather than starting one of their own, so that each outlives the call that opened
     * it, and a single transaction also gives every statement in one run the same read view instead of
     * letting a concurrent writer change the totals part way through.</p>
     *
     * @param sink the destination for both record streams, cleared once before the first statement
     * @param creditScoreSource resolves the credit score of a customer, for the reason recorded on
     *     {@link #creditScoreOf(CustomerView, ToIntFunction)}
     * @return the number of statements produced, which is the number of cross-reference rows read
     * @throws NullPointerException if {@code sink} or {@code creditScoreSource} is {@code null}
     * @throws IllegalStateException if a cross-reference row names a customer or an account that does
     *     not resolve, or if the credit score source yields a value the statement band cannot carry,
     *     either of which stops the run as the reference's abend does
     */
    @Transactional(readOnly = true)
    public int generateStatements(StatementSink sink, ToIntFunction<CustomerView> creditScoreSource) {
        Objects.requireNonNull(sink, "sink must not be null");
        Objects.requireNonNull(creditScoreSource, "creditScoreSource must not be null");

        // WHY : Assumptions: the previous run's artifacts are discarded BEFORE the first record and
        //       unconditionally, which is the deletion step app/jcl/CREASTMT.JCL runs at L66 ahead of
        //       the generator at L79. Doing it here rather than per statement is what makes a run that
        //       produces no statement still leave no stale artifact readable.
        sink.replaceArtifacts();

        int statementsProduced = 0;
        try (Stream<CardXrefView> xrefRows = cardXrefs.streamAllInCardNumberOrder()) {
            // WHY : Assumptions: the cursor is walked as an iterable rather than through a terminal
            //       stream operation because each row drives further reads and two nested writes, and
            //       a statement body reads as the paragraph it encodes when it is a loop. Trade-offs:
            //       the cast to Iterable is the idiom that adapts a Stream to an enhanced for without
            //       collecting it, and collecting it instead would materialise every cross-reference
            //       row in memory -- reintroducing, by a different route, the whole-input
            //       materialisation that D-2 removes.
            for (CardXrefView xref : (Iterable<CardXrefView>) xrefRows::iterator) {
                emitStatementForXref(xref, sink, creditScoreSource);
                statementsProduced++;
            }
        }
        return statementsProduced;
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
     * @param xref the cross-reference row naming the card, its customer and its account
     * @param sink the destination for both record streams
     * @param creditScoreSource resolves the credit score of the resolved customer
     * @throws IllegalStateException if the row names a customer or an account that does not resolve,
     *     or if the credit score cannot be carried by the statement band
     */
    private void emitStatementForXref(CardXrefView xref, StatementSink sink,
            ToIntFunction<CustomerView> creditScoreSource) {
        CustomerView customer = requireCustomer(xref);
        AccountView account = requireAccount(xref);

        createStatement(customer, account, sink, creditScoreSource);

        Money cardTotal = emitTransactionsForCard(xref.getCardNum(), sink);
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
     * @param customer the customer the statement is addressed to
     * @param account the account the statement reports on
     * @param sink the destination for both record streams
     * @param creditScoreSource resolves the credit score the heading band carries
     * @throws IllegalStateException if the credit score cannot be carried by the statement band
     */
    private void createStatement(CustomerView customer, AccountView account, StatementSink sink,
            ToIntFunction<CustomerView> creditScoreSource) {
        StatementTextMapper.PreparedHeaderFields header = StatementTextMapper.prepareHeaderFields(
                customer.getFirstName(),
                customer.getMiddleName(),
                customer.getLastName(),
                customer.getAddressLine1(),
                customer.getAddressLine2(),
                customer.getAddressLine3(),
                customer.getStateCode(),
                customer.getCountryCode(),
                customer.getPostalCode(),
                account.getAccountId(),
                account.getCurrentBalance(),
                creditScoreOf(customer, creditScoreSource));

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
     * @param narrowedCard the narrowed card number whose transactions are wanted
     * @param sink the destination for both record streams
     * @return the accumulated total of the card's transactions, zero when the card has none
     * @throws IllegalStateException if the sink refuses a record, which stops the run rather than
     *     leaving a statement missing transactions it totalled
     */
    private Money emitTransactionsForCard(String narrowedCard, StatementSink sink) {
        // WHY : Assumptions: the accumulator is a LOCAL and not a member, which is what resets it per
        //       statement without an explicit reset step. The reference needs MOVE ZERO TO
        //       WS-TOTAL-AMT at L325 precisely because its accumulator is process-wide
        //       working storage; a member here would be shared across concurrent runs as well as
        //       across statements, so the reset would be necessary and insufficient at once.
        Money cardTotal = Money.ZERO;
        try (Stream<StatementTransactionView> rows = transactions.streamByCardNumber(narrowedCard)) {
            for (StatementTransactionView row : (Iterable<StatementTransactionView>) rows::iterator) {
                writeTransaction(row, sink);
                cardTotal = cardTotal.plus(row.amount());
            }
        }
        return cardTotal;
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
     * Resolves the customer a cross-reference row names, encoding {@code 2000-CUSTFILE-GET} at L368 of
     * {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>Assumptions: an unresolved customer stops the run. The reference's read has exactly two arms,
     * {@code WHEN '00' CONTINUE} and {@code WHEN OTHER}, at L379 to L386, and the second displays the
     * relation name and the return code and then performs the abend at L921. There is <b>no
     * not-found arm at all</b>: unlike the cross-reference read at L353 to L362, which tolerates
     * end-of-file through {@code WHEN '10'}, a missing customer for an existing cross-reference row is
     * a referential-integrity violation that kills the whole run.</p>
     *
     * <p>Assumptions: that is why the resolution is a lookup returning an optional value followed by an
     * explicit refusal, rather than a join. Alternatives Considered: expressing the two dimensions as
     * an inner join in the query. Rejected because an inner join <b>silently drops</b> the
     * cross-reference row instead of stopping, which yields a run that appears to succeed while
     * producing fewer statements and different totals than the reference would; the discrepancy would
     * surface only as a golden-master difference, and only for the population that has the broken
     * reference. Refusing here reproduces the reference's outcome and names the identifier that failed
     * to resolve, which a join has no place to report.</p>
     *
     * @param xref the cross-reference row naming the customer
     * @return the resolved customer, never {@code null}
     * @throws IllegalStateException if no customer row exists for the identifier the cross-reference
     *     names
     */
    private CustomerView requireCustomer(CardXrefView xref) {
        return customers.findById(xref.getCustomerId())
                .orElseThrow(() -> abend("CUSTFILE",
                        "no customer row for customer " + xref.getCustomerId()));
    }

    /**
     * Resolves the account a cross-reference row names, encoding {@code 3000-ACCTFILE-GET} at L392 of
     * {@code app/cbl/CBSTM03A.CBL}.
     *
     * <p>Assumptions: an unresolved account stops the run, for the reason recorded on
     * {@link #requireCustomer(CardXrefView)}. The account read at L403 to L410 carries the same two
     * arms and the same absence of a not-found arm, and reaches the same abend at L921.</p>
     *
     * <p>Assumptions: the read is by the projection's own identifier and by nothing else, which is the
     * read {@code app/cbl/CBSTM03B.CBL} declares {@code ACCESS MODE IS RANDOM} at L51 with
     * {@code RECORD KEY IS FD-ACCT-ID} at L52, and for which the caller supplies the whole eleven-digit
     * key at L396 to L398. One identifier therefore yields at most one row.</p>
     *
     * @param xref the cross-reference row naming the account
     * @return the resolved account, never {@code null}
     * @throws IllegalStateException if no account row exists for the identifier the cross-reference
     *     names
     */
    private AccountView requireAccount(CardXrefView xref) {
        return accounts.findById(xref.getAccountId())
                .orElseThrow(() -> abend("ACCTFILE",
                        "no account row for account " + xref.getAccountId()));
    }

    /**
     * Resolves the credit score the heading band carries.
     *
     * <p>Assumptions: the score is supplied by the caller and is neither read from a relation nor
     * defaulted here, and the reason is a deliberate decision taken elsewhere. The band needs it:
     * L485 of {@code app/cbl/CBSTM03A.CBL} moves {@code CUST-FICO-CREDIT-SCORE} into its item, and the
     * golden statement renders it. But the projection this module reads does not carry it:
     * {@code data-migration/sql/V1__reporting_views.sql} omits that column from the customer view at
     * its L472 to L473 on the ground that a credit assessment is not statement heading data, the
     * view's own comment records the omission, and {@code CustomerView} documents it as a decision
     * rather than an oversight and directs that a band genuinely needing it is a change to the
     * relation, reported against that artifact rather than worked around.</p>
     *
     * <p>Trade-offs: a caller-supplied resolver is accepted so that this class fabricates nothing.
     * Alternatives Considered: substituting zero, which would print a real-looking credit score of 000
     * in a cardholder's statement and is the one outcome worse than refusing; and widening the
     * projection from here, which is not available to this module at all, because the column is absent
     * from the relation and the login role holds no privilege on the schema the base table lives in, so
     * naming it would fail the first read rather than return a value. What is given up is that a caller
     * has to supply the value; what is kept is that no invented figure can reach a financial
     * document.</p>
     *
     * @param customer the customer whose score is wanted
     * @param creditScoreSource the caller-supplied resolver
     * @return the resolved credit score
     * @throws IllegalStateException if the resolver yields a negative value, which the unsigned source
     *     picture {@code PIC 9(03)} has no position to represent
     */
    private static int creditScoreOf(CustomerView customer,
            ToIntFunction<CustomerView> creditScoreSource) {
        int score = creditScoreSource.applyAsInt(customer);
        if (score < 0) {
            // WHY : Assumptions: this is refused here rather than left to the mapper so that the
            //       failure names the customer whose score was unusable. The mapper refuses a negative
            //       score too, on the same unsigned-picture ground, but it sees only the number and
            //       could not say which of a run's statements produced it.
            // WHY : Assumptions: the reason is kept short enough to survive AbendDetail's declared
            //       50-character reason width, which truncates on the right rather than refusing. A
            //       longer sentence would lose the identifier, which is the one part an operator
            //       needs to find the offending customer.
            throw abend("CUSTFILE",
                    "negative credit score for customer " + customer.getCustomerId());
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
     * Refuses a request whose stated account is not the account the cross-reference names.
     *
     * <p>Assumptions: the account is optional on the request and is a cross-check rather than a second
     * key. When it is absent the cross-reference alone decides, which is what the reference does at
     * L322 of {@code app/cbl/CBSTM03A.CBL}; when it is present and disagrees, the caller has named two
     * things that cannot both be true and is told so rather than being served the card's statement
     * under the wrong account heading.</p>
     *
     * @param request the request, whose account component may be absent
     * @param xref the cross-reference row the requested card resolved to
     * @throws ClientInputException if the stated account is present and is not the resolved one
     */
    private static void requireStatedAccountMatches(StatementRequest request, CardXrefView xref) {
        String stated = request.accountId();
        if (stated == null || stated.isBlank()) {
            return;
        }
        if (Long.parseLong(stated) != xref.getAccountId()) {
            throw new ClientInputException(ApiError.CODE_VALIDATION, "accountId",
                    "the requested card resolves to a different account than the one stated");
        }
    }

    /**
     * Refuses a narrowed collision, in which two distinct cards render to one narrowed form.
     *
     * <p>Assumptions: a narrowed value is not a card identity, so the per-card grouping token on the
     * row decides which card a statement is for. Two cards sharing their last four digits narrow
     * identically, and the token is a keyed digest that distinguishes them while disclosing neither.
     * Checking row by row rather than over a collected group is what lets the check run inside a
     * traversal that is deliberately unbounded.</p>
     *
     * <p>Trade-offs: the request is refused rather than answered. That denies a statement to two
     * genuine cardholders until the request distinguishes them, and it is accepted because the
     * alternative is a statement whose total is the sum of two cards' activity, which is wrong for
     * both of them and says so nowhere. An empty traversal is not a collision: a card with no activity
     * has no token, and the reference produces a statement for it because the cross-reference walk
     * drives one statement per card whatever the activity.</p>
     *
     * @param knownToken the token seen so far in this traversal, or {@code null} on the first row
     * @param row the row just read
     * @return the token to carry forward, which is {@code knownToken} once one has been seen
     * @throws ClientInputException if the row's token differs from the one already seen, which means
     *     two distinct cards narrow to one rendering
     */
    private static String requireOneCard(String knownToken, StatementTransactionView row) {
        String token = row.cardFingerprint();
        if (knownToken == null) {
            return token;
        }
        if (!knownToken.equals(token)) {
            // WHY : Assumptions: the message names neither the narrowed rendering nor either token.
            //       The rendering carries four digits of a primary account number and a token is
            //       derived from the whole of one, so neither belongs in a message that may be logged;
            //       that a collision occurred is the whole of what a caller needs.
            throw new ClientInputException(ApiError.CODE_VALIDATION, "cardNumber",
                    "the requested card number narrows to a rendering shared by more than one "
                            + "distinct card, so a statement cannot be attributed");
        }
        return knownToken;
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
     * @param customer the customer row resolved for the requested card
     * @return the assembled name, with one blank after each of the three parts and any absent part
     *     contributing only its blank
     */
    private static String assembleName(CustomerView customer) {
        String middle = customer.getMiddleName() == null ? "" : customer.getMiddleName();
        return customer.getFirstName() + " " + middle + " " + customer.getLastName();
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
     * <p>Assumptions: the key is derived from the account identifier and the narrowed card's last four
     * digits rather than from a stored card number, so no primary account number appears in an object
     * key. An object key is written to an access log by the store itself and by every intermediary that
     * serves it, which is the same exposure the card context's addressing decision turns on.</p>
     *
     * @param accountId the account the statement is attributed to
     * @param narrowedCard the narrowed rendering of the card the statement is for
     * @param suffix the artifact suffix, either {@value #PLAIN_TEXT_SUFFIX} or {@value #HTML_SUFFIX}
     * @return the artifact location, never {@code null}
     */
    private String artifactUri(Long accountId, String narrowedCard, String suffix) {
        String tail = narrowedCard.substring(narrowedCard.length() - 4);
        return "s3://" + outputBucket + "/" + statementPrefix + accountId + "-" + tail + suffix;
    }
}
