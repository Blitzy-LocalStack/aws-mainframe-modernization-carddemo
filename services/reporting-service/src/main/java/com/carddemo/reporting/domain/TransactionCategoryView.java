package com.carddemo.reporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import org.hibernate.annotations.Immutable;

/**
 * Read-only projection of the transaction-category reference row that the transaction detail
 * report joins to in order to obtain a category description.
 *
 * <h2>Purpose</h2>
 *
 * <p>This type carries one row of the transaction-category reference data: a two-part code and
 * the description that belongs to it. Three columns are mapped and no more, across two declared
 * fields, because the two code columns are carried together by the embedded identifier that
 * mirrors the group item the source record declares. Being a type declaration rather than a
 * behaviour, it accepts no parameters, yields no value and raises nothing, so of the four content
 * elements user-specified Rule 1 (Explainability) enumerates at L18-L21 only Purpose at L18
 * applies at this level; the Parameters element at L19 is discharged on the nested identifier,
 * whose components are parameters of its canonical constructor, and the Return values element at
 * L20 on each accessor below. The written convention this file conforms to is stated once at
 * {@code docs/CODE_DOCUMENTATION_STANDARD.md}, cited by path and owned elsewhere.
 *
 * <h2>The source record, and its geometry</h2>
 *
 * <p>{@code app/cpy/CVTRA04Y.cpy} is the normative source. It declares
 * {@code 01 TRAN-CAT-RECORD} at L4 and the group {@code 05 TRAN-CAT-KEY} at L5, whose two
 * subordinate items are {@code TRAN-TYPE-CD PIC X(02)} at L6 and {@code TRAN-CAT-CD PIC 9(04)} at
 * L7; {@code TRAN-CAT-TYPE-DESC PIC X(50)} follows at L8 and {@code FILLER PIC X(04)} at L9. The
 * arithmetic closes exactly and is re-derived here rather than quoted: the group at L5 is 2 plus
 * 4, so 6; the significant bytes are 6 plus 50, so 56; and 56 plus 4 is 60, which is the record
 * length the copybook's own header comment states at L2. One-based positions are therefore 1
 * through 2 for the type code, 3 through 6 for the category code, 7 through 56 for the
 * description and 57 through 60 for the padding.
 *
 * <p>An oracle outside the copybook confirms both figures independently, which is worth naming
 * because every position above depends on them. {@code app/jcl/TRANCATG.jcl} defines the cluster
 * holding these rows with {@code KEYS(6 0)} at L40 and {@code RECORDSIZE(60 60)} at L41: a
 * six-byte key at the front of a sixty-byte record, agreeing with the group width and the record
 * length derived above from a source that contains no copybook at all.
 *
 * <h2>Decisions</h2>
 *
 * <p>What follows discharges the obligation user-specified Rule 1 (Explainability) states at L43,
 * using the four categories it names at L31-L34, for each choice here that a reasonable
 * alternative could have gone the other way on. L40 forbids leaving such a choice undocumented
 * and L41 forbids a rationale without specific justification, so every entry names the line, the
 * declared width or the byte count it rests on. The register is deliberately longer than the
 * three mapped columns would suggest, because the number of decisions is not a function of the
 * number of fields.
 *
 * <p>Assumptions: the identifier {@code TRAN-CAT-KEY} names two different group items in two
 * different copybooks, so every layout below is scoped to its owning copybook and every citation
 * names that copybook. At {@code app/cpy/CVTRA04Y.cpy} L5 the group is 6 bytes over two
 * subordinate items, {@code TRAN-TYPE-CD} at L6 and {@code TRAN-CAT-CD} at L7, under the
 * {@code 01 TRAN-CAT-RECORD} declared at L4, in a 60-byte record. At
 * {@code app/cpy/CVTRA01Y.cpy} L5 the same identifier names a 17-byte group over three
 * subordinate items, {@code TRANCAT-ACCT-ID PIC 9(11)} at L6, {@code TRANCAT-TYPE-CD PIC X(02)}
 * at L7 and {@code TRANCAT-CD PIC 9(04)} at L8, whose member prefix is {@code TRANCAT-} with no
 * interior hyphen, under the {@code 01 TRAN-CAT-BAL-RECORD} declared at L4, in a 50-byte record.
 * A single repository-wide field name map is therefore impossible: it would have to resolve one
 * identifier to two widths. The baseline itself practises exactly this discipline, and that is
 * the precedent followed here: {@code app/cbl/CBTRN03C.cbl} qualifies precisely the two ambiguous
 * names, moving the type code at L365 and the category code at L367 with an explicit
 * {@code OF TRAN-RECORD} qualifier, while L368 moves {@code TRAN-CAT-TYPE-DESC} bare because that
 * name is unique to this copybook and needs no qualifier.
 *
 * <p>Assumptions: this is the category REFERENCE row and never the category BALANCE row, and the
 * two are not interchangeable. {@code app/cpy/CVTRA01Y.cpy} declares
 * {@code 01 TRAN-CAT-BAL-RECORD} at L4 and carries a monetary item,
 * {@code TRAN-CAT-BAL PIC S9(09)V99} at L9, over a key whose leading component is an account
 * identifier at L6; it is an account-scoped ledger row, its target is
 * {@code ledger.transaction_category_balances} and transaction-service owns it. The record
 * projected here has no account in its key and no monetary item anywhere in L5 through L9: it is
 * a two-part code with a description. Projecting the wrong one of the two would substitute
 * account-scoped balances for reference data while still compiling and still reading rows.
 *
 * <p>Assumptions: {@code TRAN-CAT-TYPE-DESC} at {@code app/cpy/CVTRA04Y.cpy} L8 is the CATEGORY
 * description, notwithstanding the word TYPE inside its identifier, and it is kept distinct from
 * the TYPE description, which is a separate 50-byte item named {@code TRAN-TYPE-DESC} at
 * {@code app/cpy/CVTRA03Y.cpy} L6. Two items of identical declared width and different meaning
 * are the easiest pair in this package to interchange, and the target schema does nothing to
 * separate them: the two relations name their column {@code description} alike, so the relation
 * is what distinguishes them rather than the column name. The accessor below is therefore named
 * for the category explicitly, so that no reader or caller can take it for the type description
 * that reaches the same report line from a different relation.
 *
 * <p>Assumptions: the trailing {@code FILLER PIC X(04)} at {@code app/cpy/CVTRA04Y.cpy} L9,
 * occupying one-based positions 57 through 60, is not mapped, and recording which line was
 * discarded is what keeps the removal auditable rather than invisible. It is padding that brings
 * 56 significant bytes up to the fixed 60-byte record length, so it holds nothing a reader of a
 * report could act on and mapping it would add a column whose only content is blanks. The
 * migration plan's normative-copybook rule requires that removal to be recorded per record, which
 * this entry is.
 *
 * <p>Alternatives Considered: the identity is the whole 6-byte composite the copybook declares as
 * a group at {@code app/cpy/CVTRA04Y.cpy} L5, not the four-character category code alone. Using
 * the category code by itself was possible and is rejected, because that code is meaningful only
 * within a type code rather than on its own, and three independent sources say so. The copybook
 * declares the key as the group at L5 rather than as either subordinate item. The baseline's own
 * relational declaration of this record, {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl},
 * states {@code PRIMARY KEY(TRC_TYPE_CODE,TRC_TYPE_CATEGORY)} at L5 over the two columns it
 * declares at L2 and L3. And the target relation repeats it, keyed on the type code and the
 * category code together, with a referential constraint running from its type code to the
 * transaction-type relation, which is a statement that the type code is part of this row's
 * identity rather than an attribute hanging off it.
 *
 * <p>Alternatives Considered: that composite is expressed as an embedded identifier over a nested
 * value type rather than as a separate identifier class listing the two columns beside the
 * entity's own fields. The embedded form is chosen because the source declares a GROUP at L5 with
 * exactly two subordinate items at L6 and L7, and an embedded identifier is the construct that
 * models a group as one value, whereas the alternative flattens the group into two loose
 * attributes and so loses the one structural fact the copybook is most explicit about. The nested
 * value type is also declared inside this file rather than beside it, because this package is
 * closed at eight source files, and a top-level identifier class would be a ninth; a nested type
 * is neither a projection nor a file, so it adds neither.
 *
 * <p>Alternatives Considered: this projection is a class and its identifier is a record, and the
 * split is forced rather than stylistic. A record cannot be a persistent entity, because a record
 * is final and declares no no-argument constructor, so a provider cannot instantiate one as a
 * managed row; the entity is therefore a class with a provider-visible constructor and no
 * setters. The identifier has no such constraint: an embedded value type is instantiated through
 * its canonical constructor, so the record form applies there, and it is preferred because a
 * record's canonical equality and hash cover every component. With a two-component key, read from
 * {@code app/cpy/CVTRA04Y.cpy} L6 and L7 and totalling the 6 bytes the group at L5 declares, that
 * is exactly the contract an identifier needs, and obtaining it from the declaration rather than
 * from hand-written members removes the failure in which one of those two components is silently
 * left out of equality.
 *
 * <p>Trade-offs: this projection carries no monetary member and no timestamp member, so it
 * declares neither an arbitrary-precision decimal, nor an IEEE-754 binary floating point value,
 * nor any date-time type, zone-bearing or otherwise. Nothing in L5 through L9 of
 * {@code app/cpy/CVTRA04Y.cpy} declares an {@code S9(n)V99} item or an {@code X(26)} item, so
 * there is nothing here for the shared kernel's money type to hold. The item a reader might reach
 * for, {@code TRAN-CAT-BAL PIC S9(09)V99}, is on the balance record at
 * {@code app/cpy/CVTRA01Y.cpy} L9 and belongs to another context. What is given up is the
 * convenience of a single row shape covering both records; what is bought is that a monetary
 * value cannot arrive here through a type that has nowhere to put it.
 *
 * <p>Trade-offs: both codes are carried as fixed-width character values and neither is an
 * enumerated type. The type code is settled by its picture clause: {@code PIC X(02)} at
 * {@code app/cpy/CVTRA04Y.cpy} L6 is a fixed code, so its width is part of the key contract. The
 * category code needs more saying, because {@code PIC 9(04)} at L7 is a numeric picture and the
 * obvious reading of a numeric picture is an integer column. That reading is rejected, and four
 * pieces of evidence say why. The baseline's own relational declaration of this record states
 * {@code TRC_TYPE_CATEGORY CHAR(4) NOT NULL} at {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl}
 * L3, beside {@code TRC_TYPE_CODE CHAR(2)} at L2. The cluster definition at
 * {@code app/jcl/TRANCATG.jcl} L40 declares {@code KEYS(6 0)}, a six-byte key that balances only
 * if this item is four fixed-width bytes beside the two of the type code. The seed extract
 * {@code app/data/ASCII/trancatg.txt} stores the codes zero-padded to four characters, its first
 * rows beginning {@code 010001}, {@code 010002} and {@code 010003}, so the concatenated key form
 * is two characters followed by four. And the target relation declares {@code cat_cd CHAR(4)}
 * accordingly. An integer would discard those leading zeros, turning {@code 0001} into {@code 1}
 * and breaking a key whose whole nature is fixed width. The compromise accepted is that a value
 * declared numeric is not arithmetic here; nothing reads it arithmetically, and the two properties
 * the composite key does depend on, fixed width and preserved leading zeros, are exactly what a
 * fixed-width character value guarantees. An enumerated type was the other alternative for either
 * code and is rejected for both: these two domains are reference data that reference-service
 * seeds and maintains, not closed compile-time sets, so an enumerated type would fail on the first
 * code added to the reference relations, and the failure would land in this context rather than in
 * the one that added the row.
 *
 * <p>Assumptions: the two join columns agree in type with their counterparts, and stating that is
 * the point of the entry rather than a courtesy, because a mismatch would make the report join
 * coercion-bound. Differing identifiers do not imply differing types: the same two-byte type code
 * is named {@code TRAN-TYPE-CD} at {@code app/cpy/CVTRA04Y.cpy} L6 and at
 * {@code app/cpy/CVTRA05Y.cpy} L6 but {@code TRAN-TYPE}, with no code suffix, at
 * {@code app/cpy/CVTRA03Y.cpy} L5, and the absent suffix there is a naming difference in the
 * reference material and nothing more. All three are {@code X(02)} and all three carry the same
 * two-character target type. The category code likewise matches {@code TRAN-CAT-CD PIC 9(04)} at
 * {@code app/cpy/CVTRA05Y.cpy} L7, and the target relations agree with each other as well as with
 * the copybooks: the transaction relation this projection is joined to declares its category
 * column four characters wide, so the join is character to character of equal width on both sides
 * and needs no conversion.
 *
 * <p>Assumptions: the relation this type reads is the read-only view in the {@code reporting}
 * schema, and the row's data originates in {@code reference.transaction_categories}, which
 * reference-service owns and declares. The distinction is load-bearing rather than pedantic. The
 * privilege model established by {@code data-migration/sql/V0__schemas_and_roles.sql} gives the
 * reporting service login no privilege of any kind on a base relation in {@code ledger},
 * {@code account}, {@code card} or {@code reference}: that file withdraws any such privilege at
 * L910-L915 and leaves the login with usage on the {@code reporting} schema at L917 plus SELECT on
 * the relations there at L939, while the SELECT reaching the four source schemas is held instead
 * by the schema's owning role, granted for {@code reference} at L861, which is what the views
 * resolve their own reads as. Pointing this mapping at the base relation would therefore fail on
 * privileges at run time, and it would also contradict this package's charter, which admits a
 * view and never a base relation. The views themselves are declared by
 * {@code data-migration/sql/V1__reporting_views.sql}, cited by path and owned elsewhere; a view
 * missing at run time is a defect to report against that artifact, never one to work around from
 * here by reaching past it.
 *
 * <p>Alternatives Considered: this type maps a view and this context owns no relation of its own,
 * which is why the migration plan's schema-ownership table records it with no owned tables at all.
 * Two other shapes were evaluated and both are rejected. Owning a copy of this reference data here
 * was rejected because reference-service already owns it -- every store the two consuming members
 * read is declared as an input elsewhere, at {@code app/jcl/TRANREPT.jcl} L65-L74 and
 * {@code app/jcl/CREASTMT.JCL} L83-L86 -- and a local copy would create a second truth for figures
 * whose only purpose is to state the first one exactly. Reading a replica was
 * rejected on the plan's own ground that a replica adds cost and replica-lag semantics for no
 * parity benefit: a report disagreeing with the reference data by one replication interval is a
 * support case rather than a feature. What remains is read-only access through a cross-schema
 * view, which is the shape declared here.
 *
 * <p>Assumptions: agreement with the context that owns these rows runs through the physical view
 * and the narrowly-scoped privileges behind it, never through code, and this type accordingly
 * declares no relationship annotation to any sibling projection in either direction. Two
 * independent mechanisms hold that line. The layering test at
 * {@code services/common-lib/src/test/java/com/carddemo/common/architecture/LayeringRulesTest.java}
 * forbids one context's domain package importing another's, and being a test it cannot rot. This
 * module's build file declares exactly one dependency inside the reactor, the shared kernel, so no
 * other service module is on its compile path to be imported from in the first place. The
 * database half is authored elsewhere and cited by path:
 * {@code data-migration/sql/V0__schemas_and_roles.sql} establishes the schemas and the roles, and
 * settles the read-only privileges at L861, L910-L915, L917 and L939. That is the same discipline
 * the baseline read under, where the consuming program reached its five declared inputs through
 * job control alone, at {@code app/jcl/TRANREPT.jcl} L65-L74, and never through a compile-time
 * bond between programs.
 *
 * <p>Assumptions: no relationship to the transaction-type projection is declared here, and this
 * is the specific temptation the entry exists to refuse, because a category genuinely does belong
 * to a type. The referential constraint that expresses that belonging, running from this
 * relation's type code to the transaction-type relation and refusing rather than cascading a
 * delete of a referenced type, is declared by reference-service's own migration and owned there;
 * it preserves the semantic the baseline asserts at
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} L6-L7, and the refusal it produces
 * surfaces to a caller as a conflict response from reference-service rather than from this
 * context. This projection therefore declares no referential constraint, no check constraint, no
 * unique constraint, no index and no mapped reference to the type projection. A many-to-one
 * association would do two unwanted things at once: it would introduce a mapped association this
 * package forbids in every direction, and it would turn a two-column lookup into a graph
 * traversal. Where the report needs the type description it reaches it through the join composed
 * in the repository layer, which is also where the cursor envelope lives, so no query concern and
 * no application-level paging member leaks into this row shape.
 *
 * <p>Assumptions: this projection serves the report join only, and takes no part in the statement
 * join. {@code app/jcl/TRANREPT.jcl} runs {@code CBTRN03C} at L59 and supplies this input as
 * {@code TRANCATG} at L71-L72, corroborated by that program's {@code COPY CVTRA04Y} at L108; the
 * fifth input in that member, {@code DATEPARM} at L73-L74, resolves to a parameter dataset
 * carrying the reporting date range and is not a join participant, which is why that range is
 * handed to the job rather than read from a clock. The statement side names no category input at
 * all: {@code app/jcl/CREASTMT.JCL} lists exactly four inputs at L83, L84, L85 and L86, and
 * {@code app/cbl/CBSTM03A.CBL} copies exactly four record layouts, at L51, L53, L55 and L57, none
 * of them this one. The statement generator never reads a transaction-category row. Note also
 * that {@code app/cpy/CVTRA07Y.cpy}, copied by the report program at L113, is the report OUTPUT
 * layout rather than an input record and is deliberately given no projection in this package.
 *
 * <p>Assumptions: what the report takes from this row is the description, and the composite key
 * exists to satisfy the join rather than to be emitted. {@code app/cbl/CBTRN03C.cbl} builds each
 * detail line in {@code 1120-WRITE-DETAIL} at L361, and L368 moves this record's
 * {@code TRAN-CAT-TYPE-DESC} into the report band, while the category code on that same line comes
 * from the transaction record instead, moved at L367 under an explicit {@code OF TRAN-RECORD}
 * qualifier. That qualifier is necessary precisely because the identifier exists in both
 * {@code app/cpy/CVTRA05Y.cpy} and this record, so the baseline documents the collision in its own
 * source. This is why the type needs exactly three mapped columns and no more: two to be joined
 * on and one to be read.
 *
 * <p>Assumptions: no card row and no card-derived member appears here, and the absence is recorded
 * rather than left to look like an omission. {@code app/cpy/CVACT02Y.cpy} appears in neither
 * consuming program's copy set -- neither the four copybooks at {@code app/cbl/CBSTM03A.CBL} L51,
 * L53, L55 and L57 nor the five at {@code app/cbl/CBTRN03C.cbl} L93, L98, L103, L108 and L113 --
 * and no card file appears in either member's input list, so the schemas this context's two reads
 * actually exercise are exactly {@code ledger}, {@code account}
 * and {@code reference}. The cross-reference input the report does read is the card
 * cross-reference and resolves to the account context, which is the near miss most easily mistaken
 * for a card read.
 *
 * <p>Assumptions: this type declares no reference data of its own -- no row-creating statement, no
 * literal row and no constant list of permitted codes -- because reference-service seeds these
 * rows from the baseline reference extract and owns them. That extract is
 * {@code app/data/ASCII/trancatg.txt}, whose rows carry the 6-byte concatenated key of
 * {@code app/cpy/CVTRA04Y.cpy} L5 followed by the 50-byte description of L8 in a 60-byte record. A
 * constant list here would be a second, unversioned copy of a domain that grows by a row in
 * another context's migration, and it would disagree with the relation the moment it did.
 *
 * <p>Assumptions: no business rule is encoded here. The type exposes the two projected codes and
 * the projected description and performs no validation, no lookup and no derivation; every rule
 * the report applies lives in the service layer that composes it. An accessor that computed
 * anything would also forfeit the format concession user-specified Rule 1 grants at L23, since
 * that concession reaches only an accessor with no logic in it.
 *
 * <p>Trade-offs: this row is mapped immutable and carries no optimistic-locking version column, no
 * cascade, no setter and no write path of any kind. Its declared widths are documented in this
 * block and on the members below rather than asserted through a mapping attribute that exists for
 * schema generation, because this context generates no schema and declares no relation, so
 * restating a width here would assert something this file has no authority over while the relation
 * that does declare it lives in another context's migration. The baseline establishes that
 * immutability loses nothing: {@code app/cbl/CBTRN03C.cbl} opens this very input for input only at
 * L450, executes no record-replacing or record-removing verb against it anywhere, and opens its
 * single output at L396, where its one writing verb at L345 targets the report record instead. What
 * is given up is real and is accepted
 * deliberately: dirty checking and the convenience of merging a detached instance, neither of
 * which a read-only reader uses, and a value needing change has to be changed by the context that
 * owns the relation. What is bought is WHERE an accidental write fails. Mapped immutable it fails
 * at the mapping layer and names this type; left mutable, the same write travels to the database
 * and fails against the read-only role, surfacing as an opaque privilege error at a call site far
 * from the assignment that caused it.
 *
 * <p>Trade-offs: no equality or hash override is declared on this entity, and none is needed. The
 * only equality that is load-bearing for a row read through a join is the equality of its
 * identifier, and that is carried by the nested record below, whose canonical members cover both
 * key components, the type code at {@code app/cpy/CVTRA04Y.cpy} L6 and the category code at L7.
 * Overriding equality on the entity as well would add a second definition of
 * sameness for the same row -- one over the identifier and one over the identifier plus the
 * description -- and a reader could not then tell which one a collection was using. The cost is
 * that two instances loaded in different sessions compare as distinct unless their identifiers are
 * compared explicitly, which is what a report's assembly code does anyway.
 */
@Entity
@Immutable
@Table(name = "v_transaction_categories", schema = "reporting")
public class TransactionCategoryView {

    // Assumptions: the identity is the whole 6-byte group at app/cpy/CVTRA04Y.cpy L5, so it is
    // held as one embedded value rather than as two loose key members. Scoping matters at this
    // exact declaration: L5 reuses a group name that app/cpy/CVTRA01Y.cpy L5 also declares, there
    // over 17 bytes and three members prefixed TRANCAT-, so the nested type below names its
    // owning copybook on every component.
    @EmbeddedId
    private TransactionCategoryKey key;

    // Assumptions: this is the CATEGORY description, read from TRAN-CAT-TYPE-DESC PIC X(50) at
    // app/cpy/CVTRA04Y.cpy L8 and occupying one-based positions 7 through 56. The member is named
    // for the category and not for the word TYPE inside that identifier, because the TYPE
    // description is a separate 50-byte item at app/cpy/CVTRA03Y.cpy L6 that reaches the same
    // report line from another relation. Trailing blanks in the source are padding to the declared
    // width rather than data, which is why the column is variable-width rather than fixed.
    @Column(name = "description")
    private String categoryDescription;

    /**
     * Creates an instance for the persistence provider to populate when it materialises a row.
     *
     * <p>Trade-offs: this constructor exists solely because a provider requires a no-argument
     * constructor to instantiate a managed row, and it is protected rather than public so that
     * application code cannot fabricate a row shape no query returned. Its body is empty because
     * the provider assigns both members after construction, so there is nothing to initialise and,
     * this being a read-only projection, nothing to validate either: a row exists in the view
     * before an instance representing it exists. The alternative of exposing a public constructor
     * taking the three projected values was rejected because it would let a caller assemble a
     * reference row that the owning context never issued, which is exactly the confusion a
     * read-only projection is meant to prevent.</p>
     */
    protected TransactionCategoryView() {
    }

    /**
     * Returns the embedded identifier carrying this row's two-part reference code.
     *
     * @return the identifier whose two components are the transaction type code and the
     *     transaction category code, together forming the 6-byte key the source record declares as
     *     a group at {@code app/cpy/CVTRA04Y.cpy} L5; never {@code null} for a row materialised
     *     from the view
     */
    public TransactionCategoryKey getKey() {
        return key;
    }

    /**
     * Returns the description of this transaction category.
     *
     * @return the category description read from {@code TRAN-CAT-TYPE-DESC PIC X(50)} at
     *     {@code app/cpy/CVTRA04Y.cpy} L8, which is the value the report emits for a detail line;
     *     never the description of the transaction TYPE, which is a different item of the same
     *     declared width at {@code app/cpy/CVTRA03Y.cpy} L6
     */
    public String getCategoryDescription() {
        return categoryDescription;
    }

    /**
     * The two-part reference code that identifies one transaction-category row.
     *
     * <p>Assumptions: both components are scoped to {@code app/cpy/CVTRA04Y.cpy}, whose group
     * {@code TRAN-CAT-KEY} at L5 is 6 bytes over the two items at L6 and L7. The same group name
     * at {@code app/cpy/CVTRA01Y.cpy} L5 is 17 bytes over three items prefixed {@code TRANCAT-},
     * belongs to the category BALANCE record and is not projected by this type, so naming the
     * owning copybook on each component below is what keeps the two apart.</p>
     *
     * <p>Trade-offs: this is a record, so its equality and hash are canonical over both components
     * rather than hand-written. For an identifier that is precisely the contract wanted, because
     * both the type code at L6 and the category code at L7 participate in identity and a
     * hand-written pair could silently omit one; a provider instantiates an embedded value through
     * its canonical constructor, so the record form is available here even though the enclosing
     * entity cannot use it. The cost accepted is that the two components cannot be assigned
     * individually after construction, which a read-only key never needs.</p>
     *
     * <p>Assumptions: the type is serializable because an embedded identifier is required to be,
     * and it is nested inside the projection it identifies rather than declared beside it because
     * this package is closed at eight source files and a top-level identifier class would be a
     * ninth. It shares no base type and no identifier class with the other composite in this
     * package, the 32-byte pair of 16-character items declared at {@code app/cpy/COSTM01.CPY}
     * L21-L23; two composites of entirely different shape have nothing to factor out.</p>
     *
     * @param typeCode the two-character transaction type code, the leading component of the group
     *     at {@code app/cpy/CVTRA04Y.cpy} L5, read from {@code TRAN-TYPE-CD PIC X(02)} at L6 and
     *     occupying one-based positions 1 through 2; carried as a fixed two-character value
     *     because its width is part of the key contract, and it is the component the referential
     *     constraint in the owning context runs from
     * @param categoryCode the four-character transaction category code, the trailing component of
     *     that same group, read from {@code TRAN-CAT-CD PIC 9(04)} at
     *     {@code app/cpy/CVTRA04Y.cpy} L7 and occupying one-based positions 3 through 6; carried
     *     as a fixed four-character value with its leading zeros intact, as the {@code 010001} key
     *     form in {@code app/data/ASCII/trancatg.txt} requires, and meaningful only within the type
     *     code above rather than on its own, which is why it does not identify a row by itself
     */
    @Embeddable
    public record TransactionCategoryKey(
            @Column(name = "type_cd") String typeCode,
            @Column(name = "cat_cd") String categoryCode) implements Serializable {
    }
}
