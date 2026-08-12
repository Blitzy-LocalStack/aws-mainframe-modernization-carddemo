package com.carddemo.batch.domain;

import com.carddemo.common.money.Money;
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
 * The account master row a batch job reads, adjusts and rewrites.
 *
 * <p>This is the migrated form of {@code 01 ACCOUNT-RECORD}, declared at
 * {@code app/cpy/CVACT01Y.cpy} lines 4 to 17, whose own line 2 describes the record as
 * {@code RECLN 300}. Twelve named fields become twelve columns; the trailing {@code FILLER}
 * becomes nothing. Two batch programs govern what this type has to be able to express:
 * {@code app/cbl/CBTRN02C.cbl} posts a day's transactions against it, and
 * {@code app/cbl/CBACT04C.cbl} accrues interest onto it.</p>
 *
 * <h2>The derivation, with the byte arithmetic that checks it</h2>
 *
 * <p>Offsets are zero-based and are recorded because they are the audit trail for the mapping:
 * every column below can be traced back to a byte range of the 300-byte record.</p>
 *
 * <pre>
 * offset  bytes  copybook field (line)         PICTURE     column             SQL type
 *      0     11  ACCT-ID (L5)                  9(11)       account_id         BIGINT
 *     11      1  ACCT-ACTIVE-STATUS (L6)       X(01)       active_status      CHAR(1)
 *     12     12  ACCT-CURR-BAL (L7)            S9(10)V99   curr_bal           NUMERIC(12,2)
 *     24     12  ACCT-CREDIT-LIMIT (L8)        S9(10)V99   credit_limit       NUMERIC(12,2)
 *     36     12  ACCT-CASH-CREDIT-LIMIT (L9)   S9(10)V99   cash_credit_limit  NUMERIC(12,2)
 *     48     10  ACCT-OPEN-DATE (L10)          X(10)       open_date          DATE
 *     58     10  ACCT-EXPIRAION-DATE (L11)     X(10)       expiration_date    DATE
 *     68     10  ACCT-REISSUE-DATE (L12)       X(10)       reissue_date       DATE
 *     78     12  ACCT-CURR-CYC-CREDIT (L13)    S9(10)V99   curr_cyc_credit    NUMERIC(12,2)
 *     90     12  ACCT-CURR-CYC-DEBIT (L14)     S9(10)V99   curr_cyc_debit     NUMERIC(12,2)
 *    102     10  ACCT-ADDR-ZIP (L15)           X(10)       addr_zip           CHAR(10)
 *    112     10  ACCT-GROUP-ID (L16)           X(10)       group_id           CHAR(10)
 *    122    178  FILLER (L17)                  X(178)      dropped            none
 * </pre>
 *
 * <p>The last row is what makes the mapping checkable: the named fields end at offset 122, the
 * {@code FILLER} occupies the remaining 178 bytes, and 122 plus 178 is 300, which is the record
 * length the copybook declares. The same total is confirmed from a second, independent place --
 * the file description at {@code app/cbl/CBACT04C.cbl} lines 85 to 87 splits the record into
 * {@code FD-ACCT-ID PIC 9(11)} and {@code FD-ACCT-DATA PIC X(289)}, and 11 plus 289 is also
 * 300. Two derivations agreeing is what allows every offset above to be relied upon rather than
 * recomputed by the next reader.</p>
 *
 * <p>Assumptions: the {@code FILLER} is dropped rather than mapped, under the migration plan's
 * transformation rule T1, because trailing {@code FILLER} in a fixed-length record is padding to
 * the declared length and carries no value a program reads or writes. No statement in either
 * governing program references it. Its width is recorded in the table above anyway, so that a
 * reader who has to reconstruct the 300-byte form -- which the fixed-width emitter in
 * {@code com.carddemo.common.codec.FixedWidthCodec} does, because the committed expectation files
 * are compared byte for byte -- can still see how many pad bytes the record needs and where they
 * begin.</p>
 *
 * <h2>The table is not this module's to own</h2>
 *
 * <p>{@code account.accounts} belongs to {@code account-service}. This module reaches it under a
 * grant that is narrower than the schema: {@code data-migration/sql/V0__schemas_and_roles.sql}
 * grants {@code SELECT} across the {@code account} schema and grants {@code UPDATE} at line 779
 * on {@code account.accounts} by name and on nothing else. There is no {@code INSERT} and no
 * {@code DELETE}. This type is therefore read-modify-write only: a batch job loads a row that
 * {@code account-service} or the extract-transform-load path created, adjusts the three members
 * the baseline adjusts, and rewrites it.</p>
 *
 * <p>Assumptions: the migration that creates {@code account.accounts} is a planned artifact of the
 * migration plan and is not a file present beside this one. {@code account-service} owns it at
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql}, and a reader who
 * looks for that path and does not find it should read the dependency as declared rather than the
 * reference as broken: {@code data-migration/sql/V0__schemas_and_roles.sql} creates the
 * {@code account} schema at line 502, records the ownership map at line 20, and guards its own
 * {@code UPDATE} grant with a {@code to_regclass} test that raises a notice naming the statement to
 * re-run while the table is absent. The consequence is stated in this module's
 * {@code application.yml}, which sets {@code ddl-auto: validate} deliberately: a task started before
 * the owning context has applied that migration fails its schema check at startup with nothing
 * half-applied, which for a nightly chain is the better failure and is the accepted ordering
 * dependency rather than a defect in this mapping.</p>
 *
 * <p>That grant exists so the posting unit of work stays one commit. The posting paragraph
 * performs {@code 2700-UPDATE-TCATBAL} at {@code app/cbl/CBTRN02C.cbl} line 440, then
 * {@code 2800-UPDATE-ACCOUNT-REC} at line 441, then {@code 2900-WRITE-TRANSACTION-FILE} at line
 * 442, and reaches line 444 with all three applied or none. The migration plan records the
 * cross-schema grant at its section 0.4.1.3 as the one documented exception to
 * database-per-service purity in the entire migration, which makes it a bounded concession
 * rather than a pattern to copy. The package charter in {@code package-info.java} states the
 * boundary in full; it is repeated here in short because an annotation in this file writes to a
 * schema this module does not own, and a reader of the annotation should not have to leave the
 * file to learn that.</p>
 *
 * <p>Alternatives Considered: depending on {@code account-service} through Maven so that its
 * {@code Account} entity could be reused instead of declaring a second mapping over one table.
 * Rejected on two independent grounds. A service module importing another service module's
 * {@code domain} package is forbidden by the layering rules that the {@code architecture-rules}
 * Surefire execution enforces through {@code LayeringRulesTest}, so the import would fail a
 * build rather than attract a review comment. Independently of the test, a compile-time
 * dependency between two independently deployable services reintroduces exactly the coupling
 * bounded contexts exist to remove: the two would then have to be versioned and released
 * together, and a column added for one service's reasons would land in the other's build.</p>
 *
 * <p>Alternatives Considered: hoisting one shared entity up into {@code common-lib} so that every
 * module needing an account row could import it. Rejected because {@code common-lib} ships no
 * persistence at all by design -- its POM declares neither the JPA starter nor a driver, so
 * {@code jakarta.persistence} is not on its classpath -- and adding it there would put an entity
 * mapping into the one artifact all nine modules depend on, making a schema change in one
 * bounded context a rebuild of every module. The consequence of both rejections is worth stating
 * as a rule: this module and {@code account-service} agree through the physical schema, and never
 * through code.</p>
 *
 * <p>Trade-offs: two mappings over one table can drift, and no compiler will notice. What is
 * accepted in exchange is that the drift is loud rather than silent -- both mappings are asserted
 * against the same physical schema when their process starts, so a mapping naming a column the
 * schema does not have fails before a row is read. Compile-time agreement between the two is
 * genuinely given up, and that is the price of independent deployability rather than an
 * oversight.</p>
 *
 * <h2>A divergence this type has to carry: the final account is never flushed</h2>
 *
 * <p>Refactoring Rationale: the interest program's final-account flush is unreachable, and the
 * migrated job performs it. The reachability argument is short enough to check in full.
 * {@code app/cbl/CBACT04C.cbl} line 188 opens the control-break loop as
 * {@code PERFORM UNTIL END-OF-FILE = 'Y'}, so the condition is evaluated before each iteration.
 * Line 189 opens the body with {@code IF END-OF-FILE = 'N'}, and the matching {@code ELSE} at
 * lines 219 to 221 performs {@code 1050-UPDATE-ACCOUNT} -- the only path that flushes the last
 * account. Because the loop exits when the flag becomes {@code 'Y'}, the body never runs
 * with the flag set, so line 220 cannot be reached. The interest accumulated for the final
 * account in {@code WS-TOTAL-INT}, declared at line 169 and zeroed per account at line 200, is
 * consequently never added to its balance.</p>
 *
 * <p>The compounding consequence matters more than the missing interest, and is easy to miss:
 * {@code 1050-UPDATE-ACCOUNT} also zeroes both cycle accumulators, at lines 353 and 354, so the
 * final account's cycle buckets are not reset either and the omission carries forward into the
 * next cycle. The Java job performs the flush for every account including the last, and the
 * divergence is registered in {@code docs/architecture/cobol-to-service-traceability.md}.
 * Everything under {@code app/**} is reference-only: the baseline's flush path is unreachable
 * because its loop test precedes its body, the migrated job performs the flush, and the
 * divergence is documented. The baseline itself is not altered, and the migration plan's section
 * 0.2.2 places altering it out of scope.</p>
 *
 * <h2>What this type does not do</h2>
 *
 * <p>It holds state and does not compute. Every monetary adjustment the two programs make is
 * arithmetic, and arithmetic belongs to {@code com.carddemo.common.money.Money}, which owns the
 * two rounding contracts this migration needs and is the single place either is expressed. Date
 * edit rules, including leap-year handling, belong to
 * {@code com.carddemo.common.validation.DateEditValidator}, transcribed from
 * {@code app/cbl/CSUTLDTC.cbl} with {@code app/cpy/CSUTLDPY.cpy} and
 * {@code app/cpy/CSUTLDWY.cpy}. Neither is re-declared here, under the migration plan's
 * transformation rule T2, which is the Java analogue of compiling every program against one
 * copybook include path.</p>
 *
 * <p>Assumptions: no value reaching a member of this type is still in its baseline physical
 * representation. The money members are zoned decimal with an EBCDIC sign overpunch in the
 * dataset, and {@code com.carddemo.common.codec.ZonedDecimalCodec} is the one boundary that
 * decodes them. Nothing here decodes a sign nibble, and no column stores one.</p>
 */
@Entity
// WHY : Alternatives Considered: importing account-service's entity, or moving this mapping into
//       common-lib. The first would create the cross-service domain dependency that the architecture
//       gate forbids; the second would put JPA into a shared kernel that deliberately has no
//       persistence contract. A local mapping keeps agreement at the physical-schema boundary.
// WHY : Assumptions: no member represents the FILLER at app/cpy/CVACT01Y.cpy line 17 because its
//       178 bytes only close the fixed record from offset 122 to length 300. Keeping that padding in
//       the entity would expose a value neither governing program reads or writes.
// WHY : Alternatives Considered: leaving the table name unqualified and letting the pinned
//       connection search path resolve it, which is what
//       services/batch-service/src/main/resources/application.yml sets up at line 416 and what
//       the sibling mappings for this module's own schema rely on. That would in fact resolve
//       correctly under the configured schemas, because no two of the five schemas on that path
//       hold a table of this
//       name. It is declined for THIS mapping because account.accounts is a table this module
//       writes in a schema it does NOT own, and the grant is narrower than the schema:
//       data-migration/sql/V0__schemas_and_roles.sql line 1226 grants UPDATE on this table by
//       name while SELECT is granted schema-wide. Naming the schema on the annotation puts that
//       boundary where a reader of the entity finds it, instead of requiring a trip to the
//       connection configuration to discover that this write crosses a context. The search path
//       remains the resolver for every unqualified mapping in this module; this qualification is
//       additive and displaces nothing.
// WHY : Assumptions: this declaration is DDL-passive, and that is a hard constraint rather than a
//       preference. It names no index, no unique constraint, no column definition and no key
//       generation strategy, because the table, its columns, its types and its indexes are
//       created by account-service's own Flyway migration at
//       services/account-service/src/main/resources/db/migration/V1__account.sql. The
//       persistence provider is never permitted to emit DDL in this module -- its setting is at
//       most an assertion against the shape that already exists -- so the annotations here
//       DESCRIBE that shape rather than request it. An annotation that requested DDL would be
//       asking a module holding a scoped write grant to create or alter another service's
//       schema, which it has no right to do and which surfaces as a permission error naming
//       nothing about the actual mistake.
// WHY : Assumptions: the table this type maps is created by
//       services/account-service/src/main/resources/db/migration/V1__account.sql, which is the
//       authoritative column list for the account schema and which this mapping mirrors rather
//       than defines. Every column name, type and nullability asserted below was verified
//       against that migration applied to a live database, including the BIGINT NOT NULL claim
//       on the version column: the migration declares version BIGINT NOT NULL DEFAULT 0, and the
//       DEFAULT is what lets the bulk load insert a seed row that carries no version field.
// WHY : Alternatives Considered: authoring that migration HERE, in the module that reads the
//       table. Rejected, because it would fix the columns by inference from a consumer's read of
//       them rather than from the owner's declaration, which is the wrong direction of authority
//       and is why the file lives under account-service. The consequence carried here instead is
//       the grant: data-migration/sql/V0__schemas_and_roles.sql gives carddemo_batch SELECT and
//       UPDATE on account.accounts and SELECT alone on account.customers, which is the narrowly
//       scoped privilege the posting and interest jobs need to rewrite an account master and
//       nothing wider. The same boundary is recorded from the schema's own side in
//       docs/architecture/data-model-and-schema-mapping.md.
@Table(name = "accounts", schema = "account")
public class Account {

    // WHY : Alternatives Considered: a JPA association from this identifier to the card
    //       cross-reference, to the card master or to the posted-transaction rows, expressed as a
    //       to-one or to-many mapping with a join column. Rejected on two grounds. It would invite
    //       a lazy traversal across a schema boundary this module holds only a scoped grant on, so
    //       a navigation written innocently in a job would issue a query the grant may not cover
    //       and would fail on a permission error that names nothing about the traversal.
    //       Independently, the baseline never navigates: app/cbl/CBTRN02C.cbl lines 469 to 471
    //       build the category-balance key field by field from XREF-ACCT-ID, the transaction type
    //       code and the category code, and then issue a keyed read. Joining explicitly by
    //       identifier is what the migrated jobs do for the same reason, so the object graph stays
    //       flat and every query a job issues is visible at the query site.
    // WHY : Assumptions: the identifier is the natural key carried in the source record, assigned
    //       by whichever writer created the row, and it is never generated here. No key generation
    //       strategy is declared, and that is deliberate twice over: a generated key would need a
    //       sequence or an identity column in a schema this module may not alter, and it would
    //       replace the very value the cross-reference and category-balance keys are built from,
    //       so a re-keyed account would stop joining to its own rows. Eleven decimal digits
    //       exceed the range of a thirty-two-bit integer, which is why the column is BIGINT and
    //       the member is a Long rather than an Integer.
    @Id
    @Column(name = "account_id")
    private Long accountId;

    // WHY : Alternatives Considered: a Java enum, and the char primitive. The enum was rejected
    //       because the copybook declares no 88-level value set for this field, so its domain is
    //       simply not enumerable from the baseline; inventing a set here would reject data the
    //       baseline accepts and would turn a load of legitimate history into a failure. The char
    //       primitive was rejected because it cannot represent the difference between a blank
    //       stored in the fixed-width source and no value at all, and a one-byte field in a
    //       fixed-length record can genuinely arrive blank. A nullable String preserves both.
    // WHY : Assumptions: length alone would make Hibernate validate this String as VARCHAR(1),
    //       while the authoritative schema contract declares CHAR(1). The JDBC type override
    //       carries the fixed-character binding needed by schema validation without embedding a
    //       database-specific column definition or asking this DDL-passive entity to create DDL.
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "active_status", length = 1)
    private String activeStatus;

    // WHY : Assumptions: the precision is derived, not chosen, and getting it wrong truncates in
    //       silence. app/cpy/CVACT01Y.cpy line 7 declares ACCT-CURR-BAL as PIC S9(10)V99 -- ten
    //       integer digits plus two decimal places, twelve display bytes -- so the column is
    //       NUMERIC(12,2) and the member is a BigDecimal held at scale 2. The contrast with the
    //       transaction side is the load-bearing part: PIC S9(09)V99 is eleven bytes and maps to
    //       NUMERIC(11,2), and three records carry that narrower form -- TRAN-AMT at
    //       app/cpy/CVTRA05Y.cpy line 10, DALYTRAN-AMT at app/cpy/CVTRA06Y.cpy line 10 and
    //       TRAN-CAT-BAL at app/cpy/CVTRA01Y.cpy line 9. The two precisions CROSS at exactly two
    //       sites, both of them additions of a narrower amount into this wider accumulator:
    //       app/cbl/CBTRN02C.cbl line 547 reads ADD DALYTRAN-AMT TO ACCT-CURR-BAL, and
    //       app/cbl/CBACT04C.cbl line 352 reads ADD WS-TOTAL-INT TO ACCT-CURR-BAL, where
    //       WS-TOTAL-INT is declared PIC S9(09)V99 at line 169. Declaring this balance at the
    //       narrower scale would lose the eleventh integer digit at precisely those two lines, on
    //       exactly the accounts large enough to need it.
    // WHY : Alternatives Considered: a binary floating-point type, and an integer count of cents.
    //       The floating-point form is forbidden outright by the migration plan's transformation
    //       rule T3, and the prohibition is an executable assertion in LayeringRulesTest rather
    //       than a review note, because such a type cannot hold an exact cent and a figure wrong
    //       in the cents still looks entirely plausible. The integer-cents encoding was the more
    //       serious candidate and is rejected because the zoned-decimal codec in common-lib
    //       already decodes to a scaled BigDecimal and the committed expectation files compare
    //       formatted decimal output, so an integer intermediate would insert two conversions and
    //       two rounding opportunities into a path that has none.
    // WHY : Assumptions: the PICTURE is SIGNED, and the sign is not a property of this member. In
    //       the dataset it is an EBCDIC overpunch on the low-order digit, and tests/README.md
    //       section 5.2 records that compiling with the default ASCII sign convention silently
    //       corrupts negative balances, which is why the EBCDIC sign flag is mandatory there. The
    //       one place that representation is decoded is
    //       com.carddemo.common.codec.ZonedDecimalCodec, and under transformation rule T2 it is
    //       never re-declared here. By the time a value reaches this member it is a signed decimal
    //       and nothing else.
    // WHY : Assumptions: arithmetic on this member belongs to the service layer through
    //       com.carddemo.common.money.Money, which owns both rounding contracts the migration
    //       needs -- the general half-up reduction, and the truncating mode the interest divide
    //       requires because app/cbl/CBACT04C.cbl lines 464 to 465 carry no ROUNDED phrase and no
    //       statement anywhere in that program does. This type holds state and computes nothing,
    //       so a caller assigns a value that Money has already produced.
    @Column(name = "curr_bal", precision = 12, scale = 2)
    private BigDecimal currBal;

    // WHY : Assumptions: the credit limit is the ceiling the over-limit test compares against, so
    //       it has to be exactly as wide as the balance it is compared with.
    //       app/cbl/CBTRN02C.cbl line 407 reads IF ACCT-CREDIT-LIMIT >= WS-TEMP-BAL, and the
    //       comparison is INCLUSIVE: a projected balance landing exactly on the limit posts, and
    //       only a strictly greater one is rejected with reason 102 and the text
    //       OVERLIMIT TRANSACTION. A narrower column here would change which transactions post at
    //       the boundary rather than merely storing less.
    @Column(name = "credit_limit", precision = 12, scale = 2)
    private BigDecimal creditLimit;

    // WHY : Assumptions: the cash limit is carried at the same precision as the other four
    //       monetary members because app/cpy/CVACT01Y.cpy line 9 declares it PIC S9(10)V99, and
    //       for no other reason. Neither governing batch program reads it -- posting compares the
    //       credit limit and interest accrual touches the balance and the cycle buckets -- so it
    //       is mapped to preserve the record rather than to serve a job, and it is deliberately
    //       given no setter below.
    @Column(name = "cash_credit_limit", precision = 12, scale = 2)
    private BigDecimal cashCreditLimit;

    // WHY : Assumptions: a PIC X(10) member holding a YYYY-MM-DD value becomes a DATE column and
    //       a LocalDate member, under the migration plan's section 0.4.1.3 mapping rule, because
    //       the form is already ISO-ordered and a lexical comparison of two such strings therefore
    //       agrees with a chronological comparison of the dates they denote. That equivalence is
    //       what makes the type change safe rather than merely tidy.
    @Column(name = "open_date")
    private LocalDate openDate;

    // WHY : Refactoring Rationale: app/cpy/CVACT01Y.cpy line 11 declares this field as
    //       ACCT-EXPIRAION-DATE, the baseline spelling, with the letter T absent from the third
    //       syllable; the target column is expiration_date, and the divergence is registered in
    //       docs/architecture/data-model-and-schema-mapping.md alongside the two other renames the
    //       migration makes. The baseline name is carried verbatim into every citation of that
    //       line, including citations sitting beside the target column name, because a citation
    //       whose text has been tidied stops locating the byte range it claims to. Nothing
    //       under app/** is altered: the baseline declares one name, the target column is another,
    //       and the divergence is documented.
    // WHY : Assumptions: the same transposition occurs a second time in the baseline, at
    //       app/cpy/CVACT02Y.cpy line 9, where the card record declares CARD-EXPIRAION-DATE. It is
    //       recorded here so that a reader meeting the pattern twice does not conclude that one of
    //       the two citations is a transcription error introduced by this migration.
    // WHY : Assumptions: this member is where the DATE mapping has to be exactly right, because it
    //       carries the one comparison in the posting path that depends on the lexical-versus-
    //       chronological equivalence. app/cbl/CBTRN02C.cbl line 414 reads
    //       IF ACCT-EXPIRAION-DATE >= DALYTRAN-ORIG-TS (1:10) -- a lexical comparison of this
    //       ten-character expiry against the FIRST TEN BYTES of the daily transaction's X(26)
    //       timestamp. Because that timestamp's form is yyyy-MM-dd followed by a time, its first
    //       ten bytes are exactly yyyy-MM-dd, so comparing one LocalDate with another in Java
    //       reproduces the COBOL outcome exactly. Note the direction of the boundary, which is
    //       easy to invert: the test is >=, so a transaction dated exactly ON the expiry date
    //       POSTS, and only a strictly later date is rejected with reason 103 and the text
    //       TRANSACTION RECEIVED AFTER ACCT EXPIRATION.
    // WHY : Trade-offs: a DATE column cannot hold what a CHAR(10) column could. The baseline field
    //       is ten characters, so it can hold spaces or a malformed value, and the COBOL lexical
    //       comparison would still evaluate such a value rather than failing; a DATE column
    //       refuses it outright. That narrowing is accepted deliberately, for two reasons: the
    //       extract-transform-load path validates and rejects a malformed value at load time, in
    //       data-migration/src/carddemo_migration/readers/account.py, so the value cannot reach
    //       this column; and a DATE column lets the database enforce what the copybook only
    //       implies. It is a documented divergence rather than an oversight, and the place a
    //       blank or malformed date is handled is the loader, not this type.
    // WHY : Assumptions: date edit rules -- range checks, month lengths and leap-year handling --
    //       belong to com.carddemo.common.validation.DateEditValidator, transcribed from
    //       app/cbl/CSUTLDTC.cbl with app/cpy/CSUTLDPY.cpy and app/cpy/CSUTLDWY.cpy. This type
    //       does not re-implement any of them, under transformation rule T2, so there is exactly
    //       one place a leap-year question is answered.
    @Column(name = "expiration_date")
    private LocalDate expirationDate;

    // WHY : Assumptions: the reissue date is mapped for record fidelity and is read by neither
    //       governing batch program, so like the cash limit it is given no setter below. Its
    //       PIC X(10) at app/cpy/CVACT01Y.cpy line 12 carries the same ISO-ordered form as the
    //       other two dates, so it takes the same DATE column and LocalDate member.
    @Column(name = "reissue_date")
    private LocalDate reissueDate;

    // WHY : Assumptions: this accumulator and the debit accumulator below follow a baseline SIGN
    //       CONVENTION that looks wrong and must not be normalised. app/cbl/CBTRN02C.cbl lines 548
    //       to 552 read IF DALYTRAN-AMT >= 0 then ADD DALYTRAN-AMT TO ACCT-CURR-CYC-CREDIT, ELSE
    //       ADD DALYTRAN-AMT TO ACCT-CURR-CYC-DEBIT. The ELSE branch adds an amount that is
    //       NEGATIVE by the condition that selected it, so the debit accumulator accumulates
    //       NEGATIVELY: it is a signed running total, not a magnitude.
    // WHY : Assumptions: the consequence shows up in the over-limit projection, which is why the
    //       convention has to survive. app/cbl/CBTRN02C.cbl lines 403 to 405 compute the projected
    //       balance as ACCT-CURR-CYC-CREDIT minus ACCT-CURR-CYC-DEBIT plus DALYTRAN-AMT. Because
    //       the debit accumulator holds a negative number, SUBTRACTING it ADDS BACK its magnitude.
    //       Storing debits as positive magnitudes instead would invert that term and change which
    //       transactions clear the credit limit -- a behavioural change presenting as an
    //       arithmetic tidy-up. The convention is preserved exactly as the baseline expresses it.
    @Column(name = "curr_cyc_credit", precision = 12, scale = 2)
    private BigDecimal currCycCredit;

    // WHY : Assumptions: both cycle members are CYCLE-SCOPED rather than lifetime-scoped, and a
    //       reader who assumes lifetime totals will misread every value they hold. The interest
    //       run resets them as a side effect of flushing an account: app/cbl/CBACT04C.cbl line 352
    //       adds the accrued interest to the balance and lines 353 and 354 immediately move zero
    //       into ACCT-CURR-CYC-CREDIT and ACCT-CURR-CYC-DEBIT, so the accrual step performs a
    //       billing-cycle reset. That is why the two are mutated below through one operation that
    //       keeps the reset inseparable from the balance it accompanies, rather than through two
    //       independent setters a caller could apply singly.
    @Column(name = "curr_cyc_debit", precision = 12, scale = 2)
    private BigDecimal currCycDebit;

    // WHY : Assumptions: the column is CHAR(10) and not VARCHAR(10), which is the opposite of what
    //       a purely descriptive field would take. The authoritative field-by-field record in
    //       docs/architecture/data-model-and-schema-mapping.md maps this member to CHAR(10) on the
    //       ground that a postal code is a fixed-width code whose width is meaningful, and that
    //       document is what account-service's migration is built from. Aligning to it is
    //       mandatory rather than preferable: this module asserts its mappings against the
    //       physical schema at start-up, so a mapping that described a VARCHAR would either fail
    //       that assertion or, worse, quietly disagree with the schema about padding. Standard
    //       JPA Column metadata cannot express the CHAR-versus-VARCHAR distinction without a
    //       column definition, and a column definition is a DDL-implying attribute this
    //       DDL-passive mapping may not declare. The provider-level JDBC type code therefore
    //       describes the existing fixed-character binding while length retains the width.
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "addr_zip", length = 10)
    private String addrZip;

    // WHY : Assumptions: this member is CHAR(10) because it is one side of a join whose other side
    //       is CHAR(10), and the padding is part of the stored key rather than a formatting
    //       artefact. app/cbl/CBACT04C.cbl line 210 moves ACCT-GROUP-ID straight into the
    //       disclosure-group key field FD-DIS-ACCT-GROUP-ID, which app/cpy/CVTRA02Y.cpy line 6
    //       declares PIC X(10). When the rate lookup misses, line 437 moves the seven-character
    //       literal DEFAULT into that same ten-byte field, so the value actually in play is
    //       space-padded to ten. The seed migration stores it the same way -- V2__seed_reference.sql
    //       inserts the group as the padded ten-character value -- and reference-service declares
    //       reference.disclosure_groups.acct_group_id as CHAR(10) NOT NULL in V1__reference.sql.
    // WHY : Trade-offs: relying on CHAR semantics is what makes the fallback work, and it is worth
    //       naming the alternative that fails. A CHAR comparison in PostgreSQL ignores trailing
    //       blanks, so a predicate written against the unpadded literal still matches the padded
    //       stored value; under VARCHAR the two would be different strings and the lookup would
    //       have to agree on padding at every call site or miss. Missing here is not loud: the
    //       fallback would find nothing and a documented business rule would produce no interest
    //       without raising anything. Both sides of the join are therefore fixed at CHAR(10)
    //       together, and changing one without the other silently breaks the rate lookup.
    // WHY : Assumptions: the JDBC type override is repeated here because this member is the join
    //       side whose CHAR semantics are load-bearing; relying on the override beside addr_zip
    //       would leave Hibernate to infer VARCHAR(10) independently for this field.
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "group_id", length = 10)
    private String groupId;

    // WHY : Refactoring Rationale: the version column is not a new invention layered onto a
    //       baseline that lacked concurrency control -- it expresses natively what the baseline
    //       already implements by hand. app/cbl/COACTUPC.cbl line 669 snapshots the entire
    //       pre-edit record into ACUP-OLD-DETAILS, holding each numeric twice, as a display field
    //       with a numeric REDEFINES over it: ACUP-OLD-CURR-BAL is PIC X(12) at line 675 with
    //       ACUP-OLD-CURR-BAL-N redefining it as PIC S9(10)V99 at lines 676 and 677. It carries a
    //       change flag WS-DATACHANGED-FLAG at line 168, whose condition
    //       DATA-WAS-CHANGED-BEFORE-UPDATE at line 521 reads
    //       "Record changed by some one else. Please review"; and on a failed rewrite it issues
    //       EXEC CICS SYNCPOINT ROLLBACK at lines 4099 to 4101. That is optimistic concurrency,
    //       written across the pseudo-conversational gap. Nothing is lost by expressing it as a
    //       version column, because the read-for-update lock was never held across client
    //       think-time either -- which is precisely why the before-image had to exist.
    // WHY : Assumptions: a conflict here has a different meaning than the same conflict online,
    //       and the difference decides how a job must react. In an online service the condition
    //       surfaces as HTTP 409 carrying the data-changed semantic above. In a batch step it must
    //       FAIL THE STEP so the orchestrator's catch path routes to notification, because the
    //       operation that lost the race is a monetary adjustment and retrying it blindly would
    //       apply the same amount twice. It is also a genuine operational signal rather than
    //       routine contention: the batch window is bracketed by the quiesce and resume states of
    //       the migration plan's section 0.4.1.7, so an online write landing inside it indicates
    //       the read-only flag was not honoured.
    // WHY : Assumptions: the member is the primitive long because the authoritative mapping record
    //       declares the column BIGINT NOT NULL with a Java long. A NOT NULL column can never
    //       deliver an absent value, so the boxed form would add a null state the schema forbids,
    //       and this type is never inserted -- the grant carries UPDATE and SELECT only -- so
    //       there is no unsaved-instance case for a null to mark.
    @Version
    @Column(name = "version")
    private long version;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the provider instantiates a mapped type reflectively through its no-argument
     * constructor and then writes the members directly, so this constructor exists to satisfy that
     * contract and is not an application entry point. It is {@code protected} rather than
     * {@code public} because the provider reaches a non-public constructor without difficulty
     * while application code cannot, which keeps a partially-populated instance -- one with a null
     * identifier, on which equality would be meaningless -- out of reach of a caller who has a
     * fully-specified constructor available instead.</p>
     */
    protected Account() {
        // WHY : Assumptions: the body is deliberately empty because the provider assigns every
        //       member after construction. Initialising anything here would be overwritten on a
        //       hydrate, and initialising the money members to zero would be actively harmful: a
        //       row that failed to populate would then read as a zero balance rather than as an
        //       obviously absent one.
    }

    /**
     * Creates a fully specified account row from values already decoded from the source record.
     *
     * <p>Assumptions: every argument arrives in its target representation, not its baseline one.
     * The monetary arguments are signed decimals that
     * {@code com.carddemo.common.codec.ZonedDecimalCodec} has already decoded from zoned decimal
     * with its sign overpunch, and the date arguments are already parsed. This constructor decodes
     * nothing and validates no business rule.</p>
     *
     * <p>Assumptions: this is not an insert path. The grant this module holds on
     * {@code account.accounts} carries {@code SELECT} and {@code UPDATE} only, so rows originate
     * in {@code account-service} or in the extract-transform-load load; this constructor serves
     * test fixtures and in-memory construction, and a job in normal operation obtains an instance
     * by reading one.</p>
     *
     * <p>Trade-offs: only the identifier is checked. The remaining members are left unguarded even
     * though the columns behind several of them are not nullable, because the schema's own
     * constraints are the authority on nullability and restating them here would create a second
     * validation site that can disagree with the first -- and the one that disagreed would be this
     * one, since it cannot see a constraint introduced by the owning service. The identifier is the
     * exception because equality and hashing below depend on it, so a null there breaks this
     * type's own contract rather than the schema's.</p>
     *
     * @param accountId the eleven-digit account identifier, which becomes this row's primary key;
     *     must not be {@code null}
     * @param activeStatus the one-character active-status code, carried as declared with no domain
     *     imposed on it
     * @param currBal the current outstanding balance, signed, at scale 2
     * @param creditLimit the credit limit the over-limit projection is compared against, at scale 2
     * @param cashCreditLimit the cash credit limit, at scale 2, mapped for record fidelity
     * @param openDate the date the account was opened
     * @param expirationDate the account expiration date, against which a transaction dated equal to
     *     it still posts
     * @param reissueDate the date the account was last reissued
     * @param currCycCredit the signed running total of credits in the current billing cycle
     * @param currCycDebit the signed running total of debits in the current billing cycle, which
     *     accumulates negatively
     * @param addrZip the ten-character postal code
     * @param groupId the ten-character disclosure group identifier used as the rate-lookup key
     * @throws NullPointerException if {@code accountId} is {@code null}
     */
    public Account(
            Long accountId,
            String activeStatus,
            BigDecimal currBal,
            BigDecimal creditLimit,
            BigDecimal cashCreditLimit,
            LocalDate openDate,
            LocalDate expirationDate,
            LocalDate reissueDate,
            BigDecimal currCycCredit,
            BigDecimal currCycDebit,
            String addrZip,
            String groupId) {
        // WHY : Trade-offs: only the identifier is checked here because equality and hashing require
        //       it immediately, while nullability for the remaining columns belongs to the owning
        //       schema. Duplicating every schema constraint in this constructor would create a second
        //       contract that can drift independently.
        this.accountId = Objects.requireNonNull(accountId, "accountId must not be null");
        this.activeStatus = activeStatus;
        this.currBal = currBal;
        this.creditLimit = creditLimit;
        this.cashCreditLimit = cashCreditLimit;
        this.openDate = openDate;
        this.expirationDate = expirationDate;
        this.reissueDate = reissueDate;
        this.currCycCredit = currCycCredit;
        this.currCycDebit = currCycDebit;
        this.addrZip = addrZip;
        this.groupId = groupId;
    }

    /**
     * The eleven-digit identifier that keys this account row.
     *
     * @return the account identifier, or {@code null} on an instance the persistence provider has
     *     not yet populated
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * The one-character active-status code exactly as the source record carries it.
     *
     * @return the active-status code, which may be blank or {@code null} because the baseline
     *     imposes no value set on this field
     */
    public String getActiveStatus() {
        return activeStatus;
    }

    /**
     * The account's current outstanding balance, signed, at scale 2.
     *
     * @return the current balance, which is negative when the account is in credit
     */
    public BigDecimal getCurrBal() {
        return currBal;
    }

    /**
     * The credit limit the over-limit projection is compared against, at scale 2.
     *
     * @return the credit limit, inclusive of its own value for the over-limit test
     */
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    /**
     * The account's cash credit limit, at scale 2.
     *
     * @return the cash credit limit
     */
    public BigDecimal getCashCreditLimit() {
        return cashCreditLimit;
    }

    /**
     * The date this account was opened.
     *
     * @return the open date
     */
    public LocalDate getOpenDate() {
        return openDate;
    }

    /**
     * The account expiration date bounding which transactions may post.
     *
     * @return the expiration date; a transaction dated equal to it still posts, and only a
     *     strictly later one is rejected
     */
    public LocalDate getExpirationDate() {
        return expirationDate;
    }

    /**
     * The date this account was last reissued.
     *
     * @return the reissue date
     */
    public LocalDate getReissueDate() {
        return reissueDate;
    }

    /**
     * The signed running total of credits posted in the current billing cycle, at scale 2.
     *
     * @return the cycle credit accumulator, which is reset when accrued interest is applied
     */
    public BigDecimal getCurrCycCredit() {
        return currCycCredit;
    }

    /**
     * The signed running total of debits posted in the current billing cycle, at scale 2.
     *
     * @return the cycle debit accumulator, which accumulates NEGATIVELY and is therefore not a
     *     magnitude
     */
    public BigDecimal getCurrCycDebit() {
        return currCycDebit;
    }

    /**
     * The ten-character postal code held against this account.
     *
     * @return the postal code, space-padded to ten characters by the fixed-width column
     */
    public String getAddrZip() {
        return addrZip;
    }

    /**
     * The ten-character disclosure group identifier used as the interest rate-lookup key.
     *
     * @return the group identifier, space-padded to ten characters
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * The optimistic-lock version the persistence provider maintains for this row.
     *
     * @return the current version, which increases by one on each successful update
     */
    public long getVersion() {
        return version;
    }

    /**
     * Replaces the current balance with a value the caller has already computed.
     *
     * <p>Assumptions: the addition itself happens in the service layer through
     * {@code com.carddemo.common.money.Money}, which is why this takes a result rather than an
     * addend. It is the assignment half of {@code app/cbl/CBTRN02C.cbl} line 547, where the
     * baseline reads {@code ADD DALYTRAN-AMT TO ACCT-CURR-BAL}; splitting the arithmetic out keeps
     * every rounding contract in one place and leaves this type holding state only.</p>
     *
     * @param newBalance the balance to store, signed and already reduced to scale 2; must not be
     *     {@code null}
     * @throws NullPointerException if {@code newBalance} is {@code null}
     */
    public void setCurrBal(BigDecimal newBalance) {
        // WHY : Assumptions: this method accepts the result rather than an addend so the caller must
        //       perform the monetary operation through Money; accepting an addend here would create a
        //       second arithmetic and rounding boundary inside the entity.
        this.currBal = Objects.requireNonNull(newBalance, "newBalance must not be null");
    }

    /**
     * Replaces the current-cycle credit accumulator with a value the caller has already computed.
     *
     * <p>Assumptions: this is the assignment half of the positive branch at
     * {@code app/cbl/CBTRN02C.cbl} lines 548 and 549, which adds a non-negative transaction amount
     * to the credit accumulator. The caller performs the addition through the shared money type.</p>
     *
     * @param newCycleCredit the cycle credit total to store, at scale 2; must not be {@code null}
     * @throws NullPointerException if {@code newCycleCredit} is {@code null}
     */
    public void setCurrCycCredit(BigDecimal newCycleCredit) {
        // WHY : Assumptions: the caller supplies the already-computed branch result so this entity does
        //       not duplicate the posting sign decision or Money's exact-addition contract.
        this.currCycCredit = Objects.requireNonNull(newCycleCredit, "newCycleCredit must not be null");
    }

    /**
     * Replaces the current-cycle debit accumulator with a value the caller has already computed.
     *
     * <p>Assumptions: this is the assignment half of the negative branch at
     * {@code app/cbl/CBTRN02C.cbl} lines 550 and 551, which adds a negative transaction amount to
     * the debit accumulator. The stored value is therefore expected to be negative or zero, and a
     * caller passing a positive magnitude would invert the over-limit projection at lines 403 to
     * 405. The sign is not normalised here, because normalising it would change which transactions
     * post.</p>
     *
     * @param newCycleDebit the cycle debit total to store, at scale 2, signed as the baseline signs
     *     it; must not be {@code null}
     * @throws NullPointerException if {@code newCycleDebit} is {@code null}
     */
    public void setCurrCycDebit(BigDecimal newCycleDebit) {
        // WHY : Assumptions: no positive-magnitude guard is applied because the baseline debit
        //       accumulator is a negative running total; rejecting a negative value here would invert
        //       the projection that lines 403 to 405 of app/cbl/CBTRN02C.cbl compute.
        this.currCycDebit = Objects.requireNonNull(newCycleDebit, "newCycleDebit must not be null");
    }

    /**
     * Applies an accrued-interest balance and resets both billing-cycle accumulators as one step.
     *
     * <p>This is the assignment form of {@code 1050-UPDATE-ACCOUNT} at
     * {@code app/cbl/CBACT04C.cbl} lines 350 to 356: line 352 adds the accumulated interest to the
     * balance, and lines 353 and 354 immediately move zero into both cycle accumulators. The
     * caller supplies the post-accrual balance because the addition belongs to
     * {@code com.carddemo.common.money.Money}; the reset is performed here.</p>
     *
     * <p>Alternatives Considered: exposing the reset as its own method, or letting a caller reach
     * the two accumulators through the setters above and zero them individually. Both were
     * rejected because they make it possible to apply the interest without the reset, or to reset
     * one accumulator and not the other, and the baseline performs all three assignments together
     * with no path between them. Keeping the three inseparable removes a caller error that would
     * be invisible in the accrual run and would surface a whole cycle later as a cycle total that
     * never cleared.</p>
     *
     * <p>Assumptions: this operation is the one the baseline omits for the final account. The
     * unreachable flush recorded in this type's class documentation means the last account's
     * balance and both of its cycle accumulators are left untouched by the reference program; the
     * migrated job calls this for every account including the last, and the divergence is
     * registered in {@code docs/architecture/cobol-to-service-traceability.md}.</p>
     *
     * @param balanceAfterAccrual the balance to store, already computed by the caller as the prior
     *     balance plus the interest accumulated for this account, at scale 2; must not be
     *     {@code null}
     * @throws NullPointerException if {@code balanceAfterAccrual} is {@code null}
     */
    public void applyAccruedInterestAndResetCycle(BigDecimal balanceAfterAccrual) {
        // WHY : Alternatives Considered: separate balance-application and bucket-reset operations were
        //       rejected because a caller could then apply only part of the line 352 to 354 state
        //       transition. One operation keeps the three assignments indivisible at the call site.
        // WHY : Refactoring Rationale: app/cbl/CBACT04C.cbl line 220 is unreachable because the loop
        //       exits before its ELSE body can observe end-of-file. Keeping this operation available
        //       for the last account applies its accrued balance and prevents both cycle buckets from
        //       carrying into another cycle; the documented divergence leaves app/** unchanged.
        this.currBal =
                Objects.requireNonNull(balanceAfterAccrual, "balanceAfterAccrual must not be null");

        // WHY : Assumptions: the reset target is an exact zero carried at the column's own scale
        //       rather than the unscaled BigDecimal.ZERO, because a value stored at a different
        //       scale compares unequal to a re-read of itself under BigDecimal equality even
        //       though the two are numerically identical. Every other value on this type is held
        //       at scale 2, and a round-trip assertion over the whole row would otherwise fail on
        //       these two members alone.
        // WHY : Alternatives Considered: writing the literal 2 here, or declaring a scale constant
        //       on this type. Both were rejected because the money scale is a shared contract
        //       rather than a property of this entity, and transformation rule T2 puts a shared
        //       contract in exactly one place. Reading it from the shared money type is what keeps
        //       a change to that contract from having to find every entity that guessed it
        //       independently. This is the only use this type makes of that type: it borrows the
        //       scale and performs no arithmetic with it.
        BigDecimal zeroAtColumnScale = BigDecimal.ZERO.setScale(Money.SCALE);
        this.currCycCredit = zeroAtColumnScale;
        this.currCycDebit = zeroAtColumnScale;
    }

    /**
     * Compares this account with another object on the account identifier alone.
     *
     * <p>Alternatives Considered: comparing the business members as well. Rejected because they are
     * mutable -- three of them are mutated by the operations above -- so an instance placed in a
     * hash-based collection would change its own equality and hash mid-transaction and become
     * unfindable in the collection holding it. The identifier is the key the baseline declares at
     * {@code app/cpy/CVACT01Y.cpy} line 5 and the primary key the relational form declares, so it
     * already determines the row uniquely and comparing anything further adds no discrimination.
     * The identifier is also assigned from the source record rather than generated, so it is
     * non-null from construction and does not suffer the transient-null-identity problem a
     * generated key would.</p>
     *
     * <p>Assumptions: the version member is deliberately excluded. It changes on every successful
     * update, so including it would make an instance unequal to itself across a flush and would
     * make two reads of one row unequal whenever one of them had been updated.</p>
     *
     * @param other the object to compare with, which may be {@code null} or of any type
     * @return {@code true} when {@code other} is an account carrying an equal identifier,
     *     {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        // WHY : Assumptions: a pattern match is used rather than an exact class comparison because
        //       the persistence provider may return an instrumented subclass for a lazily-loaded
        //       reference, and a strict class comparison would then report a row as unequal to
        //       itself. No subclass of this type is authored, so widening the test costs nothing.
        if (!(other instanceof Account that)) {
            return false;
        }
        // WHY : Alternatives Considered: mutable balances, cycle totals and the provider-managed
        //       version are excluded because changing any of them while an entity is in a hash-based
        //       collection would make the same database row unfindable there. The assigned identifier
        //       is the only stable discriminator the relational contract needs.
        return Objects.equals(accountId, that.accountId);
    }

    /**
     * Produces a hash consistent with the identifier-only equality above.
     *
     * <p>Assumptions: the value is stable for the life of an instance because the identifier has no
     * setter, which is what makes this type safe to place in a hash-based collection even though
     * three of its other members are mutable. The version member is excluded for the same reason
     * it is excluded from equality.</p>
     *
     * @return the hash of the account identifier, or the hash of an absent value on an instance the
     *     persistence provider has not yet populated
     */
    @Override
    public int hashCode() {
        // WHY : Assumptions: hashing the same assigned identifier used by equals preserves the
        //       equals/hashCode contract while every mutable and provider-managed member changes.
        return Objects.hashCode(accountId);
    }

    /**
     * Renders a short diagnostic summary of this account for a log line or an assertion message.
     *
     * <p>Alternatives Considered: rendering THE ACCOUNT IDENTIFIER, THE CURRENT BALANCE AND THE CREDIT
     * LIMIT, on the reasoning that no member of this record is a primary account number, a card
     * verification value or a national identifier and so nothing here needs protecting. Rejected: the
     * premise is true and the conclusion does not follow from it. The sensitive-data logging contract in
     * {@code docs/architecture/observability.md} names account identifiers explicitly, alongside
     * persistence-bound values as a class, so a balance and a credit limit are covered by that second
     * clause whether or not either is a card number. Reasoning from three examples is not the same as
     * reasoning from the contract, so all three are omitted.</p>
     *
     * <p>Trade-offs: the omission is total rather than partial, and abbreviating the identifier was
     * considered and declined. Abbreviating a protected value IS masking, and masking has one owner
     * per context -- {@code com.carddemo.batch.mapper} for this module -- so a second and slightly
     * different rule inside an entity would give one value two renderings and make neither
     * authoritative. What remains is a status code and a disclosure group code: enough to say which
     * KIND of account an entry concerns, and not enough to say which account. The cost is real and
     * is paid down elsewhere: a request-scoped line already carries the correlation identifier
     * {@code com.carddemo.common.web.CorrelationIdFilter} publishes, and a batch line is locatable
     * through the {@code batch.batch_run} step ledger, so an event stays traceable without a
     * protected value in its text. The accessors above return every omitted member to a caller that
     * needs one, so nothing is unavailable to the job itself.</p>
     *
     * <p>Assumptions: the exposure this closes is the RETAINED LOG rather than any request path. A
     * posting run renders one such line per account across an entire daily feed, so the aggregate of
     * a single run is a log holding every account identifier and every balance in the feed -- which
     * is the account master's monetary content in a second place no migration control governs.</p>
     *
     * <p>Assumptions: this is expressly NOT the fixed-width parity emitter. The committed
     * expectation files under {@code tests/golden/posting} are compared against a 300-byte
     * fixed-width rendering that a job produces through
     * {@code com.carddemo.common.codec.FixedWidthCodec}, and using this method for that comparison
     * would break parity silently, because it emits neither the declared field widths, nor the
     * zoned-decimal sign overpunch, nor the 178 pad bytes. The two renderings serve different
     * purposes and only one of them is a contract. Nothing parses this string, so narrowing it
     * cannot disturb any compared output.</p>
     *
     * @return a single-line rendering naming the type, the active status and the disclosure group,
     *     and carrying neither the account identifier nor any monetary value
     */
    @Override
    public String toString() {
        return "Account[activeStatus=" + activeStatus
                + ", groupId=" + groupId + ']';
    }
}
