package com.carddemo.batch.domain;

import com.carddemo.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// Assumptions: these class-level rationales sit above the Javadoc rather than between it and
//     the declaration, matching the sibling mapping CardXref in this package. Keeping the Javadoc
//     immediately adjacent to the annotated class preserves the association that Checkstyle's
//     type-documentation gate reads, while this uninterrupted rationale block still belongs to
//     the same declaration.
//
// Assumptions: those three offsets are corroborated three independent ways, recorded here so
//     that nobody re-derives them and so that a disagreement with any one source is recognised as
//     a finding rather than absorbed as a rounding difference. First, the summation the Javadoc
//     below sets out field by field.
//     Second, app/jcl/TRANREPT.jcl:41-42 declares the same two fields to the sort utility as
//     TRAN-CARD-NUM,263,16,ZD and TRAN-PROC-DT,305,10,CH in ONE-BASED positions, which are
//     zero-based 262 and 304. Third, app/jcl/TRANIDX.jcl:27 defines a VSAM alternate index over
//     this record as KEYS(26 304) -- a 26-byte key beginning at zero-based offset 304, which is
//     exactly the processing stamp. All three agree, and the owning migration reached the same
//     numbers independently: services/transaction-service/src/main/resources/db/migration/
//     V1__ledger.sql:219 records the card number as "Bytes 263-278, which is zero-based offset
//     262" and :242 records the processing stamp as "Bytes 305-330, which is zero-based offset
//     304". That module also restates the alternate-index key at V1__ledger.sql:276.
//
// Assumptions: the trailing FILLER at app/cpy/CVTRA05Y.cpy:18 is padding to the fixed record
//     length rather than data, so the migration plan's transformation rule T1 drops it and
//     requires the drop be recorded. Its width is 20 bytes beginning at offset 330, so 330 plus
//     20 is the declared 350, which the copybook header states at app/cpy/CVTRA05Y.cpy:2 as
//     RECLN = 350. Two file descriptions written independently of that header agree:
//     app/cbl/CBACT04C.cbl:89-92 subdivides the record into a 16-byte key and a 334-byte
//     remainder, and 16 plus 334 is 350; and app/jcl/INTCALC.jcl:39 declares the dataset the
//     interest job writes as DCB=(RECFM=F,LRECL=350,BLKSIZE=0). Recording the width here keeps
//     the layout reconstructible from this type alone, without opening the copybook.
//
// Assumptions: those names are carried exactly as written wherever any of them is cited,
//     because a citation whose text has been tidied no longer locates the byte range it claims to.
//     The first artifact is the one that can actually mislead: FD-TRAN-ID and FD-TRANS-ID differ by
//     a single character, sit four lines apart in one program, and are the keys of TWO DIFFERENT
//     FILES, so reading either as the other silently attributes this record's key to the daily
//     feed. The Java members below take their names from the physical column instead of from any
//     file description, and transformation rule T1 is why that is safe: the copybook is the
//     normative declaration and a file description is a program-local view of it, so a mapping
//     that followed a program's declaration would depend on which program was consulted -- and
//     here it would additionally depend on which of two adjacent declarations was read.

/**
 * Maps the 350-byte posted-transaction record onto {@code ledger.transactions} for the batch
 * bounded context.
 *
 * <p>A row is one posted transaction: the settled record that the nightly chain produces and that
 * every downstream statement, report and backup reads. Two migrated jobs write it -- transaction
 * posting and interest accrual -- and the section on converging producers below sets out why that
 * is one table here where the baseline had two datasets.</p>
 *
 * <h2>Record geometry, and the naming artifacts in the file descriptions</h2>
 *
 * <p>Assumptions: the trailing FILLER at app/cpy/CVTRA05Y.cpy:18 is padding to the fixed record
 * length rather than data, so the migration plan's transformation rule T1 drops it and requires the
 * drop be recorded. Its width is 20 bytes beginning at offset 330, so 330 plus 20 is the declared
 * 350, which the copybook header states at app/cpy/CVTRA05Y.cpy:2 as RECLN = 350. Two file
 * descriptions written independently of that header agree: app/cbl/CBACT04C.cbl:89-92 subdivides
 * the record into a 16-byte key and a 334-byte remainder, and 16 plus 334 is 350; and
 * app/jcl/INTCALC.jcl:39 declares the dataset the interest job writes as
 * DCB=(RECFM=F,LRECL=350,BLKSIZE=0). Recording the width here keeps the layout reconstructible from
 * this type alone, without opening the copybook.</p>
 */
@Entity
@Table(name = "transactions", schema = "ledger")
public class Transaction {

    // Alternatives Considered: the copybook abbreviates the field TRAN-ID and the member here
    //     is transactionId, following the physical column rather than the copybook spelling. The
    //     alternative -- a member named tranId with the column supplied only through the
    //     annotation -- was rejected because the two modules that map this table agree through the
    //     physical schema and nothing else, so a member whose name diverges from its column adds a
    //     second vocabulary for one field with no compensating clarity. The sibling mapping
    //     CardXref sets the same precedent, holding XREF-CUST-ID as customerId over customer_id.
    // Assumptions: CHAR(16) rather than a numeric type, for a field whose characters happen to
    //     be digits. app/cpy/CVTRA05Y.cpy:5 declares an ALPHANUMERIC picture, so the fixed width
    //     is the contract and a leading zero is significant. The baseline composes the value as
    //     text, which settles it: app/cbl/CBACT04C.cbl:476-480 builds it with
    //     STRING PARM-DATE, WS-TRANID-SUFFIX DELIMITED BY SIZE INTO TRAN-ID, concatenating the
    //     ten-character business-date token with a six-digit sequence to fill exactly sixteen
    //     characters. With the token 2022071800 that app/jcl/INTCALC.jcl:22 supplies, and the
    //     sequence pre-incremented to one at app/cbl/CBACT04C.cbl:474, the first generated
    //     identifier is that token followed immediately by the sequence rendered as 000001.
    /**
     * Maps {@code TRAN-ID} at {@code app/cpy/CVTRA05Y.cpy:5}, {@code PIC X(16)}, offset 0. It is
     * the whole of the record key: {@code app/cbl/CBTRN02C.cbl:34-37} selects the master
     * ORGANIZATION IS INDEXED with RECORD KEY IS {@code FD-TRANS-ID} at line 37, declaring no
     * further component, so it is the whole of this mapping's primary key too. The physical column
     * is {@code transaction_id}, declared {@code CHAR(16)} NOT NULL at
     * {@code services/transaction-service/src/main/resources/db/migration/V1__ledger.sql:126} and
     * named by the primary-key constraint at that file's line 255.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "transaction_id", length = 16, nullable = false, updatable = false)
    private String transactionId;

    // Assumptions: PIC X(02) is a character picture and the field is a fixed code, so the
    //     migration plan's section 0.4.1.3 makes its fixed width part of the contract rather than
    //     an upper bound. The baseline agrees where it expressed this same field relationally:
    //     app/app-transaction-type-db2/ddl/TRNTYCAT.ddl:2 declares TRC_TYPE_CODE CHAR(2) NOT NULL.
    //     The value is also a foreign-key-shaped reference into reference.transaction_types, whose
    //     own key column is CHAR(2), so a variable-width mapping here would compare a trimmed
    //     value against a blank-padded one.
    // Assumptions: this code and the category code that follows it are declared with DIFFERENT
    //     pictures in the copybook even though they sit adjacent and are always used together, and
    //     making them uniform is the single most likely error in this pair. app/cbl/CBACT04C.cbl:482
    //     moves the literal '01' into this two-character field and :483 moves '05' into a field
    //     declared PIC 9(04); the first stays two characters while the second becomes four. The
    //     widths differ and so does the picture class, and both are preserved.
    /**
     * Maps {@code TRAN-TYPE-CD} at {@code app/cpy/CVTRA05Y.cpy:6}, {@code PIC X(02)}, offset 16.
     * Physical column {@code type_cd}, {@code CHAR(2)} at {@code V1__ledger.sql:132}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "type_cd", length = 2)
    private String typeCd;

    // WHY : Alternatives Considered: a small-integer column with a Short member, which a width-only
    //       reading of PIC 9(04) invites because four digits reach only 9999. Rejected on four
    //       grounds, the last of which is decisive. The category code is a LABEL rather than a
    //       magnitude: no program performs arithmetic on it, and its leading zeros are significant.
    //       The baseline itself stored this same field as a character column where it expressed it
    //       relationally, at app/app-transaction-type-db2/ddl/TRNTYCAT.ddl:3, which declares
    //       TRC_TYPE_CATEGORY CHAR(4) NOT NULL. PIC 9(04) is DISPLAY usage, so the four record bytes
    //       at offsets 18 through 21 literally hold the characters of the zero-padded number and a
    //       character mapping preserves that byte image exactly. And every counterpart column this
    //       value joins to is itself CHAR(4) -- ledger.transaction_category_balances.category_cd at
    //       V1__ledger.sql:822, where it is part of the primary key, plus
    //       reference.transaction_categories.cat_cd and reference.disclosure_groups.tran_cat_cd in
    //       services/reference-service/src/main/resources/db/migration/V1__reference.sql -- so a
    //       small-integer mapping here would be the thing that BREAKS those joins rather than the
    //       thing that protects them.
    // WHY : Assumptions: because the picture is numeric while the column is character, the stored
    //       value must be the ZERO-PADDED four-character form and not a left-justified or
    //       blank-padded one. app/cbl/CBACT04C.cbl:483 moves the two-character literal '05' into the
    //       PIC 9(04) field, and numeric-picture assignment right-justifies and zero-fills, so the
    //       field holds 0005 rather than '05' followed by two blanks. Storing the unpadded form would
    //       leave a value that is the correct number and the wrong key, and the join would miss. The
    //       padding itself is applied where every other representation concern is applied, in
    //       com.carddemo.batch.mapper, and is never re-derived here.
    // WHY : Alternatives Considered: the copybook abbreviates this field TRAN-CAT-CD and the owning
    //       migration expands the abbreviation to category_cd; the member follows the column, for the
    //       reason given on the key above. Note that the same logical code is spelled cat_cd on
    //       reference.transaction_categories, so the two names do denote one domain and neither
    //       spelling should be assumed from the other.
    /**
     * Maps {@code TRAN-CAT-CD} at {@code app/cpy/CVTRA05Y.cpy:7}, {@code PIC 9(04)} -- a NUMERIC
     * picture -- offset 18. Physical column {@code category_cd}, {@code CHAR(4)} at
     * {@code V1__ledger.sql:146}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "category_cd", length = 4)
    private String categoryCd;

    // Assumptions: CHAR(10) rather than a variable-width column, because the baseline compares
    //     this field blank-padded to its full declared width and fixed-width character comparison
    //     reproduces that, where a variable-width column would treat a trimmed value and a padded
    //     one as two distinct values. The field is a small closed set used as a label rather than
    //     free text, which is what separates it from the descriptive columns further down that do
    //     map to variable width. Interest accrual writes the literal 'System' into it at
    //     app/cbl/CBACT04C.cbl:484 -- six characters into a ten-byte field -- so a padded value is
    //     the normal case and not an anomaly.
    // Alternatives Considered: keeping the record prefix, as tran_source, was evaluated
    //     precisely because an unquoted column named source collides with a keyword in the SQL
    //     MERGE statement, and the category-balance upsert this module performs is a plausible
    //     place to reach for MERGE. It is not adopted here for a reason that overrides the hazard:
    //     the column physically exists as source, declared by the owning service at
    //     V1__ledger.sql:156, and this module has no authority to rename a column in the ledger
    //     schema. The hazard is therefore handled where it actually arises -- by quoting the
    //     identifier at any query site that uses MERGE -- rather than by a mapping that would fail
    //     schema validation at start-up. The member name drops the prefix in step with every other
    //     field so that no reader has to remember which one field kept it.
    /**
     * Maps {@code TRAN-SOURCE} at {@code app/cpy/CVTRA05Y.cpy:8}, {@code PIC X(10)}, offset 22.
     * Physical column {@code source}, {@code CHAR(10)} at {@code V1__ledger.sql:156}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source", length = 10)
    private String source;

    // Assumptions: variable width rather than fixed, and this is the first of four columns
    //     where that choice is taken. The migration plan's section 0.4.1.3 treats a descriptive
    //     PIC X(n) field's trailing blanks as padding to the fixed record length rather than as
    //     data, and no comparison in the governing programs depends on this field's width. The
    //     declared 100 remains a constraint; what is not stored is the run of blanks that means
    //     nothing.
    // Assumptions: an all-blank or short value is legitimate here and must not be normalised
    //     to null or rejected. Interest accrual composes the description at
    //     app/cbl/CBACT04C.cbl:485-489 as STRING 'Int. for a/c ', ACCT-ID DELIMITED BY SIZE INTO
    //     TRAN-DESC -- thirteen characters plus an eleven-digit account identifier, so
    //     twenty-four characters into a hundred-character field. No constraint narrowing this
    //     column's domain is declared, because the source domain includes both that short value
    //     and the blank remainder.
    /**
     * Maps {@code TRAN-DESC} at {@code app/cpy/CVTRA05Y.cpy:9}, {@code PIC X(100)}, offset 32.
     * Physical column {@code description}, {@code VARCHAR(100)} at {@code V1__ledger.sql:166}.
     */
    @Column(name = "description", length = 100)
    private String description;

    // Alternatives Considered: a binary floating-point type, and a long integer count of cents.
    //     Binary floating point is excluded by the migration plan's transformation rule T3 and the
    //     prohibition is an executable assertion in LayeringRulesTest rather than a review note; it
    //     cannot hold every value this field can express, so a balance could differ from the
    //     baseline by a cent with nothing in the schema to reveal it. An integer count of cents was
    //     rejected on a different ground: the shared zoned-decimal codec decodes to an already
    //     scaled decimal and the committed parity expectations compare formatted decimal output, so
    //     an integer encoding would insert two conversions and two rounding opportunities into a
    //     path that currently has none.
    // Assumptions: the picture is SIGNED and the baseline carries that sign as an EBCDIC
    //     overpunch in the final byte. tests/README.md section 5.2 records that the default ASCII
    //     sign convention silently corrupts negative balances, which is why the reference build
    //     mandates EBCDIC sign handling. Decoding that overpunch is the business of
    //     com.carddemo.common.codec.ZonedDecimalCodec and transformation rule T2 keeps it there, so
    //     it is never re-declared on this type.
    /**
     * Maps {@code TRAN-AMT} at {@code app/cpy/CVTRA05Y.cpy:10}, {@code PIC S9(09)V99}, offset 132.
     * Physical column {@code amount}, {@code NUMERIC(11,2)} at {@code V1__ledger.sql:178}.
     */
    // WHY : Refactoring Rationale: NOT NULL, where an earlier revision of this mapping left the
    //       column nullable. `TRAN-AMT PIC S9(09)V99` declares no absent state, and neither normative
    //       zoned codec produces one -- both refuse a blank or non-digit numeric body -- so there is
    //       no decode path that yields null and no reference behaviour for a row that carries none.
    //       The posting arithmetic at app/cbl/CBTRN02C.cbl:547 and its sign branch at :548-552 both
    //       read this value unconditionally.
    @Column(name = "amount", precision = 11, scale = 2, nullable = false)
    private BigDecimal amount;

    /**
     * Integer digit positions {@code TRAN-AMT PIC S9(09)V99} declares, at
     * {@code app/cpy/CVTRA05Y.cpy:10}.
     *
     * <p>Assumptions: nine, which is what makes the persisted column {@code NUMERIC(11,2)} rather
     * than the {@code NUMERIC(12,2)} the widest reference money picture would need. It is named here
     * because the mutator below bounds against it, and a literal at that one site would be a width
     * with no citation beside it.</p>
     */
    private static final int AMOUNT_INTEGER_DIGITS = 9;

    // WHY : Assumptions: an identifier, so the migration plan's section 0.4.1.3 maps it to BIGINT
    //       with a Long member. A 32-bit integer holds nine digits comfortably and was the
    //       alternative considered; the wider type is chosen so that every identifier across every
    //       schema shares one width and a later widening of a source field never forces a column type
    //       change and the data movement that goes with it. Unlike the category code above, this one
    //       genuinely is a magnitude with no significant leading zero, which is what puts it on the
    //       numeric side of the same code-versus-quantity split.
    // WHY : Assumptions: ZERO IS A LEGITIMATE STORED VALUE here, meaning no merchant, and no
    //       non-zero constraint may be added. app/cbl/CBACT04C.cbl:491 writes MOVE 0 TO
    //       TRAN-MERCHANT-ID on every generated interest transaction, so a constraint excluding zero
    //       would reject one of the two producers of this table outright.
    /**
     * Maps {@code TRAN-MERCHANT-ID} at {@code app/cpy/CVTRA05Y.cpy:11}, {@code PIC 9(09)}, offset
     * 143. Physical column {@code merchant_id}, {@code BIGINT} at {@code V1__ledger.sql:187}.
     */
    @Column(name = "merchant_id")
    private Long merchantId;

    // Assumptions: descriptive, so variable width by the same reasoning as the description
    //     column above -- trailing blanks pad the record to its fixed length and are not compared
    //     as part of a code.
    // Assumptions: AN ALL-BLANK VALUE IS LEGITIMATE across this field and the two that follow
    //     it, and none of the three may carry a constraint requiring content.
    //     app/cbl/CBACT04C.cbl:492-494 writes SPACES into the merchant name, city and postal code
    //     on every generated interest transaction, because an accrual has no merchant. A
    //     non-blank constraint would therefore reject the interest producer, and normalising blank
    //     to null would change the rendered output the committed parity expectations compare.
    /**
     * Maps {@code TRAN-MERCHANT-NAME} at {@code app/cpy/CVTRA05Y.cpy:12}, {@code PIC X(50)}, offset
     * 152. Physical column {@code merchant_name}, {@code VARCHAR(50)} at
     * {@code V1__ledger.sql:195}.
     */
    @Column(name = "merchant_name", length = 50)
    private String merchantName;

    // Assumptions: descriptive, so variable width, on the same reasoning as the merchant name
    //     immediately above -- trailing blanks pad the record to its fixed length and are not
    //     compared as part of a code. An all-blank value is likewise legitimate, written at
    //     app/cbl/CBACT04C.cbl:493, so no constraint requiring content is declared. The rationale
    //     is restated rather than only cross-referenced because a reader auditing one field should
    //     not have to reconstruct its justification from a neighbour's.
    /**
     * Maps {@code TRAN-MERCHANT-CITY} at {@code app/cpy/CVTRA05Y.cpy:13}, {@code PIC X(50)}, offset
     * 202. Physical column {@code merchant_city}, {@code VARCHAR(50)} at
     * {@code V1__ledger.sql:199}.
     */
    @Column(name = "merchant_city", length = 50)
    private String merchantCity;

    // Assumptions: fixed width, unlike the two descriptive merchant columns immediately above
    //     it, because a postal code is matched as a code and its leading zeros are significant.
    //     The owning migration records at V1__ledger.sql:201-205 that 27 of the 300 values in the
    //     committed seed extract begin with a zero. Fixed width also preserves the blank-padded
    //     comparison the baseline performs, which is the same property the source column relies
    //     on. This field sits between two variable-width neighbours, so the difference is called
    //     out here rather than left to look like an inconsistency.
    /**
     * Maps {@code TRAN-MERCHANT-ZIP} at {@code app/cpy/CVTRA05Y.cpy:14}, {@code PIC X(10)}, offset
     * 252. Physical column {@code merchant_zip}, {@code CHAR(10)} at {@code V1__ledger.sql:206}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "merchant_zip", length = 10)
    private String merchantZip;

    // Assumptions: CHAR(16) and never a numeric type. app/cpy/CVTRA05Y.cpy:15 declares an
    //     alphanumeric picture, the baseline performs no arithmetic on the value anywhere -- only
    //     moves and comparisons -- and a numeric mapping would discard a leading zero, silently
    //     shortening such a value to fifteen digits and breaking every lookup that uses it. The
    //     owning migration records at V1__ledger.sql:208-211 that 30 of the 300 values in the
    //     committed seed extract carry a leading zero, so the case is measured rather than
    //     hypothetical. No example value appears in this file: a primary account number does not
    //     belong in source prose even when it comes from a committed extract, and the aggregate
    //     count is what the type decision rests on.
    // Assumptions: interest accrual takes this value from the cross-reference rather than from
    //     any field of the transaction it is generating, at app/cbl/CBACT04C.cbl:495, so a row
    //     written by that producer carries the card the account resolves to. Posting instead copies
    //     it from the daily record at app/cbl/CBTRN02C.cbl:435. Both routes populate the same
    //     column, so neither may be assumed from the other when reading a row back.
    /**
     * Maps {@code TRAN-CARD-NUM} at {@code app/cpy/CVTRA05Y.cpy:15}, {@code PIC X(16)}, offset 262
     * -- the offset corroborated three ways in the block above this class. Physical column
     * {@code card_num}, {@code CHAR(16)} at {@code V1__ledger.sql:220}, indexed by
     * {@code idx_transactions_card_num} at {@code V1__ledger.sql:269}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", length = 16)
    private String cardNum;

    // Assumptions: PIC X(26) holding the form yyyy-MM-dd HH:mm:ss followed by a point and six
    //     fractional digits maps to microsecond precision exactly -- nineteen characters to the
    //     second, the point, then six digits is twenty-six -- so the mapping truncates no digit on
    //     the way in and invents none on the way out. Anything coarser would silently drop
    //     fractional digits the source carries. The twenty-six-character form itself is owned by
    //     com.carddemo.common.time.TimestampFormatter, whose length constant states it, and
    //     transformation rule T2 keeps it there rather than re-declared here.
    // Alternatives Considered: an instant or a zone-qualified date-time. Both were rejected
    //     because there is no zone information in the source to preserve: the baseline's timestamp
    //     structure carries a separate GMT-offset element that is never moved into the formatted
    //     value, so a zone-qualified mapping would have to invent an offset and would fabricate
    //     data. A local date-time also matches the type the shared formatter itself uses, which is
    //     forced by the same fact.
    /**
     * Maps {@code TRAN-ORIG-TS} at {@code app/cpy/CVTRA05Y.cpy:16}, {@code PIC X(26)}, offset 278.
     * Physical column {@code orig_ts}, {@code TIMESTAMP(6)} at {@code V1__ledger.sql:229}.
     */
    @Column(name = "orig_ts")
    private LocalDateTime origTs;

    // Assumptions: this member is declared non-nullable and the twelve columns above it are
    //     not, and the asymmetry is the owning migration's rather than this mapping's invention.
    //     V1__ledger.sql:231-244 records why: every writer of this table sets the processing stamp,
    //     and no transaction-master extract exists under app/data to load a blank one from. The
    //     nullability declared on each member here mirrors the physical column exactly, because
    //     declaring a column non-nullable that the owning service permits to be null would have
    //     this module reject a row that service wrote deliberately.
    // Assumptions: the business date is a separate concern from either stamp and never reaches
    //     this type. It arrives as a job parameter -- app/jcl/INTCALC.jcl:22 supplies the compact
    //     ten-character token -- which is what keeps a rerun reproducible where a clock read would
    //     not be. That token and the punctuated twenty-six-character stamped form are different
    //     shapes serving different purposes and are not interchangeable.
    /**
     * Maps {@code TRAN-PROC-TS} at {@code app/cpy/CVTRA05Y.cpy:17}, {@code PIC X(26)}, offset 304
     * -- the offset the alternate-index definition at {@code app/jcl/TRANIDX.jcl:27} confirms.
     * Physical column {@code proc_ts}, and the one column beyond the primary key that the owning
     * migration declares NOT NULL, at {@code V1__ledger.sql:245}. Indexed by
     * {@code idx_transactions_proc_ts} at {@code V1__ledger.sql:291}.
     *
     * <p>The two stamps are produced DIFFERENTLY by the two producers, and this is the field pair
     * where the converging-producers section above has an observable consequence. In posting,
     * {@code app/cbl/CBTRN02C.cbl:436} copies the feed's own origination stamp straight through,
     * then :437 performs a single timestamp read and :438 moves that fresh value here -- so the two
     * differ and the origination stamp is the earlier. The clock behind that read is the wall
     * clock, at {@code app/cbl/CBTRN02C.cbl:692-693}, because {@code app/jcl/POSTTRAN.jcl:23}
     * passes no parameter to the posting step at all. In interest accrual,
     * {@code app/cbl/CBACT04C.cbl:496} performs one timestamp read and :497 and :498 move THE SAME
     * VALUE into both stamps -- so on a generated interest transaction the two are identical by
     * construction.</p>
     */
    @Column(name = "proc_ts", nullable = false)
    private LocalDateTime procTs;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the persistence specification requires a mapped class to declare a
     * constructor taking no arguments, which the provider invokes reflectively before writing the
     * thirteen mapped columns into the fields above. Visibility is protected rather than public
     * because no caller outside this type's own hierarchy has a use for a transaction row with no
     * identifier: a provider-generated subclass reaches a protected constructor, while application
     * code cannot reach it by accident and so cannot produce a row whose null key the equality
     * below would have to treat as an identity. Protected is the widest visibility the
     * specification's requirement actually needs. </p>
     */
    protected Transaction() {
        // Assumptions: the body is empty by design rather than unfinished. The provider
        //     assigns every mapped field directly after construction on each load, so initialising
        //     any of them here would write a value that is overwritten before a caller could
        //     observe it.
    }

    /**
     * Creates a transaction row carrying the identifier that keys it, with every other member left
     * for the caller to record.
     *
     * <p>Alternatives Considered: four construction strategies were available and this one --
     * a constructor taking only the key, with the remaining twelve members recorded through setters
     * -- is chosen. A constructor taking all thirteen values was rejected because thirteen
     * positional parameters, six of which are adjacent strings, invite a silent transposition:
     * swapping the merchant name and city, or the type and category codes, compiles cleanly and
     * produces a plausible row, which is precisely the class of error the offset table above this
     * class exists to prevent. A generated builder was rejected outright because the annotation
     * processor that would generate it is prohibited across this project, its generated members
     * being unable to carry documentation at all. A
     * hand-written nested builder was the closest rival and is refused as disproportionate: it adds
     * a nested type with thirteen documented methods of its own, and it removes no ordering hazard
     * that a named setter does not already remove, since a builder call is named exactly as a setter
     * is. </p>
     *
     * <p>Assumptions: the chosen shape also mirrors how the baseline itself assembles this record.
     * Posting populates it field by field through a sequence of individual moves at
     * {@code app/cbl/CBTRN02C.cbl:425-438}, and interest accrual does the same at
     * {@code app/cbl/CBACT04C.cbl:476-498}. A setter sequence transcribes that one for one, which
     * keeps a reader able to check the migrated assembly against the paragraph it came from. </p>
     *
     * @param transactionId the String containing the sixteen-character identifier that keys this
     *     row, declared {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:5} and carried in
     *     the {@code transaction_id} column; posting copies it from the daily record at
     *     {@code app/cbl/CBTRN02C.cbl:425} and interest accrual composes it at
     *     {@code app/cbl/CBACT04C.cbl:476-480}
     */
    public Transaction(String transactionId) {
        this.transactionId = transactionId;
    }

    /**
     * Returns the sixteen-character identifier this posted transaction is keyed by.
     *
     * @return the String value of the {@code transaction_id} column, which is the key the baseline
     *     reads the master by at {@code app/cbl/CBTRN02C.cbl:37} and the order the baseline's own
     *     merge step sorts on at {@code app/jcl/COMBTRAN.jcl:30}
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Returns the two-character transaction type code classifying this transaction.
     *
     * @return the String value of the {@code type_cd} column, blank-padded to its declared width,
     *     which references the transaction-type reference data keyed on the same fixed width
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Records the two-character transaction type code classifying this transaction.
     *
     * @param typeCd the String containing the type code, taken from
     *     {@code TRAN-TYPE-CD PIC X(02)} at {@code app/cpy/CVTRA05Y.cpy:6}; interest accrual sets
     *     the literal two characters shown at {@code app/cbl/CBACT04C.cbl:482}
     */
    public void setTypeCd(String typeCd) {
        this.typeCd = typeCd;
    }

    /**
     * Returns the four-character transaction category code sub-classifying this transaction.
     *
     * @return the String value of the {@code category_cd} column in its zero-padded four-character
     *     form, which is the form the counterpart category-balance and disclosure-group keys compare
     *     against
     */
    public String getCategoryCd() {
        return categoryCd;
    }

    /**
     * Records the four-character transaction category code sub-classifying this transaction.
     *
     * @param categoryCd the String containing the category code in its zero-padded four-character
     *     form, derived from the numeric picture {@code TRAN-CAT-CD PIC 9(04)} at
     *     {@code app/cpy/CVTRA05Y.cpy:7}; the padding is applied in
     *     {@code com.carddemo.batch.mapper} and an unpadded value would be the right number and the
     *     wrong key
     */
    public void setCategoryCd(String categoryCd) {
        this.categoryCd = categoryCd;
    }

    /**
     * Returns the label naming the channel this transaction reached the system through.
     *
     * @return the String value of the {@code source} column, blank-padded to its declared width, as
     *     with the literal interest accrual writes at {@code app/cbl/CBACT04C.cbl:484}
     */
    public String getSource() {
        return source;
    }

    /**
     * Records the label naming the channel this transaction reached the system through.
     *
     * @param source the String containing the channel label, taken from
     *     {@code TRAN-SOURCE PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:8}; it is a small closed set
     *     of codes rather than free text, which is why the column is fixed width
     */
    public void setSource(String source) {
        this.source = source;
    }

    /**
     * Returns the free-text description carried on this transaction.
     *
     * @return the String value of the {@code description} column with its record padding not
     *     retained, which for a generated interest transaction is the composed text described at
     *     {@code app/cbl/CBACT04C.cbl:485-489}
     */
    public String getDescription() {
        return description;
    }

    /**
     * Records the free-text description carried on this transaction.
     *
     * @param description the String containing the description, taken from
     *     {@code TRAN-DESC PIC X(100)} at {@code app/cpy/CVTRA05Y.cpy:9}; a short or wholly blank
     *     value is legitimate and is not normalised
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the signed monetary amount this transaction posts, in exact fixed point at scale two.
     *
     * @return the BigDecimal value of the {@code amount} column, negative for a debit and
     *     non-negative for a credit, which is the sign
     *     {@code app/cbl/CBTRN02C.cbl:548-552} branches on when apportioning the account's cycle
     *     totals
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Records the signed monetary amount this transaction posts, canonicalised to the declared
     * picture.
     *
     * <p>Refactoring Rationale: an earlier revision of this mutator assigned the argument verbatim,
     * which admitted three values the column cannot hold and one it can hold wrongly. A {@code null}
     * reached a column now declared {@code NOT NULL}, so the failure surfaced at flush time naming
     * the constraint and not the assignment. A value at a scale other than two was coerced by the
     * driver, so {@code 1.005} became a stored cent figure this type never agreed to. And a value
     * needing ten integer digits -- legal for the widest reference money picture and so legal for
     * {@code Money.of} -- reached a {@code NUMERIC(11,2)} column and raised a numeric-field-overflow
     * at the database, with no field name in it. Routing through
     * {@link Money#ofPicture(BigDecimal, int)} rejects all three at the assignment that introduced
     * them.</p>
     *
     * <p>Assumptions: canonicalisation is not arithmetic. This type still computes nothing: the
     * factory reduces to the money contract's scale and bounds the magnitude, and every addition,
     * multiplication and division on an amount remains {@code Money}'s under transformation rules T3
     * and T4.</p>
     *
     * @param amount the BigDecimal amount, derived from the signed picture
     *     {@code TRAN-AMT PIC S9(09)V99} at {@code app/cpy/CVTRA05Y.cpy:10}; must not be
     *     {@code null}, is reduced to scale two under the general money contract, and must fit nine
     *     integer digits
     * @throws NullPointerException if {@code amount} is {@code null}, which the column's own
     *     {@code NOT NULL} would otherwise report at flush time instead of here
     * @throws ArithmeticException if {@code amount} is outside the input shape {@code Money} admits,
     *     or if its reduced magnitude needs more than nine integer digits
     */
    public void setAmount(BigDecimal amount) {
        this.amount = Money.ofPicture(amount, AMOUNT_INTEGER_DIGITS).amount();
    }

    /**
     * Returns the identifier of the merchant this transaction was acquired from.
     *
     * @return the Long value of the {@code merchant_id} column, where zero is a legitimate stored
     *     value meaning no merchant, as written on every generated interest transaction at
     *     {@code app/cbl/CBACT04C.cbl:491}
     */
    public Long getMerchantId() {
        return merchantId;
    }

    /**
     * Records the identifier of the merchant this transaction was acquired from.
     *
     * @param merchantId the Long merchant identifier, derived from
     *     {@code TRAN-MERCHANT-ID PIC 9(09)} at {@code app/cpy/CVTRA05Y.cpy:11}; zero is accepted
     *     because the interest producer writes it
     */
    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    /**
     * Returns the trading name of the merchant this transaction was acquired from.
     *
     * @return the String value of the {@code merchant_name} column, which is blank on a generated
     *     interest transaction because an accrual has no merchant
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Records the trading name of the merchant this transaction was acquired from.
     *
     * @param merchantName the String merchant name, taken from
     *     {@code TRAN-MERCHANT-NAME PIC X(50)} at {@code app/cpy/CVTRA05Y.cpy:12}; an all-blank
     *     value is legitimate, as written at {@code app/cbl/CBACT04C.cbl:492}
     */
    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    /**
     * Returns the city of the merchant this transaction was acquired from.
     *
     * @return the String value of the {@code merchant_city} column, blank on a generated interest
     *     transaction for the same reason the merchant name is
     */
    public String getMerchantCity() {
        return merchantCity;
    }

    /**
     * Records the city of the merchant this transaction was acquired from.
     *
     * @param merchantCity the String merchant city, taken from
     *     {@code TRAN-MERCHANT-CITY PIC X(50)} at {@code app/cpy/CVTRA05Y.cpy:13}; an all-blank
     *     value is legitimate, as written at {@code app/cbl/CBACT04C.cbl:493}
     */
    public void setMerchantCity(String merchantCity) {
        this.merchantCity = merchantCity;
    }

    /**
     * Returns the postal code of the merchant this transaction was acquired from.
     *
     * @return the String value of the {@code merchant_zip} column, blank-padded to its declared
     *     width because a postal code is matched as a code and its leading zeros carry meaning
     */
    public String getMerchantZip() {
        return merchantZip;
    }

    /**
     * Records the postal code of the merchant this transaction was acquired from.
     *
     * @param merchantZip the String postal code, taken from
     *     {@code TRAN-MERCHANT-ZIP PIC X(10)} at {@code app/cpy/CVTRA05Y.cpy:14}; an all-blank
     *     value is legitimate, as written at {@code app/cbl/CBACT04C.cbl:494}
     */
    public void setMerchantZip(String merchantZip) {
        this.merchantZip = merchantZip;
    }

    /**
     * Returns the sixteen-character card number this transaction was posted against, in full.
     *
     * @return the String value of the {@code card_num} column, unmasked because it is the value the
     *     committed parity expectations under {@code tests/golden/posting} compare byte for byte
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Records the sixteen-character card number this transaction was posted against.
     *
     * @param cardNum the String card number, taken from {@code TRAN-CARD-NUM PIC X(16)} at
     *     {@code app/cpy/CVTRA05Y.cpy:15}; posting copies it from the daily record at
     *     {@code app/cbl/CBTRN02C.cbl:435} while interest accrual takes it from the cross-reference
     *     at {@code app/cbl/CBACT04C.cbl:495}
     */
    public void setCardNum(String cardNum) {
        this.cardNum = cardNum;
    }

    /**
     * Returns the instant at which the acquiring system originated this transaction.
     *
     * @return the LocalDateTime value of the {@code orig_ts} column at microsecond precision, which
     *     posting passes through unchanged from the feed at {@code app/cbl/CBTRN02C.cbl:436} and
     *     interest accrual sets equal to the processing stamp
     */
    public LocalDateTime getOrigTs() {
        return origTs;
    }

    /**
     * Records the instant at which the acquiring system originated this transaction.
     *
     * @param origTs the LocalDateTime origination instant, derived from
     *     {@code TRAN-ORIG-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:16}; the caller supplies it
     *     from the feed or from an injected clock, never from a wall-clock read on this type
     */
    public void setOrigTs(LocalDateTime origTs) {
        this.origTs = origTs;
    }

    /**
     * Returns the instant at which this transaction was posted to the ledger.
     *
     * @return the LocalDateTime value of the {@code proc_ts} column at microsecond precision, which
     *     every writer of this table sets and which may equal rather than follow the origination
     *     stamp when the row came from interest accrual
     */
    public LocalDateTime getProcTs() {
        return procTs;
    }

    /**
     * Records the instant at which this transaction was posted to the ledger.
     *
     * @param procTs the LocalDateTime processing instant, derived from
     *     {@code TRAN-PROC-TS PIC X(26)} at {@code app/cpy/CVTRA05Y.cpy:17}; the caller supplies it
     *     from an injected clock so that a job can be run against a fixed instant
     */
    public void setProcTs(LocalDateTime procTs) {
        this.procTs = procTs;
    }

    /**
     * Reports whether another object denotes the same posted transaction as this one.
     *
     * <p>Alternatives Considered: equality rests on the transaction identifier alone, and including
     * the business members was evaluated and rejected. Every other member is mutable through a
     * setter above, so an equality that read them would change as a row was assembled: an instance
     * placed in a hash-based collection before its amount was recorded could not afterwards be
     * found in it. The identifier is the whole of the primary key, as
     * {@code V1__ledger.sql:255} declares, so it alone settles which row is meant. </p>
     *
     * <p>Assumptions: the key is a natural one, composed by the application rather than generated by
     * the database, so it is populated from construction onward through the constructor above. This
     * comparison therefore does not face the null-identity problem a generated key would present,
     * where two not-yet-persisted instances share a null key and so compare equal to each
     * other. </p>
     *
     * @param other the Object to compare against, which may be of any type and may be null
     * @return the boolean value true when the argument is a posted transaction carrying an equal
     *     transaction identifier, and false otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Transaction that)) {
            return false;
        }
        return Objects.equals(this.transactionId, that.transactionId);
    }

    /**
     * Returns a hash drawn from the same transaction identifier the equality above compares.
     *
     * <p>Assumptions: the hash is derived from exactly the member equality uses and from no other,
     * which is the contract the two methods share rather than a preference. Mixing in a member that
     * equality ignores would let two objects compare equal while hashing differently, so a
     * hash-based collection could hold both as distinct members and a lookup by an equal instance
     * could miss the entry already in it. </p>
     *
     * @return the int hash of the transaction identifier, or zero when no identifier has been
     *     populated yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.transactionId);
    }

    /**
     * Returns a diagnostic rendering naming this transaction and the four members that characterise
     * it.
     *
     * <p>Trade-offs: THE CARD NUMBER IS DELIBERATELY OMITTED. The transaction identifier already
     * names the row uniquely, being the whole of the primary key, so including the card number would
     * add nothing to a reader's ability to locate the row while placing an unmasked primary account
     * number in a log line -- and a batch job renders one such line per record across an entire
     * daily feed, so the aggregate is a log holding every card number in the feed. The migration
     * plan's section 0.4.1.9 masking discipline is the reason it is closed here. Note that the
     * exposure is the retained log itself and does NOT depend on this module opening a request path:
     * {@code services/batch-service/pom.xml} declares both {@code spring-boot-starter-actuator} and
     * {@code spring-boot-starter-web} so that the container can answer the image health probe, and
     * this rendering would be just as durable if it declared neither. What is given up is that a
     * failure diagnosed from this string alone does not name the card; the identifier resolves it
     * against the stored row, and the accessor above returns the full value to a caller that needs
     * it. </p>
     *
     * <p>Trade-offs: this string is a diagnostic aid and is expressly NOT an output contract.
     * Nothing parses it, and it is not the byte-exact fixed-width rendering the committed parity
     * expectations under {@code tests/golden/posting} compare -- that 350-byte rendering is produced
     * by the job through {@code com.carddemo.common.codec.FixedWidthCodec} from the members above --
     * so widening, reordering or reformatting this string cannot disturb any compared output.
     * Relying on it for that rendering instead would break parity silently, because the two would
     * agree on content while differing on every byte position. </p>
     *
     * @return a String containing a single-line rendering naming the type, the transaction
     *     identifier, the type and category codes and the processing stamp, and carrying neither the
     *     card number nor the amount
     */
    @Override
    public String toString() {
        return "Transaction[transactionId=" + transactionId
                + ", typeCd=" + typeCd
                + ", categoryCd=" + categoryCd
                + ", procTs=" + procTs + "]";
    }
}
