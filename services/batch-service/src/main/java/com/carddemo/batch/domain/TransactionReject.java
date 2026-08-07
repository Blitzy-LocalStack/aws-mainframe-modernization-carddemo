package com.carddemo.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps one rejected daily transaction onto {@code ledger.transaction_rejects} as the three columns
 * the 430-byte reject contract decomposes into.
 *
 * <p>A row is one transaction that failed posting validation. It is written by the migrated posting
 * job on the branch {@code app/cbl/CBTRN02C.cbl:213-215} takes when the validation reason is not
 * zero, and it is the only mapping in this package that this module inserts into a schema it does
 * not own. The committed expectation files under {@code tests/golden/posting} compare the rendered
 * form of these rows byte for byte, which is why every width, type and padding decision below is
 * derived from the baseline rather than chosen.</p>
 *
 * <h2>The 430-byte contract, and where each of the three parts comes from</h2>
 *
 * <p>The width is 430 and it is corroborated three independent ways, which matters because a reject
 * stream written at the wrong width is a file that still opens and still reads.</p>
 *
 * <table border="1">
 *   <caption>Derivation of the 430-byte reject record from three independent baseline views</caption>
 *   <tr><th>View</th><th>Where</th><th>Composition</th></tr>
 *   <tr><td>File description</td><td>{@code app/cbl/CBTRN02C.cbl:83-84}</td>
 *       <td>{@code FD-REJECT-RECORD PIC X(350)} + {@code FD-VALIDATION-TRAILER PIC X(80)}</td></tr>
 *   <tr><td>Working storage</td><td>{@code app/cbl/CBTRN02C.cbl:176-178}</td>
 *       <td>{@code REJECT-TRAN-DATA PIC X(350)} + {@code VALIDATION-TRAILER PIC X(80)}</td></tr>
 *   <tr><td>Assembled trailer</td><td>{@code app/cbl/CBTRN02C.cbl:180-182}</td>
 *       <td>{@code WS-VALIDATION-FAIL-REASON PIC 9(04)} +
 *           {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}</td></tr>
 *   <tr><td>Dataset allocation</td><td>{@code app/jcl/POSTTRAN.jcl:36}</td>
 *       <td>{@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)}</td></tr>
 * </table>
 *
 * <p>Assumptions: the two record views disagree in granularity and the finer one is the one this
 * mapping follows. The file description sees the trailer as a single opaque {@code X(80)} field,
 * while working storage declares the same 80 bytes as a separate group of a four-digit reason and a
 * 76-character description; 4 plus 76 is 80, so the views agree on width and differ only in
 * structure. Note that the finer group is a distinct {@code 01} item rather than a
 * {@code REDEFINES}: {@code app/cbl/CBTRN02C.cbl:448} moves it wholesale into the record's opaque
 * trailer field, which is how the two views meet. Three columns are carried rather than one opaque
 * 80-character column because the reason code is the field every consumer filters on, and reading
 * it out of a character substring on every query would make an ordinary count a string operation
 * over the whole table.</p>
 *
 * <p>Assumptions: the record format is fixed rather than variable, so every emitted record is
 * exactly 430 characters and carries no length prefix. Two consequences follow and both belong to
 * the emitter rather than to this type: the four-digit reason is rendered zero-padded, so 100
 * becomes {@code 0100}, and the description is rendered blank-padded to its full 76 characters. The
 * committed expectation files confirm both -- each of the four reject expectations under
 * {@code tests/golden/posting} is a single 430-character line whose characters 350 to 353 are a
 * zero-padded code and whose characters 354 to 429 are the message followed by blanks.</p>
 *
 * <h2>The reason codes this module can persist</h2>
 *
 * <table border="1">
 *   <caption>The four reachable reason codes, the line that sets each, and its verbatim
 *   text</caption>
 *   <tr><th>Code</th><th>Condition</th><th>Set at</th><th>Text, verbatim</th></tr>
 *   <tr><td>100</td><td>Card number absent from the cross-reference</td>
 *       <td>{@code :385-387}</td><td>{@code INVALID CARD NUMBER FOUND}</td></tr>
 *   <tr><td>101</td><td>Account record not found</td>
 *       <td>{@code :397-399}</td><td>{@code ACCOUNT RECORD NOT FOUND}</td></tr>
 *   <tr><td>102</td><td>Transaction would exceed the credit limit</td>
 *       <td>{@code :410-412}</td><td>{@code OVERLIMIT TRANSACTION}</td></tr>
 *   <tr><td>103</td><td>Transaction received after account expiration</td>
 *       <td>{@code :417-419}</td><td>{@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION}</td></tr>
 * </table>
 *
 * <p>All four line references are to {@code app/cbl/CBTRN02C.cbl}. The three control-flow facts a
 * faithful translation depends on -- the short circuit that makes 100 and 101 mutually exclusive,
 * the overwrite that makes 103 beat 102, and the two opposite boundary senses -- are recorded on
 * {@link #getReasonCode()}, because that is where a reader looking at a stored value will ask about
 * them.</p>
 *
 * <h2>What this type is not</h2>
 *
 * <p>Assumptions: the write path is split, and this type owns only the middle of it. In the
 * baseline, {@code app/cbl/CBTRN02C.cbl:446-465} does the whole job in one paragraph: {@code :447}
 * copies the daily record, {@code :448} moves the assembled trailer over it, and {@code :451}
 * writes the 430 bytes. In the target that sequence is three components -- the validation service
 * decides the code and the description, the job constructs this row, and the fixed-width emitter in
 * {@code com.carddemo.common.codec.FixedWidthCodec} renders the 350-character image followed by the
 * zero-padded code and the blank-padded description. The rendering is deliberately absent from this
 * type, and it is absent from {@link #toString()} in particular; the reasoning is recorded there.</p>
 *
 * <p>Assumptions: the reject count is not a column here and is not derived from this type. The
 * baseline keeps it in {@code WS-REJECT-COUNT PIC 9(09)} at {@code app/cbl/CBTRN02C.cbl:186},
 * increments it at {@code :214} immediately before writing the row at {@code :215}, and at
 * {@code :229-230} turns a non-zero count into a process return code of 4. The migrated job counts
 * what it writes in exactly that place and records the outcome on {@link BatchRun}, whose return
 * code carries the soft-warning tier. The existence of even one of these rows in a run is therefore
 * what makes that run's return code 4 rather than 0, and {@code app/jcl/TRANBKP.jcl:51} carries the
 * {@code COND=(4,LT)} skip predicate that inverts to the orchestration run predicate
 * {@code rc <= 4}. A count column here would be a second place for that number to live and a second
 * place for it to be wrong.</p>
 *
 * <p>Assumptions: a row carries no scope of its own beyond the three contract columns and the
 * ordinal. There is no run identifier, no timestamp, no card number and no account identifier, even
 * though each would be convenient, because the table belongs to another service and a column added
 * here that the owning migration does not declare would fail the start-up assertion rather than
 * work. Per-run scoping is carried by the dataset generation the job writes -- the generation-group
 * analogue, allocated as {@code AWS.M2.CARDDEMO.DALYREJS(+1)} at
 * {@code app/jcl/POSTTRAN.jcl:34-38} and defined at {@code LIMIT(5) SCRATCH} by
 * {@code app/jcl/DALYREJS.jcl:24-28} -- and by the {@code batch.batch_run} ledger.</p>
 */
// WHAT: the three columns this type maps reconstitute a 430-byte record as 350 characters of
//       verbatim rejected transaction, then a four-digit reason code, then a 76-character
//       description. The class Javadoc above derives that width three independent ways.
// WHAT: the reason-code domain this module can persist is exactly {100, 101, 102, 103}. A fifth
//       value, 109, is assigned by the baseline at app/cbl/CBTRN02C.cbl:556 on a path that cannot
//       reach the write, so it is not part of the domain; the analysis is on getReasonCode().
// WHAT: reason 101 and the unreachable 109 carry byte-identical text, ACCOUNT RECORD NOT FOUND, at
//       app/cbl/CBTRN02C.cbl:398 and :557 respectively. The description therefore does not identify
//       the code, which is a further reason the code is its own queryable column.
@Entity
// WHY : Alternatives Considered: reusing transaction-service's mapping of this same table instead of
//       declaring a local one. Rejected on three independent grounds. The migration plan forbids a
//       cross-service dependency on another context's domain package, and the shared architecture
//       test enforces that at build time rather than by convention, so the import would fail the
//       build. A compile-time dependency between two independently deployable services would also
//       reintroduce exactly the coupling a bounded context exists to remove: this module could then
//       not be released without agreeing a version with the owning service. And common-lib cannot
//       hold the type either, because it ships no persistence provider by design. The cost of a
//       local mapping is that two types describe one table and can drift; that cost is paid down by
//       the start-up assertion, which fails loudly against the owning migration rather than quietly.
// WHY : Assumptions: the baseline writes each of these records exactly once and never rewrites one,
//       so the type is immutable rather than merely lacking mutators. The evidence is the file
//       itself: app/cbl/CBTRN02C.cbl:46-49 selects the reject stream as ORGANIZATION IS SEQUENTIAL
//       with no record key, :293 opens it OUTPUT, and :451 is the only statement that writes it --
//       there is no REWRITE and no DELETE against it anywhere in the program. Declaring the type
//       immutable makes that structural: the provider excludes it from dirty checking, so a member
//       mutated inside a managed context produces no update statement at all. Inserts and deletes
//       remain available, which is what this module needs and all it needs.
// WHY : Assumptions: this annotation is read here for a different reason than on the sibling feed
//       mapping, and the two must not be read as the same claim. DailyTransaction is immutable
//       because this module never writes that table at all; this type is immutable because this
//       module writes each row once. Same annotation, opposite direction of the same boundary.
// WHY : Trade-offs: the annotation is provider-specific rather than portable, accepted because
//       every module under services/ runs the same provider through Spring Data JPA and no second
//       provider is in scope. Marking each column non-updatable instead would be portable but has
//       to be repeated per column, so a column added later would be writable by default and the
//       guarantee would decay by omission; the type-level form cannot be partially applied.
@Immutable
// WHY : Alternatives Considered: leaving the table unqualified and letting the pinned connection
//       search path resolve it. Declined because this module spans four schemas at three different
//       grant levels, so one search path cannot express which level applies to which access, and
//       this particular table is one the module writes under a narrowly-scoped cross-schema grant
//       rather than one it owns. Naming the schema on the annotation puts that boundary where a
//       reader of the entity finds it instead of in the connection configuration.
// WHY : Assumptions: this mapping is DDL-passive and declares no index, no unique constraint, no
//       column definition and no check. The table, its columns and its primary key are created by
//       the owning service's migration at
//       services/transaction-service/src/main/resources/db/migration/V1__ledger.sql:550-761, and
//       the provider is never permitted to emit DDL in this module -- its schema setting is at most
//       an assertion against the existing shape. BatchRun is the one mapping in this package whose
//       table is genuinely owned here and therefore the only one that may declare a constraint.
// WHY : Assumptions: a uniqueness assertion here would be wrong on the merits as well as out of
//       bounds. That migration states at :665-678 that no unique constraint exists over the record
//       image or over any combination of the three contract columns, because the source asserts
//       uniqueness over nothing: app/cbl/CBTRN02C.cbl:46-49 declares the stream sequential with no
//       record key and app/jcl/POSTTRAN.jcl:36 gives it a fixed-length format, so it is appended to
//       and never keyed into. The same record rejected in two runs is two legitimate entries, and
//       app/jcl/DALYREJS.jcl:24-28 retains five generations of exactly that.
// WHY : Alternatives Considered: an association to the daily transaction this row was rejected
//       from, mapped as a many-to-one with a join column. Rejected because the baseline copies the
//       record rather than referencing it -- app/cbl/CBTRN02C.cbl:447 is a single wholesale move of
//       the entire 350-byte area -- so the stream is deliberately self-contained and can be
//       replayed or diffed without joining anything. A foreign key would additionally make a reject
//       undeletable independently of the feed row that caused it, which inverts the retention
//       relationship: the generation group keeps rejects for five generations regardless of what
//       happens to the feed.
@Table(name = "transaction_rejects", schema = "ledger")
public class TransactionReject {

    /**
     * Reason code recorded when the card number is absent from the cross-reference.
     *
     * <p>Set by {@code app/cbl/CBTRN02C.cbl:385} with the text at {@code :386}.</p>
     */
    public static final short REASON_CODE_INVALID_CARD_NUMBER = 100;

    /**
     * Reason code recorded when the account the cross-reference names does not exist.
     *
     * <p>Set by {@code app/cbl/CBTRN02C.cbl:397} with the text at {@code :398}.</p>
     */
    public static final short REASON_CODE_ACCOUNT_NOT_FOUND = 101;

    /**
     * Reason code recorded when the projected cycle balance exceeds the credit limit.
     *
     * <p>Set by {@code app/cbl/CBTRN02C.cbl:410} with the text at {@code :411}.</p>
     */
    public static final short REASON_CODE_OVERLIMIT = 102;

    /**
     * Reason code recorded when the transaction date falls after the account expiration date.
     *
     * <p>Set by {@code app/cbl/CBTRN02C.cbl:417} with the text at {@code :418}.</p>
     */
    public static final short REASON_CODE_AFTER_EXPIRATION = 103;

    // WHY : Assumptions: the four texts below are carried character for character from the lines
    //       cited on each, under the migration plan's transformation rule that user-visible strings
    //       are verbatim. Capitalisation and internal spacing are part of the value: the committed
    //       expectation files compare characters 354 to 429 of each record against these exact
    //       strings, so re-casing one, correcting its wording, or trimming it changes compared bytes
    //       rather than merely changing prose. No message is synthesised for a code the baseline
    //       does not emit, and none is reworded to read better.
    // WHY : Alternatives Considered: leaving the literals at their point of use in the validation
    //       service, or pairing each code with its text in a nested enum. Keeping them here was
    //       chosen because the code and the text are written as a pair at four places in the
    //       baseline and are stored as a pair in two columns of one row, so the type that persists
    //       the pair is the one place a reader looks for it and the one place a change has to be
    //       made. A nested enum would express the pairing more tightly, but the persisted column is
    //       a small integer that the owning migration declares, so the enum would need an explicit
    //       conversion at every mapping site -- the annotation-driven enum mappings are prohibited
    //       here, ordinal position is not the reason code, and a converter would add a second
    //       mapping mechanism to a type whose whole point is to describe an existing shape plainly.

    /**
     * Verbatim description stored alongside {@link #REASON_CODE_INVALID_CARD_NUMBER}.
     */
    public static final String REASON_DESC_INVALID_CARD_NUMBER = "INVALID CARD NUMBER FOUND";

    /**
     * Verbatim description stored alongside {@link #REASON_CODE_ACCOUNT_NOT_FOUND}.
     */
    public static final String REASON_DESC_ACCOUNT_NOT_FOUND = "ACCOUNT RECORD NOT FOUND";

    /**
     * Verbatim description stored alongside {@link #REASON_CODE_OVERLIMIT}.
     */
    public static final String REASON_DESC_OVERLIMIT = "OVERLIMIT TRANSACTION";

    /**
     * Verbatim description stored alongside {@link #REASON_CODE_AFTER_EXPIRATION}.
     */
    public static final String REASON_DESC_AFTER_EXPIRATION =
            "TRANSACTION RECEIVED AFTER ACCT EXPIRATION";

    // WHY : Assumptions: the bounds below are the declared widths of the baseline fields and of the
    //       columns the owning migration creates, held once so that a mapping annotation and a
    //       constructor check can never disagree about the same number. Three baseline fields yield
    //       four bounds because the numeric one is bounded at both ends: the record image is
    //       PIC X(350) at app/cbl/CBTRN02C.cbl:177, the description is PIC X(76) at :182, and the
    //       reason is PIC 9(04) at :181 -- four digits and unsigned, so 9999 is its inclusive
    //       ceiling and 0 its inclusive floor. A five-digit value could not have come from that
    //       field, and it would render as five characters where the contract reserves four,
    //       displacing every byte that follows.
    // WHY : Alternatives Considered: trailing the qualifier instead, so that the two bounds would
    //       read as a reason-code maximum and a reason-description maximum length. That reads more
    //       naturally in isolation but was rejected, because either name then shares its prefix with
    //       the public catalogue above, and that prefix is load-bearing: it is what lets an audit
    //       enumerate the reason-code domain or the message set by reflecting over a prefix and
    //       assert that it holds exactly four members. With a bound sharing the prefix, such an
    //       audit counts five and either fails on a correct type or, worse, is relaxed to pass and
    //       then stops noticing a genuinely added fifth code. Leading with the qualifier keeps the
    //       two prefixes meaning exactly one thing each.
    private static final int RAW_RECORD_LENGTH = 350;
    private static final int MAX_REASON_DESC_LENGTH = 76;
    private static final short MAX_REASON_CODE = 9999;
    private static final short MIN_REASON_CODE = 0;

    /**
     * Ordinal of this reject event within the stream, assigned by the database on insert.
     *
     * <p>The value corresponds to no field of the 430-byte layout. It identifies the reject event
     * rather than the record that provoked it, which is the distinction that lets two legitimate
     * rejections of one identical record be told apart.</p>
     */
    // WHY : Alternatives Considered: a natural key over the three contract columns. Rejected because
    //       they are not unique and are not meant to be: the source is a sequential stream with no
    //       key at all -- app/jcl/POSTTRAN.jcl:34-38 allocates it as a new fixed-length dataset
    //       rather than as a keyed cluster -- and the same input record rejected in two runs is two
    //       entries carrying the same code and the same description. A key over those columns would
    //       refuse the second of two identical rejects, turning a faithful append into a constraint
    //       violation. Including the record image in a key was rejected for the same duplication
    //       reason and additionally because a 350-character key column indexes poorly.
    // WHY : Alternatives Considered: sequence or automatic generation instead of identity. Both
    //       rejected because both imply a generator object named by the provider rather than by the
    //       owning migration, which declares this column as an identity column at
    //       services/transaction-service/src/main/resources/db/migration/V1__ledger.sql:616. A
    //       provider-named sequence would be a second object to keep in step and would fail the
    //       start-up assertion against a migration that declares no such sequence.
    // WHY : Assumptions: identity generation is the one generation strategy compatible with this
    //       package's DDL-passive boundary, and it is compatible because it delegates rather than
    //       creates -- it asks the database for the value the existing identity column already
    //       supplies and needs no generator declaration of its own. The reason this mapping declares
    //       a strategy at all where the sibling feed mapping deliberately declares none is the
    //       direction of access: this module writes this table, so a strategy describes an insert
    //       that genuinely happens here, whereas on the read-only feed it would describe an insert
    //       this module cannot perform.
    // WHY : Assumptions: the column is declared GENERATED BY DEFAULT rather than GENERATED ALWAYS at
    //       that same migration line, so a loader replaying a captured stream or restaging one
    //       dataset generation can supply the original ordinal explicitly. Identity generation here
    //       does not remove that possibility; it only means this module never exercises it.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reject_seq", updatable = false)
    private Long rejectSeq;

    /**
     * The rejected daily transaction retained whole, as an undivided 350-character image.
     *
     * <p>This is the first 350 characters of the 430-byte record and it is stored exactly as it
     * arrived, never parsed into fields.</p>
     */
    // WHY : Assumptions: the provenance is a single statement. app/cbl/CBTRN02C.cbl:447 is
    //       MOVE DALYTRAN-RECORD TO REJECT-TRAN-DATA, one wholesale copy of the entire 350-byte
    //       daily-transaction area with no field-level handling of any kind. This column is that
    //       image. Its content duplicating a feed row field for field is intended rather than
    //       redundant: the reject stream is a self-contained artifact that can be replayed or
    //       compared without joining anything, which is exactly why the baseline copies instead of
    //       referencing. The layout it holds is 01 DALYTRAN-RECORD at app/cpy/CVTRA06Y.cpy:4-18, and
    //       the widths there sum to 350 -- 16, 2, 4, 10, 100, an 11-character zoned amount, 9, 50,
    //       50, 10, 16, 26, 26 and a 20-character trailing FILLER.
    // WHY : Alternatives Considered: a variable-width column, which is what a descriptive string
    //       would normally get and what the sibling description column below does get. Rejected
    //       here, and this is the most consequential type decision in the type. The fixed-width form
    //       blank-pads to the declared width and preserves that width on retrieval, so a reader gets
    //       350 characters back whatever a writer supplied; a variable-width column would faithfully
    //       store a short value and offer no such guarantee. Because the comparison is byte-exact at
    //       430, a short image does not fail where it occurs -- it displaces the trailer and every
    //       character after it, so the comparison fails at position 350 and at every position
    //       following, and the reported difference points nowhere near the cause. The fixed-width
    //       type makes the width structural instead of conventional, and the owning migration
    //       declares it for exactly this reason at V1__ledger.sql:618-670.
    // WHY : Trade-offs: fixed-width comparison semantics ignore trailing blanks, so a query
    //       comparing a 350-character value against its trimmed form reports them equal, and the
    //       SQL length function reports the trimmed figure while the octet-length function reports
    //       350. A test that asserted only content equality would therefore pass against a short
    //       store, which is why the round-trip test asserts the retrieved length explicitly rather
    //       than relying on equality alone.
    // WHY : Alternatives Considered: a binary column holding raw bytes. Rejected because the record
    //       reaching this point is already character data: the extract package decodes the source
    //       encoding per fixed-width field rather than per record, in
    //       data-migration/src/carddemo_migration/copybook/ebcdic_codec.py, precisely so that sign
    //       bytes and packed nibbles never pass through a text decoder. Storing bytes here would
    //       reintroduce an encoding boundary that has already been resolved upstream, and it would
    //       make the parity comparison operate on a representation the committed expectation files
    //       do not use -- those files are text, one 430-character line per reject.
    // WHY : Assumptions: the value is inert. It is never trimmed, never re-parsed into its fields,
    //       never re-serialised through a formatter and never masked. It does contain a primary
    //       account number, at zero-based offset 262 from app/cpy/CVTRA06Y.cpy:15, and masking it
    //       would change compared bytes rather than merely obscuring a number. The migration's
    //       masking discipline applies where a value leaves the system to a caller, and this module
    //       publishes no such surface for this record; what closes the remaining log exposure is the
    //       rendering decision at the end of this type.
    // WHY : Refactoring Rationale: the column is NOT NULL, where an earlier revision of this member
    //       left it nullable. A reject row EXISTS because a record was rejected, and the single
    //       statement cited above copies the whole 350-byte area unconditionally before the write at
    //       app/cbl/CBTRN02C.cbl:448, so the reference program has no path that appends a reject
    //       carrying no image. A null image is an entry recording that something was rejected while
    //       discarding the only evidence of what -- and the 430-byte parity comparison cannot be
    //       performed against it at all. The owning migration declares the same NOT NULL.
    @JdbcTypeCode(SqlTypes.CHAR)
    // WHY : Refactoring Rationale: nullable = false was ADDED to this member and to the two
    //       below, matching the NOT NULL the columns now carry. app/cbl/CBTRN02C.cbl L446-L451
    //       writes the reject record by moving two WHOLE group items into it, and a group move
    //       transfers the full declared width every time -- so there is no branch on which any of
    //       the three components is absent and no width at which one is short.
    // WHY : Trade-offs: a null in any of the three would make the 430-byte record
    //       UNRECONSTRUCTABLE rather than merely incomplete. A reconstruction concatenates the
    //       padded 350-character image, the four-digit code and the 76-character description; a null
    //       has no width, so every field after the gap would sit at the wrong offset and the record
    //       would parse cleanly into different data. Declaring the constraint on the member as well
    //       as on the column is what lets the provider refuse the instance before a flush, naming
    //       the member rather than reporting a constraint violation from the driver.
    @Column(name = "raw_record", nullable = false, length = RAW_RECORD_LENGTH, updatable = false)
    private String rawRecord;

    /**
     * Numeric reason this record was rejected.
     *
     * <p>Occupies characters 350 to 353 of the 430-byte record, rendered zero-padded to four
     * characters by the emitter.</p>
     */
    // WHY : Assumptions: the source field is WS-VALIDATION-FAIL-REASON PIC 9(04) at
    //       app/cbl/CBTRN02C.cbl:181 -- numeric, unsigned, four digits, so its domain is 0 through
    //       9999. A small integer is the narrowest exact integer type covering that domain, so a
    //       wider one would reserve bytes no value can use. A four-character column was rejected
    //       because the field is numeric and the value is compared and aggregated rather than read:
    //       :229 tests the reject count to decide the process return code, and an operator
    //       diagnosing a run groups by this value.
    // WHY : Assumptions: the type change from four zoned digits to an integer is only safe because
    //       the rendering is restored on the way out. A PIC 9(04) field occupies four characters and
    //       the value 100 renders as 0100 -- zero-padded, not blank-padded and not left-aligned --
    //       which the committed expectations confirm at characters 350 to 353 of each reject line.
    //       That padding belongs to the fixed-width emitter in com.carddemo.common.codec; this
    //       column stores the number. Rendering it as anything but four zero-padded characters
    //       displaces the 76 characters that follow.
    // WHY : Refactoring Rationale: this column is now NOT NULL at V1__ledger.sql:697 and the
    //       migration now bounds it to the picture's own domain with
    //       CHECK (reason_code BETWEEN 0 AND 9999) at its L757. An earlier revision declared
    //       neither, and read the PIC 9(04) domain only as the reason a small integer is WIDE
    //       ENOUGH -- while leaving that type's whole 32767 range admissible, negative values
    //       included, which an unsigned picture cannot express. The same reading that makes 9999 the
    //       sufficiency argument makes it the BOUND. Each reject site moves a code and its text in
    //       one pair of statements, so a null code is a state the program never produces, and it
    //       would break the reject COUNT that :229 turns into the return code because a null neither
    //       equals nor differs from any code a filter names.
    // WHY : Assumptions: the WRAPPER type is retained even though the column is now NOT NULL, and
    //       the reason the change makes DECISIVE rather than weaker is that ZERO is a legitimate
    //       value of this domain: PIC 9(04) at CBTRN02C L181 admits 0000, and the constraint above
    //       is inclusive at that end. A primitive member left unassigned would therefore read back
    //       as a real reason code rather than as an unpopulated one. The provider instantiates
    //       through the no-argument constructor below and assigns the members afterwards, so an
    //       instance does exist in that intermediate state, and a null there fails loudly where a
    //       zero would be silently plausible. Nullability of the MEMBER and nullability of the
    //       COLUMN are different questions, and only the second is what NOT NULL answers.
    @Column(name = "reason_code", nullable = false)
    private Short reasonCode;

    /**
     * Verbatim description of the reason this record was rejected.
     *
     * <p>Occupies characters 354 to 429 of the 430-byte record, rendered blank-padded to 76
     * characters by the emitter.</p>
     */
    // WHY : Assumptions: the source field is WS-VALIDATION-FAIL-REASON-DESC PIC X(76) at
    //       app/cbl/CBTRN02C.cbl:182, and the declared width is carried as the contract rather than
    //       trimmed to the longest observed message. The longest of the four is 103's at 42
    //       characters, so all four fit with room to spare.
    // WHY : Alternatives Considered: the fixed-width form used for the record image above. Rejected
    //       here, and the two differing choices are deliberate rather than inconsistent. This
    //       column's logical value is the message text, drawn from a small closed catalogue of four
    //       literals declared at the head of this type, and its padding to 76 is purely a rendering
    //       concern the emitter applies deterministically. The record image, by contrast, IS a
    //       fixed-width image whose padding is part of its content, because the padding is bytes the
    //       source record actually carried. The owning migration declares this column variable-width
    //       at V1__ledger.sql:699-719 and the image fixed-width at :670 for that distinction.
    // WHY : Refactoring Rationale: the column is NOT NULL, on the same reading as the two columns
    //       above. Every reject site moves a reason code and its verbatim text in the same pair of
    //       statements, so a row carrying a code and no text is a state the reference program cannot
    //       reach. The texts are user-visible strings carried across character for character under
    //       transformation rule T8, and a null one would silently drop the half of the 430-byte
    //       trailer an operator actually reads.
    @Column(name = "reason_desc", nullable = false, length = MAX_REASON_DESC_LENGTH)
    private String reasonDesc;

    /**
     * Creates an unpopulated instance for the persistence provider to hydrate.
     */
    // WHY : Assumptions: the provider requires a non-private no-argument constructor so that it can
    //       instantiate this type reflectively when materialising a row or building a lazy proxy, and
    //       it assigns the members afterwards by field access rather than through accessors, which is
    //       what placing the identity annotation on a field selects. The body is empty by design and
    //       not unfinished: anything initialised here would be overwritten on every load and would
    //       mask an absent column rather than surface it.
    // WHY : Alternatives Considered: private visibility, relying on the provider's reflective access.
    //       Rejected because a subclass generated for a lazy proxy must be able to invoke it, so
    //       protected is the narrowest visibility that works. Public was also rejected: it would let
    //       application code create a row with no image and no reason, which is precisely the state
    //       the argument constructor below exists to prevent.
    protected TransactionReject() {
        // WHY : Assumptions: intentionally empty, because the provider populates every mapped member
        //       immediately after reflective creation.
    }

    /**
     * Creates a reject row from a rejected record image and the trailer that classifies it.
     *
     * <p>The three arguments are the three parts of the 430-byte record, in the order the baseline
     * fills them: the image that {@code app/cbl/CBTRN02C.cbl:447} copies, then the reason code and
     * description that {@code :448} moves over it as the trailer.</p>
     *
     * @param rawRecord the rejected daily transaction as an undivided image of exactly 350
     *     characters, retained verbatim and never parsed by this type
     * @param reasonCode the four-digit reason the record was rejected for, within the inclusive
     *     range 0 to 9999 that the source picture admits
     * @param reasonDesc the reason description exactly as the baseline writes it, at most 76
     *     characters and carried across character for character
     * @throws NullPointerException if any of the three arguments is null
     * @throws IllegalArgumentException if the image is not exactly 350 characters, the description
     *     exceeds 76 characters, or the reason code falls outside the inclusive range 0 to 9999
     */
    // WHY : Assumptions: the MAPPED IDENTITY of this row is `rejectSeq` -- the member carrying the
    //       @Id annotation and the generated `reject_seq` identity column -- and it is deliberately
    //       NOT a parameter of this constructor, because the database assigns it. None of the three
    //       arguments below is or contributes to that identity: `rawRecord` is the 350-character
    //       payload image, and the two trailer members classify it. Accepting an ordinal would let a
    //       caller overwrite an append position it does not own, and on this module's write path
    //       there is no legitimate value to supply.
    // WHY : Assumptions: sequence identity is kept SEPARATE from the fixed-width payload content, and
    //       the separation is what makes duplicate rejects representable. The source asserts
    //       uniqueness over nothing -- app/cbl/CBTRN02C.cbl L46-L47 selects DALYREJS as ORGANIZATION
    //       IS SEQUENTIAL with no RECORD KEY, and app/jcl/POSTTRAN.jcl L36 gives it RECFM=F, a flat
    //       stream appended to and never keyed into -- so two identical 430-byte records are both
    //       legitimate. Deriving identity from the image, or from the image plus its trailer, would
    //       collapse those two occurrences into one row and undercount the reject total that
    //       CBTRN02C L229-L230 turns into the job's return code. A generated ordinal distinguishes
    //       them while leaving every payload byte free to repeat.
    // WHY : Assumptions: nothing is derived here. No generation, no run identifier and no timestamp
    //       is minted, because the 430-byte record carries none of the three and the generation that
    //       does exist is a property of the dataset rather than of a row.
    // WHY : Alternatives Considered: performing no validation at all, which is what the owning
    //       service's mapping of this table does and which is correct there. Its callers replay a
    //       captured stream, so it must be able to represent whatever a stored row holds, including a
    //       row with no reason. This module is the write side, where the three parts have just been
    //       computed, so a null or a mis-width here is a defect in this module rather than a fact
    //       about stored data -- and it is a defect whose only other symptom is a byte-exact
    //       comparison failing at position 350 and every position after it. Checking at construction
    //       reports it naming the contract, before a flush or an emit can obscure it.
    // WHY : Trade-offs: only the structural properties of the three values are checked -- presence,
    //       width and the numeric range the source picture admits. Their CONTENT is deliberately not
    //       validated, because validating it would refuse exactly the records this table exists to
    //       keep: a reason 100 image carries a card number that resolved to nothing, and a reason 102
    //       image can carry an amount no validated column would accept. The reason-code check is a
    //       width check in numeric form for the same reason: it admits the whole four-digit domain
    //       rather than only the four codes this module emits, because narrowing it to those four
    //       would put a policy that belongs to the validation service into the type that merely
    //       stores its outcome.
    public TransactionReject(String rawRecord, Short reasonCode, String reasonDesc) {
        String checkedRawRecord = Objects.requireNonNull(rawRecord, "rawRecord must not be null");
        Short checkedReasonCode = Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        String checkedReasonDesc = Objects.requireNonNull(reasonDesc, "reasonDesc must not be null");

        // WHY : Assumptions: the image is checked for an exact width rather than a maximum, unlike
        //       the description below, because it is the one value whose padding is content. The
        //       baseline moves a fixed 350-byte area into another fixed 350-byte area, so a
        //       legitimate value is never short. The fixed-width column would pad a short value out
        //       on storage, but the emitter renders from this instance rather than from a re-read
        //       row, so padding at the column would arrive after the record had already been written
        //       at the wrong width.
        if (checkedRawRecord.length() != RAW_RECORD_LENGTH) {
            throw new IllegalArgumentException(
                    "rawRecord must be exactly " + RAW_RECORD_LENGTH + " characters");
        }
        // WHY : Assumptions: the description is checked for a maximum rather than an exact width
        //       because its four admitted values are 25, 24, 21 and 42 characters long and the
        //       emitter is what pads the stored text out to the declared 76.
        if (checkedReasonDesc.length() > MAX_REASON_DESC_LENGTH) {
            throw new IllegalArgumentException(
                    "reasonDesc must not exceed " + MAX_REASON_DESC_LENGTH + " characters");
        }
        if (checkedReasonCode < MIN_REASON_CODE || checkedReasonCode > MAX_REASON_CODE) {
            throw new IllegalArgumentException(
                    "reasonCode must be within " + MIN_REASON_CODE + " and " + MAX_REASON_CODE);
        }

        this.rawRecord = checkedRawRecord;
        this.reasonCode = checkedReasonCode;
        this.reasonDesc = checkedReasonDesc;
    }

    /**
     * Returns this reject event's ordinal within the stream.
     *
     * <p>Assumptions: the database assigns the value on insert, so it is null on an instance this
     * module has constructed and not yet flushed, and non-null on every instance a query returned.
     * That asymmetry is the ordinary contract of a generated identifier and is what
     * {@link #equals(Object)} is written around. The ordinal is an append position and carries no
     * business meaning: it is not a run identifier, not a transaction identifier and not a reason
     * code, and nothing in the 430-byte layout corresponds to it.</p>
     *
     * @return the ordinal assigned on insert, or null before this row is flushed
     */
    public Long getRejectSeq() {
        return rejectSeq;
    }

    /**
     * Returns the rejected daily transaction as its undivided 350-character image.
     *
     * <p>Assumptions: the caller receives the image exactly as stored, including its trailing
     * blanks, and is responsible for treating it as opaque. A caller needing one field of it reads a
     * substring at the offset the layout gives -- for a synthetic illustration, the card number
     * occupies 16 characters from zero-based offset 262 per {@code app/cpy/CVTRA06Y.cpy:15} -- and
     * accepts that the field it extracts may be exactly the malformed value that caused the
     * rejection.</p>
     *
     * @return the non-null 350-character image of the rejected record
     */
    public String getRawRecord() {
        return rawRecord;
    }

    /**
     * Returns the numeric reason this record was rejected.
     *
     * <p>Assumptions: the value this module can store is exactly one of 100, 101, 102 or 103, the
     * four codes declared at the head of this type. Three properties of how the baseline arrives at
     * that value are behavioural contract rather than implementation detail, and each is recorded
     * here because a reader holding a stored code is where the question arises.</p>
     *
     * <p>Assumptions: 100 and 101 are mutually exclusive, so a record can never carry both
     * conditions. {@code app/cbl/CBTRN02C.cbl:370-376} performs the cross-reference lookup at
     * {@code :371} and then guards on the reason still being zero at {@code :372} before performing
     * the account lookup at {@code :373}. A missing card number therefore prevents the account
     * lookup from running at all, and a translation that ran both lookups unconditionally would
     * store 101 where the baseline stores 100.</p>
     *
     * <p>Assumptions: 103 takes precedence over 102 when both conditions hold, and it does so by
     * overwriting rather than by ordering. {@code app/cbl/CBTRN02C.cbl:407-413} and {@code :414-420}
     * are two sequential tests at the same nesting level, and the second is not guarded by the
     * outcome of the first -- {@code :413} closes the over-limit test and {@code :414} opens the
     * expiration test independently, with no early exit between them. Each test carries its own
     * inner alternative branch, which is where its reject is assigned; what is absent is any guard
     * BETWEEN the two. So a transaction that is both over limit and past expiration has 102 assigned
     * at {@code :410} and then replaced by 103 at {@code :417}, with the description at {@code :411}
     * replaced by the one at {@code :418}. The last writer wins.</p>
     *
     * <p>Assumptions: the practical consequence is that a translation shaped as a chain of mutually
     * exclusive alternatives in declaration order stores 102 on exactly the inputs where both
     * conditions hold, and diverges from the baseline there and only there. A faithful translation
     * evaluates both conditions and lets the later one win, or equivalently tests expiration first.
     * This is a parity requirement and not a preference, and it needs its own test: none of the nine
     * committed fixture directories under {@code tests/golden/posting} exercises the case, because
     * each of the four reject fixtures exercises one reason in isolation.</p>
     *
     * <p>Assumptions: the two boundaries are opposite in sense, and both are written in the source
     * as the condition under which a transaction PASSES. {@code app/cbl/CBTRN02C.cbl:407} reads
     * {@code IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL} on the passing branch, so 102 is stored only when
     * the projected balance is STRICTLY GREATER than the limit and a projection landing exactly on
     * the limit posts; the projection is formed at {@code :403-405} from the cycle credit and debit
     * buckets plus the transaction amount, not from the current balance. {@code :414} reads
     * {@code IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10)} on the passing branch, so 103 is
     * stored only when the expiration date is STRICTLY EARLIER than the transaction date and an
     * expiration date equal to it posts; the comparison is lexical over the first ten characters of
     * the 26-character originating stamp, which is equivalent to a date comparison only because the
     * stored form is ISO-ordered. The two committed boundary fixtures,
     * {@code boundary_exact_limit} and {@code boundary_expiry_equal}, pin both senses by expecting
     * an empty reject stream.</p>
     *
     * <p>Assumptions: a fifth value exists in the baseline and is not part of this domain.
     * {@code app/cbl/CBTRN02C.cbl:555-558} assigns 109, with text byte-identical to 101's, inside
     * the paragraph that updates the account record -- a paragraph performed only from {@code :441},
     * on the posting path. The paragraph that writes this stream is performed only from
     * {@code :215}, on the branch taken when validation already failed, and those two branches are
     * the alternatives of one decision at {@code :211-216} while {@code :208} resets the reason
     * before the next record. Nothing therefore emits 109. The baseline assigns a reason code on a
     * path that cannot write it; this module implements the reachable domain, and the divergence is
     * registered in {@code docs/architecture/cobol-to-service-traceability.md}. It is stated here so
     * that a reader who finds the assignment in the source does not add a fifth code to the four
     * above.</p>
     *
     * @return the non-null four-digit reason code, one of 100, 101, 102 or 103 as written by this
     *     module
     */
    public Short getReasonCode() {
        return reasonCode;
    }

    /**
     * Returns the verbatim description of the reason this record was rejected.
     *
     * <p>Assumptions: the returned text is one of the four literals declared at the head of this
     * type, stored unpadded; the emitter is what widens it to 76 characters. Because 101's text and
     * the unreachable 109's are byte-identical, this value alone does not identify the code, and a
     * caller needing the reason reads {@link #getReasonCode()} instead of matching on text.</p>
     *
     * @return the non-null description as the baseline writes it, at most 76 characters
     */
    public String getReasonDesc() {
        return reasonDesc;
    }

    // WHY : Alternatives Considered: exposing mutators for the three contract members, which the
    //       owning service's mapping of this table does expose because its callers hydrate rows from
    //       a replayed stream. Rejected here because on this module's side a reject is an immutable
    //       historical fact: the baseline writes the record once at app/cbl/CBTRN02C.cbl:451 and the
    //       program contains no REWRITE against that file, so a mutator would be a path with no
    //       counterpart in the behaviour being migrated. The type-level immutability declared above
    //       makes the same guarantee reach the reflective and provider-driven paths that an absence
    //       of mutators does not.

    /**
     * Compares rows by the ordinal the database assigned, and by nothing else.
     *
     * @param other the Object to compare with this row
     * @return true when other is a TransactionReject whose ordinal is non-null and equal to this
     *     row's; otherwise false
     */
    // WHY : Alternatives Considered: equality over the three contract members, which is the form the
    //       sibling BatchRun uses. Rejected because the two tables differ in exactly the property
    //       that decides this: BatchRun carries a named unique constraint over its business pair, so
    //       memory and database agree on what one row is, whereas the owning migration for this
    //       table states at V1__ledger.sql:721-745 that no unique constraint exists over these
    //       columns and that duplicate images remain legitimate. Business-member equality here would
    //       collapse two distinct rejects of one identical record into a single element of a hashed
    //       collection, which is the very thing the ordinal was added to prevent.
    // WHY : Assumptions: the ordinal is null until the row is flushed, so two unflushed instances
    //       must not compare equal however identical their members -- otherwise a set of pending
    //       rejects would silently lose all but one. Returning false while the ordinal is absent is
    //       the deliberate handling of that state rather than an unconsidered null check, and it is
    //       why the sibling test asserts the unflushed case explicitly.
    // WHY : Assumptions: this refusal is what the constant hash below is chosen against, and the two
    //       together give the property a caller depends on: an instance placed in a hashed collection
    //       while its ordinal was absent stays findable once the flush assigns one, both by itself
    //       and by a distinct instance re-read on the same ordinal, because the bucket never moves
    //       and the comparison then succeeds on the assigned value. The accepted consequence is
    //       narrower than it looks -- while the ordinal is still absent an instance is findable only
    //       by itself, never by an equal-membered twin -- and that is the state the reject stream
    //       actually wants, since two unflushed rejects of one identical record are two entries.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionReject that)) {
            return false;
        }
        if (rejectSeq == null || that.getRejectSeq() == null) {
            return false;
        }
        return rejectSeq.equals(that.getRejectSeq());
    }

    /**
     * Answers one constant for every row of this type, so the value cannot move when the database
     * assigns the ordinal.
     *
     * @return the int hash of this class, the same value for every instance whatever its ordinal
     */
    // WHY : Assumptions: the ordinal is null until the row is flushed and non-null afterwards, so a
    //       hash derived from it would take one value before the insert and a different one after.
    //       The hashed collections in the JDK read the bucket once, at insertion, and never rehash
    //       an element the collection already holds, so an instance added while the ordinal was
    //       absent would sit in the bucket for the absent value and be unreachable from the bucket
    //       the assigned value now selects -- contains would answer false for an element the
    //       collection still contains, and remove would not remove it. A constant makes every
    //       instance select one bucket for its whole lifetime, which is the property that removes
    //       that failure altogether.
    // WHY : Alternatives Considered: Objects.hash(rejectSeq), which is the form that pairs most
    //       obviously with an equality over the ordinal. Rejected for the lifecycle reason above.
    //       Two further details make the rejection concrete rather than theoretical. Objects.hash of
    //       a single null argument answers 31 and not zero, because it hashes a one-element array
    //       whose seed is 1, so the pre-insert and post-insert values are two different non-zero
    //       numbers rather than a zero that a reader might expect to be treated specially. And this
    //       type is inserted per rejected record inside a chunk, so the pre-insert state is the
    //       normal state of an instance a caller holds rather than an edge case.
    // WHY : Trade-offs: every instance of this type shares one bucket, so a hashed collection over
    //       many rejects degrades to a linear scan through equals. That cost is accepted because the
    //       posting job holds rejects in an ordered collection while building a chunk and addresses
    //       stored ones by ordinal, so no large hashed collection of this type exists on any path;
    //       what it buys is that membership never depends on when an instance was hashed relative to
    //       its flush. The sibling DailyTransaction of this package answers a constant for the same
    //       reason, so the two feed-side mappings behave alike.
    @Override
    public int hashCode() {
        return TransactionReject.class.hashCode();
    }

    /**
     * Renders the ordinal and the reason for operational diagnosis.
     *
     * @return a String containing the ordinal, the reason code and the reason description, and
     *     deliberately not the record image
     */
    // WHY : Trade-offs: the record image is omitted entirely rather than abbreviated. It carries a
    //       primary account number at zero-based offset 262 per app/cpy/CVTRA06Y.cpy:15, and
    //       rendered output reaches log lines, so including it would put an unmasked card number
    //       into a log -- the one data exposure this module could plausibly create, given that it
    //       publishes no caller-facing surface at all. Omitting it also keeps each log line from
    //       growing by 350 characters. What is given up is the ability to see the rejected bytes in a
    //       log; they are in the row and in the emitted dataset generation, which are the places an
    //       operator is meant to read them from.
    // WHY : Trade-offs: this method is a diagnostic and is emphatically not the parity emitter. The
    //       committed expectations under tests/golden/posting are compared against a 430-character
    //       fixed-width rendering the job produces through com.carddemo.common.codec.FixedWidthCodec.
    //       Routing parity output through a method whose whole purpose is to read well in a log would
    //       break the comparison the first time someone improved a log line, and the break would be
    //       silent because nothing about this method's signature says a byte contract depends on it.
    //       No caller may parse this form or depend on its field order.
    @Override
    public String toString() {
        return "TransactionReject{"
                + "rejectSeq=" + rejectSeq
                + ", reasonCode=" + reasonCode
                + ", reasonDesc='" + reasonDesc + '\''
                + '}';
    }
}
