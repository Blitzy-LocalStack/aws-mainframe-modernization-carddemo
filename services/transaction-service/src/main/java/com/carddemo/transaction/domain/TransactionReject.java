package com.carddemo.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of the reject stream the posting program writes for a daily transaction that failed
 * validation, mapping {@code ledger.transaction_rejects}.
 *
 * <p>This type records an outcome and never computes one. Which records reject, which reason code a
 * rejection carries, and in what order the reasons are assigned, all belong to the batch context.
 * What belongs here is the persisted shape of that outcome and the register of what its codes mean,
 * which is why the reason register below is stated in full on this type rather than left to be
 * rediscovered from the reference program by each reader.
 *
 * <h2>Provenance: a 430-byte record that is already three fields</h2>
 *
 * <p>Assumptions: this is the only entity in this package with no copybook behind it. Its layout is
 * declared inline in {@code app/cbl/CBTRN02C.cbl}, which is read as specification and is never
 * modified. Line 176 declares {@code 01 REJECT-RECORD} over two items -- {@code REJECT-TRAN-DATA
 * PIC X(350)} at line 177 and {@code VALIDATION-TRAILER PIC X(80)} at line 178 -- so the record is
 * 350 plus 80, or 430 bytes. The trailer is itself structured rather than opaque: lines 180 to 182
 * declare {@code 01 WS-VALIDATION-TRAILER} over {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} and
 * {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)}, so those 80 bytes resolve into 4 plus 76. The
 * baseline record is therefore three fields already, and that is the reason this table carries
 * three columns rather than one 430-character value: the split below is the program's own split and
 * not a decomposition this migration invented.
 *
 * <p>Assumptions: the 430 is corroborated outside the program by the dataset attributes it is
 * written through. {@code app/jcl/POSTTRAN.jcl} line 36 declares
 * {@code DCB=(RECFM=F,LRECL=430,BLKSIZE=0)} on the {@code DALYREJS} block at its lines 34 to 38.
 * {@code RECFM=F} is fixed and unblocked, so every record occupies its full declared length and the
 * width is part of the contract rather than an upper bound. The file description at lines 81 to 84
 * of the program states the same 350 and 80 a third time, so three independent statements agree.
 * The record is fully occupied at 430 bytes, so unlike the other three tables of this schema there
 * is no padding item to account for.
 *
 * <p>Assumptions: the paragraph that fills a record is {@code 2500-WRITE-REJECT-REC} at line 446,
 * and it does exactly two moves and one write. Line 447 moves the whole inbound record into the
 * image area, line 448 moves the trailer, and line 451 writes. The main loop reaches it at
 * line 215, having taken the reject branch at line 213.
 *
 * <h2>The record image and its decomposed sibling</h2>
 *
 * <p>Assumptions: the 350 bytes this type holds in one member are a {@code DALYTRAN-RECORD}, whose
 * contract is {@code app/cpy/CVTRA06Y.cpy} lines 4 to 18 -- thirteen data items over 330 bytes
 * closed by {@code FILLER PIC X(20)} padding bytes 331 to 350. The decomposed form of that same
 * layout is the sibling {@code DailyTransaction} in this package, so a reader who needs the field
 * positions has them one type away without this table acquiring a dependency on them. Every byte
 * range named in this file is one-based and inclusive.
 *
 * <h2>The reason register</h2>
 *
 * <p>Assumptions: five reason codes are set in the reference program and each is reproduced below
 * with the line that sets it and the line that carries its text. The texts are carried
 * character-for-character, because a reject stream is compared byte for byte against the golden
 * masters of the repository's parity suite, and the description occupies a fixed
 * {@code PIC X(76)} field that the baseline pads with spaces. The longest is 42 characters, so
 * every text fits the declared width with room unused, and the declared width is preserved as the
 * contract rather than narrowed to the observed maximum.
 *
 * <ul>
 *   <li><b>100</b> {@code INVALID CARD NUMBER FOUND} -- set at line 385 with its text at line 386,
 *       inside the cross-reference lookup paragraph at line 380, on the {@code INVALID KEY} path of
 *       the read at line 383. The card number the record carries resolved to no cross-reference
 *       entry.</li>
 *   <li><b>101</b> {@code ACCOUNT RECORD NOT FOUND} -- set at line 397 with its text at line 398,
 *       inside the account lookup paragraph at line 393, on the {@code INVALID KEY} path of the
 *       read at line 395. The cross-reference resolved, and the account it named did not.</li>
 *   <li><b>102</b> {@code OVERLIMIT TRANSACTION} -- set at line 410 with its text at line 411, on
 *       the {@code ELSE} of the guard at line 407.</li>
 *   <li><b>103</b> {@code TRANSACTION RECEIVED AFTER ACCT EXPIRATION} -- set at line 417 with its
 *       text at line 418, on the {@code ELSE} of the guard at line 414.</li>
 *   <li><b>109</b> {@code ACCOUNT RECORD NOT FOUND} -- set at line 556 with its text at line 557,
 *       inside {@code 2800-UPDATE-ACCOUNT-REC} at line 545, on the {@code INVALID KEY} path of the
 *       account {@code REWRITE} at line 554. Its text is byte-identical to 101's at line 398, so
 *       the code and not the text is what distinguishes the two. This is a post-validation
 *       account-rewrite failure and not a validation reject; the paragraph that sets it is reached
 *       only after validation has already passed, and the consequence is set out under the second
 *       finding below.</li>
 * </ul>
 *
 * <h2>The two boundaries that produce 102 and 103</h2>
 *
 * <p>Assumptions: the quantity 102 is decided on is a projected balance, and it is computed at
 * lines 403 to 405 as {@code ACCT-CURR-CYC-CREDIT - ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT}. It is
 * built from the cycle accumulators and not from the current balance, which matters to anyone
 * reproducing the decision from the account record: reading the current balance instead would
 * decide a different question.
 *
 * <p>Assumptions: both framings of the over-limit boundary are stated here, because each one alone
 * invites the opposite mistake. Line 407 is {@code IF ACCT-CREDIT-LIMIT &gt;= WS-TEMP-BAL}, so the
 * guard that lets a record pass is inclusive -- the limit may equal the projected balance -- and
 * the reject therefore fires only when the projected balance is strictly greater than the limit. A
 * transaction landing exactly at the limit posts; one cent beyond it rejects.
 *
 * <p>Assumptions: the expiration boundary is inclusive in the same way. Line 414 is
 * {@code IF ACCT-EXPIRAION-DATE &gt;= DALYTRAN-ORIG-TS (1:10)}, comparing the account expiration
 * date against the leading ten characters of a 26-character originating timestamp, so a
 * transaction dated equal to the expiration date posts and only one dated beyond it rejects. The
 * field name is spelled as the baseline spells it, because this is a citation of a name that exists
 * on disk and altering it in the citation would make the reference unfindable.
 *
 * <h2>Finding: 103 overwrites 102 when both conditions fail</h2>
 *
 * <p>Assumptions: this is an external behaviour of the reference program that any producer of these
 * rows has to reproduce, and it cannot be recovered by reading the target code, so it is recorded
 * both here and on the reason code member it governs. The account lookup paragraph contains two
 * sequential and unguarded blocks inside one {@code NOT INVALID KEY} branch: the over-limit test at
 * lines 407 to 413 and the expiration test at lines 414 to 420. The second is not conditioned on
 * the reason still being zero. When a record fails both, lines 417 and 418 therefore overwrite
 * lines 410 and 411, and the row that reaches this table carries 103 with 103's description. 103
 * wins.
 *
 * <p>Assumptions: the chain is short-circuited earlier and not here, and describing it as
 * uniformly short-circuited would be inaccurate. Line 372 is
 * {@code IF WS-VALIDATION-FAIL-REASON = 0} and it guards the account lookup at line 373, so 100
 * suppresses 101. Nothing plays that role between 102 and 103. The comment at line 377,
 * {@code ADD MORE VALIDATIONS HERE}, sits at the end of that same sequence.
 *
 * <h2>Finding: 109 never produces a row of this table</h2>
 *
 * <p>Assumptions: the baseline sets 109 and writes no reject row carrying it, and the sequence that
 * makes this so is worth stating in full because the code is representable in this column and a
 * reader will find it in the program. It is set at line 556 inside the paragraph at line 545, which
 * is performed at line 441 from {@code 2000-POST-TRANSACTION} -- a path the loop enters at line 212
 * only after line 211 has already found the reason to be zero. The loop tests the reason at
 * line 211 and never re-tests it afterwards, and at the top of the next record's iteration it
 * resets the reason at line 208 and the description at line 209. So a 109 set while posting one
 * record is cleared before the next record is validated, it is never counted by the reject
 * counter at line 214, and the write at line 451 is never reached for it.
 *
 * <p>Trade-offs: the column domain admits 109 nonetheless, and that is the accepted compromise
 * rather than an oversight. Narrowing the domain to the four codes a row can currently carry would
 * make the one code that signals a rewrite failure the single value this table could not record,
 * and the migration's posting job is able to persist what the baseline could only leave in a field.
 * The cost is that a reader who greps this register for 109 finds a code that the reference
 * behaviour never persists, which is precisely why the paragraph above says so explicitly.
 *
 * <h2>The migration owns the physical shape, and this type answers to it</h2>
 *
 * <p>Assumptions: the authoritative column list is
 * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql}, which
 * declares this table at its lines 445 to 498 and records at its lines 500 to 508 why it declares
 * no key, no unique constraint and no index over it. The sibling {@code application.yml} sets
 * {@code ddl-auto: none} at its line 473, so the persistence provider generates no schema and
 * verifies none, and every column name, type and width written below has to match that migration
 * exactly. Nothing reconciles the two at start-up, so a disagreement stays invisible until a query
 * runs. The repository profile at {@code src/test/resources/application-test.yml} line 426 sets
 * {@code ddl-auto: validate} instead, so a test against a migrated database is where a width or
 * type disagreement surfaces.
 *
 * <p>Assumptions: none of the three columns carries a not-null constraint in that migration, so no
 * {@code nullable} attribute appears on any mapping below. The mapping states what the migration
 * states and nothing more.
 *
 * <h2>No version attribute, and the absence is recorded rather than merely left</h2>
 *
 * <p>Alternatives Considered: annotating this type with {@code @Version} for optimistic
 * concurrency. That is the plausible alternative, because the wider migration adopts exactly that
 * pattern for the account and card records, and it is rejected on evidence read on the reference
 * branch. This stream is append-only. Line 293 opens it {@code OPEN OUTPUT}, line 451 is the only
 * write to it anywhere in the program, and no statement reads it or rewrites it. Optimistic
 * concurrency protects a read-modify-write, and there is none here to protect. A second reason is
 * mechanical: because schema generation is switched off, a version attribute would map to a column
 * the migration does not create and would fail when a query ran rather than degrade to unversioned
 * behaviour. The absence is stated because an entity with no version attribute looks identical
 * whether the omission was reasoned or overlooked.
 *
 * <h2>No index is declared on this type</h2>
 *
 * <p>Alternatives Considered: declaring {@code @Index} metadata on the table annotation for the
 * reason code, which is the value a reader of this stream filters on. Rejected on two independent
 * grounds. The migration deliberately declares no index over this table, so an index named here
 * would be one that does not exist -- the two indexes that file creates, at its lines 269 and 291,
 * are both over {@code ledger.transactions}. And because {@code ddl-auto: none} means such metadata
 * is never acted on, it would neither create an index nor verify one, so it could drift out of step
 * with the migration while still reading like a specification.
 *
 * <h2>The baseline dataset is a generation data group</h2>
 *
 * <p>Assumptions: the reject stream is not one dataset but a generation of one.
 * {@code app/jcl/DALYREJS.jcl} defines the base at its lines 24 to 27 as a
 * {@code GENERATIONDATAGROUP} with {@code LIMIT(5)} and {@code SCRATCH}, and
 * {@code app/jcl/POSTTRAN.jcl} line 38 writes generation {@code (+1)}, so each run appends a new
 * generation and the oldest beyond five is discarded. That retention is a property of the dataset
 * and not of a record, so in the target it lives in the versioned dataset object store, whose
 * lifecycle configuration keeps the same five, rather than as a column here. This table therefore
 * carries no generation, run or sequence member: adding one would put the same retention in two
 * places able to disagree.
 *
 * <h2>The batch context agrees with this type through the schema, never through code</h2>
 *
 * <p>Alternatives Considered: rows of this table are produced by the posting job, which belongs to
 * the batch context rather than to this module, and the two modules could have been coupled by
 * sharing this type. Instead that context declares its own {@code com.carddemo.batch.domain} and
 * writes these rows under a narrowly scoped cross-schema grant, so neither module imports a type
 * from the other and the only shared artifact is the physical schema. The grant exists because the
 * posting unit of work at lines 424 to 444 performs three writes in sequence and has to stay one
 * atomic commit; a saga across two services was the other alternative and is rejected because it
 * would make partial-posting states observable that the baseline does not have.
 *
 * <p>Assumptions: three separate outcome vocabularies meet at this table and none of them is the
 * others, so a reader who conflates any two will misread all three. Lines 229 and 230 of the
 * reference program set a return code of 4 when the reject count is above zero, and that is the
 * process exit tier the batch context owns -- clean, completed-with-rejects, and hard failure --
 * where a completed run carrying rejects is a success that has to survive with its rejects intact.
 * The repository's COBOL parity suite grades its own outcome on a mainframe condition-code rubric
 * in which a warning-level result is its green state, and that rubric is that suite's alone. This
 * module's build is the third and is binary: the compiler, the documentation gate and the test
 * runner each pass or fail, and no result here is ever described in graded terms.
 *
 * <h2>No association, and no member is renamed</h2>
 *
 * <p>Alternatives Considered: a JPA association from this type to {@code DailyTransaction}, which
 * is inviting because the image this row holds is a serialised record of exactly that layout.
 * Rejected because the baseline writes this stream as an independent sequential dataset with no
 * referential link of any kind, and because a rejected record is often precisely one whose
 * identifier resolves to nothing -- reason 100 is a card number that matched no cross-reference
 * entry. An association would assert a referential integrity this data does not have and would fail
 * to load the rows it exists to keep. The image is joined to the feed by value when a reader needs
 * that, not by a mapped relationship.
 *
 * <p>Assumptions: every member below spells its source exactly, and a reader arriving from another
 * context expecting a spelling change will find none here. The migration does spell three baseline
 * names differently in its own columns -- the account expiration date, the card expiration date and
 * the merchant category code -- and all three belong to the account, card and authorization
 * contexts. None of them appears in this record layout, so none applies.
 */
@Entity
// WHY : Alternatives Considered: the schema is named explicitly rather than left to the search_path
//       that application.yml line 321 pins per pooled connection with `connection-init-sql: SET
//       search_path TO ledger`. Relying on that would make this mapping unreadable without opening
//       the YAML, and it would resolve differently for a connection that cannot pin a single
//       schema -- the batch context's login needs `ledger` and `account` together, and it is that
//       context which writes these rows. Naming the schema here means this type resolves to one
//       table whichever connection loads it.
@Table(name = "transaction_rejects", schema = "ledger")
public class TransactionReject {

    /**
     * The rejected daily transaction retained verbatim as one undivided 350-byte image, and this
     * type's mapped identity.
     *
     * <p>Assumptions: {@code REJECT-TRAN-DATA PIC X(350)} at line 177 of the inline layout, filled
     * in one move at line 447, mapped to {@code raw_record CHAR(350)}.
     */
    // WHY : Alternatives Considered: decomposing these 350 bytes into the thirteen members their
    //       layout yields, exactly as the sibling DailyTransaction does for the same layout.
    //       Rejected because a rejected record has to be retained byte-for-byte to stay
    //       re-drivable: line 447 moves the whole record in one move with no field-level handling,
    //       and the worth of this stream is that it can be replayed unaltered once the underlying
    //       cause is addressed. Decomposing it would make the stored form depend on this release's
    //       parsing of the layout, so a record that rejected BECAUSE its layout was not what was
    //       expected could no longer be stored at all -- and reason 100 is by definition a record
    //       whose card number resolved to nothing, so unparseable content is the normal case here
    //       rather than the exceptional one.
    // WHY : Trade-offs: the accepted compromise is query power. Selecting on a card number or an
    //       amount requires either a substring at a documented offset or a join by value to
    //       ledger.daily_transactions, and that is preferred over losing byte fidelity, because a
    //       substring is recoverable from the layout at any time whereas discarded bytes are not.
    //       One retrieval detail is worth knowing before it looks like data loss: the fixed-width
    //       type blank-pads to 350, and length() reports the value with trailing blanks removed
    //       while octet_length() reports 350.
    // WHY : Assumptions: the column is fixed width rather than varying because the width IS the
    //       contract -- app/jcl/POSTTRAN.jcl line 36 declares RECFM=F with LRECL=430 -- so trailing
    //       blanks are significant padding and not absent data. The CHAR binding is stated
    //       explicitly through the type code below rather than through a columnDefinition, matching
    //       the sibling entities: a String otherwise selects the VARCHAR binding, and the driver
    //       then sends a varying-length parameter for a blank-padded column, so a comparison
    //       against a value shorter than the declared width can miss a row that is present.
    // WHY : Trade-offs: this member is the mapped identity, and that is a compromise the schema
    //       does not underwrite. The migration declares no primary key over this table and excludes
    //       a surrogate on principle at its lines 500 to 508, while the persistence provider
    //       requires an identifier, and adding a column the migration does not create would fail
    //       under `ddl-auto: none`. The image is the only one of the three columns that denotes a
    //       record at all -- the other two classify it -- so the identity is scalar over this
    //       column, which is what the parent charter rules for the three entities of this package
    //       that do not need an identifier class. The consequence belongs on the member: two rows
    //       carrying the same image are indistinguishable to this type, and the migration states at
    //       its lines 504 to 506 that such duplicates are legitimate in the stream, since one
    //       record rejected on two runs is two entries. Appending rows is the batch context's
    //       business through its own domain, and this type reads them.
    // WHY : Assumptions: mapped non-updatable because an append-only stream never rewrites an
    //       image, so reassigning it would change the member without changing the row and would
    //       silently invalidate any hash-based collection already holding the instance.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "raw_record", length = 350, updatable = false)
    private String rawRecord;

    /**
     * The four-digit reason the record was rejected for.
     *
     * <p>Assumptions: {@code WS-VALIDATION-FAIL-REASON PIC 9(04)} at line 181 of the inline layout,
     * the leading four bytes of the 80-byte trailer moved at line 448, mapped to
     * {@code reason_code SMALLINT}.
     */
    // WHY : Assumptions: when a record fails both the over-limit test and the expiration test, the
    //       value stored here is 103 and not 102. The two tests are sequential unguarded blocks
    //       inside one NOT INVALID KEY branch -- over-limit at lines 407 to 413, expiration at
    //       lines 414 to 420 -- and the second is not conditioned on the reason still being zero,
    //       so lines 417 and 418 overwrite lines 410 and 411. 103 wins. This is the external
    //       behaviour any producer of these rows has to reproduce, and a naive if / else-if written
    //       in declaration order would emit 102 and diverge from it. Note that the chain is
    //       short-circuited between 100 and 101, where line 372 tests the reason before the account
    //       lookup at line 373, and is not short-circuited between 102 and 103; treating the whole
    //       sequence as short-circuited is the mistake this note exists to prevent.
    // WHY : Alternatives Considered: a Java enum over the reject reasons, which is the usual reflex
    //       for a small closed set of codes. Rejected on two independent grounds. The reason space
    //       is deliberately open -- line 377 of the reference program carries the comment `ADD MORE
    //       VALIDATIONS HERE` at the end of the validation sequence -- and an enum would close it,
    //       making an unrecognised code unpersistable and turning a value this column can hold into
    //       a load failure. And code 109 exists outside the reject path altogether, as the class
    //       documentation sets out, so an enum named for reject reasons would have to either admit
    //       a value the reference behaviour never persists or omit a code that is genuinely set.
    //       The numeric column keeps the register a matter of documentation, which is where an open
    //       set belongs, and this package declares no enum type by charter in any case.
    // WHY : Assumptions: the declared picture admits at most 9999, so a 16-bit integer covers the
    //       whole domain and a wider type would reserve bytes no value can use. The boxed type is
    //       used rather than the primitive because the migration leaves the column nullable, and a
    //       primitive would silently render an absent code as zero -- a value that reads as a
    //       successfully validated record.
    @Column(name = "reason_code")
    private Short reasonCode;

    /**
     * The reason description that accompanies the code, carried across character-for-character.
     *
     * <p>Assumptions: {@code WS-VALIDATION-FAIL-REASON-DESC PIC X(76)} at line 182 of the inline
     * layout, the trailing 76 bytes of the trailer moved at line 448, mapped to
     * {@code reason_desc VARCHAR(76)}.
     */
    // WHY : Assumptions: the five texts the baseline writes are reproduced verbatim in the reason
    //       register on this type and are never reflowed, recased or repunctuated, because this
    //       stream is compared byte for byte against the parity suite's golden masters and because
    //       the migration carries every user-visible string across unchanged. Two of the five are
    //       byte-identical -- 101 at line 398 and 109 at line 557 -- so the code beside this value
    //       is the only thing that separates them, and a consumer that keys on the text alone
    //       cannot tell an account read failure from an account rewrite failure.
    // WHY : Alternatives Considered: a varying column of the observed maximum, 42 characters, which
    //       is the longest of the five texts. Rejected because the declared width of 76 is the
    //       contract and the observed maximum is a property of the five texts that happen to exist,
    //       so narrowing to it would make any sixth text a truncation. The type is varying rather
    //       than fixed width -- unlike the image above -- because this is descriptive text whose
    //       trailing spaces are padding the baseline field forced rather than content, and no
    //       comparison depends on them.
    @Column(name = "reason_desc", length = 76)
    private String reasonDesc;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the provider requires a no-argument constructor in order to instantiate this
     * type reflectively when it materialises a row or builds a lazy proxy, and it populates the
     * members afterwards by field access rather than through the accessors below. The body is
     * therefore empty by design and not unfinished: there is nothing to assign that the provider is
     * not about to assign itself, and defaulting a member here would overwrite a column value on
     * every load.
     *
     * <p>Alternatives Considered: making it private and relying on the provider's reflective access
     * to a private constructor. Rejected because a subclass generated for a lazy proxy has to be
     * able to invoke it, so protected is the narrowest visibility that works. The governing
     * documentation rule attaches its docstring obligation to every function with no visibility
     * qualifier, so this constructor carries a full one rather than being treated as boilerplate.
     */
    protected TransactionReject() {
        // WHY : Assumptions: intentionally empty. The provider assigns every member after
        //       construction, so any initialisation written here would be overwritten on a load and
        //       would silently mask an absent column on an insert.
    }

    /**
     * Creates a fully populated reject row from an image and the trailer that classifies it.
     *
     * <p>Assumptions: the three parameters are the three fields the reference record already has,
     * supplied in the order the program fills them -- the image at line 447, then the trailer at
     * line 448, whose four-digit code and 76-character description are the second and third
     * arguments. Nothing is derived here, and no generation, run identifier or timestamp is minted,
     * because the baseline record carries none of the three and the generation that does exist is a
     * property of the dataset rather than of a row.
     *
     * <p>Assumptions: no validation is performed, and this constructor raises nothing of its own.
     * That is deliberate rather than an omission. The validation sequence lives in the batch
     * context, it is what decides that a record rejects at all, and enforcing any part of it here
     * would refuse to represent exactly the records this table exists to keep: a reason 100 image
     * carries a card number that resolved to nothing, and a reason 102 image can carry an amount no
     * validated column would accept. A constructor that rejected such input would make the reject
     * stream unloadable.
     *
     * @param rawRecord the rejected daily transaction as an undivided 350-byte image, retained
     *     verbatim and never parsed by this type, which also serves as this instance's mapped
     *     identity
     * @param reasonCode the four-digit reason the record was rejected for, one of the codes in this
     *     type's reason register, or {@code null} on a row that carries none
     * @param reasonDesc the reason description as the baseline writes it, up to 76 characters and
     *     carried across character-for-character, or {@code null} on a row that carries none
     */
    public TransactionReject(String rawRecord, Short reasonCode, String reasonDesc) {
        this.rawRecord = rawRecord;
        this.reasonCode = reasonCode;
        this.reasonDesc = reasonDesc;
    }

    /**
     * Returns the rejected daily transaction as one undecomposed 350-byte image, which this type
     * never parses into fields.
     *
     * <p>Assumptions: the caller receives the bytes as they arrived, so anything field-level is the
     * caller's to do. The layout is {@code app/cpy/CVTRA06Y.cpy} lines 4 to 18 and its decomposed
     * form is the sibling {@code DailyTransaction}. Two consequences are worth stating at the point
     * of retrieval: the value is blank-padded to the full 350 characters by the fixed-width column,
     * and bytes 263 to 278 of it are a primary account number, so it is not a value to place in a
     * log line or a diagnostic message.
     *
     * @return the 350-character record image, blank-padded to that width, or {@code null} on an
     *     instance the provider has not hydrated
     */
    public String getRawRecord() {
        return this.rawRecord;
    }

    /**
     * Assigns the rejected daily transaction image.
     *
     * <p>Assumptions: this is the mapped identity and the column is mapped
     * {@code updatable = false}, so assigning it after the row is persisted changes the member
     * without changing the row. The accessor exists for a loader to populate a new instance, not to
     * re-key an existing one.
     *
     * @param rawRecord the 350-character record image to assign, stored verbatim and neither parsed
     *     nor validated here
     */
    public void setRawRecord(String rawRecord) {
        this.rawRecord = rawRecord;
    }

    /**
     * Returns the four-digit reason the record was rejected for.
     *
     * @return the reason code, one of the codes in this type's reason register, or {@code null} when
     *     the row carries none
     */
    public Short getReasonCode() {
        return this.reasonCode;
    }

    /**
     * Assigns the four-digit reason the record was rejected for.
     *
     * @param reasonCode the reason code to assign; it is decided by the batch context's validation
     *     sequence rather than here, and where a record fails both the over-limit and the expiration
     *     test that sequence yields 103
     */
    public void setReasonCode(Short reasonCode) {
        this.reasonCode = reasonCode;
    }

    /**
     * Returns the reason description that accompanies the code.
     *
     * @return the description as the baseline writes it, up to 76 characters, or {@code null} when
     *     the row carries none
     */
    public String getReasonDesc() {
        return this.reasonDesc;
    }

    /**
     * Assigns the reason description that accompanies the code.
     *
     * @param reasonDesc the description to assign, expected character-for-character as the baseline
     *     writes it, since two of the five texts are byte-identical and only the code beside them
     *     separates the pair
     */
    public void setReasonDesc(String reasonDesc) {
        this.reasonDesc = reasonDesc;
    }

    /**
     * Compares this row with another on the record image alone, which is this type's mapped
     * identity. Two instances carrying the same image denote the same rejected record.
     *
     * <p>Alternatives Considered: comparing all three members. Rejected because the image is the
     * value this type maps as its identity, so it already denotes the record, and because
     * all-member equality breaks identity across a persistence flush -- the provider writes values
     * into an instance a collection is already holding, and the instance then hashes differently
     * from the bucket it sits in. It would also make two reads of one row unequal the moment a
     * query populated a different subset of columns.
     *
     * <p>Trade-offs: because the migration puts no unique constraint on this column,
     * image-only equality is an assertion this type makes rather than one the schema guarantees, and
     * the same record rejected on two runs is two rows this method reports as equal. That is
     * accepted as the honest consequence of mapping an append-only keyless stream: all-member
     * comparison would report those two rows as distinct while still leaving the provider unable to
     * tell them apart, so it would move the ambiguity rather than remove it. A caller that has to
     * distinguish two occurrences of one reject distinguishes them by the generation they were read
     * from, which is a property of the dataset and not of this row.
     *
     * <p>Assumptions: an instance built by the no-argument constructor and not yet hydrated carries
     * no image, so two such instances compare equal because the image is the only value compared and
     * there is nothing else to tell them apart. The provider never publishes an instance in that
     * state, so the case is recorded rather than guarded against.
     *
     * @param other the object to compare with, which may be {@code null} or of any type
     * @return {@code true} when {@code other} is a reject row of this type carrying an equal record
     *     image, {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        // WHY : Assumptions: a pattern match rather than a class comparison, because the provider
        //       may hand back an instrumented subclass for a lazy proxy and a strict class
        //       comparison would then report a row as unequal to itself. No subclass of this type is
        //       authored, so widening the test costs nothing.
        if (!(other instanceof TransactionReject that)) {
            return false;
        }
        return Objects.equals(this.rawRecord, that.rawRecord);
    }

    /**
     * Returns a hash consistent with the image-only equality above. It is derived from the record
     * image and from nothing else.
     *
     * <p>Assumptions: the image arrives with the row and this table is only ever appended to, so the
     * value stays stable for the life of an instance even though a setter exists for a loader to
     * populate a new one. That stability is what makes the type safe to place in a hash-based
     * collection, and it is why the column is mapped {@code updatable = false}.
     *
     * <p>Trade-offs: hashing a 350-character value costs more than hashing a short key would, and
     * that is accepted because the alternative is a hash over a member that is not the identity,
     * which would break the contract between this method and the equality above. The cost is bounded
     * in any case, since the width is fixed at 350 by the record contract and cannot grow.
     *
     * @return the hash of the record image, or the hash of an absent value on an instance the
     *     provider has not yet hydrated
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.rawRecord);
    }

    /**
     * Returns a diagnostic rendering of this row that names the reason code, the reason description
     * and the length of the record image, and never the image itself. It is intended for a log line
     * or an assertion message.
     *
     * <p>Trade-offs: the record image is withheld even though it is this type's identity, so this
     * rendering is not round-trippable and a reader cannot recover the rejected record from a log
     * line. That cost is accepted because the compensation is absolute: bytes 263 to 278 of the
     * image are a sixteen-character primary account number, this output reaches logs, and the
     * migration masks account numbers to their last four digits everywhere but the administrative
     * card-detail endpoint. Emitting a masked excerpt here was the alternative and is rejected
     * because abbreviating an account number IS masking, and the parent charter names the mapper
     * package as the sole boundary where masking may appear; a second, slightly different masking
     * rule on this type would give one value two renderings and make neither authoritative.
     *
     * <p>Assumptions: the length is emitted in the image's place because it is the one property of
     * those bytes that discloses nothing while still answering the question a reader of this stream
     * actually has -- whether the row holds a full-width 350-character record or a short one, which
     * distinguishes a business-rule reject from a malformed inbound record. The code and the
     * description together identify which rule fired, and the register on this type resolves the
     * pair of codes whose descriptions are byte-identical.
     *
     * @return a short single-line rendering naming the type, the length of the record image or an
     *     absence marker in its place, the reason code and the reason description, and no other
     *     value
     */
    @Override
    public String toString() {
        // WHY : Assumptions: the length is read through an explicit absent-value branch rather than
        //       from the member directly, because a diagnostic rendering is exactly what gets called
        //       on a partially built or unhydrated instance, and a rendering that itself fails
        //       replaces the information a reader needs with an unrelated failure.
        String imageLength = this.rawRecord == null
                ? "absent"
                : Integer.toString(this.rawRecord.length());
        return "TransactionReject[rawRecordLength=" + imageLength
                + ", reasonCode=" + this.reasonCode
                + ", reasonDesc=" + this.reasonDesc + ']';
    }
}
