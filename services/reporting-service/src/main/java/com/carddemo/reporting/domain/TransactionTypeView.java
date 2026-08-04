package com.carddemo.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of the transaction type reference relation that the transaction detail
 * report joins against.
 *
 * <p>The reference baseline reads that relation as an indexed file.
 * {@code app/cbl/CBTRN03C.cbl} declares it at L39 as
 * {@code SELECT TRANTYPE-FILE ASSIGN TO TRANTYPE}, indexed at L40, keyed at L42 by the single
 * elementary item {@code FD-TRAN-TYPE}, and copies its record layout at L103 from
 * {@code app/cpy/CVTRA03Y.cpy}. The migrated equivalent is {@code transaction_types} in the
 * {@code reference} schema, read through a database role holding {@code SELECT} and nothing
 * else, and this type is the row shape a query over that relation returns. </p>
 *
 * <h2>Record geometry</h2>
 *
 * <p>The copybook is normative under the migration plan's transformation rule, so each declared
 * width settles a column type, a Java type and a byte position, and nothing else does. The
 * shared type-mapping rulings those columns follow are stated once in this package's charter
 * and are not repeated here. </p>
 *
 * <pre>
 * CVTRA03Y.cpy  baseline field   PIC     one-based   target column          Java type
 * L5            TRAN-TYPE        X(02)     1 to 2    type_cd   CHAR(2)      String
 * L6            TRAN-TYPE-DESC   X(50)     3 to 52   type_desc VARCHAR(50)  String
 * L7            FILLER           X(08)    53 to 60   not mapped             none
 *                                        ---------
 *                                         60 bytes   2 + 50 = 52 significant, and 52 + 8 = 60
 * </pre>
 *
 * <p>Two independent oracles confirm those figures from sources with no copybook in them at all.
 * {@code app/cbl/CBTRN03C.cbl} declares {@code FD TRANTYPE-FILE} at L72 with exactly two
 * members, {@code FD-TRAN-TYPE PIC X(02)} at L74 and {@code FD-TRAN-DATA PIC X(58)} at L75,
 * which sum to 60 and which put the key width at two characters. The seed extract
 * {@code app/data/ASCII/trantype.txt} then confirms the positions themselves: all 7 of its rows
 * are exactly 60 characters wide, each carrying a two-character code at positions 1 and 2, a
 * blank-padded description across positions 3 through 52, and 8 further characters at positions
 * 53 through 60 that no consumer reads. Three unrelated sources agreeing is why these positions
 * are asserted here rather than estimated. </p>
 *
 * <h2>What the report takes from this record</h2>
 *
 * <p>{@code app/cbl/CBTRN03C.cbl} assembles each detail line in the paragraph that begins at
 * L361. Its L366 reads {@code MOVE TRAN-TYPE-DESC TO TRAN-REPORT-TYPE-DESC}, so the description
 * carried on this record is what the report emits, while its L365 reads
 * {@code MOVE TRAN-TYPE-CD OF TRAN-RECORD TO TRAN-REPORT-TYPE-CD}, so the code the report prints
 * is taken from the transaction record rather than from here. This record's key therefore exists
 * to satisfy the join, and its description is the payload. That division is the whole reason the
 * type has exactly two members and needs no third. </p>
 *
 * <h2>Decisions</h2>
 *
 * <p>What follows discharges the obligation user-specified Rule 1 (Explainability) states at
 * L43, using the four categories it names at L31-L34, for every choice here that a reasonable
 * alternative could have gone the other way on. L40 forbids leaving such a choice undocumented
 * and L41 forbids a rationale carrying no specific justification, so each entry names the line,
 * the declared width or the byte count it rests on. </p>
 *
 * <p>Trade-offs: this type is mapped immutable and carries no optimistic-locking version
 * column, no cascade, no setter and no write path of any kind. The reference baseline
 * establishes that nothing is lost: {@code app/cbl/CBTRN03C.cbl} opens the file at L432 with
 * {@code OPEN INPUT TRANTYPE-FILE}, its only verb against the file is the keyed
 * {@code READ TRANTYPE-FILE INTO TRAN-TYPE-RECORD} at L495, and the program contains no
 * record-replacing and no record-removing verb at all. What the compromise buys is <b>where</b>
 * an accidental write is refused. With no setter declared, an assignment to a loaded row does
 * not compile, which is the earliest and most local refusal available; the immutable mapping
 * then excludes the type from dirty checking, so no update statement can be emitted for it even
 * reflectively. Unmapped and mutable, that same assignment would instead travel to the database
 * and be refused by the {@code SELECT}-only role, surfacing as an opaque privilege error at a
 * call site far from the line that caused it. What is given up is real and is accepted
 * deliberately: dirty checking, and the convenience of merging a detached instance. A value
 * that needs changing is changed by the context that owns the underlying relation. </p>
 *
 * <p>Alternatives Considered: this type maps a relation it does not own, and this context
 * declares no table, no index and no relational object of its own; the migration plan's
 * schema-ownership table records this context with no owned tables. Two other shapes were
 * evaluated. Holding a local table of transaction types here was rejected because
 * reference-service already owns those rows -- all 7 of them, as
 * {@code app/data/ASCII/trantype.txt} ships them -- so a local copy would become a second truth
 * for figures whose only purpose is to restate the first one exactly. Reading a replica was
 * rejected on the plan's own ground that a replica adds cost and replica-lag semantics for no
 * parity
 * benefit: a report whose type descriptions disagree with the reference rows by one replication
 * interval is a support case rather than a feature. What remains is read-only access, which is
 * why this type owns nothing. </p>
 *
 * <p>Assumptions: agreement with reference-service runs through the physical relation and the
 * narrowly-scoped read-only privilege behind it, never through code, and this type therefore
 * declares no relationship annotation to any sibling projection in either direction. The report
 * join is composed in the repository layer, by query. Two independent mechanisms hold that line.
 * The ArchUnit layering test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * forbids one context's domain package importing another's, and being a test it cannot rot; and
 * this module's {@code pom.xml} declares exactly one dependency inside the reactor, the shared
 * kernel, so no other service module is on the compile classpath to be imported from in the
 * first place. That is the discipline the reference baseline read under too, where
 * {@code app/jcl/TRANREPT.jcl} handed its four data inputs to the program through job control at
 * L65-L72 and never through a compile-time bond. The database half is authored elsewhere and is
 * cited by path: {@code data-migration/sql/V0__schemas_and_roles.sql} establishes the schemas,
 * the roles and the {@code SELECT}-only privileges, and the relations this context reads through
 * belong to a data-migration step ordered after the per-service migrations. A relation absent at
 * run time is a defect to report against those artifacts and never one to work around from
 * inside this type. </p>
 *
 * <p>Assumptions: every layout is scoped to its owning copybook and every citation names that
 * copybook, because a single repository-wide field-name map cannot be built. The same two-byte
 * transaction type code is declared under two different identifiers: {@code TRAN-TYPE}, carrying
 * no code suffix, at {@code app/cpy/CVTRA03Y.cpy} L5, and {@code TRAN-TYPE-CD} at both
 * {@code app/cpy/CVTRA04Y.cpy} L6 and {@code app/cpy/CVTRA05Y.cpy} L6. The hazard also runs the
 * other way, with {@code TRAN-TYPE-CD} and {@code TRAN-CAT-CD} declared in
 * {@code app/cpy/CVTRA05Y.cpy} at L6 and L7 and again in {@code app/cpy/CVTRA04Y.cpy} at L6 and
 * L7, and the group name {@code TRAN-CAT-KEY} measuring six bytes at
 * {@code app/cpy/CVTRA04Y.cpy} L5 but seventeen at {@code app/cpy/CVTRA01Y.cpy} L5. One name
 * would therefore have to resolve to two widths. The reference baseline practises exactly this
 * discipline and it is copied here: {@code app/cbl/CBTRN03C.cbl} qualifies precisely the two
 * ambiguous names, at L365 and L367, and leaves the unambiguous {@code TRAN-TYPE-DESC} bare at
 * L366 because that identifier exists only in {@code app/cpy/CVTRA03Y.cpy} L6. </p>
 *
 * <p>Alternatives Considered: the code member is named for the role the value plays rather than
 * for the suffix-less identifier the reference baseline uses, and the target column follows it.
 * Naming the member after {@code TRAN-TYPE} at {@code app/cpy/CVTRA03Y.cpy} L5 was the
 * alternative and was rejected on two grounds. The join column on the other side of the relation
 * is the one the migration plan names for the category table's foreign key, so a bare member
 * name would leave the two ends of a single two-character join lexically unrelated; and the bare
 * word would collide at every call site with the ordinary English sense of a type. The missing
 * suffix at L5 is <b>not</b> an error in the reference baseline, which stays byte-identical and
 * keeps running: the baseline declares {@code TRAN-TYPE}, this Java names the member for the
 * code, and the divergence is documented here rather than treated as a defect anywhere. The
 * category label is Alternatives Considered rather than Refactoring Rationale precisely because
 * L32 scopes that other label to replacing existing code, and nothing here replaces
 * anything. </p>
 *
 * <p>Assumptions: the two fifty-character descriptions in this neighbourhood are kept distinct
 * and are never conflated. {@code TRAN-TYPE-DESC} at {@code app/cpy/CVTRA03Y.cpy} L6 describes a
 * transaction <b>type</b>; {@code TRAN-CAT-TYPE-DESC} at {@code app/cpy/CVTRA04Y.cpy} L8
 * describes a transaction <b>category</b>. Both are {@code X(50)}, so width alone cannot tell
 * them apart, and {@code app/cbl/CBTRN03C.cbl} moves them to two different report fields on two
 * consecutive lines, at L366 and L368. The member below is spelled out in full for that reason,
 * so that it cannot be read at a call site as the category description. </p>
 *
 * <p>Assumptions: the trailing {@code FILLER PIC X(08)} at {@code app/cpy/CVTRA03Y.cpy} L7,
 * occupying one-based positions 53 through 60, is not mapped, and the migration plan's
 * normative-copybook rule requires that omission to be recorded per record, which is what this
 * entry is. That field is padding to the declared record length and carries nothing a reader of
 * a report could act on, so mapping it would add a column whose only content is blanks. The
 * arithmetic is stated because it is what makes the omission auditable: 2 plus 50 is 52
 * significant bytes, and 52 plus 8 is 60 exactly. </p>
 *
 * <p>Trade-offs: the two-character code is carried as a character string of declared width two
 * and is deliberately not modelled as an enumerated type, a variable-width string or a numeric
 * type. {@code app/cpy/CVTRA03Y.cpy} L5 declares it {@code X(02)}, so the width is part of the
 * key contract and a variable-width column would discard it, while a numeric column would
 * reject a code carrying a letter. An enumerated type was the tempting alternative, because two
 * characters looks like a closed set and the seed extract {@code app/data/ASCII/trantype.txt}
 * ships only 7 of them, the codes 01 through 07. It is rejected precisely because 7 is a count
 * of rows and not a constraint: the code domain is reference data that reference-service seeds
 * and maintains in the relation itself, not a compile-time constant, so an enumerated type fixed
 * at those 7 would fail on the eighth, and it would fail inside this context, which owns neither
 * the rows nor the decision to add one. The compromise accepted
 * is that no compiler check constrains the value; what is bought is a projection that keeps
 * reading correctly after the owning context adds a code. </p>
 *
 * <p>Assumptions: the join column carries the same target type on all three sides even though
 * the identifiers differ. {@code TRAN-TYPE} at {@code app/cpy/CVTRA03Y.cpy} L5,
 * {@code TRAN-TYPE-CD} at {@code app/cpy/CVTRA05Y.cpy} L6 and {@code TRAN-TYPE-CD} at
 * {@code app/cpy/CVTRA04Y.cpy} L6 are each {@code X(02)} and each become a two-character column,
 * so the report join compares like with like and needs no coercion. The differing identifiers do
 * not imply differing types, which is the whole point of scoping a layout to its owning copybook
 * rather than to a name. </p>
 *
 * <p>Assumptions: this type declares no foreign key, no check constraint, no unique constraint,
 * no index and no inverse collection of categories. The migration plan puts a foreign key from
 * the transaction-category relation to this one with a restricting delete rule, built on the
 * two-character {@code TRAN-TYPE-CD} that {@code app/cpy/CVTRA04Y.cpy} declares at L6 as the
 * leading member of its six-byte {@code TRAN-CAT-KEY} group at L5, and preserving the
 * referential semantic the reference baseline asserts through its {@code XTRNTYCAT} index. That
 * constraint is declared by reference-service's own migration and owned there. The
 * behaviour it produces, a delete of a still-referenced type being refused rather than
 * cascading, surfaces to callers as a conflict response from that service and not from this
 * context. An inverse collection is the specific temptation, because a type does have categories
 * in the domain sense; it is refused because it would both manufacture a mapped association
 * across a context boundary that this package forbids and load a collection of unbounded size
 * into a single report row. </p>
 *
 * <p>Assumptions: this projection serves the report join only. {@code app/jcl/TRANREPT.jcl}
 * supplies it at L69-L70 as {@code TRANTYPE}, and {@code app/cbl/CBTRN03C.cbl} corroborates by
 * copying the layout at L103. It has no part in the statement join:
 * {@code app/jcl/CREASTMT.JCL} lists only {@code TRNXFILE} at L83, {@code XREFFILE} at L84,
 * {@code ACCTFILE} at L85 and {@code CUSTFILE} at L86, and {@code app/cbl/CBSTM03A.CBL} copies
 * exactly four books, at L51, L53, L55 and L57, none of them this one. The statement generator
 * never reads a transaction type record. Note also that {@code app/cpy/CVTRA07Y.cpy}, which
 * {@code app/cbl/CBTRN03C.cbl} copies at L113, gets no projection anywhere in this package: it
 * declares the report output bands rather than an input record, so a projection built from it
 * would be a formatted line masquerading as a persisted row. </p>
 *
 * <p>Assumptions: no seeded row, no literal code list and no data value of any kind appears in
 * this type. The transaction type rows are loaded by reference-service from the reference
 * baseline's own extract, {@code app/data/ASCII/trantype.txt}, which ships 7 rows of 60
 * characters carrying the codes 01 through 07; a list of those 7 restated here would become a
 * second truth that drifts silently the moment that context adds an eighth. Nor does any
 * statement of data definition or privilege appear here; this module carries no schema-migration
 * directory at all, and one appearing under it would itself be a defect. </p>
 *
 * <p>Assumptions: this type encodes no business rule. It exposes the projected code and the
 * projected description and performs no validation, no lookup and no derivation, so nothing in
 * it can disagree with the context that owns the rows. The reference baseline puts the one rule
 * that touches this relation in the reading program rather than in the record:
 * {@code app/cbl/CBTRN03C.cbl} treats a key it cannot find as a terminating condition at L496
 * through L500. Rejecting a value the owning relation produced would make this context an
 * arbiter of data it does not own, and it would additionally forfeit the single-line accessor
 * form that Rule 1 permits at L23, because an accessor that computes anything is no longer
 * trivial. </p>
 *
 * <p>Assumptions: this projection carries no money member and no timestamp member, and imports
 * neither a money type nor a date-time type. No {@code S9(n)V99} field and no {@code X(26)}
 * field appears anywhere at {@code app/cpy/CVTRA03Y.cpy} L5 through L7 -- the record is two
 * character fields and one unmapped pad -- so adding either for symmetry with the transaction or
 * account projections would invent a column the source record never held. </p>
 *
 * <p>Assumptions: no card entity and no card-derived member appears here.
 * {@code app/cpy/CVACT02Y.cpy} appears in neither program's copybook set, neither the five at
 * {@code app/cbl/CBTRN03C.cbl} L93, L98, L103, L108 and L113 nor the four at
 * {@code app/cbl/CBSTM03A.CBL} L51, L53, L55 and L57, and no card file appears in either
 * member's input list. The schemas the reference baseline's job control actually exercises are
 * {@code ledger}, {@code account} and {@code reference} alone. A reader who cannot find a card
 * read in either program has not overlooked one. </p>
 *
 * <p>Alternatives Considered: identity is a single column, not a composite. The reference
 * baseline settles this twice over: {@code app/cpy/CVTRA03Y.cpy} L5 is the whole key of the
 * indexed file, and {@code app/cbl/CBTRN03C.cbl} L42 declares
 * {@code RECORD KEY IS FD-TRAN-TYPE}, one elementary item two characters wide. A composite key
 * was the alternative and it belongs to the category record instead, whose L48 declares
 * {@code RECORD KEY IS FD-TRAN-CAT-KEY} over the six-byte group at
 * {@code app/cpy/CVTRA04Y.cpy} L5. Carrying a composite here would model a key the file does not
 * have. </p>
 *
 * <p>Alternatives Considered: this is an ordinary class rather than a Java record, even though
 * two immutable character members are exactly the shape a record expresses best. A record cannot
 * be a persistence entity: the specification requires an entity class to be non-final, to
 * declare a constructor taking no arguments, and to hold its persistent state in non-final
 * fields the provider can populate, and a record is final with final components and no such
 * constructor. Library-generated accessors were rejected independently, because a generated
 * accessor has no source line on which the Javadoc that Rule 1 L15 requires could be written,
 * and no exemption is available: the audit configuration at
 * {@code config/checkstyle/checkstyle.xml} enables no comment-driven and no annotation-driven
 * suppression filter, and its companion suppressions file reaches only generated sources and
 * test fixtures. Explicit constructors and explicit accessors reach the same brevity by a route
 * that keeps every member documentable. </p>
 *
 * <p>Assumptions: the declared width and the not-null flag on each column below are metadata
 * recorded at the mapping site, not assertions evaluated on the read path. This context emits no
 * data-definition statement and never writes, so those attributes neither create nor police the
 * relation; what they do is put each copybook width where the column is declared, so a reader
 * auditing a width does not have to leave the file to find it. The not-null flag records a
 * guarantee the source record genuinely gives, since a fixed-width record has no representation
 * for absence: every one of the 60 bytes at {@code app/cpy/CVTRA03Y.cpy} L5 through L7 is always
 * present, blank-padded where it carries no value. </p>
 *
 * <h2>Documentation contract</h2>
 *
 * <p>Every member below carries the Javadoc that user-specified Rule 1 (Explainability) requires
 * at L15, in the block form L22 names for Java, stating the elements L18 through L21 enumerate.
 * The overriding methods are documented in full rather than deferring to the supertype, because
 * L15 exempts nothing and an inherited contract states none of the four elements for the
 * implementation that overrides it. The written convention all of this conforms to is stated
 * once at {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and owned elsewhere. A
 * green audit run is evidence for the docstring half of L43's conjunctive gate only; the
 * rationale half is the labelled entries above, and only a reader can judge those. </p>
 *
 * <p>Trade-offs: the two accessors below do <b>not</b> use the single-line form Rule 1 L23
 * permits for a trivial accessor, and the reason is recorded here because the omission looks
 * like an oversight and is not one. That form was written first and the audit rejected it: on
 * the pinned engine, the summary check reports a missing summary sentence for a block whose only
 * content is an at-clause, while the completeness check requires a return tag that is
 * recognised only at the start of a line, so no single physical line satisfies both at once. The
 * two demands are jointly unsatisfiable for a value-returning accessor rather than merely
 * awkward. L23 grants a permission and imposes no obligation, so declining a permission the
 * gate has made unusable cannot breach it, and the fuller form states strictly more than L23
 * asks for. The cost accepted is four lines per accessor instead of one; what is refused is the
 * alternative of loosening the audit configuration, which is owned elsewhere and which this type
 * has no standing to weaken. </p>
 */
@Entity
@Immutable
@Table(name = "transaction_types", schema = "reference")
public class TransactionTypeView {

    // Assumptions: the reference baseline names this field TRAN-TYPE at app/cpy/CVTRA03Y.cpy L5,
    // with no code suffix, while app/cpy/CVTRA04Y.cpy L6 and app/cpy/CVTRA05Y.cpy L6 name the
    // identical X(02) value TRAN-TYPE-CD. The member and the column are named for the role the
    // value plays, so that both ends of the two-character report join read alike; the divergence
    // from the suffix-less baseline identifier is recorded in the Decisions section above.
    @Id
    @Column(name = "type_cd", length = 2, nullable = false, updatable = false)
    private String typeCd;

    // Assumptions: this is the TYPE description at app/cpy/CVTRA03Y.cpy L6, and it is a different
    // field from the CATEGORY description TRAN-CAT-TYPE-DESC at app/cpy/CVTRA04Y.cpy L8. Both are
    // X(50), so the name is spelled out in full because width cannot distinguish them and
    // app/cbl/CBTRN03C.cbl moves them to two different report fields at L366 and L368.
    @Column(name = "type_desc", length = 50, nullable = false, updatable = false)
    private String typeDescription;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a persistence entity to declare a constructor
     * taking no arguments, which the provider invokes before writing the two projected columns
     * into the fields above -- the two declared at {@code app/cpy/CVTRA03Y.cpy} L5 and L6.
     * Visibility is protected rather than public because no caller outside this type's own
     * hierarchy has a use for a half-built row, and protected is the widest visibility the
     * specification's requirement actually needs. </p>
     */
    protected TransactionTypeView() {
        // Assumptions: the body is empty by design rather than unfinished. The provider assigns
        // both of the fields declared from app/cpy/CVTRA03Y.cpy L5 and L6 directly after
        // construction, so initialising either here would write a value that is immediately
        // overwritten on every load.
    }

    /**
     * Creates an instance from an already-projected code and description pair.
     *
     * <p>Alternatives Considered: this constructor validates nothing, and rejecting an argument
     * was evaluated and refused. Both values originate in a relation reference-service owns, so a
     * guard here would make this context an arbiter of data it does not own and would fail a read
     * of a row the owning context wrote on purpose. It also keeps the accessors trivial in the
     * sense Rule 1 L23 uses, which an argument-checking type could not claim. The compromise is
     * that a caller assembling an instance by hand, in a test or a mapper, can build one the
     * relation would not produce; that trade is accepted because the alternative moves a
     * validation decision into the one context with no authority to make it. </p>
     *
     * @param typeCd the two-character transaction type code that keys this row, declared as
     *     {@code TRAN-TYPE PIC X(02)} at {@code app/cpy/CVTRA03Y.cpy} L5 and carried in the
     *     {@code type_cd} column
     * @param typeDescription the fifty-character description of that type, declared as
     *     {@code TRAN-TYPE-DESC PIC X(50)} at {@code app/cpy/CVTRA03Y.cpy} L6, carried in the
     *     {@code type_desc} column, and distinct from the category description at
     *     {@code app/cpy/CVTRA04Y.cpy} L8
     */
    public TransactionTypeView(String typeCd, String typeDescription) {
        this.typeCd = typeCd;
        this.typeDescription = typeDescription;
    }

    /**
     * Returns the two-character transaction type code this row is keyed by.
     *
     * @return the value of the {@code type_cd} column, which is what the report join matches on
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Returns the description of this transaction type.
     *
     * @return the value of the {@code type_desc} column, which is what
     *     {@code app/cbl/CBTRN03C.cbl} L366 emits onto each report detail line
     */
    public String getTypeDescription() {
        return typeDescription;
    }

    /**
     * Reports whether another object denotes the same transaction type row as this one.
     *
     * <p>Alternatives Considered: equality rests on the code alone and deliberately excludes the
     * description. Including it was the alternative and was rejected, because two loads of one row
     * would then compare unequal if reference-service reformatted the fifty characters at
     * {@code app/cpy/CVTRA03Y.cpy} L6 between them, which would make row identity depend on text
     * this context does not control. The code is the whole key of the indexed file, as
     * {@code app/cbl/CBTRN03C.cbl} L42 declares, so it alone settles which row is meant. </p>
     *
     * <p>Assumptions: the test is a pattern match rather than an exact-class comparison because a
     * persistence provider may hand back a generated subclass of a mapped type, and an
     * exact-class comparison would then report two representations of one row unequal. </p>
     *
     * @param other the object to compare against, which may be of any type and may be null
     * @return true when the argument is a transaction type projection carrying an equal code, and
     *     false otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionTypeView that)) {
            return false;
        }
        return Objects.equals(this.typeCd, that.typeCd);
    }

    /**
     * Returns a hash consistent with the code-only equality above.
     *
     * <p>Assumptions: only the code is hashed, for the same reason only the code is compared. A
     * hash drawn from the description as well would change when the owning context reformatted
     * that text, which would move an instance already held in a hash-based collection and break
     * the contract this method shares with equality. </p>
     *
     * @return the hash of the two-character code, or zero when no code has been projected yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.typeCd);
    }

    /**
     * Returns a diagnostic rendering of both projected members.
     *
     * <p>Assumptions: both members are rendered in full because neither is sensitive. No primary
     * account number, no card verification value and no national identifier appears anywhere at
     * {@code app/cpy/CVTRA03Y.cpy} L5 through L7 -- the record holds a two-character code, a
     * fifty-character description and an unmapped pad -- so the masking the wider context applies
     * to account and card data has nothing to act on here, and a partially rendered form would
     * withhold the only two values a reader of a failure needs. </p>
     *
     * <p>Trade-offs: this form is for diagnostics and is not a report contract. The byte-exact
     * 133-column assembly belongs to the service layer, which composes it from the projected
     * members rather than from this rendering, so widening or reordering this string cannot
     * disturb report output. </p>
     *
     * @return a single-line rendering naming the type and both projected members
     */
    @Override
    public String toString() {
        return "TransactionTypeView[typeCd=" + typeCd + ", typeDescription=" + typeDescription + "]";
    }
}
