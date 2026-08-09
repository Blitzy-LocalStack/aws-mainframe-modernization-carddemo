package com.carddemo.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of {@code reporting.v_card_xref}, resolving one card to its customer and its
 * account.
 *
 * <h2>Purpose</h2>
 *
 * <p>Every read this bounded context performs has to resolve a card to the customer and the account
 * it belongs to, and this relation is where that resolution happens. It is the one projection in
 * this package that both of the context's joins reach, which makes it the one type written to serve
 * two consumers and to presume neither of them.
 *
 * <h2>The two joins, and why this type presumes neither</h2>
 *
 * <p>Assumptions: both joins reach this single relation, and each participation was read from the
 * job control that supplies the input and then confirmed against the {@code COPY} set of the
 * program that consumes it. The statement join runs {@code CBSTM03A} from STEP040 of
 * {@code app/jcl/CREASTMT.JCL} at L79 and supplies this relation as {@code XREFFILE} at L84, which
 * {@code app/cbl/CBSTM03A.CBL} corroborates by copying {@code CVACT03Y} at L53. The report join
 * runs {@code CBTRN03C} from STEP10R of {@code app/jcl/TRANREPT.jcl} at L59 and supplies the same
 * dataset under a different data-definition name, {@code CARDXREF} at L67-L68, which
 * {@code app/cbl/CBTRN03C.cbl} corroborates by copying the same book at L98. Because the two
 * consumers differ, no member, no derived value and no comment on this type is written for either
 * one of them in particular: a field added to suit the statement would be dead weight in the
 * report, and a member shaped around the report would quietly distort the statement.
 *
 * <p>Assumptions: the two consumers differ in access shape as well, and neither shape is expressed
 * on this type. The statement path walks the relation in key order, since
 * {@code app/cbl/CBSTM03B.CBL} declares it {@code ACCESS MODE IS SEQUENTIAL} at L39 and
 * {@code app/cbl/CBSTM03A.CBL} drives it from the paragraph {@code 1000-XREFFILE-GET-NEXT.} at
 * L345, whereas the report path reads it by key at {@code 1500-A-LOOKUP-XREF} in
 * {@code app/cbl/CBTRN03C.cbl} at L484-L492. One relation is one projection, and the two shapes are
 * carried by two repository roles instead of by two row types.
 *
 * <h2>The report's account identifier is read from this row and from nowhere else</h2>
 *
 * <p>Assumptions: the account identifier the report prints originates in this record rather than in
 * any account record. {@code app/cbl/CBTRN03C.cbl} builds each detail line in the paragraph
 * {@code 1120-WRITE-DETAIL.} at L361, and the one statement that supplies its account column is
 * L364, {@code MOVE XREF-ACCT-ID TO TRAN-REPORT-ACCOUNT-ID}, whose source operand is this record's
 * own field. That is precisely why the report join lists no account projection among its
 * participants: the identifier it needs is already in this row, so reading an account relation to
 * obtain it would add a join that supplies nothing. Treating this member as a convenience duplicate
 * of a value held elsewhere, and dropping it, would leave the report's account column with no
 * source at all.
 *
 * <h2>Record geometry, derived twice</h2>
 *
 * <p>Assumptions: the copybook is normative under the migration plan's own transformation rule, so
 * the declared widths in {@code app/cpy/CVACT03Y.cpy} settle the column types and the byte
 * positions and nothing else does. That book declares {@code 01 CARD-XREF-RECORD} at L4, the card
 * number {@code PIC X(16)} at L5, the customer identifier {@code PIC 9(09)} at L6, the account
 * identifier {@code PIC 9(11)} at L7 and a trailing {@code FILLER PIC X(14)} at L8. The three
 * mapped fields sum to 36 significant bytes, and 36 plus 14 is the declared record length of 50.
 *
 * <p>Assumptions: that trailing {@code FILLER} at L8 is dropped rather than mapped, and it occupies
 * one-based positions 37 through 50. It exists only to carry the record to its declared length and
 * holds nothing any program reads, so a column for it would persist padding as though padding were
 * data. Dropping it is what the transformation rule directs for a filler, and stating the positions
 * it occupied is what lets a reader confirm that the three positions ahead of it are undisturbed.
 *
 * <p>Assumptions: the same 50 was re-derived from a source with no copybook in it at all, and two
 * unrelated sources agreeing is why these positions are asserted here rather than estimated.
 * {@code app/cbl/CBSTM03B.CBL} declares {@code FD XREF-FILE} at L65 with exactly two members,
 * {@code FD-XREF-CARD-NUM PIC X(16)} at L67 and {@code FD-XREF-DATA PIC X(34)} at L68, and 16 plus
 * 34 is 50. The same declaration independently settles the key: L40 names
 * {@code FD-XREF-CARD-NUM} as the record key, so the key is the leading 16 bytes and the remaining
 * 34 are payload.
 *
 * <p>Assumptions: every citation above names the copybook or the file description that owns the
 * field, because a bare field name fixes no width anywhere in this baseline. Three demonstrations
 * settle that. {@code app/cbl/CBSTM03B.CBL} declares {@code FD-ACCT-DATA} twice at two different
 * widths inside one program, {@code PIC X(318)} at L63 and {@code PIC X(289)} at L78, so even a
 * single-program reading of a name is insufficient. The group {@code TRAN-CAT-KEY} is 6 bytes at
 * {@code app/cpy/CVTRA04Y.cpy} L5 and 17 bytes at {@code app/cpy/CVTRA01Y.cpy} L5. And
 * {@code app/cbl/CBTRN03C.cbl} adopts exactly this discipline in the very paragraph that consumes
 * this record, qualifying its two ambiguous operands with an explicit record qualifier at L365 and
 * L367 while leaving {@code XREF-ACCT-ID} bare at L364 because that name is unambiguous. This
 * type's layout is therefore scoped to {@code app/cpy/CVACT03Y.cpy} and to no other book.
 *
 * <h2>What this projection deliberately does not carry</h2>
 *
 * <p>Assumptions: this row carries a card number and yet no card relation is read anywhere in this
 * context, so no card member belongs on it. The evidence is symmetric across both consumers. The
 * card book {@code app/cpy/CVACT02Y.cpy} appears in neither program's {@code COPY} set, since
 * {@code app/cbl/CBSTM03A.CBL} copies exactly four books at L51, L53, L55 and L57 and
 * {@code app/cbl/CBTRN03C.cbl} copies exactly five at L93, L98, L103, L108 and L113. No card
 * dataset appears in either data-definition set either, at {@code app/jcl/CREASTMT.JCL} L83-L86 or
 * {@code app/jcl/TRANREPT.jcl} L65-L72. The schemas this context reads are exactly {@code ledger},
 * {@code account} and {@code reference}, and the {@code card} schema is not among them, so a card
 * status, an expiration date or an embossed name added here would name a column no relation this
 * context can read supplies.
 *
 * <p>Assumptions: this projection carries no monetary member and no timestamp member, and imports
 * the type of neither. All three mapped fields are declared {@code PIC X(16)}, {@code PIC 9(09)}
 * and {@code PIC 9(11)} across {@code app/cpy/CVACT03Y.cpy} L5 through L7, so not one of them
 * carries an implied decimal position and not one is 26 characters wide. There is consequently no
 * amount to hold at an exact scale of two and no instant to hold at microsecond precision, and
 * adding either for symmetry with the two transaction projections of this package would put a
 * member on this type that the relation behind it cannot populate.
 *
 * <h2>The card number arrives masked</h2>
 *
 * <p>Assumptions: the value this type receives is a masked rendering and never a primary account
 * number. The relation's own definition in {@code data-migration/sql/V1__reporting_views.sql}
 * concatenates twelve asterisks with the last four digits of the stored number and casts the result
 * back to sixteen characters, so what arrives is masked at the width the baseline field declares --
 * {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy} L5. The declared length here is
 * consequently 16 and not 4: the masked form occupies the whole declared width, and bounding the
 * column at the four visible digits would reject the very value the relation returns.
 *
 * <p>Alternatives Considered: exposing the four visible digits as a second member beside the masked
 * form, so that a caller wanting only the tail need not take a substring. Rejected because it would
 * put two renderings of one value on one type, and a caller could then pass the four-character tail
 * where the width {@code XREF-CARD-NUM PIC X(16)} declares at {@code app/cpy/CVACT03Y.cpy} L5 is
 * expected, filling a 16-character cell with 4 characters and displacing everything after it. The
 * masked form is the value, and a tail is a substring a caller takes on purpose.
 *
 * <h2>Decisions on the mapping itself</h2>
 *
 * <p>Trade-offs: the type is mapped {@code @Immutable}, declares no optimistic-locking version
 * column, no cascade and no mutator, and declares every column not updatable, so no write path
 * exists through it at any layer. What is surrendered is real and is surrendered on purpose: dirty
 * checking against a loaded instance and the convenience of merging a detached one are both given
 * up, and a value that needs changing has to be changed by the context that owns
 * {@code account.card_xref}. What is bought is <b>where</b> an accidental write fails. Mapped this
 * way it fails at the mapping layer and names this type; left mutable, the identical write would
 * travel all the way to the database and be refused by the {@code SELECT}-only login role,
 * surfacing as an opaque privilege error on a request path far from the assignment that caused it.
 * The database refusal is retained as well, since
 * {@code data-migration/sql/V1__reporting_views.sql} conveys this role {@code SELECT} on the
 * relation and withholds every writing privilege from it, because the annotation fails during
 * development and the privilege fails in production and neither substitutes for the other.
 *
 * <p>Alternatives Considered: this type maps a view, and this context declares no schema artifact
 * of its own; the migration plan's schema-ownership table records it as the single entry whose
 * owner cell reads "(none)" and which owns no tables at all. Two alternatives were weighed against
 * that and both were rejected. Owning a schema here, with base tables of its own, was rejected
 * because every store the report and the statement read is already owned elsewhere -- the two job
 * control members declare their inputs at {@code app/jcl/CREASTMT.JCL} L83-L86 and
 * {@code app/jcl/TRANREPT.jcl} L65-L72 -- so a local table would create a second truth for figures
 * whose entire purpose is to restate the first one exactly. Reading a replica instead was rejected
 * on the plan's own ground that a replica adds cost and replica-lag semantics for no parity
 * benefit: a statement disagreeing with the ledger by one replication interval is a support case
 * rather than a feature. Read-only access through a cross-schema view is what remains.
 *
 * <p>Assumptions: agreement with the context that owns these rows runs through the physical view
 * and the narrowly-scoped read privilege behind it, and never through code or a Maven dependency,
 * so this type declares no relationship annotation to any sibling projection in either direction --
 * not to either transaction projection, not to the account or the customer projection, and not to
 * either reference projection. Both four-way joins are composed in the repository layer, by query
 * over the physical relations. Two independent mechanisms hold that line: the shared kernel's
 * {@code LayeringRulesTest}, at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java},
 * forbids one context's domain package importing another's and, being a build rule, cannot decay
 * unnoticed; and this module's POM puts no other service module on its compile classpath to be
 * imported from in the first place. That mirrors how the baseline read, where the two consuming
 * programs reached their inputs through job control alone and never through a compile-time bond
 * between them. The database half is authored elsewhere and is cited by path:
 * {@code data-migration/sql/V0__schemas_and_roles.sql} establishes the schemas and the roles, and
 * {@code data-migration/sql/V1__reporting_views.sql} declares the view itself. A relation absent at
 * run time is a defect to report against those two artifacts and never one to work around from
 * inside this package.
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Assumptions: every constructor and every method below carries a docstring whatever its
 * visibility, because user-specified Rule 1 (Explainability) attaches its presence clause at L15 to
 * every function and class and names no visibility, and because the Javadoc modules of
 * {@code config/checkstyle/checkstyle.xml} are configured at private scope with an empty
 * allowed-annotations list, which exempts an overriding method no more than any other. The three
 * mapped fields carry an adjacent inline comment instead of a docstring, which is the placement
 * that rule asks for at L27 and the form that gate expects, since it configures no field-level
 * Javadoc module at all; documenting them twice would put one rationale in two places able to
 * disagree. The
 * four rationale labels used throughout are the plural, unparenthesised forms that rule gives at
 * L31-L34, and the written convention every block here follows is
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and never restated.
 */
@Entity
@Immutable
@Table(name = "v_card_xref", schema = "reporting")
public class CardXrefView {

    /**
     * Declared width of the card number, in characters.
     *
     * <p>Assumptions: 16 is the width {@code XREF-CARD-NUM PIC X(16)} declares at
     * {@code app/cpy/CVACT03Y.cpy} L5, and it is equally the width of the masked rendering the
     * relation returns, because that rendering is cast back to sixteen characters.</p>
     */
    public static final int CARD_NUMBER_WIDTH = 16;

    /**
     * Declared width of the per-card fingerprint, in characters, being 64.
     *
     * <p>Assumptions: 64 is not a preference. The relation renders a SHA-256 digest with
     * {@code encode(..., 'hex')} at {@code data-migration/sql/V1__reporting_views.sql}, and a
     * SHA-256 digest is 32 bytes, which a lower-case hexadecimal rendering carries in exactly two
     * characters each. Declaring the width states the contract the definition already fixes, so a
     * change to the digest on that side fails the read here rather than silently truncating.</p>
     */
    public static final int CARD_FINGERPRINT_WIDTH = 64;

    // Assumptions: the card number is mapped as sixteen characters and a String, never as a numeric
    // type, because app/cpy/CVACT03Y.cpy L5 declares it PIC X(16), an alphanumeric picture, and the
    // declared width is part of the key contract rather than a presentation choice. Two measured
    // consequences rule a numeric column out: 5 of the 50 rows in the seed extract
    // app/data/ASCII/cardxref.txt carry a card number whose leading digit is a zero, which a
    // numeric type discards on the way in and which would then no longer be 16 characters wide, and
    // sixteen significant digits exceed what an IEEE-754 binary representation holds exactly, so a
    // client that parsed the value as a JSON number would hand back a different card.
    // Alternatives Considered: a composite identity spanning more than one column. Rejected because
    // app/cpy/CVACT03Y.cpy L5 is the entire key of the cluster this record comes from, corroborated
    // by app/cbl/CBSTM03B.CBL L40 naming the leading PIC X(16) member declared at L67 as the record
    // key. A composite belongs to the re-keyed statement dataset instead, whose TRNX-KEY at
    // app/cpy/COSTM01.CPY L21 is the two sixteen-character fields at L22 and L23; borrowing that
    // shape here would key this relation on a column it does not have.
    // Assumptions: no index and no unique constraint is declared on this type. The by-account
    // access path the baseline surfaces as the CICS file CXACAIX becomes the non-unique secondary
    // index idx_card_xref_account_id, created on account.card_xref at
    // services/account-service/src/main/resources/db/migration/V1__account.sql L726 and owned by
    // account-service, so declaring it here would be a second declaration of one object. The
    // baseline's IDCAMS BLDINDEX step is retired outright rather than carried across, because
    // PostgreSQL maintains an index transactionally and leaves no separate build to run.
    // Assumptions: the member is named for the masked rendering it carries rather than for the
    // stored number, so that no caller reads it as a primary account number, while the column name
    // stays card_num because that is the alias the relation projects and the name the base column
    // carries as card_num CHAR(16) NOT NULL at
    // services/account-service/src/main/resources/db/migration/V1__account.sql L657. A mapped
    // column cannot be renamed on this side without breaking the read.
    // WHY : Refactoring Rationale: this member is NO LONGER the identifier of the type, and the
    //       change is the substance of the fix rather than a mapping preference. The relation
    //       projects this column as twelve asterisks followed by four digits, so two cards sharing
    //       their last four digits present one identical value -- and a JPA identifier is required
    //       to be unique per row. Two consequences followed from declaring it here and both were
    //       silent. The persistence context is keyed by identifier, so the second colliding row
    //       read in one session was answered from the first row's entry and the second cardholder's
    //       customer and account were never seen. And the report's join, which matched a
    //       transaction to its cross-reference row on this column, matched on four digits, so one
    //       transaction joined to every colliding row and the joined result carried more lines than
    //       the driving relation admitted. The identifier is now the fingerprint below, which is a
    //       function of the whole trimmed number, and this column is what it was always safe to be:
    //       a value to display.
    // WHY : Alternatives Considered: keeping the masked column as the identifier and adding a
    //       unique constraint to make the collision fail loudly instead of silently. Rejected
    //       because the collision is a property of the MASK and not of the data -- the base
    //       relation's own key is unique -- so the constraint would refuse a perfectly valid load
    //       and take the reporting context down for a condition the ledger is entitled to.
    @Column(name = "card_num", length = CARD_NUMBER_WIDTH, nullable = false, updatable = false)
    private String cardNum;

    // Assumptions: the fingerprint is a lower-case hexadecimal rendering of a SHA-256 digest, so it
    // is exactly 64 characters and is mapped as characters at that width. The relation computes it
    // at data-migration/sql/V1__reporting_views.sql over the single-row secret in
    // reporting.card_grouping_key concatenated with the trimmed card number, which is why it is
    // unique per card where the masked column is not: it is a function of all sixteen digits.
    // Assumptions: the value is never rendered to a client and never logged. Its purpose is
    // identity, join and ordering inside this context; a client that received it would hold a
    // stable per-card correlator across statements, which is precisely the linkability the masking
    // exists to remove. Every response type in this module carries the masked rendering instead,
    // and ReportingDtoMapper is the only place a card value reaches a response at all.
    // Assumptions: no length is asserted here beyond the mapped width. The digest's width is fixed
    // by the algorithm the relation names, so a value of another width would mean the relation had
    // been redefined -- a condition to discover as a read failure against the definition rather
    // than to re-derive from a constant on this side.
    // WHY : Assumptions: substituting this value for the card number as the identity, join and
    //       ordering key is registered as divergence D-REPORT-ORDER-FINGERPRINT in
    //       docs/architecture/cobol-to-service-traceability.md. That entry records what is preserved
    //       -- injectivity, so one card's rows still sort together and a group break still falls
    //       where the reference's does -- and what is not: the relative order BETWEEN two cards,
    //       because a digest orders differently from the number it digests.
    @Id
    @Column(name = "card_fingerprint", length = CARD_FINGERPRINT_WIDTH,
            nullable = false, updatable = false)
    private String cardFingerprint;

    // Alternatives Considered: mapping the customer identifier as characters, which is how the
    // baseline's own generic input-output module spells it, declaring FD-CUST-ID PIC X(09) at
    // app/cbl/CBSTM03B.CBL L72. Rejected because the copybook is normative under the migration
    // plan's transformation rule, and both app/cpy/CVACT03Y.cpy L6 and app/cpy/CUSTREC.cpy L5
    // declare the field PIC 9(09), a numeric picture. That alphanumeric spelling is an artifact of
    // the module's untyped thousand-byte transfer buffer, LK-M03B-FLDT PIC X(1000) at L112, through
    // which every field of all four of its files passes as characters; it is a transport handle and
    // not a competing declaration of the field's type. Following it here would make this column
    // disagree with the relation it joins to.
    // Assumptions: the target type agrees on both sides of that join. XREF-CUST-ID PIC 9(09) at
    // app/cpy/CVACT03Y.cpy L6 carries the same magnitude type as CUST-ID PIC 9(09) at
    // app/cpy/CUSTREC.cpy L5, and identically at app/cpy/CVCUS01Y.cpy L5 in the geometrically
    // identical second customer book. Disagreement would push every join on this column through a
    // coercion, which forfeits the index and can compare two spellings of one value unequal.
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    // Assumptions: the account identifier agrees on both sides of its join too, and here the
    // baseline agrees with itself. XREF-ACCT-ID PIC 9(11) at app/cpy/CVACT03Y.cpy L7 carries the
    // same magnitude type as ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy L5, and the same module that
    // spells the customer identifier alphanumerically spells this one numerically, as FD-ACCT-ID
    // PIC 9(11) at app/cbl/CBSTM03B.CBL L77. Leading zeros in the stored value are data rather than
    // formatting and a magnitude preserves the value they belong to; nine and eleven digits both
    // sit well inside the range this type covers.
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the persistence specification requires a mapped entity to declare a
     * constructor taking no arguments, so this one exists to satisfy the provider and not to serve
     * a caller. Visibility is protected because no caller outside this type's own hierarchy has any
     * use for a row carrying none of the three values declared at {@code app/cpy/CVACT03Y.cpy} L5
     * through L7, and protected is the widest visibility the requirement asks for.</p>
     */
    protected CardXrefView() {
        // Assumptions: the body is empty by design rather than unwritten. The provider assigns each
        // of the three mapped fields -- the 16-character key and the two identifiers declared at
        // app/cpy/CVACT03Y.cpy L5 through L7 -- directly after construction, so initialising any of
        // them here would write a value that every load immediately overwrites.
    }

    /**
     * Creates an instance from values already projected by the relation.
     *
     * <p>Alternatives Considered: validating the three arguments here. Rejected because all three
     * originate in a relation another context owns, whose base columns are already declared
     * {@code NOT NULL} at
     * {@code services/account-service/src/main/resources/db/migration/V1__account.sql} L657, L665
     * and L676, so a guard on this type would duplicate a constraint the database already asserts
     * while making this context an arbiter of data it does not own, and would fail a read of a row
     * the owning context wrote on purpose. Validation belongs where a value enters the system,
     * which for these three columns is the owning context's own write path.</p>
     *
     * @param cardNum {@code String} carrying the masked rendering of the card number the relation
     *     returns, being twelve asterisks followed by the last four digits, at the width
     *     {@code XREF-CARD-NUM PIC X(16)} declares at {@code app/cpy/CVACT03Y.cpy} L5
     * @param cardFingerprint {@code String} carrying the keyed per-card fingerprint the relation
     *     computes, at the width {@value #CARD_FINGERPRINT_WIDTH}, which is the identity of this row
     *     because the masked rendering above is not unique per card
     * @param customerId {@code Long} identifying the customer this card belongs to, declared
     *     {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy} L6 and carried as a
     *     magnitude so its leading zeros survive
     * @param accountId {@code Long} identifying the account this card is issued against, declared
     *     {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy} L7 and the sole source of
     *     the account column the report prints at {@code app/cbl/CBTRN03C.cbl} L364
     */
    public CardXrefView(String cardNum, String cardFingerprint, Long customerId, Long accountId) {
        this.cardNum = cardNum;
        this.cardFingerprint = cardFingerprint;
        this.customerId = customerId;
        this.accountId = accountId;
    }

    /**
     * Returns the masked rendering of the card number this row is keyed by.
     *
     * @return {@code String} holding the {@code card_num} column, being twelve asterisks and four
     *     digits at the declared width of {@value #CARD_NUMBER_WIDTH}
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Returns the keyed per-card fingerprint that identifies this row.
     *
     * <p>Assumptions: this is the value to join on, order by and select by, because it is the only
     * column of the relation that is a function of the whole card number. The masked rendering above
     * is four digits behind a constant filler, so a predicate on it names a tail rather than a card.
     * </p>
     *
     * <p>Trade-offs: the value is returned rather than kept private, because the report's join and
     * the statement path's per-card traversal both need to name it, and both live outside this type.
     * What that costs is that a caller could put it in a response or a log line, which would hand a
     * client a stable per-card correlator; what stops that is that neither
     * {@code ReportingDtoMapper} nor any response type in this module declares a member for it, and
     * this type's own {@code toString()} omits it.</p>
     *
     * @return {@code String} holding the {@code card_fingerprint} column at the declared width of
     *     {@value #CARD_FINGERPRINT_WIDTH}, or {@code null} where no value has been projected yet
     */
    public String getCardFingerprint() {
        return cardFingerprint;
    }

    /**
     * Returns the customer this card belongs to.
     *
     * @return {@code Long} holding the {@code customer_id} column, which the statement path uses to
     *     read the customer row from the paragraph {@code 2000-CUSTFILE-GET.} at
     *     {@code app/cbl/CBSTM03A.CBL} L368
     */
    public Long getCustomerId() {
        return customerId;
    }

    /**
     * Returns the account this card is issued against.
     *
     * @return {@code Long} holding the {@code account_id} column, which the statement path uses to
     *     read the account row from the paragraph {@code 3000-ACCTFILE-GET.} at
     *     {@code app/cbl/CBSTM03A.CBL} L392 and which the report prints directly at
     *     {@code app/cbl/CBTRN03C.cbl} L364
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Reports whether another object denotes the same cross-reference row as this one.
     *
     * <p>Assumptions: equality rests on the key alone. Including either identifier would make two
     * loads of one row compare unequal if the owning context changed either of them between the
     * loads, which would make row identity depend on data this context neither owns nor controls.
     * </p>
     *
     * <p>Refactoring Rationale: the key this rests on is the fingerprint and no longer the masked
     * card number, and the prose that justified the masked one is withdrawn rather than adjusted. It
     * argued that the mask was sound "because the relation holds one row per card and the mask
     * preserves the width ... so two masked values are equal only when their two four-digit tails
     * are equal" -- and that last clause is the defect stated as if it were the guarantee. Equal
     * tails is exactly what two DIFFERENT cards can have, so the old equality reported two distinct
     * cardholders' rows equal, and every hash-based collection and every persistence-context lookup
     * keyed on it silently kept the first and discarded the second. The fingerprint is a function of
     * all sixteen digits, so equal fingerprints means one card.</p>
     *
     * <p>Assumptions: the test is a pattern match rather than an exact-class comparison because
     * every instance a caller compares arrives from the persistence provider rather than from a
     * constructor -- this module's own
     * {@code com.carddemo.reporting.repository.StatementCardXrefRepository} declares itself over
     * this type at its L61 -- and a provider may hand back a generated subclass, which an
     * exact-class comparison would then report unequal to a plain instance of the same row.</p>
     *
     * @param other {@code Object} to compare against, which may be of any type and may be
     *     {@code null}
     * @return {@code boolean} that is {@code true} when the argument is a cross-reference
     *     projection carrying an equal per-card fingerprint, and {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardXrefView that)) {
            return false;
        }
        return Objects.equals(this.cardFingerprint, that.cardFingerprint);
    }

    /**
     * Returns a hash consistent with the key-only equality this type defines.
     *
     * <p>Assumptions: the hash is taken over exactly the one field equality is taken over, the
     * per-card fingerprint, because a hash covering any other mapped field would place two instances
     * that compare equal in two different buckets and break every hash-based collection holding
     * them.</p>
     *
     * @return {@code int} hash of the per-card fingerprint, or zero when no value has been projected
     *     into this instance yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.cardFingerprint);
    }

    /**
     * Returns a diagnostic rendering naming the type and none of the three projected members.
     *
     * <p>Assumptions: all three members are withheld. The card number is omitted outright rather than
     * abbreviated, because abbreviating a card number is masking and masking belongs to this context's
     * mapper package, which the module charter names as the sole boundary where it may appear; a
     * second, slightly different masking rule here would give one value two renderings and make
     * neither authoritative. The customer and account identifiers are omitted because
     * {@code docs/architecture/observability.md} names both among the values a diagnostic must omit
     * rather than abbreviate. Nothing remains that the rule permits a rendering to keep, so this one
     * names its type alone, which is exactly what that rule prescribes for that case.</p>
     *
     * <p>Refactoring Rationale: this rendering kept both identifiers and its own documentation argued
     * that a masked card number "beside an 11-digit account identifier is a narrower gap than either of
     * them alone" -- reasoning about the card number while treating the account identifier as free.
     * The authority it appealed to prohibits both, and the sentence is instructive precisely because it
     * shows how a rendering can be carefully argued and still disclose: the argument was about which
     * value to omit rather than about whether the rule admitted a choice. It is recorded here rather
     * than deleted so that the correction reads as a correction.</p>
     *
     * <p>Trade-offs: a log line written from this type can no longer say which cross-reference row it
     * describes. What pays that down is the same mechanism the observability authority names
     * everywhere else -- the correlation identifier on every request-scoped line and the
     * {@code batch.batch_run} step ledger for batch work.</p>
     *
     * @return {@code String} rendering on a single line, naming this type alone and no projected
     *     member, never {@code null}
     */
    @Override
    public String toString() {
        return "CardXrefView[]";
    }
}
