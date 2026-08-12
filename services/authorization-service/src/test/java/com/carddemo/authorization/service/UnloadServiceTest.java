package com.carddemo.authorization.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.authorization.domain.PendingAuthDetail;
import com.carddemo.authorization.domain.PendingAuthDetailKey;
import com.carddemo.authorization.domain.PendingAuthSummary;
import com.carddemo.authorization.mapper.PendingAuthDetailMapper;
import com.carddemo.authorization.mapper.PendingAuthSummaryMapper;
import com.carddemo.authorization.repository.PendingAuthDetailRepository;
import com.carddemo.authorization.repository.PendingAuthSummaryRepository;
import com.carddemo.common.codec.PackedDecimalCodec;
import com.carddemo.common.money.Money;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Asserts the rulings {@link UnloadService} carries across, for BOTH export shapes.
 *
 * <p><b>Purpose.</b> The sibling {@code AuthorizationExtractRoundTripTest} asserts that the two file
 * formats survive a circuit through {@link LoadService} and back, measured against the committed extract
 * fixtures. This class asserts the DECISIONS the exporter makes about those formats, which a circuit is
 * silent on: that the prefixed form is the default and the sequential form an explicit opt-in; that the
 * prefixed form is self-describing and therefore survives its records being REORDERED, while the
 * sequential form's parentage rests on nothing but write order and a run length and does NOT; that a
 * sequential extract is refused as an import source rather than failing later against a key constraint;
 * that the export's posture on a failed write is strict where the load's posture on a duplicate is
 * tolerant; that a root carrying no account identifier is passed over explicitly rather than in silence;
 * that the counts a run reports name what they actually counted; and that the export declares a read-only
 * unit of work and reaches no operation that writes.
 *
 * <h2>The two geometries, and where each one's authority lives</h2>
 *
 * <p>Assumptions: the PREFIXED form writes a hundred-byte root and a two-hundred-and-six-byte child, and
 * the ONLY authoritative source for that child is
 * {@code app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL} <strong>L43 to L48</strong>: its
 * <strong>L43</strong> and <strong>L44</strong> declare {@code FD OPFILE1} with
 * {@code 01 OPFIL1-REC PIC X(100)}, the segment verbatim with no prefix, and its <strong>L45</strong> to
 * <strong>L48</strong> declare {@code FD OPFILE2} with {@code 01 OPFIL2-REC} as a
 * {@code 05 ROOT-SEG-KEY PIC S9(11) COMP-3} occupying six bytes ahead of a
 * {@code 05 CHILD-SEG-REC PIC X(200)}. Its <strong>L230</strong> populates that prefix from the parent's
 * own key and its <strong>L271</strong> writes the record.
 *
 * <p>Assumptions: the SEQUENTIAL form writes the same hundred-byte root and a BARE two-hundred-byte
 * child, and its authority is the database descriptions rather than any program:
 * {@code ims/PASFLDBD.DBD} <strong>L27</strong> is
 * {@code DSG001 DATASET DD1=PASFILIP,DD2=PASFILOP,RECORD=(100),RECFM=F} and {@code ims/PADFLDBD.DBD}
 * <strong>L27</strong> is the same statement at {@code RECORD=(200)}. That is where the geometry lives
 * because the two inserts pass the RAW segments and never a record group --
 * {@code cbl/DBUNLDGS.CBL} <strong>L300 to L304</strong> passes {@code PENDING-AUTH-SUMMARY} through
 * {@code PASFLPCB} and its <strong>L319 to L323</strong> passes {@code PENDING-AUTH-DETAILS} through
 * {@code PADFLPCB}.
 *
 * <p>Assumptions: {@code cbl/DBUNLDGS.CBL} <strong>L51 to L56</strong> DOES redeclare a hundred-byte
 * record and a two-hundred-and-six-byte group, in working storage, and it is DEAD CODE that must never be
 * cited for the prefixed geometry. Three independent readings settle it, and they are recorded here so
 * that no later reader "discovers" the group and promotes it to a contract. First, both
 * {@code SELECT} statements are commented out at its <strong>L26 to L35</strong>, so the program has no
 * files to write. Second, the entire {@code FILE SECTION} is commented out at its <strong>L42 to
 * L48</strong>, so neither {@code FD} exists. Third, both {@code WRITE} statements are commented out --
 * {@code * WRITE OPFIL1-REC} at its <strong>L242</strong> and {@code * WRITE OPFIL2-REC} at its
 * <strong>L281</strong> -- each replaced by a {@code PERFORM ... -GSAM} at its <strong>L243</strong> and
 * <strong>L282</strong>. The live inserts named above pass the segments, which is the fourth reading and
 * the decisive one: were that group the write shape, the inserts would pass it.
 *
 * <p>Assumptions: there is no THIRD export shape and none may be inferred from the surplus of job
 * streams over programs. {@code jcl/DBPAUTP0.jcl} drives the vendor's own unload utility with no
 * application program at all, so it contributes no application record layout.
 *
 * <h2>Why the prefixed form is the default</h2>
 *
 * <p>Alternatives Considered: making the sequential form the default, on the ground that its child record
 * is the segment exactly and is therefore the simpler shape. Rejected because the two forms differ in a
 * property that is not a matter of taste. Every prefixed child carries its own parent key, so the file is
 * SELF-DESCRIBING and its records may be reordered without losing a single attribution -- which is what
 * lets {@link LoadService} read it back, and what the reordered-circuit cases below assert. A sequential
 * child carries no parent key at all, so the only things attributing it are the order it was written in
 * and each root's own {@code approved} plus {@code declined} counter sum standing as the length of the run
 * that follows -- which the reordering cases below assert BREAKS. Defaulting to the sequential form would
 * hand a caller with no view on the question a file pair that nothing in this migration can restore.
 *
 * <p>Trade-offs: the sequential form is nevertheless retained rather than removed, and the cost is
 * stated rather than hidden. What is bought is parity with the reference program that emits it, so the
 * migration answers for both unload programs instead of one. What is given up is a second on-disk format
 * that cannot round-trip and whose parentage depends on write order, so a consumer holding one has to be
 * told how to read it -- and the cases below are where that reading is written down and held to.
 *
 * <h2>The reference statuses, and the sets that must never be unified</h2>
 *
 * <p>Assumptions: the status shape this pair of programs evaluates is {@code PAUT-PCB-STATUS} and NOT
 * {@code DIBSTAT}. Only {@code cbl/PAUDBLOD.CBL}, {@code cbl/PAUDBUNL.CBL} and {@code cbl/DBUNLDGS.CBL}
 * {@code COPY IMSFUNCS}, call the batch language interface directly and test a program communication
 * block status; the four screen and message-driven programs use {@code EXEC DLI} and test the interface
 * block status instead. This class and {@code LoadServiceTest} assert the block-status shape and
 * {@code PurgeJobTest} asserts the interface-block shape, and the two vocabularies are never mixed.
 *
 * <p>Assumptions: THREE different termination-status sets exist across this context's programs and no two
 * of them may be collapsed into one. {@code cbl/CBPAUP0C.cbl} <strong>L228 to L241</strong> admits
 * {@code '  '} and {@code 'GB'} at the root and sends everything else -- {@code 'GE'} included -- to the
 * abend arm; its <strong>L259 to L271</strong> admits {@code '  '}, {@code 'GE'} and {@code 'GB'} at the
 * child; and {@code cbl/PAUDBUNL.CBL} <strong>L266 to L283</strong> admits {@code SPACES} and
 * {@code 'GE'} at the child, so {@code 'GB'} reaches the abend arm THERE while it is tolerated in the set
 * beside it. At the root, {@code PAUDBUNL}'s own <strong>L239 to L241</strong> takes {@code 'GB'} to
 * {@code END-OF-AUTHDB} and its <strong>L241</strong> raises the end-of-root flag.
 *
 * <p>Assumptions: the function codes come from {@code cpy/IMSFUNCS.cpy} <strong>L17 to L26</strong>, and
 * this pair of programs uses exactly three of them -- {@code FUNC-GN} at its <strong>L20</strong> for the
 * root walk, {@code FUNC-GNP} at its <strong>L22</strong> for the child walk, and {@code FUNC-ISRT} at
 * its <strong>L25</strong> for the sequential inserts. The three get-hold forms it also declares,
 * {@code FUNC-GHU} at <strong>L19</strong>, {@code FUNC-GHN} at <strong>L21</strong> and
 * {@code FUNC-GHNP} at <strong>L23</strong>, are passed by NO program in this module. Nothing in this
 * class asserts anything about hold semantics, locking or what the reference platform would have done
 * with one, because none of that is observable from the material and none of it could be verified without
 * a data-language runtime. No pessimistic declaration appears in this module for the same reason: a
 * read-only export has no row to protect.
 *
 * <h2>The three reference observables this class holds the target to</h2>
 *
 * <p>Alternatives Considered: reproducing each of the three exactly as it stands, so the migrated export
 * behaved identically. Rejected in all three cases for the same reason, and the house has already written
 * that reason down rather than this class inventing it: {@code tests/fixtures/README.md}
 * <strong>L144 to L151</strong> records that the existing readers and loaders reject a malformed record
 * rather than padding it, truncating it or dropping it silently, and gives its ground at its
 * <strong>L150 to L151</strong> -- that a malformed monetary record must never be silently coerced into
 * one that looks well formed. An export is the same integrity question facing the other way.
 *
 * <ul>
 *   <li><b>The silent skip, whose blast radius is larger than it looks.</b>
 *       {@code cbl/PAUDBUNL.CBL} <strong>L232</strong> guards on the account identifier being numeric,
 *       and what that guard encloses is BOTH the root write at its <strong>L233</strong> AND the whole
 *       child walk at its <strong>L235 to L236</strong>, closing at an {@code END-IF} at its
 *       <strong>L237</strong> with no {@code ELSE} of any kind. One unusable key therefore drops a parent
 *       and every authorization beneath it, and reports nothing. The same seam is at
 *       {@code cbl/DBUNLDGS.CBL} <strong>L241</strong>. Here the row is passed over identically and the
 *       occurrence is COUNTED on the returned outcome and named on the log.</li>
 *   <li><b>The counter inflation, which is wrong twice over.</b> {@code cbl/PAUDBUNL.CBL}
 *       <strong>L268 and L269</strong> sit inside the CHILD paragraph and increment
 *       {@code WS-NO-SUMRY-READ} and {@code WS-AUTH-SMRY-PROC-CNT} -- the SUMMARY counters -- once per
 *       child record, while {@code WS-NO-DTL-READ}, declared at its <strong>L68</strong>, is never
 *       incremented anywhere: that declaration is its only occurrence in the program. So the reported
 *       summary figure is inflated by the child count and the detail figure stays at zero. The same
 *       defect is at {@code cbl/DBUNLDGS.CBL} <strong>L278 and L279</strong>. Here each count names what
 *       it counted.</li>
 *   <li><b>One program name shared by two distinct programs.</b> The literal {@code 'IMSUNLOD'} is the
 *       program name in BOTH, at {@code cbl/PAUDBUNL.CBL} <strong>L54</strong> and
 *       {@code cbl/DBUNLDGS.CBL} <strong>L58</strong>, each also carrying it as a commented access
 *       specification name at their <strong>L93</strong> and <strong>L97</strong>, and
 *       {@code cbl/PAUDBUNL.CBL} <strong>L311</strong> writes {@code 'IMSUNLOD ABENDING ...'} on the way
 *       to its abend. An abend from either program is therefore indistinguishable in the log from the
 *       other. Here the two paths are distinct at the call site and their outputs are distinguishable
 *       from each other.</li>
 * </ul>
 *
 * <p>Assumptions: none of the three authorises a change to the baseline. Everything under {@code app} is
 * reference material and the behavioural oracle, so each of them, together with the dead working-storage
 * group and the misattributed diagnostic label below, is registered in
 * {@code docs/architecture/cobol-to-service-traceability.md} and NOTHING is fixed or deleted there. The
 * precedent is the house's own: {@code tests/README.md} section 1.1 at <strong>L50 to L60</strong>
 * records an unfixable defect in two immutable baseline programs, states that the minimal-change
 * principle forbids editing it, and gives its reason as auditability.
 *
 * <p>Parity honesty: there is NO golden master for any path in this module, so not one case below
 * compares against a recorded run of a reference program. {@code tests/README.md} section 5.2 at
 * <strong>L267</strong> shows the parity harness compiling from {@code app/cbl/} alone and its section
 * 1.1 scopes even that to ten of the twelve batch programs there, so the extension trees are never built
 * and neither unload program is ever compiled. What every case here rests on instead is the copybook and
 * database-description geometry cited above, the committed extract fixtures, and the transcribed walk.
 *
 * <p>Assumptions: the layout and length invariants this class depends on are stated here rather than
 * delegated, because the fixtures directory README cannot say which invariant a particular case relies
 * upon. They are: {@code cpy/CIPAUSMY.cpy} <strong>L19 to L31</strong> sums to exactly one hundred bytes
 * across thirteen declarations ending in {@code FILLER PIC X(34)}; {@code cpy/CIPAUDTY.cpy}
 * <strong>L19 to L54</strong> sums to exactly two hundred across twenty-seven elementary fields ending
 * in {@code FILLER PIC X(17)}; and packed width follows one byte per two digits plus a sign nibble, so a
 * {@code PIC S9(10)V99 COMP-3} amount is SEVEN bytes and not six -- which is why
 * {@code cpy/CIPAUDTY.cpy} <strong>L34 and L35</strong> are what make the two hundred close, since six
 * apiece would give a hundred and ninety-eight.
 *
 * <p>Assumptions: the zoned sign-overpunch convention does not apply anywhere here. The numerics in
 * these two segments are packed decimal, a two-byte binary counter pair, and one plain unsigned display
 * field, {@code PA-CUST-ID PIC 9(09)} at {@code cpy/CIPAUSMY.cpy} <strong>L20</strong>. A case written
 * against overpunch behaviour would be asserting a regime these segments never use.
 *
 * <p>Alternatives Considered: stating each stride as a figure of its own wherever a file is divided.
 * Rejected because the exporter and {@link LoadService} take every stride from the layout descriptors the
 * two mappers own -- {@code PendingAuthSummaryMapper.unloadRecordLength()},
 * {@code PendingAuthDetailMapper.unloadRecordLength()} and
 * {@code PendingAuthDetailMapper.segmentLength()} -- so a case that restated one would become a second
 * declaration of the same geometry, and a stride that moved would then have this class agreeing with its
 * own copy while disagreeing with both services. The figures the reference material declares are pinned
 * against those descriptors ONCE, in the stride case below, which is where a drift between the two is
 * meant to fail. The record-level fixed-width decode is likewise reached THROUGH those mappers rather
 * than re-implemented here, for the same single-source reason.
 *
 * <p>Assumptions: the codec internals are INHERITED from the shared kernel and are not re-proved here.
 * This class consumes {@code com.carddemo.common.codec.PackedDecimalCodec} as a READER of bytes the
 * exporter has already written, and asserts at the service boundary -- what the export put in the file --
 * rather than whether the packed regime itself is correct, which the shared kernel's own tests own. The
 * prohibition on binary floating point is likewise inherited and is deliberately not re-declared. What
 * remains this class's own is behaviour an import graph cannot see: that an amount is a
 * {@link BigDecimal} at a scale of two, that its rounding contract is {@link RoundingMode#HALF_UP}, and
 * that it crosses a boundary in a decimal STRING form rather than as a binary fraction. That is AAP Rule
 * T3, which is a different rule namespace from user-specified Rule 1 (Explainability) and is written in
 * full here for that reason.
 *
 * <p>Assumptions: no retry is asserted and none exists. The transient condition
 * {@code 88 RETRY-CONDITION VALUE 'BA', 'FH', 'TE'.} is declared at {@code cbl/PAUDBUNL.CBL}
 * <strong>L105</strong> and {@code cbl/DBUNLDGS.CBL} <strong>L109</strong> and referenced in neither
 * procedure division, as in five other programs of the eight. Were an attempt sequence ever added it
 * would be a faithful realisation of DECLARED-BUT-UNIMPLEMENTED intent and never a port of working retry
 * behaviour, and a case asserting it would have to say so. Alternatives Considered for such a list: the
 * three statuses above and nothing wider, because the statuses beside them in the same declaration
 * describe the DATA rather than the platform and two of those are load-bearing control values in the
 * walks -- end-of-database ends the root walk and segment-not-found ends one parent's children -- so
 * retrying either would repeat a decided outcome. The framework-native attribute is {@code maxRetries},
 * total attempts are one plus its value, and the enabler is {@code @EnableResilientMethods}; no circuit
 * breaker and no external resilience library is involved.
 *
 * <p>A test class accepts no parameter, yields no value and raises nothing, so this block carries no
 * parameter, return or exception at-clause.
 */
@ExtendWith(MockitoExtension.class)
class UnloadServiceTest {

    /** The class-path directory the committed fixtures live in. */
    private static final String ROOT = "/fixtures/";

    /** The prefixed unload's summary file: two hundred-byte roots, written highest account first. */
    private static final String PREFIXED_SUMMARY = "unload-prefixed-summary-100.bin";

    /** The prefixed unload's child file: four prefixed records, written with the parents alternating. */
    private static final String PREFIXED_DETAIL = "unload-prefixed-detail-206.bin";

    /** The sequential unload's summary file: the same two roots, written lowest account first. */
    private static final String SEQUENTIAL_SUMMARY = "unload-gsam-summary-100.bin";

    /** The sequential unload's child file: four bare segments, written in contiguous per-parent runs. */
    private static final String SEQUENTIAL_DETAIL = "unload-gsam-detail-200.bin";

    /** A summary image whose two binary counters hold line-terminator byte pairs. */
    private static final String TERMINATOR_SUMMARY = "pautsum0-line-terminator-bytes.bin";

    /** A summary image carrying value-bearing bytes in its trailing filler, read but never written. */
    private static final String NONBLANK_FILLER_SUMMARY = "pautsum0-filler-nonblank.bin";

    /** A summary image carrying a negatively-signed packed zero, read but never written. */
    private static final String NEGATIVE_ZERO_SUMMARY = "pautsum0-negative-zero-decode-only.bin";

    /** A detail segment whose merchant name carries significant trailing blanks. */
    private static final String UNTRIMMED_DETAIL = "pautdtl1-merchant-name-notrim.bin";

    /** The number of roots both summary fixtures hold. */
    private static final int ROOT_COUNT = 2;

    /** The number of children both detail fixtures hold. */
    private static final int CHILD_COUNT = 4;

    /** The lower of the two accounts the fixtures carry. */
    private static final Long ACCOUNT_ONE = Long.valueOf(10_000_000_001L);

    /** The higher of the two accounts the fixtures carry. */
    private static final Long ACCOUNT_TWO = Long.valueOf(10_000_000_002L);

    /** A customer identifier used only to make a skipped row's report identifiable. */
    private static final Long ORPHAN_CUSTOMER = Long.valueOf(900_000_001L);

    /** How long a walk that must terminate is allowed to take before the case fails. */
    private static final int TERMINATION_TIMEOUT_SECONDS = 30;

    /**
     * How many page queries a walk issues when every root fits in one page.
     *
     * <p>Assumptions: two -- one that returns the page and one more that arrives empty and ends the walk.
     * The exporter's own page size is two hundred, so both fixture roots arrive together. This is stated
     * as its own figure rather than as the root count, which it coincidentally equals here, because the
     * two diverge the moment a third root is added and a case written against the wrong one would then
     * fail for a reason that has nothing to do with what it asserts.
     */
    private static final int PAGE_QUERIES_FOR_ONE_PAGE = 2;

    /**
     * The zero-based offset of the merchant name inside one detail segment.
     *
     * <p>Assumptions: this is derived by summing the declarations ahead of it in
     * {@code cpy/CIPAUDTY.cpy}, taking each packed field at one byte per two digits plus a sign nibble --
     * which puts the two seven-byte amounts of its <strong>L34 and L35</strong> at seventy-four and
     * eighty-one and the fifteen-byte merchant identifier of its <strong>L39</strong> at ninety-seven, so
     * the twenty-two-byte name of its <strong>L40</strong> begins here. It is stated as a figure rather
     * than looked up because AAP Rule T1 makes the copybook normative, and a case that read the offset
     * from the same descriptor the exporter writes through could not detect the two moving together.
     */
    private static final int MERCHANT_NAME_OFFSET = 112;

    /** The declared width of the merchant name, from {@code cpy/CIPAUDTY.cpy} L40. */
    private static final int MERCHANT_NAME_WIDTH = 22;

    /**
     * The zero-based offset of the match status inside one detail segment.
     *
     * <p>Assumptions: {@code cpy/CIPAUDTY.cpy} <strong>L45</strong> declares it one byte wide directly
     * after the fifteen-byte transaction identifier of its <strong>L44</strong>, and its
     * <strong>L46 to L49</strong> close its domain to four values. It is pinned beside the fraud position
     * below because those two consecutive single-character fields are exactly what an off-by-one offset
     * confuses, and confusing them is not detectable from either value alone.
     */
    private static final int MATCH_STATUS_OFFSET = 173;

    /** The zero-based offset of the fraud position, from {@code cpy/CIPAUDTY.cpy} L50. */
    private static final int AUTH_FRAUD_OFFSET = 174;

    /** The zero-based offset of the transaction identifier, from {@code cpy/CIPAUDTY.cpy} L44. */
    private static final int TRANSACTION_ID_OFFSET = 158;

    /** The declared width of the transaction identifier, from {@code cpy/CIPAUDTY.cpy} L44. */
    private static final int TRANSACTION_ID_WIDTH = 15;

    /** The zero-based offset of the trailing filler in one summary segment. */
    private static final int SUMMARY_FILLER_OFFSET = 66;

    /** The declared width of the summary's trailing filler, from {@code cpy/CIPAUSMY.cpy} L31. */
    private static final int SUMMARY_FILLER_WIDTH = 34;

    /** The zero-based offset of the credit balance in one summary segment. */
    private static final int CREDIT_BALANCE_OFFSET = 38;

    /** The zero-based offset of the credit limit in one summary segment. */
    private static final int CREDIT_LIMIT_OFFSET = 26;

    /** The integer digit count both summary money pictures declare, from {@code cpy/CIPAUSMY.cpy}. */
    private static final int SUMMARY_MONEY_INT_DIGITS = 9;

    /** The zero-based offset of the transaction amount in one detail segment. */
    private static final int TRANSACTION_AMOUNT_OFFSET = 74;

    /** The integer digit count the detail money pictures declare, from {@code cpy/CIPAUDTY.cpy} L34. */
    private static final int DETAIL_MONEY_INT_DIGITS = 10;

    /** The decimal digit count every money picture in both segments declares. */
    private static final int MONEY_DEC_DIGITS = 2;

    /** The digit count the packed parent key declares, from {@code cbl/PAUDBUNL.CBL} L47. */
    private static final int PARENT_KEY_DIGITS = 11;

    /**
     * The status values the child walk of {@code cbl/PAUDBUNL.CBL} treats as terminal.
     *
     * <p>Assumptions: its <strong>L266</strong> admits {@code SPACES} and its <strong>L273</strong>
     * admits {@code 'GE'}, so its <strong>L279</strong> sends everything else -- end-of-database
     * included -- to the abend arm at its <strong>L282</strong>.
     */
    private static final List<String> UNLOAD_CHILD_TERMINAL = List.of("  ", "GE");

    /**
     * The status values the root walk of {@code cbl/CBPAUP0C.cbl} treats as terminal.
     *
     * <p>Assumptions: its <strong>L228 to L241</strong> is an evaluation with arms for {@code '  '} and
     * {@code 'GB'} only, so segment-not-found reaches the abend arm THERE while the set beside it
     * tolerates that same value.
     */
    private static final List<String> PURGE_ROOT_TERMINAL = List.of("  ", "GB");

    /**
     * The status values the child walk of {@code cbl/CBPAUP0C.cbl} treats as terminal.
     *
     * <p>Assumptions: its <strong>L259 to L271</strong> admits {@code '  '}, {@code 'GE'} and
     * {@code 'GB'}, which is the widest of the three sets and the reason none of them may be unified.
     */
    private static final List<String> PURGE_CHILD_TERMINAL = List.of("  ", "GE", "GB");

    /** The status field name the three batch-interface programs evaluate. */
    private static final String BLOCK_STATUS_FIELD = "PAUT-PCB-STATUS";

    /** The status field name the four screen and message-driven programs evaluate instead. */
    private static final String INTERFACE_STATUS_FIELD = "DIBSTAT";

    /** The one program-name literal both reference unload programs carry. */
    private static final String SHARED_REFERENCE_PROGRAM_NAME = "IMSUNLOD";

    /** The misattributed label {@code cbl/DBUNLDGS.CBL} L331 puts on a CHILD insert failure. */
    private static final String MISATTRIBUTED_LABEL = "GSAM PARENT FAIL";

    /** The summary repository the exporter's outer walk reads. */
    @Mock
    private PendingAuthSummaryRepository summaries;

    /** The authorization repository the exporter's inner walk reads. */
    @Mock
    private PendingAuthDetailRepository details;

    /** The two summary rows decoded from the committed prefixed fixture, in fixture order. */
    private final List<PendingAuthSummary> storedRoots = new ArrayList<>();

    /** The four authorization rows decoded from the committed prefixed fixture, in fixture order. */
    private final List<PendingAuthDetail> storedChildren = new ArrayList<>();

    /** The stream the summary images of the case under test are written to. */
    private final ByteArrayOutputStream rootFile = new ByteArrayOutputStream();

    /** The stream the authorization records of the case under test are written to. */
    private final ByteArrayOutputStream childFile = new ByteArrayOutputStream();

    /**
     * How many pages the outer walk double has served in this case.
     *
     * <p>Assumptions: it exists so the double can offer an unattributable row once rather than on every
     * page, for the reason recorded on the double itself.
     */
    private int pagesServed;

    /** The exporter's own log events, captured so a reported skip can be asserted rather than assumed. */
    private ListAppender<ILoggingEvent> captured;

    /** The exporter's logger, held so the appender attached in setup can be detached again. */
    private ch.qos.logback.classic.Logger serviceLogger;

    /** The level the exporter's logger carried before this case lowered it. */
    private Level previousLevel;

    /**
     * Decodes the committed fixtures into the rows every case walks and starts capturing the log.
     *
     * <p>Assumptions: the rows are decoded ONCE PER CASE rather than shared across the class, because
     * several cases mutate the summary list to introduce a row with no account identifier. A shared list
     * would make those cases order-dependent on every other one.
     *
     * <p>Assumptions: the prefixed fixtures are the source of every row, and they are the oracle rather
     * than anything this class computes. Building the entities by decoding them means each case runs
     * against the same two accounts and four authorizations the sibling round-trip class and the
     * repository integration test use, so a stride or an offset that drifted fails in all three rather
     * than in one.
     *
     * <p>Assumptions: the level is lowered to warning rather than left alone, because the two reports
     * this class asserts are emitted at that level and a configuration that suppressed them would make
     * those cases pass while observing nothing.
     */
    @BeforeEach
    void decodeTheFixturesAndCaptureTheLog() {
        for (byte[] record : split(bytes(PREFIXED_SUMMARY),
                PendingAuthSummaryMapper.unloadRecordLength())) {
            this.storedRoots.add(PendingAuthSummaryMapper.fromExtractRecord(record));
        }
        for (byte[] record : split(bytes(PREFIXED_DETAIL),
                PendingAuthDetailMapper.unloadRecordLength())) {
            this.storedChildren.add(PendingAuthDetailMapper.fromUnloadRecord(record));
        }
        this.serviceLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(UnloadService.class);
        this.previousLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.WARN);
        this.captured = new ListAppender<>();
        this.captured.start();
        this.serviceLogger.addAppender(this.captured);
    }

    /**
     * Stops capturing and restores the level this case lowered.
     *
     * <p>Assumptions: the previous level is RESTORED rather than assumed to have been unset, so a level
     * left lowered cannot change what a later class in the same virtual machine observes. Detaching
     * without restoring would leak a configuration change out of this class.
     */
    @AfterEach
    void stopCapturingTheLog() {
        this.serviceLogger.detachAppender(this.captured);
        this.captured.stop();
        this.serviceLogger.setLevel(this.previousLevel);
    }

    /**
     * The prefixed form is what an export writes when the caller names no form.
     *
     * <p>Assumptions: the default is asserted from BOTH directions, because the two say different things.
     * The published constant states which form the class calls its default; the byte comparison states
     * that the overload taking no form actually uses it. A case asserting only the second would pass for a
     * class whose constant said one thing and whose overload did another.
     */
    @Nested
    @DisplayName("shape (a), the prefixed form, is the default")
    class PrefixedFormIsTheDefault {

        /**
         * The published default is the prefixed form and the no-form overload writes exactly it.
         *
         * <p>Assumptions: the prefixed form is the default because it is the one a load restores from,
         * which is the first of the three findings recorded on {@link UnloadService}. A caller with no
         * view on the question therefore cannot produce a terminal file pair by accident.
         */
        @Test
        @DisplayName("the default form is the prefixed form, and the no-form overload writes exactly it")
        void theDefaultFormIsThePrefixedForm() {
            givenTheWalksAnswerFromTheDecodedRows();

            assertThat(UnloadService.DEFAULT_FORM)
                    .as("the round-trip form is the default, because the sequential pair is read by"
                            + " no job in the module")
                    .isEqualTo(UnloadService.UnloadForm.PREFIXED);

            UnloadService.UnloadOutcome byDefault =
                    exporter().unload(UnloadServiceTest.this.rootFile,
                            UnloadServiceTest.this.childFile);
            ByteArrayOutputStream namedRoots = new ByteArrayOutputStream();
            ByteArrayOutputStream namedChildren = new ByteArrayOutputStream();
            UnloadService.UnloadOutcome byName = exporter()
                    .unload(UnloadService.UnloadForm.PREFIXED, namedRoots, namedChildren);

            assertThat(UnloadServiceTest.this.rootFile.toByteArray())
                    .isEqualTo(namedRoots.toByteArray());
            assertThat(UnloadServiceTest.this.childFile.toByteArray())
                    .isEqualTo(namedChildren.toByteArray());
            assertThat(byDefault).isEqualTo(byName);
            assertThat(byDefault.rootsWritten()).isEqualTo(ROOT_COUNT);
            assertThat(byDefault.childrenWritten()).isEqualTo(CHILD_COUNT);
            assertThat(byDefault.rootsSkipped()).isZero();
        }

        /**
         * The two forms emit the same hundred-byte root and child records of the two declared strides.
         *
         * <p>Assumptions: the strides are asserted SEPARATELY and against the lengths the layout
         * descriptors report, so the two shapes cannot silently converge. Asserting only that they differ
         * would hold for two wrong strides. The figures they must equal are then pinned once against the
         * geometry the reference material declares -- a hundred from {@code cbl/PAUDBUNL.CBL}
         * <strong>L44</strong> and {@code ims/PASFLDBD.DBD} <strong>L27</strong> agreeing, two hundred
         * from that program's <strong>L48</strong> and {@code ims/PADFLDBD.DBD} <strong>L27</strong>
         * agreeing, and two hundred and six from the six-byte packed key of its <strong>L47</strong>
         * ahead of that segment.
         */
        @Test
        @DisplayName("the two forms emit one 100-byte root shape and child records of 206 and 200 bytes")
        void theTwoFormsEmitTheDeclaredStrides() {
            givenTheWalksAnswerFromTheDecodedRows();

            exporter().unload(UnloadService.UnloadForm.PREFIXED, UnloadServiceTest.this.rootFile,
                    UnloadServiceTest.this.childFile);
            ByteArrayOutputStream sequentialRoots = new ByteArrayOutputStream();
            ByteArrayOutputStream sequentialChildren = new ByteArrayOutputStream();
            exporter().unload(UnloadService.UnloadForm.SEQUENTIAL, sequentialRoots,
                    sequentialChildren);

            assertThat(PendingAuthSummaryMapper.unloadRecordLength())
                    .as("the root record is the segment verbatim, so the two declarations agree at 100")
                    .isEqualTo(100);
            assertThat(PendingAuthDetailMapper.segmentLength())
                    .as("the bare child is the segment, which the database description declares at 200")
                    .isEqualTo(200);
            assertThat(PendingAuthDetailMapper.unloadRecordLength())
                    .as("the prefixed child is a six-byte packed parent key ahead of that segment")
                    .isEqualTo(206);
            assertThat(prefixWidth())
                    .as("six bytes is the packed width of an eleven-digit key, one per two digits"
                            + " plus a sign nibble")
                    .isEqualTo(PackedDecimalCodec.packedWidth(PARENT_KEY_DIGITS, 0));

            assertThat(UnloadServiceTest.this.rootFile.size())
                    .isEqualTo(ROOT_COUNT * PendingAuthSummaryMapper.unloadRecordLength());
            assertThat(UnloadServiceTest.this.childFile.size())
                    .isEqualTo(CHILD_COUNT * PendingAuthDetailMapper.unloadRecordLength());
            assertThat(sequentialRoots.toByteArray())
                    .as("both reference programs move the whole root with no prefix, so this file is"
                            + " identical between the forms")
                    .isEqualTo(UnloadServiceTest.this.rootFile.toByteArray());
            assertThat(sequentialChildren.size())
                    .isEqualTo(CHILD_COUNT * PendingAuthDetailMapper.segmentLength());
        }

        /**
         * The sequential form is reachable only by naming it, and its child file differs from the default.
         *
         * <p>Assumptions: the opt-in is asserted as an OBSERVABLE difference in the child file rather
         * than as the presence of an overload, because an overload that quietly produced the same bytes
         * would satisfy a signature check while making the choice meaningless.
         */
        @Test
        @DisplayName("the sequential form is an explicit opt-in and its child file is never the default")
        void theSequentialFormRequiresAnExplicitOptIn() {
            givenTheWalksAnswerFromTheDecodedRows();

            exporter().unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);
            ByteArrayOutputStream sequentialRoots = new ByteArrayOutputStream();
            ByteArrayOutputStream sequentialChildren = new ByteArrayOutputStream();
            exporter().unload(UnloadService.UnloadForm.SEQUENTIAL, sequentialRoots,
                    sequentialChildren);

            assertThat(UnloadService.UnloadForm.values())
                    .as("there are exactly two shapes, for the reason recorded against the utility job")
                    .containsExactly(UnloadService.UnloadForm.PREFIXED,
                            UnloadService.UnloadForm.SEQUENTIAL);
            assertThat(sequentialChildren.toByteArray())
                    .as("the opt-in is observable: the default never emits the bare child shape")
                    .isNotEqualTo(UnloadServiceTest.this.childFile.toByteArray());
        }

        /**
         * The six-byte parent prefix is written packed, and a text rendering of the same key differs.
         *
         * <p>Assumptions: the case states BOTH readings of the emitted bytes and asserts they disagree,
         * rather than asserting only that the packed reading works. A text prefix would not fail on
         * write; it would be the wrong LENGTH as well as the wrong bytes, and at the wrong length every
         * later record in the file is misaligned -- while a load reading text as packed does not fail
         * either, it decodes to a different account and attributes the authorization to it.
         */
        @Test
        @DisplayName("the emitted parent prefix decodes as packed decimal and is not the key as text")
        void theParentPrefixIsWrittenPackedAndNotAsText() {
            givenTheWalksAnswerFromTheDecodedRows();

            exporter().unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            int stride = PendingAuthDetailMapper.unloadRecordLength();
            byte[] firstRecord =
                    Arrays.copyOfRange(UnloadServiceTest.this.childFile.toByteArray(), 0, stride);
            byte[] emittedPrefix = Arrays.copyOfRange(firstRecord, 0, prefixWidth());

            assertThat(PendingAuthDetailMapper.unloadedAccountId(firstRecord))
                    .as("read as packed decimal the prefix names the account the walk was positioned on")
                    .isEqualTo(ACCOUNT_ONE);
            // WHY : Assumptions: the codec is used here as a READER of bytes the exporter already wrote,
            //       which is a service-boundary assertion. The packed regime itself belongs to the shared
            //       kernel's own tests and is not re-proved: what is asserted is that the export put a
            //       decodable key of the declared geometry at offset zero, not that packing is correct.
            assertThat(PackedDecimalCodec.decodePacked(emittedPrefix, 0, PARENT_KEY_DIGITS, 0, true))
                    .isEqualByComparingTo(BigDecimal.valueOf(ACCOUNT_ONE.longValue()));
            assertThat(emittedPrefix)
                    .as("the same key written as characters is a different byte sequence of the"
                            + " same width")
                    .isNotEqualTo(Arrays.copyOfRange(
                            ACCOUNT_ONE.toString().getBytes(StandardCharsets.US_ASCII), 0,
                            prefixWidth()));
        }

        /**
         * Both prefixed fixtures violate ordering deliberately, and the export still groups correctly.
         *
         * <p>Assumptions: the summary fixture writes the HIGHER account first and the detail fixture
         * writes the two parents ALTERNATING, and both are deliberate. They exist so that grouping is
         * asserted rather than assumed: a walk that echoed its input would emit the roots highest first
         * and the children alternating, and a consumer that carried the previous record's account forward
         * would produce a plausible result on a grouped file and the wrong one here.
         */
        @Test
        @DisplayName("the export groups every child under its own parent from reverse-ordered and"
                + " interleaved fixtures")
        void theExportGroupsChildrenDespiteTheFixtureOrder() {
            assertThat(this.accountsInFixtureOrder(bytes(PREFIXED_SUMMARY)))
                    .as("the committed summary fixture is deliberately written highest account first")
                    .containsExactly(ACCOUNT_TWO, ACCOUNT_ONE);
            assertThat(this.parentsInFixtureOrder(bytes(PREFIXED_DETAIL)))
                    .as("the committed detail fixture deliberately alternates its two parents")
                    .containsExactly(ACCOUNT_ONE, ACCOUNT_TWO, ACCOUNT_ONE, ACCOUNT_TWO);

            givenTheWalksAnswerFromTheDecodedRows();
            exporter().unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            assertThat(this.accountsInFixtureOrder(UnloadServiceTest.this.rootFile.toByteArray()))
                    .as("the walk emits roots in ascending key order whatever order they arrived in")
                    .containsExactly(ACCOUNT_ONE, ACCOUNT_TWO);
            assertThat(this.parentsInFixtureOrder(UnloadServiceTest.this.childFile.toByteArray()))
                    .as("each parent's children are contiguous, and the prefix says so record by record")
                    .containsExactly(ACCOUNT_ONE, ACCOUNT_ONE, ACCOUNT_TWO, ACCOUNT_TWO);
        }

        /**
         * Reads the account of every root in a summary file, in file order.
         *
         * @param summaryFile a whole number of hundred-byte root records; must not be {@code null}
         * @return the account identifier of each root in file order; never {@code null}
         */
        private List<Long> accountsInFixtureOrder(byte[] summaryFile) {
            List<Long> accounts = new ArrayList<>();
            for (byte[] record : split(summaryFile, PendingAuthSummaryMapper.unloadRecordLength())) {
                accounts.add(PendingAuthSummaryMapper.fromExtractRecord(record).getAccountId());
            }
            return accounts;
        }

        /**
         * Reads the parent account of every prefixed child in a detail file, in file order.
         *
         * @param detailFile a whole number of two-hundred-and-six-byte prefixed records; must not be
         *     {@code null}
         * @return the parent account each record's own prefix names, in file order; never {@code null}
         */
        private List<Long> parentsInFixtureOrder(byte[] detailFile) {
            List<Long> parents = new ArrayList<>();
            for (byte[] record : split(detailFile, PendingAuthDetailMapper.unloadRecordLength())) {
                parents.add(PendingAuthDetailMapper.unloadedAccountId(record));
            }
            return parents;
        }
    }

    /**
     * The prefixed form closes a circuit through the load path, and reordering it changes nothing.
     *
     * <p>Assumptions: what these cases ask is not whether the FORMAT survives a circuit -- the sibling
     * round-trip class asserts that against the committed fixtures -- but whether the circuit is
     * INDIFFERENT to the order of the records inside it. That is the property the prefix buys and the one
     * the sequential form cannot have, so it is asserted here beside its own negation in the block below.
     *
     * <p>Alternatives Considered: comparing the restored ENTITIES against the source entities. Rejected
     * because only {@code PendingAuthDetailKey} declares equality in this context, so an entity comparison
     * would silently reduce to a key comparison and would pass for a circuit that lost every non-key
     * column. Re-exporting the restored rows and comparing BYTES is what makes a lost or shifted field
     * fail, and it also compares the two halves of the circuit in the same vocabulary the file uses.
     */
    @Nested
    @DisplayName("shape (a) is self-describing, so its circuit is order-insensitive")
    class PrefixedFormSurvivesAReorderedCircuit {

        /**
         * An export re-imported through the load path and exported again reproduces the first export.
         *
         * <p>Assumptions: the load is driven through {@link LoadService} itself rather than through a
         * decode in this class, because the ruling under test is that THIS pair of services agrees. A
         * comparison against a decode written here would assert this class's reading of the format on both
         * sides of the circuit and could agree with itself while disagreeing with the loader.
         */
        @Test
        @DisplayName("export, load and export again reproduces the first export byte for byte")
        void thePrefixedFormRoundTripsThroughTheLoadPath() {
            Extract exported = exportOver(UnloadServiceTest.this.storedRoots,
                    UnloadServiceTest.this.storedChildren, UnloadService.UnloadForm.PREFIXED);

            Restored restored = restore(exported.rootFile(), exported.childFile());
            Extract reexported = exportOver(restored.roots(), restored.children(),
                    UnloadService.UnloadForm.PREFIXED);

            assertThat(restored.outcome().read()).isEqualTo(ROOT_COUNT + CHILD_COUNT);
            assertThat(restored.outcome().inserted()).isEqualTo(ROOT_COUNT + CHILD_COUNT);
            assertThat(restored.outcome().alreadyPresent()).isZero();
            assertThat(reexported.rootFile())
                    .as("every root column survives the circuit, so the root file is reproduced exactly")
                    .isEqualTo(exported.rootFile());
            assertThat(reexported.childFile())
                    .as("every child column and its parent prefix survive, so the child file is too")
                    .isEqualTo(exported.childFile());
        }

        /**
         * The same circuit reproduces the same two files when the exported records are shuffled first.
         *
         * <p>Assumptions: the shuffle reverses BOTH files rather than one, and reversal is the strongest
         * available shuffle for two files of two and four records: it moves every record, and for the
         * child file it also breaks the per-parent contiguity the export produced, leaving the two parents
         * alternating again. If the prefix were not what attributes a child, the restored rows would be
         * attributed differently and the re-export would not match.
         *
         * <p>Assumptions: the ROOT file is reversed as well, which matters because the loader's two passes
         * require every parent to be present before any child is read. Reversing the roots changes which
         * one is inserted first and nothing else, and that is exactly the claim: root order is not
         * load-bearing in this form.
         */
        @Test
        @DisplayName("shuffling the exported records leaves the restored rows and the re-export unchanged")
        void thePrefixedCircuitIsIndifferentToRecordOrder() {
            Extract exported = exportOver(UnloadServiceTest.this.storedRoots,
                    UnloadServiceTest.this.storedChildren, UnloadService.UnloadForm.PREFIXED);
            byte[] shuffledRoots = reverseRecords(exported.rootFile(),
                    PendingAuthSummaryMapper.unloadRecordLength());
            byte[] shuffledChildren = reverseRecords(exported.childFile(),
                    PendingAuthDetailMapper.unloadRecordLength());

            Restored restored = restore(shuffledRoots, shuffledChildren);
            Extract reexported = exportOver(restored.roots(), restored.children(),
                    UnloadService.UnloadForm.PREFIXED);

            assertThat(shuffledChildren)
                    .as("the shuffle really did change the file, so the comparison below is not vacuous")
                    .isNotEqualTo(exported.childFile());
            assertThat(restored.outcome().inserted()).isEqualTo(ROOT_COUNT + CHILD_COUNT);
            assertThat(reexported.rootFile()).isEqualTo(exported.rootFile());
            assertThat(reexported.childFile())
                    .as("parentage came from each record's own prefix, so reordering cost nothing")
                    .isEqualTo(exported.childFile());
        }

        /**
         * Every restored child is attributed to the parent its own prefix names, whatever the file order.
         *
         * <p>Assumptions: the attribution is asserted directly on the restored keys as well as through the
         * re-export above, because the two can fail differently. A circuit that attributed every child to
         * one parent would still re-export a file of the right length and the right record count, and the
         * comparison above would catch it only because the prefixes would differ -- whereas this states
         * the property the prefix exists for in the terms a reader thinks in.
         */
        @Test
        @DisplayName("each restored child carries the account its own prefix named, not its neighbour's")
        void everyRestoredChildKeepsItsOwnParent() {
            Extract exported = exportOver(UnloadServiceTest.this.storedRoots,
                    UnloadServiceTest.this.storedChildren, UnloadService.UnloadForm.PREFIXED);
            List<Long> writtenParents = new ArrayList<>();
            for (byte[] record : split(exported.childFile(),
                    PendingAuthDetailMapper.unloadRecordLength())) {
                writtenParents.add(PendingAuthDetailMapper.unloadedAccountId(record));
            }

            Restored restored = restore(reverseRecords(exported.rootFile(),
                    PendingAuthSummaryMapper.unloadRecordLength()),
                    reverseRecords(exported.childFile(),
                            PendingAuthDetailMapper.unloadRecordLength()));

            // WHY : Assumptions: the expectation is the written parents REVERSED, because the doubles accept
            //       rows in the order the shuffled file presents them. Comparing against the written order
            //       instead would make the case assert that the load re-sorted its input, which it does not
            //       do and must not: what is under test is that each record kept its own parent, not that
            //       the load restored an order the file no longer had.
            List<Long> restoredParents = restored.children().stream()
                    .map(child -> child.getId().getAccountId()).toList();
            assertThat(restoredParents)
                    .as("the restored rows arrive in the shuffled file's order, so the expectation is"
                            + " the written parents reversed rather than the written order")
                    .containsExactlyElementsOf(writtenParents.reversed());
            assertThat(restoredParents).containsOnly(ACCOUNT_ONE, ACCOUNT_TWO);
        }
    }

    /**
     * The sequential form is order-dependent, and it is an export with no way back.
     *
     * <p>Assumptions: a bare child carries no parent key, so the ONLY things a consumer can reconstruct
     * parentage from are the order the records were written in and each root's own
     * {@code PA-APPROVED-AUTH-CNT} plus {@code PA-DECLINED-AUTH-CNT} standing as the length of the run
     * that follows it. Both counters are two-byte binary fields, declared at {@code cpy/CIPAUSMY.cpy}
     * <strong>L27 and L28</strong>. Root order is therefore LOAD-BEARING in this form, which is the exact
     * opposite of the block above, and the two blocks are kept apart so that neither claim can be read as
     * the other's.
     */
    @Nested
    @DisplayName("shape (b), the bare sequential form, is order-dependent and terminal")
    class SequentialFormIsOptInAndTerminal {

        /**
         * Parentage reconstructs from write order plus each root's counter sum, on the export's output.
         *
         * <p>Assumptions: the reconstruction is checked against an INDEPENDENT oracle rather than against
         * itself. Each authorization carries a fifteen-character transaction identifier at
         * {@code cpy/CIPAUDTY.cpy} <strong>L44</strong>, and the prefixed extract states the parent of
         * each of those identifiers in its own prefix -- so the mapping from identifier to account is
         * available without appealing to order at all, and the run-length reading is measured against it.
         */
        @Test
        @DisplayName("the bare form reconstructs parentage from write order and each root's counter sum")
        void theBareFormReconstructsFromWriteOrderAndRunLength() {
            Extract exported = exportOver(UnloadServiceTest.this.storedRoots,
                    UnloadServiceTest.this.storedChildren, UnloadService.UnloadForm.SEQUENTIAL);

            Map<String, Long> truth = parentByTransactionIdentifier();
            List<Long> reconstructed =
                    reconstructBareParents(exported.rootFile(), exported.childFile());
            List<Long> actual = new ArrayList<>();
            for (byte[] segment : split(exported.childFile(),
                    PendingAuthDetailMapper.segmentLength())) {
                actual.add(truth.get(transactionIdentifierOf(segment)));
            }

            assertThat(reconstructed)
                    .as("the run lengths the roots declare are the only attribution this file has")
                    .containsExactlyElementsOf(actual);
            assertThat(reconstructed).containsExactly(ACCOUNT_ONE, ACCOUNT_ONE, ACCOUNT_TWO,
                    ACCOUNT_TWO);
        }

        /**
         * The same reconstruction works on the committed sequential fixtures, which are never reorderable.
         *
         * <p>Assumptions: the committed pair is used as well as the export's own output because the two
         * are independent statements. The fixture summary file is written in ASCENDING account order and
         * its detail file in contiguous per-parent runs, which is what makes the pair readable at all --
         * and it is the deliberate opposite of the prefixed pair beside it, whose summary file is
         * descending and whose detail file alternates. Neither sequential fixture may ever be reordered,
         * and the next two cases are what enforces that.
         */
        @Test
        @DisplayName("the committed sequential fixtures reconstruct to the parents the prefixed pair names")
        void theCommittedSequentialFixturesReconstruct() {
            byte[] roots = bytes(SEQUENTIAL_SUMMARY);
            byte[] children = bytes(SEQUENTIAL_DETAIL);

            assertThat(roots).hasSize(ROOT_COUNT * PendingAuthSummaryMapper.unloadRecordLength());
            assertThat(children).hasSize(CHILD_COUNT * PendingAuthDetailMapper.segmentLength());

            Map<String, Long> truth = parentByTransactionIdentifier();
            List<Long> reconstructed = reconstructBareParents(roots, children);
            List<Long> actual = new ArrayList<>();
            for (byte[] segment : split(children, PendingAuthDetailMapper.segmentLength())) {
                actual.add(truth.get(transactionIdentifierOf(segment)));
            }

            assertThat(reconstructed).containsExactlyElementsOf(actual);
            assertThat(reconstructed).containsExactly(ACCOUNT_ONE, ACCOUNT_ONE, ACCOUNT_TWO,
                    ACCOUNT_TWO);
        }

        /**
         * Reordering the root file misattributes every child, because the runs are the only mapping.
         *
         * <p>Assumptions: the two roots declare run lengths of two and two, so reversing them yields the
         * SAME run lengths against the SAME children and a reconstruction that is still well formed and
         * entirely wrong. That is the point worth asserting: the failure this form admits is silent, not
         * loud, so nothing in the file reveals it.
         */
        @Test
        @DisplayName("reversing the root file silently attributes every child to the wrong parent")
        void reorderingTheRootFileBreaksReconstruction() {
            byte[] roots = bytes(SEQUENTIAL_SUMMARY);
            byte[] children = bytes(SEQUENTIAL_DETAIL);
            List<Long> correct = reconstructBareParents(roots, children);

            List<Long> reordered = reconstructBareParents(
                    reverseRecords(roots, PendingAuthSummaryMapper.unloadRecordLength()), children);

            assertThat(reordered)
                    .as("a well-formed reconstruction of the same size, so nothing about the file"
                            + " reveals the misattribution")
                    .hasSameSizeAs(correct);
            assertThat(reordered)
                    .as("root order is load-bearing in this form, unlike in the prefixed one")
                    .isNotEqualTo(correct);
            assertThat(reordered).containsExactly(ACCOUNT_TWO, ACCOUNT_TWO, ACCOUNT_ONE, ACCOUNT_ONE);
        }

        /**
         * Reordering the child file misattributes every child for the same reason.
         *
         * <p>Assumptions: the child file is reversed rather than rotated, which moves each of the four
         * records across a run boundary. The reconstruction still consumes two records per root, so it
         * still returns four attributions and is still well formed -- and each one is now wrong, which is
         * the same silent failure the case above records from the other side.
         */
        @Test
        @DisplayName("reversing the child file silently attributes every child to the wrong parent")
        void reorderingTheChildFileBreaksReconstruction() {
            byte[] roots = bytes(SEQUENTIAL_SUMMARY);
            byte[] children = bytes(SEQUENTIAL_DETAIL);
            Map<String, Long> truth = parentByTransactionIdentifier();

            byte[] reversedChildren =
                    reverseRecords(children, PendingAuthDetailMapper.segmentLength());
            List<Long> reconstructed = reconstructBareParents(roots, reversedChildren);
            List<Long> actual = new ArrayList<>();
            for (byte[] segment : split(reversedChildren, PendingAuthDetailMapper.segmentLength())) {
                actual.add(truth.get(transactionIdentifierOf(segment)));
            }

            assertThat(reconstructed).hasSize(CHILD_COUNT);
            assertThat(reconstructed)
                    .as("the reconstruction now disagrees with the transaction identifiers, which is"
                            + " the only place the truth survives once the order is disturbed")
                    .isNotEqualTo(actual);
        }

        /**
         * A bare-child extract is refused as an import source at the record boundary, before any read.
         *
         * <p>Alternatives Considered: attempting a positional reconstruction on the way IN, so that a
         * sequential extract could be loaded by pairing it with its root file. Rejected because the
         * composite key {@code pending_auth_detail} declares is account, date and time, and a bare record
         * supplies only the last two -- so the account would have to come from a run-length reading of a
         * file whose order the store cannot vouch for, and a file that had been sorted, merged or
         * partially re-run would insert every authorization under a plausible wrong parent with nothing
         * failing. Refusing outright keeps the failure loud, which is the house stance the class-level
         * note cites.
         *
         * <p>Assumptions: the refusal is asserted to happen with NO repository interaction at all, which
         * is what makes it explicit rather than a downstream constraint violation. A load that reached the
         * store and was stopped by a key would also fail, and it would fail after opening a transaction
         * and issuing statements, reporting a constraint name instead of the file.
         */
        @Test
        @DisplayName("a bare-child extract is refused at the stride boundary with no store interaction")
        void aBareChildExtractIsRefusedAtTheStrideBoundary() {
            byte[] bare = bytes(SEQUENTIAL_DETAIL);
            PendingAuthSummaryRepository roots = mock(PendingAuthSummaryRepository.class);
            PendingAuthDetailRepository children = mock(PendingAuthDetailRepository.class);
            LoadService loader = new LoadService(roots, children, chunkTransactions(),
                    LoadService.DEFAULT_MAX_RECORDS);

            assertThat(bare.length % PendingAuthDetailMapper.unloadRecordLength())
                    .as("four bare segments are 800 bytes, which is not a whole number of 206-byte"
                            + " prefixed records")
                    .isNotZero();
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> loader.loadDetails(new ByteArrayInputStream(bare)))
                    .withMessageContaining("whole number of "
                            + PendingAuthDetailMapper.unloadRecordLength() + "-byte records");
            verifyNoInteractions(roots, children);
        }

        /**
         * A bare-child extract that IS a whole number of prefixed records is still refused, by ordinal.
         *
         * <p>Assumptions: this case exists because the length check above is a coincidence of the record
         * counts and not a guarantee. Two hundred and two hundred and six share a common multiple, so a
         * bare extract of a hundred and three segments occupies exactly a hundred prefixed strides and
         * passes every length test there is. What refuses it is the prefix itself: the first six bytes of a
         * bare segment are its packed date and part of its packed time, and their final nibble is a digit
         * rather than a sign, so the key does not decode. The refusal names the RECORD, which is what an
         * operator holding the file needs.
         *
         * <p>Trade-offs: relying on the prefix decode rather than on a declared form marker is accepted
         * here. A marker would refuse the file for the right reason at the first byte, and it would also
         * be a byte the reference format does not have, so the two forms would stop being the reference
         * records they are. The cost is that the refusal is a consequence of the geometry rather than a
         * declaration, and this case is what pins that consequence.
         */
        @Test
        @DisplayName("a bare extract sized to whole prefixed records is refused naming the record")
        void aBareChildExtractSizedToWholeRecordsIsStillRefused() {
            int segment = PendingAuthDetailMapper.segmentLength();
            int stride = PendingAuthDetailMapper.unloadRecordLength();
            // WHY : Assumptions: the record count is DERIVED from the two strides rather than written as a
            //       hundred and three. The least common multiple of the two lengths divided by the bare
            //       length is the smallest number of bare segments that also fills a whole number of
            //       prefixed records, so the fabricated file coincides by construction. Stating the count
            //       instead would silently stop coinciding the moment either stride changed, and the case
            //       would then pass by refusing the file at the length boundary -- which is the sibling
            //       case's claim and not this one's.
            int repeats = stride / gcd(segment, stride);
            byte[] oneSegment = Arrays.copyOf(bytes(SEQUENTIAL_DETAIL), segment);
            byte[] aligned = new byte[repeats * segment];
            for (int offset = 0; offset < aligned.length; offset += segment) {
                System.arraycopy(oneSegment, 0, aligned, offset, segment);
            }
            PendingAuthSummaryRepository roots = mock(PendingAuthSummaryRepository.class);
            PendingAuthDetailRepository children = mock(PendingAuthDetailRepository.class);
            LoadService loader = new LoadService(roots, children, chunkTransactions(),
                    LoadService.DEFAULT_MAX_RECORDS);

            assertThat(aligned.length % stride)
                    .as("the fabricated file passes every length test a prefixed reader can apply")
                    .isZero();
            assertThatExceptionOfType(LoadService.MalformedParentKeyException.class)
                    .isThrownBy(() -> loader.loadDetails(new ByteArrayInputStream(aligned)))
                    .satisfies(refusal -> assertThat(refusal.getRecordOrdinal())
                            .as("the refusal identifies the record, not the account it could not read")
                            .isEqualTo(1))
                    .withMessageContaining("parent key");
            verify(children, never()).insertDetailIfAbsent(any());
        }

        /**
         * Reads the fifteen-character transaction identifier out of one bare segment.
         *
         * @param segment one detail segment image of exactly the declared segment length; must not be
         *     {@code null}
         * @return the identifier as it stands in the record, trailing blanks included; never {@code null}
         */
        private String transactionIdentifierOf(byte[] segment) {
            return new String(segment, TRANSACTION_ID_OFFSET, TRANSACTION_ID_WIDTH,
                    StandardCharsets.ISO_8859_1);
        }

        /**
         * Builds the identifier-to-account mapping the prefixed extract states in its own prefixes.
         *
         * <p>Assumptions: this is the independent oracle the reconstruction cases measure against, and it
         * appeals to no ordering of any kind: each prefixed record states its parent and its transaction
         * identifier in the same bytes, so the mapping holds however either file is arranged.
         *
         * @return each transaction identifier in the prefixed extract mapped to its parent account; never
         *     {@code null}
         */
        private Map<String, Long> parentByTransactionIdentifier() {
            Map<String, Long> parents = new LinkedHashMap<>();
            for (byte[] record : split(bytes(PREFIXED_DETAIL),
                    PendingAuthDetailMapper.unloadRecordLength())) {
                parents.put(this.transactionIdentifierOf(PendingAuthDetailMapper
                        .unloadedSegment(record)), PendingAuthDetailMapper.unloadedAccountId(record));
            }
            return parents;
        }

        /**
         * Reconstructs the parent of every bare child from write order and the roots' counter sums.
         *
         * <p>Assumptions: this is the ONLY reading a consumer of this form has, and it is written here
         * rather than in the service because nothing in this migration consumes the form -- the service
         * writes it and no job reads it back. Stating the reading in the test is what documents it and
         * holds it to the two fixtures at the same time.
         *
         * @param rootFile a whole number of hundred-byte root records, in write order; must not be
         *     {@code null}
         * @param childFile a whole number of two-hundred-byte bare segments, in write order; must not be
         *     {@code null}
         * @return the parent account of each child in child-file order; never {@code null}
         * @throws AssertionError if the roots' counter sums do not account for exactly the children
         *     present, which would mean the file pair cannot be read at all
         */
        private List<Long> reconstructBareParents(byte[] rootFile, byte[] childFile) {
            List<byte[]> children = split(childFile, PendingAuthDetailMapper.segmentLength());
            List<Long> parents = new ArrayList<>();
            for (byte[] record : split(rootFile, PendingAuthSummaryMapper.unloadRecordLength())) {
                PendingAuthSummary root = PendingAuthSummaryMapper.fromExtractRecord(record);
                int run = root.getApprovedAuthCount().intValue() + root.getDeclinedAuthCount().intValue();
                for (int taken = 0; taken < run; taken++) {
                    if (parents.size() >= children.size()) {
                        throw new AssertionError("the roots claim more children than the child file"
                                + " holds, so this file pair cannot be read");
                    }
                    parents.add(root.getAccountId());
                }
            }
            if (parents.size() != children.size()) {
                throw new AssertionError("the roots account for " + parents.size() + " children but the"
                        + " child file holds " + children.size());
            }
            return parents;
        }
    }

    /**
     * The export's posture on a failed write is strict, where the load's posture on a duplicate is not.
     *
     * <p>Assumptions: the asymmetry is in the reference material and is carried across rather than
     * smoothed away. The sequential unload's two insert paragraphs treat ANY non-blank status as fatal --
     * {@code cbl/DBUNLDGS.CBL} <strong>L311 to L315</strong> abends at its <strong>L314</strong> for the
     * root and its <strong>L330 to L334</strong> abends at its <strong>L333</strong> for the child, and
     * neither has an arm for the already-present status. The load has one at BOTH levels:
     * {@code cbl/PAUDBLOD.CBL} <strong>L256 to L258</strong> reports the root duplicate and continues and
     * its <strong>L329 to L331</strong> does the same for the child, abending only at their
     * <strong>L259</strong> and <strong>L332</strong> for anything else. {@code LoadServiceTest} asserts
     * the tolerant half against the loader; these cases assert the strict half and the contrast, so
     * neither posture can be applied to the other side by mistake.
     */
    @Nested
    @DisplayName("the export is strict where the load tolerates a duplicate")
    class GsamInsertErrorPosture {

        /**
         * A write the output refuses raises, rather than being counted and the export continuing.
         *
         * <p>Assumptions: this is the migrated form of the abend both programs reach on a bad file
         * operation, each setting a return code of sixteen -- {@code cbl/PAUDBUNL.CBL} <strong>L308 to
         * L314</strong> and {@code cbl/DBUNLDGS.CBL} <strong>L357 to L363</strong>. Counting the record
         * and continuing would return counts that overstated what the file holds, and a caller
         * reconciling those counts against the table would then conclude the export was complete.
         *
         * <p>Assumptions: only the ROOT walk is answered here. The refusal happens on the first root
         * record before any child is reached, so answering the child walk as well would leave a stubbing
         * this case never uses, which the strict double correctly refuses as dead test code.
         */
        @Test
        @DisplayName("a stream that cannot be written raises instead of returning overstated counts")
        void aStreamThatCannotBeWrittenRaises() {
            givenTheRootWalkAnswers();

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> exporter().unload(new RefusingStream("root output"),
                            UnloadServiceTest.this.childFile))
                    .withMessageContaining("could not be written");
        }

        /**
         * The export carries no tolerated-outcome count, where the load's own outcome does.
         *
         * <p>Assumptions: the postures are compared through the SHAPE of what each service returns rather
         * than by driving a duplicate through the exporter, because the exporter has no duplicate to
         * drive: it writes what the walk hands it and a repeated row would simply be written twice. What
         * the reference asymmetry amounts to in the target is therefore that one service has a tolerated
         * arm to report and the other has none, and the two record declarations say so.
         */
        @Test
        @DisplayName("the export outcome has no tolerated arm to report, unlike the load outcome")
        void theExportOutcomeHasNoToleratedArm() {
            List<String> exportComponents = componentNamesOf(UnloadService.UnloadOutcome.class);
            List<String> loadComponents = componentNamesOf(LoadService.LoadOutcome.class);

            assertThat(exportComponents)
                    .as("what an export reports is written, written and passed over; nothing tolerated")
                    .containsExactly("rootsWritten", "childrenWritten", "rootsSkipped");
            assertThat(loadComponents)
                    .as("the loader reports a tolerated count, which is the duplicate arm its reference"
                            + " program has at both levels and the unload has at neither")
                    .contains("alreadyPresent");
            assertThat(exportComponents).doesNotContain("alreadyPresent");
        }

        /**
         * A child-side write failure is attributed to the child and never labelled a parent failure.
         *
         * <p>Assumptions: the reference material carries a misattributed diagnostic here and it is
         * recorded rather than reproduced. {@code cbl/DBUNLDGS.CBL} <strong>L331</strong> reports a CHILD
         * insert failure with the label {@code 'GSAM PARENT FAIL :'} followed by
         * {@code PADFL-PCB-STATUS} -- the child block's own status under the parent's label, which its
         * <strong>L312</strong> shows was copied from the paragraph above. An operator reading that line
         * looks at the wrong file. The target's diagnostic chain names the output that actually refused
         * the write, and the label the baseline used appears nowhere.
         */
        @Test
        @DisplayName("a child-side write failure names the child and never carries the parent label")
        void aChildSideFailureIsNotReportedAsAParentFailure() {
            givenTheWalksAnswerFromTheDecodedRows();

            assertThatExceptionOfType(UncheckedIOException.class)
                    .isThrownBy(() -> exporter().unload(UnloadServiceTest.this.rootFile,
                            new RefusingStream("child output")))
                    .satisfies(refusal -> {
                        assertThat(refusal.getCause()).hasMessageContaining("child output");
                        assertThat(refusal.getMessage())
                                .doesNotContainIgnoringCase(MISATTRIBUTED_LABEL)
                                .doesNotContainIgnoringCase("parent");
                    });
            assertThat(UnloadServiceTest.this.rootFile.size())
                    .as("the root that preceded the refused child was written, so the failure really"
                            + " did arise on the child side")
                    .isEqualTo(PendingAuthSummaryMapper.unloadRecordLength());
        }
    }

    /**
     * The walk terminates on the migrated form of each reference status, and the three sets stay apart.
     *
     * <p>Assumptions: the reference terminations are statuses and the migrated ones are query answers, so
     * each is asserted in its own vocabulary. End-of-database becomes an EMPTY page and ends the root
     * walk; segment-not-found-within-parent becomes an exhausted child list and ends one parent's
     * children without touching the root walk. What must not happen is the two being treated as one
     * signal, which is exactly the collapse the three reference sets forbid.
     */
    @Nested
    @DisplayName("the traversal terminations, kept in three distinct sets")
    class TraversalTermination {

        /**
         * An empty page ends the root walk and returns, where an exhausted child list ends one parent.
         *
         * <p>Assumptions: the two are asserted in ONE case because the property under test is that they
         * are different scopes. The account with no children still produces a root record and the walk
         * continues past it, which is the child-level termination staying child-level; the walk then stops
         * when a page arrives empty, which is the root-level one. A service that let the child signal end
         * the outer walk would export one root and stop, and that is what this distinguishes.
         */
        @Test
        @DisplayName("an exhausted child list ends one parent while an empty page ends the whole walk")
        void theTwoTerminationsHaveDifferentScopes() {
            givenTheRootWalkAnswers();
            // WHY : Assumptions: the child walk is answered EMPTY for the first account rather than for the
            //       second, so the walk has to carry on past a childless parent to reach one that has
            //       children. Emptying the last account instead would leave a service that stopped at the
            //       first empty child answer indistinguishable from a correct one, because there would be
            //       nothing after it to fail to export.
            answerChildChunks(UnloadServiceTest.this.details,
                    () -> UnloadServiceTest.this.storedChildren.stream()
                            .filter(child -> !ACCOUNT_ONE.equals(child.getId().getAccountId()))
                            .toList(),
                    () -> { });

            UnloadService.UnloadOutcome outcome = exporter()
                    .unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            assertThat(outcome.rootsWritten())
                    .as("the childless account did not end the outer walk, so both roots were written")
                    .isEqualTo(ROOT_COUNT);
            assertThat(outcome.childrenWritten())
                    .as("only the second account's two authorizations were written")
                    .isEqualTo(CHILD_COUNT / 2);
            // WHY : Assumptions: the outer walk still asks once more after its page and stops only on the
            //       empty answer, so the childless account did not shorten it. The figure is the page
            //       count and not the root count, which it only coincidentally equals here.
            verify(UnloadServiceTest.this.summaries, times(PAGE_QUERIES_FOR_ONE_PAGE)
                    .description("the childless account must not end the outer walk"))
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any());
        }

        /**
         * The three reference termination sets are pairwise different and none contains another.
         *
         * <p>Assumptions: this is asserted as a guard rather than as behaviour, and it is worth a case
         * because the three sets look interchangeable at a glance and are not. Segment-not-found is
         * TOLERATED in two of them and FATAL in the third, and end-of-database is tolerated in two and
         * fatal in the other one -- so a reader who unified any pair would move a fatal status into a
         * tolerated position or the reverse.
         */
        @Test
        @DisplayName("the three reference termination sets are distinct and must not be unified")
        void theThreeTerminationSetsAreDistinct() {
            assertThat(UNLOAD_CHILD_TERMINAL)
                    .as("the unload child walk tolerates segment-not-found and nothing else beyond"
                            + " success, so end-of-database is fatal there")
                    .containsExactly("  ", "GE")
                    .doesNotContain("GB");
            assertThat(PURGE_ROOT_TERMINAL)
                    .as("the purge root walk tolerates end-of-database, so segment-not-found is fatal")
                    .containsExactly("  ", "GB")
                    .doesNotContain("GE");
            assertThat(PURGE_CHILD_TERMINAL)
                    .as("the purge child walk tolerates both, which is the widest of the three")
                    .containsExactly("  ", "GE", "GB");

            assertThat(UNLOAD_CHILD_TERMINAL).isNotEqualTo(PURGE_ROOT_TERMINAL);
            assertThat(UNLOAD_CHILD_TERMINAL).isNotEqualTo(PURGE_CHILD_TERMINAL);
            assertThat(PURGE_ROOT_TERMINAL).isNotEqualTo(PURGE_CHILD_TERMINAL);
            assertThat(BLOCK_STATUS_FIELD)
                    .as("this pair of programs evaluates the block status; the screen and message-driven"
                            + " programs evaluate the interface status, and the two are never mixed")
                    .isNotEqualTo(INTERFACE_STATUS_FIELD);
        }

        /**
         * A page offering no key to resume from ends the walk instead of being requested for ever.
         *
         * <p>Assumptions: the double answers the SAME page to every call, which is what a real repository
         * would do if the walk could not advance its position -- the page predicate is strictly greater
         * than that position, so an unchanged position re-selects an unchanged page. Without the guard
         * under test this case does not fail, it does not finish, which is why it carries a timeout.
         */
        @Test
        @Timeout(value = TERMINATION_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("a page carrying no resumable account identifier ends the walk rather than repeating")
        void aPageWithNoResumableKeyEndsTheWalk() {
            List<PendingAuthSummary> unattributable = List.of(summaryWithNoAccountIdentifier());
            when(UnloadServiceTest.this.summaries
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any()))
                    .thenReturn(unattributable);

            UnloadService.UnloadOutcome outcome = exporter()
                    .unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            assertThat(outcome).isEqualTo(new UnloadService.UnloadOutcome(0, 0, 1));
            assertThat(UnloadServiceTest.this.rootFile.size()).isZero();
            assertThat(UnloadServiceTest.this.childFile.size()).isZero();
        }

        /**
         * The ended walk is reported by what the run had done, not by the key it stopped on.
         *
         * <p>Assumptions: the key this walk stops on is an account identifier, so the line reports the
         * run's two counters instead. The case asserts both that the counters are there and that neither
         * identifier the offending row carries appears anywhere in the captured stream -- the account
         * identifier is absent by construction in this fixture, so the customer identifier is the value
         * a well-meaning correction would reach for and the one worth pinning as forbidden.
         */
        @Test
        @Timeout(value = TERMINATION_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("the ended walk is reported by its counters and never by the key it stopped on")
        void theEndedWalkIsReportedWithoutTheKeyItStoppedOn() {
            List<PendingAuthSummary> unattributable = List.of(summaryWithNoAccountIdentifier());
            when(UnloadServiceTest.this.summaries
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any()))
                    .thenReturn(unattributable);

            exporter().unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            // WHY : ⚠️ Assumptions: the line is matched by its structured EVENT TOKEN rather than by the
            //       prose it once carried. Every operational record in this service is keyed by an
            //       event= token so a log query can match one field instead of a sentence, and matching
            //       prose would fail the moment the wording of a message changed without its meaning
            //       changing. What the case is about is unaffected: the counters are asserted, and the
            //       absence of the key the walk stopped on is asserted, because that key is an account
            //       identifier and this migration's logging contract withholds it from a durable record.
            assertThat(capturedMessages())
                    .anySatisfy(line -> assertThat(line)
                            .contains("event=authorization.unload.walk-ended-without-resume-key")
                            .contains("rootsWritten=0")
                            .contains("rootsSkipped=1")
                            .doesNotContain("resumeKey"));
            assertThat(capturedMessages())
                    .allSatisfy(line ->
                            assertThat(line).doesNotContain(ORPHAN_CUSTOMER.toString()));
        }
    }

    /**
     * The inner walk reads one account's authorizations in bounded keyset chunks.
     *
     * <p>Purpose: the exporter's memory footprint must be set by its chunk size and not by an account's
     * history. Before this set existed the inner read was a single unbounded query, so the outer page cap
     * bounded only how many summaries were resident while one account with a large history was still
     * loaded whole -- and no case here could tell the difference, because every fixture holds two
     * authorizations per account. These cases drive an account whose history exceeds one chunk, which is
     * the only shape in which the bound is observable at all.
     *
     * <p>Assumptions: the properties asserted are the ones a reader of the OUTPUT depends on -- every
     * authorization written exactly once, in one descending sequence, with no boundary visible in the file
     * -- plus the one an operator depends on, that more than one query is issued. The chunk FIGURE is
     * asserted only as the limit the walk asks for, because the class documents it as a batch size rather
     * than a contract; asserting a record count against it would turn a tuning value into a fixed
     * expectation.
     */
    @Nested
    @DisplayName("the child walk is bounded, and its chunk boundary is invisible in the output")
    class ChildWalkChunking {

        /**
         * The chunk the service asks for, mirrored here so a change to it fails loudly rather than quietly.
         *
         * <p>Assumptions: this restates {@code UnloadService.CHILD_CHUNK_SIZE}, which is private, and the
         * first case below asserts the limit the walk actually requests EQUALS it. Widening that constant
         * to package scope so a test could read it would change the production surface to suit a test;
         * mirroring it and asserting the mirror is the same protection without that cost, and a divergence
         * fails with a message naming both figures.
         */
        private static final int CHUNK = 500;

        /**
         * Builds one account's authorizations, descending in key, of the requested size.
         *
         * @param howMany how many authorizations to build; must not be negative
         * @return the authorizations, newest first; never {@code null}
         */
        private List<PendingAuthDetail> historyOf(int howMany) {
            // WHY : Alternatives Considered: composing each row from literal field values, which is what
            //       the sibling purge-job cases do. Rejected here because every non-key field would then
            //       be a width this class restated -- and the first attempt got one of them wrong,
            //       failing on a fifteen-character merchant identifier given seventeen characters. Copying
            //       every non-key field from a DECODED FIXTURE row instead means the only values this
            //       method chooses are the two that make up the key, which are the two the walk turns on.
            PendingAuthDetail template = UnloadServiceTest.this.storedChildren.getFirst();
            // WHY : Assumptions: the keys descend by TIME within one shared date rather than by date, so
            //       every row shares a boundary date with its neighbours. That is the shape a composite
            //       resumption is most easily got wrong on -- comparing the two components independently
            //       drops or repeats exactly the rows that share the boundary date -- so it is the shape
            //       worth generating.
            List<PendingAuthDetail> built = new ArrayList<>(howMany);
            for (int ordinal = 0; ordinal < howMany; ordinal++) {
                built.add(PendingAuthDetail.rehydrated(
                        new PendingAuthDetailKey(ACCOUNT_ONE,
                                template.getId().getAuthDate(),
                                Integer.valueOf(235_959 - ordinal)),
                        template.getAuthOrigDate(), template.getAuthOrigTime(),
                        template.getCardNum(), template.getAuthType(),
                        template.getCardExpiryDate(), template.getMessageType(),
                        template.getMessageSource(), template.getAuthIdCode(),
                        template.getAuthRespCode(), template.getAuthRespReason(),
                        template.getProcessingCode(), template.getTransactionAmount(),
                        template.getApprovedAmount(), template.getMerchantCategoryCode(),
                        template.getAcqrCountryCode(), template.getPosEntryMode(),
                        template.getMerchantId(), template.getMerchantName(),
                        template.getMerchantCity(), template.getMerchantState(),
                        template.getMerchantZip(), template.getTransactionId(),
                        template.getMatchStatus()));
            }
            return List.copyOf(built);
        }

        /**
         * Exports one account carrying the supplied history, over doubles built for this case alone.
         *
         * @param history the authorizations beneath the one account; must not be {@code null}
         * @param detailDouble the repository double to drive and later verify; must not be {@code null}
         * @return the child file the export wrote; never {@code null}
         */
        private byte[] exportHistory(List<PendingAuthDetail> history,
                PendingAuthDetailRepository detailDouble) {
            PendingAuthSummaryRepository summaryDouble = mock(PendingAuthSummaryRepository.class);
            PendingAuthSummary onlyRoot = UnloadServiceTest.this.storedRoots.stream()
                    .filter(root -> ACCOUNT_ONE.equals(root.getAccountId()))
                    .findFirst()
                    .orElseThrow();
            when(summaryDouble.findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any()))
                    .thenAnswer(invocation -> invocation.<Long>getArgument(0).longValue()
                            < ACCOUNT_ONE.longValue() ? List.of(onlyRoot) : List.of());
            answerChildChunks(detailDouble, () -> history, () -> { });
            ByteArrayOutputStream roots = new ByteArrayOutputStream();
            ByteArrayOutputStream children = new ByteArrayOutputStream();
            new UnloadService(summaryDouble, detailDouble)
                    .unload(UnloadService.UnloadForm.PREFIXED, roots, children);
            return children.toByteArray();
        }

        /**
         * A history longer than one chunk is read in more than one query, and asks for the chunk size.
         */
        @Test
        @DisplayName("a history longer than one chunk is read in two bounded queries, not one unbounded one")
        void aLongHistoryIsReadInBoundedChunks() {
            PendingAuthDetailRepository detailDouble = mock(PendingAuthDetailRepository.class);

            byte[] childFile = exportHistory(historyOf(CHUNK + 1), detailDouble);

            ArgumentCaptor<Limit> firstLimit = ArgumentCaptor.forClass(Limit.class);
            verify(detailDouble).findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(
                    eq(ACCOUNT_ONE), firstLimit.capture());
            assertThat(firstLimit.getValue().max())
                    .as("the walk asks for UnloadService.CHILD_CHUNK_SIZE rows; if that constant moved,"
                            + " move CHUNK in this class to match it")
                    .isEqualTo(CHUNK);
            // WHY : Assumptions: the resumption is verified as having happened AT LEAST once rather than
            //       exactly once, because the count follows from the chunk size and the history length
            //       and would have to be recomputed every time either moved. What matters is that the
            //       walk resumed at all -- an unbounded read never does -- and that the file is whole,
            //       which the next assertion states.
            verify(detailDouble, atLeastOnce()).findOlderThan(eq(ACCOUNT_ONE), any(), any(), any());
            assertThat(childFile.length)
                    .as("every authorization was written exactly once across the chunk boundary")
                    .isEqualTo((CHUNK + 1) * PendingAuthDetailMapper.unloadRecordLength());
        }

        /**
         * The records cross the chunk boundary in one unbroken descending sequence.
         */
        @Test
        @DisplayName("the written order is one descending sequence with no boundary in it")
        void theOrderIsUnbrokenAcrossTheChunkBoundary() {
            List<PendingAuthDetail> history = historyOf(CHUNK + 5);

            byte[] childFile = exportHistory(history, mock(PendingAuthDetailRepository.class));

            List<Integer> writtenTimes = new ArrayList<>();
            for (byte[] record : split(childFile, PendingAuthDetailMapper.unloadRecordLength())) {
                writtenTimes.add(PendingAuthDetailMapper.fromUnloadRecord(record)
                        .getId().getAuthTime());
            }
            // WHY : Assumptions: the file is decoded back and compared against the source order rather
            //       than merely checked for descent. Descent alone would hold for a file that repeated
            //       the boundary row or dropped it, since a repeat is not an ascent; comparing the whole
            //       sequence element for element is what catches both, which are precisely the two
            //       failures a mis-stated composite boundary produces.
            assertThat(writtenTimes).containsExactlyElementsOf(
                    history.stream().map(child -> child.getId().getAuthTime()).toList());
        }

        /**
         * A history that is an exact multiple of the chunk asks once more and is told nothing remains.
         */
        @Test
        @DisplayName("a history of exactly one chunk asks a second time and stops on the empty answer")
        void anExactMultipleAsksOnceMoreAndStops() {
            PendingAuthDetailRepository detailDouble = mock(PendingAuthDetailRepository.class);

            byte[] childFile = exportHistory(historyOf(CHUNK), detailDouble);

            // WHY : Assumptions: this is the case the short-chunk break alone cannot end. A full final
            //       chunk is indistinguishable from a chunk with more behind it, so the walk MUST ask
            //       again and rely on the empty answer -- which is why both break conditions exist and
            //       neither is redundant. A walk that stopped only on a short chunk would loop forever
            //       here, and one that stopped only on an empty chunk would still be correct but slower.
            verify(detailDouble, times(1)).findOlderThan(eq(ACCOUNT_ONE), any(), any(), any());
            assertThat(childFile.length)
                    .isEqualTo(CHUNK * PendingAuthDetailMapper.unloadRecordLength());
        }

        /**
         * A history shorter than one chunk never asks to resume.
         */
        @Test
        @DisplayName("a history shorter than one chunk issues no resumption query at all")
        void aShortHistoryNeverResumes() {
            PendingAuthDetailRepository detailDouble = mock(PendingAuthDetailRepository.class);

            byte[] childFile = exportHistory(historyOf(3), detailDouble);

            // WHY : Assumptions: this is the common case and it is asserted because the chunking must not
            //       cost a query per account on an estate of small histories. A short first chunk ends
            //       the walk without a second round trip, so the ordinary case pays exactly what the
            //       unbounded read paid.
            verify(detailDouble, never()).findOlderThan(any(), any(), any(), any());
            assertThat(childFile.length)
                    .isEqualTo(3 * PendingAuthDetailMapper.unloadRecordLength());
        }
    }

    /**
     * The export interleaves its two files in one pass, where the load is strictly two-pass.
     *
     * <p>Assumptions: the asymmetry is deliberate on both sides and unifying either would break the
     * other. The export can interleave because each shape carries its own attribution mechanism -- a
     * prefix in the prefixed form, a run length in the sequential one -- so a child may be written the
     * moment its parent has been. The load cannot, because a child insert is positioned by a preceding
     * root fetch, which is why {@code cbl/PAUDBLOD.CBL} runs its root loop at <strong>L177 to
     * L178</strong> to completion before its child loop begins at <strong>L180 to L181</strong>. Both
     * unload programs instead write a root and then its own children inside one loop, at
     * {@code cbl/PAUDBUNL.CBL} <strong>L233 to L236</strong>.
     */
    @Nested
    @DisplayName("the export interleaves in one pass while the load is two-pass")
    class InterleavedAgainstTwoPass {

        /**
         * The export reads one parent's children immediately after writing that parent, not afterwards.
         *
         * <p>Assumptions: the interleaving is asserted from the ORDER of the calls the two walks receive,
         * because that is the only place a single pass is distinguishable from two. A two-pass export
         * would ask for every page of summaries before asking for any children, and the recorded sequence
         * would then hold both root queries before both child queries.
         */
        @Test
        @DisplayName("each root's children are read before the next page of roots is requested")
        void theExportInterleavesRootsAndChildren() {
            List<String> callOrder = new ArrayList<>();
            // WHY : Alternatives Considered: the double serves ONE root per page rather than honouring the
            //       exporter's own page size, which would deliver both roots at once. With both in one
            //       page the recorded sequence is a single root query followed by two child queries, and
            //       that shape is the same whether the export interleaves or defers -- so the case could
            //       not tell the two apart. One root per page forces a second root query to fall BETWEEN
            //       the two child queries, which only an interleaved pass produces.
            when(UnloadServiceTest.this.summaries
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any()))
                    .thenAnswer(invocation -> {
                        callOrder.add("roots");
                        long after = invocation.<Long>getArgument(0).longValue();
                        return UnloadServiceTest.this.storedRoots.stream()
                                .filter(root -> root.getAccountId().longValue() > after)
                                .sorted(UnloadServiceTest::byAccountIdentifierAscending)
                                .limit(1L)
                                .toList();
                    });
            answerChildChunks(UnloadServiceTest.this.details,
                    () -> UnloadServiceTest.this.storedChildren,
                    () -> callOrder.add("children"));

            exporter().unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            assertThat(callOrder)
                    .as("a root page, then that root's children, then the next page: one interleaved"
                            + " pass and not two sequential ones")
                    .containsExactly("roots", "children", "roots", "children", "roots");
        }

        /**
         * The load consumes its two files in sequence, which the export's own output order then satisfies.
         *
         * <p>Assumptions: the two-pass order is observed through the loader's own presence probe, which it
         * issues against the summary repository from BOTH halves. Every root has been inserted by the time
         * the child half asks whether the parents exist, so the child half finds them -- and that is the
         * dependency the reference control flow has and the schema's own foreign key asserts. Interleaving
         * the load would ask about a parent that had not been inserted yet.
         */
        @Test
        @DisplayName("the load inserts every root before it asks whether any child's parent exists")
        void theLoadRunsItsTwoPassesInOrder() {
            Extract exported = exportOver(UnloadServiceTest.this.storedRoots,
                    UnloadServiceTest.this.storedChildren, UnloadService.UnloadForm.PREFIXED);

            Restored restored = restore(exported.rootFile(), exported.childFile());

            assertThat(restored.roots()).hasSize(ROOT_COUNT);
            assertThat(restored.children())
                    .as("no child was refused, so every parent was already present when its half ran")
                    .hasSize(CHILD_COUNT);
            assertThat(restored.outcome().inserted()).isEqualTo(ROOT_COUNT + CHILD_COUNT);
        }
    }

    /**
     * A root carrying no account identifier is passed over explicitly rather than in silence.
     *
     * <p>Assumptions: this is divergence {@code D-UNLOAD-SKIP-REPORTED}, described on
     * {@link UnloadService} and registered in {@code docs/architecture/cobol-to-service-traceability.md}.
     * Both halves of the reference guard are reproduced -- the root is not written and its children are
     * not read -- and what is added is the report, so a caller sees the shortfall in the result it already
     * holds instead of by counting the file against the table afterwards.
     */
    @Nested
    @DisplayName("an unattributable root is reported, where the reference programs report nothing")
    class UnattributableRootIsReported {

        /**
         * The row is passed over, counted on the outcome, and its children are never read.
         *
         * <p>Assumptions: three things are asserted together because the reference guard does three things
         * at once. Its false branch writes no root, and because the child walk sits INSIDE the guard at
         * {@code cbl/PAUDBUNL.CBL} <strong>L235 to L236</strong> it also reads no child, so the case
         * verifies the detail walk is never asked about the unattributable row. The count on the outcome is
         * the third and is the divergence itself.
         */
        @Test
        @DisplayName("a summary with no account key is skipped, counted, and its children left unread")
        void aSummaryWithNoAccountIdentifierIsSkippedAndCounted() {
            UnloadServiceTest.this.storedRoots.add(0, summaryWithNoAccountIdentifier());
            givenTheWalksAnswerFromTheDecodedRows();

            UnloadService.UnloadOutcome outcome = exporter()
                    .unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            assertThat(outcome.rootsSkipped())
                    .as("the skip is reported on the result, which the reference programs do not do")
                    .isEqualTo(1);
            assertThat(outcome.rootsWritten())
                    .as("every root that does carry a key is still exported")
                    .isEqualTo(ROOT_COUNT);
            assertThat(UnloadServiceTest.this.rootFile.size())
                    .as("no partial record was produced for the row that was passed over")
                    .isEqualTo(ROOT_COUNT * PendingAuthSummaryMapper.unloadRecordLength());
            assertThat(UnloadServiceTest.this.childFile.size())
                    .isEqualTo(CHILD_COUNT * PendingAuthDetailMapper.unloadRecordLength());
            verify(UnloadServiceTest.this.details, never())
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(eq(null), any());
            verify(UnloadServiceTest.this.details, times(ROOT_COUNT))
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any(), any());
        }

        /**
         * The skip is reported on the log by POSITION, and the row's own identifiers are withheld.
         *
         * <p>Refactoring Rationale: this case asserted that the report NAMED the customer identifier, and
         * the diagnostic did. Both were wrong in the same way. The withdrawn rationale argued that the
         * customer identifier was admissible because "the account is exactly the value that is absent" and
         * it was therefore "the one handle an operator has" -- but a customer identifier is a subject
         * identifier on a diagnostic path, and this service's two sibling walks were already held to
         * naming none. The run-local ordinal answers the operator's real question, which is how far into
         * the run the skip happened and how many there were, and it identifies nobody.
         *
         * <p>Assumptions: the case now asserts BOTH directions -- that the position IS reported and that
         * the customer identifier is NOT. Asserting only the first would pass against a line that reported
         * the ordinal and the identifier side by side, which is the state this change moved away from.
         */
        @Test
        @DisplayName("the skipped row is reported by run-local position and names no subject")
        void theSkippedRowIsReportedByPositionAndNamesNoSubject() {
            UnloadServiceTest.this.storedRoots.add(0, summaryWithNoAccountIdentifier());
            givenTheWalksAnswerFromTheDecodedRows();

            exporter().unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            assertThat(capturedMessages())
                    .as("the reference guard has no else branch at all, so this line is the whole of"
                            + " what the divergence adds")
                    .anySatisfy(line -> assertThat(line)
                            .contains("skipped")
                            .contains("skippedOrdinal=1"));
            assertThat(capturedMessages())
                    .as("no line may carry an identifier of the row that was passed over, even though"
                            + " the row the walk saw carries one")
                    .noneSatisfy(line ->
                            assertThat(line).contains(ORPHAN_CUSTOMER.toString()));
        }

        /**
         * Every root the walk reached is either written or counted as skipped, and never neither.
         *
         * <p>Assumptions: the identity is asserted rather than inferred from the two counts happening to
         * agree in the case above. It is the property that lets a caller detect a shortfall from the
         * result alone, so a third disposition appearing later -- a root neither written nor counted --
         * has to fail something, and this is that something.
         */
        @Test
        @DisplayName("roots written plus roots skipped is the number of summary rows the walk reached")
        void theOutcomeAccountsForEveryRootTheWalkReached() {
            UnloadServiceTest.this.storedRoots.add(summaryWithNoAccountIdentifier());
            int rowsPresented = UnloadServiceTest.this.storedRoots.size();
            givenTheWalksAnswerFromTheDecodedRows();

            UnloadService.UnloadOutcome outcome = exporter()
                    .unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            assertThat(outcome.rootsWritten() + outcome.rootsSkipped()).isEqualTo(rowsPresented);
        }
    }

    /**
     * Each count names what it counted, in both forms.
     *
     * <p>Assumptions: the reference report is wrong twice over, and both halves are corrected here. Its
     * summary counters are incremented inside the CHILD paragraph, at {@code cbl/PAUDBUNL.CBL}
     * <strong>L268 and L269</strong> and {@code cbl/DBUNLDGS.CBL} <strong>L278 and L279</strong>, so the
     * figure it prints as summaries read is the roots plus the children; and its detail counter, declared
     * at {@code cbl/PAUDBUNL.CBL} <strong>L68</strong>, is never incremented at all, that declaration
     * being its only occurrence in the program, so the figure it prints as details read is always zero.
     */
    @Nested
    @DisplayName("the counts name what they counted, not the sum of both")
    class CountsNameWhatTheyCount {

        /**
         * The prefixed form's counts equal the records each of its two files actually holds.
         *
         * <p>Assumptions: each count is measured against the FILE it names rather than against the
         * fixture's expected total alone, so a count that had absorbed the other's records fails on the
         * division. Under the reference arithmetic the summary figure here would be six rather than two,
         * and the detail figure zero rather than four.
         */
        @Test
        @DisplayName("the prefixed form counts two roots and four children, each against its own file")
        void thePrefixedFormCountsEachFileSeparately() {
            givenTheWalksAnswerFromTheDecodedRows();

            UnloadService.UnloadOutcome outcome = exporter()
                    .unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            assertThat(outcome.rootsWritten())
                    .as("the reference arithmetic would report the roots plus the children here")
                    .isEqualTo(UnloadServiceTest.this.rootFile.size()
                            / PendingAuthSummaryMapper.unloadRecordLength())
                    .isEqualTo(ROOT_COUNT);
            assertThat(outcome.childrenWritten())
                    .as("the reference detail counter is never incremented, so it would report zero")
                    .isEqualTo(UnloadServiceTest.this.childFile.size()
                            / PendingAuthDetailMapper.unloadRecordLength())
                    .isEqualTo(CHILD_COUNT);
            assertThat(outcome.rootsWritten() + outcome.childrenWritten())
                    .as("the inflated figure is what the two counts must never each equal")
                    .isEqualTo(ROOT_COUNT + CHILD_COUNT);
        }

        /**
         * The sequential form's counts equal the records its own two files hold, at its own stride.
         *
         * <p>Assumptions: the sequential form is counted separately rather than assumed to match, because
         * its child stride differs and a count derived from a byte total would be six bytes per record
         * wrong in exactly this form. That is the arithmetic a reader of the dead working-storage group
         * would produce, so the case also pins the geometry the class-level note settles.
         */
        @Test
        @DisplayName("the sequential form counts two roots and four children at the bare stride")
        void theSequentialFormCountsEachFileSeparately() {
            givenTheWalksAnswerFromTheDecodedRows();

            UnloadService.UnloadOutcome outcome = exporter()
                    .unload(UnloadService.UnloadForm.SEQUENTIAL, UnloadServiceTest.this.rootFile,
                            UnloadServiceTest.this.childFile);

            assertThat(outcome.rootsWritten())
                    .isEqualTo(UnloadServiceTest.this.rootFile.size()
                            / PendingAuthSummaryMapper.unloadRecordLength())
                    .isEqualTo(ROOT_COUNT);
            assertThat(outcome.childrenWritten())
                    .isEqualTo(UnloadServiceTest.this.childFile.size()
                            / PendingAuthDetailMapper.segmentLength())
                    .isEqualTo(CHILD_COUNT);
            assertThat(UnloadServiceTest.this.childFile.size()
                    % PendingAuthDetailMapper.segmentLength())
                    .as("the bare file divides by 200 and leaves nothing over")
                    .isZero();
        }
    }

    /**
     * Each export path identifies itself, where the reference programs share one name.
     *
     * <p>Assumptions: the reference collision is real and is recorded rather than reproduced. The literal
     * {@code 'IMSUNLOD'} is the program name in both unload programs, at {@code cbl/PAUDBUNL.CBL}
     * <strong>L54</strong> and {@code cbl/DBUNLDGS.CBL} <strong>L58</strong>, and the abend line at
     * {@code cbl/PAUDBUNL.CBL} <strong>L311</strong> prints it, so a failure in either is
     * indistinguishable in the log from a failure in the other.
     */
    @Nested
    @DisplayName("each export path identifies itself distinctly")
    class ExportPathIdentifiesItself {

        /**
         * The two paths are named distinctly at the call site, and neither is named after the shared name.
         *
         * <p>Assumptions: the identification a caller relies on is the FORM it names, so that is what is
         * asserted first. The two constants are distinct, they describe the record shape rather than the
         * storage technology each reference program happened to use, and neither carries the shared
         * program name that made the reference logs ambiguous.
         */
        @Test
        @DisplayName("the two forms are distinctly named and neither reuses the shared program name")
        void theTwoFormsAreDistinctlyNamed() {
            List<String> names = Arrays.stream(UnloadService.UnloadForm.values()).map(Enum::name)
                    .toList();

            assertThat(names).doesNotHaveDuplicates().hasSize(2);
            assertThat(names)
                    .as("the shared reference program name identifies neither path, which is the"
                            + " collision this replaces")
                    .doesNotContain(SHARED_REFERENCE_PROGRAM_NAME);
            assertThat(UnloadService.DEFAULT_FORM.name()).isIn(names);
        }

        /**
         * A produced child file identifies which path wrote it, by the stride it divides by.
         *
         * <p>Assumptions: the artifact identifies its own path here, which the reference log does not.
         * Exactly one of the two strides divides each file for these record counts, so a consumer holding
         * one file and neither the job that wrote it nor a log can still tell the two apart.
         *
         * <p>Trade-offs: the discriminator is arithmetic and is therefore not universal, and that is
         * stated rather than left to be discovered. Two hundred and two hundred and six share a common
         * multiple, so at a record count that reaches it both strides divide and the file alone no longer
         * decides -- which is why the FORM named at the call site is the primary identification and this is
         * the corroborating one. The bare-extract refusal case above is what covers the coincident case.
         */
        @Test
        @DisplayName("the child file each path writes divides by exactly one of the two strides")
        void theProducedFileIdentifiesItsPath() {
            givenTheWalksAnswerFromTheDecodedRows();

            exporter().unload(UnloadService.UnloadForm.PREFIXED, UnloadServiceTest.this.rootFile,
                    UnloadServiceTest.this.childFile);
            ByteArrayOutputStream sequentialRoots = new ByteArrayOutputStream();
            ByteArrayOutputStream sequentialChildren = new ByteArrayOutputStream();
            exporter().unload(UnloadService.UnloadForm.SEQUENTIAL, sequentialRoots,
                    sequentialChildren);

            int prefixed = PendingAuthDetailMapper.unloadRecordLength();
            int bare = PendingAuthDetailMapper.segmentLength();
            assertThat(UnloadServiceTest.this.childFile.size() % prefixed).isZero();
            assertThat(UnloadServiceTest.this.childFile.size() % bare)
                    .as("a prefixed file of this record count does not divide by the bare stride")
                    .isNotZero();
            assertThat(sequentialChildren.size() % bare).isZero();
            assertThat(sequentialChildren.size() % prefixed)
                    .as("and a bare file of this record count does not divide by the prefixed stride")
                    .isNotZero();
        }
    }

    /**
     * The exported bytes place every field where the copybook declares it, and money stays exact.
     *
     * <p>Assumptions: these are SERVICE-BOUNDARY assertions about what the export put in the file, not
     * codec proofs. Where a byte is read back through the shared kernel's packed codec, the codec is being
     * used as a reader; whether packing itself is correct belongs to that kernel's own tests and is not
     * re-proved here.
     */
    @Nested
    @DisplayName("the exported record geometry and its exact-money invariants")
    class RecordGeometryAndMoney {

        /**
         * The merchant name, match status and fraud position land at their declared offsets.
         *
         * <p>Assumptions: the three are pinned in ONE case because they are what an off-by-one offset
         * confuses. The merchant name is twenty-two bytes at a hundred and twelve, and the match status and
         * fraud position are consecutive single bytes at a hundred and seventy-three and a hundred and
         * seventy-four -- so a shift of one leaves both single-character fields holding a plausible value
         * that belongs to the other. Each is asserted against the VALUE the row carries rather than against
         * a literal byte, so the case fails on a shift instead of on a change of content.
         *
         * <p>Assumptions: the offsets are asserted in the SEGMENT and again in the prefixed record, at the
         * segment offset plus the prefix width. Reading the prefixed record at the bare offsets is exactly
         * the mistake the six-byte prefix invites, and it is a mistake that yields printable-looking values
         * rather than a failure.
         */
        @Test
        @DisplayName("the merchant name, match status and fraud position land at 112, 173 and 174")
        void theDeclaredOffsetsArePinnedInBothShapes() {
            givenTheWalksAnswerFromTheDecodedRows();
            PendingAuthDetail first = UnloadServiceTest.this.storedChildren.stream()
                    .filter(child -> ACCOUNT_ONE.equals(child.getId().getAccountId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "the committed detail fixture holds no child of account " + ACCOUNT_ONE));

            // WHY : Alternatives Considered: the bare form is exported FIRST so that the segment-relative
            //       offsets can be read straight off the file with no arithmetic. Taking them from a
            //       prefixed record instead would mean every assertion subtracted the prefix width, and an
            //       error in that subtraction would look exactly like an error in the offset it is meant to
            //       be pinning. The prefixed reading below is then the same three offsets shifted once, so
            //       the shift is asserted in one place rather than folded into each.
            exporter().unload(UnloadService.UnloadForm.SEQUENTIAL, UnloadServiceTest.this.rootFile,
                    UnloadServiceTest.this.childFile);
            byte[] bareSegment = Arrays.copyOf(UnloadServiceTest.this.childFile.toByteArray(),
                    PendingAuthDetailMapper.segmentLength());

            ByteArrayOutputStream prefixedRoots = new ByteArrayOutputStream();
            ByteArrayOutputStream prefixedChildren = new ByteArrayOutputStream();
            exporter().unload(UnloadService.UnloadForm.PREFIXED, prefixedRoots, prefixedChildren);
            byte[] prefixedRecord = Arrays.copyOf(prefixedChildren.toByteArray(),
                    PendingAuthDetailMapper.unloadRecordLength());

            assertThat(spanAt(bareSegment, MERCHANT_NAME_OFFSET, MERCHANT_NAME_WIDTH))
                    .isEqualTo(first.getMerchantName());
            assertThat((char) bareSegment[MATCH_STATUS_OFFSET])
                    .isEqualTo(first.getMatchStatus().charAt(0));
            assertThat((char) bareSegment[AUTH_FRAUD_OFFSET])
                    .as("the fixture rows carry no fraud marking, so this position is exported blank"
                            + " rather than omitted")
                    .isEqualTo(' ');
            assertThat(first.getAuthFraud()).isNull();

            assertThat(spanAt(prefixedRecord, prefixWidth() + MERCHANT_NAME_OFFSET,
                    MERCHANT_NAME_WIDTH))
                    .as("in the prefixed record every segment offset moves by the prefix width")
                    .isEqualTo(first.getMerchantName());
            assertThat(spanAt(prefixedRecord, MERCHANT_NAME_OFFSET, MERCHANT_NAME_WIDTH))
                    .as("reading a prefixed record at the bare offset yields content, not a failure,"
                            + " which is why the two readings are asserted apart")
                    .isNotEqualTo(first.getMerchantName());
        }

        /**
         * Trailing blanks in the merchant name are stored data and survive the export unchanged.
         *
         * <p>Assumptions: the name is a fixed twenty-two-byte character field, so its trailing blanks are
         * part of the value the record carries rather than padding a writer may re-derive. The committed
         * untrimmed fixture is the vector, and the case asserts the exported span is that span byte for
         * byte -- which a writer that trimmed and then re-padded would also satisfy, so the DECODED value
         * is asserted at full width too.
         */
        @Test
        @DisplayName("the merchant name keeps its trailing blanks through the export")
        void trailingBlanksInTheMerchantNameSurvive() {
            byte[] fixture = bytes(UNTRIMMED_DETAIL);
            PendingAuthDetail decoded = PendingAuthDetailMapper.toEntity(fixture, ACCOUNT_ONE);

            byte[] exported = PendingAuthDetailMapper.toSegment(decoded);

            assertThat(decoded.getMerchantName())
                    .as("the decoded value is the whole declared width, blanks included")
                    .hasSize(MERCHANT_NAME_WIDTH)
                    .endsWith(" ");
            assertThat(Arrays.copyOfRange(exported, MERCHANT_NAME_OFFSET,
                    MERCHANT_NAME_OFFSET + MERCHANT_NAME_WIDTH))
                    .isEqualTo(Arrays.copyOfRange(fixture, MERCHANT_NAME_OFFSET,
                            MERCHANT_NAME_OFFSET + MERCHANT_NAME_WIDTH));
            assertThat(exported)
                    .as("nothing else in the record moved either")
                    .isEqualTo(fixture);
        }

        /**
         * Every exported amount is exact fixed point at a scale of two and re-encodes to its own bytes.
         *
         * <p>Assumptions: this is AAP Rule T3 at the one boundary this service has. The boundary is a
         * fixed-width record rather than a payload, so what the rule's string clause amounts to here is
         * that an amount's rendering is a DECIMAL text form and never a binary fraction -- which is
         * asserted through the plain decimal string the shared money type produces. The JSON form of the
         * same rule belongs to the response boundary, which this service does not have.
         *
         * <p>Assumptions: the exported bytes are decoded and then re-encoded and compared, rather than
         * compared against a value computed here. A hand-written expectation authored from the same
         * misreading of an offset would agree with a wrong export; getting the original bytes back cannot.
         */
        @Test
        @DisplayName("exported amounts are BigDecimal at scale two and re-encode to the same bytes")
        void exportedAmountsAreExactFixedPoint() {
            givenTheWalksAnswerFromTheDecodedRows();

            exporter().unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);
            byte[] firstRoot = Arrays.copyOf(UnloadServiceTest.this.rootFile.toByteArray(),
                    PendingAuthSummaryMapper.unloadRecordLength());
            byte[] firstChild = PendingAuthDetailMapper.unloadedSegment(
                    Arrays.copyOf(UnloadServiceTest.this.childFile.toByteArray(),
                            PendingAuthDetailMapper.unloadRecordLength()));

            BigDecimal limit = PackedDecimalCodec.decodePacked(firstRoot, CREDIT_LIMIT_OFFSET,
                    SUMMARY_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, true);
            BigDecimal amount = PackedDecimalCodec.decodePacked(firstChild, TRANSACTION_AMOUNT_OFFSET,
                    DETAIL_MONEY_INT_DIGITS, MONEY_DEC_DIGITS, true);

            assertThat(Money.SCALE).isEqualTo(MONEY_DEC_DIGITS);
            assertThat(Money.GENERAL_ROUNDING).isEqualTo(RoundingMode.HALF_UP);
            assertThat(limit.scale()).isEqualTo(MONEY_DEC_DIGITS);
            assertThat(amount.scale()).isEqualTo(MONEY_DEC_DIGITS);
            assertThat(Money.of(limit).amount()).isEqualByComparingTo(limit);
            assertThat(Money.of(amount).toPlainString())
                    .as("the boundary form is a decimal string, never a binary fraction")
                    .isEqualTo(amount.toPlainString())
                    .contains(".");
            assertThat(PackedDecimalCodec.encodePacked(limit, SUMMARY_MONEY_INT_DIGITS,
                    MONEY_DEC_DIGITS, true))
                    .as("the exported packed bytes re-encode to exactly what was written")
                    .isEqualTo(Arrays.copyOfRange(firstRoot, CREDIT_LIMIT_OFFSET,
                            CREDIT_LIMIT_OFFSET + PackedDecimalCodec.packedWidth(
                                    SUMMARY_MONEY_INT_DIGITS, MONEY_DEC_DIGITS)));
        }

        /**
         * Terminator-shaped bytes inside a record survive the export, which is written in binary.
         *
         * <p>Assumptions: the committed vector holds the two-byte binary counters set to values whose
         * bytes ARE line terminators -- a carriage return followed by a line feed, a line feed twice, and
         * two negative values ending in each -- so a writer that went through a text-mode or
         * line-oriented path would translate or split them and the record count itself would change. The
         * counters are asserted by value and the bytes by identity, because a translated byte pair can
         * still decode to a plausible number.
         *
         * <p>Assumptions: the whole record is compared, not only the counter span, since a splitting
         * writer would change the length rather than one field.
         */
        @Test
        @DisplayName("terminator-shaped counter bytes survive the export unchanged")
        void terminatorShapedBytesSurviveTheExport() {
            byte[] fixture = bytes(TERMINATOR_SUMMARY);
            List<byte[]> records =
                    split(fixture, PendingAuthSummaryMapper.unloadRecordLength());
            ByteArrayOutputStream written = new ByteArrayOutputStream();

            for (byte[] record : records) {
                byte[] exported = PendingAuthSummaryMapper
                        .toUnloadRecord(PendingAuthSummaryMapper.fromExtractRecord(record));
                written.write(exported, 0, exported.length);
            }

            assertThat(records).hasSize(ROOT_COUNT);
            assertThat(written.toByteArray())
                    .as("no byte was translated and no record was split, so the count is unchanged too")
                    .isEqualTo(fixture);
            assertThat(written.size() / PendingAuthSummaryMapper.unloadRecordLength())
                    .isEqualTo(ROOT_COUNT);
        }

        /**
         * A non-blank trailing filler is tolerated on the way in and is written back as blanks.
         *
         * <p>Assumptions: this vector is DECODE-ONLY and the case is written so that it cannot be mistaken
         * for a round trip. The reader accepts value-bearing bytes in the filler, because refusing them
         * would refuse a record whose declared fields are all well formed; the export writes blanks there,
         * because the filler is padding to the declared record length and carries no field. Asserting byte
         * identity for this fixture would assert the opposite of that and would fail.
         */
        @Test
        @DisplayName("a non-blank filler is read but never written back, so this vector is decode-only")
        void aNonBlankFillerIsDecodedButNotReproduced() {
            byte[] fixture = bytes(NONBLANK_FILLER_SUMMARY);

            byte[] exported = PendingAuthSummaryMapper
                    .toUnloadRecord(PendingAuthSummaryMapper.fromExtractRecord(fixture));

            assertThat(spanAt(fixture, SUMMARY_FILLER_OFFSET, SUMMARY_FILLER_WIDTH))
                    .as("the vector really does carry value-bearing bytes there")
                    .isNotBlank();
            assertThat(spanAt(exported, SUMMARY_FILLER_OFFSET, SUMMARY_FILLER_WIDTH))
                    .as("the export writes padding, so those bytes are blanks and not what was read")
                    .isBlank();
            assertThat(exported).isNotEqualTo(fixture);
            assertThat(Arrays.copyOf(exported, SUMMARY_FILLER_OFFSET))
                    .as("every declared field ahead of the filler is reproduced exactly")
                    .isEqualTo(Arrays.copyOf(fixture, SUMMARY_FILLER_OFFSET));
        }

        /**
         * A negatively-signed packed zero decodes to an exact zero at scale two and is not re-emitted.
         *
         * <p>Assumptions: this vector is decode-only for the same class of reason as the one above, and the
         * normalisation it exercises is registered elsewhere as its own difference rather than claimed
         * here. What this case asserts is the part that belongs to this boundary: the decoded amount is
         * exact fixed point at a scale of two whatever sign nibble it arrived under, so a negatively-signed
         * zero cannot become a non-zero or a differently-scaled value on the way through.
         */
        @Test
        @DisplayName("a packed negative zero decodes to an exact zero and the export writes it positive")
        void aPackedNegativeZeroDecodesExactly() {
            byte[] fixture = bytes(NEGATIVE_ZERO_SUMMARY);
            int width = PackedDecimalCodec.packedWidth(SUMMARY_MONEY_INT_DIGITS, MONEY_DEC_DIGITS);

            PendingAuthSummary decoded = PendingAuthSummaryMapper.fromExtractRecord(fixture);
            byte[] exported = PendingAuthSummaryMapper.toUnloadRecord(decoded);

            assertThat(decoded.getCreditBalance())
                    .as("the amount is exact fixed point at scale two, sign nibble notwithstanding")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(decoded.getCreditBalance().scale()).isEqualTo(MONEY_DEC_DIGITS);
            assertThat(Arrays.copyOfRange(exported, CREDIT_BALANCE_OFFSET,
                    CREDIT_BALANCE_OFFSET + width))
                    .as("the export does not re-emit the negative sign nibble, so this is not a"
                            + " byte-identical round trip and is not asserted as one")
                    .isNotEqualTo(Arrays.copyOfRange(fixture, CREDIT_BALANCE_OFFSET,
                            CREDIT_BALANCE_OFFSET + width));
        }
    }

    /**
     * The export declares a read-only unit of work and reaches no operation that writes.
     *
     * <p>Assumptions: the posture rests on four independent readings of the reference material recorded on
     * {@link UnloadService} -- both access specifications declare get-only processing, both jobs run
     * outside the online region, the update-capable specification is passed by the load and purge jobs and
     * not by either unload, and both unload jobs hold the database directly. So the boundary is declared
     * read-only rather than merely left without writes.
     */
    @Nested
    @DisplayName("the export declares a read-only unit of work")
    class ReadOnlyUnitOfWork {

        /**
         * Both entry points declare a read-only unit of work and the export invokes only the two walks.
         *
         * <p>Assumptions: the posture is asserted from two directions because either alone is incomplete.
         * The annotation is what makes the provider skip dirty checking and what a reviewer reads, so it is
         * asserted on BOTH public entry points; the interaction check is what proves no repository
         * operation that stores or removes a row was reached, which an annotation cannot promise.
         */
        @Test
        @DisplayName("both entry points declare read-only and only the two walk methods are invoked")
        void theExportDeclaresReadOnlyAndPerformsNoWrites() {
            givenTheWalksAnswerFromTheDecodedRows();

            assertThat(readOnlyDeclarationOf(OutputStream.class, OutputStream.class)).isTrue();
            assertThat(readOnlyDeclarationOf(UnloadService.UnloadForm.class, OutputStream.class,
                    OutputStream.class)).isTrue();

            exporter().unload(UnloadServiceTest.this.rootFile, UnloadServiceTest.this.childFile);

            verify(UnloadServiceTest.this.summaries, times(PAGE_QUERIES_FOR_ONE_PAGE))
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any());
            verify(UnloadServiceTest.this.details, times(ROOT_COUNT))
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any(), any());
            verifyNoMoreInteractions(UnloadServiceTest.this.summaries,
                    UnloadServiceTest.this.details);
        }

        /**
         * The sequential form reaches no write either, so the posture does not depend on the shape.
         *
         * <p>Assumptions: the second form is asserted separately rather than assumed to inherit the first,
         * because the two take different paths through the child encoder and only one of them is the
         * default. A shape-specific write would be reached by exactly the path a default-only case never
         * drives.
         */
        @Test
        @DisplayName("the sequential form reaches no write operation either")
        void theSequentialFormAlsoPerformsNoWrites() {
            givenTheWalksAnswerFromTheDecodedRows();

            exporter().unload(UnloadService.UnloadForm.SEQUENTIAL, UnloadServiceTest.this.rootFile,
                    UnloadServiceTest.this.childFile);

            verify(UnloadServiceTest.this.summaries, times(PAGE_QUERIES_FOR_ONE_PAGE))
                    .findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any());
            verify(UnloadServiceTest.this.details, times(ROOT_COUNT))
                    .findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any(), any());
            verifyNoMoreInteractions(UnloadServiceTest.this.summaries,
                    UnloadServiceTest.this.details);
        }

        /**
         * The counts refuse a negative child total rather than carrying one.
         *
         * <p>Assumptions: the guard is asserted because the carrier's whole value is that its components
         * can be reconciled against the table, and a negative component would let two wrong numbers sum to
         * a plausible one. The condition is unreachable from the walk, whose child count is a list size, so
         * the assertion documents the contract for any later caller of the accumulator.
         */
        @Test
        @DisplayName("the outcome carrier refuses a negative child count")
        void theOutcomeRefusesANegativeChildCount() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> UnloadService.UnloadOutcome.NOTHING.andRoot(-1))
                    .withMessageContaining("must not be negative");
        }
    }

    /**
     * Builds the exporter over the two class-level doubles.
     *
     * @return a new exporter reading the two repository doubles; never {@code null}
     */
    private UnloadService exporter() {
        return new UnloadService(this.summaries, this.details);
    }

    /**
     * Answers both repository walk methods from the decoded rows, in their declared orders.
     */
    private void givenTheWalksAnswerFromTheDecodedRows() {
        givenTheRootWalkAnswers();
        givenTheChildWalkAnswers();
    }

    /**
     * Answers the outer walk from the decoded summary rows, honouring its order and its limit.
     *
     * <p>Assumptions: the answer filters strictly ABOVE the position it is given and honours the
     * {@link Limit}, reproducing the contract the repository declares rather than the order the fixture
     * happens to hold. The exporter depends on both -- it resumes from the last key and decides a walk is
     * finished by receiving an empty page -- so a double that ignored either would be defining those
     * signals instead of exercising them.
     *
     * <p>Assumptions: a row carrying no account identifier is offered on the FIRST page only, and that is
     * the closest a double can come to how such a row can arrive at all. A keyed predicate cannot return
     * one, since a comparison against an absent value matches no row, so the row stands for a summary
     * reaching the exporter from a source that is not that query. Offering it on every page instead would
     * present the same row to the walk repeatedly and count one skip as several.
     */
    private void givenTheRootWalkAnswers() {
        when(this.summaries.findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any()))
                .thenAnswer(invocation -> {
                    long after = invocation.<Long>getArgument(0).longValue();
                    Limit limit = invocation.getArgument(1);
                    boolean openingPage = this.pagesServed == 0;
                    this.pagesServed++;
                    return this.storedRoots.stream()
                            .filter(root -> root.getAccountId() == null
                                    ? openingPage
                                    : root.getAccountId().longValue() > after)
                            .sorted(UnloadServiceTest::byAccountIdentifierAscending)
                            .limit(limit.max())
                            .toList();
                });
    }

    /**
     * Answers the inner walk from the decoded authorization rows of one account, newest first.
     *
     * <p>Assumptions: the answer reproduces the ORDER the query method's own name declares rather than the
     * order the fixture holds, because that order is the contract the export depends on and a double that
     * returned fixture order would let a broken walk pass. The committed detail fixture deliberately
     * alternates its parents, so its order is not a per-account order at all.
     */
    private void givenTheChildWalkAnswers() {
        answerChildChunks(this.details, () -> this.storedChildren, () -> { });
    }

    /**
     * Exports the rows handed in, over doubles built for this call alone.
     *
     * <p>Assumptions: fresh doubles are used rather than the class-level pair because the circuit cases
     * export twice from two different row sets -- the decoded fixtures and then the restored rows -- and one
     * pair of doubles cannot answer the same walk from two sources without the second stubbing replacing
     * the first.
     *
     * @param roots the summary rows the outer walk should answer with, in any order; must not be
     *     {@code null}
     * @param children the authorization rows the inner walk should answer with, in any order; must not be
     *     {@code null}
     * @param form which record shape the child file should carry; must not be {@code null}
     * @return the two files the export wrote; never {@code null}
     */
    private static Extract exportOver(List<PendingAuthSummary> roots,
            List<PendingAuthDetail> children, UnloadService.UnloadForm form) {
        PendingAuthSummaryRepository summaryDouble = mock(PendingAuthSummaryRepository.class);
        PendingAuthDetailRepository detailDouble = mock(PendingAuthDetailRepository.class);
        when(summaryDouble.findByAccountIdGreaterThanOrderByAccountIdAsc(any(), any()))
                .thenAnswer(invocation -> {
                    long after = invocation.<Long>getArgument(0).longValue();
                    Limit limit = invocation.getArgument(1);
                    return roots.stream()
                            .filter(root -> root.getAccountId().longValue() > after)
                            .sorted(UnloadServiceTest::byAccountIdentifierAscending)
                            .limit(limit.max())
                            .toList();
                });
        answerChildChunks(detailDouble, () -> children, () -> { });
        ByteArrayOutputStream roundRoots = new ByteArrayOutputStream();
        ByteArrayOutputStream roundChildren = new ByteArrayOutputStream();
        new UnloadService(summaryDouble, detailDouble).unload(form, roundRoots, roundChildren);
        return new Extract(roundRoots.toByteArray(), roundChildren.toByteArray());
    }

    /**
     * Loads a prefixed file pair through {@link LoadService}, over doubles that remember what they store.
     *
     * <p>Assumptions: the doubles answer the batched presence query FROM what they have already accepted,
     * which is what makes the loader's two passes behave as they do against a real store -- the child half
     * finds its parents because the root half committed them. A double answering presence from a fixed set
     * would either skip every root as present or refuse every child as unparented.
     *
     * <p>Assumptions: the stubbings are LENIENT because a malformed extract is refused before any of them
     * is reached, and two cases here exercise exactly that. Strict stubbings would report those legitimate
     * refusals as unused test code.
     *
     * @param rootFile the summary extract to load, a whole number of hundred-byte records; must not be
     *     {@code null}
     * @param childFile the detail extract to load, a whole number of prefixed records; must not be
     *     {@code null}
     * @return what the loader reported together with the rows it accepted, in acceptance order; never
     *     {@code null}
     */
    private static Restored restore(byte[] rootFile, byte[] childFile) {
        PendingAuthSummaryRepository summaryDouble = mock(PendingAuthSummaryRepository.class);
        PendingAuthDetailRepository detailDouble = mock(PendingAuthDetailRepository.class);
        List<PendingAuthSummary> acceptedRoots = new ArrayList<>();
        List<PendingAuthDetail> acceptedChildren = new ArrayList<>();
        lenient().when(summaryDouble.insertSummaryIfAbsent(any())).thenAnswer(invocation -> {
            PendingAuthSummary row = invocation.getArgument(0);
            boolean known = acceptedRoots.stream()
                    .anyMatch(stored -> stored.getAccountId().equals(row.getAccountId()));
            if (known) {
                return Integer.valueOf(0);
            }
            acceptedRoots.add(row);
            return Integer.valueOf(1);
        });
        lenient().when(summaryDouble.findExistingAccountIds(any())).thenAnswer(invocation -> {
            Collection<Long> wanted = invocation.getArgument(0);
            return acceptedRoots.stream().map(PendingAuthSummary::getAccountId)
                    .filter(wanted::contains).toList();
        });
        lenient().when(detailDouble.insertDetailIfAbsent(any())).thenAnswer(invocation -> {
            PendingAuthDetail row = invocation.getArgument(0);
            boolean known = acceptedChildren.stream()
                    .anyMatch(stored -> stored.getId().equals(row.getId()));
            if (known) {
                return Integer.valueOf(0);
            }
            acceptedChildren.add(row);
            return Integer.valueOf(1);
        });
        lenient().when(detailDouble.findExistingIds(any())).thenAnswer(invocation -> {
            Collection<PendingAuthDetailKey> wanted = invocation.getArgument(0);
            return acceptedChildren.stream().map(PendingAuthDetail::getId).filter(wanted::contains)
                    .toList();
        });
        LoadService.LoadOutcome outcome =
                new LoadService(summaryDouble, detailDouble, chunkTransactions(),
                        LoadService.DEFAULT_MAX_RECORDS)
                        .load(new ByteArrayInputStream(rootFile),
                                new ByteArrayInputStream(childFile));
        return new Restored(outcome, acceptedRoots, acceptedChildren);
    }

    /**
     * Builds a transaction template whose transactions begin and commit without a database.
     *
     * <p>Assumptions: a real template over a mocked manager is used rather than a stub that simply runs the
     * callback, because the loader depends on its transaction ENDING per chunk and a stub that never asked
     * for one would let a loader which stopped opening one still pass. The manager answers a mocked status,
     * so the template begins, runs the callback once and commits.
     *
     * <p>Assumptions: the stubbing is lenient because a malformed extract is refused while the record
     * reader is filling the first chunk, which happens OUTSIDE the template, so the refusal cases here
     * legitimately never ask this manager for anything.
     *
     * @return a transaction template that begins and commits without a database; never {@code null}
     */
    private static TransactionTemplate chunkTransactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        lenient().when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Orders two summary rows the way the outer walk declares, with an unattributable row first.
     *
     * <p>Assumptions: a row carrying no account identifier sorts FIRST rather than being dropped here, so
     * the exporter is the thing that decides what to do with it. Dropping it in the double would make every
     * case about the guard vacuous.
     *
     * @param left the first row to compare; must not be {@code null}
     * @param right the second row to compare; must not be {@code null}
     * @return a negative value when {@code left} sorts first, zero when the two tie, positive otherwise
     */
    private static int byAccountIdentifierAscending(PendingAuthSummary left,
            PendingAuthSummary right) {
        if (left.getAccountId() == null) {
            return right.getAccountId() == null ? 0 : -1;
        }
        if (right.getAccountId() == null) {
            return 1;
        }
        return Long.compare(left.getAccountId().longValue(), right.getAccountId().longValue());
    }

    /**
     * Answers both bounded child reads from one row source, honouring the limit and the keyset boundary.
     *
     * <p>Purpose: the exporter walks an account's authorizations in keyset chunks, so a double has to
     * answer two methods consistently -- the account-only read for the first chunk and the
     * strictly-older read for every later one -- and has to respect the limit each is given. Every case
     * that drives the inner walk routes through here so that no case can pass against a double that
     * returns everything regardless of the limit it was handed.
     *
     * <p>Assumptions: the older-than answer applies the SAME composite predicate the query declares --
     * a row qualifies when its date is earlier, or its date is equal and its time is earlier -- rather
     * than comparing the two components independently. A double that compared them independently would
     * either drop or repeat rows sharing the boundary date, and it would do so in the double rather
     * than in the code under test, which is the way a test lies about a walk it appears to cover.
     *
     * @param repository the double to stub; must not be {@code null}
     * @param perAccount every stored authorization, from which each answer is filtered and ordered; must
     *     not be {@code null}
     * @param onCall invoked once per answered call, for cases that record call ordering; must not be
     *     {@code null}
     */
    private static void answerChildChunks(PendingAuthDetailRepository repository,
            java.util.function.Supplier<List<PendingAuthDetail>> perAccount, Runnable onCall) {
        when(repository.findByIdAccountIdOrderByIdAuthDateDescIdAuthTimeDesc(any(), any()))
                .thenAnswer(invocation -> {
                    onCall.run();
                    Long accountId = invocation.getArgument(0);
                    Limit limit = invocation.getArgument(1);
                    return perAccount.get().stream()
                            .filter(child -> accountId.equals(child.getId().getAccountId()))
                            .sorted(UnloadServiceTest::newestFirst)
                            .limit(limit.max())
                            .toList();
                });
        // WHY : Assumptions: the resumption read is stubbed LENIENTLY while the first read is strict, and
        //       the asymmetry states a fact about the walk rather than working around the framework.
        //       Every fixture in this class holds fewer authorizations beneath one account than a chunk
        //       holds, so the first chunk comes back short and the walk correctly stops without ever
        //       asking to resume -- a strict stub would therefore fail each of those cases for doing
        //       exactly the right thing. The one case that does fill a chunk asserts the resumption call
        //       explicitly by verifying it, which is a stronger statement than strictness would make.
        lenient().when(repository.findOlderThan(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    onCall.run();
                    Long accountId = invocation.getArgument(0);
                    int afterDate = invocation.<Integer>getArgument(1).intValue();
                    int afterTime = invocation.<Integer>getArgument(2).intValue();
                    Limit limit = invocation.getArgument(3);
                    return perAccount.get().stream()
                            .filter(child -> accountId.equals(child.getId().getAccountId()))
                            .filter(child -> {
                                int date = child.getId().getAuthDate().intValue();
                                int time = child.getId().getAuthTime().intValue();
                                return date < afterDate || (date == afterDate && time < afterTime);
                            })
                            .sorted(UnloadServiceTest::newestFirst)
                            .limit(limit.max())
                            .toList();
                });
    }

    /**
     * Orders two authorizations newest first, which is the order the inner walk declares.
     *
     * @param left the first authorization to compare; must not be {@code null}
     * @param right the second authorization to compare; must not be {@code null}
     * @return a negative value when {@code left} is newer, positive when older, zero when the keys tie
     */
    private static int newestFirst(PendingAuthDetail left, PendingAuthDetail right) {
        PendingAuthDetailKey leftKey = left.getId();
        PendingAuthDetailKey rightKey = right.getId();
        int byDate = Integer.compare(rightKey.getAuthDate(), leftKey.getAuthDate());
        return byDate != 0 ? byDate : Integer.compare(rightKey.getAuthTime(), leftKey.getAuthTime());
    }

    /**
     * Builds a summary row that carries no account identifier.
     *
     * <p>Assumptions: a double is used rather than a constructed entity, because the aggregate refuses an
     * absent account identifier at construction -- the schema declares that column the whole primary key
     * and not null. The condition under test is therefore reachable only from a summary that did not come
     * from that table, which is exactly what this stands for.
     *
     * @return a summary answering an absent account identifier and a present customer identifier; never
     *     {@code null}
     */
    private static PendingAuthSummary summaryWithNoAccountIdentifier() {
        PendingAuthSummary orphan = mock(PendingAuthSummary.class);
        when(orphan.getAccountId()).thenReturn(null);
        // WHY : Assumptions: the customer identifier is stubbed LENIENTLY and is deliberately still
        //       present, even though the export no longer reads it. A real orphan row carries one --
        //       cpy/CIPAUSMY.cpy declares it at L20 -- and the non-disclosure case above asserts that no
        //       log line contains it. Removing the stub because nothing reads it any more would make that
        //       assertion vacuous: it would then be checking that a line does not contain a value the
        //       fixture never held. Leniency is what lets the value stay available to the two cases that
        //       do not exercise the diagnostic.
        lenient().when(orphan.getCustomerId()).thenReturn(ORPHAN_CUSTOMER);
        return orphan;
    }

    /**
     * Reports whether one {@code unload} overload declares a read-only unit of work.
     *
     * @param parameterTypes the parameter types identifying the overload; must not be {@code null}
     * @return {@code true} when that overload carries a read-only transactional declaration
     * @throws IllegalStateException if no {@code unload} overload has those parameter types, which would
     *     mean this case is asserting against a method that no longer exists
     */
    private static boolean readOnlyDeclarationOf(Class<?>... parameterTypes) {
        Method overload;
        try {
            overload = UnloadService.class.getMethod("unload", parameterTypes);
        } catch (NoSuchMethodException absent) {
            throw new IllegalStateException("UnloadService declares no unload overload taking "
                    + Arrays.toString(parameterTypes), absent);
        }
        Transactional declaration = overload.getAnnotation(Transactional.class);
        return declaration != null && declaration.readOnly();
    }

    /**
     * Lists the component names one record type declares, in declaration order.
     *
     * @param carrier the record type to read; must not be {@code null}
     * @return its component names in declaration order; never {@code null}
     * @throws AssertionError if the type is not a record, which would mean the shape this case compares
     *     no longer exists
     */
    private static List<String> componentNamesOf(Class<?> carrier) {
        RecordComponent[] components = carrier.getRecordComponents();
        if (components == null) {
            throw new AssertionError(carrier.getName() + " is not a record, so it declares no components");
        }
        return Arrays.stream(components).map(RecordComponent::getName).toList();
    }

    /**
     * Reads one fixed-width character span out of a record as text.
     *
     * <p>Assumptions: the single-byte Latin encoding is used rather than a multi-byte one, because these
     * spans are fixed-width character fields in which one byte is one position -- a multi-byte decode could
     * combine two bytes into one character and change the width the assertion measures.
     *
     * @param record the record bytes to read from; must not be {@code null}
     * @param offset the zero-based start of the span
     * @param width how many bytes the span occupies
     * @return the span as text, trailing blanks included; never {@code null}
     */
    private static String spanAt(byte[] record, int offset, int width) {
        return new String(record, offset, width, StandardCharsets.ISO_8859_1);
    }

    /**
     * Divides a file into whole records of one stride.
     *
     * @param file the bytes to divide; must not be {@code null}
     * @param stride how many bytes one record occupies; must be positive
     * @return the records in file order; never {@code null}
     * @throws AssertionError if the file does not hold a whole number of records, which would mean the
     *     stride and the file disagree and every assertion built on the split would read shifted bytes
     */
    private static List<byte[]> split(byte[] file, int stride) {
        if (file.length % stride != 0) {
            throw new AssertionError("a file of " + file.length + " bytes is not a whole number of "
                    + stride + "-byte records");
        }
        List<byte[]> records = new ArrayList<>(file.length / stride);
        for (int offset = 0; offset < file.length; offset += stride) {
            records.add(Arrays.copyOfRange(file, offset, offset + stride));
        }
        return records;
    }

    /**
     * Returns the same records in the opposite order, which is the shuffle these cases apply.
     *
     * <p>Assumptions: reversal is used rather than a randomised shuffle so that a failure is reproducible.
     * For files of two and four records it also moves every record, which a random permutation cannot be
     * relied on to do.
     *
     * @param file a whole number of records of the given stride; must not be {@code null}
     * @param stride how many bytes one record occupies; must be positive
     * @return the same records concatenated in reverse file order; never {@code null}
     */
    private static byte[] reverseRecords(byte[] file, int stride) {
        List<byte[]> records = new ArrayList<>(split(file, stride));
        ByteArrayOutputStream reversed = new ByteArrayOutputStream(file.length);
        for (byte[] record : records.reversed()) {
            reversed.write(record, 0, record.length);
        }
        return reversed.toByteArray();
    }

    /**
     * The greatest common divisor of two positive record strides.
     *
     * <p>Assumptions: this exists so the coincident-length case derives its record count from the two
     * strides rather than stating a hundred and three, which would go stale the moment either stride
     * changed and would then fabricate a file that no longer coincides.
     *
     * @param first the first stride, which must be positive
     * @param second the second stride, which must be positive
     * @return the greatest value dividing both
     */
    private static int gcd(int first, int second) {
        int larger = first;
        int smaller = second;
        while (smaller != 0) {
            int remainder = larger % smaller;
            larger = smaller;
            smaller = remainder;
        }
        return larger;
    }

    /**
     * The width of the packed parent-key prefix each prefixed record carries.
     *
     * @return the prefixed stride less the segment length, which is the prefix
     */
    private static int prefixWidth() {
        return PendingAuthDetailMapper.unloadRecordLength()
                - PendingAuthDetailMapper.segmentLength();
    }

    /**
     * The messages the exporter's logger emitted during this case, in emission order.
     *
     * @return each captured event's formatted message; never {@code null}
     */
    private List<String> capturedMessages() {
        return this.captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Reads one committed fixture from the class path.
     *
     * @param name the fixture's file name within the fixtures directory; must not be {@code null}
     * @return the fixture's bytes; never {@code null}
     * @throws IllegalStateException if the fixture is absent from the class path
     * @throws UncheckedIOException if the fixture cannot be read
     */
    private static byte[] bytes(String name) {
        try (InputStream stream = UnloadServiceTest.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IllegalStateException("fixture " + ROOT + name + " is not on the class path");
            }
            return stream.readAllBytes();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("fixture " + ROOT + name + " could not be read", unreadable);
        }
    }

    /**
     * The two files one export wrote.
     *
     * @param rootFile every byte written to the summary output
     * @param childFile every byte written to the authorization output
     */
    private record Extract(byte[] rootFile, byte[] childFile) {
    }

    /**
     * What a load reported together with the rows its doubles accepted.
     *
     * @param outcome the counts the loader itself returned
     * @param roots the summary rows accepted, in acceptance order
     * @param children the authorization rows accepted, in acceptance order
     */
    private record Restored(LoadService.LoadOutcome outcome, List<PendingAuthSummary> roots,
            List<PendingAuthDetail> children) {
    }

    /**
     * A stream that refuses every write, standing for an output that cannot be written.
     *
     * <p>Assumptions: a refusing stream is used rather than a closed one, because a closed
     * {@code ByteArrayOutputStream} still accepts writes -- so a case built on one would assert nothing.
     * The stream names itself in its refusal so that a case can assert WHICH output failed, which is what
     * the misattributed reference diagnostic got wrong.
     */
    private static final class RefusingStream extends OutputStream {

        /** How this stream identifies itself in the refusal it raises. */
        private final String identity;

        /**
         * Builds a stream that refuses every write, identifying itself by the name given.
         *
         * @param identity how the refusal should name this output; must not be {@code null}
         */
        RefusingStream(String identity) {
            this.identity = identity;
        }

        /**
         * Refuses one byte.
         *
         * @param oneByte the byte that will not be written
         * @throws IOException always, standing for an output that cannot be written
         */
        @Override
        public void write(int oneByte) throws IOException {
            throw new IOException("the " + this.identity + " refuses every write");
        }

        /**
         * Refuses a whole record.
         *
         * @param record the bytes that will not be written; never {@code null}
         * @param offset where in {@code record} the write would start
         * @param length how many bytes the write would carry
         * @throws IOException always, standing for an output that cannot be written
         */
        @Override
        public void write(byte[] record, int offset, int length) throws IOException {
            throw new IOException("the " + this.identity + " refuses every write");
        }
    }
}
