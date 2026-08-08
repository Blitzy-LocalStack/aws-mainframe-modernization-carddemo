//=============================================================================
// WHY : Assumptions: every column name, declared width and nullability stated
//       below was read out of
//       services/reference-service/src/main/resources/db/migration/
//       V1__reference.sql, which this package's charter in package-info.java
//       names as the authority for the physical shape of the reference schema.
//       Where this file and that migration could ever disagree, the migration
//       is right and this file is the defect.
// WHY : Assumptions: every path beginning app/ is reference material. It is
//       read as the specification for what this type must carry and is cited
//       by path and line, never modified.
//=============================================================================
package com.carddemo.reference.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One transaction type row of the {@code reference} schema: a two-character code, the description a
 * user reads, and the counter that guards a concurrent replace.
 *
 * <h2>Purpose</h2>
 *
 * <p>This type maps a Java object onto a row of {@code reference.transaction_types} and does nothing
 * else. It carries no business rule, no request or response shape, no query and no byte offset,
 * because this package's charter in {@code package-info.java} assigns each of those to a sibling
 * package. The one decision left to it, and the whole subject of the entries below, is which
 * physical column of which declared width each baseline field lands on.</p>
 *
 * <h2>Two baseline representations, one target table</h2>
 *
 * <p>Refactoring Rationale: the same logical entity exists twice in the baseline, in two different
 * storage technologies, and this single type replaces both. The batch side reads it as an indexed
 * file: {@code app/cbl/CBTRN03C.cbl} declares {@code SELECT TRANTYPE-FILE ASSIGN TO TRANTYPE} at
 * L39, {@code ORGANIZATION IS INDEXED} at L40, {@code RECORD KEY   IS FD-TRAN-TYPE} at L42, copies
 * the record layout at L103 with {@code COPY CVTRA03Y.}, and resolves a report description at L189
 * to L190 by moving the code into that key and performing {@code 1500-B-LOOKUP-TRANTYPE}. The online
 * side reads and writes it as the Db2 table {@code CARDDEMO.TRANSACTION_TYPE}, which the three
 * extension programs give full maintenance:
 * {@code app/app-transaction-type-db2/cbl/COBTUPDT.cbl} includes the host structure at L54 and
 * carries {@code INSERT} at L138, {@code UPDATE} at L172 and {@code DELETE} at L202;
 * {@code COTRTLIC.cbl} includes it at L333, selects at L342, L358 and L1806, updates at L1847 and
 * deletes at L1901; {@code COTRTUPC.cbl} includes it at L286, selects at L1480, updates at L1545,
 * inserts at L1598 and deletes at L1628.</p>
 *
 * <p>The two representations divide the evidence rather than competing. The copybook supplies the
 * record length and the one padding field; the data definition supplies the authoritative column
 * names and nullability, and the two agree on every width. The baseline maintains two
 * representations at those paths and lines; this Java maintains one; the divergence in
 * representation count is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <h2>Record geometry</h2>
 *
 * <p>The copybook is normative under the migration plan's transformation rule, so each declared
 * width settles a column type, a Java type and a byte position, and nothing else does.</p>
 *
 * <pre>
 * CVTRA03Y.cpy  baseline field   PIC     one-based   target column            Java type
 * L5            TRAN-TYPE        X(02)     1 to 2    type_cd     CHAR(2)      String
 * L6            TRAN-TYPE-DESC   X(50)     3 to 52   description VARCHAR(50)  String
 * L7            FILLER           X(08)    53 to 60   not mapped               none
 *                                        ---------
 *                                         60 bytes   2 + 50 = 52 significant, and 52 + 8 = 60
 * </pre>
 *
 * <p>Three unrelated sources agree on those figures, which is why they are asserted here rather than
 * estimated. {@code app/cpy/CVTRA03Y.cpy} states the declared length in its own L2 header,
 * {@code Data-structure for transaction type (RECLN = 60)}. {@code app/cbl/CBTRN03C.cbl} declares
 * {@code FD  TRANTYPE-FILE.} at L72 with exactly two members, {@code FD-TRAN-TYPE PIC X(02)} at L74
 * and {@code FD-TRAN-DATA PIC X(58)} at L75, which sum to 60 with no copybook involved at all. And
 * {@code app/jcl/TRANTYPE.jcl} defines the cluster with {@code KEYS(2 0)} at L40 and
 * {@code RECORDSIZE(60 60)} at L41, a two-byte key at offset zero inside a constant 60-byte
 * record.</p>
 *
 * <h2>Decisions</h2>
 *
 * <p>What follows discharges the obligation user-specified Rule 1 (Explainability) states at L43,
 * using the four categories it names at L31 to L34, for every choice here that a reasonable
 * alternative could have gone the other way on. L40 forbids leaving such a choice undocumented and
 * L41 forbids a rationale carrying no specific justification, so each entry names the line, the
 * declared width or the byte count it rests on. Entries whose subject is a member or an annotation
 * are additionally stated beside that member, because L27 asks for adjacency and a class-level block
 * alone cannot provide it.</p>
 *
 * <p>Assumptions: the two-character code is character data of declared width two, and is neither a
 * numeric type nor an enumerated type. Four independent sources carry it as characters:
 * {@code app/cpy/CVTRA03Y.cpy} L5 declares {@code TRAN-TYPE PIC X(02)};
 * {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl} L2 declares
 * {@code TR_TYPE                        CHAR(2) NOT NULL,};
 * {@code app/app-transaction-type-db2/dcl/DCLTRTYP.dcl} L38 generates the host variable as
 * {@code 10 DCL-TR-TYPE          PIC X(2).}; and {@code app/jcl/TRANTYPE.jcl} L40 defines
 * {@code KEYS(2 0)}, a key that balances only if this field occupies two declared-width bytes. A
 * numeric column was the tempting alternative, because {@code app/data/ASCII/trantype.txt} seeds only
 * the seven codes {@code 01} through {@code 07} and those read as small integers. It is rejected on
 * two grounds. The width is part of the key contract, so an integer column would store {@code 01} as
 * 1 and a key assembled from that value would not locate its own row; and a numeric column would
 * reject a code carrying a letter, which the declared picture admits. An enumerated type is rejected
 * for a related reason: seven is a count of seeded rows and not a constraint, so a type closed at
 * those seven would fail on an eighth that this service itself is the one able to add.</p>
 *
 * <p>Assumptions: the {@code FILLER PIC X(08)} at {@code app/cpy/CVTRA03Y.cpy} L7 is not carried
 * across, and the migration plan's normative-copybook rule requires that omission to be recorded per
 * record, which is what this entry is. It is padding to a positional record length and carries
 * nothing a reader could act on. What matters more than the omission is that any reader of the
 * baseline extract must locate the description by offset rather than by trimming the record:
 * {@code app/data/ASCII/trantype.txt} holds eight ASCII {@code 0} characters in those eight bytes on
 * every one of its seven rows, not eight blanks, so a reader that took the record and trimmed it
 * would retain {@code 00000000} as though it were data. Reading positions 3 through 52 and stopping
 * there cannot make that mistake. The arithmetic is stated because it is what makes the omission
 * auditable: 2 plus 50 is 52 significant bytes, and 52 plus 8 is 60 exactly.</p>
 *
 * <p>Refactoring Rationale: the description is one {@code String} member, and the length halfword the
 * Db2 host structure pairs with it has no counterpart here.
 * {@code app/app-transaction-type-db2/dcl/DCLTRTYP.dcl} models {@code TR_DESCRIPTION} across L40 to
 * L46 as a group {@code 10 DCL-TR-DESCRIPTION.} containing two subordinate items at level 49: a
 * length halfword {@code DCL-TR-DESCRIPTION-LEN PIC S9(4) USAGE COMP} and the text itself,
 * {@code DCL-TR-DESCRIPTION-TEXT PIC X(50)}. That pairing is the generator's signature for a
 * varying-length column and is a host-language artifact of reading Db2 from COBOL, where a program
 * has no other way to learn how much of a declared-width area is significant. A Java
 * {@code String} and a PostgreSQL {@code VARCHAR} each carry their own length intrinsically, so a
 * separate member holding it would be a second, independently settable statement of the same fact.
 * Its disappearance is recorded here in the same spirit as the padding field above, so that a reader
 * comparing the host structure against this type finds both absences explained rather than one.</p>
 *
 * <p>Assumptions: the description IS trimmed of the padding the baseline record carries, and the
 * trailing blanks across {@code app/cpy/CVTRA03Y.cpy} L6's fifty bytes are padding to the positional
 * record length rather than data. The migration plan maps a descriptive {@code PIC X(n)} to
 * {@code VARCHAR(n)} for exactly this reason, and the Db2 side reached the same conclusion for the
 * same field: {@code app/app-transaction-type-db2/ddl/TRNTYPE.ddl} L3 declares
 * {@code TR_DESCRIPTION                 VARCHAR(50) NOT NULL,} rather than a constant-width
 * type.</p>
 *
 * <p>Trade-offs: that ruling is deliberately the opposite of the one this package applies to the
 * disclosure account-group identifier, which is ten characters wide and must never be trimmed on
 * either side of a comparison. The asymmetry is intentional and it turns on what the value is for. A
 * group identifier is a key compared for equality against a stored value that carries its padding,
 * so trimming either side would leave the two as distinct values and a lookup would find nothing.
 * This is descriptive text that no key is assembled from and no equality test is performed on, so
 * its padding has no such load to bear. The evidence behind the group-identifier ruling is not
 * restated here; it is recorded once as ruling two of {@code package-info.java}.</p>
 *
 * <p>Alternatives Considered: no secondary index is declared on this type. The baseline declares one,
 * at {@code app/app-transaction-type-db2/ddl/XTRNTYPE.ddl}, whose L1 reads
 * {@code CREATE UNIQUE INDEX CARDDEMO.XTRAN_TYPE} and whose L3 names its single key column
 * {@code (TR_TYPE   ASC)}, because in Db2 an index is the object that enforces a key. PostgreSQL
 * implements a primary-key constraint by building exactly that unique tree itself, and
 * {@code V1__reference.sql} declares the constraint on {@code type_cd}, so reproducing
 * {@code XTRAN_TYPE} would create a second structure identical to the first: two indexes to write on
 * every insert and to hold in cache, enforcing one rule. Declaring it through table metadata on this
 * type was the other alternative and is rejected independently, because this service runs with
 * schema management disabled, so such metadata would neither create nor verify an index while still
 * reading like a specification that could drift from the migration that does create it. The baseline
 * behaviour is preserved by the constraint; the divergence in object count is recorded in
 * {@code docs/architecture/cobol-to-service-traceability.md}.</p>
 *
 * <p>Alternatives Considered: this type declares no collection of the categories that reference it,
 * in either direction. The referential rule genuinely exists, and the database is what performs it:
 * {@code app/app-transaction-type-db2/ddl/TRNTYCAT.ddl} declares across L6 to L7
 * {@code FOREIGN KEY TRC_TYPE_CODE (TRC_TYPE_CODE)} referencing
 * {@code CARDDEMO.TRANSACTION_TYPE (TR_TYPE) ON DELETE RESTRICT}, which this schema's migration
 * carries forward, so an attempt to delete a type that still has categories is refused by the engine
 * as SQLSTATE 23503. Spring surfaces that as a
 * {@code DataIntegrityViolationException}, and {@code com.carddemo.common.error}'s
 * {@code GlobalExceptionHandler} in the shared kernel discriminates it by name and answers HTTP 409.
 * That path needs no object association at any point, which is the whole reason none is declared. No
 * exception mapping of any kind appears in this file either: the 409 is inherited from the shared
 * handler, and a second declaration of it here would compete with the one that already works.</p>
 *
 * <p>Trade-offs: what is given up is real. Object navigation from a type to its categories is
 * unavailable, so the sibling {@code service} and {@code mapper} packages join explicitly or issue a
 * second repository call when a category's description is wanted alongside its parent. That cost is
 * accepted because a mapped association would buy convenience and charge for it twice over: it would
 * attach a lazily-initialised collection to a table small enough that a list endpoint would trigger
 * one query per row, and it would open a cascade route from a type to its categories that the
 * baseline never had, where deleting a type could reach through and remove the rows the restricting
 * rule exists to protect.</p>
 *
 * <p>Alternatives Considered: this is an ordinary class with explicitly written members, not a record
 * and not a type whose accessors are generated. A record cannot be a persistence entity, which needs
 * a constructor taking no arguments and non-final state the provider can populate, and a record
 * offers neither. Generated accessors are rejected on the ground this package's charter records: a
 * generated member has no source line on which the documentation Rule 1 L15 requires could be
 * written, and no exemption is available, because {@code config/checkstyle/checkstyle.xml} enables no
 * comment-driven and no annotation-driven suppression filter. Explicit members reach the same brevity
 * by a route that keeps every one of them documentable.</p>
 *
 * <p>Assumptions: no binary floating-point type appears anywhere in this type, in a field, a
 * parameter or a return position. The baseline layout holds no monetary field at all, so the
 * prohibition has nothing here to act on, but it binds regardless: the shared architecture rule takes
 * every production type under {@code com.carddemo} as its subject rather than the money package
 * alone, so a {@code double} introduced here would fail the build rather than a review.</p>
 *
 * <p>Assumptions: the declared width and the not-null flag on each column below are metadata recorded
 * at the mapping site rather than assertions evaluated on the read path. This service emits no
 * data-definition statement and runs with schema management disabled, so those attributes neither
 * create nor police the table; what they do is put each declared copybook width where the column is
 * mapped, so a reader auditing a width does not have to leave the file to find it. The not-null flag
 * records a guarantee the source record genuinely gives, because a constant-width record has no
 * representation for absence: every one of the 60 bytes at {@code app/cpy/CVTRA03Y.cpy} L5 through L7
 * is always present, blank-padded where it carries no value.</p>
 */
@Entity
// WHY : Assumptions: the schema is named explicitly rather than left to the JDBC search path the
//       sibling config package pins. This is the settled package ruling, recorded once in
//       package-info.java: relying on the path couples entity resolution to connection
//       configuration owned by a different file, and an integration-test profile starting a
//       throwaway database is the context most likely to lack that pin, so the failure would
//       appear as a missing table in a test rather than as a misconfiguration where it was made.
@Table(name = "transaction_types", schema = "reference")
public class TransactionType {

    // WHY : Assumptions: the column name, its declared width and its nullability are taken verbatim
    //       from the transaction_types block of V1__reference.sql, which declares
    //       type_cd CHAR(2) NOT NULL and constrains it as the primary key. The name is bound
    //       explicitly rather than inferred, because an implicit naming strategy would derive the
    //       column from the member and would then answer a rename silently.
    // WHY : Assumptions: the baseline identifier is TRAN-TYPE at app/cpy/CVTRA03Y.cpy L5, carrying no
    //       code suffix, while app/cpy/CVTRA04Y.cpy L6 names the identical X(02) value TRAN-TYPE-CD.
    //       The member is named for the role the value plays so that both ends of the two-character
    //       relationship read alike. The absent suffix at L5 is not an error in the baseline, which
    //       stands unmodified and keeps running; the divergence in identifier is recorded in
    //       docs/architecture/cobol-to-service-traceability.md.
    // WHY : Refactoring Rationale: the JDBC type code is declared HERE and an earlier revision of this
    //       file omitted it, which made this the one key in the package bound differently from the
    //       column it maps. V1__reference.sql L106 declares type_cd CHAR(2), and without this
    //       annotation the provider binds a String parameter as VARCHAR; PostgreSQL then compares a
    //       blank-padded CHAR column against an unpadded VARCHAR parameter, which is a comparison
    //       across two types rather than within one. The three sibling keys of this package all carry
    //       the annotation, so the omission also made the package inconsistent with itself.
    // WHY : Assumptions: the practical consequence is narrow but real and is worth naming rather than
    //       asserting in the abstract. Every value this key carries today is exactly two characters,
    //       so no padding difference arises and no lookup is currently wrong. What the annotation buys
    //       is that a one-character code -- which the two-position column admits and would store as a
    //       character followed by a blank -- is found by a lookup for that one character, and it lets
    //       the index on a CHAR column be used without an implicit cast.
    // WHY : Assumptions: the column is declared NOT UPDATABLE. The identifier of a seeded reference
    //       row is its identity rather than one of its attributes: the two-character code is what a
    //       category's foreign key at V1__reference.sql L267 points at, so changing it in place would
    //       silently break every child row that references it. The provider is told not to write the
    //       column on an update, which turns an attempt to reassign an identity into a no-op at the
    //       database boundary rather than a corrupted relationship.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "type_cd", length = 2, nullable = false, updatable = false)
    private String typeCd;

    // WHY : Assumptions: taken verbatim from V1__reference.sql, which declares
    //       description VARCHAR(50) NOT NULL. The varying-width column is what admits the trimmed
    //       value the Decisions section above settles on.
    // WHY : Assumptions: this is the TYPE description at app/cpy/CVTRA03Y.cpy L6, and it is a
    //       different field from the CATEGORY description TRAN-CAT-TYPE-DESC at
    //       app/cpy/CVTRA04Y.cpy L8. Both are X(50), so declared width alone cannot tell them apart,
    //       which is why the member is not named for a bare description.
    @Column(name = "description", length = 50, nullable = false)
    private String description;

    // WHY : Assumptions: this member exists because V1__reference.sql declares
    //       version BIGINT NOT NULL DEFAULT 0 on transaction_types, and because the third package
    //       ruling in package-info.java assigns an optimistic-lock counter to exactly two of the six
    //       entities of this package, this one among them. The four seeded lookup entities carry no
    //       such member, because their tables declare no such column and no operation replaces a row
    //       in them.
    // WHY : Refactoring Rationale: the mechanism is the baseline's own rather than an addition.
    //       app/app-transaction-type-db2/cbl/COTRTUPC.cbl snapshots the record it read, carries a
    //       data-changed flag, compares that snapshot against the stored row before committing, and
    //       commits only when the two agree. That is optimistic concurrency across a
    //       pseudo-conversational gap, and a counter is the same guarantee expressed natively:
    //       the provider compares one column under the row lock the update already takes, whereas a
    //       comparison written by hand would have to re-read every field and could still lose a
    //       write that landed between that re-read and the update.
    // WHY : Assumptions: the member is the primitive long, not the boxed type. The column is
    //       BIGINT NOT NULL, so it can never deliver an absent value and the boxed form would add a
    //       null state the schema forbids; a primitive also starts at the same zero the column
    //       defaults to, so a row inserted through this type agrees with a row seeded around it.
    // WHY : Alternatives Considered: having a replace request echo back the description it read and
    //       comparing that instead was rejected, because it checks only the members a caller chose
    //       to echo, so a column added to this table would fall silently outside the check, whereas
    //       a counter covers the whole row by construction.
    // WHY : Refactoring Rationale: the column is declared NOT NULL here as well as in the migration,
    //       and an earlier revision of this mapping omitted it. The omission was not inert. A mapping
    //       that under-states nullability describes a column able to hold a value the schema forbids,
    //       so any tool deriving a definition from this metadata -- a schema comparison, a test-time
    //       creation, a documentation pass -- would emit a nullable column and disagree with the one
    //       V1__reference.sql declares. Where that disagreement is actually read is worth stating
    //       exactly rather than overstating: the production profile sets ddl-auto to none, so nothing
    //       compares the two at start-up there, while src/test/resources/application-test.yml sets it
    //       to validate specifically so a migration test asserts entity drift against the Flyway
    //       output. The metadata and the migration now say the same thing under both.
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the specification requires a persistence entity to declare a constructor taking
     * no arguments, which the provider invokes before writing the mapped columns into the members
     * above. It is {@code protected} rather than public, which is the policy every other entity in this
     * codebase already follows: 30 of the 33 entity declarations across the eight services declare it
     * protected, and the five public ones were all in this package. The provider reaches a protected
     * constructor, and application code outside this package cannot allocate an unpopulated row and
     * pass it on as though it had been loaded.
     *
     * <p>Refactoring Rationale: this was public, and each of the five gave its own reason -- that a
     * mapper or a test needs to assemble a row member by member. Narrowing costs those callers nothing: the all-argument constructor is public and is the route they already take, and this package's own tests share this package, so protected reaches them as well.
     */
    protected TransactionType() {
        // WHY : Assumptions: the body is empty by design rather than unfinished. The provider assigns
        //       every mapped member directly after construction on each load, so initialising one
        //       here would write a value that is immediately overwritten. The version member needs no
        //       initialiser either, because a primitive long already begins at the zero its column
        //       defaults to.
    }

    /**
     * Creates an instance from a transaction type code and its description.
     *
     * <p>Assumptions: the version counter is deliberately not an argument. It is written by the
     * persistence provider rather than by a caller, which is why no member of this type offers a way
     * to assign it and why a newly built instance begins at the zero its column defaults to. A
     * caller replacing a stored row loads that row, compares the counter it read against the one the
     * row now carries, and lets the provider check it again as it writes.</p>
     *
     * <p>Trade-offs: only the identifier is checked here. Nullability for the description belongs to
     * the owning schema, which declares it not-null, and duplicating that constraint in this
     * constructor would stand up a second contract able to drift from the first. The identifier is
     * the exception because equality and hashing below rest on it immediately, so an instance built
     * without one would misbehave in a collection long before any database rejected it.</p>
     *
     * @param typeCd the two-character code that keys this row, declared as
     *     {@code TRAN-TYPE PIC X(02)} at {@code app/cpy/CVTRA03Y.cpy} L5 and carried in the
     *     {@code type_cd} column; must not be {@code null}
     * @param description the description of that type, declared as
     *     {@code TRAN-TYPE-DESC PIC X(50)} at {@code app/cpy/CVTRA03Y.cpy} L6, carried in the
     *     {@code description} column, trimmed of the padding the baseline record holds, and distinct
     *     from the category description at {@code app/cpy/CVTRA04Y.cpy} L8
     * @throws NullPointerException if {@code typeCd} is {@code null}
     */
    public TransactionType(String typeCd, String description) {
        this.typeCd = Objects.requireNonNull(typeCd, "typeCd must not be null");
        this.description = description;
    }

    /**
     * Returns the two-character code this transaction type row is keyed by.
     *
     * @return the value of the {@code type_cd} column, which a category's parent reference carries
     */
    public String getTypeCd() {
        return typeCd;
    }

    /**
     * Returns what this transaction type means to a user.
     *
     * @return the value of the {@code description} column, trimmed of the padding the baseline
     *     record at {@code app/cpy/CVTRA03Y.cpy} L6 carries
     */
    public String getDescription() {
        return description;
    }

    /**
     * Replaces what this transaction type means to a user.
     *
     * @param description the description to store in the {@code description} column, of declared
     *     width fifty per {@code app/cpy/CVTRA03Y.cpy} L6
     */
    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * Returns the optimistic-lock counter the persistence provider maintains for this row.
     *
     * <p>Assumptions: no companion assignment method accompanies this one, and the omission is the
     * point rather than an oversight. The provider owns the counter: it reads the value as it loads
     * the row, compares it as it writes, and raises the value on each successful replace. A caller
     * able to assign it could report a version the row never held, which is precisely the confusion
     * the counter exists to prevent.</p>
     *
     * @return the counter as this row last carried it, zero on an instance no replace has yet
     *     touched, rising by one on each successful replace
     */
    public long getVersion() {
        return version;
    }

    /**
     * Reports whether another object denotes the same transaction type row as this one.
     *
     * <p>Alternatives Considered: equality rests on the code alone and excludes both the description
     * and the counter. Including the description was the alternative and is rejected, because two
     * loads of one row would then compare unequal if the text were edited between them, which would
     * make row identity depend on a value a user can change. Including the counter would be worse
     * still, since it changes on every successful replace by design. The code is the whole key of the
     * baseline indexed file, as {@code app/cbl/CBTRN03C.cbl} L42 declares with
     * {@code RECORD KEY   IS FD-TRAN-TYPE}, so it alone settles which row is meant.</p>
     *
     * <p>Assumptions: the test is a pattern match rather than an exact-class comparison, because a
     * persistence provider may hand back a generated subclass of a mapped type and an exact-class
     * comparison would then report two representations of one row unequal.</p>
     *
     * @param other the object to compare against, which may be of any type and may be {@code null}
     * @return {@code true} when the argument is a transaction type carrying an equal code, and
     *     {@code false} otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TransactionType that)) {
            return false;
        }
        return Objects.equals(this.typeCd, that.typeCd);
    }

    /**
     * Returns a hash consistent with the code-only equality above.
     *
     * <p>Assumptions: only the code is hashed, for the same reason only the code is compared. A hash
     * drawn from the description or from the counter would change when either did, which would move
     * an instance already held in a hash-based collection and break the contract this method shares
     * with equality.</p>
     *
     * @return the hash of the two-character code, or zero when no code has been assigned yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.typeCd);
    }

    /**
     * Returns a diagnostic rendering of all three mapped members.
     *
     * <p>Assumptions: every member is rendered in full because none is sensitive. No primary account
     * number, no card verification value and no national identifier appears anywhere at
     * {@code app/cpy/CVTRA03Y.cpy} L5 through L7, which holds a two-character code, a description and
     * one padding field, so the masking the wider migration applies to account and card data has
     * nothing here to act on. A partially rendered form would withhold the only values a reader of a
     * failure needs.</p>
     *
     * <p>Trade-offs: this rendering is for diagnostics and is not a published shape. The response
     * body a caller receives is assembled by the sibling {@code mapper} package from the accessors
     * above, so reordering or widening this string cannot disturb what any endpoint returns.</p>
     *
     * @return a single-line rendering naming the type and all three mapped members
     */
    @Override
    public String toString() {
        return "TransactionType[typeCd=" + typeCd
                + ", description=" + description
                + ", version=" + version + "]";
    }
}
