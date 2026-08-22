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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
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

    /**
     * Object-name stem both run-wide statement artifacts are published under, being
     * {@code statements}.
     */
    public static final String STATEMENT_OBJECT_STEM = "statements";

    /**
     * Object name of the plain-text artifact one statement run publishes, being
     * {@code statements.txt}.
     *
     * <p>⚠️ Refactoring Rationale: this name and its markup counterpart are declared HERE, and the
     * writer's own constants are aliases of them, because the read side and the write side disagreeing
     * about an object name is exactly the defect a review found on this class: the response published a
     * per-card location while {@code S3StatementSink} wrote two run-wide objects, so every published
     * location named an object that did not exist. One declaration removes the class of defect rather
     * than the instance.
     *
     * <p>Assumptions: the declaration sits on this side and not on the writer because
     * {@code S3StatementSink} already depends on this class for the {@link StatementSink} seam, so the
     * writer can alias a constant here without a package cycle whereas this class importing the writer
     * would create one. Alternatives Considered: keeping both declarations and asserting their equality
     * in a test, which detects a drift instead of preventing it and leaves the wrong value compilable.
     */
    public static final String PLAIN_TEXT_OBJECT = STATEMENT_OBJECT_STEM + PLAIN_TEXT_SUFFIX;

    /**
     * Object name of the markup artifact the same statement run publishes, being
     * {@code statements.html}.
     */
    public static final String HTML_OBJECT = STATEMENT_OBJECT_STEM + HTML_SUFFIX;

    /**
     * Object name of the run index, being {@code statements-index.txt}.
     *
     * <p>Assumptions: the index is a THIRD artifact of the run rather than a member of either other
     * one. Neither artifact can carry it: the plain-text object is compared byte for byte against the
     * golden masters, so anything added to it is a parity failure, and the markup object is a rendered
     * document. Trade-offs: a third object per run, against a statement response that can say where in
     * the run's document one card's statement begins.
     *
     * <p>Assumptions: the index is NOT collectable through the artifact operation. It is an internal
     * locating aid whose records carry card fingerprints, and a fingerprint names exactly one card, so
     * publishing the whole index would hand a caller the portfolio's card identities in one request --
     * which is precisely the metadata disclosure the artifact selectors exist to avoid.
     */
    public static final String INDEX_OBJECT = STATEMENT_OBJECT_STEM + "-index" + PLAIN_TEXT_SUFFIX;

    /**
     * Object name of the manifest naming the run every read resolves against, being
     * {@code statements-manifest.txt}.
     *
     * <p>⚠️ Refactoring Rationale: this object is the commit point of a statement run, and it exists
     * because a review found the run publishing INCOHERENTLY. The three objects of a run were written
     * to fixed keys one after another, so each became visible the instant it was written: a reader
     * arriving between an artifact write and the index write held the PREVIOUS run's index over the NEW
     * run's artifact, and every position that index named then addressed whatever the new run had
     * placed there. A per-card response could therefore report a position lying inside another
     * cardholder's statement, with nothing in either object recording the mismatch. Writing a run's
     * objects under a per-run key prefix and naming the run here makes the last write the only visible
     * change: until this object names a run, that run's objects are addressed by nothing at all.
     *
     * <p>Assumptions: ONE small object is the commit record, rather than a marker beside each artifact,
     * because the property wanted is that exactly one run is current -- three markers can disagree with
     * each other and one cannot. Alternatives Considered: relying on the bucket's own versioning and
     * reading a matched version of each of the three objects, which the store supports; rejected
     * because a reader would then have to learn three version identifiers from somewhere, which is this
     * manifest again with more moving parts, and because a lifecycle rule expiring a noncurrent version
     * would break the pairing with nothing detecting it.
     *
     * <p>Assumptions: the manifest sits INSIDE the statement key prefix, beside the run prefixes it
     * names. The reporting task role is granted its object actions on the reporting key prefixes of the
     * dataset bucket, so an object introduced outside them would be unwritable and unreadable until an
     * environment root was changed too -- and a run that cannot publish its manifest is a run no reader
     * can see.
     */
    public static final String MANIFEST_OBJECT =
            STATEMENT_OBJECT_STEM + "-manifest" + PLAIN_TEXT_SUFFIX;

    /**
     * Key segment introducing one run's immutable object set, being {@code run=}.
     *
     * <p>Assumptions: the {@code name=value} shape matches the {@code dt=} and {@code gen=} segments
     * the dataset bucket already uses for the nightly generation families, so an operator listing the
     * bucket reads one convention rather than two. Trade-offs: a run prefix accumulates one directory
     * per run, where the retired fixed keys accumulated none; the bucket's noncurrent-version lifecycle
     * does not reclaim them because each key is written exactly once, so retention of superseded runs
     * is a lifecycle rule on the prefix rather than a property of the writer -- which is the honest
     * place for it, since only the deployment knows how long a superseded statement run must be
     * readable.
     */
    public static final String RUN_SEGMENT = "run=";

    /**
     * Number of characters a run identifier carries, being thirty-two.
     *
     * <p>Assumptions: thirty-two lower-case hexadecimal characters is a rendered version-4 UUID with
     * its hyphens removed, which is 122 bits of randomness -- so two runs, even two started in the same
     * second by two orchestrations, cannot collide in practice and neither can overwrite the other's
     * objects. Alternatives Considered: the business date, or a date and a sequence number; both were
     * rejected because a rerun of one date would then reuse the prefix of the run it is replacing,
     * which reintroduces exactly the mutation this scheme exists to remove.
     */
    public static final int RUN_ID_LENGTH = 32;

    /**
     * Path the statement surface is served under, being {@code /api/v1/reports/statements}.
     *
     * <p>Assumptions: declared here and ALIASED by {@code StatementController.BASE_PATH}, for the same
     * reason the two artifact object names are declared here and aliased by the writer: this class
     * composes a location that has to be the path the controller serves, and two independent
     * declarations of one path is the shape the defect this replaces had. Trade-offs: a service
     * declaring an HTTP path reads oddly, and the alternative -- a configured base URL -- is worse,
     * because a configured value can name a host or a path this deployment does not answer on and
     * nothing would detect it until a caller followed the location.
     */
    public static final String STATEMENTS_BASE_PATH = "/api/v1/reports/statements";

    /**
     * Path segment separating the artifact collection operation from the statement paths, being
     * {@code /artifacts/}.
     *
     * <p>Assumptions: the trailing separator belongs to the segment, so the location prefix and the
     * route pattern are both one concatenation away from it and neither has to remember to add it.
     * Both remain compile-time constants, which is what lets the route pattern sit in an annotation.
     */
    public static final String ARTIFACTS_SEGMENT = "/artifacts/";

    /**
     * Greatest number of integer positions a statement total this class publishes may occupy, being
     * nine.
     *
     * <p>⚠️ Refactoring Rationale: nine was enforced only by the artifact writer. {@code CobolEditMask}
     * refuses a tenth integer digit when it edits a total into the 13-character band item that
     * {@code ST-TOTAL-TRAMT} declares at L142 of {@code app/cbl/CBSTM03A.CBL}, but the two request-edge
     * operations return a total WITHOUT emitting an artifact, so nothing on that path met the encoder
     * and a total the reference band could not hold was published as a plain JSON value. The contract's
     * own {@code MonetaryAmount} admits nine integer digits, so the response was capable of carrying a
     * value it declares invalid.
     *
     * <p>Assumptions: the bound is nine because both reference items are nine --
     * {@code PIC S9(09)V99} at L29 of {@code app/cpy/COSTM01.CPY} for the amount being summed and
     * {@code PIC S9(9)V99 COMP-3} at L65 of the program for the accumulator. This constant is
     * DELIBERATELY separate from the encoder's own nine: this one bounds what a response body may
     * carry, the encoder's bounds what a band item may hold, and collapsing them would make a change
     * to either silently move the other.
     */
    /**
     * Path prefix the artifact download operation is served under, one opaque selector short of a
     * complete path.
     *
     * <p>Assumptions: the published location is a path this deployment serves and not an
     * {@code s3://} location, because the dataset bucket denies access outside the VPC endpoint -- so
     * an object-store location is unusable by the one caller the field exists for, while a path
     * carries the caller's own bearer token through the same authorization the rest of the surface
     * uses. Trade-offs: the path is a compile-time constant rather than a configured base URL, which
     * costs the ability to publish an absolute location naming a host, and buys the guarantee that a
     * location cannot name a host this deployment does not answer on. The gateway and the load
     * balancer both preserve the path, so a relative location resolves for a browser and for a service
     * client alike.
     *
     * <p>Assumptions: {@code StatementController} derives its own mapping from this constant, and
     * {@code StatementApiContractGateTest} asserts that the two agree, so a published location and the
     * route that serves it cannot drift apart.
     */
    public static final String ARTIFACT_LOCATION_PREFIX =
            STATEMENTS_BASE_PATH + ARTIFACTS_SEGMENT;

    public static final int STATEMENT_TOTAL_INTEGER_DIGITS = 9;

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

    /**
     * The two artifact object names, in the order a statement response reports them.
     *
     * <p>Assumptions: the list is the whole domain of what a selector may resolve to, which is what
     * lets {@link #resolveArtifactKey(String)} answer by lookup instead of by composition -- no part
     * of a caller-supplied value reaches an object key, so a caller cannot address an object this
     * service does not publish however the selector is manipulated.
     */
    private static final List<String> ARTIFACT_OBJECT_NAMES =
            List.of(PLAIN_TEXT_OBJECT, HTML_OBJECT);

    /**
     * The shape a run identifier must have before any part of it reaches an object key.
     *
     * <p>Assumptions: the identifier is validated on the way OUT of the manifest as strictly as on the
     * way in, and the pattern is anchored to the whole value by {@link java.util.regex.Matcher#matches}
     * rather than searched for. The manifest is an object in a bucket, so its content is not something
     * this class produced within the request it is serving; a value read from it is concatenated into a
     * key, and admitting {@code ../} or a leading slash there would let a manifest select an object
     * outside the statement prefix. Alternatives Considered: trusting the manifest because only this
     * service writes it, which is true today and is exactly the kind of assumption that stops being
     * true when a second writer appears.
     */
    private static final Pattern RUN_ID_PATTERN =
            Pattern.compile("[0-9a-f]{" + RUN_ID_LENGTH + "}");

    /**
     * Greatest number of bytes read from the manifest, being one identifier and a line ending's slack.
     *
     * <p>Assumptions: the manifest is read through a BOUNDED range rather than opened whole, because it
     * is a pointer file and an unbounded read of an object whose size this service does not control
     * would size an allocation from the store's answer. Sixteen bytes of slack past the identifier
     * covers a trailing newline of either convention and leaves a longer body to fail validation rather
     * than to be truncated into something that happens to validate.
     */
    private static final int MANIFEST_MAX_BYTES = RUN_ID_LENGTH + 16;

    private static final String ABEND_CULPRIT = "CBSTM03A";

    // WHY : Assumptions: four characters is what ABEND-CODE declares, so the code is chosen to fit
    //       rather than shortened on arrival. Trade-offs: one code covers every abend this class
    //       raises instead of one code per condition, because the reference draws no distinction
    //       either -- 9999-ABEND-PROGRAM at L921 is reached from three different reads and displays
    //       the same text for all of them -- and the reason component carries what separates them.
    private static final String ABEND_CODE = "STMT";

    /**
     * Names how much of a statement run a response may disclose to the caller that asked for it.
     *
     * <p>⚠️ Refactoring Rationale: this distinction exists because a review found a CONFIDENTIALITY
     * defect in the statement response, not because two callers wanted two shapes. A statement is
     * requested for one card, and the response carried the selectors of the run-wide plain-text and
     * markup artifacts -- objects holding every cardholder's statement in the portfolio -- so any
     * caller holding an ordinary group claim could ask for one card it was entitled to and be handed
     * the address of the whole run. The artifact route is now admitted to the administrative group
     * alone, and a response is assembled to match: a cardholder audience is answered with the figures
     * of its own statement and no handle to anything wider.
     *
     * <p>Assumptions: the audience is decided at the request edge from validated claims and passed in,
     * rather than read here from a security context. This class is called from a task with no request
     * and no principal at all -- {@code GenerateStatementsTask} -- so a class that reached for an
     * ambient principal would have to decide what an absent one means, and the safe answer there is not
     * the useful answer at the edge. Alternatives Considered: assembling the full response always and
     * redacting it in the controller; rejected because the redaction would then have to be extended by
     * hand every time a run-wide field is added, and because the position lookup it discards costs
     * several ranged reads of the index that a cardholder request now never performs.
     *
     * <p>Trade-offs: the two values are not a permission model and must not grow into one. They name
     * one decision -- whether run-wide detail is admissible in this answer -- and the authorization that
     * makes the decision lives in {@code SecurityConfig}, where it is enforced for the collection route
     * as well.
     */
    public enum ArtifactAudience {

        /**
         * A caller reading one card's statement, answered with that card's figures and nothing wider.
         */
        CARDHOLDER,

        /**
         * An operator reading a run, answered with the run's artifact handles and index positions too.
         */
        OPERATOR
    }

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



    // WHY : Assumptions: the prefix is configuration rather than a stored value, so no relation has to
    //       be written to record where an artifact went -- which matters because this context holds no
    //       writable relation at all. Trade-offs: the bucket is NO LONGER held here. It moved to
    //       ArtifactStore, which is the only collaborator that addresses the store, so this
    //       class can no longer name a bucket in a response body -- which is what it used to do.
    private final String statementPrefix;

    /**
     * The read side of the object store, which answers whether an artifact exists and when it was
     * written.
     *
     * <p>⚠️ Refactoring Rationale: this collaborator is what turns a published location from a claim
     * into a fact. Before it, the response asserted two locations unconditionally, so a caller reading
     * a statement heading for a card whose run had not happened yet received two locations that
     * resolved to nothing and a produced-at stamp of 26 blanks. Asking the store costs one
     * {@code HeadObject} per artifact and lets the response say "absent" where absent is the truth.
     */
    private final ArtifactStore artifacts;

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
     * @param statementPrefix the key prefix the artifacts sit under, supplied by
     *     {@value #STATEMENT_PREFIX_PROPERTY}
     * @param artifacts the read side of the object store, which reports whether each artifact exists
     *     and when it was last written
     * @param artifactIdentity the keyed tokeniser the artifact object key is built with, so that no
     *     account identifier and no part of a card number appears in a key an object store logs
     * @throws NullPointerException if any collaborator or configuration value is {@code null}
     */
    public StatementService(
            StatementTransactionRepository transactions,
            StatementCardXrefRepository cardXrefs,
            StatementCustomerRepository customers,
            StatementAccountRepository accounts,
            @Value("${" + STATEMENT_PREFIX_PROPERTY + "}") String statementPrefix,
            ArtifactStore artifacts,
            @Qualifier(ArtifactIdentityConfig.ARTIFACT_TOKENISER)
                    OpaqueIdentifier artifactIdentity) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.cardXrefs = Objects.requireNonNull(cardXrefs, "cardXrefs must not be null");
        this.customers = Objects.requireNonNull(customers, "customers must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.statementPrefix =
                Objects.requireNonNull(statementPrefix, "statementPrefix must not be null");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts must not be null");
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
    
        /**
         * Renders NOTHING but the presence of its optional members: all fourteen components are protected.
         *
         * <p>Purpose. This is the widest concentration of protected data anywhere in the migration -- a
         * primary account number, an account identifier, a name in three parts, an address in six, a credit
         * score and a balance, for one identified cardholder, in one object.
         * {@code docs/architecture/observability.md} L1093 to L1112 withholds every one of those
         * categories, and the compiler-generated rendering printed all of them together, which is the exact
         * join that rule exists to prevent.</p>
         *
         * <p>Assumptions: the card number is omitted rather than masked, unlike the statement RESPONSE this
         * heading is mapped into. The response carries a masked number because a client has to know which
         * card its statement is for; this internal heading is identified by the run it belongs to, so the
         * rule's second clause does not apply -- the sanctioned abbreviation is available only where a
         * rendering has no other way to say which row it describes.</p>
         *
         * <p>Assumptions: the card fingerprint is omitted too, and it is the component most likely to be
         * mistaken for safe. It is a derived value over the card number used to group a run's rows, so it is
         * a confirmable token over a sixteen-digit input -- the shared kernel's own analysis of unkeyed
         * digests, which that rule reproduces, is that a low-entropy input makes such a token disclose the
         * value it was meant to withhold.</p>
         *
         * <p>Trade-offs: the credit score is omitted although it is neither an identifier nor an amount. It
         * is a per-person financial assessment, which is the category the rule's list enumerates rather
         * than an exhaustive set, and there is no statement-generation fault it helps diagnose.</p>
         *
         * @return a rendering reporting which optional members are present, with all fourteen values
         *     withheld; never {@code null}
         */
        @Override
        public String toString() {
            return "StatementHeading[middleNamePresent=" + (this.middleName != null)
                    + ", addressLine2Present=" + (this.addressLine2 != null)
                    + ", personalData=[REDACTED]]";
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
     * @param audience how much of the run this answer may disclose: an operator audience carries the
     *     current run's artifact locations, its write instant and this card's position within it, and a
     *     cardholder audience carries none of the three; must not be {@code null}
     * @return the heading figures, the accumulated total and the row count, with the run-wide fields
     *     present only for an operator audience and only where the store holds the artifact
     * @throws ClientInputException if the request does not name exactly one of a card and an account,
     *     or if the named account holds more than one card
     * @throws NoSuchElementException if no card with the requested number exists, or the requested
     *     account holds no card
     * @throws IllegalStateException if the cross-reference names a customer or an account that does
     *     not resolve, which the reference treats as an abend rather than as an omission
     */
    public StatementResponse describe(StatementRequest request, ArtifactAudience audience) {
        StatementHeading heading = resolveHeading(request);
        StatementTransactionRepository.StatementAggregate totals =
                transactions.aggregateByCardFingerprint(heading.cardFingerprint());
        return headingResponse(heading, Money.of(totals.getTotal()), totals.getLineCount(), audience);
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
     * @param audience how much of the run the heading beside the rows may disclose, on the terms
     *     {@link #describe(StatementRequest, ArtifactAudience)} records; must not be {@code null}
     * @return the heading and at most {@value #MAX_RESPONSE_TRANSACTIONS} lines, in transaction order
     * @throws ClientInputException if the request does not name exactly one of a card and an account,
     *     or if the named account holds more than one card
     * @throws NoSuchElementException if no card with the requested number exists, or the requested
     *     account holds no card
     * @throws IllegalStateException if the cross-reference names a customer or an account that does
     *     not resolve, which the reference treats as an abend rather than as an omission
     */
    public StatementDocument compose(StatementRequest request, ArtifactAudience audience) {
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

        StatementResponse response = headingResponse(
                heading, Money.of(totals.getTotal()), totals.getLineCount(), audience);
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
     * Assembles the heading response one request-edge operation returns, reporting each artifact only
     * where the store actually holds it.
     *
     * <p>⚠️ Refactoring Rationale: this method used to assert three values it had not established. It
     * composed two per-card {@code s3://} locations from a keyed token, and no writer anywhere produces
     * a per-card object -- {@code S3StatementSink} publishes exactly {@value #PLAIN_TEXT_OBJECT} and
     * {@value #HTML_OBJECT} for a whole run -- so both locations named nothing whether or not a run had
     * happened. It then stamped {@code generatedAt} with 26 blanks, defending that as "not inventing a
     * clock reading", which was true and still published a fixed value in a field whose contract calls
     * it the instant the statement was produced. A caller had no way to tell a statement that exists
     * from one that does not.
     *
     * <p>Assumptions: existence is ESTABLISHED and not assumed, by one {@code HeadObject} per artifact.
     * Where the store holds the artifact the response carries a path this deployment serves and the
     * artifact's own last-written instant; where it does not, all three values are {@code null}, which
     * the contract publishes as nullable for exactly this state. Trade-offs: two store calls per
     * heading read, against a response whose locations resolve. Alternatives Considered: recording
     * artifact metadata in a relation at write time, which this context cannot do -- it holds no
     * writable relation and its database role is {@code SELECT}-only, which is the property
     * {@code CrossSchemaPrivilegeContractTest} asserts.
     *
     * <p>Assumptions: the two artifacts are described INDEPENDENTLY even though one run writes both,
     * and they are described within the run the manifest names. A lifecycle rule that expires one of a
     * superseded run's objects leaves the store holding the other, and reporting the one that exists is
     * more useful than reporting neither and more honest than reporting both. Note what this no longer
     * covers: a run INTERRUPTED between its two writes used to be observable here, and it no longer is,
     * because an incomplete run never reaches the manifest at all.
     *
     * <p>⚠️ Assumptions: every run-wide field is withheld from a CARDHOLDER audience. The artifacts
     * hold the whole portfolio's statements and the index positions are coordinates into them, so a
     * per-card answer carrying either is a per-card answer carrying a handle to every other cardholder
     * -- which is the defect this parameter was introduced for, recorded in full on
     * {@link ArtifactAudience}. The withholding is done HERE, at the one point both request-edge
     * operations assemble their response through, so a third operation added later inherits it rather
     * than having to remember it.
     *
     * <p>Assumptions: the manifest is resolved ONCE per response and both artifacts, the index and the
     * published locations are all derived from that one run identity. Resolving it per field would let
     * a manifest switching mid-response pair one run's artifact with another run's position, which is
     * the incoherence the manifest exists to remove.
     *
     * <p>Assumptions: the produced-at stamp is taken from whichever artifact is present, preferring the
     * plain-text one because that is the artifact the reference job writes first at L87 of
     * {@code app/jcl/CREASTMT.JCL}. The two instants differ by the time between two writes of one run,
     * so either is a truthful production time and the order only decides which of two adjacent
     * instants is published.
     *
     * <p>Measured: restoring the unconditional publication -- both locations always, and 26 blanks
     * when no artifact is described -- fails two cases of {@code StatementServiceTest}.
     * {@code noStoredArtifactYieldsNoLocation} reports a location where {@code null} was expected,
     * {@code but was: "/api/v1/reports/statements/artifacts/Rk3IELBwhDXagAz_-uTGC7"}, and
     * {@code oneStoredArtifactIsReportedAlone} reports the same for the artifact the store does not
     * hold. Removing the magnitude guard instead fails exactly one case,
     * {@code aTotalNeedingATenthIntegerDigitIsRefused}, with {@code Expecting code to raise a
     * throwable} -- so the two changes are independently asserted.
     *
     * @param heading the resolved heading row; must not be {@code null}
     * @param total the exact sum of the card's transaction amounts; must not be {@code null}
     * @param lineCount how many transactions the card has, which is the true count and not the number
     *     of rows any body carries
     * @param audience how much of the run this answer may disclose; must not be {@code null}
     * @return the heading response, with each artifact location, the produced-at stamp and the index
     *     position present only for an operator audience and only where the store holds the artifact;
     *     never {@code null}
     * @throws ArithmeticException if the total needs more than
     *     {@value #STATEMENT_TOTAL_INTEGER_DIGITS} integer positions
     * @throws IllegalStateException if the manifest names something that is not a run identifier this
     *     service could have published
     */
    private StatementResponse headingResponse(
            StatementHeading heading, Money total, long lineCount, ArtifactAudience audience) {
        requireStatementTotalMagnitude(total);
        Objects.requireNonNull(audience, "audience must not be null");
        // WHY : Assumptions: a cardholder audience consults the STORE NOT AT ALL, rather than reading
        //       the run and then discarding what it read. Every field those reads would fill is
        //       withheld from this audience, so the manifest read, the two metadata reads and the
        //       index search would be four requests to the object store per statement whose only
        //       effect is latency.
        Optional<String> runId = audience == ArtifactAudience.OPERATOR
                ? publishedRunId()
                : Optional.empty();
        Optional<ArtifactStore.ArtifactDescriptor> plainText =
                runId.flatMap(run -> artifacts.describe(runPrefix(run) + PLAIN_TEXT_OBJECT));
        Optional<ArtifactStore.ArtifactDescriptor> markup =
                runId.flatMap(run -> artifacts.describe(runPrefix(run) + HTML_OBJECT));
        // WHY : Assumptions: the index is searched only when the artifact it indexes EXISTS. A position
        //       into an artifact the store does not hold locates nothing, so the search would spend
        //       several ranged reads to produce a pair the response must report as absent anyway.
        Optional<StatementIndexEntry> position = plainText.isPresent()
                ? locateInArtifact(runId.orElseThrow(), heading.cardFingerprint())
                : Optional.empty();
        return new StatementResponse(
                heading.cardNum(),
                String.valueOf(heading.accountId()),
                assembleName(heading),
                total,
                Math.toIntExact(lineCount),
                plainText.isPresent() ? artifactLocation(runId.orElseThrow(), PLAIN_TEXT_OBJECT) : null,
                markup.isPresent() ? artifactLocation(runId.orElseThrow(), HTML_OBJECT) : null,
                plainText.or(() -> markup)
                        .map(ArtifactStore.ArtifactDescriptor::lastModified)
                        .orElse(null),
                position.map(StatementIndexEntry::firstRecord).orElse(null),
                position.map(StatementIndexEntry::recordCount).orElse(null));
    }

    /**
     * Reads the manifest and reports which run every read of this response must resolve against.
     *
     * <p>Purpose: this is the one place the current run is decided. A statement run publishes its two
     * artifacts and its index under an immutable per-run key prefix and then writes this manifest, so
     * the value read here names a run whose objects are all present -- and a run that failed part-way
     * never appears here at all.
     *
     * <p>Assumptions: an ABSENT manifest is an ordinary state and yields nothing rather than failing.
     * A deployment whose first statement run has not happened has no manifest, which is the same state
     * as the one in which no artifact exists, and a statement response answers it by reporting no
     * artifact. A manifest that is PRESENT but does not name a well-formed run identifier is a failure,
     * because the alternative is to compose an object key out of whatever it holds.
     *
     * @return the run identifier the manifest names, or empty when no run has been published
     * @throws IllegalStateException if the manifest holds something other than one run identifier
     */
    private Optional<String> publishedRunId() {
        byte[] recorded;
        try {
            recorded = artifacts.readRange(manifestKey(this.statementPrefix), 0, MANIFEST_MAX_BYTES - 1);
        } catch (NoSuchElementException noRunPublished) {
            return Optional.empty();
        }
        String named = new String(recorded, StandardCharsets.US_ASCII).trim();
        if (!RUN_ID_PATTERN.matcher(named).matches()) {
            throw new IllegalStateException("the statement manifest does not name a run this service "
                    + "published, so no artifact of it can be addressed");
        }
        return Optional.of(named);
    }

    /**
     * Composes the key prefix one run's objects sit under.
     *
     * @param runId the run identifier, already validated by whichever of
     *     {@link #publishedRunId()} or {@link #runKeyPrefix(String, String)} obtained it
     * @return the prefix each object name of that run is appended to; never {@code null}
     */
    private String runPrefix(String runId) {
        return this.statementPrefix + RUN_SEGMENT + runId + "/";
    }

    /**
     * Mints the identifier of one statement run.
     *
     * <p>Assumptions: the identifier is random and is minted by the writer at the start of a run, so a
     * rerun of a business date never reuses the prefix of the run it replaces. The reasoning for the
     * width and the alternative of a date-derived identifier are recorded on {@link #RUN_ID_LENGTH}.
     *
     * @return {@value #RUN_ID_LENGTH} lower-case hexadecimal characters; never {@code null}
     */
    public static String mintRunId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Composes the key prefix a writer publishes one run's objects under, validating the identifier.
     *
     * <p>Assumptions: this is the writer's counterpart to {@link #runPrefix(String)} and is static
     * because the writing task holds the prefix as configuration and has no instance of this class's
     * read side. The identifier is validated HERE as well as on the read path, so a caller cannot
     * publish under a prefix that no reader would ever be allowed to resolve.
     *
     * @param statementPrefix the configured statement key prefix, ending in a separator; must not be
     *     {@code null}
     * @param runId the run identifier from {@link #mintRunId()}; must be
     *     {@value #RUN_ID_LENGTH} lower-case hexadecimal characters
     * @return the prefix each object name of that run is appended to; never {@code null}
     * @throws IllegalArgumentException if the identifier is not of the published shape
     */
    public static String runKeyPrefix(String statementPrefix, String runId) {
        Objects.requireNonNull(statementPrefix, "statementPrefix must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        if (!RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new IllegalArgumentException("a statement run identifier is " + RUN_ID_LENGTH
                    + " lower-case hexadecimal characters");
        }
        return statementPrefix + RUN_SEGMENT + runId + "/";
    }

    /**
     * Names the manifest object within one statement key prefix.
     *
     * @param statementPrefix the configured statement key prefix, ending in a separator; must not be
     *     {@code null}
     * @return the manifest's key; never {@code null}
     */
    public static String manifestKey(String statementPrefix) {
        Objects.requireNonNull(statementPrefix, "statementPrefix must not be null");
        return statementPrefix + MANIFEST_OBJECT;
    }

    /**
     * Encodes the manifest body naming one run as current.
     *
     * <p>Assumptions: the body is the identifier and a newline, in US-ASCII, and nothing else. A
     * structured body -- a timestamp, a record count, the object names -- was considered and rejected:
     * every one of those is already recoverable from the run's own objects, and each field added is a
     * field a reader has to parse before it may compose a key. Trade-offs: the manifest carries no
     * record of WHEN it was switched; the store's own last-modified metadata carries that, and the
     * response publishes the artifact's instant rather than the manifest's in any case.
     *
     * @param runId the run identifier to publish as current; must be of the published shape
     * @return the bytes to write to {@link #manifestKey(String)}; never {@code null}
     * @throws IllegalArgumentException if the identifier is not of the published shape
     */
    public static byte[] encodeManifest(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        if (!RUN_ID_PATTERN.matcher(runId).matches()) {
            throw new IllegalArgumentException("a statement run identifier is " + RUN_ID_LENGTH
                    + " lower-case hexadecimal characters");
        }
        return (runId + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Refuses a statement total the reference's own accumulator could not hold.
     *
     * <p>⚠️ Refactoring Rationale: the total published by the two request-edge operations is a database
     * aggregate over one card's transactions, and an aggregate is bounded by the data rather than by
     * any declared picture. Every other path to a statement total meets
     * {@code CobolEditMask.formatStatementAmount}, which refuses a tenth integer digit; this path does
     * not emit an artifact and so met nothing. The check is placed on the one method both operations
     * assemble their response through, rather than on each operation, so a third operation added later
     * inherits it.
     *
     * <p>Assumptions: the refusal is an {@code ArithmeticException} carrying the same meaning the
     * encoder's is -- a figure outside the range the reference regime can represent -- rather than a
     * client error, because no request parameter chose the figure. Trade-offs: the message names the
     * FIELD and the bound and never the figure, because the figure is a sum of a cardholder's
     * transaction amounts and the logging contract in {@code docs/architecture/observability.md} names
     * a monetary amount as a value that is omitted rather than abbreviated.
     *
     * @param total the accumulated statement total; must not be {@code null}
     * @throws ArithmeticException if the total needs more than
     *     {@value #STATEMENT_TOTAL_INTEGER_DIGITS} integer positions
     */
    private static void requireStatementTotalMagnitude(Money total) {
        try {
            Money.ofPicture(total.amount(), STATEMENT_TOTAL_INTEGER_DIGITS);
        } catch (ArithmeticException overflow) {
            throw new ArithmeticException("the statement total exceeds the "
                    + STATEMENT_TOTAL_INTEGER_DIGITS
                    + " integer positions the statement regime declares");
        }
    }

    /**
     * Builds the published location of one artifact of one run from its opaque selector.
     *
     * @param runId the run the artifact belongs to, already validated; must not be {@code null}
     * @param objectName the artifact's object name within that run's prefix; must not be {@code null}
     * @return the path a caller collects the artifact from, never {@code null}
     */
    private String artifactLocation(String runId, String objectName) {
        return ARTIFACT_LOCATION_PREFIX + artifactSelector(runId, objectName);
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
     * <p>⚠️ Refactoring Rationale: the run now returns an INDEX beside its count, where it previously
     * returned the count alone. The two artifacts are run-wide -- one plain-text object and one markup
     * object for every card of the night -- and the statement response points a caller at them, so
     * without an index a caller asking for one card's statement was pointed at a document covering the
     * whole portfolio with no way to find its own statement inside it. The index is built here because
     * this is the only place a card boundary is observable: {@code emitStatement} is one call per card,
     * and the records it writes are counted around it.
     *
     * <p>Assumptions: the index covers the PLAIN-TEXT artifact only. That artifact is a sequence of
     * fixed-width records, so a record ordinal locates a statement inside it exactly and survives being
     * quoted to another tool. The markup artifact is one document whose per-card boundary is not
     * addressable by any consumer of it -- a reader opens it and scrolls -- so an ordinal into it would
     * be a number with no use. Alternatives Considered: indexing both, which doubles the counting and
     * publishes a figure nothing can act on.
     *
     * @param sink the destination for both record streams, cleared once before the first statement
     * @return the number of statements produced and one index entry per statement, in the order the
     *     cross-reference walk produced them, which is ascending card fingerprint
     * @throws NullPointerException if {@code sink} is {@code null}
     * @throws IllegalStateException if a cross-reference row names a customer or an account that does
     *     not resolve, or if a credit score cannot be carried by the statement band, either of which
     *     stops the run as the reference's abend does
     */
    public StatementRunOutcome generateStatements(StatementSink sink) {
        Objects.requireNonNull(sink, "sink must not be null");

        // WHY : Assumptions: the previous run's artifacts are discarded BEFORE the first record and
        //       unconditionally, which is the deletion step app/jcl/CREASTMT.JCL runs at L66 ahead of
        //       the generator at L79. Doing it here rather than per statement is what makes a run that
        //       produces no statement still leave no stale artifact readable.
        sink.replaceArtifacts();

        // WHY : Assumptions: the counting sink WRAPS the caller's sink rather than the caller being
        //       asked to count. The seam stays at three methods and no writer has to know that an index
        //       exists, which matters because the same seam is implemented by the object-store writer
        //       and by every test double; a fourth method would have made every implementation carry a
        //       concern only this method has.
        CountingSink counted = new CountingSink(sink);
        List<StatementIndexEntry> index = new ArrayList<>();
        int statementsProduced = 0;

        // WHY : Refactoring Rationale: the anchor is the WHOLE ordering tuple and both components
        //       advance from the same row. Only the fingerprint advanced before, which could not have
        //       named a position in a sequence ordered by the masked rendering first -- and the
        //       predicate it fed compared that one component too, so cards were skipped and repeated
        //       rather than resumed. The repository records which cards each outcome reached.
        // WHY : Assumptions: the two locals are assigned from the SAME row and never from different
        //       ones. Advancing them independently would build an anchor naming a position no row
        //       occupies, which is a shape the engine accepts and answers with a chunk that begins in
        //       the wrong place.
        String afterCardNum = WALK_FROM_START;
        String afterFingerprint = WALK_FROM_START;
        for (;;) {
            List<StatementHeadingRow> chunk =
                    cardXrefs.findHeadingChunk(afterCardNum, afterFingerprint, HEADING_CHUNK_SIZE);
            if (chunk.isEmpty()) {
                return new StatementRunOutcome(statementsProduced, List.copyOf(index));
            }
            for (StatementHeadingRow row : chunk) {
                long firstRecord = counted.statementRecords();
                emitStatement(StatementHeading.of(row), counted);
                index.add(new StatementIndexEntry(row.getCardFingerprint(), firstRecord,
                        counted.statementRecords() - firstRecord));
                statementsProduced++;
                afterCardNum = row.getCardNum();
                afterFingerprint = row.getCardFingerprint();
            }
        }
    }

    /**
     * A sink that counts the plain-text records passing through it and otherwise changes nothing.
     *
     * <p>Assumptions: only the plain-text stream is counted, because only that stream is indexed, and
     * the markup stream is forwarded untouched. Trade-offs: the count is a {@code long} although a run
     * that overflowed an {@code int} would be a hundred million statement lines; a record ordinal is
     * published in a response and widening it here costs nothing, whereas a silent wrap would put one
     * card's ordinal inside another card's statement.</p>
     */
    private static final class CountingSink implements StatementSink {

        /** The sink every call is forwarded to. */
        private final StatementSink delegate;

        /** How many plain-text records have passed through. */
        private long statementRecords;

        /**
         * Wraps one sink.
         *
         * @param delegate the sink to forward to; must not be {@code null}
         */
        private CountingSink(StatementSink delegate) {
            this.delegate = delegate;
        }

        /**
         * Reports how many plain-text records have been written so far.
         *
         * @return the count, which is the ordinal the next record will occupy
         */
        private long statementRecords() {
            return statementRecords;
        }

        /**
         * Forwards the artifact reset.
         */
        @Override
        public void replaceArtifacts() {
            delegate.replaceArtifacts();
        }

        /**
         * Counts one plain-text record and forwards it.
         *
         * @param record the record to write
         */
        @Override
        public void writeStatementRecord(byte[] record) {
            statementRecords++;
            delegate.writeStatementRecord(record);
        }

        /**
         * Forwards one markup record without counting it.
         *
         * @param record the record to write
         */
        @Override
        public void writeMarkupRecord(byte[] record) {
            delegate.writeMarkupRecord(record);
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
     * Mints the opaque selector that addresses one run-wide statement artifact.
     *
     * <p>⚠️ Refactoring Rationale: this replaces a method that composed a per-card {@code s3://} location
     * from a keyed token, and it was wrong in three independent ways that a review found together. The key
     * it composed NAMED NO STORED OBJECT: {@code S3StatementSink} publishes exactly two run-wide objects,
     * {@value com.carddemo.reporting.sink.S3StatementSink#PLAIN_TEXT_OBJECT} and
     * {@value com.carddemo.reporting.sink.S3StatementSink#HTML_OBJECT}, and nothing anywhere writes a
     * per-card key -- so every location the statement response published resolved to nothing. The scheme
     * it used was unusable by the one audience for the field, because the dataset bucket denies access
     * outside the VPC endpoint, so a browser could not open an {@code s3://} location even if the object
     * had existed. And it named the BUCKET in a response body, which tells an external caller where the
     * data sits for no benefit it can act on.
     *
     * <p>Assumptions: the selector is opaque and keyed, which keeps the property the retired method was
     * built for. Nothing in it spells out an account identifier, a card number or an object key, so it can
     * be published in a response, logged and quoted in a support conversation. The tokeniser is keyed
     * rather than random for the same reason as before: a stable selector means the same artifact answers
     * to the same address across runs, which a random identifier would have cost. That reasoning is
     * recorded in full on {@code ArtifactIdentityConfig}.
     *
     * <p>Assumptions: the selector is computed over the ARTIFACT NAME and no longer over a card, because
     * the artifacts are run-wide. One consequence is worth stating rather than leaving to be discovered:
     * two cards of one run now report the same two selectors, which is correct -- they are two views of one
     * pair of artifacts -- and a caller wanting one card's own content uses the two per-card operations
     * this contract publishes instead.
     *
     * <p>⚠️ Assumptions: the RUN is part of the tokenised value, so a selector names one artifact of one
     * run and not an artifact name in the abstract. That is what makes a selector stop working when the
     * manifest moves on: a caller holding yesterday's selector is answered as though the artifact were
     * absent, rather than being handed today's bytes under yesterday's understanding of what they
     * contain. This is the read-side half of the coherence fix recorded on {@link #MANIFEST_OBJECT};
     * without it, immutable keys alone would still let a stale selector resolve against a newer run.
     *
     * <p>Trade-offs: a selector is therefore no longer stable across runs, which the retired form was.
     * A caller that stored one and comes back after the next run is answered 404 and asks for the
     * statement again, which is one extra request; the alternative -- a stable selector that follows the
     * current run -- is the mutable read this fix removes.
     *
     * @param runId the run the artifact belongs to, of the shape {@link #RUN_ID_LENGTH} declares; must
     *     not be {@code null}
     * @param objectName the artifact's object name within that run's prefix; must not be {@code null}
     *     or blank
     * @return the opaque selector for that artifact of that run, never {@code null}
     */
    public String artifactSelector(String runId, String objectName) {
        Objects.requireNonNull(runId, "runId must not be null");
        return this.artifactIdentity.token(ARTIFACT_TOKEN_PURPOSE, runId + "/" + objectName);
    }

    /**
     * Resolves one artifact selector to the object key it names, refusing anything else.
     *
     * <p>Assumptions: resolution is a lookup in a two-entry table and never a composition. The selector is
     * compared against the selectors of the two artifacts this service knows about, and the key is then
     * taken from the matching entry -- so no part of a caller-supplied value reaches an object key, and a
     * selector for an artifact this deployment does not publish resolves to nothing rather than to a
     * probe of the bucket.</p>
     *
     * <p>⚠️ Assumptions: the table is built for the run the MANIFEST names, so the only two selectors
     * that resolve are the two of the current run. A selector minted for a superseded run therefore
     * resolves to nothing even though its objects are still stored, which is the point: it was published
     * alongside an index and a set of positions that described that run, and answering it with the
     * current run's bytes is precisely the mismatch the manifest exists to prevent.</p>
     *
     * <p>Measured: replacing the lookup with a composition -- returning the prefix concatenated with
     * the selector, which is the shape an object-key parameter would have had -- fails three cases of
     * {@code StatementServiceTest}. {@code anUnmintedSelectorIsRefused} reports
     * {@code Expecting an empty Optional but was containing value: "statements/ffffffffffffffffffffff"},
     * which is a caller addressing a key of its own choosing;
     * {@code aPublishedLocationResolvesToTheWrittenObject} and {@code collectionOpensTheResolvedKey}
     * report {@code expected: "statements/statements.html" but was: "statements/Zk1yZpeITFLYiKrzFIk5nG"}.
     *
     * @param selector the opaque selector from a statement response; may be {@code null}
     * @return the object key, or empty when the selector names neither artifact of the published run,
     *     and empty when no run has been published at all
     * @throws IllegalStateException if the manifest holds something other than one run identifier
     */
    public Optional<String> resolveArtifactKey(String selector) {
        if (selector == null) {
            return Optional.empty();
        }
        Optional<String> published = publishedRunId();
        if (published.isEmpty()) {
            return Optional.empty();
        }
        String runId = published.get();
        for (String objectName : ARTIFACT_OBJECT_NAMES) {
            if (artifactSelector(runId, objectName).equals(selector)) {
                return Optional.of(runPrefix(runId) + objectName);
            }
        }
        return Optional.empty();
    }

    /**
     * Finds where one card's statement sits inside the run-wide plain-text artifact.
     *
     * <p>Purpose: this is what makes a run-wide artifact usable from a per-card response. The index
     * artifact holds one fixed-width record per statement in ascending card-fingerprint order, so an
     * entry's position is its ordinal times {@link StatementIndexEntry#ENCODED_WIDTH} and a card can be
     * found by bisection over the object's own size.
     *
     * <p>Assumptions: the search reads ONE ENTRY PER PROBE and never the whole index. A portfolio of a
     * million cards is an eighty-eight-megabyte index and twenty probes of eighty-eight bytes, so the
     * cost of a statement read stays flat as the portfolio grows. Alternatives Considered: reading the
     * whole index and building a map, which is simpler and one request rather than several, and whose
     * cost is the entire index transferred on every statement read; and recording the positions in a
     * relation, which this context cannot do because its database role is {@code SELECT}-only.
     *
     * <p>Assumptions: an absent index yields nothing rather than a failure. A run reaches the manifest
     * only after its index has been written, so the run named there normally has one; what remains
     * possible is a lifecycle rule expiring one object of a superseded run, and reporting no position
     * for it is more useful than failing a statement read over it. A PRESENT index whose size is not a
     * whole number of entries is a failure, because it means the object was truncated and every
     * position derived from it would be wrong.
     *
     * @param runId the run whose index is searched, as the manifest named it; must not be {@code null}
     * @param cardFingerprint the fingerprint naming the card whose position is wanted; must not be
     *     {@code null}
     * @return the entry naming the card's first record and record count, or empty when no index is
     *     stored or the index does not name the card
     * @throws IllegalStateException if the stored index is not a whole number of entries
     */
    public Optional<StatementIndexEntry> locateInArtifact(String runId, String cardFingerprint) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(cardFingerprint, "cardFingerprint must not be null");
        // WHY : Assumptions: the index is read from the SAME run prefix the artifact was described
        //       under, which is what makes a position mean something. An index and an artifact from two
        //       runs agree on nothing: the ordinal of a card in one run's index is a byte offset into
        //       that run's artifact and names an unrelated cardholder in another's.
        String key = runPrefix(runId) + INDEX_OBJECT;
        Optional<ArtifactStore.ArtifactDescriptor> index = artifacts.describe(key);
        if (index.isEmpty()) {
            return Optional.empty();
        }
        long size = index.get().sizeBytes();
        if (size % StatementIndexEntry.ENCODED_WIDTH != 0) {
            throw new IllegalStateException("the stored statement index is " + size
                    + " bytes, which is not a whole number of entries; every position derived from it "
                    + "would name the wrong card");
        }
        long low = 0;
        long high = size / StatementIndexEntry.ENCODED_WIDTH - 1;
        while (low <= high) {
            long probe = low + (high - low) / 2;
            StatementIndexEntry entry = readIndexEntry(key, probe);
            int order = entry.cardFingerprint().compareTo(cardFingerprint);
            if (order == 0) {
                return Optional.of(entry);
            }
            if (order < 0) {
                low = probe + 1;
            } else {
                high = probe - 1;
            }
        }
        return Optional.empty();
    }

    /**
     * Reads one entry of the index artifact by its ordinal.
     *
     * <p>Assumptions: the range is derived from the declared entry width rather than from anything read
     * out of the artifact, so a probe cannot drift onto a record boundary that does not exist. The upper
     * bound is inclusive because that is what the store's range syntax means, and the conversion is done
     * here once rather than at each call site.</p>
     *
     * @param key the index object's key
     * @param ordinal which entry to read, counted from zero
     * @return the decoded entry, never {@code null}
     * @throws IllegalStateException if the store returns a short read, which means the index shrank
     *     between the size being read and the entry being fetched
     */
    private StatementIndexEntry readIndexEntry(String key, long ordinal) {
        long firstByte = ordinal * StatementIndexEntry.ENCODED_WIDTH;
        byte[] record = artifacts.readRange(key, firstByte,
                firstByte + StatementIndexEntry.ENCODED_WIDTH - 1);
        if (record.length != StatementIndexEntry.ENCODED_WIDTH) {
            throw new IllegalStateException("the statement index returned " + record.length
                    + " bytes for entry " + ordinal + "; it was replaced while being searched");
        }
        return StatementIndexEntry.decode(record);
    }

    /**
     * Resolves one selector and opens the artifact it names, for a caller that will stream it onward.
     *
     * <p>⚠️ Refactoring Rationale: this is the read path the statement surface never had. The response
     * published two artifact locations and NOTHING served them, so the only way to collect a statement
     * was direct access to the dataset bucket -- which the bucket policy refuses outside the VPC
     * endpoint, leaving the artifacts unreachable to every caller the response was written for. Serving
     * them through this service puts collection behind the same bearer token, the same authorization
     * rules and the same audit trail as the rest of the surface.
     *
     * <p>Assumptions: a selector is RESOLVED against the two artifacts this service publishes and is
     * never treated as a key. Nothing a caller sends is concatenated into an object key, so no selector,
     * however manipulated, can address another object in the bucket -- which matters because the same
     * bucket holds the report artifacts of every other run.
     *
     * <p>Assumptions: an unknown selector and an absent artifact are reported the SAME way, as
     * {@link NoSuchElementException}, which the request edge renders as 404. Distinguishing them would
     * tell an unauthenticated-in-effect caller which selectors are real, and neither state is actionable
     * differently by a caller that holds a selector from a response body. A selector of a SUPERSEDED
     * run now falls into the same answer, for the reason recorded on
     * {@link #artifactSelector(String, String)}.
     *
     * <p>⚠️ Assumptions: the artifacts this opens hold EVERY cardholder's statement in the portfolio,
     * so the route that reaches this method is admitted to the administrative group alone --
     * {@code SecurityConfig} enforces that, and a cardholder response no longer publishes a selector at
     * all. This method deliberately performs no authorization of its own: a second check here would
     * either duplicate the chain's rule or drift from it, and the chain refuses before the handler runs.
     *
     * @param selector the opaque selector taken from a statement response; may be {@code null}, which is
     *     reported as an absent artifact rather than as a parameter fault
     * @return the stored size and the open stream, which the caller must close
     * @throws NoSuchElementException if the selector names no artifact of this service, or names one the
     *     store does not hold
     */
    public ArtifactStore.OpenArtifact collectArtifact(String selector) {
        String key = resolveArtifactKey(selector).orElseThrow(() -> new NoSuchElementException(
                "the requested statement artifact is not available"));
        return artifacts.open(key);
    }

    /**
     * Names the two run-wide artifacts a statement run publishes.
     *
     * @return the two object names, in the order the response reports them; never {@code null}
     */
    public static List<String> artifactObjectNames() {
        return ARTIFACT_OBJECT_NAMES;
    }
}
