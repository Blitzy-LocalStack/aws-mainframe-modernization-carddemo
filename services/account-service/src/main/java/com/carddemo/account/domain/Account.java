package com.carddemo.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * The account master row this bounded context owns.
 *
 * <h2>Reference contract</h2>
 *
 * <p>This is {@code ACCOUNT-RECORD}, declared at {@code app/cpy/CVACT01Y.cpy} L4, whose header comment
 * at L2 announces a 300-byte record. Twelve named fields at L5 to L16 become the twelve mapped members
 * below, and the {@code FILLER} at L17 is dropped.</p>
 *
 * <p>Assumptions: the copybook is normative, so a field's picture clause is what decides its column
 * type and its member type rather than a preference applied afterwards. The record accounts for its
 * declared length exactly, which is why the arithmetic is quoted here: the twelve named fields occupy
 * 11 + 1 + 12 + 12 + 12 + 10 + 10 + 10 + 12 + 12 + 10 + 10 = 122 bytes, the {@code FILLER PIC X(178)}
 * at L17 occupies the remaining 178, and 122 + 178 = 300. That sum is independently checkable against
 * the seed data, and it is what distinguishes a deliberately dropped {@code FILLER} from a field
 * somebody forgot to map. The filler is padding to a fixed physical record length, and a row has no
 * such length, so it carries no information a column could hold.</p>
 *
 * <h2>The column shape this entity is checked against</h2>
 *
 * <p>Assumptions: the authoritative declaration of these columns is the migration at
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql}, not this class.
 * Nothing in the running system compares the two, because the provider's schema management is switched
 * off at {@code ddl-auto} in {@code src/main/resources/application.yml}; a member naming a column the
 * migration does not declare would therefore compile, start, and only then fail on the first statement
 * that touched it. The expected shape is restated here so that the obligation is visible at the mapping
 * site rather than only at the migration:</p>
 *
 * <pre>
 * account.accounts
 *   account_id         BIGINT        NOT NULL   -- primary key pk_accounts, assigned not generated
 *   active_status      CHAR(1)       NOT NULL   -- ck_accounts_active_status: IN ('Y','N')
 *   curr_bal           NUMERIC(12,2) NOT NULL
 *   credit_limit       NUMERIC(12,2) NOT NULL
 *   cash_credit_limit  NUMERIC(12,2) NOT NULL
 *   open_date          DATE          NOT NULL
 *   expiration_date    DATE          NOT NULL   -- renames ACCT-EXPIRAION-DATE
 *   reissue_date       DATE          NOT NULL
 *   curr_cyc_credit    NUMERIC(12,2) NOT NULL
 *   curr_cyc_debit     NUMERIC(12,2) NOT NULL
 *   addr_zip           CHAR(10)      NOT NULL   -- NOT a date
 *   group_id           CHAR(10)      NOT NULL   -- NOT a date; no foreign key
 *   version            BIGINT        NOT NULL DEFAULT 0
 * </pre>
 *
 * <p>Assumptions: every column is {@code NOT NULL} and no member is optional. A fixed-width record has
 * no concept of absence -- an unset field is spaces or zeroes -- so there is no value in this set that
 * the reference could leave out and nothing here models one.</p>
 *
 * <p>Assumptions: the schema qualifier is supplied by configuration rather than repeated on this entity,
 * which is why {@code @Table} below names a table and does not qualify it. The module pins a default
 * schema and additionally sets the search path on every pooled connection. Repeating the qualifier here
 * would be harmless until the day it disagreed with the configured value, at which point two sources
 * would compete and the annotation would silently win.</p>
 *
 * <h2>Money is exact fixed point, never IEEE-754 binary floating point</h2>
 *
 * <p>Alternatives Considered: typing the five monetary members as the shared {@code Money} value type
 * from {@code com.carddemo.common.money} instead of {@link BigDecimal}. Rejected. That type is the
 * JSON-boundary representation: the shared Jackson module binds its serialiser to it so that an amount
 * leaves the process as a JSON string, and a money-bearing transfer object therefore has to be typed
 * with it. An entity is a different boundary. Carrying it here would oblige this domain package to
 * declare a persistence attribute converter or an embeddable purely to satisfy the provider, which puts
 * persistence plumbing into the layer that is meant to hold the record and nothing else. The mapping
 * table the migration is derived from settles the pairing as {@code NUMERIC(12,2)} to {@link BigDecimal},
 * which the provider maps natively with no converter at all, so the conversion to the boundary type is
 * the {@code mapper} package's responsibility and not this class's.</p>
 *
 * <p>Assumptions: the five amounts are exact decimal quantities and are held at scale 2, matching
 * {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code CVACT01Y.cpy} L7 and the four fields declared the same
 * way at L8, L9, L13 and L14. Ten integer digits plus two fractional digits is what fixes the column at
 * precision 12 and scale 2. The reference stores these as zoned decimal with a sign overpunch: the
 * balance on the first record of {@code app/data/ASCII/acctdata.txt} occupies twelve bytes of which the
 * first eleven are the digits {@code 00000001940} and the twelfth is an opening-brace character that
 * encodes the sign, rather than a leading minus. IEEE-754 binary floating point cannot
 * represent every two-decimal value exactly, and the consequence of getting this wrong is not a visible
 * failure but a plausible wrong number: {@code tests/README.md} L273 to L274 records that compiling the
 * reference with the default sign convention instead of the EBCDIC one silently corrupts negative
 * balances. The prohibition is not left to discipline. The shared architecture test
 * {@code com.carddemo.common.architecture.LayeringRulesTest} fails the build on a binary
 * floating-point member anywhere beneath the analysed root.</p>
 *
 * <h2>Three fields are dates; two that look like dates are not</h2>
 *
 * <p>Assumptions: exactly three members are {@link LocalDate} over {@code DATE} columns --
 * {@code ACCT-OPEN-DATE} at L10, {@code ACCT-EXPIRAION-DATE} at L11 and {@code ACCT-REISSUE-DATE} at
 * L12 -- although the reference declares all three as {@code PIC X(10)} character fields. Two
 * independent proofs in the reference itself close the question. First, the comparison paragraph of
 * {@code app/cbl/COACTUPC.cbl} takes {@code ACCT-OPEN-DATE(1:4)}, {@code (6:2)} and {@code (9:2)} at
 * L4127 to L4129, and does the same for the expiry at L4131 to L4133 and the reissue date at L4135 to
 * L4137: byte positions 5 and 8 are skipped in every case because they hold the hyphens of a
 * year-month-day rendering. Second, {@code app/cbl/CBTRN02C.cbl} L414 compares the expiry against the
 * first ten characters of a timestamp with a plain {@code >=}, which is only meaningful because the
 * layout is year-month-day ordered and lexical order therefore agrees with date order. That agreement
 * is what makes this mapping behaviour-preserving. The seed data confirms the rendering directly, with
 * an open date of {@code 2014-11-20} and an expiry of {@code 2025-05-20} on the first record.</p>
 *
 * <p>Assumptions: {@code ACCT-ADDR-ZIP} at L15 and {@code ACCT-GROUP-ID} at L16 are also
 * {@code PIC X(10)} and are emphatically NOT dates. Both are {@link String} over {@code CHAR(10)}, and
 * neither may ever become a date. Narrowing a postal code or a disclosure-group key to a date would
 * discard information irrecoverably, and here it would not even fail quietly: the seeded
 * {@code ACCT-ADDR-ZIP} is {@code A000000000} in all fifty records, which begins with a letter and
 * would refuse to parse as a date on the very first row. Each has its own independent evidence.
 * {@code ACCT-ADDR-ZIP} is export-only -- it has exactly four references in the whole reference tree,
 * being its own declaration at {@code CVACT01Y.cpy} L15, {@code EXP-ACCT-ADDR-ZIP PIC X(10)} at
 * {@code app/cpy/CVEXPORT.cpy} L58, and one move each at {@code app/cbl/CBEXPORT.cbl} L361 and
 * {@code app/cbl/CBIMPORT.cbl} L338 -- and it appears nowhere in the two account screens or the account
 * reader, so its only behaviour is fixed-width byte transport through an export record.
 * {@code ACCT-GROUP-ID} is a reference-table discriminator: it keys
 * {@code DIS-ACCT-GROUP-ID PIC X(10)} at {@code app/cpy/CVTRA02Y.cpy} L6, it is displayed by
 * {@code app/cbl/COACTVWC.cbl} L490 and captured into the before-image at {@code COACTUPC.cbl} L3847,
 * and the literal {@code 'DEFAULT'} is in its value domain per {@code app/cbl/CBACT04C.cbl} L437.</p>
 *
 * <p>Assumptions: {@code group_id} is an un-enforced reference to another context's table and carries no
 * foreign key. The disclosure groups it points at are owned by the reference context in a different
 * schema, and schema-per-service places that constraint out of reach. The seeded value is ten spaces in
 * all fifty records, so a constraint would in any case have nothing to match, which is precisely the
 * condition that exercises the reference reader's {@code 'DEFAULT'} fallback at
 * {@code CBACT04C.cbl} L437.</p>
 *
 * <h2>The one renamed field</h2>
 *
 * <p>Refactoring Rationale: {@code ACCT-EXPIRAION-DATE} at {@code CVACT01Y.cpy} L11 becomes
 * {@code expirationDate} over the {@code expiration_date} column. The misspelling is not a local typo
 * that could be read past -- it propagates through the reference, appearing three times in
 * {@code app/cbl/CBACT01C.cbl}, as {@code OUT-ACCT-EXPIRAION-DATE PIC X(10)} at L64 and again at L207
 * and L222, once at {@code app/cbl/COACTVWC.cbl} L488, and in the before-image declaration at
 * {@code app/cbl/COACTUPC.cbl} L690 -- so a reader who greps the reference for the corrected spelling
 * finds nothing and could reasonably conclude the field is absent. The correction is registered in
 * {@code docs/architecture/data-model-and-schema-mapping.md} so the lineage is never ambiguous, and it
 * is one of exactly three renames the migration makes. No other member of this record is renamed.</p>
 *
 * <h2>Optimistic concurrency is a re-expression, not an addition</h2>
 *
 * <p>Refactoring Rationale: the {@code version} member below is the native form of a check the
 * reference already performs by hand, and reading it as a new semantic would be wrong.
 * {@code app/cbl/COACTUPC.cbl} snapshots the entire pre-edit record into {@code ACUP-OLD-DETAILS},
 * declared at L669 and running to L756, with {@code ACUP-NEW-DETAILS} beginning immediately after at
 * L757. Each numeric in that snapshot is held twice, as a display field with a numeric
 * {@code REDEFINES} over it: {@code ACUP-OLD-CURR-BAL PIC X(12)} at L675 is redefined at L676 to L677
 * as {@code ACUP-OLD-CURR-BAL-N PIC S9(10)V99}, and the credit limit, cash limit, cycle credit and
 * cycle debit repeat that pairing at L678 to L680, L681 to L683, L702 to L704 and L705 to L707. A
 * one-character flag {@code WS-DATACHANGED-FLAG} at L168 carries the outcome, with its two condition
 * names at L169 and L170. The comparison itself is the paragraph
 * {@code 9700-CHECK-CHANGE-IN-REC} at L4109, whose body runs to L4192 and whose exit paragraph is at
 * L4193; it is invoked at L3947 to L3948 under a comment at L3944 to L3946 asking, in the reference's
 * own words, whether someone changed the record while we were out, and when the answer is yes the write
 * is abandoned at L3950 to L3952. What makes the version column the right target is that the
 * read-for-update lock was never held across the client gap in the first place -- that is exactly why
 * the before-image exists -- so expressing the same check as a version counter loses nothing.</p>
 *
 * <p>Trade-offs: a version column is not a behaviour-identical substitute for that comparison. It is
 * stricter. The reference compares some fields case-folded and others raw: the account group goes
 * through {@code FUNCTION LOWER-CASE} on both sides at {@code COACTUPC.cbl} L4139 to L4140, and on the
 * customer side the names, address lines and government-issued identifier go through
 * {@code FUNCTION UPPER-CASE} across L4152 to L4173, while the phone numbers at L4170, the national
 * identifier at L4171, the date of birth at L4174 to L4179 and the remaining fields to L4186 are
 * compared as stored. A version counter detects any committed modification of the row, including a
 * change of letter case alone, which the reference would have treated as no change at all. The
 * migration therefore conflicts in a narrow set of cases where the reference proceeded. That is
 * accepted deliberately, on the ground that refusing a write whose basis has demonstrably moved is the
 * safe direction for the error to lie in, and it is recorded here rather than only in the traceability
 * matrix because a reader comparing the two implementations will otherwise find the difference
 * themselves and take it for a defect. Note also that the reference comparison covers ten account
 * fields and not twelve: the identifier is excluded as the key, and {@code ACCT-ADDR-ZIP} has no
 * counterpart in the snapshot at all -- there is no {@code ACUP-OLD-ADDR-ZIP}, and a search for the
 * field name across that program returns nothing.</p>
 *
 * <p>Assumptions: this class neither raises nor interprets the resulting conflict. The provider raises
 * it when a versioned row is flushed against a changed database row, and the shared advice
 * {@code com.carddemo.common.error.GlobalExceptionHandler} already answers it with HTTP 409, matching on
 * a fully-qualified type name while walking the cause chain because the shared library has no
 * persistence types on its classpath. Defining, wrapping or re-mapping that failure here would create a
 * second answer to a question already answered once. Note that the reference fuses two things this
 * split keeps apart: its conflict condition names sit on {@code WS-RETURN-MSG PIC X(75)}, declared at
 * L479 with its condition family spanning L480 to L528, so setting the conflict condition at L521 to
 * L522 simultaneously signals the conflict and loads the operator-visible text. Here the exception
 * carries the signal and the shared error body carries the text.</p>
 *
 * <h2>Shape of this class</h2>
 *
 * <p>Alternatives Considered: declaring this type as a Java record. Rejected on two independent counts.
 * The provider requires a no-argument constructor and non-final members to materialise and to manage a
 * row, which a record cannot offer; and the documentation gate is configured with
 * {@code allowMissingParamTags} false, so a record would additionally demand a parameter tag for every
 * component on the type itself, which is a worse place to document a column than the member.</p>
 *
 * <p>Alternatives Considered: generating the accessors with an annotation processor such as Lombok.
 * Rejected because a generated accessor cannot carry the documentation the project's Explainability rule
 * requires of every method, and the gate is configured with {@code allowMissingPropertyJavadoc} false
 * and an empty {@code allowedAnnotations}, so generated members would fail it rather than be waived.
 * Writing the accessors out is the cost of having each one documented.</p>
 *
 * <p>Alternatives Considered: modelling the customer and the card cross-reference as associations from
 * this entity. Rejected, and the absence is deliberate rather than an omission. The reference record
 * carries no customer identifier at all, so there is nothing here to join on: the cross-reference record
 * at {@code app/cpy/CVACT03Y.cpy}, whose {@code XREF-CUST-ID PIC 9(09)} and
 * {@code XREF-ACCT-ID PIC 9(11)} sit at L6 and L7, is the only path between an account and a customer,
 * and {@code app/cbl/COACTVWC.cbl} reaches all three rows as three separate keyed reads at L727 to
 * L728, L776 to L777 and L826 to L827 rather than by navigation. Nor is a foreign key added: the
 * reference asserts referential integrity explicitly where it wants it, and the one place it does so is
 * another context's category table at {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L6 to L7,
 * with nothing of the kind in the account context, whose four file definitions in
 * {@code app/csd/CARDDEMO.CSD} are each declared with no recovery and no journalling. Adding a
 * constraint the reference does not have would refuse rows the reference accepts, which is a
 * behavioural change and not a structural one.</p>
 *
 * <p>Assumptions: nothing is imported from {@code com.carddemo.common} and that is a decision rather
 * than an oversight. Converting an amount to the boundary money type belongs to the {@code mapper}
 * package, this record declares no 26-character timestamp so the shared timestamp formatter has no
 * subject here, and the error and pagination types belong to the layers that answer requests. The
 * imports are consequently the persistence annotations, the two exact value types and
 * {@link Objects}.</p>
 */
@Entity
@Table(name = "accounts")
public class Account {

    /**
     * The account identifier, {@code ACCT-ID PIC 9(11)} at {@code CVACT01Y.cpy} L5.
     *
     * <p>Assumptions: this key is assigned from the source data and is never generated, which is why no
     * generation strategy is declared. The reference numbers accounts from {@code 00000000001} upward
     * -- the seed file holds fifty of them, numbered 1 to 50 -- and the migration loads each key as it
     * stands. A generation strategy would overwrite real identity with a sequence value and break every
     * cross-reference that points at it. The column is marked not updatable because a primary key that
     * could be reassigned would move a row's identity underneath the rows referring to it.</p>
     */
    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /**
     * Whether the account is active, {@code ACCT-ACTIVE-STATUS PIC X(01)} at {@code CVACT01Y.cpy} L6.
     *
     * <p>Assumptions: the domain is {@code 'Y'} or {@code 'N'} and the migration constrains it, even
     * though the copybook itself declares only a single character and carries no condition names to
     * narrow it. The evidence is in the programs: {@code app/cbl/COACTUPC.cbl} L503 to L504 declares the
     * operator message stating the status must be Y or N, L1473 routes the field through the shared
     * yes-or-no edit, and all fifty seeded records carry {@code 'Y'}. That is enough to justify the
     * check constraint the migration declares and is deliberately the only value domain asserted on
     * this table -- there is no citable domain for the postal code or the group identifier, so no
     * constraint is invented for either.</p>
     *
     * <p>Alternatives Considered: carrying this as a boolean. Rejected because the stored domain is a
     * character, and a boolean would have to invent a mapping for any third value the data turns out to
     * hold rather than letting the constraint reject it. The reference also uses a low-values sentinel
     * at {@code COACTUPC.cbl} L1067 to mean not supplied on the screen, which is a screen-validation
     * state and not a stored one, so it is not modelled here.</p>
     */
    // WHY : Assumptions: length alone would make Hibernate infer VARCHAR(1) while V1__account.sql
    //       declares CHAR(1), and the two disagree about trailing blanks. The provider-level JDBC
    //       type code states the fixed-character binding without a columnDefinition, which is a
    //       DDL-implying attribute this DDL-passive mapping may not declare.
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "active_status", length = 1, nullable = false)
    private String activeStatus;

    /**
     * The posted balance, {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code CVACT01Y.cpy} L7.
     *
     * <p>Assumptions: precision 12 and scale 2 come straight from that picture clause, ten integer
     * digits plus two fractional ones. This field is the normative exemplar for the other four amounts
     * on this entity; the class documentation records why every one of them is exact decimal and never
     * IEEE-754 binary floating point.</p>
     */
    @Column(name = "curr_bal", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentBalance;

    /**
     * The credit limit an authorization is weighed against, {@code ACCT-CREDIT-LIMIT PIC S9(10)V99} at
     * {@code CVACT01Y.cpy} L8.
     *
     * <p>Assumptions: exact decimal at scale 2, for the reason recorded on the balance above. This
     * value is compared against a balance rather than merely reported, so an inexact representation
     * would move the boundary of that comparison rather than only its display.</p>
     */
    @Column(name = "credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal creditLimit;

    /**
     * The cash credit limit, {@code ACCT-CASH-CREDIT-LIMIT PIC S9(10)V99} at {@code CVACT01Y.cpy} L9.
     *
     * <p>Assumptions: exact decimal at scale 2, for the reason recorded on the balance above.</p>
     */
    @Column(name = "cash_credit_limit", nullable = false, precision = 12, scale = 2)
    private BigDecimal cashCreditLimit;

    /**
     * When the account was opened, {@code ACCT-OPEN-DATE PIC X(10)} at {@code CVACT01Y.cpy} L10.
     *
     * <p>Assumptions: a real date type over a character field, proven behaviour-preserving by the
     * reference's own byte slicing of this field at {@code COACTUPC.cbl} L4127 to L4129, which skips
     * the hyphen positions. The class documentation records the full argument.</p>
     */
    @Column(name = "open_date", nullable = false)
    private LocalDate openDate;

    /**
     * When the account expires, the corrected spelling of {@code ACCT-EXPIRAION-DATE PIC X(10)} at
     * {@code CVACT01Y.cpy} L11.
     *
     * <p>Refactoring Rationale: this member carries both of the file's transformations at once, being
     * the renamed field and one of the three true dates. The class documentation records where the
     * misspelling recurs in the reference and where the rename is registered.</p>
     */
    @Column(name = "expiration_date", nullable = false)
    private LocalDate expirationDate;

    /**
     * When the account was last reissued, {@code ACCT-REISSUE-DATE PIC X(10)} at
     * {@code CVACT01Y.cpy} L12.
     *
     * <p>Assumptions: a real date type, on the same evidence as the open date; the reference slices
     * this field at {@code COACTUPC.cbl} L4135 to L4137.</p>
     */
    @Column(name = "reissue_date", nullable = false)
    private LocalDate reissueDate;

    /**
     * Credits posted in the current cycle, {@code ACCT-CURR-CYC-CREDIT PIC S9(10)V99} at
     * {@code CVACT01Y.cpy} L13.
     *
     * <p>Assumptions: exact decimal at scale 2, for the reason recorded on the balance above.</p>
     */
    @Column(name = "curr_cyc_credit", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentCycleCredit;

    /**
     * Debits posted in the current cycle, {@code ACCT-CURR-CYC-DEBIT PIC S9(10)V99} at
     * {@code CVACT01Y.cpy} L14.
     *
     * <p>Assumptions: exact decimal at scale 2, for the reason recorded on the balance above.</p>
     */
    @Column(name = "curr_cyc_debit", nullable = false, precision = 12, scale = 2)
    private BigDecimal currentCycleDebit;

    /**
     * The account's postal code, {@code ACCT-ADDR-ZIP PIC X(10)} at {@code CVACT01Y.cpy} L15.
     *
     * <p>Assumptions: a character member and never a date, despite sharing the {@code PIC X(10)}
     * picture of the three dates above. The seeded value is {@code A000000000} in all fifty records and
     * begins with a letter. The class documentation records the export-only evidence in full.</p>
     */
    // WHY : Assumptions: the column is CHAR(10) in V1__account.sql, so a five-digit postal code is
    //       stored blank-padded to ten. Left as VARCHAR the padded stored value and an unpadded
    //       parameter would be different strings under PostgreSQL's text comparison rules, so a
    //       lookup would silently miss rather than fail loudly.
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "addr_zip", length = 10, nullable = false)
    private String addressZip;

    /**
     * The disclosure group the interest rate is looked up under, {@code ACCT-GROUP-ID PIC X(10)} at
     * {@code CVACT01Y.cpy} L16.
     *
     * <p>Assumptions: a character member and never a date, and an un-enforced reference to a table
     * another context owns rather than a constrained one. The class documentation records both the
     * disclosure-group evidence and why no foreign key is declared.</p>
     */
    // WHY : Assumptions: this is the join side of the disclosure-group rate lookup, and CHAR is
    //       load-bearing for it: the seeded DEFAULT group is stored blank-padded to ten characters,
    //       and under VARCHAR semantics 'DEFAULT' would not equal the stored value. A miss there
    //       produces no interest and raises nothing, so the binding is fixed here rather than left
    //       to every call site to pad correctly.
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "group_id", length = 10, nullable = false)
    private String groupId;

    /**
     * The optimistic-concurrency counter, which has no counterpart in the copybook.
     *
     * <p>Refactoring Rationale: this is the only member below that maps no reference field, and it is
     * present because the reference already performs the equivalent check by hand across the gap
     * between screen turns. The class documentation records the before-image evidence, the comparison
     * paragraph, and the respect in which this counter is stricter than that comparison. It is a
     * primitive rather than a boxed type because the provider always supplies a value and the column is
     * declared with a default of zero, so there is no absent state to represent.</p>
     */
    // WHY : Assumptions: this member is READ-ONLY to application code -- there is a getter and
    //       deliberately no setter. The counter belongs to the persistence provider: it reads it on
    //       load, compares it on flush and advances it on a successful update, which is the whole
    //       mechanism by which a concurrent change is detected. A setter let application code assign
    //       it, and the two ways that goes wrong are both silent. Assigning the value just read makes
    //       the comparison always succeed, so the concurrent-change detection is disabled while the
    //       column still looks like a version. Assigning anything else makes a correct update fail as
    //       though someone else had written the row. Refactoring Rationale: a public setVersion(long)
    //       existed here and had no caller anywhere in the reactor, so removing it costs nothing and
    //       closes both routes. The client-supplied precondition travels in the If-Match header and is
    //       compared by the service, never assigned onto the entity.
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the provider requires a no-argument constructor in order to materialise a row,
     * and this one is {@code protected} rather than {@code public} so that application code cannot
     * construct a half-built account by accident and then persist it. The provider reaches a
     * {@code protected} constructor reflectively, so narrowing the visibility costs nothing.</p>
     */
    protected Account() {
    }

    /**
     * Creates an account master row from a complete set of reference field values.
     *
     * <p>Assumptions: every parameter is required and none is defaulted, because the migration marks
     * every column {@code NOT NULL} and the reference record declares no optional field in this set.
     * Rejecting an absent value here rather than at flush time is deliberate: a constraint violation
     * raised by the database names the column but not the caller, so the failure would surface far from
     * the omission that caused it. The version counter is deliberately not a parameter -- the provider
     * owns it, and accepting one here would invite a caller to fabricate a concurrency token.</p>
     *
     * @param accountId the account identifier, assigned from the source data; must not be {@code null}
     * @param activeStatus the one-character active indicator, {@code 'Y'} or {@code 'N'}; must not be
     *     {@code null}
     * @param currentBalance the posted balance as an exact decimal at scale 2; must not be {@code null}
     * @param creditLimit the credit limit as an exact decimal at scale 2; must not be {@code null}
     * @param cashCreditLimit the cash credit limit as an exact decimal at scale 2; must not be
     *     {@code null}
     * @param openDate the date the account was opened; must not be {@code null}
     * @param expirationDate the date the account expires; must not be {@code null}
     * @param reissueDate the date the account was last reissued; must not be {@code null}
     * @param currentCycleCredit credits posted in the current cycle as an exact decimal at scale 2;
     *     must not be {@code null}
     * @param currentCycleDebit debits posted in the current cycle as an exact decimal at scale 2; must
     *     not be {@code null}
     * @param addressZip the ten-character postal code, which is not a date; must not be {@code null}
     * @param groupId the ten-character disclosure-group key, which is not a date; must not be
     *     {@code null}
     * @throws NullPointerException if any argument is {@code null}
     */
    public Account(Long accountId, String activeStatus, BigDecimal currentBalance,
            BigDecimal creditLimit, BigDecimal cashCreditLimit, LocalDate openDate,
            LocalDate expirationDate, LocalDate reissueDate, BigDecimal currentCycleCredit,
            BigDecimal currentCycleDebit, String addressZip, String groupId) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.activeStatus = Objects.requireNonNull(activeStatus, "activeStatus must not be null");
        this.currentBalance = Objects.requireNonNull(currentBalance, "currentBalance must not be null");
        this.creditLimit = Objects.requireNonNull(creditLimit, "creditLimit must not be null");
        this.cashCreditLimit =
                Objects.requireNonNull(cashCreditLimit, "cashCreditLimit must not be null");
        this.openDate = Objects.requireNonNull(openDate, "openDate must not be null");
        this.expirationDate = Objects.requireNonNull(expirationDate, "expirationDate must not be null");
        this.reissueDate = Objects.requireNonNull(reissueDate, "reissueDate must not be null");
        this.currentCycleCredit =
                Objects.requireNonNull(currentCycleCredit, "currentCycleCredit must not be null");
        this.currentCycleDebit =
                Objects.requireNonNull(currentCycleDebit, "currentCycleDebit must not be null");
        this.addressZip = Objects.requireNonNull(addressZip, "addressZip must not be null");
        this.groupId = Objects.requireNonNull(groupId, "groupId must not be null");
    }

    /**
     * Returns the account identifier.
     *
     * @return the account identifier, never {@code null} on a persisted instance
     */
    public Long getAccountId() {
        return this.accountId;
    }

    /**
     * Returns the one-character active indicator.
     *
     * @return the active status, never {@code null} on a persisted instance
     */
    public String getActiveStatus() {
        return this.activeStatus;
    }

    /**
     * Returns the posted balance.
     *
     * @return the current balance as an exact decimal, never {@code null} on a persisted instance
     */
    public BigDecimal getCurrentBalance() {
        return this.currentBalance;
    }

    /**
     * Returns the credit limit an authorization is weighed against.
     *
     * @return the credit limit as an exact decimal, never {@code null} on a persisted instance
     */
    public BigDecimal getCreditLimit() {
        return this.creditLimit;
    }

    /**
     * Returns the cash credit limit.
     *
     * @return the cash credit limit as an exact decimal, never {@code null} on a persisted instance
     */
    public BigDecimal getCashCreditLimit() {
        return this.cashCreditLimit;
    }

    /**
     * Returns the date the account was opened.
     *
     * @return the open date, never {@code null} on a persisted instance
     */
    public LocalDate getOpenDate() {
        return this.openDate;
    }

    /**
     * Returns the date the account expires.
     *
     * @return the expiration date, never {@code null} on a persisted instance
     */
    public LocalDate getExpirationDate() {
        return this.expirationDate;
    }

    /**
     * Returns the date the account was last reissued.
     *
     * @return the reissue date, never {@code null} on a persisted instance
     */
    public LocalDate getReissueDate() {
        return this.reissueDate;
    }

    /**
     * Returns credits posted in the current cycle.
     *
     * @return the current-cycle credit total as an exact decimal, never {@code null} on a persisted
     *     instance
     */
    public BigDecimal getCurrentCycleCredit() {
        return this.currentCycleCredit;
    }

    /**
     * Returns debits posted in the current cycle.
     *
     * @return the current-cycle debit total as an exact decimal, never {@code null} on a persisted
     *     instance
     */
    public BigDecimal getCurrentCycleDebit() {
        return this.currentCycleDebit;
    }

    /**
     * Returns the account's postal code, which is a character value and not a date.
     *
     * @return the ten-character postal code, never {@code null} on a persisted instance
     */
    public String getAddressZip() {
        return this.addressZip;
    }

    /**
     * Returns the disclosure group the interest rate is looked up under.
     *
     * @return the ten-character group identifier, never {@code null} on a persisted instance
     */
    public String getGroupId() {
        return this.groupId;
    }

    /**
     * Returns the optimistic-concurrency counter.
     *
     * @return the version, zero on a row that has never been updated
     */
    public long getVersion() {
        return this.version;
    }


    // WHY : Assumptions: the persistence annotations above sit on the fields, so the provider uses
    //       field access and never calls an accessor declared here. That is what makes validating in
    //       these mutators safe -- a null check in a mutator cannot interfere with materialising a row,
    //       because materialising a row does not go through them. They exist for the update path the
    //       account context owns, the migrated form of app/cbl/COACTUPC.cbl, where a managed entity is
    //       mutated inside a transaction and the version member above lets the flush refuse a write
    //       whose basis moved underneath it.
    // WHY : Alternatives Considered: leaving this entity immutable and expressing an update as a fresh
    //       instance carrying the same identifier. Rejected, because the concurrency check depends on
    //       the version counter travelling with the row that was actually read; rebuilding the instance
    //       puts the caller in charge of copying that counter across, and a caller that omitted it
    //       would silently obtain a write that overwrites a concurrent change rather than refusing it.
    //       Mutating the managed instance keeps the counter where the provider put it.

    /**
     * Replaces the account identifier.
     *
     * <p>Assumptions: the identifier is assigned from source data before the row is first persisted,
     * which is the only point at which setting it is meaningful. The column is declared not updatable,
     * so the provider will not carry a later change to an already-persisted row through to the
     * database.</p>
     *
     * @param accountId the account identifier to assign; must not be {@code null}
     * @throws NullPointerException if {@code accountId} is {@code null}
     */
    public void setAccountId(Long accountId) {
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
    }

    /**
     * Replaces the one-character active indicator.
     *
     * @param activeStatus the active status to assign, {@code 'Y'} or {@code 'N'}; must not be
     *     {@code null}
     * @throws NullPointerException if {@code activeStatus} is {@code null}
     */
    public void setActiveStatus(String activeStatus) {
        this.activeStatus = Objects.requireNonNull(activeStatus, "activeStatus must not be null");
    }

    /**
     * Replaces the posted balance.
     *
     * @param currentBalance the balance to assign, an exact decimal at scale 2; must not be
     *     {@code null}
     * @throws NullPointerException if {@code currentBalance} is {@code null}
     */
    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = Objects.requireNonNull(currentBalance, "currentBalance must not be null");
    }

    /**
     * Replaces the credit limit an authorization is weighed against.
     *
     * @param creditLimit the credit limit to assign, an exact decimal at scale 2; must not be
     *     {@code null}
     * @throws NullPointerException if {@code creditLimit} is {@code null}
     */
    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = Objects.requireNonNull(creditLimit, "creditLimit must not be null");
    }

    /**
     * Replaces the cash credit limit.
     *
     * @param cashCreditLimit the cash credit limit to assign, an exact decimal at scale 2; must not be
     *     {@code null}
     * @throws NullPointerException if {@code cashCreditLimit} is {@code null}
     */
    public void setCashCreditLimit(BigDecimal cashCreditLimit) {
        this.cashCreditLimit =
                Objects.requireNonNull(cashCreditLimit, "cashCreditLimit must not be null");
    }

    /**
     * Replaces the date the account was opened.
     *
     * @param openDate the open date to assign; must not be {@code null}
     * @throws NullPointerException if {@code openDate} is {@code null}
     */
    public void setOpenDate(LocalDate openDate) {
        this.openDate = Objects.requireNonNull(openDate, "openDate must not be null");
    }

    /**
     * Replaces the date the account expires.
     *
     * @param expirationDate the expiration date to assign; must not be {@code null}
     * @throws NullPointerException if {@code expirationDate} is {@code null}
     */
    public void setExpirationDate(LocalDate expirationDate) {
        this.expirationDate = Objects.requireNonNull(expirationDate, "expirationDate must not be null");
    }

    /**
     * Replaces the date the account was last reissued.
     *
     * @param reissueDate the reissue date to assign; must not be {@code null}
     * @throws NullPointerException if {@code reissueDate} is {@code null}
     */
    public void setReissueDate(LocalDate reissueDate) {
        this.reissueDate = Objects.requireNonNull(reissueDate, "reissueDate must not be null");
    }

    /**
     * Replaces the credits posted in the current cycle.
     *
     * @param currentCycleCredit the current-cycle credit total to assign, an exact decimal at scale 2;
     *     must not be {@code null}
     * @throws NullPointerException if {@code currentCycleCredit} is {@code null}
     */
    public void setCurrentCycleCredit(BigDecimal currentCycleCredit) {
        this.currentCycleCredit =
                Objects.requireNonNull(currentCycleCredit, "currentCycleCredit must not be null");
    }

    /**
     * Replaces the debits posted in the current cycle.
     *
     * @param currentCycleDebit the current-cycle debit total to assign, an exact decimal at scale 2;
     *     must not be {@code null}
     * @throws NullPointerException if {@code currentCycleDebit} is {@code null}
     */
    public void setCurrentCycleDebit(BigDecimal currentCycleDebit) {
        this.currentCycleDebit =
                Objects.requireNonNull(currentCycleDebit, "currentCycleDebit must not be null");
    }

    /**
     * Replaces the account's postal code.
     *
     * <p>Assumptions: the value is a character string and never a date, so no date parsing is applied
     * to it on the way in. The seeded value begins with a letter, which a date parser would reject.</p>
     *
     * @param addressZip the ten-character postal code to assign; must not be {@code null}
     * @throws NullPointerException if {@code addressZip} is {@code null}
     */
    public void setAddressZip(String addressZip) {
        this.addressZip = Objects.requireNonNull(addressZip, "addressZip must not be null");
    }

    /**
     * Replaces the disclosure group the interest rate is looked up under.
     *
     * <p>Assumptions: no validation against the disclosure-group table happens here. That table
     * belongs to another bounded context in another schema, so this member is an un-enforced reference
     * and the reader that resolves it owns the fallback when the key is absent.</p>
     *
     * @param groupId the ten-character disclosure-group key to assign; must not be {@code null}
     * @throws NullPointerException if {@code groupId} is {@code null}
     */
    public void setGroupId(String groupId) {
        this.groupId = Objects.requireNonNull(groupId, "groupId must not be null");
    }


    /**
     * Compares this account with another object for equality by account identifier alone.
     *
     * <p>Alternatives Considered: comparing every mapped member, as a generated implementation would.
     * Rejected, because eleven of the thirteen are mutable and the update path exists precisely to
     * change them: an account placed in a hash-based collection and then edited would no longer be
     * found, since its hash would have moved while it sat in the collection. Comparing the identifier
     * alone keeps equality stable for the whole lifetime of the row, which is the property a collection
     * depends on.</p>
     *
     * <p>Assumptions: the identifier is assigned rather than generated, so it is present from
     * construction onward and not only after the first flush. That is what makes an identifier-based
     * comparison usable here when it would be unsafe for an entity whose key is issued by the database:
     * there is no window in which a persistent instance has no identifier to compare.</p>
     *
     * <p>Trade-offs: two instances that both carry no identifier compare unequal unless they are the
     * same object. The only way to reach that state is the provider's no-argument constructor before it
     * populates the row, so the case does not arise for application code, which cannot obtain an
     * instance without supplying an identifier. The exact-class test rather than an
     * {@code instanceof} test is deliberate for the same reason it is conventional: it keeps the
     * relation symmetric.</p>
     *
     * @param other the object to compare with; may be {@code null}
     * @return {@code true} if {@code other} is an account with an equal identifier, {@code false}
     *     otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return Objects.equals(this.accountId, ((Account) other).accountId);
    }

    /**
     * Returns a hash code derived from the account identifier alone.
     *
     * <p>Assumptions: this is computed from exactly the member the equality comparison uses, which is
     * the contract between the two, and from no mutable member, so a row's hash does not move when the
     * update path edits it.</p>
     *
     * @return the hash code of the account identifier, or zero when no identifier has been assigned
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.accountId);
    }

    /**
     * Renders this row for a log line or a diagnostic, disclosing no protected value at all.
     *
     * <p>Refactoring Rationale: this rendering carried the ACCOUNT IDENTIFIER, on the reasoning that
     * the identifier says which row and is not account detail. That reasoning is refuted by the
     * sensitive-data logging contract in {@code docs/architecture/observability.md}, which names account
     * identifiers explicitly among the prohibited values and covers persistence-bound values as a class.
     * The rule that section states has three parts, and the first is decisive here: a prohibited value
     * is OMITTED and not abbreviated, because abbreviating a protected value is masking and masking has
     * exactly one owner per bounded context, in that context's {@code mapper} package. So the identifier
     * is gone rather than shortened, and what remains is the identity the same section admits -- a status
     * code, a date and a version counter.</p>
     *
     * <p>Trade-offs: a log line naming this row can no longer be joined to a specific account by
     * identifier, and that is a real cost rather than a nominal one. It is paid down by the correlation
     * identifier the shared kernel's request filter already puts on every request-scoped line and, for
     * batch work, by the step ledger -- both of which locate an event without naming a protected value.
     * The alternative was a keyed opaque token in place of the identifier, and the same section rejects
     * it for this position specifically: this method takes no argument and the persistence provider
     * instantiates this class, so no tokeniser could be handed to it, and reaching one through static
     * mutable state would make a diagnostic method depend on start-up ordering.</p>
     *
     * <p>Assumptions: the five amounts and the postal code were already withheld and remain so. Nothing
     * in this rendering is parsed by anything, and it is not the fixed-width form used for parity
     * comparison, so narrowing it cannot disturb any compared output.</p>
     *
     * @return a rendering carrying the status, the expiry and the version and no identifier or amount,
     *     never {@code null}
     */
    @Override
    public String toString() {
        return "Account[activeStatus=" + this.activeStatus
                + ", expirationDate=" + this.expirationDate
                + ", version=" + this.version + ']';
    }
}
