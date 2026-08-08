package com.carddemo.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// WHY : Assumptions: these class-level rationales sit above the Javadoc rather than between it and
//       the declaration. Keeping the Javadoc immediately adjacent to the annotated class preserves
//       the association that Checkstyle's type-documentation gate reads, while this uninterrupted
//       rationale block still belongs to the same declaration.
//
// WHY : Assumptions: this mapping is DDL-PASSIVE, and the annotation carries no index, no unique
//       constraint, no explicit column definition and no key generation strategy. The table, its
//       columns and its indexes are created by the owning service's Flyway migration,
//       services/account-service/src/main/resources/db/migration/V1__account.sql, and that
//       migration is also where the by-account secondary index idx_card_xref_account_id -- the
//       relational replacement for the VSAM alternate index, per the migration plan's section
//       0.4.1.3 -- is declared. THIS TYPE DEPENDS ON THAT INDEX AND MUST NOT DECLARE IT. Declaring
//       it here is genuinely tempting, because this module is the index's heaviest user, and it is
//       refused because it would have a module with no authority over the account schema request
//       an object in it, and would create a second definition of one index that nothing can detect
//       drifting from the first. Hibernate's schema handling in this module is never stronger than
//       an assertion against the shape already present.
// WHY : Assumptions: that migration is a planned artifact of the migration plan rather than a file
//       present beside this one, so a reader who looks for the path named above and does not find it
//       should read the dependency as declared and not the reference as broken. What does exist is
//       the provisioning script: data-migration/sql/V0__schemas_and_roles.sql creates the account
//       schema at line 502 and records the ownership map at line 20, and it guards its own grant on
//       account.accounts with a to_regclass test that raises a notice naming the statement to re-run
//       while the table is absent. The consequence for this module is stated where the setting that
//       causes it lives: application.yml sets ddl-auto: validate deliberately, so a task started
//       before the owning context has applied that migration fails its schema check at startup with
//       nothing half-applied. For a nightly chain that is the better failure and is the accepted
//       ordering dependency, not a defect in this mapping.
// WHY : Alternatives Considered: the schema is named explicitly on the annotation rather than left
//       to the connection's search path. Relying on the search path was the alternative and is
//       rejected because this module spans four schemas with four different authorities -- batch
//       owned outright, ledger and account reached through write grants of different breadth, and
//       reference read-only -- so no single search path can disambiguate them, and a mapping that
//       resolved by position would silently follow whichever path the pool happened to hand it.
//       Naming the schema also puts the grant boundary in the source, where a reader auditing what
//       this module may touch will actually look.
// WHY : Assumptions: the type is marked immutable, and it declares no setter, because NOTHING IN
//       THIS MODULE WRITES THIS TABLE. Two independent contracts establish that. In the baseline
//       all three programs open the file for input only -- app/cbl/CBTRN01C.cbl:291,
//       app/cbl/CBTRN02C.cbl:275 and app/cbl/CBACT04C.cbl:254 -- and no program issues a write or
//       a rewrite against it. In the target, data-migration/sql/V0__schemas_and_roles.sql:768
//       grants this module's role SELECT on the account schema and line 779 grants UPDATE on
//       account.accounts BY NAME, so no insert, update or delete privilege on the cross-reference
//       is granted at all. Rows originate in account-service and, for a migrated load, in the
//       extract-transform-load reader data-migration/src/carddemo_migration/readers/xref.py.
//       Expressing that read-only relationship in the type system rather than in prose is the
//       point: a later edit that needs a setter has to confront the grant instead of discovering
//       it as a permission error at run time.
// WHY : Trade-offs: the immutability and JDBC-type annotations are Hibernate's, in a type otherwise
//       using jakarta.persistence. Both provider extensions are accepted because Jakarta Persistence
//       has neither a portable immutability marker nor a portable way to select CHAR binding for a
//       String without embedding vendor DDL in columnDefinition, which this DDL-passive mapping must
//       not do. The sibling reporting-service domain package already establishes Hibernate mapping
//       annotations as a house convention, so they do not introduce a new dependency. Immutability
//       suppresses an update silently rather than raising, but that cannot arise in practice because
//       no setter exists to produce a dirty state; its real effect is to spare the provider
//       dirty-checking a row that interest accrual loads once per account at the control break in
//       app/cbl/CBACT04C.cbl:194. It deliberately does NOT block an insert or a delete, which leaves
//       the migrated load and the parity fixtures able to populate the table.

/**
 * Maps the 50-byte card cross-reference record onto {@code account.card_xref} for the batch
 * bounded context.
 *
 * <p>The row resolves a card number to the account and the customer that own it. Every migrated
 * batch job depends on that resolution: the account identifier a posting or accrual step works
 * with is read from here rather than taken from the transaction being processed.</p>
 *
 * <h2>The field set, and how it is derived</h2>
 *
 * <p>The contract is {@code 01 CARD-XREF-RECORD} at {@code app/cpy/CVACT03Y.cpy:4-8}, and the
 * copybook is normative: the migration plan's transformation rule T1 makes a field's
 * {@code PICTURE} clause decide its column type, its Java type and its byte offset, so the four
 * declarations below are the specification rather than a provenance note.</p>
 *
 * <table border="1">
 *   <caption>Field derivation from {@code app/cpy/CVACT03Y.cpy}</caption>
 *   <tr><th>Copybook field</th><th>Line</th><th>Offset</th><th>PICTURE</th>
 *       <th>Column</th><th>SQL type</th><th>Java type</th></tr>
 *   <tr><td>{@code XREF-CARD-NUM}</td><td>5</td><td>0</td><td>{@code X(16)}</td>
 *       <td>{@code card_num}</td><td>{@code CHAR(16)}</td><td>{@code String}</td></tr>
 *   <tr><td>{@code XREF-CUST-ID}</td><td>6</td><td>16</td><td>{@code 9(09)}</td>
 *       <td>{@code customer_id}</td><td>{@code BIGINT}</td><td>{@code Long}</td></tr>
 *   <tr><td>{@code XREF-ACCT-ID}</td><td>7</td><td>25</td><td>{@code 9(11)}</td>
 *       <td>{@code account_id}</td><td>{@code BIGINT}</td><td>{@code Long}</td></tr>
 *   <tr><td>{@code FILLER}</td><td>8</td><td>36</td><td>{@code X(14)}</td>
 *       <td>dropped</td><td>none</td><td>none</td></tr>
 * </table>
 *
 * <p>Assumptions: the mapped field set plus the dropped pad accounts for every byte of the record,
 * and the arithmetic is written out because an unnoticed shortfall shifts every field after it.
 * The pad begins at offset 36 and is 14 bytes wide, so 36 plus 14 is the declared 50, which
 * {@code app/cpy/CVACT03Y.cpy:2} states as {@code RECLN 50} in the copybook's own header. The
 * length is corroborated twice more, from file descriptions that were written independently of
 * that header: {@code app/cbl/CBACT04C.cbl:69-74} subdivides the record into 16 plus 9 plus 11
 * plus 14, and both {@code app/cbl/CBTRN02C.cbl:76-79} and {@code app/cbl/CBTRN01C.cbl:76-79}
 * declare a 16-byte key followed by a single 34-byte remainder. Three derivations agreeing on 50
 * matters because a 50-byte record decoded against a 49-byte belief is not a build failure, it is
 * a wrong account identifier.</p>
 *
 * <p>Assumptions: trailing {@code FILLER} is padding to the fixed record length and is not data,
 * so transformation rule T1 drops it and requires the drop be recorded. Its width is recorded
 * above for that reason, so the layout stays reconstructible from this type alone without opening
 * the copybook.</p>
 *
 * <h2>Baseline names a reader will otherwise mistake for transcription errors</h2>
 *
 * <p>Two naming artifacts sit in the file descriptions of this record, and neither is a mistake
 * made during this migration. {@code app/cbl/CBACT04C.cbl:72} names the middle field
 * {@code FD-XREF-CUST-NUM}, while {@code app/cpy/CVACT03Y.cpy:6} names the same nine-byte range
 * {@code XREF-CUST-ID}. At {@code app/cbl/CBACT04C.cbl:74} the same file description gives the pad
 * a name, {@code FD-XREF-FILLER}, where the copybook leaves it a bare {@code FILLER}. Both names
 * are carried exactly as written wherever either is cited, because a citation whose text has been
 * tidied no longer locates the byte range it claims to.</p>
 *
 * <p>Assumptions: the Java member is {@code customerId}, following {@code XREF-CUST-ID} from the
 * copybook rather than {@code FD-XREF-CUST-NUM} from the file description. Transformation rule T1
 * makes the copybook the normative source, and the file descriptions demonstrate exactly why it
 * has to be: the three programs that read this record subdivide it three different ways in their
 * own file sections, so a mapping that followed a program's declaration would depend on which
 * program was consulted. All three nonetheless read into the copybook structure -- at
 * {@code app/cbl/CBTRN02C.cbl:383}, {@code app/cbl/CBACT04C.cbl:394} and
 * {@code app/cbl/CBTRN01C.cbl:229} -- so the copybook is the one declaration every reader of this
 * record actually shares.</p>
 *
 * <h2>Two live access paths, and why both must remain available</h2>
 *
 * <p>This is the one record in this package that the baseline reaches through two different keys,
 * and both are in use. A mapping that supported only one of them would leave one of the two
 * migrated jobs without the read it depends on, so the evidence for each is set out in full.</p>
 *
 * <p><b>Path 1, by card number, over the base cluster.</b> Posting declares the file at
 * {@code app/cbl/CBTRN02C.cbl:40-44} with {@code ORGANIZATION IS INDEXED},
 * {@code ACCESS MODE IS RANDOM} and {@code RECORD KEY IS FD-XREF-CARD-NUM} at line 43, and
 * declares <b>no alternate key at all</b>. Its lookup paragraph
 * {@code 1500-A-LOOKUP-XREF} spans {@code app/cbl/CBTRN02C.cbl:380-392}: line 382 moves the daily
 * transaction's card number into the key, line 383 reads, and a miss sets reject reason 100 at
 * line 385 with the text {@code 'INVALID CARD NUMBER FOUND'} at line 386. The driving job mounts
 * one cross-reference data definition, on the base key-sequenced cluster, at
 * {@code app/jcl/POSTTRAN.jcl:32-33}. The preflight reads the same way, declaring the same single
 * record key at {@code app/cbl/CBTRN01C.cbl:43} and naming it explicitly on the read at
 * {@code app/cbl/CBTRN01C.cbl:230}. In this mapping that path is the primary-key lookup.</p>
 *
 * <p><b>Path 2, by account identifier, over the alternate index.</b> Interest accrual declares the
 * same file at {@code app/cbl/CBACT04C.cbl:34-39} with the same record key at line 37 <b>and</b>
 * {@code ALTERNATE RECORD KEY IS FD-XREF-ACCT-ID} at line 38. Its paragraph
 * {@code 1110-GET-XREF-DATA} spans {@code app/cbl/CBACT04C.cbl:393-398} and names that alternate
 * key on the read itself, {@code KEY IS FD-XREF-ACCT-ID}, at line 395; the account identifier was
 * moved in at {@code app/cbl/CBACT04C.cbl:204}, immediately after the control break at line 194
 * detects a change of account. A miss displays {@code 'ACCOUNT NOT FOUND: '} with the identifier
 * at line 397. The driving job mounts a <b>second</b> data definition, {@code XREFFIL1}, on the
 * alternate-index path, at {@code app/jcl/INTCALC.jcl:31-32}. In this mapping that path is a
 * secondary-index query.</p>
 *
 * <p>Assumptions: the by-account path is served by the index {@code idx_card_xref_account_id},
 * which the migration plan records at its section 0.4.1.3 as the relational replacement for the
 * VSAM alternate index. That index is declared by {@code account-service}'s own migration,
 * {@code services/account-service/src/main/resources/db/migration/V1__account.sql}. <b>This type
 * depends on that index and must not declare it</b>; the DDL-passivity section below states why
 * and what declaring it would cost.</p>
 *
 * <p>Assumptions: the by-account path is <b>not unique in principle</b>, because one account may
 * hold more than one card, so a repository method serving it returns a collection or states an
 * explicit single-result contract rather than assuming at most one row. The baseline's own
 * behaviour on that point is narrower than it looks and has to be preserved rather than merely
 * matched: {@code app/cbl/CBACT04C.cbl:394-398} performs a <b>single</b> keyed read on the
 * alternate key, so it takes the first matching record in alternate-key order and never advances,
 * and {@code app/cbl/CBACT04C.cbl:495} then moves that record's card number onto every generated
 * interest transaction. Which card ends up on that transaction is therefore decided by the order
 * the read returns, so the by-account query has to be <b>deterministically ordered</b> and not
 * merely correct. A relational read returns rows in no guaranteed order unless one is requested,
 * and an unordered read here does not fail, it silently picks a different card between runs.</p>
 *
 * <p>Refactoring Rationale: the mainframe step that physically built the alternate index,
 * {@code IDCAMS BLDINDEX}, is retired outright rather than translated, under the migration plan's
 * transformation rule T6. PostgreSQL maintains an index transactionally as rows change, so there
 * is nothing left for a separate build step to do and a translated one would be a scheduled job
 * that accomplishes nothing. The distinction worth keeping straight is that the <b>key</b>
 * survives, as an index definition owned by another service, while the <b>build step</b> does
 * not.</p>
 *
 * <h2>Schema ownership, and why this mapping declares no DDL</h2>
 *
 * <p><b>{@code account.card_xref} is owned by {@code account-service}, not by this module.</b>
 * The only table this module owns is {@code batch.batch_run}. This mapping reaches its table
 * through a narrowly-scoped grant held by a dedicated database role and through nothing else, so
 * reading this type as though the batch context owned the cross-reference is the most consequential
 * misreading available here.</p>
 *
 * <p>Assumptions: the grant is read-only on this table, and it is worth stating precisely because
 * the batch role does hold a write grant elsewhere in the same schema.
 * {@code data-migration/sql/V0__schemas_and_roles.sql:768} grants {@code SELECT} on the tables of
 * the {@code account} schema, and line 779 grants {@code UPDATE} on {@code account.accounts}
 * <b>by name</b>. No insert, update or delete privilege on the cross-reference is granted to this
 * module at all. That matches the baseline exactly, where all three programs open the file for
 * input only -- {@code app/cbl/CBTRN01C.cbl:291}, {@code app/cbl/CBTRN02C.cbl:275} and
 * {@code app/cbl/CBACT04C.cbl:254} -- and no program issues a write or a rewrite against it. Rows
 * originate in {@code account-service} and, for a migrated load, in the extract-transform-load
 * reader {@code data-migration/src/carddemo_migration/readers/xref.py}.</p>
 *
 * <p>Assumptions: this mapping is DDL-passive. It declares no index, no unique constraint, no
 * explicit column definition and no key generation strategy, because the table, its columns and
 * its indexes are created by the owning service's migration and already exist by the time this
 * type is loaded. Hibernate is never permitted to emit DDL in this module: schema evolution
 * belongs to Flyway, and the JPA schema setting is at most an assertion against the shape that is
 * already there, never a generator of it.</p>
 *
 * <p>Trade-offs: the temptation to declare the by-account index on this very type is real, because
 * this module is that index's heaviest user -- interest accrual reads through it once per account
 * -- and declaring it here would put the dependency next to the code that needs it. It is refused
 * for two reasons that outweigh the convenience. It would have this module request an object in a
 * schema it holds no authority to alter, which surfaces either as a validation failure or as a
 * permission error, and neither of those names the actual mistake. And it would create a second
 * definition of one index, in a different repository from the first, with nothing able to detect
 * the two drifting apart. The cost accepted is that the dependency is documented here and
 * satisfied elsewhere, which a reader has to follow across two modules; the compensation is that
 * a missing index is a finding to raise with the owning service rather than something this type
 * can paper over.</p>
 *
 * <h2>Why the mapping is local rather than borrowed</h2>
 *
 * <p>Alternatives Considered: a Maven dependency on {@code account-service}, so that its existing
 * entity for this table could be reused instead of a second mapping being declared. Rejected on
 * two independent grounds. A service module importing another service module's {@code domain}
 * package is forbidden outright, and the prohibition belongs to the layering rules that the
 * {@code architecture-rules} Surefire execution in {@code services/pom.xml} selects by the simple
 * name {@code LayeringRulesTest}, so the import fails a build rather than drawing a review
 * comment. Independently of that test, a compile-time dependency between two independently
 * deployable services reintroduces exactly the coupling that bounded contexts exist to remove:
 * the two would then have to be built, versioned and released together.</p>
 *
 * <p>Alternatives Considered: hoisting a shared mapping upward into {@code common-lib} so one type
 * could serve every module that reads this table. Rejected because {@code common-lib} deliberately
 * ships no persistence at all -- neither the JPA starter nor a driver nor Flyway appears in its
 * POM, and its charter denies it a {@code domain} package -- so {@code jakarta.persistence} is
 * simply not on its classpath, by design rather than by omission. Adding it there to serve this
 * module would place an entity mapping in the one artifact every other module depends on, making a
 * schema change in one bounded context a rebuild of all nine.</p>
 *
 * <p>Trade-offs: two mappings over one table can drift, and nothing in the compiler notices. The
 * accepted mitigation is that both are asserted against the same physical schema at start-up, so a
 * drift that matters surfaces as a failing assertion rather than as a silent divergence. What is
 * genuinely given up is compile-time agreement between the two, and that is the price of
 * independent deployability: <b>this module and {@code account-service} agree through the physical
 * schema and never through code.</b></p>
 *
 * <h2>What this type deliberately does not do</h2>
 *
 * <p>Alternatives Considered: a {@code jakarta.persistence} association from this row to the
 * account it references, so a caller could traverse to the account object directly. Rejected for
 * two reasons. A managed association invites lazy-loading traversals across a schema boundary this
 * module holds only a scoped grant on, and the first such traversal to reach a table outside that
 * grant fails on a permission error that names a privilege rather than the design decision that
 * caused it. And it does not match how the work is actually done: the batch steps resolve by
 * identifier, exactly as the baseline performs a keyed read, so posting moves the resolved
 * identifier into an account key at {@code app/cbl/CBTRN02C.cbl:394} and reads again. An
 * association would model a join the baseline never performs.</p>
 *
 * <p>Assumptions: representation concerns are not this layer's. Fixed widths, sign overpunch,
 * packed decimal, the dropped pad and the baseline field-name spellings are confined to
 * {@code com.carddemo.batch.mapper}, and the byte-exact fixed-width rendering used for parity
 * comparison is produced by the job through {@code com.carddemo.common.codec.FixedWidthCodec}
 * rather than by anything on this type. Transcribed business rules live in
 * {@code com.carddemo.batch.service}, and data access in
 * {@code com.carddemo.batch.repository}.</p>
 *
 * <h2>Why the card number is STORED unmasked and RENDERED not at all</h2>
 *
 * <p>Trade-offs: the migration plan requires at its section 0.4.1.9 that a primary account number
 * be masked to its last four digits at API boundaries and never returned by a non-administrative
 * endpoint. This type holds the value in full, and that is deliberate rather than an oversight. A
 * cross-reference lookup by <b>complete</b> card number is precisely the read the posting job
 * performs, at {@code app/cbl/CBTRN02C.cbl:382-383}, so a masked or truncated value stored here
 * would not match the key it is looked up by and the lookup would report reject reason 100 for
 * every transaction. Masking the STORED value is therefore not available, and adding it here is
 * recognised as breaking the lookup rather than hardening it.</p>
 *
 * <p>Refactoring Rationale: what does NOT follow from that is that the value may be rendered
 * freely, and an earlier version of this note argued that it did on a premise that no longer
 * holds. It reasoned that this module "exposes no such interface at all: it declares no web
 * starter, opens no listener and returns nothing over a network", and therefore that no value on
 * this type leaves the process. Both {@code spring-boot-starter-actuator} and
 * {@code spring-boot-starter-web} are now declared in {@code services/batch-service/pom.xml}, so
 * the module does start a servlet container in order to answer the image health probe. The
 * conclusion the premise supported has been withdrawn with it: this type renders no card number in
 * its diagnostic string, which {@link #toString()} records, and the reason is no longer that a
 * rendering cannot escape but that a rendering is not needed.</p>
 *
 * <p>Assumptions: the surviving obligation on a consumer of this type is unchanged and is stated
 * here so it is not lost with the withdrawn premise. The value is available in full to code that
 * needs it for a keyed read, and any code that carries it towards an interface, a log, a message
 * attribute or a report must mask it there -- through
 * {@code com.carddemo.common.security.CardNumberMasker} for a rendering, or through
 * {@code com.carddemo.common.security.OpaqueIdentifier} where an identity rather than a value is
 * wanted. The mapper layer of each exposing service is where that obligation is discharged.</p>
 */
// WHY : Assumptions: the table this type maps is created by
//       services/account-service/src/main/resources/db/migration/V1__account.sql, alongside the
//       account master the sibling mapping reads. The sixteen-character key column, the two
//       identifier columns and the dropped fourteen-byte pad recorded below were each verified
//       against that migration applied to a live database. This module's grant on the table is
//       SELECT only, conveyed schema-wide by data-migration/sql/V0__schemas_and_roles.sql, so
//       unlike the account master there is no conditional per-table grant for it -- a
//       cross-reference is resolved, never rewritten.
// WHY : Refactoring Rationale: this block formerly recorded the dependency as UNSATISFIED, noting
//       that the migration was not yet authored and that account-service held no db/migration
//       directory. Both statements were true when written and neither is now. The note also
//       rejected authoring the migration from THIS module, on the ground that account-service's
//       own schema is the authority for every column in it; that objection is why the file was
//       authored under account-service rather than here, so the resolution followed the direction
//       of authority the note prescribed rather than overriding it. The resolution is recorded at
//       both mapping sites and in docs/architecture/data-model-and-schema-mapping.md, because a
//       reader arrives at one of them rather than at a common ancestor.
@Entity
@Immutable
@Table(name = "card_xref", schema = "account")
public class CardXref {

    /**
     * The number of trailing digits of a card number a diagnostic rendering may disclose.
     *
     * <p>Assumptions: four, taken from the migration's disclosure rule -- a primary account number is
     * rendered to its last four digits everywhere except the one administrative card-detail endpoint,
     * which this module is not. The constant is named rather than written into the rendering so that
     * the width and its justification sit together, and so that a reader can see the whole disclosure
     * decision of this type in one declaration.
     */
    private static final int CARD_NUMBER_SUFFIX_WIDTH = 4;

    // WHY : Assumptions: the column is a fixed-width CHAR(16) and not a VARCHAR(16). The copybook
    //       declares PIC X(16) and the field is a key, which the migration plan's section 0.4.1.3
    //       maps to a fixed-width character column precisely because the width is part of the
    //       contract rather than an upper bound. The baseline forms its key by moving a value into
    //       a fixed sixteen-byte key field -- app/cbl/CBTRN02C.cbl:382 moves the card number into
    //       FD-XREF-CARD-NUM, declared X(16) at app/cbl/CBTRN02C.cbl:78 -- so the key it presents
    //       is always exactly sixteen bytes, and a shorter stored value would not compare equal to
    //       it. A fixed-width column reproduces that by padding to the declared width, where a
    //       variable-width column would preserve the shortfall and miss the row.
    // WHY : Alternatives Considered: a numeric column and a numeric Java type. Sixteen digits fit
    //       a signed 64-bit integer, so this was a real option rather than a straw man, and it is
    //       refused on three counts. The copybook declares the field as character and not as
    //       numeric; the baseline performs no arithmetic on it anywhere, only moves and compares;
    //       and a numeric representation discards a leading zero, which changes the key and so
    //       changes which row is found. Holding it as text additionally keeps the value out of the
    //       arithmetic path altogether, which is where the exact fixed-point money contract and
    //       its prohibition on binary floating point apply.
    // WHY : Assumptions: the column is declared non-updatable because this value identifies the
    //       row. Rewriting it in place would not amend a cross-reference; it would silently
    //       reassign one card's account and customer to a different card number, and every
    //       subsequent lookup by the original number would then report the miss that posting
    //       turns into reject reason 100 at app/cbl/CBTRN02C.cbl:385.
    // WHY : Alternatives Considered: length = 16 alone was evaluated and rejected because a Java
    //       String otherwise selects JDBC VARCHAR, making schema validation reject the owning
    //       migration's CHAR(16) column even though the width agrees. Adding columnDefinition =
    //       "CHAR(16)" was also rejected: that would duplicate vendor DDL inside a mapping which has
    //       no authority to create this table. JdbcTypeCode selects the standard CHAR binding for
    //       reads, writes and validation while leaving the physical definition wholly with the
    //       account-service migration.
    /**
     * Maps {@code XREF-CARD-NUM} at {@code app/cpy/CVACT03Y.cpy:5}, {@code PIC X(16)}, offset 0. It
     * is the whole of the base cluster key -- {@code app/cbl/CBTRN02C.cbl:43} declares RECORD KEY
     * IS {@code FD-XREF-CARD-NUM} with no further component -- and therefore the whole of this
     * mapping's primary key.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "card_num", length = 16, nullable = false, updatable = false)
    private String cardNum;

    // WHY : Assumptions: BIGINT for a nine-digit identifier is a uniformity decision, and it is
    //       stated as one rather than dressed up as a capacity requirement. A 32-bit INTEGER holds
    //       nine digits comfortably and was the alternative considered; the migration plan's
    //       section 0.4.1.3 maps any PIC 9(n) serving as a key or identifier to BIGINT, so that
    //       every identifier across every schema has one width and a later widening of a source
    //       field never forces a column type change and the data movement that goes with it.
    /**
     * Maps {@code XREF-CUST-ID} at {@code app/cpy/CVACT03Y.cpy:6}, {@code PIC 9(09)}, offset 16.
     * The preflight displays it on a successful read, at {@code app/cbl/CBTRN01C.cbl:238}, so it is
     * a field the baseline observably uses rather than one carried here only to account for the
     * record's bytes.
     */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    // WHY : Assumptions: BIGINT is required here rather than merely uniform, and the two
    //       identifiers on this row arrive at the same SQL type for different reasons, which is
    //       worth knowing when reading either. Eleven digits exceed the range of a 32-bit INTEGER
    //       outright, so this column has no alternative where the customer identifier above had
    //       one and declined it.
    // WHY : Assumptions: this value is what the downstream category-balance key is composed from,
    //       which is the reason this mapping has to be settled before that one can be. Posting
    //       builds the three-part key at app/cbl/CBTRN02C.cbl:469-471 from XREF-ACCT-ID together
    //       with the daily transaction's type and category codes, and the create arm at
    //       app/cbl/CBTRN02C.cbl:505 composes the same key the same way. The account identifier on
    //       a category-balance row therefore comes from the CROSS-REFERENCE and never from the
    //       daily transaction record. Taking it from the transaction instead would key balances
    //       against the wrong account for any card whose cross-reference disagrees with an account
    //       field on the transaction, and the resulting balance would be a plausible number
    //       attached to the wrong account rather than a detectable error.
    /**
     * Maps {@code XREF-ACCT-ID} at {@code app/cpy/CVACT03Y.cpy:7}, {@code PIC 9(11)}, offset 25. It
     * is also the alternate key of the file, declared ALTERNATE RECORD KEY IS
     * {@code FD-XREF-ACCT-ID} at {@code app/cbl/CBACT04C.cbl:38}, which is what makes the
     * by-account read a keyed lookup.
     *
     * <p>Both migrated jobs that consume the daily file resolve through this field, and their
     * failure behaviours differ. The preflight resolves at {@code app/cbl/CBTRN01C.cbl:171-176} --
     * card number in, account identifier out, then a keyed account read -- and on failure SKIPS AND
     * REPORTS, displaying 'ACCOUNT ' with the identifier and ' NOT FOUND' at
     * {@code app/cbl/CBTRN01C.cbl:178}, or 'CARD NUMBER ' with the number and ' COULD NOT BE
     * VERIFIED. SKIPPING TRANSACTION ID-' with the transaction identifier at
     * {@code app/cbl/CBTRN01C.cbl:181-183}. Posting instead REJECTS, with reason 100 at
     * {@code app/cbl/CBTRN02C.cbl:385} when the cross-reference misses and reason 101 at
     * {@code app/cbl/CBTRN02C.cbl:397} when the account does. One mapping serves both, so neither
     * behaviour may be assumed from the other.</p>
     */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /**
     * Creates an empty instance for the persistence provider to populate.
     *
     * <p>Assumptions: the persistence specification requires a mapped class to declare a
     * constructor taking no arguments, which the provider invokes reflectively before writing the
     * three mapped columns into the fields above. Visibility is protected rather than public
     * because no caller outside this type's own hierarchy has a use for a half-built
     * cross-reference row: a provider-generated subclass reaches a protected constructor, while
     * application code cannot reach it by accident and so cannot produce a row with a null key
     * that the equality below would then have to treat as an identity. Protected is the widest
     * visibility the specification's requirement actually needs. </p>
     */
    protected CardXref() {
        // WHY : Assumptions: the body is empty by design rather than unfinished. The provider
        //       assigns all three mapped fields directly after construction on every load, so
        //       initialising any of them here would write a value that is overwritten before any
        //       caller could observe it.
    }

    /**
     * Creates a cross-reference row from a card number and the account and customer it resolves to.
     *
     * <p>Trade-offs: one complete constructor is provided and a builder is not. A builder was the
     * alternative and is refused because this row has three fields, no optional member and no
     * lifecycle, so there is no partially-specified state for a builder to accumulate. Adding one
     * would introduce a nested type carrying its own documentation obligation and its own
     * accessors, while removing no argument-ordering hazard that three parameters of two distinct
     * types meaningfully present. The compromise accepted is that a caller must supply all three
     * values at once, which is exactly what a row read from the cross-reference always has. </p>
     *
     * <p>Alternatives Considered: this constructor validates nothing, and rejecting an argument
     * was evaluated and refused. All three values originate in a table {@code account-service}
     * owns, so a guard here would make the batch context an arbiter of data it holds no authority
     * over, and would fail a read of a row the owning service wrote deliberately. The structural
     * obligations are instead expressed where they are enforced uniformly for every writer: both
     * identifier columns are declared not-null above, so the database refuses a row no owner
     * should have written, and this type stays a mapping rather than becoming a validator. </p>
     *
     * @param cardNum the String containing the sixteen-character card number that keys this row,
     *     declared as
     *     {@code XREF-CARD-NUM PIC X(16)} at {@code app/cpy/CVACT03Y.cpy:5} and carried in the
     *     {@code card_num} column; it is the value posting presents on its lookup at
     *     {@code app/cbl/CBTRN02C.cbl:382-383}
     * @param customerId the Long identifier of the customer holding this card, derived from the
     *     nine-digit field
     *     {@code XREF-CUST-ID PIC 9(09)} at {@code app/cpy/CVACT03Y.cpy:6} and carried in the
     *     {@code customer_id} column
     * @param accountId the Long identifier of the account holding this card, derived from the
     *     eleven-digit field
     *     {@code XREF-ACCT-ID PIC 9(11)} at {@code app/cpy/CVACT03Y.cpy:7}, carried in the
     *     {@code account_id} column, and the value the category-balance key is composed from at
     *     {@code app/cbl/CBTRN02C.cbl:469}
     */
    public CardXref(String cardNum, Long customerId, Long accountId) {
        this.cardNum = cardNum;
        this.customerId = customerId;
        this.accountId = accountId;
    }

    /**
     * Returns the sixteen-character card number this cross-reference row is keyed by.
     *
     * @return the String value of the {@code card_num} column, in full and unmasked, which is the
     *     key posting presents on its lookup at {@code app/cbl/CBTRN02C.cbl:382-383}
     */
    public String getCardNum() {
        return cardNum;
    }

    /**
     * Returns the identifier of the customer this card belongs to.
     *
     * @return the Long value of the {@code customer_id} column, which the preflight displays on a
     *     successful read at {@code app/cbl/CBTRN01C.cbl:238}
     */
    public Long getCustomerId() {
        return customerId;
    }

    /**
     * Returns the identifier of the account this card belongs to.
     *
     * @return the Long value of the {@code account_id} column, which is the account identifier
     *     every downstream posting write is keyed by, rather than any account field carried on
     *     the transaction being posted
     */
    public Long getAccountId() {
        return accountId;
    }

    /**
     * Reports whether another object denotes the same cross-reference row as this one.
     *
     * <p>Alternatives Considered: equality rests on the card number alone, and including either
     * identifier was evaluated and rejected. Two rows may legitimately carry the same account
     * identifier, because one account can hold more than one card -- which is the same property
     * that makes the by-account query non-unique -- so an equality that took the account into
     * account would still have to fall back to the card number to separate them, while an
     * equality that took only the account would report two different cards equal. The card number
     * is the whole of the base cluster key, as {@code app/cbl/CBTRN02C.cbl:43} declares, so it
     * alone settles which row is meant. </p>
     *
     * <p>Assumptions: the key is a natural one, assigned from the source record rather than
     * generated by the database, so it is populated from construction onward. This comparison
     * therefore does not face the null-identity problem a generated key would present, where two
     * not-yet-persisted instances share a null key and compare equal to each other. </p>
     *
     * <p>Assumptions: the test is a pattern match rather than an exact-class comparison, because a
     * persistence provider may return a generated subclass of a mapped type, and an exact-class
     * comparison would then report two representations of one row unequal. </p>
     *
     * @param other the Object to compare against, which may be of any type and may be null
     * @return the boolean value true when the argument is a cross-reference row carrying an equal
     *     card number, and false otherwise
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CardXref that)) {
            return false;
        }
        return Objects.equals(this.cardNum, that.cardNum);
    }

    /**
     * Returns a hash drawn from the same card number the equality above compares.
     *
     * <p>Assumptions: the hash is derived from exactly the field equality uses and from no other,
     * which is the contract the two methods share rather than a preference. Mixing in an
     * identifier that equality ignores would let two objects compare equal while hashing
     * differently, so a hash-based collection could hold both as distinct members and a lookup by
     * an equal instance could miss the entry already in it. </p>
     *
     * @return the int hash of the card number, or zero when no card number has been populated yet
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(this.cardNum);
    }

    /**
     * Returns a diagnostic rendering carrying the last four digits of the card number and nothing
     * else.
     *
     * <p>Refactoring Rationale: an earlier revision rendered all three members in full, on the
     * reasoning that this module declares no web starter and opens no listener, so a rendering
     * produced here reaches a log this context controls rather than a client. The premise is true and
     * the conclusion does not follow, for two reasons the premise does not address. First, a log is
     * not a private channel: it is retained, aggregated, searched and readable by every holder of log
     * access rather than of account access, and a batch job renders one line per record across an
     * entire daily feed, so the aggregate is a file holding every card number in that feed. Second,
     * and specific to this type: a cross-reference row's whole content IS the linkage between a card
     * number, a customer and an account, so rendering all three in full does not merely disclose three
     * values -- it reproduces {@code CARDXREF} itself, in plain text, in a second place that no
     * migration control governs. The sibling {@code com.carddemo.batch.domain.Transaction} in this
     * same package already withholds its card number for the first of those reasons; this type now
     * agrees with it.
     *
     * <p>Trade-offs: the last four digits do not identify the row uniquely, so a reader diagnosing
     * from this string alone may have to disambiguate between rows sharing a suffix. That is accepted
     * because the alternative is the aggregate described above, because the step and run identifiers
     * already in the logging context say which unit of work an entry belongs to, and because the three
     * accessors above return the full values to a caller that needs them. Rendering nothing at all was
     * also available and is declined: a rendering that identifies no row is of no diagnostic use, and
     * the suffix is the same rendering the migration's own published contracts expose.
     *
     * <p>Assumptions: this string is a diagnostic aid and is not an output contract. Nothing parses
     * it, and it is not the byte-exact fixed-width rendering used for parity comparison -- that is
     * produced by the job through {@code com.carddemo.common.codec.FixedWidthCodec} from the
     * members above -- so narrowing, widening or reformatting this string cannot disturb any
     * compared output. </p>
     *
     * @return a String containing a single-line rendering naming the type and the last four digits of
     *     the card number, or the absence of a card number when none has been populated
     */
    @Override
    public String toString() {
        return "CardXref[cardNumSuffix=" + cardNumberSuffix() + "]";
    }

    /**
     * Renders the last four digits of the card number for diagnostic use.
     *
     * <p>Assumptions: four is the suffix width the migration's disclosure rule names, and a value
     * shorter than four digits is rendered as absent rather than in part, because a partial value from
     * a short or unpopulated field would disclose the whole of whatever it holds while reading like a
     * suffix of something longer.
     *
     * @return exactly four digits, or {@code "none"} when no card number of at least four digits has
     *     been populated
     */
    private String cardNumberSuffix() {
        return cardNum == null || cardNum.length() < CARD_NUMBER_SUFFIX_WIDTH
                ? "none"
                : cardNum.substring(cardNum.length() - CARD_NUMBER_SUFFIX_WIDTH);
    }
}
